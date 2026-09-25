#!/bin/sh
# 「`llama_jni.cpp` 本体真的过一遍编译器」+「每个 static 函数的首次使用不早于其声明/定义」（模块 S）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 0.9.86 栽过一次，0.9.120 之后又栽了一次同一种：**C++ 的「先用后定义」**。
# 上一轮 mmap 系列（M/M2/M3）新加的 `classify_mmap_devices` 从文件下方 500 行处的
# `split_desc_append()` 取格式化能力（当时的 M4 的 `pin_devices_to_cpu` 同形态；
# 而它的**定义**在调用点之后，中间没有任何前置声明 ——
# `error: 'split_desc_append' was not declared in this scope`，直接编不过。
#
# 而**整套判据网一条都没拦住**，因为：
#   · CI 里那族守卫全是静态 grep，判的是"某个标识符/结构有没有出现"，
#     "定义在调用点之后"在文本上**完全看不出来**；
#   · 唯一带 NDK 的编 APK 流水线自 09-21 后没在这个 sha 上跑过；
#   · 需要 g++ 的宿主单测按契约 SKIP（Runner 无编译器）。
# 于是"源码能不能编过"成了判据网的盲区，一路绿到有人手工编 APK 才暴露。
#
# ═══════════════════════════════════════════════════════════════════════════
# 做法：不再"抽取片段 + 自造桩"，而是**编真源码本体**
# ═══════════════════════════════════════════════════════════════════════════
# 仓库里已有 `tools/run_jni_schema_syntax.sh`（0.9.86 那次事故的产物），
# 它从源码里**逐字抽取**几个函数体、用最小桩顶替依赖再编。那一层的两个固有代价：
#   · 只覆盖**被抽到的那几个函数** —— 本轮坏掉的 `classify_mmap_devices`
#     （以及当时的 `pin_devices_to_cpu`）都不在里面，所以它全绿而源码编不过；
#   · 抽取脚本"知道源码形状"，函数一改名/换签名就自己失效。
# 所以本守卫走另一条：**对着真文件跑编译器**。宿主上编不过 `llama_jni.cpp` 的
# 唯一原因是缺 NDK 的头（jni.h / android/log.h），补两份**只声明不定义**的存根
# （`tools/stub_jni/jni.h` 面积严格等于源码真正用到的成员、`tools/stub_sys/android/log.h`），
# 就能让**整个 TU** 过一遍语义分析。它挡住的是：
#   · 先声明后使用（本轮的真因）；
#   · 签名与前置声明不一致；
#   · 函数体内语法错 / 类型错 / 未声明标识符；
#   · 改了一个函数而漏改另一处调用点。
#
# ⚠ `-fsyntax-only` 不做代码生成、不链接、不管模板实例化 —— 它挡不住的写在
#   HTP-STATUS 里，别当成已覆盖（真机编 APK 才是唯一终审）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「归属关系」，不锚「出现过某个字符串」
# ═══════════════════════════════════════════════════════════════════════════
# 这一族最可能的假绿是"守卫自己恒真"：
#   · `-fsyntax-only` 因为编译清单写错、路径不存在而**根本编不到**
#     （编译器没被调用、退出码被 `|| true` 吃掉）；
#   · 逐条比对"首次使用 vs 声明行号"的 python 段解析正则写歪，一个函数都没比。
# 所以：
#   ② 要求编译器**真的跑过且有输出**（把真源码里的一个 `static` 函数名打进去，
#      形成「引用未定义标识符」必然变红 —— 用它反证"编译器真的在看这个文件"）；
#   ③ 要求"比对过的函数数 ≥ 一个下限"，并**逐条验桩**：坏形态必须红。
#
# 运行：sh tools/run_static_order_guard.sh
set -e
cd "$(dirname "$0")/.."

JNI=app/src/main/cpp/llama_jni.cpp
STUB_JNI=tools/stub_jni
STUB_SYS=tools/stub_sys
PY=${PYTHON:-python3}

# ⚠ 编译器不能写死成 `g++`：本文件 include 的 `chat_abi.h` 有一条**按设计**的护栏 ——
#   必须用 libc++ 编译（`librnllama*.so` 里全是 `NSt6__ndk1`，用 libstdc++ 编出来的
#   `St7__cxx11` 符号与它不匹配）。`g++` 在 Debian 上默认走 libstdc++，一编就撞护栏，
#   得到两条"本文件必须用 libc++"的 `#error` —— 那是护栏在工作，不是源码有问题。
#   所以这里**只认 `-stdlib=libc++` 能编过的那把编译器**：依次试候选，第一个
#   能把本文件编过的就用它。全都编不过时报清楚的错（而不是假红一堆护栏噪音）。
CXX_CANDIDATES=${CXX:-clang++ clang++-14 clang++-15 clang++-16 clang++-17 clang++-18 g++ g++-12}

# 试编：用候选编译器 + 同一套 include 编一个**空 TU**（只 include chat_abi.h），
# 只为确认"libc++ 这条路在这台机器上通不通"。空 TU 编过 = 护栏 1 能过。
try_cxx() {
    _c="$1"
    command -v "$_c" >/dev/null 2>&1 || return 1
    echo '#include "chat_abi.h"' \
        | "$_c" -std=c++17 -fsyntax-only -stdlib=libc++ \
              -I app/src/main/cpp/include -I app/src/main/cpp/chat/include -I app/src/main/cpp \
              -x c++ - >/dev/null 2>&1
}
CXX=""
for _c in $CXX_CANDIDATES; do
    if try_cxx "$_c"; then CXX="$_c"; break; fi
done

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）—— 与既有脚本同一契约。
# ⚠ `.cnb.yml` 里**不带** REQUIRED=0：本仓库栽过"写了 REQUIRED=0 而脚本不读它"
#   （CI 接线守卫判据⑤ 专门守这条）。这里读它，是为了让本脚本在别的宿主上也能
#   优雅退化；CI 里缺编译器就当失败（编不过 native 是**必须**拦下的事）。
missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then echo "SKIP  缺少 $1（REQUIRED=0，允许退化）"; exit 0; fi
    echo "缺少 $1"; exit 2
}
[ -n "$CXX" ] || missing "支持 libc++（-stdlib=libc++）的宿主 C++ 编译器（候选：$CXX_CANDIDATES）"
command -v "$PY"  >/dev/null 2>&1 || missing "python3"
[ -f "$JNI" ] || missing "$JNI"

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# ── ① 真源码本体过一遍编译器（语义分析，不做代码生成）──────────────────────
# -stdlib=libc++：chat_abi.h 的护栏 1 要求 libc++（与 librnllama*.so 同 ABI），
#   用 libstdc++ 会让它按设计 `#error` —— 那是护栏在工作，不是源码有问题。
#   Runner 上装 `libc++-dev`；未装时这一条会红，属于"环境缺件"该报出来。
compile_real() {
    "$CXX" -std=c++17 -fsyntax-only -stdlib=libc++ \
        -Wall -Wextra -Wno-unused-function -Wno-invalid-offsetof \
        -I "$STUB_JNI" -I "$STUB_SYS" \
        -I app/src/main/cpp/include -I app/src/main/cpp/chat/include -I app/src/main/cpp \
        -x c++ "$JNI" 2>&1
}

REAL_OUT="$TMP/real.txt"
if compile_real > "$REAL_OUT" 2>&1; then REAL_RC=0; else REAL_RC=$?; fi
c "llama_jni.cpp 本体通过宿主侧 -fsyntax-only（先声明后使用 / 签名一致 / 语法类型）" \
  "[ \"\$REAL_RC\" = 0 ]"
[ "$REAL_RC" = 0 ] || { echo "----- 编译器输出（头 40 行）-----"; head -40 "$REAL_OUT"; }

# ── ② 反证"编译器真的在看这个文件"：往里塞一个不存在的标识符，必须变红 ────────
# 防的假绿是"编译清单写歪 / 路径不存在 / 退出码被吃掉" —— 那一形态下 ① 恒绿。
# 桩①（自测）打的正是这里。
PROBE="$TMP/probe.cpp"
"$PY" - "$JNI" "$PROBE" <<'PYEOF'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
# 在第一个函数定义前插一行引用一个**必然不存在**的标识符。
i = src.index('static int  g_probe_fd  = -1;')
open(sys.argv[2], 'w', encoding='utf-8').write(
    src[:i] + 'static void static_order_guard_probe(void) { __static_order_guard_missing_symbol(); }\n' + src[i:])
PYEOF
PROBE_OUT="$TMP/probe.out"
if "$CXX" -std=c++17 -fsyntax-only -stdlib=libc++ \
      -I "$STUB_JNI" -I "$STUB_SYS" \
      -I app/src/main/cpp/include -I app/src/main/cpp/chat/include -I app/src/main/cpp \
      -x c++ "$PROBE" > "$PROBE_OUT" 2>&1; then PROBE_RC=0; else PROBE_RC=1; fi
c "反证：塞入不存在的标识符后编译器真的报错（防编译清单写歪而恒绿）" \
  "[ \"\$PROBE_RC\" != 0 ] && grep -q '__static_order_guard_missing_symbol' \$PROBE_OUT"

# ── ③ 逐条比对「首次使用」与「声明/定义」的行号 ───────────────────────────
# 编译器 ① 已经能从"能不能编过"这一面判同一件事，这一条是**独立的第二判据**：
# 它能把"哪个函数、第几行"点出来，而不是只给一句 was not declared。
# 为什么两条都要：① 依赖整套 include 环境都对（任一环缺件就红/绿错方向），
# ③ 只做行号比较、不依赖任何工具链 —— 它们失效的条件不同，互为对照。
# 判据只看**文件级 static 函数**（本文件里的模块工具函数全是这一形态）：
#   · 声明形式：`static <ret> name(` 且当行以 `;` 收尾；
#   · 定义形式：`static <ret> name(` 且后面出现 `) {`（同一处的窗口内）；
#   · 首次使用：先剥注释、再剥字符串字面量后的 `name(`。
# 剥字符串是必须的：日志文案里会出现函数名，不剥会把"只在日志里提到"算成"使用"。
ORDER_OUT="$TMP/order.txt"
"$PY" - "$JNI" > "$ORDER_OUT" <<'PYEOF'
import io, re, sys

src = io.open(sys.argv[1], encoding='utf-8').read()
lines = src.split('\n')

def strip_noise(text):
    """剥注释、再剥字符串字面量 —— 两处都会出现函数名，不剥就算成「使用」。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '/' and i + 1 < n and text[i+1] == '/':
            while i < n and text[i] != '\n':
                i += 1
        elif ch == '/' and i + 1 < n and text[i+1] == '*':
            i += 2
            while i + 1 < n and not (text[i] == '*' and text[i+1] == '/'):
                i += 1
            i += 2
        elif ch == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == '\\' else 1
            i += 1
            out.append('""')
        elif ch == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == '\\' else 1
            i += 1
            out.append("''")
        else:
            out.append(ch)
            i += 1
    return ''.join(out)

clean = strip_noise(src)
clean_lines = clean.split('\n')

# 收集文件级 static 函数的「声明」与「定义」位置。
# 形状：行首（允许缩进 0）为 `static <...> name(`。排除变量与类型。
DEF_RE = re.compile(r'^\s*static\s+(?:[A-Za-z_][A-Za-z0-9_:<>,\s\*&]*?)\b([a-z_][A-Za-z0-9_]*)\s*\(')
decl = {}
deffn = {}
for idx, line in enumerate(clean_lines):
    m = DEF_RE.match(line)
    if not m:
        continue
    name = m.group(1)
    # 排除 `static const char * const x[] = {` 这类（第 1 组会匹配成 x）
    window = '\n'.join(clean_lines[idx:idx+3])
    if ') {' in window.replace('(', '('):
        pass
    is_decl = line.rstrip().endswith(');')
    is_def  = (') {' in line) or re.search(r'\)\s*\{', line) is not None
    if is_def:
        deffn.setdefault(name, idx + 1)
    elif is_decl:
        decl.setdefault(name, idx + 1)
    else:
        # 多行签名：往后再看几行找 `) {` 还是 `);`
        for k in range(idx + 1, min(idx + 12, len(clean_lines))):
            s = clean_lines[k]
            if re.search(r'\)\s*\{', s):
                deffn.setdefault(name, idx + 1); break
            if s.rstrip().endswith(');'):
                decl.setdefault(name, idx + 1); break

# 每个 static 函数的「首次使用」：剥噪音后，`name(` 出现在既非声明也非定义处的首行。
# 定义那一行本身会命中 `name(`，所以要排除"该行的行号 == 该函数的声明/定义行号"。
first_use = {}
for idx, line in enumerate(clean_lines):
    for m in re.finditer(r'\b([a-z_][A-Za-z0-9_]*)\s*\(', line):
        name = m.group(1)
        if name not in deffn and name not in decl:
            continue
        if idx + 1 in (deffn.get(name), decl.get(name)):
            continue
        first_use.setdefault(name, idx + 1)

bad = []
checked = 0
for name, dline in sorted(deffn.items()):
    use = first_use.get(name)
    if use is None:
        continue          # 只定义不使用：无「先用后定义」可言
    # 口径：**每个「既定义又被使用」的 static 函数**都逐条比一次 ——
    # 只有"使用点在定义之前"的那批才构成违规，但比对的次数应当是**全部**，
    # 否则 CHECKED 只统计"已经违规的"，判据③ 的"防恒真下限"就没意义了。
    told = decl.get(name)
    checked += 1
    if use < dline and (told is None or told > use):
        bad.append("%s:定义@%d 首次使用@%d 声明@%s" % (name, dline, use, told or "无"))

print("DEFS", len(deffn))
print("CHECKED", checked)
print("BAD", len(bad), *bad)
PYEOF
ORDER_DEFS=$(awk '$1=="DEFS" {print $2}' "$ORDER_OUT")
ORDER_CHECKED=$(awk '$1=="CHECKED" {print $2}' "$ORDER_OUT")
ORDER_BAD=$(awk '$1=="BAD" {print $2}' "$ORDER_OUT")
ORDER_LIST=$(awk '$1=="BAD" {for (i=3; i<=NF; i++) printf "%s%s", (i>3?" ":""), $i; print ""}' "$ORDER_OUT")

c "每个 static 函数的首次使用都不早于其定义或前置声明（违规：${ORDER_LIST:-无}）" \
  "[ \"\${ORDER_BAD:-1}\" = 0 ]"
# 防上面那条恒真：解析正则写歪 -> 一个函数都没比 -> CHECKED=0 -> 恒绿。
c "确实逐条比对过 35+ 个 static 函数（防上一条恒真，实比 ${ORDER_CHECKED:-0} / 定义 ${ORDER_DEFS:-0}）" \
  "[ \"\${ORDER_CHECKED:-0}\" -ge 35 ]"

# ── ④ 存根面积本身就是契约：不许为了让守卫变绿而删掉检查 ─────────────────────
c "存根按真 jni.h 的形状写（jobject/jstring/jclass 同源不透明类型）" \
  "grep -q 'typedef _jobject \\* jstring' \$STUB_JNI/jni.h && \
   grep -q 'typedef _jobject \\* jobject' \$STUB_JNI/jni.h"

c "android/log.h 存根只声明不定义（本守卫只做 -fsyntax-only）" \
  "grep -q 'int __android_log_print(' \$STUB_SYS/android/log.h && \
   ! grep -qE '^(static )?inline int __android_log_print.*\\{' \$STUB_SYS/android/log.h"

if [ "$bad" -eq 0 ]; then
    echo "=== 静态顺序守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 静态顺序守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
