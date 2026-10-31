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
 *     而生成循环持着它那一 slot 的 `slotLock`）在迟到的 15s socket 超时后触发取消，就会把此时
 *     真正在跑的那一轮（可能是 App 内正在看的对话）**当成自己**停掉。
 * 所以取消必须带**归属**：每个请求领一个自增的 [RequestCancel.Token]，只有「当前
 * 正在生成的那个 token」的取消才真的生效，过期的取消请求只会被记一笔日志。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 多 slot 之后（Issue #330）：从「单当前轮次」到「按 slot 的多轮次注册表」
 * ══════════════════════════════════════════════════════════════════════════
 * 单并发时代「当前那一轮」是唯一的，所以一张表、一个 token 就够。并发 slot 上线后
 * 同一时刻有多个轮次在跑，取消必须能指到具体某一个：本文件的注册表因此按 slot 建
 * （[enterSlot] / [cancelSlot] / [isSlotBusy]）。
 *
 * 旧 API **语义保留**而不是删掉：无参的 [enter] / [cancelCurrent] 等价于 slot 0，
 * 于是 App 内停止按钮与既有单测一行不改仍成立。App 内「停止」按钮**只停 slot 0**
 * （判据与动作都只看 slot 0，设计文档 §9.2.2）；这样做的代价是 slot 0 成了一个
 * 隐式约定 —— 但它换回来的是「旧路径零改动」，而 slot 0 的语义在 §5 里写明了
 * （App 内聊天占 slot 0，与 HTTP 的 slot 1.. 对等）。
 * 真正的新增只有按 slot 取消那一条（[cancelSlot]），而它正是本期最需要钉住的东西：
 * 一个槽位上的迟到取消打到别的槽 = 「用户看到别人的回答被掐断」。
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
 * ══════════════════════════════════════════════════════════════════════════
 * 取消的归属：从「这一格」到「那一刻占着这一格的是谁」（ISSUE #450 第二次现场）
 * ══════════════════════════════════════════════════════════════════════════
 * 上一版把「prefill 窗口里的取消」记成**按格子的一张欠账表**（`pendingCancels`），
 * 由这一格的**下一次登记**兑现（[adopt]）。它在单请求单轮次上是自洽的，一旦
 * 请求与轮次不再一一对应就整条错位：
 *
 *   · 一轮可能**永远不登记**（prefill 失败、被上下限拦下、用户又按了一次停止）——
 *     那笔账于是没有任何兑现对象，唯一的出口是**很久之后**这一格上的另一轮；
 *   · 而"这一格上的下一轮"与它**没有任何关系**：单格池里 App 连点两次「生成」、
 *     多格池里同一下标被反复发出去，撞上的概率都不低。
 *
 * 用户报的那一幕正是它：App 内打断 prefill 之后，点一次「重新生成」看到
 * 「取消在登记之前就到了」，**再点一次**才有字 —— 第一次是把上一轮的旧账吃掉了。
 * 同一族的另一面更糟：HTTP 的 `/v1/abort` 打在 prefill 上之后，下一轮无关请求
 * 一开跑就被取消（`tools/cancel` 里 2d 节记的就是这条）。
 *
 * 根因不是"记账记错了格子"，而是**账的形状本身**：它把「谁该被停」记成了
 * 「哪一格该被停」，而这两件事只在"一格同一时刻只有一个请求、且它一定会登记"
 * 时才等价。修法是把账换成**身份**：
 *
 *   · 「占着这一格的人」= 那一刻占着这一格的是谁（由调度器给出：lease + 来源）。
 *     它从 `acquire` 那一刻就成立，正好覆盖 prefill 窗口；
 *   · 取消时把取消**记在那个身份上**（[claim]），而不是记在格子上；
 *   · 登记时（[adopt]）只接受**同一个身份**的账 —— 换了租约就是另一个请求，
 *     它的账与这一轮无关，当场作废。
 *
 * 于是「账被下一轮吃掉」这条路径在**判据**上就不成立了，不再依赖某个调用点
 * 记得去结账（`settleUnclaimed` 那条自相矛盾的判据因此整个删掉，见 [abandon]）。
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
         * 且在生成线程持**本 slot 的** `slotLock(slot)` 时取，读到的才是本轮的编号。
         */
        @Volatile var nativeEpoch: Long = 0L
            private set

        /**
         * 本轮登记在哪个 slot 上（Issue #330）。只作**可观测与自证**用
         * （日志里能看出"这一轮占的是 2 号槽"），不参与取消判定 ——
         * 取消一律由 [RequestCancel.cancelSlot] 按下标查表，不靠 token 反查。
         */
        @Volatile var slot: Int = 0
            private set

        /**
         * 登记这一轮**是谁的**（发起方的凭证，今天就是 `SlotScheduler.Lease`）。
         *
         * 用途只有一个：重试时把**自己上一次**留下的脏登记摘掉
         *（见 [abandonCancelOwnedBy]）。它不参与"能不能取消"的判定 ——
         * 取消一律按下标查表，不靠凭证反查。
         */
        @Volatile var owner: Any? = null
            private set

        /** 登记 slot（只应在轮次开始时调用一次）。 */
        fun bindSlot(s: Int) { slot = s }

        /** 绑定本轮归属凭证（只应在轮次开始时调用一次，与 [bindSlot] 同处）。 */
        fun bindOwner(o: Any?) { owner = o }

        /** 绑定本轮在 native 侧的编号（只应在轮次开始时调用一次）。 */
        fun bindNativeEpoch(epoch: Long) { nativeEpoch = epoch }

        /** 停在哪个 stop 串上时为 true —— `finish_reason` 要区分 `stop` 与 `length`。 */
        @Volatile var stopHit: Boolean = false
            private set

        /**
         * 本次取消**是冲着这一轮来的**（ISSUE #450：登记之前就已经在算的那一段）。
         *
         * 与 [requested] 分开是必须的 —— 两者在这一格上**不同时**成立：
         *   · [requested] = "我这一轮别算了"，生成循环每步查它；
         *   · [targeted]  = "别算的是**这一轮**"，取消端点在**登记表里还没有它**时用它。
         *
         * 后者正是 [adopt] 那条路存在的理由：一轮的登记发生在
         * `startCompletion` **成功之后**，而 prefill 就在 `startCompletion` 里跑
         * （真机上是好几秒）—— 那一段里表里是空的，于是 `/v1/abort` 拿到 null、
         * 回一句 `aborted:false`，客户端只看到自己断流，服务端照旧算满 `max_tokens`。
         * 这就是用户报的「按了停止，app 还是后台算完了」（ISSUE #450）。
         */
        @Volatile var targeted: Boolean = false
            private set

        /** 因客户端离开 / `/v1/abort` 而停时为 true；`finish_reason` 报 `cancelled`。 */
        val cancelled: Boolean get() = requested

        /** 标记取消；返回**本次调用**是否首次置位（用于「只记一次日志」）。 */
        fun request(): Boolean {
            if (requested) return false
            requested = true
            return true
        }

        /** 标记取消**冲着这一轮**来（只有 [RequestCancel.cancelSlotOrMark] 该调）。 */
        fun markTargeted() {
            targeted = true
        }

        /**
         * 被一个"登记之前就发出的取消"追上（[RequestCancel.adopt] 调）。
         *
         * 只置 [requested]，**不碰** [targeted]：后者是"这一次取消为谁而来"的记账，
         * 由发出它的那一刻落；追上之后就没用了，清掉反而会让日志少一个可读的事实。
         */
        fun inheritCancellation() {
            requested = true
        }

        /** 标记本轮命中 stop 序列（恰在一次，防止重复）。 */
        fun markStopHit() {
            stopHit = true
        }
    }

    /**
     * 按 slot 建的多轮次注册表（Issue #330 阶段 2）。
     *
     * 多 slot 之后**同一时刻有多个轮次在跑**，取消必须能指到具体某一个：
     * 一个槽位上的迟到取消若打到别的槽，就是「用户看到别人的回答被掐断」——
     * 而本文件头记的那次事故正是它的单 slot 版。
     *
     * 表本身只有一条不变量：**一个下标同一时刻最多一个轮次**。
     * 它由 [enter] 保证（同下标已有轮次时后到的那个拿到 null，由调用方 503），
     * 而 [leave] 必须只摘自己那一份（`===` 判等，不是按下标清空）——
     * 否则迟到的 leave 会清掉后来者在同一个下标上开始的轮次。
     */
    private val lock = Any()
    private val rounds: MutableMap<Int, Token> = HashMap()

    /**
     * **取消记在谁头上**（ISSUE #450 第二次现场）：`身份 -> 那一轮该停`。
     *
     * 它顶替了旧版的「按格子记的欠账表」（`pendingCancels`），形状的差别就是
     * 修法的全部内容 —— 旧版记的是**哪一格**该停，这一版记的是**谁**该停：
     *
     *   · 谁 = 那一刻占着这一格的那个请求 —— 形参叫 `occupant`，类型只能是 `Any?`：
     *     本文件是纯逻辑层，**不认**调度器的 `Lease`（那份依赖一引进来，三份宿主套件
     *     `run_slot_tests.sh` / `run_ctx_budget_tests.sh` / `run_health_tests.sh` 就在
     *     有 kotlinc 的机器上编不过 —— 那正是 [AbortGate] 存在的理由）。判等用 `===`。
     *     它从 `acquire` 那一刻就成立，正好覆盖 prefill 窗口（那时取消注册表里
     *     还没有这一轮，但这一格已经有主了）；
     *   · 兑现只认**同一个身份**（[adopt]）。换了租约 = 换了请求，那一笔无论
     *     还留没留着，都与这一轮无关 —— 于是「账被下一格/下一轮吃掉」这条路径
     *     在判据上就不成立了。
     *
     * ⚠ 旧版那条账为什么必须删掉（而不是"再补一条结账"）：它的存在期限一直没解决 ——
     *   `settleUnclaimed` 的判据是「这一格此刻有没有人」，而"有钱"恰恰是它自己
     *   记上去的那一刻成立的，于是**它永远结不掉**，只能等下一个人来吃。用户报的
     *   「点一次重新生成没字、再点一次才有」就是它被吃掉的两次。
     *
     * ⚠ 每一条都带**期限**（[CLAIM_TTL_NANOS]）：prefill 窗口是秒级的，一次取消不该在分钟级
     *   之后还生效。这与"身份"是两道独立的闸门，任一条不成立就作废 —— 身份治
     *   "被别的请求吃掉"，期限治"被同一份 lease 复用（重试）吃掉"。
     */
    private val claims: MutableMap<Any, Claim> = HashMap()

    /** 一笔账：什么时候记下的（期限判据用，见 [CLAIM_TTL_NANOS]）。 */
    private class Claim(val atNanos: Long)

    /**
     * 一笔账的存在期限。prefill 是**秒级**的（真机上长 prompt 好几秒），而这条链上
     * 所有判据的时间尺度都是"这一轮之内" —— 于是 30s 既不会误作废一次真的 prefill
     * 取消，也不会让一笔账活到下一次用户操作之后。
     */
    private const val CLAIM_TTL_NANOS = 30_000_000_000L

    /**
     * 这一格上那一笔账**作废**（取消没能落到任何一轮上：这一格空着、或占着它的人
     * 已经收尾）。旧版由调用方用 `settleUnclaimed(claimed=...)` 来结，而那条判据
     * 自相矛盾（见 [claims] 的注释）；现在"是谁"已经记清楚，作废也就有了唯一所指。
     *
     * @return 是否真的作废了一笔（调用方据此决定要不要打一行日志）
     */
    fun abandon(occupant: Any?): Boolean {
        val id = occupant ?: return false
        return synchronized(lock) { claims.remove(id) != null }
    }

    /**
     * 登记一轮时把「**冲着这一轮（这个身份）来的**取消」兑现（[enterSlot] 的唯一调用方）。
     *
     * 只有"本轮登记的那一刻，这个身份上正记着一笔"才置位 —— 判断与置位在
     * **同一把锁**里，所以不存在"查的时候没有、置位的时候已经来了"的窗口。
     *
     * 兑现是**一次性**的：取走即删。这一轮于是既能"立即收尾"，也不会把账留给下一轮
     * —— 后者正是用户报的那一幕（点一次重新生成没字、再点一次才有）。
     *
     * ⚠ 换了身份（`owner` 不是记这笔账的那一个）就**不认**：那一刻占着这一格的
     *   是别人，这笔取消与这一轮无关。旧版按格子记，这一档只能靠"格子别撞上"，
     *   而单格池里它一定会撞上。
     */
    fun adopt(occupant: Any?, t: Token): Boolean {
        val id = occupant ?: return false
        // 判断与清账在**同一个临界区**里：拆成两次加锁的话，两个并发登记会各自看到
        // "欠着"，于是把两轮同时停掉（而欠账只有一笔）。
        val c = synchronized(lock) {
            val c = claims.remove(id)
            if (c != null && System.nanoTime() - c.atNanos > CLAIM_TTL_NANOS) null else c
        } ?: return false
        t.inheritCancellation()
        t.markTargeted()
        return true
    }

    /**
     * 这个身份上是否还记着一笔（仅读数：日志与 `/v1/abort` 的 `aborted`）。
     *
     * 进锁读（值是可变 map）：它的写方是 HTTP 连接线程 / 主线程，与生成线程不抢
     * 同一把锁（生成循环每步读的是 token 上的 `requested`，那是 `@Volatile` 的）。
     */
    fun isClaimed(occupant: Any?): Boolean {
        val id = occupant ?: return false
        return synchronized(lock) {
            val c = claims[id] ?: return false
            if (System.nanoTime() - c.atNanos > CLAIM_TTL_NANOS) { claims.remove(id); false } else true
        }
    }

    /** 此刻记着几笔账（仅读数；`/health` 与日志用）。 */
    val claimCount: Int get() = synchronized(lock) { claims.size }

    /**
     * 无参 [enter] 登记的轮次所在下标。
     *
     * 旧 API（`enter()` / `cancelCurrent()`）没有 slot 概念，语义上就是
     * 「当前那一轮」。多 slot 之后它退化为 slot 0 的一个**别名** ——
     * 这样 UI 路径与既有单测一行不改仍成立（设计文档 §6）。
     */
    private const val LEGACY_SLOT = 0

    /**
     * 生成开始：登记归属；返回本轮 token（生成循环持它逐 step 判）。
     *
     * 无参版 = [enterSlot] 的 slot 0 别名，语义与旧版逐字节一致。
     */
    fun enter(): Token = enterSlot(LEGACY_SLOT)!!

    /**
     * 在指定 slot 上登记一轮生成；该 slot 已有轮次时返回 **null**（调用方据此 503）。
     *
     * [owner] 是发起方的凭证（`SlotScheduler.Lease`）：只给重试时的脏登记清理用
     *（见 [abandonCancelOwnedBy]），不参与判定。
     *
     * 返回 null 而不是覆盖：同一 slot 上两轮并发就是两路写同一个 ctx，
     * 而它的失效形态是静默的（不崩、不报错，只是内容互相污染）。
     * 让「抢占」在这里就被拒，比让 native 侧去分辨要安全得多。
     */
    fun enterSlot(slot: Int, owner: Any? = null): Token? {
        val t = Token()
        t.bindSlot(slot)
        t.bindOwner(owner)
        synchronized(lock) {
            if (rounds.containsKey(slot)) return null
            rounds[slot] = t
            return t
        }
    }

    /** 生成结束（正常 / 异常 / 取消都要走）：仅当自己仍是该 slot 的轮次时摘除。 */
    fun leave(t: Token) {
        synchronized(lock) {
            // 逐份判等后摘除，**不是** `rounds.remove(t.slot)`：迟到的 leave
            // 若按下标清空，会清掉后来者在同一个 slot 上开始的轮次。
            val it = rounds.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value === t) it.remove()
            }
        }
    }

    /**
     * 摘除**由 `owner` 这个凭证登记的那一轮**（没有则 no-op）。
     *
     * 这不是 [leave] 的别名，两者防的东西不同：
     *   · [leave] 由**生成期收尾**调用（normal / 异常 / 取消），手里有 token；
     *   · 本函数由**登记方**在重试时调用（`startCompletion` 失败后同一份 lease 再来一次）：
     *     那一轮 native 根本没跑起来，没人拿得到 token，于是那条登记会永远留着 ——
     *     之后每一次 `enterSlot` 都被拒，服务看起来彻底坏了（503「调度器与取消注册表不一致」）。
     *
     * 判据是**身份**（`owner === 这份 lease`），不是"这一格上有没有轮次"：
     * 无条件清空会把**别人**正在跑的那一轮摘掉，那比留下一条脏登记糟得多。
     */
    fun abandonCancelOwnedBy(owner: Any) {
        synchronized(lock) {
            val it = rounds.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.owner === owner) it.remove()
            }
        }
    }

    /** 该 slot 此刻是否已有轮次在跑（[enterSlot] 的伴生判据，便于调用方先查再登记）。 */
    fun isSlotBusy(slot: Int): Boolean = synchronized(lock) { rounds.containsKey(slot) }

    /**
     * 取消这一格上的一轮：**有轮次就置位，没轮次就记在这一格的主人头上**（ISSUE #450）。
     *
     * 与 [cancelSlot] 的唯一区别是后者那句 `?: return null` —— 而那句在 prefill 窗口里
     * 恒成立（一轮的登记在 `startCompletion` **之后**，prefill 就在 `startCompletion`
     * 里面跑）。于是"客户端按了停止"只换来 `aborted:false`，服务端把 `max_tokens`
     * 算完。用户报的原话是「我在客户端明明按了停止了，但是 app 还是后台算完了」，
     * 客户端那侧只看到 `Fetch request has been canceled`（它自己断的）。
     *
     * 没轮次时账记在 [occupant]（那一刻占着这一格的**那个请求**）上，等它自己登记时
     * 由 [adopt] 兑现。**只认这个身份**：换了一份租约就是另一个请求，它的账与这一轮
     * 无关 —— 旧版按格子记，于是"这一格的下一条请求"会把上一笔吃掉（见文件头）。
     *
     * [occupant] 为 null（这一格真的空着）= 没有可停的对象，账**不记**：记下来也没有
     * 人认领，只会变成很久之后某个无关请求的意外取消。
     *
     * @return 被置位的那一轮；没有轮次时返回 null（调用方仍可回 `aborted:true`，
     *         因为**取消意图已经记在这一格的主人头上**，只是兑现在下一跳）。
     */
    fun cancelSlotOrMark(slot: Int, occupant: Any?): Token? {
        val t = synchronized(lock) {
            rounds[slot] ?: run {
                if (occupant != null) claims[occupant] = Claim(System.nanoTime())
                null
            }
        } ?: return null
        // 有轮次那一档与旧行为逐字节一致：置位 + 标记"冲着这一轮来"。
        t.markTargeted()
        t.request()
        return t
    }

    /** 正在跑的轮次数（= 被占用的 slot 数；仅用于日志与 `/health`）。 */
    val roundCount: Int get() = synchronized(lock) { rounds.size }

    /**
     * 取消「当前正在生成的那一轮」。没有轮次在跑时返回 null。
     *
     * 多 slot 之后它退化为 slot 0 的别名（见 [LEGACY_SLOT]）：
     * App 内「停止」按钮走的仍是这条（**只停 slot 0**，设计文档 §9.2.2），语义与旧版一致。
     * 要停别的 slot 用 [cancelSlot]；[cancelAllSlots] 本期没有调用点。
     */
    fun cancelCurrent(): Token? = cancelSlot(LEGACY_SLOT)

    /**
     * 取消**整池**：把此刻所有在跑的轮次都置位，返回被停到的那几轮（空 = 本来没人跑）。
     *
     * ⚠ **本期没有调用点，也不许有**（设计文档 §9.2.2，用户已确认）：
     * App 内那个「停止」按钮**也只停 slot 0**（它就在 App 内聊天的抽屉里，停的就是
     * 「我正在看的那一路」），判据与动作都只看 slot 0；`/v1/abort` 同样只停指定 slot。
     * 一个客户端不许掐掉别人的回答 —— 那正是 [cancelSlot] 存在的全部理由。
     *
     * 保留它是因为「整池停止」这个形态已经随阶段 2 落地（表结构就是这个形状），
     * 删掉反而要在将来某次真要整池停止时重造；但**不上任何 UI 路径、不接 `/v1/abort`**。
     * 这条判据由 `tools/run_slot_tests.sh` 与阶段 5/6 的取消守卫正面钉住
     * （断言 `/v1/abort` 与停止按钮的路径上都不出现 [cancelAllSlots]）。
     *
     * 与 [cancelSlot] 同一时刻的一致性：先快照再逐个置位（不在持锁时做任何别的事），
     * 置位是幂等的，所以中途有轮次收尾也不会错。
     */
    fun cancelAllSlots(): List<Token> {
        val ts = synchronized(lock) { rounds.values.toList() }
        for (t in ts) t.request()
        return ts
    }

    /**
     * 取消指定 slot 上那一轮；该 slot 没有轮次时返回 null（no-op，不去停别人的 slot）。
     *
     * 这一条是本期最容易踩的雷：一个槽位上的迟到取消若打到别的槽，就是
     * 「用户看到别人的回答被掐断」。判据只看**这个下标**，绝不遍历、绝不兜底。
     */
    fun cancelSlot(slot: Int): Token? {
        val t = synchronized(lock) { rounds[slot] } ?: return null
        t.request()
        return t
    }

    /** 是否有轮次在跑（仅用于日志与 `/health` 展示，不做任何判定用）。 */
    val active: Boolean get() = synchronized(lock) { rounds.isNotEmpty() }

    /** 当前轮次是否已被取消（生成循环用来中止；与 token.requested 等价，保留给 UI 路径）。 */
    fun isCancelled(): Boolean = synchronized(lock) { rounds[LEGACY_SLOT]?.requested } ?: false

    /** slot 0 那一轮的 token；没有轮次在跑时返回 null。供 UI 侧引用（不参与判定）。 */
    fun currentToken(): Token? = synchronized(lock) { rounds[LEGACY_SLOT] }

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
