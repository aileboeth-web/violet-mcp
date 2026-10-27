package com.mcp.server.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

/** 第二轮加固：监听限制、畸形请求、隐私默认值、计时 */
class HardeningTest {

    private fun reply(o: McpProtocol.Outcome): JSONObject {
        assertTrue(o is McpProtocol.Outcome.Reply)
        return JSONObject((o as McpProtocol.Outcome.Reply).json)
    }

    @Test
    fun localServerRefusesNonLoopbackAddresses() {
        for (host in listOf("0.0.0.0", "192.168.1.10", "10.0.0.2", "::", "example.com", "")) {
            try {
                LocalHttpServer(TestTools.endpoint(), host, 0)
                fail("$host 应被拒绝")
            } catch (_: IllegalArgumentException) {
            }
        }
        for (host in listOf("127.0.0.1", "localhost", "::1")) {
            val s = LocalHttpServer(TestTools.endpoint(), host, 0)
            assertTrue(host, s.start())
            s.stop()
        }
    }

    @Test
    fun deeplyNestedJsonIsRejectedWithoutCrash() {
        val p = TestTools.protocol()
        val deep = "[".repeat(200_000) + "]".repeat(200_000)
        assertEquals(McpProtocol.PARSE_ERROR, reply(p.handleBody(deep)).getJSONObject("error").getInt("code"))
        // 字符串里的括号不计入深度
        val ok = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"app_info","arguments":{"packageName":"${"[".repeat(500)}"}}}"""
        assertTrue(reply(p.handleBody(ok)).has("result"))
        assertTrue(McpProtocol.nestingTooDeep("[".repeat(65), 64))
        assertFalse(McpProtocol.nestingTooDeep("\"\\\"[[[[\"", 1))
    }

    @Test
    fun oversizedBatchAndWrongVersionRejected() {
        val p = TestTools.protocol()
        val batch = JSONArray()
        repeat(McpProtocol.MAX_BATCH + 1) { batch.put(JSONObject().put("jsonrpc", "2.0").put("id", it).put("method", "ping")) }
        assertEquals(McpProtocol.INVALID_REQUEST, reply(p.handleBody(batch.toString())).getJSONObject("error").getInt("code"))
        val v1 = reply(p.handleBody("""{"jsonrpc":"1.0","id":1,"method":"tools/call","params":{"name":"battery"}}"""))
        assertEquals(McpProtocol.INVALID_REQUEST, v1.getJSONObject("error").getInt("code"))
        val missing = reply(p.handleBody("""{"id":1,"method":"tools/list"}"""))
        assertEquals(McpProtocol.INVALID_REQUEST, missing.getJSONObject("error").getInt("code"))
    }

    @Test
    fun malformedBodiesNeverExecuteTools() {
        var calls = 0
        val r = ToolRegistry()
        r.register(ToolDefinition("battery", "x", emptySchema()) { calls++; JSONObject() })
        val p = TestTools.protocol(r)
        val bad = listOf(
            "", "null", "42", "\"tools/call\"", "{", "[{]", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":\"battery\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":[\"battery\"]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":{\"x\":\"battery\"}}}",
            String(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()), StandardCharsets.UTF_8),
        )
        for (b in bad) {
            val o = p.handleBody(b)
            if (o is McpProtocol.Outcome.Reply) assertTrue(b, JSONObject(o.json).has("error") || JSONArray(o.json).length() > 0)
        }
        assertEquals(0, calls)
    }

    @Test
    fun serverTimingHeaderOnMcpResponses() {
        val ep = TestTools.endpoint()
        val req = HttpRequest("POST", "/mcp",
            mapOf("authorization" to "Bearer ${TestTools.TOKEN}", "content-type" to "application/json"),
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"battery"}}""".toByteArray())
        val resp = ep.handle(req)
        assertEquals(200, resp.status)
        assertNotNull(resp.headers["Server-Timing"])
        assertTrue(resp.headers["Server-Timing"]!!.matches(Regex("phone;dur=[0-9.]+, tool;dur=[0-9.]+")))
    }

    @Test
    fun requestObserverGetsNoSecrets() {
        val records = mutableListOf<McpEndpoint.RequestRecord>()
        val ep = McpEndpoint(TestTools.protocol(), { TestTools.TOKEN }, onRequest = { records.add(it) })
        ep.handle(HttpRequest("POST", "/mcp", mapOf("authorization" to "Bearer wrong-token-0123456789"), ByteArray(0)))
        assertEquals(1, records.size)
        assertEquals(401, records[0].status)
        assertFalse(records[0].toString().contains("wrong-token"))
    }

    @Test
    fun privacyDefaults() {
        assertEquals(setOf("battery", "get_foreground_app", "get_screen_state", "screen_info", "health"),
            PrivacyPolicy.DEFAULT_ENABLED)
        for (n in PrivacyPolicy.DEFAULT_ENABLED + PrivacyPolicy.SENSITIVE) assertTrue(n, ReadOnlyPolicy.isAllowed(n))
        for (n in PrivacyPolicy.SENSITIVE) assertFalse("敏感工具 $n 不能默认开启", PrivacyPolicy.defaultEnabled(n))
        // 用默认值构造的注册表：敏感工具既不在列表里，也无法调用
        val r = ToolRegistry { PrivacyPolicy.defaultEnabled(it) }
        for (n in ReadOnlyPolicy.ALLOWED_TOOLS) r.register(ToolDefinition(n, n, emptySchema()) { JSONObject() })
        val listed = r.toListResult().getJSONArray("tools")
        val names = (0 until listed.length()).map { listed.getJSONObject(it).getString("name") }.toSet()
        assertEquals(PrivacyPolicy.DEFAULT_ENABLED, names)
        for (n in PrivacyPolicy.SENSITIVE) assertNull(r.resolve(n))
        val p = TestTools.protocol(r)
        val err = reply(p.handleBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_app_usage_stats"}}"""))
        assertEquals(McpProtocol.INVALID_PARAMS, err.getJSONObject("error").getInt("code"))
    }

    @Test
    fun multiplePinsParsing() {
        val a = "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val b = "ab".repeat(32)
        assertEquals(2, RelayClient.parsePins("$a,$b")!!.size)
        assertEquals(2, RelayClient.parsePins("$a\n$b")!!.size)
        assertNull(RelayClient.parsePins("$a,$a,$a,$a"))
        assertNull(RelayClient.parsePins("$a,bad"))
        assertNull(RelayClient.validate(RelayConfig("wss://h/device", "0123456789abcdef", "$a,$b")))
        assertNotNull(RelayClient.validate(RelayConfig("wss://h/device", "0123456789abcdef", connectTimeoutSec = 1)))
        assertNotNull(RelayClient.validate(RelayConfig("wss://h/device", "0123456789abcdef", clientPingIntervalSec = 5)))
    }
}
