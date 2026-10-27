package com.mcp.server.core

/**
 * UsageStatsManager 事件的纯计算逻辑（不依赖 Android，便于单元测试）。
 * Android 层把 UsageEvents.Event 转成 [UsageEventRecord] 后调用这里。
 */
data class UsageEventRecord(
    val timeMs: Long,
    val type: Int,
    val packageName: String? = null,
    val className: String? = null,
)

/** 与 android.app.usage.UsageEvents.Event 中的常量值一致 */
object UsageEventTypes {
    const val ACTIVITY_RESUMED = 1      // 旧名 MOVE_TO_FOREGROUND
    const val ACTIVITY_PAUSED = 2       // 旧名 MOVE_TO_BACKGROUND
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27

    fun name(type: Int): String = when (type) {
        ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
        DEVICE_STARTUP -> "DEVICE_STARTUP"
        else -> "TYPE_$type"
    }
}

object UsageAggregator {

    /** 同一应用内切换 Activity 时，PAUSED 与下一个 RESUMED 之间的间隔小于该值视为同一次使用 */
    const val MERGE_GAP_MS = 2_000L

    data class AppUsage(
        val packageName: String,
        val foregroundMs: Long,
        val sessions: Int,
        val lastStart: Long,
        val lastEnd: Long?,
        /** 统计截止时仍处于前台（事件上看） */
        val openAtEnd: Boolean,
    )

    data class Aggregate(
        val apps: List<AppUsage>,
        val earliestEventMs: Long?,
        val latestEventMs: Long?,
        val eventCount: Int,
    )

    private class Track {
        val active = HashSet<String>()
        var sessionStart: Long? = null
        var total = 0L
        var sessions = 0
        var lastStart = 0L
        var lastEnd: Long? = null
        var seen = false
    }

    /**
     * 按事件配对计算 [rangeStart, rangeEnd) 内每个应用的前台时长。
     * - 区间开始前已在前台的应用：第一条事件是 PAUSED 时，从 rangeStart 起算
     * - 灭屏、关机时结束所有进行中的会话（灭屏期间不算使用）
     * - 截止时仍在前台的会话计到 rangeEnd
     */
    fun aggregate(events: List<UsageEventRecord>, rangeStart: Long, rangeEnd: Long): Aggregate {
        val sorted = events.filter { it.timeMs in rangeStart..rangeEnd }.sortedBy { it.timeMs }
        val tracks = LinkedHashMap<String, Track>()

        fun close(t: Track, at: Long) {
            val s = t.sessionStart ?: return
            t.total += (minOf(at, rangeEnd) - maxOf(s, rangeStart)).coerceAtLeast(0)
            t.lastEnd = at
            t.sessionStart = null
            t.active.clear()
        }

        for (e in sorted) {
            when (e.type) {
                UsageEventTypes.ACTIVITY_RESUMED -> {
                    val pkg = e.packageName ?: continue
                    val t = tracks.getOrPut(pkg) { Track() }
                    t.seen = true
                    if (t.sessionStart == null) {
                        val lastEnd = t.lastEnd
                        if (lastEnd != null && e.timeMs - lastEnd < MERGE_GAP_MS && t.sessions > 0) {
                            // 应用内切换页面：并回上一段会话，把间隔也算进去
                            t.total -= (minOf(lastEnd, rangeEnd) - maxOf(t.lastStart, rangeStart)).coerceAtLeast(0)
                            t.sessionStart = t.lastStart
                        } else {
                            t.sessionStart = e.timeMs
                            t.sessions++
                            t.lastStart = e.timeMs
                        }
                        t.lastEnd = null
                    }
                    t.active.add(e.className ?: "")
                }
                UsageEventTypes.ACTIVITY_PAUSED, UsageEventTypes.ACTIVITY_STOPPED -> {
                    val pkg = e.packageName ?: continue
                    val t = tracks.getOrPut(pkg) { Track() }
                    if (!t.seen && e.type == UsageEventTypes.ACTIVITY_PAUSED) {
                        // 区间开始前就在前台
                        t.seen = true
                        t.sessions++
                        t.lastStart = rangeStart
                        t.total += (e.timeMs - rangeStart).coerceAtLeast(0)
                        t.lastEnd = e.timeMs
                        continue
                    }
                    t.seen = true
                    if (t.sessionStart == null) continue
                    t.active.remove(e.className ?: "")
                    if (t.active.isEmpty()) close(t, e.timeMs)
                }
                UsageEventTypes.SCREEN_NON_INTERACTIVE, UsageEventTypes.DEVICE_SHUTDOWN -> {
                    for (t in tracks.values) close(t, e.timeMs)
                }
            }
        }

        val apps = tracks.map { (pkg, t) ->
            val open = t.sessionStart != null
            if (open) {
                val s = t.sessionStart!!
                t.total += (rangeEnd - maxOf(s, rangeStart)).coerceAtLeast(0)
            }
            AppUsage(pkg, t.total, t.sessions, t.lastStart, if (open) null else t.lastEnd, open)
        }.filter { it.foregroundMs > 0 || it.openAtEnd }
            .sortedByDescending { it.foregroundMs }

        return Aggregate(apps, sorted.firstOrNull()?.timeMs, sorted.lastOrNull()?.timeMs, sorted.size)
    }

    // ==================== 当前/最近前台应用 ====================

    data class ForegroundGuess(
        val packageName: String,
        val className: String?,
        /** 最近一次进入前台的时间 */
        val resumedAt: Long,
        /** 之后离开前台的时间；仍在前台（事件上看）为 null */
        val pausedAt: Long?,
        val lastScreenEvent: UsageEventRecord?,
        val lastKeyguardEvent: UsageEventRecord?,
    ) {
        val stillResumed: Boolean get() = pausedAt == null
    }

    /** 从事件中找出最近一次进入前台的应用，以及它之后是否已经离开前台 */
    fun latestForeground(events: List<UsageEventRecord>): ForegroundGuess? {
        val sorted = events.sortedBy { it.timeMs }
        val active = HashMap<String, HashSet<String>>()
        var lastPkg: String? = null
        var lastCls: String? = null
        var lastResumed = 0L
        var pausedAt: Long? = null
        var screen: UsageEventRecord? = null
        var keyguard: UsageEventRecord? = null

        for (e in sorted) {
            when (e.type) {
                UsageEventTypes.ACTIVITY_RESUMED -> {
                    val pkg = e.packageName ?: continue
                    active.getOrPut(pkg) { HashSet() }.add(e.className ?: "")
                    val p = pausedAt
                    val sameSession = pkg == lastPkg && (p == null || e.timeMs - p < MERGE_GAP_MS)
                    if (!sameSession) {
                        lastPkg = pkg
                        lastCls = e.className
                        lastResumed = e.timeMs
                    } else {
                        lastCls = e.className
                    }
                    pausedAt = null
                }
                UsageEventTypes.ACTIVITY_PAUSED, UsageEventTypes.ACTIVITY_STOPPED -> {
                    val pkg = e.packageName ?: continue
                    val set = active[pkg]
                    set?.remove(e.className ?: "")
                    if (pkg == lastPkg && set.isNullOrEmpty() && pausedAt == null) pausedAt = e.timeMs
                }
                UsageEventTypes.SCREEN_INTERACTIVE, UsageEventTypes.SCREEN_NON_INTERACTIVE -> screen = e
                UsageEventTypes.KEYGUARD_SHOWN, UsageEventTypes.KEYGUARD_HIDDEN -> keyguard = e
                UsageEventTypes.DEVICE_SHUTDOWN -> {
                    active.clear()
                    if (lastPkg != null && pausedAt == null) pausedAt = e.timeMs
                }
            }
        }
        val pkg = lastPkg ?: return null
        return ForegroundGuess(pkg, lastCls, lastResumed, pausedAt, screen, keyguard)
    }

    enum class ForegroundStatus { CURRENT, RECENT, UNKNOWN }

    data class Classification(val status: ForegroundStatus, val reason: String)

    /**
     * 判断“最近前台应用”能否当作“当前前台应用”：
     * 只有亮屏、未锁屏、且事件显示该应用之后没有离开前台时才是 CURRENT，其余一律 RECENT。
     */
    fun classify(guess: ForegroundGuess?, screenInteractive: Boolean, keyguardLocked: Boolean): Classification = when {
        // 实时的熄屏 / 锁屏状态优先于事件推断：即使没有近期事件，也能明确说明原因
        !screenInteractive -> Classification(if (guess == null) ForegroundStatus.UNKNOWN else ForegroundStatus.RECENT, "screen_off")
        keyguardLocked -> Classification(if (guess == null) ForegroundStatus.UNKNOWN else ForegroundStatus.RECENT, "keyguard_locked")
        guess == null -> Classification(ForegroundStatus.UNKNOWN, "no_events")
        !guess.stillResumed -> Classification(ForegroundStatus.RECENT, "left_foreground")
        else -> Classification(ForegroundStatus.CURRENT, "resumed_and_screen_on")
    }
}
