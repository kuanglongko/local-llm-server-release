package com.xiaowan.localinference

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// 「HTTP 监听生命周期」的离线复刻测试（模块 A 的 A-1 / A-2 / A-4）。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么不是"读代码就能定"的
// ═══════════════════════════════════════════════════════════════════════════
// 本轮命中的四条**全部只在多线程交错时出现，稳态下 100% 通过**：
//   · A-1 listener 的 finally 无条件清共享状态 —— 旧线程只要"慢"过一次
//     （新 listener 已接管、它才进 finally），就会把新 listener 踩掉；
//   · A-2 watchdog 只看 `running`，被踩之后永久躺平，设计目标"30~60s 自愈"
//     实际是"一旦踩中永不自愈"；
//   · A-4 stop() 只关 listening socket，在途连接不被收敛。
// 这三条的现场特征都是「端口在 LISTEN 但没人 accept」/「服务已停却仍在返回」——
// 与"网络问题"完全同形。对着好实现跑一万次都发现不了，必须**主动构造交错**。
//
// 所以这份测试不跑 HttpApi（它依赖 Android 栈，宿主编不过），而是把
// **真实的 start / stop / finally / watchdog 结构逐字复刻**成可运行程序，
// 再用真实的 socket 做交错。复刻的取舍写在 `Http` 的注释里。
//
// 依赖：仅 JDK（无 kotlinc 之外的东西）。运行：sh tools/run_http_lifecycle_tests.sh
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

/**
 * 修复后的结构。与 `HttpApi` 的对应关系（逐条对应，不是"大意相同"）：
 *   · [desired] / [running] 两个标志        <-> HttpApi.desired / running
 *   · [gen] 代际号 + `finally` 比代际        <-> HttpApi.listenerGen + start 的 finally
 *   · [stop] 递增代际 / 关当前 socket / 收敛在途 <-> HttpApi.stop
 *   · [restartIfDesired] 唯一的重启判据      <-> HttpApi.restartListenerIfDesired
 *
 * ⚠ 端口必须**先选定再释放**（`ServerSocket(0)` 取号 -> close -> 交给 listener）：
 * 若把取号的 socket 本体当成服务来收连接，它会**抢在 listener 之前** accept
 * （两个 socket 都能 accept 同一端口），连接被测试自己吞掉、listener 永远收不到。
 * 这是写这份复刻时真踩到的坑，留着注释以免下次再踩。
 */
private object Http {
    val desired = AtomicBoolean(false)
    val running = AtomicBoolean(false)
    val gen = AtomicInteger(0)

    @Volatile var server: ServerSocket? = null

    @Volatile var port = 0

    /** 在途连接登记（A-4）。 */
    val conns: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 走了「代际作废」分支的 listener 次数 —— 用来断言"迟到确实发生了"。 */
    @Volatile var lateExits = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        desired.set(true)
        val myGen = gen.incrementAndGet()
        Thread {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
                    // 200ms 醒一次：测试要在秒级内看到交错，真实现是 5s。
                    soTimeout = 200
                }
                server = ss
                while (running.get()) {
                    val s = try { ss?.accept() } catch (e: SocketTimeoutException) { continue }
                        catch (e: Exception) { break } ?: break
                    // 代际作废立刻收工：running 可能已被新 listener 重新置真。
                    if (gen.get() != myGen) break
                    conns.add(s)
                    Thread({
                        try {
                            s.getInputStream().read(ByteArray(1024))
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                                .toByteArray())
                            out.flush()
                        } catch (_: Exception) {
                        } finally {
                            conns.remove(s)
                            try { s.close() } catch (_: Exception) {}
                        }
                    }, "http-conn").apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
            } finally {
                try { ss?.close() } catch (_: Exception) {}
                // ⚠ 核心判据：先比代际再清共享状态。
                if (gen.get() == myGen) {
                    running.set(false)
                    server = null
                } else {
                    lateExits++
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        desired.set(false)
        gen.incrementAndGet()
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        for (c in conns) { try { c.close() } catch (_: Exception) {} }
        conns.clear()
    }

    fun restartIfDesired(): Boolean {
        if (!desired.get()) return false
        stop()
        desired.set(true)
        Thread.sleep(50)
        start()
        return true
    }

    // ---- Issue #159（后台保活）：探活失败 → 登记，空闲后才重启 ----
    // 与 HttpApi 的对应关系：
    //   · [generating]              <-> HttpApi.isGenerating
    //   · [restartPending]          <-> HttpApi.restartPending
    //   · [consumeRestartPendingIfIdle] <-> HttpApi.consumeRestartPendingIfIdle

    /** 等价于 `HttpApi.isGenerating`（HTTP busy 或引擎在跑；测试里外部置位）。 */
    val generating = AtomicBoolean(false)

    /** 「欠一次重启」（等价于 HttpApi.restartPending）。 */
    val restartPending = AtomicBoolean(false)

    /** 真的重启了几轮（断言"该重启的重启了、不该重启的没动"）。 */
    @Volatile var restarts = 0

    /** 探活失败时的登记（等价于 startWatchdog 里那一行）。**不重启**。 */
    fun onProbeFailed() { restartPending.set(true) }

    /**
     * 消费登记：确实空闲才重启。返回是否真的重启了。
     *
     * ⚠ 不空闲时**不清标记** —— 清了就等于把这次断联丢了（流跑完也没人补）。
     */
    fun consumeRestartPendingIfIdle(): Boolean {
        if (generating.get()) return false
        if (!desired.get()) { restartPending.set(false); return false }
        // 与 HttpApi 同步：原子取清，避免"先读后清"丢掉并发置上的那次欠账。
        if (!restartPending.getAndSet(false)) return false
        val ok = restartIfDesired()
        if (ok) restarts++
        return ok
    }
}

fun main() {
    val holder = ServerSocket(0)
    Http.port = holder.localPort
    holder.close()   // 端口号已选定，socket 必须立刻释放（见 Http 的注释）

    Http.start()
    Thread.sleep(300)
    ck("start 后 isRunning=true", Http.running.get())
    ck("start 后端口可连", canConnect(Http.port))

    // ---- A-1：旧世代不许强杀新世代 ----
    // 修复前（无条件清）这一轮之后：running=false、server=null、端口仍在 LISTEN
    // 但没人 accept（见 tools/run_http_lifecycle_guard.sh 记录的反例读数）。
    Http.stop()
    Http.start()   // 不 sleep —— 越贴近真机"stop 后立刻 start"越容易复现
    Thread.sleep(600)
    ck("A-1 stop→start 之后仍在跑（旧 finally 未踩掉新 listener）", Http.running.get())
    ck("A-1 stop→start 之后端口仍可连", canConnect(Http.port))
    ck("A-1 迟到的旧 listener 确实走了「作废」分支", Http.lateExits >= 1)
    ck("A-1 isRunning 与端口实际状态一致（僵尸 LISTEN 不再出现）",
        Http.running.get() == canConnect(Http.port))

    // ---- A-1b：反复重启，每次都活 ----
    var allAlive = true
    repeat(6) {
        Http.stop()
        Http.start()
        Thread.sleep(350)
        if (!canConnect(Http.port)) allAlive = false
    }
    ck("A-1b 反复 stop/start 6 轮后仍可连", allAlive)

    // ---- A-2：意图 vs 状态 ----
    Http.stop()
    Thread.sleep(400)
    ck("A-2 用户主动停服后 watchdog 不重启（desired=false）", !Http.restartIfDesired())
    ck("A-2 停服后端口不再 LISTEN", !canConnect(Http.port))

    Http.start()
    Thread.sleep(300)
    try { Http.server?.close() } catch (_: Exception) {}   // 等价于 accept 抛异常退出
    Thread.sleep(500)
    ck("A-2 listener 意外死亡后 running=false（现场状态）", !Http.running.get())
    ck("A-2 但 desired 仍为 true（用户仍想要服务）", Http.desired.get())
    val restarted = Http.restartIfDesired()
    Thread.sleep(400)
    ck("A-2 watchdog 把意外死亡的 listener 拉起来了（自愈不再躺平）",
        restarted && canConnect(Http.port))

    // ---- A-4：stop() 收敛在途连接 ----
    Http.start()
    Thread.sleep(300)
    val idle = (1..3).map { Socket("127.0.0.1", Http.port) }
    Thread.sleep(400)
    ck("A-4 在途连接已登记（=3）", Http.conns.size == 3)
    Http.stop()
    Thread.sleep(300)
    // 对端立刻可感知关闭：写一次 + 读一次拿 EOF（而不是等 15s soTimeout）。
    val gone = idle.all { s ->
        try {
            s.getOutputStream().write(1)
            s.getOutputStream().flush()
            Thread.sleep(30)
            s.getInputStream().read() < 0
        } catch (_: Exception) { true }
    }
    ck("A-4 stop() 收敛了在途连接（对端立刻可感知关闭）", gone)
    ck("A-4 登记表已清空（没有只涨不跌的引用）", Http.conns.isEmpty())
    idle.forEach { try { it.close() } catch (_: Exception) {} }

    // ---- Issue #159 A2：探活失败不许当场重启（会掐掉正在跑的流）----
    //
    // 这是"每一句读起来都对"的那种写法：探活失败 -> 立刻重启，最自然。
    // 而 restartIfDesired 的第一步是 stop()，它 close() 掉**所有在途连接** ——
    // 包括那条正在续传的 SSE。所以这里必须真开一条连接、真把 generating 置上，
    // 再断言"连接还在、没被收敛"。
    Http.start()
    Thread.sleep(300)
    val streaming = Socket("127.0.0.1", Http.port)
    Thread.sleep(200)
    Http.generating.set(true)                 // 模拟：有一轮生成正在跑
    Http.onProbeFailed()                      // 探活失败（此时正在生成）
    val restartsBefore = Http.restarts
    ck("A2 生成期探活失败只登记、不重启", !Http.consumeRestartPendingIfIdle())
    ck("A2 生成期确实一次都没重启", Http.restarts == restartsBefore)
    ck("A2 但登记被保留（复联后要补上，不许丢）", Http.restartPending.get())
    // 那条连接必须还活着：写一次不抛、读得到字节。
    val streamAlive = try {
        val out = streaming.getOutputStream()
        out.write("GET / HTTP/1.0\r\n\r\n".toByteArray()); out.flush()
        Thread.sleep(200)
        streaming.getInputStream().read() >= 0
    } catch (_: Exception) { false }
    ck("A2 正在跑的那条连接没被掐断（这是本改动的全部意义）", streamAlive)
    try { streaming.close() } catch (_: Exception) {}

    // 生成结束（空闲）之后，登记才被消费。
    Http.generating.set(false)
    val consumed = Http.consumeRestartPendingIfIdle()
    Thread.sleep(400)
    ck("A2 空闲后登记被消费、重启真的发生", consumed && canConnect(Http.port))
    ck("A2 消费后登记已清（不会反复重启）", !Http.restartPending.get())
    // 用户已停服时：登记作废，不许自动拉起。
    Http.stop()
    Http.onProbeFailed()
    ck("A2 用户已停服时登记作废、不自动拉起", !Http.consumeRestartPendingIfIdle() && !canConnect(Http.port))

    println("")
    if (fail == 0) {
        println("=== HTTP 生命周期复刻测试：PASS 21 / FAIL 0 ===")
    } else {
        println("=== HTTP 生命周期复刻测试：FAIL $fail ===")
    }
    kotlin.system.exitProcess(if (fail == 0) 0 else 1)
}

private fun canConnect(p: Int): Boolean = try {
    Socket("127.0.0.1", p).use { true }
} catch (_: Exception) {
    false
}
