package com.mcp.server.tools

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.mcp.server.BuildConfig
import com.mcp.server.core.ToolDefinition
import com.mcp.server.core.emptySchema
import com.mcp.server.core.inCategory
import com.mcp.server.server.Diagnostics
import com.mcp.server.server.McpServerService
import com.mcp.server.server.ServiceStatus
import com.mcp.server.server.Settings
import org.json.JSONObject

/** 服务器自身状态（不含任何密钥） */
object MetaTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(health(ctx).inCategory("服务器"))
        sink.add(permissionState(ctx).inCategory("服务器"))
    }

    private fun health(ctx: Context): ToolDefinition = ToolDefinition(
        "health", "服务器健康状态：服务状态（运行 / 受限 / 停止）、运行时长、中继连接状态、最近一次成功连接时间、已开启工具数。不含任何密钥。只读。",
        emptySchema(),
    ) {
        val relay = McpServerService.relayState
        val status = ServiceStatus.evaluate(ctx)
        JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME)
            put("service", status.state.name.lowercase())
            put("uptimeSeconds", McpServerService.uptimeSeconds())
            put("relay", JSONObject()
                .put("enabled", Settings.relayEnabled(ctx))
                .put("phase", relay?.phase?.name?.lowercase() ?: "stopped")
                .put("connectedSince", relay?.connectedSince?.takeIf { it > 0 }?.let { TimeFmt.iso(it) } ?: JSONObject.NULL)
                .put("lastSuccessAt", Diagnostics.lastRelaySuccessAt(ctx).takeIf { it > 0 }?.let { TimeFmt.iso(it) } ?: JSONObject.NULL)
                .put("reconnects", relay?.reconnects ?: 0))
            put("enabledTools", ToolKit.enabledCount())
            put("queryTime", TimeFmt.iso(System.currentTimeMillis()))
        }
    }

    private fun permissionState(ctx: Context): ToolDefinition = ToolDefinition(
        "permission_state", "检查本服务器依赖的权限是否已授予（使用情况访问、通知、电池优化豁免），用于排查工具不可用的原因。只读。",
        emptySchema(),
    ) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        JSONObject().apply {
            put("usageAccess", UsageTools.hasUsageAccess(ctx))
            put("notifications", if (Build.VERSION.SDK_INT >= 33)
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED else true)
            put("ignoringBatteryOptimizations", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            put("wakeLockEnabled", Settings.wakeLockEnabled(ctx))
            put("autoStart", Settings.autoStart(ctx))
            put("note", "usageAccess 为 false 时 get_foreground_app / get_app_usage_stats 不可用；需在手机设置中手动授权")
        }
    }
}
