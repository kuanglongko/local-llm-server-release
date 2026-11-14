package com.xiaowan.localinference

/**
 * 「同一批文本，逐条编 vs 合批编，向量是不是**逐位相同**」的**纯逻辑**判据
 * （ISSUE #269 第 5 步：那个把 `g` 从"必比字段"解放出来的真机断言）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么必须有这一层（而不是"跑一遍看着差不多"）
 * ═══════════════════════════════════════════════════════════════════════
 * 0.9.218 把批大小 `g` 从语义字段里**单独定级**（`FpDiff.onlyBatch`），但
 * **明确不授权沿用**：因为"不同批大小下算出的向量逐位相同"这个断言**尚未验证**。
 * 断言未验就沿用，索引会变成两种语义拼起来的 —— 症状是"检索分数像随机、
 * 接口 200、日志干净"。所以第 5 步要做的，就是**把这个断言量出来**：
 *
 *   · 逐位相同 -> `g` 从此是**纯加速旋钮**，改它不再作废任何东西，
 *     `onlyBatch` 从"只说清楚"升级为"真的沿用"；
 *   · 逐位不同 -> `g` 保持必比字段（沿用会把两种语义拼起来），
 *     而 `onlyBatch` 那句"前 N 块语义同源"要改成**如实说不建议沿用**。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么是"逐位"，不是"余弦够近"
 * ═══════════════════════════════════════════════════════════════════════
 * 浮点前向在不同 batch 布局下可能有**极小的**数值差异（不同的归约次序、
 * 不同的 SIMD 宽度）。"够近"（余弦 > 0.9999）会把这种差异判成相同 ——
 * 而**沿用**这条路上，索引里恰恰是**两种**向量在混用：查询向量来自当前
 * 配置那一批，库里的向量来自另一种批大小。要沿用，必须知道"它们是不是**同一
 * 个数**"，而不是"差不多是同一个方向"。
 *
 * 所以本层给出**三档**结论，让上层按用途选：
 *   · [Verdict.IDENTICAL]  —— 逐位相同（含符号零与 NaN 的位模式）。
 *     只有这一档才允许"沿用"；
 *   · [Verdict.CLOSE]      —— 不是逐位，但都在容差内（数值等价）。
 *     措辞要如实：**不能**据此沿用（索引里仍是两种向量）；
 *   · [Verdict.DIFFERENT]  —— 有分量超出容差（真的算出了不同的向量）。
 *
 * ⚠ 这一层**只给判定，不做决定**：是否沿用、要不要重建由调用方（`LlmEngine`
 *   与用户）拍板。让它去决定沿用 = 把"观测"与"决策"焊在一起 ——
 *   而它们的正确方向相反（观测要忠实，决策要保守），与本仓库反复记录的那族同源。
 */
object EmbedParity {

    /**
     * 单条文本的比对结论。
     *
     * 为什么要**逐条**而不是"整批一个结论"：合批里失败的条目（全零/下标）
     * 与"算得不一样"是两件完全不同的事，处置相反 ——
     *   · 某条**缺失**（单条或合批那侧没有向量）-> 是 bug / 输入问题，看那一对；
     *   · 某条**算得不一样** -> 是断言问题（若整批都不同，方向一致）。
     * 整批一个布尔值会把这两件事压成一个"不相同"，而它们要查的地方不同。
     */
    enum class Verdict {
        /** 两侧都有值，且**逐位**相同（含 `-0.0f` 与 `0.0f` 的区分、NaN 的位模式）。 */
        IDENTICAL,

        /** 两侧都有值，不是逐位相同，但每个分量都在容差内（数值等价）。 */
        CLOSE,

        /** 两侧都有值，有分量超出容差（真的不同）。 */
        DIFFERENT,

        /** 至少一侧没值（缺失）—— **不是**"相同"，也不是"不同"。 */
        MISSING,
    }

    /**
     * 一条的比对结果。[maxAbsDiff] 是逐分量最大绝对差（缺失时为 `NaN`）——
     * **必须报出来**：只说"CLOSE"而不给差多少，用户无法判断它离"逐位相同"
     * 有多远，也就判断不了"换个批次布局会不会就相同了"。
     */
    data class Row(
        /** 这是第几条（原下标，**不是**合批里的位置）。 */
        val index: Int,
        val verdict: Verdict,
        /** 逐分量最大绝对差；[Verdict.MISSING] 时为 `NaN`。 */
        val maxAbsDiff: Float,
        /** 第一个超容差（或首个不逐位相同）的分量下标；没有则为 `-1`。 */
        val firstDiffDim: Int,
    )

    /**
     * 整批的比对结论。
     *
     * [rows] 逐条；聚合字段是为了让**调用点不重新数一遍**（重数一遍时
     * 迟早与 `rows` 分岔，而那两份数字看着都正常）。
     */
    data class Report(
        val rows: List<Row>,
        val dim: Int,
        /** 两侧都存在且**逐位**相同的条数。 */
        val identical: Int,
        /** 两侧都存在、非逐位但在容差内的条数。 */
        val close: Int,
        /** 两侧都存在、有分量超容差的条数。 */
        val different: Int,
        /** 至少一侧缺失的条数。 */
        val missing: Int,
        /**
         * 「**同一条路**连着编两次，是不是逐位相同」的逐条结论（可为空 = 没测）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么必须有这一组（真机首轮读数逼出来的）
         * ═══════════════════════════════════════════════════════════════════
         * 首轮真机报出的那 1 条不同，`firstDiffDim = 0`、`maxAbsDiff = 4.5e-03` ——
         * 一个 1024 维句向量的第 0 维差了 4 个数量级，而输入是同一条、同一个模型、
         * 同一台机。这**不是**「批大小是不是加速旋钮」能解释的：
         *
         *   · 若**同一条路**连着编两次也不逐位相同 -> 那是**编码本身不确定**
         *     （线程归约次序 / 未初始化缓冲 / 池化取错那一格），批大小是次要问题；
         *   · 若同一条路重复**逐位相同**、只有跨路才不同 -> 差异来自**批布局**，
         *     这才轮到"要不要收窄结论、批大小能不能放行"。
         *
         * 而 [rows] 那一组**分不开这两件事**：它是"逐条 vs 合批"各跑**一次**，
         * 差异里同时混着"确定性"与"批布局"两个来源 —— 这条读数不补上，
         * "要不要继续查批大小"就只能靠猜（本仓库的规矩是判据要落在具体一格上）。
         *
         * 条数一般与 [rows] 相同（每条都重复编了一次），也可能为**空**
         * （调用方没做重复对照）—— 空与"全绿"必须分开：空是**没测**。
         */
        val repeatRows: List<Row> = emptyList(),
        /**
         * 「**同一条路**再多编一遍，是不是逐位相同」的第二组逐条结论（可为空 = 没测）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么一组不够（`0.9.221` 那组的对称缺口）
         * ═══════════════════════════════════════════════════════════════════
         * [repeatRows] 只把**逐条**那条路重复了一次 —— 两条路里只查了一条。
         * 而"合批是纯加速旋钮"这个结论要成立，还差另一条：**合批那条路自己**
         * 重复时是不是也逐位相同。缺这一格时，"跨路有差异"这个读数里混着
         * "合批自身不确定"这一份，而它与"批布局改语义"处置相反。
         *
         *   · 逐条自重复全绿、合批自重复**就有不同** -> 那是**合批自身不确定**
         *     （同一批、同一台机、同一次前向都复现不了自己），与"批布局改语义"
         *     是两件事 —— 前者去查合批那条路，后者才是 `g` 收不收窄的问题；
         *   · 合批自重复也全绿 -> 两件"不确定性"都被排掉了，只剩**跨路**差异
         *     （那才是批布局），这时"要不要收窄"才真的能谈。
         *
         * 条数与 [repeatRows] 相同（同批样本各编两遍），也可能为**空**（没测）。
         * 空与"全绿"必须分开，同 [repeatRows] 的理由。
         */
        val repeatBatchedRows: List<Row> = emptyList(),
    ) {
        /**
         * 整批是否**逐位全同**（= 断言成立）。
         *
         * ⚠ 判据是 `identical == rows.size`（**全部**），不是 `different == 0`：
         * 后者会把"有缺失"也算成成立 —— 而缺失时我们连"这一条算没算出来"
         * 都不知道，把它算进"成立"是把未知当成已知（与 `salvage` 的
         * `-1 = 没算` 同一条理由）。
         *
         * ⚠ 还必须 **`rows.isNotEmpty()`**：空批时 `identical == rows.size` 是
         * `0 == 0` —— **一条都没编过就说"整批逐位相同"**，正是本文件反复要挡的
         * "没验过 == 验过了"（空批该说"没有可比对"，不是"成立"）。这一格与
         * [repeatMeasured] / `salvage = -1` 是**同一条**口径：没有读数 ≠ 读数成立。
         */
        val allIdentical: Boolean get() = rows.isNotEmpty() && identical == rows.size

        /**
         * 「同一条路连着编两次」里**不是逐位相同**的条数。
         *
         * 判据与 [allIdentical] 同一条理由：只数"不同"会漏掉"缺失"
         * （缺失时连"这条算没算出来"都不知道），所以**有没有测过**由
         * [repeatMeasured] 单独回答，而不是把 `repeatDifferent == 0`
         * 读成"同路径重复全绿"。
         */
        val repeatDifferent: Int get() = repeatRows.count { it.verdict == Verdict.DIFFERENT }

        /** 「同路径重复」里非逐位相同的条数（含 CLOSE / MISSING —— 只要有差异就算）。 */
        val repeatNotIdentical: Int get() = repeatRows.count { it.verdict != Verdict.IDENTICAL }

        /**
         * 「同路径重复」这件事**到底测过没有**。
         *
         * ⚠ 不能拿 `repeatDifferent == 0` 当"全绿"：没测过时它也是 0 ——
         * 而"没测过"与"测了、全绿"给出的下一步相反（前者该去测，后者可以
         * 把差异归给批布局）。与本对象里 `batchedEntryRatio` 空态给 null
         * 而不是 0.0 同一条理由。
         */
        val repeatMeasured: Boolean get() = repeatRows.isNotEmpty()

        /**
         * 同路径重复是否**全绿**（测过、且每条都逐位相同）。
         *
         * 这是分岔"差异来自哪里"的那一格：真 = 差异来自**批布局**（可谈收窄）；
         * 假 = 连同一条路自己都不能复现自己，**先别谈批大小**。
         *
         * ⚠ 它只管**逐条**那条路的重现性。合批那条路的重现性由
         * [repeatBatchedAllIdentical] 单独说 —— 两件事处置不同。
         */
        val repeatAllIdentical: Boolean get() = repeatMeasured && repeatNotIdentical == 0

        /** 「合批自己重复一遍」里不是逐位相同的条数（没测时为 0，读之前先看 [repeatBatchedMeasured]）。 */
        val repeatBatchedNotIdentical: Int get() =
            repeatBatchedRows.count { it.verdict != Verdict.IDENTICAL }

        /**
         * 「合批那条路自己重复」这件事**测过没有**。
         *
         * ⚠ 与 [repeatMeasured] 同一条理由：没测过时 [repeatBatchedNotIdentical] 也是 0，
         * 读成"全绿"就是把"没测过"说成"验过了、没问题"。
         */
        val repeatBatchedMeasured: Boolean get() = repeatBatchedRows.isNotEmpty()

        /**
         * 合批那条路自己重复是否**全绿**（测过、且每条都逐位相同）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么要单独一格（而不是并进 [repeatAllIdentical]）
         * ═══════════════════════════════════════════════════════════════════
         * 两个"不确定性"是**两件不同的故障**，处置不同：
         *
         *   · 逐条自重复有差异 -> 逐条那条路不确定（可能影响的不止是合批）；
         *   · 合批自重复有差异 -> 合批那条路不确定（同批同次前向都复现不了自己）。
         *
         * 合成一格时"合批自己就不确定"会被读成"合批与逐条不同" —— 而后者才是
         * `g` 收不收窄的问题，前者根本不该谈 `g`（与 `repeatMeasured` 分开报
         * `notIdentical` 同一条理由）。
         */
        val repeatBatchedAllIdentical: Boolean get() =
            repeatBatchedMeasured && repeatBatchedNotIdentical == 0

        /**
         * 「哪些条目算得不一样」——**一行一条**，给日志用（`/health` 只报计数）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么必须有它（首轮真机读数逼出来的）
         * ═══════════════════════════════════════════════════════════════════
         * 真机首轮是 `different`：**6 条里 1 条不同**。而 [describe] 只说得出计数 ——
         * "是哪一条"没有落点，于是"要不要把结论收窄成'不等长合批有问题'"只能靠猜。
         * 而这两件事的下一步**相反**：
         *
         *   · 整批都不同 -> 合批这条路整体不可信，批大小不能当加速旋钮；
         *   · 只有某几条不同 -> 是**那几条**踩到了边界（不等长合批的 padding、
         *     超长那条的截断点），其余条目其实逐位相同。
         *
         * ⚠ [Row.maxAbsDiff] / [Row.firstDiffDim] 一开始就算出来了，却**没有任何
         *   地方用它** —— "算出来但没人读"与"没算"在现象上一模一样（本仓库记录过
         *   的一族：读数要有落点才算读数）。这里把它落到日志的一行上。
         *
         * 只报**不是逐位相同**的那些条：全绿时这一节是空的（逐位相同与否
         * 由 [describe] 那行说），而 40 条全绿会把这行日志淹掉。
         */
        fun detailLines(): List<String> {
            var out = rows
                .filter { it.verdict != Verdict.IDENTICAL }
                .map { row -> "  · " + rowLine(row, "跨路") }
            // 「同路径重复」的明细**只在这种情况下列**：它有非逐位相同的条目
            // （那是最要紧的一类 —— 它把"差异来自批布局"这个前提本身否掉了）。
            // 全绿时不列：全绿是**预期**，而把 6 行"重复也相同"打出来只会
            // 把上面那条真正的结论淹掉（与本节只列非逐位相同同一条理由）。
            if (repeatMeasured && !repeatAllIdentical) {
                out += repeatRows
                    .filter { it.verdict != Verdict.IDENTICAL }
                    .map { row -> "  · " + rowLine(row, "同路重复·逐条") }
            }
            // 合批自己也重复了一遍：它有差异时更要列 —— 那一格说的是"合批自身
            // 不确定"，与"批布局改语义"是两件事（标签必须与 [repeatRows] 那组
            // 分开，否则两组都写"重复"时指不出是哪一条路）。
            if (repeatBatchedMeasured && !repeatBatchedAllIdentical) {
                out += repeatBatchedRows
                    .filter { it.verdict != Verdict.IDENTICAL }
                    .map { row -> "  · " + rowLine(row, "同路重复·合批") }
            }
            return out
        }

        /**
         * 一条明细的措辞。`tag` 说清这是**哪一组**（跨路 vs 同路重复）——
         * 两组都列时"第 3 条：DIFFERENT"会长得一样，而它们指向完全不同的根因
         * （批布局 vs 编码本身不确定），分不开就等于没报。
         */
        private fun rowLine(row: Row, tag: String): String {
            val d = if (row.maxAbsDiff.isNaN()) "未知" else String.format("%.3e", row.maxAbsDiff)
            return "第 ${row.index} 条（$tag）：${row.verdict}，最大分量差 $d，" +
                "首个不逐位的分量 ${row.firstDiffDim}"
        }

        /**
         * 说一句话（给日志 / 界面）。
         *
         * 为什么要一句话而不是让调用方自己拼：这句话是用户唯一会看的结论，
         * 它的措辞必须与 [Verdict] 的语义**一致**。两处各拼一遍，迟早一处说
         * "向量一致"、另一处说"差不多"（同一个观测，两种口径）。
         */
        fun describe(): String {
            val head = "逐位比对 ${rows.size} 条（dim=$dim）：逐位相同 $identical 条，" +
                "仅数值接近 $close 条，真的不同 $different 条，缺失 $missing 条"
            val body = when {
                rows.isEmpty() -> "逐位比对：没有可比对的条目（两侧都没跑出向量）"
                allIdentical -> "$head —— **整批逐位相同**，批大小是纯加速旋钮"
                different > 0 -> "$head —— 有 $different 条真的不同，" +
                    "批大小**不能用**纯加速旋钮来解释（沿用会把两种语义拼起来）"
                missing > 0 -> "$head —— 有 $missing 条缺失，" +
                    "补齐后再判（缺失不是'相同'也不是'不同'）"
                else -> "$head —— 不是逐位相同（有浮点级差异），" +
                    "**不能**据此沿用（索引里仍是两种向量）"
            }
            return body + repeatSentence()
        }

        /**
         * 「同路径重复」那一句（接在跨路结论后面）。
         *
         * 措辞**必须**把三档结论分开说（这也是 [repeatSentence] 有四个分支的原因）：
         *
         *   · 逐条自重复就有差异 -> **先别谈批大小**（编码本身不确定），
         *     合批那格**不解读**（它答不了"批布局是不是元凶"）；
         *   · 逐条全绿、**合批自重复**有差异 -> 一样**先别谈批大小**
         *     （合批自身不确定，那是另一件事：先查合批那条路）；
         *   · 两条路各自都全绿 -> 差异才能归给**批布局**（那才轮到"批大小能不能放行"）。
         *
         * 三档只看 [describe] 那半句时长得一样（都叫"有不同"），分不开就等于没报。
         * 没测过时**不说"全绿"**，只说没测（同 [repeatMeasured] 的理由）。
         */
        private fun repeatSentence(): String = when {
            !repeatMeasured -> "（本次没做同路径重复对照 —— 差异来源分不开：是批布局还是编码本身不确定）"
            // 逐条那条路自己都不能复现自己：一切都停在这里 —— 合批那格**先不解读**
            // （它再有读数也答不了"批布局是不是元凶"，那是下一个问题）。
            !repeatAllIdentical -> "；⚠ 同路径重复（逐条）就有 ${repeatNotIdentical} 条不是逐位相同" +
                "（第 ${repeatRows.first { it.verdict != Verdict.IDENTICAL }.index} 条起）" +
                " —— 连同一条路自己都不能复现自己，**先别谈批大小**（那是编码本身不确定）"
            // 逐条自重复全绿，但**合批那条路自己**复现不了自己 -> 一样先别谈批大小：
            // 差异里混着"合批自身不确定"这一份，它和"批布局改语义"是两件事。
            repeatBatchedMeasured && !repeatBatchedAllIdentical ->
                "；同路径重复（逐条）${repeatRows.size} 条全绿，但**合批**那条路自己重复时" +
                    "就有 ${repeatBatchedNotIdentical} 条不是逐位相同" +
                    " —— 那是**合批自身不确定**，**先别谈批大小**（先查合批那条路）"
            // 两组重复都全绿：这时才轮到把跨路差异归给**批布局**。
            // 跨路也全绿时不说"差异来自批布局"—— 那时根本没有差异可归因。
            allIdentical -> "；同路径重复（逐条 ${repeatRows.size} 条）全绿" +
                repeatBatchedSuffix() + "（跨路也逐位相同，没有差异可归因）"
            else -> "；同路径重复（逐条 ${repeatRows.size} 条）全绿" + repeatBatchedSuffix() +
                "（差异来自**批布局**，不是编码本身不确定）"
        }

        /**
         * 「合批那条路重复」的半句（接在逐条那半句后面）。
         *
         * 没测过时只说**没测**（不说全绿）；测过且全绿时报条数（它把"合批自身
         * 不确定"这一份排掉了 —— 那是"差异来自批布局"这句能不能说的前提）。
         */
        private fun repeatBatchedSuffix(): String = when {
            !repeatBatchedMeasured -> "、合批**未做**同路径重复"
            repeatBatchedAllIdentical -> "、合批重复 ${repeatBatchedRows.size} 条也全绿"
            // 这一支上面已经拦过了（会被 `when` 的上面那支先命中）—— 保留是为了
            // 让"三种状态各有落点"，而不是让一个不可能值静默落进别的档。
            else -> "、合批重复有 ${repeatBatchedNotIdentical} 条不是逐位相同"
        }
    }

    /** 默认容差：绝对差超过它算"真的不同"。见对象头那段（为什么不是余弦）。 */
    const val DEFAULT_EPS = 1e-6f

    /**
     * 比一对向量。
     *
     * `a` / `b` 任一为 null -> [Verdict.MISSING]（**不是**"相同"）。
     * 长度不等 -> 也算 [Verdict.MISSING]：长度对不上时"逐位比"没有定义，
     * 而按短的比会把"整体错位"判成"前几位的差异"（错误的方向）。
     */
    fun compareRow(index: Int, a: FloatArray?, b: FloatArray?, eps: Float = DEFAULT_EPS): Row {
        if (a == null || b == null) return Row(index, Verdict.MISSING, Float.NaN, -1)
        if (a.size != b.size) return Row(index, Verdict.MISSING, Float.NaN, -1)
        var maxAbs = 0.0f
        var firstNonBitEq = -1
        var firstOver = -1
        for (i in a.indices) {
            // 逐位相同：**同一位模式**才算。`-0.0f == 0.0f` 在这里为 true
            // （`Float.equals` 也不分正负零），所以正负零算相同 —— 它们
            // 数值上确实相同，而"符号零"不是这里的故障形态。
            // NaN：两侧都是 NaN 才算相同（`!=` 对 NaN 恒真，所以单靠 `==`
            // 会把 NaN 判成不同 —— 这里显式处理，避免把"都是 NaN"报成差异，
            // 那种差异不指向批布局，只指向这一条本身有问题）。
            val bothNaN = a[i].isNaN() && b[i].isNaN()
            val bitEq = bothNaN || a[i] == b[i]
            if (!bitEq && firstNonBitEq < 0) firstNonBitEq = i
            val d = if (bothNaN) 0.0f else kotlin.math.abs(a[i] - b[i])
            // NaN 参与相减 -> NaN，`maxAbs` 会被污染成 NaN。两个都非 NaN 时才算差值。
            if (!d.isNaN() && d > maxAbs) maxAbs = d
            if (d > eps && firstOver < 0) firstOver = i
        }
        return when {
            firstNonBitEq < 0 -> Row(index, Verdict.IDENTICAL, maxAbs, -1)
            firstOver >= 0 -> Row(index, Verdict.DIFFERENT, maxAbs, firstOver)
            else -> Row(index, Verdict.CLOSE, maxAbs, firstNonBitEq)
        }
    }

    /**
     * 比一整批。`a` 是**逐条路径**的向量表（`null` = 这条没编出来），
     * `b` 是**合批路径**的（同）。两边长度不等按较长的一边走（缺的那侧为 null）。
     *
     * 为什么两边长度不等也要比（而不是判"整批无效"）：长度不等本身就是
     * 一个要报出来的事实（某条在一条路径上成功、另一条上失败），
     * 判"整批无效"会让这个事实消失，而它是这一层最该说清的一类差异。
     */
    fun compare(
        a: List<FloatArray?>,
        b: List<FloatArray?>,
        dim: Int,
        eps: Float = DEFAULT_EPS,
        /**
         * 「**逐条**那条路连着编两次」的第二遍（[a] 是第一遍）。**空 = 没测**
         * （不是"测了全绿"）—— 见 [Report.repeatMeasured]。
         */
        aRepeat: List<FloatArray?> = emptyList(),
        /**
         * 「**合批**那条路连着编两次」的第二遍（[b] 是第一遍）。**空 = 没测**
         * （不是"测了全绿"）—— 见 [Report.repeatBatchedMeasured]。
         *
         * ⚠ 比的是 `b` 与它的第二遍，**不是**与 `a` —— 与 `a` 比是跨路那件事，
         * 已经在 [rows] 里了。这里要回答的是"合批自己复现不复现得了自己"。
         */
        aBatchedRepeat: List<FloatArray?> = emptyList(),
    ): Report {
        val n = maxOf(a.size, b.size)
        val rows = ArrayList<Row>(n)
        for (i in 0 until n) {
            rows.add(compareRow(i, a.getOrNull(i), b.getOrNull(i), eps))
        }
        val repeats = ArrayList<Row>(aRepeat.size)
        for (i in aRepeat.indices) {
            repeats.add(compareRow(i, a.getOrNull(i), aRepeat[i], eps))
        }
        // 合批那条路的自重复：同一支判据（逐位 + 容差），只是换了一对向量。
        // 为什么不复用上面那个循环：两个"第二遍"是**两条路**的第二遍，
        // 合成一个循环时要靠下标自己记"这次是谁"，而那正是最容易串的一处。
        val batchedRepeats = ArrayList<Row>(aBatchedRepeat.size)
        for (i in aBatchedRepeat.indices) {
            batchedRepeats.add(compareRow(i, b.getOrNull(i), aBatchedRepeat[i], eps))
        }
        return Report(
            rows = rows,
            dim = dim,
            identical = rows.count { it.verdict == Verdict.IDENTICAL },
            close = rows.count { it.verdict == Verdict.CLOSE },
            different = rows.count { it.verdict == Verdict.DIFFERENT },
            missing = rows.count { it.verdict == Verdict.MISSING },
            repeatRows = repeats,
            repeatBatchedRows = batchedRepeats,
        )
    }

    /**
     * 「最近一次逐位比对」的对外读数（给 `/health` 报）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么必须报出来（而不是只在日志里打一句）
     * ═══════════════════════════════════════════════════════════════════════
     * 这个对照的结论决定一件**改不了回头看**的事：改批大小要不要重建索引。
     * 只在日志里说，用户就要先动手翻日志（而日志会滚）；而且结论是**跨版本**
     * 要对的（"0.9.218 那条断言到底成立没有"）—— 打探就能读到才是读数。
     *
     * 字段保守地只报**能支撑结论的那几个**（不报整张逐条表：几十条明细塞进
     * `/health` 会让它变成一份报告，而探活要的是"一句话能判"）：
     *   · `embed_parity_rows`    —— 参与比对的条数（0 = 还没跑过）；
     *   · `embed_parity_identical` —— 逐位相同的条数；
     *   · `embed_parity_different` —— **真的不同**的条数（"1/6"与"6/6"下一步相反）；
     *   · `embed_parity_verdict` —— 结论档（见 [ParityState.verdict]）。
     *
     * ⚠ 判据与 [Report.allIdentical] **同一个**（全部逐位相同），不在这里
     *   重算一遍 —— 两处各算一遍时迟早一处报"相同"、另一处报"近似"。
     */
    object Last {
        /**
         * 结论档。用**字符串**（而不是把布尔塞进 `/health`）：三档结论里
         * "不是逐位相同"与"其实没测过"是两件事，一个布尔放不下 ——
         * 而把"没测过"报成 `false` 会被读成"验过了，结论是不相同"（一个**结论**），
         * 与真相（没有读数）方向相反（与 `batchedEntryRatio` 的 null 同一条理由）。
         */
        enum class Verdict { UNKNOWN, IDENTICAL, CLOSE, DIFFERENT, MISSING }

        /**
         * 一次快照。字段与 `/health` 一一对应（守卫正面钉住这点）。
         *
         * ⚠ 「路径重复」是**两格**（逐条 / 合批），不是一个布尔：两条路各自
         * 复现得了复现不了自己是两件不同的故障，压成一格时"合批自己就不确定"
         * 会被读成"合批与逐条不同"（后者才是 `g` 收不收窄的问题）。
         *
         * [different] 与 [identical] **必须都在**：只报相同条数时，
         * "6 条里 1 条不同"与"6 条里 6 条不同"在读数上长得一样 ——
         * 而前者说"去查那 1 条"，后者说"这条路整体不可信"（下一步相反）。
         *
         * ⚠ [different] 与 [verdict] 可能**自相矛盾**（verdict=DIFFERENT 而
         * different=0）：这不是可以"顺手改成 0"的噪音，而是"快照被写坏了"的
         * 证据。所以 [record] 把它拦下来（不落这个读数），而不是让它在
         * `/health` 里冒充一个正常数。
         */
        data class State(
            val rows: Int,
            val identical: Int,
            val different: Int,
            val verdict: Verdict,
            /**
             * 「同路径重复」的读数。`repeatMeasured=false` = **这次没测**
             * （不是"测了全绿"）—— 见 [Report.repeatMeasured] 与
             * `/health` 的 `embed_parity_repeat_*`。
             */
            val repeatMeasured: Boolean,
            /** 「同路径重复」里不是逐位相同的条数（没测时为 0，读之前先看 [repeatMeasured]）。 */
            val repeatNotIdentical: Int,
            /**
             * 「**合批**那条路自己重复」的读数。`false` = 这次没测（不是"测了全绿"）
             * —— 见 [Report.repeatBatchedMeasured]。
             */
            val repeatBatchedMeasured: Boolean,
            /** 「合批自己重复」里不是逐位相同的条数（没测时为 0，读之前先看 [repeatBatchedMeasured]）。 */
            val repeatBatchedNotIdentical: Int,
            /**
             * 这份读数取自**哪一组样本**（`长短悬殊` / `等长`，空串 = 还没跑过）。
             *
             * ═══════════════════════════════════════════════════════════════
             * 为什么"取自哪一组"必须一起报
             * ═══════════════════════════════════════════════════════════════
             * 两组样本回答的是**两个不同的问题**，而它们的结论方向相反：
             *
             *   · 等长组把"长度齐不齐"这一个变量**拿掉**了，它一旦逐位相同
             *     只说明"批布局本身不改语义" —— 那是**构造上偏稳**的一组；
             *   · 长短悬殊组是**唯一能改变答案**的一组（含超长 / 截断），
             *     它才是"改批大小要不要重建索引"要问的那件事。
             *
             * 只报 `identical/different/verdict` 而不报取自哪一组时，一行
             * "逐位相同 4/4 —— 批大小是纯加速旋钮"在两组下**长得一模一样**，
             * 而读者会把它读成"长短悬殊那组也稳"（真机现场正是这样：`/health`
             * 报 4/4 全同，而同一份日志里长短悬殊是「真的不同 1 条、4.510e-03」）。
             * 于是"是哪一组"不是可以省掉的标签，而是这行读数**能不能被解释**的前提。
             */
            val group: String,
        )

        private val lock = Any()
        private var rows = 0
        private var identical = 0
        private var different = 0
        private var verdict = Verdict.UNKNOWN
        private var repeatMeasured = false
        private var repeatNotIdentical = 0
        private var repeatBatchedMeasured = false
        private var repeatBatchedNotIdentical = 0
        private var group = ""

        /**
         * 记一次对照结论。[r] 为 null = 没得到结论（保持 [Verdict.UNKNOWN]）。
         *
         * [g] = 这份结论取自哪一组样本（只作回显，不参与判定）—— 见 [State.group]：
         * 两组回答的问题不同，只报数字不报来源时，"长短悬殊也稳"会被从
         * **构造上偏稳**的那组推出来。
         */
        fun record(r: Report?, g: String = "") {
            synchronized(lock) {
                if (r == null) { verdict = Verdict.UNKNOWN; group = ""; return }
                val v = when {
                    r.rows.isEmpty() -> Verdict.UNKNOWN
                    r.allIdentical -> Verdict.IDENTICAL
                    r.different > 0 -> Verdict.DIFFERENT
                    r.missing > 0 -> Verdict.MISSING
                    else -> Verdict.CLOSE
                }
                // 自洽检查：`DIFFERENT` 这一档的**充分必要**条件是 `different > 0`
                // （见上面那个 `when` 的分支）。对不上只有两种可能：要么这份
                // `Report` 是手拼的（不是 `compare` 出来的），要么 `when` 被改岔了。
                // 两种情况下这三个数都**不能**发出去 —— `verdict=different` 配
                // `different=0` 会被读成"验过了，没有不同的条目"（一个**相反的结论**），
                // 而它比不报更坏（不报只是没读数）。落成 UNKNOWN 才是如实：没有结论。
                // ⚠ 两条"拦下来"的分支都**同时清 group**：留着它时会出现
                //   「verdict=unknown（没结论）」却仍标着"长短悬殊"的读数 ——
                //   而那个标签正是让人把这份读数当成某组结论的东西（见 State.group）。
                if (v == Verdict.DIFFERENT && r.different <= 0) { group = ""; return }
                // 自洽检查二：「合批自己重复全绿」与「合批自己重复有 N>0 条不同」
                // **不可能同时成立**（一个是 `count != IDENTICAL == 0`，另一个是它 > 0）。
                // 对不上说明这份 `Report` 是手拼的 —— 而报出去会被读成"合批自身是确定的"
                // （一个**相反的结论**：它把"合批复现不了自己"洗成"合批很稳"，
                // 而那正是要不要谈批布局的前提）。落 UNKNOWN 才是如实：没有结论。
                //
                // ⚠ 判据与 `Report.repeatBatchedAllIdentical` **同一个**（不在这里
                // 重算一遍）—— 两处各算一遍时迟早一处说"全绿"、另一处说"有差异"。
                val batchedOk = !(r.repeatBatchedAllIdentical && r.repeatBatchedNotIdentical > 0)
                repeatBatchedMeasured = r.repeatBatchedMeasured && batchedOk
                repeatBatchedNotIdentical = if (batchedOk) r.repeatBatchedNotIdentical else 0
                rows = r.rows.size
                identical = r.identical
                different = r.different
                verdict = v
                // 「同路径重复」一并落盘（它决定"差异来自批布局还是编码本身不确定"
                // 那一格 —— 只有跨路读数时说不出这件事，见 Report.repeatRows）。
                repeatMeasured = r.repeatMeasured
                repeatNotIdentical = r.repeatNotIdentical
                group = g
            }
        }

        fun snapshot(): State = synchronized(lock) {
            State(rows, identical, different, verdict, repeatMeasured, repeatNotIdentical,
                repeatBatchedMeasured, repeatBatchedNotIdentical, group)
        }

        /** 清零（换嵌入模型时调 —— 结论描述的是"这个模型"的批行为）。 */
        fun reset() = synchronized(lock) {
            rows = 0; identical = 0; different = 0; verdict = Verdict.UNKNOWN
            repeatMeasured = false; repeatNotIdentical = 0
            repeatBatchedMeasured = false; repeatBatchedNotIdentical = 0
            group = ""
        }
    }
}
