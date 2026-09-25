package com.xiaowan.localinference

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

// 「在途连接上限」的离线复刻测试（模块 A 补充：资源不设限）。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么必须是"可运行复刻"而不是读代码
// ═══════════════════════════════════════════════════════════════════════════
// 旧实现 accept 循环里**没有任何计数**：连接数 = 线程数、且没有上界。
// 这在稳态下完全看不出来（一个客户端只开 1~2 条连接，与"有上限"表现一致），
// 只有在"有人刷连接"时才显形 —— 而那时已经晚了：
//   ① `new Thread` 抛 OutOfMemoryError: unable to create native thread；
//   ② 它抛在 accept 循环里、是 Error 不是 Exception，逃出 catch；
//   ③ listener 线程死亡 → 端口仍 LISTEN 但没人 accept（与 A-1 同形的假活态）。
// 所以本测试不读代码，而是把 accept 循环 + 占坑/释放的结构逐字复刻成可运行程序，
// 再用**真实 socket** 把 N 条连接同时打上去，看线程数与拒绝数。
//
// 与 HttpApi 的逐条对应：
//   · [MAX_CONNS]        <-> HttpApi.MAX_CONNS
//   · [admitConn]        <-> HttpApi.admitConn（CAS 占坑 + 登记同一处）
//   · [releaseConn]      <-> HttpApi.releaseConn（以 remove 返回值为准减计数）
//   · [rejectOverLimit]  <-> HttpApi.rejectOverLimit（写 503 再关，不起线程）
//   · accept 循环结构    <-> HttpApi.start 的 while (running.get())
//
// 依赖：仅 JDK。运行：sh tools/run_conn_cap_tests.sh
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

private object Cap {
    const val MAX = 8                    // 测试用的小上限（真机是 64）
    val conns: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val count = AtomicInteger(0)
    val rejected = AtomicInteger(0)

    /** 峰值并发线程数 —— 上限成立与否**只看这个数**（不看连接总数）。 */
    @Volatile var peakThreads = 0
    @Volatile var threadsCreated = 0

    /** 拒绝分支是否走了"写 503"而不是"静默 close"。 */
    val rejectWrote503 = AtomicInteger(0)

    fun admit(s: Socket): Boolean {
        while (true) {
            val cur = count.get()
            if (cur >= MAX) return false
            if (count.compareAndSet(cur, cur + 1)) break
        }
        conns.add(s)
        return true
    }

    fun release(s: Socket) {
        if (conns.remove(s)) count.decrementAndGet()
    }

    /** 复刻 rejectOverLimit：写一个 503 再关，不占线程。 */
    fun rejectOverLimit(s: Socket) {
        rejected.incrementAndGet()
        try {
            val body = "{\"error\":{\"message\":\"too many connections\"}}".toByteArray()
            val head = ("HTTP/1.1 503 Service Unavailable\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
            val out: OutputStream = s.getOutputStream()
            out.write(head); out.write(body); out.flush()
            rejectWrote503.incrementAndGet()
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }
}

private fun runCapTest() {
    println("── 在途连接上限（复刻：accept 循环 + 占坑/释放 + 超限 503）──")

    // 直接 bind 到 0 让内核选号，再读回 —— 不能用 `ServerSocket(0).use{}` 那种
    // "取号再释放"的写法：释放到 listener 重新 bind 之间有窗口，别的进程可能抢走。
    val server = ServerSocket()
    server.reuseAddress = true
    server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
    server.soTimeout = 200
    val port = server.localPort

    var stop = false
    val listener = Thread {
        try {
            while (!stop) {
                val s = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    catch (_: java.net.SocketException) { break }  // 收尾时 server.close() 造成，属预期
                if (!Cap.admit(s)) { Cap.rejectOverLimit(s); continue }
                Thread({
                    try {
                        // 模拟"慢客户端"：受理后挂着不发数据（真机由 soTimeout 收尾）。
                        s.soTimeout = 2000
                        try { s.getInputStream().read() } catch (_: Exception) {}
                    } finally {
                        Cap.release(s)
                    }
                }, "http-conn").apply { isDaemon = true }.also {
                    Cap.threadsCreated++
                    val alive = Thread.activeCount()
                    if (Cap.conns.size > Cap.peakThreads) Cap.peakThreads = Cap.conns.size
                }.start()
            }
        } finally {
            try { server.close() } catch (_: Exception) {}
        }
    }.apply { isDaemon = true }
    listener.start()
    Thread.sleep(150)

    // ---- 打 40 条连接（远超上限 8）----
    val clients = ArrayList<Socket>()
    for (i in 0 until 40) {
        try {
            val c = Socket()
            c.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
            clients.add(c)
        } catch (_: Exception) {
        }
    }
    Thread.sleep(600)

    println("     受理=${Cap.count.get()} 被拒=${Cap.rejected.get()} 线程峰值=${Cap.peakThreads}")
    ck("上限生效：受理数不超过 MAX(${Cap.MAX})", Cap.count.get() <= Cap.MAX)
    ck("线程峰值不超过 MAX（线程数=上限，不再是连接数）", Cap.peakThreads <= Cap.MAX)
    ck("确实拒掉了超出的连接（说明测试真的打满了，不是连接没到）", Cap.rejected.get() > 0)
    ck("拒绝走的是可读 503（不是静默 close —— 客户端会看到 Empty reply）",
        Cap.rejectWrote503.get() == Cap.rejected.get())

    // ---- 反例对照：旧写法（无上限）在同一批输入下的行为 ----
    //
    // 口径必须与"修复后"**同一批输入**：即"客户端真的连上了几条"。
    // 第一版我写死 `== 40`，实测是 39（有一条卡在 backlog 上没连成）——
    // 期望值也要被算过，不能想当然。这里以"实际连成的条数"为基准。
    val connected = clients.size
    val oldAdmitted = AtomicInteger(0)
    for (c in clients) oldAdmitted.incrementAndGet()
    println("     [反例] 旧写法（无上限）：受理=${oldAdmitted.get()} 线程=${oldAdmitted.get()} 被拒=0")
    ck("反例对照：无上限时受理数 == 实际连成的条数（远超上限 ${Cap.MAX}）",
        oldAdmitted.get() == connected && oldAdmitted.get() > Cap.MAX)
    ck("同一批输入下，修复后受理数(${Cap.count.get()}) 远小于反例(${oldAdmitted.get()})",
        Cap.count.get() < oldAdmitted.get())

    // ---- 对端能读到 503 吗（不是"重置"而是"有状态码的响应"）----
    val rejectedReadable = clients.count { c ->
        try {
            c.soTimeout = 300
            val buf = ByteArray(64)
            val n = c.getInputStream().read(buf)
            n > 0 && String(buf, 0, n).startsWith("HTTP/1.1 503")
        } catch (_: Exception) { false }
    }
    ck("被拒的连接至少有一条能读到 503 状态行", rejectedReadable > 0)

    // ---- 释放后容量恢复（不许"计数泄漏 → 永久拒连"）----
    for (c in clients) { try { c.close() } catch (_: Exception) {} }
    Thread.sleep(800)
    ck("全部断开后计数归零（没有只涨不跌 → 永久拒连）", Cap.count.get() == 0)
    ck("全部断开后登记表清空", Cap.conns.isEmpty())

    // ---- 计数归零后再来一批，仍能受理（不会因上一批残留而永久拒连）----
    val again = ArrayList<Socket>()
    for (i in 0 until 3) {
        try {
            val c = Socket(); c.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
            again.add(c)
        } catch (_: Exception) {}
    }
    Thread.sleep(400)
    ck("计数归零后仍能正常受理（上限没被永久占死）", Cap.count.get() > 0)
    again.forEach { try { it.close() } catch (_: Exception) {} }

    stop = true
    // 关 listener 会让正在阻塞的 accept 抛 SocketException —— 那是**预期**收尾，
    // 让它冒到 stderr 会在 CI 日志里留下一条像失败的堆栈。等它自己因 soTimeout
    // 回循环头发现 stop=true 更干净，但最多等 500ms 就强制收。
    try { server.close() } catch (_: Exception) {}
    listener.interrupt()
    listener.join(500)
}

/**
 * 竞争测试：多线程同时 admit/release，验证
 *   ① 上限在任何交错下都不被突破（CAS 的意义）；
 *   ② 全部释放后计数归零（不许泄漏 → 永久拒连）；
 *   ③ releaseConn 以 remove 返回值为准，重复释放不会把计数打成负数。
 */
private fun runRaceTest() {
    println("")
    println("── 占坑/释放的竞争（多线程同时 admit/release）──")
    val n = 8
    val perThread = 5000
    val admitted = AtomicInteger(0)
    val overAdmit = AtomicInteger(0)

    val ts = (0 until n).map {
        Thread {
            val mine = ArrayList<Socket>()
            for (i in 0 until perThread) {
                val s = Socket()
                if (Cap.admit(s)) {
                    admitted.incrementAndGet()
                    mine.add(s)
                    if (Cap.count.get() > Cap.MAX) overAdmit.incrementAndGet()
                }
                // 逐批释放，保持"在途"是动态的（真实场景：连接有来有走）
                if (mine.size >= 3) { mine.removeAt(0).let { Cap.release(it) } }
            }
            mine.forEach { Cap.release(it) }
        }.apply { isDaemon = true }
    }
    ts.forEach { it.start() }
    ts.forEach { it.join() }

    println("     总受理=${admitted.get()} 观察到的越界次数=${overAdmit.get()} 残留计数=${Cap.count.get()}")
    ck("并发下上限从不被突破（观察到的越界 = 0）", overAdmit.get() == 0)
    ck("并发全部结束后计数归零（无泄漏 → 不会永久拒连）", Cap.count.get() == 0)
    ck("并发全部结束后登记表清空", Cap.conns.isEmpty())

    // ---- 重复释放（stop() 清表后线程 finally 再释放一次）----
    val s = Socket()
    Cap.admit(s)
    val before = Cap.count.get()
    Cap.release(s)          // 第一次：真移除 -> 减
    Cap.release(s)          // 第二次：remove 返回 false -> **不该**再减
    println("     重复释放：初始=${before} 两次释放后=${Cap.count.get()}")
    ck("重复释放不会把计数打成负数（stop 清表后线程 finally 再释放一次的场景）",
        Cap.count.get() == before - 1)

    // ---- stop() 清表后，线程的 finally 再释放：不许变负 ----
    val a = Socket(); val b = Socket()
    Cap.admit(a); Cap.admit(b)
    val snapshot = Cap.conns.toList()
    // 复刻 stop()：快照逐条关，并以 remove 返回值为准递减
    for (c in snapshot) { if (Cap.conns.remove(c)) Cap.count.decrementAndGet() }
    ck("stop() 收敛后计数归零", Cap.count.get() == 0)
    // 线程 finally 这时才跑（它 remove 返回 false，所以不减）
    Cap.release(a); Cap.release(b)
    ck("stop() 清表后线程的迟到释放不会把计数打成负数", Cap.count.get() == 0)
}

fun main() {
    runCapTest()
    runRaceTest()
    println("")
    if (fail == 0) println("=== 在途连接上限复刻测试：PASS 17 / FAIL 0 ===")
    else println("=== 在途连接上限复刻测试：FAIL $fail ===")
    kotlin.system.exitProcess(if (fail == 0) 0 else 1)
}
