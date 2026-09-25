#!/usr/bin/env python3
"""K-1 判据：`g_old_*` 的写入必须排在"只装一次"的闸门**之后**。

为什么不能只用 grep：
  `grep -q 'if (g_signals_installed)'` 恒真于失效状态 —— 闸门那一行在、
  `sigaction(SIGSEGV, &sa, &g_old_segv)` 那一行也在，但**顺序**若写反
  （先 sigaction 再查闸门），第二次安装照样把 `g_old_segv` 覆盖成探针自己，
  转发链成一个环，信号现场全丢。而"现场全丢"与"根本没崩 signal"同形。

判据（结构关系）：
  1. 函数体里必须先出现 `if (g_signals_installed) {`（闸门）；
  2. `g_signals_installed = true;`（置闸门）必须晚于闸门判断；
  3. 所有 `&g_old_` 实参的出现位置必须晚于闸门判断 —— 这是本条真正要钉的东西。
"""
import sys


def fnbody(src: str, sig: str) -> str:
    i = src.index(sig)
    j = src.index("\n}\n", i) + 3
    return src[i:j]


def strip_comments(body: str) -> str:
    out = []
    for ln in body.splitlines():
        s = ln.split("//", 1)[0]
        if s.strip().startswith("*"):
            continue
        out.append(s)
    return "\n".join(out)


def main(path: str) -> int:
    src = open(path, encoding="utf-8").read()
    body = strip_comments(fnbody(src, "static void probe_install_signals()"))

    gate = body.find("if (g_signals_installed) {")
    if gate < 0:
        print("FAIL  探针信号守卫：probe_install_signals 里没有一次性闸门")
        return 1

    setgate = body.find("g_signals_installed = true;")
    if setgate < 0 or setgate < gate:
        print("FAIL  探针信号守卫：闸门置位缺失或排在判断之前")
        return 1

    # 第一个 &g_old_ 实参的位置必须晚于闸门。
    first_old = body.find("&g_old_")
    if first_old < 0:
        print("FAIL  探针信号守卫：函数体里一个 g_old_* 都没装（链式转发已丢）")
        return 1
    if first_old < gate:
        line = body[:first_old].count("\n") + 1
        print("FAIL  探针信号守卫：&g_old_* 出现在闸门之前（第 %d 行）—— "
              "第二次安装会覆盖 g_old_*，转发链成环，信号现场全丢" % line)
        return 1

    print("PASS  探针信号守卫：g_old_* 的写入全部排在一次性闸门之后（顺序 %d < %d）"
          % (gate, first_old))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
