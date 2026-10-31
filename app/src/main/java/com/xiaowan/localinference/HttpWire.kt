package com.xiaowan.localinference

import java.io.OutputStream

/**
 * HTTP 响应的**出口层**：状态行 / 头部 / CORS / 错误体。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独一层（第 7 批 H7 第 1 步）
 * ══════════════════════════════════════════════════════════════════════════
 * `HttpApi.kt` 同时是"监听器 + 路由 + 端点 + SSE + 鉴权 + 健康检查"，
 * 六千多行里最容易被顺手绕过的就是**响应头**：多一处自己拼 `HTTP/1.1`，
 * 就多一处漏掉 CORS 或 `X-Slot-Id` 的地方 —— 而它只在浏览器跨源请求
 * 或中间层读坐标时暴露，服务端本身一切正常。
 *
 * 所以这里把三件事收成一个出口：
 *   · [errJson] 只负责"错误体长什么样"（OpenAI 形状）；
 *   · [writeRaw] 是**唯一**拼状态行与公共头的地方（CORS 在这里统一注入）；
 *   · [writeJson] / [slotHeaders] 是在它上面的薄约定。
 *
 * `HttpApi` 仍保留同名的私有薄门面，调用点一个都不用改；改的只是实现
 * 所在的位置。判据（tools/run_cors_guard.sh 等）现在锚本文件。
 */
object HttpWire {

/**
 * OpenAI 形状的错误体。
 *
 * `type` / `code` 默认仍是历史那一份（`local_inference_error`、无 code），
 * **只有**取消那一档传 `request_cancelled`：客户端与日志都该能从机器可读的字段
 * 分辨"请求被取消"与"服务端故障"，而不是去 match 中文 message。
 * `code` 为 null 时整格不出现 —— 历史响应逐字节不变。
 */
fun errJson(
    msg: String,
    type: String = "local_inference_error",
    code: String? = null,
): String {
    val codeField = if (code == null) "" else ",\"code\":" + code.jsonQuoted()
    return """{"error":{"message":${msg.jsonQuoted()},"type":${type.jsonQuoted()}$codeField}}"""
}

/**
 * 非预先分帧的响应。**CORS 头必须在这里统一注入**，不允许调用点自己拼 —— 
 * 21 处 `writeJson` 只要漏一处，症状就是"预检过了、某一条端点被拦"，
 * 而这种"只有一条路径不通"的故障在浏览器里看起来与"服务随机抽风"完全一样。
 */
fun writeRaw(
    out: OutputStream,
    code: Int,
    body: String,
    contentType: String = "application/json; charset=utf-8",
    extraHeaders: String = "",
    corsOrigin: String? = null,
) {
    val b = body.toByteArray(Charsets.UTF_8)
    val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
        "Content-Type: $contentType\r\n" +
        "Content-Length: ${b.size}\r\n" +
        CorsPolicy.responseHeaders(corsOrigin) +
        extraHeaders +
        "Connection: close\r\n\r\n"
    out.write(head.toByteArray(Charsets.UTF_8))
    if (b.isNotEmpty()) out.write(b)
    out.flush()
}

/**
 * @param extraHeaders 额外的响应头（每行以 `\r\n` 结尾，含 `X-Slot-Id` 之外的那些）。
 *   目前只有 relay 那一档用（`X-Relay-Coord`，ISSUE #452「拿回真流式」）：
 *   非流式没有"第一帧"可以挂坐标，中间层要的就是**响应头**。默认空串 ——
 *   逐字节与引入本特性之前相同。
 */
fun writeJson(
    out: OutputStream,
    code: Int,
    body: String,
    corsOrigin: String? = null,
    slot: Int = -1,
    extraHeaders: String = "",
) = writeRaw(out, code, body, "application/json; charset=utf-8",
    extraHeaders = slotHeaders(slot) + extraHeaders, corsOrigin = corsOrigin)

/**
 * 「这一轮占的是哪一格」—— 响应头 `X-Slot-Id`（ISSUE #403）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么必须由**服务端**告诉调用方
 * ══════════════════════════════════════════════════════════════════════
 * `/health` 的 `slot_active` 只说"哪几格被 HTTP 占着"，两格都是 `http` 时它
 * 答不出"哪一格是我"；而**猜错格子的停止**会掐掉别人的回答。既然服务端在受理
 * 那一刻就知道答案，就没有理由让调用方去猜。
 *
 * 跨源要能读到它：`CorsPolicy.EXPOSE_HEADERS` 里必须含它 —— 少了那一条等于
 * 服务端没发（跨源 JS 默认读不到自定义响应头），而"没发"与"页面没读"在现象上
 * 完全同形。
 *
 * `slot < 0` = 不报（错误响应、非生成端点）。**只在真的有一格时写**：
 * 给每个 400/503 都挂一个 `X-Slot-Id`，调用方会以为"这一格是我的"。
 */
fun slotHeaders(slot: Int): String =
    if (slot < 0) "" else "X-Slot-Id: $slot\r\n"

/**
 * 状态码 -> 原因短语。
 *
 * 缺一条会回 `HTTP/1.1 204 Error` —— 客户端大多能忍，但日志与抓包里
 * "Error" 会被当成服务端故障。499 不在 RFC 里（nginx 的社区约定），
 * 写明它才能让"客户端自己断开"与"服务坏了"在日志里分得开。
 */
fun reason(c: Int) = when (c) {
    200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
    499 -> "Client Closed Request"
    404 -> "Not Found"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
    503 -> "Service Unavailable"; else -> "Error"
}

/**
 * SSE 响应头。CORS 必须在这里带上，理由同 [writeRaw]：
 * 流式端点是浏览器前端最常打的那个，漏了它等于 CORS 只做了一半。
 */
fun sseHead(out: OutputStream, corsOrigin: String? = null, slot: Int = -1,
                    extraHeaders: String = "") {
    val head = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/event-stream\r\n" +
        "Cache-Control: no-cache\r\n" +
        "Transfer-Encoding: chunked\r\n" +
        CorsPolicy.responseHeaders(corsOrigin) +
        slotHeaders(slot) +
        extraHeaders +
        "Connection: close\r\n\r\n"
    out.write(head.toByteArray(Charsets.UTF_8))
    out.flush()
}

/**
 * 把一帧 chunked 数据写进任意输出流（`buf` 与 socket 同一份实现）。
 *
 * 中间层的预读层（[RelayPrefetch]）垫心跳时写的是它——**一帧注释就是一个 chunk**，
 * 与 [sseComment] 逐字节相同。分开两份实现的话，一份改了分帧、另一份没改，
 * 客户端读到长度行错位的表现完全一样，而它只在"这一轮走的是 relay"时出现。
 */
fun writeFrame(dst: OutputStream, payload: ByteArray) {
    dst.write(payload.size.toString(16).toByteArray(Charsets.UTF_8))
    dst.write("\r\n".toByteArray(Charsets.UTF_8))
    dst.write(payload)
    dst.write("\r\n".toByteArray(Charsets.UTF_8))
    dst.flush()
}

/**
 * SSE 注释帧（思考生成期间防客户端空闲超时）。**必须走 chunked 分帧**：
 * 裸写一个字节会破坏 `Transfer-Encoding: chunked`，客户端解析下一个 chunk 的长度行时
 * 读到 `0x0`，报 `Expected leading [0-9a-fA-F] character but was 0x0` 后断流
 * （2026-09-20 真机事故，见 [RequestCancel] 文件头）。
 */
fun sseComment(out: OutputStream) {
    try { writeFrame(out, ": ping\n\n".toByteArray(Charsets.UTF_8)) } catch (_: Exception) {}
}

fun sseEvent(out: OutputStream, data: String) {
    val payload = "data: $data\n\n".toByteArray(Charsets.UTF_8)
    out.write(payload.size.toString(16).toByteArray(Charsets.UTF_8))
    out.write("\r\n".toByteArray(Charsets.UTF_8))
    out.write(payload)
    out.write("\r\n".toByteArray(Charsets.UTF_8))
    out.flush()
}

fun sseEnd(out: OutputStream) {
    out.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
    out.flush()
}

}
