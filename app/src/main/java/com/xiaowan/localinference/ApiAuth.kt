package com.xiaowan.localinference

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * 接口鉴权的**纯逻辑**部分：Bearer token 的生成/校验、请求头的拆解、
 * 「这条路径要不要鉴权」的判据。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是 `Authorization: Bearer`（而不是自定义 header）
 * ══════════════════════════════════════════════════════════════════════════
 * 这个接口的客户端分两类，而它们的填法不同：
 *   · `curl` / 脚本 / OpenAI SDK —— `Authorization: Bearer <token>` 是标准填法；
 *   · 浏览器里的第三方 UI（Open WebUI / NextChat 一类）—— 多数只给一个
 *     「API Key」输入框，它按约定就填进 `Authorization`。
 * 自定义 header（例如 `X-Local-Token`）在这第二类客户端上**根本填不进去**，
 * 结果会是"鉴权做完了、UI 却调不通"，然后被归因成"加鉴权把功能搞坏了"。
 * 所以这里**只认** `Authorization: Bearer`，不另发明一套。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 哪些路径**免**鉴权（这条判据错了就是"自检把自己挡死"）
 * ══════════════════════════════════════════════════════════════════════════
 * 只有**生成端点**要鉴权。以下一律免鉴权，且每一条都有具体理由：
 *   · `OPTIONS` —— 预检是浏览器**自动**发的，不带 `Authorization`。拦了它，
 *     浏览器根本不会把正式请求发出来，症状是"预检 401、POST 压根没发"，
 *     排查方向一开始就是错的。这条是 Issue #40 里写死的硬约束。
 *   · `GET /health` —— 存活探针（K8s 式 liveness、监控）默认只 GET 不带头；
 *     而且服务端自己的看门狗（`HttpApi.probeHealthy`）与 App 内「存活探测」
 *     （`HealthCheck`）打的就是它 —— 一旦要 token，自检会**把自己判成不可达**，
 *     表现为"服务明明在跑，探测说连不上"。
 *   · `GET /v1/models` —— 只读的模型清单，不含任何生成能力；第三方 UI 的
 *     「模型下拉框」正是靠它填充，带不上 token 会让 UI 显示空列表。
 *   · `GET /` —— 自带测试页（同源）。它在**浏览器地址栏里被打开**，
 *     没有任何地方能填 header；要 token 就等于页面永远打不开。
 *     页面对 `/v1/` 下那些生成端点的调用才需要 token（页面里给了输入框，见 [WebChatPage]）。
 *
 * 反过来，**要**鉴权的正好是能烧算力/电的那三个：`/v1/chat/completions`、
 * `/v1/completions`、`/v1/abort`。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 比较为什么必须**定长**
 * ══════════════════════════════════════════════════════════════════════════
 * 用 `MessageDigest.isEqual`（JDK 保证实现为定长比较）比逐字符 `==`：
 * 后者在第一处不等就返回，比较耗时随"猜对了几个字符"变化 ——
 * 理论上可被逐字节旁路。本服务跑在局域网、威胁模型很弱，这个加固几乎白拿，
 * 但它同样**没有代价**（同长度比一次），所以直接做对。
 *
 * 运行：宿主侧离线单测见 `tools/run_auth_tests.sh`（与 [verdict] 共用同一份判据）。
 */
object ApiAuth {

    /** token 的字符集：去掉易混淆的 `0O1lI`，方便用户在手机上抄。 */
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** token 长度。24 字符 × log2(56) ≈ 140 bit，远超任何暴力尝试。 */
    const val TOKEN_LEN = 24

    /**
     * 服务端当前生效的 token；为空 = **鉴权关闭**（keep-compatible 的关键）。
     *
     * 为什么"不设 token"就等于不开鉴权，而不是"用默认 token"：
     * 服务在没配 token 时必须与**本轮之前**完全同行为 —— 否则所有既有客户端
     * （`tools/acceptance_*.py`、README 里的 curl 示例、App 内自检）会在升级后
     * 集体失败，而它们的失败形态是"请求 401"、看起来正如"功能坏了"。
     * 默认关闭 = 不改变现状；要开启是用户的一次显式动作（设置页生成 token）。
     */
    @Volatile var token: String = ""

    /** 鉴权是否在生效（有非空 token 才算开）。 */
    val enabled: Boolean get() = token.isNotEmpty()

    private val rng by lazy { SecureRandom() }
    private val rngCalls = AtomicLong(0)

    /**
     * 生成一个新 token。
     *
     * 用 `SecureRandom` 而不是 `Random`/时间戳：后者可预测，而 token 是**唯一**
     * 挡住"同网段任何人"的东西。取模偏置在这里可以忽略（56 与 2^8 的偏差 < 3%），
     * 换来的是实现足够短、一眼能看懂。
     */
    fun generate(): String {
        val sb = StringBuilder(TOKEN_LEN)
        repeat(TOKEN_LEN) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /**
     * 从 `Authorization` 头里取出 Bearer token；取不到返回空串。
     *
     * 判据全部显式，不"顺手宽容"：
     *   · 只认 `Bearer`（大小写不敏感 —— 规范里 scheme 是 case-insensitive 的，
     *     各客户端写法不一，卡大小写只会制造"同一个 token 有的客户端行有的不行"）；
     *   · token 本身**不** trim 内部空白（它由固定字符集生成，出现空白说明是伪造）；
     *   · 空 token、非 Bearer scheme 一律返回空串（= 拿不到凭据）。
     */
    fun bearer(header: String?): String {
        val h = header?.trim().orEmpty()
        if (h.isEmpty()) return ""
        val sp = h.indexOf(' ')
        if (sp <= 0) return ""
        val scheme = h.substring(0, sp)
        if (!scheme.equals("Bearer", ignoreCase = true)) return ""
        val value = h.substring(sp + 1).trim()
        return if (value.isEmpty()) "" else value
    }

    /** 定长比较（长度不同直接 false —— 长度本身不是秘密，token 长度固定）。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        return java.security.MessageDigest.isEqual(x, y)
    }

    /**
     * 这条请求要不要鉴权。
     *
     * 判据写成**白名单式**（明确列出免鉴权的四种），不是"排除了几条就放行"：
     * 后者在新增端点时会默认把新端点**放在门外**（fail-open），
     * 而这里宁可让新端点默认需要 token（fail-closed）—— 那才是安全的默认。
     */
    fun requiresAuth(method: String, path: String): Boolean {
        val m = method.trim().uppercase()
        // 预检必须豁免（见文件头）。这里判的是"要不要 token"，而不是"放不放行"，
        // 所以豁免是"不要求凭据"，不是"无条件 204"—— 后者由 CorsPolicy 管。
        if (m == "OPTIONS") return false
        // 存活/清单/自带测试页：只读、无生成能力，且都被自检与浏览器地址栏直接打。
        if (under(path, "/health")) return false
        if (under(path, "/v1/models")) return false
        if (path == "/" || path.startsWith("/?")) return false
        // `/v1/` 之下：只放行上面两条精确豁免，其余一律要凭据。
        // 注意这里**不是** `under(path, "/v1/")` 才返回 true —— 那样 `/healthz`
        //（既不落在 `/health` 也不落在 `/v1/`）会一路走到 return false，
        // 从"前缀被误当成合法豁免"翻转成"前缀被误当成免鉴权"。改成"落在这个
        // 命名空间里就按 fail-closed 处理"，两个方向都不会漏。
        if (path == "/v1" || path.startsWith("/v1/") || path.startsWith("/v1?")) return true
        // 其余（例如不存在的路径）交回路由层去 404。这里不回 true：
        // 对一个不存在的路径回 401 会把"路径写错"误导成"token 不对"。
        return false
    }

    /**
     * 路径是否**落在** `base` 这个前缀之下（而不是"以此开头的任意串"）。
     *
     * 为什么不能用裸 `startsWith`：它的语义是"字节前缀"，于是 `/healthz`、
     * `/v1/models-evil` 都算命中 —— 免鉴权集合被悄悄放大。每新增一个
     * `/health*` / `/v1/models*` 端点都会自动落进免鉴权侧（fail-open），
     * 而文件头承诺的是"宁可 fail-closed"。两种写法的差别就这么一次比对，
     * 但报错的方向完全不同：前缀式出错时没有任何症状。
     *
     * 合法写法只有三种：完全相等、base 后接 `/`（下级路径）、base 后接 `?`（查询串）。
     * `base` 结尾带 `/` 时（`/v1/`）只看前缀即可——它本身就以分隔符收尾。
     */
    private fun under(path: String, base: String): Boolean {
        if (path == base) return true
        if (base.endsWith("/")) return path.startsWith(base)
        return path.startsWith("$base/") || path.startsWith("$base?")
    }

    /**
     * 鉴权判定。返回 `null` = 放行；非 null = **给客户端的错误正文**。
     *
     * 为什么把"判定 + 错误正文"放在同一个纯函数里：两处分开写必然漂移 ——
     * 而漂移的表现是"某条端点拒了却不说是为什么"，用户只能靠猜。
     * 这里连**人话提示**都一起产出，HTTP 层只负责把字符串写出去。
     *
     * 缺凭据与凭据错误都回 **401**（不是一个 401 一个 403）：对客户端而言
     * "没带" 与 "带错了" 的处理动作完全一样（重新填 token），
     * 而 403 会让人以为"token 是对的、权限不够"，去查不存在的东西。
     * 错误正文里**只出现**提示，绝不回显服务端 token 或其任何片段。
     */
    fun verdict(authHeader: String?): String? {
        if (!enabled) return null
        val got = bearer(authHeader)
        if (got.isEmpty()) return "缺少或格式错误的 Authorization 头：本服务已开启鉴权，" +
            "请用 `Authorization: Bearer <token>`（token 在 App 设置页查看）。"
        if (!constantTimeEquals(got, token)) return "token 不正确：请在 App 设置页核对，" +
            "或用「复制」按钮重新复制（注意首尾不要多带空格）。"
        return null
    }

    /** 401 的响应头。`WWW-Authenticate` 是 HTTP 语义的一部分：告诉客户端"该带什么凭据"。 */
    fun unauthorizedHeaders(): String = "WWW-Authenticate: Bearer realm=\"local-llm-server\"\r\n"

    /** 生成一个不与当前 token 相同的 token（设置页「重新生成」用；重复概率极低但断言要成立）。 */
    fun regenerate(): String {
        var t = generate()
        var guard = 0
        while (t == token && guard++ < 8) t = generate()
        rngCalls.incrementAndGet()
        return t
    }
}
