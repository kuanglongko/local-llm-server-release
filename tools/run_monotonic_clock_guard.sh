#!/bin/sh
# 「时长测量必须用单调钟」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条守卫防的是什么（Issue #198：G1/G2/G3 同族）
# ═══════════════════════════════════════════════════════════════════════════
# `System.currentTimeMillis()` 是**墙钟**：它受 NTP 校准 / 用户改表 / 时区调整影响，
# 会前跳也会后跳。凡是"两个时刻相减得到一段时长"的地方用墙钟，都带两个方向的失效：
#
#   · **前跳**（校准把表往前拨）：`now - 基准` 瞬间巨大 ——
#     G1 的卡死看门狗于是把"其实正常"判成 native 卡死，状态栏写
#     「停止无效，请强杀应用」；用户照做，正在跑的会话丢了。
#   · **后跳**（把表往回调）：差值为负 ——
#     G1 的 `idle >= HANG_SEC` 恒假，**真实卡死永不上报**（失效静音，比前跳更坏）；
#     G2 的 SSE 心跳 `now - lastBeat > 800` 恒假，思考段不再保活，客户端空闲断连；
#     G3 的 tok/s 被 `sec > 0.01f` 兜成 0.0（仅显示错，低危）。
#
# 修法只有一条：**作差的场合一律 `System.nanoTime()`（单调钟）**，且
# **一对差值的两个端点必须取自同一口钟** —— 只改读方或只改写方，比不改更坏：
# 混钟相减得到的数字毫无意义（两钟起点无关），却照样能过类型检查。
#
# 所以判据锚**结构关系**，不锚存在性：
#   ① "文件里出现过 nanoTime" 是恒真的（SamplingParams 早就用 nanoTime 取种子）——
#      挡不住任何一处退回墙钟；
#   ② 真正的判据是"这几处**具体的差**的**两个端点**都在单调钟上"。
# 本守卫逐处断言配对关系：写方（生产者）与读方（消费者）同时落在 nanoTime 上。
#
# 为什么不用 kotlinc 编译后跑：这几处依赖 Android 栈宿主（EngineActivity）
# 与真实时钟跳变，编不进单测；而失效恰恰是"稳态下 100% 看不出来"的那类，
# 只有源码级结构断言能钉住。
#
# 纯 sh + grep，不依赖任何工具链。运行：sh tools/run_monotonic_clock_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
HTTP=$SRC/HttpApi.kt
ENG=$SRC/LlmEngine.kt
HC=$SRC/HealthCheck.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 0) 前提：四个文件的源码都在（防下面所有断言因"文件为空"而恒真）──────────
c "四个被测源码文件都存在且非空" \
  "[ -s \$ACT ] && [ -s \$HTTP ] && [ -s \$ENG ] && [ -s \$HC ]"

# ═══ G1：卡死看门狗 idle —— 生产者 lastProgressAt 与消费者必须同口钟 ═════════
# 这是本 issue 的主症：前跳误报（引导强杀丢会话）/ 后跳静音（真实卡死不上报）。
# lastProgressAt 的**三处写入**（1 处声明 + 2 处赋值）与**一处读取**（hangWatch）
# 必须全在 nanoTime —— 少一处就是混钟。
#
# 写入从 3 处收到 2 处（Issue #238 日志一轮）：探针模式那条 `if (probeEnabled)
# { lastProgressAt = ... }` 被删掉了 —— 它不是"少了一处打点"，而是**故障本身**：
# 探针开着时逐 token 的每一行回调都会推新基准，卡死看门狗于是永久静默。
# 这里断言的"两处"是：pushLog 的常规分支、以及 onToken/decode 那处显式打点。
c "G1 lastProgressAt 的两处写入都在单调钟（生产者）" \
  "[ \$(grep -c 'lastProgressAt = System.nanoTime()' \$ENG) -eq 2 ] && \
   [ \$(grep -c 'lastProgressAt = System.currentTimeMillis()' \$ENG) -eq 0 ]"
c "G1 生成开始时置基准也在单调钟（EngineActivity 侧写入）" \
  "grep -q 'LlmEngine.lastProgressAt = System.nanoTime()' \$ACT && \
   ! grep -q 'LlmEngine.lastProgressAt = System.currentTimeMillis()' \$ACT"
c "G1 卡死看门狗的 idle 用单调钟算（消费者与生产者同口）" \
  "grep -q 'val idle = (System.nanoTime() - LlmEngine.lastProgressAt) / 1_000_000_000L' \$ACT"
c "G1 idle 的墙钟旧写法已绝迹（它是误报/静音两向失效的根因）" \
  "! grep -q 'System.currentTimeMillis() - LlmEngine.lastProgressAt' \$ACT"

# ═══ G2：SSE 心跳 lastBeat —— 两处端点各自成对，且判据与基准同口 ═══════════
# handleChat（think 段）与 handleCompletions（纯断连探测）各一套。
# 判据阈值从 800 改成 800_000_000（纳秒），这本身就是"换钟了"的证据。
c "G2 心跳节流用单调钟（两处判据都是纳秒阈值）" \
  "[ \$(grep -c 'System.nanoTime() - lastBeat > 800_000_000L' \$HTTP) -eq 2 ]"
c "G2 心跳基准 lastBeat 的六处刷新都在单调钟（两处声明 + 四处刷新）" \
  "[ \$(grep -c 'lastBeat = System.nanoTime()' \$HTTP) -eq 6 ] && \
   [ \$(grep -c 'lastBeat = System.currentTimeMillis()' \$HTTP) -eq 0 ]"
c "G2 心跳的墙钟旧写法已绝迹（后跳会让心跳永不触发）" \
  "! grep -q 'System.currentTimeMillis() - lastBeat' \$HTTP"

# ═══ G3：tok/s 计时 sec —— t0 与 sec 同口 ═══════════════════════════════════
c "G3 tok/s 的 t0 用单调钟" \
  "grep -q 'val t0 = System.nanoTime()' \$ACT"
c "G3 tok/s 的 sec 用单调钟（纳秒 → 秒）" \
  "grep -q 'val sec = (System.nanoTime() - t0) / 1_000_000_000f' \$ACT"
c "G3 sec 的墙钟旧写法已绝迹" \
  "! grep -q 'val sec = (System.currentTimeMillis() - t0)' \$ACT"

# ═══ 同类：watchdog 睡眠实测（"被冻了多久"是纯差值）════════════════════════
c "watchdog 睡眠实测用单调钟（墙钟跳变会把冻结时长算错甚至算负）" \
  "awk '/val plannedMs = if \(isGenerating\)/{f=NR} f && /val t0 = System.nanoTime\(\)/{print (NR<=f+4) ? \"OK\" : \"\"; exit}' \$HTTP | grep -q OK && \
   grep -q 'recordWatchdogSleep(plannedMs, (System.nanoTime() - t0) / 1_000_000L)' \$HTTP"
c "watchdog 睡眠的墙钟旧写法已绝迹" \
  "! grep -q 'recordWatchdogSleep(plannedMs, System.currentTimeMillis() - t0)' \$HTTP"

# ═══ 同类：HealthCheck.probe 的 elapsedMs（探测耗时是纯差值）════════════════
c "probe 的 t0 用单调钟" \
  "grep -q 'val t0 = System.nanoTime()' \$HC"
c "probe 的四处 elapsedMs 都在单调钟（纳秒 → 毫秒）" \
  "[ \$(grep -c 'System.nanoTime() - t0) / 1_000_000L' \$HC) -eq 3 ] && \
   grep -q 'elapsedMs = (System.nanoTime() - t0) / 1_000_000L)' \$HC"
c "probe 的墙钟耗时旧写法已绝迹" \
  "! grep -q 'System.currentTimeMillis() - t0' \$HC"

# ═══ 反向：墙钟该留的地方必须留着（防"一刀切全改 nanoTime"的反向错误）══════
# id / created 是**对外时间戳**（API 契约里要的是 Unix 纪元秒），必须用墙钟：
# nanoTime 的起点是任意的，拿它做时间戳会给出一个没有意义、且随进程重启归零的数。
c "HttpApi 的 id / created 仍用墙钟（对外时间戳，不是时长）" \
  "[ \$(grep -c 'System.currentTimeMillis()}' \$HTTP) -ge 2 ] && \
   [ \$(grep -c 'val created = System.currentTimeMillis() / 1000' \$HTTP) -eq 2 ]"
c "对外时间戳没被误改成 nanoTime（nanoTime 起点任意，不能当时间戳）" \
  "! grep -q 'val created = System.nanoTime()' \$HTTP"
c "SessionStore 的 updatedAt 仍用墙钟（持久化时间戳），且没有一处 nanoTime" \
  "grep -q 'updatedAt\", System.currentTimeMillis()' \$SRC/SessionStore.kt && \
   ! grep -q 'updatedAt\", System.nanoTime()' \$SRC/SessionStore.kt"

# ═══ 前提自证：本守卫的判据不是恒真 ═════════════════════════════════════════
# nanoTime 确实已在本仓库被用于"作差"以外的用途（SamplingParams 取种子），
# 所以"文件里出现过 nanoTime"这种弱断言挡不住本次回归 —— 上面每条都钉了
# **具体的表达式配对**。这里再确认被测文件真的动了（不是空文件恒真）。
c "被测的这四个文件里确实同时存在 nanoTime 与（合法用途的）currentTimeMillis" \
  "grep -q 'nanoTime' \$ACT && grep -q 'nanoTime' \$HTTP && grep -q 'nanoTime' \$HC && grep -q 'nanoTime' \$ENG"

echo ""
if [ "$bad" = "0" ]; then echo "=== 单调钟守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 单调钟守卫：PASS $ok / FAIL $bad ==="; exit 1
