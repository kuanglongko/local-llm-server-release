#!/bin/sh
# 「会话配额：从个数换成总字符」的接线守卫（S3）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一步改的是**用户可见的口径**，而每个坏法都能"看着像修了"：
#
#   · **软提示线参与拒绝** —— 把 `MAX_SESSIONS_WARN` 也塞进 `decide`，
#     就退回成"用个数硬卡"的老问题（用户攒了几百个短会话时又被 200 卡住，
#     而他真正占用的空间很小）。提示线只许提示。
#   · **闸门挂到读路径上** —— 在 `list` / `load` 里也判配额，会让**已超限的
#     老库打不开**：用户陷入"打不开也删不掉"的死锁。闸门只许在**新增**路径上。
#   · **导入整批一起判** —— 尾部一条超限就把前面能收的也全丢（用户看到
#     "什么都没进来"）。必须按**预算从前往后**一条条收。
#   · **判据写死在 UI 里** —— `decide` / `acceptCount` 的逻辑若在 EngineActivity
#     里手写一遍，改上限时两处会漂移（提示说 2M、实际按 1M 拒）。
#
# 判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 在哪个函数体内），
# 不锚存在性：`const val MAX_TOTAL_CHARS` 光"存在"什么都证明不了 ——
# 一个从不被 `decide` 用到的常量与没有它等价。
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_session_quota_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
SS=$SRC/SessionStore.kt
QUOTA=$SRC/SessionQuota.kt
ACT=$SRC/EngineActivity.kt
TST=tools/session_quota/SessionQuotaTest.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（与 run_session_migrate_guard.sh 的 body() 同一判据）。
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

c "SessionQuota.kt / SessionStore.kt / EngineActivity.kt / 行为测试都存在" \
  "[ -f \$QUOTA ] && [ -f \$SS ] && [ -f \$ACT ] && [ -f \$TST ]"

# ── 1) 常量：硬闸门是字符，软提示是个数，且旧的个数闸门已绝迹 ──────────────
c "① 硬闸门是**总字符**常量 MAX_TOTAL_CHARS" \
  "grep -q 'const val MAX_TOTAL_CHARS' \$QUOTA"
c "① 旧的硬闸门 MAX_SESSIONS = 50 已绝迹（不再用个数硬卡）" \
  "! nocomment < \$SS | grep -qE 'const val MAX_SESSIONS[[:space:]]*=' && \
   ! nocomment < \$SS | grep -q 'MAX_SESSIONS\\b'"
c "① 软提示线是**另取的名字** MAX_SESSIONS_WARN" \
  "grep -q 'const val MAX_SESSIONS_WARN' \$QUOTA"

# ── 2) 软提示线不参与拒绝 ────────────────────────────────────────────────
# decide() 是**表达式体**（`): Decision =` + `Decision(...)`），body() 的括号计数
# 抓不住它（第一行就 depth==0 退了），所以取从签名到下一个 `fun ` 之间的一段。
DEC=$(awk '/fun decide\(usedChars: Long, incomingChars: Long\): Decision/{f=1} \
           f && /^    fun [a-zA-Z]/ && !/fun decide\(usedChars/{exit} f' "$QUOTA")
c "① decide() 存在（准入的唯一判据）" "[ -n \"\$DEC\" ]"
# 关键：判决式里**只能**出现 MAX_TOTAL_CHARS，不许 MAX_SESSIONS_WARN 掺进来。
c "① decide() 只拿字符上限判决，软提示线不参与（不退回个数硬卡）" \
  "printf '%s' \"\$DEC\" | nocomment | grep -q 'usedChars + incomingChars <= MAX_TOTAL_CHARS' && \
   ! printf '%s' \"\$DEC\" | nocomment | grep -q 'MAX_SESSIONS_WARN'"
c "① 会话数只用于 shouldWarnCount（提示），不用于判决" \
  "grep -q 'fun shouldWarnCount(sessionCount: Int): Boolean' \$QUOTA && \
   ! printf '%s' \"\$(body 'fun decide' \$QUOTA)\" | grep -q 'shouldWarnCount'"

# ── 2b) 零增长必须放行（否则顶到上限后连落脚点都没有）────────────────────
# ⚠ 这一条守的是本轮修掉的真缺陷：`used == 上限 && incoming == 0` 是
#   `create` / `createNoMigrate` **唯一会问**的那一格（新会话正文是 0）。
#   旧判决式 `used + incoming <= 上限` 在这一格判拒绝，后果是用户顶到上限之后
#   **再也建不出一个空会话**来整理旧内容 —— 而类注释第一条口径正是
#   "只卡新增，不卡已存在 …… 否则用户会陷入『打不开也删不掉』的死锁"。
#   判据锚的是**判决式里有没有那条放行支**，不是"文件里出现过 0"。
c "① 零增长必须放行（顶到上限后仍能建空会话整理，不是死锁）" \
  "printf '%s' \"\$DEC\" | nocomment | grep -q 'incomingChars <= 0L ||'"

# ── 3) 批量按预算从前往后收，遇阻即停 ────────────────────────────────────
AC=$(body 'fun acceptCount\(usedChars: Long, incoming: List<Long>\): Int' "$QUOTA")
c "① acceptCount() 存在（批量导入的预算判据）" "[ -n \"\$AC\" ]"
c "① 收不下就**停**，不是跳过它继续试后面（结果不随文件顺序变）" \
  "printf '%s' \"\$AC\" | nocomment | grep -q 'break' && \
   ! printf '%s' \"\$AC\" | nocomment | grep -qE 'continue'"

# ── 4) 闸门只挂在**新增**路径上，读路径不碰 ─────────────────────────────
CREATE=$(body 'fun create\(ctx: Context\): String\?' "$SS")
c "② create() 的拒绝判据走 SessionQuota.decide（不是比个数）" \
  "printf '%s' \"\$CREATE\" | grep -q 'SessionQuota.decide(usedChars(ctx), 0L).allowed'"
c "② createNoMigrate() 同样走 decide" \
  "printf '%s' \"\$(body 'fun createNoMigrate' \$SS)\" | grep -q 'SessionQuota.decide(usedChars(ctx), 0L).allowed'"
c "② 导入按预算算能收几条（从前往后）" \
  "printf '%s' \"\$(body 'fun importAll' \$SS)\" | grep -q 'SessionQuota.acceptCount(usedChars(ctx), sessions.map { charsOf(it) })'"
# 读路径绝不判配额：否则已超限的老库打不开（打不开也删不掉）。
c "② list()/load()/currentId() 里**没有**配额判据（老库超限也能打开）" \
  "! printf '%s' \"\$(body 'fun list\(ctx: Context\)' \$SS)\" | grep -q 'SessionQuota' && \
   ! printf '%s' \"\$(body 'fun load\(ctx: Context, id: String\)' \$SS)\" | grep -q 'SessionQuota' && \
   ! printf '%s' \"\$(body 'fun currentId\(ctx: Context\)' \$SS)\" | grep -q 'SessionQuota'"
# 判据锚**调用点**：只用 decide 与 acceptCount 两处出口，不许在别处手写算术。
c "② 配额算术只有两处出口（decide / acceptCount），别处不得手写 <= MAX_TOTAL_CHARS" \
  "! nocomment < \$SS | grep -q 'MAX_TOTAL_CHARS'"
c "② SessionStore 只经 SessionQuota 触达上限（转引常量即可，不重算）" \
  "grep -q 'SessionQuota.decide' \$SS && grep -q 'SessionQuota.acceptCount' \$SS"

# ── 5) UI：报出真实读数，且不含手写算术 ─────────────────────────────────
c "③ 新建被拒时报出**真实占用**（已用 X / 上限 Y），而不是"已达上限"" \
  "grep -q '已用 \$used/\${SessionQuota.MAX_TOTAL_CHARS} 字符' \$ACT"
c "③ 会话管理标题同时报「个数 + 字符占用」两样（只报个数会让用户以为能一直建）" \
  "grep -q '个 · \${used}/\${SessionQuota.MAX_TOTAL_CHARS} 字符' \$ACT"
c "③ 导入确认按 acceptCount 预告能收几条（不是写完再说）" \
  "grep -q 'SessionQuota.acceptCount(SessionStore.usedChars(this), incoming)' \$ACT"
c "③ UI 里不出现**写死的上限数字**（判据取自常量，避免两处漂移）" \
  "! nocomment < \$ACT | grep -qE '2_000_000|2000000'"

# ── 6) 纯逻辑文件不带 android 依赖（否则宿主行为测试编不出来）───────────
c "④ SessionQuota.kt 不 import android（可在宿主上直接单测）" \
  "! nocomment < \$QUOTA | grep -q 'import android'"

# ── 7) 行为测试与实现不得脱钩 ────────────────────────────────────────────
c "④ 行为测试钉住"刚好用满必须收下"这条闭区间边界" \
  "grep -q '刚好用满：允许' \$TST && grep -q 'decide(LIMIT - 1, 1L).allowed' \$TST"
c "④ 行为测试钉住"软提示线不参与拒绝"" \
  "grep -q '提示线不拦人' \$TST && grep -q 'decide(1000L, 0L).allowed' \$TST"
c "④ 行为测试钉住"遇阻即停"（不跳过去收后面的）" \
  "grep -q '不跳过去收第三条' \$TST && grep -q 'acceptCount(0L, listOf(10L, LIMIT, 10L)) == 1' \$TST"
c "④ 行为测试用真实现断言（SessionQuota 无 android 依赖，不须复刻）" \
  "grep -q 'SessionQuota.Max_TOTAL_CHARS\\|SessionQuota.MAX_TOTAL_CHARS' \$TST || \
   grep -q 'SessionQuota' \$TST"

echo
echo "=== 会话配额守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
