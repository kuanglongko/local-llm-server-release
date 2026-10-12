#!/usr/bin/env python3
"""两个生成端点里，`ProtocolFields.fromRequest` 必须都排在 `SamplingParams.fromRequest` **之前**。

═══════════════════════════════════════════════════════════════════════
为什么顺序是判据（而不是"风格问题"）
═══════════════════════════════════════════════════════════════════════
`max_completion_tokens` 是 `max_tokens` 的**别名**，而"两个字段名指向同一个量、
同时给出且取值不同要 400"这条判据需要**同时看到两个字段**。`SamplingParams`
刻意是逐字段结构（一次只看自己那一格），所以别名必须在外面先解、再把结果
传进去（`aliasMaxTokens = pf.maxTokens`）。

顺序反了的后果不是报错，而是**静默失效**：`SamplingParams` 会先按
`max_tokens` 的缺省填成 512，而它那一步看不到别名 —— 于是客户端写的
`max_completion_tokens: 100` 完全不生效，而请求照 200、响应照旧看起来正常。

为什么"逐端点各判一次"而不是"文件里某处 pf 在 sp 之前"：
后者在**其中一个**端点写反时照样绿，而那个端点上别名就是失效的 ——
两个端点对同一字段给不同答案，是本仓库最忌讳的分叉形态。

用法：check_parse_order.py <HttpApi.kt>
退出码：0 = 两个端点顺序都对；1 = 有端点顺序反了（打印行号）
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().split("\n")

# 每个生成端点函数体的范围：从 `private fun handleX(` 到下一个同缩进的 `private fun`。
starts = []
for i, l in enumerate(lines):
    if re.match(r"\s*private fun handle(Chat|Completion)\(", l):
        starts.append((i, l.strip()))
starts.append((len(lines), ""))  # 哨兵

bad = []
for k in range(len(starts) - 1):
    a, name = starts[k]
    b = starts[k + 1][0]
    body = lines[a:b]
    pf = None
    sp = None
    for off, l in enumerate(body):
        code = l.split("//", 1)[0]
        if pf is None and "ProtocolFields.fromRequest(j)" in code:
            pf = a + off + 1
        if sp is None and "SamplingParams.fromRequest(j" in code:
            sp = a + off + 1
    if pf is None or sp is None:
        bad.append((name, "找不全两处调用（pf=%s sp=%s）" % (pf, sp)))
        continue
    if pf > sp:
        bad.append((name, "pf 在第 %d 行、sp 在第 %d 行 —— 顺序反了" % (pf, sp)))

if len(starts) - 1 != 2:
    print("%s: 找到 %d 个生成端点（应为 2：handleChat / handleCompletion）" % (path, len(starts) - 1))
    sys.exit(1)
for name, why in bad:
    print("%s: %s —— %s" % (path, name, why))
sys.exit(1 if bad else 0)
