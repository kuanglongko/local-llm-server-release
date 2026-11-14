package com.xiaowan.localinference

import org.json.JSONObject

/**
 * 「思考开关」的**纯逻辑**部分：请求侧参数解析 + prompt 侧软开关注入。
 *
 * 为什么单独抽一个文件：这套逻辑原本**在两个地方各写了一遍**——
 * `HttpApi.handleChat`（HTTP 入口）与 `EngineActivity.doGenerate`（App 内聊天），
 * 两边取值来源不同（一个读全局默认 + 请求覆盖，一个只读持久化开关），
 * 而"注入空 think 块"的写法却必须逐字一致。重复实现的结果就是修一处、漏一处。
 * 所以这里把两条路径收敛到同一组函数上；写法只有一处，改一次两边都动。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 优先让**模型模板自己关**，软开关只在模板关不掉时兜底
 * ══════════════════════════════════════════════════════════════════════════
 * 这是本文件最重要的一条，2026-09-19 真机报障（客户端多出一个 think 标签 + 一直在推理）
 * 就是没守住它。
 *
 * MiniCPM5 的模板（`tools/minicpm5_fixture/chat_template.jinja`）尾部是：
 *
 *     {%- if enable_thinking is defined %}
 *         {%- if enable_thinking is false %}{{- '<think>\n\n</think>\n\n' }}
 *         {%- elif enable_thinking is true %}{{- '<think>\n' }}
 *
 * 也就是说：**模板自带一套完整的开关**，关掉时由它自己吐完整闭合块
 * （`<|im_start|>assistant\n<think>\n\n</think>\n\n`），比宿主拼出来的更准
 * （它知道自己的换行怎么写）。此时宿主再叠一个软开关，拼出来是这样：
 *
 *     渲染结果   …<|im_start|>assistant\n<think>\n        ← 模板自己吐的（思考开）
 *     软开关后   …<|im_start|>assistant\n<think>\n\n</think>\n\n\n
 *                                          ↑ 裸 </think>，前面只有 1 个 \n
 *
 * 两个后果，都在客户端可见：
 *   ① 模板那个 `<think>` 与宿主补的 `</think>` 之间只剩 **1 个换行**，
 *      模型必然把这一轮推理写在这 1 字节里，于是 `</think>` 永远紧跟在
 *      **推理文字**后面，而不是紧跟在标记后面 —— 裸标签漏进正文；
 *   ② 这与模型在**思考开**时的自然输出形状（`<think>推理</think>`）几乎一样，
 *      模型会顺着它先"复盘"一遍再回答 —— 表现就是"一直在自己推理，很久才结束"。
 *
 * 所以对本类模板，正确做法是**让位**：软开关不注入，由库把 `enable_thinking=false`
 * 渲染进去，模板自己吐那段完整闭合块（见 `LlmEngine.applyChatTemplate` 的 thinkingOn）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 软开关真正必要的地方：模板里没有 enable_thinking 变量
 * ══════════════════════════════════════════════════════════════════════════
 * LFM2.5 这类模型模板里**没有** `enable_thinking` 变量
 * （`common_chat_templates_support_enable_thinking` 对它返回 false），
 * 参数传下去也不改变任何东西；而它开启思考时的生成后缀**硬编码就是**
 * `<|im_start|>assistant\n<think>\n`。库关不掉，唯一能关掉它的办法恰好就是
 * 往这个后缀后面补上闭合段 —— 也就是软开关本身，**而且这里才是软开关真正必要的地方**。
 * 对这类模板 `enable_thinking` 传什么都不会重复，所以不注入反而关不掉思考，必须注入。
 *
 * 判据因此是（见 [softSwitchApplies]）：
 *   · 模板含 `enable_thinking`          → **让位**，交给库/模板自己关，不注入；
 *   · 模板不含、但生成后缀自带未闭合的 `<think>` → 宿主软开关注入（LFM2.5）；
 *   · 两者都不满足                       → 不注入（Gemma / Qwen-Instruct 本就没有思考段，
 *     不看就注入只会往 prompt 里塞一对没人认识的标签，模型只能当正文吐出来）。
 *
 * 判断依据只用**模板原文与渲染结果**，不打模型名/文件名的主意：
 * `llama_model_chat_template` 返回的是模型自带的 Jinja 原文（不带 `apply_chat_template`
 * 包装），不渲染一遍是看不出后缀长什么样的。
 */
object ThinkingControl {

    /** assistant 生成后缀里若出现这些标记，说明模板默认就要模型先"想一段"。 */
    private val THINKING_START_MARKERS = listOf("<think>")

    /**
     * 注入在 assistant 生成后缀**之后**的空 think 块。
     *
     * 位置不能挪到 prompt 开头：那份 prompt 已经以 assistant 生成后缀
     * （如 `<|im_start|>assistant\n<think>\n`）结尾，把空 think 块紧贴其后，等于替模型"预填"了
     * 一段已经闭合的思考段，模型直接续写正文即可，等价于思考被关掉。
     * 而放在最前面时，模型看到的是一个「先输出 `<think>` 的例子」，会照抄一遍——
     * 表现就是"设了关闭思考，模型还是先想一段"。
     *
     * **只在模板不带 `enable_thinking` 时使用**（见文件头）：带变量的模板由库关，
     * 宿主再补一个开标签会与模板后缀自带的那个形成"两个 `<think>`"。
     */
    const val EMPTY_THINK_BLOCK = "<think>\n\n</think>\n\n"

    /**
     * prompt 软开关：关闭思考时把 [EMPTY_THINK_BLOCK] 补进渲染好的 prompt。
     *
     * 只处理「模板关不掉」的那一类（LFM2.5）：后缀已经把 `<think>` 吐出来了，
     * 此时在末尾再追加整块会多出一个开标签，所以从最后一个开标签**之后**插进去、
     * 只补闭合部分，让它与那个开标签配对：
     *
     *     <|im_start|>assistant\n<think>\n   →   <|im_start|>assistant\n<think>\n\n</think>\n\n\n
     *
     * 注意拼出来的尾部**不一定**与模板自己关闭时逐字节相同（LFM2.5 本来就没有那种后缀，
     * 无对照物）；对**带** `enable_thinking` 的模板本函数不会被执行到 ——
     * 那类模板自己吐的那段才是权威，宿主拼不出它的换行细节。
     *
     * 模板不含 `enable_thinking`、后缀里也没有思考标记时（Gemma / Qwen-Instruct）
     * 原样返回：不看模板就注入，只会往 prompt 里塞一对没人认识的标签，模型只能当正文吐出来。
     */
    fun applyToPrompt(prompt: String, thinkingOn: Boolean, chatTemplate: String): String =
        if (!softSwitchApplies(thinkingOn, chatTemplate, prompt)) prompt
        else appendAfterLastThinkTag(prompt)

    /**
     * 空 think 块要补进去的位置：渲染结果里**最后一个**思考起始标记之后。
     *
     * 为什么不能简单地在末尾追加：LFM2.5 的生成后缀是 `<|im_start|>assistant\n<think>\n`，
     * 它已经把 `<think>` 吐出来了（还没闭合）。这时在**整个 prompt 末尾**追加
     * `<think>\n\n</think>\n\n` 会得到：
     *
     *     <|im_start|>assistant\n<think>\n<think>\n\n</think>\n\n
     *                                  ↑ 多出来的第二个 <think>
     *
     * 模型接下来续写的是正文，于是最早那个 `<think>` 一直没有闭合，
     * 整段回答被下游的 think 状态机当成思考段吞掉（表现为"回答为空"）。
     * 正确做法是从最后一个 `<think>` **之后**插进去，闭合段与它配对：
     *
     *     <|im_start|>assistant\n<think>\n\n</think>\n\n
     *
     * 对后缀里不含 `<think>` 的模板，`lastIndexOf` 返回 -1，
     * 插入位置就是字符串末尾。
     */
    private fun appendAfterLastThinkTag(prompt: String): String {
        val marker = THINKING_START_MARKERS[0]
        val at = prompt.lastIndexOf(marker)
        // 后缀里已经有 <think>（还没闭合）：把闭合段插到它后面，别再多吐一个开标签。
        // 没有则追加到末尾。
        if (at < 0) return prompt + EMPTY_THINK_BLOCK
        val afterOpen = at + marker.length
        return prompt.substring(0, afterOpen) + EMPTY_THINK_BLOCK.substring(marker.length) + prompt.substring(afterOpen)
    }

    /**
     * 让**历史里的 assistant 段**与本轮生成后缀里的空 think 块逐字节一致（KV 前缀复用）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须有这一步（2026-11-09 真机：Qwen3.5 4B + 知识库，每轮全量 prefill）
     * ══════════════════════════════════════════════════════════════════════
     * Qwen3.5 的模板在 `add_generation_prompt` + `enable_thinking=false` 时，会在
     * 生成后缀里补一段**空的闭合 think 块**：
     *
     *     <|im_start|>assistant\n<think>\n\n</think>\n\n
     *
     * 但同一份模板在**回放历史** assistant 消息时，走的是另一支
     * （`loop.index0 <= last_query_index`），只渲染 `<|im_start|>assistant\n{content}` ——
     * 那段空 think 块**没有**。于是下一轮的 prompt 与上一轮相比，在上一轮末尾
     * **往前数 4 枚**（`<think>` / `\n\n` / `</think>` / `\n\n`）处就分叉：
     * 上一轮是空 think 块，这一轮是上一轮回答的第一个字。
     *
     * 对普通 transformer，这 4 枚只是让 `reuse` 少一点；但对**混合 / 循环记忆**
     * （Qwen3.5 就是 hybrid）是致命的：循环态不能部分回滚，唯一能回滚的
     * 快照存的是**上一轮 prompt 的末尾**（= 第 P 枚，含那 4 枚空 think），
     * 而本轮需要回到 P-4 —— 快照「太长」用不了，于是每一轮都整条清空、
     * 全量 prefill。日志里就是固定形状：
     *
     *     命中 136 tok、上一轮已生成到 169 tok，但需要回滚到第 136 枚；
     *     快照不可用（现有快照 140 tok，条件不满足或恢复失败）
     *
     * 140 - 136 = 4，每一轮都差这 4 枚。修法不是在 KV 侧硬回滚（循环态回不去），
     * 而是**把历史回放拼成模型当时真正看到的那一段**：上一轮生成时模型已经看过
     * 这段空 think 块，回放时补回去，上一轮 prompt 才成为这一轮的**真前缀**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 只在判据全部成立时动手（任一条不成立都原样返回）
     * ══════════════════════════════════════════════════════════════════════
     *   · 生成后缀 = assistant 起始标记 + [EMPTY_THINK_BLOCK]；
     *   · prompt 确实以这段后缀结尾；
     *   · 历史 assistant 段后面还没自带 `<think>`（避免给「思考开」的轮次叠第二段）。
     *
     * 其它模板（生成后缀是裸的 `<|im_start|>assistant\n`、或未闭合的 `<think>\n`）
     * 一律 no-op —— 它们本来就没有这段不对称，乱插只会改坏 prompt。
     *
     * @param prompt 渲染好的完整 prompt（以生成后缀结尾）
     * @param generationSuffix 渲染侧给出的**真后缀**（[RenderedPrompt.generationSuffix]）；
     *   null = 未知，按 no-op 处理（宁可少省一次，也不能把形状猜错）
     */
    fun alignHistoryThinkBlock(prompt: String, generationSuffix: String?): String {
        if (prompt.isEmpty() || generationSuffix.isNullOrEmpty()) return prompt
        val marker = "<|im_start|>assistant\n"
        if (!generationSuffix.startsWith(marker)) return prompt
        // 只认「空的闭合 think 块」这一种后缀；`<think>\n`（思考开）或裸后缀都不是本故障。
        if (generationSuffix.substring(marker.length) != EMPTY_THINK_BLOCK) return prompt
        if (!prompt.endsWith(generationSuffix)) return prompt
        val head = prompt.substring(0, prompt.length - generationSuffix.length)
        // 只认**消息边界**上的 assistant 起始标记：ChatML 的历史消息一律以
        // `<|im_end|>\n` 收尾。正文里恰好出现同样的字面量（用户粘进来的文本）时
        // 不能往里插 —— 那是改内容，不是回放。
        val endOfMsg = "<|im_end|>\n"
        val sb = StringBuilder(head.length + EMPTY_THINK_BLOCK.length)
        var from = 0
        while (true) {
            val at = head.indexOf(marker, from)
            if (at < 0) {
                sb.append(head, from, head.length)
                break
            }
            val end = at + marker.length
            sb.append(head, from, end)
            val boundary = at == 0 ||
                (at >= endOfMsg.length && head.regionMatches(at - endOfMsg.length, endOfMsg, 0, endOfMsg.length))
            // 历史段若已自带思考块（模板的「最后一条 assistant」分支 / 思考开的历史轮），
            // 不要重复插 —— 那样会拼出两个 `<think>`，比原来的 4 枚分叉更糟。
            if (boundary && !head.startsWith("<think>", end)) sb.append(EMPTY_THINK_BLOCK)
            from = end
        }
        return sb.toString() + generationSuffix
    }

    /**
     * [applyToPrompt] 的判定部分单独暴露，便于日志与单测直接断言"这次到底注没注进去"。
     *
     * @param renderedPrompt 渲染好的 prompt（判"生成后缀自带思考标记"需要它）
     */
    fun softSwitchApplies(
        thinkingOn: Boolean,
        chatTemplate: String,
        renderedPrompt: String = "",
    ): Boolean {
        if (thinkingOn) return false
        // 模板自带 enable_thinking 开关 -> **让位**，由库把它渲进 prompt、模板自己吐完整
        // 闭合块（MiniCPM5 就是这一支）。宿主再补一段会与模板后缀自带的 <think> 构成
        // "两个开标签"，而且补出来的闭合段前面只有 1 个换行 —— 裸 </think> 漏进正文，
        // 模型还会顺着这个形状把已经想过的东西再"复盘"一遍。详见文件头。
        if (templateSupportsEnableThinking(chatTemplate)) return false
        // 模板关不掉、但后缀确实自带思考起始标记（LFM2.5）-> 这里才是软开关必须上场的地方。
        return renderedPromptEndsWithThinkingStart(renderedPrompt)
    }

    /** 模板是否带 `enable_thinking` 变量（Qwen3 / SmolLM3 / MiniCPM5 一类可参数化思考的模板）。 */
    fun templateSupportsEnableThinking(chatTemplate: String): Boolean =
        chatTemplate.contains("enable_thinking")

    /**
     * 渲染结果的生成后缀里是否自带思考起始标记（LFM2.5 一类把思考写死在模板里的模型）。
     *
     * 只看**生成后缀**（末尾那一段 assistant 起始标记），不扫全文：
     * 历史轮次里出现过 `<think>` 不代表这一轮模型会先想一段。
     *
     * ⚠ 判据本体是 [ThinkStream.classifyTail] —— 这里只做转发，**不得**另写一套。
     * 本文件曾是同一判据的**第三份**实现：那份的窗口取的是 256 个 **UTF-16 code unit**，
     * 而 C++ 侧 `classify_rendered_think_tail` 取的是 256 **字节**（256 字节 ≈ 85 个
     * 汉字 → 覆盖范围差 3 倍），与 `RenderedPrompt.openAtStartForTest` 那份一起
     * 构成"两处都漂了却全绿"。三处收口到同一个函数，单位由守卫正面断言。
     */
    fun renderedPromptEndsWithThinkingStart(renderedPrompt: String): Boolean {
        if (renderedPrompt.isEmpty()) return false
        return ThinkStream.classifyTail(renderedPrompt) == ThinkStream.Companion.TailShape.openOnly
    }

    /**
     * 请求侧的思考控制：显式开关 + `reasoning_budget` + `reasoning_effort` +
     * `reasoning_format`。
     *
     * 三者不是三个同义词，优先级与语义都不同：
     *
     *   · `enable_thinking` / `chat_template_kwargs.enable_thinking` —— **显式开关**；
     *   · `reasoning_budget` —— `0` 明确关闭，`>0` 明确开启并给出思考上限，
     *     `-1` 表示"不限"（不改变开关，交给全局默认）；
     *   · `reasoning_effort` —— **档位建议**，不是开关。服务端默认关思考时，
     *     单单一个 `reasoning_effort=high` 不得把它顶开（很多 SDK 会不带条件地
     *     发 `medium`，旧实现把它当"非 none 即开"，于是"默认关闭思考"被一条
     *     无关字段悄悄推翻）。要显式开启，用 `enable_thinking=true` 或
     *     `reasoning_budget>0`。
     *
     * `reasoning_format` 只影响**输出怎么切分**（是否把思考段放进
     * `reasoning_content`），**不传给 native**。native 的同名字段会改变 PEG /
     * grammar 的产生式（思考块变成可选前缀），那会与结构化输出的
     * `gbnf_realign_root_literal` 对齐逻辑相互踩踏 —— 那条链的语义不能因为
     * "换一个响应形状"而被悄悄改写。宿主已经有唯一的 `ThinkStream` 状态机，
     * 这里只决定要不要用它。
     */
    data class Reasoning(
        /** 显式开关；null = 没表态。 */
        val enableThinking: Boolean?,
        /** null = 没给；-1 = 不限；0 = 关；>0 = 思考上限（token）。 */
        val budget: Int?,
        /** null = 没给（未知取值会直接 400，不会落成"没表态"）。 */
        val effort: Effort?,
        /** null = 没给（= 保持既有行为：抽到 `reasoning_content`）。 */
        val format: Format?,
        /** 必须落日志的说明：被更高优先级覆盖、预算被钳等。 */
        val notices: List<String>,
    )

    /**
     * `reasoning_format`：响应里思考段怎么摆。
     *
     * 取值与 llama.cpp 的同名服务端参数一致，但这里**只做响应侧切分**：
     *   · `none` —— 不切，`<think>` 标签原样留在 `content` 里；
     *   · `auto` / `deepseek` —— 切到 `reasoning_content`（当前默认行为）；
     *   · `deepseek-legacy` —— 非流式切、流式保持标签内联（旧版 deepseek 行为）。
     */
    enum class Format(val wire: String) {
        NONE("none"),
        AUTO("auto"),
        DEEPSEEK("deepseek"),
        DEEPSEEK_LEGACY("deepseek-legacy"),
    }

    /** `reasoning_effort` 的合法档位；预算到点由 native 采样器强制收尾（见 HttpApi / llama_jni）。 */
    enum class Effort(val wire: String) {
        MINIMAL("minimal"),
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high"),
        NONE("none"),
    }

    /**
     * **设置页**里的全局默认（App 与 HTTP 共用一份）。
     *
     * 与 [Reasoning] 的区别：那个是**这一次请求**的表态，这个是"请求没表态时用哪档"。
     * 请求永远压过默认 —— 因此默认值**不参与**请求侧的冲突校验（例如全局
     * `budget=0` 与请求 `enable_thinking=true` 不冲突，后者直接生效）。
     *
     * 三个字段都可能为 null，全为 null 时调用方拿到 null（= 全局默认关闭，行为与
     * 引入这些设置之前逐字节相同）。
     */
    data class Defaults(
        val budget: Int?,
        val effort: Effort?,
        val format: Format?,
    )

    const val errEnableThinking =
        "enable_thinking 必须是布尔值（也接受 true/false/1/0/on/off）"
    const val errReasoningBudget =
        "reasoning_budget 必须是 >= -1 的整数（-1 = 不限，0 = 关闭思考）"
    const val errReasoningEffort =
        "reasoning_effort 只支持 none / minimal / low / medium / high"
    const val errReasoningFormat =
        "reasoning_format 只支持 none / auto / deepseek / deepseek-legacy"
    const val errBudgetOffConflict =
        "enable_thinking=true 与 reasoning_budget=0 冲突（一个要开、一个要关）"
    const val errBudgetOnConflict =
        "enable_thinking=false 与 reasoning_budget>0 冲突（一个要关、一个要开）"

    /**
     * 解析请求里的思考控制字段。返回 (结果, 错误信息) 二选一。
     *
     * 缺省（字段不存在 / null / 空串）永远走旧行为：不产生任何覆盖。
     * 这与 `ProtocolFields` / `SamplingParams` 的"缺省不得改变既有行为"同一条。
     */
    fun parseReasoning(j: JSONObject): Pair<Reasoning?, String?> {
        val notices = ArrayList<String>()

        // `has` 对 null 返回 true，而 `optBoolean` 对 JSONObject.NULL 会回落到默认值 ——
        // 于是 {"enable_thinking":null} 曾被读成 **true**（开思考），与「没表态」相反。
        // 判据与 SamplingParams.read 对齐：null 视为没给，落到后面的链上。
        val kwEt = j.optJSONObject("chat_template_kwargs")?.opt("enable_thinking")
        val topRaw = if (j.has("enable_thinking") && !j.isNull("enable_thinking")) {
            j.opt("enable_thinking")
        } else null
        val kwRaw = if (kwEt != null && kwEt != JSONObject.NULL) kwEt else null

        val topSwitch = topRaw?.let { switchValue(it) ?: return null to errEnableThinking }
        val kwSwitch = kwRaw?.let { switchValue(it) ?: return null to errEnableThinking }
        val explicit = topSwitch ?: kwSwitch
        if (topSwitch != null && kwSwitch != null && topSwitch != kwSwitch) {
            notices += "enable_thinking=$topSwitch 覆盖了 chat_template_kwargs.enable_thinking=$kwSwitch"
        }

        val budget = when (val r = readInt(j, "reasoning_budget")) {
            is IntRead.Absent -> null
            is IntRead.Bad -> return null to errReasoningBudget
            is IntRead.Ok -> {
                if (r.value < -1) return null to errReasoningBudget
                r.value
            }
        }

        val effortRaw = if (j.has("reasoning_effort") && !j.isNull("reasoning_effort")) {
            j.opt("reasoning_effort")
        } else null
        val effort = when (effortRaw) {
            null -> null
            is String -> {
                if (effortRaw.trim().isEmpty()) null
                else effortOf(effortRaw) ?: return null to errReasoningEffort
            }
            else -> return null to errReasoningEffort
        }

        val formatRaw = if (j.has("reasoning_format") && !j.isNull("reasoning_format")) {
            j.opt("reasoning_format")
        } else null
        val format = when (formatRaw) {
            null -> null
            is String -> {
                if (formatRaw.trim().isEmpty()) null
                else formatOf(formatRaw) ?: return null to errReasoningFormat
            }
            else -> return null to errReasoningFormat
        }

        // 显式开关与预算的冲突必须报 400，不能替调用方选一个：
        // 一个说"开"、一个说"关"，静默选哪个都会让另一半失效且看不出来。
        if (explicit == true && budget == 0) return null to errBudgetOffConflict
        if (explicit == false && budget != null && budget > 0) return null to errBudgetOnConflict

        if (explicit != null && effort != null) {
            val effortOn = effort != Effort.NONE
            if (explicit != effortOn) {
                notices += "reasoning_effort=${effort.wire} 被显式 enable_thinking=$explicit 覆盖"
            }
        }
        if (budget != null && budget != -1 && effort != null) {
            val budgetOn = budget > 0
            if (budgetOn != (effort != Effort.NONE)) {
                notices += "reasoning_effort=${effort.wire} 被 reasoning_budget=$budget 覆盖"
            }
        }

        return Reasoning(explicit, budget, effort, format, notices) to null
    }

    /**
     * 这一轮要不要把思考段切到 `reasoning_content`。
     *
     * 缺省（null）= 切：这是引入 `reasoning_format` 之前的行为，必须逐字节保持。
     * `deepseek-legacy` 的流式形态把标签留在 `content` 里，非流式才切 —— 与
     * llama.cpp 的同名档位一致。
     */
    fun extractReasoning(format: Format?, stream: Boolean): Boolean = when (format) {
        null, Format.AUTO, Format.DEEPSEEK -> true
        Format.NONE -> false
        Format.DEEPSEEK_LEGACY -> !stream
    }

    /**
     * 解析设置页存的**全局默认**（原始串）。
     *
     * 与 [parseReasoning] 的关键差异：这里**永不失败**。设置页存的是一份长期状态，
     * 里面出现不认识的值（用户手改 prefs、升级后残留）只能当作"没设过" ——
     * 若在这里报错，服务会从启动起就拒绝所有请求，而用户看不出是哪一格导致的。
     *
     * 空白串 = 没设过。三者全空 -> 返回 null，调用方据此走"完全没有全局默认"的老路径。
     */
    fun parseDefaults(
        budgetRaw: String?,
        effortRaw: String?,
        formatRaw: String?,
    ): Defaults? {
        val budget = budgetRaw?.trim()?.takeIf { it.isNotEmpty() }
            ?.toIntOrNull()?.takeIf { it >= -1 }
        val effort = effortRaw?.trim()?.takeIf { it.isNotEmpty() }?.let { effortOf(it) }
        val format = formatRaw?.trim()?.takeIf { it.isNotEmpty() }?.let { formatOf(it) }
        if (budget == null && effort == null && format == null) return null
        return Defaults(budget, effort, format)
    }

    /**
     * 最终这一轮思考是开还是关。
     *
     * `reasoning_effort` 的非 none 档位**只能保持**全局默认，不能把服务端的
     * "默认关"顶成开 —— 这是本版与旧实现的唯一语义差异，也是修复"SDK 默认发
     * medium 就把思考打开"的那一刀。`none` 仍然明确关闭。
     *
     * 优先级（高 -> 低）：请求显式开关 > 请求 `reasoning_budget` > 请求
     * `reasoning_effort` > **设置页全局默认** > 全局"默认关闭思考"。
     *
     * 全局默认**必须排在请求之后**：设置页那一格是"没表态时用哪档"，请求只要
     * 表了态（哪怕只是 `reasoning_effort=none`）就不能被它推翻。
     */
    fun resolve(
        disableByDefault: Boolean,
        r: Reasoning,
        defaults: Defaults? = null,
    ): Boolean {
        r.enableThinking?.let { return it }
        r.budget?.let {
            if (it == 0) return false
            if (it > 0) return true
            // -1 = 不限：它不表达"开或关"，继续走后面的链。
        }
        if (r.effort == Effort.NONE) return false
        defaults?.budget?.let {
            if (it == 0) return false
            if (it > 0) return true
        }
        if (defaults?.effort == Effort.NONE) return false
        return !disableByDefault
    }

    /**
     * 这一轮实际生效的 `reasoning_format`：请求 > 设置页全局默认 > null（= 既有切分）。
     *
     * 与 [resolve] 同一条优先级；`null` 的语义见 [extractReasoning]。
     */
    fun effectiveFormat(r: Reasoning, defaults: Defaults? = null): Format? =
        r.format ?: defaults?.format

    /**
     * 预算落值：正数钳到本轮**生成上限**（`max_tokens` / 上下文预算给的那一格）。
     *
     * 0 与 -1 原样返回：0 是"关"，-1 是"不限"，都不是"一个很小的上限"。
     * 钳制必须留痕（调用方用 [describeReasoning] 打出来），否则用户会以为
     * 自己设的 4096 真生效了，实际只有 512。
     */
    fun effectiveBudget(
        r: Reasoning,
        maxTokens: Int,
        defaults: Defaults? = null,
    ): Int? {
        val b = r.budget ?: defaults?.budget ?: return null
        if (b <= 0 || maxTokens <= 0) return b
        return b.coerceAtMost(maxTokens)
    }

    /** 落日志用的一行；没有思考相关字段时返回空串。 */
    fun describeReasoning(r: Reasoning, effectiveBudget: Int?): String {
        val sb = StringBuilder()
        r.enableThinking?.let { sb.append("enable_thinking=").append(it).append(' ') }
        r.budget?.let {
            sb.append("reasoning_budget=").append(it)
            if (effectiveBudget != null && effectiveBudget != it) {
                sb.append("(钳到 ").append(effectiveBudget).append(')')
            }
            sb.append(' ')
        }
        r.effort?.let { sb.append("reasoning_effort=").append(it.wire).append(' ') }
        r.format?.let { sb.append("reasoning_format=").append(it.wire).append(' ') }
        return sb.toString().trim()
    }

    /**
     * 设置页全局默认的一行摘要；没有默认时返回空串。
     *
     * 单独一行（而不是塞进 [describeReasoning]）是为了让"这次到底是请求带来的、
     * 还是设置页默认生效的"在一眼之内分得开 —— 两者语义完全不同，混在一行里
     * 排查时只能靠猜。
     */
    fun describeDefaults(d: Defaults?): String {
        if (d == null) return ""
        val sb = StringBuilder()
        d.budget?.let { sb.append("reasoning_budget=").append(it).append(' ') }
        d.effort?.let { sb.append("reasoning_effort=").append(it.wire).append(' ') }
        d.format?.let { sb.append("reasoning_format=").append(it.wire).append(' ') }
        return sb.toString().trim()
    }

    /** 把 true/false/1/0/on/off 统一成布尔；无法识别的显式取值返回 null（由调用方报 400）。 */
    private fun switchValue(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> null
        }
        else -> null
    }

    private fun effortOf(raw: String): Effort? {
        val v = raw.trim().lowercase()
        return Effort.entries.firstOrNull { it.wire == v }
    }

    private fun formatOf(raw: String): Format? {
        // `deepseek_legacy` 是同一个档位的常见拼法，接受它不会造成歧义。
        val v = raw.trim().lowercase().replace('_', '-')
        return Format.entries.firstOrNull { it.wire == v }
    }

    private sealed interface IntRead {
        object Absent : IntRead
        object Bad : IntRead
        data class Ok(val value: Int) : IntRead
    }

    private fun readInt(j: JSONObject, key: String): IntRead {
        if (!j.has(key) || j.isNull(key)) return IntRead.Absent
        val v = j.opt(key)
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> {
                val t = v.trim()
                if (t.isEmpty()) return IntRead.Absent
                t.toDoubleOrNull() ?: return IntRead.Bad
            }
            else -> return IntRead.Bad
        }
        if (!d.isFinite() || d != Math.floor(d) ||
            d < Int.MIN_VALUE.toDouble() || d > Int.MAX_VALUE.toDouble()) return IntRead.Bad
        return IntRead.Ok(d.toInt())
    }

    /**
     * 渲染期传给库的 `common_chat_templates_inputs::enable_thinking`。
     *
     * 必须传，且必须与 [resolve] 同一个值：`llama_jni.cpp` 从不设这个字段，
     * 而 C++ 默认是 `true` —— MiniCPM5 模板因此**无论 App 里怎么勾**都会进
     * `enable_thinking is true` 那一支，吐 `<think>\n`，思考永远关不掉。
     * 模板关不掉的那类（LFM2.5）传下去不改变渲染结果，由软开关兜底，所以两条
     * 路径可以放心共用同一个取值。
     */
    fun templateEnableThinking(resolvedThinkingOn: Boolean): Boolean = resolvedThinkingOn
}
