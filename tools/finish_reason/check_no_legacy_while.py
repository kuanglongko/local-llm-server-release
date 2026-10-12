#!/usr/bin/env python3
"""旧的 `while (n < maxTok)` 循环形态不能在**代码行**里复活。

═══════════════════════════════════════════════════════════════════════
为什么单开一个脚本（而不是一条 grep）
═══════════════════════════════════════════════════════════════════════
这一格的判据必须是"真实代码行"，不能是"文件里出现过这个串"：

`while (n < maxTok)` 这个旧写法会**出现在注释里**，而且是**有意**留的 ——
`HttpApi.kt` 里那段说明"为什么把上限判定移进循环体"的注释正好引用了它。
锚串的判据会把这段注释判红（本仓库在 `run_schema_sampler_guard.sh` 里
刚栽过一次同款：锚"开头就是 thinkingOn"导致每次加形参都误红）。
所以这里剥掉注释行与行尾注释，只看**代码**。

用法：
  check_no_legacy_while.py <kt 文件>
退出码：0 = 代码行里没有旧形态；1 = 有（或有裸 while 没判上限）
"""
import re
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()

bad = []
for i, line in enumerate(src.split("\n"), 1):
    stripped = line.strip()
    # 注释行整行跳过（// 开头、* 开头、块注释边界）
    if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
        continue
    # 行尾注释剥掉（本仓库注释里出现 ` ` 的地方都在 // 之后）
    code = line.split("//")[0]
    if re.search(r"while\s*\(\s*n\s*<\s*maxTok\s*\)", code):
        bad.append((i, stripped))

if bad:
    for i, l in bad:
        print("%s:%d: 旧形态 `while (n < maxTok)` 复活了（上限判定要留在体内最前，"
              "否则「跑满」这件事只剩收尾反推）" % (path, i))
    sys.exit(1)
print("ok")
