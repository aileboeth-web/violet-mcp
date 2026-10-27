"""
用 Python MCP SDK 测试经中继访问时的稳定性与延迟（电脑端，配合 JVM harness 与测试中继）。

  python test_resilience.py --url http://127.0.0.1:18787/mcp --token <令牌> \
      --relay-ctl "bash tests/relay_ctl.sh" --request-timeout 5

harness 需要以 --slow-ms 8000 启动（locale_info 故意变慢），中继以 -request-timeout 5s 启动。
覆盖：并发调用、分阶段耗时、工具超时后会话继续可用、中继重启后自动恢复（同一会话 / 新会话）、中继完全不可达时快速失败。
"""
from __future__ import annotations

import argparse
import asyncio
import os
import shlex
import statistics
import subprocess
import sys
import time
from datetime import timedelta
from importlib import metadata

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
from mcp.shared.exceptions import McpError

os.environ["NO_PROXY"] = os.environ["no_proxy"] = "127.0.0.1,localhost,::1"


class Report:
    def __init__(self) -> None:
        self.rows: list[tuple[str, bool, str]] = []

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.rows.append((name, ok, detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""), flush=True)
        return ok

    def note(self, name: str, detail: str) -> None:
        print(f"[INFO] {name} — {detail}", flush=True)

    @property
    def failed(self) -> int:
        return sum(1 for _, ok, _ in self.rows if not ok)


def health(base: str) -> dict:
    try:
        return httpx.get(base + "/health", timeout=2, trust_env=False).json()
    except Exception:  # noqa: BLE001
        return {}


def wait_device(base: str, timeout: float) -> float | None:
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        if health(base).get("deviceConnected"):
            return time.monotonic() - t0
        time.sleep(0.2)
    return None


def server_timing(url: str, token: str) -> dict[str, float]:
    """发一个原始请求，解析 Server-Timing 各阶段（毫秒）"""
    r = httpx.post(url, json={"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "battery"}},
                   headers={"Authorization": f"Bearer {token}", "Accept": "application/json, text/event-stream"},
                   timeout=10, trust_env=False)
    out = {}
    for part in r.headers.get("server-timing", "").split(","):
        if ";dur=" in part:
            k, v = part.strip().split(";dur=")
            out[k] = float(v)
    return out


def session(url: str, token: str):
    return streamablehttp_client(url, headers={"Authorization": f"Bearer {token}"}, timeout=timedelta(seconds=30))


async def run(a: argparse.Namespace, r: Report) -> None:
    base = a.url.rsplit("/mcp", 1)[0]
    ctl = shlex.split(a.relay_ctl)
    r.check("设备已连接中继", wait_device(base, 60) is not None)

    # ---------- 1. 并发 + 延迟 ----------
    async with session(a.url, a.token) as (rs, ws, _):
        async with ClientSession(rs, ws) as s:
            await s.initialize()
            t0 = time.perf_counter()
            results = await asyncio.gather(*[s.call_tool("battery", {}) for _ in range(20)], return_exceptions=True)
            ok = sum(1 for x in results if not isinstance(x, BaseException) and not x.isError)
            r.check("同一会话 20 个并发 tools/call 全部成功", ok == 20, f"{ok}/20，用时 {(time.perf_counter() - t0) * 1000:.0f} ms")

            lat = []
            for _ in range(30):
                t0 = time.perf_counter()
                await s.call_tool("battery", {})
                lat.append((time.perf_counter() - t0) * 1000)
            p50 = statistics.median(lat)
            p95 = sorted(lat)[int(len(lat) * 0.95) - 1]
            r.check("Violet 侧单次 tools/call 延迟（本机中继）p95 < 500 ms", p95 < 500, f"p50={p50:.1f} ms p95={p95:.1f} ms")
            st = server_timing(a.url, a.token)
            r.check("响应头 Server-Timing 含各阶段耗时", all(k in st for k in ("relay", "rtt", "phone", "tool")),
                    ", ".join(f"{k}={v:.1f}ms" for k, v in st.items()))

            # ---------- 2. 工具超时：只让这一次调用失败，会话继续可用 ----------
            if a.request_timeout:
                t0 = time.perf_counter()
                err = None
                try:
                    await s.call_tool("locale_info", {})
                except McpError as e:
                    err = e
                took = time.perf_counter() - t0
                r.check("手机无响应时按中继超时返回明确错误（McpError -32011）",
                        err is not None and err.error.code == -32011 and "did not respond" in err.error.message,
                        f"{took:.1f} s，{err.error.message if err else '没有报错'}")
                r.check("超时时间符合配置（不会无限等待）", a.request_timeout - 0.5 <= took <= a.request_timeout + 3, f"{took:.1f} s")
                res = await s.call_tool("battery", {})
                r.check("超时后同一会话仍可继续调用", not res.isError)

    # ---------- 3. 中继重启 ----------
    async with session(a.url, a.token) as (rs, ws, _):
        async with ClientSession(rs, ws) as s:
            await s.initialize()
            await s.call_tool("battery", {})
            subprocess.run(ctl + ["restart"], check=True)
            t = wait_device(base, 90)
            r.check("中继重启后手机自动重连", t is not None, f"{t:.1f} s" if t is not None else "超时")
            try:
                res = await asyncio.wait_for(s.call_tool("battery", {}), 20)
                r.note("中继重启后同一 MCP 会话", "继续可用" if not res.isError else "返回错误")
            except BaseException as e:  # noqa: BLE001
                r.note("中继重启后同一 MCP 会话", f"不可用（{type(e).__name__}），需要客户端重新建立会话")
    async with session(a.url, a.token) as (rs, ws, _):
        async with ClientSession(rs, ws) as s:
            await s.initialize()
            res = await s.call_tool("battery", {})
            r.check("中继重启后新建会话正常", not res.isError)

    # ---------- 4. 中继完全不可达：快速失败而不是挂住 ----------
    subprocess.run(ctl + ["stop"], check=True)
    t0 = time.perf_counter()
    failed = False
    try:
        async with session(a.url, a.token) as (rs, ws, _):
            async with ClientSession(rs, ws) as s:
                await asyncio.wait_for(s.initialize(), 30)
    except BaseException:  # noqa: BLE001
        failed = True
    took = time.perf_counter() - t0
    r.check("中继不可达时客户端快速失败", failed and took < 10, f"{took:.1f} s")
    subprocess.run(ctl + ["start"], check=True)
    t = wait_device(base, 90)
    r.check("中继恢复后手机重新连接", t is not None, f"{t:.1f} s" if t is not None else "超时")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--relay-ctl", required=True, help="控制测试中继的命令，接受 start/stop/restart 参数")
    ap.add_argument("--request-timeout", type=float, default=0, help="中继 -request-timeout（秒）；0 表示跳过超时测试")
    a = ap.parse_args()
    print(f"Python MCP SDK {metadata.version('mcp')}\n")
    r = Report()
    asyncio.run(run(a, r))
    print(f"\n共 {len(r.rows)} 项，失败 {r.failed} 项")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
