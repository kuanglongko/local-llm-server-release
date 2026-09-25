#!/bin/sh
# 「HTTP 监听生命周期」的**接线**守卫（模块 A：循环 / 线程 / 生命周期）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠复刻测试不够
# ═══════════════════════════════════════════════════════════════════════════
# `tools/run_http_lifecycle_tests.sh` 证明的是"**这套结构**在多线程交错下是活的"，
# 它证明不了"**仓库里的 HttpApi 用的就是这套结构**" —— 复刻与实现可以各走各的。
# 本轮四条失效又恰好都是**零症状**的（稳态下 100% 通过）：
#
#   A-1 listener 的 `finally` 无条件清 `running` / `server`：`running`/`server` 是
#       object 级共享状态，而 finally 是**每个 listener 线程各写一次、且原来不看代际**。
#       看门狗重启路径里的 `Thread.sleep(500)` 是**赌**旧线程能在窗口内走完 finally。
#       赌输 → 新 listener 的状态被旧线程踩掉 → 新 accept 循环下一轮看到 running=false
#       就退出：现场是「端口在 LISTEN，没人 accept」，且 server 被清成 null 后
#       `stop()` 关不掉那个仍在监听的 socket → 重启 `BindException`（SO_REUSEADDR
#       只救 TIME_WAIT，救不了"对端仍在 LISTEN"）→ 服务**永久不可用**。
#   A-2 watchdog 唯一闸门写成 `if (!running.get()) continue`：A-1 一旦踩中，
#       此后每一次都走这条 continue —— 设计目标"断联 30~60s 自愈"实际是"永久躺平"。
#   A-3 503 分支 `busy.set(false)` 在 CAS **失败**时清的是**别人**的占位：
#       背压失效（本应被拒的请求被放进来挂在 genLock 上）+ `/health` 的 busy 说谎。
#   A-4 `stop()` 只关 listening socket：在途连接由各自线程的 `s.use{}` 才释放，
#       后果是"服务已停，仍在为已停的请求把整段响应写完"。
#
# 这四条的共同点是"读代码时每一句都成立"，所以判据必须锚定**结构关系**
# （谁在什么条件下清状态 / 闸门看哪个标志 / 判据有几种组合 / 有没有登记表），
# 而不是存在性（"文件里出现过 listenerGen"）。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_http_lifecycle_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
HTTP=$SRC/HttpApi.kt
ACT=$SRC/EngineActivity.kt
SVC=$SRC/InferenceService.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 取函数体：从 `private fun NAME` / `fun NAME` 起，到第一个**顶格 4 空格**的 `}` 止 ──
# 为什么不用 `awk '/fun x/,/^    \}/'`：那个范围会被**后面**任何顶格 `}` 截断错了位，
# 而 Kotlin 的函数体里到处是缩进更深的 `}`。取函数体是本轮判据的地基，
# 取错了会让"该红的红"变成"恒真"（桩测会当场抓到）。
guard_fn() {
    awk -v name="$1" '
        $0 ~ ("fun " name "\\(") { infn=1 }
        infn { print; if ($0 == "    }") exit }
    ' "$HTTP"
}
# accept 循环体：从 `while (running.get())` 起，到 `http-conn` 线程起完为止。
# 不能用"到某个 `}` 为止"那种范围（Kotlin 函数体里到处是缩进更深的 `}`，
# 会被提前截断 —— 这个 helper 第一版就栽在这上面，判据于是恒假）。
guard_accept_body() {
    awk '/^                while \(running.get\(\)\) \{/ { inb=1 }
         inb { print; if (index($0, "\"http-conn\"") > 0) exit }' "$HTTP"
}

# ── 0) 三件状态必须是**三个不同的东西**（合并任意两个都丢一种语义）────────────
c "desired（用户意图）独立于 running（实际状态）" \
  "grep -q 'private val desired = AtomicBoolean(false)' \$HTTP"
c "listener 代际号是具名状态" \
  "grep -q 'private val listenerGen = java.util.concurrent.atomic.AtomicInteger(0)' \$HTTP"
c "在途连接登记表存在（A-4）" \
  "grep -q 'private val activeConns: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()' \$HTTP"

# ── 1) A-1：finally **必须先比代际**，无条件清必须绝迹 ───────────────────────
# 判据是"清状态那两行被代际号**包住**"，不是"文件里出现过 listenerGen"。
c "listener 的 finally 先比代际再清共享状态" \
  "awk '/if \(listenerGen.get\(\) == myGen\) \{/{f=NR} f&&/running.set\(false\)/{print (NR<=f+3) ? \"OK\" : \"\"; exit}' \$HTTP | grep -q OK"
c "「无条件清」旧写法已绝迹（它是僵尸 LISTEN 复发 + 服务永久死亡的根因）" \
  "! awk '/try \{ ss\?\.close\(\) \} catch \(_: Exception\) \{\}/{f=NR} f&&/^                running.set\(false\)/{print \"BAD\"; exit}' \$HTTP | grep -q BAD"
c "start() 先取代际号（每轮 listener 各一份）" \
  "grep -q 'val myGen = listenerGen.incrementAndGet()' \$HTTP"
c "accept 循环每轮都查代际（作废的循环不许再用已关闭的 socket accept）" \
  "grep -q 'if (listenerGen.get() != myGen) break' \$HTTP"
c "迟到退出必须留痕（症状指向错方向的那类必须有日志）" \
  "grep -q '迟到退出：已被 gen=' \$HTTP"
c "listener 线程名带代际（日志里能分辨两轮线程）" \
  "grep -q 'name = \"llm-http-\$myGen\"' \$HTTP"

# ── 2) A-2：看门狗闸门看 desired，不看 running ──────────────────────────────
c "watchdog 闸门是 desired（用户意图）" \
  "grep -q 'if (!desired.get()) { failStreak = 0; continue }' \$HTTP"
c "只看 running 的死闸门已绝迹（它就是"永久躺平"的根因）" \
  "! grep -q 'if (!running.get()) { failStreak = 0; continue }' \$HTTP"
c "重启判据收敛到唯一一处（restartListenerIfDesired）" \
  "grep -q 'private fun restartListenerIfDesired(reason: String): Boolean' \$HTTP"
c "重启入口先查 desired（用户已停服不许自动拉起）" \
  "awk '/private fun restartListenerIfDesired/{f=NR} f&&/if \(!desired.get\(\)\) return false/{print (NR<=f+8) ? \"OK\" : \"\"; exit}' \$HTTP | grep -q OK"
c "重启时把 desired 补回来（stop() 会清它；不补则"只死一次"）" \
  "awk '/private fun restartListenerIfDesired/{f=NR} f&&/desired.set\(true\)/{print \"OK\"; exit}' \$HTTP | grep -q OK"
c "watchdog 与重启入口是同一处判据（不许内联第二份）" \
  "grep -q 'if (restartListenerIfDesired(' \$HTTP"

# ── 3) A-3：503 的**两条**判据分开，回滚只回滚自己那份 ───────────────────────
# 判据锚定结构：CAS 失败那条分支里**不许**出现 busy.set(false)（它在清别人的占位）。
c "CAS 失败分支不回滚 busy（那会儿 busy 属于别人）" \
  "! awk '/if \(!busy.compareAndSet\(false, true\)\) \{/{f=NR} f&&/busy.set\(false\)/{print (NR<=f+2) ? \"BAD\" : \"\"; exit}' \$HTTP | grep -q BAD"
c "CAS 与「引擎被占用」被拆成两条 if" \
  "[ \$(grep -c 'if (!busy.compareAndSet(false, true)) {' \$HTTP) -eq 2 ] && [ \$(grep -c 'if (LlmEngine.isGenerating) {' \$HTTP) -eq 2 ]"
c "「引擎被占用」那条负责回滚自己刚抢到的那份" \
  "awk '/if \(LlmEngine.isGenerating\) \{/{f=NR} f&&/busy.set\(false\)/{print (NR<=f+2) ? \"OK\" : \"\"; exit}' \$HTTP | grep -q OK"
c '旧的 compareAndSet 与 isGenerating 复合写法已绝迹' \
  "! grep -q 'compareAndSet(false, true) || LlmEngine.isGenerating' \$HTTP"

# ── 4) A-4 在途连接登记 + 连接数上限：登记/计数**同源**，stop() 真的用它们 ──────
#
# 锚点说明（连接上限那一轮起）：登记不再是 accept 循环里的裸 `activeConns.add(s)`，
# 而是收进 admitConn（占坑 + 登记）与 releaseConn（摘除 + 减计数），
# 因为「计数」与「登记表」必须同源增减 —— 各自增减会出现"计数泄漏 → 永久拒连"，
# 那种失效零症状、只在长时间运行后显形。所以判据从"某行出现过"改成锚**结构关系**。
c "受理路径经 admitConn（受理判据只此一处）" \
  "grep -q 'private fun admitConn(s: Socket?): Boolean' \$HTTP"
c "accept 循环里不许裸 add（计数与登记表不许分家）" \
  "! guard_accept_body | grep -q 'activeConns.add'"
c "admitConn 里占坑成功后才登记，且登记**可达**（不许挪到 return 之后）" \
  "guard_fn admitConn | awk '/compareAndSet\(cur, cur \+ 1\)/{c=NR} /activeConns.add/{a=NR} /return true/{r=NR} END{ if (c>0 && a>c && r>0 && a<r) print \"OK\" }' | grep -q OK"
c "releaseConn 以 remove 的返回值减计数（stop 清表后不许重复减）" \
  "guard_fn releaseConn | grep -q 'if (s != null \&\& activeConns.remove(s)) connCount.decrementAndGet()'"
c "连接线程 finally 走 releaseConn（摘除与减计数同源）" \
  "grep -q 'releaseConn(s)' \$HTTP"
c "stop() 收敛在途连接（用快照，边遍历边改会漏关或重复减）" \
  "guard_fn stop | grep -q 'for (c in activeConns.toList())'"
c "stop() 收敛时同步减计数（只关不递减 = 计数泄漏 → 永久拒连）" \
  "guard_fn stop | grep -q 'if (activeConns.remove(c)) connCount.decrementAndGet()'"
# ── 4b) 连接数上限（本轮新增）────────────────────────────────────────────────
c "上限是具名常量（不是散落的魔数）" \
  "grep -q 'private const val MAX_CONNS = 64' \$HTTP"
c "受理判据在**起线程之前**（受理后补救会漏过并发窗口）" \
  "guard_accept_body | awk '/if \(!admitConn\(s\)\)/{a=NR} /http-conn/{b=NR} END{ if (a>0 && b>0 && a<b) print \"OK\" }' | grep -q OK"
c "超限拒绝回可读 503（不得静默 close —— 客户端会看到 Empty reply）" \
  "guard_fn rejectOverLimit | grep -q 'writeRaw(out, 503, body'"
c "超限拒绝必须关 socket（不关 = fd 随拒绝次数线性泄漏）" \
  "guard_fn rejectOverLimit | grep -q 's.close()'"
c "超限拒绝不占 http-conn 线程槽（拒绝路径不许再起线程）" \
  "! guard_fn rejectOverLimit | grep -q 'Thread('"
c "被拒计数作为诊断视图存在" \
  "grep -q 'val rejectedConnCount: Int get() = rejectedConns.get()' \$HTTP"
c "每次超限拒绝都自增计数（否则被上限拒的日志与背压拒同形）" \
  "guard_fn rejectOverLimit | grep -q 'rejectedConns.incrementAndGet()'"
c "计数不许停在负数（负值会让上限失真）" \
  "guard_fn stop | grep -q 'if (cur >= 0) break'"
# 上限的**前提**：连接有读超时。没有它，64 条"连上就不发数据"的连接会永久占满
# 容量 —— 那时上限就从"保护"变成"拒绝服务面"（合法客户端也进不来）。
# 这条断言不是"soTimeout 存在"（那种恒真），而是"它非零且是个具体上限"。
c "在途连接上限的前提：handleConn 有非零读超时（否则满员容量不会自愈）" \
  "awk '/private fun handleConn/{f=NR} f && /s.soTimeout = [1-9][0-9_]*/ { print \"OK\"; exit }' \$HTTP | grep -q OK"
c "读超时不许被改成 0 / 无（0 = 永不超时 = 满员后永久拒绝服务）" \
  "! awk '/private fun handleConn/{f=NR} f && /s.soTimeout = 0/ { print \"BAD\"; exit }' \$HTTP | grep -q BAD"
c "stop() 递增代际（让在途 listener 的 finally 自动作废）" \
  "guard_fn stop | grep -q 'listenerGen.incrementAndGet()'"
c "stop() 先置 desired=false（意图先声明，再动状态）" \
  "guard_fn stop | head -4 | grep -q 'desired.set(false)'"

# ── 5) 启动路径：desired 与代际必须在**起线程之前**就位 ──────────────────────
# 反例：先起线程再置 desired，会有一个窗口里 listener 已在跑而 watchdog 判"用户没要求"。
c "start() 在起线程前置 desired" \
  "awk '/fun start\(\)/{f=NR} f&&/desired.set\(true\)/{print (NR<=f+4) ? \"OK\" : \"\"; exit}' \$HTTP | grep -q OK"
c "start() 在起线程前取代际号" \
  "awk '/fun start\(\)/{f=NR} f&&/val myGen = listenerGen.incrementAndGet\(\)/{print (NR<=f+5) ? \"OK\" : \"\"; exit}' \$HTTP | grep -q OK"
# start() 的幂等闸门仍在（两处并发 start 只许起来一个 listener）
c "start() 仍以 running CAS 做幂等闸门" \
  "grep -q 'if (!running.compareAndSet(false, true)) return' \$HTTP"

# ── 6) 对外视图：状态与意图分开暴露（UI 不该靠"猜"）──────────────────────────
c 'isDesiredRunning 与 isRunning 分开暴露（语义不同，不许混用）' \
  "grep -q 'val isDesiredRunning: Boolean get() = desired.get()' \$HTTP && grep -q 'val isRunning: Boolean get() = running.get()' \$HTTP"
# 判据锚定结构：`/health` 的 JSON 段里不许出现 activeConnCount（内部读数不当契约）；
# 但它**应该**作为诊断视图存在于 object 上。两件事必须分开判，别写成一条或式。
c "在途连接数不进 /health 契约（它是内部读数，不是协议承诺）" \
  "! awk '/private fun healthJson\(\)/,/^    \}/' \$HTTP | grep -q activeConnCount"
c "在途连接数作为诊断视图存在" \
  "grep -q 'val activeConnCount: Int get() = activeConns.size' \$HTTP"

# ── 7) 注释里不许写 /v1/* 那类会被当成嵌套块注释开头的 /* ─────────────────────
# Kotlin 不支持嵌套块注释：文档注释里写 `/*` 会把后面整份文件"注释掉"，
# 编译报 Unclosed comment。本项目已在 ApiAuth.kt / WebChatPage.kt 各踩一次。
c "HttpApi 注释里没有嵌套块注释开头（别写 /v1/* 那种）" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$HTTP"

echo ""
if [ "$bad" = "0" ]; then echo "=== 生命周期接线守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 生命周期接线守卫：PASS $ok / FAIL $bad ==="; exit 1
