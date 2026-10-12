#!/bin/sh
# 「知识库正文的编辑闭环」的源码级守卫（RAG 阶段 2 的 1+2 条）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 阶段 1 落盘、0.9.152 装嵌入、0.9.153 接检索 —— 三块都在，守卫全绿，
# 而**编辑器是单向的**：粘贴框只在建库时被读，从来不被写。于是同一个根因
# 长出三个现象，全都"不崩、不报错、界面上看起来正常"：
#
#   ① 点库只改库名 -> 框里还是上一个库的文本（用户以为那就是这个库的内容）；
#   ② 启动不回填 -> 退出重进后框是空的（内容一直在 texts.jsonl 里，只是没人读回来）；
#   ③ 没有"保存" -> 每次加一条都要重粘全量（把"编辑"逼成了"整库重建"）。
#
# 修它需要**三处对齐**：存储层（编码 / 回读 / 只保存正文）、引擎（校验与降级）、
# UI（点库回填 / 保存按钮 / 脏标记 / 归属）。单测验得了"每一处自己算得对"，
# 验不了"它们接上了没有" —— 这一层缺口只存在于接线处，所以只能锚结构关系。
#
# 判据一律锚**函数体内的那一跳 / 那一处取值**，不锚"文件里出现过某关键字"
# （后者恒真：注释、别的分支里都有）。判据本体在 tools/rag_edit/ 下。
#
# 运行：sh tools/run_rag_edit_guard.sh
set -e
cd "$(dirname "$0")/.."
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
UI=app/src/main/java/com/xiaowan/localinference/RagResumeUi.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "EngineActivity.kt 存在" "[ -f \$EA ]"
c "LlmEngine.kt 存在"      "[ -f \$ENGINE ]"
c "RagIndexStore.kt 存在"  "[ -f \$STORE ]"

# ── ① 存储层：回读 / 只保存 / 编码三条路 ──────────────────────────────────
c "存储层能读出条目（readEntries）—— 回填的唯一数据来源" \
  "grep -q 'fun readEntries' \$STORE"
# ⚠ 判据锚的是"**行解析只有一份实现**"，不是"readEntries 体内出现了 decodeTextRow"。
#   阶段 3 把解析抽成了纯函数 `readEntriesTexts`（UI 的条目列表与删除的行号都要
#   与它同源），于是读文件那版只剩两行 —— 按原来那条判会**假红**（它恰好把
#   "只有一份实现"这件事判反了：实现被搬走反而说它没走那一步）。
#   所以判据改成两段：① 读文件那版确实把解析**委派**出去了；② 被委派到的那一处
#   真的用了 decodeTextRow（行解析没有第二份）。
c "readEntries 把行解析委派给唯一实现（不自己再写一份）" \
  "printf '%s' \"\$($PY tools/rag_edit/strip_comments.py \$STORE)\" | $PY tools/rag_index/_fnbody.py readEntries | grep -q 'readEntriesTexts'"
c "被委派的那一处真的走 decodeTextRow（行解析全仓库只有这一份）" \
  "printf '%s' \"\$($PY tools/rag_edit/strip_comments.py \$STORE)\" | $PY tools/rag_index/_fnbody.py readEntriesTexts | grep -q 'decodeTextRow'"
c "存储层有\"只保存正文\"的入口（saveTexts），不重建索引" \
  "$PY tools/rag_index/check_texts_hash_wiring.py save-texts-only \"\$STORE\""

# ── ② 引擎：保存正文有前提校验，且不抛异常 ────────────────────────────────
c "引擎的 saveTexts 拒绝\"该库还没有索引\"（否则写进孤儿目录）" \
  "$PY tools/rag_index/check_texts_hash_wiring.py save-guard \"\$ENGINE\""

# ── ③ UI：点库回填 / 启动回填 / 保存 / 脏标记 / 归属 / 代际 ────────────────
c "点库 -> 回填编辑器（load-on-select）" \
  "$PY tools/rag_edit/check_edit_wiring.py load-on-select \"\$EA\""
c "启动 / 回页时也回填（退出重进后不再是空白）" \
  "$PY tools/rag_edit/check_edit_wiring.py restore-load \"\$EA\""
c "保存按钮只写正文、不触发重建" \
  "$PY tools/rag_edit/check_edit_wiring.py save-writes-text \"\$EA\""
c "切库前确认未保存的改动（改动不许静默消失）" \
  "$PY tools/rag_edit/check_edit_wiring.py dirty-guard \"\$EA\""
c "脏标记来自真实编辑，程序化回填不算（防确认框变成噪声）" \
  "$PY tools/rag_edit/check_edit_wiring.py dirty-flag \"\$EA\""
c "保存的目标是编辑器归属的库（不是库名输入框的字面值）" \
  "$PY tools/rag_edit/check_edit_wiring.py owner-target \"\$EA\""
c "回填走代际计数 + 落地前复核归属（快速切库不串内容）" \
  "$PY tools/rag_edit/check_edit_wiring.py doc-gen \"\$EA\""
c "库列表标出\"正文已改，需重建\"" \
  "$PY tools/rag_edit/check_edit_wiring.py list-insync \"\$EA\" \"\$UI\""

# ── ③b 条目列表的**行号**必须与正文文件对齐（删错行的根因）─────────────
# ⚠ 这一条是本轮补的，它守的是"删错一行"。链条：
#     列表里「删」按下 -> [confirmDeleteEntry] -> [doDeleteEntry] ->
#     [RagIndexStore.removeEntry]，而 removeEntry 把那个数字当**正文文件的行号**
#     （体内 `lines[index]`）。
#   于是列表渲染时用的"行号"必须是**正文行号**，不能是"压紧后的第几条"：
#     · `readEntries` 会把空行/坏行**跳过并压紧** —— 下标 i 不再是行号；
#     · `readEntriesAligned` 保留 `null` 占位 —— 下标**恒等于行号**（注入侧已用它）。
#   用错的那个（`readEntries`）在正文里出现一个空行或坏行时就错位：界面上第 i 行
#   显示的是**另一条**，点它的「删」删掉隔壁 —— 不崩、不报错、Toast 照说"已删除"。
#   而空行恰好会被 removeEntry 的 `lines[index].isEmpty()` 挡住（表现为"点了没反应"），
#   坏行（非空但非法 JSON）挡不住，会真的被删掉，且此后索引与正文整体错位一格。
c "条目列表按**行号对齐**读正文（readEntriesAligned，不是压紧的 readEntries）" \
  "$PY tools/rag_edit/check_edit_wiring.py row-aligned \"\$EA\""
c "列表渲染保留行号（跳过的行不重排下标）" \
  "$PY tools/rag_edit/check_edit_wiring.py row-index-kept \"\$EA\""

# ── ④ 状态行必须反映"正文与索引不一致"（它是唯一的可观测面）─────────────
c "状态行读 textsInSync（不是只看 index.bin 在不在）" \
  "$PY tools/rag_inject/check_status.py \$EA"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
