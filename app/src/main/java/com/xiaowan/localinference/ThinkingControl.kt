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
     * HTTP 请求侧：`enable_thinking` / `chat_template_kwargs.enable_thinking` /
     * `reasoning_effort` 的覆盖值。返回 null 表示"请求没表态"，交由全局默认决定。
     *
     * 顺序即优先级：顶层 `enable_thinking` > `chat_template_kwargs` > `reasoning_effort`。
     */
    fun requestThinkingOverride(j: JSONObject): Boolean? {
        val kwEt = j.optJSONObject("chat_template_kwargs")?.opt("enable_thinking")
        return when {
            // `has` 对 null 返回 true，而 `optBoolean` 对 JSONObject.NULL 会回落到默认值 ——
            // 于是 {"enable_thinking":null} 被读成 **true**（开思考），与「没表态」的语义相反。
            // 不少 OpenAI 兼容 SDK 序列化可选字段时会带上 null，而 App 全局默认是关思考，
            // 结果用户设了关，请求里一个 null 就把它翻成开（症状：怎么突然开始想这么久）。
            // 判据与 SamplingParams.read 对齐：null 视为没给，落到后面几条链上。
            j.has("enable_thinking") && !j.isNull("enable_thinking") ->
                j.optBoolean("enable_thinking", true)
            kwEt is Boolean -> kwEt
            kwEt is Number -> kwEt.toInt() != 0
            kwEt is String -> kwEt.lowercase() !in listOf("false", "0", "off")
            j.optString("reasoning_effort", "").isNotEmpty() -> j.optString("reasoning_effort") != "none"
            else -> null
        }
    }

    /** 请求覆盖 > 全局默认：最终这一轮到底是开还是关。 */
    fun resolve(disableByDefault: Boolean, override: Boolean?): Boolean = override ?: !disableByDefault

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
