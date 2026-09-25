package com.xiaowan.localinference

/**
 * CORS（跨源资源共享）的**纯逻辑**部分：来源白名单判定、预检应答、响应头生成，
 * 以及自带测试页的 HTML 生成。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这个文件
 * ══════════════════════════════════════════════════════════════════════════
 * 此前服务端**一个 CORS 头都没有**、`OPTIONS` 直接落进 404 分支。后果：
 * 浏览器里跑的第三方前端（Open WebUI / NextChat / 任何自写的 HTML demo）
 * 一旦把 API Base 填成本机地址就会被拦死 —— 带 `Content-Type: application/json`
 * 的请求会先发 **OPTIONS 预检**，服务端不认，真正的 POST 根本不会发出去。
 * 这不是"可选功能"，是协议面的残缺（与 `stop` 同级）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是「白名单式」而不是 `Access-Control-Allow-Origin: *`
 * ══════════════════════════════════════════════════════════════════════════
 * 本接口**无鉴权**（README 明写）。现状的攻击面是「局域网内直连」。
 * 一旦回 `*`，攻击面立刻变成「**用户在浏览器里打开的任何一个网页**都能调这台手机的模型」
 * —— 一个恶意页面可以静默拉满 GPU/电、把手机当免费推理机。这是真实放大，不是理论风险。
 *
 * 所以这里的默认集刻意**极窄**，且只放行三类：
 *   · `null` —— 本地 `file://` 打开的 HTML / sandboxed iframe（原始字符串就是 "null"）；
 *   · 回环来源（`localhost` / `127.0.0.1` / `[::1]`，任意端口、http/https）——
 *     同机反代/前端开发服务器；
 *   · **本机 IP** 来源（无端口或本机端口）—— 自带测试页经局域网 IP 访问时的同源请求，
 *     以及同机的网页。
 * 其余一律**不回 CORS 头**（= 浏览器按同源策略拒绝），且**不** 403 ——
 * 头缺失本身就是拒绝，回 403 只会让"服务不可达"和"来源被拒"两种故障更难分辨。
 *
 * 需要放行别的来源（例如局域网内另一台机器上的 Open WebUI）时，
 * 用 [allowOrigin] 加进去，或走 `ModelStore` 的持久化设置 —— **不做成"填了就生效"的裸通配**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么 `Access-Control-Allow-Credentials` 明确不开
 * ══════════════════════════════════════════════════════════════════════════
 * 开了它就必须回显具体 Origin（不能 `*`），而本接口根本不发 Cookie / HTTP 认证 ——
 * 没有任何凭据需要携带。开了只会让"某个来源被放行"这件事的后果变严重
 * （跨源请求开始携带凭据），收益为零。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 预检为什么必须 204 且绝不能鉴权
 * ══════════════════════════════════════════════════════════════════════════
 * 预检是**浏览器自动发**的，不带 `Authorization`、不带 body。所以：
 *   · 204（无正文）而不是 200：规范允许任何 2xx，但 204 明确"没有正文"，
 *     不会让某些客户端去等一个永远不来的 body；
 *   · 将来加鉴权时（Issue #40 规划的第 2 步），`OPTIONS` **必须豁免** ——
 *     拦了它等于 CORS 白做，且症状是"预检 200/401、正式请求压根没发"这种极难归因的形态。
 *     本文件把这条写进 [preflight] 的注释与断言里。
 *
 * 运行：宿主侧离线单测见 `tools/run_cors_tests.sh`（与 [plan] 共用同一份判据）。
 */
object CorsPolicy {

    /** 预检结果的"有效时长"（秒）。10 分钟：足够省掉重复预检，又不至于把白名单改动拖太久。 */
    const val MAX_AGE_SECONDS = 600

    /**
     * 放行跨源请求的来源集。**默认极窄**（见文件头）。
     *
     * 存的是**小写**规范形式（scheme://host[:port]）。比较时两侧都规范化，
     * 因为 Origin 的大小写与默认端口写法在不同浏览器/代理下并不一致
     * （`http://LOCALHOST:80` 与 `http://localhost` 是同一个来源，字面比对会漏）。
     */
    @Volatile
    var allowOrigin: Set<String> = emptySet()

    /** CORS 是否启用。关掉后所有 CORS 头与预检都不再出现（回到旧行为），用于排障对照。 */
    @Volatile
    var enabled: Boolean = true

    /**
     * 规范化一个来源：小写、去掉默认端口。
     *
     * 为什么必须做：`http://localhost:80` 与 `http://localhost` 是同一来源，
     * 但字面不同。不做这一步的白名单会在"浏览器省了默认端口、用户手填时带上了"
     * 这类场景下静默漏放行 —— 表现为"配置看着对、就是不通"。
     *
     * 非法/空串返回空串（调用方据此拒绝）：空串**绝不能**被当成"放行"。
     */
    fun normalize(origin: String?): String {
        val o = origin?.trim().orEmpty()
        if (o.isEmpty()) return ""
        if (o.equals("null", ignoreCase = true)) return "null"
        val lower = o.lowercase()
        val scheme: String
        val rest: String
        val i = lower.indexOf("://")
        if (i <= 0) return ""            // 没有 scheme 的 Origin 不是合法的 Origin 头
        scheme = lower.substring(0, i)
        if (scheme != "http" && scheme != "https") return ""
        rest = lower.substring(i + 3)
        if (rest.isEmpty()) return ""
        // Origin 头**不含**路径/查询/片段。带其中任何一个的都是伪造或客户端 bug，
        // 而"看着像合法来源"正是最该拒的一类 —— 放行它等于把源比对交给字符串前缀。
        if (rest.any { it == '/' || it == '?' || it == '#' }) return ""
        // 主机与端口：IPv6 是 [::1]:8080 这种形状，冒号在括号里，不能简单 split(':')
        val colon: Int
        val hostPart: String
        val portPart: String
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return ""
            colon = rest.indexOf(':', close)
            hostPart = rest.substring(0, close + 1)
            portPart = if (colon >= 0) rest.substring(colon + 1) else ""
        } else {
            colon = rest.lastIndexOf(':')
            if (colon >= 0 && rest.indexOf(':') == colon) {
                hostPart = rest.substring(0, colon)
                portPart = rest.substring(colon + 1)
            } else {
                // 无端口，或出现多个冒号（既不是 IPv6 带括号的写法，也不是合法 host:port）
                if (colon >= 0) return ""
                hostPart = rest
                portPart = ""
            }
        }
        if (hostPart.isEmpty()) return ""
        if (portPart.isNotEmpty() && portPart.toIntOrNull() == null) return ""
        val defaultPort = (scheme == "http" && portPart == "80") || (scheme == "https" && portPart == "443")
        val port = if (portPart.isEmpty() || defaultPort) "" else ":$portPart"
        return "$scheme://$hostPart$port"
    }

    /** 该来源是不是回环（localhost / 127.0.0.1 / [::1]）。 */
    fun isLoopback(origin: String): Boolean {
        val n = normalize(origin)
        if (n.isEmpty()) return false
        val host = hostOf(n) ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]"
    }

    /** 从规范化来源里取主机部分（不含端口）。 */
    private fun hostOf(normalized: String): String? {
        val i = normalized.indexOf("://")
        if (i < 0) return null
        val rest = normalized.substring(i + 3)
        if (rest.startsWith("[")) {
            val c = rest.indexOf(']')
            return if (c < 0) null else rest.substring(0, c + 1)
        }
        val c = rest.lastIndexOf(':')
        return if (c >= 0) rest.substring(0, c) else rest
    }

    /** 从 URL 里取来源（`http://a.b:8080/x/y` -> `http://a.b:8080`）；取不到返回空串。 */
    fun originOf(url: String?): String {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return ""
        val i = u.indexOf("://")
        if (i <= 0) return ""
        val rest = u.substring(i + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return normalize(u.substring(0, i + 3) + (if (end < 0) rest else rest.substring(0, end)))
    }

    /**
     * 本机所有可能的来源（回环 + 局域网 IPv4 + 显式端口）。
     *
     * 为什么要把"本机 IP 的字面来源"也算进白名单：
     * 自带测试页如果用 `http://192.168.1.23:8080/` 打开，那么页面里的
     * `fetch('/v1/chat/completions')` 是**同源**的，浏览器根本不查 CORS；
     * 真正需要它的是"页面在别的端口/别的机器上打开、却要调本服务"的场景。
     * 而只要用户在页面上把 Base 填成**本机 IP**（很常见的写法），来源就是本机 IP。
     * 把它算进来让"本机自用"这条路径永不因为配置而断，同时不扩大对**其它主机**的放行。
     */
    fun localOrigins(port: Int, extraHosts: List<String> = emptyList()): List<String> {
        val hosts = LinkedHashSet<String>()
        hosts.add("localhost"); hosts.add("127.0.0.1"); hosts.add("[::1]")
        for (h in extraHosts) {
            val t = h.trim()
            if (t.isEmpty()) continue
            hosts.add(if (t.contains(':') && !t.startsWith("[")) "[$t]" else t)
        }
        val out = ArrayList<String>()
        for (h in hosts) {
            out.add("http://$h:$port")
            out.add("http://$h")
        }
        return out
    }

    /**
     * 请求来源是否被放行；返回**原样回显**的 Origin（或 null 表示不回 CORS 头）。
     *
     * 回显原值而不是规范化值：浏览器比对的是字节级相等，回显是唯一安全的做法
     *（规范允许回 `null` 或用 `*`，但 `*` 已被本文件头排除）。
     */
    fun allow(origin: String?): String? {
        if (!enabled) return null
        val raw = origin?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val n = normalize(raw)
        // 规范化失败 = 不是合法 Origin（含"带路径""非 http(s) scheme"）→ 拒绝。
        // 注意 `null` 是**合法**来源（file:// 打开的页面），normalize 会保留它。
        if (n.isEmpty()) return null
        if (n == "null") return raw
        if (isLoopback(n)) return raw
        if (n in allowOrigin.map { normalize(it) }) return raw
        return null
    }

    /** 在默认白名单（回环）之外**追加**来源；返回是否真的新增。 */
    fun addOrigin(origin: String): Boolean {
        val n = normalize(origin)
        if (n.isEmpty()) return false
        synchronized(this) {
            if (n in allowOrigin.map { normalize(it) }) return false
            allowOrigin = allowOrigin + n
            return true
        }
    }

    /** 清空追加的来源，回到默认白名单。 */
    fun clearOrigins() {
        synchronized(this) { allowOrigin = emptySet() }
    }

    // ---- HTTP 层面 ----

    /**
     * 预检（OPTIONS）应答。
     *
     * 返回 `null` 表示「这个 Origin 不在白名单里」—— 调用方**仍然回 204**，
     * 只是不带任何 `Access-Control-*` 头。为什么不是 403：
     * 头缺失本身就是拒绝，而 403 会让"来源被拒"和"服务坏了"两种故障在客户端
     * 看起来一模一样（都是请求失败），排障时多一层没有收益的歧义。
     *
     * 将来加鉴权时，**这个入口必须豁免**（见文件头）。
     */
    fun preflight(origin: String?, port: Int, reqHeaders: String?, bindAll: Boolean): String? {
        if (!enabled) return null
        val allow = allow(origin) ?: return null
        // 允许的方法**就是路由里真实存在的那些**（外加 OPTIONS 自身）。
        // 不写通配 "*"：预检的语义就是"我要用这个方法"，回通配会让浏览器
        // 对不存在的路由也放行，错误从"预检失败"推迟成"404"。
        val methods = "GET, POST, OPTIONS"
        // 允许的请求头：显式列出浏览器会为 JSON+SSE 客户端发的那几个。
        // 回显 `Access-Control-Request-Headers` 更宽松；这里选择显式列表，
        // 因为白名单式 CORS 的整个前提就是"不要随手放宽"。
        val headers = "Content-Type, Authorization, Accept, Cache-Control"
        val sb = StringBuilder()
        sb.append("Access-Control-Allow-Origin: ").append(allow).append("\r\n")
        sb.append("Access-Control-Allow-Methods: ").append(methods).append("\r\n")
        sb.append("Access-Control-Allow-Headers: ").append(headers).append("\r\n")
        sb.append("Access-Control-Max-Age: ").append(MAX_AGE_SECONDS).append("\r\n")
        // 明确**不**发 Access-Control-Allow-Credentials（见文件头）。
        return sb.toString()
    }

    /**
     * 非预检响应的 CORS 头（普通响应体上要带的）。
     *
     * 只回 `Access-Control-Allow-Origin`：简单请求（无自定义头）只需要这一个；
     * 带自定义头的请求会先走预检，那时上面那份已经把它放行了。
     * 加 `Vary: Origin` —— 响应内容随 Origin 变化，缺了它会被中间缓存
     * 把"给 A 的放行头"回给 B。
     */
    fun responseHeaders(origin: String?): String {
        val allow = allow(origin) ?: return ""
        return "Access-Control-Allow-Origin: $allow\r\nVary: Origin\r\n"
    }

    // ---- 自带测试页 ----

    /**
     * 测试页标题。**转发自 [WebChatPage]** —— 页面本体已搬到独立文件
     * （CORS 判据与 400 行 HTML 混在一起，读任一边都要先翻过另一边）。
     * 这里保留同名常量与同名入口，是因为既有单测与接线守卫都拿它们当锚点。
     */
    const val PAGE_TITLE = WebChatPage.PAGE_TITLE

    /**
     * 自带测试页（同源，内嵌 HTML/CSS/JS，零外部依赖）。
     *
     * 实现见 [WebChatPage.html]。这里**保留入口**而不是让 HttpApi 直接调
     * [WebChatPage]：`run_cors_guard.sh` 与 `run_cors_tests.sh` 都断言
     * `CorsPolicy.pageHtml(` 这个调用形式，换入口等于同时改掉两张网。
     * 转发一行不增加任何维护面，却让"页面搬到哪"与"接线断言看哪"解耦。
     */
    fun pageHtml(port: Int, bindAll: Boolean, modelLoaded: Boolean, modelDesc: String?): String =
        WebChatPage.html(port, bindAll, modelLoaded, modelDesc)
}
