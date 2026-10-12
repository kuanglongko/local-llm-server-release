package com.xiaowan.localinference

/**
 * `SessionQuota`（S3：上限从「会话个数」换成「正文总字符」）的**宿主侧行为测试**。
 *
 * 为什么不能只靠 `run_session_quota_guard.sh`：守卫钉的是"结构没改回去"
 * （闸门挂在新增路径上、`MAX_SESSIONS_WARN` 不参与拒绝、导入按预算收），
 * 它证明不了"这套算术在**边界输入**下真的对"。而配额的本体就是算术，
 * 边界恰恰是它全部的难点：
 *
 *   · `已用 + 本次 == 上限` 必须**收下**（边界取闭区间）—— 差一就拒会让
 *     用户对着"刚好放得下"的文件反复重试；
 *   · `已用 + 本次 == 上限 + 1` 必须**拒绝**；
 *   · 批量导入在一半处撞线时，能收的**条数**要按从前往后算准，
 *     而且**遇到第一条收不下的就停**（跳过它继续试后面会让结果随文件顺序变，
 *     用户数不清到底进去几个）。
 *
 * `SessionQuota` 是纯逻辑（无 android 依赖），所以这里直接用**真实现**断言，
 * 不像 `SessionCapTest` 那样复刻控制流。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

// 非 const：`.toLong()` 让下面所有字面量都对得上 decide/acceptCount 的 Long 形参
private val LIMIT = SessionQuota.MAX_TOTAL_CHARS.toLong()

fun main() {
    // ── 1. 单次准入：边界是闭区间 ─────────────────────────────────────────
    ck("空库放空会话：允许", SessionQuota.decide(0L, 0L).allowed)
    ck("刚好用满：允许（== 上限必须收下）", SessionQuota.decide(LIMIT - 1, 1L).allowed)
    ck("刚好用满（整批）：允许", SessionQuota.decide(0L, LIMIT).allowed)
    ck("超一个字符：拒绝", !SessionQuota.decide(0L, LIMIT + 1).allowed)
    // ⚠ 「已满 + 本次为 0」必须**放行** —— 这一格是本轮修掉的一个真缺陷。
    //   旧判据是 `used + incoming <= 上限`，在 `used == 上限 && incoming == 0`
    //   时判拒绝；而这一格恰好是 `create` / `createNoMigrate` 唯一会问的那一格
    //   （新会话正文是 0）。后果：用户顶到上限之后**再也建不出一个空会话来整理**，
    //   只能删旧会话腾地方，却没有落脚点搬内容 —— 正是类注释里第一条口径
    //   "只卡新增，不卡已存在（否则陷入『打不开也删不掉』的死锁）"要防的那件事。
    //   零增长不是"新增"，它是整理动作的入口。
    ck("已满再放空会话：允许（零增长不占额度，是整理旧会话的落脚点）",
        SessionQuota.decide(LIMIT, 0L).allowed)
    ck("已满再放空会话：remaining 仍为 0（放行不等于变出额度）",
        SessionQuota.decide(LIMIT, 0L).remaining == 0L)
    ck("已满再放空会话：over 为 0（放行就没有超出量）",
        SessionQuota.decide(LIMIT, 0L).over == 0L)
    ck("已满放一个字符：仍然拒绝（那才是真的新增）", !SessionQuota.decide(LIMIT, 1L).allowed)
    ck("负数（调用方算错）与 0 同处置：放行，且不把额度算小",
        SessionQuota.decide(LIMIT, -5L).allowed)

    // ── 2. Decision 的读数 ────────────────────────────────────────────────
    val d = SessionQuota.decide(LIMIT - 500, 100L)
    ck("remaining = 上限 - 已用（未含本次）", d.remaining == 500L)
    ck("未拒时 over 为 0", d.over == 0L)

    val r = SessionQuota.decide(LIMIT, 300L)
    ck("已满时 remaining 不为负", r.remaining == 0L)
    ck("被拒时 over = 超出量", r.over == 300L)
    ck("被拒：allowed=false", !r.allowed)

    // ── 3. 批量：能收几条 ────────────────────────────────────────────────
    ck("空库、刚好放两份：收 2 条",
        SessionQuota.acceptCount(0L, listOf(LIMIT / 2, LIMIT / 2)) == 2)
    ck("第三份放不下：只收 2 条（从前往后）",
        SessionQuota.acceptCount(0L, listOf(LIMIT / 2, LIMIT / 2, 1L)) == 2)
    ck("第一条就超限：收 0 条", SessionQuota.acceptCount(0L, listOf(LIMIT + 1)) == 0)

    // 遇到收不下的**就停**，不跳过它继续试后面的 —— 否则结果随顺序变。
    // 这条用例正是"跳过"与"停下"的分水岭：第三份很大、第四份很小，
    // 跳过法会得出 2（收下第 1、2、4），停下法得出 2（只收第 1、2）。
    // 用一个更清晰的例子：第 2 份超大、第 3 份很小。
    ck("第二条放不下就停（不跳过去收第三条）",
        SessionQuota.acceptCount(0L, listOf(10L, LIMIT, 10L)) == 1)
    ck("第一条放不下就停（后面再小也不收）",
        SessionQuota.acceptCount(0L, listOf(LIMIT, LIMIT + 1, 1L)) == 1)

    ck("已用一半：只收得下剩下一半的量",
        SessionQuota.acceptCount(LIMIT / 2, listOf(LIMIT / 4, LIMIT / 2)) == 1)
    ck("空列表：收 0 条", SessionQuota.acceptCount(0L, emptyList()) == 0)

    // ── 4. 软提示线不参与拒绝 ─────────────────────────────────────────────
    ck("软提示线是 200，且大于旧的硬闸门 50", SessionQuota.MAX_SESSIONS_WARN == 200)
    ck("会话数到线：shouldWarnCount=true",
        SessionQuota.shouldWarnCount(SessionQuota.MAX_SESSIONS_WARN))
    ck("会话数没到线：false", !SessionQuota.shouldWarnCount(SessionQuota.MAX_SESSIONS_WARN - 1))
    // 关键不变量：软提示线**不**影响准入 —— 会话数再多，只要没顶到字符上限就能新建。
    ck("会话数远超提示线但正文很少：仍允许新增（提示线不拦人）",
        SessionQuota.decide(1000L, 0L).allowed)

    println(if (f == 0) "SessionQuota: all ok" else "SessionQuota: $f FAIL")
    if (f != 0) throw AssertionError("$f failed")
}
