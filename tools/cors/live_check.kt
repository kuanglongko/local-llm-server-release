// 「CORS + OPTIONS + GET /」的**端到端**校验：不依赖 Android、不依赖设备，
// 直接拿 CorsPolicy 的判据 + 与 HttpApi 同款的头部拼装逻辑跑一遍真实字节。
//
// 为什么在单测之外还要这一份：单测验的是 CorsPolicy 的判据，而"响应到底长什么样"
// 取决于 HttpApi 里的拼装顺序。这里的核心断言只有一条但很硬 ——
// **预检响应里 204 之后必须没有 Content-Length 之后的正文、且 CORS 头必须在头块内**，
// 也就是"头拼对了、没有把 CORS 头落到 body 里去"这种低级但真实存在的错。
package com.xiaowan.localinference

private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

private fun headBytes(code: Int, reason: String, contentType: String,
                      corsOrigin: String?, extra: String, bodySize: Int): String {
    return "HTTP/1.1 $code $reason\r\n" +
        "Content-Type: $contentType\r\n" +
        "Content-Length: $bodySize\r\n" +
        CorsPolicy.responseHeaders(corsOrigin) +
        extra +
        "Connection: close\r\n\r\n"
}

fun main() {
    CorsPolicy.clearOrigins()
    CorsPolicy.enabled = true

    // ── 预检：白名单内 ──
    val pf = headBytes(204, "No Content", "application/json; charset=utf-8", null,
        CorsPolicy.preflight("http://localhost:5173", 8080, "content-type", false) ?: "", 0)
    ck("预检状态行是 204 No Content", pf.startsWith("HTTP/1.1 204 No Content\r\n"))
    ck("预检 Content-Length: 0（否则客户端会等一个永远不来的正文）", pf.contains("Content-Length: 0\r\n"))
    ck("预检的 CORS 头位于头块内（头块以空行结束）",
        pf.indexOf("Access-Control-Allow-Origin") < pf.indexOf("\r\n\r\n"))
    ck("预检头块以 \\r\\n\\r\\n 正常结束", pf.endsWith("\r\n\r\n"))
    ck("预检头块里**没有**正文（空行之后长度为 0）", pf.substringAfter("\r\n\r\n").isEmpty())
    // 预检**不**带 responseHeaders 那一份（预检自己有完整的 Allow-* 头，
    // 再叠一份 Allow-Origin 会出现两个同名头 —— 浏览器对重复头的行为是实现定义的）。
    ck("预检不出现重复的 Allow-Origin",
        pf.split("Access-Control-Allow-Origin").size - 1 == 1)

    // ── 预检：白名单外 ──
    val nop = headBytes(204, "No Content", "application/json; charset=utf-8", null,
        CorsPolicy.preflight("http://evil.example", 8080, null, false) ?: "", 0)
    ck("白名单外预检仍是 204（不是 403）", nop.startsWith("HTTP/1.1 204 No Content\r\n"))
    ck("白名单外预检一个 CORS 头都没有", !nop.contains("Access-Control-"))

    // ── 普通响应带 CORS ──
    val ok = headBytes(200, "OK", "application/json; charset=utf-8",
        CorsPolicy.allow("http://localhost:3000"), "", 42)
    ck("普通响应带 Allow-Origin 回显", ok.contains("Access-Control-Allow-Origin: http://localhost:3000\r\n"))
    ck("普通响应带 Vary: Origin", ok.contains("Vary: Origin\r\n"))
    ck("普通响应头顺序合法（CORS 头在空行之前）",
        ok.indexOf("Access-Control-Allow-Origin") < ok.indexOf("\r\n\r\n"))

    // ── 非白名单来源的普通响应 ──
    val no = headBytes(200, "OK", "application/json; charset=utf-8",
        CorsPolicy.allow("http://evil.example"), "", 42)
    ck("非白名单来源普通响应无 CORS 头", !no.contains("Access-Control-"))
    ck("非白名单来源普通响应仍然 200（跨源请求本来就由浏览器拦，服务端不做 403）",
        no.startsWith("HTTP/1.1 200 OK\r\n"))

    // ── 自带测试页的响应头 ──
    val page = CorsPolicy.pageHtml(8080, true, true, "m.gguf")
    val ph = headBytes(200, "OK", "text/html; charset=utf-8", CorsPolicy.allow("null"), "",
        page.toByteArray(Charsets.UTF_8).size)
    ck("测试页 Content-Type 是 text/html 且带 charset",
        ph.contains("Content-Type: text/html; charset=utf-8\r\n"))
    ck("测试页 Content-Length 是字节数（不是字符数 —— 页子里有中文）",
        ph.contains("Content-Length: ${page.toByteArray(Charsets.UTF_8).size}\r\n"))
    ck("测试页对 file:// 来源也回 CORS 头（Origin: null 在白名单里）",
        ph.contains("Access-Control-Allow-Origin: null\r\n"))

    println()
    if (fail == 0) { println("=== CORS 线上形状检查：全绿 ==="); return }
    println("=== CORS 线上形状检查：FAIL $fail ===")
    kotlin.system.exitProcess(1)
}
