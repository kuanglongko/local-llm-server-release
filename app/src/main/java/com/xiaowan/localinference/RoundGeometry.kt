package com.xiaowan.localinference

/**
 * 每一轮问答在这条 prompt 里**占哪一段**（字符 `[start, end)`）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它回答的是中间层唯一真正缺的那件事：坐标
 * ══════════════════════════════════════════════════════════════════════════
 * 客户端每轮把**整份历史**重新送一遍，中间层手里有那份原文；而服务端这一轮
 * 实际喂进 prefill 的是「预算裁过 + 知识库注入过」的另一份。于是中间层能算
 * 「这一轮我该丢几轮」，却算不出「**丢到哪一句为止**」—— 它不知道服务端上一轮
 * 保住了哪几轮、哪几轮已经不在 prompt 里了。
 *
 * 这一张表把「哪几轮在、哪几轮被丢」变成下标，中间层照着它删整条消息即可：
 * 它**不需要** tokenizer、不需要复刻模板，只需要按条/按轮重排客户端原文。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是**字符**下标而不是 token 下标
 * ══════════════════════════════════════════════════════════════════════════
 * 中间层拿的是文本（它没有 tokenizer，也不该有 —— 让它自己 tokenize 就等于让它
 * 复刻服务端模板，那份分叉本仓库已经吃过两次）。而服务端逐条 tokenize 再累加
 * 是 O(消息数) 次 tokenize，每轮多 2~5 ms，换来的精度中间层**用不上**：
 * 它的裁剪粒度是「整轮 / 整条」。
 *
 * ⚠ 那么这份下标能不能被当成 token 用？**不能**。它是**只保留下来那几条消息的正文
 *   按顺序拼起来**的偏移（渲染固定开销与生成后缀都不在内），与 `prompt_real`
 *   （native 数出来的真 token 数）是两个坐标系。要判「这一轮还剩多少」，读
 *   `local_ctx.kept` / `reuse`；这一张表只说「哪几轮、在第几个字符上」。
 */
object RoundGeometry {

    /** 一轮 = 一条 user 连同它后面紧邻的 assistant（与 `ContextBudget.groupRounds` 同一条切法）。 */
    class Round(
        /** 从 0 起的序号（**含**被丢掉的轮次，所以中间层能拿它当稳定身份）。 */
        val index: Int,
        /** 这一轮第一条消息在保留集里的下标。 */
        val first: Int,
        /** 这一轮最后一条消息的下标（`+1` = 开区间末尾）。 */
        val last: Int,
        /** 正文在保留集拼串里的起点（字符偏移）。 */
        val start: Int,
        /** 正文在这条拼串里的终点（`end - start` = 这一轮的字符数）。 */
        val end: Int,
        /** `false` = 这一轮**已经被丢掉**（它不在保留集里，坐标是推算出来的）。 */
        val kept: Boolean
    ) {
        val chars: Int get() = end - start
    }

    /**
     * 切出这条 prompt 里的每一轮。
     *
     * 判据（与预算那一侧**同一条**，不另立一套）：
     *   · 一条 user 连同其后**所有**连续 assistant 算一轮 —— 一条 user 后面跟两条
     *     assistant 是"模型重生成"，把它切成两轮会让中间层丢在半句中间；
     *   · 首条 system **不属任何一轮**（它在 pos 0，中间层不许动它）；
     *   · **开头**的孤 assistant 也不成轮（它对应的提问早就不在了）。
     *
     * @param messages 这一轮**真正会喂进 prefill 的**那一份（`Plan.messages`）——
     *   不是客户端原件：中间层要对照的是服务端实际吃了什么。
     */
    fun of(messages: List<Pair<String, String>>): List<Round> {
        val out = ArrayList<Round>()
        val head = if (messages.firstOrNull()?.first == "system") 1 else 0
        // 字符游标：正文按顺序拼起来的长度（与中间层**能自己算出来**的那个量同源）。
        var cur = messages.take(head).sumOf { it.second.length }
        var i = head
        // 开头的孤 assistant：不成轮，但它的字符照样要往前走 —— 否则后面每一轮的
        // 坐标都短一截，而"短一截"在中间层看来与"这一轮更早开始"一模一样。
        while (i < messages.size && messages[i].first == "assistant") {
            cur += messages[i].second.length
            i++
        }
        var idx = 0
        while (i < messages.size) {
            if (messages[i].first != "user") {
                // 结构上不该出现（system 只在头部），但错序的历史确实会被客户端送来。
                // 不猜它属于谁：跳过并保持游标正确，比并进上一轮安全。
                cur += messages[i].second.length
                i++
                continue
            }
            val first = i
            val start = cur
            cur += messages[i].second.length
            i++
            while (i < messages.size && messages[i].first == "assistant") {
                cur += messages[i].second.length
                i++
            }
            out.add(Round(idx, first, i, start, cur, true))
            idx++
        }
        return out
    }

    /**
     * 把「丢掉最老 `droppedRounds` 轮」这件事也标出来，给中间层一个能直接对照的窗口。
     *
     * 被丢掉的那几轮**不在 `messages` 里**，它们的下标只能报「不在了」这个事实：
     * `first = last = -1`、`kept = false`，坐标取保留集的第一轮起点之前的一段虚位。
     * 这正是中间层需要的那句话 —— 它看 `kept` 就知道该删自己手里的哪几轮，
     * 而不需要反推"服务端是按什么规则删的"。
     *
     * @param droppedRounds 这一轮被丢掉的**轮数**（`local_ctx.messages` 里那部分）
     */
    fun of(messages: List<Pair<String, String>>, droppedRounds: Int): List<Round> {
        val kept = of(messages)
        val d = droppedRounds.coerceAtLeast(0)
        if (d == 0) return kept
        val out = ArrayList<Round>(kept.size + d)
        for (k in 0 until d) out.add(Round(k, -1, -1, 0, 0, false))
        for (r in kept) out.add(Round(r.index + d, r.first, r.last, r.start, r.end, true))
        return out
    }
}
