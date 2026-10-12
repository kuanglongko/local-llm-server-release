#!/usr/bin/env python3
"""判：文件里每个 `Thread {` / `Thread({` 创建点的 `.start()` 都带了 `isDaemon = true`。

为什么锚"每个创建点的链尾"而不是"文件里有 isDaemon"：
  HttpApi 里有 3 个线程（连接 / listener / watchdog），漏标一个 = Service 停止后
  JVM 被那个非守护线程吊住、进程不退，而"文件里有 isDaemon"照样为真。
"""
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().splitlines()
bad = 0
n = 0
for i, l in enumerate(lines):
    if "currentThread" in l:
        continue
    if not re.search(r"Thread\s*[({]", l):
        continue
    n += 1
    # 从创建点向下找 `.start()`（跨行 lambda 可能很长，放宽到 200 行）
    seg = None
    for j in range(i, min(i + 200, len(lines))):
        if ".start()" in lines[j]:
            seg = "\n".join(lines[i:j + 1])
            break
    if seg is None:
        print(f"{path}:{i + 1}: 没找到该 Thread 的 .start()")
        bad += 1
        continue
    if "isDaemon = true" not in seg:
        print(f"{path}:{i + 1}: Thread 创建点没标 isDaemon（Service 停后进程可能不退）")
        bad += 1
print(f"Thread 创建点 {n} 个，漏标 daemon {bad} 个")
sys.exit(1 if bad else 0)
