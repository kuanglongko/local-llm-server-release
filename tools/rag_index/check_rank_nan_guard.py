#!/usr/bin/env python3
"""判 `rank()` 里那道「丢弃非有限分数」的闸**排在阈值比较之前**。

═══════════════════════════════════════════════════════════════════════════
为什么需要单独一条（而不是 grep 一下 `isFinite` 就够了）
═══════════════════════════════════════════════════════════════════════════
`rank` 里原本只有一句阈值比较：

    if (minScore > 0f && !(score >= minScore)) continue

它顺带挡掉了 NaN —— 但**仅当 `minScore > 0f`**。调用方可以显式传 `minScore <= 0`
表示"不按分数过滤"（`RagInject.select` 与 `rag_index.h::select_injections` 都把这个
口子写在注释里，用于对比测试）。那种取值下：

  · 整个 `if` 被短路掉，NaN 进 `passed`；
  · Kotlin 的 `compareByDescending` 把 NaN 排在**最前**（Float 比较里 NaN 大于一切）；
  · 于是 NaN **占掉一个 top-k 名额**，把一条真正及格的命中挤出结果 ——
    排序全对、只是少了一条、接口 200、日志干净。

所以正确的实现是**两道闸分开**，且 NaN 那道在前：

    if (!score.isFinite()) continue          // 与阈值无关，永远生效
    if (minScore > 0f && !(score >= minScore)) continue

判据因此不是"文件里出现了 isFinite"（那种写法放在 `passed.add` 之后也照样能被
grep 到，而那时 NaN 已经进列表了），而是**顺序关系**：
`isFinite` 那一行必须在 `passed.add` 之前、且在阈值比较之前。

用法：check_rank_nan_guard.py <RagIndexStore.kt>
"""
import re
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from _fnbody import kotlin_fn_body  # noqa: E402


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write(__doc__)
        return 2
    src = open(sys.argv[1], encoding='utf-8').read()
    body, _line = kotlin_fn_body(src, 'rank')
    if body is None:
        print('FAIL  找不到 rank 的函数体（改名/拆掉？）')
        return 1

    fails = []
    # ① 必须有那道闸（且是 `!score.isFinite()`，不是别的写法）
    nan_at = body.find('!score.isFinite()')
    if nan_at < 0:
        fails.append('rank 里没有 `!score.isFinite()` 这道闸 —— '
                     'minScore <= 0 时 NaN 会进 passed 并占掉 top-k 名额')

    # ② 与 passed.add 的相对位置
    add_at = body.find('passed.add(')
    if add_at < 0:
        fails.append('rank 里找不到 `passed.add(` —— 抽取失效（判据会恒真）')
    elif nan_at >= 0 and nan_at > add_at:
        fails.append('NaN 闸排在 `passed.add(` **之后** —— NaN 已经进列表了才判，等于没判')

    # ③ 与阈值比较的相对位置（这是本判据的真正内容）
    thr_at = body.find('minScore > 0f')
    if thr_at < 0:
        fails.append('rank 里找不到阈值比较 `minScore > 0f` —— 抽取失效（判据会恒真）')
    elif nan_at >= 0 and nan_at > thr_at:
        fails.append('NaN 闸排在阈值比较**之后** —— `minScore > 0f` 为假时那段被短路，'
                     'NaN 照样进 passed（这正是本判据要钉的缺陷）')

    if fails:
        for f in fails:
            print('FAIL  %s' % f)
        return 1
    print('PASS  rank 的 NaN 闸在 passed.add 与阈值比较**之前**（阈值关掉时也生效）')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
