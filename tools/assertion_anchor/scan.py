#!/usr/bin/env python3
"""扫描 `tools/run_*guard.sh` 里的判据，抽出**正向 grep 断言**及其 pattern/目标。

甲-1 判据② 的载体：一条 `grep -q 'PAT' $FILE` 若 pattern 在文件里**零命中**，
它就永远退非 0 —— 而 `c()` 把非 0 判成 FAIL，所以它其实**恒红**；
反过来若写进负向位置（`! grep`），零命中就**恒绿**。两类都静默：
前者会被 `|| true` 抹掉，后者从头到尾没问过问题。

本模块只做"抽出来"，判据在守卫里。

为什么要按 shell 词法抽而不是正则一把梭：
  · `$SRC/HttpApi.kt` 与 `$HTTP` 两种写法并存，需要变量展开；
  · 判据体可能是**多条 grep 用 `&&` 连起来**的复合式，每条都要单独核；
  · 负向断言（`! grep ...`）**不该**要求命中 —— 它的语义是"不出现"。
"""

import re

# 变量赋值：`FOO=path`（含 `export FOO=path`）。只收非空、无空格的值。
VAR_RE = re.compile(r'^\s*(?:export\s+)?([A-Z][A-Z0-9_]*)=([^\s;|&]+)\s*$', re.M)

# 一条正向 grep 断言：`grep -q 'PAT' FILE`（pattern 用单引号）。
GREP_RE = re.compile(r"grep\s+-[qF]+[a-zA-Z]*\s+'([^']*)'\s+\"?(\S+?)\"?\s*(?:$|\||&|;|\))")


def load_var_map(src):
    return dict(VAR_RE.findall(src))


def join_continuations(src):
    """把 `\\` 续行拼成逻辑行，同时保留每段首行的行号（报错要指得回原文）。"""
    lines = src.splitlines()
    out = []          # (起始行号, 逻辑行文本)
    buf = None
    start = 0
    for i, line in enumerate(lines, 1):
        if buf is None:
            start = i
            buf = line
        else:
            buf += " " + line.strip()
        if buf.rstrip().endswith("\\"):
            buf = buf.rstrip()[:-1]
            continue
        out.append((start, buf))
        buf = None
    if buf is not None:
        out.append((start, buf))
    return out


def expand(token, varmap):
    """把 `$SRC/HttpApi.kt` / `${HTTP}` / `ApiAuth.kt` 展开成仓库相对路径。

    变量**递归展开**：`AUTH=$SRC/ApiAuth.kt` 的写法很常见，只展开一层会留下
    `$SRC/ApiAuth.kt` 这种"看起来是路径、其实不存在"的目标 —— 那会让整条判据
    从核对面里悄悄掉出去（本模块开发时就真踩到：461 条可核，实际 500+）。
    """
    t = token.strip().strip('"')
    # 源码里判据常在双引号内，`$` 被转义成 `\$`；先还原
    t = t.replace("\\$", "$")
    for _ in range(5):                      # 递归上限：防环
        m = re.match(r'^\$\{?([A-Z][A-Z0-9_]*)\}?(.*)$', t)
        if not m:
            break
        var, rest = m.groups()
        if var not in varmap:
            break
        t = (varmap[var] + rest).lstrip("/")
    return t


def iter_grep_assertions(guard_path, varmap):
    """产出该守卫里的正向 grep 断言：(行号, 判据名, pattern, 目标文件)。"""
    src = open(guard_path, encoding="utf-8").read()
    out = []
    for lineno, logical in join_continuations(src):
        m = re.match(r'\s*c\s+"([^"]*)"\s+"(.*)"\s*$', logical)
        if not m:
            continue
        name, cmd = m.group(1), m.group(2)
        for gm in GREP_RE.finditer(cmd):
            pat, target = gm.groups()
            head = cmd[:gm.start()]
            # 该 grep 前面紧邻 `!` -> 负向段，跳过（它要的正是"不出现"）
            if re.search(r'!\s*$', head):
                continue
            out.append((lineno, name, pat, expand(target, varmap)))
    return out
