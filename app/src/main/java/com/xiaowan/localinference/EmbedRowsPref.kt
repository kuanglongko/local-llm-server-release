package com.xiaowan.localinference

import android.content.Context

/**
 * 「嵌入每批条数上限」这个**用户可调参数**的持久化与归一（0.9.242）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 它调的不是"一次塞几条"，而是**这一份 ctx 有几个 seq**
 * ═══════════════════════════════════════════════════════════════════════
 * 用户问的是：*"有必要加一个参数么，让用户可以设置 kEmbedMaxCtx / kEmbedGate
 * 和 kEmbedBatchRows 么，这样可以让低端机调低，降低内存占用"*。
 *
 * 三个数里，[EmbedBatchPref]（每批编几条）**不在此列**：那是"循环里一次塞几条"，
 * 只影响吞吐，**不改** ctx 容量、不省一 MB 内存（`EmbedCtxPref` 的对象头
 * 就点明了这件事）。而这一格拧的是 native 的 `n_seq_max` ——
 * 库按 `n_ctx = 每条预算 × n_seq_max` 分配 KV / output buffer，所以它能省内存。
 *
 * ⚠ 两者**必须分开**而不是合成一格：一个改的是"编多快"，一个改的是"吃多少"。
 *   合成一格时，用户为了省内存把条数调到 1，会顺手把合批的收益也关掉，
 *   而屏幕上没有任何东西说这两件事本来是分开的。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么默认值是 0（而不是 4）
 * ═══════════════════════════════════════════════════════════════════════
 * 与 [EmbedBatchPref] / [EmbedCtxPref] 同一条理由：0 = "没设过"（= native 用
 * 天花板那一档，即 0.9.241 的行为）。它与"设过 4"**行为等价**，但在
 * `/health` 上不同 —— `embed_seq_max_set` 要能区分"用户没碰过这一格"与
 * "用户显式设了 4"。混成一个读数时，"我上次调过没有"就没有基线可比。
 */
object EmbedRowsPref {

    /** 键名。与 [EmbedBatchPref] / [EmbedCtxPref] 同一个 store。 */
    const val KEY = "embed_seq_max"

    /** `0` = 没设过（用天花板那一档）；见对象头那段说明。 */
    const val UNSET = 0

    /** 读写口；四条纪律见 [IntPref]。 */
    private val pref = IntPref(Prefs.RAG, KEY, UNSET) { normalize(it) }

    /** 读当前设置（归一后：`1..kEmbedBatchRows`）。 */
    fun get(ctx: Context): Int = pref.get(ctx)

    /** 写当前设置（先归一，免得把一个跑不了的值落盘）。 */
    fun set(ctx: Context, value: Int) = pref.set(ctx, value)

    /** 这一格有没有被显式设过（`/health` 与设置页的"要不要显示默认"都要它）。 */
    fun isSet(ctx: Context): Boolean = pref.isSet(ctx)

    /**
     * 抹掉这一格（回到"没设过"）。**「还原默认设置」必须调它**。
     *
     * 与 [EmbedBatchPref.clear] 同一条理由：`isSet()` 的判据是"键在不在"，
     * 所以还原**不能**靠写一个默认值进去 —— 键仍在 = 读数仍报"用户设过"。
     * 而这一格尤其要紧：它是 `n_ctx = 每条预算 × 它` 的乘数之一，
     * 没删干净时用户下次加载嵌入模型用的还是他上次设的条数，
     * "内存为什么还这么高/为什么合批变慢了"的答案就藏在这个他没设过的值里。
     */
    fun clear(ctx: Context) = pref.clear(ctx)

    /**
     * 把任意输入钳到**native 真能跑的区间**（`1..kEmbedBatchRows`，非正当天花板）。
     * 纯函数（只转调 [LlmEngine.kEmbedBatchLimit]，不碰文件），设置页与加载两条路共用。
     *
     * ⚠ 转调的是**批条数**那个钳（不是 ctx 或门那两个）：它钳的正是
     * `n_seq_max`，而 `n_seq_max` 的上限就是 native 的 `kEmbedBatchRowsCap`。
     */
    fun normalize(value: Int): Int = LlmEngine.kEmbedBatchLimit(value)
}
