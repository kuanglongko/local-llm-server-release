#!/usr/bin/env python3
"""`logprobs` 字段只能**在客户端要了时**出现。

═══════════════════════════════════════════════════════════════════════
为什么必须单独钉一条
═══════════════════════════════════════════════════════════════════════
本仓库对"新增可选字段"有一条硬不变量（写在 `JsonSchemaFormat` 文件头）：
**字段不存在 => 与引入本特性之前逐字节相同**。响应里无条件多出一个
`"logprobs":null` 看起来无害，但它改的是**所有**既有客户端看到的响应形状 ——
而 SDK 对"响应里有没有这个字段"是有判断的（它据此决定要不要解那一块）。

判据是"那个三元表达式的 else 支是空串"这一条**结构**，而不是"文件里出现过
logprobsFieldOf"：后者在把 else 支改成非空常量后照样为真，而那时每个响应
都会多一个字段。

走 python 是因为要匹配的片段里有转义引号与逗号，内联进 shell 的引号层容易打架。

用法：check_logprobs_optional.py <HttpApi.kt>
退出码：0 = else 支确实是空串；1 = 不是（或找不到判据前提）
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().split("\n")

# 找到函数头那一行（可能跨行写 `= if (...) ... else ...`）。
start = None
for i, l in enumerate(lines):
    if "fun logprobsFieldOf(" in l:
        start = i
        break
if start is None:
    print("%s: 找不到 logprobsFieldOf（被改名/删掉了）" % path)
    sys.exit(1)

# 取函数头起 6 行，剥注释、折成单行 —— 跨行写法也一起收进来。
code = "\n".join(l.split("//", 1)[0] for l in lines[start:start + 6])
code = " ".join(code.split())

# else 后面那个字面量：要么是普通字符串，要么是转义空串。
m = re.search(r'\belse\b\s*("(?:[^"\\]|\\.)*"|[^\s;,)]+)', code)
if not m:
    print("%s: logprobsFieldOf 里找不到 else 支" % path)
    sys.exit(1)
other = m.group(1)

# 只接受"空串"这一种形状：`""` 或 `"\"\""`（源码里就是两个引号）。
if other in ('""', '"\\"\\""'):
    sys.exit(0)
print("%s: logprobsFieldOf 的 else 支不是空串，而是 %s" % (path, other))
print("      客户端没要 logprobs 时也会多出字段 —— 违反「默认逐字节同旧行为」")
sys.exit(1)
