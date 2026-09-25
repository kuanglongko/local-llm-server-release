#!/bin/sh
# 宿主侧编译并运行 `stop` 匹配语义单测（不依赖 NDK / 设备 / 模型）
#
# 与 llama_jni.cpp **共用同一份** stop_sequences.h，所以这里绿了才代表线上语义对。
# 关注两类没有异常、没有日志的故障：漏拦（stop 之后的垃圾被吐出去）
# 与多拦（正文被吞一截）。
#
# 运行：bash tools/run_stop_sampler_tests.sh
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
"$CXX" -std=c++17 -O1 -g -Wall -Wextra -I ../app/src/main/cpp \
    stop_sampler_test.cpp -o /tmp/stop_sampler_test
exec /tmp/stop_sampler_test
