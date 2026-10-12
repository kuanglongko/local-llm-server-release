#!/usr/bin/env python3
"""`FinishReason.of` 的四个分支必须按 cancelled > length > tool_calls > stop 排。

═══════════════════════════════════════════════════════════════════════
为什么单开一个脚本（而不是一条 grep）
═══════════════════════════════════════════════════════════════════════
要判的是**四个分支的相对顺序**，grep 只能判"某个串在不在文件里"，判不了
"这一支排在那一支之前"。而顺序在这里正是判据：一个 `when { }` 的分支先后
决定了优先级，顺序反了的症状不是报错，是**某一档永远不可达** ——
"被取消且正好跑满"会报 length，调用方于是以为"拉长上限就能拿到完整输出"，
而重试会被再次取消。

用法：
  check_priority_order.py <kt 文件>
退出码：0 = 顺序正确；1 = 顺序反了或找不到
"""
import re
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()
lines = src.split("\n")

start = None
for i, l in enumerate(lines):
    if re.search(r"fun of\(", l):
        start = i
        break
if start is None:
    print("%s: 找不到 `fun of(`" % path)
    sys.exit(1)

depth = 0
block = []
for l in lines[start:]:
    block.append(l)
    depth += l.count("{") - l.count("}")
    if depth == 0 and len(block) > 1:
        break
body = "\n".join(block)

order = [("cancelled", "-> CANCELLED"), ("hitLimit", "-> LENGTH"),
         ("hasToolCalls", "-> TOOL_CALLS"), ("else", "-> STOP")]
pos = []
for marker, ret in order:
    idx = body.find(ret)
    if idx < 0:
        print("%s: of() 里找不到 `%s`（缺了 %s 这一档）" % (path, ret, marker))
        sys.exit(1)
    pos.append(idx)
if pos != sorted(pos):
    print("%s: of() 的分支顺序不对 —— 必须是 cancelled > length > tool_calls > stop" % path)
    sys.exit(1)
print("ok")
