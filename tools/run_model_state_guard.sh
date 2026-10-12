#!/bin/sh
# 「选中」与「驻留内存」是两件事 —— 模型状态的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（Issue #228）
# ═══════════════════════════════════════════════════════════════════════════
# 用户加载了 a，又想切到 b，于是点了一下列表里的 b —— 但还没点「加载模型」。
# 此时内存里**仍然是 a**，而 App 的状态行、列表标记都在说 b。这不是"显示不全"，
# 是**显示与现场相反**：用户据此以为 a 已经让位，进而判断"切模型已经好了"
# 或"内存已经降下来了"，两份结论都错，而且**没有任何一行读数会纠正他**。
#
# 根因是三个字段谁都不代表"在内存里"：
#   · `modelFile`（本页）—— 用户**选中**的文件，点一下列表就变；
#   · `HttpApi.currentModel` —— 协议里的**别名**，改个别名也会变；
#   · `LlmEngine.loadedPath` —— **唯一**代表"此刻真驻留在内存"的那一个。
# 而状态的画法取了前两者：`modelTv` 写的是"当前: <选中名>"，列表只标 `●` 选中态。
#
# 所以判据一律锚**取数来源**与**两态可分辨**，不锚"某函数存在"：
#   ① 驻留的判据只能是 `LlmEngine.loadedPath`（不得拿 modelFile / currentModel 顶替）；
#   ② 状态行与列表必须**同时**报选中与驻留，且两者不同时看得出来（同一行里出现两个词）；
#   ③ 驻留值变了必须**重画**（加载 / 卸载 / 别名之外的服务侧卸载三条路径都要覆盖）——
#      否则标记会停在旧的那一行上，"看着还在内存里"照样成立；
#   ④ 列表的两个标记不得互相顶替（选中态曾把驻留态挤掉，就是本 issue 的本体）。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_model_state_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
SVC=$SRC/InferenceService.kt
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

# ── 1) 驻留的判据只有一处：LlmEngine.loadedPath ─────────────────────────────
LOADED=$(body 'private fun loadedFile\(\)' "$ACT")
c "loadedFile() 存在（驻留判据的单一入口，锚点未漂）" "[ -n \"\$LOADED\" ]"
c "驻留判据取自 LlmEngine.loadedPath（引擎真值）" \
  "printf '%s' \"\$LOADED\" | grep -q 'LlmEngine\.loadedPath'"
# 反向：这个入口若改问 modelFile / currentModel，就退回本 issue 的现场。
c "驻留判据不得改问本页选中态（modelFile）" \
  "! printf '%s' \"\$LOADED\" | grep -q 'modelFile'"
c "驻留判据不得改问协议别名（HttpApi.currentModel）" \
  "! printf '%s' \"\$LOADED\" | grep -q 'currentModel'"

# ── 2) 状态行必须**同时**报两态，且不同时看得出来 ───────────────────────────
STATE=$(body 'private fun modelStateText\(\)' "$ACT")
c "modelStateText() 存在（状态行文案单一入口，锚点未漂）" "[ -n \"\$STATE\" ]"
c "状态行同时引用选中态（modelFile）与驻留态（loadedFile()）" \
  "printf '%s' \"\$STATE\" | grep -q 'modelFile' && printf '%s' \"\$STATE\" | grep -q 'loadedFile()'"
# "不一致要看得出来"的硬判据：文案里必须出现一个**只说驻留**的措辞，
# 且存在一条专门给"两者不同"的分支（否则两态同时被报也可能读成一件事）。
c "状态行有「已加载」字样（只说驻留的那一半）" \
  "printf '%s' \"\$STATE\" | grep -q '已加载'"
c "状态行有不一致分支（内存里仍是 <另一份>）" \
  "printf '%s' \"\$STATE\" | grep -qE '内存里仍是|才.{0,4}真正切换'"
# 状态行不得退化成"只报选中"（旧写法：`当前: <文件名> (MB)`）。
n "状态行不再退化成只报选中（旧的「当前:」写法应绝迹——代码行）" \
  "awk '/private fun modelStateText\(\)/,/^    \}/' \$ACT | grep -v '^[ 	]*//' | grep -qE '\"当前: '"

# ── 3) 列表标记：选中与驻留**各自独立**，不得互相顶替 ───────────────────────
RND=$(body 'private fun renderModelList\(' "$ACT")
c "renderModelList() 存在（锚点未漂）" "[ -n \"\$RND\" ]"
c "列表渲染接收驻留参数（loaded）" \
  "printf '%s' \"\$RND\" | grep -q 'loaded: String?'"
c "列表渲染同时算 isSel 与 isLoaded（两个独立问句）" \
  "printf '%s' \"\$RND\" | grep -q 'isSel =' && printf '%s' \"\$RND\" | grep -q 'isLoaded ='"
c "行标随 isLoaded 变化（驻留态有自己的标记）" \
  "printf '%s' \"\$RND\" | grep -qE 'if \(isLoaded\)|isLoaded ->|isSel && isLoaded'"
c "驻留在内存的行有专属说明（写明未切换 / 正在用）" \
  "printf '%s' \"\$RND\" | grep -qE '在内存里|正在用'"
# 反向：行标不得再只由 isSel 决定（旧写法 `if (isSel) "● " else "○ "`）。
n "行标不再只由选中态决定（旧写法应绝迹——代码行）" \
  "printf '%s' \"\$RND\" | grep -v '^[ 	]*//' | grep -qE '\\(if \\(isSel\\) \"● \" else \"○ \"\\)'"

# ── 4) 驻留值变了必须重画：三条路径都要覆盖 ─────────────────────────────────
LOAD=$(body 'private fun doLoad\(\)' "$ACT")
c "加载成功后重画状态行（updateModelTv）" \
  "printf '%s' \"\$LOAD\" | grep -q 'updateModelTv()'"
c "加载成功后重画列表（refreshModelList）" \
  "printf '%s' \"\$LOAD\" | grep -q 'refreshModelList()'"
CPU=$(body 'private fun doLoadCpuOnly\(\)' "$ACT")
c "纯 CPU 加载路径同样重画状态行" \
  "printf '%s' \"\$CPU\" | grep -q 'updateModelTv()'"
c "纯 CPU 加载路径同样重画列表" \
  "printf '%s' \"\$CPU\" | grep -q 'refreshModelList()'"
UNL=$(body 'private fun doUnload\(\)' "$ACT")
c "卸载后重画状态行" \
  "printf '%s' \"\$UNL\" | grep -q 'updateModelTv()'"
c "卸载后重画列表" \
  "printf '%s' \"\$UNL\" | grep -q 'refreshModelList()'"
# 服务侧卸载（InferenceService.ACTION_STOP）不会走本页任何回调 ——
# 只能靠轮询发现驻留值变化，这一条是本 issue 里最容易漏的路径。
POLL=$(body 'private fun startServerPoll\(\)' "$ACT")
c "轮询里比较驻留值快照（loadedPath vs shownLoadedPath）" \
  "printf '%s' \"\$POLL\" | grep -q 'LlmEngine\.loadedPath' && printf '%s' \"\$POLL\" | grep -q 'shownLoadedPath'"
c "发现变化后重画状态行与列表" \
  "printf '%s' \"\$POLL\" | grep -q 'updateModelTv()' && printf '%s' \"\$POLL\" | grep -q 'refreshModelList()'"
# 快照必须在**所有**重画路径上被更新。做法是把更新放进 updateModelTv() ——
# 它是重画状态行的单一入口，散在各调用点漏一处就会退化成"轮询多画一次"（无害）
# 或漏报一次变化（有害，正是要防的）。写入点**允许两处**：这里 + 轮询里的比较，
# 后者是判据本身。第三处就说明有人绕开入口自己改快照，那时两套口径必然漂移。
c "updateModelTv() 内更新快照（重画状态行的单一入口）" \
  "awk '/private fun updateModelTv\(\)/,/^    \}/' \$ACT | grep -q 'shownLoadedPath = '"
c "快照的写入点不超过两个（入口 + 轮询比较，多出来就是绕开入口）" \
  "[ \$(grep -c 'shownLoadedPath = ' \$ACT) -le 2 ]"
c "快照字段是 String?（null 留给从未取过，不用来代表「未加载」）" \
  "grep -q 'private var shownLoadedPath: String? = null' \$ACT"

# ── 5) 防"顺手统一"：引擎侧的真值源与协议侧别名不许被合并 ──────────────────
c "引擎仍以 currentPath 记驻留（真值源没被搬到 HttpApi）" \
  "grep -qE 'val loadedPath: String\? get\(\) = if \(loaded\) currentPath else null' \$ENG"
# Service 在 ACTION_STOP 里卸载模型：这是本 issue 里"UI 之外改驻留值"的那条路径，
# 它必须仍然存在（否则第 4 节的轮询判据就没有存在理由，判据会退化成恒真）。
n "ACTION_STOP 仍会在服务侧卸载模型（轮询判据的存在前提）" \
  "! awk '/ACTION_STOP ->/,/return START_NOT_STICKY/' \$SVC | grep -q 'LlmEngine\.unload()'"

echo ""
if [ "$bad" = "0" ]; then echo "=== 模型状态守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 模型状态守卫：PASS $ok / FAIL $bad ==="; exit 1
