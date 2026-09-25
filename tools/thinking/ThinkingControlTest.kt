package com.xiaowan.localinference

import org.json.JSONObject
import java.io.File

// 「思考开关」的离线单测。
//
// 为什么值得单测：关闭思考有**两条**路，而两条路都不报错、不崩，错了只表现为
// "设了没生效"或"客户端多出一对 think 标签"：
//
//   ① 模板自带 `enable_thinking` 变量（MiniCPM5 / Qwen3）-> 把开关渲进 prompt，
//      由模板自己吐完整闭合块。宿主**必须让位**，不能叠软开关。
//   ② 模板里没有这个变量（LFM2.5）-> 库关不掉，只能由软开关补闭合段。
//
// 历史上真出过事的三处，都在下面钉住：
//   1. 生效判据只看模板里有没有 `enable_thinking`，而 LFM2.5 的"思考开"是模板生成后缀
//      硬编码的，模板里没有那个变量 -> 软开关恒不生效，"默认关闭思考"怎么设都没用。
//   2. 注入位置。LFM2.5 的生成后缀已经把 `<think>` 吐出来了，这时在末尾再追加一整块
//      `<think>\n\n</think>\n\n` 会多出一个开标签，最早那个永不闭合，
//      整段回答被 think 状态机当思考段吞掉（表现为"回答为空"）。
//   3. **该让位的时候没让位**（2026-09-19 真机报障，本文件新增的一组）。
//      MiniCPM5 模板在 `add_generation_prompt` 下**总是**吐 `<think>\n`
//      （`enable_thinking` 缺省 true），宿主再补一段 `\n</think>\n\n` 会拼出
//      `...<|im_start|>assistant\n<think>\n\n</think>\n\n\n` ——
//      ① 模板那个 `<think>` 与宿主补的 `</think>` 之间只剩 1 个换行，
//         模型把推理写进去，裸 `</think>` 漏进 content；
//      ② 形状与"思考开"几乎一样，模型会顺着它把想过的东西再复盘一遍 —— 一直推理。
//      所以「模板自带 enable_thinking」必须是**不注入**。
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

// ── MiniCPM5：**真模板**（tools/minicpm5_fixture/chat_template.jinja，9060B，md5 固定）──
// 为什么用真模板而不是手写简化版：本组的三条结论完全建立在模板尾部的**具体字形**上
// （`enable_thinking is defined` 分支、`'<think>\n\n</think>\n\n'` 与 `'<think>\n'`
//  这两个字面量），手写简化版会"顺手写对"，测不出真实故障、反而给虚假的安全感。
private val TMPL_MINICPM5: String by lazy {
    File("tools/minicpm5_fixture/chat_template.jinja").readText()
}

// MiniCPM5 在 add_generation_prompt + enable_thinking=true 下的生成后缀（模板写死）。
// 这一段就是真机报告里"系统默认的那个 think 标签"。
private const val MINICPM5_SUFFIX_THINK_ON = "<|im_start|>assistant\n<think>\n"
private const val RENDERED_MINICPM5 = "<|im_start|>system\nYou are MiniCPM.<|im_end|>\n" +
    "<|im_start|>user\n北京天气<|im_end|>\n" + MINICPM5_SUFFIX_THINK_ON

fun main() {
    // ---- 1. 注入位置：必须在 assistant 生成后缀之后（用需要注入的 LFM2.5 来钉）----
    val off = ThinkingControl.applyToPrompt(RENDERED_LFM25, thinkingOn = false, chatTemplate = TMPL_LFM25)
    ck("关闭思考时确实注入了内容", off.length > RENDERED_LFM25.length)
    ck("注入块追加在末尾（紧贴 assistant 生成后缀，而不是塞到 prompt 开头）",
        off.startsWith(RENDERED_LFM25) && off.endsWith("</think>\n\n\n"))
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
    // 模板自带 enable_thinking -> **让位**，由库/模板自己关（见文件头第 3 条）。
    // 这一条 2026-09-19 之前是反的（判定为"适用"），真机表现为客户端多一个 think 标签。
    ck("模板含 enable_thinking -> 软开关让位（不注入），交给模板自己关",
        !ThinkingControl.softSwitchApplies(false, TMPL_WITH_ET, RENDERED_QWEN3))
    ck("模板含 enable_thinking -> applyToPrompt 是 no-op",
        ThinkingControl.applyToPrompt(RENDERED_QWEN3, false, TMPL_WITH_ET) == RENDERED_QWEN3)
    ck("模板自带 enable_thinking -> 路由判据命中「让位」这一支",
        ThinkingControl.templateSupportsEnableThinking(TMPL_WITH_ET))

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

    // ═══════════════════════════════════════════════════════════════════════
    // 3c. MiniCPM5：模板自带 enable_thinking，**必须让位**（2026-09-19 真机报障）
    // ═══════════════════════════════════════════════════════════════════════
    // 真机症状：客户端输出两个 think 标签 + 一直在自己推理、很久才结束。
    // 两个标签的来源：
    //   · "系统默认"那个 = 模板自己在 add_generation_prompt 下吐的 `<think>\n`
    //   · "修复加的"那个 = 宿主软开关叠上去的 `<think>\n\n</think>\n\n`
    //
    // 这一组用**真模板**钉住三件事：模板确实自带开关（所以必须让位）、
    // 让位之后不再出现两个开标签、叠上去时那 1 个换行的空档确实存在（回归守卫）。
    ck("MiniCPM5 真模板文件在（否则本组断言无意义）", TMPL_MINICPM5.isNotEmpty())
    ck("MiniCPM5 真实尺寸 9060（与真机日志 chat_template_len=9060 一致）",
        TMPL_MINICPM5.toByteArray(Charsets.UTF_8).size == 9060)
    ck("MiniCPM5 模板**自带** enable_thinking 分支（这正是「必须让位」的依据）",
        TMPL_MINICPM5.contains("enable_thinking is defined"))
    ck("MiniCPM5 模板关掉思考时自己吐完整闭合块（宿主拼不出这个换行细节）",
        TMPL_MINICPM5.contains("'<think>\\n\\n</think>\\n\\n'"))
    ck("MiniCPM5 生成后缀自带未闭合的 <think>（「系统默认」的那个标签）",
        RENDERED_MINICPM5.endsWith(MINICPM5_SUFFIX_THINK_ON))

    ck("MiniCPM5 关闭思考 -> 软开关让位（不注入）",
        !ThinkingControl.softSwitchApplies(false, TMPL_MINICPM5, RENDERED_MINICPM5))
    ck("MiniCPM5 关闭思考 -> applyToPrompt 是 no-op（交给模板自己关）",
        ThinkingControl.applyToPrompt(RENDERED_MINICPM5, false, TMPL_MINICPM5) == RENDERED_MINICPM5)
    ck("MiniCPM5 思考开 -> 同样不注入（开关是双向的）",
        ThinkingControl.applyToPrompt(RENDERED_MINICPM5, true, TMPL_MINICPM5) == RENDERED_MINICPM5)

    // 回归守卫：把 2026-09-19 之前那版实现（无脑叠软开关）跑一遍，断言症状确实存在。
    // 这样"以后有人把让位规则删掉"会在 CI 里红，而不是等真机复现。
    //
    // 两条都要钉，因为它们**不是**同一个症状、也不是同一版引入的：
    //   · 0.9.70 第一版（末尾追加整块）-> 两个开标签（真机报的「两个 think 标签」）；
    //   · 0.9.70 后续版（插在最后一个 <think> 之后、只补闭合部分）-> 空档缩到 1 个换行
    //     （模型把推理写在这 1 个换行里，`</think>` 紧跟在推理文字后，裸标签照旧漏进正文），
    //     而**模板那个 `<think>` 与宿主补的 `</think>` 之间的空档本身消不掉** ——
    //     宿主拼不出模板的换行细节（模板自己吐的是 "\n\n"）。
    // 结论：对 MiniCPM5 这类模板，唯一的正解是**让位**，不是把拼接改得更巧。
    run {
        val v1 = RENDERED_MINICPM5 + ThinkingControl.EMPTY_THINK_BLOCK        // 0.9.70 第一版
        ck("【回归守卫】第一版「末尾追加整块」-> 两个 <think>（真机报的两个标签）",
            v1.split("<think>").size - 1 == 2)

        val v2 = ThinkingControl.applyToPrompt(RENDERED_MINICPM5, false, TMPL_MINICPM5)  // 让位后
        ck("【回归守卫】让位后一个多余标签都没有（</think> 计数为 0，<think> 仍只有模板那一个）",
            v2 == RENDERED_MINICPM5 &&
                v2.split("</think>").size - 1 == 0 &&
                v2.split("<think>").size - 1 == 1)

        // 现实现（"插在最后一个 <think> 之后、只补闭合部分"）**不是**模板该吐的那一段：
        // 它与模板 enable_thinking=false 时的 "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        // 相比，多出一个换行 —— 那个多出来的换行正是模型可以塞推理进去的空档。
        val hostPatched = RENDERED_MINICPM5.substring(0, RENDERED_MINICPM5.length - 1) +   // 去掉尾部 \n
            ThinkingControl.EMPTY_THINK_BLOCK +
            RENDERED_MINICPM5.substring(RENDERED_MINICPM5.length - 1)
        ck("【回归守卫】宿主补出来的闭合段与模板自己吐的**不逐字节相同**（这就是必须让位的理由）",
            hostPatched.endsWith("<think>\n\n</think>\n\n\n") &&
                !hostPatched.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    // ---- 4. 开启思考时绝不注入（开关是双向的）----
    ck("思考开 -> 不注入",
        ThinkingControl.applyToPrompt(RENDERED_QWEN3, thinkingOn = true, chatTemplate = TMPL_WITH_ET) == RENDERED_QWEN3)
    ck("思考开 -> 判定为不适用",
        !ThinkingControl.softSwitchApplies(true, TMPL_WITH_ET, RENDERED_QWEN3))
    ck("思考开 -> LFM2.5 也不注入",
        ThinkingControl.applyToPrompt(RENDERED_LFM25, true, TMPL_LFM25) == RENDERED_LFM25)
    ck("思考开 -> 后缀带思考标记也仍不注入（开关是双向的）",
        !ThinkingControl.softSwitchApplies(true, TMPL_LFM25, RENDERED_LFM25))
    // 空模板 + 空渲染结果（模型没带模板 / 取模板失败）不能当成"有思考段"来放行。
    ck("模板与渲染结果都为空 -> 不注入",
        ThinkingControl.applyToPrompt("", false, "") == "")

    // ---- 5. HTTP 请求侧的覆盖优先级 ----
    // 顶层 enable_thinking > chat_template_kwargs.enable_thinking > reasoning_effort
    ck("未表态 -> null（交给全局默认）",
        ThinkingControl.requestThinkingOverride(JSONObject("""{}""")) == null)
    // `has` 对 null 返回 true，`optBoolean` 对 JSONObject.NULL 会回落到默认值 true ——
    // 于是 {"enable_thinking":null} 曾被判成**开思考**，与「没表态」相反。
    // 不少 OpenAI 兼容 SDK 会把可选字段序列化成 null，而 App 全局默认是关思考，
    // 用户设了关就被一个 null 翻成开。判据必须与 SamplingParams 的 null=Absent 对齐。
    ck("enable_thinking=null -> null（= 没表态，不得翻成 true）",
        ThinkingControl.requestThinkingOverride(JSONObject("""{"enable_thinking":null}""")) == null)
    ck("chat_template_kwargs.enable_thinking=null -> null",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"chat_template_kwargs":{"enable_thinking":null}}""")) == null)
    ck("enable_thinking=null 时仍读后面的 reasoning_effort（null 不吞掉后续判据）",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"enable_thinking":null,"reasoning_effort":"none"}""")) == false)
    ck("enable_thinking=null 不得覆盖 kwargs 的显式表态",
        ThinkingControl.requestThinkingOverride(
            JSONObject("""{"enable_thinking":null,"chat_template_kwargs":{"enable_thinking":true}}""")) == true)
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
    // 生效判定必须看得见渲染结果：只传 (thinkingOn, chatTemplate) 的旧调用对 LFM2.5
    // 恒为 false —— 那正是"关不掉思考"的根因。渲染结果现在是 RenderedPrompt，
    // 取它的 .text（同一次渲染，不是另拼一份）。
    ck("HTTP 路径的生效判定传入了渲染结果",
        http.contains("ThinkingControl.softSwitchApplies(thinkingOn, chatTemplate, rendered.text)"))
    ck("App 内聊天路径的生效判定传入了渲染结果",
        ui.contains("ThinkingControl.softSwitchApplies(thinkingOn, chatTemplate, rendered.text)"))
    ck("生效判据不退回【只看模板有没有 enable_thinking】",
        tc.contains("renderedPromptEndsWithThinkingStart"))

    // ── 让位规则（2026-09-19 的根因，钉到源码层面）──
    // 模板自带 enable_thinking 时必须 **不注入**：库/模板自己关得比宿主准。
    ck("模板自带 enable_thinking 时判定让位（不得注入）",
        tc.contains("if (templateSupportsEnableThinking(chatTemplate)) return false"))

    // ── enable_thinking 必须真的传进库（C++ 默认 true，不传 = 恒"思考开"）──
    // 这处是"编译期拦不住"的典型：字段存在、默认值合理、不赋值也不报错，
    // 真机只表现为"勾了默认关闭思考也没用"。只能断言到源码层面。
    val jni = File("app/src/main/cpp/llama_jni.cpp").readText()
    val engine = File(src, "LlmEngine.kt").readText()
    ck("LlmEngine 渲染时不带 tools 也要传 thinkingOn",
        engine.contains("nativeApplyChatTemplate(chatTemplate(), roles, contents, addAss, thinkingOn)"))
    ck("LlmEngine 渲染时带 tools 也要传 thinkingOn",
        engine.contains("parallelToolCalls, addAss, thinkingOn)"))
    ck("LlmEngine 工具解析要传与渲染同源的 thinkingOn",
        engine.contains("nativeParseToolCalls(text, toolsJson, tmpl, addAss, thinkingOn)"))
    ck("JNI 三处 common_chat_templates_inputs 都显式设 enable_thinking",
        jni.split("in.enable_thinking = (enableThinking == JNI_TRUE);").size - 1 == 3)
    ck("no-tools 渲染走 common_chat_templates_apply（旧 llama_chat_apply_template 不认 enable_thinking）",
        !jni.contains("llama_chat_apply_template("))
    ck("HTTP 路径把 thinkingOn 传给工具解析（与渲染同源）",
        http.contains("thinkingOn = thinkingOn)"))
    ck("App 内聊天路径把 thinkingOn 传给渲染",
        ui.contains("LlmEngine.applyChatTemplate(messages, addAss = true, thinkingOn = thinkingOn)"))

    println("=== 思考开关单测 ${if (f == 0) "全部通过" else "$f 条失败"} ===")
    if (f != 0) kotlin.system.exitProcess(1)
}
