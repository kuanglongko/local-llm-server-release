package com.xiaowan.localinference

/**
 * `SessionCleanup`（S4：会话列表的搜索与按时间清理）的**宿主侧行为测试**。
 *
 * 为什么不能只靠 `run_session_cleanup_guard.sh`：守卫钉的是"结构没改回去"
 * （筛选只在渲染层、删除取全库、默认不勾选、删完作废 KV 账本），
 * 它证明不了"这几条口径在**边界输入**下真的对"。而这里的难点全在边界：
 *
 *   · 「多少天算旧」是一条**闭区间**线：`updatedAt` 恰好等于 `now - days`
 *     的那一条**算旧**。差一就把它留下来了，而用户选的就是"7 天"这一档，
 *     同一批会话在连续两次打开时表现不一致，他没法判断到底删没删干净。
 *   · `updatedAt <= 0`（读不出来 / 老文件没写这个字段）必须**算旧** ——
 *     这种会话在列表里本来就排在最后，留着它会让"清理 90 天"永远清不掉，
 *     而用户看不到原因。
 *   · 空关键字 = **不筛**（不是"一个都匹配不上"）：后者会让刚打开列表
 *     就弹一次"没有匹配的会话"，而用户什么都还没输。
 *   · **一个都没勾就不许删** —— 这条是"不误删"的全部兜底，边界是空集合。
 *
 * `SessionCleanup` 是纯逻辑（无 android 依赖），所以直接用**真实现**断言。
 * 它引用 `SessionStore.Info`，那一份跟着一起编 —— 但本测试**不调用**任何
 * 带 `Context` 的方法，android.jar 里的 `Stub!` 空壳不会被触发。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private const val NOW = 1_700_000_000_000L
private val DAY = SessionCleanup.DAY_MS

private fun s(id: String, title: String, updatedAt: Long) =
    SessionStore.Info(id, title, "", 0L, updatedAt, 0)

fun main() {
    // ── 1. 搜索：空即不筛 / 子串 / 忽略大小写 / 大小写混合 ─────────────────
    val pool = listOf(
        s("a", "Kotlin 协程", NOW),
        s("b", "会议记录", NOW),
        s("c", "kotlin 笔记", NOW),
        s("d", "", NOW),
    )
    ck("空关键字：不筛（返回原列表，不是空）", SessionCleanup.filter(pool, "").size == 4)
    ck("全空白关键字：也不筛", SessionCleanup.filter(pool, "   ").size == 4)
    ck("子串命中：Kotlin 两条都中", SessionCleanup.filter(pool, "Kotlin").size == 2)
    ck("大小写不敏感：小写 kotlin 也命中两条", SessionCleanup.filter(pool, "kotlin").size == 2)
    ck("中文子串命中", SessionCleanup.filter(pool, "会议").map { it.id } == listOf("b"))
    ck("不命中：返回空列表", SessionCleanup.filter(pool, "不存在的标题").isEmpty())
    ck("筛选不改原列表（返回新列表）", pool.size == 4)

    // ── 2. 按时间：闭区间边界 + 未来时间 + updatedAt<=0 ──────────────────
    val byTime = listOf(
        s("just",  "刚刚",   NOW - 1),          // 1ms 前
        s("d7",    "正好7天", NOW - 7L * DAY),  // 恰好边界
        s("d7p",   "7天多",  NOW - 7L * DAY - 1),
        s("d30",   "正好30天", NOW - 30L * DAY),
        s("zero",  "没时间戳", 0L),
        s("neg",   "负时间戳", -1L),
    )
    ck("恰好第 7 天：算旧（闭区间）",
        SessionCleanup.olderThan(listOf(byTime[1]), NOW, 7).map { it.id } == listOf("d7"))
    ck("7 天多 1ms：算旧",
        SessionCleanup.olderThan(listOf(byTime[2]), NOW, 7).map { it.id } == listOf("d7p"))
    ck("刚刚（1ms 前）：不算旧",
        SessionCleanup.olderThan(listOf(byTime[0]), NOW, 7).isEmpty())
    // ⚠ 这条此前写的是 `listOf("d7","d7p","d30","zero","neg")` —— 它**恒假**：
    //   `olderThan(days=30)` 的阈值是 `NOW - 30*DAY`，而 d7/d7p 只过去 7 天，
    //   `updatedAt` 远在阈值**之后**，不该被选中。上一版的名字还写着
    //   "档位是「超过 N 天」，不是「恰好 N 天」"，可它期望的行为恰恰相反
    //   （把 7 天当成 30 天的旧）—— 名字与断言方向矛盾，而它一直是红的，
    //   于是被当成"环境跑不起来"沉在那里（详见本套件 run 脚本里补的 JAVA_HOME 说明）。
    //   按实现自己的口径（`olderThan` 的注释："超过 [days] 天没用过"）改正：
    //   30 天档只收 d30 与两个无时间戳的。
    ck("30 天档：只收超过 30 天的（7 天的还没到，档位不是「至少」）",
        SessionCleanup.olderThan(byTime, NOW, 30).map { it.id } == listOf("d30", "zero", "neg"))
    ck("30 天档：7 天档够不到的两条（7 天整 / 7 天多）都不在其中",
        SessionCleanup.olderThan(listOf(byTime[1], byTime[2]), NOW, 30).isEmpty())
    ck("updatedAt <= 0：算旧（没有可用时间信息的不能永远清不掉）",
        SessionCleanup.olderThan(listOf(byTime[4], byTime[5]), NOW, 90).size == 2)
    ck("0 天档：全部算旧（含刚刚那一条）",
        SessionCleanup.olderThan(byTime, NOW, 0).size == 6)
    ck("未来时间戳（改过系统时钟）：不算旧",
        SessionCleanup.olderThan(listOf(s("future", "未来", NOW + 10L * DAY)), NOW, 7).isEmpty())

    // 档位是**有名字的可见选项**，不是让用户填数字。
    ck("天数档位是 7 / 30 / 90 三档",
        SessionCleanup.DAY_CHOICES.toList() == listOf(7, 30, 90))
    ck("默认档位在三档里（否则 daysGroup.check 会落空）",
        SessionCleanup.DAY_CHOICES.contains(SessionCleanup.DEFAULT_DAY_CHOICE))

    // ── 3. 删除闸：空集合拒绝 / 交集 / 中途被删的不算 ──────────────────────
    ck("一个都没勾：拒绝", !SessionCleanup.canDelete(emptySet(), setOf("a", "b")))
    ck("勾了一个且它还在：允许", SessionCleanup.canDelete(setOf("a"), setOf("a", "b")))
    ck("勾的全都不在了（中途被别处删掉）：拒绝",
        !SessionCleanup.canDelete(setOf("a"), setOf("b")))
    ck("勾了三个、只剩一个在：允许（按交集动手）",
        SessionCleanup.canDelete(setOf("a", "x", "y"), setOf("a", "b")))

    // 「删除」按钮的**可用性**就取自 canDelete（视图里的 delBtn.isEnabled）——
    // 这一条不是形式主义：上一版按钮可用性靠 `dialogRef.getButton(...)` 回手同步，
    // 而框架 `AlertDialog` 在运行时取不到那个按钮，于是这段判据**根本没跑到**，
    // 新机型上还直接闪退。按钮的可用性必须由**一个**可以在这里被穷举的函数决定。
    ck("按钮可用性 = canDelete（打开时一个都没勾：灰的）",
        !SessionCleanup.canDelete(linkedSetOf(), setOf("a", "b")))
    ck("按钮可用性 = canDelete（勾了一条：亮的）",
        SessionCleanup.canDelete(linkedSetOf("a"), setOf("a", "b")))
    ck("按钮可用性 = canDelete（候选为空、也没勾：灰的）",
        !SessionCleanup.canDelete(emptySet(), emptySet()))

    ck("交集按列表序取（与 list() 的倒序一致）",
        SessionCleanup.resolve(pool, setOf("c", "a")).map { it.id } == listOf("a", "c"))
    ck("交集只留库里还有的（不存在的 id 不产生「假删除」）",
        SessionCleanup.resolve(pool, setOf("a", "不存在")).map { it.id } == listOf("a"))
    ck("空勾选：交集为空（调用方据此拒绝）",
        SessionCleanup.resolve(pool, emptySet()).isEmpty())

    // ── 会话管理列表里「哪一条打勾」的判据（本轮修的：对号不跟随选中会话）──
    //
    // 这一段的对象是 `makeSessionListView` 里那个 `label(i, selectedId)`
    // —— 它是**留在 Activity 里的局部函数**，宿主上取不到（Activity 里全是
    // `Stub!` 空壳）。所以这里按本仓库对这类对象的既有做法**逐字复刻判据**
    // （同 `PureLogicTest.capCharsNew` / `UiIoTest` 的 I-2 那两条），
    // 而"复刻与实现的判据不得脱钩"由守卫 ⑨ 段那条源码断言兜底
    // （它钉住实现里的判据是 `if (i.id == selectedId)`，不是 `currentSessionId`）。
    //
    // 为什么值得单独测：这条判据的错法**没有任何症状可言** —— 它照旧画出
    // 一个合法列表、对号也在、点哪条也能切，只是**对号停在上一条**上。
    // 唯一能当场证伪它的方式，就是"同一份行、给不同的 selected 入参，
    // 画出来的标记必须跟着换"。
    run {
        fun label(i: SessionStore.Info, selectedId: String): String = buildString {
            if (i.id == selectedId) append("✓ ")
            append(i.title)
        }
        val a = s("a", "会议记录", NOW)
        val b = s("b", "Kotlin 协程", NOW)

        ck("打勾的判据是**入参**：同一条行，给它当选中就带勾",
            label(a, "a").startsWith("✓ ") && label(b, "b").startsWith("✓ "))
        ck("同一条行，不给它当选中就不带勾（不是“凡进过列表就勾上”）",
            !label(a, "b").startsWith("✓ ") && !label(b, "a").startsWith("✓ "))
        ck("换一个 selected，同一批行的标记**整体跟着换** —— 这就是那个 bug 的判据",
            listOf(a, b).map { label(it, "a") }.map { it.startsWith("✓ ") } == listOf(true, false) &&
                listOf(a, b).map { label(it, "b") }.map { it.startsWith("✓ ") } == listOf(false, true))
        // 反向：**没有**任何会话被选中时（比如 currentSessionId 为空串，
        // 库刚被清空那一刻）不许有任何一行带勾 —— 否则它指向一条不存在的会话。
        ck("selected 为空串时一条都不勾（指向不存在的会话比不勾更坏）",
            listOf(a, b).none { label(it, "").startsWith("✓ ") })
        // ⚠ 这条断言**不是**在说"标题里的 ✓ 会被忽略"——它不会被忽略，它就在
        //    标题里，画出来当然在。它说的是**标记的归属**：一行带勾只能有一个
        //    来源，即"这一条就是选中的那条"。用一个**标题自带 ✓** 的行去问
        //    "没选中它时该不该带勾"，答案是"不" —— 这一条证明的是判据读的是
        //    `i.id == selectedId`，而不是"整行的文本里有没有 ✓ 这个字符"
        //    （后者是个恒真的坏判据：任何标题自带对号的行都会被判成选中）。
        val selfChecked = s("c", "✓ 我自己打的", NOW)
        // 没选中它时，**画出来的那一行仍然以标题自带的 ✓ 开头** —— 这正是
        // 「按文本里有没有 ✓ 判选中」这种坏判据会犯的错：它会把这条判成选中。
        // 所以断言分两半：坏判据会得到的结论（true），与好判据得到的结论（false）。
        ck("标题自带 ✓ 的行：没选中它 -> 标记那一位**没有**（文本里有 ✓ 不算选中）",
            selfChecked.title.startsWith("✓") &&
                !label(selfChecked, "a").startsWith("✓ ✓ "))
        ck("标题自带 ✓ 的行：选中它 -> 开头**有**标记（标记与标题是两个来源）",
            label(selfChecked, "c").startsWith("✓ ") &&
                label(selfChecked, "c") == "✓ ✓ 我自己打的")
        ck("标记后面必须紧跟标题（不是“勾”与标题各画各的）",
            label(a, "a") == "✓ 会议记录")
    }

    println(if (f == 0) "SessionCleanup: all ok" else "SessionCleanup: $f FAIL")
    if (f != 0) throw AssertionError("$f failed")
}
