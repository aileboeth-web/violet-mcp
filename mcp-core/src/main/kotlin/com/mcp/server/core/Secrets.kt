package com.mcp.server.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 令牌生成、哈希与常量时间比较 */
object Secrets {
    private val random = SecureRandom()

    /** 生成 base64url 随机令牌（默认 32 字节 = 256 bit） */
    fun newToken(bytes: Int = 32): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))

    fun sha256Hex(s: String): String = sha256(s).joinToString("") { "%02x".format(it) }

    /** 常量时间比较：先哈希成定长再比较，避免长度和逐字节比较泄露信息 */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(sha256(a), sha256(b))

    /** 从 Authorization 头解析 Bearer 凭证（scheme 不区分大小写） */
    fun bearer(headerValue: String?): String? {
        if (headerValue == null) return null
        val v = headerValue.trim()
        if (v.length < 7 || !v.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return v.substring(7).trim().ifEmpty { null }
    }

    /** 校验 Bearer 凭证；期望值为空或过短时一律拒绝 */
    fun checkBearer(headerValue: String?, expected: String): Boolean {
        if (expected.length < MIN_SECRET_LENGTH) return false
        val presented = bearer(headerValue) ?: return false
        return constantTimeEquals(presented, expected)
    }

    /** 界面展示用：只露出首尾 4 个字符 */
    fun mask(secret: String): String =
        if (secret.length <= 10) "•".repeat(secret.length)
        else secret.take(4) + "…" + secret.takeLast(4)

    const val MIN_SECRET_LENGTH = 16
}
