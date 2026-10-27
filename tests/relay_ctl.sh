#!/usr/bin/env bash
# 端到端测试用：启动 / 停止 / 重启电脑上的测试中继。
# 需要环境变量：WORK（工作目录，内含 violet-mcp-relay 和 data/）、DEV_PORT、MCP_PORT、RELAY_EXTRA（可选的额外参数）
set -euo pipefail
pidf="$WORK/relay.pid"

stop_relay() {
  if [[ -f "$pidf" ]]; then
    kill "$(cat "$pidf")" 2>/dev/null || true
    rm -f "$pidf"
  fi
  # 等端口释放
  for _ in $(seq 1 50); do
    curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:$MCP_PORT/health" || return 0
    sleep 0.1
  done
}

start_relay() {
  # shellcheck disable=SC2086
  "$WORK/violet-mcp-relay" -data-dir "$WORK/data" -device-listen "127.0.0.1:$DEV_PORT" \
    -client-listen "127.0.0.1:$MCP_PORT" -self-signed-hosts 127.0.0.1,localhost ${RELAY_EXTRA:-} \
    >>"$WORK/relay.log" 2>&1 &
  echo $! >"$pidf"
  for _ in $(seq 1 100); do
    curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:$MCP_PORT/health" && return 0
    sleep 0.1
  done
  echo "relay did not start"; tail -20 "$WORK/relay.log"; exit 1
}

case "${1:-}" in
  start) start_relay ;;
  stop) stop_relay ;;
  restart) stop_relay; start_relay ;;
  *) echo "usage: $0 start|stop|restart"; exit 2 ;;
esac
