#!/bin/sh
# 「角色卡（V1）」的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠"能编译 + 手点一下"不够
# ═══════════════════════════════════════════════════════════════════════════
# 这一版的**代码量很小**（纯逻辑 + 一个文件 + 两处 UI），但它有三条
# "错得不响"的口径，而它们恰恰是当初决定"要做 V1"的全部理由：
#
#   ① **切卡不作废 KV 账本** —— `system` 在 pos 0，前缀是逐 token 比的。
#      换了 system 段，命中长度就是 0，不复用是**对**的；但那几 MB 白占着，
#      而且用户不知道"点一下卡片 = 整段历史重算"。这一条**没有任何症状**：
#      现象只是"手机烫了 / 变慢了"，与"模型就是慢"同形。
#   ② **会话只存卡片 id，不存内容快照** —— 卡片一删，那个会话的人设**静默没了**；
#      它照旧能打开、照旧能聊、照旧 200，只是"回答怪怪的"。这是整件事里
#      唯一不可归因的一条，也是"存快照"这个设计的全部理由。
#   ③ **改卡片顺手去改所有会话** —— 用户按下「保存修改」时并不知道自己正在改
#      N 个会话；而这是**不可恢复**的（旧内容在会话里没有第二份）。
#
# 判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 在哪个函数体内 / 顺序），
# 不锚"某某函数存在"—— 一个从不被调用的 `resetKvCache` 与没有它等价。
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_role_card_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
RC=$SRC/RoleCard.kt
RCS=$SRC/RoleCardStore.kt
SS=$SRC/SessionStore.kt
ACT=$SRC/EngineActivity.kt
TST=tools/role_card/RoleCardTest.kt
STST=tools/role_card/RoleCardStoreTest.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（大括号配平；与 run_session_cleanup_guard.sh 同一判据）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }
# 剥注释（判据文本在注释里也出现 —— 不剥会得到恒真的假绿）。
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

c "RoleCard.kt / RoleCardStore.kt / SessionStore.kt / EngineActivity.kt / 两份行为测试都存在" \
  "[ -f \$RC ] && [ -f \$RCS ] && [ -f \$SS ] && [ -f \$ACT ] && [ -f \$TST ] && [ -f \$STST ]"

# ── 1) 纯逻辑：卡片就是一段文本，没有别的任何东西 ───────────────────────
c "① RoleCard 是**无 android 依赖**的纯逻辑（宿主上可编可跑）" \
  "! grep -qE '^import android' \$RC"
# ⚠ `data class Card(...)` 的构造体里**没有大括号**，`body()`（靠大括号配平）
#    在第一行就 depth==0 退出，取到的是空 —— 于是这两条断言里靠 body 的那一半
#    恒真/恒假（第一版实测两条都错）。改用**行区间**：从声明行到第一个 `)`。
CARD=$(awk '/data class Card\(/{f=1} f{print} f&&/^\)/{exit}' "$RC")
c "① 卡片只有 id/name/system/时间四个字段（没有"分区"/"格式"那种 V2 的东西）" \
  "printf '%s' \"\$CARD\" | grep -q 'val system: String' && \
   printf '%s' \"\$CARD\" | nocomment | grep -q 'val id: String' && \
   ! grep -qE 'val (persona|scenario|greeting|example|lore|world)' \$RC"
# 上限是具名常量，且"能不能再加"的判断只有一处写点（两处各写一份，改上限就漂移）。
c "① 上限是具名常量 MAX_CARDS / MAX_CARD_CHARS" \
  "grep -q 'const val MAX_CARDS' \$RC && grep -q 'const val MAX_CARD_CHARS' \$RC"
c "① 「还能不能再加一张」只有 canAdd 一处判据（不是各处再写 < MAX_CARDS）" \
  "grep -q 'fun canAdd(current: Int): Boolean = current < MAX_CARDS' \$RC && \
   printf '%s' \"\$(body 'fun add\(ctx: Context' \$RCS)\" | nocomment | grep -q 'RoleCard.canAdd' && \
   ! printf '%s' \"\$(body 'fun add\(ctx: Context' \$RCS)\" | nocomment | grep -q 'MAX_CARDS'"
# 名字要规范化：它进的是**单行**下拉，带换行在那里看不见分隔。
c "① 名字走 normalizeName 折掉换行（下拉是单行控件）" \
  "printf '%s' \"\$(body 'fun normalizeName' \$RC)\" | nocomment | grep -q \"replace('\\\\\\\\n', ' ')\""
# 正文**不**折换行：那是用户写的结构，替他重排 = 静默改设定。
c "① 正文只 trim、**不**折换行（换行是用户写的结构）" \
  "grep -q 'fun normalizeSystem(raw: String): String = raw.trim()' \$RC"

# ── 2) ★ 快照优先：会话存内容，不存 id ──────────────────────────────────
c "② 会话结构里有 cardId **与** cardSnapshot 两个字段（缺一不可）" \
  "grep -q 'fun cardSnapshotOf(ctx: Context, id: String): String' \$SS && \
   grep -q 'fun cardIdOf(ctx: Context, id: String): String' \$SS"
c "② 挂卡写的是**快照内容**（setCard 的入参里带着正文，不是只写 id）" \
  "grep -q 'fun setCard(ctx: Context, id: String, cardId: String, cardSnapshot: String)' \$SS"
# ⚠ 这一条是整件事的核心：生效的 system 必须走 effectiveSystem（快照优先），
#   而不是"按 id 去卡片库里现取"。后者在卡片被删之后拿出空串 = 人设静默消失。
c "② 生效的 system 走 effectiveSystem（快照优先），不在别处另拼一份" \
  "grep -q 'fun effectiveSystem(cardSnapshot: String, typedSystem: String): String' \$RC && \
   printf '%s' \"\$(body 'private fun chatHistory\(sys: String\)' \$ACT)\" | nocomment | grep -q 'RoleCard.effectiveSystem'"
# 反向：不许"按 id 去库里现取 system" —— 那就是"卡片一删人设就没了"的写法。
# 锚 `chatHistory` 的函数体（这是喂给模型的唯一出口），不是全文件 grep。
c "② 喂模型那一处**不**按 id 去卡片库现取（那正是删卡丢人设的写法）" \
  "! printf '%s' \"\$(body 'private fun chatHistory\(sys: String\)' \$ACT)\" | nocomment | grep -qE 'RoleCardStore.get|RoleCardStore.load'"
c "② 快照为空 = 不用卡片：落回用户手打的 system（V1 之前行为逐字不变）" \
  "printf '%s' \"\$(body 'fun effectiveSystem' \$RC)\" | nocomment | grep -q 'if (cardSnapshot.isNotEmpty()) cardSnapshot else typedSystem'"

# ── 3) ★ 切卡必须作废 KV 账本（没有任何症状的那一条）────────────────────
# 判据锚"在**切卡这个函数里**、且在**写快照之前**调 resetKvCache"，
# 不是"文件里出现过 resetKvCache"（别的路径也在用它，那样恒真）。
PICK=$(body 'private fun pickCard\(position: Int\)' "$ACT")
c "③ pickCard() 存在（锚点未漂）" "[ -n \"\$PICK\" ]"
c "③ ★ 切卡作废 KV 账本（换了 system 段 = 前缀不再成立）" \
  "printf '%s' \"\$PICK\" | nocomment | grep -q 'LlmEngine.resetKvCache()'"
c "③ 作废排在**写快照之前**（顺序反了会出现"历史已换、旧前缀还在用"的那一帧）" \
  "printf '%s' \"\$PICK\" | nocomment | awk '/LlmEngine.resetKvCache\\(\\)/{r=NR} /SessionStore.setCard\\(/{w=NR} END{exit !(r>0 && w>r)}'"
# 与「清空对话」「删除会话」同一条出口：三处要作废的原因同源。
c "③ 与清空/删除走**同一个**出口（LlmEngine.resetKvCache，不是另找一条路）" \
  "[ \"\$(grep -c 'LlmEngine.resetKvCache()' \$ACT)\" -ge 3 ]"
# 用户必须**看得见**"这一下会丢缓存"：换 system 是低频手动作时不必说，
# 变成"点一下"之后频次上一个量级，不说就是"手机莫名发烫"。
c "③ 切卡之后**说出来**（否则症状是"点一下卡片手机就烫了"）" \
  "printf '%s' \"\$PICK\" | grep -q '丢弃 prompt 缓存'"
# 覆盖快照那条路径同样要作废（它也在换 system 段）。
c "③ 「用新内容覆盖本会话」也作废 KV（它同样换了 system 段）" \
  "printf '%s' \"\$(body 'private fun refreshCardSnapshot\(\)' \$ACT)\" | nocomment | grep -q 'LlmEngine.resetKvCache()'"

# ── 4) ★ 改卡片**不许**动任何会话 ───────────────────────────────────────
UPD=$(body 'fun update\(ctx: Context, id: String' "$RCS")
c "④ RoleCardStore.update() 存在（锚点未漂）" "[ -n \"\$UPD\" ]"
c "④ ★ 改卡片只写卡片库（不碰 SessionStore 的任何写入口）" \
  "! printf '%s' \"\$UPD\" | nocomment | grep -qE 'SessionStore|setCard|setSystem'"
c "④ 删卡片也只写卡片库（会话照旧用着快照）" \
  "! printf '%s' \"\$(body 'fun delete\(ctx: Context, id: String' \$RCS)\" | nocomment | grep -qE 'SessionStore'"
# 而这件事必须在界面上**说出来** —— 否则用户改完卡回聊天页发现"没生效"，
# 会以为没保存上，于是反复改、反复"没生效"。
# 判据锚"保存这张卡的动作里带出这句话"——不能全文件 grep，那样注释里那句
# （`cardHintTv` 建处也写着"没跟着变"）会让它恒真。
DLG=$(body 'private fun showCardDialog\(\)' "$ACT")
c "④ RoleCardStore / 卡片对话框锚点都在（防止下面两条靠空字符串恒真）" \
  "[ -n \"\$DLG\" ] && [ -n \"\$UPD\" ]"
c "④ 保存卡片时**明说**已挂卡的会话不会跟着变（不说用户会以为没存上）" \
  "printf '%s' \"\$DLG\" | grep -q '已挂卡的会话'"
c "④ 卡片改了/被删了这件事在聊天页**常驻可见**（不是 Toast 弹一次）" \
  "grep -q 'private fun cardHintText(cards: List<RoleCard.Card>, usedId: String, snapshot: String): String' \$ACT && \
   grep -q 'cardHintTv.visibility = if (cardHintTv.text.isEmpty()) View.GONE else View.VISIBLE' \$ACT"

# ── 5) 落盘：整体重写必须是"全成或全不成" ───────────────────────────────
SAVE=$(body 'fun save\(ctx: Context, cards: List<RoleCard.Card>\): Boolean' "$RCS")
c "⑤ save() 存在（锚点未漂）" "[ -n \"\$SAVE\" ]"
# 判据锚**两行的顺序**：先写 .tmp、再改名到正式文件。只 grep 'File(ctx.filesDir, TMP)'
# 在常量声明处就恒真。
c "⑤ ★ 走『先写 .tmp 再 rename』（写一半断电 = 整个卡片库变半截 JSON）" \
  "printf '%s' \"\$SAVE\" | nocomment | grep -q 'tmp.writeText' && \
   printf '%s' \"\$SAVE\" | nocomment | grep -q 'tmp.renameTo(file(ctx))'"
c "⑤ 写 TMP 排在 renameTo **之前**（反了就是"先毁旧的再写新的"）" \
  "printf '%s' \"\$SAVE\" | nocomment | awk '/tmp\\.writeText/{w=NR} /tmp\\.renameTo/{r=NR} END{exit !(w>0 && r>w)}'"
c "⑤ 写入失败返回 false（调用方必须处理，不许假装存上了）" \
  "printf '%s' \"\$SAVE\" | grep -q 'getOrElse'"
c "⑤ 读盘只认正式文件（.tmp 是中间态，不当成库）" \
  "printf '%s' \"\$(body 'fun load\(ctx: Context\)' \$RCS)\" | nocomment | grep -q 'file(ctx)' && \
   ! printf '%s' \"\$(body 'fun load\(ctx: Context\)' \$RCS)\" | nocomment | grep -q 'TMP'"

# ── 6) 读盘"尽力而为"，但三类毛病要分得开 ───────────────────────────────
PARSE=$(body 'fun parse\(text: String\): RoleCard.Loaded' "$RCS")
c "⑥ parse() 存在（锚点未漂）" "[ -n \"\$PARSE\" ]"
# ⚠ 判据锚"**这两类坏卡**（不是对象 / 校验不过）各自在同一行里既跳过又计数"，
#   不锚"文件里出现过 dropped++"——`parse` 有两处坏卡分支，只锚一处出现过
#   会让"另一处静默丢"的桩**假绿**（第一版实测：把 validate 那支的 `dropped++`
#   删掉，守卫照旧全绿 —— 命中的是 `o == null` 那一支）。
c "⑥ 两类坏卡**各自**跳过并计数（不是静默丢）" \
  "printf '%s' \"\$PARSE\" | grep -qF 'if (o == null) { dropped++; continue }' && \
   printf '%s' \"\$PARSE\" | grep -qF '{ dropped++; continue }' && \
   grep -q 'val dropped: Int' \$RC"
# 判据与 validate **同源**：两处各写一份，改上限时就漂移（存得进、读不出）。
c "⑥ 读侧用**同一个** validate 判坏卡（不是在这里另写一份判据）" \
  "printf '%s' \"\$PARSE\" | nocomment | grep -q 'RoleCard.validate(name, sys)'"
c "⑥ 顶到上限只读最近的、且**如实报**（不是静默砍掉最老的）" \
  "printf '%s' \"\$PARSE\" | nocomment | grep -q 'sortedByDescending { it.updatedAt }' && \
   printf '%s' \"\$PARSE\" | nocomment | grep -q 'CardFault.OVER_LIMIT'"
c "⑥ 「文件不在」与「文件坏了」分开（一个是常态，一个是数据出问题）" \
  "printf '%s' \"\$(body 'fun load\(ctx: Context\)' \$RCS)\" | nocomment | grep -q 'CardFault.NONE' && \
   printf '%s' \"\$(body 'fun load\(ctx: Context\)' \$RCS)\" | nocomment | grep -q 'CardFault.UNREADABLE'"
# ⚠ parse 必须是**纯逻辑**（宿主上能跑）：`android.util.Log` 在宿主下抛 Stub!，
#   在 parse 里调它就等于"最该被穷举的那几条判据测不了"。日志职责留在 load。
c "⑥ parse() 不碰 android.util.Log（否则宿主上跑不了，判据只能靠人看）" \
  "! printf '%s' \"\$PARSE\" | nocomment | grep -q 'Log\.'"

# ── 7) UI 接线：下拉的"填充那一拍"不许当成用户选择 ──────────────────────
c "⑦ 下拉的选中回调存在（锚点未漂）" \
  "grep -q 'cardSpinner.onItemSelectedListener' \$ACT"
# 这是本轮最容易踩的一脚：重填下拉会触发一次 onItemSelected(0)，
# 不挡住就等于"每次切会话都顺手把卡片摘掉"（静默、不可恢复）。
c "⑦ ★ 重填下拉期间吃掉框架触发的那一次选中（否则切会话会静默摘卡）" \
  "grep -q 'cardPickerFilling' \$ACT && \
   printf '%s' \"\$(body 'override fun onItemSelected' \$ACT)\" | grep -q 'if (cardPickerFilling) return'"
c "⑦ 下标 → id 走字典（下拉里比库里多一个"（不用卡片）"档，差一就挂错卡）" \
  "grep -q 'private var cardPickerItems: List<String>' \$ACT && \
   grep -q 'cardPickerItems.getOrNull(position)' \$ACT"
c "⑦ id 非空却在库里找不到时**不动**（挂一张不存在的卡 = 静默换成空人设）" \
  "printf '%s' \"\$PICK\" | nocomment | grep -q 'if (id.isNotEmpty() && card == null) return'"
c "⑦ 卡片行与标题是**同一个渲染点**（分开刷会出现"标题换了、卡还是上一条的"）" \
  "printf '%s' \"\$(body 'private fun refreshSessionTitle\(\)' \$ACT)\" | nocomment | grep -q 'refreshCardRow()'"
# 状态行点一下要**做它说的那件事**：卡片还在 = 覆盖快照；卡片没了 = 去重挂一张。
c "⑦ 状态行点击按当前状态分派（卡片没了还去"覆盖"就是点一下没反应）" \
  "grep -q 'RoleCardStore.get(this@EngineActivity, id) == null) showCardDialog()' \$ACT"

# ── 8) 卡片库不在会话配额里，但必须被文档记到 ───────────────────────────
# 判据锚"usedChars 统计的是 messages 正文"：卡片库要是不小心被算进配额，
# 用户会看到"删了卡片腾出空间"这种没人能解释的行为。
c "⑧ 会话配额仍然只数 messages 正文（卡片库不进配额）" \
  "printf '%s' \"\$(body 'fun usedChars\(ctx: Context\): Long' \$SS)\" | nocomment | grep -q 'optJSONArray(\"messages\")'"
c "⑧ 卡片库的路径 `cards.json` 在 README 的「数据存放位置」表里（否则没人知道它占磁盘）" \
  "grep -q 'cards.json' README.md"

# ── 9) ★ 导出件要写**实际生效的** system，不是手打的那一段 ───────────────
# 导出是用户**唯一**能审计"到底喂了什么"的出口：聊天页只看气泡（system 那一条
# 被 renderHistory 跳过），prompt 也不进日志。导出把生效的人设漏掉，用户就会得到
# "卡片内容根本没注入对话"这个**错误结论**（而 prompt 里确实有）。
# 判据锚"exportOne 里 system = ... 过 effectiveSystem"，不是"文件里出现过
# effectiveSystem"（chatHistory 那一处会让它恒真）。
EXP=$(body 'fun exportOne\(ctx: Context, id: String\)' "$SS")
c "⑨ exportOne() 存在（锚点未漂）" "[ -n \"\$EXP\" ]"
c "⑨ ★ 导出写的是**生效的** system（快照优先），与喂模型同一判据" \
  "printf '%s' \"\$EXP\" | nocomment | grep -q 'RoleCard.effectiveSystem'"
# 反向：不许退回"只写手打的 system" —— 那正是"卡片内容在导出里看不见"的写法。
# 用单引号包 `o.optString(...)` 片段，避开 `!` 与内层双引号的转义（第一版这里
# 转义没配平，整份守卫在解析阶段就死于 `Syntax error`）。
# 判据锚"exportOne 里没有 `system = o.optString(...)` 这一写法"——用不含引号的
# 片段 `system = o.optString` 做判据，避开 shell 里三层引号的转义（第一版用
# `&quot;` 占位，结果它匹配的是实体本身、恒不命中，反向那条**恒绿**）。
c "⑨ ★ 导出不直接写手打的 system（那会让卡片内容在导出里消失）" \
  "! printf '%s' \"\$EXP\" | nocomment | grep -q 'system = o.optString'"

echo ""
if [ "$bad" = "0" ]; then echo "=== 角色卡（V1）守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 角色卡（V1）守卫：PASS $ok / FAIL $bad ==="; exit 1
