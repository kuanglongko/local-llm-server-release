package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject

// `response_format`（结构化输出）请求侧解析与校验的单测。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么要单独一份（这些断言各自防的都是一类"静默"故障）
// ═══════════════════════════════════════════════════════════════════════════
// 1. **默认行为必须逐字节不变**：不带 `response_format` 的请求必须解析成 None，
//    且 schemaArg == null（= native 侧不加 grammar 采样器）。任何"顺手给个默认格式"
//    都会改变**所有**既有客户端的输出分布，而它不会让任何现有测试变红。
// 2. **拼错的 type 不能被静默忽略**：`"json_shema"` 若被当成"没给"处理，调用方
//    会以为约束生效了、拿到自由文本却找不出原因。必须 400。
// 3. **json_object 不能退化成 none**：`json_object` 要的是"JSON 约束但无结构"，
//    折成 None 会让这个类型静默失效（输出仍是自由文本）。判据是 schemaArg == ""
//    而不是 null —— 这两个值的 native 语义完全不同。
// 4. **schema 原文不能被改写**：Kotlin 侧只做形状校验，不改写、不重新序列化。
// 5. **生成后缀的"未知"与"确认没有"必须分开**（`null` vs 空串）：折错方向会给
//    prompt 施加错位置的 grammar 约束 —— 真机表现是模型自己把生成后缀吐一遍
//    （`content = "<|im_start|>assistant\n[ ]"`）。见 ResponseFormat.GenerationPrompt。
// 6. **合法性判据不得替库下结论**：曾经硬编码"schema.type 是数组 -> 400"，
//    而真机证明库能接受 —— 能力边界交给 native 就地 catch + 降级，这里只判协议级错误。
//    改写（哪怕只是字段顺序/转义）都可能让库产出的 GBNF 与调用方预期不符，
//    而那是静默的。
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

/** 构造一个请求体，带可选 response_format。 */
private fun req(rf: String?): JSONObject {
    val j = JSONObject()
    j.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
    if (rf != null) j.put("response_format", JSONObject(rf))
    return j
}

/** 真机那例的 content 逐字：```json 围栏 + 3 元素数组（Qwen3 test_array）。 */
private const val FENCED_ARR = "```json\n[\n  \"pandas\",\n  \"numpy\",\n  \"matplotlib\"\n]\n```"

fun main() {
    // ══════════════════════════════════════════════════════════════════
    // 1. 缺省 —— 与引入本特性前逐字节相同
    // ══════════════════════════════════════════════════════════════════
    run {
        val (f, e) = JsonSchemaFormat.fromRequest(req(null))
        ck("字段缺失 -> None", f == ResponseFormat.None)
        ck("字段缺失 -> 无错误", e == null)
        ck("字段缺失 -> schemaArg 是 null（不产生 native 调用）", JsonSchemaFormat.schemaArg(f!!) == null)
    }
    run {
        val j = JSONObject(); j.put("response_format", JSONObject.NULL)
        val (f, _) = JsonSchemaFormat.fromRequest(j)
        ck("字段为 null -> None（不当成非法）", f == ResponseFormat.None)
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. type = text / json_object
    // ══════════════════════════════════════════════════════════════════
    run {
        val (f, e) = JsonSchemaFormat.fromRequest(req("""{"type":"text"}"""))
        ck("type=text -> None", f == ResponseFormat.None)
        ck("type=text -> 无错误", e == null)
    }
    run {
        val (f, e) = JsonSchemaFormat.fromRequest(req("""{"type":"json_object"}"""))
        ck("type=json_object -> JsonObject", f == ResponseFormat.JsonObject)
        ck("type=json_object -> 无错误", e == null)
        // 关键：空串而非 null。null 会让 native 侧不挂 grammar，于是 json_object 静默失效。
        ck("type=json_object -> schemaArg 是空串（不是 null）", JsonSchemaFormat.schemaArg(f!!) == "")
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. type = json_schema —— 正常路径
    // ══════════════════════════════════════════════════════════════════
    run {
        val body = """{"type":"json_schema","json_schema":{"name":"person","strict":true,"schema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}"""
        val (f, e) = JsonSchemaFormat.fromRequest(req(body))
        ck("json_schema -> JsonSchema", f is ResponseFormat.JsonSchema)
        ck("json_schema -> 无错误", e == null)
        val js = f as ResponseFormat.JsonSchema
        ck("name 被保留", js.name == "person")
        ck("strict 被保留", js.strict)
        // 原文不改写：解析回来必须还是那个对象，且 required 数组仍在
        val back = JSONObject(js.schema)
        ck("schema 原文可解析回对象（未被改写）", back.optString("type") == "object")
        ck("schema 原文保留了 required", back.optJSONArray("required")?.optString(0) == "name")
        ck("schemaArg 就是 schema 原文", JsonSchemaFormat.schemaArg(f) == js.schema)
    }
    run {
        // strict / name 缺省
        val (f, _) = JsonSchemaFormat.fromRequest(
            req("""{"type":"json_schema","json_schema":{"schema":{"type":"object"}}}"""))
        val js = f as ResponseFormat.JsonSchema
        ck("name 缺省为 null", js.name == null)
        ck("strict 缺省为 false", !js.strict)
    }
    run {
        // 只给 json_schema 对象、不给 type —— 宽容处理（有真实客户端这么写）
        val (f, e) = JsonSchemaFormat.fromRequest(
            req("""{"json_schema":{"schema":{"type":"object"}}}"""))
        ck("缺 type 但有 json_schema -> 按 json_schema 处理", f is ResponseFormat.JsonSchema)
        ck("缺 type 但有 json_schema -> 无错误", e == null)
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. 非法输入一律 400（返回 null + 可读原因）
    // ══════════════════════════════════════════════════════════════════
    fun mustBad(name: String, body: String) {
        val (f, e) = JsonSchemaFormat.fromRequest(req(body))
        ck("$name -> 判非法", f == null)
        ck("$name -> 带原因", !e.isNullOrEmpty())
    }
    // response_format 不是对象
    run {
        val j = JSONObject(); j.put("response_format", "json")
        val (f, e) = JsonSchemaFormat.fromRequest(j)
        ck("response_format 是字符串 -> 非法", f == null && !e.isNullOrEmpty())
    }
    run {
        val j = JSONObject(); j.put("response_format", 42)
        val (f, _) = JsonSchemaFormat.fromRequest(j)
        ck("response_format 是数字 -> 非法", f == null)
    }
    mustBad("type 是数字", """{"type":123}""")
    mustBad("type 拼错（json_shema）", """{"type":"json_shema"}""")
    mustBad("type 是未知取值", """{"type":"xml"}""")
    mustBad("json_schema 类型不是对象", """{"type":"json_schema","json_schema":"x"}""")
    mustBad("缺 json_schema 包装", """{"type":"json_schema"}""")
    mustBad("json_schema 里缺 schema", """{"type":"json_schema","json_schema":{"name":"a"}}""")
    mustBad("schema 是字符串", """{"type":"json_schema","json_schema":{"schema":"notobj"}}""")
    mustBad("schema 是数组", """{"type":"json_schema","json_schema":{"schema":[1,2]}}""")
    mustBad("schema 是 null", """{"type":"json_schema","json_schema":{"schema":null}}""")
    mustBad("name 不是字符串", """{"type":"json_schema","json_schema":{"name":1,"schema":{"type":"object"}}}""")
    mustBad("strict 不是布尔", """{"type":"json_schema","json_schema":{"strict":"yes","schema":{"type":"object"}}}""")
    // ⚠️ 这里原本有一条「schema.type 是数组 -> 400」，**已删除**：那是标准 JSON Schema 的
    // 联合类型（`["string","null"]`），真机实测库能接受并转出 GBNF —— 那条判据拦的是
    // 合法请求。能力边界（库真的转换不了）由 native 侧就地 catch + 降级处理，不由 Kotlin 侧猜。
    run {
        val (f, e) = JsonSchemaFormat.fromRequest(
            req("""{"type":"json_schema","json_schema":{"schema":{"type":["string","null"]}}}"""))
        ck("schema.type 是数组 -> 接受（不再硬编码判 400）", f is ResponseFormat.JsonSchema)
        ck("schema.type 是数组 -> 无错误", e == null)
    }
    run {
        // 顶层数组 schema：真机就是用这个形状测的（`{"type":"array","items":{"type":"string"}}`）
        val (f, e) = JsonSchemaFormat.fromRequest(
            req("""{"type":"json_schema","json_schema":{"schema":{"type":"array","items":{"type":"string"}}}}"""))
        ck("数组 schema -> 接受", f is ResponseFormat.JsonSchema)
        ck("数组 schema -> 原文照传（不改写）",
            (f as ResponseFormat.JsonSchema).schema.contains("\"items\""))
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. schema 体积上限：**拒绝**而不是截断
    // ══════════════════════════════════════════════════════════════════
    run {
        // 造一个刚好超限的 schema
        val big = StringBuilder()
        big.append("{\"type\":\"object\",\"properties\":{")
        var i = 0
        while (big.length < JsonSchemaFormat.MAX_SCHEMA_BYTES + 100) {
            if (i > 0) big.append(",")
            big.append("\"k$i\":{\"type\":\"string\"}")
            i++
        }
        big.append("}}")
        val body = JSONObject().put("type", "json_schema")
            .put("json_schema", JSONObject().put("schema", JSONObject(big.toString()))).toString()
        val (f, e) = JsonSchemaFormat.fromRequest(req(body))
        ck("超大 schema -> 判非法（拒绝而不是截断）", f == null)
        ck("超大 schema -> 原因为体积上限", e == JsonSchemaFormat.errSchemaTooLarge)
    }
    run {
        // 边界：正好等于上限应当**通过**（断言用的是 > 而不是 >=）
        val probe = JsonSchemaFormat.MAX_SCHEMA_BYTES
        ck("上限是 64KiB", probe == 64 * 1024)
    }

    // ══════════════════════════════════════════════════════════════════
    // 6. schemaArg 的三态映射（native 语义的唯一来源）
    // ══════════════════════════════════════════════════════════════════
    ck("None -> null", JsonSchemaFormat.schemaArg(ResponseFormat.None) == null)
    ck("JsonObject -> 空串", JsonSchemaFormat.schemaArg(ResponseFormat.JsonObject) == "")
    ck("JsonSchema -> 原文",
        JsonSchemaFormat.schemaArg(ResponseFormat.JsonSchema("""{"type":"object"}""", null, false))
            == """{"type":"object"}""")

    // ══════════════════════════════════════════════════════════════════
    // 7. describe 不泄露整段 schema（对话日志是要给人看的）
    // ══════════════════════════════════════════════════════════════════
    run {
        val long = """{"type":"object","properties":{"veryLongFieldName":${"\"x\"".repeat(50)}}}"""
        val d = JsonSchemaFormat.describe(ResponseFormat.JsonSchema(long, "n", true))
        ck("describe 带 name/strict", d.contains("name=n") && d.contains("strict=true"))
        ck("describe 只报 schema 长度、不整段回显", !d.contains("veryLongFieldName"))
    }
    ck("describe(None) 明说无约束",
        JsonSchemaFormat.describe(ResponseFormat.None).contains("未指定"))

    // ══════════════════════════════════════════════════════════════════
    // 8. genPromptArg（生成后缀的三态）—— 真机故障的判据
    // ══════════════════════════════════════════════════════════════════
    // 这一节的每一条都对应一个"静默"后果：
    //   · 把"确认没有生成后缀"折成 null -> 给没有后缀的 prompt 施加"后缀之后"的约束；
    //   · 把"未知"折成空串 -> 反过来，给有后缀的 prompt 施加"立刻开始"的约束；
    //   · None 时返回非 null -> 默认路径多了一个 native 入参（第一条硬判据被破坏）。
    val js = ResponseFormat.JsonSchema("""{"type":"object"}""", null, false)
    ck("None -> null（默认路径不得新增入参）",
        JsonSchemaFormat.genPromptArg(ResponseFormat.None, ResponseFormat.GenerationPrompt.EMPTY) == null)
    ck("JsonObject + 未知 -> null（native 退回旧口径）",
        JsonSchemaFormat.genPromptArg(ResponseFormat.JsonObject, null) == null)
    ck("JsonObject + 确认没有 -> 空串（不是 null）",
        JsonSchemaFormat.genPromptArg(ResponseFormat.JsonObject, ResponseFormat.GenerationPrompt.EMPTY) == "")
    ck("JsonSchema + 有后缀 -> 原文",
        JsonSchemaFormat.genPromptArg(js, ResponseFormat.GenerationPrompt.TEXT("<|im_start|>assistant\n"))
            == "<|im_start|>assistant\n")
    ck("未知(null) 与 确认没有(空串) 不相等",
        (JsonSchemaFormat.genPromptArg(js, null) ?: "N") !=
            JsonSchemaFormat.genPromptArg(js, ResponseFormat.GenerationPrompt.EMPTY))
    ck("ChatML 回落常量与 LlmEngine 的拼法一致（同一段尾巴）",
        RequestContext.CHATML_GEN_SUFFIX == "<|im_start|>assistant\n")
    ck("describeGenPrompt 的入参就是下发的那个值（null -> 未知）",
        JsonSchemaFormat.describeGenPrompt(
            JsonSchemaFormat.genPromptArg(js, null)).contains("未知"))
    ck("describeGenPrompt 空串 -> 明说没有生成后缀",
        JsonSchemaFormat.describeGenPrompt(
            JsonSchemaFormat.genPromptArg(js, ResponseFormat.GenerationPrompt.EMPTY)).contains("空"))
    ck("describeGenPrompt 不整段回显后缀（只报长度）",
        !JsonSchemaFormat.describeGenPrompt("<|im_start|>assistant\n").contains("im_start"))

    // ══════════════════════════════════════════════════════════════════
    // 9. RequestContext：模板同源 + addAss=false 时"确认没有"
    // ══════════════════════════════════════════════════════════════════
    ck("模板取不到时空串（= 让库按模型自选，旧行为）",
        RequestContext.chatTemplateOf(null) == "")
    ck("模板取得到时原样透传（不做 trim / 不做长度限制）",
        RequestContext.chatTemplateOf("  {{ x }}  ") == "  {{ x }}  ")
    ck("addAss=false -> 空串（这一轮确实没有生成后缀）",
        RequestContext.genPromptArg(null, addAss = false) == "")
    ck("addAss=true 但渲染失败 -> null（未知，不猜）",
        RequestContext.genPromptArg(null, addAss = true) == null)
    ck("addAss=true 且渲染成功 -> 用渲染侧给的后缀",
        RequestContext.genPromptArg(
            RenderedPrompt("p", false, "<|im_start|>assistant\n<think>\n"), addAss = true)
            == "<|im_start|>assistant\n<think>\n")
    ck("渲染成功但后缀未知（旧 native 不给）-> null，不拿空串顶替",
        RequestContext.genPromptArg(RenderedPrompt("p", false), addAss = true) == null)

    // ══════════════════════════════════════════════════════════════════
    // 10. probeBody：响应体探针（**唯一**能看见"模型吐了什么"的那一行）
    // ══════════════════════════════════════════════════════════════════
    // 这一节的每一条都防"打了日志却读不出来"：
    //   · 换行不转义 -> 一行被日志文件按行撕开，grep [body] 只捞到第一段，
    //     而 Qwen3 的故障差别**就在换行上**（`\n` 与 `\n\n</think>\n\n`）；
    //   · 不报元素个数 -> `[ ]` 与 `["a","b","c"]` 都是 13 tok，数字上分不开；
    //   · 围栏不剥离就报 json=fail -> "吐了围栏"与"吐了彻底非法的东西"混成一条读数，
    //     而这两者的修法完全不同。
    val bare = JsonSchemaFormat.probeBody("""{"is_even":true}""")
    ck("裸对象：json=ok(object)、无围栏、不是数组",
        bare.contains("json=ok(object)") && bare.contains("strip=无") && bare.contains("array=-1"))
    val arrBare = JsonSchemaFormat.probeBody("""["pandas","numpy","matplotlib"]""")
    ck("裸数组：json=ok(array) 且**元素个数**被打出来（3）",
        arrBare.contains("json=ok(array)") && Regex("array=3\\b").containsMatchIn(arrBare))
    // 这一条是本轮的真机原样（Qwen3 test_array 的 content，逐字）：
    val qwen3 = JsonSchemaFormat.probeBody("[json]".let { "```json\n[\n  \"pandas\",\n  \"numpy\",\n  \"matplotlib\"\n]\n```" })
    ck("真机那例（```json 围栏 + 3 元素）：strip=有围栏已剥 且 array=3",
        qwen3.contains("有围栏已剥") && Regex("array=3\\b").containsMatchIn(qwen3))
    ck("换行被转义成可见的 \\n（否则一行日志会被按行撕开）",
        qwen3.contains("\\n") && !qwen3.contains("\n"))
    // tail 只在**超出 head 窗口**时才打（短响应体打 tail 是把同一段重复一遍）。
    // 所以这里用一个超过 HEAD+TAIL 的两头形状样例来验：前段看围栏、后段看未闭合尾巴。
    val twoEnds = JsonSchemaFormat.probeBody(
        "```json\n" + "[1,2,3]" + "," + " ".repeat(200) + "," + "\"TAILMARK\"\n```")
    ck("head 与 tail 都在（前段看围栏，后段看未闭合尾巴）",
        twoEnds.contains("head=\"") && twoEnds.contains("tail=\"") && twoEnds.contains("TAILMARK"))
    ck("短响应体只打 head、不重复打 tail（日志不灌水）",
        !qwen3.contains("tail=\""))
    val empty = JsonSchemaFormat.probeBody("[ ]")
    ck("空数组 `[ ]`：array=0（与 3 元素在数字上可区分）",
        empty.contains("array=0") && empty.contains("json=ok(array)"))
    ck("彻底非法（不是 JSON）：json=fail 且 array=-1",
        JsonSchemaFormat.probeBody("I cannot answer that.").let {
            it.contains("json=fail") && it.contains("array=-1") })
    ck("空响应体不炸（json=fail，bytes=0）",
        JsonSchemaFormat.probeBody("").contains("bytes=0"))
    ck("think 残段能被 head 照出来（这是 Qwen3 那类故障的第一嫌疑）",
        JsonSchemaFormat.probeBody("<|im_start|>assistant\n{\"a\":1}").contains("im_start"))
    // head/tail 的边界：极长内容不得把整段日志灌满（只报首尾两小段）。
    val long = "{" + "x".repeat(4000) + "}"
    ck("长响应只报首尾两小段（不整段回显，日志文件不被撑大）",
        JsonSchemaFormat.probeBody(long).length < 400)
    // 转义必须**恰好转一层**：日志里看到 "\\n" 就说明转了两层，等于换了口径。
    ck("转义只转一层（head 里是 \\n，不是 \\\\n）",
        JsonSchemaFormat.probeBody("a\nb").contains("head=\"a\\nb\""))

    // ══════════════════════════════════════════════════════════════════
    // 11. 请求关联码：把"这一例请求要了什么"与"这一例吐出来什么"按 id 对齐
    // ══════════════════════════════════════════════════════════════════
    // 真机缺口：请求侧那三行逐行可读，却**一行都对不上** —— 无 id、三例长得一模一样
    // （只有 name= 不同），与生成后的 `[body]` 只能靠数顺序配对。这几条盯的就是"能不能配上对"。
    ck("reqTagOf 取 id 的**数字尾巴**（前缀是噪音，区分彼此只靠尾巴）",
        JsonSchemaFormat.reqTagOf("chatcmpl-local-1789992569096") == "1789992569096")
    ck("reqTagOf 对无数字 id 退化成取尾段（不得变成空串 —— 空串 = 配不上对）",
        JsonSchemaFormat.reqTagOf("cmpl-abcXYZ") == "cmpl-abcXYZ")
    ck("reqTagOf 对 null / 空串给空串（不猜、不抛）",
        JsonSchemaFormat.reqTagOf(null) == "" && JsonSchemaFormat.reqTagOf("") == "")
    val tagged = JsonSchemaFormat.probeBody("1789992569096", "[\"a\",\"b\",\"c\"]")
    ck("带关联码时只剩多一个 id 段，读数其余部分与不带时逐字相同",
        tagged.startsWith("[body id=1789992569096] bytes=") &&
            tagged.substringAfter("] ") ==
            JsonSchemaFormat.probeBody("[\"a\",\"b\",\"c\"]").substringAfter("] "))
    ck("不带关联码（空串）时输出与 0.9.94 逐字节同形（老日志判据不被改口径）",
        JsonSchemaFormat.probeBody("", "{}").startsWith("[body] bytes=2"))
    // 关联码里不得出现空格/引号 —— 它直接落进 `[body id=...]` 的方括号里，
    // 带空格会把这一行日志撕成两段（与本探针"必须一行"的初衷相反）。
    val allTags = listOf("chatcmpl-local-1789992569096", "cmpl-abcXYZ", "")
        .map { JsonSchemaFormat.reqTagOf(it) }.joinToString("")
    ck("关联码本身不含空白与引号（否则会把一行日志撕开）",
        allTags.none { it.isWhitespace() || it == '"' })

    // ══════════════════════════════════════════════════════════════════
    // 12. 下发前剥围栏：只在"要求了结构化输出"且"剥后仍是 JSON"时才动字节
    // ══════════════════════════════════════════════════════════════════
    // 这三条硬约束任一写错都比不剥更糟：
    //   ① 没要求结构化输出时原样下发（否则会改掉"本身就带围栏"的普通文本）；
    //   ② 剥了必须还是 JSON（否则会把"模型吐了半截"伪装成"服务端啃坏了"）；
    //   ③ 不剥时**同一实例**返回（调用方据此区分"本来就没围栏"与"剥了"）。
    val jsFmt = ResponseFormat.JsonSchema("{}", "t", false)

    // ①：None -> 字节一个不动
    ck("未要求结构化输出：原样下发（哪怕内容带围栏）",
        JsonSchemaFormat.stripFenceForDelivery(ResponseFormat.None, FENCED_ARR) === FENCED_ARR)
    ck("未要求结构化输出：描述行明说原样下发",
        JsonSchemaFormat.describeDeliveryStrip(ResponseFormat.None, FENCED_ARR).contains("原样下发"))

    // 关键路径：真机那例（带 json 围栏的数组）
    val stripped = JsonSchemaFormat.stripFenceForDelivery(jsFmt, FENCED_ARR)
    ck("要求了 json_schema + 剥后仍是 JSON：围栏被剥掉",
        stripped == "[\n  \"pandas\",\n  \"numpy\",\n  \"matplotlib\"\n]")
    ck("剥掉的东西能直接 JSON 解析（这才是 response_format 的承诺）",
        stripped.trimStart().startsWith("[") && stripped.trimEnd().endsWith("]"))
    ck("已剥的描述行报出**前后字节数**（服务端改了字节必须留痕）",
        JsonSchemaFormat.describeDeliveryStrip(jsFmt, FENCED_ARR).let {
            it.contains("已剥") && it.contains("${FENCED_ARR.toByteArray(Charsets.UTF_8).size}B -> ") })

    // ②：剥后非 JSON -> 退回原件（宁可让调用方看见围栏，也不发残段）
    val fencedProse = "```\nI cannot answer that.\n```"
    ck("有围栏但剥后不是 JSON：退回**原件**（不发残段）",
        JsonSchemaFormat.stripFenceForDelivery(jsFmt, fencedProse) === fencedProse)
    ck("退回原件的描述行明说\u201c退回\u201d",
        JsonSchemaFormat.describeDeliveryStrip(jsFmt, fencedProse).contains("退回原件"))

    // ③：无围栏时同一实例返回（对象身份，不是内容相等）
    val bareJson = """{"is_even":true}"""
    ck("无围栏：返回同一实例（不新建字符串 —— 调用方据此判\u201c没动\u201d）",
        JsonSchemaFormat.stripFenceForDelivery(jsFmt, bareJson) === bareJson)
    ck("无围栏：描述行报\u201c无围栏，原样下发\u201d",
        JsonSchemaFormat.describeDeliveryStrip(jsFmt, bareJson).contains("无围栏"))

    // 围栏开在中间（模型先说了句话再说 JSON）不算围栏 —— 不得去猜、不得切一刀
    val midFence = "Here it is:\n```json\n{\"a\":1}\n```"
    ck("围栏不在开头（前面有散文）：不碰（只认\u201c以围栏开头\u201d这一种确定形态）",
        JsonSchemaFormat.stripFenceForDelivery(jsFmt, midFence) === midFence)

    // 只有开围栏没有闭围栏：不剥（不能把没闭合的东西当完整 JSON 发出去）
    val openOnly = "```json\n{\"a\":1}"
    ck("只有开围栏、无闭围栏：不碰（不把未闭合段当完整 JSON）",
        JsonSchemaFormat.stripFenceForDelivery(jsFmt, openOnly) === openOnly)

    // json_object 同样在范围内（它是"要求了结构化输出"的另一半）
    ck("json_object 也剥（与 json_schema 同一条判据，不另判一次）",
        JsonSchemaFormat.stripFenceForDelivery(ResponseFormat.JsonObject, FENCED_ARR) == stripped)

    println()
    if (fail == 0) { println("=== JsonSchemaFormatTest: ALL PASS ==="); return }
    println("=== JsonSchemaFormatTest: $fail FAILED ===")
    kotlin.system.exitProcess(1)
}
