package com.mcp.server.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具参数校验器。
 *
 * 在工具执行前根据 inputSchema 对客户端传入的 arguments 做统一校验，参数错误时
 * 返回带取值范围和示例的详细错误信息（由 McpProtocol 包装为 isError 的工具结果），
 * 让客户端模型能立刻知道正确用法，而不是等执行后才得到晦涩的失败。
 *
 * 校验规则（与 JSON Schema 子集对应）：
 * - 必填参数缺失（schema.required）
 * - 参数类型不匹配（schema.properties[].type：string/integer/number/boolean/object/array）
 * - 枚举取值越界（schema.properties[].enum）
 * - 数字最小值/最大值越界（minimum/maximum）
 * - 字符串长度限制（minLength/maxLength）
 */
object ParamValidator {

    /** 类型名 -> JSON 值分类器 */
    private fun jsonType(v: Any?): String = when (v) {
        null -> "null"
        is String -> "string"
        is Boolean -> "boolean"
        is Int, is Long -> "integer"
        is Double, is Float -> "number"
        is JSONObject -> "object"
        is JSONArray -> "array"
        else -> v.javaClass.simpleName
    }

    /** 校验工具调用参数，返回错误描述；通过返回 null */
    fun validate(args: JSONObject, schema: JSONObject?): String? {
        if (schema == null) return null
        val props = schema.optJSONObject("properties") ?: return null

        // 必填参数
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (i in 0 until required.length()) {
                val key = required.optString(i)
                if (key.isEmpty()) continue
                if (!args.has(key) || args.isNull(key)) {
                    val p = props.optJSONObject(key)
                    return "缺少必要参数: $key" + usageSuffix(key, p)
                }
            }
        }

        // 逐参数校验
        for (key in args.keys()) {
            val p = props.optJSONObject(key) ?: continue
            val v = args.opt(key)
            if (v == null || v === JSONObject.NULL) continue
            val want = p.optString("type", "")
            val got = jsonType(v)

            // 类型匹配
            if (want.isNotEmpty()) {
                val ok = when (want) {
                    "string" -> v is String
                    "boolean" -> v is Boolean
                    "integer" -> v is Int || v is Long ||
                        (v is Double && v % 1.0 == 0.0) ||
                        (v is Float && v % 1.0f == 0.0f)
                    "number" -> v is Number
                    "object" -> v is JSONObject
                    "array" -> v is JSONArray
                    else -> true
                }
                if (!ok) {
                    return "参数 $key 类型错误: 期望 $want，实际为 $got" + usageSuffix(key, p)
                }
            }

            // 枚举取值
            val allowed = p.optJSONArray("enum")
            if (allowed != null && allowed.length() > 0) {
                var matched = false
                for (idx in 0 until allowed.length()) {
                    if (allowed.opt(idx).toString() == v.toString()) { matched = true; break }
                }
                if (!matched) {
                    val list = (0 until allowed.length()).joinToString(" / ") { allowed.optString(it) }
                    val example = if (allowed.length() > 0) allowed.optString(0) else ""
                    return "参数 $key 取值非法: $v（allowedValues: $list，例如: \"$example\"）"
                }
            }

            // 数值范围
            if (v is Number) {
                val d = v.toDouble()
                if (p.has("minimum") && d < p.optDouble("minimum")) {
                    return "参数 $key 过小: $d（最小 ${p.optDouble("minimum")}）" + usageSuffix(key, p)
                }
                if (p.has("maximum") && d > p.optDouble("maximum")) {
                    return "参数 $key 过大: $d（最大 ${p.optDouble("maximum")}）" + usageSuffix(key, p)
                }
            }

            // 字符串长度
            if (v is String) {
                if (p.has("minLength") && v.length < p.optInt("minLength")) {
                    return "参数 $key 过短: 至少 ${p.optInt("minLength")} 字符" + usageSuffix(key, p)
                }
                if (p.has("maxLength") && v.length > p.optInt("maxLength")) {
                    return "参数 $key 过长: 最多 ${p.optInt("maxLength")} 字符" + usageSuffix(key, p)
                }
            }
        }
        return null
    }

    /** 生成用法后缀：优先 example，其次枚举建议 */
    private fun usageSuffix(key: String, p: JSONObject?): String {
        if (p == null) return ""
        val sb = StringBuilder()
        if ((p.optJSONArray("examples")?.length() ?: 0) > 0) {
            sb.append("。正确用法示例: $key = ").append(p.getJSONArray("examples").opt(0))
        }
        val allowed = p.optJSONArray("enum")
        if (allowed != null && allowed.length() > 0) {
            val list = (0 until allowed.length()).joinToString(" / ") { allowed.optString(it) }
            sb.append("。allowedValues: ").append(list)
        }
        if (p.has("description")) {
            sb.append("（").append(p.optString("description")).append("）")
        }
        return sb.toString()
    }
}
