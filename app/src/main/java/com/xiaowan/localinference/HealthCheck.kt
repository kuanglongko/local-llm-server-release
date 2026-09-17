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
        /** 原始响应体；非 200 时也保留，便于把服务器的错误信息带出来。 */
        val body: String,
        /** /health 的 status 字段；缺失即为 null。 */
        val status: String?,
        val modelLoaded: Boolean?,
        val modelDesc: String?,
        val ctxUsed: Int?,
        val ctxSize: Int?,
        val busy: Boolean?,
        val port: Int?,
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
            return Result(null, "", null, null, null, null, null, null, null,
                transportError ?: "未收到任何响应数据", elapsedMs)
        }
        // 状态行：`HTTP/1.1 200 OK`。拿不到就不是合法 HTTP 响应，
        // 但仍把原文留在 body 里——"连上了但对方不是 HTTP 服务"是常见误配。
        val lineEnd = raw.indexOf("\r\n").let { if (it >= 0) it else raw.indexOf('\n') }
        if (lineEnd <= 0) {
            return Result(null, raw.trim(), null, null, null, null, null, null, null,
                transportError ?: "响应不含 HTTP 状态行", elapsedMs)
        }
        val statusLine = raw.substring(0, lineEnd).trim()
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (code == null) {
            return Result(null, raw.trim(), null, null, null, null, null, null, null,
                "状态行无法解析: ${statusLine.take(80)}", elapsedMs)
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
            return Result(code, body, null, null, null, null, null, null, null,
                (err?.plus("; ") ?: "") + "响应体不是合法 JSON", elapsedMs)
        }
        // 逐字段 opt：/health 是跨版本接口，缺字段要报缺、不能当默认值。
        // 用 isNull 判断，避免 JSONObject.NULL 被 optString 变成 "null" 这种脏值。
        fun str(k: String): String? = if (j.isNull(k)) null else j.optString(k, "")?.ifEmpty { null }
        fun int(k: String): Int? = if (j.isNull(k)) null else j.optInt(k, Int.MIN_VALUE)
            .let { if (it == Int.MIN_VALUE) null else it }
        fun bool(k: String): Boolean? = if (j.isNull(k)) null else j.optBoolean(k, false)
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

    /** 多行、可直接贴给别人的探测报告；参数里的 host/port 由调用方按实际绑定给出。 */
    fun format(r: Result, host: String, port: Int): String {
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
        r.busy?.let { if (it) sb.append("\n生成进行中（busy=true）") }
        // 只有 status=ok 却没带 model_loaded 才算异常；status 本来就非 ok 时上面已说明过了
        if (r.httpCode == 200 && r.status?.lowercase() == "ok" && r.modelLoaded == null) {
            sb.append("\n⚠ 响应里没有 model_loaded 字段，无法确认模型状态")
        }
        if (r.body.isNotBlank() && verdict(r) != Verdict.ALIVE) {
            sb.append("\n响应原文：${r.body.replace("\n", " ").take(200)}")
        }
        return sb.toString()
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
        val t0 = System.currentTimeMillis()
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
                    return Result(null, "", null, null, null, null, null, null, null,
                        "读取超时（连接已建立但 ${timeoutMs} ms 内无任何响应字节，" +
                            "端口在监听却无人 accept —— 典型的假活态）",
                        System.currentTimeMillis() - t0)
                }
            }
        } catch (e: Throwable) {
            val ms = System.currentTimeMillis() - t0
            return Result(null, raw, null, null, null, null, null, null, null,
                transportReason(e), ms)
        }
        return parse(raw, System.currentTimeMillis() - t0)
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
