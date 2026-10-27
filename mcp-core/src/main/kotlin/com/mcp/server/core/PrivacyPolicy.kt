package com.mcp.server.core

/**
 * 第二层（隐私）开关的默认值与敏感等级。
 *
 * 第一层是 [ReadOnlyPolicy]：决定“能有哪些工具”，写死在代码里。
 * 第二层是用户在 App 界面上的逐个开关：决定“现在开放哪些工具”。只有 App 界面能改，MCP 请求无法修改。
 * 关闭的工具既不出现在 tools/list，直接调用也与未知工具一样被拒绝（见 [ToolRegistry.resolve]）。
 */
object PrivacyPolicy {

    /** 首次安装（或从未设置过）时默认开启的工具：只涉及当前状态，不含历史记录和设备标识 */
    val DEFAULT_ENABLED: Set<String> = setOf(
        "battery",
        "get_foreground_app",
        "get_screen_state",
        "screen_info",
        "health",
    )

    /** 可能泄露完整使用记录、已装应用或设备标识的工具：界面上单独标注，需要用户单独开启 */
    val SENSITIVE: Set<String> = setOf(
        "get_app_usage_stats",
        "installed_apps",
        "app_info",
        "system_properties",
        "device_info",
        "permission_state",
    )

    fun defaultEnabled(name: String): Boolean = name in DEFAULT_ENABLED

    fun isSensitive(name: String): Boolean = name in SENSITIVE
}
