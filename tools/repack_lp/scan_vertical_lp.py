#!/usr/bin/env python3
"""扫「纵向容器 + lp(...) 子项」这一类误用；有则打印名字并 exit 1。

背景见 tools/run_repack_lp_guard.sh：`lp(w, weight)` 是给**横向行**写的
（`w==0` → 宽 0dp、第三参数是 weight）。放在 VERTICAL 容器里，weight 分的是
**高度** —— 子项被拉成整屏高的空块，视觉上就是"一大片空白"。
0.9.127 的 repack 三档单选正是这么写坏的。

把它抽成独立脚本而不是写在守卫里的一行内联 python：那段代码里的正则
（`//`、`/* … */`、`\"`）要穿过两层引用（sh 的 `"` 与 python 的 `\"`），
本判据第一版就在这里被 sh 先解一层、再被 here-doc 当命令执行，跑出一堆
`/bin: Permission denied` 的假失败 —— 与它要防的"判据自己坏了"是同一类事故。

用法：python3 tools/repack_lp/scan_vertical_lp.py <Kotlin 源码路径>
"""
import io
import re
import sys


def strip_literals_and_comments(src: str) -> str:
    """剥字符串字面量与注释。

    顺序有讲究：**先剥字符串字面量**。Kotlin 的 `/* … */` 与字符串里的 `//`
    （URL、路径之类）会互相吞：源码里多处说明文字含 `//`，若先剥 `//`，会把它们的
    收尾引号连行尾一起割掉，接下来那个 `/*.*?*/` 就会一路吃到下一个 `*/` ——
    中间整段代码凭空消失（本判据第一版正是这样，报出一处**不存在的**子项）。
    """
    src = re.sub(r'"[^"]*"', '""', src)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return src


def vertical_containers(src: str) -> set:
    """名字集合：源码里被显式设成 VERTICAL 的 LinearLayout / RadioGroup。"""
    by_ctor = re.findall(
        r'(?:val\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:android\.widget\.)?'
        r'(?:LinearLayout|RadioGroup)\(this\)[^\n]*VERTICAL', src)
    by_prop = re.findall(
        r'([A-Za-z_][A-Za-z0-9_]*)\.orientation\s*=\s*(?:android\.widget\.)?'
        r'(?:LinearLayout|RadioGroup)\.VERTICAL', src)
    return set(by_ctor) | set(by_prop)


def offenders(src: str) -> list:
    """[(容器名, 子项表达式)] —— 父容器是纵向、子项却用 lp(...) 的那些。"""
    names = vertical_containers(src)
    out = []
    for m in re.finditer(
            r'([A-Za-z_][A-Za-z0-9_]*)\.addView\(([^,]+),\s*lp\(', src):
        if m.group(1) in names:
            out.append((m.group(1), ' '.join(m.group(2).split())))
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print('用法: scan_vertical_lp.py <Kotlin 源码路径>', file=sys.stderr)
        return 2
    src = strip_literals_and_comments(io.open(sys.argv[1], encoding='utf-8').read())
    bad = offenders(src)
    if not bad:
        print('none')
        return 0
    for container, child in bad:
        print('%s.addView(%s, lp(…))' % (container, child))
    return 1


if __name__ == '__main__':
    sys.exit(main())
