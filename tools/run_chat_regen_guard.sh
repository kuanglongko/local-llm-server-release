#!/bin/sh
# 「聊天页重新生成」的接线守卫（Issue #163 的 ③）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠"能编译"不够
# ═══════════════════════════════════════════════════════════════════════════
# 「重新生成」的坏法与 ①② 完全不同族：它**每一步都不报错**，但用户的答案会不翼而飞。
# 这批里有四条零症状的不变量：
#
#   ① 截断历史必须**早于**建"正在生成"的占位气泡。
#      renderChat 会把页面上那条可变气泡摘掉重建 —— 顺序反了（先建气泡再截断）时，
#      参数非法或 native 拒绝启动就会让用户看到"我的答案没了"。
#      这是整批里最容易踩的一个坑：两行代码换个位置，症状只在参数填错时才出现。
#   ② 「重新生成」的边界必须是**用户长按的那一条**，不是"末条"，也不是别的猜法。
#      重跑范围一旦取错，丢掉的就不是一条而是好几条，且不可恢复。
#   ③ 「停止」后的半截回答**照样落盘**（与客户端一致）：停止只表示"我不等了"，
#      不改变"这条已经存在"。于是历史里没有"半成品"状态，也不需要 `streamingAppended`
#      这类区分"页面上那条是不是历史里的"标志位 —— 少一个状态就少一类漂移。
#      反过来（停止不落盘）会让"末条 assistant"出现两种语法相同的语义，只能靠标志位猜。
#   ④ 重跑路径**绝不能**作废 KV 账本：`resetKvCache()` 走 native 是**全清**
#      （`seq_rm(0,-1,-1)`），而重跑点**之前**的历史一条没动、前缀还能复用 ——
#      全清之后下一轮 `plan_reuse` 直接 `kv_valid == false` → 从 token 0 全量重算。
#      症状是"重新生成后 KV 完全没复用"，但**不报错、不崩**，所以只能靠断言钉住。
#      这一条是"不得出现"（方向与上一版相反：上一版钉的是"必须出现"，正好钉反了）。
#
# 判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 顺序 / 在哪个函数体内），
# 不锚"某个函数存在"。纯静态断言，不依赖任何工具链。
# 运行：sh tools/run_chat_regen_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
SS=$SRC/SessionStore.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（与 run_chat_bubble_guard.sh / run_ui_io_guard.sh
# 的 body() 同一判据；三处各写一份取函数体的近似就是"判据漂移"的起点）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }

# 剥注释（判据文本在注释里也出现，不剥会得到恒真的假绿）
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

# 在某个函数体里取两个锚点出现的**行序**：第一个锚必须在第二个之前。
# 顺序类不变量的唯一有效判据 —— "两行都在" 对上"位置换了"是恒真的。
before_in() { # 函数签名 前锚正则 后锚正则 文件
    body "$1" "$4" | nocomment | awk -v a="$2" -v b="$3" '
        !pa && $0 ~ a { pa=NR }
        !pb && $0 ~ b { pb=NR }
        END { exit !(pa && pb && pa < pb) }'
}

c "EngineActivity.kt / SessionStore.kt 存在" "[ -f \$ACT ] && [ -f \$SS ]"

# ── 1) 生成循环只有一处：普通生成与重新生成共用同一条收尾 ───────────────────
# 两边各写一份生成循环就会各有各的收尾（看门狗 / 停止归属 / 落盘），
# 而"重新生成"这条路上的半截回答落盘与否直接决定 ③ 的语义。
SG=$(body 'private fun startGeneration\(messages: List<Pair<String, String>>\)' "$ACT")
c "startGeneration() 存在（生成循环的唯一归属）" "[ -n \"\$SG\" ]"
c "生成线程只在 startGeneration 里启动（两边各起一个就是两套收尾）" \
  "printf '%s' \"\$SG\" | grep -q 'Thread {'"
DG=$(body 'private fun doGenerate\(\)' "$ACT")
c "doGenerate() 存在（普通生成的入口）" "[ -n \"\$DG\" ]"
c "doGenerate 不再自己起生成（交给 startGeneration，收尾只有一份）" \
  "printf '%s' \"\$DG\" | grep -q 'startGeneration(preparedMessages())' && \
   ! printf '%s' \"\$DG\" | grep -q 'Thread {'"
# 拼历史 / 解析参数：两条路必须共用同一份实现（各拼一份 = system 可能只在一处被带上）。
# 判据是**调用点**：renderHistory 走它（显示）、preparedMessages 走它（喂模型），
# 加上一处定义，共 3 次；普通生成路径（doGenerate）自己**不**拼历史。
# ⚠ RAG 阶段 2 起 preparedMessages 不再直接 `return chatHistory(sys)`：
#   它先取历史，再按开关拼上检索片段（注入只落在 system 段），最后返回。
#   所以判据改成"体内**取到了** chatHistory 且**返回**了拼装结果"，
#   仍然钉住"喂模型的那一份history只在这里取"（关键点没变：别的路径不许自己拼）。
c "喂模型的 prompt 只有一个拼接出口（preparedMessages）" \
  "[ \$(grep -c 'chatHistory(' \$ACT) -eq 3 ] && \
   printf '%s' \"\$(body 'private fun preparedMessages\(\)' \"\$ACT\")\" | grep -q 'chatHistory(sys)' && \
   printf '%s' \"\$(body 'private fun preparedMessages\(\)' \"\$ACT\")\" | grep -q 'return ' && \
   ! printf '%s' \"\$DG\" | grep -q 'chatHistory('"
c "参数区解析只有一个出口（currentSamplingParams 收口）" \
  "[ \$(grep -c 'SamplingParams.fromRequest(' \$ACT) -eq 1 ] && \
   printf '%s' \"\$SG\" | grep -q 'currentSamplingParams()'"

# ── 2) 顺序：先截断历史，再建占位气泡 ──────────────────────────────────────
# 这是整批最容易踩的坑：两行换个位置，"参数非法时旧答案凭空消失"就回来了。
RG=$(body 'private fun regenerateLast\(index: Int\)' "$ACT")
c "regenerateLast(index) 存在（长按气泡的重跑入口）" "[ -n \"\$RG\" ]"
c "重跑入口里先截断历史、再进 startGeneration（顺序反面 = 旧答案先没了）" \
  "before_in 'private fun regenerateLast\\(index: Int\\)' 'SessionStore.truncateTo' 'startGeneration\\(preparedMessages\\(\\)\\)' \$ACT"
# 强判据：startGeneration 内部不得出现 truncateTo —— 一旦出现，"什么时候丢旧答案"
# 就与"什么时候建占位气泡"绑在一起了，顺序约束在调用方那里再也说不清。
c "startGeneration 内部不碰历史（截断只属于调用方，顺序才钉得住）" \
  "! printf '%s' \"\$SG\" | grep -q 'truncateTo'"
c "startGeneration 里先建占位气泡、再置 generating（次序反了会 位置还没出现就开始跑）" \
  "before_in 'private fun startGeneration\\(messages: List<Pair<String, String>>\\)' 'renderChat\\(\"\"\\)' 'generating = true; genBtn.isEnabled = false' \$ACT"

# ── 3) 边界：重跑范围 = 用户长按的那一条 ───────────────────────────────────
c "重跑边界取自**用户长按的那一条**（不是末条、也不是另算一份）" \
  "printf '%s' \"\$RG\" | grep -q 'SessionStore.load(this, currentSessionId).getOrNull(index) ?: return'"
c "截断用的是「保留前 index 条」（= 从长按的那条开始重跑）" \
  "printf '%s' \"\$RG\" | grep -q 'SessionStore.truncateTo(this, currentSessionId, index)'"
# 长按 user 时截断会把它自己也带走 —— 必须从**存储**补回这条提问，
# 而不是从输入框取（输入框里可能已经是用户准备问的下一句了）。
c "长按 user 时从存储补回这条提问（不从输入框取）" \
  "printf '%s' \"\$RG\" | grep -q 'SessionStore.appendUser(this, currentSessionId, target.content)'"
c "补回只发生在截断确实丢了它的时候（本就在末尾时补 = 同一句问两遍）" \
  "printf '%s' \"\$RG\" | grep -q 'SessionStore.load(this, currentSessionId).size == index'"

# ── 4) 停止即保存：半截回答照样落盘，历史里没有"半成品" ────────────────────
# 与客户端一致：停止只表示"我不等了"，不改变"这条已经存在"。
# 于是存储层不会再出现"半截 assistant"这种状态，也就不需要标志位去区分
# "页面上那条是不是历史里的" —— 少一个状态就少一类漂移（猜一定会漂）。
c "不再有 streamingAppended 这类 页面上那条是不是历史里的 标志位" \
  "! grep -q 'streamingAppended' \$ACT"
# 停止路径与成功路径是同一段收尾（thread 的 finally）里的两支。
# 判据是：停止那一支（`if (!done && !errd)` 之内）**必须**出现 appendAssistant ——
# 少了它就退回"半截只活在页面上"，那正是本轮要改掉的行为，且它没有任何报错。
c "「停止」后的半截回答照样落盘（不落盘就退回 只活在页面上的半成品）" \
  "printf '%s' \"\$SG\" | awk '/if \(!done && !errd\)/{f=1} f&&/appendAssistant/{ok=1} END{exit !ok}'"
c "停止落盘后同样定型气泡（renderChat(null)）—— 半截要变成一条正常的历史气泡" \
  "printf '%s' \"\$SG\" | awk '/if \(!done && !errd\)/{f=1} f&&/renderChat\(null\)/{ok=1} END{exit !ok}'"
c "落盘只看 有没有产出内容（成功/停止两条收尾都以它为前提）" \
  "printf '%s' \"\$SG\" | grep -q 'text.isNotBlank()' && printf '%s' \"\$SG\" | grep -q 'sb.isNotBlank()'"
# 半截落盘之后，重跑路径不再需要 partial 分支 —— 有它反而是"两种语义又回来了"。
c "重跑路径不再有 partial 半截分支（半截也落盘了，一律重写）" \
  "! printf '%s' \"\$RG\" | grep -q 'partial'"

# ── 5) KV 账本：重跑路径**不得**作废（作废是全清，会把还能用的前缀一起扔）──
# 这一条的方向上一版是**反的**：当时钉的是"regenerateLast 体内必须出现 resetKvCache()"。
# 而 `resetKvCache()` 走 native 是 `seq_rm(0,-1,-1)` = **全清**，重跑点**之前**的历史
# 明明没动、前缀还能复用，全清之后下一轮 `plan_reuse` 直接 `kv_valid == false` →
# 从 token 0 全量重算。症状正是用户报的"重新生成最后一条，KV 完全没复用"。
# 正确做法是**什么都不做**：plan_reuse 按逐 token 公共前缀复用到分叉点，并自己
# `seq_rm(0, reuse, -1)` 砍尾巴。所以断言必须是"不得出现"。
# 判据**先剥注释**：这两处的注释里本身就在讲 resetKvCache 这件事，不剥会得到恒红的假红。
c "重跑路径**不**作废账本（resetKvCache 是全清，会把还能复用的前缀一起扔）" \
  "! printf '%s' \"\$RG\" | nocomment | grep -q 'LlmEngine.resetKvCache()'"
# 作用域判据仍在：连 startGeneration 里也不能出现 —— 那会让每条起点都无条件全清一次
# （比上一版的 bug 更广，普通生成的复用也一起归零）。
c "作废点也不在 startGeneration 里（搬进去会让每条起点都全清一次）" \
  "! printf '%s' \"\$SG\" | nocomment | grep -q 'resetKvCache'"
c "作废点也不混进 common 的拼历史/解析参数那两个出口" \
  "! printf '%s' \"\$(body 'private fun preparedMessages\\(\\)' \"\$ACT\")\" | grep -q 'resetKvCache' && \
   ! printf '%s' \"\$(body 'private fun currentSamplingParams\\(\\)' \"\$ACT\")\" | grep -q 'resetKvCache'"
# 作废出口本身必须还在（清空路径 + 设置页手动兜底要用），且仍是"清 KV + 作废账本"。
c "resetKvCache 这个全清出口仍在（清空 / 设置页手动兜底用），且两件事都做" \
  "grep -q 'fun resetKvCache() = runCatching { nativeKvCacheReset() }' \$SRC/LlmEngine.kt"

# ── 6) 出口：菜单里只在**末条**给出"重新生成" ──────────────────────────────
# 不是末条时不给（而不是给了再灰掉）：点了没反应的按钮是最难解释的那类现象。
CM=$(body 'private fun confirmDeleteMessage\(index: Int\)' "$ACT")
c "confirmDeleteMessage() 存在（长按菜单，锚点未漂）" "[ -n \"\$CM\" ]"
c "「重新生成」只在长按**末条**时挂上（重跑的回答总是排在最后）" \
  "printf '%s' \"\$CM\" | grep -q 'val isLast = index == msgs.size - 1' && \
   printf '%s' \"\$CM\" | grep -q 'if (!isLast) null'"
# 判据锚 Builder 的 `setNeutralButton`（**不是** `AlertDialog.setButton` ——
# 后者不在 Builder 上，`Unresolved reference`，只在编 APK 时才暴露，
# 静态 grep 与守卫都看不见）。这条同时挡住"把中位按钮改回一个不存在的方法"。
c "菜单里真的挂上了这个按钮（Builder.setNeutralButton），且点它调 regenerateLast(index)" \
  "printf '%s' \"\$CM\" | grep -q 'builder.setNeutralButton(regen)' && \
   printf '%s' \"\$CM\" | grep -q 'regenerateLast(index)' && \
   ! printf '%s' \"\$CM\" | grep -q 'setButton('"
c "生成中禁止重新生成（与会话相关操作同一条闸门）" \
  "printf '%s' \"\$RG\" | grep -q 'if (generating)'"
c "模型没加载时拒绝（与普通生成同一条前置）" \
  "printf '%s' \"\$RG\" | grep -q 'if (!LlmEngine.hasModel)'"
c "菜单文案只有一种语义（重写/换一个 —— 不再有 接着往下写 那支）" \
  "printf '%s' \"\$CM\" | grep -q '重新生成这一条' && ! printf '%s' \"\$CM\" | grep -q '接着往下写'"

# ── 7) 存储层：截断是"精确地丢尾巴"，不夹带裁剪 ────────────────────────────
c "SessionStore 有 truncateTo(ctx, id, keep)" \
  "grep -q 'fun truncateTo(ctx: Context, id: String, keep: Int): Boolean' \$SS"
TR=$(body 'fun truncateTo\(ctx: Context, id: String, keep: Int\)' "$SS")
c "truncateTo() 存在（锚点未漂）" "[ -n \"\$TR\" ]"
c "截断后写回消息列表（真的落盘，不是只改内存）" \
  "printf '%s' \"\$TR\" | grep -q 'writeMessages(ctx, id'"
c "截断不夹带裁剪（还是 只动那几条，不是顺手再裁一刀）" \
  "! printf '%s' \"\$TR\" | grep -q 'capChars'"
c "append() 仍是**唯一**做裁剪的地方（保险丝没被搬家）" \
  "[ \$(grep -c 'capChars(ctx, id, msgs)' \$SS) -eq 1 ]"

echo ""
if [ "$bad" = "0" ]; then echo "=== 聊天页重新生成守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 聊天页重新生成守卫：PASS $ok / FAIL $bad ==="; exit 1
