# 工具清单与权限说明（1.3.0）

服务器能暴露的工具只有下面 14 个**只读**工具，分两层控制：

1. **第一层：只读白名单**，写死在 [`ReadOnlyPolicy.kt`](../mcp-core/src/main/kotlin/com/mcp/server/core/ReadOnlyPolicy.kt)。`ToolRegistry` 拒绝注册名单外的工具，`tools/list` 和 `tools/call` 都只查这个注册表。
2. **第二层：隐私开关**，默认值在 [`PrivacyPolicy.kt`](../mcp-core/src/main/kotlin/com/mcp/server/core/PrivacyPolicy.kt)。用户在 App 的“工具与权限”页逐个开关；**只能在手机上修改**，没有任何 MCP 方法能改动它。关闭的工具不出现在 `tools/list`，直接调用时与未知工具一样返回 `-32602`。

每个工具在 `tools/list` 中都带 `annotations.readOnlyHint=true`、`destructiveHint=false`。

| 工具 | 默认 | 敏感 | 需要的 Android 权限 |
|---|---|---|---|
| `get_foreground_app` | **开** | — | 使用情况访问 |
| `get_screen_state` | **开** | — | 无（使用情况访问可选） |
| `battery` | **开** | — | 无 |
| `screen_info` | **开** | — | 无 |
| `health` | **开** | — | 无 |
| `get_app_usage_stats` | 关 | 是 | 使用情况访问 |
| `installed_apps` | 关 | 是 | 无 |
| `app_info` | 关 | 是 | 无 |
| `device_info` | 关 | 是 | 无 |
| `system_properties` | 关 | 是 | 无 |
| `permission_state` | 关 | 是 | 无 |
| `storage_info` | 关 | — | 无 |
| `locale_info` | 关 | — | 无 |
| `time_now` | 关 | — | 无 |

开启敏感工具时，App 会弹窗说明它会暴露哪些信息。

## 1. 手机感知

所有感知工具都基于 Android 官方 API（`UsageStatsManager`、`PowerManager`、`KeyguardManager`、`DisplayManager`），不使用 Root，也不使用无障碍服务。

### `get_foreground_app`

| 参数 | 说明 |
|---|---|
| `lookbackMinutes` | 向前查找系统事件的范围，1–1440，默认 60 |

默认只返回以下 5 个字段：

```json
{"state": "current", "app": "示例应用", "package": "example.package", "observed_at": "2026-10-10T10:02:11.000+08:00", "age_seconds": 2}
```

| state | 条件 | app / package |
|---|---|---|
| `current` | 亮屏、已解锁，且该应用进入前台后没有离开事件 | 给出 |
| `screen_off` | 屏幕熄灭（实时读取） | null |
| `locked` | 停在锁屏界面（实时读取） | null |
| `unknown` | 无法确定：应用已离开前台（例如停在通知栏）、查找范围内没有事件，或手机重启后尚未解锁 | null |
| `permission_denied` | 未授予使用情况访问权限（另附 `hint`） | null |

- `observed_at` 是支持该结论的最近一次系统事件时间，`age_seconds` 是它距今的秒数。
- `current` 状态下，`observed_at` 就是应用进入前台的时间；`age_seconds` 大只说明用户在这个应用里停留得久。
- 数据过期、屏幕锁定或权限不足时，只返回明确的状态，**不猜测当前应用**。
- 不返回活动历史和设备标识。

**详细模式**：用户在“工具与权限”页开启“前台应用：详细模式”后，结果会额外包含：

- `reason`、`explanation`
- `event_count`
- 非 current 状态下的 `last_app`、`last_package`、`last_seen_at`

### `get_app_usage_stats`（敏感，默认关闭）

| 参数 | 说明 |
|---|---|
| `range` | `last_15m` / `last_1h` / `last_3h` / `last_24h` / `today` / `yesterday` / `last_7d`，默认 `today` |
| `start`、`end` | 自定义区间。可以是 ISO-8601（如 `2026-10-09T08:00`、`2026-10-09T08:00+08:00`）、日期（如 `2026-10-09`）或 epoch 毫秒；`end` 默认现在 |
| `limit` | 1–100，默认 20 |
| `includeHomeScreen` | 是否把桌面计入列表，默认 false（桌面时长单独放在 `homeScreenMs`） |
| `method` | `events`（默认，按事件精确计算，最多 10 天）或 `system`（系统按天聚合，区间边界不精确） |

返回：

- `apps[]`：每个应用的 `appName`、`packageName`、`foregroundMs`、`foreground`、`sessions`、`lastUsed`、`inForegroundAtEnd`
- `totalForegroundMs`
- `range`
- `queryTime`
- `freshness`：`dataThrough`、`earliestEventInRange`、`possiblyIncomplete`、`note`

灭屏期间不计入使用时长。

### `get_screen_state`

| 字段 | 含义 |
|---|---|
| `screenOn` | `PowerManager.isInteractive` 的值 |
| `displayState` | `ON` / `OFF` / `DOZE` / `DOZE_SUSPEND` / `ON_SUSPEND` / `VR` / `UNKNOWN` |
| `keyguardLocked`、`deviceLocked`、`deviceSecure` | 锁屏相关状态 |
| `summary` | 一句话摘要 |
| `stateSince`、`stateDurationSeconds` | 当前亮屏或灭屏状态开始的时间。需要使用情况访问权限，且最近一次亮/灭屏事件必须与当前状态一致才会给出 |
| `queryTime`、`freshness` | `realtime=true` |

## 2. 设备信息

| 工具 | 内容 |
|---|---|
| `battery` | 电量、充电状态、电源、温度、电压、健康度、省电模式、Doze、电池优化豁免 |
| `screen_info` | 分辨率、密度、刷新率、亮度设置 |
| `storage_info` | 内部、外部、可移动存储的容量（不访问任何文件） |
| `locale_info` | 语言、时区 |
| `time_now` | 手机当前时间 |
| `device_info` | 品牌、型号、Android 版本、安全补丁、ABI、指纹（不含序列号、基带） |
| `system_properties` | 固定白名单内的 build 属性，**1.2 起只用公开的 `Build` API**，不再反射调用隐藏 API |

## 3. 应用信息（敏感，默认关闭）

| 工具 | 参数 | 说明 |
|---|---|---|
| `app_info` | `packageName`（必填） | 名称、版本、安装和更新时间、是否系统应用、是否可启动、是否桌面 |
| `installed_apps` | `keyword`、`includeSystem`、`limit` | 带桌面图标的应用 |

不申请 `QUERY_ALL_PACKAGES`。Manifest 只用 `<queries>` 声明了启动器和 HOME intent。

## 4. 服务器

| 工具 | 说明 |
|---|---|
| `health` | 版本、服务状态（`running` / `restricted` / `stopped`）、运行时长、中继阶段、最近一次成功连接、已开启工具数；不含任何密钥 |
| `permission_state`（敏感） | 使用情况访问、通知、电池优化豁免、WakeLock、开机自启的状态 |

## 5. 已删除的工具（无法列出，也无法调用）

| 类别 | 工具 |
|---|---|
| 命令 / 脚本执行 | `shell`（app / Shizuku / root）、`shizuku`、`mcp_javascript` |
| 文件修改与删除 | `write_file`、`edit`、`create_directory`、`copy_file`、`move_file`、`delete_file`、`touch`、`compress_zip`、`extract_zip`、`web_download`、`set_workspace` |
| 文件读取 | `get_workspace`、`list_files`、`read_file`、`file_info`、`compare_files`、`file_search`、`continue` |
| 应用控制 | `stop_app` |
| 剪贴板 / 通知 | `clipboard`、`send_notification` |
| 任意网络请求 | `http_request`、`shorten_url` |
| 绕过分发层 | `batch` |
| 其他 | `running_processes`、`check_permission`、`json_format`、`text_convert`、`base64_encode`、`decrypt_xor`、`help`、`tools_list`、`stats`，以及所有 MCP 桥接工具 |

用这些名字（包括大小写变化、首尾空白、桥接前缀、重复键等变体）调用，都会返回 `-32602 Unknown tool`。

## 6. Android 权限

| 权限 | 用途 | 何时请求 |
|---|---|---|
| `INTERNET` | 本地端点、TLS 中继 | 安装时 |
| `ACCESS_NETWORK_STATE` | 网络切换后立即重连；没有网络时暂停重试 | 安装时 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` | 长期运行（`specialUse`；dataSync 在 Android 15 上每天限 6 小时，且不能开机启动） | 安装时 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复（需用户开启“开机自启”，且没有手动停止过服务） | 安装时 |
| `WAKE_LOCK` | 每个请求最多持有 10 秒的短时唤醒锁；常驻 WakeLock 默认关闭 | 安装时 |
| `POST_NOTIFICATIONS` | 前台服务通知（可选） | 第一次点“启动”时 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 只针对本应用的“电池无限制”（可选），不要求关闭全局省电 | 用户在设置页点“申请”时 |
| `PACKAGE_USAGE_STATS` | 前台应用、使用时长 | 用户在系统设置中手动开启 |

另有 `com.mcp.server.violet.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，由 androidx.core 自动添加，只有本应用自己能使用。

不需要的权限：Root、Shizuku、无障碍服务、所有文件访问、`QUERY_ALL_PACKAGES`、悬浮窗、修改系统设置、电话、短信、定位、相机、麦克风。

其他设置：

- `allowBackup=false`，数据提取规则排除全部数据。
- `usesCleartextTraffic=false`。
- 只有启动器 Activity 和开机广播接收器是导出组件；开机接收器只响应受保护的系统广播。
