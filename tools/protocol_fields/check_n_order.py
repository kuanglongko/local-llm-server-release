#!/usr/bin/env python3
"""`n` 的取值分支里，「哨兵」必须判在「= 1」之前。

═══════════════════════════════════════════════════════════════════════
为什么单开一个脚本（而不是一条 grep）
═══════════════════════════════════════════════════════════════════════
`readIntField` 把"读不出合法整数"折成一个**必然越界**的哨兵 `MALFORMED`
（取值 `Int.MAX_VALUE`）。于是 `n` 的 `when` 有三支：哨兵、`== N_SUPPORTED`、
其余（= 不支持）。**这三支的顺序不是风格问题**：

`readIntField` 对 `1.5` / `"abc"` / `true` 一律返回 `MALFORMED`（= Int.MAX_VALUE），
而 `MALFORMED > N_SUPPORTED` 恒成立。所以只要"哨兵"那支排到后面，
`n: 1.5` 就会先命中别的分支、报成"不支持 1.5"——
调用方看到的是「这个服务不支持 n=1.5」，而真相是「你写错类型了」。
两者对调用方的含义不同（一个是"我可以但没做"，一个是"你写错了"），
文案必须分开，顺序错了就等于没分。

用法：
  check_n_order.py <kt 文件>
退出码：0 = 哨兵分支在 N_SUPPORTED 分支之前；1 = 顺序反了或找不到
"""
import re
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()
lines = src.split("\n")

# 定位 `val n = when {`（n 的取值分支）
start = None
for i, l in enumerate(lines):
    if re.search(r"\bval n = when\s*\{", l):
        start = i
        break
if start is None:
    print("%s: 找不到 `val n = when {`（n 的取值分支）" % path)
    sys.exit(1)

# 取到配对的 `}`（简单计数，分支里没有嵌套 when）
depth = 0
body = []
for l in lines[start:]:
    body.append(l)
    depth += l.count("{") - l.count("}")
    if depth == 0:
        break
block = "\n".join(body)

m_bad = re.search(r"nRaw == MALFORMED", block)
m_ok = re.search(r"nRaw == N_SUPPORTED", block)
if m_bad is None:
    print("%s: n 的取值分支里没有 `nRaw == MALFORMED`（哨兵未判 -> 非整数会被误报）" % path)
    sys.exit(1)
if m_ok is None:
    print("%s: n 的取值分支里没有 `nRaw == N_SUPPORTED`" % path)
    sys.exit(1)
if m_bad.start() > m_ok.start():
    print("%s: 哨兵分支排在 N_SUPPORTED 之后 —— `n: 1.5` 会被误报成「不支持」而不是「写错了」" % path)
    sys.exit(1)
print("ok")
