package com.xiaowan.localinference

import android.content.Context

/**
 * 「暗色模式」三档的**纯逻辑**：判定 / 归一 / 计数（零 android 依赖，宿主可直跑）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么是**三档**而不是"一个开关 + 一个跟随系统开关"
 * ═══════════════════════════════════════════════════════════════════════════
 * 需求原文是「默认关闭，可选跟随系统」。这两句各自都是一种**用户意图**，
 * 而"跟随系统"本身也是一个终态：系统亮 = 亮、系统暗 = 暗。
 * 所以它天然是 `OFF / ON / SYSTEM` 三态，不是一个布尔加一个修饰位：
 *
 *   · 合成"off + follow=1"这种组合在行为上是**自相矛盾**的（关闭又跟随），
 *     一旦允许它存在，就必须在读取侧决定谁优先 —— 而那个决定没有任何
 *     用户能看见的依据，两个客户端改同一份 prefs 时会互相覆盖成不同的含义；
 *   · 三档单选与设置页既有的 **repack 三档**（没设过 / 关 / 开）是同一形态
 *     （同一个 `View.generateViewId()` + `RadioGroup.check()` 样板），
 *     用户在这一页学到的"点一下就是选它"不用再学第二遍。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 三处必须落值，缺一处就是"有的页暗、有的页亮"
 * ═══════════════════════════════════════════════════════════════════════════
 * 只做 `setTheme()` 是**不够**的 —— 本仓库的界面几乎没有用 XML 布局的控件，
 * 全部是在 `onCreate` 里 `new TextView(this)` 出来、并且**显式**
 * `setTextColor(0xFF...)` / `bubbleBackground()` 硬编码浅色值的。
 * 系统主题只能管到那些没被显式设色的地方（对话框、控件默认字色），
 * 而它们恰恰是被硬编码盖住的那一层。所以真正的落点是：
 *
 *   ① [ThemePalette]-on 的底色/字色（这一层决定"看起来是不是暗的"）；
 *   ② `EngineActivity.setTheme(...)`（决定系统控件与对话框的默认外观）；
 *   ③ `EngineActivity` 的 `Configuration.uiMode` 回调（决定"跟随系统"时
 *      用户在系统里切了深浅之后回到前台的那一下）。
 *
 * ⚠ 本对象**不 import android**：档位判定是纯函数，宿主单测（`ThemePrefTest.kt`）
 *   直接跑这一份真实现。带 `Context` 的那几个薄包装只是存取盘与系统读数，
 *   它们各自只有一行 —— 逻辑一行都不放在那里。
 */
object ThemePref {

    /** 键名。与 [EmbedCtxPref] / [EmbedBatchPref] / [RagInject] 同一个 store（`rag_store`）。 */
    const val KEY = "dark_mode"

    /**
     * 档位值：关闭 / 打开 / 跟随系统。
     *
     * 用 `Int` 而不是 `enum`：值要落进 `SharedPreferences` 的 `getInt`，
     * 而枚举的 `ordinal` 一旦有人插一项就会**静默改义**（"跟随系统"变成"打开"）
     * —— 用户的设置会在他没碰过的情况下自己变。显式常量没有这个失败模式。
     */
    const val OFF = 0
    const val ON = 1
    const val SYSTEM = 2

    /** 档位顺序（设置页按这个顺序铺单选，三个键的唯一来源）。 */
    val MODES = intArrayOf(OFF, ON, SYSTEM)

    /**
     * 出厂默认 = **关闭**（需求原文："默认关闭"）。
     *
     * ⚠ 这是一个**显式的默认值**，不是"没读到就当关"的巧合：磁盘上没有这个键时
     *   `getInt(KEY, OFF)` 与"用户选过关"在行为上等价，但在 `/health` 那类读数里
     *   必须分得开（同 [EmbedCtxPref.UNSET] 的理由）。这里两件事都做了 ——
     *   [get] 回落默认，[isSet] 单独回答"用户碰过没有"。
     */
    const val DEFAULT = OFF

    /** 读写口；四条纪律见 [IntPref]（读、写两侧都走 [normalize]）。 */
    private val pref = IntPref(Prefs.RAG, KEY, DEFAULT) { normalize(it) }

    /** 档位名（设置页单选文案 + 守卫对账用，两边都从这里取）。 */
    fun label(mode: Int): String = when (mode) {
        ON -> "打开（始终用暗色）"
        SYSTEM -> "跟随系统（系统切深浅时跟着变）"
        else -> "关闭（始终用亮色）"
    }

    /**
     * 把任意输入钳到三个档位之一（**非正当输入回落默认**）。
     *
     * 归一在读**和**写两侧都做：磁盘上那份可能来自旧版本、或被人手改过
     * （`adb shell` 改 prefs 是真实会发生的，见 [EmbedCtxPref] 同样的理由）。
     * 读到 7 时不归一会一路传到 `setTheme()` 的 `when` 上，而那个 `else`
     * 分支会把一个**错的值**悄悄当成"关闭" —— 与抄错一位的失效方式完全相同。
     * 所以非法值在这里就落地成 [DEFAULT]，且**读侧**（[get]）不信任磁盘。
     */
    fun normalize(value: Int): Int = if (value in MODES) value else DEFAULT

    /**
     * 「跟随系统 + 系统当前是深夜」→ 这一屏该不该用暗色。
     *
     * ⚠ 这个函数**收一个已经读好的布尔**，不自己去读 `Configuration`：
     *   "哪口钟 / 哪个配置"只能有一处答案（同 [RagProgress] 对时钟的处置）。
     *   读系统的那一处是 [systemDark]，它在 Activity 侧。
     */
    fun isDark(mode: Int, systemDark: Boolean): Boolean = when (normalize(mode)) {
        ON -> true
        SYSTEM -> systemDark
        else -> false
    }

    /** 这一挡需不需要"跟着系统配置变化重画"（只有 [SYSTEM] 需要）。 */
    fun followsSystem(mode: Int): Boolean = normalize(mode) == SYSTEM

    // ── 以下三处是薄包装：每处一行，逻辑不放在这里 ──────────────────────────

    /** 读当前档位（**归一后**的值；磁盘脏值在这里就被吃掉）。 */
    fun get(ctx: Context): Int = pref.get(ctx)

    /** 写当前档位（先归一，免得把一个不存在的档位落盘）。 */
    fun set(ctx: Context, mode: Int) = pref.set(ctx, mode)

    /**
     * 用户有没有显式选过。
     *
     * 判据是"键在不在"，不是"值等不等于默认" —— 用户选过「关闭」时必须报
     * true，否则"他确确实实选了关闭"与"他从没碰过"在读数上同形。
     * 这与 [EmbedCtxPref.isSet] 是同一条口径。
     */
    fun isSet(ctx: Context): Boolean = pref.isSet(ctx)

    /**
     * 抹掉这一格（回到"没选过"）。
     *
     * ⚠ 「还原默认设置」**必须**调它：本项默认是**关闭**，所以"把 OFF 写进键里"
     *   会让 [isSet] 仍为 true —— 用户以为自己还原了，而读数说他设过。
     *   （同 [EmbedCtxPref.clear] / [EmbedBatchPref.clear] 那条理由。）
     */
    fun clear(ctx: Context) = pref.clear(ctx)

    /**
     * 当前该用哪个主题资源（`R.style` 没有 —— 本仓库只有一个资源文件
     * `strings.xml`，为一个主题新开一类资源不值当，同 NPU 配额组那条
     * "用运行时 id，不新开 ids.xml"）。
     *
     * `@android:style/Theme.Material.NoActionBar` 在 API 29+ 会自己跟随
     * 系统的深浅开关，而在更低的版本上它恒为亮色。所以从 [SYSTEM] 这一档
     * 推导出一个**具体的** 亮/暗（而不是把 `Theme.Material.DayNight` 写进
     * manifest、指望系统在低版本上也认它）—— 后者在 API 26~28 上静默退化成
     * 亮色，而我们的 `targetSdk = 28`、`minSdk = 26`，那正是主战场。
     */
    fun themeDark(mode: Int, systemDark: Boolean): Boolean = isDark(mode, systemDark)

    /**
     * 系统当前是不是深夜色（唯一读 `Configuration` 的地方）。
     *
     * `uiMode` 的第 4 位（`UI_MODE_NIGHT_MASK`）由系统维护，本应用**只读**：
     * 写 `UiModeManager.setNightMode` 在 API 29 起被废弃、且需要
     * `MODIFY_DAY_NIGHT_MODE` 权限，普通应用拿不到 —— 试图"替系统决定"只会
     * 静默抛异常（那正是本仓库最讨厌的"看着做了、其实没做"）。
     */
    fun systemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}
