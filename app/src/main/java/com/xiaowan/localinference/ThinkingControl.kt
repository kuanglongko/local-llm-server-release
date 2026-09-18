package com.xiaowan.localinference

import org.json.JSONObject

/**
 * 「思考开关」的**纯逻辑**部分：请求侧参数解析 + prompt 侧软开关注入。
 *
 * 为什么单独抽一个文件：这套逻辑原本**在两个地方各写了一遍**——
 * `HttpApi.handleChat`（HTTP 入口）与 `EngineActivity.doGenerate`（App 内聊天），
 * 两边取值来源不同（一个读全局默认 + 请求覆盖，一个只读持久化开关），
 * 而"注入空 think 块"的写法却必须逐字一致。重复实现的结果就是修一处、漏一处。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 关于门禁条件：不能用 `chatTemplate.contains("enable_thinking")`
 * ══════════════════════════════════════════════════════════════════════════
 * 这是本文件最容易改错的一处，单独写清楚。
 *
 * 「软开关」的可用范围**不取决于模型模板里有没有 `enable_thinking` 这个变量名**，
 * 而取决于模板渲染出的 assistant 生成后缀里**是否自带一段思考起始标记**。
 * 两者不是一回事：
 *
 *   · Qwen3 / SmolLM3 —— 模板里有 `enable_thinking` 变量，开启思考时
 *     生成后缀是 `<|im_start|>assistant\n`，思考段由模型自己吐 `<think>`，
 *     所以要靠"预填一个闭合空 think 块"来压掉它。
 *   · LFM2.5 这类 —— 模板里**根本没有 `enable_thinking` 变量**
 *     （`common_chat_templates_support_enable_thinking` 对它返回 false），
 *     无法通过参数关掉；而它开启思考时的生成后缀**硬编码就是**
 *     `<|im_start|>assistant\n<think>\n`。也就是说"思考开"是模板写死的，
 *     唯一能关掉它的办法恰好就是往这个后缀后面补上闭合段
 *     —— 也就是软开关本身，**而且这里才是软开关真正必要的地方**。
 *
 * 旧写法（`contains("enable_thinking")`）在 LFM2.5 上恒为 false，于是：
 * 设了「默认关闭思考」也**不会**注入空 think 块，模型照模板硬编码的后缀
 * 继续往下想 —— 这就是「App 内聊天关不掉思考」的真实原因。
 * 同一条件在 HTTP 侧也一样恒假，所以那个开关在两条路径上**从来没生效过**，
 * 不存在"HTTP 好、App 内坏"的分叉。请勿再按"两条路径分叉"的思路改这里。
 *
 * 判据换成"生成后缀里是否自带思考起始标记"之后：
 *   · LFM2.5  → 后缀含 `<think>` → 需要注入 → 注入后思考段被预填闭合，思考关掉；
 *   · Qwen3   → 后缀不含 `<think>`，但模板含 `enable_thinking` → 仍按老规矩注入
 *     （这两类模型的关闭方式已长期验证有效，不能因为这次改动被误伤）；
 *   · Gemma / Qwen-Instruct → 后缀不含 `<think>` 且模板不含 `enable_thinking`
 *     → 不注入（这类模型本来就没有思考段，塞进去只会多出一对标签当正文吐出来）。
 *
 * 判断依据只用**渲染结果与模板原文**，不打模型名/文件名的主意：
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
     * （如 `<|im_start|>assistant\n`）结尾，把空 think 块紧贴其后，等于替模型"预填"了
     * 一段已经闭合的思考段，模型直接续写正文即可，等价于思考被关掉。
     * 而放在最前面时，模型看到的是一个「先输出 `<think>` 的例子」，会照抄一遍——
     * 表现就是"设了关闭思考，模型还是先想一段"。
     */
    const val EMPTY_THINK_BLOCK = "<think>\n\n</think>\n\n"

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
     * 对后缀里不含 `<think>` 的模板（Qwen3 等），`lastIndexOf` 返回 -1，
     * 插入位置就是字符串末尾 —— 与老行为逐字节相同。
     */
    private fun appendAfterLastThinkTag(prompt: String): String {
        val marker = THINKING_START_MARKERS[0]
        val at = prompt.lastIndexOf(marker)
        // 后缀里已经有 <think>（还没闭合）：把闭合段插到它后面，别再多吐一个开标签。
        // 没有则追加到末尾（与旧行为一致）。
        if (at < 0) return prompt + EMPTY_THINK_BLOCK
        val afterOpen = at + marker.length
        return prompt.substring(0, afterOpen) + EMPTY_THINK_BLOCK.substring(marker.length) + prompt.substring(afterOpen)
    }

    /**
     * prompt 软开关：关闭思考时把空 think 块的闭合段补到渲染结果里。
     *
     * 生效条件（见文件头）：模型**确实会先输出思考段**。两类都算：
     *   · 模板含 `enable_thinking`（Qwen3 等，思考可由参数控制）；
     *   · 生成后缀自带思考起始标记（LFM2.5 等，思考写死在模板里，
     *     参数关不掉，只能靠预填闭合段压过去）。
     *
     * 两类都不满足（Gemma / Qwen-Instruct 等本没有思考段的模型）时原样返回：
     * 不看模板就注入，只会往 prompt 里塞一对没人认识的标签，模型只能当正文吐出来。
     */
    fun applyToPrompt(prompt: String, thinkingOn: Boolean, chatTemplate: String): String =
        if (!softSwitchApplies(thinkingOn, chatTemplate, prompt)) prompt
        else appendAfterLastThinkTag(prompt)

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
        return templateSupportsEnableThinking(chatTemplate) ||
                renderedPromptEndsWithThinkingStart(renderedPrompt)
    }

    /** 模板是否带 `enable_thinking` 变量（Qwen3 / SmolLM3 一类可参数化思考的模板）。 */
    fun templateSupportsEnableThinking(chatTemplate: String): Boolean =
        chatTemplate.contains("enable_thinking")

    /**
     * 渲染结果的生成后缀里是否自带思考起始标记（LFM2.5 一类把思考写死在模板里的模型）。
     *
     * 只看**生成后缀**（末尾那一段 assistant 起始标记），不扫全文：
     * 历史轮次里出现过 `<think>` 不代表这一轮模型会先想一段。
     */
    fun renderedPromptEndsWithThinkingStart(renderedPrompt: String): Boolean {
        if (renderedPrompt.isEmpty()) return false
        // 生成后缀一定在末尾：往前取一小段窗口就够（LFM2.5 的
        // '<|im_start|>assistant\n<think>\n' 只有几十字节），不需要全文扫描。
        val tail = renderedPrompt.substring(maxOf(0, renderedPrompt.length - TAIL_WINDOW))
        val at = tail.lastIndexOf(THINKING_START_MARKERS[0])
        if (at < 0) return false
        // 已经闭合的思考段（历史轮里模型自己想过一段）不算"这一轮还要先想一段"。
        val after = tail.substring(at)
        return after.indexOf("</think>") < 0
    }

    private const val TAIL_WINDOW = 256

    /**
     * HTTP 请求侧：`enable_thinking` / `chat_template_kwargs.enable_thinking` /
     * `reasoning_effort` 的覆盖值。返回 null 表示"请求没表态"，交由全局默认决定。
     *
     * 顺序即优先级：顶层 `enable_thinking` > `chat_template_kwargs` > `reasoning_effort`。
     */
    fun requestThinkingOverride(j: JSONObject): Boolean? {
        val kwEt = j.optJSONObject("chat_template_kwargs")?.opt("enable_thinking")
        return when {
            j.has("enable_thinking") -> j.optBoolean("enable_thinking", true)
            kwEt is Boolean -> kwEt
            kwEt is Number -> kwEt.toInt() != 0
            kwEt is String -> kwEt.lowercase() !in listOf("false", "0", "off")
            j.optString("reasoning_effort", "").isNotEmpty() -> j.optString("reasoning_effort") != "none"
            else -> null
        }
    }

    /** 请求覆盖 > 全局默认：最终这一轮到底是开还是关。 */
    fun resolve(disableByDefault: Boolean, override: Boolean?): Boolean = override ?: !disableByDefault
}
