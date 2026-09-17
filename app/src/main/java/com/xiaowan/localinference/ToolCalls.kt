package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 工具调用（`tools` / `function calling`）的**纯逻辑**部分：请求侧解析 + 响应侧拼装。
 *
 * 为什么单独一个文件：这套逻辑此前散在 `HttpApi.handleChat` 里，而它有两处
 * **只在真实请求里才会暴露**的坑（见 [toolCallsJson] 与 [streamDeltas] 的注释），
 * 混在 600 行的 HTTP 处理函数中既测不到也读不出来。抽出来之后可以离线单测
 * （`tools/run_tool_call_tests.sh`），不必装机、不必下模型。
 *
 * 分工：**语法解析全部在 native**（`common_chat_parse` 按模型模板推导 PEG 解析器，
 * 支持 Qwen 的 `<tool_call>`、Llama 的 `[TOOL_CALLS]` 等任意模板）；
 * 本文件只负责「把 native 给的归一化结果翻译成 OpenAI 线上的 JSON 形状」。
 * 在 Kotlin 侧硬编码任何一种语法都会漏掉其它模板，这是本功能的既定红线。
 */
object ToolCalls {

    /**
     * 请求侧：`tool_choice` 归一。
     *
     * 只认字符串（`auto` / `required` / `none`）。**其余一律归一成 `"auto"`，绝不返回 null**：
     * 这是「一调工具服务端就闪退」的直接成因 ——
     *
     * - 请求不传 `tool_choice`（或传 null / 空串 / 对象）时，旧实现返回 `null`；
     * - `null` 透传过 JNI 后 native 侧只能拿到空串 `""`；
     * - 而 vendor 库的 `common_chat_tool_choice_parse_oaicompat("")` **对空串是 throw
     *   `std::invalid_argument("Invalid tool_choice: ")`，不是返回 AUTO**（注释里原来
     *   「空串等同 auto」的说法与库实现相反）；
     * - 该异常从库内 unwind 穿过 JNI 帧 = UB → `std::terminate` → SIGABRT。
     *
     * 所以这里必须在请求侧就把 `null`/空串/对象收敛成显式的 `"auto"`，
     * native 侧另有一道同源兜底（见 llama_jni.cpp），两道都要有。
     *
     * 附带修正一处旧注释的错误假设：`{"type":"function",...}` 这种对象形式**不是**
     * 「退化成 null」，而是明确归一成 `"auto"`（native 侧同样如此）。
     */
    fun parseToolChoice(j: JSONObject, hasTools: Boolean): String? {
        if (!hasTools) return null
        if (j.opt("tool_choice") is String) {
            return j.optString("tool_choice").takeIf { it.isNotEmpty() } ?: AUTO
        }
        // null / 缺字段 / 对象形式 -> 显式 auto（不是 null，见上方崩因说明）
        return AUTO
    }

    /** `tool_choice` 的兜底值，与 native 侧 `COMMON_CHAT_TOOL_CHOICE_AUTO` 对应。 */
    const val AUTO = "auto"

    /** 请求是否带工具（`tools` 存在且非空数组）。 */
    fun hasTools(j: JSONObject): Boolean {
        val a = j.optJSONArray("tools") ?: return false
        return a.length() > 0
    }

    /** 请求里的 tools 原文，透传给 native；无工具返回 null。 */
    fun toolsJson(j: JSONObject): String? {
        val a = j.optJSONArray("tools") ?: return null
        return if (a.length() > 0) a.toString() else null
    }

    /**
     * 单个 tool_call 的 OpenAI 线上形状。
     *
     * id 兜底规则必须与 native 侧**逐字一致**（缺 id 时 `call_<下标>`）：
     * 客户端要把这个 id 回填进后续 `role=tool` 消息，两条路径算出不同 id 会让上游 SDK 对不上号。
     * 之所以在 Kotlin 侧也兜一次，是因为 `arguments` 是**字符串内嵌 JSON**，
     * 直接把它拼进响应体会破坏 JSON 结构，必须在拼装时用 [jsonStr] 转义。
     */
    fun callJson(index: Int, c: JSONObject): String {
        val id = c.optString("id").ifEmpty { "call_$index" }
        return """{"index":$index,"id":${id.jsonStr()},"type":"function","function":{"name":${c.optString("name").jsonStr()},"arguments":${c.optString("arguments").jsonStr()}}}"""
    }

    /**
     * 流式响应的 tool_calls 增量块。
     *
     * 已整段解析完成，因此一次性下发全部调用（每块带 `index`），而不是逐 token 拼装 ——
     * 客户端按 index 聚合，两种做法结果一致，但一次性下发不必维护半截 arguments 的状态机。
     */
    fun streamDeltas(calls: JSONArray): List<String> =
        (0 until calls.length()).mapNotNull { k ->
            val c = calls.optJSONObject(k) ?: return@mapNotNull null
            """{"tool_calls":[${callJson(k, c)}]}"""
        }

    /**
     * 非流式响应里的 `message.tool_calls` 数组字面量（含前导逗号，便于拼进 message 对象）。
     *
     * 不直接透传 native 的 JSON 是刻意的：native 回来的 `toolCalls[]` 只有
     * `id/name/arguments`（解析器的原生形状），OpenAI 线上要的是
     * `{"id":..,"type":"function","function":{..}}`。此前把原生数组当响应体直接吐出去，
     * 客户端拿不到 `type` / `function` 两层包装，SDK 会当成无效 tool_calls 丢弃。
     */
    fun messageToolCallsField(calls: JSONArray): String {
        val items = (0 until calls.length()).mapNotNull { k ->
            calls.optJSONObject(k)?.let { callJson(k, it) }
        }
        if (items.isEmpty()) return ""
        val head = "," + """"tool_calls":["""
        return head + items.joinToString(",") + "]"
    }

    // toJsonStr 在 HttpApi 里是 private，这里要独立可测（工具调用测试不编译整个 HTTP 层），
    // 故自带一份等价实现。两处都只做 JSON 字符串转义，语义相同。
    private fun String.jsonStr(): String {
        val sb = StringBuilder(length + 16).append('"')
        for (c in this) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
