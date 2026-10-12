#!/bin/sh
# 「知识库条目编辑（追加一条 / 删除单条）」的源码级守卫（RAG 阶段 3）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 阶段 2 结束时"加一条"只有两条路：重粘全量再重建（100 条要等 5~30 秒），
# 或者改完正文只保存（索引立刻失效、检索停用）。于是"维护知识库"在用户手里
# 永远是一次**整库操作**，而他想做的往往只是"补一句笔记""删掉写错的那句"。
#
# 阶段 3 把它拆成增量。增量的失效形态**全是不崩不报错的错位**：
#
#   ① 追加时"没算出向量的条目也占正文一行，而向量不占" -> 索引整体错位一行，
#      检索**给出结果**，只是内容全错了（最坏的一种）；
#   ② 删除时只删一个文件（或两个文件删的不是同一行）-> 后面每一行往前挪一位，
#      同上；而"条目数少了一个"看起来完全正常；
#   ③ 索引头的 count 与正文行数不再相等 -> 刚加的那条永远搜不到，
#      与"没命中"一模一样；
#   ④ 写完不重算指纹（正文 hash / 行数都在指纹里）-> 索引当场失效，
#      用户看到的是"追加成功了、检索却停了"，增量白做；
#   ⑤ UI 用"第几个视图"当行号 -> 有坏行被跳过时删掉的是**隔壁那一行**。
#
# 单测验得了"每一处自己算得对"，验不了**它们接上了没有** —— 这一层缺口只
# 存在于接线处，所以只能锚结构关系（"这个函数体内有没有那一跳 / 是不是那个取值"）。
# 判据一律锚**函数体内的那一跳 / 那一处取值**，不锚"文件里出现过某关键字"
# （后者恒真：注释、别的分支里都有）。判据本体在 tools/rag_edit/ 下。
#
# 运行：sh tools/run_rag_entry_guard.sh
set -e
cd "$(dirname "$0")/.."
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
HDR=app/src/main/cpp/rag_index.h
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "EngineActivity.kt 存在" "[ -f \$EA ]"
c "LlmEngine.kt 存在"      "[ -f \$ENGINE ]"
c "RagIndexStore.kt 存在"  "[ -f \$STORE ]"

# ── ① 引擎：追加真的编码**这一条**，且有先决条件 ─────────────────────────
c "追加前编码了**这一条**（不是标脏等重建）" \
  "$PY tools/rag_edit/check_entry_wiring.py append-encodes \"\$ENGINE\""
c "追加的先决条件（该库已有索引 + 嵌入模型在位 + 一对读齐）" \
  "$PY tools/rag_edit/check_entry_wiring.py append-precondition \"\$ENGINE\""
# 第 3 步：超长截断必须**出声**。旧代码注释写着"并提示"，代码里却一个字没有 ——
# 于是"向量只覆盖前 N 字、注入却是全文"这件用户看不见的事永远查不到。
c "追加超长时截断**出声**（成功回执上带话，不是只打日志）" \
  "$PY tools/rag_edit/check_entry_wiring.py append-truncation \"\$ENGINE\""

# ── ② 存储层：行号 / 行数 / 指纹 / 顺序，四处硬约束 ──────────────────────
c "追加的 rows 按**新正文**现算，且指纹一起重算（hash + 行数）" \
  "$PY tools/rag_edit/check_entry_wiring.py append-rows \"\$STORE\""
c "向量区只装\"算出来的那几条\"（没算出的不占向量行）" \
  "$PY tools/rag_edit/check_entry_wiring.py append-count \"\$STORE\""
c "两个写入口都**先写索引、后写正文**（失败落在可归因的一侧）" \
  "$PY tools/rag_edit/check_entry_wiring.py idx-before-texts \"\$STORE\""
c "每次编辑前校验「指纹行数 == 正文行数 == count」（缺一即拒）" \
  "$PY tools/rag_edit/check_entry_wiring.py rows-checks \"\$STORE\""
c "删除按**正文行号**同时移掉两个文件里的同一行" \
  "$PY tools/rag_edit/check_entry_wiring.py delete-line-index \"\$STORE\""
c "删除后剩余向量逐行前移，且搬运数不符即拒" \
  "$PY tools/rag_edit/check_entry_wiring.py delete-shift \"\$STORE\""
c "删除的三道闸（越界 / 索引里没有这一行 / 不许删空）" \
  "$PY tools/rag_edit/check_entry_wiring.py delete-guard \"\$STORE\""

# ── ③ UI：条目列表按行号删、删除前确认、不碰草稿、不重建 ────────────────
c "条目列表记下行号、按行号删、删除前确认" \
  "$PY tools/rag_edit/check_entry_wiring.py ui-rows \"\$EA\""
c "追加**不碰**编辑器里的草稿（草稿与库内容互不覆盖）" \
  "$PY tools/rag_edit/check_entry_wiring.py no-draft-clobber \"\$EA\""
c "追加 / 删除都不触发整库重建（否则增量没有意义）" \
  "$PY tools/rag_edit/check_entry_wiring.py no-full-rebuild \"\$EA\""
c "状态行报出\"库里几条\"（追加到底进去没有的唯一可见答案）" \
  "$PY tools/rag_edit/check_entry_wiring.py status-count \"\$EA\""

# ── ④ 行数进了指纹：`r` 字段必须两边都有（少一个就退回"索引落后于正文"）──
# 判据锚**两边各自的实现**里都出现了 `r` 那一天字段，而不是"文件里出现过 r"。
# ⚠ 不能用 `_fnbody` 判这个：它把字符串字面量抹成 `""`，而"有没有 r 这个前缀"
#   恰恰就在字面量里（本轮真踩到，判据恒假）。所以走 check_fp_fields.py ——
#   它**不剥字符串字面量**（字段前缀本身就是字面量），只剥注释，
#   并且判的是"C++ 与 Kotlin 两边的字段集合**相等**"，比"单边出现过 r"强。
c "指纹字段集合两边一致（含 r=正文行数）" \
  "$PY tools/rag_index/check_fp_fields.py \"\$HDR\" \"\$STORE\""
# 而且 r 必须真的进了两边各自的指纹实现（不能只出现在结构体里）
c "C++ 侧 fingerprint_of 里把 r 写进指纹" \
  "grep -q '\"r\"' \$HDR"
# ⚠ 同样不能用 `_fnbody` 判字面量（它抹成 `""`）。这里判的是**取值表达式**：
#   `append(rows)` 出现在指纹构造里 —— 把 rows 去掉（退回 -- 老行为）时这一条会红。
#   为什么锚"append(rows)"而不是 "rows 出现过"：形参里本来就有 rows，
#   按"出现过"判会**恒真**。
c "Kotlin 侧 fingerprintOf 里把 rows 写进指纹" \
  "printf '%s' \"\$($PY tools/rag_edit/strip_comments.py \$STORE)\" | $PY tools/rag_index/_fnbody.py fingerprintOf | grep -q 'append(rows)'"
c "Kotlin 侧 fingerprintOf 里有 rows 形参（且 rowsInFingerprint 能取出它）" \
  "printf '%s' \"\$($PY tools/rag_edit/strip_comments.py \$STORE)\" | $PY tools/rag_index/_fnbody.py fingerprintOf | grep -q 'rows'"
c "load 拿指纹里的 r 与索引 count 比对（两者不等 = 不许检索）" \
  "printf '%s' \"\$($PY tools/rag_edit/strip_comments.py \$STORE)\" | $PY tools/rag_index/_fnbody.py load | grep -q 'rowsInFingerprint'"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
