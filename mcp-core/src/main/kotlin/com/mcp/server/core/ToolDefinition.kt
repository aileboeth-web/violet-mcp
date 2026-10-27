package com.mcp.server.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * An MCP tool definition plus its implementation.
 * 只读服务器中的工具都必须出现在 [ReadOnlyPolicy.ALLOWED_TOOLS] 里，否则 [ToolRegistry] 拒绝注册。
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val category: String = "通用",
    /** Invoke the tool. Returns a JSONObject (preferred), String or other JSON-serializable value. */
    val handler: (JSONObject) -> Any?,
)

/** 给工具打分类标记 */
fun ToolDefinition.inCategory(category: String): ToolDefinition = copy(category = category)

/** 无参数工具的 inputSchema */
fun emptySchema(): JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

/**
 * 构建参数 schema 条目：type + enum / examples / description / minimum / maximum / minLength / maxLength。
 * 参数错误时 [ParamValidator] 会结合这些信息生成带取值范围和示例的用法提示。
 */
fun param(
    name: String,
    type: String,
    allowedValues: List<String>? = null,
    example: Any? = null,
    description: String? = null,
    minimum: Number? = null,
    maximum: Number? = null,
    minLength: Int? = null,
    maxLength: Int? = null,
): Pair<String, JSONObject> {
    val o = JSONObject().put("type", type)
    if (!allowedValues.isNullOrEmpty()) o.put("enum", JSONArray(allowedValues))
    if (example != null) o.put("examples", JSONArray().put(example))
    if (description != null) o.put("description", description)
    if (minimum != null) o.put("minimum", minimum)
    if (maximum != null) o.put("maximum", maximum)
    if (minLength != null) o.put("minLength", minLength)
    if (maxLength != null) o.put("maxLength", maxLength)
    return name to o
}

/** 由 param() 条目列表生成完整 inputSchema */
fun paramSchema(
    props: List<Pair<String, JSONObject>>,
    required: List<String> = emptyList(),
): JSONObject {
    val p = JSONObject()
    for ((k, v) in props) p.put(k, v)
    val s = JSONObject().put("type", "object").put("properties", p)
    if (required.isNotEmpty()) s.put("required", JSONArray(required))
    return s
}
