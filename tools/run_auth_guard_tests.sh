#!/bin/sh
# 自测 `tools/run_auth_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据大多是 `grep -q '某段源码'` 形式的**存在性**断言，它有两个自欺模式：
#   1. **恒真**：pattern 写成到处都有的文本，于是把功能删掉照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远红，
#      很快就有人把 `exit 1` 改成 `|| true` —— 那时它彻底没用了。
#
# 鉴权这套尤其不能只"跑一遍看它绿"：它的每一条接线错法都是**静默**的
# （头没读出来 / 判定顺序错 / token 没注入 / 看门狗被挡），
# 对着好实现跑一万次都发现不了。唯一有效的做法是对着**已知该红**的桩跑：
# 就地改真源码、守卫必须变红并指出是哪一条，再还原。
# 与 run_cors_guard_tests.sh / run_web_chat_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_auth_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_auth_guard.sh
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
AUTH=app/src/main/java/com/xiaowan/localinference/ApiAuth.kt
WEB=app/src/main/java/com/xiaowan/localinference/WebChatPage.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
SVC=app/src/main/java/com/xiaowan/localinference/InferenceService.kt
README=README.md
CHANGELOG=CHANGELOG.md

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"; cp "$AUTH" "$TMP/auth.bak"; cp "$WEB" "$TMP/web.bak"
cp "$ACT" "$TMP/act.bak"; cp "$SVC" "$TMP/svc.bak"
cp "$README" "$TMP/readme.bak"; cp "$CHANGELOG" "$TMP/changelog.bak"
restore() {
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/auth.bak" "$AUTH"; cp "$TMP/web.bak" "$WEB"
    cp "$TMP/act.bak" "$ACT"; cp "$TMP/svc.bak" "$SVC"
    cp "$TMP/readme.bak" "$README"; cp "$TMP/changelog.bak" "$CHANGELOG"
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
reset_src() {
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/auth.bak" "$AUTH"; cp "$TMP/web.bak" "$WEB"
    cp "$TMP/act.bak" "$ACT"; cp "$TMP/svc.bak" "$SVC"
    cp "$TMP/readme.bak" "$README"; cp "$TMP/changelog.bak" "$CHANGELOG"
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①（Authorization 头不再被读出来） ──────────
# 最阴的一类：鉴权判定永远拿到 null → 要么把所有人挡在门外、要么（若顺手 fail-open）
# 等于鉴权根本没做。两者都不抛异常、不打日志。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '                "authorization" -> auth = value'
new = '                "authorization-x" -> auth = value'
assert s.count(old) == 1, "stub1: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩①（Authorization 头不再被读出来）" "parseReq 读 authorization 头"
reset_src

# ── 2. 桩②（鉴权判定挪到真实路由之后） ──────────
# 等于没挡：生成端点的代码已经跑起来了、算力已经烧了，401 只是"告诉你一声"。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '''            if (ApiAuth.requiresAuth(req.method, req.path)) {'''
new = '''            if (false && ApiAuth.requiresAuth(req.method, req.path)) {'''
assert s.count(old) == 1, "stub2: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩②（鉴权判定被短路）" "鉴权判定存在"
reset_src

# ── 3. 桩③（鉴权判定挪到 OPTIONS 之前） ──────────
# 拦了预检等于 CORS 白做（症状："预检 401、正式请求压根没发"）。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
# 把"鉴权判定排在 OPTIONS 之后"破坏掉：把 OPTIONS 分支挪到鉴权判定之后。
i = s.index('            // ---- 鉴权（只针对生成端点；判据在 ApiAuth.requiresAuth）----')
j = s.index('            when {', i)
auth_block = s[i:j]
s = s[:i] + s[j:]
k = s.index('            if (req.method == "OPTIONS") {')
open(p, "w", encoding="utf-8").write(s[:k] + auth_block + s[k:])
PYEOF
must_red "桩③（鉴权判定挪到 OPTIONS 之前）" "鉴权判定排在 OPTIONS 分支"
reset_src

# ── 4. 桩④（token 不再从设置注入） ──────────
# "设置页填了 token，服务端压根不认" —— 用户以为开了鉴权，实际接口仍然裸奔。
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '        ApiAuth.token = t'
new = '        ApiAuth.token = ""'
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩④（token 不再从设置注入）" "InferenceService 注入的是 ModelStore.apiToken"
reset_src

# ── 5. 桩⑤（写日志时把凭据打出来） ──────────
# 调鉴权时最自然的调试动作就是打印收到的凭据 —— 那一行会永久留在 logcat /
# 导出文件 / Issue 截图里，鉴权白做。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '''                    emitLog("鉴权拒绝: ${req.method} ${req.path}（" +'''
new = '''                    emitLog("鉴权拒绝: ${req.method} ${req.path} auth=${req.auth}（" +'''
assert s.count(old) == 1, "stub5: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑤（日志里打印收到的凭据）" "鉴权拒绝的日志不打印收到的凭据"
reset_src

# ── 6. 桩⑥（免鉴权判据不再放行 /health） ──────────
# 服务端看门狗与 App 内探测打的就是它 —— 要 token 等于自检把自己判成不可达。
"$PY" - $AUTH <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '        if (under(path, "/health")) return false'
new = '        if (under(path, "/healthz")) return false'
assert s.count(old) == 1, "stub6: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑥（/health 不再免鉴权）" "免鉴权判据里显式放行 /health"
reset_src

# ── 7. 桩⑦（页面留空时拼空 Bearer） ──────────
# 服务端默认不开鉴权，此时空 Bearer 会被判成"凭据不匹配"而 401 ——
# 表现是"默认配置下自带页面直接不可用"。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "  if (t !== '') h['Authorization'] = 'Bearer ' + t;"
new = "  h['Authorization'] = 'Bearer ' + t;"
assert s.count(old) == 1, "stub7: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑦（页面无条件拼 Authorization 头）" "留空 => 不加 Authorization 头"
reset_src

# ── 8. 桩⑧（/v1/abort 不再带凭据） ──────────
# 表现最刁：正文照常流出来，只有"停止"按钮按了没反应（401 被吞），
# 看起来像"停止功能坏了"而不是"少带了凭据"。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "await fetch('/v1/abort', { method: 'POST', headers: authHeaders() })"
new = "await fetch('/v1/abort', { method: 'POST' })"
assert s.count(old) == 1, "stub8: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑧（/v1/abort 不再带凭据）" "/v1/abort 也带凭据"
reset_src

# ── 9. 桩⑨（注释里写 /v1/*） ──────────
# Kotlin 不支持嵌套块注释：文档注释里的 `/*` 会把后面整份文件"注释掉"，
# 编译直接报 `Unclosed comment`。本轮真踩了两次。
"$PY" - $AUTH <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = ' * 只有**生成端点**要鉴权'
new = ' * 只有 /v1/* 这些生成端点要鉴权'
assert s.count(old) == 1, "stub9: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑨（注释里写 /v1/*）" "注释里没有会被当成嵌套块注释开头的"
reset_src

# ── 10. 桩⑩（鉴权分组头被改名，块不再自成一段） ──────────
# 表现：用户在「本地服务」分组里找不到鉴权，而它"还在页面上"——
# 编译、单测、运行时都不报错。collapsify 是后处理重排，控件归属完全由
# 它相对 `── 标题 ──` 的位置决定，挪一行就能静默改掉分组。
"$PY" - $ACT <<'STUBEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '        pageSet.addView(label("── 接口鉴权（仅生成端点）──"))'
assert s.count(old) == 1, "stub10: anchor not found"
new = '        pageSet.addView(label("接口鉴权（仅生成端点）"))'
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
STUBEOF
must_red "桩⑩（鉴权分组头被改名）" "鉴权分组头在（只在本地服务段内出现一次）"
reset_src

# ── 11. 桩⑪（思考开关被搬回「本地服务」段）──────────
# 正是本次改动要防的回退：它决定生成本身，混进"怎么连"的开关里。
"$PY" - $ACT <<'STUBEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
text = '        thinkCb = android.widget.CheckBox(this).apply {'
assert s.count(text) == 1, "stub11: anchor not found"
i = s.index(text)
j = s.index('        addFull(thinkCb)\n', i) + len('        addFull(thinkCb)\n')
block = s[i:j]
s = s[:i] + s[j:]
anchor = '        pageSet.addView(label("── 本地服务（OpenAI 兼容，供局域网调用）──"))\n'
k = s.index(anchor) + len(anchor)
open(p, "w", encoding="utf-8").write(s[:k] + block + s[k:])
STUBEOF
must_red "桩⑪（思考开关被搬回本地服务段）" "思考开关在「生成与采样」段内"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 鉴权守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 鉴权守卫自测：PASS $ok / FAIL $bad ==="; exit 1
