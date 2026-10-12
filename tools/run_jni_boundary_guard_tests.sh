#!/bin/sh
# 自测 `tools/run_jni_boundary_guard.py`：拿**就地改坏的真源码**跑它，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# 桩全是真会写出来的坏法（对应本仓库合入过的那类漂移）：
#   ① Kotlin 声明改了名、C 侧没跟着改 -> UnsatisfiedLinkError
#   ② 形参删掉一个 -> JNI 按位置取值，静默读错
#   ③ 形参类型写错（jint 写成 jlong）-> 64 位机上低位对、高位垃圾
#   ④ 结构体加了字段但没加 offsetof -> 头与 .so 不同源却无人发现
#   ⑤ offsetof 指向已不存在的字段 -> 头换版本后静默错位
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明
PY=${PYTHON:-python3}
GUARD=tools/run_jni_boundary_guard.py
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
CPP=app/src/main/cpp/llama_jni.cpp
ABI=app/src/main/cpp/chat_abi.h
HDR=app/src/main/cpp/chat/include/chat.h
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$KT" "$TMP/kt.bak"; cp "$CPP" "$TMP/cpp.bak"
cp "$ABI" "$TMP/abi.bak"; cp "$HDR" "$TMP/hdr.bak"
restore() { cp "$TMP/kt.bak" "$KT"; cp "$TMP/cpp.bak" "$CPP"
            cp "$TMP/abi.bak" "$ABI"; cp "$TMP/hdr.bak" "$HDR"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/kt.bak" "$KT"; cp "$TMP/cpp.bak" "$CPP"
              cp "$TMP/abi.bak" "$ABI"; cp "$TMP/hdr.bak" "$HDR"; }

run_guard() { set +e; "$PY" "$GUARD" > "$TMP/out" 2>&1; rc=$?; set -e; return $rc; }
must_red() {
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/out"; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」" 0; fi
}

# ── 0. 基线 ────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了）" 0; sed -n '1,40p' "$TMP/out"; fi

# ── 1. 桩①：Kotlin 改了名、C 侧没跟着改 ────────────────────────────────────
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "external fun nativeCtxSize()"
new = "external fun nativeCtxSizeRenamed()"
assert s.count(old) == 1, "桩①：没找到声明"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（Kotlin 改名、C 侧没改 -> UnsatisfiedLinkError）" "每个 external fun 都有 JNI 导出"
reset_src

# ── 2. 桩②：C 侧形参**删掉一个**（JNI 按位置取值，静默读错）────────────────
"$PY" - "$CPP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# nativeStartCompletion(prompt, maxTokens) -> 删掉 maxTokens
old = "JNIEnv * env, jclass, jstring jprompt, jint maxTokens) {"
new = "JNIEnv * env, jclass, jstring jprompt) {"
assert s.count(old) == 1, "桩②：没找到 nativeStartCompletion 形参"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（JNI 形参少一个 -> 静默读错）" "每个导出的形参**个数**与 Kotlin 声明一致"
reset_src

# ── 3. 桩③：C 侧类型写错（jint 写成 jlong）───────────────────────────────
"$PY" - "$CPP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "JNIEnv * env, jclass, jstring jprompt, jint maxTokens) {"
new = "JNIEnv * env, jclass, jstring jprompt, jlong maxTokens) {"
assert s.count(old) == 1, "桩③：没找到形参"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（JNI 形参类型写错 -> 高位垃圾）" "每个形参**类型**与 Kotlin 声明一致"
reset_src

# ── 4. 桩④：结构体加了字段但没加 offsetof 断言 ────────────────────────────
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    bool                                  force_pure_content = false;"
new = ("    bool                                  force_pure_content = false;\n"
       "    bool                                  newly_added_field = false;")
assert s.count(old) == 1, "桩④：没找到 force_pure_content"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（结构体加字段、没加 offsetof）" "结构体每个字段都有 offsetof 断言"
reset_src

# ── 5. 桩⑤：offsetof 指向已不存在的字段 ───────────────────────────────────
"$PY" - "$ABI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "offsetof(common_chat_templates_inputs, force_pure_content)"
new = "offsetof(common_chat_templates_inputs, ghost_field)"
assert s.count(old) == 1, "桩⑤：没找到该 offsetof"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（offsetof 指向幻影字段）" "offsetof 断言没有指向不存在的字段"
reset_src

# ── 6. 全部还原后回绿 ──────────────────────────────────────────────────────
if run_guard; then ck "全部还原后：守卫回绿（桩没有残留污染）" 1
else ck "全部还原后：守卫回绿" 0; fi

echo
echo "=== JNI 边界守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
