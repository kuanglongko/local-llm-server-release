#!/usr/bin/env python3
"""递归保护必须在**任何写日志之前**。

为什么：`probe_signal` 的第一件事若是写日志，而写日志本身又崩了（fd 已坏 /
缓冲越界），就会再次进入 handler，而 `g_in_handler` 还没置位 ——
于是无限递归，直到栈耗尽再崩一次，现场只剩最后一层。
旧写法本来就是对的（先判 `g_in_handler` 再写），这条断言把它钉住：
判据是**顺序**，不是"文件里有没有 g_in_handler"。
"""
import sys


def fnbody(src: str, sig: str) -> str:
    i = src.index(sig)
    j = src.index("\n}\n", i) + 3
    return src[i:j]


def main(path: str) -> int:
    src = open(path, encoding="utf-8").read()
    body = fnbody(src, "static void probe_signal")
    lines = [ln.split("//", 1)[0] for ln in body.splitlines()]
    guard = flags = log = -1
    for n, ln in enumerate(lines):
        if guard < 0 and "if (g_in_handler)" in ln:
            guard = n
        if guard >= 0 and flags < 0 and "g_in_handler = 1;" in ln:
            flags = n
        if guard >= 0 and log < 0 and ("jp(" in ln):
            log = n
    if guard < 0:
        print("FAIL  探针信号守卫：probe_signal 里没有递归保护")
        return 1
    if flags < 0:
        print("FAIL  探针信号守卫：递归保护的进入标志没置位")
        return 1
    if log >= 0 and log < flags:
        print("FAIL  探针信号守卫：写日志（第 %d 行）排在递归保护置位（第 %d 行）之前 —— "
              "日志本身再崩就是无限递归" % (log + 1, flags + 1))
        return 1
    print("PASS  探针信号守卫：递归保护先于任何写日志（guard=%d flags=%d log=%d）"
          % (guard, flags, log))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
