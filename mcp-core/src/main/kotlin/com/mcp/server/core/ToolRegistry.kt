package com.mcp.server.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册表：tools/list 和 tools/call 的唯一来源。
 * - 注册时强制检查 [ReadOnlyPolicy]，名单外的工具直接抛异常（启动即失败，不会悄悄暴露）。
 * - 查找时再次检查白名单与用户开关（[isEnabled]，只能在白名单内关掉工具，不能打开名单外的工具）。
 */
class ToolRegistry(private val isEnabled: (String) -> Boolean = { true }) {

    @Volatile
    private var tools: Map<String, ToolDefinition> = emptyMap()

    @Synchronized
    fun register(def: ToolDefinition) {
        require(ReadOnlyPolicy.isAllowed(def.name)) { "工具 ${def.name} 不在只读白名单内，拒绝注册" }
        require(def.name !in tools) { "工具 ${def.name} 重复注册" }
        tools = LinkedHashMap(tools).apply { put(def.name, def) }
    }

    /** 已注册的全部工具（含被用户关闭的，供界面展示开关） */
    fun all(): List<ToolDefinition> = tools.values.toList()

    /** 客户端可见的工具 */
    fun enabled(): List<ToolDefinition> = tools.values.filter { visible(it.name) }

    /** tools/call 的唯一查找入口：不在白名单、未注册或被关闭都返回 null */
    fun resolve(name: String): ToolDefinition? {
        if (!visible(name)) return null
        return tools[name]
    }

    private fun visible(name: String): Boolean = ReadOnlyPolicy.isAllowed(name) && isEnabled(name)

    /** tools/list 结果 */
    fun toListResult(): JSONObject {
        val arr = JSONArray()
        for (t in enabled()) {
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.inputSchema)
                put("annotations", JSONObject().apply {
                    put("readOnlyHint", true)
                    put("destructiveHint", false)
                    put("idempotentHint", true)
                    put("openWorldHint", false)
                })
            })
        }
        return JSONObject().put("tools", arr)
    }
}
