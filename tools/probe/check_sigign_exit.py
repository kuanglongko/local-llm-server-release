#!/usr/bin/env python3
"""K-3 判据：`SIG_IGN` 那一支里不得 `_exit`（也不得调 sa_handler）。

旧写法：
    } else if (old && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
    }
    if (old && old->sa_handler == SIG_IGN) _exit(128 + sig);
三点问题：
  · `SIG_IGN` 的值是 `(void (*)(int)) 1`，**不是**可调用的函数 —— 前面那条
    `!= SIG_IGN` 只挡住了它，挡不住就等于跳到地址 1；
  · 后面那一句把"本进程此前忽略这个信号"**升级成致命退出**（exit=128+sig）,
    与文件头承诺的"绝不吞掉信号，也不改变原有行为"直接冲突；
  · 真正需要 `_exit` 的只有 `g_in_handler` 那个**递归保护**。

判据：`probe_signal` 的 `SIG_IGN` 分支（从 `== SIG_IGN` 到它自己的 `}`）
里不得出现 `_exit` / `exit(` / `abort(` / `raise(`。
"""
import sys


def fnbody(src: str, sig: str) -> str:
    i = src.index(sig)
    j = src.index("\n}\n", i) + 3
    return src[i:j]


def strip_comments(body: str) -> str:
    out = []
    for ln in body.splitlines():
        s = ln.split("//", 1)[0]
        if s.strip().startswith("*"):
            continue
        out.append(s)
    return "\n".join(out)


def main(path: str) -> int:
    src = open(path, encoding="utf-8").read()
    body = strip_comments(fnbody(src, "static void probe_signal"))
    at = body.find("== SIG_IGN) {")
    if at < 0:
        print("FAIL  探针信号守卫：找不到 SIG_IGN 分支（形状变了？）")
        return 1
    # 该分支的结束：从 at 起的第一个 "        }" 或 "    }" 行。
    lines = body[at:].splitlines()
    depth = 0
    branch = []
    for ln in lines:
        branch.append(ln)
        depth += ln.count("{") - ln.count("}")
        if depth <= 0 and len(branch) > 1:
            break
    branch_src = "\n".join(branch)
    bad = [w for w in ("_exit", "exit(", "abort(", "raise(") if w in branch_src]
    if bad:
        print("FAIL  探针信号守卫：SIG_IGN 分支里出现 %s —— "
              "把\"忽略\"升级成\"去死\"，原语义被改写" % ",".join(bad))
        return 1
    print("PASS  探针信号守卫：SIG_IGN 分支保持忽略语义（无 _exit/exit/abort/raise）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
