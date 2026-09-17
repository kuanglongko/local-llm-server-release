#!/bin/sh
# 宿主侧编译并运行「libc++ 短串布局判据」单测（不依赖 NDK / 设备）。
#
# 为什么值得单独一个脚本：ABI 自检的字符串布局判据曾被写反（读末字节而非首字节），
# 导致真机上持续误报"ABI 不一致"，把一个不存在的故障当成根因追了好几轮。
# 这里用显式构造的字节数组把判据钉死，CI 用 libstdc++ 编也能拦住回归。
#
# 运行：bash tools/run_string_layout_tests.sh
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
"$CXX" -std=c++17 -O1 -g -Wall -Wextra \
    string_layout_test.cpp -o /tmp/string_layout_test
exec /tmp/string_layout_test
