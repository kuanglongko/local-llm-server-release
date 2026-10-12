#!/usr/bin/env python3
"""打桩：删掉某个函数体内**第一个含指定片段的整行**（守卫必须因此变红）。

用法：stub_drop_line.py <file> <fnName> <needle>

为什么要单独一个脚本：桩的操作是"在**指定函数体内**删一行"，
而 `sh` 里嵌 python heredoc 在本仓库已多次踩坑（展开错、静默返回空串）。
另外它可以把"找不到"变成**明确的非零退出**，而不是静默不改 —— 后者会让
自测假绿：桩没打成、守卫照绿，于是"该红的红"这条断言根本没被检验过。

⚠ 本仓库的守卫判据一律锚"函数体内"，所以这个桩也必须**只在函数体内**动手。
两个函数的函数体边界规则不同（Kotlin 看缩进回到 4 空格的下一处声明；
C++ 看下一个 JNIEXPORT / static），这里两者都算，取更大的那个。
"""
import re
import sys


def find_kt_fn(lines, name):
    """`fun name(` 或 `fun name<`（泛型）所在行号；排除注释里的提及。"""
    for i, l in enumerate(lines):
        if re.match(r'\s*fun\s+' + re.escape(name) + r'\s*[(<]', l):
            return i
    return None


def end_kt(lines, start):
    for i in range(start + 1, len(lines)):
        if re.match(r'    fun\s', lines[i]) or lines[i].startswith('    // ──'):
            return i
    return len(lines)


def find_cpp_fn(lines, name):
    """C++ 侧：`... nativeXxx(` 且该处是定义（行尾 `{`）。"""
    for i, l in enumerate(lines):
        if name in l and l.rstrip().endswith('{') and '(' in l:
            return i
    return None


def end_cpp(lines, start):
    for i in range(start + 1, len(lines)):
        if lines[i].startswith('JNIEXPORT') or lines[i].startswith('static '):
            return i
    return len(lines)


def main() -> int:
    if len(sys.argv) != 4:
        sys.stderr.write(__doc__)
        return 2
    path, fn, needle = sys.argv[1], sys.argv[2], sys.argv[3]
    lines = open(path, encoding='utf-8').read().split('\n')

    start = find_kt_fn(lines, fn)
    if start is None:
        start = find_cpp_fn(lines, fn)
    if start is None:
        sys.stderr.write('打桩失败：找不到函数 %s\n' % fn)
        return 2

    # 两种边界规则都算，取更靠后的那个（覆盖 Kotlin / C++ 两种形态）
    end = max(end_kt(lines, start), end_cpp(lines, start))

    for i in range(start, min(end, len(lines))):
        if needle in lines[i]:
            del lines[i]
            open(path, 'w', encoding='utf-8').write('\n'.join(lines))
            print('已打桩：删掉 %s 里含 %r 的一行（原行 %d）' % (fn, needle, i + 1))
            return 0
    sys.stderr.write('打桩失败：%s 里没有含 %r 的行\n' % (fn, needle))
    return 2


if __name__ == '__main__':
    sys.exit(main())
