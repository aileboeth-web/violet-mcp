package com.mcp.server.server

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.mcp.server.BuildConfig
import com.mcp.server.MainActivity
import com.mcp.server.update.ReleaseSource
import com.mcp.server.update.UpdateChecker
import com.mcp.server.update.Versions
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 应用自身的更新（Violet MCP 1.4.0）。
 *
 * 更新方式（设置页，默认“自动更新”）：
 *  - auto：每 6 小时检查一次；有新版本就下载、校验，然后交给系统安装（系统通常要你点一次“更新”，
 *    通知栏会提示；Android 12 以上、本应用自己装过一次之后，系统可能允许直接更新）
 *  - notify：每 6 小时检查一次，有新版本只发通知，在应用里点“下载并安装”
 *  - manual：只在点“检查更新”时检查
 *
 * 安装前的检查：SHA-256 与发布里的 SHA256SUMS.txt 一致（[UpdateChecker]）；安装包的包名就是本应用、
 * 版本号比现在高、签名证书链里包含本应用当前的签名证书。任何一项不符都删除安装包、不安装。
 * 系统安装时还会再校验一次签名，所以即使下载来源被冒充，也装不上签名不同的应用。
 */
object AppUpdater {
    const val MODE_AUTO = "auto"
    const val MODE_NOTIFY = "notify"
    const val MODE_MANUAL = "manual"
    val MODES = listOf(MODE_AUTO, MODE_NOTIFY, MODE_MANUAL)
    const val CHECK_INTERVAL_MS = 6L * 3600 * 1000
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 7
    internal const val ACTION_INSTALL_STATUS = "com.mcp.server.UPDATE_INSTALL_STATUS"

    enum class Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val latest: String? = null,
        val message: String? = null,
        val progress: Int = -1,
        val checkedAt: Long = 0L,
        val pageUrl: String? = null,
    ) {
        val newer: Boolean get() = Versions.isNewer(latest, BuildConfig.VERSION_NAME)
    }

    @Volatile
    var state: State = State()
        private set

    /** 仅测试替换：发布来源 */
    @Volatile
    internal var source: () -> ReleaseSource = { UpdateChecker() }

    /** 仅测试替换：安装（真机用 PackageInstaller） */
    @Volatile
    internal var installer: (Context, File) -> Unit = { ctx, file -> commitInstall(ctx, file) }

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "app-updater").apply { isDaemon = true } }
    @Volatile
    private var periodic: ScheduledFuture<*>? = null

    // ---------------------------------------------------------------- settings

    fun mode(context: Context): String =
        Settings.prefs(context).getString("update_mode", MODE_AUTO).takeIf { it in MODES } ?: MODE_AUTO

    fun setMode(context: Context, mode: String) {
        require(mode in MODES)
        Settings.prefs(context).edit().putString("update_mode", mode).apply()
    }

    fun lastCheckAt(context: Context): Long = Settings.prefs(context).getLong("update_checked_at", 0L)

    /** 打开应用 / 服务运行时调用：到时间了才检查（手动模式从不自己检查） */
    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        mode(context) != MODE_MANUAL && now - lastCheckAt(context) >= CHECK_INTERVAL_MS

    /** 界面初始状态：上次检查的结果（进程重启后也能看到） */
    fun restore(context: Context) {
        if (state.checkedAt != 0L || state.phase != Phase.IDLE) return
        val p = Settings.prefs(context)
        val latest = p.getString("update_latest", null)
        state = State(
            phase = if (Versions.isNewer(latest, BuildConfig.VERSION_NAME)) Phase.AVAILABLE else Phase.IDLE,
            latest = latest, checkedAt = p.getLong("update_checked_at", 0L), pageUrl = p.getString("update_page", null),
            message = p.getString("update_error", null),
        )
    }

    // ---------------------------------------------------------------- entry points

    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        if (isDue(app)) executor.execute { runCheck(app, userAsked = false) }
    }

    /** “检查更新”按钮 */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        executor.execute { runCheck(app, userAsked = true) }
    }

    /** “下载并安装”按钮 */
    fun installNow(context: Context) {
        val app = context.applicationContext
        executor.execute { runCheck(app, userAsked = true, install = true) }
    }

    /** 服务运行期间每小时看一次是否到了检查时间 */
    fun startPeriodic(context: Context) {
        val app = context.applicationContext
        if (periodic != null) return
        periodic = executor.scheduleWithFixedDelay({ if (isDue(app)) runCheck(app, userAsked = false) },
            2, 60, TimeUnit.MINUTES)
    }

    fun stopPeriodic() {
        periodic?.cancel(false)
        periodic = null
    }

    // ---------------------------------------------------------------- the work (runs on the executor; tests call it)

    internal fun runCheck(context: Context, userAsked: Boolean, install: Boolean = false) {
        val mode = mode(context)
        state = state.copy(phase = Phase.CHECKING, message = null, progress = -1)
        val release = try {
            source().latest()
        } catch (e: Exception) {
            fail(context, e.message ?: "检查更新失败", userAsked)
            return
        }
        val now = System.currentTimeMillis()
        Settings.prefs(context).edit().putLong("update_checked_at", now).putString("update_latest", release.version)
            .putString("update_page", release.pageUrl).remove("update_error").apply()
        state = State(phase = Phase.IDLE, latest = release.version, checkedAt = now, pageUrl = release.pageUrl)
        if (!Versions.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            state = state.copy(message = "已经是最新版本")
            return
        }
        state = state.copy(phase = Phase.AVAILABLE, message = "有新版本 ${release.version}")
        if (!(install || mode == MODE_AUTO)) {
            if (!userAsked) notify(context, "Violet MCP 有新版本 ${release.version}", "打开应用，在“设置 → 应用更新”里安装")
            return
        }
        val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, "violet-mcp-${release.version}.apk")
        try {
            state = state.copy(phase = Phase.DOWNLOADING, progress = 0, message = "正在下载 ${release.version}")
            source().download(release, file) { read, total ->
                if (total > 0) state = state.copy(progress = (read * 100 / total).toInt())
            }
            verifyApk(context, file, release.version)?.let { problem ->
                file.delete()
                fail(context, problem, userAsked)
                return
            }
        } catch (e: Exception) {
            file.delete()
            fail(context, e.message ?: "下载失败", userAsked)
            return
        }
        cleanOldDownloads(context, keep = file)
        if (!canInstall(context)) {
            state = state.copy(phase = Phase.AVAILABLE, progress = -1,
                message = "已下载并校验 ${release.version}；需要允许 Violet MCP 安装应用（设置 → 应用更新 → 去允许）")
            if (!userAsked) notify(context, "Violet MCP ${release.version} 已下载", "需要允许本应用安装更新，打开应用处理")
            return
        }
        state = state.copy(phase = Phase.INSTALLING, progress = -1, message = "正在安装 ${release.version}")
        try {
            installer(context, file)
        } catch (e: Exception) {
            fail(context, "无法开始安装：${e.message ?: e.javaClass.simpleName}", userAsked)
        }
    }

    private fun fail(context: Context, message: String, userAsked: Boolean) {
        Settings.prefs(context).edit().putString("update_error", message).apply()
        state = state.copy(phase = Phase.ERROR, message = message, progress = -1)
        LogStore.warn("应用更新：$message")
        if (!userAsked && mode(context) == MODE_AUTO && message.contains("校验")) {
            notify(context, "Violet MCP 更新没有安装", message)
        }
    }

    private fun cleanOldDownloads(context: Context, keep: File) {
        File(context.cacheDir, "updates").listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- checks before installing

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** null = 可以安装；否则是不安装的原因 */
    internal fun verifyApk(context: Context, file: File, expectedVersion: String): String? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: return "下载的文件不是有效的安装包"
        if (archive.packageName != context.packageName) return "安装包不是 Violet MCP（${archive.packageName}），不安装"
        if (archive.versionName != expectedVersion) return "安装包的版本（${archive.versionName}）和发布的不一致，不安装"
        val installed = installedInfo(pm, context.packageName) ?: return "读不到本应用的签名"
        if (versionCode(archive) <= versionCode(installed)) return "安装包的版本号不比现在的高，不安装"
        return if (signerAccepted(currentSigners(installed), signerHistory(archive))) null
        else "安装包的签名和本应用不一致，不安装（调试版也不能这样更新）"
    }

    /** 新安装包的证书链里包含本应用当前的签名证书（换过签名密钥也能接上）才接受 */
    internal fun signerAccepted(installedCurrent: Set<String>, archiveHistory: Set<String>): Boolean =
        installedCurrent.isNotEmpty() && archiveHistory.containsAll(installedCurrent)

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? = try {
        pm.getPackageArchiveInfo(file.path, signatureFlags())
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        pm.getPackageInfo(pkg, signatureFlags())
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            return (si.apkContentsSigners ?: emptyArray()).map { digest(it.toByteArray()) }.toSet()
        }
        return (info.signatures ?: emptyArray()).map { digest(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun signerHistory(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            return (certs ?: emptyArray()).map { digest(it.toByteArray()) }.toSet()
        }
        return (info.signatures ?: emptyArray()).map { digest(it.toByteArray()) }.toSet()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- installing and telling the user

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun commitInstall(context: Context, file: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12+: an app that installed itself before may update itself without a prompt
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** UpdateInstallReceiver 收到系统的安装结果 */
    internal fun onInstallStatus(context: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return
                state = state.copy(phase = Phase.INSTALLING, message = "请在系统弹出的窗口里点“更新”")
                val launch = confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(launch)                     // the app is in front: the system dialog opens
                } catch (_: Exception) {
                }
                // in the background Android may not open it: the notification does
                notify(context, "Violet MCP 新版本已下载", "点这里安装", PendingIntent.getActivity(context, 1, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            PackageInstaller.STATUS_SUCCESS -> state = state.copy(phase = Phase.IDLE, message = "已更新")
            else -> {
                val text = "安装没有完成：${message ?: "状态 $status"}"
                state = state.copy(phase = Phase.ERROR, message = text)
                Settings.prefs(context).edit().putString("update_error", text).apply()
                LogStore.warn("应用更新：$text")
            }
        }
    }

    private fun notify(context: Context, title: String, text: String, tap: PendingIntent? = null) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        val open = tap ?: PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // no notification permission: the app shows the same state when opened
        }
    }

    /** 仅测试用 */
    internal fun resetForTest() {
        state = State()
        source = { UpdateChecker() }
        installer = { ctx, file -> commitInstall(ctx, file) }
    }
}
