#!/bin/sh
# 「列表先出 + 删一条不重读盘」的接线守卫（ISSUE #228）。
#
# 判据都在 tools/rag_list_first/check_wiring.py 里，这里只负责调用。
# 运行：sh tools/run_rag_list_first_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
exec python3 tools/rag_list_first/check_wiring.py "$SRC/EngineActivity.kt" "$SRC/RagUiCap.kt"
