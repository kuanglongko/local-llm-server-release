package com.xiaowan.localinference

// 自带测试页（折叠设置区 + 多轮会话）的**页面结构判据**。
//
// 为什么值得单测：页面是纯字符串产物，写错**完全不参与编译** ——
// 少一个 `id`、漏一个字段名、把 `messages` 写成只发最后一句，
// 编译过、单测（别处的）过、HTTP 200，只有打开浏览器的人能看到不对。
// 而且它的失效形态都很像"服务端有问题"（比如字段名漂移 = 设置没生效，
// 用户会以为服务端忽略了参数），排查方向一开始就是错的。
//
// 所以这里钉的是**用户提的三件事**本身：
//   ① 折叠设置区存在，且三组都在；
//   ② 三个设置项的字段名与服务端请求侧逐字一致（漂移 = 静默不生效）；
//   ③ 多轮：页面维护历史、每轮带上、且"失败/空回复不进历史"。
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

fun main() {
    val html = WebChatPage.html(8080, true, true, "qwen2.5-1.5b")

    // ══════════════════════════════════════════════════════════════════
    // 1. 折叠设置区：三组都要在，且是**可折叠**的
    // ══════════════════════════════════════════════════════════════════
    // 判据用 `<details`（原生折叠），不用"有个隐藏 div"—— 后者要么靠 JS 自己
    // 管状态（多一处能写坏的地方），要么在 JS 没跑起来时永远展开。
    ck("设置区是可折叠的 <details>", html.contains("<details"))
    // 标题要点明**每一组**：漏一组等于那组在页面上"没有名字"，用户不会去点开它。
    // 本轮加了结构化输出，标题与断言一起改 —— 断言跟着功能走，不是反过来。
    ck("折叠标题点明了四组",
        html.contains("设置（思考链 · 生成与采样 · 重复与惩罚 · 结构化输出）"))
    ck("思考链开关在（选择框，三态）", html.contains("id=\"think\"") && html.contains("enable_thinking"))
    ck("有个「跟随服务端默认」的选项（不下发字段）",
        html.contains("跟随服务端默认") && html.contains("value=\"default\""))
    ck("生成与采样组容器在", html.contains("id=\"gridSampling\""))
    ck("重复与惩罚组容器在", html.contains("id=\"gridPenalty\""))
    ck("有恢复默认按钮", html.contains("id=\"btnReset\""))

    // ══════════════════════════════════════════════════════════════════
    // 1b. 结构化输出一栏（本轮新增）
    // ══════════════════════════════════════════════════════════════════
    // 三条断言各防一件"静默"：
    //   · 选"不要求"必须**不下发** response_format（页面默认行为 = 服务端旧版行为）；
    //   · 三态齐全（none / json_object / json_schema）—— 缺一档就有一种取值发不出去；
    //   · schema 必须在**客户端先 parse**（手写 JSON 少个大括号，服务端只会回
    //     "schema 必须是对象"，而真正的原因本地就能说清）。
    ck("结构化输出选择框在", html.contains("id=\"respFmt\""))
    ck("schema 输入框在", html.contains("id=\"schemaJson\""))
    ck("有「不要求（默认）」这一档", html.contains("value=\"none\"") && html.contains("不要求（默认）"))
    ck("有 json_object 档", html.contains("value=\"json_object\""))
    ck("有 json_schema 档", html.contains("value=\"json_schema\""))
    ck("选中『不要求』时不下发 response_format（条件分支在）",
        html.contains("if (rf === 'json_object')") && html.contains("=== 'json_schema'"))
    ck("schema 在客户端先 JSON.parse（本地就能指出语法错）",
        html.contains("JSON.parse(raw)"))
    ck("schema 不是对象时本地就拦（不白跑一趟 400）",
        html.contains("Array.isArray(parsed)"))
    ck("页面里写明『不要求』= 不下发该字段", html.contains("不要求」不下发该字段"))

    // ══════════════════════════════════════════════════════════════════
    // 2. 字段名与 SamplingParams 请求侧逐字一致（漂移 = 静默不生效）
    // ══════════════════════════════════════════════════════════════════
    // 这一条是本文件最重要的断言：页面字段名如果写成 `temperature_` 之类，
    // 服务端读不到就走默认值，页面上却"看起来填了"——
    // 不报错、不异常，用户只会说"参数不生效"。
    val samplingKeys = WebChatPage.SAMPLING_FIELDS.map { it.key }
    ck("生成与采样含 temperature", samplingKeys.contains("temperature"))
    ck("生成与采样含 top_p", samplingKeys.contains("top_p"))
    ck("生成与采样含 top_k", samplingKeys.contains("top_k"))
    ck("生成与采样含 min_p", samplingKeys.contains("min_p"))
    ck("生成与采样含 max_tokens", samplingKeys.contains("max_tokens"))
    ck("生成与采样含 seed", samplingKeys.contains("seed"))
    val penaltyKeys = WebChatPage.PENALTY_FIELDS.map { it.key }
    ck("重复与惩罚含 repeat_penalty", penaltyKeys.contains("repeat_penalty"))
    ck("重复与惩罚含 repeat_last_n", penaltyKeys.contains("repeat_last_n"))
    ck("重复与惩罚含 frequency_penalty", penaltyKeys.contains("frequency_penalty"))
    ck("重复与惩罚含 presence_penalty", penaltyKeys.contains("presence_penalty"))

    // 字段定义只有一处：页面里的 JS 数组由 Kotlin 序列化而来，
    // 于是"页面渲染的字段"与"断言检查的字段"天然同源。
    ck("页面内嵌了字段定义 JSON（不是手写第二份）", html.contains("\"key\":\"temperature\""))
    ck("惩罚组字段也内嵌了", html.contains("\"key\":\"repeat_penalty\""))

    // 默认值必须与 SamplingParams 的 DEF_* 同源，不能各写一份字面量。
    val tempField = WebChatPage.SAMPLING_FIELDS.first { it.key == "temperature" }
    ck("temperature 默认值引 SamplingParams.DEF_TEMP", tempField.def == WebChatPage.fmt(SamplingParams.DEF_TEMP))
    val maxField = WebChatPage.SAMPLING_FIELDS.first { it.key == "max_tokens" }
    ck("max_tokens 默认值引 SamplingParams.DEF_MAX_TOKENS",
        maxField.def == SamplingParams.DEF_MAX_TOKENS.toString())
    val repField = WebChatPage.PENALTY_FIELDS.first { it.key == "repeat_penalty" }
    ck("repeat_penalty 默认值引 SamplingParams.DEF_REPEAT_PENALTY",
        repField.def == WebChatPage.fmt(SamplingParams.DEF_REPEAT_PENALTY))

    // 取值界要与服务端校验一致：页面放宽 > 服务端 400，用户不知道是自己越界了。
    ck("temperature 上限与服务端一致（5）", tempField.max == SamplingParams.TEMP_CAP.toDouble())
    ck("max_tokens 上限与服务端一致", maxField.max == SamplingParams.MAX_TOKENS_CAP.toDouble())
    ck("frequency_penalty 是 [-2,2]",
        WebChatPage.PENALTY_FIELDS.first { it.key == "frequency_penalty" }.let { it.min == -2.0 && it.max == 2.0 })

    // ══════════════════════════════════════════════════════════════════
    // 2b. 取值界：**唯一来源** + 与服务端判据**逐条对应**（H-2）
    // ══════════════════════════════════════════════════════════════════
    // 以前界是逐字段手抄的字面量，且 TEMP_CAP/TOP_K_CAP/PENALTY_ABS_CAP 是 private，
    // 页面拿不到只能抄 —— 于是**已经漂了两处**：top_p 抄成下界 0.0（服务端要 >0）、
    // min_p 抄成上界 1.0（服务端要 <1）。更糟的是这些界从没设到 <input> 上，纯死数据。
    //
    // 现在界只有一份来源：`SamplingParams.boundsOf`，与各 `*Ok` 一一对应。
    // 判据是**从两侧各抽一遍再比对**，而不是写死"等于 5.0"（写死改一边不会红）。
    //
    // 服务端判据 → 客户端界，逐字段核对：
    // 一项「界 vs 服务端判据」的对照：key / 服务端 *Ok / 合法样本 / 非法样本。
    data class BoundCase(
        val key: String, val ok: (Double) -> Boolean, val good: Double, val evil: Double)

    val boundChecks = listOf(
        BoundCase("temperature", { v -> SamplingParams.tempOk(v.toFloat()) }, 2.0, 5.5),
        BoundCase("top_p", { v -> SamplingParams.topPOk(v.toFloat()) }, 0.5, 0.0),
        BoundCase("min_p", { v -> SamplingParams.minPOk(v.toFloat()) }, 0.5, 1.0),
        BoundCase("top_k", { v -> SamplingParams.topKOk(v.toInt()) }, 10.0, 2_000_000.0),
        BoundCase("max_tokens", { v -> SamplingParams.maxTokensOk(v.toInt()) }, 100.0, 0.0),
        BoundCase("repeat_penalty", { v -> SamplingParams.repeatPenaltyOk(v.toFloat()) }, 1.2, 0.5),
        BoundCase("repeat_last_n", { v -> SamplingParams.repeatLastNOk(v.toInt()) }, 64.0, -1.0),
        BoundCase("frequency_penalty", { v -> SamplingParams.penaltyOk(v.toFloat()) }, 1.0, 3.0),
        BoundCase("presence_penalty", { v -> SamplingParams.penaltyOk(v.toFloat()) }, -1.0, -3.0),
    )
    var bDisagree = 0
    val bDetail = StringBuilder()
    for (cse in boundChecks) {
        // 合法样本两侧都收；非法样本两侧都拒。任一不一致 = 界与判据漂移。
        val b = SamplingParams.boundsOf(cse.key)
        if (b == null) { bDisagree++; bDetail.append("${cse.key}(无界) "); continue }
        val agree =
            b.contains(cse.good) && cse.ok(cse.good) &&
                !b.contains(cse.evil) && !cse.ok(cse.evil)
        if (!agree) { bDisagree++; bDetail.append("${cse.key}(good=${cse.good} evil=${cse.evil}) ") }
    }
    ck("9 个字段的界与服务端判据逐条一致（含端点排他）", bDisagree == 0)
    if (bDisagree != 0) println("      漂移：$bDetail")

    // 两处**已漂移**的历史值必须已修正 —— 直接点名钉住。
    ck("top_p 下界不是 0（服务端要求 >0）",
        SamplingParams.boundsOf("top_p")!!.let { it.min == 0.0 && it.minExclusive } &&
            !SamplingParams.boundsOf("top_p")!!.contains(0.0))
    ck("min_p 上界不是 1（服务端要求 <1）",
        SamplingParams.boundsOf("min_p")!!.let { it.max == 1.0 && it.maxExclusive } &&
            !SamplingParams.boundsOf("min_p")!!.contains(1.0))

    // 整数字段必须被标出整数性（服务端走 readInt，512.5 会被 400）。
    ck("整数字段带上 integer 判据（top_k/max_tokens/repeat_last_n/seed）",
        listOf("top_k", "max_tokens", "repeat_last_n", "seed").all {
            SamplingParams.boundsOf(it)!!.integral })
    ck("浮点字段不带 integer 判据（temperature/top_p…）",
        listOf("temperature", "top_p", "min_p", "repeat_penalty").none {
            SamplingParams.boundsOf(it)!!.integral })

    // 页面字段表持有的界**等于** boundsOf 的产物（同源，不是复制成字面量后再改）。
    // 用 `==` 而不是 `===`：Bounds 是 data class，每次调用返回新实例，
    // 但逐字段相等就说明"页面的界不可能与服务端判据分叉"—— 这正是要钉的不变量。
    ck("页面字段的界与 boundsOf 同源（temperature）",
        tempField.bounds == SamplingParams.boundsOf("temperature"))
    ck("页面字段的界与 boundsOf 同源（max_tokens）",
        maxField.bounds == SamplingParams.boundsOf("max_tokens"))
    // 更强的一层：字段表里**每一个**字段的界都必须等于 boundsOf 的产物。
    var allSame = true
    for (f in WebChatPage.SAMPLING_FIELDS + WebChatPage.PENALTY_FIELDS)
        if (f.bounds != SamplingParams.boundsOf(f.key)) allSame = false
    ck("10 个字段的界全部来自 boundsOf（无一处手抄漂移）", allSame)

    // ══════════════════════════════════════════════════════════════════
    // 2c. 界**真的生效**（H-2 的另一半：以前界从没设到控件、也没本地校验）
    // ══════════════════════════════════════════════════════════════════
    ck("渲染时把界设到控件上（min/max/step 真的在用）",
        html.contains("inp.dataset.min = f.min") && html.contains("inp.dataset.max = f.max") &&
            html.contains("inp.step = f.step"))
    ck("排他端不写进 min/max（浏览器不会把它当合法端点放行）",
        html.contains("if (!f.minExclusive) inp.min = f.min") &&
            html.contains("if (!f.maxExclusive) inp.max = f.max"))
    ck("整数字段用整数输入模式",
        html.contains("inp.inputMode = f.integral ? 'numeric' : 'decimal'"))
    // 本地校验函数必须存在且判据齐全（非数字 / 越界 / 排他端 / 整数性）。
    ck("有本地取值校验函数", html.contains("function valueProblem("))
    ck("本地校验判非数字", html.contains("if (!isFinite(n)) return '不是数字'"))
    ck("本地校验判排他下界", html.contains("inp.dataset.minExclusive && n <= lo"))
    ck("本地校验判排他上界", html.contains("inp.dataset.maxExclusive && n >= hi"))
    ck("本地校验判整数性", html.contains("inp.dataset.integral && n !== Math.floor(n)"))
    ck("readSettings 真的用上了本地校验（不是声明了不调）",
        html.contains("const why = valueProblem(inp, n)"))

    // ══════════════════════════════════════════════════════════════════
    // 2d. 坏项**整轮不发**（H-1：注释说不发送，代码里没有 return）
    // ══════════════════════════════════════════════════════════════════
    // 以前 bad 分支走完继续 fetch：用户选了 json_schema，拿到无约束生成，
    // 提示"本轮不下发"读起来像"这一轮不发"，实际是"发了但没约束"，模型照常回答。
    ck("bad 分支真的 return（不发请求）", html.contains("本轮**没有发送**") &&
        html.contains("    return;"))
    ck("bad 分支在 bubble 之前（不留气泡痕迹）",
        html.indexOf("本轮**没有发送**") < html.indexOf("bubble('我', 'me')"))
    ck("bad 提示措辞可区分「拒发」与「忽略」（不再写含混的『本轮不下发』）",
        !html.contains("这些项没填对，本轮不下发"))

    // ══════════════════════════════════════════════════════════════════
    // 2e. modelLoaded 必须**真的出现在渲染结果里**（H-3：存而不用就该删）
    // ══════════════════════════════════════════════════════════════════
    val loaded = WebChatPage.html(8080, true, true, "m.gguf")
    val notLoaded = WebChatPage.html(8080, true, false, "m.gguf")
    ck("未加载模型时页面顶部有显式提示", notLoaded.contains("id=\"noModel\""))
    ck("未加载模型的提示点名『没有加载模型』", notLoaded.contains("没有加载模型"))
    ck("已加载模型时不出那条提示", !loaded.contains("id=\"noModel\""))
    ck("modelLoaded 参数不再是死参数（有无它渲染结果不同）", loaded != notLoaded)

    // ══════════════════════════════════════════════════════════════════
    // 2f. 流收尾要对残留 buf 再消费一次（H-4：末帧无尾随换行会被吞）
    // ══════════════════════════════════════════════════════════════════
    ck("解析逻辑抽成一个可复用的 consume(line)",
        html.contains("const consume = (line) =>"))
    ck("流中完整行走 consume", html.contains("for (const line of lines) consume(line)"))
    ck("流结束后对残留 buf 再消费一次（用同一个 consume，判据不漂移）",
        html.contains("for (const line of buf.split('\\n')) consume(line)"))
    ck("收尾前 flush 解码器尾字节", html.contains("buf += dec.decode()"))

    // ══════════════════════════════════════════════════════════════════
    // 3. 多轮连贯对话
    // ══════════════════════════════════════════════════════════════════
    // 服务端无状态，连贯性完全靠页面把历史带上。三条缺一不可：
    ck("页面维护历史数组", html.contains("let history = []"))
    ck("每轮把历史拼进 messages（不只发最后一句）", html.contains("for (const m of history) messages.push(m)"))
    ck("成功回复追加进历史", html.contains("history.push({ role: 'assistant', content: text })"))
    ck("用户这句也进历史", html.contains("history.push(userMsg)"))
    ck("body 里带的是拼好的 messages", html.contains("messages: messages"))
    ck("有「新对话」按钮", html.contains("id=\"btnNew\""))
    // 清空历史必须同时清掉渲染出来的气泡，否则视觉与上下文不一致。
    ck("新对话同时清历史与气泡", html.contains("history = []") && html.contains("$('thread').innerHTML = ''"))
    // 失败/空回复不入历史：半截内容进了历史，后面每轮都在喂坏上下文。
    ck("空回复不入历史（pending = null 后 return）",
        html.contains("pending = null;   // 空回复也不入历史"))
    ck("HTTP 错误不入历史", html.contains("pending = null;") && html.contains("aiBody.classList.add('err')"))
    ck("system 提示可填且只放一次", html.contains("id=\"sys\"") && html.contains("role: 'system'"))
    ck("有流式正文渲染容器", html.contains("id=\"thread\""))

    // ══════════════════════════════════════════════════════════════════
    // 停止 = 一轮的结束：句柄必须**同步**清掉（Issue #154）
    // ══════════════════════════════════════════════════════════════════
    // 这三条一起才等价于"停止之后还能再发"。少任何一条的表现都是：
    // 请求确实停了（服务端收到 /v1/abort、客户端接到 AbortError），但页面
    // 自己认为"还在生成"，再点发送只会回一句"正在生成中，先停止"——
    // 用户读到的就是"停止之后再也没法开始新对话"。
    ck("停止先取走在飞句柄（const ctl = abort）", html.contains("const ctl = abort;"))
    ck("取走后同步置空（不等异步 finally）", html.contains("  abort = null;"))
    ck("仍真的调 abort() 取消那一路请求", html.contains("if (ctl) ctl.abort();"))
    // 顺序判据：把 `abort = null;` 排到 `ctl.abort()` 后面，就重新出现
    // "已停止却仍被判在生成"的那段窗口。这里比的是**代码行**之间的先后，
    // 不是"出现过"——注释里也写着这两个串，所以先滤掉注释行。
    val stopCode = html.lines()
        .dropWhile { !it.contains("$('btnStop').onclick") }
        .takeWhile { !it.startsWith("};") }
        .filterNot { it.trimStart().startsWith("//") }
    ck("置空排在 abort() 之前（顺序不能反）",
        stopCode.indexOfFirst { it.contains("abort = null;") } in 0 until
            (stopCode.indexOfFirst { it.contains("ctl.abort()") }.takeIf { it >= 0 } ?: Int.MAX_VALUE))

    // ══════════════════════════════════════════════════════════════════
    // 收尾清句柄必须带**身份**判据（同一个 Issue 的第三个面）
    // ══════════════════════════════════════════════════════════════════
    // `finally` 里那句清句柄若无条件执行，就会踩掉**后来那一轮**的 controller：
    // 用户点停止、又立刻发新一轮，旧请求的 finally 迟到一步把 `abort` 清成 null，
    // 此后「停止」对新一轮完全失效（它按的是 null）—— 同样不报错、不打日志。
    // 判据是**身份**（`abort === myCtl`）而不是"abort 是不是 null"：后者在
    // "新一轮还没赋值"的那一瞬同样成立，等于没判。
    ck("本轮句柄自己留一份（myCtl）", html.contains("const myCtl = new AbortController();"))
    ck("全局句柄指向本轮这一份", html.contains("abort = myCtl;"))
    ck("请求的 signal 来自本轮自己的句柄", html.contains("signal: myCtl.signal,"))
    ck("收尾清句柄带身份判据（不是无条件清）",
        html.contains("} finally { if (abort === myCtl) abort = null; }"))

    // 停止按钮仍然在（服务端要跑满 max_tokens 才发现断连）。
    ck("停止按钮仍在", html.contains("btnStop") && html.contains("/v1/abort"))
    ck("思考段单独渲染（reasoning_content 不混进正文）", html.contains("reasoning_content"))

    // ══════════════════════════════════════════════════════════════════
    // 3.5 鉴权（Issue #40 第 2 步）：页面必须带上凭据，且"留空"时不能拼空 Bearer
    // ══════════════════════════════════════════════════════════════════
    // 三条硬判据，理由见 WebChatPage 文件头：
    ck("页面有 token 输入框", html.contains("id=\"token\""))
    ck("token 输入框默认为密码框（旁人一眼看不全）", html.contains("id=\"token\" type=\"password\""))
    ck("有显示/隐藏按钮（手机上要能边看边粘）", html.contains("id=\"btnTokenShow\""))
    ck("有拼 Authorization 头的唯一入口", html.contains("function authHeaders("))
    ck("拼的是 Bearer 前缀（与服务端 ApiAuth.bearer 认的形状一致）",
        html.contains("'Bearer ' + t"))
    // 留空 => 不加头。这条最容易被写成"无条件加 Authorization: Bearer "，
    // 而服务端未开鉴权是**默认状态** —— 那时空 Bearer 会判成"凭据不匹配"而 401，
    // 表现是"默认配置下自带页面直接不可用"。
    ck("token 留空时**不**加 Authorization 头",
        html.contains("if (t !== '') h['Authorization'] = 'Bearer ' + t;"))
    ck("生成请求带凭据", html.contains("headers: authHeaders({ 'Content-Type': 'application/json' })"))
    // /v1/abort 是要鉴权的生成端点：漏了它，正文照常流、只有"停止"按了没反应，
    // 看起来像"停止坏了"而不是"少带凭据"。
    ck("/v1/abort 也带凭据", html.contains("headers: authHeaders()"))
    ck("/health 探测也走统一入口（留空即不带头，服务端本就免鉴权）",
        html.contains("headers: authHeaders({ Accept: 'application/json' })"))
    // 页面自身（GET /）永远免鉴权 —— 它在地址栏里被打开，没法填 header。
    // 判据是"页面里不出现它自己的 token 校验"，而不是"没提到鉴权"（提了是对的）。
    ck("页面不自行编造 401 文案（沿用服务端给的人话）",
        html.contains("服务端给的**人话**") || html.contains("直接展示即可"))

    // ══════════════════════════════════════════════════════════════════
    // 4. 注入安全（沿用上一轮的反向判据，别在重写时丢掉）
    // ══════════════════════════════════════════════════════════════════
    // 上一轮的语料**只有 `<` 族**（`</script><script>`、`<b>`、`<i>`）。
    // 它们在属性位置也真的安全（`<` 被转义了），于是给出的是"注入已防住"的
    // **假信心** —— 而真正越界的是 `"` 族：JS 转义器把 `"` 写成 `\"`，
    // 而 HTML 没有反斜杠转义，属性照样被那个 `"` 闭合。
    // 所以本节的语料按**输出位置**分成两族，各自断言。
    //
    // 位置①：`value="…"`（HTML 属性）—— 必须走实体转义，且**无损**。
    // 无损性的**逐字符**判据：把实体还原回来必须等于输入。
    // 用不到真 HTML 解析器也能钉住 —— 只要实体表是完备的。
    // （`&amp;` 必须**最后**还原，否则会先一步把 `&amp;lt;` 吃成 `<`。）
    fun unescapeHtml(v: String): String = v
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")   // 必须最后，否则会先一步吃掉别的实体

    val attrPayloads = listOf(
        "\" onfocus=alert(1) autofocus x=\"",   // 闭合属性 + 注入事件 + 自动触发
        "\"><img src=x onerror=alert(1)>",       // 闭合属性 + 开新标签
        "' onmouseover='alert(1)",                // 单引号族（有人写单引号属性时）
        "x\" onanimationstart=alert(1) style=\"animation-name:x",
    )
    // 判据是**从 value 段里取出载荷那一截**再看它，而不是在整行里 grep 危险词 ——
    // "整行里有没有 `\" onfocus`" 会被 `placeholder="model（可留空）"` 这类
    // 无关属性干扰，而且改一个字符就恒真。所以先按 `value="` 与闭合引号切出载荷。
    fun valueSegment(row: String): String {
        val at = row.indexOf("value=\"")
        if (at < 0) return "<无 value 属性>"
        val start = at + "value=\"".length
        val end = row.indexOf('\"', start)
        return if (end < 0) "<value 属性未闭合>" else row.substring(start, end)
    }
    for (p in attrPayloads) {
        val page = WebChatPage.html(8080, true, true, p)
        val row = page.lines().first { it.contains("id=\"model\"") }
        val seg = valueSegment(row)
        // 判据①：value 段里**一个裸引号都没有** —— 旧写法产出的正是
        // `value="\" onfocus=…"`，那段里的 `"` 就是越界的那个。
        ck("value 段里无裸引号（属性没被闭合）：[$p]",
            !seg.contains('\"') && seg.isNotEmpty() && seg != "<value 属性未闭合>")
        // 判据②：载荷里的危险字符必须以**实体**形态出现，而不是反斜杠形态。
        // `"` 族看 `&quot;`、`'` 族看 `&#39;` —— 不能一刀切只要 `&quot;`。
        ck("危险字符走实体转义（非 JS 反斜杠转义）：[$p]",
            (!p.contains('\"') || seg.contains("&quot;")) &&
                (!p.contains("'") || seg.contains("&#39;")) &&
                !seg.contains("\\"))
        // 判据③：**无损** —— 实体还原后逐字符等于原串（模型名要原样回显）。
        ck("载荷无损（实体还原 == 原串）：[$p]", unescapeHtml(seg) == p)
    }

    val roundTrip = listOf("plain", "a&b", "a\"b", "a'b", "<x>", "&amp;", "汉字🚀", "`t`\\s", "=", "--")
    var rtBad = 0
    for (v in roundTrip) if (unescapeHtml(WebChatPage.escapeForHtml(v)) != v) rtBad++
    ck("escapeForHtml 对 ${roundTrip.size} 个样本无损往返", rtBad == 0)

    // `&` 必须第一个替换：否则 `&` 会把自己刚生成的实体再转一遍（`&amp;amp;`）。
    ck("escapeForHtml 先处理 &（不双转）", WebChatPage.escapeForHtml("&") == "&amp;" &&
        WebChatPage.escapeForHtml("&lt;") == "&amp;lt;" &&
        !WebChatPage.escapeForHtml("a&b").contains("&amp;amp;"))

    // 位置②：`<script>` 里的 JS 字面量 —— 仍走 JS 转义。
    val evil = WebChatPage.html(8080, false, true, "</script><script>alert(1)</script>")
    ck("模型名里的 </script> 被转义（不能形成注入）", !evil.contains("</script><script>alert(1)"))
    ck("模型名的反斜杠被转义", WebChatPage.escapeForScript("a\\b") == "a\\\\b")
    // 两个转义器**不可互换**：这是本条最容易"顺手简化"掉的地方。
    ck("escapeForScript 与 escapeForHtml 产物形状不同（不可互换）",
        WebChatPage.escapeForScript("\"") == "\\\"" && WebChatPage.escapeForHtml("\"") == "&quot;")
    ck("字段 JSON 里的 < 被转义（防止提前闭合脚本块）",
        !WebChatPage.fieldsJson(listOf(
            WebChatPage.NumberField("k", "<b>", "0", "<i>", SamplingParams.boundsOf("temperature")!!))).contains("<"))

    // 页面无外部依赖：离线场景下引 CDN 就等于打不开。
    ck("页面无外部依赖",
        !html.contains("http://cdn") && !html.contains("https://cdn") &&
            !html.contains("unpkg.com") && !html.contains("jsdelivr"))

    println()
    if (fail == 0) { println("=== WebChatPage 单测：全绿 ==="); return }
    println("=== WebChatPage 单测：FAIL $fail ===")
    kotlin.system.exitProcess(1)
}
