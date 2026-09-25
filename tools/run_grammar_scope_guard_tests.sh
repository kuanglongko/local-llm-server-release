#!/bin/sh
# 自测 `tools/run_grammar_scope_guard.sh`：对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红、且指得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条测试防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据大多是 `grep -q '某段源码'` 形式的存在性断言，它有两个自欺模式：
#
#   1. **恒真**：pattern 写成了一段到处都有的文本，于是把写法改回故障版本也照样 PASS；
#   2. **恒假后被静音**：pattern 与真实源码差一个空格/引号，守卫永远 FAIL，
#      很快就有人把它的 `exit 1` 改成 `|| true` —— 那时它就彻底没用了。
#
# 这两个模式在 CI 里"跑一遍看它绿"是发现不了的（它本来就绿）。
# 唯一有效的做法是对着**已知该红**的桩跑：把源码临时改成故障版本，守卫必须变红，
# 且必须指出**是哪一条**；再改回来必须恢复全绿。
#
# 桩全部来自**真正的旧写法**（`HttpApi.kt:1364` 的 `chatTemplateOf(null)`，见
# 模块 F 审查评论），不是编出来的近似。桩是"就地改真源码 + 改完还原"，
# 不是维护一份副本：副本会随真源码演进而漂移，那时这个自测测的就是一份没人看的旧代码。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_grammar_scope_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_grammar_scope_guard.sh
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"
restore() { cp "$TMP/http.bak" "$HTTP"; rm -rf "$TMP"; }
trap restore EXIT

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0
    else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}

# ── 0. 基线：未改动的源码树必须全绿 ─────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 桩①：/v1/completions 退回旧写法 `chatTemplateOf(null)`（= F-2 本体）──
# 这是**本轮真正的旧写法**。注意 handleChat 那一处保持正确 —— 这样才测得出
# "一个对一个错"时守卫还会不会红（存在性判据在这里会漏）。
cp "$TMP/http.bak" "$HTTP"
$PY - "$HTTP" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = "chatTemplateOverride = RequestContext.chatTemplateOf(LlmEngine.chatTemplate()),"
assert s.count(old) == 1, s.count(old)
s = s.replace(old, "chatTemplateOverride = RequestContext.chatTemplateOf(null),")
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩① 一个端点退回「让库自选」" "两个生成端点的 chatTemplateOverride 都追溯到运行时模板"

# ── 桩②：两个端点**一起**退回旧写法（计数判据也必须红）────────────────────
cp "$TMP/http.bak" "$HTTP"
$PY - "$HTTP" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = s.replace("RequestContext.chatTemplateOf(chatTemplate)",
              "RequestContext.chatTemplateOf(null)")
s = s.replace("RequestContext.chatTemplateOf(LlmEngine.chatTemplate())",
              "RequestContext.chatTemplateOf(null)")
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩② 两个端点一起退回旧写法" "两个生成端点的 chatTemplateOverride 都追溯到运行时模板"

# ── 桩③：取值来源不可追溯（换成一个与运行时模板无关的表达式）──────────────
cp "$TMP/http.bak" "$HTTP"
$PY - "$HTTP" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = "chatTemplateOverride = RequestContext.chatTemplateOf(LlmEngine.chatTemplate()),"
s = s.replace(old, 'chatTemplateOverride = RequestContext.chatTemplateOf(""),')
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩③ 空串「确认没有模板」（方向相反的同一种错）" "两个生成端点的 chatTemplateOverride 都追溯到运行时模板"

# ── 桩④：注释里写上旧写法（不得让守卫变红，也不得被当成实现）──────────────
# 这条防的是"把注释行算进实现"那类误判（模块 I 的 PR-1 踩过）。
cp "$TMP/http.bak" "$HTTP"
$PY - "$HTTP" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
anchor = "private fun handleCompletion("
i = s.index(anchor)
s = s[:i] + "// 旧写法是 RequestContext.chatTemplateOf(null)，本轮已收口\n    " + s[i:]
open(p, 'w', encoding='utf-8').write(s)
PY
if run_guard; then ck "桩④ 注释里出现旧写法 -> 守卫仍绿（不误判）" 1
else ck "桩④ 注释里出现旧写法 -> 守卫仍绿（被注释误判成实现）" 0
     grep -F 'FAIL' "$TMP/guard.out" || true; fi

# ── 桩⑤：completions 侧干脆不传 chatTemplateOverride（形参漂移）──────────
cp "$TMP/http.bak" "$HTTP"
$PY - "$HTTP" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = """                    chatTemplateOverride = RequestContext.chatTemplateOf(LlmEngine.chatTemplate()),
"""
assert s.count(old) == 1, s.count(old)
s = s.replace(old, "")
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩⑤ completions 不传模板（形参漂移）" "两个生成端点的 chatTemplateOverride 都追溯到运行时模板"

# ── 桩⑥：契约注释被删（"顺手删掉"是文案类退化最常见的形态）────────────────
cp "$TMP/http.bak" "$HTTP"
$PY - "$HTTP" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = s.replace("必须与渲染侧同源", "同源")
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩⑥ completions 侧的同源契约注释被删" "HttpApi 的 completions 侧写明「必须与渲染侧同源」"

# ── 桩⑦：native 侧把模板写死空串（退回"库自选"的另一半）──────────────────
cp "$TMP/http.bak" "$HTTP"
JNI=app/src/main/cpp/llama_jni.cpp
cp "$JNI" "$TMP/jni.bak"
$PY - "$JNI" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = "common_chat_templates_init(S.model, tmpl);"
assert s.count(old) == 1, s.count(old)
s = s.replace(old, 'common_chat_templates_init(S.model, "");')
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩⑦ native 把模板写死空串" "native 侧把 tmplOverride 交给 common_chat_templates_init"
cp "$TMP/jni.bak" "$JNI"

# ── 收尾：全部还原后必须恢复全绿 ───────────────────────────────────────────
cp "$TMP/http.bak" "$HTTP"
if run_guard; then ck "收尾：还原后 -> 守卫全绿" 1
else ck "收尾：还原后 -> 守卫全绿（没还原干净？）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

if [ "$bad" -eq 0 ]; then
    echo "=== grammar 模板同源守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== grammar 模板同源守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
