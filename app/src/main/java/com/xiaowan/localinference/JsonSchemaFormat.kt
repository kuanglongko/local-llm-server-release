package com.xiaowan.localinference

import org.json.JSONObject

/**
 * OpenAI `response_format`（结构化输出）的**纯逻辑**部分：请求侧解析 + 校验。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 这个特性为什么必须"默认行为逐字节不变"
 * ══════════════════════════════════════════════════════════════════════════
 * 它是**新增的可选字段**：不带 `response_format` 的请求必须与引入本特性之前
 * 完全同行为（同一采样链、同一 prompt、同一输出）。所以这里的判据只有一条：
 * **字段不存在 => 返回 [None]，不产生任何 native 调用**。任何"顺手给个默认
 * 格式"的写法都会改变所有既有客户端的输出分布，属于最严重的一类回归。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 什么算"非法"（400），什么是"库的能力边界"（接受但降级 + 落日志）
 * ══════════════════════════════════════════════════════════════════════════
 * 只对**协议级错误**报 400 —— 这些无论谁来用都是错的，报错能帮调用方立刻定位：
 *   · `response_format` 不是对象（`"json"`、`[..]`、数字）；
 *   · `type` 不是字符串 / 空串；
 *   · `type` 是字符串但不是已知取值（拼错 `json_shema` 必须报错，**绝不能**
 *     静默当成"没给" —— 那会让调用方以为约束生效了，拿到自由文本却找不出原因）；
 *   · `type == "json_schema"` 但缺 `json_schema` 对象，或里面缺 `schema`；
 *   · `schema` 不是 JSON 对象（数组 / 字符串 / 数字）。
 *
 * ⚠️ 曾经还有一条「`schema` 里出现已知不被支持的键」——**已删除**，理由见本文件
 * 末尾那段「这条判据故意不存在」：
 * 那条判据拦的是**合法**请求（JSON Schema 的 `type: ["string","null"]`），把"能力边界"
 * 错当成了"协议级错误"。
 *
 * 而**库的能力边界**（schema 转换覆盖不到、GBNF 编译失败）一律**不失败请求**：
 * 走 200 + 降级成无约束采样 + 落一行日志。理由与 [StopSequences.MAX_SEQUENCES]
 * 那段完全一致 —— "补齐一个协议字段"不该把本来能用的请求弄挂；而且降级是
 * **静默**的（输出仍是合法文本，只是没被约束住），比报错更需要日志留痕。
 *
 * 这条分界线的**判据是"库真的抛错了吗"，不是"我猜库支持吗"**：
 * 曾经在这里硬编码了"`type` 是数组 => 库不支持 => 400"，而实测（真机日志
 * `content = "<|im_start|>assistant\n[ ]"`）证明库**能**接受并转出 GBNF ——
 * 那条判据拦掉的是合法请求，而它拦不到的那个问题（生成后缀没对齐、模型自己把尾巴
 * 吐一遍）在别处（见 [ResponseFormat.GenerationPrompt]）。
 *
 * 判据与理由都收敛在本文件，HttpApi 只做取用（这是本项目的既有做法：
 * `SamplingParams` / `StopSequences` / `CorsPolicy` / `ApiAuth` 都是这个形状）。
 */

/**
 * 结构化输出的解析结果。
 *
 * 用 sealed class 而不是 `String?`（schema 的 JSON 文本）是为了把
 * **"没要求格式"** 与 **"要求了但 schema 为空"** 在类型上分开：
 * 前者绝不能产生任何 native 调用，后者必须是 400。用同一个 null 表示两者，
 * 就会写出"少判一处、把 400 变成静默放行"的代码 —— 而那恰好是最难发现的一类。
 */
sealed interface ResponseFormat {
    /** 请求没带 `response_format`（或带 `null`）——**与引入本特性前逐字节同行为**。 */
    object None : ResponseFormat

    /** 要求 JSON 对象，但调用方没给结构（随时可无损收窄成 [JsonSchema]）。 */
    object JsonObject : ResponseFormat

    /** 要求按给定 JSON Schema 输出；[schema] 是**原文** JSON（不重新序列化，见 [JsonSchemaFormat.fromRequest]）。 */
    data class JsonSchema(val schema: String, val name: String?, val strict: Boolean) : ResponseFormat

    /**
     * 派生值：这一轮**实际渲染出来的**生成后缀（prompt 里模型要接着续写的那段尾巴）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么它必须一路传到 native（它是一次真机故障的根因）
     * ══════════════════════════════════════════════════════════════════════
     * GBNF 约束的是「生成后缀**之后**」的输出，而库推导 GBNF 时把生成后缀当作
     * "已经咽下去的前缀"。原先那一跳写死 `add_generation_prompt = true` —— 于是当
     * 渲染侧给出的是**另一段**尾巴（如模板在 `enable_thinking=false` 下吐的
     * `<|im_start|>assistant\n<think>\n\n</think>\n\n`，或渲染失败回落的宿主 ChatML）
     * 时，约束的起点与模型实际续写的位置**不是同一处**。模型于是自己先把那个尾巴
     * 补一遍，才轮到满足 grammar —— 真机（MiniCPM5-2B-Q4_K_M + json_schema）两例：
     *
     *     content = ":assistant\n{ ... }"          ← 头部被截（客户端丢掉 <|im_start| 一段）
     *     content = "<|im_start|>assistant\n[ ]"    ← 完整形态：生成后缀被模型自己吐了出来
     *
     * 第二例的 `[ ]` 不是"模型不听话"，而是它把 token 预算花在了那个尾巴上，
     * 之后 JSON 数组只要最短的合法形态就停住了。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 三态（与 [schemaArg] 同一种做法：判据只有一处，不在调用点另判）
     * ══════════════════════════════════════════════════════════════════════
     * · [None] / [GenerationPrompt] 缺失 -> `null`：**没有 / 不知道**，native 退回旧口径
     *   （"这一轮有生成后缀"）。它必须与"确认没有生成后缀"区分开 —— 后者是**空串**。
     * · [GenerationPrompt.EMPTY] -> `""`：调用方明确说这一轮没有生成后缀
     *   （渲染时 `add_generation_prompt=false`）。
     * · [GenerationPrompt.TEXT] -> 原文：与渲染侧同一段尾巴。
     *
     * 若把"没有生成后缀"折成 `null`，就会给一个没有后缀的 prompt 施加"后缀之后"的约束 ——
     * 与这次要修的故障**同一个成因，方向相反**。所以这里与 `json_object` 那条一样，
     * 用类型把"空"与"未知"分开。
     */
    sealed interface GenerationPrompt {
        /** 没有生成后缀（渲染时 add_generation_prompt=false）——**空串不是 null**。 */
        object EMPTY : GenerationPrompt

        /** 生成后缀原文（渲染侧给出，与 prompt 的结尾逐字节一致）。 */
        data class TEXT(val text: String) : GenerationPrompt
    }
}

object JsonSchemaFormat {

    /**
     * `json_schema.schema` 的体积上限（字节）。
     *
     * **拒绝**而不是截断：截断一个 JSON 结构等于喂给转换器一个语法不完整的
     * 文档，那不是"少约束一点"，而是另一个（错的）schema —— 输出会按错的约束被
     * 限制住，且接口 200。截断在这里没有任何合理语义。
     *
     * 64 KiB 远高于任何真实的结构化输出 schema（典型几百字节到几 KB）。
     */
    const val MAX_SCHEMA_BYTES = 64 * 1024

    const val errNotObject = "response_format 必须是对象，如 {\"type\":\"json_object\"}"
    const val errTypeMissing = "response_format.type 必须是字符串（json_object / json_schema / text）"
    const val errTypeUnknown =
        "response_format.type 只支持 json_object / json_schema / text（拼写错误不会被静默忽略）"
    const val errWrapperMissing =
        "response_format.type=json_schema 时必须给出 json_schema 对象（含 schema 字段）"
    const val errSchemaMissing =
        "response_format.json_schema.schema 必填，且必须是 JSON 对象"
    const val errSchemaNotObject = "response_format.json_schema.schema 必须是 JSON 对象"
    const val errSchemaTooLarge = "response_format.json_schema.schema 过大（上限 $MAX_SCHEMA_BYTES 字节）"

    /** 落日志用的**未知取值**提示（不参与 400 —— 见 [fromRequest] 里 `text` 的说明）。 */
    fun unknownButTolerated(type: String) =
        "response_format.type=\"$type\" 不被识别，按无约束处理（不会失败请求；json_object / json_schema 会被识别）"

    /**
     * 解析请求里的 `response_format`。返回 (结果, 错误) 二选一：结果为 null 表示非法。
     *
     * 一个刻意的保守选择：**不重新序列化 schema**，直接把调用方给的那段 JSON
     * 原文（`JSONObject.toString()`）交给 native。原因是我们只把原文当
     * **不透明字符串**传给库，自己不做任何解析/改写；自己解析一遍再序列化，
     * 只会引入"转义 / 顺序 / 精度"三处可能失真的地方，而它们全都静默。
     * 我们只用 `org.json` 做**形状校验**（是不是对象、有没有那个键）。
     */
    fun fromRequest(j: JSONObject): Pair<ResponseFormat?, String?> {
        if (!j.has("response_format") || j.isNull("response_format")) return ResponseFormat.None to null

        val rf = j.opt("response_format")
        if (rf !is JSONObject) return null to errNotObject

        // `type` 缺失 => 让库按它自己的默认（json_schema 时已知会转成 JSON 对象约束）。
        // 这是**宽容**的一处：OpenAI 规范要求 type 必填，但历史上确实有客户端只给
        // json_schema 对象而不给 type；此时报 400 会拒掉一个本来能正确工作的请求。
        if (!rf.has("type") || rf.isNull("type")) {
            return if (rf.has("json_schema")) parseJsonSchema(rf) else ResponseFormat.JsonObject to null
        }

        val type = rf.opt("type")
        if (type !is String) return null to errTypeMissing
        return when (type) {
            "text" -> ResponseFormat.None to null
            "json_object" -> ResponseFormat.JsonObject to null
            "json_schema" -> parseJsonSchema(rf)
            else -> null to errTypeUnknown
        }
    }

    /**
     * `type == "json_schema"` 分支：校验包装对象与 schema 本体。
     *
     * `name` / `strict` 只做**形状**校验（非字符串即 400），不做语义推断 ——
     * 它们不影响 grammar 的形状，只影响日志（见 [describe]）。
     * 不把 `strict:false` 当"不约束"：OpenAI 的语义里它只是"服务端尽力而为"，
     * 直接不约束会让调用方拿到自由文本，比"约束了但可能不完美"更糟。
     */
    private fun parseJsonSchema(rf: JSONObject): Pair<ResponseFormat?, String?> {
        val wrapper = rf.opt("json_schema")
        if (wrapper !is JSONObject) return null to errWrapperMissing

        val name = wrapper.opt("name")?.let {
            if (it !is String) return null to "response_format.json_schema.name 必须是字符串"
            it
        }
        val strict = wrapper.opt("strict")?.let {
            if (it !is Boolean) return null to "response_format.json_schema.strict 必须是布尔值"
            it
        } ?: false

        if (!wrapper.has("schema") || wrapper.isNull("schema")) return null to errSchemaMissing
        val schema = wrapper.opt("schema")
        if (schema !is JSONObject) return null to errSchemaNotObject

        val text = schema.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_SCHEMA_BYTES) return null to errSchemaTooLarge

        return ResponseFormat.JsonSchema(text, name, strict) to null
    }

    /**
     * 这条判据**故意不存在**（曾经存在过，是本文件里唯一一次把合法请求判成 400）。
     *
     * 旧版本在这里硬编码了一条「`schema.type` 是数组 => 库不支持 => 400」，依据是
     * 库里的两条消息（`type must be array, but is ...` / `Unrecognized schema: `）。
     * 现在**删除**，两条理由：
     *
     * ① **它是错的**：`type: ["string","null"]` 是标准 JSON Schema 的联合类型，
     *    真机实测（MiniCPM5-2B-Q4_K_M，`{"type":"array","items":{"type":"string"}}`
     *    与对象 schema）库能接受并转出 GBNF、约束也确实生效了 —— 那条判据拦的是
     *    合法请求。这与 [fromRequest] 文件头的分界一致：**只有协议级错误才 400**，
     *    "库支持不支持"属于能力边界，应走 200 + 降级（native 侧就地 catch + 留痕），
     *    而不是由 Kotlin 侧替库做决定。
     * ② **它拦不到真正的问题**：实测里"数组 schema 出了个空数组 `[ ]`"，根因不是
     *    schema 形状，而是**生成后缀没与 grammar 对齐**（模型先把生成后缀吐了一遍，
     *    token 预算花光，JSON 只能给最短合法形态）。见
     *    [ResponseFormat.GenerationPrompt]。把这条判据留着，反而会让人以为
     *    "数组 schema 被我们拒了/不支持"，把排查方向带偏。
     *
     * 真要对 schema 的形状设限，判据只能是"库真的抛错了吗" —— 而那个错误发生在
     * native 侧，可以就地 catch、留日志、降级，不需要在这里猜。
     */
    @Suppress("unused")
    private const val ARRAY_TYPE_IS_NOT_REJECTED = true

    /**
     * 把 [ResponseFormat] 折成给 native 的入参：schema 原文，或 null 表示不约束。
     *
     * [ResponseFormat.JsonObject] 返回 `""`（**空串不是 null**）：
     * 两者在 native 侧的含义不同 —— null = 压根不要 grammar 采样器（与引入本特性
     * 前逐字节相同），"" = 要一个 json 采样器但由模板自己给 grammar。
     * 把 JsonObject 也折成 null 会让 `json_object` 这个类型静默失效。
     */
    fun schemaArg(f: ResponseFormat): String? = when (f) {
        ResponseFormat.None -> null
        ResponseFormat.JsonObject -> ""
        is ResponseFormat.JsonSchema -> f.schema
    }

    /**
     * 把 [ResponseFormat] 与生成后缀折成给 native 的入参：null = 没有 / 不知道（旧口径），
     * 空串 = 确认没有生成后缀，其余 = 原文。见 [ResponseFormat.GenerationPrompt]。
     *
     * [ResponseFormat.None] 时同样返回 null：这一轮不需要 grammar，值不会被用到；
     * 但**不得**在这里返回空串或原文 —— 那会让"没要求结构化输出"的请求也带上一段
     * 生成后缀信息，等于给默认路径新增了一个 native 入参（本特性的第一条硬判据是
     * 默认路径逐字节不变）。
     */
    fun genPromptArg(f: ResponseFormat, gp: ResponseFormat.GenerationPrompt?): String? {
        if (f is ResponseFormat.None) return null
        return when (gp) {
            null -> null
            ResponseFormat.GenerationPrompt.EMPTY -> ""
            is ResponseFormat.GenerationPrompt.TEXT -> gp.text
        }
    }

    /**
     * [genPromptArg] 结果的日志摘要（只报长度与形状，不整段回显）—— 传**折好的那个值**，
     * 不另接 [ResponseFormat.GenerationPrompt]：日志要与真正下发的入参同源，
     * 否则出现"日志说未知、实际传了原文"这种没人能立刻看出的错。
     */
    fun describeGenPrompt(gp: String?): String = when (gp) {
        null -> "生成后缀=未知（native 按旧口径：有生成后缀）"
        "" -> "生成后缀=空（这一轮没有生成后缀）"
        else -> "生成后缀=${gp.toByteArray(Charsets.UTF_8).size}B"
    }

    /**
     * 响应体的探针行（**只落日志，不参与任何控制流**）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须有这一行（这是被同一类缺口绊住的第 N 次）
     * ══════════════════════════════════════════════════════════════════════
     * 结构化输出这条链修了八轮，前八轮的日志里**只有 `chat ok: n tok` 这个计数**，
     * 响应体一个字都没落盘。于是每次复测都只能靠"用户说还是有代码块"这个结论反推，
     * 而 `[ ]`（空数组）与 `["a","b","c"]` 恰好都是 13 tok —— **计数分不开它们**，
     * 这正是第九轮还在原地的最直接原因。
     *
     * 所以这里把「模型真正吐出来了什么」落成一行可 grep 的读数：
     *   · [bytes]      —— UTF-8 字节数（与 prompt 侧日志的口径一致）；
     *   · [head]       —— 前 [HEAD] 个字符的**转义原文**（换行 \n / 引号都是可见字符）；
     *   · [json]       —— 剥掉 Markdown 围栏后能不能 `JSON.parse`（这才是客户端真正的判据）；
     *   · [array]      —— 顶层是数组时的元素个数（`-1` = 不是数组 / 解析不出来）。
     *
     * ⚠ 只报**首尾**两小段，不整段回显：对话日志是要给人看的，而且这段文本可能很长。
     *   `[head]` 回答"前面有没有多出 `<think>` / 围栏"，`[tail]` 回答"后面有没有多出
     *   一段没闭合的尾巴"—— 两个方向都要能看见，故障形态刚好一个在前一个在后。
     */
    const val PROBE_HEAD = 80
    const val PROBE_TAIL = 40

    /** 内部可见（同包单测直接调），纯函数：不读全局、不写日志。 */
    fun probeBody(content: String): String = probeBody("", content)

    /**
     * 带**请求关联码**的响应体探针行（[reqTag] 由调用方给，空串 = 不打关联段）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须有关联码（这是本轮真机日志暴露的、此前没人看见的缺口）
     * ══════════════════════════════════════════════════════════════════════
     * 本轮用户给的 `[probe]` 里，请求侧那三行是：
     *
     *     [probe] [http] response_format=json_schema(name=test_object, ... 111B, ...)
     *     [probe] [http] response_format=json_schema(name=test_boolean, ... 84B, ...)
     *     [probe] [http] response_format=json_schema(name=test_array, ... 42B, ...)
     *
     * 它们**逐行可读，却一行都对不上** —— 无请求 id、无时间戳顺序可依，三例长得一模一样，
     * 只有 `name=` 不同。而 `[body]` 那行也没有 id：一次会话里三例连发，`[body]` 与
     * 请求侧那三行的**配对只能靠"数出来的顺序"**，一旦并发、重试或把两组日志并排看，
     * 配对就断了。这是"读数有了、结论仍要猜"的最后一道缝。
     *
     * 所以本行在**头部**加一个短关联码（与 OpenAI 响应的 `id` 同源，见 HttpApi 里
     * `chatcmpl-local-*` 的生成处）：`[body id=...] bytes=...`。请求侧那几行也带同一个码，
     * 于是"这一例请求要了什么"与"这一例吐出来什么"能**按 id 对齐**，不必再数顺序。
     *
     * ⚠ 关联码只进日志，**不进响应体**（不带 `id` 段落时输出与 `0.9.94` 逐字节相同）。
     */
    fun probeBody(reqTag: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8).size
        val head = content.take(PROBE_HEAD).escapeProbe()
        val tail = if (content.length > PROBE_HEAD + PROBE_TAIL)
            content.takeLast(PROBE_TAIL).escapeProbe() else ""
        val stripped = stripCodeFence(content)
        val (ok, kind, elems) = inspectJson(stripped)
        return buildString {
            append("[body")
            // 关联码不含空格/引号（由调用方保证，见 HttpApi 的 reqTagOf），直接进方括号。
            if (reqTag.isNotEmpty()) append(" id=").append(reqTag)
            append("] bytes=").append(bytes)
            append(" chars=").append(content.length)
            append(" head=\"").append(head).append('"')
            if (tail.isNotEmpty()) append(" tail=\"").append(tail).append('"')
            append(" json=").append(if (ok) "ok($kind)" else "fail")
            append(" strip=").append(if (stripped !== content) "有围栏已剥" else "无")
            append(" array=").append(elems)
        }
    }

    /**
     * 从响应 id 里取**短关联码**（只取 id 的数字尾巴），空串表示无可关联信息。
     *
     * 为什么要砍短：OpenAI 的 `id` 是 `chatcmpl-local-1789992569096` 这种形状，一行的
     * 关联信息里重复那截前缀只是噪音，而多例连发时**真正能区分彼此的只有数字尾巴**。
     * 取不到数字（自定义 id）时返回整个 id —— 宁可长一点，也不能让关联码变成空。
     */
    fun reqTagOf(id: String?): String {
        if (id.isNullOrEmpty()) return ""
        val digits = id.takeLastWhile { it.isDigit() }
        return if (digits.isNotEmpty()) digits else id.takeLast(16)
    }

    /**
     * 剥离 Markdown 代码围栏（```json ... ``` / ``` ... ```）。
     *
     * 这不是"帮客户端容错"——落日志时**不剥**才是错的：`json=fail` 会把"模型吐了围栏"
     * 与"模型吐了彻底非法的东西"混成一条读数，而这两者的修法完全不同
     * （前者是生成后缀没对齐、后者是 grammar 压根没生效）。
     * 返回**同一个实例**表示没剥（调用方据此区分"本来就没有围栏"）。
     */
    fun stripCodeFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return s
        val nl = t.indexOf('\n')
        if (nl < 0) return s
        val body = t.substring(nl + 1)
        val end = body.lastIndexOf("```")
        if (end < 0) return s
        return body.substring(0, end).trim()
    }

    /**
     * 下发前的**围栏剥离**：只在「调用方要求了结构化输出」且「剥出来的东西**确实是 JSON**」
     * 时才把围栏去掉，否则**原样返回**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么这不是"帮客户端容错"，而是"服务端没做的那一步"
     * ══════════════════════════════════════════════════════════════════════
     * 真机读数（`0.9.101`，Qwen3-0.6B-Q8_0 + `response_format=json_schema`）里，
     * 三例的 content 是：
     *
     *     {"name": "OpenAI", "title": "CEO: Sam Altman"}          ← 干净
     *     ```json\n[\n  "pandas", …\n]\n```                       ← 被围栏包住
     *     {"is_even": true}                                        ← 干净
     *
     * 那三层反引号**不是 grammar 产的**（库内 `space ::= | " " | "\n"{1,2} [ \t]{0,20}`
     * 吃不下反引号），是模型在 grammar 够不到的地方自由写出来的。于是调用方拿着
     * 一份"语法合法、但 `json.loads()` 直接抛"的 200 响应 —— 而这恰恰是
     * `response_format` 承诺过要避免的东西。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 三条硬约束（写错任何一条都比不剥更糟）
     * ══════════════════════════════════════════════════════════════════════
     * ① **只在要求了结构化输出时才动**：[ResponseFormat.None] 的请求原样下发。
     *    否则会把一段**本身就**以围栏开头的普通文本（如模型在教 markdown）改掉，
     *    那是"服务端改了模型输出"里最不该发生的一类。
     * ② **剥了必须还是 JSON**：剥完仍解析不出来就退回原件。宁可让调用方看见围栏
     *    （他能自己剥），也不能把一个残段发出去 —— 那会让"模型吐了半截"看起来
     *    像"服务端啃坏了"。判据用 [inspectJson]，与探针读数**同一份实现**。
     * ③ **不碰 grammar、不碰生成路径一个字节**：这里只改**下发的字节**，
     *    `strip===content` 时字节与改动前**逐字节相同**。
     *
     * 返回**同一个实例**表示没剥（调用方据此区分"本来就没围栏"与"剥了"）。
     */
    fun stripFenceForDelivery(f: ResponseFormat, content: String): String {
        if (f is ResponseFormat.None) return content
        val stripped = stripCodeFence(content)
        if (stripped === content) return content
        // 判据②：剥出来的必须**自己**能解析。用 inspectJson（探针同一份实现），
        // 免得"探针说能解析、下发却不能"这种两把尺子。
        if (!inspectJson(stripped).first) return content
        return stripped
    }

    /**
     * [stripFenceForDelivery] 的伴随读数：剥了 / 没剥 / 退回了原件。
     *
     * 落日志而不只是"静默剥掉"：这是**服务端改了模型输出**的动作，
     * 必须留痕，否则下一个读日志的人会以为模型本来就吐得干净。
     */
    fun describeDeliveryStrip(f: ResponseFormat, content: String): String {
        if (f is ResponseFormat.None) return "围栏剥离：未要求结构化输出，原样下发"
        val stripped = stripCodeFence(content)
        if (stripped === content) return "围栏剥离：无围栏，原样下发（${content.toByteArray(Charsets.UTF_8).size}B）"
        if (!inspectJson(stripped).first)
            return "围栏剥离：有围栏但剥后非 JSON，**退回原件**（${content.toByteArray(Charsets.UTF_8).size}B）"
        return "围栏剥离：已剥（${content.toByteArray(Charsets.UTF_8).size}B -> " +
            "${stripped.toByteArray(Charsets.UTF_8).size}B）"
    }

    /** (能否解析, 顶层类型名, 数组元素个数；-1 表示不是数组) —— 纯读数，不做任何校验。 */
    private fun inspectJson(s: String): Triple<Boolean, String, Int> {
        val t = s.trim()
        if (t.isEmpty()) return Triple(false, "-", -1)
        // 数组用 JSONArray 单独判：org.json 的 JSONObject 解析不了顶层数组，
        // 而"数组 schema 出了代码块"恰恰是这一轮要盯的那一例。
        if (t.startsWith("[")) {
            return try {
                val a = org.json.JSONArray(t)
                Triple(true, "array", a.length())
            } catch (_: Throwable) { Triple(false, "-", -1) }
        }
        return try {
            val o = JSONObject(t)
            Triple(true, "object", -1).also { _ -> o.length() }
        } catch (_: Throwable) { Triple(false, "-", -1) }
    }

    /**
     * 转义成一行的可读原文：换行 / 回车 / 制表 / 双引号都转成可见形式。
     *
     * 为什么必须转义（这是本条探针"能不能读"的关键）：响应体里的换行会被日志文件
     * 按行拆开，一行 `[body]` 就会被撕成好几行，grep `[body]` 只能捞到第一段；
     * 而 `<|im_start|>assistant\n` 与 `\n\n</think>\n\n` 的**差别就在换行上** ——
     * 转义之后一眼可读，不转义则等于没打。
     */
    private fun String.escapeProbe(): String = buildString {
        for (c in this@escapeProbe) when (c) {
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /** 落日志用的一行摘要（schema 只报长度，不整段进日志 —— 对话日志是要给人看的）。 */
    fun describe(f: ResponseFormat): String = when (f) {
        ResponseFormat.None -> "response_format=未指定（无约束）"
        ResponseFormat.JsonObject -> "response_format=json_object"
        is ResponseFormat.JsonSchema ->
            "response_format=json_schema(name=${f.name ?: "-"}, strict=${f.strict}, " +
                "schema=${f.schema.toByteArray(Charsets.UTF_8).size}B, 原文交给 native 转 GBNF)"
    }
}
