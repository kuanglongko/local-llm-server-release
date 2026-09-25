#!/bin/sh
# 宿主侧运行「取消（abort）归属 + 账本作废」的离线**复刻**测试（模块 E：E-1~E-5）。
#
# 为什么是"复刻"而不是直接编 llama_jni.cpp：它依赖 llama.h/ggml 的真实实现与 NDK，
# 宿主上编不过；而这四条**没有一条会让别的测试变红**（错停对象/答非所问/读数撒谎/
# 两种处境同形）。复刻与实现的逐条对应关系写在 tools/abort/AbortEpochTest.py 里，
# 并由 tools/run_llama_jni_abort_guard.sh 的静态断言保证实现里真有那几条判据。
#
# 依赖：只要 python3（标准库）。
# 运行：sh tools/run_llama_jni_abort_tests.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }
exec "$PY" tools/abort/AbortEpochTest.py
