package com.mcp.server.tools

import android.content.Context
import com.mcp.server.core.PrivacyPolicy
import com.mcp.server.core.ToolDefinition
import com.mcp.server.core.ToolRegistry

/**
 * App 侧的工具集合：把只读工具注册进 [ToolRegistry]，并保存每个工具的开关（第二层权限）。
 * - 注册表拒绝任何不在只读白名单里的工具名；开关只能关掉白名单内的工具。
 * - 默认只开启 [PrivacyPolicy.DEFAULT_ENABLED]；其余（尤其 [PrivacyPolicy.SENSITIVE]）需用户在“工具与权限”页单独开启。
 * - 开关只能在 App 界面修改，没有任何 MCP 方法或中继帧可以改动它。
 */
object ToolKit {

    private const val PREFS = "tool_enabled"

    private lateinit var ctx: Context

    @Volatile
    lateinit var registry: ToolRegistry
        private set

    /** 工具需要的 Android 权限（界面展示用） */
    enum class Requirement(val label: String) {
        NONE(""),
        USAGE_ACCESS("需要“使用情况访问权限”"),
        USAGE_ACCESS_OPTIONAL("“使用情况访问权限”可选（用于显示状态开始时间）"),
    }

    fun requirement(name: String): Requirement = when (name) {
        "get_foreground_app", "get_app_usage_stats" -> Requirement.USAGE_ACCESS
        "get_screen_state" -> Requirement.USAGE_ACCESS_OPTIONAL
        else -> Requirement.NONE
    }

    @Synchronized
    fun init(context: Context) {
        if (::registry.isInitialized) return
        build(context)
    }

    /** 仅测试用：每个 Robolectric 测试都有新的 Application，需要重新构建 */
    @Synchronized
    internal fun initForTest(context: Context) = build(context)

    private fun build(context: Context) {
        ctx = context.applicationContext
        val r = ToolRegistry { isEnabled(it) }
        val list = mutableListOf<ToolDefinition>()
        UsageTools.register(ctx, list)
        SystemTools.register(ctx, list)
        AppTools.register(ctx, list)
        MetaTools.register(ctx, list)
        list.forEach { r.register(it) }
        registry = r
    }

    fun isEnabled(name: String): Boolean = prefs().getBoolean("tool_$name", PrivacyPolicy.defaultEnabled(name))

    fun setEnabled(name: String, enabled: Boolean) {
        prefs().edit().putBoolean("tool_$name", enabled).apply()
    }

    fun isSensitive(name: String): Boolean = PrivacyPolicy.isSensitive(name)

    fun enabledCount(): Int = registry.enabled().size

    fun totalCount(): Int = registry.all().size

    /** 恢复默认开关 */
    fun resetToDefaults() {
        prefs().edit().clear().apply()
    }

    /** 固定分类顺序 */
    private val categoryOrder = listOf("手机感知", "设备信息", "应用信息", "服务器")

    /** 分类 -> 工具列表（全部工具，含已关闭的） */
    fun groupedTools(): List<Pair<String, List<ToolDefinition>>> {
        val byCat = registry.all().groupBy { it.category }
        return categoryOrder.mapNotNull { cat -> byCat[cat]?.let { cat to it } } +
            byCat.filterKeys { it !in categoryOrder }.toList()
    }

    private fun prefs(): android.content.SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

}
