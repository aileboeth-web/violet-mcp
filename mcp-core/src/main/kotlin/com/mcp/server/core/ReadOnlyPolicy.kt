package com.mcp.server.core

/**
 * 只读白名单。这是服务器能暴露的全部工具名，写死在代码里，不能通过配置扩展。
 * [ToolRegistry] 拒绝注册名单外的工具；tools/list 与 tools/call 都只经过注册表。
 */
object ReadOnlyPolicy {

    val ALLOWED_TOOLS: Set<String> = linkedSetOf(
        // 手机感知（UsageStatsManager / PowerManager）
        "get_foreground_app",
        "get_app_usage_stats",
        "get_screen_state",
        // 设备信息
        "device_info",
        "screen_info",
        "battery",
        "storage_info",
        "locale_info",
        "system_properties",
        "time_now",
        // 应用信息
        "app_info",
        "installed_apps",
        // 服务器自身
        "permission_state",
        "health",
    )

    /**
     * 原项目中会修改设备状态、执行代码、读写文件或向外发请求的工具。
     * 这些工具的实现已从源码中删除；名单只用于测试（确认无法列出、无法调用）和文档。
     */
    val REMOVED_TOOLS: List<String> = listOf(
        // 命令 / 脚本执行
        "shell", "shizuku", "mcp_javascript",
        // 文件修改与删除
        "write_file", "edit", "create_directory", "copy_file", "move_file", "delete_file",
        "touch", "compress_zip", "extract_zip", "web_download", "set_workspace",
        // 文件读取（需要全部文件访问权限，且 continue 无路径校验）
        "get_workspace", "list_files", "read_file", "file_info", "compare_files", "file_search", "continue",
        // 应用控制
        "stop_app",
        // 剪贴板 / 通知
        "clipboard", "send_notification",
        // 任意网络请求
        "http_request", "shorten_url",
        // 绕过分发层的批量调用
        "batch",
        // 其他
        "running_processes", "check_permission", "json_format", "text_convert",
        "base64_encode", "decrypt_xor", "help", "tools_list", "stats",
    )

    fun isAllowed(name: String): Boolean = name in ALLOWED_TOOLS
}
