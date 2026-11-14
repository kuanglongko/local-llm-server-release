package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 把 [HttpApi.handleChat] 写出的 **OpenAI 形状 HTTP 响应** 转换成
 * **Anthropic Messages 形状**，并写进真正的 socket 输出流。
 *
 * 它被设计成一个 [OutputStream]：`handleChat` 对它一无所知，只管往里写。
 * 三件事在这里完成：
 *
 *  1. **HTTP 头缓冲区**：先不写任何字节，直到收到 `\r\n\r\n`。
 *     这样才能知道原响应是不是 chunked、状态码是多少 —— 然后**丢弃原头**，
 *     改写为 Anthropic 需要的头（`Content-Type`、`Transfer-Encoding` /
 *     `Content-Length`、`request-id`、`anthropic-version`，并**保留**
 *     `X-Slot-Id` / `X-Relay-Coord` / `X-Local-Ctx` 这类本端自有头）。
 *  2. **body 解码**：
 *     - 流式：解 chunked 分帧 → 逐条 `data:` 行 → 翻成 Anthropic SSE 事件
 *     - 非流式：缓冲整段 body（`Content-Length` 可能被改写，必须等全量）
 *       → 一次性翻译 → 重写 `Content-Length` 后写出
 *     - 错误（状态码 ≥ 400）：把 OpenAI `{"error":{...}}` 翻成
 *       Anthropic `{"type":"error","error":{...}}`
 *  3. **安全失败**：客户端已断开（499 等）时，任何写异常都被吞掉 ——
 *     与 [HttpApi] 里 `sseComment` 的 `runCatching` 同一原则。
 */
class AnthropicTranslator(
    private val real: OutputStream,
    /** 本次请求是否流式（由转换层自己掌控，与请求体一致）。 */
    private val stream: Boolean,
    /** 对外暴露的 model 名（Anthropic 响应里要回显）。 */
    private val model: String,
    /** 随机 request-id，Anthropic 响应头要求。 */
    private val requestId: String,
    /** CORS 头（透传，便于浏览器调用）。 */
    private val corsOrigin: String? = null,
) : OutputStream() {

    // ---- HTTP 头缓冲：收齐 `\r\n\r\n` 之前一个字节都不往下写 ----
    private val headBuf = ByteArrayOutputStream()
    private var headDone = false
    private var statusCode = 200
    private var originalChunked = false

    // ---- body 侧 ----
    /** 非流式/错误：整段缓冲。流式：这里放「未处理完的分帧残片」。 */
    private val bodyBuf = ByteArrayOutputStream()

    // ---- chunked 解帧状态（仅流式）----
    private var chunkRemaining = 0
    private var readingChunkSize = true
    private val lineAcc = StringBuilder()

    // ---- Anthropic SSE 会话状态 ----
    private var messageStartSent = false
    private var finished = false

    /**
     * 下一个 content block 的 index。Anthropic 的 block 按 0,1,2… 递增编号，
     * 且**必须**连续 —— text block 用掉 0 之后，tool_use block 就得从 1 开始。
     * （首版用的是单个 `contentBlockOpen` 布尔，暗含「只有一个 block」的假设，
     * 一旦要发 tool_use 就会 index 撞车。）
     */
    private var nextBlockIndex = 0

    /** 当前**已 start 但未 stop** 的 block index；null 表示没有打开的 block。 */
    private var openBlockIndex: Int? = null

    /** 已累积的输出 token（用于 message_delta.usage.output_tokens）。 */
    private var outputTokens = 0

    /** 本次响应里已经下发的 tool_use 个数（用于生成唯一 toolu_... id）。 */
    private var toolUseSeq = 0

    /** 输入 token（从 OpenAI usage.prompt_tokens 取，若没有则 0）。 */
    private var inputTokens = 0

    // =========================================================================
    // OutputStream 接口
    // =========================================================================

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (!headDone) {
            headBuf.write(b, off, len)
            tryConsumeHead()
            return
        }
        feedBody(b, off, len)
    }

    override fun flush() {
        try {
            if (headDone) real.flush()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        try {
            // 头还没收全就关（极少见：handler 直接抛异常且没写任何东西）：
            // 给一个最小可用的 Anthropic 错误响应，避免客户端拿到半截连接。
            if (!headDone) {
                emitError(500, "internal_error", "服务端未产生完整响应")
                return
            }
            // 非流式 / 错误：此刻 body 已完整，做最终翻译并写出。
            if (!originalChunked) {
                finalizeBufferedBody()
            }
            real.flush()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // HTTP 头解析与改写
    // =========================================================================

    private fun tryConsumeHead() {
        val bytes = headBuf.toByteArray()
        val end = indexOfHeaderEnd(bytes) ?: return
        // 头收齐了
        headDone = true
        val headText = String(bytes, 0, end.first, StandardCharsets.ISO_8859_1)
        val lines = headText.split("\r\n")
        if (lines.isNotEmpty()) {
            // 状态行：HTTP/1.1 200 OK
            val parts = lines[0].split(" ")
            statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 200
        }
        // 解析原有头（保留本端自有头）
        var slotId: String? = null
        var relayCoord: String? = null
        var localCtx: String? = null
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (name) {
                "transfer-encoding" -> if (value.contains("chunked", true)) originalChunked = true
                "x-slot-id" -> slotId = value
                "x-relay-coord" -> relayCoord = value
                "x-local-ctx" -> localCtx = value
                // 本端自有的 prompt token 数：流式 `message_start` 发生在生成之前，
                // 等不到收尾的 usage chunk，所以提前用这个头把 input_tokens 交给翻译层。
                "x-local-prompt-tokens" -> value.toIntOrNull()?.let { if (it >= 0) inputTokens = it }
            }
        }

        // 头之后可能已经带了一部分 body（同一次 write）。
        val bodyStart = end.second
        val bodyCarry = if (bodyStart < bytes.size) {
            bytes.copyOfRange(bodyStart, bytes.size)
        } else null

        // 若是错误状态码 -> 一律按「非流式 JSON」处理（错误体是完整 JSON）
        val isError = statusCode >= 400
        if (isError) originalChunked = false

        writeHead(isError = isError, slotId = slotId, relayCoord = relayCoord, localCtx = localCtx)

        if (bodyCarry != null && bodyCarry.isNotEmpty()) {
            feedBody(bodyCarry, 0, bodyCarry.size)
        }
    }

    /** 返回 (headersEndIndex, bodyStartIndex)；未找到返回 null。 */
    private fun indexOfHeaderEnd(b: ByteArray): Pair<Int, Int>? {
        for (i in 0 until b.size - 3) {
            if (b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() &&
                b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()
            ) {
                return Pair(i + 4, i + 4)
            }
        }
        return null
    }

    private fun writeHead(isError: Boolean, slotId: String?, relayCoord: String?, localCtx: String?) {
        val sb = StringBuilder()
        val effectiveStatus = statusCode
        val statusText = statusTextOf(effectiveStatus)
        sb.append("HTTP/1.1 ").append(effectiveStatus).append(' ').append(statusText).append("\r\n")
        if (isError) {
            sb.append("Content-Type: application/json; charset=utf-8\r\n")
        } else if (stream) {
            sb.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        } else {
            sb.append("Content-Type: application/json; charset=utf-8\r\n")
        }
        sb.append("Cache-Control: no-cache\r\n")
        // Anthropic 客户端会读这两个头
        sb.append("request-id: ").append(requestId).append("\r\n")
        sb.append("anthropic-version: ").append(AnthropicApi.SUPPORTED_VERSION).append("\r\n")
        // 保留本端自有头（客户端续聊 / relay 坐标要用）
        slotId?.let { sb.append("X-Slot-Id: ").append(it).append("\r\n") }
        relayCoord?.let { sb.append("X-Relay-Coord: ").append(it).append("\r\n") }
        localCtx?.let { sb.append("X-Local-Ctx: ").append(it).append("\r\n") }
        corsOrigin?.let { sb.append("Access-Control-Allow-Origin: ").append(it).append("\r\n") }
        // 流式：chunked（内容长度未知）；非流式/错误：Content-Length 待定 -> 由 finalize 决定，
        // 这里先不写 Content-Length，等 finalize 时用真实长度补齐（见 finalizeBufferedBody）。
        if (stream && !isError) {
            sb.append("Transfer-Encoding: chunked\r\n")
            sb.append("\r\n")
            real.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
            real.flush()
        } else {
            // 非流式/错误：头先写出「不含 Content-Length」，body 由 finalize 追加。
            // 为简单稳妥，这里把头整体缓存进 pendingHead，等 finalize 时一起写。
            pendingHead = sb
        }
    }

    private var pendingHead: StringBuilder? = null

    // =========================================================================
    // body 处理
    // =========================================================================

    private fun feedBody(b: ByteArray, off: Int, len: Int) {
        if (originalChunked && statusCode < 400) {
            feedChunkedStream(b, off, len)
        } else {
            bodyBuf.write(b, off, len)
        }
    }

    /** 非流式 / 错误：body 已完整 -> 翻译 + 补 Content-Length + 写出。 */
    private fun finalizeBufferedBody() {
        val raw = bodyBuf.toString(StandardCharsets.UTF_8.name())
        val head = pendingHead?.toString() ?: "HTTP/1.1 200 OK\r\n\r\n"
        pendingHead = null

        val (status, bodyText) = if (statusCode >= 400) {
            // 错误：OpenAI -> Anthropic 错误形状
            val (t, msg) = parseOpenAiError(raw)
            statusCode to anthropicErrorBody(t, msg)
        } else if (stream) {
            // 理论上不会到（流式走 chunked）；兜底当非流式处理
            finalizeFromOpenAiJson(raw)
        } else {
            finalizeFromOpenAiJson(raw)
        }

        val bodyBytes = bodyText.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder()
        // 重写状态行（状态码以原始为准，但错误体换了形状）
        out.append("HTTP/1.1 ").append(status).append(' ').append(statusTextOf(status)).append("\r\n")
        out.append("Content-Type: application/json; charset=utf-8\r\n")
        out.append("Cache-Control: no-cache\r\n")
        out.append("request-id: ").append(requestId).append("\r\n")
        out.append("anthropic-version: ").append(AnthropicApi.SUPPORTED_VERSION).append("\r\n")
        // 保留自有头（从 pendingHead 里抠出来）
        appendOwnHeaders(out, head)
        corsOrigin?.let { out.append("Access-Control-Allow-Origin: ").append(it).append("\r\n") }
        out.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        out.append("\r\n")
        real.write(out.toString().toByteArray(StandardCharsets.ISO_8859_1))
        real.write(bodyBytes)
        real.flush()
    }

    private fun appendOwnHeaders(out: StringBuilder, head: String) {
        for (line in head.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            when (name.lowercase()) {
                "x-slot-id", "x-relay-coord", "x-local-ctx" -> out.append(line).append("\r\n")
            }
        }
    }

    /** 把 OpenAI 非流式 `chat.completion` JSON 翻成 Anthropic `message` JSON。 */
    private fun finalizeFromOpenAiJson(raw: String): Pair<Int, String> {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return statusCode to anthropicErrorBody("api_error", "上游返回了无法解析的响应")
        val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        // Android 的 JSONObject.optString(name, fallback) 在值为 JSONObject.NULL
        // 时返回字符串 "null"（不是 fallback）—— 本端 tool_calls 响应的
        // `message.content` 正好是 null。不先判 isNull 的话，会凭空多出一个
        // `{"type":"text","text":"null"}` block，Claude SDK 会把它当正文显示。
        val content = if (msg == null || msg.isNull("content")) "" else msg.optString("content", "")
        val usage = json.optJSONObject("usage")
        val inTok = usage?.optInt("prompt_tokens", 0) ?: 0
        val outTok = usage?.optInt("completion_tokens", 0) ?: 0
        val finish = json.optJSONArray("choices")?.optJSONObject(0)
            ?.optString("finish_reason", "end_turn") ?: "end_turn"
        val stopReason = mapStopReason(finish)
        val id = normalizeMessageId(json.optString("id", ""), requestId)
        // model 优先取上游回显的（与客户端请求一致），缺失时退回本端记录的 model
        val modelName = json.optString("model", model).ifEmpty { model }
        // 非流式也要带 tool_use：首版只取 message.content，
        // 于是 `finish_reason=tool_calls` 时正文空、工具也没了 —— 整个调用丢失。
        val blocks = StringBuilder()
        if (content.isNotEmpty()) {
            blocks.append("""{"type":"text","text":${jsonQuote(content)}}""")
        }
        val calls = msg?.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            val toolBlocks = toolUseBlocksJson(calls, requestId)
                .removePrefix("[").removeSuffix("]")
            if (toolBlocks.isNotEmpty()) {
                if (blocks.isNotEmpty()) blocks.append(',')
                blocks.append(toolBlocks)
            }
        }
        val contentJson = if (blocks.isEmpty()) "[]" else "[$blocks]"
        return statusCode to anthropicMessageBodyFromBlocks(
            id, contentJson, inTok, outTok, stopReason, modelName)
    }

    // =========================================================================
    // chunked 解帧 + SSE 翻译（流式）
    // =========================================================================

    private fun feedChunkedStream(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (readingChunkSize) {
                // ---- 状态 1：读 size 行（十六进制 + 可选扩展），到 '\n' 为止 ----
                val c = b[i].toInt().toChar()
                i++
                if (c == '\r') continue          // size 行的 CR：忽略，等 LF
                if (c != '\n') {
                    lineAcc.append(c)
                    continue
                }
                val sizeLine = lineAcc.toString().trim()
                lineAcc.setLength(0)
                val sizeStr = sizeLine.substringBefore(';').trim()
                val n = sizeStr.toIntOrNull(16)
                if (n == null) {
                    // 分帧坏了：无法继续解析。安全收尾。
                    // 若上一块刚结束、正等着吞 CRLF，这里的空 sizeLine 是合法的
                    // （因为 CRLF 已在下方「状态 0」被吞掉），不会走到这里。
                    emitSseStop()
                    return
                }
                if (n == 0) {
                    // 末块 -> 收尾
                    emitSseStop()
                    return
                }
                chunkRemaining = n
                readingChunkSize = false
            } else if (chunkRemaining > 0) {
                // ---- 状态 2：读一块数据 ----
                val take = minOf(chunkRemaining, end - i)
                processSseBytes(b, i, take)
                i += take
                chunkRemaining -= take
                if (chunkRemaining == 0) {
                    // 数据读完：接下来一定是该块的结尾 CRLF。
                    // 不改状态 —— 下一轮因 `chunkRemaining == 0 && !readingChunkSize`
                    // 自然落到「状态 0」去吞它。
                }
            } else {
                // ---- 状态 0：吞掉刚结束那一块后面的 CRLF（1 或 2 字节）----
                // HTTP/1.1 chunked 规定是 CRLF；兼容只有 LF 的实现。
                // 必须显式吞掉 —— 否则 '\r' 会被当成 size 行，得到空串，
                // toIntOrNull 失败 -> 误判「分帧坏了」-> 提前 emitSseStop()，
                // 症状就是「多帧的流在第一帧后就断」。
                val c = b[i].toInt().toChar()
                if (c == '\r') {
                    i++
                    continue                      // 接着看下一个是不是 '\n'
                }
                if (c == '\n') {
                    i++
                    readingChunkSize = true       // 回到状态 1
                    continue
                }
                // 非 CR/LF：不合规，但别卡死。落回状态 1 重新解释该字节
                // （不消费），让 size 行分支去处理。
                readingChunkSize = true
            }
        }
    }

    /** 累积 SSE 文本，按 `\n` 切行处理。 */
    private val sseAcc = StringBuilder()

    private fun processSseBytes(b: ByteArray, off: Int, len: Int) {
        sseAcc.append(String(b, off, len, StandardCharsets.UTF_8))
        var nl = sseAcc.indexOf("\n")
        while (nl >= 0) {
            val line = sseAcc.substring(0, nl).trimEnd('\r')
            sseAcc.delete(0, nl + 1)
            handleSseLine(line)
            nl = sseAcc.indexOf("\n")
        }
    }

    private fun handleSseLine(line: String) {
        if (line.isEmpty()) return
        // 心跳注释帧（`: ping`）：丢弃
        if (line.startsWith(":")) return
        if (!line.startsWith("data:")) return
        val payload = line.substring(5).trim()
        if (payload.isEmpty()) return
        if (payload == "[DONE]") {
            emitSseStop()
            return
        }
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        translateChunk(json)
    }

    /** OpenAI chunk -> Anthropic SSE 事件。 */
    private fun translateChunk(chunk: JSONObject) {
        // 首个 chunk 时发 message_start
        if (!messageStartSent) {
            emitMessageStart(chunk)
        }
        // usage（部分 chunk 带）必须**先于** choices 判空：
        // OpenAI 的最后一个 usage-only chunk 常常是 `"choices":[]`（甚至没有
        // choices 字段），旧写法在 `?: return` 处就丢掉了它，message_delta
        // 于是永远报 output_tokens=0。
        chunk.optJSONObject("usage")?.let { u ->
            inputTokens = u.optInt("prompt_tokens", inputTokens)
            outputTokens = u.optInt("completion_tokens", outputTokens)
        }
        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta")
        if (delta != null) {
            // 正文
            val content = delta.optString("content", "")
            if (content.isNotEmpty()) {
                emitContentDelta(content)
            }
            // 工具调用：OpenAI `delta.tool_calls[]` -> Anthropic tool_use block。
            // 首版漏了这条分支，症状是流式下 `stop_reason=tool_use` 却没有任何
            // tool_use block —— Claude SDK 直接把这次调用丢掉。
            delta.optJSONArray("tool_calls")?.let { emitToolUseBlocks(it) }
            // reasoning_content（思考段）：Anthropic 无对应增量字段，
            // 第一版按「不暴露思考段」处理（与客户端关思考时的行为一致），
            // 详细说明见 CHANGELOG。这里显式忽略以保证不破坏正文流。
        }
        // finish_reason -> message_delta + message_stop（在 emitSseStop 里统一发）
        val finish = choice.optString("finish_reason", "")
        if (finish.isNotEmpty()) {
            pendingStopReason = mapStopReason(finish)
        }
    }

    private var pendingStopReason: String = "end_turn"

    private fun emitMessageStart(chunk: JSONObject) {
        messageStartSent = true
        val id = normalizeMessageId(chunk.optString("id", ""), requestId)
        val sb = StringBuilder()
        sb.append("event: message_start\ndata: ")
        sb.append("{\"type\":\"message_start\",\"message\":{\"id\":")
        sb.append(jsonQuote(id))
        sb.append(",\"type\":\"message\",\"role\":\"assistant\",\"model\":")
        sb.append(jsonQuote(model))
        sb.append(",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,")
        sb.append("\"usage\":{\"input_tokens\":$inputTokens,\"output_tokens\":0}}}")
        sb.append("\n\n")
        writeChunked(sb.toString())
    }

    private fun emitContentDelta(text: String) {
        // 首个正文块：开 text block（index = 下一个可用）
        if (openBlockIndex == null || !textBlockIsOpen) {
            openBlockIndex = nextBlockIndex++
            textBlockIsOpen = true
            writeChunked(
                "event: content_block_start\ndata: " +
                    """{"type":"content_block_start","index":${openBlockIndex},"content_block":{"type":"text","text":""}}""" + "\n\n"
            )
        }
        writeChunked(
            "event: content_block_delta\ndata: " +
                """{"type":"content_block_delta","index":${openBlockIndex},"delta":{"type":"text_delta","text":${jsonQuote(text)}}}""" + "\n\n"
        )
    }

    /** 当前打开的 block 是不是 text（决定能否继续往里追加 text_delta）。 */
    private var textBlockIsOpen = false

    /**
     * 把一条 OpenAI `delta.tool_calls`（或非流式 message.tool_calls）翻成
     * Anthropic 的 `tool_use` content block。
     *
     * Anthropic 协议里 tool_use 块的三段式：
     *   content_block_start {content_block:{type:"tool_use",id,name,input:{}}}
     *   content_block_delta {delta:{type:"input_json_delta",partial_json:"..."}}
     *   content_block_stop
     *
     * 本端 native 侧**已把 arguments 整段解析好**（不是逐 token 吐），所以这里
     * start→delta(全量)→stop 一气呵成，无需维护半截 JSON 的拼装状态。
     * `input` 起始给 `{}` 是 Anthropic 官方 SDK 的期望（它用 partial_json 增量拼）。
     */
    private fun emitToolUseBlocks(calls: JSONArray) {
        for (k in 0 until calls.length()) {
            val c = calls.optJSONObject(k) ?: continue
            // OpenAI 形状：name / arguments 在 function 层里（本端 ToolCalls.callJson
            // 产出的就是这个形状）。首版曾直接读 c.optString("name")，永远读到空串。
            val fn = c.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            if (name.isEmpty()) continue          // 无名调用无法表示，跳过
            // native 在模型没给 id 时会补占位 `call_<index>`；Anthropic 要求
            // `toolu_...` 且在多次响应间唯一。这里统一改写占位 id，保留模型真给的 id。
            val id = normalizeToolUseId(c.optString("id", ""), requestId, toolUseSeq++)
            val args = fn.optString("arguments", "").ifEmpty { "{}" }

            // tool_use 之前必须先把已开的 text block 关掉（block 不能嵌套）
            closeOpenBlock()

            val idx = nextBlockIndex++
            writeChunked(
                "event: content_block_start\ndata: " +
                    """{"type":"content_block_start","index":$idx,"content_block":{"type":"tool_use","id":${jsonQuote(id)},"name":${jsonQuote(name)},"input":{}}}""" + "\n\n"
            )
            writeChunked(
                "event: content_block_delta\ndata: " +
                    """{"type":"content_block_delta","index":$idx,"delta":{"type":"input_json_delta","partial_json":${jsonQuote(args)}}}""" + "\n\n"
            )
            writeChunked(
                "event: content_block_stop\ndata: " +
                    """{"type":"content_block_stop","index":$idx}""" + "\n\n"
            )
            // 注意：tool_use 块开出即关上，openBlockIndex 保持 null，
            // 于是紧随其后的正文会另起新 block（这正是 Anthropic 期望的语义：
            // 「先思考/说话，再调工具」时正文与工具是不同 block）。
        }
    }

    /** 关闭当前打开的 block（若有）。 */
    private fun closeOpenBlock() {
        val idx = openBlockIndex ?: return
        writeChunked(
            "event: content_block_stop\ndata: " +
                """{"type":"content_block_stop","index":$idx}""" + "\n\n"
        )
        openBlockIndex = null
        textBlockIsOpen = false
    }

    /** 收尾：content_block_stop + message_delta + message_stop（幂等）。 */
    private fun emitSseStop() {
        if (finished) return
        finished = true
        if (!messageStartSent) {
            // 一条正文都没有（空回复 / 立刻结束）：仍要发 message_start，保证
            // Anthropic 客户端拿到结构完整的事件流。
            emitMessageStart(JSONObject())
        }
        // 空回复**不再补一个空 text block**：Anthropic 允许 content 为空数组，
        // 而 `{"type":"text","text":""}` 会被客户端当成一段真实存在的空正文
        // （真机报障：纯工具调用/空回复的 content 里多一个空 text 块）。
        // 只有**真的还有打开的 block** 才发 stop。tool_use 是开出即关上的，
        // 此时 openBlockIndex 已经是 null。
        openBlockIndex?.let { idx ->
            writeChunked(
                "event: content_block_stop\ndata: " +
                    """{"type":"content_block_stop","index":$idx}""" + "\n\n"
            )
            openBlockIndex = null
            textBlockIsOpen = false
        }
        writeChunked(
            "event: message_delta\ndata: " +
                """{"type":"message_delta","delta":{"stop_reason":"$pendingStopReason","stop_sequence":null},"usage":{"output_tokens":$outputTokens}}""" + "\n\n"
        )
        writeChunked(
            "event: message_stop\ndata: " +
                """{"type":"message_stop"}""" + "\n\n"
        )
        // chunked 终止块
        safeWrite("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        safeFlush()
    }

    // =========================================================================
    // 写出工具
    // =========================================================================

    /** 以 chunked 分帧写一段 SSE 文本。 */
    private fun writeChunked(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val header = Integer.toHexString(bytes.size) + "\r\n"
        safeWrite(header.toByteArray(StandardCharsets.ISO_8859_1))
        safeWrite(bytes)
        safeWrite("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun emitError(status: Int, type: String, message: String) {
        val body = anthropicErrorBody(type, message).toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusTextOf(status)).append("\r\n")
        sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("request-id: ").append(requestId).append("\r\n")
        sb.append("anthropic-version: ").append(AnthropicApi.SUPPORTED_VERSION).append("\r\n")
        corsOrigin?.let { sb.append("Access-Control-Allow-Origin: ").append(it).append("\r\n") }
        sb.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        safeWrite(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        safeWrite(body)
        safeFlush()
    }

    private fun safeWrite(b: ByteArray) {
        try {
            real.write(b)
        } catch (_: Exception) {
            // 客户端已断开（499 一类的现场）：静默。与 sseComment 的 runCatching 同源。
        }
    }

    private fun safeFlush() {
        try {
            real.flush()
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // 形状工具（纯函数，便于单测）
    // =========================================================================

    companion object {
        fun anthropicErrorBody(type: String, message: String): String =
            """{"type":"error","error":{"type":${jsonQuote(type)},"message":${jsonQuote(message)}}}"""

        fun anthropicMessageBody(
            id: String,
            text: String,
            inputTokens: Int,
            outputTokens: Int,
            stopReason: String,
            model: String = "local",
        ): String {
            val content = if (text.isEmpty()) "[]" else
                """[{"type":"text","text":${jsonQuote(text)}}]"""
            return anthropicMessageBodyFromBlocks(
                id, content, inputTokens, outputTokens, stopReason, model)
        }

        /**
         * 同上，但 content 直接给**预构造好的 block 数组字面量**。
         *
         * 非流式下正文和 tool_use 都要体现在同一个 `content[]` 里 ——
         * 只用字符串 text 的旧签名表达不了 tool_use，所以拆出这条。
         * `contentJson` 须是合法 JSON 数组字符串（如 `[{...},{...}]`）。
         *
         * 注意**没有**做成同名重载：两个版本都是 (String,String,Int,Int,String,String)，
         * Kotlin 的 JVM 签名相同，仅靠参数名/默认值区分不了 —— 会编译不过。
         */
        fun anthropicMessageBodyFromBlocks(
            id: String,
            contentJson: String,
            inputTokens: Int,
            outputTokens: Int,
            stopReason: String,
            model: String,
        ): String {
            return """{"id":${jsonQuote(id)},"type":"message","role":"assistant",""" +
                """"model":${jsonQuote(model)},"content":$contentJson,""" +
                """"stop_reason":${jsonQuote(stopReason)},"stop_sequence":null,""" +
                """"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens}}"""
        }

        /**
         * 把 OpenAI `message.tool_calls[]` 翻成 Anthropic `tool_use` block 数组字面量。
         *
         * OpenAI: `{"id":..,"type":"function","function":{"name":..,"arguments":"{...}"}}`
         * Anthropic: `{"type":"tool_use","id":..,"name":..,"input":{...}}`
         *
         * `arguments` 是**字符串形式的 JSON**，而 Anthropic 要的是**对象** ——
         * 必须解析一次；解析失败就退化为空对象（宁可丢参数，也不能吐出非法 JSON
         * 让整个响应不可用）。
         */
        fun toolUseBlocksJson(calls: JSONArray, requestId: String = "local"): String {
            val blocks = (0 until calls.length()).mapNotNull { k ->
                val c = calls.optJSONObject(k) ?: return@mapNotNull null
                val fn = c.optJSONObject("function") ?: return@mapNotNull null
                val name = fn.optString("name", "")
                if (name.isEmpty()) return@mapNotNull null
                val id = normalizeToolUseId(c.optString("id", ""), requestId, k)
                val argsRaw = fn.optString("arguments", "")
                val inputJson = runCatching { JSONObject(argsRaw) }.getOrNull()?.toString()
                    ?: "{}"
                """{"type":"tool_use","id":${jsonQuote(id)},"name":${jsonQuote(name)},"input":$inputJson}"""
            }
            return blocks.joinToString(",", "[", "]")
        }

        /**
         * 把上游 id 归一成 Anthropic 的合法形状。
         *
         * 上游（native / OpenAI 兼容层）在模型没给 id 时会补 `call_<index>` 占位；
         * Anthropic 客户端要求 `msg_...` / `toolu_...`，而且工具 id 必须在多次
         * 响应之间唯一。本端随请求生成的 [requestId] 正是为了这个唯一性。
         * 模型真正给了 id（非占位）时原样保留，避免破坏调用方续聊时的配对。
         */
        fun normalizeMessageId(id: String, requestId: String): String {
            if (id.isEmpty()) return "msg_$requestId"
            if (id.startsWith("msg_")) return id
            return "msg_" + safeIdPart(id)
        }

        fun normalizeToolUseId(id: String, requestId: String, index: Int): String {
            // `call_...` 都是 OpenAI 侧的占位/派生 id（native 的 call_<n>，或本层
            // 唯一化后的 call_<hash>_<n>）；Anthropic 要求 toolu_...，一律换掉。
            // 其它 id（例如模型真给的 toolu_real_1）原样保留，续聊才能配对。
            val placeholder = id.isEmpty() || id.startsWith("call_")
            if (!placeholder) return id
            return "toolu_${safeIdPart(requestId)}_$index"
        }

        /** 只保留 id 里安全且可读的字符（Anthropic 的 id 是 `[A-Za-z0-9_-]+`）。 */
        private fun safeIdPart(s: String): String {
            val out = s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
                .joinToString("")
            return out.ifEmpty { "local" }
        }

        /** OpenAI finish_reason -> Anthropic stop_reason。 */
        fun mapStopReason(finish: String): String = when (finish) {
            "stop" -> "end_turn"
            "length" -> "max_tokens"
            "tool_calls", "function_call" -> "tool_use"
            "cancelled" -> "end_turn"
            else -> "end_turn"
        }

        /** 从 OpenAI 错误体里抠出 type/message。 */
        fun parseOpenAiError(raw: String): Pair<String, String> {
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: return "api_error" to raw.ifBlank { "未知错误" }
            val err = json.optJSONObject("error")
            val msg = err?.optString("message", "")?.ifEmpty { raw } ?: raw
            val t = err?.optString("type", "")?.ifEmpty { "api_error" } ?: "api_error"
            return normalizeErrorType(t) to msg
        }

        /** OpenAI 错误 type -> Anthropic 错误 type。 */
        fun normalizeErrorType(t: String): String = when (t) {
            "invalid_request_error", "invalid_request" -> "invalid_request_error"
            "authentication_error", "invalid_api_key" -> "authentication_error"
            "permission_error" -> "permission_error"
            "not_found_error" -> "not_found_error"
            "rate_limit_error", "rate_limit_exceeded" -> "rate_limit_error"
            "overloaded_error" -> "overloaded_error"
            else -> "api_error"
        }

        fun statusTextOf(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            499 -> "Client Closed Request"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }

        /** 转义 JSON 字符串**内容**（不含首尾引号）。`jsonQuote` 与模板字符串共用。 */
        fun escapeJsonContent(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ') {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
            return sb.toString()
        }

        /** 转义并加引号，得到一个可直接嵌入 JSON 的字符串字面量。 */
        fun jsonQuote(s: String): String = "\"" + escapeJsonContent(s) + "\""
    }
}
