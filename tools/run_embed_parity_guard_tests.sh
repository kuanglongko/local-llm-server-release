#!/bin/sh
# 自测 `tools/run_embed_parity_guard.sh`：拿几份**打桩源码树**跑它，
# 断言"该红的红、且失败时点得出是哪一条"（ISSUE #269 第 5 步）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是**结构关系**（谁在什么位置读了/调了谁），而在**正确**的
# 源码上它们全绿 —— 一个永远绿的判据与没有判据在效果上一样。
#
# 桩全部取自"真正会写错的写法"（这一族真踩过的形态）：
#   · 入口只跑合批那一侧（比的是"合批自己和自己"，结论恒为"相同"）；
#   · 入口跑了两侧但不落 /health（结论只能翻日志）；
#   · /health 报了但 HealthCheck.parse 不解析（报告里读不到）；
#   · 结论报成一个布尔（"没测过"与"不是逐位"混成一个值）；
#   · 换嵌入模型不清结论（看到的是上一个模型的）；
#   · allIdentical 用 `different == 0` 判（"有条目缺失"也算成立）；
#   · 界面没有入口（真机量不出来）；样本运行时从库里取（跨版本对不上）；
#   · 逐条明细算了不往日志里打（结论只说得出"有几条不同"，说不出"是哪一条"
#     —— 而"1/6"与"6/6"的下一步相反）；
#   · /health 只报"逐位相同几条"、不报"真的不同几条"（同上，两个读数长得一样）；
#   · 入口只跑跨路、不跑"同路径重复"（「批布局改语义」与「编码本身不确定」
#     混在一个读数里 —— 首轮真机那条 firstDiffDim=0 正是分不开的两件事）；
#   · 只有一组长短悬殊样本（「批布局」与「不等长的 padding/切分」分不开）；
#   · /health 把"同路径重复测没测"写成常量（把"没测"读成"全绿"）；
#   · 入口只重复**逐条**那条路（「合批自身不确定」与「批布局改语义」混在一起）；
#   · 样本条数与批大小不对齐（跨版本比结论时多出"图不一样"这个变量）；
#   · 界面那行说明的条数手抄（常量改条数时就地漂，而那行字正是让人对读数的）；
#   · 落 /health 的是**等长**那组（构造上必然稳）—— 报出来的是"必然成立"那句
#     （真机 PR #301 的现场）；
#   · 报读数时**不带来源组名** —— 同一句"逐位相同 4/4"在两组下同形，
#     读者把等长组的结论当成长短悬殊组也成立。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_embed_parity_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_embed_parity_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ENG=$SRC/LlmEngine.kt
ACT=$SRC/EngineActivity.kt
HTTP=$SRC/HttpApi.kt
HEALTH=$SRC/HealthCheck.kt
PARITY=$SRC/EmbedParity.kt
PROBE=$SRC/EmbedParityProbe.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ENG" "$TMP/eng.bak"; cp "$ACT" "$TMP/act.bak"; cp "$HTTP" "$TMP/http.bak"
cp "$HEALTH" "$TMP/health.bak"; cp "$PARITY" "$TMP/parity.bak"; cp "$PROBE" "$TMP/probe.bak"
restore() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/http.bak" "$HTTP";
            cp "$TMP/health.bak" "$HEALTH"; cp "$TMP/parity.bak" "$PARITY"; cp "$TMP/probe.bak" "$PROBE";
            rm -rf "$TMP"; }
reset_src() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/http.bak" "$HTTP";
              cp "$TMP/health.bak" "$HEALTH"; cp "$TMP/parity.bak" "$PARITY"; cp "$TMP/probe.bak" "$PROBE"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 桩①：入口只跑合批那一侧（比的是"合批自己和自己"）──────────────────────
"$PY" - "$ENG" <<'PYEOF1'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把逐条那一侧的两行删掉（保留合批那一侧）——正是一个"只跑一侧"的写法。
old = """        val singles = ArrayList<FloatArray?>(texts.size)
        val repeats = ArrayList<FloatArray?>(texts.size)
        for (t in texts) {
            singles.add(embed(t))
            repeats.add(embed(t))
        }
"""
assert old in s, "锚点失效（LlmEngine 里逐条那一侧的那段被改过）"
# 把逐条那一侧整个删掉（只留合批那侧 + 跨路比对）——正是一个"只跑一侧"的写法。
s = s.replace(old, "        val singles = ArrayList<FloatArray?>(texts.size)\n", 1)
s = s.replace("aRepeat = repeats, ", "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF1
must_red "桩①（入口只跑合批那一侧）" "没有跑**逐条**那一侧"
reset_src

# ── 桩②：结论不落 /health（只打日志）──────────────────────────────────────
"$PY" - "$ENG" <<'PYEOF2'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (label.isEmpty() || label == LONG_SHORT_LABEL) EmbedParity.Last.record(report, label)\n"
assert old in s, "锚点失效（LlmEngine 里 Last.record 那行被改过）"
s = s.replace(old, "")
open(p, 'w', encoding='utf-8').write(s)
PYEOF2
must_red "桩②（结论不落 /health，只打日志）" "没有把结论落进 EmbedParity.Last"
reset_src

# ── 桩③：/health 报了字段但 HealthCheck.parse 不解析 ──────────────────────
"$PY" - "$HEALTH" <<'PYEOF3'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            embedParityRows = int("embed_parity_rows"),\n'
assert old in s, "锚点失效（HealthCheck 里 embedParityRows 那行被改过）"
s = s.replace(old, "")
old2 = '            embedParityVerdict = str("embed_parity_verdict"),\n'
assert old2 in s, "锚点失效（HealthCheck 里 embedParityVerdict 那行被改过）"
s = s.replace(old2, "")
open(p, 'w', encoding='utf-8').write(s)
PYEOF3
must_red "桩③（/health 报了但 parse 不解析）" "HealthCheck.parse 没解析 embed_parity_rows"
reset_src

# ── 桩④：结论报成布尔（不是分档）─────────────────────────────────────────
"$PY" - "$PARITY" <<'PYEOF4'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        enum class Verdict { UNKNOWN, IDENTICAL, CLOSE, DIFFERENT, MISSING }\n"
assert old in s, "锚点失效（EmbedParity 里 Last.Verdict 那行被改过）"
# 换成一个布尔真相源（正是"压进另一档"的写法）。
s = s.replace(old, "        var allSame: Boolean = false\n")
open(p, 'w', encoding='utf-8').write(s)
PYEOF4
must_red "桩④（结论报成布尔，没测过与不是逐位混成一个值）" "Last 的结论不是分档枚举"
reset_src

# ── 桩⑤：换嵌入模型不清结论 ───────────────────────────────────────────────
"$PY" - "$ENG" <<'PYEOF5'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        EmbedParity.Last.reset()\n"
assert old in s, "锚点失效（LlmEngine 里 Last.reset 那行被改过）"
s = s.replace(old, "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF5
must_red "桩⑤（换嵌入模型不清结论）" "没有 EmbedParity.Last.reset"
reset_src

# ── 桩⑥：allIdentical 用 `different == 0` 判 ──────────────────────────────
"$PY" - "$PARITY" <<'PYEOF6'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "val allIdentical: Boolean get() = rows.isNotEmpty() && identical == rows.size"
assert old in s, "锚点失效（EmbedParity 里 allIdentical 那行被改过）"
s = s.replace(old, "val allIdentical: Boolean get() = different == 0")
open(p, 'w', encoding='utf-8').write(s)
PYEOF6
must_red "桩⑥（allIdentical 用「没有不同」判，缺失也算成立）" "allIdentical 的判据不是"
reset_src

# ── 桩⑥b：allIdentical 少了「非空」那半个条件 ─────────────────────────────
# 与桩⑥是**同族的第二面**：桩⑥挡「缺失算成立」，这一桩挡「空批算成立」
# （`0 == 0` —— 一条都没编过就说整批逐位相同）。两个面必须各有一条桩：
# 只钉一个时，另一个方向的坏法会**静默绿**（本仓库记过的那族）。
"$PY" - "$PARITY" <<'PYEOF6b'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "val allIdentical: Boolean get() = rows.isNotEmpty() && identical == rows.size"
assert old in s, "锚点失效（EmbedParity 里 allIdentical 那行被改过）"
s = s.replace(old, "val allIdentical: Boolean get() = identical == rows.size")
open(p, 'w', encoding='utf-8').write(s)
PYEOF6b
must_red "桩⑥b（allIdentical 少了「非空」—— 空批报成整批逐位相同）" "allIdentical 少了"
reset_src

# ── 桩⑦：界面没有入口 ─────────────────────────────────────────────────────
"$PY" - "$ACT" <<'PYEOF7'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚**结构**（两处调用 + 它们之间的跨度），不锚行首空白：
#    把这一趟搬进 `ioExecutor.execute` 时整体缩进多了一级，按字面空白写的锚点
#    当场失效（而那与"入口被删掉"长得一模一样 —— 自测会从"该红却绿"变成
#    "锚点失效"报错，仍然叫，但不是它该叫的那件事）。
m = re.search(
    r'val\s+longShort\s*=\s*LlmEngine\.probeEmbedBatchParity\s*\(.*?'
    r'val\s+sameLen\s*=\s*LlmEngine\.probeEmbedBatchParity\s*\(.*?\)\s*\n',
    s, re.S)
assert m, "锚点失效（EngineActivity 里入口那两处调用被改过）"
old = m.group(0)
indent = re.match(r'[ \t]*', old).group(0)
repl = (indent + "val longShort: EmbedParity.Report? = null\n"
        + indent + "val sameLen: EmbedParity.Report? = null\n")
# 两处入口都拿掉 —— 正是一个"界面上没有这个按钮"的写法。
s = s.replace(old, repl, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF7
must_red "桩⑦（界面没有入口，真机量不出来）" "没有调 probeEmbedBatchParity"
reset_src

# ── 桩⑧：样本集运行时从库里取（不是写死常量）──────────────────────────────
"$PY" - "$PROBE" <<'PYEOF8'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    val TEXTS: List<String> = listOf(\n"
assert old in s, "锚点失效（EmbedParityProbe 里 TEXTS 那段被改过）"
# 删掉 TEXTS 这个**常量声明**（改成运行时从库里取 —— 那样就不是固定样本集了）。
s = s.replace(old, "    val FROM_KB: List<String> = emptyList() // 运行时从库里取\n")
open(p, 'w', encoding='utf-8').write(s)
PYEOF8
must_red "桩⑧（样本集不是写死的常量）" "固定样本集是常量"
reset_src

# ── 桩⑨：逐条明细算了不往日志里打 ─────────────────────────────────────────
# 正是首轮真机的形态：结论只说得出"6 条里 1 条不同"，说不出是**哪一条** ——
# 而"只有某几条不同"（去查那几条）与"整批都不同"（这条路整体不可信）处置相反。
"$PY" - "$ENG" <<'PYEOF9'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        for (line in report.detailLines()) uiLog(\"[嵌入] $line\")\n"
assert old in s, "锚点失效（LlmEngine 里 detailLines 那行被改过）"
s = s.replace(old, "")
open(p, 'w', encoding='utf-8').write(s)
PYEOF9
must_red "桩⑨（逐条明细算了不打日志）" "没有把逐条明细"
reset_src

# ── 桩⑩：/health 把「真的不同」的条数写成常量 0（不读快照）──────────────
"$PY" - "$HTTP" <<'PYEOF10'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 锚点用**正则**写（不是字面量）：这段会被塞进 `python3 - <<'PYEOF10'`，
# 字符串里的 `${...}` 与替换内容里的 `$` 会被层层展开 —— 正则里不含 `$`，
# 就不受这件事影响（本桩第一版正是被它改成了 `":0${pari.different}` —— 
# 数字里混进展开结果，于是"该红的没红"）。
pat = re.compile(r',"embed_parity_different":\$\{pari\.different\}')
assert pat.search(s), "锚点失效（HttpApi 里 embed_parity_different 那处被改过）"
# 把值换成**常量 0**（而不是删掉整段）：`/health` 仍会回这个字段名，但报的永远是 0
# —— 真机"6 条里 1 条不同"时它会把"有 1 条不同"说成"没有不同"（一个相反的读数）。
s = pat.sub(',"embed_parity_different":0', s, count=1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF10
must_red "桩⑩（/health 把不同的条数写成常量 0）" "没有报 embed_parity_different"
reset_src

# ── 桩⑪：Report 报"有条目不同"、却一条不同都没有（自相矛盾）──────────────
# 这是"快照被写坏"的形态：`verdict=different` 配 `different=0` 会被读成
# "验过了，没有不同的条目"（一个**相反**的结论）。要么是 `Report` 手拼的，
# 要么是那个 `when` 被改岔了 —— 两种情况下都不该把这三个数发出去（落 UNKNOWN 才如实）。
"$PY" - "$PARITY" <<'PYEOF11'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 去掉那段自洽拦截（`if (v == Verdict.DIFFERENT && r.different <= 0) return`）：
# 这正是"快照被写坏也不拦"的写法 —— 之后 `verdict=different` 配 `different=0`
# 就会照常发出去，被读成"验过了，没有不同的条目"。
old = "                if (v == Verdict.DIFFERENT && r.different <= 0) { group = \"\"; return }\n"
assert old in s, "锚点失效（EmbedParity.record 里自洽拦截那段被改过）"
s = s.replace(old, "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF11
must_red "桩⑪（结论说不同、条数却是 0）" "没有拦「结论说不相同"
reset_src

# ── 桩⑫：入口只跑跨路、不跑「同路径重复」对照 ──────────────────────────────
# 正是首轮真机的形态：那条 firstDiffDim=0 说不出是「批布局改语义」还是
# 「编码本身不确定」—— 而只有"同一条路编两次"这组读数能把两者分开。
"$PY" - "$ENG" <<'PYEOF12'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val singles = ArrayList<FloatArray?>(texts.size)
        val repeats = ArrayList<FloatArray?>(texts.size)
        for (t in texts) {
            singles.add(embed(t))
            repeats.add(embed(t))
        }
"""
assert old in s, "锚点失效（LlmEngine 里同路径重复那段被改过）"
# 只编一次（不跑重复对照）——正是"分不开两个来源"的写法。
s = s.replace(old, """        val singles = ArrayList<FloatArray?>(texts.size)
        for (t in texts) singles.add(embed(t))
""", 1)
s = s.replace("aRepeat = repeats, ", "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF12
must_red "桩⑫（入口不跑同路径重复对照）" "没有做「同路径重复」对照"
reset_src

# ── 桩⑬：只有一组长短悬殊样本（没有等长对照集）────────────────────────────
# 正是首轮真机的另一个形态：分不清"那 1 条不同"是批布局造成的，
# 还是不等长合批的 padding/切分造成的 —— 下一组实验（等长）无从下手。
"$PY" - "$PROBE" <<'PYEOF13'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    val SAME_LEN: List<String> = listOf("
assert old in s, "锚点失效（EmbedParityProbe 里 SAME_LEN 那段被改过）"
# 删掉等长对照集（只剩长短悬殊那一组）——名字换成不含 SAME_LEN 的，
# 否则判据 `val\s+SAME_LEN` 仍会命中（本桩第一版正是这么假绿的）。
s = s.replace(old, "    val EQUAL_LEN_OFF: List<String> = listOf(", 1)
s = s.replace("EmbedParityProbe.SAME_LEN", "EmbedParityProbe.TEXTS")
open(p, 'w', encoding='utf-8').write(s)
PYEOF13
must_red "桩⑬（没有等长对照样本集）" "取不到 SAME_LEN 的条目"
reset_src

# ── 桩⑭：/health 把「同路径重复测没测」写成常量（不读快照）────────────────
# 把 true 写成常量 true：没跑过重复对照时也报"测过了"—— 于是探活里
# `notIdentical == 0` 会被读成"同路径重复全绿"（把"没测过"说成"验过了、没问题"，
# 与 `different` 用 null/0 分开同一条理由）。
"$PY" - "$HTTP" <<'PYEOF14'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
pat = re.compile(r',"embed_parity_repeat_measured":\$\{pari\.repeatMeasured\}')
assert pat.search(s), "锚点失效（HttpApi 里 embed_parity_repeat_measured 那处被改过）"
s = pat.sub(',"embed_parity_repeat_measured":true', s, count=1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF14
must_red "桩⑭（同路径重复测没测写成常量）" "没有报 embed_parity_repeat_measured"
reset_src

# ── 桩⑮：入口只重复**逐条**那条路（不重复合批）────────────────────────────
# 只重复逐条时，读数里还剩一格没被排掉：「**合批自己**复现不了自己」。它与
# "批布局改语义"是两件事（前者先查合批，后者才谈 `g` 收不收窄），处置相反 ——
# 合成一个"同路径重复"时，两个来源被焊在一起。
"$PY" - "$ENG" <<'PYEOF15'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        // ⚠ **合批那条路也要重复一遍**（同一批文本、再走一次 `embedBatch`）。"""
assert old in s, "锚点失效（LlmEngine 里合批重复那段被改过）"
# 把合批重复整段删掉（只留逐条那条路的重复）——正是"只重复一条路"的写法。
i = s.index(old)
j = s.index("        val report = EmbedParity.compare(", i)
s = s[:i] + s[j:]
s = s.replace(", aBatchedRepeat = batchedRepeat", "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF15
must_red "桩⑮（入口不重复合批那条路）" "没有做「**合批**那条路的同路径重复」对照"
reset_src

# ── 桩⑯：样本条数与批大小不对齐（TEXTS 又变成 6 条）──────────────────────
# 条数不对齐时，两组样本跨版本比结论会多出「图不一样」这一个变量 ——
# 而本对照的结论正是**跨版本**要对的那一条（"0.9.218 那句待验断言成立没有"）。
"$PY" - "$PROBE" <<'PYEOF16'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        "向量检索是把文本编码成向量，再按余弦相似度找最接近的几条。","""
assert old in s, "锚点失效（EmbedParityProbe 里 TEXTS 第②条被改过）"
# 加两条"同类的第二条"（这正是被删掉的那种重复取法）：4 -> 6，与 8 不对齐。
s = s.replace(old, old + """
        "这是一条用来把条数顶到不对齐位置的样本甲。",
        "这是一条用来把条数顶到不对齐位置的样本乙。",""", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF16
must_red "桩⑯（样本条数与批大小不对齐）" "与批大小 8 不对齐"
reset_src

# ── 桩⑰：界面那行说明里的条数**手抄**（不是从常量取）──────────────────────
# 这正是 0.9.222 的真身：两组各 6 条改成各 4 条，而说明里还写着"等长 5 条"。
# 那行字是让人**按对的样本数**解释结论的手段 —— 写错时两个方向都会被误导
# （少数一条：以为真跑了 5 条；多数一条：以为覆盖比实际更广）。
"$PY" - "$ACT" <<'PYEOF17'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把手抄的条数**按对的常量值**写回去（4 / 4）—— 正是"看着对、却不与常量同源"
# 的写法：常量以后再改条数时，它仍然会漂，而守卫现在就该红。
# ⚠ 锚点跟过两次源码：
#   ① 这句说明原本是「（长短悬殊 ${...size} 条 / 等长 ${...size} 条，各占两批）」
#      跨两行的写法，为塞进面板的体积上限（`tools/repack_lp/check_hint_length.py`
#      的 HINT_MAX）收成了一行的「（悬殊 X / 等长 Y 条）」；0.9.231 又按
#      「常驻探针」重定位措辞，但仍保持这一行的形状。
#   ② 桩要钉的是"条数**从常量取**"这件事，所以锚点跟着说明的形状走 ——
#      换的是措辞，不是判据。按整段原文锚会在文案改一轮后**静默失配**
#      （打桩脚本抛异常、这桩被跳过，而"跳过"看起来跟"绿"一样）。
pat = re.compile(
    r'（悬殊 \$\{EmbedParityProbe\.TEXTS\.size\} / '
    r'等长 \$\{EmbedParityProbe\.SAME_LEN\.size\} 条）')
assert pat.search(s), "锚点失效（EngineActivity 里样本说明那行被改过）"
s = pat.sub('（悬殊 4 条 / 等长 4 条）', s, count=1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF17
must_red "桩⑰（界面说明的条数手抄，不与常量同源）" "条数不是从常量取"
reset_src

# ── 桩⑱：落 /health 的是**等长**那组（构造上必然稳）────────────────────────
# 这正是本版修掉的那一处：等长组按构造必然稳，落它时 `/health` 报的是
# 「整批逐位相同，批大小是纯加速旋钮」，而它答不了"长短悬殊那批换批大小
# 会不会改答案" —— 那才是「`g` 能不能放行」的依据（真机 PR #301 现场）。
"$PY" - "$ENG" <<'PYEOF18'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (label.isEmpty() || label == LONG_SHORT_LABEL) EmbedParity.Last.record(report, label)\n"
assert old in s, "锚点失效（LlmEngine 里 Last.record 那行被改过）"
# 改回"落等长那组"（正是这次被改掉的写法）。
s = s.replace(old, "        if (label.isEmpty() || label == SAME_LEN_LABEL) EmbedParity.Last.record(report, label)\n", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF18
must_red "桩⑱（落 /health 的是等长那组）" "没按长短悬殊那组分闸"
reset_src

# ── 桩⑲：落盘/上报**不带来源组名**（两组读数同形）──────────────────────────
# 只报数字不报来源时，"逐位相同 4/4"在等长组与长短悬殊组下长得一模一样 ——
# 于是等长组那句结论会被读成"长短悬殊也稳"。这里把 `record(report, label)`
# 退回成不带组名的 `record(report)`，并把 `/health` 的字段删掉。
"$PY" - "$ENG" "$HTTP" "$PARITY" <<'PYEOF19'
import sys
eng, http, parity = sys.argv[1], sys.argv[2], sys.argv[3]
# ① 落盘不带组名
s = open(eng, encoding='utf-8').read()
old = "        if (label.isEmpty() || label == LONG_SHORT_LABEL) EmbedParity.Last.record(report, label)\n"
assert old in s, "锚点失效（LlmEngine 里 Last.record 那行被改过）"
s = s.replace(old, "        if (label.isEmpty() || label == LONG_SHORT_LABEL) EmbedParity.Last.record(report)\n", 1)
open(eng, 'w', encoding='utf-8').write(s)
# ② /health 删掉那格（"没得报"与"报了但不带组名"都要红）
h = open(http, encoding='utf-8').read()
frag = ',"embed_parity_group":$parityGroupJson'
assert frag in h, "锚点失效（HttpApi 里 embed_parity_group 那格被改过）"
open(http, 'w', encoding='utf-8').write(h.replace(frag, "", 1))
# ③ 快照里删掉 group
q = open(parity, encoding='utf-8').read()
frag2 = "            val group: String,\n"
assert frag2 in q, "锚点失效（EmbedParity.Last.State 里 group 那行被改过）"
open(parity, 'w', encoding='utf-8').write(q.replace(frag2, "", 1))
PYEOF19
must_red "桩⑲（读数不带来源组名）" "没带组名"
reset_src

# ── 桩⑳：按钮又改回旧定位/旧名 ─────────────────────────────────────────────
# 那段断言**已经答完**（真机读数），而按钮留着是因为它是"批大小到底会不会改
# 语义/改答案"在真机上**唯一的取样点** —— 所以它从"临时诊断"改成"常驻探针"，
# 名字也跟着改（旧名"向量逐位比对"只说了它量的一半，且听着像内部实现）。
# 这一桩把名字退回旧名：那时界面上写着"向量逐位比对"，而日志前缀是
# "[批大小检查]" —— 用户按界面上的字去日志里搜，什么也搜不到。
"$PY" - "$ACT" <<'PYEOF20'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val logParityBtn = btn("批大小一致性检查")'
assert old in s, "锚点失效（EngineActivity 里那个按钮的文案被改过）"
s = s.replace(old, 'val logParityBtn = btn("向量逐位比对")', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF20
must_red "桩⑳（按钮又改回旧名/旧定位）" "重新定位"
reset_src

# ── 桩㉑：日志前缀没跟着按钮名走（按界面上的字去日志里搜不到）──────────────
"$PY" - "$ACT" <<'PYEOF21'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert 'uiLog("[批大小检查]' in s, "锚点失效（EngineActivity 里那几行日志前缀被改过）"
s = s.replace('uiLog("[批大小检查]', 'uiLog("[逐位比对]')
open(p, 'w', encoding='utf-8').write(s)
PYEOF21
must_red "桩㉑：日志前缀没跟着按钮名走" "日志前缀没跟着新按钮名走"
reset_src

echo
echo "=== 嵌入逐位比对守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
