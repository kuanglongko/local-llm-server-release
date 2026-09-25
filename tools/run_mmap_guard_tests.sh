#!/bin/sh
# run_mmap_guard.sh 的自测：**每一条判据都要能红**，且红在**真的旧写法**上。
#
# 为什么必须有这一条：守卫失效的形态是"全绿" —— 而全绿与"真的通过了"同形。
# 所以这里把 mmap 那一段临时换成**本轮修复前的真实旧写法**（逐字取自 `3b8e2a5`，
# 它在 main 上真的存在过），逐条跑守卫并期望它变红；再换回修复后的写法，
# 期望全绿 —— 否则判据可能只是"讨厌某种写法"，而不是"钉住了正确的结构关系"。
#
# 运行：sh tools/run_mmap_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
chk() { # chk <描述> <期望:red|green>
    name="$1"; want="$2"
    if sh tools/run_mmap_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=/tmp/mmap_guard_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$JNI" "$STASH/llama_jni.cpp"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; }
trap restore EXIT

# stub <name> <expect> <python 变换脚本（stdin: 源码路径 argv[1]）>
stub() {
    name="$1"; want="$2"; prog="$3"
    cp "$STASH/llama_jni.cpp" "$JNI"
    printf '%s\n' "$prog" | python3 - "$JNI"
    chk "$name" "$want"
}

# ① 逐字回到 0.9.6 的单支写法（这就是用户在真机上撞到的那一版）
stub "旧写法：只落『关』这一支" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
new = "    mp.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;"
old = "    if (!useMmap) mp.load_mode = LLAMA_LOAD_MODE_NONE;"
assert new in s, "源码形状变了，本自测要一起更新"
s = s.replace(new, old, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ② 三目回来，但同时**又**留了一条单支赋值（复制粘贴最可能产生的形态：
#    新写法加了，旧的 if 忘了删 -> 赋值变成两条，且后者会覆盖前者）
stub "新旧并存：三目 + 遗留单支（计数应为 2）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
new = "    mp.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;"
old = new + "\n    if (!useMmap) mp.load_mode = LLAMA_LOAD_MODE_NONE;"
assert new in s
s = s.replace(new, old, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ③ 换成"只用常量 NONE"：开关彻底失效（用户设 1 也走无 mmap 直读）
stub "常量写死 NONE：开关失效" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
new = "    mp.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;"
s = s.replace(new, "    mp.load_mode = LLAMA_LOAD_MODE_NONE;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ④ 改用库的字符串解析（第三方行为，且本变体里未必按我们以为的语义解析）
stub "改用 llama_load_mode_from_str" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
new = "    mp.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;"
s = s.replace(new,
    "    mp.load_mode = llama_load_mode_from_str(useMmap ? \"mmap\" : \"none\");", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑤ 把可观测那一行删掉（开关对了，但日志里读不到实际交了什么 —— 本轮排障的入口）
stub "删掉 load_mode 日志" red '
import sys, io, re
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
i = s.index("    jlog(\"[加载] mmap=%d")
j = s.index("modelPath.c_str());", i) + len("modelPath.c_str());")
k = s.index("\n", j) + 1
s = s[:i] + s[k:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑥ 反向：修复后的写法必须全绿（判据不是"讨厌某种写法"）
cp "$STASH/llama_jni.cpp" "$JNI"
chk "修复后的写法" green

restore
if [ "$bad" -eq 0 ]; then
    echo "=== mmap 开关守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 开关守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
