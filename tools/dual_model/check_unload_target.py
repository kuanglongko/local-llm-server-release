#!/usr/bin/env python3
"""断言：某个 JNI 导出函数体内只拆**目标** Session，绝不拆另一个。

判据锚「这个函数体里调用了 unloadModelInternal(谁)」，不锚"文件里出现过 E/S"：
后者恒真（别处出现也算），前者才是隔离本身。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import fn_body  # noqa: E402


def main():
    src_path = sys.argv[1]
    fn = None
    want = None
    forbid = None
    args = sys.argv[2:]
    i = 0
    while i < len(args):
        if args[i] == '--fn':
            fn = args[i + 1]
        elif args[i] == '--want':
            want = args[i + 1]
        elif args[i] == '--forbid':
            forbid = args[i + 1]
        i += 1
    if not (fn and want and forbid):
        print("用法: check_unload_target.py <cpp> --fn NAME --want S --forbid E")
        return 2

    src = Path(src_path).read_text(encoding='utf-8')
    body, line = fn_body(src, fn)
    if body is None:
        print(f"FAIL  找不到 {fn} 的函数体（判据锚点失效，不可静默放过）")
        return 1

    # 在该函数体里找 unloadModelInternal( x ) 的目标
    import re
    targets = re.findall(r'unloadModelInternal\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)', body)
    if not targets:
        print(f"FAIL  {fn} 体内没有 unloadModelInternal 调用（该拆的那一份没拆？）")
        return 1
    bad = [t for t in targets if t == forbid]
    if bad:
        print(f"FAIL  {fn} 体内出现了 unloadModelInternal({forbid}) —— 会拆错那一份")
        return 1
    if want not in targets:
        print(f"FAIL  {fn} 体内没有 unloadModelInternal({want})（拆的是 {targets}）")
        return 1
    print(f"PASS  {fn} 只拆 {want}（第 {line} 行起，目标 {targets}）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
