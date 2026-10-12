#!/usr/bin/env python3
"""守卫的**判据自身**有没有"永远问不出问题"—— 甲-1：判据「被执行过」。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一份（Issue #174 / 模块甲-1）
═══════════════════════════════════════════════════════════════════════════
本仓库的守卫判据绝大多数是 `grep -q 'PAT' $FILE`。这类断言有一个**它自己看不见**的坏法：

  PAT 在文件里**一个都不命中**时，这条断言就再没问过任何问题 ——
  正向位置（`grep -q`）下它**恒红**（有人 `|| true` 掉，彻底失效）；
  负向位置（`! grep -q`）下它**恒绿**（"不出现"永远成立，等于没写）。

两类都**静默**：守卫照样打印 PASS/FAIL，只是那一条的信息量是 0。
本仓库已经栽过同款（`0.9.142` 那个多打的方括号），而没有任何机制能发现下一处 ——
因为**判据网里没有一件东西问过"这条断言真的能命中吗"**。

这一份就补这一件。它把每个守卫**真跑一遍**（用记账 shim 换掉 grep），
拿到每条 grep 收到的**真实 pattern**（shell 两层转义后），再逐条判：

  ① **正向断言必须"有话说"**：文件存在、pattern 却在整个仓库里零命中
     -> 恒红/恒静默，报出来；
  ② **负向断言必须"能被打脸"**：pattern 若在整个仓库零命中，
     "不出现"就永远成立 —— 这条负向判据**没有任何东西能触发它**，同样是静默的；
  ③ **计数类不许 `-ge 1`**：`grep -c` 连注释一起数，`-ge 1` 在只有一行注释时也绿。

边界（写在收口文档里）：本守卫只判"pattern 能不能命中"，判不了
"命中之后那个断言在逻辑上对不对" —— 后者要靠各守卫自己的双向自测。

纯 python3 标准库 + sh。运行：python3 tools/run_assertion_anchor_guard.py

性能（2026-10-02）：三条都只动**执行方式**、不动判据 ——
  · `classify` 按 (pattern, file, flags) **去重**：守卫里"跑一遍别的套件"那一跳
    会把被驱动套件的 grep 原样重记（31240 -> 798 / 15259 -> 328 行）；
  · 判据② 复用第一步已收的 `_rows`，不再把 40 份 ledger 真跑第二遍；
  · `repo_hit` 加结果缓存、预扫一次填满，逐条判定变查表。
整份从 ~310s 降到 ~80s；自测（跑 4 轮）从 18 分钟降到 ~6.5 分钟。
⚠ ledger 那一步仍**串行**：那些守卫会就地打桩真源码再 trap 还原，并发会互相盖桩。
"""
import glob
import os
import re
import signal
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "assertion_anchor"))
from classify import classify  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

ok = 0
bad = 0

# ── 总时限 ───────────────────────────────────────────────────────────────
# 本守卫要对 40 份守卫各真跑一遍（各自又可能驱动别的套件），再对每条判据查命中表 ——
# 优化后本机约 80s（此前 ~310s，见下面 collect 的注释）。一旦某个子进程挂住
# （历史踩过 shim 递归），整轮会无限期拖住 CI，而"挂住"与"还在跑"在 CI 里同形。
# 这里给整轮一个上限：
# 默认 600s（实测的数倍余量，容器更慢也够），可用 ASSERTION_ANCHOR_TIMEOUT=<秒> 覆盖，置 0 关闭。
# 超时**明确报错退出**，而不是静默挂死。
_TOTAL_TIMEOUT = float(os.environ.get("ASSERTION_ANCHOR_TIMEOUT", "600"))


def _install_total_timeout(seconds):
    if seconds <= 0:
        return

    def _on_alarm(_sig, _frm):
        print(f"\nFAIL  —— 整轮超过 {seconds:g}s 上限（ASSERTION_ANCHOR_TIMEOUT）"
              "，判据锚定未跑完；调大上限或查是哪份守卫挂住了", file=sys.stderr)
        sys.exit(1)

    signal.signal(signal.SIGALRM, _on_alarm)
    signal.alarm(int(seconds) if seconds >= 1 else 1)


_install_total_timeout(_TOTAL_TIMEOUT)


def check(name, cond, detail=""):
    global ok, bad
    if cond:
        print(f"PASS  {name}")
        ok += 1
    else:
        print(f"FAIL  {name}" + (f" —— {detail}" if detail else ""))
        bad += 1


# ── 全仓命中：**缓存 + 并发**（性能，2026-10-02）─────────────────────────
# v1 对**每一条**断言各起一次 `grep -r` 全仓扫描。实测 1241 条正向断言里
# 只有 1154 个**唯一** (pattern, mode) —— 重复的先把同一份扫描白做了多次；
# 叠加"判据② 把 40 份 ledger 真跑第二遍"，本机 ~310s（容器更慢）。
# 于是自测要 18 分钟，被快检的 300s 预算 SIGKILL（正是上一轮记的那条遗留）。
#
# 这次只动**执行方式**，不动判据：
#   · 同一个 (pattern, mode) 只算一次 —— 结果缓存，第二次起零成本；
#   · 互不依赖的 **grep 查询**并发跑（线程池）。每条 grep 的参数、输入、
#     判定分支与 v1 **逐字节相同**，只是不再重复。
# 判据仍是"这条 grep 会不会命中"，由 grep 自己算（第一版不带 flags，把
# 28 条 `-E` 判据误报成零命中，这一层必须留给 grep）。
#
# ⚠ 只有**只读的 grep 查询**能并发。守卫本身（`classify` 那一步）**串行** ——
# 见下面 collect 处的注释。
from concurrent.futures import ThreadPoolExecutor as _Pool

_PAR = _Pool(max_workers=min(24, (os.cpu_count() or 4) * 3))
_ROW_CACHE = {}          # (pattern, mode) -> bool


def _repo_hit_uncached(pattern, flags):
    """pattern 在整个仓库里有没有命中。**与 v1 逐字节相同**，含两段判定。"""
    # 由原 flags 推出递归版：保留 E/F 模式，去掉 n/q/c/l，补 -r
    mode = ""
    if "E" in flags or "e" in flags:
        mode = "E"
    elif "F" in flags:
        mode = "F"
    base = ["grep", "-rq"] + ([f"-{mode}"] if mode else [])
    # 只扫**源码与文档**，**不扫 tools/run_*guard*.sh 自己**：
    # 判据 pattern 常常会出现在自己的自测桩里（波形的"打错一个字符"桩就是），
    # 把守卫/测试文件算进来会让"错 pattern 也命中"—— 本模块开发时真踩到，
    # 桩① 因此假绿。tools/ 下只扫非 guard 的（如单测 .kt/.cpp）。
    try:
        r = subprocess.run(
            base + ["--", pattern, "app", "docs", "scripts",
                    ".cnb.yml", "README.md", "HTP-STATUS.md", "CHANGELOG.md"],
            capture_output=True,
        )
        if r.returncode == 0:
            return True
        # tools/ 下排除 guard 与它们的自测
        r2 = subprocess.run(
            base + ["--", pattern, "tools"],
            capture_output=True,
        )
        if r2.returncode != 0:
            return False
        hits = subprocess.run(
            ["grep", "-rl"] + ([f"-{mode}"] if mode else []) + ["--", pattern, "tools"],
            capture_output=True, text=True,
        ).stdout.splitlines()
        return any(not re.search(r"run_\w*guard", h) for h in hits)
    except Exception:  # noqa: BLE001
        return True  # 查不动时不误报


def repo_hit(pattern, flags):
    """带缓存的单条查询（判据见 `_repo_hit_uncached`）。"""
    key = (pattern, "E" if ("E" in flags or "e" in flags) else ("F" if "F" in flags else ""))
    if key not in _ROW_CACHE:
        _ROW_CACHE[key] = _repo_hit_uncached(pattern, flags)
    return _ROW_CACHE[key]


def warm_hit_cache(patterns_with_flags):
    """把全量待核 (pattern, flags) **并发**预跑一遍，把 `repo_hit` 的缓存填满。

    这是本文件唯一的性能改动：顺序仍是"先 A 段后 B 段"、判据一字未改，
    只是不再一条一条串行等 I/O。返回的缓存让后面逐条判定变成查表。
    """
    uniq = {}
    for pat, flags in patterns_with_flags:
        mode = "E" if ("E" in flags or "e" in flags) else ("F" if "F" in flags else "")
        uniq.setdefault((pat, mode), flags)
    list(_PAR.map(lambda kv: repo_hit(kv[0][0], kv[1]), uniq.items()))


GUARDS = sorted(glob.glob("tools/run_*guard.sh"))
check("扫到守卫脚本（防下面几条恒真）", len(GUARDS) >= 20, f"实际 {len(GUARDS)}")

# `run_ci_wiring_guard.sh` 是**聚合型**：它跑别的守卫（`sh -n` 自证那一节），
# 于是它的 ledger 里混进了别的守卫的 grep —— 那些 pattern 的 `!` 前缀不在它自己的
# 源码里，静态匹配必然落空、被误判成"正向零命中"。所以**它只做独立判定之上的聚合**，
# 不参与"逐条锚定"。它自己的判据由它自己的自测与上面的判据①~③ 覆盖。
AGGREGATOR = "run_ci_wiring_guard.sh"

# 只核**明确指向仓库内某个文件**的断言（排除管道/嵌套命令里被当成文件名的片段，
# 如 `fnbody $JNI ... | grep -q '/proc/self/maps'` 里的 `maps`）。
REAL_PREFIX = ("app/", "tools/", "scripts/", "docs/", "vendor/")
REAL_EXACT = (".cnb.yml", "README.md", "HTP-STATUS.md", "CHANGELOG.md")


def is_real_target(f):
    return (f.startswith(REAL_PREFIX) or f in REAL_EXACT) and os.path.isfile(f)

# ── 全量收集：每个守卫真跑的 grep 断言 ────────────────────────────────────
positive_broken = []      # 判据①：正向断言，pattern 全仓零命中
total_pos = 0
total_neg = 0

# 第一步：把 40 份守卫的 `ledger`（真跑一遍守卫、拿它发出的每条 grep）
# **串行**收上来，且**只收一次**（下面判据② 复用这份结果，不再重跑）。
# ⚠ 这一步**不能并发**：`run_ci_wiring_guard.sh` / `run_schema_sampler_guard.sh`
# 等守卫会**就地打桩真源码**再靠 trap 还原（v1 机制），它们动的是同一份工作树。
# 并发跑时一个守卫的还原窗口会盖掉另一个的桩、或把桩当基线写回 ——
# 实测并发跑一轮就在 `llama_jni.cpp` 里留下一处桩（并因此多出一条假"零命中"）。
# 串行是原行为，也是这里唯一安全的顺序；去重后它只要几十秒（见 classify 注释）。
_rows = [(g, classify(g)) for g in GUARDS]

# `run_ci_wiring_guard.sh` 会**跑别的守卫**（`sh -n` 自证那一节），
# 于是它的 ledger 里混进了别的守卫的 grep —— 那些 pattern 的 `!` 前缀
# 不在它自己的源码里，静态匹配必然落空、被误判成"正向"。
# 所以先建一份**全局负向 pattern 集合**（静态扫全部守卫），
# 逐条判定时以它为准：只要某个守卫里这条 pattern 是负向的，就不当正向报。
ALL_NEG = set()
for _g, _r in _rows:
    for pat, f, neg, flags in _r:
        if neg:
            ALL_NEG.add(pat)
            ALL_NEG.add(pat.replace('\\"', '"'))

# 第二步：先把**所有**待核正向断言的 (pattern, flags) 并发预跑一遍、
# 把 `repo_hit` 的缓存填满。之后再逐条判定 —— 那时是一条条的**查表**，
# 不再是上万个串行 grep（本文件唯一的性能改动，判据一字未改）。
warm_hit_cache(
    (pat, flags)
    for _g, _r in _rows
    for pat, f, neg, flags in _r
    if not neg and pat not in ALL_NEG and is_real_target(f)
    and not re.search(r"run_\w*guard", f)
)

for g, _r in _rows:
    if os.path.basename(g) == AGGREGATOR:
        continue
    for pat, f, neg, flags in _r:
        if not neg and pat in ALL_NEG:
            neg = True      # 全局看它确是负向断言
        if not is_real_target(f):
            continue
        if neg:
            total_neg += 1
        else:
            total_pos += 1
            # 目标就是守卫/自测文件本身的断言（`grep 'stripstr' tools/run_mmap_pin_guard.sh`）：
            # 它们刻意锚"判据网自身的写法"，pattern 只可能活在这一个文件里 ——
            # 直接核该文件即可，不套"全仓找"（全仓扫描按设计排除守卫文件）。
            if re.search(r"run_\w*guard", f):
                hit = subprocess.run(
                    ["grep", "-q"] + ([f"-{('E' if 'E' in flags else 'F')}"] if ("E" in flags or "F" in flags) else [])
                    + ["--", pat, f],
                    capture_output=True,
                ).returncode == 0
            else:
                hit = repo_hit(pat, flags)
            if not hit:
                positive_broken.append((g, pat, f))

check("扫到了正向断言（防下面几条恒真）", total_pos >= 200, f"实际 {total_pos}")
check("扫到了负向断言（防下面几条恒真）", total_neg >= 20, f"实际 {total_neg}")

# ── 判据①：正向断言的 pattern 必须有命中 ─────────────────────────────────
check(
    f"每条正向 grep 断言都能命中（零命中：{len(positive_broken)} 条）",
    not positive_broken,
    "; ".join(f"{os.path.basename(g)}: {p!r}" for g, p, _f in positive_broken[:5]),
)

# ── 判据②：负向断言的"能被打脸"由**双向自测**证明 ───────────────────────
# 一条负向断言（"X 不出现"）在**当前树里** pattern 零命中，是**正确**的 ——
# 那正是它要的状态，不能据此报它"永不触发"（第一版就这么误报了 34 条）。
# 它"能不能被打脸"只有一种证明方式：有一份自测往里**打桩**，让守卫变红。
# 所以这一条查的是：带负向断言的守卫，必须有对应的 `run_XXX_guard_tests.sh`。
# ⚠ 复用第一步已收的 `_rows`，**不再** `classify(g)` 重跑一遍：
# 那一版把 40 份守卫的 ledger 又串行真跑了第二遍 —— 上半场 80s、下半场 148s，
# 整份 310s 里有一半是这**同一批子进程**跑了两次。结果完全一样（classify 是
# 纯函数），只是白等。顺带：重跑 `run_schema_sampler_guard.sh` 还多一次
# 打桩/还原窗口，是漏桩概率的另一处来源。
neg_guards = []
_neg_guards_all = [g for g, r in _rows if any(neg for _p, _f, neg, _fl in r)]
for g in _neg_guards_all:
    has_neg = True
    test = g[:-3] + "_tests.sh"        # xxx_guard.sh -> xxx_guard_tests.sh
    referenced = os.path.exists(test) and os.path.basename(g) in open(test, encoding="utf-8").read()
    if not referenced:
        neg_guards.append(os.path.basename(g))

check("扫到了带负向断言的守卫（防下一条恒真）", len(_neg_guards_all) >= 10)
check(
    f"带负向断言的守卫都有双向自测（缺：{len(neg_guards)} 个）",
    not neg_guards,
    "; ".join(neg_guards[:5]),
)

# ── 判据③：计数类断言的阈值必须**由非注释行**满足 ─────────────────────────
# `grep -c PAT FILE` 会把**注释行**一起数。所以 `-ge N` 的 N 若只被注释撑到，
# 这条断言就是在替一段说明文字站岗 —— 而注释恰恰可能正在说"这里不该这么写"。
# 判法：把该文件里**非注释**（不以 `//`、`*`、`#` 开头的行）的命中数数出来，
# 要求 >= 断言里的 N。数不够 = 阈值虚高。
COUNT_RE = re.compile(r"grep\s+-c\s+(?:-[A-Za-z]+\s+)?'([^']*)'\s+(\S+)[^\n]*?-ge\s+(\d+)")
weak_counts = []
count_checked = 0
for g in GUARDS:
    src = open(g, encoding="utf-8").read()
    varmap = dict(re.findall(r'^\s*([A-Z][A-Z0-9_]*)=(\S+)\s*$', src, re.M))
    for m in COUNT_RE.finditer(src):
        pat, target, n = m.group(1), m.group(2), int(m.group(3))
        tgt = target.replace("\\$", "$")
        mm = re.match(r'^\$\{?([A-Z][A-Z0-9_]*)\}?(.*)$', tgt)
        if mm:
            tgt = (varmap.get(mm.group(1), "") + mm.group(2)).lstrip("/")
        if "/" not in tgt or not os.path.exists(tgt):
            continue
        # 非注释行的命中数
        try:
            real = subprocess.run(["grep", "-cE", f"^[^/*#]*{re.escape(pat)}", tgt],
                                  capture_output=True, text=True).stdout.strip()
            real = int(real or 0)
        except Exception:  # noqa: BLE001
            continue
        count_checked += 1
        if real < n:
            weak_counts.append((g, pat, n, real))

check("核到了计数类断言（防下一条恒真）", count_checked >= 1, f"实际 {count_checked}")
check(
    f"计数类断言的阈值由非注释行满足（虚高：{len(weak_counts)} 处）",
    not weak_counts,
    "; ".join(f"{os.path.basename(g)}: {p!r} 要>={n} 实际非注释 {r}" for g, p, n, r in weak_counts[:5]),
)

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
