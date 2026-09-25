package com.xiaowan.localinference

/**
 * 一次模板渲染的产物：prompt 原文 + 它的**语义标注**。
 *
 * 为什么它是独立文件而不是 `LlmEngine` 的内部类：判据必须能被**离线单测**钉住，
 * 而 `LlmEngine` 依赖 android.os.Build 与 native 符号，宿主下编不了。
 * 抽出来之后 `tools/run_thinking_tests.sh` 用与线上**同一份**实现跑断言。
 *
 * 渲染结果 + 它的**语义标注**：这一轮的生成后缀里是否已经有一个未闭合的
 * `<think>`（即"思考段由 prompt 开好、模型只负责写、最后由模型自己 `</think>` 收尾"）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么这个标注不能由 Kotlin 侧扫字符串得出
 * ══════════════════════════════════════════════════════════════════════
 * 判据原先就写在旁边（`ThinkStream.promptEndsWithOpenThink`）：取渲染结果尾部窗口，
 * 找最后一个 `<think>`、要求其后没有 `</think>`。模板形状一变就静默失灵 ——
 * 它把 MiniCPM5 在 `enable_thinking=true` 下吐的 `<think>\n`（**只有开标签**）
 * 判成"思考段已开"。而模板自带的开标签只说明"模板自己吐了一个开标签"，
 * 并不说明"闭合标签会出现在模型输出里"：模型仍然要自己吐一个 `</think>` 来收尾，
 * 那正是状态机该去配对的东西。判成"已开"的后果是——模型吐的 `</think>` 被当作
 * 「思考段内的裸闭合标签」静默剥离，整段思考与正文一起留在 reasoning 里，
 * 客户端拿到的 `content` 远短于真实回答（2026-09-19 真机报障）。
 *
 * 所以判据改由**渲染发生的地方**给出（`llama_jni.cpp` 的
 * `classify_rendered_think_tail`，只看库自己算的生成后缀），随 prompt 一起返回；
 * 同一个值同时用于 prompt 软开关判定与 `ThinkStream.openAtStart`，
 * 两条路径不可能再对不上。
 *
 * @param text 渲染后的 prompt（已剥掉 native 的语义前缀）
 * @param openAtStart 生成后缀里有一个未闭合的 `<think>`；`ThinkStream` 据此起始于思考段
 * @param generationSuffix 这一轮**模型要接着续写的那段尾巴**（模板 `add_generation_prompt`
 *   分支的产物，如 `<|im_start|>assistant\n` 或 `<|im_start|>assistant\n<think>\n`）。
 *
 *   ══════════════════════════════════════════════════════════════════════
 *   它为什么必须由**渲染侧**给出，且必须一路传到 grammar 推导那一跳
 *   ══════════════════════════════════════════════════════════════════════
 *   GBNF 采样器约束的是「**生成后缀之后**」的输出，而生成后缀是渲染时按
 *   `add_generation_prompt` 与 `enable_thinking` 决定的（模板分支里还带不带思考块），
 *   用常量近似必然在一部分模板上错。真实故障（真机 `MiniCPM5-2B-Q4_K_M` + json_schema）：
 *
 *     content = ":assistant\n{...}"        ← 被截掉头部（客户端丢掉 `<|im_start|` 一段）
 *     content = "<|im_start|>assistant\n[ ]" ← 完整形态：模型先把生成后缀自己吐了一遍
 *
 *   两例的内核是同一件事：grammar 按"prompt 不以生成后缀结尾"推导，于是模型必须
 *   自己**补出**那个尾巴，才轮到去满足 grammar（[ ] 是空数组，JSON 里最短的合法
 *   数组 —— 模型插值到这里就没话可说了）。约束与实际 prompt 对齐之后，这段多余输出
 *   就没有存在的余地。取值经 [RequestContext.genPromptArg] 传给 `newSampler`。
 */
class RenderedPrompt(
    val text: String,
    val openAtStart: Boolean,
    val generationSuffix: String? = null,
) {
    companion object {
        /**
         * native 侧在 prompt 前置一个字节携带语义标注：
         * `I` = openAtStart true（Leading **I**n），`O` = false（**O**ut）。
         * 用前缀而不是第二个 JNI 出口：少一次 jstring 往返，也就不存在
         * "两次取值不一致"的新窗口（那正是本类要消灭的那类故障）。
         */
        private const val LEAD_IN = 'I'
        private const val LEAD_OUT = 'O'

        /**
         * 渲染结果尾部形状的**宿主侧镜像**，只为离线单测存在（生产路径不用它 ——
         * 生产路径用 native 返回的语义前缀，见 [parse]）。
         *
         * 为什么要有镜像：`classify_rendered_think_tail`（C++）是这次故障的判据本体，
         * 而 C++ 在宿主上没有工具链跑不了。镜像与它的关系由两组用例钉住：
         *   · `ThinkStreamTest` 第 8 组用例钉镜像的**行为**（含 MiniCPM5/LFM2.5 的真实后缀）；
         *   · 源码级守卫钉两边**形状同构**（同一组字面量、同一个尾窗口**值且同单位**），
         *     任一处分叉就会红 —— 而不是等真机上再发现判据漂了。
         *
         * ⚠ 这个函数**只做转发**，判据本体是 [ThinkStream.classifyTail]，就是它一个。
         * 上一版在这里另写了一套实现（`substring(length - 256)`），于是"256"两边
         * 各是一个单位：C++ 是字节、这里是 UTF-16 code unit，中文 prompt 下差 3 倍，
         * 而现有用例全是 ASCII 后缀 —— 分叉了却全绿。判据**只有一处实现**才是这条
         * 的收口方式，而不是"再补一条用例"。
         */
        fun openAtStartForTest(renderedPrompt: String): Boolean =
            ThinkStream.classifyTail(renderedPrompt) == ThinkStream.Companion.TailShape.openOnly

        /** 生成后缀长度的固定宽度（十六进制字符数），与 native 侧逐字一致。 */
        private const val SUFFIX_HEX_LEN = 8

        fun parse(raw: String): RenderedPrompt = when {
            raw.isEmpty() -> RenderedPrompt(raw, openAtStart = false)
            raw[0] == LEAD_IN -> parseBody(raw.substring(1), openAtStart = true)
            raw[0] == LEAD_OUT -> parseBody(raw.substring(1), openAtStart = false)
            // 旧 native（没带前缀）时退化为 false：宁可让状态机自己去解析标签，
            // 也不要把正文吞进思考段 —— 后者正是这次要修的症状。
            else -> RenderedPrompt(raw, openAtStart = false)
        }

        /**
         * 剥掉语义前缀之后的正文：`<8 位十六进制后缀字节数><后缀原文><prompt 原文>`。
         *
         * 为什么要按**长度前缀**切而不是找第一个换行：生成后缀自己就含换行
         * （`<|im_start|>assistant\n`），按分隔符切必然切错。
         *
         * 取不到（长度段不是 8 位十六进制 / 长度越界 / 字节数超长）时退化为
         * `generationSuffix = null` —— 即"未知"，让 native 走旧口径。
         * **不得**退化成空串：空串在 native 侧的含义是"确认这一轮没有生成后缀"，
         * 用于 `add_generation_prompt=false` 的裸补全；把"不知道"说成"确认没有"
         * 会给一个**有**生成后缀的 prompt 施加"后缀之后"的 grammar ——
         * 与本次故障同一种错、相反的方向。
         */
        private fun parseBody(body: String, openAtStart: Boolean): RenderedPrompt {
            val unknown = RenderedPrompt(body, openAtStart = openAtStart)
            if (body.length < SUFFIX_HEX_LEN) return unknown
            val hex = body.substring(0, SUFFIX_HEX_LEN)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return unknown
            val n = hex.toIntOrNull(16) ?: return unknown
            // 越界（n 超过剩余正文）说明长度段与正文不是同一次拼出来的 -> 不猜。
            if (n < 0 || body.length < SUFFIX_HEX_LEN + n) return unknown
            val suffix = body.substring(SUFFIX_HEX_LEN, SUFFIX_HEX_LEN + n)
            val prompt = body.substring(SUFFIX_HEX_LEN + n)
            // 空后缀必须与原样保留的 null 分开（见上面的说明）。
            return RenderedPrompt(prompt, openAtStart = openAtStart, generationSuffix = suffix)
        }
    }
}
