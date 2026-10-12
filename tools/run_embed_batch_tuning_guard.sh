#!/bin/sh
# 「嵌入合批的读数与批大小设置**真的接上了**」的源码级守卫（ISSUE #269 第 1+2 步）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠两个纯逻辑单测）
# ═══════════════════════════════════════════════════════════════════════════
# 第 1 步（读数）与第 2 步（可调）各自的纯逻辑单元都能全绿，而它们要修的
# **恰恰是"纯逻辑全绿、界面上什么都没有"这一类**：
#
#   · `EmbedBatchStats` 算得对，但建库循环里没人调 record -> 读数永远是空态；
#   · `/health` 报了字段，但 `HealthCheck.parse` 没解析 -> 报告里读不到；
#   · 设置页有输入框、能存盘，但 `buildIndex` 没读它 -> 与当初那个
#     "线程数输入框对建库完全无效"一模一样：能填、能存、就是没用；
#   · 批大小没进指纹 -> 用户改了它、旧索引仍被当成"可用"，
#     而新旧向量一旦不逐位相同，检索就是"用一种语义命中、注入另一种语义的正文"。
#
# **单测钉不住它们**：`EmbedBatchStats` 的单测喂什么批大小就跑什么批大小，
# "界面上那个框到底接没接上"、"/health 报没报"它看不见。
# 所以判据必须锚源码结构关系。
#
# 运行：sh tools/run_embed_batch_tuning_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
HEALTH=app/src/main/java/com/xiaowan/localinference/HealthCheck.kt
STATS=app/src/main/java/com/xiaowan/localinference/EmbedBatchStats.kt
PREF=app/src/main/java/com/xiaowan/localinference/EmbedBatchPref.kt
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
HDR=app/src/main/cpp/rag_index.h

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "八个源文件都在" \
  "[ -f \$ENGINE ] && [ -f \$ACT ] && [ -f \$HTTP ] && [ -f \$HEALTH ] && [ -f \$STATS ] && [ -f \$PREF ] && [ -f \$STORE ] && [ -f \$HDR ]"

# ① 全链判据收在一个 checker 里（它们是同一个契约的几个面），失败时逐条点名。
c "合批读数（建库->/health->探活解析）与批大小（设置->建库->指纹）全程接通" \
  "$PY tools/embed_batch/check_batch_tuning_wiring.py \"\$ENGINE\" \"\$ACT\" \"\$HTTP\" \"\$HEALTH\" \"\$STATS\" \"\$PREF\" \"\$STORE\" \"\$HDR\""

# ② 读数层与决策层**必须分开**：`EmbedBatchStats.record` 只累加、不返回值，
#    而 `EmbedBatchStats` 里**不许**出现 `BatchEmbed` / `embed(` 这类决策符号 ——
#    让它去决定"要不要回落"就是把"观测"与"决策"焊在一起，
#    那时改一个是改两个，而它们的正确方向相反（观测要忠实，决策要保守）。
c "读数层不做决策（EmbedBatchStats 里不出现 BatchEmbed/回落判定）" \
  "$PY -c \"
import re, sys
s = open('$STATS', encoding='utf-8').read()
sys.exit(0 if not re.search(r'BatchEmbed|Unavailable', s) else 1)
\""

# ③ `EmbedBatchPref` 的默认值必须是 **`isSet` 可判**的：把"没设过"与
#    "设了一个恰好等于默认值的数"混成一个读数，"这个值到底生效了没有"就没有基线。
c "批大小设置能区分『没设过』与『设了值』（EmbedBatchPref.isSet）" \
  "grep -q 'fun isSet' \$PREF && grep -q 'const val UNSET' \$PREF"

echo
echo "=== 嵌入合批读数与可调守卫：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
