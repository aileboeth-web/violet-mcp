package com.mcp.server.core

import com.mcp.server.core.UsageAggregator.ForegroundStatus
import com.mcp.server.core.UsageEventTypes.ACTIVITY_PAUSED
import com.mcp.server.core.UsageEventTypes.ACTIVITY_RESUMED
import com.mcp.server.core.UsageEventTypes.ACTIVITY_STOPPED
import com.mcp.server.core.UsageEventTypes.DEVICE_SHUTDOWN
import com.mcp.server.core.UsageEventTypes.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAggregatorTest {
    private fun ev(t: Long, type: Int, pkg: String? = null, cls: String? = null) =
        UsageEventRecord(t * 1000, type, pkg, cls ?: pkg?.let { "$it.Main" })

    private fun usage(events: List<UsageEventRecord>, start: Long, end: Long) =
        UsageAggregator.aggregate(events, start * 1000, end * 1000).apps.associateBy { it.packageName }

    @Test
    fun simpleSessions() {
        val apps = usage(listOf(
            ev(10, ACTIVITY_RESUMED, "a"), ev(70, ACTIVITY_PAUSED, "a"), ev(71, ACTIVITY_STOPPED, "a"),
            ev(70, ACTIVITY_RESUMED, "b"), ev(100, ACTIVITY_PAUSED, "b"),
            ev(200, ACTIVITY_RESUMED, "a"), ev(230, ACTIVITY_PAUSED, "a"),
        ), 0, 1000)
        assertEquals(90_000, apps["a"]!!.foregroundMs)
        assertEquals(2, apps["a"]!!.sessions)
        assertEquals(30_000, apps["b"]!!.foregroundMs)
        assertFalse(apps["a"]!!.openAtEnd)
    }

    @Test
    fun inAppNavigationMergedIntoOneSession() {
        val apps = usage(listOf(
            ev(10, ACTIVITY_RESUMED, "a", "A1"),
            ev(20, ACTIVITY_PAUSED, "a", "A1"),
            UsageEventRecord(20_300, ACTIVITY_RESUMED, "a", "A2"),
            ev(21, ACTIVITY_STOPPED, "a", "A1"),
            ev(40, ACTIVITY_PAUSED, "a", "A2"),
        ), 0, 100)
        assertEquals(30_000, apps["a"]!!.foregroundMs)
        assertEquals(1, apps["a"]!!.sessions)
    }

    @Test
    fun sessionStartedBeforeRangeIsClipped() {
        val apps = usage(listOf(ev(130, ACTIVITY_PAUSED, "a")), 100, 200)
        assertEquals(30_000, apps["a"]!!.foregroundMs)
    }

    @Test
    fun openSessionCountedUntilRangeEnd() {
        val apps = usage(listOf(ev(150, ACTIVITY_RESUMED, "a")), 100, 200)
        assertEquals(50_000, apps["a"]!!.foregroundMs)
        assertTrue(apps["a"]!!.openAtEnd)
    }

    @Test
    fun screenOffAndShutdownEndSessions() {
        val apps = usage(listOf(
            ev(10, ACTIVITY_RESUMED, "a"), ev(40, SCREEN_NON_INTERACTIVE),
            ev(500, ACTIVITY_PAUSED, "a"),
            ev(600, ACTIVITY_RESUMED, "b"), ev(650, DEVICE_SHUTDOWN),
        ), 0, 1000)
        assertEquals(30_000, apps["a"]!!.foregroundMs)
        assertEquals(50_000, apps["b"]!!.foregroundMs)
    }

    @Test
    fun eventsOutsideRangeIgnored() {
        val agg = UsageAggregator.aggregate(listOf(ev(5, ACTIVITY_RESUMED, "a"), ev(500, ACTIVITY_PAUSED, "a")), 100_000, 200_000)
        assertEquals(0, agg.eventCount)
        assertTrue(agg.apps.isEmpty())
        assertNull(agg.earliestEventMs)
    }

    @Test
    fun latestForegroundStillResumed() {
        val g = UsageAggregator.latestForeground(listOf(
            ev(10, ACTIVITY_RESUMED, "a"), ev(20, ACTIVITY_PAUSED, "a"), ev(20, ACTIVITY_RESUMED, "b"),
        ))!!
        assertEquals("b", g.packageName)
        assertTrue(g.stillResumed)
        assertEquals(20_000, g.resumedAt)
        val c = UsageAggregator.classify(g, screenInteractive = true, keyguardLocked = false)
        assertEquals(ForegroundStatus.CURRENT, c.status)
    }

    @Test
    fun latestForegroundKeepsSessionStartAcrossInAppNavigation() {
        val g = UsageAggregator.latestForeground(listOf(
            ev(10, ACTIVITY_RESUMED, "a", "A1"),
            ev(50, ACTIVITY_PAUSED, "a", "A1"),
            UsageEventRecord(50_200, ACTIVITY_RESUMED, "a", "A2"),
        ))!!
        assertEquals(10_000, g.resumedAt)
        assertTrue(g.stillResumed)
    }

    @Test
    fun staleForegroundIsNeverCurrent() {
        val left = UsageAggregator.latestForeground(listOf(ev(10, ACTIVITY_RESUMED, "a"), ev(20, ACTIVITY_PAUSED, "a")))!!
        assertFalse(left.stillResumed)
        assertEquals(20_000L, left.pausedAt)
        assertEquals(ForegroundStatus.RECENT, UsageAggregator.classify(left, true, false).status)

        val resumed = UsageAggregator.latestForeground(listOf(ev(10, ACTIVITY_RESUMED, "a")))!!
        assertEquals("screen_off", UsageAggregator.classify(resumed, false, false).reason)
        assertEquals("keyguard_locked", UsageAggregator.classify(resumed, true, true).reason)
        assertEquals(ForegroundStatus.UNKNOWN, UsageAggregator.classify(null, true, false).status)
        // 没有事件时，熄屏 / 锁屏仍然给出明确原因
        assertEquals("screen_off", UsageAggregator.classify(null, false, false).reason)
        assertEquals("keyguard_locked", UsageAggregator.classify(null, true, true).reason)
        assertEquals("no_events", UsageAggregator.classify(null, true, false).reason)
    }
}
