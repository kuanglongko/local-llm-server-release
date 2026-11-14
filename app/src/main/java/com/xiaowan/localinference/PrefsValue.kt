package com.xiaowan.localinference

import android.content.Context

/**
 * 一格 `SharedPreferences` 的**最小值类**：读 / 写 / 有没有设过 / 抹掉。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么需要它
 * ═══════════════════════════════════════════════════════════════════════
 * 本仓库有七个"一格设置"对象（批大小 / 每条 ctx / 两道门 / 条数上限 / 水位 /
 * 主动治理 / 暗色档位），每个对象里都原样写着同一套五句：
 *
 *     private fun prefs(ctx) = ctx.getSharedPreferences(Prefs.RAG, MODE_PRIVATE)
 *     fun get(ctx)  = normalize(prefs(ctx).getInt(KEY, DEFAULT))
 *     fun set(ctx,v) { prefs(ctx).edit().putInt(KEY, normalize(v)).apply() }
 *     fun isSet(ctx) = prefs(ctx).contains(KEY)
 *     fun clear(ctx) { prefs(ctx).edit().remove(KEY).apply() }
 *
 * 五句本身都很短，但**错法全是静默的**：
 *   · `set` 忘了归一 -> 一个跑不了的值落了盘，下次读回来才炸；
 *   · `clear` 写成 `putX(默认值)` -> 键还在 -> [isSet] 仍报"用户设过"
 *     （"还原默认"没还原，界面上看不出来）；
 *   · `get` 的默认值抄错一位 -> 升级上来的用户会看到另一档默认值。
 * 七份各自演化时，任何一份分叉都不会有编译错误、也不会有日志。
 *
 * 收口到这里之后，上面四条语义**只有一处实现**；各个对象保留自己的
 * [IntPref.key] / 默认值 / `normalize` 纯函数与文档 —— 那才是各自的知识。
 *
 * ⚠ 存储名仍由 [Prefs] 提供（store 名的唯一出处），本文件不写任何字面量。
 * ⚠ 归一函数只允许是**纯函数**（这些对象现在的 `normalize` 都只转调
 *   `LlmEngine.kEmbed*Limit` / `ContextWatermarkPct.normalize`）：它会在
 *   `get` 与 `set` 两侧各调一次，不碰磁盘、不碰 Android。
 */
class IntPref(
    private val store: String,
    /** 键名。留成公开属性，守卫与设置页对账时要读它。 */
    val key: String,
    private val unset: Int,
    private val normalize: (Int) -> Int,
) {

    /**
     * 读当前值（**归一后**）。
     *
     * 归一拍在读这一侧，而不是只写在写那一侧：磁盘上那份可能来自旧版本、
     * 或被人手改过（`adb shell` 改 prefs 是真实会发生的）。
     */
    fun get(ctx: Context): Int = normalize(prefs(ctx).getInt(key, unset))

    /** 写当前值（先归一，免得把一个跑不了的值落盘）。 */
    fun set(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(key, normalize(value)).apply()
    }

    /** 这一格有没有被显式设过（`/health` 与 UI 的"要不要显示默认"都要它）。 */
    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(key)

    /**
     * 抹掉这一格（回到"没设过"）。
     *
     * ⚠ 必须是 `remove(key)`，**不能**写一个默认值进去：[isSet] 的判据是
     *   "键在不在"，写默认值会让"还原默认"之后读数仍说"用户设过"。
     */
    fun clear(ctx: Context) { prefs(ctx).edit().remove(key).apply() }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(store, Context.MODE_PRIVATE)
}

/**
 * 布尔型的同款值类。
 *
 * 布尔没有"非法值"，所以没有 `normalize` —— 这是它与 [IntPref] 唯一的
 * 语义差别（`get` / `set` / `isSet` / `clear` 的四条纪律完全相同）。
 */
class BoolPref(
    private val store: String,
    /** 键名。留成公开属性，守卫与设置页对账时要读它。 */
    val key: String,
    private val unset: Boolean,
) {

    /** 读当前值（缺键时落 [unset]）。 */
    fun get(ctx: Context): Boolean = prefs(ctx).getBoolean(key, unset)

    /** 写当前值。 */
    fun set(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(key, value).apply()
    }

    /** 这一格有没有被显式设过（设置页要区分"默认关"与"我关的"）。 */
    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(key)

    /**
     * 抹掉这一格（回到版本默认）。
     *
     * ⚠ 必须是 `remove(key)`，**不能**写一个默认布尔进去：[isSet] 的判据是
     *   "键在不在"，写默认值会让"还原默认"之后不再跟着版本默认走。
     */
    fun clear(ctx: Context) { prefs(ctx).edit().remove(key).apply() }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(store, Context.MODE_PRIVATE)
}

/**
 * 字符串型的同款值类（RAG 的"当前库名"那几格）。
 *
 * ⚠ `get` 仍 `orEmpty()`：`getString` 的返回类型是 `String?`，而调用方
 *   （库名、远端库名）一直把 `null` 与空串当同一件事（"" = 没配）。
 *   把 `null` 漏给调用方只会让每个比较点多一次判空，而漏掉一次的后果是
 *   `NPE` 或"库名看起来不是空、其实什么都没配"。
 */
class StringPref(
    private val store: String,
    /** 键名。留成公开属性，守卫与设置页对账时要读它。 */
    val key: String,
    private val unset: String,
) {

    /** 读当前值（缺键时落 [unset]；存过 `null` 不可能，但类型上仍兜一层）。 */
    fun get(ctx: Context): String = prefs(ctx).getString(key, unset).orEmpty()

    /** 写当前值。 */
    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }

    /** 这一格有没有被显式设过。 */
    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(key)

    /** 抹掉这一格（回到没设过）。 */
    fun clear(ctx: Context) { prefs(ctx).edit().remove(key).apply() }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(store, Context.MODE_PRIVATE)
}
