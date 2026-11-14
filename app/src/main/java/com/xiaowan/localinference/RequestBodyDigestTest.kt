package com.xiaowan.localinference

// RequestBodyDigest 的离线单测：请求正文摘要（ISSUE #452）。
//
// 每个断言对应一种**排查时的失效**：摘要不随内容变（那就白记了）、
// 或者顺手把正文写进了日志（那就把排查工具做成了泄露工具）。
import org.json.JSONArray
import org.json.JSONObject

private var fail = 0
private fun ck(name: String, cond: Boolean) {
    if (cond) println("PASS  $name") else { println("FAIL  $name"); fail++ }
}

private fun msg(role: String, content: String) =
    JSONObject().put("role", role).put("content", content)

fun main() {
    // ── 1. 指纹：同内容同指纹，改一个字就不同 ─────────────────────────────────
    val a = RequestBodyDigest.describe("chat", listOf("user" to "你好"))
    val b = RequestBodyDigest.describe("chat", listOf("user" to "你好"))
    val c = RequestBodyDigest.describe("chat", listOf("user" to "你好！"))
    ck("同一份对话 -> 同一枚 head", a == b)
    ck("改一个字 -> head 变", a != c)

    // ── 2. head 覆盖「顺序+逐条内容」，不是只看条数 ──────────────────────────
    val d1 = RequestBodyDigest.describe("chat", listOf("system" to "s", "user" to "u"))
    val d2 = RequestBodyDigest.describe("chat", listOf("user" to "s", "system" to "u"))
    ck("两条交换角色 -> head 不同", d1 != d2)
    val d3 = RequestBodyDigest.describe("chat", listOf("user" to "su"))
    ck("拼起来相同但分条不同 -> head 不同", d1 != d3)

    // ── 3. content 数组按 text 段拼（与解析侧同一口径）───────────────────────
    val arr = JSONArray()
    arr.put(JSONObject().put("type", "text").put("text", "你"))
    arr.put(JSONObject().put("type", "text").put("text", "好"))
    val nested = JSONArray().put(JSONObject().put("role", "user").put("content", arr))
    ck("content 数组 -> 按 text 段拼成一条",
        RequestBodyDigest.pairsOf(nested) == listOf("user" to "你好"))
    ck("role 缺省按 user",
        RequestBodyDigest.pairsOf(JSONArray().put(JSONObject().put("content", "x"))) == listOf("user" to "x"))
    ck("null -> 空", RequestBodyDigest.pairsOf(null).isEmpty())

    // ── 4. 默认不落正文（这是「加了它也不用回退」的前提）───────────────────────
    RequestBodyDigest.debugLogBodies = false
    val noBody = RequestBodyDigest.describe("chat", listOf("user" to "机密内容ABC"))
    ck("默认不打正文", !noBody.contains("机密内容ABC"))
    ck("但长度与指纹都在", noBody.contains("len=") && noBody.contains("sha="))

    // ── 5. 打开时才打片段，且截断、单行 ──────────────────────────────────────
    RequestBodyDigest.debugLogBodies = true
    val long = "第一行\n" + "x".repeat(RequestBodyDigest.EXCERPT + 50)
    val withBody = RequestBodyDigest.describe("chat", listOf("user" to long))
    ck("打开后有正文片段", withBody.contains("第一行"))
    ck("片段截断", !withBody.contains("x".repeat(RequestBodyDigest.EXCERPT + 1)))
    ck("换行不破行（一条消息只占一行）",
        withBody.lines().size == 2)
    RequestBodyDigest.debugLogBodies = false

    // ── 6. 总开关关掉：一个字都不打（不发 400、不改请求，只静默）──────────────
    RequestBodyDigest.enabled = false
    ck("开关关掉 -> 空串", RequestBodyDigest.describe("chat", listOf("user" to "x")).isEmpty())
    RequestBodyDigest.enabled = true
    ck("开关打开 -> 有内容", RequestBodyDigest.describe("chat", listOf("user" to "x")).isNotEmpty())

    // ── 7. 两条端点各自带 kind，日志里能分开数 ───────────────────────────────
    ck("chat/cmpl 前缀分开",
        RequestBodyDigest.describe("cmpl", RequestBodyDigest.pairsOfPrompt("p")).startsWith("cmpl msgs=1"))
    ck("裸 prompt 当一条 user",
        RequestBodyDigest.pairsOfPrompt("p") == listOf("user" to "p"))

    println(if (fail == 0) "ALL PASS" else "FAILED $fail")
    if (fail != 0) kotlin.system.exitProcess(1)
}
