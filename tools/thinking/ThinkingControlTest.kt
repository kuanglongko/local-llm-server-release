package com.xiaowan.localinference

import org.json.JSONObject
import java.io.File

// 「思考开关」的离线单测。
//
// 为什么值得单测："关闭思考"不是调 API，而是**往渲染好的 prompt 里补一段闭合 think 块**——
// 纯字符串拼接，写错位置（或干脆没拼）既不报错也不崩，只表现为"设了没生效"。
//
// 这里钉住两件历史上真出过事的事：
//   1. 生效判据。曾经只看模板里有没有 `enable_thinking` 变量，而 LFM2.5 这类模型的
//      "思考开"是模板生成后缀硬编码的（"<|im_start|>assistant" 后紧跟 "<think>"），
//      模板里根本没有那个变量 —— 于是软开关对它恒不生效，"默认关闭思考"怎么设都没用。
//      判据必须同时看**渲染结果的后缀**。
//   2. 注入位置。LFM2.5 的生成后缀已经把 `<think>` 吐出来了，这时在末尾再追加一整块
//      `<think>\n\n</think>\n\n` 会多出一个开标签，最早那个永不闭合，
//      整段回答被 think 状态机当思考段吞掉（表现为"回答为空"）。必须插在最后一个
//      `<think>` 之后，只补闭合部分。
private var f = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); f++ }
}

// 一份**模仿真实渲染结果**的 prompt：以 assistant 生成后缀结尾。
// 注入位置对不对，全看这段后缀在不在注入块之前。
private const val RENDERED_QWEN3 = "<|im_start|>system\nYou are Qwen.<|im_end|>\n" +
    "<|im_start|>user\n你好<|im_end|>\n<|im_start|>assistant\n"

private const val TMPL_WITH_ET = "{% if enable_thinking %}...{% endif %}"

// LFM2.5 的模板原文**不含** enable_thinking，但生成后缀硬编码带 <think>（真机 / 库内一致）。
private const val TMPL_LFM25 = "{%- for message in messages -%}{{ message.content }}{%- endfor -%}"

private const val RENDERED_LFM25 = "<|im_start|>system\nYou are LFM.<|im_end|>\n" +
    "<|im_start|>user\n你好<|im_end|>\n<|im_start|>assistant\n<think>\n"

// 无思考段的普通模板（Gemma / Qwen-Instruct 一类）。
private const val TMPL_NO_THINK = "{% for m in messages %}{{ m.role }}{% endfor %}"
private const val RENDERED_NO_THINK = "<start_of_turn>user\n你好<end_of_turn>\n<start_of_turn>model\n"

fun main() {
    // ---- 1. 注入位置：必须在 assistant 生成后缀之后 ----
    val off = ThinkingControl.applyToPrompt(RENDERED_QWEN3, thinkingOn = false, chatTemplate = TMPL_WITH_ET)
    ck("关闭思考时确实注入了内容", off.length > RENDERED_QWEN3.length)
    ck("注入块追加在末尾（紧贴 assistant 生成后缀，而不是塞到 prompt 开头）",
        off.startsWith(RENDERED_QWEN3) && off.endsWith(ThinkingControl.EMPTY_THINK_BLOCK))
    ck("注入块位置在 assistant 生成后缀之后",
        off.indexOf("<|im_start|>assistant") < off.indexOf("<think>"))
    // 回归守卫：把注入块放到最前面，模型会把它当成"示例"照抄一遍 —— 这正是旧写法。
    ck("注入块不在 prompt 开头（开头写法会让模型模仿输出思考段）",
        !off.startsWith(ThinkingControl.EMPTY_THINK_BLOCK))

    // ---- 2. 空 think 块本身是闭合的 ----
    ck("空 think 块包含开标签", ThinkingControl.EMPTY_THINK_BLOCK.contains("<think>"))
    ck("空 think 块包含闭标签（只开不闭等于没关）",
        ThinkingControl.EMPTY_THINK_BLOCK.contains("</think>"))
    ck("空 think 块开在闭之前",
        ThinkingControl.EMPTY_THINK_BLOCK.indexOf("<think>") < ThinkingControl.EMPTY_THINK_BLOCK.indexOf("</think>"))
    // 块内只允许空白：哪怕塞进一个字，都等于替模型预写了一段思考内容。
    // 取出开闭标签之间的内容：只允许空白，哪怕多一个字都等于替模型预写了思考。
    val inner = ThinkingControl.EMPTY_THINK_BLOCK
        .substringAfter("<think>").substringBefore("</think>")
    ck("空 think 块内只有空白（否则等于替模型写了一段思考）", inner.isBlank())
    // 去掉标签后整体也必须是空白：块里不许夹带别的标记或说明文字。
    ck("空 think 块不含其它标签/文字", ThinkingControl.EMPTY_THINK_BLOCK
        .replace("<think>", "").replace("</think>", "").isBlank())
    ck("空 think 块恰好是一对开闭标签", ThinkingControl.EMPTY_THINK_BLOCK
        .count { it == '<' } == 2 && ThinkingControl.EMPTY_THINK_BLOCK.count { it == '>' } == 2)

    // ---- 3. 门禁：既无 enable_thinking、后缀也不带思考标记时绝不注入 ----
    // 不看这两样就注入，会往 Gemma / Qwen-Instruct 这类没有思考段的模板里塞一对
    // <think> 标签，模型只能当正文吐出来（表现：回答里凭空多出标签）。
    ck("无 enable_thinking 且后缀无思考标记 -> 判定为不适用",
        !ThinkingControl.softSwitchApplies(false, TMPL_NO_THINK, RENDERED_NO_THINK))
    ck("无 enable_thinking 且后缀无思考标记 -> prompt 原样返回（no-op）",
        ThinkingControl.applyToPrompt(RENDERED_NO_THINK, false, TMPL_NO_THINK) == RENDERED_NO_THINK)
    ck("模板含 enable_thinking -> 判定为适用",
        ThinkingControl.softSwitchApplies(false, TMPL_WITH_ET, RENDERED_QWEN3))

    // ---- 3b. LFM2.5：思考写死在模板后缀里，模板原文没有 enable_thinking ----
    // 这就是「App 内聊天关不掉思考」的根因：旧判据只看模板有没有 enable_thinking，
    // LFM2.5 没有 -> 恒不注入 -> 模型顺着后缀硬编码的 <think> 继续想。
    ck("LFM2.5 模板原文确实不含 enable_thinking（否则本组断言没意义）",
        !TMPL_LFM25.contains("enable_thinking"))
    ck("LFM2.5 后缀自带未闭合的 <think>", RENDERED_LFM25.trimEnd().endsWith("<think>"))
    ck("LFM2.5：仅凭后缀即可判定软开关适用",
        ThinkingControl.softSwitchApplies(false, TMPL_LFM25, RENDERED_LFM25))
    ck("LFM2.5：旧判据（只看模板）本会漏掉——后缀判据是必要的",
        !ThinkingControl.templateSupportsEnableThinking(TMPL_LFM25))
    val lfm = ThinkingControl.applyToPrompt(RENDERED_LFM25, false, TMPL_LFM25)
    ck("LFM2.5：确实注入了内容", lfm.length > RENDERED_LFM25.length)
    ck("LFM2.5：注入后只剩一个 <think>（不得再多吐一个开标签）",
        lfm.split("<think>").size - 1 == 1)
    ck("LFM2.5：注入后开标签恰好被闭合（>0 个 </think>）",
        lfm.split("</think>").size - 1 >= 1)
    ck("LFM2.5：闭合段紧跟开标签（中间只有空白）",
        lfm.substringAfter("<think>").substringBefore("</think>").isBlank())
    ck("LFM2.5：注入块在 assistant 生成后缀之后",
        lfm.indexOf("<|im_start|>assistant") < lfm.indexOf("<think>"))
    ck("LFM2.5：开标签在所有 </think> 之前（没有闭合先于开启这种倒挂）",
        lfm.indexOf("<think>") < lfm.indexOf("</think>"))
    ck("LFM2.5：思考开 -> 不注入",
        ThinkingControl.applyToPrompt(RENDERED_LFM25, thinkingOn = true, chatTemplate = TMPL_LFM25) == RENDERED_LFM25)
    // 历史轮里模型自己想过一段（闭合）→ 不代表这一轮还要先想一段。
    val historyClosed = "<|im_start|>assistant\n<think>\n想过一段\n</think>\n正文<|im_end|>\n<|im_start|>assistant\n"
    ck("LFM2.5：后缀已闭合的历史思考段不算【这一轮还要想】",
        !ThinkingControl.softSwitchApplies(false, TMPL_LFM25, historyClosed))

    // ---- 4. 开启思考时绝不注入（开关是双向的）----
    ck("思考开 -> 不注入",
        ThinkingControl.applyToPrompt(RENDERED_QWEN3, thinkingOn = true, chatTemplate = TMPL_WITH_ET) == RENDERED_QWEN3)
    ck("思考开 -> 判定为不适用",
        !ThinkingControl.softSwitchApplies(true, TMPL_WITH_ET, RENDERED_QWEN3))
    ck("思考开 -> 后缀带思考标记也仍不注入（开关是双向的）",
        !ThinkingControl.softSwitchApplies(true, TMPL_LFM25, RENDERED_LFM25))
    // 空模板 + 空渲染结果（模型没带模板 / 取模板失败）不能当成"有思考段"来放行。
    ck("模板与渲染结果都为空 -> 不注入",
        ThinkingControl.applyToPrompt("", false, "") == "")

    // ---- 5. HTTP 请求侧的覆盖优先级 ----
    // 顶层 enable_thinking > chat_template_kwargs.enable_thinking > reasoning_effort
    ck("未表态 -> null（交给全局默认）",
        ThinkingControl.requestThinkingOverride(JSONObject("""{}""")) == null)
    ck("enable_thinking=false -> false",
        ThinkingControl.requestThinkingOverride(JSONObject("""{"enable_thinking":false}""")) == false)
    ck("enable_thinking=true -> true",
        ThinkingControl.requestThinkingOverride(JSONObject("""{"enable_thinking":true}""")) == true)
    ck("chat_template_kwargs.enable_thinking=false -> false",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"chat_template_kwargs":{"enable_thinking":false}}""")) == false)
    ck("chat_template_kwargs.enable_thinking=0（数字）-> false",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"chat_template_kwargs":{"enable_thinking":0}}""")) == false)
    ck("chat_template_kwargs.enable_thinking=\"false\"（字符串）-> false",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"chat_template_kwargs":{"enable_thinking":"false"}}""")) == false)
    ck("chat_template_kwargs.enable_thinking=\"off\"（字符串）-> false",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"chat_template_kwargs":{"enable_thinking":"off"}}""")) == false)
    ck("reasoning_effort=none -> false",
        ThinkingControl.requestThinkingOverride(JSONObject("""{"reasoning_effort":"none"}""")) == false)
    ck("reasoning_effort=high -> true",
        ThinkingControl.requestThinkingOverride(JSONObject("""{"reasoning_effort":"high"}""")) == true)
    ck("顶层 enable_thinking 压过 kwargs（优先级）",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"enable_thinking":true,"chat_template_kwargs":{"enable_thinking":false}}""")) == true)
    ck("kwargs 压过 reasoning_effort（优先级）",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"chat_template_kwargs":{"enable_thinking":true},"reasoning_effort":"none"}""")) == true)

    // ---- 6. 全局默认 + 覆盖 的组合 ----
    ck("全局关 + 请求未表态 -> 关", ThinkingControl.resolve(true, null) == false)
    ck("全局关 + 请求要开 -> 开（客户端可覆盖）", ThinkingControl.resolve(true, true) == true)
    ck("全局开 + 请求未表态 -> 开", ThinkingControl.resolve(false, null) == true)
    ck("全局开 + 请求要关 -> 关", ThinkingControl.resolve(false, false) == false)

    // ---- 7. 源码级守卫 ----
    // 两个"改回去就复发"的点，断言到源码层面：
    //   · 任一条路径重新手写 "<think>" 拼接 -> 两条路径再次分叉；
    //   · 任一条路径在判定时不传渲染结果 -> LFM2.5 的软开关又恒不生效（本次故障根因）。
    val src = File("app/src/main/java/com/xiaowan/localinference")
    val http = File(src, "HttpApi.kt").readText()
    val ui = File(src, "EngineActivity.kt").readText()
    val tc = File(src, "ThinkingControl.kt").readText()
    ck("HTTP 路径走 ThinkingControl.applyToPrompt", http.contains("ThinkingControl.applyToPrompt("))
    ck("App 内聊天路径走 ThinkingControl.applyToPrompt", ui.contains("ThinkingControl.applyToPrompt("))
    ck("两条路径都不再各自手写空 think 块字面量",
        !http.contains("prompt + \"<think>") && !ui.contains("prompt + \"<think>"))
    ck("字面量只在 ThinkingControl 里定义一处",
        tc.contains("EMPTY_THINK_BLOCK = \"<think>"))
    ck("HTTP 路径的开关解析也走 ThinkingControl",
        http.contains("ThinkingControl.requestThinkingOverride(") &&
            http.contains("ThinkingControl.resolve("))
    ck("App 内聊天受持久化开关控制（ModelStore.disableThinking）",
        ui.contains("ModelStore.disableThinking(this@EngineActivity)"))
    // 生效判据必须能看见渲染结果：只传 (thinkingOn, chatTemplate) 的旧调用，
    // 对 LFM2.5 恒为 false —— 这正是"关不掉思考"的根因，钉死不许回退。
    ck("HTTP 路径的生效判定传入了渲染结果",
        http.contains("ThinkingControl.softSwitchApplies(thinkingOn, chatTemplate, prompt)"))
    ck("App 内聊天路径的生效判定传入了渲染结果",
        ui.contains("ThinkingControl.softSwitchApplies(thinkingOn, chatTemplate, rendered)"))
    ck("生效判据不退回【只看模板有没有 enable_thinking】",
        tc.contains("renderedPromptEndsWithThinkingStart"))

    println("=== 思考开关单测 ${if (f == 0) "全部通过" else "$f 条失败"} ===")
    if (f != 0) kotlin.system.exitProcess(1)
}
