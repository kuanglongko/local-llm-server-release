package com.xiaowan.localinference

/**
 * `EmbedBatchStats` 的离线单测（ISSUE #269 第 1 步：先量，再改）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么这一层要单测
 * ═══════════════════════════════════════════════════════════════════════
 * 它本身不改任何判定（[EmbedBatchStats.record] 只累加），所以"算错"不会
 * 让建库坏掉 —— 但它算错时**没有任何东西会红**：
 *   · 批次数与条数对不上（一个涨一个没涨）—— 读数看着像个正常数；
 *   · 负耗时被记进去 —— 平均毫秒变成无意义的数，而它看着像个正常数；
 *   · [EmbedBatchStats.reset] 后仍有残留 —— 换了模型但读数还是上一个模型的。
 * 这三种都只有断言能钉住。**纯逻辑**（不碰 Android、不碰文件），
 * 所以能直接跑在宿主上。
 */
object EmbedBatchStatsTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") }
        else { fail++; println("FAIL  $what") }
    }

    private fun eqi(a: Any?, b: Any?, what: String) = ok(a == b, "$what（实际 $a）")

    @JvmStatic
    fun main(args: Array<String>) {
        reset()

        // ── 空态：一次都没跑过时，比例是 null 而**不是** 0.0 ──────────────
        // 为什么必须区分：0.0 会被读成"合批命中率 0%"（= 合批完全没生效，是个结论），
        // 而真相是"还没跑过"（= 没有读数）。两者在 /health 上会给出完全相反的行动。
        val s0 = EmbedBatchStats.snapshot()
        eqi(s0.batchedBatches, 0L, "空态：合批批次 = 0")
        eqi(s0.batchedEntryRatio, null, "空态：比例是 null（不是 0.0 —— 那会被读成「合批没生效」）")
        eqi(s0.lastRoute, null, "空态：最近一批的路由是 null")
        ok(EmbedBatchStats.describe(s0, EmbedBatchStats.kScopeCumulative).contains("还没跑过"),
            "空态：累计口径说「还没跑过」")
        // ⚠ 段口径的空态是**另一句话**："这一段建库一条都没编过"（多半建库在开头就
        // 失败了），而不是"本次运行还没跑过"。两者该去的方向不同 —— 合成一句时，
        // "建库一开始就挂了"会被读成"还没用过嵌入模块"。
        ok(EmbedBatchStats.describe(s0, EmbedBatchStats.kScopeThisBuild).contains("这一段建库"),
            "空态：段口径说「这一段建库一条都没编过」（不是「本次运行还没跑过」）")

        // ── 一批合批 ──────────────────────────────────────────────────────
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 8, 120))
        val s1 = EmbedBatchStats.snapshot()
        eqi(s1.batchedBatches, 1L, "合批一批：批次数 = 1")
        eqi(s1.batchedEntries, 8L, "合批一批：条数 = 8")
        eqi(s1.batchedMs, 120L, "合批一批：毫秒 = 120")
        eqi(s1.fellBackBatches, 0L, "合批一批：回落批次仍为 0（两类互斥）")
        eqi(s1.lastRoute, EmbedBatchStats.Route.BATCHED, "合批一批：最近路由 = BATCHED")
        eqi(s1.lastBatchEntries, 8, "合批一批：最近批条数 = 8")
        eqi(s1.lastBatchMs, 120L, "合批一批：最近批耗时 = 120")

        // ── 一批回落 ──────────────────────────────────────────────────────
        // ⚠ 回落**不进** batchedMs：它走的是逐条路径，那份耗时不是合批省下的。
        // 记进去会让"合批花了多少毫秒"这个读数把逐条的时间也算进去 —— 看着正常。
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.FELL_BACK, 8, 999))
        val s2 = EmbedBatchStats.snapshot()
        eqi(s2.fellBackBatches, 1L, "回落一批：回落批次 = 1")
        eqi(s2.fellBackEntries, 8L, "回落一批：回落条数 = 8")
        eqi(s2.batchedMs, 120L, "回落一批：合批毫秒**不变**（那是逐条的时间，不是合批省的）")
        eqi(s2.lastRoute, EmbedBatchStats.Route.FELL_BACK, "回落一批：最近路由 = FELL_BACK")
        eqi(s2.batchedEntryRatio, 0.5, "一半一半：比例 = 0.5")

        // ── 负耗时按 0 记 ─────────────────────────────────────────────────
        // 墙钟跳变 / 计时器精度问题的下限会给负数。负的毫秒会让"平均"变成
        // 一个无意义的数，而它看着像个正常数 —— 按 0 记，至少不污染。
        reset()
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 4, -50))
        val s3 = EmbedBatchStats.snapshot()
        eqi(s3.batchedMs, 0L, "负耗时：按 0 记（不污染平均）")
        eqi(s3.lastBatchMs, 0L, "负耗时：最近批耗时也按 0 记")

        // ── 非正条数按 0 记（但批次仍要记）────────────────────────────────
        // 条数传 0 在真实调用里是 bug（一批不可能 0 条），但**批次**必须记 ——
        // 否则"这一批走过合批"这件事在读数上消失（而它正是本 issue 要回答的）。
        reset()
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 0, 10))
        val s4 = EmbedBatchStats.snapshot()
        eqi(s4.batchedBatches, 1L, "0 条：批次仍记为 1（走过合批这件事不许消失）")
        eqi(s4.batchedEntries, 0L, "0 条：条数记 0")

        // ── reset 清干净 ──────────────────────────────────────────────────
        reset()
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 8, 10))
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.FELL_BACK, 8, 10))
        EmbedBatchStats.reset()
        val s5 = EmbedBatchStats.snapshot()
        eqi(s5.batchedBatches, 0L, "reset：合批批次清零")
        eqi(s5.fellBackBatches, 0L, "reset：回落批次清零")
        eqi(s5.batchedMs, 0L, "reset：毫秒清零")
        eqi(s5.lastRoute, null, "reset：最近路由清零")
        eqi(s5.batchedEntryRatio, null, "reset：比例回到 null（不是 0.0）")

        // ── describe：一句话里有四个数 ────────────────────────────────────
        reset()
        repeat(2) { EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 8, 60)) }
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.FELL_BACK, 2, 40))
        val line = EmbedBatchStats.describe(EmbedBatchStats.snapshot(), EmbedBatchStats.kScopeCumulative)
        ok(line.contains("16/18"), "describe：合批条数/总条数（$line）")
        ok(line.contains("88%"), "describe：百分比取整（$line）")
        ok(line.contains("2 批"), "describe：合批批次数（$line）")
        ok(line.contains("120 ms"), "describe：合批累计毫秒（$line）")
        ok(line.contains("2 条"), "describe：回落条数（$line）")

        // ── 舍入方向：占比 87.5% 取整成 87（截断，不是四舍五入到 88）─────
        reset()
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 7, 1))
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.FELL_BACK, 1, 1))
        ok(EmbedBatchStats.describe(EmbedBatchStats.snapshot(), EmbedBatchStats.kScopeCumulative)
                .contains("87%"),
            "describe：7/8 说 87%（截断，不夸大）")

        // ── delta：段读数 = 收尾快照 - 开工快照 ──────────────────────────
        // 这一条钉的是本段那句"本段批大小=N"里的数字到底怎么来的。
        // 不钉住时最省事的写法是直接把 [snapshot] 传给 describe ——
        // 那是**累计值**，会被这一行的"本段"读成本次建库（本仓库真出过的措辞错）。
        reset()
        // 前面已经攒了 20 条 / 5 批（模拟"进程开了一段时间、建过好几次库"）。
        repeat(4) { EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 4, 100)) }
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.FELL_BACK, 4, 10))
        val before = EmbedBatchStats.snapshot()
        eqi(before.batchedEntries, 16L, "delta：开工前累计 16 条")
        eqi(before.batchedBatches, 4L, "delta：开工前累计 4 批")
        // 本段跑了两批合批（4+4 条）、一批回落。
        repeat(2) { EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.BATCHED, 4, 100)) }
        EmbedBatchStats.record(EmbedBatchStats.Sample(EmbedBatchStats.Route.FELL_BACK, 4, 10))
        val seg = EmbedBatchStats.delta(before, EmbedBatchStats.snapshot())
        eqi(seg.batchedEntries, 8L, "delta：本段合批条数 = 8（**不是**累计的 24）")
        eqi(seg.batchedBatches, 2L, "delta：本段合批批次 = 2（**不是**累计的 6）")
        eqi(seg.batchedMs, 200L, "delta：本段合批毫秒 = 200")
        eqi(seg.fellBackEntries, 4L, "delta：本段回落条数 = 4（**不是**累计的 8）")
        eqi(seg.fellBackBatches, 1L, "delta：本段回落批次 = 1")
        // ⚠ "最近一批"那两个字段是瞬时量，**不做减法** —— 相减出来的数没有意义。
        eqi(seg.lastRoute, EmbedBatchStats.Route.FELL_BACK, "delta：最近一批那条取收尾端（不做减法）")
        eqi(seg.lastBatchEntries, 4, "delta：最近一批条数取收尾端")
        // 一句话里的数字也必须是段值。
        val segLine = EmbedBatchStats.describe(seg, EmbedBatchStats.kScopeThisBuild)
        ok(segLine.contains("8/12"), "delta：段口径那句话报 8/12（$segLine）")
        ok(segLine.contains("2 批"), "delta：段口径那句话报 2 批（$segLine）")
        // 段内一条都没编过时，是那句"这一段建库一条都没编过"，不是"本次运行还没跑过"。
        val zeroSeg = EmbedBatchStats.delta(before, before)
        ok(EmbedBatchStats.describe(zeroSeg, EmbedBatchStats.kScopeThisBuild).contains("这一段建库"),
            "delta：段为空时报「这一段建库一条都没编过」")

        println()
        println("========================================================")
        println("PASS $pass / FAIL $fail")
        if (fail > 0) kotlin.system.exitProcess(1)
    }

    private fun reset() = EmbedBatchStats.reset()
}
