#!/bin/sh
# 「知识库状态 / 库名框提交 / 两个删除按钮」的接线守卫（ISSUE #259 第二批）。
#
# 它回答的是那五条 bug 各自的**结构判据**：状态行读哪份真值、导入的目标库
# 在哪一刻冻结、'未落盘'与'待重建'是不是两个状态、两个删除按钮的判据与分工。
# 判据全是静态断言，所以**必须有一个自测**（见 run_rag_kb_state_guard_tests.sh）——
# 否则一个恒真的 pattern 会一直报绿。
#
# 运行：sh tools/run_rag_kb_state_guard.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
SRC=app/src/main/java/com/xiaowan/localinference

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本守卫只用标准库）"; exit 2; }

exec "$PY" tools/rag_kb_state/check_wiring.py "$SRC/EngineActivity.kt" "$SRC/RagIndexStore.kt" "$SRC/RagResumeUi.kt"
