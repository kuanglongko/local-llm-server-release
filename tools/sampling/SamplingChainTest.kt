package com.xiaowan.localinference

import org.json.JSONObject

// 采样链**顺序**与门禁的离线单测。
//
// buildChain() 是 app/src/main/cpp/llama_jni.cpp:nativeNewSampler 建链分支的逐条照抄
// （同样只用 Kotlin 表达，不复制浮点比较）。改动 native 那段 if 顺序时，
// 把这里同步改掉即可在提交前发现语义回归 —— 尤其"惩罚必须排在所有截断之前"。
private var f2 = 0
fun c2(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); f2++ }
}

/** 复刻 llama_jni.cpp:nativeNewSampler 的建链分支（不复制浮点比较，直接照抄条件）。 */
fun buildChain(s: SamplingParams): List<String> {
    val ch = ArrayList<String>()
    if (s.temp <= 0.0f) {
        ch.add("greedy")
        return ch
    }
    if (s.repeatLastN > 0 &&
        (s.repeatPenalty != 1.0f || s.freqPenalty != 0.0f || s.presencePenalty != 0.0f)) {
        ch.add("penalties")
    }
    if (s.topK > 0) ch.add("top_k")
    if (s.topP > 0.0f && s.topP < 1.0f) ch.add("top_p")
    if (s.minP > 0.0f && s.minP < 1.0f) ch.add("min_p")
    ch.add("temp")
    ch.add("dist")
    return ch
}

fun main() {
    fun p(vararg kv: Pair<String, Any>) =
        SamplingParams.fromRequest(JSONObject().apply { kv.forEach { put(it.first, it.second) } }).first!!

    // 全默认：top_p=0.95 是默认档位，它本身就会入链（0<0.95<1）；
    // 无惩罚、无 top_k、无 min_p。顺序上 top_p 在 temp 之前。
    c2("默认链 = [top_p, temp, dist]", buildChain(p()) == listOf("top_p", "temp", "dist"))

    // 惩罚必须排在最前（这是本次修复的核心）
    val all = buildChain(p("temperature" to 0.8, "top_k" to 40, "top_p" to 0.9, "min_p" to 0.05,
        "repeat_penalty" to 1.1, "frequency_penalty" to 0.2, "presence_penalty" to 0.3))
    c2("完整链 = penalties -> top_k -> top_p -> min_p -> temp -> dist",
        all == listOf("penalties", "top_k", "top_p", "min_p", "temp", "dist"))
    c2("penalties 在 top_k 之前", all.indexOf("penalties") < all.indexOf("top_k"))
    c2("penalties 在 min_p 之前（本次修复点）", all.indexOf("penalties") < all.indexOf("min_p"))
    c2("temp 在截断之后（与官方示例一致）", all.indexOf("temp") > all.indexOf("top_p"))
    c2("dist 收尾", all.last() == "dist")

    // 单独 presence_penalty 必须能挂上 penalties（B 的核心）
    val onlyPres = buildChain(p("presence_penalty" to 0.5))
    c2("单独 presence_penalty -> 链里有 penalties", onlyPres.contains("penalties"))
    c2("单独 presence_penalty 只多出 penalties（top_p 来自默认档）",
        onlyPres == listOf("penalties", "top_p", "temp", "dist"))

    // 只有 repeat_penalty=1（关闭）时不该挂 penalties
    c2("repeat_penalty=1 时无 penalties", !buildChain(p()).contains("penalties"))
    c2("默认档没有任何惩罚类采样器", buildChain(p()).none { it == "penalties" })
    // repeat_last_n=0 关闭惩罚窗口
    c2("repeat_last_n=0 时无 penalties",
        !buildChain(p("repeat_last_n" to 0, "repeat_penalty" to 1.5)).contains("penalties"))

    // temp<=0 -> 纯贪心，不挂任何其他采样器
    c2("temp=0 -> 仅 greedy", buildChain(p("temperature" to 0)) == listOf("greedy"))
    c2("temp=-1 -> 仅 greedy（合法）", buildChain(p("temperature" to -1)) == listOf("greedy"))

    // top_p=1.0 = 关闭，不入链
    c2("top_p=1.0 不入链", !buildChain(p("top_p" to 1.0)).contains("top_p"))
    // min_p=0 = 关闭，不入链
    c2("min_p=0 不入链", !buildChain(p("min_p" to 0)).contains("min_p"))
    // top_k=0 = 关闭，不入链
    c2("top_k=0 不入链", !buildChain(p("top_k" to 0)).contains("top_k"))

    // HTTP 显式 min_p=0.05 仍能生效
    c2("显式 min_p=0.05 入链", buildChain(p("min_p" to 0.05)).contains("min_p"))

    println()
    println(if (f2 == 0) "=== SamplingChainTest 全部通过 ===" else "=== SamplingChainTest 失败 $f2 项 ===")
    if (f2 != 0) kotlin.system.exitProcess(1)
}
