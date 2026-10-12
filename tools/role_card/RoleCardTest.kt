package com.xiaowan.localinference

/**
 * `RoleCard` + `RoleCardStore.parse/encode`（V1 角色卡）的**宿主侧行为测试**。
 *
 * 为什么不能只靠 `run_role_card_guard.sh`：守卫钉的是"结构没改回去"
 * （切卡要作废 KV / 落盘走 tmp+rename / 会话存快照不存 id），
 * 它证明不了"这几条口径在**边界输入**下真的对"。而这一族的难点全在边界：
 *
 *   · **快照优先，而不是"按 id 去库里现取"** —— 这条是 V1 的核心。
 *     判据是"卡片删掉之后，会话还能拿出那段文本"（只存 id 的版本在这里恒为空）。
 *   · **`match` 比的是内容逐字节**，不是"id 还在"。只比 id 会把"卡片改了文案"
 *     判成 OK，而那正是最让用户误以为"改了卡片所有会话都跟着变"的错法。
 *   · **卡片库读盘是尽力而为**：一张坏卡不该让整个库读不出来；
 *     但**顶到上限**要如实报（不是静默丢掉最老的那些）。
 *   · **`canAdd` 是闭区间**：正好放得下必须收（同 `SessionQuota.decide` 那一格）。
 *
 * `RoleCard` / `RoleCardStore.parse|encode` 是纯逻辑（不碰 Context / 文件系统），
 * 所以这里用**真实现**断言；带 Context 的那几个（load/save/add/update/delete）
 * 留给了 `run_role_card_store_tests.sh`（它自己搭临时目录当 filesDir）。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private const val T = 1_700_000_000_000L

fun main() {
    println("=== 角色卡（V1）行为测试 ===")

    // ── 1. 名字/正文的规范化：改的与不改的必须分得开 ─────────────────────
    run {
        ck("名字两端空白去掉", RoleCard.normalizeName("  小万  ") == "小万")
        // 名字进的是**单行**下拉：带换行的名字在那里会被压成一行，
        // 而中间看不出分隔（"小万\\n助手" 显示成 "小万助手"）——
        // 用户看到的与存的不一样。
        ck("名字里的换行折成空格（下拉是单行控件，不折就看不见分隔）",
            RoleCard.normalizeName("小万\n助手") == "小万 助手")
        ck("名字里的 \\r 也折（Windows 上回传的文件）",
            RoleCard.normalizeName("小万\r\n助手") == "小万  助手")

        // 正文**只**去两端空白：换行与空行是用户写的结构，
        // 替它重排 = 静默改设定（症状是"改了设定回答没变"，与模型抽风同形）。
        ck("正文只去两端空白，内部换行与空行一字不动",
            RoleCard.normalizeSystem("  你是一个助手。\n\n规则：\n1. 不编造  ") ==
                "你是一个助手。\n\n规则：\n1. 不编造")
        ck("正文里的连续空行不被压缩", RoleCard.normalizeSystem("a\n\n\n\nb") == "a\n\n\n\nb")
    }

    // ── 2. 校验：拦住"存下去也用不了"的卡片 ─────────────────────────────
    run {
        ck("空名字被拒（下拉里是一行空白，用户不知道自己在选什么）",
            RoleCard.validate("", "内容") != null)
        ck("空正文被拒（空的卡片与「不用卡片」没有区别）",
            RoleCard.validate("名字", "") != null)
        ck("名字超长被拒", RoleCard.validate("x".repeat(RoleCard.MAX_NAME_CHARS + 1), "c") != null)
        ck("名字正好到上限**放行**（闭区间：差一就拒会让用户反复重试）",
            RoleCard.validate("x".repeat(RoleCard.MAX_NAME_CHARS), "c") == null)
        ck("正文超长被拒", RoleCard.validate("n", "y".repeat(RoleCard.MAX_CARD_CHARS + 1)) != null)
        ck("正文正好到上限**放行**（同闭区间）",
            RoleCard.validate("n", "y".repeat(RoleCard.MAX_CARD_CHARS)) == null)
        ck("正常的一张卡能过", RoleCard.validate("小万", "你是小万") == null)
        // 拒的时候必须**说清是哪一条**：只说"卡片非法"时用户不知道该改名字还是改正文。
        ck("拒的理由里带上了上限数字（可行动）",
            RoleCard.validate("x".repeat(99), "c")!!.contains(RoleCard.MAX_NAME_CHARS.toString()))
    }

    // ── 3. 上限是闭区间，且判据与 validate 同源 ─────────────────────────
    run {
        ck("没到上限：还能加", RoleCard.canAdd(RoleCard.MAX_CARDS - 1))
        ck("正好到上限：不能再加（第 201 张不该进）", !RoleCard.canAdd(RoleCard.MAX_CARDS))
        ck("空库：能加", RoleCard.canAdd(0))
    }

    // ── 4. match：**内容**逐字节比，不是"id 还在" ────────────────────────
    run {
        val card = RoleCard.Card("c1", "小万", "你是小万", T, T)
        val cards = listOf(card)

        ck("没有快照 = NONE（这个会话没用卡片）",
            RoleCard.match("", "", cards) == RoleCard.Match.NONE)
        ck("快照与库里那张逐字相同 = OK",
            RoleCard.match("c1", "你是小万", cards) == RoleCard.Match.OK)
        // ★ 这条是这块判据存在的理由：只比 id 会把"改了文案"判成 OK，
        //   于是用户以为"改了卡片、那些会话跟着变了"，而事实相反。
        ck("卡片改了文案（快照没变）= STALE —— 只比 id 会误判成 OK",
            RoleCard.match("c1", "你是小万（旧）", cards) == RoleCard.Match.STALE)
        ck("卡片被删（id 不在库里）= STALE",
            RoleCard.match("c1", "你是小万", emptyList()) == RoleCard.Match.STALE)
        // 只改名字（正文没动）不算 STALE：生效的是**正文**，名字只用来认人。
        ck("只改名字、正文没动 = OK（生效的是正文，名字只用来认人）",
            RoleCard.match("c1", "你是小万", listOf(card.copy(name = "新名字"))) == RoleCard.Match.OK)
        // 两端空白不算差异：两边都过 normalizeSystem，用户手打的两个空格不该
        // 被判成"卡片改过了"（那会让他点一次没必要的"覆盖"）。
        ck("快照两端有空白、库里没有 = OK（两边都规范化过）",
            RoleCard.match("c1", "  你是小万  ", cards) == RoleCard.Match.OK)
    }

    // ── 5. effectiveSystem：**快照优先**（V1 的核心口径）─────────────────
    run {
        ck("有快照就用快照（哪怕手打的那段更长更详细）",
            RoleCard.effectiveSystem("你是小万", "用户手打的另一段") == "你是小万")
        ck("没有快照才用手打的（V1 之前的行为逐字不变）",
            RoleCard.effectiveSystem("", "用户手打的") == "用户手打的")
        ck("两样都没有 = 空（不发 system 段）", RoleCard.effectiveSystem("", "") == "")
    }

    // ── 6. 卡片库解析：尽力而为 + 三类毛病分得开 ────────────────────────
    run {
        val good = """{"id":"a","name":"小万","system":"你是小万","createdAt":1,"updatedAt":2}"""
        ck("空数组 = 空库且无毛病（绝大多数用户的常态，不该出声）",
            RoleCardStore.parse("[]").let { it.cards.isEmpty() && it.fault == RoleCard.CardFault.NONE })

        val one = RoleCardStore.parse("[$good]")
        ck("正常一张能读出来", one.cards.size == 1 && one.cards[0].name == "小万")
        ck("正常读取时 dropped = 0", one.dropped == 0)

        // 坏卡跳过并计数：500 张里有一张脏就全丢是更坏的失败方向（同 ChatArchive 对 role 的口径）。
        val withBad = RoleCardStore.parse(
            """[$good,{"id":"b","name":"","system":"x"},{"id":"c","name":"n","system":""},1,"nope"]""")
        ck("坏卡被跳过**并计数**（不是静默丢、也不是整份拒绝）",
            withBad.cards.size == 1 && withBad.dropped == 4)
        ck("坏卡不影响好卡读出来", withBad.cards[0].id == "a")

        // 非 JSON / 不是数组：整个库读不出来 —— 与"空库"必须分开
        // （一个是"你没建过卡"，一个是"你的卡在某个损坏的文件里"）。
        ck("不是 JSON = UNREADABLE（与「空库」分开）",
            RoleCardStore.parse("这不是 JSON").fault == RoleCard.CardFault.UNREADABLE)
        ck("是 JSON 但不是数组 = UNREADABLE",
            RoleCardStore.parse("""{"cards":[]}""").fault == RoleCard.CardFault.UNREADABLE)

        // 超限：只读最近的，且**如实报**（不是静默砍掉最老的那些）。
        val many = (1..(RoleCard.MAX_CARDS + 5)).joinToString(",") { i ->
            """{"id":"c$i","name":"n$i","system":"s","createdAt":$i,"updatedAt":$i}"""
        }
        val over = RoleCardStore.parse("[$many]")
        ck("超限只读进 ${RoleCard.MAX_CARDS} 张", over.cards.size == RoleCard.MAX_CARDS)
        ck("超限**如实报**（不是静默丢）", over.fault == RoleCard.CardFault.OVER_LIMIT)
        // ★ 截断必须砍**最老**的：按文件原始顺序截断会让"丢了哪几张"
        //   取决于历史写入顺序 —— 用户看到的是一批随机丢掉的卡片。
        ck("截断砍的是最老的（留下的是 updatedAt 最大的那些）",
            over.cards.first().id == "c${RoleCard.MAX_CARDS + 5}" &&
                over.cards.none { it.id == "c1" })
    }

    // ── 7. 序列化往返：逐字段一致（备份不可信等于没有备份）──────────────
    run {
        val cards = listOf(
            RoleCard.Card("a", "小万", "你是小万\n\n规则：不编造", 1L, 2L),
            RoleCard.Card("b", "翻译", "只输出译文", 3L, 4L),
        )
        val back = RoleCardStore.parse(RoleCardStore.encode(cards))
        ck("往返：张数一致", back.cards.size == 2)
        // ⚠ 按 **id** 找回来比，不按下标：读侧会按 `updatedAt` 倒序重排
        //   （那是它该做的事，见下一条断言），按下标比就等于在测"顺序恰好没变"。
        ck("往返：字段逐个一致（含正文里的换行）",
            back.cards.first { it.id == "a" } == cards[0] &&
                back.cards.first { it.id == "b" } == cards[1])
        ck("encode 的字段名是固定的那几个（便于人看与 diff）",
            RoleCardStore.encode(cards).contains("\"name\"") &&
                RoleCardStore.encode(cards).contains("\"system\""))
        // 排序：读侧按 updatedAt 倒序 —— 与 SessionStore.list 同口径。
        ck("读出来按 updatedAt 倒序（最近改的在前面）",
            back.cards.map { it.id } == listOf("b", "a"))
    }

    println(if (f == 0) "RoleCardTest: all passed" else "RoleCardTest: $f failed")
    if (f != 0) kotlin.system.exitProcess(1)
}
