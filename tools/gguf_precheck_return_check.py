#!/usr/bin/env python3
"""GGUF 预读守卫的**归属**检查：`return mk` 必须落在守卫失败的那个 if 块里。

为什么单独成脚本（而不是守卫里一行 grep -A 3）：
  · 按"往下 N 行"锚会在**正确**的改动上误红 —— 本改动自己就踩过：
    先拼 `const std::string msg = ...` 再 return，只是把 return 推后了一行；
  · 判据要的是**归属**（"这个 return 属于这个 if 吗"），而归属只能按
    花括号配对判，grep 的距离是它的近似，而且是会漂的近似。

用法：`python3 tools/gguf_precheck_return_check.py <函数体文件>`
退出码：0 = 归属正确；1 = 守卫块里没有 return（会落到解析）。
"""
import sys


def main(argv):
    if len(argv) != 2:
        print("用法: %s <函数体文件>" % argv[0], file=sys.stderr)
        return 2
    src = open(argv[1], encoding="utf-8").read()
    i = src.find("gguf_header_precheck(cpath")
    if i < 0:
        return 1
    j = src.find("{", i)          # 守卫那个 if 的左括号
    if j < 0:
        return 1
    d = 0
    k = j
    while k < len(src):
        if src[k] == "{":
            d += 1
        elif src[k] == "}":
            d -= 1
            if d == 0:
                break
        k += 1
    return 0 if "return mk" in src[j:k] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
