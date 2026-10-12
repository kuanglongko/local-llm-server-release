#!/usr/bin/env python3
"""「清掉截断旗标之后也重画按钮」的判据。

═══════════════════════════════════════════════════════════════════════════
为什么这一条要单写一个脚本
═══════════════════════════════════════════════════════════════════════════
清旗标那一处在 `ragDocEt` 的 `TextWatcher.afterTextChanged` 里 —— 一个**匿名
对象内部的回调**。`awk` 那种"按大括号配平取函数体"的取体法在这里取不准
（它认不出 lambda 的边界），取歪了判据就恒绿/恒红，而两者都看不出来。

判据要钉的是一个**次序关系**：在同一个 `afterTextChanged` 体里，
`ragDocClipped = false` 之后必须出现 `paintRagDocFold()`。两者之间隔着别的语句
是允许的（目前就没有），但**只有清旗标那一处**必须配一次重画 ——
旗标正是按钮上"只是前一部分"那句话的唯一来源。
"""
import re
import sys


def main():
    src = sys.argv[1]
    with open(src, encoding="utf-8") as f:
        text = f.read()

    # 取每一个 afterTextChanged 的体（按大括号配平；这个回调体里没有字符串
    # 含大括号的形态，所以朴素的配平是够的 —— 取不准会走下面的 FAIL 分支，
    # 而失败方向偏"报红"而不是"静默绿"）。
    hits = []
    for m in re.finditer(r"override fun afterTextChanged\([^)]*\)\s*\{", text):
        i = m.end() - 1
        depth = 0
        j = i
        while j < len(text):
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        hits.append(text[i:j])

    if not hits:
        print("FAIL  找不到 afterTextChanged 的回调体（锚点漂了）")
        return 1

    body = "\n".join(hits)
    flag = body.find("ragDocClipped = false")
    if flag < 0:
        print("FAIL  afterTextChanged 里没有清掉 ragDocClipped（判据锚点漂了 —— "
              "用户手动补全尾巴后仍会被当‘只是前一部分’拦住）")
        return 1
    repaint = body.find("paintRagDocFold()", flag)
    if repaint < 0:
        print("FAIL  清掉 ragDocClipped 之后没有 paintRagDocFold() —— "
              "按钮会一直挂着‘只是前一部分’（假警告）")
        return 1
    print("PASS  清掉 ragDocClipped 之后跟着重画了一次按钮（旗标与按钮文案同生同灭）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
