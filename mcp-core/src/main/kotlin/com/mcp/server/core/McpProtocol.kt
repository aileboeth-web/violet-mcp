package com.mcp.server.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * MCP JSON-RPC 处理（无状态）。
 *
 * 只实现只读服务器需要的方法：initialize、ping、tools/list、tools/call。
 * 通知（notifications/ *）和客户端发来的响应不产生输出，HTTP 层据此返回 202。
 */
class McpProtocol(
    private val registry: ToolRegistry,
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String,
    private val log: CoreLog = NoopLog,
    /** 每次工具调用（只传工具名、是否出错、执行耗时，不传参数和结果）用于统计和日志 */
    private val onToolCall: (name: String, isError: Boolean, toolNanos: Long) -> Unit = { _, _, _ -> },
) {

    sealed class Outcome {
        /** 只有通知或响应：HTTP 202，无响应体 */
        object Accepted : Outcome()

        /** 需要返回的 JSON（单个对象或批量数组）；toolNanos 为本次请求中工具执行的总耗时 */
        class Reply(val json: String, val toolNanos: Long = 0L) : Outcome()
    }

    /** 单次 handleBody 内累计的工具耗时（同一线程内使用） */
    private val toolNanosAcc = ThreadLocal<LongArray>()

    fun handleBody(body: String): Outcome {
        // 先做嵌套深度检查：JSONTokener 是递归解析，极深嵌套会栈溢出
        if (nestingTooDeep(body, MAX_NESTING)) {
            return Outcome.Reply(error(null, PARSE_ERROR, "Parse error: nesting too deep").toString())
        }
        val root = try {
            JSONTokener(body).nextValue()
        } catch (e: JSONException) {
            return Outcome.Reply(error(null, PARSE_ERROR, "Parse error").toString())
        } catch (e: StackOverflowError) {
            return Outcome.Reply(error(null, PARSE_ERROR, "Parse error").toString())
        }
        val acc = LongArray(1)
        toolNanosAcc.set(acc)
        try {
            return when (val o = dispatch(root)) {
                is Outcome.Reply -> Outcome.Reply(o.json, acc[0])
                else -> o
            }
        } finally {
            toolNanosAcc.remove()
        }
    }

    private fun dispatch(root: Any?): Outcome {
        return when (root) {
            is JSONObject -> handleMessage(root)?.let { Outcome.Reply(it.toString()) } ?: Outcome.Accepted
            is JSONArray -> {
                if (root.length() == 0) return Outcome.Reply(error(null, INVALID_REQUEST, "Empty batch").toString())
                if (root.length() > MAX_BATCH) {
                    return Outcome.Reply(error(null, INVALID_REQUEST, "Batch too large (max $MAX_BATCH)").toString())
                }
                val out = JSONArray()
                for (i in 0 until root.length()) {
                    val item = root.opt(i)
                    val r = if (item is JSONObject) handleMessage(item)
                    else error(null, INVALID_REQUEST, "Invalid Request")
                    if (r != null) out.put(r)
                }
                if (out.length() == 0) Outcome.Accepted else Outcome.Reply(out.toString())
            }
            else -> Outcome.Reply(error(null, INVALID_REQUEST, "Invalid Request").toString())
        }
    }

    /** 处理单条消息；通知/响应返回 null */
    private fun handleMessage(msg: JSONObject): JSONObject? {
        val hasId = msg.has("id") && !msg.isNull("id")
        val id: Any? = if (hasId) msg.get("id") else null
        if (msg.opt("jsonrpc") != "2.0") return error(id, INVALID_REQUEST, "Invalid Request: jsonrpc must be \"2.0\"")
        val methodValue = msg.opt("method")
        if (methodValue == null) {
            // 客户端发来的 JSON-RPC 响应（本服务器不发请求），忽略
            return if (msg.has("result") || msg.has("error")) null
            else error(id, INVALID_REQUEST, "Invalid Request")
        }
        if (methodValue !is String) return error(id, INVALID_REQUEST, "Invalid Request")
        if (!hasId) return null // 通知：notifications/initialized、notifications/cancelled 等

        val paramsValue = msg.opt("params")
        if (paramsValue != null && paramsValue != JSONObject.NULL && paramsValue !is JSONObject) {
            return error(id, INVALID_PARAMS, "params must be an object")
        }
        val params = paramsValue as? JSONObject ?: JSONObject()
        return try {
            when (methodValue) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, registry.toListResult())
                "tools/call" -> callTool(id, params)
                else -> error(id, METHOD_NOT_FOUND, "Method not found: $methodValue")
            }
        } catch (e: Exception) {
            log.error("处理 $methodValue 失败: ${e.javaClass.simpleName}")
            error(id, INTERNAL_ERROR, "Internal error")
        }
    }

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion", "")
        val version = if (requested in SUPPORTED_VERSIONS) requested else LATEST_VERSION
        val client = params.optJSONObject("clientInfo")
        log.info("客户端初始化: ${client?.optString("name") ?: "unknown"} ${client?.optString("version") ?: ""} 协议 $version")
        return JSONObject().apply {
            put("protocolVersion", version)
            put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            put("serverInfo", JSONObject().put("name", serverName).put("version", serverVersion))
            put("instructions", instructions)
        }
    }

    private fun callTool(id: Any?, params: JSONObject): JSONObject {
        val nameValue = params.opt("name")
        if (nameValue !is String || nameValue.isEmpty()) {
            return error(id, INVALID_PARAMS, "Missing tool name")
        }
        val argsValue = params.opt("arguments")
        if (argsValue != null && argsValue != JSONObject.NULL && argsValue !is JSONObject) {
            return error(id, INVALID_PARAMS, "arguments must be an object")
        }
        val args = argsValue as? JSONObject ?: JSONObject()

        // 唯一的分发入口：白名单 + 注册表 + 用户开关。被移除/禁用的工具与未知工具返回完全相同的错误。
        val tool = registry.resolve(nameValue)
            ?: return error(id, INVALID_PARAMS, "Unknown tool: $nameValue",
                JSONObject().put("errorCode", ErrorCodes.UNKNOWN_TOOL))

        val validation = ParamValidator.validate(args, tool.inputSchema)
        if (validation != null) {
            onToolCall(tool.name, true, 0L)
            return result(id, toolResult(Err.of(ErrorCodes.INVALID_PARAMS, validation), forceError = true))
        }
        val t0 = System.nanoTime()
        return try {
            val value = tool.handler(args)
            val elapsed = System.nanoTime() - t0
            toolNanosAcc.get()?.let { it[0] += elapsed }
            val isError = value is JSONObject && Err.isError(value)
            onToolCall(tool.name, isError, elapsed)
            result(id, toolResult(value, forceError = false))
        } catch (e: Exception) {
            val elapsed = System.nanoTime() - t0
            toolNanosAcc.get()?.let { it[0] += elapsed }
            onToolCall(tool.name, true, elapsed)
            log.warn("工具 ${tool.name} 执行异常: ${e.javaClass.simpleName}")
            result(id, toolResult(Err.of(ErrorCodes.INTERNAL,
                "工具执行失败: ${e.message ?: e.javaClass.simpleName}"), forceError = true))
        }
    }

    /** 工具结果：text 内容 + structuredContent（JSON 对象时）+ isError */
    private fun toolResult(value: Any?, forceError: Boolean): JSONObject {
        val text = when (value) {
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            null -> "null"
            else -> value.toString()
        }
        val out = JSONObject()
        out.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        if (value is JSONObject) out.put("structuredContent", value)
        out.put("isError", forceError || (value is JSONObject && Err.isError(value)))
        return out
    }

    private fun result(id: Any?, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun error(id: Any?, code: Int, message: String, data: JSONObject? = null): JSONObject {
        val e = JSONObject().put("code", code).put("message", message)
        if (data != null) e.put("data", data)
        return JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", e)
    }

    companion object {
        /** JSON 最大嵌套深度（MCP 请求实际不超过十几层） */
        const val MAX_NESTING = 64

        /** 单个批量请求最多包含的消息数 */
        const val MAX_BATCH = 32

        /** 扫描 JSON 文本的最大嵌套深度（跳过字符串内容），超过 [max] 返回 true */
        fun nestingTooDeep(text: String, max: Int): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (c in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> if (++depth > max) return true
                    ']', '}' -> depth--
                }
            }
            return false
        }

        const val LATEST_VERSION = "2025-11-25"
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}
