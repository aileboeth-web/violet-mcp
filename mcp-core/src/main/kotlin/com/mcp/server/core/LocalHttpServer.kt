package com.mcp.server.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 最小 HTTP/1.1 服务器（每个连接处理一个请求后关闭）。
 * - 按字节读取请求，请求头 ≤ 16 KiB，请求体 ≤ 1 MiB，支持 Content-Length 和 chunked
 * - 先做 [McpEndpoint.precheck]（鉴权等），未通过时不读取请求体
 * - 固定大小线程池 + 有界队列，过载时直接返回 503
 * - 只允许监听回环地址：令牌走明文 HTTP，不能暴露到局域网或公网。传入其他地址直接抛异常、拒绝启动；
 *   远程访问一律经 TLS 中继（[RelayClient]）
 */
class LocalHttpServer(
    private val endpoint: McpEndpoint,
    private val bindHost: String,
    private val port: Int,
    private val log: CoreLog = NoopLog,
    maxThreads: Int = 8,
) {
    init {
        require(isLoopbackLiteral(bindHost)) {
            "拒绝监听非回环地址 $bindHost：本地端点是明文 HTTP，只允许 127.0.0.1 / ::1 / localhost"
        }
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = ThreadPoolExecutor(
        maxThreads, maxThreads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
    ) { r -> Thread(r, "mcp-http").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

    val isRunning: Boolean get() = running.get()

    /** 实际监听端口（port=0 时由系统分配） */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /** 启动监听；端口被占用等错误返回 false */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName(bindHost), port), 50)
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "mcp-http-accept").apply { isDaemon = true; start() }
            log.info("本地 MCP 端点已监听 $bindHost:${ss.localPort}")
            true
        } catch (e: IOException) {
            log.error("本地 MCP 端点启动失败（$bindHost:$port）: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        pool.shutdownNow()
        log.info("本地 MCP 端点已停止")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try { ss.accept() } catch (e: IOException) {
                if (running.get()) log.warn("接受连接失败: ${e.message}")
                break
            }
            try {
                pool.execute { serve(socket) }
            } catch (_: RejectedExecutionException) {
                writeQuietly(socket, HttpResponse.json(503, "{\"error\":\"busy\"}"), head = false)
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream())
                val response = readAndHandle(input)
                write(s.getOutputStream(), response.first, response.second)
                drain(s, input)
            } catch (_: SocketTimeoutException) {
                writeQuietly(s, HttpResponse.json(408, "{\"error\":\"timeout\"}"), head = false)
            } catch (e: HttpError) {
                writeQuietly(s, HttpResponse.json(e.status, "{\"error\":\"${e.message}\"}"), head = false)
            } catch (e: IOException) {
                // 客户端断开等，无需响应
            } catch (e: Exception) {
                log.error("处理请求异常: ${e.javaClass.simpleName}")
                writeQuietly(s, HttpResponse.json(500, "{\"error\":\"internal\"}"), head = false)
            }
        }
    }

    /** 返回 (响应, 是否 HEAD 请求) */
    private fun readAndHandle(input: InputStream): Pair<HttpResponse, Boolean> {
        val requestLine = readLine(input, MAX_LINE) ?: throw IOException("closed")
        val receivedAtNanos = System.nanoTime()
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "bad request line")
        val method = parts[0].uppercase()
        val path = parts[1]

        val headers = LinkedHashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, MAX_LINE) ?: throw IOException("closed")
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) throw HttpError(431, "headers too large")
            val idx = line.indexOf(':')
            if (idx <= 0) throw HttpError(400, "bad header")
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            // 重复头按逗号合并（Authorization 不应重复，合并后鉴权自然失败）
            headers[key] = headers[key]?.let { "$it, $value" } ?: value
        }
        val head = method == "HEAD"

        endpoint.precheck(method, path, headers)?.let { return it to head }

        val body = readBody(input, headers)
        return endpoint.handle(HttpRequest(method, path, headers, body, receivedAtNanos)) to head
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val te = headers["transfer-encoding"]?.lowercase()
        if (te != null) {
            if (te != "chunked") throw HttpError(501, "unsupported transfer-encoding")
            return readChunked(input)
        }
        val lenHeader = headers["content-length"] ?: return ByteArray(0)
        val len = lenHeader.toLongOrNull() ?: throw HttpError(400, "bad content-length")
        if (len < 0) throw HttpError(400, "bad content-length")
        if (len > MAX_BODY_BYTES) throw HttpError(413, "body too large")
        return readExactly(input, len.toInt())
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input, MAX_LINE) ?: throw IOException("closed")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400, "bad chunk")
            if (size < 0) throw HttpError(400, "bad chunk")
            if (size == 0) {
                // 跳过 trailer
                while (true) {
                    val l = readLine(input, MAX_LINE) ?: break
                    if (l.isEmpty()) break
                }
                return out.toByteArray()
            }
            if (out.size() + size > MAX_BODY_BYTES) throw HttpError(413, "body too large")
            out.write(readExactly(input, size))
            readLine(input, MAX_LINE) // 块尾 CRLF
        }
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("unexpected end of body")
            off += n
        }
        return buf
    }

    /** 读取一行（CRLF 或 LF 结尾，按 ISO-8859-1 解码）；EOF 且无数据时返回 null */
    private fun readLine(input: InputStream, max: Int): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            out.write(b)
            if (out.size() > max) throw HttpError(431, "line too long")
        }
        val bytes = out.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, StandardCharsets.ISO_8859_1)
    }

    private fun write(out: OutputStream, r: HttpResponse, head: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(HttpResponse.reason(r.status)).append("\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        if (!head) out.write(r.body)
        out.flush()
    }

    private fun writeQuietly(s: Socket, r: HttpResponse, head: Boolean) {
        try {
            write(s.getOutputStream(), r, head)
            drain(s, s.getInputStream())
        } catch (_: IOException) {
        }
        try { s.close() } catch (_: IOException) {}
    }

    /**
     * 提前拒绝（如 401）时请求体可能还没读。直接 close 会让内核发 RST，客户端可能收不到响应，
     * 所以先半关闭输出，再在有限时间内读掉剩余数据。
     */
    private fun drain(s: Socket, input: InputStream) {
        try {
            s.shutdownOutput()
            s.soTimeout = 1000
            val buf = ByteArray(8192)
            var total = 0
            while (total < MAX_DRAIN_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
        }
    }

    private class HttpError(val status: Int, message: String) : Exception(message)

    companion object {
        /** 只接受字面量回环地址，不做 DNS 解析 */
        fun isLoopbackLiteral(host: String): Boolean =
            host == "localhost" || host == "::1" || host == "[::1]" ||
                Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""").matches(host)

        const val MAX_BODY_BYTES = 1 shl 20
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADERS = 64
        const val MAX_LINE = 8 * 1024
        const val READ_TIMEOUT_MS = 30_000
        // 提前返回 413/401 时读掉客户端仍在发送的请求体，让它能收到响应（只监听回环地址，代价很小）
        const val MAX_DRAIN_BYTES = 4 * 1024 * 1024
    }
}
