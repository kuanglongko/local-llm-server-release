package com.xiaowan.localinference

// FinishReason 的离线单测：四档优先级 + "截断"判据只认 hitLimit。
//
// 为什么必须钉：这一行的旧形态是
//     if (cancelled) "cancelled" else if (toolCallsJson != null) "tool_calls" else "stop"
// ——**没有 length**。被 max_tokens 掐断时报 stop，SDK 会把半截输出当完整回答
// 存库/缓存，而 HTTP 200、响应体合法、没有异常。这是"字段在、值也在，只是值错了"，
// 比"字段缺失"更难查（缺字段至少 SDK 会抱怨）。
//
// 每个断言对应一种**档位塌陷**：某一档永远不可达，或某一档被另一档吃掉。
private var fail = 0
private fun ck(name: String, cond: Boolean) {
    if (cond) println("PASS  $name") else { println("FAIL  $name"); fail++ }
}

fun main() {
    // ── 1. 四档各自可达 ────────────────────────────────────────────────────
    ck("自然收尾 -> stop",
        FinishReason.of(cancelled = false, hitLimit = false, hasToolCalls = false) == "stop")
    ck("命中工具 -> tool_calls",
        FinishReason.of(cancelled = false, hitLimit = false, hasToolCalls = true) == "tool_calls")
    ck("跑满上限 -> length（**旧实现报的是 stop**）",
        FinishReason.of(cancelled = false, hitLimit = true, hasToolCalls = false) == "length")
    ck("被取消 -> cancelled",
        FinishReason.of(cancelled = true, hitLimit = false, hasToolCalls = false) == "cancelled")

    // ── 2. 优先级：cancelled > length > tool_calls > stop ───────────────────
    // 顺序错了就是某一档永远不可达。逐对判，不只判"能算出来"。
    ck("cancelled 压过 length（被取消且正好跑满，取 cancelled）",
        FinishReason.of(cancelled = true, hitLimit = true, hasToolCalls = false) == "cancelled")
    ck("cancelled 压过 tool_calls",
        FinishReason.of(cancelled = true, hitLimit = false, hasToolCalls = true) == "cancelled")
    ck("cancelled 压过 length 且压过 tool_calls（三档同真）",
        FinishReason.of(cancelled = true, hitLimit = true, hasToolCalls = true) == "cancelled")
    // length 压过 tool_calls：截断意味着 tool_calls 的 JSON 本身可能就不完整，
    // 按它去执行会出错 —— 让调用方知道"被掐了"比"有个工具要调"更重要。
    ck("length 压过 tool_calls（截断的 tool_calls JSON 可能不完整）",
        FinishReason.of(cancelled = false, hitLimit = true, hasToolCalls = true) == "length")
    // tool_calls 压过 stop
    ck("tool_calls 压过 stop",
        FinishReason.of(cancelled = false, hitLimit = false, hasToolCalls = true) == "tool_calls")

    // ── 3. 枚举值本身：OpenAI 有的三个必须逐字对 ────────────────────────────
    ck("LENGTH 就是字符串 length", FinishReason.LENGTH == "length")
    ck("TOOL_CALLS 就是字符串 tool_calls", FinishReason.TOOL_CALLS == "tool_calls")
    ck("STOP 就是字符串 stop", FinishReason.STOP == "stop")
    ck("CANCELLED 是自定值（OpenAI 枚举里没有）",
        FinishReason.CANCELLED == "cancelled" && !FinishReason.OPENAI_VALUES.contains("cancelled"))
    // OpenAI 的 finish_reason 枚举值里 length 必须在列 —— 它是本 PR 要补的那一格
    ck("length 在 OpenAI 枚举清单里", FinishReason.OPENAI_VALUES.contains("length"))

    // ── 4. 0 条 / 不崩：入参是布尔，没有第三条分支 ──────────────────────────
    // 穷举 8 种组合，确认**没有哪个组合落到空串或未知值**（那种值 SDK 会读成未知枚举）
    var seen = 0
    for (c in listOf(false, true)) for (h in listOf(false, true)) for (t in listOf(false, true)) {
        val v = FinishReason.of(c, h, t)
        if (v.isNotEmpty() && v in listOf("stop", "length", "tool_calls", "cancelled")) seen++
    }
    ck("8 种布尔组合全部落到四个已知取值之一（没有空串/未知值）", seen == 8)

    println()
    println(if (fail == 0) "=== FinishReasonTest 全部通过 ===" else "=== FinishReasonTest 失败 $fail 项 ===")
    if (fail != 0) kotlin.system.exitProcess(1)
}
