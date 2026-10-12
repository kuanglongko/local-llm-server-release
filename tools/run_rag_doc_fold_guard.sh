#!/bin/sh
# 「正文框的收起 / 展开」接线守卫（ISSUE #259）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一条修的是什么
# ═══════════════════════════════════════════════════════════════════════════
# 正文框能装 `RagUiCap.MAX_DOC_CHARS`（4 万字符 ≈ 88 条、几百行），而它是
# `WRAP_CONTENT`：**装多少就占多高**，4 万字符在这一页上是十几屏。
# 排在它下面的恰好是用户最常去的地方（「追加」那一行、条目列表），
# 于是"往下滚"成了一次次都要付的通行费。用户报的原话：
#   *"有的时候滑倒底部去追加或者按条查看，滑动太慢"*。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一族改动的危险处**全在"错得极响，但方向不对"**
# ═══════════════════════════════════════════════════════════════════════════
# 「收起」这个动作天生只有两种写法，而**两种都是常见写法**：
#   · `maxLines` -> 内容一个字节不动，只是框变矮（**对的**）；
#   · `visibility = GONE` / `setText("")` -> 框"消失"了，但**内容也没了**
#     （对 `setText("")` 是字面意义上的没了：`ragDocEntries()` 读的就是它，
#     下一次「建库/重建」会拿一份空正文去建，而它**不崩、不报错**）。
# 两者的界面效果在前两秒**完全一样**。所以判据不能钉"看起来对不对"，
# 只能钉"这个动作碰了哪些东西"——见下面第 2 节。
#
# 另外三处是"看起来能用、其实用不了"：
#   · 收起之后**不说框里还有多少** -> "收起"与"内容丢了"分不开，
#     用户会去重导（而导入是追加合并，重导等于条目翻倍）；
#   · 被截过的头部**收起之后看不到那句"只是前一部分"**（说明写在正文末尾）
#     -> 唯一能告诉他"这份不是全部"的那句话正好被藏起来；
#   · `paintRagDocFold` 不在**换库**与**清旗标**两处被调 -> 按钮报着上一个库的
#     字符数、"只是前一部分"在用户手动补全之后仍然挂着（假警告）。
#
# 判据一律锚**结构关系**（碰了哪些东西 / 谁调用谁 / 控件挂在哪），
# 不锚"某某字符串出现过"（后者恒真：注释里就有）。纯静态断言，不依赖工具链。
# 运行：sh tools/run_rag_doc_fold_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
CAP=$SRC/RagUiCap.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

c "EngineActivity.kt / RagUiCap.kt 都存在" "[ -f \$ACT ] && [ -f \$CAP ]"

# ── 1) 收起态是**一个字段**，且冷启动是**收起** ──────────────────────────
# ⚠ 默认值本轮从 false 改成 true（ISSUE #259 的用户拍板：*"文字预览的窗口默认
#   设为折叠，用户需要时自己打开"*）。为什么改：
#   这一页的正文框是**编辑器**（要改的内容），不是**阅读器**（要看的内容），
#   而用户进这一页十次里有九次是为了别的事（点库 / 追加 / 看条目 / 建库）。
#   展开态下框占十几屏，把「追加」与条目列表推到了屏幕外 —— 于是"打开知识库页"
#   这个动作本身先付一次十几屏的滑动。
#
# ⚠ 但"默认收起"**只有在下面 4 节全部成立时才可接受**：框仍占 3 行（看得见里面有
#   东西）、按钮报着字符数、说明行写着"内容没有被改动"、且收起**不碰**正文一个字节。
#   只把框藏起来而不出声，与"这份内容没了"长得一样 —— 而这一条比"默认展开"贵得多。
#
# 另外两条不变：
#   · 收起态**不持久化**（"记住上次收起"会让用户第二次打开 App 看到一只矮框，
#     而"框里没内容"与"内容被收起了"在那一屏上完全一样）；
#   · 它是"这一屏现在要看什么"，不是内容的一部分（同 ragRowsPage 不持久化）。
c "① 收起态是个字段（不是每次现算）" \
  "grep -qE 'private var ragDocCollapsed = ' \$ACT"
c "① 冷启动默认**收起**（用户拍板：编辑器不该默认占十几屏）" \
  "grep -qE 'private var ragDocCollapsed = true' \$ACT"
# ⚠ 默认值改了，但"建完这一屏必须跟着画一次"这条**不能**跟着丢：字段说收起、
#   控件停在初始值，两个信号打架 —— 而用户看到的那个（框高）说"展开"。
c "① 建完这一屏就按字段画一次（默认收起必须当场生效，不然框高与字段相反）" \
  "grep -q 'paintRagDocFold()' \$ACT"
c "① 收起态**不写进 prefs**（它是"现在要看什么"，不是内容的一部分）" \
  "! printf '%s' \"\$(body 'private fun setRagDocCollapsed' \$ACT)\" | nocomment | grep -q 'SharedPreferences\|prefs'"

# ── 2) 收起**只改显示**：这一个函数的体内不许出现任何"改内容"的调用 ────
# ⚠⚠ 这一节是本守卫的核心。上面那段说了：`maxLines` 与 `setText("")` 在界面上
#   前两秒完全一样，而后者会让 `ragDocEntries()` 读出一份空正文 ——
#   「建库/重建」照跑、索引照建，只是建出来的是**空的**。
#   所以判据钉的不是"用了 maxLines"，而是"**没有碰任何内容侧的东西**"：
#   一个 `setText` / `append` / `Editable` 取值都不许有。
PAINT=$(body 'private fun paintRagDocFold' "$ACT")
c "② paintRagDocFold() 存在（锚点未漂）" "[ -n \"\$PAINT\" ]"
c "② 收起**改的是 maxLines**（不是把框藏起来）" \
  "printf '%s' \"\$PAINT\" | grep -q 'maxLines'"
c "② 收起**不许**碰正文内容（setText / setRagDocText / append / Editable）" \
  "! printf '%s' \"\$PAINT\" | nocomment | grep -qE 'setText\\(|setRagDocText|append\\(|Edit(able|text)'"
# `visibility` 只许出现在**说明行**上（收起态那一行字），不许出现在正文框上。
# 这一条防的是"顺手把框也 GONE 了"：那与"内容没了"长得一样（框里那份是
# 唯一副本时更是直接毁掉用户的唯一一份）。
c "② 收起**不许**把正文框设成 GONE（那与"内容没了"长得一样）" \
  "! printf '%s' \"\$PAINT\" | nocomment | grep -qE 'ragDocEt\\.visibility'"
c "② 收起态的行数从 RagUiCap 取（不在 UI 里另写一个数）" \
  "printf '%s' \"\$PAINT\" | nocomment | grep -q 'RagUiCap.DOC_COLLAPSED_LINES'"
c "② 按钮文案也从 RagUiCap 取（含字符数 —— "收起"与"内容丢了"靠它分开）" \
  "printf '%s' \"\$PAINT\" | nocomment | grep -q 'RagUiCap.docToggleLabel'"

# ── 3) 收起**不影响**任何读内容的路径 ──────────────────────────────────
# 「收起」若被写进判据链（"收起了就不算数"），后果是静默的：
# 建库/保存读到的东西与用户在屏幕上看到的**不再是一件事**。
ENT=$(body 'private fun ragDocEntries' "$ACT")
c "③ ragDocEntries() 仍只认框里的 text（收起态不参与取数）" \
  "printf '%s' \"\$ENT\" | grep -q 'ragDocEt.text.toString()' && \
   ! printf '%s' \"\$ENT\" | nocomment | grep -q 'ragDocCollapsed'"
c "③ 「保存正文」的拒绝判据里**没有**收起态（收起 ≠ 没内容）" \
  "! printf '%s' \"\$(body 'private fun doSaveTexts' \$ACT)\" | nocomment | grep -q 'ragDocCollapsed'"
c "③ 「建库/重建」的拒绝判据里**没有**收起态" \
  "! printf '%s' \"\$(body 'private fun doBuildIndex' \$ACT)\" | nocomment | grep -q 'ragDocCollapsed'"

# ── 4) 两处内容变化都要重画按钮（否则按钮报着上一个库的数） ─────────────
SET=$(body 'private fun setRagDocText' "$ACT")
c "④ setRagDocText() 里重画按钮（换库之后按钮的数字要跟着走）" \
  "printf '%s' \"\$SET\" | nocomment | grep -q 'paintRagDocFold()'"
# 用户一编辑，[ragDocClipped] 被清掉 —— 而按钮上那句"只是前一部分"是**从它来的**。
# 不在同一处重画，用户手动补全了尾巴之后按钮还在说"这只是前一部分"（假警告），
# 而假警告的代价是真被截的那一次也没人信（本仓库对警告噪音的一贯判据）。
c "④ 清掉截断旗标之后也重画（不许留一句假警告）" \
  "$PY tools/rag_doc_fold/check_tw_repaint.py \$ACT"

# ── 4b) 折叠状态必须是 [setRagDocSliced] 的**最后一笔**（ISSUE #228 第 ② 条）──
# `setRagDocSliced` 里的 `setSingleLine(false)` 会把 maxLines 复位成「不限」，
# 于是"先填正文(paintRagDocFold: maxLines=3)、再切切片态(setSingleLine(false):
# maxLines=MAX_VALUE)"这一对调用恒把收起态**冲掉** —— 字段说收起、框在屏幕上
# 展开，用户点两次才收得起来。判据钉的是**次序**（谁最后说话），不是字符串出现过。
c "④b 折叠状态是 setRagDocSliced() 的最后一笔（不被 setSingleLine(false) 冲掉）" \
  "$PY tools/rag_doc_fold/check_collapse_after_sliced.py \$ACT"

# ── 5) 按钮**够得着**：挂在正文框**上面**，且整行宽 ────────────────────
# 挂在框**下面**的写法在展开态下要滑过十几屏才够得着 —— 而那正是这一功能
# 要解决的问题本身（滑太慢）。判据钉的是**挂载顺序**：按钮在框之前。
c "⑤ 折叠按钮挂在**正文框之前**（展开时也在够得着的地方）" \
  "$PY tools/rag_doc_fold/check_order.py \$ACT ragDocFoldBtn ragDocEt"
# ⚠ 判据锚的是"**按钮被挂上去了**"，不是"直接挂在 pageRag 上"：ISSUE #259
#   第 ③ 条之后它挂在 `ragDocTopRow` 那一行里（与「保存正文」同一行），
#   那一行再挂到 pageRag。死认 `pageRag.addView(ragDocFoldBtn` 会恒红，
#   而恒红的判据很快会被静音 —— 它挡的是"定义了控件却忘了挂"（点了没反应）。
#   "挂载链最终到 pageRag"那一层由上面的 check_order.py 钉（它走上溯）。
c "⑤ 按钮存在 —— 不是只定义了控件没挂上去（挂在 pageRag 或它的行容器里）" \
  "grep -qE 'pageRag\\.addView\\(ragDocFoldBtn|[A-Za-z_][A-Za-z0-9_]*\\.addView\\(ragDocFoldBtn' \$ACT"
c "⑤ 按钮点了**切换**（不是只朝一个方向）" \
  "grep -q 'setRagDocCollapsed(!ragDocCollapsed)' \$ACT"

# ── 6) 纯逻辑那一半在 RagUiCap（可离线单测） ───────────────────────────
c "⑥ 收起行数是 RagUiCap 里的具名常量" \
  "grep -qE 'const val DOC_COLLAPSED_LINES = ' \$CAP"
c "⑥ 按钮文案是纯函数（宿主可单测，不依赖 Android）" \
  "grep -q 'fun docToggleLabel(' \$CAP && grep -q 'fun docCollapsedNote(' \$CAP"
# ⚠ 收起态那句说明**必须**分出"被截过"这一支：框里那份的说明写在正文**末尾**，
#   收起之后正好看不到 —— 而"框里不是全部"这件事恰恰最不能靠翻找才发现。
c "⑥ 收起态的说明分「被截过 / 没截过」两支（说明在正文末尾，收起就看不见了）" \
  "grep -q 'if (clipped)' \$CAP"

echo ""
if [ "$bad" = "0" ]; then echo "=== 正文框收起接线：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 正文框收起接线：PASS $ok / FAIL $bad ==="; exit 1
