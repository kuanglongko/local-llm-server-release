package com.xiaowan.localinference

import kotlin.math.abs

/**
 * 左右翻页手势的**方向锁**（纯 Kotlin，零 Android 依赖，宿主可直接单测）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要它（而不是"看松手时的 dx / dy"）
 * ══════════════════════════════════════════════════════════════════════════
 * 旧实现只在 `ACTION_UP` 那一刻比较总位移：`abs(dx) > 80dp` 且
 * `abs(dx) > abs(dy) * 1.5`。问题出在**纵向滚动时手指不可能是一条直线**：
 * 一路往下滑，横向漂移很容易累计到 80dp；而人在松开前常会往回带一点，
 * 于是松手时 `dy` 反而不大 —— 判据成立，页面就翻了。用户的原话是
 * "上下滑动时经常被识别成左右滑动，触发概率非常高"。
 *
 * 正确的判据不是"最后落在哪里"，而是"**这次手势一开始是往哪个方向走的**"：
 * 手指移动超过 touch slop 时，按当时的横向/纵向占比**一次性锁定**轴向；
 * 锁定为纵向之后，同一手势后面无论横移多少都不再翻页。松手时也只认
 * 锁定为横向的那一次，并要求最终横向位移仍明显大于纵向。
 *
 * ⚠ 坐标必须用 `MotionEvent.rawX / rawY`：`x / y` 是相对当前接收 View 的，
 *   事件在子控件与父滚动区之间移交（ScrollView 拦截）时会被重新偏移，
 *   于是同一个手指会"跳"一下，方向判据也跟着跳。
 *
 * ⚠ 没收到 `ACTION_DOWN` 时 `move/up` 一律忽略：若手势起点落在会消费 DOWN
 *   的子控件上（输入框、按钮），父级监听可能只从半截的 MOVE 开始收到事件；
 *   拿 (0,0) 当起点会凭空造出一段巨大横移，正是"手指没在翻页，页面却翻了"。
 */
class SwipePager(
    /** 判定轴向所需的最小位移（通常传 `ViewConfiguration.scaledTouchSlop`）。 */
    private val slopPx: Float,
    /** 真正翻页所需的最小横向位移（避免手抖/轻点也翻页）。 */
    private val commitPx: Float,
    /** 横向必须比纵向大多少倍才认（越大越保守）。 */
    private val dominance: Float = 1.8f,
) {
    enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL }

    var axis: Axis = Axis.UNDECIDED
        private set

    private var downX = 0f
    private var downY = 0f
    private var armed = false

    fun down(x: Float, y: Float) {
        downX = x
        downY = y
        armed = true
        axis = Axis.UNDECIDED
    }

    fun move(x: Float, y: Float) {
        if (!armed || axis != Axis.UNDECIDED) return
        val dx = abs(x - downX)
        val dy = abs(y - downY)
        if (dx < slopPx && dy < slopPx) return
        // 一次锁定，后面不再改：这正是"纵向滚动不会翻页"的结构保证。
        axis = if (dx >= dy * dominance) Axis.HORIZONTAL else Axis.VERTICAL
    }

    /**
     * 松手结算：返回 `1` = 下一页、`-1` = 上一页、`0` = 不翻页。
     *
     * 结算后状态归零，下一次 [down] 开始新手势。
     */
    fun up(x: Float, y: Float): Int {
        if (!armed) {
            reset()
            return 0
        }
        val dx = x - downX
        val dy = y - downY
        val horizontal = axis == Axis.HORIZONTAL &&
            abs(dx) >= commitPx &&
            abs(dx) >= abs(dy) * dominance
        val step = when {
            !horizontal -> 0
            dx < 0f -> 1       // 手指向左 = 看右边一页
            else -> -1         // 手指向右 = 看左边一页
        }
        reset()
        return step
    }

    /** 被系统/父控件打断（多指、ScrollView 抢走、窗口失焦）时调用。 */
    fun cancel() = reset()

    private fun reset() {
        armed = false
        axis = Axis.UNDECIDED
    }
}
