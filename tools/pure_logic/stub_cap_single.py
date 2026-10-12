"""桩①b（C-1 第二级只截一条）：把 `capChars` 的第二级换成对某一条的无条件截断。

⚠ 为什么这个桩是**独立 .py 文件**而不是自测里的内联 heredoc：
桩文本里的两个锚点有一个是 `for (i in last - 1 downTo 0) {` ——
这个片段要被塞进 python 字符串**常量**，而它本身带着 `"` 以外还带着 `{` 与 `(`。
先前内联时，heredoc 的引号层数把这段常量吃掉了（实测：源文件被"改"成
`start_anchor == end_anchor`，切出空串，于是守卫照绿，断言静默失效）。
本仓库已在 release_hygiene 那段栽过同一个坑 —— 走文件、不走内联。

用法：python3 tools/pure_logic/stub_cap_single.py <SessionStore.kt>
"""
import io
import sys

START = "        for (i in last - 1 downTo 0) {"
END = "        Log.w(TAG,"
SUB = """        val only = msgs.first()
        val head = only.optString("content")
        if (head.length > MAX_CHARS) {
            only.put("content", head.substring(0, MAX_CHARS))
        }
"""


def main():
    p = sys.argv[1]
    s = io.open(p, encoding="utf-8").read()
    # END 在文件里有**两处**（一级分支那次留痕 + 二级分支这次），取 start 之后的第一个。
    assert s.count(START) == 1, "没找到第二级的截断循环"
    assert s.count(END) == 2, "没找到两处留痕"
    i = s.index(START)
    j = s.index(END, i + 1)
    assert j > i, "锚点顺序反了（这正是先前静默失效的形态）"
    io.open(p, "w", encoding="utf-8").write(s[:i] + SUB + s[j:])
    print("patched %d bytes" % (j - i))


main()
