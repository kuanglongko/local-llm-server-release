package com.xiaowan.localinference

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * 零依赖 HTTP API 层：以进程内引擎直连提供 OpenAI 兼容的 server 能力，
 * 不 exec 外部 llama-server 二进制（部分 OEM 的 W^X 策略禁止执行应用数据目录下的文件）。
 *
 * 绑定 127.0.0.1（端口可自定义，默认 8080），OpenAI 兼容端点：
 * GET  /health                 -> 引擎/模型/上下文状态
 * GET  /v1/models              -> 当前加载的模型（单实例）
 * POST /v1/chat/completions    -> 对话补全，stream=true 时 SSE 流式；认 `tools`（工具调用）
 * POST /v1/completions         -> raw prompt 补全（不走 chat 模板，适合测速）
 *
 * 并发模型：每连接一线程（accept 不串行阻塞）；生成期间（busy）新请求返回 503；
 * 生成循环持 LlmEngine.genLock，与 UI 测试页互斥。
 * JSON 解析用平台自带 org.json，响应手工拼装（零第三方依赖约束）。
 */
object HttpApi {
    private const val TAG = "HttpApi"
    const val DEFAULT_PORT = 8080

    /** 端口可自定义；InferenceService 启动前从 ModelStore 注入。 */
    @Volatile var PORT: Int = DEFAULT_PORT

    /** true = 绑定 0.0.0.0 允许局域网访问（无鉴权）；默认 false 只绑回环。 */
    @Volatile var bindAll: Boolean = false
    /** 全局默认关闭思考（客户端 enable_thinking / chat_template_kwargs / reasoning_effort 可覆盖）。 */
    @Volatile var disableThinkingDefault: Boolean = true

    /** 取本机在局域网的首个非回环 IPv4，用于通知/UI 展示；找不到返回 null。 */
    fun lanIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) { null }

    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    /** 是否有 HTTP 生成任务进行中（供 UI 侧卸载/停止/重载前做忙检查）。 */
    val isGenerating: Boolean get() = busy.get()

    @Volatile private var server: ServerSocket? = null

    /** Service 加载模型后设置，用于 /v1/models 与日志。 */
    @Volatile var currentModel: String? = null

    /** 启动方（InferenceService）注入；为空时 /v1/models 优雅降级，绝不抛异常。 */
    @Volatile var appContext: android.content.Context? = null

    val isRunning: Boolean get() = running.get()

    private fun emitLog(s: String) {
        Log.i(TAG, s)
        try { LlmEngine.logSink?.invoke("[http] $s") } catch (_: Exception) {}
        // 探针模式下还要落到 native fd：HTTP 线程上的崩溃点与它前面那句日志
        // 只隔几微秒，走 JVM 文件写会丢。
        try { LlmEngine.probeMark("[http] $s") } catch (_: Exception) {}
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            var ss: ServerSocket? = null
            try {
                // bindAll=false 时绑 IPv4 回环。注意：部分 OEM 的 getLoopbackAddress()
                // 会返回 ::1，导致 IPv4 客户端全部拒连；bindAll=true 绑 0.0.0.0 供局域网访问（无鉴权）。
                val bindAddr = if (bindAll) "0.0.0.0" else "127.0.0.1"
                // 改为「先构造后绑定」，设置 SO_REUSEADDR —— stop/start 竞态后
                // 残留 TIME_WAIT/socket 不再导致 BindException。
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(InetAddress.getByName(bindAddr), PORT), 16)
                    // accept 5s 醒一次检查 running —— 消灭 stop() 竞态窗口下
                    // 「accept 永久阻塞 + socket 泄漏」的僵尸 LISTEN（端口在但没人 accept）。
                    soTimeout = 5_000
                }
                server = ss
                emitLog("listening http://$bindAddr:$PORT (model=${currentModel ?: "none"})")
                while (running.get()) {
                    val s = try { ss?.accept() } catch (e: java.net.SocketTimeoutException) {
                        continue  // 心跳超时属正常，回循环头检查 running
                    } catch (e: Exception) {
                        // accept 异常必须留痕（此前 catch(_){break} 静默死亡无日志）
                        if (running.get()) emitLog("accept error: ${e.message}")
                        break
                    } ?: break  // 监听 socket 已被 stop() 清空 → 退出
                    // 每连接一线程。原单线程串行下，任一连接挂住（半开/慢发送）
                    // 会阻塞 accept 长达 soTimeout，后续连接全部饿死（health/models 全超时）。
                    Thread({
                        try { handleConn(s) } catch (t: Throwable) {
                            Log.w(TAG, "conn error: ${t.message}")
                            try { s.close() } catch (_: Exception) {}
                        }
                    }, "http-conn").apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (running.get()) emitLog("server error: ${e.message}")
            } finally {
                // 任何退出路径都关闭监听 socket —— 修复 fd 泄漏成「僵尸 LISTEN」
                // （现场特征：HTTP 线程已死亡但端口仍处 LISTEN，客户端表现为超时而非 refused）
                try { ss?.close() } catch (_: Exception) {}
                running.set(false)
                server = null
            }
        }.apply { name = "llm-http"; isDaemon = true }.start()
        startWatchdog()
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // ---- 看门狗自愈 ----
    // 背景：部分 OEM 的后台冻结策略（切后台即冻、亮屏后也冻）与
    // accept 线程异常死亡都会造成「端口 LISTEN 但没人 accept」的假活态。
    // 每 30s 自连探活，连续 2 次失败自动重启监听 —— 断联自愈窗口 30~60s。
    private val watchdogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startWatchdog() {
        if (!watchdogRunning.compareAndSet(false, true)) return
        Thread {
            var failStreak = 0
            try {
                while (true) {
                    Thread.sleep(30_000)
                    if (!running.get()) { failStreak = 0; continue }  // 服务未运行，仅休眠待命
                    if (probeHealthy()) {
                        if (failStreak >= 2) emitLog("[watchdog] 自检恢复正常")
                        failStreak = 0
                        continue
                    }
                    failStreak++
                    emitLog("[watchdog] 自连探活失败 $failStreak/2 (port=$PORT)")
                    if (failStreak >= 2) {
                        emitLog("[watchdog] 监听假活，自动重启 listener（模型不受影响）")
                        stop()
                        Thread.sleep(500)  // 等旧 accept 线程走完 finally，避免 running 竞态
                        start()
                        failStreak = 0
                        Thread.sleep(2_000)  // 新 listener 稳定窗口
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                watchdogRunning.set(false)
            }
        }.apply { name = "llm-watchdog"; isDaemon = true }.start()
    }

    /** 自连探活：TCP 连上并发 GET /health，2s 内收到任意响应字节即健康。 */
    private fun probeHealthy(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 2_000)
            s.soTimeout = 2_000
            s.getOutputStream().apply {
                write("GET /health HTTP/1.0\r\n\r\n".toByteArray())
                flush()
            }
            s.getInputStream().read(ByteArray(16)) > 0
        }
    } catch (_: Exception) {
        false
    }

    // ---- 请求处理 ----

    private class Req(val method: String, val path: String, val body: String)

    private fun handleConn(s: Socket) {
        s.soTimeout = 15_000  // 15s 内未收到完整请求即断开；生成期只写不读，不受影响
        s.use { sock ->
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            val req = parseReq(ins) ?: return
            // /health 与 /v1/models 为高频轮询端点，只写 logcat、不推 UI 日志。
            // 历史缺陷：每个请求都走 logSink → ui.post → renderLog（120 行 TextView 全量重排），
            // 刷新风暴会占满主线程 → 整个应用卡顿 → 流式生成停摆、客户端断联。
            if (req.method == "GET" && (req.path.startsWith("/health") || req.path.startsWith("/v1/models"))) {
                Log.i(TAG, "${req.method} ${req.path}")
            } else {
                emitLog("${req.method} ${req.path}")
            }
            when {
                req.method == "GET" && req.path.startsWith("/health") ->
                    writeJson(out, 200, healthJson())
                req.method == "GET" && req.path.startsWith("/v1/models") ->
                    writeJson(out, 200, modelsJson())
                req.method == "POST" && req.path.startsWith("/v1/chat/completions") ->
                    handleChat(req, out)
                req.method == "POST" && req.path.startsWith("/v1/completions") ->
                    handleCompletion(req, out)
                else ->
                    writeJson(out, 404, errJson("not found: ${req.path}"))
            }
        }
    }

    private fun parseReq(ins: InputStream): Req? {
        val first = readLine(ins) ?: return null
        val sp = first.split(' ')
        if (sp.size < 2) return null
        val method = sp[0]
        val path = sp[1]
        var contentLength = 0
        while (true) {
            val line = readLine(ins) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().lowercase() == "content-length") {
                contentLength = line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength.coerceAtMost(4 * 1024 * 1024))
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else ""
        return Req(method, path, body)
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    // ---- OpenAI 兼容端点 ----

    private fun healthJson(): String {
        val loaded = LlmEngine.hasModel
        val desc = if (loaded) LlmEngine.modelDesc().toJsonStr() else "null"
        val ctxUsed = if (loaded) LlmEngine.contextUsed() else 0
        val ctxSize = if (loaded) LlmEngine.contextSize() else 0
        return """{"status":"ok","model_loaded":$loaded,"model":$desc,""" +
            """"ctx_used":$ctxUsed,"ctx_size":$ctxSize,"busy":${busy.get()},"port":$PORT}"""
    }

    private fun modelsJson(): String {
        // org.json 结构化输出：模型列表带路径/大小/时间/量化等富信息。
        // 注意：toJsonStr() 自带引号，手拼 JSON 时再嵌一层引号会产生非法 JSON，
        // 客户端就读不到模型列表了。
        // 模型未加载 -> 空列表（服务仍可用，生成请求将得到 503 提示）
        if (!LlmEngine.hasModel) {
            val empty = JSONObject()
            empty.put("object", "list")
            empty.put("data", JSONArray())
            empty.put("models", JSONArray())
            return empty.toString()
        }
        val ctx = appContext
        val files = try {
            if (ctx != null) ModelStore.list(ctx) else emptyList()
        } catch (_: Throwable) { emptyList() }
        val cur = currentModel
        val root = JSONObject()
        root.put("object", "list")
        val modelsArr = JSONArray()
        val dataArr = JSONArray()
        if (files.isEmpty()) {
            // 降级：拿不到模型库时只报当前加载模型（别名），保证连接可用
            cur?.let { alias ->
                dataArr.put(JSONObject().put("id", alias).put("object", "model").put("owned_by", "local-llm-server"))
                modelsArr.put(JSONObject().put("name", alias).put("model", alias).put("type", "model"))
            }
        } else {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val seen = HashSet<String>()
            for (f in files) {
                val alias = ModelStore.aliasOf(ctx!!, f.name)
                // 主标识用别名（Ollama 风格短名，不暴露内部路径）；
                // 重名时依次用"文件名去后缀/完整路径"兜底，保证 id 唯一
                val id = listOf(alias, f.name.removeSuffix(".gguf"), f.absolutePath)
                    .first { seen.add(it) }
                val quant = Regex("(?i)(iq\\d+[a-z0-9_]*|q\\d+[a-z0-9_]*|f16|bf16|f32)").find(f.name)?.value?.uppercase() ?: ""
                val params = Regex("(\\d+(?:\\.\\d+)?)\\s*[Bb]").find(f.name.removeSuffix(".gguf"))?.let { m -> m.groupValues[1] + "B" } ?: ""
                modelsArr.put(JSONObject()
                    .put("name", id)
                    .put("model", id)
                    .put("modified_at", fmt.format(java.util.Date(f.lastModified())))
                    .put("size", f.length())
                    .put("digest", "")
                    .put("type", "model")
                    .put("description", f.name)
                    .put("tags", JSONArray())
                    .put("capabilities", JSONArray().put("completion"))
                    .put("parameters", "")
                    .put("details", JSONObject()
                        .put("parent_model", "")
                        .put("format", "gguf")
                        .put("family", "")
                        .put("families", JSONArray().put(""))
                        .put("parameter_size", params)
                        .put("quantization_level", quant)))
                dataArr.put(JSONObject()
                    .put("id", id)
                    .put("aliases", JSONArray().put(alias))
                    .put("tags", JSONArray())
                    .put("object", "model")
                    .put("created", f.lastModified() / 1000)
                    .put("owned_by", "llamacpp")
                    .put("meta", JSONObject()
                        .put("size", f.length())
                        .put("ftype", quant)))
            }
            // 当前加载模型不在库里时（异常情况），补一个别名条目保证客户端可选
            val listedAliases = files.mapNotNull { f -> ctx?.let { c -> ModelStore.aliasOf(c, f.name) } }
            if (cur != null && cur !in listedAliases) {
                dataArr.put(JSONObject().put("id", cur).put("object", "model").put("owned_by", "local-llm-server"))
            }
        }
        root.put("models", modelsArr)
        root.put("data", dataArr)
        return root.toString()
    }

    private fun handleChat(req: Req, out: OutputStream) {
        if (!LlmEngine.hasModel) { writeJson(out, 503, errJson("模型未加载：先在 App 内选择模型并启动服务")); return }
        if (!busy.compareAndSet(false, true)) { writeJson(out, 503, errJson("server busy（上一请求生成中）")); return }
        try {
            val j = JSONObject(req.body)
            val stream = j.optBoolean("stream", false)
            // 采样参数统一走 SamplingParams 校验：非法值直接 400，不再静默降级（历史坑：
            // top_p=0 被静默关掉、repeat_penalty<1 语义反转、min_p 默认值与 UI 不一致）。
            // max_tokens 也在此处按 1..8192 校验（此前用 coerceIn 静默钳制）。
            // fromRequest 已逐字段校验并给出原因；返回 null 即取值非法，直接 400。
            val (sp, spErr) = SamplingParams.fromRequest(j)
            if (sp == null) { writeJson(out, 400, errJson(spErr ?: "采样参数非法")); return }
            val maxTok = sp.maxTokens
            val modelName = j.optString("model", "local").ifEmpty { "local" }

            val msgs = ArrayList<Pair<String, String>>()
            val arr = j.optJSONArray("messages")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = m.optString("role", "user")
                var content = m.optString("content")
                val ca = m.optJSONArray("content") // 多模态数组：取 text 段拼接
                if (ca != null) {
                    val sb = StringBuilder()
                    for (k in 0 until ca.length()) {
                        val o = ca.optJSONObject(k)
                        if (o != null && o.optString("type") == "text") sb.append(o.optString("text"))
                    }
                    if (sb.isNotEmpty()) content = sb.toString()
                }
                msgs.add(role to content)
            }
            if (msgs.isEmpty()) { writeJson(out, 400, errJson("messages is empty")); return }

            // ---- 工具调用（OpenAI tools / function calling）----
            // 请求带 tools 时必须走「工具感知」模板渲染：工具定义要进 prompt，
            // 否则模型不知道自己有哪些函数可用，永远吐不出 tool_calls。
            // 解析逻辑在 ToolCalls（纯函数，可离线单测），这里只做取用
            val hasTools = ToolCalls.hasTools(j)
            val toolsJson = if (hasTools) ToolCalls.toolsJson(j) else null
            LlmEngine.probeMark("[http] 请求分类：has_tools=$hasTools stream=$stream " +
                    "tools_count=${if (hasTools) j.optJSONArray("tools")?.length() ?: 0 else 0} " +
                    "body_len=${req.body.length} thread=${Thread.currentThread().name}")
            if (hasTools) LlmEngine.probeMark("[http] tools 原文=${toolsJson?.take(600)}")
            val toolChoice = ToolCalls.parseToolChoice(j, hasTools)
            val parallelToolCalls = j.optBoolean("parallel_tool_calls", true)
            // toolChoice 带 tools 时已由 parseToolChoice 保证非 null（缺省归一成 "auto"），
            // 这里不再显示 null，避免"看起来没传"这种会被误读的日志。
            if (hasTools) emitLog("tools: ${j.optJSONArray("tools")!!.length()} 个，choice=${toolChoice ?: "auto"}")

            // 思考控制 —— 请求显式参数 > 全局默认；仅模板含 enable_thinking 时注入空 think 块（软开关）
            val kwEt = j.optJSONObject("chat_template_kwargs")?.opt("enable_thinking")
            val reqThinking: Boolean? = when {
                j.has("enable_thinking") -> j.optBoolean("enable_thinking", true)
                kwEt is Boolean -> kwEt
                kwEt is Number -> kwEt.toInt() != 0
                kwEt is String -> kwEt.lowercase() !in listOf("false", "0", "off")
                j.optString("reasoning_effort", "").isNotEmpty() -> j.optString("reasoning_effort") != "none"
                else -> null
            }
            val thinkingOn = reqThinking ?: !disableThinkingDefault
            // 带 tools 时优先进工具感知路径；渲染失败（无模型 / 模板不支持）回落普通路径，
            // 与「模型不支持工具调用时退化成普通对话」的承诺一致，不会让请求整体失败。
            var prompt = if (toolsJson != null)
                    LlmEngine.applyChatTemplateWithTools(msgs, toolsJson, toolChoice, parallelToolCalls, addAss = true)
                else null
            if (prompt == null) prompt = LlmEngine.applyChatTemplate(msgs, addAss = true)
            if (!thinkingOn && LlmEngine.chatTemplate().contains("enable_thinking")) {
                prompt = prompt + "<think>\n\n</think>\n\n"  // 追加在末尾（紧贴assistant生成后缀），置于开头会诱导模型模仿输出空think块
                emitLog("thinking off (soft switch)")
            }
            val id = "chatcmpl-local-${System.currentTimeMillis()}"
            val created = System.currentTimeMillis() / 1000
            var n = 0

            synchronized(LlmEngine.genLock) {
                LlmEngine.newSampler(sp.temp, sp.topP, sp.minP, seed = sp.seed,
                    topK = sp.topK, repPenalty = sp.repeatPenalty, penaltyN = sp.repeatLastN,
                    freqPenalty = sp.freqPenalty, presencePenalty = sp.presencePenalty)
                emitLog("sampling ${sp.describe()}")
                val err = LlmEngine.startCompletion(prompt, maxTok)
                if (err != null) { writeJson(out, 400, errJson(err)); return }
                val sb = StringBuilder()
                val sbR = StringBuilder()
                if (stream) {
                    sseHead(out)
                    sseEvent(out, chatChunkJson(id, modelName, created, """{"role":"assistant","content":""}""", null))
                }
                // <think> 状态机 —— 正文发 content；思考段实时转发 reasoning_content（不混入正文、零延迟）；
                // 思考期间 SSE 注释心跳防客户端超时
                var sawOpen = false
                var inThink = false
                var lastBeat = System.currentTimeMillis()
                val carry = StringBuilder()
                fun partialTag(s: StringBuilder, tag: String): Int {
                    val str = s.toString()
                    for (k in minOf(tag.length - 1, str.length) downTo 1) if (str.endsWith(tag.substring(0, k))) return k
                    return 0
                }
                // 带 tools 时**不实时下发 content**：工具调用语法（<tool_call> / [TOOL_CALLS] 等）
                // 与正文同处一条输出流，边生成边发会把语法标记当正文吐给客户端。改为整段生成结束后
                // 先按模板解析出 tool_calls，再决定发 tool_calls 增量块还是纯 content。
                // reasoning_content 不受影响：思考段不会是工具调用。
                val bufferContent = toolsJson != null && stream
                fun emit(text: String, asReason: Boolean) {
                    if (text.isEmpty()) return
                    if (stream && (!bufferContent || asReason)) sseEvent(out, chatChunkJson(id, modelName, created,
                        if (asReason) """{"reasoning_content":${text.toJsonStr()}}""" else """{"content":${text.toJsonStr()}}""", null))
                }
                fun feed(piece: String) {
                    sb.append(piece)
                    carry.append(piece)
                    while (carry.isNotEmpty()) {
                        if (!sawOpen) {
                            val i = carry.indexOf("<think>")
                            if (i >= 0) { if (i > 0) emit(carry.substring(0, i), false); carry.delete(0, i + 7); sawOpen = true; inThink = true; continue }
                            val keep = partialTag(carry, "<think>")
                            val e = carry.length - keep
                            if (e > 0) { emit(carry.substring(0, e), false); carry.delete(0, e) }
                            break
                        } else if (inThink) {
                            val c2 = carry.indexOf("</think>")
                            if (c2 >= 0) { if (c2 > 0) emit(carry.substring(0, c2), true); carry.delete(0, c2 + 8); inThink = false; continue }
                            val keep = partialTag(carry, "</think>")
                            val e = carry.length - keep
                            if (e > 0) { emit(carry.substring(0, e), true); carry.delete(0, e) }
                            break
                        } else {
                            emit(carry.toString(), false); carry.setLength(0); break
                        }
                    }
                }
                while (n < maxTok) {
                    val piece = LlmEngine.step() ?: break
                    n++
                    if (stream && inThink && System.currentTimeMillis() - lastBeat > 800) { sseComment(out); lastBeat = System.currentTimeMillis() }
                    if (piece.isNotEmpty()) feed(piece)
                }
                if (carry.isNotEmpty()) emit(carry.toString(), sawOpen && inThink)
                carry.setLength(0)
                // 非流式 —— 思考段剥离：正文留在 sb（content），思考放 sbR（reasoning_content）
                var reasonField = ""
                if (!stream && sb.indexOf("<think>") >= 0) {
                    val raw = sb.toString()
                    val open = raw.indexOf("<think>")
                    val close = raw.indexOf("</think>", open)
                    val before = if (open > 0) raw.substring(0, open) else ""
                    val reason = if (close >= 0) raw.substring(open + 7, close) else raw.substring(open + 7)
                    val after = if (close >= 0) raw.substring(close + 8) else ""
                    val r = reason.trim()
                    if (r.isNotEmpty()) reasonField = "," + "\"reasoning_content\":" + r.toJsonStr()
                    sb.setLength(0)
                    sb.append((before + after).trim())
                }
                // ---- 工具调用解析 ----
                // 带 tools 的请求，输出里可能含 tool_calls，语法随模板而定，交给 native 归一。
                // 命中时正文通常为空，finish_reason 必须从 stop 改成 tool_calls，
                // 否则客户端（OpenAI SDK / LangChain 等）不会去执行工具。
                var toolCallsJson: String? = null
                if (toolsJson != null) {
                    LlmEngine.probeMark("[http] 生成结束，进入工具解析：n=$n 输出长度=${sb.length}")
                    val parsed = LlmEngine.parseToolCalls(sb.toString(), toolsJson)
                    if (parsed != null) {
                        toolCallsJson = parsed.second
                        // content 只留解析出的正文：工具语法标记不能当正文吐给客户端
                        sb.setLength(0)
                        sb.append(parsed.first)
                        emitLog("tool_calls: ${JSONArray(toolCallsJson).length()} 个")
                    }
                }
                if (stream) {
                    val fin = if (toolCallsJson != null) "tool_calls" else "stop"
                    // 缓冲模式下补发正文（带 tools 但模型没调工具）
                    if (bufferContent && toolCallsJson == null && sb.isNotEmpty())
                        sseEvent(out, chatChunkJson(id, modelName, created, """{"content":${sb.toString().toJsonStr()}}""", null))
                    // 流式下按 OpenAI 约定发 tool_calls 增量块（index/id/type/function）。
                    // 已整段解析完成，故一次性下发而不是逐 token 拼装——客户端按 index 聚合，结果一致。
                    // id 兜底与 native 侧同一条规则：parse 结果缺 id 时用 call_<下标>，
                    // 不能在这里另写一份默认值，否则同一份数据在两条路径上会算出不同的 id。
                    if (toolCallsJson != null) {
                        for (delta in ToolCalls.streamDeltas(JSONArray(toolCallsJson)))
                            sseEvent(out, chatChunkJson(id, modelName, created, delta, null))
                    }
                    sseEvent(out, chatChunkJson(id, modelName, created, "{}", fin))
                    sseEvent(out, "[DONE]")
                    sseEnd(out)
                } else {
                    val usage = usageJson(LlmEngine.contextUsed(), n)
                    val fin = if (toolCallsJson != null) "tool_calls" else "stop"
                    // 有 tool_calls 时 content 按 OpenAI 规范可为 null
                    val contentField = if (toolCallsJson != null && sb.isEmpty()) "null" else sb.toString().toJsonStr()
                    // 必须走 ToolCalls 重新包装：native 给的是解析器原生形状（只有 id/name/arguments），
                    // OpenAI 线上要的是 {"id":..,"type":"function","function":{..}}，
                    // 直接透传会让 SDK 认不出 tool_calls 而静默丢掉。
                    val toolField = if (toolCallsJson != null) ToolCalls.messageToolCallsField(JSONArray(toolCallsJson)) else ""
                    writeJson(out, 200,
                        """{"id":"$id","object":"chat.completion","created":$created,"model":"$modelName",""" +
                        """"choices":[{"index":0,"message":{"role":"assistant","content":$contentField$reasonField$toolField},"finish_reason":"$fin"}],"usage":$usage}""")
                }
            }
            emitLog("chat ok: $n tok, model=$modelName")
            LlmEngine.probeMark("[http] handleChat 正常结束 n=$n")
        } catch (t: Throwable) {
            LlmEngine.probeMark("[http] handleChat 抛出：${t.javaClass.name}: ${t.message}")
            try { writeJson(out, 500, errJson(t.message ?: "internal error")) } catch (_: Exception) {}
        } finally {
            busy.set(false)
        }
    }

    private fun handleCompletion(req: Req, out: OutputStream) {
        if (!LlmEngine.hasModel) { writeJson(out, 503, errJson("模型未加载")); return }
        if (!busy.compareAndSet(false, true)) { writeJson(out, 503, errJson("server busy")); return }
        try {
            val j = JSONObject(req.body)
            val stream = j.optBoolean("stream", false)
            // 与 /v1/chat/completions 同一套解析与校验，默认值不会两边漂移
            // fromRequest 已逐字段校验并给出原因；返回 null 即取值非法，直接 400。
            val (sp, spErr) = SamplingParams.fromRequest(j)
            if (sp == null) { writeJson(out, 400, errJson(spErr ?: "采样参数非法")); return }
            val maxTok = sp.maxTokens
            val modelName = j.optString("model", "local").ifEmpty { "local" }
            val prompt = when (val p = j.opt("prompt")) {
                is String -> p
                is JSONArray -> (0 until p.length()).mapNotNull { p.opt(it) as? String }.joinToString("\n")
                else -> ""
            }
            if (prompt.isEmpty()) { writeJson(out, 400, errJson("prompt is empty")); return }

            val id = "cmpl-local-${System.currentTimeMillis()}"
            val created = System.currentTimeMillis() / 1000
            var n = 0

            synchronized(LlmEngine.genLock) {
                LlmEngine.newSampler(sp.temp, sp.topP, sp.minP, seed = sp.seed,
                    topK = sp.topK, repPenalty = sp.repeatPenalty, penaltyN = sp.repeatLastN,
                    freqPenalty = sp.freqPenalty, presencePenalty = sp.presencePenalty)
                emitLog("sampling ${sp.describe()}")
                val err = LlmEngine.startCompletion(prompt, maxTok)
                if (err != null) { writeJson(out, 400, errJson(err)); return }
                val sb = StringBuilder()
                if (stream) {
                    sseHead(out)
                }
                while (n < maxTok) {
                    val piece = LlmEngine.step() ?: break
                    n++
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        if (stream) sseEvent(out, compChunkJson(id, modelName, created, piece.toJsonStr(), null))
                    }
                }
                if (stream) {
                    sseEvent(out, compChunkJson(id, modelName, created, "\"\"", "stop"))
                    sseEvent(out, "[DONE]")
                    sseEnd(out)
                } else {
                    val usage = usageJson(LlmEngine.contextUsed(), n)
                    writeJson(out, 200,
                        """{"id":"$id","object":"text_completion","created":$created,"model":"$modelName",""" +
                        """"choices":[{"index":0,"text":${sb.toString().toJsonStr()},"finish_reason":"stop"}],"usage":$usage}""")
                }
            }
            emitLog("completion ok: $n tok")
        } catch (t: Throwable) {
            try { writeJson(out, 500, errJson(t.message ?: "internal error")) } catch (_: Exception) {}
        } finally {
            busy.set(false)
        }
    }

    // ---- JSON / SSE 工具 ----

    private fun chatChunkJson(id: String, model: String, created: Long, deltaJson: String, finish: String?): String {
        val fin = if (finish == null) "null" else "\"$finish\""
        return """{"id":"$id","object":"chat.completion.chunk","created":$created,"model":"$model",""" +
            """"choices":[{"index":0,"delta":$deltaJson,"finish_reason":$fin}]}"""
    }

    private fun compChunkJson(id: String, model: String, created: Long, textJson: String, finish: String?): String {
        val fin = if (finish == null) "null" else "\"$finish\""
        return """{"id":"$id","object":"text_completion","created":$created,"model":"$model",""" +
            """"choices":[{"index":0,"text":$textJson,"finish_reason":$fin}]}"""
    }

    private fun usageJson(promptTok: Int, completionTok: Int): String =
        """{"prompt_tokens":$promptTok,"completion_tokens":$completionTok,"total_tokens":${promptTok + completionTok}}"""

    private fun errJson(msg: String): String =
        """{"error":{"message":${msg.toJsonStr()},"type":"local_inference_error"}}"""

    private fun String.toJsonStr(): String {
        val sb = StringBuilder(length + 16).append('"')
        for (c in this) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    private fun writeJson(out: OutputStream, code: Int, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${b.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(b)
        out.flush()
    }

    private fun reason(c: Int) = when (c) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"
        503 -> "Service Unavailable"; else -> "Error"
    }

    private fun sseHead(out: OutputStream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** SSE 注释心跳（思考生成期间防客户端空闲超时）。必须走 chunked 分帧：裸写会破坏 Transfer-Encoding: chunked 导致客户端截断。 */
    private fun sseComment(out: OutputStream) {
        try {
            val c = ": ping\n\n".toByteArray(Charsets.UTF_8)
            out.write(c.size.toString(16).toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write(c)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {}
    }

    private fun sseEvent(out: OutputStream, data: String) {
        val payload = "data: $data\n\n".toByteArray(Charsets.UTF_8)
        out.write(payload.size.toString(16).toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.write(payload)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sseEnd(out: OutputStream) {
        out.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
