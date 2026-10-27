# 真机验证清单（Violet MCP 1.3.0；清单写于 1.2.2-eve）

> **1.3.0（改名 Violet MCP）**：包名改为 `com.mcp.server.violet`，应用名“Violet MCP”，中继改名 `violet-mcp-relay` 1.2.0。系统把它当作新应用：不能覆盖安装旧的 `com.mcp.server.eve`，需要全新安装、重新填中继地址 / 设备密钥 / 证书指纹，Violet 那边改用新令牌（旧应用可以卸载）。因此 **A 节（覆盖升级）不适用于 1.3.0**；其余各节照做，把下面的 `PKG` 和 APK 路径换成：
>
> ```bash
> PKG=com.mcp.server.violet
> $ADB install dist/v1.3.0/violet-mcp-1.3.0-release.apk
> ```
>
> 下面的正文保留了 1.2.2 时的写法（旧包名、旧文件名）。

> 下面所有项目都**还没有在真机上执行过**（开发环境没有连接 Android 设备，也无法运行加速模拟器）。
> 电脑上的自动化测试和 Robolectric 模拟测试只能证明逻辑正确，不能代替真机上的后台行为。
> 请逐项执行后，把“结果”列从“未验证”改为实际结果（通过 / 失败 + 现象），并记录机型系统版本（不要写进公开报告）。

## 准备

```bash
# 电脑上（Windows Git Bash；adb 路径按实际修改）
ADB=D:/android-sdk-public-build/platform-tools/adb.exe
PKG=com.mcp.server.eve
$ADB devices                              # 确认手机已连接并授权 USB 调试
$ADB install -r dist/v1.2.2-eve/eve-readonly-mcp-1.2.2-eve-release.apk
```

在手机上：

1. 打开 App → 设置 → 填写中继地址、设备密钥、证书指纹 → 保存并应用 → 开启中继。
2. 设置 → 权限 → 授予“使用情况访问权限”。
3. 主页点“启动”。

在 VPS 上，用中继自带的健康检查确认手机已连接：

```bash
curl -s http://127.0.0.1:8787/health; echo      # deviceConnected 应为 true
```

后面许多步骤需要“经中继调用一次”。在 VPS 上先执行一次下面的定义（`TOKEN` 改成手机“安全”页复制的令牌），之后用 `call 工具名` 调用：

```bash
TOKEN='粘贴访问令牌'
call() { curl -s -w '\n耗时 %{time_total}s\n' -D - http://127.0.0.1:8787/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":{}}}" | grep -E 'Server-Timing|"text"|耗时'; }
call get_screen_state
```

## A. 安装、升级与凭据

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| A1 | 在已装 1.1.0（或 1.2.0）并配置好中继的手机上，直接覆盖安装 1.2.1 | 安装成功；令牌不变（Eve 不用改配置）；中继配置保留；“安全”页显示“用 Android Keystore 加密保存” | 未验证 |
| A2 | 升级后不做任何操作，等待约 1 分钟 | 如果 1.1.0 开启了“开机自启”并且在运行，服务应自动恢复（应用更新广播） | 未验证 |
| A3 | 1.1.0 开过“局域网访问”的手机升级后打开 App | 弹出“局域网访问已移除”的提示；本地端点只监听 127.0.0.1：`$ADB shell ss -ltn \| grep 1145` 只显示 127.0.0.1 | 未验证 |
| A4 | 安全页 → 更换令牌 | 旧令牌立即返回 401；新令牌可用；中继无需重启（手机会自动重连并上报新的令牌哈希） | 未验证 |
| A5 | 安全页 → 撤销远程凭据 | 中继显示设备离线；设置页中的设备密钥和指纹被清空 | 未验证 |
| A6 | `$ADB shell run-as $PKG cat shared_prefs/mcp_server_settings.xml` | release 包不可调试，命令应失败；如果用 debug 包测试，文件中应只有 `token.enc` / `relay_secret.enc`，没有明文令牌 | 未验证 |
| A7 | `$ADB backup` 或系统备份 | 备份中不含设置（allowBackup=false） | 未验证 |
| A8 | Android 9+ 手机上已装 1.2.1（旧密钥签名），覆盖安装 1.2.2（密钥已轮换） | 安装成功，数据保留。`$ADB shell dumpsys package $PKG \| grep -A3 -i "signatures\|signing"` 显示新证书（SHA-256 `d5a5eeec…`），并带有轮换历史 | 未验证 |
| A9 | A8 之后尝试覆盖安装 1.2.1：`$ADB install -r -d dist/v1.2.2-eve/eve-readonly-mcp-1.2.2-eve-release.apk` | 失败（签名不匹配或版本降级）；1.2.2 和数据不受影响 | 未验证 |
| A10 | Android 8.0 / 8.1 手机覆盖安装 1.2.2（如果有这样的设备） | 安装成功，数据保留（v2 仍是旧证书）。记录：此类设备不受轮换保护 | 未验证 |

## B. 只读与权限（真机复核）

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| B1 | 在 VPS 上运行完整的 SDK 测试：`python tests/interop/test_mcp_interop.py --url http://127.0.0.1:8787/mcp --token <令牌> --device` | 全部通过；默认只列出 5 个工具 | 未验证 |
| B2 | 在“工具与权限”页开启 `get_app_usage_stats`，再执行 B1 | 工具出现并可调用；关闭后立即从列表中消失，直接调用返回 -32602 | 未验证 |
| B3 | `$ADB shell dumpsys package $PKG \| grep -A20 "requested permissions"` | 只有 9 个权限（见 TOOLS.md） | 未验证 |
| B4 | 调用全部工具后检查：亮度、音量、Wi-Fi、蓝牙、通知栏、剪贴板 | 都没有变化 | 未验证 |

## C. 手机感知功能

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| C1 | 解锁并打开“设置”应用，3 秒后 `call get_foreground_app` | `state=current`，app 是“设置”，`age_seconds` 只有几秒 | 未验证 |
| C2 | 切换到另一个应用，5 秒后再调用 | app 变为新的应用 | 未验证 |
| C3 | 按电源键熄屏，再调用 `get_foreground_app` 和 `get_screen_state` | `state=screen_off`，app 为 null；`screenOn=false` | 未验证 |
| C4 | 亮屏但停在锁屏界面时调用 | `state=locked`，app 为 null | 未验证 |
| C5 | 下拉通知栏停留 10 秒后调用 | `current`（前一个应用）或 `unknown`；记录实际值，用来评估厂商系统的事件行为 | 未验证 |
| C6 | 开启 `get_app_usage_stats`，使用两个应用各 1–2 分钟，然后调用 `range=last_15m` | 列出这两个应用及其名称，时长大致准确 | 未验证 |
| C7 | 在系统设置中撤销“使用情况访问权限”，再调用 C1 和 C6 | 前台应用返回 `state=permission_denied`；使用时长返回 `PERMISSION_DENIED` 错误；App 主页出现授权提示 | 未验证 |
| C8 | 重启手机后、首次解锁前（如果能经中继访问）调用 | 服务尚未运行（凭据存储在首次解锁前不可用），这是预期行为 | 未验证 |

## D. 后台运行

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| D1 | 启动后按 Home 键，在最近任务中划掉 App，等 5 分钟后 `call health` | 仍然可以调用；通知栏仍有服务通知 | 未验证 |
| D2 | `$ADB shell dumpsys activity services $PKG \| grep -E "isForeground\|types"` | `isForeground=true`，类型为 specialUse | 未验证 |
| D3 | 点通知上的“停止”，然后 `$ADB shell am kill $PKG` 并等待 2 分钟 | 服务不会被系统或程序重新拉起；App 主页显示“服务已停止” | 未验证 |
| D4 | 开启“开机自启”并启动服务后重启手机，首次解锁 | 1 分钟内服务自动恢复，中继自动连接；`curl /health` 中 deviceConnected 为 true | 未验证 |
| D5 | 停止服务后重启手机 | 服务不会自动启动（用户手动停止过） | 未验证 |
| D6 | 在系统任务管理器（Android 13+ 通知栏底部的“正在运行”）中停止本应用 | 服务停止；App 主页显示“服务应在运行，但目前已被系统停止或未能启动”，而不是“运行中” | 未验证 |
| D7 | `$ADB shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND ignore`（模拟用户开启后台限制）后打开 App | 诊断页显示“系统已对本应用启用后台限制”；测完后执行 `... allow` 恢复 | 未验证 |
| D8 | `$ADB shell am set-standby-bucket $PKG restricted` 后打开 App | 主页显示“受限”；测完后执行 `... active` 恢复 | 未验证 |
| D9 | 熄屏 30 分钟后经中继调用（不开常驻 WakeLock） | 能在 `-request-timeout`（默认 20 秒）内返回；记录 Server-Timing 中的 rtt | 未验证 |
| D10 | 强制 Doze：`$ADB shell dumpsys deviceidle force-idle`，经中继调用 3 次，再执行 `$ADB shell dumpsys deviceidle unforce` | 能返回，或者返回明确的超时 / 设备离线错误（不会挂住） | 未验证 |
| D11 | 用电池统计记录 12 小时：`$ADB shell dumpsys batterystats --reset`，12 小时后执行 `$ADB shell dumpsys batterystats $PKG` | 记录耗电和唤醒次数；没有常驻 WakeLock（`Wake lock` 只有每次请求的短时锁） | 未验证 |

## E. 网络

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| E1 | 已连接时关闭 Wi-Fi（切到移动数据） | App 日志出现“默认网络已切换”，几秒内恢复“已连接”；调用成功 | 未验证 |
| E2 | 从移动数据切回 Wi-Fi | 同上 | 未验证 |
| E3 | 开启飞行模式 1 分钟 | 状态显示“等待网络”，期间不再反复尝试连接；中继返回 `Device offline`（JSON-RPC -32010） | 未验证 |
| E4 | 关闭飞行模式 | 网络恢复后立即重连（不等退避结束） | 未验证 |
| E5 | 在 VPS 上执行 `systemctl restart eve-mcp-relay` | 手机在 60 秒内自动重连 | 未验证 |
| E6 | 在 VPS 上执行 `systemctl stop eve-mcp-relay`，等 10 分钟后再 start | 期间手机按退避间隔重试（最长 60 秒一次），不会频繁重连；start 后自动恢复 | 未验证 |
| E7 | 让手机网络“假在线”（例如连接一个上不了外网的 Wi-Fi） | 中继心跳超时后断开（默认 90 秒），手机看门狗也会断开并重连；Eve 的请求在 20 秒内得到超时错误 | 未验证 |
| E8 | 用同一组凭据在两台手机上同时开启中继 | 后连上的一台生效，另一台显示“已被另一台设备顶替”，并按 2 分钟以上的间隔重试，不会互相抢连接 | 未验证 |
| E9 | 在 VPS 上用 Python SDK 并发 20 个调用（`tests/interop/test_resilience.py` 的第 1 段） | 全部成功，结果没有串号 | 未验证 |

## F. Project Eve 兼容性（在 Eve 所在服务器上）

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| F1 | 按 [EVE_INTEGRATION.md](EVE_INTEGRATION.md) 配置 `MCP_SERVERS` 后启动 Eve | Eve 能列出 5 个默认工具 | 未验证 |
| F2 | 让 Eve 询问“我现在在用什么应用” | 调用 `get_foreground_app`，回答与实际一致；熄屏时回答“屏幕已熄灭”，而不是猜一个应用 | 未验证 |
| F3 | 手机开启飞行模式后让 Eve 调用工具 | Eve 得到“Device offline”错误，Eve 本身不崩溃，后续调用能恢复 | 未验证 |
| F4 | 记录一次完整的 Eve 任务耗时，并与中继日志中的 `total=`、`rtt=`、`phone=` 对比 | 手机 MCP 部分（rtt）应在毫秒到几百毫秒之间；如果 Eve 总耗时远大于它，瓶颈不在手机 MCP | 未验证 |

## G. 凭据存储与 Keystore（1.2.1 新增）

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| G1 | 1.1.0 → 1.2.1 覆盖安装，打开 App | “安全”页没有红色的“凭据存储”卡片；令牌与升级前相同；中继能连接 | 未验证 |
| G2 | 用 debug 包重复 G1，然后执行 `$ADB shell run-as $PKG cat shared_prefs/mcp_server_settings.xml` | 只能看到 `token.enc` / `relay_secret.enc`，看不到明文 `token` / `relay_secret` | 未验证 |
| G3 | 迁移中断：先装 debug 版 1.1.0 并配置好，然后写入一份只有明文的配置（或者在 1.2.1 首次启动、读取令牌之前强制停止应用：`$ADB shell am force-stop $PKG`），再打开 App | 令牌不变；读取后明文被删除，只剩 `.enc` | 未验证 |
| G4 | 模拟密文无法解密（debug 包）：`$ADB shell run-as $PKG sh -c "sed -i 's/token.enc\">v1:/token.enc\">v1:AA/' shared_prefs/mcp_server_settings.xml"`，然后 `$ADB shell am force-stop $PKG` 并重新启动服务 | 主页显示“已保存的凭据无法解密”；用旧令牌经中继调用返回 401；**不会改用任何明文副本**；在“安全”页更换令牌后恢复 | 未验证 |
| G5 | 如果手头有 Keystore 不可用或有缺陷的设备（某些定制系统 / 老机型） | 主页显示“凭据无法加密保存，远程认证已停用”；未同意前所有请求都返回 401；勾选“我了解风险”开启兼容模式后才恢复，并持续显示警告；关闭兼容模式后明文被删除 | 未验证（没有可用设备） |
| G6 | 恢复出厂或清除应用数据后重新配置 | 生成新令牌，旧令牌失效 | 未验证 |

## H. 厂商系统：OriginOS（vivo / iQOO）重点检查

> 不同 OriginOS 版本的菜单名称和位置会有差异，下文写的是常见叫法，请以手机上实际看到的为准；遇到不一致请把实际路径记在“结果”列。
> 其他厂商系统（HyperOS / MIUI、ColorOS、HarmonyOS、One UI）按同样的项目检查，记录各自的设置位置。

**准备**：在手机管家 / i管家 → 应用管理 → 权限管理（或应用详情）中，分别记录本应用以下设置的**默认值**，再开始测试。

- 自启动
- 关联启动
- 后台高耗电 / 允许后台运行
- 锁屏后清理 / 后台冻结
- 联网权限（WLAN / 移动数据）

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| H1 | **默认设置**下启动服务，锁屏 10 分钟、30 分钟、2 小时后，在 VPS 上分别执行 `call health` | 记录每个时间点能否响应；不能响应时 App 主页应显示“受限”或“已停止”，而不是“运行中” | 未验证 |
| H2 | 打开“允许后台高耗电”和“自启动”，关闭“锁屏后清理”，重复 H1 | 2 小时后仍能响应。如果仍会被清理，记录具体在哪个时间点 | 未验证 |
| H3 | 设置“电池无限制”（App 设置页的“申请”按钮）后重复 H1 | 记录与 H2 的差异 | 未验证 |
| H4 | 打开省电模式或超级省电模式，锁屏 30 分钟 | 记录服务和中继的状态；省电模式下中断是允许的，但界面必须如实显示 | 未验证 |
| H5 | 开机恢复：开启 App 的“开机自启”和系统的“自启动”，重启手机并解锁 | 1 分钟内服务恢复并连上中继。如果系统拦截，应收到“未能自动启动”的通知，诊断页记录原因 | 未验证 |
| H6 | 只开启 App 的“开机自启”，**不开启**系统的“自启动”，重启手机 | 记录实际结果（OriginOS 可能拦截开机广播）。被拦截时 App 不能显示为运行中 | 未验证 |
| H7 | 在 OriginOS 上用“一键清理 / 加速球”清理后台 | 记录服务是否存活；被清理后 App 主页显示“服务应在运行，但已被系统停止” | 未验证 |
| H8 | 网络切换：Wi-Fi → 移动数据 → Wi-Fi，以及在“联网管理”中关闭本应用的移动数据权限后切到移动数据 | 网络正常时几秒内重连；被禁止联网时显示“等待网络”或持续重连失败，诊断页给出原因，不会频繁重连耗电 | 未验证 |
| H9 | 息屏状态下切换网络（例如走出 Wi-Fi 覆盖范围） | 恢复连接所需时间（记录秒数） | 未验证 |
| H10 | 前台应用识别：依次打开微信、浏览器、设置、桌面，每次停留 10 秒后 `call get_foreground_app` | 每次 `state=current`，`app` 正确；回到桌面时 `package` 为桌面启动器 | 未验证 |
| H11 | OriginOS 特有界面：负一屏、控制中心、通知栏、小窗 / 浮窗应用、分屏、原子组件、游戏模式 / 游戏助手 | 记录每种界面下返回的 state 和 app。期望：要么是正确的应用，要么是 `unknown`，**不能给出错误的应用名** | 未验证 |
| H12 | 锁屏杂志 / 息屏显示（AOD）状态下调用 `get_foreground_app` 和 `get_screen_state` | `screen_off` 或 `locked`；`displayState` 可能为 DOZE；不能给出应用名 | 未验证 |
| H13 | 双开应用（应用分身）打开分身微信 | 记录返回的 package 和 app。可能与主应用相同，需在报告中注明 | 未验证 |
| H14 | 使用时长：开启 `get_app_usage_stats`，记录 1 小时的使用，再与系统“屏幕使用时间 / 健康使用手机”对比 | 主要应用的差异在 10% 以内；记录差异较大的应用 | 未验证 |
| H15 | 12 小时耗电：开启电池无限制、不开常驻 WakeLock，息屏放置 12 小时 | 记录本应用耗电百分比和唤醒次数（`dumpsys batterystats`）；作为是否调整中继 `-ping-interval` 的依据 | 未验证 |
