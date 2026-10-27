# 第二轮审查（1.1.0-eve → 1.2.0-eve）

审查日期：2026-10-10。审查对象是工作区中的 1.1.0-eve 源码（`mcp-core`、`app`、`relay`、`tests`）。
本文先列出审查结论，第 5 节是本轮的修改清单。

## 1. 已实现、可以保留的功能

| 功能 | 位置 | 说明 |
|---|---|---|
| 代码级只读白名单 | `ReadOnlyPolicy`、`ToolRegistry`、`McpProtocol.callTool` | 注册时检查一次，调用时再检查一次。名字精确匹配，对大小写、空白、桥接前缀等变体都没有回退逻辑；批量请求逐条走同一个分发入口 |
| 写入类能力已从源码删除 | — | 没有 shell、Shizuku、QuickJS、文件写入、剪贴板写入工具、通知工具、`killBackgroundProcesses`；APK 静态检查也确认了这一点 |
| Bearer 鉴权 | `McpEndpoint`、`Secrets` | 令牌不能关闭，256 bit，常量时间比较；先校验再读请求体 |
| UsageStats 感知工具 | `UsageTools`、`UsageAggregator` | 事件配对和前台判定逻辑都有单元测试 |
| WSS 中继与证书固定 | `RelayClient`、`relay/` | 只接受 wss，SPKI 指纹固定，设备密钥和令牌哈希双重校验 |
| 指数退避 + 抖动、网络切换后立即重连 | `RelayClient`、`McpServerService` | 1→60 秒，×0.8–1.2 |
| 前台服务 `specialUse`、开机自启 | Manifest、`BootReceiver` | — |
| Python MCP SDK 1.8.0 / 1.30.0 兼容 | — | 无状态，返回 JSON，通知返回 202 |
| 备份排除、禁止明文流量 | Manifest | — |

## 2. 本轮要求中尚未实现的部分

| 要求 | 现状 |
|---|---|
| 工具第二层隐私开关（敏感工具默认关闭） | 现在所有工具默认开启 |
| 前台应用只返回最少字段 | 现在返回 `explanation`、`screen`、`freshness` 等一大批字段；熄屏或锁屏时会给出“最近一次前台应用”的包名 |
| 令牌加密存储、迁移、撤销凭据 | 现在以明文存放在 SharedPreferences（已排除备份，但 root 后或做设备取证时可以直接读到） |
| 不安全监听配置直接拒绝启动 | 手机端“局域网访问”会在 `0.0.0.0` 上用明文 HTTP 传输令牌；中继端 `-client-listen` 设成非本地地址时只打印一条 WARNING |
| 连接超时、心跳间隔可配置 | 现在写死在代码里 |
| 分阶段耗时 | 没有 |
| 三态服务状态（运行 / 被系统限制 / 已停止） | 只有 `running` 一个布尔值；进程被系统杀掉后界面显示“未运行”，无法和“用户主动停止”区分 |
| 诊断页、安全页、最近一次连接/调用信息 | 没有 |
| 证书更新流程 | 只能填一个指纹；换证书期间必然会断连 |

## 3. 代码中实际存在的问题

| # | 严重度 | 问题 | 位置 |
|---|---|---|---|
| R1 | 高 | 局域网模式用明文 HTTP 传输 Bearer 令牌，同网段的人可以嗅探到 | `McpServerService.restartLocal` |
| R2 | 高 | 中继 `-client-listen 0.0.0.0:8787` 在没有 TLS 时仍然会启动，令牌在公网上明文传输 | `relay/main.go` |
| R3 | 中 | 令牌和设备密钥明文存储 | `Settings.kt` |
| R4 | 中 | 用户点“停止”之后，应用更新广播（`MY_PACKAGE_REPLACED`）或 `START_STICKY` 重启仍会把服务拉起来，没有记录“用户期望的运行状态” | `BootReceiver`、`McpServerService` |
| R5 | 中 | 在 Android 12 及以上，`startForeground` 可能抛出 `ForegroundServiceStartNotAllowedException`（例如系统粘性重启发生在后台时），没有捕获，会导致崩溃 | `McpServerService.enterForeground` |
| R6 | 中 | 两台设备（或同一台设备的两个实例）使用同一组凭据时，会被中继互相替换。被替换的一方只按 1 秒起的退避重连，形成互相顶替的循环 | `RelayClient` |
| R7 | 中 | 证书指纹不匹配被当作普通网络错误，按 1 秒起的退避反复重试；界面也没有提示可能存在中间人 | `RelayClient.describe` |
| R8 | 中 | 没有网络时仍按退避不停尝试连接（会白白唤醒 CPU 和射频） | `RelayClient` |
| R9 | 中 | 网络连续抖动时，每次 `reconnectNow` 都会立刻新建连接，没有去抖 | `RelayClient.reconnectNow` |
| R10 | 中 | 手机端无法感知中继的 WebSocket 心跳（OkHttp 不暴露）。连接半开时，只能靠手机自己每 20 秒发 ping 来发现；而熄屏后这个计时器会暂停 | `RelayClient` |
| R11 | 中 | 中继等待手机响应的默认超时是 60 秒，比 Python SDK 默认的 HTTP 超时（30 秒）还长。手机半开连接时，Eve 先报客户端超时，拿不到明确原因 | `relay` |
| R12 | 中 | 极深嵌套的 JSON（例如 10 万层 `[`）会让 `JSONTokener` 栈溢出。`StackOverflowError` 不是 `Exception`，当前没有捕获 | `McpProtocol.handleBody` |
| R13 | 低 | 每个请求都要调用 `Settings.token()`，每条日志都要调用 `Settings.secrets()`；改成 Keystore 加密后，每次都会做一次解密 IPC | `McpEndpoint` / `LogStore` |
| R14 | 低 | `system_properties` 通过反射调用隐藏 API `android.os.SystemProperties` | `SystemTools` |
| R15 | 低 | 设置变更时，`RelayClient.stop()` 在主线程上最多阻塞 3 秒 | `McpServerService.restartRelay` |
| R16 | 低 | `AppLabels.homePackages` 每次调用都会查询 PackageManager | `AppTools` |
| R17 | 低 | “WakeLock 保持运行”开启后会无限期持有 PARTIAL_WAKE_LOCK；处理单个请求时反而没有短时 WakeLock | `WakeLockHelper` |

### 工具副作用逐个复核

| 工具 | 调用的系统接口 | 副作用 | 结论 |
|---|---|---|---|
| `battery` | `registerReceiver(null, BATTERY_CHANGED)` | receiver 为 null 时只读取粘性广播，不注册 | 只读 |
| `storage_info` | `StatFs` | 无 | 只读 |
| `screen_info` | `DisplayManager`、`Settings.System.getInt` | 无 | 只读 |
| `device_info`、`locale_info`、`time_now` | `Build`、`Locale`、`TimeZone` | 无 | 只读 |
| `system_properties` | 反射调用 `SystemProperties.get` | 无写入，但使用了非 SDK 接口 | 只读；本轮改成只用公开的 `Build` API |
| `app_info`、`installed_apps` | `PackageManager` 的查询方法 | 无（应用名缓存只在进程内存中） | 只读 |
| `get_foreground_app`、`get_app_usage_stats` | `UsageStatsManager.queryEvents` / `queryAndAggregateUsageStats` | 无 | 只读；但能看到完整的使用记录，需要隐私开关 |
| `get_screen_state` | `PowerManager`、`KeyguardManager`、`DisplayManager` | 无 | 只读 |
| `health`、`permission_state` | AppOps 检查、PowerManager | 无 | 只读 |

### 非工具入口

| 入口 | 结论 |
|---|---|
| `/health`、`/status` | 只读，不含密钥 |
| 中继帧 | 只处理 `hello_ack`、`ping`、`http_request`、`error` 四种，没有任何配置类帧 |
| 导出的组件 | 只有 `MainActivity`（启动器入口，不读取 intent extras）和 `BootReceiver`（只响应受保护的系统广播）。服务未导出；通知里的 PendingIntent 是 immutable |

## 4. 当前环境无法验证的项目

本机没有连接 Android 设备，也没有启用硬件虚拟化，无法运行加速的模拟器。以下项目只能列为“未验证”：

- 真实厂商系统上的长期后台存活、Doze、息屏网络、开机自启、FGS 启动限制
- Wi-Fi 与移动网络之间的切换、手机断网后恢复
- 使用情况权限被撤销后的真实系统行为（Robolectric 只能模拟 AppOps 的返回值）
- Android Keystore 在真机上的加解密（Robolectric 没有 AndroidKeyStore 提供者，只能测迁移逻辑和失败分支）

## 5. 本轮修改计划（按优先级）

1. **安全**：
   - 删除局域网明文监听；`LocalHttpServer` 遇到非回环地址直接抛异常，拒绝启动。
   - 中继在“非回环地址且未开 TLS”时拒绝启动。
   - 修复 R12 的深层嵌套 JSON。
   - `system_properties` 改为只用公开 API。
2. **隐私**：
   - 工具开关分两层，默认只开启 5 个工具，敏感工具单独授权。
   - `get_foreground_app` 默认只返回最少字段，并区分 current / screen_off / locked / unknown / permission_denied 几种状态。
   - 开关只能在 App 界面修改，MCP 请求无法修改。
3. **凭据**：
   - 用 Android Keystore 的 AES-GCM 加密令牌和设备密钥。
   - 从明文安全迁移：先加密并验证能解出原值，之后才删除明文；任何一步失败都保留原值。
   - 密钥丢失或损坏时拒绝认证并提示用户，不会悄悄生成新令牌。
   - 提供轮换、撤销远程凭据、清除全部认证配置三个操作。
4. **中继**：
   - 无网络时暂停重连；网络恢复时去抖后立即重连。
   - 被替换（4000）和证书指纹不匹配时进入长退避，并在界面上明确提示。
   - 支持两个证书指纹，用于更换证书时平滑过渡。
   - 心跳改为中继发起的应用层 ping，手机端增加看门狗。
   - 超时参数可配置；中继请求超时默认改为 20 秒；设备短暂离线时最多等 5 秒再返回 503。
5. **后台运行**：
   - 记录用户期望的运行状态，修复 R4。
   - 捕获前台服务启动失败（R5），并给出提示。
   - 界面显示三态服务状态和后台受限检测。
   - 只在处理单个请求时持有短时 WakeLock。
6. **性能**：
   - 中继和手机两侧都用单调时钟记录分阶段耗时，通过 `Server-Timing` 响应头和日志输出。
   - 日志级别可调。
   - 缓存令牌、桌面包名等，避免每次请求重复查询。
7. **界面**：
   - 主页增加状态信息。
   - 新增权限页（工具开关）、诊断页、安全页。
8. **测试与文档**：
   - 补充对应的单元测试，新增 Robolectric（模拟环境）测试，扩展 Python 端到端测试。
   - 更新文档，补充升级和回退说明。
