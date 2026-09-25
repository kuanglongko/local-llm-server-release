package com.xiaowan.localinference

/**
 * 请求取消的**纯逻辑**部分：一次生成的「停止标志」、取消请求的归属判定、
 * 以及客户端断连的判据。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这个文件（原来的缺口）
 * ══════════════════════════════════════════════════════════════════════════
 * `LlmEngine.abort()` 早就存在，但它是**全局**的：谁都能叫停、叫停的永远是
 * 「当前那一轮」。HTTP 路径此前压根没接上它，于是：
 *   · 客户端 Ctrl-C / 断网 / 超时离开后，服务端照跑满 `max_tokens`，白烧 CPU 与电；
 *   · 更糟的是**停错对象**：一个已经断连的请求（其线程仍卡在 `LlmEngine.step()` 里，
 *     而生成循环持着 `genLock`）在迟到的 15s socket 超时后触发取消，就会把此时
 *     真正在跑的那一轮（可能是 App 内正在看的对话）**当成自己**停掉。
 * 所以取消必须带**归属**：每个请求领一个自增的 [RequestCancel.Token]，只有「当前
 * 正在生成的那个 token」的取消才真的生效，过期的取消请求只会被记一笔日志。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 什么算「客户端走了」（这条判据错了就是无差别误杀）
 * ══════════════════════════════════════════════════════════════════════════
 * **只靠写**：探测就是往连接里写一个**合法的 SSE 注释帧**（`[HttpApi.heartbeat]`），
 * 写抛异常（`Broken pipe` / `Connection reset`）= 对端已走（RST）→ [PeerState.GONE]；
 * 写成功 = 连接仍在 → [PeerState.ALIVE]。不读、不改 `soTimeout`、不写裸字节。
 *
 *   · `socket.isClosed` 不能用：它只有本端自己 close 才会置位，对端走掉时是 false；
 *   · **读**探测不能用（2026-09-20 真机事故）：旧实现为了兼得「FIN」这一路信号，
 *     往 socket 里裸写了一个 `0x00` 再读。但流式响应是 `Transfer-Encoding: chunked`,
 *     那个裸字节落在分帧之外，客户端解析「下一个 chunk 的长度行」时读到 `0x0` →
 *     `Expected leading [0-9a-fA-F] character but was 0x0` → 客户端主动断流。
 *     而它偏偏在思考段（[HttpApi] 里 `think.inThink`）被触发，正是「思考开时回答
 *     输出一小段就断」的成因。**为了拿到 FIN 一路信号而污染流，代价与收益完全不对等。**
 *
 * 写探测的「漏判」缺口是有意留下的：对端 FIN 之后本端首次写通常**成功**
 *（数据进内核缓冲，第二次写才拿到 RST），所以写探测只保证「最终会发现」，
 * 不保证「立刻发现」。代价是客户端若用「半关」方式退出（shutdown write
 * 而进程不退），要跑到下一次写才被察觉 —— 这比让每条流都被污染小得多。
 *
 * 为什么只在 SSE 的**心跳**上探测（见 [HttpApi] 调用点）而不到处探测：
 * 真正需要知道「客户端还在不在」的时刻，是长时间只有思考段、没有正文可发的空档；
 * 而心跳帧本身就是合法 SSE 帧，探测搭在它上面等于零成本。
 */
object RequestCancel {

    /** 无人取消（UI 路径、或尚未建立归属）时的 token。真 token 从 1 起，0 恒不匹配。 */
    const val NONE = 0L

    /**
     * 一次生成的「停止标志」。每个请求领一个，生成循环每步查一次。
     *
     * [requested] 用 volatile：写的一方是 HTTP 连接线程（断连探测 / `/v1/abort`），
     * 读的一方是生成线程，两者不同线程。
     */
    class Token {
        @Volatile var requested: Boolean = false
            private set

        /**
         * native 侧的**轮次编号**（`nativeCurrentEpoch`）。取消时回传给 `nativeAbort`
         * 做归属核对 —— 没有它，归属就只防到 JNI 门口。
         *
         * 为什么必须绑在 token 上：置位方是 HTTP 连接线程 / 主线程，而"这一轮还在不在"
         * 只有 native 知道。一个迟到的取消（它那一轮早已结束）若不带编号，
         * 会把**此刻才开始的下一轮**一起停掉 —— 正是本文件头记录的那个事故，
         * 只是它此前在最后一跳（JNI）上重新出现了一次。
         *
         * 0 = 未绑定（native 侧一律不生效，并留日志）。绑定时机见
         * `LlmEngine.beginCancelable()`：必须在 `startCompletion` 返回成功之后、
         * 且在生成线程持 `genLock` 时取，读到的才是本轮的编号。
         */
        @Volatile var nativeEpoch: Long = 0L
            private set

        /** 绑定本轮在 native 侧的编号（只应在轮次开始时调用一次）。 */
        fun bindNativeEpoch(epoch: Long) { nativeEpoch = epoch }

        /** 停在哪个 stop 串上时为 true —— `finish_reason` 要区分 `stop` 与 `length`。 */
        @Volatile var stopHit: Boolean = false
            private set

        /** 因客户端离开 / `/v1/abort` 而停时为 true；`finish_reason` 报 `cancelled`。 */
        val cancelled: Boolean get() = requested

        /** 标记取消；返回**本次调用**是否首次置位（用于「只记一次日志」）。 */
        fun request(): Boolean {
            if (requested) return false
            requested = true
            return true
        }

        /** 标记本轮命中 stop 序列（恰在一次，防止重复）。 */
        fun markStopHit() {
            stopHit = true
        }
    }

    /**
     * 当前正在生成的那一轮。`[token] == NONE` 表示此刻没有轮次在跑。
     *
     * 三个方法必须是**同一个锁**下的原子操作：[enter] 与 [leave] 由发起方调用，
     * [cancelCurrent] 由 HTTP 连接线程调用。分开的 getter/setter 会出现
     * 「读到旧 token → 在新一轮上叫停」的窗口，正是本文件要消灭的那类 bug。
     */
    private val lock = Any()
    @Volatile private var current: Token? = null

    /** 生成开始：登记归属；返回本轮 token（生成循环持它逐 step 判）。 */
    fun enter(): Token {
        val t = Token()
        synchronized(lock) { current = t }
        return t
    }

    /** 生成结束（正常 / 异常 / 取消都要走）：仅当自己仍是当前轮次时摘除，避免踩掉后来者。 */
    fun leave(t: Token) {
        synchronized(lock) { if (current === t) current = null }
    }

    /**
     * 取消「当前正在生成的那一轮」。返回被取消的 token；没有轮次在跑时返回 null。
     *
     * 调用方（`/v1/abort`、断连探测）**不指定**要停哪个请求：能到达 native 的只有
     * 「当前这一轮」——单实例引擎、生成循环持 `genLock`，队列里没有第二个。
     * 想让调用方自己判断「该不该停」，就必然要跨线程读 `busy` 之类的标志，
     * 而那个判断与真实状态之间总有窗口（本文件头描述的事故就是它）。
     */
    fun cancelCurrent(): Token? {
        val t = synchronized(lock) { current } ?: return null
        t.request()
        return t
    }

    /** 是否有轮次在跑（仅用于日志与 `/health` 展示，不做任何判定用）。 */
    val active: Boolean get() = synchronized(lock) { current != null }

    /** 当前轮次是否已被取消（生成循环用来中止；与 token.requested 等价，保留给 UI 路径）。 */
    fun isCancelled(): Boolean = synchronized(lock) { current?.requested } ?: false

    /** 当前轮次的 token；没有轮次在跑时返回 null。供 UI 侧引用（不参与判定）。 */
    fun currentToken(): Token? = synchronized(lock) { current }

    // ---- 客户端断连判据 ----

    /** 写探测的结果。两态：写成功即 ALIVE，写抛异常即 GONE。 */
    enum class PeerState { ALIVE, GONE }

    /**
     * 写探测的**判据**（纯函数，无 IO，便于离线单测）。
     *
     * 只有一条规则：写成功 = 活着，写失败 = 走了。**没有第三态**，因为不读 ——
     * 读探测带来的「UNKNOWN（读超时）」在调用方是纯粹的负担（还得逐个判能不能当离开，
     * 判错就是误杀），而它为唯一能多拿到的 FIN 信号付出的代价是污染 chunked 流。
     */
    fun classify(writeOk: Boolean): PeerState =
        if (writeOk) PeerState.ALIVE else PeerState.GONE

    /**
     * 真做一次**写探测**：`sink` 往这条连接写一个字节并 flush，抛异常即返回 false。
     *
     * `sink` 由调用方给（`[HttpApi]` 传的是写合法 SSE 注释帧的那份实现），
     * 而不是在本函数里 `sock.getOutputStream().write(0)`：
     *   · 裸写一个字节会破坏 `Transfer-Encoding: chunked` 的分帧（见文件头）；
     *   · 每次都 `getOutputStream()` 会**再叠一层** BufferedOutputStream，
     *     前一次探测的字节可能还留在内层缓冲里，"写成功"就成了假象。
     * 任何异常都不外抛：调用方是生成循环，抛出去等于多一条崩溃路径。
     */
    fun probe(sink: () -> Unit): PeerState {
        return try {
            sink()
            PeerState.ALIVE
        } catch (_: Throwable) {
            PeerState.GONE
        }
    }
}
