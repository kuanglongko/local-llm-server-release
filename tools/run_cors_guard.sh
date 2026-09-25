#!/bin/sh
# 「CORS 白名单 + OPTIONS 预检 + 自带测试页」的**接线**守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠 run_cors_tests.sh 不够
# ═══════════════════════════════════════════════════════════════════════════
# 单测钉住了 CorsPolicy 这个纯函数算得对不对，但钉不住**接线**：
#
#   ① 每个响应都要**真的带上** CORS 头。HttpApi 里有 21 处 writeJson，
#      只要有一处绕过统一入口自己拼头，症状就是"预检过了、某一条端点被拦" ——
#      而它在浏览器里看起来与"服务随机抽风"完全一样。
#   ② 预检（OPTIONS）必须在**真实路由之前**被处理，且**不能碰业务校验**。
#      将来加鉴权时这条尤其要命：预检不带 Authorization，拦了等于 CORS 白做。
#   ③ `GET /` 必须排在 404 分支之前 —— 顺序错了它永远返回 not found，
#      而"页面打不开"与"服务没起来"同形。
#   ④ **绝不允许**出现 `Access-Control-Allow-Origin: *` 或 Allow-Credentials。
#      这是本次唯一的硬安全承诺，接口是无鉴权的；它如果被漏进去，
#      攻击面从"局域网内直连"变成"用户在浏览器里打开的任何一个网页"。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_cors_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
HTTP=$SRC/HttpApi.kt
CORS=$SRC/CorsPolicy.kt
# 自带测试页的**页面本体**已搬到独立文件（CORS 判据与数百行 HTML 混在一起，
# 读任一边都要先翻过另一边）。接线断言里"页面结构"那几条改查这个文件 ——
# 判据跟着实现走，而不是把实现钉回旧文件。
WEB=$SRC/WebChatPage.kt
ACT=$SRC/EngineActivity.kt
SVC=$SRC/InferenceService.kt
MS=$SRC/ModelStore.kt
README=README.md
STATUS=HTP-STATUS.md

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "CorsPolicy.kt 是独立判据文件（可被宿主单测）" "[ -f \$CORS ]"

# ── 1) 硬安全承诺：不许出现通配放行 / 不许开 Credentials ────────────────────
# 这一条是本文件存在的**首要**理由。判据必须查"代码里有没有出现那个字面量"，
# 而不是查"有没有人写过 allowOrigin 变量"—— 后者是恒真的（桩会照样全绿）。
c "HttpApi 不出现 Allow-Origin: *"      "! grep -q 'Access-Control-Allow-Origin: \\*' \$HTTP"
# 判据刻意写成「响应里**实际回出的**头值」，而不是"文件里有没有出现过这个字串" ——
# 后者是恒真的自欺：CorsPolicy 的文件头注释本身就要讨论 `Allow-Origin: *` 为什么不做。
# 因此这里只查**拼接进响应头的那几行**（以 `sb.append("Access-Control-...` 开头）。
c "CorsPolicy 拼出的响应头里没有通配 Origin" \
  "! grep -qE 'sb\\.append\(.Access-Control-Allow-Origin: \\*' \$CORS"
c "没有任何地方发 Allow-Credentials（本接口无凭据可带）" \
  "! grep -qE 'sb\\.append\\(.Access-Control-Allow-Credentials|^[^*/]*Access-Control-Allow-Credentials:' \$CORS \$HTTP"
c "预检方法不用通配 *"                   "grep -q 'val methods = \"GET, POST, OPTIONS\"' \$CORS"
c "预检来源是**回显白名单命中的 Origin**，不是常量" \
  "grep -q 'val allow = allow(origin) ?: return null' \$CORS"
c "未被放行的来源返回 null（= 不回头、等于拒绝）" \
  "grep -q 'if (n in allowOrigin.map { normalize(it) }) return raw' \$CORS"

# ── 2) 接线①：CORS 头统一注入，每个响应都带 ────────────────────────────────
c "writeRaw 是唯一的头部拼装点（统一注入 CORS）" \
  "grep -q 'CorsPolicy.responseHeaders(corsOrigin)' \$HTTP"
c "SSE 响应头也带 CORS（流式端点最常被浏览器前端打）" \
  "grep -q 'Transfer-Encoding: chunked' \$HTTP && grep -A6 'Transfer-Encoding: chunked' \$HTTP | grep -q 'CorsPolicy.responseHeaders(corsOrigin)'"
# 不许有一条绕过统一入口：`writeJson(` 的**调用点**全部要带 corsOrigin。
# 判据是"调用行数 == 带 corsOrigin 的调用行数"，而不是 grep 某个变量名（那是恒真的）。
# 两段式：①调用点数 == corsOrigin 出现次数；②**任何** writeJson 调用之后 8 行内
# 必须能看到 corsOrigin（覆盖 `writeJson(out, 200,` 换行、corsOrigin 落在末行的那种写法 ——
# 只数同一行的会把它误报成绕过）。两者缺一都能被骗过，所以都要有。
# 判据要**报出是哪一行**（不只是一句"有绕过"）：只有指出位置，这个守卫才会
# 在被改坏时真的有人去修，而不是当成噪音把 `exit 1` 改成 `|| true`。
# 双段式，缺一都能被骗过：
#   ① 每一条 writeJson 调用的**括号内**必须有 corsOrigin（容忍跨行：看其后 8 行）；
#   ② 断言存在性（恒真的那一半单独列出来，方便对照自测）。
# 这条判据的 shell 引号嵌套太深，抽成独立脚本（就在 tools/ 下），
# 好处是它自己也能被自测脚本单独对着打桩源码跑。
c "所有 writeJson 调用都透传 corsOrigin（不许有一条绕过，失败时点出行号）" \
  "sh tools/cors_writejson_guard.sh \$HTTP"
c "至少存在一条透传（防"判据写死成恒真"的另一半）" \
  "grep -q 'writeJson(out, 200, healthJson(), corsOrigin)' \$HTTP"
c "handleChat / handleCompletion 都接收并透传 corsOrigin" \
  "grep -q 'private fun handleChat(req: Req, out: OutputStream, corsOrigin: String? = null)' \$HTTP && grep -q 'private fun handleCompletion(req: Req, out: OutputStream, corsOrigin: String? = null)' \$HTTP"
c "响应里带 Vary: Origin（否则中间缓存会把放行头回给别的来源）" \
  "grep -q 'Vary: Origin' \$CORS"

# ── 3) 接线②：OPTIONS 预检 ────────────────────────────────────────────────
c "handleConn 里有 OPTIONS 分支"         "grep -q 'if (req.method == \"OPTIONS\")' \$HTTP"
c "预检排在真实路由之前（在 when 之前 return）" \
  "awk '/if \\(req.method == \"OPTIONS\"\\)/{f=1} f&&/^            when \\{/{print \"OK\"; exit}' \$HTTP | grep -q OK"
c "预检回 204（规范允许任意 2xx，204 明确无正文）" \
  "grep -q 'writeRaw(out, 204, \"\", extraHeaders = CorsPolicy.preflight' \$HTTP"
c "204 有对应的 reason 文案（缺了会回 'HTTP/1.1 204 Error'）" \
  "grep -q '204 -> \"No Content\"' \$HTTP"
c "预检走白名单判定，不是无条件放行" \
  "grep -q 'CorsPolicy.preflight(' \$HTTP"
c "来源被拒时留日志（否则无法区分「来源被拦」与「服务坏了」）" \
  "grep -q 'CORS 拒绝来源' \$HTTP"
c "预检**不参与**任何业务校验（不许出现模型未加载/busy 判定）" \
  "! awk '/if \\(req.method == \"OPTIONS\"\\)/{f=1} f&&/when \\{/{exit} f' \$HTTP | grep -qE 'hasModel|busy|compareAndSet'"

# ── 4) 接线③：从请求里**真的读出** Origin（否则白名单退化成"全放/全拦"）────
c "parseReq 读 Origin 头"                "grep -q '\"origin\" -> origin = value' \$HTTP"
c "parseReq 读 Access-Control-Request-Headers" \
  "grep -q 'access-control-request-headers' \$HTTP"
c "Req 携带 origin（不只是拼响应头）"     "grep -q 'val origin: String? = null' \$HTTP"

# ── 5) 接线④：自带测试页路由 ──────────────────────────────────────────────
c "GET / 路由存在"                       "grep -q 'req.path == \"/\"' \$HTTP"
# 范围锚定：从 GET / 那一行起，到它后面的 else ->（404）之前，
# 中间不许先出现 else -> —— 否则页面打不开，且与"服务没起来"同形。
c "GET / 排在 404 分支之前" \
  "awk '/req.path == \"\\/\"/{f=1} f&&/else ->/{print (f==1 ? \"OK\" : \"\"); exit}' \$HTTP | grep -q OK"
c "测试页用 text/html 且显式带 charset"   "grep -q 'text/html; charset=utf-8' \$HTTP"
c "测试页内容来自 CorsPolicy.pageHtml（判据可被宿主单测）" \
  "grep -q 'CorsPolicy.pageHtml(' \$HTTP"
c "测试页同源：不发 CORS 头也照样能用（页面本体不引 Access-Control）" \
  "! grep -q 'Access-Control-Allow-Origin' \$WEB"
c "页面本体在独立文件（不与 CORS 判据混住）" \
  "[ -f \$WEB ] && grep -q 'object WebChatPage' \$WEB"
c "CorsPolicy.pageHtml 转发给页面本体（入口保留，两张网都还在）" \
  "grep -q 'WebChatPage.html(' \$CORS"
c "页面标题常量唯一来源（CorsPolicy 转发，不各写一份）" \
  "grep -q 'const val PAGE_TITLE = WebChatPage.PAGE_TITLE' \$CORS && grep -q 'const val PAGE_TITLE' \$WEB && grep -q 'PAGE_TITLE</title>' \$WEB"
# 模型名落进的是 `value=\"…\"`（HTML 属性），判据必须锚**实体转义**而不是
# "某个转义函数存在" —— 上一轮后者恒真，属性越界 XSS 就活在它的缺口里。
c "模型名注入页面时按 HTML 属性位置转义（否则恶意的 gguf 文件名 = 同源 XSS）" \
  "grep -q 'fun escapeForHtml' \$WEB && grep -q 'val model = escapeForHtml(modelDesc ?: \"\")' \$WEB && grep -q '\"&quot;\"' \$WEB"

# ── 6) 接线⑤：白名单从设置真注入（"填了没用"会把所有人逼回通配）────────────
c "ModelStore 有 corsEnabled 与额外来源的持久化" \
  "grep -q 'fun corsEnabled' \$MS && grep -q 'fun corsExtraOrigins' \$MS && grep -q 'fun setCorsExtraOrigins' \$MS"
c "InferenceService 启动时注入开关与白名单" \
  "grep -q 'CorsPolicy.enabled = ModelStore.corsEnabled(ctx)' \$SVC && grep -q 'CorsPolicy.addOrigin(raw)' \$SVC"
c "非法白名单条目被丢弃并留日志（绝不静默变成放行一切）" \
  "grep -q 'CORS 白名单条目无效已忽略' \$SVC"
c "设置页有 CORS 开关与额外来源输入框" \
  "grep -q 'corsCb = android.widget.CheckBox' \$ACT && grep -q 'corsExtraEt = field(' \$ACT"
c "设置页对白名单的直接生效（不必重启服务）" \
  "grep -q 'for (o in ModelStore.corsExtraOrigins(this)) CorsPolicy.addOrigin(o)' \$ACT"
c "设置页警示不许填 \"*\"（安全边界必须当场说清）" \
  "grep -q '不要填' \$ACT && grep -q '无鉴权' \$ACT"
c "设置页有「打开测试页」按钮（删掉抄 IP/端口这个环节）" \
  "grep -q 'private fun openWebPage' \$ACT && grep -q 'btn(\"打开测试页\")' \$ACT"

# ── 7) 文档：三处都要写清"这不是「自带网页就能用」的开关" ───────────────────
c "README 有 CORS 一节"                  "grep -q '## 浏览器 / CORS' \$README"
c "README 写明**不用**通配并给出原因" \
  "grep -qF '不用 \`Access-Control-Allow-Origin: *\`' \$README"
c "README 列出 OPTIONS 预检与 204"        "grep -q 'OPTIONS' \$README && grep -q '204' \$README"
c "README 写明自带测试页（GET /）"        "grep -q 'GET /' \$README"
c "README 写明「CORS 修好了不等于自带网页能用」这两件事独立" \
  "grep -q '同源' \$README"
c "HTP-STATUS.md 记录了本轮"             "grep -q 'CORS' \$STATUS"

echo ""
if [ "$bad" = "0" ]; then echo "=== CORS 接线守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== CORS 接线守卫：PASS $ok / FAIL $bad ==="; exit 1
