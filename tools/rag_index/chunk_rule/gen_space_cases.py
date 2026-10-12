#!/usr/bin/env python3
"""生成"分隔符只有单字符空格"的语料，喂给 `run_diff.sh` 的探针格式。

═══════════════════════════════════════════════════════════════════════════
为什么单独立一份语料（而不是往 gen_corpus.py 里加一档）
═══════════════════════════════════════════════════════════════════════════
`gen_corpus.py` 的三档（ASCII / CJK / emoji）里**没有一个空格**，于是
`pick_chunk_cut` 里那条"单字符分隔符"的路径**从来没被跑过**。而这条路径上
有一个必然失效的形态（见 `cases/space_cut_at_window_end.txt` 的表头）：
分隔符正好落在窗口末位时 `cut == end`，而"下一块的起点"是 `end - overlap`
—— 落在**上一轮窗口内部**，那一轮找不到新分隔符，于是硬截。

本脚本只生成**语料**；判据在 `run_space_cases.sh` 里（它独立调用真源码，
不依赖 kotlinc / vendor 库）。
"""
import sys


def cases():
    """[(名字, 说明, targetChars, overlapChars, 正文), ...]"""
    out = []
    # ① 分隔符正好落在窗口末位 —— 上面那条失效形态。C++ 实测 4 块 3 次硬截。
    a = "a" * 9 + " " + "b" * 4 + " " + "c" * 9 + " "
    out.append(("space_cut_at_window_end", "分隔符落在窗口末位（cut==end）", 10, 4, a))
    # ② 每一段都以"分隔符 + 26 个非分隔符字符"结尾 —— 每轮窗口末位都是分隔符，
    #    相邻块之间存在**连续 ≥26 个字符的公共片段**（`b` 那一段整个被重切）。
    b = "A" * 9 + "\n" + "B" * 26
    out.append(("space_block_duplicated", "相邻块大段重复", 36, 10, b))
    # ③ 守正例：窗口内分隔符不在末位 —— 此时不会出现"重叠区被整块重切"。
    c = "x" * 5 + " " + "y" * 14 + " " + "z" * 5 + " "
    out.append(("space_normal", "分隔符不在末位（守正例）", 20, 4, c))
    # ④ 混合：段落（双字符）与单字符空格交替，钉住"两个 pass"的次序
    d = "p" * 8 + "\n\n" + "q" * 14 + " " + "r" * 14 + " " + "s" * 8
    out.append(("space_mixed_para", "段落级与单字符级交替", 20, 4, d))
    return out


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for name, why, tg, ov, text in cases():
        if only and only != name:
            continue
        # 探针输入格式：byteTarget \t byteOverlap \t charTarget \t charOverlap \t 正文
        body = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        print("%d\t%d\t%d\t%d\t%s" % (tg, ov, tg, ov, body))
        sys.stderr.write("# %s: %s  (target=%d overlap=%d len=%d)\n"
                         % (name, why, tg, ov, len(text)))


if __name__ == "__main__":
    main()
