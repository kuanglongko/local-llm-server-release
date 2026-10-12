#!/usr/bin/env python3
"""断言**远端库名不跟随 App 内当前库**。

═══ 这一条守的是什么 ═══
"跟随当前库"看着方便（少配一次），实际把一类**静默故障**请了回来：
`currentKb` 是单一全局值，在 App 里点任何一条库都会改它。远端若读它，
就会出现"我在 App 里切到库 B 看了两眼，此时远端某个正在轮询的客户端
**静默**换成了库 B" —— 回答变差，而没有任何现象指向"库换了"。

判据锚**取值来源**：`remoteKb()` 的函数体里不许出现 `KEY_CURRENT`
（那个键就是 App 内当前库）。锚"函数体"而不是"整个文件"：
文件里别处当然会有 `KEY_CURRENT`（`currentKb()` 自己就用），整文件判恒红。

用法：check_remote_kb_independent.py <RagInject.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def main():
    if len(sys.argv) < 2:
        print("用法：check_remote_kb_independent.py <RagInject.kt>")
        return 2
    src = Path(sys.argv[1]).read_text(encoding="utf-8")
    body, _ = kotlin_fn_body(src, 'remoteKb')
    if body is None:
        print("FAIL  找不到 remoteKb()（远端库名的唯一读取口）")
        return 1
    # `_fnbody` 会把字符串字面量抹成 ""，而我们要判的正是**读哪个键**。
    # 所以回原文里取该函数的区间（用行号定位），而不是用抹过的函数体。
    # 这里用最直接的办法：取 `fun remoteKb(` 之后到下一个 `fun ` 之前的原文。
    m = re.search(r'fun\s+remoteKb\s*\([^)]*\)[^\n]*\n(.*?)\n\s*fun\s', src, re.S)
    if not m:
        print("FAIL  取不到 remoteKb 的原文区间")
        return 1
    seg = m.group(1)
    if 'KEY_CURRENT' in seg:
        print("FAIL  remoteKb 读了 KEY_CURRENT —— 远端库名跟随了 App 内当前库，"
              "在 App 里切库会静默改掉远端正在用的库")
        return 1
    if 'KEY_REMOTE_KB' not in seg:
        print("FAIL  remoteKb 没有读 KEY_REMOTE_KB（远端库名取的不是它自己的键）")
        return 1
    print("PASS  remoteKb 只读自己的键（不跟随 App 内当前库）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
