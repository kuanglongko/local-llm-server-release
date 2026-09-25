#!/bin/sh
# 「UI / 服务 / 存储」的主线程与资源守卫（模块 I 之 PR-2：I-2 / I-3 / I-4 / I-5）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这四条为什么不能靠"加个 grep"就完事
# ═══════════════════════════════════════════════════════════════════════════
# 四条的共同形态是「代码在那儿、但没起作用」：
#   I-2 `refreshModelList` 在**主线程**上逐行去问 prefs / stat —— 循环体里的
#       IO 次数随模型数线性增长（N=50 时可达数百毫秒）。取数与渲染没分开。
#   I-3 `File(ctx.getExternalFilesDir(null), child)`：外部存储不可用时前者返回
#       **null**，而 `File(File?, String)` 在 parent==null 时退化成
#       `File(child)` —— 一个**相对进程 CWD** 的路径。不抛、不返 null，静默改语义。
#   I-4 `LogFileStore` 的折叠状态（fileBase/fileRep/noiseRun/noiseSample）读-改-写
#       全在 `synchronized(lock)` **之外**，而 append 会被四个线程并发调用 ——
#       而它产出的 `×N` 与「省略 N 条」**唯一用途就是给人对账**。
#   I-5 `ioExecutor` 每次 onCreate 现建、`onDestroy` 从不 shutdown —— 每次重建
#       留一个线程，且任务都捕获了 this。
#
# 所以判据一律锚**结构关系**，不锚"某某函数/字段存在"：
#   - I-2：`refreshModelList` **函数体**里不得出现 prefs / stat 类调用；取数必须
#          发生在 `ioExecutor.execute` 之内，渲染函数必须是另一个函数。
#   - I-3：源码里不得出现 `getExternalFilesDir(...)` 直接作为 `File(parent, ...)`
#          的第一实参；唯一允许的写法是**显式处理 null**（见 ModelStore.modelsDir）。
#   - I-4：这四个字段的**每一次**读写都必须落在 `synchronized(lock)` 区段内。
#   - I-5：`onDestroy` 必须 shutdown ioExecutor；且两处必须成对（建了就关）。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_ui_io_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
LFS=$SRC/LogFileStore.kt
EXT=$SRC/ExternalModel.kt
MS=$SRC/ModelStore.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（从签名行到下一个同缩进 `}`）。
# 用 awk 按大括号配平，避免"函数里还有嵌套函数"时截断。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }

# ── 1) I-2：refreshModelList 主线程不得做按模型数线性增长的 IO ──────────────
RB=$(body 'private fun refreshModelList\(\)' "$ACT")
c "refreshModelList() 存在（锚点未漂）" "[ -n \"\$RB\" ]"
c "I-2: refreshModelList 函数体里不再直接问 prefs（aliasOf/isExternal/getSharedPreferences）" \
  "! printf '%s' \"\$RB\" | grep -qE 'getSharedPreferences|aliasOf\(|isExternal\('"
c "I-2: refreshModelList 函数体里不再 list() / listFiles 逐项扫（取数已外移）" \
  "! printf '%s' \"\$RB\" | grep -qE 'ModelStore\.list\(|listFiles'"
c "I-2: 取数发生在 ioExecutor 上（不是主线程）" \
  "printf '%s' \"\$RB\" | grep -q 'ioExecutor.execute'"
c "I-2: 取数走一次算完的 ModelStore.rows()（不是逐行问）" \
  "printf '%s' \"\$RB\" | grep -q 'ModelStore\.rows('"
# 渲染与取数必须是两个函数：合成一个就必然有一步在错误的线程上
c "I-2: 渲染是独立函数 renderModelList（取数/渲染分离）" \
  "grep -q 'private fun renderModelList(' \$ACT"
RND=$(body 'private fun renderModelList\(' "$ACT")
c "I-2: renderModelList 函数体里不含任何 prefs / 取数调用" \
  "! printf '%s' \"\$RND\" | grep -qE 'getSharedPreferences|aliasOf\(|isExternal\(|ModelStore\.rows\(|ModelStore\.list\('"
c "I-2: renderModelList 只消费预取字段（bytes/external/alias/missing 至少各用一次）" \
  "printf '%s' \"\$RND\" | grep -q 'r\.bytes' && printf '%s' \"\$RND\" | grep -q 'r\.external' && printf '%s' \"\$RND\" | grep -q 'r\.alias' && printf '%s' \"\$RND\" | grep -q 'r\.missing'"
# 代际号：连点两次时先发后回会让列表闪回旧选中态
c "I-2: 过期结果不得落地（渲染前比代际号）" \
  "printf '%s' \"\$RB\" | grep -q 'modelListGen' && printf '%s' \"\$RB\" | grep -qE 'myGen == modelListGen'"
c "I-2: ModelStore.rows() 一次 getAll（不是逐键 getString）" \
  "awk '/fun rows\(ctx: Context\)/,/^    \}/' \$MS | grep -q 'prefs\.all'"
c "I-2: ModelStore.rows() 不得逐行再调 aliasOf/isExternal" \
  "! awk '/fun rows\(ctx: Context\)/,/^    \}/' \$MS | grep -qE 'aliasOf\(|isExternal\('"
c "I-2: Row 携带渲染所需全部字段（取数一次算完的硬前提）" \
  "grep -q 'data class Row(' \$MS && awk '/data class Row\(/,/\)/' \$MS | grep -q 'bytes: Long'"

# ── 2) I-3：getExternalFilesDir 为 null 时不得静默变相对路径 ────────────────
# 唯一允许的形态：显式 `?:` 兜底（ModelStore.modelsDir），或 `?.let`（App.kt）。
# 禁止的是把它**直接**当 File(parent, child) 的第一实参 —— 那种写法在 null 分支
# 下不报错、只是路径变成相对 CWD。
c "I-3: 全仓不得把 getExternalFilesDir(...) 直接当 File(parent, child) 的第一实参" \
  "! grep -rnE 'File\([^,)]*getExternalFilesDir' \$SRC --include='*.kt'"
c "I-3: modelsDir 显式处理 null（?: 兜底到 filesDir）" \
  "awk '/fun modelsDir\(ctx: Context\)/,/^    \}/' \$MS | grep -qE 'getExternalFilesDir\(null\) \?:'"
c "I-3: modelsDir 返回前保证目录存在（mkdirs，且不再依赖相对路径）" \
  "awk '/fun modelsDir\(ctx: Context\)/,/^    \}/' \$MS | grep -q 'mkdirs()'"
# ⚠ 必须排注释：修法说明里就写着 `getExternalFilesDir`，算进代码行会恒假触发。
ADD_CODE="awk '/fun add\(ctx: Context/,/^    \}/' \$EXT | grep -v '^[ \t]*//'"
c "I-3: ExternalModel.add 的去重判据改用 modelsDir（不再自己拼 getExternalFilesDir）" \
  "! $ADD_CODE | grep -q 'getExternalFilesDir' && $ADD_CODE | grep -q 'ModelStore\.modelsDir(ctx)'"
# LogExport 是既有的正确写法，钉住它别被"统一改回去"
c "I-3: LogExport 对 getExternalFilesDir 的结果仍判 null（既有正确写法不许退回去）" \
  "awk '/viaAppExternalDir|getExternalFilesDir/,/^    \}/' \$SRC/LogExport.kt | grep -qE '\?:'"

# ── 3) I-4：折叠状态与噪音计数必须在同一把锁里读改写 ────────────────────────
# 判据不是"有 synchronized"，而是"**这四处字段的每一次读写**都在锁区段内"。
c "I-4: append() 的折叠读改写整段在 synchronized(lock) 之内" \
  "awk '/fun append\(line: String\)/,/^    \}/' \$LFS | awk '/synchronized\(lock\)/{ins=1} ins' | grep -q 'fileRep++'"
c "I-4: append() 不得在锁外先算 out（旧写法的本体）" \
  "! awk '/fun append\(line: String\)/,/^    \}/' \$LFS | awk '/synchronized\(lock\)/{exit} /fileRep|fileBase/{print \"OUTSIDE\";exit}' | grep -q OUTSIDE"
c "I-4: flushNoise() 的取数与清零在同一临界区（旧写法中间无独占）" \
  "awk '/fun flushNoise\(\)/,/^    \}/' \$LFS | grep -q 'synchronized(lock)'"
c "I-4: flushNoise() 里 noiseRun 的清零也必须在锁内（不是先读后清零再出锁）" \
  "awk '/fun flushNoise\(\)/,/^    \}/' \$LFS | grep -qE 'n = noiseRun' && awk '/fun flushNoise\(\)/,/^    \}/' \$LFS | grep -q 'noiseRun = 0'"
c "I-4: noteNoise() 的计数与样例在同一临界区" \
  "awk '/fun noteNoise\(line: String\)/,/^    \}/' \$LFS | grep -q 'synchronized(lock)'"
c "I-4: noteNoise 不得在锁外先判 noiseRun==0（旧写法：判与设之间被抢占）" \
  "! awk '/fun noteNoise\(line: String\)/,/^    \}/' \$LFS | awk '/synchronized\(lock\)/{exit} /noiseRun/{print \"OUTSIDE\";exit}' | grep -q OUTSIDE"
# init 重置折叠状态：必须与写入路径用**同一把**锁，否则等于没锁
c "I-4: init() 用 lock 而不是 this 做监视器（与 append 同一把）" \
  "awk '/fun init\(context: android.content.Context\)/,/^    \}/' \$LFS | grep -q 'synchronized(lock)'"
c "I-4: init() 不再用 @Synchronized（那是 this，与 lock 不是同一把）" \
  "! awk 'NR>1 && /@Synchronized/ {p=NR} /fun init\(context/{if(p==NR-1) print \"THISLOCK\"}' \$LFS | grep -q THISLOCK"
c "I-4: init() 里重置 fileBase/fileRep 落在锁区段内" \
  "awk '/fun init\(context: android.content.Context\)/,/^    \}/' \$LFS | awk '/synchronized\(lock\)/{ins=1} ins' | grep -q 'fileBase = null; fileRep = 0'"

# ── 4) I-5：ioExecutor 建了就必须关 ─────────────────────────────────────────
c "I-5: onDestroy 里 shutdown ioExecutor（建了就关）" \
  "awk '/override fun onDestroy\(\)/,/^    \}/' \$ACT | grep -q 'ioExecutor.shutdownNow()'"
c "I-5: 用 shutdownNow 而不是 shutdown（Activity 没了，队列里排着的没意义）" \
  "awk '/override fun onDestroy\(\)/,/^    \}/' \$ACT | grep -q 'ioExecutor.shutdownNow()'"
c "I-5: ioExecutor 仍是 Activity 私有实例（不是进程级单例，故必须关）" \
  "awk '/private val ioExecutor = Executors\.newSingleThreadExecutor\(\)/{print \"OK\"}' \$ACT | grep -q OK && ! awk '/object .*ioExecutor|companion object.*ioExecutor/{print \"SHARED\"}' \$ACT | grep -q SHARED"
# 关掉之后不得再往里提交（提交会抛 RejectedExecutionException）；回调里也不许用
c "I-5: 已 shutdown 的 executor 不得再被提交任务（执行体里无 ioExecutor.execute）" \
  "! awk '/override fun onDestroy\(\)/,/^    \}/' \$ACT | grep -q 'ioExecutor.execute'"
# 其它路径仍应走 ioExecutor（别为了"少个线程"把 IO 挪回主线程）
c "I-5: 其余重 IO 路径仍走 ioExecutor（confirmDelete / probe / import 至少 3 处）" \
  "[ \$(grep -c 'ioExecutor.execute' \$ACT) -ge 3 ]"

# ── 4b) I-6：本页「停止」是一次性意图，生命周期是**一轮** ───────────────────
# 判据不是"有 stopRequested = false 这一行"，而是那行**在哪儿**：
#   · 必须在 `synchronized(genLock)` **之内**（锁外清零会与主线程点停止交错）；
#   · 必须在 `startCompletion` **之前**（反过来写的那段窗口恰好是整段 prefill）；
#   · 且 `doGenerate` 里**恰好一次**（说明它在"每轮开跑"这条路径上，不是散在收尾处）。
# 三条一起才等价于"生命周期=一轮"。少任何一条，表现都是 Issue #154：
# 按过一次停止之后，本轮循环第一判就走 false —— 零 token、无异常、无日志。
GENBODY=$(body 'private fun doGenerate\(\)' "$ACT")
c "I-6: doGenerate() 存在（锚点未漂）" "[ -n \"\$GENBODY\" ]"
c "I-6: 每轮开跑时把上一轮的停止意图清零（stopRequested = false）" \
  "printf '%s' \"\$GENBODY\" | grep -q 'stopRequested = false'"
c "I-6: 清零是**每轮一次**（恰好一处，不是散在收尾的多条 return 分支上）" \
  "[ \"\$(printf '%s' \"\$GENBODY\" | grep -c 'stopRequested = false')\" = 1 ]"
c "I-6: 清零落在 synchronized(genLock) 之内（锁外清零会与按钮回调交错）" \
  "printf '%s' \"\$GENBODY\" | awk '/synchronized\(LlmEngine.genLock\)/{ins=1} ins' | grep -q 'stopRequested = false'"
# 注释里也会出现 `startCompletion` 这个词（位置说明本来就写着它），必须排掉：
# 不排的话下面这条会拿**注释**当锚点 —— 退化成"注释里提到过"的存在性断言，恒真。
GEN_CODE=$(printf '%s' "$GENBODY" | grep -v '^[ 	]*//')
c "I-6: 清零落在 startCompletion **之前**（先有标志再动生成）" \
  "printf '%s' \"\$GEN_CODE\" | grep -n 'stopRequested = false\\|startCompletion' | sort -t: -k1,1n | head -1 | grep -q 'stopRequested'"
# 反向判据：字段本身必须仍是"每轮清零"的那一个，不能被换成常量/只读。
c "I-6: stopRequested 仍是可变字段（不是 val / 不是常量）" \
  "grep -qE '@Volatile private var stopRequested = false' \$ACT"
# 置位方仍在（把清零加回来时最容易顺手把"停止"也删了）。
c "I-6: 停止按钮仍置位 stopRequested（清零不等于停用）" \
  "grep -q 'stopRequested = true' \$ACT"

# ── 5) 防"顺手统一"的交叉判据 ───────────────────────────────────────────────
c "EngineActivity 注释里没有嵌套块注释开头" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$ACT"
c "ModelStore 注释里没有嵌套块注释开头" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$MS"
c "LogFileStore 注释里没有嵌套块注释开头" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$LFS"

echo ""
if [ "$bad" = "0" ]; then echo "=== UI/IO 守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== UI/IO 守卫：PASS $ok / FAIL $bad ==="; exit 1
