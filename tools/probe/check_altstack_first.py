#!/usr/bin/env python3
"""K-2 判据：备用信号栈必须在**第一次**设置 SA_ONSTACK 之前装好。

为什么不能只看"文件里有没有 sigaltstack"：
  `probe_install_alt_stack()` 若排在写 `sa_flags` **之后**，第一次安装时
  `g_alt_stack_ready` 还是 false -> sa_flags 里没有 SA_ONSTACK ->
  而备用栈是在装完之后才挂上的 —— 这一轮 handler **永远**拿不到备用栈，
  直到第二次安装（而第二次又会被幂等闸门挡掉）—— 等于装了个永不生效的备用栈。

判据（结构关系）：函数体里 `probe_install_alt_stack()` 的调用位置必须早于
第一次出现 `sa_flags` 赋值的位置。
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
    call = body.find("probe_install_alt_stack()")
    flags = body.find("sa_flags")
    if call < 0:
        print("FAIL  探针信号守卫：probe_install_signals 里没有装备用栈")
        return 1
    if flags < 0:
        print("FAIL  探针信号守卫：找不到 sa_flags 赋值（函数形状变了？）")
        return 1
    if call > flags:
        print("FAIL  探针信号守卫：备用栈装在 sa_flags 之后（%d > %d）—— "
              "第一次安装拿不到备用栈，SA_ONSTACK 形同虚设" % (call, flags))
        return 1
    print("PASS  探针信号守卫：备用栈先于 sa_flags（%d < %d）" % (call, flags))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
