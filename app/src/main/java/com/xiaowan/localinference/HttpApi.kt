package com.xiaowan.localinference

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * 零依赖 HTTP API 层：以进程内引擎直连提供 OpenAI 兼容的 server 能力，
 * 不 exec 外部 llama-server 二进制（部分 OEM 的 W^X 策略禁止执行应用数据目录下的文件）。
 *
 * 绑定 127.0.0.1（端口可自定义，默认 8080），OpenAI 兼容端点：
 * GET  /health                 -> 引擎/模型/上下文状态
 * GET  /v1/models              -> 当前加载的模型（单实例）
 * POST /v1/chat/completions    -> 对话补全，stream=true 时 SSE 流式；认 `tools`（工具调用）
 * POST /v1/completions         -> raw prompt 补全（不走 chat 模板，适合测速）
 * POST /v1/abort               -> 取消当前生成（客户端主动放弃，或断连后的兜底）
 *
 * 并发模型：每连接一线程（accept 不串行阻塞）；生成期间（busy=任一路生成在跑）新请求返回 503；
 * 生成循环持 LlmEngine.genLock，与 UI 测试页互斥。
 * JSON 解析用平台自带 org.json，响应手工拼装（零第三方依赖约束）。
 *
 * 生命周期（start / stop / 看门狗自愈）有三条不变量，全部由**代际号**与
 * 「意图 vs 状态」两个标志撑住（判据与理由见各自字段的注释）：
 *   1. [desired] = 用户意图，[running] = 实际状态，**永不合并**。看门狗只看 desired，
 *      否则"listener 意外死亡"与"用户主动停服"无法区分，只能二选一：
 *      要么不自愈、要么把用户停掉的服务自动拉起。
 *   2. listener 的 `finally` **必须**先比 [listenerGen] 再清共享状态。无条件清会让
 *      迟到的旧线程踩掉新一代 listener（现场：端口在 LISTEN 但没人 accept，
 *      且 `server` 被清成 null 后 `stop()` 关不掉它 → 重启 `BindException` → 永久不可用）。
 *   3. [stop] 递增代际、关当前活的 socket、并**收敛在途连接**（[activeConns]），
 *      否则"服务已停，仍在把已停请求的整段响应写完"。
 *
 * 鉴权（可选，未设 token 时**不生效** = 与之前完全同行为）：
 *   只有生成端点（`/v1/chat/completions`、`/v1/completions`、`/v1/abort`）要 `Authorization: Bearer`。
 *   `OPTIONS` / `GET /health` / `GET /v1/models` / `GET /`（自带测试页）**一律免鉴权** ——
 *   判据与理由在 [ApiAuth] 文件头（那是本文件唯一一处鉴权判据，别在这里另写一份）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 请求取消（客户端断连 / `POST /v1/abort`）
 * ══════════════════════════════════════════════════════════════════════════
 * 三条路径，都收敛到 [RequestCancel] 的**当前轮次 token**（归属判定在那里）：
 *   1. 客户端离开（curl 结束、进程被杀、断网）—— 生成期由 SSE 心跳做写探测发现，
 *      立即 `request()`；这是**主动**发现，省下的是一整段 max_tokens 的 CPU/电；
 *   2. 客户端明确调 `POST /v1/abort` —— 走 [handleAbort]，立即生效；
 *   3. 迟到的兜底：写 SSE 抛异常（生成已结束但连接是坏的）时也标记取消。
 * 收尾有**迟滞**：探测每 256 步一次、外加思考段心跳，但**只靠写**（写失败即对端已走），
 * 且首条真实 SSE 帧下发前不探（见 `lastBeat` 的注释）。
 * 为什么不能只靠"写 SSE 抛异常"这条路：正常关闭（FIN）之后本端首次写往往**成功**
 * （数据进内核缓冲），第二次写才拿到 RST —— 只在异常路径探测的实现在这类客户端上
 * 等于没实现。[RequestCancel] 文件头记了这个"最终会发现、不保证立刻发现"的取舍。
 */
object HttpApi {
    private const val TAG = "HttpApi"
    const val DEFAULT_PORT = 8080

    /**
     * 断连探测的间隔（按**已生成 token 数**计，不是时间）。
     *
     * 为什么不按时间：生成速度随模型/后端差 20 倍（CPU 1.5B 约 20 tok/s，
     * HTP 上可能到 100+），按毫秒等于让探测频率随机器漂移；而探测的开销是一次
     * 合法 SSE 帧的写，按 token 计才能保证它与生成量成正比。
     *
     * 256 步 ≈ 5~15 秒一次：足够早地发现客户端走了，相对 512~8192 的
     * max_tokens 又不算频繁。
     *
     * **只在流式（`stream=true`）上探测**，这是被逼出来的取舍：探测的载体是
     * 「一帧 SSE 注释」，而流式的响应体是可自同步的 SSE 帧流 —— 注释帧按规范被忽略。
     * 非流式的响应体是**一整段 JSON**，往里插一帧会直接把这次响应写坏。
     * 所以非流式只能靠"写响应时抛异常"这条迟到路径发现（见 [handleChat] 的 catch），
     * 代价是它要跑满 max_tokens 才收尾。**这是一处已知的、有意留下的缺口** ——
     * 补它需要在响应体上另开一条带外通道（例如 `Transfer-Encoding: chunked` +
     * 空 chunk），那属于改协议面，不在本次范围内。
     */
    private const val CANCEL_PROBE_STEPS = 256

    /** 端口可自定义；InferenceService 启动前从 ModelStore 注入。 */
    @Volatile var PORT: Int = DEFAULT_PORT

    /** true = 绑定 0.0.0.0 允许局域网访问（无鉴权）；默认 false 只绑回环。 */
    @Volatile var bindAll: Boolean = false
    /** 全局默认关闭思考（客户端 enable_thinking / chat_template_kwargs / reasoning_effort 可覆盖）。 */
    @Volatile var disableThinkingDefault: Boolean = true

    /**
     * CORS 开关的**镜像**（真值在 CorsPolicy.enabled，判据全在那边）。
     *
     * 为什么在 HttpApi 上留一份：设置页改开关时要写进 CorsPolicy，但 UI 侧的
     * 读法不统一会出"设置页显示开着、实际没生效"这种错位。这里做成
     * 一个显式的转发点，两边取同一个文件里的判据（见 run_cors_tests.sh 的断言）。
     */
    var corsEnabled: Boolean
        get() = CorsPolicy.enabled
        set(v) { CorsPolicy.enabled = v }

    /** 取本机在局域网的首个非回环 IPv4，用于通知/UI 展示；找不到返回 null。 */
    fun lanIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) { null }

    /**
     * 「用户想要服务在跑」——与 [running]（实际状态）**必须分开**。
     *
     * 只用一个 `running` 表达两件事时，看门狗无法区分这两种"不在跑"：
     *   · 用户主动 [stop]（不该重启）；
     *   · listener 意外死亡 / 状态位被踩（**必须**重启）。
     * 旧实现只有 `running`，看门狗写成 `if (!running.get()) continue` —— 于是
     * 一旦 `running` 被踩成 false（见 [listenerGen] 的迟到 finally），
     * 看门狗此后**每一次**都走那条 continue：设计目标是"断联 30~60s 自愈"，
     * 实际是**一旦踩中永久躺平**，服务死了不自愈。
     */
    private val desired = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    /**
     * listener 的**代际号**：每起一轮 accept 循环 +1，`finally` 只在
     * "我仍是当前那一轮"时才允许清 [running] / [server]。
     *
     * 为什么非有不可：`running` / `server` 是 object 级共享状态，而 `finally`
     * 是**每个 listener 线程各写一次、且原来不看代际**。看门狗的重启路径是
     * `stop(); Thread.sleep(500); start()`，那 500ms 是**赌**旧线程能在窗口内
     * 走完 `finally`。赌输的现场：新 listener 已把 `running=true`、`server=新socket`，
     * 旧线程这时才进 `finally` → `running.set(false)`、`server = null` →
     * 新 accept 循环下一轮看到 `running=false` 直接退出。
     * 症状正是注释里声称已修好的那条：**端口在 LISTEN，没人 accept**，
     * 而且叠加上下面 `server` 被清成 null 后，`stop()` 关不掉那个仍在监听的 socket
     * → 重启时 `BindException: Address already in use`（`SO_REUSEADDR` 对
     * "对端仍在 LISTEN"无效，它只救 TIME_WAIT）→ 服务**永久不可用**。
     *
     * [stop] 递增它，于是在途 listener 的 `finally` 自动作废。
     */
    private val listenerGen = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 在途连接登记表。[stop] 靠它**主动收敛**已受理的连接。
     *
     * 不登记的话 `stop()` 只关 listening socket，每条连接的 socket 由各自
     * `http-conn` 线程的 `s.use{}` 释放，`stop()` 无从介入 —— 后果是"服务已停，
     * 仍在为已停的请求把整段响应写完"（生成路径只写不读，不受 `soTimeout` 约束）。
     *
     * 用 `ConcurrentHashMap.newKeySet()` 而不是 `Collections.newSetFromMap`：
     * 前者本身就是并发集合（本项目 Kotlin 侧零第三方依赖，但 JDK 集合是允许的）。
     */
    private val activeConns: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 在途连接的**上限**（不是请求上限，也不是生成上限）。
     *
     * 为什么非有不可：本项目是「每连接一线程」，而 `bindAll=true` 时监听 0.0.0.0、
     * **局域网内任何人**都能连。此前 accept 循环**不做任何计数**，于是连接数 = 线程数、
     * 且**没有上界** —— 一个跑飞的客户端（或一把扫描器）就能把进程拉到线程枯竭：
     * `new Thread` 抛 `OutOfMemoryError: unable to create native thread`，
     * 而它抛出点在 accept 循环里，会被 `catch (e: Exception)` 之外的 `Error` 逃出去，
     * 直接让 listener 线程死亡 → 端口仍 LISTEN 但没人 accept（与 A-1 同形的假活态）。
     *
     * 取值 64 的依据（不是拍出来的）：本服务**单并发**（`busy` + `LlmEngine.genLock`），
     * 正常客户端最多持 1~2 条连接（生成 + 轮询 `/health`）。
     * 64 给了"多个客户端 × 各自几条连接"的余量，同时把线程数钉在两位数 ——
     * 手机进程的线程上限通常在数百量级，64 留足安全边际。
     *
     * ⚠ 上限只约束**在途连接**，不约束**连接建立速率**：这不是限流（rate limit），
     * 是资源保护。要做限流得另有一套"按时间窗计数"的状态，与本判据正交。
     */
    private const val MAX_CONNS = 64

    /**
     * 当前**已被受理**（占了一个 `http-conn` 线程槽）的连接数。
     *
     * 不直接用 [activeConns.size] 做判据的原因：`size()` 与「加进集合」之间不是原子的，
     * 两个 accept 线程（watchdog 重启期间可能短暂并存两轮 listener）同时读到 63
     * 就会双双向 64 里塞。这里用 CAS 循环让「计数 + 占位」是一个原子步。
     *
     * 它与 [activeConns] 的**增删必须同源**：都在 `admitConn` / `releaseConn` 里。
     * 分别各自增减会出现"表里有、计数没有"（上限永远差一位）或反之（计数泄漏、
     * 服务最终**永久拒连**）—— 后者是这条判据最危险的失效形态，因为它零症状、
     * 只在长时间运行后显形。
     */
    private val connCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 因超过 [MAX_CONNS] 被拒的连接数（诊断用）。
     *
     * 存在的理由与 A-2 的 `desired` 同类：超限拒绝是**零症状**的 ——
     * 客户端拿到的是 503（与"服务正忙"完全同形），日志里不留痕就分不清
     * "被生成背压拒了"与"被连接数上限拒了"，而这两者的处置完全不同
     *（前者等一会儿就好，后者说明有东西在刷连接）。
     */
    private val rejectedConns = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 是否「用户想要服务在跑」（= [desired]，UI 侧语义化视图）。
     *
     * `isRunning` 报的是**实际状态**，两者在多线程交错时可能短暂不一致 ——
     * 想知道"该不该拉起"看这个，想知道"现在能不能连上"看 [isRunning]。
     */
    val isDesiredRunning: Boolean get() = desired.get()

    /** 当前在途连接数（诊断用；`/health` 不暴露它，避免把内部读数当成契约）。 */
    val activeConnCount: Int get() = activeConns.size

    /**
     * 连接上限（诊断/UI 用）。与 [activeConnCount] 一起看才能回答
     * "现在是真闲着，还是贴着上限在拒连"。
     */
    val maxConns: Int get() = MAX_CONNS

    /** 因超过 [maxConns] 被拒的累计连接数（诊断用，不对外当契约）。 */
    val rejectedConnCount: Int get() = rejectedConns.get()

    /**
     * 是否有生成在跑 —— 供 UI 侧做「能不能卸载/重载/停服」的忙检查。
     *
     * 必须**两半都看**，缺一半就会各漏一种情况（这正是它此前的问题）：
     *   · [busy]（HTTP 侧）：从「受理了这个请求」就为真，一直到最后一次写响应；
     *   · `LlmEngine.isGenerating`（引擎侧）：只覆盖「prefill 成功 → 收尾」这段。
     * 两者**不互相包含**：
     *   · HTTP 请求受理之后、渲染 prompt / prefill 之前，只有 busy 为真（这段可能
     *     很长，prompt 渲染要走模板），只看引擎标志会让 UI 在此时卸载模型；
     *   · App 内聊天在跑时只有引擎标志为真，只看 busy 会放 HTTP 请求进来排队。
     * 原实现只看 busy，于是前一种情况安全、后一种漏；名字保留以免改动调用点。
     */
    val isGenerating: Boolean get() = busy.get() || LlmEngine.isGenerating

    /** 只有 **HTTP** 生成任务（`busy` 的对外视图，用于日志/诊断）。 */
    val isHttpGenerating: Boolean get() = busy.get()

    @Volatile private var server: ServerSocket? = null

    /** Service 加载模型后设置，用于 /v1/models 与日志。 */
    @Volatile var currentModel: String? = null

    /** 启动方（InferenceService）注入；为空时 /v1/models 优雅降级，绝不抛异常。 */
    @Volatile var appContext: android.content.Context? = null

    val isRunning: Boolean get() = running.get()

    private fun emitLog(s: String) {
        Log.i(TAG, s)
        try { LlmEngine.logSink?.invoke("[http] $s") } catch (_: Exception) {}
        // 探针模式下还要落到 native fd：HTTP 线程上的崩溃点与它前面那句日志
        // 只隔几微秒，走 JVM 文件写会丢。
        try { LlmEngine.probeMark("[http] $s") } catch (_: Exception) {}
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        desired.set(true)
        val myGen = listenerGen.incrementAndGet()
        Thread {
            var ss: ServerSocket? = null
            try {
                // bindAll=false 时绑 IPv4 回环。注意：部分 OEM 的 getLoopbackAddress()
                // 会返回 ::1，导致 IPv4 客户端全部拒连；bindAll=true 绑 0.0.0.0 供局域网访问（无鉴权）。
                val bindAddr = if (bindAll) "0.0.0.0" else "127.0.0.1"
                // 改为「先构造后绑定」，设置 SO_REUSEADDR —— stop/start 竞态后
                // 残留 TIME_WAIT/socket 不再导致 BindException。
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(InetAddress.getByName(bindAddr), PORT), 16)
                    // accept 5s 醒一次检查 running —— 消灭 stop() 竞态窗口下
                    // 「accept 永久阻塞 + socket 泄漏」的僵尸 LISTEN（端口在但没人 accept）。
                    soTimeout = 5_000
                }
                server = ss
                emitLog("listening http://$bindAddr:$PORT (model=${currentModel ?: "none"}) gen=$myGen")
                while (running.get()) {
                    val s = try { ss?.accept() } catch (e: java.net.SocketTimeoutException) {
                        continue  // 心跳超时属正常，回循环头检查 running
                    } catch (e: Exception) {
                        // accept 异常必须留痕（此前 catch(_){break} 静默死亡无日志）
                        if (running.get()) emitLog("accept error: ${e.message}")
                        break
                    } ?: break  // 监听 socket 已被 stop() 清空 → 退出
                    // 代际作废（已被新的一轮取代）时立刻收工：`running` 可能已被新 listener
                    // 重新置真，只看它会让旧循环拿着**已关闭**的 socket 继续 accept。
                    if (listenerGen.get() != myGen) break
                    // 每连接一线程。原单线程串行下，任一连接挂住（半开/慢发送）
                    // 会阻塞 accept 长达 soTimeout，后续连接全部饿死（health/models 全超时）。
                    //
                    // 登记在途连接（A-4）：不登记的话 stop() 只关 listening socket，
                    // 已受理的连接由各自线程的 `s.use{}` 才能释放 —— 关闭后仍在
                    // 为"已停的请求"把整段响应写完（生成路径只写不读，不受 soTimeout 约束）。
                    // ---- 在途连接上限（受理前判定，不是受理后补救）----
                    //
                    // 顺序很关键：**先占坑、再起线程**。反过来的话，"起线程"与"计数"
                    // 之间会有一个窗口 —— 那一瞬线程已经存在但上限看不见它，
                    // 并发 accept 时能一起挤进去，上限就不再是上限。
                    // 判据由 [admitConn] 一处承担（CAS 循环），这里只消费它的结论。
                    if (!admitConn(s)) {
                        // 超限拒绝：**回一个可读的 503 再关**，不静默 close。
                        // 静默 close 让客户端只看到"连接被重置"（curl: Empty reply from server），
                        // 与"服务崩了"完全同形；而这条路径上的 503 是"服务好着，只是满员"。
                        // 写这一小段发生在 accept 线程上、只有几十字节，且**不占线程槽** ——
                        // 所以它不会让"连接爆炸"重演。写失败（对端已走/半开）静默放弃。
                        rejectOverLimit(s)
                        continue
                    }
                    Thread({
                        try { handleConn(s) } catch (t: Throwable) {
                            Log.w(TAG, "conn error: ${t.message}")
                            try { s.close() } catch (_: Exception) {}
                        } finally {
                            // 登记表必须与 `s.use{}` 的释放同源：漏移除 = 一个永久引用
                            // （socket 已关但对象留着），表只涨不跌，排查时读数失真。
                            // 计数与登记表**同源增减**（都在 releaseConn 里）：
                            // 分别各自减会出现"计数泄漏"→ 服务**永久拒连**，且零症状。
                            releaseConn(s)
                        }
                    }, "http-conn").apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (listenerGen.get() == myGen && running.get()) emitLog("server error: ${e.message}")
            } finally {
                // 任何退出路径都关闭监听 socket —— 修复 fd 泄漏成「僵尸 LISTEN」
                // （现场特征：HTTP 线程已死亡但端口仍处 LISTEN，客户端表现为超时而非 refused）
                try { ss?.close() } catch (_: Exception) {}
                // ⚠ 清共享状态**必须先比代际**。无条件清是"僵尸 LISTEN 复发 +
                // 服务永久死亡"的根因：迟到的 finally 会把**新** listener 的状态踩掉，
                // 而新循环下一轮看到 running=false 就退出了。
                if (listenerGen.get() == myGen) {
                    running.set(false)
                    server = null
                } else {
                    // 留痕：这条分支正是"症状指向错方向"的那一类，没有日志就查不动
                    // （现场只会看到"端口在 LISTEN 但没人 accept"）。
                    emitLog("listener gen=$myGen 迟到退出：已被 gen=${listenerGen.get()} 取代，不清共享状态")
                }
            }
        }.apply { name = "llm-http-$myGen"; isDaemon = true }.start()
        startWatchdog()
    }

    /**
     * 受理一个连接：**先占计数、再登记**，两者必须同源。
     *
     * 用 CAS 循环而不是"读 size 再判断"：`activeConns.size` 与 add 之间不是原子的，
     * 两轮 listener 短暂并存（watchdog 重启窗口）时，两个 accept 线程同时读到 63
     * 就会一起挤进 64。CAS 让"判断 + 占位"是**一个**原子步。
     *
     * 返回值 = 是否受理。false 表示超限，调用方必须拒绝该 socket
     *（**不得**把它留在 backlog 里不管 —— 那会退化成"客户端超时无提示"，即 A-1 的假活形态）。
     */
    private fun admitConn(s: Socket?): Boolean {
        while (true) {
            val cur = connCount.get()
            if (cur >= MAX_CONNS) return false
            if (connCount.compareAndSet(cur, cur + 1)) break
        }
        // 占坑成功后登记。add 失败不可能（ConcurrentHashMap 的 keySet 不会失败），
        // 但**登记必须发生在占坑之后**：反过来的话，登记表里会短暂出现一个
        // 还没被上限看见的连接（stop() 收敛时把它的 socket 关了，而计数没动）。
        s?.let { activeConns.add(it) }
        return true
    }

    /**
     * 释放一个连接：计数与登记表**必须同一处减**。
     *
     * 分别各自减的失效形态是"计数泄漏" —— `connCount` 只涨不跌，
     * 服务会在某个时刻起**永久拒连**（[admitConn] 恒返 false），
     * 而 `activeConns` 是正常的：症状是"连接数没满却全被 503"。
     * 这类零症状故障是本文件反复在防的东西，所以增减收在一处、不给人拆开的机会。
     */
    private fun releaseConn(s: Socket?) {
        // ⚠ 以 `remove` 的**返回值**为准，而不是无条件 `decrementAndGet()`。
        //
        // 因为 [stop] 会清空登记表（并替这些连接减一次计数，见那里）。若这里
        // 仍然无条件减，那么"被 stop() 清掉、随后线程才跑 finally"的连接会被**减两次**
        // —— `connCount` 会变成负数，而 CAS 从负值起步意味着上限**永久失真**
        //（本该 64，实际能塞进 64+|负数| 条）。`remove` 返回 false 恰好表示
        //"这一次释放已经由 stop() 负责过了"，语义正好对得上。
        if (s != null && activeConns.remove(s)) connCount.decrementAndGet()
    }

    /**
     * 超限拒绝：回一个可读的 503 再关，**不静默 close**。
     *
     * 为什么不是"直接 close"：静默 close 让客户端拿到的是 `Empty reply from server`
     *（连接被重置），与"服务崩了"完全同形，用户会去查模型/进程，而真因是连接满员。
     *
     * 为什么不是"不 accept，让内核 backlog 兜"：backlog 只有 16，满了之后
     * SYN 被静默丢弃 → 客户端**超时且无任何提示**，正是本项目 A-1 要防的
     *「假活」形态（端口在、连得上、就是没人应）。回 503 至少给出一个可读状态码。
     *
     * 实现在 accept 线程上、只写几十字节，**不占 http-conn 线程槽** ——
     * 所以这条路径本身不会成为新的"线程爆炸"入口。
     * socket 上写失败（对端已走 / 半开 / 无输出流）一律静默放弃：
     * 这条路径的全部意义只是"尽量告诉对方"，不值得为它再起线程或重试。
     */
    private fun rejectOverLimit(s: Socket?) {
        if (s == null) return
        rejectedConns.incrementAndGet()
        // 留痕（每 16 次一条，避免被刷爆日志；第一次必留）——
        // 不记的话"被连接上限拒了"与"被生成背压拒了"在日志里完全同形，
        // 而两者的处置不同：前者说明有东西在刷连接，后者等一会儿就好。
        val n = rejectedConns.get()
        if (n == 1 || n % 16 == 0) {
            emitLog("拒绝超限连接：在途已达 $MAX_CONNS（累计拒绝 $n 次）—— 有客户端在刷连接？")
        }
        try {
            // soTimeout 约束的是 **read** 方向（这里用不到），列在这里只为让
            // "这条路径有超时"这件事在代码里可查；真正的安全性来自
            // "200 字节 << 内核发送缓冲（默认 16KB+）"，单次 write 不会阻塞 accept。
            s.soTimeout = 1_000
            val body = errJson("too many connections (limit $MAX_CONNS in flight); retry later")
            val out = s.getOutputStream()
            // 走与正常响应**同一个**写入器：状态行/头/长度都由它保证一致，
            // 这里不再手写一份（手写的第二份必然与它漂移，那是 B 轮踩过的坑）。
            // extraHeaders 留空：writeRaw 自己已经写 `Connection: close`
            // （重复写会让同一响应里出现两次该头 —— 重复头的处理属未定义行为，
            //  这正是模块 B 第一轮我误报过、后来自己更正过的那类"叠加头"）。
            writeRaw(out, 503, body, corsOrigin = null)   // 它自己以 flush 收尾
        } catch (_: Exception) {
            // 对端已走 / 半开：这条路径只负责"尽量告知"，写失败不是故障。
        } finally {
            // 必须关：不关的话 fd 会随拒绝次数线性泄漏（拒绝率越高漏得越快），
            // 而"拒绝"恰恰是高压时的常态。
            try { s.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        // ① 先表明"用户不要了" —— 看门狗据此**不**重启（这是 desired 存在的唯一理由）。
        desired.set(false)
        // ② 递增代际：让所有在途 listener 的 `finally` 自动作废，且让它们的 accept
        //    循环下一轮就 break。必须先递增，再关 socket。
        listenerGen.incrementAndGet()
        running.set(false)
        // ③ 关"当前活着的"监听 socket。注意 `server` 有可能被迟到的 finally 清成 null
        //    （这正是 A-1 的组合故障），所以**不能只依赖它**；代际 + 下面的在途收敛
        //    才是兜底。
        try { server?.close() } catch (_: Exception) {}
        server = null
        // ④ 收敛在途连接：不收敛的话"服务已停，仍在把已停请求的整段响应写完"
        //    （生成路径只写不读，不受 soTimeout 约束）。
        // 计数必须与登记表**同源**收敛：只 clear() 不减计数的话，被清掉的连接
        // 再也不会由 [releaseConn] 减（它的 remove 会返回 false），`connCount` 只涨不跌
        // → 服务在若干次 stop/start 之后**永久拒连**，而 activeConns 看着是空的。
        // 这正是"零症状故障"的形态，所以这里逐条减、再用一次 CAS 兜底钳到 0。
        // 先快照再处理：`activeConns` 是并发集合，遍历时由别的线程（各连接的
        // finally）同时 remove 是常态。快照让"关哪些 socket"与"清哪几次计数"
        // 是**同一批**对象，不会因为边遍历边改而漏关或重复减。
        for (c in activeConns.toList()) {
            try { c.close() } catch (_: Exception) {}
            if (activeConns.remove(c)) connCount.decrementAndGet()
        }
        // 兜底：任何未预料的路径都不许让计数停在负数（负值会让上限失真）。
        while (true) {
            val cur = connCount.get()
            if (cur >= 0) break
            if (connCount.compareAndSet(cur, 0)) break
        }
        activeConns.clear()
    }

    // ---- 看门狗自愈 ----
    // 背景：部分 OEM 的后台冻结策略（切后台即冻、亮屏后也冻）与
    // accept 线程异常死亡都会造成「端口 LISTEN 但没人 accept」的假活态。
    // 每 30s 自连探活，连续 2 次失败自动重启监听 —— 断联自愈窗口 30~60s。
    private val watchdogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun startWatchdog() {
        if (!watchdogRunning.compareAndSet(false, true)) return
        Thread {
            var failStreak = 0
            try {
                while (true) {
                    Thread.sleep(30_000)
                    // ⚠ 判据是 desired（用户意图），**不是** running（当前状态）。
                    // 只看 running 时，A-1 一踩就把这里变成死分支 —— 设计目标
                    // "断联 30~60s 自愈"退化成"一旦踩中永久躺平"：
                    // 那次 stop() 会把 desired 置 false，而错误路径上的 stop() 与
                    // 用户主动停服在 running 上完全同形（都是 false），
                    // 只有 desired 能区分"我不该重启"与"我该重启"。
                    if (!desired.get()) { failStreak = 0; continue }  // 用户没要求跑，仅休眠待命
                    if (probeHealthy()) {
                        if (failStreak >= 2) emitLog("[watchdog] 自检恢复正常")
                        failStreak = 0
                        continue
                    }
                    failStreak++
                    emitLog("[watchdog] 自连探活失败 $failStreak/2 (port=$PORT, running=${running.get()})")
                    if (failStreak >= 2) {
                        // 重启判据只有一处（见 restartListenerIfDesired 的注释）：
                        // 内联在这里时，新增的探活调用点都得自己记得补 desired 判据，
                        // 漏一个就是"用户已停服却被自动拉起"这种后台偶发故障。
                        if (restartListenerIfDesired("连续 $failStreak 次自连探活失败")) {
                            failStreak = 0
                            Thread.sleep(2_000)  // 新 listener 稳定窗口
                        } else {
                            failStreak = 0  // 用户已停服：不重启，静默待命
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                watchdogRunning.set(false)
            }
        }.apply { name = "llm-watchdog"; isDaemon = true }.start()
    }

    /**
     * 探活失败时的自愈入口：**仅当用户仍想要服务在跑**（[desired]）时才重启。
     *
     * 抽成函数是为了让"想不想重启"这条判据只有一处 —— 内联在 watchdog 循环里时，
     * 任何新增的探活调用点都得自己记得补 desired 判据，漏一个就是一处
     * "用户已停服却被自动拉起"（而它只在后台偶发，真机上几乎抓不到）。
     * 返回值 = 是否真的重启了一轮。
     */
    private fun restartListenerIfDesired(reason: String): Boolean {
        if (!desired.get()) return false
        emitLog("[watchdog] 监听假活（$reason），自动重启 listener（模型不受影响）")
        stop()
        desired.set(true)  // stop() 把它清了；本轮是"用户本来就要跑"，补回来
        Thread.sleep(500)
        start()
        return true
    }

    /** 自连探活：TCP 连上并发 GET /health，2s 内收到任意响应字节即健康。 */
    private fun probeHealthy(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 2_000)
            s.soTimeout = 2_000
            s.getOutputStream().apply {
                write("GET /health HTTP/1.0\r\n\r\n".toByteArray())
                flush()
            }
            s.getInputStream().read(ByteArray(16)) > 0
        }
    } catch (_: Exception) {
        false
    }

    // ---- 请求处理 ----

    private class Req(
        val method: String,
        val path: String,
        val body: String,
        /** `Origin` 请求头（跨源请求才有）；用于 CORS 白名单判定。 */
        val origin: String? = null,
        /** `Access-Control-Request-Headers`（预检才有）；仅用于日志取证。 */
        val reqHeaders: String? = null,
        /**
         * `Authorization` 请求头（原样，未拆 Bearer）。鉴权判定走 [ApiAuth.bearer]，
         * 这里只负责「**读出来**」—— 与 Origin 同一个道理：不读出来，
         * 白名单/鉴权就只能退化成"要么全放行、要么全拦"。
         */
        val auth: String? = null,
        /** 请求体声明长度超过 [MAX_BODY_BYTES]（→ 413，**不读 body**）。 */
        val tooLarge: Boolean = false,
        /** 请求行或某个头行超过 [MAX_LINE_LEN]（→ 400，见 [readLine]）。 */
        val overlongLine: Boolean = false,
    )

    /**
     * 「这一行超长」的哨兵。用**对象**而不是某个字符串字面量的原因：
     * 任何客户端都无法让一个正常读出的行恰与它 `===` 相等。
     * [readLine] 靠它把"超长"与"读完（EOF）/头结束（空行）"分开 ——
     * 混在一起时，超长行之后的所有头会被静默丢弃（见 [parseReq]）。
     */
    private val LINE_TOO_LONG = String()

    /** 头/请求行的长度上限（字节 ≈ 字符，头里全是 ASCII）。 */
    private const val MAX_LINE_LEN = 8192

    /** 请求体上限。超限**拒绝**（413），不截断。 */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /**
     * 路由用的"落在某前缀之下"判定（与 [ApiAuth.under] 同一条判据）。
     *
     * 裸 `startsWith` 会把 `/healthz`、`/v1/models-evil` 也算命中，
     * 于是将来任何 `/health*` / `/v1/models*` 端点都自动进入免鉴权集合 ——
     * fail-open 且**零症状**，与 [ApiAuth] 文件头承诺的方向正好相反。
     */
    private fun pathUnder(path: String, base: String): Boolean {
        if (path == base) return true
        if (base.endsWith("/")) return path.startsWith(base)
        return path.startsWith("$base/") || path.startsWith("$base?")
    }

    private fun handleConn(s: Socket) {
        s.soTimeout = 15_000  // 15s 内未收到完整请求即断开；生成期只写不读，不受影响
        s.use { sock ->
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            val req = parseReq(ins) ?: return
            // ---- CORS 白名单判定（**每个响应**都要带，不只是预检）----
            //
            // 判据在 CorsPolicy（纯函数、可离线单测）。这里只做三件事：
            //   ① 算出允许回显的 Origin（null = 不放行 → 不加任何 CORS 头）；
            //   ② 把它透传给响应写入器（普通响应也带，否则"预检过了、正式请求被拦"
            //      这种最难查的形态就会出现）；
            //   ③ 预检单独走一个 204 分支。
            //
            // ⚠ 位置必须在**所有** `writeJson` 之前 —— 下面的 400/413 拒绝也带 CORS 头，
            //   所以它们是 corsOrigin 的**首批读者**。写在它们之后 = 先用后声明，
            //   局部变量在 Kotlin 里没有提升，直接编译不过
            //   （2026-09-22 CI 红：`unresolved reference 'corsOrigin'` ×2）。
            //   那一轮 22 个脚本都在 grep 这个文件，但**没有一个编译它** ——
            //   判据见 tools/run_http_hardening_guard.sh 判据⑨（结构顺序断言）。
            val corsOrigin = CorsPolicy.allow(req.origin)
            // 来源被拒时留一行日志：浏览器侧看到的只是"请求失败"，
            // 没有这行日志就无法区分"来源被拦"与"服务坏了"。
            if (req.origin != null && corsOrigin == null) {
                emitLog("CORS 拒绝来源: ${req.origin}（不在白名单内，未回 CORS 头）")
            }
            // 畸形请求**必须**挡在路由与鉴权之前，且**各自回自己的码**：
            // 两者都曾退化成"静默丢内容"，症状分别是"token 明明没错却说不匹配"
            //（头被丢）与"服务内部错误"（body 被截断后 JSON 解析炸）。
            // 这两个码（400/413）都不在 CORS 白名单外豁免的讨论范围内：
            // 它们不是业务拒绝，而是"这个请求本身没读全"，回什么都救不了。
            if (req.overlongLine) {
                emitLog("拒绝畸形请求: 请求行或头行超长（> ${MAX_LINE_LEN} 字节）")
                writeJson(out, 400, errJson("request line or header line too long " +
                    "(limit $MAX_LINE_LEN bytes)"), corsOrigin)
                return
            }
            if (req.tooLarge) {
                emitLog("拒绝超限请求体: 声明超过 ${MAX_BODY_BYTES} 字节（未读 body，直接 413）")
                writeJson(out, 413, errJson("request body too large " +
                    "(limit $MAX_BODY_BYTES bytes)"), corsOrigin)
                return
            }
            // /health 与 /v1/models 为高频轮询端点，只写 logcat、不推 UI 日志。
            // 历史缺陷：每个请求都走 logSink → ui.post → renderLog（120 行 TextView 全量重排），
            // 刷新风暴会占满主线程 → 整个应用卡顿 → 流式生成停摆、客户端断联。
            if (req.method == "GET" && (pathUnder(req.path, "/health") || pathUnder(req.path, "/v1/models"))) {
                Log.i(TAG, "${req.method} ${req.path}")
            } else {
                emitLog("${req.method} ${req.path}")
            }
            // 预检（OPTIONS）**必须**排在真实路由之前，且**不参与任何业务校验**
            //（鉴权已落地：下面的鉴权判定刻意排在它**之后** —— 预检不带 Authorization，
            //  拦了等于 CORS 白做。这条位置由 run_auth_guard.sh 的一条顺序断言钉住）。
            if (req.method == "OPTIONS") {
                // 白名单外也回 204：头缺失本身就是拒绝（CorsPolicy 文件头解释了为什么不用 403）。
                // `preflight` 返回 null 就是"不放行"（与 allow 同义），折成空串 ——
                // 不能写成 `!!`：白名单外回 204 是**正常路径**，不是异常。
                writeRaw(out, 204, "", extraHeaders = CorsPolicy.preflight(
                    req.origin, PORT, req.reqHeaders, bindAll) ?: "")
                return
            }
            // ---- 鉴权（只针对生成端点；判据在 ApiAuth.requiresAuth）----
            //
            // 位置很关键，三件事按这个顺序：
            //   ① OPTIONS 之后 —— 预检**必须**豁免（浏览器自动发、不带 Authorization，
            //      拦了等于 CORS 白做）；
            //   ② 真实路由**之前** —— 挡在 `handleChat` / `handleCompletion` 外面，
            //      不许"进到端点里再判"：那样每个端点都要各写一遍，漏一个就是
            //      一个无鉴权的后门，而漏掉的那条不会有任何现象；
            //   ③ body 已经读完 —— 这里直接回 401 并关连接，不必再读 body。
            //
            // `/health`、`/v1/models`、`GET /` 一律免鉴权（理由见 [ApiAuth] 文件头）：
            // 服务端自己的看门狗、App 内「存活探测」打的都是 `/health`，
            // 要 token 等于**自检把自己判成不可达**。
            if (ApiAuth.requiresAuth(req.method, req.path)) {
                val deny = ApiAuth.verdict(req.auth)
                if (deny != null) {
                    // 日志必须留痕（浏览器/客户端只看到 401，分不清"没开鉴权"与"带错"）。
                    // **绝不打印 token 本身**，也不打印它的任何片段或长度。
                    val had = ApiAuth.bearer(req.auth).isNotEmpty()
                    emitLog("鉴权拒绝: ${req.method} ${req.path}（" +
                        (if (had) "凭据不匹配" else "未带 Authorization 头") + "）")
                    writeRaw(out, 401, errJson(deny),
                        extraHeaders = ApiAuth.unauthorizedHeaders(), corsOrigin = corsOrigin)
                    return
                }
            }
            when {
                req.method == "GET" && pathUnder(req.path, "/health") ->
                    writeJson(out, 200, healthJson(), corsOrigin)
                req.method == "GET" && pathUnder(req.path, "/v1/models") ->
                    writeJson(out, 200, modelsJson(), corsOrigin)
                // 自带测试页（**同源**，内嵌 HTML，零外部依赖）：
                // 浏览器直接打开 http://<手机IP>:<端口>/ 就能聊。
                // 它不需要 CORS —— 这也是"CORS 修好了并不能让自带网页能用"的反面：
                // 真正让它能用的是这条路由，两件事各自独立、都要有。
                req.method == "GET" && (req.path == "/" || req.path.startsWith("/?")) ->
                    // `modelDesc` 直接取，**不要**写成 `if (hasModel) modelDesc() else null`：
                    // `modelDesc()` 声明为非空 String，那个写法在 Kotlin 里推成 String?
                    // 直接编译不过（2026-09-20 CI 挂在 `pageHtml` 第 4 个入参上）。
                    // 而且它想省的那次调用本身就是安全的：没模型时 nativeModelDesc
                    // 走 `if (!S.model) return NewStringUTF("")`，返回空串而非 null。
                    writeHtml(out, 200, CorsPolicy.pageHtml(
                        PORT, bindAll, LlmEngine.hasModel, LlmEngine.modelDesc()), corsOrigin)
                req.method == "POST" && pathUnder(req.path, "/v1/chat/completions") ->
                    handleChat(req, out, corsOrigin)
                req.method == "POST" && pathUnder(req.path, "/v1/completions") ->
                    handleCompletion(req, out, corsOrigin)
                // 取消端点放在两个生成端点之后：路径前缀互不包含，顺序不影响行为，
                // 但读起来「先补全、后取消」更贴合使用顺序。
                req.method == "POST" && pathUnder(req.path, "/v1/abort") ->
                    writeJson(out, 200, handleAbort(), corsOrigin)
                else ->
                    writeJson(out, 404, errJson("not found: ${req.path}"), corsOrigin)
            }
        }
    }

    private fun parseReq(ins: InputStream): Req? {
        val first = readLine(ins) ?: return null
        if (first === LINE_TOO_LONG) return Req(method = "", path = "", body = "", overlongLine = true)
        val sp = first.split(' ')
        if (sp.size < 2) return null
        val method = sp[0]
        val path = sp[1]
        var contentLength = 0
        var origin: String? = null
        var reqHeaders: String? = null
        var auth: String? = null
        while (true) {
            // `?: break` 这里只表示 EOF（连接在半路断了）—— 超长**不**返回 null，
            // 而是在下一行被单独判掉。两者分开是 B-3 的修复本体，别把顺序调过来。
            val line = readLine(ins) ?: break
            if (line === LINE_TOO_LONG) return Req(method = "", path = "", body = "", overlongLine = true)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) continue
            val name = line.substring(0, i).trim().lowercase()
            val value = line.substring(i + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull() ?: 0
                // 鉴权凭据也必须**读出来**：不读的话 [ApiAuth.verdict] 永远拿到 null，
                // 于是要么把所有人挡在门外、要么（若顺手 fail-open）等于鉴权没做。
                "authorization" -> auth = value
                // Origin 与预检头都要**读出来**（不能只靠响应侧拼头）：
                // 白名单判定必须有来源，没有它就退化成"要么全放行、要么全拦"，
                // 而这两者都不是"白名单式"。
                "origin" -> origin = value
                "access-control-request-headers" -> reqHeaders = value
            }
        }
        // 超出上限**直接拒**（不是截断）：`coerceAtMost` 会把多余字节留在 socket 里
        // 没人读，截断后的 JSON 再交给解析器去炸 —— 客户端拿到的是「服务内部错误」，
        // 真因却是「请求体超限」。宁可回一个说明白了的 413。
        if (contentLength > MAX_BODY_BYTES) {
            return Req(method, path, "", origin, reqHeaders, auth, tooLarge = true)
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else ""
        return Req(method, path, body, origin, reqHeaders, auth)
    }

    /**
     * 读一行（不含行尾 CRLF）。
     *
     * 返回 `null` = 连接在读满一行之前就结束了（EOF）；返回 [LINE_TOO_LONG] = 行超长。
     * 两者**必须**区分：此前都返回 `null`，于是调用方的 `?: break` 把
     * "超长"与"头结束"混成同一件事 —— 超长头之后的内容被整段静默丢弃。
     * 这是哨兵对象（不是一个正常不会出现的字符串字面量）的原因：
     * 任何客户端都无法伪造出 `===` 相等。
     */
    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_LINE_LEN) return LINE_TOO_LONG
        }
        return sb.toString()
    }

    // ---- OpenAI 兼容端点 ----

    private fun healthJson(): String {
        val loaded = LlmEngine.hasModel
        val desc = if (loaded) LlmEngine.modelDesc().toJsonStr() else "null"
        val ctxUsed = if (loaded) LlmEngine.contextUsed() else 0
        val ctxSize = if (loaded) LlmEngine.contextSize() else 0
        // `generating` 与 `busy` 分开报：`busy` 是 HTTP 侧「我不能接新请求」，
        // `generating` 是「引擎此刻真在跑」（含 App 内聊天）。只看 busy 会把
        // 「UI 正在生成、HTTP 请求会被 503」这种状态报成"闲着"，排查时对不上。
        // `/v1/abort` 打的就是 generating 那一轮，所以这个字段也是它的可观测面。
        // 取 `LlmEngine.isGenerating`（= 引擎侧那一份）而**不**加上 `busy`：
        // 加了之后 `generating` 就退化成 busy 的复制品，两字段再也分辨不出
        // 「HTTP 已受理但还没开始跑」与「真在跑」，那正是它存在的意义。
        val generating = LlmEngine.isGenerating
        // KV 前缀复用（prompt cache）的三个可观测字段。
        //
        // 为什么放进 /health 而不是只留在日志里：复用是**纯加速**，
        // 生效与不生效在协议上完全看不出来（同样的 200、同样的 token、同样的 finish_reason）。
        // 没有这三个字段，「缓存到底有没有工作」就只能开 native 探针去读日志 ——
        // 而那需要人先动手，于是它极容易在回归里静默失效而无人发现。
        //   · kv_cache_valid    —— 账本有效（下一轮**可能**复用）；
        //   · kv_reuse_tokens   —— 上一轮实际复用了多少 token；
        //   · kv_prefill_tokens —— 上一轮**新算**了多少 token；
        //   · kv_rounds         —— 已经跑完 prefill 的轮次数。
        // kv_rounds 不是"锦上添花"：kv_cache_valid=false 同时覆盖两种**完全不同的**
        // 处境 —— 「从未跑过任何请求」（冷启动，正常）与「跑过、但账本已被作废」
        //（取消 / 换模型 / prompt 超长，是故障或取消）。没有它，这两者同形，
        // 读 /health 的人会把"缓存一次没生效"与"这次被取消了"读成同一件事。
        // 读法：kv_rounds==0 = 冷启动；kv_rounds>0 且 !kv_cache_valid = 本轮的账本已失效；
        // 多轮对话里第二个请求之后 kv_reuse_tokens 应显著大于 0；
        // kv_prefill_tokens 恒等于本轮「新增的那部分」，**绝不该是 0**
        //（0 是"复用把整段都当成已缓存"的症状：采样步会没有 logits ——
        //  kv_prefix.h 的约束 ④ 就是专门防它的）。
        val kvValid = if (loaded) LlmEngine.kvCacheValid else false
        val kvReuse = if (loaded) LlmEngine.lastReuseTokens else 0
        val kvPrefill = if (loaded) LlmEngine.lastPrefillTokens else 0
        val kvRounds = if (loaded) LlmEngine.kvRounds else 0
        // 注意 Kotlin 原始字符串（"""..."""）的行尾引号计数：
        // 拼接段必须以 `"""` 收尾（字面量内容 0 个引号）；若写成 `""""`，
        // 最后一个引号会被当成字面量内容，JSON 里就多出一个 `"`，整份响应立刻非法。
        // 段首同理：`""""` = 结束符 `"""` + 内容 `"`。
        //
        // ⚠ 这里是**恰好 4 段**的拼接，不是随手折的行：`tools/run_health_json_guard.py`
        // 会按 Kotlin 词法把每一段还原成字面量再拼成真 JSON 去解析，判据里第一条就是
        // "有 4 段"。加字段时**不要新增一段**（多一段立刻红），把字段并进现有段里。
        // 反过来，段数对不上时那条守卫会先红在"段数"上，比 JSON 解析报的错好查得多。
        val json = """{"status":"ok","model_loaded":$loaded,"model":$desc,""" +
            """"ctx_used":$ctxUsed,"ctx_size":$ctxSize,"busy":${busy.get()},"port":$PORT,""" +
            """"generating":$generating,"cancelled":${LlmEngine.currentCancel?.cancelled ?: false},""" +
            """"kv_cache_valid":$kvValid,"kv_reuse_tokens":$kvReuse,"kv_prefill_tokens":$kvPrefill,"kv_rounds":$kvRounds}"""
        // 自检：手拼 JSON 一旦出引号错，探活/监控会把健康的服务判为不可达。
        // 这里用 JSONObject 解析兜底，宁可抛异常暴露问题，也不静默发出非法 JSON。
        return try {
            JSONObject(json).toString()
        } catch (e: Exception) {
            errJson("health json malformed: ${e.message}")
        }
    }

    private fun modelsJson(): String {
        // org.json 结构化输出：模型列表带路径/大小/时间/量化等富信息。
        // 注意：toJsonStr() 自带引号，手拼 JSON 时再嵌一层引号会产生非法 JSON，
        // 客户端就读不到模型列表了。
        // 模型未加载 -> 空列表（服务仍可用，生成请求将得到 503 提示）
        if (!LlmEngine.hasModel) {
            val empty = JSONObject()
            empty.put("object", "list")
            empty.put("data", JSONArray())
            empty.put("models", JSONArray())
            return empty.toString()
        }
        val ctx = appContext
        val files = try {
            if (ctx != null) ModelStore.list(ctx) else emptyList()
        } catch (_: Throwable) { emptyList() }
        val cur = currentModel
        val root = JSONObject()
        root.put("object", "list")
        val modelsArr = JSONArray()
        val dataArr = JSONArray()
        if (files.isEmpty()) {
            // 降级：拿不到模型库时只报当前加载模型（别名），保证连接可用
            cur?.let { alias ->
                dataArr.put(JSONObject().put("id", alias).put("object", "model").put("owned_by", "local-llm-server"))
                modelsArr.put(JSONObject().put("name", alias).put("model", alias).put("type", "model"))
            }
        } else {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val seen = HashSet<String>()
            for (f in files) {
                val alias = ModelStore.aliasOf(ctx!!, f.name)
                // 主标识用别名（Ollama 风格短名，不暴露内部路径）；
                // 重名时依次用"文件名去后缀/完整路径"兜底，保证 id 唯一
                val id = listOf(alias, f.name.removeSuffix(".gguf"), f.absolutePath)
                    .first { seen.add(it) }
                val quant = Regex("(?i)(iq\\d+[a-z0-9_]*|q\\d+[a-z0-9_]*|f16|bf16|f32)").find(f.name)?.value?.uppercase() ?: ""
                val params = Regex("(\\d+(?:\\.\\d+)?)\\s*[Bb]").find(f.name.removeSuffix(".gguf"))?.let { m -> m.groupValues[1] + "B" } ?: ""
                modelsArr.put(JSONObject()
                    .put("name", id)
                    .put("model", id)
                    .put("modified_at", fmt.format(java.util.Date(f.lastModified())))
                    .put("size", f.length())
                    .put("digest", "")
                    .put("type", "model")
                    .put("description", f.name)
                    .put("tags", JSONArray())
                    .put("capabilities", JSONArray().put("completion"))
                    .put("parameters", "")
                    .put("details", JSONObject()
                        .put("parent_model", "")
                        .put("format", "gguf")
                        .put("family", "")
                        .put("families", JSONArray().put(""))
                        .put("parameter_size", params)
                        .put("quantization_level", quant)))
                dataArr.put(JSONObject()
                    .put("id", id)
                    .put("aliases", JSONArray().put(alias))
                    .put("tags", JSONArray())
                    .put("object", "model")
                    .put("created", f.lastModified() / 1000)
                    .put("owned_by", "llamacpp")
                    .put("meta", JSONObject()
                        .put("size", f.length())
                        .put("ftype", quant)))
            }
            // 当前加载模型不在库里时（异常情况），补一个别名条目保证客户端可选
            val listedAliases = files.mapNotNull { f -> ctx?.let { c -> ModelStore.aliasOf(c, f.name) } }
            if (cur != null && cur !in listedAliases) {
                dataArr.put(JSONObject().put("id", cur).put("object", "model").put("owned_by", "local-llm-server"))
            }
        }
        root.put("models", modelsArr)
        root.put("data", dataArr)
        return root.toString()
    }

    private fun handleChat(req: Req, out: OutputStream, corsOrigin: String? = null) {
        if (!LlmEngine.hasModel) { writeJson(out, 503, errJson("模型未加载：先在 App 内选择模型并启动服务"), corsOrigin); return }
        // 503 的判据是 `busy`（HTTP 在跑）**或** 引擎已被别的入口占用（App 内聊天）。
        // 只看 busy 会放进来一个请求，它会卡在 `synchronized(LlmEngine.genLock)` 上
        // 直到 App 那轮结束 —— 客户端看到的是"连上了、迟迟不回"，比立刻 503 难查得多。
        //
        // ⚠ 两条判据必须**分开**，且回滚只回滚"自己刚抢到的那一份"。
        // 旧写法 `if (!CAS(false,true) || isGenerating) { busy.set(false); ... }` 里，
        // 无条件 `set(false)` 在 CAS **失败**时清的是**别人**的占位：
        //   B 抢到 busy=true 开始生成 → C 的 CAS 失败进分支 → C 把 busy 清成 false →
        //   D 的 CAS 成功、与 B **并发**受理（生成循环仍靠 genLock 串行，不会内存错乱，
        //   但代价是两道防线同时失效）：
        //     · 503 背压失效：本应被拒的 D 被放进来了，它会挂在 genLock 上，
        //       客户端表现为"连上了迟迟不回" —— 正是本段注释自己说要避免的形态；
        //     · /health 的 busy 与 HttpApi.isGenerating 说谎 → EngineActivity 用它做
        //       "能不能卸载/停服"判断时会**误放**。
        if (!busy.compareAndSet(false, true)) {
            writeJson(out, 503, errJson("server busy（已有生成任务进行中）"), corsOrigin); return
        }
        if (LlmEngine.isGenerating) {
            busy.set(false)  // 这份占位是**我自己**刚抢到的，回滚安全
            writeJson(out, 503, errJson("server busy（已有生成任务进行中）"), corsOrigin); return
        }
        try {
            val j = JSONObject(req.body)
            val stream = j.optBoolean("stream", false)
            // 采样参数统一走 SamplingParams 校验：非法值直接 400，不再静默降级（历史坑：
            // top_p=0 被静默关掉、repeat_penalty<1 语义反转、min_p 默认值与 UI 不一致）。
            // max_tokens 也在此处按 1..8192 校验（此前用 coerceIn 静默钳制）。
            // fromRequest 已逐字段校验并给出原因；返回 null 即取值非法，直接 400。
            val (sp, spErr) = SamplingParams.fromRequest(j)
            if (sp == null) { writeJson(out, 400, errJson(spErr ?: "采样参数非法"), corsOrigin); return }
            // stop / stop_sequences 与采样参数同一套原则：取值非法直接 400，不静默忽略。
            // 拦截本身在 native（local-stop 采样器，逐 token 判），这里只做请求侧解析与校验；
            // 非流式另有 truncate 兜底（见 StopSequences 文件头）。
            val (stopsRaw, stopErr) = StopSequences.fromRequest(j)
            if (stopsRaw == null) { writeJson(out, 400, errJson(stopErr ?: "stop 参数非法"), corsOrigin); return }
            // 条数超内部上限只截断 + 落日志，**不** 400：条数是性能边界、
            // 不是协议合法性边界。曾在此硬报 400，把"两个别名各塞一批"的正常客户端
            // 整个请求拒掉，客户端表现为完全无法生成（详见 StopSequences.MAX_SEQUENCES）。
            val stops = StopSequences.capped(stopsRaw)
            if (stops.size != stopsRaw.size) emitLog(StopSequences.truncatedNotice(stopsRaw.size))
            // response_format（结构化输出）与采样参数同一套原则：**协议级错误报 400**
            // （类型/取值拼错、schema 不是对象、包装对象缺字段），而"库转换不了这个 schema"
            // 属于能力边界 —— 那时不失败请求，只在 native 侧降级并落日志（判据与理由见
            // JsonSchemaFormat 文件头）。字段不存在时必须不产生任何 native 调用。
            val (respFormatRaw, rfErr) = JsonSchemaFormat.fromRequest(j)
            if (respFormatRaw == null) { writeJson(out, 400, errJson(rfErr ?: "response_format 非法"), corsOrigin); return }
            val respFormat = respFormatRaw
            // 请求级关联码：**生成前**就要有（请求侧这几行与生成后的 `[body]` 都要带上它，
            // 否则三例连发时"哪一行请求配哪一行输出"只能靠数顺序）。取数字尾巴，见 reqTagOf。
            val id = "chatcmpl-local-${System.currentTimeMillis()}"
            val reqTag = JsonSchemaFormat.reqTagOf(id)
            if (respFormat !is ResponseFormat.None)
                emitLog("[id=$reqTag] " + JsonSchemaFormat.describe(respFormat))
            val maxTok = sp.maxTokens
            val modelName = j.optString("model", "local").ifEmpty { "local" }

            val msgs = ArrayList<Pair<String, String>>()
            val arr = j.optJSONArray("messages")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = m.optString("role", "user")
                var content = m.optString("content")
                val ca = m.optJSONArray("content") // 多模态数组：取 text 段拼接
                if (ca != null) {
                    val sb = StringBuilder()
                    for (k in 0 until ca.length()) {
                        val o = ca.optJSONObject(k)
                        if (o != null && o.optString("type") == "text") sb.append(o.optString("text"))
                    }
                    if (sb.isNotEmpty()) content = sb.toString()
                }
                msgs.add(role to content)
            }
            if (msgs.isEmpty()) { writeJson(out, 400, errJson("messages is empty"), corsOrigin); return }

            // ---- 工具调用（OpenAI tools / function calling）----
            // 请求带 tools 时必须走「工具感知」模板渲染：工具定义要进 prompt，
            // 否则模型不知道自己有哪些函数可用，永远吐不出 tool_calls。
            // 解析逻辑在 ToolCalls（纯函数，可离线单测），这里只做取用
            val hasTools = ToolCalls.hasTools(j)
            val toolsJson = if (hasTools) ToolCalls.toolsJson(j) else null
            LlmEngine.probeMark("[http] 请求分类：has_tools=$hasTools stream=$stream " +
                    "tools_count=${if (hasTools) j.optJSONArray("tools")?.length() ?: 0 else 0} " +
                    "body_len=${req.body.length} thread=${Thread.currentThread().name}")
            if (hasTools) LlmEngine.probeMark("[http] tools 原文=${toolsJson?.take(600)}")
            val toolChoice = ToolCalls.parseToolChoice(j, hasTools)
            val parallelToolCalls = j.optBoolean("parallel_tool_calls", true)
            // toolChoice 带 tools 时已由 parseToolChoice 保证非 null（缺省归一成 "auto"），
            // 这里不再显示 null，避免"看起来没传"这种会被误读的日志。
            if (hasTools) emitLog("tools: ${j.optJSONArray("tools")!!.length()} 个，choice=${toolChoice ?: "auto"}")

            // 思考控制 —— 请求显式参数 > 全局默认。
            //
            // 关闭思考有**两条**路，谁在前谁在后是有讲究的（见 ThinkingControl 文件头）：
            //   ① 模板自带 `enable_thinking` 变量（MiniCPM5 / Qwen3 一类）-> 把开关渲进 prompt，
            //      **由模板自己吐**完整闭合块。这类模板的闭合块比宿主拼得准（它知道自己的换行），
            //      宿主再叠一段就会与模板后缀自带的 `<think>` 构成两个开标签、
            //      且补出来的 `</think>` 前面只剩 1 个换行 —— 裸标签漏进正文
            //      （2026-09-19 真机：客户端多一个 think 标签 + 一直在推理）。
            //   ② 模板里没有这个变量、库关不掉（LFM2.5 一类）-> 软开关补闭合段兜底。
            val thinkingOn = ThinkingControl.resolve(disableThinkingDefault, ThinkingControl.requestThinkingOverride(j))
            val chatTemplate = LlmEngine.chatTemplate()
            // 带 tools 时优先进工具感知路径；渲染失败（无模型 / 模板不支持）回落普通路径，
            // 与「模型不支持工具调用时退化成普通对话」的承诺一致，不会让请求整体失败。
            // thinkingOn 必须传：它决定模板生成后缀的形状，而同一个值还要传给工具解析，
            // 两边不一致会让 generation_prompt 形状对不上、tool_calls 静默变 0。
            var rendered = if (toolsJson != null)
                    LlmEngine.applyChatTemplateWithTools(
                        msgs, toolsJson, toolChoice, parallelToolCalls, addAss = true, thinkingOn = thinkingOn)
                else null
            if (rendered == null) rendered = LlmEngine.applyChatTemplate(msgs, addAss = true, thinkingOn = thinkingOn)
            // 判定要看渲染结果：LFM2.5 这类模型的"思考开"是模板后缀硬编码的
            // （模板里没有 enable_thinking 变量），只能靠渲染后的后缀识别。
            // 形状由渲染侧（native）给出，宿主只消费 —— 见 RenderedPrompt 的注释，
            // 宿主自己扫字符串已经出过一次真机故障。
            val soft = ThinkingControl.softSwitchApplies(thinkingOn, chatTemplate, rendered.text)
            var prompt = if (soft) ThinkingControl.applyToPrompt(rendered.text, thinkingOn, chatTemplate)
                         else rendered.text
            // openAtStart 只表示「**这一轮模型要续写的第一段字节落在思考段里**」——
            // 也就是"生成后缀里有一个**未闭合**的 `<think>`，模型自己会吐 `</think>` 来收尾"。
            //
            // ⚠ 软开关注入之后**不能**把它翻成 true（这正是 2026-09-21 ISSUE #106 的根因）：
            // 宿主补的那一段是**闭合段**（`<think>\n\n</think>\n\n`），注入完思考段就已经
            // **闭合**了 —— 模型接下来写的每一个字都是**正文**。此时若置 openAtStart=true，
            // ThinkStream 会以 inThink=true 起步、且永远等不到模型再吐 `</think>`，
            // 于是**整段回答被当成 reasoning_content 下发**，客户端把它折叠进思考块
            // （真机现象：LFM2.6B 关思考后回答整段被折叠，正文里只看到 `<think>` 开头）。
            // 正确取值就是渲染侧对**收尾 prompt** 的分类：注入后它是 `closed` -> false。
            //
            // 反过来，没注入（soft=false）时 rendered.openAtStart 才是权威：
            // 后缀以未闭合的 `<think>` 结尾（LFM2.5-2.6B 思考开、MiniCPM5 思考开）
            // -> 模型从思考正文起笔 -> true。
            val openAtStart = rendered.openAtStart && !soft
            // 注入把"后缀未闭合"翻成"后缀已闭合"，与分类结果一致，必须留痕便于取证。
            if (soft && rendered.openAtStart)
                LlmEngine.probeMark("[think] 软开关注入闭合段：openAtStart 由 true 翻为 false（模型本轮直接写正文）")
            // 日志必须写清**这次走的哪条路**：只写"思考=关"会让「模板自己关的」
            // 与「软开关补的」看起来一样，而两者的失败模式完全不同。
            emitLog("thinking=" + (if (thinkingOn) "on" else "off") + " -> " + when {
                thinkingOn -> "模板按思考开渲染"
                soft -> "软开关注入空 think 块（模板关不掉）"
                ThinkingControl.templateSupportsEnableThinking(chatTemplate) -> "模板自带 enable_thinking，由库/模板自己关"
                else -> "不适用（模板没有思考段）"
            })
            val created = System.currentTimeMillis() / 1000
            var n = 0

            // 结构化输出要用的「生成后缀」：**只有渲染侧知道**这一轮 prompt 是以哪段尾巴结尾的
            // （模板按 add_generation_prompt 与 enable_thinking 决定，取不到就只能按"未知"处理）。
            // 渲染失败（native 返回 null，宿主回落 ChatML）时 rendered 为 null —— 那条路径
            // 用的尾巴是宿主自己拼的 `<|im_start|>assistant\n`，取值见 ChatML_GEN_SUFFIX。
            // handleChat 的渲染恒用 addAss = true（见上面两处 applyChatTemplate* 调用）。
            val genPrompt = RequestContext.genPromptArg(
                rendered ?: RenderedPrompt("", false, RequestContext.CHATML_GEN_SUFFIX),
                addAss = true)
            synchronized(LlmEngine.genLock) {
                LlmEngine.newSampler(sp.temp, sp.topP, sp.minP, seed = sp.seed,
                    topK = sp.topK, repPenalty = sp.repeatPenalty, penaltyN = sp.repeatLastN,
                    freqPenalty = sp.freqPenalty, presencePenalty = sp.presencePenalty,
                    stops = stops, responseFormat = respFormat, generationPrompt = genPrompt,
                    chatTemplateOverride = RequestContext.chatTemplateOf(chatTemplate),
                    // 思考开关必须与**渲染侧**同一个值：它决定库算出的生成后缀形状，
                    // 进而决定 GBNF 里思考块是"写死的"还是"可选的"。不透传时库按默认
                    // true 重算 PEG，与真 prompt 分叉 —— 而分叉的后果是 grammar
                    // **整个失效**（不是"少一点约束"），原因见 gbnf_from_json_schema 判据 ④。
                    thinkingOn = thinkingOn)
                if (respFormat !is ResponseFormat.None)
                    emitLog("sampling ${sp.describe()} ${StopSequences.describe(stops)} " +
                            JsonSchemaFormat.describeGenPrompt(genPrompt))
                val err = LlmEngine.startCompletion(prompt, maxTok)
                if (err != null) { writeJson(out, 400, errJson(err), corsOrigin); return }
                // 登记取消归属 —— 必须在 startCompletion 之后（prefill 失败就没有轮次可取消），
                // 且整个生成期都在 finally 里摘除（异常路径同样要摘，否则会留下一个
                // 永远"在跑"的僵尸轮次，之后所有 /v1/abort 都打在一个不存在的对象上）。
                val cancel = LlmEngine.beginCancelable()
                try {
                    val sb = StringBuilder()
                    val sbR = StringBuilder()
                    if (stream) {
                        sseHead(out, corsOrigin)
                        sseEvent(out, chatChunkJson(id, modelName, created, """{"role":"assistant","content":""}""", null))
                    }
                    // <think> 状态机 —— 正文发 content；思考段实时转发 reasoning_content（不混入正文、零延迟）；
                    // 思考期间 SSE 注释心跳防客户端超时。
                    //
                    // 实现在 ThinkStream（与离线单测共用同一份），这里只负责把产物转成 SSE。
                    // openAtStart 只表示「生成后缀里有一个**未闭合**的 `<think>`，模型自己会吐
                    // `</think>` 来收尾」。它由渲染侧给出（见 RenderedPrompt），**不是**
                    // "prompt 里出现过 <think>" —— 后者会把 MiniCPM5 在思考开时吐的
                    // `<think>\n` 误判成"思考段已开"，于是模型吐的 `</think>` 被当裸标签剥掉，
                    // 整段回答留在 reasoning 里、content 只剩极短一截（2026-09-19 真机报障）。
                    val think = ThinkStream(openAtStart)
                    // 已真实下发的 SSE 事件数 —— **首帧门槛**：一条 data: 帧都没发出去之前，
                    // 绝不探测（见下面心跳与 probeOutbound 两处调用点）。
                    var emitted = 0
                    // 心跳节流：`lastBeat` = 上一次**真实下发过 SSE 事件**的时刻（不是"上一次心跳"）。
                    // 语义差别很重要 —— 见下面心跳调用点与 [heartbeat] 的注释：
                    //   · 初始值取"本轮受理时刻"会让第一次心跳在"还没发过任何真实帧"时就开火，
                    //     而 chunked 流在那一瞬只有响应头，探测一旦写坏分帧，客户端立刻断流
                    //     （2026-09-20 「思考开时输出一小段就断」的现场）；
                    //   · 改为「每次真发过事件后刷新」，就等于把首帧门槛内建进来：
                    //     至少有一条 data: 帧出去了，心跳才有意义，也才安全。
                    var lastBeat = System.currentTimeMillis()
                    // 带 tools 时**不实时下发 content**：工具调用语法（<tool_call> / [TOOL_CALLS] 等）
                    // 与正文同处一条输出流，边生成边发会把语法标记当正文吐给客户端。改为整段生成结束后
                    // 先按模板解析出 tool_calls，再决定发 tool_calls 增量块还是纯 content。
                    // reasoning_content 不受影响：思考段不会是工具调用。
                    val bufferContent = toolsJson != null && stream
                    fun emit(text: String, asReason: Boolean) {
                        if (text.isEmpty()) return
                        if (stream && (!bufferContent || asReason)) {
                            sseEvent(out, chatChunkJson(id, modelName, created,
                                if (asReason) """{"reasoning_content":${text.toJsonStr()}}""" else """{"content":${text.toJsonStr()}}""", null))
                            // 真发过一帧 —— 从现在起 "客户端空闲" 才成立，心跳才允许探测。
                            emitted++
                            lastBeat = System.currentTimeMillis()
                        }
                    }
                    while (n < maxTok) {
                        // 取消判定放在**取 token 之前**：已经生成的内容不回滚（SSE 已下发，
                        // 收不回来），但绝不再多算一步。取消后走与自然结束同一条收尾路径。
                        if (cancel.requested) break
                        val piece = LlmEngine.step() ?: break
                        n++
                        // 心跳：只在思考段（此时没有正文增量可发）打，防客户端空闲超时 ——
                        // 正常生成时每个 token 都有自己的 SSE 事件，再插心跳是多余的写。
                        // 顺带做一次断连探测（见 [heartbeat]），客户端走了就立刻取消，
                        // 不再白跑完剩下的 max_tokens。
                        //
                        // 首帧门槛内建在 `lastBeat` 的语义里（见它的注释）：思考段若从第一个
                        // token 就开始（openAtStart=true），在**第一条真实帧下发之前**绝不心跳 ——
                        // 那一刻流里只有响应头，探测写出的任何东西都会与 chunked 分帧竞争。
                        if (stream && think.inThink && emitted > 0 && System.currentTimeMillis() - lastBeat > 800) {
                            heartbeat(out, cancel)
                            lastBeat = System.currentTimeMillis()
                        }
                        if (piece.isNotEmpty()) think.feed(piece) { t, asReason ->
                            if (stream) emit(t, asReason)
                            else if (asReason) sbR.append(t) else sb.append(t)
                        }
                        // 同样带首帧门槛：前 256 步若一条真实帧都没发（思考段 / 带 tools 缓冲），
                        // 流里只有响应头，探测是与分帧竞争而不是探测（见 lastBeat 注释）。
                        if (stream && emitted > 0 && n % CANCEL_PROBE_STEPS == 0 && probeOutbound(out, cancel)) break
                    }
                    think.flush { t, asReason ->
                        if (stream) emit(t, asReason)
                        else if (asReason) sbR.append(t) else sb.append(t)
                    }
                    // 收尾丢弃了半个标签（生成被截断/取消/乱码，恰好停在 `<thi` 之类）：
                    // 丢是对的（它没有合法语义），但要留痕，否则只能看到「回复末尾少几个字节」。
                    if (think.droppedPartialTag > 0) {
                        emitLog("think: 收尾丢弃半个标签 ${think.droppedPartialTag} 字节")
                    }
                    // 非流式 —— 思考段与正文由**同一个状态机**分开落进 sbR / sb（不再事后 indexOf 切一刀）。
                    // 事后切分有两个固有缺陷，正是 2026-09-19 客户端「折叠里再折叠」的成因：
                    //   · 它依赖开标签出现在**输出**里，而 LFM2.5 / MiniCPM5 思考开时开标签在 prompt 里，
                    //     `indexOf("<think>")` 恒 -1 -> 整段思考与裸 `</think>` 全留在 content；
                    //   · 它只按**第一个** `</think>` 切，嵌套的 <think> 留在 reasoning 里，
                    //     客户端对 reasoning_content 再识别一次标签 -> 折叠里再折叠。
                    var reasonField = ""
                    if (!stream) {
                        val r = sbR.toString().trim()
                        if (r.isNotEmpty()) reasonField = "," + "\"reasoning_content\":" + r.toJsonStr()
                        val c = sb.toString().trim()
                        sb.setLength(0); sb.append(c)
                    }
                    // ---- 工具调用解析 ----
                    // 带 tools 的请求，输出里可能含 tool_calls，语法随模板而定，交给 native 归一。
                    // 命中时正文通常为空，finish_reason 必须从 stop 改成 tool_calls，
                    // 否则客户端（OpenAI SDK / LangChain 等）不会去执行工具。
                    var toolCallsJson: String? = null
                    if (toolsJson != null) {
                        LlmEngine.probeMark("[http] 生成结束，进入工具解析：n=$n 输出长度=${sb.length}")
                        // addAss=true：与上面 applyChatTemplateWithTools(..., addAss = true) 同一取值。
                        // 解析侧的 generation_prompt 随它变化，而它决定 PEG 根节点要匹配的前缀。
                        // thinkingOn 与渲染侧同源（同一次请求同一个值），否则 PEG 根节点前缀对不上。
                        val parsed = LlmEngine.parseToolCalls(
                            sb.toString(), toolsJson, addAss = true, thinkingOn = thinkingOn)
                        if (parsed != null) {
                            toolCallsJson = parsed.second
                            // content 只留解析出的正文：工具语法标记不能当正文吐给客户端
                            sb.setLength(0)
                            sb.append(parsed.first)
                            emitLog("tool_calls: ${JSONArray(toolCallsJson).length()} 个")
                        }
                    }
                    // 非流式兜底：native 的 stop 采样器负责真正拦截，这里再切一刀防「多吐了」。
                    // 只对 !stream 生效 —— 流式下若此刻才切，越界内容早已发给了客户端（协议级错误）。
                    if (!stream && stops.isNotEmpty()) {
                        val before = sb.length
                        val t = StopSequences.truncate(sb.toString(), stops)
                        if (t.length != before) { sb.setLength(0); sb.append(t); emitLog("stop 兜底截断：$before -> ${t.length} 字符") }
                    }
                    // 响应体探针：**只落日志**，下发的字节一个字都不动（emitted 之后不再改 sb）。
                    // 位置刻意放在这里：此刻 content 已经过 think 状态机与 tool_calls 解析，
                    // 与客户端真正收到的那份同源 —— 早一步（在生成循环里）打出来的是半成品。
                    // ⚠ 探针必须打在**剥离之前**：它记的是"模型真吐了什么"，不是"我们改成了什么"。
                    emitLog(JsonSchemaFormat.probeBody(JsonSchemaFormat.reqTagOf(id), sb.toString()))
                    // 服务端剥围栏（**只改下发的字节**，不碰 grammar / 生成路径）。
                    // 放在探针之后、下发之前是唯一正确的位置：探针读原件，客户端读剥过的。
                    // 剥不动（无围栏 / 剥后非 JSON）时 sb 逐字节不变 —— 见 stripFenceForDelivery。
                    // ⚠ 只对**非流式**与**缓冲流式**（带 tools）有效：`stream && !bufferContent`
                    //    时正文是边生成边下发的，此刻已经发出去的字节收不回来（协议级不可逆）。
                    //    这不是本处漏了，是流式的固有边界 —— 客户端侧仍可自行剥那一路。
                    val rawOut = sb.toString()
                    val delivered = JsonSchemaFormat.stripFenceForDelivery(respFormat, rawOut)
                    emitLog(JsonSchemaFormat.describeDeliveryStrip(respFormat, rawOut))
                    if (delivered !== rawOut) { sb.setLength(0); sb.append(delivered) }
                    // finish_reason 的优先级：取消 > tool_calls > stop。
                    //
                    // 「取消」必须自成一档，不能退化成 "stop"：调用方区分得了
                    // 「模型说完了」与「我自己不要了」，前者可以存库，后者只能丢弃 ——
                    // 都报 stop 会让客户端把半截输出当完整回答。
                    // OpenAI 官方枚举里没有 "cancelled"，但它的服务端也不会因为客户端
                    // 断连而返回这个字段，这里只能自定一个；未流式时它才是真正可达的
                    // （流式下断连后没人收得到这个字段，只有 /v1/abort 会）。
                    val cancelled = cancel.cancelled
                    if (cancelled) {
                        // E-4 的可观测面（**不回滚**）：取消那一拍，引擎侧可能有一个 token 已经
                        // 走进 KV 与账本，而它的 SSE 帧还没发（或被 ThinkStream 暂存、被 tools 缓冲）。
                        // 客户端若按"我收到的文本"拼回历史，两边就会短一截 —— 重发同一 prompt 时
                        // 引擎不会重复计算（对），但客户端拼的对话与引擎手里的对不上。
                        // 这里只把差额记下来，让人**查得到**：回滚要引入新分支与新失效模式，
                        // 收益只是"少算一个 token"。handleChat 与 handleCompletion 同一处对账。
                        val ledger = LlmEngine.roundLedgerTokens
                        emitLog("生成已被取消（客户端断连或 /v1/abort）：已生成 $n tok，提前收尾" +
                            "；引擎账本 $ledger tok" +
                            (if (ledger > n) "（有 ${ledger - n} tok 已进 KV 未下发，" +
                                "客户端按收到的文本拼回的历史会比引擎手里的短）" else ""))
                    }
                    if (stream) {
                        val fin = if (cancelled) "cancelled" else if (toolCallsJson != null) "tool_calls" else "stop"
                        // 缓冲模式下补发正文（带 tools 但模型没调工具）。
                        // 下发字节同样是上面剥过围栏的那份 sb —— 与 !stream 路径同一处判据，
                        // 不在这里再判一次（两处判据必然漂移）。
                        if (bufferContent && toolCallsJson == null && sb.isNotEmpty())
                            sseEvent(out, chatChunkJson(id, modelName, created, """{"content":${sb.toString().toJsonStr()}}""", null))
                        // 流式下按 OpenAI 约定发 tool_calls 增量块（index/id/type/function）。
                        // 已整段解析完成，故一次性下发而不是逐 token 拼装——客户端按 index 聚合，结果一致。
                        // id 兜底与 native 侧同一条规则：parse 结果缺 id 时用 call_<下标>，
                        // 不能在这里另写一份默认值，否则同一份数据在两条路径上会算出不同的 id。
                        if (toolCallsJson != null) {
                            for (delta in ToolCalls.streamDeltas(JSONArray(toolCallsJson)))
                                sseEvent(out, chatChunkJson(id, modelName, created, delta, null))
                        }
                        sseEvent(out, chatChunkJson(id, modelName, created, "{}", fin))
                        sseEvent(out, "[DONE]")
                        sseEnd(out)
                    } else {
                        val usage = usageJson(LlmEngine.contextUsed(), n)
                        val fin = if (cancelled) "cancelled" else if (toolCallsJson != null) "tool_calls" else "stop"
                        // 有 tool_calls 时 content 按 OpenAI 规范可为 null
                        val contentField = if (toolCallsJson != null && sb.isEmpty()) "null" else sb.toString().toJsonStr()
                        // 必须走 ToolCalls 重新包装：native 给的是解析器原生形状（只有 id/name/arguments），
                        // OpenAI 线上要的是 {"id":..,"type":"function","function":{..}}，
                        // 直接透传会让 SDK 认不出 tool_calls 而静默丢掉。
                        val toolField = if (toolCallsJson != null) ToolCalls.messageToolCallsField(JSONArray(toolCallsJson)) else ""
                        writeJson(out, 200,
                            """{"id":"$id","object":"chat.completion","created":$created,"model":"$modelName",""" +
                            """"choices":[{"index":0,"message":{"role":"assistant","content":$contentField$reasonField$toolField},"finish_reason":"$fin"}],"usage":$usage}""", corsOrigin)
                    }
                } finally {
                    // 摘除取消归属。**必须在 emitLog 之前**：之后任何 /v1/abort 都应看到
                    // 「没有轮次在跑」，而不是打在一个已收尾的对象上。
                    LlmEngine.endCancelable(cancel)
                }
            }
            emitLog("chat ok: $n tok, model=$modelName")
            LlmEngine.probeMark("[http] handleChat 正常结束 n=$n")
        } catch (t: Throwable) {
            LlmEngine.probeMark("[http] handleChat 抛出：${t.javaClass.name}: ${t.message}")
            try { writeJson(out, 500, errJson(t.message ?: "internal error"), corsOrigin) } catch (_: Exception) {}
        } finally {
            busy.set(false)
        }
    }

    private fun handleCompletion(req: Req, out: OutputStream, corsOrigin: String? = null) {
        if (!LlmEngine.hasModel) { writeJson(out, 503, errJson("模型未加载"), corsOrigin); return }
        // 同 /v1/chat/completions：App 内占用时立刻 503，不让请求挂在 genLock 上。
        // 两条判据分写、只回滚自己刚抢到的那一份 —— 理由见 handleChat 同处（A-3）。
        if (!busy.compareAndSet(false, true)) {
            writeJson(out, 503, errJson("server busy（已有生成任务进行中）"), corsOrigin); return
        }
        if (LlmEngine.isGenerating) {
            busy.set(false)
            writeJson(out, 503, errJson("server busy（已有生成任务进行中）"), corsOrigin); return
        }
        try {
            val j = JSONObject(req.body)
            val stream = j.optBoolean("stream", false)
            // 与 /v1/chat/completions 同一套解析与校验，默认值不会两边漂移
            // fromRequest 已逐字段校验并给出原因；返回 null 即取值非法，直接 400。
            val (sp, spErr) = SamplingParams.fromRequest(j)
            if (sp == null) { writeJson(out, 400, errJson(spErr ?: "采样参数非法"), corsOrigin); return }
            val (stopsRaw, stopErr) = StopSequences.fromRequest(j)
            if (stopsRaw == null) { writeJson(out, 400, errJson(stopErr ?: "stop 参数非法"), corsOrigin); return }
            // 同上：条数超额只截断 + 落日志，不失败请求。
            val stops = StopSequences.capped(stopsRaw)
            if (stops.size != stopsRaw.size) emitLog(StopSequences.truncatedNotice(stopsRaw.size))
            // response_format（结构化输出）与采样参数同一套原则：**协议级错误报 400**
            // （类型/取值拼错、schema 不是对象、包装对象缺字段），而"库转换不了这个 schema"
            // 属于能力边界 —— 那时不失败请求，只在 native 侧降级并落日志（判据与理由见
            // JsonSchemaFormat 文件头）。字段不存在时必须不产生任何 native 调用。
            val (respFormatRaw, rfErr) = JsonSchemaFormat.fromRequest(j)
            if (respFormatRaw == null) { writeJson(out, 400, errJson(rfErr ?: "response_format 非法"), corsOrigin); return }
            val respFormat = respFormatRaw
            // 与 chat 端点同一个关联码口径（见 handleChat 同处的说明）：请求侧与 `[body]` 成对。
            val id = "cmpl-local-${System.currentTimeMillis()}"
            val reqTag = JsonSchemaFormat.reqTagOf(id)
            if (respFormat !is ResponseFormat.None)
                emitLog("[id=$reqTag] " + JsonSchemaFormat.describe(respFormat))
            val maxTok = sp.maxTokens
            val modelName = j.optString("model", "local").ifEmpty { "local" }
            val prompt = when (val p = j.opt("prompt")) {
                is String -> p
                is JSONArray -> (0 until p.length()).mapNotNull { p.opt(it) as? String }.joinToString("\n")
                else -> ""
            }
            if (prompt.isEmpty()) { writeJson(out, 400, errJson("prompt is empty"), corsOrigin); return }

            val created = System.currentTimeMillis() / 1000
            var n = 0

            synchronized(LlmEngine.genLock) {
                // 裸补全**没有生成后缀**（prompt 由调用方自己拼、模型直接续写），
                // 所以这里传空串 = "确认没有" —— 不是 "未知"。
                // grammar 的起点因此是"输入之后立刻"，与模型实际续写的位置一致。
                val genPrompt = JsonSchemaFormat.genPromptArg(
                    respFormat, ResponseFormat.GenerationPrompt.EMPTY)
                LlmEngine.newSampler(sp.temp, sp.topP, sp.minP, seed = sp.seed,
                    topK = sp.topK, repPenalty = sp.repeatPenalty, penaltyN = sp.repeatLastN,
                    freqPenalty = sp.freqPenalty, presencePenalty = sp.presencePenalty,
                    stops = stops, responseFormat = respFormat, generationPrompt = genPrompt,
                    // 模板：**必须与渲染侧同源** —— 这里与 handleChat 一样取运行时模板，
                    // 不再传空串"让库按模型自选"。
                    //
                    // 裸补全的 prompt 由调用方自己拼、模型直接续写，所以它**没有渲染侧**。
                    // 但 GBNF 的推导（`gbnf_from_json_schema`）还要再问库一次
                    // `common_chat_templates_apply`，而这一跳必须回答"用哪个模板"：
                    // 库内自选的那份（`minja::resolve_template`，还会被 `json_schema` 分支改写）
                    // 与运行时模板**可能不是同一份**。模板不同 → grammar 的落点不同
                    // （`cp.grammar` 的根首字面量与 `cp.generation_prompt` 都按那个模板求），
                    // 于是约束落错位置或压根产不出 GBNF（"降级为无约束采样"），两者都 HTTP 200。
                    //
                    // 为什么裸补全也该用**模型自己那份**模板：GBNF 的推导需要一个模板，
                    // 而"这一轮真正会被用到的"就是模型自带的那份（`llama_model_chat_template`）；
                    // 库内自选只是"另一份可能不同的猜"。取运行时模板 = 两个端点对同一个问题
                    // 给同一个答案，这条分叉也就没有了。
                    chatTemplateOverride = RequestContext.chatTemplateOf(LlmEngine.chatTemplate()),
                    // 裸补全没有渲染侧，取全局默认（与 handleChat 同一条 `resolve`，不另立一份）。
                    // 仍要**显式**传：形参不给时用的是同一个值，但显式才让"这一支按什么算的"可查。
                    thinkingOn = ThinkingControl.templateEnableThinking(
                        ThinkingControl.resolve(disableThinkingDefault, null)))
                if (respFormat !is ResponseFormat.None)
                    emitLog("sampling ${sp.describe()} ${StopSequences.describe(stops)} " +
                            JsonSchemaFormat.describeGenPrompt(genPrompt))
                val err = LlmEngine.startCompletion(prompt, maxTok)
                if (err != null) { writeJson(out, 400, errJson(err), corsOrigin); return }
                // 归属登记与 handleChat 同一套（见那边的注释）。
                val cancel = LlmEngine.beginCancelable()
                try {
                    val sb = StringBuilder()
                    // 与 handleChat 同一套语义：lastBeat 记「上次真实下发时刻」，emitted 作首帧门槛。
                    var emitted = 0
                    var lastBeat = System.currentTimeMillis()
                    if (stream) {
                        sseHead(out, corsOrigin)
                    }
                    while (n < maxTok) {
                        if (cancel.requested) break
                        val piece = LlmEngine.step() ?: break
                        n++
                        // /v1/completions 没有 think 状态机，心跳纯粹为了断连探测：
                        // 这个端点常见于"测速"长跑（max_tokens 拉满），客户端中途离开
                        // 却白跑完是最浪费的一类场景。同样带首帧门槛（见 handleChat 的注释）。
                        if (stream && emitted > 0 && System.currentTimeMillis() - lastBeat > 800) {
                            heartbeat(out, cancel)
                            lastBeat = System.currentTimeMillis()
                        }
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            if (stream) {
                                sseEvent(out, compChunkJson(id, modelName, created, piece.toJsonStr(), null))
                                emitted++
                                lastBeat = System.currentTimeMillis()
                            }
                        }
                        // 同样带首帧门槛：前 256 步若一条真实帧都没发（思考段 / 带 tools 缓冲），
                        // 流里只有响应头，探测是与分帧竞争而不是探测（见 lastBeat 注释）。
                        if (stream && emitted > 0 && n % CANCEL_PROBE_STEPS == 0 && probeOutbound(out, cancel)) break
                    }
                    val cancelled = cancel.cancelled
                    if (cancelled) {
                        // E-4 的可观测面（**不回滚**）：取消那一拍，引擎侧可能有一个 token 已经
                        // 走进 KV 与账本，而它的 SSE 帧还没发（或被 ThinkStream 暂存、被 tools 缓冲）。
                        // 客户端若按"我收到的文本"拼回历史，两边就会短一截 —— 重发同一 prompt 时
                        // 引擎不会重复计算（对），但客户端拼的对话与引擎手里的对不上。
                        // 这里只把差额记下来，让人**查得到**：回滚要引入新分支与新失效模式，
                        // 收益只是"少算一个 token"。与 handleChat 同一处对账（判据同源）。
                        val ledger = LlmEngine.roundLedgerTokens
                        emitLog("生成已被取消（客户端断连或 /v1/abort）：已生成 $n tok，提前收尾" +
                            "；引擎账本 $ledger tok" +
                            (if (ledger > n) "（有 ${ledger - n} tok 已进 KV 未下发，" +
                                "客户端按收到的文本拼回的历史会比引擎手里的短）" else ""))
                    }
                    if (stream) {
                        sseEvent(out, compChunkJson(id, modelName, created, "\"\"", if (cancelled) "cancelled" else "stop"))
                        sseEvent(out, "[DONE]")
                        sseEnd(out)
                    } else {
                        if (stops.isNotEmpty()) {
                            val before = sb.length
                            val t = StopSequences.truncate(sb.toString(), stops)
                            if (t.length != before) { sb.setLength(0); sb.append(t); emitLog("stop 兜底截断：$before -> ${t.length} 字符") }
                        }
                        // 与 handleChat 同一行探针（同一个纯函数、同一份口径）：
                        // 裸补全 + json_schema 也会出"带围栏"的老症状，缺了这一行那边就查不动。
                        emitLog(JsonSchemaFormat.probeBody(JsonSchemaFormat.reqTagOf(id), sb.toString()))
                        // 与 handleChat 同一处剥离判据（探针读原件、客户端读剥过的）。
                        val rawOut = sb.toString()
                        val delivered = JsonSchemaFormat.stripFenceForDelivery(respFormat, rawOut)
                        emitLog(JsonSchemaFormat.describeDeliveryStrip(respFormat, rawOut))
                        if (delivered !== rawOut) { sb.setLength(0); sb.append(delivered) }
                        val usage = usageJson(LlmEngine.contextUsed(), n)
                        val fin = if (cancelled) "cancelled" else "stop"
                        writeJson(out, 200,
                            """{"id":"$id","object":"text_completion","created":$created,"model":"$modelName",""" +
                            """"choices":[{"index":0,"text":${sb.toString().toJsonStr()},"finish_reason":"$fin"}],"usage":$usage}""", corsOrigin)
                    }
                } finally {
                    LlmEngine.endCancelable(cancel)
                }
            }
            emitLog("completion ok: $n tok")
        } catch (t: Throwable) {
            try { writeJson(out, 500, errJson(t.message ?: "internal error"), corsOrigin) } catch (_: Exception) {}
        } finally {
            busy.set(false)
        }
    }

    /**
     * 生成期的一次「客户端还在不在」探测；返回 true 表示已判定离开（调用方应停止生成）。
     *
     * 只在**已经取消**时返回 true：探测结果只是「给当前轮次打一个取消标记」，
     * 真正的停止判据始终是循环头部的 `cancel.requested`，两条路径共用一个状态，
     * 不会出现「探测说走了、循环还在跑」这种分叉。
     *
     * 探测载体是**一帧合法 SSE 注释**（走 [sseComment]，即 chunked 分帧），
     * 不是裸字节 —— 后者会污染 `Transfer-Encoding: chunked`，客户端读长度行会拿到
     * `0x0` 并断流（见 [RequestCancel] 文件头与 [sseComment] 的注释）。
     *
     * 判定只有两态（[RequestCancel.probe]）：写失败 = GONE，写成功 = ALIVE。
     * 没有"读超时"那一档了：探测不读，所以不存在「连接还在但没话说」被误判成离开的
     * 可能 —— 这是删掉读探测白拿到的收益（旧实现的 UNKNOWN 档就是误杀风险源）。
     */
    private fun probeOutbound(out: OutputStream, cancel: RequestCancel.Token): Boolean {
        if (cancel.requested) return true
        return when (RequestCancel.probe { sseComment(out) }) {
            RequestCancel.PeerState.GONE -> {
                if (cancel.request()) {
                    emitLog("客户端已断开（写探测判定离开）：取消本轮生成，不再空转")
                    // 归属一路送到 native：Kotlin 侧的 requested 只挡得住"循环自己查标志"，
                    // 挡不住 prefill（那是一次阻塞的 native 调用）。
                    LlmEngine.abortRound(cancel)
                }
                true
            }
            RequestCancel.PeerState.ALIVE -> false
        }
    }

    /**
     * 流式生成期的心跳：写 SSE 注释防客户端空闲超时，**探测就搭在这一帧上**。
     *
     * 只做一次写、不读（[RequestCancel] 文件头解释了为什么**读**探测必须删掉）：
     *   · 写成功 → 连接仍在，心跳（那帧注释）已经发出去了，探测零成本；
     *   · 写抛异常（Broken pipe / Connection reset）→ 对端已走，取消本轮。
     * 调用方要保证「至少发过一条真实 SSE 帧」才允许调用（见 `lastBeat` 的注释）：
     * 在只有响应头、没有任何 `data:` 帧的窗口里探测，是与分帧竞争而不是探测。
     */
    private fun heartbeat(out: OutputStream, cancel: RequestCancel.Token) {
        if (cancel.requested) return
        when (RequestCancel.probe { sseComment(out) }) {
            RequestCancel.PeerState.GONE -> {
                if (cancel.request()) {
                    emitLog("客户端已断开（心跳写探测失败）：取消本轮生成")
                    LlmEngine.abortRound(cancel)
                }
            }
            RequestCancel.PeerState.ALIVE -> {} // 心跳帧已随探测写出，无需再写一次
        }
    }

    /**
     * `POST /v1/abort`：取消**当前正在生成的那一轮**，无 body、无参数。
     *
     * 为什么不做成 `{request_id}` / `DELETE /v1/chat/completions/{id}` 那种按 id 取消：
     * 本服务是单实例引擎、单并发（生成循环持 `LlmEngine.genLock`），任何时刻最多
     * 只有一轮在跑，**没有可选项**。要求调用方先记住 id，只会多一层「id 对不上
     * 就静默不生效」的失败模式 —— 而它恰恰是取消类接口最容易骗过测试的那种坏法。
     *
     * 返回 200 + `aborted` 字段说明实际发生了什么（没有 id 也要让结果**可判定**）：
     *   · `aborted=true`  真的停到了某一轮；
     *   · `aborted=false` 当时没有任何轮次在跑（把「取消了不存在的请求」与
     *     「成功取消」区分开；两者都返回 200，取消本身不该因竞态而失败）。
     *
     * 注意 `busy` 与它无关：App 内的对话也占 engine 的生成轮次，`/v1/abort` 一样能停
     * （这正是「用户点了 App 里的停止，但停的是 HTTP 请求」那类错位要避免的反面 ——
     * 取消永远指向真实在跑的那一轮，而不是请求的来源）。
     */
    private fun handleAbort(): String {
        // 走 LlmEngine.requestAbort（唯一入口）：它同时做两件事 ——
        // 标记 Kotlin 侧的 token，并把**带归属的**取消送到 native。
        // 只标记 token 的话，prefill 阶段（一次阻塞的 native 调用）根本停不下来。
        val t = LlmEngine.requestAbort()
        val running = t != null
        // 不走 `t!!`：`running` 为 true 与 `t != null` 是同一判断，
        // 但写成 `!!` 就多一处"靠非局部推理成立"的断言（`t` 一旦改成别的取值来源就会炸）。
        // 编号只进日志，用 `?: 0` 兜住即可 —— 它是**读数**，不参与判定。
        emitLog(if (t != null) "abort: 取消当前生成轮次（来源=http，epoch=${t.nativeEpoch}）"
                else "abort: 当前没有生成轮次（no-op）")
        return """{"aborted":$running,"generating":${RequestCancel.active}}"""
    }

    // ---- JSON / SSE 工具 ----

    private fun chatChunkJson(id: String, model: String, created: Long, deltaJson: String, finish: String?): String {
        val fin = if (finish == null) "null" else "\"$finish\""
        return """{"id":"$id","object":"chat.completion.chunk","created":$created,"model":"$model",""" +
            """"choices":[{"index":0,"delta":$deltaJson,"finish_reason":$fin}]}"""
    }

    private fun compChunkJson(id: String, model: String, created: Long, textJson: String, finish: String?): String {
        val fin = if (finish == null) "null" else "\"$finish\""
        return """{"id":"$id","object":"text_completion","created":$created,"model":"$model",""" +
            """"choices":[{"index":0,"text":$textJson,"finish_reason":$fin}]}"""
    }

    private fun usageJson(promptTok: Int, completionTok: Int): String =
        """{"prompt_tokens":$promptTok,"completion_tokens":$completionTok,"total_tokens":${promptTok + completionTok}}"""

    private fun errJson(msg: String): String =
        """{"error":{"message":${msg.toJsonStr()},"type":"local_inference_error"}}"""

    private fun String.toJsonStr(): String {
        val sb = StringBuilder(length + 16).append('"')
        for (c in this) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    /**
     * 非预先分帧的响应。**CORS 头必须在这里统一注入**，不允许调用点自己拼 —— 
     * 21 处 `writeJson` 只要漏一处，症状就是"预检过了、某一条端点被拦"，
     * 而这种"只有一条路径不通"的故障在浏览器里看起来与"服务随机抽风"完全一样。
     */
    private fun writeRaw(
        out: OutputStream,
        code: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        extraHeaders: String = "",
        corsOrigin: String? = null,
    ) {
        val b = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${b.size}\r\n" +
            CorsPolicy.responseHeaders(corsOrigin) +
            extraHeaders +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        if (b.isNotEmpty()) out.write(b)
        out.flush()
    }

    private fun writeJson(out: OutputStream, code: Int, body: String, corsOrigin: String? = null) =
        writeRaw(out, code, body, "application/json; charset=utf-8", corsOrigin = corsOrigin)

    /**
     * 自带测试页：`text/html; charset=utf-8`。
     *
     * 显式带 charset：页子里有中文，缺了它浏览器会按 latin-1 猜，
     * 现场表现是"页面标题乱码"这种看着像编码 bug、实际是响应头缺项的问题
     *（`<meta charset>` 虽在，但它要等 HTML 解析到那一行才生效，中间那段已经错了）。
     */
    private fun writeHtml(out: OutputStream, code: Int, body: String, corsOrigin: String? = null) =
        writeRaw(out, code, body, "text/html; charset=utf-8", corsOrigin = corsOrigin)

    private fun reason(c: Int) = when (c) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
        503 -> "Service Unavailable"; else -> "Error"
    }

    /**
     * SSE 响应头。CORS 必须在这里带上，理由同 [writeRaw]：
     * 流式端点是浏览器前端最常打的那个，漏了它等于 CORS 只做了一半。
     */
    private fun sseHead(out: OutputStream, corsOrigin: String? = null) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            CorsPolicy.responseHeaders(corsOrigin) +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * SSE 注释帧（思考生成期间防客户端空闲超时）。**必须走 chunked 分帧**：
     * 裸写一个字节会破坏 `Transfer-Encoding: chunked`，客户端解析下一个 chunk 的长度行时
     * 读到 `0x0`，报 `Expected leading [0-9a-fA-F] character but was 0x0` 后断流
     * （2026-09-20 真机事故，见 [RequestCancel] 文件头）。
     */
    private fun sseComment(out: OutputStream) {
        try {
            val c = ": ping\n\n".toByteArray(Charsets.UTF_8)
            out.write(c.size.toString(16).toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write(c)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {}
    }

    private fun sseEvent(out: OutputStream, data: String) {
        val payload = "data: $data\n\n".toByteArray(Charsets.UTF_8)
        out.write(payload.size.toString(16).toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.write(payload)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sseEnd(out: OutputStream) {
        out.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
