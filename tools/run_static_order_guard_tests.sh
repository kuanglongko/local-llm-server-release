#!/bin/sh
# run_static_order_guard.sh 的自测：**每条判据都要能红**，且红在真的坏形态上。
#
# 为什么必须有这一条：守卫失效的形态是"全绿"，而"全绿"与"真的通过了"同形。
# 本模块的假绿有两个入口，下面分别打：
#   · **编译器根本没编到**（清单写歪 / 路径不存在 / 退出码被吃掉）—— 桩①；
#   · **逐条比对那段一个函数都没比**（解析正则写歪）—— 桩⑤。
# 其余桩取自本仓库真发生过的形态：
#   · 新函数调用了定义在下方的函数、且**没有前置声明**（0.9.120 之后的真因）—— 桩②；
#   · 前置声明写了但**签名与定义不一致**（编译器能从重载/类型两面判出来）—— 桩③；
#   · 前置声明**位置也不对**（写在使用点之后，等于没写）—— 桩④；
#   · 把 `static` 函数名只写在**日志文案**里（剥字符串的判据必须不把它算成"使用"）—— 桩⑥。
#
# 运行：sh tools/run_static_order_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
# 与守卫同一口径：编译器由守卫自己挑（必须能过 libc++）。
# 自测里只在把 CXX 显式传下去时有意义 —— 默认留空，让守卫用它的候选表。
CXX=${CXX:-}

ok=0; bad=0
chk() {
    name="$1"; want="$2"
    if CXX="$CXX" sh tools/run_static_order_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=/tmp/static_order_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$JNI" "$STASH/llama_jni.cpp"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; }
trap restore EXIT



stub() {
    name="$1"; want="$2"; prog="$3"
    cp "$STASH/llama_jni.cpp" "$JNI"
    printf '%s\n' "$prog" | python3 - "$JNI"
    chk "$name" "$want"
}

# ① 编译器根本没编到（把真文件路径换成一个不存在的）：必须红。
# 这是"守卫自己恒真"的第一入口 —— 清单写歪、路径写错、CI 里那句被 `|| true` 吃掉。
stub "编译清单写歪 / 文件不在（守卫不该恒绿）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "static int  g_probe_fd  = -1;"
assert old in s
# 制造一个**必然编不过**的语法错：少一个分号 + 未声明标识符。
s = s.replace(old, old + "\nstatic void guard_probe_broken(void) { __no_such_symbol_here(); }", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ② 本轮真因：新函数调用下方定义的函数（保留前置声明）。先删掉前置声明即还原坏形态。
stub "新函数调用下方定义、且无前置声明（0.9.120 之后的真因）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "static void split_desc_append(char * out, size_t cap, int * w, const char * fmt, ...);\n"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ③ 前置声明写了但签名与定义不一致（编译器从类型面判出来）。
stub "前置声明与定义签名不一致（改一处忘改另一处）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "static void split_desc_append(char * out, size_t cap, int * w, const char * fmt, ...);"
assert old in s, "源码形状变了，本自测要一起更新"
# 把声明里的 `int * w` 改成 `int w`：调用点按 `&w` 传参，必然编不过。
new = "static void split_desc_append(char * out, size_t cap, int w, const char * fmt, ...);"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ④ 前置声明**位置写在使用点之后** —— 等于没写，只是让"文件里有这句"变真。
stub "前置声明挪到使用点之后（等于没写）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
decl = "static void split_desc_append(char * out, size_t cap, int * w, const char * fmt, ...);\n"
assert decl in s, "源码形状变了，本自测要一起更新"
s = s.replace(decl, "", 1)
# 挪到 `classify_mmap_devices` 定义之后（使用点在它之前）。
anchor = "    return n_take;\n}\n"
i = s.index("static int classify_mmap_devices(char * out, size_t cap) {")
j = s.index(anchor, i) + len(anchor)
s = s[:j] + decl + s[j:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑤ 逐条比对那段一个函数都没比（判据④ 的"防恒真"下限必须能红）。
# 形态：把 DEF_RE 写歪（要求 `static` 后至少 3 个空格），于是 deffn 恒空 ->
# CHECKED=0 -> 判据③ 恒绿、判据④ 必须把它逮住。
# 打的是**守卫脚本自身**，所以不经过 `stub()`（那个只改 JNI）；这里单独处理，
# 并显式备份/还原守卫脚本。
GUARD=tools/run_static_order_guard.sh
cp "$GUARD" "$STASH/guard.sh"
restore_guard() { cp "$STASH/guard.sh" "$GUARD"; }
trap 'restore; restore_guard' EXIT
python3 - "$GUARD" <<'PYEOF'
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "re.compile(r'^\\s*static\\s+"
assert old in s, "守卫脚本形状变了，本自测要一起更新"
s = s.replace(old, "re.compile(r'^\\s*static\\s{3,}", 1)
io.open(p, "w", encoding="utf-8").write(s)
PYEOF
cp "$STASH/llama_jni.cpp" "$JNI"
chk "比对正则写歪、一个函数都没比（判据④ 必须逮住）" red
restore_guard

# ⑥ 把函数名只写进**日志文案**（剥字符串的判据不该把它算成"使用"）。
# 形态：删掉一处**真实的**前置声明，但在它档位的日志里提到这个名字 —— 必须仍红。
stub "只在日志文案里提到函数名（剥字符串必须仍然红）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
decl = "static void split_desc_append(char * out, size_t cap, int * w, const char * fmt, ...);\n"
assert decl in s
s = s.replace(decl, "", 1)
# 在使用点之前的日志里写上名字（不是调用），判据不许把它当"使用"。
anchor = "    jlog(\"[mmap诊断]"
i = s.index(anchor)
s = s[:i] + "    jlog(\"split_desc_append 只是被提到，不是调用\");\n" + s[i:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦ 反向：真源码（本轮修复后的写法）必须全绿
stub "修复后的写法（反向对照）" green '
import sys
'

if [ "$bad" -eq 0 ]; then
    echo "=== 静态顺序守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 静态顺序守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
