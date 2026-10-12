package com.xiaowan.localinference

/**
 * 「**换批大小重建索引后，检索结果还是不是同一些行**」的**纯逻辑**判据
 * （ISSUE #269 第 5 步：与 [EmbedParity] 配对的那一半）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么必须有这一层（[EmbedParity] 答不了这个问题）
 * ═══════════════════════════════════════════════════════════════════════
 * [EmbedParity] 量的是"**同一批文本**、逐条编 vs 合批编，向量逐位相同吗"。
 * 它给出的是**向量级**结论。而用户真正要问的是**另一句**：
 *
 *   「批大小改了、索引重建了，我搜出来的东西还是不是同几条？」
 *
 * 这两句**不是同一句**，方向也不一样：
 *
 *   · 向量**逐位不同**（`firstDiffDim=0`、`4.5e-03` 那种）**不等于**结果不同 ——
 *     向量不是东西本身，"两个向量的差"与"排名换位"之间没有蕴含关系；
 *   · 向量**逐位相同**也**不等于**结果相同 —— 索引里还有正文行序、切块、
 *     分数比较这几种东西（本仓库记录过"检索分数是对的、注入内容错位"那一族）。
 *
 * 0.9.222 那轮的真机读数（12 次逐位比对）能给的是前者。后者**一次都没量过** ——
 * 而"要不要按库放行 `g`"（`FpDiff.onlyBatch` 那条）恰恰要的是后者。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 判据：两套索引互查，命中的**行下标序列**必须逐位一样
 * ═══════════════════════════════════════════════════════════════════════
 * 做法只有一条是诚实的：**把同一批文本按两种批大小各编一遍**（两套向量），
 * 然后用**同一组查询**各自去查，比"命中的行下标序列"：
 *
 *   · 下标序列逐位一样 -> 换批大小没换**答案**（对这批文本成立，可收窄结论）；
 *   · 有位置不一样    -> 答案变了，`g` 就不能按库放行（哪怕向量只差 4e-03）。
 *
 * ⚠ 比的是**下标**而不是分数：分数在两种批布局下本来就允许有浮点级差异
 *   （那正是 [EmbedParity] 量出来的东西）——拿分数当判据会把"数值不同"
 *   与"答案不同"又混成一个读数，而那正是本层要分开的那两件事。
 *   分数只作为**明细**报出来（"第 k 名从 A 换成 B，分数 a -> b"）。
 *
 * ⚠ 打平（分数相等）时要小心：两次检索里"同分"的几行**可能顺序不同**，
 *   而那**不是**答案变了。所以本层的判据落在**集合**上（Top-K 里有哪些行），
 *   顺序另记一格（[orderChanged]）—— 顺序变了、集合没变，处置不同：
 *   集合变了 = 答案变了；只顺序变了 = 排名在打平处不稳定（那是另一件事，
 *   也可能是"索引里混了两种语义"的早期信号）。把两者压成一个"不一样"，
 *   就把"要不要放行 `g`"与"要不要查打平稳定性"焊在了一起。
 */
object EmbedRetrieveParity {

    /**
     * 一次查询的对照结论。
     *
     * [base] / [other] 是**两名次一行**的行下标（同一个查询在两套索引上的 Top-K）。
     */
    data class Query(
        /** 查询文本（明细里要指得出是哪一条）。 */
        val text: String,
        val base: List<Int>,
        val other: List<Int>,
        /** 两侧同名次上的分数（只用于明细：`a -> b`）；缺失按 NaN。 */
        val baseScores: List<Float> = emptyList(),
        val otherScores: List<Float> = emptyList(),
    ) {
        /** 两侧命中集合是否相同（**答案**同不同看这一格）。 */
        val sameSet: Boolean get() = base.toSet() == other.toSet()

        /** 名次序列是否逐位相同（比 [sameSet] 严：打平处换序也算不同）。 */
        val sameOrder: Boolean get() = base == other

        /** 集合相同但名次不同 = 打平处换序（不是答案变了）。 */
        val onlyOrderChanged: Boolean get() = sameSet && !sameOrder

        /** 共有集合为空 = 这次查询**没量到**（两侧都搜不到东西不该算"不同"）。 */
        val vacuous: Boolean get() = base.toSet().isEmpty() && other.toSet().isEmpty()

        /** 第一条不同的名次（-1 = 没有）。 */
        val firstDiffAt: Int
            get() {
                val n = maxOf(base.size, other.size)
                for (i in 0 until n) {
                    if (base.getOrNull(i) != other.getOrNull(i)) return i
                }
                return -1
            }
    }

    /** 一次「换批大小重建后检索是否还一致」的对照结论。 */
    data class Report(
        val rows: List<Query>,
        /** 参与对照的批大小（`base` / `other`）；`<=0` = 没拿到（那就不该有结论）。 */
        val baseBatch: Int,
        val otherBatch: Int,
        val topK: Int,
    ) {
        /** 有结论的查询条数（两侧都搜到了东西的那些）。 */
        val effective: Int get() = rows.count { !it.vacuous }

        /** 参与对照的查询总数（**没量到的不算**，见 [effective]）。 */
        val total: Int get() = rows.size

        /**
         * 命中集合**不同**的查询条数 —— 这才是"答案变了"。
         *
         * ⚠ 只有这一格为 0 才允许说"换批大小不改答案"。名次不同不算，
         *   那是 [onlyOrderChanged] 那格（处置不同）。
         */
        val answerChanged: Int get() = rows.count { !it.vacuous && !it.sameSet }

        /** 集合相同但名次不同的查询条数（打平处换序）。 */
        val orderChanged: Int get() = rows.count { it.onlyOrderChanged }

        /**
         * 有没有真的量到东西。**没量到不等于一致** —— 两侧都搜不到时
         * `answerChanged` 也是 0，而那时该说的是"没测到"，不是"答案一样"。
         */
        val measured: Boolean get() = effective > 0

        /** 参与对照的查询条数与 topK 是否对得上（守卫正面钉住口径）。 */
        val allIdentical: Boolean get() = measured && answerChanged == 0

        /**
         * 说一句话（给日志 / `/health`）。
         *
         * ⚠ 措辞里**不许**出现"向量不同 -> 检索结果不同"这种蕴含：本层量的
         *   恰恰是那个蕴含**成不成立**。两档分开说（同 [EmbedParity.describe] 的规矩）：
         *     · 集合变了 -> `g` 不能按库放行（哪怕向量只差 4e-03）；
         *     · 集合没变、名次有变 -> 打平处不稳，与"`g` 能不能放行"是两件事。
         */
        fun describe(): String {
            val head = "检索对照 ${rows.size} 条查询（topK=$topK，批大小 $baseBatch vs $otherBatch）"
            if (!measured) return "$head —— 本次没量到（两侧都没搜到任何行，不报结论）"
            if (answerChanged > 0) {
                return "$head —— 命中集合在 $answerChanged/${effective} 条上变了" +
                    "（`g` 不能按库放行：换批大小换了**答案**）"
            }
            if (orderChanged > 0) {
                return "$head —— 命中集合全同，但 $orderChanged/${effective} 条的名次变了" +
                    "（打平处换序；与「要不要放行 `g`」不是同一件事）"
            }
            return "$head —— 命中集合与名次都逐位相同" +
                "（这批文本上，换批大小没换答案）"
        }

        /** 逐条明细（只列有差异的那些；全同时这一节为空）。 */
        fun detailLines(): List<String> {
            if (!measured) return emptyList()
            return rows.filter { !it.vacuous && !it.sameOrder }.map { q ->
                val at = q.firstDiffAt
                val b = q.base.getOrNull(at)
                val o = q.other.getOrNull(at)
                val bs = q.baseScores.getOrNull(at)
                val os = q.otherScores.getOrNull(at)
                val kind = if (q.sameSet) "名次变了" else "集合变了"
                "  · 「${q.text.take(24)}」$kind：第 ${at + 1} 名 $b -> $o" +
                    "（分数 ${fmt(bs)} -> ${fmt(os)}）"
            }
        }

        private fun fmt(f: Float?): String =
            if (f == null || f.isNaN()) "未知" else String.format("%.4f", f)
    }

    /**
     * 比对两组检索结果（同一个查询在两套索引上的命中）。
     *
     * 调用方负责跑检索；这一层只回答"一不一致"。之所以分开：
     * 检索在 native 侧、判据在纯逻辑层（`tools/embed_retrieve/` 那套单测
     * 认的就是这个函数），两处合起来时"判据写错"与"检索跑错"长得一样。
     */
    fun compare(
        queries: List<String>,
        base: List<List<Int>>,
        other: List<List<Int>>,
        baseBatch: Int,
        otherBatch: Int,
        topK: Int,
        baseScores: List<List<Float>> = emptyList(),
        otherScores: List<List<Float>> = emptyList(),
    ): Report {
        val n = maxOf(queries.size, base.size, other.size)
        val rows = ArrayList<Query>(n)
        for (i in 0 until n) {
            rows.add(
                Query(
                    text = queries.getOrNull(i) ?: "",
                    base = base.getOrNull(i) ?: emptyList(),
                    other = other.getOrNull(i) ?: emptyList(),
                    baseScores = baseScores.getOrNull(i) ?: emptyList(),
                    otherScores = otherScores.getOrNull(i) ?: emptyList(),
                )
            )
        }
        return Report(rows = rows, baseBatch = baseBatch, otherBatch = otherBatch, topK = topK)
    }

    /**
     * 「最近一次检索对照」的对外读数（给 `/health` 报）。
     *
     * 与 [EmbedParity.Last] 同源的两条规矩：
     *   · `measured=false` 与"测了全同"必须分开（没测时 `answerChanged` 也是 0）；
     *   · 自洽拦截：`allIdentical=true` 与 `answerChanged>0` 不可能同时成立 ——
     *     对不上说明这份 `Report` 是手拼的，落 UNKNOWN 而不是把它发出去
     *     （发出去会被读成"答案没变"，一个**相反**的结论）。
     *
     * ⚠ **只有一个调用点该记这里**：`probeRetrieveParity` 一次按钮里会跑**两组**
     *   （长短悬殊 + 等长），而这一层只有**一组**字段 —— 无条件记两次时后跑的那组
     *   覆盖先跑的。所以调用方按 `label` 分闸，只记**长短悬殊**那组
     *   （`LlmEngine.LONG_SHORT_LABEL`）：等长组按构造就是稳的（见
     *   [EmbedParityProbe.SAME_LEN] 对象头），把它报出去等于**用必然成立的结论
     *   去回答"答案会不会变"** —— 那正是这一层存在的理由的反面。
     *   （`EmbedParity.Last` 那边取的是**相反**的一组：向量那格要的是"批布局"
     *   这一个变量，见 `probeEmbedBatchParity` 的落读数处。）
     */
    object Last {
        data class State(
            val measured: Boolean,
            val queries: Int,
            val answerChanged: Int,
            val orderChanged: Int,
            val baseBatch: Int,
            val otherBatch: Int,
            val topK: Int,
        )

        private val lock = Any()
        private var measured = false
        private var queries = 0
        private var answerChanged = 0
        private var orderChanged = 0
        private var baseBatch = 0
        private var otherBatch = 0
        private var topK = 0

        /** 记一次结论。[r] 为 null = 没得到结论（清成"没测"，不保留旧读数）。 */
        fun record(r: Report?) {
            synchronized(lock) {
                if (r == null || !r.measured) {
                    measured = false; queries = 0; answerChanged = 0; orderChanged = 0
                    baseBatch = 0; otherBatch = 0; topK = 0
                    return
                }
                // 自洽拦截见对象头（`allIdentical` 与 `answerChanged>0` 互斥）。
                if (r.allIdentical != (r.answerChanged == 0)) return
                measured = true
                queries = r.effective
                answerChanged = r.answerChanged
                orderChanged = r.orderChanged
                baseBatch = r.baseBatch
                otherBatch = r.otherBatch
                topK = r.topK
            }
        }

        fun snapshot(): State = synchronized(lock) {
            State(measured, queries, answerChanged, orderChanged, baseBatch, otherBatch, topK)
        }

        /** 清零（换嵌入模型时调 —— 结论描述的是"这个模型"的批行为）。 */
        fun reset() = synchronized(lock) {
            measured = false; queries = 0; answerChanged = 0; orderChanged = 0
            baseBatch = 0; otherBatch = 0; topK = 0
        }
    }
}
