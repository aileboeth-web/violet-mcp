package com.mcp.server.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** 中继连接配置 */
data class RelayConfig(
    /** 中继设备入口，必须是 wss://，例如 wss://relay.example.com:8443/device */
    val url: String,
    /** 设备密钥（与中继 device.secret 一致） */
    val deviceSecret: String,
    /**
     * 可选：中继证书公钥指纹（SPKI SHA-256），格式 sha256/<base64> 或 64 位十六进制。
     * 可以填多个（逗号或换行分隔，最多 3 个），用于更换证书时新旧证书并存。为空则用系统 CA 校验。
     */
    val pin: String? = null,
    val deviceName: String = "android",
    val appVersion: String = "",
    /** TCP + TLS + WebSocket 握手超时 */
    val connectTimeoutSec: Int = 15,
    /** 手机侧 WebSocket ping 间隔（兜底检测；0 表示关闭）。主要心跳由中继发起，见 [RelayClient] 说明 */
    val clientPingIntervalSec: Int = 60,
    /** 断线重连的最大退避间隔 */
    val maxRetrySec: Int = 60,
)

/**
 * 手机主动连接 VPS 中继的客户端（TLS WebSocket）。
 *
 * 帧格式（JSON 文本帧）：
 *  - 手机 → 中继  {"type":"hello","protocol":1,"deviceName":..,"appVersion":..,"mcpTokenSha256":..,"features":["app_ping"]}
 *  - 中继 → 手机  {"type":"hello_ack","pingIntervalMs":30000}
 *  - 中继 → 手机  {"type":"ping","t":..}            手机回 {"type":"pong","t":..}
 *  - 中继 → 手机  {"type":"http_request","id":..,"method":..,"path":..,"headers":{..},"body":".."}
 *  - 手机 → 中继  {"type":"http_response","id":..,"status":..,"headers":{..},"body":".."}
 *
 * 心跳：由中继定期发 ping（息屏时网络包能唤醒手机，保持 NAT 映射），手机只回 pong 并记录“最近收到中继数据的时间”；
 * 看门狗发现超过 2.5 个心跳周期没有收到任何数据就主动断开重连。OkHttp 自带的 ping 作为兜底（兼容旧版中继）。
 *
 * 重连：网络错误按指数退避（1s → maxRetrySec，±20% 抖动）；没有网络时暂停，等 [setNetworkAvailable] 通知；
 * [reconnectNow] 有 2 秒去抖；被另一台设备顶替（4000）或认证/证书失败时进入长退避，避免互相顶替和无意义的重试。
 * 证书指纹不匹配时记录服务器实际出示的指纹供人工核对，**绝不自动接受新证书**。
 *
 * 日志中不出现设备密钥、MCP 令牌和请求内容。
 */
class RelayClient(
    private val config: RelayConfig,
    private val endpoint: McpEndpoint,
    private val mcpTokenProvider: () -> String,
    private val log: CoreLog = NoopLog,
    private val onState: (RelayState) -> Unit = {},
    /** 单调时钟（毫秒）。Android 上传入 SystemClock.elapsedRealtime（包含深度睡眠时间） */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** 处理单个请求时的保护（Android 上用短时 WakeLock，防止处理到一半 CPU 休眠） */
    private val workGuard: (() -> Unit) -> Unit = { it() },
    /** 看门狗判定超时的下限（默认 45 秒；测试中可调小） */
    private val minWatchdogMs: Long = 45_000L,
) {

    enum class Phase {
        STOPPED, CONNECTING, CONNECTED,
        /** 网络错误，按退避等待重连 */
        WAITING_RETRY,
        /** 当前没有可用网络，暂停重连 */
        WAITING_NETWORK,
        /** 设备密钥被拒、证书不受信任或指纹不匹配：需要用户检查配置 */
        REJECTED,
        /** 另一台设备用同一组凭据连上了中继 */
        REPLACED,
    }

    data class RelayState(
        val phase: Phase,
        val message: String = "",
        /** 以下时间都是墙上时钟（毫秒），供界面显示 */
        val connectedSince: Long = 0L,
        val nextRetryAt: Long = 0L,
        val reconnects: Int = 0,
        val lastConnectedAt: Long = 0L,
        val lastFailureAt: Long = 0L,
        val lastFailureReason: String = "",
        /** 证书指纹不匹配时，中继实际出示的指纹（供人工与 VPS 上的 -print-pin 输出核对） */
        val presentedPin: String = "",
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "relay-sched").apply { isDaemon = true }
    }
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64)) { r ->
        Thread(r, "relay-worker").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private val trustManager: PinnedTrustManager? = parsePins(config.pin)?.let { PinnedTrustManager(it) }
    private val client: OkHttpClient = buildClient(config, trustManager)

    // 以下字段只在 scheduler 线程读写
    private var started = false
    private var generation = 0
    private var socket: WebSocket? = null
    private var attempt = 0
    private var reconnects = 0
    private var pending: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var networkAvailable = true
    private var lastConnectStart = Long.MIN_VALUE / 2
    private var lastConnectedAt = 0L
    private var lastFailureAt = 0L
    private var lastFailureReason = ""

    /** 最近一次收到中继任何数据的单调时间（OkHttp 线程写，scheduler 读） */
    @Volatile
    private var lastActivity = 0L

    @Volatile
    var state: RelayState = RelayState(Phase.STOPPED)
        private set

    val host: String = try { URI(config.url).host ?: "" } catch (_: Exception) { "" }

    fun start() = post {
        if (started) return@post
        started = true
        log.info("中继客户端启动，目标 $host")
        connect()
    }

    /** 停止并释放线程（不阻塞调用线程）。停止后不能再次 start，需要新建实例 */
    fun stop() {
        post {
            started = false
            generation++
            pending?.cancel(false)
            watchdog?.cancel(false)
            socket?.close(1000, "stop")
            socket = null
            publish(state.copy(phase = Phase.STOPPED, message = "已停止", nextRetryAt = 0L))
            log.info("中继客户端已停止")
            scheduler.shutdown()
        }
        workers.shutdown()
        // OkHttp 自己的线程在关闭握手完成（或超时）后会自行退出，这里不强制关停，避免关闭帧发不出去
    }

    /** 立即重连（网络切换、令牌更新时调用），重置退避；2 秒内的重复调用会合并 */
    fun reconnectNow(reason: String) = post {
        if (!started) return@post
        if (!networkAvailable) {
            log.info("暂无网络，忽略立即重连（$reason）")
            return@post
        }
        val since = clock() - lastConnectStart
        attempt = 0
        if (since < DEBOUNCE_MS) {
            pending?.cancel(false)
            pending = scheduler.schedule({ reconnect(reason) }, DEBOUNCE_MS - since, TimeUnit.MILLISECONDS)
            return@post
        }
        reconnect(reason)
    }

    /** 由网络监听调用：无网络时暂停重连，恢复后立即连接 */
    fun setNetworkAvailable(available: Boolean) = post {
        if (networkAvailable == available) return@post
        networkAvailable = available
        if (!started) return@post
        if (!available) {
            if (state.phase != Phase.CONNECTED) {
                pending?.cancel(false)
                publish(state.copy(phase = Phase.WAITING_NETWORK, message = "等待网络", nextRetryAt = 0L))
            }
            return@post
        }
        if (state.phase == Phase.WAITING_NETWORK || state.phase == Phase.WAITING_RETRY) {
            attempt = 0
            log.info("网络已恢复，重新连接中继")
            pending?.cancel(false)
            connect()
        }
    }

    // ==================== 连接管理（scheduler 线程） ====================

    private fun post(block: () -> Unit) {
        try {
            scheduler.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun reconnect(reason: String) {
        if (!started) return
        log.info("立即重连中继：$reason")
        pending?.cancel(false)
        generation++
        socket?.cancel()
        socket = null
        connect()
    }

    private fun connect() {
        if (!started) return
        if (!networkAvailable) {
            publish(state.copy(phase = Phase.WAITING_NETWORK, message = "等待网络", nextRetryAt = 0L))
            return
        }
        val gen = ++generation
        lastConnectStart = clock()
        watchdog?.cancel(false)
        publish(state.copy(phase = Phase.CONNECTING, message = "正在连接 $host", nextRetryAt = 0L))
        val request = Request.Builder()
            .url(config.url)
            .header("Authorization", "Bearer ${config.deviceSecret}")
            .header("X-Violet-Relay-Protocol", PROTOCOL_VERSION.toString())
            .build()
        socket = client.newWebSocket(request, Listener(gen))
    }

    private enum class Failure { NETWORK, REJECTED, REPLACED }

    private fun scheduleReconnect(reason: String, kind: Failure, presentedPin: String = "") {
        if (!started) return
        watchdog?.cancel(false)
        lastFailureAt = System.currentTimeMillis()
        lastFailureReason = reason
        if (!networkAvailable && kind == Failure.NETWORK) {
            publish(failureState(Phase.WAITING_NETWORK, "等待网络", 0L, presentedPin))
            return
        }
        attempt++
        reconnects++
        val maxMs = config.maxRetrySec.coerceIn(10, 3600) * 1000L
        val base = (INITIAL_RETRY_MS shl (attempt - 1).coerceAtMost(10)).coerceAtMost(maxMs)
        val floor = when (kind) {
            Failure.NETWORK -> 0L
            Failure.REJECTED -> REJECTED_RETRY_MS
            Failure.REPLACED -> REPLACED_RETRY_MS
        }
        val delay = (maxOf(base, floor) * (0.8 + Math.random() * 0.4)).toLong()
        val phase = when (kind) {
            Failure.NETWORK -> Phase.WAITING_RETRY
            Failure.REJECTED -> Phase.REJECTED
            Failure.REPLACED -> Phase.REPLACED
        }
        publish(failureState(phase, reason, System.currentTimeMillis() + delay, presentedPin))
        log.warn("中继断开（$reason），${delay / 1000} 秒后重连")
        pending?.cancel(false)
        pending = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun failureState(phase: Phase, message: String, nextRetryAt: Long, presentedPin: String) = RelayState(
        phase = phase, message = message, nextRetryAt = nextRetryAt, reconnects = reconnects,
        lastConnectedAt = lastConnectedAt, lastFailureAt = lastFailureAt, lastFailureReason = lastFailureReason,
        presentedPin = presentedPin,
    )

    private fun onConnected(pingIntervalMs: Long) {
        attempt = 0
        lastConnectedAt = System.currentTimeMillis()
        lastActivity = clock()
        publish(RelayState(Phase.CONNECTED, "已连接 $host", connectedSince = lastConnectedAt,
            reconnects = reconnects, lastConnectedAt = lastConnectedAt,
            lastFailureAt = lastFailureAt, lastFailureReason = lastFailureReason))
        log.info("已连接中继 $host")
        watchdog?.cancel(false)
        if (pingIntervalMs > 0) {
            val limit = maxOf((pingIntervalMs * 5) / 2, minWatchdogMs)
            val gen = generation
            watchdog = scheduler.scheduleWithFixedDelay({
                if (gen == generation && started && clock() - lastActivity > limit) {
                    log.warn("超过 ${limit / 1000} 秒没有收到中继心跳，判定连接失效")
                    generation++
                    socket?.cancel()
                    socket = null
                    scheduleReconnect("心跳超时", Failure.NETWORK)
                }
            }, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun publish(s: RelayState) {
        state = s
        try { onState(s) } catch (_: Exception) {}
    }

    private fun onEvent(gen: Int, block: () -> Unit) = post { if (started && gen == generation) block() }

    private inner class Listener(private val gen: Int) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastActivity = clock()
            val hello = JSONObject()
                .put("type", "hello")
                .put("protocol", PROTOCOL_VERSION)
                .put("deviceName", config.deviceName)
                .put("appVersion", config.appVersion)
                .put("mcpTokenSha256", Secrets.sha256Hex(mcpTokenProvider()))
                .put("features", org.json.JSONArray().put("app_ping"))
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val receivedAt = System.nanoTime()
            lastActivity = clock()
            val frame = try { JSONObject(text) } catch (_: Exception) {
                log.warn("中继帧格式错误，已忽略")
                return
            }
            when (frame.optString("type")) {
                "hello_ack" -> {
                    val interval = frame.optLong("pingIntervalMs", 0L)
                    onEvent(gen) { onConnected(interval) }
                }
                "http_request" -> {
                    try {
                        workers.execute { workGuard { serve(webSocket, frame, receivedAt) } }
                    } catch (_: RejectedExecutionException) {
                        webSocket.send(responseFrame(frame.optString("id"),
                            HttpResponse.json(503, "{\"error\":\"busy\"}")).toString())
                    }
                }
                "ping" -> webSocket.send(JSONObject().put("type", "pong").put("t", frame.opt("t") ?: 0).toString())
                "error" -> log.warn("中继返回错误: ${frame.optString("message").take(200)}")
                else -> {}
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onEvent(gen) {
                socket = null
                when (code) {
                    CLOSE_REPLACED -> scheduleReconnect(
                        "另一台设备使用同一组凭据连接了中继（本机已被顶替）", Failure.REPLACED)
                    CLOSE_REJECTED -> scheduleReconnect("中继拒绝了本设备（$reason）", Failure.REJECTED)
                    else -> scheduleReconnect("连接关闭 $code ${reason.take(80)}".trim(), Failure.NETWORK)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            response?.close()
            val mismatch = trustManager?.takeMismatch()
            onEvent(gen) {
                socket = null
                when {
                    mismatch != null -> scheduleReconnect(
                        "中继证书指纹不匹配，可能是证书已更换或连接被拦截。请在 VPS 上运行 -print-pin 核对后再更新指纹",
                        Failure.REJECTED, presentedPin = mismatch)
                    code == 401 || code == 403 ->
                        scheduleReconnect("中继拒绝了设备密钥（HTTP $code），请检查设备密钥", Failure.REJECTED)
                    t is javax.net.ssl.SSLHandshakeException || t is javax.net.ssl.SSLPeerUnverifiedException ->
                        scheduleReconnect("中继证书不受信任：${t.message?.take(120) ?: ""}（自签证书需要填写证书指纹）",
                            Failure.REJECTED)
                    else -> scheduleReconnect(describe(t, code), Failure.NETWORK)
                }
            }
        }
    }

    private fun serve(webSocket: WebSocket, frame: JSONObject, receivedAt: Long) {
        val id = frame.optString("id")
        val response = try {
            val headers = HashMap<String, String>()
            frame.optJSONObject("headers")?.let { h ->
                for (k in h.keys()) headers[k.lowercase()] = h.optString(k)
            }
            val body = frame.optString("body", "").toByteArray(Charsets.UTF_8)
            if (body.size > LocalHttpServer.MAX_BODY_BYTES) {
                HttpResponse.json(413, "{\"error\":\"body too large\"}")
            } else {
                val method = frame.optString("method", "POST").uppercase()
                val path = frame.optString("path", "/mcp")
                endpoint.handle(HttpRequest(method, path, headers, body, receivedAt))
            }
        } catch (e: Exception) {
            log.error("处理中继请求异常: ${e.javaClass.simpleName}")
            HttpResponse.json(500, "{\"error\":\"internal\"}")
        }
        webSocket.send(responseFrame(id, response).toString())
    }

    private fun responseFrame(id: String, r: HttpResponse): JSONObject {
        val headers = JSONObject()
        for ((k, v) in r.headers) headers.put(k, v)
        return JSONObject()
            .put("type", "http_response")
            .put("id", id)
            .put("status", r.status)
            .put("headers", headers)
            .put("body", r.bodyText())
    }

    private fun describe(t: Throwable, code: Int?): String {
        if (code != null) return "握手失败 HTTP $code"
        return when (t) {
            is java.net.UnknownHostException -> "无法解析主机 $host"
            is java.net.SocketTimeoutException -> "连接超时（${config.connectTimeoutSec} 秒）"
            is java.net.ConnectException -> "无法连接 $host"
            else -> "${t.javaClass.simpleName}: ${t.message?.take(120) ?: ""}"
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val CLOSE_REPLACED = 4000
        const val CLOSE_REJECTED = 4001
        private const val INITIAL_RETRY_MS = 1_000L
        private const val REJECTED_RETRY_MS = 60_000L
        private const val REPLACED_RETRY_MS = 120_000L
        private const val DEBOUNCE_MS = 2_000L
        private const val MAX_PINS = 3

        /** 校验配置，返回错误描述；合法返回 null */
        fun validate(config: RelayConfig): String? {
            val uri = try { URI(config.url.trim()) } catch (_: Exception) { return "中继地址格式错误" }
            if (!uri.scheme.equals("wss", ignoreCase = true)) return "中继地址必须以 wss:// 开头（强制 TLS）"
            if (uri.host.isNullOrEmpty()) return "中继地址缺少主机名"
            if (config.deviceSecret.length < Secrets.MIN_SECRET_LENGTH) return "设备密钥至少 ${Secrets.MIN_SECRET_LENGTH} 个字符"
            if (!config.pin.isNullOrBlank() && parsePins(config.pin) == null) {
                return "证书指纹格式错误（每个应为 sha256/<base64> 或 64 位十六进制，最多 $MAX_PINS 个）"
            }
            if (config.connectTimeoutSec !in 5..60) return "连接超时应在 5–60 秒之间"
            if (config.clientPingIntervalSec != 0 && config.clientPingIntervalSec !in 15..600) {
                return "手机侧心跳间隔应为 0（关闭）或 15–600 秒"
            }
            return null
        }

        /** 解析一个或多个指纹（逗号、空白或换行分隔）；为空返回 null，任一格式错误也返回 null */
        fun parsePins(text: String?): List<ByteArray>? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
            if (parts.isEmpty() || parts.size > MAX_PINS) return null
            val out = parts.map { parsePin(it) ?: return null }
            return out
        }

        /** 解析 SPKI SHA-256 指纹：sha256/<base64> 或 64 位十六进制（可带冒号） */
        fun parsePin(pin: String): ByteArray? {
            val p = pin.trim()
            return try {
                if (p.startsWith("sha256/", ignoreCase = true)) {
                    Base64.getDecoder().decode(p.substring(7)).takeIf { it.size == 32 }
                } else {
                    val hex = p.replace(":", "").lowercase()
                    if (hex.length != 64 || hex.any { it !in "0123456789abcdef" }) null
                    else ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** 计算证书公钥指纹（sha256/<base64>），与中继输出的格式一致 */
        fun spkiPin(cert: X509Certificate): String = pinString(
            MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

        private fun pinString(digest: ByteArray) = "sha256/" + Base64.getEncoder().encodeToString(digest)

        private fun buildClient(config: RelayConfig, tm: PinnedTrustManager?): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutSec.toLong(), TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(config.clientPingIntervalSec.toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
            if (tm != null) {
                // 证书固定：只信任公钥指纹匹配的证书（适用于自签证书），身份由指纹保证，因此不再校验主机名
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf(tm), null)
                b.sslSocketFactory(ctx.socketFactory, tm)
                b.hostnameVerifier { _, _ -> true }
            }
            return b.build()
        }
    }

    /** 只接受叶子证书公钥指纹在列表中的证书链；不匹配时记录服务器出示的指纹 */
    private class PinnedTrustManager(private val pins: List<ByteArray>) : X509TrustManager {
        @Volatile
        private var mismatch: String? = null

        fun takeMismatch(): String? = mismatch.also { mismatch = null }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates not supported")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("中继未提供证书")
            leaf.checkValidity()
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            if (pins.none { MessageDigest.isEqual(actual, it) }) {
                mismatch = pinString(actual)
                throw CertificateException("中继证书指纹不匹配")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
