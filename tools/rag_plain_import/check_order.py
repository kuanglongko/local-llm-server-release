#!/usr/bin/env python3
"""判两个锚点在同一个函数体里的**先后次序**（`A 必须在 B 之前`）。

为什么要单独一个脚本，而不是在 sh 里写 `awk`：
判据文本里必然带 `[` `(` `"` 这些字符（例如 `startsWith("[")`），
而它们在 awk 的 ERE / sh 的引号里各有一套转义规则。层数一多就会写成
"恒不匹配" —— 守卫永远红（很快被人加 `|| true` 静音）或者恒绿（等于没写）。
这一族判据的整个价值就在**次序**上（判据挪到后面等于没拒绝），所以它值得
一份不必和转义搏斗的实现。

取体用的是**最保守**的写法：从签名行起、到第一个"行首就是右括号"的收尾行为止。
它不认字符串里的花括号，代价是可能截多 —— 于是判据偏向"误报红"，而不是静默绿。
剥注释：这两个锚点在本函数的注释里通常也会出现，不剥会得到恒真的假判。
"""
import re
import sys


def strip_comments(src: str) -> str:
    out = []
    i, n = 0, len(src)
    in_str = in_line = in_block = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                if nxt:
                    out.append(nxt)
                    i += 2
                    continue
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def fnbody(src: str, name: str):
    """取函数体：从签名行起，到位数为**签名缩进**那一行收尾 `}` 为止。

    ⚠ 不能用"第一个行首就是 `}` 的行"当收尾：函数体里的 `if (...) { ... }`
    在**同一个缩进**下也以 `}` 收尾，于是截出来的是一小段
    （本轮实测：只截到第一个 `if` 的收尾，`startsWith(` 根本不在里面，
    判据恒红而看起来像"源码写错了"）。判据的价值全在"体真的取对了吗"，
    所以这里用缩进对齐来判断"哪一行才是函数自己的收尾" —— 与真源码的排版同源，
    不猜花括号。
    """
    lines = src.split("\n")
    start = None
    for i, ln in enumerate(lines):
        if re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", ln):
            start = i
            break
    if start is None:
        return None
    indent = len(lines[start]) - len(lines[start].lstrip())
    body = [lines[start]]
    for i in range(start + 1, len(lines)):
        body.append(lines[i])
        if lines[i].strip() == "}" and (len(lines[i]) - len(lines[i].lstrip())) == indent:
            return "\n".join(body)
    return "\n".join(body)  # 没找到收尾行：返回从签名到文件尾（宁可判宽）


def main() -> int:
    path, name, a, b = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    src = strip_comments(open(path, encoding="utf-8").read())
    body = fnbody(src, name)
    if body is None:
        print(f"找不到函数 {name}（锚点漂了）")
        return 1
    ia, ib = body.find(a), body.find(b)
    if ia < 0:
        print(f"{name} 里找不到前一个锚点：{a!r}")
        return 1
    if ib < 0:
        print(f"{name} 里找不到后一个锚点：{b!r}")
        return 1
    if ia >= ib:
        print(f"次序反了：{a!r} 在第 {ia} 字符，{b!r} 在第 {ib} 字符（必须在前）")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
