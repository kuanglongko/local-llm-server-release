#!/usr/bin/env python3
"""剥离 C++/Kotlin 的块注释、行注释与字符串字面量，保留换行。

判据一律锚**实现**：注释里提到 `static Session S;` 或旧写法不算数
（本仓库在模块 I 踩过"把注释也算进实现"的坑）。

⚠ 必须**单趟扫描**，不能先跑 `re.sub(r'/\*.*?\*/')` 再跑 `re.sub(r'//[^\n]*')`：
后者会让 `//` 行注释里的 `/*`（真源码里就有，如 `// ... /*/README.md`）
被当成块注释起点，一路吞到**很远的**下一个 `*/` —— 于是中间的真实代码
（包括 `struct Session` 与 `static Session S;`）整段消失。
这个坑本轮真踩到了：守卫因此把"两份 Session 都在"判成假红。
"""
import sys


def strip(src: str) -> str:
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c in '"\'':
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                j += 1
            out.append('""')
            i = j
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def main():
    src = open(sys.argv[1], encoding='utf-8').read()
    sys.stdout.write(strip(src))


if __name__ == '__main__':
    main()
