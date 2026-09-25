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

    println("")
    if (fail == 0) {
        println("=== HTTP 生命周期复刻测试：PASS 15 / FAIL 0 ===")
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
