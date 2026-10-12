#!/usr/bin/env python3
"""`describe()` 的取值必须写成 `${p.x}`，不能写成 `$p.x`。

═══════════════════════════════════════════════════════════════════════
为什么单开一个脚本（而不是一条 grep）
═══════════════════════════════════════════════════════════════════════
Kotlin 的字符串模板里，`"$p.maxTokens"` **不等价于** `"${p.maxTokens}"`：
前者只把 `p` 求值成字符串（一个 `Parsed(...)` 的 `toString()`），
`.maxTokens` 被当成**普通文本**原样拼进去。于是日志里出现的是

    [协议字段] max_completion=Parsed(maxTokens=64, ...).maxTokens ...

它编得过、跑得过、单元测试也照样绿（没人断言日志长什么样），
只是那一行读数**没用了** —— 而这一行正是用来和 `[采样]` 那行对账的。

本轮就是这么被抓到的：自测里断言 `describe().contains("max_completion=64")`，
红了才发现输出是上面那串。

判据用 python 而不是 shell 的 grep，因为要匹配的字面量里有 `${` ——
内联进 shell 的双引号层时它会被 shell 自己展开，判据会退化成"文件里出现过 p"
（本仓库在 `release_hygiene` 与筛选的桩里已经栽过两次同款）。

用法：python3 tools/protocol_fields/check_describe_braces.py <ProtocolFields.kt>
退出码：0 = 无退化模板且确实用了花括号形态；1 = 否则
"""
import re
import sys

path = sys.argv[1]
bad = []
braced = 0
for n, line in enumerate(open(path, encoding="utf-8"), 1):
    # `$p.` 紧跟**字母**才算退化：`${p.x}` 里 `{` 不是字母，不匹配。
    # 同时排除注释行（注释里会引用这个反面写法当说明）。
    code = line.split("//", 1)[0]
    if re.search(r"\$p\.[A-Za-z]", code):
        bad.append((n, line.rstrip()))
    if re.search(r"\$\{p\.[A-Za-z]+", code):
        braced += 1

for n, l in bad:
    print("%s:%d: 退化模板（应写 ${p.x}）: %s" % (path, n, l.strip()))
if not bad and braced == 0:
    # 一条 `${p.x}` 都没有 = 判据的前提不成立（describe 被整段删了/改名了），
    # 这时"没有退化模板"是真话但**没有意义**，必须报出来而不是静默通过。
    print("%s: 没有找到任何 ${p.x} 形态的取值 —— describe() 可能被改名或删掉了" % path)
    sys.exit(1)
sys.exit(1 if bad else 0)
