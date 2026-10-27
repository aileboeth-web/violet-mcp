package com.mcp.server.core

import org.json.JSONObject

/**
 * 与传输无关的 HTTP 路由 + 鉴权。本地 socket 服务器和中继客户端都把请求交给它，
 * 所以两条路径的鉴权和工具限制完全一致。
 *
 * - `GET  /health`：不鉴权，只返回 {"ok":true}，不含任何设备或配置信息
 * - `POST /mcp`   ：Bearer 鉴权，Streamable HTTP（无状态，响应一律 application/json）
 * - `GET  /status`：Bearer 鉴权，服务器状态（不含任何密钥）
 * - `GET/DELETE /mcp`：405（不提供服务器推送流，也没有会话可删除）
 * - 带 `Origin` 头的请求一律 403（浏览器来源，防 DNS rebinding）
 *
 * `/mcp` 响应带 `Server-Timing: phone;dur=<收到请求到生成响应>, tool;dur=<工具执行>`（毫秒，单调时钟），
 * 中继会在此基础上追加自己的耗时，Violet 收到的响应头里能看到各阶段时间。
 */
class McpEndpoint(
    private val protocol: McpProtocol,
    private val tokenProvider: () -> String,
    private val statusProvider: () -> JSONObject = { JSONObject() },
    private val log: CoreLog = NoopLog,
    /** 每个请求处理完后回调（只含路径、状态码和耗时），用于诊断页 */
    private val onRequest: (RequestRecord) -> Unit = {},
) {

    /** 一次请求的统计信息，不含请求内容和凭据 */
    data class RequestRecord(
        val method: String,
        val path: String,
        val status: Int,
        val handleMs: Double,
        val toolMs: Double,
    )

    /**
     * 读取请求体之前的检查（鉴权、Origin、路由、方法）。
     * 返回非 null 表示直接用该响应结束请求，传输层不必再读请求体。
     */
    fun precheck(method: String, rawPath: String, headers: Map<String, String>): HttpResponse? {
        val path = normalize(rawPath)
        if (headers.containsKey("origin")) {
            log.warn("拒绝带 Origin 头的请求: $method $path")
            return jsonError(403, "Forbidden origin")
        }
        if (path == "/health") {
            return if (method == "GET" || method == "HEAD") null else methodNotAllowed("GET")
        }
        if (path != "/mcp" && path != "/status") return jsonError(404, "Not Found")
        if (!Secrets.checkBearer(headers["authorization"], tokenProvider())) {
            log.warn("鉴权失败: $method $path")
            return HttpResponse.json(401, rpcError(-32001, "Unauthorized").toString(),
                mapOf("WWW-Authenticate" to "Bearer realm=\"mcp\""))
        }
        if (path == "/status") return if (method == "GET") null else methodNotAllowed("GET")
        // /mcp
        return when (method) {
            "POST" -> {
                val ct = headers["content-type"]?.lowercase() ?: ""
                if (!ct.startsWith("application/json")) {
                    return jsonError(415, "Content-Type must be application/json")
                }
                val pv = headers["mcp-protocol-version"]
                if (pv != null && pv !in McpProtocol.SUPPORTED_VERSIONS) {
                    return jsonError(400, "Unsupported MCP-Protocol-Version: $pv")
                }
                null
            }
            else -> methodNotAllowed("POST")
        }
    }

    fun handle(req: HttpRequest): HttpResponse {
        var toolNanos = 0L
        val resp = precheck(req.method, req.path, req.headers) ?: when (normalize(req.path)) {
            "/health" -> HttpResponse.json(200, JSONObject().put("ok", true).toString())
            "/status" -> HttpResponse.json(200, statusProvider().toString(2))
            else -> when (val outcome = protocol.handleBody(req.bodyText())) {
                is McpProtocol.Outcome.Accepted -> HttpResponse.empty(202)
                is McpProtocol.Outcome.Reply -> {
                    toolNanos = outcome.toolNanos
                    HttpResponse.json(200, outcome.json)
                }
            }
        }
        val handleMs = (System.nanoTime() - req.receivedAtNanos) / 1e6
        val toolMs = toolNanos / 1e6
        try {
            onRequest(RequestRecord(req.method, normalize(req.path), resp.status, handleMs, toolMs))
        } catch (_: Exception) {
        }
        return HttpResponse(resp.status,
            resp.headers + ("Server-Timing" to "phone;dur=${fmt(handleMs)}, tool;dur=${fmt(toolMs)}"), resp.body)
    }

    private fun fmt(ms: Double): String = String.format(java.util.Locale.US, "%.1f", ms)

    private fun normalize(rawPath: String): String {
        val p = rawPath.substringBefore('?').substringBefore('#')
        return if (p.length > 1) p.trimEnd('/') else p
    }

    private fun methodNotAllowed(allow: String) =
        HttpResponse.json(405, rpcError(-32000, "Method not allowed").toString(), mapOf("Allow" to allow))

    private fun jsonError(status: Int, message: String) =
        HttpResponse.json(status, rpcError(-32000, message).toString())

    private fun rpcError(code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
}
