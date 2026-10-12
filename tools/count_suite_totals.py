#!/usr/bin/env python3
"""把「每个套件自报的总条数」抽出来，落成一份可对账的清单。

═══════════════════════════════════════════════════════════════════════════
为什么要有这个文件
═══════════════════════════════════════════════════════════════════════════
`HTP-STATUS.md` §5.2 里每个套件后面写着一个「N 条」。2026-09-23 复核出
**9 处与实测对不上**（最大一处 77 -> 132），套件总数也写着 42（实际 79）。

根因与 `0.9.84` 那起事故**同一形态**：数字靠人工维护，没有任何机制让它随
代码一起演进。它比「脚本没接进 CI」更隐蔽 —— 脚本接了、也真的在跑，
只有**那个数字**是假的，而数字恰恰是读者判断「这块有多少保障」的唯一依据。

`run_ci_wiring_guard.sh` 的判据①③ 只比**脚本名字**，数字落在判据面之外。
本脚本补上「数量」这一维：跑一遍能自报总数的套件，把结果落成
`tools/suite-counts.json`，交给守卫做静态比对（守卫不再自己跑套件 ——
那是分钟级操作，不该压在接线守卫里）。

用法：
    python3 tools/count_suite_totals.py            # 打印 JSON 到 stdout
    python3 tools/count_suite_totals.py --write    # 就地更新 suite-counts.json
    python3 tools/count_suite_totals.py --check    # 与清单比对，漂移则 exit 1

**只收录能自报总数的套件**：kotlinc 编译的宿主单测逐条打印 `PASS`、不汇总，
硬凑一个条数只会制造新的漂移源。`--write` 对跑不出总数的项**保留原值**，
不把它抹成 null —— 否则本机缺工具链时跑一次就会把整份清单打回原形。

除了逐套件的「N 条」，`--write` 还落一个 **`suite_total`**：目录里到底有多少个
离线套件。这是「N 个套件」这句口径的**唯一真值** —— 此前文档里
「83 个套件」「42 → 81 个」这类**总数**没有任何东西核对（判据⑤ 的正则只认
表格里写 `N 条` 的行），于是「N 条」有尺子、「N 个套件」没有，同一族漂移在
总数这一维上原样复现。`--check-total` 就是那把缺的尺子。
"""
import glob
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, "tools", "suite-counts.json")

# 三种汇总形状（本仓库现有套件全落在其中）：
#   · `PASS <n> / FAIL <m>`        —— 过半守卫用这个
#   · `全部通过（<n> 条 …）` / `（共 <n> 条…）`
#   · `<n> 通过 / <m> 失败`
# 只认**全绿**的汇总行：条数要作为「这块有多少保障」的证据，就必须来自
# 一次通过的运行。否则本机缺工具链 / 环境不满足时会把一个"部分失败"的
# 条数写进清单，再让文档去对齐它 —— 那是用坏读数去校准证据。
#   · `PASS <n> / FAIL 0`
#   · `全部通过（<n> 条…`（"全部通过"本身就是全绿）
#   · `（共 <n> 条，失败 0）`
#   · `<n> 通过 / 0 失败`
PATTERNS = (
    r"PASS (\d+) / FAIL 0\b",
    r"全部通过（(?:共 )?(\d+) 条",
    r"（(?:共 )?(\d+) 条[，,]?\s*失败 0）",
    r"[:：]\s*(\d+) 通过 / 0 失败",
)


# 反例对照段落的标记：`run_probe_signal_tests.sh` 等脚本会同时打印
# 「当前实现 PASS 6」与「旧实现对照 PASS 1」两段汇总，最后一段是**反例**。
# 取错段落会让清单记成 1，而文档写的是当前实现的 6 —— 变成一条假漂移。
CONTRAST = ("旧实现", "反例", "对照组", "旧写法")


# 自测脚本会把**被它驱动的那个套件**的输出原样 echo 出来（`echo "$OUT" | tail -25`），
# 于是一份自测的输出里会有两段汇总：内层套件的（`== 取消验收：PASS 26 / FAIL 0 ==`）
# 与自测自己的（`=== 取消验收脚本自测：23 通过 / 0 失败 ===`）。两者形状不同、
# 计数对象也不同 —— 取错那一段就把一个**别的套件**的条数记进了这份清单，
# 而文档会跟着去对齐这个错数（`run_probe_signal_tests.sh` 的反例段是同一族）。
# 这些内部段落的特征前缀：
# ⚠ 不能用"以 `==` 开头就当成内层"这条判据：**自测自己的收尾也可能是单层等号**
# （`== run_acceptance_stop_tests: PASS 20 / FAIL 0 ==`），一刀切会把这份脚本的
# 真实读数也跳掉，于是清单记成 null（第一版实测如此）。
# 真正要认的是"这一行**属于另一个套件**"，而它可判别的特征是**内层那一行带有
# 被驱动套件的名字或它在驱动过程中才会出现的字段（`WARN`）**。更稳的做法是用
# **缩进/前缀**：自测输出内层读数时会原样 echo（`echo "$OUT" | tail -25`），
# 因此那一段**行首没有 `=`**，且行内会出现 `（实际` 这类包装词。
NESTED = (
    r"（实际\s.*PASS \d+ / FAIL \d+",   # 内联进用例描述里的内层读数
)


def _interpreter(script):
    """按首行 shebang 给出运行这个套件的命令前缀。

    认三种：`python3`（含 `#!/usr/bin/env python3`）、`sh` / `bash`、`node`。
    认不出时退回扩展名（`.py -> python3`，其余 `sh`）—— 那是"没有 shebang"
    时的合理默认，而不是猜。
    """
    try:
        first = open(script, encoding="utf-8", errors="replace").readline()
    except OSError:
        first = ""
    if first.startswith("#!"):
        if "python" in first:
            return ["python3"]
        if "node" in first:
            return ["node"]
        if "bash" in first:
            return ["bash"]
        if "sh" in first:
            return ["sh"]
    return ["python3"] if script.endswith(".py") else ["sh"]


def totals_from(text):
    """取脚本输出里当前实现的总条数；跑不出返回 None。

    从后往前找**第一段不是反例对照、也不是被 echo 出来的内层汇总**的行。
    三条规则：
      · 反例对照段（`旧实现` / `反例` / `对照组` / `旧写法`）跳过 ——
        取到它会把当前实现的 6 记成反例的 1，变成一条假漂移；
      · 内层套件的汇总（`== … ==`，单层等号）跳过 —— 那是自测**驱动**的
        另一个套件的读数，不是这份脚本自己的；
      · 其余从后往前取第一段命中。
    """
    lines = text.splitlines()
    for line in reversed(lines):
        if any(k in line for k in CONTRAST):
            continue
        if any(re.search(pat, line) for pat in NESTED):
            continue
        for pat in PATTERNS:
            m = re.search(pat, line)
            if m:
                return int(m.group(1))
    return None


def measure(script, timeout=300):
    # ⚠ 解释器按**首行 shebang** 选，不按扩展名。
    #
    # 这条规则是被一次实测逼出来的：`run_release_rules_guard.py` 与
    # `run_http_scope_order.py` 都以 `#!/usr/bin/env python3` 开头，此前却按
    # `.py -> python3` 判（对），而 `run_llama_jni_abort_tests.sh` /
    # `run_rag_kb_state_guard.sh` / `run_sse_forensics_tests.sh` 是**薄壳**：
    # 它们是 `#!/bin/sh`，正文只做 `exec python3 tools/xxx/check.py` —— 按扩展名
    # 判也对。真正分家的是另一种写法：**表里叫 `run_*.py`、文件却是 sh 语法**
    # （或反过来），按扩展名挑解释器会直接 `Syntax error` 退出，读数落成 None，
    # 清单与实测对不上 —— 而"对不上"在 CI 里就是一条**假漂移**。
    #
    # 所以从文件首行取。取不到（没有 shebang）时退回扩展名。
    cmd = _interpreter(script) + [script]
    # 按套件覆盖预算（见 MEASURE_TIMEOUTS）。相对路径归一成 `tools/xxx`。
    key = "tools/" + os.path.basename(script)
    timeout = MEASURE_TIMEOUTS.get(key, timeout)
    try:
        p = subprocess.run(
            cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout
        )
    except (subprocess.TimeoutExpired, OSError):
        return None
    return totals_from(p.stdout + "\n" + p.stderr)


def suite_scripts():
    return sorted(
        glob.glob(os.path.join(ROOT, "tools", "run_*.sh"))
        + glob.glob(os.path.join(ROOT, "tools", "run_*.py"))
    )


# 依赖「宿主 C++ 编译器 / kotlinc」的直接标记。命中即视为快检模式下**不可用**。
TOOLCHAIN_MARKERS = ("bin/kotlinc", "g++", "TC/")


def _read(path):
    return open(path, encoding="utf-8", errors="replace").read()


def _invokes_toolchain_suite(body, dep_names):
    """本脚本是否**真的调起**了某个依赖工具链的兄弟套件。

    自测脚本通常自己不写编译器命令，而是去 `sh tools/run_xxx.sh` —— 它的
    工具链依赖是**从被驱动的那份**传递来的：`run_static_order_guard_tests.sh`
    自己没有 `g++` 字样，却驱动着 `run_static_order_guard.sh`（其候选表里有
    `g++`，且需要 libc++）。只按本文件正文扫标记会漏掉这一类，快检模式就会
    把一份**要编译器**的自测算进"纯 sh + python3"里 —— 工具链一缺，它跑不出
    总数（实测=None），与清单记的条数对不上，CI 判成漂移、在 `kt_check.sh`
    之前就 exit 1（等于把"编不过"的发现时刻又推回到用户装机）。

    ⚠ 只认**命令行式**的调用（`sh tools/run_x` / `bash tools/run_x` /
    `python3 tools/run_x`），不认"文件里提到过这个名字"：接线守卫会在注释与
    白名单里列出一堆 `run_*.sh`，按"提到过"判会把**不需要编译器**的套件也
    一并排除掉，快检覆盖面凭空缩水。这是本仓库反复吃过的那一课：锚**调用关系**，
    不锚"某个字符串出现过"。
    """
    return set(
        re.findall(
            r"(?:sh|bash|python3)\s+(?:\./)?tools/(run_[A-Za-z0-9_]+\.(?:sh|py))", body
        )
    ) & set(dep_names)


def toolchain_dependent():
    """返回需要「宿主 C++ 编译器 / kotlinc」的套件名集合。

    两条来源：
      · **直接**：脚本正文里出现 `TOOLCHAIN_MARKERS`（`bin/kotlinc` / `g++` / `TC/`）；
      · **一层传递**：脚本**调用**了上面这类套件（见 `_invokes_toolchain_suite`）。

    只做**一层**，不做完整闭包：`run_ci_wiring_guard.sh` 会调起十几份守卫，
    完整闭包会顺带把一大票**不需要编译器**的套件也排除掉（实测会从 30 涨到 46），
    而快检模式要恰恰是"能在无工具链机器上核对"的最大集合。多排除 = 少核对，
    等于把这份清单本要防的"数字漂移"放回门内。
    """
    by_name = {os.path.basename(p): p for p in suite_scripts()}
    dep = {n for n, p in by_name.items() if any(m in _read(p) for m in TOOLCHAIN_MARKERS)}
    for n, p in by_name.items():
        if n in dep:
            continue
        if _invokes_toolchain_suite(_read(p), dep):
            dep.add(n)
    return dep


# 「按套件给的测量预算」（秒）。默认 300s —— 正常套件几秒到几十秒。
# 少数套件**自身**是分钟级的（框架如此，不是慢在环境上），给足预算才能量到条数；
# 预算不够时 measure() 会把它 kill 掉，读数落成 None —— 那不算"漂移"，
# 但会让 `--check` 报一条假漂移（清单里记着真值、实测却永远是 None）。
#
# ⚠ 这里**只该放宽预算，不该把套件从核对面里拿掉**：拿掉 = 少核对，
# 正是这份清单要防的"数字漂移"回到门内。真放宽不动的（超过 CI 愿意等的上限），
# 才在下面 `SLOW_SUITES` 里显式列出、由快检跳过，且必须写清为什么。
# ⚠ 放宽预算要付的代价是**让 `--check` 每次多等这么久**，所以只在
# "这分钟的等待本身买得到条数"时才用。实测 `run_assertion_anchor_guard_tests.sh`
# 要 ~6.5 分钟（它跑 4 轮 `run_assertion_anchor_guard.py`，后者已从 ~310s
# 降到 ~80s —— 见该守卫里 collect 阶段的注释）—— 4 轮仍超过快检的 300s 预算，
# 于是它进 `SLOW_SUITES` 而不是这里。当前无套件需要放宽。
MEASURE_TIMEOUTS = {}

# 快检（`--check --static-only`）**明确跳过**的套件：放宽预算也买不到条数
# （耗时远超 CI 肯等的上限）。与 `toolchain_dependent()` 同一个思路
# —— 都是"快检这台机器上量不动" —— 但判据不同：那里缺的是工具链，
# 这里缺的是**时间预算**。
#
# 列在这里的套件**不会失去保障**：它的条数由它自己（跑起来就打印汇总）
# 与它被 `.cnb.yml` 调起的那一节保证，只是不进快检的逐套件对账。
# `--check`（非快检、给足时间的模式）仍然会核对它。
SLOW_SUITES = {
    # ~6.5 分钟：内部要跑 4 轮 `run_assertion_anchor_guard.py`（单轮已 ~80s，
    # 见该守卫 collect 阶段的注释）。4 轮仍超过快检的 300s 预算，会被 kill 成
    # `实测=None`，对着清单里的真值就是一条**假漂移** —— 这正是它此前一直挂在
    # `实测=None` 上的原因。
    "run_assertion_anchor_guard_tests.sh",
}


def load_manifest():
    with open(MANIFEST, encoding="utf-8") as fh:
        return json.load(fh)["totals"]


def collect(old=None):
    """跑一遍全部套件。old 给定时，跑不出总数的项沿用旧值（不抹成 null）。"""
    out = {}
    for path in suite_scripts():
        name = "tools/" + os.path.basename(path)
        got = measure(path)
        if got is None and old is not None and old.get(name) is not None:
            # 本机缺工具链 / 依赖真实 native 栈 —— 沿用清单里的值，别把它打回 null
            got = old[name]
        out[name] = got
    return out


def disk_suite_total():
    """目录里离线套件的个数 —— 「N 个套件」这句口径的**唯一真值**。

    与 `suite_scripts()` 同一把尺子（同一个 glob），不另写一份近似：
    两处各写一遍就是判据漂移的起点（模块 J 的原始形态）。
    """
    return len(suite_scripts())


def main():
    args = set(sys.argv[1:])
    old = load_manifest() if os.path.exists(MANIFEST) else None
    if "--check-total" in args:
        # 「N 个套件」的尺子：清单里落的 suite_total 必须等于磁盘上的套件个数。
        # 这是判据⑤ 的**数量维延伸** —— 判据⑤ 只管逐套件的「N 条」，
        # 文档里的**总数**（「83 个套件」「42 → 81 个」）此前无人核对。
        # 静态、秒级，CI 里与接线守卫同一节跑。
        manifest = json.load(open(MANIFEST, encoding="utf-8")) if os.path.exists(MANIFEST) else {}
        want = manifest.get("suite_total")
        got = disk_suite_total()
        if want is None:
            print("清单里没有 suite_total —— 请跑 python3 tools/count_suite_totals.py --write")
            return 1
        if want != got:
            print(f"套件总数与磁盘不符：清单={want} 磁盘={got}")
            return 1
        print(f"套件总数与磁盘一致（{got} 个）")
        return 0
    if "--check" in args:
        want = old or {}
        # `--static-only`：只核对"不依赖 kotlinc / g++ 之外工具链"的快套件。
        # CI 里用这个模式 —— 全套件跑一遍要 5 分钟以上，而接线守卫那一节
        # 已经在跑各守卫了，再跑一遍全套件等于把 CI 时间翻倍。
        # 也能在没有工具链的机器上跑通（这类快套件纯 sh + python3）。
        static_only = "--static-only" in args
        # 传递闭包算一次（不在循环里重复读文件）。
        toolchain_dep = toolchain_dependent()
        drift = []
        checked = 0
        for path in suite_scripts():
            name = "tools/" + os.path.basename(path)
            w = want.get(name)
            if w is None:
                continue
            if static_only and os.path.basename(path) in toolchain_dep:
                continue  # 依赖工具链（含**传递**依赖），快检模式跳过
            if static_only and os.path.basename(path) in SLOW_SUITES:
                continue  # 耗时远超快检定位（见 SLOW_SUITES），快检模式跳过
            # ⚠ 每项先报一行进度，且**立刻 flush** —— 不是日志洁癖。
            # 这一步要串行跑几百个套件、静默几分钟，而 CNB runner 有一条
            # 「任务超过(10m0s)没有任何输出」的**静默超时**：全程不打印的进程
            # 会被当成卡死杀掉。本次就真踩到了 —— 构建停在
            # `=== 套件条数清单 vs 实测（判据⑤ 的另一半）===` 之后一行不输出，
            # 报的是"没有任何输出而被强制关闭"，看起来像套件慢，其实是这段
            # 代码把几百项的输出全憋在最后一起打。进度行同时也让"卡在哪一项"
            # 变得可读（上一次排障全靠人肉 ps 才看出来）。
            print(f"[{checked + 1}] {name} …", flush=True)
            got = measure(path)
            checked += 1
            if got != w:
                drift.append(f"{name}: 清单={w} 实测={got}")
                print(f"    漂移：清单={w} 实测={got}", flush=True)
        if drift:
            print("套件条数清单与实测不符：")
            for d in drift:
                print("  " + d)
            return 1
        print(f"套件条数清单与实测一致（核对了 {checked} 项）")
        return 0
    totals = collect(old=old)
    text = json.dumps(
        {
            "note": "每个套件自报的总条数清单，由 tools/count_suite_totals.py "
                    "--write 生成；null = 该脚本逐条打印 PASS、不汇总"
                    "（或依赖真实 native 栈，本机算不准）。"
                    "suite_total = 目录里离线套件的个数，是文档里「N 个套件」"
                    "这句口径的唯一真值（--check-total 核对）。",
            "suite_total": disk_suite_total(),
            "totals": totals,
        },
        ensure_ascii=False, indent=2, sort_keys=True,
    ) + "\n"
    if "--write" in args:
        with open(MANIFEST, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"已写入 {MANIFEST}")
        return 0
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
