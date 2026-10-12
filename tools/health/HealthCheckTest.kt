package com.xiaowan.localinference

import java.net.ServerSocket

// 「存活探测」的离线单测。
//
// 为什么值得单测：判定规则全是"看字段下结论"，而它最可能的失效方式恰恰是**报错报反**——
//   1. 服务器没回 200（或 /health 的 status 不是 ok）却判定成"存活"，用户拿它当"能用"的依据；
//   2. 模型没加载（生成必 503）却因为 status=ok 而显示全绿；
//   3. 连接被拒/超时（真正要查的那种故障）被归类成"服务可达"。
// 这三种都不会抛异常、界面看着也正常，只有断言能钉住。
// 另外这里真的开一个临时 ServerSocket 跑一遍 [HealthCheck.probe]，
// 因为"超时值没设/读不到 EOF 卡死/把响应头当正文"这类错只有真连一次才暴露。
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

/** 拼一份标准 /health 响应（默认 200 + status=ok + 模型已加载）。 */
private fun resp(
    body: String,
    code: Int = 200,
    reason: String = "OK",
    http: String = "HTTP/1.1",
): String = "$http $code $reason\r\n" +
    "Content-Type: application/json\r\n" +
    "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
    "Connection: close\r\n\r\n" + body

private const val HEALTH_OK =
    """{"status":"ok","model_loaded":true,"model":"qwen2.5-1.5b","ctx_used":42,"ctx_size":4096,"busy":false,"port":8080}"""

fun main() {
    // ---- 1. 请求报文 ----
    val req = HealthCheck.buildRequest(8080)
    ck("请求行是 GET /health", req.startsWith("GET /health HTTP/1.0\r\n"))
    ck("用 HTTP/1.0：靠 EOF 判结束，不依赖 Content-Length 解析", req.contains("HTTP/1.0"))
    ck("带 Host 头（部分代理/虚拟主机路由需要）", req.contains("Host: 127.0.0.1:8080"))
    ck("声明短连接，与服务器行为一致", req.contains("Connection: close"))
    ck("以空行结束头部（缺了服务器会一直等，表现为「连上了但没响应」）", req.endsWith("\r\n\r\n"))
    ck("端口出现在 Host 头里，用的是传入值", HealthCheck.buildRequest(9099).contains(":9099"))

    // ---- 2. 正常响应 ----
    val ok = HealthCheck.parse(resp(HEALTH_OK), 5)
    ck("解析出 HTTP 200", ok.httpCode == 200)
    ck("解析出 status", ok.status == "ok")
    ck("解析出 model_loaded", ok.modelLoaded == true)
    ck("解析出模型名", ok.modelDesc == "qwen2.5-1.5b")
    ck("解析出 ctx_used / ctx_size", ok.ctxUsed == 42 && ok.ctxSize == 4096)
    ck("解析出 busy", ok.busy == false)
    ck("解析出 port", ok.port == 8080)
    ck("无技术性错误", ok.transportError == null)
    ck("健康响应判定为 ALIVE", HealthCheck.verdict(ok) == HealthCheck.Verdict.ALIVE)
    ck("报告首行是存活结论", HealthCheck.format(ok, "127.0.0.1", 8080).startsWith("✅ 存活"))
    ck("报告里带探测 URL 与端口", HealthCheck.format(ok, "127.0.0.1", 8080).contains("http://127.0.0.1:8080/health"))
    ck("服务自报端口与探测端口一致时不报不一致",
        !HealthCheck.format(ok, "127.0.0.1", 8080).contains("不一致"))
    ck("服务自报端口与探测端口不同时要明说（否则用户会拿错端口排查）",
        HealthCheck.format(ok, "127.0.0.1", 9090).contains("不一致"))

    // ---- 3. 换行风格容错：只给 \n 的响应不能解析失败 ----
    val lf = "HTTP/1.1 200 OK\nContent-Type: application/json\n\n$HEALTH_OK"
    ck("纯 \\n 换行也能解析出状态行与正文",
        HealthCheck.parse(lf, 1).httpCode == 200 && HealthCheck.parse(lf, 1).status == "ok")

    // ---- 4. 模型未加载：服务活着但不能推理，必须降级而不是全绿 ----
    val noModel = HealthCheck.parse(
        resp("""{"status":"ok","model_loaded":false,"model":null,"ctx_used":0,"ctx_size":0,"busy":false,"port":8080}"""), 3)
    ck("模型未加载 -> DEGRADED（status=ok 也不能算全绿）",
        HealthCheck.verdict(noModel) == HealthCheck.Verdict.DEGRADED)
    ck("模型未加载的报告不说存活", !HealthCheck.format(noModel, "127.0.0.1", 8080).startsWith("✅"))
    ck("模型未加载的报告点明生成会 503",
        HealthCheck.format(noModel, "127.0.0.1", 8080).contains("503"))
    ck("model=null 不产生脏字符串 \"null\"", noModel.modelDesc == null)

    // ---- 5. status 非 ok ----
    val bad = HealthCheck.parse(resp("""{"status":"error","model_loaded":true}"""), 2)
    ck("status=error -> DEGRADED", HealthCheck.verdict(bad) == HealthCheck.Verdict.DEGRADED)
    val noStatus = HealthCheck.parse(resp("""{"model_loaded":true}"""), 2)
    ck("缺 status 字段 -> DEGRADED（不能默认成 ok）",
        HealthCheck.verdict(noStatus) == HealthCheck.Verdict.DEGRADED)

    // ---- 6. 非 200 ----
    val e503 = HealthCheck.parse(resp("""{"error":{"message":"模型未加载"}}""", 503, "Service Unavailable"), 1)
    ck("HTTP 503 -> DEGRADED", HealthCheck.verdict(e503) == HealthCheck.Verdict.DEGRADED)
    ck("非 200 时保留响应体原文（错误信息要能带出来）", e503.body.contains("模型未加载"))
    val e404 = HealthCheck.parse(resp("""{"error":{"message":"not found: /health"}}""", 404, "Not Found"), 1)
    ck("HTTP 404 -> DEGRADED（服务在，但端点不对）",
        HealthCheck.verdict(e404) == HealthCheck.Verdict.DEGRADED)

    // ---- 7. 响应根本不是 JSON（连到了别的进程） ----
    val html = HealthCheck.parse(resp("<html>hello</html>"), 1)
    ck("响应体不是 JSON -> 不可达（不能当存活）",
        HealthCheck.verdict(html) == HealthCheck.Verdict.UNREACHABLE)
    ck("非 JSON 时说明原因", html.transportError?.contains("JSON") == true)
    ck("非 JSON 时保留原文", html.body.contains("hello"))

    // ---- 8. 连状态行都没有 ----
    val junk = HealthCheck.parse("garbage", 1)
    ck("无 HTTP 状态行 -> 不可达", HealthCheck.verdict(junk) == HealthCheck.Verdict.UNREACHABLE)
    ck("无状态行时说明原因", junk.transportError?.contains("状态行") == true)
    val empty = HealthCheck.parse("", 1)
    ck("空响应 -> 不可达", HealthCheck.verdict(empty) == HealthCheck.Verdict.UNREACHABLE)
    ck("空响应也有原因说明（不能只给个空格子）", !empty.transportError.isNullOrBlank())

    // ---- 9. 真连一次：正常服务 ----
    ServerSocket(0).use { ss ->
        val port = ss.localPort
        val t = Thread {
            try {
                ss.accept().use { sock ->
                    val ins = sock.getInputStream()
                    // 读到请求头结束为止（不读完，模拟真实的短连接服务）
                    val sb = StringBuilder()
                    while (!sb.endsWith("\r\n\r\n")) {
                        val b = ins.read()
                        if (b < 0) break
                        sb.append(b.toChar())
                    }
                    sock.getOutputStream().apply {
                        write(resp(HEALTH_OK).toByteArray(Charsets.UTF_8)); flush()
                    }
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        val r = HealthCheck.probe("127.0.0.1", port)
        ck("真发请求能拿到并解析 200/ok", r.httpCode == 200 && r.status == "ok")
        ck("真发请求判定为 ALIVE", HealthCheck.verdict(r) == HealthCheck.Verdict.ALIVE)
        ck("真发请求有耗时记录（UI 要显示它）", r.elapsedMs >= 0)
        ck("真发请求时正文不含响应头（不能把头部当正文解析）",
            !r.body.contains("Content-Type"))
        t.join(1000)
    }

    // ---- 10. 真连一次：端口没人监听 -> 连接被拒，且必须快速返回 ----
    val deadPort = ServerSocket(0).use { it.localPort }  // 关掉后该端口即无人监听
    val t0 = System.currentTimeMillis()
    val refused = HealthCheck.probe("127.0.0.1", deadPort, timeoutMs = 1500)
    val cost = System.currentTimeMillis() - t0
    ck("端口未监听 -> 不可达", HealthCheck.verdict(refused) == HealthCheck.Verdict.UNREACHABLE)
    ck("连接被拒要有可据以行动的说明（端口/绑定地址）",
        refused.transportError?.contains("拒绝") == true)
    ck("连接被拒是立刻返回，不耗满超时（否则用户以为在卡死）", cost < 1500)

    // ---- 11. 真连一次：连上但不回数据 -> 读超时，不能被当成假死 ----
    ServerSocket(0).use { ss ->
        val port = ss.localPort
        val hold = Thread {
            try { ss.accept().use { Thread.sleep(600) } } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        val t1 = System.currentTimeMillis()
        val hang = HealthCheck.probe("127.0.0.1", port, timeoutMs = 300)
        val cost1 = System.currentTimeMillis() - t1
        ck("连上但不回数据 -> 不可达", HealthCheck.verdict(hang) == HealthCheck.Verdict.UNREACHABLE)
        ck("读超时说明是「端口在但无人应答」（正是要区分的假活态）",
            hang.transportError?.contains("超时") == true)
        ck("探测受超时约束，不会一直等（$cost1 ms）", cost1 < 2000)
        hold.interrupt()
    }

    // ---- 12. 判定优先级：没收到响应时，就算 transportError 里提到 ok 也不能算存活 ----
    ck("白纸黑字：200 + 非 JSON 正文不能算可达（判定顺序的第一道门）",
        HealthCheck.verdict(HealthCheck.parse(resp("<html>portal</html>"), 1)) == HealthCheck.Verdict.UNREACHABLE)
    // 服务自报的端口与探测端口不一致时必须点出来（改过端口没重启就会这样）
    val portMismatch = HealthCheck.parse(
        resp("""{"status":"ok","model_loaded":true,"model":"m","ctx_used":0,"ctx_size":4096,"busy":false,"port":9999}"""), 1)
    ck("服务自报端口与探测端口不一致时报告里点明",
        HealthCheck.format(portMismatch, "127.0.0.1", 8080).contains("不一致"))
    ck("端口一致时不产生噪音",
        !HealthCheck.format(ok, "127.0.0.1", 8080).contains("不一致"))

    // ---- 13. 报告必须回答"现在能不能用"，不能只有结论没有依据 ----
    val aliveReport = HealthCheck.format(ok, "127.0.0.1", 8080)
    ck("存活报告带耗时（用户要判断是慢还是死）", aliveReport.contains("ms"))
    ck("存活报告带模型名", aliveReport.contains("qwen2.5-1.5b"))
    ck("不可达报告带失败原因（只说失败等于没说）",
        HealthCheck.format(junk, "127.0.0.1", 8080).contains("原因："))
    ck("busy=true 时报告点明正在生成",
        HealthCheck.format(HealthCheck.parse(
            resp("""{"status":"ok","model_loaded":true,"model":"m","busy":true,"port":8080}"""), 1),
            "127.0.0.1", 8080).contains("生成进行中"))
    ck("status=ok 但缺 model_loaded 字段要明说无法确认",
        HealthCheck.format(HealthCheck.parse(resp("""{"status":"ok","port":8080}"""), 1),
            "127.0.0.1", 8080).contains("model_loaded"))
    ck("无响应永远是不可达（不受任何文本影响）",
        HealthCheck.verdict(HealthCheck.parse("", 1, "status ok")) == HealthCheck.Verdict.UNREACHABLE)

    // ---- 14. 回显正文上限：常态响应必须完整回显，残片绝不能悄悄发出去 ----
    //
    // 两条都来自真机反馈（2026-09-20）：
    //   ① 用户贴出的「响应原文」被切在第 200 字符，而真机上 /health 的正文约 221 字符
    //      —— 截出来的残片天然非法 JSON，报告里却挂着"响应体不是合法 JSON"这句警告，
    //      于是一份**报告自己造的截断**被读成了服务端在返回坏 JSON；
    //   ② 反方向也不行：回显的是完整正文时，报告后面照样跟着"截断处不是响应的问题"，
    //      在"对方回的是 HTML"这种结论下，这句免责声明在解释一段不存在的东西。
    // 判据不是"少显示一段没关系"，而是**给出去的证据必须能被独立复核**：
    // 要么完整，要么明说截断，且不在没有截断时凭空解释截断。
    val longBody = "{\"status\":\"ok\",\"model_loaded\":false,\"model\":null,\"model_desc\":\"" +
        "x".repeat(HealthCheck.BODY_PREVIEW_CHARS * 2) + "\"}"
    val longR = HealthCheck.parse(resp(longBody), 1)
    val longReport = HealthCheck.format(longR, "127.0.0.1", 8080)
    ck("超长正文的回显不超过上限（不能让几十 KB 的 HTML 灌进结论区）",
        longReport.length < longBody.length)
    ck("超长正文的回显必须标注已截断（否则复制出来的是天然非法 JSON）",
        longReport.contains("已截断"))
    ck("截断说明要带上完整长度（用户据此知道原响应是完好的）",
        longReport.contains(longBody.length.toString()))
    ck("截断说明要澄清不是服务端的问题（否则红字结论会被读反）",
        longReport.contains("不是响应的问题"))
    // 关键：真机那份 221 字符的 /health 正文必须**完整**出现在报告里 ——
    // 上限存在的理由是拦 HTML，不是拦本服务自己的响应。
    val realWorld = """{"status":"ok","model_loaded":true,"model":"qwen3 0.6B Q8_0",""" +
        """"ctx_used":39,"ctx_size":8192,"busy":false,"port":8083,"generating":false,""" +
        """"cancelled":false,"kv_cache_valid":true,"kv_reuse_tokens":0,"kv_prefill_tokens":5}"""
    val realR = HealthCheck.parse(resp(realWorld), 2)
    ck("真机那份 221 字符 /health 正文是合法 JSON（不是服务端的错）", realR.jsonParsed)
    ck("真机正文长度在本版上限之内（这就是 200 -> 4096 要修的事）",
        realWorld.length < HealthCheck.BODY_PREVIEW_CHARS)
    ck("真机正文的判定是 ALIVE（解析与判定都没被长度影响）",
        HealthCheck.verdict(realR) == HealthCheck.Verdict.ALIVE)
    // 顺手钉住：能复现用户那份报告的状态（DEGRADED）。
    // 用户的「探测失败」正是这种形状：它根本不是解析失败，是模型/状态没给全。
    // 注意比对的必须是**降级后**的这份正文：`realWorld` 里是 model_loaded=true，
    // 拿它去比于是一辈子不命中——这正是此前那条陈旧断言报红的原因（不是代码错，
    // 是断言自己期待了一份报告里不可能出现的字符串）。
    val degradedRealBody = realWorld.replace("\"model_loaded\":true", "\"model_loaded\":false")
    val degradedReal = HealthCheck.parse(resp(degradedRealBody), 4)
    val degradedReport = HealthCheck.format(degradedReal, "127.0.0.1", 8083)
    ck("复现用户那份报告：回显的正文必须是完整 221 字符，不带截断标注",
        degradedReport.contains(degradedRealBody) && !degradedReport.contains("已截断"))
    ck("正文完整且非 JSON 结论时不谈截断（免责声明只在真截断时给）",
        HealthCheck.format(HealthCheck.parse(resp("<html>portal</html>"), 1), "127.0.0.1", 8080)
            .let { it.contains("未被截断") && !it.contains("已截断") })
    ck("正文与判定一致（合法 JSON 时被降级）要说明结论不来自这段预览",
        degradedReport.contains("与判定一致"))
    // 反例：正文没超上限时**不得**出现"已截断"，那是纯噪音，还会让人以为响应残缺。
    ck("短文正文不得误报截断",
        !HealthCheck.format(HealthCheck.parse(resp(HEALTH_OK), 1), "127.0.0.1", 8080).contains("已截断"))
    // 【已随实现更新：方案 1】此前这里断言「（全绿也）回显响应原文」——理由是
    // 用户点存活探测想拿一份可复制的证据。方案 1 改了这件事，理由也换了：
    // 真机那份 /health 正文 1249 字符 / 51 个字段，**根本没到 [BODY_PREVIEW_CHARS]
    // 的截断线**，于是整份原件一字不落灌进了报告 —— 用户看到的"信息太乱"就是它。
    // 现在的口径是：**全绿时不再复述原件**（那一坨字段对"接下来该做什么"没有任何
    // 指向），改成一句"完整响应去哪儿看"；**异常时照旧回显**（那时原文就是
    // "到底哪里不对"的唯一证据）。
    // 判据因此钉的是**这两面**：全绿 -> 不复述、且点明去哪儿看；异常 -> 照旧回显。
    val aliveBodyReport = HealthCheck.format(HealthCheck.parse(resp(HEALTH_OK), 1), "127.0.0.1", 8080)
    ck("全绿的存活报告不再复述整份响应原文（那 51 个字段就是信息太乱的来源）",
        !aliveBodyReport.contains("响应原文"))
    ck("全绿的存活报告要说清完整响应去哪儿看（不是简单删掉原文）",
        aliveBodyReport.contains("完整响应不在此复述") && aliveBodyReport.contains("/health"))
    ck("全绿时不再回显 body 原件",
        !aliveBodyReport.contains(HEALTH_OK))
    // 反例：**异常**（非 JSON / 降级）时必须照旧回显原文，否则用户拿着
    // "不可达"三个字没法问 —— 这也是"证据必须能被独立复核"的落点。
    val portalBody = HealthCheck.format(HealthCheck.parse(resp("<html>portal</html>"), 1), "127.0.0.1", 8080)
    ck("异常（非 JSON）时**照旧**回显原文（证据必须能被独立复核）",
        portalBody.contains("响应原文") && portalBody.contains("portal"))

    // ---- Issue #159：冻结痕迹（/health 的四个新字段）----
    //
    // 这四条的现场价值：后台被冻过时**连接不断、写不抛异常、日志不写**，
    // 解冻后"探活成功"与"从没被冻过"读数完全一样。若解析侧把它们吃掉，
    // "后台到底有没有被限制"就又只能靠体感描述 —— 这正是本轮要终结的那种处境。
    val frozen = HealthCheck.parse(
        resp("""{"status":"ok","model_loaded":true,"model":"m","ctx_used":1,"ctx_size":4096,""" +
            """"busy":false,"port":8080,"frozen_ms":63000,"frozen_count":3,""" +
            """"frozen_total_ms":185000,"restart_pending":true}"""), 5)
    ck("解析出 frozen_ms", frozen.frozenMs == 63000L)
    ck("解析出 frozen_count", frozen.frozenCount == 3)
    ck("解析出 frozen_total_ms", frozen.frozenTotalMs == 185000L)
    ck("解析出 restart_pending", frozen.restartPending == true)
    val frozenReport = HealthCheck.format(frozen, "127.0.0.1", 8080)
    ck("报告回显「上次被冻 N 秒」", frozenReport.contains("上次 63s"))
    ck("报告回显累计冻结次数", frozenReport.contains("累计 3 次"))
    ck("重启欠账必须说清楚（否则与「重启判据没生效」同形）",
        frozenReport.contains("欠一次重启"))
    // 反例：没检出过冻结时**不得**多挂一行（每次探测都多一行无信息的字 = 噪音）。
    val cleanReport = HealthCheck.format(HealthCheck.parse(resp(HEALTH_OK), 1), "127.0.0.1", 8080)
    ck("没被冻过时不回显冻结行（避免噪音）", !cleanReport.contains("后台冻结"))
    ck("没重启欠账时不回显该行", !cleanReport.contains("欠一次重启"))
    // 老服务端没有这四个字段时必须是 null（不能默认成 0 —— 那是"报了一个假的没发生"）。
    val legacy = HealthCheck.parse(resp(HEALTH_OK), 1)
    ck("缺字段时报 null（不伪造成 0）",
        legacy.frozenMs == null && legacy.frozenCount == null &&
            legacy.frozenTotalMs == null && legacy.restartPending == null)

    // ---- 15. 逐位比对那行必须**按取样的组换措辞** ----
    // 两组样本回答**两个不同的问题**、方向相反：等长组按构造必然稳，
    // 长短悬殊组才是"换批大小会不会改答案"要问的那组。同一句
    // "整批逐位相同，批大小是纯加速旋钮"跟在两组后面时**字面一样、含义相反**
    // —— 而真机现场（PR #301）正是拿等长组那句去答长短悬殊那组的问题。
    val lsOk = HealthCheck.parse(resp(
        """{"status":"ok","model_loaded":true,"embed_parity_rows":4,"embed_parity_identical":4,""" +
            """"embed_parity_different":0,"embed_parity_verdict":"identical",""" +
            """"embed_parity_group":"长短悬殊"}"""), 1)
    // ⚠ 这几条问的是**措辞本身**，所以显式打开原始读数（0.9.231 起它们
    //   默认不进报告正文 —— 见「16.」那一段；措辞逻辑没搬走，只是默认不展示）。
    val lsRep = HealthCheck.format(lsOk, "127.0.0.1", 8080, showRawEmbedProbe = true)
    ck("长短悬殊组全同 -> 说「纯加速旋钮」", lsRep.contains("纯加速"))
    ck("报告写明读数取自哪一组（长短悬殊）", lsRep.contains("取自长短悬殊"))

    val slOk = HealthCheck.parse(resp(
        """{"status":"ok","model_loaded":true,"embed_parity_rows":4,"embed_parity_identical":4,""" +
            """"embed_parity_different":0,"embed_parity_verdict":"identical",""" +
            """"embed_parity_group":"等长"}"""), 1)
    val slRep = HealthCheck.format(slOk, "127.0.0.1", 8080, showRawEmbedProbe = true)
    // 等长组全同时**不许**说"批大小是纯加速旋钮"（那会被读成长短悬殊也稳）——
    // 它只说明批布局本身不改语义，答不了"换批大小会不会改答案"。
    ck("等长组全同 -> **不说**「纯加速旋钮」", !slRep.contains("纯加速"))
    ck("等长组全同 -> 点明它答不了那句（要长短悬殊那组）", slRep.contains("答不了"))

    val noGroup = HealthCheck.parse(resp(
        """{"status":"ok","model_loaded":true,"embed_parity_rows":4,"embed_parity_identical":4,""" +
            """"embed_parity_different":0,"embed_parity_verdict":"identical"}"""), 1)
    val ngRep = HealthCheck.format(noGroup, "127.0.0.1", 8080, showRawEmbedProbe = true)
    // 旧服务端没这个字段时**不猜**：给最弱的一句，不承诺"纯加速旋钮"。
    ck("缺 group 字段 -> 不承诺「纯加速旋钮」", !ngRep.contains("纯加速"))
    ck("缺 group 字段 -> 说清不能外推", ngRep.contains("不能据此推出"))

    // ---- 16. 那十几格原始读数**不进报告正文**，但**完整落进日志** ----
    // 用户的原话是"信息太乱了，对用户也没实际用处"。要分开的**不是"报不报"**，
    // 是"报给谁看"：报告正文（用户）只留能直接决定下一步的结论；日志（排查者）
    // 一格不少；/health 响应体一个字段没动。所以这里两个方向都要钉：
    //   · 默认报告里**看不见** embed_parity_* / embed_retrieve_* 那几行；
    //   · 同样这份 Result，rawEmbedProbeLines() **落得出**那些读数（"完整信息不丢"）。
    val rawR = HealthCheck.parse(resp(
        """{"status":"ok","model_loaded":true,""" +
            """"embed_parity_rows":4,"embed_parity_identical":3,"embed_parity_different":1,""" +
            """"embed_parity_verdict":"different","embed_parity_group":"长短悬殊",""" +
            """"embed_parity_repeat_measured":true,"embed_parity_repeat_not_identical":0,""" +
            """"embed_parity_repeat_batched_measured":true,""" +
            """"embed_parity_repeat_batched_not_identical":0,""" +
            """"embed_retrieve_measured":true,"embed_retrieve_queries":3,""" +
            """"embed_retrieve_answer_changed":0,"embed_retrieve_order_changed":0,""" +
            """"embed_retrieve_batch_base":8,"embed_retrieve_batch_other":4,""" +
            """"embed_retrieve_topk":3}"""), 1)
    val rawRep = HealthCheck.format(rawR, "127.0.0.1", 8080)
    ck("日常探活报告里**不出现**「逐位比对」那行", !rawRep.contains("向量逐位比对"))
    ck("日常探活报告里**不出现**「检索对照」那行", !rawRep.contains("检索对照"))
    ck("日常探活报告里**不出现**「真的不同 1 条」那类原始格", !rawRep.contains("真的不同"))
    // 但"能直接指导下一步"的那几格必须留着（它们是搬走的理由的反面）——
    // 库认的批大小不同就整库不可用，这条是报告里最该看见的一句。
    val idxR = HealthCheck.parse(resp(
        """{"status":"ok","model_loaded":true,"embed_batch_size":4,""" +
            """"embed_batch_size_index":1,"embed_batch_size_index_kb":"ceshi2"}"""), 1)
    ck("可指导下一步的「建索引时用的批大小」仍在报告里（搬走的是原始读数，不是结论）",
        HealthCheck.format(idxR, "127.0.0.1", 8080).contains("建索引时用的批大小"))
    // 完整读数**必须有个落点**：日志那三行要真的落得出上面那些数
    // （只从报告里删干净、却没落地 = "算出来没人读"，比铺在报告里更坏）。
    val rawLines = HealthCheck.rawEmbedProbeLines(rawR).joinToString("\n")
    ck("完整读数落进日志：逐位比对的条数/取自哪一组", rawLines.contains("embed_parity") &&
        rawLines.contains("真的不同=1") && rawLines.contains("取自=长短悬殊"))
    ck("完整读数落进日志：「测没测」与「几条不同」分开", rawLines.contains("做没做=true") &&
        rawLines.contains("不是逐位相同=0"))
    ck("完整读数落进日志：检索对照的查询数/集合变没变/两侧批大小",
        rawLines.contains("embed_retrieve") && rawLines.contains("查询=3") &&
            rawLines.contains("集合变了=0") && rawLines.contains("批大小=8 vs 4"))
    // 没跑过时**不落**"没跑过"这种噪音行（噪音会被跳过，同其它几格的口径）。
    ck("没跑过对照时不落噪音行", HealthCheck.rawEmbedProbeLines(
        HealthCheck.parse(resp(HEALTH_OK), 1)).isEmpty())

    // ---- 17. A 档新增：**向量模型**那一格（用户点名要加） ----
    // 对话模型加载与否由「模型已加载」那行回答，可**嵌入模型是另一份**：
    // 双常驻的四种组合（只有对话 / 只有嵌入 / 都有 / 都没有）在 `model_loaded`
    // 上**长得一模一样**。而缺嵌入模型时检索必然全空 —— 用户看到"一条都搜不到"
    // 时，`model_loaded=true` 会把他引去查知识库，其实是嵌入那份没加载。
    val embOk = HealthCheck.parse(resp(
        """{"status":"ok","model_loaded":true,"model":"qwen3 4B","embed_loaded":true,""" +
            """"embed_model":"Qwen3-Embedding-0.6B","embed_dim":1024}"""), 1)
    ck("解析出 embed_loaded", embOk.embedLoaded == true)
    ck("解析出 embed_model", embOk.embedModelDesc == "Qwen3-Embedding-0.6B")
    ck("解析出 embed_dim", embOk.embedDim == 1024)
    val embRep = HealthCheck.format(embOk, "127.0.0.1", 8080)
    ck("报告里出现「向量模型」这一行（用户点名要加的那格）", embRep.contains("向量模型"))
    ck("向量模型那行报名字", embRep.contains("Qwen3-Embedding-0.6B"))
    ck("向量模型那行报维度（维度是索引指纹的一部分）", embRep.contains("dim=1024"))
    // ⚠ 它与对话模型是**两份**：报告里两个字面都要在，别让人以为是一个。
    ck("向量模型与对话模型分行报（不是同一份）",
        embRep.contains("模型已加载：qwen3 4B") && embRep.contains("向量模型已加载"))

    // 缺嵌入模型（检索必然全空）时必须**说清后果**，而不是只报一个"未加载"。
    val embMissing = HealthCheck.parse(resp(
        """{"status":"ok","model_loaded":true,"model":"qwen3 4B","embed_loaded":false,""" +
            """"embed_model":"","embed_dim":0}"""), 1)
    val embMissingRep = HealthCheck.format(embMissing, "127.0.0.1", 8080)
    ck("向量模型未加载 -> 明说检索必然全空", embMissingRep.contains("检索必然全空"))
    ck("向量模型未加载 -> 给出后果（/v1/embeddings 503）",
        embMissingRep.contains("503"))
    // 反例：旧服务端**没报**这三个字段时**不得**多挂一行（null = 没报，不是 false；
    // 挂一行"向量模型：未报"是噪音，而噪音会被跳过 —— 与冻结那几格同一条口径）。
    ck("旧服务端没报这三个字段时不回显该行（避免噪音）",
        !HealthCheck.format(legacy, "127.0.0.1", 8080).contains("向量模型"))
    ck("缺字段时报 null（不伪造成未加载）",
        legacy.embedLoaded == null && legacy.embedModelDesc == null && legacy.embedDim == null)

    println()
    println(if (fail == 0) "=== HealthCheckTest 全部通过 ===" else "=== HealthCheckTest 失败 $fail 项 ===")
    if (fail != 0) kotlin.system.exitProcess(1)
}
