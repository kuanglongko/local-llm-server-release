package com.xiaowan.localinference

/**
 * `RagCost`（建索引成本的**纯逻辑**估算）的离线单测。
 *
 * 钉的是这个对象存在的**全部意义**：把"这一批要花多久"变成一个**用户能读的量级**，
 * 且在条数小的时候**闭嘴**。它的失效形态都是"提示本身变成噪音或谎言"：
 *   · 条数小也提示 -> 用户每次建库都点确定，贵的提示来了一起被无视；
 *   · 量级算错（比如把秒当分钟）-> 用户据此判断"现在建还是等一下"，方向反了；
 *   · 撞 maxChunks 时不区分 -> 提示说"耐心等"，等完发现后半段还是没进去；
 *   · 文案里塞进半篇说明书 -> 与"简短"这条用户要求直接冲突。
 * 都不抛异常、不崩溃，只有断言能钉住。
 */
object RagCostTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    fun run(): Int {
        // ① 小批量**必须闭嘴**（"简短"这条要求的落点）
        ok(RagCost.buildPrompt(0) == null, "0 条 -> 不提示（空批不该弹框）")
        ok(RagCost.buildPrompt(1) == null, "1 条 -> 不提示")
        ok(RagCost.buildPrompt(RagCost.WARN_ENTRIES - 1) == null,
            "${RagCost.WARN_ENTRIES - 1} 条（阈值下沿）-> 不提示")
        // 阈值**本身**要提示：判据是 `< WARN_ENTRIES` 返回 null，不是 `<=`
        ok(RagCost.buildPrompt(RagCost.WARN_ENTRIES) != null,
            "${RagCost.WARN_ENTRIES} 条（阈值上沿）-> 提示（边界取闭）")

        // ② 大批量提示里必须有条数与"要多久"（否则等于没说）
        val p = RagCost.buildPrompt(1000)
        ok(p != null && p.contains("1000 条"), "提示里报出条数")
        ok(p != null && (p.contains("分钟") || p.contains("秒")), "提示里报出时间量级")
        // ③ 文案必须**简短**：三句以内（用户明确要求"简短提示"）
        val sentences = p!!.split('\n').count { it.trim().isNotEmpty() }
        ok(sentences <= 3, "文案不超过 3 行（实际 $sentences 行）——多出来就是噪音")

        // ④ 量级换算：秒 / 分钟 / 半小时以上 / 小时 四档
        ok(RagCost.humanSeconds(1.0, 3.0).contains("秒"), "秒级 -> 说『秒』")
        ok(RagCost.humanSeconds(60.0, 120.0).contains("分钟"), "分钟级 -> 说『分钟』")
        ok(RagCost.humanSeconds(600.0, 1800.0).contains("分钟"),
            "半小时内 -> 说『分钟』（不报假精确）")
        ok(RagCost.humanSeconds(0.0, 0.0).contains("立刻"), "0 -> 『几乎立刻』（不出现 0 秒）")

        // ④b **小时档必须存在**（ISSUE #269）。
        // 这一格是本轮新钉的：真机实测 0.77 秒/条，7000 条 ≈ **两小时**。
        // 旧口径（上界 0.30 秒/条）把它算成 35 分钟，落进"十几分钟甚至更久"里 ——
        // 用户按那句"十几分钟"决定现在开始，然后等了两小时，
        // 期间他一直以为自己遇上了卡死（用户原文："点击卡住的页面，app 闪退"）。
        // **提示报小了比不提示更坏**：不提示他只是不知道，报小了他是被误导。
        val twoHours = RagCost.humanSeconds(700.0, 7000.0)
        ok(twoHours.contains("小时"),
            "7000 条（≈两小时）-> 必须说『小时』，不能只说『十几分钟』（实际：$twoHours）")
        ok(twoHours.contains("1~2 小时") || twoHours.contains("2 小时"),
            "小时档要给出区间量级（实际：$twoHours）")
        // 7000 条**不许**再被说成"十几分钟"那种模糊话 —— 那句正是旧行为的原文
        ok(!twoHours.contains("十几分钟"),
            "小时档不许退化成『十几分钟甚至更久』那句模糊话（实际：$twoHours）")
        // estimateSeconds 的真实读数要落进小时档（口径与 humanSeconds 咬合，
        // 而不是"humanSeconds 支持小时"但没人喂得到它）
        val (hlo, hhi) = RagCost.estimateSeconds(7000)
        ok(hhi >= 3600.0,
            "7000 条的估算上界要真的过 3600 秒（否则小时档永远走不到；实际 $hhi）")
        // 上界必须覆盖真机读数 0.77 秒/条（ISSUE #269）—— 报小了比不报更坏
        ok(hhi / 7000.0 >= 0.77,
            "单条上界 ≥ 真机读数 0.77 秒/条（否则 7000 条仍会被报小；实际 ${hhi / 7000.0}）")

        // ⑤ estimateSeconds 单调、界内有意义
        val (lo1, hi1) = RagCost.estimateSeconds(100)
        val (lo2, hi2) = RagCost.estimateSeconds(200)
        ok(lo1 > 0 && hi1 > lo1, "单条上界 > 下界（不是退化成一个数）")
        ok(lo2 > lo1 && hi2 > hi1, "条数翻倍 -> 估算翻倍（与条数成正比）")
        val (z1, z2) = RagCost.estimateSeconds(0)
        ok(z1 == 0.0 && z2 == 0.0, "0 条 -> (0,0)，不产生负值/NaN")
        val (n1, n2) = RagCost.estimateSeconds(-5)
        ok(n1 == 0.0 && n2 == 0.0, "负数 -> (0,0)（不崩、不给负时间）")

        // ⑥ 撞 maxChunks：必须**换一句话**，不能说"只是慢"
        val cap = RagCost.DEFAULT_MAX_CHUNKS
        val over = RagCost.buildPrompt(cap + 1, cap)
        ok(over != null && over.contains("最多切 $cap 块"),
            "条数超上限 -> 提示里说清『单条最多切 N 块，超出不进索引』")
        ok(over != null && over.contains("不会进索引"),
            "那句说的是『不进索引』（不是『慢一点』）—— 两者后果差一个量级")
        val under = RagCost.buildPrompt(300, 16384)
        ok(under != null && !under.contains("最多切"),
            "条数未超上限 -> **不**出现那句（不报假警）")

        // ⑦ 进行中那行也报量级（等的时候没有参照物才是焦虑的来源）
        val line = RagCost.buildingLine(1000)
        ok(line.contains("1000 条") && (line.contains("分钟") || line.contains("秒")),
            "建索引中那行同时给条数与量级")
        ok(RagCost.buildingLine(1).contains("立刻") || RagCost.buildingLine(1).contains("秒"),
            "1 条那行也给得出量级（不出现空串）")

        println()
        println("RagCostTest: PASS $pass / FAIL $fail")
        return if (fail == 0) 0 else 1
    }
}

fun main() {
    kotlin.system.exitProcess(RagCostTest.run())
}
