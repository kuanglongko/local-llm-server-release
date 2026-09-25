#!/bin/sh
# 宿主侧编译并运行「信号 handler 链」行为测试（模块 K：K-1 / K-2 / K-3）。
#
# 为什么不能只留源码守卫：见 tools/probe_signal/probe_signal_test.cpp 文件头 ——
# 这三条的症状**全部与"正常"同形**（"现场一行都没有" == "根本没崩 signal"），
# 只有主动送**真信号**才分得清。
#
# 抽取而不是维护副本：`.inc` 由 tools/probe_signal/extract.py 从
# `llama_jni.cpp` **逐字抽取**生成，测的就是真机编进去的那几个函数。
#
# 反例对照：同一份测试源码编**两次** —— 一次对当前实现，一次对**旧版源码**。
# 两个可执行文件必须在同一组输入上给出不同结果（否则判据没打在洞上）。
#
# 运行：bash tools/run_probe_signal_tests.sh
set -e
cd "$(dirname "$0")/.."

CXX=${CXX:-g++}
PY=${PYTHON:-python3}
SRC=app/src/main/cpp/llama_jni.cpp
TEST=tools/probe_signal/probe_signal_test.cpp
EXTRACT=tools/probe_signal/extract.py

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）—— 与既有脚本同一契约。
missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"; exit 0; fi
    echo "缺少 $1"; exit 2
}
command -v "$CXX" >/dev/null 2>&1 || missing "$CXX（本测试需要宿主 C++ 编译器）"
command -v "$PY"  >/dev/null 2>&1 || missing "python3"
for f in "$SRC" "$TEST" "$EXTRACT"; do [ -f "$f" ] || missing "$f"; done

OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
INC="$OUT/probe_signal_extract.inc"
OLDINC="$OUT/probe_signal_extract_old.inc"

"$PY" "$EXTRACT" "$SRC" "$INC"
[ -s "$INC" ] || missing "抽取单元为空（源码形状变了？）"

# ═══════════════════════════════════════════════════════════════════════════
# 「旧写法」是一个**历史对象**，不能锚 `origin/main`
# ═══════════════════════════════════════════════════════════════════════════
# 锚 `origin/main` 会让它在自己被合并的那一刻失效：K 段修复一合进 main，
# `origin/main` 就是修好的那棵树，旧锚点（`_exit(128 + sig)` 那一行）不复存在。
# 改成钉**修复前的父提交**，并额外提供"从当前树往前扫第一个含旧锚点的提交"
# 这条收敛路径（浅克隆 / SHA 不可得时不至于整条失败）。
PROBE_SIGNAL_OLD_BASE=${PROBE_SIGNAL_OLD_BASE:-8297fc5}
find_old_base() {
    for sha in "$PROBE_SIGNAL_OLD_BASE" "${PROBE_SIGNAL_OLD_BASE_ALT:-}"; do
        [ -n "$sha" ] || continue
        git rev-parse --verify -q "$sha^{commit}" >/dev/null 2>&1 || continue
        echo "$sha"; return 0
    done
    for sha in $(git rev-list --max-count=300 HEAD 2>/dev/null); do
        if git show "$sha:app/src/main/cpp/llama_jni.cpp" 2>/dev/null |
           grep -q 'if (old && old->sa_handler == SIG_IGN) _exit(128 + sig);'; then
            echo "$sha"; return 0
        fi
    done
    return 1
}

OLD_BASE=$(find_old_base || true)
if [ -n "$OLD_BASE" ]; then
    git show "$OLD_BASE:app/src/main/cpp/llama_jni.cpp" > "$OUT/old_llama_jni.cpp"
    grep -q 'if (old && old->sa_handler == SIG_IGN) _exit(128 + sig);' "$OUT/old_llama_jni.cpp" || {
        echo "反例对照失败：$OLD_BASE 不含旧锚点（SIG_IGN -> _exit）——"
        echo "  '旧写法' 必须是一个**确实含旧实现**的历史对象。"
        echo "  若历史被改写，请设 PROBE_SIGNAL_OLD_BASE=<含旧实现的提交> 重跑。"
        exit 1
    }
    "$PY" "$EXTRACT" --old "$OUT/old_llama_jni.cpp" "$OLDINC"
    echo "反例对照锚定提交：$OLD_BASE"

    "$CXX" -std=c++17 -O1 -g -Wall -Wextra -Werror -I "$OUT" "$TEST" -o "$OUT/t_new"
    "$CXX" -std=c++17 -O1 -g -Wall -Wextra -Werror -DPROBE_SIGNAL_OLD=1 -I "$OUT" "$TEST" -o "$OUT/t_old"

    echo "── 当前实现 ──────────────────────────────"
    "$OUT/t_new"
    echo "── 旧实现（反例对照，期望与上面不同）─────"
    "$OUT/t_old"
else
    echo "警告：找不到含旧实现的提交（浅克隆 / 历史别名全无）—— 反例对照将 SKIP"
    "$CXX" -std=c++17 -O1 -g -Wall -Wextra -Werror -I "$OUT" "$TEST" -o "$OUT/t_new"
    "$OUT/t_new"
fi
