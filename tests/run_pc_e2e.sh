#!/usr/bin/env bash
# 电脑端端到端测试（不需要手机，结果属于“模拟环境”）：
#   Go 中继（自签 TLS）⇄ JVM harness（与 App 相同的协议/鉴权/中继客户端代码，工具数据为模拟值）⇄ Python MCP SDK
#
# 依赖：go、JDK 17（JAVA_HOME）、装好 mcp 的 Python（PYTHON，默认 python3）、curl
# 用法：PYTHON=/path/to/venv/bin/python bash tests/run_pc_e2e.sh
# 可选：OLD_RELAY=<1.0.0 中继可执行文件>  OLD_SRC_ZIP=<1.1.0 源码 zip>  用于新旧版本混合测试
#       E2E_WORK=<纯 ASCII 的工作目录>（Windows 用户目录含中文时需要）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
# 工作目录必须是纯 ASCII 路径（Android Gradle 插件拒绝非 ASCII 路径）；可用 E2E_WORK 指定
export WORK="${E2E_WORK:-$(mktemp -d)}"
rm -rf "$WORK" && mkdir -p "$WORK"
export DEV_PORT=18443 MCP_PORT=18787
export RELAY_EXTRA="-request-timeout 5s -offline-wait 2s -ping-interval 10s -log-level debug"
LOCAL_PORT=18080
GRADLEW="$ROOT/gradlew"
[[ "${OS:-}" == "Windows_NT" ]] && GRADLEW="$ROOT/gradlew.bat"
export PYTHONIOENCODING=utf-8 NO_PROXY="127.0.0.1,localhost" no_proxy="127.0.0.1,localhost"
CTL="bash $ROOT/tests/relay_ctl.sh"
HARNESS_PID=""
FAILED=0

kill_harnesses() {
  [[ -n "$HARNESS_PID" ]] && kill "$HARNESS_PID" 2>/dev/null || true
  HARNESS_PID=""
  # Windows 下 gradle 启动的 java 进程不会随父进程退出
  if command -v powershell >/dev/null 2>&1; then
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'HarnessMain' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1 || true
  fi
}
cleanup() { kill_harnesses; $CTL stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

wait_for() { # 文件 正则 次数 超时秒
  local file=$1 pattern=$2 count=${3:-1} timeout=${4:-120} i=0
  until [[ $(grep -cE "$pattern" "$file" 2>/dev/null || true) -ge $count ]]; do
    sleep 1; i=$((i + 1))
    if [[ $i -ge $timeout ]]; then echo "超时：等待 $pattern（$file）"; tail -20 "$file"; exit 1; fi
  done
}

step() { echo; echo "== $*"; }
run_py() { "$PYTHON" "$@" || FAILED=$((FAILED + 1)); }

start_harness() { # 项目根目录 日志 额外参数...
  local root=$1 logf=$2; shift 2
  local gw="$root/gradlew"; [[ "${OS:-}" == "Windows_NT" ]] && gw="$root/gradlew.bat"
  "$gw" -p "$root" :mcp-core:runHarness --console=plain -q --args="$*" >"$logf" 2>&1 &
  HARNESS_PID=$!
}

step "构建中继"
(cd "$ROOT/relay" && go build -o "$WORK/violet-mcp-relay" .)

step "不安全的监听配置必须拒绝启动"
if "$WORK/violet-mcp-relay" -data-dir "$WORK/data" -client-listen 0.0.0.0:18999 >"$WORK/insecure.log" 2>&1; then
  echo "FAIL: 中继在 0.0.0.0 明文监听时启动了"; FAILED=$((FAILED + 1))
elif grep -q "refusing to start" "$WORK/insecure.log"; then
  echo "PASS: $(grep -o 'refusing to start.*' "$WORK/insecure.log" | head -c 160)"
else
  echo "FAIL: 退出原因不对"; cat "$WORK/insecure.log"; FAILED=$((FAILED + 1))
fi

$CTL start
SECRET="$(tr -d '\r\n' <"$WORK/data/device.secret")"
PIN="$("$WORK/violet-mcp-relay" -data-dir "$WORK/data" -print-pin | tr -d '\r\n')"
TOKEN="e2e-$("$PYTHON" -c 'import secrets;print(secrets.token_urlsafe(32))')"
HARNESS_ARGS="--port $LOCAL_PORT --token $TOKEN --relay wss://127.0.0.1:$DEV_PORT/device --device-secret $SECRET --pin $PIN --slow-ms 8000"

step "启动 JVM harness（当前版本）并连接中继"
start_harness "$ROOT" "$WORK/harness.log" $HARNESS_ARGS
wait_for "$WORK/harness.log" "RELAY\] CONNECTED" 1 300

step "1/4 经中继：协议、鉴权、白名单、畸形请求"
run_py "$ROOT/tests/interop/test_mcp_interop.py" --url "http://127.0.0.1:$MCP_PORT/mcp" --token "$TOKEN" \
  --skip-call locale_info --expect-disabled storage_info

step "2/4 直连本地端点"
run_py "$ROOT/tests/interop/test_mcp_interop.py" --url "http://127.0.0.1:$LOCAL_PORT/mcp" --token "$TOKEN" \
  --skip-call locale_info --expect-disabled storage_info

step "3/4 稳定性：并发、延迟、超时、中继重启、中继不可达"
run_py "$ROOT/tests/interop/test_resilience.py" --url "http://127.0.0.1:$MCP_PORT/mcp" --token "$TOKEN" \
  --relay-ctl "$CTL" --request-timeout 5

step "4/4 日志泄露检查"
for f in "$WORK/relay.log" "$WORK/harness.log"; do
  if grep -qF -- "$TOKEN" "$f" || grep -qF -- "$SECRET" "$f"; then
    echo "FAIL: $f 中出现了令牌或设备密钥"; FAILED=$((FAILED + 1))
  else
    echo "PASS: $(basename "$f") 中没有令牌和设备密钥"
  fi
done

if [[ -n "${OLD_RELAY:-}" ]]; then
  step "混合版本 A：当前手机代码 ⇄ 旧版中继 1.0.0"
  BEFORE=$(grep -c "RELAY\] CONNECTED" "$WORK/harness.log" || true)
  $CTL stop
  cp "$OLD_RELAY" "$WORK/violet-mcp-relay-old"
  "$WORK/violet-mcp-relay-old" -data-dir "$WORK/data" -device-listen "127.0.0.1:$DEV_PORT" \
    -client-listen "127.0.0.1:$MCP_PORT" >"$WORK/relay-old.log" 2>&1 &
  OLD_PID=$!
  wait_for "$WORK/harness.log" "RELAY\] CONNECTED" $((BEFORE + 1)) 180
  run_py "$ROOT/tests/interop/test_mcp_interop.py" --url "http://127.0.0.1:$MCP_PORT/mcp" --token "$TOKEN" \
    --skip-call locale_info --expect-disabled storage_info --legacy
  kill "$OLD_PID" 2>/dev/null || true
  sleep 1
  $CTL start
fi

if [[ -n "${OLD_SRC_ZIP:-}" ]]; then
  step "混合版本 B：旧版手机代码 1.1.0 ⇄ 当前中继"
  kill_harnesses
  mkdir -p "$WORK/old" && (cd "$WORK/old" && unzip -q "$OLD_SRC_ZIP")
  OLD_ROOT="$(ls -d "$WORK"/old/*/ | head -1)"
  cp "$ROOT/local.properties" "$OLD_ROOT/" 2>/dev/null || true
  start_harness "$OLD_ROOT" "$WORK/harness-old.log" --port $LOCAL_PORT --token "$TOKEN" \
    --relay "wss://127.0.0.1:$DEV_PORT/device" --device-secret "$SECRET" --pin "$PIN"
  wait_for "$WORK/harness-old.log" "RELAY\] CONNECTED" 1 400
  run_py "$ROOT/tests/interop/test_mcp_interop.py" --url "http://127.0.0.1:$MCP_PORT/mcp" --token "$TOKEN" --legacy
fi

echo
if [[ $FAILED -eq 0 ]]; then echo "== 全部通过"; else echo "== 有 $FAILED 组失败"; exit 1; fi
