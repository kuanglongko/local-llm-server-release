package com.xiaowan.localinference

/**
 * KV 账本快照的**下标与形状**集中处（第 7 批 H6 的 KvSnapshot.kt）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么把下标收在这里
 * ══════════════════════════════════════════════════════════════════════════
 * native 交回来的快照是**位置约定**（`int[5] = {used, reuse, prefill, rounds, lcp}`、
 * 账本第 24 格是 spare、第 26..28 是水位三格）。这些数字一旦散在
 * `LlmEngine` 的几十个 getter 里，"把 lcp 读成 rounds"就是一次
 * 合法编译、合法运行、读数看着正常的错误（真机 `rounds:901` 的现场）。
 *
 * 本对象只做两件事：
 *   · 把"第几格是什么"写成**具名函数**，getter 只负责调 native 与兜底；
 *   · 把"缺省值/长度不够"的折法也放在同一处（例如水位三格缺省 `-1`）。
 *
 * ⚠ 刻意不碰任何 native 声明：`LlmEngine` 仍是唯一的 native 门面，
 *   本对象只接收它已经取到的数组。这样宿主单测可以不加载 .so 直接测下标。
 */
object KvSnapshot {

    /**
     * `/health` 顶层那五个 `kv_*` 实际读哪一格：`rounds` 最大者，并列取下标小的。
     *
     * 一格都没跑过（冷启动）退回 [legacySlot] —— 那时谁是 0 都一样，
     * 而"退回 0"与"读过 0"在读数上必须同形（否则冷启动那一屏又是两个数字打架）。
     */
    fun liveSlot(all: Array<IntArray>, legacySlot: Int): Int {
        if (all.isEmpty()) return legacySlot
        var best = -1
        var bestRounds = 0
        for (i in all.indices) {
            val rounds = roundsInSlotSnapshot(all[i])
            if (rounds > bestRounds) { best = i; bestRounds = rounds }
        }
        return if (best < 0) legacySlot else best
    }

    /** `nativeKvCacheStatsSlot` 的第 3 格（`[2]`）——**不是** lcp（`[3]`）。 */
    fun roundsOf(stats: IntArray): Int = stats.getOrElse(2) { 0 }

    /**
     * [LlmEngine.kvStatsAllSlots] 每格五元组的第 4 格（`[3]`）。
     *
     * ⚠ 与 [roundsOf] **不是同一个下标**：那是 native 的原始快照，这是宿主
     * 拼好后的 `{used, reuse, prefill, rounds, lcp}`。混用就是把 lcp 当轮次
     * （真机 `rounds:901` 的同一类错误）。
     */
    fun roundsInSlotSnapshot(stats: IntArray): Int = stats.getOrElse(3) { 0 }

    /** 账本第 24 格：整格还能不能再接一轮（见 `kvSpareOf` 的说明）。 */
    fun spareOf(ledger: IntArray): Int = ledger.getOrElse(24) { 0 }

    /** 账本第 26..28 格：`{watermark_pct, drop_room, drop_overshoot}`。 */
    fun watermarkOf(ledger: IntArray): IntArray = intArrayOf(
        ledger.getOrElse(26) { -1 },
        ledger.getOrElse(27) { -1 },
        ledger.getOrElse(28) { -1 },
    )

    /** 生成停止四格；长度对不上时返回 [defaultGenStop]（`-1` = 没报）。 */
    fun genStopOf(raw: IntArray?): IntArray = raw ?: defaultGenStop()

    fun defaultGenStop(): IntArray = IntArray(4) { -1 }
}
