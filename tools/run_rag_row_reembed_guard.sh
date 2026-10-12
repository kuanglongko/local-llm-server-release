#!/bin/sh
# 「改内容、行数不变 → 重算改那一行向量」的接线守卫（ISSUE #228 的新特性）。
#
# 用户原话：*"改内容、行数不变 → 重算改那一行。"*
#
# 为什么必须有这个守卫：这一族的失效形态**全是静默的** ——
#   · 指纹把模型那一半现算成"当前配置" -> 刚写完的索引立刻被判"需重建"
#     （指向一个用户没动过的字段），而这次修法本身就是为了免重建（自相矛盾）；
#   · 只重算向量、不重算指纹里的 `x` -> 索引与正文仍不同源，"免重建"没发生；
#   · 编码失败 / 索引不同源时不退回"只写正文" -> 用户的改动一个字节都没落盘，
#     而回执说得像成功了；
#   · 不先确认"索引此刻可用"就编码 -> 一份索引里两种语义，检索分数像随机，不报错。
# 屏幕上一切正常，只有静态判据能钉住它们不许回来。
#
# 判据都在 tools/rag_row_reembed/check_wiring.py 里，这里只负责调用。
# 运行：sh tools/run_rag_row_reembed_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
exec python3 tools/rag_row_reembed/check_wiring.py "$SRC/LlmEngine.kt" "$SRC/RagIndexStore.kt"
