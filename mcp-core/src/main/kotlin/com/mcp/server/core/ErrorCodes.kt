package com.mcp.server.core

import org.json.JSONObject

/**
 * MCP 工具统一错误码。
 *
 * 工具失败时返回 {errorCode, error, hint?}，[McpProtocol] 会把它包装成 `isError: true` 的工具结果，
 * 方便客户端（模型）读到原因并自行修正参数。
 */
object ErrorCodes {
    const val UNKNOWN_TOOL = "UNKNOWN_TOOL"             // 未知工具（含已移除的写入类工具）
    const val INVALID_PARAMS = "INVALID_PARAMS"         // 参数校验失败
    const val INVALID_VALUE = "INVALID_VALUE"           // 取值非法（枚举/数值越界/时间范围等）
    const val NOT_FOUND = "NOT_FOUND"                   // 目标不存在（如应用不可见/未安装）
    const val PERMISSION_DENIED = "PERMISSION_DENIED"   // 缺少权限（如使用情况访问权限）
    const val UNAVAILABLE = "UNAVAILABLE"               // 系统服务不可用
    const val INTERNAL = "INTERNAL"                     // 内部错误
}

/** 工具错误结果构建器 */
object Err {
    /** 构造错误结果：{errorCode, error, hint?}，调用方负责加 isError */
    fun of(code: String, message: String, hint: String? = null): JSONObject {
        val o = JSONObject().put("errorCode", code).put("error", message)
        if (!hint.isNullOrEmpty()) o.put("hint", hint)
        return o
    }

    fun isError(result: JSONObject): Boolean = result.has("errorCode") && result.has("error")
}
