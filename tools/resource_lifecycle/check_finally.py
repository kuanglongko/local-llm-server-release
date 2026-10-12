#!/usr/bin/env python3
"""判：文件里每个 `endCancelable(` 调用都身处某个 `finally {` 块内。

为什么锚"在 finally 里"而不是"文件里出现过 endCancelable"：
  把摘除挪到 `try` 末尾时，**正常路径照常摘得掉**、异常路径漏掉 ——
  留下一个"永远在跑"的僵尸轮次，而文件里 `endCancelable` 一次不少，
  存在性判据必然假绿。
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().splitlines()
ok = 0
bad = 0
for i, l in enumerate(lines):
    if "endCancelable(" not in l:
        continue
    if l.strip().startswith("//") or l.strip().startswith("*"):
        continue
    found = None
    for j in range(i, -1, -1):
        if re.search(r"finally\s*\{", lines[j]):
            found = j
            break
        if re.search(r"beginCancelable|^\s*fun ", lines[j]):
            break
    if found is None:
        print(f"{path}:{i + 1}: endCancelable 不在任何 finally 块里")
        bad += 1
    else:
        ok += 1
print(f"endCancelable 共 {ok + bad} 处，其中 {ok} 处在 finally 内")
sys.exit(1 if bad else 0)
