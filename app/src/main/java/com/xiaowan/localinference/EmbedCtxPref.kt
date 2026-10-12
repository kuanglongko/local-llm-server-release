package com.xiaowan.localinference

import android.content.Context

/**
 * 「嵌入模型每条文本的上下文预算」这个**用户可调参数**的持久化与归一（0.9.212）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么它必须存在（而不是把 512 藏在 native 里）
 * ═══════════════════════════════════════════════════════════════════════
 * 用户的原话是 *"增加可调的输入框，成功了再加一个内存占用为啥高的提示"* ——
 * 而"内存占用为啥高"的答案**就在这一格**，不在批大小那格：
 *
 *   · 批大小（[EmbedBatchPref]）只决定"一次塞几条" —— 它**不改** ctx 容量，
 *     所以把它从 8 调到 1，内存一 MB 都不会降；
 *   · 这一格是**每条**文本的预算，native 侧 `n_ctx = 预算 × kEmbedBatchRows`
 *     （再按 [LlmEngine.kEmbedCtxMax] 钳），而 `embeddings=true` 时的 KV、
 *     output buffer、compute buffer 三项**都按这个总量分配**。
 *
 * 所以"内存高"与"能编多长的块"是同一个旋钮的两端，而它此前是 native 里的
 * 一个常量（`kEmbedDefaultCtx = 512`）。用户只能看到结果（"占用怎么这么高"），
 * 看不到原因，也改不动 —— 与本 issue 最初那个"线程数输入框对建库完全无效"
 * 是同一族：**参数在某处写死，界面上看不出来**。
 *
 * ⚠ [normalize] 与 [LlmEngine.kEmbedCtxLimit] 是**同一条判据**的两个名字：
 *    前者是"这个值能不能存"，后者是"这个值能不能跑"。两处各写一遍迟早分岔，
 *    而分岔的症状是"输入框里 4096、日志里跑 512，界面上一个字都没有"。
 *    所以 [normalize] 直接**转调** [LlmEngine.kEmbedCtxLimit]，不自己再钳一次
 *    （与 [EmbedBatchPref.normalize] 完全同一形态）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么默认值是 0（而不是 512）
 * ═══════════════════════════════════════════════════════════════════════
 * 与 [EmbedBatchPref] 同一条理由：0 = "没设过"。它与"设过 512"在**行为上等价**
 * （native 侧 `nCtx <= 0` 时取 `kEmbedDefaultCtx`），但在可观测性上不同 ——
 * `/health` 的 `embed_ctx_per_seq_set` 要能区分"用户没碰过这一格"与"用户显式
 * 设了 512"。把两者混成一个读数，"这个值到底生效了没有"就没有基线可比。
 */
object EmbedCtxPref {

    /**
     * 键名。与 [EmbedBatchPref] / [RagInject.PREFS] 同一个 store。
     *
     * ⚠ 名字里**不许**出现别名：改名不是兼容问题（读不到就用默认值），
     *    但它会让"我上次设过"这件事静默消失 —— 用户只能得出"这个版本把设置重置了"。
     */
    const val KEY = "embed_ctx_per_seq"

    /** `0` = 没设过（用 native 的默认值）；见对象头那段说明。 */
    const val UNSET = 0

    private const val PREFS = "rag_store"

    /**
     * 读当前设置（**归一后**的值）。
     *
     * 归一拍在读这一侧而不是只写在写那一侧：磁盘上那份可能来自旧版本、
     * 或被人手改过（`adb shell` 改 prefs 是真实会发生的），而读到 0 或负数
     * 会一路传到 native（`nCtx` 为负时 native 回落到默认值，但"设置里写着 -5"
     * 这件事本身就不该存在）。
     */
    fun get(ctx: Context): Int = normalize(prefs(ctx).getInt(KEY, UNSET))

    /** 写当前设置（先归一，免得把一个跑不了的值落盘）。 */
    fun set(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY, normalize(value)).apply()
    }

    /** 这一格有没有被显式设过（`/health` 与设置页的"要不要显示默认"都要它）。 */
    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(KEY)

    /**
     * 抹掉这一格（回到"没设过"）。**「还原默认设置」必须调它**。
     *
     * 与 [EmbedBatchPref.clear] 同一条理由（那里写全了）：`isSet()` 的判据是
     * "键在不在"，所以"还原默认"**不能**靠写一个默认值进这一格 —— 键仍在
     * = 读数仍报"用户设过" = 用户以为自己还原了而读数说他没还原。
     *
     * ⚠ 这一格比批大小那格更值得删干净：它**不进索引指纹**，但它是
     *   `n_ctx = 它 × 批上限` 的乘数 —— 还原之后没删，用户下次加载嵌入模型
     *   用的还是他上次设的预算，而"内存为什么还这么高"的答案就藏在这个
     *   他没设过的值里。
     */
    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 把任意输入钳到**native 真能跑的区间**（`1..kEmbedCtxMax`，非正当默认）。
     * 纯函数（只转调 [LlmEngine.kEmbedCtxLimit]，不碰文件），设置页与加载两条路共用。
     */
    fun normalize(value: Int): Int = LlmEngine.kEmbedCtxLimit(value)
}
