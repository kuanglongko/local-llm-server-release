package com.xiaowan.localinference

import org.json.JSONObject

/**
 * 采样参数的**唯一**解析 / 校验 / 规整入口。
 *
 * 为什么单独一个文件：`/v1/chat/completions`、`/v1/completions` 与 UI 测试页三处
 * 都各自拼一长串 `optDouble(...)`，默认值靠人肉同步（历史上就漏出过
 * HTTP `min_p=0.05` vs UI `min_p=0` 的分裂），非法值也一律裸传进 native。
 * 收敛到一处后，改默认值 / 加校验只动这一个文件。
 *
 * 设计原则：**非法的显式取值报 400，而不是静默降级**。用户写了 `top_p: 0` 却拿到
 * 完全不同的输出分布、且没有任何提示，比直接报错难排查得多。
 * 缺省（字段不存在）永远走默认值，不影响既有调用方。
 */
data class SamplingParams(
    val temp: Float,
    val topP: Float,
    val minP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val repeatLastN: Int,
    val freqPenalty: Float,
    val presencePenalty: Float,
    val seed: Long,
    val maxTokens: Int,
) {
    /** 生成用默认值，与 UI 参数区默认档保持一致。 */
    companion object {
        const val DEF_TEMP = 0.8f
        const val DEF_TOP_P = 0.95f
        const val DEF_MIN_P = 0f          // 0 = 关闭 min_p；HTTP 侧此前硬编码 0.05，已统一回来
        const val DEF_TOP_K = 0           // 0 = 关闭
        const val DEF_REPEAT_PENALTY = 1f // 1 = 关闭
        const val DEF_REPEAT_LAST_N = 64
        const val DEF_FREQ_PENALTY = 0f
        const val DEF_PRESENCE_PENALTY = 0f
        const val DEF_MAX_TOKENS = 512
        const val MAX_TOKENS_CAP = 8192

        /** top_k 上限。超过词表规模没有意义，同时防止巨大 k 拖慢 top-k 采样。 */
        private const val TOP_K_CAP = 1_000_000

        /** 采样温度上限。LLaMA 系在 2.0 附近已接近均匀分布，再高只会输出乱码。 */
        private const val TEMP_CAP = 5f

        /** 惩罚系数上限（OpenAI 的 presence/frequency 合法区间是 [-2, 2]）。 */
        private const val PENALTY_ABS_CAP = 2f

        /**
         * 把调用方给的 seed 规整成非零的 uint32。
         *
         * 三个坑：
         * 1. native 侧是 `(uint32_t) seed` 截断取低 32 位，低 32 位为 0 时
         *    `llama_sampler_init_dist(0)` 的 xorshift 状态恒为 0 → 采样退化成常量输出；
         * 2. 截断后恰好等于 `LLAMA_DEFAULT_SEED`(0xFFFFFFFF) 时，llama.cpp 会改用
         *    「随机种子」，与调用方指定的固定 seed 语义反转；
         * 3. 不传 seed 时用 `System.nanoTime()`，同一毫秒内的两次请求拿到同一个种子，
         *    而 App 的 busy 闸门保证不会有并发生成，等于两次请求输出完全相同。
         *
         * 因此：无 seed 时用 nanoTime；统一做一次 xorshift32 混合让相邻纳秒分散开；
         * 结果为 0 或 0xFFFFFFFF 时换一个固定非零常量。
         */
        fun normalizeSeed(raw: Long): Long {
            var x = raw.toInt() // 低 32 位
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            return when (val v = x.toLong() and 0xFFFFFFFFL) {
                0L -> 0x9E3779B9L
                0xFFFFFFFFL -> 0x85EBCA6BL
                else -> v
            }
        }

        /** 未指定 seed 时的随机源。 */
        fun randomSeed(): Long = normalizeSeed(System.nanoTime())

        /**
         * 从请求体解析采样参数。返回 (参数, 错误信息) 二选一。
         *
         * 只认「显式给出」的字段做范围校验；字段缺失一律取默认值。
         * 每个字段独立判断是否存在，因此 `top_p: 0` 会被拦下，而 `top_p` 缺失不会。
         */
        fun fromRequest(j: JSONObject): Pair<SamplingParams?, String?> {
            // 注意：每个字段都要在这里就地校验，不能只依赖 `check()`。
            // `check()` 看到的是「解析后的值」，而缺省字段会填默认值——默认值永远合法，
            // 于是任何「能被 readFloat 读出来但超出语义范围」的输入都成了漏网之鱼
            // （实测 top_p=0 / repeat_penalty=0.9 / max_tokens=0 都能溜过 check）。
            val temp = readFloat(j, "temperature", DEF_TEMP) ?: return null to errTemp
            if (!tempOk(temp)) return null to errTemp

            val topP = readFloat(j, "top_p", DEF_TOP_P) ?: return null to errTopP
            if (!topPOk(topP)) return null to errTopP

            val minP = readFloat(j, "min_p", DEF_MIN_P) ?: return null to errMinP
            if (!minPOk(minP)) return null to errMinP

            val topK = readInt(j, "top_k", DEF_TOP_K) ?: return null to errTopK
            if (!topKOk(topK)) return null to errTopK

            val repPen = readFloat(j, "repeat_penalty", DEF_REPEAT_PENALTY)
                ?: return null to errRepeatPenalty
            if (!repeatPenaltyOk(repPen)) return null to errRepeatPenalty

            val repN = readInt(j, "repeat_last_n", DEF_REPEAT_LAST_N) ?: return null to errRepeatLastN
            if (!repeatLastNOk(repN)) return null to errRepeatLastN

            val freqPen = readFloat(j, "frequency_penalty", DEF_FREQ_PENALTY)
                ?: return null to errFreqPenalty
            if (!penaltyOk(freqPen)) return null to errFreqPenalty

            val presPen = readFloat(j, "presence_penalty", DEF_PRESENCE_PENALTY)
                ?: return null to errPresencePenalty
            if (!penaltyOk(presPen)) return null to errPresencePenalty

            // max_tokens 此前是 coerceIn(1,8192) 静默钳制：传 0 或 99999 会悄悄变成 1/8192。
            // 现在显式报错，让调用方知道自己的取值没被采纳。
            val maxTok = readInt(j, "max_tokens", DEF_MAX_TOKENS) ?: return null to errMaxTokens
            if (!maxTokensOk(maxTok)) return null to errMaxTokens

            // seed 缺席（含空串）-> 随机；给了但解析不了 -> 报 400，不再默默随机。
            val seed = readLong(j, "seed")?.let { normalizeSeed(it) } ?: run {
                if (j.has("seed") && !j.isNull("seed") &&
                    (j.opt("seed") as? String)?.trim()?.isNotEmpty() == true) return null to errSeed
                randomSeed()
            }

            return SamplingParams(
                temp = temp, topP = topP, minP = minP, topK = topK,
                repeatPenalty = repPen, repeatLastN = repN,
                freqPenalty = freqPen, presencePenalty = presPen,
                seed = seed, maxTokens = maxTok,
            ) to null
        }

        const val errTemp = "temperature 必须是有限数值（推荐 0~2，<=0 为贪心解码）"
        const val errTopP = "top_p 必须是有限数值且位于 (0,1]（1.0 = 关闭）"
        const val errMinP = "min_p 必须是有限数值且位于 [0,1)"
        const val errTopK = "top_k 必须是 >=0 的整数（0 = 关闭）"
        const val errRepeatPenalty = "repeat_penalty 必须是有限数值且 >=1（1.0 = 关闭；<1 会反转惩罚语义）"
        const val errRepeatLastN = "repeat_last_n 必须是 >=0 的整数（0 = 关闭惩罚窗口）"
        const val errFreqPenalty = "frequency_penalty 必须是有限数值且位于 [-2,2]"
        const val errPresencePenalty = "presence_penalty 必须是有限数值且位于 [-2,2]"
        const val errMaxTokens = "max_tokens 必须是 1~$MAX_TOKENS_CAP 的整数"
        const val errSeed = "seed 必须是整数"

        // ---- 逐字段读取：类型不对 / 非有限值 / 越界都返回 null，由调用方转成 400 ----

        /** 「字段没给」与「字段给了但解析不了」是两种结果，不能都塞进 null。 */
        private sealed interface Read {
            /** 字段缺失 / null / 空串——用默认值。 */
            object Absent : Read
            /** 字段存在但无法解析成数字——报 400。 */
            object Bad : Read
            data class Ok(val v: Double) : Read
        }

        /**
         * 取数值。
         *
         * 空串 / 纯空白按「没给」处理：UI 侧参数框被清空后传进来就是 `""`，
         * 语义上等于「这一项没填」，应当走默认值而不是弹「参数非法」。
         * 非空但解析不了（如 "abc"、"1.5.0"）才算非法——这类输入此前会被
         * `?: 默认值` 静默吞掉，用户以为参数生效了其实没有。
         */
        private fun read(j: JSONObject, key: String): Read {
            if (!j.has(key) || j.isNull(key)) return Read.Absent
            val v = j.opt(key)
            val d: Double? = when (v) {
                is Number -> v.toDouble()
                is String -> {
                    val t = v.trim()
                    if (t.isEmpty()) return Read.Absent
                    t.toDoubleOrNull()
                }
                else -> null
            }
            return if (d == null) Read.Bad else Read.Ok(d)
        }

        private fun readFloat(j: JSONObject, key: String, def: Float): Float? = when (val r = read(j, key)) {
            is Read.Absent -> def
            is Read.Bad -> null
            is Read.Ok -> {
                val f = r.v.toFloat()
                if (r.v.isFinite() && f.isFinite()) f else null
            }
        }

        private fun readInt(j: JSONObject, key: String, def: Int): Int? = when (val r = read(j, key)) {
            is Read.Absent -> def
            is Read.Bad -> null
            is Read.Ok -> {
                val d = r.v
                if (!d.isFinite() || d != Math.floor(d) ||
                    d < Int.MIN_VALUE.toDouble() || d > Int.MAX_VALUE.toDouble()) null
                else d.toInt()
            }
        }

        private fun readLong(j: JSONObject, key: String): Long? = when (val r = read(j, key)) {
            is Read.Absent -> 0L
            is Read.Bad -> null
            is Read.Ok -> {
                val d = r.v
                if (!d.isFinite() || d != Math.floor(d) ||
                    d < Long.MIN_VALUE.toDouble() || d > Long.MAX_VALUE.toDouble()) null
                else d.toLong()
            }
        }

        // ---- 范围校验（UI 侧与 HTTP 侧共用，避免两处规则漂移）----

        /** temp<=0 = 贪心，合法；上界防均匀乱码。 */
        fun tempOk(t: Float) = t.isFinite() && !t.isNaN() && t <= TEMP_CAP

        /**
         * top_p 合法区间 (0,1]（OpenAI 同）。
         * 注意 `<=0` 不能算「关闭」：native 侧 `topP > 0 && topP < 1` 才会入链，
         * 传 0/负数等于**静默失效**——用户以为关掉了却没关，比直接报错难排查。
         * 要「不启用」就传 1.0。
         */
        fun topPOk(p: Float) = p.isFinite() && p > 0f && p <= 1f

        /** min_p >= 1 会让所有候选被砍光（+min_keep=1 兜底），判非法。 */
        fun minPOk(p: Float) = p.isFinite() && p >= 0f && p < 1f

        fun topKOk(k: Int) = k in 0..TOP_K_CAP

        /**
         * repeat_penalty 合法区间 [1, ∞)：1.0 = 关闭。
         * 小于 1 时 llama.cpp 的除法变换会把语义**反转**成「奖励高频 token」，
         * 表现为越重复越爱说车轱辘话——这几乎不可能是调用方的本意，故直接判非法。
         * （此前该值裸传进 native，0.9 会被当成"有效惩罚"用上。）
         */
        fun repeatPenaltyOk(p: Float) = p.isFinite() && p >= 1f

        fun repeatLastNOk(n: Int) = n >= 0

        /** OpenAI 的 presence/frequency penalty 合法区间是 [-2,2]，负值 = 鼓励重复。 */
        fun penaltyOk(p: Float) = p.isFinite() && p >= -PENALTY_ABS_CAP && p <= PENALTY_ABS_CAP

        fun maxTokensOk(n: Int) = n in 1..MAX_TOKENS_CAP
    }

    /**
     * 全字段总校验；返回 null 表示通过，否则返回可展示给用户的中文原因。
     *
     * [SamplingParams.Companion.fromRequest] 在解析时已逐字段校验，因此正常路径下
     * 拿到的实例一定通过。这里保留总校验是给「不经 fromRequest 手工构造」的调用方兜底
     * （例如将来加测试或从偏好设置直接构造），避免漏掉某条规则。
     */
    fun check(): String? = when {
        !tempOk(temp) -> errTemp
        !topPOk(topP) -> errTopP
        !minPOk(minP) -> errMinP
        !topKOk(topK) -> errTopK
        !repeatPenaltyOk(repeatPenalty) -> errRepeatPenalty
        !repeatLastNOk(repeatLastN) -> errRepeatLastN
        !penaltyOk(freqPenalty) -> errFreqPenalty
        !penaltyOk(presencePenalty) -> errPresencePenalty
        !maxTokensOk(maxTokens) -> errMaxTokens
        else -> null
    }

    /** 落日志用的一行摘要（不含 seed 原始值排障时够用）。 */
    fun describe(): String =
        "temp=$temp top_p=$topP min_p=$minP top_k=$topK " +
            "repeat=[$repeatPenalty/$repeatLastN] freq=$freqPenalty pres=$presencePenalty " +
            "max_tokens=$maxTokens"
}
