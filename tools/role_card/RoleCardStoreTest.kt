package com.xiaowan.localinference

import java.io.File

/**
 * `RoleCardStore` 的**落盘**那一半（带 Context 的那几个入口）的宿主侧复刻测试。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么这一半必须单独测
 * ══════════════════════════════════════════════════════════════════════════
 * `RoleCardStore` 的 `load/save/add/update/delete` 签名里带 `android.content.Context`
 * （只为拿 `filesDir`），宿主下拿不到可用的 Context（android.jar 里是抛 `Stub!`
 * 的空壳）。所以这里把**落盘协议**逐字复刻成"吃一个目录"的纯逻辑来跑 ——
 * 同本仓库对 `SessionStore.capChars` / `SessionMigrate` 的既有做法。
 *
 * 而这一半有两条**只有真实文件系统才能证伪**的不变量，全是"错得不响"：
 *
 * ① **写盘必须是"全成或全不成"**。卡片库是**整体重写**（改一张 = 重写整个文件），
 *    直接 `writeText` 写一半断电 = 整个库变成半截 JSON = 下次读出来
 *    `UNREADABLE` = **全部卡片一起没**。会话那边丢了最坏是少一轮对话，
 *    损失面差一个量级，所以这里走 `tmp + renameTo`。
 *    判据：**写失败时磁盘上那份必须一个字节都没变**（拿旧内容对拍）。
 * ② **`.tmp` 不许被当成库**。它是中间状态，一个只认 `cards.json` 的读盘路径
 *    才不会被"上次写了一半的临时文件"骗到。
 *
 * 复刻与实现不得脱钩由 `run_role_card_guard.sh` 的源码断言兜底
 * （它钉住 `save` 里那两行：先写 TMP、再 renameTo 到正式文件）。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private const val FILE = "cards.json"
private const val TMP = "cards.json.tmp"

/** 复刻 `RoleCardStore.save`：先写 `.tmp`，再删目标、再 rename。 */
private fun saveFake(dir: File, cards: List<RoleCard.Card>): Boolean = runCatching {
    val tmp = File(dir, TMP)
    tmp.writeText(RoleCardStore.encode(cards))
    // 目标文件**故意设成只读**来模拟"写盘失败"：删除会抛，于是 catch 走"返回 false"。
    File(dir, FILE).delete()
    tmp.renameTo(File(dir, FILE))
}.getOrElse { false }

/** 复刻 `RoleCardStore.load`：只认正式文件，`.tmp` 不在读取范围内。 */
private fun loadFake(dir: File): RoleCard.Loaded {
    val f2 = File(dir, FILE)
    if (!f2.exists()) return RoleCard.Loaded(emptyList(), RoleCard.CardFault.NONE)
    val text = runCatching { f2.readText() }.getOrNull()
        ?: return RoleCard.Loaded(emptyList(), RoleCard.CardFault.UNREADABLE)
    return RoleCardStore.parse(text)
}

private fun tmpDir(): File =
    File(System.getProperty("java.io.tmpdir"),
        "rolecard-test-" + System.nanoTime().toString(36)).apply { mkdirs() }

fun main() {
    println("=== 角色卡库（落盘）复刻测试 ===")

    // ── 1. 没有文件 = 空库且**不出声**（绝大多数用户的常态）────────────────
    run {
        val d = tmpDir()
        val l = loadFake(d)
        ck("没有 cards.json：空库 + NONE（不该弹一句「卡片全没了」）",
            l.cards.isEmpty() && l.fault == RoleCard.CardFault.NONE)
        d.deleteRecursively()
    }

    // ── 2. 存 → 读 往返（含"最近改的在前面"）────────────────────────────
    run {
        val d = tmpDir()
        val cards = listOf(
            RoleCard.Card("a", "旧", "旧内容", 1L, 1L),
            RoleCard.Card("b", "新", "新内容", 2L, 9L),
        )
        ck("save 返回 true", saveFake(d, cards))
        ck("落盘之后目录里**没有** .tmp 残留",
            !File(d, TMP).exists())
        val back = loadFake(d)
        ck("读回来两张", back.cards.size == 2)
        ck("读回来按 updatedAt 倒序", back.cards.map { it.id } == listOf("b", "a"))
        ck("正文逐字节一致", back.cards.first { it.id == "a" }.system == "旧内容")
        d.deleteRecursively()
    }

    // ── 3. 覆盖写：旧内容必须**整体**被换掉（不是追加、不是合并）──────────
    run {
        val d = tmpDir()
        saveFake(d, listOf(RoleCard.Card("a", "一", "内容一", 1, 1)))
        saveFake(d, listOf(RoleCard.Card("b", "二", "内容二", 2, 2)))
        val back = loadFake(d)
        ck("覆盖写之后只剩新的那批（卡片库是整体重写，不是 append）",
            back.cards.map { it.id } == listOf("b"))
        d.deleteRecursively()
    }

    // ── 4. ★ 写失败必须"一个字节都不动"（半截写入 = 整个库消失）────────────
    run {
        val d = tmpDir()
        saveFake(d, listOf(RoleCard.Card("keep", "留着", "这段必须还在", 1, 1)))
        val before = File(d, FILE).readText()

        // 把目录设成不可写来模拟写盘失败（`File.setWritable(false)` 只有一个无参重载，
        // 没有"恢复初值"的返回值 —— 所以自己记一下再在 finally 里还原）。
        val writableDir = d.canWrite()
        d.setWritable(false)
        val okFail = try {
            saveFake(d, listOf(RoleCard.Card("x", "新", "新内容", 2, 2)))
        } finally {
            d.setWritable(writableDir)
        }
        // root 用户下 chmod 拦不住写入，这个对照会"没失败"—— 如实跳过而不是假装验过。
        if (okFail) {
            println("  skip  写失败对照（当前用户不受文件权限约束，无法制造失败）")
        } else {
            ck("写失败返回 false", true)
            ck("★ 写失败后磁盘上那份**一个字节都没变**（旧卡片还在）",
                File(d, FILE).readText() == before)
            ck("★ 写失败后 reload 仍能读出**原来那张**（不是空库、不是半截 JSON）",
                loadFake(d).cards.map { it.id } == listOf("keep"))
        }
        d.deleteRecursively()
    }

    // ── 5. 半截 JSON = UNREADABLE，而**不是**"空库"（两者处置不同）────────
    run {
        val d = tmpDir()
        File(d, FILE).writeText("""[{"id":"a","name":"只写了一半""")
        val l = loadFake(d)
        ck("半截 JSON：读成 UNREADABLE 而不是「空库」（一个是文件坏了，一个是你没建过）",
            l.cards.isEmpty() && l.fault == RoleCard.CardFault.UNREADABLE)
        // ⚠ 而且**不许**把它当空库去写：那会把用户剩下的卡片一起抹掉。
        //   复刻里"不许自动写回"这条由 `load` 的签名保证（它只读不写）——
        //   这里用文件内容对拍把它钉住。
        val raw = File(d, FILE).readText()
        loadFake(d)
        ck("★ 读失败之后**没有**任何人去改写那个文件（坏数据留着，等用户处置）",
            File(d, FILE).readText() == raw)
        d.deleteRecursively()
    }

    // ── 6. `.tmp` 不会被当成库（上次写了一半的中间态）─────────────────────
    run {
        val d = tmpDir()
        File(d, TMP).writeText("""[{"id":"half","name":"半截","system":"x"}]""")
        val l = loadFake(d)
        ck("只有 .tmp 时读成「空库」（中间态不当成正式内容）",
            l.cards.isEmpty() && l.fault == RoleCard.CardFault.NONE)
        d.deleteRecursively()
    }

    // ── 7. 上一次的 .tmp 会被这次覆盖写全量替换（不会串味）─────────────────
    run {
        val d = tmpDir()
        File(d, TMP).writeText("这是上一次留下的垃圾")
        saveFake(d, listOf(RoleCard.Card("a", "一", "内容一", 1, 1)))
        ck("有残留 .tmp 时正常写入（不是把垃圾当内容拼进去）",
            loadFake(d).cards.map { it.id } == listOf("a"))
        ck("正常写入之后 .tmp 已改名、不残留", !File(d, TMP).exists())
        d.deleteRecursively()
    }

    println(if (f == 0) "RoleCardStoreTest: all passed" else "RoleCardStoreTest: $f failed")
    if (f != 0) kotlin.system.exitProcess(1)
}
