package com.mcp.server.update

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateCheckerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private var sha = sha256(apk)
    private var routes = mutableMapOf<String, MockResponse>()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path] ?: MockResponse().setResponseCode(404)
        }
        server.start()
        routes["/repos/o/violet-mcp/releases/latest"] = json(release("v1.4.0"))
        routes["/dl/apk"] = MockResponse().setBody(Buffer().write(apk))
        routes["/dl/sums"] = MockResponse().setBody(
            "$sha *violet-mcp-1.4.0-release.apk\n${"0".repeat(64)} *violet-mcp-relay-1.2.0-linux-amd64\n")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun release(tag: String, apkName: String = "violet-mcp-${tag.trimStart('v')}-release.apk", sums: Boolean = true) = """
        {"tag_name": "$tag", "html_url": "https://github.com/o/violet-mcp/releases/tag/$tag",
         "published_at": "2026-10-27T00:00:00Z", "body": "notes",
         "assets": [{"name": "$apkName", "size": ${apk.size}, "browser_download_url": "${base()}/dl/apk"}
                    ${if (sums) """, {"name": "SHA256SUMS.txt", "size": 300, "browser_download_url": "${base()}/dl/sums"}""" else ""}]}
    """.trimIndent()

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun checker(hosts: Set<String> = setOf(server.hostName)) =
        UpdateChecker(repo = "o/violet-mcp", apiBase = base(), allowedHosts = hosts, requireHttps = false)

    @Test
    fun findsTheApkAndTheChecksumsOfTheLatestRelease() {
        val r = checker().latest()
        assertEquals("1.4.0", r.version)
        assertEquals("violet-mcp-1.4.0-release.apk", r.apkName)
        assertEquals("https://github.com/o/violet-mcp/releases/tag/v1.4.0", r.pageUrl)
        assertTrue(r.apkUrl.endsWith("/dl/apk") && r.sumsUrl.endsWith("/dl/sums"))
    }

    @Test
    fun downloadsAndKeepsTheApkOnlyWhenTheChecksumMatches() {
        val c = checker()
        val target = tmp.root.resolve("u/update.apk")
        assertEquals(sha, c.download(c.latest(), target))
        assertTrue(target.readBytes().contentEquals(apk))
        assertFalse(tmp.root.resolve("u/update.apk.part").exists())

        routes["/dl/sums"] = MockResponse().setBody("${"a".repeat(64)} *violet-mcp-1.4.0-release.apk\n")
        target.delete()
        expectError("校验失败") { c.download(c.latest(), target) }
        assertFalse(target.exists())
        assertFalse(tmp.root.resolve("u/update.apk.part").exists())
    }

    @Test
    fun aReleaseWithoutApkOrChecksumsIsRefused() {
        routes["/repos/o/violet-mcp/releases/latest"] = json(release("v1.4.0", apkName = "other.apk"))
        expectError("没有 violet-mcp-1.4.0-release.apk") { checker().latest() }
        routes["/repos/o/violet-mcp/releases/latest"] = json(release("v1.4.0", sums = false))
        expectError("SHA256SUMS.txt") { checker().latest() }
        routes["/repos/o/violet-mcp/releases/latest"] = json(release("nightly"))
        expectError("不是版本号") { checker().latest() }
    }

    @Test
    fun noReleaseYetAndBadAnswersSayWhy() {
        routes.remove("/repos/o/violet-mcp/releases/latest")
        expectError("还没有发布") { checker().latest() }
        routes["/repos/o/violet-mcp/releases/latest"] = json("not json")
        expectError("无法解析") { checker().latest() }
    }

    @Test
    fun hostsOutsideTheListAreNeverContacted() {
        expectError("拒绝连接") { checker(hosts = setOf("github.com")).latest() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRedirectToAnotherHostIsRefused() {
        routes["/dl/apk"] = MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.2:9/evil.apk")
        val c = checker(hosts = setOf(server.hostName))
        expectError("拒绝连接") { c.download(c.latest(), tmp.root.resolve("x.apk")) }
    }

    @Test
    fun theDefaultCheckerOnlyTalksHttpsToGithub() {
        val c = UpdateChecker(apiBase = "http://api.github.com")
        expectError("拒绝连接") { c.latest() }
        assertEquals(setOf("api.github.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"), UpdateChecker.GITHUB_HOSTS)
    }

    @Test
    fun checksumFilesInBothFormats() {
        val s = parseSums("${"A".repeat(64)} *dist/a.apk\n${"b".repeat(64)}  b.bin\nnot a line\n")
        assertEquals("a".repeat(64), s["a.apk"])
        assertEquals("b".repeat(64), s["b.bin"])
        assertEquals(2, s.size)
    }

    @Test
    fun versionComparison() {
        assertTrue(Versions.isNewer("1.4.0", "1.3.0"))
        assertTrue(Versions.isNewer("v1.10.0", "1.9.9"))
        assertFalse(Versions.isNewer("1.3.0", "1.3.0"))
        assertFalse(Versions.isNewer("1.3.0-rc1", "1.3.0"))
        assertTrue(Versions.isNewer("1.4.0", "1.3.0-debug-x"))      // "1.3.0-debug-x" is still 1.3.0
        assertFalse(Versions.isNewer("2.0", "unknown"))
        assertNull(Versions.key("1.2.3.4"))
        assertNull(Versions.key("abc"))
    }

    private fun expectError(part: String, block: () -> Unit) {
        try {
            block()
            fail("expected an UpdateException containing \"$part\"")
        } catch (e: UpdateChecker.UpdateException) {
            assertTrue("\"${e.message}\" should contain \"$part\"", e.message!!.contains(part))
        }
    }
}
