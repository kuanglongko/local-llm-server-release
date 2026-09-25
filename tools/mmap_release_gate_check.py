#!/usr/bin/env python3
"""release_populated_pages 的**调用归属**检查：调用点必须落在
`if (useMmap == JNI_TRUE)` 的块内 —— 判归属，不判存在。

为什么单独成脚本（而不是守卫里的 awk 一行）：
  · 本文件是 **C++**，判据要么按花括号深度走、要么就会被函数体里
    `#if / #else / #endif` 这种**行首 `}`** 截断（本模块真踩过：
    release_populated_pages 的 `fopen` 失败分支就落在行首 `}` 上）；
  · 守卫用 `eval "$2"` 执行判据，awk 程序会被 shell 再展开一次，
    `/[{]/`、`\/\/` 这类转义在 shell 与 awk 之间来回丢，实测反复出错；
  · 之前的"紧邻下一行"判据太窄：调用点前多几个自证用的声明就会被误红。

退出码：0 = 归属正确；1 = 不在门控内 / 找不到门控。
"""
import sys

SRC = "app/src/main/cpp/llama_jni.cpp"
ENTRY = "Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel"
CALLEE = "release_populated_pages("


def fn_body(src, needle):
    """取以 needle 所在处开始的函数体（按花括号深度收尾）。"""
    i = src.index(needle)
    i = src.index("{", i)
    d = 0
    j = i
    while j < len(src):
        if src[j] == "{":
            d += 1
        elif src[j] == "}":
            d -= 1
            if d == 0:
                return src[i:j + 1]
        j += 1
    raise SystemExit("找不到 %s 的函数体（源码形状变了，本检查要一起更新）" % needle)


def main():
    src = open(SRC, encoding="utf-8").read()
    body = fn_body(src, ENTRY).split("\n")

    gates = [k for k, l in enumerate(body)
             if l.strip().startswith("if") and "useMmap == JNI_TRUE" in l]
    calls = [k for k, l in enumerate(body)
             if CALLEE in l and "static" not in l]
    if not gates:
        print("找不到 useMmap 门控（调用点无归属可言）")
        return 1
    if not calls:
        print("找不到 %s 的调用点" % CALLEE)
        return 1

    inside = []
    for gk in gates:
        k, d, started = gk, 0, False
        while k < len(body):
            d += body[k].count("{") - body[k].count("}")
            if "{" in body[k]:
                started = True
            if started and d == 0:
                break
            k += 1
        for ck in calls:
            if gk < ck <= k:
                inside.append(ck)

    if len(inside) == len(calls) and inside:
        print("调用点 %d 处，全部落在 useMmap 门控块内" % len(inside))
        return 0
    print("调用点未落在 useMmap 门控块内（调用 %d 处，命中 %d 处）" % (len(calls), len(inside)))
    return 1


sys.exit(main())
