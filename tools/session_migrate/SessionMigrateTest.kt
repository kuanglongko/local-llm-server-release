package com.xiaowan.localinference

import java.io.File

/**
 * S2「旧单会话迁移：被上限挡住时必须出声」的**宿主侧行为复刻测试**。
 *
 * 为什么不能只靠源码守卫：`tools/run_session_migrate_guard.sh` 钉的是
 * "结构没改回去"，证明不了"这套结构在**边界输入**下真的对"。而
 * `SessionStore.migrateIfNeeded` 落盘要走 `android.content.Context` 的文件系统
 * （宿主下 android.jar 里是抛 `Stub!` 的空壳），所以只能把**结局的判定**
 * 逐字复刻成纯逻辑跑，用临时目录当 `filesDir`。
 *
 * 复刻与实现是否脱钩由守卫兜底（它钉住四格结局、失败不删旧文件的行序、
 * 以及结局只在 initSessions 一处认领）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 这批用例围绕**一条**不变量：「迁移失败不许把旧记录变没」
 * ═══════════════════════════════════════════════════════════════════════
 * 旧写法是 `createNoMigrate(ctx) ?: return` —— 建不出空会话就静默返回
 * （S3 起：建不出 = 正文总量达 `SessionQuota.MAX_TOTAL_CHARS`；此前是会话数达 50）。
 * 那是最安全的方向（不动比半迁移好），但**没有出声**：
 * 用户看到「我的旧聊天记录不见了」，真相却是"被配额挡住了，
 * 删掉一些旧会话重启就好"。两者的处置相反，所以必须分得出来。
 *
 * 反过来也要钉住：**没有旧文件**是绝大多数用户的常态，对它出声等于
 * 每次冷启动都报一次"迁移失败" —— 用户被吵到会去删数据。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

/** 与 `SessionStore.MigrateResult` 同形（复刻，不引用 android 依赖）。 */
private enum class MigrateResult { OK, NO_LEGACY, BLOCKED_BY_LIMIT, FAILED }

/**
 * 复刻里用来模拟"`createNoMigrate` 返回 null"的开关：达到它即视为建不出空会话。
 *
 * 真实触发条件是"正文总量顶到 `SessionQuota.MAX_TOTAL_CHARS`"（S3 起），
 * 本测试只关心"建不出 → 必须返回 BLOCKED_BY_LIMIT"这条**结局契约**，
 * 用一个整数阈值当替身即可。名字刻意不叫 `MAX_SESSIONS` —— 个数已不是闸门。
 */
private const val QUOTA_BLOCKS_AT = 50

/**
 * 复刻修复后的 `SessionStore.migrateIfNeeded`。
 *
 * @param existingSessions 库里已有几个会话
 * @param brokenDecode     旧文件读得出、但正文映射失败（模拟写入出错）
 */
private fun migrateNew(
    filesDir: File,
    existingSessions: Int,
    brokenDecode: Boolean = false,
): MigrateResult {
    val legacy = File(filesDir, "chat_session.json")
    if (!legacy.exists()) return MigrateResult.NO_LEGACY
    if (brokenDecode) return MigrateResult.FAILED
    // createNoMigrate：撞上限返回 null
    if (existingSessions >= QUOTA_BLOCKS_AT) return MigrateResult.BLOCKED_BY_LIMIT
    runCatching { legacy.delete() }
    return MigrateResult.OK
}

/**
 * 复刻**旧写法**：三种结局全塌成同一个静默返回（`?: return`）。
 * 它返回 `Unit`，所以这里用 `Boolean` 表示"迁移过"，供对照断言使用。
 */
private fun migrateOld(filesDir: File, existingSessions: Int): Boolean {
    val legacy = File(filesDir, "chat_session.json")
    if (!legacy.exists()) return false
    if (existingSessions >= QUOTA_BLOCKS_AT) return false   // 静默 return：什么都没有发生
    runCatching { legacy.delete() }
    return true
}

fun main() {
    println("=== 会话迁移行为复刻测试（S2）===")
    val ss = File("app/src/main/java/com/xiaowan/localinference/SessionStore.kt").readText()

    // 临时目录：先建**目录**再写旧文件。不用 `File.createTempFile` ——
    // 它会真的建一个文件，`delete()` + `mkdirs()` 的写法在无法建出目录时
    // 会静默退化成"往文件里写"，测试照跑、工作区里却留下垃圾。
    val root = File(System.getProperty("java.io.tmpdir"), "mig-test-" + System.nanoTime())
    fun tmpWithLegacy(text: String = "[{\"role\":\"user\",\"content\":\"旧提问\"}]"): Pair<File, String> {
        val d = File(root, "case-" + System.nanoTime())
        check(d.mkdirs()) { "建不出临时目录：$d" }
        File(d, "chat_session.json").writeText(text)
        return d to text
    }

    // ── 1. 核心：被上限挡住时必须**分得出来**，且旧文件仍在 ────────────────
    run {
        val (d, original) = tmpWithLegacy()
        val legacy = File(d, "chat_session.json")
        val prev = migrateOld(d, existingSessions = 50)
        ck("对照：旧写法把三种结局塌成同一个静默返回（返回 false，说不出为什么）", !prev)
        // ⚠ 这条对照要证的是"旧写法**说不出为什么**"，而它此前的形态同时踩了
        //   两个坑：
        //   ① **求值顺序把 d 用坏了**：旧写法"没被挡住"那一支会
        //      `legacy.delete()`，于是 `migrateOld(d, 0)` 一跑，`d` 里的旧文件
        //      就没了 —— 下面那条"旧写法下的旧文件**也**还在"（读 `legacy`）
        //      随即抛 FileNotFoundException，整条套件从这里崩掉。
        //   ② **断言本身恒真**：它比的是"`migrateOld(d,50)`（被挡住、静默 return）
        //      == `migrateOld(d,0)`"。可第二遍进来时文件已被①删掉，
        //      `!legacy.exists()` 直接返回 false —— 比的其实是 "false == false"，
        //      与"两种情形返回同一个值"毫无关系。
        //   修法：两边各用**自己的**临时目录，且比的是**真实形态**——
        //   旧写法在"没被挡住"时会删掉旧文件（说明它至少做了事），
        //   而在"被挡住"时**一个字节都不动** —— 用户从返回值里分不出是哪一种。
        //   这才是"无从知道"的物质内容。
        //   具体比三件事，缺一条都证不到"无从知道"：
        //     · 被挡住时 `migrateOld` 返回 **false**（= "没迁成"）；
        //     · 真的迁成了时它返回 **true**，可"迁成功了"与"被挡住了"在
        //       用户那一侧看到的是**同一件事**：旧记录不在了 / 没进来。
        //       旧写法的返回值只说"我这趟动了没动"，从来不说是**哪一种没动**；
        //     · 两人的**后果**截然不同：一个旧文件原封不动（还能重试），
        //       一个旧文件已被删掉（迁完了）—— 返回值里一个字都没提这件事。
        //   （这也正是 S2 要修的东西：`MigrateResult` 四格把这几件事分开报。）
        val notBlocked = tmpWithLegacy()
        val moved = migrateOld(notBlocked.first, 0)
        ck("对照：旧写法下用户无从知道是\"被上限挡住\"（返回值说不出来，只看得出\"没迁成\"）",
            prev == false && moved == true &&
                File(d, "chat_session.json").exists() &&
                !File(notBlocked.first, "chat_session.json").exists())

        val (d2, original2) = tmpWithLegacy()
        val r = migrateNew(d2, existingSessions = 50)
        ck("修复：撞上限时返回 BLOCKED_BY_LIMIT（不是一个笼统的\"失败\"）",
            r == MigrateResult.BLOCKED_BY_LIMIT)
        ck("修复：失败时旧文件仍在（下次启动会重试）",
            File(d2, "chat_session.json").readText() == original2)
        ck("修复：失败不删旧文件 —— 内容逐字节没变", File(d2, "chat_session.json").exists())
        // 清理上面第一份临时目录（那里刻意没删，正是对照）
        ck("对照：旧写法下的旧文件**也**还在（区别不在\"删不删\"，在\"说没说\"）",
            legacy.readText() == original)
    }

    // ── 2. 没有旧文件：绝大多数用户的常态，不许出声 ─────────────────────────
    run {
        val d = File(root, "case-" + System.nanoTime()).also { check(it.mkdirs()) { "建不出临时目录：$it" } }
        ck("没有旧文件时返回 NO_LEGACY（与\"被挡住\"分得开）",
            migrateNew(d, existingSessions = 3) == MigrateResult.NO_LEGACY)
        ck("NO_LEGACY 与 BLOCKED_BY_LIMIT 不是同一格",
            migrateNew(d, 3) != migrateNew(tmpWithLegacy().first, 50))
    }

    // ── 3. 正常迁移：落到 OK，且旧文件被回收 ──────────────────────────────
    run {
        val (d, _) = tmpWithLegacy()
        ck("正常迁移返回 OK", migrateNew(d, existingSessions = 3) == MigrateResult.OK)
        ck("正常迁移后旧文件被回收（不留着重复迁）", !File(d, "chat_session.json").exists())
    }

    // ── 4. 写入出错与上限挡住必须是**两格**（处置相反）──────────────────────
    run {
        val (d, original) = tmpWithLegacy()
        val r = migrateNew(d, existingSessions = 3, brokenDecode = true)
        ck("写入出错时返回 FAILED（不是 OK、也不是 BLOCKED_BY_LIMIT）", r == MigrateResult.FAILED)
        ck("写入出错时旧文件仍在（不许把\"迁移失败\"变成\"删除成功\"）",
            File(d, "chat_session.json").readText() == original)
        ck("FAILED 与 BLOCKED_BY_LIMIT 是两格（一个看日志、一个删会话）",
            r != MigrateResult.BLOCKED_BY_LIMIT)
        ck("四格互不相等（塌成两三格就再也分不出处置）",
            listOf(MigrateResult.OK, MigrateResult.NO_LEGACY, MigrateResult.BLOCKED_BY_LIMIT, MigrateResult.FAILED).toSet().size == 4)
    }

    // ── 5. 边界：刚好在 49 / 50 / 51 三个点上的行为 ────────────────────────
    run {
        // ⚠ QUOTA_BLOCKS_AT = 50 是"建不出空会话"的**替身**，不是"会话个数上限"。
        //   S3 之后真实闸门是正文总字符数，而本测试只关心"建不出 → BLOCKED_BY_LIMIT"
        //   这条**结局契约**（见 QUOTA_BLOCKS_AT 上方的说明）。所以这里探的是
        //   "替身阈值两侧"的边界，不是"50 个会话这个数"。
        ck("替身阈值之下仍能迁（没有提前一步就放弃）",
            migrateNew(tmpWithLegacy().first, 49) == MigrateResult.OK)
        ck("50 个会话时被挡住",
            migrateNew(tmpWithLegacy().first, 50) == MigrateResult.BLOCKED_BY_LIMIT)
        ck("51 个会话时也被挡住（不是只判等于 50）",
            migrateNew(tmpWithLegacy().first, 51) == MigrateResult.BLOCKED_BY_LIMIT)
    }

    // ── 6. 实现侧不变量：失败判据必须排在删旧文件之前 ──────────────────────
    run {
        val body = ss.substringAfter("fun migrateIfNeeded(ctx: Context): MigrateResult")
        val failAt = body.indexOf("if (!ok) return MigrateResult.FAILED")
        val delAt = body.indexOf("legacy.delete()")
        ck("实现：失败判据在删旧文件之前（行序，不是\"两个都有\"）",
            failAt in 0 until delAt)
        ck("实现：上限挡住那一步用的是 ?: return BLOCKED_BY_LIMIT（不是裸 return）",
            body.contains("?: return MigrateResult.BLOCKED_BY_LIMIT"))
        ck("实现：旧写法 `createNoMigrate(ctx) ?: return` 已绝迹",
            !Regex("createNoMigrate\\(ctx\\) \\?: return\\n").containsMatchIn(body))
    }

    println(if (f == 0) "=== 会话迁移复刻测试全部通过 ===" else "=== 会话迁移复刻测试失败 $f 条 ===")
    if (f != 0) kotlin.system.exitProcess(1)
}
