package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject

// 工具调用（tools / function calling）**线上形状**的离线单测。
//
// 覆盖三处「装机才会发现、且症状不自明」的坑：
//   1. 非流式响应把 native 的原生形状（只有 id/name/arguments）直接当 OpenAI 的
//      message.tool_calls 吐出去——少了 type/function 两层包装，客户端 SDK 会静默丢弃；
//   2. arguments 是**字符串内嵌 JSON**，直接拼进响应体会把外层 JSON 撑破；
//   3. tool_choice 写成对象（指定具体函数）时若两边归一规则不同，流式/非流式会渲染出不同 prompt。
//
// 解析语法本身（<tool_call> / [TOOL_CALLS]）在 native 侧由 common_chat_parse 按模板处理，
// 这里不测、也不该用正则去测——那正是本功能刻意回避的做法。
private var f3 = 0
fun c3(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); f3++ }
}

private fun calls(vararg triples: Triple<String, String, String>): JSONArray {
    val a = JSONArray()
    for ((id, name, args) in triples) {
        a.put(JSONObject().apply { put("id", id); put("name", name); put("arguments", args) })
    }
    return a
}

fun main() {
    // ---- 请求侧 ----
    c3("无 tools 字段 -> hasTools=false", !ToolCalls.hasTools(JSONObject("""{"messages":[]}""")))
    c3("tools 空数组 -> hasTools=false", !ToolCalls.hasTools(JSONObject("""{"tools":[]}""")))
    c3("tools 非空 -> hasTools=true",
        ToolCalls.hasTools(JSONObject("""{"tools":[{"type":"function"}]}""")))
    c3("无 tools -> toolsJson=null", ToolCalls.toolsJson(JSONObject("""{}""")) == null)
    c3("tools 空数组 -> toolsJson=null（不透传，避免渲染出带工具的空 prompt）",
        ToolCalls.toolsJson(JSONObject("""{"tools":[]}""")) == null)
    c3("tools 原文透传",
        ToolCalls.toolsJson(JSONObject("""{"tools":[{"type":"function","function":{"name":"f"}}]}"""))
            ?.contains("\"name\":\"f\"") == true)

    c3("tool_choice=auto 透传",
        ToolCalls.parseToolChoice(JSONObject("""{"tool_choice":"auto"}"""), true) == "auto")
    c3("tool_choice=required 透传",
        ToolCalls.parseToolChoice(JSONObject("""{"tool_choice":"required"}"""), true) == "required")
    // ---- 崩因回归：未传 tool_choice 时**绝不能返回 null** ----
    // 现场：agent 带 63 个 tools 的请求、未传 tool_choice -> 请求侧返回 null ->
    // JNI 只能变成空串 "" -> native 把空串喂给库 -> 库 throw std::invalid_argument
    // ("Invalid tool_choice: ") -> 异常穿 JNI 帧 -> SIGABRT，服务端进程消失。
    // 所以"缺省必须归一成显式 auto"是硬约束，下面四条把这个口子钉死。
    c3("tool_choice=空串 -> \"auto\"（不是 null：null 会在 native 变成空串并触发库 throw）",
        ToolCalls.parseToolChoice(JSONObject("""{"tool_choice":""}"""), true) == "auto")
    c3("tool_choice 为对象 -> \"auto\"（与 native 侧归一规则一致）",
        ToolCalls.parseToolChoice(JSONObject("""{"tool_choice":{"type":"function","function":{"name":"f"}}}"""), true) == "auto")
    c3("tool_choice=null -> \"auto\"", ToolCalls.parseToolChoice(JSONObject("""{"tool_choice":null}"""), true) == "auto")
    c3("完全缺 tool_choice 字段 -> \"auto\"（agent 场景的实际形态）",
        ToolCalls.parseToolChoice(JSONObject("""{"tools":[{"type":"function"}]}"""), true) == "auto")
    c3("无 tools 时忽略 tool_choice",
        ToolCalls.parseToolChoice(JSONObject("""{"tool_choice":"required"}"""), false) == null)

    // ---- 响应侧：单个调用 ----
    val one = calls(Triple("call_0", "get_weather", """{"city":"北京"}"""))
    val cj = JSONObject(ToolCalls.callJson(0, one.getJSONObject(0)))
    c3("callJson 带 index", cj.getInt("index") == 0)
    c3("callJson 带 id", cj.getString("id") == "call_0")
    c3("callJson type=function", cj.getString("type") == "function")
    c3("callJson 有 function 包装", cj.getJSONObject("function").getString("name") == "get_weather")
    c3("arguments 是字符串而非对象（OpenAI 规范）",
        cj.getJSONObject("function").get("arguments") is String)
    c3("arguments 内容未被破坏",
        cj.getJSONObject("function").getString("arguments") == """{"city":"北京"}""")

    // id 缺失时按 index 兜底，且与 native 同一条规则
    val noId = JSONArray().put(JSONObject().apply {
        put("id", ""); put("name", "f"); put("arguments", "{}")
    })
    c3("id 缺失 -> call_<下标>",
        JSONObject(ToolCalls.callJson(3, noId.getJSONObject(0))).getString("id") == "call_3")

    // ---- 响应侧：arguments 内含引号/换行，外层 JSON 不能被撑破 ----
    // 模型给的是内嵌 JSON，"值里带引号和换行"是最常见的一类（用户输入回填进参数）。
    // 期望：外层解析回来后 arguments 仍是**同一段文本**（转义原样保留），
    // 若拼装时漏转义，JSONObject(outer) 会直接抛 JSONException。
    val trickyArgs = """{"t":"a\"b\nc"}"""
    val tricky = JSONArray().put(JSONObject().apply {
        put("id", "call_0"); put("name", "echo")
        put("arguments", trickyArgs)
    })
    val outer = """{"tool_calls":[${ToolCalls.callJson(0, tricky.getJSONObject(0))}]}"""
    val reparsed = JSONObject(outer)   // 撑破的话这里会抛
    c3("arguments 含引号/换行时外层 JSON 仍可解析",
        reparsed.getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function").getString("arguments") == trickyArgs)

    // ---- 响应侧：message.tool_calls 字段（含前导逗号）----
    val field = ToolCalls.messageToolCallsField(one)
    c3("messageToolCallsField 以逗号开头（便于拼进 message）", field.startsWith(","))
    c3("messageToolCallsField 键名为 tool_calls", field.contains("\"tool_calls\":"))
    val msgJson = """{"role":"assistant","content":null$field}"""
    val msg = JSONObject(msgJson)
    c3("拼出的 message 可解析", msg.getString("role") == "assistant")
    c3("拼出的 message.tool_calls 长度为 1", msg.getJSONArray("tool_calls").length() == 1)
    c3("message.tool_calls[0].function.name 正确",
        msg.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name") == "get_weather")
    c3("空数组 -> 空串（不产生 ,\"tool_calls\":[] 噪声）",
        ToolCalls.messageToolCallsField(JSONArray()).isEmpty())

    // ---- 响应侧：并行多调用 ----
    val three = calls(
        Triple("call_0", "a", "{}"),
        Triple("call_1", "b", "{}"),
        Triple("call_2", "c", "{}")
    )
    val deltas = ToolCalls.streamDeltas(three)
    c3("并行调用下发 3 个增量块", deltas.size == 3)
    c3("每个增量块 index 递增",
        deltas.mapIndexed { i, d -> JSONObject(d).getJSONArray("tool_calls")
            .getJSONObject(0).getInt("index") == i }.all { it })
    c3("每个增量块可独立解析（客户端逐个聚合）",
        deltas.all { JSONObject(it).getJSONArray("tool_calls").length() == 1 })
    c3("流式与非流式的 id 规则一致",
        JSONObject(deltas[1]).getJSONArray("tool_calls").getJSONObject(0).getString("id") == "call_1")

    val whole = JSONObject("""{"tool_calls":[${
        (0 until three.length()).joinToString(",") { ToolCalls.callJson(it, three.getJSONObject(it)) }
    }]}""")
    c3("非流式并行调用可解析且长度为 3", whole.getJSONArray("tool_calls").length() == 3)

    // ---- 模板一致性：解析必须用与渲染同一个模板 ----
    // 解析器是按模板推导出的 PEG，两边模板不一致（App 里选了自定义模板、
    // 解析时却让 chat 层自选内置）会解析错甚至触发 native 断言。
    // nativeParseToolCalls 的第三个参数就是为此存在，这里钉住调用方必须传模板。
    val cpp = java.io.File("app/src/main/cpp/llama_jni.cpp").readText()
    // 只取 parseToolCallsImpl 的函数体：渲染路径同样有 add_generation_prompt，不能误命中
    fun cppParse(): String {
        val start = cpp.indexOf("static jstring parseToolCallsImpl")
        return if (start < 0) "" else cpp.substring(start)
    }
    val src = java.io.File("app/src/main/java/com/xiaowan/localinference/LlmEngine.kt").readText()
    c3("nativeParseToolCalls 带 tmpl 形参（模板一致性）",
        src.contains("text: String, toolsJson: String?, tmpl: String, addAss: Boolean"))
    // 形参名会随探针埋点调整，但"必须传模板变量、不能就地再取一次"这条语义不能松：
    // 渲染与解析之间若重新取一次模板，两边可能取到不同结果。
    c3("parseToolCalls 调用时把模板变量传进去",
        src.contains("nativeParseToolCalls(text, toolsJson, tmpl, addAss)"))

    // ---- add_generation_prompt 一致性：这是 tool_calls 恒为 0 的第二条真因 ----
    // cp.generation_prompt（会被 common_chat_parse 前拼到输入上、决定 PEG 根节点能否匹配）
    // 随 add_generation_prompt 变化。解析侧以前写死 false、渲染侧传 true，
    // 于是解析侧算出的前缀是 "<|im_start|>assistant\n<think>\n"（30B），
    // 而 PEG 根节点要的是 "<|im_start|>assistant\n"（22B），差的 8B 正是 "<think>\n"
    // —— 与真机日志 content_len - text_len = 8 精确吻合，根节点一上来就匹配不上。
    // 下面四条钉死"两边必须同源、且不得再写死"。
    c3("parseToolCalls 把 addAss 透传进 native",
        src.contains("nativeParseToolCalls(text, toolsJson, tmpl, addAss)"))
    c3("parseToolCalls 对 addAss 有默认值且与渲染侧同取 true",
        src.contains("fun parseToolCalls(text: String, toolsJson: String, addAss: Boolean = true)"))
    c3("native parse 侧不再写死 add_generation_prompt = false",
        !cppParse().contains("in.add_generation_prompt = false;"))
    c3("native parse 侧 add_generation_prompt 取自 addAss",
        cpp.contains("in.add_generation_prompt = (addAss == JNI_TRUE);"))
    c3("nativeApplyChatTemplateTools 也接收模板参数",
        src.contains("nativeApplyChatTemplateTools(") && src.contains("tmpl: String"))

    // ---- 崩溃防线：解析失败必须回落为纯文本，不能让异常穿过 JNI ----
    // 现场症状是「客户端一调工具，作为服务端的 App 就闪退，然后自动恢复」。
    // 成因之一：common_chat_parse 在输出不匹配模板语法时 throw，
    // C++ 异常穿过 JNI 帧是 UB，多数构建直接 std::terminate -> SIGABRT。
    // 所以 native 侧必须"任何失败都只返回 null"，且调用方不做假设。
    c3("LlmEngine.parseToolCalls 对 native 异常有兜底（返回 null 而非抛出）",
        src.contains("catch (t: Throwable)") &&
            src.contains("[工具] 解析失败"))

    // ---- 崩因回归（native 侧）：空串必须前置判掉、且解析那一跳必须有 try/catch ----
    // vendor 库对空串是 throw 而不是返回 AUTO，异常穿过 JNI 帧即 SIGABRT。
    // 这两条防线任何一条缺失都会让"未传 tool_choice 的 agent 请求"重新变成闪退。
    c3("native 对空 tool_choice 做前置判断（不把空串喂给库）",
        cpp.contains("toolChoice.empty()"))
    c3("native 空串前置判断走的是显式 AUTO",
        cpp.contains("in.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;"))
    c3("native tool_choice 解析包在 try/catch 里",
        cpp.contains("common_chat_tool_choice_parse_oaicompat(toolChoice)"))
    c3("native tool_choice 解析异常降级为 AUTO（不向外抛）",
        cpp.contains("退回 AUTO"))

    // ---- 崩因回归（JNI 边界）：入口必须有 catch-all 兜底 ----
    // 逐点 try/catch 覆盖不了库内全部 throw 路径；入口这一层兜底才是
    // "任何参数组合都不能让服务端进程死"的最后一道墙。
    c3("nativeApplyChatTemplateTools 入口有 catch-all",
        cpp.contains("Java_com_xiaowan_localinference_LlmEngine_nativeApplyChatTemplateTools") &&
            cpp.contains("applyChatTemplateTools 边界捕获异常"))
    c3("nativeParseToolCalls 入口有 catch-all",
        cpp.contains("Java_com_xiaowan_localinference_LlmEngine_nativeParseToolCalls") &&
            cpp.contains("parseToolCalls 边界捕获异常"))

    println("=== 工具调用单测 ${if (f3 == 0) "全部通过" else "$f3 条失败"} ===")
    if (f3 != 0) kotlin.system.exitProcess(1)
}
