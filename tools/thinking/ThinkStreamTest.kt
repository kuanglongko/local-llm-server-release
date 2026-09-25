package com.xiaowan.localinference

import java.io.File

/**
 * `<think>` 状态机的离线单测。
 *
 * 三条故障都不报错、不崩，只表现为客户端 view 里「折叠里再折叠」或思考内容混进正文，
 * 因此每一条都钉到"字段级"：断言某段文本到底进了 content 还是 reasoning_content。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

/** 跑一遍状态机，返回 (content, reasoning)。pieces 一项一个 chunk，模拟逐 token 下发。 */
private fun run(pieces: List<String>, openAtStart: Boolean = false): Pair<String, String> {
    val st = ThinkStream(openAtStart)
    val c = StringBuilder(); val r = StringBuilder()
    val emit: (String, Boolean) -> Unit = { t, asReason -> if (asReason) r.append(t) else c.append(t) }
    for (p in pieces) st.feed(p, emit)
    st.flush(emit)
    return c.toString() to r.toString()
}

/** 逐字符切 chunk：最严苛的跨 chunk 场景，半个标签必须被 carry 吃掉。 */
private fun runCharwise(text: String, openAtStart: Boolean = false) = run(text.map { it.toString() }, openAtStart)

private fun hasTag(s: String) = s.contains("<think>") || s.contains("</think>")

fun main() {
    println("=== ThinkStream 单测 ===")

    val src = File("app/src/main/java/com/xiaowan/localinference")
    val http = File(src, "HttpApi.kt").readText()
    val ts = File(src, "ThinkStream.kt").readText()


    // ---- 1. 故障①：开标签只在 prompt 里（LFM2.5 / MiniCPM5 思考开的真实形状）----
    // prompt 后缀硬编码 <think>，模型输出从思考正文开始、到 </think> 结束。
    // 旧实现 sawOpen 恒 false -> 整段思考 + 裸 </think> 全进 content。
    run {
        val (c, r) = runCharwise("先想想。\n1+1=2。\n</think>答案是 2。", openAtStart = true)
        ck("① 开标签在 prompt 里：思考段进 reasoning", r == "先想想。\n1+1=2。\n")
        ck("① 开标签在 prompt 里：正文进 content", c == "答案是 2。")
        ck("① 开标签在 prompt 里：content 不含裸标签", !hasTag(c))
        ck("① 开标签在 prompt 里：reasoning 不含标签", !hasTag(r))
    }

    // ---- 1b. ISSUE #106（2026-09-21）：软开关注入后 openAtStart 必须落到 false ----
    //
    // 真机现象（LFM2.6B / 关闭思考 / ChatterUI）：回答**整段**被折叠进思考块，
    // 正文里只看到 `<think>` 开头。用户的原话是"内容应该是服务端返回的正常输出正文"。
    //
    // 根因不在状态机，在**传给它的 openAtStart**：
    //   · LFM2.5-2.6B 的生成后缀确实是 `<|im_start|>assistant\n<think>`（未闭合）
    //     -> rendered.openAtStart = true；
    //   · 关思考时软开关注入闭合段 -> prompt 尾部变成 `<think>\n\n</think>\n\n`（**已闭合**）。
    //   旧写法 `openAtStart = rendered.openAtStart || soft` 把已闭合的后缀又判回"思考段已开"，
    //   状态机以 inThink=true 起步，而模型这一轮写的是**正文**、永远不会再吐 `</think>`，
    //   于是整段正文留在 reasoning_content 里 —— 客户端把它折叠进思考块。
    //
    // 正确取向是 `&& !soft`：软开关补的是闭合段，注入完思考段就闭合了。
    run {
        // 模型在"思考段已被预填闭合"之后写的是正文（无任何标签）。
        val answer = "你好！我是111。很高兴见到你。今天有什么我可以帮你的吗？"

        val buggy = runCharwise(answer, openAtStart = true)   // 旧写法：整段被吞
        ck("ISSUE #106：旧写法（注入后仍 true）-> 正文被整段折叠进 reasoning",
            buggy.first == "" && buggy.second == answer)
        ck("ISSUE #106：旧写法 -> content 为空（正是真机「整段折叠」的算术形态）",
            buggy.first.isEmpty())

        val fixed = runCharwise(answer, openAtStart = false)  // 新写法：正文归 content
        ck("ISSUE #106：新写法（注入后 false）-> 正文正确落在 content",
            fixed.first == answer && fixed.second == "")
        ck("ISSUE #106：新写法 -> content 不含任何 think 标签（客户端不会再折叠）",
            !hasTag(fixed.first))
    }

    // ---- 2. 故障②：思考段内嵌套 <think>（用户报的「折叠里再折叠」）----
    run {
        val (c, r) = runCharwise("<think>外层想。<think>内层想。</think></think>答案。")
        ck("② 嵌套：reasoning 里不得再出现 <think>（否则客户端折叠再折叠）", !r.contains("<think>"))
        ck("② 嵌套：reasoning = 外层+内层内容", r == "外层想。内层想。")
        ck("② 嵌套：content = 答案（裸 </think> 不得漏出）", c == "答案。")
        ck("② 嵌套：content 不含任何标签", !hasTag(c))
    }

    // ---- 3. 故障③：非思考段的裸 </think>（关闭思考时模型重复收尾）----
    run {
        val (c, r) = runCharwise("</think>答案是 2。")
        ck("③ 裸 </think>：不得漏进 content", c == "答案是 2。")
        ck("③ 裸 </think>：reasoning 为空", r == "")
    }

    // ---- 4. 常规形状不许被误伤 ----
    run {
        val (c, r) = runCharwise("<think>想一想</think>正文")
        ck("常规：R1/Qwen3 形状仍正确分流", c == "正文" && r == "想一想")
    }
    run {
        val (c, r) = runCharwise("没有思考段，直接回答。")
        ck("常规：无思考段的模型原样进 content", c == "没有思考段，直接回答。" && r == "")
    }
    run {
        val (c, r) = runCharwise("开头有正文<think>再想</think>结尾正文")
        ck("常规：思考段前有正文也要正确切分",
            c == "开头有正文结尾正文" && r == "再想")
    }
    run {
        // 未闭合的思考段（max_tokens 截断）：全部算 reasoning，content 不得混入。
        val (c, r) = runCharwise("<think>想到一半就被截断")
        ck("常规：思考段被截断时整体归 reasoning", c == "" && r == "想到一半就被截断")
    }

    // ---- 5. 跨 chunk：逐 token 下发与整段下发结果必须一致 ----
    run {
        val text = "<think>甲<think>乙</think>丙</think>丁"
        val (c1, r1) = run(listOf(text))
        val (c2, r2) = runCharwise(text)
        ck("跨 chunk：分片与整段结果一致", c1 == c2 && r1 == r2)
        ck("跨 chunk：嵌套标签在分片下也不漏出", !hasTag(c2) && !hasTag(r2))
    }

    // ---- 6. 半截标签的收尾：真内容是内容，半标签不是 ----
    //
    // 旧实现把 carry 里剩的字节**一律**按当前段归属发出，于是截断/取消恰好停在标签中途时
    // content 尾部会多出裸 `<think` / `</think`（与故障③同一条判据，只是发生在收尾这一拍）。
    // 正确判据是「carry 里属于标签前缀的那几个字节不是内容，要丢；其余照发」——
    // 所以「正文<thi」里的 `正文` 必须留下，`<thi` 必须消失。
    run {
        val (c, r) = runCharwise("正文<thi")   // 停在半个标签上
        ck("收尾：真内容保留、半个标签丢弃", c == "正文" && r == "")
    }
    run {
        val (c, r) = runCharwise("正文</think")   // 停在半个闭合标签上
        ck("收尾：半个闭合标签也不漏进 content", c == "正文" && !hasTag(c))
    }
    run {
        val (c, r) = runCharwise("<thi")   // 整段都是标签前缀
        ck("收尾：整段都是标签前缀时全丢（不留裸标签、不产出空内容）", c == "" && r == "")
    }
    run {
        val (c, r) = runCharwise("<think>思考</think>正文的中间")   // 正常收尾
        ck("收尾：不含半标签时不丢任何字节（正文完整）", c == "正文的中间" && r == "思考")
    }

    // ---- 7. RenderedPrompt：native 语义前缀的解析（判据的唯一来源）----
    run {
        ck("RenderedPrompt：I 前缀 -> openAtStart=true，且前缀被剥掉",
            RenderedPrompt.parse("I" + "<|im_start|>assistant\n").let {
                it.openAtStart && it.text == "<|im_start|>assistant\n" })
        ck("RenderedPrompt：O 前缀 -> openAtStart=false，且前缀被剥掉",
            RenderedPrompt.parse("O" + "<|im_start|>assistant\n").let {
                !it.openAtStart && it.text == "<|im_start|>assistant\n" })
        // 没前缀 = 旧 native。必须**退化到 false**：宁可让状态机自己解析标签，
        // 也不要把正文吞进思考段（后者正是 2026-09-19 真机报障的症状）。
        ck("RenderedPrompt：无前缀 -> 退化 false 且原文不动（不吞字节）",
            RenderedPrompt.parse("<|im_start|>assistant\n").let {
                !it.openAtStart && it.text == "<|im_start|>assistant\n" })
        ck("RenderedPrompt：空串不崩", RenderedPrompt.parse("").let { !it.openAtStart && it.text == "" })
        // prompt 首字符恰好是 I/O 时会不会被误吃？native 一定加前缀，所以不会。
        // 但必须钉住"剥掉的一定是那个前缀"：只剩 prompt 本体。
        ck("RenderedPrompt：I 开头的 prompt 不会被多剥一个字节",
            RenderedPrompt.parse("IIndex").let { it.text == "Index" && it.openAtStart })
    }

    // ---- 8. 渲染尾部形状的判据（宿主侧镜像；生产路径在 native）----
    //
    // 这一组是 2026-09-19 真机故障的**判据本体**：
    //   · LFM2.5 思考开后缀        `…assistant\n<think>\n`          -> 未闭合的裸开标签 -> true
    //   · MiniCPM5 + enable_thinking=true  `…assistant\n<think>\n`  -> **同上**
    //   · MiniCPM5 + enable_thinking=false `…assistant\n<think>\n\n</think>\n\n` -> 已闭合 -> false
    //
    // 注意第三行才是修复的关键：它必须为 false（否则"关思考"时模型仍会再补一个
    // `</think>`，而状态机把那个当裸标签剥掉 -> content 少一截，两边都崩）。
    val jni = File("app/src/main/cpp/llama_jni.cpp").readText()
    val m5Open = "<|im_start|>assistant\n<think>\n"
    val m5Closed = "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    ck("形状①：LFM2.5 思考开后缀 -> openAtStart", RenderedPrompt.openAtStartForTest(m5Open))
    ck("形状②：MiniCPM5 思考开后缀 -> openAtStart", RenderedPrompt.openAtStartForTest(m5Open))
    ck("形状③：MiniCPM5 关思考后缀 -> 不是 openAtStart（否则正文被吞）",
        !RenderedPrompt.openAtStartForTest(m5Closed))
    ck("形状④：无思考段后缀 -> false",
        !RenderedPrompt.openAtStartForTest("<|im_start|>assistant\n"))
    ck("形状⑤：只有历史轮想过一段 -> false（不把历史当当前轮）",
        !RenderedPrompt.openAtStartForTest("<think>想过</think>\n<|im_start|>assistant\n"))

    // 镜像与 C++ 的形状必须同构：字面量与尾窗口大小任一处漂了，
    // 上面那五条断言就不再代表线上判据 —— 那是"测试自说自话"的经典失效。
    // ⚠ 模块 F 那一轮：判据本体从 llama_jni.cpp 搬到了 probe_util.h（收口到一处 +
    // 宿主可编），而且 `RenderedPrompt` 那份镜像也**不再自己写窗口**——它转发到
    // `ThinkStream.classifyTail`。所以"同源"的锚点跟着实现走，**判据意图不变**：
    // 仍然是"字面量一致 + 尾窗口同值同单位 + 三条分支齐全"。
    val pu = File("app/src/main/cpp/probe_util.h").readText()
    val cppSide = jni + "\n" + pu
    val ktSide = File(src, "ThinkStream.kt").readText()
    ck("C++ 判据与宿主镜像同源：开/闭标签字面量一致",
        cppSide.contains("\"<think>\"") && cppSide.contains("\"</think>\""))
    ck("C++ 判据与宿主镜像同源：尾窗口同值（且都是字节单位）",
        pu.contains("kThinkTailWindowBytes = 256") &&
            ktSide.contains("TAIL_WINDOW_BYTES = 256") &&
            ktSide.contains("toByteArray(Charsets.UTF_8)"))
    ck("C++ 三条分支齐全（none / open-only / closed）",
        cppSide.contains("kOpenOnly") && cppSide.contains("kClosed") && cppSide.contains("kNone"))
    // 镜像现在是**转发**，不再是"另一套近似"——判据本体只有一处。
    ck("宿主镜像只做转发（判据本体唯一：ThinkStream.classifyTail）",
        File(src, "RenderedPrompt.kt").readText().contains("ThinkStream.classifyTail(renderedPrompt)") &&
            File(src, "ThinkingControl.kt").readText().contains("ThinkStream.classifyTail(renderedPrompt)"))
    // 出口的**调用点**两个（无 tools / 带 tools），且都必须把生成后缀一起交回宿主 ——
    // 只交 prompt 不交后缀，是 0.9.87 那次"genPrompt=无"的真因（见 RenderedPrompt.kt）。
    ck("两条渲染路径都经同一个语义标注出口（不得各判一次）",
        jni.split("return new_rendered_prompt(env, cp.prompt, cp.generation_prompt);").size - 1 == 2)
    ck("该出口必须把生成后缀一起交回（缺了它结构化输出的 grammar 会与真 prompt 分叉）",
        jni.contains("cp.generation_prompt"))

    // ---- 9. 源码级守卫：彻底删掉宿主那份字符串近似 ----
    // 留着它 = 下次还会有人用；而它的语义与真实需求（"闭合标签会不会出现在模型输出里"）
    // 并不等价，这正是本次故障。
    ck("ThinkStream 不再提供 promptEndsWithOpenThink（近似判据已删除）",
        !ts.contains("fun promptEndsWithOpenThink"))
    ck("HttpApi 不再自行判定 openAtStart（只消费渲染侧结论）",
        http.contains("rendered.openAtStart && !soft") && !http.contains("promptEndsWithOpenThink"))
    ck("SoftSwitch 注入后 openAtStart 落到 false（不得翻真，ISSUE #106）",
        !http.contains("rendered.openAtStart || soft"))
    ck("App 内聊天路径同步改为消费 RenderedPrompt（不得只改 HTTP 一条）",
        File(src, "EngineActivity.kt").readText().let {
            it.contains("softSwitchApplies(thinkingOn, chatTemplate, rendered.text)") &&
                !it.contains("applyToPrompt(rendered,") })

    // ---- 10. 源码级守卫（续）----
    // 状态机只有一份：HttpApi 不得再内联一份 feed/partialTag（否则又变成"改一处漏一处"）。
    ck("HTTP 路径走 ThinkStream", http.contains("ThinkStream("))
    ck("HTTP 路径不再内联 partialTag（状态机只有一份）", !http.contains("fun partialTag("))
    ck("状态机剥离嵌套开标签（否则折叠嵌套复发）", ts.contains("嵌套开标签") && ts.contains("nested"))

    println("=== ThinkStream 单测 ${if (f == 0) "全部通过" else "$f 条失败"} ===")
    if (f != 0) kotlin.system.exitProcess(1)
}
