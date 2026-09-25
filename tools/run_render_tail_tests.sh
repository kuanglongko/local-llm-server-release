#!/bin/sh
# 宿主侧编译并运行「渲染出口长度段 + 尾窗口」行为测试。
#
# 覆盖模块 F 的两条修复：
#   F-1 长度段必须等于**实际发出那一串**的 code unit 数（50 万例随机字节对照）；
#   F-2 两侧尾窗口必须**同单位**（字节），中文/emoji prompt 下同档。
#
# 为什么不能只留源码守卫：见 tools/render_tail/render_tail_test.cpp 文件头 ——
# F-1 的症状**只在非法 UTF-8 上出现**（合法 UTF-8 下两边恒等）；
# F-2 的症状**只在非 ASCII prompt 上出现**（ASCII 下两种窗口覆盖同一段）。
# 也就是说：不构造这两类输入，跑多少遍都是绿的。
#
# 抽取而不是维护副本：`.inc` 由 tools/render_tail/extract.py 从
# `llama_jni.cpp` / `utf8_safe.h` / `probe_util.h` / `ThinkStream.kt` **逐字抽取**
# 生成，测的就是真机编进去的那几个函数。
#
# 运行：bash tools/run_render_tail_tests.sh
set -e
cd "$(dirname "$0")/.."

CXX=${CXX:-g++}
PY=${PYTHON:-python3}
SRC=app/src/main/cpp/llama_jni.cpp
UTF8=app/src/main/cpp/utf8_safe.h
PU=app/src/main/cpp/probe_util.h
KT=app/src/main/java/com/xiaowan/localinference/ThinkStream.kt
TEST=tools/render_tail/render_tail_test.cpp
EXTRACT=tools/render_tail/extract.py

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）—— 与既有脚本同一契约。
missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"; exit 0; fi
    echo "缺少 $1"; exit 2
}
command -v "$CXX" >/dev/null 2>&1 || missing "$CXX（本测试需要宿主 C++ 编译器）"
command -v "$PY"  >/dev/null 2>&1 || missing "python3"
for f in "$SRC" "$UTF8" "$PU" "$KT" "$TEST" "$EXTRACT"; do [ -f "$f" ] || missing "$f"; done

OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
INC="$OUT/render_tail_extract.inc"

"$PY" "$EXTRACT" "$SRC" "$UTF8" "$PU" "$KT" "$INC"

# ── 反例对照：抽出**旧实现**，让测试断言"旧写法确实会红" ──
# 一条只会绿的断言测不出任何东西 —— "修复是对的"必须与"旧写法确实会红"同时成立，
# 否则要么用例没打在这个洞上，要么这条判据本来就是恒真的。
#
# ═══════════════════════════════════════════════════════════════════════════
# 旧实现必须钉在一个**固定提交**上，不能锚 `origin/main`
# ═══════════════════════════════════════════════════════════════════════════
# 这条判据的语义是"**旧写法**必须会红"，而"旧写法"是一个**历史对象**。
# 锚 `origin/main` 会让它在自己被合并的那一刻失效：F 段修复一合进 main，
# `origin/main` 就是修好的那棵树，旧锚点（`enum class RenderedThinkShape`、
# `kGenPromptTailWindow`、`TAIL_WINDOW`）全部不复存在 —— 抽取当场抛
# `ValueError: substring not found`，脚本以 1 退出。CI 里写的是
# `REQUIRED=0 sh tools/run_render_tail_tests.sh`，而 `REQUIRED=0` 只把
# **缺工具链**降级为 SKIP，拦不住这个崩溃 —— 于是 CI 一直红着。
# 改成钉提交之后，"该红时红、该绿时绿"，且不会再随合并漂移。
#
# 钉的是 F 段修复（`d3f92b8`）的**父提交**：那一版里旧实现确实存在。
RENDER_TAIL_OLD_BASE=${RENDER_TAIL_OLD_BASE:-cefb5c0}
OLD="$OUT/old"
mkdir -p "$OLD"

# 三条都试：① 钉定的提交；② 显式指定的提交；③ 从 main 往回收敛地找**含旧锚点**的提交。
# 之所以要 ③：钉死在某个 SHA 上，在历史被改写/浅克隆时就会拿不到 —— 那时要么
# 静默丢一半判据（旧行为），要么整条测试失败（同样不对）。收敛查找既保住
# "旧写法"这个语义，又不依赖某个具体 SHA 一直可得。
find_old_base() {
    for sha in "$RENDER_TAIL_OLD_BASE" "${RENDER_TAIL_OLD_BASE_ALT:-}"; do
        [ -n "$sha" ] || continue
        git rev-parse --verify -q "$sha^{commit}" >/dev/null 2>&1 || continue
        echo "$sha"; return 0
    done
    # 从当前树的父提交往前扫，取第一个**含旧锚点**的提交
    for sha in $(git rev-list --max-count=200 HEAD 2>/dev/null); do
        if git show "$sha:app/src/main/cpp/llama_jni.cpp" 2>/dev/null | grep -q 'enum class RenderedThinkShape'; then
            echo "$sha"; return 0
        fi
    done
    return 1
}

OLD_BASE=$(find_old_base || true)
if [ -n "$OLD_BASE" ]; then
    git show "$OLD_BASE:app/src/main/cpp/llama_jni.cpp" > "$OLD/llama_jni.cpp" 2>/dev/null || missing "旧版 llama_jni.cpp（$OLD_BASE）"
    git show "$OLD_BASE:app/src/main/cpp/utf8_safe.h" > "$OLD/utf8_safe.h" 2>/dev/null || missing "旧版 utf8_safe.h（$OLD_BASE）"
    git show "$OLD_BASE:app/src/main/cpp/probe_util.h" > "$OLD/probe_util.h" 2>/dev/null || echo "" > "$OLD/probe_util.h"
    git show "$OLD_BASE:app/src/main/java/com/xiaowan/localinference/RenderedPrompt.kt" > "$OLD/RenderedPrompt.kt" 2>/dev/null || missing "旧版 RenderedPrompt.kt（$OLD_BASE）"
    # 旧锚点必须**真的**在抽出来的那一版里 —— 否则 `extract.py --old` 会抛异常，
    # 那正是本条判据被合并弄失效时的形态。这里提前判掉，给出可读的错因。
    grep -q 'enum class RenderedThinkShape' "$OLD/llama_jni.cpp" || {
        echo "反例对照失败：$OLD_BASE 不含旧锚点 RenderedThinkShape ——"
        echo "  '旧写法' 必须是一个**确实含旧实现**的历史对象。"
        echo "  若历史被改写，请设 RENDER_TAIL_OLD_BASE=<含旧实现的提交> 重跑。"
        exit 1
    }
    "$PY" "$EXTRACT" --old "$OLD/llama_jni.cpp" "$OLD/utf8_safe.h" "$OLD/probe_util.h" \
        "$OLD/RenderedPrompt.kt" "$OUT/render_tail_extract_old.inc"
    EXTRA="-DRENDER_TAIL_HAVE_OLD -I $OUT"
    mv "$OUT/render_tail_extract_old.inc" "$OUT/old_extract.inc"
    # 旧单元用另一个名字 include，靠宏切换
    echo "反例对照锚定提交：$OLD_BASE"
    "$CXX" -std=c++17 -O2 -Wall -Wextra -Werror $EXTRA "$TEST" -I "$OUT" -o "$OUT/render_tail_test"
else
    echo "警告：找不到含旧实现的提交（浅克隆 / 历史别名全无）—— 反例对照将 SKIP，只跑正向断言"
    "$CXX" -std=c++17 -O2 -Wall -Wextra -Werror "$TEST" -I "$OUT" -o "$OUT/render_tail_test"
fi
"$OUT/render_tail_test"
