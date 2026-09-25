#!/bin/sh
# 自测 `tools/run_render_tail_guard.sh`：对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红、且指得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条测试防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 `grep -q '某段源码'` 形式的**存在性**断言，它有两个自欺模式：
#
#   1. **恒真**：pattern 写成了一段到处都有的文本（比如只 grep `utf16_len`），
#      于是把写法改回故障版本也照样 PASS；
#   2. **恒假后被静音**：pattern 与真实源码差一个空格/引号，守卫永远 FAIL，
#      很快就有人把它的 `exit 1` 改成 `|| true` —— 那时它就彻底没用了。
#
# 这两个模式在 CI 里"跑一遍看它绿"是发现不了的（它本来就绿）。
# 唯一有效的做法是对着**已知该红**的桩跑：把源码临时改成故障版本，守卫必须变红，
# 且必须指出**是哪一条**；再改回来必须恢复全绿。
#
# 桩全部来自**本轮真正的旧写法**（见 `git show HEAD:...`），不是编出来的近似。
# 桩是"就地改真源码 + 改完还原"，不是维护一份副本：副本会随真源码演进而漂移，
# 那时这个自测测的就是一份没人看的旧代码。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_render_tail_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_render_tail_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
UTF8=app/src/main/cpp/utf8_safe.h
SRC=app/src/main/java/com/xiaowan/localinference

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"
cp "$UTF8" "$TMP/utf8.bak"
cp "$SRC/RenderedPrompt.kt" "$TMP/rp.bak"
cp "$SRC/ThinkStream.kt" "$TMP/ts.bak"
cp "$SRC/ThinkingControl.kt" "$TMP/tc.bak"
restore_all() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/utf8.bak" "$UTF8"
    cp "$TMP/rp.bak" "$SRC/RenderedPrompt.kt"
    cp "$TMP/ts.bak" "$SRC/ThinkStream.kt"
    cp "$TMP/tc.bak" "$SRC/ThinkingControl.kt"
}
restore() { restore_all; rm -rf "$TMP"; }
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

# ── 1. 桩①（F-1 的旧写法）：utf16_len 自己数 code unit，不走共享解码器 ──────
# 真实历史版本正是这一段（`git show HEAD:app/src/main/cpp/llama_jni.cpp`）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
new_body = '''static size_t utf16_len(const std::string & s) {
    size_t n = 0;
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = (unsigned char) s[i];
        if (c < 0x80) { i += 1; n += 1; }
        else if ((c & 0xE0) == 0xC0) { i += std::min<size_t>(2, s.size() - i); n += 1; }
        else if ((c & 0xF0) == 0xE0) { i += std::min<size_t>(3, s.size() - i); n += 1; }
        else if ((c & 0xF8) == 0xF0) { i += std::min<size_t>(4, s.size() - i); n += 2; }
        else { i += 1; n += 1; }
    }
    return n;
}
'''
i = s.index('static size_t utf16_len(const std::string & s) {')
j = s.index('\n}\n', i) + 3
assert 'utf8_decode_each' in s[i:j], "桩①：当前 utf16_len 不是修复版"
open(p, 'w', encoding='utf-8').write(s[:i] + new_body + s[j:])
EOF
must_red "桩①（utf16_len 自己数 code unit）" "utf16_len 调用共享解码器"

# ── 2. 桩②：发出侧不走共享解码器（自写 while 回来） ────────────────────────
cp "$TMP/jni.bak" "$JNI"
"$PY" - "$UTF8" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    utf8_decode_each(str, len, [&out](uint32_t cp) { utf8_push_code_unit(out, cp); });"
new = "    size_t i = 0;\n    while (i < len) { unsigned char c = (unsigned char) s[i]; utf8_push_code_unit(out, (uint32_t) c); i++; }"
assert s.count(old) == 1, "桩②：没找到发出侧的共享解码调用"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（发出侧自写解码）" "发出函数走 utf8_decode_each"
cp "$TMP/utf8.bak" "$UTF8"

# ── 3. 桩③（F-2 的旧写法）：RenderedPrompt 又自写一套 UTF-16 窗口 ──────────
"$PY" - "$SRC/RenderedPrompt.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            ThinkStream.classifyTail(renderedPrompt) == ThinkStream.Companion.TailShape.openOnly"
new = ("            run {\n"
       "                val tail = renderedPrompt.substring(maxOf(0, renderedPrompt.length - TAIL_WINDOW))\n"
       "                val at = tail.lastIndexOf(ThinkStream.OPEN)\n"
       "                at >= 0 && tail.substring(at).indexOf(ThinkStream.CLOSE) < 0\n"
       "            }\n"
       "        private const val TAIL_WINDOW = 256")
assert s.count(old) == 1, "桩③：没找到 openAtStartForTest 的转发实现"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（RenderedPrompt 自写 UTF-16 窗口）" "镜像是**转发**"

# ── 4. 桩④：Kotlin 尾窗口改成 UTF-16 code unit（单位分叉） ────────────────
cp "$TMP/rp.bak" "$SRC/RenderedPrompt.kt"
"$PY" - "$SRC/ThinkStream.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            val bytes = renderedPrompt.toByteArray(Charsets.UTF_8)"
new = "            val bytes = renderedPrompt.toByteArray(Charsets.UTF_8)  // (桩)\n            val unusedTail = renderedPrompt.substring(maxOf(0, renderedPrompt.length - TAIL_WINDOW_BYTES))"
assert s.count(old) == 1, "桩④：没找到字节窗口取值"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
# 关键词必须挑**这条桩真正打中的**那一行：守卫里另有一条同名不同义的断言
# （"RenderedPrompt 不得再出现旧的 UTF-16 长度窗口写法"，它在本桩下是 PASS），
# 关键词撞上它会让这条自测永远"指错行" —— 选判据文本要先确认它是 FAIL 那一行。
must_red "桩④（Kotlin 窗口退回 UTF-16 下标）" "旧的 UTF-16 长度窗口写法在全仓绝迹"
cp "$TMP/ts.bak" "$SRC/ThinkStream.kt"

# ── 5. 桩⑤：两侧窗口数值不再相同 ─────────────────────────────────────────
"$PY" - "$SRC/ThinkStream.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count("TAIL_WINDOW_BYTES = 256") == 1, "桩⑤：没找到 TAIL_WINDOW_BYTES"
open(p, 'w', encoding='utf-8').write(s.replace("TAIL_WINDOW_BYTES = 256", "TAIL_WINDOW_BYTES = 512", 1))
EOF
must_red "桩⑤（两侧窗口数值分叉）" "两侧尾窗口数值逐一相同"
cp "$TMP/ts.bak" "$SRC/ThinkStream.kt"

# ── 6. 桩⑥：ThinkingControl 又出现第三份实现 ─────────────────────────────
"$PY" - "$SRC/ThinkingControl.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        return ThinkStream.classifyTail(renderedPrompt) == ThinkStream.Companion.TailShape.openOnly"
new = ("        val tail = renderedPrompt.substring(maxOf(0, renderedPrompt.length - 256))\n"
       "        val at = tail.lastIndexOf(THINKING_START_MARKERS[0])\n"
       "        return at >= 0 && tail.substring(at).indexOf(\"</think>\") < 0")
assert s.count(old) == 1, "桩⑥：没找到 ThinkingControl 的转发实现"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（ThinkingControl 复活第三份实现）" "第三处收口"
cp "$TMP/tc.bak" "$SRC/ThinkingControl.kt"

# ── 7. 桩⑦：new_rendered_prompt 里长度段与发出串不再并存 ──────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    snprintf(hex, sizeof(hex), "%08zx", utf16_len(genSuffix));'
new = '    snprintf(hex, sizeof(hex), "%08zx", genSuffix.size());'
assert s.count(old) == 1, "桩⑦：没找到长度段写法"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（长度段改用字节数，与发出串不同源）" "同时有长度段与发出串"

# ── 8. 还原后必须回到全绿（桩不能把真源码留在坏状态）──────────────────────
restore_all
if run_guard; then ck "还原：所有桩撤销后 -> 守卫恢复全绿" 1
else ck "还原：所有桩撤销后 -> 守卫恢复全绿（实际红了）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

echo
if [ "$bad" -eq 0 ]; then
    echo "=== 渲染尾窗口/长度段守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 渲染尾窗口/长度段守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
