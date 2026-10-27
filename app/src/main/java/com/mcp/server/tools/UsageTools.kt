package com.mcp.server.tools

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserManager
import android.view.Display
import com.mcp.server.core.ErrorCodes
import com.mcp.server.core.Err
import com.mcp.server.core.ToolDefinition
import com.mcp.server.core.UsageAggregator
import com.mcp.server.core.UsageAggregator.ForegroundStatus
import com.mcp.server.core.UsageEventRecord
import com.mcp.server.core.UsageEventTypes
import com.mcp.server.core.emptySchema
import com.mcp.server.core.inCategory
import com.mcp.server.core.param
import com.mcp.server.core.paramSchema
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * 手机感知工具（只读）：基于 Android 官方 UsageStatsManager，需用户在系统设置中授予“使用情况访问权限”。
 * 不使用 Root、无障碍服务或任何会改变设备状态的接口。
 *
 * 所有结果都带 queryTime（查询时刻）和 freshness（数据来源、是否实时、数据截止时间），
 * 由事件推断的结果不会被描述为实时状态。
 */
object UsageTools {

    private const val MAX_EVENTS_RANGE_DAYS = 10L
    private const val MAX_SYSTEM_RANGE_DAYS = 365L

    private val RELEVANT_EVENTS = setOf(
        UsageEventTypes.ACTIVITY_RESUMED, UsageEventTypes.ACTIVITY_PAUSED, UsageEventTypes.ACTIVITY_STOPPED,
        UsageEventTypes.SCREEN_INTERACTIVE, UsageEventTypes.SCREEN_NON_INTERACTIVE,
        UsageEventTypes.KEYGUARD_SHOWN, UsageEventTypes.KEYGUARD_HIDDEN,
        UsageEventTypes.DEVICE_SHUTDOWN, UsageEventTypes.DEVICE_STARTUP,
    )

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(foregroundApp(ctx).inCategory("手机感知"))
        sink.add(appUsageStats(ctx).inCategory("手机感知"))
        sink.add(screenState(ctx).inCategory("手机感知"))
    }

    // ==================== 权限 ====================

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun noAccess(): JSONObject = Err.of(
        ErrorCodes.PERMISSION_DENIED,
        "未授予“使用情况访问权限”，无法读取应用使用数据",
        "请在手机上打开本应用 → 设置 → 权限 → 使用情况访问权限，允许本应用",
    ).put("queryTime", TimeFmt.stamp(System.currentTimeMillis()))

    private fun userLockedNote(ctx: Context): String? {
        val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        return if (!um.isUserUnlocked) "手机重启后尚未解锁过，系统在首次解锁前不提供使用事件" else null
    }

    private fun readEvents(ctx: Context, start: Long, end: Long): List<UsageEventRecord> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, end) ?: return emptyList()
        val out = ArrayList<UsageEventRecord>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType in RELEVANT_EVENTS) {
                out.add(UsageEventRecord(e.timeStamp, e.eventType, e.packageName, e.className))
            }
        }
        return out
    }

    private fun screenSnapshot(ctx: Context): Triple<Boolean, Boolean, Int> {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val state = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
        return Triple(pm.isInteractive, km.isKeyguardLocked, state)
    }

    // ==================== get_foreground_app ====================

    private fun foregroundApp(ctx: Context): ToolDefinition = ToolDefinition(
        "get_foreground_app",
        "获取用户当前正在使用的前台应用。只读。返回 state：" +
            "current（亮屏、已解锁，且该应用进入前台后没有离开事件——此时给出 app 与 package）；" +
            "screen_off（屏幕熄灭）、locked（锁屏界面）、unknown（无法确定，例如应用已离开前台或没有近期事件）、" +
            "permission_denied（手机未授予使用情况访问权限）。非 current 状态不会给出应用，请不要猜测。" +
            "observed_at 是支持该结论的最近一次系统事件时间，age_seconds 是距今秒数；" +
            "current 状态下它是应用进入前台的时间，数值大只表示用户在该应用里停留得久。",
        paramSchema(listOf(
            param("lookbackMinutes", "integer", example = 60, minimum = 1, maximum = 1440,
                description = "向前查找系统事件的时间范围（分钟），默认 60"),
        )),
    ) { args ->
        val now = System.currentTimeMillis()
        val detail = com.mcp.server.server.Settings.foregroundDetail(ctx)
        fun base(state: String, app: String?, pkg: String?, observedAt: Long) = JSONObject().apply {
            put("state", state)
            put("app", app ?: JSONObject.NULL)
            put("package", pkg ?: JSONObject.NULL)
            put("observed_at", TimeFmt.iso(observedAt))
            put("age_seconds", ((now - observedAt) / 1000).coerceAtLeast(0))
        }
        if (!hasUsageAccess(ctx)) {
            return@ToolDefinition base("permission_denied", null, null, now)
                .put("hint", "请在手机上打开本应用 → 设置 → 权限 → 使用情况访问权限，允许本应用")
        }
        val lookbackMin = args.optInt("lookbackMinutes", 60).coerceIn(1, 1440)
        val events = readEvents(ctx, now - lookbackMin * 60_000L, now)
        val guess = UsageAggregator.latestForeground(events)
        val (interactive, keyguardLocked, _) = screenSnapshot(ctx)
        val c = UsageAggregator.classify(guess, interactive, keyguardLocked)

        val out = when (c.reason) {
            "resumed_and_screen_on" -> base("current", AppLabels.label(ctx, guess!!.packageName),
                guess.packageName, guess.resumedAt)
            "screen_off" -> base("screen_off", null, null,
                guess?.lastScreenEvent?.takeIf { it.type == UsageEventTypes.SCREEN_NON_INTERACTIVE }?.timeMs ?: now)
            "keyguard_locked" -> base("locked", null, null,
                guess?.lastKeyguardEvent?.takeIf { it.type == UsageEventTypes.KEYGUARD_SHOWN }?.timeMs ?: now)
            "left_foreground" -> base("unknown", null, null, guess!!.pausedAt ?: now)
            else -> base("unknown", null, null, now)
        }
        if (detail) {
            // 详细模式（用户在“工具与权限”页开启）：附带判定原因和最近一次前台应用
            out.put("reason", c.reason)
            out.put("explanation", when (c.reason) {
                "resumed_and_screen_on" -> "亮屏且未锁屏，系统事件显示该应用进入前台后尚未离开"
                "screen_off" -> "屏幕已熄灭，当前没有用户正在使用的应用"
                "keyguard_locked" -> "手机处于锁屏界面"
                "left_foreground" -> "最近的应用已离开前台，之后可能是通知栏、系统界面等不产生事件的界面"
                else -> "查找范围内没有应用进入前台的事件"
            })
            if (guess != null && c.status != ForegroundStatus.CURRENT) {
                out.put("last_app", AppLabels.label(ctx, guess.packageName) ?: JSONObject.NULL)
                out.put("last_package", guess.packageName)
                out.put("last_seen_at", TimeFmt.iso(guess.pausedAt ?: guess.resumedAt))
            }
            out.put("event_count", events.size)
            userLockedNote(ctx)?.let { out.put("warning", it) }
        } else if (events.isEmpty()) {
            userLockedNote(ctx)?.let { out.put("warning", it) }
        }
        out
    }

    // ==================== get_app_usage_stats ====================

    private val RANGES = listOf("last_15m", "last_1h", "last_3h", "last_24h", "today", "yesterday", "last_7d")

    private fun appUsageStats(ctx: Context): ToolDefinition = ToolDefinition(
        "get_app_usage_stats",
        "读取指定时间范围内各应用的前台使用时长（应用名、包名、时长、次数、最后使用时间）。" +
            "range 用预设范围；或用 start/end 指定（ISO-8601 如 2026-10-09T08:00、日期如 2026-10-09，或 epoch 毫秒）。" +
            "默认 method=events 按系统事件精确计算区间内时长；method=system 用系统按天聚合的统计（区间边界不精确）。" +
            "数据只统计到查询时刻，返回 queryTime 与 freshness。只读。",
        paramSchema(listOf(
            param("range", "string", allowedValues = RANGES, example = "today", description = "预设时间范围，默认 today；指定 start 时忽略"),
            param("start", "string", example = "2026-10-09T08:00", description = "开始时间（本地时区）"),
            param("end", "string", example = "2026-10-09T12:00", description = "结束时间，默认现在"),
            param("limit", "integer", example = 20, minimum = 1, maximum = 100, description = "最多返回多少个应用，默认 20"),
            param("includeHomeScreen", "boolean", example = false, description = "是否把桌面启动器计入列表，默认 false"),
            param("method", "string", allowedValues = listOf("events", "system"), example = "events", description = "计算方式"),
        )),
    ) { args ->
        if (!hasUsageAccess(ctx)) return@ToolDefinition noAccess()
        val now = System.currentTimeMillis()
        val range = resolveRange(args, now) ?: return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE,
            "无法解析 start/end", "使用 ISO-8601（如 2026-10-09T08:00 或 2026-10-09T08:00+08:00）、日期（2026-10-09）或 epoch 毫秒")
        val (start, endRequested, label) = range
        val end = minOf(endRequested, now)
        if (end <= start) return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "结束时间必须晚于开始时间，且开始时间不能晚于现在")
        val method = args.optString("method", "events")
        val spanDays = (end - start) / 86_400_000.0
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val includeHome = args.optBoolean("includeHomeScreen", false)
        val home = AppLabels.homePackages(ctx)

        if (method == "events" && spanDays > MAX_EVENTS_RANGE_DAYS) {
            return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE,
                "method=events 最多支持 $MAX_EVENTS_RANGE_DAYS 天（系统只保留有限天数的详细事件）",
                "缩小时间范围，或改用 method=system")
        }
        if (spanDays > MAX_SYSTEM_RANGE_DAYS) {
            return@ToolDefinition Err.of(ErrorCodes.INVALID_VALUE, "时间范围不能超过 $MAX_SYSTEM_RANGE_DAYS 天")
        }

        val out = JSONObject()
        out.put("range", JSONObject().apply {
            put("preset", label)
            put("start", TimeFmt.stamp(start))
            put("end", TimeFmt.stamp(end))
            if (endRequested > now) put("note", "结束时间晚于现在，已截止到查询时刻")
        })
        out.put("method", method)
        val apps = JSONArray()
        var total = 0L
        var homeMs = 0L
        var matched = 0

        if (method == "system") {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usm.queryAndAggregateUsageStats(start, end)
            var bucketStart = Long.MAX_VALUE
            var bucketEnd = 0L
            val sorted = stats.values.filter { it.totalTimeInForeground > 0 }.sortedByDescending { it.totalTimeInForeground }
            for (s in sorted) {
                bucketStart = minOf(bucketStart, s.firstTimeStamp)
                bucketEnd = maxOf(bucketEnd, s.lastTimeStamp)
                if (s.packageName in home) {
                    homeMs += s.totalTimeInForeground
                    if (!includeHome) continue
                }
                matched++
                total += s.totalTimeInForeground
                if (apps.length() < limit) apps.put(JSONObject().apply {
                    put("packageName", s.packageName)
                    put("appName", AppLabels.label(ctx, s.packageName) ?: JSONObject.NULL)
                    put("foregroundMs", s.totalTimeInForeground)
                    put("foreground", TimeFmt.duration(s.totalTimeInForeground))
                    put("lastUsed", TimeFmt.stamp(s.lastTimeUsed))
                })
            }
            out.put("freshness", JSONObject().apply {
                put("source", "UsageStatsManager.queryAndAggregateUsageStats（系统按天聚合）")
                put("realtime", false)
                put("dataThrough", TimeFmt.stamp(end))
                if (bucketEnd > 0) {
                    put("bucketStart", TimeFmt.stamp(bucketStart))
                    put("bucketEnd", TimeFmt.stamp(bucketEnd))
                }
                put("note", "系统统计以天为单位，时长可能包含区间边界之外同一天的使用；需要精确区间请用 method=events")
                userLockedNote(ctx)?.let { put("warning", it) }
            })
        } else {
            val events = readEvents(ctx, start, end)
            val agg = UsageAggregator.aggregate(events, start, end)
            for (a in agg.apps) {
                if (a.packageName in home) {
                    homeMs += a.foregroundMs
                    if (!includeHome) continue
                }
                matched++
                total += a.foregroundMs
                if (apps.length() < limit) apps.put(JSONObject().apply {
                    put("packageName", a.packageName)
                    put("appName", AppLabels.label(ctx, a.packageName) ?: JSONObject.NULL)
                    put("foregroundMs", a.foregroundMs)
                    put("foreground", TimeFmt.duration(a.foregroundMs))
                    put("sessions", a.sessions)
                    put("lastUsed", TimeFmt.stamp(a.lastEnd ?: end))
                    put("inForegroundAtEnd", a.openAtEnd)
                })
            }
            out.put("freshness", JSONObject().apply {
                put("source", "UsageStatsManager.queryEvents（按进入/离开前台事件配对计算）")
                put("realtime", false)
                put("dataThrough", TimeFmt.stamp(end))
                put("eventCount", agg.eventCount)
                put("earliestEventInRange", agg.earliestEventMs?.let { TimeFmt.stamp(it) } ?: JSONObject.NULL)
                put("latestEventInRange", agg.latestEventMs?.let { TimeFmt.stamp(it) } ?: JSONObject.NULL)
                put("possiblyIncomplete", now - start > 7 * 86_400_000L || agg.eventCount == 0)
                put("note", "统计截止到 dataThrough；仍在前台的应用计到截止时刻（inForegroundAtEnd=true）；灭屏期间不计入。" +
                    "早于系统事件保留期（通常约一周）的数据可能缺失")
                userLockedNote(ctx)?.let { put("warning", it) }
            })
        }
        out.put("apps", apps)
        out.put("appCount", matched)
        out.put("returned", apps.length())
        out.put("totalForegroundMs", total)
        out.put("totalForeground", TimeFmt.duration(total))
        if (!includeHome) out.put("homeScreenMs", homeMs)
        out.put("queryTime", TimeFmt.stamp(now))
        out
    }

    /** 返回 (start, end, 范围标签)；解析失败返回 null */
    private fun resolveRange(args: JSONObject, now: Long): Triple<Long, Long, String>? {
        val zone = ZoneId.systemDefault()
        val startArg = args.optString("start", "").trim()
        if (startArg.isNotEmpty()) {
            val s = parseTime(startArg, zone) ?: return null
            val endArg = args.optString("end", "").trim()
            val e = if (endArg.isEmpty()) now else parseTime(endArg, zone) ?: return null
            return Triple(s, e, "custom")
        }
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return when (val r = args.optString("range", "today")) {
            "last_15m" -> Triple(now - 15 * 60_000L, now, r)
            "last_1h" -> Triple(now - 3_600_000L, now, r)
            "last_3h" -> Triple(now - 3 * 3_600_000L, now, r)
            "last_24h" -> Triple(now - 86_400_000L, now, r)
            "yesterday" -> Triple(LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), todayStart, r)
            "last_7d" -> Triple(now - 7 * 86_400_000L, now, r)
            else -> Triple(todayStart, now, "today")
        }
    }

    private fun parseTime(s: String, zone: ZoneId): Long? {
        s.toLongOrNull()?.let { return if (it in 1_000_000_000_000L..9_999_999_999_999L) it else null }
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                ZonedDateTime.parse(s).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli()
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
            }
        }
    }

    // ==================== get_screen_state ====================

    private fun displayStateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "UNKNOWN"
    }

    private fun screenState(ctx: Context): ToolDefinition = ToolDefinition(
        "get_screen_state",
        "读取屏幕亮灭与锁屏状态（实时）：screenOn、displayState（ON/OFF/DOZE 等）、keyguardLocked。" +
            "如已授予使用情况访问权限，还会给出当前亮/灭屏状态从何时开始。只读。",
        emptySchema(),
    ) {
        val now = System.currentTimeMillis()
        val (interactive, keyguardLocked, displayState) = screenSnapshot(ctx)
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val out = JSONObject()
        out.put("screenOn", interactive)
        out.put("displayState", displayStateName(displayState))
        out.put("keyguardLocked", keyguardLocked)
        out.put("deviceLocked", km.isDeviceLocked)
        out.put("deviceSecure", km.isDeviceSecure)
        out.put("summary", when {
            !interactive -> "屏幕熄灭"
            keyguardLocked -> "屏幕亮着，处于锁屏界面"
            else -> "屏幕亮着，已解锁"
        })

        var sinceSource = "unavailable"
        var since: JSONObject? = null
        if (hasUsageAccess(ctx)) {
            val events = readEvents(ctx, now - 86_400_000L, now)
            val lastScreen = events.lastOrNull {
                it.type == UsageEventTypes.SCREEN_INTERACTIVE || it.type == UsageEventTypes.SCREEN_NON_INTERACTIVE
            }
            // 只有最近一次亮/灭屏事件与当前实时状态一致时，才把它当作“状态开始时间”
            if (lastScreen != null && (lastScreen.type == UsageEventTypes.SCREEN_INTERACTIVE) == interactive) {
                since = TimeFmt.stamp(lastScreen.timeMs)
                out.put("stateDurationSeconds", (now - lastScreen.timeMs) / 1000)
                sinceSource = "UsageStatsManager ${UsageEventTypes.name(lastScreen.type)}"
            } else {
                sinceSource = "no_matching_event"
            }
        } else {
            sinceSource = "usage_access_not_granted"
        }
        out.put("stateSince", since ?: JSONObject.NULL)
        out.put("queryTime", TimeFmt.stamp(now))
        out.put("freshness", JSONObject().apply {
            put("source", "PowerManager.isInteractive / Display.getState / KeyguardManager")
            put("realtime", true)
            put("stateSinceSource", sinceSource)
        })
        out
    }
}
