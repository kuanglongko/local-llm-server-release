#!/usr/bin/env python3
"""打桩：把 `LlmEngine.search` 里的指纹构造抹掉（守卫必须因此变红）。

为什么单独一个脚本而不是守卫自测里的 heredoc：`sh` 的 heredoc 里嵌
python 的 f-string / 引号在本仓库已多次踩坑（`$(f)` 展开错、静默返回空串）。
一个**真文件**没有这个问题，也能被单独跑、单独看输出。

⚠ 锚点随实现变化（阶段 2）：指纹从"一行调用"变成了**三行**（`fingerprintOf(` +
`FpInput(...)` + `textsHashOf(...)`）。只把第一行换掉的话，另外两行还在 ——
守卫里的 `grep 'fingerprintOf'` 会因为 `notAFingerprint` 里也含 `fingerprint`
而**照样绿**（桩没打成，却看不出来）。所以这里把**整段三行**一起换成空指纹，
并在找不到时**明确报错**（而不是静默不改：那会让"该红的红"这条自测假绿）。

⚠ 指纹构造在 buildIndex 与 search 里各有一处，所以必须**按行号定位到 search
函数体内**那一次。直接做全局字符串替换会连 buildIndex 的一起改掉 ——
那样守卫即使"指对了 search"也不是因为真的锚在 search 上。
"""
import re
import sys


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write(__doc__)
        return 2
    path = sys.argv[1]
    lines = open(path, encoding='utf-8').read().split('\n')

    # 定位 `fun search(`（排除注释里的提及：行首空白后面直接是 fun）
    start = None
    for i, l in enumerate(lines):
        if re.match(r'\s*fun\s+search\s*\(', l):
            start = i
            break
    if start is None:
        sys.stderr.write('打桩失败：找不到 fun search(\n')
        return 2

    # 函数体的结束：从签名行起，第一个缩进回到同级以下且是 `fun ` 的行。
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if re.match(r'    fun\s', lines[i]) or re.match(r'    // ──', lines[i]):
            end = i
            break

    # 形态：三行连续的指纹构造。按"起止行都命中"来定位，避免只改掉中间那一行。
    for i in range(start, end):
        if 'val fp = RagIndexStore.fingerprintOf(' not in lines[i]:
            continue
        j = i
        while j < end and 'RagIndexStore.textsHashOf(' not in lines[j]:
            j += 1
        if j >= end:
            continue
        # 整段换成空指纹：`search` 于是拿 "" 去 load（必然不匹配 -> 没有索引），
        # 也就是"不做指纹比对"的退化形态。
        new = ['        val fp = ""'] + [''] * (j - i)
        lines[i:j + 1] = new
        open(path, 'w', encoding='utf-8').write('\n'.join(lines))
        print('已打桩：search 里的指纹构造（行 %d-%d）-> val fp = ""' % (i + 1, j + 1))
        return 0
    sys.stderr.write('打桩失败：search 函数体内没找到指纹构造\n')
    return 2


if __name__ == '__main__':
    sys.exit(main())
