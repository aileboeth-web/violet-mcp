package com.mcp.server.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 发布来源（App 测试时可以换成假的） */
interface ReleaseSource {
    fun latest(): UpdateChecker.Release
    fun download(release: UpdateChecker.Release, target: File, progress: (Long, Long) -> Unit = { _, _ -> }): String
}

/**
 * 应用自身的更新检查与下载（Violet MCP 1.4.0）。纯 JVM，不依赖 Android。
 *
 * - 来源只有 GitHub Releases：`https://api.github.com/repos/<repo>/releases/latest`，不登录。
 * - 安装包按命名约定找：`violet-mcp-<版本>-release.apk`；校验值来自同一发布的 `SHA256SUMS.txt`。
 * - 每一次请求（包括 GitHub 下载时的跳转）都只允许 https 和 [allowedHosts] 中的主机，在连接之前检查；
 *   安装包大小有上限。
 * - 下载的文件在 SHA-256 与 SHA256SUMS.txt 一致后才保留，否则删除并报错。
 *
 * 安装前的包名、签名证书、版本号检查在 App 侧完成（需要 PackageManager）。
 *
 * 单独放在 com.mcp.server.update 包里：协议核心 com.mcp.server.core 和工具包仍然不允许任何写文件操作
 * （tests/verify_apk.py 检查），只有这里和 AppUpdater 可以写、删自己缓存目录里的安装包。
 */
class UpdateChecker(
    private val repo: String = DEFAULT_REPO,
    private val apiBase: String = "https://api.github.com",
    private val allowedHosts: Set<String> = GITHUB_HOSTS,
    private val requireHttps: Boolean = true,
) : ReleaseSource {
    companion object {
        const val DEFAULT_REPO = "aileboeth-web/violet-mcp"
        val GITHUB_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com")
        const val MAX_APK_BYTES = 64L * 1024 * 1024
        private const val MAX_JSON_BYTES = 2L * 1024 * 1024
        private const val MAX_SUMS_BYTES = 64L * 1024
    }

    class UpdateException(message: String) : IOException(message)

    data class Release(
        val tag: String,
        val version: String,
        val pageUrl: String?,
        val publishedAt: String?,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sumsUrl: String,
    )

    // redirects are followed by hand (open): every hop is checked BEFORE anything connects to it
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun checkUrl(url: HttpUrl) {
        if ((requireHttps && !url.isHttps) || url.host !in allowedHosts) {
            throw UpdateException("拒绝连接 ${url.host}：更新只从 GitHub 下载")
        }
    }

    /** GET [url], following at most 5 redirects, each one inside [allowedHosts]. The caller closes the response. */
    private fun open(url: String, accept: String? = null): Response {
        var current = url.toHttpUrlOrNull() ?: throw UpdateException("地址不正确")
        repeat(6) {
            checkUrl(current)
            val request = Request.Builder().url(current).header("User-Agent", "violet-mcp-updater")
                .apply { if (accept != null) header("Accept", accept) }.build()
            val resp = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw UpdateException("网络不通或被拦截（${e.javaClass.simpleName}）")
            }
            if (resp.code !in 300..399) return resp
            val location = resp.header("Location")
            resp.close()
            current = location?.let { current.resolve(it) } ?: throw UpdateException("下载地址跳转不正确")
        }
        throw UpdateException("下载地址跳转次数太多")
    }

    /** 最新发布。没有安装包、没有校验文件或版本号不规范时报错（中文原因） */
    override fun latest(): Release {
        val body = get("$apiBase/repos/$repo/releases/latest", MAX_JSON_BYTES, "application/vnd.github+json")
        val data = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            throw UpdateException("GitHub 返回的内容无法解析")
        }
        val tag = data.optString("tag_name")
        val version = tag.trimStart('v', 'V')
        if (Versions.key(version) == null) throw UpdateException("最新发布的标签 $tag 不是版本号")
        val assets = data.optJSONArray("assets") ?: throw UpdateException("最新发布 $tag 没有附件")
        val apkName = "violet-mcp-$version-release.apk"
        var apk: JSONObject? = null
        var sums: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            when (a.optString("name")) {
                apkName -> apk = a
                "SHA256SUMS.txt" -> sums = a
            }
        }
        if (apk == null) throw UpdateException("最新发布 $tag 里没有 $apkName")
        if (sums == null) throw UpdateException("最新发布 $tag 里没有 SHA256SUMS.txt，无法校验，不更新")
        return Release(
            tag = tag, version = version,
            pageUrl = data.optString("html_url").takeIf { it.startsWith("https://github.com/") },
            publishedAt = data.optString("published_at").ifBlank { null },
            notes = data.optString("body").take(4000),
            apkName = apkName, apkUrl = apk.optString("browser_download_url"), apkSize = apk.optLong("size", -1),
            sumsUrl = sums.optString("browser_download_url"),
        )
    }

    /** SHA256SUMS.txt 里安装包的 SHA-256（小写十六进制） */
    fun expectedSha256(release: Release): String {
        val text = String(get(release.sumsUrl, MAX_SUMS_BYTES), Charsets.UTF_8)
        return parseSums(text)[release.apkName]
            ?: throw UpdateException("SHA256SUMS.txt 里没有 ${release.apkName}，不更新")
    }

    /**
     * 下载安装包到 [target]，SHA-256 与 SHA256SUMS.txt 一致才保留。返回校验值。
     * [progress] 收到已下载字节数和总字节数（未知时为 -1）。
     */
    override fun download(release: Release, target: File, progress: (Long, Long) -> Unit): String {
        if (release.apkSize > MAX_APK_BYTES) throw UpdateException("安装包太大（${release.apkSize} 字节），不下载")
        val expected = expectedSha256(release)
        val tmp = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open(release.apkUrl).use { resp ->
                    if (!resp.isSuccessful) throw UpdateException("下载安装包失败：HTTP ${resp.code}")
                    val body = resp.body ?: throw UpdateException("下载安装包失败：没有内容")
                    val total = body.contentLength()
                    if (total > MAX_APK_BYTES) throw UpdateException("安装包太大，不下载")
                    var read = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                read += n
                                if (read > MAX_APK_BYTES) throw UpdateException("安装包太大，不下载")
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                                progress(read, total)
                            }
                        }
                    }
                }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw UpdateException("安装包校验失败（SHA-256 与 SHA256SUMS.txt 不一致），已删除")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw UpdateException("无法保存安装包")
            return actual
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException("下载中断（${e.javaClass.simpleName}），稍后再试")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun get(url: String, limit: Long, accept: String? = null): ByteArray {
        try {
            open(url, accept).use { resp ->
                if (resp.code == 404) throw UpdateException("$repo 还没有发布任何版本")
                if (!resp.isSuccessful) throw UpdateException("GitHub 返回 ${resp.code}")
                val source = resp.body?.source() ?: throw UpdateException("GitHub 没有返回内容")
                if (source.request(limit + 1)) throw UpdateException("返回的内容太大")   // more than limit bytes
                return source.buffer.readByteArray()
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException("网络不通或被拦截（${e.javaClass.simpleName}）")
        } catch (e: IllegalArgumentException) {
            throw UpdateException("地址不正确")
        }
    }
}

/** `<sha256> *<文件名>` 或 `<sha256>  <文件名>` 每行一个 */
fun parseSums(text: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (line in text.lineSequence()) {
        val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*)$").find(line.trim()) ?: continue
        out[m.groupValues[2].trim().substringAfterLast('/')] = m.groupValues[1].lowercase()
    }
    return out
}

/** 语义化版本比较（只看主、次、修订号；“1.3.0-rc1” 视为 1.3.0） */
object Versions {
    fun key(version: String?): Triple<Int, Int, Int>? {
        val head = (version ?: "").trim().trimStart('v', 'V').substringBefore('-').substringBefore('+')
        val parts = head.split('.')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 6 }) {
            return null
        }
        val n = parts.map { it.toInt() } + List(3 - parts.size) { 0 }
        return Triple(n[0], n[1], n[2])
    }

    /** 两个都是版本号且 latest 更大才算更新（当前版本不规范时从不认为“有更新”） */
    fun isNewer(latest: String?, current: String?): Boolean {
        val a = key(latest) ?: return false
        val b = key(current) ?: return false
        return compareValuesBy(a, b, { it.first }, { it.second }, { it.third }) > 0
    }
}
