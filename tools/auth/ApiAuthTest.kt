package com.xiaowan.localinference

// 「Bearer 鉴权 + 免鉴权路径判据」的离线单测（纯判据，不依赖 HTTP 栈、不依赖设备）。
//
// 为什么值得单测：这套东西的失效**两个方向都很像"功能坏了"**：
//
//   1. **判据写反（要鉴权的没鉴、免鉴权的被鉴）** —— 后者尤其致命：
//      `/health` 一旦要 token，服务端自己的看门狗（`HttpApi.probeHealthy`）与
//      App 内「存活探测」（`HealthCheck`）会**把自己判成不可达**，
//      现场表现是"服务明明在跑，探测说连不上" —— 排查方向一开始就是错的。
//      而 `OPTIONS` 一旦要 token，浏览器预检 401、正式请求压根不会发出去，
//      症状是"加了鉴权、浏览器就调不通了"，且看不出是哪一步的问题。
//   2. **比较/拆头写宽松（"顺手宽容"）** —— `Bearer` 大小写、内部空白的处理
//      只要松一格，就多一条绕过路径；而它不会让任何请求变红，
//      只会让"唯一挡住同网段任何人"的那道门变薄。
//
// 判据本体在 ApiAuth（纯函数），这里跑的是**同一份源码**（不是重抄）。
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

fun main() {
    // ══════════════════════════════════════════════════════════════════
    // 1. 默认状态：**不设 token = 鉴权不生效**（keep-compatible 的核心）
    // ══════════════════════════════════════════════════════════════════
    // 这条是"不破坏已有功能"的判据本体：服务在没配 token 时必须与升级前
    // 完全同行为，否则所有既有客户端（验收脚本 / README 的 curl）会在升级后
    // 集体 401，而它们看起来正像"功能坏了"。
    ApiAuth.token = ""
    ck("未设 token 时鉴权不开", !ApiAuth.enabled)
    ck("未设 token 时放行（连 Authorization 头都不需要）", ApiAuth.verdict(null) == null)
    ck("未设 token 时带任意头也放行", ApiAuth.verdict("Bearer garbage") == null)
    ck("未设 token 时生成端点也不要求凭据（保持旧行为）",
        ApiAuth.requiresAuth("POST", "/v1/chat/completions") && ApiAuth.verdict(null) == null)

    // ══════════════════════════════════════════════════════════════════
    // 2. 免鉴权路径判据（写错的后果见文件头）
    // ══════════════════════════════════════════════════════════════════
    // 免鉴权的一律**不要求**凭据 —— 用 requiresAuth == false 断言，
    // 而不是"verdict 恰好放行"：后者在"鉴权关着"时恒真，是自欺。
    ck("OPTIONS 预检免鉴权（拦了等于 CORS 白做）", !ApiAuth.requiresAuth("OPTIONS", "/v1/chat/completions"))
    ck("OPTIONS 对任意路径都免鉴权", !ApiAuth.requiresAuth("OPTIONS", "/v1/completions"))
    ck("/health 免鉴权（看门狗与 App 自检打的就是它）", !ApiAuth.requiresAuth("GET", "/health"))
    ck("/health 带查询串也免鉴权", !ApiAuth.requiresAuth("GET", "/health?verbose=1"))
    ck("/v1/models 免鉴权（第三方 UI 的模型下拉框靠它）", !ApiAuth.requiresAuth("GET", "/v1/models"))
    ck("GET / 自带测试页免鉴权（地址栏里没法填 header）", !ApiAuth.requiresAuth("GET", "/"))
    ck("GET /?x 也免鉴权", !ApiAuth.requiresAuth("GET", "/?x=1"))

    // 要鉴权的正好是烧算力/电的那三个。
    ck("chat/completions 要鉴权", ApiAuth.requiresAuth("POST", "/v1/chat/completions"))
    ck("completions 要鉴权", ApiAuth.requiresAuth("POST", "/v1/completions"))
    ck("abort 要鉴权（它能掐掉别人的生成）", ApiAuth.requiresAuth("POST", "/v1/abort"))
    // 方法大小写不应影响判据（HTTP 方法在协议里是大写的，但别把判据建在这上面）。
    ck("小写 method 判定一致", ApiAuth.requiresAuth("post", "/v1/chat/completions"))
    // 不存在的路径：交回路由层 404，**不要**回 401 —— 否则"路径写错"会被
    // 误导成"token 不对"，用户会去反复重填一个本来没错的 token。
    ck("不存在的路径不要求鉴权（免得把 404 说成 401）", !ApiAuth.requiresAuth("GET", "/nope"))

    // ══════════════════════════════════════════════════════════════════
    // 2b. 前缀边界：判定是"落在路径之下"，不是"以此开头的任意串"
    // ══════════════════════════════════════════════════════════════════
    // 裸 startsWith 的语义是"字节前缀"：`/healthz` 会被当成 `/health`。
    // 免鉴权集合因此被悄悄放大 —— 每新增一个 `/health*` / `/v1/models*`
    // 端点都会自动进入免鉴权侧（fail-open），而文件头承诺的是 fail-closed。
    // 这类错误**零症状**：请求全都照常 200，只是门薄了一层。
    // 判据分两段看，别混成一段：
    //   ① 路由侧：`/healthz` **不再命中** `/health` 路由（否则既免鉴权又返回健康 JSON）；
    //   ② 鉴权侧：`/healthz` 落在 `/v1/` 之外，按本文件的设计**不强制** token ——
    //      它路由不上、回 404，而"对不存在的路径回 401 会把路径写错误导成 token 不对"。
    //      `/v1/models-evil` 则**必须**要 token：它落在 `/v1/` 命名空间里，
    //      那里按 fail-closed 处理（不能因为"看起来像 models"就免掉）。
    ck("/v1/models-evil 落在 /v1/ 之下，仍要 token（fail-closed）",
        ApiAuth.requiresAuth("GET", "/v1/models-evil"))
    ck("/v1/modelstorex 同上", ApiAuth.requiresAuth("GET", "/v1/modelstorex"))
    ck("/healthz 不落在 /v1/ 下 => 不强制 token（路由层回 404，不是 401）",
        !ApiAuth.requiresAuth("GET", "/healthz"))
    // 反过来，合法写法一个都不能被误伤 —— 漏放行会让"/health 探测失败"，
    // 与"服务没起来"同形，排查方向一开始就是错的。
    ck("带子路径的 /health/deep 仍免鉴权", !ApiAuth.requiresAuth("GET", "/health/deep"))
    ck("带查询串的 /v1/models?x 仍免鉴权", !ApiAuth.requiresAuth("GET", "/v1/models?x=1"))
    ck("纯粹的 /v1/ 之下仍要鉴权（生成端点没被误伤）",
        ApiAuth.requiresAuth("POST", "/v1/chat/completions") &&
            ApiAuth.requiresAuth("POST", "/v1/completions") &&
            ApiAuth.requiresAuth("POST", "/v1/abort"))

    // ══════════════════════════════════════════════════════════════════
    // 3. Bearer 拆解：宽松是有边界的
    // ══════════════════════════════════════════════════════════════════
    ck("标准 Bearer 拆出来", ApiAuth.bearer("Bearer abc123") == "abc123")
    // scheme 大小写不敏感是规范要求：卡大小写只会制造"同一个 token 有的客户端行
    // 有的不行"，而 Open WebUI / 各家 SDK 的写法并不统一。
    ck("bearer 小写也认（scheme 是 case-insensitive 的）", ApiAuth.bearer("bearer abc123") == "abc123")
    ck("BEARER 全大写也认", ApiAuth.bearer("BEARER abc123") == "abc123")
    ck("首尾空白被容忍（复制粘贴常带）", ApiAuth.bearer("  Bearer abc123  ") == "abc123")
    // 但这些**不能**被容忍 —— 每宽容一格就多一条绕过路径。
    ck("非 Bearer scheme 不认", ApiAuth.bearer("Basic dXNlcjpwYXNz") == "")
    ck("只有 scheme 没有值不认", ApiAuth.bearer("Bearer") == "")
    ck("Bearer 后面只有空白不认", ApiAuth.bearer("Bearer   ") == "")
    ck("空头不认", ApiAuth.bearer("") == "" && ApiAuth.bearer(null) == "")
    ck("裸 token（无 scheme）不认", ApiAuth.bearer("abc123") == "")
    // 允许值里带 `=`/`-`/`_` 之类（不同客户端的 token 形状各异），但**必须整段**匹配。
    ck("值里带符号仍整段取出", ApiAuth.bearer("Bearer a-b_c.d=e") == "a-b_c.d=e")

    // ══════════════════════════════════════════════════════════════════
    // 4. generate：形状与随机性
    // ══════════════════════════════════════════════════════════════════
    val t1 = ApiAuth.generate()
    val t2 = ApiAuth.generate()
    ck("token 长度符合声明", t1.length == ApiAuth.TOKEN_LEN)
    ck("两次生成不同（随机源真的在工作）", t1 != t2)
    // 字符集去掉了易混淆的 0/O/1/l/I —— 用户在手机上抄 token 是真实场景，
    // 混进这几个字符会制造"抄对了却说不匹配"的挫败。
    ck("token 不含易混淆字符 0O1lI",
        t1.none { it == '0' || it == 'O' || it == '1' || it == 'l' || it == 'I' })
    ck("token 只含字母数字", t1.all { it.isLetterOrDigit() })
    // 100 个样本都满足形状：单次抽样可能碰巧通过，批量才钉得住"没有分支漏了"。
    ck("批量生成形状稳定", (1..100).all { ApiAuth.generate().length == ApiAuth.TOKEN_LEN })

    // ══════════════════════════════════════════════════════════════════
    // 5. verdict：正确放行 / 错误 401（开鉴权之后）
    // ══════════════════════════════════════════════════════════════════
    ApiAuth.token = "AbCd2345EfGh6789JkLmNpQr"
    ck("开了鉴权", ApiAuth.enabled)
    ck("正确 token 放行", ApiAuth.verdict("Bearer AbCd2345EfGh6789JkLmNpQr") == null)
    ck("大小写不同的 token 被拒（token 本身是大小写敏感的）",
        ApiAuth.verdict("Bearer abcd2345efgh6789jklmnpqr") != null)
    ck("缺头被拒", ApiAuth.verdict(null) != null)
    ck("空头被拒", ApiAuth.verdict("") != null)
    ck("错误 token 被拒（前缀相同也不算）", ApiAuth.verdict("Bearer AbCd2345EfGh6789JkLmNpQ") != null)
    ck("多一个字符被拒", ApiAuth.verdict("Bearer AbCd2345EfGh6789JkLmNpQrX") != null)
    ck("非 Bearer scheme 被拒", ApiAuth.verdict("Basic AbCd2345EfGh6789JkLmNpQr") != null)

    // ══════════════════════════════════════════════════════════════════
    // 6. 错误正文：必须可读，且**绝不泄露** token
    // ══════════════════════════════════════════════════════════════════
    val denyMissing = ApiAuth.verdict(null)!!
    val denyWrong = ApiAuth.verdict("Bearer definitely-not-the-token")!!
    ck("缺凭据的提示提到 Bearer 的用法", denyMissing.contains("Bearer"))
    ck("缺凭据的提示指向设置页", denyMissing.contains("设置页"))
    ck("错凭据的提示也指向设置页", denyWrong.contains("设置页"))
    // 泄露 = 把正确的 token 打进错误正文（日志/截图/Issue 里就飞出去了）。
    ck("错误正文不含服务端 token", !denyMissing.contains(ApiAuth.token) && !denyWrong.contains(ApiAuth.token))
    // 连片段也不行（免得被逐段拼出来）。
    ck("错误正文不含 token 的任何片段",
        listOf(4, 8, 12).none { n ->
            val frag = ApiAuth.token.substring(0, n)
            denyMissing.contains(frag) || denyWrong.contains(frag)
        })
    ck("两个方向的提示措辞不同（「没带」与「带错」要分得开）", denyMissing != denyWrong)

    // ══════════════════════════════════════════════════════════════════
    // 7. 401 的响应头 + regenerate 的契约
    // ══════════════════════════════════════════════════════════════════
    // WWW-Authenticate 是 HTTP 语义的一部分：它告诉客户端"该带什么凭据"，
    // 少了它，某些 SDK 不会自动重试 / 提示用户。
    ck("401 带 WWW-Authenticate: Bearer", ApiAuth.unauthorizedHeaders().contains("WWW-Authenticate: Bearer"))
    ck("401 头的 scheme 与判据一致（不会一个 Bearer 一个别的）",
        ApiAuth.unauthorizedHeaders().contains("Bearer"))
    val re = ApiAuth.regenerate()
    ck("regenerate 形状合法", re.length == ApiAuth.TOKEN_LEN)
    ck("regenerate 不会等于当前 token（否则「重新生成」是个空动作）", re != "AbCd2345EfGh6789JkLmNpQr")

    // 开关的切换必须是**即时**的（设置页点一下就该生效，不必重启服务）。
    ApiAuth.token = ""
    ck("清空 token 立即回到免鉴权", !ApiAuth.enabled && ApiAuth.verdict(null) == null)
    ApiAuth.token = "XyZ2345AbCd6789EfGhJkLmN"
    ck("设回 token 立即重新要求鉴权", ApiAuth.enabled && ApiAuth.verdict(null) != null)

    println()
    if (fail == 0) { println("=== ApiAuth 单测：全绿 ==="); return }
    println("=== ApiAuth 单测：FAIL $fail ===")
    kotlin.system.exitProcess(1)
}
