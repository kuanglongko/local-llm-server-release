#!/bin/sh
# 宿主侧编译并运行「PEG 根节点字面量 + generation_prompt 对齐」单测。
# 不依赖 NDK / 设备 / 模型 —— 跑的是真机编进去的那份 probe_util.h。
#
# 为什么值得单独一个脚本：这条判定（输入要不要改写、往哪改写）是
# 「tool_calls 恒为 0」那类故障的收口条件，而它的产物是**字符串**，
# 错了不会崩、不会报错，只会静默解析不出工具调用。所以把它拿到宿主上
# 用真字节比对钉死：改写后 effective_input 必须与改写前逐字节相同。
#
# 运行：bash tools/run_root_literal_probe_tests.sh
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
"$CXX" -std=c++17 -O1 -g -Wall -Wextra -Werror \
    -I ../app/src/main/cpp \
    root_literal_probe_test.cpp -o /tmp/root_literal_probe_test
exec /tmp/root_literal_probe_test
