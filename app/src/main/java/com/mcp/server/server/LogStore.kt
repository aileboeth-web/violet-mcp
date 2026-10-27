package com.mcp.server.server

import android.os.Build
import com.mcp.server.BuildConfig
import com.mcp.server.core.CoreLog
import com.mcp.server.core.Redactor
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 一条运行日志 */
data class LogEntry(
    val id: Long,
    val time: Long,
    val level: String, // D / I / W / E
    val message: String,
) {
    val timeText: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
}

/**
 * 全局运行日志（只在内存中保留最近 500 条，不写文件）。
 * 调用方本来就不写入令牌、请求头、请求体和工具结果；写入前再用 [Redactor] 把当前密钥替换掉作为兜底。
 * 日志级别在设置中调整：debug 会额外记录每个请求的分阶段耗时。
 */
object LogStore : CoreLog {
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<LogEntry>()
    private var nextId = 1L
    private val redactor = Redactor {
        try { Settings.secrets(McpAppCtx.app) } catch (_: Exception) { emptyList() }
    }

    /** 0=debug 1=info 2=warn */
    @Volatile
    var level: Int = 1

    fun setLevel(name: String) {
        level = when (name) { "debug" -> 0; "warn" -> 2; else -> 1 }
    }

    fun debug(message: String) { if (level <= 0) add("D", message) }

    override fun info(message: String) { if (level <= 1) add("I", message) }

    override fun warn(message: String) = add("W", message)

    override fun error(message: String) = add("E", message)

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /** 导出文本：只含应用版本、Android 版本和已脱敏的日志，不含设备型号、令牌和使用记录 */
    fun exportText(): String {
        val sb = StringBuilder()
        sb.append("Violet MCP ").append(BuildConfig.VERSION_NAME)
            .append(" · Android SDK ").append(Build.VERSION.SDK_INT).append('\n')
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (e in snapshot()) {
            sb.append(fmt.format(Date(e.time))).append(" [").append(e.level).append("] ")
                .append(redactor.redact(e.message)).append('\n')
        }
        return sb.toString()
    }

    private fun add(level: String, message: String) {
        val safe = redactor.redact(message)
        synchronized(this) {
            entries.addLast(LogEntry(nextId++, System.currentTimeMillis(), level, safe))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }
}
