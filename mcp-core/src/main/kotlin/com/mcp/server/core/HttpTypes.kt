package com.mcp.server.core

import java.nio.charset.StandardCharsets

/** 与传输无关的 HTTP 请求（本地 socket 与中继共用）。headers 的 key 一律小写。 */
class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
    /** 收到请求的时刻（System.nanoTime，单调时钟），用于分阶段计时 */
    val receivedAtNanos: Long = System.nanoTime(),
) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun bodyText(): String = String(body, StandardCharsets.UTF_8)
}

class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun bodyText(): String = String(body, StandardCharsets.UTF_8)

    companion object {
        fun json(status: Int, json: String, extra: Map<String, String> = emptyMap()): HttpResponse =
            HttpResponse(status, mapOf("Content-Type" to "application/json") + extra,
                json.toByteArray(StandardCharsets.UTF_8))

        fun empty(status: Int, extra: Map<String, String> = emptyMap()): HttpResponse =
            HttpResponse(status, extra)

        fun reason(code: Int): String = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            406 -> "Not Acceptable"; 408 -> "Request Timeout"; 411 -> "Length Required"
            413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
            431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
            501 -> "Not Implemented"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
            else -> "Status"
        }
    }
}
