package com.xiaowan.localinference

import android.content.Context

/**
 * 「一次合批编几条」这个**用户可调参数**的持久化与归一（ISSUE #269 第 2 步）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么它必须存在
 * ═══════════════════════════════════════════════════════════════════════
 * 合批（一次前向编 n 条）落在 main 上之后，批大小 8 是**写死的常量**：
 * 用户改不了、看不到，日志里也只有回落时才有一行。一个"新特性"用户摸不着，
 * 等于没有 —— 这与本 issue 最初那个"线程数输入框对建库完全无效"是**同一个病**：
 * 参数在某处写死、或在某处断掉，界面上看不出来。
 *
 * 所以这一层把那个数提成设置项，并把它收口到**唯一一处**：
 *   · [KEY]    —— `SharedPreferences` 里的键（与别的 RAG 设置同一个 store）；
 *   · [get]    —— 读（带默认值）；[set] —— 写；
 *   · [normalize] —— 把任意输入钳到 native 真能跑的区间（纯函数，可离线单测）。
 *
 * ⚠ [normalize] 与 [LlmEngine.kEmbedBatchLimit] 是**同一条判据**的两个名字：
 *    前者是"这个值能不能存"，后者是"这个值能不能跑"。两处各写一遍时迟早分岔，
 *    而分岔的症状是"输入框里 16、日志里 8，界面上一个字都没有"。
 *    所以 [normalize] 直接**转调** [LlmEngine.kEmbedBatchLimit]，不自己再钳一次。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么默认值是 0（而不是 8）
 * ═══════════════════════════════════════════════════════════════════════
 * 0 = "没设过"。它与"设过 8"在**行为上等价**，但在可观测性上不同：
 * `/health` 的 `embed_batch_size` 要能区分"用户没碰过这一格"与"用户显式设了 8"
 * —— 前者是升级上来的绝大多数人，后者是主动调过的人。把两者混成一个读数，
 * "这个值到底生效了没有"就没有基线可比（与 `mmap` 的那个 `null` 同一条理由）。
 */
object EmbedBatchPref {

    /**
     * 键名。与 [RagInject.PREFS] 同一个 store（这些键都是"知识库怎么用"的那一类）。
     *
     * ⚠ 键名里**不许**出现 `batch_size` 之外的别名：改名不是兼容问题（读不到
     *    就用默认值），但它会让"我上次设过"这件事静默消失 —— 用户只能得出
     *    "这个版本把设置重置了"。
     */
    const val KEY = "embed_batch_size"

    /** `0` = 没设过（用默认值）；见对象头那段说明。 */
    const val UNSET = 0

    private const val PREFS = "rag_store"

    /**
     * 读当前设置。
     *
     * 返回的是**归一后**的值（`1..kEmbedBatchRows`），不是磁盘上的原始串：
     * 磁盘上那份可能来自旧版本、或被人手改过。把归一放在**读**这一侧（而不是
     * 只在写那一侧），是因为"写"可能来自任何一处（含手工 `adb shell`）——
     * 而读到 0 会让建库进死循环（见 [LlmEngine.kEmbedBatchLimit]）。
     */
    fun get(ctx: Context): Int = normalize(prefs(ctx).getInt(KEY, UNSET))

    /** 写当前设置（先归一，免得把一个跑不了的值落盘）。 */
    fun set(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY, normalize(value)).apply()
    }

    /** 这一格有没有被显式设过（`/health` 与 UI 的"要不要显示默认"都要它）。 */
    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(KEY)

    /**
     * 抹掉这一格（回到"没设过"）。**「还原默认设置」必须调它** —— 见下面那段。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么"还原默认"必须**真的删掉键**，而不是写一个默认值进去
     * ═══════════════════════════════════════════════════════════════════════
     * `isSet()` 的判据是"键在不在"。若还原时写一个默认值（比如 8）进去，
     * 键**仍然在** -> `isSet()` 仍为 true -> `/health` 的 `..._set` 报"用户设过"
     * ——用户以为自己"还原成默认了"，而读数说他设过。更坏的是 [EmbedBatchPref]
     * 这一格**进索引指纹**（`g`）：`g` 与默认值相同时索引照样能用，所以
     * 这个谎**当场看不出来**，只在下次用户把批大小从"他以为的默认"改走时才暴露。
     * 删键才是"回到默认"的唯一真值形态（与 [UNSET] 的定义一致）。
     */
    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 把任意输入钳到**native 真能跑的区间**。纯函数（只转调 [LlmEngine.kEmbedBatchLimit]，
     * 不碰文件），供设置页与建库两条路共用。
     *
     * 之所以不在这里直接 `coerceIn(1, 8)`：上限是 **native 的 `n_seq_max`**，
     * 而它是"建 ctx 时写了什么"决定的。写死 8 的话，哪天 native 把那个数改了，
     * 设置页还按 8 限制 —— 而用户填 8 却被 native 拒（上限变 6 时）或填不上去
     *（上限变 16 时），两种都不报错。转调那一个函数，改一处就全改。
     */
    fun normalize(value: Int): Int = LlmEngine.kEmbedBatchLimit(value)
}
