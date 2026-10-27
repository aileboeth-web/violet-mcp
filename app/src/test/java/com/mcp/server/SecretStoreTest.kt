package com.mcp.server

import android.content.Context
import android.content.SharedPreferences
import com.mcp.server.core.HttpRequest
import com.mcp.server.core.McpEndpoint
import com.mcp.server.server.SecretCipher
import com.mcp.server.server.SecretStorageUnavailableException
import com.mcp.server.server.SecretStore
import com.mcp.server.server.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 【模拟环境】凭据存储：失败时一律拒绝（fail closed），只有用户明确同意兼容模式才使用明文。
 * Robolectric 没有 AndroidKeyStore，这里用可控的假加密器；真机上的 Keystore 行为需要在设备上验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretStoreTest {
    private lateinit var prefs: SharedPreferences
    private var consent = false

    /** 可逆的假加密：前缀 + 反转；可设置成加密或解密时抛异常 */
    class FakeCipher(var failEncrypt: Boolean = false, var failDecrypt: Boolean = false) : SecretCipher {
        override fun encrypt(plain: String): String {
            if (failEncrypt) throw IllegalStateException("keystore broken")
            return "fake:" + plain.reversed()
        }

        override fun decrypt(blob: String): String {
            if (failDecrypt) throw IllegalStateException("key lost")
            require(blob.startsWith("fake:"))
            return blob.removePrefix("fake:").reversed()
        }
    }

    private fun store(cipher: SecretCipher?) = SecretStore(prefs, cipher) { consent }

    @Before
    fun setUp() {
        prefs = TestSupport.app().getSharedPreferences("secret_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        consent = false
    }

    // ---------- 迁移 ----------

    @Test
    fun migratesPlaintextAndKeepsValue() {
        prefs.edit().putString("token", "legacy-token-0123456789").commit()
        val s = store(FakeCipher())
        assertEquals(SecretStore.Read.Value("legacy-token-0123456789", SecretStore.Mode.ENCRYPTED), s.read("token"))
        assertFalse("迁移后不应再有明文", prefs.contains("token"))
        assertTrue(prefs.getString("token.enc", "")!!.startsWith("fake:"))
    }

    @Test
    fun failedMigrationKeepsPlaintextButRefusesItWithoutConsent() {
        prefs.edit().putString("token", "legacy-token-0123456789").commit()
        val s = store(FakeCipher(failEncrypt = true))
        assertTrue("未经同意不能用明文认证", s.read("token") is SecretStore.Read.NeedsConsent)
        assertTrue("迁移失败不得清空配置", prefs.contains("token"))
        assertFalse(prefs.contains("token.enc"))
        assertNotNull(s.lastError)
        consent = true
        assertEquals(SecretStore.Read.Value("legacy-token-0123456789", SecretStore.Mode.PLAINTEXT_FALLBACK), s.read("token"))
    }

    @Test
    fun legacyPlaintextWithoutKeystoreNeedsConsent() {
        prefs.edit().putString("token", "legacy-token-0123456789").commit()
        assertTrue(store(null).read("token") is SecretStore.Read.NeedsConsent)
        assertTrue(prefs.contains("token"))
    }

    @Test
    fun interruptedMigrationIsCompleted() {
        val v = "same-token-0123456789"
        prefs.edit().putString("token", v).putString("token.enc", "fake:" + v.reversed()).commit()
        assertEquals(SecretStore.Read.Value(v, SecretStore.Mode.ENCRYPTED), store(FakeCipher()).read("token"))
        assertFalse("密文解密成功后删除残留明文", prefs.contains("token"))
    }

    // ---------- 残留明文副本不能被接受 ----------

    @Test
    fun decryptFailureNeverFallsBackToResidualPlaintext() {
        prefs.edit().putString("token", "old-plain-token-0123456789")
            .putString("token.enc", "fake:" + "new-token-0123456789abc".reversed()).commit()
        consent = true // 即使同意了兼容模式，也不能用残留明文顶替无法解密的密文
        val r = store(FakeCipher(failDecrypt = true)).read("token")
        assertTrue(r is SecretStore.Read.Unreadable)
        assertTrue("密文和明文都保留，供用户处理", prefs.contains("token.enc") && prefs.contains("token"))
    }

    @Test
    fun keystoreGoneNeverFallsBackToResidualPlaintext() {
        prefs.edit().putString("token", "old-plain-token-0123456789")
            .putString("token.enc", "fake:whatever").commit()
        consent = true
        assertTrue(store(null).read("token") is SecretStore.Read.Unreadable)
    }

    @Test
    fun differingResidualPlaintextIsDiscardedWhenCiphertextIsGood() {
        prefs.edit().putString("token", "old-plain-token-0123456789")
            .putString("token.enc", "fake:" + "new-token-0123456789abc".reversed()).commit()
        assertEquals("new-token-0123456789abc", (store(FakeCipher()).read("token") as SecretStore.Read.Value).value)
        assertFalse(prefs.contains("token"))
    }

    // ---------- 写入 ----------

    @Test
    fun writeRefusesPlaintextWithoutConsent() {
        for (cipher in listOf(null, FakeCipher(failEncrypt = true))) {
            try {
                store(cipher).write("token", "new-token-0123456789")
                fail("未经同意不能写明文")
            } catch (_: SecretStorageUnavailableException) {
            }
            assertFalse(prefs.contains("token"))
        }
        consent = true
        assertEquals(SecretStore.Mode.PLAINTEXT_FALLBACK, store(null).write("token", "new-token-0123456789"))
    }

    @Test
    fun writeReplacesBothCopiesAtomically() {
        prefs.edit().putString("token", "old-plain-token-0123456789").commit()
        store(FakeCipher()).write("token", "rotated-token-0123456789")
        assertFalse(prefs.contains("token"))
        assertEquals("rotated-token-0123456789", (store(FakeCipher()).read("token") as SecretStore.Read.Value).value)
    }

    // ---------- Settings 层（端到端：令牌是否被接受） ----------

    private fun endpointAccepts(token: String): Boolean {
        val ep = McpEndpoint(TestSupport.protocol(), { Settings.token(TestSupport.app()) })
        val r = ep.handle(HttpRequest("POST", "/mcp",
            mapOf("authorization" to "Bearer $token", "content-type" to "application/json"),
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray()))
        return r.status == 200
    }

    private fun freshSettings(cipher: SecretCipher?): Context {
        val app = TestSupport.fresh()
        Settings.cipherFactory = { cipher }
        return app
    }

    @Test
    fun settingsUpgradeKeepsTokenWhenKeystoreWorks() {
        val app = freshSettings(FakeCipher())
        Settings.prefs(app).edit().putString("token", "token-from-1.1.0-abcdefghijkl").commit()
        assertEquals("token-from-1.1.0-abcdefghijkl", Settings.token(app))
        assertTrue(endpointAccepts("token-from-1.1.0-abcdefghijkl"))
        assertEquals(Settings.CredentialStorage.ENCRYPTED, Settings.credentialStorage(app))
    }

    @Test
    fun settingsWithoutKeystoreRejectsLegacyTokenUntilConsent() {
        val app = freshSettings(null)
        Settings.prefs(app).edit().putString("token", "token-from-1.1.0-abcdefghijkl").commit()
        assertEquals("", Settings.token(app))
        assertFalse("未同意兼容模式前，旧明文令牌不能通过认证", endpointAccepts("token-from-1.1.0-abcdefghijkl"))
        assertEquals(Settings.CredentialStorage.NEEDS_CONSENT, Settings.credentialStorage(app))
        assertTrue("旧令牌仍保留在本机", Settings.prefs(app).contains("token"))

        Settings.setPlaintextConsent(app, true)
        assertTrue(endpointAccepts("token-from-1.1.0-abcdefghijkl"))
        assertEquals(Settings.CredentialStorage.PLAINTEXT_CONSENTED, Settings.credentialStorage(app))

        Settings.setPlaintextConsent(app, false) // 撤回同意：明文被删除，令牌立即失效
        assertFalse(Settings.prefs(app).contains("token"))
        assertFalse(endpointAccepts("token-from-1.1.0-abcdefghijkl"))
    }

    @Test
    fun settingsWithoutKeystoreNeverGeneratesPlaintextTokenSilently() {
        val app = freshSettings(null)
        assertEquals("", Settings.token(app))
        assertFalse(Settings.prefs(app).contains("token"))
        try {
            Settings.regenerateToken(app)
            fail()
        } catch (_: SecretStorageUnavailableException) {
        }
        try {
            Settings.saveRelay(app, "wss://relay.example/device", "device-secret-0123456789", "")
            fail()
        } catch (_: SecretStorageUnavailableException) {
        }
        assertEquals("保存失败时不应留下半份配置", "", Settings.relayUrl(app))
    }

    @Test
    fun undecryptableTokenRejectsOldAndResidualCopies() {
        val cipher = FakeCipher()
        val app = freshSettings(cipher)
        val good = Settings.token(app)
        Settings.prefs(app).edit().putString("token", "residual-plain-0123456789").commit()
        cipher.failDecrypt = true
        Settings.resetForTest()
        Settings.cipherFactory = { cipher }
        assertEquals("", Settings.token(app))
        assertFalse(endpointAccepts(good))
        assertFalse(endpointAccepts("residual-plain-0123456789"))
        assertEquals(Settings.CredentialStorage.UNREADABLE, Settings.credentialStorage(app))
    }

    @Test
    fun rotatedTokenInvalidatesOldImmediately() {
        val app = freshSettings(FakeCipher())
        val old = Settings.token(app)
        val new = Settings.regenerateToken(app)
        assertFalse(endpointAccepts(old))
        assertTrue(endpointAccepts(new))
    }

    @Test
    fun clearAllRegeneratesTokenAndRemovesRelayCredentials() {
        val app = freshSettings(FakeCipher())
        val old = Settings.token(app)
        Settings.saveRelay(app, "wss://relay.example/device", "device-secret-0123456789", "")
        Settings.setRelayEnabled(app, true)
        Settings.clearAllCredentials(app)
        assertFalse(Settings.relayEnabled(app))
        assertEquals("", Settings.relaySecret(app))
        assertEquals("", Settings.relayUrl(app))
        val new = Settings.token(app)
        assertTrue(new.length >= 32 && new != old)
        assertFalse(endpointAccepts(old))
    }

    @Test
    fun revokeKeepsTokenButDropsDeviceSecret() {
        val app = freshSettings(FakeCipher())
        val token = Settings.token(app)
        Settings.saveRelay(app, "wss://relay.example/device", "device-secret-0123456789", "sha256/x")
        Settings.revokeRelayCredentials(app)
        assertEquals("", Settings.relaySecret(app))
        assertEquals("", Settings.relayPin(app))
        assertEquals(token, Settings.token(app))
    }
}
