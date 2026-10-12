package com.xiaowan.localinference

// ProtocolFields 的离线单测：max_completion_tokens 别名、logit_bias 的格式判据、
// logprobs/top_logprobs 的绑定关系，以及"字段不存在 = 逐字节同旧行为"这条不变量。
//
// 只依赖 org.json（运行期用真实现，见 tools/run_protocol_fields_tests.sh）。
//
// 每个断言对应一种**静默失效**：客户端设了值、拿到 200、而参数没生效（或生效了
// 一半），调用方从输出上分不出来。这类问题在真机上的排查成本比编译错误高一个量级，
// 所以全部在宿主侧钉住。
import org.json.JSONObject

private var fail = 0
private fun ck(name: String, cond: Boolean) {
    if (cond) println("PASS  $name") else { println("FAIL  $name"); fail++ }
}

private fun ok(j: JSONObject): ProtocolFields.Parsed =
    ProtocolFields.fromRequest(j).first ?: error("期望合法但被拦: $j -> ${ProtocolFields.fromRequest(j).second}")
private fun bad(j: JSONObject): String? = ProtocolFields.fromRequest(j).second

fun main() {
    // ── 1. 默认（字段一个都不给）：必须与引入本特性之前逐字节同行为 ───────────
    val base = ok(JSONObject())
    ck("默认 maxTokens=null（缺省交给 SamplingParams 的 DEFAULT）", base.maxTokens == null)
    ck("默认 logitBias=null（不挂偏置采样器）", base.logitBias == null)
    ck("默认 logprobs=false", !base.logprobs)
    ck("默认 topLogprobs=null", base.topLogprobs == null)
    ck("默认没有 notice（不该产生任何日志）", base.notices.isEmpty())
    ck("默认 describe() 为空串（调用方据此不打日志）", ProtocolFields.describe(base).isEmpty())
    ck("默认 biasIds 为 null（native 侧据此跳过挂载）", ProtocolFields.biasIds(base) == null)
    ck("默认 biasValues 为 null", ProtocolFields.biasValues(base) == null)

    // ── 2. max_completion_tokens：与 max_tokens 是同一个量 ──────────────────
    ck("max_completion_tokens=100 被解析出来", ok(JSONObject().put("max_completion_tokens", 100)).maxTokens == 100)
    ck("max_tokens=100 仍照旧", ok(JSONObject().put("max_tokens", 100)).maxTokens == 100)
    ck("两者同值 100 可接受", ok(JSONObject().put("max_tokens", 100).put("max_completion_tokens", 100)).maxTokens == 100)
    ck("两者不同值必须报错（不做静默二选一）",
        bad(JSONObject().put("max_tokens", 100).put("max_completion_tokens", 200)) == ProtocolFields.errMaxTokensConflict)
    // 上限与 max_tokens 共用同一份（SamplingParams.MAX_TOKENS_CAP），不是各写一份
    ck("别名为 0 被拦（与 max_tokens 同一句 400）",
        bad(JSONObject().put("max_completion_tokens", 0)) == ProtocolFields.errMaxCompletionTokens)
    ck("别名为 -1 被拦", bad(JSONObject().put("max_completion_tokens", -1)) == ProtocolFields.errMaxCompletionTokens)
    ck("别名超过 8192 被拦",
        bad(JSONObject().put("max_completion_tokens", SamplingParams.MAX_TOKENS_CAP + 1)) == ProtocolFields.errMaxCompletionTokens)
    ck("别名恰为 8192 合法",
        ok(JSONObject().put("max_completion_tokens", SamplingParams.MAX_TOKENS_CAP)).maxTokens == SamplingParams.MAX_TOKENS_CAP)
    ck("别名 1 合法（下界可取）", ok(JSONObject().put("max_completion_tokens", 1)).maxTokens == 1)
    // 关键：非数值不能静默回落成默认值（那正是本特性要修的那种失效）
    ck("别名 \"abc\" 被拦（不静默回落默认值）",
        bad(JSONObject().put("max_completion_tokens", "abc")) == ProtocolFields.errMaxCompletionTokens)
    ck("别名 12.5 被拦（非整数）",
        bad(JSONObject().put("max_completion_tokens", 12.5)) == ProtocolFields.errMaxCompletionTokens)
    ck("别名 true 被拦",
        bad(JSONObject().put("max_completion_tokens", true)) == ProtocolFields.errMaxCompletionTokens)
    ck("别名 \"500\" 字符串数值可接受（UI 参数框会传字符串）",
        ok(JSONObject().put("max_completion_tokens", "500")).maxTokens == 500)
    ck("别名 null 视为没给（与 SamplingParams 的 null=Absent 判据一致）",
        ok(JSONObject().put("max_completion_tokens", JSONObject.NULL)).maxTokens == null)

    // ── 3. logit_bias：格式判据 ─────────────────────────────────────────────
    val b1 = ok(JSONObject().put("logit_bias", JSONObject().put("12", 5.0).put("34", -3.0)))
    ck("logit_bias 两条可解析", b1.logitBias?.size == 2)
    ck("bias 的 id 落对", b1.logitBias?.map { it.tokenId }?.toSet() == setOf(12, 34))
    ck("bias 的值落对", b1.logitBias?.first { it.tokenId == 34 }?.bias == -3.0f)
    ck("biasIds 与 biasValues 长度相等（平行数组的前提）",
        ProtocolFields.biasIds(b1)?.size == ProtocolFields.biasValues(b1)?.size)
    ck("平行数组顺序一一对应",
        ProtocolFields.biasIds(b1)?.toList() == b1.logitBias?.map { it.tokenId } &&
            ProtocolFields.biasValues(b1)?.toList() == b1.logitBias?.map { it.bias })

    ck("logit_bias 不是对象被拦", bad(JSONObject().put("logit_bias", "12:5")) == ProtocolFields.errLogitBiasType)
    ck("logit_bias 是数组被拦", bad(JSONObject().put("logit_bias", org.json.JSONArray().put(1))) == ProtocolFields.errLogitBiasType)
    // key 必须是**纯十进制整数**：前导 +、小数、空白、字母都要拦
    ck("key 为 \"1.5\" 被拦", bad(JSONObject().put("logit_bias", JSONObject().put("1.5", 1.0))) == ProtocolFields.errLogitBiasType)
    ck("key 为 \"abc\" 被拦", bad(JSONObject().put("logit_bias", JSONObject().put("abc", 1.0))) == ProtocolFields.errLogitBiasType)
    ck("key 为 \" 12\"（带空格）被拦", bad(JSONObject().put("logit_bias", JSONObject().put(" 12", 1.0))) == ProtocolFields.errLogitBiasType)
    ck("key 为 \"+12\" 被拦（toIntOrNull 会收，这里必须显式不收）",
        bad(JSONObject().put("logit_bias", JSONObject().put("+12", 1.0))) == ProtocolFields.errLogitBiasType)
    ck("key 为 \"-12\" 被拦（token id 无负数）",
        bad(JSONObject().put("logit_bias", JSONObject().put("-12", 1.0))) == ProtocolFields.errLogitBiasType)
    // 取值区间与类型
    ck("bias=100 合法（闭区间上界）", ok(JSONObject().put("logit_bias", JSONObject().put("1", 100.0))).logitBias?.size == 1)
    ck("bias=-100 合法（闭区间下界）", ok(JSONObject().put("logit_bias", JSONObject().put("1", -100.0))).logitBias?.size == 1)
    ck("bias=101 被拦", bad(JSONObject().put("logit_bias", JSONObject().put("1", 101.0))) == ProtocolFields.errLogitBiasValue)
    ck("bias=-101 被拦", bad(JSONObject().put("logit_bias", JSONObject().put("1", -101.0))) == ProtocolFields.errLogitBiasValue)
    ck("bias 为字符串被拦（不做 \"0.5\" 的隐式转换）",
        bad(JSONObject().put("logit_bias", JSONObject().put("1", "0.5"))) == ProtocolFields.errLogitBiasValue)
    ck("bias 为对象被拦", bad(JSONObject().put("logit_bias", JSONObject().put("1", JSONObject()))) == ProtocolFields.errLogitBiasValue)
    ck("bias 为 bool 被拦", bad(JSONObject().put("logit_bias", JSONObject().put("1", true))) == ProtocolFields.errLogitBiasValue)
    ck("bias 为 null 被拦（不静默当成 0）",
        bad(JSONObject().put("logit_bias", JSONObject().put("1", JSONObject.NULL))) == ProtocolFields.errLogitBiasValue)
    ck("空对象 = 没有偏置（不是错误，也不挂采样器）",
        ok(JSONObject().put("logit_bias", JSONObject())).logitBias == null)
    ck("logit_bias=null 视为没给", ok(JSONObject().put("logit_bias", JSONObject.NULL)).logitBias == null)

    // 条数上限：**截断 + 留痕**，不是 400（性能边界，不是合法性边界）
    val many = JSONObject()
    for (i in 0 until ProtocolFields.MAX_LOGIT_BIAS + 5) many.put("$i", 1.0)
    val m = ok(JSONObject().put("logit_bias", many))
    ck("超上限只截断到 MAX_LOGIT_BIAS 条", m.logitBias?.size == ProtocolFields.MAX_LOGIT_BIAS)
    ck("超上限必须留痕（否则症状与「模型不听话」长得一样）",
        m.notices.any { it.contains("logit_bias") && it.contains("超过内部上限") })

    // 保留的是**哪几条**：这是 B-1 的本体，也是本节最要紧的一条。
    // `logit_bias` 是 JSON **对象**，`org.json` 内部是 `HashMap` —— `keys()` 是
    // String 的哈希序。不排序时 `subList(0, N)` 取到的是"哈希序前 N 个"，
    // 而日志说"前 N 条"：实测插 302 条时丢的是 id 109/229，留下的却是 300/301。
    // 所以这里**真跑 `fromRequest`**（不是 grep 文案），断言"保留的恰好是
    // 按 token id 升序的前 N 个" —— 这条断言在删掉 `sortedBy` 时会立刻变红。
    // 参数刻意**乱序**插入：顺序插入时哈希序与插入序碰巧一致的区间会假绿。
    val shuffled = JSONObject()
    val total = ProtocolFields.MAX_LOGIT_BIAS + 40
    for (i in 0 until total) shuffled.put("${(i * 7919) % total}", 1.0)
    val m2 = ok(JSONObject().put("logit_bias", shuffled))
    val keptIds = m2.logitBias?.map { it.tokenId } ?: emptyList()
    ck("超上限：保留的是**升序前 N 个** id（不是哈希序的任意子集）",
        keptIds == (0 until ProtocolFields.MAX_LOGIT_BIAS).toList())
    // 反例方向：哈希序下这一条会怎样，写进名字里 —— 断言的**形状**本身要能区分
    // "排序前 N" 与 "子集大小恰好 N"。只判 size 是拦不住 B-1 的。
    ck("超上限：保留集是**连续**的 0..N-1（散点子集会在此露馅）",
        keptIds.sorted().withIndex().all { (i, v) -> i == v })

    // 升序截断的边界：恰好 N 条时一条都不许丢（截断只在**超过**时才发生）
    val exact = JSONObject()
    for (i in 0 until ProtocolFields.MAX_LOGIT_BIAS) exact.put("${(i * 7919) % ProtocolFields.MAX_LOGIT_BIAS}", 1.0)
    val me = ok(JSONObject().put("logit_bias", exact))
    ck("恰好 MAX_LOGIT_BIAS 条：不截断、也不留痕",
        me.logitBias?.map { it.tokenId } == (0 until ProtocolFields.MAX_LOGIT_BIAS).toList()
            && me.notices.none { it.contains("超过内部上限") })

    // ── 4. logprobs / top_logprobs：绑定关系 ────────────────────────────────
    ck("logprobs=true 可接受", ok(JSONObject().put("logprobs", true)).logprobs)
    ck("logprobs=false 合法（= 不要）", !ok(JSONObject().put("logprobs", false)).logprobs)
    ck("logprobs=true 必须留痕（字段收下了但不出数据，得让「为什么没有」查得到）",
        ok(JSONObject().put("logprobs", true)).notices.any { it.contains("logprobs") })
    ck("logprobs 非布尔被拦（不静默当 false）",
        bad(JSONObject().put("logprobs", "true")) == ProtocolFields.errLogprobsType)
    ck("logprobs 为数字被拦", bad(JSONObject().put("logprobs", 1)) == ProtocolFields.errLogprobsType)
    ck("top_logprobs 需要 logprobs=true（OpenAI 同）",
        bad(JSONObject().put("top_logprobs", 5)) == ProtocolFields.errTopLogprobsNeedsLogprobs)
    ck("top_logprobs 配 logprobs=false 也被拦",
        bad(JSONObject().put("logprobs", false).put("top_logprobs", 5)) == ProtocolFields.errTopLogprobsNeedsLogprobs)
    ck("top_logprobs=5 配 logprobs=true 合法",
        ok(JSONObject().put("logprobs", true).put("top_logprobs", 5)).topLogprobs == 5)
    ck("top_logprobs=0 合法（下界闭）",
        ok(JSONObject().put("logprobs", true).put("top_logprobs", 0)).topLogprobs == 0)
    ck("top_logprobs=20 合法（上界闭）",
        ok(JSONObject().put("logprobs", true).put("top_logprobs", 20)).topLogprobs == 20)
    ck("top_logprobs=21 被拦",
        bad(JSONObject().put("logprobs", true).put("top_logprobs", 21)) == ProtocolFields.errTopLogprobsType)
    ck("top_logprobs=-1 被拦",
        bad(JSONObject().put("logprobs", true).put("top_logprobs", -1)) == ProtocolFields.errTopLogprobsType)
    ck("top_logprobs=2.5 被拦（非整数）",
        bad(JSONObject().put("logprobs", true).put("top_logprobs", 2.5)) == ProtocolFields.errTopLogprobsType)
    ck("logprobs=true 时 topLogprobs 缺省为 null（不是 0）",
        ok(JSONObject().put("logprobs", true)).topLogprobs == null)

    // ── 4.5 n：只认 1，其余明确 400（不静默给一条）─────────────────────────
    // 这一格的失效形态最容易被漏掉：`n: 2` 时服务端只回 1 条而 HTTP 照 200，
    // 客户端拿到的是"结构完全合法、只是少了一条"的响应 —— 很多 SDK 根本不看
    // `choices.length`，于是调用方以为自己在做 2 选 1。
    ck("n 缺省时按 1 记（与引入本特性之前逐字节同行为）", ok(JSONObject()).n == 1)
    ck("n=1 显式给出可接受", ok(JSONObject().put("n", 1)).n == 1)
    ck("n=1 与 n 缺省的 Parsed 全等（除 n 无差别）",
        ok(JSONObject().put("n", 1)).n == ok(JSONObject()).n)
    ck("n=2 明确 400（不静默给一条）",
        bad(JSONObject().put("n", 2)) == ProtocolFields.errNUnsupported)
    ck("n=10 同样 400", bad(JSONObject().put("n", 10)) == ProtocolFields.errNUnsupported)
    // "写错了"与"不支持"是两件事，文案必须分开
    ck("n=0 报的是**类型/取值**错误、不是「不支持」",
        bad(JSONObject().put("n", 0)) == ProtocolFields.errNType)
    ck("n=-1 报取值错误", bad(JSONObject().put("n", -1)) == ProtocolFields.errNType)
    ck("n=1.5 被拦（非整数）", bad(JSONObject().put("n", 1.5)) == ProtocolFields.errNType)
    ck("n=\"abc\" 被拦（不静默回落成 1）", bad(JSONObject().put("n", "abc")) == ProtocolFields.errNType)
    ck("n=true 被拦", bad(JSONObject().put("n", true)) == ProtocolFields.errNType)
    // MALFORMED 哨兵恰好大于 N_SUPPORTED，判序错了 n:\"abc\" 会落进「不支持」而不是「写错了」
    ck("n 的哨兵判在「不支持」之前（否则 n=\"abc\" 会被误报成不支持）",
        bad(JSONObject().put("n", "abc")) != ProtocolFields.errNUnsupported)
    ck("n 为 null 视为没给（= 1）", ok(JSONObject().put("n", JSONObject.NULL)).n == 1)
    ck("n=\"1\" 字符串数值可接受", ok(JSONObject().put("n", "1")).n == 1)
    ck("n=1 不产生 notice（合法取值不该留痕）", ok(JSONObject().put("n", 1)).notices.isEmpty())

    // ── 5. 三个字段可以一起给（组合路径）────────────────────────────────────
    val all = ok(JSONObject()
        .put("max_completion_tokens", 64)
        .put("logit_bias", JSONObject().put("7", -2.0))
        .put("logprobs", true).put("top_logprobs", 3))
    ck("三字段同时给都能解析", all.maxTokens == 64 && all.logitBias?.size == 1 && all.logprobs && all.topLogprobs == 3)
    ck("describe() 覆盖三个字段",
        ProtocolFields.describe(all).contains("max_completion=64") &&
            ProtocolFields.describe(all).contains("logit_bias=1") &&
            ProtocolFields.describe(all).contains("logprobs=true"))

    // ── 6. 与 SamplingParams 的联动：别名确实改到生成长度 ────────────────────
    val sp = SamplingParams.fromRequest(
        JSONObject().put("max_completion_tokens", 77), aliasMaxTokens = ok(JSONObject().put("max_completion_tokens", 77)).maxTokens).first!!
    ck("别名传进 SamplingParams 后 maxTokens=77（而不是默认 512）", sp.maxTokens == 77)
    val spDef = SamplingParams.fromRequest(JSONObject(), aliasMaxTokens = null).first!!
    ck("不给别名时仍走默认 512（逐字节同旧行为）", spDef.maxTokens == SamplingParams.DEF_MAX_TOKENS)
    // 别名越界时 SamplingParams 也必须拦（两个名字共用同一条 maxTokensOk）
    val spBad = SamplingParams.fromRequest(JSONObject().put("max_completion_tokens", 0), aliasMaxTokens = 0).first
    ck("别名取值越界时 SamplingParams 也拦（共用同一份判据）", spBad == null)

    println()
    println(if (fail == 0) "=== ProtocolFieldsTest 全部通过 ===" else "=== ProtocolFieldsTest 失败 $fail 项 ===")
    if (fail != 0) kotlin.system.exitProcess(1)
}
