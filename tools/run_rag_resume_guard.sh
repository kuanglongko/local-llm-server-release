#!/bin/sh
# 「知识库续编：准入 / 续编 / 作废闸 / 状态措辞」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠 RagResumeUiTest / RagIndexStore 的单测）
# ═══════════════════════════════════════════════════════════════════════════
# 本轮把"断了从头跑"改成"断了接着编"，而这条链子有三处接口，两两之间必须
# 严丝合缝 —— 任一处置空，行为**与改动前逐字相同**（照样从头跑），且不报错：
#
#   ① 准入：`load` 要把"前缀连续的半份"判成 `partial`（可续），
#      而不是沿用旧口径 `none` —— 旧口径把可续掐死在门口；
#   ② 续编：`buildIndex` 要**先**读回前缀、从缺口接着编；读回那一步若排在
#      编码循环之后（或压根没有），与从头跑没有任何区别；
#   ③ 作废闸：指纹任一项变了要整档作废**且说出来**（`lastDiff`），
#      否则续编会拿"上次那套配置的向量"配"这次这套"，排序像随机、日志干净。
#
# 判据一律锚**结构关系**（谁在谁之前、谁只认哪种状态、落盘在什么条件下发生），
# 不锚"文件里出现过某个字符串"（写错的那一支也会有关键词）。
#
# 运行：sh tools/run_rag_resume_guard.sh
cd "$(dirname "$0")/.."
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
UI=app/src/main/java/com/xiaowan/localinference/RagResumeUi.kt
PY=${PYTHON:-python3}

TMPOUT=$(mktemp)
trap 'rm -f "$TMPOUT"' EXIT

{
  if [ -f "$UI" ]; then echo "PASS  RagResumeUi.kt 存在（状态措辞纯逻辑层）"
  else echo "FAIL  RagResumeUi.kt 缺失"; fi
  "$PY" tools/rag_resume/check_resume_wiring.py "$STORE" "$ENGINE" "$ACT"
} > "$TMPOUT" 2>&1
rc=$?
cat "$TMPOUT"

ok=$(grep -c '^PASS  ' "$TMPOUT" || true)
bad=$(grep -c '^FAIL  ' "$TMPOUT" || true)
echo
echo "=== 知识库续编守卫：PASS $ok / FAIL $bad ==="
[ "$rc" = "0" ] && [ "$bad" = "0" ] || exit 1
exit 0
