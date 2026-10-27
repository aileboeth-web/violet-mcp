package com.mcp.server.server

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 对单个字符串加解密（便于在测试中替换） */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(blob: String): String
}

/**
 * Android Keystore 中的 AES-256-GCM 密钥：密钥材料不出 Keystore、不可导出。
 * 不要求用户解锁（服务需要在锁屏时校验令牌）；手机重启后首次解锁前应用不会运行，凭据也不会被读取。
 * minSdk 26 均支持 AndroidKeyStore 的 AES/GCM；个别厂商实现有缺陷时由 [SecretStore] 走兼容模式。
 */
class KeystoreCipher(private val alias: String = "violet_mcp_secrets_v1") : SecretCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return "v1:" + b64.encodeToString(c.iv) + ":" + b64.encodeToString(ct)
    }

    override fun decrypt(blob: String): String {
        val parts = blob.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "unknown format" }
        val b64 = Base64.getDecoder()
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b64.decode(parts[1])))
        return String(c.doFinal(b64.decode(parts[2])), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** 构造并自检；Keystore 不可用时返回 null */
        fun createOrNull(): SecretCipher? = try {
            val c = KeystoreCipher()
            check(c.decrypt(c.encrypt("probe")) == "probe")
            c
        } catch (_: Throwable) {
            null
        }
    }
}

/** Keystore 不可用且用户没有同意兼容模式时，拒绝写入明文 */
class SecretStorageUnavailableException(message: String) : Exception(message)

/**
 * 长期凭据（MCP 令牌、中继设备密钥）的存储。**失败时一律拒绝（fail closed）**：
 *
 * - 正常情况：值用 [SecretCipher]（Android Keystore AES-GCM）加密后存为 `<name>.enc`。
 * - 从旧版明文迁移：先加密写入并验证能解出原值，写入成功后才删除明文。任何一步失败都**保留**明文（不丢配置），
 *   但在用户明确同意兼容模式之前，这份明文**不能用于认证**（返回 [Read.NeedsConsent]）。
 * - 密文存在时**只认密文**：解密失败或 Keystore 不可用时返回 [Read.Unreadable]，绝不改用残留的明文副本，
 *   也不自动生成新值；用户需要在安全页重新生成。迁移中断留下的明文副本在密文成功解密后删除。
 * - 写入时 Keystore 不可用或加密失败：只有 [plaintextAllowed]（用户在安全页明确同意兼容模式）为真时才写明文，
 *   否则抛出 [SecretStorageUnavailableException]。
 */
class SecretStore(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher?,
    private val plaintextAllowed: () -> Boolean = { false },
) {

    enum class Mode { ENCRYPTED, PLAINTEXT_FALLBACK }

    sealed class Read {
        data class Value(val value: String, val mode: Mode) : Read()
        object Missing : Read()

        /** 已保存的密文无法解密：需要用户重新生成 / 重新填写 */
        data class Unreadable(val reason: String) : Read()

        /** 只有明文副本（旧版遗留或迁移失败），用户尚未同意兼容模式：不可用于认证 */
        data class NeedsConsent(val reason: String) : Read()
    }

    @Volatile
    var lastError: String? = null
        private set

    val keystoreAvailable: Boolean get() = cipher != null

    fun read(name: String): Read {
        val enc = prefs.getString("$name.enc", null)
        val plain = prefs.getString(name, null)
        if (enc != null) {
            val c = cipher ?: return Read.Unreadable("本机 Android Keystore 不可用，无法解密已保存的凭据")
                .also { lastError = it.reason }
            return try {
                val v = c.decrypt(enc)
                // 迁移中断留下的明文副本：以密文为准，删除明文
                if (plain != null) prefs.edit().remove(name).commit()
                Read.Value(v, Mode.ENCRYPTED)
            } catch (e: Exception) {
                // 不改用明文副本：残留的明文可能是旧凭据
                Read.Unreadable("凭据解密失败（${e.javaClass.simpleName}）").also { lastError = it.reason }
            }
        }
        if (plain != null) {
            if (migrate(name, plain)) return Read.Value(plain, Mode.ENCRYPTED)
            return if (plaintextAllowed()) Read.Value(plain, Mode.PLAINTEXT_FALLBACK)
            else Read.NeedsConsent(lastError ?: "凭据未加密，需要先迁移到加密存储")
        }
        return Read.Missing
    }

    /** 迁移成功返回 true；失败保留明文、返回 false */
    private fun migrate(name: String, plain: String): Boolean {
        val c = cipher
        if (c == null) {
            lastError = "本机 Android Keystore 不可用，无法把旧版明文凭据迁移到加密存储"
            return false
        }
        return try {
            val blob = c.encrypt(plain)
            check(c.decrypt(blob) == plain) { "verify mismatch" }
            check(prefs.edit().putString("$name.enc", blob).commit()) { "write failed" }
            prefs.edit().remove(name).commit()
            true
        } catch (e: Exception) {
            lastError = "迁移到加密存储失败，已保留原值（${e.javaClass.simpleName}）"
            false
        }
    }

    /** 写入；返回实际使用的存储方式。不能加密且未同意兼容模式时抛出 [SecretStorageUnavailableException] */
    fun write(name: String, value: String): Mode {
        val c = cipher
        if (c != null) {
            try {
                val blob = c.encrypt(value)
                check(c.decrypt(blob) == value) { "verify mismatch" }
                check(prefs.edit().putString("$name.enc", blob).remove(name).commit()) { "write failed" }
                lastError = null
                return Mode.ENCRYPTED
            } catch (e: Exception) {
                lastError = "加密写入失败（${e.javaClass.simpleName}）"
            }
        } else {
            lastError = "本机 Android Keystore 不可用"
        }
        if (!plaintextAllowed()) {
            throw SecretStorageUnavailableException("$lastError，且未开启兼容模式，拒绝以明文保存凭据")
        }
        check(prefs.edit().putString(name, value).remove("$name.enc").commit()) { "write failed" }
        return Mode.PLAINTEXT_FALLBACK
    }

    fun delete(name: String) {
        prefs.edit().remove(name).remove("$name.enc").commit()
    }

    /** 删除所有明文副本（用户关闭兼容模式时调用） */
    fun deletePlaintext(vararg names: String) {
        val e = prefs.edit()
        for (n in names) e.remove(n)
        e.commit()
    }

    /** 当前存储方式；不存在返回 null */
    fun mode(name: String): Mode? = when {
        prefs.contains("$name.enc") -> Mode.ENCRYPTED
        prefs.contains(name) -> Mode.PLAINTEXT_FALLBACK
        else -> null
    }
}
