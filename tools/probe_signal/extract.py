#!/usr/bin/env python3
"""从真源码**逐字抽取**信号 handler 链那一段，生成宿主行为测用的 .inc。

为什么是抽取而不是维护副本：副本会随源码漂移，那时测的是一份没人看的旧代码。
本脚本与源码形状绑定 —— 改函数名/签名时这里会直接失败（这正是想要的）。

用法：extract.py <llama_jni.cpp> <out.inc>
      extract.py --old <llama_jni.cpp> <out.inc>
"""
import sys

mode_old = len(sys.argv) > 3 and sys.argv[1] == "--old"
if mode_old:
    src, out = sys.argv[2:4]
else:
    src, out = sys.argv[1:3]

SRC = open(src, encoding='utf-8').read()


def body(sig, label):
    """从**定义**处抓函数体。签名后面 400 字符内出现 `) {` 才算定义。"""
    i = -1
    while True:
        try:
            i = SRC.index(sig, i + 1)
        except ValueError:
            sys.exit("抽取失败：源码里找不到定义 `%s`（%s）—— 函数改名/签名改了？"
                     "本脚本与源码形状绑定，请一起更新" % (sig, label))
        if ') {' in SRC[i:i + 400]:
            break
    j = SRC.index('\n}\n', i) + 3
    return SRC[i:j]


def span(start_sig, end_sig, label):
    try:
        i = SRC.index(start_sig)
    except ValueError:
        sys.exit("抽取失败：找不到 `%s`（%s）" % (start_sig, label))
    j = SRC.index(end_sig, i) + len(end_sig)
    return SRC[i:j]


handler = body("static void probe_signal(int sig", "K 段：信号 handler")
if mode_old:
    # ⚠ 旧版这一段的形状：
    #   · handler 里 SIG_IGN 走 `old->sa_handler(sig)` + `_exit(128+sig)`；
    #   · 没有 probe_install_alt_stack；
    #   · probe_install_signals **没有**幂等闸门（每次都覆盖 g_old_*）。
    # 所以旧单元只抽 handler（安装那两段在旧版里形状不同，抽不出来也不该抽）。
    # 抽取的东西必须是**逐字节的真旧代码** —— 这一点由调用方先把旧版本文件落盘保证。
    parts = [handler]
else:
    parts = [
        handler,
        body("static void probe_install_alt_stack()", "K-2：备用栈"),
        body("static void probe_install_signals()", "K-1：安装"),
    ]

open(out, "w", encoding="utf-8").write("\n\n".join(parts))
