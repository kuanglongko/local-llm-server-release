package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI `POST /v1/embeddings` 的**纯逻辑**部分：请求解析 + 校验 + 响应拼装。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ⚠ 这个端点与"知识库开关"**完全解耦**（这是刻意的，不是巧合）
 * ══════════════════════════════════════════════════════════════════════════
 * 它**不读**任何 RAG 开关、不读远端库名、不因为"知识库注入关着"而被拒绝。
 * 理由：它的语义是"逐条算向量"，是一个**无状态**的纯函数式接口 —— 调用方走它
 * 意味着**它自己**负责分块、向量库、检索、拼 prompt，服务端只提供那个"向量计算器"。
 * 而服务端内部的 RAG 走的是另一条完全不同的路（[RagInject] / [LlmEngine.ragContext]）。
 *
 * 两条路唯一的共同点是"都用得到嵌入模型"。这一点很容易被做成耦合
 *（"反正都要嵌入模型，那开关关了就别给了"），而那样做的后果是：
 *   · 用户为了 App 内聊天不被注入而关掉知识库开关，
 *     结果**远端客户端的 embeddings 也跟着 503** —— 两件毫不相干的事被绑在一起；
 *   · 而报出来的错还是"知识库未启用"，与真实原因（开关）差着十万八千里。
 * 所以本端点**只**判一件事：嵌入模型在不在。缺了就 503 + 明文原因，绝不静默。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 OpenAI 的**有意不一致**（不写清楚就是静默错）
 * ══════════════════════════════════════════════════════════════════════════
 * `embedding` 里的向量**已做 L2 归一化**。这不是"顺手做的优化"，而是必须：
 * 服务端内部检索用余弦相似度（= 归一化后的点积，见 `embed_text.h`），
 * 索引里存的就是归一化向量。若这里回未归一化的原向量，调用方拿它算出来的余弦
 * 与"同一模型在服务端内部检索"的结果**对不上** —— 而两者都合法、都不报错。
 * OpenAI 自己也归一化（`text-embedding-3-*` 回的就是单位向量），所以这一条实践上
 * 是*一致*的；写在这里是因为**文档必须说清**，否则调用方会自己再归一化一次
 *（对单位向量再归一化是无害的，但会让"我到底该不该归"变成猜）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * `dimensions`：**明确 400**，不静默忽略（0.9.170 新增）
 * ══════════════════════════════════════════════════════════════════════════
 * OpenAI 的 `dimensions` 表示"让输出向量降到我指定的维度"（`text-embedding-3-*`
 * 靠重训出来的 MRL 结构做截断）。本地这份嵌入模型是 BGE-small-zh-v1.5，
 * **没有** MRL —— 直接截前 N 维出来的向量**语义是坏的**（它不再是那个模型的
 * 语义空间里的点，检索结果退化成噪声），而它和"模型本来就没训过降维"在接口上
 * 长得一模一样：200、维度合法、向量看起来正常。
 *
 * 所以这里**明确 400**，而不是静默忽略该字段。静默忽略的后果不是"没生效"，
 * 是：调用方设了 `dimensions:512`、拿到 200、拿到一段 1024 维向量，然后按它的
 * 期望去截或去用 —— 静默错位、无任何报错。这与本仓库 `ProtocolFields` 开头
 * 那条准则同源：**缺失/未实现的字段被静默忽略，是协议残缺，不是"没做这个特性"**。
 *
 * 判据落在 `tools/rag_remote/EmbeddingsApiTest.kt`：`dimensions` 出现即 400，
 * 且**这一条先于其它字段判**（见 [fromRequest] 里的顺序说明）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * `encoding_format`：`float`（默认）与 `base64` 都支持（0.9.170 新增）
 * ══════════════════════════════════════════════════════════════════════════
 * 此前只接受 `float`、`base64` 明确 400（理由见 git 历史）。用户要求支持之后，
 * 实现口径必须与 OpenAI 一致，否则调用方按 base64 去解一段 JSON 数字数组会
 * 直接崩在它那边：
 *   · 每维 **4 字节小端 IEEE-754 float32**（OpenAI 就是这个，见其文档
 *     "base64-encoded float32"；**大端是错的**，字节序写反后数值量级离谱，
 *     但 base64 解码成功、长度也对 —— 典型的静默错）；
 *   · 整条向量拼成一段字节后 base64（**不是**逐维 base64）；
 *   · 响应里 `embedding` 的位置放**字符串**而不是数字数组。
 * 三条都得写死在文档里，因为任何一条写反都不会报错。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * `input` 的 token id 数组：**支持**（0.9.170 新增）
 * ══════════════════════════════════════════════════════════════════════════
 * 此前明确拒绝（理由：不同模型的 token 语义不同）。现在支持了，做法只能是
 * **诚实**的一条：id 用**嵌入模型自己的 vocab** 还原成文本，再走与字符串输入
 * **完全相同**的前向路径。这样"id 数组"与"字符串"两条输入最终共用同一条编码
 * 路径，不会出现"两条路各算一遍、结果却不一样"的分岔。
 *
 * 越界的 id（不属于本 vocab）**整条 400**，不 clamp、不跳过 —— 跳过会让向量
 * 对应一段调用方没给过的文本，而接口 200。**静默换输入**正是当初拒绝它的理由，
 * 支持之后这条理由变成了"必须校验越界"。
 *
 * `int[][]`（批量 token 数组）同样支持；空 id 数组与空串同一处置（400），
 * 因为"空输入没有可编码的语义"这条判据与它是文本还是 id 无关。
 */
object EmbeddingsApi {

    /**
     * 单次请求的 `input` 条数上限（**性能边界**，超出报 400）。
     *
     * 与 `StopSequences.MAX_SEQUENCES` 的"截断告警"不同，这里**报 400**：
     * 嵌入是逐个前向计算的，条数直接决定这一条 HTTP 的耗时（手机上一次前向
     * 几十毫秒）。客户端一次发 10000 条时，它会超时、重试、并发进来更多 ——
     * 而服务是单并发的。截断（只算前 N 条）在这里尤其坏：调用方会拿到一个
     * **长度对不上**的 data 数组，而它按 index 对齐入库，于是向量与文本整体错位。
     * 所以条数超限明确拒绝，让调用方自己分批。
     */
    const val MAX_INPUTS = 256

    /** 单条文本的字符数上限（性能边界，超出报 400，理由同上：截断会造成错位）。 */
    const val MAX_INPUT_CHARS = 8192

    /**
     * `input` 的一条 —— 文本或预 tokenize 的 id 数组。
     *
     * 两种形态**在这一层就分开**（而不是"统一成文本"）：因为 id 数组要经
     * `LlmEngine.detokenize` 才能变成文本，而那一层依赖 native 的 vocab，
     * 纯逻辑层不该碰。分开之后 [fromRequest] 仍然是可离线单测的纯函数，
     * 而"哪一条是 id"这件事在解析期就定死，调用方没有"猜"的余地。
     */
    sealed class Item {
        /** `"input": "一句话"` 或数组里的字符串元素。 */
        data class Text(val text: String) : Item()

        /** `"input": [1,2,3]` 或 `int[][]` 里的一行。非空（空数组在解析期已拒）。 */
        data class TokenIds(val ids: IntArray) : Item()
    }

    /** 解析结果：要么是要编码的条目列表（顺序即 data[].index），要么是一句错误说明。 */
    data class Request(val inputs: List<Item>) {
        /** OpenAI 的 `input` 允许 string | string[] | int[] | int[][]。 */
        val batch: Boolean get() = inputs.size != 1
    }

    /** 输出编码格式（OpenAI `encoding_format`）。 */
    enum class Encoding { FLOAT, BASE64 }

    /** 解析结果里除 `input` 之外还要带回调用方的字段。 */
    data class Options(val encoding: Encoding)

    /**
     * 解析并校验请求体。
     *
     * `input` 的合法形态（OpenAI 规范里的四种 + 一种"空"）：
     *   · `"input": "一句话"`        —— 单条文本；
     *   · `"input": ["a", "b"]`      —— 多条文本；
     *   · `"input": [1, 2, 3]`       —— 单条文本的**预 tokenize id**；
     *   · `"input": [[1,2], [3,4]]`  —— 多条文本的预 tokenize id；
     *   · `"input": []`              —— 空数组：**合法**，回一个空的 `data`。
     *
     * 返回 `(null, null, 错误说明)` 表示请求非法（400）。判定准则与 [ProtocolFields]
     * 一致：类型/取值写错无论谁来用都是错的 -> 400；能力边界 -> 接受 + 落日志。
     *
     * ⚠ **判序**（不是随手排的，见下面每段的理由）：
     *   ① `dimensions` 有值就 400 —— 它是一条"能力边界"，且**必须独立于 input 校验**
     *      先判：否则"input 写错 + dimensions 也写了"只会报 input 的错，调用方修完
     *      input 再来一次还是 400，而这次才看到 dimensions —— 白跑一趟。
     *   ② `encoding_format` 取值非法一律 400（取值写错不是能力边界）。
     *   ③ `input` 的存在性与元素类型。
     *   ④ 文本长度 / id 越界（越界在调用方，不在这一层 —— 见 [Item.TokenIds]）。
     */
    fun fromRequest(j: JSONObject): Triple<Request?, Options?, String?> {
        // ① dimensions：**明确 400**，不静默忽略。理由见文件头的长注释：
        //    这份 BGE 模型没有 MRL，截断出来的向量语义是坏的，而"坏了"与"没生效"
        //    在接口上长得一模一样。拒绝比回一个"看起来正常的坏向量"好。
        //    只判"键存在且不是 JSON null" —— `dimensions: null` 是调用方在表达
        //    "我不用这个字段"，与字段缺失同义，不该拒。
        if (j.has("dimensions") && !j.isNull("dimensions"))
            return Triple(null, null,
                "dimensions 不支持：本地嵌入模型（BGE 系列）没有 MRL 结构，" +
                    "截断出来的向量语义是坏的。本端点固定输出模型原生维度；" +
                    "需要降维请在客户端用 PCA / 截断后**重新归一化**，或换一个支持降维的模型")

        // ② encoding_format：float（默认）与 base64。取值非法 -> 400。
        //    `base64` 曾经也是 400，现在实现了（见 [itemJson] 与 [base64Of]）。
        var encoding = Encoding.FLOAT
        val ef = j.opt("encoding_format")
        if (ef != null && !j.isNull("encoding_format")) {
            // 说明里只报"收到的是什么" —— 不把原值嵌进带引号的串（嵌套引号会让
            // 本文件在源码级括号配平检查里被词法切割，本仓库有这条守卫）。
            when {
                ef !is String -> return Triple(null, null, "encoding_format 必须是 float 或 base64（收到 $ef）")
                ef == "float" -> encoding = Encoding.FLOAT
                ef == "base64" -> encoding = Encoding.BASE64
                else -> return Triple(null, null, "encoding_format 只支持 float 或 base64（收到 $ef）")
            }
        }

        // ③ input
        if (!j.has("input")) return Triple(null, null, "缺少 input 字段（OpenAI /v1/embeddings 的必填项）")
        val raw = j.opt("input")
        val items = ArrayList<Item>()
        when (raw) {
            is String -> items.add(Item.Text(raw))
            is Number -> {
                // `"input": 123` —— 规范里的 input 是 string | string[] | int[] | int[][]，
                // **没有**裸数字这一种。它最可能是调用方漏了方括号，明确报出来。
                return Triple(null, null,
                    "input 是单个数字 —— 规范里 token id 也要是数组（如 [1212, 318]）；" +
                        "若想编码文本请传字符串")
            }
            is JSONArray -> {
                // ⚠ 先判形态、**再**判条数上限，顺序不能反（这是本轮修掉的一个真 bug）：
                // 扁平 id 数组 `[1,2,3,...]` 是**一条**输入的 token 序列，它的长度是
                // **token 数**，不是"输入条数"。若把 MAX_INPUTS 套在它头上，
                // 一段 300 token 的文本以 id 形式发进来会被当成"批次过大"而拒 ——
                // 而同样的文本以字符串发进来却能过（字符串的长度上限是 8K**字符**）。
                // 两条输入路径对同一个文本给出不同判决，正是本仓库最恨的那种不一致。
                if (raw.length() > 0) {
                    val first = raw.opt(0)
                    if (first is Number || first is JSONArray)
                        return parseIdRows(raw, encoding, items)
                }
                if (raw.length() > MAX_INPUTS)
                    return Triple(null, null,
                        "input 条数过多（${raw.length()} > $MAX_INPUTS）；请分批调用 —— " +
                            "截断会让 data 与你的文本错位，而错位不报错")
                for (i in 0 until raw.length()) {
                    val v = raw.opt(i)
                    if (v !is String)
                        return Triple(null, null,
                            "input 元素必须是字符串（第 $i 项是 ${v?.javaClass?.simpleName ?: "null"}）")
                    items.add(Item.Text(v))
                }
            }
            else -> return Triple(null, null, "input 必须是字符串、字符串数组或 token id 数组")
        }

        // ④ 文本项的长度校验（id 项在 parseIdRows 里各自校验）
        for ((i, it) in items.withIndex()) {
            val t = (it as? Item.Text)?.text ?: continue
            if (t.isEmpty()) return Triple(null, null, "input 第 $i 项是空串 —— 空文本没有可编码的语义")
            if (t.length > MAX_INPUT_CHARS)
                return Triple(null, null,
                    "input 第 $i 项过长（${t.length} > $MAX_INPUT_CHARS 字符）；" +
                        "截断会让向量与你的原文错位，而错位不报错，所以这里直接拒绝")
        }
        return Triple(Request(items), Options(encoding), null)
    }

    /**
     * `input` 是 token id 形态时的解析：兼容两种写法 ——
     *   · `[1, 2, 3]`         —— 一个**扁平**的 id 序列 = 一条输入；
     *   · `[[1,2], [3,4]]`    —— 每行各一条输入（批量）。
     *
     * 用**首个元素**判形态（数字 -> 扁平；数组 -> 批量），随后要求**其余元素同形态**：
     * 混排（`[1, [2,3]]`）在这里被拒。理由不是"规范没写"，而是混排下
     * "这条输入对应哪个 id 段"没有唯一解，任何解释都是猜 —— 而猜错的表现是
     * 向量与调用方以为的文本无关，接口 200。
     *
     * ⚠ 越界校验**不在这里**（这里不依赖 native）：`Item.TokenIds` 只承载 id，
     * "这些 id 属不属于本 vocab"由 `LlmEngine.detokenize` 在编码期判（返回 null
     * 即越界）。这保持了本文件是纯逻辑、可离线单测。
     */
    private fun parseIdRows(
        raw: JSONArray, encoding: Encoding, out: ArrayList<Item>,
    ): Triple<Request?, Options?, String?> {
        if (raw.length() == 0) return Triple(null, null, "input 是空数组 —— 没有可编码的内容")
        val rowsAreArrays = raw.opt(0) is JSONArray
        if (rowsAreArrays) {
            // 嵌套形态：外层长度是**输入条数**（与字符串数组同一口径，套 MAX_INPUTS）。
            if (raw.length() > MAX_INPUTS)
                return Triple(null, null,
                    "input 条数过多（${raw.length()} > $MAX_INPUTS）；请分批调用 —— " +
                        "截断会让 data 与你的文本错位，而错位不报错")
            for (i in 0 until raw.length()) {
                val v = raw.opt(i)
                if (v !is JSONArray)
                    return Triple(null, null,
                        "input 的 token id 形态必须整齐：第 0 项是数组、第 $i 项不是 —— " +
                            "混排无法确定哪段 id 对应哪条输入")
                val ids = IntArray(v.length())
                for (k in 0 until v.length()) {
                    val (id, bad) = intId(v.opt(k), "第 $i 行第 $k 个")
                    if (bad != null) return Triple(null, null, bad)
                    ids[k] = id
                }
                if (ids.isEmpty()) return Triple(null, null, "input 第 $i 行是空 id 数组 —— 没有可编码的语义")
                if (ids.size > MAX_INPUT_CHARS)
                    return Triple(null, null, "input 第 $i 行 id 过多（${ids.size} > $MAX_INPUT_CHARS）；请分批")
                out.add(Item.TokenIds(ids))
            }
        } else {
            // 扁平形态：整条数组是**一个**文本的 id 序列。
            val ids = IntArray(raw.length())
            for (k in 0 until raw.length()) {
                val (id, bad) = intId(raw.opt(k), "第 $k 个")
                if (bad != null) return Triple(null, null, bad)
                ids[k] = id
            }
            if (ids.size > MAX_INPUT_CHARS)
                return Triple(null, null, "input id 过多（${ids.size} > $MAX_INPUT_CHARS）；请分批")
            out.add(Item.TokenIds(ids))
        }
        return Triple(Request(out), Options(encoding), null)
    }

    /**
     * 单个 token id 的取值校验：必须是**落在 Int 范围内的整数**。
     *
     * 三个都要判，少一个都是静默错：
     *   · 小数（`1.5`）—— 不是 token id；
     *   · 超出 Int（如 `99999999999`）—— `toInt()` 会**截断回绕**成一个
     *     完全不同的 id，而它在 vocab 内时**不会越界**，于是编码的是一段
     *     调用方没给过的文本、接口 200。必须在这里拒，不能留给 native 的越界判。
     */
    private fun intId(v: Any?, where: String): Pair<Int, String?> = when {
        v == null -> 0 to "input $where 是 null（token id 不能为空）"
        v !is Number -> 0 to "input $where 不是整数 token id（收到 ${v.javaClass.simpleName}）"
        v.toDouble() != v.toLong().toDouble() -> 0 to "input $where 不是整数 token id（收到小数 $v）"
        v.toLong() > Int.MAX_VALUE || v.toLong() < Int.MIN_VALUE ->
            0 to "input $where 超出 Int 范围（$v）—— 截断回绕会变成一个完全不同的 id，而它可能仍在 vocab 内"
        else -> v.toInt() to null
    }

    /**
     * 一条成功编码的向量 -> `data` 数组里的一项。
     *
     * `index` 必须**原样**是这一条在请求 `input` 里的下标：调用方按 index 把向量
     * 与自己的文本对齐入库，错一位就是"文本 A 配了文本 B 的向量" —— 检索全错、
     * 接口 200。所以 index 由调用方从循环下标传入，**不在这里按数组位置重算**。
     *
     * `encoding` 决定 `embedding` 是数字数组（float）还是 base64 字符串。
     * 两者都**必须**用同一个 index、同一段向量 —— 所以只在最后一步分岔，
     * 前面的取值路径共用。
     */
    fun itemJson(index: Int, embedding: FloatArray, encoding: Encoding = Encoding.FLOAT): String {
        val head = "{\"object\":\"embedding\",\"index\":$index,\"embedding\":"
        return when (encoding) {
            Encoding.FLOAT -> {
                val sb = StringBuilder(head.length + embedding.size * 10 + 2)
                sb.append(head).append('[')
                for (i in embedding.indices) {
                    if (i > 0) sb.append(',')
                    sb.append(fmt(embedding[i]))
                }
                sb.append("]}")
                sb.toString()
            }
            Encoding.BASE64 -> head + "\"" + base64Of(embedding) + "\"}"
        }
    }

    /**
     * 一段向量 -> base64（**每维 4 字节小端 IEEE-754 float32**，整段一起编码）。
     *
     * 三条都写死在实现里，因为**任何一条写反都不报错**：
     *   · 字节序必须是**小端** —— OpenAI / 主流 numpy 默认都是小端。写成大端时
     *     base64 解码照样成功、长度照样对（4N 字节），但数值量级离谱；
     *   · 是**整段**拼接后编码，不是每维各编一段（后者长度是 4N 个 base64 串，
     *     调用方按前者解会当场报废，而错误指向"解出来维度不对"）；
     *   · `Float.toRawBits()` 而不是先转 double —— 后者会改变数值（float 的
     *     位模式在 double 里是另一个数），解析出来与原向量不等。
     *
     * `NaN` / `Infinity` 在 [embed] 的契约里不会出现；真出现了这里**照样按位
     * 编码**（float32 里 NaN/Inf 都是合法位模式），调用方解出来一看就知道 ——
     * 这与 float 分支"折成 null"不同：base64 分支没有"null 维度"这个位置，
     * 塞一个 null 会把整段字节流错位。所以这里不折，保持字节流长度恒为 4N。
     */
    fun base64Of(embedding: FloatArray): String {
        val bytes = ByteArray(embedding.size * 4)
        for (i in embedding.indices) {
            val bits = embedding[i].toRawBits()
            // 小端：低字节在前。手写位运算而不经 ByteBuffer，是为了让"字节序"
            // 这件事在源码里**显式可见** —— 交给 ByteBuffer 时它只是一个
            // `order(...)` 参数，读代码的人得自己去确认它有没有被设置。
            bytes[i * 4] = (bits and 0xFF).toByte()
            bytes[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return b64(bytes)
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 标准 base64（含 `=` 补齐）。自己写是为了不依赖 `android.util.Base64`（那是 android.* ，纯逻辑层不该有）。 */
    private fun b64(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(B64[(n ushr 18) and 63]).append(B64[(n ushr 12) and 63])
                .append(B64[(n ushr 6) and 63]).append(B64[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(B64[(n ushr 18) and 63]).append(B64[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(n ushr 18) and 63]).append(B64[(n ushr 12) and 63])
                    .append(B64[(n ushr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }

    /**
     * 一条**失败**的项 —— 挂在 `data[]` 的那一项上（`embedding: null` + `error` 说明）。
     *
     * 为什么不是整批 500：一批里有一条编码失败（文本触发了某条路径的退化）时，
     * 整个请求 500 会让调用方**永远不知道是哪一条**出了问题，只能二分重试。
     * 这与 `RagIndexStore` 的"部分失败要带上失败明细"是同一个取舍。
     * 唯一能这么做的前提是 `data[].index` 把成败项对齐 —— 所以两条路径
     *（成功/失败）必须共用同一个循环下标，不能各自 append。
     */
    fun errorItemJson(index: Int, message: String): String =
        "{\"object\":\"embedding\",\"index\":$index,\"embedding\":null," +
            "\"error\":{\"message\":${message.jsonQuoted()}}}"

    /** 整份响应体。`data` 是已经拼好的项（成功与失败混在一起，按 index 有序）。 */
    fun responseJson(model: String, data: List<String>): String =
        "{\"object\":\"list\",\"data\":[${data.joinToString(",")}]," +
            "\"model\":${model.jsonQuoted()}}"

    /**
     * float -> JSON 数字。用 `BigDecimal` 式的"最短往返表示"而不是 `%f`：
     *
     *   · `"%.6f".format(x)` 会把 `1e-30` 打成 `0.000000` —— 一个**非零的分量
     *     变成 0**，向量方向悄悄变了，而它看起来只是"低位精度"。归一化后的
     *     分量本来就小（384 维下典型量级 1e-3~1e-1），大模型维度更高时更小；
     *   · `toString()` 给的是 Java 的最短往返表示（保证 `parse(toString(x)) == x`），
     *     这也是"数值不丢"的唯一可靠做法。
     *
     * `NaN` / `Infinity` **不可能出现**（[embed] 的契约是归一化成功才返回，
     * 非有限值在归一化那一步就被拒了），但真出现了也不能把它拼进 JSON ——
     * `NaN` 不是合法 JSON，整份响应会变成非法，客户端解析失败而错误指向"格式"。
     * 这里折成 `null`（JSON 合法值），让"这一条有问题"在数据里看得见。
     */
    private fun fmt(v: Float): String =
        if (v.isFinite()) v.toString() else "null"

    /** 日志用的一句话摘要（**不打印向量本身**，也不打印用户文本）。 */
    fun describe(batch: Boolean, count: Int, dim: Int, encoding: Encoding = Encoding.FLOAT): String =
        "embeddings: ${if (batch) "batch" else "single"} 条数=$count dim=$dim " +
            "encoding=${encoding.name.lowercase()}"
}
