package com.xiaowan.localinference

/**
 * 「这个库现在是什么状态」这一层的离线单测（ISSUE #259 第二批）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么这一层必须由单测钉住
 * ══════════════════════════════════════════════════════════════════════
 * 用户一次报了五条，其中三条的**根**都是"同一件事在屏幕上只有一个答案，
 * 而代码里有好几个判据在回答它"：
 *
 *   · ① 库名框里的字面值 vs 提交过的真值 -> 屏幕上说"新建了库 / 该库不存在"；
 *   · ③ 「未落盘」vs「待重建」-> 两者处置**相反**，而用户看到的是同一行字；
 *   · ⑤ "有索引" vs "有库" -> 一个半成品库每个按钮都灰着，而它确实在磁盘上。
 *
 * 它们全都不崩、不报错、日志干净 —— 正是最该由断言钉住的那一类。
 * 这一份只测**纯逻辑**（`Info` 的状态分类），不碰 Context / 磁盘：
 * 状态分类错一个分支，屏幕上就是一句假话，而那种错没有任何现象。
 *
 * ⚠ `isPending` 默认值必须是 `false`：`Info` 在库列表那条路上永远是
 *   "磁盘上真有的库"（`list()` 只列它们）。默认给 `true` 的话，
 *   任何一处忘了传这个参数的构造都会把一个真库说成"还没落盘" ——
 *   而"还没落盘"是个**会被丢弃**的状态，用户读到它会以为内容不用管了。
 */
object RagKbStateTest {

    private var pass = 0
    private var fail = 0

    private fun ck(name: String, cond: Boolean) {
        if (cond) { pass++; println("PASS  $name") } else { fail++; println("FAIL  $name") }
    }

    /** 造一个"磁盘上有正文、没有索引"的库（导入之后的正常中间状态）。 */
    private fun pendingLike() = RagIndexStore.Info(
        "n", 0, 0, 0L, 0, false, false, isPending = false)

    /** 造一个"只有界面上的名字、磁盘上什么都没有"的库。 */
    private fun virtual() = RagIndexStore.Info(
        "n", 0, 0, 0L, 0, false, false, isPending = true)

    /** 造一个"有索引且同步"的库。 */
    private fun ready() = RagIndexStore.Info(
        "n", 3, 384, 1L, 480, true, true, isPending = false)

    @JvmStatic
    fun main(args: Array<String>) {
        // ── ① 三态互斥且都能表达出来 ─────────────────────────────────────
        ck("① 有索引且同步 -> onDisk（磁盘上有它）", ready().onDisk)
        ck("① 待重建（有正文、无索引）-> onDisk（**磁盘上有它**，这是关键）",
            pendingLike().onDisk)
        ck("① 未落盘（磁盘上什么都没有）-> !onDisk", !virtual().onDisk)
        ck("① 未落盘不是『待重建』：两者由不同字段表达",
            virtual().isPending != pendingLike().isPending)

        // ── ② deletable：与 onDisk 同源，且**不看有没有索引** ──────────
        // 用户报的第 ⑤ 条：半成品库（有目录、无索引）必须删得掉。
        ck("② 待重建的库可删（**不看有没有索引**：旧判据就是死在这里）",
            pendingLike().deletable)
        ck("② 未落盘的库不可删（磁盘上没有东西可删 —— 它由『切走』丢弃）",
            !virtual().deletable)
        ck("② 有索引的库可删", ready().deletable)
        ck("② deletable 与 onDisk 恒等（同一个问句不许有两个答案）",
            listOf(ready(), pendingLike(), virtual()).all { it.deletable == it.onDisk })

        // ── ③ hasIndex 与 onDisk 是**两个**问题 ─────────────────────────
        // 合成一个的后果就是用户那两条：
        //   · "这俩按钮有啥区别"（0 条的库上两个都"能删"，而屏幕上说不清）；
        //   · "该库不存在（还没落盘任何东西）"（有正文的库被说成不存在）。
        ck("③ 待重建的库：onDisk 为真而 hasIndex 为假（两个问题必须分开）",
            pendingLike().onDisk && !pendingLike().hasIndex)
        ck("③ 有索引的库：两者都为真", ready().onDisk && ready().hasIndex)
        ck("③ 未落盘的库：两者都为假", !virtual().onDisk && !virtual().hasIndex)

        // ── ④ 默认值：忘了传 isPending 时**不许**把真库说成未落盘 ───────
        val noFlag = RagIndexStore.Info("n", 0, 0, 0L, 0, false, false)
        ck("④ 不传 isPending 时默认 false（默认 true 会把真库说成『会丢弃』）",
            !noFlag.isPending && noFlag.onDisk && noFlag.deletable)

        // ── ⑤ 一个 0 条的库仍然是"可删的库" ─────────────────────────────
        // 这正是用户报的第 ③ 条：0 条时两个删除按钮的差别只剩"目录留不留"，
        // 而**两条路都必须走得通**（以前两条都走不通：一条要索引、一条要正文）。
        val emptyButReal = RagIndexStore.Info("n", 0, 0, 0L, 0, false, false, isPending = false)
        ck("⑤ 0 条但磁盘上有目录 -> 可删（『按钮全灰』的根因就在这里）",
            emptyButReal.onDisk && emptyButReal.deletable)
        ck("⑤ 0 条且没有索引 -> 仍然不是『未落盘』（它在磁盘上，只是空的）",
            !emptyButReal.isPending)

        println()
        if (fail == 0) println("RagKbStateTest: PASS $pass / FAIL 0")
        else { println("RagKbStateTest: PASS $pass / FAIL $fail"); kotlin.system.exitProcess(1) }
    }
}
