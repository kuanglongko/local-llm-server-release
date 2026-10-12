#!/bin/sh
# 工作区独占锁接线守卫：**凡就地打桩真源码的自测，都必须经过那把锁**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条守卫防的是什么
# ═══════════════════════════════════════════════════════════════════════════
# 几十份 `*_tests.sh` 都走同一条套路：备份真源码 → 就地打桩 → 断言守卫该红 →
# `trap` 还原。它们动**同一份工作树**，并行跑会互相盖桩，产出**假红 / 假绿**
# （漏桩而 CI 照绿 —— 与本仓库一贯在防的「静默失效」同族）。
#
# 修法是给它们加一把共享独占锁（`tools/with_worktree_lock.sh`）。但**加锁是
# 人工动作**：新写一份打桩自测的人很可能忘了加，而"忘加"的表现是**一切照常**
# （锁没加，测试照样绿）—— 没有任何东西会因此变红。这条守卫就补那一件：
# 把"漏加锁"从一个**静默**的疏漏，变成一个**响亮**的失败。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据
# ═══════════════════════════════════════════════════════════════════════════
# ① 每一份"就地打桩真源码"的自测，都 `.` 了 `with_worktree_lock.sh`；
# ② 且在其中**调用了** `worktree_lock_acquire` 或 `with_worktree_lock`；
# ③ 且调用点**在打桩/还原 trap 之前**（锁必须在动源码前拿到）；
# ④ 反向：锁脚本自身对**同一进程**可重入（不自锁死）—— 见它的自测。
#
# "就地打桩真源码"的判据（结构特征，不锚"文件里出现过某单词"）：
#   · 存在 `trap ... restore|cleanup ...` 行（退出还原），**且**
#   · 存在对**仓库内文件**做的备份副本（`cp <源文件> ...bak` / `${...}.bak`）。
# 两者同时成立，说明它备份了真源码并靠 trap 还原 —— 正是会互相盖桩的那类。
# 只比其中之一会误收：单 `trap 'rm -rf $TMP'` 的脚本只清临时目录，不动源码。
#
# 边界（写清楚）：本守卫判的是"**接线**在不在"，判不了"锁真的互斥吗"——
# 后者由 `tools/run_worktree_lock_tests.sh` 对着真并发跑一遍来判。
#
# 纯 sh + python3 标准库。运行：sh tools/run_worktree_lock_guard.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本守卫只用标准库）"; exit 2; }

"$PY" - <<'PYEOF'
import glob
import os
import re
import sys

ok = 0
bad = 0

def check(name, cond, detail=""):
    global ok, bad
    if cond:
        print(f"PASS  {name}"); ok += 1
    else:
        print(f"FAIL  {name}" + (f" —— {detail}" if detail else "")); bad += 1

LOCK = "with_worktree_lock.sh"
ACQUIRE_CALLS = ("worktree_lock_acquire", "with_worktree_lock ")

# 判据锚「**退出时还原真源码**」这个结构特征，而不是"文件里出现过某个单词"。
# 两道合取，缺一不可：
#   ① 有一条 `trap`，处理器名含 restore / cleanup / _on_exit 之一
#      （本仓库里这三种命名**专指**"退出还原真源码"：`trap 'restore; rm -rf $TMP'`、
#       `trap restore_all ...`、`trap _on_exit ...`）；
#   ② 该处理器（或同名的函数体）里**确实有一条把文件拷回仓库路径的 cp**。
# 只取①会误收"只清临时目录"的 `cleanup() { rm -rf "$TMP"; }`（锁自测里就有一个），
# 只取②会把循环变量当源、漏掉一批（`for f in "$A" "$B"; do cp ... "$f"; done`）。
# 两道合取刚好把"真还原了真源码"的那批框住，收放都钉在结构上。
_RESTORE_HANDLER_RE = re.compile(r'^\s*trap\s+\S*\b(restore|cleanup|_on_exit)\b')
# 仓库内路径（含变量声明间接引用，见 var_to_path）
_REPO_TOKEN_RE = re.compile(r'(app/src/|vendor/|docs/|scripts/|tools/(?!stub_jni/)|README|CHANGELOG|\.cnb\.yml|\.kt\b|\.cpp\b|\.h\b)')

def _var_to_path(text):
    m = {}
    for ln in text.split("\n"):
        mm = re.match(r'\s*([A-Za-z_][A-Za-z0-9_]*)=["\']?([^"\'\s;]+)', ln)
        if mm:
            m[mm.group(1)] = mm.group(2)
    return m

def _backs_up_repo_file(text):
    """有没有 `cp <仓库内文件或指向它的变量> <临时目录>/...` —— 即备份真源码。

    只认"备份**出去**"这一个方向：源是仓库路径（可能是变量，
    如 `for f in "$KA" ...; do cp "$f" "$TMP/$(basename "$f").bak"`），
    目标是 `$TMP` / `$STASH` 之类临时目录。
    """
    vars_ = _var_to_path(text)
    # 循环变量：`for f in "$A" "$B"; do ...` —— 把 f 记成"可能是仓库文件"，
    # 只要 in 列表里任一变量指向仓库路径（`cp "$f" "$TMP/..."` 这类就靠它兜住）。
    loop_vars = set()
    for ln in text.split("\n"):
        m = re.search(r'for\s+([A-Za-z_][A-Za-z0-9_]*)\s+in\s+([^;]+);?\s*do', ln)
        if not m:
            continue
        for tok in re.findall(r'\$\{?([A-Za-z_][A-Za-z0-9_]*)', m.group(2)):
            if _REPO_TOKEN_RE.search(vars_.get(tok, "")):
                loop_vars.add(m.group(1))
                break
    def is_repo_ref(ref):
        ref = ref.strip().strip('"')
        if _REPO_TOKEN_RE.search(ref):
            return True
        if ref.startswith("$"):
            nm = ref[1:].split("/")[0].split("}")[0]
            if nm in loop_vars:
                return True
            return bool(_REPO_TOKEN_RE.search(vars_.get(nm, "")))
        return False
    for ln in text.split("\n"):
        # `cp` 可能不在行首（`for f in ...; do cp ...; done`），所以按行内
        # `\bcp\b` 命中即可，真正认 `cp` 交给下面的逐语句切分。
        if not re.search(r'\bcp\b', ln):
            continue
        for stmt in ln.split(";"):
            toks = [t for t in stmt.strip().split() if not t.startswith("-")]
            # 剥掉 shell 关键字，让 `do cp ...` / `then cp ...` 也能被认成一条 cp。
            while toks and toks[0] in ("do", "then", "else", "elif", "if", "!"):
                toks.pop(0)
            if not toks or toks[0] != "cp" or len(toks) < 3:
                continue
            src = toks[1]
            # 目标可能是 `"$TMP/$(basename "$f").bak"` 这种被引号/命令替换切碎的 token，
            # 所以对**整条语句**找临时目录落点，不只看最后一个 token。
            rest = " ".join(toks[2:])
            if is_repo_ref(src) and re.search(r'\$\{?(TMP|STASH|OUT)\b|/tmp/|mktemp', rest):
                return True
    return False

def is_inplace_stubber(text):
    """备份真源码 + 退出时还原 —— 就是会互相盖桩的那类。"""
    has_handler = any(_RESTORE_HANDLER_RE.match(ln) for ln in text.split("\n"))
    return has_handler and _backs_up_repo_file(text)

def sources_lock(text):
    return bool(re.search(r'^\s*\.\s+"?\$\(dirname\s+"\$0"\)/with_worktree_lock\.sh', text, re.M))

def calls_acquire(text):
    return any(re.search(r'^\s*' + re.escape(c).rstrip() + r'\b', text, re.M) for c in ACQUIRE_CALLS)

def acquire_line_no(text):
    for i, ln in enumerate(text.split("\n"), 1):
        s = ln.strip()
        if s.startswith("worktree_lock_acquire") or s.startswith("with_worktree_lock"):
            return i
        if "with_worktree_lock.sh" in ln and s.startswith("."):
            # the source line itself doesn't count as the call; keep looking
            pass
    return None

def stubtrap_line_no(text):
    for i, ln in enumerate(text.split("\n"), 1):
        if _RESTORE_HANDLER_RE.match(ln):
            return i
    return None

files = sorted(glob.glob("tools/*_tests.sh"))
stubbers = [f for f in files if is_inplace_stubber(open(f, encoding="utf-8").read())]

print(f"—— 就地打桩自测共 {len(stubbers)} 份 ——")
for f in stubbers:
    print(f"      {os.path.basename(f)}")
print()

# ── 判据①：每一份就地打桩自测都 `.` 了锁脚本 ──────────────────────────────
for f in stubbers:
    text = open(f, encoding="utf-8").read()
    check(f"① {os.path.basename(f)} 引入工作区锁", sources_lock(text),
          "没有 `. \"$(dirname \"$0\")/with_worktree_lock.sh\"`")

# ── 判据②：且在锁内执行（真的调了抢锁） ────────────────────────────────────
for f in stubbers:
    text = open(f, encoding="utf-8").read()
    check(f"② {os.path.basename(f)} 真的抢锁", calls_acquire(text),
          "引入了锁脚本但没调用 acquire（引了不用 = 没接上）")

# ── 判据③：抢锁在还原 trap 之前（必须在动源码前拿到锁） ────────────────────
for f in stubbers:
    text = open(f, encoding="utf-8").read()
    a = acquire_line_no(text)
    t = stubtrap_line_no(text)
    if a is None:
        check(f"③ {os.path.basename(f)} 抢锁早于还原 trap", False, "找不到 acquire 调用")
    elif t is None:
        check(f"③ {os.path.basename(f)} 抢锁早于还原 trap", False, "找不到还原 trap")
    else:
        check(f"③ {os.path.basename(f)} 抢锁早于还原 trap", a < t,
              f"acquire 在第 {a} 行、还原 trap 在第 {t} 行（锁必须在打桩前拿到）")

# ── 判据④：锁脚本自己存在且可被 source（不是空壳） ────────────────────────
lock_path = "tools/with_worktree_lock.sh"
check("④ 锁脚本存在", os.path.isfile(lock_path))
if os.path.isfile(lock_path):
    lt = open(lock_path, encoding="utf-8").read()
    check("④ 锁脚本定义了 worktree_lock_acquire", "worktree_lock_acquire()" in lt)
    check("④ 锁脚本定义了 with_worktree_lock", "with_worktree_lock()" in lt)
    check("④ 锁脚本用 flock 或 mkdir 兜底", ("flock" in lt) and ("mkdir" in lt),
          "既没 flock 也没 mkdir 兜底")

# ── 判据⑤（反向守住）：不作差类的脚本不该被误收 ──────────────────────────
# 只清临时目录、不动真源码的自测不该被上面判据误判为"就地打桩"。
false_pos = []
for f in files:
    if f in stubbers:
        continue
    text = open(f, encoding="utf-8").read()
    if re.search(r"\.bak", text) and not re.search(r"(app/src|vendor/|README|\.kt\b|\.cpp\b)", text):
        false_pos.append(f)
check("⑤ 未误收（只清 $TMP、不备份真源码的自测不在此列）", not false_pos,
      f"疑似误收：{false_pos}")

print()
if bad == 0:
    print(f"=== 工作区锁接线守卫：PASS {ok} / FAIL 0 ===")
    sys.exit(0)
print(f"=== 工作区锁接线守卫：PASS {ok} / FAIL {bad} ===")
sys.exit(1)
PYEOF
