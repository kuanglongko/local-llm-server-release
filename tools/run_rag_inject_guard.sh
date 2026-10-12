#!/bin/sh
# 「RAG 注入：格式两边一致 + 接线不漏 + 开关只有一个判据点」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 本轮的注入分成了**三处**，两两之间必须严丝合缝：
#
#   · `app/src/main/cpp/rag_index.h`              —— C++ 侧的 render_injection（检索库内部的引用）；
#   · `app/src/main/java/.../RagInject.kt`        —— Kotlin 侧的选择/渲染/拼接（App 实际走这条）；
#   · `app/src/main/java/.../EngineActivity.kt`   —— 接线（preparedMessages -> 拼进 system）。
#
# 单测验得了"每一处自己算得对"，**验不了三处之间对齐**。而对齐一旦破，症状全是静默的：
#
#   ① 行首标记两边不一致 / 来源修饰被重新加回 -> 前者让"到底注入了没有"的 grep 判据
#      有一边恒假，后者让注入片段里多出一段可被模型抄进回答的元信息（本轮要消掉的东西）；
#   ② 表头策略不一致（一边无条目返回空串、另一边仍返回表头）->
#      "非空 = 已注入"这条判据恒真，任何注入失效的验证都白做；
#   ③ preparedMessages 没接上注入 -> 用户配了知识库但回答里永远不带内容（= 阶段 0 那个"调用方为 0"的复现）；
#   ④ 注入写进了 user 段 -> 模型当成"用户在问这个"，答非所问而 prompt 语法合法；
#   ⑤ 开关判据写了两处 -> 漂移方向必然是"多注入一个字节"。
#
# 判据一律锚**结构关系**（"这个函数体内有没有那一跳"、"两边的格式常量是否相等"），
# 不锚"文件里出现过某个字符串"（那是恒真的：写错的那一支也会有关键词）。
#
# 运行：sh tools/run_rag_inject_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

HDR=app/src/main/cpp/rag_index.h
INJ=app/src/main/java/com/xiaowan/localinference/RagInject.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "RagInject.kt 存在"      "[ -f \$INJ ]"
c "rag_index.h 存在"       "[ -f \$HDR ]"
c "EngineActivity.kt 存在" "[ -f \$EA ]"
c "LlmEngine.kt 存在"      "[ -f \$ENGINE ]"

# ── ① 格式交叉校验：两边喂同一份输入逐字节比（这是本守卫的核心）──────────
c "注入格式交叉校验（C++ vs Kotlin）" \
  "$PY tools/rag_inject/cross_inject.py \"\$HDR\" \"\$INJ\""

# ── ② 接线：preparedMessages 真的把注入拼进 system ────────────────────────
c "注入真的接到了拼 prompt 处（preparedMessages）" \
  "$PY tools/rag_inject/check_wiring.py \"\$EA\" \"\$ENGINE\""

# ── ③ 开关默认值：App 内默认开；且键与"当前库"分开 ────────────────────────
# 判据锚**取值表达式**，不锚"文件里有 enabled 这个词"（后者恒真）。
c "RagInject.enabled 的默认值是 true（App 内默认使用知识库）" \
  "grep -q 'getBoolean(KEY_ENABLED, true)' \$INJ"
c "开关与当前库使用**不同**的 prefs 键" \
  "$PY tools/rag_inject/check_keys.py \$INJ"

# ── ④ 索引必须连正文一起落盘（否则检索命中行号却无正文可注入）───────────
c "buildIndex 把正文一起交给 save，且与向量同序" \
  "$PY tools/rag_inject/check_texts.py \"\$ENGINE\""

# ── ④b 注入侧 `rows`/`labels` 必须**按行号对齐**（第 4 轮，用户拍板修法 a）──
# `RagInject.render` 的契约是"按行号取源"（`rows[p.index]`，与 C++ 逐字对应）。
# 调用方若按**命中顺序压紧**地造数组（长度只有 picks.size），两个下标口径就只在
# "命中的正好是第 0、1、2…行"时偶然相等 —— 命中靠后的行会被**整块丢掉**
# （用户报的「选中一条，内容为空」），落在前几行时更坏：注入的是**另一行**的正文。
# 单测验不出（单测喂的都是行号对齐的数组），错的是调用方 —— 只能在源码级锚"怎么造数组"。
c "注入侧 rows 按行号对齐（不按 picks 压紧）" \
  "$PY tools/rag_inject/check_rows_alignment.py \"\$ENGINE\" \"\$STORE\""
# 反向：不许再退回"逐条各读一次全文"（`rowText`/`rowSource` 在 ragContext 里出现
# 就是旧写法的指纹 —— 它按命中顺序取，天然压紧）。
c "ragContext 不逐条取（旧写法会按命中顺序压紧下标）" \
  "! printf '%s' \"\$(\$PY tools/rag_inject/strip_comments.py \$ENGINE)\" | \$PY tools/rag_inject/_fnbody.py ragContext | grep -q 'RagIndexStore.rowText'"

# ── ⑤ 存储层：正文旁挂文件的读写 ────────────────────────────────────────
# ⚠⚠ 这一条本轮**反向**（ISSUE #259 第 ② 条）。旧判据要求 `drop` 里出现
#   `textsFile`（"删索引连正文一起删，不留没有向量的孤儿正文"）。而那个理由
#   早就全部失效：`list` 现在按"目录里有正文或索引"列库，所以"只有正文"的库
#   **照样在列表里**、状态是"待重建"，清掉它的入口有两个（重建索引 / 删库）。
#   于是"孤儿正文"根本不是孤儿 —— 它是这个库**唯一**的资产（向量可再生，正文不能）。
#   用户的原话：*"删索引为啥要删正文，是你按钮写错了还是逻辑写错了"*。**逻辑错了。**
#   现在 `drop` **只删索引**：它体内出现 `textsFile` 就说明有人又把正文删回去了。
c "drop **只删索引**，不碰正文（正文是唯一的、不可再生的那一半）" \
  "! printf '%s' \"\$(\$PY tools/rag_inject/strip_comments.py \$STORE)\" | \$PY tools/rag_inject/_fnbody.py drop | grep -q 'textsFile'"
# ⚠ 落正文的那个内部助手在阶段 2 从 `saveTexts` 改名成了 `writeTexts`
#   （`saveTexts` 现在是**对外**的"只保存正文"入口）—— 判据锚的是"save 里有没有
#   那一跳"而不是那个名字，所以这里跟着改名；名字写错会让它恒假（很快被人改成 || true）。
c "save 先落正文再落向量（避免\"有向量没正文\"的窗口）" \
  "printf '%s' \"\$(\$PY tools/rag_inject/strip_comments.py \$STORE)\" | \$PY tools/rag_inject/_fnbody.py save | grep -q 'writeTexts'"
c "rowText / rowSource 从同一行取（正文与来源不许分两次读）" \
  "grep -q 'fun rowText' \$STORE && grep -q 'fun rowSource' \$STORE && grep -q 'private fun row(' \$STORE"

# ── ⑥ UI 入口存在（不然用户无处建库 / 无处勾开关）────────────────────────
# ⚠ 锚的是**两个函数各司其职**（0.9.190 起把"问"与"做"拆开了）：
#   · `doBuildIndex` = 入口（校验 + 必要时的成本确认），必须走到 `startBuildIndex`；
#   · `startBuildIndex` = 真正开工，必须调 `LlmEngine.buildIndex`。
#   ⚠ 不能只判后者：那样"入口根本不接这个按钮"也能绿（症状=点了没反应）。
#   ⚠ 也不能只判前者：那样"开工那儿调的是别的函数"也能绿（建库实际没跑）。
c "UI 建库入口 doBuildIndex 走到开工点 startBuildIndex" \
  "printf '%s' \"\$(\$PY tools/rag_inject/strip_comments.py \$EA)\" | \$PY tools/rag_inject/_fnbody.py doBuildIndex | grep -q 'startBuildIndex'"
c "开工点 startBuildIndex 真的调 LlmEngine.buildIndex（不是只改了状态）" \
  "printf '%s' \"\$(\$PY tools/rag_inject/strip_comments.py \$EA)\" | \$PY tools/rag_inject/_fnbody.py startBuildIndex | grep -q 'LlmEngine.buildIndex'"
c "UI 有检索开关且写入持久化（RagInject.setEnabled）" \
  "grep -q 'RagInject.setEnabled' \$EA"
c "状态行读引擎真值（不是本页变量）" \
  "\$PY tools/rag_inject/check_status.py \$EA"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
