package com.xiaowan.localinference

/**
 * `EmbedParity` 的离线单测（ISSUE #269 第 5 步：把"g 是不是纯加速旋钮"量出来）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么这一层要单测
 * ═══════════════════════════════════════════════════════════════════════
 * 这一层的结论**直接决定**"改批大小要不要重建索引"，而它的失效形态全是
 * "结论变成一个像真的的东西"：
 *   · 把"缺失"（一侧没向量）报成"相同" -> 据此沿用，索引里是两种语义；
 *   · 把"浮点级差异（CLOSE）"报成"逐位相同" -> 同上，只是更隐蔽
 *     （差异小到看不出来，而检索会慢慢漂）；
 *   · `allIdentical` 用 `different == 0` 判 -> "有条目缺失"也会算成立；
 *   · 长度不等按短的比 -> 整体错位被判成"前几位的差异"（方向错）；
 *   · 正负零 / NaN 的位模式处理错 -> 把"都是 NaN"报成差异（不指向批布局）。
 * 都不抛异常、不崩溃，只有断言能钉住。
 *
 * **纯逻辑**（不碰 Android、不碰文件、不碰 native），所以能直接跑在宿主上。
 */
object EmbedParityTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") }
        else { fail++; println("FAIL  $what") }
    }

    private fun eqi(a: Any?, b: Any?, what: String) = ok(a == b, "$what（实际 $a）")

    private fun f(vararg xs: Float) = xs.toList().toFloatArray()

    /**
     * **容差之内、但 f32 真的表示得出来**的那点差（"接近但非逐位"要的就是它）。
     *
     * ⚠ 这份单测原先到处写的是 `1.0f + 1e-9f` —— 而 1e-9 在 f32 里**低于
     *   1.0 的 ulp（约 1.19e-7）**，加完还是 `1.0f`：输入**根本不是**"接近但
     *   非逐位"，而是**逐位相同**。于是凡是用它的断言全部朝着"IDENTICAL"
     *   倒（10 条），而它们要量的 `CLOSE` 那一档**从来没有被这条输入碰到过** ——
     *   典型的"测试输入产不出要被观察的现象"，与 `EmbedRetrieveParity` 那两条
     *   同源（见 `run_kt_test_runner_guard.py` 的文件头）。
     *
     * 这里取 `Math.ulp` 的**一倍**（约 1.19e-7）：它大于"逐位"、小于默认容差
     * `EmbedParity.DEFAULT_EPS`（1e-6），正好落在 `CLOSE` 那一档的中间。
     * 用 `Math.ulp(x)` 而不是写一个常数，是因为**ulp 随量级变**（4.0 的 ulp
     * 是 1.0 的 4 倍）——写死一个数就只能在某一个量级上成立。
     */
    private fun near(x: Float) = x + Math.ulp(x)

    @JvmStatic
    fun main(args: Array<String>) {
        // ── 逐位相同：同一组数 -> IDENTICAL，且 maxAbsDiff = 0 ─────────────
        run {
            val a = f(0.1f, 0.2f, 0.3f)
            val b = f(0.1f, 0.2f, 0.3f)
            val r = EmbedParity.compareRow(0, a, b)
            eqi(r.verdict, EmbedParity.Verdict.IDENTICAL, "同一组数 -> 逐位相同")
            eqi(r.maxAbsDiff, 0.0f, "逐位相同 -> 最大差 0")
            eqi(r.firstDiffDim, -1, "逐位相同 -> 没有差异分量")
        }

        // ── 正负零：数值相同，算逐位相同（符号零不是这里的故障形态）──────
        run {
            val r = EmbedParity.compareRow(0, f(0.0f, 1.0f), f(-0.0f, 1.0f))
            eqi(r.verdict, EmbedParity.Verdict.IDENTICAL, "正负零算逐位相同（数值相同）")
        }

        // ── 双方都是 NaN：算相同（不指向批布局）──────────────────────────
        // 若用 `a!=b` 判会报 DIFFERENT —— 而"两条路都算出 NaN"说明这一条本身
        // 有问题（输入/池化），与批大小无关，报成"不同"会把用户带错方向。
        run {
            val r = EmbedParity.compareRow(0, f(Float.NaN, 1.0f), f(Float.NaN, 1.0f))
            eqi(r.verdict, EmbedParity.Verdict.IDENTICAL, "两侧都是 NaN 算逐位相同")
            ok(!r.maxAbsDiff.isNaN(), "都是 NaN 时最大差不是 NaN（不被污染）")
        }

        // ── 浮点级差异：不是逐位，但在容差内 -> CLOSE（**不是** IDENTICAL）──
        // 这一条最关键：CLOSE 被当成 IDENTICAL 就是"据此沿用"，而索引里是两种向量。
        run {
            val r = EmbedParity.compareRow(0, f(1.0f), f(near(1.0f)))
            eqi(r.verdict, EmbedParity.Verdict.CLOSE, "浮点级差异 -> 接近但非逐位")
            ok(r.firstDiffDim == 0, "CLOSE 要报出第一个不逐位的分量")
            ok(r.maxAbsDiff > 0.0f, "CLOSE 的最大差 > 0（差多少要报出来）")
        }

        // ── 超容差：DIFFERENT ────────────────────────────────────────────
        run {
            val r = EmbedParity.compareRow(0, f(1.0f, 0.0f), f(1.0f, 0.5f))
            eqi(r.verdict, EmbedParity.Verdict.DIFFERENT, "超容差 -> 真的不同")
            eqi(r.firstDiffDim, 1, "DIFFERENT 指向第一个超容差的分量")
        }

        // ── 一侧缺失：MISSING（**不是** IDENTICAL / DIFFERENT）─────────────
        run {
            eqi(EmbedParity.compareRow(0, null, f(1.0f)).verdict, EmbedParity.Verdict.MISSING,
                "一侧为 null -> MISSING")
            eqi(EmbedParity.compareRow(0, f(1.0f), null).verdict, EmbedParity.Verdict.MISSING,
                "另一侧为 null -> MISSING")
            eqi(EmbedParity.compareRow(0, null, null).verdict, EmbedParity.Verdict.MISSING,
                "两侧都 null -> MISSING")
            ok(EmbedParity.compareRow(0, null, f(1.0f)).maxAbsDiff.isNaN(),
                "MISSING 的最大差是 NaN（未知，不是 0）")
        }

        // ── 长度不等：也算 MISSING（按短的比会把错位判成"前几位的差异"）────
        run {
            val r = EmbedParity.compareRow(0, f(1.0f, 2.0f), f(1.0f, 2.0f, 3.0f))
            eqi(r.verdict, EmbedParity.Verdict.MISSING, "长度不等 -> MISSING（比不了，不猜）")
        }

        // ── 整批聚合：计数与 allIdentical ────────────────────────────────
        run {
            val a = listOf(f(1f, 2f), f(3f, 4f), f(5f, 6f), null)
            val b = listOf(f(1f, 2f), f(3f, near(4f)), f(5f, 9f), f(7f, 8f))
            val rep = EmbedParity.compare(a, b, dim = 2)
            eqi(rep.rows.size, 4, "整批：4 条")
            eqi(rep.identical, 1, "整批：逐位相同 1 条")
            eqi(rep.close, 1, "整批：接近 1 条")
            eqi(rep.different, 1, "整批：不同 1 条")
            eqi(rep.missing, 1, "整批：缺失 1 条")
            ok(!rep.allIdentical, "有不同/缺失时 allIdentical 为假")
        }

        // ── allIdentical 的判据是"全部逐位"（不是"没有不同"）─────────────
        // 若用 `different == 0` 判，下面这一批（有 CLOSE、有 MISSING）会被
        // 算成"成立" —— 那是把未知（缺失）与近似（close）都当成了已知。
        run {
            val a = listOf(f(1f), f(2f), null)
            val b = listOf(f(1f), f(near(2f)), f(3f))
            val rep = EmbedParity.compare(a, b, dim = 1)
            eqi(rep.different, 0, "这批没有 DIFFERENT")
            ok(!rep.allIdentical, "只有 close + missing 时 allIdentical **仍为假**（不是「没有不同」就算成立）")
        }

        run {
            val a = listOf(f(1f), f(2f))
            val b = listOf(f(1f), f(2f))
            ok(EmbedParity.compare(a, b, dim = 1).allIdentical, "全部逐位相同 -> allIdentical 为真")
        }

        // ── 两侧长度不等：按较长的一边走，缺的那侧为 null（不判整批无效）──
        run {
            val rep = EmbedParity.compare(listOf(f(1f)), listOf(f(1f), f(2f)), dim = 1)
            eqi(rep.rows.size, 2, "长度不等按较长的一边走（不丢事实）")
            eqi(rep.missing, 1, "多出来的那条算缺失")
        }

        // ── 空批：没有条目 -> allIdentical 为假（不报"成立"）─────────────
        run {
            val rep = EmbedParity.compare(emptyList(), emptyList(), dim = 4)
            ok(!rep.allIdentical, "空批 allIdentical 为假（没验过，不是验过了）")
            ok(rep.describe().contains("没有可比对"), "空批 describe 说的是「没有可比对」")
        }

        // ── describe 的措辞必须与结论一致（把 CLOSE 说成"相同"会让人据此沿用）──
        run {
            val d = EmbedParity.compare(listOf(f(1f)), listOf(f(near(1f))), dim = 1).describe()
            ok(!d.contains("整批逐位相同"), "CLOSE 的 describe **不**说「整批逐位相同」")
            ok(d.contains("不能"), "CLOSE 的 describe 明确说不能据此沿用")
        }
        run {
            val d = EmbedParity.compare(listOf(f(1f)), listOf(f(1f)), dim = 1).describe()
            ok(d.contains("整批逐位相同"), "IDENTICAL 的 describe 说「整批逐位相同」")
        }
        run {
            val d = EmbedParity.compare(listOf(f(1f)), listOf(f(2f)), dim = 1).describe()
            ok(d.contains("真的不同"), "DIFFERENT 的 describe 说「真的不同」")
        }

        // ── Last：空态 / 记录 / 复位 ─────────────────────────────────────
        run {
            EmbedParity.Last.reset()
            val s0 = EmbedParity.Last.snapshot()
            eqi(s0.verdict, EmbedParity.Last.Verdict.UNKNOWN, "Last 空态：结论是 UNKNOWN（不是 DIFFERENT）")
            eqi(s0.rows, 0, "Last 空态：条数 0")

            // record(null)（没得到结论）**不改变**条数、只落回 UNKNOWN —— 把
            // "没测过"报成"不相同"会给出一个相反的行动方向。
            EmbedParity.Last.record(EmbedParity.compare(listOf(f(1f)), listOf(f(1f)), dim = 1))
            eqi(EmbedParity.Last.snapshot().verdict, EmbedParity.Last.Verdict.IDENTICAL,
                "Last：整批逐位相同 -> IDENTICAL")

            EmbedParity.Last.record(null)
            eqi(EmbedParity.Last.snapshot().verdict, EmbedParity.Last.Verdict.UNKNOWN,
                "Last：record(null) 落回 UNKNOWN（不是把上一次的结论留着）")

            EmbedParity.Last.record(EmbedParity.compare(listOf(f(1f)), listOf(f(near(1f))), dim = 1))
            eqi(EmbedParity.Last.snapshot().verdict, EmbedParity.Last.Verdict.CLOSE, "Last：接近 -> CLOSE")
            eqi(EmbedParity.Last.snapshot().rows, 1, "Last：条数被记下")

            EmbedParity.Last.record(EmbedParity.compare(listOf(f(1f)), listOf(f(2f)), dim = 1))
            eqi(EmbedParity.Last.snapshot().verdict, EmbedParity.Last.Verdict.DIFFERENT, "Last：不同 -> DIFFERENT")

            EmbedParity.Last.record(EmbedParity.compare(listOf(f(1f)), listOf(null), dim = 1))
            eqi(EmbedParity.Last.snapshot().verdict, EmbedParity.Last.Verdict.MISSING, "Last：缺失 -> MISSING")

            EmbedParity.Last.reset()
            eqi(EmbedParity.Last.snapshot().verdict, EmbedParity.Last.Verdict.UNKNOWN, "Last：reset 回 UNKNOWN")
            eqi(EmbedParity.Last.snapshot().identical, 0, "Last：reset 后 identical 归零")
        }

        // ── detailLines：不是逐位相同的那些条要**逐条**报出来 ───────────────
        // 首轮真机是「6 条里 1 条不同」—— 而 `describe()` 只说得出计数，"是哪一条"
        // 没有落点（`Row.maxAbsDiff` / `Row.firstDiffDim` 算了没人读）。
        // 而"整批都不同"（这条路整体不可信）与"只有某几条不同"（去查那几条）
        // 处置相反，所以这一节必须说得出条号与差多少。
        run {
            val a = listOf(f(1f, 2f), f(3f, 4f), null)
            val b = listOf(f(1f, 2f), f(3f, 9f), f(5f, 6f))
            val rep = EmbedParity.compare(a, b, dim = 2)
            val lines = rep.detailLines()
            eqi(lines.size, 2, "detailLines：只列不是逐位相同的那些条（2 条）")
            ok(lines[0].contains("第 1 条"), "detailLines：第一条指向原下标 1")
            ok(lines[0].contains("DIFFERENT"), "detailLines：说得出结论档")
            ok(lines[0].contains("首个不逐位的分量 1"), "detailLines：说得出第一个差的分量")
            ok(lines[0].contains("e"), "detailLines：最大分量差用科学计数法（小差异要看得见）")
            ok(lines[1].contains("第 2 条") && lines[1].contains("未知"),
                "detailLines：缺失那条说「未知」（不是 0 —— 0 是「差为零」）")
        }

        run {
            val rep = EmbedParity.compare(listOf(f(1f)), listOf(f(1f)), dim = 1)
            ok(rep.detailLines().isEmpty(), "detailLines：全绿时这一节为空（几十条全绿会把结论那行淹掉）")
        }

        run {
            val rep = EmbedParity.compare(listOf(f(1f)), listOf(f(near(1f))), dim = 1)
            ok(rep.detailLines().size == 1, "detailLines：CLOSE 也要列（差多少是判断「换个布局会不会就相同」的依据）")
        }

        // ── 同路径重复：`repeatRows` 分得开「批布局」与「编码本身不确定」─────
        // 首轮真机那条 `firstDiffDim=0` / `maxAbsDiff=4.5e-03` 是哪种？只有
        // "同一条路编两次"这组读数说得清 —— 而它是**没测**（空）还是**全绿**，
        // 必须分得开（`repeatMeasured`），否则"没测过"会被读成"验过了、没问题"。
        run {
            val a = listOf(f(1f, 2f), f(3f, 4f))
            val b = listOf(f(1f, 2f), f(3f, 9f))
            val rep = EmbedParity.compare(a, b, dim = 2) // 不给 repeat -> 没测
            ok(!rep.repeatMeasured, "没给重复向量 -> repeatMeasured 为假（没测，不是全绿）")
            eqi(rep.repeatNotIdentical, 0, "没测时 repeatNotIdentical 是 0（所以读它之前要看 repeatMeasured）")
            ok(!rep.repeatAllIdentical, "没测时 repeatAllIdentical 为假（不能说成全绿）")
        }

        run {
            // 跨路有差异，但同路重复**全绿** -> 差异来自**批布局**（可谈收窄）。
            val a = listOf(f(1f, 2f), f(3f, 4f))
            val b = listOf(f(1f, 2f), f(3f, 9f))
            val rep = EmbedParity.compare(a, b, dim = 2, aRepeat = a)
            ok(rep.repeatMeasured, "给了重复向量 -> repeatMeasured 为真")
            eqi(rep.repeatNotIdentical, 0, "同路重复逐位相同 -> 0 条不同")
            ok(rep.repeatAllIdentical, "同路重复全绿 -> repeatAllIdentical 为真")
            ok(rep.describe().contains("差异来自**批布局**"),
                "同路重复全绿时 describe 说「差异来自批布局」")
        }

        run {
            // 同路重复**自己**就有差异 -> 先说"编码本身不确定"，别谈批大小。
            val a = listOf(f(1f, 2f))
            val b = listOf(f(1f, 2f))
            val rep = EmbedParity.compare(a, b, dim = 2, aRepeat = listOf(f(1f, 2f + 0.5f)))
            ok(rep.repeatMeasured && !rep.repeatAllIdentical,
                "同路重复就不是逐位 -> repeatAllIdentical 为假")
            eqi(rep.repeatNotIdentical, 1, "同路重复里不是逐位相同的条数 1")
            val d = rep.describe()
            ok(d.contains("先别谈批大小"), "同路重复有差异时 describe 说「先别谈批大小」")
            // ⚠ 措辞是「同路径重复」（源码里的原话）—— 断言原先写的是「同路重复」，
            //   少一个「径」就永远命不中，于是这条断言**恒假**（一跑就红）。
            ok(d.contains("同路径重复"), "同路重复那组读数也进 describe")
        }

        // ── 「合批那条路自己重复」：单独一格，不与逐条那格合成一个 ─────────
        // 只重复逐条那条路时，「合批自身不确定」会被读成「批布局改语义」——
        // 而它们处置相反：前者先查合批，后者才谈 `g` 收不收窄。
        run {
            val a = listOf(f(1f, 2f), f(3f, 4f))
            val b = listOf(f(1f, 2f), f(3f, 9f))
            val rep = EmbedParity.compare(a, b, dim = 2, aRepeat = a) // 合批重复没给
            ok(!rep.repeatBatchedMeasured, "没给合批重复向量 -> repeatBatchedMeasured 为假（没测，不是全绿）")
            eqi(rep.repeatBatchedNotIdentical, 0,
                "没测时 repeatBatchedNotIdentical 是 0（所以读它之前要看 repeatBatchedMeasured）")
            ok(!rep.repeatBatchedAllIdentical, "没测时 repeatBatchedAllIdentical 为假（不能说成全绿）")
        }

        run {
            // 两条路各自都全绿 -> 这时才轮到"差异来自批布局"这句。
            val a = listOf(f(1f, 2f), f(3f, 4f))
            val b = listOf(f(1f, 2f), f(3f, 9f))
            val rep = EmbedParity.compare(a, b, dim = 2, aRepeat = a, aBatchedRepeat = b)
            ok(rep.repeatBatchedMeasured, "给了合批重复向量 -> repeatBatchedMeasured 为真")
            eqi(rep.repeatBatchedNotIdentical, 0, "合批重复逐位相同 -> 0 条不同")
            ok(rep.repeatBatchedAllIdentical, "合批重复全绿 -> repeatBatchedAllIdentical 为真")
            val d = rep.describe()
            ok(d.contains("差异来自**批布局**"), "两条路各自都全绿时 describe 说「差异来自批布局」")
            ok(d.contains("合批重复") && d.contains("全绿"), "describe 里报出合批那半句（也是全绿）")
        }

        run {
            // 逐条全绿、**合批自己**复现不了自己 -> 一样先别谈批大小。
            val a = listOf(f(1f, 2f))
            val b = listOf(f(1f, 2f))
            val rep = EmbedParity.compare(a, b, dim = 2, aRepeat = a,
                aBatchedRepeat = listOf(f(1f, 2f + 0.5f)))
            ok(rep.repeatBatchedMeasured && !rep.repeatBatchedAllIdentical,
                "合批重复不是逐位 -> repeatBatchedAllIdentical 为假")
            eqi(rep.repeatBatchedNotIdentical, 1, "合批重复里不是逐位相同的条数 1")
            val d = rep.describe()
            ok(d.contains("合批自身不确定"), "合批自重复有差异时 describe 说「合批自身不确定」")
            ok(d.contains("先别谈批大小"), "那一档的结论同样是「先别谈批大小」")
            ok(!d.contains("差异来自**批布局**"),
                "合批自己有差异时**不能**说「差异来自批布局」（两个来源焊在一起了）")
        }

        run {
            // 逐条自己就有差异时，合批那格**先不解读**（它答不了"批布局是不是元凶"）。
            val a = listOf(f(1f, 2f))
            val b = listOf(f(1f, 2f))
            val rep = EmbedParity.compare(a, b, dim = 2,
                aRepeat = listOf(f(1f, 2f + 0.5f)), aBatchedRepeat = b)
            val d = rep.describe()
            ok(d.contains("先别谈批大小"), "逐条自重复有差异 -> 先别谈批大小")
            ok(!d.contains("合批那条路自己重复"), "逐条那格已经否掉了前提，不再解读合批那格")
        }

        run {
            // 明细要能**分开**三组：跨路 / 同路重复·逐条 / 同路重复·合批 ——
            // 都叫"第 3 条：DIFFERENT" 就分不出是**哪条路**的根因。
            val a = listOf(f(1f), f(2f))
            val b = listOf(f(1f), f(9f))
            val rep = EmbedParity.compare(a, b, dim = 1,
                aRepeat = listOf(f(1f), f(2f + 0.25f)),
                aBatchedRepeat = listOf(f(1f), f(9f + 0.25f)))
            val joined = rep.detailLines().joinToString("\n")
            ok(joined.contains("（跨路）"), "明细里跨路那组带「跨路」标签")
            ok(joined.contains("（同路重复·逐条）"), "明细里逐条重复那组带「同路重复·逐条」标签")
            ok(joined.contains("（同路重复·合批）"), "明细里合批重复那组带「同路重复·合批」标签")
        }

        run {
            // 同路重复全绿时**不列**（全绿是预期，列出来会把真正的结论淹掉）。
            val a = listOf(f(1f, 2f))
            val b = listOf(f(1f, 2f))
            val rep = EmbedParity.compare(a, b, dim = 2, aRepeat = a)
            ok(rep.detailLines().isEmpty(), "跨路与同路都全绿 -> 明细为空")
        }

        // ── 样本集：固定样本必须覆盖"长短悬殊 + 超长 + 多语言"这几类边界 ──
        // 只取几条长度相近的普通句子时，两条路很可能本来就相同 ——
        // 那样"验过了"其实什么都没验到（见 EmbedParityProbe 对象头）。
        run {
            val t = EmbedParityProbe.TEXTS
            // 条数与批大小对齐（4 条 = 半个默认批，走两次 embedBatch）：
            // 见 EmbedParityProbe 对象头"为什么两组各 4 条"。
            eqi(t.size, 4, "长短悬殊样本 4 条（与批大小对齐）")
            ok(t.any { it.length <= 4 }, "样本含极短条目（固定开销最突出）")
            ok(t.any { it.length >= 1000 }, "样本含超长条目（要走到截断那一支）")
            ok(t.any { it.any { c -> c.code > 0x2E80 } }, "样本含 CJK")
            ok(t.any { it.contains("\n") }, "样本含多行代码（空白/缩进最容易岔开）")
            ok(t.none { it.isEmpty() } , "样本里没有空串（空串在两条路上都是失败，验不到东西）")
        }

        // ── 等长对照集（SAME_LEN）：这一组的全部价值是"长度都一样" ─────────
        // 它与 TEXTS 的差别只剩一个变量（长度齐不齐），于是"批布局"与
        // "不等长的 padding/切分"这两个来源才分得开（见 SAME_LEN 对象头）。
        run {
            val s2 = EmbedParityProbe.SAME_LEN
            eqi(s2.size, 4, "等长对照样本 4 条（与批大小对齐）")
            ok(s2.none { it.isEmpty() }, "等长对照样本里没有空串")
            ok(s2.toSet().size == s2.size, "等长对照样本内容各不相同（重复同一句时两条路多半也相同，验不到东西）")
            // "等长"是这一组的**唯一**变量：长度必须真的一样。
            // ⚠ 比的是**字符数**（这一层不碰 tokenizer）：它不等于 token 数，
            // 但"字符数都一样"至少排除了"长短悬殊"这个来源 —— 而那正是它的用途。
            ok(s2.map { it.length }.toSet().size == 1,
                "等长对照样本的字符数全部相同（实际 ${s2.map { it.length }.toSet().sorted()}）")
        }

        // ── 两组样本的条数都要**与批大小对齐**（4 | 8）─────────────────────
        // 不对齐时（如 6 条 / 批 8）批里还剩空位，条数一变就去不了同样的图 ——
        // 而本对照的结论是**跨版本**要对的那一条，多出"图不一样"这个变量时
        // "上次这样、这次那样"就分不清是环境变了还是样本变了。
        run {
            // ⚠ 读 EmbedLimits（**不是** LlmEngine）：这一份单测只编
            //   `EmbedParity.kt` + `EmbedParityProbe.kt` + `EmbedLimits.kt` + 本文件
            //   这几个纯逻辑文件，引用 LlmEngine 会**编不过**（它依赖 Android）。
            //   原先这里写的正是 `LlmEngine.kEmbedBatchDefault` —— 于是这份单测
            //   从来没跑成过，而它打印的却是"SKIP（缺工具链）"。
            //   见 `tools/run_kt_test_runner_guard.py` 的文件头。
            val k = EmbedLimits.kEmbedBatchDefault
            ok(k > 0, "批大小取得到（样本条数按它对齐）")
            for ((name, n) in listOf("TEXTS" to EmbedParityProbe.TEXTS.size,
                                     "SAME_LEN" to EmbedParityProbe.SAME_LEN.size,
                                     "RETRIEVE_TEXTS" to EmbedParityProbe.RETRIEVE_TEXTS.size)) {
                ok(k % n == 0 || n % k == 0,
                    "$name 的条数 $n 与批大小 $k 对齐（整除关系）")
            }
        }

        // ── 检索对照的语料（RETRIEVE_TEXTS）：条数必须**远大于 topK** ───────
        // 这一组的全部价值是"Top-K 真的有选择的余地"。语料只比 topK 多一点时
        // （4 条 vs topK=3），任何查询的 Top-3 都是"4 条里去掉 1 条"，
        // 命中集合几乎恒等 —— 那一格会**永远**报"没换答案"，而它不是答案稳，
        // 是样本被判据压住了（真机那轮读数就是被这一处压住的）。
        run {
            val rt = EmbedParityProbe.RETRIEVE_TEXTS
            val topk = EmbedLimits.kRetrieveParityTopK
            ok(topk > 0, "topK 取得到（语料条数按它判）")
            ok(rt.size >= 2 * topk,
                "检索对照语料 ${rt.size} 条 >= topK($topk) 的两倍（否则 Top-K 没有选择余地）")
            ok(rt.none { it.isEmpty() }, "检索对照语料里没有空串")
            ok(rt.toSet().size == rt.size, "检索对照语料内容各不相同")
            // 长短悬殊：既要有能走到截断的超长条，也要有短条 —— 这是"批布局
            // 改变向量"的那个来源，语料里必须有它。
            ok(rt.map { it.length }.max() > rt.map { it.length }.min() * 4,
                "检索对照语料长短悬殊（最长 > 最短的 4 倍）")
            // 查询必须取自这份语料本身（拿库外的句子来查，两侧都搜不到 ->
            // "都空"被读成"答案没变"）。
            val qs = EmbedParityProbe.RETRIEVE_TEXTS_QUERIES
            ok(qs.isNotEmpty(), "检索对照语料的查询集非空")
            ok(qs.all { it in rt }, "检索对照的查询全部取自 RETRIEVE_TEXTS（不在库外）")
        }

        println()
        println("=== EmbedParity 单测：PASS $pass / FAIL $fail ===")
        if (fail > 0) throw RuntimeException("$fail 条失败")
    }
}
