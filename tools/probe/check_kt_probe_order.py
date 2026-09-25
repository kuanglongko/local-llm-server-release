#!/usr/bin/env python3
"""钉「Kotlin 的『关』那次调用不会被提前 return 掉」。

判据写成"传参那一行之前**不得**有 probeEnabled 的提前返回"：
只断言"文件里没有 `if (!probeEnabled) return null`"是错的 —— 修复后这一行
本来就得留着（它是"关着就别报挂载失败"的正确处理），只是必须排在调用**之后**。
用行号比较，而不是"某行在不在"。
"""
import re
import sys

src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"fun startProbe\(.*?\n    \}", src, re.S)
if not m:
    print("startProbe 没找到", file=sys.stderr)
    sys.exit(2)
# 去掉行内注释，免得注释里提到这些串
body = "\n".join(line.split("//")[0] for line in m.group(0).splitlines())
call = body.find("nativeProbeInit(dir.absolutePath")
early = body.find("if (!probeEnabled) return null")
if call < 0:
    print("startProbe 里没有调 nativeProbeInit(dir.absolutePath, ...)", file=sys.stderr)
    sys.exit(1)
if early >= 0 and early < call:
    print("!probeEnabled 的提前返回排在 nativeProbeInit 调用之前 —— off 那次调用发不出去",
          file=sys.stderr)
    sys.exit(1)
sys.exit(0)
