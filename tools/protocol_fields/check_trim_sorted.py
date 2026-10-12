#!/usr/bin/env python3
"""`logit_bias` 超限截断**必须取自按 token id 升序排过的那一份**。

═══════════════════════════════════════════════════════════════════════
为什么这条有资格单独占一个脚本
═══════════════════════════════════════════════════════════════════════
`bias` 是按 `o.keys()` 的顺序攒的，而 `logit_bias` 是 JSON **对象**：
`org.json` 内部是 `java.util.HashMap`，`keys()` 的迭代顺序是 **String 的哈希序**
—— 既不是插入序，也不是字典序。直接 `bias.subList(0, MAX_LOGIT_BIAS)`
取到的是"哈希序前 300 个"，而日志写的是"已只保留前 300 条"：

  实测（插 302 条，id 0..301）：丢弃 id = [109, 229]，保留 id = 300/301。
  调用方看到日志会以为"要的 0..299 都在、只少了 300/301"。

而 native 侧 `llama_sampler_init_logit_bias` 收的是一组 `(id -> bias)` 对，
**集合成员**才是语义、顺序无关 —— 所以"哪 300 条活下来"直接决定输出。

这条**不是** `grep -q sortedBy`（那是存在性断言，本仓库反复踩过）：
它判的是"截断取的那一份**是排过序的**"——即 `subList` 的**接收者**
必须是排序后的名字，而不是原始 `bias`。

用法：check_trim_sorted.py <ProtocolFields.kt>
退出码：0 = 截断取自排序后的一份；1 = 取的是原始顺序（打印行号）
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().split("\n")

# 1) 找超限分支，定位它所在的行号。
start = None
for i, l in enumerate(lines):
    if "bias.size > MAX_LOGIT_BIAS" in l or "ordered.size > MAX_LOGIT_BIAS" in l:
        start = i
        break
if start is None:
    print("%s: 找不到超限分支（*.size > MAX_LOGIT_BIAS）" % path)
    sys.exit(1)

# 2) 该分支之前必须有一次 `sortedBy { ... tokenId ... }`，且绑到一个名字上。
sorted_name = None
for l in lines[: start + 1]:
    code = l.split("//", 1)[0]
    m = re.search(r"\bval\s+(\w+)\s*=\s*\w*\.?sortedBy\s*\{([^}]*)\}", code)
    if m and "tokenId" in m.group(2):
        sorted_name = m.group(1)
if sorted_name is None:
    print("%s:%d: 超限分支之前没有 `sortedBy { it.tokenId }` —— "
          "subList 取到的是 HashMap 的哈希序子集，与日志说的「前 N 条」不符"
          % (path, start + 1))
    sys.exit(1)

# 3) 该分支里截断的**接收者**必须是那个排序后的名字，不能是原始 `bias`。
body = lines[start : min(start + 8, len(lines))]
bad = []
for i, l in enumerate(body):
    code = l.split("//", 1)[0]
    # `bias.subList(...)` 这种取原始顺序的写法一律拦下。
    if re.search(r"\bbias\.subList\s*\(", code):
        bad.append((start + i + 1, l.strip()))
if not any(re.search(r"\b%s\.subList\s*\(" % re.escape(sorted_name), l.split("//", 1)[0])
           for l in body):
    bad.append((start + 1, "超限分支里没有对 `%s.subList(...)` 的截断" % sorted_name))

if bad:
    for n, l in bad:
        print("%s:%d: 截断取的不是排序后的那一份: %s" % (path, n, l))
    sys.exit(1)
sys.exit(0)
