#!/bin/sh
# 自测 `tools/run_embed_params_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据里有一大半是**结构关系**（"这段字节序是不是低位在前""这个分支
# 里回的是不是字符串"），它们的自欺模式是：pattern 只写到"关键字出现过"——
# 而写坏的那一支里关键字往往还在（`base64Of(` 在错的那一支里照样在）。
# 唯一有效的做法是对着**已知该红**的桩跑，看守卫有没有点出来。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
#
# ⚠ 本轮真踩到并已修的坑：早期判据用 `_fnbody.kotlin_fn_body`，而它会把
#   **字符串字面量抹成 `""`** —— `j.has("dimensions")`、`ef == "base64"` 全变成
#   `""`，判据整片假红。已改成自带的 `kt_raw_span`（只剥注释、保留字面量）。
#   桩①③正是钉这件事：字面量一旦被抹，唯一标识（字段名）就没了。
#
# 运行：sh tools/run_embed_params_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_embed_params_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
EMB=$SRC/EmbeddingsApi.kt
API=$SRC/HttpApi.kt
JNI=app/src/main/cpp/llama_jni.cpp

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

if ! sh "$GUARD" > /dev/null 2>&1; then
    echo "FAIL  跑自测前工作树不干净：基线守卫已经是红的。"
    echo "      先手动还原（git checkout）再跑本自测。"
    exit 2
fi

TMP=$(mktemp -d)
for f in "$EMB" "$API" "$JNI"; do cp "$f" "$TMP/$(basename "$f").bak"; done
restore() {
    for f in "$EMB" "$API" "$JNI"; do
        [ -f "$TMP/$(basename "$f").bak" ] && cp "$TMP/$(basename "$f").bak" "$f"
    done
    rm -rf "$TMP"
}
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
reset_src() {
    for f in "$EMB" "$API" "$JNI"; do
        [ -f "$TMP/$(basename "$f").bak" ] && cp "$TMP/$(basename "$f").bak" "$f"
    done
}
must_red() {
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
must_green() {
    name=$1
    if run_guard; then ck "$name：守卫全绿" 1
    else ck "$name：守卫全绿（实际红了）" 0; sed -n '1,60p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 桩①：dimensions 被改回"读取后忽略"（静默 200）─────────────────────
# 把拒绝分支删掉，只留一行无害的读取 —— 这正是"字段存在但静默忽略"的形态。
"$PY" - "$EMB" <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
i = s.index('if (j.has("dimensions") && !j.isNull("dimensions"))')
j = s.index('// ② encoding_format', i)
s = s[:i] + 'val _dimIgnored = j.opt("dimensions")\n\n        ' + s[j:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩①（dimensions 被静默忽略）" "dimensions 有值就报错"
reset_src

# ── 桩②：encoding_format 的 base64 分支被摘掉（回到硬拒）───────────────
"$PY" - "$EMB" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
a = s.index('ef == "base64" -> encoding = Encoding.BASE64')
b = s.index('\n', a) + 1
s = s[:a] + s[b:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩②（base64 分支被摘掉）" "encoding_format 接受 base64"
reset_src

# ── 桩③：base64 字节序被写成**大端**（解码成功、数值离谱）─────────────
"$PY" - "$EMB" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''            bytes[i * 4] = (bits and 0xFF).toByte()
            bytes[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()'''
new = '''            bytes[i * 4] = ((bits ushr 24) and 0xFF).toByte()
            bytes[i * 4 + 1] = ((bits ushr 16) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((bits ushr 8) and 0xFF).toByte()
            bytes[i * 4 + 3] = (bits and 0xFF).toByte()'''
assert old in s
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩③（base64 字节序写成大端）" "base64 是**小端**"
reset_src

# ── 桩④：base64 改成"逐维各编一段"（长度 4N 段，调用方解不出）────────
"$PY" - "$EMB" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val bytes = ByteArray(embedding.size * 4)'
assert old in s
s = s.replace(old, 'val bytes = ByteArray(4)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩④（base64 不是整段编码）" "base64 是整段编码"
reset_src

# ── 桩⑤：base64 项被拼成数字数组（调用方按 base64 解会崩）────────────
"$PY" - "$EMB" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'Encoding.BASE64 -> head + "\\"" + base64Of(embedding) + "\\"}"'
assert old in s
s = s.replace(old, 'Encoding.BASE64 -> head + "[0]}"', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑤（base64 项回的是数字数组）" "base64 项的 embedding 是字符串"
reset_src

# ── 桩⑥：HTTP 路径不再调 detokenize（id 被静默重编码）───────────────
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert 'LlmEngine.detokenize(item.ids)' in s
s = s.replace('LlmEngine.detokenize(item.ids)', 'item.ids.joinToString(" ")', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑥（HTTP 不再 detokenize）" "HTTP 路径真的调了 detokenize"
reset_src

# ── 桩⑦：handleEmbeddings 忘了把 opt.encoding 传下去 ──────────────────
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert 'EmbeddingsApi.itemJson(i, vec, opt.encoding)' in s
s = s.replace('EmbeddingsApi.itemJson(i, vec, opt.encoding)',
              'EmbeddingsApi.itemJson(i, vec)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑦（itemJson 没带 encoding）" "base64 的取用来自解析结果"
reset_src

# ── 桩⑧：native detokenize 对越界 id 只是跳过（静默换文本）──────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''            jlog("[detokenize] id[%d]=%d 越界（vocab=%d），整条拒绝", (int) i, (int) t, (int) nVocab);
            return env->NewStringUTF("");'''
assert old in s
# 改成"跳过、继续" —— 这正是"静默换输入"的形态。
s = s.replace(old, '            continue;', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑧（越界 id 被跳过而不是整条拒绝）" "id 越界整条拒绝"
reset_src

# ── 桩⑨：native detokenize 读的是对话模型的 vocab（S 而非 E）─────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
i = s.index('Java_com_xiaowan_localinference_LlmEngine_nativeDetokenize')
j = s.index('Java_com_xiaowan_localinference_LlmEngine_nativeEmbed', i)
seg = s[i:j]
assert 'E.vocab' in seg
s = s[:i] + seg.replace('E.vocab', 'S.vocab') + s[j:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑨（detokenize 读了对话模型的 vocab）" "用的是嵌入模型那一份 vocab"
reset_src

# ── 桩⑩：detokenize 声明被删（只有 Kotlin 侧，会 UnsatisfiedLinkError）─
"$PY" - "$JNI" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'Java_com_xiaowan_localinference_LlmEngine_nativeDetokenize(\n        JNIEnv * env, jclass, jintArray jids) {'
assert old in s
s = s.replace(old, 'Java_com_xiaowan_localinference_LlmEngine_nativeDetokenize_GONE(\n        JNIEnv * env, jclass, jintArray jids) {', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑩（native 导出被改名）" "native 导出 nativeDetokenize"
reset_src

# ── 收尾：还原后必须全绿 ────────────────────────────────────────────────
must_green "还原后：守卫全绿"

echo
if [ "$bad" = 0 ]; then
    echo "=== 嵌入协议参数守卫自测 PASS $ok / FAIL $bad ==="
else
    echo "=== 嵌入协议参数守卫自测 PASS $ok / FAIL $bad ==="
    exit 1
fi
