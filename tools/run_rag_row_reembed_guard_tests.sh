#!/bin/sh
# 自测 `tools/rag_row_reembed/check_wiring.py`：把它对着几份**打桩源码**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是**存在性、结构性**断言（这个函数体内有没有那一跳 / 谁在谁之前 /
# 取值走哪个函数），两个自欺模式这一族最容易踩：
#
#   1. **锚错位置**：钉"文件里出现过 replaceRowsWithVectors"而不是"写盘那一跳
#      走它" —— 前者在别处（退回那一条）也成立，于是判据恒绿。
#   2. **恒假后被静音**：pattern 与源码差一个字符 -> 永远 FAIL，
#      很快就会有人把 `exit 1` 改成 `|| true`。
#
# 桩全部来自"真正会写错的写法"（这一族的失败方向，而它们**全都不抛异常**）：
#   · 指纹的模型那一半**现算当前配置**（用户改过批大小 -> 刚写完就判需重建）
#   · 指纹**不换 `x`**（索引与正文仍不同源 —— 免重建没发生）
#   · 编码失败**不退回**只写正文（用户的改动一个字节都没落盘）
#   · 退回时**不走** replaceRowsInPlace（走 saveTexts = 把库砍成一页）
#   · **不先确认**索引可用就编码（一份索引两种语义，不报错）
#   · 「可用」自己另写一个近似判据（不与检索同源）
#   · 半份索引也放行（后面那些行本来就没有向量）
#   · 正常路径**不走** replaceRowsWithVectors（只写正文 = 免重建没发生）
#   · 先写正文后写索引（失败方向比反过来费解）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_row_reembed_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"

PY=${PYTHON:-python3}
GUARD=tools/rag_row_reembed/check_wiring.py
SRC=app/src/main/java/com/xiaowan/localinference
ENG=$SRC/LlmEngine.kt
STORE=$SRC/RagIndexStore.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ENG" "$TMP/eng.bak"; cp "$STORE" "$TMP/store.bak"
restore() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/store.bak" "$STORE"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/store.bak" "$STORE"; }

run_guard() { set +e; "$PY" "$GUARD" "$ENG" "$STORE" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

stub_py() { "$PY" - "$@" || echo "⚠ 打桩脚本失败（锚点漂了？）—— 该桩将被跳过" >&2; }

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
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：指纹的模型那一半**现算当前配置** ─────────────────────────────
# 症状：用户改过批大小 / 换过模型时，刚写完的索引立刻被判"需重建"，
#       而这次修法本身就是为了免重建 —— 指向一个他没动过的字段。
stub_py "$STORE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val fpAfter = fingerprintOf(FpInput(fpIn), textsHashOf(after.toByteArray(Charsets.UTF_8)), rows)"
new = "        val fpAfter = fingerprintOf(FpInput(fpIn), textsHashOf(after.toByteArray(Charsets.UTF_8)), rows)\n        val _unused = embedFpInput"
assert s.count(old) == 1, "桩①：没找到指纹那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩①（指纹现算当前配置）" "不许在这里现算 embedFpInput"
reset_src

# ── 2. 桩②：指纹**不换 `x`**（索引与正文仍不同源）──────────────────────
stub_py "$STORE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val fpAfter = fingerprintOf(FpInput(fpIn), textsHashOf(after.toByteArray(Charsets.UTF_8)), rows)"
new = "        val fpAfter = fingerprintOf(FpInput(fpIn), fpIn, rows)"
assert s.count(old) == 1, "桩②：没找到指纹那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩②（指纹不换 x）" "指纹里只换 x"
reset_src

# ── 3. 桩③：编码失败**不退回**只写正文（改动一个字节都没落盘）────────────
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            return if (textsWritten == RagIndexStore.EditResult.OK)
                RewriteResult.TextsOnly("第 $rows 行没能重算向量（$why）")
            else RewriteResult.Failed("第 $rows 行重算失败，且正文也没写进磁盘")"""
new = """            return RewriteResult.Failed("第 $rows 行没能重算向量（$why）")"""
assert s.count(old) == 1, "桩③：没找到编码失败那一支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩③（编码失败不退回只写正文）" "编码失败那一支 -> 退回 TextsOnly"
reset_src

# ── 4. 桩④：退回那一条**走 saveTexts**（整份覆盖 = 把库砍成一页）────────
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            val textsWritten = RagIndexStore.replaceRowsInPlace(ctx, name, indices, texts)\n            uiLog(\"[知识库] $name 有 ${failed.size}"
new = "            val textsWritten = LlmEngine.saveTextsLike(ctx, name, texts)\n            uiLog(\"[知识库] $name 有 ${failed.size}"
assert s.count(old) == 1, "桩④：没找到退回那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩④（退回走 saveTexts 而不是按行回写）" "退回那一条走 replaceRowsInPlace"
reset_src

# ── 5. 桩⑤：**不先确认**索引可用就编码（一份索引两种语义）──────────────
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (!indexUsableForRewrite(ctx, name)) {"
new = "        if (false) {"
assert s.count(old) == 1, "桩⑤：没找到可用性闸"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤（不先确认索引可用就编码）" "编码**之前**先确认索引与当前模型同源"
reset_src

# ── 6. 桩⑥：「可用」自己另写一个近似判据（不与检索同源）─────────────────
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val fpIn = embedFpInput(cp, EmbedBatchPref.get(ctx)) ?: return false\n        val textsRaw = RagIndexStore.readTexts(ctx, name) ?: return false"
new = "        val textsRaw = RagIndexStore.readTexts(ctx, name) ?: return false"
assert s.count(old) == 1, "桩⑥：没找到同源取值那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑥（可用判据不与检索同源）" "「可用」的判据与检索**同源**"
reset_src

# ── 7. 桩⑦：半份索引也放行（后面那些行本来就没有向量）──────────────────
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        return RagIndexStore.load(ctx, name, fp) is RagIndexStore.Load.valid"
new = "        return RagIndexStore.load(ctx, name, fp) !is RagIndexStore.Load.none"
assert s.count(old) == 1, "桩⑦：没找到 load 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑦（半份索引也放行）" "只认 Load.valid"
reset_src

# ── 8. 桩⑧：正常路径**不走** replaceRowsWithVectors（只写正文）──────────
# 症状：免重建没发生 —— 改字之后索引仍被判"需重建"，检索停用。
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        return when (RagIndexStore.replaceRowsWithVectors(ctx, name, indices, texts, vecs)) {"
new = "        return when (RagIndexStore.replaceRowsInPlace(ctx, name, indices, texts)) {"
assert s.count(old) == 1, "桩⑧：没找到正常路径那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑧（正常路径不走 replaceRowsWithVectors）" "正常路径走 replaceRowsWithVectors"
reset_src

# ── 9. 桩⑨：存储层**先写正文、后写索引**（失败方向更费解）──────────────
stub_py "$STORE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (!writeIndexAtomic(d, plan.indexBytes)) return EditResult.FAILED
        if (!writeTexts(d, plan.textsBytes)) return EditResult.STALE"""
new = """        if (!writeTexts(d, plan.textsBytes)) return EditResult.STALE
        if (!writeIndexAtomic(d, plan.indexBytes)) return EditResult.FAILED"""
assert s.count(old) == 1, "桩⑨：没找到写盘那两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑨（先写正文后写索引）" "先写索引、后写正文"
reset_src

# ── 10. 桩⑩：存储层**不校验**索引齐整/同源（半份或旧索引也照写）────────
stub_py "$STORE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (count != rowsToCount(rows)) return null
        if (!textsInSync(indexRaw, before)) return null"""
new = "        if (false) return null"
assert s.count(old) == 1, "桩⑩：没找到那两条前置判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑩（不校验索引齐整/同源）" "前置判据：索引齐整"
reset_src

# ── 11. 桩⑪：指纹的 `x` 也不换、`r` 也不对（整份指纹照搬）──────────────
stub_py "$STORE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val fpAfter = fingerprintOf(FpInput(fpIn), textsHashOf(after.toByteArray(Charsets.UTF_8)), rows)"
new = "        val fpAfter = fingerprintOf(FpInput(fpIn), textsHashOf(before), rows)"
assert s.count(old) == 1, "桩⑪：没找到指纹那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑪（指纹用的是旧正文的 hash）" "指纹里只换 x"
reset_src

# ── 12. 桩⑫：引擎**不逐条编码**（拿整库去 buildIndex）──────────────────
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            val v = embed(t)"
new = "            val v = if (true) null else embed(t)"
assert s.count(old) == 1, "桩⑫：没找到编码那一行"
s = s.replace(old, new, 1)
# 顺手把"不调 buildIndex"这条也踩了（桩要能同时点出这一族）
old2 = "        return when (RagIndexStore.replaceRowsWithVectors("
s = s.replace(old2, "        buildIndex(ctx, name, emptyList())\n        return when (RagIndexStore.replaceRowsWithVectors(", 1)
open(p, 'w', encoding='utf-8').write(s)
PYSTUB
must_red "桩⑫（不逐条编码 / 调了 buildIndex）" "这一跳**不调** buildIndex"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 改字重算向量守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 改字重算向量守卫自测：PASS $ok / FAIL $bad ==="; exit 1
