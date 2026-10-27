package com.mcp.server.core.harness

import com.mcp.server.core.CoreLog
import com.mcp.server.core.LocalHttpServer
import com.mcp.server.core.McpEndpoint
import com.mcp.server.core.McpProtocol
import com.mcp.server.core.RelayClient
import com.mcp.server.core.RelayConfig
import com.mcp.server.core.ToolDefinition
import com.mcp.server.core.ToolRegistry
import com.mcp.server.core.emptySchema
import com.mcp.server.core.param
import com.mcp.server.core.paramSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 电脑端互操作 harness：用与 App 完全相同的协议、鉴权、HTTP 和中继代码，
 * 挂上模拟数据的只读工具，供 tests/interop 里的 Python MCP SDK 测试连接。
 *
 *   --port 18080 --token <token> [--relay wss://127.0.0.1:18443/device --device-secret <s> --pin sha256/...]
 *   [--slow-ms 30000]   locale_info 工具故意延迟，用于测试中继超时
 *
 * storage_info 已注册但处于“关闭”状态（模拟用户在 App 里关掉的工具），用于测试直接调用会被拒绝。
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val token = opts["token"] ?: error("--token required")
    val port = opts["port"]?.toInt() ?: 18080
    val slowMs = opts["slow-ms"]?.toLong() ?: 0L

    val log = object : CoreLog {
        override fun info(message: String) = println("[I] $message")
        override fun warn(message: String) = println("[W] $message")
        override fun error(message: String) = println("[E] $message")
    }

    val disabled = setOf("storage_info")
    val registry = ToolRegistry { it !in disabled }
    registry.register(ToolDefinition("time_now", "当前时间（harness 模拟）", emptySchema()) {
        JSONObject().put("epochMillis", System.currentTimeMillis())
    })
    registry.register(ToolDefinition("battery", "电量（harness 模拟）", emptySchema()) {
        JSONObject().put("percent", 87).put("charging", false)
    })
    registry.register(ToolDefinition("app_info", "应用信息（harness 模拟）",
        paramSchema(listOf(param("packageName", "string", example = "com.android.settings")), listOf("packageName"))) { a ->
        JSONObject().put("packageName", a.getString("packageName")).put("appName", "设置")
    })
    registry.register(ToolDefinition("get_screen_state", "屏幕状态（harness 模拟）", emptySchema()) {
        JSONObject().put("screenOn", true).put("queryTime", System.currentTimeMillis())
            .put("freshness", JSONObject().put("realtime", true))
    })
    registry.register(ToolDefinition("get_app_usage_stats", "使用时长（harness 模拟）",
        paramSchema(listOf(param("range", "string", allowedValues = listOf("today", "last_1h"))))) { a ->
        JSONObject().put("range", a.optString("range", "today")).put("apps", JSONArray()
            .put(JSONObject().put("packageName", "com.example.chat").put("appName", "聊天").put("foregroundMs", 600000)))
    })
    registry.register(ToolDefinition("storage_info", "存储（harness：已关闭）", emptySchema()) {
        error("关闭的工具不应被执行")
    })
    if (slowMs > 0) {
        registry.register(ToolDefinition("locale_info", "故意变慢的工具（harness）", emptySchema()) {
            Thread.sleep(slowMs)
            JSONObject().put("slept", slowMs)
        })
    }

    val protocol = McpProtocol(registry, "violet-android-readonly-mcp-harness", "0.0.0-harness",
        "harness：模拟数据，只读", log) { name, isError, nanos ->
        log.info("工具调用 $name ${"%.1f".format(nanos / 1e6)}ms${if (isError) "（错误）" else ""}")
    }
    val endpoint = McpEndpoint(protocol, { token }, { JSONObject().put("harness", true) }, log)
    val server = LocalHttpServer(endpoint, "127.0.0.1", port, log)
    check(server.start()) { "无法监听端口 $port" }

    val relayUrl = opts["relay"]
    val relay = if (!relayUrl.isNullOrEmpty()) {
        val cfg = RelayConfig(relayUrl, opts["device-secret"] ?: "", opts["pin"], "jvm-harness", "0.0.0",
            maxRetrySec = (opts["max-retry"] ?: "60").toInt())
        RelayClient.validate(cfg)?.let { error(it) }
        RelayClient(cfg, endpoint, { token }, log, onState = { s -> println("[RELAY] ${s.phase} ${s.message}") })
            .also { it.start() }
    } else null

    println("HARNESS READY port=${server.boundPort}")
    Runtime.getRuntime().addShutdownHook(Thread {
        relay?.stop()
        server.stop()
    })
    Thread.currentThread().join()
}
