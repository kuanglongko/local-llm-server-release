#!/bin/sh
# 自测 `tools/run_cors_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据大多是 `grep -q '某段源码'` 形式的**存在性**断言，它有两个自欺模式：
#   1. **恒真**：pattern 写成到处都有的文本，于是把功能删掉照样 PASS
#      （桩①/桩⑧ 就是拿它检验的：白名单判定改成恒放行、GET / 路由整条删掉）；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远红，
#      很快就有人把 `exit 1` 改成 `|| true` —— 那时它彻底没用了。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真源码、守卫必须变红并指出是哪一条，再还原。
# 与 run_kv_cache_guard_tests.sh / run_think_routing_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_cors_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_cors_guard.sh
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
CORS=app/src/main/java/com/xiaowan/localinference/CorsPolicy.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
SVC=app/src/main/java/com/xiaowan/localinference/InferenceService.kt
README=README.md

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"; cp "$CORS" "$TMP/cors.bak"
cp "$ACT" "$TMP/act.bak"; cp "$SVC" "$TMP/svc.bak"; cp "$README" "$TMP/readme.bak"
restore() {
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/cors.bak" "$CORS"
    cp "$TMP/act.bak" "$ACT"; cp "$TMP/svc.bak" "$SVC"; cp "$TMP/readme.bak" "$README"
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
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/cors.bak" "$CORS"
    cp "$TMP/act.bak" "$ACT"; cp "$TMP/svc.bak" "$SVC"; cp "$TMP/readme.bak" "$README"
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


# ── 1. 桩①（白名单判定恒放行） ──────────
# 这是最该被挡住的一类改动：它**不会让任何单测变红**（单测测的是 CorsPolicy 的算法，
# 改完之后算法本身仍然‘合法’，它只是不再拒绝），却直接把攻击面从「局域网内直连」
# 放大成「你在浏览器里打开的任何一个网页」。
"$PY" - $CORS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '        if (n in allowOrigin.map { normalize(it) }) return raw\n        return null'
new = '        return raw'
assert s.count(old) == 1, "stub%d: anchor not found" % 1
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩①（白名单判定恒放行）" "未被放行的来源返回 null"
reset_src

# ── 2. 桩②（预检回通配 Origin） ──────────
"$PY" - $CORS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '        sb.append("Access-Control-Allow-Origin: ").append(allow).append("\\r\\n")'
new = '        sb.append("Access-Control-Allow-Origin: *").append("\\r\\n")'
assert s.count(old) == 1, "stub%d: anchor not found" % 2
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩②（预检回通配 Origin）" "CorsPolicy 拼出的响应头里没有通配 Origin"
reset_src

# ── 3. 桩③（预检开了 Allow-Credentials） ──────────
"$PY" - $CORS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '        sb.append("Access-Control-Max-Age: ").append(MAX_AGE_SECONDS).append("\\r\\n")'
new = '        sb.append("Access-Control-Max-Age: ").append(MAX_AGE_SECONDS).append("\\r\\n")\n        sb.append("Access-Control-Allow-Credentials: true").append("\\r\\n")'
assert s.count(old) == 1, "stub%d: anchor not found" % 3
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩③（预检开了 Allow-Credentials）" "没有任何地方发 Allow-Credentials"
reset_src

# ── 4. 桩④（删掉 OPTIONS 预检分支） ──────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
i = s.index('            if (req.method == "OPTIONS") {')
j = s.index("            when {", i)
open(p, "w", encoding="utf-8").write(s[:i] + s[j:])
PYEOF
must_red "桩④（删掉 OPTIONS 预检分支）" "handleConn 里有 OPTIONS 分支"
reset_src

# ── 5. 桩⑤（预检挪到业务路由之后） ──────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
i = s.index('            if (req.method == "OPTIONS") {')
j = s.index("            when {", i)
block = s[i:j]
s = s[:i] + s[j:]
k = s.index("                else ->\n                    writeJson(out, 404")
open(p, "w", encoding="utf-8").write(s[:k] + block + s[k:])
PYEOF
must_red "桩⑤（预检挪到业务路由之后）" "预检排在真实路由之前"
reset_src

# ── 6. 桩⑥（预检里加业务校验） ──────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '            if (req.method == "OPTIONS") {'
new = '            if (req.method == "OPTIONS") {\n                if (!LlmEngine.hasModel) { writeRaw(out, 204, ""); return }'
assert s.count(old) == 1, "stub%d: anchor not found" % 6
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑥（预检里加业务校验）" "预检**不参与**任何业务校验"
reset_src

# ── 7. 桩⑦（一条 writeJson 漏传 corsOrigin） ──────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'writeJson(out, 200, modelsJson(), corsOrigin)'
new = 'writeJson(out, 200, modelsJson())'
assert s.count(old) == 1, "stub%d: anchor not found" % 7
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑦（一条 writeJson 漏传 corsOrigin）" "所有 writeJson 调用都透传 corsOrigin"
reset_src

# ── 8. 桩⑧（删掉 GET / 路由） ──────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
i = s.index('                req.method == "GET" && (req.path == "/"')
j = s.index('                req.method == "POST" && pathUnder(req.path, "/v1/chat/completions")')
open(p, "w", encoding="utf-8").write(s[:i] + s[j:])
PYEOF
must_red "桩⑧（删掉 GET / 路由）" "GET / 路由存在"
reset_src

# ── 9. 桩⑨（白名单不再从设置注入） ──────────
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '            if (!CorsPolicy.addOrigin(raw)) {'
new = '            if (false) {'
assert s.count(old) == 1, "stub%d: anchor not found" % 9
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑨（白名单不再从设置注入）" "InferenceService 启动时注入开关与白名单"
reset_src

# ── 10. 桩⑩（README 删掉不通配的告警） ──────────
"$PY" - $README <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '- **不用 `Access-Control-Allow-Origin: *`**。本接口**无鉴权**'
new = '- 用 `Access-Control-Allow-Origin: *` 最省事。'
assert s.count(old) == 1, "stub%d: anchor not found" % 10
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑩（README 删掉不通配的告警）" "README 写明**不用**通配并给出原因"
reset_src

# ── 11. 收尾：还原后必须恢复全绿 ────────────────────────────────────────────
if run_guard; then ck "收尾：还原源码后守卫恢复全绿" 1
else ck "收尾：还原源码后守卫恢复全绿（实际红了 -> 有桩没还原干净）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== CORS 接线守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== CORS 接线守卫自测：PASS $ok / FAIL $bad ==="; exit 1
