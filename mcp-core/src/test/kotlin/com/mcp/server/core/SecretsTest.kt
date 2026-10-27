package com.mcp.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsTest {
    @Test
    fun tokensAreLongAndUnique() {
        val a = Secrets.newToken()
        assertEquals(43, a.length)
        assertNotEquals(a, Secrets.newToken())
    }

    @Test
    fun bearerParsing() {
        assertEquals("abc", Secrets.bearer("Bearer abc"))
        assertEquals("abc", Secrets.bearer("bearer  abc "))
        assertNull(Secrets.bearer("Basic abc"))
        assertNull(Secrets.bearer("Bearer "))
        assertNull(Secrets.bearer(null))
    }

    @Test
    fun checkBearerRejectsShortExpected() {
        assertFalse(Secrets.checkBearer("Bearer short", "short"))
        assertTrue(Secrets.checkBearer("Bearer 0123456789abcdef", "0123456789abcdef"))
        assertFalse(Secrets.checkBearer("Bearer 0123456789abcdeF", "0123456789abcdef"))
    }

    @Test
    fun redactorMasksSecrets() {
        val r = Redactor { listOf("supersecretvalue") }
        assertEquals("token=[REDACTED]", r.redact("token=supersecretvalue"))
    }

    @Test
    fun pinParsing() {
        val hex = "ab".repeat(32)
        assertEquals(32, RelayClient.parsePin(hex)!!.size)
        assertEquals(32, RelayClient.parsePin(hex.chunked(2).joinToString(":"))!!.size)
        assertEquals(32, RelayClient.parsePin("sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32)))!!.size)
        assertNull(RelayClient.parsePin("sha256/abc"))
        assertNull(RelayClient.parsePin("xyz"))
    }

    @Test
    fun relayConfigValidation() {
        val ok = RelayConfig("wss://relay.example.com:8443/device", "0123456789abcdef")
        assertNull(RelayClient.validate(ok))
        assertTrue(RelayClient.validate(ok.copy(url = "ws://relay.example.com/device"))!!.contains("wss://"))
        assertTrue(RelayClient.validate(ok.copy(url = "https://relay.example.com/device")) != null)
        assertTrue(RelayClient.validate(ok.copy(deviceSecret = "short")) != null)
        assertTrue(RelayClient.validate(ok.copy(pin = "bad")) != null)
    }
}
