#!/bin/sh
# run_probe_signal_guard.sh 的自测：**每一条判据都要能红**。
#
# 为什么必须有这一条：守卫自己失效的形态是"全绿" —— 而全绿与"真的通过了"同形。
# 所以这里为每条判据造一个**桩源码树**，桩**逐字取自本轮修复前的真实旧写法**
# （不是"随便改一处"，那些写法在 main 上真的存在过），逐条跑守卫并期望它变红。
#
# 反向也不能少：桩改回**修复后的写法**必须全绿 —— 否则判据可能只是"讨厌某种写法"，
# 而不是"钉住了正确的结构关系"。
#
# 运行：sh tools/run_probe_signal_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

ok=0; bad=0
chk() { # chk <描述> <期望:red|green> <stub 目录>
    name="$1"; want="$2"; dir="$3"
    if sh tools/run_probe_signal_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

# 桩机制：把仓库里的两份源**临时替换**成桩，跑完守卫再还原（trap 保证异常也还原）。
STASH=/tmp/probe_signal_guard_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$JNI" "$STASH/llama_jni.cpp"
cp "$KT"  "$STASH/LlmEngine.kt"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"; }
trap restore EXIT

run_stub() { # run_stub <name> <expect> <sed 脚本...>
    name="$1"; want="$2"; shift 2
    cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
    for prog in "$@"; do
        printf '%s\n' "$prog" | python3 - "$JNI" "$KT" >/dev/null
    done
    chk "$name" "$want" -
    restore
}

# 用 python 做桩替换（sed 处理多行/缩进太脆）。
stub() { # stub <文件选择 jni|kt> <旧串文件> <新串文件>
    :
}

# ① 基线：当前（修复后）源码必须全绿。
chk "修复后源码全绿（基线）" green -

# ── ② K-1 桩：把幂等闸门删掉（= 旧写法：每次安装无条件覆盖 g_old_*）──
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$JNI" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = """    if (g_signals_installed) {
        jp("[probe] signal handlers 已装过（幂等跳过，保留原 handler 链）\\n");
        return;
    }
"""
assert old in s, "K-1 桩锚点未命中"
s = s.replace(old, "", 1)
s = s.replace("    g_signals_installed = true;\n", "", 1)
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-1 桩：删掉幂等闸门 -> 守卫变红" red -
restore

# ── ③ K-1 桩：闸门在、但 sigaction 排在闸门之前（顺序写反）──
#    桩必须真的让 `&g_old_*` 出现在闸门**之前**才叫"顺序写反"：
#    第一版桩只是把闸门往下挪了一行，`&g_old_` 仍在其后 —— 守卫正确地全绿，
#    是**桩**没打到位（这条是本自测自己抓出来的，不是一开始想到的）。
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$JNI" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
gate = s[s.index("if (g_signals_installed) {"):s.index("\n    }\n", s.index("if (g_signals_installed) {")) + 7]
assert gate, "桩锚点未命中"
s = s.replace(gate, "", 1)
anchor = "    g_signals_installed = true;\n"
assert anchor in s
s = s.replace(anchor, gate + anchor, 1)
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-1 桩：g_old_* 恢复先装后判（顺序写反）-> 守卫变红" red -
restore

# ── ④ K-2 桩：删掉 sigaltstack（= 旧写法：只有 SA_ONSTACK）──
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$JNI" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = """    probe_install_alt_stack();
"""
assert old in s
s = s.replace(old, "", 1)
s = s.replace("sa.sa_flags = SA_SIGINFO | (g_alt_stack_ready ? SA_ONSTACK : 0);",
              "sa.sa_flags = SA_SIGINFO | SA_ONSTACK;", 1)
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-2 桩：退化成只有 SA_ONSTACK（旧写法）-> 守卫变红" red -
restore

# ── ⑤ K-2 桩：备用栈排在 sa_flags 之后（装了个永不生效的栈）──
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$JNI" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = """    probe_install_alt_stack();
    struct sigaction sa;"""
new = """    struct sigaction sa;"""
assert old in s
s = s.replace(old, new, 1)
anchor = "    sa.sa_flags = SA_SIGINFO | (g_alt_stack_ready ? SA_ONSTACK : 0);\n"
assert anchor in s
s = s.replace(anchor, anchor + "    probe_install_alt_stack();\n", 1)
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-2 桩：备用栈排在 sa_flags 之后 -> 守卫变红" red -
restore

# ── ⑥ K-3 桩：SIG_IGN 分支退回旧写法（sa_handler + _exit）──
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$JNI" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = """    } else if (old && old->sa_handler == SIG_IGN) {"""
assert old in s
i = s.index(old)
j = s.index("    }\n", i) + 6
s = s[:i] + """    } else if (old && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
    }
    if (old && old->sa_handler == SIG_IGN) _exit(128 + sig);
""" + s[j:]
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-3 桩：SIG_IGN 退回 _exit 升级（旧写法）-> 守卫变红" red -
restore

# ── ⑦ K-3 桩：递归保护的 _exit 被删（另一侧的底线）──
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$JNI" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = "    if (g_in_handler) _exit(128 + sig);"
assert old in s
s = s.replace(old, "    if (g_in_handler) return;", 1)
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-3 桩：删掉递归保护的 _exit -> 守卫变红" red -
restore

# ── ⑧ K-4 桩：probeGguf 退回只按 path 命中（旧写法）──
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$KT" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = """        val id = probeIdentityOf(path)
        if (probeId == id) return probeVal"""
new = """        val id = probeIdentityOf(path)
        if (probeId?.path == path) return probeVal"""
assert old in s
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-4 桩：probeGguf 退回只按路径命中 -> 守卫变红" red -
restore

# ── ⑨ K-4 桩：peekGguf 与 probeGguf 判据分叉（各自一份近似）──
cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"
python3 - "$KT" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = """        if (probeId == id && probeVal != null) return probeVal"""
new = """        if (probeId?.path == path && probeVal != null) return probeVal"""
assert old in s
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PY
chk "K-4 桩：peekGguf 判据与 probeGguf 分叉 -> 守卫变红" red -
restore

# ── ⑩ 反向：还原后必须全绿（判据不是在"讨厌某种写法"）──
restore
chk "还原后全绿（反向验证）" green -

echo "=== 探针信号链守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
