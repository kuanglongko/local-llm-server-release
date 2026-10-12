#!/usr/bin/env python3
"""桩：把 `logprobsFieldOf` 的 else 支改成**非空** —— 即"客户端没要也出现该字段"。

单独成文件是因为这条替换要匹配的正是源码里那串 `" ,\"logprobs\":null"`，
内联进 `run_protocol_fields_guard_tests.sh` 的 heredoc 时会被三层引号层
（sh 单引号 -> python 三引号 -> 源码里的转义引号）吃掉，脚本本身先报
AssertionError，而那不是被守卫抓住的（是桩自己写坏了）。

用法：stub_logprobs_always.py <HttpApi.kt>
"""
import re
import sys

path = sys.argv[1]
s = open(path, encoding="utf-8").read()

# 匹配 `if (wanted) <字面量> else <字面量>`，把 else 那个换成与 if 支相同。
pat = re.compile(r"(if\s*\(wanted\)\s*)(\"(?:[^\"\\]|\\.)*\")(\s*else\s*)(\"(?:[^\"\\]|\\.)*\")")
m = pat.search(s)
if not m:
    print("桩⑤：没找到 logprobsFieldOf 的 if/else 字面量对")
    sys.exit(1)
s = s[:m.start()] + m.group(1) + m.group(2) + m.group(3) + m.group(2) + s[m.end():]
open(path, "w", encoding="utf-8").write(s)
print("poked")
