# 源码交接与全新环境构建

## 1. 不要直接压缩工作目录

开发者的工作目录里有以下内容，**任何一样都不能交出去**：

| 内容 | 为什么不能交 |
|---|---|
| `signing/`：签名私钥 `*.jks`、口令文件 `rotation.properties`、轮换证明 `lineage-*.bin` | 拿到私钥和口令的人可以签出“合法更新”，覆盖安装并读取 App 数据（见 [SIGNING_KEY_INCIDENT.md](SIGNING_KEY_INCIDENT.md)） |
| `.git/` | 完整历史，可能包含曾经误加入又删除的内容 |
| `local.properties` | 本机路径 |
| `.gradle/`、`.kotlin/`、`*/build/`、`dist/` | 缓存和构建产物，体积大，也可能残留中间文件 |

2026-10-10 发生过一次：把整个工作目录直接压缩成 `mcp-server.zip` 交了出去，其中包含签名私钥和口令，随后已轮换密钥（记录见 SIGNING_KEY_INCIDENT.md §6）。

## 2. 生成交接包（唯一推荐方式）

```bash
python tools/make_handoff.py --version 1.3.0
# 输出：dist/handoff/violet-mcp-1.3.0-source-handoff.zip
#       dist/handoff/violet-mcp-1.3.0-source-handoff.scan.json（扫描报告）
```

脚本做以下几件事：

1. **选文件**：只取 git 已跟踪的文件和未被 `.gitignore` 排除的新文件，然后按两份黑名单再过滤一遍。
2. **写清单**：生成 `HANDOFF_MANIFEST.txt`，记录每个文件的 SHA-256。
3. **扫描**：对**生成的 ZIP 本身**做递归安全扫描。检查范围覆盖 ZIP 里每个文件，以及嵌套的 zip、jar、apk、tar、gz：
   - 禁止的文件名和目录；
   - PEM 私钥、JKS / PKCS#12 密钥库；
   - 口令配置行；
   - 常见云服务和 API 令牌格式；
   - 与本机真实密钥文件逐字节比对、本机真实口令的字面匹配。

   任何一项不通过，就删除 ZIP。

**手动复查**任意交接包：

```bash
python -I -X utf8 tests/check_release_artifacts.py --handoff 交接包.zip
```

## 3. 在全新环境中构建

需要：

- JDK 17
- Android SDK（platform 35、build-tools 35）
- Go 1.23+
- Python 3.10+（只有测试会用到）

```bash
unzip violet-mcp-1.3.0-source-handoff.zip && cd violet-mcp-1.3.0
grep -v '^#' HANDOFF_MANIFEST.txt | sha256sum -c --quiet && echo 清单校验通过   # 校验文件完整性

cp config-templates/local.properties.example local.properties    # 改成本机 SDK 路径
./gradlew :mcp-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
# 没有签名配置时 release 包不签名（DOES NOT VERIFY 属于预期）

cd relay && go vet ./... && go test ./... \
  && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o violet-mcp-relay-linux-amd64 .
```

Windows 提示：路径中不能有中文。如果用户目录含中文，设置 `GRADLE_USER_HOME` 为纯英文路径。

需要代理才能下载依赖时：Gradle 不读取 `HTTP_PROXY` 环境变量，请用 `JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=… -Dhttps.proxyPort=… -Dhttp.proxyHost=… -Dhttp.proxyPort=…"` 或 `~/.gradle/gradle.properties` 中的 `systemProp.https.proxyHost` 等设置。

**一键验证**（解压到全新目录、使用全新的 Gradle / Go 缓存、跑测试和构建、用一次性密钥验证签名模板）：

```bash
bash tests/verify_handoff_build.sh 交接包.zip <Android SDK 路径> <纯英文的空工作目录>
```

## 4. 签名

交接包里**不含任何真实密钥**，只有 `config-templates/` 中的模板。

| 场景 | 做法 |
|---|---|
| 自己构建、自己安装（分支项目） | 复制 `config-templates/keystore.properties.example` 为 `signing/keystore.properties`，填写自己生成的密钥。签出的 APK 不能覆盖安装官方版本，需要先卸载 |
| 官方版本发布（只有持有密钥的维护者） | 把离线保存的密钥放回 `signing/`，复制 `config-templates/rotation.properties.example` 为 `signing/rotation.properties` 并填写口令；`gradlew :app:assembleRelease` 产出未签名包，再运行 `python tools/sign_release.py <apk>` 用 v1 + v2 + lineage 签名并自动校验 |

两个文件不能同时存在，否则 Gradle 会直接报错。这是为了防止只用旧密钥签名：已经升级到轮换版本的手机会拒绝这样的更新。
