package com.mcp.server

import android.app.NotificationManager
import android.provider.Settings as AndroidSettings
import com.mcp.server.core.PrivacyPolicy
import com.mcp.server.core.ReadOnlyPolicy
import com.mcp.server.tools.ToolKit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 【模拟环境】用 App 真实注册的工具（Robolectric 模拟 Android 框架）检查：
 * 白名单、隐私默认开关、关闭的工具无法调用、所有工具调用后没有可观察到的副作用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolsPolicyTest {

    @Before
    fun setUp() {
        TestSupport.fresh()
    }

    @Test
    fun appRegistersExactlyTheAllowlist() {
        val names = ToolKit.registry.all().map { it.name }.toSet()
        assertEquals(ReadOnlyPolicy.ALLOWED_TOOLS, names)
    }

    @Test
    fun privacyDefaultsOnFreshInstall() {
        assertEquals(PrivacyPolicy.DEFAULT_ENABLED, TestSupport.listedTools())
        for (name in PrivacyPolicy.SENSITIVE) {
            assertFalse("$name 默认应关闭", ToolKit.isEnabled(name))
            val err = TestSupport.call(name).optJSONObject("error")
            assertEquals("关闭的工具 $name 直接调用必须被拒绝", -32602, err?.getInt("code"))
        }
    }

    @Test
    fun userCanEnableAndDisableTools() {
        ToolKit.setEnabled("get_app_usage_stats", true)
        assertTrue("get_app_usage_stats" in TestSupport.listedTools())
        ToolKit.setEnabled("battery", false)
        assertFalse("battery" in TestSupport.listedTools())
        assertEquals(-32602, TestSupport.call("battery").getJSONObject("error").getInt("code"))
    }

    @Test
    fun removedToolsAreNotCallableEvenWithAllTogglesOn() {
        for (n in ReadOnlyPolicy.ALLOWED_TOOLS) ToolKit.setEnabled(n, true)
        for (n in ReadOnlyPolicy.REMOVED_TOOLS) ToolKit.setEnabled(n, true) // 即使有人写入了开关，也不会生效
        for (n in ReadOnlyPolicy.REMOVED_TOOLS) {
            assertEquals(n, -32602, TestSupport.call(n, JSONObject().put("command", "id")).getJSONObject("error").getInt("code"))
        }
        assertTrue(TestSupport.listedTools().none { it in ReadOnlyPolicy.REMOVED_TOOLS })
    }

    @Test
    fun everyToolRunsWithoutObservableSideEffects() {
        val app = TestSupport.app()
        TestSupport.setUsageAccess(app, true)
        for (n in ReadOnlyPolicy.ALLOWED_TOOLS) ToolKit.setEnabled(n, true)
        val brightnessBefore = AndroidSettings.System.getInt(app.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, -1)
        val filesBefore = app.filesDir.list()?.toSet() ?: emptySet()
        val shadowApp = shadowOf(app)

        val args = mapOf("app_info" to JSONObject().put("packageName", app.packageName))
        for (n in ReadOnlyPolicy.ALLOWED_TOOLS) {
            val r = TestSupport.call(n, args[n] ?: JSONObject())
            assertTrue("$n 应返回结果：$r", r.has("result"))
        }

        // 没有启动任何 Activity / Service，没有发送广播，没有发通知，没有改系统设置，没有写文件
        assertNull(shadowApp.nextStartedActivity)
        assertNull(shadowApp.nextStartedService)
        assertTrue(shadowApp.broadcastIntents.isEmpty())
        assertEquals(0, shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertEquals(brightnessBefore,
            AndroidSettings.System.getInt(app.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, -1))
        assertEquals(filesBefore, app.filesDir.list()?.toSet() ?: emptySet<String>())
    }

    @Test
    fun systemPropertiesNoLongerUsesHiddenApi() {
        ToolKit.setEnabled("system_properties", true)
        val r = TestSupport.call("system_properties").getJSONObject("result").getJSONObject("structuredContent")
        assertTrue(r.has("ro.build.version.sdk"))
        assertFalse(r.has("ro.serialno"))
    }
}
