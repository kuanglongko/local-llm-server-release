#!/bin/sh
# 「正文框装一片 + 按行偏移回写」的接线守卫（P1-a 立框，P1-b 放开编辑）。
#
# P1-a：框不再装整个库，只装**当前这一页**（点开库要等几秒的根）。
# P1-b：框**可以改**这一页，保存按**行偏移**只回写改过的行
#       （其余行/其余页的真值仍来自磁盘）—— 于是"改字"重新可用，
#       而"拿一页覆盖全库"这条路径从代码上不存在。
#
# 为什么必须有这个守卫：这一族的失效形态**全是静默毁数据**（建索引拿一页去建、
# 保存把 texts.jsonl 整份覆盖成一页、回写落在另一段行上）—— 屏幕上一切正常，
# 不崩不报错。只有静态判据能钉住。
#
# 判据都在 tools/rag_doc_preview/check_wiring.py 里，这里只负责调用。
# 运行：sh tools/run_rag_doc_preview_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
exec python3 tools/rag_doc_preview/check_wiring.py "$SRC/EngineActivity.kt" "$SRC/RagUiCap.kt"
