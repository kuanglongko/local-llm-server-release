package com.xiaowan.localinference

import java.io.File

/**
 * 模块 C 四条修复的**宿主侧行为复刻测试**。
 *
 * 为什么不能只靠源码守卫：`run_pure_logic_guard.sh` 钉的是"结构没改回去"，
 * 它证明不了"这套结构在**边界输入**下真的对"。而 C-1 尤其没有宿主单测可达的入口
 * —— `SessionStore.capChars` 签名里带 `android.content.Context`（只为打日志），
 * 宿主下没有 Context 实现（android.jar 里是抛 `Stub!` 的空壳）。
 *
 * 因此这里把 `capChars` 的控制流**逐字复刻**成纯逻辑跑（只把日志换成计数），
 * 并对**旧写法**同时跑一遍做对照：同一组输入下旧写法必须暴露出问题。
 * 复刻与实现是否脱钩，由 `run_pure_logic_guard.sh` 的源码断言兜底
 * （它钉住 `while (msgs.size > 2 && total() > MAX_CHARS)` 与末条截断那两行）。
 *
 * C-2 / C-3 / C-4 的复刻对象是可以直接编译的纯逻辑文件（无 android 依赖），
 * 所以那三条在 `run_thinking_tests.sh` / `run_sampling_tests.sh` 里用**真实现**断言，
 * 本文件只补 C-1 与"跨文件判据一致"的那两条。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private const val MAX_CHARS = 24000
private const val MIN_MSGS = 2

/** 复刻修复后的 `SessionStore.capChars`（日志换成返回值中的 dropped 计数）。 */
private fun capCharsNew(msgs: MutableList<Pair<String, String>>): Int {
    fun total() = msgs.sumOf { it.second.length }
    while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)
    if (total() <= MAX_CHARS) return 0
    var over = total() - MAX_CHARS
    var dropped = 0
    for (i in msgs.indices.reversed()) {
        if (over <= 0) break
        val cur = msgs[i].second
        val cut = minOf(over, cur.length)
        if (cut > 0) {
            msgs[i] = msgs[i].first to cur.substring(0, cur.length - cut)
            over -= cut; dropped += cut
        }
    }
    return dropped
}

/** 复刻修复**前**的写法（`size > 2` 当先决条件的复合 while）。 */
private fun capCharsOld(msgs: MutableList<Pair<String, String>>) {
    while (msgs.sumOf { it.second.length } > MAX_CHARS && msgs.size > 2) msgs.removeAt(0)
}

fun main() {
    println("=== 纯逻辑行为复刻测试（模块 C）===")
    val src = File("app/src/main/java/com/xiaowan/localinference")
    val ss = File(src, "SessionStore.kt").readText()

    // ---- C-1：只剩 2 条时保险丝必须仍生效（旧写法在这里完全失效）----
    run {
        val two = mutableListOf("user" to "A".repeat(30000), "assistant" to "B".repeat(30000))
        val oldCopy = two.map { it.first to it.second }.toMutableList()
        capCharsOld(oldCopy)
        val oldLen = oldCopy.sumOf { it.second.length }
        ck("C-1 对照：旧写法在只剩 2 条时完全失效（注释称 ≤24000，实际 $oldLen）", oldLen > MAX_CHARS)

        val newCopy = two.map { it.first to it.second }.toMutableList()
        capCharsNew(newCopy)
        val newLen = newCopy.sumOf { it.second.length }
        ck("C-1 修复：只剩 2 条时把超限量落到末条（$newLen <= $MAX_CHARS）", newLen <= MAX_CHARS)
        ck("C-1 修复：保留两条消息（不把对话删空）", newCopy.size == 2)
        ck("C-1 修复：从最新一条往前截（末条先动）",
            newCopy.last().second.length < 30000)
        ck("C-1 修复：两条合起来必须落回上限内（旧写法在此恒漏）", newLen <= MAX_CHARS)
    }

    // ---- C-1：正常情形（3 条以上）仍走"丢最早"的老路径 ----
    run {
        val many = mutableListOf(
            "u" to "x".repeat(15000), "a" to "y".repeat(15000), "u" to "z".repeat(15000))
        val c = many.map { it.first to it.second }.toMutableList()
        capCharsNew(c)
        ck("C-1：3 条超限时先丢最早的历史，再落到上限内",
            c.size == 2 && c.sumOf { it.second.length } == MAX_CHARS)
    }

    // ---- C-1：未超限时一个字节都不动（保险丝不得误伤）----
    run {
        val ok = mutableListOf("u" to "a".repeat(100), "a" to "b".repeat(100))
        val c = ok.map { it.first to it.second }.toMutableList()
        ck("C-1：未超限时不丢字节", capCharsNew(c) == 0 && c.sumOf { it.second.length } == 200)
        ck("C-1：未超限时消息条数不变", c.size == 2)
    }

    // ---- C-1：单条就超上限（极端）也要落到上限内 ----
    run {
        val one = mutableListOf("u" to "q".repeat(50000), "a" to "r".repeat(100))
        val c = one.map { it.first to it.second }.toMutableList()
        capCharsNew(c)
        ck("C-1：单条远超上限时也会被截到上限内",
            c.sumOf { it.second.length } <= MAX_CHARS)
    }

    // ---- C-2：半个标签不是内容（对真实现断言，见 run_thinking_tests.sh）----
    // 这里只补一条"跨文件判据一致"：ThinkStream 不得重新依赖 android.util.Log，
    // 否则宿主单测直接 `Stub!` 崩（本轮曾犯，被 run_thinking_tests.sh 抓到）。
    ck("C-2：ThinkStream 零 android import（纯逻辑契约）",
        ss.isNotEmpty() && !File(src, "ThinkStream.kt").readText().lines()
            .any { it.startsWith("import android") })

    // ---- C-1 复刻与实现不得脱钩（源码级锚点，与守卫同口径）----
    ck("C-1：实现里存在两级裁剪（丢历史 + 逐条截断）",
        ss.contains("while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)") &&
            ss.contains("msgs[i].put(\"content\", cur.substring(0, cur.length - cut))"))
    ck("C-1：旧形状已从实现中消失",
        !ss.contains("sumOf { it.optString(\"content\").length } > 24000 && msgs.size > 2"))

    println(if (f == 0) "=== 纯逻辑复刻测试全部通过 ===" else "=== 纯逻辑复刻测试失败 $f 条 ===")
    if (f != 0) kotlin.system.exitProcess(1)
}
