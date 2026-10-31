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
 * POST /v1/completions         -> raw prompt 补全（不走 chat 模板，适合测速）；认 `echo`（见 [CompletionEcho]）
 * POST /v1/abort               -> 取消当前生成（客户端主动放弃，或断连后的兜底）
 * POST /v1/relay/chat/completions -> 中间层（同进程第二个监听口，ISSUE #452 第 3 步）：
 *                                按服务端上一轮报的 `local_rounds` 在客户端原件上**先删掉
 *                                那几轮**，再走与 /v1/chat/completions 完全相同的那条路。
 *                                客户端把 base_url 从 `/v1` 换成 `/v1/relay` 就接上了。
 *
 * 并发模型：每连接一线程（accept 不串行阻塞）；生成期间（池里 slot 占满）新请求返回 503；
 * 生成循环持**自己那一 slot 的** `LlmEngine.slotLock(slot)`：同 slot 与 UI 测试页
 * 互斥，不同 slot 之间并行（Issue #330 起不再是一把全局锁）。
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

    /**
     * 默认端口。
     *
     * ⚠ 这是**唯一出处**：`ServerSettings.serverPort` 的回落值、"还原默认设置"的文案
     * 与输入框回填、启动前的区间校验，一律引用它。曾经 `8080` 在四处各写一遍
     * （HttpApi / ServerSettings / Activity 文案 / Activity 回填），改一处漏一处时
     * 症状是"重置按钮说要 9090，实际回填 8080"这种**界面自己在说谎**。
     */
    const val DEFAULT_PORT = 8080

    /** 端口下界（含）。1024 以下需要 root，普通应用绑不上。 */
    const val PORT_MIN = 1024

    /** 端口上界（含）。 */
    const val PORT_MAX = 65535

    /**
     * relay 那一档的响应缓冲上限（字节）。超了就**放弃记账、照旧转发**。
     *
     * 为什么要个上限：一次长回答 + SSE 分帧在真机上是几百 KB，而无上限的缓冲
     * 在"客户端发个超长 max_tokens 就不管了"的场景里会直接把堆吃掉。
     * 超限之后行为退化成"这一轮没记住坐标"（下一轮原样转发，与不带中间层同形），
     * 而不是 500。
     */
    private const val BUF_RELAY_BYTES = 4 * 1024 * 1024

    /**
     * 「先预读、拿到坐标就放开直写」那一档：预读最多多少字节（ISSUE #452）。
     *
     * 预读要等的是两样东西之一：**响应头**（非流式只有它），或**第一帧 SSE**。
     * 后者在真机上是几十到几百字节；给 8 KB 是因为头里可能带着 `local_ctx`、
     * 而 SSE 的第一帧带着 `local_ctx.prompt_prefix` 与 `local_rounds` ——
     * 两个都远超真实大小，而它只在这一轮**开头**占内存。
     */
    private const val RELAY_PREFETCH_BYTES = 8 * 1024

    /**
     * 中间层这一轮**走到哪一步**的台阶编号（ISSUE #450 第十次现场）。
     *
     * 与 [RelayPrefetch.reached] 配套：服务端以前只在"成功路径"上给 relay 打日志，
     * 于是"请求没进来"与"进来了但一帧都没写"在日志上**同形 —— 一行都没有**。
     * 台阶只用于收尾那句话，不参与任何判据（它不改变这一轮发什么、写什么）。
     *
     * 用枚举而不是四个 `Int` 常量：台阶之间唯一的比较是"只前不退"，而
     * `ordinal` 就是声明顺序 —— 没有第二份编号要维护。原来那组常量里
     * 把两档的数字对调是**能编译、能运行**的，症状只是收尾那句少报一档；
     * 与本仓库 `rounds`/`lcp` 混格是同一类静默错。
     */
    private enum class RelayStage {
        ENTERED, FIRST_WRITE, BUF, RELEASED
    }

    /** 预读里**最多扫几帧**去找带坐标的那一帧（头部没有就往后找，超过就放弃记账）。 */
    private const val RELAY_SCAN_FRAMES = 4

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
     *
     * ⚠ 这条取舍**必须由调用点兑现**（ISSUE #450 第七次现场）：此前
     *   [probeOutbound] 那一跳不看 `stream`，非流式请求在生成期每 256 步照样往
     *   响应体里写一帧注释 —— 那一档的响应体是一整段 JSON + `Content-Length`，
     *   多出来的字节让客户端读到的长度对不上，它只能当成断流。现在两处（这里与
     *   [PrefillProbe] 的 `comments = false`）用的是同一条判据：**非流式一个字节都不写**。
     */
    private const val CANCEL_PROBE_STEPS = 256

    /**
     * 生成期断连探测的**时间节拍**（毫秒）。
     *
     * 与 [CANCEL_PROBE_STEPS] 的关系是"两条判据都要"，不是二选一：
     *   · 256 步在慢机器上要跑十几秒 —— 那时客户端早就走了，服务端还在算；
     *   · 只有时间节拍时，快机器（HTP 上 100+ tok/s）会每秒写好几次探测帧。
     * 两者取"到点且到步"（`n % 256 == 0 || 距上次探测 > 2s`），既不会在慢机器上
     * 拖十几秒，也不会在快机器上把连接写满。
     *
     * 为什么必须有时间这一半（ISSUE #450 第十四次现场）：直连那一档真机上报的是
     * 「多轮超长对话，未点停止，服务器显示 abort」—— 服务端看到的只有"探测写失败"，
     * 而它上一次写探测可能在十几秒之前，那段时间里连接已经被中间环节（代理 / 浏览器
     * 空闲回收 / 客户端自己的读超时）掐掉了。探得越晚，"这是客户端主动断的"这个
     * 结论就越像是"服务端自己决定的"。
     */
    private const val CANCEL_PROBE_MS = 2000L

    /**
     * prefill 窗口里的探测间隔（毫秒，ISSUE #450 第三次现场）。
     *
     * 为什么要单独一个数：整段 prompt 的 prefill 是**一次阻塞的 JNI 调用**
     * （`startCompletion`），那段时间里上面那个"每 256 步"的探测**一步都跑不到** ——
     * 于是服务端看不见客户端已经走了，照旧把 prompt 算完、再算满 `max_tokens`。
     * 能覆盖那一段的唯一探针是**另一条线程**（见 [PrefillProbe]）。
     *
     * 为什么是 800ms：与思考段心跳同一个节拍，也远小于客户端常见的读超时；
     * 而 prefill 本身是秒级的量，这个密度既不会把连接写满，也不会拖到用户按完停止
     * 才第一次知道对面还在不在。
     */
    private const val PREFILL_PROBE_MS = 800L

    /**
     * 中间层**没放开直写**时，替上游垫一帧 SSE 注释的节拍（毫秒）。
     *
     * 为什么必须是中间层自己垫（ISSUE #450 第九次现场）：
     * 预读要么看到坐标、要么攒满 [RELAY_PREFETCH_BYTES] —— 两条路之外的整整一段时间里，
     * 上游写进来的每一个字节都**停在 [RelayPrefetch.buf] 里，一个都没到客户端**。
     * 而那段正是**整个 prefill**：直连（`/v1/chat/completions`）的客户端会在 prefill
     * 期间每 [PREFILL_PROBE_MS] 收到一帧 `: ping`，客户端因此知道"连接是活的"；
     * relay 这一档**一帧都没有**，而 prefill 在真机上是 25~60 秒。
     *
     * 于是客户端按自己的空闲超时先断（日志里是 `handleChat 抛出：Connection reset`），
     * 服务端那一轮的探针随后写失败、判 GONE、取消 prefill —— 用户读到的是
     * 「通过 relay 收不到回复，重新生成才能看到」，而**他从没按过停止**
     * （`[kv] prefill 被中断（abort）` 就是这么来的）。
     *
     * 一帧注释是 SSE 协议的一部分，客户端按规范忽略；节拍与直连那一档同源
     * （[PREFILL_PROBE_MS]），于是"统一走 relay 当中间层"不再削掉一条已有能力。
     */
    private const val RELAY_KEEPALIVE_MS = PREFILL_PROBE_MS

    /** 一帧 `: ping\n\n` 写在连接上的字节数（4 字节长度行 + CRLF + 载荷 + CRLF = 11）。 */
    private const val PROBE_PING_BYTES = 11

    /**
     * 引擎**不动**之后，才敢往客户端连接上写一个字节的静默时长（毫秒）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么"往回写一帧"会变成"取消这一轮"（ISSUE #450 第十五次现场）
     * ══════════════════════════════════════════════════════════════════════════
     * prefill 探针把 `write` 抛异常当成"客户端已经走了"，而**一次写抛异常并不说明**
     * 对端走了：它只要没被 `accept`（对端读缓冲满 / 客户端边读边算 / 代理不读了）
     * 就会抛 `Write timed out` / `Would block`，这一轮随即被取消 —— 用户读到的是
     * 「我没点停止，它 abort 了，肯定没内容」。
     *
     * 判据换成**时间**而不是"这一次写的结果"：只有这一轮**已经没人在算**
     * （引擎计数 `LlmEngine.busy` 归零）且**静默满这么久**，才把这一帧交出去。
     * 于是"写不出去"这件事在 prefill 窗口里**不可达** —— 那个窗口引擎一直在忙。
     *
     * 2 秒：真机上"算完到客户端读走"这一段正常是毫秒级；而它远小于任何客户端的
     * 空闲超时（几十秒），所以"客户端还活着的话早就读走了"这条判断成立。
     */
    private const val PROBE_QUIET_MS = 2_000L

    /** 同一轮里连续多少次"写不出去"才认账（极慢的被写端；也防探针自己空转成忙等）。 */
    private const val PROBE_BLOCK_STRIKES = 3

    /**
     * 把一段字节里**响应头**与**正文**切开（`-1` = 还没读到 `\r\n\r\n`）。
     *
     * 为什么要切：relay 那一档放开直写之后，`sseEvent` / `writeJson` 的每一次写都是
     * 分开的几次 `write`（长度行 / CRLF / 载荷 / CRLF），而心跳与探针也往同一条连接
     * 上写 —— 两次写交错，客户端读到的是"长度行里插了一帧注释"（它只会报协议错、断流）。
     * 所以"一次写"必须是**一个整体**：拿着这把锁的人一次 `write` 写完。
     *
     * 判据只看**第一次**写：只有那一次里含头（响应头必然是整段响应的第一段），
     * 之后每一段都是纯正文 —— 这在 `handleChat` 里成立（`sseHead` / `writeJson`
     * 都是"头 + 正文"一次给），也正是这一层唯一的字节来源。
     */
    private fun headEndOf(b: ByteArray, off: Int, len: Int): Int {
        var i = off
        val stop = off + len - 3
        while (i < stop) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() &&
                b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i + 4 - off
            i++
        }
        return -1
    }

    /**
     * 一次写、一次 flush。**只在 [RelayPrefetch.relayWriteLock] 里调**（见那边的注释：
     * 这条连接上不止一个写方，而 `writeFrame` 本身是四次 `write`）。
     */
    private fun writeOnce(dst: OutputStream, bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (len <= 0) return
        dst.write(bytes, off, len)
        dst.flush()
    }

    /** 端口可自定义；InferenceService 启动前从 [ServerSettings] 注入。 */
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
    private val running = AtomicBoolean(false)

    /**
     * 「用户想要服务在跑」——与 [running]（实际状态）**必须分开**。
     *
     * 看门狗的判据只取它：`running` 在"用户主动停"与"listener 意外死亡"两种
     * 处境下同形（都是 false），而只有 `desired` 分得开"不该重启"与"该重启"。
     * 它漏掉时是**编译期**就红 —— 但带上 `start()` / `stop()` / 看门狗三条链
     * 一起编不过，比静默更早暴露。
     */
    private val desired = AtomicBoolean(false)
    // ⚠ `busy`（HTTP 侧占位 CAS）在阶段 4 **已被 SlotScheduler 取代**，字段删掉。
    // 它原来表达的是"HTTP 侧已受理、还没写完响应"，而现在这个语义由
    // 「池里有几个 slot 被占、各是谁」**唯一地**表达 —— 两个来源拼判据正是
    // §9.2.4 要消掉的那个漂移（含义动了、判据却还是拼出来的）。
    // 对外读数（`/health: busy`）保留、只增不改：下面按"HTTP 侧是否有 slot 被占"重算。

    /**
     * **上一轮**远端请求实际注入了几条知识库内容（0 = 没注入）。
     *
     * 它是 `/health` 的 `rag_remote_injected` 的值，也是"远端知识库到底有没有
     * 在工作"的唯一可编程读数。为什么不能只看 `/health` 的"开关开着"：
     * 开关开着完全不代表检索命中了 —— 阈值、索引是否需重建、嵌入模型是否驻留，
     * 任一环节没就绪，注入都是 0 条，而开关字段照旧报 `true`。
     * 那种"开了却什么都没发生"在调用方看来与"功能坏了"是一回事，
     * 只有这个"上一轮实际条数"能把两者分开。
     *
     * 语义与 `kv_reuse_tokens` 一致（都是"上一轮"）：它在**每轮请求开始时清零**，
     * 注入成功则写上真实条数。不清零的实现会在本轮没注入时继续报上一轮的数 ——
     * 那样"没注入"与"注入了"读数相同，字段就废了。
     */
    private val lastRagInjected = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * **注入当时的库名**（与 [lastRagInjected] 同一个时刻取）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么库名必须和条数**同源取一次**，不能到响应时现读
     * ══════════════════════════════════════════════════════════════════════
     * `local_rag` 字段是 `{kb, injected}` 一个**对象** —— 它描述的是"这一轮
     * 用了哪个库、注入了几条"这一件事。到响应时再 `remoteKb(prefs)` 现读一次，
     * 两者就可能来自**两个不同的时刻**：
     *
     *   · 用户在生成期间（长生成可以几十秒）在 App 里把「远端库名」从 A 改成 B；
     *   · 本轮实际是拿 A 检索、注入了 3 条，而响应里写成 `kb:"B", injected:3`。
     *
     * 调用方读到的是一对**从未同时成立过**的事实，而它没有任何现象 ——
     * 字段本身合法、JSON 合法、只是内容不真。这正是本仓库反复要消灭的
     * "分成两次读，中间恰好变了一次"那一族（见 [RagIndexStore.readState] 的注释）。
     *
     * 所以与条数**同一个判据点**取出来（注入成功那一刻），响应时只读这两个共享值。
     * 它在**每轮请求开始时清零**，语义与 [lastRagInjected] 一致。
     */
    @Volatile private var lastRagKb: String = ""

    /**
     * 上一轮注入块的**字符数**（0 = 本轮没注入；语义与 [lastRagInjected] 一致：
     * 每轮请求开始清零，注入成功才写上真实值）。
     *
     * 为什么要单独一格：注入块的**宽度**一变，本轮提问那一段就全变、KV 前缀断在块头。
     * 这句话以前只写在一条 uiLog 里（`（块定长 513 字 / 每条上限 170）`），
     * 而 /health 上一个数都没有 —— 于是"复用为什么这轮没了"永远要人去翻日志对它。
     * 宽度从 `ctxPerSlot` 推出来（`injectionChars`），而 `ctxPerSlot` 会随并发数变：
     * 换一次 parallelN，块宽就跟着变，前缀当场断 —— 把宽度落成读数，这件事才看得见。
     */
    private val lastRagChars = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 「两路共用一份 ctx」这句告警**本进程已经说过没有**（ISSUE #426）。
     *
     * 为什么要一次性：`parallelN=1` 是默认档，也就是说这句话对绝大多数用户永远成立。
     * 每轮都打一遍会把日志淹掉（本仓库最贵的一次教训就是"日志太吵等于没有日志"），
     * 一次不说又等于没有 —— 用户看到的是"KV 复用忽有忽无、某轮突然 400"，
     * 而这两件事的成因在日志里一个字都没有。
     */
    private val ctxSharedNoticed = java.util.concurrent.atomic.AtomicBoolean(false)

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
     * 取值 64 的依据（不是拍出来的）：正常客户端最多持 1~2 条连接（生成 + 轮询 `/health`）。
     * 64 给了"多个客户端 × 各自几条连接"的余量，同时把线程数钉在两位数 ——
     * 手机进程的线程上限通常在数百量级，64 留足安全边际。
     *
     * ⚠ 原始依据里的「本服务**单并发**」这句在阶段 4 之后**失效了**（§9.2.5）：
     * 现在 `parallelN` 会真的开出 N 路并发，一个客户端持 2 条生成连接是**被鼓励**的。
     * **64 本身仍然够用**（每路仍是一条连接 + 轮询 `/health`），改的是那句话 ——
     * 不写清的话，下一个读代码的人会按失效的依据去调它。
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
     * 是否有生成在跑（池里有没有 slot 被占）—— 日志/诊断/看门狗用。
     *
     * ⚠ 阶段 4 起判据**只有一个来源**：池子里有没有 slot 被占（`SlotScheduler.busy`）。
     * 此前是 `busy || LlmEngine.isGenerating` 两个拼出来的，而那两个量在多 slot 下
     * 含义已经从「有生成在跑」**漂成**「池里还有 slot 被占」，却仍由两个来源拼 ——
     * 正是设计文档 §9.2.4 要消掉的那类漂移。
     *
     * 为什么用 slot 而不是 `LlmEngine.isGenerating`（= `RequestCancel.active`）：
     * slot 是在**受理那一刻**就占住的，覆盖了「渲染 prompt / prefill 之前」那一段
     * （那段可能很长，prompt 渲染要走模板）；而 `RequestCancel` 只覆盖
     * 「prefill 成功 → 收尾」。后者是前者的**真子集**，所以 slot 口径更宽、也更简单。
     */
    val isGenerating: Boolean get() = SlotScheduler.busy > 0

    /**
     * 只有 **HTTP** 生成任务（用于日志/诊断）。
     *
     * 语义与 [isGenerating] 的区别：只看被 **HTTP** 占着的 slot。
     * 两者在多 slot 下会**真的不同**（App 内聊天占 slot 0、HTTP 占 slot 1 时，
     * `isGenerating=true` 而它若 App 那路是唯一占用者则本字段为 false）。
     */
    val isHttpGenerating: Boolean get() = (SlotScheduler.countBySource()[SlotScheduler.Source.HTTP] ?: 0) > 0

    @Volatile private var server: ServerSocket? = null

    /** Service 加载模型后设置，用于 /v1/models 与日志。 */
    @Volatile var currentModel: String? = null

    /** 启动方（InferenceService）注入；为空时 /v1/models 优雅降级，绝不抛异常。 */
    @Volatile var appContext: android.content.Context? = null

    /**
     * 读 RAG 相关 prefs 用的 `Context` —— **本 object 里唯一的取口**。
     *
     * ⚠ 为什么要有这一层：`HttpApi` 是 `object`（单例），函数体里的 `this` 是
     * **HttpApi 自己**，不是 `Context`。而 `RagPrefs.remoteEnabled/remoteKb`（以及
     * `LlmEngine.ragContext` 的首参）要的是 `android.content.Context` —— 直接写
     * `RagPrefs.remoteKb(this)` 是**编译不过**的（`object` 不是 `Context`），
     * 这不是"能不能拿到值"的问题，是类型不匹配。
     *
     * 取不到 `Context` 时**抛异常**而不是回退成"当作没开"：本函数在 `handleChat`
     * 的 `try` 之内被调用，异常由既有兜底转成 500；这与"静默按未配置处理"相比，
     * 差别是**出错时能查**。而唯一的正常调用方（`start()` 之前由 InferenceService
     * 注入）事实上保证它不为空 —— `injected` 无埋点、`static` 会在首次访问就初始化
     * 并丢掉后来注入的那一个，两者都是"看着能跑、跑起来是错的"，所以不采用。
     */
    private fun ragPrefs(): android.content.Context =
        appContext ?: throw IllegalStateException(
            "HttpApi.appContext 未注入：RAG 相关读数必须在 Service 启动后使用")

    /**
     * 本轮注入块的渲染宽度（每条正文多少个 UTF-16 码元；0 = 不注入 / 容量未知）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么宽度不按"当前每格容量"每次重算（ISSUE #330 第 6 块）
     * ══════════════════════════════════════════════════════════════════════════
     * `ContextBudget.injectionChars(ctxPerSlot, topK)` 按**当前**每格容量给宽度，
     * 而每格容量 = `n_ctx / parallelN`。用户把并发数从 1 改到 2 之后重新加载，
     * 块里每一条都短一截 —— 块是拼在"本轮提问"之前的，块一变长，它**之后**的
     * 每一轮都对不上，前缀断在块头，历史全部重来。
     *
     * 定长块只解决"同一批命中 → 同样字节"，解决不了"换了个并发数 → 同样字节"。
     * 所以宽度改成**按会话定一次**（落盘在 `SessionStore.injectWidthOf`）：
     * 只在第一次渲染注入块时按当时的容量定下，之后跟着会话走 —— 容量再变也不挪。
     *
     * ⚠ 远端 HTTP 是**无状态**的（一次请求一个 slot，没有会话 id），所以这里按
     *   "**当前模型 + 当前知识库**"这个组合记一次宽度（`ragWidthStore`），
     *   而不是按对话会话 —— 它与 App 内那条路（按会话）是同一条口径的两种落点：
     *   **同一个"上下文"里宽度不许变**。
     */
    private fun sessionInjectWidth(): Int {
        val ctx = LlmEngine.ctxPerSlot()
        // 水位要传：块与提问一起待在 `ctx × 水位` 以内，按整格摊会让块自己吃穿
        // 水位留的那一截（见 `ContextBudget.injectionChars` 的说明）。
        val now = ContextBudget.injectionChars(
            ctx, RagInject.DEFAULT_TOP_K, ContextWatermark.get(ragPrefs()))
        if (now <= 0) return 0
        val key = RagPrefs.remoteKb(ragPrefs()) + "|" + LlmEngine.modelDesc()
        synchronized(ragWidthLock) {
            val remembered = ragWidthStore[key] ?: 0
            val width = ContextBudget.sessionInjectionChars(now, remembered)
            if (remembered <= 0) ragWidthStore[key] = width
            return width
        }
    }

    /**
     * 远端注入块宽度的记忆（key = 知识库名 + 模型描述；见 [sessionInjectWidth]）。
     *
     * 为什么不落 SharedPreferences：它只在**一次进程生命周期**里需要稳定 ——
     * 重启之后模型与知识库都要重新加载、KV 账本本来也已作废（`kv_invalidate`
     * 「模型卸载/换模型」），前缀无论如何要从头攒。落盘只会多一份"什么时候该清"
     * 的判据，而那正是这类静默 bug 的温床。
     */
    private val ragWidthStore = HashMap<String, Int>()
    private val ragWidthLock = Any()

    /**
     * 中间层**最近一次** relay 请求前置删了几轮（`/health` 的 `relay_forwarded`）。
     *
     * 为什么是一个全局的"最近一次"而不是按会话：`/health` 没有会话上下文，
     * 而它要回答的问题是"中间层在不在干活"（单值问题）。按会话报会让探活方
     * 需要先知道该带哪个会话名 —— 那个名字只有客户端有。
     *
     * ⚠ 它是读数，不参与任何判据。0 = 最近一次原样转发（成因看 `relay_last_reason`）。
     */
    @Volatile private var relayForwardedRounds = 0

    /** 中间层最近一次的那句人话原因（与日志同一份文案，`/health` 的 `relay_reason`）。 */
    @Volatile private var relayLastReason = ""

    /**
     * 本会话**实际生效**的远端注入块宽（字符；0 = 这一进程还没注入过 / 宽度算不出来）。
     *
     * 与 `lastRagChars` 分开：那是"**上一轮**那个块有多宽"（每轮变），这是"本会话
     * 定下来的宽度"（定了就不再随并发数变，见 [sessionInjectWidth]）。前缀断在块头
     * 这件事的判据是后者 —— 拿前者去比，会因为"命中的条目正文长短不同"而每轮都
     * "变了"，那与"宽度被重新算过"在读数上一模一样。
     *
     * 报的原因：`embed_batch_size_index`（库认的批大小）有出口，而库认的**块宽**
     * （`embed_chunk_index`）对面这一格此前没有 —— 只有库那一半，对账无法成立。
     */
    private fun injectWidthOf(ctx: android.content.Context): Int =
        synchronized(ragWidthLock) {
            ragWidthStore[RagPrefs.remoteKb(ctx) + "|" + LlmEngine.modelDesc()] ?: 0
        }

    /**
     * 远端知识库**当前是否真的在注入**（`remote_enabled && remoteKb 非空`）。
     *
     * 与其他判断"远端那条路当下开着没有"（[RagPrefs.remoteEnabled]）分开，
     * 语义是**两者都满足**。叫 `ragActive` 而不是 `remoteEnabled` 是刻意的：
     * 与 [RagPrefs.remoteEnabled] 同名会成为"到底是哪一个"的长期误读源。
     *
     * ⚠ **不提供"传 null 就当作没开"的重载**：那正是本文件此前六处错误的温床 ——
     * 少写一个 `?`（或漏掉判空）就变成"静默按未配置处理"，接口照 200、内容不注入，
     * 与"用户没开"完全同形。宁可让它在类型层面就编译不过。
     */
    private fun ragActive(): Boolean =
        RagPrefs.remoteEnabled(ragPrefs()) && RagPrefs.remoteKb(ragPrefs()).isNotEmpty()

    /**
     * 「这一档只有一份 ctx，两路共用」这句话（ISSUE #426，一次性）。
     *
     * 判据是 `SlotScheduler.total <= 1`（`parallelN=1` 或除不尽回落），**不是**
     * `sharedAt` —— 那句话说的是这一档的固有约束，与"此刻两路是不是都压着"无关：
     * 用户很可能正是那种"一边开 App 一边试 HTTP"的用法，而这一刻不并发的概率很低。
     *
     * 位置在**拿到 lease 之后**：没拿到 slot 的那一轮连生成都没开始，
     * 对着它讲"两路共用"只是噪音（而且那句 503 本身已经把原因说了）。
     */
    private fun noteSingleCtxOnce(slot: Int) {
        if (SlotScheduler.total > 1) return
        if (!ctxSharedNoticed.compareAndSet(false, true)) return
        emitLog("parallelN=1：App 内聊天与 HTTP 共用 slot $slot 这一份 ctx（同一条 seq，" +
            "位置只有 ${LlmEngine.ctxPerSlot()} 枚）—— 请只在 App 或 HTTP 任一侧使用，" +
            "两路同时用会互相顶掉 KV 前缀（复用忽有忽无、某轮突然 400）。" +
            "要两路同时用请把 parallelN 调到 2 以上后重新加载模型（本提示只打一次）")
    }

    val isRunning: Boolean get() = running.get()

    private fun emitLog(s: String) {
        Log.i(TAG, s)
        // 多行日志（如 [body] 的逐条摘要）经 logSink 进日志页时会被当成**一行**
        // 塞进 120 行窗口与 ring，排版就散了。落盘那条管线（pushLog -> LogFileStore.append）
        // 本来就是按行追加，所以只有 UI 这一路需要按行拆开后各走一次 ——
        // 不拆的话「日志页看不见第 2 条起的消息」而文件里有，两边对不上。
        val sink = LlmEngine.logSink
        if (s.indexOf('\n') >= 0) {
            for (ln in s.split('\n')) try { sink?.invoke("[http] $ln") } catch (_: Exception) {}
        } else {
            try { sink?.invoke("[http] $s") } catch (_: Exception) {}
        }
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
    // 自检周期见 [WATCHDOG_IDLE_MS] / [WATCHDOG_BUSY_MS]；
    // 探活失败**不立刻重启**（那会掐断正在跑的流），只登记 [restartPending]，空闲后才重启。
    //
    // ⚠ 2026-09-26（Issue #159）重写这一段的**判据**，三处都是"原来那样看着对、
    //    实际会掐断正在跑的流"：
    //   1. **生成期必须完全不碰 socket、不重启**。冻结/长 prefill 期间本进程整套都慢，
    //      2s 探活必然 timeout；周期压到 5s 之后，连续两次失败的概率直接乘 6 ——
    //      而 [restartListenerIfDesired] 里的 `stop()` 会 close() 掉**所有在途连接**，
    //      包括那条正在续传的 SSE。所以生成期为真时只做内存态检查、连 socket 都不建。
    //   2. **探活失败不立刻重启，只登记 [restartPending]，等空闲再重启**。
    //      "监听假活"与"有一条流正在跑"在探活结果上完全同形，任何立刻重启都是拿
    //      正在跑的流去赌；延后到空闲再重启，赌注就不存在了。
    //   3. 探活超时 2s → [PROBE_TIMEOUT_MS]：后台冻住时本进程连自己都慢，
    //      2s 会把"服务好好地在跑"自造成"失败"。
    private val watchdogRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 后台冻结的**痕迹**（A3）：看门狗上一轮从"准备睡"到"醒来"之间，实际过了多久。
     *
     * 为什么必须有：冻结期间**没有任何症状** —— 探活不会失败（那会儿它根本没在跑）、
     * 连接不会断、写不抛异常、日志不写。没有这行痕迹，"A2 到底有没有生效"
     * 就永远验不了：解冻后探活一次成功，与"从来就没被冻过"读数完全一样。
     * 判据只能是**墙钟**：`Thread.sleep(5s)` 却过了一小时，那就是被冻了一小时
     *（或机器进了深睡），二者对调用方是同一件事：这段时间没人 accept。
     *
     * 另一个用途在 App 内：设置页/存活探测把"上次被冻 N 秒"显示出来，
     * 用户不用再靠猜"是不是被限制了"。
     */
    @Volatile private var lastSleepMs: Long = 0
    /** 最近一次"睡眠远超预期"的超出量（毫秒）；0 = 从没被冻过。 */
    @Volatile private var lastFreezeMs: Long = 0
    /** 累计被冻的超出量（毫秒）。 */
    @Volatile private var freezeTotalMs: Long = 0
    /** 累计被冻次数（每次超出阈值算一次）。 */
    @Volatile private var freezeCount: Int = 0
    /**
     * "睡眠超时多少算被冻"的阈值：一个 3 倍余量。
     * 取相对值而不是绝对秒数，是因为周期本身有两档（5s / 30s），
     * 用一个固定阈值时要么在小周期上误报、要么在大周期上漏报。
     */
    private const val FREEZE_FACTOR = 3

    /** 「上次被冻了 N 秒」的对外读法（App 内 UI 与 /health 共用一份）。 */
    val lastFreezeSeconds: Long get() = lastFreezeMs / 1000
    /** 「累计被冻 N 次 / 共 M 秒」的对外读法。 */
    val freezeCountTotal: Int get() = freezeCount
    val freezeTotalSeconds: Long get() = freezeTotalMs / 1000
    /** 上一轮看门狗实际睡了多少毫秒（诊断用；正常 ≈ 周期）。 */
    val lastWatchdogSleepMs: Long get() = lastSleepMs
    /** 当前是否欠着一次"空闲后重启"（诊断用）。 */
    val isRestartPending: Boolean get() = restartPending.get()

    /**
     * 记一次看门狗睡眠的实测时长，判断这一轮是不是**被冻过**。
     *
     * 记账放在独立函数里，理由与 [consumeRestartPendingIfIdle] 同族：判据只能有一处。
     * 内联在循环里的话，第二个睡眠点（将来若加）必然忘记记账，
     * 而"忘了记账"的症状是"冻结痕迹永远为 0" —— 与"从没被冻过"完全同形。
     */
    private fun recordWatchdogSleep(plannedMs: Long, actualMs: Long) {
        lastSleepMs = actualMs
        val extra = actualMs - plannedMs
        if (extra <= plannedMs * FREEZE_FACTOR) return  // 没超阈值：那一轮算正常
        lastFreezeMs = extra
        freezeTotalMs += extra
        freezeCount++
        emitLog("[watchdog] 检出后台冻结：本轮计划 ${plannedMs}ms、实际 ${actualMs}ms" +
            "（超出 ${extra}ms，累计 ${freezeCount} 次 / ${freezeTotalMs / 1000}s）")
    }

    /**
     * 「探活失败、但当时不适合重启」——等生成结束（空闲）后再重启。
     *
     * 为什么不能"失败即重启"：重启路径的第一步是 `stop()`，而 `stop()` 会收敛
     * **所有在途连接**（见那里第 ④ 步）。探活失败只说明"这一秒没连上"，
     * 它同时覆盖两种完全不同的处境：listener 真死了、或引擎被一条长生成占满
     * （accept 轮不上）。后者下重启＝当场掐掉那条流。
     *
     * 它不是"取消重启"，是**延后**：空闲后由 [consumeRestartPendingIfIdle] 消费掉。
     * 每次失败都重新置 true（不"只置一次"）：只要还有人探活失败，就还欠一次重启。
     */
    private val restartPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 探活周期（空闲）：解冻后 1~5s 内恢复，不再是最坏 30~60s。 */
    private const val WATCHDOG_IDLE_MS = 5_000L
    /** 探活周期（有生成在跑）：只是弱检查，拉到 30s，连唤醒次数都省下来。 */
    private const val WATCHDOG_BUSY_MS = 30_000L
    /** 连续探活失败多少次才算"服务真没了"（单次失败可能只是抖动）。 */
    private const val FAIL_STREAK_LIMIT = 1
    /** 自连探活超时。后台被冻时本进程整套都慢，2s 会把健康服务自造成失败。 */
    private const val PROBE_TIMEOUT_MS = 3_000

    private fun startWatchdog() {
        if (!watchdogRunning.compareAndSet(false, true)) return
        Thread {
            var failStreak = 0
            try {
                while (true) {
                    // ---- 两档周期 + 冻结痕迹 ----
                    // 周期：空闲 5s（解冻后 1~5s 恢复），生成期 30s（那时只是弱检查）；
                    // 两档都由这一处决定，别在循环里另写一份判据。
                    val plannedMs = if (isGenerating) WATCHDOG_BUSY_MS else WATCHDOG_IDLE_MS
                    val t0 = System.nanoTime()  // 只作差：墙钟跳变会把"被冻了多久"算错甚至算负
                    Thread.sleep(plannedMs)
                    // 睡够了吗？**只记实测**，不靠"我睡过 5s"这个假设：
                    // 被冻/被深睡时 sleep 会远超计划值，而那一刻正是 t0 之后才发现 ——
                    // 这也是本进程**唯一**能证明"刚才没人 accept"的证据（见 [lastSleepMs]）。
                    recordWatchdogSleep(plannedMs, (System.nanoTime() - t0) / 1_000_000L)
                    // ⚠ 判据是 desired（用户意图），**不是** running（当前状态）。
                    // 只看 running 时，A-1 一踩就把这里变成死分支 —— 设计目标
                    // "断联自愈"退化成"一旦踩中永久躺平"：
                    // 那次 stop() 会把 desired 置 false，而错误路径上的 stop() 与
                    // 用户主动停服在 running 上完全同形（都是 false），
                    // 只有 desired 能区分"我不该重启"与"我该重启"。
                    if (!desired.get()) { failStreak = 0; continue }  // 用户没要求跑，仅休眠待命
                    // ---- 闸门①：生成期只做内存态检查 ----
                    // 这一条是"绝不掐断正在跑的流"的全部保证：`isGenerating` 为真时
                    // **不发请求、不碰 socket、不重启**，只把探测推迟到空闲。
                    // 判据用 [isGenerating]（= 池里有没有 slot 被占，阶段 4 起只有一个来源，
                    // 见它的注释）—— 一条 socket 探活会挂在生成路径的锁上白占线程、
                    // 还给每次生成添几次调度抖动。
                    if (isGenerating) { failStreak = 0; continue }
                    // ---- 闸门②：欠着一次重启时，先补上（此时已确认空闲）----
                    // 放在探活**之前**：上一次失败登记的 restartPending 说明
                    // "那时没连上"，而此刻空闲 + 端口可能还活着 —— 先重启再探活
                    // 会让每次重启都多耗一轮；先消费登记、再探活，
                    // 恢复动作与观察动作各自只有一次。
                    if (restartPending.get() && consumeRestartPendingIfIdle()) {
                        failStreak = 0
                        continue
                    }
                    if (probeHealthy()) {
                        if (failStreak >= FAIL_STREAK_LIMIT) emitLog("[watchdog] 自检恢复正常")
                        failStreak = 0
                        continue
                    }
                    failStreak++
                    emitLog("[watchdog] 自连探活失败 $failStreak/${FAIL_STREAK_LIMIT} " +
                        "(port=$PORT, running=${running.get()})")
                    if (failStreak >= FAIL_STREAK_LIMIT) {
                        // ⚠ 这里**不**直接重启，只登记"欠一次重启"。
                        // 判据只有一处（见 [consumeRestartPendingIfIdle] 的注释）：
                        // 内联在这里时，新增的探活调用点都得自己记得补 desired 判据，
                        // 漏一个就是"用户已停服却被自动拉起"这种后台偶发故障。
                        restartPending.set(true)
                        failStreak = 0
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                watchdogRunning.set(false)
            }
        }.apply { name = "llm-watchdog"; isDaemon = true }.start()
    }

    /**
     * 这条连接的对端还在不在 —— **不写一个字节**（ISSUE #450 第十五次现场）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么不能拿"写一帧试试"当判据
     * ══════════════════════════════════════════════════════════════════════════
     * `OutputStream.write` 抛异常**不代表**对端走了：只要没被 `accept` 它会阻塞，
     * 而 socket 上的 `SO_SNDTIMEO` 一到就抛 `SocketTimeoutException: Write timed out`；
     * 非阻塞句柄上则是 `Would block`。prefill 期间对端在读一帧都没有的连接上
     * 干等（或者它自己正忙），这两个异常都**必然**会出现 —— 而旧代码把它们读成
     * "客户端已经离开"，于是**服务端自己取消了自己那一轮**。
     *
     * 真正能答"它走没走"的是 `POLLRDHUP` / `POLLHUP` / `POLLERR`：对端 FIN / RST
     * 一定会置位，且零超时查询不会阻塞、不产生任何字节。
     *
     * @return `false` = 确定对端已离开；`true` = 还在（或这条连接不是可 poll 的
     *   实现 —— 那一档退回旧行为，即"探测不到就当作还在"）
     */
    private fun pollConn(ch: java.nio.channels.SocketChannel): Boolean {
        val sel = java.nio.channels.Selector.open()
        try {
            // 注册用的 ops 只是一个"我关心它"的声明，不另判；真正的判据在 readyOps 上。
            ch.register(sel, java.nio.channels.SelectionKey.OP_READ)
            // 0 = 只查一次，不等待。
            sel.select(0)
            val ready = sel.selectedKeys()
            if (ready.isEmpty()) return true
            // ⚠ Android 的 `SelectionKey` 常量与 JDK **不是同一套数**：
            //   JDK 的 `OP_READ`（0x01）在 Android 上是 `OP_ACCEPT`，`OP_WRITE` 是 `OP_CONNECT`。
            //   这里按 Android 的取值: `OP_CONNECT`(0x08) = `POLLRDHUP`，
            //   `OP_WRITE`(0x04) = `POLLHUP|POLLERR`。名字与位是错位的，值才是真的。
            //   `POLLRDHUP` 在 Android 上对 socket 恒置位，所以它只作"确认还在"——
            //   真正的"走了"由 `POLLHUP` / `POLLERR` 给出，而那两个也在 `OP_WRITE` 里。
            // ⚠ 写死数值而不是引常量：常量名在两端同名不同义，读代码的人会读成另一件事。
            val hangup = 0x04 or 0x08
            val ops = ready.first().readyOps()
            return (ops and hangup) == 0
        } catch (_: Throwable) {
            // 查不出来就当作还在：这条判据只用来**带来**取消，不许因为"查不了"而误停。
            return true
        } finally {
            runCatching { sel.close() }
        }
    }

    /**
     * 消费 [restartPending]：**确实空闲**时才重启，否则原样留着。
     *
     * 返回值 = 是否真的重启了一轮。它在"不空闲"时也返回 false，但**不清标记** ——
     * 清了就等于把用户这次断联丢了（流跑完也没人补这次重启）。
     *
     * 之所以把"空闲判据"独立于调用方的 `if (isGenerating)` 再查一遍：
     * 那个闸门与这里之间隔着一次 `sleep`（最长 30s），这期间用户完全可能新起一轮生成。
     * 两个判据不是重复，是"进入前"与"动手前"各查一次 —— 后者才是真正拦住误杀的那道。
     */
    private fun consumeRestartPendingIfIdle(): Boolean {
        if (isGenerating) return false          // 这会儿又忙起来了：继续欠着，绝不掐流
        if (!desired.get()) { restartPending.set(false); return false }  // 用户已停服：这次欠账作废
        // ⚠ 用 `getAndSet(false)` 而不是"先读后清"：两行之间若有并发失败把标记又置上，
        //   "先读后清"会把那次新欠账**一起清掉**（丢一次重启，而症状与"从没失败过"
        //   完全同形）。getAndSet 是原子的，返回值只代表"我这一轮看到的是 true"。
        if (!restartPending.getAndSet(false)) return false
        // 唯一的重启调用点：到这里三条都已确认（欠着重启、此刻空闲、用户仍要跑）。
        // 判据由 tools/run_http_lifecycle_guard.sh 钉住 —— 它要求文件里存在
        // `if (restartListenerIfDesired(`，也要求 startWatchdog 里**没有**这个调用。
        if (restartListenerIfDesired("之前探活失败，此刻已空闲")) return true
        return false
    }

    /**
     * 探活失败时的自愈入口：**仅当用户仍想要服务在跑**（[desired]）时才重启。
     *
     * 抽成函数是为了让"想不想重启"这条判据只有一处 —— 命名与调用点由
     * `tools/run_http_lifecycle_guard.sh` 钉住（"重启判据收敛到唯一一处"），
     * 任何新增的探活调用点都得走它，漏一个就是一处"用户已停服却被自动拉起"
     *（而它只在后台偶发，真机上几乎抓不到）。
     *
     * 调用者只能有两个：[consumeRestartPendingIfIdle]（正常路径，先确认空闲）
     * 与测试。**绝不允许**在生成期为真的路径上直接调它：`stop()` 会关掉在途连接。
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

    /** 自连探活：TCP 连上并发 GET /health，[PROBE_TIMEOUT_MS] 内收到任意响应字节即健康。 */
    private fun probeHealthy(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), PROBE_TIMEOUT_MS)
            s.soTimeout = PROBE_TIMEOUT_MS
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

    /**
     * 中间层那一档的「预读 → 放开直写」（ISSUE #452「拿回真流式」）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它替掉的东西：整条响应缓冲
     * ══════════════════════════════════════════════════════════════════════════
     * 上一版把 relay 那一轮的响应**整条**写进缓冲、抽完 `local_ctx` 再原样转给客户端，
     * 于是流式响应的首帧延迟 = 整轮生成时长。代价换来的是"下一轮有坐标" ——
     * 而坐标只挂在**收尾帧**上，中间层读不到自己刚发的响应就永远没有输入。
     *
     * 这一版把坐标从收尾帧挪到**响应头与第一帧**（`local_ctx.prompt_prefix` /
     * `local_rounds` / `reuse` 三处都带），于是中间层只要看到**这一轮开头**就能
     * 算出该删哪几轮 —— 一旦算出来，剩下的字节就**直接流给客户端**。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 判据只有一条：没拿到坐标就继续缓冲（绝不直写）
     * ══════════════════════════════════════════════════════════════════════════
     * 拿到坐标之后多写的每一个字节都是**不可撤回**的：那一轮就已经"直连"了。
     * 所以释放点在 [absorb] 里，且只有两个：
     *   · 响应头就带着坐标（非流式 / `local_ctx` 在头里那一档）；
     *   · 前几帧之一带着坐标（流式那一档，`local_ctx` 在第一帧）。
     * 都不是 → 这一轮退回"整条缓冲"（行为与上一版逐字节相同，只是慢）。
     *
     * ⚠ 释放之后 `buf` 就**不完整**了：`RelayLedger.parseRelayResponse` 对一段
     *   截断的响应会解析失败 —— 那是**对的**（这一轮的坐标已经在释放那一刻记进账本了），
     *   收尾处再拿它覆盖一遍只会把一份好坐标换成"没有"。
     */
    private class RelayPrefetch(
        val session: String,
        /**
         * 这一档的响应体是不是 SSE（`stream=true` 且响应头声明了 `text/event-stream`）。
         *
         * 心跳只在 `true` 时开：它写的是**一帧 SSE 注释**（`writeFrame`，带 chunked
         * 分帧）—— 对 SSE 是协议的一部分，对一整段 JSON 的响应体不是。非流式
         * 那一档只保留节拍（探测与 `pending` 的补齐），一个字节都不写。
         */
        private val sse: Boolean = false,
        /**
         * 这一轮喂进去的那一份的正文顺序指纹（角色 + 正文，逐条）。
         *
         * 与坐标一起交给账本：下一轮进门时 `matchesChain` 用它验证"上一轮留下的
         * 那一段这一轮还在不在"。没有它，一份坐标就无从自校 —— 而"坐标过期"
         * 与"坐标正确"在删的时候是同一件事的两面（一个悄悄改用户的对话，一个正常省下 prefill）。
         */
        val chain: List<Pair<String, String>> = emptyList(),
    ) {
        /**
         * 这一轮响应的 `Content-Length`（非流式）。`null` = 流式 / 不知道长度。
         *
         * 存在的理由只有一个：**放开直写之后剩下的字节不是"没给过客户端"**。
         * 客户端按 `Content-Length` 读满就关，而 [release] 交给它的只有预读那一段；
         * 剩下的（哪怕只剩 500 字节）还在 [buf] 里，`handleRelayChat` 的 finally
         * 再 `writeTo(out)` 时服务端已经不再往这条连接写 —— 结果是 `Broken pipe`，
         * 而客户端手里是一份**截断的 JSON**。
         *
         * 真机现场（ISSUE #450 第七次）：`[probe] [http] handleChat 抛出：
         * java.net.SocketException: Broken pipe`，客户端一句"无输出"，
         * 而服务端日志里 `[s1] [计时] prefill 3241 tok / 62715ms` 全都在。
         */
        var contentLength: Int? = null
            private set

        /**
         * 整个 `RelayPrefetch` 共用的一把锁（`absorb` / `heartLoop` / `bodyKind` / `flushHeld`）。
         *
         * 心跳垫进 `buf` 的是一帧 chunked；插进别人半截帧之间就把分帧写坏了
         * （客户端读到长度行乱码，然后断流）。两者都只做"往 `buf` 追加一帧"。
         * 收尾侧读 `buf`（[bodyKind] / [pending]）也走它 —— `ByteArrayOutputStream`
         * 自己的方法有同步，但"取字节 -> 再解析"这一整段没有，不加锁会读出半截。
         *
         * ⚠ **只此一把**：合并 #528（预读心跳）与 #529（必经日志）时两边各带进来
         *   一份同名字段，机械并集就成了同一个类里两个 `lock` —— 那不是"冗余"，
         *   Kotlin 直接报 Conflicting declarations 与重载歧义，整个包编不出来。
         */
        private val lock = Any()

        /**
         * 上游已经写进来过字节了。
         *
         * 心跳只覆盖"等第一个字节"那一段（**整个 prefill**）：那之后字节自己会往下走，
         * 而 `buf` 里此刻那一帧说不定正是坐标帧 —— 往它前面垫一帧，[hasCoord] 的
         * 逐帧扫描就要多绕一帧（`RELAY_SCAN_FRAMES` 那点余量不该花在心跳上）。
         */
        @Volatile private var started = false

        /**
         * 上游（`handleChat`）上一次**往这条流里写字节**的墙钟时刻（`0` = 还没有过）。
         *
         * 只服务一件事：探针/心跳在"引擎还在算"的那段里**不写连接**（见 [PROBE_QUIET_MS]）。
         * 用墙钟而不是单调钟：它只与"多久没动了"比，不参与任何跨进程的时间差运算。
         */
        @Volatile private var lastUpstreamMs = 0L

        /**
         * 心跳线程（见 [RELAY_KEEPALIVE_MS]）。**只在"还没放开直写、上游也还没开口"时写** ——
         * 放开之后上游自己的心跳直接到客户端，这一层再垫就成了两倍。
         *
         * 退出条件收敛在 `released` / `started` / `closed` 三个已有标志上，
         * 不另立一个新标志：多一个就多一种"两个都说自己在跑"。
         */
        private val beat = Thread { heartLoop() }.apply { isDaemon = true; name = "relay-keepalive" }

        /**
         * 心跳往**哪儿**写：真连接（[relayOut] 点火时交进来）。
         *
         * ══════════════════════════════════════════════════════════════════════
         * 为什么不是写进 [buf]（ISSUE #450 第十一次现场：#516 合并之后「普通端点
         * 收不到文字、relay 能收到，但一样不能停」）
         * ══════════════════════════════════════════════════════════════════════
         * #528 把心跳垫进 `buf` 的理由是"`buf` 里的字节最终会原样转发给客户端" ——
         * 那句话对**预读还没放开**的那一档成立，可这一层一共四条出口，写进 `buf`
         * 在另外三条上分别变成：
         *   · [release]：那几帧 `: ping` 被**当成上游响应体**一起交出去（客户端收到
         *     一堆注释帧，且它们的长度被算进预读的那 8 KB 里）；
         *   · `hasCoord` 的逐帧扫描：注释帧要花掉 `RELAY_SCAN_FRAMES` 的余量；
         *   · `rememberContentLength` / [pending] / [flushHeld]：非流式那一档的
         *     补齐按"客户端还欠多少字节"算，缓冲里多了几十字节注释帧，补齐的一截
         *     整体错位 —— 客户端读到的 `Content-Length` 与实际字节对不上。
         * 反过来，"全等转发让客户端字节变多"这件事本身就是回归：直连那一档的心跳是
         * **带外**的（分帧注释，不属于响应体），而 relay 这一档此前把它变成了响应体。
         *
         * 所以心跳回到它唯一正确的去处：**直接写客户端连接**（[writeFrame]，与直连
         * 的 `sseComment` 逐字节相同）。`buf` 从此只装上游写进来的字节。
         *
         * ⚠ 这一段的结论仍然成立，但**只到此为止**（ISSUE #450 第十三次现场）：心跳
         *   写对地方还不够 —— 它落到真连接上之后，这条连接就有了两个写方，而
         *   "写对地方"与"写得完整"是两件事，后者见 [relayWriteLock]。
         */
        /**
         * 心跳这条路上**串行化**的那把锁 —— 与 [relayOut] 共用（ISSUE #450 第十三次现场）。
         *
         * ══════════════════════════════════════════════════════════════════════
         * 为什么它是必要的，而不是"心跳已经写对地方了"就够了
         * ══════════════════════════════════════════════════════════════════════
         * 心跳回到真连接（#533）之后，这条连接上**有两个写方**：心跳线程与生成线程。
         * 而 `writeFrame` 是**四次 `write` + 一次 `flush`**（长度行 / CRLF / 载荷 /
         * CRLF）—— 两次写交错进来，就是"一帧 chunked 被劈成两半"：客户端读到长度行
         * 乱码，然后断流。而它的现场与"服务端没发"完全同形（客户端一个字都收不到），
         * 排查方向全错。
         *
         * 所以心跳不再是"一帧孤独的注释"：它必须与生成线程的每一帧互斥。
         * 锁的持有点**只有两处**：心跳的一帧、以及 [relayOut] 的一次真实写。
         * 两处都只做"往连接上写"，所以不会互相拖慢。
         */
        private val relayWriteLock = Any()

        /** 一帧 `: ping`（`writeFrame` 的产物，**一次 `write`** 交给连接）。 */
        private val pingFrame: ByteArray = run {
            val payload = ": ping\n\n".toByteArray(Charsets.UTF_8)
            val head = payload.size.toString(16).toByteArray(Charsets.UTF_8)
            head + "\r\n".toByteArray(Charsets.UTF_8) + payload + "\r\n".toByteArray(Charsets.UTF_8)
        }

        @Volatile private var beatOut: OutputStream? = null

        /**
         * 心跳 / 探针写一帧（与 [relayOut] 的真实写互斥，见 [relayWriteLock]）。
         *
         * ⚠ 整帧**一次 `write`**（ISSUE #450 第十五次现场）：`writeFrame` 是四次写，
         *   而这一帧与生成线程的写共用一把锁 —— 四次写在锁里也一样是"四次"，被 OS
         *   拆开送达时客户端会读到半帧；而分帧一旦坏，客户端的表现是断流，
         *   与"服务端没发"同形。合成一帧发，收发两侧都少一种错法。
         */
        private fun beatWrite(dst: OutputStream) {
            synchronized(relayWriteLock) { writeOnce(dst, pingFrame) }
        }

        /**
         * 上游（= [relayOut]）写一段到真连接，**与心跳互斥**。
         *
         * 唯一存在的理由：`writeFrame` 是四次 `write`，心跳那一帧插进来就劈开分帧。
         * 判据只有一条 —— 这条连接上"一次写"必须是一个整体，`relayWriteLock` 就是
         * 那个整体的名字。
         */
        fun writeLocked(dst: OutputStream, bytes: ByteArray) {
            synchronized(relayWriteLock) { writeOnce(dst, bytes) }
        }

        /**
         * 心跳这条路上**写失败**（客户端已经走了）。
         *
         * 处置只有一处：`handleRelayChat` 的 finally 里把取消送到**这一路自己**
         * 头上（`requestAbortHttp`），不留给下一轮。读一次就清（[beatGone] 是
         * 一次性的事件，不是一个状态）。
         */
        @Volatile var beatGone = false

        /**
         * 这一轮**此刻**占着哪一格 —— 由 [relayOut] 在每次真实写时喂进来
         * （`SlotScheduler.currentSlotOf(lease)` 的落值，`-1` = 这一轮已经不在任何格上）。
         *
         * 为什么不让 [heartLoop] 自己去查：心跳线程手里没有 lease，而 `leaseAt(slot)`
         * 要先知道"我是哪一格"—— 那正是它要回答的问题。写方手里有 lease，由它把答案
         * 送进来，判据只有一处。
         */
        @Volatile private var slotNow = -1

        /**
         * 心跳写失败那一刻**这一轮占着的那一格**（`-1` = 没取到）。
         *
         * 为什么要单独记：`handleRelayChat` 的 finally 与 `handleChat` 的 finally 没有
         * 先后保证，而"这一轮在哪一格"只有调度器的表答得出。在**写失败那一刻**记下它，
         * 才把"取消打给谁"钉在事实上，而不是钉在收尾那一刻的猜测上。
         */
        @Volatile var slotAtGone = -1

        /** `relayOut` 每次真实写时喂进来的"我是哪一格"（见 [slotNow]）。 */
        fun noteSlotNow(slot: Int) { slotNow = slot }

        /** 由 [relayOut] 的第一次写调用记下真连接（心跳/探针都往它写）。 */
        fun startKeepAlive(out: OutputStream) {
            beatOut = out
        }

        /**
         * 起心跳（**在 prefill 结束之后**，由 `emit` 之前那一处调）。
         *
         * ⚠ 不能在这条流第一次写（= 写响应头）时就起（ISSUE #450 第十五次现场）：
         *   那时整段 prompt 还没算，而 prefill 是秒级的量 —— 心跳每 800ms 往一条
         *   "对端没在读"的连接上写一帧，只会把自己写进 `Write timed out`，
         *   而那一次失败以前被读成"客户端已经走了"。现在它与探针共用同一条静默判据
         *   （见 [PROBE_QUIET_MS]），起得早也不再有害，但"等第一个字节"这一段
         *   本来就该由探针负责，两套机制各管一段，节拍才只有一份。
         */
        fun arm() {
            if (sse && !beat.isAlive) runCatching { beat.start() }
        }

        /**
         * prefill 补的断连探测（[PrefillProbe] 在 relay 那一档把帧交到这里）。
         *
         * 它只做两件事：把帧送到客户端，以及**在写失败时判 GONE** ——
         * 直连那一档的探针就是靠"往真连接写"发现客户端走了，relay 这一档要是
         * 把帧咽进缓冲，那个判据就没了（而对端早走时缓冲里的帧永远不会失败）。
         *
         * ⚠ 它**总是**直接写连接，与"预读放没放开"无关：预读放手之前，客户端除了
         *   响应头之外一个字节都还没收到，而这一段正是整段 prefill —— 不给它这一帧
         *   就是"心跳开着、客户端一帧都没见着"（ISSUE #450 第九/十三次现场）。
         *   写进 `buf` 更是错的：那几帧会被 `release()` 当成上游响应体交出去。
         */
        /**
         * @return `true` = 这一帧真的写到连接上了；`false` = 没写（还没点火 / 已收工 /
         *   这一轮还在算）。调用方据此区分"发出去了"与"引擎忙着，没发"，
         *   而**不是**把 `false` 当成"客户端走了"（那正是上一版的失效点）。
         */
        fun relayPing(frame: ByteArray): Boolean {
            // ⚠ ISSUE #450 第十三次现场：探针这一帧**必须**落到客户端连接上。
            //
            //   这一层的 `buf` 是**上游响应体**的暂存处：`release()` 会把它当成
            //   "还没给过客户端的字节"原样交出去 —— 探针帧垫在里面就成了响应体的一部分
            //   （客户端字节变多、非流式那一档的 `flushHeld` 整体错位）。
            //   所以这一帧与心跳同一条出路：**真连接**。
            //
            // ⚠ 第十五次现场：在这里写，就会在 prefill 期间写 —— 而那时引擎正占着
            //   这条连接的另一头。对端没读走（读缓冲满 / 边读边算 / 代理不读了）时
            //   `write` 会抛 `Write timed out` / `Would block`，旧判据把它读成
            //   "客户端已经走了"并取消这一轮。用户读到的是「我没点停止，它 abort 了」。
            //   所以**先看时间**：这一轮还在算就一帧都不写（见 [PROBE_QUIET_MS]）。
            val dst = beatOut ?: return false
            if (closed.get()) return false
            if (SlotScheduler.busy > 0) return false
            val idle = lastUpstreamMs
            if (idle != 0L && System.currentTimeMillis() - idle < PROBE_QUIET_MS) return false
            beatWrite(dst)
            return true
        }

        /**
         * 这一轮 prefill 探针**真的往连接上写出去了**几个字节（只进日志）。
         *
         * 它与 [relayPing] 的分工：`relayPing` 负责"写"，这里负责"记账" ——
         * 探针线程是唯一知道"我这一帧写成功了没有"的人，而 relay 的收尾那句话
         * 需要它在，才能把「中间层替上游垫了多久的心跳」与「客户端见过几个字节」
         * 摆在一起读（ISSUE #450 第十四次现场：relay 点了停止没效果，服务端日志里
         * 一句都没有 —— 因为它确实一个字都没写出去过）。
         */
        fun noteProbePing() { pingFrames++; pingBytes += PROBE_PING_BYTES }

        /** 探针写成功过几帧 / 几个字节（`/health` 不读，只进收尾那行日志）。 */
        @Volatile var pingFrames = 0
            private set
        @Volatile private var pingBytes = 0

        /** 收工那句话里的一句话读数（`""` = 没用过探针，不必提）。 */
        fun probeText(): String =
            if (pingFrames <= 0) "" else "｜prefill 探针写过 $pingFrames 帧 / $pingBytes 字节"

        private fun heartLoop() {
            var wrote = 0
            var strikes = 0
            while (true) {
                try { Thread.sleep(RELAY_KEEPALIVE_MS) } catch (_: InterruptedException) { return }
                if (released || closed.get()) {
                    if (wrote == 0) why = "心跳一期都没写出去就退场（已放开直写 / 已收工）"
                    return
                }
                val dst = beatOut ?: return
                // ⚠ 引擎还在算就不写（ISSUE #450 第十五次现场）：见 [PROBE_QUIET_MS]。
                //   这一档不是"少发了一帧"，而是"不让一次写阻塞被读成客户端走了"。
                if (SlotScheduler.busy > 0) continue
                val idle = lastUpstreamMs
                if (idle != 0L && System.currentTimeMillis() - idle < PROBE_QUIET_MS) continue
                // 真连接上的**一次**写（整帧）：抛异常 = 对端已走（与直连同一条判据）。
                // 写成功什么也不说明，所以这条链上只**带来**取消、不会误停。
                val ok = try { beatWrite(dst); strikes = 0; true } catch (_: Throwable) { false }
                if (ok) { wrote++; continue }
                if (++strikes < PROBE_BLOCK_STRIKES) continue
                slotAtGone = slotNow
                beatGone = true
                why = "心跳写失败（客户端已离开）：第 ${wrote + 1} 帧"
                return
            }
        }

        /** 上游这一轮写完了（`handleRelayChat` 的 finally 置位）。心跳到此为止。 */
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 收工（`handleRelayChat` 的 finally 调一次；幂等）。[bodyKind] 此后再读即最终那份。 */
        fun close() {
            closed.set(true)
            beat.interrupt()
        }

        /** 这一段是不是响应开头那一段（只用于 `absorb` 那一次形状检查）。 */
        private var sawHead = false

        /** 见 `absorb` 里那一处：`chunk` 的前几个字节像不像一条响应。 */
        private fun rawKindOf(chunk: ByteArray, len: Int): String {
            if (len <= 0) return ""
            val first = chunk[0].toInt().toChar()
            if (first == 'H' || first == '0') return ""
            return "中间层收到的第一段字节不是 HTTP 响应头（首字节 '${first}'）：" +
                "这一轮交给客户端的是一段无头响应体"
        }

        /** 还没放开直写时，字节先到这里。 */
        val buf = java.io.ByteArrayOutputStream(RELAY_PREFETCH_BYTES)

        /** 已经放开直写（此后一切都直接写客户端）。 */
        var released = false
            private set

        /**
         * 这一轮**走到哪一步了**（ISSUE #450 第十次现场：用户说「relay 没有任何反应，
         * 日志也不提示」）。
         *
         * 为什么要有它：relay 这一档的每一处落点都**只有一行日志、且只在成功路径上**
         * （"坐标已记账" / "放开直写" / "没有坐标可用"）。于是三种完全不同的处境在
         * 服务端日志上**同形 —— 一行都没有**：
         *   · 请求根本没进 relay（路由/路径拼错，或客户端打的是直连）；
         *   · 进了 relay、但在 `handleChat` 里还没写出第一个字节就返回了
         *     （鉴权 401 / 模型未加载 503 / 占不到格子 503 / 参数 400 —— 这些
         *      `writeJson` 都走 `relayOut`，缓冲下来之后 hit 被冲掉，一行日志都不留）；
         *   · prefill 要 25~60 秒，那一段本来就不该有 relay 的日志。
         * 用户看到的是第三种，而前两种在日志上也长这样 —— 于是"没有反应"这句话
         * 无法指向任何一步。
         *
         * 判据是**台阶编号**而不是新增第二份真值：它只被 `relayOut` 与 `absorb` 推进，
         * 而那两个地方本来就是这一轮的唯二出口。收尾那句话**一定打**（见
         * `handleRelayChat` 的 finally），于是"relay 有没有人收工"不再需要靠猜。
         */
        var reached = RelayStage.ENTERED
            private set

        /** 推进台阶（只前不前退：`absorb` 每帧都调，回退会让收尾那句话自相矛盾）。 */
        fun reached(stage: RelayStage) {
            if (stage.ordinal > reached.ordinal) reached = stage
        }

        /**
         * 这一轮正文的**形状**：`text/event-stream` 还是整段 JSON。
         *
         * 判据只取**响应头**：`sseHead` 写的是 `text/event-stream`，`writeJson` 写的是
         * `application/json`。两个都是本进程自己拼的，所以这个答案在"什么都没攒下"
         * 时也答得出来 —— 那时只能给出"还没写头"。
         *
         * ⚠ 它**不参与**任何判定（心跳开不开、放开不放开都在别处定了），只进日志：
         *   用户在客户端里换一个 `stream` 开关，日志上就该看得见这一格变了 ——
         *   否则"垫的心跳去哪儿了"又是一句只能靠猜的话（ISSUE #450 第十次现场）。
         */
        fun bodyKind(): String {
            // ⚠ 头一旦被 [takeHeadBytes] 剥走，`buf` 里就只剩正文 —— 不能再去 `buf`
            //   里找 `\r\n\r\n`（那会把每一轮都报成「响应头还没收齐」，日志在这一格上
            //   变成一句假话）。优先读 [headSnapshot] 那份原件。
            val raw = synchronized(lock) {
                try {
                    headSnapshot?.toString(Charsets.UTF_8) ?: buf.toString("UTF-8")
                } catch (_: Throwable) { "" }
            }
            if (raw.isEmpty()) return "还没写响应头"
            val headEnd = raw.indexOf("\r\n\r\n")
            if (headEnd < 0) {
                // `headSnapshot` 是剥头时按 `\r\n\r\n` 切出来的，它一定带这段分隔；
                // 没有分隔说明 `buf` 里那份头还没收齐。
                return "响应头还没收齐"
            }
            val headPart = raw.substring(0, headEnd)
            return when {
                headPart.contains("text/event-stream", ignoreCase = true) -> "SSE（流式）"
                headPart.contains("application/json", ignoreCase = true) -> "整段 JSON（非流式）"
                else -> "其它"
            }
        }

        /** 收尾那句话里的「走到哪一步」（不参与任何判定）。 */
        fun stageText(): String = when (reached) {
            RelayStage.ENTERED -> "请求已进中间层，但还没有一个字节的响应（还在预算/prompt 那一段，或正要写）"
            RelayStage.FIRST_WRITE -> "刚从 handleChat 拿到第一个字节，还没攒够放开直写的条件"
            RelayStage.BUF -> "在预读窗口里攒字节（等坐标，或等攒满 $RELAY_PREFETCH_BYTES 字节）"
            RelayStage.RELEASED -> "已放开直写（剩下的字节直接流给客户端）"
        }

        /** 这一轮记进账本的原因（`/health` 的 `relay_reason` 之外的一句，只进日志）。 */
        var why = ""
            private set

        /**
         * 这一轮的坐标已经记进账本（[noteRelayCoord] 置位）。
         *
         * 保证"同一轮只记一次"。`false` 且整轮跑完 = 这一轮确实没报坐标
         * （记一笔成因，客户端据此原样转发）。
         */
        var coordTaken = false

        /**
         * 放开直写那一刻从预读里抽出来的坐标（`null` = 那一刻没抽到）。
         *
         * ══════════════════════════════════════════════════════════════════════
         * 为什么这一支要单独记账
         * ══════════════════════════════════════════════════════════════════════
         * [release] 之后 `buf` 就被清空了 —— 收尾那一支再拿 `buf` 去
         * `parseRelayResponse` 时拿到的是**剩下那半截**（多数时候是空串）。
         * 而"头里带坐标"那条路（非流式 / 头先到）恰恰是**只**在 [release] 那一刻
         * 手上有完整字节的那一档：一旦放开直写，这条响应就再也拿不回来了。
         *
         * 所以判据只有一条：**谁把字节交给客户端，谁负责在交出去之前记下坐标**。
         * 收尾那一支（[noteRelayCoord]，收尾帧里带 `prompt_prefix` 的那一档）仍然优先 ——
         * 它手上那份是完整的，且比这里多一格起喂点。
         */
        var coordSnapshot: RelayLedger.Snapshot? = null
            private set

        /**
         * 记一笔字节，返回**现在才该写给客户端**的那一段（`null` = 继续缓冲）。
         *
         * 返回值语义是"这些字节此前没给过客户端"，所以调用方只许 `write` 它一次 ——
         * 重复写就是客户端收到两遍（SSE 里表现为正文重复，而那件事的真机排查花了很久）。
         */
        fun absorb(chunk: ByteArray, len: Int): ByteArray? {
            // 上游真的开始写了（心跳就此退场，见 [heartLoop]）。
            started = true
            // 探针/心跳的"静默判据"读它（见 [PROBE_QUIET_MS]）：上游刚有动作 = 不许写。
            lastUpstreamMs = System.currentTimeMillis()
            reached(RelayStage.BUF)
            // ⚠ 一条检查，不改字节：`handleChat` 往这条流里写的第一段必须是响应头
            //   （`HTTP/1.1 ...`），或 chunked 的结束标记。别的一律记下来 ——
            //   它是"客户端那句 Unexpected status line 从哪来"的唯一直接证据
            //   （ISSUE #450 第十四次现场）。判据只落日志，不参与任何分支。
            if (!sawHead) {
                sawHead = true
                val kind = rawKindOf(chunk, len)
                if (kind.isNotEmpty()) {
                    why = kind
                    emitLog("relay[$session]：$kind")
                }
            }
            // ⚠ 放开直写之后的每一次写都**原样回到调用方手里**，由它持锁一次写完
            //   （见 `relayOut`）：这条连接上还有心跳/探针两个写方，而"响应头带坐标"
            //   （非流式 / 头先到）这一档是**响应头就已经**放开了 —— 那时 `buf` 里
            //   一个字节都没有，"剩下的字节"指的就是接下来这些，不是一段空缓冲。
            //   写成 `if (released) return chunk.copyOf(len)` 会让它们**只落进 `buf`**、
            //   由收尾去补交：于是客户端要等到整轮算完才看见第一个字节。
            if (released) return chunk
            // ⚠ 整段都在锁里：判据（`released` / `buf` / 攒满 / 有坐标）必须与
            //   "这一次要交出去的字节"是同一个决定。
            return synchronized(lock) {
                if (released) return chunk
                // ⚠ 响应头那一格**一次交齐**（ISSUE #450 第十五次现场）：`sseHead` / `writeRaw`
                //   都是"头 + 正文"一次给，而它们后面还跟着 flush 与别的写。留在 `buf` 里
                //   等"下一次写"或者"收尾"才交出去，都会让客户端先收到一堆帧再收到头 ——
                //   它按协议只能报错断开（现场是 `Unexpected status line: 8`，
                //   那个 `8` 正是某条长度行的首字符）。
                buf.write(chunk, 0, len)
                // `Content-Length` 只在**响应头**里出现，而头一定进得了预读窗口
                // （8 KB，真机几十字节）。读到它才能回答"客户端会读多少字节" ——
                // 那是 [pending] 的全部依据（见 `contentLength` 的注释）。
                if (contentLength == null) rememberContentLength()
                // 头在 `buf` 里了 —— 先把它交出去（见 [takeHeadBytes]）。
                if (headSnapshot == null && bufHasHeadEnd()) {
                    // ⚠ 判据必须取在**剥头之前**（本轮 relay 流式损坏的根因）：
                    //   `hasCoord()` 读的就是 `buf` 里那段完整响应头，而 `takeHeadBytes`
                    //   会把头从 `buf` 里摘走。先剥头再判 = 头里那行 `X-Relay-Coord`
                    //   已经不在 `buf` 里，`hasCoord()` 永远答"没有坐标"，于是这一轮
                    //   一路缓冲到 8 KB（短回答则到收尾）—— 首帧延迟又变回整轮时长。
                    //
                    //   头里带坐标那一档必须**同一次写**把"头 → 正文"按顺序交出去：
                    //   先写头、下一次写再写正文会让客户端先收到一截没有响应头的正文。
                    val headHasCoord = hasCoord()
                    val head = takeHeadBytes()
                    why = if (headHasCoord) "响应头已带坐标：先交头，随后放开直写"
                          else "响应头已攒齐：先交头，坐标到了再放开直写"
                    if (head != null) {
                        if (!headHasCoord) return head
                        val body = release()
                        return if (body.isEmpty()) head else head + body
                    }
                }
                // 到这里要么头已经交出去过（`headSnapshot != null`），要么这一块字节
                // 里还没有完整的头。下面任何 return 都只能是**正文**。
                if (buf.size() > RELAY_PREFETCH_BYTES) {
                    // 预读够了还是没有坐标：这一轮放弃记账，把攒下的字节**一次交给客户端**
                    // （退化成上一版的行为）。判据是"攒不下更多"而不是"攒够一屏"——
                    // 后者会让长回答的首帧延迟凭空多出一屏的时间。
                    why = "预读 ${buf.size()} 字节仍没有坐标 —— 退回整条缓冲"
                    return release()
                }
                if (!hasCoord()) return null
                why = "坐标已在响应开头，放开直写"
                return release()
            }
        }

        /** `buf` 里已经有一个完整的响应头（`\r\n\r\n`）。判据与 [headEndOf] 同一条。 */
        private fun bufHasHeadEnd(): Boolean {
            val b = buf.toByteArray()
            return headEndOf(b, 0, b.size) > 0
        }

        /**
         * 从攒着的字节里读 `Content-Length`（只认**读完的头**，半行不算）。
         *
         * 半行就解析会把 `Content-Length: 51` 读成 `51x`（下一行开头的字符），
         * 而那个数是"客户端会读多少"的唯一依据 —— 错一位，补齐的那一段就整体错位。
         * 判据与 [hasCoord] 那边同一条：`\r\n\r\n` 出现了才说明头读完了。
         */
        private fun rememberContentLength() {
            val raw = buf.toString("UTF-8")
            val headEnd = raw.indexOf("\r\n\r\n")
            if (headEnd < 0) return
            // 只有**完整读到头**时才落这一格。没有这个头也算一个结论（流式 / chunked
            // 没有 Content-Length）—— 但只在头完整时才敢下，半行时留着 null 等下一块。
            contentLength = null
            for (line in raw.substring(0, headEnd).split("\r\n")) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                if (!line.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) continue
                contentLength = line.substring(i + 1).trim().toIntOrNull()
                return
            }
        }

        /**
         * 这一轮已经攒下的字节里，**还有多少是客户端没收到过的**。
         *
         * 只对"非流式 + 已放开直写"那一档有值：那种响应的字节是**一次写完**的
         * （`writeJson` 拼完才 `write`），客户端按 `Content-Length` 读满就关 ——
         * 放开直写那一刻交给它的只是预读的那一段，剩下的还在 [buf] 里。
         * 流式那一档的字节本来就一帧一帧来，预读之后每一帧都会直接写出去，所以是 0。
         *
         * ⚠ 判据是**长度**，不是"流式/非流式"这个标签：`Content-Length` 是我们自己
         *   写在响应头里的（[writeJson]），它就是客户端会读多少的唯一依据。
         */
        fun pending(): Int {
            val total = contentLength ?: return 0
            if (!released) return 0                     // 还没放开：buf 里有全部，由 finally 写
            val held = buf.size()
            return (total - held).coerceIn(0, total)
        }

        /**
         * 把"客户端还没收到的那一截"补交出去（[pending] 有值时才会真的写）。
         *
         * 覆盖的场景：非流式 + 放开直写。判据与 [pending] 同一条 —— 只交
         * `contentLength - buf.size()` 个字节，一个不多。
         *
         * ⚠ 为什么走 `buf` 的**下标**而不是清空重发：`buf` 里此刻正是"整条响应减去
         *   预读那一段"，位置与客户端的读取位置一一对应；按长度算一遍，两边就不会
         *   错位（错位的表现是客户端 JSON 解析失败，而服务端只看到一次写失败）。
         */
        fun flushHeld(dst: OutputStream) {
            val n = synchronized(lock) { pending() }
            if (n <= 0) return
            val all = synchronized(lock) { buf.toByteArray() }
            // 客户端读的位置 = `contentLength - buf.size()` 起（预读交出去的就是那一段）。
            // 保守起见取"最后 n 个字节"：两者的落值在正常一轮里相同，取尾部不会
            // 多交任何字节（多交就是协议错，少交就是截断 —— 两者里少交更容易看）。
            val from = (all.size - n).coerceAtLeast(0)
            dst.write(all, from, all.size - from)
            dst.flush()
        }

        /**
         * 放开直写：返回"客户端现在该收到的那一段"（`null` = 一个字节都还不该给）。
         *
         * ⚠ 响应头**在这条路径上只出现一次**，且排在最前面（ISSUE #450 第十五次现场 +
         *   本轮真机流式损坏）：
         *   · 正常路径上响应头已经被 [takeHeadBytes] 剥走（`headSnapshot != null`），
         *     这里返回的就是纯正文，头由调用方先前那次写送出去；
         *   · 极端碎写里头还留在 `buf` 开头 —— 这里把它切下来，和正文拼成
         *     "头 → 正文"**一次**返回，由 `relayOut` 写一次。
         *
         *   绝不能再把头另存进一个"待写"字段、让第二条出口再写一遍：客户端会把
         *   第二份 `HTTP/1.1 200 OK` 当成第二次响应，逐字节读到的流当场断开
         *   （现场：`Unexpected status line: 8`，那个 `8` 是某条长度行的首字符）。
         */
        private fun release(): ByteArray {
            released = true
            reached(RelayStage.RELEASED)
            // 交出字节**之前**先记账（这一跳之后 `buf` 可能被清空）。
            // 解析失败一律当"这一轮没有坐标"：中间层的失效必须是"退化成直连"，
            // 不能是每轮都拿一份半截 JSON 去覆盖账本。
            //
            // ⚠ 只在"手上真有完整字节"时才记（ISSUE #450 第十五次现场）：放开直写那一档
            //   此刻 `buf` 里只有**头之后**的那一段，解析出来的是 `null` —— 而
            //   `?.also { put }` 那种写法会把它**当成一次失败**，把账本里上一轮那份
            //   好坐标悄悄留在原地。那一轮的字（`local_ctx` 在响应头里）现在从
            //   [headSnapshot] 取；两处读的是同一份字节，判据只有一条。
            val all = buf.toByteArray()
            // 正常路径上响应头已经由 [takeHeadBytes] 剥走（`headSnapshot != null`），
            // `all` 就是正文。只有在"头分成多次写、还没剥过"的极端碎写里，头才会
            // 仍在这一批字节的开头 —— 那一刀仍然要切，但切下来的头**跟着这批字节一起
            // 交给调用方**（顺序：头 → 正文），不再存一个"待写头"字段。
            //
            // ⚠ 以前那一版把切下来的头存进 `heldHead`，而 `absorb` 同时也把头作为
            //   返回值交给 `relayOut` —— 同一个头被写了两遍，客户端把第二份当成
            //   第二次响应，流当场断开。
            var head: ByteArray? = null
            var body = all
            if (headSnapshot == null) {
                // ⚠ 交出去之前必须把"这不是一条合法响应"的那一档说明白（见 [rawKind]）：
                //   它一旦被原样写给客户端，客户端读到的第一个字节就是正文中间的一个十六进制
                //   数字，报出来的是 `ProtocolException: Unexpected status line: 8` —— 而
                //   服务端这一侧什么都看不出来（每一格读数都正常）。
                //   头已经剥走那一档 `all` 是正文，首字节当然不是 `H` —— 那**不是**坏响应，
                //   所以这条检查只在"还没见过头"时跑。
                val kind = rawKind()
                if (kind.isNotEmpty()) {
                    why = kind
                    emitLog("relay[$session]：$kind")
                }
                val hs = headEndOf(all, 0, all.size)
                if (hs > 0) {
                    head = all.copyOfRange(0, hs)
                    headSnapshot = head
                    body = all.copyOfRange(hs, all.size)
                }
            }
            buf.reset()
            coordSnapshot = (RelayLedger.parseRelayResponse(all.toString(Charsets.UTF_8))
                ?: headSnapshot?.let { RelayLedger.parseRelayResponse(it.toString(Charsets.UTF_8)) })
                // ⚠ 这里必须带上 `chain`：非流式那一档 `noteRelayCoord` 先记了一次，
                //   紧接着 `writeJson` 触发这里的 `release()`；用默认空链再 put 一次
                //   会把刚记好的指纹冲掉，于是下一轮 `matchesChain` 永远为假 ——
                //   `relay_forwarded` 恒 0，而每一格读数都正常。
                ?.also { RelayLedger.put(session, it, chain) }
            return when {
                head == null -> body
                body.isEmpty() -> head
                else -> head + body
            }
        }

        /**
         * 从 `buf` 里**剥出**开头那个响应头（真的剥：`buf` 里只剩正文），`null` = 还没攒齐 / 已经剥过。
         *
         * 与 [release] 的区别：这一刀**不**改 `released` —— 它只说"头可以先出门"，
         * 不说"整条响应从此直写"。响应头本来就应该越早到越好（它是客户端判断
         * "这一轮开始了"的唯一依据），而中间层要等的坐标在第一帧里。
         *
         * ⚠ 头**只在返回值里出现一次**：调用方（[absorb] → `relayOut`）拿到什么就写什么。
         *   这里不再把头另存进一个"待写"字段 —— 那样同一个头会被写两遍，而两遍
         *   在客户端看来就是"第二次响应"，流直接断（本轮真机流式损坏的根因）。
         */
        private fun takeHeadBytes(): ByteArray? = synchronized(lock) {
            if (headSnapshot != null) return null
            val all = buf.toByteArray()
            val hs = headEndOf(all, 0, all.size)
            if (hs <= 0) return null
            buf.reset()
            buf.write(all, hs, all.size - hs)
            val head = all.copyOfRange(0, hs)
            headSnapshot = head
            return head
        }

        /**
         * 已经攒齐的响应头（只用于**读**：`bodyKind` 与账本兜底解析）。
         *
         * 它**不是**待写队列：头写不写、写给谁，只由 [takeHeadBytes] / [release]
         * 的返回值决定。保存这一份是因为头一旦从 `buf` 里剥走，解析 `local_ctx`
         * 与判断"这一轮是 SSE 还是整段 JSON"就再没有第二处可读。
         */
        private var headSnapshot: ByteArray? = null

        /**
         * 缓冲里那段字节**是不是一条合法 HTTP 响应**（`""` = 是，非空 = 一句话说明）。
         *
         * 判据只有两条，都在**响应开头**：
         *   · 第一个字节是 `H`（`HTTP/1.1` 的状态行），或 `0`（chunked 结束标记 —— 一条
         *     没有正文的 204 那一档）；
         *   · 还没写头（`buf` 空）也算合法 —— 那一档收尾什么都不会交出去。
         *
         * 为什么要它（ISSUE #450 第十四次现场）：真机上报的
         * `Unexpected status line: 8` 里那个 `8` 是**正文中间的一个十六进制数字**
         * （长度行的一部分），说明客户端从半截帧开始读，而服务端日志里一个字都没有 ——
         * 它确实"发出去了"，只是发出去的那段字节已经不是一条响应。
         * 这条判据不改任何字节（中间层不许替上游改协议），只让它**说出来**：
         * 这一轮到底是因为"我们自己拼坏了"才没有输出。
         */
        fun rawKind(): String {
            // 头已经交出去时 `buf` 里是正文（chunked 长度行），当然不以 `H` 开头 ——
            // 那不是"响应坏了"，只是这一层已经把合法的头送走了。
            if (headSnapshot != null) return ""
            val head = synchronized(lock) { buf.toByteArray() }
            if (head.isEmpty()) return ""
            val first = head[0].toInt().toChar()
            if (first == 'H' || first == '0') return ""
            return "缓冲里的字节不是一条 HTTP 响应（首字节 '${first}'）：" +
                "这一轮交给客户端的会是一段无头响应体，客户端按协议会报 " +
                "Unexpected status line"
        }

        /**
         * 把缓冲里**还没交给客户端**的字节一次取走，并清空它（收工唯一出口）。
         *
         * 为什么不是 `buf.writeTo(out)`（ISSUE #450 第十四次现场）：
         * `ByteArrayOutputStream.writeTo` **不清空自己** —— 它只是把内容再写一遍。
         * 这一层一共四条出口（放开直写的直写 / [flushHeld] 补齐 / 这一处收工 /
         * 异常路径），其中两条会走到这里：
         *
         *   · 放开直写那一档：`buf` 里是"已经写出去过"的尾巴，再写一遍就是客户端
         *     收到一截**重复帧**（chunked 长度行错位 -> 断流 -> 与"没有输出"同形）；
         *   · 整条缓冲那一档：`flushHeld` 刚补完，这里又写一遍，同样重复。
         *
         * 判据只有一条：**每个字节只交给客户端一次**。取走即清是它的实现，
         * 而"取走"这个词同时也是这条链的收尾语义（此后 `buf` 里什么都没有）。
         */
        fun takeAll(): ByteArray = synchronized(lock) {
            val all = buf.toByteArray()
            buf.reset()
            all
        }

        /** 攒到现在的那点字节里，有没有一条能用的坐标。 */
        fun hasCoord(): Boolean {
            val raw = buf.toString("UTF-8")
            if (raw.isEmpty()) return false
            // 头里就有（非流式 / 以及头先到的那一档）：这是最快能放开的路径。
            //
            // ⚠ 判据是「**整个头读完了**，且里面那一行有一个左花括号」—— 不是
            //   "出现过 `X-Relay-Coord:` 这个前缀"。后者在半行还没写完时为真，
            //   而那时放开直写 = 客户端先收到一个半截头（`Connection: clos`），
            //   整条连接当场作废。头读没读完只有一个判据：`\r\n\r\n`。
            val headEnd = raw.indexOf("\r\n\r\n")
            // 两种形状都可能走到这里：
            //   · 头还在 `buf` 里（第一次写）—— 先看头里那一行，再从头之后扫帧；
            //   · 头已经被 [takeHeadBytes] 剥走（`headSnapshot != null`）—— `buf`
            //     从 chunked 长度行开始，直接扫帧。
            //
            // ⚠ 两种形状必须**分开**判：剥头之后 `buf` 里全是正文，哪怕正文里
            //   恰好出现 `\r\n\r\n`（空载荷帧等），也不能把它当成响应头再解析一遍。
            //   以前这里无条件按 `headEnd` 分支，剥头之后正文里通常没有 `\r\n\r\n`
            //   （SSE 用 LF），于是"坐标在第一帧"永远判不出来。
            var seen = 0
            if (headSnapshot == null) {
                if (headEnd < 0) {
                    // 头还没读完 = 这时候判"有坐标"会让客户端先收到半截头。
                    return false
                }
                val headPart = raw.substring(0, headEnd)
                val coordAt = headPart.indexOf("X-Relay-Coord:")
                // 值是 `local_ctx:{...}`，只认它开头那个 `{` —— 值合法与否由账本那边
                // （`RelayLedger.parse`）判，这里只判"够不够放开直写"。
                if (coordAt >= 0 && headPart.indexOf('{', coordAt) >= 0) return true
                val after = raw.substring(headEnd).trimStart()
                // 头之后就是整段 JSON 那一档（非流式）：`local_ctx` 落在 body 里，
                // 而 body 是**一次写**发出的 —— 见到 `{` 就说明整条都在缓冲里了。
                if (after.startsWith("{")) {
                    return raw.indexOf("\"local_ctx\"") >= 0
                }
                seen = headEnd + 4
            }
            // 帧里才有：只认**已经完整收到**的那一帧，半截 `data: {...` 上判"有坐标"
            // 会立刻释放，而下一帧才带坐标 —— 那正是"这一轮没记住"的那一档。
            var frames = 0
            while (seen < raw.length && frames < RELAY_SCAN_FRAMES) {
                val eol = raw.indexOf("\r\n", seen)
                if (eol < 0) return false                 // 长度行还没到齐
                val n = raw.substring(seen, eol).toIntOrNull(16) ?: run {
                    // ⚠ 长度行不是十六进制：**这一轮的分帧已经坏了**，往后扫只会
                    //   一帧一帧地错位。这一格以前静默 `return false`（与"还没有坐标"
                    //   同形），于是"分帧坏了"这件事在任何地方都读不出来。
                    why = "预读里的 chunked 长度行不是十六进制（第 ${frames + 1} 帧）—— 分帧已坏，这一轮不按坐标放开"
                    return false
                }
                // ── 空窗口守卫（ISSUE #450 第十次现场：有人写了 0 字节的帧？）──────
                // `n == 0` 在 chunked 里是**结束标记**，正常轮次里不会出现在中间。
                // 而这里以前 `n <= 0 -> return false`：攒到一帧 `0\r\n\r\n` 之后，
                // 后面就算紧跟一帧带着 `local_ctx`，`hasCoord` 也永远答"没有" ——
                // 于是**这一轮永不放开直写**，整条响应都被攒到收尾，而日志上只有一句
                // "预读满 8KB 仍没有坐标"（还是猜的）。跳过它，把后面的帧照样扫一遍：
                // 判据一个字都没变（仍然只认"完整收到的那一帧里有 local_ctx"）。
                if (n == 0) {
                    // ⚠ 长度行之后**不只是** CRLF：`0\r\n` 后面紧跟的还有一个 `\r\n`
                    //   （结束标记那一帧的"空载荷"），也就是整整 5 个字节 `0\r\n\r\n`。
                    //   只跳 `eol + 2` 会让游标停在结束标记后半截的 `\r\n` 上，下一轮
                    //   往下找 `\r\n` 会落到**下一帧的载荷里面**，于是从那里起把正文
                    //   当成长度行读 —— 而正文以 `d`（`data: `）开头，`toIntOrNull(16)`
                    //   回 null，这一轮就永远"没有坐标"（从未放开直写，全缓冲到收尾）。
                    //   这也正是客户端那句 `Unexpected status line: 8` 的来路之一：
                    //   从正文中间开始读，第一个字符恰好是一个十六进制数字。
                    //   跳满 4 个字节才是"这一帧到此为止"（长度行 + CRLF + 空载荷 + CRLF）。
                    why = "预读里遇到一个 0 字节的帧（chunked 结束标记）—— 跳过它继续找坐标"
                    seen = eol + 4
                    frames++
                    continue
                }
                if (n < 0) return false          // 负数长度行：同"不是十六进制"那一档
                val bodyAt = eol + 2
                if (bodyAt + n > raw.length) return false // 帧还没收完
                if (raw.substring(bodyAt, bodyAt + n).indexOf("\"local_ctx\"") >= 0) return true
                seen = bodyAt + n + 2
                frames++
            }
            // 扫满 [RELAY_SCAN_FRAMES] 帧还没有：这一轮大概率永远等不到坐标，
            // 但**别在这里就下结论**（后面的帧可能带）。留个痕，收尾那句话会带上它。
            if (frames >= RELAY_SCAN_FRAMES) why = "预读扫满 $RELAY_SCAN_FRAMES 帧仍没有坐标"
            return false
        }
    }

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
        /**
         * 请求头没在本端给的窗口内读完（客户端连上了，但**一个完整请求都没发过来**）。
         *
         * 与 [overlongLine] 同一个道理：它必须能被**单独判掉**，否则症状是
         * "连接被静默断开" —— 客户端只看到 `Empty reply from server`，
         * 与"服务崩了"完全同形。见 [handleConn] 的 A1 说明。
         */
        val headTimedOut: Boolean = false,
        /**
         * `Content-Length` 的值既不是合法整数、也不在合法范围内（→ 400）。
         *
         * **与 [tooLarge] 分开、且必须分开**：`99999999999999` 这种溢出值一旦被
         * 折成 0，`> MAX_BODY_BYTES` 就永远不成立，于是连 413 都触发不了 ——
         * "超限"这个语义轮不到它，它连一个合法长度都不是。同理 `abc` / `5abc`
         * （`toIntOrNull` 只认整串）与 `-1`（能解析、但不是长度）。
         */
        val badLength: Boolean = false,
        /**
         * body **没读满**声明的 [contentLength] 就遇到了 EOF（→ 400）。
         *
         * 与 [headTimedOut] 是两个阶段、两个码：头没读完是本端等超时（客户端还在，
         * 只是没说完 → 可重试的 503）；body 没读完是**对端关了连接**，请求已经不完整，
         * 再重试也只是重发同一个残包 → 400。折在一起的话，"传输截断"
         * 会退化成两种错方向：截在 JSON 合法处 = 静默按错误语义跑，
         * 截在非法处 = 500「服务内部错误」。见 [Req.shortBody]。
         */
        val shortBody: Boolean = false,
        /** 上面那种情形**实际**收到了多少字节（只用于错误文案，不回显内容）。 */
        val gotBodyBytes: Int = 0,
        /**
         * 请求头 `X-Log-Tag: 1` —— 这一轮的日志**要求带上 `[sN]`**（ISSUE #403 第三次现场）。
         *
         * 为什么要调用方开口：单格独占时服务端按 §8.1 **一个字都不加**（那是既有口径），
         * 而用户拿两路比对日志时，"哪一行是哪一路"就成了必须先有答案的问题。
         * 开关只影响**这一条连接**的日志前缀，不改行文、不改任何落值 ——
         * 不带的请求仍与改动前逐字相同（既有锚字符串的守卫一条都不用重认）。
         */
        val logTag: Boolean = false,
        /**
         * 中间层会话键（只对 `/v1/relay/chat/completions` 有意义；其余端点恒为空串）。
         *
         * 为什么不用 `currentSessionId`：App 内那张会话表是**另一条路**的东西，
         * 只用 HTTP 的场次里它可能是空的 —— 那时所有请求共用一个键，
         * 上一轮的坐标会跨会话串（`RelayTrim` 的对不上判据再挡一次，但那是兜底不是设计）。
         * relay 这一侧拿的是**客户端自己给的** `X-Relay-Session`（缺省 = 整台设备一份），
         * 口径是"同一个键 = 同一条对话"，由调用方决定。
         */
        val relaySession: String = "",
    )

    /**
     * 解析阶段的 `Content-Length` 读取器（纯函数，可离线单测）。
     *
     * 返回值是**三态**，为的是让"头缺失""头非法""头合法"在上层能各自处置：
     * 缺失 → 按无 body 处理（合法）；非法 → 400；合法 → 记长度。压成两态
     * （比如 `toIntOrNull() ?: 0`）就是静默丢 body 的根因，别退回那种写法。
     *
     * 判据本体（[Parsed]）与接线（[parseReq]）分开的原因与其余判据同源：
     * 纯函数能离线喂 `abc` / `5abc` / `-1` / `2147483648` / `99999999999999`，
     * 接线只能靠读代码。
     */
    internal object ContentLengthHeader {
        /** 三态：头缺失（合法）、头非法（→ 400）、头合法（记长度）。 */
        sealed class Parsed {
            /** 头缺失：语义等价于 `Content-Length: 0`。 */
            object Absent : Parsed()
            /** 头在，但值不是合法长度（非整数 / 负数 / 溢出 Int）。 */
            object Invalid : Parsed()
            /** 头在且合法。 */
            data class Length(val bytes: Int) : Parsed()
        }

        /** `toIntOrNull` 只认**整串**：`5abc` 与 `abc` 都是 `Invalid`，不会被截成 5。 */
        fun parse(raw: String): Parsed {
            val n = raw.trim().toIntOrNull() ?: return Parsed.Invalid
            // 负数单独拦：`"-1".toIntOrNull()` 返回 -1 而非 null，光靠 `?: 0`
            // 挡不住它，它会一路活到 `if (contentLength > 0)` 才变假 —— 同样是丢 body。
            if (n < 0) return Parsed.Invalid
            return Parsed.Length(n)
        }
    }

    /**
     * 「这一行超长」的哨兵。用**对象**而不是某个字符串字面量的原因：
     * 任何客户端都无法让一个正常读出的行恰与它 `===` 相等。
     * [readLine] 靠它把"超长"与"读完（EOF）/头结束（空行）"分开 ——
     * 混在一起时，超长行之后的所有头会被静默丢弃（见 [parseReq]）。
     */
    private val LINE_TOO_LONG = String()

    /**
     * 「这一行的读超时了」的哨兵（A1，Issue #159）。
     *
     * 与 [LINE_TOO_LONG] 同构、也必须分开：`readLine` 原来把
     * `SocketTimeoutException` 直接抛出去，于是"客户端还在、只是没说完"
     * 与"客户端崩了"在调用方那条 `?: break` 上**完全同形**，最终都落到
     * `parseReq` 返回 null → `handleConn` 静默 return —— 客户端只看到
     * `Empty reply from server`，会去查模型/进程，而真因只是"这个客户端
     * 自己没把请求发完"。有了这个哨兵，那条路径才能回**可读的 503**。
     *
     * 用对象而不是字符串：任何客户端都无法让一个正常读出的行恰与它 `===` 相等。
     */
    private val HEAD_TIMED_OUT = String()

    /** 头/请求行的长度上限（字节 ≈ 字符，头里全是 ASCII）。 */
    private const val MAX_LINE_LEN = 8192

    /** 请求体上限。超限**拒绝**（413），不截断。 */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /**
     * **读完请求头**的等待上限（A1，Issue #159）。
     *
     * 语义**只有一条**：从连接被受理，到请求头读完，最多等这么久，且**只在没人在生成时**
     * 才计时。超时回一个**可读的 503 JSON**，而不是静默断连。
     *
     * 为什么必须"只在没人在生成时才计时"：15s 这个数原本是拍出来防"连上就不发数据"
     * 占满线程槽的。但一旦有生成在跑，服务端本来就忙、客户端拿 503 也是应得的；
     * 而空闲期给它 15s 完全没有意义 —— 空闲意味着服务端毫秒级就能接手，
     * 15s 收不到任何字节只可能是客户端根本没打算说话。缩短窗口让"假活"
     * 更早变成"明确失败"（客户端可以据此重试），代价是零（正常客户端不发请求就不该连）。
     *
     * ⚠ 取 5s 而不是更短：解冻后的第一拍会慢，客户端可能正好在这时连上并开始发头，
     *   窗口太短会把一次**正常但慢**的请求判死。
     *
     * ⚠⚠ **这是 `soTimeout`，而 `soTimeout` 只作用在 `read()` 上**（ISSUE #450 第七次
     *   现场）。此前它的注释写着"首字节之前"，但 socket 一受理就把它设成了读超时，
     *   于是一整轮 **prefill**（真机 3241 tok / 62.7 秒）走到第 5 秒时，服务端会对着
     *   一条**根本没在等响应**的连接 `read()` 超时 —— 客户端此刻正在等服务端出字，
     *   没有义务再发任何字节。超时之后那一轮连一行日志都没有就走到了 else 分支，
     *   客户端拿到的是断流（`Broken pipe` / `prefill 失败`），而服务端的账本里
     *   还留着一段半截 prompt。
     *
     *   所以读超时的语义现在**只管"请求头读完了没有"**：`parseReq` 一返回就调到
     *   [GEN_READ_TIMEOUT_MS]（见 [handleConn]）。两件事各用一个数，不再共用一个
     *   被读成"首字节"的名字。
     */
    private const val HEAD_TIMEOUT_MS = 5_000

    /**
     * 请求头读取的上限（在**生成期**使用）。
     *
     * 生成期不缩短窗口：那时服务端本来就忙，任何"服务端说你别等了"的判断
     * 都不该由读超时来做（真忙的结论由 [handleChat] 的 503 背压给出，那条路径
     * 带着明确的 `server busy` 文案）。这里保持原来的 15s，行为与改动前一致。
     */
    private const val HEAD_TIMEOUT_BUSY_MS = 15_000

    /**
     * **请求头读完之后**的读超时（ISSUE #450 第七次现场：prefill 60 秒被它掐掉）。
     *
     * 与 [HEAD_TIMEOUT_MS] 是**两件事**，共用一个数是这一整族现场的共同起点：
     * 那两个数是"等客户端**发**请求头"的预算，而请求头读完（`parseReq` 返回）之后，
     * 这条连接的**读方向**就再也不会被用到 —— 生成路径**只写不读**。
     *
     * 于是把窗口放到 [MAX_BODY_BYTES] 那个量级的默认值上（0 = 不做读超时）：
     * 它不是"允许客户端挂多久"，而是"我们不会再去碰读方向"。真正的保护由
     * 写方向那三条负责 —— 生成循环的心跳/断连探测（[heartbeat]、[probeOutbound]）、
     * prefill 窗口的 [PrefillProbe]、以及 [stop] 的整池收敛。它们判的是"对端还在不在"，
     * 而那与"对端还打不打算说话"完全无关。
     *
     * ⚠ 0 值是 `Socket.setSoTimeout` 的"无限"语义。设在请求头读完之后是安全的：
     *   那一刻之后本进程**没有任何**读调用，阻塞的只会是写。
     */
    private const val GEN_READ_TIMEOUT_MS = 0

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
        // ---- A1：首字节之前的等待上限，且**只在空闲时**收紧（Issue #159）----
        //
        // 读超时只作用于 **read** 方向（生成路径只写不读，不受影响），
        // 且超时之后这里不再静默 return，而是回一个可读的 503 ——
        // 这是本改动的全部：把"卡死/静默断连"降级成"明确失败 + 可重试"。
        // 判据只取一次（`isGenerating` 在受理那一刻的快照）：连接一旦开始读头，
        // 中途"忙起来了"不该改变已经定下的窗口，否则同一条连接的超时值会随
        // 别人起生成而漂移，成了不可复现的偶发。
        val headTimeoutMs = if (isGenerating) HEAD_TIMEOUT_BUSY_MS else HEAD_TIMEOUT_MS
        s.soTimeout = headTimeoutMs
        s.use { sock ->
            val ins = sock.getInputStream()
            val out = sock.getOutputStream()
            // 这条连接的可 poll 视图（见 [pollConn]）：prefill 探针用它判"对端还在不在"，
            // 而**不写一个字节**。拿不到（老 JVM / 非 TCP）时保持 null —— 那时探针
            // 退回"探测不到就当作还在"，绝不因为查不了而取消一轮。
            val chan = runCatching { sock.channel as java.nio.channels.SocketChannel }.getOrNull()
            // 解析与 CORS 判定**排在畸形请求判据之前**：那四条 400/413 的响应要读
            // `req.*` 字段、也要带 `corsOrigin`，两者都由下面两行产出。顺序反过来
            // 时它们一起变成 `unresolved reference` —— 报错看不出是顺序问题。
            val req = parseReq(ins) ?: return
            // 请求头读完了 —— 这条连接的**读方向**从此不会再被用到（生成只写不读）。
            // 不把读超时放回去的话，一整轮 prefill（真机 60 秒）会在第 5 秒上让
            // `read()` 超时，而客户端那一刻正在等服务端出字（没有义务再发任何字节）——
            // 结果是"服务端日志里什么都看不见、客户端只收到断流"（ISSUE #450 第七次）。
            // 「对端还在不在」由写探测判（心跳 / prefill 探针），不由读超时判。
            runCatching { s.soTimeout = GEN_READ_TIMEOUT_MS }
            val corsOrigin = CorsPolicy.allow(req.origin)
            // 畸形请求**必须**挡在路由与鉴权之前，且**各自回自己的码**：
            // 两者都曾退化成"静默丢内容"，症状分别是"token 明明没错却说不匹配"
            //（头被丢）与"服务内部错误"（body 被截断后 JSON 解析炸）。
            // 这两个码（400/413）都不在 CORS 白名单外豁免的讨论范围内：
            // 它们不是业务拒绝，而是"这个请求本身没读全"，回什么都救不了。
            if (req.badLength) {
                emitLog("拒绝畸形请求: Content-Length 不是合法长度（非整数 / 负数 / 溢出）")
                writeJson(out, 400, errJson("invalid Content-Length header " +
                    "(must be a non-negative integer)"), corsOrigin)
                return
            }
            if (req.shortBody) {
                // 文案里给出「差多少字节」：只回一句"请求不完整"的话，
                // 排查方分不清是客户端少发了、还是服务端少读了（两边都说得通）。
                emitLog("拒绝截断请求体: 声明 ${req.body.length + req.gotBodyBytes}" +
                    " 字节，实际收到 ${req.gotBodyBytes} 字节后连接结束")
                writeJson(out, 400, errJson("incomplete request body: " +
                    "received ${req.gotBodyBytes} bytes, connection closed early"), corsOrigin)
                return
            }
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
            // ---- CORS 白名单判定（**每个响应**都要带，不只是预检）----
            //
            // 判据在 CorsPolicy（纯函数、可离线单测）。这里只做三件事：
            //   ① 算出允许回显的 Origin（null = 不放行 → 不加任何 CORS 头）；
            //   ② 把它透传给响应写入器（普通响应也带，否则"预检过了、正式请求被拦"
            //      这种最难查的形态就会出现）；
            //   ③ 预检单独走一个 204 分支。
            //
            // ⚠ 位置必须在**所有** `writeJson` 之前 —— 首字节超时的 503、下面的
            //   400/413 拒绝也都带 CORS 头，所以它们是 corsOrigin 的**首批读者**。
            //   写在它们之后 = 先用后声明，局部变量在 Kotlin 里没有提升，直接编译不过
            //   （2026-09-22 CI 红：`unresolved reference 'corsOrigin'` ×2）。
            //   那一轮 22 个脚本都在 grep 这个文件，但**没有一个编译它** ——
            //   判据见 tools/run_http_hardening_guard.sh 判据⑨（结构顺序断言）。
            //
            // ⚠ 首字节超时那条路径上根本没读到请求头，能被判的只有"来源未知"，
            //   而"来源未知"在 CorsPolicy 里就是不放行 —— 与正常请求漏带 Origin
            //   是同一条判据，不是特例。所以这里先把请求读出来（`parseReq` 自己
            //   把超时折成 [Req.headTimedOut]，不再抛异常），再统一判 CORS：
            //   `corsOrigin` **只有这一处声明**，也就没有"第二条路径漏透传"的空间。
            // 来源被拒时留一行日志：浏览器侧看到的只是"请求失败"，
            // 没有这行日志就无法区分"来源被拦"与"服务坏了"。
            if (req.origin != null && corsOrigin == null) {
                emitLog("CORS 拒绝来源: ${req.origin}（不在白名单内，未回 CORS 头）")
            }
            // ---- A1：首字节超时 → 可读 503（必须在 corsOrigin 之后）----
            // 位置由 `tools/cors_writejson_guard.sh` 钉住：**每一条**写出 JSON 的调用
            // 都必须能透传 corsOrigin。这条 503 也是响应，不能例外 ——
            // 漏了它在浏览器里就退化成"预检过了、正式请求被拦"那种最难查的形态。
            // 解析阶段自己判出的"头没读完"（[Req.headTimedOut]）走同一条路径。
            if (req.headTimedOut) {
                emitLog("首字节超时（${headTimeoutMs}ms 内未收到完整请求头，解析阶段判定）：回 503")
                writeJson(out, 503, errJson(
                    "request head not received within ${headTimeoutMs}ms; " +
                        "service is up, retry with a complete request"), corsOrigin)
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
                // ⚠ **顺序是判据**：`/v1/models/{id}` 必须排在 `/v1/models` 之前。
                // 反过来会被前缀命中吞掉，客户端拿到的是一份**列表**而不是它要的那个
                // 模型对象 —— 而列表本身是合法 JSON，SDK 侧只会解成"字段缺失"，
                // 报出来的错与"模型不存在"长得一模一样，排障方向全错。
                // 判据落在 tools/run_models_profile_guard.sh 的**行号关系**上（与
                // `parseReq` 的哨兵那两条同一个做法），不靠 review 肉眼保证。
                req.method == "GET" && pathUnder(req.path, "/v1/models/") ->
                    writeJson(out, 200, modelEntryJson(idFromModelsPath(req.path)), corsOrigin)
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
                // `/v1/embeddings`（阶段 3）：**无状态逐条算向量**。
                // 位置在 chat 之前、models 之后，路径前缀互不包含，顺序不影响行为；
                // 读起来"先清单、再嵌入、后对话"贴合使用顺序。
                // ⚠ 它与知识库（RAG）开关**完全解耦**：这个端点不读任何 rag_* 开关，
                //   也不因为"App 内/远端没开知识库"而被拒绝 —— 它只判嵌入模型在不在。
                req.method == "POST" && pathUnder(req.path, "/v1/embeddings") ->
                    handleEmbeddings(req, out, corsOrigin)
                // ⚠ **顺序是判据**：`/v1/relay/...` 必须排在 `/v1/chat/completions`
                // 之前。两者路径前缀互不包含（`/v1/relay` vs `/v1/chat`），今天顺序不影响
                // 行为；但把它写在后面就等于让"以后有人把 relay 改成 `/v1/chat/completions/relay`"
                // 这一种命名悄悄走进 chat 的分支 —— 那时症状是"relay 端点行为与直连一模一样"，
                // 而它看起来完全正常（结果确实是对的，只是绕过了中间层）。
                req.method == "POST" && pathUnder(req.path, "/v1/relay/chat/completions") ->
                    handleRelayChat(req, out, corsOrigin, chan)
                req.method == "POST" && pathUnder(req.path, "/v1/chat/completions") ->
                    handleChat(req, out, corsOrigin, chan = chan)
                req.method == "POST" && pathUnder(req.path, "/v1/completions") ->
                    handleCompletion(req, out, corsOrigin)
                // 取消端点放在两个生成端点之后：路径前缀互不包含，顺序不影响行为，
                // 但读起来「先补全、后取消」更贴合使用顺序。
                // `/v1/abort` 支持按 slot 取消（缺省 0）：见 [handleAbort] 与 [parseAbortSlot]。
                req.method == "POST" && pathUnder(req.path, "/v1/abort") ->
                    writeJson(out, 200, handleAbort(req), corsOrigin)
                else ->
                    writeJson(out, 404, errJson("not found: ${req.path}"), corsOrigin)
            }
        }
    }

    /**
     * 读并解析请求（请求行 + 头 + body）。
     *
     * 读取阶段的**超时**与"读到 EOF"必须分开：EOF 是客户端真走了（静默 return 合理），
     * 超时是"客户端还在，只是没说完"（要回可读的 503，见 [headTimedOut]）。
     * 请求行与请求头的读超时都在这里折成 [Req.headTimedOut]；**body** 阶段的读超时
     * 保持原语义（当"连接坏掉"处理，由调用方的 `s.use{}` 收尾）—— 那时请求已成立、
     * 响应还没开始写，回什么都救不了那次请求。
     */
    private fun parseReq(ins: InputStream): Req? {
        val first = readLine(ins) ?: return null
        // 读取阶段的两个哨兵分开判：读超时（HEAD_TIMED_OUT）与超长（LINE_TOO_LONG）
        // 语义完全不同（前者=客户端还在只是没说完、后者=这一行根本读不完），
        // 都不能与 EOF（`?: return null`）混为一谈。
        if (first === HEAD_TIMED_OUT) return Req(method = "", path = "", body = "", headTimedOut = true)
        if (first === LINE_TOO_LONG) return Req(method = "", path = "", body = "", overlongLine = true)
        val sp = first.split(' ')
        if (sp.size < 2) return null
        val method = sp[0]
        val path = sp[1]
        var contentLength = 0
        // 「头缺失」「头非法」「头合法」必须是**三种**状态，不能压成两种
        //（三态判据在 [ContentLengthHeader]，这里只记结果）。压缩的坏法就是
        // 原来那行 `value.toIntOrNull() ?: 0`：
        //   · `abc` / `5abc` —— toIntOrNull 只认整串，返回 null；
        //   · `99999999999999` / `2147483648` —— 溢出，同样返回 null；
        //   · `-1` —— **能解析**，返回 -1，`?: 0` 拦不住它。
        // 三者都落进「没有 body」那一支：body 一个字节都不读、真实字节留在 socket 里，
        // 客户端却收到一个"正常"响应，全程零错误信号。
        var badLength = false
        var origin: String? = null
        var reqHeaders: String? = null
        var logTag = false
        var auth: String? = null
        var relaySession = ""
        while (true) {
            // `?: break` 这里只表示 EOF（连接在半路断了）—— 超长与读超时都**不**返回 null，
            // 而是在下一行被单独判掉（两个哨兵，见 [LINE_TOO_LONG] / [HEAD_TIMED_OUT]）。
            // 三者分开是 B-3 的修复本体，别把顺序调过来。
            // ⚠ 下面这两条判定必须**按这个顺序、且紧跟** readLine 的 `?: break`：
            //    ① 超长（LINE_TOO_LONG）必须在 readLine 的**下一行**（判据②锚的是
            //       这个相邻行号关系，插一行注释进去都会红）；② 读超时（HEAD_TIMED_OUT）
            //       紧随其后。语义上三者完全不同：超时 = 客户端还在、只是没说完；
            //       EOF = 客户端走了；超长 = 这一行根本读不完 —— 混起来只能静默断连。
            val line = readLine(ins) ?: break
            if (line === LINE_TOO_LONG) return Req(method = "", path = "", body = "", overlongLine = true)
            if (line === HEAD_TIMED_OUT) return Req(method = "", path = "", body = "", headTimedOut = true)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) continue
            val name = line.substring(0, i).trim().lowercase()
            val value = line.substring(i + 1).trim()
            when (name) {
                // 非法值**置标记**（→ 400），绝不折成 0 —— 折成 0 就是静默丢 body。
                // 负数单独拦：`"-1".toIntOrNull()` 是 -1 不是 null，`?: 0` 挡不住，
                // 它一路活到 `if (contentLength > 0)` 才变假，结局同样是丢 body，
                // 但根因不在"解析失败"，所以只改 `?: 0` 修不掉这一支。
                "content-length" -> when (val len = ContentLengthHeader.parse(value)) {
                    is ContentLengthHeader.Parsed.Length -> contentLength = len.bytes
                    ContentLengthHeader.Parsed.Invalid -> badLength = true
                    ContentLengthHeader.Parsed.Absent -> Unit
                }
                // 鉴权凭据也必须**读出来**：不读的话 [ApiAuth.verdict] 永远拿到 null，
                // 于是要么把所有人挡在门外、要么（若顺手 fail-open）等于鉴权没做。
                "authorization" -> auth = value
                // Origin 与预检头都要**读出来**（不能只靠响应侧拼头）：
                // 白名单判定必须有来源，没有它就退化成"要么全放行、要么全拦"，
                // 而这两者都不是"白名单式"。
                "origin" -> origin = value
                "access-control-request-headers" -> reqHeaders = value
                // 日志前缀开关：只认明确的 `1` / `true`。写成"只要头在就开"会让
                // 反向代理 / SDK 顺手带的空值把它打开，于是老锚字符串的守卫
                // 会在用户那边突然全部对不上（而服务端一切正常）。
                "x-log-tag" -> logTag = value == "1" || value.equals("true", ignoreCase = true)
                // 中间层会话键（见 [Req.relaySession]）。**只读不改**：它不参与任何
                // 鉴权与路由判据，只是 relay 那一侧记账用的一个字符串；缺省时由
                // `handleRelayChat` 折成 "default"（= 整台设备一份，与不带这个头同形）。
                "x-relay-session" -> relaySession = value.take(64)
            }
        }
        // 超出上限**直接拒**（不是截断）：`coerceAtMost` 会把多余字节留在 socket 里
        // 没人读，截断后的 JSON 再交给解析器去炸 —— 客户端拿到的是「服务内部错误」，
        // 真因却是「请求体超限」。宁可回一个说明白了的 413。
        // ⚠ **顺序是判据**：非法值先判，再判超限。反过来的话溢出值
        //（`2147483648`、`99999999999999`）会被 413 抢走 —— 报的是"体太大"，
        // 而它连一个合法长度都不是，客户端照着"体太大"去改会改不到点子上。
        if (badLength) {
            return Req(method, path, "", origin, reqHeaders, auth, badLength = true, logTag = logTag)
        }
        if (contentLength > MAX_BODY_BYTES) {
            return Req(method, path, "", origin, reqHeaders, auth, tooLarge = true, logTag = logTag)
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                // EOF 在这里**不代表请求读完了**：声明了 N 字节却只到了 off 字节，
                // 请求是残的。原来这条 `break` 直接收工、把半包当完整 body 交上去，
                // 于是两种症状、没有一种是"截断"：残片恰好是合法 JSON 时静默按错误
                // 语义执行（典型：`"stream":true` 落在没读到的后半段里 → 按非流式跑完，
                // 客户端等 SSE 等到超时）；残片非法时 500「服务内部错误」，
                // 真因（传输截断）被掩盖。退回给调用方判 400，并说明差多少字节。
                if (n < 0) {
                    return Req(method, path, "", origin, reqHeaders, auth,
                        shortBody = true, gotBodyBytes = off, logTag = logTag)
                }
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        } else ""
        return Req(method, path, body, origin, reqHeaders, auth, logTag = logTag,
            relaySession = relaySession)
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
            // 读超时**不往外抛**：折成 [HEAD_TIMED_OUT] 哨兵，让调用方能把
            // "超时"与"EOF/超长"三者分开处理（三者症状完全不同，混起来
            // 就只能静默断连 —— 见 [HEAD_TIMED_OUT] 的注释）。
            // `soTimeout` 抛的是 `SocketTimeoutException`（IOException 子类）。
            val b = try {
                ins.read()
            } catch (_: java.net.SocketTimeoutException) {
                return HEAD_TIMED_OUT
            }
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
        val desc = if (loaded) LlmEngine.modelDesc().jsonQuoted() else "null"
        // ⚠ 旧的 KV / ctx 读数**语义保留 = slot 0**（设计文档 §9.2.10「只增不改」）：
        // `contextUsed()` / `lastReuseTokens` 等无 slot 版读的本来是
        // 「最后动过账本的那一路」，多 slot 下看起来完全正常、只是不是你以为的那一路。
        // 按 slot 的读数在下面三个新字段旁另给（`slot_active` 就是那个入口的形态）。
        //
        // ⚠ 但"写死 slot 0"这一条要**跟着 `slot_in_use` 走**（ISSUE #452 真机）：
        //   只用 HTTP 时请求落在 slot 1，于是 `ctx_used` 恒 0 —— 而它与
        //   `kv_slots[1].stop_wall: 2279` 同屏，读起来是"KV 里一枚都没有，位置却
        //   已经排到 2279"。两个数各自合法，合起来是一句假话。
        //   冷启动（一格都没跑过）仍退回 slot 0，与旧读数逐字节相同。
        val ctxSlot = if (!loaded) -1 else LlmEngine.kvStatsLiveSlot()
        val ctxUsed = when {
            !loaded -> 0
            ctxSlot >= 0 -> LlmEngine.contextUsed(ctxSlot)
            else -> LlmEngine.contextUsed()
        }
        // ⚠ `ctx_size` 报的是**总量**，不是 `contextSize()` 那一格。
        //
        // 这个字段的语义一直是"用户设的上下文长度"（早期版本 之前 `n_ctx` 只有一份，
        // 两者同值）。多 slot 之后 `contextSize()` 变成**每格**，若照抄过来，
        // `ctx=8192 / parallelN=2` 会在 /health 上显示 `ctx_size:4096` ——
        // 而用户填的 8192 完全生效了。他按这个读数把框里的 8192 改成 4096，
        // 总量就真掉一半（"我改了无效、被写死"的现场就是这么来的）。
        // 每格容量另有 `ctx_per_slot`（下面），两格并排读才分得清"总量"与"每格"。
        //
        // ⚠ 但"总量"要取**请求的那个**，不是"实际每格 × 份数"（ISSUE #392 真机）：
        //   每份拿到的是**用户填的那个数**（`n_ctx` 的语义就是每格，见 `load_model_impl`
        //   那段），再乘一遍份数等于把用户输入翻倍 —— 用户填 16128 + `parallelN=2`
        //   就会报成 `ctx_size: 32256`，而他从没填过这个数。
        //   两个数必须能分别读到，否则「切了两次」那条告警永远对得上（恒假）：
        //     · `ctx_size`   —— 请求的总量（= 每份请求值 × 份数，用户认的那个数）；
        //     · `ctx_actual` —— 实际的总量（= 每份实际 × 份数，平台钳过/建份失败会变）。
        //   请求值取不到（旧入口没记）时退回实际值 —— 那时只能报"它实际用了多少"。
        val ctxSize = if (!loaded) 0 else
            LlmEngine.requestedCtxTotal().takeIf { it > 0 } ?: LlmEngine.effectiveCtxTotal()
        // 实际生效的总量：与 `ctx_size` 不同 = 请求值没照做（平台钳 / 建份失败）。
        val ctxActual = if (loaded) LlmEngine.effectiveCtxTotal() else 0
        // ---- slot 三字段（设计文档 §7 / §8.3，**只增不改**）----
        //
        // 三个字段合起来回答一个问题：**「503 到底是因为没有 slot，还是因为模型没加载？」**
        // 今天 `busy` 与 `model_loaded` 分不开这两件事，而它们的处置完全相反
        //（等一会儿 vs 去加载模型）。
        //   · slots_total  —— 本次加载开出的 slot 数（= parallelN 实际落值；1 = 单并发）
        //   · slot_active  —— 各 slot 的占用来源数组（"http" / "app" / null），按下标对齐
        //   · slot_owner   —— 同上，但**共用**的那一格报两路（"app+http"，ISSUE #403 第三次现场）
        //
        // ⚠ 这里**不再报** `slots_busy` / `slots_shared`（#392 收口）：
        //   · `slots_busy` 是 `busy` 的整数版（`busy` 就是 `SlotScheduler.busy > 0`），
        //     再加一遍 `slot_active` 里非 null 的个数 —— 三格说一件事，读的人一定挑错的；
        //   · `slots_shared` 就是 `slot_owner` 里出现 `"+“` 的格数（同源 `sharedAt`），
        //     看见 `"app+http"` 数都数得出来。
        //   共线字段最有害：它们与真值**同真同假**，却在真机上长得更像结论。
        //
        // ⚠ `slots_total` 取 **SlotScheduler.total** 而不是 native 的 `slotCount()`：
        // 前者是调度器**判决后**的落值（含"除不尽回落单 slot"），后者是 native 侧的池子
        // 大小 —— 两者本应同值（native 自己也会再校验一次整除），但读数要取自
        // 「调度器怎么想的」，否则"调度器放了 2 个、池子只有 1 个"这种不一致会被报成一致。
        val slotsTotal = SlotScheduler.total
        val slotActiveJson = SlotScheduler.active().joinToString(",", "[", "]") { src ->
            when (src) {
                SlotScheduler.Source.HTTP -> "\"http\""
                SlotScheduler.Source.APP -> "\"app\""
                null -> "null"
            }
        }
        // ---- 「这一格到底是谁在写」（ISSUE #403 第三次现场）----
        //
        // `slot_active` 一格只放得下一个来源，于是**单格池里 App 与 HTTP 共用 slot 0**
        // 这一幕在它上面与"只有一路在跑"逐字相同。用户的判断（「kv 每次都被顶掉」）
        // 因此既拿不到证据、也没法被证伪。
        //
        // `slot_owner` 报**两路**（`"app+http"`）：
        //   · 里面出现 `+`             -> 两轮正压在同一份 ctx 上（KV 必然互相顶）；
        //   · 里面没有 `+`             -> KV 被顶的原因在别处，别再往这条上猜。
        // 判据来自 [SlotScheduler.sharedAt]（与 `writerOf` 同源），不是在这里另算一遍。
        val slotOwnerJson = SlotScheduler.active().mapIndexed { i, src ->
            when {
                src == null -> "null"
                SlotScheduler.sharedAt(i) -> "\"app+http\""
                src == SlotScheduler.Source.HTTP -> "\"http\""
                else -> "\"app\""
            }
        }.joinToString(",", "[", "]")
        // ---- 每格容量与「本路」读数（ISSUE #330 真机复盘第三回合）----
        //
        // 用户手上只有两个数：客户端设的 Max Context（总量）与日志里一句「复用 152」。
        // 而「152 到底是不是 system 段长度」「这一格到底有多大」「我该读到哪一格」
        // 三件事**都不能从这两个数推出来** —— 于是每一轮讨论都在猜。
        //
        // 这三格把猜测变成算术：
        //   · ctx_per_slot  —— 每格容量（= native 读回来的那一份，见下面那行的注释）。
        //     客户端要压到多少才不裁 = 它 − max_tokens，得先有这个数；
        //   · slot_in_use   —— 顶上那几个 `kv_*` 读的是哪一格。它与 `kv_slot` **必须同源**
        //     （都是 `LlmEngine.kvStatsLegacySlot()` = slot 0，§9.2.10 的语义归属）；
        //     报成"正在跑的那一格"就是让一个索引去配另一个数组 —— 只用 HTTP 时
        //     请求落在 slot 1、而那几个数来自 slot 0，读者会按索引去 `kv_slots[1]` 找，
        //     找不到就说"复用一次都没生效"。要读哪一格跑过，看下面 `kv_slots` 的 rounds；
        //   · kv_last_ctx   —— 上一轮**真正进 KV 的那一格**的容量（native 落值）。
        //     它与 ctx_per_slot 不同才说明「我以为的容量」与「它实际用的容量」分叉了；
        //     以前这里跨格取 max，于是这句告警**永远对得上**（恒假 = 从不告警）。
        //
        // 三格只增不改：`kv_slot` 原样；`ctx_size` 一直是"总量"（多 slot 之后
        // 必须取 每格 × 份数 才对得上它自己的语义，见上面那行）。
        // ⚠ 取 **native 的真值**（`llama_n_ctx()` = 建出来的那一份），不是
        //   `SlotScheduler.loadedCtxPerSlot`（ISSUE #426 第七回合现场）。
        //
        // 那个值有两个来源，而它们在"没加载成"的场次上会**一直停在判决值**：
        //   · `open()` 里按 `nCtx / slots` 算出来的愿望值；
        //   · `syncTo(nativeCtxPerSlot = nativeCtxSize())` 只在**加载路径**上改写它。
        // 于是"native 只开出一份 / 另一个池子被别处用着 / 上一轮加载的读数还挂着"
        // 这类场次里，`ctx_per_slot` 报的是 `ctx_size / 判决份数`，而 `ctx_size`
        // 又来自 native —— 两个数一个真一个愿望，乘起来还"自洽"（真机：
        // `ctx_size 12288 / slots_total 3 / ctx_per_slot 4096`，而设置页填的是 8192）。
        // 三个数各自都能自证，合起来却一个都对不上 —— 正是用户报的那句话。
        //
        // 现在判据只用一处真值（native 的 `llama_n_ctx()`），与 `ctx_actual` 同源，
        // 于是 `ctx_actual == ctx_per_slot × slots_total` **恒成立**，读的人可以当场乘一遍核对。
        val ctxPerSlot = if (!loaded) 0 else LlmEngine.contextSize()
        // 请求的每格容量：与 [ctxPerSlot] 不等 = 平台钳过 / 建份失败（"设置没照做"）。
        val ctxPerSlotReq = if (!loaded) 0 else LlmEngine.ctxRequestedPerSlot()
        // 请求的份数：与 [slotsTotal] 不等 = 除不尽回落 / 建份失败 / 上限钳位。
        val slotsReq = if (!loaded) 0 else LlmEngine.slotsRequested()
        // 「顶上那几个 kv_* 读的到底是哪一格」—— **必须与它们真正的来处同源**。
        //
        // 这里曾经报的是"有账本的那一格 / 正在跑的那一格"，而顶上那几个 `kv_*`
        // 一行都不是按它取的：它们读的是 `LlmEngine.lastXxxTokens`
        //（= `nativeKvCacheStats(...)`，native 侧写死 slot 0）。于是只用 HTTP 时
        // 它报 1、而顶上那些数其实来自 slot 0 —— 一个"索引"配错数组，读数越看越乱。
        //
        // ⚠ 现在两件事都收在一处（ISSUE #452 真机）：
        //   · `slotInUse` 仍是 [LlmEngine.kvStatsLegacySlot]（那五个字段的语义归属）；
        //   · 顶上那五个数**改成按它取**（见下面 `kvValid` 那一段的注释）。
        //   两边同源之后，`kv_slots[slot_in_use]` 与顶层必然逐格相等 ——
        //   真机那份"`slot_in_use: 0` 配 `kv_rounds: 0`、而 `kv_slots[1].rounds: 6`"
        //   的自相矛盾，从此不可能再拼出来。
        // ⚠ 取值从 `kvStatsLegacySlot()`（恒 0）换成 `kvStatsLiveSlot()`（最近跑过 prefill
        //   的那一格）：真机上"顶上五个 `kv_*` 恒 0、而 `kv_slots[1].rounds: 6`"就是
        //   这个索引配错数组的直接后果 —— 它把"这一路明明跑了 6 轮"报成"缓存一次没生效"。
        //   冷启动（一格都没跑过）仍退回 0，与旧读数逐字节相同。
        val slotInUse = if (!loaded) -1 else LlmEngine.kvStatsLiveSlot()
        // 上一轮真正进 KV 的那一格多大：**取有账本的那一格**（`slot_in_use` 的同一判据），
        // 不是跨格取最大 —— 最大会把"另一格上一轮的容量"报成"这一格用的容量"，
        // 于是那句「与现在报的 N 不一致」的告警永远对得上（恒假 = 永远不告警）。
        // 冷启动（一格都没跑过）报 0，不拿别人的容量充数。
        val lastCtx = if (!loaded || slotInUse < 0) 0 else {
            LlmEngine.kvAttributionOf(slotInUse)[3]
        }
        // `generating` 与 `busy` 分开报：`busy` 是 HTTP 侧「我不能接新请求」，
        // `generating` 是「引擎此刻真在跑」（含 App 内聊天）。只看 busy 会把
        // 「UI 正在生成、HTTP 请求会被 503」这种状态报成"闲着"，排查时对不上。
        // `/v1/abort` 打的就是 generating 那一轮，所以这个字段也是它的可观测面。
        // 取 `LlmEngine.isGenerating`（= 引擎侧那一份）而**不**加上 `busy`：
        // 加了之后 `generating` 就退化成 busy 的复制品，两字段再也分辨不出
        // 「HTTP 已受理但还没开始跑」与「真在跑」，那正是它存在的意义。
        val generating = LlmEngine.isGenerating
        // ---- prefill 窗口（ISSUE #450 第三次现场）----
        //
        // `generating` 覆盖的是「prefill 成功 -> 收尾」（它读的是取消注册表），而
        // **整段 prompt 的 prefill 就在 `startCompletion` 里面跑**（长 prompt 好几秒）。
        // 于是用户最可能按停止的那一刻，`/health` 报的是一句 `busy:true / generating:false`
        // —— 两格各自都对，合起来读却是"没人在跑"，而他明明在等第一个字。
        // 现场记录里那句「按停止什么都没有」就是从这来的：连"我到底打中了哪一段"
        // 都无从判断，只能靠猜。
        //
        // 判据只用**已有**的两张表，不新增状态机：
        //   · `busy`        = 调度器发过格子（从 `acquire` 那一刻成立，覆盖 prefill）；
        //   · `generating`  = 取消注册表里有这一轮（`startCompletion` 返回之后才有）。
        // 两者之差就是 prefill 窗口 —— 它是一个**推导出来的事实**，不是又一处要维护的真值。
        // `prefilling` 是它的名字：接口上回答"现在按停止会不会落空"。
        val prefilling = SlotScheduler.busy > 0 && !generating
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
        //   · kv_lcp_tokens     —— 上一轮与上一轮之前的**最长公共前缀** token 数
        //     （不设门槛）。它把"没命中"这个笼统结论拆开：lcp 大却 reuse=0 ->
        //     是门槛/容量拦的（差一点）；lcp 很小 -> 两轮 prompt 真的不一样
        //     （开了知识库时检索结果每轮都变，分叉点在 system 段里），
        //     那是**注入位置**的问题、要改代码，不是调参能解决的。
        //     没有它，这两种情形在 /health 与日志上完全同形。
        //   · frozen_ms         —— 最近一次"看门狗该醒却晚了"的超出量（毫秒，0 = 没检出过）；
        //   · frozen_count      —— 累计检出次数；
        //   · frozen_total_ms   —— 累计超出量（毫秒）；
        //   · restart_pending   —— 欠着一次"空闲后重启"（探活失败但当时有生成在跑）。
        //
        // 前三个是**后台冻结**唯一的可观测面（见 [lastSleepMs] 的长注释）：被冻期间
        // 探活不失败、连接不断、日志不写，解冻后"探活成功"与"从没被冻过"读数完全一样。
        // 没有它们，"自愈到底有没有生效"就只能靠用户描述；有了它们，客户端与设置页
        // 都能把"刚才那 N 秒是冻的，不是我网络坏了"说清楚 —— 从零症状变成可归因。
        // restart_pending 同理：它是"服务此刻**本**该重启、但为了不掐断在跑的流
        // 而故意欠着"的唯一读数，否则"没重启"与"重启判据没生效"长得一模一样。
        // kv_rounds 不是"锦上添花"：kv_cache_valid=false 同时覆盖两种**完全不同的**
        // 处境 —— 「从未跑过任何请求」（冷启动，正常）与「跑过、但账本已被作废」
        //（取消 / 换模型 / prompt 超长，是故障或取消）。没有它，这两者同形，
        // 读 /health 的人会把"缓存一次没生效"与"这次被取消了"读成同一件事。
        // 读法：kv_rounds==0 = 冷启动；kv_rounds>0 且 !kv_cache_valid = 本轮的账本已失效；
        // 多轮对话里第二个请求之后 kv_reuse_tokens 应显著大于 0；
        // kv_prefill_tokens 恒等于本轮「新增的那部分」，**绝不该是 0**
        //（0 是"复用把整段都当成已缓存"的症状：采样步会没有 logits ——
        //  kv_prefix.h 的约束 ④ 就是专门防它的）。
        // ⚠ 口径改过（ISSUE #452 真机）：这五个数**跟着 `slot_in_use` 走**，不再写死 slot 0。
        //
        // 以前它们走 `LlmEngine.lastReuseTokens` 之类（= `nativeKvCacheStats()`，native 侧
        // 写死 slot 0），而 `slot_in_use` 报的是"有账本的那一格"。两条路一错开，探活就自相矛盾：
        // 真机那份 `slot_in_use: 0` 配 `kv_slots[1].rounds: 6`，顶上 `kv_rounds: 0` ——
        // 读的人只能得出"缓存一次没生效"，而真相是**读错了格**（slot 1 明明跑了 6 轮）。
        //
        // 那 0 还有更坏的用处：它与 `ctx_used`（同样是 slot 0）一起被当成"这一轮谁送进来多长"，
        // 于是排查往"客户端送了 2350 tok"上走，而那是上一轮的残留 —— 方向整个跑偏。
        // 改成按 `slot_in_use` 取之后，顶上五个数与 `kv_slots[slot_in_use]` **逐格相等**，
        // 两处读数再也不可能互相打脸（这条被 `tools/run_health_json_guard.py` 钉住）。
        val kvValid = if (loaded && slotInUse >= 0) LlmEngine.kvCacheValidOf(slotInUse) else false
        val kvReuse = if (loaded && slotInUse >= 0) LlmEngine.lastReuseTokensOf(slotInUse) else 0
        val kvPrefill = if (loaded && slotInUse >= 0) LlmEngine.lastPrefillTokensOf(slotInUse) else 0
        val kvRounds = if (loaded && slotInUse >= 0) LlmEngine.kvRoundsOf(slotInUse) else 0
        val kvLcp = if (loaded && slotInUse >= 0) LlmEngine.lastLcpTokensOf(slotInUse) else 0
        // ---- KV 账本**按 slot**的读数（设计文档 §9.2.10 的下半，Issue #330 收口）----
        //
        // 上面那五个 `kv_*` 字段读的是 **slot 0**（§9.2.10「只增不改」）。
        // 单 slot 时那就是唯一一份，没问题；但 §11.7 起 HTTP 挑空位时**绕开 slot 0**
        //（保留给 App 内聊天），于是「只用 HTTP 客户端」时每轮请求都落在 slot 1，
        // 顶层读数恒为 `kv_reuse_tokens:0 / kv_rounds:0` —— 看起来就是
        // 「KV 复用一次都没生效」，而真相是**读错了路**（两路各有各的账本）。
        //
        //   · kv_slot  —— 顶层那五个 `kv_*` 读的是哪一格（= 0；**显式**报出来防误读）；
        //   · kv_slots —— 每格的 `{i,loaded,used,valid,reuse,prefill,rounds,lcp}`。
        //     读法：`slot_active` 说「这一轮谁在跑」，`kv_slots[那一格]` 说「它复用了多少」。
        //     只用 HTTP 时应看 `kv_slots[1]`，不是顶层那几个数。
        val kvSlot = LlmEngine.kvStatsLegacySlot()
        val kvSlotsJson = if (!loaded) "[]" else
            LlmEngine.kvStatsAllSlots().mapIndexed { i, a ->
                // ⚠ `a` 是 `nativeKvCacheStatsSlot(i)` 的四元组：**它才是这一格自己的读数**。
                //   下面那几格（账本 / 归属 / 属性）此前全是 `LlmEngine.xxxOf(i)` 现取的，
                //   而这个 `a` 拿进来以后一次都没用 —— 于是 `kv_slots[i]` 里
                //   `i` / `loaded` / `used` / `valid` / `reuse` / `prefill` / `rounds` / `lcp`
                //   这八个字段**一个都没写进 JSON**，整格只剩后半截属性。读的人按注释去读
                //   `kv_slots[1].reuse`，读到的是 null；而 null 与 0 长得一样，
                //   于是"这一格没跑过"与"这一格跑了但没命中"永远分不开。
                val valid = runCatching { LlmEngine.kvCacheValidOf(i) }.getOrDefault(false)
                // 归属读数：gen_cap / ctx 两个"上一轮预算落值"。
                //
                // ⚠ 这里**原先还有三格**：dropped_by_us / dropped_by_peer / lcp_reason
                // （外加一个把数组下标当字段发出去的 reason）。已删 —— ISSUE #330 里
                // 用户点名的那一处：
                //   · dropped_by_us   ⟺ prompt_trimmed > 0 —— 同真同假，一个数报两遍；
                //   · dropped_by_peer ⟺ 未命中（reuse == 0）—— 同真同假；
                //   · lcp_reason      是这两格背后的**数组下标**，现已恒 0；
                //     把下标当字段发出去，读的人还得自己查表。
                // 与已有读数共线的格子最有害：读的人一定会挑那个最像结论的
                //（"对端裁的"），而它在真机上恰恰是错的那一格。
                //
                // 留下的两个是**不共线**的读数：
                //   · gen_cap / ctx  -> "被掐断"的唯一原始依据，配合 finish_reason=length 读；
                //   · prompt_trimmed -> >0 才是"我们自己砍断了 KV 前缀"，0 = 与本轮复用无关。
                // `reuse_owner` 不进 /health：它只有"命中=1 / 未命中=0"两档，与 reuse 完全
                // 共线，多报一格只会让人去找一个不存在的第二种取值。
                // 这一格的**归属**读数（`{reuse_owner, reserved, last_gen_cap, last_ctx}`）：
                // `gen_cap` / `ctx` 是"这一轮被掐断"的唯一原始依据（配合
                // `finish_reason=length` 读）。以前它取了却**一格都没发**，"被掐断"
                // 在 `/health` 上就只能靠猜 —— 而它是用户拿 length 回来问"为什么"时
                // 唯一能当场回答的数。
                val attr = LlmEngine.kvAttributionOf(i)
                val genCap = atOr(attr, 2, -1)
                val slotCtx = atOr(attr, 3, -1)
                // 预算按**哪条线**判的：**这一条 seq** 的容量（ISSUE #426 真机
                // `decode rc!=0 at offset 2048`）。两路各一条 seq 之后
                // 「这一格有多大」与「位置号能排到几」是两个数（`n_ctx` vs `n_ctx / n_seq_max`），
                // 而预算与 prefill 各用一把时，失败现场只剩一行像是"KV 坏了"的日志。
                // 与 `ctx` 并排读：两者不等 = 这一轮的容量确实被 seq 切过；
                // 相等 = 单 seq（两把尺子同值，改动前的行为逐字节不变）。
                // 取值走**账本**的 `last_seq_ctx`（"上一轮真按它判的"那一份）——
                // 与 native 的 `kv_last_seq_ctx` 同一处落账，不是归属数组里的推导值。
                val ledgerNow = LlmEngine.kvLedgerOf(i)
                // ⚠ 只在**账本有效**时才有"上一轮按哪条线判的"这回事（ISSUE #452 真机）：
                //   真机那一屏是 `ctx: 4096` 配 `seq_ctx: 2` —— 2 是 `Session` 的初值，
                //   从来没被 `budget_seq_cap` 写过（账本作废 / 冷启动那一档）。报一个
                //   "看着像容量"的 2，读的人只会以为这一格被切成了两条 seq。
                //   0 = 没有这一格（与 `kv_last_ctx` / 冷启动同一条口径）。
                val seqCtx = if (valid) atOr(ledgerNow, 25, -1) else 0
                // 「这一轮为什么停」的三笔账（`gen_planned / n_rem / stop_wall`）：
                // `gen_cap` 只回答"预算愿意给多少"，而拦住生成的往往是位置
                // （`step_impl` 的 `n_used + 2 >= n_ctx`）。真机上那一轮是
                // `gen_cap 1024 / spare 693 / 实际 99` —— 只报 gen_cap 时
                // "调用方只要这么多" "预算削的" "位置不够" 三种处境逐字相同。
                val gs = LlmEngine.genStopOf(i)
                val genPlanned = atOr(gs, 0, -1)
                val genRem = atOr(gs, 1, -1)
                val genWall = atOr(gs, 2, -1)
                // ⚠ 原生数组的第四格是 `pos_gap`（`spare - stop_wall`），这里**不再发**
                //   （#392 收口）：它是同一段里 `spare` 与 `stop_wall` 的派生量，
                //   而那两个数就在同一屏上。派生量最坏的地方是它长得像结论 ——
                //   读的人会拿它当"位置被谁吃了"的答案，而它只是另两个数的减法。
                // 账本的构成与分叉点（ISSUE #330 真机复盘）：日志里"那一刀落在哪一截"
                // 的结论必须能在这里逐字段复核，否则它只是一句无法自证的话。
                //   · ledger_prompt / ledger_gen —— 上一轮进 KV 的 prompt 段与生成段；
                //   · fork_at = -1 表示没有分叉（命中 / 首次运行 / 整串都在前缀里）；
                //   · 纯数，没有需要转义的字符串。
                // ⚠ 这里**不再报** `fork_prev` / `fork_cur`（#392 收口）：三格
                //   （`fork_at` / `fork_prev` / `fork_cur`）说的是同一件事，而报告与
                //   `fork_why` 判据都只读 `fork_at`。两个"区间另一端"在真机上
                //   永远对不上人脑 —— 读的人一定挑错那个。
                val led = ledgerNow
                // 断点几何（见下面那四格的说明）：`fork_why` 那句结论与它**同源**，
                // 所以两处必须一起发 —— 只发结论、不发区间的话，读的人无从复核。
                val geo = LlmEngine.kvGeometryOf(i)
                // `ctx` 是**上一轮真正进 KV 的那一格**的容量（native 落值）—— 与顶层的
                // `ctx_per_slot` 并排读：两者不同就说明「我以为的容量」与「它实际用的
                // 容量」分叉了，而那正是"客户端按 ctx_per_slot 压了、仍每轮被裁"的解释。
                // 这一格自己的八格先写（见上面 `a` 那段：漏掉它们时整格只剩属性）。
                // 口径与顶层那五个 `kv_*` **同一份 getter**（`nativeKvCacheStatsSlot`），
                // 所以 `kv_slots[kv_slot]` 必然与顶层那几个数逐格相等 —— 不相等就说明
                // 又有人在这里另算了一份。
                //
                // ⚠ 取数**先落成局部变量**，`$` 插值里只留名字（ISSUE #392 真机）：
                //   原先 `used` 那一格写的是「`$` + `{a.getOrElse(0)` + 一个**块**
                //   `{ 0 }` + `}`」—— 也就是 **`$` 插值里套了一对花括号**。Kotlin 认
                //   这一行（编过、跑得通），但手拼 JSON 时块结尾那个 `}` 是多出来的
                //   **字面量**，发出去的正文就成了 `"used":<表达式>}`：
                //   整份响应自此非法，报的是 `Unterminated array at character 392`。
                //   探活只能报"服务端拼错了"，而病根是**插值里写了带块的表达式** ——
                //   凡是 `$` 插值里出现 `{`/`}`，拼 JSON 这条路就不成立。所以这里换
                //   局部变量，判据钉在 `tools/run_health_json_guard.py`（`$` 插值里
                //   再出现花括号就红）。
                //
                // ⚠ 每格**先拼成一段完整对象文本**（两个括号都自己写出来）
                //   （ISSUE #392 第二回）：上一版这里只拼了 `"i":0,"loaded":1,...` 这样的
                //   **属性串**，外层 `["i":0,...]` 直接成了数组字面量 —— 每格的两个对象括号
                //   **根本没写**。真机报的是 `Unterminated array at character 403`，而
                //   403 落在 `"used"` 上（不是病根）：解析器从 `[` 起就把 `"i"` 当元素，
                //   一路往后找结束符。上一版守卫没拦住，因为它判"每格里有不有裸花括号"
                //   时**只看右括号在不在、不看在不在左括号** —— 两个都缺时它是绿的。
                //
                //   所以这版把两件事绑在一起、也钉在守卫里：
                //     · 每格正文**必须自带** `{` 与 `}`（缺一不可，且在两端）；
                //     · 正文里任何字符串值都走 `jsonQuoted()`，手拼 JSON 不许裸插原文
                //      （`fork_why` 那句里有用户的历史摘要，一个 `"` 就能把整份打断）。
                val loadedFlag = if (a.isNotEmpty()) 1 else 0
                // `valid` 是**这一格**报的有效性；但真机上出现过"KV 里有账本、
                // kv_cache_valid 却是 false"的场次（#392 那份：`loaded`=1、`valid`=0）。
                // 两者必须能同屏，别拿其中一个盖掉另一个：
                //   · loaded  —— 这一格解出来了（有读数）；
                //   · valid   —— 这一格的 KV 缓存当下还能不能复用。
                val validFlag = if (valid) 1 else 0
                // `a` 是 [LlmEngine.kvStatsAllSlots] 的一格，顺序
                // `{used, reuse, prefill, rounds, lcp}`（见该函数的文档），**不是**顶层
                // 那五个 `kv_*` 的顺序（`{reuse, prefill, rounds, lcp}`）—— 两套差一格：
                //   0 = 已入 KV 的 token 数（contextUsed(i)），1 = 上一轮复用的枚数，
                //   2 = 上一轮新算的枚数（prefill），3 = 账本里的轮数，4 = 上一轮公共前缀。
                // ⚠ 两版各自错过一位，症状不同、都**不会**被"缺字段"判据抓住：
                //   · 都读 `a[0]`（上一版）—— `reuse` 恒等于 `used`，`lcp` 取到 rounds；
                //     真机 `reuse:1600 / prefill:28` 实际是复用 28、新算 901；
                //   · `used` 改走 `contextUsed(i)`（另一版）—— 值本身对，但**这一格
                //     自己的四元组又没人读了**，`used` 与其余四格来源分叉。
                // 这版统一按 `a` 自己的顺序取：一格一源，"这一格 KV 里装了多少"与
                // "复用了多少"是两件事，各取各的格。
                val used = at(a, 0)
                val reuse = at(a, 1)
                val prefill = at(a, 2)
                val rounds = at(a, 3)
                val lcp = at(a, 4)
                // 「本路」读数：`used` 答"这一格装了多少"，这两格答"我这一路占了多少"。
                // 两路共格时 `used > ctx` 是真事（差值是另一路占的位置），只报 `used`
                // 时它长得像"上下文超了"。拿不到就按 `used` 兜底 —— 单路时它们同一个数。
                val usedSelf = at(a, 5).let { if (it < 0) used else it }
                val roundUsedSelf = at(a, 6).let { if (it < 0) usedSelf else it }
                // 上一轮是谁在这个槽位上跑的：**属性**数组的 `a[6]`，
                // 数组是 `int[8] = {reuse_owner, reserved, gen_cap, ctx, used_self,
                // round_used_self, writer, seq_left}`（倒二格 writer、末格 `seq_left`；
                // 预算按哪条线判的走账本那一格 `last_seq_ctx`，见 [kvLedgerOf]）。
                // ⚠ 别拿账本 `led[25]` 顶它：两个数组里都有一格叫 writer，但不是同一件事 ——
                //   前者答"上一轮这一格归谁"，后者答"上一轮写进账本的是谁"，且被
                //   `kv_valid` 门着（账本作废时它照旧留着旧值）。名字按仓库既有约定：
                //   这一格是 `round_writer`，账本那一格是 `ledger_writer`。
                val roundWriter = at(a, 6)
                // 本路那条 seq **还剩多少位置**（ISSUE #426）。容量取库的真值、已用取本路实况，
                // 由 native 一处相减 —— 让读的人自己拿 `ctx_size / 2` 去减，算出来的数一旦
                // 与 native 的判据（`seq_ctx_limit`）分叉，症状只是某轮突然 400。
                // `-1` = 这一格没有读数（未加载 / 越界），`< 0` 且不是 -1 = 两路已经挤爆这一格。
                val seqLeft = at(a, 7)
                // 账本那半截走 `at(led, n)`：越界报 -1（"没有这一格"），不抛、也不吞成 0
                // —— 0 是合法取值，吞成 0 会让"索引越界"伪装成"读数正常"。
                val start = at(led, 12)
                val startPrev = atOr(led, 13, -1)
                val startNew = at(led, 19)
                val aligned = if (at(led, 17) == 1) 1 else 0
                val alignReject = at(led, 18)
                val promptReal = at(led, 14)
                val promptEst = at(led, 15)
                // ── 每一格的字段（顺序即读法，加字段往这段里并、别另起一段）──
                //   · gen_cap / ctx —— "被掐断"的唯一原始依据，配合 finish_reason=length 读；
                //   · prompt_trimmed —— "复用没了是不是我砍的"：>0 就是我们自己那一刀。
                //     gen_trimmed 单列：只削**生成上限**对 KV 零影响，两者合成一个数时
                //     "话变短了"会被读成"历史被裁过"，处置正好相反。
                //   · head_same / head_cmp / fork_in_head —— `fork_why` 那句话的**依据**：
                //     结论"落在上一轮回复段开头"只在断点之前逐 token 相等时才成立，
                //     而真机上它出现在"开头就已经不一样"的场次里（方向正好相反）。
                //   · start / start_prev —— 起点有没有漂：单看任何一个都正常，一减才知道。
                //   · aligned / align_reject —— "接上了没有 / 想接而让掉多少空间"（led[17]/[18]）。
                //     `aligned=0` 且 `align_reject>0` 才是"调大 ctx 就接得上"。
                //   · inject_chars 是**字符数**，diff_extra_len 是**token 数**（两个坐标系）：
                //     只有后者能与 fork_at 相减，判"断点压在注入块头上"要看它。
                //   · prompt_real / prompt_est —— 差值才是"预算说装得下、native 却砍了一刀"那条窄带。
                //   · diff_extra* / diff_prev_head_at —— 断点两侧的几何，四格全是下标，没有推断。
                //     `diff_extra_len` 与 `diff_extra` 是同一区间**两端各报一个下标**（合起来
                //     就是 `ctx`）—— 留两格是因为"这一刀落在哪一截"的判据按两端各取一次
                //     （见 native 的 `kv_diff_where`）；派生的那个数（`ctx - diff_extra`）
                //     读的人自己减即可。`prev_prefix_in_cur` 与它们同源（几何五格），
                //     单独拎出来是因为它分的是"我们扔的 / 对端换的"这两件相反的事。
                // ⚠ `start` / `start_prev` 那两格**不再报** `rediscover`：它从四个数里推断
                //   成因，真机上几档互相覆盖，用户照着改了五轮客户端。读数能被数据直接回答，
                //   成因不能；不能的就别给它名字。
                //
                // ⚠ 这里**不再报** `rediscover`（"起点为什么动了"那五档）、`continue_kept`、
                //   `prev_lcp`、`dropped_by_us/peer`、`lcp_reason`、`reuse_owner`：
                //   前两个与已有读数**共线**（同真同假），后几个是判据的中间量。
                //   共线的格子最有害 —— 读的人一定挑那个最像结论的，而它在真机上是错的那一格。
                // 断点那两枚 token 与「上一轮那段在本轮还找得到几枚」（ISSUE #452 真机）。
                // 四格**不参与任何判据**，只回答「为什么每轮都只接上开头 300 枚」：
                //   · `fork_prev_tok` / `fork_cur_tok` —— 断点那一枚两侧分别是什么（-1 = 没有）；
                //   · `elided_len`      —— 上一轮 `[0, lcp)` 这段在本轮还找得到几枚（-1 = 没法量）：
                //     等于 `lcp` = 那一段照旧在、断点在它之后；小于 `lcp` = 上一轮那段**被换掉了**；
                //   · `head_ledger_len` —— 上一轮存的开头有多长，与 `head_cmp` 成对读：
                //     `head_cmp < head_ledger_len` = 本轮更短、上一轮的开头没比完，
                //     那时「断点在更后面」不成立。
                // 两枚 id 并排，"内容变了"与"这一轮少喂了一段"才分得开 —— 这两者的处置
                // 完全相反（前者查客户端回传的历史，后者查我们自己的裁剪）。
                val peek = LlmEngine.kvForkPeekOf(i)
                val body =
                    """"i":$i,"loaded":$loadedFlag,"valid":$validFlag,""" +
                    """"used":$used,"reuse":$reuse,""" +
                    // gen_cap / ctx 在变长字段段**之前**（合并 main 时漏过一处：
                    // 两格取值都还在、也在注释里点了名，唯独没人拼进正文 ——
                    // 与 #392 同一类病：算对了不透出，等于没有）。
                    """"gen_cap":$genCap,"ctx":$slotCtx,"seq_ctx":$seqCtx,""" +
                    // 停在哪条线上的三笔账：`min(n_rem, stop_wall)` 就是这一轮
                    // 实际能生成的枚数，谁最小谁就是原因（见 LlmEngine.genStopOf）。
                    """"gen_planned":$genPlanned,"n_rem":$genRem,"stop_wall":$genWall,""" +
                    """"prefill":$prefill,"rounds":$rounds,"lcp":$lcp,""" +
                    """"ledger_prompt":${at(led, 0)},"ledger_gen":${at(led, 1)},""" +
                    """"fork_at":${at(led, 3)},""" +
                    """"prompt_trimmed":${at(led, 6)},"gen_trimmed":${at(led, 7)},""" +
                    // 丢完之后的**余量**（0 = 尾巴顶在边界上）。判据不是"丢没丢"：
                    // `prompt_trimmed > 0` 在「丢一次」与「每轮都丢」两种处境下都成立，
                    // 而只有这一格说得清下一轮接不接得上（真机那场「越过某个长度之后
                    // 每轮都在重算」就是 spare 恒 0）。
                    """"spare":${at(led, 24)},""" +
                    // 水位三格（ISSUE #426 方案 B）：
                    //   · `ctx_watermark`  —— 用户设的那一档（跨轮不变，是"多久丢一次"的解释变量）；
                    //   · `drop_room`      —— 丢完之后离**水位**还剩多少（0 = 下一轮再长就再丢）；
                    //   · `drop_overshoot` —— 比"只丢缺口"多丢了多少（主动忘掉的记忆，换下一轮不丢）。
                    // ⚠ 原来这里报的是 `wm_dropped`（"这一轮是按水位丢的"）：判据在能走到
                    //   的地方恒等于 `prompt_trimmed > 0`，与它共线 —— 一格信息量都没有。
                    //   而 `spare` 量的是**整格**：水位 85% 那轮它还有几百枚、`drop_room` 已是 0，
                    //   只报 `spare` 时"还有余量"与"下一轮又要丢"同时为真，读的人只会得出前者。
                    """"ctx_watermark":${at(led, 26)},"drop_room":${at(led, 27)},"drop_overshoot":${at(led, 28)},""" +
                    """"head_same":${at(led, 8)},"head_cmp":${at(led, 9)},"fork_in_head":${at(led, 10)},""" +
                    """"start":$start,"start_prev":$startPrev,""" +
                    """"start_new":$startNew,""" +
                    """"aligned":$aligned,""" +
                    """"align_reject":$alignReject,""" +
                    """"inject_chars":${LlmEngine.injectCharsOf(i)},""" +
                    // `est_scale` 紧挨着 `prompt_est`：那一格是**乘过它**之后的数，
                    // 差值的方向（高估白丢 / 低估砍了一刀）只有配上系数才解得开
                    // （ISSUE #452 ⑤）。它取自 `local_ctx` 的同一个来源 —— 判据自己报的，
                    // 不是这里再问一次引擎（两处各取一次就会拼出从未同时成立的组合）。
                    """"prompt_real":$promptReal,"prompt_est":$promptEst,""" +
                    """"est_scale":${at(led, 30)},""" +
                    """"diff_extra":${at(geo, 0)},"diff_extra_len":${at(geo, 1)},""" +
                    """"diff_extra_prev":${at(geo, 2)},"diff_prev_head_at":${at(geo, 3)},""" +
                    // 上一轮**整段**账本在本轮逐枚相同几枚（ISSUE #503，账本第 43 格）。
                    // 与 `diff_prev_head_at` 成对：丢过历史之后本轮就是上一轮的前缀，
                    // 那一格必然 -1（上一轮开头那 ≤512 枚在本轮一枚也放不下），于是
                    // `fork_why` 会说「两轮喂进去的不是同一份对话」—— 而真相是我们自己
                    // 扔了开头。这一格等于 `prompt_real` 时，那句话就该反过来读。
                    """"prev_prefix_in_cur":${at(geo, 4)},""" +
                    // 覆盖范围与起点两格（ISSUE #503 真机第二轮）。
                    //   · `prev_head_span` —— 那次比对**真的比过**账本开头多少枚。
                    //     `head_cmp` 是"找了几次"的**步数**（上一轮开头整段还在时它等于
                    //     首次出现的位置），拿它当覆盖范围会把"整段都比过"读成"只比了一小截"；
                    //   · `tok_start` —— 本轮从第几枚起喂（**整段 prompt** 的坐标系，丢头之前）。
                    //     与 `start_prev` 并排才读得出东西：两者不等 = 起点每轮往后滑，
                    //     那正是「每轮全量 prefill」的自锁；单看任何一个都正常。
                    """"prev_head_span":${LlmEngine.kvPrevHeadSpanOf(i)},""" +
                    """"tok_start":${LlmEngine.kvTokStartOf(i)},""" +
                    """"fork_prev_tok":${at(peek, 0)},"fork_cur_tok":${at(peek, 1)},""" +
                    """"elided_len":${at(peek, 2)},"head_ledger_len":${at(peek, 3)},""" +
                    // 断点的**种类**（ISSUE #503 真机第一屏）：`0` 内容在中间断掉 /
                    // `1` 本轮整段都是上一轮账本的前缀（断点在本轮末尾，不是内容变了）。
                    // 只读 `fork_at` 时两者可能同值（都等于本轮长度），而处置相反：
                    // 前者要查模板与注入，后者本来就该复用 `prompt_real - 1` 枚。
                    """"fork_at_kind":${LlmEngine.kvForkKindOf(i)},""" +
                    """"fork_why":${LlmEngine.kvForkWhyOf(i).jsonQuoted()},""" +
                    """"ledger_mismatch":${LlmEngine.ledgerMismatchOf(i)},""" +
                    // 上一轮**是谁**写进这一格的（0 未知 / 1 App / 2 HTTP，ISSUE #403 第三次现场）。
                    // `writer` 与上一轮不同 = 换了路 —— 单格池里 App 与 HTTP 轮替占同一格时，
                    // 换路那一轮逐 token 公共前缀只到 system 段就断（两路 prompt 构造不同），
                    // 于是"每轮都整段重算"是数学，不是缓存坏了。这句话以前无从判定：
                    // 复用在 /health 上与"缓存真坏了"逐字相同。
                    """"writer":${LlmEngine.kvWriterOf(i)},""" +
                    // 一格里两条 seq 之后的「本路」读数（见 usedSelf 那段说明）：
                    // `used_self` 与顶上的 `used` 并存 —— 前者答"我这一路"，后者答"这一格"。
                    // 两路都在用时 `used > ctx` 是真事，别读成故障；差值是另一路占的位置。
                    """"used_self":$usedSelf,"round_used_self":$roundUsedSelf,"seq_left":$seqLeft,""" +
                    // 两个 writer 各报各的：`round_writer` 是**属性**那格的（上一轮这一格
                    // 归谁，不经 `kv_valid`），`ledger_writer` 是**账本**第 25 格（账本里的
                    // 那一位，`kv_valid=0` 时账本说话不算数）。合成一个字段时"清完缓存
                    // 之后"读到的是上一本账，而它看起来完全正常。
                    """"round_writer":$roundWriter,""" +
                    """"ledger_writer":${at(led, 25)}"""
                "{" + body + "}"

            }.joinToString(",", "[", "]")
        // 冻结痕迹（A3）。注意它们与 `loaded` 无关：没加载模型时端口照样可能被冻。
        val frozenMs = lastFreezeMs
        val frozenCount = freezeCount
        val frozenTotal = freezeTotalMs
        val restarting = restartPending.get()
        // 嵌入模型（双常驻）的两个可观测字段。**这是它唯一的读取面**：
        // 双常驻把模型从一份变成两份，而 `/health` 此前只报得出对话那一份 ——
        // 于是"嵌入模型到底装上没有"在探活里完全看不见，用户只能翻日志猜，
        // 也就无法区分"嵌入没加载（检索必然全空）"与"检索没命中（正常）"。
        //   · embed_loaded —— 嵌入那份是否驻留（**与 model_loaded 无关**，
        //     两者的组合正是双常驻的定义：可以只有嵌入、可以只有对话、可以都有）；
        //   · embed_model  —— 嵌入模型的短描述，空串 = 未加载。取的是**嵌入**
        //     那一份的描述，不是对话那份的复制品；
        //   · embed_dim    —— 向量维度，0 = 未加载。它是索引指纹的一部分：
        //     维度变了说明换了嵌入模型，旧索引必须作废。
        val embedLoaded = LlmEngine.hasEmbedModel
        val embedDesc = if (embedLoaded) LlmEngine.embedModelDesc().jsonQuoted() else "\"\""
        val embedDim = if (embedLoaded) LlmEngine.embedDim else 0
        //   · embed_pooling / embed_out_dim —— 嵌入模型声明的池化方式与输出侧维度。
        //     两者**已经是索引指纹的一部分**（`FpInput.pooling` / `FpInput.embedOutDim`），
        //     于是"索引说池化方式变了"这句话在 /health 上一个数都没有 —— 对账只能翻日志。
        //     它们是"值相等但对象不是同一个"那一族事故的直接判据：
        //       · 池化方式变了（CLS / LAST / MEAN）-> 同一份文本、同一个维度，向量完全不同；
        //       · out_dim 与 dim 不等 -> 库按输出侧维度分配 buffer、读取按 n_embd 步长走，
        //         "读一行"读到相邻 buffer（含未初始化的 NaN），症状是"某些条归一化失败、时好时坏"。
        //     `-1` = 取不到（未加载 / 旧版本库）；与 `embed_dim` 的 `0` 分开，不合并。
        val embedPooling = if (embedLoaded) LlmEngine.embedPooling else -1
        val embedOutDim = if (embedLoaded) LlmEngine.embedOutDim else 0
        // 嵌入**合批**的六个可观测字段（ISSUE #269 第 1 步：先量）。
        // 合批是**纯加速**：生效与不生效在协议上完全看不出来（同样的 200、
        // 同样的向量、同样的检索结果）。没有这几个数，"合批到底有没有在工作"
        // 就只能靠翻日志推断 —— 而它与 `kv_cache` 那三个字段是同一个病：
        // 纯加速的东西没有可观测面，回归里静默失效而无人发现。
        //   · embed_batch_batches    —— 走了合批的**批次**数；
        //   · embed_batch_entries    —— 合批**真正编掉的条数**（它们才是省下的前向）；
        //   · embed_batch_fallback_batches —— 回落逐条的批次数；
        //   · embed_batch_fallback_entries —— 回落逐条的条数；
        //   · embed_batch_ms          —— 合批花掉的毫秒（只算合批那部分）；
        //   · embed_batch_size        —— **当前配置的**批大小（设置值，默认 8）。
        // 加上 `_live` 那几格才是本轮第 2 步（可调）的对外面："我设了 8，它到底跑了几"
        // 必须有一个打探就能读到的答案。
        //
        // ⚠ 这里**不再报**四个 `_set` 布尔（`embed_batch_size_set` /
        //   `embed_ctx_per_seq_set` / `embed_seq_max_set` / `embed_gate_set`，#392 收口）：
        //   它们的语义是"用户碰过没有"，而"到底生效没有"由同组的 `_live` 回答
        //   （`_live` 为 0 = 没加载）。两组一起读只会看到八组数字互相印证，
        //   而"没碰过、按默认值跑"与"碰过、设成默认值"在处置上没有任何差别。
        //
        // ── 嵌入 ctx 那一格（0.9.212）──
        // "内存占用为啥高"的答案在这一格，不在批大小那格：批大小只决定"一次塞几条"，
        // 而 ctx 预算决定 KV / output / compute buffer 三项分配（还有它推出来的
        // 两道门 `n_batch`/`n_ubatch`）。只报批大小的话，用户把批从 8 调到 1
        // 之后会以为内存该降了 —— 而它一点都不会降。
        //   · embed_ctx_per_seq      —— **当前配置的**每条预算（设置值，默认 [LlmEngine.kEmbedCtxDefault]）。
        // 报的是**配置值**而不是 native 的落值：native 那个只有加载之后才有，
        // 而这一格要在加载**之前**就能对账（"我填了 1024，它到底有没有用上"）。
        val bs = EmbedBatchStats.snapshot()
        val wantBatch = EmbedBatchPref.get(ragPrefs())
        val wantCtx = EmbedCtxPref.get(ragPrefs())
        // ── 设置值 vs **生效值**（0.9.241）──────────────────────────────────
        // native 会把每条预算钳到 `kEmbedCtxMax / kEmbedBatchRows`（0.9.241 起
        // 2048/4 = 512）。只报设置值时，"填 1024、实际按 512 跑"与"填 1024、
        // 真的用上 1024"在读数上逐字相同 —— 那就是"这格没用"的观感来源。
        // 两个数并排，差值自己会说话（没加载时生效值是 0，不是"0 位置"）。
        val ctxPerSeqLive = LlmEngine.embedCtxPerSeq
        // ── 0.9.242：三格「调低省内存」的旋钮（条数 / 两道门 / ctx 总量）─────────
        // 每一格都报**两个数**（配置值 + 生效值），理由与 `embed_ctx_per_seq` 那对
        // 完全相同：native 会做构造性收口（`perSeqMax = min(门, 总量) / 条数`），
        // 所以"填了 2、实际按 4 跑"与"填了 2、真的用上 2"必须分得开。
        //   · embed_seq_max / _live —— 每批条数上限（= n_seq_max）；
        //   · embed_gate / _live    —— 两道门（n_batch/n_ubatch 取小）；
        //   · embed_ctx_total_live  —— ctx 总量（生效值 = 库的 n_ctx）。
        //     ⚠ 这里**不再报** `embed_ctx_total`（#392 收口）：它恒等于编译期常量
        //       `kEmbedCtxMax`，不读 prefs、不读 native —— 一个从不变化、与实际配置
        //       无关的常量占一格，而旁边的 `_live` 才是真值。
        // ⚠ 只报配置值时，"我调到 1 条了，内存怎么没怎么降"无从回答 ——
        //   真值在 `_live` 那一列（省内存要动 ctx 总量，条数只是它的一个乘数）。
        val wantRows = EmbedRowsPref.get(ragPrefs())
        val wantGate = EmbedGatePref.get(ragPrefs())
        val seqMaxLive = LlmEngine.embedSeqMax
        val gateLive = LlmEngine.embedGate
        // ── 建索引时用的批大小（ISSUE #269 第 5 步：把"库认的那个"也报出来）────
        // 这一格补的是**只有一半读数**留下的洞：`embed_batch_size` 是**当前配置**
        // 的那个，而"建这份索引时用的是几"此前**哪里都读不到**。于是用户把批大小
        // 8 改成 1 之后，库**静默不可检索**（`g` 是必比字段，8 ≠ 1 -> 指纹不符 ->
        // `Load.none`），而他能拿到的只有一句"索引不可用：合批每批条数 变了"，
        // **机制一个字都不说**（因为两个数里只有一个在外面）。
        // 现在两个数摆在一起，那句话就有了答案：索引里是 8、当前是 1 -> 改回来。
        //
        // ⚠ 读的是**当前选中的那个库**（[RagPrefs.currentKb]）的索引 —— `g` 是
        //   每份索引自己开头写的，没有"全局的那个"。所以还要把**库名**也报出来
        //   （`embed_batch_size_index_kb`），否则用户看到 -1 无从知道是"这个库没索引"
        //   还是"读的是别的库"。
        //   -1 = 当前库没有索引 / 读不出来（旧格式 / 文件坏）—— 与 `get` 的语义
        //   （0 = 没设过）不同，因为这里是"库里的那个数"，没有"没设过"这一说。
        val currentKbName = RagPrefs.currentKb(ragPrefs())
        val currentKbNameJson = RagProtocol.kbJson(currentKbName)
        val indexBatch = if (currentKbName.isNotEmpty())
            RagIndexStore.batchOfIndex(ragPrefs(), currentKbName) else -1
        // ── 库认的**块宽**（与上面那条"库认的批大小"同一族，同一个坑）──────────
        // 块宽（`t`）也是**必比字段**：改一次它，整份索引对不上、库静默不可检索。
        // 而它能读到的只有配置值 —— 建这份索引时用的是几，此前哪里都读不到。
        // 两个数摆在一起才答得了"我改了块宽，为什么库不能用了"。
        // ⚠ 与 `embed_batch_size_index` 同一条口径：-1 = 当前库没有索引 / 读不出来。
        val indexChunk = if (currentKbName.isNotEmpty())
            RagIndexStore.targetCharsOfIndex(ragPrefs(), currentKbName) else -1
        // ── 逐位比对（ISSUE #269 第 5 步：把"g 是不是纯加速旋钮"量出来）────
        // 这三个数回答的是**唯一决定"改批大小要不要重建索引"**的那条断言。
        // 不报的话，用户只能看日志（会滚），而结论是**跨版本**要对的一条
        // （"0.9.218 那条待验断言到底成立没有"）—— 打探能读到的才叫读数。
        //   · embed_parity_rows      —— 参与比对的条数（0 = 还没跑过，**不是**"相同"）；
        //   · embed_parity_identical —— 其中逐位相同的条数；
        //   · embed_parity_different —— 其中**真的不同**的条数（0 = 跑过且没有不同）；
        //   · embed_parity_verdict   —— 三档结论（unknown/identical/close/different/missing）；
        //   · embed_parity_repeat_measured          —— 这次做没做「逐条重复」对照；
        //   · embed_parity_repeat_not_identical     —— 逐条重复里不是逐位相同的条数；
        //   · embed_parity_repeat_batched_measured  —— 这次做没做「合批重复」对照；
        //   · embed_parity_repeat_batched_not_identical —— 合批重复里不是逐位相同的条数；
        //   · embed_parity_group     —— 这份读数取自**哪一组样本**（长短悬殊 / 等长）。
        // ⚠ `embed_parity_group` 不是可省的标签：两组样本回答**两个不同的问题**，
        //   而它们的方向相反 —— 等长组按构造就是稳的（唯一变量只剩批布局），
        //   长短悬殊组才是"换批大小会不会改答案"要问的那组。只报数字不报来源时，
        //   一行"逐位相同 4/4 —— 批大小是纯加速旋钮"在两组下**长得一模一样**，
        //   而读者会把它读成"长短悬殊也稳"（真机现场正是如此）。落进 `/health`
        //   的是**长短悬殊**那组（见 `probeEmbedBatchParity` 的落读数处）。
        //
        // ── 「检索对照」（`embed_retrieve_*`）：与上面那组**配对**的另一半 ──────
        // ⚠ 上面那组量的是**向量**（"逐位相同吗"）。而"改批大小要不要重建索引"
        // 要问的是**另一句**："换批大小重建后，**搜出来的东西**还是不是同几条"。
        // 两者之间**没有蕴含关系** —— 向量差 4e-03 不代表答案会变，向量逐位相同
        // 也不代表答案不变（索引里还有正文行序 / 切分 / 分数比较）。
        // 不报这组时，用户会拿"向量有差异"直接推出"检索结果也会不一样"，
        // 而那是**推出来的**，不是量出来的（本仓库的规矩是判据落在具体一格上）。
        //   · embed_retrieve_measured        —— 这一格**测没测**（false 时下面三个都是 0）；
        //   · embed_retrieve_queries         —— 参与对照、且**两侧都搜到了东西**的查询条数；
        //   · embed_retrieve_answer_changed  —— 命中**集合**变了的查询条数（>0 = `g` 不能放行）；
        //   · embed_retrieve_order_changed   —— 集合相同、只是名次变了的条数（打平处换序，另一件事）；
        //   · embed_retrieve_batch_base / embed_retrieve_batch_other —— 比的是哪两种批大小；
        //   · embed_retrieve_topk            —— 取前几名（口径：与用户实际用的同一档）。
        // ⚠「测没测」必须单独一格：没测时 `answer_changed` 也是 0 —— 而"没测"
        // 该去测、"集合全同"才能收窄结论，方向相反（与上面那两格同一条理由）。
        // 这四格是"差异来自哪里"的那一格：只跑一次时「批布局改语义」与
        // 「编码本身不确定」混在一个读数里，而它们的下一步相反（前者收窄批大小
        // 结论，后者先别谈批大小）—— 见 `probeEmbedBatchParity`。
        // ⚠ 「重复」是**两格**（逐条 / 合批），不是一个布尔：合批自己复现不了
        // 自己，与"合批和逐条不同"是两件事（前者先查合批，后者才谈 `g` 收不收窄）。
        // `different` 的条数必须单独报：真机首轮读数是「6 条里 1 条不同」，
        // 而"1/6"（某几条踩到边界）与"6/6"（合批普遍改语义）的下一步相反 ——
        // 只有"逐位相同 N 条"时这两个读数长得一模一样（见 HealthCheck.Result）。
        // 用**字符串**而不是布尔：三档里"不是逐位相同"与"其实没测过"是两件事，
        // 一个布尔会把"没测过"报成 false -> 被读成"验过了，结论是不相同"。
        val pari = EmbedParity.Last.snapshot()
        val retr = EmbedRetrieveParity.Last.snapshot()
        val parityVerdictJson = RagProtocol.kbJson(
            if (pari.verdict == EmbedParity.Last.Verdict.UNKNOWN) "" else pari.verdict.name.lowercase())
        // 这份读数取自哪一组样本（`长短悬殊` / `等长`，空串 = 还没跑过）。
        // ⚠ 它不是可省的标签，而是这行读数**能不能被解释**的前提：两组样本
        //   回答两个不同的问题（等长组按构造必然稳、长短悬殊组才是"会不会变"），
        //   而只报数字时"逐位相同 4/4"在两组下同形（见 `pari` 上方那段说明）。
        val parityGroupJson = RagProtocol.kbJson(pari.group)
        // 远端知识库（RAG 阶段 3）的三个可观测字段。
        // 它们与 `embed_*` 是**两组独立**的读数（用户口径 4：`/v1/embeddings` 与
        // RAG 开关完全解耦）：嵌入模型在不在，与"远端会不会注入"是两件事。
        // 组合起来才够归因：
        //   · rag_remote_enabled=true 且 embed_loaded=false -> 远端开了但注入必然为空（缺嵌入模型）；
        //   · 两者都 true 而 rag_remote_injected=0 -> 检索跑了但没命中（调阈值/加内容），
        //     与上一条的处置完全不同 —— 没有 `rag_remote_injected` 时两者同形。
        //   · `rag_remote_enabled` —— 远端那条路当下开着没有（调用方发请求前就能读）；
        //   · `rag_remote_kb`       —— 远端用的是哪个库（空串 = 没配）。两个库名分开存之后，
        //     "我配的和远端用的到底是不是同一个"必然会被问到，这是它唯一的答案；
        //   · `rag_remote_injected` —— **上一轮**远端实际注入了几条（语义同 kv_reuse_tokens）。
        //     开关开着**完全不代表**检索命中了，这个数才是"有没有真的在工作"的读数。
        val ragRemoteEnabled = RagPrefs.remoteEnabled(ragPrefs())
        val ragRemoteKbJson = RagProtocol.kbJson(RagPrefs.remoteKb(ragPrefs()))
        val ragRemoteInjected = lastRagInjected.get()
        // ── 两路共用一份 ctx 的「提示」（ISSUE #426）────────────────────────────
        //
        // `parallelN=1`（默认档）时 App 内聊天与 HTTP **落在这同一份 ctx 上**，
        // 而且**共用同一条 seq**（一格一条 seq，见 native 的 `paths_of` / `seq_of`）：
        // 位置只有一份，谁用过谁留下的前缀就是谁的 —— 两路同时用会互相顶掉对方的
        // KV 前缀，表现为"复用忽有忽无 / 某轮突然报装不下"。
        //
        // 为什么不靠"一格里切两条 seq"把两路隔开：库的 `n_ctx_seq = n_ctx / n_seq_max`，
        // 切一刀就把每格的位置白砍一半（真机 `decode rc!=0 at offset 2048` 就是这么来的）。
        // 两路的分工本来就该由**格**完成 —— 所以这一档的答案只能是把约束说明白。
        //
        // 所以这一档必须**当场说清楚**：把"只能在一侧用"写成一句可读的警告，
        // 而不是留给用户从 `slots_shared` / `used_self` 里自己推。判据是
        // 「只有一份 ctx」（`slots_total <= 1`），与 `sharedAt` 不同 ——
        // 后者是"此刻两路都压着"，而这句话说的是**这一档的固有约束**：
        // 哪怕现在只有一路在跑，调大 parallelN 才是两路并用的前提。
        val singleCtx = loaded && slotsTotal <= 1
        val sharedNote = if (singleCtx)
            "parallelN=1：App 内聊天与 HTTP 共用这一格的同一份 ctx、同一条 seq" +
                (if (ctxPerSlot > 0) "（位置只有 $ctxPerSlot 枚）" else "") +
                "，请只在 App 或 HTTP 任一侧使用，否则两路会互相顶掉 KV 前缀；" +
                "要两路同时用请把 parallelN 调到 2 以上并重新加载模型" else ""
        val sharedNoteJson = sharedNote.jsonQuoted()
        // ── 一份「这几个数对不对得上」的自证（ISSUE #426 第七回合）─────────────
        //
        // 真机那一屏是 `ctx_size 12288 / slots_total 3 / ctx_per_slot 4096`，而设置页
        // 填的是 8192 / 2 格。三个数各自都合法（每个都是"某个量"的真值），乘起来
        // 还恰好自洽 —— 于是没有任何一处能被指认为错，要读的人自己做三次除法才能
        // 发现"这三个数不是同一次加载的数"。这就是这条读数存在的理由。
        //
        // 报的是「请求的」与「实际的」这一对差值，空串 = 两边都对得上（绝大多数场次），
        // 不为一行正常读数刷屏：
        //   · `slots_requested != slots_total`         -> 除不尽回落 / 建份失败 / 钳位；
        //   · `ctx_per_slot_requested != ctx_per_slot` -> 平台钳过。
        //
        // ⚠ 这里只发 `ctx_shared_note`（README §并发那一节点过名，是对外承诺），
        //   **不再发** `slots_note`（#392 收口）：它与 `ctx_actual` / `ctx_per_slot` /
        //   `ctx_per_slot_requested` / `slots_requested` 四个数完全等价，是把数字
        //   翻成自然语言的第二份拷贝 —— 报告自己本来就从数字重新生成这句话。
        //   JSON 里放人话的代价：客户端要么正则解析，要么忽略。
        // 注意 Kotlin 原始字符串（"""..."""）的行尾引号计数：
        // 拼接段必须以 `"""` 收尾（字面量内容 0 个引号）；若写成 `""""`，
        // 最后一个引号会被当成字面量内容，JSON 里就多出一个 `"`，整份响应立刻非法。
        // 段首同理：`""""` = 结束符 `"""` + 内容 `"`。
        //
        // ⚠ 这是多段拼接，不是随手折的行：`tools/run_health_json_guard.py`
        // 会按 Kotlin 词法把每一段还原成字面量再拼成真 JSON 去解析，判据里第一条是
        // "至少 4 段"（并成更少才是退化 —— 见该守卫 `0.9.143` 的改法：判据从
        // "恰好 4 段"放宽，因为它原先挂在**排版**上，而不是 JSON 本身）。
        // 加字段时**尽量并进现有段**，少一次折行就少一次引号计数的机会。
        // 反过来，段数不对时守卫会先红在"段数"上，比 JSON 解析报的错好查得多。
        val json = """{"status":"ok","model_loaded":$loaded,"model":$desc,""" +
            """"ctx_used":$ctxUsed,"ctx_size":$ctxSize,"ctx_actual":$ctxActual,"ctx_per_slot":$ctxPerSlot,"ctx_per_slot_requested":$ctxPerSlotReq,"slot_in_use":$slotInUse,"kv_last_ctx":$lastCtx,"busy":${SlotScheduler.busy > 0},"port":$PORT,""" +
            """"generating":$generating,"prefilling":$prefilling,"cancelled":${LlmEngine.currentCancel?.cancelled ?: false},""" +
            // slot 三字段并进这一段（**不另起一段**）：`run_health_json_guard.py` 守着
            // 引号计数，少一次折行就少一次引号计数的机会（这个坑踩过一次）。
            """"slots_total":$slotsTotal,"slot_active":$slotActiveJson,""" +
            """"slot_owner":$slotOwnerJson,"slots_requested":$slotsReq,"ctx_shared_note":$sharedNoteJson,""" +
            """"kv_cache_valid":$kvValid,"kv_reuse_tokens":$kvReuse,"kv_prefill_tokens":$kvPrefill,"kv_rounds":$kvRounds,"kv_lcp_tokens":$kvLcp,"kv_slot":$kvSlot,"kv_slots":$kvSlotsJson,"frozen_ms":$frozenMs,"frozen_count":$frozenCount,"frozen_total_ms":$frozenTotal,"restart_pending":$restarting,""" +
            // 三个 RAG 字段与合批六个字段都并进**最后这一段**（不另起一段）：
            // 段数越少，行尾引号计数出错的窗口越小 —— 这份 JSON 非法时
            // 探针会把健康的服务判成不可达。
            // `$ragRemoteKbJson` 是**已带引号**的 JSON 串（转义收在 RagProtocol 一处）。
            // 中间层三格（ISSUE #452 第 3 步，`/v1/relay/chat/completions`）并进**同一段**
            // —— 与上面那两处同一条纪律：段数越少，行尾引号计数出错的窗口越小。
            // 它们回答的是"中间层到底在不在干活"。三格缺一格，失效就看不见：
            //   · `relay_sessions`  —— 账本里记着几条会话（0 = 一次都没走过 relay）；
            //   · `relay_forwarded` —— **最近一次** relay 请求前置删了几轮（0 = 原样转发，
            //     而"原样转发"有两种成因：坐标对不上、或本来就没丢过 —— 下一格分开它们）；
            //   · `relay_reason`    —— 最近一次 relay 的那句人话原因（与日志同一份文案）。
            // ⚠ 不报 token 数、不报"省了多少 prefill"：中间层量不出这两件事
            //   （见 `RelayTrim` 文件头），编一个数出来比不报更糟。
            """"embed_loaded":$embedLoaded,"embed_model":$embedDesc,"embed_dim":$embedDim,"embed_pooling":$embedPooling,"embed_out_dim":$embedOutDim,"rag_remote_enabled":$ragRemoteEnabled,"rag_remote_kb":$ragRemoteKbJson,"rag_remote_injected":$ragRemoteInjected,"embed_batch_batches":${bs.batchedBatches},"embed_batch_entries":${bs.batchedEntries},"embed_batch_fallback_batches":${bs.fellBackBatches},"embed_batch_fallback_entries":${bs.fellBackEntries},"embed_batch_ms":${bs.batchedMs},"embed_batch_size":$wantBatch,"embed_batch_size_index":$indexBatch,"embed_batch_size_index_kb":$currentKbNameJson,"embed_chunk_index":$indexChunk,"rag_inject_chars_set":${injectWidthOf(ragPrefs())},"embed_ctx_per_seq":$wantCtx,"embed_ctx_per_seq_live":$ctxPerSeqLive,"embed_seq_max":$wantRows,"embed_seq_max_live":$seqMaxLive,"embed_gate":$wantGate,"embed_gate_live":$gateLive,"embed_ctx_total_live":${LlmEngine.embedCtxTotal},"embed_parity_rows":${pari.rows},"embed_parity_identical":${pari.identical},"embed_parity_different":${pari.different},"embed_parity_verdict":$parityVerdictJson,"embed_parity_repeat_measured":${pari.repeatMeasured},"embed_parity_repeat_not_identical":${pari.repeatNotIdentical},"embed_parity_repeat_batched_measured":${pari.repeatBatchedMeasured},"embed_parity_repeat_batched_not_identical":${pari.repeatBatchedNotIdentical},"embed_parity_group":$parityGroupJson,"embed_retrieve_measured":${retr.measured},"embed_retrieve_queries":${retr.queries},"embed_retrieve_answer_changed":${retr.answerChanged},"embed_retrieve_order_changed":${retr.orderChanged},"embed_retrieve_batch_base":${retr.baseBatch},"embed_retrieve_batch_other":${retr.otherBatch},"embed_retrieve_topk":${retr.topK},"relay_sessions":${RelayLedger.size()},"relay_forwarded":${relayForwardedRounds},"relay_reason":${relayLastReason.jsonQuoted()}}"""
        // 自检：手拼 JSON 一旦出引号错，探活/监控会把健康的服务判为不可达。
        // 这里用 JSONObject 解析兜底，宁可抛异常暴露问题，也不静默发出非法 JSON。
        return try {
            JSONObject(json).toString()
        } catch (e: Exception) {
            // 只有一句 `Unterminated array at character 402`（#384 原文）时，"服务端拼错了"
            // 与"探针把这个位置读裂了"两个方向**同形**：报的位置上恰好是一段合法的数组
            // 字面量，而读者既不能证伪、也不能证实，只能猜。
            //
            // 所以这里把**位置 + 那一段**一起报出来：拼错的那一格当场可见，
            // 而"报的位置上明明是一段合法 JSON"也当场可见 —— 两种方向从此分得开。
            // 片段边界**按码点回退**（不切在代理对中间）：切开一个 emoji 会再多出一个
            // 无法解释的"乱码"，而那正是用户一开始报的现象。
            errJson("health json malformed: ${e.message}" + jsonSnippetAround(json, e))
        }
    }

    /**
     * 数组取值：越界给 -1，**不抛、也不吞成 0**。
     *
     * -1 是「这一格没有」（与 `fork_at` 的"无分叉"同一套语义：缺 = 负）。
     * 用 `runCatching` 吞异常的那一版会把越界说成 0 —— 而 0 在这份账本里到处是
     * 合法取值（"从第 0 枚算" / "没注入"），于是"代码坏了"在 `/health` 上长成
     * "读数一切正常"，谁也查不出来（#384 就是这么过去的）。
     *
     * 写成对象内的**普通方法**（不是扩展函数）：`kv_slots` 那段在
     * `mapIndexed { }` 里读，`a` 的静态类型是 `IntArray`，扩展函数在这里要靠
     * 隐式接收者解析、易与同名成员打架；`at(a, 0)` 直白且不会被解析走。
     */
    private fun at(arr: IntArray, index: Int): Int =
        if (index >= 0 && index < arr.size) arr[index] else -1

    /** 同上，但越界取 [fallback]（只给"缺了比报 0 更误导"的那几格用）。 */
    private fun atOr(arr: IntArray, index: Int, fallback: Int): Int =
        if (index >= 0 && index < arr.size) arr[index] else fallback

    /**
     * `health json malformed` 的定位助手：给一句"位置 + 附近原文"。
     *
     * 只为让两个方向分得开（见 [healthJson] 调用处）：服务端拼错 vs 探针读裂。
     * 取不到位置（解析器不报 `at character N`）时**只多报长度**，不猜位置 ——
     * 猜一个位置比不报更糟：读者会拿它去数原文，而数出来的东西对不上。
     */
    private fun jsonSnippetAround(json: String, e: Exception): String {
        val pos = Regex("at character (\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
        // ⚠ 三件事分开报，因为它们答的是三个问题：
        //   · 全文多少**字符 / 码点 / UTF-8 字节** —— 解析器报的那个 `character` 是**按它的
        //     口径**数的，与我们这里的 `String.length` 未必同一把尺子（中文一个码点就是
        //     3 个字节）。只报一个数时，"位置 401 附近明明是合法 JSON"会被读成"解析器
        //     读裂了"，而真相可能只是两个数各自都数对了、单位不同；
        //   · 位置**夹住**时如实说，不把越界位置硬截成一个看得过去的片段 ——
        //     截出来的那段会"证明"一个它其实没证明的位置。
        val chars = json.length
        val cps = json.codePointCount(0, chars)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        val total = "；全文 $chars 字符 / $cps 码点 / $bytes 字节（UTF-8）"
        if (pos == null) return total
        if (pos !in 0..chars) {
            return total + "；位置 $pos 超出 0..$chars（解析器的 character 口径与本处字符数不同，先看这三个数）"
        }
        // 左右各留 24 个码点：够包住一个 `"key":` 与它的值，又不至于把整段刷屏。
        val from = json.offsetByCodePoints(maxOf(0, pos - 24), minOf(24, pos))
        val to = json.offsetByCodePoints(from, minOf(48, json.codePointCount(from, chars)))
        // 换行/引号在这里只是"证据"，不是要复用的一份 JSON —— 拍平即可。
        val piece = json.substring(from, to).replace("\n", "\\n").replace("\r", "\\r")
        return total + "；位置 $pos 附近：…$piece…"
    }

    /**
     * `GET /v1/models/{id}` 的 id 取口 —— **解出来的 id 必须与 `modelsJson()` 里
     * 放进 `data[].id` 的那一个逐字节相等**，否则 SDK 会照着列表里的 id 去查、
     * 查到 404，而"列表里有、查不到"这种自相矛盾，排查时会先怀疑网络。
     *
     * 三条约束（每条都对应一种"看起来正常"的错法）：
     *   · **百分号解码**：id 可能是中文别名或带空格的文件名，客户端一定会 URL 编码。
     *     不解码时 `/v1/models/我的模型` 直接找不到，而报的是 404「模型不存在」——
     *     与"这个模型真没有"同形。
     *   · 解码失败（非法 `%xx`）**按原样**使用，不抛 —— 抛出去就是 500，
     *     而这里最坏只是找不到，404 更准确。
     *   · **查 query 只在 `/` 之后**（`pathUnder` 会把 `/v1/models/abc?x=1` 也判进
     *     这个分支），少切这一刀会把 `?x=1` 拼进 id。
     */
    private fun idFromModelsPath(path: String): String {
        val tail = path.removePrefix("/v1/models/")
        val raw = tail.substringBefore('?').substringBefore('#')
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * 单个模型的 OpenAI 兼容对象（`GET /v1/models/{id}`）。
     *
     * 语义上就是**列表里那一条**，所以这里复用同一个档案拼装逻辑（[attachProfile]），
     * 字段名不许另起一套：两处字段名一旦分叉，客户端会得到"列表里有 profile、
     * 单查没有"的诡异结果，而两边都是合法 JSON、谁都不报错。
     *
     * 命中判据与 `modelsJson()` **同源**：拿别名逐个比（`id`/`aliases[0]` 都是别名）。
     * 不再写第二套（比如"去文件系统 stat 一下这个名字"）—— 判据分叉的那天开始，
     * 列表与单查就会给出互相矛盾的两个答案。
     */
    private fun modelEntryJson(id: String): String {
        val dbg = appContext
        if (dbg == null) return errJson("not found: $id")
        val files = try { ModelLibrary.list(dbg) } catch (_: Throwable) { emptyList() }
        val profile = LlmEngine.modelProfile()
        // 两点纯粹是**源码级判据**要求的，改了会觉得"这有什么必要"，但不改就会红：
        //   · 形参名**不能叫 `alias`** —— 它下面那个 `for` 循环里有个局部变量同名，
        //     而 `run_http_scope_order.py` 会把局部函数的形参表当成"声明前的读"
        //     （它只排除 `fun f(名: 类型)` 这一种形态，形参表 + 返回类型不认）。
        //     判据是真词法、能捞到真问题，所以让路的是源码，不是判据。
        //   · `cur` 必须**先取好**再声明 `entry`：`entry` 体内读它，而局部变量没有提升。
        val cur = currentModel
        fun entry(forId: String, bytes: Long?, ftype: String?): JSONObject {
            val o = JSONObject()
                .put("id", forId)
                .put("object", "model")
                .put("created", 0)
                .put("owned_by", "local-llm-server")
            if (bytes != null) o.put("meta", JSONObject().put("size", bytes).put("ftype", ftype ?: ""))
            if (profile.available && forId == cur) {
                o.put("profile", JSONObject().apply {
                    profile.name?.let { put("name", it) }
                    profile.architecture?.let { put("architecture", it) }
                    profile.tensorBytes?.let { put("tensor_bytes", it) }
                    profile.paramCount?.let { put("param_count", it) }
                    profile.ctxTrain?.let { put("ctx_train", it) }
                    profile.vocabSize?.let { put("vocab_size", it) }
                    profile.fileType?.let { put("file_type", it) }
                    profile.metaCount?.let { put("meta_count", it) }
                })
            }
            return o
        }
        for (f in files) {
            val alias = ModelLibrary.aliasOf(dbg, f.name)
            if (alias != id) continue
            val quant = Regex("(?i)(iq\\d+[a-z0-9_]*|q\\d+[a-z0-9_]*|f16|bf16|f32)").find(f.name)?.value?.uppercase() ?: ""
            return entry(alias, f.length(), quant).toString()
        }
        // 库为空但模型已加载（异常情况）：与 `modelsJson()` 的兜底**同一口径**，
        // 保证"列表里出现过的 id 一定查得到"。
        if (currentModel == id) return entry(id, null, null).toString()
        return errJson("not found: $id")
    }

    private fun modelsJson(): String {
        // org.json 结构化输出：模型列表带路径/大小/时间/量化等富信息。
        // 注意：jsonQuoted() 自带引号，手拼 JSON 时再嵌一层引号会产生非法 JSON，
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
            if (ctx != null) ModelLibrary.list(ctx) else emptyList()
        } catch (_: Throwable) { emptyList() }
        val cur = currentModel
        val root = JSONObject()
        root.put("object", "list")
        val modelsArr = JSONArray()
        val dataArr = JSONArray()
        // 「当前加载的是哪一个」的判据是**别名**（与 `HttpApi.currentModel` 同源），
        // 只有它指的那一条挂档案：库同一时刻只加载一份权重（`Session S` 只有一个），
        // 给别的条目挂等于把"这一个模型"的读数说成"那个模型"的。
        //
        // 别名解析必须走 `ModelLibrary.aliasOf`：另一处**同名**的
        // `ModelLibrary.Row.alias` 是 `rows()` 内置好的一批，两者在"用户改过别名、
        // 而改名前已缓存"这类边界上取值不同 —— 拿 `rows()` 去比对等于换了一套判据，
        // 结果就是"当前模型看起来不在库里"（列表里一条都不带档案）。
        val profile = LlmEngine.modelProfile()
        fun attachProfile(o: JSONObject) {
            if (!profile.available) return
            o.put("profile", JSONObject().apply {
                profile.name?.let { put("name", it) }
                profile.architecture?.let { put("architecture", it) }
                // 权重字节与文件字节**两个都给、字段名不同**：它们差值就是元数据+对齐，
                // 合并成一个数就再也说不清"文件为什么比权重大一截"。
                profile.tensorBytes?.let { put("tensor_bytes", it) }
                profile.paramCount?.let { put("param_count", it) }
                profile.ctxTrain?.let { put("ctx_train", it) }
                profile.vocabSize?.let { put("vocab_size", it) }
                profile.fileType?.let { put("file_type", it) }
                profile.metaCount?.let { put("meta_count", it) }
            })
        }
        if (files.isEmpty()) {
            // 降级：拿不到模型库时只报当前加载模型（别名），保证连接可用
            cur?.let { alias ->
                val d = JSONObject().put("id", alias).put("object", "model").put("owned_by", "local-llm-server")
                attachProfile(d)
                dataArr.put(d)
                modelsArr.put(JSONObject().put("name", alias).put("model", alias).put("type", "model"))
            }
        } else {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val seen = HashSet<String>()
            for (f in files) {
                val alias = ModelLibrary.aliasOf(ctx!!, f.name)
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
                val d = JSONObject()
                    .put("id", id)
                    .put("aliases", JSONArray().put(alias))
                    .put("tags", JSONArray())
                    .put("object", "model")
                    .put("created", f.lastModified() / 1000)
                    .put("owned_by", "llamacpp")
                    .put("meta", JSONObject()
                        .put("size", f.length())
                        .put("ftype", quant))
                if (cur != null && alias == cur) attachProfile(d)
                dataArr.put(d)
            }
            // 当前加载模型不在库里时（异常情况），补一个别名条目保证客户端可选
            val listedAliases = files.mapNotNull { f -> ctx?.let { c -> ModelLibrary.aliasOf(c, f.name) } }
            if (cur != null && cur !in listedAliases) {
                val d = JSONObject().put("id", cur).put("object", "model").put("owned_by", "local-llm-server")
                attachProfile(d)
                dataArr.put(d)
            }
        }
        root.put("models", modelsArr)
        root.put("data", dataArr)
        return root.toString()
    }

    /**
     * `POST /v1/embeddings`：把一批文本编码成**已归一化**的向量（RAG 阶段 3）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么这个端点**只**判「嵌入模型在不在」
     * ══════════════════════════════════════════════════════════════════════
     * 它不读 `rag_enabled` / `rag_remote_enabled` / `rag_remote_kb` 中的任何一个。
     * 这是本端点唯一的架构约束，也是它最容易被"顺手"改坏的地方 —— 加一句
     * `if (!RagPrefs.remoteEnabled(this)) 503` 看起来是"统一口径"，
     * 实际是**让两件不相干的事互相绑架**：用户为了 App 内聊天不注入而关了知识库，
     * 远端客户端的 embeddings 就跟着不可用，而报的错还是"知识库未启用"。
     *
     * 缺嵌入模型 -> **503 + 明文原因**，绝不静默返回空 data（那会被调用方当成
     * "这批文本没有向量"而静默跳过，检索全空但一切 200）。
     * 判据落在 `tools/rag_remote/check_embeddings_decoupled.py`：该函数体里
     * **一个 `RagInject.` 引用都不许有**。
     *
     * ⚠ 它**不持 `busy`**：嵌入与生成走两条路（双常驻的另一半价值）。持了 busy
     * 就等于"聊天的同时不能算向量"，而那正是双常驻要买的东西。
     */
    private fun handleEmbeddings(req: Req, out: OutputStream, corsOrigin: String? = null) {
        if (!LlmEngine.hasEmbedModel) {
            emitLog("拒绝 /v1/embeddings：嵌入模型未加载（与知识库开关无关）")
            writeJson(out, 503, errJson(
                "嵌入模型未加载：先在 App 内加载一个嵌入模型（如 bge-small-zh-v1.5）；" +
                    "本端点与知识库开关无关，只看嵌入模型是否驻留"), corsOrigin)
            return
        }
        val dim = LlmEngine.embedDim
        if (dim <= 0) {
            emitLog("拒绝 /v1/embeddings：嵌入模型维度取不到（加载可能不完整）")
            writeJson(out, 503, errJson("嵌入模型维度取不到（加载可能不完整），请重新加载嵌入模型"), corsOrigin)
            return
        }
        try {
            val j = JSONObject(req.body)
            val (rq, opt, err) = EmbeddingsApi.fromRequest(j)
            if (rq == null || opt == null) {
                emitLog("拒绝 /v1/embeddings：${err}")
                writeJson(out, 400, errJson(err ?: "input 非法"), corsOrigin)
                return
            }
            // 模型名回显：OpenAI 客户端会用响应里的 model 反查它请求的那个。
            // 缺省取**嵌入模型自己的描述**而不是硬编码 "local" —— 双常驻下这句话
            // 必须指向嵌入那一份（对话模型往往是另一个，回错等于告诉调用方
            // "你算出来的向量来自 X"，而实际来自 Y；两个模型维度不同时，
            // 调用方按 X 的维度去解析会直接错位）。
            val modelName = j.optString("model", "").ifEmpty {
                LlmEngine.embedModelDesc().ifEmpty { "local-embed" }
            }
            val items = ArrayList<String>(rq.inputs.size)
            var failCount = 0
            var idCount = 0
            for ((i, item) in rq.inputs.withIndex()) {
                // ⚠ index 必须是**请求里的下标**（`i`），不是"第几个成功的"：
                // 用成功计数当 index 会让"中间有一条失败"之后的每一条都错位一格，
                // 而调用方按 index 把向量配文本入库 —— 文本 A 拿到文本 B 的向量，
                // 检索全错、接口 200、没有任何一处报错。
                //
                // token id 数组在这里**先还原成文本**再走同一条编码路径（见
                // `nativeDetokenize` 的注释）。还原失败（id 越界 / 没加载模型）
                // 是这一条的错误，不是整批的 —— 与"这条编码失败"同一处置。
                val text: String? = when (item) {
                    is EmbeddingsApi.Item.Text -> item.text
                    is EmbeddingsApi.Item.TokenIds -> {
                        idCount++
                        LlmEngine.detokenize(item.ids)
                    }
                }
                if (text == null) {
                    failCount++
                    items.add(EmbeddingsApi.errorItemJson(i,
                        "这一条的 token id 还原失败：id 越界（不属于本模型 vocab）或数组为空；" +
                            "id 必须来自同一个嵌入模型"))
                    continue
                }
                val vec = LlmEngine.embed(text)
                if (vec == null || vec.isEmpty()) {
                    failCount++
                    // 实值原因已由 LlmEngine.embed 内部打过一行；这里在**该项**上再带
                    // 一句，让调用方不必翻服务端日志就知道是哪一条。
                    items.add(EmbeddingsApi.errorItemJson(i,
                        "这一条编码失败（服务端日志有实值原因：维度/池化/范数）"))
                } else {
                    items.add(EmbeddingsApi.itemJson(i, vec, opt.encoding))
                }
            }
            // 一条失败不拖垮整批（见 EmbeddingsApi.errorItemJson），但必须留痕：
            // 只回 200 而日志干净时，"有些条没有向量"会被当成"那些文本本来就没意义"。
            if (failCount > 0)
                emitLog("embeddings：${rq.inputs.size - failCount} 条成功 / $failCount 条失败" +
                    "（失败项在 data[] 里带 error）")
            // id 条数单独记一笔：id 数组与字符串两条输入共用同一条编码路径，
            // 日志里若不区分，"这一批为什么慢/为什么有几条失败"就少了最关键的
            // 那半句（越界 id 的失败原因与"文本有问题"完全不同）。
            if (idCount > 0) emitLog("embeddings：其中 $idCount 条是 token id 输入（已还原文本）")
            emitLog(EmbeddingsApi.describe(rq.batch, rq.inputs.size, dim, opt.encoding))
            // 空 input（`"input": []`）是合法的：回一个空 data，**不是**错误。
            // 回 400 会让"我确实没有要编码的东西"看起来像请求写错了。
            writeJson(out, 200, EmbeddingsApi.responseJson(modelName, items), corsOrigin)
        } catch (t: Throwable) {
            // 与生成端点同一口径：异常兜住、不留半截响应。但嵌入的失败**不携带
            // 用户文本**（日志里只报类型与 message），避免把知识库内容或提问
            // 写进日志文件 —— 迁移日志时那是最容易泄漏的东西。
            emitLog("embeddings 异常：${t.javaClass.simpleName}: ${t.message}")
            try { writeJson(out, 500, errJson(t.message ?: "internal error"), corsOrigin) } catch (_: Exception) {}
        }
    }

    /**
     * 中间层（服务端内第二个监听口，ISSUE #452 第 3 步）：`POST /v1/relay/chat/completions`。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它是**同进程内的一个旁路入口**，不是第二个服务
     * ══════════════════════════════════════════════════════════════════════════
     * 部署形态选的是 B（服务端内第二个监听口）：同一个 `HttpApi` 循环、同一份
     * `SlotScheduler`、同一份 KV 账本 —— 与另起一个进程/端口相比，它不需要
     * "中间层怎么知道服务端上一轮丢了什么"这条跨进程链路，因为那屏读数就在手边
     * （`local_ctx` 由同一个进程写出来）。
     *
     * 路径前缀 `/v1/relay/` 是刻意的：客户端把 base_url 从 `/v1` 换成 `/v1/relay`
     * 就接上了中间层，换回来就直连 —— 两档之间**不改任何请求形状**。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它只做三件事（做完就把请求交给同一个 [handleChat]）
     * ══════════════════════════════════════════════════════════════════════════
     *   ① 从 [RelayLedger] 取出**这条会话上一轮**的坐标；
     *   ② 按 [RelayTrim] 在客户端原件上删掉服务端已经丢过的那几轮
     *      （坐标对不上就原样转发 —— 见那边的判据顺序）；
     *   ③ 把重写过的 `messages` 交回 [handleChat] 跑**完全相同**的那一条路，
     *      并告诉预算"这些轮次是我前置删的"（`layoutDroppedRounds`），
     *      让它不再按水位丢第二遍。
     *
     * ⚠ 为什么不复制一份 `handleChat` 的代码：那 400 行里每一处判据（协议字段 /
     *   采样参数 / 工具 / 思考 / 预算 / 知识库 / 取消）都是"两个端点必须同一套"的
     *   老账，复制一份第二天就会漂。这里只改 `req.body`，其余一个字节不动。
     * ⚠ 记账（写 [RelayLedger]）**只由成功的响应写**：400/503 的响应体里没有
     *   `local_ctx`，拿它覆盖上一轮的坐标会让下一轮"看起来没有坐标"。
     *
     * 失败一律退化：请求体不是合法 JSON、没有 `messages`、坐标不可用 —— 全部**原样
     * 转发**给 [handleChat]，由它给出与直连完全相同的错误语义。中间层的失效
     * 必须表现为"退化成直连"，不能表现为"502"。
     */
    /**
     * 中间层那句「我进来了」/「我收工了」—— **同时**进日志页、落盘文件与探针。
     *
     * 以前这里是 `emitLog` + 直写 [LogFileStore]：文件里有、日志页里没有。用户每次
     * 想读 relay 那几行都得先点「导出日志」—— 而他报障的原话正是「日志里确实有，
     * 但每次看需要导出来」。
     *
     * 现在走 [LlmEngine.uiLog]：它与 App 内聊天日志是**同一条**管线
     * （logcat + ring + [LogFileStore.append] + logSink），所以 relay 的每一行都在
     * 日志页里直接可读，也照旧落文件、照旧进探针（`pushLog` 在探针模式下
     * 直写 native fd + 会话原文）。
     *
     * 为什么不在 `uiLog` 之外再直写一次文件：`pushLog` 已经调
     * [LogFileStore.append]，再写一遍就是每行两份。
     *
     * `##` 前缀是 [LogFileStore.append] 的既有语义（"控制行，复位折叠计数"）：
     * 用它带上 `[relay]` 前缀，读者一眼能把中间层那几行从聊天尾巴里挑出来。
     * 日志页上也会看到这个前缀 —— 相比"必须导出才看得见"，这是可接受的代价。
     */
    private fun relayMark(line: String) {
        try {
            LlmEngine.uiLog("## [relay] $line")
        } catch (_: Throwable) {
            // pushLog / 落盘理论上不抛；真抛了也至少让 logcat 与日志页有一行可读。
            emitLog(line)
        }
    }

    private fun handleRelayChat(req: Req, out: OutputStream, corsOrigin: String? = null,
                                chan: java.nio.channels.SocketChannel? = null) {
        val session = req.relaySession.ifEmpty { "default" }
        val incoming: org.json.JSONArray
        val msgs: List<Pair<String, String>>
        try {
            val j = JSONObject(req.body)
            // ⚠ 这三条早退也必须落 `/health` 的两格读数：不落的话探活会**一直显示
            //   上一次的那个数**，而"最近一次 relay 删了几轮"停在旧值上看起来完全正常。
            //   它们都是"退化成直连"那一档，语义与下面的 `droppedRounds == 0` 相同。
            incoming = j.optJSONArray("messages") ?: run {
                relayForwardedRounds = 0
                relayLastReason = "请求里没有 messages —— 原样转发（错误语义交给 handleChat）"
                relayMark("relay[$session]：${relayLastReason}")
                return handleChat(req, out, corsOrigin)
            }
            msgs = relayMessagesOf(incoming) ?: run {
                relayForwardedRounds = 0
                relayLastReason = "messages 形状认不出来 —— 原样转发"
                relayMark("relay[$session]：${relayLastReason}")
                return handleChat(req, out, corsOrigin)
            }
        } catch (t: Throwable) {
            relayForwardedRounds = 0
            relayLastReason = "请求体不合法（${t.javaClass.simpleName}）—— 原样转发"
            relayMark("relay[$session]：${relayLastReason}")
            return handleChat(req, out, corsOrigin)
        }
        // 只有"上一轮留下的那一段这一轮还在原处"时，才把那一份读数当坐标用
        // （见 `RelayLedger.matchesChain`：拿过期坐标删用户的对话是这一层唯一
        //  不可接受的坏法，而它与"正常删"在读数上完全同形）。对不上时把成因记一笔，
        // 否则 `/health` 只剩"原样转发"，看不出是"没有上一轮"还是"历史被改过"。
        //
        // ── 进到中间层了，先留一行（ISSUE #450 第十次现场）────────────────────
        // 这一行存在的唯一理由：让「请求到底进没进 relay」在日志上可判。
        // 它就读上面那两个值（同一份账本、同一次比对），一个判据都不参与 ——
        // 下面做什么与它无关。
        val ledgerSnap = RelayLedger.get(session)
        val chainOk = RelayLedger.matchesChain(session, msgChain(incoming))
        relayMark("relay[$session]：本轮进入中间层｜${msgs.size} 条消息｜账本" +
            (if (ledgerSnap == null) "空（首轮 / 刚启动 / 换了会话）"
             else if (chainOk) "对得上（${ledgerSnap.describe()}）" else "在、但与这一轮的对话对不上"))
        if (!chainOk && ledgerSnap != null) {
            relayMark("relay[$session]：上一轮的坐标与这一轮的对话对不上 —— 原样转发（重新对齐）")
        }
        val prev = if (chainOk) ledgerSnap?.toPrev() else null
        val verdict = RelayTrim.apply(msgs, prev)
        // `/health` 的那两格跟着最近一次落（见字段注释：探活没有会话上下文）。
        relayLastReason = verdict.reason
        val forwarded: String
        var layoutDropped = 0
        if (verdict.droppedRounds <= 0) {
            // 一个字节都不改：这一档必须与直连**逐字节相同**（包括 body 的空白与键序，
            // 因为 `RequestBodyDigest` 摘要的就是 body —— 改一个字节，"两轮送的是不是
            // 同一份"这个读数就对不上了）。
            relayMark("relay[$session]：${verdict.reason}")
            forwarded = req.body
        } else {
            forwarded = try {
                rewriteMessages(JSONObject(req.body), verdict.messages)
            } catch (t: Throwable) {
                emitLog("relay[$session]：重写 messages 失败（${t.javaClass.simpleName}）—— 原样转发")
                req.body
            }
            // 重写失败时 `forwarded === req.body`（内容相同），此时**不许**声明前置删除 ——
            // 否则预算会以为"那几轮已经不在 messages 里"，而它明明还在。
            layoutDropped = if (forwarded === req.body) 0 else verdict.droppedRounds
            relayMark("relay[$session]：${verdict.reason}" +
                (if (layoutDropped > 0)
                    "，服务端预算继续按水位治理（只裁剩下的这一段）" else ""))
        }
        val sub = Req(req.method, req.path, forwarded, req.origin, req.reqHeaders, req.auth,
            logTag = req.logTag, relaySession = session)
        // 上一轮坐标对**这一轮**的记账：`handleChat` 收尾处会把本轮 `local_ctx` 一起
        // 下发，但那是**响应体**，服务端自己写不出来 —— 唯一能读到它的是刚收到响应的
        // 客户端。所以这里在**发出请求之前**把"这一轮是我前置删了 N 轮"记在案上，
        // 下一轮 `RelayTrim.apply` 用它与本轮响应里服务端报的坐标做交叉验证
        // （见 `RelayLedger.Snapshot.layoutDropped`）。
        // ⚠ 只增不改：这一条不影响 [RelayTrim.apply] 的任何判据（它读的是 `rounds`），
        //   是给 `/health` 与日志对账用的。
        relayForwardedRounds = layoutDropped
        // 留下来的那份在**原件**里从第几条起（`-1` = 这一轮没删）。
        // ⚠ 取自 `verdict.keepFrom`，不在这里另算一遍：两个起点分叉的那一天不会报错，
        //   只会表现为"偶尔多删/少删一轮"（这一层唯一不可接受的坏法）。
        val layoutKeepFrom = if (layoutDropped > 0) verdict.keepFrom else -1
        RelayLedger.markForwarded(session, layoutDropped, msgs.size, layoutKeepFrom)
        // 同一个决定也要交给判据自己记一份：下一轮**只有首帧坐标**时（生成还没跑完，
        // 中间层拿不到收尾那一份 `rounds`），`RelayTrim.apply` 要靠它"沿用上一轮的
        // 决定"—— 见 `RelayTrim.carryOver`。两处写同一个数是有意的：一处给 `/health`
        // 与日志对账，一处给判据；它们必须同源，所以只在这一个位置落。
        RelayTrim.remember(layoutDropped, layoutKeepFrom)
        // ⚠ 响应**先预读、拿到坐标就放开直写**（ISSUE #452「拿回真流式」）。
        //
        //   上一版把整条响应写进缓冲、抽完 `local_ctx` 再原样转给客户端 —— 首帧延迟
        //   等于整轮生成时长。原因是坐标只挂在**收尾帧**上，而中间层读不到自己刚发
        //   的响应就没有下一轮的输入。
        //
        //   这一版把坐标挪到**响应开头**（`local_ctx.prompt_prefix` 随第一帧 / 头一起
        //   下发，`local_rounds` / `reuse` 也都在那里），于是：
        //     · 预读看到坐标 -> 这一轮记账 -> 剩下的字节**直接流给客户端**；
        //     · 预读满一屏还没有坐标 -> 退回整条缓冲（行为与上一版逐字节相同，只是慢）。
        //   两条路都保证客户端看到的字节与直连逐字节相同 —— 直写那条只是**更早**看到。
        //
        // ⚠ 直写那条路顺带修好了一个更严重的事：生成期的断连探测（`probeOutbound`）
        //   此前读的是缓冲、不是真实 socket，于是"客户端走了"要等整轮生成跑完才发现。
        //   放开直写之后探测打的是真连接，客户端一走就立刻停。
        // 这一轮**客户端原件**的顺序指纹（不是中间层转发出去的那一份）。
        //
        // 下一轮进门时 `RelayLedger.matchesChain` 只做一件事：上一轮这份原件是不是
        // 这一轮的前缀。客户端每轮把整份历史重发、正常只往后长，所以前缀一定成立；
        // 换会话 / 用户删了历史 / 不是同一条对话时前缀立刻断。存"转发出去的那一份"
        // 就必然要再存两份删除量（中间层的 + 服务端的）才能对齐 —— 那就是三套
        // 坐标系，真机上第二轮就判"对不上"。
        val chain = msgs
        // ⚠ `catch` 必须有，哪怕只加一句日志（ISSUE #450 第十次现场）。
        //   `handleChat` 自己吞异常（它把 Throwable 折成 500），但**它写在 `relayOut`
        //   上的那一次 500 同样会被预读吞下来**，于是 relay 这一层看到的只是
        //   "终于写了几个字节、然后收工" —— 异常本身一个字都没进这份日志。
        //   这里不改语义（照样往外抛），只把它记下来：用户那句「relay 没有任何反应」
        //   在绝大多数现场里就是"某一层抛了、而没有任何地方说过它抛了"。
        // 这一档的响应体是不是 SSE：`stream=true` 时才可能。判据取**请求**而不是响应头，
        // 因为心跳要在第一个字节之前就备好（响应头那时还在 `buf` 里）。
        // 读错方向（请求说流式、上游回 400 一整段 JSON）时的代价只有一种：垫进去的
        // 注释帧跟着那段 JSON 一起给客户端 —— 而 400 那一档 `handleChat` 根本
        // 走不到 `relayOut`（它直接 `writeJson`），所以这条路不存在。
        val relaySse = runCatching { JSONObject(forwarded).optBoolean("stream", false) }
            .getOrDefault(false)
        val prefetch = RelayPrefetch(session, relaySse, chain)
        // 这一轮在调度器里的那份 lease —— 由 `handleChat` 拿到之后回填（见 `onLease`）。
        //
        // 为什么要它：心跳写失败那一刻要回答"取消打给谁"，而 `handleRelayChat` 的
        // finally 与 `handleChat` 的 finally 没有先后保证。按**来源**问
        // （`requestAbortHttp`）在单格池里打不出唯一所指（App 与 HTTP 轮替占着同一格），
        // 于是"relay 探到客户端走了"可能停到 App 那一轮头上 —— 用户读到的是
        // 「relay 我点了停止没效果，不停止就生成完毕、但没内容」（ISSUE #450 第十三次现场）。
        var roundLease: SlotScheduler.Lease? = null
        try {
            handleChat(sub, relayOut(out, prefetch, { roundLease }), corsOrigin, layoutDropped, prefetch,
                onLease = { roundLease = it }, chan = chan)
        } catch (t: Throwable) {
            relayMark("relay[$session]：本轮异常抛出 ${t.javaClass.simpleName}" +
                (if (t.message.isNullOrEmpty()) "" else "：${t.message}") +
                "｜走到：" + prefetch.stageText())
            throw t
        } finally {
            // 记账有两支，缺一支就有一整类轮次永远记不上：
            //   · 收尾那一支（`noteRelayCoord`）：收尾帧 / 响应体里那份带着 `prompt_prefix`，
            //     它最全，也是"整条缓冲"那一档唯一的出口（`coordTaken` 已置位就什么都不做）；
            //   · 放开直写那一支：`release` 那一刻手里有完整字节时先落一份（`coordSnapshot`）。
            //     ⚠ 这一支只在**收尾没记上**时才补 —— 收尾那份多一格起喂点，不能被它覆盖。
            if (!prefetch.coordTaken) {
                val snap = prefetch.coordSnapshot
                    // 整条缓冲那一档（一屏还没攒满就结束）：`buf` 仍是完整的。
                    ?: RelayLedger.parseRelayResponse(prefetch.buf.toString("UTF-8"))
                if (snap != null && !prefetch.released) {
                    // 整条缓冲：**只认成功的响应**（`local_ctx` 只在成功路径上有），
                    // 连同这一轮喂进去的正文指纹一起落账（下一轮 `matchesChain` 要用）。
                    RelayLedger.put(session, snap, chain)
                    prefetch.coordTaken = true
                } else if (snap != null) {
                    // 直写那一档：`buf` 只剩尾巴，但 `release` 已经把那一刻的坐标摘下来了。
                    RelayLedger.put(session, snap, chain)
                    prefetch.coordTaken = true
                }
            }
            if (!prefetch.coordTaken) {
                relayLastReason += "；本轮没有坐标可用（服务端没报 kept_rounds / prompt_prefix）"
                // ⚠ 这句以前只在"到了 finally 还没记上账"时打，而**这句话的读者是排障的人**：
                //   他需要知道"没记上"是"服务端没报坐标"（正常，退化直连）还是
                //   "我们压根没等到响应"（异常）。两件事以前同形，现在按台阶说清。
                relayMark("relay[$session]：${prefetch.why.ifEmpty { "这一轮的响应里没有坐标" }}" +
                    "｜" + prefetch.stageText())
            }
            // 心跳到此为止：这一轮不会再往连接上写字，垫帧也没有对象了。
            prefetch.close()
            // ⚠ 心跳写失败 = 客户端在 prefill 窗口里走了（ISSUE #450 第十一次现场）。
            //   这一笔取消**必须落回这一轮自己头上**（`resolve` 只认同一个身份），
            //   否则它会留到那一轮登记时被兑现，或者更糟：留成一笔无主的账。
            //   取消走 `HttpApi` 的统一出口，判据仍是"按来源找我这一路"。
            if (prefetch.beatGone) {
                prefetch.beatGone = false
                // ⚠ ISSUE #450 第十三次现场：这一笔取消原来的出口是 `requestAbortHttp()`，
                //   而它是**按来源**找格子的 —— 单格池里 `slotOfSource(HTTP)` 回的是
                //   **先被扫到**的那个来源，不保证是这一条连接。
                //   现在打在**心跳写失败那一刻记下的那一格 + 那份 lease** 上
                //   （判据与取消登记同源，见 `slotAtGone`）；那个格子若已归还调度器，
                //   就什么都不做 —— 这一轮紧接着会收尾，不欠谁一笔。
                val slot = prefetch.slotAtGone
                prefetch.slotAtGone = -1
                val lease = roundLease
                if (slot >= 0 && lease != null && SlotScheduler.currentSlotOf(lease) == slot) {
                    emitLog("relay[$session]：预读窗口里探测到客户端已离开（心跳写失败）：" +
                        "取消 slot $slot 上这一轮，不再把 prompt 算完")
                    runCatching { LlmEngine.requestAbort(slot, lease) }
                } else {
                    emitLog("relay[$session]：预读窗口里探测到客户端已离开（心跳写失败），但这一轮" +
                        "已经不在 slot $slot 上了（已收尾或已换格）—— 不再补打一笔取消")
                }
            }
            // ── 非流式放开直写之后，把客户端还欠着的那一截补齐 ──────────────
            // 那种响应的字节是**一次写完**的，客户端按 `Content-Length` 读满就关；
            // 而放开直写那一刻交给它的只有预读那一段（见 [RelayPrefetch.contentLength]）。
            // 不补的现场（ISSUE #450 第七次）：客户端手里是一份**截断的 JSON**，
            // 服务端这边是 `Broken pipe` —— 两边都只看到"没有输出"。
            // ⚠ ISSUE #450 第十四次现场：这一跳以前是 `buf.writeTo(out)` —— 而
            //   `ByteArrayOutputStream.writeTo` **不清空自己**。于是：
            //     · 放开直写那一档，`buf` 里是"客户端已经收到过"的尾巴，这里再写一遍
            //       = 同一条 chunked 流里多出一截重复帧（客户端读到长度行错位后断流，
            //       现象与"服务端什么都没发"完全同形）；
            //     · 整条缓冲那一档，`flushHeld` 刚补完的那一截又被写一遍。
            //   改走 `takeAll()`（取走即清）：一次收工只把"还没给过客户端的字节"交出去一次。
            //   它不改变字节内容 —— `handleChat` 写进来的每一个字节本来就自带分帧
            //   （`sseHead` / `writeFrame` / `writeRaw`），这一层只负责搬运。
            //
            // ⚠ `heldBytes` 必须取在 `flushHeld` **之前**：那一跳之后 `buf` 里剩下的
            //   已经不是"这一轮攒下了多少"（判据同 `close()` 那一处）。
            val heldBytes = prefetch.buf.size()
            // 先把**客户端还欠着的那一截**补上（`Content-Length` 说了算），再交出剩下的尾巴。
            // 顺序反了就是"先写尾巴再补中段"：客户端读到一份错位的 JSON —— 而它只会
            // 报 `Unexpected status line` 或"无输出"，与服务端这边的读数完全对不上。
            //
            // ⚠ 响应头**不在**这里另写：它随 [RelayPrefetch.absorb] 的返回值出去，
            //   而 `relayOut` 每次只写 `absorb` 交回来的那一段。这里再写一次就是
            //   "同一个头写两遍"—— 客户端把第二份当成第二次响应，流当场断开
            //   （本轮 relay 流式损坏的根因）。"整条缓冲"这一档如果头还没被剥走，
            //   它会原样留在 `buf` 里，随下面的尾巴一起交出去（顺序仍是头→正文）。
            try { prefetch.flushHeld(out) } catch (_: Exception) {}
            try {
                val tail = prefetch.takeAll()
                if (tail.isNotEmpty()) prefetch.writeLocked(out, tail)
            } catch (_: Exception) {}
            // 心跳/探针线程到此为止（这一轮不会再往 `buf` 里写字，垫帧也没有对象了）。
            try { prefetch.close() } catch (_: Throwable) {}
            // ── 这一轮**一定**留一行（ISSUE #450 第十次现场：用户说「relay 没有任何反应，
            //    日志也不提示」）────────────────────────────────────────────────
            // 上游那几句都只在**成功路径**上：请求没进 relay、或进了但在 `handleChat` 里
            // 第一帧之前就返回（401/503/400）、或 prefill 还在跑 —— 这三种处境在日志上
            // **完全同形：一行都没有**，于是"没有反应"这句话指向不了任何一步。
            //
            // 这一行不带判据、也不改行为的两件事，一并报出来：
            //   · 台阶（`stageText`）：换行之后有没有真往客户端写出过字节；
            //   · 正文的形状（`bodyKind`）：流式与整段 JSON 两条路对"短连接"的容错
            //     完全不同（后者错一个字节就是一份坏掉的 JSON），而这一格在日志上
            //     以前没有任何地方说得出。
            // ⚠ `buf.size()` 必须取在 `close()` **之前**：`close()` 之后那一格已经
            //   不代表"这一轮攒下了多少"了（心跳线程退场、`buf` 可能已被清空）。
            relayMark("relay[$session]：本轮收工｜" + prefetch.stageText() +
                "｜攒下 $heldBytes 字节｜正文 ${prefetch.bodyKind()}" +
                prefetch.probeText() +
                (if (prefetch.why.isEmpty()) "" else "｜${prefetch.why}"))
        }
    }

    /**
     * 这一轮的坐标记账（下一轮 [RelayTrim] 的输入）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 记哪一份：`body`（收尾帧 / 响应体里那个 `local_ctx`），不是响应头
     * ══════════════════════════════════════════════════════════════════════════
     * 头里那一份与 body 那一份差**一格**：`prompt_prefix`（起喂点）只在 body 里。
     * 而中间层唯一靠它答的那件事是「服务端这一轮真丢了历史、而中间层上一轮没删过」
     * —— 少了它，那一档永远退化成"照上一轮的决定 = 删 0 轮"，于是服务端每轮都在丢、
     * 中间层一次都没删过（INV-#452 真机：`relay_forwarded` 恒 0）。
     *
     * 所以这一刀记的是 body 那一份；`head` 只当**兜底**（body 那一份没报坐标时，
     * 头里那份至少能说明"该删哪几轮"，总比什么都没有强）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么挂在"收尾帧写出去之后"，而不是更早
     * ══════════════════════════════════════════════════════════════════════════
     * 起喂点量的是"这一轮**最终**从第几个字符起喂"，而它在 prefill 之后才定；
     * 收尾帧是每一条流都必然会到达的那一帧，位置也在生成之后 —— 到那里两份坐标
     * 才是同一个东西。再往后一步（`handleRelayChat` 的 `finally`）就已经晚了：
     * 直写那条路走完 `prefetch.buf` 已被 `writeTo` 清空，解析出来的是半截。
     *
     * 只由**成功的**响应写：400/503 的路径上根本走不到这两处（`ctxField` 是空串）。
     *
     * @param body 本轮完整 `local_ctx` 的**内存字符串**（`{"local_ctx":{...}}`，
     *   空串 = 这一轮没报）。它**不**再写进响应体（OpenAI 兼容），只在这里喂账本。
     * @param head relay 响应头里那一份（`,"local_ctx":{...}`，空串 = 没报）
     */
    private fun noteRelayCoord(prefetch: RelayPrefetch?, body: String, head: String) {
        // 直连那一档恒 `null` —— 这里拦住，免得"直连的响应把 relay 的账本写了一份"
        // （那会让下一轮 relay 拿到别人的坐标）。
        if (prefetch == null || prefetch.coordTaken) return
        // ⚠ 两处都是**含前导逗号**的片段（`,"local_ctx":{…}`），合成一个对象前
        //   必须先去掉那个逗号 —— 否则 `{,"local_ctx":…}` 不是合法 JSON，
        //   `RelayLedger.parse` 返回 null，账本静默为空（与"这一轮没坐标"同形）。
        val snap = (if (body.isNotEmpty()) RelayLedger.parse("{" + body.substring(1) + "}") else null)
            // `head` 与 `body` 同一个形状：去掉前导逗号后交给**同一个**解析入口：
            // 两份坐标两种解析必然漂移。
            ?: (if (head.isNotEmpty()) RelayLedger.parse("{" + head.substring(1) + "}") else null)
            ?: return
        prefetch.coordTaken = true
        RelayLedger.put(prefetch.session, snap, prefetch.chain)
        emitLog("relay[${prefetch.session}]：本轮坐标已记账 ${snap.describe()}")
    }

    /**
     * relay 那一档的**出口**：字节先过 [RelayPrefetch]，它决定"现在就写"还是"再攒一会儿"。
     *
     * 为什么不是直接把预读那一层套在 [OutputStream] 上：`handleChat` 里所有写都是
     * 「一帧一帧 `sseEvent` / 一条 `writeJson`」，而它要的语义是"这一段**此前没给过
     * 客户端**"。套一层 `filterOutputStream` 会把这件事藏进 `write` 的语义里 ——
     * 一旦有人在中间插一次 `flush`，"攒着的那段"就会被写两遍（表现为正文重复）。
     *
     * 所以判据留在**一个函数**里：`absorb` 返回什么就写什么，别处不写。
     */
    private fun relayOut(out: OutputStream, prefetch: RelayPrefetch,
                         lease: () -> SlotScheduler.Lease?): OutputStream =
        object : OutputStream() {
            // ⚠ 单字节这一档**必须**包成数组再走下面那个重载，不能写成
            //   `prefetch.absorb(...)` 的两份实现（两份分帧判断迟早漂移）。
            // ⚠ 也不能反过来：Java 里 `OutputStream.write(byte[], Int, Int)` 的默认
            //   实现是"逐字节回调 `write(Int)`"，所以数组那一档里若再调
            //   `write(byteArrayOf(b), 0, 1)` 就是两个重载互相递归 ——
            //   **`StackOverflowError`**。而它的症状正是 ISSUE #450 第十次现场：
            //   「relay 没有任何反应，日志也不提示」。`writeJson` 一个字节一个字节地写，
            //   第一条就栈溢出、抛在 `handleChat` 的 `catch` 里，而 relay 那一层
            //   的日志一条都还没轮到；直连不经过 `relayOut`，于是它"看起来能用"。
            //   判据：单字节 -> 数组 -> `absorb`，**单向**。
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                prefetch.reached(RelayStage.FIRST_WRITE)
                // 第一个字节进来才起心跳（见 [RELAY_KEEPALIVE_MS]）：这一轮**真的开始写了**
                // 才有「客户端在等」这件事可谈 —— 早于它开线程，等的是一段还不存在的响应。
                // ⚠ 心跳写的是**真连接**（`out`），不是 `prefetch.buf`：写进缓冲的那几帧
                //   会被 [RelayPrefetch.release] 当成上游响应体转发，非流式那一档还会让
                //   `pending` / `flushHeld` 的补齐整体错位（见 `beatOut` 的注释）。
                prefetch.startKeepAlive(out)
                // "我这一格"由写方喂给预读（心跳写失败时要靠它把取消打对地方，见
                // `slotAtGone`）。取值走调度器的表，不是 `lease.slot`（那是挑来的下标）。
                prefetch.noteSlotNow(SlotScheduler.currentSlotOf(lease()))
                val chunk = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
                // "放开直写"与"这次写"是**一个**决定（ISSUE #450 第十五次现场）：
                // 分成"先判、再写"两步的话，心跳/探针可能正好挤进那两步之间，
                // 于是它拿到的是"还没放开"的许可、写出去的那一帧却排在正文**中间**。
                //
                // ⚠ 响应头也在 `absorb` 的返回值里（顺序：头 → 正文），**只此一次**。
                //   这里以前还有一句 `prefetch.takeHead()` —— 它把同一个头又写了一遍，
                //   客户端在流开头读到两份 `HTTP/1.1 200 OK`，当场判协议错断流。
                prefetch.absorb(chunk, chunk.size)?.let { prefetch.writeLocked(out, it) }
            }
            override fun flush() { out.flush() }
        }

    /**
     * 把这一轮的响应写进账本（连同"这一轮喂进去的是什么形状"）。
     *
     * 为什么还要记 `chain`：**下一轮**进来时，唯一能验证"上一轮那份坐标还算不算数"的
     * 东西是"这一轮的原件开头，是不是上一轮留下的那一段"。账本自己记着那份快照不够 ——
     * 快照说的是"服务端那一轮怎么裁的"，说不清"客户端这一轮还认不认这份历史"。
     *
     * 所以这里顺手把这条指纹落下；比对与判据在 [RelayLedger.matchesChain] 一处
     * （它只做一件事：前缀还在不在）。**不在这里判"该不该删"** —— 那是 [RelayTrim] 的事。
     */
    private fun adoptLedger(session: String, chain: List<Pair<String, String>>, raw: String) {
        // 解析与落库在同一行：两处分开写时，"解析出来了但忘了 put" 会让整层静默退化成
        // 直连（下一轮永远没有输入），而每一格读数都正常。
        RelayLedger.parseRelayResponse(raw)?.let { RelayLedger.put(session, it, chain) }
    }

    /**
     * 把 `messages` 数组解析成 [Pair]（`content` 为数组时按 [ContentParts] 拼 text 段）。
     *
     * 形状认不出来时返回 `null`（**不抛**）：调用方据此原样转发，让 [handleChat]
     * 用同一套判据去报 400 —— 中间层不该替它决定"这条消息算不算合法"。
     *
     * ⚠ 这里**不**做非 text 段的 400：那是 [handleChat] 的判据（`ContentParts`），
     *   在中间层再写一遍就是两把尺子。非 text 段在这一步按"空正文"参与切轮，
     *   随后由 handleChat 当场拒掉 —— 顺序不影响结论（拒的就是同一个请求）。
     */
    /**
     * 一条 messages 数组的「正文顺序指纹」（每条：角色 + 正文）。
     *
     * 只服务账本自校（见 `RelayLedger.matchesChain`）：账本记的坐标描述的是**上一轮
     * 喂进去的那一份**，而中间层唯一的判据来源就是它。这一格让下一轮能问一句
     * 「那段历史还在原处吗」—— 不用它的话，"换了会话 / 用户删了历史"与"一切正常"
     * 在删的时候完全同形。
     *
     * ⚠ 与 [relayMessagesOf] 分开：那个解析失败要 `null`（"形状认不出来，原样转发"），
     *   这个只做指纹、失败给空表 —— 两件事的失败语义不同，混在一处会让"指纹缺失"
     *   被读成"请求不合法"。
     */
    private fun msgChain(arr: org.json.JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (k in 0 until arr.length()) {
            val m = arr.optJSONObject(k) ?: return emptyList()
            val ca = m.optJSONArray("content")
            out.add(m.optString("role", "") to
                (if (ca != null) ContentParts.textOf(ca) else m.optString("content")))
        }
        return out
    }

    private fun relayMessagesOf(arr: org.json.JSONArray): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (k in 0 until arr.length()) {
            val m = arr.optJSONObject(k) ?: return null
            val role = m.optString("role", "")
            if (role.isEmpty()) return null
            val ca = m.optJSONArray("content")
            val text = if (ca != null) ContentParts.textOf(ca) else m.optString("content")
            out.add(role to text)
        }
        return out
    }

    /**
     * 用 [msgs] 替换请求体里的 `messages`（其余字段原样保留）。
     *
     * 只动 `content` / `role` 两个字段，且**保持消息条数与角色**与 [RelayTrim] 的结论一致 ——
     * 不借这一步"顺手"改温度、改 tools、改任何别的东西。中间层要能一句话说清
     * "我改了什么"：只有 messages 里被删掉的那几轮。
     */
    private fun rewriteMessages(j: JSONObject, msgs: List<Pair<String, String>>): String {
        val arr = org.json.JSONArray()
        for ((role, text) in msgs) {
            arr.put(JSONObject().put("role", role).put("content", text))
        }
        j.put("messages", arr)
        return j.toString()
    }

    /**
     * `layoutDroppedRounds` = 调用方**已经替我们前置删掉了几轮**（只由 [handleRelayChat] 传非 0）。
     *
     * 直连那一档恒为 0 —— 它不是"另一个开关"，而是"这一份 messages 已经被中间层
     * 按上一轮的坐标裁过了"这个**事实**：剩下的这一段若仍越过水位，预算**照样**
     * 按水位往下丢（见 [ContextBudget.plan] 的 `layoutDroppedRounds`）。中间层下一轮
     * 会把这一轮累计删掉的轮数一起算进去（`RelayTrim.apply` 的 `carriedRounds + index`），
     * 不会重复删同一段。
     *
     * ⚠ 用形参而不是字段：每连接一线程，字段会被隔壁请求读到 —— 症状是"预算
     *   莫名其妙不丢历史了"，静默且只在并发下出现。
     *
     * ⚠ 签名从"必须单行"退到"`corsOrigin` 紧跟左括号那一段"：`tools/run_cors_guard.sh`
     *   锚的是**那个形参在签名里**（ISSUE #452 之后它已经多了两个形参，锚整行等于
     *   让它去管"签名长什么样"；此前那一版锚整行，加一个形参就红）。
     *   `corsOrigin` 在**第一个**形参组里，加新形参一律往后排 —— 折在它前面才是假警报。
     *
     * `prefetchRelay`（ISSUE #452「拿回真流式」）：**只有** `/v1/relay/...` 传得进来，
     * 直连恒 `null`。它让这一轮预读的那几行响应（头 / 收尾帧）先交给中间层去抽坐标，
     * 抽到就放开直写 —— 见 [RelayPrefetch]。为什么是形参而不是字段：每连接一线程，
     * 字段会被隔壁请求读到（症状是"直连的响应里混进了 relay 的预读"）。
     */
    private fun handleChat(req: Req, out: OutputStream, corsOrigin: String? = null,
                           layoutDroppedRounds: Int = 0,
                           prefetchRelay: RelayPrefetch? = null,
                           /**
                            * 拿到这一轮 lease 的那一刻把它交出去（只给 relay 出口用）。
                            *
                            * 存在的理由只有一个（ISSUE #450 第十三次现场）：relay 那一档
                            * 的心跳写失败时要"取消**这一条连接上**那一轮"。取消按来源找
                            * 格子会打到 App 头上（单格池里 `slotOfSource` 打不出唯一所指）。
                            *
                            * `null` = 直连（不需要，行为逐字节不变）。
                            */
                           onLease: ((SlotScheduler.Lease) -> Unit)? = null,
                           /**
                            * 这条连接的 `SocketChannel`（`null` = 拿不到）。
                            *
                            * 只给 prefill 探针用：[pollConn] 在它上面查"对端还在不在"，
                            * **不写一个字节** —— 这条链以前靠"往连接写一帧，写失败就判走了"，
                            * 而 prefill 期间那次写几乎必然会被 socket 的发送超时打断，
                            * 于是服务端把自己的那一轮取消掉了（ISSUE #450 第十五次现场）。
                            */
                           chan: java.nio.channels.SocketChannel? = null) {
        // ---- ① 模型没加载 -> 503（与"没 slot"是两件事，处置相反，文案必须分开）----
        if (!LlmEngine.hasModel) {
            writeJson(out, 503, errJson(
                "模型未加载：先在 App 内选择模型并启动服务"), corsOrigin); return
        }
        // ---- ② 向调度器要一个 slot（阶段 4 的接线点）----
        //
        // 这一步取代了原来的 `busy` CAS + `LlmEngine.isGenerating` 双判据：
        //   · 旧写法两个量**来源不同**且在多 slot 下含义已经漂了（见 §9.2.4 的注释），
        //     而"占位/回滚"还要分两次判、回滚只回滚自己那一份 —— 一处写错就是
        //     "两路并发写同一个 ctx"，而它是静默的；
        //   · 现在只有一处判据：[SlotScheduler.acquire]（占满返回 null，**不排队**），
        //     调用方在 finally 里 [SlotScheduler.release]，幂等。
        //
        // App 内聊天占 slot 0 那一份（阶段 5 接线）。HTTP 与 App **对等**，
        // 但优先级不同处：App 走的是它自己那条无背压的路径，谁也拒不了它。
        val lease = SlotScheduler.acquire(SlotScheduler.Source.HTTP)
        if (lease == null) {
            // 带原因的 503：说得出"是 no slot 还是 no model"，以及 slot 被谁占着。
            // 今天笼统一句「已有生成任务进行中」把这两件事糊在一起，而处置完全相反
            //（等一会儿 vs 去加载模型）。来源统计由调度器一处给出，不在这里另算。
            emitLog("503：${SlotScheduler.busyReason()}")
            writeJson(out, 503, errJson(SlotScheduler.busyReason()), corsOrigin); return
        }
        noteSingleCtxOnce(lease.slot)
        // 直连那一档 `onLease` 为 null（一个判据都不多，行为逐字节不变）。
        runCatching { onLease?.invoke(lease) }
        val slot = lease.slot
        try {
            val j = JSONObject(req.body)
            val stream = j.optBoolean("stream", false)
            // 采样参数统一走 SamplingParams 校验：非法值直接 400，不再静默降级（历史坑：
            // top_p=0 被静默关掉、repeat_penalty<1 语义反转、min_p 默认值与 UI 不一致）。
            // max_tokens 也在此处按 1..8192 校验（此前用 coerceIn 静默钳制）。
            // fromRequest 已逐字段校验并给出原因；返回 null 即取值非法，直接 400。
            // 协议字段（`max_completion_tokens` / `logit_bias` / `logprobs`）先解。
            // **必须在 SamplingParams 之前**：那是这一批里唯一"要同时看到两个字段名"
            // 的判据（`max_tokens` 与 `max_completion_tokens` 同时给出且取值不同 -> 400），
            // 而 SamplingParams 是逐字段结构、一次只看自己那一格。
            // 判据只有一处，就在 ProtocolFields（默认值/上限/文案都从那里引，不另抄）。
            val (pf, pfErr) = ProtocolFields.fromRequest(j)
            if (pf == null) { writeJson(out, 400, errJson(pfErr ?: "协议字段非法"), corsOrigin); return }
            // 每条 notice 是一句"客户端以为生效了、其实没有/部分生效"的说明，必须留痕
            // （`logit_bias` 超限被截断、`logprobs=true` 收下但不产出数据）。
            for (msg in pf.notices) emitLog(msg)
            ProtocolFields.describe(pf).takeIf { it.isNotEmpty() }?.let { emitLog("协议字段 $it") }
            val (sp, spErr) = SamplingParams.fromRequest(j, aliasMaxTokens = pf.maxTokens)
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
            // ⚠ 这里**不**留 `val maxTok = sp.maxTokens`：这一格的落值在预算之后
            //   （见 `val maxTok = budget.maxTokens`），而两条同名的线并存时，
            //   活下来的一定是被后写的那条 —— 那是"预算削了、循环不认"的老形状。
            val modelName = j.optString("model", "local").ifEmpty { "local" }

            val msgs = ArrayList<Pair<String, String>>()
            val arr = j.optJSONArray("messages")
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val role = m.optString("role", "user")
                var content = m.optString("content")
                val ca = m.optJSONArray("content") // 多模态数组：取 text 段拼接
                if (ca != null) {
                    // ⚠ 非 text 段（`image_url` / `input_audio` / `file` …）**不许静默丢**：
                    // 本服务没有视觉/音频通路（native 侧没有 mmproj），而 SDK 会照着
                    // 它自己的模型清单照发。旧写法只取 `type=="text"`，把图一个字不提地
                    // 扔掉 —— 客户端以为模型看过了，模型只看到一句没有图的话，两边都不报错。
                    // 判据只有一处（[ContentParts]），空 text 段仍按现状处理。
                    val bad = ContentParts.unsupported(ca)
                    if (bad != null) {
                        emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " 400：多模态内容不支持（" + bad + "）")
                        writeJson(out, 400, errJson(ContentParts.rejectMessage(bad)), corsOrigin); return
                    }
                    content = ContentParts.textOf(ca)
                }
                msgs.add(role to content)
            }
            if (msgs.isEmpty()) { writeJson(out, 400, errJson("messages is empty"), corsOrigin); return }

            // ---- 请求正文摘要（ISSUE #452）----
            //
            // 位置在**预算与知识库注入之前**：要回答的问题是「客户端这一轮送来的
            // 和上一轮是不是同一份」，所以量的是**客户端原件**，不是我们改过的。
            // 两轮 `head` 相同而 native 的 `head_cmp` 仍断掉 -> 变的是我们自己拼的
            // 那一段（system / 模板 / 注入块），与客户端无关。这正好补上 `fork_why`
            // 那句「两轮喂进去的不是同一份对话」一直答不出的另一半。
            //
            // 摘要而非明文：理由见 [RequestBodyDigest]（日志会轮转留在设备上）。
            RequestBodyDigest.describe("chat", msgs).takeIf { it.isNotEmpty() }?.let { emitLog(it) }

            // ---- 上下文预算（ISSUE #330）----
            //
            // 位置在**知识库注入之前、模板渲染之前**：预算要按"最终会喂进 prefill 的
            // 那一份 messages"算，而注入只动最后一条 user 的正文（长度会变）。
            // 放在渲染之后就得把渲染重做一遍，且工具路径与普通路径各要改一次。
            //
            // 为什么这一层必须有：客户端按别的模型/别处的数裁，而服务端每格是
            // `SlotScheduler.ctxPerSlot`（= 设置页那一格；第八回合起格数不再切它）。
            // 两边一旦不等（客户端按 8192 送、这一格只有 4096），它每轮都多送一倍，
            // 而我们此前**从不检查**，撞墙才失败。
            //
            // 裁的次序与 native（`ctxbudget.h`）同一条：保住首条 system 与本轮提问，
            // 从最老的一对开始丢 —— 每轮按同一份预算裁，前缀才会稳定在"被裁过的
            // 那个起点"，只在真正需要再丢一轮时才断一次 KV 复用。
            // 生成后缀占多少位置**不在消息里**（`<|im_start|>assistant\n` + 可能的
            // `<think>\n`）：模板与思考开关决定它，所以由渲染侧的真值给出，
            // 这里只按"有没有思考起始标记"取两档 —— 不自己拼一份形状，
            // 那份形状在 native（`classify_rendered_think_tail`），两处各写一份必然漂移。
            // 思考开关必须先于预算算出来：预算里的生成后缀按它取两档，而这一段的
            // 取值口径与后面渲染用的是**同一个** `resolve`（请求覆盖 > 全局默认）——
            // 写成两处各自 resolve，两个值一旦分叉，预算按"关"估、渲染按"开"渲。
            val thinkingOn = ThinkingControl.resolve(
                disableThinkingDefault, ThinkingControl.requestThinkingOverride(j))
            val chatTemplate = LlmEngine.chatTemplate()
            // 思考开关关掉、且启用的是**软开关**（模板自己不吐思考标记）时，
            // 末尾那段空 think 块是宿主补的 —— 它真实占位置。模板自己带
            // `enable_thinking` 时那段由模板渲进 prompt，形状取不到，按"会带"估。
            val genSuffix = ContextBudget.generationSuffixTokens(
                thinkingOn = thinkingOn,
                softInjected = ThinkingControl.softSwitchApplies(thinkingOn, chatTemplate))
            // ⚠ 预算**不在这里**算 —— 它挪到知识库注入**之后**（见下面那段）。
            // 顺序反过来的代价是 513~1000 字的注入块在预算里一个 token 都不占，
            // 而 native 按真 token 判：超了就从头部砍一刀，KV 前缀每轮断在同一处。

            // ---- 知识库注入（RAG 阶段 3：远端路径）----
            //
            // 位置在**渲染之前、工具解析之前**：注入只动最后一条 user 的正文，
            // 越早做越好 —— 之后所有步骤（工具感知模板、思考开关判定、长度统计）
            // 看到的都是**最终**那一份 messages。放在渲染之后就必须把渲染重做一遍，
            // 而且工具路径与普通路径各要改一次（两处判据必然漂移）。
            //
            // 「要不要注入」的判据**只有一处**（[RagInject.shouldSearchRemote] ->
            // [RagInject.shouldSearch]），与 App 内那条路径共用同一个函数 ——
            // 客户端无意间多发一个字节、或服务端多注入一个字节，都没有任何现象。
            //
            // ⚠ 与 `/v1/embeddings` **完全解耦**（用户口径 4）：那个端点不读这里的
            //   开关、也不受"有没有注入"影响。两条路唯一的共同点是都用嵌入模型，
            //   而那由各自的入口独立判断（缺嵌入模型时各自的错误语义也不同）。
            // ⚠ 这里必须走 [ragPrefs]（= `appContext`），**不能写 `this`**：
            //    `this` 在本文件里是 `HttpApi` 这个 **object 单例**，不是 `Context`，
            //    而 `RagPrefs.remoteKb/remoteEnabled` 的形参是 `android.content.Context` ——
            //    写成 `this` 直接编译不过（`/health` 与 [ragFieldOf] 里同样如此）。
            val remoteKb = RagPrefs.remoteKb(ragPrefs())
            val lastUserText = msgs.lastOrNull { it.first == "user" }?.second.orEmpty()
            // 先清零再算：`rag_remote_injected` 是"**上一轮**注入了几条"的读数
            // （与 kv_reuse_tokens 同一语义）。不清零的话，本轮没注入时它会
            // 继续报上一轮的数 —— 于是"这轮没注入"与"这轮注入了"读数相同，
            // 而读数存在的全部意义就是分辨这两者。
            lastRagInjected.set(0)
            lastRagKb = ""
            lastRagChars.set(0)
            // 注入块**每条定长**（`perItemChars`）：块长度一变，KV 前缀就断在块的头一枚 ——
            // 真机上"开知识库时复用有时有、有时没有"就是这么来的（不是缓存坏了）。
            // 长度按**每格容量**给（[ContextBudget.injectionChars]），不是拍一个常数：
            // 块再重要也不该吃掉半格上下文，而太短又连一句完整的话都装不下。
            // `items` 用 topK 而不是命中条数：检索之前就该定下来，否则"这一轮定长、
            // 下一轮不定长"会在**同一份代码里**产生两种块形状。
            // 宽度**按会话定一次**，不再按当前并发数每次重算（ISSUE #330 第 6 块）：
            // 宽度来自「每格容量 ÷ 4 ÷ 条数」，而每格容量会随设置页 ctx 变 ——
            // 改一次并发数，块里每一条都短一截，块之后那一段每轮都对不上，前缀断在块头。
            // 宽度是跨轮不变的东西，就不能由会变的量决定（见 [sessionInjectWidth]）。
            val rag = LlmEngine.ragContext(
                ragPrefs(), remoteKb, lastUserText, RagPrefs.remoteEnabled(ragPrefs()),
                perItemChars = sessionInjectWidth())
            if (rag != null) {
                // 与 App 路径**同一个助手**（`attachBeforeLastUser`）：消息角色序列一个字节不变，
                // 只在最后一条 user 的正文前拼上注入块。位置对了，KV 前缀复用的分叉点就落在
                // "本轮提问处"（放 system 段会让分叉点回到 pos 0、reuse 恒为 0）。
                val injected = RagInject.attachBeforeLastUser(msgs.toList(), rag.block)
                msgs.clear()
                msgs.addAll(injected)
                lastRagInjected.set(rag.count)
                // 注入块的**宽度**也落账（ISSUE #330 第 4 块）：它一变，本轮提问那一段
                // 就全变、前缀断在块头。以前这个数只在一条 uiLog 里，/health 上没有 ——
                // 于是"复用为什么这轮没了"永远要人去翻日志对它。
                lastRagChars.set(rag.block.length)
                // ⚠ 写的是 `rag.kb`（检索**当时**真正用的那个库名），**不是**
                //    到响应时再 `remoteKb(prefs)` 现读 —— 两者可能差一次用户改设置
                //    （长生成期间很容易发生），于是一对从未同时成立过的 `{kb, injected}`
                //    就发出去了。见 [lastRagKb] 的说明。
                lastRagKb = rag.kb
                emitLog("知识库注入（远端）: kb=${rag.kb} 条数=${rag.count} —— 本轮响应带 local_rag 字段，" +
                    "正文里带 ${RagInject.MARK} 锚点；关闭请在 App 内关「远端知识库」开关")
            } else if (ragActive()) {
                // 开关开着却一条没注入 —— 这是最需要留痕的一种：用户以为功能坏了。
                // 具体原因（没命中/索引需重建/嵌入未加载）已经由 ragContext 内部逐条打过日志，
                // 这里只补一句"远端这一轮没有注入"，让"开了但没注入"在日志上可判。
                emitLog("知识库：远端开关开着（kb=$remoteKb）但本轮 0 条注入（原因见上一行 [知识库] 日志）")
            }

            // ---- 上下文预算（ISSUE #330）----
            //
            // ⚠ 位置**必须**在知识库注入**之后**（四轮误判的收口处）。
            // 预算量的是"最终会喂进 prefill 的那一份 messages"，而注入块是拼在
            // 最后一条 user 正文前的、长度 513~1000+ 字的一段。放在注入之前算，
            // 那一段就**一个 token 都不占**：
            //   · 预算说"装得下、一条不丢"；
            //   · 渲染后进 native 的 prompt 比 `avail` 多出那块的长度；
            //   · native 按**真 token** 判 `prompt + gen > ctx-2`，从**头部**砍一刀；
            //   · KV 前缀起点每轮往后挪，复用只剩 system 段那几个 token。
            // 而它触不触发取决于这轮命中长条文还是短条文 —— 这就是"复用忽好忽坏"。
            //
            // 头部固定段（首条 system 正文 + 注入块）的**真实长度**单独量一遍交给预算
            // （[ContextBudget.plan] 的 `systemTokens`）：注入块拼在 user 上，
            // 而它改变的其实是"整段 prompt 的固定头部"，按 system 一侧扣最接近真相。
            //
            // 裁的次序与 native（`ctxbudget.h`）同一条：保住首条 system 与本轮提问，
            // 从最老的一对开始丢 —— 每轮按同一份预算裁，前缀才会稳定在"被裁过的
            // 那个起点"，只在真正需要再丢一轮时才断一次 KV 复用。
            val baseSystem = msgs.firstOrNull { it.first == "system" }?.second.orEmpty()
            // 预算**进来时**那一份（未裁）。下面算"丢了几轮 / 从哪一条起"要用它 ——
            // `budget.dropped` 只是条数，条数在"一条 user 后跟两条 assistant"的形状里
            // 除不出轮数，而坐标表（`local_rounds`）的 `i` 是轮序号。
            val budgetInput = msgs.toList()
            val budget = ContextBudget.plan(
                budgetInput, sp.maxTokens,
                // 容量取**每格**，与 native 的 `S.n_ctx` 同一把尺子（唯一真值是
                // `ctxPerSlot`；用 `capacity(contextSize(), slotCount())` 会把每格
                // 再按份除一次）。
                // ⚠ 但每格还不是"位置号能排到几"（ISSUE #426 真机
                //   `decode rc!=0 at offset 2048`）：两路各一条 seq 时，这一条 seq
                //   的容量是 `n_ctx / n_seq_max`，比整格小一半。客户端按整格放行的
                //   那一轮，prefill 排到 2048 就被库拒 —— 而两边都认为自己没错。
                //   所以位置那条线必须**另传**（见 `positionCap` 那个实参）。
                LlmEngine.ctxPerSlot(),
                genSuffix,
                // ⚠ 这里以前是 `SYSTEM_TOKENS_UNKNOWN` —— 而 `baseSystem` 就在上面几行
                // 算着、`ContextBudget.systemTokensOf` 就在手边，于是**注入块在这条路上
                // 一个 token 都不占**：预算说"装得下、一条不丢"，渲染后进 native 的
                // prompt 比 `avail` 多出整块的长度，native 按真 token 从头部砍一刀 ——
                // 复用只剩 system 段那几枚。这条窄带与 App 侧（那边早就传了）在同一次
                // 会话里就有两条不同的判据，而两边都认为自己没错（ISSUE #452）。
                ContextBudget.systemTokensOf(baseSystem),
                // 水位上限（ISSUE #426 方案 B）：用户可调的分档，默认 85%。
                // ⚠ 必须**现读设置项**，不能写成 `DEFAULT_WATERMARK_PCT`：
                //   直连与 relay 都走这一段，写死默认值等于"用户设 90%、日志里
                //   永远是 85%"—— 屏幕上按钮亮了、native 也按 90% 判，
                //   只有这一层按 85% 裁，两把尺子各说各话。
                ContextWatermark.get(ragPrefs()),
                // ⚠ 直连这条路**只做水位收紧、不做减半**（`shrinkOnly = true`）。
                //
                // 减半的前提是"我们丢掉的下一轮不会被原样送回来"—— App 内聊天满足它
                // （历史在本地），直连 HTTP 不满足：对端每轮都贴着它自己的 Max Context
                // 送整段历史，我们丢一半、下一轮它全送回来，于是**既丢不了、又多丢**。
                // 真正替直连减半的是 native `ctxbudget::plan` 里的 `toHalf`
                // （`ctx_budget.h`）：它面对的是真 token 数，且丢完之后账本就在服务端，
                // 下一轮不会被谁送回来。`kvlongchat::planKeep` **不在**这条路上
                // （`llama_jni.cpp` 拿到 `budget.trimmed > 0` 后直接 `startFor`）——
                // 别去那里找减半，也别在那边补一套。
                //
                // ⚠ **relay 那一档不 shrinkOnly**（`prefetchRelay != null`）：它的
                //   账本会把这一轮丢掉的轮次记成累计值，下一轮在客户端原件上先删掉
                //   同一段（见 `RelayTrim.apply`），所以"下一轮它全送回来"不再成立。
                //   主动治理打开时，relay 与 App 内聊天走**同一条**水位 + 半水位落点。
                shrinkOnly = prefetchRelay == null,
                // 位置那条线（每条 seq 的容量）。`<= 0` = 没量到，退回整格口径 ——
                // 单 seq 的盘上两个数同值，与改动前逐字节一致。
                positionCap = LlmEngine.seqCtxPerSeq(slot),
                // 估算系数（ISSUE #452 ⑤）：native 每轮量到的真 token 数把估算往下校准一档。
                // 只乘在**估算**上（真 token 那条路走 `promptTokensOverride`，不再乘），
                // 且只向下（0.70~1.00，见 `ContextBudget.PromptCalibration`）——
                // 拿恒偏多的数去"修正"偏多的估算，唯一安全的方向就是往下。
                estimateScale = LlmEngine.promptEstScale(LlmEngine.loadedPath.orEmpty()),
                // 主动治理开关（ISSUE #452 第 2 步）：默认**关** —— 客户端自己会裁，
                // 服务端只在真装不下（越过整格）时才让。水位仍在、仍上报，只是不再
                // 自己触发丢弃（见 `CtxProactivePref` 与 `ContextBudget.PROACTIVE_DEFAULT`）。
                proactive = CtxProactivePref.get(ragPrefs()),
                // 中间层前置删过几轮（`/v1/relay/...` 那一档；直连恒为 0）。
                // ⚠ 它**不再**关掉主动治理：那些轮次这一轮已经不在 `messages` 里，
                //   若剩下的历史仍越过水位，再丢一轮正是水位该做的事。中间层下一轮
                //   会把服务端这一轮报的 `index` 累加到 `carriedRounds` 上，删的是
                //   同一段累计轮次，不会重复删（见 `RelayTrim.apply`）。
                layoutDroppedRounds = layoutDroppedRounds)
            if (budget.overflow) {
                emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " 400：" + ContextBudget.describe(budget))
                writeJson(out, 400, errJson(ContextBudget.describe(budget)), corsOrigin); return
            }
            // 这一轮预算**丢了几轮**（轮序号口径，不是条数）。`budget.dropped` 是条数：
            // 一条 user 后跟两条 assistant 时 3 条只算 1 轮，两者在 `local_rounds`
            // 里必须分开（以前把条数当轮数传进去，中间层每轮都按双倍轮数删/判）。
            val budgetRounds = RoundGeometry.of(budgetInput).size
            val keptRounds = RoundGeometry.of(budget.messages).size
            val droppedRounds = (budgetRounds - keptRounds).coerceAtLeast(0)
            if (prefetchRelay != null) {
                // 中间层要的那一行 —— 与 App 内聊天的 `[ctx] …` 同一套信息：
                // 丢了几轮 / 几条、从哪一条（哪一轮、什么内容）起喂、水位多少、
                // 发出去多大、下一次什么时候动手。
                //
                // ⚠ 这一行走 `relayMark`：日志页与落盘文件**同时**有 —— 它正是
                //   "主动治理到底有没有跑"的唯一证据（ISSUE #450）。
                //   `describe(budget)` 里的水位来自这条路的 `plan(...)` 实参，
                //   必须是设置项那一档，不能是写死的默认值（否则用户设 90%
                //   而日志永远显示 85%）。
                val where = if (budget.dropped > 0) {
                    val from = budget.keptFrom.coerceIn(0, budgetInput.size)
                    val preview = budgetInput.getOrNull(from)?.second
                        ?.replace('\n', ' ')?.replace('\r', ' ')?.take(24).orEmpty()
                    "：${budgetInput.size} 条 → ${budget.messages.size} 条" +
                        "，从第 ${droppedRounds + 1} 轮（第 ${from + 1} 条）「$preview」起喂"
                } else "：${budgetInput.size} 条全进"
                relayMark("relay[${prefetchRelay.session}]：[ctx] " +
                    (if (layoutDroppedRounds > 0) "中间层已前置删 $layoutDroppedRounds 轮｜" else "") +
                    (if (budget.dropped > 0)
                        "本轮预算再丢最老 $droppedRounds 轮（${budget.dropped} 条消息）$where"
                     else "本轮预算没丢历史$where") +
                    "｜" + ContextBudget.describe(budget) +
                    "｜发出去这一段估算 ${budget.promptTokens} tok" +
                    ContextBudget.landingSummary(budget))
            }
            if (budget.dropped > 0) {
                emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " " + ContextBudget.describe(budget))
                msgs.clear()
                msgs.addAll(budget.messages)
            }
            // ── 生成上限以**预算的落值**为准（ISSUE #330 第 4 块）───────────────────
            //
            // `max_tokens` 是客户端的**意愿**，不是合同：越线那一轮预算会把它削到
            // "这一格还留得下的长度"（`Plan.maxTokens`，与 `gen_trimmed` 成对报出）。
            // 削过却不落下去，等于预算白算 —— 循环按 `sp.maxTokens` 跑满、
            // native 侧再自己削一刀，客户端看到的是"预算说 64、实际按 1024 跑"，
            // 而 `/v1/chat/completions` 与 `/v1/relay/chat/completions` **同走这一条**
            // （中间层做完前置删整轮之后，也是在同一个 `plan(...)` 里把这个数算出来的）。
            //
            // ⚠ 与 `/v1/completions` 那条路同一个口径（那边一直是 `budget.maxTokens`）：
            //   两条路各写一份就会漂，而漂出来的症状是"同一个客户端换个端点，回答长度不一样"。
            // ⚠ 兜底到 `sp.maxTokens`：`Plan.maxTokens` 为 0 只在"容量未知"(ctx<=0，即
            //   模型没加载 / 这一格连一个位置都留不出) 那一档，而 0 会让下面
            //   `if (n >= maxTok)` 在**第一步之前**就成立 —— 客户端拿到一条空回答、
            //   `finish_reason` 报 length，看起来像"模型哑了"。那一档该走的报错在
            //   别处（native 会回 400），不该被一个 0 抢先变成静默的空回答。
            val maxTok = if (budget.maxTokens > 0) budget.maxTokens else sp.maxTokens

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
            // `thinkingOn` / `chatTemplate` 已在上面预算那一步取好（同一份取值）。
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
            // 生成期互斥**按 slot**（不再是一把全局 genLock）：同 slot 排队、
            // 跨 slot 并行。全局锁会把多 slot 并发重新串行化 —— 那是"看着能跑、
            // 其实一路排队"的静默失效（见 LlmEngine.slotLocks 的注释）。
            synchronized(LlmEngine.slotLock(slot)) {
                LlmEngine.newSampler(sp.temp, sp.topP, sp.minP, seed = sp.seed,
                    topK = sp.topK, repPenalty = sp.repeatPenalty, penaltyN = sp.repeatLastN,
                    freqPenalty = sp.freqPenalty, presencePenalty = sp.presencePenalty,
                    stops = stops, responseFormat = respFormat, generationPrompt = genPrompt,
                    chatTemplateOverride = RequestContext.chatTemplateOf(chatTemplate),
                    // slot 一路带到 native（阶段 4 接线）。**每一处生成调用都带**：
                    // 漏一处就是那一步在 slot 0 上跑，而它的失效是静默的
                    //（另一路拿到串位的 token），正是本期最需要钉住的东西。
                    slot = slot,
                    // 思考开关必须与**渲染侧**同一个值：它决定库算出的生成后缀形状，
                    // 进而决定 GBNF 里思考块是"写死的"还是"可选的"。不透传时库按默认
                    // true 重算 PEG，与真 prompt 分叉 —— 而分叉的后果是 grammar
                    // **整个失效**（不是"少一点约束"），原因见 gbnf_from_json_schema 判据 ④。
                    thinkingOn = thinkingOn,
                    // logit_bias 的两个平行数组来自同一个 List，长度天然相等。
                    // 没给时两者都是 null -> native 不挂偏置采样器，链路逐字节同旧行为。
                    biasIds = ProtocolFields.biasIds(pf), biasVals = ProtocolFields.biasValues(pf))
                if (respFormat !is ResponseFormat.None)
                    emitLog("sampling ${sp.describe()} ${StopSequences.describe(stops)} " +
                            JsonSchemaFormat.describeGenPrompt(genPrompt))
                // 两个**只用于读数**的透传量（ISSUE #330 第 4 块）：
                //   · promptEst 是本轮**渲染后**这段 prompt 的估算 token 数，与 native
                //     tokenize 出来的真值并排进 /health —— 差值就是那条窄带的宽度；
                //   · lastRagChars 是本轮注入块的字符数（0 = 没注入）。
                // 它们**一个字节都不参与判据**：读数不许反过来改变服务端怎么切 KV。
                //
                // ⚠ 这一格以前传的是 `budget.promptTokens`，而那个量的是**消息**
                //   （字符 × 折算率 + 固定开销），不是这段渲染出来的 prompt。两者在英文上
                //   差 3~4 倍，于是真机读数长成 `prompt_real 2649 / prompt_est 4036` ——
                //   估算比真值还大，"我们高估白丢了历史"与"我们低估、native 砍了一刀"
                //   被糊成同一个数，而这两件事的处置完全相反。现在数的是**同一段文本**：
                //   有真值就直接用（分词一次），量不到才退回消息估算（那是估算的下限）。
                // ⚠ 估算那一格报的必须是**押注这一份真的会发出去的那一段**（`promptTokens`，
                //   裁完之后，已含估算系数）—— 它要和 native 的 `prompt_real` 描述同一份东西。
                //   校准分母也必须是它：`real / est` 只有两边同口径才是一个比值。
                //   （报"丢掉之前"那一份的那一版在 `/health` 上印过"估多了 3438 tok"，
                //   而 native 那边其实一路装得下；同一个差值还被校准当样本吃掉，
                //   系数压到 0.70、下一轮丢得更多 —— ISSUE #452。）
                val promptEst = budget.promptTokens
                // ⚠ prefill 是**一次阻塞的 JNI 调用**：这期间生成线程全在 native 里，
                //   下面那个「每 256 步探测一次」一步都跑不到，服务端对"客户端已经走了"
                //   完全无感（ISSUE #450 第三次现场：按了停止、客户端自己断流，服务端
                //   照旧算满）。所以这一段单独起一条探针，见 [PrefillProbe]。
                //
                // ⚠ 非流式也起（ISSUE #450 第六次现场：`abort_case` 一直是 `unowned`）。
                //   此前这里是 `if (stream) ... else null`，于是非流式请求在整个 prefill
                //   窗口里**一个探针都没有** —— 那一档唯一的取消入口只剩 `/v1/abort`，
                //   而它的读出是一句"上一格有人、但记不到它主人头上"，用户拿着这句话
                //   来问"下一步怎么办"（见 [unownedWhy]）。
                //   非流式**不写注释帧**（`comments = false`）：响应体是一整段 JSON，
                //   不该把"客户端肯不肯跳过响应体之前那几帧"当成判据；它那一档只保留
                //   节拍。所以这一处修的是"探针不再只在流式里存在"这件事的**接线**，
                //   不是"非流式也能探测" —— 后者要改协议面，不在本次范围。
                // ⚠ relay 那一档的注释帧交给**预读**：它已经在预读窗口里替上游垫
                //   心跳（[RelayPrefetch] 的 `beat`），两条链各写一遍就是双倍注释帧；
                //   而预读那一层还知道"现在这一帧到不到得了客户端"（它决定放开不放开）。
                // ⚠ **无条件起，一个判据都不许加**（ISSUE #450 第十一次现场）。
                //   曾经这里有两道门：`if (stream)`（#516 去掉）与
                //   `if (RequestCancel.isSlotBusy(slot)) null`（#533 加的）。后者按
                //   "这一轮登记过没有"来决定要不要探，而登记的时机与"prefill 跑没跑完"
                //   是两个不同的钟：
                //     · `attempt` 重试那一轮 —— 上一趟**已经返回过**（登记过）、
                //       prefill 却失败要重来，于是它的那次 prefill **一次都不被探**；
                //     · 关掉探针的后果方向只有一个：客户端在等待窗口里掉线，服务端
                //       收不到任何取消，prompt 照算满（单轮 25~62 秒），客户端**一个
                //       字都收不到**。这正是「#516 之后不能输出」那一档。
                //   而"两个线程写同一条 chunked 流"要防的是**分帧**，不是"起不起探针"：
                //   判据应当是"这条连接上此刻有没有别人在写"，由 [PrefillProbe] 自己
                //   守（[PrefillProbe.quiet] 静音 + "是不是这个 socket 唯一的主人"，
                //   见那边的注释）。关掉探针是把"可能抢写"换成"一定听不见对面走了"，
                //   这个交换在任何一侧都不划算。
                // ⚠ **prefill 窗口里一个字节都不往这条连接上写**（ISSUE #450 第十五次现场）。
                //
                //   此前这里是"每 800ms 写一帧 `: ping`，写失败就判客户端已离开"，而
                //   prefill 是**一次阻塞的 native 调用**：那两个线程在同一条 socket 上
                //   互相抢（`SO_SNDTIMEO` 一到就抛 `Write timed out` / `Would block`）。
                //   那个异常被当成"对端走了"，服务端当场取消这一轮 ——
                //   用户读到的是「我没点停止，它 abort 了，肯定没内容」（直连那一档），
                //   以及 relay 那一档「点了停止没效果」（取消打在了被自己写坏的那条连接上）。
                //
                //   现在探针只做**不碰写缓冲区**的那件事：`pollConn`（`POLLOUT|POLLRDHUP`，
                //   零超时）。它不写字节，所以不会被"对端读得慢"骗成断连；而对端真走了
                //   一定会现形（`POLLRDHUP` / `POLLHUP` / `POLLERR`）。
                //   代价说清楚：**本轮不再产生任何 `: ping`** —— 但那本来就不该由服务端承担：
                //   prefill 期间连接上确实没有数据，客户端的读超时该按"生成可能要一分钟"
                //   去设，而不是靠服务端喂注释帧（一帧被写坏的注释，比没有注释坏得多）。
                val prefillProbe = PrefillProbe(
                    out, lease, "http" + LlmEngine.slotTagLine(slot, req.logTag),
                    comments = stream,
                    sink = prefetchRelay?.let { p -> { f: ByteArray -> p.relayPing(f) } },
                    prefetch = prefetchRelay,
                    quietProbe = chan?.let { c -> { pollConn(c) } },
                ).also { it.start() }
                val err = try {
                    LlmEngine.startCompletion(
                        prompt, maxTok, slot, promptEst, lastRagChars.get(),
                        writer = LlmEngine.WRITER_HTTP)
                } finally {
                    // 收针要**无条件**做（含 prefill 失败那两条 400/异常路径），
                    // 否则它会一直对着一条已经不归它管的连接写。
                    // 它探到"客户端已经走了"时会**自己**把取消送到 native（见
                    // [PrefillProbe]），所以这里不需要它的返回值 —— 与下面那个
                    // `err != null` 的 400 完全是两件事。
                    prefillProbe?.close()
                }
                // 校准样本（ISSUE #452 ⑤）**必须在 prefill 之后**取：真 token 数是 native
                // 在丢头之前落的那一格，而 `strlen`/tokenize 在 prefill 里才算完。
                // 判据与读数都全不参与 —— 它只影响**下一轮**的估算系数。
                LlmEngine.observePromptReal(LlmEngine.loadedPath.orEmpty(),
                    LlmEngine.promptRealOf(slot), promptEst)
                if (err != null) {
                    // 取消**不是 400**（#450 第十四次现场）：400 对客户端意味着"请求写错了"，
                    // 而真相是"这一轮被停止 / 客户端已经走了，重发同一个请求即可"。
                    // 499 是 nginx 起的社区约定（Client Closed Request）：仍在 4xx 家族，
                    // SDK 会按"请求失败"处理、**不会**把错误体当成一次成功回答；
                    // 而 200 + error body 会骗过按状态码分支的客户端，更糟。
                    // 错误体的 `type`/`code` 也标成 request_cancelled，读日志的人不必
                    // 从中文文案里猜这是取消还是故障。
                    if (err.cancelled) {
                        emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) +
                                " 499（已取消）：" + err.message)
                        writeJson(out, 499,
                            errJson(err.message, "request_cancelled", "request_cancelled"),
                            corsOrigin)
                        return
                    }
                    // 其余失败也要落日志：这条路径以前**一个字都不进日志**，于是客户端拿着
                    // 一句话来问"这 400 是谁说的"，服务端日志里连痕都没有 ——
                    // 而"HTTP 入口按估算判死刑"与"native 按真 token 判装不下"的文案
                    // 长得很像，两种处置（调估算 / 调容量）完全相反。
                    emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " 400：" + err.message)
                    writeJson(out, 400, errJson(err.message), corsOrigin); return
                }
                // 登记取消归属 —— 必须在 startCompletion 之后（prefill 失败就没有轮次可取消），
                // 且整个生成期都在 finally 里摘除（异常路径同样要摘，否则会留下一个
                // 永远"在跑"的僵尸轮次，之后所有 /v1/abort 都打在一个不存在的对象上）。
                //
                // ⚠ 登记**在本轮自己的 slot 上**：同一 slot 上已有轮次时返回 null，
                // 而那意味着调度器把同一个 slot 发给了两路 —— 那是调度器的 bug，
                // 这里必须**立刻收工**而不是覆盖登记（覆盖 = 两路写同一个 ctx，静默）。
                // 格子取自调度器的表（见 [beginCancelableFor]）：`lease.slot` 是"挑来的"，
                // 它答不了"这一轮现在在哪一格"。
                val cancel = beginCancelableFor(lease)
                    ?: run {
                        emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " slot $slot 上已有轮次：拒绝并发写同一份 ctx")
                        writeJson(out, 503, errJson("slot $slot 已被占用（调度器与取消注册表不一致）"), corsOrigin)
                        return
                    }
                try {
                    val sb = StringBuilder()
                    val sbR = StringBuilder()
                    // 首帧那一份坐标（ISSUE #452「拿回真流式」）：必须**在进生成循环之前**
                    // 算好 —— 首帧就在循环前发出，而中间层拿到它才放开直写。所以这一格的
                    // 取值绝不能依赖"生成完了没有"：它只由**预算 + native 在 prefill 后落的
                    // 坐标**决定（`sent` / `kept` / `messages` / `rounds` 在 prefill 结束时就定了）。
                    //
                    // 以下四格**只算一次**，`coordHead` 与 `ctxField` 都读它们 ——
                    // 两个出海口各算一遍，就是"头里一个数、收尾帧另一个数"的来路。
                    val keptReal = LlmEngine.contextUsed(slot)
                    val roundsField = RoundGeometryField.of(budget.messages, droppedRounds)
                    val geom = LlmEngine.kvReuseGeomOf(slot)
                    // 起喂点只在本轮**动过手**时才有意义（`promptPrefixField` 自己判），
                    // 直连恒空串（`prefetchRelay == null`）—— 逐字节与改动前相同。
                    // ⚠ relay 的 `X-Relay-Coord` **不带** `prompt_prefix`（`prefixField = ""`）：
                    //   那一格是"服务端从客户端原文的第几个字符起喂"，只在本函数的
                    //   `ctxField` / `X-Local-Ctx` 里算（见下面那一处）。理由不是省字节：
                    //   `tok_start` 量的是"这一轮最终有没有丢"，而 relay 的头在生成之前就
                    //   下发了 —— 起喂点错一条就让中间层"少删一轮"（ISSUE #452 现场）。
                    //   中间层要的另外几格（`rounds` / 复用几何 / `kept`）不受这个限制：
                    //   它们本来就只在 prefill 之后才定，头与 `X-Local-Ctx` 读的是同一批值。
                    val prefixField = ""
                    // 本轮 `local_ctx`（服务端这一轮动了什么手）与它的**响应头**载体。
                    //
                    // ⚠ 它**不再进 OpenAI 响应体**（2026-10 兼容性修复）：`local_ctx` 不是
                    //   OpenAI 规范里的字段，拼在 `usage` 后面会让任何按 schema 校验的客户端
                    //   把整条响应判成非法。诊断信息改走 `X-Local-Ctx` —— 自定义响应头，
                    //   标准 OpenAI 客户端会直接忽略；关心的调用方仍能读到同一份数。
                    //   同时它仍然喂给 [noteRelayCoord]：relay 的账本读的就是这一份。
                    //
                    // ⚠ 必须在**进生成循环之前**算好：响应头在生成开始前就发出去了。
                    //   这几格只由「预算 + prefill 之后 native 落的读数」决定，生成期间不变
                    //   （`genStopOf` / `promptTrimmedOf` 都在 prefill 收尾时定值）。
                    val promptPrefixField =
                        if (prefetchRelay == null) ""
                        else ContextBudget.promptPrefixField(
                            LlmEngine.turnOriginOf(slot), budget.promptTokens, budget.droppedChars,
                            budget.bodyChars,
                            RoundGeometryField.tailRoundsOf(budget.messages, droppedRounds))
                    val ctxField = ContextBudget.localCtxField(
                        budget, keptReal,
                        LlmEngine.promptTrimmedOf(slot), LlmEngine.genStopOf(slot)[1],
                        // 复用几何（ISSUE #452 第 0 步）：`lcp` / `reuse` / `start` / `ledger`。
                        geom,
                        // 轮次坐标（ISSUE #452 第 2 步）：每轮问答在**实际喂进去的那一份**里
                        // 占哪一段、哪几轮已经被丢。
                        roundsField,
                        // 起喂点：只在 relay 那条路上算（直连的客户端用不上）。
                        promptPrefixField)
                    // 头里只放 `{...}` 对象体（键名在头名里，不必再重复一次）。
                    // 值全是单行 ASCII：JSON 的逗号/引号/花括号都合法出现在 HTTP 头值里。
                    val localCtxHeader =
                        if (ctxField.isEmpty()) ""
                        else "X-Local-Ctx: " + ctxField.substring(ctxField.indexOf("{")) + "\r\n"
                    // 头里那一份 = 首帧那一份 = 收尾帧那一份（**逐键同值**，起喂点那一格除外）：
                    // 三处各拼一次必然漂移，而漂移的表现是"头里说丢了 4 条、收尾说 5 条"，
                    // 中间层按头去裁、客户端按帧去读，两边都合法，谁也说不清。
                    //
                    // 所以坐标**只**从上面那四格里取（`ctxField` 与 `coordHead` 读同一批）：
                    //   · `prompt_prefix` / `rounds` / 复用几何 —— 中间层"该删哪几轮"的输入；
                    //   · `sent` / `kept` / `messages` / `ask_only` —— "服务端这一轮动没动手"
                    //     的最小输入。`kept_rounds` 只在复用几何有值时才拼，而"服务端真丢了
                    //     历史、却没接上前缀"（冷启动 / 换会话 / 裁剪后起点挪了）那几档几何
                    //     是空的 —— 那时头里一个字都不说，中间层下一轮退化成直连
                    //     （INV-#452 真机正是这一档：`relay_forwarded` 恒 0）。
                    //
                    // 另外两格 `kept_ctx_rounds` / `kept_ctx_dropped`：服务端这一轮**留下**几轮、
                    // 又**丢掉**几轮。两者相加（再加中间层自己记的删除量）才是"上一轮服务端
                    // 一共见到几轮" —— 中间层下一轮拿它与本轮客户端轮数比（见
                    // `RelayTrim.Prev.prevRoundCount`：只比 `rounds.size` 会每轮都判"历史被改过"）。
                    // ⚠ 取 `RoundGeometry.of(budget.messages).size` 而不是客户端原件的轮数：
                    //   这一份才是服务端真吃进去的形状，"中间层删掉的"由 `kept_ctx_dropped` 补。
                    val coordHead =
                        if (prefetchRelay == null) ""
                        else ContextBudget.coordHeadOf(budget, keptReal, roundsField, prefixField, geom,
                            RoundGeometry.of(budget.messages).size, droppedRounds)
                    // 头里那一份：非流式 / 以及"响应头先到、第一帧还在后面"那一档。
                    //
                    // ⚠ 值必须是**单行 ASCII**：HTTP 头里不能有裸换行，而 `local_ctx`
                    //   那一小段本来就是单行 JSON（`coordHeadOf` 只拼逗号与数字）。
                    //   所以这里只去掉可能的前导逗号，不做任何转义 —— 转义会让中间层
                    //   解析到一份"看起来像 JSON 的字符串"，而那是最难查的一种错。
                    val relayCoordHeaders =
                        if (coordHead.isEmpty()) ""
                        // `coordHead` 是 `,"local_ctx":{...}`，头里要的是 `local_ctx` 那个对象
                        // 的前缀 `local_ctx:`（去掉前导逗号，保留键名）—— 中间层按
                        // "`local_ctx` 前面退到最近的 `{`" 那条判据解析，键名必须在。
                        else "X-Relay-Coord: " + coordHead.substring(1) + "\r\n"
                    // 首帧（带坐标、正文为空的那一帧）是否已经出过这条流。
                    // 与下面的 `emitted` 分开：它**不**是"有正文下发过"，但它确实是
                    // "这条流上有过一帧" —— 心跳的首帧门槛要的正是后者（见 `lastBeat` 的注释）。
                    // 必须声明在 `if (stream)` **之前**：首帧就在那个块里发。
                    var firstFrameSent = false
                    if (stream) {
                        // 流式那一档的头里也带一份坐标：中间层只要**响应头**到手就能放开
                        // 直写，连"第一帧的往返"都不用等。`X-Local-Ctx` 是完整读数，
                        // `X-Relay-Coord` 是 relay 要的那一小份（两者都是头，不是正文）。
                        sseHead(out, corsOrigin, slot, relayCoordHeaders + localCtxHeader)
                        // 首帧是标准 OpenAI 形状（空 delta）——**不再挂 `local_ctx`**：
                        // 坐标已经在响应头里，中间层拿到头就能放开直写。
                        sseEvent(out, chatChunkJson(id, modelName, created, """{"role":"assistant","content":""}""", null))
                        // 首帧也是**一帧**：心跳的首帧门槛要认它（见进循环之后那一处的心跳注释）。
                        // 之前 `emitted` 只由 `emit()` 加，于是"已经发过一帧、只是它不带正文"
                        // 在心跳判据上等同于"一个字节都没发" —— 思考段因此一帧心跳都没有。
                        firstFrameSent = true
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
                    var lastBeat = System.nanoTime()  // G2：与判据同口单调钟，墙钟后跳会让心跳永不触发
                    // 上一次**断连探测**的时刻（与 `lastBeat` 分开：那个是心跳节流，
                    // 这个是探测节流 —— 两个节拍不同源，混用会让"探测太晚"重新出现）。
                    var lastProbe = lastBeat
                    // 带 tools 时**不实时下发 content**：工具调用语法（<tool_call> / [TOOL_CALLS] 等）
                    // 与正文同处一条输出流，边生成边发会把语法标记当正文吐给客户端。改为整段生成结束后
                    // 先按模板解析出 tool_calls，再决定发 tool_calls 增量块还是纯 content。
                    // reasoning_content 不受影响：思考段不会是工具调用。
                    val bufferContent = toolsJson != null && stream
                    fun emit(text: String, asReason: Boolean) {
                        if (text.isEmpty()) return
                        if (stream && (!bufferContent || asReason)) {
                            sseEvent(out, chatChunkJson(id, modelName, created,
                                if (asReason) """{"reasoning_content":${text.jsonQuoted()}}""" else """{"content":${text.jsonQuoted()}}""", null))
                            // 真发过一帧 —— 从现在起 "客户端空闲" 才成立，心跳才允许探测。
                            emitted++
                            lastBeat = System.nanoTime()
                        }
                    }
                    // [hitLimit] = "这一轮是被 `max_tokens` 掐断的"，供收尾处算 finish_reason。
                    // 判据锚在**循环怎么退出的**（体内那支置位后 break），不在收尾处反推
                    // `n >= maxTok` —— 后者会把"最后一步恰好吐出一个 token"与"最后一步是
                    // EOS"混成一谈。推导与优先级见 [FinishReason]。
                    var hitLimit = false
                    while (true) {
                        // 上限判定移到循环**体内最前面**：这样"为什么退出"只有一个来源 ——
                        // 这一支置 [hitLimit] 后 break，其余三个 break 各自是对的取值。
                        // 写成 `while (n < maxTok)` 的话，"跑满"这件事只剩一个**推导**
                        // （收尾处反推 `n >= maxTok`），而那个推导会把 EOS 也覆盖进去。
                        if (n >= maxTok) { hitLimit = true; break }
                        // 取消判定放在**取 token 之前**：已经生成的内容不回滚（SSE 已下发，
                        // 收不回来），但绝不再多算一步。取消后走与自然结束同一条收尾路径。
                        if (cancel.requested) break
                        val stepOut = LlmEngine.step(slot)
                        // `step()` 返回 null 有五类成因，只有"被上下文/生成上限掐断"
                        // 该报 `length`。判据取 native 那一步的结论（**按 slot** 读），
                        // 不在这里反推 `n >= maxTok` —— 后者会把"最后一步恰好落在上限上"
                        // 与"最后一步是 EOS"混成一谈（见 FinishReason 文件头）。
                        //
                        // 这就是本轮修的那个真实现象：上下文满了原来报 stop，
                        // 客户端于是把半截输出当完整回答（HTTP 200、不报错）。
                        if (stepOut == null) {
                            // `length` 收尾不是"历史作废"（ISSUE #414 第 2 档）：
                            // 它发生在**取值之前**那一拍，账本与 KV 本来就对齐，作废
                            // 它只会让下一轮把几千枚 prompt 重算一遍。
                            // `keepKvLedger` 只在**没人在按停止**时调 —— 用户取消走的是
                            // `abort_impl` 那条"只截尾巴"的路，两处各自判一次会互相盖掉
                            // （`cancel.requested` 是宿主侧的真值，native 的 `abort` 可能还没到）。
                            // ⚠ 顺序：先护账本、再读成因。前者动缓存语义，后者只读一格读数。
                            if (!cancel.requested) LlmEngine.keepKvLedger(slot)
                            if (LlmEngine.stepCtxExhausted(slot)) hitLimit = true
                            break
                        }
                        val piece = stepOut
                        n++
                        // 心跳：只在思考段（此时没有正文增量可发）打，防客户端空闲超时 ——
                        // 正常生成时每个 token 都有自己的 SSE 事件，再插心跳是多余的写。
                        // 顺带做一次断连探测（见 [heartbeat]），客户端走了就立刻取消，
                        // 不再白跑完剩下的 max_tokens。
                        //
                        // 首帧门槛内建在 `lastBeat` 的语义里（见它的注释）：思考段若从第一个
                        // token 就开始（openAtStart=true），在**第一条真实帧下发之前**绝不心跳 ——
                        // 那一刻流里只有响应头，探测写出的任何东西都会与 chunked 分帧竞争。
                        // ⚠ ISSUE #450 第十三次现场：判据 `emitted > 0` 对**首帧**不成立。
                        //   流式路径在进循环之前就发过首帧（`coordFirst` 那一帧空 delta，
                        //   带坐标、正文为空），而 `emitted` 只在 `emit()` 里加 —— 于是
                        //   整个思考段里 `emitted` 恒 0，心跳一帧都不打：客户端按自己的
                        //   空闲超时先断，服务端随后判 GONE 并取消这一轮。用户读到的是
                        //   「我没点停止，它 abort 了，肯定没内容」（直连那一档的原话）。
                        //   判据改成"这条流上**有没有任何一帧**出去过"：首帧也算。
                        //   非流式仍然是"一个字节都不写"（响应体是一整段 JSON），
                        //   由 `stream` 那一半挡住，与 `PrefillProbe(comments = stream)` 同一条。
                        // ⚠ 这里必须与 `lastBeat` 的**同一个钟**（`System.nanoTime()`）：`lastBeat` 是
                        //   nanoTime 赋的值，而纳秒量级（上电起算）拿墙钟（1970 起算、毫秒）
                        //   去减，差值是**恒负**的 —— 于是 handleChat 这一路的心跳
                        //   **永远不会触发**：思考段里没有真实帧、又发不出心跳，客户端的
                        //   空闲超时先把连接掐了（表现是"思考开时回答输出一小段就断"，
                        //   与 2026-09-20 那次断流长得一样，成因却完全不同）。
                        //   G2 这条守卫钉的正是这一处（`run_monotonic_clock_guard.sh`）。
                        if (stream && think.inThink && (emitted > 0 || firstFrameSent) &&
                            System.nanoTime() - lastBeat > 800_000_000L) {
                            heartbeat(out, cancel)
                            lastBeat = System.nanoTime()
                        }
                        if (piece.isNotEmpty()) think.feed(piece) { t, asReason ->
                            if (stream) emit(t, asReason)
                            else if (asReason) sbR.append(t) else sb.append(t)
                        }
                        // 同样带首帧门槛：前 256 步若一条真实帧都没发（思考段 / 带 tools 缓冲），
                        // 流里只有响应头，探测是与分帧竞争而不是探测（见 lastBeat 注释）。
                        // ⚠ 两条判据是"或"（ISSUE #450 第十四次现场）：
                        //   · 每 [CANCEL_PROBE_STEPS] 步 —— 快机器上别把连接写满；
                        //   · 每 [CANCEL_PROBE_MS] —— 慢机器上别拖十几秒才发现对端走了。
                        //   只有前一条时，真机「未点停止却 abort」那一档里，上一次探测
                        //   可能在十几秒之前，中间足够让中间环节把连接掐掉。
                        if (stream && emitted > 0 &&
                            (n % CANCEL_PROBE_STEPS == 0 || System.nanoTime() - lastProbe > CANCEL_PROBE_MS * 1_000_000L)) {
                            lastProbe = System.nanoTime()
                            if (probeOutbound(out, cancel)) break
                        }
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
                        if (r.isNotEmpty()) reasonField = "," + "\"reasoning_content\":" + r.jsonQuoted()
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
                        val ledger = LlmEngine.roundLedgerTokensOf(slot)
                        emitLog(LlmEngine.slotTagLine(slot, req.logTag) + "生成已被取消（客户端断连或 /v1/abort）：已生成 $n tok，提前收尾" +
                            "；引擎账本 $ledger tok" +
                            (if (ledger > n) "（有 ${ledger - n} tok 已进 KV 未下发，" +
                                "客户端按收到的文本拼回的历史会比引擎手里的短）" else ""))
                    }
                    // logprobs 字段片段：流式与非流式两条路径**共用这一个**（值恒为 null，
                    // 客户端没要就是空串 -> 逐字节同旧行为）。放在分支外算一次，
                    // 免得两条路径各算一次后漂移。
                    val lpField = logprobsFieldOf(pf.logprobs)
                    // 本轮的 `local_rag` 字段（没注入 -> 空串，一个字节都不多）。
                    // 三条响应路径共用同一处取值：这条字段最怕的就是"某条路径上没有"——
                    // 那正是它存在的意义的反面（调用方在该路径上永远看不到被注入了）。
                    val ragField = ragFieldOf(lastRagKb, lastRagInjected.get())
                    // ⚠ `local_ctx` 已在上面（响应头之前）算好：它现在只走 `X-Local-Ctx`
                    //   与 relay 账本，**不再拼进这里的响应体**。
                    if (stream) {
                        val fin = FinishReason.of(cancelled, hitLimit, toolCallsJson != null)
                        // 缓冲模式下补发正文（带 tools 但模型没调工具）。
                        // 下发字节同样是上面剥过围栏的那份 sb —— 与 !stream 路径同一处判据，
                        // 不在这里再判一次（两处判据必然漂移）。
                        if (bufferContent && toolCallsJson == null && sb.isNotEmpty())
                            sseEvent(out, chatChunkJson(id, modelName, created, """{"content":${sb.toString().jsonQuoted()}}""", null, lpField))
                        // 流式下按 OpenAI 约定发 tool_calls 增量块（index/id/type/function）。
                        // 已整段解析完成，故一次性下发而不是逐 token 拼装——客户端按 index 聚合，结果一致。
                        // id 兜底与 native 侧同一条规则：parse 结果缺 id 时用 call_<下标>，
                        // 不能在这里另写一份默认值，否则同一份数据在两条路径上会算出不同的 id。
                        if (toolCallsJson != null) {
                            for (delta in ToolCalls.streamDeltas(JSONArray(toolCallsJson)))
                                sseEvent(out, chatChunkJson(id, modelName, created, delta, null, lpField))
                        }
                        // `local_rag` 只挂在**收尾帧**上：流式下客户端按 delta 聚合正文，
                        // 额外字段挂在中间帧没有意义（它描述的是整轮），而收尾帧是每一条流
                        // 都必然会收到的那一帧（含被取消的轮次 —— 取消也要让调用方知道自己
                        // 这一轮其实被注入了，否则"被注入了又被我自己中断"这条读不到）。
                        // ⚠ `ragField` 必须**真的传进来**：它就是上面那句注释说的
                        // "收尾帧是每一条流都会收到的那一帧"。此前这一行漏了第七个实参
                        // （默认值 `""`），于是 `local_rag` 在流式路径上**一个字节都到不了**
                        // —— 而非流式那条路带得好好的，症状是"同一个接口两种模式读数不同"，
                        // 且不报错、不崩溃。接线守卫（`tools/rag_remote/check_wiring.py`
                        // 的判据⑤）正面钉住这个实参。
                        sseEvent(out, chatChunkJson(id, modelName, created, "{}", fin, lpField, ragField))
                        sseEvent(out, "[DONE]")
                        sseEnd(out)
                        // 台账：`ctxField` 已经不在响应体里，但 relay 的账本仍然读它
                        // （起喂点只有这一份有）。所以记账这一步**不能**跟着字段一起删。
                        noteRelayCoord(prefetchRelay, ctxField, coordHead)
                    } else {
                        val usage = usageJson(LlmEngine.contextUsed(slot), n)
                        val fin = FinishReason.of(cancelled, hitLimit, toolCallsJson != null)
                        // 有 tool_calls 时 content 按 OpenAI 规范可为 null
                        val contentField = if (toolCallsJson != null && sb.isEmpty()) "null" else sb.toString().jsonQuoted()
                        // 必须走 ToolCalls 重新包装：native 给的是解析器原生形状（只有 id/name/arguments），
                        // OpenAI 线上要的是 {"id":..,"type":"function","function":{..}}，
                        // 直接透传会让 SDK 认不出 tool_calls 而静默丢掉。
                        val toolField = if (toolCallsJson != null) ToolCalls.messageToolCallsField(JSONArray(toolCallsJson)) else ""
                        // 非流式：坐标走响应头（`X-Relay-Coord` / `X-Local-Ctx`），
                        // 正文保持标准 OpenAI 形状（`choices` / `usage` 之后不再追加字段）。
                        // 账本仍用带起喂点的 `ctxField` 记账。
                        noteRelayCoord(prefetchRelay, ctxField, coordHead)
                        writeJson(out, 200,
                            """{"id":"$id","object":"chat.completion","created":$created,"model":"$modelName",""" +
                            """"choices":[{"index":0,"message":{"role":"assistant","content":$contentField$reasonField$toolField}$lpField,"finish_reason":"$fin"}],"usage":$usage$ragField}""",
                            corsOrigin, slot = slot,
                            // 非流式没有"第一帧"可利用 —— 中间层等的是**响应头**。
                            // 所以坐标挂在这里：预读一看到头就放开直写，于是这一档
                            // 也不会让中间层白等（区别只是非流式本来就只有一次写）。
                            extraHeaders = relayCoordHeaders + localCtxHeader)
                    }
                } finally {
                    // 摘除取消归属。**必须在 emitLog 之前**：之后任何 /v1/abort 都应看到
                    // 「没有轮次在跑」，而不是打在一个已收尾的对象上。
                    LlmEngine.endCancelable(cancel)
                }
            }
            emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " chat ok: $n tok, model=$modelName")
            LlmEngine.probeMark("[http] handleChat 正常结束 n=$n")
        } catch (t: Throwable) {
            LlmEngine.probeMark("[http] handleChat 抛出：${t.javaClass.name}: ${t.message}")
            try { writeJson(out, 500, errJson(t.message ?: "internal error"), corsOrigin) } catch (_: Exception) {}
        } finally {
            // 归还 slot。**必须在 finally**：取消 / 异常 / 正常三条路径都要还，
            // 漏一次这个 slot 就永久占着（之后每个请求都 503，而服务看起来"没坏"）。
            // [SlotScheduler.release] 幂等（迟到的 leave 不许清掉别人刚拿走的同一个下标）。
            SlotScheduler.release(lease)
        }
    }

    private fun handleCompletion(req: Req, out: OutputStream, corsOrigin: String? = null) {
        // 与 handleChat 同一套（见那边的注释）：模型未加载 / 没 slot 是两个不同的 503，
        // 而"要 slot"只有 [SlotScheduler.acquire] 一条判据。
        if (!LlmEngine.hasModel) {
            writeJson(out, 503, errJson("模型未加载"), corsOrigin); return
        }
        val lease = SlotScheduler.acquire(SlotScheduler.Source.HTTP)
        if (lease == null) {
            emitLog("503：${SlotScheduler.busyReason()}")
            writeJson(out, 503, errJson(SlotScheduler.busyReason()), corsOrigin); return
        }
        noteSingleCtxOnce(lease.slot)
        val slot = lease.slot
        try {
            val j = JSONObject(req.body)
            val stream = j.optBoolean("stream", false)
            // 与 /v1/chat/completions 同一套解析与校验，默认值不会两边漂移
            // fromRequest 已逐字段校验并给出原因；返回 null 即取值非法，直接 400。
            // 与 /v1/chat/completions 同一套解析与顺序（理由见 handleChat 同处）：
            // 协议字段先解 -> 再解采样参数（别名 max_completion_tokens 的结果传进去）。
            val (pf, pfErr) = ProtocolFields.fromRequest(j)
            if (pf == null) { writeJson(out, 400, errJson(pfErr ?: "协议字段非法"), corsOrigin); return }
            for (msg in pf.notices) emitLog(msg)
            ProtocolFields.describe(pf).takeIf { it.isNotEmpty() }?.let { emitLog("协议字段 $it") }
            val (sp, spErr) = SamplingParams.fromRequest(j, aliasMaxTokens = pf.maxTokens)
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
            // ⚠ 这里不再留 `maxTok`：这条路的生成长度**只有**预算的落值（见下面 `genTok`）。
            //   留一份原件在手里，两条路各自取一个数，就会重演「预算说 64、循环跑到 1024」。
            val modelName = j.optString("model", "local").ifEmpty { "local" }
            val prompt = when (val p = j.opt("prompt")) {
                is String -> p
                is JSONArray -> (0 until p.length()).mapNotNull { p.opt(it) as? String }.joinToString("\n")
                else -> ""
            }
            if (prompt.isEmpty()) { writeJson(out, 400, errJson("prompt is empty"), corsOrigin); return }
            // 与 chat 那条路同一份摘要口径（ISSUE #452）：裸 prompt 当成**一条** user，
            // 与预算侧对它的口径一致 —— 两边若各记一种形状，摘要就没法横着比。
            RequestBodyDigest.describe("cmpl", RequestBodyDigest.pairsOfPrompt(prompt))
                .takeIf { it.isNotEmpty() }?.let { emitLog(it) }
            // `echo` / `suffix` 与上面那批同一套原则：**协议级错误报 400**，不静默忽略。
            // 此前这两个字段一个都没被读，于是 `echo: true` 与 `echo: false` 拿到的是
            // 同一份 text —— 客户端以为自己拿到了 prompt + completion（见 CompletionEcho 文件头）。
            val (echo, echoErr) = CompletionEcho.fromRequest(j)
            if (echoErr != null) { writeJson(out, 400, errJson(echoErr), corsOrigin); return }
            // 回显的就是**喂进 prefill 的那一整串**：JSONArray 形式的 prompt 已在上面
            // 用 `\n` 连成一段，回显若换一种拼法，客户端「text 减 prompt」就会切错位置。
            val echoPrefix = CompletionEcho.echoPrefix(prompt, echo)
            if (echoPrefix != null)
                emitLog("[id=$reqTag] echo=true：text 前置 prompt ${echoPrefix.length} 字符" +
                    "（usage 不变，仍只报真实 prefill 的 token 数）")

            // ---- 上下文预算（ISSUE #452 第 1 步）----
            //
            // 这条端点此前**一个判据都没有**：`prompt` 一路直送 `startCompletion`，
            // 于是它既没有水位/丢历史的宽限、也没有 `local_ctx` 读数，越线那轮只能
            // 由 native 那一刀来表现（从头部砍 + 减半），文案还是 native 版。
            // 而 `/v1/chat/completions` 有 —— 两个生成端点对同一件事给出两种行为。
            //
            // 这里的口径与 chat 那条路**同一套判据、同一个 plan**，但只消费三样东西：
            //   · `overflow`  -> 与 chat 同一句 400（`ContextBudget.describe`）；
            //   · `maxTokens` -> 替代裸 `max_tokens`（预算削过的生成长度要真落下去）；
            //   · 整个 `Plan` -> 收尾时拼 `local_ctx`（客户端看得见"丢了多少"）。
            //
            // ⚠ 裸 prompt **不减半**（`shrinkOnly = true`）：这条路本来就没有"组"可丢，
            //   "丢一半"唯一能落的对象就是这段 prompt 自己 —— 而那等于把用户的输入
            //   扔掉一半再让他付一次 400 的钱。真正减半的是 native 的 `ctxbudget::plan`。
            //
            // ⚠ 估算口径是**真 token 数优先**：`prompt` 是渲染好的原文（不是消息表），
            //   能直接 tokenize 就不该用字符折算（英文上两者差 3~4 倍，而低估会把
            //   这一轮推给 native 从头部砍）。量不到才退回"一条 user 消息"的估算。
            val measuredPrompt = LlmEngine.countPromptTokens(prompt)
            val budget = ContextBudget.plan(
                listOf("user" to prompt), sp.maxTokens,
                LlmEngine.ctxPerSlot(),
                // 裸补全没有生成后缀（模型直接续写），这份不是"未知"是"确认没有"。
                generationSuffixTokens = 0,
                systemTokens = ContextBudget.SYSTEM_TOKENS_UNKNOWN,
                ContextWatermark.get(ragPrefs()),
                shrinkOnly = true,
                positionCap = LlmEngine.seqCtxPerSeq(slot),
                promptTokensOverride = if (measuredPrompt > 0) measuredPrompt else -1,
                // 估算系数与 chat 那条路同一个值、同一个来处（ISSUE #452 ⑤）。
                // 这条路上它是**兜底**：量得到真 token 时 `promptTokensOverride` 才是判据，
                // 而量不到（未加载 / 空串）时退回的正是这里校准过的估算。
                estimateScale = LlmEngine.promptEstScale(LlmEngine.loadedPath.orEmpty()),
                // 主动治理开关与 chat 那条路**同一个来处**（ISSUE #452 第 2 步）：
                // 这条路的 `prompt` 是调用方自己拼的，它比 App 更可能"不知道自己多长"，
                // 但风险方向一样 —— 估算偏高带来的早丢，比 native 那一刀更贵。
                proactive = CtxProactivePref.get(ragPrefs()))
            if (budget.overflow) {
                emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " 400：" + ContextBudget.describe(budget))
                writeJson(out, 400, errJson(ContextBudget.describe(budget)), corsOrigin); return
            }
            if (budget.dropped > 0 || budget.genTrimmed > 0)
                emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " " + ContextBudget.describe(budget))
            // 预算削过的生成长度要真落下去（chat 那条路一直是这么做的：生成循环的上限
            // 只能有一个来源，两个数各取一半时"为什么只答了 64 tok"就再也答不出来）。
            val genTok = budget.maxTokens

            val created = System.currentTimeMillis() / 1000
            var n = 0

            // 与 handleChat 同源：生成期互斥**按 slot**（跨 slot 并行、同 slot 排队）。
            synchronized(LlmEngine.slotLock(slot)) {
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
                        ThinkingControl.resolve(disableThinkingDefault, null)),
                    // 与 handleChat 同源（见那边的说明）；两个端点对同一字段给同一个答案。
                    biasIds = ProtocolFields.biasIds(pf), biasVals = ProtocolFields.biasValues(pf),
                    // slot 一路带到 native（与 handleChat 同源，见那边的说明）。
                    slot = slot)
                if (respFormat !is ResponseFormat.None)
                    emitLog("sampling ${sp.describe()} ${StopSequences.describe(stops)} " +
                            JsonSchemaFormat.describeGenPrompt(genPrompt))
                // 裸补全这条路上**没有**知识库注入（不读 RAG 开关），所以块宽恒为 0；
                // prompt 的估算值与 chat 那条路用**同一个口径**（上面那段预算算出来的
                // 就是它，有真 token 时以真值为准）。`/health` 上 `prompt_est` 因此不再
                // 对这条路径恒报 0 —— 0 是"这一轮没有这个数"，而这条路一直有。
                // 估算那一格与 chat 同一个口径：**裁完之后那一份**的估算（已含估算系数），
                // 因为它要与 native 数出来的 `prompt_real` 描述同一段文本 —— 两个数分开报，
                // `/health` 上的 `prompt_real / prompt_est` 才是同一问题的两把尺子。
                // ⚠ 这条路 `shrinkOnly`，`dropped` 恒 0，口径差在这里不显形；两条路仍用
                //   同一个表达式，免得下次谁照着这条改回去。
                val promptEst = budget.promptTokens
                val err = LlmEngine.startCompletion(
                    prompt, genTok, slot, promptEst, 0,
                    writer = LlmEngine.WRITER_HTTP)
                LlmEngine.observePromptReal(LlmEngine.loadedPath.orEmpty(),
                    LlmEngine.promptRealOf(slot), promptEst)
                if (err != null) {
                    // 与 handleChat 同一套：取消回 499，其余仍是 400（理由见那边的注释）。
                    if (err.cancelled) {
                        writeJson(out, 499,
                            errJson(err.message, "request_cancelled", "request_cancelled"),
                            corsOrigin)
                    } else {
                        writeJson(out, 400, errJson(err.message), corsOrigin)
                    }
                    return
                }
                // 归属登记与 handleChat 同一套（见 [beginCancelableFor] 的注释）。
                val cancel = beginCancelableFor(lease)
                    ?: run {
                        emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " slot $slot 上已有轮次：拒绝并发写同一份 ctx")
                        writeJson(out, 503, errJson("slot $slot 已被占用（调度器与取消注册表不一致）"), corsOrigin)
                        return
                    }
                try {
                    val sb = StringBuilder()
                    // 与 handleChat 同一套语义：lastBeat 记「上次真实下发时刻」，emitted 作首帧门槛。
                    var emitted = 0
                    var lastBeat = System.nanoTime()  // G2：与 handleChat 同一口径
                    var lastProbe = lastBeat   // 断连探测的节流（见 handleChat 里同一处）
                    // `local_ctx` 必须在**响应头之前**算好：它现在只走 `X-Local-Ctx`
                    // 响应头（OpenAI 兼容：正文里不再出现非标准字段）。几格都取自
                    // prefill 之后的 native 读数，生成期间不变。
                    val ctxField = ContextBudget.localCtxField(
                        budget, LlmEngine.contextUsed(slot),
                        LlmEngine.promptTrimmedOf(slot), LlmEngine.genStopOf(slot)[1],
                        LlmEngine.kvReuseGeomOf(slot),
                        // 裸补全没有"轮"（一条 user 就是全部），`dropped` 是**条数**；
                        // 拿条数当轮数会让 `local_rounds` 把一条 prompt 报成被丢的一轮。
                        RoundGeometryField.of(budget.messages, 0))
                    val localCtxHeader =
                        if (ctxField.isEmpty()) ""
                        else "X-Local-Ctx: " + ctxField.substring(ctxField.indexOf("{")) + "\r\n"
                    if (stream) {
                        sseHead(out, corsOrigin, slot, localCtxHeader)
                        // 回显段作为**首帧**立刻下发（见 CompletionEcho.headPiece）：
                        // 让 text 的拼接顺序与非流式那条路完全一致，同时避免
                        // 「prompt 很长 + 首 token 很慢」被客户端的空闲超时断开。
                        CompletionEcho.headPiece(echoPrefix)?.let {
                            sseEvent(out, compChunkJson(id, modelName, created, it.jsonQuoted(), null))
                            emitted++
                            lastBeat = System.nanoTime()
                        }
                    }
                    // 与 handleChat 同一套：上限判定在体内最前，「跑满」这件事只有一个来源。
                    // 裸补全没有 tool_calls，[hitLimit] 之外只剩 cancelled / stop 两档。
                    var hitLimit = false
                    while (true) {
                        // 上限只有一个来源：预算削过之后的值（`genTok`）。用裸 `maxTok`
                        // 会让"预算说只能答 64"与"循环跑到 1024"同时成立，而后者永远先停。
                        if (n >= genTok) { hitLimit = true; break }
                        if (cancel.requested) break
                        val stepOut = LlmEngine.step(slot)
                        // `step()` 返回 null 有五类成因，只有"被上下文/生成上限掐断"
                        // 该报 `length`。判据取 native 那一步的结论（**按 slot** 读），
                        // 不在这里反推 `n >= maxTok` —— 后者会把"最后一步恰好落在上限上"
                        // 与"最后一步是 EOS"混成一谈（见 FinishReason 文件头）。
                        //
                        // 这就是本轮修的那个真实现象：上下文满了原来报 stop，
                        // 客户端于是把半截输出当完整回答（HTTP 200、不报错）。
                        if (stepOut == null) {
                            // 与 handleChat 同一处、同一理由（ISSUE #414）：
                            // `length` 收尾不是"历史作废"，账本与 KV 这一拍本来是对齐的。
                            if (!cancel.requested) LlmEngine.keepKvLedger(slot)
                            if (LlmEngine.stepCtxExhausted(slot)) hitLimit = true
                            break
                        }
                        val piece = stepOut
                        n++
                        // /v1/completions 没有 think 状态机，心跳纯粹为了断连探测：
                        // 这个端点常见于"测速"长跑（max_tokens 拉满），客户端中途离开
                        // 却白跑完是最浪费的一类场景。同样带首帧门槛（见 handleChat 的注释）。
                        if (stream && emitted > 0 && System.nanoTime() - lastBeat > 800_000_000L) {
                            heartbeat(out, cancel)
                            lastBeat = System.nanoTime()
                        }
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            if (stream) {
                                sseEvent(out, compChunkJson(id, modelName, created, piece.jsonQuoted(), null))
                                emitted++
                                lastBeat = System.nanoTime()
                            }
                        }
                        // 同样带首帧门槛：前 256 步若一条真实帧都没发（思考段 / 带 tools 缓冲），
                        // 流里只有响应头，探测是与分帧竞争而不是探测（见 lastBeat 注释）。
                        // ⚠ 两条判据是"或"（ISSUE #450 第十四次现场）：
                        //   · 每 [CANCEL_PROBE_STEPS] 步 —— 快机器上别把连接写满；
                        //   · 每 [CANCEL_PROBE_MS] —— 慢机器上别拖十几秒才发现对端走了。
                        //   只有前一条时，真机「未点停止却 abort」那一档里，上一次探测
                        //   可能在十几秒之前，中间足够让中间环节把连接掐掉。
                        if (stream && emitted > 0 &&
                            (n % CANCEL_PROBE_STEPS == 0 || System.nanoTime() - lastProbe > CANCEL_PROBE_MS * 1_000_000L)) {
                            lastProbe = System.nanoTime()
                            if (probeOutbound(out, cancel)) break
                        }
                    }
                    val cancelled = cancel.cancelled
                    if (cancelled) {
                        // E-4 的可观测面（**不回滚**）：取消那一拍，引擎侧可能有一个 token 已经
                        // 走进 KV 与账本，而它的 SSE 帧还没发（或被 ThinkStream 暂存、被 tools 缓冲）。
                        // 客户端若按"我收到的文本"拼回历史，两边就会短一截 —— 重发同一 prompt 时
                        // 引擎不会重复计算（对），但客户端拼的对话与引擎手里的对不上。
                        // 这里只把差额记下来，让人**查得到**：回滚要引入新分支与新失效模式，
                        // 收益只是"少算一个 token"。与 handleChat 同一处对账（判据同源）。
                        val ledger = LlmEngine.roundLedgerTokensOf(slot)
                        emitLog(LlmEngine.slotTagLine(slot, req.logTag) + "生成已被取消（客户端断连或 /v1/abort）：已生成 $n tok，提前收尾" +
                            "；引擎账本 $ledger tok" +
                            (if (ledger > n) "（有 ${ledger - n} tok 已进 KV 未下发，" +
                                "客户端按收到的文本拼回的历史会比引擎手里的短）" else ""))
                    }
                    // ⚠ `local_ctx` 已在生成循环之前算好：它只走 `X-Local-Ctx` 响应头，
                    //   **不拼进响应体**（OpenAI 兼容）。
                    if (stream) {
                        sseEvent(out, compChunkJson(id, modelName, created, "\"\"",
                            FinishReason.of(cancelled, hitLimit, hasToolCalls = false)))
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
                        val usage = usageJson(LlmEngine.contextUsed(slot), n)
                        val fin = FinishReason.of(cancelled, hitLimit, hasToolCalls = false)
                        val lpField = logprobsFieldOf(pf.logprobs)
                        // echo=true 时把 prompt 接在正文前（CompletionEcho.joinText：prefix 为 null
                        // 时原样返回，与引入本特性之前逐字节相同）。**usage 不动**：
                        // 回显是形状问题，不是"又生成了这些 token"。
                        val textOut = CompletionEcho.joinText(echoPrefix, sb.toString())
                        writeJson(out, 200,
                            """{"id":"$id","object":"text_completion","created":$created,"model":"$modelName",""" +
                            """"choices":[{"index":0,"text":${textOut.jsonQuoted()}$lpField,"finish_reason":"$fin"}],"usage":$usage}""",
                            corsOrigin, slot = slot, extraHeaders = localCtxHeader)
                    }
                } finally {
                    LlmEngine.endCancelable(cancel)
                }
            }
            emitLog("http" + LlmEngine.slotTagLine(slot, req.logTag) + " completion ok: $n tok")
        } catch (t: Throwable) {
            try { writeJson(out, 500, errJson(t.message ?: "internal error"), corsOrigin) } catch (_: Exception) {}
        } finally {
            // 与 handleChat 同源：finally 里归还（见那边的注释）。幂等。
            SlotScheduler.release(lease)
        }
    }

    /**
     * 登记本轮的取消归属 —— **格子取自调度器的表，不取 `lease.slot`**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不能直接用 `lease.slot`（ISSUE #403：slot 1 的停止失效）
     * ══════════════════════════════════════════════════════════════════════
     * `acquire()` 的动作是"**挑**一个空位"：它签的是"这一刻的第一个空格"，
     * 不是"这一轮永远在第 i 格"。单 slot（默认档）时它挑的就是 slot 0 ——
     * 与 App 内聊天争来的保留位无关（保留位只在 `total > 1` 时才让开，
     * 那一条不能退，退了单 slot 的 HTTP 全 503）。
     *
     * 于是「HTTP 那一路的 `lease.slot`」在池子收窄 / 前面那一路刚还回来时会变，
     * 而 `beginCancelable(lease.slot)` 与 `/v1/abort` 都按它打 —— 打空的那个槽
     * 不会报错，只是**取消没生效**：客户端自己断了（`Fetch request has been
     * canceled`），服务端照旧把 `max_tokens` 跑完。这正是用户报的那一幕。
     *
     * 判据收在一处：[SlotScheduler.currentSlotOf]（`owner === lease`，与 `release`
     * 同一个真值）。取不到（`-1`，说明这一格已经被别人接管或本来就还了）时
     * **立刻收工**并回一句可读的 503 —— 覆盖登记会变成两路写同一份 ctx（静默）。
     *
     * ⚠ 这条 503 只在**记账真的不一致**时该出现。它曾经对所有 HTTP 请求都出现：
     * 那时 `currentSlotOf` 对 `acquire()` 挑来的 lease 一律回 -1，而 HTTP 两个端点
     * 走的全是 `acquire()` —— 于是每个请求都撞这一句，客户端看到的是"服务坏了"。
     * 判据本身没错，错的是把"挑来的下标"理解成了"问不出格子"。
     */
    private fun beginCancelableFor(lease: SlotScheduler.Lease): RequestCancel.Token? {
        val real = SlotScheduler.currentSlotOf(lease)
        if (real < 0) {
            emitLog("http slot 记账不一致：lease 的下标 ${lease.slot} 已不是本轮的（拒绝开跑）")
            return null
        }
        // ⚠ 这一格上若已经有**同一个 lease** 的登记，先摘一次再登记。
        //
        // 为什么会撞上：`startCompletion` 的返回码允许重试，而 native 启动失败时
        // 那一轮并没有跑起来（没有轮次可取消）—— 但上一轮的 token 若没被摘除，
        // `RequestCancel.enterSlot` 会一直拒到进程重启。这个"脏登记"的处置只能是
        // "同一份 lease 在同一格上重来一次"，**不能**是"覆盖别人的"：lease 的身份
        // 就是这一轮的身份，`abandonCancelOwnedBy(lease)` 只摘登记过的那一份。
        RequestCancel.abandonCancelOwnedBy(lease)
        return LlmEngine.beginCancelable(real, owner = lease)
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
    /**
     * ⚠ 阶段 4（§9.2.6）：探测**不在这里查"当前是谁在跑"**。
     *
     * 「判 GONE 之后取消哪一路」由 `cancel` 上记的 slot 决定（[RequestCancel.Token.slot]，
     * 在 `LlmEngine.beginCancelable(slot)` 那一刻绑好），[LlmEngine.abortRound] 只消费它。
     * 写成"在探测路径上再查一次当前轮次"是单并发时代的写法 —— 多 slot 下那个查法
     * 会打到别人那一路上（用户看到别人的回答被掐断），而这条路径是**异步**触发的，
     * 它触发的那一刻"当前是谁在跑"很可能已经换人了。
     */
    private fun probeOutbound(out: OutputStream, cancel: RequestCancel.Token): Boolean {
        if (cancel.requested) return true
        // **非流式一个字节都不写**（与 [PrefillProbe] 的 `comments = false` 同一条判据）：
        // 那一档的响应体是一整段 JSON + `Content-Length`，往里插一帧 SSE 注释会把
        // 这次响应写坏（客户端按 JSON 解析，多出来的 `: ping` 直接让它报错）。
        //
        // ⚠ 此前这一跳**不看 stream**，于是非流式请求在生成期每 256 步往响应体里
        //   写一帧注释，而且**每一次都写成功**（分帧由 `writeJson` 自己按长度拼，
        //   探针那句 `sseComment` 只写裸字节）—— 客户端读到的 JSON 长度对不上，
        //   于是它把自己当成"断流"重连/放弃。用户报的「直连一直不行」那一档里，
        //   有一条就是这个（[RelayPrefetch] 那一侧一合并字节，看着就像"没有任何输出"）。
        //   非流式的取消只剩两条路：`/v1/abort` 与写失败的迟到路径 —— 这与
        //   [PrefillProbe] 的取舍逐字一致（改协议面不在本次范围）。
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
     * prefill 窗口里的**断连探针**（ISSUE #450 第三次现场）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么生成循环里那条探针覆盖不了这一段
     * ═══════════════════════════════════════════════════════════════════════
     * 整段 prompt 的 prefill 是**一次阻塞的 JNI 调用**（`LlmEngine.startCompletion`
     * → `nativeStartCompletionSlot`），生成循环要等它**返回**才第一次拿到 CPU。
     * 于是那段时间里：`emitted` 还是 0、`n` 还是 0，上面那条"每 256 步探测"一步都
     * 跑不到 —— 服务端对"客户端已经走了"完全无感，prompt 照算完、`max_tokens` 照算满。
     * 长 prompt 上这正好是**用户最可能按停止**的那几秒：他等着第一个字，等不到就断。
     *
     * 这正是现场记录里「按停止什么都没有」的技术形状 —— 服务端连"对面还在不在"
     * 都要等到 prefill 结束才知道，而那时候算力已经烧完了。
     *
     * 所以这里换一个角度：**不打断 prefill，也不等它**，而是从另一条线程探。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 它做什么 / 不做什么
     * ═══════════════════════════════════════════════════════════════════════
     * · **做**：每 [PREFILL_PROBE_MS] 写一帧合法 SSE 注释（`sseComment`，chunked 分帧，
     *   客户端按规范忽略）；
     * · **做**：写失败（`Broken pipe` / `Connection reset`）= 对端已走 → 给**这一格**
     *   （`currentSlotOf(lease)`：这一轮此刻真正占着的那一格，不是 `acquire` 挑来的
     *   那个下标）打上取消，并走 [LlmEngine.requestAbort] 把取消送到 native
     *   （`abort_impl` 会置 `S.abort`，prefill 循环每一批都查它，于是当场收尾）。
     *   走 `requestAbort` 而不是 `cancelSlot` 的原因只有一个：只有前者会到 native，
     *   而 prefill 是 native 里的一次阻塞调用 —— Kotlin 侧置位挡不住它。
     * · **不**判 ALIVE：写成功什么也不说明（对端 FIN 之后首次写通常仍然成功）。
     *   所以这条探针只会**带来**取消，绝不会让本来正常的请求被误停。
     * · **不**写日志里的"活着"：每条请求每秒两次的心跳日志会把真现场淹掉。
     * · **[comments] = false 时什么都不写**：非流式（`Content-Length` 那一档）的响应
     *   体是一整段 JSON，"被忽略的前导"在某些客户端上不是免费的 —— 那一档只保留
     *   "每 800ms 醒一次"这个**节拍**，不产出字节。
     * · **静音**（[quiet]，这一格上已经有别人在探测）：不写字节，但**照样醒**，
     *   因为"这一格上的探测权在谁手上"随时会变（见 [claim] / [refreshOwner]）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 与生成期那些写的关系
     * ═══════════════════════════════════════════════════════════════════════
     * prefill 期间生成线程**一次写都没有**（它连第一条 `data:` 帧都没发），所以这里
     * 不存在"两个线程同时写同一个 chunked 流"的竞争：`close()` 一定在
     * `startCompletion` 返回之后调，而那一刻起生成线程才恢复。
     * ⚠ 唯一要守的是**退出路径**：`close()` 必须在 `finally` 里（prefill 会失败），
     *   否则这条线程会一直对着连接写 —— 而它写坏一帧分帧，客户端就断流。
     */
    private class PrefillProbe(
        private val out: OutputStream,
        private val lease: SlotScheduler.Lease,
        private val tag: String,
        /**
         * 能不能往这条连接上写 SSE 注释帧（`: ping` + chunked 分帧）。
         *
         * `true`：流式（`Transfer-Encoding: chunked`）。注释帧本来就是这个协议的一部分，
         * 客户端按规范忽略它 —— 这一档**探测能力与流式完全一致**，仍是"每
         * [PREFILL_PROBE_MS] 写一次"，写失败即判 GONE。
         * `false`：非流式（`writeJson` 带 `Content-Length`）。这一档**一个字节都不写**：
         * 响应体是一整段 JSON，而客户端是不是肯跳过"响应体之前的那几帧"，取决于
         * 它怎么解析 —— 把 HTTP 语义押在别人的宽容度上不是判据，是运气。
         * 代价说清楚：非流式的 prefill 窗口**发现不了客户端离开**（与今天相同），
         * 它那一档唯一的取消入口是 `/v1/abort`；这一格只保留节拍，不产出字节。
         */
        private val comments: Boolean,
        /**
         * relay 那一档：这一帧交给**中间层的预读**那一层去写（`RelayPrefetch.relayPing`）。
         *
         * `null` = 直连（写的就是客户端的连接）。非 `null` = relay：预读那一层手里
         * 才是**真连接**（`beatOut` 是 `relayOut` 第一次写的时候才交给它的），而且它
         * 与自己的心跳共用同一把写锁（[RelayPrefetch.writeLocked]）—— 两套各写一份
         * chunked 分帧就会互相劈开。
         *
         * ⚠ 但 `relayPing` **不是**"垫进预读缓冲"：它写的也是真连接（见那边的注释）。
         *   名字叫 ping、去向是真连接，是因为"这一帧该由谁写"和"这一帧放哪儿"是两件事：
         *   前者归预读（它持有连接与锁），后者永远是"带外注释帧"。
         */
        private val sink: ((ByteArray) -> Unit)? = null,
        /**
         * 预读那一层的出口（**只有 relay 这一档非 null**）。
         *
         * 为什么要有它（ISSUE #450 第十四次现场）：探针写一帧 = 往这条连接上追加
         * `4+n+2` 个字节，而 relay 那一档的字节**同时**还在往 [RelayPrefetch.buf] 里
         * 攒 —— 那些 `: ping` 帧在缓冲里是上游响应体之外的字节，一旦预读攒满 8 KB
         * 或者放开直写，它们就会被**原样交给客户端**：SSE 客户端读到的是注释帧
         * （多数能跳过），可客户端一按停止"一个字都没有"的现场里，我们连"这些帧
         * 到底有没有写出去"都答不出。
         *
         * 所以探针往**预读的账**上记一笔（它知道自己这一轮写了多少），收尾处
         * 两处对账（见 `RelayPrefetch.noteProbePing` / `probeLost`）。
         */
        private val prefetch: RelayPrefetch? = null,
        /**
         * "对端还在不在"的**无写入**判据（`false` = 确定走了）。
         *
         * `null` = 拿不到这条连接的可 poll 视图 —— 那时这一轮**不做断连探测**
         * （见下面 [probe] 的注释：宁可晚一步发现，也不许把"写不出去"当成"走了"）。
         */
        private val quietProbe: (() -> Boolean)? = null,
    ) {
        @Volatile private var stopped = false

        /** 这一条探针**真的写成功**过几帧（只进日志：它是"客户端那头见过字节"的唯一证据）。 */
        @Volatile private var wrote = 0

        /**
         * 这一格上**由谁来探**（ISSUE #450 第十一次现场）。
         *
         * ══════════════════════════════════════════════════════════════════════
         * 为什么不能靠"起不起探针"来解决抢写
         * ══════════════════════════════════════════════════════════════════════
         * 同一格上可能同时有两趟在跑（`attempt` 重试：上一趟已返回、这一趟接着算）。
         * 两趟各起一条探针往**同一格**上写，写失败就会把对方的轮次认成"客户端走了"
         * 然后取消掉 —— 于是有过一版拿 `RequestCancel.isSlotBusy(slot)` 把探针关掉。
         * 那一刀换来的是"这一趟 prefill 一次都不被探"：客户端在等待窗口里掉线，
         * 服务端一个字都收不到，prompt 照算满（单轮 25~62 秒）—— 用户看到的就是
         * **一个字都不输出**。
         *
         * 正确的判据是"此刻这一格由谁在探"，而不是"要不要探"。这个是**一份**：
         *   · 先拿到的那条成为唯一会写字节的那条（[quiet] = false）；
         *   · 其余的把 [quiet] 置真 —— 不写，但**照样醒**（见下面循环）；
         *   · 持有者 `close()` 时归还，下一条醒来的自己接手（[quiet] 是每拍判的，
         *     不是构造时判的 —— 构造时判就让"持有者先退"那一档永久静音）。
         */
        class Beat {
            var owner: PrefillProbe? = null
        }

        /** 这一轮此刻真正占着的那一格（`currentSlotOf`：不是 `acquire` 挑来的下标）。 */
        private val slotNow: Int get() = SlotScheduler.currentSlotOf(lease)

        /** 这一格上此刻是不是**我在写**。不是就静音（但仍按拍醒着，见 [refreshOwner]）。 */
        @Volatile private var quiet = false

        private fun beatAt(slot: Int): Beat =
            PROBE_BEATS.computeIfAbsent(slot) { Beat() }

        /**
         * 这一格此刻**还没被分配**（`currentSlotOf` 回 -1）。
         *
         * 静音，但**照样按拍醒着**（与 [quiet] 同一条路）：抢探测权的判据是"这一格
         * 由谁在探"，而 -1 不是一格 —— 把 -1 当成一格去抢，两条本不相干的请求会
         * 在 `PROBE_BEATS[-1]` 上互相静音，最后谁都不探。
         */
        private fun unassigned(): Boolean = slotNow < 0

        /** 抢这一格的探测权。抢不到就静音 —— 写失败会落到**别人**头上，那比不探更坏。 */
        private fun claim() {
            if (unassigned()) { quiet = true; return }
            val b = beatAt(slotNow)
            synchronized(b) {
                if (b.owner == null || b.owner === this) { b.owner = this; quiet = false } else quiet = true
            }
        }

        /** 每拍判一次：这一格上此刻唯一会写字节的那条，是不是已经换成我了（持有者先退那一档）。 */
        private fun refreshOwner() {
            if (unassigned()) { quiet = true; return }
            if (!quiet) return
            val b = beatAt(slotNow)
            synchronized(b) {
                if (b.owner == null || b.owner === this) { b.owner = this; quiet = false }
            }
        }

        private fun releaseOwner() {
            val b = PROBE_BEATS[slotNow] ?: return
            synchronized(b) { if (b.owner === this) b.owner = null }
        }

        init { claim() }

        private val thread = Thread({
            // 只探一次 GONE 就够：判出来之后这一轮注定收尾，没有"再探一次"的意义。
            while (!stopped) {
                try { Thread.sleep(PREFILL_PROBE_MS) } catch (_: InterruptedException) { break }
                if (stopped) break
                // ⚠ 这两个 `continue` 是**节拍**，不是"退出"：它们每 800ms 醒一次再判，
                //   所以 [quiet] / [comments] 的状态在运行中变了也立刻跟上。
                //   写成"构造时判一次就 `return`"那一版，正是"这一格永久静音"的来源。
                refreshOwner()
                if (quiet) continue
                // ══════════════════════════════════════════════════════════════
                // 判据：**这一次写的结果**不是判据，"对端还在不在"才是
                // ══════════════════════════════════════════════════════════════
                // 旧实现每 800ms 往这条连接写一帧 `: ping`，写抛异常就判 GONE。而 prefill
                // 是**一次阻塞的 native 调用**：那段时间对端在读一帧都没有的连接上干等，
                // socket 的发送超时一到，`write` 就抛 `Write timed out` / `Would block` ——
                // 服务端于是把**自己**那一轮取消了（用户原话：『我没点停止，它 abort 了，
                // 肯定没内容』；relay 那一侧则是『点了停止没效果』：取消打在了被自己写坏的
                // 那条连接上）。
                //
                // 现在这一段**不写字节**：[quietProbe] 用 `poll` 查 hangup，零超时、零写入。
                // 换来的取舍说清楚：prefill 期间连接上**没有心跳**（见 [PROBE_QUIET_MS] 的
                // 说明），客户端得按"生成可能要一分钟"去设读超时。
                val probeFn = quietProbe
                if (probeFn == null) {
                    // 拿不到可 poll 的连接：不探测（`quiet` 判据也一并失效）。
                    // **绝不退回"写一帧试试"** —— 那条路已经被证明会误停。
                    if (!comments) Thread.onSpinWait()
                    continue
                }
                val gone = try { !probeFn() } catch (_: Throwable) { false }
                if (!gone) {
                    wrote++
                    prefetch?.noteProbePing()
                    continue
                }
                // 对端走了：把取消落到**这一格**上。先摘掉自己，避免与生成线程抢着
                // 往同一条连接上写（后面那句 emitLog 走的是服务自己的日志通道）。
                stopped = true
                emitLog("$tag prefill 窗口里探测到客户端已离开（poll 到对端挂断，第 $wrote 次探测）：" +
                    "取消这一轮，不再把 prompt 算完")
                // 打的是**这一轮真正占着的那一格**（`currentSlotOf`），不是 `lease.slot`：
                // 后者是 `acquire` 挑来的下标，运行中会变（单格池里前一路还回来、
                // 这一路就换格了），而"打空不报错"正是本族问题的通用失效形态。
                // 判据与 `LlmEngine.sourceSlot` 同源（`owner === lease`），两处不各算一份。
                runCatching { LlmEngine.requestAbort(SlotScheduler.currentSlotOf(lease), lease) }
                break
            }
        }, "prefill-probe")
        fun start() {
            thread.isDaemon = true
            thread.start()
        }
        fun close() {
            stopped = true
            thread.interrupt()
            releaseOwner()
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
     *
     * ⚠ **只对流式调用**：它写的是"一帧合法 SSE 注释"，而非流式的响应体是一整段
     *   JSON + `Content-Length` —— 往里插一帧会直接把这份响应写坏。`handleChat`
     *   里两个调用点都在 `if (stream && …)` 里，这一句是给后来的人看的判据
     *   （[probeOutbound] 那边此前漏了同一个判断，见那边的注释）。
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
     * 每格一份：这一格上**由谁在探**（[PrefillProbe] 的探测权，见那边的 [PrefillProbe.Beat]）。
     *
     * 与 [PrefillProbe] 的 `quiet` 配成一对：判据是"此刻这一格有没有人在写"，而不是
     * "这一轮登记过没有" —— 后者把"客户端在 prefill 窗口里掉线"这件事整个抹掉了。
     */
    /**
     * 每格一份：这一格上**由谁在探**（[PrefillProbe] 的探测权，见那边的 [PrefillProbe.Beat]）。
     *
     * 与探针的 `quiet` 配成一对：判据是"此刻这一格有没有人在写"，而不是
     * "这一轮登记过没有" —— 后者把"客户端在 prefill 窗口里掉线"这件事整个抹掉了。
     *
     * ⚠ `object` 里没有 `companion object`（那是 `class` 才有的东西），所以这张表就是
     *   一个普通字段，嵌套类直接按名字访问即可。
     */
    private val PROBE_BEATS = java.util.concurrent.ConcurrentHashMap<Int, PrefillProbe.Beat>()

    /**
     * `POST /v1/abort`：取消**当前正在生成的那一轮**，无 body、无参数。
     *
     * 为什么不做成 `{request_id}` / `DELETE /v1/chat/completions/{id}` 那种按 id 取消：
     * 本服务是单实例引擎、**每 slot 一路**（Issue #330 起生成循环按 slot 持锁），
     * `slot` 缺省 0 兼容今天"只有一路"的客户端。要求调用方先记住 id，只会多一层「id 对不上
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
     *
     * ⚠ ISSUE #403 之后"缺省 0"这一句改了：不带 `slot` 时先按**来源**取消，
     * 见 [requestAbortHttp] 与 [handleAbort] 里的那一段。
     */
    /**
     * 不带 `slot` 的 `/v1/abort`：停**调用方自己**那一路（ISSUE #403）。
     *
     * 判据只有一条：**按来源**取消（[LlmEngine.requestAbortSource]）—— 这是唯一能说清
     * "停的是我这一路、不是别人的回答"的依据（单 slot 时 HTTP 与 App 共用 slot 0）。
     * 按来源问不出唯一格子（多 slot 时 HTTP 可能占好几格）就打空，回 `aborted:false`。
     *
     * ⚠ 这里曾经还有"再退回旧语义 slot 0"的第二跳，已经删掉：单格池里 slot 0 可能
     *   正压着 **App 那一轮**，而按来源打空恰恰说明"HTTP 这一路不在池子里"——
     *   这时再按 0 打一跳，停的必然是别人的回答（ISSUE #450 自检里发现的残留）。
     *   老客户端也不会因此停不到东西：它自己那一轮在跑时，按来源一定问得到格子。
     *
     * 不把这两步合成"遍历所有 slot"：一个客户端不许掐掉别人的回答 ——
     * `cancelAllSlots` 至今没有调用点正是这条（设计文档 §9.2.2）。
     */
    private fun requestAbortHttp(): RequestCancel.Token? =
        LlmEngine.requestAbortSource(SlotScheduler.Source.HTTP)

    /**
     * `abort_case == "unowned"` 那一档的**成因**：两句完全不同的话，判据就在手里。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不能只报"这一格有人、但记不到它主人头上"
     * ══════════════════════════════════════════════════════════════════════
     * 真机上用户就是拿着那一句回来问"下一步怎么办"（ISSUE #450 第六次现场），
     * 因为它是四档里唯一一档"带着人来提问、却不给出方向"的读出。而这一档底下压着
     * 两件处置相反的事：
     *
     *   · ① **HTTP 这一路在池子里**（不带 slot 时按来源问得出格子，而且那一格的来源
     *     就是 HTTP）：说明这一轮**已经受理了、只是还在 prefill**（登记表里没有它 =
     *     `startCompletion` 还没返回）。账**该**记上却空了 —— 这一档是代码的问题：
     *     要么这一笔被 `abandon` 收掉了，要么 `requestAbortSource` 没把主人传下去。
     *     拿着这一句去查 `LlmEngine.requestAbortSource` 那一跳的实参。
     *   · ② **HTTP 这一路不在池子里**：它那一轮已经收尾（或还没受理），此刻占着
     *     格子的是 App 那一轮。这一档**必须什么都不做** —— 停它就是掐别人的回答，
     *     与「`/v1/abort` 绝不兜底到 slot 0」是同一条判据。
     *
     * ⚠ 这两档以前完全同形（都只说"有人、但记不到"），于是真机上用户拿着它来问
     *   "下一步怎么办" —— 而答案在这两档里正好相反（去查代码 / 什么都不用做）。
     *
     * 另外 **`aborted` 与这一档并不矛盾**：不带 slot 时「取消意图是否落到这一路」
     * 由 `aborted` 报（`requestAbortSource` 记账就算落到了），而 `abort_case`
     * 只说"停到了哪一段"。以前的读法是"`aborted:false` + `unowned` = 停失败了"，
     * 那是四档挤在一个布尔量里的产物 —— 现在分开读：先看 `aborted`，再看 `abort_case`。
     *
     * 两句都只**读**已有的表（`slotOfSource`），不新增任何状态、不改变判定：
     * `aborted` 仍然是上面算出来的那个值。
     */
    private fun unownedWhy(slot: Int): String {
        if (slot >= 0) {
            return "slot $slot 上有格子被占着，但这一笔记不到它的主人头上" +
                "（这一格的主人已收尾）；要按来源停自己那一路就别带 slot"
        }
        // 不带 slot：说清楚"HTTP 这一路此刻在池子里吗"——这一句决定了下一步该查什么。
        val httpSlot = SlotScheduler.slotOfSource(SlotScheduler.Source.HTTP)
        if (httpSlot < 0) {
            val appSlot = SlotScheduler.slotOfSource(SlotScheduler.Source.APP)
            return "HTTP 这一路此刻不在池子里（它的那一轮已收尾或还没受理），" +
                "占着格子的是 ${if (appSlot >= 0) "App 那一轮（slot $appSlot）" else "另一路"} —— " +
                "这一档**故意不停**：停它等于掐掉别人的回答。你要停的那一轮若还没受理，" +
                "等它进到 prefill 之后再按一次"
        }
        return "HTTP 这一路就在 slot $httpSlot 上、那一格是它自己的，登记表里还没有这一轮" +
            "（= 还在 prefill），而这一笔取消**没记上**；" +
            "这不是本次调用的用法问题，是 `requestAbortSource` 那一跳没把主人传下去" +
            "（见该函数的注释）—— 把这一行连同上一条 `abort:` 一起发我"
    }

    /*
     * ⚠ `unowned` 这一档的两句话在 [unownedWhy]，不在这里：它是 `/v1/abort` 唯一一档
     *   需要调用方"读完再去查点什么"的读出。混在 `when` 的字符串里会让那段本来就长的
     *   分支再长一倍，而它的**判定**一个字都不该变（`aborted` 仍在上面算）。
     */
    private fun handleAbort(req: Req): String {
        // ---- 可选 `slot`（阶段 4）：不带时按**来源**取消（ISSUE #403）----
        //
        // 为什么要带 slot：多 slot 之后同一时刻有多个轮次在跑，「取消当前那一轮」
        // 不再有唯一所指。**只停指定 slot** —— 一个客户端不许掐掉别人的回答
        //（设计文档 §6）。取值非法时**不静默回落**：回落成 "停 slot 0" 会把一个
        // "我写错了参数"变成"别人的回答被掐断"，而那正是本期最需要避免的形态。
        //
        // ══════════════════════════════════════════════════════════════════
        // 不带 slot 时**不再缺省 0**（ISSUE #403：slot 1 的停止失效）
        // ══════════════════════════════════════════════════════════════════
        // 缺省 0 在两个方向上都错，而**错的方式都不报错**：
        //   · 单 slot（默认档）时 HTTP 与 App 内聊天共用 slot 0：外部客户端想停
        //     自己那一路，此刻 slot 0 上若是 App 那一轮，就停到了别人的回答上；
        //   · 反过来，`/` 自带测试页的「停止」也不带 slot，它停的其实常常是 App。
        // 现场是用户报的「客户端按了停止，app 还是后台算完了」+ 客户端只看到
        // `Fetch request has been canceled`（那是**客户端自己**断的，服务端没收到取消）。
        //
        // 判据换成"停调用方**自己**那一路"：来源是 HTTP（这个端点只由外部客户端调）。
        // 多 slot 时 HTTP 可能占多格，此时"那一路"没有唯一所指 —— 判据仍然只问
        // 「HTTP 那一格上有没有已登记的轮次」，问不出就打空（`aborted:false`）。
        // **不**再兜底去打 slot 0：单 slot 时 slot 0 可能正压着 App 那一轮，
        // 兜底那一跳就是"停别人的回答"，而这正是本 Issue 里反向的那一半。
        val slot = when (val r = parseAbortSlot(req.body)) {
            is AbortRequest.Slot.Default -> -1   // -1 = 按来源取消，见下
            is AbortRequest.Slot.Value -> r.slot
            // 非法**拒绝执行**（不回落 slot 0）：回落会把"我写错了参数"变成
            // "别人的回答被掐断"，而那正是本期最需要避免的形态。返回里带 `aborted:false`
            // 与 `error`，让调用方分得清"没停到东西"与"你的参数是错的"。
            is AbortRequest.Slot.Invalid -> {
                emitLog("abort: slot 参数非法（必须是 0..${SlotScheduler.SLOT_MAX - 1} 的整数），拒绝执行")
                return """{"aborted":false,"generating":${RequestCancel.active},"abort_case":"invalid","error":"invalid slot"}"""
            }
        }
        // 走 LlmEngine 的两个唯一入口之一：它们都同时做两件事 ——
        // 标记 Kotlin 侧的 token，并把**带归属的**取消送到 native。
        // 只标记 token 的话，prefill 阶段（一次阻塞的 native 调用）根本停不下来。
        //
        // 不带 slot（-1）时按来源取消：问调度器"HTTP 那一路此刻在哪一格"
        // （单 slot 时它可能就在 0，也可能撞上 App 的那一轮）；问不出唯一格子
        // （多 slot、HTTP 占了好几格）就打空 —— **不退回 slot 0**（那会停别人的回答）。
        //
        // 显式带 `slot` 时**只打那一格**，打空也**不换格**（ISSUE #450 自检的残留）：
        // 客户端那份格子来自 `X-Slot-Id`，是**受理那一刻**的事实；生成期里格子会变 ——
        // 单格池上另一路要开口时 `SlotScheduler.acquire` 会把这一格**让出去**，
        // HTTP 那一轮于是收了尾。此时再点停止，那一格上可能已经换成别人（App，或另一条
        // 请求）的轮次；按来源补一跳就会停到它头上 —— 与"绝不兜底到别的 slot"同类风险。
        //
        // "点名某一格打空"本身不需要兜底：`requestAbort(slot)` 内部已经把这笔取消
        // 送到了**那一格**的 native（打空窗口 = prefill），并把"无处可去"的那一笔作废
        // （`RequestCancel.abandon`）。
        //
        // ⚠ 这里不再记 `owedBefore`（旧版读的是"这一格有没有欠账"）。那张按格子记的
        //   账表已经换成按**身份**记（见 `RequestCancel` 文件头）：一笔账属于某个请求，
        //   而 `/v1/abort` 手里只有"哪一格" —— 用格子去反查身份，问出来的答案可能是
        //   别人的账（单格池里 App 与 HTTP 轮替占着同一格）。于是 `aborted` 只报
        //   **这次调用真的落到了什么**：停到了一轮（`t != null`），或者把取消记在了
        //   这一格主人头上（`claimed`）。
        //
        //   格子换成主人的那一跳走调度器的表：`leaseAt(slot)` 就是"这一格现在是谁"，
        //   而账本按身份记。取不到主人（空位）= 没有可停的对象，`claimed` 自然是 false。
        val occupant = if (slot >= 0) SlotScheduler.leaseAt(slot) else null
        val claimed = RequestCancel.isClaimed(occupant)
        val t = when {
            slot < 0 -> requestAbortHttp()
            // ⚠ 只打这一格，**不换格兜底**（ISSUE #450 自检收尾）：这里曾经是
            //   `requestAbort(slot) ?: requestAbortHttp()`，等于"点名 slot 2、结果停了
            //   slot 1 上的那一轮"—— 与上面那条"绝不兜底到别的 slot"是同一类风险，
            //   只是此前两侧恰好同格（单格池）所以看不出来。打空那一档该做的事是**结账**
            //   （`requestAbort` 内部已经把取消送到这一格的 native，并结掉无处可去的账），
            //   不是"再找一条路打一遍"。
            //
            // ⚠ 必须把 `occupant` 一起传进去：打空那一刻正是 prefill，取消注册表还是空的，
            //   而"该记在谁头上"只有这一格的 lease 答得出来（ISSUE #450 第二次现场）。
            //   不传的话这笔取消要么丢掉（客户端照旧算满），要么被这一格的下一条请求吃掉。
            else -> LlmEngine.requestAbort(slot, occupant)
        }
        // `aborted` 的含义在 ISSUE #450 之后是「**取消意图**是否落到了这一格」，
        // 不再是「此刻表里有没有一个被置位的 token」：prefill 窗口里表本来就是空的，
        // 而取消照样有效（记在 `RequestCancel` 里，由**那一轮自己**登记时兑现）。
        // 只报 token 存不存在的话，客户端拿到 `aborted:false` 就会以为"没停到"，
        // 而服务端其实已经不在算这一轮了 —— 反过来也一样糟：这里报 false、
        // 那边报 true，两边对不上账。
        val running = t != null || claimed
        // 两路共用一格时的读数（ISSUE #403 第三次现场）：
        //   · `running=false` 而这里写着「共用」，说明**停到的那一格不是它自己的** ——
        //     单格池里 App 与 HTTP 轮替占着同一格，`slot` 缺省或猜错就是打空；
        //   · `running=false` 且 `登记轮次=无`，是"这一轮已收尾"的正常 no-op。
        // 两句话以前完全同形，于是每次都被归因到网络/客户端。
        val sharedNote = if (SlotScheduler.sharedCount > 0)
            "｜⚠ 有 ${SlotScheduler.sharedCount} 格是两路共用（App 与 HTTP 挤在同一份 ctx 上，" +
                "各自的 KV 前缀会互相作废）；要分开就得用 parallelN≥2 加载模型" else ""
        // 不走 `t!!`：`running` 为 true 与 `t != null` 是同一判断，
        // 但写成 `!!` 就多一处"靠非局部推理成立"的断言（`t` 一旦改成别的取值来源就会炸）。
        // 编号只进日志，用 `?: 0` 兜住即可 —— 它是**读数**，不参与判定。
        //
        // 回显的 `slot` 一律取**真停到的那一格**（`t.slot`），不取入参：
        // 入参可能在走 `-1`（按来源取消），而回显一个 `-1` 是没法对着读数的。
        // 停不到时回显入参（`-1` 表示"按来源找过、没找到"）。
        val shown = t?.slot ?: slot
        val owed = claimed || t?.targeted == true
        // ---- 这一次取消**落到了什么**（ISSUE #450 第三次现场：日志里三种成因同形）----
        //
        // 此前只有两句日志（"取消到了轮次" / "没有生成轮次（no-op）"），而真机上
        // `/v1/abort` 在 prefill 窗口里会命中**第三句**（`owed`：账记在这一轮的头上），
        // 于是"按了停止、什么都没发生"的现场里，日志既说不出"打中了哪一段"，
        // 也说不出"这台机器上到底有没有人收到"。现在每一档都有自己的名字，并且
        // 一并回给调用方（`abort_case`）—— 取消是**请求-应答**，只靠服务端日志
        // 排查等于让用户去猜。
        val abortCase = when {
            t != null -> "round"       // 停到了已登记的那一轮
            owed -> "prefill"          // 打中 prefill 窗口：取消记在这一轮头上，登记时兑现
            // 这一格此刻有人占着，但那张表里还没有它的轮次 —— 而账也没记上
            // （`occupant` 取不到，或这一笔已被 `abandon` 收掉）。**这是唯一需要人
            // 去看的形态**：它意味着取消意图没落到任何对象上。
            //
            // ⚠ 它此前只报"有人、但记不到"（ISSUE #450 第六次现场：用户拿着这一句
            //   回来问"下一步怎么办"）。而这个形态里其实藏着两件**完全不同**的事，
            //   判据就在手里，不该让读的人去猜：
            //     · HTTP 这一路**在池子里**占着某一格（`slotOfSource` 答得出）——
            //       那一格上的主人就是这一路自己，账本该记上却空了。这是**代码**
            //       的问题（prefill 窗口里的探针没跑起来：非流式请求没有它，见
            //       `handleChat` 里那段 `if (stream)`），只看日志能认出这一档；
            //     · HTTP 这一路**根本不在池子里** —— 它的这一轮已经收尾（或还没受理），
            //       此刻占着格子的是 App 那一轮。这一档**不该停**：停它就是掐别人的回答，
            //       这正是 `/v1/abort` 从不兜底 slot 0 的同一条理由。
            SlotScheduler.busy > 0 -> "unowned"
            else -> "idle"             // 真的没有轮次在跑（正常的 no-op）
        }
        // 日志按 `abort_case` 分档 —— 每一档都有**自己的名字**，因为它们是四件不同的
        // 事，处置相反（等一会儿 / 去查 native / 去查调度器 / 什么都不用做）。
        // 此前只有三句、且"prefill 中"与"没有轮次"同形，于是每次都被归因到网络。
        val note = when (abortCase) {
            "round" -> "abort: 取消 slot $shown 上的生成轮次（来源=http，epoch=${t?.nativeEpoch ?: 0}）"
            // prefill 窗口里的正常结果，**不是** no-op：取消记在欠账上、登记时兑现
            // （ISSUE #450）。这两句话以前完全同形，于是每次都被归因到网络/客户端。
            "prefill" -> "abort: slot $shown 上还没有登记轮次（prefill 中）：取消已记在这一轮的头上，它登记时立即兑现"
            // 有人在跑、却没人认这一笔：说清楚它属于"这次没停到任何东西"，
            // 与 `idle` 那种正常 no-op 分开（否则又回到"三种成因同形"）。
            "unowned" -> "abort: " + unownedWhy(slot)
            // ⚠ 兜底那一档要说**它能说的事**：这一格此刻既没有已登记的轮次、也没有
            //   记上账 —— 可能是"真的没有轮次"，也可能是"这一轮已经收尾"（走 `-1`
            //   按来源取消时 `shown` 是 -1，读起来就像"停到了 -1 号格"）。两种都
            //   答不出更细的结论，所以只报这件事本身 + 让调用方去看 `generating`。
            else -> "abort: slot $shown 上没有生成轮次（no-op；若你这次没带 slot，" +
                "说明按来源没找到「我这一路」的格子 —— 可能是这一轮已经收尾）"
        }
        emitLog(note + sharedNote)
        // `unowned` 这一档要**再说一句**：上面的名字只说"记不到主人头上"，
        // 而这句话决定了下一步该查什么（见 [unownedWhy]）。
        if (abortCase == "unowned") emitLog("abort: " + unownedWhy(slot))
        // `slot` 回显让调用方能把"我停的是哪一路"对上；`generating` 仍是**全池**
        // 是否有人在跑的读数（旧语义保留，只增不改）。
        // `abort_case` 是**新增**的一格（只增不改）：它把上面那四种形态的原名回给
        // 调用方，于是"按了停止没反应"这件事在客户端一侧就能自证，不必再去翻日志。
        return """{"aborted":$running,"generating":${RequestCancel.active},"slot":$shown,"abort_case":"$abortCase"}"""
    }

    /**
     * 解析 `/v1/abort` 的 `slot`（缺省 0）。**非法不回落**，返回 [AbortRequest.Slot.Invalid]。
     *
     * 三态与判据都在 [AbortRequest.resolve]（纯逻辑、有宿主单测）；这里只做
     * "body -> JSON 值"那一跳。分成两半的理由：`org.json` 在宿主是 stub，
     * 判据留在 `HttpApi` 就等于它永远只能在真机上验。
     */
    private fun parseAbortSlot(body: String): AbortRequest.Slot {
        if (body.isBlank()) return AbortRequest.Slot.Default
        val j = try { JSONObject(body) } catch (_: Exception) { return AbortRequest.Slot.Invalid }
        // JSON 那一跳留在这里（`org.json` 是 Android 的），**判据**在 [AbortRequest.resolve]
        // —— 这样"三态怎么判"是纯逻辑、可以在宿主上单测（`org.json` 在宿主是 stub）。
        return AbortRequest.resolve(j.has("slot"), j.opt("slot"))
    }

    // ---- JSON / SSE 工具 ----

    /**
     * 一条流式 chat 增量块。
     *
     * [ragField] 是**已拼好的**片段（含前导逗号）或空串：`local_rag`（本轮注入了什么）
     * 是"服务端替调用方做了什么"的读数，三条响应路径必须**同一处取值**（见 [ragFieldOf]），
     * 各拼一次必然漂移成"某条路径上没有"。
     *
     * ⚠ `local_ctx` **不在这里出现**，也不在任何响应体里：它不是 OpenAI 规范字段，
     *   拼在 `choices` / `usage` 旁边会让严格按 schema 解析的客户端判整条响应非法。
     *   诊断读数改走 `X-Local-Ctx` 响应头（见 `handleChat` 里那一处）。
     *
     * [logprobsField] 是**已拼好的** `,"logprobs":null` 或**空串**。为什么是字符串而不是
     * 一个 Boolean 让这里判：调用方（handleChat）手里才有"客户端到底要没要 logprobs"这个
     * 信息，而"要了才出现该字段、不要就一个字节都不多"是本仓库对新增可选字段的硬要求
     * （见 [JsonSchemaFormat] 文件头：字段不存在 => 与引入本特性之前逐字节相同）。
     * 在这里再判一次就等于把那个判据复制到第二个地方，两处必然漂移。
     */
    private fun chatChunkJson(id: String, model: String, created: Long, deltaJson: String,
                               finish: String?, logprobsField: String = "",
                               ragField: String = ""): String {
        val fin = if (finish == null) "null" else "\"$finish\""
        return """{"id":"$id","object":"chat.completion.chunk","created":$created,"model":"$model",""" +
            """"choices":[{"index":0,"delta":$deltaJson$logprobsField,"finish_reason":$fin}]$ragField}"""
    }

    /**
     * 本轮响应里的 `local_rag` 字段片段（含前导逗号）；**没有注入时是空串**。
     *
     * 为什么单独一个方法而不是把 `RagProtocol.Injection` 透传给三处调用点：
     * 三处（流式收尾帧 / 非流式正文 / 补发正文帧）各拼一次必然漂移，
     * 而漂移的方向是"某一条路径上字段没了"—— 那条路径上的调用方于是**永远
     * 看不到"被注入了"**，正是这个字段要消除的那种盲区。
     * 判据落在 `tools/rag_remote/check_response_field.py`（三条路径都必须取它）。
     */
    private fun ragFieldOf(kb: String, count: Int): String =
        RagProtocol.responseField(
            if (count > 0) RagProtocol.Injection(kb, count) else null)

    /**
     * 同 [chatChunkJson] 的 [logprobsField]：空串 = 客户端没要 logprobs，一个字节都不多。
     *
     * ⚠ `local_ctx` 同样**不进这个块**（OpenAI 兼容）：它走 `X-Local-Ctx` 响应头。
     */
    private fun compChunkJson(id: String, model: String, created: Long, textJson: String, finish: String?,
                               logprobsField: String = ""): String {
        val fin = if (finish == null) "null" else "\"$finish\""
        return """{"id":"$id","object":"text_completion","created":$created,"model":"$model",""" +
            """"choices":[{"index":0,"text":$textJson$logprobsField,"finish_reason":$fin}]}"""
    }

    /**
     * 客户端要了 `logprobs` 时该出现在 choice 里的那段片段（**含前导逗号**）。
     *
     * 为什么值恒为 `null`：本实现收下并校验了这个字段，但**不产出**逐 token 置信度
     * （理由与代价写在 [ProtocolFields] 文件末尾那段）。这里的取舍是：
     *   · 字段要出现（否则 SDK 会把"我们要过 logprobs"这件事整个丢掉，而它对
     *     响应形状的判断是按字段在不在做的）；
     *   · 值必须是 `null` 而不是 `[]` 或 `{}` —— 后两者都是"结构对但内容为空"，
     *     客户端会当成"模型一个 token 都没给置信度"，而真相是"这版不支持"。
     *     `null` 才是 OpenAI 语义里"没有这一项"的表达，且日志里那行说明与它成对。
     *
     * 客户端**没要**时返回空串 —— 一个字节都不多，与引入本特性之前逐字节相同。
     */
    private fun logprobsFieldOf(wanted: Boolean): String =
        // 引号用**转义形式**而不是三引号原样串：本值里恰好有 `":` 的序列，
        // 写成三引号是对的但读起来像括号打架（而且守卫的判据要落到这行上，
        // 正则解析三引号串会把它绕过去）。
        if (wanted) ",\"logprobs\":null" else ""

    private fun usageJson(promptTok: Int, completionTok: Int): String =
        """{"prompt_tokens":$promptTok,"completion_tokens":$completionTok,"total_tokens":${promptTok + completionTok}}"""

    /** 错误体与响应头的实现在 [HttpWire]；这里保留薄门面，调用点无需改。 */
    private fun errJson(
        msg: String,
        type: String = "local_inference_error",
        code: String? = null,
    ): String = HttpWire.errJson(msg, type, code)

    private fun writeRaw(
        out: OutputStream,
        code: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        extraHeaders: String = "",
        corsOrigin: String? = null,
    ) = HttpWire.writeRaw(out, code, body, contentType, extraHeaders, corsOrigin)

    private fun writeJson(
        out: OutputStream,
        code: Int,
        body: String,
        corsOrigin: String? = null,
        slot: Int = -1,
        extraHeaders: String = "",
    ) = HttpWire.writeJson(out, code, body, corsOrigin, slot, extraHeaders)

    private fun slotHeaders(slot: Int): String = HttpWire.slotHeaders(slot)

    /**
     * 自带测试页：`text/html; charset=utf-8`。
     *
     * 显式带 charset：页子里有中文，缺了它浏览器会按 latin-1 猜，
     * 现场表现是"页面标题乱码"这种看着像编码 bug、实际是响应头缺项的问题
     *（`<meta charset>` 虽在，但它要等 HTML 解析到那一行才生效，中间那段已经错了）。
     */
    private fun writeHtml(out: OutputStream, code: Int, body: String, corsOrigin: String? = null) =
        writeRaw(out, code, body, "text/html; charset=utf-8", corsOrigin = corsOrigin)

    private fun reason(c: Int) = HttpWire.reason(c)

    // SSE 的响应头与分帧也在 HttpWire（第 7 批 H7 第 1 步的延伸）；
    // 这里同样只留薄门面，调用点无需改。
    private fun sseHead(out: OutputStream, corsOrigin: String? = null, slot: Int = -1,
                        extraHeaders: String = "") =
        HttpWire.sseHead(out, corsOrigin, slot, extraHeaders)

    private fun writeFrame(dst: OutputStream, payload: ByteArray) =
        HttpWire.writeFrame(dst, payload)

    private fun sseComment(out: OutputStream) = HttpWire.sseComment(out)

    private fun sseEvent(out: OutputStream, data: String) = HttpWire.sseEvent(out, data)

    private fun sseEnd(out: OutputStream) = HttpWire.sseEnd(out)
}
