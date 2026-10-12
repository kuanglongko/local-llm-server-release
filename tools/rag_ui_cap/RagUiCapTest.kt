package com.xiaowan.localinference

/**
 * `RagUiCap`（"一屏最多画多少"的**纯逻辑**）的离线单测。
 *
 * 钉的是这个对象存在的**全部意义**：把"几千条的一个库铺成一屏"这件事
 * 从"卡死/OOM"变成"少画一些、但说清楚少画了什么"。
 *
 * 它的失效形态全是**静默**的，而且方向恰好相反：
 *   · 截了**不说** -> 用户以为库就这么多，带着一份缺了 90% 的列表去找内容，
 *     找不到就重导（而导入是追加合并，条目翻倍）；
 *   · "没截"的路径也说一句 -> 每一次刷新都多一行无用的警告，用户学会无视它，
 *     真被截的那次也一样被无视（本仓库对警告噪音的一贯判据）；
 *   · 上限**比"没截"的边界搞错一格** -> 恰好等于上限时也截，白截；
 *   · 正文截断时**不说磁盘那份是完整的** -> 用户以为内容丢了，去重导。
 *
 * 都是纯函数，边界值直接断言，不需要任何工具链之外的东西。
 */
object RagUiCapTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    fun run(): Int {
        // ── 1) 没截就**一个字都不说** ────────────────────────────────────────
        ok(RagUiCap.moreLibsLabel(3, 3) == null, "库数没截 -> 不说话")
        ok(RagUiCap.rowsPageNote(0, 0) == null, "空库 -> 不说话（不该有分页提示）")
        ok(RagUiCap.rowsPageNote(1, 1) == null, "1 条 -> 不说话")
        ok(RagUiCap.rowsPageNote(RagUiCap.MAX_ROWS, RagUiCap.MAX_ROWS) == null,
            "恰好一页 -> 不说话（边界取闭，别白截也别白说）")
        ok(RagUiCap.rowsPageNote(RagUiCap.MAX_ROWS - 1, RagUiCap.MAX_ROWS - 1) == null,
            "上限下沿 -> 不说话")

        // ── 2) 截了就必须说话，且**数目对得上** ──────────────────────────────
        val m = RagUiCap.rowsPageNote(RagUiCap.MAX_ROWS, 7021)
        ok(m != null, "7021 条、一页装不下 -> 必须说话")
        ok(m!!.contains("${RagUiCap.MAX_ROWS}"), "说明里报出**这一页显示了**多少")
        ok(m.contains("${7021 - RagUiCap.MAX_ROWS}"), "说明里报出**还有**多少（差值，不是总数）")
        ok(m.contains("没少"), "说明里说清『数据一条都没少』——否则用户会去重导")
        // 多一条也该说话（差一的边界）
        ok(RagUiCap.rowsPageNote(RagUiCap.MAX_ROWS, RagUiCap.MAX_ROWS + 1) != null,
            "比一页多一条 -> 说话（差一的边界不许漏）")

        // ── 3) 库列表的说明**不能**与条数的说明混用（单位不同） ────────────────
        val ml = RagUiCap.moreLibsLabel(RagUiCap.MAX_LIBS, RagUiCap.MAX_LIBS + 5)
        ok(ml != null && ml.contains("个库"), "库列表说明用『个库』作单位")
        ok(ml != null && ml.contains("5"), "库列表说明报出差值 5")

        // ── 4) 正文截断：**两种来源说两句相反的话**（这才是本轮的核心） ────────
        // 正文框被截的那条路有两个来源，而它们的下一步刚好相反：
        //   · loadRagDoc（磁盘上有完整副本）-> "用磁盘上那份"是走得通的；
        //   · applyRagTextImport（还没落盘）-> 磁盘上根本没有这份内容，
        //     说"磁盘上那份是完整的"是一句**假话**，而且它把人引到一条死路
        //     （用户照着点「用磁盘上那份建索引」，App 读的是**另一个库** ——
        //     用户报的原文就是这条）。所以两句话必须**分开**，且互不串门。
        val disk = RagUiCap.docTruncatedOnDiskNote(40_000, 3_000_000)
        ok(disk.contains("40000") || disk.contains("40,000"), "磁盘版报出实际装了多少字符")
        ok(disk.contains("3000000") || disk.contains("3,000,000"), "磁盘版报出全文共多少字符")
        ok(disk.contains("磁盘上那份是完整的"), "磁盘版说**磁盘上那份是完整的**（它确实在）")
        ok(disk.contains("用磁盘上那份"), "磁盘版给出可执行的下一步（用磁盘那份建索引）")

        val sole = RagUiCap.docTruncatedSoleCopyNote(40_000, 3_000_000)
        ok(sole.contains("40000") || sole.contains("40,000"), "唯一副本版报出实际装了多少字符")
        ok(sole.contains("3000000") || sole.contains("3,000,000"), "唯一副本版报出全文共多少字符")
        // ⚠ 这一条是判据的核心：**不许**说"磁盘上那份"
        ok(!sole.contains("磁盘上那份"),
            "唯一副本版**不许**说『磁盘上那份』（磁盘上根本没有 —— 那句是死路的误导源）")
        ok(sole.contains("还没有") && sole.contains("唯一"),
            "唯一副本版明说『还没有写进任何库 / 框里是唯一副本』")
        // 两句必须**真的不同**（同义反复地复制一份 = 等于没拆）
        ok(disk != sole, "两种来源的说明是两句**不同**的话（不是复制一份）")
        // 也不能拿"两个数不同"来蒙混：同一个输入下文案必须**各说各的真相** ——
        // 磁盘版不许说"还没有"（它有），唯一副本版不许说"磁盘上那份"（它没有）。
        ok(!disk.contains("还没有") && !disk.contains("唯一"),
            "磁盘版**不许**说『还没有 / 唯一副本』（磁盘上明明有 —— 反方向的一句假话）")
        ok(!sole.contains("用磁盘上那份") && !sole.contains("完整的"),
            "唯一副本版**不许**给出『用磁盘上那份』这条走不通的下一步")

        // ── 5) clipDoc：没超限**原样返回同一个对象**（不为大字符串白复制一份） ──
        val small = "abc"
        ok(RagUiCap.clipDoc(small) === small, "没超限 -> 原样返回同一个实例（不复制）")
        val exact = "x".repeat(RagUiCap.MAX_DOC_CHARS)
        ok(RagUiCap.clipDoc(exact) === exact, "恰好等于上限 -> 不截")
        val over = "x".repeat(RagUiCap.MAX_DOC_CHARS + 10)
        ok(RagUiCap.clipDoc(over).length == RagUiCap.MAX_DOC_CHARS, "超限 -> 截到上限长度")

        // ── 6) 截断**绝不切在代理对中间**（孤立代理落成 UTF-8 会变 `?`，不可逆） ──
        // 让一个 emoji（占两个 UTF-16 code unit）恰好骑在上限的切点上。
        val head = "a".repeat(RagUiCap.MAX_DOC_CHARS - 1)
        val s = head + "\uD83D\uDE00" + "tail"     // 高代理落在 MAX-1，低代理落在 MAX
        val c = RagUiCap.clipDoc(s)
        ok(c.length == RagUiCap.MAX_DOC_CHARS - 1,
            "切点落在代理对中间 -> 往前收一格，把整个码点留给后面（实际 ${c.length}）")
        ok(!c.last().isHighSurrogate() && !c.last().isLowSurrogate(),
            "截出来的尾部不是孤立代理（落盘不会变成 `?`）")

        // ── 7) 上限之间的关系：列表比库列表小得多（一行的 View 是库的三倍） ──
        ok(RagUiCap.MAX_ROWS < RagUiCap.MAX_LIBS,
            "条目上限 < 库上限（一行的 View 数是库的三倍，不该同档）")

        // ── 8) 分页：把「铺多少」与「能看到多少」拆开 ──────────────────────
        // 这一组存在的理由：降 [RagUiCap.MAX_ROWS] 只有在**分页同时存在**时
        // 才是安全的（否则降它 = 砍掉"能看到多少"）。所以"降上限"与"能翻页"
        // 必须被同一条判据绑在一起 —— 少了一样，另一条就是错的。
        ok(RagUiCap.rowPageCount(0) == 0, "空库 -> 0 页（不是 1 页：没有可翻的东西）")
        ok(RagUiCap.rowPageCount(1) == 1, "1 条 -> 1 页")
        ok(RagUiCap.rowPageCount(RagUiCap.MAX_ROWS) == 1, "恰好一页 -> 1 页（边界取闭）")
        ok(RagUiCap.rowPageCount(RagUiCap.MAX_ROWS + 1) == 2, "多一条 -> 2 页（差一不许漏）")
        ok(RagUiCap.rowPageCount(7021) == (7021 + RagUiCap.MAX_ROWS - 1) / RagUiCap.MAX_ROWS,
            "7021 条 -> 页数 = ceil(7021 / 页大小)")

        val r1 = RagUiCap.rowPageRange(7021, 1)
        ok(r1.first == 0 && r1.last == RagUiCap.MAX_ROWS - 1, "第 1 页 = [0, MAX_ROWS)")
        val r2 = RagUiCap.rowPageRange(7021, 2)
        ok(r2.first == RagUiCap.MAX_ROWS, "第 2 页从 MAX_ROWS 起（相邻页不重叠、不漏）")
        ok(r2.last + 1 == r2.first + RagUiCap.MAX_ROWS, "第 2 页也是满页")
        // 末页必须是**短**页，且最后一条就是总数-1 —— 不然最后几条永远摸不到
        val last = RagUiCap.rowPageCount(7021)
        ok(RagUiCap.rowPageRange(7021, last).last == 7020,
            "末页的最后一条 == 总数-1（否则最后几条永远看不到）")
        // 页与页之间**不留缝**：把所有页拼起来必须正好覆盖 [0, total)
        var covered = 0
        for (pg in 1..RagUiCap.rowPageCount(7021)) {
            val r = RagUiCap.rowPageRange(7021, pg)
            ok(r.first == covered, "第 $pg 页与上一页无缝（期望起点 $covered，实际 ${r.first}）")
            covered = r.last + 1
        }
        ok(covered == 7021, "所有页拼起来正好覆盖全部 7021 条（不多不少）")

        // 越界钳制：删条目会把当前页删空，那时**不许**给一张空列表
        // （空列表与"这个库空了"长得一样 —— 本仓库对这种静默变空一向判红）
        ok(RagUiCap.rowPageRange(7021, 0).first == 0, "页号 0 -> 钳到第 1 页")
        ok(RagUiCap.rowPageRange(7021, -5).first == 0, "页号负 -> 钳到第 1 页")
        ok(RagUiCap.rowPageRange(7021, 99999).last == 7020, "页号超末页 -> 钳到**末页**（不是空）")
        ok(RagUiCap.rowPageRange(0, 3).isEmpty(), "空库任何页都是空的（且不抛）")
        ok(RagUiCap.rowPageRange(10, 0).last == 9, "10 条、页号 0 -> 钳到第 1 页且能看到全部 10 条")

        // 页大小必须与分页用的是**同一个数**（两处各写一个必然漂移）
        ok(RagUiCap.ROWS_PER_PAGE == RagUiCap.MAX_ROWS, "ROWS_PER_PAGE 与 MAX_ROWS 同源")

        // 翻页文案：只有一页时必须**一个字都不说**（几十条的库是常态，那是噪音）
        ok(RagUiCap.pageLabel(0, 1) == null, "空库 -> 不说话")
        ok(RagUiCap.pageLabel(1, 1) == null, "1 条 -> 不说话")
        ok(RagUiCap.pageLabel(RagUiCap.MAX_ROWS, 1) == null, "恰好一页 -> 不说话（边界取闭）")
        val pl = RagUiCap.pageLabel(7021, 3)
        ok(pl != null, "多页 -> 必须说话")
        ok(pl!!.contains("3"), "文案里有当前页号")
        ok(pl.contains("/"), "文案里有『共几页』的分母")
        // 两个范围都要给：只看"第 3/118 页"不知道在文件的哪一段，
        // 只看"第 121-180 条"不知道还剩多少
        val r3 = RagUiCap.rowPageRange(7021, 3)
        ok(pl.contains("${r3.first + 1}") && pl.contains("${r3.last + 1}"),
            "文案里报出**这一页是哪几条**（第 ${r3.first + 1}–${r3.last + 1} 条）")

        // ── 8b) 分页说明的措辞：必须说"翻得到"，不能说成"看不见" ─────────────
        // 上一轮那一句"只显示了**前** N 条"在分页之后是**错的**（显示的可能是
        // 第 3 页的第 121–180 条），而"前"这个字会把用户引到"继续往下滑" ——
        // 列表到底了就是到底了，剩下的在别的页上。
        // 分页说明必须给出**可执行的动作** —— 只说"还有 6961 条未显示"会读成
        // "看不见了"，而真相是"翻页就能看到"。句子要带上「下一页」。
        ok(RagUiCap.rowsPageNote(RagUiCap.MAX_ROWS, 7021)!!.contains("下一页"),
            "说明里给出**可执行的动作**（用「下一页」翻）——不是说『看不见』")
        // 词是"这一页"，不是"前 N 条"：前者说的是"翻得到"，后者说的是"后面摸不着"。
        ok(RagUiCap.rowsPageNote(RagUiCap.MAX_ROWS, 7021)!!.contains("这一页"),
            "措辞是『这一页』（翻得到），不是『前 N 条』（后面摸不着）")

        // ── 9) 正文框与**一页条目**咬合（不是两个随手定的数） ────────────────
        // 以前 MAX_DOC_CHARS(40万) 与 MAX_ROWS(300) 之间没有任何关系 ——
        // 两个数各自"看着合理"，加起来却是"框里能放下 888 条、列表只显示 60 条"。
        // 这一条要求"一页的条目全进正文框"，因为"看列表找一条"与"在框里改它"
        // 必须落在同一份数据上（错位的删除不崩不报错 —— 本仓库的老缺陷）。
        ok(RagUiCap.docWideEnoughForRows(),
            "正文框装得下一页条目（MAX_DOC_CHARS >= 一页行数 × 单条字数）")
        ok(RagUiCap.docCharsForRows(RagUiCap.ROWS_PER_PAGE) <= RagUiCap.MAX_DOC_CHARS,
            "同一条关系用另一种写法再钉一次（防止 docWideEnoughForRows 自己写错）")
        // 降上限之后这一条**必须还在**：4 万 < 300×450 = 13.5 万 时它会红，
        // 而那正是"降了 MAX_ROWS 却忘了降 MAX_DOC_CHARS"（或反过来）的现场。
        ok(RagUiCap.MAX_DOC_CHARS < 400_000, "正文框上限已从 40 万降下来（本轮的目的之一）")
        ok(RagUiCap.MAX_ROWS < 300, "条目上限已从 300 降下来（本轮的目的之一）")

        // ── 10) 正文框的收起 / 展开：改的必须是**显示**，不是内容 ────────────
        // 这一组存在的理由只有一个：`maxLines`（对的）与 `setText("")`（错的）
        // 在界面上**前两秒完全一样**，而后者会让 `ragDocEntries()` 读出一份空正文
        // —— 建库照跑、索引照建，只是建出来的是空的。UI 侧那条判据钉的是
        // 「碰了什么」（见 run_rag_doc_fold_guard.sh），这里钉的是
        // 「文案里必须有的那几个数」：收起之后屏幕上剩下的信息就只有按钮这一行，
        // 它少一个数，「收起」与「内容丢了」就分不开。
        val foldOut = RagUiCap.docToggleLabel(collapsed = false, chars = 12345, clipped = false)
        ok(foldOut.contains("收起"), "展开态按钮说的是「收起」（点它会收）")
        ok(foldOut.contains("12345"), "按钮上报出**框里现在装着多少字符**（收起与丢了靠它分开）")
        val foldIn = RagUiCap.docToggleLabel(collapsed = true, chars = 12345, clipped = false)
        ok(foldIn.contains("展开"), "收起态按钮说的是「展开」（点它会展开）")
        ok(foldIn.contains("12345"), "收起态**照旧**报出字符数（收起不许把数藏起来）")
        ok(foldIn != foldOut, "两个状态的按钮文案不同（否则点了像没反应）")
        // 被截过时必须**在按钮上说**：那句「只是前一部分」原本写在正文**末尾**，
        // 收起之后正好看不到 —— 而它恰恰最不能靠翻找才发现。
        val foldClip = RagUiCap.docToggleLabel(collapsed = true, chars = 40_000, clipped = true)
        ok(foldClip.contains("前一部分"), "被截过时按钮上带旗标（说明在正文末尾，收起就看不见了）")
        ok(!RagUiCap.docToggleLabel(true, 40_000, false).contains("前一部分"),
            "没被截时**不带**那个旗标（否则是假警告，真被截那次也没人信）")
        ok(RagUiCap.docToggleLabel(true, 0, false).contains("空"),
            "框是空的时候说「空」，不是「0 字符」（0 是个数，空是「没有内容」）")

        // 收起态的说明行：空框**一个字都不说**（没有内容可收，说了是噪音）
        ok(RagUiCap.docCollapsedNote(0, false) == null, "空框收起 -> 不说话（没有内容可收）")
        val noteIn = RagUiCap.docCollapsedNote(12_000, false)
        ok(noteIn != null, "有内容且收起 -> 必须说话")
        ok(noteIn!!.contains("没有") && noteIn.contains("改动"),
            "说明里说清「内容没有被改动」——否则用户会去重导（条目翻倍）")
        ok(RagUiCap.docCollapsedNote(12_000, true)!!.contains("前一部分"),
            "被截过时说明里也带上（两条路都不许把这件事藏起来）")
        // 收起行数必须是「几行」的量级：0 等于把框藏起来（与「内容没了」一样），
        // 几十行等于没改（那一屏还是十几屏高）。
        ok(RagUiCap.DOC_COLLAPSED_LINES in 1..8,
            "收起行数是个「几行」的量级（不是 0 = 藏起来，也不是几十行 = 没改）")

        // ── 13) 删除一条之后的**本地推算**（P3：删一条不重读盘） ──────────────
        // 这一条省的正是"删完再读一遍 6 MB texts.jsonl"那次开销。而它的失效形态
        // 全静默：算出来一份**行号错位**的表 —— 屏幕上标号照常、点「删」删的是
        // 隔壁那一条（本仓库那个"删错行"的老缺陷）。所以逐条钉死。
        //
        // 基准：3 条（下标即行号）。
        val three = listOf(0 to "a", 1 to "b", 2 to "c")
        val delMid = RagUiCap.dropRowAt(three, 1)
        ok(delMid != null, "删中间那条 -> 算得出来（不返回 null）")
        ok(delMid == listOf(0 to "a", 1 to "c"),
            "删中间 -> 剩余两条按**新位置**重编行号（0,1），不是留着 0,2 那个洞")
        ok(RagUiCap.dropRowAt(three, 0) == listOf(0 to "b", 1 to "c"), "删第一条 -> 其余各前移一位")
        ok(RagUiCap.dropRowAt(three, 2) == listOf(0 to "a", 1 to "b"), "删最后一条 -> 前面不动")
        // 关键：**不是留一个洞**（留洞会让后面每一行都指向**上一条**）
        val delFirst = RagUiCap.dropRowAt(three, 0)
        ok(delFirst!!.map { it.first } == listOf(0, 1),
            "结果是稠密的（行号连续 0,1，不是留洞）")

        // ── 行号错位的表 -> 一律不算（退回重读） ─────────────────────────────
        // 这两条是"删错隔壁那一行"的正面判据：一份来路不明的表**不许**按下标
        // 推算行号 —— 而错行号的症状是"删掉的是别的那条"，屏幕上一切正常。
        val shifted = listOf(1 to "a", 2 to "b", 3 to "c")   // 行号整体偏 1
        ok(RagUiCap.dropRowAt(shifted, 1) == null, "行号整体错位 -> 不算（返回 null）")
        val gapped = listOf(0 to "a", 2 to "b", 3 to "c")    // 中间缺一格
        ok(RagUiCap.dropRowAt(gapped, 2) == null, "行号有缺 -> 不算（返回 null）")
        // 边界：越界/空表
        ok(RagUiCap.dropRowAt(emptyList(), 0) == null, "空表 -> 不算")
        ok(RagUiCap.dropRowAt(three, -1) == null, "负下标 -> 不算")
        ok(RagUiCap.dropRowAt(three, 3) == null, "下标越界 -> 不算")
        // 只剩一条时删掉 = 空表（合法结果，不是 null）——"库空了"与"算不出来"
        // 必须可分：前者要画出空态说明，后者要退回重读。
        ok(RagUiCap.dropRowAt(listOf(0 to "only"), 0) == emptyList<Pair<Int, String?>>(),
            "只剩一条 -> 算出空表（是结果，不是 null）")
        // 坏行占位（null）也要跟着前移，占位是**这一行读不出来**，不是"不存在"
        val withNull = listOf<Pair<Int, String?>>(0 to "a", 1 to null, 2 to "c")
        ok(RagUiCap.dropRowAt(withNull, 0) == listOf(0 to null, 1 to "c"),
            "坏行占位跟着前移（占位就是一行，它的行号也要对）")

        // ── 14) P1-a：正文框的**只读预览**（一页，不是整库）────────────────
        // 这一档的失效形态是"预览与列表说的不是同一段"：列表翻到第 3 页、
        // 框里还停在第 1 页，于是用户按框里看到的内容去点「删」/「追加」，
        // 改动落在一段他以为不是那里的行上 —— 不崩、不报错。
        // 所以判据钉的是"预览区间 == 条目列表区间"（同一条 rowPageRange）。

        // 预览区间必须与列表**逐字同源**：同一个函数、同一页、同一段。
        for (total in listOf(0, 1, 60, 61, 137, 7021)) {
            for (pg in listOf(1, 2, 3, 100)) {
                ok(RagUiCap.docPreviewRange(total, pg) == RagUiCap.rowPageRange(total, pg),
                    "预览区间与条目列表区间同源（total=$total page=$pg）")
            }
        }
        // 一页最多 60 行 —— 预览装的就是这一页，与 MAX_ROWS 咬合。
        // ⚠ 名字带 `pv` 前缀：本函数里上面已经有过两个 `r1` / `r3`（分页那一组），
        //   重名会让**整个套件编译不过** —— 而它报出来像"测试没过"，
        //   真相是一行断言都没跑（本仓库对"证据链断了"的一贯判据）。
        val pvR1 = RagUiCap.docPreviewRange(7021, 1)
        ok(pvR1.first == 0 && pvR1.last + 1 == RagUiCap.MAX_ROWS,
            "第 1 页预览的就是前 MAX_ROWS 条（P1-a：框装一页，不是整库）")
        val pvR3 = RagUiCap.docPreviewRange(7021, 3)
        ok(pvR3.first == 2 * RagUiCap.MAX_ROWS, "第 3 页从前两条页的交界开始（区间不重叠）")
        // 空库：没有区间（不画任何东西），也不抛异常。
        ok(RagUiCap.docPreviewRange(0, 1).isEmpty(), "空库 -> 空区间（不抛异常）")

        // 预览纯文本：**保留占位行**，且逐行对齐（行号即下标）。
        ok(RagUiCap.docPreviewText(emptyList()) == "", "空表 -> 空串")
        ok(RagUiCap.docPreviewText(listOf("a")) == "a", "一条 -> 一行")
        ok(RagUiCap.docPreviewText(listOf("a", "b")) == "a\nb", "两条 -> 换行分隔")
        // 关键：坏行（null）留一个**看得见的占位行**，不许被压紧 ——
        // 压紧了，预览第 3 行就不再是正文第 3 行，而"行号即下标"是全屏的口径。
        val pv = RagUiCap.docPreviewText(listOf("a", null, "c"))
        ok(pv.split("\n").size == 3, "坏行占一个占位行（不压紧 -> 行号仍对齐）")
        ok(pv.split("\n")[1].isNotEmpty(), "占位行不是空行（空行会被读成「这一条是空的」）")

        // 说明行：四件事必须同时说清 —— ①这是这一页 ②**能改** ③一行一条
        // ④改完怎么存（且"只写这一页"）。
        ok(RagUiCap.docEditableNote(0, 1) == null, "空库 -> 不说话（没有可编辑的内容）")
        val n1 = RagUiCap.docEditableNote(30, 1)
        ok(n1 != null && n1.contains("可编辑"), "有内容 -> 明说「可编辑」（不然用户以为框坏了）")
        ok(n1!!.contains("保存本页改动"),
            "说明里给出**保存的动作名**（不是那种只能猜的『本框不可编辑』）")
        ok(n1.contains("只写这一页"),
            "必须说清保存**只写这一页**（旧版是整份覆盖，两者长得像、后果相反）")
        val n2 = RagUiCap.docEditableNote(200, 2)
        ok(n2 != null && n2.contains("不是全部") || (n2 != null && n2.contains("第")),
            "多页时报出是哪一段（否则用户以为库就这一页这么多）")
        ok(n2!!.contains("2/"), "多页时报出当前是第几页（与列表翻页条说同一件事）")
        // 单页时**不报页号**（"第 1/1 页"是纯噪音，而噪音会让真多页时那行被无视）。
        val n1b = RagUiCap.docEditableNote(30, 1)
        ok(n1b != null && !n1b.contains("/"),
            "只有一页时不报页号（噪音会让真多页那行被无视）")

        // 两态：只有"新建、还没落盘"的库可编辑，其余一律只读预览。
        ok(RagUiCap.docIsSlice(false), "磁盘上有这个库 -> 框是它的一片")
        ok(!RagUiCap.docIsSlice(true), "新建、还没落盘 -> 框就是全部（磁盘上没有副本可预览）")

        // ══════════════════════════════════════════════════════════════════
        // 9) P1-b：片内编辑 → 按行偏移回写的**计划**（docEditPlan）
        // ══════════════════════════════════════════════════════════════════
        // 这一组的失效形态全是**静默**的：写错行 -> 改写的是别处的正文、
        // 增删行被当成"改字" -> 行号整体错位、空行被写下去 -> 行号即下标这条
        // 口径直接失效。全都不崩不报错，只有断言能钉住。
        //
        // ① 与 docPreviewText **逐字互逆**：没动过的框 -> 一行都没变（计划为空）。
        val rows3 = listOf("aa", "bb", "cc")
        val rendered = RagUiCap.docPreviewText(rows3)
        ok(RagUiCap.docEditLines(rendered) == rows3, "拆行与拼法互逆（没动过 -> 逐行相等）")
        ok(RagUiCap.docEditPlan(rows3, 0, rendered) == null,
            "一行都没改 -> 拒绝（不是编辑，不许产生写盘）")

        // ② 改其中一行：只把**那一行**写回，且行号 = first + i。
        val edited2 = RagUiCap.docPreviewText(listOf("aa", "BB", "cc"))
        val p2 = RagUiCap.docEditPlan(rows3, 10, edited2)
        ok(p2 != null, "改一行 -> 有计划")
        ok(p2!!.indices == listOf(11) && p2.texts == listOf("BB"),
            "只回写改过的那一行，且行号 = first + 下标（10 + 1 = 11）")
        ok(p2.changed == 1 && p2.sliceRows == 3, "计划报得出『改了几行 / 这一片几行』")

        // 改多行：行号逐位对齐，且**相对顺序不变**。
        val edited3 = RagUiCap.docPreviewText(listOf("XX", "bb", "ZZ"))
        val p3 = RagUiCap.docEditPlan(rows3, 5, edited3)
        ok(p3!!.indices == listOf(5, 7) && p3.texts == listOf("XX", "ZZ"),
            "改两行 -> 行号与内容逐位对齐（5、7）")

        // ③ 行数变了 -> 一律拒绝（增行、删行都是）。本版只支持"改字"。
        ok(RagUiCap.docEditPlan(rows3, 0, "aa\nbb") == null, "删行 -> 拒绝")
        ok(RagUiCap.docEditPlan(rows3, 0, "aa\nbb\ncc\ndd") == null, "增行 -> 拒绝")
        ok(RagUiCap.docEditPlan(rows3, 0, "aa\nbb\n") == null,
            "末尾多一个回车 -> 行数变了 -> 拒绝（那个空串不许被静默吃掉）")
        ok(RagUiCap.docEditPlan(rows3, 0, "") == null, "清空 -> 行数变了 -> 拒绝")
        // 空白对齐：多一个空格也算改（它在正文里是一个字节，不是排版）。
        val p4 = RagUiCap.docEditPlan(rows3, 0, RagUiCap.docPreviewText(listOf("aa ", "bb", "cc")))
        ok(p4 != null && p4.texts == listOf("aa "), "行尾多一个空格算改动（正文逐字节）")

        // ④ 改到了一行"读不出来"的 -> 拒绝（那一行是我们编的占位话）。
        val rowsN = listOf<String?>("aa", null, "cc")
        val rendN = RagUiCap.docPreviewText(rowsN)
        ok(RagUiCap.docEditPlan(rowsN, 0, rendN) == null,
            "没动过（含坏行）-> 仍不算改动")
        ok(RagUiCap.docEditPlan(rowsN, 0,
                RagUiCap.docPreviewText(listOf("aa", "xx", "cc"))) == null,
            "把『读不出来』那一行改成别的内容 -> 拒绝（不许拿编的话盖磁盘原字节）")
        ok(RagUiCap.docEditPlan(rowsN, 0,
                RagUiCap.docPreviewText(listOf("AA", null, "cc")))!!.indices == listOf(0),
            "坏行原样保留、改别的行 -> 有计划，且坏行不进计划")

        // ⑤ 一行改成**空** -> 拒绝（空行会让『行号即下标』失效，删它请用「删」）。
        ok(RagUiCap.docEditPlan(rows3, 0,
                RagUiCap.docPreviewText(listOf("aa", "", "cc"))) == null,
            "把一行改成空 -> 拒绝（要删那一条请用列表里的「删」）")

        // ⑥ 边界：空行表 / 负起点 -> 拒绝（不抛异常）。
        ok(RagUiCap.docEditPlan(emptyList(), 0, "x") == null, "空的这一片 -> 拒绝")
        ok(RagUiCap.docEditPlan(rows3, -1, edited2) == null, "负的行号起点 -> 拒绝")

        // ⑦ 保存之后那句话必须点出"改了几行 / 写进了哪个库 / 其余没动"。
        val note = RagUiCap.docEditSavedNote("库A", p3!!)
        ok(note != null && note.contains("库A"), "保存回执报出**写进了哪个库**")
        ok(note!!.contains("2"), "保存回执报出**改了几行**")
        ok(note.contains("其余"), "保存回执说清**其余行与其余页没动**")
        ok(note.contains("建索引/重建索引"),
            "保存回执给出下一步（改过的条目要重建才按新内容检索）")

        // ── ⑦b ISSUE #228 新特性：**重算了向量**那一路的回执 ──────────────
        // 它与 ⑦ 是**两句不同的话**，差别正是本轮新增的能力（无需重建 vs 需重建）。
        // 判据必须钉住"无需重建"这一句是**说出来的** —— 用户对"改字 = 要重建"
        // 有旧的习惯性预期，不说清他会去找重建按钮、或者干脆不敢改。
        val vnote = RagUiCap.docEditVectorNote("库A", p3!!)
        ok(vnote != null && vnote.contains("库A"), "重算回执报出**写进了哪个库**")
        ok(vnote!!.contains("2"), "重算回执报出**改了几行**")
        ok(vnote.contains("其余"), "重算回执说清**其余行与其余页没动**")
        ok(vnote.contains("重算"), "重算回执点出**重算了这几条的向量**（不是只写正文）")
        ok(vnote.contains("无需重建"), "重算回执明说**无需重建**")
        // ⚠ 这一条防的是"两句回执被写成同一句"：把新路的话写成老路的（或反过来），
        //   用户据此判断"要不要去点重建"，而两句话的结论**相反**。
        ok(vnote != note, "重算回执与『待重建』回执**不是同一句**（结论相反）")
        ok(RagUiCap.docEditVectorNote("库A", RagUiCap.DocEditPlan(emptyList(), emptyList(), 3)) == null,
            "没有改动 -> 重算回执也不该有这句话")

        println()
        println(if (fail == 0) "=== RagUiCapTest: PASS $pass / FAIL 0 ==="
                else "=== RagUiCapTest: PASS $pass / FAIL $fail ===")
        return if (fail == 0) 0 else 1
    }
}

fun main() {
    kotlin.system.exitProcess(RagUiCapTest.run())
}
