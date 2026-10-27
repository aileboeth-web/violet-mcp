"""
用官方 Python MCP SDK（1.x）连接只读 MCP Server，验证兼容性和安全边界。

可以指向：
  - 电脑上的 JVM harness（与 App 同一套协议/鉴权/中继代码，模拟工具数据）
  - 手机本地端点（adb forward tcp:1145 tcp:1145 后用 http://127.0.0.1:1145/mcp）
  - VPS 中继的 /mcp（Violet 实际使用的路径）

用法：
  python test_mcp_interop.py --url http://127.0.0.1:8787/mcp --token <MCP 令牌> [--device]

--device 表示连接的是真机：会额外检查 3 个感知工具的返回字段（queryTime、freshness 等）。
退出码：0 全部通过；1 有失败项。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
import time
from datetime import timedelta
from importlib import metadata
from pathlib import Path

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
from mcp.shared.exceptions import McpError

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "mcp-core/src/main/kotlin/com/mcp/server/core/ReadOnlyPolicy.kt"


def load_policy() -> tuple[set[str], list[str]]:
    """直接解析 Kotlin 源码中的白名单和已移除工具列表，避免两边不一致。"""
    src = POLICY.read_text(encoding="utf-8")
    allowed_block = re.search(r"ALLOWED_TOOLS: Set<String> = linkedSetOf\((.*?)\n    \)", src, re.S).group(1)
    removed_block = re.search(r"REMOVED_TOOLS: List<String> = listOf\((.*?)\n    \)", src, re.S).group(1)
    strip = lambda b: re.sub(r"//[^\n]*", "", b)
    return set(re.findall(r'"([^"]+)"', strip(allowed_block))), re.findall(r'"([^"]+)"', strip(removed_block))


ALLOWED, REMOVED = load_policy()
# 额外的变体：大小写、空白、原项目桥接前缀、路径穿越式名字
EXTRA_FORBIDDEN = ["SHELL", "shell ", " shell", "Shell", "MCP1_shell", "bridge_shell", "../shell",
                   "write_file\u0000", "tools/call", "device_info; rm -rf /"]


class Report:
    def __init__(self) -> None:
        self.rows: list[tuple[str, bool, str]] = []

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.rows.append((name, ok, detail))
        mark = "PASS" if ok else "FAIL"
        print(f"[{mark}] {name}" + (f" — {detail}" if detail else ""), flush=True)
        return ok

    @property
    def failed(self) -> int:
        return sum(1 for _, ok, _ in self.rows if not ok)


def sample_args(schema: dict) -> dict:
    """根据 inputSchema 的 required + examples 生成最小参数。"""
    props = schema.get("properties", {}) or {}
    args = {}
    for key in schema.get("required", []) or []:
        p = props.get(key, {})
        if p.get("examples"):
            args[key] = p["examples"][0]
        elif p.get("enum"):
            args[key] = p["enum"][0]
        elif p.get("type") == "integer":
            args[key] = p.get("minimum", 1)
        elif p.get("type") == "boolean":
            args[key] = False
        else:
            args[key] = "com.android.settings"
    return args


def find_status_codes(e: BaseException) -> list[int]:
    out = []
    if isinstance(e, httpx.HTTPStatusError):
        out.append(e.response.status_code)
    for sub in getattr(e, "exceptions", []) or []:
        out += find_status_codes(sub)
    if e.__cause__ is not None:
        out += find_status_codes(e.__cause__)
    return out


def raw_checks(r: Report, url: str, token: str, legacy: bool = False) -> None:
    """不经过 SDK 的 HTTP 层安全检查。"""
    base = url.rsplit("/mcp", 1)[0]
    init = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "raw", "version": "0"}}}
    list_req = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    hdr = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    with httpx.Client(timeout=30, trust_env=False) as c:
        resp = c.post(url, json=list_req, headers=hdr)
        r.check("无令牌调用 tools/list 被拒绝（401）", resp.status_code == 401, f"HTTP {resp.status_code}")
        r.check("401 响应不含工具名", not any(t in resp.text for t in ALLOWED))
        resp = c.post(url, json=list_req, headers={**hdr, "Authorization": "Bearer wrong-token-0123456789abcdef"})
        r.check("错误令牌被拒绝（401）", resp.status_code == 401, f"HTTP {resp.status_code}")
        resp = c.post(url, json=list_req, headers={**hdr, "Authorization": f"Basic {token}"})
        r.check("非 Bearer 方案被拒绝（401）", resp.status_code == 401, f"HTTP {resp.status_code}")
        resp = c.post(url, json=list_req, headers={**hdr, "Authorization": f"Bearer {token}", "Origin": "https://evil.example"})
        r.check("浏览器 Origin 被拒绝（403）", resp.status_code == 403, f"HTTP {resp.status_code}")
        for path in ["/tools", "/info", "/sse", "/", "/status"]:
            resp = c.get(base + path)
            leaked = any(t in resp.text for t in ALLOWED)
            r.check(f"未认证 GET {path} 不暴露工具/状态", resp.status_code in (401, 404) and not leaked,
                    f"HTTP {resp.status_code}")
        resp = c.get(base + "/health")
        r.check("/health 只返回最小信息", resp.status_code == 200 and "token" not in resp.text.lower()
                and not any(t in resp.text for t in ALLOWED), resp.text[:80])

        auth = {**hdr, "Authorization": f"Bearer {token}"}
        resp = c.get(url, headers={"Authorization": f"Bearer {token}", "Accept": "text/event-stream"})
        r.check("GET /mcp 返回 405（无服务器推送流）", resp.status_code == 405, f"HTTP {resp.status_code}")
        resp = c.post(url, json={"jsonrpc": "2.0", "method": "notifications/initialized"}, headers=auth)
        r.check("通知返回 202 且无响应体", resp.status_code == 202 and resp.content == b"", f"HTTP {resp.status_code}")
        resp = c.post(url, json=init, headers=auth)
        ok = resp.status_code == 200 and resp.headers.get("content-type", "").startswith("application/json")
        r.check("initialize 返回 application/json", ok, f"HTTP {resp.status_code}")
        if ok:
            r.check("协议版本协商 2025-06-18", resp.json()["result"]["protocolVersion"] == "2025-06-18")
        resp = c.post(url, content=b"x" * (1024 * 1024 + 10), headers=auth)
        r.check("超过 1 MiB 的请求体被拒绝（413）", resp.status_code == 413, f"HTTP {resp.status_code}")

        # 畸形请求：都应被安全拒绝，而且不会执行任何工具
        def rpc_error(content: bytes) -> int | None:
            rr = c.post(url, content=content, headers=auth)
            try:
                j = rr.json()
            except ValueError:
                return None
            if isinstance(j, list):
                return j[0].get("error", {}).get("code") if j else None
            return j.get("error", {}).get("code")
        deep = b"[" * 100_000 + b"]" * 100_000
        r.check("10 万层嵌套 JSON 被拒绝（解析错误，服务不崩溃）", rpc_error(deep) == -32700)
        r.check("非法 UTF-8 请求体被拒绝", rpc_error(b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"\xff\xfe"}}') in (-32700, -32602, -32600))
        if legacy:
            print("[SKIP] 1.2 新增的检查（jsonrpc 版本、批量上限、Server-Timing）：旧版本不支持")
        else:
            r.check("jsonrpc 版本不是 2.0 被拒绝",
                    rpc_error(b'{"jsonrpc":"1.0","id":1,"method":"tools/call","params":{"name":"battery"}}') == -32600)
            big_batch = json.dumps([{"jsonrpc": "2.0", "id": i, "method": "ping"} for i in range(33)]).encode()
            r.check("超过 32 条的批量请求被拒绝", rpc_error(big_batch) == -32600)
            st = c.post(url, json={"jsonrpc": "2.0", "id": 1, "method": "ping"}, headers=auth).headers.get("server-timing", "")
            r.check("响应带 Server-Timing（毫秒级分阶段耗时）", "phone;dur=" in st, st)
        dup = b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"battery","name":"shell","arguments":{"command":"id"}}}'
        dup_resp = c.post(url, content=dup, headers=auth).text
        r.check("重复键（name 先 battery 后 shell）不能执行 shell", '"command"' not in dup_resp and "uid=" not in dup_resp)

        # 绕过尝试：批量请求、不同 id 类型、arguments 携带危险参数
        batch = [{"jsonrpc": "2.0", "id": i, "method": "tools/call", "params": {"name": n, "arguments": {"command": "id"}}}
                 for i, n in enumerate(["shell", "write_file", "http_request", "mcp_javascript"])]
        resp = c.post(url, json=batch, headers=auth)
        arr = resp.json() if resp.status_code == 200 else []
        r.check("批量 tools/call 中的写入类工具全部被拒绝",
                isinstance(arr, list) and len(arr) == 4 and all("error" in x for x in arr), f"HTTP {resp.status_code}")


async def sdk_checks(r: Report, url: str, token: str, device: bool,
                     skip: set[str] = frozenset(), disabled: set[str] = frozenset()) -> None:
    # 无令牌时 SDK 连接应失败
    try:
        async with streamablehttp_client(url, timeout=timedelta(seconds=15)) as (rs, ws, _):
            async with ClientSession(rs, ws) as s:
                await asyncio.wait_for(s.initialize(), 15)
        r.check("SDK 无令牌无法 initialize", False, "居然成功了")
    except BaseException as e:  # noqa: BLE001 — SDK 把 HTTPStatusError 包在 ExceptionGroup 里
        # 新版 SDK 把 401 作为 HTTPStatusError 抛出；旧版（如 1.8）只记录日志后以超时/其他异常结束。
        # 服务端返回 401 已由 raw_checks 断言，这里只要求“无法完成初始化”。
        codes = find_status_codes(e)
        r.check("SDK 无令牌无法 initialize", not isinstance(e, AssertionError),
                f"{type(e).__name__} status={codes or '（SDK 未暴露状态码）'}")

    headers = {"Authorization": f"Bearer {token}"}
    async with streamablehttp_client(url, headers=headers, timeout=timedelta(seconds=30)) as (rs, ws, get_sid):
        async with ClientSession(rs, ws) as s:
            init = await s.initialize()
            r.check("SDK initialize 成功", True,
                    f"server={init.serverInfo.name} {init.serverInfo.version} protocol={init.protocolVersion}")
            r.check("服务器只声明 tools 能力", init.capabilities.tools is not None
                    and init.capabilities.resources is None and init.capabilities.prompts is None)
            await s.send_ping()
            r.check("ping", True)

            tools = (await s.list_tools()).tools
            names = [t.name for t in tools]
            r.check("tools/list 非空", len(names) > 0, f"{len(names)} 个: {', '.join(names)}")
            r.check("tools/list 全部在只读白名单内", set(names) <= ALLOWED, str(set(names) - ALLOWED))
            r.check("tools/list 不含任何已移除的写入类工具", not (set(names) & set(REMOVED)))
            r.check("每个工具都标注 readOnlyHint=true / destructiveHint=false",
                    all(t.annotations and t.annotations.readOnlyHint and t.annotations.destructiveHint is False for t in tools))

            for name in disabled:
                r.check(f"已关闭的工具 {name} 不在 tools/list 中", name not in names)
                try:
                    res = await s.call_tool(name, {})
                    r.check(f"已关闭的工具 {name} 无法直接调用", False, "返回了结果")
                except McpError as e:
                    r.check(f"已关闭的工具 {name} 无法直接调用", e.error.code == -32602, e.error.message)

            for t in tools:
                if t.name in skip:
                    continue
                args = sample_args(t.inputSchema)
                t0 = time.perf_counter()
                res = await s.call_tool(t.name, args)
                ms = (time.perf_counter() - t0) * 1000
                text = res.content[0].text if res.content else ""
                try:
                    payload = json.loads(text)
                except ValueError:
                    payload = {}
                err = payload.get("errorCode") if isinstance(payload, dict) else None
                # 真机未授权使用情况访问时，前台应用/使用时长返回 PERMISSION_DENIED 属于预期
                acceptable = (not res.isError) or err == "PERMISSION_DENIED"
                r.check(f"调用 {t.name}", acceptable, f"{ms:.0f} ms" + (f" errorCode={err}" if err else ""))
                if device and not res.isError and t.name in ("get_foreground_app", "get_app_usage_stats", "get_screen_state"):
                    sc = getattr(res, "structuredContent", None) or payload
                    has = all(k in sc for k in ("queryTime", "freshness"))
                    r.check(f"{t.name} 带 queryTime 与 freshness", has)
                    if t.name == "get_foreground_app":
                        r.check("get_foreground_app 状态字段", sc.get("status") in ("current", "recent", "unknown")
                                and isinstance(sc.get("isCurrent"), bool), f"status={sc.get('status')} app={sc.get('appName')}")
                        if sc.get("status") == "recent":
                            r.check("recent 状态不标记为当前", sc.get("isCurrent") is False)
                    if t.name == "get_screen_state":
                        r.check("get_screen_state 为实时数据", sc["freshness"].get("realtime") is True,
                                f"screenOn={sc.get('screenOn')} locked={sc.get('keyguardLocked')}")
                    if t.name == "get_app_usage_stats":
                        apps = sc.get("apps", [])
                        r.check("get_app_usage_stats 返回应用名+包名", all("packageName" in a and "appName" in a for a in apps),
                                f"{len(apps)} 个应用，合计 {sc.get('totalForeground')}")

            # 参数错误应作为工具错误返回（模型可读），而不是协议错误
            res = await s.call_tool("app_info", {}) if "app_info" in names else None
            if res is not None:
                r.check("缺少必填参数返回 isError 工具结果", res.isError is True)

            # 所有已移除的写入类工具 + 名字变体：既不在列表里，也无法调用
            blocked = 0
            for name in REMOVED + EXTRA_FORBIDDEN:
                try:
                    res = await s.call_tool(name, {"command": "id", "path": "/sdcard/x", "content": "x", "url": "http://example.com"})
                    ok = res.isError is True and not (getattr(res, "structuredContent", None) or {}).get("ok")
                    if not ok:
                        r.check(f"已移除工具 {name!r} 不可调用", False, "返回了成功结果")
                        continue
                except McpError as e:
                    if e.error.code != -32602:
                        r.check(f"已移除工具 {name!r} 不可调用", False, f"意外的错误码 {e.error.code}")
                        continue
                blocked += 1
            total = len(REMOVED) + len(EXTRA_FORBIDDEN)
            r.check(f"已移除的写入类工具全部无法调用（{blocked}/{total}）", blocked == total)

            # 调用后工具仍然只有白名单
            names2 = [t.name for t in (await s.list_tools()).tools]
            r.check("多次调用后 tools/list 不变", names2 == names)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--device", action="store_true", help="连接的是真机，额外检查感知工具字段")
    ap.add_argument("--legacy", action="store_true", help="新旧版本混合测试：跳过 1.2 新增功能的检查")
    ap.add_argument("--skip-call", default="", help="逗号分隔：列出但不逐个调用的工具（例如测试用的慢工具）")
    ap.add_argument("--expect-disabled", default="", help="逗号分隔：应处于关闭状态的工具，检查其无法列出也无法调用")
    a = ap.parse_args()
    host = httpx.URL(a.url).host
    if host in ("127.0.0.1", "localhost", "::1"):
        # 本机目标不走系统 HTTP 代理（SDK 内部的 httpx 默认读取 HTTP(S)_PROXY）
        os.environ["NO_PROXY"] = os.environ["no_proxy"] = "127.0.0.1,localhost,::1"

    print(f"Python MCP SDK {metadata.version('mcp')} / httpx {metadata.version('httpx')}")
    print(f"目标 {a.url}；白名单 {len(ALLOWED)} 个工具，已移除 {len(REMOVED)} 个\n")
    r = Report()
    raw_checks(r, a.url, a.token, a.legacy)
    split = lambda v: {x.strip() for x in v.split(",") if x.strip()}
    asyncio.run(sdk_checks(r, a.url, a.token, a.device, split(a.skip_call), split(a.expect_disabled)))
    print(f"\n共 {len(r.rows)} 项，失败 {r.failed} 项")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
