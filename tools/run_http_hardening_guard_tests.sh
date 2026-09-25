#!/bin/sh
# 自测 `tools/run_http_hardening_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据大多是 `grep -q` 形式的**存在性**断言，它有两个自欺模式：
#   1. **恒真**：pattern 写成到处都有的文本，于是把功能删掉照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远红，
#      很快就有人把 `exit 1` 改成 `|| true` —— 那时它彻底没用了。
#
# 本轮这四条失效（静默截断 / 静默丢头 / 前缀越界 / 文案缺失）都不会让别的
# 测试变红，唯一有效的做法是对着**已知该红**的桩跑：就地改真源码、
# 守卫必须变红并指出是哪一条，再还原。
# 与 run_auth_guard_tests.sh / run_cors_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_http_hardening_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_http_hardening_guard.sh
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
AUTH=app/src/main/java/com/xiaowan/localinference/ApiAuth.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"; cp "$AUTH" "$TMP/auth.bak"
restore() {
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/auth.bak" "$AUTH"
    rm -rf "$TMP"
}
trap restore EXIT

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
reset_src() { cp "$TMP/http.bak" "$HTTP"; cp "$TMP/auth.bak" "$AUTH"; }

# must_red "<桩名>" "<期望被点名的那条断言>"
must_red() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    if [ "$rc" = "0" ]; then
        echo "FAIL  $1：守卫没变红（该断言失效了）"; bad=$((bad+1)); return
    fi
    if grep -qF "$2" "$TMP/guard.out"; then
        echo "PASS  $1：守卫变红，且指到了「$2」"; ok=$((ok+1))
    else
        echo "FAIL  $1：守卫红了，但没指到「$2」"; bad=$((bad+1))
    fi
}

# ── 桩①（body 超限又退回截断）──────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '        if (contentLength > MAX_BODY_BYTES) {'
new = '        if (false) {'
assert s.count(old) == 1, "stub1: anchor not found"
s = s.replace(old, new, 1)
old2 = '            val buf = ByteArray(contentLength)'
new2 = '            val buf = ByteArray(contentLength.coerceAtMost(4 * 1024 * 1024))'
assert s.count(old2) == 1, "stub1b: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old2, new2, 1))
PYEOF
must_red "桩①（超限退回静默截断）" "截断式 coerceAtMost 已绝迹"
must_red "桩①（超限退回静默截断）" "body 超限走独立分支"
reset_src

# ── 桩②（readLine 超长又返回 null，与 EOF 混同）────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'if (sb.length > MAX_LINE_LEN) return LINE_TOO_LONG'
new = 'if (sb.length > MAX_LINE_LEN) return null'
assert s.count(old) == 1, "stub2: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩②（超长与 EOF 混同）" "readLine 超长不再返回 null"
must_red "桩②（超长与 EOF 混同）" "readLine 超限时返回哨兵"
reset_src

# ── 桩③（头循环把超长混进 break）──────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '            if (line === LINE_TOO_LONG) return Req(method = "", path = "", body = "", overlongLine = true)\n'
assert s.count(old) == 1, "stub3: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩③（头循环把超长混进 break）" "头行超长被识别"
reset_src

# ── 桩④（413 文案被删）────────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '413 -> "Payload Too Large"; '
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩④（413 文案被删）" "413 有文案"
reset_src

# ── 桩⑤（500 文案被删）────────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = ' 500 -> "Internal Server Error"'
assert s.count(old) == 1, "stub5: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑤（500 文案被删）" "500 有文案"
reset_src

# ── 桩⑥（鉴权免鉴权判据退回裸 startsWith）──────────────
"$PY" - $AUTH <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'if (under(path, "/health")) return false'
new = 'if (path.startsWith("/health")) return false'
assert s.count(old) == 1, "stub6: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑥（免鉴权退回裸 startsWith）" "ApiAuth 免鉴权判据走 under"
must_red "桩⑥（免鉴权退回裸 startsWith）" "ApiAuth 不再用裸 startsWith"
reset_src

# ── 桩⑦（/v1/ 命名空间不再 fail-closed）───────────────
"$PY" - $AUTH <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'if (path == "/v1" || path.startsWith("/v1/") || path.startsWith("/v1?")) return true'
new = 'if (under(path, "/v1/")) return true'
assert s.count(old) == 1, "stub7: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑦（/v1/ 不再 fail-closed）" "fail-closed"
reset_src

# ── 桩⑧（路由侧退回裸 startsWith，与鉴权侧分叉）────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'req.method == "GET" && pathUnder(req.path, "/health") ->'
new = 'req.method == "GET" && req.path.startsWith("/health") ->'
assert s.count(old) == 1, "stub8: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑧（路由侧退回裸 startsWith）" "HttpApi 路由不再用裸 startsWith"
reset_src

# ── 桩⑨（畸形请求判定挪到真实路由之后）────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
i = s.index('            if (req.overlongLine) {')
j = s.index('            // /health 与 /v1/models 为高频轮询端点', i)
block = s[i:j]
s = s[:i] + s[j:]
k = s.index("                else ->\n                    writeJson(out, 404")
open(p, "w", encoding="utf-8").write(s[:k] + block + s[k:])
PYEOF
must_red "桩⑨（畸形判定挪到路由之后）" "畸形请求判定排在真实路由之前"
reset_src

# ── 桩⑩（拒绝路径不留日志）────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'emitLog("拒绝超限请求体: 声明超过 ${MAX_BODY_BYTES} 字节（未读 body，直接 413）")'
assert s.count(old) == 1, "stub10: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑩（超限拒绝不留日志）" "超限 body 拒绝留日志"
reset_src

# ── 桩⑪（corsOrigin 先用后声明 —— 2026-09-22 CI 红的原样复刻）──────────
# 桩的来源是**真实故障**，不是编出来的反例：把 400/413 两条拒绝留在原位、
# 把 `val corsOrigin = ...` 的声明挪到它们**之后**。
# 期望：判据⑨（read-before-declare）变红并点出 `corsOrigin`。
# 这一格同时在证明"存在性断言对这个形态无效"—— 桩改完后
# `corsOrigin` 仍出现 20 次、`writeJson` 仍与它相邻，其余判据全绿，只有⑨红。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
i = s.index('            // ---- CORS 白名单判定')
k = s.index('            // 畸形请求**必须**挡在路由与鉴权之前')
block = s[i:k]                       # 声明 + 拒绝日志整块
s = s[:i] + s[k:]                    # 先摘掉它
anchor = '            // /health 与 /v1/models 为高频轮询端点'
assert s.count(anchor) == 1, "stub11: anchor not found"
j = s.index(anchor)
open(p, "w", encoding="utf-8").write(s[:j] + block + s[j:])  # 挪到 400/413 之后
PYEOF
must_red "桩⑪（corsOrigin 先用后声明）" "read-before-declare"
reset_src

# ── 收尾：还原后守卫必须恢复全绿 ──────────────────────
set +e
sh "$GUARD" > "$TMP/guard.out" 2>&1
rc=$?
set -e
if [ "$rc" = "0" ]; then echo "PASS  收尾：还原源码后守卫恢复全绿"; ok=$((ok+1));
else echo "FAIL  收尾：还原后守卫仍红"; grep '^FAIL' "$TMP/guard.out" || true; bad=$((bad+1)); fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 请求边界守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 请求边界守卫自测：PASS $ok / FAIL $bad ==="; exit 1
