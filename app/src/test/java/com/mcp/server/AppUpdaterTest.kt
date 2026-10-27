package com.mcp.server

import android.content.pm.PackageInstaller
import com.mcp.server.update.ReleaseSource
import com.mcp.server.update.UpdateChecker
import com.mcp.server.server.AppUpdater
import com.mcp.server.server.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 【模拟环境】应用更新：更新方式、检查节奏、只提醒 / 自动下载、安装前校验。
 * 真正的系统安装（PackageInstaller 弹窗、签名校验、更新后服务恢复）需要真机验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {
    private val app get() = TestSupport.app()

    private class FakeSource(val version: String, val bytes: ByteArray = "not an apk".toByteArray()) : ReleaseSource {
        var downloads = 0
        var fail: String? = null
        override fun latest(): UpdateChecker.Release {
            fail?.let { throw UpdateChecker.UpdateException(it) }
            return UpdateChecker.Release("v$version", version, "https://github.com/aileboeth-web/violet-mcp/releases/tag/v$version",
                null, "", "violet-mcp-$version-release.apk", "https://github.com/x.apk", bytes.size.toLong(),
                "https://github.com/SHA256SUMS.txt")
        }

        override fun download(release: UpdateChecker.Release, target: File, progress: (Long, Long) -> Unit): String {
            downloads++
            target.writeBytes(bytes)
            progress(bytes.size.toLong(), bytes.size.toLong())
            return "sha"
        }
    }

    private val installs = mutableListOf<File>()

    @Before
    fun setUp() {
        TestSupport.fresh()
        AppUpdater.resetForTest()
        AppUpdater.installer = { _, f -> installs.add(f) }
    }

    @After
    fun tearDown() = AppUpdater.resetForTest()

    @Test
    fun theDefaultIsAutomaticAndManualNeverChecksByItself() {
        assertEquals(AppUpdater.MODE_AUTO, AppUpdater.mode(app))
        assertTrue(AppUpdater.isDue(app))
        AppUpdater.setMode(app, AppUpdater.MODE_MANUAL)
        assertFalse(AppUpdater.isDue(app))
        AppUpdater.setMode(app, AppUpdater.MODE_NOTIFY)
        assertTrue(AppUpdater.isDue(app))
        Settings.prefs(app).edit().putString("update_mode", "bogus").commit()
        assertEquals(AppUpdater.MODE_AUTO, AppUpdater.mode(app))
    }

    @Test
    fun aCheckIsDueAgainAfterSixHours() {
        val src = FakeSource(BuildConfig.VERSION_NAME)
        AppUpdater.source = { src }
        AppUpdater.runCheck(app, userAsked = false)
        val at = AppUpdater.lastCheckAt(app)
        assertFalse(AppUpdater.isDue(app, at + AppUpdater.CHECK_INTERVAL_MS - 1))
        assertTrue(AppUpdater.isDue(app, at + AppUpdater.CHECK_INTERVAL_MS))
        assertEquals("已经是最新版本", AppUpdater.state.message)
        assertFalse(AppUpdater.state.newer)
    }

    @Test
    fun notifyModeReportsButDoesNotDownload() {
        AppUpdater.setMode(app, AppUpdater.MODE_NOTIFY)
        val src = FakeSource("99.0.0")
        AppUpdater.source = { src }
        AppUpdater.runCheck(app, userAsked = false)
        assertEquals(AppUpdater.Phase.AVAILABLE, AppUpdater.state.phase)
        assertTrue(AppUpdater.state.newer)
        assertEquals(0, src.downloads)
        assertTrue(installs.isEmpty())
    }

    @Test
    fun aDownloadThatIsNotASignedVioletMcpIsDeletedAndNeverInstalled() {
        val src = FakeSource("99.0.0")
        AppUpdater.source = { src }
        AppUpdater.runCheck(app, userAsked = true, install = true)
        assertEquals(1, src.downloads)
        assertEquals(AppUpdater.Phase.ERROR, AppUpdater.state.phase)
        assertTrue(AppUpdater.state.message!!.contains("不是有效的安装包"))
        assertTrue(installs.isEmpty())
        assertTrue(File(app.cacheDir, "updates").listFiles().isNullOrEmpty())
    }

    @Test
    fun automaticModeDownloadsByItself() {
        val src = FakeSource("99.0.0")
        AppUpdater.source = { src }
        AppUpdater.runCheck(app, userAsked = false)
        assertEquals(1, src.downloads)                     // then refused by the checks (fake bytes)
        assertTrue(installs.isEmpty())
    }

    @Test
    fun aFailedCheckIsShownAndRemembered() {
        val src = FakeSource("99.0.0").apply { fail = "网络不通或被拦截（UnknownHostException）" }
        AppUpdater.source = { src }
        AppUpdater.runCheck(app, userAsked = true)
        assertEquals(AppUpdater.Phase.ERROR, AppUpdater.state.phase)
        assertEquals("网络不通或被拦截（UnknownHostException）", Settings.prefs(app).getString("update_error", null))
    }

    @Test
    fun theLastResultSurvivesARestartOfTheApp() {
        AppUpdater.setMode(app, AppUpdater.MODE_NOTIFY)
        AppUpdater.source = { FakeSource("99.0.0") }
        AppUpdater.runCheck(app, userAsked = false)
        AppUpdater.resetForTest()
        AppUpdater.restore(app)
        assertEquals("99.0.0", AppUpdater.state.latest)
        assertEquals(AppUpdater.Phase.AVAILABLE, AppUpdater.state.phase)
    }

    @Test
    fun theSignerRule() {
        // the new package's certificate history must contain the current certificate (key rotation still works)
        assertTrue(AppUpdater.signerAccepted(setOf("new"), setOf("old", "new")))
        assertFalse(AppUpdater.signerAccepted(setOf("new"), setOf("other")))
        assertFalse(AppUpdater.signerAccepted(emptySet(), setOf("x")))
    }

    @Test
    fun aFailedInstallIsShown() {
        AppUpdater.onInstallStatus(app, PackageInstaller.STATUS_FAILURE_CONFLICT, "签名冲突", null)
        assertEquals(AppUpdater.Phase.ERROR, AppUpdater.state.phase)
        assertTrue(AppUpdater.state.message!!.contains("签名冲突"))
    }
}
