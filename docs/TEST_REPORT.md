# 测试报告

## Violet MCP 1.3.0（中继 1.2.0）— 2026-10-27

只改名（应用名、包名 `com.mcp.server.violet`、产物名、中继名和环境变量），工具和安全逻辑没有变化。

| 测试 | 结果 |
|---|---|
| mcp-core 单元测试（`:mcp-core:test --offline`） | 通过，64/64 |
| Go 中继 `go vet` / `go test -count=1 ./...` | 通过，20/20（新增 `TestDeviceSecretEnvironmentNewAndOldName`：旧环境变量 `EVE_RELAY_DEVICE_SECRET` 仍可用，新名优先） |
| 与 Violet 的静态契约（14 个工具三方一致、全部只读） | 通过 |
| Robolectric（`:app:testDebugUnitTest`，**模拟**） | 通过，38/38 |
| `:app:assembleDebug` / `:app:assembleRelease` | 通过；`aapt2 dump badging`：`com.mcp.server.violet`、versionCode 6、1.3.0、应用名“Violet MCP” |
| APK 静态检查（`tests/verify_apk.py`，debug 包） | 通过，失败 0 项 |
| 签名（`tools/sign_release.py`，用户同意） | 通过：Android 9+ 签名者 `d5a5eeec…`（与 1.2.2 相同的轮换后密钥），Android 8.x 为旧证书，lineage 旧 → 新 |
| 发布包（`tools/package_release.py --version 1.3.0 --relay-version 1.2.0`） | 通过：`dist/v1.3.0/`（APK、中继 linux-amd64 / arm64、源码交接包、SHA256SUMS），发布产物和交接包扫描问题 0 个 |
| PC 端到端 | 未执行 |
| 真机 | 无法验证（没有设备） |

## 1.2.2-eve（中继 1.1.0，未改动）— 2026-10-10

本轮修改：

- 签名密钥轮换（APK Signature Scheme v3 lineage）；
- 新增安全的源码交接打包（`tools/make_handoff.py`）和交接包扫描（`check_release_artifacts.py --handoff`）；
- 新增签名脚本 `tools/sign_release.py` 和签名配置模板；
- App 和中继**没有功能改动**，只把版本号改为 1.2.2-eve / versionCode 5。

下表的结果都是本轮在最终代码上**实际执行**得到的。

| 测试 | 命令 | 结果 |
|---|---|---|
| `mcp-core` 单元测试 | `gradlew :mcp-core:test --rerun-tasks` | **64/64 通过** |
| Android 单元测试（Robolectric，**模拟环境**） | `gradlew :app:testDebugUnitTest --rerun-tasks` | **38/38 通过** |
| Go 中继 | `go vet ./... && go test -count=1 ./...` | **19/19 通过** |
| Python MCP SDK 1.30.0 端到端 | `tests/run_pc_e2e.sh` | 经中继 **41/41**，直连 **41/41**，稳定性 **11/11**；不安全监听配置拒绝启动；relay.log / harness.log 中没有令牌和设备密钥 |
| APK 静态检查 | `tests/verify_apk.py` | debug 包 **45/45 通过**；release 包只有“检查对象是否未混淆”这一项按预期失败（与 1.2.1 相同） |
| 发布 APK 签名 | `apksigner verify -v --min-sdk-version 26` | 通过（v2 + v3）。Android 9+ 看到的签名者为新证书 `d5a5eeec…15ea`，Android 8.x 看到的为旧证书 `52f602d3…b499`；APK 内嵌 lineage 为 旧 → 新。`aapt2` 显示 versionCode 5 / versionName 1.2.2-eve |
| Gradle 签名保护 | `signing/` 中同时放 `rotation.properties` 和 `keystore.properties` | 构建直接失败并提示原因，防止只用旧密钥签名 |
| lineage 文件丢失后的恢复 | `apksigner sign --lineage <已发布的 1.2.2 APK>` | 签出的 APK 带有完整的 2 级 lineage |
| **全新环境构建**（交接包） | `tests/verify_handoff_build.sh <交接包> <SDK> D:/Claude/.handoff-fresh` | **全部通过**，详见下方 |
| 交接包扫描（**ZIP 本身**） | `make_handoff.build()` → `check_release_artifacts.py --handoff` | 0 个问题，详见下方 |
| 发布目录扫描 | `check_release_artifacts.py dist/v1.2.2-eve` | 0 个问题 |
| 历史产物复查 | `check_release_artifacts.py dist`；各目录 `sha256sum -c` | 0 个问题；1.1.0（dist 根目录）、1.2.0、1.2.1 的产物校验和全部一致，**未被覆盖** |
| 扫描器自检 | 在临时 zip 中植入真实密钥（改名后放进 zip 内的 jar）、真实口令（放进 zip 内的 tar.gz）、`sk-ant-` 格式的密钥、`.git/`、`build/` | 全部检出；干净的 zip 通过 |
| 防覆盖 | 对已存在的 `dist/v1.2.2-eve` 再次运行 `package_release.py`；对已存在的交接包再次调用 `make_handoff.build()` | 都拒绝执行，原文件不变 |

**全新环境构建**的具体内容：

- 交接包解压到空目录，`GRADLE_USER_HOME`、`GOMODCACHE`、`GOCACHE` 都是新建的空目录，所有依赖重新下载。下载经本机代理，通过 `JAVA_TOOL_OPTIONS` 提供，不属于源码。
- 结果：
  - `HANDOFF_MANIFEST.txt` 中 104 个文件的校验全部通过；
  - 包内没有 `signing/`、`.git/`、`local.properties`、缓存和构建目录；
  - 核心测试 64 项、Android 单元测试 38 项，失败 0；
  - debug 和 release 构建成功，没有签名配置时 release 包为未签名，符合预期；
  - 中继 `go vet` / `go test` 通过，Linux amd64 构建成功；
  - 按 `config-templates/keystore.properties.example` 填入一次性测试密钥后，release 包签名成功。
- 这项验证在 Windows（Git Bash）上执行；Linux / macOS 上**没有**实际运行过。

**用户此前提供的完整工作目录 ZIP**（`mcp-server.zip`）用交接模式复查的结果：

- 4437 个文件（含嵌套），共 3631 个问题；
- 其中密钥相关的有：`signing/eve-release.jks`（与本机逐字节相同，PKCS#12）、`signing/keystore.properties`（含本机真实口令）、`local.properties`；
- 其余是 `.git/`、`.gradle/`、`.kotlin/`、`build/`、`dist/` 下的文件；
- 没有发现其他令牌或 API 密钥。

**未验证**（没有连接手机，也无法运行模拟器）：

- 在真机上从 1.2.1 覆盖安装 1.2.2；
- 安装 1.2.2 后拒绝 1.2.1；
- Android 8.x 的行为。

见 DEVICE_CHECKLIST.md A8–A10。其余真机项与 1.2.1 相同，仍未验证。

---

## 1.2.1-eve（中继 1.1.0，本轮未改动）— 2026-10-10

本轮修改：凭据存储改为失败即拒绝（fail closed），兼容模式需要用户明确同意；新增发布包泄密检查和打包脚本。
下表中的结果都是本轮在最终代码上**重新执行**得到的；没有重新执行的项目单独列出。

| 测试 | 命令 | 结果 |
|---|---|---|
| `mcp-core` 单元测试 | `gradlew clean :mcp-core:test --rerun-tasks` | **64/64 通过** |
| Android 单元测试（Robolectric，**模拟环境**） | `gradlew :app:testDebugUnitTest --rerun-tasks` | **38/38 通过**（其中 SecretStoreTest 16 项，包括本轮新增和改写的） |
| 变异检查 | 把“解密失败时改用明文副本”的 1.2.0 写法放回去 | 有 2 个用例失败，说明能检出；之后已恢复 |
| Go 中继 | `go vet ./... && go test -count=1 ./...` | **19/19 通过** |
| Python MCP SDK **1.30.0** 端到端 | `tests/run_pc_e2e.sh` | 全部通过，详见下方 |
| APK 静态检查 | `tests/verify_apk.py`（debug 包） | **45/45 通过**；release 包只有“检查对象是否已混淆”这一项按预期失败 |
| 发布包泄密检查 | `tests/check_release_artifacts.py dist` | 16 个产物、579 个文件，**0 个问题**；植入真实密钥的自检能检出 |
| 打包脚本 | `tools/package_release.py --version 1.2.1-eve` | 成功生成产物，`sha256sum -c` 全部 OK；再次运行时拒绝覆盖；泄密检查不通过时会自动删除产物（误报修复前实际触发过） |
| 签名密钥轮换演练（一次性测试密钥） | `apksigner rotate` → `sign --lineage --rotation-min-sdk-version 28` → `verify` | 用 v3 方案校验通过，签名者为新证书 |

端到端测试的具体结果：

| 步骤 | 结果 |
|---|---|
| 中继配置为在 `0.0.0.0` 明文监听 | 拒绝启动 |
| 经中继访问 | 41/41 |
| 直连本地端点 | 41/41 |
| 稳定性测试 | 11/11（20 个并发调用全部成功；p95 3.8 ms；超时 5.0 s 后返回 -32011；中继重启后 1.0 s 重连；中继不可达时 2.8 s 失败） |
| 日志检查 | 无令牌和设备密钥 |
| 新手机 ⇄ 旧中继 1.0.0 | 38/38 |
| 旧手机 1.1.0 ⇄ 新中继 | 36/36 |

**本轮没有执行的测试（不能视为通过）：**

- 全部真机项目，见 [DEVICE_CHECKLIST.md](DEVICE_CHECKLIST.md)。其中新增的 G 节（凭据与 Keystore）和 H 节（OriginOS 等厂商系统）也都还没有执行；
- 真机上的 Android Keystore 加解密，以及 1.1.0 / 1.2.0 升级到 1.2.1 的迁移。Robolectric 没有 AndroidKeyStore，本轮测试用的是假加密器；
- Python MCP SDK 1.8.0 的端到端测试，本轮没有重跑（上一轮的结果见下文 §1.3.1）；
- 混淆后的 release APK 在真机上的运行。

---

# 1.2.0-eve 测试记录（上一轮，保留作历史）

日期：2026-10-10。以下数字均来自 1.2.0 代码的实际运行结果。

**结果分为三类：**

| 类别 | 含义 | 结论 |
|---|---|---|
| 自动化（电脑） | JVM 单元测试、Go 测试、Python MCP SDK 端到端、APK 静态检查。手机部分用 JVM harness 运行**与 App 相同的** `mcp-core` 代码，工具数据为模拟值 | 全部通过 |
| 模拟环境（Robolectric） | 在 JVM 上模拟 Android 框架，运行 App 的真实工具、服务和设置代码；系统事件、屏幕状态、AppOps 由测试注入 | 全部通过；**不代表真机** |
| 真机 | Android 手机上的后台存活、Doze、网络切换、开机自启、Keystore、厂商系统限制 | **未验证**（开发环境没有设备，也无法运行加速模拟器）。清单见 [DEVICE_CHECKLIST.md](DEVICE_CHECKLIST.md) |

## 1. 自动化（电脑）

### 1.1 `mcp-core` 单元测试：64 项全部通过（连续运行 3 次稳定）

```
gradlew :mcp-core:test
```

| 测试类 | 项数 | 覆盖 |
|---|---|---|
| `ToolRegistryTest` | 5 | 36 个已移除工具名注册即失败；白名单与移除名单不重叠；关闭的工具不可见、不可解析；只读注解 |
| `McpProtocolTest` | 10 | 协议版本协商；通知；id 类型；已移除工具 / 变体 / 关闭工具统一返回 -32602；参数和工具错误作为 isError 结果；批量请求 |
| `HardeningTest` | 8 | 本地端点拒绝绑定 `0.0.0.0`、局域网 IP、`::`、域名；20 万层嵌套 JSON 被安全拒绝；批量超过 32 条、jsonrpc≠2.0 被拒绝；11 种畸形请求体都不会执行工具；Server-Timing；请求统计中不含凭据；隐私默认值（敏感工具默认关闭、不可列出、不可调用）；多证书指纹解析与参数校验 |
| `HttpServerTest` | 15 | 通过真实 socket 测试：鉴权、Origin、路由、405/202、按字节读取中文请求体、chunked、413（含**真实发送 1 MiB 以上请求体**仍能收到 413 的回归测试）、提前 401 不被 RST、Content-Type、协议版本头 |
| `RelayClientTest` | 13 | 证书固定；转发与二次鉴权；Server-Timing 回传；40 个并发请求的 id 一一对应；应用层 ping/pong；看门狗在中继沉默时重连；指纹错误进入长退避并报告服务器指纹；第二个指纹用于换证书；未固定的自签证书被拒；设备密钥被拒；被顶替（4000）后长退避；中继断开后重连；10 次连续立即重连被去抖为 1 次；无网络时不发起连接、网络恢复后立即连接 |
| `UsageAggregatorTest` | 9 | 会话配对、应用内切换合并、区间裁剪、灭屏与关机、前台判定（熄屏 / 锁屏优先于事件推断） |
| `SecretsTest` | 6 | 令牌长度与随机性、Bearer 解析、脱敏、指纹解析、配置校验 |

### 1.2 Go 中继测试：19 项全部通过

```
cd relay && go vet ./... && go test ./...
```

覆盖：

- **认证**：设备密钥错误被拒绝；未认证请求、错误令牌、Origin 都在中继拦截，**不会到达手机**。
- **转发**：只转发白名单内的请求头和响应头；`/health` 只返回最少的信息；浏览器无法打开设备 WebSocket。
- **监听安全**：0.0.0.0、`:8787`、局域网 IP、`[::]`、域名在不开 TLS 时都拒绝启动；按实际绑定地址的二次检查同样有效；计时参数超出范围时拒绝启动。
- **离线与超时**：离线等待会接住正在重连的设备，并且等待时间有上限；设备离线、超时、处理途中断开都返回带原 id 的 JSON-RPC 错误（通知返回 202、批量逐条返回、无法解析时退回 HTTP 状态码、令牌错误仍为 401）。
- **心跳与探测**：心跳会通告间隔，不回 pong 的设备在读超时后被断开；请求超时后会探测连接，5 秒内无回应就断开。
- **并发与计时**：50 个并发请求的结果不串；Server-Timing 带各阶段耗时，并阻止非数字值注入响应头。
- **日志**：每个测试都在 **debug 日志级别**下断言中继日志里没有令牌和设备密钥。

### 1.3 Python MCP SDK 端到端（`tests/run_pc_e2e.sh`）：全部通过

链路：Go 中继（自签 TLS）⇄ JVM harness ⇄ Python MCP SDK 1.30.0。

| 场景 | 结果 |
|---|---|
| 中继在 `0.0.0.0` 上明文监听 | 拒绝启动（`refusing to start`） |
| 经中继：协议、鉴权、白名单、畸形请求（`test_mcp_interop.py`） | 41/41 |
| 直连手机本地端点 | 41/41 |
| 稳定性（`test_resilience.py`） | 11/11 |
| 日志泄露检查（中继、harness） | 均无令牌和设备密钥 |
| 混合版本 A：当前手机代码 ⇄ 旧中继 1.0.0 | 38/38（1.2 新增检查跳过） |
| 混合版本 B：旧手机代码 1.1.0 ⇄ 当前中继 | 36/36（1.2 新增检查跳过） |

41 项检查覆盖以下内容：

- **鉴权**：无令牌、错误令牌、Basic 方案都返回 401，且不泄露工具名；Origin 返回 403；未认证访问 `/tools` `/info` `/sse` `/` `/status` 都不暴露信息。
- **协议**：`/health` 最小化；GET 返回 405；通知返回 202；initialize 返回 JSON；协议版本协商。
- **畸形请求**：请求体超过 1 MiB 返回 413；10 万层嵌套 JSON、非法 UTF-8、jsonrpc 1.0、33 条的批量都被拒绝；重复键（`battery` 后跟 `shell`）不能执行 shell；批量调用写入类工具全部被拒绝。
- **SDK 行为**：没有令牌时 SDK 无法初始化；服务端只声明 tools 能力；tools/list 不超出白名单，且全部带只读注解；逐个调用已列出的工具；关闭的工具（`storage_info`）无法列出也无法直接调用。
- **写入类工具**：36 个已移除的工具名加 10 个名字变体，共 46 个，全部无法调用。

稳定性 11 项的实测值：

| 项 | 结果 |
|---|---|
| 同一会话 20 个并发 tools/call | 20/20，147 ms |
| Eve 侧单次调用延迟（本机中继） | p50 3.1 ms，p95 3.8 ms |
| Server-Timing | relay 0.8 ms，rtt 0.8 ms，phone 0.3 ms，tool 0.0 ms |
| 工具超过中继超时（5 s） | 5.0 s 后返回 `McpError -32011`，原因明确 |
| 超时后同一会话继续调用 | 成功 |
| 中继重启后手机自动重连 | 1.0 s |
| 中继重启后同一 MCP 会话 | 继续可用（本机网络下） |
| 中继完全不可达 | 客户端 2.7 s 内失败，不会挂住 |
| 中继恢复后手机重连 | 1.3 s |

### 1.3.1 用 Python MCP SDK 1.8.0 重跑同一套端到端测试（最终代码）

| 场景 | 结果 |
|---|---|
| 经中继 / 直连 | 41/41、41/41（协商协议 2024-11-05） |
| 稳定性 | 10/11 |

**唯一失败项是“中继完全不可达时客户端快速失败”：SDK 1.8.0 在连接被拒绝时不会把错误交给调用方，`initialize` 一直挂到调用方自己设置的超时（测试里是 30 秒）。**
这是旧版 SDK 客户端的行为，服务端无法修复；SDK 1.30.0 会在 2.7 秒内失败。

建议：

- Project Eve 使用 SDK ≥ 1.30；
- 或在调用外层用 `asyncio.wait_for` 设置超时，见 [EVE_INTEGRATION.md](EVE_INTEGRATION.md)。

### 1.4 APK 静态检查（`tests/verify_apk.py`）

对 debug 包运行（未混淆，能按类名定位），45 项全部通过，覆盖：

- 权限与清单完全一致（9 项），10 个高权限都已移除。
- 没有 Shizuku Provider，没有无障碍服务；`allowBackup=false`、`usesCleartextTraffic=false`、前台服务类型为 `specialUse`。
- APK 中没有 Shizuku、QuickJS、DocumentFile，也没有原项目的 Bridge、Bore、File、Script、Net、FloatWindow 相关类。
- 全应用范围内没有 `Runtime.exec`、`ProcessBuilder`、文件删除或写入、ContentResolver 写入、强杀应用、写系统设置。
- **工具包和协议核心中**没有以下任何调用：启动组件或发送广播、发通知、写剪贴板、写系统设置、改音量 / Wi-Fi / 蓝牙、执行进程、打电话或发短信、写文件。

对 release 包（R8 混淆）运行时，只有“工具类存在”这一项失败。这是检查脚本的预期行为：它借这一项确认被检查的包没有混淆。权限、组件和第三方库检查全部通过。

## 2. 模拟环境（Robolectric 4.14.1，Android SDK 34）：30 项全部通过

```
gradlew :app:testDebugUnitTest
```

| 测试类 | 项数 | 覆盖 |
|---|---|---|
| `ToolsPolicyTest` | 6 | App 实际注册的工具恰好等于白名单；新安装只列出 5 个工具，敏感工具直接调用被拒绝；开关立即生效；即使有人往开关里写入已移除的工具名也无效；**开启全部 14 个工具逐个调用后，没有启动 Activity / Service、没有发广播、没有发通知、亮度设置没变、没有写文件**；`system_properties` 不再含序列号 |
| `UsageToolsTest` | 9 | current 只返回 5 个字段且带应用名；切换应用后结果更新；熄屏、锁屏不给出应用；离开前台返回 unknown，不猜测；详细模式只在手机上开启后才给出 last_app；**撤销使用情况访问权限**后前台应用返回 permission_denied，使用时长返回 PERMISSION_DENIED；使用时长按事件计算并带应用名；屏幕状态为实时值 |
| `SecretStoreTest` | 8 | 从明文迁移并保持原值；迁移失败时保留明文并报错；无法解密时不悄悄替换；中断的迁移会被补完；没有 Keystore 时明确进入兼容模式；**从 1.1.0 升级后令牌不变**；全部清除会生成新令牌并删除中继凭据；撤销远程凭据会保留令牌 |
| `ServiceLifecycleTest` | 7 | 启动后本地端点只在 127.0.0.1 上提供服务（未带令牌返回 401）；通知上的“停止”会记录为用户主动停止；用户停止后，系统粘性重启不会把服务拉起；期望运行时粘性重启会恢复；被杀掉时显示为“受限”而不是“运行中”；开机恢复只在“开机自启开启且用户没有手动停止过”时发生，伪造的 QUICKBOOT 广播被忽略；APPLY 不会在服务未运行时启动前台服务；新安装显示为“已停止” |

**变异检查**：人为加入两个缺陷——把 `installed_apps` 设为默认开启，以及让“停止”不记录用户意图——相应的测试都会失败。说明这些测试确实能发现问题。

## 3. 未执行的测试

以下项目需要真机，**未验证，不能视为通过**：

- 真机上的前台应用和使用时长数据准确性、应用切换、锁屏、撤销权限后的系统行为
- 后台存活：划掉最近任务、任务管理器停止、厂商省电策略、长时间熄屏、Doze
- 开机自启（`BOOT_COMPLETED` 实际送达，以及 Android 15 上 `specialUse` 前台服务能否启动）
- Wi-Fi ↔ 移动网络切换、飞行模式、断网恢复、真实公网下中继重启后的恢复
- Android Keystore 在真机上的加解密，以及 1.1.0 升级时的令牌迁移
- R8 混淆后的 release 包在真机上的运行（本轮的 Robolectric 和 harness 测试都基于未混淆的代码）
- 两台手机使用同一组凭据时的行为（逻辑已在 `RelayClientTest` 和 Go 测试中验证）
- 在 Project Eve 中的实际接入，以及真实网络下的耗时分布

## 4. 已知限制

- 前台应用由 UsageStatsManager 事件推断，系统事件可能延迟数秒；通知栏、系统对话框等界面不产生 Activity 事件，此时返回 `unknown`。
- 使用时长统计不计灭屏期间；`method=events` 最多支持 10 天，更长的区间请用 `method=system`。
- 厂商系统（MIUI、ColorOS、HarmonyOS、OneUI 等）可能额外限制后台运行。界面会显示“受限 / 已停止”，但无法保证永久在线。
- 中继完全不可达时，Python SDK 会结束当前会话；Eve 需要在下次调用时重新建立会话。设备离线或超时不会结束会话。
- 旧版 Python SDK（例如 1.8.0）在中继不可达时会挂起，直到调用方超时（见 §1.3.1）。
