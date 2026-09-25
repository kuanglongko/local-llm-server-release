#!/bin/sh
# 自测 `tools/run_think_routing_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条测试防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 `grep -q '某段源码'` 形式的**存在性**断言，它有两个自欺模式：
#
#   1. **恒真**：pattern 写成了一段到处都有的文本（比如只 grep `RenderedPrompt`
#      而不断言它的**分支**），于是把判据改回故障版本也照样 PASS；
#   2. **恒假后被静音**：pattern 与真实源码差一个空格/引号，守卫永远 FAIL，
#      很快就有人把它的 `exit 1` 改成 `|| true` —— 那时它就彻底没用了。
#
# 这两个模式在 CI 里"跑一遍看它绿"是发现不了的（它本来就绿）。
# 唯一有效的做法是对着**已知该红**的桩跑：把源码临时改成故障版本，
# 守卫必须变红，且必须指出**是哪一条**；再改回来，必须恢复全绿。
#
# 桩是"就地改真源码 + 改完还原"，不是维护一份副本：
# 副本会随真源码演进而漂移，那时这个自测测的就是一份没人看的旧代码。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_think_routing_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_think_routing_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
PU=app/src/main/cpp/probe_util.h
SRC=app/src/main/java/com/xiaowan/localinference

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
# 备份 + 保证任何退出路径都还原：桩一旦没还原，后面所有用例都在改坏的树上跑。
cp "$JNI" "$TMP/jni.bak"
cp "$PU" "$TMP/pu.bak"
cp "$SRC/RenderedPrompt.kt" "$TMP/rp.bak"
cp "$SRC/ThinkStream.kt" "$TMP/ts.bak"
cp "$SRC/HttpApi.kt" "$TMP/http.bak"
cp "$SRC/EngineActivity.kt" "$TMP/ui.bak"
restore() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/pu.bak" "$PU"
    cp "$TMP/rp.bak" "$SRC/RenderedPrompt.kt"
    cp "$TMP/ts.bak" "$SRC/ThinkStream.kt"; cp "$TMP/http.bak" "$SRC/HttpApi.kt"
    cp "$TMP/ui.bak" "$SRC/EngineActivity.kt"
    rm -rf "$TMP"
}
trap restore EXIT

run_guard() { # -> 打印输出，返回守卫退出码
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then
        ck "$name：守卫变红" 0
    else
        ck "$name：守卫变红" 1
    fi
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

# ── 1. 桩①：判据退回「见过 <think> 就算已开」（这就是本次真机故障）──────────
# 真实历史版本正是少看了闭合标签这一步，于是 MiniCPM5 思考开时判反。
"$PY" - "$PU" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ("    if (tail.find(CLOSE, at) != std::string::npos) return ThinkTailShape::kClosed;\n"
       "    return ThinkTailShape::kOpenOnly;")
new = "    (void) CLOSE;\n    return ThinkTailShape::kOpenOnly;"
assert s.count(old) == 1, "桩①：没找到要替换的判据分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（判据不看闭合标签）" "没有闭合标签」"

# ── 2. 桩②：宿主又自己扫字符串判定（近似判据复活）──────────────────────────
cp "$TMP/pu.bak" "$PU"   # 只还原桩①改过的那个文件（不能用 restore，它会删掉 $TMP）
"$PY" - "$SRC/ThinkStream.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    companion object {\n        const val OPEN = \"<think>\""
new = "    companion object {\n        fun promptEndsWithOpenThink(p: String): Boolean = false\n        const val OPEN = \"<think>\""
assert s.count(old) == 1, "桩②：没找到 companion object"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（宿主复活扫串近似）" "近似函数已删除"

# ── 3. 桩③：只改 HTTP 一条路径，App 内聊天漏改 ──────────────────────────────
cp "$TMP/ts.bak" "$SRC/ThinkStream.kt"
"$PY" - "$SRC/EngineActivity.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count("rendered.text") >= 1, "桩③：EngineActivity 里没有 rendered.text"
s = s.replace("softSwitchApplies(thinkingOn, chatTemplate, rendered.text)",
              "softSwitchApplies(thinkingOn, chatTemplate, promptOldText)", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩③（App 内聊天路径漏改）" "App 内聊天路径同步消费"
cp "$TMP/ui.bak" "$SRC/EngineActivity.kt"   # 立刻还原，别把坏状态带进后续桩

# ── 4. 桩④：软开关之后把标注**错翻成 true**（ISSUE #106 的根因）───────────
# 正确写法是 `&& !soft`；桩把它改回旧的错误叠加 `|| soft` —— 那会让整段正文
# 被当 reasoning 折叠进思考块，正是 2026-09-21 真机 LFM2.6B 的现象。
cp "$TMP/http.bak" "$SRC/HttpApi.kt"
"$PY" - "$SRC/HttpApi.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "val openAtStart = rendered.openAtStart && !soft"
assert s.count(old) == 1, "桩④：没找到 openAtStart 赋值"
open(p, 'w', encoding='utf-8').write(s.replace(old, "val openAtStart = rendered.openAtStart || soft", 1))
EOF
must_red "桩④（软开关后标注错翻成 true）" "软开关注入后 openAtStart 落到 false"

# ── 5. 桩⑤：只改一条渲染路径（另一条漏改）─────────────────────────────────
cp "$TMP/http.bak" "$SRC/HttpApi.kt"
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
import re
pat = re.compile(r"return new_rendered_prompt\(env, cp\.prompt[,)]")
assert len(pat.findall(s)) == 2, "桩⑤：渲染出口不是 2 处"
# 只把**第一条**出口换成不走标注的裸返回
open(p, 'w', encoding='utf-8').write(pat.sub("return new_string_utf8_safe(env, cp.prompt.c_str())", s, count=1))
EOF
must_red "桩⑤（只标注一条渲染路径）" "两条渲染路径都走同一个标注出口"

# ── 6. 还原后必须回到全绿（桩不能把真源码留在坏状态）──────────────────────
cp "$TMP/jni.bak" "$JNI"
cp "$TMP/pu.bak" "$PU"
cp "$TMP/rp.bak" "$SRC/RenderedPrompt.kt"
cp "$TMP/ts.bak" "$SRC/ThinkStream.kt"
cp "$TMP/http.bak" "$SRC/HttpApi.kt"
cp "$TMP/ui.bak" "$SRC/EngineActivity.kt"
if run_guard; then ck "还原：所有桩撤销后 -> 守卫恢复全绿" 1
else ck "还原：所有桩撤销后 -> 守卫恢复全绿（实际红了）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

echo
if [ "$bad" -eq 0 ]; then
    echo "=== 思考路由守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 思考路由守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
