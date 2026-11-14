package com.xiaowan.localinference

import org.json.JSONObject

/**
 * OpenAI 协议里**三个此前完全没被读**的字段：`max_completion_tokens`、`logit_bias`、
 * `logprobs`/`top_logprobs`。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么这不算"新特性"，算"协议残缺"
 * ══════════════════════════════════════════════════════════════════════════
 * `stop` 那一轮定下的准则：补齐一个协议字段不该被算成新特性，因为**缺失的字段
 * 是被静默忽略的**。这与"这个功能我没做"是两件事：客户端以为自己设了值、
 * 服务端照 200 回、输出却按默认值走 —— 调用方拿到的不是错误，是一个**看起来正常
 * 但参数没生效**的结果。三个字段的失效形态全是这一种：
 *
 *   · `max_completion_tokens` —— 新版 OpenAI SDK 的默认字段名（`max_tokens`
 *     在 chat 端已标记 deprecated）。只认 `max_tokens` 的话，SDK 升级后的客户端
 *     每一轮都跑满 512（默认值），而它以为自己设的是 100。
 *   · `logit_bias` —— 请求里根本没这个字段，传了等于没传。
 *   · `logprobs` / `top_logprobs` —— 同上；而且它是这个仓库最缺的那类能力：
 *     "这一次生成的置信度是多少"，分类/校准场景直接要它。
 *   · `n` —— 同上，**而且它的失效形态最容易被漏掉**：`n: 2` 时服务端只回 1 条
 *     而 HTTP 照 200，客户端拿到的是"结构完全合法、只是少了一条"的响应。
 *     OpenAI SDK 按 `choices.length` 聚合，`n=2` 却只有一条时它既不报错也不补 ——
 *     调用方以为自己在做 2 选 1 采样，实际拿到的是单条。本仓库是单请求单轮，
 *     `parallelN` 是引擎级配置（见 `SamplingParams`），**不是请求级**能力，
 *     所以 `n > 1` 只能明确拒绝，不能静默降级成一条。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 分界线：什么是"协议级错误"（400），什么是"能力边界"（接受 + 落日志）
 * ══════════════════════════════════════════════════════════════════════════
 * 与 [JsonSchemaFormat] / [StopSequences] 同一套分界，不另立一份：
 *
 *   **400**：类型/取值写错，无论谁来用都是错的 ——
 *     `max_completion_tokens: 0` 或 `"abc"`、`logit_bias` 不是对象、
 *     bias 值不是数值、`top_logprobs` 非整数或越界、`top_logprobs` 给了而
 *     `logprobs` 没给（OpenAI 自己也报 400，两者是绑定关系）。
 *
 *   **接受 + 落日志（不失败请求）**：字段本身合法、但**本实现撑不起**的取值 ——
 *     · `logit_bias` 条数超过内部上限（性能边界，不是合法性边界 ——
 *       `StopSequences.MAX_SEQUENCES` 那段已经用一次真实事故说明过这条）；
 *     · `logprobs: true`：见文件末尾那段「为什么 `logprobs` 收下了却仍返回 null」。
 *
 * 而 `logit_bias` 里**词表中没有的 token id** 既不是 400 也不是"能力边界"——
 * 它在 native 侧是一次天然的空操作（`llama_sampler_init_logit_bias` 的语义是
 * "候选表里 id 相等就加偏置"，候选表里没有它就不匹配），所以连日志都不必为它单独打：
 * 挂载条数那一行已经说了实际挂了几条。
 *
 * 三条都有同一个不变量：**默认（字段不存在）必须与引入本特性之前逐字节同行为**。
 */
object ProtocolFields {

    /**
     * `max_completion_tokens` 的别名关系与上限。
     *
     * 上限与 `max_tokens` **必须同一个** [SamplingParams.MAX_TOKENS_CAP]：
     * 两个字段名指向同一个语义量，各自一份上限必然会分叉 —— 那时
     * `max_tokens=99999` 报 400、`max_completion_tokens=99999` 通吃（或反过来），
     * 调用方看到的是"换个字段名就能用"，而不是"我越界了"。
     */
    const val KEY_MAX_COMPLETION = "max_completion_tokens"

    /** 单条 `logit_bias` 的合法区间，与 OpenAI 一致。 */
    const val LOGIT_BIAS_MIN = -100.0
    const val LOGIT_BIAS_MAX = 100.0

    /**
     * 参与偏置的 token 条数上限（**性能边界**，超出只截断）。
     *
     * 与 [StopSequences.MAX_SEQUENCES] 同族：偏置要在词表（15 万级）上建一张
     * 偏置表，条数直接决定每步采样的一次查表开销与建表的分配量。
     * 但"条数多"不是协议错误 —— OpenAI 文档写"最多 300 条"却不因此拒绝请求。
     * 所以超出只截断 + 落日志，绝不 400。
     */
    const val MAX_LOGIT_BIAS = 300

    /** `top_logprobs` 的合法区间，与 OpenAI 一致（0~20，且是整数）。 */
    const val TOP_LOGPROBS_MAX = 20

    const val errMaxCompletionTokens =
        "max_completion_tokens 必须是 1~${SamplingParams.MAX_TOKENS_CAP} 的整数"
    const val errMaxTokensConflict =
        "max_tokens 与 max_completion_tokens 不能同时给出且取值不同（两者是同一个量，请只用一个）"
    const val errLogitBiasType = "logit_bias 必须是对象（token_id -> 偏置值），且 token_id 必须是十进制整数"
    const val errLogitBiasValue = "logit_bias 的取值必须是有限数值且位于 [-100,100]"
    /**
     * `n`（返回多少个候选）唯一被支持的取值。
     *
     * 本仓库一轮请求只生成一条序列：`parallelN` 是**引擎级**配置（载模型时定的
     * 并行槽数，见 `SamplingParams` 与 native 侧的 `n_parallel`），不是"这一次
     * 请求要几条"。把它当请求级用会引出一个本地推理根本不划算的东西 ——
     * 本地端每多一条候选就多一份 KV 与一次完整前向，而 `n` 的常见用法
     * （best-of-N + 校验）在端侧的成本远高于云端。
     *
     * 所以这里只接受 `n` 缺省或 1，其余一律 400。**不接受"截成 1 条"**：
     * 那正是本文件开头要消灭的形态 —— 客户端设了 2、拿到 1 条、HTTP 200，
     * 而它唯一的线索是"choices 怎么只有一条"（很多 SDK 根本不看）。
     */
    const val N_SUPPORTED = 1

    /** `n > 1` 的 400：明确说清"不支持"而不是"给了一条"。 */
    const val errNUnsupported =
        "n 只支持 1（本服务一轮请求只生成一条；并行多槽是引擎级配置，不是请求级参数）"

    /** `n` 本身写错（非整数 / 小于 1）的 400 —— 与"不支持"是两件事。 */
    const val errNType = "n 必须是 >= 1 的整数"

    const val errLogprobsType = "logprobs 必须是布尔值"
    const val errTopLogprobsType = "top_logprobs 必须是 0~$TOP_LOGPROBS_MAX 的整数"
    const val errTopLogprobsNeedsLogprobs = "top_logprobs 需要同时给出 logprobs=true"

    /** `logit_bias` 的一条：token id + 偏置。 */
    data class Bias(val tokenId: Int, val bias: Float)

    /**
     * 解析结果。
     *
     * [maxTokens] 为 null = 两个字段都没给（调用方用 [SamplingParams.DEF_MAX_TOKENS]）；
     * [logitBias] 为 null = 没给（`logit_bias: {}` 与"没给"等价，都是"不偏置"）；
     * [logprobs] 为 false 时 [topLogprobs] 必为 null。
     *
     * [notices] 是**必须落日志**的一串说明（偏置被截断、`logprobs` 被无害接受）。
     * 放在返回值里而不是就地打日志：这个文件是纯逻辑（无 android import），
     * 要能被宿主单测直接跑 —— 而"打了没打"这种副作用在宿主编译产物里验不了。
     *
     * ⚠ [n] 目前**没有任何消费方**，别从它反推"校验已生效"。`n >= 2` 的 400 是
     * [fromRequest] 里那条 `else -> return null to errNUnsupported` **早退**出来的，
     * 而这里这个格子只在 `n` 合法（缺省或 1）时才有值 —— 也就是它恒等于
     * [N_SUPPORTED]、恒为"通过校验"的那一档。真正的判据在**早退支**里，
     * 不在 `Parsed` 上：下一个人若把校验改成"填进 `Parsed` 再由调用方判"，
     * 那两处端点（`HttpApi.kt` 的 chat / completions）都得各自补一遍，
     * 而漏一处不会报错 —— 只会静默回落成"给一条"。留在这里就是为了让这个
     * 改动**必须先看见这句话**。
     */
    data class Parsed(
        val maxTokens: Int?,
        val logitBias: List<Bias>?,
        val logprobs: Boolean,
        val topLogprobs: Int?,
        val n: Int,
        val notices: List<String>,
    )

    /**
     * 从请求体解析这三个字段。返回 (结果, 错误信息) 二选一。
     *
     * 这里**不**做词表校验，也不该做：`logit_bias` 的 key 是 token id，而
     * `llama_sampler_init_logit_bias` 的语义是"候选表里 id 相等就加偏置"——
     * 不在词表里的 id 自然是一次**空操作**（不越界、不崩）。要在这里判"这个词表里
     * 有没有这个 id"就得先有一份 id→token 的反查口，而本仓库只有 `nativeLoadModel`
     * 之后才拿得到词表；多出来的那条分叉（有模型时 400、无模型时通吃）比它拦下的
     * 东西更贵。所以只做**格式**校验（必须是十进制整数、取值在区间内），
     * 语义上的"这个 id 不存在"交给 native 侧的空操作 + 一行日志。
     */
    fun fromRequest(j: JSONObject): Pair<Parsed?, String?> {
        val notices = ArrayList<String>()

        // ── max_completion_tokens：与 max_tokens 是**同一个量**的两个名字 ──────
        // 两个都给时只在**取值相同**的情况下接受。理由：取哪个都"对"、但也都
        // 会让另一个静默失效 —— 而调用方同时写两个字段的动机通常是"兼容新旧 SDK"，
        // 那时他期望的就是同一个值。取值不同则是真的写错了，报 400 比替他选一个安全。
        val legacy = readIntField(j, "max_tokens")
        val alias = readIntField(j, KEY_MAX_COMPLETION)
        val maxTokens: Int? = when {
            legacy == null && alias == null -> null
            alias == null -> legacy
            legacy == null -> alias
            legacy == alias -> legacy
            else -> return null to errMaxTokensConflict
        }
        // 范围校验：与 SamplingParams.fromRequest 的 max_tokens 走**同一条**判据
        // （`maxTokensOk`），不另写一份。这里只校验这个字段**自己的**显式取值；
        // 缺省时由 SamplingParams 填默认值（默认值恒合法，不必重复判）。
        if (legacy != null && !SamplingParams.maxTokensOk(legacy)) return null to errMaxCompletionTokens
        if (alias != null && !SamplingParams.maxTokensOk(alias)) return null to errMaxCompletionTokens

        // ── logit_bias ────────────────────────────────────────────────────
        val bias = ArrayList<Bias>()
        if (j.has("logit_bias") && !j.isNull("logit_bias")) {
            val o = j.opt("logit_bias")
            if (o !is JSONObject) return null to errLogitBiasType
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                // key 必须是十进制整数串：OpenAI 用它当 token id。`"1.5"` / `"abc"`
                // / `" 12"` 都判非法 —— 静默忽略会让调用方以为偏置生效了。
                // 注意 `toIntOrNull` 接受前导 `+` 与 `-`，这里显式只收纯数字。
                // `toIntOrNull` 会收 `+12` 与 `-12`（后者让它过是因为 `(-12).toString()`
                // 恰好还原成 key 本身），所以**先判非负**再判"往返一致"。
                // token id 是词表下标，负数没有任何含义 —— 静默收下它就是一次
                // 语义错、且 native 侧的空操作会让它静默消失。
                val id = k.toIntOrNull()?.takeIf { it >= 0 && it.toString() == k }
                    ?: return null to errLogitBiasType
                val v = o.opt(k)
                val d = when (v) {
                    // Int 与 Double 都收；布尔/字符串/对象一律非法（字符串 "0.5"
                    // 也非法：OpenAI 的取值是数值，字符串形式的容忍度各实现不同，
                    // 猜错比报错更难查）。
                    is Number -> v.toDouble()
                    // `JSONObject.NULL` 会走 `is Number` 之外的分支 —— 一并落进非法。
                    else -> return null to errLogitBiasValue
                }
                if (!d.isFinite() || d < LOGIT_BIAS_MIN || d > LOGIT_BIAS_MAX) {
                    return null to errLogitBiasValue
                }
                bias.add(Bias(id, d.toFloat()))
            }
        }
        // 条数超限只截断（性能边界，不是合法性边界，见 [MAX_LOGIT_BIAS]）。
        // 截断必须留痕：偏置少了几条等于"输出不完全按调用方要的走"，
        // 而这个症状与"模型不听话"长得一样。
        // 截断前**必须先排序**：`bias` 是按 `o.keys()` 的顺序攒的，而 `logit_bias`
        // 是 JSON **对象** —— `org.json` 内部是 `HashMap`，`keys()` 是 **String 的
        // 哈希序**，既不是插入序也不是字典序。直接 `subList(0, N)` 取到的是
        // "哈希序前 N 个"，而日志说"前 N 条" —— 调用方会按一个**错误的假设**去
        // 排查输出（实测插 302 条时丢的是 id 109 / 229，留下的却是 300 / 301）。
        // 偏置是调用方给的**约束集合**，"按 token id 升序取前 N"至少可解释、可复现。
        val ordered = bias.sortedBy { it.tokenId }
        val trimmed: List<Bias> = if (ordered.size > MAX_LOGIT_BIAS) {
            // 截断必须留痕（见上）：偏置少几条的症状与"模型不听话"长得一样。
            // 这里写"去重后"是笔误级的准确：本实现不额外去重（JSON 对象的 key
            // 天然唯一），照抄 StopSequences 的措辞会让运维去数一个不存在的步骤。
            notices += "logit_bias ${ordered.size} 条，超过内部上限 $MAX_LOGIT_BIAS 条，" +
                "已按 token id 升序保留前 $MAX_LOGIT_BIAS 条"
            ordered.subList(0, MAX_LOGIT_BIAS).toList()
        } else ordered

        // ── n：只认 1，其余一律 400（见 [N_SUPPORTED]） ────────────────────
        // 缺省与显式 1 都是"一条"，两者必须**逐字节同行为** —— 这是本文件那条
        // 硬不变量（新增可选字段不给时与引入前相同）在这一格上的形态。
        // 读取复用 readIntField：它把"读不出整数"折成必然越界的哨兵，
        // 于是 `n: "abc"` 与 `n: 0` 都落进下面同一条判据，不必各写一份。
        val nRaw = readIntField(j, "n")
        val n = when {
            nRaw == null -> N_SUPPORTED
            // 非整数（哨兵）或小于 1：**格式/取值**错误，与"不支持 2"分开报 ——
            // 两者对调用方的含义不同（一个是"你写错了"，一个是"我可以但没做"）。
            nRaw == MALFORMED || nRaw < 1 -> return null to errNType
            nRaw == N_SUPPORTED -> nRaw
            // 合法但撑不起：明确说"不支持"，不静默给一条。
            else -> return null to errNUnsupported
        }

        // ── logprobs / top_logprobs ───────────────────────────────────────
        // 两者是**绑定**关系（OpenAI 同）：`top_logprobs` 单独给是协议错误，
        // 因为它唯一的用途就是"在 logprobs 里多带几个候选"，没有 logprobs 时无意义。
        val lpRaw = if (j.has("logprobs") && !j.isNull("logprobs")) j.opt("logprobs") else null
        val logprobs = when (lpRaw) {
            null -> false
            is Boolean -> lpRaw
            else -> return null to errLogprobsType
        }
        val tlRaw = if (j.has("top_logprobs") && !j.isNull("top_logprobs")) j.opt("top_logprobs") else null
        val topLogprobs: Int? = when {
            tlRaw == null -> null
            tlRaw is Number -> {
                val d = tlRaw.toDouble()
                if (!d.isFinite() || d != Math.floor(d) || d < 0 || d > TOP_LOGPROBS_MAX) {
                    return null to errTopLogprobsType
                }
                d.toInt()
            }
            else -> return null to errTopLogprobsType
        }
        if (tlRaw != null && !logprobs) return null to errTopLogprobsNeedsLogprobs
        // ⚠ 收下 `logprobs: true` 但**不产生**任何 logprobs 数据（响应里
        // `logprobs` 恒为 null）。这里落一行让人查得到，理由见文件末尾那段。
        if (logprobs) notices += logprobsAcceptedNotice(topLogprobs)

        return Parsed(
            maxTokens = maxTokens,
            logitBias = if (trimmed.isEmpty()) null else trimmed,
            logprobs = logprobs,
            topLogprobs = topLogprobs,
            n = n,
            notices = notices,
        ) to null
    }

    /**
     * `logprobs: true` 被接受、但本轮不产出数据的说明。
     *
     * 为什么是"接受 + 留痕"而不是 400：`logprobs` 是**合法的 OpenAI 字段**，
     * 报 400 会把一个协议正确的客户端整个请求拒掉（正是 `stop` 那轮踩过的坑）。
     * 而返回 `"logprobs": null` 与"模型没给出置信度"在 SDK 侧同形，所以
     * 这条**必须**在日志里说清，让"为什么没有 logprobs"有据可查。
     */
    fun logprobsAcceptedNotice(topLogprobs: Int?): String =
        "logprobs=true 已接受，但本轮不产出逐 token 置信度（响应里 logprobs 为 null）；" +
            "top_logprobs=${topLogprobs ?: "未给"}"

    /**
     * 把 `max_tokens` / `max_completion_tokens` 读成一个**整数候选**；没给返回 null。
     *
     * ⚠ 「读不出整数」**故意不在这里报错**，而是折成 [MALFORMED]（一个必然越界的
     * 哨兵值）交给下游那**唯一一条** `SamplingParams.maxTokensOk` 判 ——
     * `max_tokens` 的合法性在 [SamplingParams] 里已经有一份完整判据（含 400 文案），
     * 这里再写第二份必然漂移。于是本函数只回答"是不是一个整数"，
     * "在不在 1..8192"由那一条说了算，两个字段名也就自动共用同一份上限。
     *
     * 为什么哨兵取 [Int.MAX_VALUE] / [Int.MIN_VALUE] 而不是 null：null 已被
     * "字段没给"占用（缺省要走默认值），两者混一谈就会让 `max_tokens: "abc"`
     * 静默回落成默认值 —— 正是本文件开头要修的那类问题。
     */
    private fun readIntField(j: JSONObject, key: String): Int? {
        if (!j.has(key) || j.isNull(key)) return null
        val v = j.opt(key)
        val d = when (v) {
            is Number -> v.toDouble()
            // 字符串数值也收（UI 参数框传进来的就是字符串）；非数值落哨兵。
            is String -> v.trim().toDoubleOrNull() ?: return MALFORMED
            else -> return MALFORMED
        }
        if (!d.isFinite() || d != Math.floor(d) ||
            d < Int.MIN_VALUE.toDouble() || d > Int.MAX_VALUE.toDouble()) return MALFORMED
        return d.toInt()
    }

    /** 「读得出字段、但读不出合法整数」的哨兵：必然被 `maxTokensOk` 判掉。 */
    private const val MALFORMED = Int.MAX_VALUE

    /** 落日志用的一行摘要；没给任何字段时返回空串（调用方据此决定不打这行）。 */
    fun describe(p: Parsed): String {
        val sb = StringBuilder()
        // ⚠ 每个取值都必须写成 `${...}`，**不能**写 `$p.maxTokens` ——
        // 后者的模板求值只取 `p`，`.maxTokens` 会被当成普通文本原样拼进去，
        // 于是日志里出现的是 `max_completion=Parsed(...).maxTokens` 这种鬼东西。
        // 它编得过、跑得过、只是那一行读数没用了（本轮自测当场抓到过一次）。
        if (p.maxTokens != null) sb.append("max_completion=${p.maxTokens} ")
        if (p.logitBias != null) sb.append("logit_bias=${p.logitBias.size} 条 ")
        if (p.logprobs) sb.append("logprobs=true top=${p.topLogprobs ?: "-"} ")
        // `n` 故意不打：它在 [Parsed] 里恒等于 [N_SUPPORTED]（`n >= 2` 在
        // [fromRequest] 就早退了，走不到这里），打出来只会是一行固定读数，
        // 而"每行都必须和调用方给的东西对得上"才是这一串的意义。
        // 别为了补偿它去给 [Parsed] 加一个"是否显式给过"的格子 —— 那是给
        // 一行日志加状态，而真正需要留痕的 `n >= 2` 已由 400 文案负责。
        return sb.toString().trim()
    }

    /**
     * `logit_bias` 拆成 native 侧要的两个平行数组（id 与 bias）。
     *
     * 为什么是**平行数组**而不是一个 `"12:0.5"` 这样的字符串：
     * native 侧要的正是 `llama_logit_bias{token, bias}` 的结构数组，字符串形态
     * 会在 CC 侧再解析一遍 —— 而那一遍的判据（分隔符、小数格式）与这里的 KOTLIN
     * 判据必然分叉，分叉的症状是"偏置 silently 丢掉几条"。平行数组没有第二遍解析。
     *
     * 返回 null 表示"没有偏置"（native 侧据此跳过挂载，不进链）。
     */
    fun biasIds(p: Parsed): IntArray? = p.logitBias?.map { it.tokenId }?.toIntArray()

    /** 与 [biasIds] 一一对应。两者长度必须相等，由本函数与 [biasIds] 同源保证。 */
    fun biasValues(p: Parsed): FloatArray? = p.logitBias?.map { it.bias }?.toFloatArray()
}

// ══════════════════════════════════════════════════════════════════════════════
// 为什么 `logprobs` 收下了却仍然返回 null
// ══════════════════════════════════════════════════════════════════════════════
// 它是**故意未接通**的那一格，写在这里免得下一个人以为漏了。
//
// llama.cpp 侧的 `llama_sampler_*` 只给"被选中的那个 token"，逐 token 的
// **候选分布**要走 `llama_sampler_get_candidates` 或让采样链把候选表交出来 ——
// 而本仓库的采样链是自己搭的（见 `llama_jni.cpp:nativeNewSampler`，链尾是
// `llama_sampler_init_dist`），没有任何一环把候选表回传给宿主。
// 要接上它需要：
//   ① 在链尾之后**再挂一个观察者采样器**（与 stop 采样器同一形态），
//      在 `accept` 里记下"这一拍 prob 最高的 N 个"；
//   ② 把这 N 个（token 文本 + 概率）按 token 序列带回 Kotlin，且要**与
//      已下发的增量块对齐**（`TopLogprobs` 是按"每个生成 token"给的，
//      而本仓库的增量块是按字节切的）；
//   ③ 非流式与流式两种形状各拼一份（`choices[].logprobs.content[]`）。
//
// 这三步里第 ② 步是真正贵的：本仓库有 [ThinkStream]（思考段暂存）、
// [ToolCalls]（工具调用缓冲）两处会**改变下发时机**的中间层，而 logprobs
// 必须与**最终下发的那串文本**逐 token 对齐 —— 对不齐时客户端拿到的
// 置信度会串位，而"串位的置信度"比"没有置信度"坏得多（后者查得到，
// 前者只会让你得出错误结论）。
//
// 所以本轮只做①之外的全部：**字段收下、校验到位、留痕说清没数据**。
// 这与 [JsonSchemaFormat] 里"库转换不了这个 schema 就降级 + 落日志"同一套取舍：
// 协议面先补齐（不静默 400、不静默忽略），能力面单独一轮再做。
