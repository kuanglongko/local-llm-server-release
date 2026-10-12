#!/bin/sh
# 线程与资源的**生命周期**守卫（Issue #174 / 模块乙-4）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一份
# ═══════════════════════════════════════════════════════════════════════════
# 这一族的失效方向是"**只涨不跌**"——而它**与正确顺序同形**：
#
#   ① `beginCancelable` 摘除没在 `finally` 里 —— 正常路径摘得掉，异常路径漏掉，
#      留下一个"永远在跑"的僵尸轮次；之后所有 `/v1/abort` 都打在不存在的对象上。
#      零症状：HTTP 照常 200，只是"取消不好使了"。
#   ② 基础设施线程（HTTP 连接 / watchdog / listener / stderr 泵）**不是 daemon** ——
#      Service 停止后 JVM 被非守护线程吊住不退出，进程"看起来还活着"。
#   ③ `ioExecutor` 不 shutdown —— 每次 Activity 重建各留一个线程，且任务 lambda
#      捕获 `this`，等于可观测的 Activity 泄漏。
#
# 这三条此前**没有任何判据**（`beginCancelable`/`endCancelable` 只在注释里被提过）。
# 判据锚**归属与位置**（在不在 finally 里、是不是 daemon、有没有 shutdown），
# 不锚"文件里出现过 endCancelable"。
#
# 运行：sh tools/run_resource_lifecycle_guard.sh
set -e
cd "$(dirname "$0")/.."
HA=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
SP=app/src/main/java/com/xiaowan/localinference/StderrPump.kt
IS=app/src/main/java/com/xiaowan/localinference/InferenceService.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "HttpApi.kt 存在"      "[ -f \$HA ]"
c "EngineActivity.kt 存在" "[ -f \$EA ]"
c "StderrPump.kt 存在"   "[ -f \$SP ]"
c "InferenceService.kt 存在" "[ -f \$IS ]"

# ── 1) beginCancelable / endCancelable：数量配对 + 摘除在 finally 里 ───────
# 两个方向都判：少一个 end = 僵尸轮次；配对但不在 finally = 异常路径漏摘。
for f in "$HA" "$EA"; do
    base=$(basename "$f")
    b=$(grep -c 'beginCancelable()' "$f")
    e=$(grep -c 'endCancelable(' "$f")
    c "$base：begin/end 数量配对（$b/$e）" "[ \"$b\" = \"$e\" ]"
done

# 「在 finally 里摘」的判据：每个 endCancelable 都**身处**某个 finally 块内。
# 锚"在不在 finally 里"而不是"文件里有没有" —— 后者会假绿：把摘除挪到 try 末尾时，
# 正常路径照常摘得掉、异常路径漏掉（僵尸轮次），而文件里 endCancelable 一次不少。
# 走 python（awk 的块边界在嵌套花括号上不可靠，本守卫第一版就因此假红）。
c "HttpApi：每个 endCancelable 都在 finally 块内" \
  "\$PY tools/resource_lifecycle/check_finally.py \$HA"
c "EngineActivity：每个 endCancelable 都在 finally 块内" \
  "\$PY tools/resource_lifecycle/check_finally.py \$EA"

# ── 2) 基础设施线程必须是 daemon ──────────────────────────────────────────
# 这些线程的存在只为服务持续运行；Service 停后若还吊着，进程不会退。
# 判据锚"该 Thread 的 apply 里带 isDaemon = true"，逐处判（不是"文件里有 isDaemon"）。
c "HTTP 连接线程是 daemon（'http-conn'）" \
  "grep -q '\"http-conn\").apply { isDaemon = true }' \$HA"
c "listener 线程是 daemon（'llm-http-'）" \
  "grep -q '\"llm-http-\$myGen\"; isDaemon = true' \$HA"
c "watchdog 线程是 daemon（'llm-watchdog'）" \
  "grep -q '\"llm-watchdog\"; isDaemon = true' \$HA"
c "stderr 泵线程是 daemon（'stderr-pump'）" \
  "grep -q 'isDaemon = true; name = \"stderr-pump\"' \$SP"
c "健康探测线程是 daemon（'health-probe'）" \
  "grep -q '\"health-probe\"; isDaemon = true' \$EA"
# 反例：HttpApi 里每个 `Thread {` / `Thread({` 创建点都必须带 isDaemon ——
# 判据锚"某处 Thread 创建没带 isDaemon"，而不是"文件里有 isDaemon"。
# 数字直接数不出来（有的走 .apply、有的走链式），所以交给 python 逐处核。
c "HttpApi 每个 Thread 创建点都标了 daemon（逐处核，无裸 start）" \
  "\$PY tools/resource_lifecycle/check_thread_daemon.py \$HA"

# ── 3) ioExecutor 必须在 onDestroy 里 shutdown ────────────────────────────
# 每次 onCreate 现建 executor，不关就随每次重建各留一个线程 + 捕获 this 的泄漏。
c "ioExecutor 是每次现建（不是静态/单例）" \
  "grep -q 'private val ioExecutor = Executors.newSingleThreadExecutor()' \$EA"
c "onDestroy 里 shutdownNow（不关 = 每次重建各留一个线程）" \
  "grep -q 'ioExecutor.shutdownNow()' \$EA"
c "shutdown 排在 super.onDestroy() 之前（否则与已拆视图抢资源）" \
  "awk '/ioExecutor.shutdownNow\\(\\)/{if(!s)s=NR} /super.onDestroy\\(\\)/{if(!d)d=NR} END{exit !(s && d && s < d)}' \$EA"

echo
echo "=== 线程与资源生命周期守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
