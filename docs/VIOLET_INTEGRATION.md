# Violet 接入示例

Violet 的后台“工具”页可以直接添加 MCP 服务器（填地址和 `Authorization` 请求头，再给工具分类、授权给 Agent）；也可以在配置文件的 `MCP_SERVERS`（JSON 数组）里写。服务器名用 `android-phone`，Violet 自带这 14 个只读工具的分类模板。
本服务器对 Violet 来说就是一个普通的 Streamable HTTP MCP 端点，**不需要修改 Violet 代码**。

## 1. 配置

推荐中继和 Violet 在同一台机器上（中继只监听 `127.0.0.1:8787`）：

| 项 | 值 |
|---|---|
| URL | `http://127.0.0.1:8787/mcp` |
| 传输 | Streamable HTTP |
| 请求头 | `Authorization: Bearer <手机 App “安全”页复制的访问令牌>` |

示意（**字段名以 Violet 现有 schema 为准**，这里只说明需要哪三项）：

```json
[
  {
    "name": "android-phone",
    "transport": "streamable_http",
    "url": "http://127.0.0.1:8787/mcp",
    "headers": { "Authorization": "Bearer 手机上复制的访问令牌" }
  }
]
```

从 1.1.0 升级到 1.2.0 时，令牌和 URL 都不变，Violet 的配置不需要改。

## 2. 等价的 Python 代码（Python MCP SDK 1.x）

```python
import asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
from mcp.shared.exceptions import McpError

URL = "http://127.0.0.1:8787/mcp"
HEADERS = {"Authorization": "Bearer 手机上复制的访问令牌"}

async def main():
    async with streamablehttp_client(URL, headers=HEADERS) as (read, write, _):
        async with ClientSession(read, write) as s:
            await s.initialize()
            print([t.name for t in (await s.list_tools()).tools])
            try:
                r = await s.call_tool("get_foreground_app", {})
                print(r.structuredContent)  # {"state": "current", "app": "...", "package": "...", ...}
            except McpError as e:
                # 手机暂时离线 / 超时：只有这一次调用失败，会话仍然可以继续用
                print("设备暂不可用：", e.error.code, e.error.message)

asyncio.run(main())
```

已测试的 SDK 版本：1.8.0（协商协议 2024-11-05）和 1.30.0（2025-11-25）。

**注意**：中继进程完全不可达（例如 VPS 上的中继停止了）时：

- SDK 1.30.0 会在约 3 秒内抛出连接错误；
- SDK 1.8.0 不会抛出，而是一直等到调用方自己的超时。

使用旧版 SDK 时，建议在外层加上超时：`await asyncio.wait_for(s.initialize(), 15)`、`await asyncio.wait_for(s.call_tool(...), 30)`。

## 3. 返回值与错误

| 情况 | Violet 看到的结果 | 会话是否还能用 |
|---|---|---|
| 正常 | 工具结果（`content` 文本 + `structuredContent`） | 能 |
| 工具内部错误、参数错误、使用情况权限未授予 | 工具结果，`isError: true`，`errorCode` 给出原因（例如 `PERMISSION_DENIED`） | 能 |
| 工具被用户在手机上关闭，或调用了不存在的工具 | `McpError -32602 Unknown tool` | 能 |
| 手机离线（等待 `-offline-wait` 后仍未连上） | `McpError -32010 Device offline` | 能 |
| 手机在 `-request-timeout`（默认 20 秒）内没有响应 | `McpError -32011 Device did not respond within 20s`；中继同时探测连接，失效就断开，让手机重连 | 能 |
| 处理途中手机断开 | `McpError -32012` | 能 |
| 令牌错误 | HTTP 401，SDK 抛出异常并结束会话 | 否（属于配置错误） |
| 中继进程没有运行 | 连接被拒绝，SDK 抛出异常 | 否；Violet 应在下次调用时重新建立会话 |

`-32010`、`-32011`、`-32012` 的 `error.data.retryable` 为 `true`。

## 4. 前台应用的读法

`get_foreground_app` 默认只返回：

```json
{"state": "current", "app": "微信", "package": "com.tencent.mm", "observed_at": "2026-10-10T10:02:11.000+08:00", "age_seconds": 75}
```

| state | 含义 | app / package |
|---|---|---|
| `current` | 亮屏、已解锁，且该应用进入前台后没有离开事件 | 给出 |
| `screen_off` | 屏幕熄灭 | null |
| `locked` | 停在锁屏界面 | null |
| `unknown` | 无法确定（例如应用已离开前台、停在通知栏，或没有近期事件） | null |
| `permission_denied` | 手机未授予使用情况访问权限 | null |

`observed_at` 是支持该结论的最近一次系统事件时间。`current` 状态下它就是应用进入前台的时间，`age_seconds` 大只说明用户在这个应用里停留得久。
如果用户在手机上开启了“前台应用：详细模式”，非 current 状态下还会额外返回 `last_app`、`last_package` 和 `reason`。

## 5. 定位延迟

中继给每个响应加上 `Server-Timing` 头，并在日志中记录同样的内容（毫秒，单调时钟）：

```
Server-Timing: relay;dur=12.4, wait_device;dur=0.0, rtt;dur=11.9, phone;dur=3.1, tool;dur=2.0
```

| 字段 | 含义 |
|---|---|
| `relay` | 请求进入中继到响应就绪的总耗时 |
| `wait_device` | 等待手机上线的时间（手机在线时为 0） |
| `rtt` | 中继发给手机到收到手机响应（含网络往返） |
| `phone` | 手机收到请求到发出响应 |
| `tool` | 工具执行本身 |

在中继上查看：`journalctl -u violet-mcp-relay | grep "client POST"`，每行有 `total=`、`rtt=`、`phone=`、`tool=`。
如果 Violet 一次任务耗时很长（例如之前观察到的约 198 秒），而这里的 `total` 只有几十毫秒，那么瓶颈不在手机 MCP 这一环。
