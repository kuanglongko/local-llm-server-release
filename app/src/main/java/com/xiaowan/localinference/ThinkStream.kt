package com.xiaowan.localinference

/**
 * `<think>` 状态机：把模型输出流切成「思考段」与「正文」两路。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独抽出来
 * ══════════════════════════════════════════════════════════════════════════
 * 这段逻辑原先内联在 `HttpApi.handleChat` 的生成循环里，既没法离线单测，也没法
 * 复用。它最容易错的地方（下面四条）都**不报错、不崩**，只表现为客户端 view 里
 * 多一层折叠、思考内容混进正文、或**正文整段看不见**——靠肉眼看输出很难定位。
 * 抽成纯逻辑后，`feed` 的输入输出可逐条断言，测试与线上跑的是**同一份**实现。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 四个真实故障（都曾是"设了没用/看着像模型坏了"）
 * ══════════════════════════════════════════════════════════════════════════
 * ① 开标签只在 prompt 里：LFM2.5 / MiniCPM5 的生成后缀硬编码
 *    `…<|im_start|>assistant\n<think>\n`，即 `<think>` 是**渲染进 prompt 的**，
 *    模型的输出从思考正文开始、到 `</think>` 结束。旧实现把 `sawOpen` 初值写死
 *    false（要求标签出现在输出里），于是整段思考连同裸 `</think>` 全被当正文
 *    `content` 发出。修法：[openAtStart] 由调用方按**渲染结果**传入。
 *
 * ④ 上一条的过度修正（2026-09-19 真机）：[openAtStart] 的判据一度写成
 *    「渲染结果尾部出现过 `<think>` 且其后没有 `</think>`」，并额外要求 `thinkingOn`。
 *    MiniCPM5 思考开时模板吐的 `…assistant\n<think>\n` 正好命中它 —— 但模板那个
 *    开标签**只说明模板自己吐了开标签**，不说明闭合标签会出现在模型输出里：
 *    模型仍要自己吐一个 `</think>` 来收尾。判成"已开"之后，模型吐的 `</think>`
 *    走了「思考段内的裸闭合标签」那一支被静默剥离，整段思考与正文一起留在
 *    reasoning 里，客户端拿到的 `content` 只剩极短一截。
 *    修法：判据上移到**渲染发生的地方**（`llama_jni.cpp` 的
 *    `classify_rendered_think_tail`，只看库自己算的生成后缀），宿主不再做字符串近似；
 *    这里也不再保留那份近似函数 —— 留着就还会有人用它。
 *
 * ② 嵌套 `<think>`：思考段内模型又吐一个 `<think>` 时，旧实现在 `inThink` 分支
 *    只找 `</think>`，内层开标签被当成思考正文原样转发进 `reasoning_content`。
 *    客户端对 `reasoning_content` 会再做一次标签识别 → **折叠里再折叠**。
 *    修法：思考段内的 `<think>` 一律剥离，不转发。
 *
 * ③ 裸 `</think>`：关闭思考时 prompt 被预填了闭合块，模型有时仍会补一个
 *    `</think>`。旧实现在非思考段只透传，裸闭合标签漏进 `content`。修法：非思考
 *    段的 `</think>` 同样剥离（它只能来自模型对已闭合段的重复收尾，不可能是正文）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 用法
 * ══════════════════════════════════════════════════════════════════════════
 * ```
 * val st = ThinkStream(openAtStart = rendered.openAtStart && !softSwitchInjected)
 * for (piece in tokens) st.feed(piece) { text, asReason -> emit(text, asReason) }
 * st.flush { text, asReason -> emit(text, asReason) }
 * ```
 * [feed] 的 `emit` 回调参数 `asReason=true` 表示这段应进 `reasoning_content`，
 * 否则进 `content`。跨 chunk 的半个标签由内部 carry 缓冲，不会漏出去。
 *
 * [openAtStart] 的取值只有一个合法来源：**渲染侧**给出的「生成后缀里有一个未闭合的
 * `<think>`」（[RenderedPrompt.openAtStart]）。
 *
 * ⚠ 与软开关注入的叠加方向是 **`&& !soft`，不是 `|| soft`**（2026-09-21 ISSUE #106 的根因）：
 * 软开关补的是**闭合段**，注入完思考段就已经闭合，模型接下来写的是**正文**。
 * 把它翻成 true 会让整段正文被当成 reasoning 折叠进思考块
 * （真机现象：LFM2.6B 关思考后回答整段被折叠）。不要在本文件或调用方另写判据 —— 见故障④。
 */
class ThinkStream(openAtStart: Boolean = false) {

    /** 是否已经见过（或由调用方声明已存在）思考开标签。 */
    var sawOpen: Boolean = openAtStart
        private set

    /** 当前是否处于思考段内（调用方用它决定要不要打 SSE 心跳）。 */
    var inThink: Boolean = openAtStart
        private set

    private val carry = StringBuilder()

    /** 是否还压着未下发的字节（半个标签）。收尾时必须 [flush]。 */
    val pending: Boolean get() = carry.isNotEmpty()

    /**
     * [flush] 收尾时丢弃的半个标签字节数。
     *
     * 本文件是**纯逻辑**（不得 import android.*，否则宿主单测编不了），因此不能在这里
     * 打日志；把丢弃量作为可读状态暴露出去，由持有 android 上下文的调用方决定怎么记。
     * 正常生成（标签成对出现）时恒为 0，非 0 说明这一轮是截断/取消/乱码收尾。
     */
    var droppedPartialTag: Int = 0
        private set

    private fun partialTag(s: StringBuilder, tag: String): Int {
        val str = s.toString()
        for (k in minOf(tag.length - 1, str.length) downTo 1) if (str.endsWith(tag.substring(0, k))) return k
        return 0
    }

    /** 喂入一段模型输出。`emit(text, asReason)` 会被调用 0..n 次。 */
    fun feed(piece: String, emit: (String, Boolean) -> Unit) {
        if (piece.isEmpty()) return
        carry.append(piece)
        drain(emit)
    }

    /**
     * 生成结束，把 carry 里剩下的字节下发。
     *
     * carry 里的残留分成两种，**处理方向相反**：
     * · 不构成任何标签前缀的正常文本（如停在「正文的中间」）—— 这是真内容，必须发出；
     * · **整个 carry 就是一个标签前缀**（如 `<thi` / `</think`）—— 它不是内容，必须**丢弃**。
     *
     * 旧实现把两者一起按当前段归属发出，于是 `max_tokens` 截断、取消、模型乱码
     * 恰好停在标签中途时，`content` 尾部会多出裸 `<think` / `</think`。这与本文件
     * 故障③「裸 `</think>` 不得漏进 content」是**同一条判据**，只是发生在收尾这一拍：
     * 自带测试页按字段渲染看不出，但按标签识别思考段的第三方 UI、以及把 content
     * 拼回 history 的下一轮请求都会从这里开始错位。
     *
     * 判据是「carry 全部属于某个标签的前缀」，不是「以标签前缀结尾」——
     * 后者会连正常文本一起丢（如 `答案<thi` 里的 `答案` 是真内容）。因此这里
     * 先剥前缀再发余下部分，且只在**余下为空**时才整段丢弃。
     */
    fun flush(emit: (String, Boolean) -> Unit) {
        if (carry.isNotEmpty()) {
            val keep = maxOf(partialTag(carry, OPEN), partialTag(carry, CLOSE))
            val body = carry.length - keep
            if (body > 0) emit(carry.substring(0, body), inThink)
            if (keep > 0) droppedPartialTag += keep
        }
        carry.setLength(0)
    }

    private fun drain(emit: (String, Boolean) -> Unit) {
        while (carry.isNotEmpty()) {
            if (!sawOpen) {
                // 还没见过开标签。这里同样要剥掉裸 `</think>`：关闭思考时 prompt 被预填了
                // 闭合块，模型有时会再补一个收尾标签 —— 它落在「开标签之前」，不走 inThink 分支，
                // 旧实现就直接当正文透传，客户端 content 里冒出孤零零的 `</think>`。
                val i = carry.indexOf(OPEN)
                val c0 = carry.indexOf(CLOSE)
                if (i >= 0 && (c0 < 0 || i < c0)) {
                    if (i > 0) emit(carry.substring(0, i), false)
                    carry.delete(0, i + OPEN.length); sawOpen = true; inThink = true; continue
                }
                if (c0 >= 0) {
                    if (c0 > 0) emit(carry.substring(0, c0), false)
                    carry.delete(0, c0 + CLOSE.length); continue
                }
                val keep = maxOf(partialTag(carry, OPEN), partialTag(carry, CLOSE))
                val e = carry.length - keep
                if (e > 0) { emit(carry.substring(0, e), false); carry.delete(0, e) }
                return
            } else if (inThink) {
                // 嵌套开标签：剥离，不转发（否则 reasoning_content 里出现标签 -> 折叠嵌套）。
                val nested = carry.indexOf(OPEN)
                val c2 = carry.indexOf(CLOSE)
                if (c2 >= 0 && (nested < 0 || nested > c2)) {
                    if (c2 > 0) emit(carry.substring(0, c2), true)
                    carry.delete(0, c2 + CLOSE.length); inThink = false; continue
                }
                if (nested >= 0) {
                    if (nested > 0) emit(carry.substring(0, nested), true)
                    carry.delete(0, nested + OPEN.length); continue
                }
                val keep = maxOf(partialTag(carry, OPEN), partialTag(carry, CLOSE))
                val e = carry.length - keep
                if (e > 0) { emit(carry.substring(0, e), true); carry.delete(0, e) }
                return
            } else {
                // 非思考段：裸 </think> 剥离（重复收尾），其余全是正文。
                val c2 = carry.indexOf(CLOSE)
                if (c2 >= 0) {
                    if (c2 > 0) emit(carry.substring(0, c2), false)
                    carry.delete(0, c2 + CLOSE.length); continue
                }
                val keep = partialTag(carry, CLOSE)
                val e = carry.length - keep
                if (e > 0) { emit(carry.substring(0, e), false); carry.delete(0, e) }
                return
            }
        }
    }

    companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"

        /**
         * 渲染结果尾部形状的判据：`none` / `open-only` / `closed`。
         *
         * ══════════════════════════════════════════════════════════════════
         * 它为什么在这里，而不是留在各自的实现里
         * ══════════════════════════════════════════════════════════════════
         * 生产路径的判据在 `llama_jni.cpp` 的 `classify_rendered_think_tail`，本文件
         * 这份是它的**宿主镜像**（[RenderedPrompt.openAtStartForTest] 的落点），
         * 唯一职责是让 `ThinkStreamTest` 能在没有 C++ 工具链时也钉住这条判据。
         *
         * 镜像一旦与 C++ 侧**分叉**，它给出的就是"判据没漂"的**假信心** ——
         * 上一版正是如此：两边都叫"256 尾窗口"，C++ 取的是 **256 字节**、
         * Kotlin 取的是 **256 个 UTF-16 code unit**。对 ASCII 后缀两者恰好相等
         * （现有用例全是这一类，全绿），而 256 字节 ≈ 85 个汉字 —— 中文 prompt 下
         * 覆盖范围差 3 倍，C++ 说 `none`、镜像说 `open-only`。
         *
         * 现在两件事同时收口：
         *   ① 窗口**单位统一为 UTF-8 字节**（与本判据的消费方一致：后缀要拼回
         *      prompt、prompt 要按字节交给 tokenizer，用字节口径才不会在多字节
         *      后缀上错位）；
         *   ② 判据**本体只有一处实现**（就是这个函数），[RenderedPrompt.openAtStartForTest]
         *      只做转发 —— 镜像不再是"另一套近似"。
         *
         * 窗口从**末尾**取，并且必须落在字符边界上：从字符串尾往前数 256 个字节，
         * 若正好切在一个多字节序列中间，就把开头那几个续字节丢掉（与 C++ 侧
         * `prompt.substr(from)` 的裸字节切片同向 —— 那里切出的半截序列在扫描
         * `<think>` 时天然不参与匹配，无需额外处理）。
         */
        fun classifyTail(renderedPrompt: String): TailShape {
            if (renderedPrompt.isEmpty()) return TailShape.none
            val bytes = renderedPrompt.toByteArray(Charsets.UTF_8)
            var from = if (bytes.size > TAIL_WINDOW_BYTES) bytes.size - TAIL_WINDOW_BYTES else 0
            // 落在续字节（10xxxxxx）上就一直往前挪，保证窗口是合法 UTF-8 的起点。
            while (from < bytes.size && (bytes[from].toInt() and 0xC0) == 0x80) from++
            val tail = String(bytes, from, bytes.size - from, Charsets.UTF_8)
            val at = tail.lastIndexOf(OPEN)
            if (at < 0) return TailShape.none
            // 最后一个开标签之后已经有闭合标签 —— 思考段已闭合，模型接下来写的是正文。
            if (tail.indexOf(CLOSE, at) >= 0) return TailShape.closed
            return TailShape.openOnly
        }

        /** 尾部形状。三档与 C++ 侧 `RenderedThinkShape` 一一对应，不得合并。 */
        enum class TailShape { none, openOnly, closed }

        /**
         * 尾窗口大小，**单位是 UTF-8 字节**。
         *
         * 与 `llama_jni.cpp` 的 `kGenPromptTailWindow` 必须同值同单位 ——
         * 改这里就要改那里，两处由 `run_think_routing_guard.sh` 与
         * `run_render_tail_guard.sh` 同时钉住。
         */
        const val TAIL_WINDOW_BYTES = 256
    }
}
