package com.xiaowan.localinference

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 「存活探测」：从 App 内**真发一次 HTTP 请求**打自己的 `/health`，再解析响应。
 *
 * 为什么不做成「读内存里的 HttpApi.isRunning / LlmEngine.hasModel」：
 * 那些标志位只说明对象自认为在跑，说明不了**端口真的可达**。现场踩过的两次
 * 假活态都骗过了标志位：
 *   1. 冻结策略下 accept 线程已死，但端口仍在 LISTEN —— 自检标志位全绿，客户端全超时；
 *   2. 绑定地址从回环改成 0.0.0.0（或反过来）后，本机 127.0.0.1 不再可达。
 * 用户点「存活探测」要的就是「现在到底连不连得上」，所以这里走完整链路：
 * DNS 解析 → TCP 连接 → 发请求 → 收响应 → 解析 JSON → 判定 status。
 *
 * 请求与解析都做成不依赖 Android 的纯函数（[buildRequest] / [parse] / [verdict]），
 * 好让判定规则能在宿主侧离线单测——真机上只能靠"看着对不对"发现的那种错，
 * 正是这个文件要避免的。
 */
object HealthCheck {

    /**
     * `/health` 响应的结构化视图。
     * 全部字段可空：探测的价值恰恰在于服务器可能只回了半截（{@code status} 都没有）
     * 或者根本不是 JSON（连到了别的进程的 8080），那时必须如实报"响应异常"，
     * 不能为了凑齐字段而给默认值——默认值会把"没读到"伪装成"读到了且正常"。
     */
    data class Result(
        /** HTTP 状态码，未收到响应行为 null。 */
        val httpCode: Int?,
        /**
         * 原始响应体**原文**（未截断）；非 200 时也保留，便于把服务器的错误信息带出来。
         *
         * 这里是完整的，截断只发生在 [format] 的回显上 —— 两者不能混为一谈：
         * 判定用的是完整原文（[jsonParsed] 就是对它解析的结果），
         * 报告里给用户复制的才是预览，且预览必然标注"已截断"。
         */
        val body: String,
        /** /health 的 status 字段；缺失即为 null。 */
        val status: String?,
        val modelLoaded: Boolean?,
        val modelDesc: String?,
        val ctxUsed: Int?,
        val ctxSize: Int?,
        val busy: Boolean?,
        val port: Int?,
        /**
         * 「最近一次被后台冻结了多久」（毫秒；`null` = 服务端没报这个字段）。
         *
         * 来自 `/health` 的 `frozen_ms`。它是**冻结唯一的可观测面**：
         * 被冻期间连接不断、写不抛异常、日志不写，解冻之后"探活成功"与
         * "从没被冻过"读数完全一样 —— 没有这个数，"后台到底有没有被限制"
         * 就只能靠用户的体感描述（Issue #159）。
         */
        val frozenMs: Long? = null,
        /** 累计检出被冻的次数（`/health` 的 `frozen_count`）。 */
        val frozenCount: Int? = null,
        /** 累计被冻的毫秒数（`/health` 的 `frozen_total_ms`）。 */
        val frozenTotalMs: Long? = null,
        /** 是否欠着一次"空闲后重启监听"（`/health` 的 `restart_pending`）。 */
        val restartPending: Boolean? = null,
        /**
         * 嵌入**合批**的六个读数（ISSUE #269 第 1 步）。`null` = 服务端没报
         * （旧版本 / 不是本服务）—— 与上面同一条口径：缺字段要报缺，不能给默认值。
         *
         * 为什么它们要进探活报告：合批是**纯加速**，生效与不生效在协议上
         * 完全看不出来。只在 App 内日志里报的话，远端客户端与监控就永远
         * 读不到——而日志会滚、要人先动手翻，于是它极容易在回归里
         * 静默失效而无人发现（与 `kv_cache` 那三个字段同一个病）。
         */
        val embedBatchBatches: Long? = null,
        val embedBatchEntries: Long? = null,
        val embedBatchFallbackBatches: Long? = null,
        val embedBatchFallbackEntries: Long? = null,
        val embedBatchMs: Long? = null,
        /** 当前生效的批大小（设置值）；`null` = 服务端没报。 */
        val embedBatchSize: Int? = null,
        /**
         * **建当前库的索引时用的**批大小（写在那份索引的指纹里，`g` 那一格）。
         * `null` = 服务端没报；`-1` = 当前库没有索引 / 读不出来。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么它必须与 [embedBatchSize] **并排**出现
         * ═══════════════════════════════════════════════════════════════════
         * `g` 是索引指纹的**必比字段**：用户把批大小从 8 改成 1 之后，磁盘上那份
         * 索引（`g=8`）与当前配置（`g=1`）对不上，整个库**静默不可检索**。
         * 而此前 `/health` 里**只有当前配置那一个数** —— 于是"我改了它，库为什么
         * 废了"在读数上没有任何地方能回答（两个数里只有一个在外面）。
         *
         * 两个数摆在一起，判据才是可判的：
         *   · 两者相等 -> 批大小不是库不可用的原因，去别处查；
         *   · 两者不同 -> **就是它**：改回来（或重建索引）。
         * 这正是本仓库"纯加速/纯配置的东西必须有可观测面"那条口径
         *（见 `kv_cache` / `embed_batch_*` 的同族注释）。
         */
        val embedBatchSizeIndex: Int? = null,
        /**
         * [embedBatchSizeIndex] 读的是**哪个库**（当前选中的那个）。
         *
         * 为什么连库名一起报：`g` 是**每份索引自己**开头写的，没有"全局的那个"。
         * 只报一个 `-1` 而不说读的是谁，用户分不清是"这个库没索引"还是
         * "服务端读的是另一个库" —— 那正是"两个真值源分岔时没有任何现象指向它"。
         */
        val embedBatchSizeIndexKb: String? = null,
        /**
         * 嵌入模型**每条文本**的上下文预算（设置值，0.9.212）。
         *
         * 它是"内存占用为啥高"的那一格：KV / output / compute buffer 三项都按
         * 「它 × 批上限」分配，而 [embedBatchSize] 那格**不影响内存**。
         * 只报批大小时，用户把它从 8 调到 1 会以为内存该降了 —— 而不会降。
         */
        val embedCtxPerSeq: Int? = null,
        /**
         * **嵌入模型**那份是否驻留（`/health` 的 `embed_loaded`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么探活报告要报"向量模型"这一格
         * ═══════════════════════════════════════════════════════════════════
         * 对话模型加载与否由 [modelLoaded] 那一行回答，可**嵌入模型是另一份**：
         * 双常驻的四种组合（只有对话 / 只有嵌入 / 都有 / 都没有）在 `model_loaded`
         * 上**长得一模一样**。而缺嵌入模型时，检索必然全空 —— 用户看到"知识库
         * 一条都搜不到"时，`model_loaded=true` 会把他引去查知识库，其实是
         * 嵌入那份没加载（`/v1/embeddings` 直接 503）。
         *
         * `null` = 服务端没报这个字段（旧版本），**不是** false。
         */
        val embedLoaded: Boolean? = null,
        /**
         * 嵌入模型的短描述（`/health` 的 `embed_model`；空串 = 未加载）。
         *
         * 与 [modelDesc] **分开**：`embed_model` 取的是**嵌入**那一份的描述，
         * 不是对话那份的复制品。两者可能不同名（比如对话用 4B、嵌入用 0.6B），
         * 只报一个时会让人以为"嵌入用的就是对话那个模型"。
         */
        val embedModelDesc: String? = null,
        /**
         * 嵌入向量维度（`/health` 的 `embed_dim`；0 = 未加载）。
         *
         * 它是**索引指纹的一部分**：维度变了说明换了嵌入模型，旧索引必须作废
         * （同 [embedBatchSizeIndex] 那条"指纹字段必须有可观测面"的口径）。
         * 与 [embedModelDesc] 并排时，"换了嵌入模型"才判得出来 —— 只看名字
         * 可能两份描述一样、而维度其实变了（或反过来）。
         */
        val embedDim: Int? = null,
        /**
         * 「向量逐位比对」的读数（ISSUE #269 第 5 步）。
         *
         * [embedParityVerdict] 是结论档（`identical`/`close`/`different`/`missing`，
         * 空串或 null = 还没跑过），[embedParityRows] 参与条数、[embedParityIdentical]
         * 其中逐位相同的条数。三个数回答的是**唯一决定"改批大小要不要重建索引"**
         * 的那条断言 —— 只在日志里说时用户要先动手翻日志，而结论是跨版本要对的一条。
         */
        val embedParityRows: Int? = null,
        val embedParityIdentical: Int? = null,
        val embedParityVerdict: String? = null,
        /**
         * 三档结论里"有 **几条**"真的不同。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么"有不同"这一档还必须报**条数**
         * ═══════════════════════════════════════════════════════════════════
         * 真机读数（2026-10-09）是 `different`：**6 条样本里只有 1 条**不同。
         * 而"1/6"与"6/6"指向**完全相反**的结论：
         *
         *   · 6/6 -> 合批**普遍**改变了语义，批大小这条路整体不可信；
         *   · 1/6 -> 更像**某几条**踩到了某个边界（不等长合批的 padding、
         *     或超长那条的截断点），而其余条目其实是逐位相同的。
         *
         * 只有 [embedParityVerdict]（一档字符串）与 [embedParityIdentical]（相同条数）
         * 时，这两个数**说得出比例，说不出是哪一半** —— 于是"要不要收窄结论"只能靠猜，
         * 而本仓库的规矩是"判据要能落在具体一格上"。这里先给出**计数**（最小的一步）；
         * "是哪一条"要样本带名字，是后面的步。
         *
         * ⚠ `null` = 服务端没报这个字段（旧版本），**不是** 0：0 是"跑过了、没有不同"，
         * 与"没测过"方向相反（同 [embedParityRows] 的口径）。
         */
        val embedParityDifferent: Int? = null,
        /**
         * 「同路径重复」对照做没做（`embed_parity_repeat_measured`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么"有没有测"必须与"测出来几条不同"分开报
         * ═══════════════════════════════════════════════════════════════════
         * 真机首轮那条不同是 `firstDiffDim = 0`、`maxAbsDiff = 4.5e-03` —— 一条
         * 1024 维句向量的第 0 维差了四个数量级。这有两种互斥的根因：
         *
         *   · **批布局**改了语义（那是 `g` 进不进指纹的问题）；
         *   · **编码本身不确定**（同一条路连着编两次也不一样）—— 那批大小是次要问题。
         *
         * 分开这两件事的唯一读数是"同一条路重复编两次比不比得上"。而
         * `repeatNotIdentical == 0` **同时**是"全绿"与"根本没测"的读数 ——
         * 两者行动方向相反（后者该去测，前者才能收窄结论）。所以"测没测"
         * 必须单独一格（`false` = 没测，**不是**"全绿"）。
         */
        val embedParityRepeatMeasured: Boolean? = null,
        /**
         * 「同路径重复」里不是逐位相同的条数（`embed_parity_repeat_not_identical`）。
         *
         * ⚠ 读它之前**先看** [embedParityRepeatMeasured]：没测时它也是 0。
         */
        val embedParityRepeatNotIdentical: Int? = null,
        /**
         * 「**合批**那条路自己重复一遍」对照做没做（`embed_parity_repeat_batched_measured`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么「逐条重复」那一格还不够
         * ═══════════════════════════════════════════════════════════════════
         * [embedParityRepeatMeasured] 只重复了**逐条**那条路。而"合批是纯加速
         * 旋钮"这个结论要成立，还差一格：**合批那条路自己**重复时是不是也逐位相同。
         *
         *   · 逐条自重复全绿、合批自重复**就有不同** -> **合批自身不确定**
         *     （同一批、同一台机、同一次前向都复现不了自己）—— 那时"跨路不同"
         *     里混着这一份，先说"差异来自批布局"就是把两个来源焊在一起；
         *   · 合批自重复也全绿 -> 两个"不确定性"都被排掉，只剩**跨路**差异
         *     （那才是批布局），这时"要不要收窄"才真的能谈。
         *
         * `false` = 没测（**不是** "全绿"）—— 与 [embedParityRepeatMeasured] 同一条理由。
         */
        val embedParityRepeatBatchedMeasured: Boolean? = null,
        /**
         * 「合批自己重复」里不是逐位相同的条数（`embed_parity_repeat_batched_not_identical`）。
         *
         * ⚠ 读它之前**先看** [embedParityRepeatBatchedMeasured]：没测时它也是 0。
         */
        val embedParityRepeatBatchedNotIdentical: Int? = null,
        /**
         * 上面那组逐位比对的读数**取自哪一组样本**（`embed_parity_group`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么"取自哪一组"必须报出来（而不是省成一个隐含前提）
         * ═══════════════════════════════════════════════════════════════════
         * 两组样本回答的是**两个不同的问题**，方向相反：
         *
         *   · **等长**组把"长度齐不齐"拿掉了，它全同只说明"批布局本身不改语义"
         *     —— 构造上偏稳；
         *   · **长短悬殊**组是唯一能改变答案的那组（含超长 / 截断）。
         *
         * 真机现场（PR #301）把它暴露出来了：报告里一行「逐位相同 4/4 条 ——
         * 整批逐位相同，批大小是纯加速旋钮（可考虑沿用）」取自**等长**组，而
         * 同一份日志里长短悬殊是「真的不同 1 条、`4.510e-03`」。两个读数同时
         * 成立，可读者拿到的结论恰好是**用构造上偏稳的那组去回答"会不会变"**
         * —— 而这格存在的全部理由就是判「`g` 能不能按库放行」。
         *
         * 于是报告里必须写清"这组读数取自哪一组"，且**当它取自等长组时不许
         * 把结论说成"批大小是纯加速旋钮"**（那会被读成长短悬殊也稳）。
         * `null` = 服务端没报这个字段（旧版本）：不猜，只按原来的说法报数字。
         */
        val embedParityGroup: String? = null,
        /**
         * 「**换批大小重建索引后，检索结果还是不是同一些行**」对照做没做
         * （`embed_retrieve_measured`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么这一格与上面那些**向量**读数不是同一件事
         * ═══════════════════════════════════════════════════════════════════
         * [embedParityDifferent] 那几个报的是**向量**（逐条编 vs 合批编差几个分量）。
         * 而用户真正要问的是**另一句**："换批大小重建索引后，我搜出来的还是不是同几条"。
         * 这两句之间**没有蕴含关系**：
         *
         *   · 向量差 4e-03 **不等于**答案会变 —— 那点差落在第几名上，是要量的；
         *   · 向量逐位相同也**不等于**答案一样（索引里还有正文行序、切分、分数比较）。
         *
         * 所以这一格是**独立**的一格：同一批文本按两种批大小各编一套向量、
         * 同一组查询互查，比"命中的行下标集合"。没有它时，"向量有差异"会被
         * 顺手读成"检索结果也会不一样" —— 一个**推出来的**结论，而它恰好是
         * "`g` 要不要按库放行"那条决定的全部依据。
         *
         * `false` = 没测（**不是** "答案一样"）—— 与 [embedParityRepeatMeasured] 同源：
         * 没测时 [embedRetrieveAnswerChanged] 也是 0，而"没测"该去测。
         */
        val embedRetrieveMeasured: Boolean? = null,
        /**
         * 参与对照、且**两侧都搜到了东西**的查询条数（`embed_retrieve_queries`）。
         *
         * ⚠ 两侧都搜不到的查询**不算**（那是"没量到"，把它算成"一致"是把
         * 没有读数洗成一个结论）。
         */
        val embedRetrieveQueries: Int? = null,
        /**
         * 命中**集合**变了的查询条数（`embed_retrieve_answer_changed`）。
         *
         * >0 = 换批大小换了**答案** -> `g` 不能按库放行（哪怕向量只差 4e-03）。
         *
         * ⚠ 读它之前**先看** [embedRetrieveMeasured]：没测时它也是 0。
         */
        val embedRetrieveAnswerChanged: Int? = null,
        /**
         * 命中集合相同、只是**名次**变了的查询条数（`embed_retrieve_order_changed`）。
         *
         * 与 [embedRetrieveAnswerChanged] 分开：名次变 = 打平处换序（可能是
         * "索引里混了两种语义"的早期信号，但不是"答案变了"）—— 两者处置不同。
         */
        val embedRetrieveOrderChanged: Int? = null,
        /** 对照比的是哪两种批大小（`embed_retrieve_batch_base` / `_other`；0 = 没测）。 */
        val embedRetrieveBatchBase: Int? = null,
        val embedRetrieveBatchOther: Int? = null,
        /** 检索对照取前几名（`embed_retrieve_topk`；0 = 没测）。 */
        val embedRetrieveTopk: Int? = null,
        /** 连接/读超时等技术性失败的原因（未收到 HTTP 响应行时才有值）。 */
        val transportError: String?,
        val elapsedMs: Long,
        /**
         * 响应体是否是合法 JSON。HTTP 200 但正文不是 JSON，说明**应答的不是本服务**
         * （常见：同一端口被别的进程占了、或中间有反代/门户页回了 200 的 HTML）。
         * 这种情况必须算「不可达」，不能因为看见了 200 就报存活。
         */
        val jsonParsed: Boolean = false,
    )

    /** 探测默认超时：本机回环上的 /health 是毫秒级，给到 3s 已经非常宽松。 */
    const val DEFAULT_TIMEOUT_MS = 3000

    /**
     * 报告里回显正文的上限（字符）。
     *
     * 做成常量而不是写死的字面量：**截断必须被报告自己说明**（见 [format]），
     * 而"是否截断"的判据与 [format] 用的是同一个数 —— 两处各写一个数，
     * 改了一处就会出现"实际截了却不说"或"没截却说截了"。
     *
     * 值取 4096：/health 的正文约 **221 字符**，典型上限（512/1024）都在它之上；
     * 而"灌爆结论区"的真实来源是一整页 HTML（连到了别的进程），那是几十 KB 量级，
     * 4 KB 一样拦得住。上限存在的理由是拦 HTML，不是省几行字，
     * 所以它必须**明显大于本服务自己的响应**——否则常态响应一进来就被切，
     * 而切出来的残片正好落在一堆合法 JSON 上（本函数的警告语就挂在里面的
     * "响应体不是合法 JSON"），读者会把它当成解析器的结论去反推服务端。
     */
    const val BODY_PREVIEW_CHARS = 4096

    /**
     * 组装探测用的 HTTP 请求报文。
     *
     * 用 **HTTP/1.0** 而不是 1.1：服务器（HttpApi）按 `Connection: close` 处理，
     * 1.0 的默认语义就是短连接，读端能靠 EOF 判定结束，不必依赖 Content-Length
     * 解析正确——探测自己差一点反而会得出错误结论。
     */
    fun buildRequest(port: Int, path: String = "/health"): String =
        "GET $path HTTP/1.0\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Accept: application/json\r\n" +
            "Connection: close\r\n\r\n"

    /**
     * 解析响应：状态行 → body → /health 的 JSON 字段。
     * 任何一步出问题都不抛异常，只留下 null 字段与 [Result.transportError] 说明原因。
     */
    fun parse(raw: String, elapsedMs: Long, transportError: String? = null): Result {
        if (raw.isBlank()) {
            return Result(httpCode = null, body = "", status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportError ?: "未收到任何响应数据", elapsedMs = elapsedMs)
        }
        // 状态行：`HTTP/1.1 200 OK`。拿不到就不是合法 HTTP 响应，
        // 但仍把原文留在 body 里——"连上了但对方不是 HTTP 服务"是常见误配。
        val lineEnd = raw.indexOf("\r\n").let { if (it >= 0) it else raw.indexOf('\n') }
        if (lineEnd <= 0) {
            return Result(httpCode = null, body = raw.trim(), status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportError ?: "响应不含 HTTP 状态行", elapsedMs = elapsedMs)
        }
        val statusLine = raw.substring(0, lineEnd).trim()
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (code == null) {
            return Result(httpCode = null, body = raw.trim(), status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = "状态行无法解析: ${statusLine.take(80)}", elapsedMs = elapsedMs)
        }
        val bodyStart = findBodyStart(raw, lineEnd)
        val body = if (bodyStart >= raw.length) "" else raw.substring(bodyStart).trim()

        // 非 200：本服务的错误响应体是 {"error":{...}}，与 /health 是两种形状，
        // 所以不在这里取 status / model_loaded 字段。但**仍然要判定正文是不是 JSON**——
        // 这正是"服务确实应答了（503/404）"与"端口上根本不是本服务"的分水岭。
        if (code != 200) {
            val isJson = try { org.json.JSONObject(body); true } catch (_: Throwable) { false }
            return Result(
                httpCode = code, body = body, status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportError, elapsedMs = elapsedMs, jsonParsed = isJson,
            )
        }
        return parseHealthBody(code, body, elapsedMs, transportError)
    }

    /** 找到响应头结束、正文开始的位置；\r\n\r\n 优先，容错裸 \n\n。 */
    private fun findBodyStart(raw: String, searchFrom: Int): Int {
        val crlf = raw.indexOf("\r\n\r\n", searchFrom)
        if (crlf >= 0) return crlf + 4
        val lf = raw.indexOf("\n\n", searchFrom)
        if (lf >= 0) return lf + 2
        return raw.length
    }

    private fun parseHealthBody(code: Int, body: String, elapsedMs: Long, err: String?): Result {
        val j = try {
            org.json.JSONObject(body)
        } catch (_: Throwable) {
            return Result(httpCode = code, body = body, status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = (err?.plus("; ") ?: "") + "响应体不是合法 JSON",
                elapsedMs = elapsedMs)
        }
        // 逐字段 opt：/health 是跨版本接口，缺字段要报缺、不能当默认值。
        // 用 isNull 判断，避免 JSONObject.NULL 被 optString 变成 "null" 这种脏值。
        fun str(k: String): String? = if (j.isNull(k)) null else j.optString(k, "")?.ifEmpty { null }
        fun int(k: String): Int? = if (j.isNull(k)) null else j.optInt(k, Int.MIN_VALUE)
            .let { if (it == Int.MIN_VALUE) null else it }
        fun bool(k: String): Boolean? = if (j.isNull(k)) null else j.optBoolean(k, false)
        // long 与 int 分开：冻结毫秒数在大周期上不会溢出 Int，但"累计"会随时间线性增长，
        // 用 Int 会得到一个静默回绕的负数 —— 那是"数字看着有、其实在说谎"，比不报更糟。
        fun long(k: String): Long? = if (j.isNull(k)) null else j.optLong(k, Long.MIN_VALUE)
            .let { if (it == Long.MIN_VALUE) null else it }
        return Result(
            jsonParsed = true,
            httpCode = code,
            body = body,
            status = str("status"),
            modelLoaded = bool("model_loaded"),
            modelDesc = str("model"),
            ctxUsed = int("ctx_used"),
            ctxSize = int("ctx_size"),
            busy = bool("busy"),
            port = int("port"),
            frozenMs = long("frozen_ms"),
            frozenCount = int("frozen_count"),
            frozenTotalMs = long("frozen_total_ms"),
            restartPending = bool("restart_pending"),
            // 合批六个读数。⚠ 前五个用 `long` 而**不是** `int`：它们是**累计**量，
            // 随时间线性增长，用 Int 会得到一个静默回绕的负数 —— 而那是"数字看着有、
            // 其实在说谎"，比不报更糟（与 `frozen_total_ms` 同一条理由）。
            embedBatchBatches = long("embed_batch_batches"),
            embedBatchEntries = long("embed_batch_entries"),
            embedBatchFallbackBatches = long("embed_batch_fallback_batches"),
            embedBatchFallbackEntries = long("embed_batch_fallback_entries"),
            embedBatchMs = long("embed_batch_ms"),
            embedBatchSize = int("embed_batch_size"),
            embedBatchSizeIndex = int("embed_batch_size_index"),
            embedBatchSizeIndexKb = str("embed_batch_size_index_kb"),
            embedCtxPerSeq = int("embed_ctx_per_seq"),
            // 向量模型那三格（对话那份由 model_loaded/model 回答，这里回答**嵌入**那份）：
            // 缺嵌入模型时检索必然全空，而它在 model_loaded 上看不出来。
            embedLoaded = bool("embed_loaded"),
            embedModelDesc = str("embed_model"),
            embedDim = int("embed_dim"),
            // 逐位比对读数（ISSUE #269 第 5 步）。`rows/identical` 用 `int` 而不是
            // `long`：它们**不是**累计量（每次对照覆盖），条数量级在个位到几十，
            // 不存在回绕问题（与合批那五个累计量不同）。
            embedParityRows = int("embed_parity_rows"),
            embedParityIdentical = int("embed_parity_identical"),
            embedParityVerdict = str("embed_parity_verdict"),
            embedParityDifferent = int("embed_parity_different"),
            embedParityRepeatMeasured = bool("embed_parity_repeat_measured"),
            embedParityRepeatNotIdentical = int("embed_parity_repeat_not_identical"),
            embedParityRepeatBatchedMeasured = bool("embed_parity_repeat_batched_measured"),
            embedParityRepeatBatchedNotIdentical = int("embed_parity_repeat_batched_not_identical"),
            embedParityGroup = str("embed_parity_group"),
            // 检索对照（与上面那组**配对**的另一半 —— 向量那几格答不了"答案变没变"）。
            embedRetrieveMeasured = bool("embed_retrieve_measured"),
            embedRetrieveQueries = int("embed_retrieve_queries"),
            embedRetrieveAnswerChanged = int("embed_retrieve_answer_changed"),
            embedRetrieveOrderChanged = int("embed_retrieve_order_changed"),
            embedRetrieveBatchBase = int("embed_retrieve_batch_base"),
            embedRetrieveBatchOther = int("embed_retrieve_batch_other"),
            embedRetrieveTopk = int("embed_retrieve_topk"),
            transportError = err,
            elapsedMs = elapsedMs,
        )
    }

    /** 判定结果，供 UI 一句话回显与着色。 */
    enum class Verdict { ALIVE, DEGRADED, UNREACHABLE }

    /**
     * 判定规则（这是本文件的核心，也是单测钉得最死的部分）：
     *
     * 第一道门：**应答的到底是不是本服务**。判据是"正文能否解析成 JSON"，不是 HTTP 状态码
     * ——一个门户页/反代/别的进程完全可能回 200 的 HTML，把它算成"可达"正是误判的开头。
     * 过不了这道门一律 [Verdict.UNREACHABLE]（连接被拒 / 超时 / 连到了别的东西）。
     *
     * 过了第一道门，就是本服务的响应体，再分两档：
     * - [Verdict.ALIVE]：`status=ok` **且** 模型已加载 —— 现在就能推理
     * - [Verdict.DEGRADED]：其余全部情况。包括 `status` 非 `ok`、
     *   非 200（本服务的错误体是 `{"error":{...}}`，证明它确实应答了、只是这请求没成功）、
     *   以及 **`status=ok` 但模型未加载**（服务活着，生成类请求拿 503。
     *   用户问的是"服务器状态"，一个不能推理的服务不该被显示成全绿）
     */
    fun verdict(r: Result): Verdict = when {
        // 第一道门是"对方回的到底是不是本服务"：正文不是 JSON 就不是，
        // 哪怕它回了 200。判定顺序不能颠倒——先看 200 就会把别人的 HTML 当存活。
        !r.jsonParsed -> Verdict.UNREACHABLE
        // 到这里可以确定是本服务的响应体（/health 的 {"status":...}
        // 或错误分支的 {"error":...}），再按 status 分档。
        r.status?.lowercase() != "ok" -> Verdict.DEGRADED
        r.httpCode != 200 -> Verdict.DEGRADED
        r.modelLoaded != true -> Verdict.DEGRADED
        else -> Verdict.ALIVE
    }

    /**
     * 多行、可直接贴给别人的探测报告；参数里的 host/port 由调用方按实际绑定给出。
     *
     * [showRawEmbedProbe] 默认 **false**：`embed_parity_*` / `embed_retrieve_*`
     * 那十几个字段是**排查批大小问题时才需要的原始读数**，日常探活里它们是噪音
     * ——用户想知道的是"服务活没活、模型装没装、库认的批大小是多少"，而
     * "逐位相同 3/4 条、命中集合变了 0 条、取自长短悬殊那组"这一大坨**看完也不知道
     * 该做什么**。所以它们**不进报告正文**（正文只留 [embedBatchSizeIndex] 那类
     * "能直接指导下一步"的结论）。
     *
     * ⚠ **不是丢弃**：完整读数由 [rawEmbedProbeLines] 照旧打进 App 日志
     * （`[探测]` 那几行），要排查时翻日志就有；`/health` 响应体本身也没改
     * （旧客户端/监控拿到的还是全量字段）。这里只改"探活报告给用户看什么"。
     *
     * ⚠ 做成**形参**而不是常量开关：判据与调用方要能各自决定，而
     * "以后想临时打开看一眼"不该改源码。
     */
    fun format(r: Result, host: String, port: Int, showRawEmbedProbe: Boolean = false): String {
        val url = "http://$host:$port/health"
        val sb = StringBuilder()
        when (val v = verdict(r)) {
            Verdict.ALIVE -> sb.append("✅ 存活：服务可达，模型已加载")
            Verdict.DEGRADED -> sb.append("⚠ 服务可达，但状态不完整")
            Verdict.UNREACHABLE -> sb.append("❌ 不可达：探测失败")
        }
        sb.append("\n探测 $url")
        sb.append("（${r.elapsedMs} ms）")

        r.transportError?.let { sb.append("\n原因：$it") }
        r.httpCode?.let { sb.append("\nHTTP $it") }
        r.status?.let { sb.append(" ｜ status=$it") }
        r.port?.let { p ->
            sb.append("\n端口（服务自报）：$p")
            if (p != port) sb.append("  ⚠ 与当前探测端口 $port 不一致")
        }
        r.modelLoaded?.let { loaded ->
            if (loaded) {
                sb.append("\n模型已加载：${r.modelDesc ?: "(未报名称)"}")
                if (r.ctxSize != null) sb.append("\n上下文：${r.ctxUsed ?: 0}/${r.ctxSize}")
            } else {
                sb.append("\n模型未加载：/v1/models 为空，生成类请求会返回 503")
            }
        }
        // 向量模型（嵌入那份）——与对话模型**分开**报。
        // 双常驻的四种组合在 `model_loaded` 上长得一模一样，而缺嵌入模型时
        // 检索必然全空（用户看到"一条都搜不到"会被引去查知识库）。
        // ⚠ 只在服务端**报了**这三个字段时才出这一行：`null` = 旧版本没报，
        //   那时多挂一行"向量模型：未报"是噪音，而噪音会被跳过。
        r.embedLoaded?.let { loaded ->
            sb.append("\n向量模型")
            if (loaded) {
                sb.append("已加载：").append(r.embedModelDesc ?: "(未报名称)")
                r.embedDim?.let { d -> if (d > 0) sb.append("（dim=$d）") }
            } else {
                sb.append("未加载：检索必然全空（/v1/embeddings 返回 503）；" +
                    "与对话模型是**两份**，模型已加载不代表它能用")
            }
        }
        r.busy?.let { b -> if (b) sb.append("\n生成进行中（busy=true）") }
        // 后台冻结：只有真的检出过才回显（0 次不提，免得每次探测都挂一行无信息的字）。
        // 这一行的存在意义就是"别再靠猜"：用户看到的"后台被限制了"到底有没有发生、
        // 发生了多久，全由它给出。`restart_pending` 一并报 —— 它是"服务本该死而复生、
        // 但为了不掐断在跑的流而故意欠着"的唯一读数。
        val fc = r.frozenCount ?: 0
        if (fc > 0) {
            sb.append("\n后台冻结：上次 ").append((r.frozenMs ?: 0) / 1000)
                .append("s，累计 ").append(fc).append(" 次 / ")
                .append((r.frozenTotalMs ?: 0) / 1000).append("s")
        }
        if (r.restartPending == true) {
            sb.append("\n监听欠一次重启（等本轮生成结束，避免掐断在跑的流）")
        }
        // 嵌入合批（ISSUE #269）：**只在有读数时回显**（0 提不提，免得每次探活都挂一行
        // 无信息的字）。它是合批生效与否**唯一的对外读数** —— 没有它，
        // "合批到底有没有在工作"只能翻 App 内日志猜。
        val bb = r.embedBatchBatches ?: 0L
        val be = r.embedBatchEntries ?: 0L
        val fb = r.embedBatchFallbackBatches ?: 0L
        val fe = r.embedBatchFallbackEntries ?: 0L
        if (be > 0L || fe > 0L) {
            val total = be + fe
            val pct = if (total > 0L) (be * 100 / total).toInt() else 0
            sb.append("\n嵌入合批：").append(be).append("/").append(total)
                .append(" 条（").append(pct).append("%，").append(bb).append(" 批")
                .append(if (bb > 0L) "，共 " + (r.embedBatchMs ?: 0L) + " ms" else "")
                .append("）")
            if (fb > 0L) {
                sb.append("；回落逐条 ").append(fe).append(" 条（").append(fb).append(" 批）")
                if (bb == 0L) {
                    sb.append(" ⚠ 合批**一次都没生效**（这个模型声明的 pooling " +
                        "可能是 MEAN/CLS/LAST 那三档，它们不合批）")
                }
            }
        }
        r.embedBatchSize?.let { n -> sb.append("\n当前批大小：").append(n)
            .append(if (n <= 1) "（关合批）" else "") }
        // 建索引时用的批大小（ISSUE #269 第 5 步）：与"当前批大小"并排，两个数
        // 一起才判得出"库为什么不可用"。⚠ 只在**库里的那个数与当前不同**时才多说
        // 一句 —— 两者相等时再念一遍只是噪音，而噪音会让真正要紧的那句被跳过。
        r.embedBatchSizeIndex?.let { idx ->
            val kb = r.embedBatchSizeIndexKb.orEmpty()
            if (idx < 0) {
                sb.append("\n建索引时用的批大小：读不到")
                    .append(if (kb.isNotEmpty()) "（当前库「$kb」没有索引或索引读不出来）" else "（没选中知识库）")
            } else {
                sb.append("\n建索引时用的批大小：").append(idx)
                    .append(if (kb.isNotEmpty()) "（库「$kb」）" else "")
                val now = r.embedBatchSize
                if (now != null && now != idx) {
                    sb.append("\n⚠ 与当前批大小（").append(now)
                        .append("）不同 —— 批大小进索引指纹，这一个数不一致就会让整库判成" +
                            "「索引不可用」：把批大小改回 ").append(idx).append("，或重建索引")
                }
            }
        }
        // 嵌入 ctx 那一格（0.9.212）：内存的**唯一**旋钮在它身上 —— 批大小只决定
        // "一次塞几条"，不改 ctx 容量。两行并排摆出来，"内存怎么降"才有落点。
        r.embedCtxPerSeq?.let { n -> sb.append("\n嵌入每条预算：").append(n)
            .append("（ctx 总量 = 它 × ").append(EmbedLimits.kEmbedBatchRows)
            .append("；KV/output/compute buffer 都按这个总量分配 —— 内存高看这一格）") }
        // ⚠ 从这里往下到「检索对照」那一段，全是 `embed_parity_*` / `embed_retrieve_*`
        // 的**原始读数** —— 默认**不进报告正文**（理由见 format 的 kdoc：
        // 日常探活里它们看完也不知道该做什么）。完整读数由 [rawEmbedProbeLines]
        // 打进日志；要临时看回报告，把 [showRawEmbedProbe] 传 true。
        if (showRawEmbedProbe) {
        // 逐位比对结论（ISSUE #269 第 5 步）。**只在跑过时**才说（`rows<=0` 或
        // 结论为空 = 还没跑过，那时报一句"没跑过"是噪音，而噪音会被跳过）。
        // 措辞必须与结论档**一一对应**：把 close 说成"相同"会让用户据此沿用 ——
        // 而沿用这条路上索引里正是两种向量（见 EmbedParity 对象头）。
        val pv = r.embedParityVerdict.orEmpty()
        if (pv.isNotEmpty() && (r.embedParityRows ?: 0) > 0) {
            val n = r.embedParityRows ?: 0
            val idn = r.embedParityIdentical ?: 0
            val dfn = r.embedParityDifferent
            sb.append("\n向量逐位比对：逐位相同 ").append(idn).append("/").append(n).append(" 条")
            // `different` 的**条数**要报出来（`null` = 服务端没报这个字段，与 0 分开写）：
            // "1/6 不同"与"6/6 不同"给出的下一步相反的（前者去查那条样本，
            // 后者说明合批整体不可信）—— 只报相同条数时这两个读数长得一样。
            dfn?.let { sb.append("、真的不同 ").append(it).append(" 条") }
            // 取自哪一组**必须说**：等长组按构造必然稳，长短悬殊组才答"会不会变"。
            // 不写这一句时，"逐位相同 4/4 —— 批大小是纯加速旋钮"会被读成
            // "长短悬殊也稳"（真机现场正是如此 —— 见 [Result.embedParityGroup]）。
            // 服务端没报这个字段（旧版本）时不猜，只按原来的说法报数字。
            val grp = r.embedParityGroup.orEmpty()
            if (grp.isNotEmpty()) sb.append("（取自").append(grp).append("那组）")
            sb.append(" —— ")
            // ⚠ `identical` 这一档的措辞**跟着组走**：等长组全同时只能说
            //   "这一组（构造上偏稳）没测出差异"，**不能**说"批大小是纯加速旋钮"
            //   —— 那是长短悬殊组的结论，而它恰恰是这格要回答的那句。落进
            //   `/health` 的若仍是等长组（旧版本），这句话就是**用必然成立的那组
            //   回答问题**（本仓库反复记的那族）。
            sb.append(when (pv) {
                "identical" -> parityIdenticalWording(grp)
                "close" -> "不是逐位相同（浮点级差异），**不能**据此沿用"
                "different" -> if (dfn == null) {
                    "有条目**真的不同**，批大小不能用纯加速来解释（沿用会把两种语义拼起来）"
                } else if (n > 0 && dfn >= n) {
                    "**每一条**都真的不同 —— 合批**普遍**改变了语义，批大小这条路整体不可信"
                } else if (dfn > 0) {
                    "只有 $dfn/$n 条真的不同（不是整批）—— 更像**某几条**踩到了边界" +
                        "（不等长合批的 padding / 超长那条的截断点），先查这几条再决定要不要收窄结论"
                } else {
                    "结论说有不同的条目，但不同的条数是 0（读数自相矛盾，先看服务端怎么算的）"
                }
                "missing" -> "有条目缺失，补齐后再判（缺失不是相同也不是不同）"
                else -> pv
            })
            // 「同路径重复」那两句 —— 它们把"差异来自哪里"分成**三种**下一步。
            // 分岔点有两处，且处置各不同：
            //   · 逐条那条路自己重复就有不同 -> **先别谈批大小**（编码本身不确定）；
            //   · 逐条全绿、但**合批**自己重复就有不同 -> 一样先别谈批大小
            //     （合批自身不确定，与"批布局改语义"是两件事）；
            //   · 两条路各自都全绿 -> 差异来自**批布局**（这才轮到"批大小能不能放行"）。
            // 没测过（`...Measured == false/null`）时**不能**说"全绿"：
            // `notIdentical == 0` 在没测时也是 0，把它读成全绿会把"没测过"说成
            // "验过了、没问题"（与 `repeatMeasured` 分开报同一条理由）。
            when (r.embedParityRepeatMeasured) {
                true -> {
                    val rn = r.embedParityRepeatNotIdentical ?: 0
                    if (rn > 0) {
                        sb.append("；⚠ 同路径重复（逐条）就有 $rn 条不是逐位相同 —— " +
                            "**先别谈批大小**（连同一条路自己都不能复现自己，是编码本身不确定）")
                    } else {
                        // 逐条自重复全绿 -> 再看**合批**那条路自己重复的结果。
                        // 这一格不读时，"合批自身不确定"会被当成"批布局改语义"。
                        when (r.embedParityRepeatBatchedMeasured) {
                            true -> {
                                val bn = r.embedParityRepeatBatchedNotIdentical ?: 0
                                sb.append(if (bn == 0) {
                                    "（同路径重复全绿：逐条、合批**两条路各自都复现得了自己**" +
                                        " —— 差异来自**批布局**，不是编码本身不确定）"
                                } else {
                                    "；⚠ 逐条重复全绿，但**合批**那条路自己重复就有 $bn 条不是逐位相同" +
                                        " —— 那是**合批自身不确定**，**先别谈批大小**（先查合批那条路）"
                                })
                            }
                            false -> sb.append("（逐条重复全绿，但**没做**合批那条路的重复对照 —— " +
                                "「合批自身不确定」与「批布局改语义」在这里分不开）")
                            // null = 服务端没报（旧版本）：不猜，只说逐条那半句
                            else -> sb.append("（同路径重复（逐条）全绿；" +
                                "合批那条路的重复对照这次没有读数）")
                        }
                    }
                }
                false -> sb.append("（本次**没做**同路径重复对照 —— " +
                    "差异来自批布局还是编码本身不确定，这里分不开）")
                // null = 服务端没报这个字段（旧版本）：什么都不说，不猜
                else -> {}
            }
        }
        // 「检索对照」那一句（ISSUE #269 第 5 步，与上面那组**配对**的另一半）。
        // ⚠ 它**必须**与"向量逐位比对"分开说：向量不同**不蕴含**答案不同，
        //   而"顺手把前者读成后者"正是这一格要挡住的推断（见 EmbedRetrieveParity
        //   对象头）。所以这里只报**量出来的**那件事：命中集合变没变。
        if (r.embedRetrieveMeasured == true && (r.embedRetrieveQueries ?: 0) > 0) {
            val qn = r.embedRetrieveQueries ?: 0
            val changed = r.embedRetrieveAnswerChanged ?: 0
            val order = r.embedRetrieveOrderChanged ?: 0
            val bB = r.embedRetrieveBatchBase ?: 0
            val oB = r.embedRetrieveBatchOther ?: 0
            val k = r.embedRetrieveTopk ?: 0
            sb.append("\n检索对照（跳批大小 $bB vs $oB，topK=$k）：$qn 条查询")
            sb.append(when {
                changed > 0 -> "里有 $changed 条**命中集合变了** —— 换批大小换了**答案**，" +
                    "`g` 不能按库放行（注意：这与「向量是否逐位相同」是两件事）"
                order > 0 -> "命中集合全同，但 $order 条的名次变了（打平处换序 —— " +
                    "与「要不要放行 `g`」不是同一件事）"
                else -> "命中集合与名次都逐位相同 —— 这批文本上，换批大小没换答案" +
                    "（**这是量出来的**，不是从「向量是否有差异」推出来的）"
            })
        } else if (r.embedRetrieveMeasured == false) {
            sb.append("\n检索对照：本次**没测**（没测时「命中没变」也是 0 条，读之前先看这一格）")
        }
        }
        // 只有 status=ok 却没带 model_loaded 才算异常；status 本来就非 ok 时上面已说明过了
        if (r.httpCode == 200 && r.status?.lowercase() == "ok" && r.modelLoaded == null) {
            sb.append("\n⚠ 响应里没有 model_loaded 字段，无法确认模型状态")
        }
        if (r.body.isNotBlank()) renderBody(sb, r)
        return sb.toString()
    }

    /**
     * `embed_parity_*` / `embed_retrieve_*` 那十几个字段的**完整读数**，一行一条。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么它们从"报告正文"搬到"日志"
     * ═══════════════════════════════════════════════════════════════════════
     * 这些字段是 0.9.223 起为排查「批大小 `g` 能不能按库放行」逐格补上的：
     * 参与条数、逐位相同条数、真的不同条数、取自哪一组、同路径重复（逐条 / 合批）
     * 做没做、命中集合变了没变、名次变了没变、两侧批大小、topK……
     *
     * 它们**每一格都有理由**，但那个理由是**排查场景**的：用户日常打一次探活，
     * 要的是"服务活没活 / 模型装没装 / 库认的批大小是多少"这种**一句话能指导
     * 下一步**的信息。把十几格原始读数铺在它下面，结果是**信息量大、可用信息少**
     * ——报告长了三倍，而多出来的部分看完不知道该做什么（用户的反馈原话：
     * *"信息太乱了，对用户也没实际用处"*）。
     *
     * ⚠ 所以要分开的**不是"报不报"**，是"**报给谁看**"：
     *   · 报告正文（用户）—— 只留能直接决定下一步的结论；
     *   · 日志（排查者）—— **完整**读数照旧，一格不少；
     *   · `/health` 响应体（监控 / 旧客户端）—— **一个字段都没动**。
     *
     * 「完整信息不丢」这条是硬要求：`/health` 有十几个字段、报告里只剩几行，
     * 若中间没有一处如实落下来，那些字段就成了"写在服务端、谁也没读过"的摆设
     * （本仓库反复记录的那族：算出来没人读 == 没算）。日志就是那个落点。
     *
     * ⚠ 落 [Result.embedParityGroup] 那类**来源**字段要一并落下来：只报数字时
     * "逐位相同 4/4"在两组样本下**字面一样、含义相反**（见 [parityIdenticalWording]）。
     */
    fun rawEmbedProbeLines(r: Result): List<String> {
        val out = ArrayList<String>()
        // 逐位比对那一组。`rows` 为 0 或结论为空 = 还没跑过，那一格就不落
        // （落一行"没跑过"是噪音，而噪音会被跳过）。
        val pv = r.embedParityVerdict.orEmpty()
        if (pv.isNotEmpty() && (r.embedParityRows ?: 0) > 0) {
            out.add("[探测] embed_parity 结论=$pv 条数=${r.embedParityRows} " +
                "逐位相同=${r.embedParityIdentical ?: 0} 真的不同=${r.embedParityDifferent ?: 0} " +
                "取自=${r.embedParityGroup.orEmpty().ifEmpty { "(未报)" }}")
            // 「测没测」必须与"几条不同"分开落：0 在没测时也是 0，两件事方向相反。
            out.add("[探测] embed_parity 重复对照：逐条 做没做=${r.embedParityRepeatMeasured ?: "未报"}" +
                " 不是逐位相同=${r.embedParityRepeatNotIdentical ?: 0}；" +
                "合批 做没做=${r.embedParityRepeatBatchedMeasured ?: "未报"}" +
                " 不是逐位相同=${r.embedParityRepeatBatchedNotIdentical ?: 0}")
        }
        // 检索对照那一组（与上面配对的那一半 —— 向量那几格答不了"答案变没变"）。
        if (r.embedRetrieveMeasured != null) {
            out.add("[探测] embed_retrieve 测没测=${r.embedRetrieveMeasured} " +
                "查询=${r.embedRetrieveQueries ?: 0} 集合变了=${r.embedRetrieveAnswerChanged ?: 0} " +
                "名次变了=${r.embedRetrieveOrderChanged ?: 0} " +
                "批大小=${r.embedRetrieveBatchBase ?: 0} vs ${r.embedRetrieveBatchOther ?: 0} " +
                "topK=${r.embedRetrieveTopk ?: 0}")
        }
        return out
    }

    /**
     * 「逐位相同」这一档的措辞 —— 它**跟着取样的那一组走**（[Result.embedParityGroup]）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么同一档读数要用两句不同的话
     * ═══════════════════════════════════════════════════════════════════════
     * 两组样本回答**两个不同的问题**，且方向相反：
     *
     *   · **等长**组把"长度齐不齐"拿掉了，它全同只说明"批布局本身不改语义"
     *     —— 构造上偏稳；
     *   · **长短悬殊**组是唯一能改变答案的那组（含超长 / 截断）。
     *
     * 同一句"整批逐位相同，批大小是纯加速旋钮"跟在两组后面时，**字面一样、
     * 含义相反**：前者是"这一组（必然稳的）没测出差异"，后者才是"换批大小
     * 不改向量"。真机现场（PR #301）正是拿等长组那句去答"长短悬殊会不会变"
     * —— 而这格存在的全部理由就是判「`g` 能不能按库放行」。
     *
     * 落进 `/health` 的**本该**是长短悬殊那组（见 `probeEmbedBatchParity`）；
     * 这里仍要分句，是因为旧版本的服务端可能落的是等长组（`group` 会说），
     * 而报告不能替它把话说满。组名缺失（旧版本没这个字段）时给一句**最弱**的
     * 说法：只说这一批逐位相同，不承诺"纯加速旋钮"。
     */
    private fun parityIdenticalWording(group: String): String = when (group) {
        EmbedLimits.kLongShortLabel ->
            "整批逐位相同，批大小是**纯加速**旋钮（可考虑沿用）"
        EmbedLimits.kSameLenLabel ->
            "**这一组（构造上偏稳）**整批逐位相同 —— 它只说明批布局本身不改语义，" +
                "**答不了**「长短悬殊那批换批大小会不会改答案」（那组才是放行 `g` 的依据）"
        else ->
            "这一批逐位相同（没报取自哪一组 —— 不能据此推出「长短悬殊那批也一样」）"
    }

    /**
     * 「响应原文」那一行 —— 方案 1：**不再回显整份正文**，只说它去哪儿看。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么"报告里那一大坨"不是字段多，是这一行
     * ═══════════════════════════════════════════════════════════════════════
     * 用户贴回的探活报告里有 51 个字段，看着像"探活塞了太多东西"。但把
     * [format] 的正文段从头数一遍会发现：`embed_parity_*` / `embed_retrieve_*`
     * 那十几格**早在 0.9.231 就不进报告了**，真正把 51 个字段一字不落带进来的
     * 是**末尾这一行** —— `BODY_PREVIEW_CHARS` 是 4096，而真机那份 `/health`
     * 正文 1249 字符，**根本没触发截断**，等于整份原件回显。
     *
     * 所以"信息太乱"与"`/health` 有几个字段"是**两件事**：删字段删的是
     * "服务端怎么回"（那会把监控 / 旧客户端 / 判据一起砸掉），改这一行改的才是
     * "报告给用户看什么"。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么不是"把正文删掉"
     * ═══════════════════════════════════════════════════════════════════════
     * 因为**给出去的证据必须能被独立复核**：用户点一次探活，就是为了拿一份
     * 能贴给别人看的东西。全绿时省掉原文，等于把"凭什么说它活着"藏起来。
     * 所以这里**照旧回显**，但只在**异常**时回显：
     *
     *   · 判定为 [Verdict.ALIVE]（全绿）—— 不贴原文。完整响应在日志里
     *     （`[探测]` 那几行 + `/health` 响应体本身），报告只写去哪儿看；
     *   · 其余情况（DEGRADED / UNREACHABLE，以及**正文不是合法 JSON**）——
     *     **照旧回显**（含截断规则）：这时原文本身就是"到底哪里不对"的唯一证据，
     *     把它省掉，用户就只能拿着"不可达"三个字来问。
     *
     * ⚠ 截断规则不变（[BODY_PREVIEW_CHARS]）：**截断必须自证**。默默 `take(N)`
     * 的后果不是"少显示一段"，而是**证据变成伪证** —— 残片天然非法 JSON，
     * 而结论区里就挂着"响应体不是合法 JSON"，读者只能把报告自己造的截断
     * 读成服务端的错。
     */
    private fun renderBody(sb: StringBuilder, r: Result) {
        val flat = r.body.replace("\n", " ").replace("\r", " ")
        // 全绿且正文是本服务的合法 JSON -> 不复述原件（那一坨 51 个字段对
        // "接下来该做什么"没有任何指向），只留一句去哪儿看。
        if (verdict(r) == Verdict.ALIVE && r.jsonParsed) {
            sb.append("\n（完整响应不在此复述：日志里搜「[探测]」，或直接 GET " +
                "/health —— 上面几行是它的结论）")
            return
        }
        sb.append("\n响应原文：${flat.take(BODY_PREVIEW_CHARS)}")
        if (flat.length > BODY_PREVIEW_CHARS) {
            sb.append("…（已截断，完整 ${flat.length} 字符；截断处不是响应的问题）")
            return
        }
        // 在整段正文里再点一次它与结论的关系：结论在最上面，正文在下面，
        // 两段嵌在一起，报告又会被整段复制出去，不点明就会各读各的。
        if (r.jsonParsed) {
            sb.append("\n（以上正文与判定一致：合法 JSON / 状态码，结论不来自这段预览）")
        } else {
            sb.append("\n（以上正文即服务器所回原文，未被截断；它不是合法 JSON，故判定为不可达）")
        }
    }

    /**
     * 真发请求。阻塞式，调用方负责放到后台线程。
     * 连接与读都设 [timeoutMs]，避免"探测按钮转圈不停"这种更糟的体验。
     */
    fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        path: String = "/health",
    ): Result {
        val t0 = System.nanoTime()  // elapsedMs 只作差，单调钟（墙钟跳变会算出负耗时）
        var raw = ""
        var readTimedOut = false
        try {
            Socket().use { s ->
                // 连接与读分开设超时：连不上通常是"端口没开"（立刻 refused），
                // 而读到一半卡住才是"假活态"，两者提示语不同，超时值也该区别对待。
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply {
                    write(buildRequest(port, path).toByteArray(Charsets.UTF_8))
                    flush()
                }
                raw = readAll(s.getInputStream(), timedOut = { readTimedOut = true })
                // 读超时（连上了但对方一个字节都没回）不能和"连接被拒"混为一谈：
                // 前者正是要区分的假活态（端口在、没人 accept），后者是端口根本没开。
                // readAll 为了不让探测自己卡死会吞掉超时，所以在这里补回这个事实。
                if (raw.isEmpty() && readTimedOut) {
                    return Result(httpCode = null, body = "", status = null, modelLoaded = null,
                        modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                        transportError = "读取超时（连接已建立但 ${timeoutMs} ms 内无任何响应字节，" +
                            "端口在监听却无人 accept —— 典型的假活态）",
                        elapsedMs = (System.nanoTime() - t0) / 1_000_000L)
                }
            }
        } catch (e: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            return Result(httpCode = null, body = raw, status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportReason(e), elapsedMs = ms)
        }
        return parse(raw, (System.nanoTime() - t0) / 1_000_000L)
    }

    /** 把异常翻译成用户能据以行动的短句；直接抛 e.message 常见是 null，等于什么都没说。 */
    private fun transportReason(e: Throwable): String = when (e) {
        is java.net.ConnectException -> "连接被拒绝（端口未监听或绑定地址不是本机回环）"
        is java.net.SocketTimeoutException -> "连接或读取超时（端口在但无人应答，属假活态）"
        is java.net.UnknownHostException -> "地址无法解析：${e.message}"
        is java.net.NoRouteToHostException -> "路由不可达（IP 或子网不对）"
        is java.io.IOException -> "网络异常：${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * 读到 EOF 或读满上限为止。
     * 上限不是省事，是护栏：/health 只有几十字节，若对方一直吐数据（连到的不是本服务），
     * 这里必须能收手，否则探测自己会变成一个新的卡死点。
     */
    private fun readAll(
        ins: InputStream,
        cap: Int = 64 * 1024,
        timedOut: (() -> Unit)? = null,
    ): String {
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < cap) {
            val n = try { ins.read(buf) } catch (_: java.net.SocketTimeoutException) {
                timedOut?.invoke(); break
            }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
