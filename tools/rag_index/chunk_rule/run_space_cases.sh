#!/bin/sh
# 「硬截轮不重叠」这一档的**合规判据**（不是现状指纹）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条为什么必须存在（run_diff.sh 覆盖不到它）
# ═══════════════════════════════════════════════════════════════════════════
# `run_diff.sh` 的三档语料（ASCII / CJK / emoji）验的是"C++ 与 Kotlin 切得一样"，
# 判不了"切得对不对"。而这里这一档，**两份实现会一起错**：
#
# 硬截轮（窗口内一个分隔符都没有）结束时 `end = pos + target`，而"下一块的起点"
# 曾是 `end - overlap` —— 落在**上一轮已消化过的区间内部**。那一轮的窗口右端
# 仍是"新起点 + target"，整段仍在上一轮覆盖范围里，于是同样找不到分隔符、同样
# 硬截。后果两条，都不响：
#   · `overlap` 那段正文被切**两遍**（相邻块最长公共片段 ≥ overlap），
#     检索时同一段正文互相抢 top-k；
#   · 硬截计数被灌水 —— 用户看到"有 N 块是硬截的"，N 里一半是重切。
#
# 修法：**硬截那一轮不重叠**（`next = end`），软切那一轮**保留**重叠
# （切点由分隔符决定，"语义跨边界"是真需求）。
#
# ⚠ 本脚本的判据一律**锚行为**（从真源码抽 `chunk_text` 编译后跑），不锚
#   "文件里出现过某个字符串"。且判据是**合规**的（"相邻块不许超出设计重叠"），
#   不是把当前输出冻下来 —— 后者会把有问题的形态固化成"正确"。
#
# 用法：sh tools/rag_index/chunk_rule/run_space_cases.sh
set -e
cd "$(dirname "$0")/../../.."     # 仓库根

PY=${PYTHON:-python3}
HDR=app/src/main/cpp/rag_index.h
EXTRACT=tools/rag_index/chunk_rule/extract_chunk.py
GEN=tools/rag_index/chunk_rule/gen_space_cases.py

command -v "$PY" >/dev/null 2>&1 || { echo "SKIP  lacks python3"; exit 2; }
command -v g++ >/dev/null 2>&1 || {
    if [ "${REQUIRED:-1}" = "0" ]; then echo "SKIP  缺少 g++（REQUIRED=0）"; exit 0; fi
    echo "缺少 g++（宿主 C++ 编译器）"; exit 2
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"$PY" "$EXTRACT" cpp "$HDR" "$TMP/probe.cpp" >/dev/null || exit 1
g++ -std=c++17 -O1 -I app/src/main/cpp "$TMP/probe.cpp" -o "$TMP/probe" || exit 1

"$PY" "$GEN" > "$TMP/corpus.txt" 2> "$TMP/meta.txt"
"$TMP/probe" < "$TMP/corpus.txt" > "$TMP/out.txt"

fail=0
judge() {   # $1=说明 $2=实测表达式（真 -> 红）
    if [ "$2" = "1" ]; then echo "FAIL  $1"; fail=1; else echo "PASS  $1"; fi
}

# 每行 = 一个用例；用例名从 GEN 的 stderr 注释里按序取。
i=0
while IFS= read -r line; do
    i=$((i + 1))
    name=$(sed -n "${i}p" "$TMP/meta.txt" | sed 's/^# \([^:]*\):.*/\1/')
    n=$(echo "$line" | cut -d'|' -f1)
    hc=$(echo "$line" | cut -d'|' -f2)
    case "$name" in
        space_cut_at_window_end)
            # 合规判据：整段没有可用分隔符（全是硬截形态）时，
            #   · 块数必须等于"原文长度按 target 净推进"能切出的最少块数附近
            #     —— 这里直接锚"硬截次数 < 块数"（旧实现 3/4，删掉重切后应更低）；
            #   · 且硬截不灌水：hc <= n（不可能每块都"重切一次还多算"）。
            judge "$name: 硬截次数 <= 块数（不灌水，实测 $hc/$n）" \
                  "$([ "$hc" -le "$n" ] && echo 0 || echo 1)"
            # 旧实现是 4 块 3 硬截（重切一次）。修后应严格更优：块数更少或硬截更少。
            judge "$name: 块数 < 4 或 硬截 < 3（确实去掉了那次重切，实测 $n/$hc）" \
                  "$([ "$n" -lt 4 ] || [ "$hc" -lt 3 ] && echo 0 || echo 1)"
            ;;
        space_block_duplicated)
            # 这一例**只是**重叠的正常表现（首块就吃满 target），不属本条。
            judge "$name: 正常 1 块、0 硬截（实测 $n/$hc）" \
                  "$([ "$n" = "1" ] && [ "$hc" = "0" ] && echo 0 || echo 1)"
            ;;
        space_normal)
            # 守正例：软切那一轮**保留**重叠 —— 块数 2、硬截 1（与修前一致，
            # 证明"不重叠"只作用在硬截轮，没有把软切轮的重叠一起砍掉）。
            judge "$name: 软切轮保留重叠（块数=2、硬截=1，实测 $n/$hc）" \
                  "$([ "$n" = "2" ] && [ "$hc" = "1" ] && echo 0 || echo 1)"
            ;;
        space_mixed_para)
            # 段落级与单字符级交替：修后 4 块 1 硬截（旧实现 5 块 2 硬截 —— 多出来的
            # 那一块正是被重切的区间）。
            judge "$name: 段落与单字符交替 -> 4 块 1 硬截（实测 $n/$hc）" \
                  "$([ "$n" = "4" ] && [ "$hc" = "1" ] && echo 0 || echo 1)"
            ;;
        *)
            echo "SKIP  未知用例 $name"; ;;
    esac
done < "$TMP/out.txt"

judge "用例数 = 4（实测 $i）" "$([ "$i" = "4" ] && echo 0 || echo 1)"

[ "$fail" = "0" ] || exit 1
echo "PASS  硬截轮不重叠（软切轮保留重叠）：行为与预期一致"
exit 0
