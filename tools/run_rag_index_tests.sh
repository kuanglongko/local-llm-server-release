#!/bin/sh
# 宿主侧编译并运行「知识库索引纯逻辑」单测（不依赖 NDK / 设备 / 模型）
#
# 与 llama_jni.cpp **共用同一份** app/src/main/cpp/rag_index.h，所以这里绿了
# 才代表线上分块 / 指纹 / 头布局 / 注入选择是对的。
#
# 为什么它必须在 CI 里跑：索引的失效模式全是**静默**的 ——
#   · 切在 UTF-8 码点中间 -> 那一段永远编码失败，"某几段检索不到"，无日志指向分块；
#   · 块间留空洞 -> 中间几个字节的正文永远进不了索引，"找不到"与"没有"表现一样；
#   · 指纹漏字段 -> 换模型/换切块参数后旧索引被当新的用，检索命中旧向量，排序全乱而 200；
#   · 头读错位 -> 把指纹当向量读，维度"对得上"，检索永远无意义；
#   · overlap >= target / target = 0 -> 死循环或永久不前进；
#   · 空库当"建好了" / 无命中硬塞低分条目 -> 污染回答且无法归因。
# 都不抛异常、不崩溃、不报错，只有断言能钉住。
#
# 运行：bash tools/run_rag_index_tests.sh
# 缺 g++ 时 REQUIRED=0 退化为 SKIP（CI 是 AFK 编包场景），否则硬失败。
set -e
cd "$(dirname "$0")"

missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then
        echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"
        exit 0
    fi
    echo "缺少 $1"
    exit 2
}

CXX=${CXX:-g++}
command -v "$CXX" >/dev/null 2>&1 || missing "g++（宿主 C++ 编译器）"

"$CXX" -std=c++17 -O1 -g -Wall -Wextra -I ../app/src/main/cpp \
    rag_index_test.cpp -o /tmp/rag_index_test
# 正文 hash 的**共用向量表**：与 Kotlin 侧单测读同一份文件（那条走 TEXTS_HASH_VECTORS
# 环境变量，见 run_rag_index_store_tests.sh）。传绝对路径是因为本脚本已经 cd 到了
# tools/，而表在 tools/rag_index/ 下 —— 相对路径会随 CWD 变，那是"跑不起来"而不是"判据红"。
exec /tmp/rag_index_test "$(pwd)/rag_index/texts_hash_vectors.tsv"
