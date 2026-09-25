#!/bin/sh
# 自测 `tools/run_web_chat_guard.sh`：把它对着几份**打桩源码树**跑，
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
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真源码、守卫必须变红并指出是哪一条，再还原。
# 与 run_cors_guard_tests.sh / run_kv_cache_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_web_chat_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_web_chat_guard.sh
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
CORS=app/src/main/java/com/xiaowan/localinference/CorsPolicy.kt
WEB=app/src/main/java/com/xiaowan/localinference/WebChatPage.kt
README=README.md
CHANGELOG=CHANGELOG.md

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"; cp "$CORS" "$TMP/cors.bak"; cp "$WEB" "$TMP/web.bak"
cp "$README" "$TMP/readme.bak"; cp "$CHANGELOG" "$TMP/changelog.bak"
restore() {
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/cors.bak" "$CORS"; cp "$TMP/web.bak" "$WEB"
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
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/cors.bak" "$CORS"; cp "$TMP/web.bak" "$WEB"
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

# ── 1. 桩①（HttpApi 不再走 CorsPolicy.pageHtml）──────────
# 症状：页面打不开，与"服务没起来"完全同形。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'CorsPolicy.pageHtml('
assert s.count(old) == 1, "stub1: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, 'WebChatPage.html(', 1))
PYEOF
must_red "桩①（HttpApi 绕过 pageHtml 入口）" "HttpApi 仍走 CorsPolicy.pageHtml"
reset_src

# ── 2. 桩②（删掉折叠设置区）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '<details id="detailsSettings">'
assert s.count(old) == 1, "stub2: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, '<div>', 1))
PYEOF
must_red "桩②（设置区不再是可折叠 details）" "设置区用原生 <details>"
reset_src

# ── 3. 桩③（字段名写歪：temperature -> temp）──────────
# 这是最典型的静默失效：参数填了不生效，不报错。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'field("temperature"'
assert s.count(old) == 1, "stub3: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, 'field("temp"', 1))
PYEOF
must_red "桩③（字段名漂移 temperature -> temp）" "temperature 在页面字段表"
reset_src

# ── 4. 桩④（每轮只发最后一句，不带历史）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'for (const m of history) messages.push(m);'
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, '', 1))
PYEOF
must_red "桩④（多轮不带历史）" "每轮把历史拼进 messages"
reset_src

# ── 5. 桩⑤（失败/空回复也进历史）──────────
# 把 pending = null 的出口删到只剩 1 处，守卫的计数断言应报红。
"$PY" - $WEB <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
parts = s.split('pending = null')
assert len(parts) >= 4, "stub5: expected >=3 occurrences, got %d" % (len(parts)-1)
# 只保留第一处
open(p, "w", encoding="utf-8").write(parts[0] + 'pending = null' + ''.join(parts[2:]))
PYEOF
must_red "桩⑤（失败/空回复也进历史）" "失败/空回复不入历史"
reset_src

# ── 6. 桩⑥（新对话不清气泡）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "$('thread').innerHTML = '';"
assert s.count(old) == 1, "stub6: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, '/* 不清气泡 */', 1))
PYEOF
must_red "桩⑥（新对话不清气泡）" "新对话同时清历史与气泡"
reset_src

# ── 7. 桩⑦（默认值不再引 SamplingParams，各写一份字面量）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'fmt(SamplingParams.DEF_TEMP)'
assert s.count(old) == 1, "stub7: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, '"0.8"', 1))
PYEOF
must_red "桩⑦（默认值各写一份字面量）" "页面默认值引 SamplingParams.DEF_TEMP"
reset_src

# ── 8. 桩⑧（README 删掉三组设置项说明）──────────
# 文案不影响编译、不影响任何单测，删了没有任何现象 —— 只能靠断言钉住。
"$PY" - $README <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '默认收起，四组'
assert s.count(old) == 1, "stub8: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, '默认收起', 1))
PYEOF
must_red "桩⑧（README 删掉设置区说明段）" "README 写明折叠设置区（原生 details、默认收起、四组）"
reset_src

# ── 9. 桩⑨（思考链退化成两态，丢掉「跟随默认」）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'value="default" selected'
assert s.count(old) == 1, "stub9: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, 'value="true" selected', 1))
PYEOF
must_red "桩⑨（思考链丢掉「跟随默认」三态）" "思考链有三态"
reset_src

# ── 10. 桩⑩（模型名改回 JS 转义器 —— H-5 本体）──────────
# 这是本文件里最重要的一根桩：把落进 `value="…"` 的模型名从实体转义改回
# `escapeForScript`，就是那条**可执行的属性越界 XSS**的确切写法。
# 它不报错、不异常、HTTP 200 —— 上一轮的守卫在这个状态下全绿。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'val model = escapeForHtml(modelDesc ?: "")'
assert s.count(old) == 1, "stub10: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, 'val model = escapeForScript(modelDesc ?: "")', 1))
PYEOF
must_red "桩⑩（模型名回到 JS 转义器 = H-5 本体）" "模型名走实体转义"
reset_src

# ── 11. 桩⑪（escapeForHtml 漏掉 `"` 这一族）──────────
# 只转 `<`/`>` 而不转引号，属性照样能被闭合 —— 症状与完全不转一样。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '            .replace("\\"", "&quot;")\n'
assert s.count(old) == 1, "stub11: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, '', 1))
PYEOF
must_red "桩⑪（实体转义漏掉引号族）" "escapeForHtml 转五个字符"
reset_src

# ── 12. 桩⑫（实体转义的 & 顺序反了）──────────
# 把 `&` 挪到最后，双转就会出现（`&lt;` → `&amp;lt;`），而症状是
# "模型名显示成实体串"，看起来像服务端数据坏了。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
amp = '        raw.replace("&", "&amp;")\n'
assert s.count(amp) == 1, "stub12: amp line not found"
tail = '            .replace("\'", "&#39;")\n'
assert s.count(tail) == 1, "stub12: tail line not found"
s = s.replace(amp, '', 1).replace(tail, tail + amp, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑫（实体转义的 & 挪到最后 = 双转）" "escapeForHtml 里 & 的替换在 < 之前"
reset_src


# ── 13. 桩⑬（H-1：bad 分支去掉 return，提示完继续发）──────────
# 这是 H-1 的确切写法：注释说"不发送"，代码里没有 return —— 分支走完照样 fetch。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    show($('out'), '这些项没填对，本轮**没有发送**：' + settings.bad.join('、') +
      '（请改成合法取值后重试；schema 见下方提示）', 'err');
    return;"""
assert s.count(old) == 1, "stub13: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old,
    """    show($('out'), '这些项没填对，本轮不下发：' + settings.bad.join('、'), 'err');""", 1))
PYEOF
must_red "桩⑬（H-1：bad 分支无 return）" "bad 分支真的 return"
reset_src

# ── 14. 桩⑭（H-2 上半：界不再引 boundsOf，改回手抄字面量）──────────
# 手抄正是漂移的来源：top_p 抄成下界 0.0、min_p 抄成上界 1.0。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "SamplingParams.boundsOf(key)"
assert s.count(old) == 1, "stub14: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old,
    'SamplingParams.Bounds(0.0, 1.0, 0.01)', 1))
PYEOF
must_red "桩⑭（H-2：界不再引 boundsOf，手抄回来）" "页面字段经 field() 造"
reset_src

# ── 15. 桩⑮（H-2 下半：界不设到控件上 = 死数据）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "inp.dataset.min = f.min;"
assert s.count(old) == 1, "stub15: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑮（H-2：界不设到控件 = 死数据）" "渲染时把 min/max/step 设到控件上"
reset_src

# ── 16. 桩⑯（H-3：modelLoaded 又变回死参数）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "${modelBanner(modelLoaded)}"
assert s.count(old) == 1, "stub16: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑯（H-3：modelLoaded 又变死参数）" "modelLoaded 真的影响渲染"
reset_src

# ── 17. 桩⑰（H-4：流收尾不再消费残留 buf）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "    for (const line of buf.split('\\n')) consume(line);\n"
assert s.count(old) == 1, "stub17: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑰（H-4：收尾不消费残留 buf）" "流结束后对残留 buf"

# ── 18. 桩⑱（#154 本体：停止时**不**先取走句柄 = 旧写法）──────────
# 旧写法整段替换回来：`if (abort) { abort.abort(); abort = null; }` —— 置空发生在
# **异步的 finally** 里，于是"已停止但 abort 还非 null"这段窗口内 btnSend 判真。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """  const ctl = abort;
  abort = null;
  if (ctl) ctl.abort();"""
assert s.count(old) == 1, "stub18: anchor not found"
new = """  if (abort) { abort.abort(); abort = null; }"""
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑱（#154 本体：停止不先取走句柄）" "FAIL  仍真的调 ctl.abort()"
reset_src

# ── 19. 桩⑲（#154：保留了 abort() 但把同步置空删掉）──────────
# 这一条钉的是"清句柄"与"取消"是**两件事**：只留 abort() 的话，请求照旧会停，
# 只有"再点发送"那一侧坏了 —— 正是最容易看漏的一半。
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """  const ctl = abort;
  abort = null;
  if (ctl) ctl.abort();"""
assert s.count(old) == 1, "stub19: anchor not found"
new = """  if (abort) abort.abort();"""
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑲（只取消不清句柄）" "FAIL  置空在 abort() 之前"
reset_src

# ── 20. 桩⑳（#154：置空与 abort() 顺序颠倒）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """  const ctl = abort;
  abort = null;
  if (ctl) ctl.abort();"""
assert s.count(old) == 1, "stub20: anchor not found"
new = """  const ctl = abort;
  if (ctl) ctl.abort();
  abort = null;"""
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑳（置空与 abort() 顺序颠倒）" "FAIL  置空在 abort() 之前"
reset_src

# ── 21. 桩㉑（#154 的第三面：收尾无条件清句柄，踩掉新一轮的）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "} finally { if (abort === myCtl) abort = null; }"
assert s.count(old) == 1, "stub21: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "} finally { abort = null; }", 1))
PYEOF
must_red "桩㉑（收尾无条件清句柄，踩掉新一轮）" "FAIL  finally 里仍清 abort"
reset_src

# ── 22. 桩㉒（本轮句柄不再自己留一份，只写全局 abort）──────────
"$PY" - $WEB <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """  const myCtl = new AbortController();
  abort = myCtl;"""
assert s.count(old) == 1, "stub22: anchor not found"
new = """  abort = new AbortController();"""
s = s.replace(old, new, 1)
old2 = "      signal: myCtl.signal,"
assert s.count(old2) == 1, "stub22b: anchor not found"
s = s.replace(old2, "      signal: abort.signal,", 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩㉒（句柄不自己留一份，收尾判据失去依据）" "FAIL  本轮句柄自己留一份 myCtl"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 自带测试页守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 自带测试页守卫自测：PASS $ok / FAIL $bad ==="; exit 1
