#!/usr/bin/env python3
"""把「守卫真正执行的每条 grep 断言」分成 正/负 两类，并给出它收到的真实 pattern。

两步：
  ① **动态**跑 guard（`ledger.sh`），拿到 grep 真实收到的 pattern（shell 两层转义后的）；
  ② **静态**扫 guard 源码，得到每条 grep 的 `!` 前缀（负向）标记，
     再按 pattern 归一化后与 ① 对齐。

为什么非要两步：转义只有 shell 自己算得对（静态会多算一层），
而 `!` 前缀进了 `eval` 之后 grep 根本看不见（动态拿不到）。
"""

import re
import subprocess
import os

GUARD_RE = re.compile(r"(!\s*)?grep\s+-[A-Za-z]+\s+'((?:[^'\\]|\\.)*)'\s+(\S+)")


def shell_unescape(s):
    """还原 eval 双引号层的转义：`\\"`->`"`、`\\\\`->`\\`、`\\$`->`$`、`\\``->`` ` ``。"""
    return re.sub(r'\\(["\\$`])', r"\1", s)


# 单份守卫的动态跑（ledger）上限；超时按"查不动"处理：返回空列表，
# 上层据此跳过这条守卫的判定，而不是把空结果当成"零命中"误报。
# 默认 180s —— 正常一份只要几秒，超过它多半是守卫自身挂住或 shim 递归。
LEDGER_TIMEOUT = float(os.environ.get("ASSERTION_ANCHOR_LEDGER_TIMEOUT", "180"))


def ledger(guard_path):
    """动态：跑 guard，拿 (status, pattern, file, flags)。"""
    exe = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ledger.sh")
    try:
        r = subprocess.run(["sh", exe, guard_path], capture_output=True, text=True,
                           timeout=LEDGER_TIMEOUT)
    except subprocess.TimeoutExpired:
        return []
    out = []
    for line in r.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            flags = parts[3].strip() if len(parts) >= 4 else ""
            out.append((parts[0], parts[1], parts[2], flags))
    return out


# 计数式负向：`[ $(grep -c 'PAT' FILE) -eq 0 ]` / `-le 0` —— 语义是"不许出现"。
# 这类写法骗过第一版：它不带 `!`，被当成正向，于是"零命中"（正确状态）反被报成故障。
COUNT_NEG_RE = re.compile(r"grep\s+-c[A-Za-z]*\s+'((?:[^'\\]|\\.)*)'[^\n]*?-(?:eq|le)\s+0\b")


def static_negation(guard_path):
    """静态：返回 {归一化 pattern: is_negated}。归一化 = shell 还原 + BRE 的 `\\"`->`"`。"""
    src = open(guard_path, encoding="utf-8").read()
    table = {}
    for m in GUARD_RE.finditer(src):
        neg = bool(m.group(1))
        real = shell_unescape(m.group(2))
        for cand in (real, real.replace('\\"', '"')):
            table.setdefault(cand, neg)
    # 计数式负向（`-eq 0` / `-le 0`）覆盖为 True
    for m in COUNT_NEG_RE.finditer(src):
        real = shell_unescape(m.group(1))
        for cand in (real, real.replace('\\"', '"')):
            table[cand] = True
    return table


def classify(guard_path):
    """产出 [(pattern, file, negated)]，negated 由静态表决定（查不到就当正向，从严）。

    **按 (pattern, file, flags) 去重**：守卫里常有"跑一遍别的套件"的一跳
    （`sh tools/xxx_guard_tests.sh`），而记账 shim 是**继承给子进程**的 ——
    于是那个被驱动的套件里每条 grep 都被原样重记一遍。实测
    `run_ci_wiring_guard.sh` 31240 行去重后 866、`run_schema_sampler_guard.sh`
    15259 -> 396。重复行对判定**零信息**（本模块只问"(pattern, file) 会不会
    命中"），却让下游的 `repo_hit` 把同一份全仓扫描重做上万次 ——
    这正是那一轮 310s 的主因。去重是纯噪声消除，判据不动。
    """
    neg_table = static_negation(guard_path)
    out = []
    seen = set()
    for _st, pat, f, flags in ledger(guard_path):
        key = (pat, f, flags)
        if key in seen:
            continue
        seen.add(key)
        neg = neg_table.get(pat)
        if neg is None:
            neg = neg_table.get(pat.replace('\\"', '"'), False)
        out.append((pat, f, neg, flags))
    return out
