# 签名密钥泄露处理说明

适用于 Eve 只读 MCP 的 release 签名密钥：`signing/eve-release.jks`（PKCS#12，EC P-256，别名 `eve-release`），口令保存在 `signing/keystore.properties`。

## 1. 现状核查（2026-10-10）

| 检查项 | 结果 |
|---|---|
| 历史发布包：1.1.0 源码包与 APK、1.2.0 源码包与 APK、5 个中继程序 | `tests/check_release_artifacts.py dist` 检查了 11 个产物、378 个文件（含 jar 等嵌套包），**未发现**签名密钥、PEM 私钥块、密钥库结构，也没有出现真实口令 |
| git 索引 | 不含 `signing/`、`*.jks`、`*.keystore`、`keystore.properties`、`local.properties` |
| 检查脚本自检 | 把真实 `.jks` 和 `keystore.properties` 放进测试 zip，或把 `.jks` 改名为 `renamed.bin`，都能被检出 |

> **更新（同日稍后）**：随后发现完整工作目录 ZIP 外泄了密钥和口令，已执行轮换，见第 6 节。下面是当时的核查结论，保留作记录。

**结论（当时）**：目前没有证据表明签名密钥已经泄露，所以不需要立即更换。本轮做的是加固：

- `.gitignore` 在全仓库范围排除密钥文件，不只排除 `signing/` 目录；
- 发布一律经 `tools/package_release.py` 打包：源码只取 git 跟踪的文件，再按黑名单过滤一次，打包完成后自动做泄密检查，不通过就删除全部产物。

已经发出去的旧包可以随时复查：

```bash
python -I -X utf8 tests/check_release_artifacts.py dist
```

## 2. 密钥泄露意味着什么

拿到签名密钥（`.jks` 加上口令）的人，可以给任意 APK 签上与本应用相同的签名，包名同样用 `com.mcp.server.eve`。只要用户被诱导安装这样一个“更新”：

- 它会**直接覆盖**本应用，并继承本应用的全部数据；
- 它运行在同一个 UID 下，因此能用 Android Keystore 解密本应用保存的访问令牌和中继设备密钥。拿到这两样，就能冒充你的手机连接中继，也能访问本服务器；
- 它可以继承本应用已获得的权限，例如使用情况访问。

本应用不在应用商店上架，只能侧载安装，所以攻击者必须让你主动安装他的 APK。但一旦安装，上面这些影响都会发生。

## 3. 什么时候必须更换密钥

| 情况 | 处理 |
|---|---|
| `.jks` 和口令都已外泄。包括：`signing/` 目录或 `keystore.properties` 与 `.jks` 一起被上传到网盘、仓库、聊天记录、公开的发布包 | **必须更换**，立即按第 4 节操作 |
| 开发电脑被入侵，或被他人长时间接触 | **必须更换** |
| 只有 `.jks` 外泄，口令没有外泄 | 口令是 24 字节的随机值，暴力破解不现实。仍建议按第 4 节更换，至少应尽快更换 |
| 只有口令外泄，`.jks` 没有外泄 | 用 `keytool -storepasswd` / `-keypasswd` 改口令即可，不需要更换密钥 |
| 证书指纹（SHA-256）被公开 | 不需要处理。证书指纹本来就是公开信息 |

## 4. 更换密钥且不破坏已安装用户的升级（APK Signature Scheme v3 密钥轮换）

原理：用旧密钥签署一份“轮换证明”（lineage），声明新密钥是它的继任者。

- **Android 9（API 28）及以上**：设备会接受用新密钥签名、并带有这份证明的更新，应用数据保留。装上新版本之后，**仅用旧密钥签名的 APK 不能再覆盖安装**（除非给旧密钥授予 rollback 能力；默认不授予）。
- **Android 8.0 / 8.1（API 26–27）**：这些系统不认识 v3 签名，只校验 v1/v2 签名，而 v1/v2 仍是旧密钥。这意味着在这些设备上，**持有旧密钥的人仍能推送“合法”更新**，这一点无法靠轮换解决。见本节最后的说明。

`apksigner` 位于 `D:\android-sdk-public-build\build-tools\35.0.0\`。

> 本节是通用步骤。2026-10-10 实际执行时，lineage 文件名为 `signing/lineage-v1-v2.bin`，口令写入 `signing/rotation.properties`，4.3 的签名与校验由 `tools/sign_release.py` 自动完成，见第 6 节。

### 4.1 生成新密钥，并把旧密钥移出项目目录

```bash
cd D:/Claude/mcp-server
NEWPW=$(python -c "import secrets;print(secrets.token_urlsafe(24))")
keytool -genkeypair -keystore signing/eve-release-2.jks -storetype PKCS12 -alias eve-release-2 \
  -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10000 \
  -storepass "$NEWPW" -keypass "$NEWPW" -dname "CN=Eve Read-only MCP, O=Project Eve"
echo "新口令（离线保存后清屏）：$NEWPW"
```

### 4.2 用旧密钥签署轮换证明

```bash
BT=D:/android-sdk-public-build/build-tools/35.0.0
$BT/apksigner.bat rotate --out signing/lineage-1-2.bin \
  --old-signer --ks signing/eve-release.jks --ks-key-alias eve-release \
  --new-signer --ks signing/eve-release-2.jks --ks-key-alias eve-release-2
```

`apksigner` 会交互式询问两个口令。

### 4.3 构建未签名的 release 包，再按轮换方式签名

1. 先把 `signing/keystore.properties` 临时改名，让 Gradle 不签名。
2. 然后构建：

```bash
JAVA_HOME=D:/JDK GRADLE_USER_HOME=D:/Claude/.gradle-ascii ./gradlew.bat :app:assembleRelease
APK=app/build/outputs/apk/release/eve-readonly-mcp-<新版本>-release.apk
$BT/apksigner.bat sign \
  --ks signing/eve-release.jks --ks-key-alias eve-release \
  --next-signer --ks signing/eve-release-2.jks --ks-key-alias eve-release-2 \
  --lineage signing/lineage-1-2.bin --rotation-min-sdk-version 28 \
  "$APK"
$BT/apksigner.bat verify -v --print-certs "$APK"   # 应看到 v3 签名者为新证书，并带有 lineage
```

注意：

- `--rotation-min-sdk-version 28` 让 Android 9 及以上的设备都使用新密钥。省略这个参数时，新版 apksigner 默认只对 Android 13 及以上生效。
- 后续每个版本都要用同样的 `--lineage` 签名。

### 4.4 发布与收尾

1. 尽快发布这个版本，让所有手机都装上。轮换只对已经装上新版本的设备生效。
2. 在每台手机上，到“安全”页**更换令牌**；在 VPS 上**更换 device.secret**（命令见 DEPLOY_RELAY.md §9）；再更新 Project Eve 配置。如果有人曾在你的手机上安装过伪造的更新，原有凭据应视为已经泄露。
3. 旧密钥不能删除：生成 lineage 时需要它，以后如果要再次轮换也需要它。把它离线加密备份后，从开发电脑上移走。
4. 更新 `signing/keystore.properties`，或者改为手动签名流程，并在 README 中写明当前证书的 SHA-256。

### 4.5 Android 8.0 / 8.1 设备

v3 轮换无法保护这些设备。如果密钥确实已经泄露，可以选择：

- **推荐**：把 `minSdk` 提高到 28，并请这些设备上的用户卸载后重新安装。卸载会清除本机配置，需要重新填写中继信息，令牌也会变化。
- 或者改用新的 `applicationId`，作为一个全新的应用发布，旧应用卸载。

## 5. 没有泄露时的日常防护

- `signing/` 只放在开发电脑上，并离线加密备份一份（例如加密 U 盘）。**丢失密钥后就不能再发布能覆盖安装的更新**，只能改用新的包名。
- 不要把 `signing/` 目录、`.jks` 文件或口令发到聊天记录、网盘或 issue 里。
- 发布只用 `tools/package_release.py`。手动打的包，发布前要用 `tests/check_release_artifacts.py` 检查。
- 定期在 Windows PowerShell 中运行 `Get-ChildItem -Recurse -Include *.jks,*.keystore,keystore.properties D:\` 之类的命令，确认密钥只存在于预期的位置。

## 6. 事件记录：2026-10-10 完整工作目录 ZIP 泄露签名密钥

| 项目 | 内容 |
|---|---|
| 发现 | 整个工作目录被直接压缩成 `mcp-server.zip`（约 176 MB，3768 个条目）并提供给了外部。用 `tests/check_release_artifacts.py --handoff` 扫描后，确认其中包含 `signing/eve-release.jks` 和 `signing/keystore.properties`（明文口令），与本机文件逐字节相同。另外还包含 `.git/`（293 个文件）、`.gradle/`、`.kotlin/`、`app/build/` 等构建目录、`dist/` 和 `local.properties`。 |
| 范围 | 扫描 4437 个文件（含嵌套归档），除上述密钥和口令外，**没有**发现其他凭据：没有 MCP 访问令牌、中继设备密钥或云服务 API 密钥。手机上的令牌和 VPS 上的 device.secret 从未存放在工作目录中。git 历史中也没有密钥文件。 |
| 判断 | 私钥和口令一起外泄，符合第 3 节“必须更换”的条件；用户也确认该 ZIP 已离开本机。 |
| 处理 | 已执行第 4 节的 v3 密钥轮换：<br>• 新密钥 `signing/eve-release-2.jks`（别名 `eve-release-2`）<br>• 轮换证明 `signing/lineage-v1-v2.bin`<br>• 旧密钥的口令文件改名为 `keystore.properties.v1-retired`，不再用于构建<br>• 新的签名配置 `signing/rotation.properties`<br>• Gradle 发现轮换配置时只产出未签名包，并且禁止与单一密钥配置并存<br>• 1.2.2-eve 起由 `tools/sign_release.py` 签名并自动校验 |
| 证书 | 旧证书 SHA-256 `52f602d3cf17deff7a9ebfdab12d18007b94211982fbf1c4b94ddf861160b499`（Android 8.x 仍使用）<br>新证书 SHA-256 `d5a5eeec329d58c4f4aa5ef70694eda249c642ffa9a5723d9a30f5f706af15ea`（Android 9+ 使用） |
| 预防 | 交接一律使用 `tools/make_handoff.py`（见 [HANDOFF.md](HANDOFF.md)），发布一律使用 `tools/package_release.py`，两者都会扫描最终生成的 ZIP。 |

### 仍需用户完成

1. 删除本机和所有收件方保存的 `mcp-server.zip`。外泄的副本无法撤回，所以轮换仍然必要。
2. **尽快**在每台手机上覆盖安装 1.2.2。轮换只对已装上 1.2.2 的 Android 9+ 手机生效：在装上之前，持有旧密钥的人仍然可以签出能覆盖安装的 APK。
3. 不要安装任何来源不明的“Eve 只读 MCP”APK。官方 APK 只来自 `dist/`，可以用 `apksigner verify --print-certs` 核对：在 Android 9+ 上应显示新证书。
4. 把 `signing/` 整个目录离线加密备份：两把密钥、lineage、`rotation.properties`。**丢失新密钥后，就不能再发布能覆盖安装的更新**（lineage 文件丢失不要紧：签名时把 `--lineage` 指向任意一个已发布的 1.2.2 及以后版本的 APK 即可，已实测可行）。每次发布仍需要两把密钥：旧密钥用于给 Android 8.x 做 v2 签名，新密钥用于 v3。平时可以只在发布时把 `signing/` 放回开发电脑，发布完再移走。如果把 `minSdk` 提高到 28，以后就不再需要旧密钥。
5. Android 8.x 设备无法受到轮换保护，建议卸载 Eve 只读 MCP，或停止使用。
6. 访问令牌和中继设备密钥**没有**随 ZIP 外泄，无需因为这次事件更换。如果曾经在手机上安装过来源不明的同名 APK，则需要更换。
