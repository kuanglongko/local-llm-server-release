package com.xiaowan.localinference

/**
 * 「嵌入合批到底有没有生效」的**读数**（ISSUE #269 第 1 步：先量，再改）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么必须有它
 * ═══════════════════════════════════════════════════════════════════════
 * 合批（一次前向编 n 条）落在 main 上已经有一阵了，但它是**纯加速**：
 * 生效与不生效在协议上完全看不出来 —— 同样的 200、同样的向量、同样的检索结果。
 * 唯一的读数此前是"回落时打一行 `[embedBatch]`"，而那行只在**出错**时出现：
 *
 *   · 合批正常工作时，日志里**什么都没有**；
 *   · 合批一次都没生效（每批都回落逐条）时，也只有一行行「整批不可用」——
 *     而那些行与"某一条文本空"混在一起，看不出"整轮建库一条都没合批"。
 *
 * 于是"合批到底省了多少"只能靠推断。这与 `kv_cache` 那三个字段是**同一个病**：
 * 纯加速的东西没有可观测面，回归里静默失效而无人发现。
 *
 * 所以这一层做两件事，都要求"只给读数、不驱动逻辑"：
 *   1. [record]：一批编完之后记一笔（走了合批 / 回落逐条、几条、多少毫秒）；
 *   2. [snapshot] / [reset]：给 `/health` 与日志一个可读的累积量。
 *
 * ⚠ **进程级**累积量，不是建库级：跨多次建库累加，由用户主动清零
 * （或进程重启清零）。理由与 `kv_rounds` 一致 —— "本次建库合批了几批"是
 * 一次性的，而"这个模型在这台机器上到底能不能合批"是**跨建库**的结论，
 * 后者才是用户配置批大小时要的读数。建库级明细仍走日志（一行批级汇总）。
 *
 * ⚠ 但建库那一行报的**不是**这份累计 —— 是 [delta] 减出来的**本段**。
 * 两者是**两个问题**（"这次点得怎么样" vs "这模型在这台机器上行不行"）：
 * 此前用同一个数顶着，那行只好写成"本轮"，而它其实报的是累计 —— 用户拿它
 * 比"批4 vs 批1"时读到两个几乎相等的数（相邻两次只差 4 条），会读成
 * "批大小没生效"。所以累计归 [snapshot] / `/health`，本段归 [delta] / 日志。
 *
 * ⚠ 这一层**不改任何判定**：[record] 只累加计数，返回 Unit。
 * 让它去决定"要不要回落"就是把"观测"与"决策"焊在一起 ——
 * 那时改一个是改两个，而它们的正确方向相反（观测要忠实，决策要保守）。
 */
object EmbedBatchStats {

    /**
     * 一批走了哪条路 —— 只有这两种，且它们**互斥**（一批不会既是合批又是回落）。
     *
     * 为什么不并成"批数 / 条数"两个数就完事：那两个数读不出"到底走没走合批"，
     * 那正是本 issue 要回答的问题。批级的路由必须是一等公民。
     */
    enum class Route {
        /** 这一批走了合批（一次前向编 n 条）。 */
        BATCHED,

        /**
         * 这一批回落逐条（整批不可用）。
         *
         * ⚠ 与"这一批里有某几条失败"是**两件不同的事**：后者仍算 [BATCHED]
         * （合批跑通了，只是其中几条的文本坏了）。见 `LlmEngine.embedBatch` 返回类型的说明。
         */
        FELL_BACK,
    }

    /**
     * 一笔批级读数。[entries] 是这一批的**条数**（不是块数 —— 它们相等，
     * 但条数是调用方传进来的那个，别在这里另算）。
     */
    data class Sample(val route: Route, val entries: Int, val elapsedMs: Long)

    /**
     * 累积快照。字段刻意与 `/health` 一一对应（守卫正面钉住这点）——
     * 快照里多一个内部字段而 /health 漏报一个，等于那个读数对外不可见。
     */
    data class Snapshot(
        /** 走了合批的**批次**数。 */
        val batchedBatches: Long,
        /** 走了合批的**条数**累计（= 真正省下前向的那些条目）。 */
        val batchedEntries: Long,
        /** 回落逐条的**批次**数。 */
        val fellBackBatches: Long,
        /** 回落逐条的**条数**累计。 */
        val fellBackEntries: Long,
        /** 合批花掉的**毫秒**累计（只算合批那部分，回落走逐条路径另有计时）。 */
        val batchedMs: Long,
        /** 最近一批的**条数**（0 = 还没跑过任何一批）。 */
        val lastBatchEntries: Int,
        /** 最近一批走了哪条路；null = 还没跑过任何一批。 */
        val lastRoute: Route?,
        /** 最近一批花掉的毫秒（null = 还没跑过任何一批）。 */
        val lastBatchMs: Long?,
    ) {
        /** 合批**条数**占比（0.0~1.0）；一次都没跑过时返回 null（不是 0.0）。 */
        val batchedEntryRatio: Double?
            get() {
                val total = batchedEntries + fellBackEntries
                return if (total <= 0L) null else batchedEntries.toDouble() / total.toDouble()
            }
    }

    /**
     * 累积量。**所有读写都在同一把锁内** —— 建库在后台线程、`/health` 在
     * accept 线程上跑，两个线程同时读写这八个字段（不加锁会有撕裂读数：
     * 批次数与条数对不上，而那种数字**看着完全正常**）。
     */
    private val lock = Any()

    private var batchedBatches = 0L
    private var batchedEntries = 0L
    private var fellBackBatches = 0L
    private var fellBackEntries = 0L
    private var batchedMs = 0L
    private var lastEntries = 0
    private var lastRoute: Route? = null
    private var lastMs: Long? = null

    /**
     * 记一批。**永远不抛**：它在编码循环的检查点上，抛出去会变成"建库闪退" ——
     * 而这是一条纯观测的支路，它自己坏掉不该让建库挂掉。
     *
     * [elapsedMs] 允许为负（墙钟跳变 / 计时器精度问题的下限），负值按 0 记：
     * 一个负的耗时会让"平均毫秒"变成一个无意义的数，而它看着像个正常数。
     */
    fun record(sample: Sample) {
        try {
            val ms = if (sample.elapsedMs > 0L) sample.elapsedMs else 0L
            val n = if (sample.entries > 0) sample.entries else 0
            synchronized(lock) {
                when (sample.route) {
                    Route.BATCHED -> {
                        batchedBatches++
                        batchedEntries += n
                        batchedMs += ms
                    }
                    Route.FELL_BACK -> {
                        fellBackBatches++
                        fellBackEntries += n
                    }
                }
                lastEntries = n
                lastRoute = sample.route
                lastMs = ms
            }
        } catch (_: Throwable) {
            // 观测支路自己坏掉不许把建库带下去（见函数注释）。
        }
    }

    /** 读一份快照（一致读数，见 [lock] 的说明）。 */
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            batchedBatches = batchedBatches,
            batchedEntries = batchedEntries,
            fellBackBatches = fellBackBatches,
            fellBackEntries = fellBackEntries,
            batchedMs = batchedMs,
            lastBatchEntries = lastEntries,
            lastRoute = lastRoute,
            lastBatchMs = lastMs,
        )
    }

    /**
     * 清零（换嵌入模型 / 用户主动重置时调）。
     *
     * ⚠ 换模型**必须**清零：读数描述的是"这个模型在这台机器上的合批命中率"，
     * 而两个模型的批能力可以完全不同（BGE/CLS 与 Qwen3/LAST 那一档本就不合批）。
     * 不清零时，用户从"合批型"模型换到"回落型"模型，看到的仍是上一个模型的
     * 命中率 —— 那个数字**看着正常**，只是描述的不是眼前这个模型。
     */
    fun reset() = synchronized(lock) {
        batchedBatches = 0L; batchedEntries = 0L
        fellBackBatches = 0L; fellBackEntries = 0L
        batchedMs = 0L
        lastEntries = 0; lastRoute = null; lastMs = null
    }

    /**
     * 把两份快照**相减**，得到"从 [from] 到 [to] 之间那一段"的读数。
     *
     * ── 为什么需要这一段 ──────────────────────────────────────────────
     * [record] 记的是**进程级累计**（见对象头那句"跨多次建库累加"），而建库
     * 结束那一行说给用户听的**不是**累计 —— 是"我点的这一次建库走了哪条路"。
     * 此前两者用同一份累计量，于是那行只好把累计值写成 `本轮批大小=4：合批 20/20 条`：
     * 数字本身没错，但"本轮"两个字把它说成了本次建库的增量，而它其实是
     * 第 5 次建库之后的**累计** —— 用户去比"批4 vs 批1"时，看到的是两个几乎
     * 相等的累计值，会读成"批大小没生效"。
     *
     * ⚠ 减出来的值可以出现"条数有而批数为 0"这类组合吗？不会 —— 两次快照取的是
     * 同一把锁下的**一致读数**（见 [lock]），且累计量只增不减（除 [reset]，
     * 而 reset 只发生在换模型/用户清零，那时两端都归零）。唯一会出现的是
     * "这一段的条数是 0"（建库一条都没编、直接失败返回），那由 [describe] 说成
     * "这一段没编过"。
     *
     * ⚠ [Snapshot.lastRoute] / [Snapshot.lastBatchEntries] 这两个"最近一批"
     * 的字段**不做减法** —— 它们是瞬时量，不是累计量，相减出来的数没有意义。
     * 段内读数只取 [to] 的那份（= 这一段的最后一批）。
     */
    fun delta(from: Snapshot, to: Snapshot): Snapshot = Snapshot(
        batchedBatches = to.batchedBatches - from.batchedBatches,
        batchedEntries = to.batchedEntries - from.batchedEntries,
        fellBackBatches = to.fellBackBatches - from.fellBackBatches,
        fellBackEntries = to.fellBackEntries - from.fellBackEntries,
        batchedMs = to.batchedMs - from.batchedMs,
        lastBatchEntries = to.lastBatchEntries,
        lastRoute = to.lastRoute,
        lastBatchMs = to.lastBatchMs,
    )

    /**
     * 把快照说成**一句话**（给日志 / 建库回执用）。
     *
     * 为什么要一句话而不是让调用方自己拼：建库结束那一行是用户唯一会看的读数，
     * 它的措辞必须与 `/health` 的字段语义**一致** —— 两处各拼一遍，
     * 迟早一处说"合批 N 批"、另一处说"命中 N 次"（同一个数，两种口径）。
     *
     * [scope] 是这一句话的**口径标签**：给它"本段"（这一段建库的增量，见 [delta]）
     * 或"累计"（进程级总账，[snapshot] 的直读）。为什么这个字必须由调用方给、
     * 不许 [describe] 自己猜：同一份数字，说成"本段"还是"累计"决定了用户
     * 下一步去看什么 —— 而 [describe] 手上只有一份快照，分不清它是减出来的
     * 还是直读的。写成"本轮"那种含糊词（既像本次、又像累计）正是这一处
     * 出过的错：数字正常，标签把方向指反。
     *
     * ⚠ 空态文案也跟着 [scope] 走：累计为 0 是"本次运行还没跑过嵌入"，
     * 段为 0 是"这一段建库一条都没编过"（很可能是建库在开头就失败了）——
     * 两者该去的方向不同，不许合成一句。
     */
    fun describe(s: Snapshot, scope: String): String {
        val total = s.batchedEntries + s.fellBackEntries
        if (total <= 0L) {
            // ⚠ "为 0"有三种来源，而这句话只能**如实**说"这一段的批级读数是 0"，
            //   不去猜是哪一种：
            //     · 增量导入且前端 0 条 —— 它压根不走合批（块数 <= 批大小时
            //       一次 `embedBatch` 就是一段），这一段确实"没跑过可归因的批"；
            //     · 建库在开头就失败（旧口径只认这一种，而它**是最不可猜的一种**）；
            //     · 全部块都从沿用里来（`startFrom == texts.size`）—— 一块没编。
            //   猜错的代价是用户去找一个不存在的故障（ISSUE #228 正是如此：
            //   日志说"建库在开头就失败了"，而上面那两行 `[embedBatch] 提交`
            //   分明刚提交完 8 条 + 4 条）。
            return if (scope == kScopeThisBuild)
                "这一段的批级读数为 0（没有可归因的批：可能一块都没编，也可能建库在开头就失败了）"
            else "本次运行还没跑过嵌入"
        }
        val pct = ((s.batchedEntryRatio ?: 0.0) * 100.0).toInt()
        return "合批 ${s.batchedEntries}/${total} 条（$pct%，${s.batchedBatches} 批，" +
            "共 ${s.batchedMs} ms）；回落逐条 ${s.fellBackEntries} 条（${s.fellBackBatches} 批）"
    }

    /** [describe] 的口径标签：这一段建库的增量（与"累计"相对，见 [delta] 的说明）。 */
    const val kScopeThisBuild = "本段"

    /** [describe] 的口径标签：进程级总账（[snapshot] 的直读）。 */
    const val kScopeCumulative = "累计"
}
