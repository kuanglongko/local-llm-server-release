package com.xiaowan.localinference

/**
 * `RagInject`（检索结果的**选择 / 渲染 / 拼接 / 开关**）的离线单测。
 *
 * 钉的是"注入"这一层最容易静默出错的东西 —— 它们全都不崩、不报错：
 *   · 开关关着却仍注入 -> 用户没要求却被污染上下文；
 *   · 低分条目硬塞进去 -> 用无关段落污染回答（比"没命中"更坏）；
 *   · 拼进 user 段 -> 模型当成"用户在问这个"，答非所问而 prompt 语法合法；
 *   · 空注入却返回 `[知识库]` 表头 -> 让守卫的"非空即已注入"判据恒真；
 *   · 全部低于阈值时返回最像的几条 -> 「没有命中」被伪装成「命中了」。
 */
object RagInjectTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    private fun eqStr(a: String?, b: String?, what: String) {
        if (a == b) { pass++; println("PASS  $what") }
        else { fail++; println("FAIL  $what\n     got=[$a]\n     want=[$b]") }
    }

    private fun eqi(a: Int, b: Int, what: String) {
        if (a == b) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what got=$a want=$b") }
    }

    private fun p(i: Int, s: Float) = RagInject.Pick(i, s)

    // ── select：阈值与条数 ─────────────────────────────────────────────────
    private fun testSelect() {
        val sorted = listOf(p(0, 0.9f), p(1, 0.6f), p(2, 0.4f), p(3, 0.1f))
        val r = RagInject.select(sorted, 3, 0.35f)
        eqi(r.size, 3, "select：阈值 0.35 挡住 0.1，条数上限 3 未触发")
        eqi(r[0].index, 0, "select：保持降序（第一条是最高分）")

        // 「全都不够格」必须返回空，**不是**"退而求其次取最高分那几条"
        val low = RagInject.select(listOf(p(0, 0.2f), p(1, 0.1f)), 3, 0.35f)
        eqi(low.size, 0, "select：全部低于阈值 -> 空（不许强行取最像的）")

        eqi(RagInject.select(sorted, 0, 0.35f).size, 0, "select：maxItems=0 -> 空")
        eqi(RagInject.select(sorted, 3, 0f).size, 3, "select：minScore<=0 退化为不按分数过滤")
        eqi(RagInject.select(listOf(p(-1, 0.9f)), 3, 0.35f).size, 0, "select：下标为负 -> 丢弃")
        eqi(RagInject.select(listOf(p(0, Float.NaN)), 3, 0.35f).size, 0, "select：NaN 分数被阈值挡住")
        eqi(RagInject.select(emptyList(), 3, 0.35f).size, 0, "select：空输入 -> 空")
    }

    // ── render：格式与可判别性 ─────────────────────────────────────────────
    private fun testRender() {
        val picks = listOf(p(0, 0.9f), p(1, 0.8f))
        val rows = listOf("苹果富含维C", "香蕉富含钾")
        val s = RagInject.render(picks, rows)
        eqStr(
            s,
            "[知识库]\n苹果富含维C\n香蕉富含钾\n",
            "render：格式固定（行首标记 + 每条正文一行，无来源尾巴）"
        )
        ok(s.startsWith(RagInject.MARK), "render：以固定标记起头（可判别）")
        // 关键：每条**只有正文一行** —— 行尾不带 `（来源: …）`，模型没得抄。
        // 这是「① 去掉来源尾巴」的全部意义；带尾巴就会被抄进回答。
        ok(!s.contains("来源"), "render：不含来源修饰（模型没得抄）")

        // 关键：一条都没有时必须返回**空串**，而不是只留 `[知识库]` 表头。
        // 留表头会让"非空 = 已注入"这条判据恒真（本轮要守的正是这个）。
        eqStr(RagInject.render(emptyList(), rows), "", "render：无条目 -> 空串（不留表头）")
        eqStr(RagInject.render(listOf(p(5, 0.9f)), rows), "", "render：行号越界 -> 空串")
        eqStr(RagInject.render(listOf(p(0, 0.9f)), listOf("")), "", "render：正文为空 -> 空串")
        // 部分越界时，其余条照样渲染（不整份丢、不留空位）
        eqStr(
            RagInject.render(listOf(p(9, 0.9f), p(0, 0.8f)), rows),
            "[知识库]\n苹果富含维C\n",
            "render：部分越界时跳过那一条、其余照渲染"
        )

        // ── 行号对齐：`rows` 的长度 == 全库行数，下标就是行号 ──────────────
        // 这一族是用户报的「选中一条，但内容为空」的正面用例。旧调用方按**命中顺序
        // 压紧**地造数组（长度只有 picks.size），于是 render 里 `rows[p.index]`
        // 取不到 —— 命中靠后的行被整块丢掉，落在前几行时还会串到**别行**的正文。
        // 单测本身没错（它一直喂的是行号对齐的数组），错的是调用方；这里把
        // "调用方必须传全长数组"这件事从**契约**侧钉住（调用方那侧由守卫锚）。
        val five = listOf("第0行正文", "第1行正文", "第2行正文", "第3行正文", "第4行正文")

        // ① 单条命中且行号 > 0（**正是用户报的那一档**）：必须渲染出第 4 行的正文
        eqStr(
            RagInject.render(listOf(p(3, 0.9f)), five),
            "[知识库]\n第3行正文\n",
            "render：单条命中且行号>0 -> 取的是**该行**的正文（不是被跳过）"
        )
        // ② 多条命中且行号 ≠ 0..N-1：每一条都必须取**它自己那一行**，不许错位
        eqStr(
            RagInject.render(listOf(p(3, 0.9f), p(1, 0.8f), p(4, 0.7f)), five),
            "[知识库]\n第3行正文\n第1行正文\n第4行正文\n",
            "render：命中行号非 0..N-1 时，各自取对应行（不串行、不丢）"
        )
        // ③ 压紧数组的反例：命中 #3 却只给长度 1 的数组 -> 整块为空
        //   （这正是旧调用方的形态；钉住它是为了让"整块为空"这件事有据可依，
        //    而不是让 render 去猜一个不存在的对齐方式）
        eqStr(
            RagInject.render(listOf(p(3, 0.9f)), listOf("第3行正文")),
            "",
            "render：压紧数组（长度 < 行号）-> 越界跳过 -> 空串（调用方必须传全长）"
        )
        // ④ 对齐数组里的坏行留空位：那一条被跳过，但**下标不挪**
        eqStr(
            RagInject.render(listOf(p(2, 0.9f), p(3, 0.8f)), listOf("A", "B", "", "D")),
            "[知识库]\nD\n",
            "render：坏行留空位 -> 跳过该条、下标不挪（后面的行仍取对）"
        )
    }

    // ── attachBeforeLastUser：拼到最后一条 user 之前（第 4 步的位置）────────
    private fun testAttach() {
        val h = listOf("system" to "你是助手", "user" to "旧问题", "assistant" to "旧回答",
            "user" to "新问题")
        val kb = "[知识库]\nX\n"

        val out = RagInject.attachBeforeLastUser(h, kb)
        eqi(out.size, h.size, "attach：消息条数不变（不新增一条 user，角色序列一个字节不动）")
        eqStr(out[3].second, "[知识库]\nX\n\n新问题", "attach：块拼在本轮提问之前")

        // 注入块必须排在提问**之前**（块在前、问在后）
        ok(out[3].second.startsWith(RagInject.MARK), "attach：块以标记起头（可判别）")
        ok(out[3].second.indexOf(RagInject.MARK) < out[3].second.indexOf("新问题"),
            "attach：块排在提问之前（块 -> 问）")
        ok(out[3].second.contains("新问题"), "attach：本轮提问完整保留（不被替换）")

        // 分叉点必须在**最后一条 user**处：前三条消息与输入逐字相同。
        // 这是本步的全部意义 —— 公共前缀 = 之前每一轮的完整对话（旧写法把块放
        // system 段，分叉点在 pos 0，公共前缀只剩几个 token）。
        for (i in 0..2) {
            ok(out[i].first == h[i].first && out[i].second == h[i].second,
                "attach：第 $i 条（system/历史）逐字不动 -> KV 公共前缀能覆盖到本轮提问前")
        }
        eqStr(out[0].second, "你是助手", "attach：system 段不动（它正是要被复用的那段）")
        eqStr(out[1].second, "旧问题", "attach：历史里的 user 不动（只改最后一条）")
        ok(out[3].first == "user", "attach：改的还是最后一条（user）")
        ok(out.last().first == "user", "attach：注入后最后一条仍是 user（角色序列未变）")

        // 注入为空 -> 原列表原样返回（硬契约：空注入 = 一个字节都不动）
        ok(RagInject.attachBeforeLastUser(h, "") === h,
            "attach：注入为空 -> 原列表原样返回（零字节）")

        // 没有 user 消息（只有 system）时不许凭空造一条出来
        val sysOnly = listOf("system" to "你是助手")
        ok(RagInject.attachBeforeLastUser(sysOnly, kb) === sysOnly,
            "attach：没有 user 消息 -> 原样返回（不许凭空造一条，宁可这次不注入）")

        // 本轮提问为空时也不能留一个前导空行
        val blank = listOf("system" to "s", "user" to "")
        eqStr(RagInject.attachBeforeLastUser(blank, kb)[1].second, "[知识库]\nX",
            "attach：本轮提问为空 -> 只放块、不留前导空行")
    }

    // ── shouldSearch：唯一的开关判据点 ─────────────────────────────────────
    private fun testShouldSearch() {
        ok(RagInject.shouldSearch(true, "kb", "问题"), "shouldSearch：三者齐备 -> 检索")
        ok(!RagInject.shouldSearch(false, "kb", "问题"), "shouldSearch：开关关 -> 不检索（关着必须零注入）")
        ok(!RagInject.shouldSearch(true, "", "问题"), "shouldSearch：没选库 -> 不检索")
        ok(!RagInject.shouldSearch(true, null, "问题"), "shouldSearch：库名为 null -> 不检索")
        ok(!RagInject.shouldSearch(true, "kb", ""), "shouldSearch：空查询 -> 不检索")
        ok(!RagInject.shouldSearch(true, "kb", "   "), "shouldSearch：空白查询 -> 不检索")
        ok(!RagInject.shouldSearch(true, "  ", "问题"), "shouldSearch：空白库名 -> 不检索")
    }

    // ── stripPrevious：避免"知识库内容被当成用户设定"两份并列 ──────────────
    private fun testStrip() {
        val sys = "你是助手"
        // ⚠ 第 4 步起注入不再落在 system 段，这里的输入改成手工构造的"被注入过的 system"
        //（用户自己把带标记的内容粘进 system 输入框）—— 剥离行为本身未变。
        val once = sys + "\n\n[知识库]\nA"
        eqStr(RagInject.stripPrevious(once), sys, "strip：剥掉上一次注入（回到用户原文）")
        eqStr(RagInject.stripPrevious(sys), sys, "strip：没有注入时原样返回")
        val two = once + "\n\n[知识库]\nB"
        eqStr(RagInject.stripPrevious(two), sys, "strip：连着注入两次也只留一份用户设定")
        // 中间出现的标记是用户正文的一部分，不动
        val mid = "前[知识库]中"
        eqStr(RagInject.stripPrevious(mid), mid, "strip：只剥末尾那一段（中间的标记是用户正文）")
    }

    // ── 常量：与 C++ 侧的锚点一致（守卫里逐字段比）────────────────────────
    private fun testConstants() {
        eqStr(RagInject.MARK, "[知识库]", "常量：标记与 rag_index.h 的 render_injection 一致")
        ok(RagInject.DEFAULT_TOP_K in 1..10, "常量：默认条数是合理的小值（不挤占 ctx）")
        ok(RagInject.DEFAULT_MIN_SCORE > 0f && RagInject.DEFAULT_MIN_SCORE < 1f,
            "常量：默认阈值在 (0,1) 之间（不能是 0 = 无过滤）")
        // 两个 prefs 键必须不同，且与"开关"与"当前库"一一对应
        ok(RagInject.KEY_ENABLED != RagInject.KEY_CURRENT, "常量：开关键与当前库键分开")
    }

    fun run() {
        testSelect()
        testRender()
        testAttach()
        testShouldSearch()
        testStrip()
        testConstants()
        println()
        println("PASS $pass / FAIL $fail")
        if (fail > 0) System.exit(1)
    }
}

/**
 * 顶层入口。Kotlin 的 `object` 里的 `@JvmStatic fun main` 会编出
 * `RagInjectTestKt` 之外的名字（`RagInjectTest.main`），
 * 而本仓库所有 host 侧 Kotlin 测试都按 `<文件名>Kt` 调用 —— 用顶层 main 对齐。
 */
fun main() {
    RagInjectTest.run()
}
