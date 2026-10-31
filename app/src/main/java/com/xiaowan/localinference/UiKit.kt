package com.xiaowan.localinference

import android.app.Activity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * 本仓库唯一一屏界面的**样板基类**：只放"与业务无关、但每个控件都要用一次"的三件小事。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么值得单独一层
 * ══════════════════════════════════════════════════════════════════════════
 * [EngineActivity] 已经上万行，而 `px` / `btn` / `lp` 是它里面**最没有业务含义**
 * 的三个函数：一个把 dp 换成 px、一个 new 一个按钮、一个给横向行算
 * LayoutParams。它们被全文件几百处调用，搬走之后阅读业务代码时不再被这些
 * 样板截断，也不需要（又一次）在新增页面时复制一份。
 *
 * ⚠ 刻意**只**搬这三个，不顺手把 `label()` / `field()` / `pal` 一起搬：
 *   · `label()` / `field()` 的每一行都与 [ThemePalette]（`pal`）绑在一起 ——
 *     它们是"这一屏取色的唯一出口"的一部分，搬走等于把取色契约拆成两处；
 *   · `pal` 在 `EngineActivity.onCreate()` 里赋值，且 `run_dark_mode_guard.sh`
 *     以 `private lateinit var pal: ThemePalette` 作为"取色点没有绕开"的锚。
 *   这一层只承载"换个 Activity 也原样成立"的东西。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 关于 `lp()`（模块 M5 的真机现场）
 * ══════════════════════════════════════════════════════════════════════════
 * `lp(w, weight)` 的语义是**给横向行写的**：宽为 0dp 时靠 weight 等分父容器
 * 主轴。父容器是 `VERTICAL` 时 weight 分的是**高度**，`lp(0, 1)` 会把子项拉成
 * 整屏高的空白（0.9.127 的 repack 组就这么坏过一次）。搬到这里不等于放宽这条
 * 纪律：`tools/run_repack_lp_guard.sh` 仍逐字锚住本函数的宽度分支与
 * `weight.toFloat()`，并继续扫 [EngineActivity] 里"纵向容器 + lp(...)"的组合。
 */
/**
 * 全屏字号阶梯。**每个 `textSize = ...` 都必须从这里取**，不许再写字面量。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么值得把 7 个数字命名
 * ══════════════════════════════════════════════════════════════════════════
 * 收口前 `EngineActivity` 里有 109 处 `textSize = 10f/11f/.../16f`。
 * 这些数字编码的是**层级**（说明 < 次要 < 标签 < 正文 < 强调 < 标题 < 大标题），
 * 但写在调用点上只剩一个数字：想调大正文字号时要先数清"哪些 13f 是正文、
 * 哪些 13f 是碰巧和正文一样大的按钮"，而漏掉一处就是"这一小块文字比别处小".
 * 这种不一致不会让任何测试变红，只在长时间阅读时让人觉得"哪里怪"。
 *
 * 命名之后，调整阶梯只改这里；调用点写 [BODY] / [LABEL]，读代码时不用回头查
 * 这个数字在别处意味着什么。
 *
 * ⚠ 不把 `px(n)` 的间距也做成类似的命名常量：`px()` 本身已经带上了单位，
 *   而 1/2/4/5/6/8/10/12/14/16/20/32/48/72 这组值在**不同控件里是不同的
 *   设计决定**（同一个 8 可能是 padding、可能是两行之间的间隙、可能是图标尺寸）。
 *   把它们统一改名成 `GAP_M` 只是把数字换个写法，反而丢掉"这里是 8dp"这条
 *   直接信息；字号则不同 —— 7 个值确实构成了一条可命名的阶梯。
 */
object UiText {
    /** 10f：最小号的辅助说明（参数提示、脚注）。 */
    const val CAPTION = 10f
    /** 11f：次要信息（状态行、时间戳、计数）。 */
    const val SMALL = 11f
    /** 12f：标签 / 说明行（`label()` 的固定字号）。 */
    const val LABEL = 12f
    /** 13f：正文与按钮（`btn()` / `field()` 的固定字号）。 */
    const val BODY = 13f
    /** 14f：需要被一眼看到的强调文字。 */
    const val EMPHASIS = 14f
    /** 15f：小节标题（通常配粗体，见 `setTypeface(..., BOLD)`）。 */
    const val TITLE = 15f
    /** 16f：页面级大标题。 */
    const val HEADLINE = 16f
}

open class UiActivity : Activity() {

    /** dp -> 当前屏幕密度下的 px。 */
    protected fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /**
     * 一行高的普通按钮。
     *
     * 字号固定取 [UiText.BODY]（全文件按钮一致），字色**不在这里给**：
     * 按钮前景由主题决定，而主题在 `setTheme()` 那一跳里已经按当天档位选好。
     * 需要额外配色的调用点自己 `apply { ... }`。
     */
    protected fun btn(s: String) = Button(this).apply { text = s; textSize = UiText.BODY }

    /**
     * 横向行用的 LayoutParams：`w == 0` -> 0dp（靠 weight 等分），否则 wrap。
     * 四边留 2dp 间距。
     *
     * ⚠ 别拿它给 `LinearLayout.VERTICAL` 的子项用 —— 见类注释。
     */
    protected fun lp(w: Int, weight: Int) = LinearLayout.LayoutParams(
        if (w == 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT, weight.toFloat()).apply {
        setMargins(px(2), px(2), px(2), px(2))
    }
}
