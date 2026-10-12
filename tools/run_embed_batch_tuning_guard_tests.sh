#!/bin/sh
# 自测 `tools/run_embed_batch_tuning_guard.sh`：拿几份**打桩源码树**跑它，
# 断言"该红的红、且失败时点得出是哪一条"（ISSUE #269 第 1+2 步）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是**结构关系**（谁在什么位置读了/调了谁），而在**正确**的
# 源码上它们全绿 —— 一个永远绿的判据与没有判据在效果上一样。
#
# 桩全部取自"真正会写错的写法"（本 issue 这一族真踩过的形态）：
#   · 建库循环里不调 `EmbedBatchStats.record`（读数永远空态）；
#   · 只记合批、不记回落（"整轮一条都没合批"这个关键读数消失）；
#   · `/health` 报了字段但 `HealthCheck.parse` 不解析（报告里读不到）；
#   · 设置页能存、`buildIndex` 不读它（与"线程数输入框对建库无效"一模一样）；
#   · 批大小不进指纹（改了不重建）；
#   · 建库与检索只一处传批大小（另一处算成默认值 -> 指纹永远不匹配）。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_embed_batch_tuning_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_embed_batch_tuning_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ENG=$SRC/LlmEngine.kt
ACT=$SRC/EngineActivity.kt
HTTP=$SRC/HttpApi.kt
HEALTH=$SRC/HealthCheck.kt
STORE=$SRC/RagIndexStore.kt
PREF=$SRC/EmbedBatchPref.kt
HDR=app/src/main/cpp/rag_index.h

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ENG" "$TMP/eng.bak"; cp "$ACT" "$TMP/act.bak"; cp "$HTTP" "$TMP/http.bak"
cp "$HEALTH" "$TMP/health.bak"; cp "$STORE" "$TMP/store.bak"; cp "$HDR" "$TMP/hdr.bak"
cp "$PREF" "$TMP/pref.bak"; cp "$SRC/EmbedBatchStats.kt" "$TMP/stats.bak"
restore() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/http.bak" "$HTTP";
            cp "$TMP/health.bak" "$HEALTH"; cp "$TMP/store.bak" "$STORE";
            cp "$TMP/pref.bak" "$PREF"; cp "$TMP/stats.bak" "$SRC/EmbedBatchStats.kt";
            cp "$TMP/hdr.bak" "$HDR"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/http.bak" "$HTTP";
              cp "$TMP/health.bak" "$HEALTH"; cp "$TMP/store.bak" "$STORE";
              cp "$TMP/pref.bak" "$PREF"; cp "$TMP/stats.bak" "$SRC/EmbedBatchStats.kt";
              cp "$TMP/hdr.bak" "$HDR"; }

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

# ── 桩①：建库循环里不记读数（`EmbedBatchStats.record` 没人调）──────────────
# 这正是本 issue 要消除的"合批怎么样全靠猜"：纯逻辑算得再对，
# 没人喂它就是空态。
"$PY" - "$ENG" <<'PYEOF1'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
n = s.count("EmbedBatchStats.record(")
assert n == 2, f"stub1: anchor miss (found {n})"
s = s.replace("""                    EmbedBatchStats.record(EmbedBatchStats.Sample(
                        EmbedBatchStats.Route.BATCHED, end - i,
                        (System.nanoTime() - batchT0) / 1_000_000L))
""", "")
s = s.replace("""                    EmbedBatchStats.record(EmbedBatchStats.Sample(
                        EmbedBatchStats.Route.FELL_BACK, end - i,
                        (System.nanoTime() - batchT0) / 1_000_000L))
""", "")
assert s.count("EmbedBatchStats.record(") == 0, "stub1: replace failed"
open(p, 'w', encoding='utf-8').write(s)
PYEOF1
must_red "桩①（建库不记读数）" "没有 EmbedBatchStats.record"
reset_src

# ── 桩②：只记合批、不记回落 ─────────────────────────────────────────────────
# "整轮一条都没合批"这个**关键读数**会消失（用户看不出合批完全没生效）。
"$PY" - "$ENG" <<'PYEOF2'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                    EmbedBatchStats.record(EmbedBatchStats.Sample(
                        EmbedBatchStats.Route.FELL_BACK, end - i,
                        (System.nanoTime() - batchT0) / 1_000_000L))
"""
assert s.count(old) == 1, "stub2: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYEOF2
must_red "桩②（只记合批不记回落）" "没记 Route.FELL_BACK"
reset_src

# ── 桩③：/health 报了字段但 HealthCheck 不解析 ──────────────────────────────
# 字段在响应里、探活报告里没有 —— 与没报等价（本仓库反复记录的一族）。
"$PY" - "$HEALTH" <<'PYEOF3'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'embedBatchBatches = long("embed_batch_batches"),'
assert s.count(old) == 1, "stub3: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "// (删掉了)", 1))
PYEOF3
must_red "桩③（报了字段不解析）" "HealthCheck.parse 没解析 embed_batch_batches"
reset_src

# ── 桩④：设置页能存，但建库不读它 ──────────────────────────────────────────
# 这就是用户最初报的形态：设置页那个框"能填、能存、就是对建库没用"。
"$PY" - "$ACT" <<'PYEOF4'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "LlmEngine.buildIndex(this, name, docs, embedBatchSize = embedBatchForBuild)"
assert s.count(old) == 1, "stub4: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "LlmEngine.buildIndex(this, name, docs)", 1))
PYEOF4
must_red "桩④（设置页存了但建库不读）" "没传 embedBatchSize"
reset_src

# ── 桩⑤：批大小不进指纹（Kotlin 侧那一格）──────────────────────────────────
# 改了批大小、旧索引仍被当成"可用" —— 新旧向量语义不同时是静默错位。
"$PY" - "$STORE" <<'PYEOF5'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'sb.append("g").append(fp.embedBatch).append(\'\\u001f\')'
assert s.count(old) == 1, "stub5: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "// (删掉了)", 1))
PYEOF5
must_red "桩⑤（指纹没写 g 字段）" "fingerprintOf 没有写 \`g\` 字段"
reset_src

# ── 桩⑥：`FpInput(fp: String)` 不搬 `g` ────────────────────────────────────
# 追加/删除一条时按默认批大小重算指纹 -> 用户点一次「追加」就把索引弄失效。
"$PY" - "$STORE" <<'PYEOF6'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'embedBatch = f("g")?.toIntOrNull() ?: 0'
assert s.count(old) == 1, "stub6: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "embedBatch = 0", 1))
PYEOF6
must_red "桩⑥（FpInput(fp) 不搬 g）" "没有搬 \`g\` 字段"
reset_src

# ── 桩⑦：C++ 侧指纹不写 `g`（两边字段集合分岔）────────────────────────────
"$PY" - "$HDR" <<'PYEOF7'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''    s += "g";      s += std::to_string(fp.embedBatch);   s += '\\x1f';'''
assert s.count(old) == 1, "stub7: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "    // (删掉了)", 1))
PYEOF7
must_red "桩⑦（C++ 指纹没写 g）" "fingerprint_of 没有写 \`g\` 字段"
reset_src

# ── 桩⑧：建库与检索**只一处**传批大小 ──────────────────────────────────────
# 漏传的那处把指纹算成"批大小=0" -> 它永远不匹配真实索引（每次都在重建，
# 只是慢，所以没人会去查）。
"$PY" - "$ENG" <<'PYEOF8'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val fpIn = embedFpInput(cp, EmbedBatchPref.get(ctx))"
assert s.count(old) == 1, "stub8: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "        val fpIn = embedFpInput(cp, 0)", 1))
PYEOF8
must_red "桩⑧（检索侧不传批大小）" "没有 EmbedBatchPref.get"
reset_src

# ── 桩⑨：normalize 自己再钳一次（不转调唯一判据）───────────────────────────
# 上限与 native 的 n_seq_max 脱钩 —— 用户填了合法值也每批被拒。
"$PY" - "$PREF" <<'PYEOF9'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "fun normalize(value: Int): Int = LlmEngine.kEmbedBatchLimit(value)"
assert s.count(old) == 1, "stub9: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "fun normalize(value: Int): Int = value.coerceIn(1, 8)", 1))
PYEOF9
must_red "桩⑨（normalize 自己再钳一次）" "没有转调 LlmEngine.kEmbedBatchLimit"
reset_src

# ── 桩⑨b：批大小在**后台线程里**读（View 不线程安全）──────────────────────
# `saveRagBatchSize` 读的是 `ragBatchEt.text`（一个 View 的属性）。
# 在 `Thread { ... }` 里读它是未定义行为（最软是读到旧值，最硬是闪退）。
"$PY" - "$ACT" <<'PYEOF9B'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚点跟着源码搬过家（本轮在 `Thread {` 里插了"续编提示"那一段）：
#   上一版锚的是 `val embedBatchForBuild = …` 紧接 `Thread {`，中间多了那一段
#   之后就打不上了。桩的意图不变（把批大小的**读取**挪进后台线程），
#   所以锚"主线程那一读"与"Thread 开头"两处，中间任意代码一并搬走。
old = """        val embedBatchForBuild = saveRagBatchSize()
        Thread {
"""
assert s.count(old) == 1, "stub9b: anchor miss"
s = s.replace(old, """        Thread {
""", 1)
old_mid = """                LlmEngine.buildIndex(this, name, docs, embedBatchSize = embedBatchForBuild)"""
assert s.count(old_mid) == 1, "stub9b: anchor(mid) miss"
old2 = "                LlmEngine.buildIndex(this, name, docs, embedBatchSize = embedBatchForBuild)"
assert s.count(old2) == 1, "stub9b: anchor2 miss"
s = s.replace(old2, "                LlmEngine.buildIndex(this, name, docs, embedBatchSize = saveRagBatchSize())", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF9B
must_red "桩⑨b（批大小在后台线程里读）" "startBuildIndex 没读批大小"
reset_src

# ── 桩⑩：读数层去做决策（引用了 `BatchEmbed` 这类决策符号）──────────
# 把"观测"与"决策"焊在一起时，改一个就是改两个 ——
# 而它们的正确方向相反（观测要忠实，决策要保守）。
"$PY" - "$SRC/EmbedBatchStats.kt" <<'PYEOF10'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    fun snapshot(): Snapshot = synchronized(lock) {"
assert s.count(old) == 1, "stub10: anchor miss"
new = "    // 顺手引用了决策层的类型（不该在这里）\n    val probe: BatchEmbed? = null\n    fun snapshot(): Snapshot = synchronized(lock) {"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF10
must_red "桩⑩（读数层做决策）" "读数层不做决策"
reset_src

# ── 桩⑪：`/health` 不报"建索引时用的批大小"（第 5 步那一格）────────────────
# 只有当前配置那一个数时，"我改了批大小、库为什么废了"在读数上没有任何落点：
# 用户拿到的只有一句"索引不可用：合批每批条数 变了"，**机制一个字都不说**。
"$PY" - "$HTTP" <<'PYEOF11'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ',"embed_batch_size_index":$indexBatch,"embed_batch_size_index_kb":$currentKbNameJson'
assert s.count(old) == 1, "stub11: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYEOF11
must_red "桩⑪（/health 不报建索引时用的批大小）" "没有报 embed_batch_size_index"
reset_src

# ── 桩⑫：`/health` 报的那一格不**从索引里读**（拿当前配置顶上）─────────────
# 这是最阴的一种"接上了"：字段在、解析在，但值是"当前配置"的复制品 ——
# 两个数恒相等 = 对账恒绿 = 这一格白加，而它恰恰是靠"两个数可能不等"来工作的。
"$PY" - "$HTTP" <<'PYEOF12'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'RagIndexStore.batchOfIndex(ragPrefs(), currentKbName)'
assert s.count(old) == 1, "stub12: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "wantBatch", 1))
PYEOF12
must_red "桩⑫（那一格不从索引读，拿当前配置顶上）" "没有调 batchOfIndex"
reset_src

# ── 桩⑬：`g` 差异被并回语义那一轮（丢失"能救多少"这一级）──────────────────
# 把 `g` 与 v/p/b/m/d/t/o/k/c/x/r 混成一条判据时，一份"语义完好、只差旋钮"
# 的索引被说得像换了模型 —— 用户看不到那 N 块是可救的。
"$PY" - "$STORE" <<'PYEOF13'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'for ((k, name) in kSemanticsFpFields) {'
assert s.count(old) == 1, "stub13: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, 'for ((k, name) in kRequiredFpFields) {', 1))
PYEOF13
must_red "桩⑬（g 差异并回语义那一轮）" "值比较没有用 kSemanticsFpFields"
reset_src

# ── 桩⑭：「还原默认」不清这两格（写个默认值糊过去）─────────────────────────
# `isSet()` 的判据是"键在不在"。写了默认值 = 键仍在 = 读数报"用户设过"。
# 而批大小进指纹（`g`）：他设过 1 之后还原 -> 界面显示 8、**跑的还是 1**。
"$PY" - "$ACT" <<'PYEOF14'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        EmbedBatchPref.clear(this)
        EmbedCtxPref.clear(this)
"""
assert s.count(old) == 1, "stub14: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYEOF14
must_red "桩⑭（还原默认不清批大小那格）" "没有调 EmbedBatchPref.clear"
reset_src

# ── 桩⑮：收尾那一行**报累计**（没有开工基线）────────────────────────────
# 这是本仓库真出过的措辞错：累计值顶着"本轮"两个字，用户拿它去比
# "批4 vs 批1"时读到两个几乎相等的数（相邻两次只差 4 条），
# 会读成"批大小没生效"——而真因只是那行报的是进程级总账。
"$PY" - "$ENG" <<'PYEOF15'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val statsBefore = EmbedBatchStats.snapshot()\n"
assert s.count(old) == 1, "stub15: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYEOF15
must_red "桩⑮（收尾那行报累计：没有开工基线）" "没有**开工前**取一份 EmbedBatchStats.snapshot()"
reset_src

# ── 桩⑯：那一行不减、直接拿累计去 describe ───────────────────────────────
# 基线留了、也传下去了，但收尾仍拿 `snapshot()` 直读报出去 ——
# 字段、形参全在，数字仍是累计（"看着完全正常"的那种错）。
"$PY" - "$ENG" <<'PYEOF16'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val s = EmbedBatchStats.delta(before, EmbedBatchStats.snapshot())"
assert s.count(old) == 1, "stub16: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "        val s = EmbedBatchStats.snapshot()", 1))
PYEOF16
must_red "桩⑯（不减，直接拿累计报）" "没有 EmbedBatchStats.delta"
reset_src

# ── 桩⑰：那一行的口径标签写回「本轮」（含糊词，方向可反）─────────────────
# 数字对、标签含糊：既像"本次"又像"累计"，于是同一句话能被读成两个相反的结论。
"$PY" - "$ENG" <<'PYEOF17'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "本段批大小=$batch，只算这一次建库"
assert s.count(old) == 1, "stub17: anchor miss"
s = s.replace(old, "本轮批大小=$batch", 1)
old2 = "EmbedBatchStats.describe(s, EmbedBatchStats.kScopeThisBuild)"
assert s.count(old2) == 1, "stub17: anchor2 miss"
s = s.replace(old2, "EmbedBatchStats.describe(s, EmbedBatchStats.kScopeCumulative)", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF17
must_red "桩⑰（口径标签写回「本轮」）" "还有「本轮」"
reset_src

# ── 桩⑱：日志那行的批大小与写进指纹 `g` 的**不是同一个源** ────────────────
# 两处各取一遍（一个取钳前的 `embedBatchSize`、一个取钳后的 `batch`）——
# 真机上真出现过"日志写 8、磁盘上那份索引的 `g` 是 4"这种分岔：用户拿日志
# 去核对"这次真正跑的是几"时读到的是另一个数，于是"8 批到底建过没有"
# 在本机日志里无法证伪。
"$PY" - "$ENG" <<'PYEOF18'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val fp = embedFpInput(cp, batch)"
assert s.count(old) == 1, "stub18: anchor miss"
# 指纹拿**没钳过的**用户意愿，日志拿钳过的实际值 —— 两处分岔。
s = s.replace(old, "        val fp = embedFpInput(cp, embedBatchSize)", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF18
must_red "桩⑱（日志批大小与指纹 g 不同源）" "不是同一个源"
reset_src

echo
echo "=== 嵌入合批读数与可调守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
