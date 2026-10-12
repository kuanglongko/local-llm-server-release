#!/usr/bin/env python3
"""nativeKvCacheStats 的形状检查：`kv_rounds` 必须真的在那个数组里，
且**容量 == 写入长度**（不等就是越界写，真机上直接踩内存）。

为什么单独成脚本而不是守卫里一行 awk：
  · 本文件是 **C++**，判据要么按函数体取、要么就会被相邻的同名函数/注释带偏；
  · 守卫用 `eval "$2"` 执行判据，awk 程序会被 shell 再展开一次 —— `/\(/`、
    `\\"` 这类转义在 shell 与 awk 之间来回丢，实测反复产不出可读的失败信息
    （这正是 tools/mmap_release_gate_check.py 单独立文件的同一条理由）。
  · 旧判据写的是 `grep -q 'NewIntArray(3)'`：**长度写死**。这个数组是追加式的
    （E-5 加 kv_rounds 时 2→3，加 last_lcp 时 3→4），于是每一次合法追加都会
    让它红在自己的措辞上 —— 判据漂移，即"守卫讨厌某种写法"而不是"代码坏了"。

退出码：0 = 形状正确；1 = 数组里没有 kv_rounds / 容量与写入长度不一致。
"""
import re
import sys

SRC = "app/src/main/cpp/llama_jni.cpp"
ENTRY = "Java_com_xiaowan_localinference_LlmEngine_nativeKvCacheStats"


def fn_body(src, needle):
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
    body = fn_body(open(SRC, encoding="utf-8").read(), ENTRY)
    # 剥注释：否则注释里的 "kv_rounds" 会让判据恒真。
    body = re.sub(r"//[^\n]*", "", body)

    m_cap = re.search(r"NewIntArray\((\d+)\)", body)
    m_len = re.search(r"SetIntArrayRegion\(a,\s*0,\s*(\d+),", body)
    if not m_cap or not m_len:
        print("找不到 NewIntArray / SetIntArrayRegion（判据要一起更新）")
        return 1
    cap, ln = int(m_cap.group(1)), int(m_len.group(1))
    if cap != ln:
        print("数组容量 %d != 写入长度 %d —— 越界写" % (cap, ln))
        return 1
    # kv_rounds 必须在**初始化列表那一行**里（不是文件别处出现过）。
    m_init = re.search(r"jint\s+v\[\d+\]\s*=\s*\{(.*?)\};", body, re.S)
    if not m_init or "S.kv_rounds" not in m_init.group(1):
        print("初始化列表里没有 S.kv_rounds（E-5 的第三个读数丢了）")
        return 1
    print("kv_rounds 在数组内，容量 %d == 写入长度 %d" % (cap, ln))
    return 0


sys.exit(main())
