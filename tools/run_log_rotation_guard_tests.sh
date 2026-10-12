#!/bin/sh
# run_log_rotation_guard.sh 的自测：**每一条判据都要能红**，且红在**真的旧写法**上。
#
# 为什么必须有这一条：守卫失效的形态是"全绿" —— 而全绿与"真的通过了"同形。
# 所以这里把两处锚点临时换回**本轮修复前的真实旧写法**（逐字取自 main），
# 逐条跑守卫并期望它变红；再换回修复后的写法，期望全绿 ——
# 否则判据可能只是"讨厌某种写法"，而不是"钉住了正确的结构关系"。
#
# 运行：sh tools/run_log_rotation_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：本脚本会就地打桩真源码
JNI=app/src/main/cpp/llama_jni.cpp
LFS=app/src/main/java/com/xiaowan/localinference/LogFileStore.kt

ok=0; bad=0
chk() { # chk <描述> <期望:red|green>
    name="$1"; want="$2"
    if sh tools/run_log_rotation_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=$(mktemp -d "${TMPDIR:-/tmp}/log_rot_guard_XXXXXX")
cp "$JNI" "$STASH/llama_jni.cpp"
cp "$LFS" "$STASH/LogFileStore.kt"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LogFileStore.kt" "$LFS"; }
_exit_cleaned=0
_on_exit() { [ "$_exit_cleaned" = 1 ] && return 0; _exit_cleaned=1; restore; rm -rf "$STASH"; }
worktree_lock_acquire "$0"
trap _on_exit EXIT INT TERM HUP

# stub <name> <expect> <文件> <python 变换脚本（stdin: 源码路径 argv[1]）>
stub() {
    name="$1"; want="$2"; file="$3"; prog="$4"
    restore
    printf '%s\n' "$prog" | python3 - "$file"
    chk "$name" "$want"
}

# ① R-1：回到"没有重置"（本轮修复前 native 侧真实的写法）
stub "旧写法：nativeProbeInit 不重置探针文件" red "$JNI" '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
new = "    probe_rotate_prev(path.c_str());\n"
assert new in s, "源码形状变了，本自测要一起更新"
s = s.replace(new, "", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ② R-1：把重置挪到 open **之后**（重命名的是刚被打开的 fd 指向的文件，
#    "重置"看着还在、实际已经错位 —— 本仓库最擅长的那种静默失效）
stub "重置排在 open 之后（错位）" red "$JNI" '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
rot = "    probe_rotate_prev(path.c_str());\n"
ope = "    g_probe_fd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);\n"
assert rot in s and ope in s
s = s.replace(rot, "", 1).replace(ope, ope + rot, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ③ R-1：改用 unlink（文件"重置"了，上一段现场一并消失）
stub "重置改用 unlink（上一段现场被扔掉）" red "$JNI" '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
assert "rename(path, prev.c_str())" in s
s = s.replace("rename(path, prev.c_str())", "unlink(path)", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ④ R-2：回到按 lastModified 排序（本轮修复前的真实写法）
stub "旧写法：保留窗口按 lastModified 排序" red "$LFS" '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
assert "sortedByDescending { it.name }" in s
s = s.replace("?.sortedByDescending { it.name } ?: emptyList()",
              "?.sortedByDescending { it.lastModified() } ?: emptyList()", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑤ R-2：本次会话按对象同一性排除（stale 假设 —— 与"按 mtime 排序"是同一族错）
stub "旧写法：按对象排除本次（f != cur）" red "$LFS" '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
assert "f.name != cur.name" in s
s = s.replace("f.name != cur.name", "f != cur", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑥ 反向：修复后的写法必须全绿（判据不是"讨厌某种写法"）
restore
chk "修复后的写法" green

restore
if [ "$bad" -eq 0 ]; then
    echo "=== 日志重置/保留窗口守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 日志重置/保留窗口守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
