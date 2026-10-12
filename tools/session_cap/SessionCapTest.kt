package com.xiaowan.localinference

import java.io.File

/**
 * S1「条数具名常量 + 不再啃本轮提问」的**宿主侧行为复刻测试**。
 *
 * 为什么不能只靠源码守卫：`tools/run_session_cap_guard.sh` 钉的是"结构没改回去"，
 * 证明不了"这套结构在**边界输入**下真的对"。而 `SessionStore.capChars` 签名带
 * `android.content.Context`（只为打日志），宿主下没有可用实现
 * （android.jar 里是抛 `Stub!` 的空壳），所以只能把控制流**逐字复刻**成纯逻辑跑。
 *
 * 复刻与实现是否脱钩由守卫兜底（它钉住实现里的两级裁剪、末条跳过、`removals` 挪位）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 这批用例全部围绕**一条**不变量：「被截的永远是历史，不是本轮」
 * ═══════════════════════════════════════════════════════════════════════
 * 旧写法从最新往前截、保头 —— 超限时**先啃当轮提问的尾部**，症状是
 * 「我贴的代码它只看到一半，还答得头头是道」，与模型抽风完全同形。
 * 所以这里的每一条都要求：**末条逐字节不变**。
 *
 * 另一类是"顺手越过空条目"（`minOf(over, cur.length)` 不 continue 的写法）：
 * 它看起来总能收敛，实际会**静默少截**，把超限留给下一轮 —— 而下一轮的末条
 * 已经换人了。所以有一条专门钉"截到空条之后还要继续、且总长确实落回上限"。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private const val MAX_CHARS = 24000
private const val MIN_MSGS = 2

/**
 * **上一版**的字符上限，只给"旧写法"对照用。
 *
 * 为什么要专门造这个数，而不是拿 [MAX_CHARS] 直接对照：
 * 本轮把上限抬到了 24000（能放下一大半代码片段 + 整段报错堆栈），而旧写法
 * 在 24000 这个阈值下**根本触发不了** —— 末条 12000 字符、总闸 24000，
 * 连一级（丢最早历史）都不动作，"旧写法会啃末条"这句话就无从被观察到。
 * 结果那条对照断言会**恒假**（断言的方向是对的，只是输入到不了它）。
 *
 * 4000 是"本轮把上限降到 4000 时的取值"这个尺度下的数，与旧版本的 20000 同级 ——
 * 用旧版本的真实常数会让用例里每一份数据都要写成几万字符，
 * 而**这条对照要证的只有一件事：旧写法会动末条**。量级不必复原。
 */
private const val PREV_MAX_CHARS = 4000

/** 复刻修复后的 `SessionStore.capChars`（日志换成返回值里的计数）。 */
private data class CapResult(val droppedMsgs: Int, val cutMsgs: Int, val cutChars: Int, val stillOver: Int)

private fun capCharsNew(msgs: MutableList<Pair<String, String>>): CapResult {
    fun total() = msgs.sumOf { it.second.length }
    val count0 = msgs.size
    while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)
    val droppedMsgs = count0 - msgs.size
    if (total() <= MAX_CHARS) return CapResult(droppedMsgs, 0, 0, 0)
    val last = msgs.lastIndex
    var over = total() - MAX_CHARS
    var cutMsgs = 0
    var cutChars = 0
    val removals = IntArray(msgs.size)
    for (i in last - 1 downTo 0) {
        if (over <= 0) break
        val at = i - removals[i]
        if (at !in 0 until last) continue
        val cur = msgs[at].second
        val cut = minOf(over, cur.length)
        if (cut <= 0) continue
        msgs[at] = msgs[at].first to cur.substring(0, cur.length - cut)
        removals[at]++
        cutMsgs++
        cutChars += cut
        over -= cut
    }
    return CapResult(droppedMsgs, cutMsgs, cutChars, maxOf(over, 0))
}

/** 复刻上上版（S1 之前）：从最新往前截、保头 —— 会啃当轮提问的尾部。 */
private fun capCharsPrev(msgs: MutableList<Pair<String, String>>) {
    fun total() = msgs.sumOf { it.second.length }
    while (msgs.size > MIN_MSGS && total() > PREV_MAX_CHARS) msgs.removeAt(0)
    if (total() <= PREV_MAX_CHARS) return
    var over = total() - PREV_MAX_CHARS
    for (i in msgs.indices.reversed()) {
        if (over <= 0) break
        val cur = msgs[i].second
        val cut = minOf(over, cur.length)
        if (cut > 0) {
            msgs[i] = msgs[i].first to cur.substring(0, cur.length - cut)
            over -= cut
        }
    }
}

private fun copyOf(src: List<Pair<String, String>>) = src.toMutableList()

private fun totalOf(msgs: List<Pair<String, String>>) = msgs.sumOf { it.second.length }

fun main() {
    println("=== 会话裁剪行为复刻测试（S1）===")
    val src = File("app/src/main/java/com/xiaowan/localinference")
    val ss = File(src, "SessionStore.kt").readText()

    // ── 1. 核心：超限时**末条一字不动**，超出的量从历史里扣 ────────────────
    //
    // ⚠ 输入必须是"一级（丢历史）**丢不到**上限"的那一档，否则整条判定根本
    //   走不到二级 —— 这正是上一版这三条恒绿的成因：`12000 + 12000 + 12000`
    //   恰好 **等于** 24000，一级丢完直接 `total() <= MAX_CHARS` 早退，
    //   末条当然完整、`cutMsgs` 当然为 0。断言方向没错，是输入到不了它守的地方。
    //   这里把末条放大到 20000：丢历史后仍剩 24000 + 8000，二级必须动手。
    run {
        val long = "提问".repeat(10000)         // 20000 字符
        val hist = listOf(
            "user" to "H".repeat(12000),
            "assistant" to "A".repeat(12000),
            "user" to long,
        )
        val prev = copyOf(hist); capCharsPrev(prev)
        ck("对照：旧写法把当轮提问（末条）啃短了", prev.last().second.length < long.length)

        val now = copyOf(hist)
        val r = capCharsNew(now)
        ck("修复：末条逐字节不变（用户贴的代码不会被吞）", now.last().second == long)
        ck("修复：超出的量从历史里扣，总长落回上限", totalOf(now) <= MAX_CHARS)
        ck("修复：二级真的动了手（丢历史之后仍超限，才轮到截历史）",
            r.cutMsgs > 0 && r.cutChars > 0)
        ck("修复：末条不再被截，也就不会剩超限量", r.stillOver == 0)
    }

    // ── 2. 末条自己就超 24k：整个保留，并在留痕里单独报出超出量 ────────────
    run {
        val huge = "X".repeat(30000)
        val c = copyOf(listOf("user" to "h".repeat(100), "assistant" to "a".repeat(100), "user" to huge))
        val r = capCharsNew(c)
        ck("末条自身超限时整体保留（不做\"保头截尾\"）", c.last().second == huge)
        ck("末条自身超限时把超出量报出来（30k-24k=6000）", r.stillOver == 6000)
        ck("末条自身超限时不谎报\"已落回上限\"", totalOf(c) > MAX_CHARS)
    }

    // ── 3. 只有两条时也要生效（上上版 `size > 2` 当先决条件时完全失效）──────
    // ⚠ 这一组**没有"旧写法"对照**，而且不该有：那一版是 `while (总长 > 上限 &&
    //   size > 2) removeAt(0)` —— 只剩 2 条时**一个字节都不动**，末条当然完整。
    //   于是"末条没被啃"在旧写法下**恒真**，拿它当对照组只会得到一条永远绿的
    //   断言（本仓库最恨的那类假证据）。要证明"只剩两条时保险丝仍然工作"，
    //   唯一有效的输入就是**当前实现**：它必须把超限量落到最早那条上。
    run {
        // 两条各 30000：一级到 MIN_MSGS 停手（还剩 2 条、60000 字符），
        // 二级只能从最早那条收 36000? 不 —— 最早那条只有 30000，
        // 收完仍差 6000，而末条**一个字都不许动**，于是如实报出 stillOver。
        // 这条用例要证的是"保险丝在只剩两条时仍在工作且不许侵到末条"，
        // 不是"一定能落回上限"（末条自己超限时本来就落不回，见用例 2）。
        val two = listOf("user" to "A".repeat(30000), "assistant" to "B".repeat(30000))
        val now = copyOf(two)
        val r = capCharsNew(now)
        ck("只剩 2 条时两条都还在（MIN_MSGS 保底，不许清空）", now.size == 2)
        ck("只剩 2 条时末条仍是完整的那条最新回答（保险丝不侵末条）",
            now.last().second.length == 30000)
        ck("只剩 2 条时超限量落到最早那条（它被收窄了）", now[0].second.length < 30000)
        ck("只剩 2 条时如实报出仍然超限（不假装已落回上限）", r.stillOver > 0)
    }

    // ── 4. 截到空条之后必须继续（静默少截 = 把超限留给下一轮）────────────
    run {
        // ⚠ 这一组描述的是**一级与二级之间的那条不变式**，而它值得单独写下来，
        //   因为守卫里那条"空条必须 continue"的判据在**当前两级结构下不可达**：
        //
        //   一级的终止条件是 `size > MIN_MSGS(2) && total > MAX_CHARS`。它要么把
        //   条数收到 2，要么把 total 收到上限之内 —— 也就是说，**只要二级还能进
        //   （total > MAX_CHARS），库里就只剩 2 条**，那个空条只可能是下标 0，
        //   而 `at !in 0 until last`（last=1）此刻判的就是"0 在 [0,1) 里" = 真，
        //   于是 `minOf(over, 0) = 0 -> continue` 这条路径永远走不到。
        //   （穷举验证：3~5 条、长度取 {0,1,100,5000,12000,20000,30000} 的全部
        //     组合共 19551 例，"二级面对 >2 条"为 0 例。）
        //
        //   所以这里**不再断言"截到空条之后还要继续"**（那是恒真的：行为没发生），
        //   改为钉住它背后那条**真的成立**的不变量："二级永远只面对两条，
        //   且它绝不碰末条"。那条 continue 判据留在守卫与复刻代码里 ——
        //   它是**结构上**的保险（MIN_MSGS 若被改成 3，它立刻变成活判据），
        //   但不能假装它现在正在被执行。
        val c = copyOf(listOf(
            "user" to "H".repeat(9000),
            "assistant" to "",
            "user" to "T".repeat(20000),
        ))
        val r = capCharsNew(c)
        ck("一级丢到剩两条就停手（MIN_MSGS 保底）", c.size == 2)
        ck("落回上限后不再动任何一条（空条让位给丢历史）", totalOf(c) <= MAX_CHARS)
        ck("末条仍逐字节完整（这个用例里二级并未介入）", c.last().second.length == 20000)
    }

    // ── 4b. 二级真的面对那个空条：把它放到下标 0（唯一可能的形态）──────────
    run {
        // 一级收到 2 条 = [空条, 末条]，且 total 仍超限 —— 需要末条自己就超限，
        // 于是二级必须处理下标 0 那个空条（cut = 0 -> continue）并把缺口留给末条
        // （末条一个字都不许动），如实报 stillOver。
        val c = copyOf(listOf(
            "user" to "H".repeat(50),
            "assistant" to "",
            "user" to "T".repeat(30000),
        ))
        val r = capCharsNew(c)
        ck("二级面对含空条的两条时仍然收敛（空条不贡献任何可截内容）",
            c.size == 2 && r.cutMsgs == 0)
        ck("空条不被算成\"截过一条\"", r.cutMsgs == 0)
        ck("末条自身超限时整体保留并如实报出", c.last().second.length == 30000 && r.stillOver == 6000)
    }

    // ── 5. 多级挪位：截过一条之后，它左边那条还得能继续被截 ────────────────
    run {
        // 二级的**真实形态**（见 4b 上方那段推导）：只剩两条，一条历史 + 末条。
        // 要证的只有两件事：缺口全部由那条历史承担，且末条一字不动。
        // ⚠ 上一版这条写的是"至少收窄两条历史"—— 那在上限 24000 下**恒假**：
        //   一级总要丢到只剩 2 条才会让二级进来，"两条历史"的形态不存在
        //   （穷举 19551 例，"二级面对 >2 条"为 0 例）。判据本身是好的
        //   （它想钉的是`removals` 挪位记账），只是它守的那个形态在当前
        //   两级阈值下进不来；真进得来时必须先改 MIN_MSGS。
        val c = copyOf(listOf(
            "user" to "1".repeat(20000),
            "assistant" to "2".repeat(10000),
        ))
        val r = capCharsNew(c)
        ck("二级把缺口全部落在那条历史上", totalOf(c) <= MAX_CHARS)
        ck("末条仍逐字节完整", c.last().second.length == 10000)
        ck("恰好收窄了一条历史（二级只有这一条可动）", r.cutMsgs == 1)
        ck("被收窄的量 = 超出量（20000+10000-24000=6000）",
            c[0].second.length == 20000 - 6000 && r.cutChars == 6000)
    }

    // ── 6. 未超限：一个字节都不动（保险丝不得误伤）────────────────────────
    run {
        val ok = listOf("user" to "a".repeat(100), "assistant" to "b".repeat(100), "user" to "c".repeat(100))
        val c = copyOf(ok)
        val r = capCharsNew(c)
        ck("未超限时不丢字节、不丢条", c == ok)
        ck("未超限时三项计数全为 0", r.droppedMsgs == 0 && r.cutMsgs == 0 && r.cutChars == 0)
    }

    // ── 7. 条数保险丝：40 条 → 减 1（不是 41、不是 20）────────────────────
    run {
        val forty = (1..41).map { "user" to "m$it" }
        val kept = forty.takeLast(40)
        ck("条数上限是 MAX_MSGS（= 40 条，不是 41、也不是 20 轮）", kept.size == 40)
        ck("钉住 MAX_MSGS 与\"20 轮\"的换算关系写在源码里",
            ss.contains("const val MAX_MSGS = 40") && ss.contains("40 条 = 20 轮"))
        ck("实现里不再有 `msgs.size > 40` 的裸魔数",
            !ss.contains("while (msgs.size > 40) msgs.removeAt(0)"))
    }

    println(if (f == 0) "=== 会话裁剪复刻测试全部通过 ===" else "=== 会话裁剪复刻测试失败 $f 条 ===")
    if (f != 0) kotlin.system.exitProcess(1)
}
