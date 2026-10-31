package com.xiaowan.localinference

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 带**常驻、可拖动滚动条**的 ScrollView。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么不直接用系统滚动条
 * ══════════════════════════════════════════════════════════════════════════
 * 系统滚动条（`isVerticalScrollBarEnabled` + `isScrollbarFadingEnabled=false`）
 * 在部分 ROM / 主题下仍然只有 2~3dp 宽、颜色与页面底色几乎一样；还有的 ROM
 * 会把 `scrollbarThumbVertical` 换成一个透明或极淡的 drawable。结果是
 * "代码里开着滚动条，用户却看不到"—— 知识库、日志这种长页面尤其明显。
 *
 * 这里不再依赖主题里的滚动条 drawable，而是**自己画 + 自己拖**：
 *   · 一条贯穿可视区的灰色轨道 + 一个按滚动比例定位的近黑色滑块；
 *   · 宽度 6dp，颜色来自 [ThemePalette]，深浅两档都有明确对比度；
 *   · 永远不淡出：只要内容比视口长就一直在；
 *   · 手指落在轨道/滑块上时可以上下拖动，拖动比例直接换算成 `scrollY`。
 *
 * ⚠ 拖动区比视觉条宽（[touchWidth]）：6dp 的线手指很难精准按住，而
 *   页面右侧本来就有 12dp 内边距，把可拖区向里扩一点不会压到正文。
 *
 * ⚠ 只接管**从滚动条上开始**的手势：其它位置原样交给 [ScrollView]，
 *   所以左右翻页、页面纵向滚动、点按控件都不受影响。
 */
class PagerScrollView(context: Context) : ScrollView(context) {
    private val barWidth = dp(6)
    private val barInset = dp(2)
    private val touchWidth = dp(16)
    private val minThumb = dp(24)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt()
        alpha = 0xFF
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
    }

    /** 是否正在拖动右侧滚动条。翻页手势在看到它时主动让路。 */
    var isDraggingBar = false
        private set

    private var grabOffset = 0f

    init {
        // 系统滚动条关掉：某些 ROM 上它会画在自绘滑块旁边，变成两条；
        // 而且系统滚动条本来就不能拖。自绘滑块始终可见，不依赖淡出设置。
        isVerticalScrollBarEnabled = false
        setWillNotDraw(false)
    }

    /** 主题色只有一个来源：[EngineActivity.pal]；两处调用（建视图时、换主题时）结果一致。 */
    fun setBarColors(track: Int, thumb: Int) {
        trackPaint.color = track
        // 轨道必须**实色**：半透明时聊天页会透出气泡、知识库/日志页透出底色，
        // 同一条滚动条在不同页面看起来就不是一个东西（用户报的"样式不统一"）。
        trackPaint.alpha = 0xFF
        thumbPaint.color = thumb
        invalidate()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidate()
    }

    /**
     * 内容高度**不是常量**：设置页折叠/展开、知识库每页 60 条、日志不断追加
     * 都会改 `root` 的高度。每次 layout 完成都重画一次，滑块长度与位置才不会
     * 停在上一次的量法上（那会表现为"页面明明变长了，滑块还是原来那么长"）。
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val b = bar() ?: return
        // ⚠ 这里的画布**已经被父层按 -scrollY 平移过**：ScrollView 的滚动就是这么
        // 实现的（子 View 在 `View.draw(canvas, parent, ...)` 里被 translate 到
        // `mTop - mScrollY`）。直接按视口坐标画，轨道和滑块会跟着内容一起滚走 ——
        // 表现就是「灰色轨道只剩很短一截、划一次黑色滑块就看不见了」。
        // 先把滚动量加回来，滚动条才钉在视口右侧，只有滑块的位置随 offset 变化。
        val save = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        drawBar(canvas, b)
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val b = bar()
        if (b == null) {
            isDraggingBar = false
            return super.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 新手指按下先清掉上一轮的拖动状态：若上一次 UP/CANCEL 因窗口
                // 失焦没送到，残留的 true 会让这一次普通滑动被误当成拖滚动条。
                isDraggingBar = false
                val hitLeft = (width - paddingRight - barInset - touchWidth).toFloat()
                val hitRight = (width - paddingRight).toFloat()
                if (ev.x in hitLeft..hitRight && ev.y in b.trackTop..b.trackBottom) {
                    isDraggingBar = true
                    grabOffset = if (ev.y in b.thumbTop..(b.thumbTop + b.thumbHeight)) {
                        ev.y - b.thumbTop
                    } else {
                        b.thumbHeight / 2f
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    scrollByThumb(ev.y, b)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (isDraggingBar) {
                scrollByThumb(ev.y, bar() ?: b)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (isDraggingBar) {
                isDraggingBar = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (ev.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 把滑块拖到手指位置，再按滑块在轨道上的比例反算 `scrollY`。 */
    private fun scrollByThumb(y: Float, b: Bar) {
        val travel = (b.trackBottom - b.trackTop - b.thumbHeight).coerceAtLeast(1f)
        val top = (y - grabOffset).coerceIn(b.trackTop, b.trackTop + travel)
        val progress = (top - b.trackTop) / travel
        scrollTo(0, (progress * b.maxScroll).toInt())
    }

    private fun drawBar(canvas: Canvas, b: Bar) {
        val radius = barWidth / 2f
        canvas.drawRoundRect(b.left, b.trackTop, b.left + barWidth, b.trackBottom,
            radius, radius, trackPaint)
        canvas.drawRoundRect(b.left, b.thumbTop, b.left + barWidth, b.thumbTop + b.thumbHeight,
            radius, radius, thumbPaint)
    }

    private class Bar(
        val trackTop: Float,
        val trackBottom: Float,
        val thumbTop: Float,
        val thumbHeight: Float,
        val left: Float,
        val maxScroll: Float,
    )

    private fun bar(): Bar? {
        // 用 ScrollView 自己的三把尺子，而不是 `child.height` / `scrollY`：
        //   · computeVerticalScrollRange  = 当前内容的完整高度（含当前可见页的
        //     折叠状态、知识库这一页 60 条、日志最新 120 行）；
        //   · computeVerticalScrollExtent = 视口高度；
        //   · computeVerticalScrollOffset = 当前偏移。
        // 这三个值在每次 layout 后由 ScrollView 重新算，天然跟着上面那些内容变化走。
        val range = computeVerticalScrollRange()
        val extent = computeVerticalScrollExtent()
        val offset = computeVerticalScrollOffset()
        if (extent <= 0 || range <= extent) return null

        val trackTop = paddingTop.toFloat()
        val trackBottom = (height - paddingBottom).toFloat()
        val trackH = trackBottom - trackTop
        if (trackH <= 0f) return null

        val thumbH = minOf(trackH, maxOf(minThumb.toFloat(), trackH * extent.toFloat() / range))
        val maxScroll = (range - extent).toFloat()
        val progress = (offset.toFloat() / maxScroll).coerceIn(0f, 1f)
        val thumbTop = trackTop + (trackH - thumbH) * progress
        val left = (width - paddingRight - barInset - barWidth).toFloat()
        return Bar(trackTop, trackBottom, thumbTop, thumbH, left, maxScroll)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
