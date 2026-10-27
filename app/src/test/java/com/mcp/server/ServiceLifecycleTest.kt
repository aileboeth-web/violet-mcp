package com.mcp.server

import android.app.Service
import android.content.Intent
import com.mcp.server.server.BootReceiver
import com.mcp.server.server.McpServerService
import com.mcp.server.server.ServiceStatus
import com.mcp.server.server.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * 【模拟环境】服务生命周期、开机恢复、用户停止后不被拉起、三态状态。
 * Robolectric 不会模拟真实的进程回收、Doze、厂商后台限制，这些需要真机验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceLifecycleTest {
    private val app get() = TestSupport.app()
    private var controller: ServiceController<McpServerService>? = null

    @Before
    fun setUp() {
        TestSupport.fresh()
        app.getSharedPreferences("diagnostics", 0).edit().clear().commit()
        Settings.setPort(app, ServerSocket(0).use { it.localPort }.coerceAtLeast(1024))
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    private fun startService(action: String?): ServiceController<McpServerService> {
        val intent = Intent(app, McpServerService::class.java).apply { if (action != null) setAction(action) }
        return Robolectric.buildService(McpServerService::class.java, intent).create().startCommand(0, 1)
            .also { controller = it }
    }

    @Test
    fun startServesOnLoopbackOnlyAndStopRecordsUserIntent() {
        Settings.setDesiredRunning(app, true)
        val c = startService(McpServerService.ACTION_START)
        assertTrue(McpServerService.running)
        assertNotNull(shadowOf(c.get()).lastForegroundNotification)
        // 本地端点在 127.0.0.1 上可用，未带令牌返回 401
        val conn = URL("http://127.0.0.1:${Settings.port(app)}/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write("{}".toByteArray()) }
        assertEquals(401, conn.responseCode)
        assertEquals(ServiceStatus.State.RUNNING, ServiceStatus.evaluate(app).state)

        // 通知上的“停止”
        c.withIntent(Intent(app, McpServerService::class.java).setAction(McpServerService.ACTION_STOP)).startCommand(0, 2)
        assertFalse(McpServerService.running)
        assertFalse("用户停止后应记录期望状态为停止", Settings.desiredRunning(app))
        assertEquals(ServiceStatus.State.STOPPED, ServiceStatus.evaluate(app).state)
    }

    @Test
    fun stickyRestartAfterUserStopDoesNotResurrect() {
        Settings.setDesiredRunning(app, false)
        val c = startService(null) // 系统粘性重启：intent 为 null
        assertFalse(McpServerService.running)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test
    fun stickyRestartWhenDesiredResumes() {
        Settings.setDesiredRunning(app, true)
        val c = startService(null)
        assertTrue(McpServerService.running)
        c.destroy()
        controller = null
        assertFalse(McpServerService.running)
    }

    @Test
    fun killedWhileDesiredIsShownAsRestrictedNotRunning() {
        Settings.setDesiredRunning(app, true)
        // 服务没有在运行（例如被系统杀掉）
        val info = ServiceStatus.evaluate(app)
        assertEquals(ServiceStatus.State.RESTRICTED, info.state)
        assertFalse(info.title.contains("运行中") && !info.title.contains("应在运行"))
    }

    @Test
    fun bootRestoresOnlyWhenAutoStartAndNotStoppedByUser() {
        val shadowApp = shadowOf(app)
        val boot = Intent(Intent.ACTION_BOOT_COMPLETED)

        Settings.setAutoStart(app, false)
        Settings.setDesiredRunning(app, true)
        BootReceiver().onReceive(app, boot)
        assertNull(shadowApp.nextStartedService)

        Settings.setAutoStart(app, true)
        Settings.setDesiredRunning(app, false)
        BootReceiver().onReceive(app, boot)
        assertNull("用户手动停止过，开机不应自动启动", shadowApp.nextStartedService)

        Settings.setDesiredRunning(app, true)
        BootReceiver().onReceive(app, boot)
        val started = shadowApp.nextStartedService
        assertEquals(McpServerService.ACTION_RESTORE, started?.action)

        // 其他应用伪造的广播动作被忽略
        BootReceiver().onReceive(app, Intent("android.intent.action.QUICKBOOT_POWERON"))
        assertNull(shadowApp.nextStartedService)
    }

    @Test
    fun applyWhileStoppedDoesNotStartForegroundService() {
        val c = startService(McpServerService.ACTION_APPLY)
        assertFalse(McpServerService.running)
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test
    fun freshInstallIsStoppedNotRestricted() {
        assertEquals(ServiceStatus.State.STOPPED, ServiceStatus.evaluate(app).state)
    }

    @Suppress("unused")
    private fun unused(s: Service) = s
}
