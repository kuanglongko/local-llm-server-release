package com.xiaowan.localinference

import android.content.Context

/**
 * 「嵌入两道门」（`n_batch` / `n_ubatch`）这个**用户可调参数**的持久化与归一（0.9.242）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 它省的是 compute buffer，不是 KV
 * ═══════════════════════════════════════════════════════════════════════
 * 用户在 ISSUE 里问的是"能不能让低端机把 `kEmbedGate` 也调低"。答案是可以，
 * 但要**说清它省的是哪一份**：两道门按**整批 token 数**切内部缓冲与计算图，
 * 而 KV / output buffer 是按 `n_ctx` 分配的（那是 [EmbedCtxPref] 那一格）。
 *
 * 所以设置页这两格必须**分开写清各自省什么** —— 合成一格（"内存档位"）时，
 * 用户把门调到 512 会以为内存该降，而 KV 一格没动。
 *
 * ⚠ 天花板 [LlmEngine.kEmbedGateMax] 与 [LlmEngine.kEmbedCtxMax] 同值（都是 2048），
 *   **但不是同一个常量**：门给大了的表现是 compute buffer 暴涨（真机上
 *   "加载很慢然后 OOM"），ctx 给大了的表现是"嵌入模型加载失败"。两种失效形态
 *   完全不同，读数也必须分得开（`/health` 报 `embed_gate` / `embed_gate_live`）。
 *
 * 默认值 0 = 没设过（native 用天花板那一档，即 0.9.241 的行为）——
 * 与另外三格同一条理由：`isSet` 要能区分"没碰过"与"显式设了 2048"。
 */
object EmbedGatePref {

    /** 键名。与 [EmbedCtxPref] / [EmbedRowsPref] 同一个 store。 */
    const val KEY = "embed_gate"

    /** `0` = 没设过（用天花板那一档）；见对象头那段说明。 */
    const val UNSET = 0

    /** 读写口；四条纪律见 [IntPref]。 */
    private val pref = IntPref(Prefs.RAG, KEY, UNSET) { normalize(it) }

    /** 读当前设置（归一后：`1..kEmbedGateMax`）。 */
    fun get(ctx: Context): Int = pref.get(ctx)

    /** 写当前设置（先归一，免得把一个跑不了的值落盘）。 */
    fun set(ctx: Context, value: Int) = pref.set(ctx, value)

    /** 这一格有没有被显式设过。 */
    fun isSet(ctx: Context): Boolean = pref.isSet(ctx)

    /**
     * 抹掉这一格（回到"没设过"）。**「还原默认设置」必须调它**。
     *
     * 与 [EmbedCtxPref.clear] 同一条理由：删键才是"回到默认"的唯一真值形态。
     * 这一格不进索引指纹（它不改向量语义），所以留着一个旧值时**当场看不出来**，
     * 只在下次用户纳闷"compute buffer 怎么还是那么大"时才暴露。
     */
    fun clear(ctx: Context) = pref.clear(ctx)

    /** 钳到 native 真能跑的区间（`1..kEmbedGateMax`，非正当天花板）。 */
    fun normalize(value: Int): Int = LlmEngine.kEmbedGateLimit(value)
}
