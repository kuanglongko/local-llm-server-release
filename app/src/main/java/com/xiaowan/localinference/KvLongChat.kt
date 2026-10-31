package com.xiaowan.localinference

/**
 * 长对话**越过容量之后**的取舍判据（纯逻辑）—— `app/src/main/cpp/kv_longchat.h` 的 Kotlin 镜像。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么两边各写一份，而不是只写在 native
 * ══════════════════════════════════════════════════════════════════════════
 * 客户端与 native 各有一把尺子：客户端按**估算** token 裁消息（[ContextBudget.plan]），
 * native 按**真** token 再裁一次（`ctxbudget::plan` + `kvopen`）。两份判据都在，
 * 但它们必须回答**同一个问题**——"这一轮该让掉多少、起点从哪开始"：
 *
 *   · 客户端先丢**整条消息**（保住角色序列），于是 native 真正要动的量通常很小；
 *   · native 那一份是**兜底**（客户端估低了、或裸补全没走预算那条路）。
 *
 * 两边口径不一致的症状是"客户端说装得下、服务端仍从头部砍一刀"，而两边都不报错。
 * 所以数（[GEN_FLOOR] / 让位顺序）与算法（[planKeep] / [startFor] / [canAlign]）
 * **逐条对齐**，由 `tools/run_kv_longchat_guard.sh` 拿同一组用例喂两边比结果。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 真机读数（ISSUE #330 `1.1.txt`，每格 4096 / max_tokens 1024 / 每轮历史涨约 300）
 * ══════════════════════════════════════════════════════════════════════════
 * ```
 *  轮  prompt  keep  丢头   起点  上轮起点  LCP   gen
 *   9    3300  3300     0      0        0   3000   794
 *  10    3600  3600     0      0        0   3300   494
 *  11    3900  3900     0      0        0   3600   194   ← 生成上限被吃到 194
 *  12    4200  4092   108    108        0      0     2   ← 一过线：复用归零、只剩 2 tok
 *  13    4500  4092   408    408      108      0     2
 *  14    4800  4092   708    708      408      0     2   ← 起点每轮后移，不会自己恢复
 * ```
 * 三件事由这一张表一起解释：
 *   · 「生成过长就开始重算」= 起点 108 → 408 → 708 一路后移，上一轮前缀被永久丢掉一段；
 *   · 「一超过上下文就胡说」= 同几轮 gen 掉到 2，客户端把那条空回复落盘，
 *     下一轮历史里夹一条空 `assistant`，角色序列与训练形状不再同形；
 *   · 「之前也断，只是对话短没触发」= 第 9~11 轮 gen 已经在被吃（还没丢头，所以看着正常）。
 *
 * ⚠ 上面那张表是**修复前**的读数，留在这里当判据的来历。修复之后（ISSUE #330 第 1 块）
 *   同一张表里第 12 轮起的起点是 **恒定** 的：只要上一轮起点还留在本轮内容里、
 *   且留下它不越界，就钉在它上面 —— "已进 KV 的前缀只增不减"是默认项，不是优化项。
 */
object KvLongChat {

    /**
     * 生成上限的**让位地板**：越过容量线时最多把 `max_tokens` 让到这里。
     *
     * 与 `kvlongchat::kGenFloor` **同值同义**（64）。它比 [ContextBudget.MIN_GEN_TOKENS]
     * 那个 2 大得多 —— 2 是"最后一枚留给采样"的机器下限，而 64 是"这一轮至少有
     * 一两句能读的话"的产品下限。落在两者之间的一轮 = 空回答被落盘 = 下一轮胡说。
     */
    const val GEN_FLOOR = 64

    /** 对齐上一轮时最多从**尾部**让掉多少 token（与 `kvlongchat::kAlignMaxTailDrop` 同值）。 */
    const val ALIGN_MAX_TAIL_DROP = 8192

    /**
     * 这一轮该保留多少 prompt token（`kvlongchat::planKeep` 的镜像）。
     *
     * @param prompt     整段 prompt 的 token 数（客户端是**估算**，native 是真值）
     * @param maxGen     调用方要的生成长度
     * @param ctx        这一格的 n_ctx
     * @param wantStart  想对齐的起点（上一轮起点；<=0 = 不对齐）
     * @param watermarkPct 这一轮的水位（%；`<= 0` = 默认档）。归一走 [ContextWatermarkPct.normalize]
     * @param proactive 主动治理开关（[ContextBudget.plan] 的同名参数）。**开**时判据是
     *   水位线、**关**时是整格 `ctx - 1`（默认关，与客户端那一路同一个缺省）
     * @return 保留的 prompt 长度（0 = 连 1 枚都留不出）
     *
     * ⚠ 「装得下」的线**只有一条**，且必须是调用方那一轮用的那条：开时是水位线、
     *   关时是整格。两边各判一次、各用一条线时，一边按水位丢、另一边按整格判，
     *   等于这条路把另一条路的结论又砍一刀，而**两边都报自己没错** —— 症状与
     *   native 那份完全同形（同一会话里两把尺子，谁也看不出哪把在起作用）。
     */
    fun planKeep(
        prompt: Int,
        maxGen: Int,
        ctx: Int,
        wantStart: Int,
        watermarkPct: Int = ContextBudget.DEFAULT_WATERMARK_PCT,
        proactive: Boolean = false
    ): Int {
        if (prompt <= 0 || ctx <= 0) return if (prompt > 0) prompt else 0
        val room = ctx - 1
        if (room <= 0) return 0
        var gen = Math.min(Math.max(maxGen, 0), room)
        // 水位只收紧、不放大：归一后落在 50..95，乘出来必然 < ctx；再与 `room` 取最小。
        val wm = ContextWatermarkPct.normalize(watermarkPct)
        val availWm = Math.min((ctx.toLong() * wm / 100L).toInt(), room)
        // 判据线：主动治理**开着**时是水位线（客户端那一路的 `availAt`），
        // **关着**时是整格能装多少（`availHard` = `posCap - MIN_GEN_TOKENS`，这里
        // 只到 `room` 一档，因为 native 的底线是 `ctx - 1`）。
        //
        // ⚠ 两处必须是**同一个开关**（ISSUE #488 之后）：客户端默认不按水位主动丢
        //   （`CtxProactivePref` 默认关），而这里以前**无条件**按水位算 —— 于是
        //   客户端一条不丢、native 按 3481 那条线照丢，用户看到的仍是"一开始聊就
        //   连续触发水位线，回答只剩几个字"。同一份设置在同一轮里分出两条判据，
        //   与 ISSUE #452 的「两把尺子」是同一个病。
        // ⚠ `maxGen` 那一侧不动：它只是上限，让多少都不影响 KV 前缀的连续性。
        val avail = if (proactive) availWm else room
        if (prompt + gen <= avail) return prompt
        val floorGen = Math.max(2, Math.min(GEN_FLOOR, room - 1))
        if (gen > floorGen) gen = floorGen
        if (prompt + gen <= avail) return prompt
        val over = prompt + gen - avail
        val want = if (wantStart > 0) prompt - wantStart else 0
        if (want > 0 && want + gen <= avail && (prompt - wantStart) <= ALIGN_MAX_TAIL_DROP) return want
        return (prompt - over).coerceAtLeast(0)
    }

    /**
     * 这一轮从第几枚 token 开始喂（`kvlongchat::startFor` 的镜像）。
     *
     * 与 [planKeep] 是**同一个决策的两面**（起点 = prompt - keep）：分成两处算，
     * 必然出现"预算说保留 3000、实际从 708 开始喂"这种自相矛盾。
     */
    fun startFor(prompt: Int, keep: Int, prevStart: Int, canAlign: Boolean): Int {
        if (prompt <= 0) return 0
        if (keep <= 0) return 0
        if (keep >= prompt) return 0
        if (canAlign && prevStart in 1 until prompt) return prevStart
        return prompt - keep
    }

    /** 上一轮起点之前那一段在本轮里是否逐 token 相同（`lcp >= 起点`）—— `kvlongchat::canAlign`。 */
    fun canAlign(prevStart: Int, lcp: Int): Boolean = prevStart > 0 && lcp >= prevStart

    /**
     * 越过容量线时，客户端该把生成上限报成多少。
     *
     * 与 [planKeep] 同一套：`min(want, 剩余空间)`，且**优先让 prompt 不让回答** ——
     * 客户端是走"丢整条消息"那条路的（保住角色序列），所以这里只算剩余空间，
     * 不再额外压到 2（那会让模型吐一两个字，客户端把空回复落盘）。
     */
    fun genFor(want: Int, promptTokens: Int, ctx: Int): Int {
        if (ctx <= 0) return if (want > 0) want else 0
        val room = ctx - 1 - Math.max(promptTokens, 0)
        return Math.min(Math.max(want, 0), room.coerceAtLeast(0))
    }

    /** 一句话（日志共用，措辞与 native `describeStart` 同形）。 */
    fun describeStart(prompt: Int, keep: Int, prevStart: Int, start: Int): String = when {
        start <= 0 -> "起点未动（$prompt tok 全进）"
        start == prevStart && prevStart > 0 ->
            "起点与上一轮对齐（$start tok）：从尾部让掉 ${prompt - start} tok，前 $start 枚仍可复用"
        else -> "起点后移到 $start tok（保留最近 $keep tok）—— 上一轮前缀接不上"
    }
}
