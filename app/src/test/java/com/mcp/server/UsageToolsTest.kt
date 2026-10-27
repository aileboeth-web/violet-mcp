package com.mcp.server

import com.mcp.server.TestSupport.PAUSED
import com.mcp.server.TestSupport.RESUMED
import com.mcp.server.TestSupport.SCREEN_OFF
import com.mcp.server.server.Settings
import com.mcp.server.tools.ToolKit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 【模拟环境】前台应用 / 使用时长 / 屏幕状态工具在 Robolectric 下的行为。
 * 系统事件、屏幕和锁屏状态由测试注入，不代表真机上 UsageStatsManager 的实际时序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageToolsTest {
    private val app get() = TestSupport.app()
    private val minimalKeys = setOf("state", "app", "package", "observed_at", "age_seconds")

    @Before
    fun setUp() {
        TestSupport.fresh()
        TestSupport.installApp(app, "com.example.chat", "示例聊天")
        TestSupport.installApp(app, "com.example.map", "示例地图")
        TestSupport.setUsageAccess(app, true)
        TestSupport.setScreen(app, interactive = true, locked = false)
    }

    private fun foreground(): JSONObject =
        TestSupport.call("get_foreground_app").getJSONObject("result").getJSONObject("structuredContent")

    @Test
    fun currentAppWithMinimalFields() {
        val now = System.currentTimeMillis()
        TestSupport.addEvent(app, "com.example.chat", RESUMED, now - 2_000)
        val r = foreground()
        assertEquals("current", r.getString("state"))
        assertEquals("示例聊天", r.getString("app"))
        assertEquals("com.example.chat", r.getString("package"))
        assertTrue(r.getInt("age_seconds") in 1..5)
        assertEquals("默认只返回必要字段", minimalKeys, r.keys().asSequence().toSet())
    }

    @Test
    fun appSwitchUpdatesResult() {
        val now = System.currentTimeMillis()
        TestSupport.addEvent(app, "com.example.chat", RESUMED, now - 60_000)
        TestSupport.addEvent(app, "com.example.chat", PAUSED, now - 5_000)
        TestSupport.addEvent(app, "com.example.map", RESUMED, now - 4_900)
        val r = foreground()
        assertEquals("current", r.getString("state"))
        assertEquals("com.example.map", r.getString("package"))
    }

    @Test
    fun screenOffAndLockedNeverNameAnApp() {
        val now = System.currentTimeMillis()
        TestSupport.addEvent(app, "com.example.chat", RESUMED, now - 60_000)
        TestSupport.addEvent(app, null, SCREEN_OFF, now - 30_000, cls = null)

        TestSupport.setScreen(app, interactive = false, locked = true)
        var r = foreground()
        assertEquals("screen_off", r.getString("state"))
        assertTrue(r.isNull("app"))
        assertTrue(r.isNull("package"))

        TestSupport.setScreen(app, interactive = true, locked = true)
        r = foreground()
        assertEquals("locked", r.getString("state"))
        assertTrue(r.isNull("app"))
    }

    @Test
    fun leftForegroundIsUnknownNotGuessed() {
        val now = System.currentTimeMillis()
        TestSupport.addEvent(app, "com.example.chat", RESUMED, now - 60_000)
        TestSupport.addEvent(app, "com.example.chat", PAUSED, now - 10_000)
        val r = foreground()
        assertEquals("unknown", r.getString("state"))
        assertTrue(r.isNull("app"))
    }

    @Test
    fun detailModeAddsLastAppOnlyWhenEnabledOnPhone() {
        val now = System.currentTimeMillis()
        TestSupport.addEvent(app, "com.example.chat", RESUMED, now - 60_000)
        TestSupport.setScreen(app, interactive = false, locked = true)
        assertFalse(foreground().has("last_app"))
        Settings.setForegroundDetail(app, true)
        val r = foreground()
        assertEquals("screen_off", r.getString("state"))
        assertEquals("示例聊天", r.getString("last_app"))
        assertTrue(r.isNull("app"))
    }

    @Test
    fun revokedUsageAccessIsReportedNotGuessed() {
        val now = System.currentTimeMillis()
        TestSupport.addEvent(app, "com.example.chat", RESUMED, now - 2_000)
        assertEquals("current", foreground().getString("state"))
        TestSupport.setUsageAccess(app, false) // 用户在系统设置中撤销
        val r = foreground()
        assertEquals("permission_denied", r.getString("state"))
        assertTrue(r.isNull("app"))
        assertTrue(r.has("hint"))
    }

    @Test
    fun usageStatsComputesDurationsWithNames() {
        ToolKit.setEnabled("get_app_usage_stats", true)
        val now = System.currentTimeMillis()
        TestSupport.addEvent(app, "com.example.chat", RESUMED, now - 600_000)
        TestSupport.addEvent(app, "com.example.chat", PAUSED, now - 300_000)
        TestSupport.addEvent(app, "com.example.map", RESUMED, now - 120_000)
        val r = TestSupport.call("get_app_usage_stats", JSONObject().put("range", "last_1h"))
            .getJSONObject("result").getJSONObject("structuredContent")
        val apps = r.getJSONArray("apps")
        assertEquals(2, apps.length())
        val chat = (0 until apps.length()).map { apps.getJSONObject(it) }.first { it.getString("packageName") == "com.example.chat" }
        assertEquals("示例聊天", chat.getString("appName"))
        assertTrue(chat.getLong("foregroundMs") in 299_000L..301_000L)
        assertTrue(r.has("queryTime") && r.has("freshness"))
    }

    @Test
    fun usageStatsPermissionErrorIsExplicit() {
        ToolKit.setEnabled("get_app_usage_stats", true)
        TestSupport.setUsageAccess(app, false)
        val r = TestSupport.call("get_app_usage_stats").getJSONObject("result")
        assertTrue(r.getBoolean("isError"))
        assertEquals("PERMISSION_DENIED", r.getJSONObject("structuredContent").getString("errorCode"))
    }

    @Test
    fun screenStateIsRealtime() {
        TestSupport.setScreen(app, interactive = false, locked = true)
        val r = TestSupport.call("get_screen_state").getJSONObject("result").getJSONObject("structuredContent")
        assertFalse(r.getBoolean("screenOn"))
        assertTrue(r.getBoolean("keyguardLocked"))
        assertTrue(r.getJSONObject("freshness").getBoolean("realtime"))
    }
}
