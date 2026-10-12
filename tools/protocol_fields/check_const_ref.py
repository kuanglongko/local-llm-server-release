#!/usr/bin/env python3
"""某个 `const val <文案名>` 必须**引用**另一个常量，而不是把它的值写死。

═══════════════════════════════════════════════════════════════════════
为什么单开一个脚本（而不是一条 grep）
═══════════════════════════════════════════════════════════════════════
要判的字面量里有 `${...}` —— 内联进 shell 的双引号层时 `${` 会被 shell 自己
展开，判据会退化成"文件里出现过那个名字"（本仓库在 `release_hygiene` 与日志
筛选的桩里已经栽过两次同款：要匹配的文本穿过引号层时被吃掉，于是恒真）。

为什么这条值得单独钉：`max_completion_tokens` 与 `max_tokens` 指向**同一个
语义量**，上限各写一份必然分叉 —— 那时 `max_tokens=99999` 报 400、而
`max_completion_tokens=99999` 通吃（或反过来），调用方看到的是"换个字段名
就能用"，而不是"我越界了"。

用法：
  check_const_ref.py <kt 文件> <被引用的常量名> <引用它的那行的特征串>
退出码：0 = 那个 const 的取值里出现了对被引用常量的 `$` 展开；1 = 没出现
"""
import re
import sys

path, ref, marker = sys.argv[1], sys.argv[2], sys.argv[3]
src = open(path, encoding="utf-8").read()

# 取 `const val <marker> = ...` 那一行（可能跨行到第一个不是续行的行）。
lines = src.split("\n")
idx = None
for i, l in enumerate(lines):
    if re.match(r"\s*const val %s\b" % re.escape(marker), l):
        idx = i
        break
if idx is None:
    print("%s: 找不到 `const val %s`" % (path, marker))
    sys.exit(1)

body = [lines[idx]]
j = idx
while not lines[j].rstrip().endswith(('"', "'")) and j + 1 < len(lines):
    j += 1
    body.append(lines[j])
text = "\n".join(body)

# 判据：取值里出现 `${<ref>}` 或 `$<ref>`。允许包一层花括号。
if re.search(r"\$\{?%s\b" % re.escape(ref), text):
    sys.exit(0)
print("%s: `%s` 没有引用 `%s`（写死了值）:\n  %s" % (path, marker, ref, text.strip()))
sys.exit(1)
