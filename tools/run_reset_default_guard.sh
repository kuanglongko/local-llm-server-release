#!/bin/sh
# 「还原默认设置」必须先问一句 —— 否则一次误触就是整页参数没了。
#
# ═══════════════════════════════════════════════════════════════════════════
# 现象与根因
# ═══════════════════════════════════════════════════════════════════════════
# 用户报（ISSUE #108）："给设置页的还原默认设置按钮增加确认弹窗，避免误触"。
#
# 这个按钮一次性覆盖**整页**参数（ctx / gpu / 线程数 / 采样 / 惩罚 / mmap /
# repack 三档 / 端口 / 局域网开关），而且就在面板最底下、紧挨着上面那些分组 ——
# 一次误触就够了。它**没有撤销**：用户调过的值不落任何"上一版"记录，
# 被覆盖之后只能凭记忆重填（真机上就是一格一格试回去）。
#
# 全 App 其余"一键改一批"的破坏性动作都走同一道闸：
#   · 删会话（confirmDeleteSession）、批量清理（showCleanupDialog 默认全不勾）、
#   · 导入落盘（confirmImport）、切库丢草稿（confirmDiscardRagDoc）、
#   · 丢 prompt 缓存（按钮名即后果）。
# 这里没有理由例外。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「确认之前参数一格都没动」，不锚"文件里有个 AlertDialog"
# ═══════════════════════════════════════════════════════════════════════════
# 这一族最容易被写出的假绿：
#   · 「有个 AlertDialog.Builder」—— 恒真，本文件有几十个；
#   · 「出现了 setPositiveButton」—— 恒真，同上；
#   · 「按钮名叫『还原默认设置』」—— 恒真，它一直都在，正是它没有闸；
#   · 「弹窗里有"没有撤销"这句文案」—— 恒真，而且**只弹个"已还原"的提示**
#     也能满足它 —— 那时对话框说的是"已经发生了",误触的代价一分没省。
#
# 所以本守卫钉的是**次序与可达性**这两件语义：
#   ① 设置页那个按钮的点击回调里**只允许**调确认方法 —— 参数覆盖语句不得内联
#      在回调里（内联 = 点一下就还原，弹窗弹在事后）；
#   ② 参数覆盖只在**唯一的写点** `restoreDefaultParams()` 里，且该方法**只被**
#      确认框的 `setPositiveButton` 那一句引用（任何别处的引用都是"没问就动"）；
#   ③ 确认框里的**次序**：`setPositiveButton` 引用的那个体必须是还原方法；
#      且方法体内不得出现 `restoreDefaultParams()` 之外的动作（设置页那几行的
#      `setText` 一旦抄进回调，就等于"点确定时做了两遍"或是"绕过了写点"）。
#
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_reset_default_guard.sh
set -e
cd "$(dirname "$0")/.."
KT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释（本轮修复的注释里**必然**引用坏写法与那句 setMessage，不剥则判据恒真/恒红）。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*(\*|/\*|//)'; }
# 剥字符串字面量：`setMessage` 的文案里会出现"还原""参数"这些字样。
stripstr() { sed -e 's/"[^"]*"/""/g'; }
code() { nocomment < "$KT" | stripstr; }

# 取某个 kotlin 方法的函数体（与 run_session_cleanup_guard.sh 的 body() 同一判据）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$KT"; }

c "EngineActivity.kt 存在" "[ -f \$KT ]"

# ── ① 设置页那个按钮：点击回调里**只**调确认方法 ────────────────────────────
# ⚠ 判据取的是**按钮本身那一行**（`resetBtn.setOnClickListener { … }`），
#   不是"文件里出现过 confirmResetDefaults" —— 后者对"另加一个入口但老回调
#   原样保留"的桩恒真（两处都在，用户点老按钮照样直接还原）。
RESET_CB=$(code | grep -n 'resetBtn.setOnClickListener' || true)
c "① 「还原默认设置」的点击回调存在（锚点未漂）" "[ -n \"\$RESET_CB\" ]"
c "① 点击回调里只调确认方法（不内联任何参数覆盖）" \
  "printf '%s' \"\$RESET_CB\" | grep -q 'resetBtn.setOnClickListener { confirmResetDefaults() }'"
c "① 点击回调那一行没有 setText（内联 = 点一下就还原，弹窗弹在事后）" \
  "! printf '%s' \"\$RESET_CB\" | grep -q 'setText'"
c "① 点击回调那一行没有 persistParams（没有落盘 = 没有真的在还原）" \
  "! printf '%s' \"\$RESET_CB\" | grep -q 'persistParams'"

# ── ② 参数覆盖只有唯一的写点：restoreDefaultParams() ────────────────────────
RESET_BODY=$(body 'private fun restoreDefaultParams\(\)' "$KT")
c "② restoreDefaultParams() 存在（参数覆盖的唯一写点）" "[ -n \"\$RESET_BODY\" ]"
c "② 它确实在覆盖参数（不是个空壳：ctx / 端口 / repack 三档都在）" \
  "printf '%s' \"\$RESET_BODY\" | nocomment | grep -q 'ctxEt.setText' && \
   printf '%s' \"\$RESET_BODY\" | nocomment | grep -q 'portEt.setText' && \
   printf '%s' \"\$RESET_BODY\" | nocomment | grep -q 'repackDefaultId'"
c "② 覆盖完要落盘（不落盘 = 下次打开又回来，用户以为"还原没生效"）" \
  "printf '%s' \"\$RESET_BODY\" | nocomment | grep -q 'persistParams()'"

# 归属：这几行**不得**出现在 restoreDefaultParams 之外的地方。
# 判据是"全文件里 ctxEt.setText(...) 的出现次数" —— 设置页装配处不写它
# （装配只 create 输入框 + 读存量），所以正解是 1 处。多于 1 处即"还有第二个写点"。
CTX_SET=$(code | grep -c 'ctxEt.setText' || true)
c "② 参数覆盖全文件只有一处（多出来的写点 = 有人绕过确认直接改）" \
  "[ \"\$CTX_SET\" -eq 1 ]"

# ── ③ 确认框：先问后做，且只从 setPositiveButton 那一句引用写点 ─────────────
CONFIRM_BODY=$(body 'private fun confirmResetDefaults\(\)' "$KT")
c "③ confirmResetDefaults() 存在（确认框的唯一归属）" "[ -n \"\$CONFIRM_BODY\" ]"
c "③ 它建的是 AlertDialog（不是 Toast / Snackbar 那种"告知"型反馈）" \
  "printf '%s' \"\$CONFIRM_BODY\" | nocomment | grep -q 'AlertDialog.Builder(this)'"
c "③ 确认按钮引用的是**写点**（setPositiveButton 的体里只有 restoreDefaultParams）" \
  "printf '%s' \"\$CONFIRM_BODY\" | nocomment | grep -q 'setPositiveButton(\"还原\") { _, _ -> restoreDefaultParams() }'"
# 反向：确认框的按钮体里不得**再**出现参数覆盖 —— 抄进去就等于"点确定做了两遍"
# （或者更糟：写点与回调各存一份，改一处漏另一处）。
c "③ 确认框本体里没有参数覆盖（覆盖只在写点里）" \
  "! printf '%s' \"\$CONFIRM_BODY\" | nocomment | grep -qE 'ctxEt\\.setText|portEt\\.setText|persistParams'"
# 取消必须是个**什么都不做**的出口：`setNegativeButton("取消", null)`。
# 写成 `{ _, _ -> restoreDefaultParams() }` 这种桩最阴 —— 界面上"取消"看着能退出，
# 而参数已经被还原了。
c "③ 取消 = 什么都不做（null，不是"取消也走一遍还原"）" \
  "printf '%s' \"\$CONFIRM_BODY\" | nocomment | grep -q 'setNegativeButton(\"取消\", null)'"

# ── ③b 确认框要**说清后果**，且"没有撤销"这句必须在 ────────────────────────
# 这一条是"确认框有没有用"的分界：一个只说"确定要还原吗"的框，用户在第二次
# 误触时会闭眼点"确定" —— 本仓库对提示的一贯口径是"要说清**代价**，
# 不是重复一遍按钮名"。所以钉两件事：讲明作用范围（含端口/局域网）、
# 讲明不可撤销。用**原文**（未剥字面量）查。
c "③b 确认框说清了作用范围（整页分组 + 端口 + 局域网）" \
  "grep -q '把设置页里所有分组一次性恢复成默认值' \$KT && \
   grep -q '端口（8080）与局域网开关（关）' \$KT"
c "③b 确认框说清了"没有撤销"（只说"确定要还原吗"的框，第二次误触会闭眼点）" \
  "grep -q '没有撤销' \$KT"
# 生效时机必须写在框里：加载类参数**不会**当场生效，用户还原完立刻去跑模型，
# 发现"没变化"就会以为按钮坏了（本仓库对"改完没生效"的处置一贯是当场说清）。
c "③b 确认框说清了生效时机（加载类参数需重新加载模型）" \
  "grep -q '要重新加载模型才生效' \$KT && \
   grep -q '端口与局域网开关\*\*立即生效\*\*' \$KT"

# ── ④ 反向：设置页那个按钮**不得**留下旧的内联还原体 ────────────────────────
# 这一条是**回归桩的判据面**：本轮修的正是"内联在点击回调里"这个形状。
# 旧写法里有 `gpuEt.setText("0"); thrEt.setText(...)` 连在一行上的特征，
# 只要它还留在设置页装配段里，就说明"随手点一下还是直接还原"。
SETPAGE=$(awk '/pageSet = ScrollView|pageSet.addView\(resetBtn/{print}' "$KT" >/dev/null 2>&1; \
          awk 'BEGIN{inb=0} /val pageSet|pageSet = /{inb=1} inb{print} /pageSet.addView\(resetBtn/{exit}' "$KT")
c "④ 设置页装配段里不再有旧的内联还原体（本轮修掉的形状）" \
  "! printf '%s' \"\$SETPAGE\" | nocomment | stripstr | grep -q 'thrEt.setText(LlmEngine.defaultThreads()'"

# ── ⑤ 反向锚：破坏性动作**都**走确认框（本按钮不许是唯一的例外）───────────
# 这条不是"另加一个功能"，而是把本按钮放进它本来就该在的那一族里：
# 删会话 / 导入 / 批量清理都有确认框。若这条红，说明有人把这里的口子又打开了。
c "⑤ 同族的破坏性动作都在用 AlertDialog 确认（本按钮不是孤例）" \
  "[ \$(code | grep -c 'AlertDialog.Builder(this)') -ge 8 ] && \
   grep -q 'private fun confirmImport' \$KT && \
   grep -q 'private fun confirmDeleteSession' \$KT"

# ── ⑥ 判据网自身：不得只锚「关键词出现过」 ─────────────────────────────────
c "本守卫剥了注释与字符串字面量（否则『只写句说明/文案』会误判成『真写成那样』）" \
  "grep -q 'stripstr' tools/run_reset_default_guard.sh && \
   grep -q 'nocomment' tools/run_reset_default_guard.sh"
c "本守卫取的是按钮那一行，不是「全文件搜 confirmResetDefaults」（防另加入口桩）" \
  "grep -q 'resetBtn.setOnClickListener' tools/run_reset_default_guard.sh && \
   grep -q 'RESET_CB' tools/run_reset_default_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "PASS $ok / FAIL 0"
    echo "=== 还原默认设置确认框守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 还原默认设置确认框守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
