#!/bin/sh
# 「建索引那一行的进度读数：收发两端是通的」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠 RagProgressTest）
# ═══════════════════════════════════════════════════════════════════════════
# `RagProgress` 的单测只证明"拼得对、边界对"，证明不了**接上了** ——
# 而这一族缺陷的形状恰恰是"纯逻辑全绿、屏幕上什么都没有"。本仓库在
# 这一处**真的栽过**（而且栽了很久，因为没有任何判据在看它）：
#
#   · 收方那份前缀副本多写了"逐条"两个字 —— 发方是 `正在编码：`、
#     收方是 `正在逐条编码：`，两个字符串从第一天起就不相等，于是
#     `startsWith(PROGRESS_PREFIX)` **恒假**：进度从来没走到进度行，
#     每一行都落进"2000 行 ring 全量重排"那条闸（正是 uiProgress 那段
#     注释里"上万行不进 ring"要防的事）。判据恒假 = 整条保护一行都没生效，
#     而它**看起来**一直在。
#   · 开工时刻若用墙钟做差 -> NTP 跳变/用户改时间把"已耗时"拉歪甚至算负；
#   · 切页那一跳若退回静态话 -> 用户报的"建索引提示丢失"原样复现；
#   · 收工不清最后读数 -> 下一次切页带出过期读数（已完成的库像"还在跑"）；
#   · native 与 Kotlin 两份格式化分岔 -> "日志 1:23、界面 1:5"，
#     两者都"没错"，只是说的不是同一件事，而这种漂移没有任何迹象。
#
# 判据一律锚**结构关系**（谁从哪儿取前缀 / 开工取哪口钟 / 哪一处写最后读数 /
# 两份格式化对同一组边界值给出什么），不锚"某某函数存在"。
# 运行：sh tools/run_rag_progress_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
ENG=$SRC/LlmEngine.kt
PROG=$SRC/RagProgress.kt
ETH=app/src/main/cpp/embed_text.h
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "四个源文件都在" "[ -f \$ACT ] && [ -f \$ENG ] && [ -f \$PROG ] && [ -f \$ETH ]"

# ① 接线：前缀唯一 + 单调钟 + 切页带读数 + 收工清读数 + 两份格式化同口径。
#    五件事收在一个 checker 里（它们是同一个契约的五个面），失败时逐条点名。
c "进度读数从发方到收方全程接通，且两处格式化同口径" \
  "$PY tools/rag_progress/check_progress_wiring.py \"\$ACT\" \"\$ENG\" \"\$PROG\" \"\$ETH\""

# ② RagProgress 不许依赖 android / 本仓库其它文件：
#    它要能在**不带 Android 栈**的纯逻辑单测里编过（run_rag_progress_tests.sh），
#    一旦引了 android.*，那条套件就编不过（而它是我们唯一的证据）。
c "RagProgress 不 import android（否则纯逻辑套件编不过）" \
  "! grep -q '^import android' \$PROG"
# ⚠ 必须**剥注释**再查：RagProgress 的说明里刻意提到别的类（解释为什么收
#    "已经算好的毫秒数"），而"注释里提到"不等于"代码里依赖"。
c "RagProgress 不引用本仓库其它类（前缀/格式化都自足）" \
  "! $PY tools/rag_index/strip_comments.py \$PROG | grep -qE 'LlmEngine|RagIndexStore|EngineActivity'"

# ③ 速率不许被拿去推"剩余时间"：那是**假精确**（块与块耗时能差一个数量级，
#    推出来的剩余时间会"看起来精确"而错得没边 —— 本仓库对"量不准却像量准了的
#    假读数"判据很硬，见 embed 那个 `+N MB` 被换掉那件事）。
#    锚的是"状态行那一族文案里出现了『剩余 / 预计还要 / 还差』"——
#    ⚠ 必须**剥注释**再查：RagProgress 的说明里刻意解释"为什么不报剩余时间"
#    （那段注释里就有"剩余"两个字），不剥注释的判据在这里是**恒假** ——
#    那种判据的后果是本条永远红，然后下一个人把它删掉（比没有更坏）。
c "进度行里不报『剩余/预计还要多久』那一族词（那是假精确）" \
  "! $PY tools/rag_index/strip_comments.py \$PROG | grep -qE '剩余|预计还要|还差'"

echo
echo "=== 建索引进度读数守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
