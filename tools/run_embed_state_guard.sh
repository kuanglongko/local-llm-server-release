#!/bin/sh
# 「选中」与「驻留内存」是两件事 —— **嵌入侧**的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（Issue #228 的下一句）
# ═══════════════════════════════════════════════════════════════════════════
# `run_model_state_guard.sh` 把**对话**那一份的"选中 vs 驻留"钉住了，29 条全绿。
# 而用户点开界面看到的仍然是反话 —— 因为设置页里**还有第二张模型表**：
# 嵌入模型列表与它自己的状态行。那一份是另写的，没跟着改：
#   · `renderEmbedList` 只标 `●` 选中态（`embedFile`），没有驻留那一半；
#   · `renderEmbedStatus` 只报 `已选 <选中名>`，并在同一行带上 `n_embd` ——
#     于是"我选了 b、内存里还是 a"被读成"嵌入模型：b（768）"。
# 而**检索实际用的是内存里那份 a**。这与对话侧修掉的是同一个病，
# 只是它躲在另一张表里，而"对话侧守卫全绿"恰好让这件事看起来已经解决了。
#
# 所以判据与对话侧**逐条同构**，只换对象（`embedFile` / `embedPath` / `renderEmbed*`）：
#   ① 驻留的判据只能是 `LlmEngine.embedPath`（不得拿 embedFile 顶替）；
#   ② 状态行必须**同时**报选中与驻留，且两者不同时看得出"检索用的是哪一份"；
#   ③ 列表的两个标记不得互相顶替（`●` 曾把 `▶` 挤掉）；
#   ④ 驻留值变了必须重画（加载 / 卸载 / 轮询三条路径）——
#      轮询那一条与对话侧同一处，只是比较的是 `embedPath`。
#
# 另有一条**跨表一致性**判据（第 5 节）：两张表的行标措辞必须同形。一条写
# `▶●`、另一条写 `★` 时，用户没法把两张表放在一起读 —— 而这两张表读的
# 本来就是同一件事的两半（哪个在内存、哪个只是我点的）。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_embed_state_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
ENG=$SRC/LlmEngine.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }
n() { if eval "$2"; then echo "FAIL  $1"; bad=$((bad+1)); else echo "PASS  $1"; ok=$((ok+1)); fi; }

# 取 kotlin 函数体（按大括号配平，避免嵌套函数截断）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    a=gsub(/\{/,"{"); b=gsub(/\}/,"}"); depth+=a-b
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }

c "EngineActivity.kt 存在（锚点未漂）" "[ -f \$ACT ]"

# ── 1) 驻留的判据只有一处：LlmEngine.embedPath ─────────────────────────────
LOADEDE=$(body 'private fun loadedEmbedFile\(\)' "$ACT")
c "loadedEmbedFile() 存在（嵌入驻留判据的单一入口，锚点未漂）" "[ -n \"\$LOADEDE\" ]"
c "嵌入驻留判据取自 LlmEngine.embedPath（引擎真值）" \
  "printf '%s' \"\$LOADEDE\" | grep -q 'LlmEngine\.embedPath'"
# 反向：这个入口若改问 embedFile，就退回本 issue 的现场（"我以为选了就算装上了"）。
c "嵌入驻留判据不得改问本页选中态（embedFile）" \
  "! printf '%s' \"\$LOADEDE\" | grep -q 'embedFile'"
# `hasEmbedModel` 只回答"有没有"，不回答"是哪一个" —— 拿它当驻留判据等于
# 把"装的是 b 还是 a"重新变成一个查不到的问题（这正是旧状态行干的事）。
c "嵌入驻留判据不得只问 hasEmbedModel（它答不出「哪一份」）" \
  "! printf '%s' \"\$LOADEDE\" | grep -q 'hasEmbedModel'"

# ── 2) 嵌入状态行必须**同时**报两态，且不同时看得出来 ───────────────────────
STATE=$(body 'private fun renderEmbedStatus\(\)' "$ACT")
c "renderEmbedStatus() 存在（锚点未漂）" "[ -n \"\$STATE\" ]"
c "状态行同时引用选中态（embedFile）与驻留态（loadedEmbedFile()）" \
  "printf '%s' \"\$STATE\" | grep -q 'embedFile' && printf '%s' \"\$STATE\" | grep -q 'loadedEmbedFile()'"
c "状态行有「已加载」与「已选中」两个措辞（两态各自成句）" \
  "printf '%s' \"\$STATE\" | grep -q '已加载' && printf '%s' \"\$STATE\" | grep -q '已选中'"
# "不一致要看得出来"的硬判据：必须点破"检索实际用的是哪一份"。
c "状态行有不一致分支（内存里仍是前者 / 检索用的仍是前者）" \
  "printf '%s' \"\$STATE\" | grep -qE '内存里仍是|检索用的仍是|才会真正切换'"
# 反向：旧写法把 n_embd 挂在一句 `已选 <选中名>` 上 —— 那正是"b（768）"的来源。
n "状态行不再把 n_embd 挂在选中态上（旧的「已选 X · n_embd」写法应绝迹——代码行）" \
  "awk '/private fun renderEmbedStatus\(\)/,/^    \}/' \$ACT | grep -v '^[ 	]*//' | grep -qE '已选 \\\$\{|\"已选 '"
# 这一条要判的是**绑定关系**（n_embd 那个变量是不是从驻留文件名派生出来的），
# 不是"同一行里有没有这两个词" —— 旧写法把 n_embd 挂在 `已选 <选中名>` 上时，
# 两个词照样同处一行。所以锚"n_embd 取自 loadedEmbedFile 派生的名字"。
c "n_embd 与驻留文件名在**同一句**里绑定（n 取自 loadedEmbedFile）" \
  "printf '%s' \"\$STATE\" | grep -qE 'loadedEmbedFile\\(\\)' && printf '%s' \"\$STATE\" | grep -qE 'n_embd=\\\$\{LlmEngine\\.embedDim\\}'"

# ── 3) 列表标记：选中与驻留**各自独立**，不得互相顶替 ───────────────────────
RNDE=$(body 'private fun renderEmbedList\(' "$ACT")
c "renderEmbedList() 存在（锚点未漂）" "[ -n \"\$RNDE\" ]"
c "列表渲染接收驻留参数（loaded）" \
  "printf '%s' \"\$RNDE\" | grep -q 'loaded: String?'"
c "列表渲染同时算 isSel 与 isLoaded（两个独立问句）" \
  "printf '%s' \"\$RNDE\" | grep -q 'isSel =' && printf '%s' \"\$RNDE\" | grep -q 'isLoaded ='"
c "行标随 isLoaded 变化（驻留态有自己的标记）" \
  "printf '%s' \"\$RNDE\" | grep -qE 'isSel && isLoaded|isLoaded ->'"
c "驻留在内存的行有专属说明（写明未切换 / 正在用）" \
  "printf '%s' \"\$RNDE\" | grep -qE '在内存里|正在用'"
# 反向：行标不得再只由 isSel 决定（旧写法 `if (isSel) "● " else "○ "`）。
n "行标不再只由选中态决定（旧写法应绝迹——代码行）" \
  "printf '%s' \"\$RNDE\" | grep -v '^[ 	]*//' | grep -qE '\\(if \\(isSel\\) \"● \" else \"○ \"\\)'"
# 取快照的时机：必须在**提交任务时**，不能留到渲染回调里现取（同 refreshModelList）。
REFDE=$(body 'private fun refreshEmbedList\(\)' "$ACT")
c "取数时先快照驻留值（loadedEmbedFile），不在渲染回调里现取" \
  "printf '%s' \"\$REFDE\" | grep -q 'loadedEmbedFile()'"

# ── 4) 驻留值变了必须重画：三条路径都要覆盖 ─────────────────────────────────
DOL=$(body 'private fun doLoadEmbed\(\)' "$ACT")
c "加载成功后重画状态行（renderEmbedStatus）" \
  "printf '%s' \"\$DOL\" | grep -q 'renderEmbedStatus()'"
c "加载成功后重画列表（refreshEmbedList）" \
  "printf '%s' \"\$DOL\" | grep -q 'refreshEmbedList()'"
DUN=$(body 'private fun doUnloadEmbed\(\)' "$ACT")
c "卸载后重画状态行" \
  "printf '%s' \"\$DUN\" | grep -q 'renderEmbedStatus()'"
c "卸载后重画列表" \
  "printf '%s' \"\$DUN\" | grep -q 'refreshEmbedList()'"
# 轮询：与对话侧同一处，比较的是 embedPath。
POLL=$(body 'private fun startServerPoll\(\)' "$ACT")
c "轮询里比较嵌入驻留值快照（embedPath vs shownEmbedPath）" \
  "printf '%s' \"\$POLL\" | grep -q 'LlmEngine\.embedPath' && printf '%s' \"\$POLL\" | grep -q 'shownEmbedPath'"
c "发现变化后重画嵌入状态行与列表" \
  "printf '%s' \"\$POLL\" | grep -q 'renderEmbedStatus()' && printf '%s' \"\$POLL\" | grep -q 'refreshEmbedList()'"
# 快照写入点：状态行渲染里一处 + 轮询比较一处，第三处就是绕开入口自己改快照。
c "renderEmbedStatus() 内更新快照（重画状态行的单一入口）" \
  "printf '%s' \"\$STATE\" | grep -q 'shownEmbedPath = '"
c "快照的写入点不超过两个（入口 + 轮询比较，多出来就是绕开入口）" \
  "[ \$(grep -c 'shownEmbedPath = ' \$ACT) -le 2 ]"
c "快照字段是 String?（null 留给从未取过，不用来代表「未加载」）" \
  "grep -q 'private var shownEmbedPath: String? = null' \$ACT"

# ── 5) 两张表必须读得像同一件事（跨表一致性）──────────────────────────────
# 判据锚"标注措辞"而不是"有没有某函数"：两张表各写一套自己的 `★` / `▲`
# 时，断言全绿而用户读不出来 —— 那正是本仓库最不想留下的那类绿灯。
RNDC=$(body 'private fun renderModelList\(' "$ACT")
# ⚠ 这两条只断言"两处都出现了 ▶●"是不够的 —— 它**不阻止**嵌入表把行标改成
# `★`，只要旧措辞还留在别处（注释、别的分支）。改 `▶` 时这一条就静默恒绿。
# 所以先钉一行**锚**：两个函数体里都必须出现同一组三态判据（`isSel && isLoaded`
# 与单独的 `isLoaded` 两支），再钉措辞。锚的是"判据同形"，措辞只是它的呈现。
c "两张表的行标都由同一组三态判据派生（isSel&&isLoaded / isLoaded 两支）" \
  "for B in \"\$RNDC\" \"\$RNDE\"; do printf '%s' \"\$B\" | grep -q 'isSel && isLoaded ->' && printf '%s' \"\$B\" | grep -qE '^ +isLoaded ->' || exit 1; done"
c "对话表与嵌入表都出现三态行标 ▶●（同形）" \
  "printf '%s' \"\$RNDC\" | grep -q '\"▶● \"' && printf '%s' \"\$RNDE\" | grep -q '\"▶● \"'"
c "对话表与嵌入表都出现只驻留的 ▶ 标记（同形）" \
  "printf '%s' \"\$RNDC\" | grep -q '\"▶  \"' && printf '%s' \"\$RNDE\" | grep -q '\"▶  \"'"
c "对话表与嵌入表都出现仅选中的 ● 标记（同形）" \
  "printf '%s' \"\$RNDC\" | grep -q '\"●  \"' && printf '%s' \"\$RNDE\" | grep -q '\"●  \"'"

# ── 6) 防回归：真值源仍在引擎侧，且是路径（不是布尔）────────────────────────
c "引擎仍以 embedPath 记嵌入驻留（真值源没被搬进 UI）" \
  "grep -qE 'var embedPath: String\? = null' \$ENG"
c "embedPath 仍是 private set（外部不得直接改驻留值绕开加载路径）" \
  "grep -qE 'private set' \$ENG"

echo ""
if [ "$bad" = "0" ]; then echo "=== 嵌入状态守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 嵌入状态守卫：PASS $ok / FAIL $bad ==="; exit 1
