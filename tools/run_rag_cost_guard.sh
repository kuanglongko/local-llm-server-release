#!/bin/sh
# 「建库成本提示：接在用户按下去之前 + 数一致」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠 RagCostTest）
# ═══════════════════════════════════════════════════════════════════════════
# `RagCost` 的单测只证明"算得对、文案短"，证明不了**接上了** ——
# 而这一族缺陷的形状恰恰是"纯逻辑全绿、界面上什么都没有"：
#   · buildPrompt 写好了但没人调 -> 用户按下去之前**仍然是零信息**；
#   · 调了但排在 startBuildIndex **之后** -> 先看到"建库中…"才知道贵，与不提示等价；
#   · 条数小时也调 -> 每次建库都弹框，用户闭眼点确定（真有一条贵的提示时也被无视）；
#   · RagCost 里那份抄写的默认上限与 ChunkParams.maxChunks 不相等 ->
#     撞上限时算错，用户按错的方向去拆文档。
# 判据一律锚**结构关系**（谁在什么位置调了谁 / 两个数是否相等），
# 不锚"某某函数存在"。纯静态断言，不依赖任何工具链。
# 运行：sh tools/run_rag_cost_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
COST=$SRC/RagCost.kt
STORE=$SRC/RagIndexStore.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "EngineActivity.kt 存在" "[ -f \$ACT ]"
c "RagCost.kt 存在"        "[ -f \$COST ]"
c "RagIndexStore.kt 存在"  "[ -f \$STORE ]"

# ① 接线：提示在开工之前、小批量不打扰、真实上限显式传入、数相等、进行中有量级。
#    这一条把五件事收在一个脚本里（它们是同一个契约的五个面），
#    失败时会点名是哪一面。
c "成本提示接在用户按下去之前，且上限与真实值一致" \
  "$PY tools/rag_cost/check_rag_cost_wiring.py \"\$ACT\" \"\$COST\" \"\$STORE\""

# ② 提示必须**只在批量够大时**出现（阈值是常量，不是散落的魔数）。
#    判据锚"阈值来自 RagCost.WARN_ENTRIES"，不是"出现过 200" ——
#    后者在"另一处又写了个 300"的情况下照样绿。
c "提示的触发阈值取自 RagCost.WARN_ENTRIES（不是散落的魔数）" \
  "grep -q 'count < WARN_ENTRIES' \$COST"

# ③ 导入那条路也要说这件事（它是另一个能把条数堆很大的入口）。
#    只做建库那一处的话，用户导完几千条、点重建时才发现要等几分钟。
c "导入确认框也复用了 RagCost 的成本估算（不另算一套）" \
  "grep -q 'RagCost.estimateSeconds' \$ACT"

# ④ RagCost 不许依赖 RagIndexStore / Android：
#    它要能在**不带 Android 栈**的纯逻辑单测里编过（run_rag_cost_tests.sh），
#    一旦引了 android.* 或 RagIndexStore，那条套件就编不过（而它是我们唯一的证据）。
c "RagCost 不 import android（否则纯逻辑套件编不过）" \
  "! grep -q '^import android' \$COST"
# ⚠ 必须**剥注释**再查：RagCost 的说明里刻意提到 RagIndexStore（解释为什么抄了一份
#   同值常量），而"注释里提到"不等于"代码里依赖"。不剥注释的判据在这里是恒假 ——
#   那种判据的后果是本条永远红，然后下一个人把它删掉（比没有更坏）。
c "RagCost 不引用 RagIndexStore（默认上限是本文件常量）" \
  "! $PY tools/rag_index/strip_comments.py \$COST | grep -q 'RagIndexStore'"

echo
echo "=== 建库成本提示守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
