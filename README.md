<div align="center">

# Violet MCP（Android 只读手机状态服务）

[![License: GPL v3](https://img.shields.io/badge/License-GPL_v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?logo=android&logoColor=white)](#)
[![MCP](https://img.shields.io/badge/MCP-Streamable_HTTP-blue)](https://modelcontextprotocol.io/)

</div>

Violet MCP 是给 **[Violet](https://github.com/aileboeth-web/violet-companion)**（自己部署的 AI 陪伴）用的 Android 只读 [MCP](https://modelcontextprotocol.io/) 服务器。装在你的手机上以后，Violet 可以在你允许的范围内知道手机的状态——电量、屏幕亮没亮、正在用什么应用、今天用了多久——用来更懂你此刻在做什么。**它不能修改手机上的任何东西。**

修改自 [Cazriel/mcp-server](https://github.com/Cazriel/mcp-server)：保留 Streamable HTTP、Bearer 鉴权、后台运行和开机自启；删除所有会修改设备状态的能力；新增基于 `UsageStatsManager` 的感知工具；用“手机主动连接你自己 VPS 的 TLS 中继”取代原来的明文隧道。

当前版本：应用 **Violet MCP 1.4.0**（包名 `com.mcp.server.violet`），中继 **violet-mcp-relay 1.2.0**。1.4.0 新增应用内更新；1.3.0 由“Eve 只读 MCP 1.2.2-eve”改名而来，包名变了，旧版不能直接覆盖升级，见 [docs/UPGRADE.md](docs/UPGRADE.md)。

## 特点

- **只读**：14 个工具全部写死在白名单里。命令执行、Shizuku、JavaScript、文件读写、应用控制、剪贴板、通知、任意 HTTP 请求都已从源码中删除，既不出现在 `tools/list`，也无法调用。
- **隐私开关**：默认只开放 5 个不敏感的工具（`battery`、`get_foreground_app`、`get_screen_state`、`screen_info`、`health`）；使用时长、已装应用、设备标识等要你在手机上单独开启，MCP 请求改不了这些开关。
- **不猜测**：`get_foreground_app` 熄屏、锁屏或无法确定时不猜当前应用，并说明原因。
- **远程访问不需要 VPN 或公网 IP**：手机主动用 `wss://` 连接你的 VPS 中继；设备密钥 + MCP 令牌双重认证，证书固定（可同时保存新旧指纹，方便换证书）；断线指数退避重连，网络切换立即重连；不安全的监听配置直接拒绝启动。
- **凭据安全**：令牌和设备密钥用 Android Keystore 加密保存，支持轮换、撤销、全部清除。
- **后台运行**：`specialUse` 前台服务，状态分运行 / 受限 / 已停止；你手动停止后不会被自动拉起。
- **可观测**：各阶段耗时（`Server-Timing`）、诊断页、可导出的不含隐私的日志。
- **应用内更新（1.4.0）**：设置 → 应用更新，可选“自动更新”（默认）、“只提醒”、“手动检查”。只从本仓库的 GitHub Releases 下载 `violet-mcp-<版本>-release.apk`，核对 `SHA256SUMS.txt`、包名、版本号和签名证书，任何一项不符都不安装；安卓系统通常还要你点一次“更新”确认（通知栏提示）。第一次需要在系统设置里允许本应用“安装未知应用”。更新与 MCP 无关，MCP 请求触发不了。
- **不需要 Root，不需要无障碍权限。**

> 真机上的后台存活、Doze、网络切换、开机自启**还没有在真机上验证过**，见 [docs/DEVICE_CHECKLIST.md](docs/DEVICE_CHECKLIST.md)。

## 快速开始

1. 安装 GitHub Releases 里最新的 `violet-mcp-<版本>-release.apk`（应用名“Violet MCP”）；以后的版本可以在应用里更新。
2. 打开应用 → 设置 → 权限：允许**使用情况访问权限**（“电池无限制”建议开启）。在“工具与权限”页选择开放给 Violet 的工具。
3. 按 [docs/DEPLOY_RELAY.md](docs/DEPLOY_RELAY.md) 在 VPS 上部署中继，把中继地址、设备密钥、证书指纹填进应用的“远程访问”，开启中继连接。
4. 主页点 **启动**；在“安全”页复制访问令牌。
5. 在 Violet 后台的“工具”页添加 MCP 服务器：名字 `android-phone`，地址 `http://127.0.0.1:8787/mcp`，请求头 `Authorization: Bearer <令牌>`（见 [docs/VIOLET_INTEGRATION.md](docs/VIOLET_INTEGRATION.md)）。

本机调试（不经中继）：

```bash
adb forward tcp:1145 tcp:1145
python tests/interop/test_mcp_interop.py --url http://127.0.0.1:1145/mcp --token <令牌> --device
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/DEPLOY_RELAY.md](docs/DEPLOY_RELAY.md) | VPS 中继部署、手机设置、换证书（可直接复制的命令） |
| [docs/VIOLET_INTEGRATION.md](docs/VIOLET_INTEGRATION.md) | 接入 Violet、错误码、延迟定位 |
| [docs/TOOLS.md](docs/TOOLS.md) | 工具清单、隐私默认值、返回字段、Android 权限 |
| [docs/UPGRADE.md](docs/UPGRADE.md) | 升级与回退（含从 Eve 只读 MCP 换到 Violet MCP） |
| [docs/DEVICE_CHECKLIST.md](docs/DEVICE_CHECKLIST.md) | 真机验证清单（未执行） |
| [docs/HANDOFF.md](docs/HANDOFF.md) | 源码交接、全新环境构建、签名模板（**不要直接压缩工作目录**） |
| [docs/TEST_REPORT.md](docs/TEST_REPORT.md) | 自动化 / 模拟环境测试结果 |
| [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md)、[docs/REVIEW_v1.2.md](docs/REVIEW_v1.2.md)、[docs/SECURITY_AUDIT_v1.2.md](docs/SECURITY_AUDIT_v1.2.md)、[docs/SECURITY_RISK_v1.2.1.md](docs/SECURITY_RISK_v1.2.1.md)、[docs/SIGNING_KEY_INCIDENT.md](docs/SIGNING_KEY_INCIDENT.md) | 历次安全审查与签名密钥轮换记录（写于改名前，使用当时的版本名和文件名） |

## 架构

```
┌──────────────── Android 手机（Violet MCP，com.mcp.server.violet）────────┐
│ McpServerService（前台服务 specialUse，开机自启，网络切换监听）           │
│   ├─ LocalHttpServer  127.0.0.1:1145（只限本机）                          │
│   └─ RelayClient ═══ wss:// 主动连出（设备密钥 + 证书指纹）═══╗           │
│            ↓ 两条路径都进入同一个                              ║           │
│   McpEndpoint（Bearer 鉴权、Origin 拒绝）                      ║           │
│   McpProtocol（JSON-RPC，无状态）→ ToolRegistry（只读白名单）  ║           │
│            → UsageTools / SystemTools / AppTools / MetaTools   ║           │
└────────────────────────────────────────────────────────────────╫───────────┘
                                                                 ║
┌──────────────── VPS：violet-mcp-relay（Go）───────────────────╨───────────┐
│ :8443  TLS 设备入口（WebSocket）                                           │
│ 127.0.0.1:8787  MCP 入口 /mcp ◄── Violet（Python MCP SDK）                 │
│ 未认证请求在这里返回 401，不转发；中继本身没有任何工具                     │
└────────────────────────────────────────────────────────────────────────────┘
```

| 目录 | 内容 |
|---|---|
| `mcp-core/` | 纯 Kotlin/JVM：MCP 协议、HTTP 服务器、鉴权、只读白名单、中继客户端、使用时长计算。应用和电脑端测试共用 |
| `app/` | Android：工具实现、前台服务、开机自启、界面 |
| `relay/` | Go 中继 `violet-mcp-relay` 及 systemd 配置 |
| `tests/` | Python MCP SDK 互操作 / 安全测试、电脑端到端脚本、APK 静态检查 |

## 端点

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `POST` | `/mcp` | Bearer | Streamable HTTP（无状态，响应 `application/json`；通知返回 202） |
| `GET` | `/status` | Bearer | 运行状态（不含密钥） |
| `GET` | `/health` | 无 | 只返回 `{"ok":true}` |
| `GET` / `DELETE` | `/mcp` | Bearer | 405（不提供服务器推送流，也没有会话） |

令牌不能关闭，为 256 bit 随机值，常量时间比较。带 `Origin` 头的请求一律 403。请求体上限 1 MiB，JSON 嵌套深度上限 64，批量上限 32 条。本地端点只能绑定回环地址。`/mcp` 响应带 `Server-Timing`。

## 从源码构建

环境：JDK 17、Android SDK（platform 35、build-tools 35）、Go 1.23+、Python 3.10+（测试用）。

```bash
# 1. Android SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 发布签名（可选），模板在 config-templates/，签名目录 signing/ 不进仓库也不进交接包：
#    - 自己的密钥：复制 config-templates/keystore.properties.example 为 signing/keystore.properties
#    - 官方版本（密钥已轮换）：复制 config-templates/rotation.properties.example 为 signing/rotation.properties，
#      assembleRelease 产出未签名包后运行 python tools/sign_release.py <apk>
#    都没有时 release 包不签名，可以先用 debug 包。详见 docs/HANDOFF.md §4

# 3. 单元测试（mcp-core + Robolectric 模拟环境）+ APK
./gradlew :mcp-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
#    输出 app/build/outputs/apk/{debug,release}/violet-mcp-<版本>-<类型>.apk

# 4. 中继
cd relay && go test ./... \
  && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o ../dist/v1.3.0/violet-mcp-relay-1.2.0-linux-amd64 . \
  && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o ../dist/v1.3.0/violet-mcp-relay-1.2.0-linux-arm64 .
cd ..

# 5. 电脑端到端测试（Go 中继 ⇄ JVM harness ⇄ Python MCP SDK，不需要手机）
python -m venv .venv && .venv/bin/pip install "mcp>=1.8,<2"
PYTHON=.venv/bin/python bash tests/run_pc_e2e.sh

# 6. APK 静态检查（权限、已删除组件、危险 API）
python tests/verify_apk.py --apk app/build/outputs/apk/debug/violet-mcp-1.3.0-debug.apk \
  --build-tools /path/to/android-sdk/build-tools/35.0.0
```

正式发布包用 `tools/package_release.py` 生成（会做泄密检查）。

Windows 提示：如果用户目录含中文，Gradle 的测试进程可能启动失败（`GradleWorkerMain` 找不到），把 `GRADLE_USER_HOME` 设为纯英文路径即可，例如 `set GRADLE_USER_HOME=D:\gradle-home`。

## 致谢与许可证

本项目修改自 [Cazriel/mcp-server](https://github.com/Cazriel/mcp-server)（GPL-3.0）。主要修改（2026-10 起）：删除写入类工具与相关依赖（Shizuku、QuickJS、DocumentFile、Gson）和高权限声明；工具改为只读白名单；重写 HTTP 传输与鉴权；新增 UsageStatsManager 感知工具；用 TLS 中继取代 bore 隧道；前台服务类型改为 specialUse；隐私开关与最小化输出、Keystore 加密凭据、监听安全限制、中继心跳 / 超时 / 离线等待 / 分阶段计时、三态服务状态、诊断页与安全页；1.3.0 改名为 Violet MCP。详见 [docs/SECURITY_REVIEW.md](docs/SECURITY_REVIEW.md) 和 git 历史。

同时使用了：[Model Context Protocol](https://modelcontextprotocol.io/)、[OkHttp](https://square.github.io/okhttp/)（Apache-2.0）、[gorilla/websocket](https://github.com/gorilla/websocket)（BSD-2-Clause）、[Jetpack Compose](https://developer.android.com/jetpack/compose)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)。

本项目按 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。分发 APK 时须同时提供对应源码。
