package com.xiaowan.localinference

/**
 * `RagResumeUi`（知识库列表里那一句状态）的离线单测。
 *
 * 钉的是这个对象存在的**全部意义**：把"点「建索引」这一下要等多久"
 * 变成一句用户一眼能读的话，且**五态不许塌成一个**。它的失效形态全是
 * "状态看起来对、其实指错方向"：
 *   · 「可续」与「待重建」塌成一句 -> 用户分不清"补 20 块"与"重跑两小时"；
 *   · 「可续」与「正文已改」写反   -> 半份索引被报成"你改过正文"，
 *     而他从没改过（本仓库在"待重建 vs 正文已改"上真栽过这一族）；
 *   · 「未落盘」被画成「待重建」   -> 用户在一个 0 条的库上反复点重建（ISSUE #259 ③）；
 *   · 报"预计还要多久"             -> 块耗时差一个数量级，推出来的剩余**假精确**。
 * 都不抛异常、不崩溃，只有断言能钉住。
 */
object RagResumeUiTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    fun run(): Int {
        // ① 五态各说各话，**不许塌成一个**
        val pending = RagResumeUi.state(true, false, false, false, 0, 0)
        val needBuild = RagResumeUi.state(false, false, false, false, 0, 0)
        val resumable = RagResumeUi.state(false, true, true, true, 20, 100)
        val bodyChanged = RagResumeUi.state(false, true, false, false, 0, 0)
        val clean = RagResumeUi.state(false, true, true, false, 0, 0)
        ok(pending.contains("未落盘"), "未落盘：说得出它还没落盘")
        ok(needBuild.contains("待重建"), "没有索引：待重建")
        ok(resumable.contains("可续"), "半份：可续（不是待重建）")
        ok(bodyChanged.contains("正文已改"), "正文不一致：正文已改")
        ok(clean.isEmpty(), "齐整索引且正文对得上：什么都不说")
        ok(listOf(pending, needBuild, resumable, bodyChanged).distinct().size == 4,
            "四句状态互不相同（塌成一句就是本轮要消灭的静默）")

        // ①b 「可续」不许被误报成「正文已改」/「待重建」
        ok(!resumable.contains("正文已改"), "可续的那一句里**不出现**『正文已改』")
        ok(!resumable.contains("待重建"), "可续的那一句里**不出现**『待重建』")

        // ② 可续时说清"续到哪"（分母有意义时）
        ok(resumable.contains("20") && resumable.contains("100"),
            "可续时报出 已编/总数（用户据此判断还要多久）")

        // ③ 分母不成立时退化成不报分母，**不许出现 0/0 或 NaN**
        val zeroTotal = RagResumeUi.state(false, true, true, true, 5, 0)
        ok(zeroTotal.contains("可续"), "resumeCount>rowsTotal 时仍报可续")
        ok(!zeroTotal.contains("0/0"), "分母不成立时不出现 0/0")
        ok(!zeroTotal.contains("NaN"), "任何输入都不出现 NaN")

        // ④ 绝不报"预计剩余"（假精确）
        val all = listOf(pending, needBuild, resumable, bodyChanged, zeroTotal)
        ok(all.none { it.contains("剩余") }, "任何状态都不报『剩余』")
        ok(all.none { it.contains("预计") }, "任何状态都不报『预计』")

        // ⑤ buildHint：续与重来的**措辞必须分开**
        val hintResume = RagResumeUi.buildHint(true, true, true, 20, 100)
        val hintRework = RagResumeUi.buildHint(true, false, false, 0, 0)
        val hintNone = RagResumeUi.buildHint(true, true, false, 0, 0)
        ok(hintResume != null && hintResume.contains("21"), "可续：从第 21 块接着编（1-based 说给人听）")
        ok(hintRework != null && hintRework.contains("整份重编"), "不可续：明说会**整份重编**（不许静默从头）")
        ok(hintNone == null, "齐整索引：没什么要特别说的（返回 null）")
        ok(hintResume != hintRework, "续与重来不是同一句话")

        // ⑥ 纯函数：同输入同输出（无隐藏状态）
        ok(RagResumeUi.state(false, true, true, true, 20, 100) == resumable,
            "同一输入 -> 同一输出")

        println()
        println("RagResumeUiTest: PASS $pass / FAIL $fail")
        return if (fail == 0) 0 else 1
    }
}

fun main() {
    kotlin.system.exitProcess(RagResumeUiTest.run())
}
