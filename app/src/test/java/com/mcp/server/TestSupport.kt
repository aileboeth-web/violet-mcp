package com.mcp.server

import android.app.AppOpsManager
import android.app.Application
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.PowerManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.mcp.server.core.McpProtocol
import com.mcp.server.server.Settings
import com.mcp.server.tools.ToolKit
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

/** Robolectric（模拟 Android 框架）测试的公共工具 */
object TestSupport {

    fun app(): Application = ApplicationProvider.getApplicationContext()

    /** 每个测试开始时调用：清空设置与开关，重建注册表 */
    fun fresh(): Application {
        val app = app()
        Settings.resetForTest()
        // 默认模拟 Keystore 正常的设备；需要模拟 Keystore 不可用的测试会自行覆盖
        Settings.cipherFactory = { SecretStoreTest.FakeCipher() }
        app.getSharedPreferences("tool_enabled", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("mcp_server_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ToolKit.initForTest(app)
        return app
    }

    fun protocol(): McpProtocol = McpProtocol(ToolKit.registry, "test", "0", "test")

    fun call(name: String, args: JSONObject = JSONObject()): JSONObject {
        val o = protocol().handleBody(
            JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .put("params", JSONObject().put("name", name).put("arguments", args)).toString()
        ) as McpProtocol.Outcome.Reply
        return JSONObject(o.json)
    }

    fun listedTools(): Set<String> {
        val o = protocol().handleBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") as McpProtocol.Outcome.Reply
        val arr: JSONArray = JSONObject(o.json).getJSONObject("result").getJSONArray("tools")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.toSet()
    }

    fun setUsageAccess(app: Context, allowed: Boolean) {
        val ops = app.getSystemService(AppOpsManager::class.java)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    fun setScreen(app: Context, interactive: Boolean, locked: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(interactive)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
    }

    fun addEvent(app: Context, pkg: String?, type: Int, timeMs: Long, cls: String? = pkg?.let { "$it.Main" }) {
        val usm = app.getSystemService(UsageStatsManager::class.java)
        val b = ShadowUsageStatsManager.EventBuilder.buildEvent().setTimeStamp(timeMs).setEventType(type)
        if (pkg != null) b.setPackage(pkg)
        if (cls != null) b.setClass(cls)
        shadowOf(usm).addEvent(b.build())
    }

    /** 安装一个带桌面图标的应用，用于解析应用名 */
    fun installApp(app: Context, pkg: String, label: String) {
        val ai = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            flags = 0
        }
        val pi = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ai
            versionName = "1.0"
        }
        shadowOf(app.packageManager).installPackage(pi)
    }

    const val RESUMED = UsageEvents.Event.ACTIVITY_RESUMED
    const val PAUSED = UsageEvents.Event.ACTIVITY_PAUSED
    const val SCREEN_OFF = UsageEvents.Event.SCREEN_NON_INTERACTIVE
}
