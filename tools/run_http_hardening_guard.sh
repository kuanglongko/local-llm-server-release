#!/bin/sh
# 「请求解析边界 + 路径前缀边界 + 状态行文案」的**接线**守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠 ApiAuth 单测不够
# ═══════════════════════════════════════════════════════════════════════════
# 判据在 ApiAuth（纯函数，可离线单测），但本轮的四处失效**全在接线层**：
#
#   ① 请求体超过上限被 `coerceAtMost` **截断**，剩下的字节留在 socket 里没人读，
#      截断后的 JSON 交给解析器去炸 → 客户端拿到 500「服务内部错误」，
#      真因却是「请求体超限」。既没有 413，也没有任何可读提示。
#   ② 头行超限时 `readLine` 返回 null，而调用方写成 `?: break` —— 于是
#      「超长」与「头结束（空行）」走**同一条 break**，超长行**之后**的头被整段
#      静默丢弃。丢掉 Authorization 报成"未带 Authorization 头"（用户去反复重填
#      一个本来没错的 token），丢掉 Content-Length 报成 "messages is empty"。
#   ③ 路由与鉴权都用裸 `startsWith`，`/healthz`、`/v1/models-evil` 都算命中 ——
#      免鉴权集合被前缀悄悄放大。将来每新增一个 `/health*` / `/v1/models*`
#      端点都自动进入免鉴权侧（fail-open），而 ApiAuth 文件头承诺的是 fail-closed。
#   ④ `reason()` 缺 400/413/500 文案，状态行回 `HTTP/1.1 500 Error`。
#
# 这四条的共同点是**零症状**：请求照样有响应，只是响应对不上、或者内容少了一截。
# 存在性断言（"文件里出现过 413"）挡不住它们，必须锚定**结构**：
# 哨兵要真的被区分、上限要真的被拒、前缀要真的按边界比较。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_http_hardening_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
HTTP=$SRC/HttpApi.kt
AUTH=$SRC/ApiAuth.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 1) 上限是具名常量（散落的字面量改一处漏一处）────────────────────────────
c "请求行/头行上限是具名常量"  "grep -q 'private const val MAX_LINE_LEN = 8192' \$HTTP"
c "请求体上限是具名常量"        "grep -q 'private const val MAX_BODY_BYTES = 4 \* 1024 \* 1024' \$HTTP"

# ── 2) 「超长」与「读完」必须被区分（这是 B-3 的根因）──────────────────────
# 判据是**哨兵对象**，而不是"有没有 return null" —— 后者正是坏实现本身。
c "readLine 有独立的超长哨兵（不再与 EOF 共用 null）" \
  "grep -q 'private val LINE_TOO_LONG = String()' \$HTTP"
c "readLine 超限时返回哨兵（不是 null）" \
  "grep -q 'if (sb.length > MAX_LINE_LEN) return LINE_TOO_LONG' \$HTTP"
c "请求行超长被识别"  "grep -q 'if (first === LINE_TOO_LONG)' \$HTTP"
c "头行超长被识别"    "grep -q 'if (line === LINE_TOO_LONG)' \$HTTP"
# 反向断言：`readLine` 在**超长时返回 null** 才是 B-3 的根因 —— 那时调用方的
# `?: break` 会把"超长"与"头结束"混成一件事。现在 null 只剩 EOF 一种含义，
# 所以判据锚定"超长不再返回 null"，而不是禁掉 `?: break`（那是合法写法）。
c "readLine 超长不再返回 null（这条才是 B-3 的根因）" \
  "! grep -q 'if (sb.length > MAX_LINE_LEN) return null' \$HTTP"
c "头循环里超长判定紧跟 readLine（先判超长，再判空行=头结束）" \
  "awk '/val line = readLine\(ins\) \?: break/{f=NR} f&&/if \(line === LINE_TOO_LONG\)/{print (NR==f+1) ? \"OK\" : \"\"; exit}' \$HTTP | grep -q OK"

# ── 3) 超限**拒绝**而不是截断（B-2 的根因就是 coerceAtMost）────────────────
c "body 超限走独立分支，不截断"  "grep -q 'if (contentLength > MAX_BODY_BYTES)' \$HTTP"
c "截断式 coerceAtMost 已绝迹（它就是静默丢字节的来源）" \
  "! grep -q 'coerceAtMost(4 \* 1024 \* 1024)' \$HTTP"

# ── 4) 各自回自己的码，且**排在路由/鉴权之前** ─────────────────────────────
c "超长行 -> 400"     "grep -q 'writeJson(out, 400, errJson(\"request line or header line too long' \$HTTP"
c "超大 body -> 413"  "grep -q 'writeJson(out, 413, errJson(\"request body too large' \$HTTP"
# 顺序：畸形请求判定必须在 `when {` 之前 —— 否则会先进生成端点、先烧算力。
c "畸形请求判定排在真实路由之前" \
  "awk '/if \\(req.overlongLine\\)/{f=1} f&&/^            when \\{/{print \"OK\"; exit}' \$HTTP | grep -q OK"
c "畸形请求判定排在鉴权之前（body 没读全，鉴权无从谈起）" \
  "awk '/if \\(req.overlongLine\\)/{f=1} f&&/ApiAuth.requiresAuth\\(req.method, req.path\\)/{print \"OK\"; exit}' \$HTTP | grep -q OK"

# ── 5) 状态行文案齐备（B-1）────────────────────────────────────────────────
c "400 有文案"  "grep -q '400 -> \"Bad Request\"' \$HTTP"
c "413 有文案（缺了会回 'HTTP/1.1 413 Error'）" "grep -q '413 -> \"Payload Too Large\"' \$HTTP"
c "500 有文案（它是可达的：两处 catch(Throwable)）" "grep -q '500 -> \"Internal Server Error\"' \$HTTP"
c "404/401/503 文案仍在（本轮不许顺手弄丢）" \
  "grep -q '401 -> \"Unauthorized\"' \$HTTP && grep -q '404 -> \"Not Found\"' \$HTTP && grep -q '503 -> \"Service Unavailable\"' \$HTTP"

# ── 6) 路径前缀按边界比较（B-5）────────────────────────────────────────────
# 判据本体在 ApiAuth（可离线单测），这里钉**接线**：路由侧必须用同一条判据，
# 而不是又写回裸 startsWith。两处判据一旦分叉，"路由放过、鉴权拦下"（或反之）
# 就会出现 —— 这类不一致在浏览器里与"服务随机抽风"完全同形。
c "ApiAuth 有边界比较实现（不是裸 startsWith）" \
  "grep -q 'private fun under(path: String, base: String): Boolean' \$AUTH"
c "ApiAuth 免鉴权判据走 under（不是裸 startsWith）" \
  "grep -q 'if (under(path, \"/health\")) return false' \$AUTH && grep -q 'if (under(path, \"/v1/models\")) return false' \$AUTH"
c "ApiAuth 不再用裸 startsWith 判免鉴权路径（坏写法已绝迹）" \
  "! grep -q 'if (path.startsWith(\"/health\")) return false' \$AUTH"
# fail-closed：`/v1/` 命名空间里除两条精确豁免外一律要凭据。
# 判据写成"落在 /v1/ 命名空间即 true"，而不是 `under(path, "/v1/")` 才 true ——
# 后者会让 `/healthz` 一类既不在豁免、也不在 namespace 的路径落到 return false。
c "ApiAuth 对 /v1/ 命名空间 fail-closed（未知 v1 路径也要凭据）" \
  "grep -q 'if (path == \"/v1\" || path.startsWith(\"/v1/\") || path.startsWith(\"/v1?\")) return true' \$AUTH"
c "HttpApi 有同一条边界判据（路由侧与鉴权侧不许分叉）" \
  "grep -q 'private fun pathUnder(path: String, base: String): Boolean' \$HTTP"
c "HttpApi 路由走 pathUnder（health/models/生成端点）" \
  "grep -q 'req.method == \"GET\" && pathUnder(req.path, \"/health\")' \$HTTP && grep -q 'pathUnder(req.path, \"/v1/models\")' \$HTTP"
c "HttpApi 生成端点走 pathUnder" \
  "grep -q 'pathUnder(req.path, \"/v1/chat/completions\")' \$HTTP && grep -q 'pathUnder(req.path, \"/v1/completions\")' \$HTTP && grep -q 'pathUnder(req.path, \"/v1/abort\")' \$HTTP"
c "日志分流也走 pathUnder（否则 /healthz 会被当高频端点静音）" \
  "grep -q 'pathUnder(req.path, \"/health\") || pathUnder(req.path, \"/v1/models\")' \$HTTP"
c "HttpApi 路由不再用裸 startsWith 判路径（坏写法已绝迹）" \
  "! grep -qE 'req\\.path\\.startsWith\\(\"/(health|v1)' \$HTTP"

# ── 9) 局部变量不许「先用后声明」（本轮 CI 红的根因）──────────────────────
# B-2/B-3 把 400/413 两条拒绝**提到了路由之前**，但 `corsOrigin` 的声明留在原地 ——
# 于是拒绝路径成了它的**读者**，而读者在声明之前。Kotlin 的局部变量没有提升，
# 直接编译不过：`unresolved reference 'corsOrigin'` ×2。
#
# 为什么上一轮的 22 条判据一条都没拦住：它们**全是 grep 存在性/邻接断言**，
# 只回答"这段文字在不在、挨着谁"，从不回答"这个标识符在被读的那一刻可见吗"。
# 存在性断言对这个形态**原理上**无效（`corsOrigin` 确实在文件里出现过 20 次）。
# 下面这条判据交给 tools/run_http_scope_order.py 做真词法顺序检查。
c "局部变量不得「先用后声明」（read-before-declare）" \
  "python3 tools/run_http_scope_order.py \$HTTP"
# 反向锚：400/413 两条 writeJson 必须真的带上 corsOrigin（不能为了编译过而删参数 ——
# 那会把"拒绝响应也带 CORS 头"这条语义悄悄丢掉，浏览器侧表现成"跨域下 400 看不见"）。
# 用 awk 判"这两条 writeJson 的**实参表**里出现过 corsOrigin"，避开 $ 转义地狱：
# grep 单引号串里的 \$ 会在 eval 之前被外层 sh 展开，写对了也难读。
c "400/413 拒绝响应仍透传 corsOrigin（不许为编译过而删参数）" \
  "awk '/writeJson\\(out, 4[0-9][0-9],/{f=NR} f&&NR<=f+3&&/corsOrigin/{print \"OK\"; exit}' \$HTTP | grep -q OK"
c "400/413 拒绝留在路由之前（移动声明时不许把它们顺回后面）" \
  "awk '/if \(req.overlongLine\)/{f=1} f&&/^            when \\{/{print \"OK\"; exit}' \$HTTP | grep -q OK"

# ── 7) 拒绝路径也要留痕（否则用户只能靠猜）────────────────────────────────
c "超长行拒绝留日志"    "grep -q '拒绝畸形请求' \$HTTP"
c "超限 body 拒绝留日志" "grep -q '拒绝超限请求体' \$HTTP"
# 日志与正文**不许回显**收到的原始内容（可能含凭据/用户数据）。
c "拒绝日志不打印请求内容本身" \
  "! grep -qE 'emitLog\\(.*(req\\.body|req\\.auth|first|line)\\)' \$HTTP"

echo ""
if [ "$bad" = "0" ]; then echo "=== 请求边界/前缀守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 请求边界/前缀守卫：PASS $ok / FAIL $bad ==="; exit 1
