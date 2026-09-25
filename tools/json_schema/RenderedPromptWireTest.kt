package com.xiaowan.localinference

// RenderedPrompt 的**回传格式**（native 渲染侧 -> 宿主）单测。
//
// ═══════════════════════════════════════════════════════════════════════════
// 这份测试防的是什么（0.9.87 真机故障的判据）
// ═══════════════════════════════════════════════════════════════════════════
// 0.9.87 上，结构化输出仍与修复前逐字一样（content 前面多一段
// `<|im_start|>assistant\n<think>\n`），日志里两行自证：
//
//     >> newSampler ... schema=有 genPrompt=无
//     [schema] templates_apply 产出 grammar: ... add_gen_prompt=1(调用方未提供（退回默认 true）)
//                                              gen_prompt=<|im_start|>assistant\n<think>\n
//
// `genPrompt=` 这个入参**存在**（说明新 native+Kotlin 都已上线），但值是"无" ——
// 即调用方**根本没把生成后缀传下去**。
//
// 真因：native 的渲染出口 `new_rendered_prompt` 只往前缀里塞了 `I`/`O` 一个字节，
// **没有把 cp.generation_prompt 交回宿主**；于是 `RenderedPrompt.generationSuffix`
// 永远是 null，`RequestContext.genPromptArg` 只能返回 null，native 侧退回旧口径
// （`enable_thinking` 取 C++ 默认 true）：
//
//     库算出的后缀（错） = "<|im_start|>assistant\n" + "<think>\n"            （30B）
//     真 prompt 的后缀   = "<|im_start|>assistant\n" + "<think>\n\n</think>\n\n"（41B）
//
// 库把生成后缀当作"已经咽下去的前缀"，只约束"前缀之后"的输出。前缀对不上，
// 模型就得**自己把那个尾巴补一遍**才轮到满足 grammar —— 于是 content 里出现
// 那段尾巴，数组那个用例更是"尾巴花光预算，只剩最短合法形态 `[ ]`"。
//
// 所以判据必须是**回传格式的往返**，而不是"某个函数里有没有某个字段"：
// 前者能在"字段加上了但没接线"时变红，后者不会（这正是本仓库栽过几次的自欺模式）。
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

/**
 * 复刻 native 侧 new_rendered_prompt 的拼法（逐字对齐，见 llama_jni.cpp）。
 *
 * 长度单位必须是 **UTF-16 code unit**（`String.length`），不是 UTF-8 字节 ——
 * native 侧用的是 `utf16_len()`，宿主侧按 `substring` 切，两边单位一致才对得上。
 * 纯 ASCII 后缀（`<|im_start|>assistant\n` 那几种）下两种单位**恰好相等**，
 * 所以单位错了在真机的常见模型上完全看不出来；一旦模板的生成后缀含非 ASCII
 * 就会错位。下面有一组多字节用例专门钉这一点。
 */
private fun nativeEncode(prompt: String, genSuffix: String, openAtStart: Boolean): String {
    val hex = genSuffix.length.toString(16).padStart(8, '0')
    return (if (openAtStart) "I" else "O") + hex + genSuffix + prompt
}

fun main() {
    val m5Think = "<|im_start|>assistant\n<think>\n"
    val m5NoThink = "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    val m5GenRoot = "<|im_start|>assistant\n"
    val prompt = "<|im_start|>user\nWho is the CEO of OpenAI?<|im_end|>\n" + m5NoThink

    // ══════════════════════════════════════════════════════════════════
    // 1. 往返：native 编码 -> 宿主 parse，三件事一个都不能丢
    // ══════════════════════════════════════════════════════════════════
    run {
        val raw = nativeEncode(prompt, m5NoThink, openAtStart = true)
        val rp = RenderedPrompt.parse(raw)
        ck("往返：prompt 原文逐字节一致", rp.text == prompt)
        ck("往返：openAtStart 保留", rp.openAtStart)
        ck("往返：生成后缀**非 null**（0.9.87 就是这里丢了）", rp.generationSuffix != null)
        ck("往返：生成后缀逐字节一致（含换行）", rp.generationSuffix == m5NoThink)
    }
    run {
        val raw = nativeEncode(prompt, m5GenRoot, openAtStart = false)
        val rp = RenderedPrompt.parse(raw)
        ck("往返：openAtStart=false 也保留", !rp.openAtStart)
        ck("往返：后缀换行不被分隔符切错（22B 那个）", rp.generationSuffix == m5GenRoot)
        ck("往返：prompt 里也有换行，仍能完整取回", rp.text == prompt)
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. 与真 prompt 的对齐判据（这是本轮要修的那个不等式的直接断言）
    // ══════════════════════════════════════════════════════════════════
    run {
        val rp = RenderedPrompt.parse(nativeEncode(prompt, m5NoThink, openAtStart = false))
        ck("真实 prompt 以宿主交回的生成后缀结尾（对齐成立）",
            rp.text.endsWith(rp.generationSuffix!!))
    }
    run {
        // 反例：用库自算的"思考开"后缀（30B）去对真 prompt（41B 尾巴）—— 对不上。
        ck("库自算的思考开后缀**不等于**真 prompt 的尾巴（这就是分叉本身）",
            !prompt.endsWith(m5Think))
        ck("两者不等正是 content 里多出那段尾巴的原因（判据可证伪）",
            !prompt.endsWith(m5Think) && prompt.endsWith(m5NoThink))
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. 空后缀 vs 未知 —— 两个方向都不能折错
    // ══════════════════════════════════════════════════════════════════
    run {
        val rp = RenderedPrompt.parse(nativeEncode(prompt, "", openAtStart = false))
        ck("空后缀解析成空串（不是 null）", rp.generationSuffix == "")
        ck("空后缀 != null（'确认没有' 与 '不知道' 必须分得开）", rp.generationSuffix != null)
        ck("空后缀时 prompt 仍然是完整的", rp.text == prompt)
    }
    run {
        val rp = RenderedPrompt.parse("O" + prompt)
        ck("旧 native（无长度段）-> 后缀 null（未知），不拿空串顶替",
            rp.generationSuffix == null)
        ck("旧 native -> prompt 原样保留（不含长度段）", rp.text == prompt)
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 畸形输入不得"猜"（宁可退化成未知，也不要说一个错的后缀）
    // ══════════════════════════════════════════════════════════════════
    run {
        ck("长度段含非十六进制 -> 未知", RenderedPrompt.parse("Ozzzzzzzz" + prompt).generationSuffix == null)
        ck("长度超出剩余正文 -> 未知", RenderedPrompt.parse("Offffffff" + prompt).generationSuffix == null)
        ck("长度段太短 -> 未知", RenderedPrompt.parse("O123" + prompt).generationSuffix == null)
        ck("畸形输入仍是 null 而不是空串（不得把'不知道'说成'确认没有'）",
            RenderedPrompt.parse("Offffffff" + prompt).generationSuffix != "")
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. 折给 native 的入参：非 null 的后缀必须**原样**传下去
    // ══════════════════════════════════════════════════════════════════
    run {
        val rp = RenderedPrompt.parse(nativeEncode(prompt, m5NoThink, openAtStart = false))
        val arg = RequestContext.genPromptArg(rp, addAss = true)
        ck("genPromptArg 用的是渲染侧交回的后缀（不再是 null）", arg == m5NoThink)
        ck("genPromptArg 的结果 != 库自算的那个后缀（分叉已消除）", arg != m5Think)
    }
    run {
        val rp = RenderedPrompt.parse(nativeEncode(prompt, "", openAtStart = false))
        ck("渲染侧说'没有生成后缀' -> 空串下传", RequestContext.genPromptArg(rp, addAss = true) == "")
    }

    // ══════════════════════════════════════════════════════════════════
    // 6. 长度单位必须是 UTF-16 code unit（不是 UTF-8 字节）
    // ══════════════════════════════════════════════════════════════════
    // 这一节防的是一处**只在部分模板上才显形**的错位：ASCII 后缀下
    // "字节数" 与 "UTF-16 单元数" 相等，所以常见的 `<|im_start|>assistant\n`
    // 那几种（22/30/41）根本区分不出来；换一个含汉字或 emoji 的生成后缀，
    // 按字节切就会把后缀截短 / 把 prompt 头部当后缀吃掉。
    run {
        val cjk = "思考\n"                       // 5 bytes / 3 UTF-16 units
        ck("多字节后缀：字节数 != UTF-16 单元数（用例本身有效）",
            cjk.toByteArray(Charsets.UTF_8).size != cjk.length)
        val rp = RenderedPrompt.parse(nativeEncode(prompt, cjk, openAtStart = false))
        ck("多字节后缀（汉字）往返不丢", rp.generationSuffix == cjk)
        ck("多字节后缀（汉字）prompt 完整", rp.text == prompt)
    }
    run {
        val emoji = "\uD83D\uDE00 done\n"        // 代理对：Java 记 2 个 unit
        ck("代理对后缀：单元数 > 字符数（用例本身有效）", emoji.length == 8)
        val rp = RenderedPrompt.parse(nativeEncode(prompt, emoji, openAtStart = false))
        ck("代理对后缀往返不丢", rp.generationSuffix == emoji)
        ck("代理对后缀 prompt 完整", rp.text == prompt)
    }

    println("=== RenderedPromptWireTest ${if (fail == 0) "ALL PASS" else "$fail 条失败"} ===")
    if (fail != 0) kotlin.system.exitProcess(1)
}
