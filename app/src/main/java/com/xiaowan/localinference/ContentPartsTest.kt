package com.xiaowan.localinference

// ContentParts 的离线单测：/v1/chat/completions 的 content 数组（多模态分段）。
//
// 每个断言对应一种**静默失效**：客户端以为模型看过图了，模型只看到一句没有图的话，
// 两边都不报错 —— 用户从回答上分不出来，只有他自己知道"我明明发了图"。
import org.json.JSONArray
import org.json.JSONObject

private var fail = 0
private fun ck(name: String, cond: Boolean) {
    if (cond) println("PASS  $name") else { println("FAIL  $name"); fail++ }
}

private fun arr(vararg parts: JSONObject): JSONArray {
    val a = JSONArray()
    for (p in parts) a.put(p)
    return a
}

private fun text(s: String) = JSONObject().put("type", "text").put("text", s)

fun main() {
    // ── 1. 全 text：支持的形态一个字都不改（原行为，逐字节不变）────────────────
    ck("单段 text", ContentParts.unsupported(arr(text("你好"))) == null)
    ck("多段 text 按原序拼", ContentParts.textOf(arr(text("你"), text("好"))) == "你好")
    ck("空数组：没有不支持的段", ContentParts.unsupported(JSONArray()) == null)
    ck("空 text 段仍按现状给空串", ContentParts.textOf(arr(text(""))) == "")

    // ── 2. 非 text 段：必须报出来（这是能力边界，不是裁剪）─────────────────────
    ck("image_url 被拦下且报出类型名",
        ContentParts.unsupported(arr(text("看图"), JSONObject().put("type", "image_url"))) == "image_url")
    ck("input_audio 被拦下",
        ContentParts.unsupported(arr(JSONObject().put("type", "input_audio"))) == "input_audio")
    ck("file 被拦下（未知类型也走同一条路）",
        ContentParts.unsupported(arr(JSONObject().put("type", "file"))) == "file")
    ck("缺 type 的段按不支持算（不替调用方猜它是不是文本）",
        ContentParts.unsupported(arr(JSONObject().put("text", "x"))) == "(缺少 type)")

    // ── 3. 400 的文案：说清"能力边界"与"没被悄悄丢掉"────────────────────────
    val msg = ContentParts.rejectMessage("image_url")
    ck("文案点名类型", msg.contains("image_url"))
    ck("文案说明是能力边界（不是参数非法）", msg.contains("没有视觉/音频通路"))
    ck("文案承诺没静默丢", msg.contains("悄悄丢掉"))

    // ── 4. textOf 只取 text 段（拦下之后剩下的那段仍要按原序拼）──────────────
    ck("textOf 跳过非 text 段", ContentParts.textOf(arr(text("a"), JSONObject().put("type", "x"), text("b"))) == "ab")

    println()
    if (fail > 0) { println("=== ContentParts 单测：FAIL $fail ==="); kotlin.system.exitProcess(1) }
    println("=== ContentParts 单测：全部通过（共 14 条）===")
}
