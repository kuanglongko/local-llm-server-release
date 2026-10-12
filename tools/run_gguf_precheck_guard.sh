#!/bin/sh
# 「GGUF 预读必须先过文件头检查」的源码级守卫（Issue #238）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是只靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 单测钉的是**判据本身**（gguf_header_check 该挡的挡、该放的放），
# 但它钉不住一件更致命的事：**真机的调用点有没有真的接上这道判据**。
#
# 这正是本仓库栽过的形态（见 run_mmap_release_guard_tests.sh 的"正确实现被
# 提前 return 变死代码"）：函数在、判据对、单测全绿，而运行期走的是另一支。
# 具体到这一条：gguf_header_check 写好了却没人在 nativeProbeGguf 里调它，
# 单测照样全绿 —— 真机上那场 malloc 风暴一秒都不会少。
#
# 所以判据钉的是**可达性**，不是"字符串出现过"：
#   ① nativeProbeGguf 的函数体里**真的**调了 gguf_header_precheck；
#   ② 那次调用发生在 lm_gguf_init_from_file **之前**（先挡后解析）；
#   ③ 失败分支必须提前 return，不能"打个日志继续往下解析"；
#   ④ Kotlin 侧"读不出"要出声（否则回退是静默的，与"没调预读"同形）。
#
# 运行：sh tools/run_gguf_precheck_guard.sh
set -e
cd "$(dirname "$0")/.."

JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/gguf_precheck.h
TEST=tools/gguf_precheck_test.cpp
RUNNER=tools/run_gguf_precheck_tests.sh
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释行与行内 `//...`：本文件的修复注释里恰好会引用"没接守卫"的旧写法，
# 不剥注释 -> 恒真。与 run_probe_switch_guard.sh 同一套路。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*' ; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# 取 nativeProbeGguf 的函数体（剥注释后）存成文件 —— 后续所有判据都对着这份跑。
# 用文件而不是嵌套 $( )：本守卫的行号判据需要多层管道，直接嵌进 eval 会被
# shell 再展开一次，引号与分析器来回丢（本仓库在 mmap 守卫上真踩过）。
awk '/Java_com_xiaowan_localinference_LlmEngine_nativeProbeGguf/ && /\(/ {inb=1}
     inb {print} inb && /^}/ {exit}' "$JNI" | nocomment > "$TMP/probe_body.txt"

echo "── ① 文件齐备 ────────────────────────────────────────────"
c "gguf_precheck.h 存在" "[ -f \$HDR ]"
c "宿主单测存在" "[ -f \$TEST ]"
c "单测 runner 存在" "[ -f \$RUNNER ]"
c "llama_jni.cpp 里 include 了判据头" "nocomment < \$JNI | grep -q 'gguf_precheck.h'"
c "Kotlin 侧文件存在" "[ -f \$KT ]"
c "函数体抓到了（非空）" "[ -s \$TMP/probe_body.txt ]"

echo "── ② 判据必须**可达**（这是本守卫的重点）─────────────────"
c "nativeProbeGguf 里真的调了 gguf_header_precheck" \
  "grep -q 'gguf_header_precheck' \$TMP/probe_body.txt"
# 判据不能被"短路开关"废掉：`if (false && !gguf_header_precheck(...))` 这种写法
# 里那个名字照样在，绝不该算通过。要求它的**前一个非空字符**是 `!`，
# 即它真的在一个取反否定的条件里。
c "守卫调用没有被短路常量废掉（前面是 !，不是 false &&）" \
  "grep -qE '![[:space:]]*gguf_header_precheck' \$TMP/probe_body.txt"
c "守卫调用所在条件里没有 if (false" \
  "! grep -q 'false && !gguf_header_precheck' \$TMP/probe_body.txt"
c "nativeProbeGguf 里真的调了 lm_gguf_init_from_file" \
  "grep -q 'lm_gguf_init_from_file' \$TMP/probe_body.txt"

# ③ 先挡后解析：guard 的行号必须 < 解析的行号。
#    用行号而不是"谁在谁前面的字符串里"—— 后者会被函数体内的任意重排骗过。
GUARD_LINE=$(grep -n 'gguf_header_precheck' "$TMP/probe_body.txt" | head -1 | cut -d: -f1)
PARSE_LINE=$(grep -n 'lm_gguf_init_from_file' "$TMP/probe_body.txt" | head -1 | cut -d: -f1)
c "守卫出现在解析之前（先挡后解析）" \
  "[ -n \"$GUARD_LINE\" ] && [ -n \"$PARSE_LINE\" ] && [ \"$GUARD_LINE\" -lt \"$PARSE_LINE\" ]"

# ④ 失败分支必须提前 return，不能落回解析。
#    判据：守卫调用所在的那个 `if` 块里出现 `return mk` —— 而不是"往下 3 行内"。
#    按距离锚会在"返回前多拼一个字符串"这种**正确**改动上误红（本改动自己就踩过：
#    先拼 `msg` 再 return，只是把 return 推后了一行）。
#    所以取 `if (!gguf_header_precheck(...)) {` 到配对大括号之间的整块来看。
c "守卫失败时提前 return（不是打完日志继续）" \
  "python3 tools/gguf_precheck_return_check.py \$TMP/probe_body.txt"

echo "── ③ 边界诚实性：不假装能证明文件合法 ────────────────────"
c "判据头只读头部（不含 lm_gguf_init / malloc 调用）" \
  "! nocomment < \$HDR | grep -qE 'lm_gguf_init|malloc|operator new'"
c "判据头里没有断言'合法'性质的注释性 API（不返回 ok=文件一定好）" \
  "grep -q '它\\*\\*不能证明文件一定合法\\*\\*' \$HDR"

echo "── ④ Kotlin 侧：读不出必须出声 ───────────────────────────"
awk '/fun probeGguf/ && /\(/ {inb=1} inb {print} inb && /^    }/ {exit}' "$KT" \
  | nocomment > "$TMP/kt_probe.txt"
c "probeGguf 在读不出时 uiLog（不是静默回退）" \
  "grep -q '预读不可用' \$TMP/kt_probe.txt"

echo
if [ "$bad" -eq 0 ]; then
  echo "全部通过（$ok 条）"
else
  echo "$bad 条失败（通过 $ok 条）"
  exit 1
fi
