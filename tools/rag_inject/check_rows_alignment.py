#!/usr/bin/env python3
"""断言注入侧渲染用的 `rows` 是**按行号对齐**的（不是按命中顺序压紧的）。

═══ 这一条守的是什么 ═══
`RagInject.render`（与 `rag_index.h::render_injection` 逐字对应）的契约是
**按行号取源**：`rows[p.index]`，`p.index` 是"索引里的第几行"。

调用方（`LlmEngine.ragContext`）如果按**命中顺序**把正文压进一个长度只有
`picks.size` 的数组，两个下标口径就只在"命中的正好是第 0、1、2…行"时偶然相等。
后果全都不报错：

  · 命中行号全部 >= picks.size -> `p.index >= rows.size` -> 整块被跳过
    -> 日志「选中 N 条但正文为空」（用户报的"选中一条，内容为空"）；
  · 命中行号部分落在 0..picks.size-1 -> 注入的是**另一行**的正文
    （分数对、内容错）。

单测验不出这件事：`RagInjectTest` 喂的都是**行号对齐**的数组（`render(p(0), rows[0])`），
错的是**调用方**。所以只能在源码级锚"调用方怎么造这个数组"。

判据锚**结构**（谁喂给谁、数组长度取自谁），不锚"文件里出现过某个名字"：
后者是恒真的（写错的那一支也会有那个名字）。

用法：check_rows_alignment.py <LlmEngine.kt> [RagIndexStore.kt]
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def main():
    eng = Path(sys.argv[1]).read_text(encoding='utf-8')
    bad = 0

    body, _line = kotlin_fn_body(eng, 'ragContext')
    if body is None:
        print("FAIL  找不到 ragContext（注入侧的入口）")
        return 1

    # ① rows 的**容量**不许取自 picks.size —— 那正是"压紧"的指纹。
    #    锚 `val rows = ArrayList<String>(X)` 里的 X 不是 `picks.size`。
    #    ⚠ 契约变更：注入每条只有正文，`labels` 已随「（来源: …）」一起下线，
    #    这里不再审 labels（它已不存在）。
    m = re.search(r'val\s+rows\s*=\s*ArrayList<String>\(\s*([^)]*?)\s*\)', body)
    if not m:
        print("FAIL  ragContext 里找不到 rows 的构造（render 拿不到正文源）")
        bad += 1
    elif 'picks.size' in m.group(1):
        print("FAIL  rows 的容量取自 picks.size —— 这是'按命中顺序压紧'的写法，"
              "与 render 的按行号取值口径不符（命中靠后的行会被整块丢掉）")
        bad += 1
    else:
        print("PASS  rows 的容量不取自 picks.size（不压紧）")

    # ② 填 rows 的循环**不许**迭代 picks —— 那等于"第 i 个命中配第 i 格"。
    #    锚 `for (... in <coll>)` 里那个 `<coll>` 的名字。
    loops = re.findall(r'for\s*\(\s*\w+\s+in\s+([A-Za-z_]\w*)\s*\)', body)
    if 'picks' in loops:
        print("FAIL  ragContext 用 picks 迭代填 rows —— 下标变成'第几个命中'，"
              "而 render 要的是'第几行'")
        bad += 1
    else:
        print("PASS  ragContext 填 rows 时没有按 picks 迭代")

    # ③ 必须存在一个"按行号对齐读全文"的入口被调用 —— 否则源码里没有能对齐的来源。
    #    锚**行号对齐**这个词的语义载体：读全库（readEntriesAligned 那一族）。
    if not re.search(r'RagIndexStore\.readEntriesAligned\s*\(', body):
        print("FAIL  ragContext 没有调用行号对齐的读取入口"
              "（RagIndexStore.readEntriesAligned）—— 正文源无法与索引行号对齐")
        bad += 1
    else:
        print("PASS  ragContext 走行号对齐的读取入口")

    # ④ 存储层：对齐读取必须**保留空位**（坏行/空行留 null），不能用会压紧的实现。
    #    `readEntriesTexts` 会 `if (lines[i].isEmpty()) continue` —— 那就是压紧。
    if len(sys.argv) > 2:
        store = Path(sys.argv[2]).read_text(encoding='utf-8')
        b, _ = kotlin_fn_body(store, 'readEntriesAlignedTexts')
        if b is None:
            print("FAIL  存储层没有 readEntriesAlignedTexts（对齐读取的纯函数）")
            bad += 1
        else:
            if 'decodeTextRow' not in b:
                print("FAIL  readEntriesAlignedTexts 没有走 decodeTextRow —— "
                      "行号与解析口径可能与其余读法分岔")
                bad += 1
            elif re.search(r'\bcontinue\b', b):
                print("FAIL  readEntriesAlignedTexts 体内有 continue —— "
                      "会跳过坏行/空行，返回的 list 被压紧（下标不再是行号）")
                bad += 1
            else:
                print("PASS  readEntriesAlignedTexts 保留空位（坏行不压缩下标）")

        # ⑤ 行数口径必须与 rowsOf 一致（都用 split('\n').size），否则最后一行差一位。
        #    ⚠ 这里用**原文正则**而不是 kotlin_fn_body：`_fnbody` 会抹掉字符串字面量，
        #    而 `'\n'` 正是要判的那个字面量 —— 用它判会**恒假**（本仓库反复踩过：
        #    "锚字面量却把字面量剥掉"）。
        if not re.search(r"fun\s+rowsOf\s*\([^)]*\)[^\n]*split\('\\n'\)", store):
            print("FAIL  rowsOf 不是按换行切分计行 —— 与对齐读取的计行口径不一致")
            bad += 1
        else:
            print("PASS  rowsOf 与对齐读取的计行口径一致（都按 '\\n' 切分）")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
