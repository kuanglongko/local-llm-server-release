#!/bin/sh
# 「设置页控件被撑成一大片空白」必须钉在 LayoutParams 与父容器方向的一致性上。
#
# ═══════════════════════════════════════════════════════════════════════════
# 现象与根因（0.9.127 真机回归）
# ═══════════════════════════════════════════════════════════════════════════
# 用户装机后报：「新的 apk 安装后，repack 没法调节，是一大片空白」。
#
# repack 那个**三档单选组**在 fb325e0（0.9.127）里是这么加子按钮的：
#
#     g.addView(rb, lp(0, 1))
#
# 而 `lp(w, weight)` 的语义是**给横向行写的**：
#
#     private fun lp(w: Int, weight: Int) = LinearLayout.LayoutParams(
#         if (w == 0) 0 else WRAP_CONTENT,      // ← 宽 = 0dp
#         WRAP_CONTENT,
#         weight.toFloat())                      // ← weight = 1
#
# `LinearLayout.LayoutParams` 的第三个参数是 weight —— 但 weight **分的是父容器
# 主轴方向的剩余空间**：
#   · 父容器 HORIZONTAL → weight 分**宽度** ⇒ `lp(0, 1)` 正好是"等分一行"（本文件
#     里 20 多处横向行都这么用，是对的）；
#   · 父容器 VERTICAL   → weight 分**高度**   ⇒ `lp(0, 1)` 变成"高度=0dp、但吃掉
#     全部剩余纵向空间"。
#
# repack 组正是 `RadioGroup.VERTICAL`。于是三个 RadioButton 各被拉成**整屏高的
# 空块**（文字垂直居中，看起来就是三片空白 + 中间一行小字），视觉上"整段全是空白"。
# 宽度那半同样错：`if (w == 0) 0` 给的是 0dp，在纵向容器里不靠 weight 撑宽，
# 所以文字框只剩 wrap 的宽度。
#
# 上游那一句 `quotaGroup.addView(rb, LinearLayout.LayoutParams(-1, -2))` 就在同一个
# 文件里、就在同一个方法的上一层 —— **正确写法有现成样板，这一处是抄漏了**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「父容器方向 × 子项 LayoutParams」的一致性，不锚"出现过某个字符串"
# ═══════════════════════════════════════════════════════════════════════════
# 这一族最容易被写出的假绿：
#   · 「repack 单选组存在」—— 恒真，它一直都在，正是它显示成了空白；
#   · 「repackRadioGroup() 里有 addView」—— 恒真，问题就在这句的参数上；
#   · 「源码里出现过 MATCH_PARENT」—— 全文件到处都是，与这一处无关。
# 所以本守卫先把 `lp(...)` 的**定义**读出来（宽 0dp + weight），再逐个父容器
# 与它的子项调用对账：凡 `VERTICAL` 的组**不得**出现 `lp(0, N)` 形态的子项参数，
# 且必须出现 `addView(rb, ` 且参数不含 `lp(`。同时反向锚住"横向行仍在用 lp(0,1)"
# —— 否则为了把这条修绿，把全文件的横向行一起改掉也照样绿。
#
# 运行：sh tools/run_repack_lp_guard.sh
set -e
cd "$(dirname "$0")/.."
KT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释：本轮修复注释里**必然**引用坏写法（"`lp(0, 1)` 会给出…"），不剥则判据恒红。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*(\*|/\*|//)'; }
# 剥字符串字面量：日志/说明文案里会出现 `lp(0, 1)` 这类字样（本文件就有），
# 不剥会把"只是写了句提示"当成"真把参数写成那样"。
stripstr() { sed -e 's/"[^"]*"/""/g'; }
code() { nocomment < "$KT" | stripstr; }

c "EngineActivity.kt 存在" "[ -f \$KT ]"

# ── ① lp() 的定义仍是「宽=0dp + weight」那一个（契约前提）────────────────
# 判据建立在"lp(0,N) = 宽 0dp、weight N"之上。定义改了，下面几条的语义就变了，
# 所以先把契约锚住 —— 而不是假设它永远是这样。
c "lp() 的宽度分支仍是「0 → 0dp（靠 weight 等分）」" \
  "code | grep -q 'if (w == 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT'"

c "lp() 仍把第三参数当 weight 用" \
  "code | grep -q 'weight.toFloat()'"

# ── ② repack 组的子按钮参数**不得**是 lp(...) 形态 ───────────────────────
# 判「归属」：这段赋值必须落在 repackRadioGroup 的函数体里，不只是在文件某处出现。
rbgroup() { awk '/private fun repackRadioGroup\(\)/{inb=1} inb{print} inb && /^    }$/{exit}' "$KT"; }

c "repackRadioGroup() 里改用 LinearLayout.LayoutParams 显式给宽高（不再借 lp 的 weight）" \
  "rbgroup | nocomment | stripstr | grep -q 'g.addView(rb, LinearLayout.LayoutParams('"

c "repack 组里不再出现 lp(0, 1) —— 那是给横向行写的，纵向里分的是高度" \
  "! rbgroup | nocomment | stripstr | grep -q 'addView(rb, lp('"

# ── ③ 显式给的是 MATCH_PARENT + WRAP_CONTENT（方向对了）─────────────────
c "repack 子按钮宽为 MATCH_PARENT（不是 0dp，也不用 weight 撑宽）" \
  "rbgroup | nocomment | stripstr | grep -q 'ViewGroup.LayoutParams.MATCH_PARENT'"

c "repack 子按钮高为 WRAP_CONTENT（不是靠 weight 吃满剩余高度）" \
  "rbgroup | nocomment | stripstr | grep -q 'ViewGroup.LayoutParams.WRAP_CONTENT'"

# ── ④ 全文件：**没有**任何 VERTICAL 容器拿 lp(...) 加子项 ────────────────
# 这一条是"同形态不再犯"的网：上面 ②③ 只钉 repack 那一处，而写坏它的
# **认知**（"lp 就是通用的等分参数"）在别处同样会犯。所以扫全文件。
# 逻辑抽在 tools/repack_lp/scan_vertical_lp.py 里，独立可跑、可单测 —— 不写成
# 内联 `python3 -c`：那段正则要穿过 sh 与 python 两层引用，本判据第一版就
# 死在引用上（sh 先解一层，把正则当命令执行，报 `/bin: Permission denied`）。
c "全文件不存在「VERTICAL 容器 + lp(...) 子项」的组合（同类误用清零）" \
  "python3 tools/repack_lp/scan_vertical_lp.py \"\$KT\" >/dev/null"

c "扫描器对 VERTICAL 的识别覆盖两种写法（构造器链式 / 事后赋 orientation）" \
  "grep -q 'orientation' tools/repack_lp/scan_vertical_lp.py && \
   grep -q 'VERTICAL' tools/repack_lp/scan_vertical_lp.py"

# ── ④b 面板文案：只留「按钮干什么 + 要注意什么」────────────────────────
# 这两条**不能**用剥过字符串字面量的 `code` —— 要查的正是文案本身。
#
# 判据方向已从"内容检查"改为"体积检查"。原判据要求面板里出现
# 「控件被撑开 ≠ 档位没生效」那段现场指引并点名 0.9.127 的 LayoutParams 缺陷；
# 用户后来明确反馈"太啰嗦，只保留顶部的内容，把内容精简" —— 那两段长文已从
# 设置页删掉。这里**不是**把判据删掉了事：删掉等于这一族（"说明堆成排查手册"）
# 重新失去网。改成钉**每段说明的字数上限**，既守住这次的收敛结果，
# 也把"以后又往面板里堆长文"挡在门外。
# 为什么不用"不许出现某段话"：那是把判据钉死在**本次删掉的措辞**上，
# 下次换个说法堆同样长的文就照样绿。
c "设置页每段说明都在字数上限内（说明不得再堆成排查手册）" \
  "python3 tools/repack_lp/check_hint_length.py \"\$KT\" >/dev/null"

# ── ⑤ 反向锚：横向行仍按 lp(0, N) 等分（别为了修这条把对的也改掉）───────
c "横向行仍在用 lp(0, 1) 等分（本判据只对纵向容器生效）" \
  "[ \$(code | grep -c 'addView(.*lp(0, 1))') -ge 8 ]"

# ── ⑥ 判据网自身：不得只锚「关键词出现过」 ─────────────────────────────
c "本守卫剥了注释与字符串字面量（否则『只写句说明』会误判成『真写成那样』）" \
  "grep -q 'stripstr' tools/run_repack_lp_guard.sh && \
   grep -q 'nocomment' tools/run_repack_lp_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "=== repack 布局守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== repack 布局守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
