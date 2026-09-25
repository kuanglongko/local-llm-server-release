#!/bin/sh
# 探针开关/自举那一段的**宿主语法检查**：从 llama_jni.cpp 逐字抽出来，
# 连真头 probe_flag.h / probe_util.h 一起编过。
#
# 为什么需要（而不是只靠静态守卫 + 宿主单测）：
#   · 静态守卫能钉"结构关系"，但抓不到"签名/类型写错、字段名改了一半"；
#   · 宿主单测跑的是 probe_flag.h 的**纯函数**，而 probe_bootstrap / nativeProbeInit
#     这两段调用方（getprop 循环、open、自举缓冲交付的接线）在宿主上编不过 ——
#     llama_jni.cpp 依赖 jni.h / llama.h。
# 这段是本轮改动最密集的地方，所以给它一条"改坏了在推 CI 之前就红"的通路。
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
PY=${PYTHON:-python3}
command -v "$CXX" >/dev/null 2>&1 || { echo "缺少 $CXX（本测试需要宿主 C++ 编译器）"; exit 2; }
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3"; exit 2; }

UNIT=/tmp/probe_syntax_unit.cpp
"$PY" probe_syntax/extract.py ../app/src/main/cpp/llama_jni.cpp "$UNIT.body"
cat probe_syntax/head.h "$UNIT.body" > "$UNIT"

# -Werror：这里只编 7 个函数，警告就是信号（真机上被埋在几千行里的时候不是）。
"$CXX" -std=c++17 -fsyntax-only -Wall -Wextra -Werror -Wno-unused-function \
    -I ../app/src/main/cpp "$UNIT"
echo "=== 探针开关/自举语法检查：PASS（抽取单元 $(basename "$UNIT")）==="
