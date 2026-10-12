#!/bin/sh
# 「会话列表：搜索 + 按时间批量清理」的接线守卫（S4）。
#
# ═══════════════════════════════════════════════════════════════════════════
# S3 把上限从「个数」换成「正文总字符」之后，`MAX_SESSIONS_WARN = 200` 只提示
# 不拦人 —— 于是用户面对两三百条会话，而会话管理只有一个 `AlertDialog.setItems`：
# 没有搜索、没有批量删。**上限放开了，用户却没有出路**。S4 补这条出路，
# 而它有三条坏法全都是"错得不响"：
#
#   · **搜索渗进删除取数** —— 搜索框只该决定"屏幕上显示哪几行"。一旦删除/
#     导出也走筛过的那一份，用户在搜索状态下删掉的是"看起来只有三条"里的，
#     而他其它会话在同一次里被误伤。症状是"我的会话不见了"，与手动删同形。
#   · **批量删默认勾好了候选** —— 预勾选 = 用户按下"删除"时以为在清垃圾，
#     实际带走的是他 30 天前认真写的那一条。默认必须**一个都不勾**。
#   · **删完不作废 KV 账本** —— 这一条**没有任何可观测的症状**（复用的判据
#     是逐 token 比前缀，分叉了自然不复用），只是那一轮模型会拿着别人的
#     上下文接着往下写。看不见的收益，也就看不见的丢失。
#
# 判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 在哪个函数体内），
# 不锚存在性：`fun filter(...)` 光"存在"什么都证明不了 —— 一个从不被
# 列表用的过滤函数与没有它等价。
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_session_cleanup_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
SC=$SRC/SessionCleanup.kt
ACT=$SRC/EngineActivity.kt
SS=$SRC/SessionStore.kt
TST=tools/session_cleanup/SessionCleanupTest.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（与 run_session_quota_guard.sh 的 body() 同一判据）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }

# 剥注释（判据文本在注释里也出现 —— 本文件与源码的注释为了讲清"为什么"
# 必然引用它们，不剥会得到恒真的假绿）。
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

c "SessionCleanup.kt / EngineActivity.kt / SessionStore.kt / 行为测试都存在" \
  "[ -f \$SC ] && [ -f \$ACT ] && [ -f \$SS ] && [ -f \$TST ]"

# ── 1) 纯逻辑：天数换算与候选判据只有一处写点 ─────────────────────────────
c "① 天 → 毫秒的换算有具名常量 DAY_MS（只有一个写点）" \
  "grep -q 'const val DAY_MS' \$SC"
c "① 候选判据 olderThan() 存在且用 DAY_MS（不是各处再乘 86400000）" \
  "grep -q 'fun olderThan(infos: List<SessionStore.Info>, now: Long, days: Int): List<SessionStore.Info>' \$SC && \
   printf '%s' \"\$(body 'fun olderThan' \$SC)\" | nocomment | grep -q 'DAY_MS'"
c "① olderThan 的时间来源是**入参 now**，不在内部读系统时钟（边界可在宿主穷举）" \
  "! printf '%s' \"\$(body 'fun olderThan' \$SC)\" | nocomment | grep -q 'System.currentTimeMillis'"
c "① 筛选只按**标题**（不读正文，列表页不因搜索去翻库文件）" \
  "printf '%s' \"\$(body 'fun filter' \$SC)\" | grep -q 'it.title.contains(k, ignoreCase = true)'"
c "① 空关键字 = 不筛（返回原列表，界面上不额外给"全部"档位）" \
  "printf '%s' \"\$(body 'fun filter' \$SC)\" | grep -q 'if (k.isEmpty()) return infos'"
c "① 天数档位是固定三档（Day_CHOICES 常量，不是让用户填数字）" \
  "grep -q 'val DAY_CHOICES' \$SC"

# ── 2) 「不误删」的三条闸 ────────────────────────────────────────────────
CD=$(body 'fun canDelete\(selected: Set<String>, existing: Set<String>\): Boolean' "$SC")
c "② canDelete() 存在（批量删动的唯一判据）" "[ -n \"\$CD\" ]"
c "② 空集合一律拒绝（不做"一个都没勾就顺手清掉列出来的那些"）" \
  "printf '%s' \"\$CD\" | nocomment | grep -q 'selected.isNotEmpty()'"
c "② 勾选要**真的**落在库里的 id 上（不是"勾了就删"，中途被删的不算）" \
  "printf '%s' \"\$CD\" | nocomment | grep -q 'selected.any { existing.contains(it) }'"
c "② 删除按勾选与库的**交集**动手（走 resolve，不直接删 selected）" \
  "printf '%s' \"\$(body 'private fun showCleanupDialog' \$ACT)\" | nocomment | grep -q 'SessionCleanup.resolve(infos, selected)' && \
   ! printf '%s' \"\$(body 'private fun deleteSessions' \$ACT)\" | nocomment | grep -q 'selected'"
c "② UI 用 canDelete 决定「删除」按钮的可用性（默认全不勾时它是灰的）" \
  "grep -q 'isEnabled = SessionCleanup.canDelete(selected, candidates.map { it.id }.toSet())' \$ACT"
# 存在性断言（"有个 selected 集合"）对**预勾选**桩是恒真的：桩会在集合之外
# 再加一个把候选全塞进去的函数，集合照样在。真正要钉的是**往集合里加的时机** ——
# 预勾选的定义就是"进对话框（重建候选）那一拍就把 id 塞进去了"。
# 判据：`selected.add` 只允许出现在**两个用户手势**里 —— 行点击、或显式勾"全选本档"。
ADD_SITES=$(printf '%s' "$(body 'private fun showCleanupDialog' "$ACT")" | nocomment | grep -c 'selected.add(' || true)
c "② 勾选态存在**集合**里，且加点只来自用户手势（不是进对话框就预勾）" \
  "grep -q 'val selected = linkedSetOf<String>()' \$ACT && \
   [ \"\$ADD_SITES\" -le 2 ] && \
   grep -q 'check.setOnClickListener' \$ACT"
c "② 候选列表排除**当前会话**（正在看的那条不该出现在待清理清单里）" \
  "grep -q '.filter { it.id != currentSessionId }' \$ACT"

# ── 3) 搜索只影响**显示**，删除/导出一律取全库 ────────────────────────────
MS=$(body 'private fun makeSessionListView\(infos: List<SessionStore.Info>\): View' "$ACT")
c "③ makeSessionListView() 存在（搜索框与列表的唯一归属）" "[ -n \"\$MS\" ]"
c "③ 搜索框每次变化都**重新取全库**再筛（不是把打开时的快照过滤两遍）" \
  "printf '%s' \"\$MS\" | nocomment | grep -q 'val fresh = SessionStore.list(this@EngineActivity)' && \
   printf '%s' \"\$MS\" | nocomment | grep -q 'adapter.rows = SessionCleanup.filter(fresh, '"
# 判据锚**取数的接收者**：点击要落到 `adapter.rows`（屏幕上那一行）上，
# 而不是重新 `SessionStore.list(...)` 再按下标取 —— 后者在筛过之后就是
# "点第 3 行打开第 7 个会话"。存在性断言（"文件里有 adapter.rows"）对回退桩恒真。
c "③ 点击拿的是**屏幕上行**对应的 Info（adapter.rows），不重新按全库下标取" \
  "printf '%s' \"\$MS\" | nocomment | grep -q 'val picked = adapter.rows.getOrNull(pos)' && \
   printf '%s' \"\$MS\" | nocomment | grep -q 'switchSession(picked.id)' && \
   ! printf '%s' \"\$MS\" | nocomment | grep -qE 'SessionStore.list\\(this\\)\\.getOrNull|SessionStore.list\\(this\\)\\[|fresh\\['"
# 关键反例：搜索/筛选的结果**不得**出现在删除路径上。
c "③ 批量删的候选是从**全库**取的（olderThan 的第一参是 fresh/list，不是 rows）" \
  "printf '%s' \"\$(body 'private fun showCleanupDialog' \$ACT)\" | nocomment | grep -q 'SessionCleanup.olderThan(fresh, now, '"
# 判据锚"取数发生在**删除按钮的点击回调**里"，而不是"这一行出现在某处" ——
# 删除按钮从 `setPositiveButton("删除")` 挪进视图之后（框架 `AlertDialog` 的
# `BUTTON_NEUTRAL` 在运行时取不到按钮，见 [showCleanupDialog] 那段注释），
# 重取全库那两行的接收者换了名字，但它们要证的不变量没变：**点删除的那一刻
# 重新从库里取数**，不拿屏幕上那份筛过的候选清单动手。
c "③ 删除时重取全库再取交集（不是拿候选清单直接删）" \
  "printf '%s' \"\$(body 'private fun showCleanupDialog' \$ACT)\" | nocomment | grep -q 'val infos = SessionStore.list(this@EngineActivity)' && \
   printf '%s' \"\$(body 'private fun showCleanupDialog' \$ACT)\" | nocomment | grep -q 'SessionCleanup.resolve(infos, selected)'"
c "③ 导出仍走 exportSessions（不读搜索框）—— 导出不受任何界面状态影响" \
  "printf '%s' \"\$(body 'private fun exportSessions' \$ACT)\" | nocomment | grep -q 'SessionStore.exportAll(this)' && \
   ! printf '%s' \"\$(body 'private fun exportSessions' \$ACT)\" | nocomment | grep -q 'adapter.rows'"

# ── 3.5) 批量清理的「删除」按钮：视图自有，不靠对话框回手同步 ──────────────
# 这是本轮修掉的**闪退根因**：壳是建在 `box` 之后、靠 `dialogRef` 回手同步按钮可用性，
# 而框架 `android.app.AlertDialog`（本仓库零第三方依赖）的 `BUTTON_NEUTRAL`
# 是后来插入的常量，运行时 `getButton(...)` 给回 null —— `btn?.isEnabled` 静默失效
# （删除按钮永远亮着，"不误删"的闸门被漏过），`btn.isEnabled` 直接 NPE 闪退。
# 判据：可用性的**接收者**是视图里的那个按钮，且这条路径上不许再出现 `getButton`。
CDL=$(body 'private fun showCleanupDialog' "$ACT")
c "③.5 删除按钮的可用性写在视图按钮上（delBtn.isEnabled）" \
  "printf '%s' \"\$CDL\" | nocomment | grep -q 'delBtn.isEnabled = SessionCleanup.canDelete('"
c "③.5 不再问对话框要按钮（getButton 在这条路径上是个坑，不许出现）" \
  "! printf '%s' \"\$CDL\" | nocomment | grep -q 'getButton'"
# 判据只锚「`delBtn` 被赋成一个视图按钮」，不锚 `val` 还是 `=` ——
# 这一条钉的是"按钮**在视图里**、不占对话框的按钮位"，`val delBtn = ...` 与
# `delBtn = ...` 在这件事上等价。写死 `val ` 会让"消掉与 `lateinit var` 的
# 重复声明"这种纯修 bug 的改动把守卫弄红（本轮就是这么红的），
# 而它要防的 `setPositiveButton("删除")` 并没有因此回来。
c "③.5 「删除」是视图里的自有按钮（不用 setPositiveButton 占按钮位）" \
  "grep -qE '(val )?delBtn = Button\\(this\\)' \$ACT && \
   ! printf '%s' \"\$CDL\" | nocomment | grep -q 'setPositiveButton(\"删除\")'"
c "③.5 建对话框**之前**先算好候选与按钮可用性（不依赖 show() 之后的回手同步）" \
  "printf '%s' \"\$CDL\" | nocomment | grep -q 'box.addView(delBtn'"
c "③.5 删除按钮默认是灰的（canDelete 的口径：一个都没勾就不许删）" \
  "printf '%s' \"\$CDL\" | nocomment | grep -q 'isEnabled = false'"

# ── 4) 删完的收场：作废 KV 账本 + 按全库重画 ──────────────────────────────
DS=$(body 'private fun deleteSessions\(targets: List<SessionStore.Info>\)' "$ACT")
c "④ deleteSessions() 存在（批量删的唯一出口）" "[ -n \"\$DS\" ]"
c "④ 删会话**要**作废 KV 账本（历史被换掉后旧前缀不再成立）" \
  "printf '%s' \"\$DS\" | nocomment | grep -q 'LlmEngine.resetKvCache()'"
c "④ 删完再取一次当前 id（删掉的是当前会话时界面不能还挂着旧历史）" \
  "printf '%s' \"\$DS\" | nocomment | grep -q 'SessionStore.currentId(this)'"
c "④ 删完按**全库**重画列表（不是把刚删的从显示里抹掉）" \
  "printf '%s' \"\$DS\" | nocomment | grep -q 'showSessionDialog()'"
# 反向：删**单条消息**那条路径**不得**动 KV（它删的是尾巴，plan_reuse 自己会砍）。
# 判据先剥注释 —— 源码注释里本身就在讲 resetKvCache 这件事。
c "④ 删单条消息的路径**不**碰 KV（与本轮的批量删方向相反，别互相串了）" \
  "! printf '%s' \"\$(body 'private fun confirmDeleteMessage\(index: Int\)' \$ACT)\" | nocomment | grep -q 'resetKvCache'"

# ── 4.5) 「导入」必须有个看得见的入口（本轮用户的原始诉求）─────────────────
# 上一版把「导入记录文件」埋在**长按会话项**的菜单里 —— 而导入是唯一一个
# **不针对某个会话**的动作，却要求先长按某一个会话才会出现（用户原话：
# "只能点击对话列表才弹出，不直观"）。现在首访入口在会话管理对话框上，
# 与它的对偶「导出全部」在同一层；长按菜单那一项保留，两处都指向同一个实现。
SD=$(body 'private fun showSessionDialog\(\)' "$ACT")
c "④.5 会话管理上有「导入」按钮（不是只活在长按菜单里）" \
  "printf '%s' \"\$SD\" | nocomment | grep -q 'setPositiveButton(\"导入\")'"
c "④.5 导入与导出在同一层可见（导出的对偶就摆在它旁边）" \
  "printf '%s' \"\$SD\" | nocomment | grep -q 'exportSessions(all = true)' && \
   printf '%s' \"\$SD\" | nocomment | grep -q 'pickArchiveFile()'"
c "④.5 两个入口指向**同一份**导入逻辑（不在 UI 里再写一遍）" \
  "printf '%s' \"\$(body 'private fun showSessionActions' \$ACT)\" | nocomment | grep -q 'pickArchiveFile()' && \
   ! printf '%s' \"\$SD\" | nocomment | grep -qE 'ChatArchive|openInputStream'"

# ── 5) 生成中不得清库（与其他会话操作同一条闸门）─────────────────────────
c "⑤ 批量清理对话框在生成中直接拒绝（不与其他会话操作分叉）" \
  "printf '%s' \"\$(body 'private fun showCleanupDialog' \$ACT)\" | grep -q 'if (generating)'"
c "⑤ 入口挂在长按菜单里（与删除同层，需先确认）" \
  "grep -q '🧹 批量清理…' \$ACT && \
   printf '%s' \"\$(body 'private fun showSessionActions' \$ACT)\" | grep -q 'showCleanupDialog()'"

# ── 6) 纯逻辑文件不带 android 依赖（否则宿主行为测试编不出来）───────────
c "⑥ SessionCleanup.kt 不 import android（可在宿主上直接单测）" \
  "! nocomment < \$SC | grep -q 'import android'"

# ── 7) 行为测试与实现不得脱钩 ────────────────────────────────────────────
c "⑦ 行为测试钉住「恰好第 N 天算旧」这条闭区间边界" \
  "grep -q '恰好第 7 天：算旧' \$TST && grep -q 'NOW - 7L \\* DAY' \$TST"
c "⑦ 行为测试钉住「空关键字 = 不筛」" \
  "grep -q '空关键字：不筛' \$TST"
c "⑦ 行为测试钉住「大小写不敏感的子串」" \
  "grep -q '大小写不敏感' \$TST"
c "⑦ 行为测试钉住「一个都没勾就不许删」" \
  "grep -q '一个都没勾：拒绝' \$TST && grep -q 'canDelete(emptySet(), setOf(\"a\", \"b\"))' \$TST"
c "⑦ 行为测试用真实现断言（SessionCleanup 无 android 依赖，不须复刻）" \
  "grep -q 'SessionCleanup' \$TST"
# 按钮可用性取自 canDelete —— 上一版它靠对话框回手同步，那段判据从来没跑到过。
c "⑦ 行为测试钉住「删除按钮的可用性 = canDelete」" \
  "grep -q '按钮可用性 = canDelete' \$TST"

# ── 8) C6：会话线 UI 入口的三条"错得不响" ────────────────────────────────
# 这三条都不是崩溃，而是"界面说的与库里的对不上"——
# 判据一律锚**关系**（谁在哪个函数体里被回写成什么），不锚存在性。

# 8.1 「全选本档」复选框必须跟着当前档位重算。
#   反例：切档后复选框保持上次的勾选态，而 candidates 已经换了一档 ——
#   用户看到"全选"勾着，以为新档位也一并选中，实际 selected 里一条新的都没有。
CD=$(body 'private fun showCleanupDialog' "$ACT")
c "⑧ 切档重算候选时，同步回写「全选本档」的勾选态（不是让它照旧挂着）" \
  "printf '%s' \"\$CD\" | nocomment | grep -q 'check.isChecked = candidates.isNotEmpty() && candidates.all { selected.contains(it.id) }'"
# 反向：回写必须发生在 rebuild（候选重算）那条路径里，不是别处顺手写的。
c "⑧ 这行回写在 rebuild() 内（跟 candidates 的重算同一个函数体）" \
  "printf '%s' \"\$(body 'fun rebuild\\(\\)' \$ACT)\" | nocomment | grep -q 'check.isChecked = candidates.isNotEmpty()'"

# 8.2 搜索"无匹配"必须是**常驻空态行**，不是每次按键弹一次 Toast。
#   输入是连续动作（"会议记录"按 4 次），逐次弹 Toast 会糊屏并挡住刚要出现的匹配行。
MS=$(body 'private fun makeSessionListView\(infos: List<SessionStore.Info>\): View' "$ACT")
c "⑧ 搜索无匹配用常驻空态行（emptyTv），不是 Toast" \
  "printf '%s' \"\$MS\" | nocomment | grep -q 'emptyTv.visibility = if (adapter.rows.isEmpty()'"
c "⑧ 空态只在**关键字非空**时出现（还没输不算"无匹配"）" \
  "printf '%s' \"\$MS\" | nocomment | grep -q 'adapter.rows.isEmpty() && kw.isNotBlank()'"
# 关键反例：`makeSessionListView`（搜索框与列表的唯一归属）里**不得**再出现
# Toast。锚整个函数体而不是 `afterTextChanged` —— 后者在别处（RAG 文档加载）
# 还有一个同名回调，锚名字会检到那一份、恒真。
c "⑧ 会话列表视图里不得有 Toast（改了弹一次 = 输入四个字弹四次）" \
  "! printf '%s' \"\$MS\" | nocomment | grep -q 'Toast.makeText'"

# 8.3 删除会话后的重画**只做一次**，且在库改完（收场）之后。
#   反例：`if` 之前先 showSessionDialog()、末尾又 showSessionDialog() ——
#   删非当前会话时同一屏被 dismiss 再建两遍；删当前会话时第一次建的还会被
#   doCreateSession() 再 dismiss，对话框自己灭一下又冒出来。
CDS=$(body 'private fun confirmDeleteSession\(info: SessionStore.Info\)' "$ACT")
c "⑧ confirmDeleteSession 里 showSessionDialog() 只有一处（不是两处）" \
  "[ \"\$(printf '%s' \"\$CDS\" | nocomment | grep -c 'showSessionDialog()')\" -eq 1 ]"
c "⑧ 唯一那次重画排在 SessionStore.delete 之后（库先改、屏后刷）" \
  "printf '%s' \"\$CDS\" | nocomment | awk '/SessionStore.delete/{d=NR} /showSessionDialog\(\)/{s=NR} END{exit !(d>0 && s>d)}'"

# 8.4 聊天页输入条与上方滚动区之间的分隔线，与输入条同进同退。
c "⑧ 输入条分隔线在切页处与 chatBar 同步显隐（否则留一条划痕在那页）" \
  "grep -q 'chatBarDivider?.visibility = if (i == 0) View.VISIBLE else View.GONE' \$ACT"
c "⑧ 分隔线只在聊天页（建的时候就按 curTab 定初值）" \
  "grep -q 'chatBarDivider = View(this).apply' \$ACT"

# ── 9) 会话管理列表：对号跟着**当前会话**走，切换之后这一层不许停原处 ───────
#
# 用户报的那条：「切换会话时，会话前的对号不会跟随选择的会话进行变化」。
# 根因是 `getView` 直接读 `currentSessionId` —— 而适配器**不会被通知**它变了，
# 这一屏在切完之后一次都不会重建，于是对号钉死在"打开对话框那一刻"的那一条上。
#
# 这一段的判据全都锚**这个判据住在谁身上**，不锚"源码里出现过 ✓"：
# 后者在常量、注释、别处文案里都恒真。
MS=$(body 'private fun makeSessionListView\(infos: List<SessionStore.Info>\): View' "$ACT")
c "⑨ makeSessionListView 存在（锚点未漂）" "[ -n \"\$MS\" ]"

# ① 适配器必须**自持**一个"现在该打勾的是谁"，初值取当前会话。
c "⑨ 适配器自持 selected 状态（初值 = 当前会话）" \
  "printf '%s' \"\$MS\" | grep -q 'var selected: String = currentSessionId'"
# ② 画行的那一行必须把这**两个**入参都交给行内容函数 —— 少一个就是"标记不由
#    selected 决定"，而那正是要把对号钉回 currentSessionId 的写法。
c "⑨ 行内容由 (行, selected) 两个入参决定（纯函数，可在宿主穷举）" \
  "printf '%s' \"\$MS\" | grep -q 'fun label(i: SessionStore.Info, selectedId: String): String' && \
   printf '%s' \"\$MS\" | grep -q 'tv.text = label(rows\[position\], selected)'"
# ③ 行内容函数里比的是**入参**，不是 `currentSessionId`。
#    ⚠ 锚 `label` 的函数体：全文件 grep `currentSessionId` 到处都是（刷新标题、
#    回写 system、初始加载…），恒真。
LBL=$(body 'fun label\(i: SessionStore.Info, selectedId: String\): String' "$ACT")
c "⑨ 打勾的判据是入参 selectedId（不是直接读 currentSessionId）" \
  "printf '%s' \"\$LBL\" | nocomment | grep -q 'if (i.id == selectedId) append(\"✓ \")' && \
   ! printf '%s' \"\$LBL\" | nocomment | grep -q 'currentSessionId'"

# ④ 切换那条路径必须**同时**做两件事：更新 selected、并让这一屏重画。
#    只做其中一件就是用户报的那个现象（一件都不做）或其变体（改了状态不重画，
#    要等滚动才看见）。
CLK=$(printf '%s' "$MS" | nocomment | grep -A6 'lv.setOnItemClickListener')
c "⑨ 点击之后把 selected 调成刚选的那一条（不是留着旧的）" \
  "printf '%s' \"\$CLK\" | grep -q 'adapter.selected = picked.id'"
c "⑨ 点击之后**重画这一屏**（改状态不重画 = 要等滚动才对，仍是错的）" \
  "printf '%s' \"\$CLK\" | grep -q 'adapter.notifyDataSetChanged()'"
# ⑤ 切换失败（生成中 / 已被别处删掉）不许先把对号挪走 —— 那会画出"已经切过去了"
#    而其实什么都没变。判据锚"switchSession 的返回值被用来决定要不要继续"。
c "⑨ switchSession 返回『有没有真的切过去』（调用方据此决定改不改这一屏）" \
  "grep -q 'private fun switchSession(id: String): Boolean' \$ACT"
c "⑨ 切失败就直接返回（不许先把 selected 挪到那条并不存在的会话上）" \
  "printf '%s' \"\$CLK\" | grep -q 'if (!switchSession(picked.id)) return@setOnItemClickListener'"
# ⑥ 切成功之后**关掉这一层**：留着它就是留着一个"标题取自打开那一刻快照、
#    而库里可能已经被新建/删除改过"的列表（见 setOnItemClickListener 那段注释）。
c "⑨ 切成功之后关掉这一层（不留一个标题已经过期的列表在屏幕上）" \
  "printf '%s' \"\$CLK\" | grep -q 'showSessionDialogDismiss()'"
# ⑦ 关闭必须排在**切换之后**：反过来会先 dismiss 再切，谁也说不清这一屏还活着没有。
c "⑨ 关闭排在切换之后（顺序反了等于先毁屏再改状态）" \
  "printf '%s' \"\$CLK\" | awk '/switchSession\\(picked.id\\)/{s=NR} /showSessionDialogDismiss\\(\\)/{d=NR} END{exit !(s>0 && d>s)}'"

echo
echo "=== 会话列表清理守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
