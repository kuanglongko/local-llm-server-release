#!/bin/sh
# 「会话裁剪保险丝」的接线守卫（S1）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠行为测试不够
# ═══════════════════════════════════════════════════════════════════════════
# `tools/run_session_cap_tests.sh` 证明的是"**这些输入**得到**这些输出**"，
# 它证明不了"仓库里那一处**结构**没有改回去"。本批有三条零症状的坏法：
#
#   ① **顺手啃掉本轮提问**。上一版从最新往前截、`substring(0, len - cut)` 保头，
#      超限时先把当轮提问的尾部截掉。用户的症状是"我贴的代码它只看到一半，
#      还答得头头是道" —— 与模型抽风完全同形，无法归因。
#   ② **截到空条就停**（`minOf(over, current.length)` 不 continue 的写法）。
#      空的历史条让 cut=0，循环看起来"继续"，实际 `over` 一点没减 ——
#      静默少截，把超限留给下一轮，而下一轮的末条已经换人了。
#   ③ **条数上限退化回裸魔数**。现在注释写着"40 条 = 20 轮"，而代码里没有 "20"；
#      谁把 40 改成 60 都不知道它代表轮数，注释拦不住他。
#
# 判据是"**循环下标不含末条**"、"**丢历史与截历史是两级且分开报**"、
# "**常量具名且换算关系同处**" —— 不是"某个函数存在"（后者在定义处就恒真）。
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_session_cap_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
SS=$SRC/SessionStore.kt
TST=tools/session_cap/SessionCapTest.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（与 run_chat_regen_guard.sh / run_ui_io_guard.sh
# 的 body() 同一判据；三处各写一份取函数体的近似就是"判据漂移"的起点）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }

# 剥注释（判据文本在注释里也出现，不剥会得到恒真的假绿）
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

# 在某个函数体里取两个锚点出现的**行序**：前锚必须在后锚之前。
# 顺序类不变量的唯一有效判据 —— "两行都在"对上"位置换了"是恒真的。
before_in() { # 变量名 前锚正则 后锚正则 文件
    eval "_src=\$$1"
    printf '%s' "$_src" | nocomment | awk -v a="$2" -v b="$3" '
        !pa && $0 ~ a { pa=NR }
        !pb && $0 ~ b { pb=NR }
        END { exit !(pa && pb && pa < pb) }'
}

c "SessionStore.kt 存在" "[ -f \$SS ]"

# ── 1) 条数上限：具名常量，且换算关系与常量同处 ────────────────────────────
c "条数上限是具名常量 MAX_MSGS = 40（不再散落裸魔数）" \
  "grep -q 'const val MAX_MSGS = 40' \$SS"
c "「40 条 = 20 轮」的换算关系钉在常量旁边（代码里没有 20）" \
  "grep -q '40 条 = 20 轮' \$SS"
AP=$(body 'private fun append\(ctx: Context, id: String, msg: Msg\)' "$SS")
c "append() 存在" "[ -n \"\$AP\" ]"
# ⚠ 本轮的形态改动：条数保险丝从 append **内联**收成了共享的 [trimToCap]。
#   原来这里钉的是"append 的函数体里有 `while (msgs.size > MAX_MSGS) ...`"，
#   那在"导入绕过保险丝"（B1）被修掉之后必然失效 —— 因为保险丝现在只在
#   [trimToCap] 里有一份，append 与 writeImported 都**调用**它。
#   判据相应改成"保险丝在 trimToCap 里、且两个入口都过它"：这比原来更强 ——
#   它同时钉住了"导入这条路不能绕过保险丝"（B1 的坏法）。
TC=$(body 'private fun trimToCap\(ctx: Context, id: String, msgs: MutableList<JSONObject>\)' "$SS")
c "条数保险丝落在 trimToCap 里（具名常量 MAX_MSGS，不是裸 40）" \
  "[ -n \"\$TC\" ] && printf '%s' \"\$TC\" | nocomment | grep -q 'while (msgs.size > MAX_MSGS) msgs.removeAt(0)'"
c "trimToCap 里没有「msgs.size > 40」的裸魔数" \
  "! printf '%s' \"\$TC\" | nocomment | grep -qF -e 'msgs.size > 40'"
c "trimToCap 也调 capChars（字符保险丝与条数保险丝在同一处收口）" \
  "printf '%s' \"\$TC\" | nocomment | grep -q 'capChars(ctx, id, msgs)'"
# 判据是"两个**入口**都过 trimToCap"，不是"trimToCap 存在" —— 后者对
# "append 过了、writeImported 没过"（正是 B1 的形态）是恒真的。
c "append 这条入口过 trimToCap" \
  "printf '%s' \"\$AP\" | nocomment | grep -q 'trimToCap(ctx, id, msgs)'"
# ── 1b) 标题截断必须按**代理对安全**（ISSUE #326 的 A4）────────────────────
# 裸 `take(20)` 在首条消息第 20/21 个 code unit 恰好落在 emoji 中间时会切出
# 一个孤立高代理，落盘/渲染时被替换成 `?`（用户看到标题末尾一个怪符号）。
# 同一族纪律在 ChatArchive/RagArchive 的截断处已经立了（clipAtCharBoundary），
# 标题这里跟上 —— 两处（maybeAutoTitle / migrateIfNeeded）都要。
# 判据锚**函数体**（用同一份 body() 取函数体），不锚"文件里出现过 clipAtCharBoundary"
# —— 后者在别处（ChatArchive 等）出现就恒真。
MAT=$(body 'fun maybeAutoTitle\(ctx: Context, id: String, firstUser: String\)' "$SS")
c "maybeAutoTitle 的标题截断走 clipAtCharBoundary（不是裸 take(20)）" \
  "[ -n \"\$MAT\" ] && printf '%s' \"\$MAT\" | nocomment | grep -q 'clipAtCharBoundary' && \
   ! printf '%s' \"\$MAT\" | nocomment | grep -qF -e '.take(20)'"
MG=$(body 'fun migrateIfNeeded\(ctx: Context\): MigrateResult' "$SS")
c "migrateIfNeeded 的标题截断也走 clipAtCharBoundary" \
  "[ -n \"\$MG\" ] && printf '%s' \"\$MG\" | nocomment | grep -q 'clipAtCharBoundary' && \
   ! printf '%s' \"\$MG\" | nocomment | grep -qF -e '.take(20)'"
WI=$(body 'private fun writeImported\(ctx: Context, s: ChatArchive.Session, title: String\): String\?' "$SS")
c "writeImported() 存在" "[ -n \"\$WI\" ]"
c "导入这条入口也过 trimToCap（落盘前裁剪，不绕过保险丝）" \
  "printf '%s' \"\$WI\" | nocomment | grep -q 'trimToCap(ctx, id, msgs)'"
c "导入不再原样落盘 s.messages（既不裁剪也没有第二份裁剪）" \
  "! printf '%s' \"\$WI\" | nocomment | grep -qF -e 'for (m in s.messages)'"

# ── 2) 字符上限：两级分开，且**截历史绝不碰末条** ─────────────────────────
CC=$(body 'private fun capChars\(ctx: Context, id: String, msgs: MutableList<JSONObject>\)' "$SS")
c "capChars() 存在（字符保险丝的唯一落点）" "[ -n \"\$CC\" ]"
c "一级仍是「先丢最早的历史，丢到 MIN_MSGS 为止」" \
  "printf '%s' \"\$CC\" | nocomment | grep -q 'while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)'"
# 反着来：从最新往前截的循环（`msgs.indices.reversed()`）就是上一版"啃本轮提问"的形状。
c "截断循环**不含末条**（从 last-1 开始往前），不是从最新一条起" \
  "printf '%s' \"\$CC\" | nocomment | grep -q 'for (i in last - 1 downTo 0)' && \
   ! printf '%s' \"\$CC\" | nocomment | grep -q 'msgs.indices.reversed()'"
c "截断前先取末条下标并排除它" \
  "printf '%s' \"\$CC\" | nocomment | grep -q 'val last = msgs.lastIndex' && \
   printf '%s' \"\$CC\" | nocomment | grep -q 'if (at !in 0 until last) continue'"
# 空条目必须 continue 而不是被 minOf 顺手越过 —— 后者静默少截（坏法 ②）。
c "空历史条（cut <= 0）显式 continue，不靠 minOf 顺手越过" \
  "printf '%s' \"\$CC\" | nocomment | grep -q 'if (cut <= 0) continue'"
# 判据是**行序**（记账必须在"空条/无字节可截"的 continue **之后**），
# 不是"两个片段都在" —— 后者对"把记账挪到 continue 之前"是恒真的。
c "条目挪位的记账只在 cut 真的发生后累加" \
  "before_in CC 'if \\(cut <= 0\\) continue' 'removals\\[at\\]\\+\\+' \$SS"
c "末条被截的旧形状（对 msgs[i] 无条件 put 截短内容）已绝迹" \
  "! printf '%s' \"\$CC\" | nocomment | grep -q 'msgs\[i\].put(\"content\", cur.substring(0, cur.length - cut))'"

# ── 3) 留痕：两级分开报，且末条超限时单独说明 ─────────────────────────────
c "丢掉的历史条数被单独计数" \
  "printf '%s' \"\$CC\" | grep -q 'val droppedMsgs = count0 - msgs.size'"
c "截断的条数与字符数被单独计数" \
  "printf '%s' \"\$CC\" | grep -q 'var cutChars = 0' && \
   printf '%s' \"\$CC\" | nocomment | grep -q 'cutChars += cut'"
c "留痕里明说末条一字未动（否则用户分不清是谁被截了）" \
  "printf '%s' \"\$CC\" | grep -q '末条一字未动'"
c "末条自身超限时把超出量报出来，而不是假装已落回上限" \
  "printf '%s' \"\$CC\" | grep -q '末条自身仍超限'"
c "不超限时不写日志（保险丝不得误伤）" \
  "printf '%s' \"\$CC\" | nocomment | grep -q 'if (droppedMsgs > 0) {'"

# ── 4) 行为测试与实现不得脱钩 ────────────────────────────────────────────
# 只测自己复刻的那一份是假证据：两边必须指向同一批结构。
c "行为测试存在（结构没改回去 ≠ 边界输入下真的对）" "[ -f \$TST ]"
c "行为测试复刻的截断循环与实现同形" \
  "grep -q 'for (i in last - 1 downTo 0)' \$TST && grep -q 'if (at !in 0 until last) continue' \$TST && \
   printf '%s' \"\$CC\" | nocomment | grep -q 'for (i in last - 1 downTo 0)' && \
   ! grep -q 'i in msgs.lastIndex downTo 0' \$TST"
# 判据不是"出现过这几个字"，而是"那条断言比的是**整串相等**"：
# 退化成 `isNotEmpty()` 时名字还在、断言还在，只是它再也证明不了什么。
c "行为测试钉住「末条逐字节不变」这条核心不变量（判据是整串相等，不是非空）" \
  "grep -q '末条逐字节不变' \$TST && grep -q 'now.last().second == long' \$TST && \
   ! grep -q 'now.last().second.isNotEmpty()' \$TST"
c "行为测试钉住「末条自身超限时整体保留」" \
  "grep -q '末条自身超限时整体保留' \$TST"
c "行为测试保留了上一版写法的对照（否则证明不了修复有效）" \
  "grep -q '对照：旧写法把当轮提问（末条）啃短了' \$TST"
# ⚠ 这一条**曾经守着一个不可达的形态**，必须说清为什么改成现在这样。
#
# 旧判据是 `grep -q '遇到空的历史条不停止，继续往前截'` —— 它只锚用例的**名字**，
# 而那条用例在当时是**恒真的**：它断言"截到空条还要继续"，可一级的终止条件是
# `size > MIN_MSGS(2) && total > MAX_CHARS` —— 只要二级还能进，库里就只剩 2 条
# （穷举 3~5 条 × 长度 {0,1,100,5000,12000,20000,30000} 共 19551 例，
# 「二级面对 >2 条」为 0 例）。所以 `minOf(over, 0) -> continue` 那条分支
# **一次都没被执行过**，用例绿只是因为它的断言恰好也成立（总长确实落回了上限）。
#
# 现在判据分两层，各守各的：
#   · 这一层：`continue` 在**复刻代码**里还在（结构保险 —— MIN_MSGS 一旦改成 3，
#     它立刻变成活判据）；
#   · 下一层：用例必须**如实描述**它真正跑到的形态（"二级只面对两条"），
#     不许再用一个没发生的名字冒充证据。
c "复刻代码里保留空条 continue（MIN_MSGS 若放宽，它立刻变成活判据）" \
  "grep -q 'if (cut <= 0) continue' \$TST"
c "用例如实钉住「二级只面对两条（一级丢到 MIN_MSGS 就停手）」这条真不变式" \
  "grep -q '一级丢到剩两条就停手（MIN_MSGS 保底）' \$TST && \
   grep -q '二级的\*\*真实形态\*\*' \$TST"
c "用例里不再有「截到空条还要继续」这种没被执行的断言（假证据）" \
  "! grep -q '遇到空的历史条不停止，继续往前截' \$TST"

echo
echo "=== 会话裁剪守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
