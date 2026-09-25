package com.xiaowan.localinference

// 「CORS 白名单 + 预检 + 自带测试页」的离线单测（纯判据，不依赖 HTTP 栈、不依赖设备）。
//
// 为什么值得单测：这一整套东西的失效**全是静默的**，而且两个方向都很难看：
//
//   1. **放行过宽**（回 `*` / 规范化把非法 Origin 变成合法）—— 没有任何报错。
//      现状是"接口无鉴权 + 只在局域网内可直连"；一旦回 `*`，攻击面立刻变成
//      「用户在浏览器里打开的任何一个网页都能调这台手机的模型」。
//      这个放大不会让任何一条现有测试变红，只能靠断言钉住。
//   2. **放行过窄**（白名单判定写反 / 漏了预检）—— 浏览器侧只表现为"请求失败"，
//      与"服务没起来"完全同形。而这正是本次要修的那个问题本身。
//
// 另外 `originOf` 是从 URL 反推来源的，用它拼的白名单如果算错一个字符，
// 表现就是"配置看着对、就是不通"。
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

fun main() {
    // ══════════════════════════════════════════════════════════════════
    // 1. normalize：大小写、默认端口、非法形状
    // ══════════════════════════════════════════════════════════════════
    // 为什么必须规范化：`http://LOCALHOST:80` 与 `http://localhost` 是同一个来源，
    // 字面比对会漏放行 —— 而"漏放行"在浏览器里与"服务坏了"同形，最难查。
    ck("大小写归一", CorsPolicy.normalize("HTTP://LocalHost:8080") == "http://localhost:8080")
    ck("http 默认端口 80 被去掉", CorsPolicy.normalize("http://a.example:80") == "http://a.example")
    ck("https 默认端口 443 被去掉", CorsPolicy.normalize("https://a.example:443") == "https://a.example")
    ck("非默认端口保留", CorsPolicy.normalize("http://a.example:8081") == "http://a.example:8081")
    ck("IPv6 字面量保留方括号与端口", CorsPolicy.normalize("http://[::1]:8080") == "http://[::1]:8080")
    ck("IPv6 无端口", CorsPolicy.normalize("http://[::1]") == "http://[::1]")
    // "null" 是**合法**来源：file:// 打开的页面 / sandboxed iframe 的 Origin 字面就是它。
    ck("Origin: null 是合法来源（file:// 场景）", CorsPolicy.normalize("null") == "null")
    ck("Origin: NULL 大小写归一", CorsPolicy.normalize("NULL") == "null")

    // 非法形状一律返回空串 —— 空串**绝不能**被当成"放行"，这是本文件最硬的约束。
    ck("空串无效", CorsPolicy.normalize("") == "")
    ck("空白串无效", CorsPolicy.normalize("   ") == "")
    ck("没有 scheme 无效", CorsPolicy.normalize("localhost:8080") == "")
    ck("非 http(s) scheme 无效", CorsPolicy.normalize("ftp://a.example") == "")
    ck("带路径的 Origin 无效（Origin 头不含路径）", CorsPolicy.normalize("http://a.example/x") == "")
    ck("带查询串的 Origin 无效", CorsPolicy.normalize("http://a.example?x=1") == "")
    ck("端口非数字无效", CorsPolicy.normalize("http://a.example:abc") == "")
    ck("多个冒号的伪 host:port 无效", CorsPolicy.normalize("http://a:b:c") == "")
    ck("通配 * 不是合法来源", CorsPolicy.normalize("*") == "")

    // ══════════════════════════════════════════════════════════════════
    // 2. allow：白名单判定（默认集极窄）
    // ══════════════════════════════════════════════════════════════════
    CorsPolicy.enabled = true
    CorsPolicy.clearOrigins()

    // 回显**原样**的 Origin，不是规范化值：浏览器比对的是字节级相等，
    // 回一个"我改写过的"值等于没放行（且极难归因）。
    ck("回环 localhost 放行且原样回显", CorsPolicy.allow("http://localhost:3000") == "http://localhost:3000")
    ck("回环 127.0.0.1 放行", CorsPolicy.allow("http://127.0.0.1") == "http://127.0.0.1")
    ck("回环 IPv6 放行", CorsPolicy.allow("http://[::1]:5173") == "http://[::1]:5173")
    ck("大写回环也放行", CorsPolicy.allow("HTTP://LocalHost:3000") == "HTTP://LocalHost:3000")
    ck("file:// 页面（Origin: null）放行", CorsPolicy.allow("null") == "null")

    // 默认集**不含**外部主机 —— 这是本次"白名单式"的核心承诺。
    ck("外部来源默认拒绝", CorsPolicy.allow("http://evil.example") == null)
    ck("外部来源带端口默认拒绝", CorsPolicy.allow("http://192.168.1.50:3000") == null)
    ck("空 Origin 拒绝", CorsPolicy.allow("") == null)
    ck("null（Java null）拒绝", CorsPolicy.allow(null) == null)
    ck("非法 Origin 拒绝（带路径）", CorsPolicy.allow("http://localhost/x") == null)
    ck("通配串不构成放行", CorsPolicy.allow("*") == null)

    // 追加来源后生效，且仍然原样回显。
    ck("addOrigin 合法来源返回 true", CorsPolicy.addOrigin("http://192.168.1.50:3000"))
    ck("追加后该来源放行", CorsPolicy.allow("http://192.168.1.50:3000") == "http://192.168.1.50:3000")
    CorsPolicy.addOrigin("http://192.168.1.50")
    ck("追加无端口的来源，等价写法（http 默认 80）也放行",
        CorsPolicy.allow("http://192.168.1.50:80") == "http://192.168.1.50:80")
    ck("未追加的来源仍被拒绝", CorsPolicy.allow("http://192.168.1.51:3000") == null)
    ck("重复追加返回 false", !CorsPolicy.addOrigin("HTTP://192.168.1.50:3000"))
    ck("非法来源追加被拒", !CorsPolicy.addOrigin("*") && !CorsPolicy.addOrigin("not-a-url"))
    ck("addOrigin 非法串不会污染白名单", CorsPolicy.allow("not-a-url") == null)

    CorsPolicy.clearOrigins()
    ck("clearOrigins 后追加来源失效", CorsPolicy.allow("http://192.168.1.50:3000") == null)
    ck("clearOrigins 后回环仍放行（默认集不可被清空）",
        CorsPolicy.allow("http://localhost:1") == "http://localhost:1")

    // 关掉开关 = 回到旧行为（一个 CORS 头都不出），用于排障对照。
    CorsPolicy.enabled = false
    ck("关闭 CORS 后连回环都不回显", CorsPolicy.allow("http://localhost:3000") == null)
    ck("关闭 CORS 后预检无头", CorsPolicy.preflight("http://localhost:3000", 8080, null, false) == null)
    ck("关闭 CORS 后普通响应无头", CorsPolicy.responseHeaders("http://localhost:3000") == "")
    CorsPolicy.enabled = true

    // ══════════════════════════════════════════════════════════════════
    // 3. originOf：从 URL 反推来源
    // ══════════════════════════════════════════════════════════════════
    ck("带路径的 URL 取出来源", CorsPolicy.originOf("http://192.168.1.23:8080/chat") == "http://192.168.1.23:8080")
    ck("裸主机取出来源", CorsPolicy.originOf("http://192.168.1.23:8080") == "http://192.168.1.23:8080")
    ck("query/fragment 不算来源的一部分", CorsPolicy.originOf("http://a.example/x?y=1#z") == "http://a.example")
    ck("非法 URL 取不到来源", CorsPolicy.originOf("192.168.1.23:8080") == "")

    // ══════════════════════════════════════════════════════════════════
    // 4. localOrigins：本机来源要覆盖「回环 + 本机 IP」两类写法
    // ══════════════════════════════════════════════════════════════════
    val lo = CorsPolicy.localOrigins(8080, listOf("192.168.1.23"))
    ck("本机来源含回环带端口", "http://127.0.0.1:8080" in lo)
    ck("本机来源含 localhost 无端口", "http://localhost" in lo)
    ck("本机来源含本机 IP 带端口", "http://192.168.1.23:8080" in lo)
    ck("本机来源不含别人的 IP", lo.none { it.contains("192.168.1.24") })
    val lo6 = CorsPolicy.localOrigins(8080, listOf("fe80::1"))
    ck("IPv6 主机自动加方括号", "http://[fe80::1]:8080" in lo6)

    // ══════════════════════════════════════════════════════════════════
    // 5. preflight：预检应答
    // ══════════════════════════════════════════════════════════════════
    val pf = CorsPolicy.preflight("http://localhost:5173", 8080, "content-type", false)
    ck("白名单内预检有头", pf != null)
    ck("预检回显 Origin", pf!!.contains("Access-Control-Allow-Origin: http://localhost:5173"))
    ck("预检放行 OPTIONS 自身（否则预检自己就不合法）", pf.contains("OPTIONS"))
    ck("预检放行 POST（生成端点都是 POST）", pf.contains("POST"))
    ck("预检放行 GET", pf.contains("GET"))
    ck("预检不用方法通配 *", !pf.contains("Allow-Methods: *"))
    ck("预检放行 Content-Type（JSON 客户端必带）", pf.contains("Content-Type"))
    ck("预检放行 Authorization（第 2 步加鉴权后要靠它）", pf.contains("Authorization"))
    ck("预检带 Max-Age（省掉重复预检）", pf.contains("Access-Control-Max-Age: 600"))
    // 本接口不发 Cookie / HTTP 认证，开 Credentials 只让放行的后果变严重、收益为零。
    ck("预检明确不发 Allow-Credentials", !pf.contains("Allow-Credentials"))

    // 白名单外**仍然回 204**（只不带头）：头缺失本身就是拒绝；
    // 回 403 会让"来源被拒"与"服务坏了"在客户端看起来一样。
    ck("白名单外预检无头（调用方据此回裸 204）",
        CorsPolicy.preflight("http://evil.example", 8080, null, false) == null)
    ck("白名单外预检不会退化成通配",
        CorsPolicy.preflight("http://evil.example", 8080, null, false)?.contains("*") != true)

    // ══════════════════════════════════════════════════════════════════
    // 6. responseHeaders：普通响应上的头
    // ══════════════════════════════════════════════════════════════════
    val rh = CorsPolicy.responseHeaders("http://localhost:3000")
    ck("普通响应带回显 Origin", rh.contains("Access-Control-Allow-Origin: http://localhost:3000"))
    // 响应体随 Origin 变化，缺 Vary 会被中间缓存把"给 A 的放行头"回给 B。
    ck("普通响应带 Vary: Origin", rh.contains("Vary: Origin"))
    ck("普通响应不带 Allow-Credentials", !rh.contains("Allow-Credentials"))
    ck("非白名单来源普通响应无头", CorsPolicy.responseHeaders("http://evil.example") == "")

    // 全场景**绝不允许**出现通配放行 —— 这是本次唯一的硬安全承诺。
    val allHeaders = listOfNotNull(
        CorsPolicy.preflight("http://localhost:3000", 8080, null, false),
        CorsPolicy.preflight("null", 8080, null, true),
        CorsPolicy.responseHeaders("http://localhost:3000"),
        CorsPolicy.responseHeaders("null"),
    ).joinToString("\n")
    ck("任何白名单内响应都不出现 Allow-Origin: *", !allHeaders.contains("Allow-Origin: *"))

    // ══════════════════════════════════════════════════════════════════
    // 7. 自带测试页 HTML
    // ══════════════════════════════════════════════════════════════════
    val html = CorsPolicy.pageHtml(8080, true, true, "qwen2.5-1.5b")
    ck("页面是完整 HTML 文档", html.startsWith("<!DOCTYPE html>") && html.contains("</html>"))
    ck("页面声明 UTF-8（页子里有中文，缺了必乱码）", html.contains("charset=\"utf-8\""))
    ck("页面标题引常量名", html.contains(CorsPolicy.PAGE_TITLE))
    // 页面必须打通这三件事，否则它作为"最小可验证面"就没有意义。
    ck("页面探测 /health", html.contains("/health"))
    ck("页面调 /v1/models", html.contains("/v1/models"))
    ck("页面调 /v1/chat/completions", html.contains("/v1/chat/completions"))
    ck("页面用流式（stream:true）", html.contains("stream: true"))
    ck("页面用相对路径（同源，不需要 CORS）", html.contains("fetch(path,"))
    ck("页面无外部依赖（不能引 CDN，否则离线场景就打不开）",
        !html.contains("http://cdn") && !html.contains("https://cdn") &&
            !html.contains("unpkg.com") && !html.contains("jsdelivr"))
    ck("页面显示端口", html.contains(">8080<"))
    ck("页面显示局域网访问状态", html.contains("已开启"))
    ck("页面预填当前模型 id", html.contains("qwen2.5-1.5b"))
    ck("页面提示无鉴权风险", html.contains("无鉴权"))
    ck("页面有停止按钮（服务端要跑满 max_tokens 才发现断连）", html.contains("btnStop"))
    ck("未开局域网时页面如实说明", CorsPolicy.pageHtml(8080, false, false, null).contains("未开启"))
    ck("模型未加载时不残留占位模型名",
        !CorsPolicy.pageHtml(8080, false, false, null).contains("qwen2.5-1.5b"))

    // 模型名/端口是**外部输入**（文件名可含任意字符），注入页面时必须转义 ——
    // 否则一个叫 `" onfocus=alert(1) autofocus x="` 的 gguf 文件就能在这个页面上
    // 执行脚本，而这个页面是**服务端同源**的。
    //
    // 注意语料必须覆盖 `"` 族：模型名落进的是 `value="…"`（HTML **属性**），
    // 而 HTML 没有反斜杠转义 —— 只断言 `<` 族会给出"注入已防住"的**假信心**
    // （曾经的实现正是把 JS 转义器用在属性位置，`<` 族全过、`"` 族直接越界）。
    val evil = CorsPolicy.pageHtml(8080, false, true, "</script><script>alert(1)</script>")
    ck("模型名里的 </script> 被转义（不能形成注入）", !evil.contains("</script><script>alert(1)"))
    val attrEvil = CorsPolicy.pageHtml(8080, false, true, "\" onfocus=alert(1) autofocus x=\"")
    ck("模型名里的引号被实体化（属性不能被闭合）",
        !attrEvil.contains("value=\"\" onfocus") && attrEvil.contains("&quot;"))
    ck("模型名的反斜杠原样保留（实体转义不动数据）",
        CorsPolicy.pageHtml(8080, false, true, "a\\b").contains("value=\"a\\b\""))

    // 页面本身不含任何 CORS 逻辑：它是同源的，混进来只会让人误解两件事的边界。
    ck("页面不依赖 CORS（同源）", !html.contains("Access-Control-Allow-Origin"))

    println()
    if (fail == 0) { println("=== CorsPolicy 单测：全绿 ==="); return }
    println("=== CorsPolicy 单测：FAIL $fail ===")
    kotlin.system.exitProcess(1)
}
