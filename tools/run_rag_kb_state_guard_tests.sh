#!/bin/sh
# 自测 `tools/rag_kb_state/check_wiring.py`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是**存在性、结构性**断言，三个自欺模式（与本仓库其它守卫同族）：
#
#   1. **恒真**：pattern 写成到处都有的文本。本族最容易恒真的有两条 ——
#      "状态行不许读库名框里的字面值"（解释这件事的注释里必然带着 `ragKbEt.text`）
#      与"列表不按索引判在不在"（`isPresent` 的解释注释里就有 `index.bin`）。
#      前者靠**锚函数体内**、后者靠**锚定表达式**解掉，而这两件事只有
#      **真打桩验红**才证明它有效。
#   2. **锚错位置**：钉"文件里出现过 isPresent"而不是"**状态行这一行**问的是它"。
#      `isPresent` 在本文件里有十几处调用，锚错了这条判据对"状态行读什么"毫无约束。
#   3. **恒假后被静音**：pattern 与源码差一个字符 -> 永远 FAIL，
#      很快就会有人把 `exit 1` 改成 `|| true`。桩①②③④⑤⑥ 都是这一族。
#
# 桩全部来自"真正会写错的写法"（这五条 bug 真正的失败方向）：
#   · 状态行退回读库名框里的字面值（用户报的"改名不生效 / 该库不存在"）
#   · 状态行自己去看 texts.jsonl（与库列表分家：列表里有、状态行说没有）
#   · 导入在**弹选择器之后**才取目标库（失焦把 currentKb 覆盖掉，落盘落在别的库）
#   · 「未落盘」又和「待重建」说成一句（用户问的"这俩按钮有啥区别"）
#   · pending 那一行不和磁盘上的真库去重（同名两行 = 用户报的"短暂延迟"）
#   · 删除按钮的判据退回 `hasTexts` / `exists`（用户报的"按钮全部不可用"）
#   · 「删索引」不读返回值、不说"本来就没有"（0 条时说"已删除"，用户以为删干净了）
#   · 删库按索引判存在（半成品库删不掉）
#   · 切到知识库页时无条件重画状态行（用户报的"建索引提示丢失"）
#   · 两把尺子分家（isPresent 与 list 各写一套判据）
#   · 守卫自己的 fnbody 退回朴素大括号计数（`list()` 是表达式体，判据恒假）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_kb_state_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/rag_kb_state/check_wiring.py
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
STORE=$SRC/RagIndexStore.kt
UI=$SRC/RagResumeUi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$STORE" "$TMP/store.bak"; cp "$GUARD" "$TMP/guard.bak"
cp "$UI" "$TMP/ui.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/store.bak" "$STORE"; cp "$TMP/guard.bak" "$GUARD"; cp "$TMP/ui.bak" "$UI"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/store.bak" "$STORE"; cp "$TMP/ui.bak" "$UI"; }

run_guard() { set +e; "$PY" "$GUARD" "$ACT" "$STORE" "$UI" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

stub_py() { "$PY" - "$@" || { echo "⚠ 打桩脚本失败（锚点漂了？）—— 该桩将被跳过" >&2; return 1; }; }

# ⚠ 只认**带 FAIL 前缀**的那一行：本守卫里有若干对"一正一反"的判据，
# 文案开头是同一串字 —— 直接 grep 整份输出会先命中那条 PASS 行，
# 于是这条桩自测会把"判据确实点到了"报成失败。桩自测假红的下场是没人再看它。
must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    line=$(grep -E "^FAIL " "$TMP/guard.out" | grep -F "$key" | head -1)
    if [ -n "$line" ]; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」（没有任何 FAIL 行命中它）" 0; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：状态行退回读库名框里的字面值 ────────────────────────────────
# 症状 = 用户报的第 ①④ 条：他点了已存在的库 test，在库名框里改成 test1
# （还没提交）—— 屏幕上立刻出现 "当前库：test1"、"该库不存在"、
# "新建的库（未落盘）"。而他手里那个库好好的。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        val kb = RagInject.currentKb(this)\n        val embedOk = LlmEngine.hasEmbedModel'
new = '        val kb = ragKbEt.text.toString().trim()\n        val embedOk = LlmEngine.hasEmbedModel'
assert s.count(old) == 1, "桩①：没找到状态行取当前库那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（状态行读库名框的字面值）" "状态行**不许**读库名框里的字面值"
reset_src

# ── 2. 桩②：状态行自己去看 texts.jsonl ──────────────────────────────────
# 症状：与库列表分家 —— 一个"texts.jsonl 为空 / 建库被打断"的库
# 在列表里看得见，而状态行说"该库不存在"（用户报的第 ④ 条）。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        val diskExists = listed != null || (kb.isNotEmpty() && RagIndexStore.isPresent(this, kb))'
new = '        val diskExists = kb.isNotEmpty() && RagIndexStore.textsFile(this, kb)?.isFile == true'
assert s.count(old) == 1, "桩②：没找到 diskExists 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（状态行自己看 texts.jsonl）" "状态行**不许**自己去看 texts.jsonl"
reset_src

# ── 3. 桩③：导入在**弹选择器之后**才取目标库 ────────────────────────────
# 症状：文件选择器让库名框失焦 -> 失焦那条路把 currentKb 覆盖成
# 用户打了一半的名字 -> 用户从选择器回来，那份文档落进了"另一个库"。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    private fun pickRagArchiveFile() {\n        freezeImportTarget()'
new = '    private fun pickRagArchiveFile() {'
assert s.count(old) == 1, "桩③：没找到导入库那条选择器"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（导入库不冻结目标库）" "「导入库」在**弹选择器之前**冻结目标库"
reset_src

# ── 4. 桩④：确认框回到"此刻的 currentKb" ────────────────────────────────
# 与桩③ 是**两件事**（一个在入口冻结、一个在落地时读）：只修一头仍然会把
# 内容导进另一个库，所以两条判据都要有。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        val cur = ragImportTarget'
new = '        val cur = RagInject.currentKb(this)'
assert s.count(old) == 1, "桩④：没找到确认框取目标库那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（确认框读此刻的 currentKb）" "确认框的目标库用**冻结的那份**"
reset_src

# ── 5. 桩⑤：「未落盘」又和「待重建」说成一句 ──────────────────────────────
# 症状 = 用户问的第 ③ 条：他在一个 0 条的库上把每个按钮点一遍，
# 而屏幕上每个字都说"点重建"（"这俩按钮有啥区别"）。
# 本轮判据的**锚点换了两处**：五档状态收进了 RagResumeUi.state（纯逻辑），
# 于是"未落盘与待重建分开说"要**两半都改坏**才会红 —— 桩⑤ 打收方（列表不再
# 喂 isPending），桩⑤b 打发方（RagResumeUi 里 isPending 那一档落到后面）。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                isPending = r.isPending, hasIndex = r.hasIndex, inSync = r.inSync,'
new = '                isPending = false, hasIndex = r.hasIndex, inSync = r.inSync,'
assert s.count(old) == 1, "桩⑤：没找到列表喂 isPending 那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（列表不再喂 isPending）" "喂给 RagResumeUi.state"
reset_src

stub_py "$UI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        isPending -> "  · 未落盘（粘贴/导入内容后自动保存，离开即丢弃）"\n        !hasIndex -> "  ⚠ 待重建（点「建索引/重建索引」）"'
new = '        !hasIndex -> "  ⚠ 待重建（点「建索引/重建索引」）"\n        isPending -> "  · 未落盘"'
assert s.count(old) == 1, "桩⑤b：没找到 isPending 与待重建两支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤b（未落盘档落到待重建之后）" "独立且**最前**的一档"
reset_src

# ── 6. 桩⑥：pending 那一行不与磁盘上的真库去重 ────────────────────────────
# 症状 = 用户报的第 ② 条（"有短暂延迟"）：落盘完成前后同一个名字在列表里
# 出现两次（一行"0 条 · 未落盘"、一行"N 条"），而它们切换的那一下就是他看到的延迟。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (ragPendingKb.isNotEmpty() && shown.none { it.name == ragPendingKb }) {'
new = '        if (ragPendingKb.isNotEmpty()) {'
assert s.count(old) == 1, "桩⑥：没找到 pending 那一行的判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（pending 行不去重）" "不再画**『未落盘』那一行"
reset_src

# ── 7. 桩⑦：删除按钮的判据退回「有没有正文文件」──────────────────────────
# 症状 = 用户报的第 ⑤ 条：杀进程留下一个"有目录、没索引、正文是空的"库，
# 它进得了列表，而每个按钮都是灰的 —— 没有任何操作手段删它。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (::ragDeleteBtn.isInitialized) {\n            ragDeleteBtn.isEnabled = diskExists && kb != ragPendingKb && !ragBuilding\n        }'
new = '        if (::ragDeleteBtn.isInitialized) {\n            ragDeleteBtn.isEnabled = kb != ragPendingKb && hasTexts && !ragBuilding\n        }'
assert s.count(old) == 1, "桩⑦：没找到删库按钮那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（删库按钮退回 hasTexts）" "旧判据 \`hasTexts && …\` 已从按钮上撤掉"
reset_src

# ── 8. 桩⑧：「删索引」不读返回值、不说"本来就没有" ────────────────────────
# 症状：0 条的库上点它，屏幕说"已删除索引" —— 而这次什么也没删（磁盘上
# 连正文都没有）。用户据此以为"这个库被删干净了"，而列表里那一行消失
# 只是因为空目录不进列表。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val ok = runCatching { RagIndexStore.drop(this, name) }.getOrDefault(false)\n"
new = "        RagIndexStore.drop(this, name)\n        val ok = true\n"
assert s.count(old) == 1, "桩⑧：没找到删索引读返回值那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（删索引丢掉返回值）" "的**返回值**"
reset_src

# ── 9. 桩⑨：删库退回"按索引判存在" ───────────────────────────────────────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (!RagIndexStore.isPresent(this, name)) {\n            if (name == ragPendingKb) {'
new = '        if (!(RagIndexStore.indexFile(this, name)?.isFile == true)) {\n            if (name == ragPendingKb) {'
assert s.count(old) == 1, "桩⑨：没找到删库的存在判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（删库按索引判存在）" "删库的判据是『**库在不在**』"
reset_src

# ── 10. 桩⑩：切到知识库页时无条件重画状态行 ───────────────────────────────
# 症状 = 用户报的第 ④ 条："点击建索引，此时有建索引的相关提示，我切换页面后，
# 再回到知识库页面，**建索引提示丢失**"。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                if (ragBuilding) {\n                    // 切页这一跳**必须保留最后一条读数**'
new = '                if (false) {\n                    // 切页这一跳**必须保留最后一条读数**'
assert s.count(old) == 1, "桩⑩：没找到切页那一跳的建库判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑩（切页覆盖建索引提示）" "建库进行中就不许重画状态行"
reset_src

# ── 11. 桩⑪：两把尺子分家（isPresent 与 list 各写一套）──────────────────
# 症状：一个"有目录、没索引、正文为空"的库 —— 列表按目录列（认得它）、
# isPresent 按索引判（不认得它）—— 于是列表里点得动、按钮说"磁盘上没有这个库"。
stub_py "$STORE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        return File(d, FILE).isFile || File(d, TEXTS).isFile'
new = '        return File(d, FILE).isFile'
assert s.count(old) == 1, "桩⑪：没找到 isPresent 的表达式"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑪（两把尺子分家）" "isPresent 的表达式"
reset_src

# ── 12. 桩⑫：这一条钉的是**守卫自己** ───────────────────────────────────
# `list()` 是表达式体函数，体内有多个 lambda 的 `{}`。朴素的大括号计数会在
# 第一个 lambda 的 `}` 处收工（实测只取到 90 个字符），于是
# "list 里是同一句判据"这条**永远 FAIL** —— 而源码是对的。
# 症状是"假红"，而假红的判据很快会被静音（那时它连假绿都不如）。
stub_py "$GUARD" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    ln = fnbody(store_c, "fun list(")'
new = '    ln = fnbody_plain(store_c, "fun list(")'
assert s.count(old) == 1, "桩⑫：没找到 list 的锚点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑫（大括号计数在 lambda 处收工）" "list() 里是**同一句**判据"
cp "$TMP/guard.bak" "$GUARD"
reset_src

# ── 13. 桩⑬：库列表退回"一次 list() 全算完"（ISSUE #228 的成因本身）────────
# 症状 = 用户报的那条："点击 2500 条知识库 -> **选中其他库，状态不切换**"。
# 那不是选中状态没刷新，是主线程在做 O(全库字节数) 的活（`list()` 逐库读完整正文），
# 于是点击压根没被派发。把它退回一层 = 把那个乘积请回来。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        renderRagListSkeleton()\n        loadRagListCounts()'
new = '        renderRagListSkeleton()'
assert s.count(old) == 1, "桩⑬：没找到两层那一跳"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑬（库列表退回一层）" "先画骨架、再去后台补数字"
reset_src

# ── 14. 桩⑭：数字缺席时回落到索引里的 `entries`（报一个可能过期的数）──────
# `entries` 来自索引头，而 `保存正文` 不重算向量 —— 于是它会落后于正文。
# 把它画出来就是"库里几条"报了一个错数，而错数与真数在屏幕上长得一样。
# 更坏的一层在骨架那一帧：那一刻**还没量过任何库**，报出来的全是索引里的旧数。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                val detail = if (counts == null) ""'
new = '                val detail = if (false) ""'
assert s.count(old) == 1, "桩⑭：没找到留白那一支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑭（数字缺席时不留白）" "数字缺席时**留白**"
reset_src

# ── 15. 桩⑮：状态行的条数退回**无界**整份读（主线程那一次卡顿）───────────
# `textsCount` 把整份 `texts.jsonl` 读进内存再 `split('\n')`；而 `renderRagStatus`
# 会被切库 / 刷列表 / 导入 / 建库结束一路调用 —— 于是每次重画都在主线程上
# 读一次整文件。这正是"点一个几千条的库就卡住"那一步的第二个乘数。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                ?: RagIndexStore.textsCountCapped(this, kb, RAG_STATUS_COUNT_CAP_BYTES)'
new = '                ?: RagIndexStore.textsCount(this, kb)'
assert s.count(old) == 1, "桩⑮：没找到有界轻读那一跳"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑮（状态行退回无界整份读）" "状态行的条数走**有界**轻读"
reset_src

# ── 16. 桩⑯：有界轻读先读再截（把 `null` 那一支删掉）────────────────────
# "有界"的全部意思就是**不要**付那一下 IO。读了 `capBytes` 再截，仍然是
# 主线程上的那一次读 —— 只是短了一点。而返回 `null` 是这条判据的第二个语义：
# "量不完"与"0 条"必须分得开（同一个字，相反的结论）。
stub_py "$STORE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (size > capBytes) return null'
new = '        if (false) return null'
assert s.count(old) == 1, "桩⑯：没找到超闸门那一跳"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑯（有界轻读先读再截）" "超闸门时**先看大小就停手**"
reset_src

# ── 收尾：还原之后守卫必须又是绿的（防"某一桩的还原漏了"）────────────────
if run_guard; then ck "收尾：所有桩还原之后守卫重新全绿" 1
else ck "收尾：所有桩还原之后守卫重新全绿" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 知识库状态接线守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 知识库状态接线守卫自测：PASS $ok / FAIL $bad ==="; exit 1
