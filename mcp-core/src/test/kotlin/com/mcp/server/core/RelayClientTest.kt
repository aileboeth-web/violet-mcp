package com.mcp.server.core

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 用 MockWebServer（TLS + WebSocket）模拟 VPS 中继，验证证书固定、请求转发、心跳和各种重连策略 */
class RelayClientTest {
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val other = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private lateinit var server: MockWebServer
    private var client: RelayClient? = null

    /** 中继侧收到的帧 */
    private val frames = LinkedBlockingQueue<JSONObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val upgrades = AtomicInteger()

    /** 中继回给手机的 hello_ack（可在测试里改） */
    @Volatile
    private var ack = """{"type":"hello_ack"}"""

    private val relaySide = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            upgrades.incrementAndGet()
            sockets.add(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val f = JSONObject(text)
            frames.add(f)
            if (f.optString("type") == "hello") webSocket.send(ack)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() {
        client?.stop()
        server.shutdown()
    }

    private fun url() = "wss://localhost:${server.port}/device"
    private fun heldPin() = RelayClient.spkiPin(held.certificate)

    private fun newClient(pin: String?, minWatchdogMs: Long = 45_000L, start: Boolean = true): RelayClient {
        val cfg = RelayConfig(url(), "device-secret-0123456789", pin, "test-phone", "9.9")
        return RelayClient(cfg, TestTools.endpoint(), { TestTools.TOKEN }, minWatchdogMs = minWatchdogMs)
            .also { client = it; if (start) it.start() }
    }

    private fun nextFrame(type: String, timeoutSec: Long = 10): JSONObject {
        while (true) {
            val f = frames.poll(timeoutSec, TimeUnit.SECONDS) ?: throw AssertionError("没有收到 $type 帧")
            if (f.optString("type") == type) return f
        }
    }

    private fun awaitPhase(c: RelayClient, phase: RelayClient.Phase, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (c.state.phase == phase) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun sendRequest(ws: WebSocket, id: String, auth: String?, body: String) {
        val headers = JSONObject().put("content-type", "application/json")
        if (auth != null) headers.put("authorization", auth)
        ws.send(JSONObject().put("type", "http_request").put("id", id).put("method", "POST")
            .put("path", "/mcp").put("headers", headers).put("body", body).toString())
    }

    @Test
    fun pinnedConnectionForwardsRequests() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(heldPin())

        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("Bearer device-secret-0123456789", recorded.getHeader("Authorization"))
        val hello = nextFrame("hello")
        assertEquals(Secrets.sha256Hex(TestTools.TOKEN), hello.getString("mcpTokenSha256"))
        assertFalse(hello.toString().contains(TestTools.TOKEN))
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        assertTrue(c.state.lastConnectedAt > 0)

        val ws = sockets.poll(5, TimeUnit.SECONDS)!!
        sendRequest(ws, "r1", "Bearer ${TestTools.TOKEN}", """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"battery"}}""")
        val ok = nextFrame("http_response")
        assertEquals("r1", ok.getString("id"))
        assertEquals(200, ok.getInt("status"))
        assertEquals(87, JSONObject(ok.getString("body")).getJSONObject("result")
            .getJSONObject("structuredContent").getInt("percent"))
        // 分阶段计时随响应头回传给中继
        assertTrue(ok.getJSONObject("headers").getString("Server-Timing").matches(Regex("phone;dur=[0-9.]+, tool;dur=[0-9.]+")))

        // 中继之后仍由手机再做一次鉴权
        sendRequest(ws, "r2", null, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals(401, nextFrame("http_response").getInt("status"))

        sendRequest(ws, "r3", "Bearer ${TestTools.TOKEN}", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"shell","arguments":{"command":"reboot"}}}""")
        val denied = JSONObject(nextFrame("http_response").getString("body"))
        assertEquals(-32602, denied.getJSONObject("error").getInt("code"))
    }

    @Test
    fun concurrentRequestsAreAllAnswered() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(heldPin())
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        val ws = sockets.poll(5, TimeUnit.SECONDS)!!
        val n = 40
        repeat(n) { i ->
            sendRequest(ws, "c$i", "Bearer ${TestTools.TOKEN}",
                """{"jsonrpc":"2.0","id":$i,"method":"tools/call","params":{"name":"time_now"}}""")
        }
        val ids = HashSet<String>()
        repeat(n) {
            val f = nextFrame("http_response")
            assertEquals(200, f.getInt("status"))
            val body = JSONObject(f.getString("body"))
            // 响应 id 与请求一一对应，不会串
            assertEquals(f.getString("id").removePrefix("c").toInt(), body.getInt("id"))
            ids.add(f.getString("id"))
        }
        assertEquals(n, ids.size)
    }

    @Test
    fun appLevelPingIsAnswered() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(heldPin())
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        sockets.poll(5, TimeUnit.SECONDS)!!.send("""{"type":"ping","t":12345}""")
        assertEquals(12345, nextFrame("pong").getInt("t"))
    }

    @Test
    fun watchdogReconnectsWhenRelayGoesSilent() {
        ack = """{"type":"hello_ack","pingIntervalMs":200}"""
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(heldPin(), minWatchdogMs = 600)
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        // 中继不发任何心跳：约 0.6 秒后看门狗断开并重连
        assertNotNull(nextFrame("hello", timeoutSec = 15))
        assertTrue(c.state.reconnects >= 1)
    }

    @Test
    fun wrongPinIsRejectedAndPresentedPinReported() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(RelayClient.spkiPin(other.certificate))
        assertTrue(awaitPhase(c, RelayClient.Phase.REJECTED))
        assertTrue(c.state.message, c.state.message.contains("指纹不匹配"))
        assertEquals(heldPin(), c.state.presentedPin)
        // 安全失败进入长退避（≥ 48 秒），不会快速重试
        assertTrue(c.state.nextRetryAt - System.currentTimeMillis() > 40_000)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun secondPinAllowsCertificateRotation() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(RelayClient.spkiPin(other.certificate) + "\n" + heldPin())
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
    }

    @Test
    fun untrustedSelfSignedWithoutPinIsRejected() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(null)
        assertTrue(awaitPhase(c, RelayClient.Phase.REJECTED))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun deviceSecretRejectionIsReported() {
        server.enqueue(MockResponse().setResponseCode(401))
        val c = newClient(heldPin())
        assertTrue(awaitPhase(c, RelayClient.Phase.REJECTED))
        assertTrue(c.state.lastFailureReason.contains("设备密钥"))
    }

    @Test
    fun replacedConnectionBacksOffLong() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(heldPin())
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        sockets.poll(5, TimeUnit.SECONDS)!!.close(RelayClient.CLOSE_REPLACED, "replaced")
        assertTrue(awaitPhase(c, RelayClient.Phase.REPLACED))
        assertTrue(c.state.nextRetryAt - System.currentTimeMillis() > 90_000)
    }

    @Test
    fun reconnectsAfterRelayDropsConnection() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(heldPin())
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        sockets.poll(5, TimeUnit.SECONDS)!!.close(1001, "relay restart")
        assertNotNull(nextFrame("hello"))
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        assertTrue(c.state.reconnects >= 1)
    }

    @Test
    fun reconnectNowIsDebounced() {
        repeat(5) { server.enqueue(MockResponse().withWebSocketUpgrade(relaySide)) }
        val c = newClient(heldPin())
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        // 网络抖动：短时间内连续 10 次立即重连
        repeat(10) { c.reconnectNow("网络切换（测试）") }
        Thread.sleep(3_500)
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
        // 去抖后最多再建立 1–2 条连接，而不是 10 条
        assertTrue("upgrades=${upgrades.get()}", upgrades.get() in 2..3)
    }

    @Test
    fun noNetworkPausesRetriesUntilAvailable() {
        server.enqueue(MockResponse().withWebSocketUpgrade(relaySide))
        val c = newClient(heldPin(), start = false)
        c.setNetworkAvailable(false)
        c.start()
        assertTrue(awaitPhase(c, RelayClient.Phase.WAITING_NETWORK))
        Thread.sleep(1_500)
        assertEquals("无网络时不应发起连接", 0, server.requestCount)
        c.setNetworkAvailable(true)
        nextFrame("hello")
        assertTrue(awaitPhase(c, RelayClient.Phase.CONNECTED))
    }
}
