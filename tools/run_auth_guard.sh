#!/bin/sh
# 「Bearer 鉴权」的**接线**守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠 run_auth_tests.sh 不够
# ═══════════════════════════════════════════════════════════════════════════
# 单测钉住了 ApiAuth 这个纯函数的判据（哪条路径该要 token、Bearer 怎么拆、
# 比较是否定长），但钉不住**接线** —— 而这一整套的接线错法全是静默的：
#
#   ① `Authorization` 头没被**读出来**：`ApiAuth.verdict` 永远拿到 null，
#      于是要么把所有人挡在门外、要么（若顺手 fail-open）等于鉴权根本没做。
#      两者都不抛异常、不打日志。
#   ② 鉴权判定放在**真实路由之后**：等于没挡 —— 生成端点的代码已经跑起来了，
#      算力已经烧了，401 只是"告诉你一声"。
#   ③ 鉴权判定放到 **OPTIONS 之前**：预检是浏览器自动发的、不带 Authorization，
#      拦了它等于 CORS 白做（症状："预检 401、正式请求压根没发"）。
#   ④ token 不再从设置注入：设置页填了 token，服务端压根不认 ——
#      用户以为开了鉴权，实际接口仍然裸奔。
#   ⑤ 服务端自己打 `/health` 的那两处（看门狗 + App 内探测）被鉴权挡住：
#      表现是"服务明明在跑，探测说不可达"，排查方向一开始就是错的。
#   ⑥ 把 token 写进日志或错误正文：截图 / Issue 里飞出去，鉴权白做。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_auth_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
HTTP=$SRC/HttpApi.kt
AUTH=$SRC/ApiAuth.kt
WEB=$SRC/WebChatPage.kt
ACT=$SRC/EngineActivity.kt
SVC=$SRC/InferenceService.kt
MS=$SRC/ModelStore.kt
README=README.md
STATUS=HTP-STATUS.md
CHANGELOG=CHANGELOG.md

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 0) 判据本体独立成文件（可被宿主单测；也保证"只有一处判据"）──────────────
c "ApiAuth.kt 是独立判据文件（可被宿主单测）" "[ -f \$AUTH ]"
c "判据是个 object（纯函数产物）" "grep -q 'object ApiAuth' \$AUTH"
# 文档注释里出现 `/*`（典型是写 `/v1/*`）会被 Kotlin 当成**嵌套块注释**的开头
# （Kotlin 不支持嵌套），于是整份文件后面的代码全被"注释掉"，编译报 Unclosed comment。
# 本轮真的踩了两次（ApiAuth.kt 与 WebChatPage.kt）。这条断言把它钉住。
c "注释里没有会被当成嵌套块注释开头的 /*（别写 /v1/* 那种）" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$AUTH"

# ── 1) 接线①：Authorization 头必须被真的读出来 ────────────────────────────
c "parseReq 读 authorization 头" "grep -q '\"authorization\" -> auth = value' \$HTTP"
c "Req 携带 auth（不只是响应侧拼头）" "grep -q 'val auth: String? = null' \$HTTP"

# ── 2) 接线②：鉴权判定排在真实路由**之前**、OPTIONS **之后** ──────────────
# 判据是"顺序"：三段锚点在文件里必须依次出现。
#   OPTIONS 分支  ->  鉴权判定  ->  真实路由的 when {
# 判据是"这个 if **真的**在执行"，不是"文件里出现过这个调用" ——
# 后者会被 `if (false && ApiAuth.requiresAuth(...))` 骗过（自测桩② 抓出来的）。
c "鉴权判定存在且真的执行（不是被短路/catch 吞掉）" \
  "grep -qE '^\s*if \(ApiAuth.requiresAuth\(req.method, req.path\)\) \{' \$HTTP"
c "鉴权判定排在真实路由之前（挡在生成端点外面，不是进去再判）" \
  "awk '/ApiAuth.requiresAuth\\(req.method, req.path\\)/{f=1} f&&/^            when \\{/{print \"OK\"; exit}' \$HTTP | grep -q OK"
c "鉴权判定排在 OPTIONS 分支**之后**（预检必须豁免，否则 CORS 白做）" \
  "awk '/if \\(req.method == \"OPTIONS\"\\)/{o=NR} /ApiAuth.requiresAuth\\(req.method, req.path\\)/{a=NR} END{print (o>0 && a>o) ? \"OK\" : \"\"}' \$HTTP | grep -q OK"
c "判定结果非 null 就回 401 并 return（不落进 when）" \
  "grep -q 'writeRaw(out, 401, errJson(deny)' \$HTTP"
c "401 有对应的 reason 文案（缺了会回 'HTTP/1.1 401 Error'）" \
  "grep -q '401 -> \"Unauthorized\"' \$HTTP"
c "401 带 WWW-Authenticate 头（告诉客户端该带什么凭据）" \
  "grep -q 'ApiAuth.unauthorizedHeaders()' \$HTTP"

# ── 3) 硬安全承诺：token 绝不进日志 / 错误正文 ─────────────────────────────
# 这几条是"防手滑"：调鉴权时最自然的调试动作就是把收到的凭据打出来，
# 而那一行日志会永久留在 logcat / 导出文件 / Issue 截图里。
c "鉴权拒绝的日志不打印收到的凭据" \
  "! grep -qE 'emitLog\\(.*(req\\.auth|got|bearer\\()' \$HTTP"
c "鉴权拒绝的日志点明了是「没带」还是「不匹配」（否则无法归因）" \
  "grep -q '凭据不匹配' \$HTTP && grep -q '未带 Authorization 头' \$HTTP"
# 错误正文只来自 ApiAuth.verdict（唯一一处措辞），HttpApi 不另编一套。
c "401 正文来自 ApiAuth.verdict（唯一措辞来源，不各写一份）" \
  "grep -q 'val deny = ApiAuth.verdict(req.auth)' \$HTTP"

# ── 4) 接线③：token 从设置真注入（"填了没用" = 鉴权白做）────────────────────
c "ModelStore 有 apiToken 与 setApiToken 的持久化" \
  "grep -q 'fun apiToken(ctx: Context)' \$MS && grep -q 'fun setApiToken(ctx: Context' \$MS"
c "InferenceService 有 applyApiToken（把 token 注入 ApiAuth）" \
  "grep -q 'private fun applyApiToken(ctx: android.content.Context)' \$SVC"
c "InferenceService 注入的是 ModelStore.apiToken" \
  "grep -q 'ModelStore.apiToken(ctx)' \$SVC && grep -q 'ApiAuth.token = t' \$SVC"
# 三个启动分支（已加载模型挂载 / 正常启动 / 系统重启自愈）都要注入 ——
# 漏掉任何一个都会制造"同一个设置有的路径生效、有的不生效"。
c "三个启动分支都调用了 applyApiToken" \
  "[ \"\$(grep -c 'applyApiToken(applicationContext)' \$SVC)\" -ge 3 ]"
c "空 token = 鉴权关闭这点在注入侧写清（不隐式兜底成一个默认 token）" \
  "grep -q '空 token = 鉴权关闭' \$SVC"
c "设置页有 token 只读展示框" "grep -q 'authEt = field(' \$ACT"
c "设置页能生成 token（走 ApiAuth.regenerate）" "grep -q 'ApiAuth.regenerate()' \$ACT"
c "设置页能关闭鉴权（置空 + 立即生效）" "grep -q 'ModelStore.setApiToken(this@EngineActivity, \"\")' \$ACT"
c "设置页有复制按钮（手机上抄 24 位必然出错）" "grep -q 'copyToClipboard' \$ACT"
c "设置页改写 token 后**立即生效**（不必重启服务）" \
  "grep -q 'ApiAuth.token = t' \$ACT"
c "设置页警示「不是访问控制」（安全边界必须当场说清）" \
  "grep -q '它不是访问控制' \$ACT || grep -q '不是访问控制' \$ACT"
c "设置页提示回显里不出现 token 片段" \
  "! grep -qE 'authHintText\\(\\).*\\\$\\{?t' \$ACT"

# ── 5) 接线④：服务端自身打 /health 的两处不能被鉴权挡住 ────────────────────
# 看门狗（自连探活）与 App 内「存活探测」都不带 Authorization，靠的就是
# `/health` 免鉴权。判据查的是"这两处的请求里没有 Authorization"这一事实
# 由 ApiAuth 的免鉴权判据保证 —— 即 requiresAuth 里必须显式放行 /health。
# 判据锚定"落在 /health 之下"（边界比较），不是裸前缀 ——
# 裸 startsWith 会把 /healthz 也算命中，免鉴权集合被悄悄放大（fail-open）。
c "免鉴权判据里显式放行 /health（看门狗与 App 自检靠它）" \
  "grep -q 'if (under(path, \"/health\")) return false' \$AUTH"
c "免鉴权判据里显式放行 OPTIONS" "grep -q 'if (m == \"OPTIONS\") return false' \$AUTH"
c "看门狗探活打的就是 /health（这条免鉴权是必需的，不是顺手）" \
  "grep -q 'GET /health HTTP/1.0' \$HTTP"

# ── 6) 自带测试页：/v1/* 带凭据、留空不拼空 Bearer ────────────────────────
c "页面有 token 输入框" "grep -q 'id=\"token\"' \$WEB"
c "页面有唯一的鉴权头拼装入口 authHeaders" "grep -q 'function authHeaders(' \$WEB"
c "留空 => 不加 Authorization 头（否则默认配置下页面直接不可用）" \
  "grep -q \"if (t !== '') h\\['Authorization'\\] = 'Bearer ' + t;\" \$WEB"
# /v1/abort 是要鉴权的生成端点，漏了它的表现是"停止按了没反应"。
c "/v1/abort 也带凭据（否则停止按钮看起来坏了）" "grep -q 'headers: authHeaders()' \$WEB"
c "生成请求带凭据" "grep -q 'headers: authHeaders(' \$WEB"

# ── 6b) 分组归属：鉴权必须留在「本地服务」段内，思考开关必须离开它 ──────────
# 这一组钉的是**控件落在哪个折叠分组里**，而不是控件存在与否。
# 为什么值得单测：collapsify 是**后处理重排** —— 它按 `── 标题 ──` 切段，
# 于是"把某个 addView 挪一行"就能静默改掉分组归属，而编译、单测、运行时
# 全都不报错。表现是"用户在设置页找不到那个开关"。
#
# 判据写得**故意不依赖行距离**：`── 本地服务` 段里还会插入 CORS 等一堆控件，
# 用"两行之间不超过 N 行"会在正常演进里误报。改成断言**控件在段内**、
# 且**下一段头在它之后**——两段头之间的全部内容就是这个分组。
c "鉴权分组头在（只在本地服务段内出现一次）" \
  "[ \"\$(grep -c '── 接口鉴权（仅生成端点）──' \$ACT)\" = \"1\" ]"
c "鉴权块在「本地服务」段内（段头在前、下一段头在后）" \
  "awk '/── 本地服务（OpenAI 兼容/{sec=NR} /── 接口鉴权（仅生成端点）──/{a=NR} /^        pageSet.addView\(label\(\"── /{ if(NR!=a && sec>0 && a>sec && !n) n=NR } END{print (sec>0 && a>sec && n>a) ? \"OK\" : \"\"}' \$ACT | grep -q OK"
c "思考开关在「生成与采样」段内（不在本地服务段）" \
  "awk '/── 生成与采样（对话时生效）──/{g=NR} /── 重复与惩罚（防复读）──/{r=NR} /thinkCb = android.widget.CheckBox/{t=NR} END{print (t>0 && g>0 && r>0 && t>g && t<r) ? \"OK\" : \"\"}' \$ACT | grep -q OK"
c "思考开关排在「本地服务」段之后（即已不在该段内）" \
  "awk '/── 本地服务（OpenAI 兼容/{s=NR} /thinkCb = android.widget.CheckBox/{t=NR} END{print (s>0 && t>s) ? \"OK\" : \"\"}' \$ACT | grep -q OK"
c "思考开关用的是 addFull（与采样参数同一套整行布局，不是 pageSet 直加）" \
  "grep -q 'addFull(thinkCb)' \$ACT"

# ── 7) 文档：token 的用法与"它不是访问控制"都要写清 ────────────────────────
c "README 写明 Authorization: Bearer 的用法" \
  "grep -q 'Authorization: Bearer' \$README"
c "README 写明哪些端点免鉴权（/health、/v1/models、OPTIONS、GET /）" \
  "grep -q '带鉴权' \$README || grep -q '免鉴权' \$README"
c "README 写明默认不开（保持旧行为）" \
  "grep -q '默认不开' \$README || grep -q '未设 token' \$README || grep -q '默认不开启' \$README"
c "README 写明它不是访问控制（与 CORS 同一句提醒）" \
  "grep -q '不是访问控制' \$README"
c "CHANGELOG 记录了本轮" "grep -q 'Bearer' \$CHANGELOG"
c "HTP-STATUS.md 记录了本轮" "grep -q '鉴权\|Bearer' \$STATUS"

echo ""
if [ "$bad" = "0" ]; then echo "=== 鉴权接线守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 鉴权接线守卫：PASS $ok / FAIL $bad ==="; exit 1
