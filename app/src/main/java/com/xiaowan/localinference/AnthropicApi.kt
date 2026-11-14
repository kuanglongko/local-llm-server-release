package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * Anthropic Messages API（`/v1/messages`）兼容层。
 *
 * ## 为什么是一个「外层包装」而不是在 [HttpApi.handleChat] 里加分支
 *
 * `handleChat` 已经有一套被大量现场问题打磨过的逻辑（预算、思考状态机、
 * 取消探针、心跳、relay 坐标、KV 账本……）。任何一种「在它内部按协议分叉」
 * 的改法，都要求把上述每一处的判断都复制一遍 —— 复制的两份判据必然漂移，
 * 而漂移的代价是「OpenAI 那条路上好好的、Anthropic 这条路上静默出错」。
 *
 * 所以这里采取**零侵入**策略：
 *
 *  1. 把 Anthropic 请求体**翻译成** OpenAI 请求体（[buildOpenAiBody]）；
 *  2. 用一个协议转换输出流（[Translator]）包住真正的 socket `out`；
 *  3. 原样调用 [HttpApi.handleChat]。
 *
 * `handleChat` 一行都不用改，它甚至不知道自己正在服务 Anthropic 客户端 ——
 * **relay 端点也因此自动获得同样的能力**，因为 relay 最终也是调 `handleChat`。
 *
 * ## 字节契约（转换流解析的输入）
 *
 * 转换流看到的字节 = `handleChat` 写出的**完整 HTTP 响应**，有两种形态：
 *
 *  - 流式：[HttpWire.sseHead] 写头（`text/event-stream` + `Transfer-Encoding: chunked`），
 *    随后若干 `data: {openai-chunk}\n\n`（chunked 分帧），最后 `data: [DONE]\n\n` + `0\r\n\r\n`。
 *    生成期间可能夹入心跳注释帧 `: ping\n\n`（[HttpApi.heartbeat]）—— **必须丢弃**。
 *  - 非流式：[HttpWire.writeJson] 写头（`application/json` + `Content-Length`）+ 一整段 JSON。
 *
 * 出错时（含 [HttpApi.withModelReady] 在模型加载阶段写的 429/503）
 * 是 `application/json` + `{"error":{"message","type","code"}}`。
 * 这三条形态都要被正确翻译成 Anthropic 形状 —— 所以 `withModelReady` 必须
 * 把转换流当作 `out`（见 [HttpApi.handleAnthropicMessages]），而不是直接写 socket。
 */
object AnthropicApi {

    /** 默认 max_tokens：Anthropic 要求必填，缺省按它的惯例给 4096（但我们会以真实预算为准）。 */
    const val DEFAULT_MAX_TOKENS = 4096

    /**
     * 响应里回写的 `anthropic-version` 常量。**只用于响应头**，不参与任何请求校验。
     *
     * ⚠ 本端**刻意不校验请求的 `anthropic-version` 头**，这不是遗漏：
     *
     *  - 该头在官方 API 里是必填的，因为**官方服务端同时存在多个 API 主版本**
     *    （`2023-06-01` / `2023-01-01` …），同一个 URL 的行为会随版本分叉。
     *    本端**只有一个版本**，不存在"按版本分叉"这回事 —— 它不参与路由判据
     *    （路由只看 path）、不参与翻译规则（规则是写死的单一套），因此对本端
     *    而言它是**装饰性字段**：收下、忽略，就是完整的"支持"。
     *  - 要做校验就必须先**读**这个头，而本端的头解析是**逐头白名单式**的
     *    （见 `HttpApi.kt` 的读头循环：只有 `authorization` / `origin` /
     *    `access-control-request-headers` / `x-log-tag` / `x-relay-session`
     *    被显式读取，其余头读完即弃，`Req` 里没有承载它的字段）。
     *    为此给 `Req` 加字段并逐个核对其构造点，是**侵入性改动**，与本端点
     *    "零侵入、不破坏现有功能"的前提冲突，而收益仅限于"客户端头写错时早报错"
     *    这一种边缘情况、且不改变本端任何行为。
     *  - 宽松版（缺失放行、只拒明确不支持的值）同样避不开"要读头"，
     *    因此一并不做。
     *
     * 若将来真要对齐官方契约做严格兼容测试，再连同 `Req` 的头承载一起设计；
     * 在那之前，这里就是**明确的不校验**，而不是"待实现的校验"。
     */
    const val SUPPORTED_VERSION = "2023-06-01"

    /**
     * 把 Anthropic 请求体**翻译**成 OpenAI 请求体。
     *
     * 覆盖字段：
     *  - `system`（string 或 content block 数组）→ 一条 `role=system` 消息
     *  - `messages`（`content` 可为 string 或 block 数组）→ OpenAI `messages`
     *  - `max_tokens` → `max_tokens`（Anthropic 必填，这里原样带上）
     *  - `temperature` / `top_p` / `top_k` / `stop_sequences` → 同名透传
     *    （`top_k` 需为整数、`stop_sequences` 需为字符串或字符串数组，
     *    不符合时在本层报 400，避免落到本端口径的中文提示）
     *  - `stream` → `stream`
     *  - `model` → `model`
     *  - `tools` / `tool_choice` / `parallel_tool_calls` → OpenAI 形状（见下方组装段）
     *
     * **明确不支持**：图片 / 文档等非文本 content block。它们不会被静默丢掉 ——
     * 见 [unsupportedBlockType]，在翻译阶段就回 Anthropic 形状的 400。
     * `thinking` / `redacted_thinking` 是模型侧元数据，本端不回放、也不报错。
     *
     * @return 翻译结果；校验失败返回 [TranslateResult.Rejected]。
     */
    fun buildOpenAiBody(req: JSONObject): TranslateResult {
        // ---- model ----
        val model = req.optString("model", "").trim()
        if (model.isEmpty()) {
            return TranslateResult.Rejected(
                "invalid_request_error", "model: 字段缺失或为空")
        }

        // ---- max_tokens（Anthropic 必填；缺失给默认值，不报错）----
        val maxTokens = if (req.has("max_tokens")) {
            val raw = req.opt("max_tokens")
            val v = when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            } ?: return TranslateResult.Rejected(
                "invalid_request_error", "max_tokens: 必须是整数")
            if (v <= 0) {
                return TranslateResult.Rejected(
                    "invalid_request_error", "max_tokens: 必须大于 0")
            }
            v
        } else {
            DEFAULT_MAX_TOKENS
        }

        // ---- messages ----
        val messagesArr = req.optJSONArray("messages")
            ?: return TranslateResult.Rejected(
                "invalid_request_error", "messages: 字段缺失或不是数组")
        if (messagesArr.length() == 0) {
            return TranslateResult.Rejected(
                "invalid_request_error", "messages: 不能为空数组")
        }

        // ---- tools / tool_choice / parallel_tool_calls：原样透传（本端真支持）----
        //
        // 本端工具调用链路是完整的：工具定义走 `toolsJson` 独立参数交给模板
        // （HttpApi.kt:4743 `applyChatTemplateWithTools(msgs, toolsJson, toolChoice,
        // parallelToolCalls, ...)`），响应侧由 ToolCalls 解析并下发 tool_calls。
        // 所以这里**不该拦**，原样转成 OpenAI 形态即可（见下方 body 组装段）。
        //
        // 注意：不要在这里自行做 field 级校验后再透传 —— 本端
        // ToolCalls.parseToolChoice 已有一层判据，两边都判就会漂移。
        // 唯一例外是 Anthropic 与 OpenAI 的**结构差异**，必须在此转换。


        val outMsgs = JSONArray()

        // ---- system ----
        unsupportedBlockType(req.opt("system"))?.let {
            return TranslateResult.Rejected(
                "invalid_request_error", "system: 不支持的内容类型 \"$it\"")
        }
        val sysText = flattenText(req.opt("system"), allowBlocks = true)
        if (sysText.isNotEmpty()) {
            outMsgs.put(JSONObject().put("role", "system").put("content", sysText))
        }

        // ---- 逐条 message ----
        for (i in 0 until messagesArr.length()) {
            val m = messagesArr.optJSONObject(i)
                ?: return TranslateResult.Rejected(
                    "invalid_request_error", "messages[$i]: 不是对象")
            val role = m.optString("role", "")
            if (role != "user" && role != "assistant") {
                // Anthropic 的 role 只允许 user / assistant（system 走顶层字段）
                return TranslateResult.Rejected(
                    "invalid_request_error",
                    "messages[$i].role: 只允许 \"user\" 或 \"assistant\"（得到 \"$role\"）")
            }

            val contentField = m.opt("content")
            // 图片/文档等本端无法消费的 block 必须**明确拒绝**，不能静默丢掉：
            // 调用方会以为模型看过图，实际只收到文本，答案错得毫无线索。
            unsupportedBlockType(contentField)?.let {
                return TranslateResult.Rejected(
                    "invalid_request_error",
                    "messages[$i].content: 不支持的内容类型 \"$it\"")
            }

            // ---- 结构化 block 路径 ----
            // 当 content 是 block 数组且**含 tool_use / tool_result** 时，必须做
            // 结构转换而非拍平文本 —— 否则工具配对语义会丢：
            //   · assistant 的 tool_use  -> 独立一条 assistant 消息（文本 + 调用描述）
            //   · user 的 tool_result    -> 独立一条 role="tool" 消息
            // 只有**真正**含工具 block 才走这条路；纯 text 仍走下方简单路径，
            // 保证既有行为一字不变（零侵入原则）。
            if (contentField is JSONArray && hasToolBlocks(contentField)) {
                if (role == "assistant") {
                    // assistant：text 段与 tool_use 段合并在同一条 assistant 消息里，
                    // tool_use 以文本形式描述（本端消息模型无结构化 tool_calls 输入位）。
                    val sb = StringBuilder()
                    for (b in 0 until contentField.length()) {
                        val blk = contentField.optJSONObject(b) ?: continue
                        when (blk.optString("type", "")) {
                            "text" -> {
                                val t = blk.optString("text", "")
                                if (t.isNotEmpty()) {
                                    if (sb.isNotEmpty()) sb.append('\n')
                                    sb.append(t)
                                }
                            }
                            "tool_use" -> {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(describeToolUse(blk))
                            }
                        }
                    }
                    val txt = sb.toString().trim()
                    if (txt.isEmpty()) {
                        return TranslateResult.Rejected(
                            "invalid_request_error",
                            "messages[$i].content: 为空或不支持的内容类型")
                    }
                    outMsgs.put(JSONObject().put("role", "assistant").put("content", txt))
                } else {
                    // user：普通 text 段合成一条 user 消息；
                    // 每个 tool_result 各成一条 role="tool" 消息（本端模板按 role 取名）。
                    val sb = StringBuilder()
                    val deferred = JSONArray()
                    for (b in 0 until contentField.length()) {
                        val blk = contentField.optJSONObject(b) ?: continue
                        when (blk.optString("type", "")) {
                            "text" -> {
                                val t = blk.optString("text", "")
                                if (t.isNotEmpty()) {
                                    if (sb.isNotEmpty()) sb.append('\n')
                                    sb.append(t)
                                }
                            }
                            "tool_result" -> deferred.put(blk)
                            // 其它类型（thinking 等）不参与文本；image/document
                            // 已在进入本分支前由 unsupportedBlockType 拒绝。
                        }
                    }
                    val txt = sb.toString().trim()
                    if (txt.isNotEmpty()) {
                        outMsgs.put(JSONObject().put("role", "user").put("content", txt))
                    }
                    for (d in 0 until deferred.length()) {
                        val blk = deferred.optJSONObject(d) ?: continue
                        outMsgs.put(
                            JSONObject()
                                .put("role", "tool")
                                .put("content", flattenToolResult(blk)))
                    }
                    if (txt.isEmpty() && deferred.length() == 0) {
                        return TranslateResult.Rejected(
                            "invalid_request_error",
                            "messages[$i].content: 为空或不支持的内容类型")
                    }
                }
                continue
            }

            // ---- 简单路径（纯字符串 / 纯 text blocks）：行为与首版完全一致 ----
            // content 可以是 string，也可以是 block 数组
            val text = flattenText(contentField, allowBlocks = true)
            if (text.isEmpty()) {
                return TranslateResult.Rejected(
                    "invalid_request_error", "messages[$i].content: 为空或不支持的内容类型")
            }
            outMsgs.put(JSONObject().put("role", role).put("content", text))
        }

        // ---- 组装 OpenAI body ----
        // 注意顺序：body 必须先声明，下面的 tools / tool_choice / 采样参数都往它里面写。
        val stream = req.optBoolean("stream", false)
        val body = JSONObject()
            .put("model", model)
            .put("messages", outMsgs)
            .put("max_tokens", maxTokens)
            .put("stream", stream)

        // 流式必须显式要 usage：Anthropic 的 message_delta 要带
        // input_tokens/output_tokens，而 OpenAI 流默认不保证有 usage 帧。
        // 用标准开关 `stream_options.include_usage`，由 handleChat 在收尾处
        // 补一个 usage-only chunk；非流式不受影响（它本来就有 usage）。
        if (stream) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        // ---- thinking -> 本端的思考控制 ----
        //
        // Anthropic 的扩展思考是 `{"type":"enabled","budget_tokens":N}` /
        // `{"type":"disabled"}`。旧实现把顶层 `thinking` 整块忽略，调用方以为
        // 自己设了预算，服务端却按默认开关跑 —— 与「字段收下但没生效」是同一类
        // 静默失败。这里翻成内部已经认的 `enable_thinking` / `reasoning_budget`：
        //   · enabled  -> enable_thinking=true + reasoning_budget=N
        //   · disabled -> enable_thinking=false
        // `budget_tokens` 必须小于 `max_tokens`（Anthropic 官方约束），
        // 否则不是"预算偏大"而是这条请求本身非法，翻译阶段就回 400。
        if (req.has("thinking") && !req.isNull("thinking")) {
            val thinking = req.opt("thinking")
            if (thinking !is JSONObject) {
                return TranslateResult.Rejected(
                    "invalid_request_error", "thinking: 必须是对象")
            }
            when (val type = thinking.optString("type", "")) {
                "enabled" -> {
                    val rawBudget = thinking.opt("budget_tokens")
                    val budget = when (rawBudget) {
                        is Number -> rawBudget.toDouble()
                        is String -> rawBudget.trim().toDoubleOrNull()
                        else -> null
                    }
                    if (budget == null || !budget.isFinite() || budget != Math.floor(budget) ||
                        budget < 1 || budget > Int.MAX_VALUE.toDouble()) {
                        return TranslateResult.Rejected(
                            "invalid_request_error",
                            "thinking.budget_tokens: 必须是正整数")
                    }
                    if (budget >= maxTokens) {
                        return TranslateResult.Rejected(
                            "invalid_request_error",
                            "thinking.budget_tokens: 必须小于 max_tokens（$maxTokens）")
                    }
                    body.put("enable_thinking", true)
                    body.put("reasoning_budget", budget.toInt())
                }
                "disabled" -> body.put("enable_thinking", false)
                else -> return TranslateResult.Rejected(
                    "invalid_request_error",
                    "thinking.type: 只支持 enabled / disabled（收到 \"$type\"）")
            }
        }

        // ---- tools：Anthropic -> OpenAI OAI-compat 形状（结构差异，必须转换）----
        //
        // 本端把 tools 原样交给 native（`ToolCalls.toolsJson` -> `a.toString()`），
        // 由 `common_chat_parse` 按模板推导 PEG 解析 —— 而它吃的是 OpenAI 的
        // OAI-compat 形状（`{type:"function",function:{name,description,parameters}}`）。
        // Anthropic 的形状是 `{name,description,input_schema}`，字段名不同，
        // **不转换 native 会解析不出任何工具**（这正是"看起来支持、实际没生效"）。
        val anthropicTools = req.optJSONArray("tools")
        if (anthropicTools != null && anthropicTools.length() > 0) {
            val oaTools = JSONArray()
            for (t in 0 until anthropicTools.length()) {
                val src = anthropicTools.optJSONObject(t)
                    ?: return TranslateResult.Rejected(
                        "invalid_request_error", "tools[$t]: 不是对象")
                val name = src.optString("name", "")
                if (name.isEmpty()) {
                    return TranslateResult.Rejected(
                        "invalid_request_error", "tools[$t].name: 不能为空")
                }
                val fn = JSONObject().put("name", name)
                if (src.has("description") && !src.isNull("description")) {
                    fn.put("description", src.optString("description", ""))
                }
                // Anthropic 的 input_schema <-> OpenAI 的 parameters
                val schema = src.opt("input_schema")
                fn.put("parameters", schema ?: JSONObject().put("type", "object"))
                oaTools.put(JSONObject().put("type", "function").put("function", fn))
            }
            body.put("tools", oaTools)
        }

        // ---- tool_choice：Anthropic -> OpenAI ----
        // Anthropic: {"type":"auto"|"any"|"tool", "name":...}
        // OpenAI:    "auto" | "required" | "none"
        //
        // ⚠ 本端 native 的 `common_chat_tool_choice` 只有 AUTO / REQUIRED / NONE
        //   三个枚举，**没有"指定函数名"这一档**（`chat.h`）。而 handleChat 的
        //   `ToolCalls.parseToolChoice` 对对象形式一律归一成 AUTO —— 所以把
        //   `type:"tool"` 直接翻成 OpenAI 的 function 对象，到了 native 会静默
        //   退化成 auto：模型可能不调用工具，也可能调用别的工具，而调用方以为
        //   自己已经强制指定了。这里改成 **只保留被选中的那个工具 + required**：
        //   模型能看到的工具只剩它，等价于"必须调用这个"。
        var forcedToolName: String? = null
        if (req.has("tool_choice") && !req.isNull("tool_choice")) {
            when (val tc = req.opt("tool_choice")) {
                is String -> body.put("tool_choice", tc)
                is JSONObject -> {
                    when (tc.optString("type", "")) {
                        "any" -> body.put("tool_choice", "required")
                        "none" -> body.put("tool_choice", "none")
                        "tool" -> {
                            val n = tc.optString("name", "")
                            if (n.isEmpty()) {
                                return TranslateResult.Rejected(
                                    "invalid_request_error",
                                    "tool_choice.name: type=tool 时必须给出工具名")
                            }
                            forcedToolName = n
                            body.put("tool_choice", "required")
                        }
                        else -> body.put("tool_choice", "auto") // "auto" / 未知类型
                    }
                }
                else -> body.put("tool_choice", "auto")
            }
        }
        // 指定工具名时把 tools 收窄到那一个。找不到就明确报错：不能让它退化成
        // "required + 全部工具"（模型可能调用别的工具，语义已经不是调用方要的了）。
        forcedToolName?.let { name ->
            val all = body.optJSONArray("tools")
            if (all == null || all.length() == 0) {
                return TranslateResult.Rejected(
                    "invalid_request_error",
                    "tool_choice: 指定了工具 \"$name\"，但请求没有 tools")
            }
            val filtered = JSONArray()
            for (i in 0 until all.length()) {
                val tool = all.optJSONObject(i) ?: continue
                val fn = tool.optJSONObject("function") ?: continue
                if (fn.optString("name", "") == name) filtered.put(tool)
            }
            if (filtered.length() == 0) {
                return TranslateResult.Rejected(
                    "invalid_request_error",
                    "tool_choice.name: \"$name\" 不在请求的 tools 里")
            }
            body.put("tools", filtered)
        }

        // ---- parallel_tool_calls：本端有同名参数（applyChatTemplateWithTools）----
        // Anthropic 侧是 `disable_parallel_tool_use`（在 tool_choice 对象里），
        // 这里做一次语义取反映射；顶层同名参数直接透传。
        copyIfPresent(req, body, "parallel_tool_calls")
        if (req.has("tool_choice") && !req.isNull("tool_choice")) {
            val tc = req.opt("tool_choice")
            if (tc is JSONObject && tc.has("disable_parallel_tool_use")) {
                body.put(
                    "parallel_tool_calls",
                    !tc.optBoolean("disable_parallel_tool_use", false))
            }
        }


        // 采样参数：只在存在时透传，避免用默认值覆盖本端已调好的原生默认
        copyIfPresent(req, body, "temperature")
        copyIfPresent(req, body, "top_p")

        // `top_k` -> `top_k`（同名透传，但先做整数校验）
        // 本端 [SamplingParams.fromRequest] 确实读 `top_k`（SamplingParams.kt:155，
        // `readInt(j, "top_k", DEF_TOP_K)`），所以透传是有效传递，不是摆设。
        // 语义一致：都表示"只从概率最高的 k 个 token 里采样"。
        //
        // 但本端 `top_k` 走 `readInt`，要求整数值（SamplingParams.kt:265 会检查
        // `d == Math.floor(d)`），而非整数会被 400。Anthropic 官方文档把它当
        // number，客户端传 `5.0` 能过（floor 相等），传 `5.5` 就在本端 400。
        // 这里提前拦成 Anthropic 格式的 400，避免调用方拿到一句本端口径的
        // "top_k 必须是整数" 而不知道是自己发的浮点惹的祸。
        if (req.has("top_k") && !req.isNull("top_k")) {
            val raw = req.opt("top_k")
            val d = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            }
            if (d == null || !d.isFinite() || d != Math.floor(d)) {
                return TranslateResult.Rejected(
                    "invalid_request_error",
                    "top_k: 必须是整数（本端底层按整数 top-k 采样，不接受小数）")
            }
            body.put("top_k", d.toInt())
        }

        // stop_sequences -> stop_sequences（**原名透传，不改名、不过滤**）
        //
        // 本端 [StopSequences.fromRequest] 同时认 `stop` 与 `stop_sequences`（取并集，
        // StopSequences.kt:83），且**自己**做全部校验：非字符串元素报 errNotString、
        // 空串报 errEmpty、超长报 errTooLong、类型不对报 errType。
        //
        // 早先这里写的是「改名成 stop + 只挑 String 元素」，有两个真问题：
        //   1. `(it as? String)?.let { ... }` 会把非字符串元素**静默丢掉** ——
        //      本该是 400 的 `stop_sequences: [123]` 变成"看起来没给 stop"，请求照跑。
        //   2. 只认 JSONArray，传单字符串（fromRequest 是支持的）会被整块跳过。
        // 二者都是"把明确的错误变成静默忽略"，故改为原样透传：只留 fromRequest 一层
        // 判据，不再有第二份可能漂移的逻辑。
        //
        // 但要给调用方一句 Anthropic 口径的错误：fromRequest 的提示是中文本端口径
        // （如"stop 数组元素必须都是字符串"）。这里不做完整复刻（空串/超长仍交给
        // fromRequest 判），只拦"类型明显不对"这一类，给出与 Anthropic 文档一致的 400。
        if (req.has("stop_sequences") && !req.isNull("stop_sequences")) {
            val v = req.opt("stop_sequences")
            val typeOk = when (v) {
                is String -> true
                is JSONArray -> (0 until v.length()).all { v.opt(it) is String }
                else -> false
            }
            if (!typeOk) {
                return TranslateResult.Rejected(
                    "invalid_request_error",
                    "stop_sequences: 必须是字符串或字符串数组")
            }
        }
        copyIfPresent(req, body, "stop_sequences")

        return TranslateResult.Ok(body, model, req.optBoolean("stream", false))
    }

    /**
     * 找出本端无法消费的 content block 类型；没有则返回 null。
     *
     * 允许：
     *  - `text`：正文；
     *  - `tool_use` / `tool_result`：走结构化工具路径（tool_result 的嵌套
     *    content 也会递归检查，避免图片藏在工具结果里被静默丢掉）；
     *  - `thinking` / `redacted_thinking`：模型侧元数据，忽略不影响正文。
     *
     * 其它类型（`image` / `document` / `search_result` / `server_tool_use` …）
     * 一律返回类型名，由调用方回 400。**不能静默忽略**：调用方会以为模型
     * 看过图/文档，实际只收到文本，答案错得没有任何线索。
     */
    private fun unsupportedBlockType(value: Any?): String? {
        if (value !is JSONArray) return null
        for (i in 0 until value.length()) {
            val blk = value.optJSONObject(i) ?: continue
            when (val type = blk.optString("type", "")) {
                "", "text", "tool_use", "thinking", "redacted_thinking" -> Unit
                "tool_result" -> unsupportedBlockType(blk.opt("content"))?.let { return it }
                else -> return type
            }
        }
        return null
    }

    /**
     * 把 Anthropic 的 `content`（string 或 block 数组）或 `system`
     * 压平成一段纯文本。block 里 `type == "text"` 取 `text`；
     * 其它类型由 [unsupportedBlockType] 在进入本函数之前校验/拒绝，
     * 这里只做"已知可忽略的 block 跳过"。
     *
     * 注意：此函数保证**只返回 String**，不支持的内容由上层校验兜底；
     * 对 system 更宽容（system 一般只有 text block）。
     */
    private fun flattenText(value: Any?, allowBlocks: Boolean): String {
        return when (value) {
            null -> ""
            is String -> value
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until value.length()) {
                    val blk = value.opt(i)
                    if (blk is String) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(blk)
                    } else if (blk is JSONObject) {
                        val type = blk.optString("type", "")
                        if (type == "text" || type.isEmpty()) {
                            val t = blk.optString("text", "")
                            if (t.isNotEmpty()) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(t)
                            }
                        }
                        // 其它 block 类型（tool_use/tool_result/thinking…）：
                        // 结构转换在别处，这里只拍平文本；image/document 已在
                        // 进入本函数前被 [unsupportedBlockType] 拒绝。
                    }
                }
                sb.toString()
            }
            is JSONObject -> {
                val t = value.optString("text", "")
                t
            }
            else -> value.toString()
        }.trim()
    }

    private fun copyIfPresent(from: JSONObject, to: JSONObject, key: String) {
        if (from.has(key) && !from.isNull(key)) {
            to.put(key, from.opt(key))
        }
    }

    /** block 数组里是否含 tool_use / tool_result（据此决定走结构化路径）。 */
    private fun hasToolBlocks(arr: JSONArray): Boolean {
        for (i in 0 until arr.length()) {
            val blk = arr.optJSONObject(i) ?: continue
            val type = blk.optString("type", "")
            if (type == "tool_use" || type == "tool_result") return true
        }
        return false
    }

    /**
     * 把 assistant 的 `tool_use` block 描述成一段文本。
     *
     * ⚠ 这是**有损**的：本端消息模型（role + 一段文本）没有结构化 tool_calls 输入位，
     * 所以已发生的工具调用只能以文本形式回放进上下文。格式刻意贴近本端模板在
     * 渲染 tool_calls 时的常见形态，便于模型续接；但**两侧格式无共同真源**，
     * 如需严格无损，须改本端消息模型（见类注释）。
     */
    private fun describeToolUse(blk: JSONObject): String {
        val name = blk.optString("name", "")
        val input = blk.opt("input")
        val inputStr = when (input) {
            null -> "{}"
            is JSONObject -> input.toString()
            else -> input.toString()
        }
        return "[tool_call] $name($inputStr)"
    }

    /**
     * 把 `tool_result` block 的内容拍平成一段文本。
     * `content` 可以是字符串，也可以是 block 数组（只取 text 段）。
     */
    private fun flattenToolResult(blk: JSONObject): String {
        val c = blk.opt("content")
        val txt = flattenText(c, allowBlocks = true)
        // 显式带上 tool_use_id：本端 role="tool" 消息没有独立 id 字段，
        // 放在文本里能让模型（以及排障的人）看出这是对哪次调用的回应。
        val id = blk.optString("tool_use_id", "")
        return if (id.isNotEmpty()) "[tool_result $id] $txt" else txt
    }

    /** 翻译结果。 */
    sealed class TranslateResult {
        data class Ok(
            val openAiBody: JSONObject,
            val model: String,
            val stream: Boolean,
        ) : TranslateResult()

        data class Rejected(val errorType: String, val message: String) : TranslateResult()
    }
}
