package com.xiaowan.localinference

import org.json.JSONObject

/**
 * 中间层要记住的那一屏读数：「服务端上一轮丢了哪几轮」（ISSUE #452 第 3 步）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么记的是它，而不是 token 数
 * ══════════════════════════════════════════════════════════════════════════
 * 中间层能做对的那件事只有一件：**按服务端已经说明的坐标删整轮**。
 * token 数它量不出来（没有 tokenizer，也不该有 —— 见 [RoundGeometry] 文件头），
 * 而"我猜服务端下一轮会丢多少"是主动多删，正是 ISSUE #426 修掉的东西。
 *
 * 所以账本里只存三个数：
 *   · 上一轮 `local_rounds.rounds`（每一轮的 `i` / `m` / `kept`）；
 *   · 上一轮 `local_ctx.messages`（丢了几条，用于日志对账）；
 *   · 上一轮 `local_ctx.ask_only`（历史丢光了 —— 这一档坐标不可用，见 [RelayTrim.apply]）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它是**缓存**，不是判据（三条纪律）
 * ══════════════════════════════════════════════════════════════════════════
 *   · 解析失败一律当"没有上一轮"（原样转发），绝不抛给调用方 —— 中间层的失效
 *     必须是"退化成直连"，不是"502"；
 *   · 只认**这一条会话**的读数（`session` 变了就丢掉）—— 换了会话之后上一轮的
 *     坐标一条都对不上，而"对不上"在删的时候是灾难；
 *   · 只由**生成成功**的响应写入 —— 400/503 的响应体里没有 `local_ctx`，
 *     而拿一份空读数去覆盖旧的，会让下一轮"看起来没有坐标"（其实上一轮有）。
 */
object RelayLedger {

    /** 一条会话的上一轮读数（值语义，不可变 —— 读的时候不加锁也能拿到一致的一份）。 */
    class Snapshot(
        val rounds: List<RelayTrim.RoundMark>,
        val dropped: Int,
        val askOnly: Boolean,
        /**
         * 这一轮服务端**留下了几条**消息（`local_ctx.kept`；`< 0` = 这份响应里没报）。
         *
         * 只服务 [matchesChain] 的自校：上一轮留下的那几条，这一轮必须还在原处 ——
         * 而"那几条"在客户端原件里就是从 `m` 起、连续 `kept` 条。
         */
        val kept: Int = -1,
        /** 这一轮真正复用了多少 token（`local_ctx.reuse`；0 = 没接上前缀）。 */
        val reuse: Int = 0,
        /**
         * 这份读数来自**首帧**（只有 `kept_rounds = 1` 与复用几何，没有轮次坐标）。
         *
         * 它回答的是一件比"删哪几轮"更小的事：**上一轮留下的那几轮还在不在**。
         * 中间层据此知道"服务端确实接上了我上一轮送的那一份"，于是可以继续按
         * 同一条口径裁；但它**不许**据此算出"该删几轮"——那需要 [rounds]。
         */
        val keptRounds: Boolean = false,
        /**
         * 这份坐标是服务端那一轮见到的**总轮数**（`kept + dropped`；0 = 没量到）。
         *
         * 与 `rounds.size` 是**两个数**：后者只有"服务端真丢过"那一轮才有值，而
         * 中间层恰恰让服务端此后别再丢 —— 拿它当"上一轮有多少轮"比，会一轮都不删
         * （见 [RelayTrim.Prev.prevRoundCount]）。
         */
        val roundCount: Int = 0,
        /**
         * 服务端报的起喂点几格（`prompt_prefix` 的 `tok_start` / `tok_end` / `chars` / `cpq`）。
         *
         * 空数组 = 这一轮没报。它让中间层能答一个 [rounds] 答不了的问题：
         * **服务端这一轮真丢了历史，而中间层上一轮没删过**（第一轮 relay / 刚重启）。
         * 那一档若只看 `carriedRounds`，结论永远是"照上一轮的决定 = 删 0 轮"，
         * 于是服务端每轮都在丢、中间层一次都没删过 —— UNS-452 真机就是它。
         */
        val prefix: IntArray = RelayTrim.EMPTY_PREFIX
    ) {
        fun toPrev(): RelayTrim.Prev =
            RelayTrim.Prev(rounds, dropped, askOnly, keptRounds, roundCount, prefix)

        /** `/health` 上的一句话（不参与判据）。 */
        fun describe(): String =
            "rounds=${rounds.size} round_count=$roundCount dropped=$dropped ask_only=$askOnly" +
                (if (keptRounds) " kept_rounds" else "") +
                (if (prefix.size >= 4) " prefix=${prefix[0]}/${prefix[1]}@${prefix[3]}" else "")
    }

    /**
     * 从一份响应体里读出上一轮的坐标（`null` = 这份响应里没有可用的坐标）。
     *
     * 只读两处：`local_ctx.rounds`（数组）与 `local_ctx.messages` / `ask_only`。
     * `local_ctx` 本身就是"这一轮服务端做了什么"的唯一出口（见 `ContextBudget.localCtxField`），
     * 所以这里**不再往响应体里加任何顶层字段** —— 同一个事实两个来源，缺一半时
     * 没人说得清是谁漏的。
     */
    fun parse(body: String): Snapshot? {
        if (body.isEmpty()) return null
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val ctx = root.optJSONObject("local_ctx") ?: return null
        val arr = ctx.optJSONArray("rounds")
        // 首帧那一份（ISSUE #452「拿回真流式」）**不带 `rounds`** —— 它只说「这一轮
        // 接上了上一轮留下的缓存」（`kept_rounds = 1`），外加「这一轮服务端留下几轮、
        // 又丢了几轮」（`kept_ctx_rounds` / `kept_ctx_dropped`）。这一档也要记：
        // 下一轮中间层需要的正是那两个数。
        // ⚠ 但它**不能**写成一份"全是 kept 的假坐标"：那等于告诉中间层"一轮都没丢"，
        //   而它并没有量到哪一轮在哪。所以走 `keptRounds` 这一档，见 [Snapshot]。
        // ⚠ 判据里**不许**再夹一个 `lcp > 0`：`kept_rounds` 挂的是"服务端这一轮
        //   留下了几轮"，而 `lcp` 说的是"复用了多少 **token**"。两个量纲。短会话上
        //   服务端完全可能"轮次对得上、一枚都没复用"，那时按 `lcp` 判等于把一份有效
        //   坐标丢掉 —— 而丢掉的表现正是 `relay_forwarded` 恒 0，与"这一层根本没生效"同形。
        val prefix = prefixOf(ctx)
        if (arr == null) {
            val keptFlag = ctx.optInt("kept_rounds", 0) == 1
            val droppedMsgs = ctx.optInt("messages", 0)
            // 另外两档也算"有输入"：`kept_rounds = 1`（复用几何在，接上了前一轮），
            // 以及 `messages > 0`（服务端这一轮**真动手丢过历史**，而几何可能是空的 ——
            // 冷启动 / 换会话 / 裁剪后起点挪了那几档）。后者正是 INV-#452 现场：服务端
            // 每轮都在丢，而中间层一次都没删过，因为它把"没几何"读成了"没坐标"。
            return if (keptFlag || droppedMsgs > 0)
                Snapshot(emptyList(), droppedMsgs, ctx.optBoolean("ask_only", false),
                    keptRounds = true, prefix = prefix,
                    // 服务端这一轮一共见到几轮 = 它留下的 + 它又丢的 + 中间层上一轮
                    // 已经前置删掉的（`kept_ctx_dropped` 报的就是那个数）。
                    // 首帧那一份没报这两格（= 0）时，整格按 0 交下去 —— [RelayTrim]
                    // 见到 0 会退回老判据，不会拿一个口径不明的数去删。
                    roundCount = ctx.optInt("kept_ctx_rounds", 0) +
                        ctx.optInt("kept_ctx_dropped", 0) + RelayTrim.carriedRounds)
            else null
        }
        // 空数组 = 服务端这一轮报了坐标，但一轮都没留下（历史全丢）—— 那是
        // `ask_only` 的另一副面孔，`RelayTrim` 拿它走"不删"那一档，不是"没有坐标"。
        if (arr.length() == 0)
            return Snapshot(emptyList(), ctx.optInt("messages", 0),
                ctx.optBoolean("ask_only", false), keptRounds = true, prefix = prefix)
        val marks = ArrayList<RelayTrim.RoundMark>(arr.length())
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: return null
            val i = o.optInt("i", -1)
            if (i < 0) return null
            val kept = o.optInt("kept", 0) == 1
            marks.add(RelayTrim.RoundMark(i, o.optInt("m", -1), kept))
        }
        // 收尾那一份的轮数 = 表里每一轮的 `i` 最大值 + 1（`i` 从 0 起、含被丢的那些），
        // 再补上**中间层这一轮已经前置删掉**的轮数 —— 那几轮不在服务端输入里，
        // 但仍属于"上一轮服务端一共见到几轮"的客户端原件口径（下一轮拿它与客户端
        // 送来的轮数比）。不补就会每轮少算 carriedRounds 轮，判据一路判"历史被改过"。
        var maxIdx = -1
        for (m in marks) if (m.index > maxIdx) maxIdx = m.index
        return Snapshot(marks, ctx.optInt("messages", 0), ctx.optBoolean("ask_only", false),
            kept = ctx.optInt("kept", -1), reuse = ctx.optInt("reuse", 0), prefix = prefix,
            roundCount = (if (maxIdx >= 0) maxIdx + 1 else arr.length()) +
                RelayTrim.carriedRounds)
    }

    /**
     * 读 `local_ctx.prompt_prefix` 那几格（空数组 = 没报 / 报得不全）。
     *
     * 四格必须**一起**读：只拿到 `tok_start` 而不知道 `tok_end`，就没法算"落在整段的
     * 百分之几处"，而中间层要的正是那个比值（它的字符轴与服务端的估算口径不是同一条）。
     */
    private fun prefixOf(ctx: JSONObject): IntArray {
        val p = ctx.optJSONObject("prompt_prefix") ?: return RelayTrim.EMPTY_PREFIX
        val start = p.optInt("tok_start", -1)
        val end = p.optInt("tok_end", -1)
        val chars = p.optInt("chars_start", -1)
        val cpq = p.optInt("cpq_x1000", -1)
        // 四格**一起**才有用：`chars_start` 是起喂点在客户端原文里的字符位（中间层拿它
        // 在自己那份 messages 上找到该从哪一条起保留），`cpq_x1000` / `tok_end` 是它的换算
        // 依据与对账口径（"这一枚是怎么换出来的"）。
        // 缺任何一格都给空数组：只按 `tok_start` 去猜字符位会切错半轮，
        // 而切错的代价是删掉用户手里的对话。
        if (start < 0 || end <= 0 || chars <= 0 || cpq <= 0) return RelayTrim.EMPTY_PREFIX
        // 第五格 `tail_rounds`（起喂点后面还剩几轮）**可以缺**：它只是给中间层
        // "沿轮边界核一遍"用的加固，没有它照样能按字符位对齐（见 `RelayTrim.byPrefix`）。
        // 缺了就填 -1（= 不知道），与"服务端没数出来"同义。
        val tail = p.optInt("tail_rounds", -1)
        return intArrayOf(start, end, chars, cpq, if (tail > 0) tail else -1)
    }

    /**
     * 从 SSE 流里挑出带 `local_ctx` 的那一帧（`null` = 这一轮没报坐标）。
     *
     * 为什么不逐帧解析：带坐标的第一帧之后就不会再有第二份（`local_ctx` 描述的是整轮），
     * 而流可能很长，逐帧 `JSONObject` 一遍是每轮几十次无用的解析。
     *
     * ⚠ 同一份坐标现在挂在**两处**（响应头 / 首帧，ISSUE #452「拿回真流式」），
     *   所以调用方拿到头的第一份就该记账 —— 这个函数留给"只有帧"的那一档。
     */
    fun parseStream(frames: List<String>): Snapshot? {
        // 从后往前找：收尾帧在末尾，而正文帧不可能带 `local_ctx`。
        for (k in frames.indices.reversed()) {
            if (frames[k].indexOf("\"local_ctx\"") < 0) continue
            parse(frames[k])?.let { return it }
        }
        return null
    }

    /**
     * 从**一整条 HTTP 响应**（含状态行与头、含 SSE 分帧）里抽出本轮的坐标。
     *
     * 为什么不能只 `JSONObject(body)`：relay 拿到的是 `handleChat` 写进缓冲的原始字节，
     * 它前面有状态行与头，流式那一档还有 chunked 分帧（`<hex>\r\n<data>\r\n`）。
     * 直接 `JSONObject` 一定抛 —— 而抛出去之后整条链就变成"永远没有坐标"，
     * 症状是中间层**静默退化成直连**（结果全对，只是没省下任何东西）。
     *
     * 判据只有一个：找 `"local_ctx"` 那一处，往前退到它所属的那个 `{`，再交给
     * [parse]。不需要完整的 HTTP / chunked 解析 —— 那些都是"外壳"，
     * 而这里要的是外壳里那一个对象。
     */
    fun parseRelayResponse(raw: String): Snapshot? {
        if (raw.isEmpty()) return null
        val headEnd = raw.indexOf("\r\n\r\n")
        if (headEnd > 0) {
            // 头里那一份（`X-Relay-Coord: local_ctx:{...}`）**先读**：流式那一档
            // 头与首帧带着同一份坐标，而放开直写的那一刻缓冲里可能只有头 + 半帧 ——
            // 那时 body 根本不是一份完整 JSON。头里那份是单行 JSON，够解析。
            val headPart = raw.substring(0, headEnd)
            val hv = headPart.indexOf("X-Relay-Coord:")
            if (hv >= 0) {
                val open = headPart.indexOf('{', hv)
                if (open > hv) {
                    val close = matchBrace(headPart, open)
                    // 头里那一份的键名是 `local_ctx:`（`coordHeadOf` 去掉前导逗号之后的
                    // 形状），所以要往前退到 `local_ctx` 这个键上才算一份完整对象 ——
                    // 只切 `{...}` 时 `parse` 读不到 `root.local_ctx`，一律返回 null。
                    val ctxAt = headPart.lastIndexOf("local_ctx:", open)
                    if (close > open && ctxAt in hv until open) {
                        parse(headPart.substring(ctxAt, close + 1))?.let { return it }
                    }
                }
            }
            // 快路径：非流式的响应体就是一个 JSON 对象（`{` 在头之后的第一个非空字节）。
            val body = raw.substring(headEnd + 4)
            if (body.startsWith("{")) parse(body)?.let { return it }
        }
        // 慢路径：SSE 收尾帧（`data: {...}`），或 body 里有多个 JSON 帧。
        val at = raw.lastIndexOf("\"local_ctx\"")
        if (at < 0) return null
        // ⚠ 要的是**包含 `local_ctx` 的那个对象**，不是 `local_ctx` 自己：
        //   `parse` 读的是 `root.local_ctx`。所以先取 `local_ctx` 前面最近的 `{`，
        //   它此刻是 `local_ctx` 的容器；若那一枚闭合之后 `local_ctx` 仍在外面，
        //   再往外退一层（最多退到 body 开头）—— 三层足够，再深就不是这份协议了。
        var open = raw.lastIndexOf('{', at)
        var guard = 0
        while (open >= 0 && guard < 4) {
            val close = matchBrace(raw, open)
            if (close > at) return parse(raw.substring(open, close + 1))
            open = raw.lastIndexOf('{', open - 1)
            guard++
        }
        return null
    }

    /** 从 `open` 那一枚 `{` 起找配对的 `}`（跳过字符串里的花括号与转义）。 */
    private fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (k in open until s.length) {
            val c = s[k]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return k }
            }
        }
        return -1
    }

    /**
     * 中间层这一份账本（**只在一次进程生命周期里有效**）。
     *
     * 不落盘：重启之后模型与 KV 账本都已作废，上一轮的坐标描述的那份 prompt 也
     * 就不存在了 —— 落盘只会多一份"什么时候该清"的判据，而那正是这类静默 bug 的温床
     *（同一条理由见 `HttpApi.ragWidthStore` 的注释）。
     */
    private val bySession = HashMap<String, Snapshot>()

    /**
     * 每条会话**这一轮喂进去的那一份**的正文顺序指纹（角色 + 正文，逐条）。
     *
     * 它与 [bySession] 必须同生同灭：一份坐标配一份指纹，靠它判断「这份坐标描述的
     * 那段历史这一轮还在不在」（见 [matchesChain]）。分开清会留下「有坐标没指纹」的
     * 半份状态，而那时判据只能猜。
     */
    private val chains = HashMap<String, List<Pair<String, String>>>()
    private val lock = Any()

    fun get(session: String): Snapshot? = synchronized(lock) { bySession[session] }

    fun put(session: String, snap: Snapshot, chain: List<Pair<String, String>> = emptyList()) {
        synchronized(lock) {
            bySession[session] = snap
            chains[session] = chain
        }
    }

    /**
     * 这一轮的客户端原件，是不是**上一轮那份原件往后长出来的**（ISSUE #452 真机）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它在防的坏法：客户端只有一份 history，服务端却有保留集
     * ══════════════════════════════════════════════════════════════════════════
     * 中间层唯一的记忆是"服务端上一轮怎么裁的"。而客户端可能**换了一份历史**
     * 用同一个会话键打进来（同一台设备两个 App、进程重启后接上别的会话、用户在上层
     * 删掉几轮再发）—— 那时上一轮那份坐标描述的那段文字，这一轮**已经不在**了。
     *
     * 拿它去删的后果不是"少删一轮"（可接受），而是**删错**：坐标说"从第 3 条起"，
     * 而第 3 条已经是另一段对话的开头 —— 用户手里的对话被悄悄改掉。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 判据：上一轮**原件**是这一轮的前缀（不是"保留集"的窗口）
     * ══════════════════════════════════════════════════════════════════════════
     * 客户端每轮把整份历史重发，正常形状只有"往后长"：上一轮的末条 user 还在
     * 原处，后面多出它的 assistant 与新提问。于是上一轮**送进来的那份原件**
     * 逐条等于这一轮的前缀。
     *
     * 账本以前存的是"中间层转发出去的那一份"（= 原件减掉前置删掉的几轮），再用
     * 服务端报的 `m`（**保留集**下标）去开窗 —— 三份坐标系混在一起：`m` 与转发件
     * 差着服务端丢掉的条数，转发件与原件又差着中间层删掉的轮数。真机表现是
     * 第二轮就判"对不上"，`relay_forwarded` 恒 0，而 `relay_reason` 说得挺诚恳。
     *
     * 所以现在只比**同一份东西**：上一轮的原件 vs 这一轮的原件。前缀还在 = 坐标
     * 描述的那段历史还在原处；有一处不同（或这一轮更短）= 原样转发。
     *
     * ⚠ 每一道门槛都朝"不可用"倒：指纹缺失（进程刚起 / 上一轮没记）也一律当
     *   "对不上"。代价只是这一轮退化成直连，而误删不可接受。
     */
    fun matchesChain(session: String, cur: List<Pair<String, String>>): Boolean {
        val prev: List<Pair<String, String>>
        synchronized(lock) {
            if (bySession[session] == null) return false
            prev = chains[session] ?: return false
        }
        if (prev.isEmpty()) return false
        // 正常形状只往后长：上一轮的原件必须逐条等于这一轮的前缀。
        // 比整份（而不是只比"保留集那一段"）的理由见上面：只有这一份是与客户端
        // 直接对齐的，其余两份都各自差着别人的删除量。
        if (cur.size < prev.size) return false
        for (k in prev.indices) if (cur[k] != prev[k]) return false
        return true
    }

    /** 换会话 / 取消会话时丢掉那一份（`null` = 清全部）。 */
    fun clear(session: String? = null) {
        synchronized(lock) {
            if (session == null) { bySession.clear(); chains.clear(); forwarded.clear() }
            else { bySession.remove(session); chains.remove(session); forwarded.remove(session) }
        }
    }

    /**
     * 本轮**中间层前置删了几轮 / 客户端原件有几轮**（对账用，不覆盖 [Snapshot]）。
     *
     * 它回答的是"上一轮我删了几轮" —— 与 `Snapshot.dropped`（服务端上一轮丢了几条）
     * 是两个数，分开报才能看出"我删得对不对"。判据一处都不用它们。
     */
    class Forwarded(
        val rounds: Int,
        val clientMessages: Int,
        /**
         * 留下来的那份在**客户端原件**里从第几条起（`-1` = 这一轮没删）。
         *
         * 与 [rounds] 成对读：一个答"删了几轮"，一个答"删到哪一条"。
         * 只有轮数时，"照上一轮的决定删"只能按"首条 system 之后"猜一个起点，
         * 而服务端把 system 一起丢掉时那个猜法每轮少发一条（ISSUE #452，见
         * `RelayTrim` 文件头）。
         */
        val keepFrom: Int = -1
    )

    private val forwarded = HashMap<String, Forwarded>()

    /** 记下这一轮转发时的删除量（[handleRelayChat] 在发出请求**之前**调）。 */
    fun markForwarded(session: String, rounds: Int, clientMessages: Int, keepFrom: Int = -1) {
        synchronized(lock) { forwarded[session] = Forwarded(rounds, clientMessages, keepFrom) }
    }

    fun forwardedOf(session: String): Forwarded? = synchronized(lock) { forwarded[session] }

    /** 当前记着几条会话（`/health` 读数，不参与判据）。 */
    fun size(): Int = synchronized(lock) { bySession.size }
}
