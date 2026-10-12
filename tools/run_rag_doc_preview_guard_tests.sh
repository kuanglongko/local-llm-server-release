#!/bin/sh
# 自测 `tools/rag_doc_preview/check_wiring.py`：把它对着几份**打桩源码**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是**存在性、结构性**断言（谁在谁之前 / 在哪个函数体内 / 取数走
# 哪个函数），两个自欺模式这一族最容易踩：
#
#   1. **锚错位置**：钉"文件里出现过 renderRagDocSlice"而不是"这一片画在
#      renderRagRows 里" —— 前者在别处也成立。所以判据一律锚函数体内 + 相对次序。
#   2. **恒假后被静音**：pattern 与源码差一个字符 -> 永远 FAIL，
#      很快就会有人把 `exit 1` 改成 `|| true`。
#
# 桩全部来自"真正会写错的写法"（P1-a/P1-b 真正的失败方向，而它们**全都不抛异常**）：
#   · 这一片与列表各画各的页（列表第 3 页、框里第 1 页）
#   · 建库时切片态**仍取框**（拿一页去建 -> 静默切出一个缺九成内容的库）
#   · 保存正文时切片态**不按行回写**（`texts.jsonl` 被整份覆盖成一页，没有副本）
#   · 回写那一跳**又用 saveTexts / runRagSave**（同上，只是换了个名字）
#   · `ragDocEntries` 在切片态**不拒绝**（三个"整份落盘"的调用方又拿到一页）
#   · "画出去的那一片是什么"**没记**（或记在别处）-> 回写落在另一段行上
#   · 切走时的「保存」在切片态又落回 runRagSave（用户以为在"丢弃"，其实整份覆盖）
#   · 两态里有一个把框**锁死**（P1-a 的只读属性没整个去掉 -> 半态）
#   · loadRagDoc 又拼整库（"点开库要等"的根回来）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_doc_preview_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"

PY=${PYTHON:-python3}
GUARD=tools/rag_doc_preview/check_wiring.py
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
CAP=$SRC/RagUiCap.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$CAP" "$TMP/cap.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/cap.bak" "$CAP"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/cap.bak" "$CAP"; }

run_guard() { set +e; "$PY" "$GUARD" "$ACT" "$CAP" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

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

# ── 1. 桩①：这一片与列表**各画各的页**（列表第 3 页、框里第 1 页）──────────
# 症状：用户按框里看到的内容去点「删」/「追加」/「保存本页改动」，
#       落在一段他以为不是那里的行上。P1-b 之后还多一层：回写落在别的行。
stub_py "$CAP" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    fun docPreviewRange(total: Int, page: Int): IntRange = rowPageRange(total, page)"
new = "    fun docPreviewRange(total: Int, page: Int): IntRange = if (total <= 0) IntRange.EMPTY else 0 until minOf(total, MAX_ROWS)"
assert s.count(old) == 1, "桩①：没找到 docPreviewRange 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩①（这一片固定第 1 页、与列表不同源）" "这一片的区间**复用** rowPageRange"
reset_src

# ── 2. 桩②：这一片不跟着列表画（画在别处）─────────────────────────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 两处画点（空库那一支 + 末尾）都要拿掉 —— 留一处守卫仍绿，那正是"两处各画一次"。
old = "        if (ragDocSliced) renderRagDocSlice()\n    }"
assert s.count(old) == 1, "桩②：没找到末尾那个画点"
s = s.replace(old, "    }", 1)
old2 = "            renderRagDocSlice()\n            return"
assert s.count(old2) == 1, "桩②：没找到空库那一支的画点"
s = s.replace(old2, "            return", 1)
open(p, 'w', encoding='utf-8').write(s)
PYSTUB
must_red "桩②（这一片不画在 renderRagRows 里）" "这一片在 renderRagRows 里画"
reset_src

# ── 3. 桩③：建库时切片态**仍取框**（拿一页去建）──────────────────────
# 症状：静默切出一个只有 60 条的库，回执说"索引已重建"，九成内容没进去。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            startBuildDisk(name)\n            return\n        }"
new = "            return\n        }"
assert s.count(old) == 1, "桩③：没找到 startBuildDisk(name) 那一支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩③（切片态建库不走磁盘）" "切片态走 startBuildDisk"
reset_src

# ── 4. 桩④：保存正文时切片态**不按行回写**（整份覆盖成一页）─────────────
# 症状：texts.jsonl 被整份覆盖成那一页，磁盘上原来那份完整正文没了、无副本。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (ragDocSliced) { saveRagDocSlice(); return }"
assert s.count(old) == 1, "桩④：没找到保存正文的切片态分岔"
open(p, 'w', encoding='utf-8').write(s.replace(old, "        ", 1))
PYSTUB
must_red "桩④（保存正文时切片态不走按行回写）" "切片态走 saveRagDocSlice"
reset_src

# ── 5. 桩⑤：回写那一跳**又用 saveTexts**（整份覆盖，换个名字而已）────────
# 这条是 P1-b 的**核心判据**：把"改字"偷偷变成"整份覆盖"。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                LlmEngine.rewriteRows(this, name, plan.indices, plan.texts)"
new = ("                LlmEngine.runRagSaveX(this, name, plan.texts.map { "
       "RagIndexStore.Entry(it, \"粘贴-$name\") })")
assert s.count(old) == 1, "桩⑤：没找到 rewriteRows 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤（回写那一跳改用 saveTexts）" "回写**不经过** saveTexts / runRagSave"
reset_src

# ── 5b. 桩⑤b：回写那一跳**不走** rewriteRows（改字不重算向量）──────────
# 症状：改字之后索引被判"需重建"，检索停用 —— 正是 ISSUE #228 要消掉的那件事。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                LlmEngine.rewriteRows(this, name, plan.indices, plan.texts)"
new = "                RagIndexStore.replaceRowsInPlace(this, name, plan.indices, plan.texts)"
assert s.count(old) == 1, "桩⑤b：没找到 rewriteRows 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤b（回写不走 rewriteRows、改字不重算向量）" "回写走 LlmEngine.rewriteRows"
reset_src

# ── 5c. 桩⑤c：回写把三态**压成两态**（"改动在、只是要重建"被说成失败）──
# 症状：用户以为没存上，重敲一遍；更坏的是去重导（导入是追加合并，重导 = 条目翻倍）。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把 TextsOnly 那一支**并进** Failed 的出口（最省事、也最贵的写法错误）。
old = "                    is LlmEngine.RewriteResult.TextsOnly -> {"
new = "                    is LlmEngine.RewriteResult.Failed -> {"
assert s.count(old) == 1, "桩⑤c：没找到 TextsOnly 那一支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤c（回写把 TextsOnly 并进 Failed）" "回写按 RewriteResult **三支**分开"
reset_src

# ── 5d. 桩⑤d：三支**拆成两个 if**（先后判断决定回执是哪一句）───────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                when (res) {"
new = "                kotlin.run {"
assert s.count(old) == 1, "桩⑤d：没找到 when (res) 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤d（三支不挂在同一个 when(res) 上）" "三支挂在同一个 when(res) 上"
reset_src

# ── 6. 桩⑥：`ragDocEntries` 在切片态**不拒绝** ─────────────────────────
# 症状：三个"整份落盘"的调用方（runRagSave / autosaveKb / 导入）又拿到一页。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (ragDocSliced) return emptyList()\n"
assert s.count(old) == 1, "桩⑥：没找到 ragDocEntries 的切片态拒绝"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYSTUB
must_red "桩⑥（ragDocEntries 在切片态不拒绝）" "ragDocEntries 在切片态**拒绝**"
reset_src

# ── 7. 桩⑦：`ragDocEntries` 的拒绝**在取数之后**（先读进一页再说）────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ("        if (ragDocSliced) return emptyList()\n"
       "        return ragDocEt.text.toString().split('\\n')")
new = ("        val out = ragDocEt.text.toString().split('\\n')\n"
       "        if (ragDocSliced) return emptyList()\n"
       "        return out")
assert s.count(old) == 1, "桩⑦：没找到 ragDocEntries 的拒绝+取数那一对"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑦（ragDocEntries 先取数再拒绝）" "拒绝在**取数之前**"
reset_src

# ── 8. 桩⑧：切走时的「保存」在切片态又落回 runRagSave ─────────────────
# 症状：用户以为自己在"丢弃"，而这一步把整个库整份覆盖成那一页。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                    runRagSliceSave(owner, plan) { proceed() }"
new = ("                    val docs = ragDocEntries(owner)\n"
       "                    runRagSave(owner, docs) { proceed() }")
assert s.count(old) == 1, "桩⑧：没找到切片态的 proceed 保存"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑧（切走时切片态落回 runRagSave）" "切走时的「保存」在切片态走按行回写"
reset_src

# ── 9. 桩⑨：两态里有一个把框**锁死**（P1-a 的只读属性没整个去掉）────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        ragDocEt.setFocusable(true)\n"
new = "        ragDocEt.setFocusable(false)\n"
assert s.count(old) == 1, "桩⑨：没找到切态那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑨（切态里把框锁死）" "**没有**把框锁死的动作"
reset_src

# ── 10. 桩⑩：把"画出去的那一片"**记在别处**（回写落在另一段行上）─────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        ragDocSliceRows = rows\n"
assert s.count(old) == 1, "桩⑩：没找到记录那一片的那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "        \n", 1))
PYSTUB
must_red "桩⑩（没记下画出去的那一片）" "记录**在这一跳**写"
reset_src

# ── 11. 桩⑪：loadRagDoc 又拼整库（"点开库要等"的根回来）──────────────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        refreshRagRows(name)\n    }"
new = ("        ioExecutor.execute {\n"
       "            RagIndexStore.readEntries(this, name).joinToString(\"\\n\") { it.text }\n"
       "        }\n"
       "        refreshRagRows(name)\n    }")
assert s.count(old) >= 1, "桩⑪：没找到 loadRagDoc 末尾"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑪（loadRagDoc 又拼整库）" "loadRagDoc 不读整库"
reset_src

# ── 12. 桩⑫：切库时不清掉记录（拿上一个库的行号回写）────────────────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        ragDocSliceRows = emptyList()\n"
assert s.count(old) == 1, "桩⑫：没找到切库清记录那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYSTUB
must_red "桩⑫（切库不清掉那一片的记录）" "切库时把记录清掉"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 正文框装一片 + 按行回写守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 正文框装一片 + 按行回写守卫自测：PASS $ok / FAIL $bad ==="; exit 1
