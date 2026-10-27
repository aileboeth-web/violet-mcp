# 原项目架构与安全边界审查 / 修改方案

审查对象：<https://github.com/Cazriel/mcp-server>（commit `42113fa`）
审查日期：2026-10-09
目标：改造为供 Project Eve 使用的 **Android 只读 MCP Server**。

---

## 1. 原项目架构

```
MainActivity / SettingsActivity / ToolsActivity / BridgeActivity / AboutActivity   (Compose UI)
        │ startForegroundService
        ▼
McpServerService (前台服务 dataSync, START_STICKY) ──► BoreClient (bore.pub 明文 TCP 隧道)
        │                                         └─► FloatWindowService (悬浮窗)
        ▼
McpHttpServer (手写 HTTP/1.1, 0.0.0.0:1145)
   ├─ POST /mcp      → McpHandler (JSON-RPC) → ToolKit → 46 个 ToolDefinition
   │                                          └─► BridgeManager（转发到远端 MCP）
   ├─ GET  /sse      （旧 HTTP+SSE 传输的不完整实现）
   ├─ GET  / /status / /info / /meta / /health / /tools   （均不鉴权）
BootReceiver（BOOT_COMPLETED → 启动服务）
```

| 组件 | 作用 | 处理 |
|---|---|---|
| `McpHttpServer` | Streamable HTTP 传输 | **保留并重写**为 `mcp-core/LocalHttpServer`（修复下列缺陷） |
| `McpHandler` / `RpcMessage` | JSON-RPC / MCP 方法分发 | **保留并重写**为 `mcp-core/McpProtocol` |
| `ToolKit` / `ToolDefinition` / `ParamValidator` / `ErrorCodes` | 工具注册、参数校验 | **保留**，迁入 `mcp-core`，增加只读白名单 |
| `McpServerService` | 前台服务、保活 | **保留**，FGS 类型改为 `specialUse` |
| `BootReceiver` / `WakeLockHelper` / `LogStore` | 开机自启、唤醒锁、日志 | **保留**（LogStore 增加密钥脱敏） |
| `SystemTools` / `AppTools` / `MetaTools` / `UtilityTools` 中的只读工具 | 设备/电量/存储/屏幕/应用信息 | **保留**（去掉序列号等敏感字段） |
| `FileTools` / `Workspace` | 文件读写删改、压缩、下载 | 删除（写入类；读取需 `MANAGE_EXTERNAL_STORAGE`） |
| `ScriptTools`（shell / Shizuku / root / QuickJS） | 任意命令与脚本执行 | 删除（含 Shizuku、QuickJS 依赖与 Provider） |
| `NetTools` | 任意 HTTP 请求、短链接 | 删除（可作 SSRF / 数据外传通道） |
| `CommunicationTools` | 剪贴板读写、发送通知 | 删除（写入设备状态；剪贴板后台读取在 Android 10+ 本就不可用） |
| `CryptoTools`、`json_format`、`text_convert` | 纯计算工具 | 删除（与手机感知无关，LLM 自身可完成） |
| `stop_app` / `running_processes` | 强杀应用 / 进程列表 | 删除（前者修改状态；后者在 Android 7+ 只能看到自身进程） |
| `batch` / `continue` | 批量调用 / 批量读文件 | 删除（`batch` 绕过分发层校验，`continue` 可读任意路径文件，**无工作区校验**） |
| `BridgeManager` / `BridgeActivity` / `BridgeToolsActivity` | 把远端 MCP 的工具合并暴露 | 删除（远端工具能力不受本机只读约束） |
| `BoreClient` | bore 内网穿透 | 删除，替换为 TLS 中继（见 §3） |
| `FloatWindowService` | 悬浮窗 | 删除（需要 `SYSTEM_ALERT_WINDOW`，非保活必需） |

以上删除的组件用途均已确认；没有用途不明的核心组件被删除。

## 2. 安全问题清单（原项目）

| # | 严重度 | 问题 | 位置 |
|---|---|---|---|
| S1 | 严重 | **鉴权默认关闭**（`token_enabled` 默认 `false`），服务监听 `0.0.0.0`，同网段任何人可调用 shell 等工具 | `Settings.tokenEnabled` |
| S2 | 严重 | **`GET /` 和 `/status` 未鉴权且返回明文 token**（`Net.statusJson` 写入 `"token"`），开启鉴权也会被直接读走 | `Net.statusJson` |
| S3 | 严重 | `shell`（app / Shizuku / root）、`mcp_javascript`、文件删改、`stop_app` 等可直接修改设备 | 各 Tools |
| S4 | 高 | `continue` 工具不做工作区校验，可读取任意可访问文件 | `MetaTools.continueBatch` |
| S5 | 高 | `http_request` / `web_download` 可从手机发起任意请求（SSRF、访问内网、外传数据） | `NetTools` / `FileTools` |
| S6 | 高 | bore 隧道为**明文 TCP**，公网端口随机分配且任何人可连；Bearer token 明文经过公网；`bore.pub` 公共服务无认证 | `BoreClient` |
| S7 | 高 | `/tools`、`/info` 未鉴权，公开完整工具清单 | `McpHttpServer` |
| S8 | 中 | token 只有 16 位十六进制（64 bit），比较非常量时间；还接受 `md5(token)` 作为等价凭证 | `McpHttpServer.authorized` |
| S9 | 中 | 桥接可把远端 MCP 的任意工具暴露给客户端，绕过本地权限模型 | `BridgeManager` |
| S10 | 中 | `Access-Control-Allow-Origin: *` 且不校验 `Origin`，存在 DNS rebinding 风险（MCP 规范要求校验 Origin） | `McpHttpServer` |
| S11 | 中 | 工具调用日志记录参数前 120 字符（可能包含敏感数据） | `McpServerService` |
| S12 | 中 | `allowBackup=true`，token 等配置会进入系统/ADB 备份 | Manifest |
| S13 | 低 | `Content-Length` 按字符而非字节读取，含中文的请求体会卡住或截断；无请求体大小上限；线程池无上限 | `McpHttpServer` |

### MCP 协议兼容性问题（影响 Python MCP SDK 1.x）

| # | 问题 |
|---|---|
| P1 | 客户端带 `Accept: application/json, text/event-stream`（Python SDK 默认如此）时，服务器返回 SSE 后**不关闭连接**并每次生成新的 `mcp-session-id`；旧版 SDK 会一直等待流结束 |
| P2 | 通知（如 `notifications/initialized`）返回 200 + `{"id":null,...}`，规范要求 `202 Accepted` 且无响应体 |
| P3 | `protocolVersion` 原样回显客户端版本，不做协商 |
| P4 | 未知工具返回 `-32601`；规范示例为 `-32602` |
| P5 | `GET /mcp`、`DELETE /mcp` 返回 404，规范要求不支持时返回 405 |

### Android 平台问题

| # | 问题 |
|---|---|
| A1 | `targetSdk 35` + 前台服务类型 `dataSync`：Android 15 上 dataSync 每 24 小时最多运行 6 小时，超时后系统强制停止；且 Android 15 禁止从 `BOOT_COMPLETED` 启动 dataSync 前台服务 → **开机自启和长时间后台运行都会失效** |
| A2 | 声明了大量高权限：`MANAGE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`READ_PHONE_STATE`、`USE_FULL_SCREEN_INTENT`，以及暴露给其他应用的 Shizuku Provider |
| A3 | `usesCleartextTraffic=true` |

## 3. 远程连接：bore 评估与替代方案

**结论：bore 方案不满足要求**（S6）——没有 TLS；公共 bore.pub 无认证；把手机端口原样暴露到公网随机端口；token 默认关闭。

### 替代方案：手机主动连接 VPS 的 TLS WebSocket 中继

```
Project Eve (Python MCP SDK)                VPS: eve-mcp-relay (Go)                      Android 手机
        │  POST /mcp                                  │                                        │
        │  Authorization: Bearer <MCP token>          │                                        │
        ├────────────────────────────────────────────►│ ① 校验 Bearer：SHA-256 后与手机       │
        │  (同机 127.0.0.1 明文；跨机可开 TLS)        │    上报的哈希做常量时间比较           │
        │                                             │ ② 封装为 http_request 帧               │
        │                                             │═══════ wss://（TLS，手机主动连出）═════│
        │                                             │   设备认证：Authorization: Bearer      │
        │                                             │   <device secret>；手机可固定证书      │
        │                                             │                                        │ ③ 本机再次校验 MCP token
        │                                             │                                        │ ④ McpProtocol → 只读工具
        │◄────────────────────────────────────────────┤◄═══════════ http_response 帧 ══════════│
```

- **不依赖手机公网 IP**：手机主动向 VPS 发起出站 `wss://` 连接，NAT / 移动网络都可用；不需要 VPN。
- **TLS**：设备通道强制 `wss://`（应用拒绝 `ws://`）。VPS 可以用 Let's Encrypt 证书（系统 CA 校验 + 主机名校验），也可以用中继自动生成的自签证书，在手机上填 **SPKI SHA-256 证书指纹**做证书固定。
- **双重认证**：
  - 手机 → 中继：独立的 device secret（中继只保存其 SHA-256）。
  - Eve → 中继 → 手机：MCP Bearer token。中继用手机上报的 token 哈希拦截未认证请求（未认证流量不会进入手机）；手机端再做一次常量时间校验。中继不保存明文 token。
  - 手机未连接时中继返回 503，未认证时返回 401；中继本身没有任何工具或工具清单。
- **断线重连**：指数退避（1 s → 60 s，带抖动）；监听 Android 默认网络变化（Wi-Fi ↔ 移动数据）后立即重连；双向 WebSocket ping（20 s），任一方向 60 s 内无响应就判定断线。
- **日志**：手机和中继都不记录 Authorization 头、token、device secret 和请求体；LogStore 在写入前对当前 token / secret 做脱敏兜底。
- 对 Eve 来说中继就是普通的 Streamable HTTP MCP 端点，`MCP_SERVERS` 只需填 URL 和 Authorization 头，**Eve 代码无需修改**。

## 4. 修改方案

### 4.1 模块划分

| 模块 | 内容 |
|---|---|
| `mcp-core`（纯 Kotlin/JVM，不依赖 Android） | HTTP 解析与本地服务器、MCP/JSON-RPC 协议、鉴权、只读白名单注册表、参数校验、中继客户端、使用时长聚合算法。可以在电脑上用 JVM 单元测试和 Python SDK 直接测试同一份代码 |
| `app`（Android） | 只读工具实现、UsageStats 工具、前台服务、开机自启、网络变化监听、UI |
| `relay`（Go） | VPS 中继 `eve-mcp-relay` |
| `tests/interop`（Python） | 用 Python MCP SDK 1.x 做的兼容性和安全测试 |

### 4.2 只读白名单（双重限制）

1. `ReadOnlyPolicy.ALLOWED_TOOLS` 是写死在代码里的白名单；`ToolRegistry.register()` 遇到白名单外的名字**直接抛异常**（启动即失败，不会悄悄注册）。
2. `tools/list` 和 `tools/call` 都只查这份注册表；白名单外的名字（包括原项目的 `shell`、`write_file` 等）一律返回与“未知工具”相同的 `-32602` 错误，不暴露工具是否曾经存在。
3. 写入类工具的源码、Shizuku/QuickJS 依赖都已删除；测试会检查 APK 里没有这些类，也没有 `Runtime.exec` / `ProcessBuilder` 调用。
4. 每个工具都带 `annotations.readOnlyHint=true`、`destructiveHint=false`。

### 4.3 新增感知工具（UsageStatsManager）

| 工具 | 数据源 | 新鲜度规则 |
|---|---|---|
| `get_foreground_app` | `queryEvents`（ACTIVITY_RESUMED/PAUSED、SCREEN_*、KEYGUARD_*）+ PowerManager / KeyguardManager | 只有“亮屏 + 未锁屏 + 该应用最后一次事件是 RESUMED 且之后没有 PAUSED”才标记为 `status=current`；否则为 `recent`（最近一次在前台，不代表当前），并给出事件发生时间和距今秒数 |
| `get_app_usage_stats` | 按事件配对计算区间内前台时长（可选系统聚合 `queryAndAggregateUsageStats`） | 返回统计区间、查询时间、最早可用事件时间；数据只统计到查询时刻；区间早于系统保留的事件时会注明“数据不完整” |
| `get_screen_state` | PowerManager.isInteractive、Display.state、KeyguardManager | 实时读取（`realtime=true`）；如有使用情况权限，附带最近一次亮/灭屏事件时间 |

需要用户在系统设置里手动授予“使用情况访问权限”（`PACKAGE_USAGE_STATS`，AppOps），不需要 Root，也不需要无障碍服务。

### 4.4 权限调整

| 权限 | 处理 | 原因 |
|---|---|---|
| `INTERNET`、`ACCESS_NETWORK_STATE` | 保留 | 本地服务器、中继、网络变化监听 |
| `FOREGROUND_SERVICE`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 保留 | 后台运行、开机自启 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 改为 `FOREGROUND_SERVICE_SPECIAL_USE` | 见 A1 |
| `PACKAGE_USAGE_STATS` | 新增 | UsageStatsManager（用户手动授权） |
| `MANAGE_EXTERNAL_STORAGE`、`READ/WRITE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`READ_PHONE_STATE`、`USE_FULL_SCREEN_INTENT`、`ACCESS_WIFI_STATE` | 删除 | 对应工具已删除；包可见性改用 `<queries>` 只声明桌面启动器 intent |
| Shizuku Provider | 删除 | — |
| `usesCleartextTraffic` / `allowBackup` | 改为 `false` | S12、A3 |

### 4.5 本地 HTTP 端点

- 始终要求 Bearer token（不可关闭）；token 改为 256 bit 随机值；常量时间比较；去掉 md5 等价凭证。
- 默认只监听 `127.0.0.1`（可配合 `adb forward` 调试）；局域网监听需在设置中手动开启。
- 带 `Origin` 头的请求一律拒绝（403），去掉 CORS。
- 只保留 `POST /mcp`、`GET /health`（只返回 `{"ok":true}`）、`GET /status`（需鉴权，不含任何密钥）；`GET/DELETE /mcp` 返回 405；去掉 `/sse`、`/tools`、`/info`。
- 请求体按字节读取，上限 1 MiB，请求头上限 16 KiB，固定大小线程池。
- 无状态 Streamable HTTP：请求一律返回 `application/json`，通知返回 202；协议版本按支持列表协商。
