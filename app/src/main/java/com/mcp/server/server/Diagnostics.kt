package com.mcp.server.server

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import com.mcp.server.core.McpEndpoint
import com.mcp.server.core.RelayClient

/**
 * 诊断信息：最近一次连接、失败原因、请求耗时、工具调用。只记录时间、状态码、耗时和工具名，
 * 不记录参数、返回内容和凭据。连接相关的事件持久化，进程重启后仍能看到。
 */
object Diagnostics {
    private const val PREFS = "diagnostics"

    data class ToolCall(val name: String, val at: Long, val ms: Double, val ok: Boolean)
    data class Request(val path: String, val status: Int, val at: Long, val handleMs: Double, val toolMs: Double)

    @Volatile
    var lastToolCall: ToolCall? = null
        private set

    @Volatile
    var lastRequest: Request? = null
        private set

    fun recordToolCall(name: String, ms: Double, ok: Boolean) {
        lastToolCall = ToolCall(name, System.currentTimeMillis(), ms, ok)
    }

    fun recordRequest(r: McpEndpoint.RequestRecord) {
        lastRequest = Request(r.path, r.status, System.currentTimeMillis(), r.handleMs, r.toolMs)
    }

    /** 中继状态变化时记录最近一次成功/失败（持久化） */
    fun recordRelay(context: Context, s: RelayClient.RelayState) {
        val e = prefs(context).edit()
        if (s.phase == RelayClient.Phase.CONNECTED && s.lastConnectedAt > 0) e.putLong("relay_ok_at", s.lastConnectedAt)
        if (s.lastFailureAt > 0) {
            e.putLong("relay_fail_at", s.lastFailureAt).putString("relay_fail_reason", s.lastFailureReason)
        }
        e.apply()
    }

    fun lastRelaySuccessAt(context: Context): Long = prefs(context).getLong("relay_ok_at", 0L)
    fun lastRelayFailureAt(context: Context): Long = prefs(context).getLong("relay_fail_at", 0L)
    fun lastRelayFailureReason(context: Context): String = prefs(context).getString("relay_fail_reason", "") ?: ""

    /** 前台服务启动失败（例如 Android 12+ 系统不允许从后台启动） */
    fun recordStartFailure(context: Context, reason: String) {
        prefs(context).edit().putLong("start_fail_at", System.currentTimeMillis())
            .putString("start_fail_reason", reason).apply()
    }

    fun lastStartFailureAt(context: Context): Long = prefs(context).getLong("start_fail_at", 0L)
    fun lastStartFailureReason(context: Context): String = prefs(context).getString("start_fail_reason", "") ?: ""

    fun clearStartFailure(context: Context) {
        prefs(context).edit().remove("start_fail_at").remove("start_fail_reason").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 服务的三种状态。只有服务进程确实在运行且没有检测到系统限制时才是 RUNNING；
 * 用户期望运行但服务不在运行（被系统杀掉、启动被拒、被任务管理器停止）时是 RESTRICTED，
 * 不会显示成“连接正常”。
 */
object ServiceStatus {
    enum class State { RUNNING, RESTRICTED, STOPPED }

    data class Info(val state: State, val title: String, val problems: List<String>, val hints: List<String>)

    fun evaluate(context: Context): Info {
        val running = McpServerService.running
        val desired = Settings.desiredRunning(context)
        val problems = mutableListOf<String>()
        val hints = mutableListOf<String>()

        val am = context.getSystemService(ActivityManager::class.java)
        if (Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true) {
            problems.add("系统已对本应用启用“后台限制”，服务可能被暂停或无法联网")
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            if (usm?.appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) {
                problems.add("系统把本应用放入了“受限”待机分组")
            }
        }
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(context.packageName) == false) {
            hints.add("未设置“电池无限制”：熄屏较久后连接可能被系统延迟或中断")
        }
        if (pm?.isPowerSaveMode == true) hints.add("省电模式已开启")
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            hints.add("流量节省模式已开启（前台服务通常不受影响）")
        }
        val failAt = Diagnostics.lastStartFailureAt(context)
        if (failAt > 0 && !running) {
            problems.add("最近一次启动被系统拒绝：${Diagnostics.lastStartFailureReason(context)}")
        }

        return when {
            running && problems.isEmpty() -> Info(State.RUNNING, "服务运行中", problems, hints)
            running -> Info(State.RESTRICTED, "服务在运行，但受到系统限制", problems, hints)
            desired -> Info(State.RESTRICTED, "服务应在运行，但目前已被系统停止或未能启动",
                problems + "请打开应用点“启动”；如反复出现，请允许本应用自启动和后台运行", hints)
            else -> Info(State.STOPPED, "服务已停止", problems, hints)
        }
    }
}
