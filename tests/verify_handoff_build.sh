#!/usr/bin/env bash
# 在“全新环境”中验证源码交接包能完成构建：
#   - 解压到空目录，Gradle / Go 缓存都用该目录下的新目录（所有依赖重新下载）
#   - 校验 HANDOFF_MANIFEST.txt，确认没有 signing/、.git/、local.properties
#   - 核心与 Android 单元测试、debug / release 构建（无签名配置时 release 应为未签名）
#   - 中继 go vet / go test / Linux amd64 构建
#   - 用一次性测试密钥按 config-templates/keystore.properties.example 签名，验证模板可用
#
# 用法：bash tests/verify_handoff_build.sh <交接包.zip> <Android SDK 路径> <纯 ASCII 的空工作目录>
# 需要：JDK 17（JAVA_HOME 或 PATH 中的 java/keytool）、go、unzip、sha256sum；
#       ANDROID_BUILD_TOOLS 指向 build-tools/35.0.0（用于 apksigner）。可用 GOPROXY 指定 Go 代理。
set -euo pipefail
ZIP=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
SDK=$2
WORK=$3
BT=${ANDROID_BUILD_TOOLS:-$SDK/build-tools/35.0.0}
APKSIGNER="$BT/apksigner"; [[ "${OS:-}" == "Windows_NT" ]] && APKSIGNER="$BT/apksigner.bat"
KEYTOOL=keytool; [[ -n "${JAVA_HOME:-}" ]] && KEYTOOL="$JAVA_HOME/bin/keytool"

step() { echo; echo "== $*"; }

rm -rf "$WORK" && mkdir -p "$WORK"
export GRADLE_USER_HOME="$WORK/gradle-home" GOMODCACHE="$WORK/go-mod" GOPATH="$WORK/go-path" GOCACHE="$WORK/go-cache"

step "解压并校验清单"
(cd "$WORK" && unzip -q "$ZIP")
SRC=$(ls -d "$WORK"/violet-mcp-*/ | head -1)
cd "$SRC"
grep -v '^#' HANDOFF_MANIFEST.txt | sha256sum -c --quiet && echo "清单校验通过（$(grep -vc '^#' HANDOFF_MANIFEST.txt) 个文件）"
for p in signing .git local.properties .gradle dist app/build; do
  if [[ -e "$p" ]]; then echo "FAIL: 交接包中不应有 $p"; exit 1; fi
done
echo "没有 signing/、.git/、local.properties、缓存和构建目录"

step "Gradle：全新缓存，核心测试 + Android 单元测试 + debug/release 构建"
printf 'sdk.dir=%s\n' "$SDK" > local.properties
GW=./gradlew; [[ "${OS:-}" == "Windows_NT" ]] && GW=./gradlew.bat
chmod +x gradlew 2>/dev/null || true
$GW --no-daemon --console=plain -q :mcp-core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
REL=$(ls app/build/outputs/apk/release/*-release.apk)
if "$APKSIGNER" verify "$REL" >/dev/null 2>&1; then
  echo "FAIL: 没有签名配置时 release 包不应已签名"; exit 1
fi
echo "release 包未签名（符合预期）：$(basename "$REL")"
CORE=$(grep -ho 'tests="[0-9]*"' mcp-core/build/test-results/test/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
APP=$(grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
FAILS=$(grep -ho 'failures="[0-9]*" errors="[0-9]*"' mcp-core/build/test-results/test/*.xml app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END{print s+0}')
echo "核心测试 $CORE 项，Android 单元测试 $APP 项，失败+错误 $FAILS"
[[ "$FAILS" == "0" ]]

step "中继：全新 Go 模块缓存"
(cd relay && go vet ./... && go test -count=1 ./... && \
  GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o "$WORK/violet-mcp-relay-linux-amd64" .)
echo "中继构建完成：$(wc -c <"$WORK/violet-mcp-relay-linux-amd64") 字节"

step "签名模板：用一次性测试密钥签名 release"
mkdir -p signing
PW="handoff-throwaway-$(date +%s)"
"$KEYTOOL" -genkeypair -keystore signing/throwaway.jks -storetype PKCS12 -alias throwaway -keyalg EC \
  -groupname secp256r1 -validity 2 -storepass "$PW" -keypass "$PW" -dname "CN=Handoff Throwaway" >/dev/null 2>&1
sed -e "s|signing/my-release.jks|signing/throwaway.jks|" -e "s|my-release|throwaway|" -e "s|CHANGE_ME|$PW|g" \
  config-templates/keystore.properties.example > signing/keystore.properties
$GW --no-daemon --console=plain -q :app:assembleRelease
"$APKSIGNER" verify --print-certs "$REL" | grep -q "CN=Handoff Throwaway" && echo "按模板签名成功（一次性测试密钥）"

echo
echo "== 全部通过"
