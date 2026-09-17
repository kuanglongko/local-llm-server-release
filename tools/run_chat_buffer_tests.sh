#!/bin/sh
# 宿主侧编译并运行「模板渲染缓冲区」单测（不依赖 NDK / 设备 / 模型）
#
# 为什么值得单独一个脚本：带 tools 的请求每条都要先渲染模板，
# 接收缓冲的大小与终止符规则不在 llama.cpp 的 API 契约里
# （上游是 strncpy，等长写入不补 NUL）。踩中它的症状是进程随机被杀，
# 事后极难归因到「一个长度参数少加了 1」。这里用 guard page 把边界钉死。
#
# 运行：bash tools/run_chat_buffer_tests.sh
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
"$CXX" -std=c++17 -O1 -g -Wall -Wextra \
    chat_template_buffer_test.cpp -o /tmp/chat_template_buffer_test
exec /tmp/chat_template_buffer_test
