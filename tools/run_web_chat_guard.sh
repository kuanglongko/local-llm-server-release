#!/bin/sh
# 「自带测试页（折叠设置区 + 多轮会话）」的**接线**守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠 run_web_chat_tests.sh 不够
# ═══════════════════════════════════════════════════════════════════════════
# 单测钉住了 WebChatPage 这个纯函数产物**长什么样**，但钉不住**接线**：
#
#   ① `GET /` 必须仍然把页面发出去，且走 `CorsPolicy.pageHtml(`（两张写测试的网
#      都拿这个调用形式当锚点）。把实现搬到新文件后，最容易发生的回归就是
#      "页面搬到 WebChatPage 了，但 HttpApi 还按老样子调" —— 编译能过（转发还在），
#      但如果谁顺手把转发删掉、或把路由删掉，症状是"页面打不开"，
#      与"服务没起来"完全同形。
#   ② 页面字段名必须与服务端请求侧**逐字一致**。页面是这些参数的又一个客户端，
#      名字对不上 = 填了没生效，而它不报错、不异常，用户只会说"参数不生效"。
#      这条判据查的是**两个文件里的同一个字面量**，只有静态断言能钉。
#   ③ 多轮会话的三条硬判据：历史要带回、失败/空回复不入历史、新对话要清空。
#      写错任何一条都是静默的（上下文错乱 / 每轮喂坏上下文），
#      单测能钉住其中一部分，接线侧还要确认它们**真的在发出去的那个页面里**。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_web_chat_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
HTTP=$SRC/HttpApi.kt
CORS=$SRC/CorsPolicy.kt
WEB=$SRC/WebChatPage.kt
SAMP=$SRC/SamplingParams.kt
README=README.md
STATUS=HTP-STATUS.md
CHANGELOG=CHANGELOG.md

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 1) 页面本体独立成文件，入口保留 ────────────────────────────────────────
c "WebChatPage.kt 存在（页面本体独立判据文件）" "[ -f \$WEB ]"
c "页面本体是个 object（纯函数产物，可宿主单测）" "grep -q 'object WebChatPage' \$WEB"
c "CorsPolicy.pageHtml 转发给页面本体（入口不搬，两张网都还在）" \
  "grep -q 'WebChatPage.html(' \$CORS"
c "HttpApi 仍走 CorsPolicy.pageHtml（GET / 的接线没断）" \
  "grep -q 'CorsPolicy.pageHtml(' \$HTTP"
c "GET / 路由仍在" "grep -q 'req.path == \"/\"' \$HTTP"

# ── 2) 折叠设置区：每组都要在（漏一组 = 那组在页面上"没有名字"）──────────────
c "设置区用原生 <details>（可折叠，JS 没跑也读得到）" "grep -q '<details id=\\\"detailsSettings\\\">' \$WEB"
c "思考链有三态（default/true/false 三个 option 都在，不是两态复选框）" \
  "grep -q 'id=\\\"think\\\"' \$WEB && grep -q 'value=\\\"default\\\"' \$WEB && grep -q 'value=\\\"true\\\"' \$WEB && grep -q 'value=\\\"false\\\"' \$WEB"
c "有生成与采样组容器" "grep -q 'id=\\\"gridSampling\\\"' \$WEB"
c "有重复与惩罚组容器" "grep -q 'id=\\\"gridPenalty\\\"' \$WEB"
# 结构化输出一栏（本轮新增）：三档取值都要在，且分支必须真的在 ——
# "不要求" 是**不下发** response_format 的那一档，页面默认行为因此等于服务端旧版行为。
# 只 grep 选项文本是不够的：把 JS 里的条件分支删掉，选项还在、断言照样绿。
c "有结构化输出选择框" "grep -q 'id=\"respFmt\"' $WEB"
c "结构化输出三档齐全（none / json_object / json_schema）" \
  "grep -q 'value=\"none\"' $WEB && grep -q 'value=\"json_object\"' $WEB && grep -q 'value=\"json_schema\"' $WEB"
c "有 schema 输入框" "grep -q 'id=\"schemaJson\"' $WEB"
c "仅非 none 时下发 response_format（条件分支真的在）" \
  "grep -q \"rf === 'json_object'\" $WEB && grep -q \"=== 'json_schema'\" $WEB && grep -q 'out.response_format = { type:' $WEB"
c "schema 在客户端先 JSON.parse（本地就能指出语法错）" "grep -q 'JSON.parse(raw)' $WEB"

# ── 3) 字段名与服务端请求侧逐字一致（漂移 = 静默不生效）────────────────────
# 判据是"页面字段表里出现了这个 key"，而 key 由 Kotlin 的 SAMPLING_FIELDS /
# PENALTY_FIELDS 定义 —— 与服务端 SamplingParams 的 `readFloat(j, \\"temperature\\"` 同源。
c "temperature 在页面字段表" "grep -q 'field(\\\"temperature\\\"' \$WEB"
c "top_p 在页面字段表"        "grep -q 'field(\\\"top_p\\\"' \$WEB"
c "top_k 在页面字段表"        "grep -q 'field(\\\"top_k\\\"' \$WEB"
c "min_p 在页面字段表"        "grep -q 'field(\\\"min_p\\\"' \$WEB"
c "max_tokens 在页面字段表"   "grep -q 'field(\\\"max_tokens\\\"' \$WEB"
c "seed 在页面字段表"         "grep -q 'field(\\\"seed\\\"' \$WEB"
c "repeat_penalty 在页面字段表" "grep -q 'field(\\\"repeat_penalty\\\"' \$WEB"
c "repeat_last_n 在页面字段表"  "grep -q 'field(\\\"repeat_last_n\\\"' \$WEB"
c "frequency_penalty 在页面字段表" "grep -q 'field(\\\"frequency_penalty\\\"' \$WEB"
c "presence_penalty 在页面字段表"  "grep -q 'field(\\\"presence_penalty\\\"' \$WEB"
# 服务端确实认这些名字（两处字面量必须一致，不是"页面自己写了一份")。
c "服务端请求侧认 temperature" "grep -q 'readFloat(j, \\\"temperature\\\"' \$SAMP"
c "服务端请求侧认 repeat_penalty" "grep -q 'readFloat(j, \\\"repeat_penalty\\\"' \$SAMP"
c "服务端请求侧认 frequency_penalty" "grep -q 'readFloat(j, \\\"frequency_penalty\\\"' \$SAMP"
# 默认值只有一处来源：页面字段表引 SamplingParams.DEF_*，不各写一份字面量。
c "页面默认值引 SamplingParams.DEF_TEMP" "grep -q 'fmt(SamplingParams.DEF_TEMP)' \$WEB"
c "页面默认值引 SamplingParams.DEF_MAX_TOKENS" "grep -q 'fmt(SamplingParams.DEF_MAX_TOKENS)' \$WEB"
c "页面默认值引 SamplingParams.DEF_REPEAT_PENALTY" "grep -q 'fmt(SamplingParams.DEF_REPEAT_PENALTY)' \$WEB"
c "思考链走请求侧认的 enable_thinking 字段" "grep -q 'enable_thinking' \$WEB"

# ── 4) 多轮会话：三条硬判据 ────────────────────────────────────────────────
c "页面维护历史数组" "grep -q 'let history = \\[\\]' \$WEB"
c "每轮把历史拼进 messages（不只发最后一句）" "grep -q 'for (const m of history) messages.push(m)' \$WEB"
c "成功回复追加进历史" "grep -q 'role: .assistant., content: text' \$WEB"
c "新对话同时清历史与气泡" "grep -q \"\\\$('thread').innerHTML = ''\" \$WEB && grep -q 'history = \\[\\]' \$WEB"
c "失败/空回复不入历史（pending = null 的两处出口）" \
  "[ \"\$(grep -c 'pending = null' \$WEB)\" -ge 3 ]"
c "system 提示可填且只放一次" "grep -q 'role: .system.' \$WEB"

# ── 4b) 停止 = 一轮的结束：句柄必须**同步**清掉（Issue #154）──────────────
# 判据不是"按钮里有 abort()"（那是旧写法，也有），而是**取走与置空之间没有 await**：
#   · 旧写法 `if (abort) { abort.abort(); abort = null; }` —— 置空发生在**异步的
#     finally** 里，于是"已停止但 abort 还不为 null"这段窗口内，btnSend 的
#     `if (abort)` 判真，用户读到"正在生成中，先停止" —— 而停止已经按过了。
#   · 判据钉的是那个**顺序**：先 `const ctl = abort;`、紧接着 `abort = null;`。
#     只钉"存在 abort = null"的话，旧写法（finally 里那句）照样能过。
c "停止按钮先取走在飞句柄（const ctl = abort）" \
  "grep -q 'const ctl = abort;' \$WEB"
# 锚点必须用 `$(\'btnStop\').onclick` 而**不是** `btnStop`：后者在 HTML 里也出现
# （`<button id="btnStop">`），awk 的范围会从那一行就开始、一路扫到文件尾 ——
# 于是"同步置空整段删掉"照样能命中（尾段 finally 里那句 `abort = null;` 被当成了它，
# 自测桩⑱/⑲ 抓出来的）。**范围锚点本身也是一个判据**，钉不住它就等于没有范围。
#
# 为什么把这段源码抽进变量再 grep：在 `eval` 的字符串里写一层层转义的引号，
# 读的人与被读的 shell 都很难对上账（初版就是这么写的，改了三次才对）。
# 抽成变量之后，下面两条断言都直接引用它，锚点只有一处。
STOP_HANDLER=$(awk "/^(\\\$\('btnStop'\))/,/^\};/" "$WEB" 2>/dev/null || true)
c "取走后**同步**置空（btnSend 的互斥立刻解开，不等异步 finally）" \
  "printf '%s' \"\$STOP_HANDLER\" | grep -q 'abort = null;'"
c "判定范围真的圈到了停止回调（不是空集合 → 上面那条不至于恒假）" \
  "[ \"\$(printf '%s' \"\$STOP_HANDLER\" | wc -l)\" -ge 3 ]"
# 注释里也会出现 `abort = null;` 与 `ctl.abort()` 的字样（上面那几行就在解释它们），
# 不排掉的话这里比的是**注释的顺序** —— 把代码顺序颠倒照样能过（自测桩⑳ 抓出来的）。
# 只留代码行，判据才落在真正被执行的那两行上。
STOP_CODE=$(printf '%s' "$STOP_HANDLER" | grep -v '^[ \t]*//')
c "置空在 abort() 之前（反过来那段窗口正是「已停止却仍被判在生成」）" \
  "printf '%s' \"\$STOP_CODE\" | grep -n 'abort = null;\\|ctl.abort()' | head -1 | grep -q 'abort = null;'"
c "仍真的调 ctl.abort()（清句柄不等于取消）" \
  "grep -q 'if (ctl) ctl.abort();' \$WEB"
c "btnSend 的在飞互斥判据仍是 abort 本身（没被换成别的近似）" \
  "grep -q \"if (abort) { show(\\\$('out'), '正在生成中，先停止或等它结束。', 'err'); return; }\" \$WEB"
# 收尾那一支仍要清句柄，但**必须带身份判据**：旧请求的 finally 若无条件
# 清 `abort`，就会踩掉"用户点了停止、又立刻发新一轮"时那一轮的句柄 ——
# 此后「停止」对新一轮完全失效（它按的是 null），而这一支不报错、不打日志。
c "finally 里仍清 abort（正常跑完那一支要靠它）" \
  "grep -q '} finally { if (abort === myCtl) abort = null; }' \$WEB"
c "收尾清句柄带**身份**判据（不是无条件清，也不是只判 null）" \
  "grep -q 'abort === myCtl' \$WEB"
c "本轮句柄自己留一份 myCtl（不是只写全局 abort）" \
  "grep -q 'const myCtl = new AbortController();' \$WEB && grep -q 'abort = myCtl;' \$WEB"
c "请求用的 signal 来自本轮自己的句柄（不是全局 abort）" \
  "grep -q 'signal: myCtl.signal,' \$WEB"

# ── 5) 注入安全与离线自足（别在重写时丢掉）────────────────────────────────
# 这一节上一轮只有一条"存在性"断言：`grep -q 'fun escapeForScript'`。
# 它**恒真于失效状态** —— 函数在，但用错了位置（把 JS 转义器用在 HTML 属性上），
# 于是真的存在一条可执行的属性越界 XSS，而守卫全绿。这正是 D 轮总结过的
# "存在性断言覆盖不住存在但无效"。本轮改成**按输出位置**钉两个转义器：
#   · 属性位置必须走实体转义（`&quot;`），不得是反斜杠转义（`\"`）；
#   · JS 字面量位置仍走反斜杠转义，两个转义器不许互相替代。
c "有 HTML 实体转义器（属性/文本位置专用）" \
  "grep -q 'fun escapeForHtml' \$WEB"
c "escapeForHtml 转五个字符（& 必须第一个，否则双转）" \
  "grep -q 'raw.replace(\"&\", \"&amp;\")' \$WEB && grep -q '\"&quot;\"' \$WEB && grep -q '\"&#39;\"' \$WEB && grep -q '\"&lt;\"' \$WEB && grep -q '\"&gt;\"' \$WEB"
# 判据：取 escapeForHtml 的函数体，**第一个** replace 必须是 `&`。
# 顺序反了就双转（`&lt;` 会被 `&` 的规则再吃一遍 → `&amp;lt;`），
# 而症状是"模型名显示成 `&amp;lt;`"，看起来像服务端数据坏了。
# 这里用 `grep -F`（定长串）而不是基本正则：`&` 在 BRE 里是**替换语义**，
# 写成 `grep -q '&amp;'` 会被解读成"匹配空串并替换"，于是恒真/恒假 ——
# 初版正是这么写的，实测恒假。判据自己要先用"已知该红的桩"验过。
# 判据必须是**顺序**，不是存在性：`grep -qF 'replace("&", "&amp;")'` 恒真于
# "把 & 那行挪到末尾"的状态（自测桩⑫ 抓出来的）。所以取函数体里**第一个**
# `replace(...)` 的实参，判它是不是 `&` —— 顺序反了第一个就是别的字符。
c "escapeForHtml 里 & 的替换在 < 之前（顺序不能反）" \
  "awk '/fun escapeForHtml/,/^\}/' \$WEB | tr -d '\n' | grep -oF 'replace(\"' | head -1 >/dev/null && [ \"\$(awk '/fun escapeForHtml/,/^\}/' \$WEB | tr -d '\n' | sed 's/.*escapeForHtml(raw: String): String =//' | grep -oE 'replace\(\"[^\"]*\"' | head -1)\" = 'replace(\"&\"' ]"
# 关键判据：模型名**不许**再走 JS 转义器。旧写法就在这里，且它不报错。
c "模型名走实体转义（不是 JS 转义）" \
  "grep -q 'val model = escapeForHtml(modelDesc ?: \"\")' \$WEB"
c "模型名不再被喂给 escapeForScript（旧写法必须绝迹）" \
  "! grep -q 'escapeForScript(modelDesc' \$WEB"
c "落进 value=\"…\" 的模型名用 escapeForHtml（属性位置判据）" \
  "grep -q 'value=\"\$model\"' \$WEB && grep -q 'escapeForHtml' \$WEB"
c "两个转义器都有文档说明「不可互相替代」" \
  "grep -q '不要拿它去拼 HTML 属性或文本' \$WEB && grep -q 'HTML 里没有反斜杠转义' \$WEB"
c "字段 JSON 也转义 <（防提前闭合脚本块）" "grep -q 'replace(\"<\", \"\\\\\\\\u003c\")' \$WEB"
c "页面不引任何 CDN（离线场景引了就打不开）" \
  "! grep -qE 'https?://(cdn|unpkg|jsdelivr)' \$WEB"
c "页面不引 Access-Control（同源，两件事独立）" "! grep -q 'Access-Control-Allow-Origin' \$WEB"

# ── 5b) 取值界：唯一来源 + 真的生效（H-2）──────────────────────────────────
# 这一节防两件事，都是"存在但无效"：
#   ① 界是**手抄字面量** —— TEMP_CAP/TOP_K_CAP/PENALTY_ABS_CAP 此前是 private，
#      页面拿不到只能抄，于是已经漂了两处（top_p 下界 0、min_p 上界 1）；
#   ② 界**从没设到控件上** —— 声明了、序列化了，renderFields 一个都没设，
#      readSettings 也不校验，纯死数据。
# 判据锚**结构关系**：界必须来自 boundsOf，且必须被真正读到。
c "SamplingParams 暴露界上限常量（页面才能引同一份，不再手抄）"   "grep -q 'const val TEMP_CAP' \$SAMP && grep -q 'const val TOP_K_CAP' \$SAMP && grep -q 'const val PENALTY_ABS_CAP' \$SAMP"
c "有排他端常量（top_p 下界 / min_p 上界不是闭端点）"   "grep -q 'TOP_P_MIN_EXCLUSIVE' \$SAMP && grep -q 'MIN_P_MAX_EXCLUSIVE' \$SAMP"
c "有 Bounds 类型（界 + 排他标志 + 整数性）"   "grep -q 'data class Bounds' \$SAMP && grep -q 'val minExclusive' \$SAMP && grep -q 'val integral' \$SAMP"
c "有 boundsOf 单一来源" "grep -q 'fun boundsOf(key: String)' \$SAMP"
# 已漂的两处必须修正（点名钉住，防回退）：top_p 下界排他、min_p 上界排他。
c "top_p 的界是 (0,1]（下界排他，不是 0.0）"   "grep -q 'TOP_P_MIN_EXCLUSIVE.toDouble(), 1.0, 0.01, minExclusive = true' \$SAMP"
c "min_p 的界是 [0,1)（上界排他，不是 1.0）"   "grep -q 'MIN_P_MAX_EXCLUSIVE.toDouble(), 0.01, maxExclusive = true' \$SAMP"
# 整数字段必须标 integral（服务端走 readInt）。
c "整数字段标了 integral（top_k/max_tokens/repeat_last_n/seed）" \
  "[ \"\$(grep -c 'integral = true' \$SAMP)\" -ge 4 ]"
# 页面侧：字段只能经 `field(...)` 造（界从 boundsOf 取），不许手填 min/max。
c "页面字段经 field() 造（界从 boundsOf 取，不手填）"   "grep -q 'SamplingParams.boundsOf(key)' \$WEB"
c "页面不再手填 min/max 字面量（旧写法必须绝迹）" \
  "! grep -qE 'NumberField\\(\"[a-z_]+\", \"[^\"]*\", [^,]+, [0-9-]' \$WEB"
# 界必须真的设到控件上：这是 H-2 的另一半（以前界是死数据）。
c "渲染时把 min/max/step 设到控件上"   "grep -q 'inp.dataset.min = f.min' \$WEB && grep -q 'inp.dataset.max = f.max' \$WEB && grep -q 'inp.step = f.step' \$WEB"
c "排他端不写进 min/max（浏览器会把它当合法端点放行）"   "grep -q 'if (!f.minExclusive) inp.min = f.min' \$WEB && grep -q 'if (!f.maxExclusive) inp.max = f.max' \$WEB"
c "整数字段用整数输入模式" \
  "grep -q 'inp.inputMode = f.integral ? .numeric. : .decimal.' \$WEB"
c "有本地取值校验函数（越界/排他端/整数性）"   "grep -q 'function valueProblem(' \$WEB && grep -q 'inp.dataset.minExclusive && n <= lo' \$WEB && grep -q 'inp.dataset.maxExclusive && n >= hi' \$WEB && grep -q 'inp.dataset.integral && n !== Math.floor(n)' \$WEB"
c "readSettings 真的调用本地校验（声明了不调 = 死代码）"   "grep -q 'const why = valueProblem(inp, n)' \$WEB"

# ── 5c) 坏项整轮不发（H-1）──────────────────────────────────────────────
# 以前 bad 分支只有**注释**说"不发送"，代码里没有 return —— 分支走完照样 fetch。
# 症状：用户选了 json_schema 却拿到无约束生成，提示写"本轮不下发"、
# 实际是"发了但没约束"。判据锚控制流（return 在 bubble 之前），不锚文案存在性。
c "bad 分支真的 return（不是提示完继续发）"   "grep -q '本轮\*\*没有发送\*\*' \$WEB && grep -q '    return;' \$WEB"
c "bad 提示措辞可区分「拒发」与「忽略」（旧含混文案绝迹）"   "! grep -q '这些项没填对，本轮不下发' \$WEB"

# ── 5d) modelLoaded 不再是死参数（H-3）────────────────────────────────────
# 参数存而不用比没有它更容易误导：下一个改代码的人会以为它已生效。
# 判据：**有无它渲染结果必须不同**，且未加载时有显式提示。
c "modelLoaded 真的影响渲染（不再是死参数）"   "grep -q 'fun modelBanner(modelLoaded: Boolean)' \$WEB && grep -q 'modelBanner(modelLoaded)' \$WEB"
c "未加载模型时页面顶部有显式提示" \
  "grep -qF 'id=\\\"noModel\\\"' \$WEB && grep -q '没有加载模型' \$WEB && grep -q 'fun modelBanner' \$WEB"

# ── 5e) 流收尾消费残留 buf（H-4）──────────────────────────────────────────
# 以前流结束时不处理残留在 buf 的末帧：任何一次"末帧无尾随换行"都会静默吃掉
# 最后一段正文，而用户只看到"没有收到正文"——与"模型没加载"同形。
c "解析逻辑抽成可复用的 consume(line)" "grep -q 'const consume = (line) =>' \$WEB"
# 判据用 `grep -F` 定长串，避开 sh 里 `\\n` / 引号的多重转义（写错过一次，见注释）。
c "流结束后对残留 buf 用**同一个** consume 再跑一次（判据不漂移）" \
  "grep -qF \"for (const line of buf.split('\\\\n')) consume(line)\" \$WEB"
c "收尾前 flush 解码器尾字节" "grep -q 'buf += dec.decode()' \$WEB"

# ── 6) 文档同步 ────────────────────────────────────────────────────────────
# 判据要**具体**到文档里那句独有的话，不能是"出现过『设置区』"这种到处都有的词
# （初版正是这么写的，被桩⑧ 抓出来：它是恒真的，把说明整段删掉照样 PASS）。
c "README 有「自带测试页（GET /）」一节" \
  "grep -q '自带测试页（\`GET /\`）' \$README && grep -q '### 自带测试页' \$README"
c "README 写明页面是纯函数产物、可离线断言" \
  "grep -q 'WebChatPage.kt' \$README && grep -q '纯函数产物' \$README"
# 判据要**具体**到那一节独有的话：`重复与惩罚` 在开头的"能做什么"里也出现过，
# 于是"删掉设置区那一段"照样能过（初版被桩⑧ 抓出来）。改查只在该节出现的字面量。
# 判据必须锚到**这一节独有**的措辞：`repeat_penalty` 之类在别处（采样参数一节）
# 也出现，"逐字查字段名"照样能被"把设置区那段删掉"骗过（桩⑧ 抓出来的）。
# 改成查只在设置区出现的那几句：`**折叠设置区**（原生` 与 `三态 —— **跟随服务端默认`。
c "README 写明折叠设置区（原生 details、默认收起、四组）" \
  "grep -qF '默认收起，四组' \$README && grep -qF '纯函数产物' \$README && grep -qF '结构化输出' \$README"
c "README 写明思考链三态且「跟随」= 不下发字段" \
  "grep -q '三态 —— \*\*跟随服务端默认' \$README && grep -q '不下发该字段' \$README"
c "README 写明多轮三条硬判据（只有成功才入历史 / 新对话清气泡 / 历史真带上）" \
  "grep -q '只有成功的回复才进历史' \$README && grep -q '新对话' \$README"
c "CHANGELOG 记录了本轮（有折叠设置区 + 多轮两会事）" \
  "grep -q '折叠设置区' \$CHANGELOG && grep -q '多轮连贯对话' \$CHANGELOG"
c "HTP-STATUS.md 有第十五节且含本轮要点" \
  "grep -q '十五、内置网页版' \$STATUS && grep -q 'ThinkChoice' \$STATUS"

echo ""
if [ "$bad" = "0" ]; then echo "=== 自带测试页接线守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 自带测试页接线守卫：PASS $ok / FAIL $bad ==="; exit 1
