package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject

/**
 * `EmbeddingsApi`（`/v1/embeddings` 的请求解析 / 校验 / 响应拼装）的离线单测。
 *
 * 钉的是这一层**最容易静默出错**的东西 —— 它们全都不崩、接口 200：
 *   · 把 `index` 按"第几个成功"算 -> 中间有一条失败时，之后的向量全部错位一格，
 *     调用方按 index 配对入库 -> 文本 A 配了文本 B 的向量，检索全错而不报错；
 *   · token id 数组被"尽力解释"成文本 -> 调用方以为编码了自己的输入，
 *     实际编码的是另一段（token 语义不同），向量与文本无关；
 *   · 空串被当合法编码 -> 要么零向量（余弦 0/0）、要么失败，两种都不是调用方要的；
 *   · 数值用 `%f` 格式化 -> 小分量被压成 0，向量方向悄悄变了；
 *   · 非有限值直接拼进 JSON -> 整份响应非法，客户端解析失败而错误指向"格式"。
 */
object EmbeddingsApiTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    private fun eqStr(a: String?, b: String?, what: String) {
        if (a == b) { pass++; println("PASS  $what") }
        else { fail++; println("FAIL  $what\n     got=[$a]\n     want=[$b]") }
    }

    private fun eqi(a: Int, b: Int, what: String) {
        if (a == b) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what got=$a want=$b") }
    }

    /** 三元组里只取"请求或错误"这一对，测试绝大多数只关心这两个。 */
    private fun req(json: String): Pair<EmbeddingsApi.Request?, String?> {
        val (r, _, e) = EmbeddingsApi.fromRequest(JSONObject(json))
        return r to e
    }

    /** 取解析出来的文本列表（id 项会在这里被排除，见 testTokenIds）。 */
    private fun texts(json: String): List<String>? =
        req(json).first?.inputs?.mapNotNull { (it as? EmbeddingsApi.Item.Text)?.text }

    private fun enc(json: String): EmbeddingsApi.Encoding? =
        EmbeddingsApi.fromRequest(JSONObject(json)).second?.encoding

    // ── fromRequest：合法形态 ──────────────────────────────────────────────
    private fun testParse() {
        val (a, ea) = req("""{"input":"一句话"}""")
        ok(a != null && ea == null && a.inputs.size == 1 && !a.batch,
            "单条字符串 -> 1 条、batch=false")

        val (b, eb) = req("""{"input":["a","b","c"]}""")
        ok(b != null && eb == null && b.inputs.size == 3 && b.batch,
            "字符串数组 -> 3 条、batch=true")

        // 空数组是**合法**的（确实没有要编码的东西），回空 data 而不是 400
        val (c, ec) = req("""{"input":[]}""")
        ok(c != null && ec == null && c.inputs.isEmpty(),
            "空数组 -> 合法（空 data），不是错误")

        // 顺序必须原样保留（index 就是它）
        eqStr(texts("""{"input":["z","a"]}""")?.joinToString(","), "z,a",
            "顺序原样保留（index 依赖它）")

        // 缺 input
        val (e, ee) = req("""{}""")
        ok(e == null && ee != null, "缺 input -> 400 说明")
        // 类型错
        ok(req("""{"input":123}""").first == null, "input 是数字 -> 拒绝")
        ok(req("""{"input":{"a":1}}""").first == null, "input 是对象 -> 拒绝")
    }

    // ── fromRequest：明确拒绝的东西 ────────────────────────────────────────
    private fun testReject() {
        // 数组里含空串
        val (b, eb) = req("""{"input":["ok",""]}""")
        ok(b == null && eb != null, "数组里含空串 -> 拒绝（空文本没有可编码的语义）")

        // 单条空串
        ok(req("""{"input":""}""").first == null, "单条空串 -> 拒绝")

        // 数组元素类型错
        ok(req("""{"input":["a",null]}""").first == null, "元素是 null -> 拒绝")
        ok(req("""{"input":["a",["b"]]}""").first == null, "元素是嵌套数组 -> 拒绝")
        ok(req("""{"input":["a",1]}""").first == null, "元素是数字 -> 拒绝（含说明）")

        // 条数上限
        val many = (0..EmbeddingsApi.MAX_INPUTS).joinToString(",") { "\"x$it\"" }
        val (m, em) = req("""{"input":[$many]}""")
        ok(m == null && em != null, "条数超上限 -> 400（截断会让 data 与文本错位）")

        // 单条过长
        val long = "x".repeat(EmbeddingsApi.MAX_INPUT_CHARS + 1)
        ok(req("""{"input":["$long"]}""").first == null, "单条超长 -> 400（截断会造成错位）")

        // encoding_format：float（默认）与 base64 都接受，其余 400
        ok(req("""{"input":"a","encoding_format":"float"}""").first != null,
            "encoding_format=float -> 接受")
        eqStr(enc("""{"input":"a","encoding_format":"float"}""").toString(), "FLOAT",
            "encoding_format=float -> 解析成 FLOAT")
        ok(req("""{"input":"a","encoding_format":"base64"}""").first != null,
            "encoding_format=base64 -> 接受（0.9.170 起支持）")
        eqStr(enc("""{"input":"a","encoding_format":"base64"}""").toString(), "BASE64",
            "encoding_format=base64 -> 解析成 BASE64")
        ok(req("""{"input":"a"}""").first != null,
            "缺 encoding_format -> 接受")
        eqStr(enc("""{"input":"a"}""").toString(), "FLOAT",
            "缺 encoding_format -> 默认 FLOAT（OpenAI 的 default 就是 float）")

        // dimensions：**明确 400**，不静默忽略
        ok(req("""{"input":"a","dimensions":512}""").first == null,
            "dimensions 有值 -> 明确 400（静默忽略会让调用方按错维度截，静默错位）")
        ok(req("""{"input":"a","dimensions":null}""").first != null,
            "dimensions=null -> 不拒（与字段缺失同义）")
        ok(req("""{"input":"a"}""").first != null,
            "缺 dimensions -> 不影响")

        // 数组元素类型错
        ok(req("""{"input":[1,"a"]}""").first == null,
            "id 与文本混排 -> 拒绝（无法确定哪段 id 对应哪条输入）")
    }

    // ── token id 数组（0.9.170 新增）──────────────────────────────────────
    private fun testTokenIds() {
        // 扁平 id 数组 = **一条**输入
        val (a, ea) = req("""{"input":[1212,318,257,1332]}""")
        ok(a != null && ea == null && a.inputs.size == 1 && !a.batch,
            "扁平 id 数组 -> 1 条、batch=false")
        val ids = (a?.inputs?.get(0) as? EmbeddingsApi.Item.TokenIds)?.ids
        eqStr(ids?.joinToString(","), "1212,318,257,1332", "id 原样按序保留")

        // 嵌套 = 批量
        val (b, eb) = req("""{"input":[[1,2],[3,4,5]]}""")
        ok(b != null && eb == null && b.inputs.size == 2 && b.batch,
            "嵌套 id 数组 -> 2 条、batch=true")
        eqStr((b?.inputs?.get(1) as? EmbeddingsApi.Item.TokenIds)?.ids?.joinToString(","),
            "3,4,5", "第二行的 id 原样保留")

        // 单元素数组：仍是 id 数组（一个 token 的输入）
        val (c, _) = req("""{"input":[1212]}""")
        ok((c?.inputs?.get(0) as? EmbeddingsApi.Item.TokenIds)?.ids?.size == 1,
            "[1212] -> 1 个 id 的一条输入")

        // 非法：空行 / 非整数 / 混排 / 嵌套里混数字
        ok(req("""{"input":[]}""").first != null, "input:[] -> 仍合法（空 data）")
        ok(req("""{"input":[[]]}""").first == null, "行是空 id 数组 -> 拒绝（没有可编码的语义）")
        ok(req("""{"input":[1.5,2]}""").first == null, "小数不是 token id -> 拒绝")
        ok(req("""{"input":[1,[2,3]]}""").first == null, "扁平与嵌套混排 -> 拒绝")
        ok(req("""{"input":[[1,2],3]}""").first == null, "嵌套里混数字 -> 拒绝")
        ok(req("""{"input":111}""").first == null, "裸数字（漏了方括号）-> 拒绝并提示")
        ok(req("""{"input":[null]}""").first == null, "id 是 null -> 拒绝")

        // dim/encoding 的判序：dimensions 先于 input 报（否则调用方白跑一趟）
        val (_, _, e) = EmbeddingsApi.fromRequest(
            JSONObject("""{"input":[1,"bad"],"dimensions":256}"""))
        ok(e != null && e.contains("dimensions"),
            "input 与 dimensions 同时错 -> 先报 dimensions（否则修完 input 还要再来一次）")

        // id 与文本两条路在 batch 语义上一致
        ok(req("""{"input":[[1,2]]}""").first?.batch == false,
            "单行嵌套 id 数组 -> batch=false（一条输入不是批量）")

        // ⚠ 扁平 id 数组的上限是**token 数**（MAX_INPUT_CHARS），不是"输入条数"
        //（MAX_INPUTS）：一段 300 token 的文本以 id 形式发进来必须能过，
        // 因为同样的文本以字符串发进来也能过 —— 两条路对同一文本不该有不同判决。
        val manyIds = (1..300).joinToString(",")
        val (m, em) = req("""{"input":[$manyIds]}""")
        ok(m != null && em == null && (m.inputs[0] as EmbeddingsApi.Item.TokenIds).ids.size == 300,
            "300 个 id 的扁平数组 -> 通过（上限是 token 数，不是输入条数）")
        // 但嵌套形态的外层是**输入条数**，仍受 MAX_INPUTS 约束
        val manyRows = (0..EmbeddingsApi.MAX_INPUTS).joinToString(",") { "[1,2]" }
        ok(req("""{"input":[$manyRows]}""").first == null,
            "嵌套 id 数组条数超上限 -> 400（外层是输入条数）")
    }

    // ── encoding_format=base64 的字节序列（0.9.170 新增）──────────────────
    private fun testBase64() {
        // 已知向量 -> 手算小端 float32 的 base64，逐字节比对。
        // 1.0f 的位模式 = 0x3F800000 -> 小端字节 00 00 80 3F
        val one = EmbeddingsApi.base64Of(floatArrayOf(1.0f))
        eqStr(one, "AACAPw==", "1.0f -> 小端 float32 base64（00 00 80 3F）")

        // -1.0f = 0xBF800000 -> 00 00 80 BF
        eqStr(EmbeddingsApi.base64Of(floatArrayOf(-1.0f)), "AACAvw==",
            "-1.0f -> 00 00 80 BF（符号位在高字节，证明是小端而不是大端）")

        // 0.0f -> 00 00 00 00
        eqStr(EmbeddingsApi.base64Of(floatArrayOf(0.0f)), "AAAAAA==", "0.0f -> 全零")

        // 两个数：1.0f 与 2.0f(0x40000000 -> 00 00 00 40)
        eqStr(EmbeddingsApi.base64Of(floatArrayOf(1.0f, 2.0f)), "AACAPwAAAEA=",
            "两维 -> 整段拼接后编码（不是逐维各编一段）")

        // 长度恒为 4N 字节 => base64 长度 = ceil(4N/3)*4
        val v = FloatArray(384) { it * 1e-4f }
        val b = EmbeddingsApi.base64Of(v)
        eqi(b.length, 2048, "384 维 -> 2048 字符（4*384=1536 字节，1536/3*4）")
        ok(decodeLen(b) == 384 * 4, "解回来必须是 4N 字节（长度写错就整段错位）")

        // 大端会得到不同的串 —— 正向钉住"就是小端"
        ok(EmbeddingsApi.base64Of(floatArrayOf(1.0f)) != "AD+A/w==",
            "不是大端（大端 1.0f 会是 3F 80 00 00）")

        // itemJson 的 base64 分支：embedding 是**字符串**而不是数字数组
        val item = EmbeddingsApi.itemJson(2, floatArrayOf(1.0f), EmbeddingsApi.Encoding.BASE64)
        eqStr(item, """{"object":"embedding","index":2,"embedding":"AACAPw=="}""",
            "base64 项：embedding 是字符串、index 原样")
        val parsed = JSONObject(item)
        ok(parsed.opt("embedding") is String, "base64 项解析后 embedding 是字符串")
        eqi(parsed.optInt("index"), 2, "base64 项 index 原样")

        // float 分支不受影响
        val f = EmbeddingsApi.itemJson(0, floatArrayOf(0.5f))
        ok(JSONObject(f).opt("embedding") is JSONArray, "float 项仍是数字数组")
    }

    /** base64 串解回字节数（用标准码表手算，不引第三方）。 */
    private fun decodeLen(s: String): Int {
        val pad = s.count { it == '=' }
        return (s.length / 4) * 3 - pad
    }

    // ── itemJson：index 与数值 ─────────────────────────────────────────────
    private fun testItem() {
        val s = EmbeddingsApi.itemJson(3, floatArrayOf(0.5f, -0.25f))
        eqStr(s, """{"object":"embedding","index":3,"embedding":[0.5,-0.25]}""",
            "itemJson：index 原样、数值最短往返")

        // 小分量不能被压成 0（%f 的病）：归一化后的分量本就小
        val tiny = EmbeddingsApi.itemJson(0, floatArrayOf(1e-30f))
        ok(tiny.contains("1.0E-30") || tiny.contains("1e-30") || tiny.contains("E-30"),
            "极小分量不被 %f 压成 0（那会悄悄改向量方向）：$tiny")

        // 非有限值不能污染 JSON（NaN 不是合法 JSON）
        val nan = EmbeddingsApi.itemJson(0, floatArrayOf(Float.NaN))
        ok(nan.contains("null") && !nan.contains("NaN"),
            "NaN 折成 null（拼成 NaN 会让整份响应非法）")
        JSONObject("""{"d":[$nan]}""")   // 可解析才通过（不抛即 OK）
        ok(true, "NaN 折成的项仍是合法 JSON")

        // index 必须由调用方传入、原样出现
        ok(EmbeddingsApi.itemJson(7, floatArrayOf(1f)).contains("\"index\":7"),
            "index 原样（不许按数组位置重算 —— 错一位就是文本与向量错位）")
    }

    // ── errorItemJson / responseJson ───────────────────────────────────────
    private fun testResponse() {
        val err = EmbeddingsApi.errorItemJson(2, "这两条失败 说明")
        ok(err.contains("\"index\":2") && err.contains("\"embedding\":null"),
            "失败项：index 对齐 + embedding=null")
        val o = JSONObject(err)
        ok(o.opt("embedding") == null || o.isNull("embedding"),
            "失败项 embedding 是 null（不是空数组 —— 空数组会被当成『零维向量』）")

        val body = EmbeddingsApi.responseJson("bge\n\"x\"", listOf(err))
        val parsed = JSONObject(body)
        eqStr(parsed.optString("object"), "list", "响应 object=list")
        eqi(parsed.optJSONArray("data")?.length() ?: -1, 1, "data 长度 = 项数")
        eqStr(parsed.optString("model"), "bge\n\"x\"", "model 原样回显（含特殊字符也不破坏 JSON）")

        // 空 data 也必须是合法 JSON（input:[] 的路径）
        val empty = JSONObject(EmbeddingsApi.responseJson("m", emptyList()))
        eqi(empty.optJSONArray("data")?.length() ?: -1, 0, "空 input -> 空 data（合法）")
    }

    fun run(): Int {
        testParse()
        testReject()
        testTokenIds()
        testBase64()
        testItem()
        testResponse()
        println()
        println("EmbeddingsApiTest: PASS $pass / FAIL $fail")
        return if (fail == 0) 0 else 1
    }
}

/** 两个测试对象共用一个入口（CI 只启动一次 JVM）。 */
fun main() {
    val a = EmbeddingsApiTest.run()
    val b = RagProtocolTest.run()
    kotlin.system.exitProcess(if (a == 0 && b == 0) 0 else 1)
}
