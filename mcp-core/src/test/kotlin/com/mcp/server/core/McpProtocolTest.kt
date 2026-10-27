package com.mcp.server.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    private val p = TestTools.protocol()

    private fun reply(body: String): JSONObject {
        val o = p.handleBody(body)
        assertTrue("expected reply", o is McpProtocol.Outcome.Reply)
        return JSONObject((o as McpProtocol.Outcome.Reply).json)
    }

    private fun call(name: String, args: JSONObject = JSONObject(), id: Any = 7): JSONObject =
        reply(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args)).toString())

    private fun init(v: String): JSONObject = reply(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$v","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
    ).getJSONObject("result")

    @Test
    fun initializeNegotiatesVersion() {
        assertEquals("2025-06-18", init("2025-06-18").getString("protocolVersion"))
        assertEquals("2024-11-05", init("2024-11-05").getString("protocolVersion"))
        assertEquals(McpProtocol.LATEST_VERSION, init("2099-01-01").getString("protocolVersion"))
        val r = init("2025-11-25")
        assertFalse(r.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"))
        assertEquals("test-server", r.getJSONObject("serverInfo").getString("name"))
    }

    @Test
    fun notificationsAreAccepted() {
        assertTrue(p.handleBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") is McpProtocol.Outcome.Accepted)
        assertTrue(p.handleBody("""[{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}]""") is McpProtocol.Outcome.Accepted)
    }

    @Test
    fun idTypeIsPreserved() {
        assertEquals("abc", reply("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""").getString("id"))
        assertEquals(42, reply("""{"jsonrpc":"2.0","id":42,"method":"ping"}""").getInt("id"))
    }

    @Test
    fun parseAndMethodErrors() {
        assertEquals(McpProtocol.PARSE_ERROR, reply("{not json").getJSONObject("error").getInt("code"))
        assertEquals(McpProtocol.METHOD_NOT_FOUND,
            reply("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}""").getJSONObject("error").getInt("code"))
        assertEquals(McpProtocol.INVALID_REQUEST, reply("[]").getJSONObject("error").getInt("code"))
    }

    @Test
    fun removedToolsCannotBeCalled() {
        for (name in ReadOnlyPolicy.REMOVED_TOOLS + listOf("bridge_tool", "MCP1_shell", "")) {
            val r = call(name, JSONObject().put("command", "rm -rf /sdcard"))
            assertTrue(name, r.has("error"))
            assertEquals(name, McpProtocol.INVALID_PARAMS, r.getJSONObject("error").getInt("code"))
        }
    }

    @Test
    fun disabledToolHasSameErrorAsUnknown() {
        val proto = TestTools.protocol(TestTools.registry { it != "battery" })
        val o = proto.handleBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"battery"}}""")
        val e = JSONObject((o as McpProtocol.Outcome.Reply).json).getJSONObject("error")
        assertEquals("Unknown tool: battery", e.getString("message"))
    }

    @Test
    fun successfulCallHasTextAndStructuredContent() {
        val r = call("battery").getJSONObject("result")
        assertFalse(r.getBoolean("isError"))
        assertEquals(87, r.getJSONObject("structuredContent").getInt("percent"))
        assertEquals("text", r.getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test
    fun validationAndToolErrorsAreToolResults() {
        assertTrue(call("app_info").getJSONObject("result").getBoolean("isError"))
        assertTrue(call("get_app_usage_stats", JSONObject().put("range", "forever"))
            .getJSONObject("result").getBoolean("isError"))
        assertTrue(call("app_info", JSONObject().put("packageName", "missing"))
            .getJSONObject("result").getBoolean("isError"))
        assertTrue(call("health").getJSONObject("result").getBoolean("isError"))
    }

    @Test
    fun nonObjectArgumentsRejected() {
        val r = reply("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"battery","arguments":[1]}}""")
        assertEquals(McpProtocol.INVALID_PARAMS, r.getJSONObject("error").getInt("code"))
    }

    @Test
    fun batchReturnsOnlyRequestResponses() {
        val o = p.handleBody("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"tools/list"}]""")
        val arr = JSONArray((o as McpProtocol.Outcome.Reply).json)
        assertEquals(2, arr.length())
    }
}
