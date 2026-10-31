package com.xiaowan.localinference

/**
 * 自带测试页的**页面本体**：HTML/CSS/JS 的纯字符串产物，以及页面暴露的那套
 * 设置项 / 多轮会话逻辑的判据。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么从 CorsPolicy 里搬出来
 * ══════════════════════════════════════════════════════════════════════════
 * 页面最初只做一件事：把 `/health`、`/v1/models`、一次流式对话打通，
 * 作为"手机上的服务到底通不通"的最小可验证面（那次是与 CORS 一起落地的，
 * 所以放在 CorsPolicy 里）。上一版把 CORS 判据和 200 行 HTML 混在一个文件里，
 * 还能说"页面很小、顺路"；本轮页面要长出**折叠设置区 + 多轮会话**，
 * 再混在一起就是两件事共用一份文件，读 CORS 的人得先翻过 400 行 HTML。
 *
 * 更实际的理由：页面写错是**静默**的 —— 它不参与编译、不抛异常、
 * 没有任何现有测试会因为少了一个 `id` 而变红。所以页面的关键结构必须能被
 * **宿主侧离线断言**（见 `tools/web_chat/WebChatPageTest.kt`），
 * 而"能被断言"的前提是它是**纯函数产物**。
 *
 * 注意这不是"给页面接 CORS"：页面是同源的，根本不需要 CORS，
 * CORS 关掉它照样能用。两件事独立（README 有专门一节讲这个区别）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 本轮加了什么、以及每条为什么是这么加的
 * ══════════════════════════════════════════════════════════════════════════
 * 用户反馈两件事：内置网页"太简陋"、"每次都是新对话"。
 *
 * **① 折叠设置区（`<details>`）**。三组：
 *   · 思考链开关 —— 服务端**默认关闭思考**（`disableThinkingDefault`），
 *     而真机反馈里"想让它推理"是高频诉求。协议面它就是请求体的 `enable_thinking`
 *     （[ThinkingControl.requestThinkingOverride] 认这个字段），页面只要把它发出去。
 *     用**三态**（跟随默认 / 开 / 关）而不是两态复选框：
 *     服务端默认值可能被 App 里的设置改过，页面**无法预知**，
 *     硬塞一个两态开关等于替用户做了个它没资格做的决定
 *     （"跟随默认"时不下发字段，交给服务端）。
 *   · 生成与采样 —— temperature / top_p / top_k / min_p / max_tokens / seed。
 *   · 重复与惩罚 —— repeat_penalty / repeat_last_n / frequency_penalty / presence_penalty。
 *   · 结构化输出 —— `response_format`（不要求 / json_object / json_schema）。
 *     选"不要求"时**不下发**该字段，所以页面上的默认行为与服务端旧版一致。
 *     json_schema 的 schema 在**客户端先 parse 一次**再发原文：手写 JSON 打错一个
 *     括号很常见，服务端只会回一句"schema 必须是对象"，而真正的原因（少个大括号）
 *     在本地就能说清、还能指到具体哪个字符。
 *   字段名与 [SamplingParams] 请求侧**逐字一致**：页面只是这些参数的又一个客户端，
 *   名字对不上的后果是"填了没感觉"（服务端走默认值），而它不会报错。
 *   取值范围也和 [SamplingParams] 一致 —— 页面不自己发明一套更宽松的界，
 *   否则用户会拿到服务端 400 而不知道是自己填的数越界。
 *
 * **② 多轮连贯对话**。服务端是**无状态**的（每轮请求自带完整 `messages`），
 * 所以"连贯"必须由页面自己维护历史：每轮把 assistant 的回复追加进
 * `messages`，下一轮原样带上。这不是"加个变量"那么简单，有三条判据：
 *   · **只有成功的回复才进历史**。半截/报错的内容进了历史，后面每轮都在
 *     喂坏上下文 —— 而且用户看不到为什么模型开始胡说。所以 `finish_reason`
 *     或异常都要能触发"这一轮不入历史"。
 *   · **历史要能清空**（新对话），且清空要把页面上已渲染的气泡也清掉，
 *     否则历史与视觉不一致，用户以为自己还在同一个会话里。
 *   · **服务端 KV 前缀复用**（prompt cache）依赖"本轮 prompt 与上一轮共享前缀"，
 *     而多轮历史正好把它命中率拉满 —— 这条是白拿的性能收益，但前提是
 *     页面**真的把历史带上了**，而不是每轮只发最后一句。
 *
 * 另外把 `system` 提示做成可填的一项：多轮场景下 system 只该在**第一轮**
 * 有意义（服务端每轮都从头渲染 messages，带上它只是重复渲染，代价可忽略），
 * 所以它跟着历史一起留在 `messages[0]`，不需要单独每轮重拷。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 鉴权（Issue #40 第 2 步）之后页面要怎么配合
 * ══════════════════════════════════════════════════════════════════════════
 * 服务端开了鉴权后，**只有生成端点**（`/v1/chat/completions`、`/v1/completions`、
 * `/v1/abort`）要 `Authorization: Bearer <token>`。这对页面的影响只有一件事：
 * 页面对这些端点的调用必须带上凭据。三条判据缺一不可：
 *   · **`/v1/abort` 也要带** —— 它是要鉴权的生成端点。漏了它的表现最刁：
 *     正文照常流出来，只有"停止"按钮按了没反应（401 被 `<catch (_) {}>` 吞掉），
 *     看起来像"停止功能坏了"，而不是"少带了凭据"。
 *   · **token 留空时不能拼出 `Bearer `（空值）** —— 那会被服务端判成"凭据不匹配"
 *     而 401。服务端**未开鉴权**是默认状态（`ApiAuth.token` 为空），此时页面
 *     必须一个 Authorization 头都不发，否则默认配置下页面直接不可用。
 *   · token 输入框里只放**token 本身**，`Bearer ` 前缀由页面拼 —— 让用户在
 *     输入框里自己写协议前缀，是必然抄错的一步。
 *
 * 顺带澄清一件容易混的事：页面**自身**（`GET /`）永远免鉴权。它在浏览器地址栏里
 * 被打开，那里没有任何地方能填 header；要它带 token 就等于页面永远打不开。
 * 所以本页的 token 输入框只服务于它发出去的生成端点调用。
 */
object WebChatPage {

    /** 页面标题（测试与断言都引它，避免两处各写一份）。 */
    const val PAGE_TITLE = "Local LLM Server · 自带测试页"

    /**
     * 请求体里 `enable_thinking` 的页面取值语义。
     *
     * 为什么是三态而不是布尔：服务端的思考默认值由 App 设置决定
     * （`disableThinkingDefault`），页面**读不到**它。两态复选框会强迫页面
     * 二选一下发，等于把"跟随服务端默认"这个最常见的诉求变成不可表达。
     * [DEFAULT] 对应"不下发该字段"，由服务端自行决定。
     */
    enum class ThinkChoice(val wireValue: String?) {
        DEFAULT(null),
        ON("true"),
        OFF("false");

        /** 请求体里该不该带 `enable_thinking`；DEFAULT 不带。 */
        val sends: Boolean get() = wireValue != null
    }

    /**
     * 页面设置区里一个数值项的定义：界面标签、参数名、默认值、取值界。
     *
     * [min]/[max]/[step]/[integral] **不是**这里填的字面量，而是构造时从
     * [SamplingParams.boundsOf] 取的 —— 取值界只有**一份**来源。
     *
     * 为什么这件事必须有类型层面的保证：这些界原先是在这里逐字段手抄的
     * （`0.0, 5.0`、`0.0, 1.0`…），而 `TEMP_CAP` / `TOP_K_CAP` / `PENALTY_ABS_CAP`
     * 当时在 `SamplingParams` 里是 `private`，页面拿不到，只能抄。手抄的下场是
     * **已经漂了两处**：`top_p` 抄成下界 `0.0`（服务端要求 `>0`）、`min_p` 抄成上界
     * `1.0`（服务端要求 `<1`）—— 用户按页面上的界填到端点值，拿到的是 400。
     * 更糟的是这三个数当时**从没被设到 `<input>` 上**，纯死数据：
     * 界写在源码里、序列化进页面、然后就没人读了。
     *
     * 所以现在：[SamplingParams.boundsOf] 是唯一来源，它与各 `*Ok` 判据一一对应
     * （`SamplingParamsTest` 有断言钉住），页面只做转发。判据见
     * `run_web_chat_guard.sh`「取值界引同一份」那几条。
     */
    data class NumberField(
        val key: String,
        val label: String,
        val def: String,
        val hint: String,
        /** 取值界：**直接持有** [SamplingParams.boundsOf] 的产物，不复制成字面量。 */
        val bounds: SamplingParams.Bounds,
    ) {
        val min: Double get() = bounds.min
        val max: Double get() = bounds.max
        val step: Double get() = bounds.step

        /** 服务端走 `readInt`：非整数值会被 400。界面必须给整数判据。 */
        val integral: Boolean get() = bounds.integral

        /** 下界不可取（`top_p`：0 是静默失效，不是"关闭"）。 */
        val minExclusive: Boolean get() = bounds.minExclusive

        /** 上界不可取（`min_p`：1 会砍光候选）。 */
        val maxExclusive: Boolean get() = bounds.maxExclusive

        /** 该值是否落在界内，语义与 [SamplingParams.Bounds.contains] 逐条一致。 */
        fun contains(v: Double): Boolean = bounds.contains(v)
    }

    /**
     * 按参数名造一个 [NumberField]，取值界**全部**从 [SamplingParams.boundsOf] 取。
     *
     * 这是唯一允许的构造方式：直接手填 `min`/`max` 就等于把刚修掉的"手抄漂移"
     * 再种回去。界缺失时 `error()` 直接炸 —— 宁可第一次渲染就崩，
     * 也不要悄悄放一个没有界、界面上又"看起来能填"的字段过去。
     */
    fun field(key: String, label: String, def: String, hint: String): NumberField =
        NumberField(
            key = key, label = label, def = def, hint = hint,
            bounds = SamplingParams.boundsOf(key)
                ?: error("字段 $key 没有登记取值界（SamplingParams.boundsOf）"),
        )

    /**
     * 「生成与采样」组的字段。
     *
     * 默认值与 `SamplingParams` 的 `DEF_*` 引用同一个来源，不各写一份字面量：
     * 两处各写一个数就会漂移（历史上已有过一次 `min_p` 的
     * HTTP 0.05 vs UI 0 的分裂），而漂移的表现是"页面上那个数和实际用的不一样"。
     */
    val SAMPLING_FIELDS: List<NumberField> = listOf(
        field("temperature", "temperature 温度", fmt(SamplingParams.DEF_TEMP),
            "0 = 贪心解码；越高越发散"),
        field("top_p", "top_p 核采样", fmt(SamplingParams.DEF_TOP_P),
            "(0,1]，1.0 = 关闭"),
        field("top_k", "top_k", fmt(SamplingParams.DEF_TOP_K),
            "0 = 关闭"),
        field("min_p", "min_p", fmt(SamplingParams.DEF_MIN_P),
            "[0,1)，1.0 会被服务端拒（砍光候选）"),
        field("max_tokens", "max_tokens 最大生成长度", fmt(SamplingParams.DEF_MAX_TOKENS),
            "1~" + SamplingParams.MAX_TOKENS_CAP),
        // seed 留空 = 不下发，服务端用 nanoTime 生成随机种子；非整数会被 400。
        field("seed", "seed 随机种子", "",
            "整数；留空 = 每轮随机"),
    )

    /** 「重复与惩罚」组的字段。取值界同样全部引 `SamplingParams.boundsOf` 一份。 */
    val PENALTY_FIELDS: List<NumberField> = listOf(
        field("repeat_penalty", "repeat_penalty 重复惩罚", fmt(SamplingParams.DEF_REPEAT_PENALTY),
            "1.0 = 关闭，<1 会反转语义"),
        field("repeat_last_n", "repeat_last_n 惩罚窗口", fmt(SamplingParams.DEF_REPEAT_LAST_N),
            "0 = 关闭惩罚窗口"),
        field("frequency_penalty", "frequency_penalty 频率惩罚",
            fmt(SamplingParams.DEF_FREQ_PENALTY), "[-2,2]"),
        field("presence_penalty", "presence_penalty 存在惩罚",
            fmt(SamplingParams.DEF_PRESENCE_PENALTY), "[-2,2]"),
    )

    /** 把浮点默认值格式化成页面 `value=` 的样子：去掉无意义的 `.0`，但保留小数。 */
    fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    fun fmt(v: Int): String = v.toString()

    /**
     * 顶部横幅：把「服务端**当前有没有加载模型**」在打开页面时就说清。
     *
     * 这个参数在相当长一段时间里是**死参数** —— 签名里有、调用链一路带着
     * （`HttpApi` 传 `LlmEngine.hasModel` → `CorsPolicy.pageHtml` 转发 →
     * 这里），函数体里却**一次都没引用**。危害是"界面不告诉你它其实用不了"：
     * 没加载模型时用户看到的是一张完全正常的界面，敲字、点发送，才拿到一句
     * 含糊的「没有收到正文」—— 与"模型没加载"这个真因**同形**，排查方向一开始
     * 就是错的。而这个参数是**唯一**一个"调用方特意算了、页面却丢掉"的信息。
     *
     * 两种收尾都合理：用上它（本条），或删掉它。留着一个**无效果的参数**比
     * 没有它更容易误导下一个改代码的人 —— 所以这里选了"用上"，
     * 并在守卫里钉住"它必须真的出现在渲染结果里"（存了不用就该删）。
     *
     * 文案是 HTML 文本节点，走 [escapeForHtml]（此处无外部输入，写它是为了
     * 保持"落进 HTML 就实体转义"这条规则无例外可循）。
     */
    fun modelBanner(modelLoaded: Boolean): String =
        if (modelLoaded) ""
        else "<div class=\"warn\" id=\"noModel\">" +
            escapeForHtml("当前服务端**没有加载模型**：现在发请求只会拿到空回复。请先在 App 里加载模型后再试。") +
            "</div>"

    /**
     * 自带测试页（同源，内嵌 HTML/CSS/JS，零外部依赖）。
     *
     * 保留在 [CorsPolicy.pageHtml] 的同名入口不动是有意的：它是既有单测与
     * 接线守卫的锚点（`run_cors_tests.sh` / `run_cors_guard.sh` 都引它），
     * 搬实现不搬入口，两张网都还在。
     *
     * 模型名是**外部输入**（gguf 文件名可含任意字符，用户自己往 models 目录丢），
     * 而这段是拿去拼进 `value="…"` 的。不转义的话，一个叫
     * `" onfocus=alert(1) autofocus x="` 的文件名就能在这个页面上执行脚本 ——
     * 而这个页面是**服务端同源**的，等于把接口暴露给任意构造的文件名。
     *
     * 转义必须**按输出位置选**：这里是 HTML 属性，用 [escapeForHtml]。
     * 曾经用的是 [escapeForScript]，它转义的是 JS 字符串字面量（`"` → `\"`），
     * 而 HTML 没有反斜杠转义 —— 属性照样被那个 `"` 闭合，后面跟的内容
     * 变成新的属性（`onfocus=` / `autofocus` 一类），页面加载即触发。
     */
    fun html(port: Int, bindAll: Boolean, modelLoaded: Boolean, modelDesc: String?): String {
        // 模型名落进的是 `value="…"`（HTML **属性**位置），所以用实体转义。
        // 这里曾经写的是 `escapeForScript`：它把 `"` 转成 `\"`（反斜杠转义），
        // 而 HTML 没有反斜杠转义，属性只由 `"` 界定 —— 于是 `"` 照样闭合属性，
        // 后面跟的内容变成**新的属性**（`onfocus=` / `onerror=` 一类）。
        // 两个转义器按位置分开，见各自的文档注释。
        val model = escapeForHtml(modelDesc ?: "")
        // 字段定义直接来自 Kotlin 的 SAMPLING_FIELDS / PENALTY_FIELDS —— 页面与
        // 断言共用同一份来源，避免"页面里写的 key"和"测试断言的 key"各写一份。
        val fieldsJsonSampling = fieldsJson(SAMPLING_FIELDS)
        val fieldsJsonPenalty = fieldsJson(PENALTY_FIELDS)

        return ("""
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$PAGE_TITLE</title>
<style>
:root { color-scheme: light dark; }
body { font: 14px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif; margin: 0; padding: 16px; max-width: 820px; }
h1 { font-size: 17px; margin: 0 0 4px; }
.sub { color: #888; font-size: 12px; margin-bottom: 14px; }
.card { border: 1px solid #8883; border-radius: 8px; padding: 12px; margin-bottom: 12px; }
.row { display: flex; gap: 8px; }
input, textarea, button, select { font: inherit; border-radius: 6px; border: 1px solid #8885; padding: 7px 9px; background: transparent; color: inherit; }
input, textarea { flex: 1; min-width: 0; }
textarea { resize: vertical; }
button { cursor: pointer; white-space: nowrap; }
#out { white-space: pre-wrap; word-break: break-word; min-height: 3em; }
.err { color: #c62828; }
.warn { border: 1px solid #e6a700; border-radius: 8px; padding: 8px 10px; margin-bottom: 12px; font-size: 12px; color: #b26a00; }
.ok { color: #2e7d32; }
.ok, .err { font-size: 12px; white-space: pre-wrap; word-break: break-word; }
details { border: 1px solid #8883; border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; }
details > summary { cursor: pointer; font-weight: 600; }
details[open] > summary { margin-bottom: 8px; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 10px; }
@media (max-width: 560px) { .grid { grid-template-columns: 1fr; } }
.fld { display: flex; flex-direction: column; gap: 2px; }
.fld label { font-size: 12px; color: #888; }
.fld input, .fld select { width: 100%; box-sizing: border-box; }
.hint { font-size: 11px; color: #999; }
#thread { display: flex; flex-direction: column; gap: 8px; max-height: 46vh; overflow-y: auto; padding: 4px 2px; }
.bub { border-radius: 8px; padding: 7px 10px; white-space: pre-wrap; word-break: break-word; max-width: 86%; cursor: default; }
.bub .who { font-size: 11px; color: #888; margin-bottom: 3px; }
/* 左右分栏靠 flex 行 + 自动外边距，不靠 text-align —— 后者只影响行内文字，
   长消息撑满整行时"谁说的"就分不出来了（App 内那处踩的是同一个坑）。 */
.line { display: flex; }
.line.me { justify-content: flex-end; }
.line.ai { justify-content: flex-start; }
.bub.long { max-width: 100%; }
.me { background: #8881; }
.ai { background: #4a71; }
.think { font-size: 12px; color: #888; border-left: 2px solid #8885; padding-left: 8px; margin: 4px 0; white-space: pre-wrap; }
.rowgap { display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap; }

/* 三页横向切换（需求4）。三块内容都在 DOM 里、只切 display ——
   不用 transform 位移是因为那会把"水平滑动"这个词的真正含义让给 CSS：
   用户不会看到别的页在隔壁滑过来（那是 ViewPager 的活），
   而本页只需要"换个内容块"，做位移动画反而在长会话下更卡。 */
.tabs { display: flex; gap: 6px; margin: 10px 0 4px; }
.tab { flex: 1; padding: 7px 0; border: 1px solid #ddd; border-radius: 6px;
       background: #fafafa; color: #888; font-size: 13px; }
.tab.on { background: #1a73e81a; border-color: #1a73e8; color: #1a73e8; font-weight: 600; }
.page { display: none; }
.page.on { display: block; }
</style>
</head>
<body>
<h1>$PAGE_TITLE</h1>
<div class="sub">本页由手机端服务直接提供（同源），不需要 CORS。端口 <b id="port">$port</b>，局域网访问 <b id="lan">${if (bindAll) "已开启" else "未开启（仅回环）"}</b>。</div>
${modelBanner(modelLoaded)}

<!-- (需求4): 左右滑动翻页。App 内是三页 Tab，网页版没有分页的理由 ——
     同一块屏上「聊天 / 设置 / 探测」三件事挤在一起，手机上要划很久才摸到输入框。
     做成**三页横向切换**之后，翻页靠左右滑动与顶部页签（两种入口都要：
     手指划得动的地方眼不一定看得见页签，反之亦然）。
     滑动的判据与 App 内那处同一套：|dx| > |dy| * 1.5 且够远，横滑不抢竖向滚动。
     ⚠ 落点必须挂在**内容节点**上，不能挂在 document 上：页面里有两个输入框
     （schema 的 textarea、对话的 textarea），挂 document 会连它们的光标移动一起抢。 -->
<div class="tabs" id="tabs">
  <button class="tab on" data-page="0">聊天</button>
  <button class="tab" data-page="1">设置</button>
  <button class="tab" data-page="2">探测</button>
</div>
<div id="pager">
<div class="page on" data-page="0">
<div class="card">
  <div class="sub">对话（流式，多轮连贯：页面自己把历史带上，服务端无状态）。模型 id 留空即用服务端当前模型。</div>
  <div class="row"><input id="model" placeholder="model（可留空）" value="$model"></div>
  <div class="row" style="margin-top:8px"><input id="sys" placeholder="system 提示（可留空）"></div>
  <!-- token 输入框放在**对话卡片里**，而不是设置区：
       它是"这一页能不能发请求"的前置条件，而设置区默认是折叠的。
       留空 = 不发 Authorization 头 —— 服务端未开鉴权时这就是正确用法；
       服务端开了鉴权时，不带 token 的生成请求会得到 401（下面的错误提示会说出来）。 -->
  <div class="row" style="margin-top:8px">
    <input id="token" type="password" placeholder="token（服务端开了鉴权才需要；留空 = 不带凭据）">
    <button id="btnTokenShow" title="显示/隐藏">👁</button>
  </div>
  <div class="hint">token 在 App 设置页「接口鉴权」一栏生成/复制。只用于 <code>/v1/*</code>；<code>/health</code>、<code>/v1/models</code> 与本页自身都不需要。</div>
  <div id="thread"></div>
  <div class="hint">长按任一条可单独删除；左右滑动或点上方页签可切换「聊天 / 设置 / 探测」。删除只影响这一条，<b>不会</b>连带删掉配对的那一条 —— 历史少了哪一段，后面的请求就少发哪一段。</div>
  <div class="row" style="margin-top:8px"><textarea id="q" rows="3" placeholder="说点什么…">用一句话解释 KV cache</textarea></div>
  <div class="rowgap">
    <button id="btnSend">发送</button>
    <button id="btnStop">停止</button>
    <button id="btnNew">新对话</button>
  </div>
  <div id="out" class="hint"></div>
</div>

</div><!-- /page 0 聊天 -->
<div class="page" data-page="1">
<details id="detailsSettings">
<summary>设置（思考链 · 生成与采样 · 重复与惩罚 · 结构化输出）</summary>
  <div class="fld" style="margin-bottom:8px">
    <label for="think">思考链（enable_thinking）</label>
    <select id="think">
      <option value="default" selected>跟随服务端默认</option>
      <option value="true">开（让模型先推理）</option>
      <option value="false">关</option>
    </select>
    <span class="hint">服务端默认关闭思考；此处「跟随」表示不下发该字段。</span>
  </div>

  <div class="sub" style="margin:10px 0 4px">生成与采样</div>
  <div class="grid" id="gridSampling"></div>

  <div class="sub" style="margin:10px 0 4px">重复与惩罚</div>
  <div class="grid" id="gridPenalty"></div>

  <div class="sub" style="margin:10px 0 4px">结构化输出（response_format）</div>
  <div class="fld">
    <label for="respFmt">输出格式</label>
    <select id="respFmt">
      <option value="none" selected>不要求（默认）</option>
      <option value="json_object">json_object（要合法 JSON，不限结构）</option>
      <option value="json_schema">json_schema（按下框的 schema 约束）</option>
    </select>
    <span class="hint">「不要求」不下发该字段，行为与服务端旧版一致。schema 写错会 400；
      库转不出 GBNF 时不报错、降级成无约束（App 日志里有原因）。</span>
  </div>
  <div class="fld">
    <label for="schemaJson">JSON Schema（仅 json_schema 时下发）</label>
    <textarea id="schemaJson" spellcheck="false" placeholder='{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}'></textarea>
    <span class="hint" id="schemaHint"></span>
  </div>

  <div class="rowgap">
    <button id="btnReset">恢复默认</button>
    <span class="hint">留空的项不下发，由服务端取默认值。</span>
  </div>
</details>
</div><!-- /page 1 设置 -->

<div class="page" data-page="2">

<div class="card">
  <div class="row"><button id="btnHealth">探测 /health</button><button id="btnModels">看 /v1/models</button></div>
  <div id="health" class="ok">未探测</div>
</div>

</div><!-- /page 2 探测 -->

</div><!-- /pager -->

<div class="sub">注意：本服务无鉴权。开局域网访问后，同网段任何人都能调用，请在可信网络使用或及时关闭。</div>

<script>
const $ = (id) => document.getElementById(id);
let abort = null;
/* 本轮请求占的是**哪一格**（服务端在响应头 X-Slot-Id 里告诉我们的）。
   为什么要它：单格池里 App 与 HTTP 共用 slot 0，`/health` 的 `slot_active` 一格只放
   得下一个名字 —— 页面**推不出**"我这一格是不是我这一路"。停止时带错格子的后果是
   「停到了别人的回答」，而不带格子的后果是「什么都没停到，服务端照跑满」
   （ISSUE #403 的现场，客户端只看到自己那侧 `Fetch request has been canceled`）。
   null = 服务端没给（老服务端 / 反向代理吃掉了头）→ 停止退回不带 slot 的旧语义。 */
let abortSlot = null;
// 多轮历史：服务端无状态，连贯性完全由这里维持。
// 元素形状就是 OpenAI 的 message（role/content），直接塞进下一轮请求体。
let history = [];
/* 气泡节点列表（与**非 system** 的历史消息一一对应，顺序一致）。
   长按删除靠它把"哪个节点"换算回"history 的第几条"。 */
let bubbles = [];
// 当前这一轮流式出来的正文；中途停止或报错时不入历史（半截内容进历史 = 后面每轮都喂坏上下文）。
let pending = null;

/* 设置项定义由 Kotlin 侧生成（与本文件顶部的 SAMPLING_FIELDS / PENALTY_FIELDS 同源），
   这里只负责渲染与取值 —— 两处各写一份字段名必然漂移。 */
const SAMPLING_FIELDS = $fieldsJsonSampling;
const PENALTY_FIELDS = $fieldsJsonPenalty;

function renderFields(hostId, fields) {
  const host = $(hostId);
  for (const f of fields) {
    const wrap = document.createElement('div');
    wrap.className = 'fld';
    const lab = document.createElement('label');
    lab.textContent = f.label;
    lab.setAttribute('for', 'f_' + f.key);
    const inp = document.createElement('input');
    inp.id = 'f_' + f.key;
    inp.dataset.key = f.key;
    inp.dataset.def = f.def;
    inp.value = f.def;
    inp.placeholder = f.def === '' ? '留空 = 随机' : f.def;
    // 取值界**真的设到控件上**：这一条以前是死的 —— min/max/step 声明了、
    // 序列化进页面了，但渲染时一个都没设，浏览器完全看不到。
    // 现在界由 Kotlin 的同一条 boundsOf 下发，端点排他与整数性也一起表达：
    //   · 排他端（top_p 的 0、min_p 的 1）**不写 min/max**，否则浏览器会把它当
    //     合法端点放行 —— 那里正是服务端 400 的地方。改用 data-* 带上判据，
    //     由下面的本地校验真的拦下来。
    //   · 整数项（top_k / max_tokens / repeat_last_n / seed）给 step=1 与整数键盘，
    //     否则 512.5 会原样发出、白跑一趟 400。
    if (!f.minExclusive) inp.min = f.min;
    if (!f.maxExclusive) inp.max = f.max;
    inp.step = f.step;
    inp.dataset.min = f.min;
    inp.dataset.max = f.max;
    inp.dataset.minExclusive = f.minExclusive ? '1' : '';
    inp.dataset.maxExclusive = f.maxExclusive ? '1' : '';
    inp.dataset.integral = f.integral ? '1' : '';
    inp.inputMode = f.integral ? 'numeric' : 'decimal';
    const hint = document.createElement('span');
    hint.className = 'hint';
    hint.textContent = f.hint;
    wrap.appendChild(lab); wrap.appendChild(inp); wrap.appendChild(hint);
    host.appendChild(wrap);
  }
}

function allFieldInputs() {
  return Array.prototype.slice.call(document.querySelectorAll('input[data-key]'));
}

function resetFields() {
  for (const inp of allFieldInputs()) inp.value = inp.dataset.def;
  $('think').value = 'default';
}

/* 判一个输入框的值是否合法。返回 null 表示合法，否则返回**给用户看的原因**。
   判据与 SamplingParams.Bounds.contains **逐条对应**（界由 Kotlin 下发）：
     · 非数字 / 非有限 => 非法；
     · 排他端点 => 非法（top_p 的 0、min_p 的 1 都是服务端 400 的地方）；
     · 整数字段带小数 => 非法（服务端走 readInt，512.5 会被 400）。

   为什么把界搬到本地：这正是文件头承诺的收益 ——「用户会拿到服务端 400 而不知道
   是自己填的数越界」。以前界是**死数据**（声明了、序列化了、从没设到控件上、
   readSettings 也从不校验），于是六个越界值原样发出、白跑一趟 400。
   本地判据不替服务端做决定：它只挡"本地就说得清"的输入，措辞与服务端一致。 */
function valueProblem(inp, n) {
  if (!isFinite(n)) return '不是数字';
  const lo = Number(inp.dataset.min), hi = Number(inp.dataset.max);
  if (inp.dataset.minExclusive && n <= lo) return '必须 > ' + lo;
  if (!inp.dataset.minExclusive && n < lo) return '必须 >= ' + lo;
  if (inp.dataset.maxExclusive && n >= hi) return '必须 < ' + hi;
  if (!inp.dataset.maxExclusive && n > hi) return '必须 <= ' + hi;
  if (inp.dataset.integral && n !== Math.floor(n)) return '必须是整数';
  return null;
}

/* 把设置区读成请求体里的字段。判据：
   · 留空 => **不下发**（服务端取默认值），而不是下发空串；
   · 数字非法（NaN / 越界 / 该整数却是小数）=> 不下发，并记进 bad ——
     由调用方**整轮不发请求**（见 btnSend）。以前只是"标红但照样发"：
     用户明确选了 json_schema 却拿到无约束生成，提示写"本轮不下发"、
     实际是"发了但没约束" —— 正是本项目反复在防的"哑得不响"。 */
function readSettings() {
  const out = {};
  const bad = [];
  for (const inp of allFieldInputs()) {
    const raw = inp.value.trim();
    if (raw === '') { inp.classList.remove('err'); continue; }
    const n = Number(raw);
    const why = valueProblem(inp, n);
    if (why !== null) { inp.classList.add('err'); bad.push(inp.dataset.key + '（' + why + '）'); continue; }
    inp.classList.remove('err');
    out[inp.dataset.key] = n;
  }
  const t = $('think').value;
  if (t === 'true') out.enable_thinking = true;
  else if (t === 'false') out.enable_thinking = false;
  // 结构化输出：'none' 时**不下发** response_format（与旧版行为一致）；
  // json_schema 的 schema 必须在客户端先 parse 一次再发原文 —— 页面里手写的 JSON
  // 打错一个括号是很常见的事，服务端只会回一句 "schema 必须是对象"，
  // 而真正的原因（少了个大括号）在本地就能说清。
  const rf = $('respFmt').value;
  const schemaEl = $('schemaJson');
  schemaEl.classList.remove('err');
  $('schemaHint').textContent = '';
  if (rf === 'json_object') {
    out.response_format = { type: 'json_object' };
  } else if (rf === 'json_schema') {
    const raw = schemaEl.value.trim();
    if (!raw) {
      schemaEl.classList.add('err');
      $('schemaHint').textContent = '选了 json_schema 就必须填 schema。';
      bad.push('schema');
    } else {
      let parsed = null;
      try { parsed = JSON.parse(raw); } catch (e) {
        schemaEl.classList.add('err');
        $('schemaHint').textContent = 'schema 不是合法 JSON：' + e.message;
        bad.push('schema');
      }
      if (parsed !== null) {
        if (typeof parsed !== 'object' || Array.isArray(parsed)) {
          schemaEl.classList.add('err');
          $('schemaHint').textContent = 'schema 必须是 JSON 对象（不能是数组/字符串/数字）。';
          bad.push('schema');
        } else {
          $('schemaHint').textContent = '将下发 ' + raw.length + ' 字符的 schema。';
        }
      }
    }
  }
  return { body: out, bad: bad };
}

function show(el, text, cls) { el.className = cls || ''; el.textContent = text; }

/* 一条消息 = 一个气泡。返回 {el, body}：
   · el 要留在 `bubbles` 里（长按删除时要靠它在列表里的**下标**定位消息）；
   · body 是正文节点，流式只改它的 textContent（不重建任何节点）。

   为什么返回对象而不是像上一版那样只返回 body：删除要知道"删的是哪一条"，
   而列表随时可能因为"新对话"重绘 —— 所以下标必须在长按那一刻**当场 indexOf**，
   不能在闭包里捕获（捕获值在重绘之后就指向别的消息了）。 */
function bubble(who, cls) {
  const line = document.createElement('div');
  line.className = 'line ' + cls;
  const b = document.createElement('div');
  b.className = 'bub ' + cls;
  const w = document.createElement('div');
  w.className = 'who';
  w.textContent = who;
  const body = document.createElement('div');
  b.appendChild(w); b.appendChild(body);
  line.appendChild(b);
  $('thread').appendChild(line);
  $('thread').scrollTop = $('thread').scrollHeight;
  line.oncontextmenu = () => {      // 移动端长按；桌面上右键同效
    // 生成中的闸门在 removeAt 里（唯一判据），这里只负责把"是哪一条"当场问出来 ——
    // 下标必须在长按这一刻 indexOf，不能在闭包里捕获（重绘后捕获值会指向别的消息）。
    const idx = bubbles.indexOf(line);
    if (idx >= 0) removeAt(idx);
    return false;
  };
  return { el: line, body: body };
}

/* 删除第 idx 条消息。**同时**删气泡与 history 里对应的那一条 ——
   只删一边就会出现"视觉与上下文不一致"：用户以为自己删掉了，模型还记得，
   或者反过来（页面上还在，其实已经不进历史）。这一类不一致无法从输出发现。

   ══════════════════════════════════════════════════════════════════════
   生成中**不许删**（与 App 内同一道闸门）
   ══════════════════════════════════════════════════════════════════════
   这一条是被一个**静默失效**逼出来的：`bubbles` 在本轮**发送时**就登记了两条，
   而 `history` 只在**成功收尾时**才 push。中间那段窗口里 `hi < history.length`
   恒不成立 -> 气泡真删了、history 没动 -> 收尾时又把那两条 push 进 history。
   结果是"用户明确删掉了那条回答，它却仍在上下文里，之后每轮都喂给模型"。

   而它**没有任何症状**：气泡确实从页面消失了，用户以为删成功了。这与本文件
   开头自己写的「**同时**删气泡与 history」正好相反 —— 写下来的意图与实现分叉。

   修法取"与 App 对齐"：App 内生成中删单条时会回一句 `生成中，请先停止` 并
   `return`（生成中的流式气泡**不进**可删列表）。网页版此前**没有**这道闸门，
   于是同一个动作两边行为不同，而分叉的方向恰好是"App 拦住了、网页版静默吞掉"。
   闸门用现成的 `abort !== null`（它是"页面认为有一轮在飞"的唯一判据）。

   另外：即便不生成，`hi` 越界时也**不再静默跳过** —— 那正是上面那种"删了不算"
   的形态。现在会明确说出来，让不一致暴露在页面上而不是藏在上下文里。 */
function removeAt(idx) {
  if (abort) { show($('out'), '生成中，请先停止再删除（本轮的回答还没定型）。', 'err'); return; }
  // history 的下标与气泡下标**不一定相等**：system 那条只进 history、不建气泡。
  // 所以按"气泡前面有几个 system"换算，而不是假定差值为 0 或 1。
  const sysCount = history.filter((m) => m.role === 'system').length;
  const hi = idx + sysCount;
  // 换算结果必须**落在 history 里**才算"两边都删了"。越界说明两个视图已经错位
  // （气泡比 history 多），这时只删气泡就会留下"页面上没了、模型还记得"。
  // 所以不静默跳过：把错位说出来，并把那一侧的账也补平（下面按 idx 反查 history）。
  const inRange = hi >= 0 && hi < history.length;
  if (inRange) history.splice(hi, 1);
  const el = bubbles[idx];
  if (el && el.parentNode) el.parentNode.removeChild(el);
  bubbles.splice(idx, 1);
  if (inRange) {
    show($('out'), '已删除这一条（不再进入后续请求的历史）。', '');
  } else {
    // 气泡删了、history 里没有对应项 —— 只能发生在"两个视图已经错位"时。
    // 明说，别让用户以为上下文也清了。
    show($('out'), '已从页面移除；但这一条不在历史里（本轮尚未定型），' +
      '模型可能仍记得它的内容。要彻底清掉请用「新对话」。', 'err');
  }
}

/* 鉴权头。判据：
   · 留空 => **不带** Authorization 头（服务端未开鉴权时这就是正确用法，
     而"带一个空 Bearer"会被服务端当成"凭据不匹配"而 401 —— 所以不能不加判断地拼）；
   · 非空 => `Bearer <token>`，与服务端 [ApiAuth.bearer] 认的形状逐字一致；
   · token 输入框里**只**放 token 本身，不要求用户自己写 "Bearer " 前缀
     （让用户拼协议前缀是必然抄错的一步）。 */
function authHeaders(extra) {
  const h = Object.assign({}, extra || {});
  const t = $('token').value.trim();
  if (t !== '') h['Authorization'] = 'Bearer ' + t;
  return h;
}

/* 401 的错误正文是服务端给的**人话**（"缺凭据"还是"带错了"都写在里面），
   直接展示即可 —— 不要在这里另编一套提示，两处措辞必然漂移。 */
async function getJson(path) {
  const t0 = performance.now();
  const r = await fetch(path, { headers: authHeaders({ Accept: 'application/json' }) });
  const text = await r.text();
  return { code: r.status, ms: Math.round(performance.now() - t0), text };
}

$('btnHealth').onclick = async () => {
  try {
    const r = await getJson('/health');
    show($('health'), 'HTTP ' + r.code + ' · ' + r.ms + ' ms\n' + r.text, r.code === 200 ? 'ok' : 'err');
  } catch (e) { show($('health'), '失败：' + e.message, 'err'); }
};

$('btnModels').onclick = async () => {
  try {
    const r = await getJson('/v1/models');
    let pretty = r.text;
    try { pretty = JSON.stringify(JSON.parse(r.text), null, 2); } catch (_) {}
    show($('health'), 'HTTP ' + r.code + ' · ' + r.ms + ' ms\n' + pretty, r.code === 200 ? 'ok' : 'err');
  } catch (e) { show($('health'), '失败：' + e.message, 'err'); }
};

$('btnReset').onclick = () => { resetFields(); show($('out'), '已恢复默认。', ''); };

/* 显示/隐藏 token：手机上边核对边粘贴时，密码框看不见字符很难确认抄对了。
   只切 input 的 type，不动 value（不做任何"改写内容"的动作）。 */
$('btnTokenShow').onclick = () => {
  const el = $('token');
  el.type = el.type === 'password' ? 'text' : 'password';
};

/* ══════════════════════════════════════════════════════════════════
   三页横向切换（需求4）
   ══════════════════════════════════════════════════════════════════
   两个入口都要：顶部页签（看得见、点得到）与左右滑动（拇指够得到）。
   判据与 App 内那处**同一套**（|dx| > 80px 且 > |dy| * 1.5），两处各写一套必然漂移 ——
   漂移的表现是"手机上能滑、电脑上滑不动"（或反过来），而没人会想到是判据不一致。

   ⚠ 落点是 `#pager`（内容节点），**不是 document**：页面里有两个 textarea
   （schema 与对话输入），挂在 document 上会把它们内部的光标左右移动一起抢走。 */
function showPage(i) {
  for (const p of document.querySelectorAll('.page')) {
    p.classList.toggle('on', p.dataset.page === String(i));
  }
  for (const t of document.querySelectorAll('.tab')) {
    t.classList.toggle('on', t.dataset.page === String(i));
  }
}
for (const t of document.querySelectorAll('.tab')) {
  t.onclick = () => showPage(t.dataset.page);
}
(function () {
  const pg = $('pager');
  if (!pg) return;
  let dx = 0, dy = 0, sx = 0, sy = 0;
  pg.addEventListener('touchstart', (e) => {
    const t0 = e.touches[0]; sx = t0.clientX; sy = t0.clientY; dx = 0; dy = 0;
  }, { passive: true });
  pg.addEventListener('touchmove', (e) => {
    const t0 = e.touches[0]; dx = t0.clientX - sx; dy = t0.clientY - sy;
  }, { passive: true });
  pg.addEventListener('touchend', () => {
    if (Math.abs(dx) < 80 || Math.abs(dx) < Math.abs(dy) * 1.5) return;   // 手抖 / 竖向滚动
    const cur = Number(document.querySelector('.page.on').dataset.page);
    const next = cur + (dx < 0 ? 1 : -1);
    if (next >= 0 && next <= 2) showPage(next);
  }, { passive: true });
})();

$('btnNew').onclick = () => {
  // 新对话必须同时清掉**已渲染的气泡**：只清 history 会留下视觉上的旧会话，
  // 用户以为还在同一个上下文里，而请求其实已经空了。
  history = [];
  bubbles = [];
  $('thread').innerHTML = '';
  show($('out'), '已开新对话。', '');
};

$('btnStop').onclick = async () => {
  // ══════════════════════════════════════════════════════════════════
  // 停止必须**同步**清掉在飞的句柄（Issue #154）
  // ══════════════════════════════════════════════════════════════════
  // `abort` 有两个含义，此前被混成了一件事：
  //   · `!== null` 表示"**页面认为**有一轮在飞"—— 它是 btnSend 的互斥判据；
  //   · `.abort()` 只是"请那一路请求停"，而**句柄的清理发生在异步的
  //     `finally { abort = null }` 里**，那要等 `reader.read()` 真的返回。
  //
  // 于是存在一段可观测的窗口：用户点了停止 → 这一轮确实停了（服务端收到
  // /v1/abort，客户端也接到 AbortError），但 `abort` 还没被清 —— 此期间再点
  // 「发送」，`if (abort)` 判真，页面回一句"正在生成中，先停止或等它结束"，
  // 而"停止"已经按过了。用户读到的就是**"停止之后再也没法开始新对话"**，
  // 与 App 内那条（同一个 Issue）是同一个毛病的两种外表。
  //
  // 修法只改一件事：**先取走句柄再 abort**。取走是同步的（`const ctl = abort;
  // abort = null;` 之间没有 await），所以点完停止的**同一帧**里，btnSend 的
  // 互斥就已经解开；而 `ctl.abort()` 照旧让读取循环立刻抛 AbortError 进入
  // catch/finally 收尾，气泡上仍会写"（已停止，本轮不进历史）"。
  // 收尾那一支（`finally`）仍在必要时清句柄，但它带了**身份判据**
  // （见 btnSend 里 `if (abort === myCtl)`）：只有"自己还是当前在飞的那一轮"
  // 才允许清 —— 否则旧请求的 finally 迟到一步，就会把新一轮的句柄清成 null，
  // 「停止」对新一轮从此失效（它按的是 null）。
  const ctl = abort;
  abort = null;
  if (ctl) ctl.abort();
  // 服务端一轮要跑满 max_tokens 才会自己发现断连，所以显式叫停。
  //
  // 带上**自己那一格**（ISSUE #403）：服务端在生成响应的第一帧用 `X-Slot-Id`
  // 告诉过本页"这一轮在哪一格"，这里原样送回去 —— 与同时有几路无关，
  // 也就不会"猜一格、停到别人那一轮"。
  // 拿不到头（老服务端 / 跨源没放行 / 头被反向代理吃掉）时退回**不带 slot**：
  // 服务端先按来源找 HTTP 那一路，单 slot 下必中；多 slot 且 HTTP 占多格时
  // 会明确回 `aborted:false`（= 没停到，不是"停了"），比静默伪装好。
  //
  // /v1/abort 是**要鉴权**的生成端点：不带凭据会 401，而"停止"看起来像没生效。
  // 所以停止失败**不再静默吞**（以前那个 `catch (_) {}` 让 401 / 网络失败
  // 与服务端取消没用完全同形，这族问题因此反复被误归因）。
  const slot = abortSlot;
  try {
    const r = await fetch('/v1/abort', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: slot === null ? undefined : JSON.stringify({ slot: slot }),
    });
    if (!r.ok) show($('out'), '停止没送到服务端：HTTP ' + r.status + ' ' + await r.text(), 'err');
    else {
      // 服务端**明确说没停到**时也要讲出来：`aborted:false` 是合法结果（那一轮可能已收尾），
      // 但它与"停到了"在用户眼里是两件事 —— 前者的表现会是"按了停止、回答还在长"。
      let j = null;
      try { j = JSON.parse(await r.text()); } catch (_) {}
      if (j && j.aborted === false) {
        show($('out'), '服务端说这一轮已经不在跑了（aborted:false）' +
          (slot === null ? '；本页没拿到 X-Slot-Id，停止是按来源找的' : '；格子 ' + slot) +
          '。若回答仍在继续，请把日志里 abort: 那一行发我。', 'err');
      }
    }
  } catch (e) {
    show($('out'), '停止没送到服务端：' + (e && e.message ? e.message : e), 'err');
  }
};

$('btnSend').onclick = async () => {
  const q = $('q').value.trim();
  if (!q) return;
  if (abort) { show($('out'), '正在生成中，先停止或等它结束。', 'err'); return; }
  const model = $('model').value.trim() || 'local';
  const sys = $('sys').value.trim();

  const settings = readSettings();
  if (settings.bad.length) {
    // **本轮整轮不发**（这里是 `return`，不是"提示完继续往下走"）。
    // 这条以前只有注释说"不发送"，代码里没有 return —— 分支走完照样 fetch。
    // 症状是最难查的一类：用户明确选了 json_schema，拿到的是**无约束**生成，
    // 提示语"本轮不下发"读起来像"这一轮不发"，实际是"发了但没约束"，
    // 而模型照常给出看起来正常的回答。数字项同理：越界值被 400 拦下之前，
    // 用户已经在页面上"看到"自己填的数生效了。
    // 所以：标红的项**一个都不放行**，气泡也不建 —— 不留"这一轮好像也跑了"的痕迹。
    show($('out'), '这些项没填对，本轮**没有发送**：' + settings.bad.join('、') +
      '（请改成合法取值后重试；schema 见下方提示）', 'err');
    return;
  }
  show($('out'), '');

  // ══════════════════════════════════════════════════════════════════
  // 气泡**先注册再填内容**，且注册顺序必须与 history 的顺序严格一致
  // ══════════════════════════════════════════════════════════════════
  // `bubbles` 与 `history`（去掉 system 那几条）是同一份顺序的两个视图 ——
  // 删除要把气泡下标换算回 history 下标，靠的就是这个一一对应。
  // 所以这两句的**相对顺序**不能反：反了就是"用户这一句"与"模型这一句"
  // 在 bubbles 里换位，删第 N 条会删到旁一条，且没有任何异常。
  const meBub = bubble('我', 'me');
  meBub.body.textContent = q;
  bubbles.push(meBub.el);
  // 正在生成的那条也在列表里：用户要能中途删掉它（比如已经开始胡说）。
  // 收尾时它已定型（正文就是最终答复），删它就是删那一轮的回答 —— 语义正确。
  const aiBub = bubble('模型', 'ai');
  bubbles.push(aiBub.el);
  const aiBody = aiBub.body;
  aiBody.textContent = '…';

  // 本轮要发的消息：system（有的话）只放一次 + 历史 + 这一句。
  const messages = [];
  if (sys) messages.push({ role: 'system', content: sys });
  for (const m of history) messages.push(m);
  const userMsg = { role: 'user', content: q };
  messages.push(userMsg);

  const body = Object.assign({ model: model, stream: true, messages: messages }, settings.body);
  pending = { text: '', reasoning: '' };
  // 本轮的句柄**必须自己留着**（`myCtl`），不能只写进全局 `abort`：
  // 收尾时那句 `abort = null` 若不加判据，就会踩掉**后来那一轮**的句柄 ——
  // 用户点了停止、又立刻发新一轮，旧请求的 finally 迟到一步把新一轮的
  // controller 清成 null，此后"停止"对新一轮完全失效（它按的是 null）。
  // 判据是**身份**（`abort === myCtl`）而不是"abort 是不是 null"：
  // 后者在"新一轮还没赋值"的那一瞬同样成立，等于没判。
  const myCtl = new AbortController();
  abort = myCtl;
  // 上一轮那一格作废：**必须在发请求之前清**，否则请求失败（还没拿到新的
  // X-Slot-Id）时，停止会带着上一轮的格子去打 —— 打空不报错，正好是 ISSUE #403。
  abortSlot = null;
  let thinkEl = null;
  try {
    const r = await fetch('/v1/chat/completions', {
      method: 'POST',
      // 生成端点要带凭据（服务端开了鉴权时）—— 与 getJson 共用同一个 authHeaders。
      // X-Log-Tag: 1 —— 本页的日志也带上 `[sN]`（服务端默认只在多格时才打，
      // 单格独占与改动前逐字相同）。这是在**服务端开口之外**的另一半：
      // 用户拿两路日志比对时，"哪一行是这一路"必须先有答案。
      headers: authHeaders({ 'Content-Type': 'application/json', 'X-Log-Tag': '1' }),
      signal: myCtl.signal,
      body: JSON.stringify(body),
    });
    if (!r.ok) {
      // 出错：这一轮不入历史（否则半截上下文会污染后面每一轮）。
      pending = null;
      aiBody.textContent = 'HTTP ' + r.status + '\n' + await r.text();
      aiBody.classList.add('err');
      return;
    }
    // 响应头里那一格：拿不到（跨源未放行 / 老服务端）就保持 null，停止退回旧语义。
    // 只在**这一轮**里写：新一轮开始时上面已把它清空（见 btnSend 开头）。
    const sid = r.headers.get('X-Slot-Id');
    if (sid !== null && /^\d+$/.test(sid.trim())) abortSlot = Number(sid.trim());
    const reader = r.body.getReader();
    const dec = new TextDecoder();
    let buf = '', text = '';
    // 一行 SSE 的消费逻辑。**抽成函数是因为它必须被调用两处**：流中的完整行，
    // 以及流结束（done）时残留在 buf 里的最后一段。以前只有前者 —— 收尾时
    // 留在 buf 里的帧被静默丢弃，"流末帧无尾随换行"会把答案的最后一段吃掉。
    // 失效形态很坏：用户只看到"没有收到正文"，与"模型没加载"完全同形。
    const consume = (line) => {
      if (!line.startsWith('data:')) return;
      const payload = line.slice(5).trim();
      if (payload === '' || payload === '[DONE]') return;
      try {
        const j = JSON.parse(payload);
        const ch = j.choices && j.choices[0];
        const d = ch && ch.delta;
        if (!d) return;
        // 思考段（reasoning_content）单独渲染成折叠之前的一段灰字，正文照旧。
        if (d.reasoning_content) {
          if (!thinkEl) { thinkEl = document.createElement('div'); thinkEl.className = 'think'; aiBody.parentNode.insertBefore(thinkEl, aiBody); }
          pending.reasoning += d.reasoning_content;
          thinkEl.textContent = pending.reasoning;
        }
        if (d.content) { text += d.content; pending.text = text; aiBody.textContent = text; }
      } catch (_) { /* 半截帧，等下一个 chunk */ }
    };
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop();
      for (const line of lines) consume(line);
    }
    // 流结束：清掉解码器尾字节，再消费残留的 buf。
    // 本服务端的 sseEvent / sseComment 恒定以 `\n\n` 收尾，所以对自家服务端打
    // 不出"末帧无换行"的场景；但任何一次网络层把末帧与 EOF 合并、或将来加一种
    // 不以 `\n` 收尾的帧，这里就会静默吃掉最后一段正文。
    buf += dec.decode();
    for (const line of buf.split('\n')) consume(line);
    if (!text) {
      // 一行都没有：多半是 prefill 还没出第一个 token 就已经结束（模型未加载 / 输出为空）
      aiBody.textContent = '（没有收到正文，可能模型未加载或输出为空）';
      aiBody.classList.add('err');
      pending = null;   // 空回复也不入历史
      return;
    }
    // 成功：这一轮进历史，下一轮带上 —— 这就是"连贯聊天"的全部机制。
    history.push(userMsg);
    history.push({ role: 'assistant', content: text });
    pending = null;
    $('q').value = '';
  } catch (e) {
    pending = null;
    if (e.name === 'AbortError') { aiBody.textContent += '\n（已停止，本轮不进历史）'; }
    else { aiBody.textContent = '失败：' + e.message; aiBody.classList.add('err'); }
  } finally { if (abort === myCtl) abort = null; }
};

renderFields('gridSampling', SAMPLING_FIELDS);
renderFields('gridPenalty', PENALTY_FIELDS);
</script>
</body>
</html>
""").trim()
    }

    /**
     * 把字段表序列化成一段 JS 数组字面量（仅含数字/字符串，无需 HTML 转义）。
     *
     * 用 `org.json` 而不是手拼：值里有用户可见的中文标签与 `[`/`]`/`,` 这类
     * 在 JS 里有含义的字符，手拼迟早漏转义。JSON 是 JS 字面量的子集，直接可执行。
     */
    fun fieldsJson(fields: List<NumberField>): String {
        val arr = org.json.JSONArray()
        for (f in fields) {
            arr.put(org.json.JSONObject().apply {
                put("key", f.key)
                put("label", f.label)
                put("def", f.def)
                put("min", f.min)
                put("max", f.max)
                put("step", f.step)
                // 排他端与整数性必须一起下发：只给 min/max 的话，`top_p` 的
                // "下界 0 不可取"和 `max_tokens` 的"必须是整数"在页面上表达不出来，
                // 于是页面只能放一个更宽松的界 —— 用户填到端点值照样 400。
                put("minExclusive", f.minExclusive)
                put("maxExclusive", f.maxExclusive)
                put("integral", f.integral)
                put("hint", f.hint)
            })
        }
        // 防止 `</script>` 出现在标签文字里把脚本块提前闭合（本函数产出的内容会落进 <script>）。
        return arr.toString().replace("<", "\\u003c")
    }

    /**
     * 把外部字符串转成能安全落进 `<script>` 里双引号字面量的形式。
     *
     * 反向判据（`WebChatPageTest`）：`</script>` 与反斜杠都必须被消掉 ——
     * 前者能直接闭合脚本块、后者会把后一个字符一起吃掉。
     *
     * **不要拿它去拼 HTML 属性或文本** —— 那是 [escapeForHtml] 的位置。
     * 它做的是 **JS 字符串字面量**转义（`"` → `\"`），而 HTML 里没有反斜杠转义，
     * 同一个 `\"` 在属性里毫无保护作用（`"` 照样闭合属性）。
     * 反过来 [escapeForHtml] 的实体（`&quot;`）落进 JS 字面量也不安全
     * （会变成六个字符）。两者**按输出位置分工**，不能互相替代。
     */
    fun escapeForScript(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("\"", "\\\"")

    /**
     * 把外部字符串转成能安全落进 HTML **属性值**或**文本节点**的形式。
     *
     * 与 [escapeForScript] 的分工是「按输出位置」定的：这里的产物只会被
     * HTML 解析器读，绝不能被放进 `<script>` 里的 JS 字面量。
     *
     * `&` 必须**第一个**替换，否则会把后面刚生成的实体里的 `&` 再转一次
     * （`&` → `&amp;`，再被 `&amp;` 的规则命中 → `&amp;amp;`）。
     * 四个字符缺一不可：`"`/`'` 界定属性值，`<`/`>` 能提前结束标签或开新标签。
     *
     * 判据是**无损**：实体还原回来必须逐字符等于输入 —— 转义不等于改动数据，
     * 模型名要原样显示在输入框里。
     */
    fun escapeForHtml(raw: String): String =
        raw.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
