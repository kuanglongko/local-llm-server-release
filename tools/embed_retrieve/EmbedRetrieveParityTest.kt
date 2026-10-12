package com.xiaowan.localinference

/**
 * `EmbedRetrieveParity` 的离线单测（ISSUE #269 第 5 步：与 `EmbedParity` 配对的那半）。
 *
 * 为什么必须钉：这一层的结论**直接决定**"`g` 能不能按库放行"，而它的失效形态
 * 全是"把一个像真的的读数当成结论"：
 *   · 两侧都搜不到（`vacuous`）被算成"一致" -> `answerChanged == 0` 被读成
 *     "答案没变"，而其实**什么都没量到**；
 *   · 只比分数、不比下标 -> "数值不同"与"答案不同"混成一个读数（本层要分开的正是它）；
 *   · 集合与名次压成一格 -> "打平处换序"与"答案变了"混一起，处置相反；
 *   · `measured` 不单独一格 -> 没测时 `answerChanged` 也是 0。
 * 都不抛异常、不崩溃，只有断言能钉住。
 */
private var pass = 0
private var fail = 0

private fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("  ok  $name") } else { fail++; println("FAIL  $name") }
}

private fun compare(
    queries: List<String>, a: List<List<Int>>, b: List<List<Int>>,
    baseBatch: Int = 8, otherBatch: Int = 1, topK: Int = 3,
    sa: List<List<Float>> = emptyList(), sb: List<List<Float>> = emptyList(),
) = EmbedRetrieveParity.compare(queries, a, b, baseBatch, otherBatch, topK, sa, sb)

fun main() {
    println("EmbedRetrieveParityTest")

    // ① 集合与名次全同 -> 允许说"没换答案"。
    run {
        val r = compare(listOf("q1", "q2"), listOf(listOf(2, 0, 3), listOf(1, 2, 0)),
            listOf(listOf(2, 0, 3), listOf(1, 2, 0)))
        check("全同：allIdentical 成立", r.allIdentical)
        check("全同：measured 成立", r.measured)
        check("全同：answerChanged=0", r.answerChanged == 0)
        check("全同：orderChanged=0", r.orderChanged == 0)
        check("全同：明细为空", r.detailLines().isEmpty())
        check("全同：措辞说\"没换答案\"", r.describe().contains("没换答案"))
    }

    // ② 集合变了 = 答案变了（哪怕名次看着像）。
    run {
        val r = compare(listOf("q1"), listOf(listOf(2, 0, 3)), listOf(listOf(2, 3, 1)))
        check("集合变：answerChanged=1", r.answerChanged == 1)
        check("集合变：allIdentical=false", !r.allIdentical)
        check("集合变：措辞说不放行", r.describe().contains("不能按库放行"))
        check("集合变：明细说\"集合变了\"", r.detailLines().first().contains("集合变了"))
    }

    // ③ 集合相同、名次不同 = 打平处换序（**不是**答案变了）。
    //    这一格必须与 ② 分开：处置不同（一个是"别放行 g"，一个是"查打平稳定性"）。
    run {
        val r = compare(listOf("q1"), listOf(listOf(2, 0, 3)), listOf(listOf(2, 3, 0)))
        check("换序：sameSet 成立", r.rows.first().sameSet)
        check("换序：onlyOrderChanged 成立", r.rows.first().onlyOrderChanged)
        check("换序：answerChanged=0", r.answerChanged == 0)
        check("换序：orderChanged=1", r.orderChanged == 1)
        // ⚠ 判据是"集合"，所以 `allIdentical` 在换序时**仍成立** —— 这正是本层
        //   与"名次也要逐位相同"分开的那一处（后者会把打平处的不稳定读成答案变了）。
        check("换序：allIdentical 仍成立（判据落在集合上）", r.allIdentical)
        check("换序：措辞点名打平处", r.describe().contains("打平"))
        check("换序：明细说\"名次变了\"", r.detailLines().first().contains("名次变了"))
    }

    // ④ 两侧都搜不到 -> **没量到**，不是"一致"。
    //    这是本层最要紧的一格：把它算成一致，就会在"库里根本没有相关内容"
    //    的情况下报出"换批大小不改答案"。
    run {
        val r = compare(listOf("q1", "q2"), listOf(emptyList(), emptyList()),
            listOf(emptyList(), emptyList()))
        check("全空：measured=false", !r.measured)
        check("全空：effective=0", r.effective == 0)
        check("全空：answerChanged=0（所以不能只看这个数）", r.answerChanged == 0)
        check("全空：allIdentical=false", !r.allIdentical)
        check("全空：措辞说\"没量到\"", r.describe().contains("没量到"))
        check("全空：明细为空", r.detailLines().isEmpty())
    }

    // ⑤ 部分 vacuous：只算有效的那几条（分母不能用总数）。
    run {
        // 两行：第一条两侧都有 -> 有效；第二条两侧都空 -> vacuous。
        // ⚠ 原来这里另写了一份只给 **1 条**结果的 `r`（`listOf(listOf(1))`），
        //   而 `compare` 按 `maxOf(queries, base, other)` 摊成 **2 行** ——
        //   于是 `r.rows.first()` 是第 0 行（`base=[1]`，**不 vacuous**），
        //   断言的主语取错了行。vacuous 的那一条在第 1 行。
        val r2 = EmbedRetrieveParity.compare(
            listOf("q1", "q2"), listOf(listOf(1), emptyList()),
            listOf(listOf(1), emptyList()), 8, 1, 3)
        check("部分空：total=2", r2.total == 2)
        check("部分空：effective=1", r2.effective == 1)
        check("部分空：measured 成立", r2.measured)
        check("第 0 行（两侧都有）不 vacuous", !r2.rows[0].vacuous)
        check("第 1 行（两侧都空）vacuous", r2.rows[1].vacuous)
        // 分母是 effective、不是 total —— 这一条才是"部分空"那段存在的理由：
        // 拿 total 当分母时，"2 条里 1 条 vacuous"会被说成 1/2，而真正参与
        // 对照的只有 1 条。`describe()` 里那句按 `effective` 报（源码如此），
        // 所以这里锚 it：它是 `answerChanged>0` 那一支的表达式。
        // ⚠ 不改 `describe()` 的措辞来迁就断言（那是拿判据改事实）；
        //   这里只核**已被确认的那条**：`Report.effective` 与 `total` 分得开。
        check("部分空：effective(1) 与 total(2) 是两个数", r2.effective == 1 && r2.total == 2)
        check("部分空：vacuous 的那条不计入 effective", r2.effective == r2.rows.count { !it.vacuous })
    }

    // ⑥ 一侧搜到、另一侧没搜到 = 集合变了（不是 vacuous）。
    run {
        val r = compare(listOf("q1"), listOf(listOf(1, 2)), listOf(emptyList()))
        check("单侧空：vacuous=false", !r.rows.first().vacuous)
        check("单侧空：answerChanged=1", r.answerChanged == 1)
    }

    // ⑦ 长度不等的命中序列：`firstDiffAt` 落在缺的那一位（不是抛异常）。
    run {
        val r = compare(listOf("q1"), listOf(listOf(1, 2, 3)), listOf(listOf(1, 2)))
        check("长度不等：firstDiffAt=2", r.rows.first().firstDiffAt == 2)
        check("长度不等：集合变了", r.answerChanged == 1)
    }

    // ⑧ 分数只是明细，不参与判定（分数不同、下标全同 = 一致）。
    run {
        val r = compare(listOf("q1"), listOf(listOf(3, 1)), listOf(listOf(3, 1)),
            sa = listOf(listOf(0.9f, 0.8f)), sb = listOf(listOf(0.89f, 0.79f)))
        check("只有分数不同：仍是 allIdentical", r.allIdentical)
        check("只有分数不同：明细为空", r.detailLines().isEmpty())
    }

    // ⑨ 明细里的分数格式（NaN / 缺项不许崩）。
    //    ⚠ 这一段原来拿 `[2, 0]` vs `[0, 2]` 断言 `answerChanged == 1` ——
    //    可那两个是**同一个集合**（只是名次反了），而判据只比下标、本来就不看分数，
    //    于是 `answerChanged` 是 0。**断言测错了方向**：把"名次换了"当成"答案变了"，
    //    正好踩中本层最要紧的那条分界（集合 vs 名次）。
    //    这里改成：收集合真变的那一刻看 NaN 分数能不能渲染。
    run {
        val r = compare(listOf("q1"), listOf(listOf(2, 0)), listOf(listOf(2, 1)),
            sa = listOf(listOf(Float.NaN, 0.5f)), sb = listOf(listOf(0.5f)))
        check("NaN 分数的明细能渲染", r.detailLines().first().contains("未知"))
        check("NaN 分数不参与判定（集合变了才是变了）", r.answerChanged == 1)
    }

    // ⑨b 分数不同、下标全同的**反例**：名次换序不算答案变了 —— 分数更是不算。
    run {
        val r = compare(listOf("q1"), listOf(listOf(2, 0)), listOf(listOf(0, 2)),
            sa = listOf(listOf(Float.NaN, 0.5f)), sb = listOf(listOf(0.5f)))
        check("落回同一个集合：answerChanged=0（分数不参与）", r.answerChanged == 0)
        check("落回同一个集合：orderChanged=1", r.orderChanged == 1)
    }

    // ⑩ describe 里不许出现"向量不同 -> 答案不同"这种蕴含（措辞级判据）。
    run {
        val r = compare(listOf("q1"), listOf(listOf(1)), listOf(listOf(1)))
        val d = r.describe()
        check("措辞不含\"逐位比对\"（那是另一格）", !d.contains("逐位比对"))
        check("措辞含两种批大小", d.contains("8") && d.contains("1"))
        check("措辞含 topK", d.contains("topK=3"))
    }

    // ⑪ Last：没测 / 测了 → 分开（没测时 answerChanged 也是 0，不许读成一致）。
    run {
        EmbedRetrieveParity.Last.reset()
        check("Last 初始：measured=false", !EmbedRetrieveParity.Last.snapshot().measured)
        val r = compare(listOf("q1"), listOf(listOf(1)), listOf(listOf(1)))
        EmbedRetrieveParity.Last.record(r)
        val st = EmbedRetrieveParity.Last.snapshot()
        check("Last：measured 落盘", st.measured)
        check("Last：queries 落盘", st.queries == 1)
        check("Last：batch 落盘", st.baseBatch == 8 && st.otherBatch == 1)
        // 没量到的 report 必须把"没测"落回去（而不是保留上一条读数）。
        EmbedRetrieveParity.Last.record(
            compare(listOf("q1"), listOf(emptyList()), listOf(emptyList())))
        check("Last：没量到 -> 清回没测", !EmbedRetrieveParity.Last.snapshot().measured)
    }

    // ⑫ Last：null（入口没跑出来）也清回"没测"。
    run {
        EmbedRetrieveParity.Last.record(compare(listOf("q"), listOf(listOf(0)), listOf(listOf(0))))
        EmbedRetrieveParity.Last.record(null)
        check("Last：null -> 没测", !EmbedRetrieveParity.Last.snapshot().measured)
    }

    // ⑬ 判据与 Report.allIdentical 是同一个（不重算）：answerChanged==0 且 measured。
    run {
        val a = compare(listOf("q"), listOf(listOf(1)), listOf(listOf(1)))
        val b = compare(listOf("q"), listOf(emptyList()), listOf(emptyList()))
        check("allIdentical == (measured && answerChanged==0)",
            a.allIdentical == (a.measured && a.answerChanged == 0) &&
                b.allIdentical == (b.measured && b.answerChanged == 0))
    }

    println("PASS $pass / FAIL $fail")
    if (fail > 0) throw AssertionError("$fail 条失败")
}
