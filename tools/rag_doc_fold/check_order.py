#!/usr/bin/env python3
"""「折叠按钮挂在正文框**之前**」的次序判据。

═══════════════════════════════════════════════════════════════════════════
为什么这一条只能用"行号比较"来判
═══════════════════════════════════════════════════════════════════════════
按钮与正文框都由 `pageRag.addView(...)` 挂上去，**挂载顺序就是屏幕顺序**。
而"顺序对吗"这件事没有任何运行时报错能告诉你：挂在框**下面**照样能编译、
能点、能收起 —— 只是**展开态下要滑过十几屏才够得着那个按钮**，
而"滑太慢"正是这个功能要解决的问题本身。

⚠ 判据锚的是**挂载那一行**（`pageRag.addView(...)`）之间的**行号先后**，
  不是"文件里谁先出现"：控件**定义**的位置与**挂载**的位置是两件事，
  定义写在前面而挂载写在后面是完全正常的写法（本文件就是这样）。

⚠⚠ 按钮可以挂在**一个行容器里**（ISSUE #259 第 ③ 条：「保存正文」与折叠按钮
   同一行，那一行再挂到 pageRag）—— 所以判据不能死认"pageRag 上直接有
   `addView(<btn>`"。它按**挂载链**上溯：先找按钮挂在哪个容器里，再找那个
   容器挂到 pageRag 的行号。这两跳都必须是**唯一**的，否则说明这一屏的挂载
   关系已经不是判据以为的那个形状（锚点漂了 -> 如实报 FAIL，不猜）。
"""
import re
import sys


def main():
    src = sys.argv[1]
    btn = sys.argv[2]
    box = sys.argv[3]
    with open(src, encoding="utf-8") as f:
        lines = f.readlines()

    def add_lines(name):
        """所有 `pageRag.addView(<name>` 的行号。"""
        pat = re.compile(r"pageRag\.addView\(\s*" + re.escape(name) + r"\b")
        return [i for i, l in enumerate(lines) if pat.search(l)]

    def host_add_line(name):
        """按钮挂在**哪个容器**里、那一行在第几行。返回 (容器名, 行号) 或 None。

        只认 `X.addView(<name>` 这一种形状（本文件里容器都是先建后挂）。
        找到多个宿主容器时返回 None（形状已经不明 -> 让调用方报 FAIL）。
        """
        pat = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\.addView\(\s*"
                         + re.escape(name) + r"\b")
        hits = []
        for i, l in enumerate(lines):
            m = pat.search(l)
            if m and m.group(1) != "pageRag":
                hits.append((m.group(1), i))
        return hits[0] if len(hits) == 1 else None

    # 按钮的挂载行：直接挂在 pageRag 上，或挂在一个行容器里（再上溯一层）。
    b = None
    direct = add_lines(btn)
    if len(direct) == 1:
        b = direct[0]
    else:
        hosted = host_add_line(btn)
        if hosted is not None:
            container = hosted[0]
            up = add_lines(container)
            if len(up) == 1:
                b = up[0]
    x = add_lines(box)
    x = x[0] if len(x) == 1 else None
    if b is None:
        print(f"FAIL  找不到 {btn} 的挂载行（它既没直接挂在 pageRag 上，"
              f"也不在唯一的那个行容器里 —— 锚点漂了）")
        return 1
    if x is None:
        print(f"FAIL  找不到 pageRag.addView({box}...) 这一行（锚点漂了）")
        return 1
    if b < x:
        print(f"PASS  {btn} 挂在 {box} **之前**（第 {b + 1} 行 < 第 {x + 1} 行）")
        return 0
    print(f"FAIL  {btn} 挂在了 {box} **之后**（第 {b + 1} 行 > 第 {x + 1} 行）——"
          f"展开态下要滑过十几屏才够得着它")
    return 1


if __name__ == "__main__":
    sys.exit(main())
