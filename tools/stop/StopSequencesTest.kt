package com.xiaowan.localinference

// StopSequences 的离线单测：请求侧解析/校验 + 非流式兜底截断。
//
// 为什么单独钉：`stop` 是本仓库此前**完全没有实现**的协议字段，
// 传进来会被静默忽略（表现为"模型停不下来"）。补齐之后最容易再犯的两类错是：
//   1. 空串被当成合法 —— `indexOf("")` 返回 0，输出恒为空，看起来像模型坏了；
//   2. 截断按数组顺序而不是"最靠前命中" —— `stop: ["很好","好"]` 在不同请求下
//      结果不一致，且与 OpenAI 语义不符。
// 这两条都不抛异常、不打日志，只能靠断言钉住。
//
// 还有一条来自**真实事故**、必须永久钉住：曾把条数上限设成 8 并在超出时返回 400，
// 于是「stop 与 stop_sequences 各塞一批、并集 9 条」这种完全正常的客户端请求**每个
// 都失败**，客户端表现为完全无法生成 —— 而这是"补齐 stop 支持"带来的反向结果。
// 现在条数只截断（[StopSequences.capped]）、不失败请求，相关断言见"6. 条数"一节。
//
// 另有一处**跨语言**的一致性必须在这里固定下来：native 侧 local-stop 采样器命中时
// 「stop 串本身不出现在输出里」，本文件 truncate() 必须同款。两边不一致时
// 流式与非流式会对同一请求返回不同文本。
import org.json.JSONArray
import org.json.JSONObject

private var fail = 0
fun ck(name: String, cond: Boolean) {
    if (cond) println("PASS  $name") else { println("FAIL  $name"); fail++ }
}

private fun parse(j: JSONObject) = StopSequences.fromRequest(j)

fun main() {
    // ---- 1. 「没给」不是错误，返回空列表 ----
    val none = parse(JSONObject())
    ck("缺 stop -> 空列表且无错", none.first == emptyList<String>() && none.second == null)
    ck("stop=null -> 空列表", parse(JSONObject().put("stop", JSONObject.NULL)).first == emptyList<String>())

    // ---- 2. 单串形式（OpenAI 允许）与数组形式 ----
    val one = parse(JSONObject().put("stop", "\n"))
    ck("stop 单串被解析", one.first == listOf("\n") && one.second == null)
    val arr = parse(JSONObject().put("stop", JSONArray().put("</end>").put("STOP")))
    ck("stop 数组被解析", arr.first == listOf("</end>", "STOP"))

    // ---- 3. stop_sequences 别名（vLLM/Anthropic 风格） ----
    val alias = parse(JSONObject().put("stop_sequences", JSONArray().put("X")))
    ck("stop_sequences 别名可解析", alias.first == listOf("X"))
    // 两个字段都给了取并集，不能静默丢一半
    val both = parse(JSONObject().put("stop", "A").put("stop_sequences", JSONArray().put("B")))
    ck("stop + stop_sequences 取并集", both.first == listOf("A", "B"))

    // ---- 4. 空串必须判非法（最危险的一条） ----
    ck("空串 stop 被拦（旧行为会输出恒为空）", parse(JSONObject().put("stop", "")).first == null)
    ck("空串元素被拦", parse(JSONObject().put("stop", JSONArray().put(""))).first == null)
    ck("仅空串元素 + 合法元素 也被拦", parse(JSONObject().put("stop", JSONArray().put("").put("ok"))).first == null)

    // ---- 5. 类型错误必须报错，不能当成"没给" ----
    ck("stop=数字 被拦", parse(JSONObject().put("stop", 1)).first == null)
    ck("stop=对象 被拦", parse(JSONObject().put("stop", JSONObject().put("a", 1))).first == null)
    ck("stop 数组含非字符串 被拦", parse(JSONObject().put("stop", JSONArray().put(1))).first == null)

    // ---- 6. 条数：**不是**合法性边界，多几条绝不失败请求 ----
    // 回归事故：曾把上限写成 8 并在超出时 400。调用方（stop 与 stop_sequences
    // 各塞一批、并集 9 条）**每个请求**都被拒，客户端完全无法生成 —— 而这正是
    // 「补齐协议字段让客户端更好用」要避免的反向结果。所以这里钉死"9 条可通过"。
    run {
        val (v, e) = parse(JSONObject().put("stop",
            JSONArray().apply { for (i in 1..9) put("s$i") }))
        ck("9 个互不相同的串**合法**（不再 400，客户端事故的回归点）", v?.size == 9 && e == null)
    }
    ck("正好 8 条合法", parse(JSONObject().put("stop",
        JSONArray().apply { for (i in 1..8) put("s$i") })).first?.size == 8)
    run {
        // 真实触发形态：两个别名各塞一批、并集 9 条，必须照跑不能失败请求
        val a = JSONArray().apply { for (i in 1..5) put("a$i") }
        val b = JSONArray().apply { for (i in 1..5) put("b$i") }
        val (v, e) = parse(JSONObject().put("stop", a).put("stop_sequences", b))
        ck("stop 5 条 + stop_sequences 5 条 -> 并集 10 条，合法", v?.size == 10 && e == null)
    }
    run {
        // 远超上限也**不失败请求**：交给 capped() 截断，请求照常生成
        val (v, e) = parse(JSONObject().put("stop",
            JSONArray().apply { for (i in 1..(StopSequences.MAX_SEQUENCES + 20)) put("s$i") }))
        ck("远超内部上限仍合法（不 400）", v != null && e == null)
        ck("capped 截到内部上限", StopSequences.capped(v!!).size == StopSequences.MAX_SEQUENCES)
        ck("capped 保持原顺序（取前 N 条）", StopSequences.capped(v!!).first() == "s1")
        ck("capped 对未超额列表原样返回",
            StopSequences.capped(listOf("A", "B")) == listOf("A", "B"))
    }

    // ---- 6b. 去重：并集里的重复**不算**也不该重复匹配 ----
    // 真实触发场景：客户端为兼容 stop / stop_sequences 两个别名，
    // 把同一组串在两个字段里各写一遍。若不识别重复，同一串会被匹配两遍、
    // 也会让"条数"看起来翻倍（后者曾直接导致 400，见上）。
    run {
        val same = JSONArray().apply { for (i in 1..8) put("s$i") }
        val (v, e) = parse(JSONObject().put("stop", same).put("stop_sequences", same))
        ck("两个别名写同一组 8 条 -> 去重后仍是 8 条，合法", v?.size == 8 && e == null)
    }
    run {
        val arr = JSONArray().apply { for (i in 1..8) put("dup") }
        ck("同一条重复 8 次 -> 去重成 1 条，合法", parse(JSONObject().put("stop", arr)).first?.size == 1)
    }
    run {
        // 去重后正好 8 条（含重复）必须放行
        val arr = JSONArray().apply { for (i in 1..7) put("s$i"); put("s1") }
        ck("8 条里有重复 -> 去重成 7 条，合法", parse(JSONObject().put("stop", arr)).first?.size == 7)
    }
    ck("超长单条被拦", parse(JSONObject().put("stop", "x".repeat(StopSequences.MAX_LEN + 1))).first == null)
    ck("长度正好上限合法", parse(JSONObject().put("stop", "x".repeat(StopSequences.MAX_LEN))).first != null)

    // ---- 7. truncate：stop 串本身不出现在结果里（与 native 同款语义） ----
    ck("命中即截断，stop 不出现在结果里",
        StopSequences.truncate("你好</end>后面的垃圾", listOf("</end>")) == "你好")
    ck("未命中保持原文", StopSequences.truncate("你好", listOf("</end>")) == "你好")
    ck("多条同时可命中时取最靠前的（与数组顺序无关）",
        StopSequences.truncate("很好玩", listOf("很好", "好")) == "")
    ck("倒过来写数组顺序，结果不变",
        StopSequences.truncate("很好玩", listOf("好", "很好")) == "")
    ck("空 stop 列表不改动文本", StopSequences.truncate("abc", emptyList()) == "abc")
    ck("开头就命中 -> 空串", StopSequences.truncate("STOPxxxx", listOf("STOP")) == "")
    ck("空串在列表里被忽略（不把输出截成空）",
        StopSequences.truncate("abc", listOf("", "z")) == "abc")

    // ---- 8. describe 转义：stop 串里有换行不能把日志撑成多行 ----
    val d = StopSequences.describe(listOf("a\nb"))
    ck("describe 把换行转义", !d.contains("\n") && d.contains("\\n"))
    ck("空列表的 describe", StopSequences.describe(emptyList()) == "stop=[]")

    println()
    println(if (fail == 0) "=== StopSequencesTest 全部通过 ===" else "=== StopSequencesTest 失败 $fail 项 ===")
    if (fail != 0) kotlin.system.exitProcess(1)
}
