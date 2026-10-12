#!/bin/sh
# 「被文档宣称通过的离线测试，必须真的在 CI 里跑」守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么要有这个文件
# ═══════════════════════════════════════════════════════════════════════════
# 本轮在 `main` 上撞到两处**同一形态**的既有问题：
#
#   · `tools/run_cors_live_tests.sh` —— README / HTP-STATUS 都写着
#     「16 条全通过」，但它**从未被接进 `.cnb.yml`**，于是没人发现它其实
#     **在 main 上根本编不过**（编译清单漏了页面本体 `WebChatPage.kt` 与
#     `SamplingParams.kt`；运行期 classpath 漏了 `json.jar`，android.jar 里抛
#     `Stub!` 的空壳 `org.json` 被当成真实现）。
#   · `tools/run_chat_buffer_tests.sh` —— README / HTP-STATUS 同样写着
#     「全通过」，也**从未被接进 CI**（这一份本体是好的，但没有任何东西保证
#     它以后还是好的）。
#
# 这两件的**共性**不是"代码写错了"，而是**证据链断了**：
# 文档里的数字（"16 条"/"全通过"）来自一次人工运行，之后没有任何机制
# 让它随代码一起演进。一份不跑的测试与"没有这份测试"等价，且**比没有更坏** ——
# 它让文档里的数字变成假证据，下一个人据此判断"这块有保障"。
#
# 所以这里不测"某脚本的断言对不对"（那是各脚本自己的事），只测
# **"文档宣称的覆盖"与"CI 实际执行"是否对得上**。这是唯一能让上面那两件事
# 不再发生的地方：脚本坏了会由它自己的断言暴露，而"它根本没被跑"只有这里能发现。
#
# 全是静态断言，不依赖任何工具链。运行：sh tools/run_ci_wiring_guard.sh
set -e
cd "$(dirname "$0")/.."

CI=.cnb.yml
# 调用清单已经不止 `CI` 这一个文件：`.cnb.yml` 里那个 stage 的内联脚本
# 原本有 170 KB，CNB runner 把它当命令行参数交给 `/bin/bash` 时直接
# `fork/exec: argument list too long`（脚本一行都没跑，构建就红了）。
# 正文因此挪到了 `scripts/ci-apk-stage.sh`，`.cnb.yml` 里只剩一句调用。
#
# ⚠ 若只扫 `CI` 一个文件：本守卫的**全部**判据会静默翻面 —— `called` 空 ->
# 判据①③ 报"一个都没接"，判据④⑥⑦⑨ 因为集合为空而恒绿。也就是说
# "挪个文件"这件事会把守卫从"太严"直接变成"全假绿"。所以扫描面必须跟着
# 调用实际所在的位置走，且**由脚本自己断言扫到的东西非空**（下面几处 [ -s ]）。
SCRIPTS_DIR=scripts
README=README.md
STATUS=HTP-STATUS.md
SUMMARY=docs/REVIEW-110-SUMMARY.md

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取"磁盘上真实存在的离线测试脚本"与"CI 里被调用的脚本"两个集合并做差。
# 用 python3 而不是 shell 循环：README 与 HTP-STATUS 里的引用形式不统一
# （带/不带反引号、放在表格里），纯 shell 的 grep 会漏掉一部分，
# 而"漏掉的那部分"恰恰就是本次要抓的对象。
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
python3 - "$CI" "$SCRIPTS_DIR" "$README" "$STATUS" "$SUMMARY" > "$TMP/report.txt" <<'PY'
import json, os, re, sys, glob

ci_path, scripts_dir, readme_path, status_path, summary_path = sys.argv[1:6]

def read(p):
    try:
        return open(p, encoding='utf-8').read()
    except OSError:
        return ''

# 「CI 到底调了哪些脚本」由**两处**合起来回答：`.cnb.yml` 本身，以及它引用的
# stage 脚本（`scripts/ci-*.sh`）。两处任一为空都要在下面报出来（不静默）。
ci = read(ci_path)
stage_files = sorted(glob.glob(os.path.join(scripts_dir, 'ci-*.sh')))
ci += '\n' + '\n'.join(read(p) for p in stage_files)
print("CI_SOURCES", len(ci.splitlines()), *stage_files)

# 1) CI 里被调用的脚本集合。调用形式统一为 `sh tools/run_*.sh` /
#    `bash tools/run_*.sh` / `python3 tools/run_*.py`（含 REQUIRED=0 前缀）。
called = set(re.findall(r'(?:^|\s)(?:sh|bash|python3)\s+(tools/run_[A-Za-z0-9_]+\.(?:sh|py))', ci))

# 2) 磁盘上真实存在的脚本集合。
on_disk = set(p[2:] if p.startswith('./') else p for p in glob.glob('tools/run_*.sh') + glob.glob('tools/run_*.py'))

# 3) 文档里被**宣称**的脚本集合（README + HTP-STATUS + docs/ 下全部收口文档）。
#    SUMMARY 与 docs/*.md 都纳入：它们会把套件名字与条数当成证据引用；
#    不纳入的话就是下一个"手抄漂移"源（J-1 同族）。
#
#    为什么从"只扫 SUMMARY 一份"改成"扫 docs/ 全量"：Issue #174 起每轮自检都会
#    在 docs/ 下落一份收口文档（乙-1、乙-2……）。若扫描面仍是写死的一份，
#    新文档就是**下一份"看着像证据、实则无人核对"的文档** —— 正是本守卫要防的形态。
#    改成 glob 后，任何新收口文档一落地就自动进判据①③⑤ 的扫描面。
SCRIPT_RE = re.compile(r'(tools/run_[A-Za-z0-9_]+\.(?:sh|py))')
doc_files = [readme_path, status_path, summary_path] + sorted(glob.glob('docs/*.md'))
docs = set()
for p in doc_files:
    try:
        docs |= set(SCRIPT_RE.findall(open(p, encoding='utf-8').read()))
    except OSError:
        pass
# 判据⑦ 用：小结里**经 SCRIPT_RE 命中**的脚本数（与 docs 集合同一把正则）。
# 不在文件里 grep 一遍（那是存在性断言），而是复用"真的进了集合"的那条通路。
summary_scripts = len(set(SCRIPT_RE.findall(open(summary_path, encoding='utf-8').read())))

# 判据①：磁盘上有、CI 里没跑 —— 就是本次要修的形态。
unwired = sorted(on_disk - called)
# 判据②：文档宣称、磁盘上没有 —— 文档指向了一个不存在的证据。
phantom = sorted(docs - on_disk)
# 判据③：文档宣称、磁盘上有、但 CI 没跑 —— 最坏的一种：
#        文档拿一个不跑的脚本当证据。
doc_unwired = sorted(docs & on_disk - called)

print("SUMMARY_SCRIPTS", summary_scripts)
print("UNWIRED", len(unwired), *unwired)
print("PHANTOM", len(phantom), *phantom)
print("DOC_UNWIRED", len(doc_unwired), *doc_unwired)

# 判据④：CI 用 `REQUIRED=0 <script>` 调用它，它就必须**真的读** REQUIRED。
# 否则那一行 `REQUIRED=0` 是个幻觉：缺工具链时脚本照旧硬 exit 2，
# 把整轮 APK 构建拦下 —— 而 .cnb.yml 上明明写着"允许退化"。
# 2026-09-21 真实撞到：repo1.maven.org 限流返回 429 -> json.jar 拉不到 ->
# `REQUIRED=0 sh tools/run_thinking_tests.sh`（它当时不读 REQUIRED）
# 直接 exit 2，构建停在下载一个 78KB 的 jar 上。
# 注意这里只查"CI 以 REQUIRED=0 调用"的那批，不是要求所有脚本都支持它。
req0 = set(re.findall(r'REQUIRED=0\s+(?:sh|bash)\s+(tools/run_[A-Za-z0-9_]+\.sh)', ci))
ignores = []
for script in sorted(req0):
    try:
        body = open(script, encoding='utf-8').read()
    except OSError:
        continue  # 挂在 ②号判据下报
    # 判据必须锚定"REQUIRED 被**取值**了"，不能只判"文件里出现过这个词" ——
    # 注释里写一句 `# REQUIRED=0 时退化` 就能骗过存在性断言（本项目已在
    # 鉴权守卫桩② 栽过一次同款：pattern 写成到处都有的文本就恒真）。
    # 这里要求出现 `${REQUIRED:-` 或 `$REQUIRED` 的**展开形式**。
    if not re.search(r'\$\{REQUIRED:-|\$\{REQUIRED=|(^|[^A-Za-z_])"\$REQUIRED"|(^|[^A-Za-z_])\$REQUIRED([^A-Za-z_]|$)', body):
        ignores.append(script)
print("REQ0_IGNORED", len(ignores), *ignores)

# 判据⑥用的集合：CI 真的会调用的 `.sh`（与判据① 复用同一个 `called`，只留 `.sh`）。
# 输出格式与上面几行一致，交给 shell 侧同一个 field() 解析。
called_sh = sorted(p for p in called if p.endswith(".sh"))
print("CALLED_SH", len(called_sh), *called_sh)

manifest_path = os.path.join(os.path.dirname(ci_path) or ".", "tools", "suite-counts.json")
manifest = {}
if os.path.exists(manifest_path):
    try:
        manifest = json.load(open(manifest_path, encoding="utf-8"))
    except (OSError, ValueError):
        manifest = {}
# 逐套件条数取自 totals（判据⑤）；总数取自 suite_total（判据⑧）。
# 同一份清单、同一把尺子 —— 两处各读一份近似就是判据漂移的起点。
mb = manifest.get("totals", {}) if isinstance(manifest, dict) else {}


# ── 判据⑤：文档里写的「N 条」必须等于清单里该脚本的条数 ────────────────────
# 这才是「当年那起事故」的正中靶心。
#
# 判据①③（UNWIRED / DOC_UNWIRED）比的全是**脚本名字**：
#   · 「run_cors_live_tests.sh 有没有接进 CI」—— 管 YES
#   · 「它到底跑了**几条**、和文档写的 16 条对不对得上」—— 不管 NO
# 而 0.9.84 那起事故的核心证据恰恰是**数字**：文档写着「16 条全通过」，
# 脚本却根本编不过。名字在 CI 清单里、也真的被执行了 —— 判据①③ 全绿。
#
# 2026-09-23 复核出同族漂移 9 处（HTP-STATUS §5.2），最大一处 77 -> 132。
# 根因与当年一致：数字靠人工维护，没有任何机制让它随代码演进。
#
# 做法：**不在这里跑套件**（分钟级操作压进接线守卫会把 CI 拖垮，且一旦
# 超时中断，中途被打桩的工作区会留在原地，比不查更坏）。条数由
# `tools/suite-counts.json`（`tools/count_suite_totals.py --write` 生成）
# 提供，这里只做**静态比对**：文档写的数 == 清单里的数。
# 清单与实跑的偏离，由 CI 里 `python3 tools/count_suite_totals.py --check`
# 那一步守住（它跑在「全部套件本来就要跑」的那一节里，不额外添成本）。
DOC_ROW = re.compile(r"^\|\s*`tools/(run_[A-Za-z0-9_]+\.(?:sh|py))`\s*\|[^|]*?\*\*(\d+) 条")
declared = {}
for p in doc_files:
    try:
        lines = open(p, encoding="utf-8")
    except OSError:
        continue
    for line in lines:
        m = DOC_ROW.search(line)
        if m:
            declared.setdefault("tools/" + m.group(1), set()).add(int(m.group(2)))

drift = []
checked = 0
for script, vals in sorted(declared.items()):
    want = mb.get(script)
    if want is None:
        continue  # 清单里没有该套件的条数（它不自报总数）—— 不硬凑
    checked += 1
    for v in vals:
        if v != want:
            drift.append(f"{script}:文档写{v}/清单{want}")
print("DOC_COUNT_DRIFT", len(drift), *drift)
print("DOC_COUNT_CHECKED", checked)

# ── 判据⑧：文档里写的「N 个套件」（**总数**口径）必须等于清单里的 suite_total ──
# 判据⑤ 管的是**逐套件**的「N 条」（表格里写成 `| \`tools/run_x.sh\` | **N 条`）。
# 而文档里还有另一种数字：**总数** ——「83 个套件」「42 → 81 个」。
# DOC_ROW 正则匹配不到它们，于是**「N 条」有尺子、「N 个套件」没有**：
# 同一族的漂移在总数这一维上原样复现（帐目① 实测：§5.2 写 83、实际 124）。
#
# 尺子与「N 条」**同一把**：都取自 tools/suite-counts.json —— 逐套件条数来自
# `totals`，总数来自 `suite_total`（由 count_suite_totals.py --write 一起生成，
# 由 `--check-total` 守住它与磁盘一致）。判据这里只做**静态比对**，不扫盘。
#
# 只认**标了「当前」的口径**：`**现 124 个**`。历史叙述（「42 → 81 个」
# 「落盘当刻 81」）**不核** —— 它们标的是过去某一刻的真值，随代码演进本来
# 就该保持不变；把历史数也拿去对当前总数，等于要求文档不能记历史。
TOTAL_ROW = re.compile(r"\*\*现\s*(\d+)\s*个\*\*")
wanted_total = manifest.get("suite_total") if isinstance(manifest, dict) else None
total_declared = set()
for p in doc_files:
    try:
        txt = open(p, encoding="utf-8").read()
    except OSError:
        continue
    total_declared |= {int(x) for x in TOTAL_ROW.findall(txt)}
total_drift = []
if wanted_total is None:
    total_drift.append("清单里没有 suite_total（跑 --write 生成）")
else:
    for v in sorted(total_declared):
        if v != wanted_total:
            total_drift.append(f"文档写{v}/清单{wanted_total}")
print("SUITE_TOTAL_DRIFT", len(total_drift), *total_drift)
print("SUITE_TOTAL_CHECKED", len(total_declared))
print("SUITE_TOTAL_WANT", wanted_total if wanted_total is not None else -1)
print("DISK_SUITES", len(on_disk))
PY

# 输出的行是 `<名字> <条数> <脚本1> <脚本2> ...`（`<名字> <条数>` 恒在，
# 后面只有条数 > 0 时才有内容）。取名字之后、**跳过条数**剩下的部分。
field() { awk -v k="$1" '$1==k { for (i=3; i<=NF; i++) printf "%s%s", (i>3?" ":""), $i; print "" }' "$TMP/report.txt"; }
UNWIRED=$(field UNWIRED)
PHANTOM=$(field PHANTOM)
DOC_UNWIRED=$(field DOC_UNWIRED)
REQ0_IGNORED=$(field REQ0_IGNORED)
CALLED_SH=$(field CALLED_SH)
STAGE_FILES=$(field CI_SOURCES)
DOC_COUNT_DRIFT=$(field DOC_COUNT_DRIFT)
DOC_COUNT_CHECKED=$(awk '$1=="DOC_COUNT_CHECKED" {print $2}' "$TMP/report.txt")
SUITE_TOTAL_DRIFT=$(field SUITE_TOTAL_DRIFT)
SUITE_TOTAL_CHECKED=$(awk '$1=="SUITE_TOTAL_CHECKED" {print $2}' "$TMP/report.txt")
SUITE_TOTAL_WANT=$(awk '$1=="SUITE_TOTAL_WANT" {print $2}' "$TMP/report.txt")
DISK_SUITES=$(awk '$1=="DISK_SUITES" {print $2}' "$TMP/report.txt")

# ── 1) 磁盘上的每个离线测试都已经接进 CI ───────────────────────────────────
# 失败时点出**是哪几个**（不只是一句"有没接的"）：只有指出名字，这个守卫
# 在被改坏时才真的有人去接，而不是当成噪音把 `exit 1` 改成 `|| true`。
c "磁盘上每个 tools/run_* 都已在 .cnb.yml 里被调用（未接：${UNWIRED:-无}）" \
  "[ -z \"\$UNWIRED\" ]"

# ── 2) 文档不指向不存在的脚本 ──────────────────────────────────────────────
c "README/HTP-STATUS 引用的脚本都真实存在（悬空：${PHANTOM:-无}）" \
  "[ -z \"\$PHANTOM\" ]"

# ── 3) 被文档当证据的脚本必须真的在跑 ─────────────────────────────────────
# 这一条与第 1 条**不重复**：第 1 条管"所有脚本都要接"，这一条专门管
# **"被写进文档当证据的那批"**。两者的差别正是本次两处问题的位置 ——
# 文档里出现的名字是"有承诺的"，而承诺没兑现比"有个脚本没接"严重得多。
c "被 README/HTP-STATUS 当证据的脚本都在 CI 里跑（宣称却没跑：${DOC_UNWIRED:-无}）" \
  "[ -z \"\$DOC_UNWIRED\" ]"

# ── 4) 防"守卫自己恒真"的另一半 ────────────────────────────────────────────
# 上面三条都是 `[ -z ... ]` 形式的否定式断言 —— 如果 python 段整个失效
# （比如 glob 没匹配到、变量名写错），三个集合全为空，三条会**全绿**。
# 所以必须另钉"集合本身是算出来的、且非空"。
c "磁盘上确实扫到了离线测试脚本（防上面几条恒真）" \
  "[ \"\$(ls tools/run_*.sh tools/run_*.py 2>/dev/null | wc -l)\" -gt 20 ]"
# 「CI 源非空」是**其余所有判据**的前提，也是这次真出过事的那一格：
# 扫描面从 `.cnb.yml` 挪到 `scripts/ci-*.sh` 时，若某一处没跟着改，
# `called` 会变空 —— 判据①③ 报"一个都没接"（红得莫名其妙），
# 判据④⑥⑦⑨ 的集合为空则**恒绿**（静默）。所以这条要按**两个来源分别断言**：
#   · `.cnb.yml` 里至少还留着调用（不是把 script 搬走就完事）；
#   · 被引用的 stage 脚本真的存在且被读到了。
# 两个来源**分开判**，且判的是"这一来源里真的有调用"，不是"文件里有字"：
#   · `.cnb.yml` —— 搬走正文之后它应当**恰好**剩一句 `sh scripts/ci-*.sh`
#     （所以这里不查它有没有 `tools/run_*`；那样写会把这次正确的改动判红）；
#   · stage 脚本 —— 被 `.cnb.yml` 引用、且在磁盘上真的存在，且里面真的有调用。
c "CI 调用清单的两个来源都非空（.cnb.yml 引用 stage 脚本 + scripts/ci-*.sh 有调用）" \
  "grep -qE '^[[:space:]]+(sh|bash) scripts/ci-[A-Za-z0-9_-]+\\.sh[[:space:]]*\$' \$CI && [ -n \"\$STAGE_FILES\" ] && grep -qhE '(sh|bash|python3) tools/run_[A-Za-z0-9_]+\\.(sh|py)' \$STAGE_FILES"
c "stage 脚本本体存在且被读取（${STAGE_FILES:-无}）" \
  "[ -n \"\$STAGE_FILES\" ] && [ -s \"\$(printf '%s\\n' \$STAGE_FILES | head -1)\" ]"
c "本次修复的两个脚本都在 CI 调用清单里（防有人把这两行删回去）" \
  "grep -qh 'REQUIRED=0 sh tools/run_cors_live_tests.sh' \$CI \$STAGE_FILES && grep -qh 'sh tools/run_chat_buffer_tests.sh' \$CI \$STAGE_FILES"

# ── 4b) 每个判据都必须能**真的被执行到**：脚本本体先过 `sh -n` ────────────────
# 上面几条判据（以及仓库里全部离线守卫）成立的前提，是**脚本本体能跑起来**。
# 本体语法错 -> 它自己的全部断言一行都不执行，而 CI 那一行照旧"跑了" ——
# 与判据①③（"接了没跑"）同族，只是断在更前面一环：
# 脚本**被执行了，但一条断言都没跑到**。
#
# 用**与判据① 同一个集合**（CI 真的会调用的那批），只查 `.sh`：
# "光有 tools/ 这个目录"不是"脚本本体"的证据，CI 里被 `sh`/`bash` 调起
# 来的那批才是"自证清白"的对象。纯静态，不需要任何工具链。
# 桩⑥ / ⑥b 实测"该红的红、还原后全绿"。
SH_SYNTAX_OK=yes
SH_SYNTAX_BAD=""
for f in $(printf '%s\n' $CALLED_SH); do
    [ -f "$f" ] || continue
    if ! sh -n "$f" 2>/dev/null; then SH_SYNTAX_OK=no; SH_SYNTAX_BAD="$SH_SYNTAX_BAD $f"; fi
done
c "脚本本体语法自证：CI 调用的每个 .sh 都过 sh -n（不过：${SH_SYNTAX_BAD:-无}）" \
  "[ \"\$SH_SYNTAX_OK\" = \"yes\" ]"
# 防上面那条恒真：集合为空（正则写歪 / 变量名写错）时，上面会一条都不查并恒绿。
c "确实扫到了待做语法自证的 .sh（防上一条恒真）" \
  "[ \"\$(printf '%s\n' \$CALLED_SH | grep -c '^tools/run_.*\.sh\$')\" -ge 5 ]"

# ── 4c) 同一份脚本换 shell 必须给出**同一份输出**（`sh` 与 `bash` 逐行相同）─────
# 判据⑥ 只问"能不能解析"（`sh -n`），**问不出"换一把解析器还成不成立"**。
# 本仓库真实撞到过一处：`run_schema_sampler_guard.sh` 里
#     VAR=$(cmd1 && cmd2 <<HEREDOC && cmd3 || cmd4)
# —— POSIX 没规定命令替换里「heredoc 重定向 + 紧随的 `&&`」怎么收尾：
# **dash 能解析，bash 报 `syntax error near unexpected token &&`**；
# 而 `sh -n` / `bash -n` **两份都过**（不是静态语法错），判据⑥ 因此静默放过。
# 后果（实测于 0.9.101 原样）：
#     sh   tools/run_schema_sampler_guard.sh -> PASS 121 / FAIL 0   rc=0
#     bash tools/run_schema_sampler_guard.sh -> PASS 117 / FAIL 0   rc=2
# 末尾四条判据**一条都没打印**，而它报的 `PASS 117` 长得完全不像失败。
# 这是"判据被某一份 shell 执行了一部分，且没有任何一行说这件事" ——
# 与判据①③/⑥ 同族，断在"用哪把尺子"这一环。
#
# 判据做法：对 CI 真的会调用、且**不依赖任何工具链**的那批静态守卫，
# 分别用 `sh` 与 `bash` 跑一遍，要求
#   · 两者的**全部 stdout 逐行相同**（`diff` 为空）；
#   · 退出码相同。
# 只挑"无工具链依赖"的那批（静态守卫）：它们在任何环境都该跑到底，
# 于是"两份输出不同"只可能是**解析器分叉**，不可能是"缺某个工具"。
# 桩⑦ / ⑦b 实测"该红的红、还原后全绿"。
SHELL_DIFFERS=""
SHELL_SKIPPED=""
for f in $(printf '%s\n' $CALLED_SH); do
    [ -f "$f" ] || continue
    # 只对"纯静态守卫"做这件事：这四份不依赖 kotlinc / g++ / python3 之外的东西
    # （CI 里也是无条件 `sh` 调起，没有 REQUIRED=0 前缀）。
    case "$f" in
        # ⚠ 本守卫**不在**名单里：它自己会再跑一遍名单——自指 = 无限递归。
        #   它自己的壳无关性由自测的桩⑦b 反过来钉（改坏它，桩会红）。
        tools/run_schema_sampler_guard.sh|tools/run_cors_guard.sh|\
        tools/run_auth_guard.sh|tools/run_kv_cache_guard.sh|\
        tools/run_think_routing_guard.sh|tools/run_web_chat_guard.sh|\
        tools/run_llama_jni_accept_guard.sh|\
        tools/run_llama_jni_abort_guard.sh) : ;;
        *) SHELL_SKIPPED="$SHELL_SKIPPED $f"; continue ;;
    esac
    SOUT=$(mktemp); BOUT=$(mktemp)
    # ⚠ 这两行必须 `|| true` 兜住：本守卫开着 `set -e`，而"脚本本身返回非 0"
    #   正是这里要**当成读数**的事（分叉时 bash 就是 rc=2）—— 不兜住的话
    #   守卫会死在第一次非 0 返回上，连自己那条判据都打不出来。
    sh   "$f" > "$SOUT" 2>&1 || true; SRC=$?
    bash "$f" > "$BOUT" 2>&1 || true; BRC=$?
    if [ "$SRC" != "$BRC" ] || ! diff -q "$SOUT" "$BOUT" >/dev/null 2>&1; then
        SHELL_DIFFERS="$SHELL_DIFFERS $f(sh=$SRC/bash=$BRC)"
    fi
    rm -f "$SOUT" "$BOUT"
done
c "CI 的静态守卫在 sh / bash 下输出逐行相同（分叉：${SHELL_DIFFERS:-无}）" \
  "[ -z \"\$SHELL_DIFFERS\" ]"
# 防上面那条恒真：白名单写歪 / 变量名写错 -> 一条都不比 -> 恒绿。
# 要求"真的比过至少 6 份"（本仓库白名单里现有 8 份，留一点余量）。
c "确实比对过至少 5 份静态守卫的 sh/bash 输出（防上一条恒真）" \
  "[ \"\$(printf '%s\n' \$CALLED_SH | grep -cE '^tools/run_(schema_sampler|cors|auth|kv_cache|think_routing|web_chat|llama_jni_accept)_guard\.sh\$')\" -ge 5 ]"

# ── 4d) 文档里写的「N 条」必须等于该套件的真实条数（判据⑤）─────────────────
# 判据①③ 比的是「脚本名字在不在 CI 里」；这一条比的是「它跑了**几条**、
# 与文档写的对不对得上」。当年那起事故（文档写「16 条全通过」、脚本根本编不过）
# 的核心证据正是**数字** —— 它落在①③ 的判据面之外，所以三条件全绿而问题仍在。
# 2026-09-23 实测出 9 处同族漂移（HTP-STATUS §5.2），最大一处 77 -> 132。
# 条数取 `tools/suite-counts.json`（由 count_suite_totals.py --write 生成），
# 本判据**只做静态比对**，不跑套件 —— 跑套件是 CI 里另一步的事。
c "文档写的「N 条」都等于清单条数（漂移：${DOC_COUNT_DRIFT:-无}）" \
  "[ -z \"\$DOC_COUNT_DRIFT\" ]"
# 防判据⑤ 恒真：清单缺失 / 文档正则没匹配上 -> drift 恒空 -> 恒绿。
c "确实核对过至少 30 份脚本的文档条数（防判据⑤ 恒真）" \
  "[ \"\${DOC_COUNT_CHECKED:-0}\" -ge 30 ]"

# ── 4d2) 文档里写的「N 个套件」（**总数**）必须等于清单里的 suite_total（判据⑧）──
# 判据⑤ 管**逐套件**的「N 条」；这一条管**总数**。两者是同一族的漂移，只是
# 落在不同的写法上：`| \`tools/run_x.sh\` | **N 条` 能被 DOC_ROW 抓到，
# 而「83 个套件」「42 → 81 个」抓不到 —— 于是**「N 条」有尺子、「N 个套件」没有**，
# 帐目① 实测 §5.2 写着 83、磁盘实际 124，判据⑤ 全绿。
#
# 尺子与「N 条」**同一把**：都取 tools/suite-counts.json（总数 = `suite_total`，
# 由 count_suite_totals.py --write 一起生成、由 `--check-total` 守住与磁盘一致）。
# 只核**标了「当前」的口径**（`**现 N 个**`）：历史叙述（「42 → 81 个」…
# 「落盘当刻 81」）不核 —— 它标的是过去某一刻的真值，本就该保持不变。
c "文档写的「现 N 个套件」都等于清单总数（漂移：${SUITE_TOTAL_DRIFT:-无}）" \
  "[ -z \"\$SUITE_TOTAL_DRIFT\" ]"
# 防判据⑧ 恒真：文档里一个「现 N 个」都没写 / 清单缺 suite_total -> 空集恒绿。
c "确实核对了文档里的套件总数、且清单有 suite_total（防判据⑧ 恒真，核到 ${SUITE_TOTAL_CHECKED:-0} 处）" \
  "[ \"\${SUITE_TOTAL_CHECKED:-0}\" -ge 1 ] && [ \"\${SUITE_TOTAL_WANT:-0}\" -ge 20 ]"
# 防判据⑧ 与判据⑤ 用两把尺子：清单的 suite_total 必须等于 CI/磁盘上真实的套件数。
c "清单 suite_total 等于磁盘上的套件个数（总数口径只此一把尺子，实测 ${DISK_SUITES:-0}）" \
  "[ \"\${SUITE_TOTAL_WANT:-0}\" = \"\${DISK_SUITES:-0}\" ]"

# ── 4e) 审查收口小结必须真的在"被扫描"的那一份名单里（判据⑦）────────────
# `docs/REVIEW-110-SUMMARY.md` 是 Issue #110 的**唯一索引**：它引用各套件的
# 名字与条数作为证据。若它不在判据①③⑤ 的扫描面里，就会重演 J-1 ——
# 一份**看着像证据、实则无人核对**的文档（"手抄的数字 + 没有机制跟代码走"）。
# 判据不是"文件存在"（那是存在性断言，本仓库已反复踩过）：要求它的**内容
# 真的进了集合** —— 数的是 python 段用**与 docs 集合同一把正则**在小结里
# 命中到的脚本数。少一个都说明"它掉出了扫描面"。
SUMMARY_SCRIPTS=$(awk '$1=="SUMMARY_SCRIPTS" {print $2}' "$TMP/report.txt")
c "审查收口小结里的套件名真的进了扫描集合（命中 ${SUMMARY_SCRIPTS:-0} 个，须 > 0）" \
  "[ \"\${SUMMARY_SCRIPTS:-0}\" -gt 0 ]"

# ── 5) `REQUIRED=0` 必须是**真契约**，不能只是写在那行上 ────────────────────
# 这一条防的又是"证据链断了"的同一形态，只是断在流水线而不是文档里：
# `.cnb.yml` 写着 `REQUIRED=0`（读作"缺工具链就 SKIP"），而脚本根本不读它 ——
# 于是缺工具链时硬 exit 2，把整轮构建拦下。写的人以为已经允许退化、
# 看的人（出了事来查的人）也会以为"这也退化了"，而实际没有。
# 判据只查"CI 真的以 REQUIRED=0 调用的那批"，不要求所有脚本都支持它。
c "CI 以 REQUIRED=0 调用的脚本都真的读 REQUIRED（写着却没读：${REQ0_IGNORED:-无}）" \
  "[ -z \"\$REQ0_IGNORED\" ]"
# 防上面那条恒真：maven 那批确实是以 REQUIRED=0 调的，数量>0 才说明正则没写歪。
c "确实扫到了 REQUIRED=0 的调用（防上一条恒真）" \
  "[ \$(grep -chE 'REQUIRED=0[[:space:]]+(sh|bash)[[:space:]]+tools/run_[A-Za-z0-9_]+\.sh' \$CI \$STAGE_FILES 2>/dev/null | awk '{s+=\$1} END{print s+0}') -ge 5 ]"

echo ""
if [ "$bad" = "0" ]; then echo "=== CI 接线守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== CI 接线守卫：PASS $ok / FAIL $bad ==="; exit 1
