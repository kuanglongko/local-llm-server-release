#!/bin/sh
# 宿主侧编译并运行「向量嵌入纯逻辑」单测（不依赖 NDK / 设备 / 模型）
#
# 与 llama_jni.cpp **共用同一份** app/src/main/cpp/embed_text.h，所以这里绿了
# 才代表线上算出的向量、池化下标与检索顺序是对的。
#
# 为什么它必须在 CI 里跑：嵌入的失效模式全是**静默**的 ——
#   不归一化 = 检索按向量长度排序（"总是命中同几条"，语义全错但接口 200）；
#   零向量入库 = 余弦 0/0 = NaN，排序未定义；
#   池化取错 token / 越界 = 读到无关上下文，或越界读段错误（进程级闪退）；
#   维度对不上 = 点积读越界，而数组长度看起来没问题；
#   同分顺序不稳定 = 真机验收不可复现。
# 这些都不抛异常、不崩溃、不报错，只有断言能钉住。
#
# 运行：bash tools/run_embed_text_tests.sh
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
    embed_text_test.cpp -o /tmp/embed_text_test
exec /tmp/embed_text_test
