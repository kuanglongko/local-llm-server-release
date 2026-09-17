package com.xiaowan.localinference

// SamplingParams 的离线单测：默认值、字段校验、UI 空输入框回落、seed 规整。
//
// 只依赖 org.json（运行期用真实现，见 tools/run_sampling_tests.sh），不需要 Android 设备。
// 这些断言对应的是几条**静默错**的历史缺陷：
//   - HTTP 侧 min_p 默认 0.05 与 UI 侧 0 不一致
//   - 单独设 presence_penalty 时参数被整个丢掉
//   - top_p=0 / repeat_penalty<1 / max_tokens 越界被静默降级
//   - 同一毫秒内的请求拿到同一个 seed
import org.json.JSONObject

private var fail = 0
fun ck(name: String, cond: Boolean) {
    if (cond) println("PASS  $name") else { println("FAIL  $name"); fail++ }
}

fun main() {
    // ---- 1. 默认值：HTTP 与 UI 必须完全一致，min_p 一律为 0 ----
    val d = SamplingParams.fromRequest(JSONObject()).first!!
    ck("默认 temp=0.8", d.temp == 0.8f)
    ck("默认 top_p=0.95", d.topP == 0.95f)
    ck("默认 min_p=0（不再是 0.05）", d.minP == 0f)
    ck("默认 top_k=0", d.topK == 0)
    ck("默认 repeat_penalty=1", d.repeatPenalty == 1f)
    ck("默认 repeat_last_n=64", d.repeatLastN == 64)
    ck("默认 freq/pres=0", d.freqPenalty == 0f && d.presencePenalty == 0f)
    ck("默认 max_tokens=512", d.maxTokens == 512)
    ck("默认参数本身合法", d.check() == null)

    // ---- 2. 门禁：单独设 presence_penalty 必须能构成有效配置 ----
    val p = SamplingParams.fromRequest(JSONObject().put("presence_penalty", 0.5)).first!!
    ck("presence_penalty=0.5 可解析", p.presencePenalty == 0.5f)
    ck("presence_penalty=0.5 通过校验", p.check() == null)
    // native 门禁条件（等价重写）：penaltyN>0 && (rep!=1 || freq!=0 || pres!=0)
    fun gate(s: SamplingParams) = s.repeatLastN > 0 &&
        (s.repeatPenalty != 1f || s.freqPenalty != 0f || s.presencePenalty != 0f)
    ck("单独 presence_penalty 能过 native 门禁（旧代码为 false）", gate(p))
    ck("全默认不过门禁（不该多挂一个 noop 采样器）", !gate(d))
    ck("只用 repeat=1.1 能过门禁", gate(SamplingParams.fromRequest(JSONObject().put("repeat_penalty", 1.1)).first!!))
    ck("只用 freq=0.2 能过门禁", gate(SamplingParams.fromRequest(JSONObject().put("frequency_penalty", 0.2)).first!!))
    val n0 = SamplingParams.fromRequest(JSONObject().put("repeat_last_n", 0).put("repeat_penalty", 1.5)).first!!
    ck("repeat_last_n=0 时门禁关闭（惩罚窗口为 0 本就是 noop）", !gate(n0))
    ck("repeat_last_n=0 本身合法", n0.check() == null)

    // ---- 3. 非法值必须被拦，不能静默降级 ----
    fun bad(j: JSONObject) = SamplingParams.fromRequest(j).first == null
    ck("top_p=0 被拦（旧行为：静默跳过）", bad(JSONObject().put("top_p", 0)))
    ck("top_p=-1 被拦", bad(JSONObject().put("top_p", -1)))
    ck("top_p=1.5 被拦", bad(JSONObject().put("top_p", 1.5)))
    ck("top_p=1.0 合法（=关闭）", !bad(JSONObject().put("top_p", 1.0)))
    ck("temperature 缺省非法值被拦", bad(JSONObject().put("temperature", "\"abc\"")))
    ck("temperature 过大被拦", bad(JSONObject().put("temperature", 999)))
    ck("temperature=0 合法（贪心）", !bad(JSONObject().put("temperature", 0)))
    ck("temperature=-1 合法（贪心）", !bad(JSONObject().put("temperature", -1)))
    ck("repeat_penalty=0.9 被拦（<1 会放大高频 token）", bad(JSONObject().put("repeat_penalty", 0.9)))
    ck("repeat_penalty=0 被拦", bad(JSONObject().put("repeat_penalty", 0)))
    ck("repeat_penalty=1.1 合法", !bad(JSONObject().put("repeat_penalty", 1.1)))
    ck("min_p=0.05 合法", !bad(JSONObject().put("min_p", 0.05)))
    ck("min_p=1.0 被拦（会砍光候选）", bad(JSONObject().put("min_p", 1.0)))
    ck("min_p=-0.1 被拦", bad(JSONObject().put("min_p", -0.1)))
    ck("top_k=-1 被拦", bad(JSONObject().put("top_k", -1)))
    ck("top_k=0 合法", !bad(JSONObject().put("top_k", 0)))
    ck("top_k=1.5 被拦（非整数）", bad(JSONObject().put("top_k", 1.5)))
    ck("presence_penalty=5 被拦（超出 OpenAI 的 [-2,2]）", bad(JSONObject().put("presence_penalty", 5)))
    ck("frequency_penalty=-2 合法", !bad(JSONObject().put("frequency_penalty", -2)))
    ck("max_tokens=0 被拦（旧行为：静默钳到 1）", bad(JSONObject().put("max_tokens", 0)))
    ck("max_tokens=99999 被拦（旧行为：静默钳到 8192）", bad(JSONObject().put("max_tokens", 99999)))
    ck("max_tokens=8192 合法", !bad(JSONObject().put("max_tokens", 8192)))
    ck("repeat_last_n=-1 被拦", bad(JSONObject().put("repeat_last_n", -1)))

    // ---- 4. seed 规整：非零、避开 LLAMA_DEFAULT_SEED、相邻纳秒分散 ----
    fun low32(v: Long) = v and 0xFFFFFFFFL
    ck("seed 低32位永不为 0", low32(SamplingParams.normalizeSeed(0)) != 0L)
    ck("seed 低32位永不为 0xFFFFFFFF",
        low32(SamplingParams.normalizeSeed(0xFFFFFFFFL)) != 0xFFFFFFFFL)
    ck("显式 seed=0 仍拿到非零种子", low32(SamplingParams.normalizeSeed(0)) != 0L)
    ck("显式 seed=12345 稳定可复现",
        SamplingParams.normalizeSeed(12345) == SamplingParams.normalizeSeed(12345))
    val explicit = SamplingParams.fromRequest(JSONObject().put("seed", 42)).first!!
    ck("显式 seed 被规整且非零", low32(explicit.seed) != 0L)
    ck("显式 seed 两次解析结果一致（可复现调试）",
        explicit.seed == SamplingParams.fromRequest(JSONObject().put("seed", 42)).first!!.seed)
    // 相邻纳秒必须分散：这是原来 replace(...) 相同毫秒撞种子的根因
    var diff = 0
    var prev = -1L
    for (ns in 1_000_000L until 1_000_100L) {
        val s = SamplingParams.normalizeSeed(ns)
        if (s != prev) diff++
        prev = s
    }
    ck("连续 100 个纳秒级时间戳规整后仍互不相同（无静止不动点）", diff == 100)
    // 极端：找到 xorshift32 的不动点 0，确认被替换掉
    ck("0 这个不动点被替换", SamplingParams.normalizeSeed(0) != 0L)
    ck("默认（不传 seed）也非零", low32(SamplingParams.fromRequest(JSONObject()).first!!.seed) != 0L)

    // ---- 5. describe 不泄露 seed，但含全部调参 ----
    val desc = p.describe()
    ck("describe 含各项参数", desc.contains("min_p=0.0") && desc.contains("top_p=0.95"))
    ck("describe 不含 seed", !desc.contains("seed"))


    // ---- 6. UI 侧空输入框必须回落默认值，而不是报「参数非法」 ----
    val blank = SamplingParams.fromRequest(JSONObject().apply {
        put("temperature", ""); put("top_p", "  "); put("top_k", "")
        put("repeat_penalty", ""); put("max_tokens", ""); put("presence_penalty", "")
    }).first
    ck("全空输入框可解析（回落默认）", blank != null)
    ck("空输入框 temp 回落 0.8", blank!!.temp == 0.8f)
    ck("空输入框 top_p 回落 0.95", blank.topP == 0.95f)
    ck("空输入框 min_p 回落 0", blank.minP == 0f)
    ck("空输入框 max_tokens 回落 512", blank.maxTokens == 512)
    ck("空输入框全部合法", blank.check() == null)

    // 非空但解析不了 = 真非法（此前被 ?: 默认值静默吞掉）
    ck("temp='1.5.0' 报错（不再静默按 0.8 跑）", bad(JSONObject().put("temperature", "1.5.0")))
    ck("top_k='abc' 报错", bad(JSONObject().put("top_k", "abc")))
    ck("seed='abc' 报错（不再默默随机）", bad(JSONObject().put("seed", "abc")))
    ck("seed='' 走随机不报错", !bad(JSONObject().put("seed", "")))
    ck("seed=null 走随机不报错", !bad(JSONObject().put("seed", JSONObject.NULL)))

    // 字符串形式的合法数字仍要认（UI 全走字符串）
    val strs = SamplingParams.fromRequest(JSONObject().apply {
        put("temperature", "0.7"); put("top_p", "0.9"); put("min_p", "0.02")
        put("top_k", "40"); put("repeat_penalty", "1.1"); put("repeat_last_n", "128")
    }).first!!
    ck("字符串数值可解析", strs.temp == 0.7f && strs.topP == 0.9f && strs.minP == 0.02f)
    ck("字符串 top_k/repeat_last_n 可解析", strs.topK == 40 && strs.repeatLastN == 128)

    // ---- 7. fromRequest 通过 => check() 必须也通过（两条校验规则不能漂移） ----
    val samples = listOf(
        JSONObject(),
        JSONObject().put("temperature", 0.7).put("top_p", 0.9),
        JSONObject().put("presence_penalty", 0.5),
        JSONObject().put("repeat_penalty", 1.2).put("repeat_last_n", 32),
        JSONObject().put("min_p", 0.03).put("top_k", 20),
        JSONObject().put("temperature", 0),
    )
    var drift = 0
    for (jj in samples) {
        val sp = SamplingParams.fromRequest(jj).first
        if (sp == null || sp.check() != null) drift++
    }
    ck("fromRequest 接受的样例都能过 check（两条规则一致）", drift == 0)

    println()

    println()
    println(if (fail == 0) "=== SamplingParamsTest 全部通过 ===" else "=== SamplingParamsTest 失败 $fail 项 ===")
    if (fail != 0) kotlin.system.exitProcess(1)
}
