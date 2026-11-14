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
 * 分工：**语法解析优先在 native**（`common_chat_parse` 按模型模板推导 PEG 解析器，
 * 支持 Qwen 的 `<tool_call>`、Llama 的 `[TOOL_CALLS]` 等任意模板）；
 * 本文件负责「把 native 给的归一化结果翻译成 OpenAI 线上的 JSON 形状」。
 * native 完全不认识时，[parseLegacyToolCalls] 只兜底三种**带固定标签**的格式
 * （`<function name=...><param ...>`、`<tool_call>NAME<arg_key>...`、
 * Hermes-2 的 `<function=NAME><parameter=...>`）——
 * 它们带不可混淆的标记，且真机已证实会绕过模板 PEG。除此之外不猜任何语法。
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
     * id **不是** native 占位值的直接透传：native 缺 id 时会补 `call_<下标>`，
     * 每轮都相同，客户端回填 `role=tool` 时无法区分不同轮次。这里用 [idSeed]
     * （响应 id）派生出唯一的 `call_<hash>_<index>`，见 [stableCallId]。
     * 之所以在 Kotlin 侧也兜一次，是因为 `arguments` 是**字符串内嵌 JSON**，
     * 直接把它拼进响应体会破坏 JSON 结构，必须在拼装时用 [jsonQuoted] 转义。
     */
    fun callJson(index: Int, c: JSONObject, idSeed: String = ""): String {
        val id = stableCallId(c.optString("id"), idSeed, index)
        return """{"index":$index,"id":${id.jsonQuoted()},"type":"function","function":{"name":${c.optString("name").jsonQuoted()},"arguments":${c.optString("arguments").jsonQuoted()}}}"""
    }

    /**
     * 工具调用 id 的唯一化。
     *
     * native 在模型没给 id 时会补 `call_<下标>` 占位；这个值**每轮都一样**
     * （`call_0` / `call_1`），客户端拿它回填 `role=tool` 时会把不同轮次的结果
     * 混在一起。这里用响应 id（[idSeed]）派生出每轮唯一的 `call_<hash>_<index>`。
     *
     * 保留真正的 id（模型/模板给的、不是 `call_<纯数字>` 占位）：多轮续聊要靠它
     * 与历史里的 tool 消息配对，改写会把配对打断。
     */
    private fun stableCallId(raw: String, seed: String, index: Int): String {
        val placeholder = raw.isEmpty() || Regex("""call_\d+""").matches(raw)
        if (!placeholder) return raw
        if (seed.isEmpty()) return "call_$index"
        return "call_${seed.hashCode().toUInt().toString(16)}_$index"
    }

    /**
     * 流式响应的 tool_calls 增量块。
     *
     * 已整段解析完成，因此一次性下发全部调用（每块带 `index`），而不是逐 token 拼装 ——
     * 客户端按 index 聚合，两种做法结果一致，但一次性下发不必维护半截 arguments 的状态机。
     */
    fun streamDeltas(calls: JSONArray, idSeed: String = ""): List<String> =
        (0 until calls.length()).mapNotNull { k ->
            val c = calls.optJSONObject(k) ?: return@mapNotNull null
            """{"tool_calls":[${callJson(k, c, idSeed)}]}"""
        }

    /**
     * 非流式响应里的 `message.tool_calls` 数组字面量（含前导逗号，便于拼进 message 对象）。
     *
     * 不直接透传 native 的 JSON 是刻意的：native 回来的 `toolCalls[]` 只有
     * `id/name/arguments`（解析器的原生形状），OpenAI 线上要的是
     * `{"id":..,"type":"function","function":{..}}`。此前把原生数组当响应体直接吐出去，
     * 客户端拿不到 `type` / `function` 两层包装，SDK 会当成无效 tool_calls 丢弃。
     */
    fun messageToolCallsField(calls: JSONArray, idSeed: String = ""): String {
        val items = (0 until calls.length()).mapNotNull { k ->
            calls.optJSONObject(k)?.let { callJson(k, it, idSeed) }
        }
        if (items.isEmpty()) return ""
        val head = "," + """"tool_calls":["""
        return head + items.joinToString(",") + "]"
    }

    // =========================================================================
    // 兜底解析：native 的模板 PEG 没覆盖到的三种**明确带标记**的文本格式
    // =========================================================================
    //
    // 为什么需要兜底：`common_chat_parse` 按模型模板推导 PEG，模板与实际输出
    // 不一致时它只会返回 null（不报错）。真机现场里至少有三种格式完全没被覆盖：
    //   · `<function name="get_weather"><param name="city">Paris</param></function>`
    //   · `<tool_call>get_weather<arg_key>city</arg_key><arg_value>Paris</arg_value></tool_call>`
    //   · `<tool_call><function=get_weather}><parameter=city>Paris</parameter></function></tool_call>`
    //     （Hermes-2 / lfm；名字后可能带 `}` / `needed` 之类噪声）
    // 三种都带不可混淆的标签，且可重复（并行调用），因此可以安全地在 Kotlin
    // 侧做**最后一道**兜底；native 能解析时优先用 native（不改变既有覆盖）。
    //
    // 只认这几种带标签的固定形状，不是"再写一套通用工具语法"：
    //   · 标准 JSON / Qwen `<tool_call>{...}</tool_call>` 等仍由 native 负责；
    //   · 不认识的形状原样留在正文里，绝不猜。

    private val FUNCTION_RE = Regex(
        """<function\s+name\s*=\s*["']([^"']+)["']\s*>(.*?)</function>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PARAM_RE = Regex(
        """<param\s+name\s*=\s*["']([^"']+)["']\s*>(.*?)</param>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TOOL_CALL_RE = Regex(
        """<tool_call>\s*([A-Za-z_][A-Za-z0-9_.:-]*)\s*(.*?)</tool_call>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ARG_RE = Regex(
        """<arg_key>\s*(.*?)\s*</arg_key>\s*<arg_value>\s*(.*?)\s*</arg_value>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    // Hermes-2 / lfm 的实际形状：`<function=get_weather}>`（名字用 `=` 直连），
    // 参数是 `<parameter=city>Paris</parameter>`。名字后偶尔还带畸形噪声
    // （`get_weather}` / `get_weather needed`），因此 `[^>]*` 容忍到 `>` 为止。
    private val HERMES_FUNCTION_RE = Regex(
        """<function\s*=\s*([A-Za-z_][A-Za-z0-9_.:-]*)[^>]*>(.*?)</function>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val HERMES_PARAM_RE = Regex(
        """<parameter\s*=\s*([A-Za-z_][A-Za-z0-9_.:-]*)[^>]*>(.*?)</parameter>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TOOL_CALL_TAG_RE = Regex("""</?tool_call\s*>""", RegexOption.IGNORE_CASE)

    /**
     * 尝试三种带标签的兜底格式。命中时返回 `(剩余正文, toolCallsJson)`。
     *
     * 返回值形状与 `LlmEngine.parseToolCalls` 一致，调用方不需要区分来源。
     * 剩余正文里已删掉工具调用片段；`<think>` 清洗由调用方统一做（避免两处实现分叉）。
     */
    fun parseLegacyToolCalls(text: String): Pair<String, String?>? {
        if (text.isEmpty()) return null
        val calls = JSONArray()
        val spans = ArrayList<IntRange>()
        var hermesHit = false

        for (m in FUNCTION_RE.findAll(text)) {
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) continue
            val args = JSONObject()
            for (p in PARAM_RE.findAll(m.groupValues[2])) {
                val key = p.groupValues[1].trim()
                if (key.isNotEmpty()) args.put(key, xmlUnescape(p.groupValues[2].trim()))
            }
            calls.put(callObject(name, args))
            spans.add(m.range)
        }
        for (m in TOOL_CALL_RE.findAll(text)) {
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) continue
            val args = JSONObject()
            for (p in ARG_RE.findAll(m.groupValues[2])) {
                val key = p.groupValues[1].trim()
                if (key.isNotEmpty()) args.put(key, xmlUnescape(p.groupValues[2].trim()))
            }
            calls.put(callObject(name, args))
            spans.add(m.range)
        }
        // Hermes-2 / lfm：<function=NAME ...><parameter=KEY>V</parameter></function>
        for (m in HERMES_FUNCTION_RE.findAll(text)) {
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) continue
            val args = JSONObject()
            for (p in HERMES_PARAM_RE.findAll(m.groupValues[2])) {
                val key = p.groupValues[1].trim()
                if (key.isNotEmpty()) args.put(key, xmlUnescape(p.groupValues[2].trim()))
            }
            calls.put(callObject(name, args))
            spans.add(m.range)
            hermesHit = true
        }
        if (calls.length() == 0) return null

        val content = StringBuilder()
        var pos = 0
        for (r in spans.sortedBy { it.first }) {
            if (r.first < pos) continue          // 嵌套/重叠片段只删一次
            if (r.first > pos) content.append(text, pos, r.first)
            pos = r.last + 1
        }
        if (pos < text.length) content.append(text, pos, text.length)
        // Hermes 的外层 `<tool_call>` 可能仍留在正文里（上面只删了 function 片段）。
        if (hermesHit) {
            val cleaned = TOOL_CALL_TAG_RE.replace(content.toString(), "")
            return cleaned to calls.toString()
        }
        return content.toString() to calls.toString()
    }

    /**
     * 兜底结果是否应当**替换** native 结果。
     *
     * 真机（lfm / Hermes-2）证据：模型一次生成 N 个调用（`out` 与 N 成正比），
     * native 的模板 PEG 只回第 1 个，于是客户端永远只拿到 1 个，且没有任何
     * 异常信号。这里把「数量」作为唯一替换判据，并加两道保护：
     *
     *  1. **只在兜底更多时替换**（`fbCount > nativeCount`）。等量说明 native
     *     已经覆盖，不动它；更少更不能换。
     *  2. **兜底里必须有一个调用与 native 的第一个一致**（名字 + 参数）。否则
     *     正文里恰好出现 `<function=` 字面量时会被误判成调用，换掉 native 的
     *     可信结果。允许顺序不同：只要求「存在」，不要求下标相同。
     */
    fun legacySupersedes(nativeCalls: JSONArray?, legacyCalls: JSONArray?): Boolean {
        val nativeCount = nativeCalls?.length() ?: 0
        val legacyCount = legacyCalls?.length() ?: 0
        if (legacyCount <= nativeCount) return false
        val nativeFirst = nativeCalls?.optJSONObject(0) ?: return true
        return (0 until legacyCount).any { sameCall(legacyCalls!!.optJSONObject(it), nativeFirst) }
    }

    /** 工具调用等价：名字相同，参数按 JSON 语义比较（不依赖键顺序/空白）。 */
    private fun sameCall(a: JSONObject?, b: JSONObject?): Boolean {
        if (a == null || b == null) return false
        if (a.optString("name") != b.optString("name")) return false
        return sameArgs(a.optString("arguments", ""), b.optString("arguments", ""))
    }

    /** 顶层按 JSON 对象比较：键集合相同、各值等价；解析失败则退回字面量比较。 */
    private fun sameArgs(a: String, b: String): Boolean {
        if (a == b) return true
        return try {
            val ja = JSONObject(a)
            val jb = JSONObject(b)
            if (ja.length() != jb.length()) return false
            ja.keys().asSequence().all { k ->
                jb.has(k) && ja.opt(k).toString() == jb.opt(k).toString()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun callObject(name: String, args: JSONObject): JSONObject =
        JSONObject().apply {
            put("id", "")
            put("name", name)
            put("arguments", args.toString())
        }

    /** 兜底格式里偶尔会用 XML 实体传参数；只处理最常见的五种，未知实体原样保留。 */
    private fun xmlUnescape(s: String): String =
        s.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
