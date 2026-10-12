#!/bin/sh
# GGUF 预读前置检查的宿主单测（Issue #238）。
#
# 为什么需要：真机上 `nativeProbeGguf` 把整份 GGUF 元数据（含 151936 条词表）
# 交给库去 malloc，那一轮 malloc 风暴崩在 libc 的 arena 里
# （tombstone_03: arena_run_heap_remove+888, fault addr=0x8），
# 而它跑在没有 Java 层兜底的裸线程上 —— 崩了就是进程级，没有 catch 接得住。
# 本检查的作用是"**不**把读不了的文件交给库"：把"明显不是 GGUF /
# 明显被截断 / 头部计数明显荒谬"这一档挡在解析之前。
#
# 单测**直接 include 仓库里的 gguf_precheck.h**（真机编进去的同一份），不是复刻品。
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
command -v "$CXX" >/dev/null 2>&1 || { echo "缺少 $CXX（本测试需要宿主 C++ 编译器）"; exit 2; }
"$CXX" -std=c++17 -O1 -g -Wall -Wextra -Werror -I ../app/src/main/cpp \
    gguf_precheck_test.cpp -o /tmp/gguf_precheck_test
exec /tmp/gguf_precheck_test
