package com.xiaowan.localinference

/**
 * `local_rounds` —— 轮次坐标表（中间层的输入）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它与 `local_ctx` 的分工
 * ══════════════════════════════════════════════════════════════════════════
 * `local_ctx.messages` 只说「我丢掉了几条」。中间层真正要做的是「按服务端已有的
 * 那一段，重排客户端下一轮送来的全量原文」—— 它需要的是**哪几轮还在、在哪一段**。
 * 一个条数回答不了这件事：丢 2 条可能是"丢了一轮（user+assistant）"，也可能是
 * "丢了另一轮的两条 assistant"，两者在中间层看来完全不同。
 *
 * 所以这张表给每一轮一个 `kept` 和一对字符坐标，而不是给一个总数。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 三条纪律（与 `local_ctx` / `reuseGeomField` 同源）
 * ══════════════════════════════════════════════════════════════════════════
 *   · **不出现 = 兼容**：这一轮什么都没动、也没裁时，一个字节都不多；
 *   · **成组出现**：坐标是成套的，只给一半就是逼读的人去猜另一半；
 *   · **不参与判据**：它是读数，不是开关。写错的代价是"中间层少丢一轮"，
 *     而不是"服务端算错容量"。
 */
object RoundGeometryField {

    /**
     * 拼 `local_rounds` 那一格（含前导逗号；不需要时报空串）。
     *
     * @param messages 这一轮**真正会喂进 prefill 的**那一份（`Plan.messages`）。
     * @param droppedRounds 被丢掉的轮数（0 = 没丢）。
     */
    fun of(messages: List<Pair<String, String>>, droppedRounds: Int): String {
        // 一轮都没丢 = 中间层没有任何可对照的东西。此时报全表只会让每条响应多出
        // 几百字节，而它说的那件事（"全在"）中间层自己也知道（它刚把全量发给服务端）。
        if (droppedRounds <= 0) return ""
        val rounds = RoundGeometry.of(messages, droppedRounds)
        if (rounds.isEmpty()) return ""
        val sb = StringBuilder(rounds.size * 24)
        sb.append(""","rounds":[""")
        for ((n, r) in rounds.withIndex()) {
            if (n > 0) sb.append(',')
            // 五个数一格不少：中间层判"删哪几轮"只看 `kept`，判"删到哪一句"看 `a`/`b`。
            sb.append("""{"i":""").append(r.index)
                .append(""","a":""").append(r.start)
                .append(""","b":""").append(r.end)
                .append(""","m":""").append(if (r.kept) r.first else -1)
                .append(""","kept":""").append(if (r.kept) 1 else 0)
                .append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * 从起喂点起到最后还有几轮（`local_ctx.prompt_prefix.tail_rounds`）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它替中间层堵住的那个洞
     * ══════════════════════════════════════════════════════════════════════════
     * 中间层按 `prompt_prefix.chars_start` 在**自己的 messages** 上找一个消息边界，
     * 找到就把前面的全删。而"对齐"这一步天生只能吸附到**最近**的那条边界 —— 差几条
     * 字符的换算误差就够让它少退一轮（"服务端其实从第 9 条起喂，它删到第 10 条"）。
     *
     * 少退一轮 = 服务端下一轮再从最老开始丢一次 = KV 复用仍然接不上；而这一档在
     * `/health` 上是 `relay_forwarded > 0`，看起来"在干活"，实际一枚都没省下。
     *
     * `tail_rounds` 让中间层能**沿轮边界核一遍**：起喂点后面只剩 N 轮时，它删完也该
     * 正好剩 N 轮；剩多了就是退得不够，再退一轮（见 `RelayTrim.byPrefix`）。
     *
     * 只在**这一轮真丢过轮**时才报（没丢时起点就是 0，中间层不需要坐标）——
     * 与 [of] 同一条纪律：不出现 = 与引入本特性之前逐字节相同。
     *
     * @return 轮数；`<= 0` = 报不出来 / 不需要报
     */
    fun tailRoundsOf(messages: List<Pair<String, String>>, droppedRounds: Int): Int {
        if (droppedRounds <= 0) return -1
        return RoundGeometry.of(messages, droppedRounds).count { it.kept }
    }
}
