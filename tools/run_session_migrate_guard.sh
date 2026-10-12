#!/bin/sh
# 「旧单会话迁移」的接线守卫（S2）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠"能编译 + 手点一下"不够
# ═══════════════════════════════════════════════════════════════════════════
# 这一批只有一个坏法，但它是本仓库最讨厌的那类 —— **错得不响**：
#
#   · 建不出空会话时迁移被挡（`createNoMigrate` 返回 null；S3 起触点是正文总量，
#     此前是会话数达 50），旧写法是 `?: return` —— **静默返回**，旧文件留在原地。
#     用户看到的是「我的旧聊天记录不见了」，而真相是"被上限挡住了"。
#     两者处置完全不同：一个是数据没了（没救），一个是删掉几个旧会话就好。
#
# 由此派生出四条必须一起钉住的判据，缺一条这个修复就退化成"看着像修了"：
#
#   ① **结局必须可区分**。塌成一个 `Unit`（或者只回 Boolean）就再也分不出
#      "没有旧文件"和"被上限挡住"，而这两者的处置相反：前者不该出声。
#   ② **没出声 != 出声**。`NO_LEGACY` 是**绝大多数用户**的常态，
#      对它出声 = 每次冷启动都弹一次"迁移失败"，用户被吵到会去删数据。
#      反向的坑同样致命：`BLOCKED_BY_LIMIT` 不出现 = 本轮白改。
#   ③ **结局只在一处认领**。`currentId()` 内部也调 `migrateIfNeeded`，
#      若读取路径也顺手报一次，会出现"删掉几个会话后切一下会话、
#      提示自己消失或又冒出来"，用户没法判断到底迁没迁。
#      顺序类不变量只有**行序判据**才不是恒真的。
#   ④ **失败时不许删旧文件**。`legacy.delete()` 是唯一不可逆的一步：
#      正文没落盘就删 = 把"迁移失败"变成"记录删除成功"。
#      判据锚"delete 在失败判据**之后**"，不是"文件里出现过 delete"。
#
# 判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 行序 / 在哪个函数体内），
# 不锚存在性：`enum class MigrateResult` 光"存在"什么都证明不了 ——
# 一个从不被认领的枚举与没有它等价。
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_session_migrate_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
SS=$SRC/SessionStore.kt
ACT=$SRC/EngineActivity.kt
TST=tools/session_migrate/SessionMigrateTest.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（与 run_session_cap_guard.sh / run_chat_bubble_guard.sh
# 的 body() 同一判据；三处各写一份取函数体的近似就是"判据漂移"的起点）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }

# 剥注释（判据文本在注释里也出现 —— 本文件的注释为了讲清"为什么"必然引用它们，
# 不剥会得到恒真的假绿）。
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

# 在某个函数体里取两个锚点出现的**行序**：前锚必须在后锚之前。
# 顺序类不变量的唯一有效判据 —— "两行都在"对上"位置换了"是恒真的。
before_in() { # 变量名 前锚正则 后锚正则 文件
    eval "_src=\$$1"
    printf '%s' "$_src" | nocomment | awk -v a="$2" -v b="$3" '
        !pa && $0 ~ a { pa=NR }
        !pb && $0 ~ b { pb=NR }
        END { exit !(pa && pb && pa < pb) }'
}

c "SessionStore.kt / EngineActivity.kt / 行为测试都存在" \
  "[ -f \$SS ] && [ -f \$ACT ] && [ -f \$TST ]"

# ── 1) 结局类型：必须分得出「没有旧文件」与「被上限挡住」 ───────────────────
c "① 迁移结局是具名类型，不是一个 Unit / Boolean" \
  "grep -q 'enum class MigrateResult {' \$SS"
# 防"枚举但只留一格"：三格必须同时在（少一格就塌回不可区分）。
c "① 四种结局都在：OK / NO_LEGACY / BLOCKED_BY_LIMIT / FAILED" \
  "grep -qE 'enum class MigrateResult \{ OK, NO_LEGACY, BLOCKED_BY_LIMIT, FAILED \}' \$SS"
c "① 上限与写入失败是**两格**，不合成一格（处置不同）" \
  "grep -q 'BLOCKED_BY_LIMIT' \$SS && grep -q 'FAILED' \$SS"

MIG=$(body 'fun migrateIfNeeded\(ctx: Context\): MigrateResult' "$SS")
c "migrateIfNeeded() 返回结局（锚点未漂）" "[ -n \"\$MIG\" ]"
c "① 没有旧文件时返回 NO_LEGACY（不是笼统的\"失败\"）" \
  "printf '%s' \"\$MIG\" | grep -q 'return MigrateResult.NO_LEGACY'"
c "① 拿不到空会话（撞上限）时返回 BLOCKED_BY_LIMIT" \
  "printf '%s' \"\$MIG\" | grep -q '?: return MigrateResult.BLOCKED_BY_LIMIT'"
c "① 旧写法 `?: return`（无返回值的静默返回）已绝迹" \
  "! printf '%s' \"\$MIG\" | nocomment | grep -qE 'createNoMigrate\(ctx\) \?: return\$'"
c "① 迁移成功后返回 OK" \
  "printf '%s' \"\$MIG\" | grep -q 'return MigrateResult.OK'"

# ── 2) 不可逆那一步：正文没落盘就不许删旧文件 ──────────────────────────────
c "④ 正文落盘失败时返回 FAILED（不当作迁移成功）" \
  "printf '%s' \"\$MIG\" | grep -q 'if (!ok) return MigrateResult.FAILED'"
# 判据是**行序**：delete 必须在"失败就返回"**之后**。
# 只 grep "文件里有 delete" 是恒真的 —— 挪到前面就变成了"失败也把旧记录删掉"。
c "④ 删旧文件排在失败判据**之后**（失败不删 = 不把迁移失败变成删除成功）" \
  "before_in MIG 'if \\(!ok\\) return MigrateResult.FAILED' 'legacy\\.delete\\(\\)' \$SS"
c "④ 删旧文件仍只在**成功路径**上（不是无条件执行）" \
  "printf '%s' \"\$MIG\" | nocomment | grep -q 'runCatching { legacy.delete() }'"

# ── 3) UI：结局只在一处认领，且两种结局的处置相反 ──────────────────────────
INIT=$(body 'private fun initSessions\(\)' "$ACT")
c "initSessions() 存在（迁移结局的**唯一**认领点）" "[ -n \"\$INIT\" ]"
# 判据必须锚在**认领那一次调用**上（`when (SessionStore.migrateIfNeeded(this))`），
# 不能只认"文件里出现过 BLOCKED_BY_LIMIT" —— 那个字符串在数据类注释与行为测试里
# 都有，于是"调用方吞掉结局、改去 when(0)"的桩会**假绿**（本自测的桩⑤就是这么
# 漏掉第一版的）。这里取 when 之后的那一段来判分支。
WHEN=$(body 'when \(SessionStore.migrateIfNeeded\(this\)\)' "$ACT")
c "② 结局认领处是一个 when 分支（锚点未漂）" "[ -n \"\$WHEN\" ]"
c "② 调用方按 BLOCKED_BY_LIMIT 分支（不是\"有 try/catch\"）" \
  "printf '%s' \"\$WHEN\" | grep -q 'SessionStore.MigrateResult.BLOCKED_BY_LIMIT ->'"
c "② 上限提示里报出**真实上限**而不是写死数字" \
  "printf '%s' \"\$INIT\" | grep -q 'SessionQuota.MAX_TOTAL_CHARS} 字符上限'"
c "② NO_LEGACY 与 OK 落在不出声的那一支（绝大多数用户不该被吵）" \
  "printf '%s' \"\$INIT\" | nocomment | grep -q 'SessionStore.MigrateResult.OK, SessionStore.MigrateResult.NO_LEGACY -> Unit'"
c "② FAILED 与 BLOCKED_BY_LIMIT **不是同一句提示**（处置不同）" \
  "printf '%s' \"\$INIT\" | grep -q 'SessionStore.MigrateResult.FAILED ->' && \
   printf '%s' \"\$INIT\" | grep -q '迁移失败（写入出错）'"
# 判据是**行序**：结局必须取自 initSessions 里那一次 when，而不是从 currentId 里猜。
c "③ initSessions 里先认领结局、再取当前 id" \
  "before_in INIT 'SessionStore.migrateIfNeeded\\(this\\)' 'SessionStore.currentId\\(this\\)' \$ACT"
# ③ 的正面判据：读取路径不得把结论偷出来单报（只允许取一次值）。
CID=$(body 'fun currentId\(ctx: Context\): String\?' "$SS")
c "③ currentId() 里那次迁移调用**独自取值**（不是赋值、不是分支参数）" \
  "printf '%s' \"\$CID\" | grep -qE '^[[:space:]]*migrateIfNeeded\\(ctx\\)[[:space:]]*\$'"
c "③ 结局的\"报出来\"只发生在 initSessions 一处（别处不得写 migrateHint）" \
  "test \"\$(grep -c 'migrateHint = ' \$ACT)\" = 2"
# 判据锚**调用点**而不是"谁在报"：这个函数有两个调用方（读取路径 + 认领处），
# 漏掉哪一个都会被上面几条判据放过去 —— 比如未来有人从别处再调一次
# migrateIfNeeded 并顺手按结局出声，上面那条"只写两次 migrateHint"照样绿。
# ⚠ 判据锚**调用形态**（`X.migrateIfNeeded(ctx)`），不锚"文件里出现过几次这个名字"：
# 后者把 `fun migrateIfNeeded(...)` 的定义本身与 KDoc 引用一起数进去，
# 数字是巧合凑出来的 —— 改一下注释就红，真多出第三处调用点却可能照样绿。
c "③ migrateIfNeeded 的**实际调用**只有两处（读取路径 + 唯一认领处），没有第三处" \
  "test \"\$(grep -rhoE 'migrateIfNeeded\\((ctx|this)\\)' \$SS \$ACT | wc -l)\" = 2"
c "③ 那两处调用点各在一个已知位置（不是随便哪个函数里）" \
  "printf '%s' \"\$CID\" | grep -qE '^[[:space:]]*migrateIfNeeded\\(ctx\\)[[:space:]]*\$' && \
   printf '%s' \"\$WHEN\" | grep -q 'BLOCKED_BY_LIMIT ->'"

# ── 4) 提示是常驻行，不是一次性 Toast ──────────────────────────────────────
c "② 提示是页面上的常驻行（不是 Toast 弹一次）" \
  "grep -q 'private var migrateHintTv: TextView? = null' \$ACT"
c "② 提示默认隐藏（没有异常时不许占位）" \
  "grep -q 'migrateHintTv = TextView(this).apply' \$ACT && \
   printf '%s' \"\$(body 'migrateHintTv = TextView\\(this\\)\.apply' \$ACT)\" | grep -q 'visibility = View.GONE'"
c "② 提示与标题**同一个渲染点**（分开刷会出现自相矛盾的画面）" \
  "printf '%s' \"\$(body 'private fun refreshSessionTitle\(\)' \$ACT)\" | grep -q 'migrateHintTv?\.let'"
c "② 提示为空的判据是 isNullOrEmpty（空串也要隐藏，不只判 null）" \
  "printf '%s' \"\$(body 'private fun refreshSessionTitle\(\)' \$ACT)\" | grep -q 'hint.isNullOrEmpty()'"
c "② 点一下直达会话管理（用户要做的就是删几个旧会话）" \
  "printf '%s' \"\$(body 'migrateHintTv = TextView\\(this\\)\.apply' \$ACT)\" | grep -q 'setOnClickListener { showSessionDialog() }'"
c "② 旧文件不删（下次启动重试）—— 提示文案里明说\"旧记录仍在\"" \
  "grep -q '旧记录仍在' \$ACT"

# ── 5) 行为测试与实现不得脱钩 ────────────────────────────────────────────
# 两侧都钉：只钉复刻侧时，把复刻改回旧写法、而实现里那一行仍在，判据照样绿。
c "行为测试复刻的结局分支与实现同形（两侧都钉，且复刻里不含旧写法的静默返回）" \
  "grep -q 'fun migrateNew' \$TST && grep -q 'MigrateResult.BLOCKED_BY_LIMIT' \$TST && \
   grep -q '?: return MigrateResult.BLOCKED_BY_LIMIT' \$SS && \
   ! printf '%s' \"\$(body 'private fun migrateNew' \$TST)\" | grep -q 'return false' && \
   printf '%s' \"\$(body 'private fun migrateOld' \$TST)\" | grep -q 'return false'"
# 判据是"那条断言比的是**整串相等**"，不是"出现过这几个字"：
# 退化成 `readText().isNotEmpty()` 时名字还在、断言还在，只是它再也证明不了什么。
c "行为测试钉住「失败时旧文件逐字节不变」这条核心不变量（判据是整串相等，不是非空）" \
  "grep -q '失败时旧文件仍在' \$TST && grep -q '== original2' \$TST && \
   ! grep -qE 'readText\\(\\)\\.isNotEmpty\\(\\)' \$TST && \
   ! grep -qE 'readText\\(\\)\\.isNotEmpty\\(\\)' \$SS"
c "行为测试钉住「失败时旧文件逐字节没变」而不是只判"文件还在"" \
  "grep -q '内容逐字节没变' \$TST && grep -q 'File(d2, \"chat_session.json\").exists()' \$TST"
c "行为测试保留了旧写法的对照（否则证明不了修复有效）" \
  "grep -q '对照：旧写法把三种结局塌成同一个静默返回' \$TST"
# 本仓库栽过一次同类：测试自己往工作区里落垃圾文件（`File.createTempFile` 建的是
# **文件**，`delete()` + `mkdirs()` 的写法建不出目录时会静默退化成"往文件里写"）。
# 判据锚"用的是临时目录 + mkdirs 有断言"，不是"文件里出现过 createTempFile"。
c "行为测试用临时目录且建目录失败会断言（不静默落垃圾到工作区）" \
  "grep -q 'System.getProperty(\"java.io.tmpdir\")' \$TST && \
   grep -q 'mkdirs()) { \"建不出临时目录' \$TST && \
   ! nocomment < \$TST | grep -q 'File.createTempFile'"

echo
echo "=== 会话迁移守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
