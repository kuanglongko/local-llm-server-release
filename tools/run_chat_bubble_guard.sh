#!/bin/sh
# 「聊天页气泡化 + 单条删除」的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠"能编译"不够
# ═══════════════════════════════════════════════════════════════════════════
# 这一批改动里有三条**零症状**的不变量，全都"编译过、点得动、不报错"：
#
#   ① 流式**只改那一条**的文本，不得重建整屏气泡。
#      退回 `renderChat(sb)` 照样能出字、看不出错 —— 代价是长会话每来一个 token
#      就 inflate 几十个 View，只在"会话很长 + 生成很慢"时表现为卡顿，
#      而那时候没人会想到是渲染方式的问题。
#   ② 长按删除的**下标必须当场反查**（`chatBubbles.indexOf`），不能在闭包里捕获。
#      捕获值的失效窗口是"列表被重绘过一次" —— 而删除/切换会话/清空都会重绘。
#      表现是"删掉的不是我长按的那一条"，且不可恢复。
#   ③ 删除 / 清空之后必须**作废 KV 账本**。
#      不作废也不会读错历史（复用判据是逐 token 比前缀，分叉了自然不复用），
#      所以它**没有任何可观测的症状** —— 只是那几 MB KV 白占显存。
#      这正是"必须靠断言钉住"的那一类：看不见的收益，也就看不见的丢失。
#
# 判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 顺序），不锚"某个函数存在"。
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_chat_bubble_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
SS=$SRC/SessionStore.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（与 run_ui_io_guard.sh 的 body() 同一判据，
# 两处各写一份取函数体的近似就是"判据漂移"的起点）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }
# 剥注释（本文件里判据文本在注释中也出现，不剥会得到恒真的假绿）
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

c "EngineActivity.kt / SessionStore.kt 存在" "[ -f \$ACT ] && [ -f \$SS ]"

# ── 1) 渲染：气泡是"按条建 View"，不再是"一个 TextView 拼全部" ─────────────
c "聊天记录容器是 LinearLayout（按条 addView 的容器）" \
  "grep -q 'private lateinit var chatContainer: LinearLayout' \$ACT"
c "旧的「一个 TextView 拼全部」已绝迹（outTv 不再存在）" \
  "! grep -q 'outTv' \$ACT"
c "有按条建气泡的函数 addBubble（返回正文 View）" \
  "grep -q 'private fun addBubble(role: String, text: String, mutable: Boolean): TextView' \$ACT"
c "角色标签与正文分开（不再把「我：」「AI：」拼进正文）" \
  "grep -q 'private fun bubbleHeader(role: String): TextView' \$ACT"
RB=$(body 'private fun renderHistory\(\)' "$ACT")
c "renderHistory() 存在（锚点未漂）" "[ -n \"\$RB\" ]"
c "重建前先清空容器与索引（否则列表会叠着长）" \
  "printf '%s' \"\$RB\" | grep -q 'chatContainer.removeAllViews()' && \
   printf '%s' \"\$RB\" | grep -q 'chatBubbles.clear()'"
c "renderHistory 里**没有** import 级别的整屏 setText（一条一条加）" \
  "printf '%s' \"\$RB\" | grep -q 'addBubble(m.first, m.second, mutable = false)'"

# ── 2) 流式：只改那一条，不重建整屏 ────────────────────────────────────────
# 判据是**调用关系**：生成循环里出现的必须是 setStreamingText，且它只写
# streamingBody 的 text —— 不是"文件里存在 setStreamingText"（那恒真）。
# 生成循环的归属函数是 startGeneration()（doGenerate 只做"落盘这一句 + 交给它"，
# 「重新生成」也走同一个入口）。判据跟着循环走：锚在 doGenerate 上会变成"钉一个没有
# 循环的函数"，于是 setStreamingText 与 renderChat 怎么变都判不出来 —— 正是"锚点漂移"。
GEN=$(body 'private fun startGeneration\(messages: List<Pair<String, String>>\)' "$ACT")
c "startGeneration() 存在（生成循环的唯一归属）" "[ -n \"\$GEN\" ]"
c "生成循环里走的是 setStreamingText（不是 renderChat 重绘整屏）" \
  "printf '%s' \"\$GEN\" | nocomment | grep -q 'ui.post { setStreamingText(ps) }'"
c "生成循环里不再有「每 token 重建整屏」的调用" \
  "! printf '%s' \"\$GEN\" | nocomment | grep -qE 'renderChat\(sb|renderChat\(ps'"
ST=$(body 'private fun setStreamingText\(text: String\)' "$ACT")
c "setStreamingText() 存在（锚点未漂）" "[ -n \"\$ST\" ]"
c "它只改那一条的 text（不碰容器、不重建）" \
  "printf '%s' \"\$ST\" | grep -q 'streamingBody?.text = text'"
c "它不重建整屏（不得出现 removeAllViews / 再 render）" \
  "! printf '%s' \"\$ST\" | grep -qE 'removeAllViews|renderChat'"
# 可变气泡不得混进可删除索引：它随时会被收尾/取消丢弃，那时索引就悬空了。
c "可变气泡不进 chatBubbles（mutable=true 时不入索引）" \
  "grep -q 'if (!mutable) chatBubbles.add(wrap)' \$ACT"

# ── 3) 删除：下标当场反查 + 真的接上存储层 ─────────────────────────────────
AB=$(body 'private fun addBubble\(role: String, text: String, mutable: Boolean\)' "$ACT")
c "addBubble() 存在（锚点未漂）" "[ -n \"\$AB\" ]"
c "长按监听挂在气泡容器上（不是挂在正文 TextView 上：文字不长按也要能删）" \
  "printf '%s' \"\$AB\" | grep -q 'wrap.setOnLongClickListener'"
c "长按的目标可点（isLongClickable 显式置位，否则会被正文 TextView 吃掉）" \
  "printf '%s' \"\$AB\" | grep -q 'wrap.isLongClickable = true'"
# 这两条是**真机实测踩出来的**，不是推演出来的：
#   · 「页面看不到气泡，只是对话布局有变化」= 气泡没有可见形状，只有缩进；
#   · 「长按 AI 的输出或我的输入都没反应」= 正文可选中，把长按吃掉了。
# 前后两条对应"这个 bug 的原因必须已经被移除"，而不是"新加了什么"。
c "正文**不**可选中（可选中会吃掉长按 —— 症状正是「长按没反应」）" \
  "! printf '%s' \"\$AB\" | nocomment | grep -q 'setTextIsSelectable(true)'"
c "气泡本体有可见形状（圆角 + 底色），不是只有缩进" \
  "printf '%s' \"\$AB\" | grep -q 'background = bubbleBackground(isUser)' && \
   grep -q 'private fun bubbleBackground(isUser: Boolean): GradientDrawable' \$ACT"
c "长按菜单里保留了复制出口（正文不可选中后，复制只能从这儿走）" \
  "printf '%s' \"\$(body 'private fun confirmDeleteMessage\\(index: Int\\)' \"\$ACT\")\" | grep -q 'setNeutralButton'"
c "下标**当场反查**（chatBubbles.indexOf(v)），不是闭包捕获的循环变量" \
  "printf '%s' \"\$AB\" | grep -q 'val idx = chatBubbles.indexOf(v)'"
c "反查失败时不删（idx >= 0 才动手）" \
  "printf '%s' \"\$AB\" | grep -q 'if (idx >= 0) confirmDeleteMessage(idx)'"
# 反查之后传给删除入口的还是**消息下标**：气泡索引与 SessionStore.load 的顺序必须同名。
# 强判据：renderHistory 逐条建气泡时，索引与 load 的顺序同源（同一个循环），
# 而不是"另开一次 load 再各算各的"。
c "气泡索引与历史顺序同源（在同一个遍历里建，不另算一份）" \
  "printf '%s' \"\$RB\" | grep -q 'for ((i, m) in hist.withIndex())'"
DEL=$(body 'private fun confirmDeleteMessage\(index: Int\)' "$ACT")
c "confirmDeleteMessage() 存在（锚点未漂）" "[ -n \"\$DEL\" ]"
c "动手前按 index 取到那一条（越界即返回，不猜）" \
  "printf '%s' \"\$DEL\" | grep -q 'msgs.getOrNull(index) ?: return'"
c "生成中禁止删除（与其他会话操作同一条闸门）" \
  "printf '%s' \"\$DEL\" | grep -q 'if (generating)'"
c "真的调用存储层删除 deleteAt" \
  "printf '%s' \"\$DEL\" | grep -q 'SessionStore.deleteAt(this, currentSessionId, index)'"

# ── 4) KV 账本：删一条**不得**全清；清空**才**全清 ─────────────────────────
# 这一条上一版是**反的**：当时钉的是"删一条必须 resetKvCache()"。而 `resetKvCache()`
# 走 native 是 `seq_rm(0,-1,-1)` = **全清**，删末条时把分叉点**之前**还能复用的前缀
# 一起扔了 → 下一轮从 token 0 全量重算。症状正是用户报的"删除最后一条，KV 完全没复用"。
#
# 删单条删的是**尾巴**：`plan_reuse` 按逐 token 公共前缀复用到分叉点、自己
# `seq_rm(0, reuse, -1)` 砍尾巴，所以删除路径只要**不碰** KV，复用就回来。
# 断言必须是"不得出现"。清空是全清：历史一条不剩、没有可复用前缀，**才**该 resetKvCache()。
# 判据**先剥注释**：这条注释里本身就在讲 resetKvCache 这件事，不剥会得到恒红的假红。
c "删一条**不**全清 KV（resetKvCache 是全清，会把分叉点之前的可复用前缀一起扔）" \
  "! printf '%s' \"\$DEL\" | nocomment | grep -q 'resetKvCache'"
CLR=$(body 'clearBtn.setOnClickListener' "$ACT")
c "清空对话的监听仍能取到（锚点未漂）" "[ -n \"\$CLR\" ]"
c "清空对话**才**全清 KV 账本（历史一条不剩，没有可复用前缀）" \
  "printf '%s' \"\$CLR\" | grep -q 'LlmEngine.resetKvCache()'"
c "resetKvCache 仍是 native 的显式出口（清 KV + 作废账本，两件事都做）" \
  "grep -q 'fun resetKvCache() = runCatching { nativeKvCacheReset() }' \$SRC/LlmEngine.kt"

# ── 5) 存储层：删除是"精确的一条对一条"，不夹带裁剪 ────────────────────────
c "SessionStore 有 deleteAt（0 基，与 load 顺序一致）" \
  "grep -q 'fun deleteAt(ctx: Context, id: String, index: Int): Boolean' \$SS"
c "越界不动（静默不动是安全的失败方向，不是删错一条）" \
  "grep -q 'if (index !in msgs.indices) return false' \$SS"
DA=$(body 'fun deleteAt\(ctx: Context, id: String, index: Int\)' "$SS")
c "deleteAt() 存在（锚点未漂）" "[ -n \"\$DA\" ]"
c "删除后写回消息列表（真的落盘，不是只改内存）" \
  "printf '%s' \"\$DA\" | grep -q 'writeMessages(ctx, id, msgs)'"
# 关键：删/截断**不得**再走 append 的裁剪保险丝 —— 否则"只删了中间一条，
# 最早的几条怎么也少了"，用户只会以为删除功能坏了。
WM=$(body 'private fun writeMessages\(ctx: Context, id: String, msgs: MutableList<Msg>\)' "$SS")
c "writeMessages() 存在（删除/截断的落盘出口）" "[ -n \"\$WM\" ]"
c "writeMessages 里没有夹带裁剪（capChars 不在这里）" \
  "! printf '%s' \"\$WM\" | grep -q 'capChars'"
c "deleteAt 也不调 append（append 会顺手裁剪）" \
  "! printf '%s' \"\$DA\" | grep -q 'append('"
c "append() 仍是**唯一**做裁剪的地方（保险丝没被搬家）" \
  "[ \$(grep -c 'capChars(ctx, id, msgs)' \$SS) -eq 1 ]"

# ── 6) 翻页手势**只有一个入口**（Issue：一次滑动翻两页）───────────────────
# 症状是"往前一页页走、往回却从第三页直接跳回第一页"——不是方向 bug，是同一
# 手势被两个入口各处理一次（GestureDetector.onFling + OnTouchListener），
# 一次滑动翻了两页。两个入口的触发条件不同（fling 看速度、onTouch 看位移），
# 快滑时都中、慢滑时只中一个，于是表现随滑动速度漂移、看着毫无规律。
# 判据：翻页副作用（调 selectTabFn）只允许出现在 attachSwipePager 里一处。
SP=$(body 'private fun attachSwipePager\(w: View, total: Int\)' "$ACT")
c "attachSwipePager() 存在（锚点未漂）" "[ -n \"\$SP\" ]"
c "翻页只在 attachSwipePager 里触发（一次滑动只翻一页）" \
  "[ \$(grep -c 'fn(next)' \$ACT) -eq 1 ]"
c "旧的 GestureDetector 翻页入口必须绝迹（不看注释）" \
  "! nocomment < \$ACT | grep -q 'GestureDetector'"
c "旧的 dispatchTouchEvent 翻页钩子必须绝迹" "! grep -q 'override fun dispatchTouchEvent' \$ACT"
c "四页（聊天/设置/知识库/日志）的翻页范围是 0..3" \
  "grep -q 'attachSwipePager(rootScroll, 4)' \$ACT"

echo ""
if [ "$bad" = "0" ]; then echo "=== 聊天页气泡/删除守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 聊天页气泡/删除守卫：PASS $ok / FAIL $bad ==="; exit 1
