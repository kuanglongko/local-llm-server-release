#!/bin/sh
# `cpu_feat.c`（变体选择）与 `StderrPump.kt`（native stderr 接日志）的守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一份（Issue #174 / 模块丙-1：零覆盖的两处）
# ═══════════════════════════════════════════════════════════════════════════
# 这两处此前**零判据、零单测**，而它们的坏法都是"零症状"的：
#
#   `cpu_feat.c` —— 决定加载哪个 rnllama 变体。位常量写错一位、或 AT_HWCAP /
#   AT_HWCAP2 的来源写反，结果都是"探测某一位恒 0/恒 1"：App 照常启动、照常推理，
#   只是**静默选到慢变体**（或在不支持的机上选到会 SIGILL 的快变体）。
#   没有异常、没有日志，只有"这台机器怎么这么慢"这种说不清的体感。
#
#   `StderrPump.kt` —— 把 native stderr 接进日志管线。native 崩溃前最关键那句
#   （`ggml_abort: ...`）只写 fd 2，接不上就**看不到**；而接法错了更坏：
#   读端一旦停止排空，pipe 缓冲写满后 native 线程会**永久阻塞在 write(2)** ——
#   卡死比崩溃更难查（现场没有崩溃记录，只是"卡住了"）。
#
# 所以钉三件：位常量/槽位顺序（行为单测）、排空与 sink 异常隔离（结构判据）、
# 以及"必须早于 native init"这条时序。
#
# 运行：sh tools/run_cpu_feat_guard.sh
set -e
cd "$(dirname "$0")/.."
CF=app/src/main/cpp/cpu_feat.c
SP=app/src/main/java/com/xiaowan/localinference/StderrPump.kt
LE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
TEST=tools/cpu_feat/cpu_feat_test.c
CXX=${CC:-g++}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "cpu_feat.c 存在"      "[ -f \$CF ]"
c "StderrPump.kt 存在"   "[ -f \$SP ]"
c "行为单测源存在"        "[ -f \$TEST ]"

# ── 1) 位常量：真源与单测**逐字一致** ────────────────────────────────────
# 只判"真源里出现过 HWCAP2_I8MM"会假绿 —— 单测自己另写一份常量时，
# 两边**一起改错**照样绿。所以要求真源里的常量表达式与单测里的**同一个**。
c "真源 HWCAP_ASIMDDP 是 bit 20（uapi 值）" \
  "grep -q 'HWCAP_ASIMDDP (1UL << 20)' \$CF"
c "真源 HWCAP2_I8MM 是 bit 13（uapi 值）" \
  "grep -q 'HWCAP2_I8MM (1UL << 13)' \$CF"
c "单测里的两个常量与真源同值（两边一起改错也拦得住）" \
  "grep -q 'HWCAP_ASIMDDP  (1UL << 20)' \$TEST && grep -q 'HWCAP2_I8MM    (1UL << 13)' \$TEST"

# ── 2) 取值来源：dotprod 读 AT_HWCAP、i8mm 读 AT_HWCAP2 ──────────────────
# 写反的后果同上（一位恒 0），且**编译期无感**。判据锚"哪一位取自哪个变量"。
c "dotprod 取自 AT_HWCAP（h1）" \
  "grep -q 'out\\[0\\] = (h1 & HWCAP_ASIMDDP)' \$CF"
c "i8mm 取自 AT_HWCAP2（h2）" \
  "grep -q 'out\\[1\\] = (h2 & HWCAP2_I8MM)' \$CF"
c "两次 getauxval 分别读 AT_HWCAP / AT_HWCAP2" \
  "grep -q 'getauxval(AT_HWCAP)' \$CF && grep -q 'getauxval(AT_HWCAP2)' \$CF"

# ── 3) 槽位顺序是接口：Kotlin 侧按 c[0]/c[1] 分工 ─────────────────────────
# 顺序对调 = dotprod 与 i8mm 判据整个反过来，而两边都"看起来对"。
c "native 返回 4 个槽（dotprod/i8mm/hwcap/hwcap2）" \
  "grep -q 'jlong out\\[4\\]' \$CF"
c "Kotlin 侧 c[0] 当 dotprod、c[1] 当 i8mm（与 native 槽位一致）" \
  "grep -q 'val dotprod = c != null && c\\[0\\] == 1L' \$LE && \
   grep -q 'val i8mm = c != null && c\\[1\\] == 1L' \$LE"
c "raw 两个槽被回填（日志靠它印位数）" \
  "grep -q 'out\\[2\\] = (jlong)h1' \$CF && grep -q 'out\\[3\\] = (jlong)h2' \$CF"

# ── 4) StderrPump：必须早于 native init 调用 ─────────────────────────────
# 晚于 init 就接不住初始化阶段的报错 —— 而"没接住"与"初始化没报错"同形。
c "StderrPump 有幂等 started 闸（重复 start 不重复改 fd 2）" \
  "grep -q 'if (started) return' \$SP"
c "只重定向 fd 2（不动 stdout）" \
  "grep -q 'STDERR_FD = 2' \$SP && ! grep -q 'STDOUT\\|fd 1' \$SP"

# ── 5) StderrPump：排空与 sink 异常隔离（卡死方向的判据）─────────────────
# sink 抛异常时必须继续排空 —— 否则 pipe 写满、native 线程永久阻塞在 write(2)。
# 判据锚"sink 调用被 runCatching 包住"，而不是"sink 出现过"。
c "sink 调用被 runCatching 包住（sink 抛异常不致管道阻塞）" \
  "grep -q 'runCatching { sink(line) }' \$SP"
c "整段 start 被 runCatching 包住（失败退回原行为）" \
  "grep -q 'val ok = runCatching {' \$SP"
c "start 失败时 started 复位（允许后续再试）" \
  "grep -q 'if (!ok) started = false' \$SP"
c "read 失败/EOF 时退出循环（不在 -1 上空转）" \
  "grep -q 'if (n <= 0) break' \$SP"
c "行内残留超长时也冲刷（崩溃时写一半不留死缓存）" \
  "grep -q 'pending.length > 4096' \$SP"

# ── 6) 行为单测：编译并运行 ────────────────────────────────────────────────
# 缺编译器时按 REQUIRED 退化。**条数必须与环境无关** —— 否则本地缺 g++ 时少一条、
# CI 里多一条，`suite-counts.json` 就跟着漂移（判据⑤ 立刻红）。
# 所以这条判据**恒记一条**：编译+运行成功记 PASS，缺编译器且允许退化记 SKIP（同样计入 ok），
# 编译报错或单测断言失败记 FAIL。
if ! command -v "$CXX" >/dev/null 2>&1; then
  if [ "${REQUIRED:-1}" = "0" ]; then
    echo "SKIP  cpu_feat 行为单测（缺 $CXX，REQUIRED=0 允许退化）"
    ok=$((ok+1))
  else
    echo "FAIL  cpu_feat 行为单测：缺 $CXX"
    bad=$((bad+1))
  fi
elif "$CXX" -x c -O1 -Wall -Wextra -o /tmp/cpu_feat_test "$TEST" 2>/tmp/cpu_feat_cc.log; then
  if /tmp/cpu_feat_test; then
    echo "PASS  cpu_feat 行为单测（编译并运行）"
    ok=$((ok+1))
  else
    echo "FAIL  cpu_feat 行为单测：断言失败"
    bad=$((bad+1))
  fi
else
  echo "FAIL  cpu_feat 行为单测：编译失败"
  head -5 /tmp/cpu_feat_cc.log | sed 's/^/        ↳ /'
  bad=$((bad+1))
fi

echo
echo "=== cpu_feat / StderrPump 守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
