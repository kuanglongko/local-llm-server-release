package com.xiaowan.localinference

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

// 「请求取消」的离线单测。
//
// 为什么值得单测：这个特性全是**判据**，而它两个方向的失效都极其安静：
//
//   1. 漏判（客户端走了却继续跑）—— 没有任何异常、没有日志、调用方也看不到，
//      唯一的代价是白烧 CPU/电，只对着好实现跑一万次都发现不了；
//   2. 误判（客户端还在却把生成掐了）—— 更坏：用户看到"回答说到一半停住"，
//      而服务端日志里写的是一次"成功取消"。断连判据写反就会这样。
//
// 另外这里真的开一对 socket 跑一遍 RequestCancel.probe：
// "对端正常关掉后写还成不成功"这类事实，只有真连一次才知道 ——
// 而它恰恰是整个判据的地基（写探测那条规则不是从文档抄的，是从行为定的）。
private var fail = 0
private fun ck(n: String, cond: Boolean) {
    if (cond) println("PASS  $n") else { println("FAIL  $n"); fail++ }
}

fun main() {
    // ---- 1. Token：只认第一次 ----
    val t = RequestCancel.Token()
    ck("新建的 token 未取消", !t.requested)
    ck("首次 request() 返回 true（调用方据此只记一次日志）", t.request())
    ck("重复 request() 返回 false", !t.request())
    ck("requested 保持为真", t.requested)
    ck("cancelled 与 requested 一致", t.cancelled)
    ck("未取消的 token 上 stopHit 为假", !RequestCancel.Token().stopHit)

    // ---- 2. 归属：取消只打当前轮次，绝不误伤后来者 ----
    // 这是本文件头记的那个事故的回归：断连的请求迟到触发取消，
    // 而此刻真正在跑的是**另一个**轮次。
    val a = RequestCancel.enter()
    ck("enter 之后 active", RequestCancel.active)
    ck("currentToken 就是刚登记的", RequestCancel.currentToken() === a)
    ck("cancelCurrent 取消的是 a", RequestCancel.cancelCurrent() === a)
    ck("a 已被标记", a.requested)

    // 模拟 a 退出、b 进入：b 必须是**干净**的，不能继承 a 的取消状态
    RequestCancel.leave(a)
    ck("leave 之后不再 active", !RequestCancel.active)
    ck("没有轮次时 cancelCurrent 返回 null（no-op，不该伪造一个 token）",
        RequestCancel.cancelCurrent() == null)

    val b = RequestCancel.enter()
    ck("新一轮 b 未继承上一轮的取消状态", !b.requested)
    ck("b 与 a 不是同一个对象", b !== a)
    ck("过期轮次 a 的 leave 不会踩掉当前轮次 b（错过摘除也要安全）", run {
        RequestCancel.leave(a)
        RequestCancel.currentToken() === b
    })
    ck("isCancelled 反映当前轮次 b（未取消）", !RequestCancel.isCancelled())
    RequestCancel.cancelCurrent()
    ck("取消之后 isCancelled 为真", RequestCancel.isCancelled())
    RequestCancel.leave(b)
    ck("摘除之后 isCancelled 为假（不残留）", !RequestCancel.isCancelled())

    // ---- 3. 断连判据（写探测的**两态**表）----
    // 只有一条规则：写成功 = ALIVE，写失败 = GONE。**没有 UNKNOWN 档** —— 探测不读，
    // 就不存在「连接还在但没话说」被误判成离开的可能（读探测那套三态是误杀风险源，
    // 而它为拿到 FIN 信号付出的代价是往 chunked 流里裸写 0x00，2026-09-20 真机断流）。
    ck("写抛异常 -> GONE（RST / Broken pipe）",
        RequestCancel.classify(writeOk = false) == RequestCancel.PeerState.GONE)
    ck("写成功 -> ALIVE（连接仍在，客户端在等）",
        RequestCancel.classify(writeOk = true) == RequestCancel.PeerState.ALIVE)
    ck("PeerState 只有两态（不许再出现第三种「没结论」的档）",
        RequestCancel.PeerState.values().size == 2)

    // ---- 4. 写探测的真行为（真连一次）----
    // 探测的载体由调用方给（HttpApi 里是写一帧合法 SSE 注释）。这里用"写一个裸字节"
    // 当 sink 只是为了测**探测函数本身**的成败判定；真路径上写的是分帧注释，见第 5 节。
    val sinkWrite: (java.net.Socket) -> () -> Unit = { sock ->
        { val os = sock.getOutputStream(); os.write(0x3A); os.flush() }
    }
    val srv = ServerSocket(0)
    val port = srv.localPort

    // 4a. 客户端还在：探测必须判 ALIVE，且那个字节真的发出去了。
    run {
        val srvSide = java.util.concurrent.ArrayBlockingQueue<Socket>(1)
        Thread { srvSide.put(srv.accept()) }.apply { isDaemon = true }.start()
        Socket("127.0.0.1", port).use { cli ->
            val peer = srvSide.take()
            try {
                val st = RequestCancel.probe(sinkWrite(peer))
                ck("对端在 -> ALIVE", st == RequestCancel.PeerState.ALIVE)
                // 探测确实动了连接：客户端必须读得到那个字节（判 ALIVE 却什么都没写
                // 本质上是一句空话）。
                cli.soTimeout = 2000
                ck("探测的字节真的写到了对端（ALIVE 不是空话）", cli.getInputStream().read() == 0x3A)
            } finally { peer.close() }
        }
    }

    // 4b. 客户端正常关闭（FIN）：**持续**探测必须判出 GONE。
    //     实测（Linux）：对端 close() 后本端第一次 write 仍可能成功（只有 RST 才是
    //     立刻 Broken pipe），所以「客户端走了」这一点可能要到第二次写才看得出来。
    //     这对实现的含义很明确：判据不能依赖"一次探测就出结论"，生成循环必须持续探测
    //     （本仓库是每 256 步一次 + 心跳），否则会漏掉这一类离开 —— 而它恰好最常见
    //     （客户端跑完就退出）。
    run {
        val srvSide = java.util.concurrent.ArrayBlockingQueue<Socket>(1)
        Thread { srvSide.put(srv.accept()) }.apply { isDaemon = true }.start()
        val cli = Socket("127.0.0.1", port)
        val peer = srvSide.take()
        try {
            cli.getOutputStream().write(ByteArray(8) { 65 }); cli.getOutputStream().flush()
            Thread.sleep(200)
            cli.close()
            var st = RequestCancel.PeerState.ALIVE
            var probes = 0
            for (i in 0 until 20) {
                st = RequestCancel.probe(sinkWrite(peer))
                probes++
                if (st == RequestCancel.PeerState.GONE) break
                Thread.sleep(50)
            }
            ck("客户端正常关闭（FIN）：持续探测后必须判出 GONE（一次看不出来不是 bug）",
                st == RequestCancel.PeerState.GONE)
            ck("判出 GONE 用的是有限次探测（≤20 次，不会永远探不出来）", probes <= 20)
        } finally { peer.close() }
    }

    // 4c. 客户端被强制重置（RST）：探测必须判 GONE，且不得抛异常
    run {
        val srvSide = java.util.concurrent.ArrayBlockingQueue<Socket>(1)
        Thread { srvSide.put(srv.accept()) }.apply { isDaemon = true }.start()
        val cli = Socket("127.0.0.1", port)
        val peer = srvSide.take()
        try {
            cli.setSoLinger(true, 0)   // close 时发 RST
            cli.close()
            var st = RequestCancel.PeerState.ALIVE
            for (i in 0 until 20) {
                st = RequestCancel.probe(sinkWrite(peer))
                if (st != RequestCancel.PeerState.ALIVE) break
                Thread.sleep(50)
            }
            ck("客户端被 RST -> GONE（写抛异常，不外抛）", st == RequestCancel.PeerState.GONE)
        } finally { peer.close() }
    }

    // 4d. sink 自己抛异常也必须被吞掉（调用方是生成循环，抛出去等于多一条崩溃路径）
    run {
        ck("sink 抛异常 -> GONE，且不外抛",
            RequestCancel.probe { throw java.io.IOException("Broken pipe") } == RequestCancel.PeerState.GONE)
    }
    srv.close()

    // ---- 5. 判据与 HTTP 层的一致性（源码级静态断言）----
    // 这几条防的是"实现改了、判据没跟上"：写探测的结论表在 RequestCancel，
    // 而决定要不要掐生成的是 HttpApi —— 两边必须只认 GONE。
    val http = java.io.File("app/src/main/java/com/xiaowan/localinference/HttpApi.kt").readText()
    ck("HttpApi 里有 POST /v1/abort 路由", http.contains("\"/v1/abort\"") && http.contains("handleAbort()"))
    ck("断连探测只认 GONE（两种调用点多处都判 GONE 分支）",
        Regex("PeerState\\.GONE ->").findAll(http).count() >= 2)
    ck("取消注册在 finally 里摘除（异常路径也不能留僵尸轮次）",
        http.contains("LlmEngine.endCancelable(cancel)"))
    ck("生成循环每步查 cancel.requested", http.split("cancel.requested").size >= 3)
    ck("取消后 finish_reason 报 cancelled（不退化成 stop）",
        http.contains("\"cancelled\""))
    // 探测只在 stream=true 上做：非流式的响应体是一整段 JSON，往里插一帧会落在
    // 状态行之前、把响应写坏（见 CANCEL_PROBE_STEPS 的注释）。所以这里断言的是
    // 「两个生成端点的探测判据都带 stream 门」，**不是**"非流式也探"。
    ck("两个生成端点的断连探测都门在 stream 上（非流式插帧会写坏响应体）",
        Regex("if \\(stream && emitted > 0 && n % CANCEL_PROBE_STEPS == 0 && probeOutbound").findAll(http).count() == 2)
    // 从源码里把 `probeOutbound(` 的**调用点**逐行揪出来，断言每一处都带 stream 门。
    // 不用正则做"负向先行"那种取巧写法：第一版写出来的断言自己算错了数（把函数
    // 声明行也数进去了），而它红了之后根本看不出是断言错还是实现错。
    val probeCalls = http.lines().filter { it.contains("probeOutbound(") && !it.contains("private fun") }
    ck("probeOutbound 只有 2 处调用点（两个生成端点各一）", probeCalls.size == 2)
    ck("probeOutbound 的每处调用点都带 stream 门 + 首帧门槛",
        probeCalls.all { it.contains("stream && emitted > 0 && n % CANCEL_PROBE_STEPS == 0") && !it.trimStart().startsWith("//") })
    ck("把「非流式只能靠写响应抛异常发现」这一缺口写在了注释里（不是默默漏掉）",
        http.contains("已知的、有意留下的缺口"))
    ck("abort 端点报 aborted 字段（结果可判定，不是空的 200）",
        http.contains("\"aborted\":\$running"))

    // ---- 6. 「裸探测字节不得写进 chunked 流」的源码级守卫 ----
    // 2026-09-20 真机：旧 clientGone 往 socket 裸写 0x00，破坏了 Transfer-Encoding: chunked，
    // 客户端解析下一个 chunk 的长度行拿到 0x0 -> `Expected leading [0-9a-fA-F] character but
    // was 0x0` -> 主动断流。表现是"思考开时回答输出一小段就断"，且只在思考时（inThink
    // 从第一个 token 起为真，心跳唯一会开火的窗口）。
    // 这条守卫是那次的回归：探测量必须**载在合法 SSE 帧上**（sseComment），不得裸写。
    // 判据要只看**代码**、不看注释：注释里为了解释"为什么删掉读探测"会提到这些名字，
    // 拿 `contains` 去禁注释等于逼着后人删掉解释（那正是这类守卫最常见的自伤）。
    // 所以逐行剥掉 `//` 与 `*` 之后的注释内容再断言。
    val rcCode = java.io.File("app/src/main/java/com/xiaowan/localinference/RequestCancel.kt")
        .readLines()
        .map { l -> l.substringBefore("//").let { if (it.trimStart().startsWith("*")) "" else it } }
        .joinToString("\n")
    ck("请求取消模块的代码里不再有裸写探测字节（无 getOutputStream() / PROBE_BYTE）",
        !rcCode.contains("getOutputStream") && !rcCode.contains("getInputStream") &&
        !rcCode.contains("PROBE_BYTE"))
    ck("断连探测的代码是两态（不含 PeerState.UNKNOWN）",
        !rcCode.contains("UNKNOWN"))
    ck("心跳/周期探测的载体是 sseComment（合法 chunked 帧），不是裸写",
        Regex("RequestCancel\\.probe \\{ sseComment\\(").findAll(http).count() >= 2)
    ck("心跳带首帧门槛（首条真实 SSE 帧下发前不探测）",
        http.contains("emitted > 0 && System.currentTimeMillis() - lastBeat > 800"))
    ck("首帧门槛的两个端点都计数 emitted（不是只改一条路径）",
        Regex("emitted\\+\\+").findAll(http).count() >= 2)
    ck("周期探测（每 256 步）也带首帧门槛（不只是心跳那处）",
        Regex("stream && emitted > 0 && n % CANCEL_PROBE_STEPS").findAll(http).count() == 2)

    val engine = java.io.File("app/src/main/java/com/xiaowan/localinference/LlmEngine.kt").readText()
    ck("LlmEngine 暴露 beginCancelable/endCancelable 给两条入口共用",
        engine.contains("fun beginCancelable()") && engine.contains("fun endCancelable("))
    // 归属必须**一路送到 native**：光标记 Kotlin 的 token 只挡得住"循环自己查标志"，
    // 挡不住 prefill（那是一次阻塞的 native 调用）。这三条一起钉住"最后一跳没丢归属"。
    ck("beginCancelable 把 native 轮次编号绑进 token（归属不止步于 JNI 门口）",
        engine.contains("t.bindNativeEpoch(runCatching { nativeCurrentEpoch() }"))
    ck("取消有唯一入口 requestAbort（标记 token + 送 native 同处发生）",
        engine.contains("fun requestAbort(): RequestCancel.Token?") &&
        engine.contains("runCatching { nativeAbort(t.nativeEpoch) }"))
    ck("nativeAbort 声明带轮次编号（与 native 侧签名一致）",
        engine.contains("nativeAbort(roundEpoch: Long)"))
    ck("LlmEngine.isGenerating 走的是 RequestCancel（与 /v1/abort 同一份状态）",
        engine.contains("val isGenerating: Boolean get() = RequestCancel.active"))

    val act = java.io.File("app/src/main/java/com/xiaowan/localinference/EngineActivity.kt").readText()
    ck("App 内生成也登记取消归属（HTTP /v1/abort 能停 App 内的那一轮）",
        act.contains("cancel = LlmEngine.beginCancelable()"))
    ck("App 内生成循环也判 cancel.requested", act.contains("!cancel.requested"))
    ck("停止按钮在「本页没生成、外部请求在跑」时也生效（否则点了没反应）",
        act.contains("httpBusy"))
    ck("停止按钮把取消送到 native（只标记 token 的话 prefill 停不下来）",
        act.contains("LlmEngine.requestAbort()"))

    if (fail > 0) { println("\n$fail 条失败"); kotlin.system.exitProcess(1) }
    println("\n全部通过")
}
