package com.xiaowan.localinference

import android.content.Context

/**
 * 「服务端主动治理」（`ContextBudget.plan` 的 `proactive`）这个开关的持久化（ISSUE #452 第 2 步）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么它可以关，而且默认就该关
 * ══════════════════════════════════════════════════════════════════════════
 * 这一层（水位 85% + 越线丢一半 + 估算系数校准）是为了**替一个不知道自己多长的
 * 客户端**兜底而造的。客户端一旦自己按 Max Context 裁，它的每一个动作都变成负收益，
 * 而且每一条都有真机读数：
 *
 *   · 估算偏高 -> 提前丢历史（`真 2076 / 估 2585`，离硬墙还有 1600 枚就开始丢）；
 *   · 越线就把 `gen` 削到地板 -> 之后每一轮只吐几个字；
 *   · 丢了历史 -> 本轮 prompt 成了上一轮的**后缀** -> `canAlign` 差一枚自锁 ->
 *     每轮全量 prefill。
 *
 * 所以默认 `false`：**装得下就一枚不丢**，只在真装不下（越过整格）时才让。
 * 兜底那几条一条都不撤：位置线（每条 seq 的容量）、装不下时先削 gen 再丢历史、
 * 提问自己也装不下时回 400 —— App 之外还有 curl / SDK / 第三方客户端。
 *
 * ⚠ 水位**保留**（见 [ContextWatermark]）：它仍然算、仍然报（`local_ctx.watermark`、
 *   `/health` 的 `drop_room`），只是**不再自己触发丢弃** —— 客户端要靠那几格判断
 *   "该不该把自己的 Max Context 调小"。
 * ⚠ 开关写成"关闭 = 默认值"而不是"关闭 = 存一个 false"：[isSet] 才能区分
 *   "没碰过"与"显式关掉"，而这两件事在设置页上长得一样、含义不同
 *   （前者跟着版本默认走，后者钉死）。
 */
object CtxProactivePref {

    /** 键名。与 [ContextWatermark.KEY] 同一个 store（都是上下文这一族的设置）。 */
    const val KEY = "ctx_proactive_trim"

    /** 读写口；四条纪律见 [BoolPref]（布尔没有非法值，故无归一）。 */
    private val pref = BoolPref(Prefs.RAG, KEY, ContextBudget.PROACTIVE_DEFAULT)

    /**
     * 当前是否开启主动治理。
     *
     * 读出来的值直接喂 [ContextBudget.plan] 的 `proactive`；缺省落
     * [ContextBudget.PROACTIVE_DEFAULT]（**关**）。
     */
    fun get(ctx: Context): Boolean = pref.get(ctx)

    /** 写开关（布尔本身没有"非法值"，不做归一）。 */
    fun set(ctx: Context, on: Boolean) = pref.set(ctx, on)

    /** 这一格有没有被显式设过（设置页要区分"默认关"与"我关的"）。 */
    fun isSet(ctx: Context): Boolean = pref.isSet(ctx)

    /**
     * 抹掉这一格（回到版本默认）。**「还原默认设置」必须调它** ——
     * 写一个 `false` 进去会让 [isSet] 仍为真，于是"还原默认"之后
     * 这个开关不再跟着版本默认走，而屏幕上没有任何东西说这件事。
     */
    fun clear(ctx: Context) = pref.clear(ctx)
}
