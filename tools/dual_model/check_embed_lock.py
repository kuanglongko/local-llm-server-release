#!/usr/bin/env python3
"""断言嵌入路径持 **embedLock**，而不与 genLock/loadLock 共用。

共用锁会让"检索"排在"一整轮生成"后面 —— 双常驻的全部意义（检索不用等生成）
就此消失，而行为上与单模型形态**没有任何可见差别**（只是慢），所以只能靠结构判据。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_span  # noqa: E402


def main():
    src_path = sys.argv[1]
    args = sys.argv[2:]
    fn = 'loadEmbedModel'
    if '--fn' in args:
        fn = args[args.index('--fn') + 1]
    src = Path(src_path).read_text(encoding='utf-8')
    body, line = kotlin_fn_span(src, fn)
    if body is None:
        print(f"FAIL  找不到 Kotlin 函数 {fn}（判据锚点失效）")
        return 1
    if not re.search(r'synchronized\s*\(\s*embedLock\s*\)', body):
        print(f"FAIL  {fn} 没有 synchronized(embedLock) —— 会与生成路径互等")
        return 1
    for bad in ('genLock', 'loadLock'):
        if re.search(re.escape(bad), body):
            print(f"FAIL  {fn} 体内出现了 {bad}（嵌入应与生成/加载路径解耦）")
            return 1
    print(f"PASS  {fn} 持 embedLock 且未引用 genLock/loadLock（第 {line} 行起）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
