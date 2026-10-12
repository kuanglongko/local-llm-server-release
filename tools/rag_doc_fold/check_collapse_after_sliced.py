#!/usr/bin/env python3
"""「折叠状态必须是 [setRagDocSliced] 的最后一笔」的次序判据（ISSUE #228 第 ② 条）。

═══════════════════════════════════════════════════════════════════════════
这一条修的是什么：一个**纯时序**的覆盖
═══════════════════════════════════════════════════════════════════════════
正文框在 [field] 里是用 `setSingleLine(true)` 建出来的，而
[setRagDocSliced] 里那一句 `setSingleLine(false)` 会**顺手把 `maxLines`
复位成「不限」**（Android 的 `applySingleLine` 语义：关掉单行 = 回到
`Integer.MAX_VALUE`）。

于是每一条"先填正文、再切切片态"的路（[loadRagDoc] / [renderRagDocSlice]）
都长成这样：

    setRagDocText(...)    // 内部 paintRagDocFold()：收起态 maxLines = 3   ✅
    setRagDocSliced(...)  // setSingleLine(false)：maxLines 打回 MAX_VALUE  ❌

净效果：字段 `ragDocCollapsed` 是 `true`（按钮写「▶ 展开正文框」、说明行写
「正文框已收起」），而框**在屏幕上其实是展开的** —— 两个信号打架，用户看到
的那个（框高）说"展开"。用户报的原文：*"提示说已收起，但事实上它自动处于
打开状态，要收起来得点两次"*（第一次点 `setRagDocCollapsed(false)` 因为字段
还说收起 -> 框"展开"（本就没收起，看不出变化）；第二次才真的收掉）。

═══════════════════════════════════════════════════════════════════════════
为什么判据只能钉"次序"，钉不了"看起来对不对"
═══════════════════════════════════════════════════════════════════════════
这两句谁在运行时最后说话，决定 `maxLines` 的终值 —— 而"终值对不对"没有任何
编译错误、没有异常、静态字符串也看不出来（`maxLines` 与 `paintRagDocFold`
在文件里都出现过）。唯一能钉住的形态是**函数体内的次序**：
[setRagDocSliced] 的最后一次写入必须是 `paintRagDocFold()`。

⚠ 反过来（在 `setRagDocSliced` **之前**画）恒无效：`setSingleLine(false)`
   恒在它之后覆盖。所以判据认的就是"**之后**"这个位置。

纯静态断言，不依赖工具链。运行：
  python3 tools/rag_doc_fold/check_collapse_after_sliced.py <EngineActivity.kt>
"""
import re
import sys


def body(src_lines, sig):
    """抓某个函数/方法的花括号体（与 run_rag_doc_fold_guard.sh 的 body() 同口径）。"""
    out = []
    depth = 0
    start = False
    pat = re.compile(sig)
    for i, l in enumerate(src_lines):
        if not start and pat.search(l):
            start = True
            out.append((i, l))
            depth += l.count("{") - l.count("}")
            continue
        if start:
            out.append((i, l))
            depth += l.count("{") - l.count("}")
            if depth == 0:
                break
    return out


def strip_comments(lines):
    """去掉整行注释（判据只认代码，注释里出现 `paintRagDocFold()` 恒真）。"""
    return [(i, l) for i, l in lines if not l.lstrip().startswith(("//", "*", "/*"))]


def main():
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    b = strip_comments(body(lines, r"private fun setRagDocSliced\b"))
    if not b:
        print("FAIL  找不到 setRagDocSliced()（锚点漂了）")
        return 1

    # 最后一次写入：`setSingleLine(` 与 `paintRagDocFold()` 的相对位置。
    single = [i for i, l in b if "setSingleLine(" in l]
    paint = [i for i, l in b if "paintRagDocFold()" in l]
    if not single:
        print("FAIL  setRagDocSliced() 里没有 setSingleLine( ——"
              "锚点漂了（这一条判据认的就是它与 paintRagDocFold 的先后）")
        return 1
    if not paint:
        print("FAIL  setRagDocSliced() 里没有 paintRagDocFold() ——"
              "`setSingleLine(false)` 之后没有任何一处把 maxLines 画回去，"
              "于是字段说收起、而框在屏幕上展开（用户点两次才收得起来）")
        return 1
    last_single = max(single)
    last_paint = max(paint)
    if last_paint > last_single:
        print(f"PASS  setRagDocSliced() 里 paintRagDocFold() 在 setSingleLine() 之后"
              f"（第 {last_paint + 1} 行 > 第 {last_single + 1} 行）——"
              f"折叠状态是最后说话的那一笔")
        return 0
    print(f"FAIL  setRagDocSliced() 里 paintRagDocFold() 在 setSingleLine() **之前**"
          f"（第 {last_paint + 1} 行 < 第 {last_single + 1} 行）——"
          f"`setSingleLine(false)` 会把 maxLines 打回 MAX_VALUE，"
          f"覆盖掉收起态（字段说收起、框其实展开）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
