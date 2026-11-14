package com.xiaowan.localinference

/**
 * 「建索引现在走到哪儿了」那一行**读数**的纯逻辑（零依赖：不 import 任何东西）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这一层：进度行此前只有「N/M」，而用户要回答的是另外三个问题
 * ══════════════════════════════════════════════════════════════════════════
 * 建库的耗时由**块数**主导，而块数由文档长度决定 —— 一份 3 MB 的整篇能切出
 * 上万块，就是几十分钟。那几十分钟里用户手上唯一的参照物就是这条状态行，
 * 而它此前只有 `正在编码：37/210` 一个数：
 *
 *   · 「**已经等了多久**」—— 没有。他得自己记开始那一刻，再减；
 *   · 「还有多少」—— 有分母了，但比例要他自己除；
 *   · 「**这条还在动吗**」—— 计数在走就够了，但每 250 ms 换一次的文本
 *     位数一变就抖，读起来更像"闪"而不像"走"。
 *
 * 所以这一段把"已耗费时间 / 已编条数 / 总数 / 占比"收成**一句话**，
 * 并且刻意让位宽稳定（`01:05` 而不是 `1:5`、` 37.0%` 而不是 `37%`）——
 * 这条行要挂几十分钟，抖动就是**噪声**，而噪声会让"它到底还在不在走"
 * 这个唯一的判断重新变难。
 *
 * ⚠ 报的是**已耗时间**，不是"预计剩余"：剩余要靠 `已耗时 / 已完成 × 剩余条数`
 *   推，而块与块之间的耗时能差一个数量级（一句短笔记 vs 一块满长的整篇）——
 *   推出来的剩余时间会**看起来精确**，而它错得没边。本仓库对"看起来量准了的
 *   假读数"判据一向是硬的（见 `[embed内存]` 那个 `+N MB` 被换掉那件事：
 *   一个量不准却像量准了的数，比没有读数更坏）。
 *
 * ⚠ 这一层**只拼字符串、不碰时间源**：`elapsedMs` 由调用方从**单调钟**
 *   （`System.nanoTime()`）算好传进来。墙钟（`currentTimeMillis`）在**做差**
 *   这件事上会被 NTP 跳变/用户改时间拉歪（本仓库有 `run_monotonic_clock_guard.sh`
 *   专门钉这一族，见 G1：墙钟差被当成"时长"会让真实卡死被静音）。
 *   所以这里收一个**已经算好的毫秒数**，与取时钟的那一处分开 ——
 *   "哪口钟"只有一处答案，这里不产生第二个。
 */
object RagProgress {

    /**
     * 建索引进度回执的**识别前缀**（由 [LlmEngine.buildIndex] 发出的那一档）。
     *
     * [!] 这个字符串此前**在 `EngineActivity` 里写着一份副本**，而发方写的是
     *     `"[知识库] 正在编码："` —— 两个字符串从一开始就不相等（收方那份多
     *     "逐条"两个字），于是 `startsWith` **恒假**：
     *       · 进度**从来没有**走到 `updateProgressLine` 那条路；
     *       · 每一行 `正在编码：37/210` 都落进了**日志渲染**那条闸
     *         （2000 行 ring 的排版 + 标签筛选），一次建库几百轮全量重排。
     *     而这正是 `uiProgress` 那段注释里"上万行不进 ring"要防的事 ——
     *     判据（`startsWith(PROGRESS_PREFIX)`）恒假，整条保护就一行都没生效。
     *     修法是**只有一处**定义它：发方与收方都从 [PREFIX] 取，同一个常量。
     *
     * [!] 只靠这个前缀把"进度"与"日志"分开（而不是加一个回调参数）：
     *     `logSink` 是一条 `(String) -> Unit` 的既有管线，加签名等于把"日志"
     *     这一个概念劈成两个类型，而全仓库有二十多处发日志的地方要跟着改。
     *     前缀把这件事收在**一收一发两个点**上，且判据可被守卫正面钉住。
     */
    const val PREFIX = "[知识库] 正在编码："

    /**
     * 把进度回执的**正文**（前缀之后那一段）拼出来。
     *
     * 格式（固定字段顺序，便于日志里 grep 与用户扫读）：
     *   `37/210 条（17.6%）｜ 已用 1:23 ｜ 37 条/分`
     *
     * @param done  已编码条数（含本批，`0..total`）
     * @param total 总条数（切块后的块数）
     * @param elapsedMs 从开工到此刻的**已耗毫秒**（单调钟差值；负数按 0 处理）
     */
    fun line(done: Int, total: Int, elapsedMs: Long): String {
        val d = done.coerceAtLeast(0)
        val t = total.coerceAtLeast(0)
        val sb = StringBuilder()
        sb.append(d).append('/').append(t).append(" 条")
        val pct = percent(d, t)
        if (pct != null) sb.append("（").append(pct).append("）")
        sb.append("｜ 已用 ").append(elapsed(elapsedMs))
        // 速率要给，但**只在已经量得出速度之后**：第 1 条还没编完时算出来的
        // "条/分"是一个由 0 条推出来的无穷大（或 0），报它等于报一个假读数。
        val rate = perMinute(d, elapsedMs)
        if (rate != null) sb.append("｜ ").append(rate).append(" 条/分")
        return sb.toString()
    }

    /**
     * 占比，保留 **1 位小数**；`total <= 0` 返回 `null`（分母不知道就不报）。
     *
     * ⚠ 为什么要小数位而不是取整：210 条的一个库，取整后**前 4 条**全显示 `0%`，
     *   而用户要判断的正是"它到底开始动了没有"。一位小数把这些区分开。
     * ⚠ 位宽：`0.0%` 与 `100.0%` 不同宽（5 个字符 vs 6 个），但那两个值各自
     *   只在开头/结尾出现一次 —— 中途是 4 个字符稳定不变，抖动只在两端。
     *   为了"两端也同宽"去补空格，代价是这一行在等宽字体外的环境下对不齐，
     *   得不偿失。
     */
    fun percent(done: Int, total: Int): String? {
        if (total <= 0) return null
        val v = done.coerceIn(0, total).toDouble() * 100.0 / total.toDouble()
        return oneDecimal(v) + "%"
    }

    /**
     * 已耗费时间：`M:SS`（不足 1 小时）/ `H:MM:SS`（1 小时以上）。
     *
     * ⚠ 与 `embed_text.h::format_elapsed`（native 侧那一份）**同一条口径**：
     *   分秒定长零填充、小时档才带小时。两处不共用代码（一个在 C++ 头文件、
     *   一个在 Kotlin），所以由守卫**正面钉住两者的格式一致**（见
     *   `tools/rag_progress/check_progress_wiring.py`）—— 只写一处而另一处
     *   漂移的形态是"日志里 1:23、界面上 1:5"，两者都"没错"，只是说的不是
     *   同一件事，而这种漂移没有任何迹象。
     */
    fun elapsed(ms: Long): String {
        val total = (if (ms > 0) ms else 0L) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        // 位宽：`M:SS` 的分钟位**不**补零（`59:59` 而不是 `059:59`），
        // 与 native 那份逐字相同；秒**必须**补零，否则 `1:5` 会被读成"1 分 5 秒"
        // 与"1 分 50 秒"分不清。
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /**
     * 速率（条/分），保留整数；**不到一条或不到一秒时返回 `null`**。
     *
     * 为什么这两个闸必须都在：
     *   · `done <= 0` —— 还没编完一条，速率没有分子；
     *   · `elapsedMs < 1000` —— 第一秒内算出来的速率是"3000 条/分"这种
     *     由极小分母放大的数，看着像真的，实际是噪声。等一秒再报。
     *
     * ⚠ 不报"剩余时间"（见文件头那段）：这个速率**只用来对照"快还是慢"**，
     *   乘上剩余条数去推剩余时间恰好是上面拒绝的假精确。
     */
    fun perMinute(done: Int, elapsedMs: Long): String? {
        if (done <= 0 || elapsedMs < 1000L) return null
        val v = done.toDouble() * 60_000.0 / elapsedMs.toDouble()
        return v.toInt().toString()
    }

    /** 保留 1 位小数（不用 `String.format`：那会引入 Locale，小数点在某些地区是逗号）。 */
    private fun oneDecimal(v: Double): String {
        val scaled = Math.round(v * 10.0).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    /** 两位零填充。 */
    private fun two(v: Long): String = if (v < 10L) "0$v" else v.toString()
}
