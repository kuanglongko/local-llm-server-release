#!/usr/bin/env python3
"""判 `entriesCountOf` 用的是**索引侧的同一个口径**（`rowsToCount(rowsOf(...))`）。

═══════════════════════════════════════════════════════════════════════════
为什么要单独一条
═══════════════════════════════════════════════════════════════════════════
`texts.jsonl` 的行数口径在本仓库里有**唯一**定义：段数（`split('\\n').size`），
条目数 = 段数 - 1（末尾那个由结尾换行产生的空段）。索引侧到处用它：
`load` 的 `count == rows - 1`、`readState` 的校验、`importEntries` 的行数核对、
`removeEntry` 的 `rowsToCount(rowsBefore)`。

而 `entriesCountOf` 原先自己数了"非空行个数"。两者在**自产文件**上恰好相等
（`encodeTexts` 每行以 `\\n` 收尾、不留空行），所以只有"文件被外部改过"时才分家。
而 `importEntries` 写的正是 `磁盘上已有正文 + encodeTexts(新增)` ——
"已有正文"是从磁盘**原样**读回来的，因此用户手工编辑过 `texts.jsonl`
（或它由别处导入而来）时，两种口径立刻给出两个数。

分家的后果：导入确认框显示"已有 N 条 → 导入后 N+M 条"，而实际落盘按段数算 ——
一处对不上，两处都不可信（用户没法知道到底导进去几条）。

判据（顺序敏感，不许只 grep 其中一个）：
  · 体内出现 `rowsToCount(`；
  · 体内出现 `rowsOf(`；
  · 体内**不出现** `.count { it.isNotEmpty() }` 这种"数非空行"的写法。

用法：check_entries_count.py <RagIndexStore.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write(__doc__)
        return 2
    src = open(sys.argv[1], encoding='utf-8').read()
    body, _ = kotlin_fn_body(src, 'entriesCountOf')
    if body is None:
        print('FAIL  找不到 entriesCountOf（改名/拆掉？）—— 判据会恒真')
        return 1
    bad = 0
    if 'rowsToCount(' not in body:
        print('FAIL  entriesCountOf 没有用 rowsToCount —— 口径与索引侧不同源')
        bad += 1
    if 'rowsOf(' not in body:
        print('FAIL  entriesCountOf 没有用 rowsOf —— 那它数的是什么？')
        bad += 1
    # "数非空行"的写法：`.count { it.isNotEmpty() }` / `.count{...isNotEmpty()...}`
    if re.search(r'\.count\s*\{[^}]*isNotEmpty', body):
        print('FAIL  entriesCountOf 仍在数非空行 —— 与索引侧的 rowsToCount 口径分家')
        bad += 1
    if bad == 0:
        print('PASS  entriesCountOf 与索引侧同口径（rowsToCount(rowsOf(...))）')
        return 0
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
