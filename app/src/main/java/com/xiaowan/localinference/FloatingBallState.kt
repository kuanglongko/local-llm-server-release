package com.xiaowan.localinference

/**
 * 悬浮球药丸的**纯逻辑**：读一份状态读数 → 一个状态 → 一句话。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么把这一层单独拆出来（不写在控制器里）
 * ═══════════════════════════════════════════════════════════════════════════
 * 控制器碰 `WindowManager` / `BroadcastReceiver` / `SharedPreferences`，宿主上跑不了；
 * 而药丸"该显示什么"恰恰是最容易写错、又**完全不报错**的那一段：
 *
 *   · 优先级写反（先判模型名再判活跃数）→ 推理中显示成 🟢 模型名，
 *     看起来"一切正常"，于是"到底在不在跑"永远没有可信读数；
 *   · 把「无服务」判在「推理中」之前 → 一次残留的活跃计数让药丸
 *     长期停在 🔵，用户以为有请求卡住；
 *   · 加载中/卸载中没被置顶 → 几十秒的加载窗口里药丸说「🟡 空闲」，
 *     用户以为点了没反应；
 *   · 模型名截断按字符切 → 中英混排的模型名被切成半个字（乱码）；
 *   · 位置不夹取 → 拖动/换分辨率后药丸落在屏幕外，等于**功能消失**，
 *     且用户没有任何办法把它找回来（它本来就没有"重置位置"的入口）。
 *
 * 这几条都不抛异常、不打日志，只有断言能钉住。所以这里零 android 依赖，
 * 由 `tools/run_floating_ball_tests.sh` 在宿主上穷举。
 *
 * ⚠ 本类**不持有**任何业务状态：输入是一份只读的 [ModelStatusManager.Snapshot]，
 *   输出是一份不可变的 [Pill]。控制器只负责"把 Pill 画出来"和"把点击转发出去"。
 */
object FloatingBallState {

    /**
     * 药丸的五种形态。顺序**就是**优先级（`ordinal` 越大越优先）：
     *
     *   🔄 加载中… > 🔵 推理中 > 🟢 模型名 > 🟡 空闲 > ⚪ 无服务
     *
     * `LOADING` 置顶是刻意的：模型加载/卸载是**几十秒**的窗口，那段时间里
     * "服务在跑、暂时没模型"（🟡）读起来像"一切就绪、只是没在用" ——
     * 用户会以为自己的请求没被受理。过渡态必须自己说话。
     */
    enum class Kind {
        /** ⚪ 无服务：模型没加载，且服务也没跑（或状态源暂时读不到）。 */
        NO_SERVICE,
        /** 🟡 空闲：服务在跑，但内存里没有模型。 */
        IDLE_NO_MODEL,
        /** 🟢 模型名：模型就绪、当前没有推理。 */
        IDLE_WITH_MODEL,
        /** 🔵 有推理在进行（不管模型是常驻的还是刚按需加载上来的）。 */
        RUNNING,
        /** 🔄 模型正在加载/卸载/出错过渡。 */
        LOADING,
    }

    /**
     * 一次渲染所需的**全部**信息。
     *
     * [text] 已经含前缀符号与截断后的模型名 —— 视图层不再做任何拼接，
     * 这样"药丸上写了什么"这件事只有一个出处，`generateViewId` 式的重复拼装
     * 不会在两处漂移。
     */
    data class Pill(
        val kind: Kind,
        val text: String,
        /** 完整模型名（用于长按复制/无障碍描述），与 [text] 里截断的那份无关。 */
        val fullModelName: String,
    )

    /** 模型名在药丸上的最大可见字符数。超出的部分用 `…` 收尾。 */
    const val MODEL_LABEL_MAX = 14

    /** 默认位置距屏幕边缘的留白（dp）。 */
    const val EDGE_MARGIN_DP = 8

    /**
     * 一份状态读数 → 一个状态。
     *
     * 优先级（需求 §三 3.1 / §五）：**加载中 > 推理中 > 已加载有模型 >
     * 服务在跑无模型 > 无服务**。两条判据各自盯一件事：
     *   · `state == LOADING` 只看**模型**是不是正处在过渡态（加载统一归到这一档，
     *     那几十秒的窗口不能显示成"空闲"）；
     *   · `activeInferences > 0` 只看**此刻有没有在算**，与按需加载开关无关
     *     （常驻模式下同样要有 🔵）。
     *
     * ⚠ 「无服务」放在**最低**优先级，而不是最高：它只在"模型没加载、服务也
     *   没跑"时才是事实。若像旧版那样先判 `isRunning == false`，一次残留的
     *   活跃计数或一个正在加载的模型会被一句「⚪ 无服务」盖掉 —— 而那恰好是
     *   用户最需要看清的两种现场。
     */
    fun resolve(status: ModelStatusManager.Snapshot): Pill {
        val model = status.modelName?.trim().orEmpty()
        val kind = when {
            status.state == ModelLifecycleCore.State.LOADING -> Kind.LOADING
            status.activeInferences > 0 -> Kind.RUNNING
            status.state == ModelLifecycleCore.State.LOADED && model.isNotEmpty() ->
                Kind.IDLE_WITH_MODEL
            status.serviceRunning -> Kind.IDLE_NO_MODEL
            else -> Kind.NO_SERVICE
        }
        return Pill(kind, label(kind, model), model)
    }

    private fun label(kind: Kind, model: String): String = when (kind) {
        Kind.LOADING -> "🔄 加载中…"
        Kind.RUNNING -> "🔵 推理中"
        Kind.IDLE_WITH_MODEL -> "🟢 " + shorten(model)
        Kind.IDLE_NO_MODEL -> "🟡 空闲"
        Kind.NO_SERVICE -> "⚪ 无服务"
    }

    /**
     * 按**码点**截断，不按 UTF-16 单元。
     *
     * `String.length` / `substring` 切的是 UTF-16 单元：在 emoji 与部分中日韩
     * 扩展字上会把一个字切成两半（渲染成 。模型名里 emoji 不常见，
     * 但量化标记/全角字符很常见，而"半个字"这种坏法只有在那一个模型上才出现）。
     */
    fun shorten(model: String, max: Int = MODEL_LABEL_MAX): String {
        if (model.isEmpty()) return ""
        if (max <= 0) return "…"
        var i = 0
        var count = 0
        while (i < model.length && count < max) {
            i += Character.charCount(model.codePointAt(i))
            count++
        }
        if (i >= model.length) return model
        // 截断后不留尾随空格（"模型 名" → "模型 …" 比 "模型 …" 好读，但尾随空格
        // 在药丸上就是一段看不见的偏移，而偏移会让居中的文字看起来偏左）。
        return model.substring(0, i).trimEnd() + "…"
    }

    /**
     * 把左上角坐标夹到**当前屏幕**可见范围内。
     *
     * 四件事必须一起做，少一件就有一种"药丸不见了"：
     *   1. 上边界不贴 0：状态栏底下那一条是系统手势区，`FLAG_LAYOUT_NO_LIMITS`
     *      之外的窗口放在那里会被拦掉一半触点；
     *   2. 左右夹到 `[margin, screenW - viewW - margin]`；
     *   3. 下边界同理（屏幕高 - 药丸高 - margin）；
     *   4. 屏幕比药丸还窄/矮时（分屏、开发者选项里的小屏）区间会**反过来** ——
     *      `coerceIn` 在 lo > hi 时抛 IllegalArgumentException，所以先判区间。
     */
    fun clamp(x: Int, y: Int, screenW: Int, screenH: Int, viewW: Int, viewH: Int, marginPx: Int): Pair<Int, Int> {
        val margin = marginPx.coerceAtLeast(0)
        val loX = margin
        val hiX = screenW - viewW - margin
        val loY = margin
        val hiY = screenH - viewH - margin
        return Pair(axis(x, loX, hiX), axis(y, loY, hiY))
    }

    /**
     * 区间反了（屏幕比药丸还小，分屏/小屏时真的会发生）就落 **0**：
     * 至少还看得见一角，而不是落在屏幕外。
     *
     * 判据是 `hi <= lo` 而不是 `hi < lo`：只剩一格（lo == hi）时 `coerceIn` 本身
     * 不抛，但那一格的意义已经没了；统一落 0 让"放不下"只有一种表现。
     * 落 0 而不是落 `lo`（=margin）：药丸比这一维还宽时，落 margin 会把它推出去
     * 一部分，而落 0 至少让开头那一截可见。
     */
    private fun axis(v: Int, lo: Int, hi: Int): Int =
        if (hi <= lo) 0 else v.coerceIn(lo, hi)

    /**
     * 一次触摸被判定成什么。
     *
     * 三件事只有**在松手那一刻**才知道（是不是拖动 / 是不是长按 / 还是单击），
     * 所以判定与副作用分开：本函数只回答"这一下算哪种"，控制器按答案去转发。
     */
    enum class Gesture { TAP, LONG_PRESS, DRAG, NONE }

    /**
     * 触摸判定。
     *
     * @param movedPx 从按下到此刻移动的距离（像素）
     * @param heldMs  按下到此刻的时长
     * @param slopPx  系统触摸阈值（`ViewConfiguration.getScaledTouchSlop()`）
     * @param longPressMs 系统长按阈值（`ViewConfiguration.getLongPressTimeout()`）
     *
     * `movedPx > slopPx` 一旦成立就**永久**是拖动：手指拖出去又拖回原点仍然算拖动，
     * 否则"拖了一圈回到原地松手"会被判成单击 —— 于是用户想搬家，结果打开了 App。
     */
    fun gesture(up: Boolean, movedPx: Int, heldMs: Long, slopPx: Int, longPressMs: Long, longPressFired: Boolean): Gesture = when {
        movedPx > slopPx -> Gesture.DRAG
        !up -> Gesture.NONE
        longPressFired || heldMs >= longPressMs -> Gesture.LONG_PRESS
        else -> Gesture.TAP
    }
}
