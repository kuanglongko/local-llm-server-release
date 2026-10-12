#!/usr/bin/env python3
"""发布规则**自身**的守卫：每条 `release-rules.json` 规则必须还指得着源码。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一份（Issue #174 / 模块丙-2）
═══════════════════════════════════════════════════════════════════════════
`release-rules.json` 的规则是 `old -> new` 的**精确文本匹配**（见 `release.py`
的 `selfcheck_hygiene`：`if r['old'] in cleaned: cleaned = cleaned.replace(...)`）。

这条机制的失效形态**全静默**：`old` 在源码里不再出现时，`replace` 什么也不做、
**不报错、不告警**。于是：
  · 规则**已是死的**，而发布器照样跑完、照样出一份发布物；
  · 它该清洗掉的那段开发痕迹（版本号、内网路径、开发期标签）**直接进发布线**；
  · 而人看到的是一份"规则齐全、门禁通过"的发布物。

三种状态必须分开，否则判不了：
  · **活跃**：`old` 还在源码里 —— 规则正常工作；
  · **已应用**：`old` 没了但 `new` 在 —— 源码已是发布态，规则是幂等空转（无害）；
  · **失效**：`old` 和 `new` **都不在** —— 源码后来又改过一遍，规则**再也命不中**，
    这才是"静默漏清洗"。

本守卫还判两件：
  · 规则基线的落后程度**可查**（`generated_from.dev` 能解析）；
  · 规则集**非空**、路径都存在（规则被清空/写错路径同样静默）。

纯 python3 标准库（git 可选，缺了不误报）。运行：python3 tools/run_release_rules_guard.py
"""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

RULES = "scripts/release-rules.json"

ok = 0
bad = 0


def check(name, cond, detail=""):
    global ok, bad
    if cond:
        print(f"PASS  {name}")
        ok += 1
    else:
        print(f"FAIL  {name}" + (f" —— {detail}" if detail else ""))
        bad += 1


data = json.load(open(RULES, encoding="utf-8"))
rules = data.get("rules", [])

check("规则集非空（防下面几条恒真）", len(rules) >= 5, f"实际 {len(rules)} 条")

missing_file = [r["path"] for r in rules if not os.path.exists(r["path"])]
check(
    f"每条规则的 path 都存在（悬空：{len(missing_file)} 条）",
    not missing_file,
    "、".join(missing_file[:5]),
)

# ── 逐条判：活跃 / 已应用 / 失效 ────────────────────────────────────────────
active = []
applied = []
dead = []
for r in rules:
    path = r["path"]
    if not os.path.exists(path):
        continue
    src = open(path, encoding="utf-8").read()
    if r["old"] in src:
        active.append(r)
    elif r["new"] in src:
        applied.append(r)
    else:
        dead.append(r)

print(f"      规则 {len(rules)} 条：活跃 {len(active)} / 已应用 {len(applied)} / 失效 {len(dead)}")

check("核到了规则状态（防下面几条恒真）", len(active) + len(applied) + len(dead) >= 5)

# 失效的规则 = "静默漏清洗"：它该清的东西再也清不掉。必须逐条给人看。
check(
    f"没有失效的规则（old/new 都不在源码里：{len(dead)} 条）",
    not dead,
    "；".join(f"{os.path.basename(r['path'])}: {r['old'][:40]!r}" for r in dead[:4]),
)

# 规则之间不许 old 重复（重复 = 有一条永远轮不到，或两条写重了）
olds = [r["old"] for r in rules]
dup = sorted({o for o in olds if olds.count(o) > 1})
check(f"没有重复的 old（重复：{len(dup)} 组）", not dup,
      "、".join(repr(d[:40]) for d in dup[:3]))

# ── 基线可查 ───────────────────────────────────────────────────────────────
gf = data.get("generated_from") or {}
base = gf.get("dev") or ""
check("generated_from.dev 已登记（漂移提示才不会永远是'未登记'）", bool(base))

if base:
    r = subprocess.run(["git", "rev-parse", "--verify", base + "^{commit}"], capture_output=True)
    check(f"基线的提交 {base[:9]} 在本仓库里解析得到", r.returncode == 0)

    # 落后程度：纯提示，但**必须算得出来**（算不出说明基线不可达）
    r2 = subprocess.run(["git", "rev-list", "--count", base + "..HEAD"], capture_output=True)
    if r2.returncode == 0:
        behind = int(r2.stdout.decode().strip() or 0)
        # 阈值取 200：本仓库实测已 179。这条是**提示型**判据 ——
        # 落后本身不阻断发布（规则是逐条补的），但超过阈值就该有一轮"补规则"的动作。
        check(
            f"规则基线落后不超过 200 个提交（实际 {behind}，超了该补规则了）",
            behind <= 200,
            f"基线 {base[:9]} 落后 {behind} 个提交",
        )
    else:
        print("      （git 不可用或基线不可达，跳过落后计数）")

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
