#!/usr/bin/env python3
"""去掉 C/C++ 或 Kotlin 源码里的注释，仅保留代码。

与 tools/dual_model/strip_comments.py 同源 —— 本仓库的所有源码级守卫都要
"判据锚实现，不锚注释"。**为什么不能简单用正则**：注释里常常写着实现本该做的
事（甚至写着"这里必须调用 X"），直接 grep 原文会把注释当成实现，于是
"文档说了 == 代码做了"，而恰恰是这种假绿最贵。

⚠ 与 dual_model 那份的关键差别：本脚本按语言分档，因为 Kotlin 的
`//` 与 C 的 `//` 语义相同，但 Kotlin 的**字符串模板** `"${...}"` 里可以出现
`//`，C 的字符字面量 `'/'` 同理。实现上先逐字符扫行，再判"是否在字符串里"。

用法：strip_comments.py <file>   （.kt -> Kotlin 规则，其余按 C/C++）
"""
import sys


def strip_c(src: str) -> str:
    out = []
    i, n = 0, len(src)
    state = None          # None | "'" | '"' | '//' | '/*'
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = '//'; i += 2; continue
            if c == '/' and nxt == '*':
                state = '/*'; i += 2; continue
            if c in '"\'':
                state = c; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if state == '//':
            if c == '\n':
                state = None; out.append(c)
            i += 1; continue
        if state == '/*':
            if c == '*' and nxt == '/':
                state = None; i += 2; continue
            # 块注释里的换行要保留，否则行号会漂
            if c == '\n':
                out.append('\n')
            i += 1; continue
        # 在字符串 / 字符字面量里
        if c == '\\' and i + 1 < n:
            out.append(c); out.append(nxt); i += 2; continue
        if c == state:
            state = None
        out.append(c); i += 1
    return ''.join(out)


def strip_kt(src: str) -> str:
    # Kotlin：`//`、`/* */`（可嵌套）、`"..."`、`"""..."""`、`'...'`。
    '''Kotlin 去注释：处理 ``//``、``/* */``（可嵌套）、字符串与原始字符串。'''
    out = []
    i, n = 0, len(src)
    state = None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = '//'; i += 2; continue
            if c == '/' and nxt == '*':
                state = '/*'; depth = 1; i += 2; continue
            if src[i:i + 3] == '"""':
                state = '"""'; out.append('"""'); i += 3; continue
            if c in '"\'`':
                state = c; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        if state == '//':
            if c == '\n':
                state = None; out.append(c)
            i += 1; continue
        if state == '/*':
            # Kotlin 的块注释可嵌套 —— 不处理会在嵌套处提前收尾，
            # 于是注释内容被当成实现（假绿）。
            if c == '/' and nxt == '*':
                depth += 1; i += 2; continue
            if c == '*' and nxt == '/':
                depth -= 1; i += 2
                if depth == 0:
                    state = None
                continue
            if c == '\n':
                out.append('\n')
            i += 1; continue
        if state == '"""':
            if src[i:i + 3] == '"""':
                state = None; out.append('"""'); i += 3; continue
            out.append(c); i += 1; continue
        if c == '\\' and i + 1 < n:
            out.append(c); out.append(nxt); i += 2; continue
        if c == state:
            state = None
        out.append(c); i += 1
    return ''.join(out)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    path = sys.argv[1]
    src = open(path, encoding='utf-8').read()
    sys.stdout.write(strip_kt(src) if path.endswith('.kt') else strip_c(src))
    return 0


if __name__ == '__main__':
    sys.exit(main())
