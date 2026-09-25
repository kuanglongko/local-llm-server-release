#!/bin/sh
# 探针开关判据（显式关闭）与自举缓冲（只记不写）的宿主单测。
#
# 为什么需要：这两条判据都是"错了不崩、不报错"的形态 ——
#   · 开关属性读错 → 用户在设置页关了探针，native 照样全量落盘并把日志 sink 换成空操作；
#   · 自举缓冲交付错 → "回灌正式文件"恒写 0 字节，而 Kotlin 侧的排查指令正建立在它上面。
# 单测**直接 include 仓库里的 probe_flag.h**（真机编进去的同一份），不是复刻品。
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
command -v "$CXX" >/dev/null 2>&1 || { echo "缺少 $CXX（本测试需要宿主 C++ 编译器）"; exit 2; }
"$CXX" -std=c++17 -O1 -g -Wall -Wextra -Werror -I ../app/src/main/cpp \
    probe_util_test.cpp -o /tmp/probe_util_test
exec /tmp/probe_util_test
