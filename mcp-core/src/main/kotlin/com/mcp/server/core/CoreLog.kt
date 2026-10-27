package com.mcp.server.core

/** 核心模块日志接口。实现方负责脱敏（见 [Redactor]），调用方不得传入令牌、请求头或请求体。 */
interface CoreLog {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

object NoopLog : CoreLog {
    override fun info(message: String) {}
    override fun warn(message: String) {}
    override fun error(message: String) {}
}

/** 兜底脱敏：把当前生效的密钥从日志文本中替换掉 */
class Redactor(private val secrets: () -> Collection<String>) {
    fun redact(message: String): String {
        var out = message
        for (s in secrets()) {
            if (s.length >= 8 && out.contains(s)) out = out.replace(s, "[REDACTED]")
        }
        return out
    }
}
