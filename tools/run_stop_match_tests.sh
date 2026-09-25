#!/bin/sh
# 宿主侧编译并运行「stop 匹配语义」的行为对照测（真源码 vs 独立参考实现 + 旧实现反例）。
#
# 与 llama_jni.cpp 共用同一份 stop_sequences.h —— 这里绿了才代表线上语义对。
# 为什么不能只留源码守卫：G-1 的两个症状（漏判 / 假命中）都**不报错、不崩、HTTP 200**，
# 只在"同一段文本的不同 tokenizer 分片"之间表现出不一致；不拿成千上万种分片去撞，
# 跑多少遍都是绿的。反例对照（旧实现必须不一致）保证这条测试不是恒真。
#
# 运行：bash tools/run_stop_match_tests.sh
set -e
cd "$(dirname "$0")/.."
CXX=${CXX:-g++}
TEST=tools/stop_match/stop_match_test.cpp

missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"; exit 0; fi
    echo "缺少 $1"; exit 2
}
command -v "$CXX" >/dev/null 2>&1 || missing "$CXX（本测试需要宿主 C++ 编译器）"
[ -f "$TEST" ] || missing "$TEST"
[ -f app/src/main/cpp/stop_sequences.h ] || missing "app/src/main/cpp/stop_sequences.h"

"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror -I app/src/main/cpp \
    "$TEST" -o /tmp/stop_match_test
exec /tmp/stop_match_test "${1:-200000}"
