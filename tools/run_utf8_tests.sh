#!/bin/sh
# 宿主侧编译并运行 utf8_safe 单测（不依赖 NDK，也不依赖 ASan 运行时）
# 用途：改动 app/src/main/cpp/utf8_safe.h 后，在推 CI 前先本地验证解码器与边界安全。
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
"$CXX" -std=c++17 -O1 -g -Wall -Wextra -I stub_jni -I ../app/src/main/cpp \
    utf8_safe_test.cpp -o /tmp/utf8_safe_test
exec /tmp/utf8_safe_test
