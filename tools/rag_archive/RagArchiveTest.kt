package com.xiaowan.localinference

/**
 * `RagArchive` 的宿主侧行为测试。
 *
 * 为什么它不是"守卫的重复"：`run_rag_archive_guard.sh` 钉的是**结构没改回去**
 * （信封不带向量 / 格式名判据在读 entries 之前 / 坏行计数 / 删索引在写正文之前），
 * 它证明不了"这套结构在**边界输入**下真的对"。而本特性的边界恰恰是全部难点：
 *
 *   · **三种形状**（信封 / 裸 JSON 数组 / 裸 JSONL）都要能吃，且按**内容**判而不是
 *     按扩展名 —— SAF 上 .json 常被报成 text/plain，按扩展名判会让"明明在却选不中"；
 *   · 别的 App 的备份文件（有 format 但格式名不对、或顶层是 sessions/data）
 *     必须**拒绝**。若"尽力解析"，用户会看到"导入 0 条"并读成"我的笔记是空的"；
 *   · 坏行（缺 t / 缺 s / 空 t）跳过**并计数**，末尾空行**不算**坏行（否则每次都报假警）；
 *   · 超限**整份拒绝**而不是截断（静默丢掉后半份笔记不可恢复）；
 *   · 往返一致：encode → decode 逐条相同（"备份"本身不可信的话这功能没有意义）；
 *   · **信封里出现 vector/dim 字段也要能吃**（用户手改过 / 未来版本加的）——
 *     忽略它，而不是把它带进 Entry（带上就是"用旧向量命中新正文"那条静默错）。
 *
 * 只依赖 org.json 与 `RagIndexStore.Entry`（纯 data class，无 Context）。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private fun e(t: String, s: String = "") = RagIndexStore.Entry(t, s)

fun main() {
    // ══════════════════════════════════════════════════════════════════════
    //  0) 超长正文**切成多段**（ISSUE #259 的根因之一）
    // ══════════════════════════════════════════════════════════════════════
    //
    // 这一族的坏法是**静默**的：一条几万字的正文在库里是"一条"，建库时被
    // native 按嵌入模型的 n_ctx（默认 512）截到前 511 个 token —— 那之后的
    // 每一个关键词都检索不到，而建库成功、列表条目数也对、日志里只有一行
    // `[embed] 文本 N tok 超过 ctx`。所以判据不是"有没有切"，而是：
    // **每一段都装得进 ctx、且拼起来还是原文**（不丢字、不重字）。
    run {
        // ① 短行**不动**（绝大多数笔记都是短行，切了反而让条目数虚涨）
        val short = "象牙村小学老师的名字叫谢永强。"
        ck("切段：短行原样返回（不切成多条）",
            RagArchive.splitLongLine(short) == listOf(short))

        // ② 恰好等于上限也不切（边界：`<=` 不是 `<`）
        val exact = "甲".repeat(RagArchive.EMBED_MAX_CHARS)
        ck("切段：恰好等于上限时不切", RagArchive.splitLongLine(exact).size == 1)

        // ③ 超长 + 有句读 -> 按句读切，且**内容一字不丢**
        val long = "这是一句话。".repeat(500)          // 3000 字，每 6 字一个句号
        val segs = RagArchive.splitLongLine(long)
        ck("切段：超长行切成多段", segs.size > 1)
        ck("切段：每一段都不超过上限",
            segs.all { it.length <= RagArchive.EMBED_MAX_CHARS })
        ck("切段：拼起来仍是原文（不丢字、不重字）", segs.joinToString("") == long)
        ck("切段：段边界落在句读之后（不是切在句子中间）",
            segs.dropLast(1).all { it.endsWith("。") })

        // ④ **一个标点都没有**的长串（Base64 / 无标点外文）—— 必须硬切，
        //    而且要**出声**（onHardCut 计数）。不出声的硬切与"正常切段"
        //    在界面上逐字相同（条目数、条数全对），而它的检索粒度天生更差。
        val noPunct = "A".repeat(2000)
        var hard = 0
        val segs2 = RagArchive.splitLongLine(noPunct) { hard++ }
        ck("切段：无标点的长串也会被切（不是收成一条巨大条目）", segs2.size > 1)
        ck("切段：无标点的长串切完不丢字", segs2.joinToString("") == noPunct)
        // 计数是"硬切发生了几次"，不是"切出了几段"：
        // 2000 个 A / 448 上限 = 5 段，其中前 4 段都是硬切（末段走到文末，不算硬切）。
        // ⚠ 写成 `hard == segs2.size` 会**恒假**（差 1）—— 那种恒假断言很快会被
        //    当成"这条一直红"而删掉，所以这里把两个数都写出来对账。
        ck("切段：硬切有计数（用户在回执里看得见）", hard == segs2.size - 1 && hard > 0)

        // ⑤ 代理对不许被切开：切在代理对中间会留下**孤立代理**，
        //    内存里 `==` 比较看着是对的，落成 UTF-8 时变成 `?` 且不可逆。
        val emoji = "😀".repeat(600)                    // 每个占 2 个 UTF-16 code unit
        val segs3 = RagArchive.splitLongLine(emoji)
        ck("切段：代理对没被切开（没有孤立代理）",
            segs3.none { it.isNotEmpty() && (it.last().isHighSurrogate() || it.first().isLowSurrogate()) })
        ck("切段：emoji 长串拼回来仍然完整", segs3.joinToString("") == emoji)

        // ⑥ 空串 / 纯空白：不产出空段（空段会在库里变成一条"读不出来的行"）
        ck("切段：空串不产出任何段", RagArchive.splitLongLine("").isEmpty())

        // ⑦ **对空白敏感**的语料（③④ 那两条一直是绿的，因为它们的语料每个分界符
        //    都是句号、句号后面没空白 —— 恰好绕开了这个形态）。
        //    这两条语料是**旧实现下真的会红**的形状（本机拿旧控制流实跑过：
        //    分别丢 4 字 / 丢 15 字），不是"看着像边界"：
        //      · `"word ".repeat(400)`：唯一分界符是空格 -> 旧实现走空白级，
        //        切在空格**之前**、段尾 `trim()` 再吃一次，那截空白两头都不属于；
        //      · 缩进代码：每行前 4 个空格 + 句读级软标点，同一处夹缝反复出现。
        //    ⚠ 反例留在这儿（我一开始挑错了）：**缩进 JSON**（`"  "×20 + {...}`）
        //      在旧实现下**是绿的** —— 它整段没有软标点、又刚好没过 448，
        //      一条道走到硬切，压根碰不到空白级。挑语料不能靠"看着像空白敏感"，
        //      要拿旧实现实跑一遍：**能绿着通过的语料，钉不住任何东西**。
        //    判据就一条：**拼回去 == 原文**。它是这条路上唯一一条不需要解释的判据
        //    —— 丢了它，"切得对不对"就只能靠"我觉得切得挺合理"。
        //    ⚠ `splitLongLine` 的契约是"行已 trim 过"（decodePlainText 那一步），
        //      所以这里送入的语料先 `.trim()`，比的是**内部**空白一字不少。
        val spaced = "word ".repeat(400).trim()
        val segs4 = RagArchive.splitLongLine(spaced)
        ck("切段：以空格为唯一分界符的长文，拼回来一字不少（旧实现丢 4 字）",
            segs4.joinToString("") == spaced)
        val indentedCode = ("    int a = 1;\n").repeat(100).trim()
        ck("切段：缩进代码拼回来一字不少（旧实现丢 15 字）",
            RagArchive.splitLongLine(indentedCode).joinToString("") == indentedCode)
        // 它上限 448、正文 1400 —— 会切，且切出来的每段都装得进 ctx。
        ck("切段：缩进代码切出的每段都不超过上限",
            RagArchive.splitLongLine(indentedCode).all { it.length <= RagArchive.EMBED_MAX_CHARS })
    }

    // ── 0b) 三种形状**都要**走切段（否则同一条正文在不同文件里条目数不同）──
    run {
        val long = "这是一句话。".repeat(500)
        // 信封
        val env = RagArchive.decode(
            RagArchive.encode("kb", listOf(e(long, "s")), 1L))
        ck("切段：信封形状也切（不是只有纯文本切）", env.ok && env.entries.size > 1)
        ck("切段：信封形状切完不丢字",
            env.entries.joinToString("") { it.text } == long)
        // 裸 JSONL（一行一条）
        val jsonl = org.json.JSONObject().put("t", long).put("s", "x").toString()
        val lines = RagArchive.decode(jsonl)
        ck("切段：裸 JSONL 形状也切", lines.ok && lines.entries.size > 1)
        ck("切段：两种形状切出的条数**相同**（同一条正文不该因外壳不同而变）",
            env.entries.size == lines.entries.size)
        ck("切段：切出来的每段都带上原条目的来源",
            lines.entries.all { it.source == "x" })
    }

    // ── 1) 往返一致（信封形状）──────────────────────────────────────────
    run {
        val src = listOf(
            e("尼古拉斯赵四是一位军事家政治家，全名叫赵玉田。", "粘贴-test"),
            e("多行\n内容\t带引号\"与反斜杠\\", ""),
            e("象牙村小学老师的名字叫谢永强。", "note.md"),
        )
        val json = RagArchive.encode("我的技术笔记", src, 123456L)
        val p = RagArchive.decode(json)
        ck("往返：decode 成功", p.ok)
        ck("往返：条数相同", p.entries.size == 3)
        ck("往返：无跳过", p.skipped == 0)
        ck("往返：库名保留", p.library == "我的技术笔记")
        ck("往返：正文逐条相同（含转义字符与空来源）",
            p.entries[0].text == src[0].text && p.entries[0].source == "粘贴-test" &&
                p.entries[1].text == "多行\n内容\t带引号\"与反斜杠\\" && p.entries[1].source == "" &&
                p.entries[2].text == src[2].text && p.entries[2].source == "note.md")
        ck("往返：行序原样保持", p.entries.map { it.text } == src.map { it.text })
    }

    // ── 2) 信封里**绝不**出现向量 / 指纹字段（本格式的设计约束）──────────
    run {
        val json = RagArchive.encode("k", listOf(e("a", "b")), 1L)
        ck("导出不含 vector", !json.contains("\"vector\""))
        ck("导出不含 dim", !json.contains("\"dim\""))
        ck("导出不含指纹字段", !json.contains("\"fp\"") && !json.contains("fingerprint"))
        // ⚠ 这一条此前断言的是 `"t":"a","s":"b"` —— 即"t 紧跟 s"。那是**恒假**的：
        // org.json 的 `JSONObject` 内部是 `HashMap`，`toString()` 的输出顺序
        // **不保证**是插入序（实测就是 `{"s":"b","t":"a"}`）。按相邻性去锚，
        // 锚的是实现细节而不是契约，且它从引入那天起就没绿过。
        // 真正要钉的契约是"**只有这两个键**"：键集相等，与顺序、与相邻性无关。
        // ⚠ 不用 import 走 org.json：本套件的**编译** classpath 里 android.jar
        // 排在真正的 json.jar **前面**，`import org.json.JSONObject` 会被解析成
        // android.jar 里那个抛 `Stub!` 的空壳。运行期 classpath 才把 json.jar
        // 提前（见 run_rag_archive_tests.sh 的两条注释）。全限定名绕开这个坑，
        // 且不影响运行期那份真实现 —— 同一条 java 进程里只会加载到真的那份。
        val entryKeys = org.json.JSONObject(json).getJSONArray("entries")
            .getJSONObject(0).keys().asSequence().toSet()
        ck("每条**只有** t 与 s 两个键（不看顺序 —— JSONObject 是 HashMap，输出序不保证）",
            entryKeys == setOf("t", "s"))
        val e0 = org.json.JSONObject(json).getJSONArray("entries").getJSONObject(0)
        ck("两个键的取值原样（不需要字符串扫描也能验）",
            e0.getString("t") == "a" && e0.getString("s") == "b")
    }

    // ── 2b) 但**读**的时候要能吃下这些字段（忽略，不带进 Entry）──────────
    run {
        val withVec = """{"format":"local-llm-rag","version":1,"library":"x","entries":[
            {"t":"甲","s":"","vector":[0.1,0.2],"dim":2}]}"""
        val p = RagArchive.decode(withVec)
        ck("带 vector 的文件仍能读（用户手改/未来版本）", p.ok && p.entries.size == 1)
        ck("带 vector 的文件：向量**不**进 Entry（带上就是静默错检索）",
            p.entries[0].vector.isEmpty())
    }

    // ── 3) 形状 ②：裸 JSON 数组 ─────────────────────────────────────────
    run {
        val bare = """[{"t":"第一条","s":"粘贴-test"},{"t":"第二条","s":""}]"""
        val p = RagArchive.decode(bare)
        ck("裸数组：能读", p.ok && p.entries.size == 2)
        ck("裸数组：库名为空（由调用方决定落到哪个库，不静默落默认库）", p.library == "")
        ck("裸数组：正文与来源都对", p.entries[0].text == "第一条" && p.entries[0].source == "粘贴-test")
        ck("裸数组：空来源也收（s 存在即合法）", p.entries[1].source == "" && p.entries[1].text == "第二条")
    }

    // ── 4) 形状 ③：裸 JSONL（用户从别处导出的那种）───────────────────────
    run {
        val jsonl = "{\"t\":\"尼古拉斯赵四是一位军事家政治家，全名叫赵玉田。\",\"s\":\"粘贴-test\"}\n" +
            "{\"t\":\"象牙村小学老师的名字叫谢永强。\",\"s\":\"粘贴-test\"}\n"
        val p = RagArchive.decode(jsonl)
        ck("JSONL：能读", p.ok && p.entries.size == 2)
        ck("JSONL：末尾换行不算坏行（不报假警）", p.skipped == 0)
        ck("JSONL：内容对", p.entries[1].text == "象牙村小学老师的名字叫谢永强。")
    }

    // ── 4b) JSONL 中间的空行跳过且不计数 ────────────────────────────────
    run {
        val jsonl = "{\"t\":\"A\",\"s\":\"\"}\n\n\n{\"t\":\"B\",\"s\":\"\"}\n"
        val p = RagArchive.decode(jsonl)
        ck("JSONL：空行不算坏行", p.ok && p.entries.size == 2 && p.skipped == 0)
    }

    // ── 4c) 单行 JSONL（连末尾换行都没有）也是合法形状 ──────────────────
    run {
        val one = RagArchive.decode("""{"t":"只有一行","s":"粘贴-test"}""")
        ck("单行 JSONL：能读（不是'这不是知识库文件'）", one.ok && one.entries.size == 1)
        ck("单行 JSONL：内容对", one.entries[0].text == "只有一行" && one.entries[0].source == "粘贴-test")
        // 单行但缺 s -> 仍是坏行（缺键 ≠ 空值），且必须**有明确错误文案**。
        //
        // ⚠ 这里此前断言的是 `skipped == 1`，也是**恒假**：单行文本走的是
        // `decodeObjectOrLines` 的"真·单行但不是条目"分支 —— 它调的是
        // `fail(msg)`，即 `skipped = 0`（跳过计数只有**逐行解析**那条路才有，
        // 那里每坏一行 +1）。所以"单行缺 s 要计数"这个期望本身就不成立。
        // 该钉的是"**不许静默**"：被拒 + 文案里指得出缺的是哪个键。
        val bad = RagArchive.decode("""{"t":"只有一行"}""")
        ck("单行 JSONL 缺 s：被拒（缺键 ≠ 空值）", !bad.ok)
        ck("单行 JSONL 缺 s：文案列了读到的顶层键（不静默、可归因）",
            bad.error?.contains("顶层键") == true && bad.error?.contains("t") == true)
    }

    // ── 5) 形状判据按**内容**，不按扩展名（同一份内容换个名字也一样）──────
    run {
        ck("以 [ 开头 -> 走数组路径",
            RagArchive.decode("[{\"t\":\"x\",\"s\":\"\"}]").ok)
        ck("以 { 开头但整体不是合法 JSON 对象 -> 走 JSONL",
            RagArchive.decode("{\"t\":\"x\",\"s\":\"\"}\n{\"t\":\"y\",\"s\":\"\"}").ok)
        ck("前置空白不影响形状判定",
            RagArchive.decode("  \n [{\"t\":\"x\",\"s\":\"\"}]").ok)
    }

    // ── 6) 必须拒绝的形状 ───────────────────────────────────────────────
    run {
        ck("空文件被拒绝", !RagArchive.decode("   \n ").ok)
        ck("非 JSON 被拒绝", !RagArchive.decode("这不是 json").ok)
        ck("以怪字符开头的被拒绝",
            RagArchive.decode("<html></html>").let { !it.ok && it.error?.contains("开头") == true })
        // 别的 App 的备份文件 —— 这是本条最重要的一处
        val other = RagArchive.decode("""{"format":"local-llm-chat","version":1,"sessions":[]}""")
        ck("别的 App 的备份文件被拒绝（不尽力解析）", !other.ok)
        ck("拒绝文案里带上**实际读到**的 format", other.error?.contains("local-llm-chat") == true)
        // 顶层既无 format 也无 entries
        val noEntries = RagArchive.decode("""{"version":1,"data":[{"t":"x"}]}""")
        ck("顶层无 format/entries 被拒绝", !noEntries.ok)
        ck("版本过高的信封被拒绝（不按已知字段硬解）",
            !RagArchive.decode("""{"format":"local-llm-rag","version":99,"entries":[]}""").ok)
        ck("entries 不是数组被拒绝",
            !RagArchive.decode("""{"format":"local-llm-rag","version":1,"entries":"x"}""").ok)
        ck("没有任何可用条目被拒绝（'导入 0 条'会被读成'库是空的'）",
            !RagArchive.decode("""{"format":"local-llm-rag","version":1,"entries":[]}""").ok)
    }

    // ── 7) 坏行：跳过并**计数**，不猜空值 ───────────────────────────────
    run {
        val json = """{"format":"local-llm-rag","version":1,"entries":[
            {"t":"好的一条","s":""},
            {"t":"缺 s 的一条"},
            {"s":"缺 t 的一条"},
            {"t":"","s":"空正文也算坏"},
            {"t":"另一条好的","s":"x"}]}"""
        val p = RagArchive.decode(json)
        ck("坏行：好条目仍收进来", p.ok && p.entries.size == 2)
        ck("坏行：计数为 3（不是静默丢）", p.skipped == 3)
        ck("坏行：好条目内容正确",
            p.entries[0].text == "好的一条" && p.entries[1].text == "另一条好的")
    }

    // ── 8) 超长：先**切段**（装得进 ctx），到硬顶才截断并计数 ─────────────
    //
    // ⚠ 这一段本轮改过口径。旧行为是"超过 10 万字就截断并计数"，而它有两个坑：
    //   · 一条 3 万字（在硬顶之内、所以**连计数都不报**）走到建库时被 native
    //     按嵌入模型的 n_ctx 截到前 511 个 token —— 那之后的关键词一个都检索
    //     不到，而建库成功、界面上一切正常；
    //   · 一条 3 MB 的整篇（没有换行）在库里是"1 条"，而"我的文件被切成了几条"
    //     这件事没有任何地方说得清。
    // 现在：超过 EMBED_MAX_CHARS 就切段（内容全在、每段都装得进 ctx），
    // 超过 MAX_TEXT_CHARS 硬顶才截（那一档是"防一份文件撑爆 UI 与 prompt"，仍计数）。
    run {
        // ① 超长但有句读 -> 切成多段，**一条不丢、一个字不丢**，且不计跳过
        val long = "这是一句话。".repeat(500)               // 3000 字
        val json = RagArchive.encode("k", listOf(e(long, "")), 1L)
        val p = RagArchive.decode(json)
        ck("超长：切成多段（不是收成一条巨大条目）", p.ok && p.entries.size > 1)
        ck("超长：切段**不算跳过**（内容一条没丢，报 skipped 会把用户引去查文件）",
            p.skipped == 0)
        ck("超长：每一段都装得进嵌入模型的 ctx",
            p.entries.all { it.text.length <= RagArchive.EMBED_MAX_CHARS })
        ck("超长：切完仍是原文", p.entries.joinToString("") { it.text } == long)
    }
    run {
        // ② 超过**硬顶**（MAX_TEXT_CHARS）才截断，且如实计数
        val huge = "x".repeat(RagArchive.MAX_TEXT_CHARS + 10)
        val json = RagArchive.encode("k", listOf(e(huge, "")), 1L)
        val p = RagArchive.decode(json)
        ck("硬顶：仍能导入", p.ok && p.entries.size >= 1)
        ck("硬顶：超出的部分被截掉（每段都不超过硬顶）",
            p.entries.all { it.text.length <= RagArchive.MAX_TEXT_CHARS })
        ck("硬顶：计数 +1（报给用户，不静默丢）", p.skipped == 1)
    }

    // ── 9) 超限：整份拒绝，不静默截断 ───────────────────────────────────
    run {
        val many = (0 until RagArchive.MAX_IMPORT_ENTRIES + 1)
            .joinToString(",", "[", "]") { "{\"t\":\"t$it\",\"s\":\"\"}" }
        val p = RagArchive.decode(many)
        ck("超限：整份拒绝", !p.ok)
        ck("超限：文案说明未导入任何内容", p.error?.contains("未导入任何内容") == true)
        ck("超限：没有产出任何条目", p.entries.isEmpty())
        val env = """{"format":"local-llm-rag","version":1,"entries":$many}"""
        ck("超限：信封路径也挡", !RagArchive.decode(env).ok)
    }

    // ── 10) 边界：刚好等于上限要能过 ────────────────────────────────────
    run {
        val just = (0 until RagArchive.MAX_IMPORT_ENTRIES)
            .joinToString(",", "[", "]") { "{\"t\":\"t$it\",\"s\":\"\"}" }
        val p = RagArchive.decode(just)
        ck("刚好等于上限：能导入（上限是 <= 不是 <）",
            p.ok && p.entries.size == RagArchive.MAX_IMPORT_ENTRIES)
    }

    // ── 11) s 为空串与 s 缺失是**两件事** ───────────────────────────────
    run {
        val okOne = RagArchive.decode("""[{"t":"甲","s":""}]""")
        val badOne = RagArchive.decode("""[{"t":"甲"}]""")
        ck("s 为空串 -> 合法", okOne.ok && okOne.entries[0].source == "")
        ck("s 缺失 -> 坏行（缺键 ≠ 空值）", !badOne.ok && badOne.skipped == 1)
    }

    println()
    if (f == 0) println("=== RagArchive 行为测试：全部通过 ===")
    else { println("=== RagArchive 行为测试：$f 条失败 ==="); kotlin.system.exitProcess(1) }
}
