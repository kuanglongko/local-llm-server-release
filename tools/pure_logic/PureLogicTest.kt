package com.xiaowan.localinference

import java.io.File

/**
 * 模块 C 四条修复的**宿主侧行为复刻测试**。
 *
 * 为什么不能只靠源码守卫：`run_pure_logic_guard.sh` 钉的是"结构没改回去"，
 * 它证明不了"这套结构在**边界输入**下真的对"。而 C-1 尤其没有宿主单测可达的入口
 * —— `SessionStore.capChars` 签名里带 `android.content.Context`（只为打日志），
 * 宿主下没有 Context 实现（android.jar 里是抛 `Stub!` 的空壳）。
 *
 * 因此这里把 `capChars` 的控制流**逐字复刻**成纯逻辑跑（只把日志换成计数），
 * 并对**旧写法**同时跑一遍做对照：同一组输入下旧写法必须暴露出问题。
 * 复刻与实现是否脱钩，由 `run_pure_logic_guard.sh` 的源码断言兜底
 * （它钉住 `while (msgs.size > 2 && total() > MAX_CHARS)` 与末条截断那两行）。
 *
 * C-2 / C-3 / C-4 的复刻对象是可以直接编译的纯逻辑文件（无 android 依赖），
 * 所以那三条在 `run_thinking_tests.sh` / `run_sampling_tests.sh` 里用**真实现**断言，
 * 本文件只补 C-1 与"跨文件判据一致"的那两条。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private const val MAX_CHARS = 24000
private const val MIN_MSGS = 2

/**
 * 复刻修复后的 `SessionStore.capChars`（日志换成返回值中的 dropped 计数）。
 *
 * ⚠ S1（`0.9.175`）起：截断**从次新的往前**做、**末条整体保留**（旧形状在这里
 * 换成了 `last - 1 downTo 0` + 排除末条）。逐条挪位的记账（`removals`）与空条
 * 的显式 `continue` 也一并复刻 —— 它们是这条不变量的实现手段，漏掉任一处，
 * 复刻就在测"我写的一份近似"。S1 更细的边界用例在
 * `tools/session_cap/SessionCapTest.kt`（14 条，含上一版/上上版的对照）。
 */
private fun capCharsNew(msgs: MutableList<Pair<String, String>>): Int {
    fun total() = msgs.sumOf { it.second.length }
    while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)
    if (total() <= MAX_CHARS) return 0
    val last = msgs.lastIndex
    var over = total() - MAX_CHARS
    var dropped = 0
    val removals = IntArray(msgs.size)
    for (i in last - 1 downTo 0) {
        if (over <= 0) break
        val at = i - removals[i]
        if (at !in 0 until last) continue
        val cur = msgs[at].second
        val cut = minOf(over, cur.length)
        if (cut <= 0) continue
        msgs[at] = msgs[at].first to cur.substring(0, cur.length - cut)
        removals[at]++
        over -= cut
        dropped += cut
    }
    return dropped
}

/** 复刻 **S1 之前**的写法：从最新一条往前截、保头 —— 会先把当轮提问的尾部啃掉。 */
private fun capCharsPrev(msgs: MutableList<Pair<String, String>>) {
    while (msgs.size > MIN_MSGS && msgs.sumOf { it.second.length } > MAX_CHARS) msgs.removeAt(0)
    if (msgs.sumOf { it.second.length } <= MAX_CHARS) return
    var over = msgs.sumOf { it.second.length } - MAX_CHARS
    for (i in msgs.indices.reversed()) {
        if (over <= 0) break
        val cur = msgs[i].second
        val cut = minOf(over, cur.length)
        if (cut > 0) {
            msgs[i] = msgs[i].first to cur.substring(0, cur.length - cut)
            over -= cut
        }
    }
}

/** 复刻修复**前**的写法（`size > 2` 当先决条件的复合 while）。 */
private fun capCharsOld(msgs: MutableList<Pair<String, String>>) {
    while (msgs.sumOf { it.second.length } > MAX_CHARS && msgs.size > 2) msgs.removeAt(0)
}

fun main() {
    println("=== 纯逻辑行为复刻测试（模块 C）===")
    val src = File("app/src/main/java/com/xiaowan/localinference")
    val ss = File(src, "SessionStore.kt").readText()

    // ---- C-1：只剩 2 条时保险丝必须仍生效（旧写法在这里完全失效）----
    run {
        val two = mutableListOf("user" to "A".repeat(30000), "assistant" to "B".repeat(30000))
        val oldCopy = two.map { it.first to it.second }.toMutableList()
        capCharsOld(oldCopy)
        val oldLen = oldCopy.sumOf { it.second.length }
        ck("C-1 对照：旧写法在只剩 2 条时完全失效（注释称 ≤24000，实际 $oldLen）", oldLen > MAX_CHARS)

        val newCopy = two.map { it.first to it.second }.toMutableList()
        capCharsNew(newCopy)
        val newLen = newCopy.sumOf { it.second.length }
        ck("C-1 修复：只剩 2 条时把超限量落到最早那条（最早那条被收窄到 0，$newLen == 30000）",
            newCopy.first().second.isEmpty())
        ck("C-1 修复：保留两条消息（不把对话删空）", newCopy.size == 2)
        ck("C-1 修复：末条一字未动（提问/结论不再被吞一半）",
            newCopy.last().second.length == 30000)
        ck("C-1 修复：末条自身超限时（30000 > $MAX_CHARS）整体保留 —— 与 S1\"不啃本轮\"同一口径",
            newLen == 30000)
    }

    // ---- C-1：S1 的对照 —— 上一版（从最新往前截）会把当轮提问啃短 ----
    run {
        val long = "提问".repeat(6000)
        // ⚠ 长度是**算过的**：两条历史必须都 <= MIN_MSGS 那层丢不动的量。
        // 取 12000 时 12000+12000+36000 = 60000，一级 `while (size > 2 && total > MAX)`
        // 直接把它丢到只剩 2 条，二级的截断循环一次都没跑到 —— 那条"上一版把提问
        // 啃短"的断言因此长期恒红/恒绿（它断言的其实是**另一条**路径）。
        val src = mutableListOf("user" to "H".repeat(17000), "assistant" to "A".repeat(17000), "user" to long)
        val prev = src.map { it.first to it.second }.toMutableList()
        capCharsPrev(prev)
        ck("C-1 对照（S1 前）：上一版把当轮提问（末条）啃短了", prev.last().second.length < long.length)

        val now = src.map { it.first to it.second }.toMutableList()
        capCharsNew(now)
        ck("C-1 修复（S1）：末条逐字节不变", now.last().second == long)
        ck("C-1 修复（S1）：超限由历史承担，总长落回上限",
            now.sumOf { it.second.length } <= MAX_CHARS)
    }

    // ---- C-1：末条自身超限时整体保留（不做"保头截尾"）----
    run {
        val huge = "X".repeat(30000)
        val c = mutableListOf("u" to "h".repeat(100), "a" to "a".repeat(100), "u" to huge)
        capCharsNew(c)
        ck("C-1 修复（S1）：末条自身超限时也整体保留", c.last().second == huge)
    }

    // ---- C-1：正常情形（3 条以上）仍走"丢最早"的老路径 ----
    run {
        val many = mutableListOf(
            "u" to "x".repeat(15000), "a" to "y".repeat(15000), "u" to "z".repeat(15000))
        val c = many.map { it.first to it.second }.toMutableList()
        capCharsNew(c)
        ck("C-1：3 条超限时先丢最早的历史，再落到上限内",
            c.size == 2 && c.sumOf { it.second.length } == MAX_CHARS)
    }

    // ---- C-1：未超限时一个字节都不动（保险丝不得误伤）----
    run {
        val ok = mutableListOf("u" to "a".repeat(100), "a" to "b".repeat(100))
        val c = ok.map { it.first to it.second }.toMutableList()
        ck("C-1：未超限时不丢字节", capCharsNew(c) == 0 && c.sumOf { it.second.length } == 200)
        ck("C-1：未超限时消息条数不变", c.size == 2)
    }

    // ---- C-1：单条就超上限（极端）也要落到上限内 ----
    run {
        val one = mutableListOf("u" to "q".repeat(50000), "a" to "r".repeat(100))
        val c = one.map { it.first to it.second }.toMutableList()
        capCharsNew(c)
        ck("C-1：单条远超上限时也会被截到上限内",
            c.sumOf { it.second.length } <= MAX_CHARS)
    }

    // ---- C-2：半个标签不是内容（对真实现断言，见 run_thinking_tests.sh）----
    // 这里只补一条"跨文件判据一致"：ThinkStream 不得重新依赖 android.util.Log，
    // 否则宿主单测直接 `Stub!` 崩（本轮曾犯，被 run_thinking_tests.sh 抓到）。
    ck("C-2：ThinkStream 零 android import（纯逻辑契约）",
        ss.isNotEmpty() && !File(src, "ThinkStream.kt").readText().lines()
            .any { it.startsWith("import android") })

    // ---- 日志页筛选：取数判据的行为（复刻 LogFilter 的 matches/keywordMatches，逐字对齐）----
    // 为什么在这里复刻而不进守卫：守卫只能钉"标签走 startsWith 而不是 contains"，
    // 钉不出**边界输入下到底对不对** —— 而这里的用例全是"看着像标签、其实不是"、
    // "一个都没选"、"选了仅错误又选了两档"这一类，它们恰恰是筛选最容易被写成
    // "差不多能用"的地方。复刻与实现的字面量对齐由 run_pure_logic_guard.sh + 
    // tools/log_filter/check_tag_sync.py 钉住（只测自己抄的那一份是假证据）。
    run {
        val tags = listOf("[HTP参与]", "[HTP实测]", "[计时]", "[内存]")   // 仅用于自检：与常量表比对
        // 复刻：与 LogFileStore.LogFilter 的 matches/keywordMatches 逐字一致。
        fun matches(keys: Set<String>, line: String): Boolean {
            val t = line.trimStart()
            val anyLabel = keys.any { it != "仅错误" }
            val byLabel = if (!anyLabel) true else
                ("计时" in keys && t.startsWith("[计时]")) ||
                    ("内存" in keys && t.startsWith("[内存]")) ||
                    ("HTP" in keys && (t.startsWith("[HTP参与]") || t.startsWith("[HTP实测]")))
            val err = "仅错误" in keys
            // ⚠ 半：`⚠` 行内任意位置都算（真实失败行长这样：`[repack] ⚠ …`）。
            // 方括号标记半：只认行首（否则正文里提到 `[错误]` 就被算成错误行）。
            val isErr = line.contains("⚠") || t.startsWith("[错误]") ||
                listOf("[失败]", "[拒绝]", "[异常]", "[崩溃]", "[crash]", "[error]", "[Error]")
                    .any { t.startsWith(it) }
            return byLabel && (!err || isErr)
        }
        fun kwMatches(kw: String, line: String): Boolean =
            if (kw.isEmpty()) true else line.contains(kw, ignoreCase = true)

        // —— 单选语境的四条（0.9.142 就有，不能因为改成多选而退化）——
        ck("筛选：四个标签各自只命中自己那一档",
            matches(setOf("计时"), "[计时] decode 12 tok/s") &&
                !matches(setOf("计时"), "[内存] RSS 900 MB") &&
                matches(setOf("内存"), "[内存] RSS 900 MB") &&
                !matches(setOf("内存"), "[计时] prefill 1 tok/s"))
        ck("筛选：HTP 档同时覆盖 [HTP参与] 与 [HTP实测]",
            matches(setOf("HTP"), "[HTP参与] 摘要") && matches(setOf("HTP"), "[HTP实测] 12/32 层在NPU") &&
                !matches(setOf("HTP"), "[计时] decode"))
        // 关键反例：正文里出现标签文字 —— contains 写法会把它们捞进来（看着能用）。
        ck("筛选：正文里提到标签不算命中（行首判据，非 contains）",
            !matches(setOf("内存"), "用户问：日志里的[内存]是什么意思"))
        ck("筛选：行首带缩进的标签算命中（trimStart 后仍按行首判）",
            matches(setOf("计时"), "   [计时] 前面有缩进也算"))

        // —— 多选：或聚合、空集合 = 全部 ——
        ck("多选：选两档时两档都放行（或，不是与）",
            matches(setOf("计时", "内存"), "[计时] decode") &&
                matches(setOf("计时", "内存"), "[内存] RSS 900 MB") &&
                !matches(setOf("计时", "内存"), "[HTP参与] 摘要"))
        // 这条是多选最容易写错的地方，而症状正好是一张空白页。
        ck("多选：一个都没选 = 全部（不是「全部不放行」）",
            matches(emptySet(), "[计时] decode") && matches(emptySet(), "随便一行") &&
                matches(emptySet(), ""))
        // 与"空集合"同一个不变量：认不出的档**不参与**筛选，而不是把行全筛掉。
        // ⚠ 这里钉的是**已知档之间**的"或"，以及一条容易被想当然的边界：
        // 认不出的档名**不会**放行任何行（它不属于任何一支判据）—— 换句话说，
        // "未知档 = 显示全部"这个直觉是**错的**，真正的兜底在**入口处**：
        // 读 prefs 时按 labels 过滤、点击时按 labels 校验（两处都在 EngineActivity，
        // 由 run_log_filter_guard.sh 钉住）。写清楚这一点，是因为把它当成
        // "未知档放行"会让下一个人在判据里补一条"未知就放行"的兜底 ——
        // 而那条兜底恰好会让**脏值 prefs 从"整页空白"变成"看着正常但筛选失效"**，
        // 后者更难发现。
        ck("多选：已知档之间是「或」；未知档名**不**放行任何行（兜底在入口校验）",
            matches(setOf("计时", "内存"), "[计时] decode") &&
                !matches(setOf("计时", "内存"), "[HTP参与] 摘要") &&
                !matches(setOf("这不是一个档"), "[计时] x") &&
                !matches(setOf("计时", "这不是一个档"), "[内存] RSS 900 MB"))

        // —— 「仅错误」与其它档的关系：叠加是收窄，单独选时不看标签 ——
        // 注意失败标记的判据是**行首**（与标签同源的理由）：`[计时] decode 正常`
        // 的行首是 `[计时]`，不是 `⚠` —— 所以"一行既是计时又算错误"这件事，
        // 只有行首本身是失败标记时才成立。测试里两个档都得用**行首标记**的行。
        // 这两条钉的是"标签在前、⚠ 在后"这个**真实形态**（`[repack] ⚠ …`）：
        // 若把 ⚠ 也要求成行首，"计时 + 仅错误"在本仓库几乎永远筛不出东西，
        // 而"筛不出东西"与"确实没出错"同形 —— 用户会得出"没出过错"的结论。
        ck("仅错误：与「计时」叠加时是收窄（计时里的告警行）",
            matches(setOf("计时", "仅错误"), "[计时] ⚠ 超时") &&
                matches(setOf("计时", "仅错误"), "[计时] ⚠ 落回 CPU") &&
                !matches(setOf("计时", "仅错误"), "[计时] decode 正常") &&
                !matches(setOf("计时", "仅错误"), "[内存] ⚠ 超时") &&
                !matches(setOf("计时", "仅错误"), "[HTP参与] ⚠ 层数不匹配"))
        // 这条防的是"仅错误悄悄变成某一档的子集"：用户点它要的是所有失败。
        ck("仅错误：单独选时**不看标签**（所有失败，不管哪一档）",
            matches(setOf("仅错误"), "⚠ 超时") &&
                matches(setOf("仅错误"), "[内存] ⚠ 超时") &&
                matches(setOf("仅错误"), "[崩溃] SIGABRT") &&
                !matches(setOf("仅错误"), "[计时] decode 正常"))
        ck("仅错误：方括号标记只认**行首**，正文里提到不算",
            matches(setOf("仅错误"), "[错误] OOM") &&
                !matches(setOf("仅错误"), "[计时] 加载失败") &&
                !matches(setOf("仅错误"), "用户问：日志里的[错误]是什么意思") &&
                !matches(setOf("仅错误"), "用户问：加载失败是什么意思"))

        // —— 关键字：子串、大小写不敏感、空即不筛 ——
        ck("关键字：子串命中（包括屏幕上看到的半句）",
            kwMatches("decode", "[计时] decode 12 tok/s") &&
                kwMatches("12 tok", "[计时] decode 12 tok/s"))
        ck("关键字：大小写不敏感（日志里大小写混着来）",
            kwMatches("decode", "[计时] Decode 12 tok/s") &&
                kwMatches("DECODE", "[计时] decode 12 tok/s"))
        ck("关键字：空串不筛（不是「什么都不命中」）",
            kwMatches("", "[计时] decode") && kwMatches("", ""))
        ck("关键字：搜的是整行（行首/行尾/正文都能命中）",
            kwMatches("用户问", "用户问：日志里的[内存]是什么意思"))
        ck("关键字：搜不到就是不命中（不是模糊匹配）",
            !kwMatches("decode", "[计时] prefill 12 tok/s") &&
                !kwMatches("decode2", "[计时] decode 12 tok/s"))

        // —— 两维叠加是「与」——
        ck("两维：标签与关键字是**与**（两个都要满足）",
            matches(setOf("计时"), "[计时] decode 12 tok/s") && kwMatches("decode", "[计时] decode 12 tok/s") &&
                !(matches(setOf("计时"), "[内存] decode 12 tok/s") &&
                    kwMatches("decode", "[内存] decode 12 tok/s")))

        // 空白页提示的三条判据：区分"筛出来没有"与"日志本来就空"。
        fun describe(keys: Set<String>, keyword: String): String {
            val parts = ArrayList<String>(2)
            if (keys.isNotEmpty()) parts += "「" + keys.joinToString("」「") + "」"
            if (keyword.isNotEmpty()) parts += "含「$keyword」"
            return parts.joinToString(" + ")
        }
        fun emptyHint(keys: Set<String>, keyword: String, hit: Int, total: Int): String {
            if (hit > 0) return ""
            val what = describe(keys, keyword)
            return when {
                total <= 0 -> "日志为空：还没有写入任何日志行。"
                what.isEmpty() -> ""
                else -> "没有同时满足 $what 的日志行（整段日志共 $total 行）；" +
                    "清掉筛选或关键字看全部。"
            }
        }
        ck("提示：有命中时不显示任何提示（不要拿说明挤掉正文）",
            emptyHint(setOf("计时"), "", 3, 900) == "")
        ck("提示：ring 为空时说的是「日志为空」而不是「筛出来没有」",
            emptyHint(setOf("计时"), "decode", 0, 0).contains("日志为空"))
        ck("提示：有日志但没命中时给出**筛了什么**、还剩多少、出口在哪",
            emptyHint(setOf("计时"), "decode", 0, 900).contains("计时") &&
                emptyHint(setOf("计时"), "decode", 0, 900).contains("decode") &&
                emptyHint(setOf("计时"), "decode", 0, 900).contains("900") &&
                emptyHint(setOf("计时"), "decode", 0, 900).contains("清掉筛选"))
        ck("提示：两维都空时不产生提示（那种情况下的空白页是「日志为空」）",
            emptyHint(emptySet(), "", 0, 900) == "")

        // 标签与判据必须仍指向同一批字面量（这里用的 tags 就是源码里的常量值）。
        val lfs = File(src, "LogFileStore.kt").readText()
        // ⚠ 比的是"源码里有没有那段字面量"，所以直接拿常量声明那句去比，
        // 不在这里拼带引号的字符串（拼错了这条会恒真，等于没有）。
        ck("筛选：复刻用的标签就是源码里的 TAG_* 值（防两侧漂移）",
            listOf(
                "TAG_HTP = \"[HTP参与]\"",
                "TAG_HTP_MEASURED = \"[HTP实测]\"",
                "TAG_TIME = \"[计时]\"",
                "TAG_MEM = \"[内存]\"",
            ).all { lfs.contains(it) } &&
                listOf("\"[HTP参与]\"", "\"[HTP实测]\"", "\"[计时]\"", "\"[内存]\"")
                    .all { lfs.contains(it) })
        // 档位名的字面量（4 个）也在这里自检：它们是两维判据的**集合元素**，
        // 一旦实现改文案而这里没跟着改，"复刻测试全绿"就与真实现无关了。
        ck("筛选：复刻用的档位名就是源码里的常量值（防两侧漂移）",
            listOf("\"计时\"", "\"内存\"", "\"HTP\"", "\"仅错误\"").all { lfs.contains(it) })
    }

    // ---- C-1 复刻与实现不得脱钩（源码级锚点，与守卫同口径）----
    ck("C-1：实现里存在两级裁剪（丢历史 + 从次新的往前逐条收窄）",
        ss.contains("while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)") &&
            ss.contains("msgs[at].put(\"content\", cur.substring(0, cur.length - cut))") &&
            ss.contains("for (i in last - 1 downTo 0)"))
    ck("C-1：旧形状已从实现中消失",
        !ss.contains("sumOf { it.optString(\"content\").length } > 24000 && msgs.size > 2"))

    println(if (f == 0) "=== 纯逻辑复刻测试全部通过 ===" else "=== 纯逻辑复刻测试失败 $f 条 ===")
    if (f != 0) kotlin.system.exitProcess(1)
}
