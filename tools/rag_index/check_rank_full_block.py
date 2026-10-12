#!/usr/bin/env python3
"""rank 的「全挡」那一格有没有**执行力**。

判据锚的是**测试用例自己的数据与阈值**，而不是"文件里出现过 1.00001f"：

  ① 矩阵里的最高分（`val m = floatArrayOf(...)` 两维成对、query = (1,0)，
     于是点积 = 每对的第一个分量）；
  ② 被当作"全挡"用的那个阈值 —— 取 testRank() 里最后一条 `rank(...)` 的
     第 6 个实参；
  ③ 那条断言期望的 hits 数。

只有当 `阈值 > 最高分` 且 `期望 hits == 0` 时，这一格才真的在测"全挡"。
`0.9999f` 就栽在这里：精确值是 0.99989998…（**严格小于** 1.0），
`1.0f >= 0.9999f` 为真，row0 照样命中 —— 那一格从落地起就没进过全挡分支，
于是"全挡 -> 空"实测 hits=1（红）、"全挡时 above=0"实测 above=1（红），
而夹在中间的"best 仍是 1.0"恰好为真，正好把"这格没在测全挡"盖住了。

为什么不直接 grep `1.00001f`：那是字面锚，等价于"把当前写法抄进判据"，
换一个同样正确的阈值（1.5f）会被判成坏。这里比的是**数值关系**。

退出码 0 = 该格有执行力；1 = 没有；2 = 判据自身锚点漂了。
"""
import io
import re
import sys

TEST = "tools/rag_index/RagIndexStoreTest.kt"


def last_rank_threshold(body: str):
    """取 testRank() 里最后一条 `rank(...)` 的 minScore（最后一个实参）。

    不能写 `rank\((.*?)\)`：实参里含 `floatArrayOf(1f, 0f)`，那个 `(` 会让
    非贪婪匹配在**第一个** `)` 就收工（实测把实参切成了 `floatArrayOf(1f`、
    `0f` —— 于是第 6 个"实参"根本不存在）。从行尾倒着取 `, <数字>f)` 才稳。
    """
    # 只看**带「全挡」字样的注释所管辖的那一段**（注释行之后紧跟的调用），
    # 否则会取到 body 末尾随便哪个 `, 0f)`（比如 NaN 那格），量纲完全不对。
    lines = body.splitlines()
    anchors = [n for n, ln in enumerate(lines) if "全挡" in ln and "-> 空" in ln]
    if not anchors:
        return None
    # 断言行的**上一行**就是那次调用
    for a in reversed(anchors):
        for k in range(a - 1, max(a - 4, -1), -1):
            m = re.search(r",\s*([0-9]*\.?[0-9]+)f\)\s*$", lines[k].strip())
            if m:
                return float(m.group(1))
    return None


def main() -> int:
    s = io.open(TEST, encoding="utf-8").read()
    if "private fun testRank()" not in s:
        print("锚点漂了：testRank() 不见了 —— 本脚本与测试形状绑定，请一起更新")
        return 2
    i = s.index("private fun testRank()")
    j = s.find("\n    private fun ", i + 10)
    body = s[i : j if j > 0 else len(s)]

    m = re.search(r"val m = floatArrayOf\(([^)]*)\)", body)
    if not m:
        print("锚点漂了：testRank() 里找不到 `val m = floatArrayOf(...)`")
        return 2
    vals = [float(x.strip().rstrip("fF")) for x in m.group(1).split(",") if x.strip()]
    if len(vals) % 2:
        print(f"锚点漂了：矩阵维度 ({len(vals)}) 不是 2 的倍数，本脚本按二维点积算")
        return 2
    top = max(vals[k] for k in range(0, len(vals), 2))   # query = (1, 0)

    thr = last_rank_threshold(body)
    if thr is None:
        print("锚点漂了：testRank() 里最后一条 rank(...) 的 minScore 实参认不出来")
        return 2

    # 期望的 hits 数：与"全挡"同段的最后一条 hits.size 断言
    lines = body.splitlines()
    anchors = [n for n, ln in enumerate(lines) if "全挡" in ln and "-> 空" in ln]
    want = None
    if anchors:
        m = re.search(r"r\.hits\.size\.toLong\(\),\s*(\d+)L", lines[anchors[-1]])
        if m:
            want = int(m.group(1))

    ok = True
    if not thr > top:
        print(f"FAIL  用 {thr}f 当「全挡」阈值，但矩阵最高分是 {top} —— "
              f"{top} >= {thr} 为真，这一格**没有**任何一条被挡住")
        ok = False
    if want != 0:
        print(f"FAIL  「全挡」那一格期望 hits = {want}（应为 0）")
        ok = False
    # ⚠ 必须**剥掉注释**再扫：本测试的注释里正在解释"0.9999f 为什么挡不住"，
    # 不剥就是恒假（这个文件里最大的自欺模式就是它 —— 见守卫头部说明）。
    code = "\n".join(
        ln for ln in body.splitlines() if not ln.strip().startswith("//")
    )
    if re.search(r"rank\([^\n]*0\.9999f", code):
        print("FAIL  testRank() 里仍用 0.9999f 当「全挡」阈值 —— 它挡不住 1.0")
        ok = False
    if ok:
        print(f"PASS  「全挡」阈值 {thr} > 矩阵最高分 {top}，且期望 hits = 0（该格有执行力）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
