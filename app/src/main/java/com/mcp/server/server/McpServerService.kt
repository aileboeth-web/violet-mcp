package com.mcp.server.server

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.mcp.server.BuildConfig
import com.mcp.server.MainActivity
import com.mcp.server.core.LocalHttpServer
import com.mcp.server.core.McpEndpoint
import com.mcp.server.core.McpProtocol
import com.mcp.server.core.RelayClient
import com.mcp.server.tools.ToolKit
import com.mcp.server.tools.UsageTools
import org.json.JSONObject

/**
 * 前台服务：持有本地 MCP 端点（只监听 127.0.0.1）和中继客户端。
 *
 * - 前台服务类型 specialUse（dataSync 在 Android 15 每天限 6 小时且不能开机启动）。这只是让系统允许
 *   长时间运行的前提，不能保证永久存活：厂商系统、后台限制、用户在任务管理器中停止都可能结束服务。
 * - 用户点“停止”会记录期望状态为停止，系统粘性重启、开机和应用更新广播都不会再把服务拉起。
 * - Android 12+ 系统可能拒绝在后台启动前台服务（[ForegroundServiceStartNotAllowedException]），
 *   此时记录原因、停止自身，并在界面上显示为“受限”，而不是崩溃或假装在运行。
 */
class McpServerService : Service() {

    companion object {
        const val ACTION_START = "com.mcp.server.START"
        /** 开机 / 应用更新后自动恢复 */
        const val ACTION_RESTORE = "com.mcp.server.RESTORE"
        const val ACTION_STOP = "com.mcp.server.STOP"
        /** 设置变更后重新应用（中继配置 / 日志级别等） */
        const val ACTION_APPLY = "com.mcp.server.APPLY"
        /** MCP 令牌重新生成后通知中继刷新令牌哈希 */
        const val ACTION_TOKEN_CHANGED = "com.mcp.server.TOKEN_CHANGED"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var localServer: LocalHttpServer? = null

        @Volatile
        private var relay: RelayClient? = null

        @Volatile
        var relayState: RelayClient.RelayState? = null
            private set

        @Volatile
        private var startedAt: Long = 0L

        val localRunning: Boolean get() = localServer?.isRunning == true

        fun uptimeSeconds(): Long {
            val s = startedAt
            return if (s > 0) (SystemClock.elapsedRealtime() - s) / 1000 else 0L
        }

        /** 用户在界面上点“启动” */
        fun start(context: Context) {
            Settings.setDesiredRunning(context, true)
            Diagnostics.clearStartFailure(context)
            context.startForegroundService(Intent(context, McpServerService::class.java).setAction(ACTION_START))
        }

        /** 用户在界面上点“停止” */
        fun stop(context: Context) {
            Settings.setDesiredRunning(context, false)
            if (running) context.startService(Intent(context, McpServerService::class.java).setAction(ACTION_STOP))
        }

        /** 服务运行中才转发设置变更，避免为此启动服务 */
        fun notifyIfRunning(context: Context, action: String) {
            if (running) context.startService(Intent(context, McpServerService::class.java).setAction(action))
        }
    }

    private var endpoint: McpEndpoint? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastNotificationText = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 来自界面或通知上的“停止”：记录为用户主动停止
                Settings.setDesiredRunning(this, false)
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPLY -> {
                if (!running) return stopIdle()
                LogStore.setLevel(Settings.logLevel(this))
                restartRelay()
                refreshNotification()
            }
            ACTION_TOKEN_CHANGED -> {
                if (!running) return stopIdle()
                relay?.reconnectNow("令牌已更新")
            }
            else -> {
                // ACTION_START / ACTION_RESTORE / 系统粘性重启（intent 为 null）
                if (!Settings.desiredRunning(this)) {
                    LogStore.info("用户已停止服务，不自动恢复")
                    return stopIdle()
                }
                if (!startEverything()) return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /** 被 startService 拉起但不应运行时，直接退出 */
    private fun stopIdle(): Int {
        if (!running) stopSelf()
        return START_NOT_STICKY
    }

    private fun startEverything(): Boolean {
        // 必须先进入前台，再做其他初始化
        if (!enterForeground()) return false
        if (running) return true
        running = true
        startedAt = SystemClock.elapsedRealtime()
        Diagnostics.clearStartFailure(this)
        LogStore.setLevel(Settings.logLevel(this))
        ToolKit.init(this)
        if (Settings.legacyLanAccessWasOn(this)) {
            LogStore.warn("1.1.0 的“局域网访问”（明文 HTTP）已移除，本地端点只监听 127.0.0.1；远程访问请使用 TLS 中继")
        }
        val protocol = McpProtocol(
            registry = ToolKit.registry,
            serverName = "violet-android-readonly-mcp",
            serverVersion = BuildConfig.VERSION_NAME,
            instructions = INSTRUCTIONS,
            log = LogStore,
            onToolCall = { name, isError, nanos ->
                val ms = nanos / 1e6
                Diagnostics.recordToolCall(name, ms, !isError)
                LogStore.info("工具调用 $name ${"%.1f".format(ms)}ms${if (isError) "（返回错误）" else ""}")
            },
        )
        endpoint = McpEndpoint(protocol, { Settings.token(this) }, { statusJson() }, LogStore) { r ->
            Diagnostics.recordRequest(r)
            LogStore.debug("请求 ${r.method} ${r.path} -> ${r.status} 手机内处理 ${"%.1f".format(r.handleMs)}ms，工具 ${"%.1f".format(r.toolMs)}ms")
        }
        if (Settings.tokenProblem(this) != null) {
            LogStore.error("访问令牌无法读取：所有请求都会被拒绝，请在“安全”页重新生成令牌")
        }
        if (Settings.wakeLockEnabled(this)) WakeLockHelper.acquire(this)
        startLocal()
        registerNetworkCallback()
        restartRelay()
        if (!UsageTools.hasUsageAccess(this)) {
            LogStore.warn("未授予使用情况访问权限：前台应用、使用时长工具会返回权限不足")
        }
        LogStore.info("服务已启动，已开启 ${ToolKit.enabledCount()} 个只读工具")
        refreshNotification()
        return true
    }

    private fun stopEverything() {
        unregisterNetworkCallback()
        relay?.stop()
        relay = null
        relayState = null
        localServer?.stop()
        localServer = null
        endpoint = null
        WakeLockHelper.release()
        if (running) LogStore.info("服务已停止")
        running = false
        startedAt = 0L
    }

    private fun startLocal() {
        val ep = endpoint ?: return
        localServer?.stop()
        // 只监听回环地址；LocalHttpServer 对其他地址会直接拒绝启动
        localServer = LocalHttpServer(ep, "127.0.0.1", Settings.port(this), LogStore).also { it.start() }
    }

    private fun restartRelay() {
        val ep = endpoint ?: return
        relay?.stop() // 不阻塞
        relay = null
        relayState = null
        if (!Settings.relayEnabled(this)) return
        val cfg = Settings.relayConfig(this, BuildConfig.VERSION_NAME)
        val problem = Settings.relaySecretProblem(this) ?: RelayClient.validate(cfg)
        if (problem != null) {
            LogStore.error("中继配置无效：$problem")
            relayState = RelayClient.RelayState(RelayClient.Phase.REJECTED, problem)
            return
        }
        val app = applicationContext
        relay = RelayClient(
            config = cfg,
            endpoint = ep,
            mcpTokenProvider = { Settings.token(app) },
            log = LogStore,
            onState = { s ->
                val phaseChanged = relayState?.phase != s.phase
                relayState = s
                if (phaseChanged) {
                    Diagnostics.recordRelay(app, s)
                    refreshNotification()
                }
            },
            clock = { SystemClock.elapsedRealtime() }, // 包含深度睡眠时间
            workGuard = { block -> WakeLockHelper.withRequestWakeLock(app) { block() } },
        ).also {
            it.setNetworkAvailable(hasNetwork())
            it.start()
        }
    }

    // ==================== 网络 ====================

    private fun hasNetwork(): Boolean = try {
        getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    } catch (_: Exception) {
        true
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                val changed = current != null && network != current
                val wasDown = current == null
                current = network
                relay?.setNetworkAvailable(true)
                if (changed) {
                    LogStore.info("默认网络已切换")
                    relay?.reconnectNow("网络切换")
                } else if (wasDown) {
                    LogStore.debug("默认网络可用")
                }
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    if (cm.activeNetwork == null) {
                        LogStore.warn("网络已断开，暂停重连")
                        relay?.setNetworkAvailable(false)
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            LogStore.warn("无法监听网络变化: ${e.javaClass.simpleName}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    // ==================== 状态 / 通知 ====================

    private fun statusJson(): JSONObject = JSONObject().apply {
        put("running", running)
        put("version", BuildConfig.VERSION_NAME)
        put("uptimeSeconds", uptimeSeconds())
        put("relayPhase", relayState?.phase?.name ?: "STOPPED")
        put("enabledTools", ToolKit.enabledCount())
    }

    private fun notificationText(): String {
        val r = relayState
        val relayText = when {
            !Settings.relayEnabled(this) -> "远程访问未开启"
            r == null -> "中继未启动"
            r.phase == RelayClient.Phase.CONNECTED -> "已连接中继"
            r.phase == RelayClient.Phase.CONNECTING -> "正在连接中继"
            r.phase == RelayClient.Phase.WAITING_NETWORK -> "等待网络"
            r.phase == RelayClient.Phase.REJECTED -> "中继认证或证书有问题"
            r.phase == RelayClient.Phase.REPLACED -> "已被另一台设备顶替"
            else -> "中继重连中"
        }
        return "已开启 ${ToolKit.enabledCount()} 个只读工具 · $relayText"
    }

    /** 进入前台；系统拒绝时记录原因并返回 false */
    private fun enterForeground(): Boolean {
        val n = buildNotification(notificationText().also { lastNotificationText = it })
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: Exception) {
            val reason = if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                "系统不允许在后台启动前台服务"
            } else {
                "无法进入前台：${e.javaClass.simpleName}"
            }
            Diagnostics.recordStartFailure(this, reason)
            LogStore.error("服务启动失败：$reason")
            running = false
            stopSelf()
            false
        }
    }

    private fun refreshNotification() {
        if (!running) return
        val text = notificationText()
        if (text == lastNotificationText) return
        lastNotificationText = text
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MCP 服务器", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持只读 MCP 服务器在后台运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, McpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("只读 MCP 服务器运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}

private const val INSTRUCTIONS =
    "这是运行在用户 Android 手机上的只读 MCP 服务器，供 Violet 感知手机状态。所有工具都只读，不能修改手机。" +
        "get_foreground_app 只有 state=current 时才表示用户正在使用该应用；screen_off、locked、unknown 表示无法确定当前应用，" +
        "不要猜测。get_screen_state 是实时读取的亮灭屏和锁屏状态。工具列表由用户在手机上控制，未列出的工具不可用。" +
        "如果工具返回 permission_denied，说明用户尚未在手机上授予使用情况访问权限。"
