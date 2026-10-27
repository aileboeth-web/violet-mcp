package com.mcp.server.core

import org.json.JSONArray
import org.json.JSONObject

/** 测试与 harness 共用：用白名单内的名字注册模拟只读工具 */
object TestTools {
    const val TOKEN = "test-token-0123456789abcdef"

    fun registry(isEnabled: (String) -> Boolean = { true }): ToolRegistry {
        val r = ToolRegistry(isEnabled)
        r.register(ToolDefinition("time_now", "当前时间", emptySchema()) {
            JSONObject().put("epochMillis", System.currentTimeMillis())
        })
        r.register(ToolDefinition("battery", "电量", emptySchema()) {
            JSONObject().put("percent", 87).put("charging", false)
        })
        r.register(ToolDefinition("app_info", "应用信息",
            paramSchema(listOf(param("packageName", "string", example = "com.example", description = "包名")),
                required = listOf("packageName"))) { args ->
            val pkg = args.getString("packageName")
            if (pkg == "missing") Err.of(ErrorCodes.NOT_FOUND, "未找到应用 $pkg")
            else JSONObject().put("packageName", pkg).put("appName", "示例应用")
        })
        r.register(ToolDefinition("get_app_usage_stats", "使用时长",
            paramSchema(listOf(param("range", "string", allowedValues = listOf("today", "last_24h"))))) { args ->
            JSONObject().put("range", args.optString("range", "today")).put("apps", JSONArray())
        })
        r.register(ToolDefinition("health", "健康", emptySchema()) { throw IllegalStateException("boom") })
        return r
    }

    fun protocol(registry: ToolRegistry = registry()) =
        McpProtocol(registry, "test-server", "0.0.0", "test instructions")

    fun endpoint(registry: ToolRegistry = registry(), token: String = TOKEN) =
        McpEndpoint(protocol(registry), { token }, { JSONObject().put("running", true) })
}
