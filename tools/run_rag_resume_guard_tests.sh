#!/bin/sh
# 自测 `tools/run_rag_resume_guard.sh`：拿几份**打桩源码树**跑它，
# 断言"该红的红、且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是结构关系（谁在谁之前 / 谁只认哪种状态 / 落盘在什么条件下发生），
# 在**正确**的源码上它们全绿 —— 一个永远绿的判据与没有判据效果一样。
#
# 桩全部取自"真正会写错的写法"：
#   ① load 又把"count != rows-1"一律判 none（本轮要修掉的那条旧口径）—— 可续被掐死；
#   ② load 把半份判成 valid（能检索）—— 缺的行永远搜不到，而它看着像索引好的；
#   ③ buildIndex 把 loadPrefix 排在 embedBatch 之后（装饰）—— 与从头跑逐字相同；
#   ④ 编码循环从 0 起（读了前缀却不用）—— 续编白做；
#   ⑤ 定期落盘不分失败与否（有失败也落，向量往前挤）—— 检索分数对、注入内容错；
#   ⑥ 续不上时静默从头（不读 lastDiff）—— 用户以为续跑，实际又跑两小时；
#   ⑦ RagResumeUi 把"可续"判在"正文已改"之后（写反）—— 可续永显示成"你改过正文"；
#   ⑧ 进度落盘又拿"已编过的那些块"当整份正文（0.9.216 那个 bug）—— 未编到的
#      那些字**从 texts.jsonl 里消失**，而索引与剩下的正文自洽 -> 下次判成
#      "库建成功了"，条目数是截断后的那个（用户报的"导 1000 条只剩 216 条"）；
#   ⑨ 进度落盘连全量正文都不给（自己拼了个只到第 i 块的表）—— 同一条 bug 的
#      另一种写法，防"只修了参数名、没修数据源"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 打桩失败 = **一条 FAIL**，不是"静默跳过"
# ═══════════════════════════════════════════════════════════════════════════
# 桩靠**锚点**找那一块，而源码演进会让锚点漂（`6663b70` 给"作废闸"那段补的
# `onlyBatch` 说明就把桩⑥逐行原文的锚点顶断了）。漂了之后绝不能只是"跳过"：
# 跳过 = 这条判据没跑，输出里一行 FAIL 都没有 —— 读日志的人会以为它绿了，
# 而它其实一次都没验（这正是 `b646e0c` 诊断过的那只虫：`assert` 抛异常不是红，
# 是静默跳过，且 `set -e` 会让后面所有桩一条都不跑、退出码还是 1）。
# 所以 `stub_py` 失败时记一条 FAIL（"打桩失败（锚点漂了？—— 该桩没跑，不是绿）"），
# 并让紧随其后的 `must_red` 不再拿未改源码去断言；脚本**继续**跑后面的桩。
# 这一条元判据由第 10 节正面自测。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_resume_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_resume_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
STORE=$SRC/RagIndexStore.kt
ENGINE=$SRC/LlmEngine.kt
ACT=$SRC/EngineActivity.kt
UI=$SRC/RagResumeUi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$STORE" "$TMP/store.bak"; cp "$ENGINE" "$TMP/eng.bak"
cp "$ACT" "$TMP/act.bak"; cp "$UI" "$TMP/ui.bak"
restore() { cp "$TMP/store.bak" "$STORE"; cp "$TMP/eng.bak" "$ENGINE";
            cp "$TMP/act.bak" "$ACT"; cp "$TMP/ui.bak" "$UI"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/store.bak" "$STORE"; cp "$TMP/eng.bak" "$ENGINE";
              cp "$TMP/act.bak" "$ACT"; cp "$TMP/ui.bak" "$UI"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

# ── 打桩失败 = **一条 FAIL**，不是"静默跳过" ──────────────────────────────
# 桩是"就地改真源码"，而打桩脚本靠锚点找那一块 —— 源码演进会让锚点漂。
# ⚠ 漂了之后**绝不能**只是"跳过该桩"：跳过意味着这条判据**没有跑**，
#   而输出里一行 FAIL 都没有，读日志的人会以为它绿了（这正是 `b646e0c`
#   自己诊断过的那只虫：`assert` 抛异常不是红，是静默跳过，且 `set -e`
#   会让后面所有桩**一条都不跑**、退出码还是 1）。
# 所以这里：
#   · 打桩失败 -> `ck` 记一条 FAIL（名字就说"打桩失败（锚点漂了）"）；
#   · 置 [stub_dead]，让紧随其后的 `must_red` **不再**拿"没打成桩的源码"
#     去断言（那会既报"守卫没变红"又报"没指到关键词"，两条噪声把真正的
#     原因埋掉）—— 这一桩到此为止，脚本**继续**跑后面的桩。
stub_dead=0
stub_py() { # 名字 文件...（脚本从 stdin 读；名字只用于"打桩失败"那条 FAIL）
    _name=$1; shift
    if "$PY" - "$@"; then stub_dead=0
    else
        echo "⚠ 打桩脚本失败（锚点漂了？）：$_name" >&2
        ck "$_name：打桩失败（锚点漂了？—— 该桩没跑，不是绿）" 0
        stub_dead=1
    fi
    return 0
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    [ "$stub_dead" = "1" ] && return 0     # 桩没打成，别再拿未改源码断言
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    line=$(grep -E "^FAIL " "$TMP/guard.out" | grep -F "$key" | head -1)
    if [ -n "$line" ]; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」（没有任何 FAIL 行命中它）" 0; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：load 又把"count != rows-1"一律判 none（旧口径，可续被掐死）─────
stub_py 桩① "$STORE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (count > full) {"
assert s.count(old) == 1, "桩①：没找到越界判据那一行"
new = ("        if (rowsInFp != null && rowsToCount(rowsInFp) != count) {\n"
       "            lastDiff = FpDiff(false, true, \"r\", \"旧口径\")\n"
       "            return Load.none\n"
       "        }\n"
       "        if (count > full) {")
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩①（load 又用 count != rows-1 判 none）" "load() 里"

# ── 2. 桩②：load 把半份判成 valid（能检索）─────────────────────────────────
reset_src
stub_py 桩② "$STORE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        return Load.partial(dim, dim, count, vec, full + 1)"
assert s.count(old) == 1, "桩②：没找到 return Load.partial 那一行"
new = "        return Load.valid(dim, dim, count, vec)"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩②（半份判成 valid）" "返回 partial"

# ── 3. 桩③：loadPrefix 排在 embedBatch 之后（装饰）─────────────────────────
reset_src
stub_py 桩③ "$ENGINE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把 loadPrefix 那一行注释掉、挪到编码循环后面（用一个假的调用"装样子"）
old = "        val prefix = RagIndexStore.loadPrefix(ctx, name, fullFp)"
assert s.count(old) == 1, "桩③：没找到 loadPrefix 那一行"
new = "        val prefix: Pair<Int, FloatArray>? = null  // 桩：假装读过（排在循环前）"
s = s.replace(old, new, 1)
# 循环之后补一次"真读"，制造"排在 embedBatch 之后"的形状
old2 = "        if (rows.isEmpty()) return \"没有可索引的内容（文档全为空或切不出块）\""
assert s.count(old2) == 1, "桩③：没找到收尾那一行"
new2 = ("        val latePrefix = RagIndexStore.loadPrefix(ctx, name, fullFp)\n"
        "        if (latePrefix != null) { /* 桩：这里才读，排在 embedBatch 之后 */ }\n"
        + old2)
s = s.replace(old2, new2, 1)
open(p, 'w', encoding='utf-8').write(s)
PYSTUB
must_red "桩③（loadPrefix 排在 embedBatch 之后）" "排在 embedBatch"

# ── 4. 桩④：编码循环从 0 起（读了前缀却不用）───────────────────────────────
reset_src
stub_py 桩④ "$ENGINE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        var i = startFrom\n"
assert s.count(old) == 1, "桩④：没找到 var i = startFrom"
new = "        var i = 0\n"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩④（编码循环从 0 起）" "从 startFrom 起步"

# ── 5. 桩⑤：定期落盘不分失败与否（有失败也落）─────────────────────────────
reset_src
stub_py 桩⑤ "$ENGINE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            if (failedIdx.isEmpty() &&\n"
assert s.count(old) == 1, "桩⑤：没找到 failedIdx.isEmpty() 那一行"
new = "            if (true &&\n"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤（定期落盘不分失败与否）" "只在没有失败时"

# ── 6. 桩⑥：续不上时静默从头（不读 lastDiff）──────────────────────────────
reset_src
stub_py 桩⑥ "$ENGINE" <<'PYSTUB'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 整块删掉：续不上时**静默**从头（连 lastDiff 都不读）。
# ⚠ 锚点锚**结构**（"作废闸那一块" = 从 `if (prefix == null && ... isPresent ...) {`
#   这一段，到它后面的续编那一段 `if (prefix != null && startFrom > 0) {` 之前），
#   不锚那一块**内部逐行原文**：块里的注释/措辞会随源码演进（`b646e0c` 之后
#   那段 `onlyBatch` 说明就是加进去的），按原文锚会在源码一漂时**打桩失败**，
#   而"打桩失败"此前的处置是**跳过该桩** —— 于是一条该红的桩静默不跑，
#   自测照样绿（这正是 `b646e0c` 自己诊断过的那只虫：`assert` 抛异常不是红）。
#   结构锚只在"整块被删/改名"时才失配，那正是这一桩要抓的形态。
pat = re.compile(
    r'\n        if \(prefix == null && RagIndexStore\.isPresent\(ctx, name\)\) \{.*?'
    r'(?=\n        if \(prefix != null && startFrom > 0\) \{)',
    re.S)
m = pat.search(s)
assert m, "桩⑥：没找到作废闸出声那一块（从 `prefix == null ... isPresent` 到续编那一段）"
open(p, 'w', encoding='utf-8').write(s[:m.start()] + s[m.end():])
PYSTUB
must_red "桩⑥（续不上时静默从头）" "作废闸"

# ── 7. 桩⑦：RagResumeUi 把"可续"判在"正文已改"之后（写反）──────────────────
reset_src
stub_py 桩⑦ "$UI" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''        resumable -> {
            // 只说"能续"与"续到哪"，不说"还要多久"（见文件头）。
            val frac = if (rowsTotal > resumeCount && resumeCount > 0) {
                "（已编 $resumeCount/$rowsTotal，其余接着编）"
            } else {
                "（上次没编完，接着编）"
            }
            "  ↻ 可续$frac"
        }
        !inSync -> "  ⚠ 正文已改，需重建"'''
assert s.count(old) == 1, "桩⑦：没找到可续/正文已改两支"
new = '''        !inSync -> "  ⚠ 正文已改，需重建"
        resumable -> "  ↻ 可续"'''
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑦（可续判在正文已改之后，写反）" "正文已改之前"

# ── 8. 桩⑧：进度落盘又拿"已编过的那些块"当整份正文（0.9.216 那个 bug）────
# 这一桩正是用户报的那一条：`saveProgress` 的实参换回 `rows`（只装到第 i 块），
# `savePartial` 照契约把它当整份正文写 -> 后 texts.size-i 块的字从 texts.jsonl 消失。
reset_src
stub_py 桩⑧ "$ENGINE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                val okP = RagIndexStore.saveProgress(ctx, name, fp, dim, i,
                    texts, sources, prefixVec,
                    partialMeta(name, i, texts.size, dim, cp))"""
assert s.count(old) == 1, "桩⑧：没找到 saveProgress 调用那一块"
new = """                val okP = RagIndexStore.savePartial(ctx, name, fp, dim, rows,
                    partialMeta(name, i, texts.size, dim, cp))"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑧（进度落盘拿已编过的半份当整份正文）" "不许把 rows/slice"

# ── 9. 桩⑨：进度落盘只给已编的那半份、连 texts 都不给 ──────────────────────
reset_src
stub_py 桩⑨ "$ENGINE" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                val okP = RagIndexStore.saveProgress(ctx, name, fp, dim, i,
                    texts, sources, prefixVec,
                    partialMeta(name, i, texts.size, dim, cp))"""
assert s.count(old) == 1, "桩⑨：没找到 saveProgress 调用那一块"
new = """                val okP = RagIndexStore.saveProgress(ctx, name, fp, dim, i,
                    allTextsPrefix, sources, prefixVec,
                    partialMeta(name, i, texts.size, dim, cp))"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑨（进度落盘没给全量正文 texts）" "实参里有 texts"

reset_src

# ── 10. 自测"打桩失败 -> FAIL"这条**元判据**本身 ─────────────────────────
# 桩靠锚点找那一块，源码演进会让锚点漂。漂了之后**绝不能**只是"跳过该桩"：
# 跳过 = 这条判据没跑，而输出里没有 FAIL 行，读日志的人会以为它绿了 ——
# 这正是 `b646e0c` 诊断过的那只虫（`assert` 抛异常不是红，是静默跳过，
# 且 `set -e` 会让后面所有桩**一条都不跑**、退出码还是 1）。
# 做法：起一个**独立**的小脚本，复用**同一份** `stub_py` 定义，喂一份必然
# 失配的锚点，断言它输出"打桩失败"那条 FAIL（而**不是**静默跳过）。
# 判据锚**输出里那行 FAIL**，不锚"函数返回了什么"。
reset_src
META=$TMP/meta.sh
cat > "$META" <<META_EOF
set -e
PY=\${PYTHON:-python3}
ok=0; bad=0
ck() { if [ "\$2" = "1" ]; then echo "PASS  \$1"; ok=\$((ok+1));
       else echo "FAIL  \$1"; bad=\$((bad+1)); fi; }
stub_dead=0
stub_py() { # 名字 文件...（与上面**同一份**定义，元判据验的就是它）
    _name=\$1; shift
    if "\$PY" - "\$@"; then stub_dead=0
    else
        echo "⚠ 打桩脚本失败（锚点漂了？）：\$_name" >&2
        ck "\$_name：打桩失败（锚点漂了？—— 该桩没跑，不是绿）" 0
        stub_dead=1
    fi
    return 0
}
must_red() { name=\$1; key=\$2
    [ "\$stub_dead" = "1" ] && return 0
    ck "\$name：守卫变红" 0; }
T=\$(mktemp -d); cp "\$1" "\$T/Engine.kt"
stub_py 元桩 "\$T/Engine.kt" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count("这一句源码里不存在，锚点必然失配") == 1, "元桩：故意打桩失败"
PYSTUB
[ "\$bad" -gt 0 ] || exit 3
META_EOF
meta_out=$(sh "$META" "$ENGINE" 2>&1) && meta_rc=0 || meta_rc=$?
case "$meta_out" in
  *"FAIL  元桩：打桩失败"*)
      ck "元判据：打桩失败被记成 FAIL（不是静默跳过）" 1 ;;
  *)
      ck "元判据：打桩失败被记成 FAIL（实际输出：$meta_out）" 0 ;;
esac

echo
echo "=== 知识库续编守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
