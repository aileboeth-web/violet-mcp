package com.mcp.server.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/** 通过真实 socket 测试本地 HTTP 端点：鉴权、Origin、路由、按字节读请求体、限流 */
class HttpServerTest {
    private lateinit var server: LocalHttpServer

    @Before
    fun setUp() {
        server = LocalHttpServer(TestTools.endpoint(), "127.0.0.1", 0)
        assertTrue(server.start())
    }

    @After
    fun tearDown() = server.stop()

    private class Resp(val status: Int, val headers: Map<String, String>, val body: String)

    private fun raw(request: ByteArray): Resp {
        Socket("127.0.0.1", server.boundPort).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(request)
            s.getOutputStream().flush()
            val out = ByteArrayOutputStream()
            s.getInputStream().copyTo(out)
            val text = String(out.toByteArray(), StandardCharsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val status = lines[0].split(' ')[1].toInt()
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Resp(status, headers, text.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun request(
        method: String, path: String, body: String? = null,
        token: String? = TestTools.TOKEN, extra: Map<String, String> = emptyMap(),
    ): Resp {
        val bytes = body?.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: localhost\r\n")
        if (token != null) sb.append("Authorization: Bearer $token\r\n")
        if (bytes != null) sb.append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        return raw(sb.toString().toByteArray(StandardCharsets.UTF_8) + (bytes ?: ByteArray(0)))
    }

    private val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"中文客户端","version":"1"}}}"""

    @Test
    fun healthIsPublicButMinimal() {
        val r = request("GET", "/health", token = null)
        assertEquals(200, r.status)
        assertEquals("""{"ok":true}""", r.body)
    }

    @Test
    fun mcpRequiresValidBearer() {
        assertEquals(401, request("POST", "/mcp", init, token = null).status)
        assertEquals(401, request("POST", "/mcp", init, token = "wrong-token-0123456789").status)
        assertEquals(401, request("POST", "/mcp", init, token = TestTools.TOKEN + "x").status)
        assertEquals(401, request("POST", "/mcp", init, token = null,
            extra = mapOf("Authorization" to "Basic ${TestTools.TOKEN}")).status)
        val ok = request("POST", "/mcp", init)
        assertEquals(200, ok.status)
        assertTrue(ok.headers["content-type"]!!.startsWith("application/json"))
    }

    @Test
    fun statusAndToolListingRequireAuth() {
        assertEquals(401, request("GET", "/status", token = null).status)
        assertEquals(200, request("GET", "/status").status)
        // 原项目的未鉴权端点已经不存在
        assertEquals(404, request("GET", "/tools", token = null).status)
        assertEquals(404, request("GET", "/info", token = null).status)
        assertEquals(404, request("GET", "/sse", token = null).status)
        assertEquals(404, request("GET", "/", token = null).status)
        assertFalse(request("GET", "/status").body.contains(TestTools.TOKEN))
    }

    @Test
    fun browserOriginRejected() {
        assertEquals(403, request("POST", "/mcp", init, extra = mapOf("Origin" to "http://evil.example")).status)
    }

    @Test
    fun getAndDeleteOnMcpAre405() {
        val g = request("GET", "/mcp", extra = mapOf("Accept" to "text/event-stream"))
        assertEquals(405, g.status)
        assertEquals("POST", g.headers["allow"])
        assertEquals(405, request("DELETE", "/mcp").status)
    }

    @Test
    fun notificationGets202() {
        val r = request("POST", "/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, r.status)
        assertEquals("", r.body)
    }

    @Test
    fun utf8BodyIsReadByBytes() {
        val r = request("POST", "/mcp", """{"jsonrpc":"2.0","id":"中文-id","method":"tools/call","params":{"name":"app_info","arguments":{"packageName":"包名测试"}}}""")
        assertEquals(200, r.status)
        val result = JSONObject(r.body).getJSONObject("result")
        assertEquals("包名测试", result.getJSONObject("structuredContent").getString("packageName"))
    }

    @Test
    fun chunkedBodySupported() {
        val body = """{"jsonrpc":"2.0","id":3,"method":"ping"}""".toByteArray()
        val head = "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${TestTools.TOKEN}\r\n" +
            "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
        val chunked = head.toByteArray() +
            "${Integer.toHexString(10)}\r\n".toByteArray() + body.copyOfRange(0, 10) + "\r\n".toByteArray() +
            "${Integer.toHexString(body.size - 10)}\r\n".toByteArray() + body.copyOfRange(10, body.size) + "\r\n".toByteArray() +
            "0\r\n\r\n".toByteArray()
        val r = raw(chunked)
        assertEquals(200, r.status)
        assertEquals(3, JSONObject(r.body).getInt("id"))
    }

    @Test
    fun oversizedBodyRejected() {
        val head = "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${TestTools.TOKEN}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${LocalHttpServer.MAX_BODY_BYTES + 1}\r\n\r\n"
        assertEquals(413, raw(head.toByteArray()).status)
    }

    @Test
    fun oversizedBodyActuallySentStillGets413() {
        // 客户端真的把 1 MiB+ 的请求体发完：服务器提前返回 413 后要读掉剩余数据，客户端才能收到响应
        repeat(5) {
            Socket("127.0.0.1", server.boundPort).use { s ->
                s.soTimeout = 10_000
                val size = LocalHttpServer.MAX_BODY_BYTES + 10
                val head = "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${TestTools.TOKEN}\r\n" +
                    "Content-Type: application/json\r\nContent-Length: $size\r\n\r\n"
                val writer = Thread {
                    try {
                        s.getOutputStream().write(head.toByteArray())
                        s.getOutputStream().write(ByteArray(size) { 'x'.code.toByte() })
                        s.getOutputStream().flush()
                    } catch (_: Exception) {
                    }
                }.apply { start() }
                val text = s.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
                writer.join(5_000)
                assertTrue(text.take(40), text.startsWith("HTTP/1.1 413"))
            }
        }
    }

    @Test
    fun unauthorizedWithLargeBodyStillGets401() {
        // 未读请求体时直接关闭会触发 RST；服务器应先半关闭再读掉剩余数据
        val big = "x".repeat(200_000)
        assertEquals(401, request("POST", "/mcp", big, token = null).status)
    }

    @Test
    fun wrongContentTypeAndProtocolVersion() {
        val head = "POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${TestTools.TOKEN}\r\n" +
            "Content-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}"
        assertEquals(415, raw(head.toByteArray()).status)
        assertEquals(400, request("POST", "/mcp", init, extra = mapOf("MCP-Protocol-Version" to "1999-01-01")).status)
        assertEquals(200, request("POST", "/mcp", init, extra = mapOf("MCP-Protocol-Version" to "2025-06-18")).status)
    }

    @Test
    fun removedToolCallOverHttpFails() {
        val r = request("POST", "/mcp", """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"shell","arguments":{"command":"id"}}}""")
        assertEquals(200, r.status)
        assertEquals(-32602, JSONObject(r.body).getJSONObject("error").getInt("code"))
    }

    @Test
    fun emptyTokenNeverAuthorizes() {
        val s = LocalHttpServer(TestTools.endpoint(token = ""), "127.0.0.1", 0)
        assertTrue(s.start())
        try {
            Socket("127.0.0.1", s.boundPort).use { sock ->
                sock.getOutputStream().write(
                    "POST /mcp HTTP/1.1\r\nAuthorization: Bearer \r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                val text = sock.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(text, text.startsWith("HTTP/1.1 401"))
            }
        } finally {
            s.stop()
        }
    }
}
