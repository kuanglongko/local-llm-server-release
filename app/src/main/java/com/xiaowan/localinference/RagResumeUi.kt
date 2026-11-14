package com.xiaowan.localinference

/**
 * 「这个知识库现在是什么状态」那一行的**纯逻辑**（零依赖：不 import 任何东西）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这一层
 * ══════════════════════════════════════════════════════════════════════════
 * 知识库列表里每个库后面挂着一句状态（「待重建」/「正文已改」/「未落盘」…）。
 * 用户看它是为了回答**唯一的那个问题**：*我点「建索引」这一下要等多久？*
 *
 * 本轮加了"**可续**"这一档之后，这个问题第一次有了两种答案：
 *   · 可续   -> 只补缺口（前面已经算过的块原样沿用）；
 *   · 要重建 -> 整份重来（几十分钟，对大库而言）。
 * 两者在屏幕上**必须长得不一样** —— 这正是用户最初要的那件事
 *（"中途断了，下次能接上……感觉太浪费"）。把两者合成一句「待重建」之后，
 * 他无法区分"点一下补 20 块"与"点一下从头再跑两小时"。
 *
 * ⚠ 这一层**只拼字符串、不碰文件/时间/Android**：判据（[RagIndexStore.Info] 的
 *   那几个布尔）由调用方算好传进来。理由与本仓库别的纯逻辑层一致 ——
 *   它要能在**不带 Android 栈**的宿主单测里编过，而那是我们唯一的证据；
 *   也因为它把"什么状态说什么话"收在**一处**，列表与详情页不会各说一句。
 *
 * ⚠ 绝对**不报"预计还要多久"**：剩余要靠"已耗时 / 已完成 × 剩余块数"推，
 *   而块与块之间的耗时能差一个数量级（一句短笔记 vs 一块满长的整篇）——
 *   推出来的剩余时间会**看起来精确**而错得没边（与 [RagProgress] 拒绝
 *   报剩余时间是同一条理由：量不准却像量准了的假读数，比没有读数更坏）。
 */
object RagResumeUi {

    /**
     * 一个库的状态后缀（拼在 `名称  12 条 · dim=1024` 后面）。
     *
     * 五态**显式分开**，判据是调用方算好的那几个布尔（不在这里重推）：
     *   · [RagIndexStore.Info.isPending]  -> 未落盘（磁盘上还没有它）；
     *   · 没有索引                        -> 待重建（导入来的 / 从未建过）；
     *   · **有索引且可续**                -> 可续（半份，点一下接着编）；
     *   · 有索引、不可续、正文对不上      -> 正文已改，需重建；
     *   · 有索引、不可续、正文对得上      -> 什么都不说（索引是齐的、能检索）。
     *
     * ⚠ 顺序是判据的一部分：`可续` 必须排在 `正文已改` **之前**校验 ——
     *   半份索引的正文 hash 与指纹是**一致**的（[RagIndexStore.resumableCount]
     *   已经把这一点算作它成立的前提），所以它不会落到"正文已改"那一支；
     *   但把两支写反（先问 inSync）会让"可续"永远显示成"正文已改"，
     *   而那句话会指引用户去查他**没有做过**的正文修改。
     *
     * @param isPending  [RagIndexStore.Info.isPending]
     * @param hasIndex   [RagIndexStore.Info.hasIndex]
     * @param inSync     [RagIndexStore.Info.inSync]
     * @param resumable  [RagIndexStore.Info.resumable]
     * @param resumeCount 已经编到第几块（仅 [resumable] 为 true 时有意义）
     * @param rowsTotal  正文总块数/条数（仅 [resumable] 为 true 时有意义；
     *                   用于把"接着编多少"说清，0 时退化成不报分母）
     */
    fun state(isPending: Boolean, hasIndex: Boolean, inSync: Boolean,
              resumable: Boolean, resumeCount: Int, rowsTotal: Int): String = when {
        isPending -> "  · 未落盘（粘贴/导入内容后自动保存，离开即丢弃）"
        !hasIndex -> "  ⚠ 待重建（点「建索引/重建索引」）"
        resumable -> {
            // 只说"能续"与"续到哪"，不说"还要多久"（见文件头）。
            val frac = if (rowsTotal > resumeCount && resumeCount > 0) {
                "（已编 $resumeCount/$rowsTotal，其余接着编）"
            } else {
                "（上次没编完，接着编）"
            }
            "  ↻ 可续$frac"
        }
        !inSync -> "  ⚠ 正文已改，需重建"
        else -> ""
    }

    /**
     * 「点建索引」这一步在本库上会是**哪种口径**——给确认框/提示用的一句话。
     * 返回 null = 没什么要特别说的（齐整索引 / 未落盘都走原有文案）。
     *
     * 与 [state] 同源（同样的四个布尔），只是措辞面向"点下去之后"：
     * 用户真正怕的是"点一下又白等两小时"，而这句直接回答它。
     */
    fun buildHint(hasIndex: Boolean, inSync: Boolean, resumable: Boolean,
                  resumeCount: Int, rowsTotal: Int): String? = when {
        resumable && rowsTotal > resumeCount && resumeCount > 0 ->
            "上次建到 $resumeCount/$rowsTotal 块，这次从第 ${resumeCount + 1} 块接着编（前 $resumeCount 块沿用）"
        resumable -> "上次没建完，这次接着编"
        hasIndex && !inSync -> "索引与当前配置/正文不一致，这次会**整份重编**"
        else -> null
    }
}
