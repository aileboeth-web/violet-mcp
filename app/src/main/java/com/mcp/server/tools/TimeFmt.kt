package com.mcp.server.tools

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 工具返回的时间统一格式：epoch 毫秒 + 带时区偏移的 ISO-8601 本地时间 */
object TimeFmt {
    fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(ms))

    fun stamp(ms: Long): JSONObject = JSONObject().put("epochMs", ms).put("iso", iso(ms))

    /** 人类可读时长，如 1小时5分 / 3分20秒 */
    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${sec}秒"
            else -> "${sec}秒"
        }
    }
}
