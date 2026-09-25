#!/bin/sh
# 宿主侧编译并运行 KV 前缀复用的切分语义单测（不依赖 NDK / 设备 / 模型）
#
# 与 llama_jni.cpp **共用同一份** kv_prefix.h，所以这里绿了才代表线上算出的
# 「KV 留几个 / 还要 prefill 几个」是对的。
#
# 为什么它必须在 CI 里跑：复用的失效模式全是**静默**的 ——
# 多留一段 = 模型读到错位历史（答非所问，但接口 200）；少留一段 = 只是慢，
# 效果凭空消失；reuse == total = 采样在没有 logits 的上下文上取值。
# 这三类都不会抛异常、不会打日志，只有断言能钉住。
#
# 运行：bash tools/run_kv_cache_tests.sh
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
"$CXX" -std=c++17 -O1 -g -Wall -Wextra -I ../app/src/main/cpp \
    kv_prefix_test.cpp -o /tmp/kv_prefix_test
exec /tmp/kv_prefix_test
