#!/usr/bin/env python3
"""`logit_bias` 超上限时**只能截断 + 留痕**，不许 return 400。

═══════════════════════════════════════════════════════════════════════
为什么这条有资格单独占一个脚本
═══════════════════════════════════════════════════════════════════════
`StopSequences.MAX_SEQUENCES` 那段已经用一次**真实事故**说明过这条分界：
把"条数多"当成协议错误报 400，会让一个业务上完全正常的客户端**每个请求**都被拒，
表现为"完全无法生成"。而它与本特性引入前的行为是**反向**的 ——
那时字段被静默忽略、请求照跑。也就是说"补齐一个协议字段"反而把能用的客户端弄挂了。

所以条数是**性能边界**，不是合法性边界：只截断 + 落日志。
本脚本判的是"超限那一支里没有 return null / return ... to err"这类**早退**。

判据有意做成**窄**的（只看超限分支那几行）：宁可漏，不要误报 ——
一个会误报的守卫很快会被 `|| true` 静音，那时它保护的与它自己都一起失效。

用法：check_no_400_on_trim.py <ProtocolFields.kt>
退出码：0 = 超限分支里没有 early return；1 = 有（打印行号）
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().split("\n")

start = None
for i, l in enumerate(lines):
    if ("bias.size > MAX_LOGIT_BIAS" in l) or ("ordered.size > MAX_LOGIT_BIAS" in l):
        start = i
        break
if start is None:
    print("%s: 找不到超限分支（bias.size > MAX_LOGIT_BIAS）" % path)
    sys.exit(1)

# 取该分支起 8 行（够覆盖 if 体 + 收尾），找早退。
bad = []
for i in range(start, min(start + 8, len(lines))):
    code = lines[i].split("//", 1)[0]
    if re.search(r"\breturn\b.*\bnull\b", code) or re.search(r"return\s+null", code):
        bad.append((i + 1, lines[i].strip()))

if bad:
    for n, l in bad:
        print("%s:%d: 超限分支里有 early return（应只截断 + 留痕）: %s" % (path, n, l))
    sys.exit(1)
sys.exit(0)
