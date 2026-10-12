#!/usr/bin/env python3
"""钉「宿主复刻测试用的标签」与「实现里的 TAG_* 常量」是同一批字面量。

为什么单独一个文件：这段要穿过 shell 的引号层。写成内联 heredoc 时，
里面每一对双引号都得再转义一层，而这里最需要原样的恰恰是**带引号的字面量** ——
本仓库已经在那上面栽过一次（release_hygiene 的桩改为独立文件正是因为
"内联会被 heredoc 的引号层数与 sh 的展开吃掉反斜杠"）。

判据两侧：
  · 复刻侧（tools/pure_logic/PureLogicTest.kt）比的是 **Kotlin 源码的转义形态**；
  · 实现侧（LogFileStore.kt）比的是**真正的常量声明**。
  · 另外比一遍**档位名**（计时/内存/HTP/仅错误）：它们是两维判据的**集合元素**，
    改文案而漏改复刻时，"复刻测试全绿"就与真实现无关了 —— 而同族漂移在这个
    仓库叫 `0.9.115`（文档条数与实测对不上）。

用法：python3 tools/log_filter/check_tag_sync.py <复刻测试> <实现>
"""
import sys
from pathlib import Path

MARK = "筛选：复刻用的标签就是源码里的 TAG_* 值"
WANT = (
    ("HTP", "[HTP参与]"),
    ("HTP_MEASURED", "[HTP实测]"),
    ("TIME", "[计时]"),
    ("MEM", "[内存]"),
)

# 档位名（chip 文案）—— 复刻侧写的是 `setOf("计时")` 这种形态，实现侧是 const val。
LEVELS = ("计时", "内存", "HTP", "仅错误")

Q = chr(34)      # "
BS = chr(92)     # \


def main() -> int:
    plt = Path(sys.argv[1]).read_text(encoding="utf-8")
    lfs = Path(sys.argv[2]).read_text(encoding="utf-8")
    if MARK not in plt:
        print("复刻测试里找不到同步自检那一句（判据被删了或改名了）：%s" % MARK)
        return 1
    seg = plt[plt.index(MARK): plt.index(MARK) + 700]
    bad = []
    for name, tag in WANT:
        # 复刻侧：`"TAG_MEM = \"[内存]\""` 这种转义形态
        left = "TAG_%s = " % name + BS + Q + tag + BS + Q
        # 实现侧：`const val TAG_MEM = "[内存]"` 里的那一段
        right = Q + tag + Q
        if left not in seg:
            bad.append("复刻侧缺 %s" % left)
        if right not in lfs:
            bad.append("实现侧缺常量值 %s" % right)
    for lvl in LEVELS:
        left = BS + Q + lvl + BS + Q
        right = Q + lvl + Q
        if left not in seg:
            bad.append("复刻侧缺档位名 %s" % left)
        if ("const val " not in lfs) or (right not in lfs):
            bad.append("实现侧缺档位常量值 %s" % right)
    for b in bad:
        print("  " + b)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
