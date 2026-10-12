#!/bin/sh
# 自测 `tools/run_apk_debug_entry_guard.sh`：把它对着打桩输入跑一遍，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有
# ═══════════════════════════════════════════════════════════════════════════
# 这份守卫的判据全是**静态文本关系**（两段 script 是否逐字相同、调的是不是
# 那个脚本、镜像是否一致）。这类判据有两种典型的"自己失效"方式，而且
# **在 CI 里跑一遍看它绿是发现不了的**（它本来就绿）：
#
#   · **剥注释漏了** —— 说明这件事的注释里必然出现事件名、脚本名与 `script:`。
#     漏剥之后，"把入口从 `.cnb.yml` 里删掉、注释还留着"的输入会让
#     判据①全绿。本仓库在鉴权守卫桩② 与到期清扫守卫判据④ 上各栽过一次同款。
#   · **切段切歪了** —— 事件段落切到下一个顶层键还是下一个事件键，决定了
#     判据③读到的正文是哪一段。切歪的后果不是"报错"，而是**读到别的段还判它相等**。
#
# 所以下面每一根桩都**只改被测对象的一处**，并同时断言"守卫变红"
# 与"点名的那条判据确实是 FAIL"；另配对偶桩断言"改回去就恢复绿"
# —— 没有对偶的话，一根写着 `must_red` 的桩在守卫恒红时也会通过。
#
# ⚠ 打桩走**副本目录**：守卫支持 `CNB_ROOT`（工作目录即仓库根），
#   所以整个自测可以在 `$TMP` 里跑，**不碰真源码**，也就不需要工作区独占锁。
#   （本仓库多数自测是"就地打桩 + trap 还原"，那是它们被并行跑时的风险源；
#    这一份没有那个问题，因为没有一处真源码被打开写。）
#
# 运行：sh tools/run_apk_debug_entry_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_apk_debug_entry_guard.sh

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM HUP

# 副本地形：守卫在仓库根下只碰这三样（`.cnb.yml` / `scripts/ci-apk-stage.sh`
# / `tools/apk_debug_entry/parse_entries.py`），照抄成最小可跑的形状。
seed() {
    rm -rf "$TMP/root"; mkdir -p "$TMP/root/scripts" "$TMP/root/tools/apk_debug_entry"
    cp .cnb.yml "$TMP/root/.cnb.yml"
    cp scripts/ci-apk-stage.sh "$TMP/root/scripts/ci-apk-stage.sh"
    cp tools/apk_debug_entry/parse_entries.py "$TMP/root/tools/apk_debug_entry/"
}

run_guard() {
    set +e
    out=$(cd "$TMP/root" && CNB_ROOT="$TMP/root" sh "$OLDPWD/$GUARD" 2>&1)
    rc=$?
    set -e
    printf '%s\n' "$out" > "$TMP/guard.out"
    return $rc
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -F "$key" "$TMP/guard.out" | grep -q 'FAIL'; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」（该判据没起作用）" 0
    fi
}

must_green() {
    if run_guard; then ck "$1：守卫全绿" 1; else ck "$1：守卫全绿（输出见下）" 0; cat "$TMP/guard.out"; fi
}

# ── 0) 基线：真源码上必须全绿 ──────────────────────────────────────────────
seed
must_green "原始源码"

# ── 1) 桩①：入口整段从 `.cnb.yml` 删掉、注释原样留着 ───────────────────────
# 这是"剥注释漏了"的正面靶心：注释里那把 `api_trigger_apk_debug` 的字样
# 必须**不足以**让判据①通过。
seed
"$PY" - "$TMP/root/.cnb.yml" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
# 把键行降成**缩进**注释（于是名字还在文件里，但它已经不是配置了）。
# ⚠ 不能写成 `"  " + "# 曾有过 ..."`：YAML 的规矩里 `#` 只要顶格就是
#   **文档分隔符**，不是注释 —— 那样会插入一份新文档，而"注释里还留着名字"
#   这个要点一根都没打到（本文件第一版正是这么写的，桩① 是**假绿**过的）。
old = "  api_trigger_apk_debug:\n"
assert s.count(old) == 1, '桩①：没找到入口那行'
s = s.replace(old, "    # 曾有过 api_trigger_apk_debug:\n", 1)
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩①（入口被注释掉、注释里名字还在）" "入口里有 apk_debug"
seed

# ── 2) 桩②：两个事件的 script 正文漂了（新入口多一个参数）──────────────────
# 靶心是判据③：两段**逐字**比。多一个 `--fast` 就编出别的东西，
# 而"出现过 ci-apk-stage.sh"这类存在性判据对它恒真。
seed
"$PY" - "$TMP/root/.cnb.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
# 只动第二个（新入口）那一个 —— 两个都改就成"一起漂"，判据③会（正确地）判相等。
i = s.index("api_trigger_apk_debug:")
j = s.index("sh scripts/ci-apk-stage.sh", i)
s = s[:j] + "sh scripts/ci-apk-stage.sh --fast" + s[j+len("sh scripts/ci-apk-stage.sh"):]
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩②（新入口的命令漂成 --fast）" "命令与既有 stage 逐字相同"
seed

# ── 3) 桩③：新入口改调另一个脚本（两段都"相等"，但都不是既有 stage）───────
# 这一根专打判据③ 的单点失效：判据③ 判的是"两段相等"，**相等**并不等于
# "调的是既有那份"。若没有判据④，把两条一起改名叫 `scripts/other.sh`
# 就能全绿通过 —— 而质量门禁那 240 份套件谁也不跑了。
seed
"$PY" - "$TMP/root/.cnb.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
assert s.count("sh scripts/ci-apk-stage.sh") == 2
s = s.replace("sh scripts/ci-apk-stage.sh", "sh scripts/other-stage.sh")
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩③（两个事件一起改调 other-stage.sh，判据③ 仍判相等）" "调的是 scripts/ci-apk-stage.sh"
seed

# ── 4) 桩④：原有入口被顶掉（改个名，让新名字占住 abicheck2 的位置）─────────
# 这是"用力方向反着"的一根：判据① 会因为新名字仍在而全绿，
# 只有判据② 能发现**唯一的质量门禁没了**。
seed
"$PY" - "$TMP/root/.cnb.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
assert s.count("api_trigger_abicheck2:") == 1
s = s.replace("api_trigger_abicheck2:", "api_trigger_old_gate:", 1)
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩④（原有 abicheck2 被改名顶掉）" "原有的 api_trigger_abicheck2 仍在"

# 对偶：名改回去必须恢复绿（证明判据② 抓的是"那个名字在不在"，
# 不是"文件里出现过 api_trigger_"这种存在性）。
seed
must_green "桩④b（还原后恢复绿）"

# ── 5) 桩⑤：新入口换成没工具链的镜像 ──────────────────────────────────────
# 形态：静态判据全绿（入口在、名字对、命令一样），第一次真跑才发现镜像里
# 没有 NDK —— 而那要等一次构建。这一条能在静态阶段就把它拦下。
seed
"$PY" - "$TMP/root/.cnb.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
i = s.index("api_trigger_apk_debug:")
j = s.index("mingc/android-build-box:latest", i)
s = s[:j] + "ubuntu:latest" + s[j+len("mingc/android-build-box:latest"):]
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩⑤（新入口的镜像换成 ubuntu:latest）" "镜像非空且两边一致"
seed

# ── 6) 桩⑥：新增入口的同时，把出包那段从既有 stage 里拆走 ──────────────────
# 这是本守卫里唯一把**两个事件一起看**的那一条：新入口照旧绿，
# 而 240 份套件与产物一起没了。
seed
"$PY" - "$TMP/root/scripts/ci-apk-stage.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = "echo \"=== 编 APK ===\"\n"
assert s.count(old) == 1, '桩⑥：没找到出包标记行'
s = s.replace(old, "echo \"=== 已搬到新入口 ===\"\n", 1)
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩⑥（出包那段从既有 stage 里被拆走）" "既有 stage 里仍是整条守卫链 + 出包"
seed

# 对偶：出包行还在但 `gradle` 被删（只判"出现过 gradle"会漏的那种）────────
seed
"$PY" - "$TMP/root/scripts/ci-apk-stage.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
# ⚠ 锚**那一整行**（`ln.strip()` 全等）而不是片段：文件正文说明注释里也写着
#   `gradle :app:assembleDebug`（"流水线最后一步 … 才是…"），拿片段去 count
#   会撞上注释、`count == 1` 当场失败，整份自测从这一根中止（本文件第一版
#   就是这样 —— 后面几根桩一条都没跑到，而"没跑到"长得像"通过"）。
n = 0
out = []
for ln in s.split("\n"):
    if ln.strip() == "gradle :app:assembleDebug --no-daemon":
        out.append("echo '(略过编译)'")
        n += 1
    else:
        out.append(ln)
assert n == 1, "桩⑥b：assembleDebug 那一行不唯一（找到 %d 处）" % n
open(p, "w", encoding="utf-8").write("\n".join(out))
PY
must_red "桩⑥b（既有 stage 里 assembleDebug 被拿掉）" "既有 stage 里仍是整条守卫链 + 出包"
seed

# ── 7) 桩⑦：切段的尺子自己坏了 —— 指向一个不存在的配置文件 ────────────────
# 守卫是靠"按事件名切段"判定的。若切法写歪（正则永远不匹配），
# `ENTRY` 会变空 —— 判据① 报"一个事件都没有"（红得莫名其妙），
# 而判据②③④ 会因为输入为空而**静默变成另一回事**。
# 这一根改的是**被读的文件**（换成一份空配置），断言判据① 仍然红
# 且点名的是"有 api_trigger 事件"那一条 —— 也就是"切歪"与"真没有"分得开。
seed
printf '# 空配置\n' > "$TMP/root/.cnb.yml"
must_red "桩⑦（配置文件里一个事件都没有）" "有 api_trigger 事件"
seed

# ── 8) 桩⑧：`.cnb.yml` 整个不在（守卫必须红，不许静默全绿）─────────────────
seed
rm "$TMP/root/.cnb.yml"
run_guard || true
if grep -F ".cnb.yml 存在" "$TMP/guard.out" | grep -q 'FAIL'; then
    ck "桩⑧（配置文件缺失时点得到「.cnb.yml 存在」）" 1
else
    ck "桩⑧（配置文件缺失时点得到「.cnb.yml 存在」）" 0
fi
seed

# ── 9) 桩⑨：多一个**尚未接门**的同族入口（守卫必须红）───────────────────
# 形态：有人照着 `api_trigger_apk_debug` 又抄了一份 `api_trigger_apk_debug_x`
# （镜像、命令、事件前缀全对），只是**忘了给它接门**。守卫的判据面是
# "`api_trigger_*` 的名单" —— 于是它会当场点出这个名字。
# 这正是"加一条入口只需照抄一次"的代价面：不钉的话，第二份抄件会静默地
# 只受判据①②（名字在、不和旧的撞），③④⑤（命令/脚本/镜像）一条都不管它。
seed
"$PY" - "$TMP/root/.cnb.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = "  api_trigger_apk_debug:\n"
assert s.count(old) == 1
dup = "  api_trigger_apk_debug_x:\n    - docker:\n        image: mingc/android-build-box:latest\n      stages:\n        - name: 抄错的\n          script: |\n            sh scripts/ci-apk-stage.sh\n\n"
s = s.replace(old, dup + old, 1)
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩⑨（多一个没接门的同族入口）" "有未接门的 api_trigger 事件"
seed

# ── 10) 桩⑩：`parse_entries.py` 的剥注释被改坏（守卫必须立刻红，不许静默）───
# 这是"判据自己依赖的那把尺子"的桩：把 `strip_comments` 改成**不剥**，
# 桩① 那种输入（入口被注释掉、注释里名字还在）就会反过来全绿。
# 也就是说 —— 判据① 的有效性建立在"剥注释真的剥掉"之上，这里钉住那个前提。
seed
"$PY" - "$TMP/root/tools/apk_debug_entry/parse_entries.py" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = '    stripped = re.sub(r"(?m)(^|\\s)#.*$", r"\\1", text)'
assert old in s, '桩⑩：没找到剥注释那一行'
s = s.replace(old, '    stripped = text   # 桩⑩：不再剥注释', 1)
open(p, "w", encoding="utf-8").write(s)
PY
"$PY" - "$TMP/root/.cnb.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
s = s.replace("  api_trigger_apk_debug:\n", "    # api_trigger_apk_debug:\n", 1)
open(p, "w", encoding="utf-8").write(s)
PY
if run_guard; then
    ck "桩⑩（剥注释被改坏后，'入口被注释掉'的输入必须仍然红）" 0
else
    ck "桩⑩（剥注释被改坏后，'入口被注释掉'的输入必须仍然红）" 1
fi
seed

# ── 11) 还原后必须恢复全绿 ────────────────────────────────────────────────
seed
must_green "还原后"

echo ""
if [ "$bad" = "0" ]; then echo "=== 按需出包入口守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 按需出包入口守卫自测：PASS $ok / FAIL $bad ==="; exit 1
