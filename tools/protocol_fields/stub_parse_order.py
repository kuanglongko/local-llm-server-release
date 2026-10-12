#!/usr/bin/env python3
"""桩：把**第一个**生成端点（handleChat）里的协议字段解析挪到采样参数解析**之后**。

═══════════════════════════════════════════════════════════════════════
这条桩测的是什么
═══════════════════════════════════════════════════════════════════════
顺序反了的后果**不是报错**：`SamplingParams` 会先按 `max_tokens` 的缺省填成
512，而它那一步看不到 `max_completion_tokens`（别名要在外面先解再传进去）。
于是别名在那个端点上**完全失效**，而请求照 200、响应看起来一切正常。

单独成文件而不是内联进 `run_protocol_fields_guard_tests.sh` 的 heredoc：
源码那几行里既有 `?:` 又有转义引号，穿过三层引号层（sh 单引号 -> python
三引号 -> 源码字面量）时**桩自己会先写坏**，而那不是被守卫抓住的。

只改第一个端点（不是两个都改）：判据要能发现"**其中一个**写反"——
两个端点对同一字段给不同答案，是本仓库最忌讳的分叉形态。

用法：stub_parse_order.py <HttpApi.kt>
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().split("\n")

# 1) 找第一个 `val (pf, pfErr) = ProtocolFields.fromRequest(j)` 及其紧跟的守卫行。
i = None
for n, l in enumerate(lines):
    if "val (pf, pfErr) = ProtocolFields.fromRequest(j)" in l:
        i = n
        break
if i is None:
    print("桩⑩：没找到 pf 解析行")
    sys.exit(1)

# 2) 其后紧跟的那一行必须是 `if (pf == null) { ... }`（同一缩进、单行）。
if "if (pf == null)" not in lines[i + 1]:
    print("桩⑩：pf 解析行后面不是那行守卫（源码形状变了？）")
    sys.exit(1)

block = [lines[i], lines[i + 1]]
# 3) 找同一函数里 `val (sp, spErr) = SamplingParams.fromRequest(j, aliasMaxTokens = pf.maxTokens)`。
j = None
for n in range(i + 2, len(lines)):
    if "val (sp, spErr) = SamplingParams.fromRequest(j, aliasMaxTokens = pf.maxTokens)" in lines[n]:
        j = n
        break
    if re.match(r"\s*private fun ", lines[n]):
        break
if j is None:
    print("桩⑩：没找到 sp 解析行")
    sys.exit(1)

# 4) 删掉原位置，插到 sp 那一行之后。
del lines[i:i + 2]
j -= 2
lines[j + 1:j + 1] = block

open(path, "w", encoding="utf-8").write("\n".join(lines))
print("poked")
