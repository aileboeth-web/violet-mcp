# 升级与回退

## Violet MCP 1.3.0 → 1.4.0（应用内更新；中继不变）

- 直接覆盖安装 `violet-mcp-1.4.0-release.apk`（包名、签名都与 1.3.0 相同）：令牌、中继配置、工具开关都保留，Violet 那边不用改。
- 新增：设置 → 应用更新。默认“自动更新”：每 6 小时检查 GitHub Releases，有新版本就下载、核对 SHA-256 / 包名 / 版本 / 签名证书，再交给系统安装（通常要你点一次“更新”）。可改成“只提醒”或“手动检查”。
- 新增权限 `REQUEST_INSTALL_PACKAGES`：要在系统设置里为本应用打开“安装未知应用”才能装更新；不打开也不影响 MCP 服务，只是更新要自己下载安装。
- 应用会访问 `api.github.com`、`github.com` 和 GitHub 的下载服务器（不登录、不上传任何数据）；“手动检查”模式下只在你点按钮时访问。
- 回退：卸载后装 1.3.0（数据会清空，要重新配置），或用 `adb install -r -d`（降级安装，数据保留与否取决于系统）。

## 1.2.2-eve → Violet MCP 1.3.0 / 中继 1.2.0（改名；工具和安全规则都不变）

1.3.0 只改名字：应用名“Violet MCP”、包名 `com.mcp.server.violet`、发布文件 `violet-mcp-<版本>-release.apk`；中继改名 `violet-mcp-relay`（1.2.0），环境变量改为 `VIOLET_RELAY_DEVICE_SECRET`（旧名 `EVE_RELAY_DEVICE_SECRET` 仍然认）。14 个只读工具、权限、鉴权、TLS 和签名密钥都没有变化。

**手机**：包名变了，系统把 1.3.0 当作另一个应用，**不能覆盖安装**旧的“Eve 只读 MCP”（`com.mcp.server.eve`）。

1. 安装 `violet-mcp-1.3.0-release.apk`（可以和旧应用同时存在）。
2. 在新应用里重新开启“使用情况访问权限”，填写中继地址、设备密钥、证书指纹（和旧应用填的一样即可），开启中继。
3. 在“安全”页复制新的访问令牌，到 Violet 后台“工具”页把 `android-phone` 服务器的 `Authorization` 改成新令牌。
4. 确认 Violet 能调用以后，在旧应用里关闭中继，然后卸载旧应用（两个应用同时连同一个中继时，后连上的会替换先连上的）。

**中继**（在 VPS 上，root）。想保留原来的证书和设备密钥（手机就不用改证书指纹）时，先把旧数据目录复制过去：

```bash
systemctl disable --now eve-mcp-relay
cp -a /var/lib/private/eve-mcp-relay /var/lib/private/violet-mcp-relay   # DynamicUser 的数据在 /var/lib/private 下
mkdir -p /opt/violet-mcp-relay
ARCH=$( [ "$(uname -m)" = aarch64 ] && echo arm64 || echo amd64 )
install -m 0755 /root/violet-mcp-relay-1.2.0-linux-$ARCH /opt/violet-mcp-relay/violet-mcp-relay
install -m 0644 /root/violet-mcp-relay.service /etc/systemd/system/violet-mcp-relay.service
systemctl daemon-reload && systemctl enable --now violet-mcp-relay
journalctl -u violet-mcp-relay -n 20 --no-pager      # 证书指纹应与以前相同
```

不复制数据目录也可以：新中继会生成新的证书和设备密钥，按 [DEPLOY_RELAY.md](DEPLOY_RELAY.md) 第 3 步取得三项，在手机上重新填写。确认无误后可以删除旧的 `/opt/eve-mcp-relay`、`/etc/systemd/system/eve-mcp-relay.service`。

以上步骤**尚未在真机和真实 VPS 上执行过**。

下面是 1.3.0 之前各版本的升级记录（文件名、包名用的是当时的名字）。

## 1.2.1-eve → 1.2.2-eve（签名密钥轮换；手机和中继功能都不变）

1.2.2 没有功能变化，只做了两件事：

- 签名密钥轮换，原因是旧密钥随完整工作目录 ZIP 外泄（见 SIGNING_KEY_INCIDENT.md §6）；
- 新增安全的源码交接打包。

中继仍然是 1.1.0，不需要更新。

**升级（手机）**：直接覆盖安装 `dist/v1.2.2-eve/eve-readonly-mcp-1.2.2-eve-release.apk`。可以从 1.1.0、1.2.0 或 1.2.1 直接升级，数据保留（令牌、中继配置、工具开关都不变）：

| 手机系统 | 能否覆盖安装 | 原因 |
|---|---|---|
| Android 9+（API 28+） | 可以 | APK 带有旧证书 → 新证书的轮换证明（v3 lineage），系统据此确认是同一开发者 |
| Android 8.0 / 8.1 | 可以 | 这些系统只校验 v2 签名，而 v2 仍由旧密钥签名。但这也意味着它们**无法受到轮换保护** |

以上结论来自 apksigner 的校验结果和 Android 的签名规则，**尚未在真机上验证**（见 DEVICE_CHECKLIST.md A8–A10）。

**升级后请尽快**：

- 所有手机都装上 1.2.2，轮换只对已安装 1.2.2 的设备生效；
- 删除所有 `mcp-server.zip` 副本；
- 只从 `dist/` 获取 APK。

**回退到 1.2.1 或更早版本**：Android 9+ 手机装上 1.2.2 之后，只用旧密钥签名的 1.2.1 **不能覆盖安装**。这是轮换的保护作用：旧密钥在轮换证明中没有授予回退能力。另外，版本号更低的 APK 本来也不能覆盖安装。因此回退必须先卸载，本机配置会丢失，需要重新填写中继信息，Eve 也要改用新令牌。

**不建议回退**：1.2.1 及更早版本是用已经外泄的旧密钥单独签名的。

**开发者构建方式变化**：

- 存在 `signing/rotation.properties` 时，`gradlew :app:assembleRelease` 只产出未签名包，需要再运行 `python tools/sign_release.py <apk>`；
- 发布用 `tools/package_release.py`，源码交接用 `tools/make_handoff.py`，见 HANDOFF.md。

---

## 1.2.0-eve → 1.2.1-eve（只更新手机，中继不变）

**升级**：直接覆盖安装 `dist/v1.2.1-eve/eve-readonly-mcp-1.2.1-eve-release.apk`。签名与 1.1.0、1.2.0 相同，数据保留。也可以从 1.1.0 直接升级。

**行为变化（安全修复）**：

| 情况 | 1.2.0 | 1.2.1 |
|---|---|---|
| Keystore 正常（绝大多数手机） | 加密保存 | 不变：加密保存，令牌不变 |
| Keystore 不可用，或旧版明文令牌无法迁移 | 静默改为明文保存，继续接受认证 | **停用远程认证**，主页显示红色提示；旧令牌保留在本机但暂不可用。需要在“安全”页阅读风险说明、勾选同意兼容模式后才恢复，之后持续显示警告 |
| 已保存的密文无法解密 | 如果有残留的明文副本，改用明文副本 | 拒绝认证，不使用任何明文副本；需要在“安全”页更换令牌（并更新 Eve 配置），在“设置”中重新填写设备密钥 |

**升级后的检查**：打开 App 看主页有没有红色的“凭据”提示卡片。没有提示时，一切与 1.2.0 相同。

**回退到 1.2.0**：Android 不允许降低版本号覆盖安装，必须卸载后再安装 `dist/v1.2.0-eve/eve-readonly-mcp-1.2.0-eve-release.apk`。卸载会清空本机配置，需要重新填写中继信息，令牌也会变化（Eve 配置要同步修改）。除非 1.2.1 出现阻断性问题，否则不建议回退，因为 1.2.0 存在上表中的问题。

**发布方式变化**：今后的发布包一律用 `tools/package_release.py` 生成，并经 `tests/check_release_artifacts.py` 检查。签名密钥的处理见 [SIGNING_KEY_INCIDENT.md](SIGNING_KEY_INCIDENT.md)。

---

# 1.1.0-eve → 1.2.0-eve，中继 1.0.0 → 1.1.0

## 兼容性

新旧版本混合使用已在电脑上测试过（见 TEST_REPORT §1.3 混合版本），**先升级哪一端都可以**：

| 手机 | 中继 | 结果 |
|---|---|---|
| 1.2.0 | 1.0.0 | 可用；中继不发应用层心跳，手机端看门狗不启用，靠手机侧 WebSocket ping（默认 60 秒）检测断线；Eve 收不到 Server-Timing |
| 1.1.0 | 1.1.0 | 可用；旧手机会回应中继的心跳；没有 1.2 新增的手机端检查（批量上限、jsonrpc 版本校验、手机侧耗时） |
| 1.2.0 | 1.1.0 | 推荐组合 |

## 升级后会变化的行为

| 变化 | 影响 | 处理 |
|---|---|---|
| 工具隐私开关默认值 | 1.1.0 中**没有手动改过**的工具，按新默认值处理：只开 `battery`、`get_foreground_app`、`get_screen_state`、`screen_info`、`health` | 需要使用时长等工具时，到“工具与权限”页开启 |
| `get_foreground_app` 返回格式 | 由 `status/isCurrent/packageName/...` 改为 `state/app/package/observed_at/age_seconds`；非 current 状态不再给出应用 | Eve 的提示词或解析逻辑如果依赖旧字段，需要调整（MCP 工具描述里已有说明，模型一般能直接读懂） |
| “局域网访问”删除 | 1.1.0 开过局域网访问的，升级后本地端点只监听 127.0.0.1 | 改用 TLS 中继 |
| 令牌加密存储 | 第一次读取时自动迁移，**令牌值不变** | 无需处理 |
| 开机自启 | 用户点过“停止”后，开机和应用更新都不会再自动启动 | 想恢复时在 App 中点“启动” |
| 中继默认超时 | 等待手机响应的超时由 60 秒改为 20 秒；新增离线等待 5 秒、心跳 30 秒 | 可用 `-request-timeout`、`-offline-wait`、`-ping-interval` 调整 |
| 中继非回环明文监听 | 以前只打印警告，现在**拒绝启动** | 改成 127.0.0.1，或加 `-client-tls` |
| 中继的设备侧错误 | 手机离线 / 超时由 HTTP 503/504 改为 HTTP 200 + JSON-RPC 错误 | Eve 不需要改；会话不再因此中断 |

## 升级步骤

### 1. 中继（VPS）

先在 Windows 电脑上上传新程序（只改 `$VPS` 这一处）：

```powershell
$VPS = "root@你的VPS地址"
scp D:\Claude\mcp-server\dist\v1.2.0-eve\eve-mcp-relay-1.1.0-linux-amd64 D:\Claude\mcp-server\relay\deploy\eve-mcp-relay.service "${VPS}:/root/"
ssh $VPS
```

再在 VPS 上执行。旧程序会保留为 `eve-mcp-relay-1.0.0`，用于回退：

```bash
cp /opt/eve-mcp-relay/eve-mcp-relay /opt/eve-mcp-relay/eve-mcp-relay-1.0.0
install -m 0755 /root/eve-mcp-relay-1.1.0-linux-amd64 /opt/eve-mcp-relay/eve-mcp-relay
# 新的 systemd 配置与旧版相同（监听 127.0.0.1:8787 和 :8443）；如果你改过 ExecStart，保留自己的版本
systemctl restart eve-mcp-relay
journalctl -u eve-mcp-relay -n 20 --no-pager     # 应看到 "eve-mcp-relay 1.1.0" 和 timers 一行
curl -s http://127.0.0.1:8787/health; echo       # 手机重连后 deviceConnected 为 true
```

如果启动失败并提示 `refusing to start`，说明 `-client-listen` 配成了非回环地址且没有开 TLS。按提示修改 `/etc/systemd/system/eve-mcp-relay.service` 后执行 `systemctl daemon-reload && systemctl restart eve-mcp-relay`。

### 2. 手机

直接覆盖安装 `eve-readonly-mcp-1.2.0-eve-release.apk`（签名与 1.1.0 相同，数据保留）。安装后：

1. 打开 App，确认主页显示“服务运行中”，远程访问显示“已连接”。
2. 打开“安全”页，确认令牌显示“用 Android Keystore 加密保存”。
3. 打开“工具与权限”页，按需开启敏感工具。

## 回退

### 中继回退到 1.0.0

```bash
install -m 0755 /opt/eve-mcp-relay/eve-mcp-relay-1.0.0 /opt/eve-mcp-relay/eve-mcp-relay
systemctl restart eve-mcp-relay
```

device.secret 和自签证书都在 `/var/lib/eve-mcp-relay`，两个版本通用，手机配置不用改。

### 手机回退到 1.1.0

Android 不允许直接覆盖安装版本号更低的 APK，**回退必须先卸载**，所以本机配置会丢失：

1. 打开 1.2.0，在“安全”页复制访问令牌，并记下中继地址、设备密钥（在 VPS 上 `cat /var/lib/eve-mcp-relay/device.secret`）和证书指纹。
2. 卸载 1.2.0，安装 `dist/eve-readonly-mcp-1.1.0-eve-release.apk`。
3. 在 1.1.0 中重新填写中继配置。旧版无法导入原令牌，会生成新令牌，所以 **Project Eve 的 Authorization 需要改成 1.1.0 显示的新令牌**。
4. 1.1.0 的已知问题：工具默认全部开启；令牌明文存储；用户停止服务后仍可能被应用更新广播拉起（见 REVIEW_v1.2.md R4）。

### 产物

- **1.1.0 稳定版文件**在 `dist/` 根目录，没有被覆盖：
  - `eve-readonly-mcp-1.1.0-eve-release.apk`
  - `eve-mcp-relay-linux-amd64`（1.0.0）
  - 源码包
- **1.2.0 文件**都在 `dist/v1.2.0-eve/` 目录下，文件名带版本号。
