# 权限与只读安全审计（1.2.0-eve / 中继 1.1.0）

审计日期：2026-10-10。第一轮审查见 [SECURITY_REVIEW.md](SECURITY_REVIEW.md)，本轮审查结论见 [REVIEW_v1.2.md](REVIEW_v1.2.md)。

## 1. 结论

| 项目 | 结论 | 依据 |
|---|---|---|
| 强制只读 | 通过（自动化） | 白名单写死在代码里；注册表拒绝名单外工具；调用前再查一次；APK 中没有命令执行、文件写入、强杀应用、发通知（工具）、写系统设置的调用 |
| 绕过手段（别名、大小写、批量、重复键、畸形请求、协议兼容入口） | 通过（自动化） | 46 个名字变体、批量、重复键、深层嵌套、非法 UTF-8、jsonrpc≠2.0、超大批量都被拒绝；原项目的 `/sse`、`/tools`、桥接已删除 |
| 第二层隐私开关 | 通过（自动化 + 模拟环境） | 默认只开 5 个工具；关闭的工具既不列出也无法调用；开关只能在手机界面修改 |
| 最小权限 | 通过（静态检查） | 9 个权限，全部有用途；可选权限只在用户点按钮时请求；只有启动器 Activity 和开机广播接收器是导出组件 |
| 监听安全 | 通过（自动化） | 手机本地端点只能绑定回环地址，代码层面拒绝其他地址；中继遇到“非回环地址且未开 TLS”时拒绝启动，并在绑定后按实际地址再检查一次 |
| 凭据存储 | 通过（模拟环境），**真机 Keystore 未验证** | 使用 AES-256-GCM（Android Keystore）；迁移失败时保留原值；无法解密时拒绝认证而不是悄悄重置 |
| 日志 | 通过（自动化） | debug 级别下中继和 harness 日志中都没有出现令牌或设备密钥；手机日志写入前做脱敏；导出的日志不含设备型号和使用记录 |
| 后台运行 | **真机未验证** | 只在模拟环境（Robolectric）中验证了状态机，见 [DEVICE_CHECKLIST.md](DEVICE_CHECKLIST.md) |

## 2. 执行入口逐一核查

| 入口 | 能做什么 | 结论 |
|---|---|---|
| `POST /mcp` → `McpProtocol` | 只有 `initialize`、`ping`、`tools/list`、`tools/call`；其他方法返回 -32601 | 只读 |
| `tools/call` → `ToolRegistry.resolve` | 只解析白名单内、已注册、且用户开启的工具；名字精确匹配 | 只读 |
| `GET /status`、`GET /health` | 返回运行状态，不含密钥 | 只读 |
| 中继帧（`hello_ack`、`ping`、`http_request`、`error`） | `http_request` 交给 `McpEndpoint`，走同一套鉴权和白名单；没有任何配置类帧 | 只读 |
| `MainActivity`（导出，启动器入口） | 不读取 intent extras | 不能被其他应用驱动执行操作 |
| `BootReceiver`（导出） | 只响应 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`，这两个都是受保护广播，其他应用无法伪造；1.1.0 中可被伪造的 `QUICKBOOT_POWERON` 已删除 | 只会恢复本服务 |
| `McpServerService`（未导出） | — | 其他应用无法启动 |
| 通知上的“停止”按钮 | immutable PendingIntent，只能停止本服务 | — |

### 应用自身（非 MCP 工具）的操作

以下操作都由用户在 App 界面上触发，或属于 App 自身的运行需要，不暴露给 MCP 客户端：

- 复制令牌到剪贴板
- 分享诊断日志（系统分享面板）
- 打开系统设置页（使用情况访问、电池优化）
- 前台服务通知
- 开机自启被系统拦截时，发出一条提醒通知

## 3. 工具副作用审计

除逐个阅读代码（见 REVIEW_v1.2 §3）外，`ToolsPolicyTest.everyToolRunsWithoutObservableSideEffects`（模拟环境）会开启全部 14 个工具，并逐个调用。调用前后检查以下几项，均无变化：

- 没有启动任何 Activity 或 Service
- 没有发送广播
- 没有发出通知
- 屏幕亮度设置没有被改动
- 应用目录中没有新增文件

| 工具 | 默认 | 敏感 | 需要的权限 | 说明 |
|---|---|---|---|---|
| `battery` | 开 | — | 无 | 读取电池粘性广播（不注册接收器） |
| `get_foreground_app` | 开 | — | 使用情况访问 | 默认只返回 state / app / package / observed_at / age_seconds；非 current 状态不给应用名 |
| `get_screen_state` | 开 | — | 无（使用情况访问可选） | 实时读取 PowerManager、Display、Keyguard |
| `screen_info` | 开 | — | 无 | 分辨率、刷新率、亮度设置 |
| `health` | 开 | — | 无 | 只含服务状态，不含密钥 |
| `get_app_usage_stats` | 关 | 是 | 使用情况访问 | 完整的使用记录 |
| `installed_apps` | 关 | 是 | 无 | 已安装应用列表 |
| `app_info` | 关 | 是 | 无 | 可探测某个应用是否安装 |
| `device_info` | 关 | 是 | 无 | 型号、指纹 |
| `system_properties` | 关 | 是 | 无 | 改为只用公开的 `Build` API，不再反射调用隐藏 API |
| `permission_state` | 关 | 是 | 无 | 本应用的权限配置 |
| `storage_info`、`locale_info`、`time_now` | 关 | — | 无 | 低敏感，但不属于默认开启范围 |

## 4. Android 权限

| 权限 | 何时需要 | 何时请求 |
|---|---|---|
| `INTERNET`、`ACCESS_NETWORK_STATE` | 中继连接、网络切换检测 | 安装时（普通权限） |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE` | 长期运行 | 安装时 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复（只有用户开启“开机自启”后才会生效） | 安装时 |
| `WAKE_LOCK` | 处理单个请求时持有短时 WakeLock（≤10 秒）；常驻 WakeLock 默认关闭 | 安装时 |
| `POST_NOTIFICATIONS` | 显示前台服务通知（可选） | 用户第一次点“启动”时 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 只针对本应用的“电池无限制”（可选），不要求关闭全局省电 | 用户在设置页点“申请”时 |
| `PACKAGE_USAGE_STATS` | 前台应用、使用时长 | 用户在系统设置里手动开启（AppOps） |

不需要的权限：Root、Shizuku、无障碍、所有文件访问、`QUERY_ALL_PACKAGES`、悬浮窗、`WRITE_SETTINGS`、电话、短信、定位、摄像头、麦克风。
`allowBackup=false` 且数据提取规则排除了全部数据；`usesCleartextTraffic=false`。

### `specialUse` 前台服务

- **适用性**：本应用需要长期保持一条到用户自己中继的连接，不属于 dataSync、mediaPlayback、location、connectedDevice 等任何预定义类型，因此使用 `specialUse` 并在 Manifest 中声明了用途说明（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）。
- **平台政策**：通过 Google Play 分发需要提交 `specialUse` 用途审核；本项目是自行安装（sideload）。
- **限制**：Android 15 起 `dataSync` 每天限 6 小时，并且不能从 `BOOT_COMPLETED` 启动，`specialUse` 不受这两条限制。但这**不等于永久存活**：
  - 用户可以在任务管理器中停止服务；
  - 系统在内存压力下仍可能回收进程；
  - 厂商系统可能限制后台运行或网络；
  - Android 12+ 可能拒绝在后台启动前台服务。
  这些情况在界面上会显示为“受限”或“已停止”，不会显示成“连接正常”。

## 5. 凭据

| 凭据 | 存储方式 | 轮换 | 撤销 |
|---|---|---|---|
| MCP 访问令牌（256 bit） | Keystore AES-GCM；Keystore 不可用时退回明文，并在界面上提示 | 安全页“更换令牌”（旧令牌立即失效，中继同步更新令牌哈希） | 安全页“全部清除” |
| 中继设备密钥 | 同上 | 在 VPS 上更换 device.secret，再在手机上填写新值 | 安全页“撤销远程凭据” |
| 中继证书指纹 | 明文（公开信息） | 支持同时填两个指纹，换证书时平滑过渡 | 同上 |
| 中继 device.secret（VPS） | 文件，权限 0600；只比较 SHA-256 | 删除文件后重启中继 | — |

**迁移**：1.1.0 的明文令牌在第一次读取时被加密，并验证能解出原值，之后才删除明文；令牌值本身不变，Project Eve 不需要改配置。

**兼容处理**：（1.2.0 的描述，已被 1.2.1 取代）1.2.0 在 Keystore 加密失败时会**静默**改用明文，并且在解密失败时会改用残留的明文副本。这两点在 1.2.1 已修复为失败即拒绝（fail closed），兼容模式必须由用户明确同意，详见 [SECURITY_RISK_v1.2.1.md](SECURITY_RISK_v1.2.1.md)。

## 6. 中继

- `-client-listen` 的地址必须是回环地址，或者同时开启 `-client-tls`，否则拒绝启动。绑定之后还会按实际地址再检查一次。
- VPS 上的 `127.0.0.1:8787` 只有本机进程能访问。同一台 VPS 上的其他本地用户能连上这个端口，但仍然需要令牌。
- 设备入口必须走 TLS。手机端只接受 `wss://`。证书指纹不匹配时进入长退避，并显示服务器出示的指纹供人工核对，**不会自动接受未知证书**。
- 只转发白名单内的请求头和响应头。`Server-Timing` 由中继重新组装，非数字值会被丢弃，防止响应头注入。
- 设备暂时离线、超时、处理途中断开时，返回 JSON-RPC 错误（HTTP 200，带原请求 id）。这样 Python MCP SDK 只让这一次调用失败，不会结束整个会话。鉴权失败仍然返回 HTTP 401。

## 7. 仍存在的风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 真机后台行为未验证 | 厂商系统、Doze、长时间熄屏、网络切换、开机自启 | 按 [DEVICE_CHECKLIST.md](DEVICE_CHECKLIST.md) 在真机上逐项验证 |
| Keystore 兼容性 | 个别机型的 Keystore 实现可能有缺陷 | （1.2.1 起）停用远程认证并醒目提示，只有用户明确同意后才进入兼容模式；迁移失败不会丢失配置 |
| 令牌在 TLS 终止后可见 | 中继能看到 Eve 发来的 Bearer 令牌 | 中继部署在用户自己的 VPS 上，并只监听本机 |
| 本地端点没有 TLS | `127.0.0.1` 上的同机其他应用可以尝试连接 | 仍然需要令牌；令牌为 256 bit 随机值，使用常量时间比较 |
| 中继重启时的 Eve 会话 | 本机测试中，同一个会话在中继重启后可以继续使用；真实网络下 HTTP 长连接断开的表现可能不同 | Eve 遇到连接错误时应重新建立会话（见 [EVE_INTEGRATION.md](EVE_INTEGRATION.md)） |
