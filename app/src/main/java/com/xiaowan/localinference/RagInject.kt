package com.xiaowan.localinference

/**
 * RAG 检索结果的**注入纯逻辑**（RAG 阶段 2）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独抽一个文件
 * ══════════════════════════════════════════════════════════════════════════
 * "检索到几条 -> 拼成什么文本 -> 塞进 prompt 的哪一段"这三步的错法**全是静默的**：
 *
 *   · 开关关着却仍注入了一个字节 -> 用户没要求却被污染上下文，接口 200，看不出是谁干的；
 *   · 低分条目硬塞进去 -> 用一个无关段落污染回答，比"没命中"更坏（后者只是没帮助）；
 *   · 注入落在每轮都变的那个位置（system 段）-> KV 前缀复用的分叉点被提前到 pos 0，
 *     公共前缀只剩几个 token，`reuse` 恒为 0；表现是"开了知识库就一点缓存都用不上"，
 *     而关掉立刻恢复 —— 看不出是排布问题还是缓存坏了；
 *   · 库名对不上却静默返回空 -> "配了知识库但回答里永远不带内容"，无法归因。
 *
 * 所以判定抽成纯函数（与 `rag_index.h` 的 `select_injections` / `render_injection`
 * 同一套路），宿主侧用真数据钉住，两边**各自实现一遍、喂同一份输入逐字对齐**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 C++ 版的对应关系（守卫里逐字段比）
 * ══════════════════════════════════════════════════════════════════════════
 * | 这里                      | `rag_index.h`            |
 * |---------------------------|--------------------------|
 * | [select] + [Inject.Pick]  | `select_injections`      |
 * | [render]                  | `render_injection`       |
 * | [TOGGLE] / [DEFAULT_TOP_K]| （Kotlin 侧特有：开关与默认条数） |
 *
 * 前缀 `[知识库]` 是**可判别**的锚点：回答里带没带知识库内容一眼可见，
 * 守卫也据此断言"开关关闭时一个字节都不注入"。
 * 每条**只拼正文**、不带来源尾巴 —— 尾巴待在 prompt 里就会被模型抄进回答（见 [render]）。
 */
object RagInject {

    /** 注入片段的行首标记。刻意固定且全局唯一，用于"到底注入了没有"的判定。 */
    const val MARK = "[知识库]"

    /** 默认注入条数上限：拼多了会挤掉有限的 ctx，还会稀释真正相关的那条。 */
    const val DEFAULT_TOP_K = 3

    /** 默认相关度阈值：低于它的一条都不要（没有命中时正确行为是"不注入"）。 */
    const val DEFAULT_MIN_SCORE = 0.35f

    /*
     * 持久化（开关 / 当前库名 / 远端那一对）不在本对象里 —— 见 [RagPrefs]。
     *
     * ⚠ 这里曾经有 `KEY_ENABLED` / `KEY_CURRENT` / `KEY_REMOTE_ENABLED` /
     *   `KEY_REMOTE_KB` 与四组 get/set，以及 `private fun prefs(ctx)`。它们让
     *   本对象在"纯逻辑"之外多了一个 Android 依赖，而"离线单测的边界"全靠
     *   人记着"别在这里加 prefs"。搬出去之后，本文件**一个 Context 都不碰**。
     */

    /** 一条待注入的命中：在索引里的行号 + 分数。字段与 C++ 的 `Pick` 一一对应。 */
    data class Pick(val index: Int, val score: Float)

    /**
     * 从**已按分数降序**的命中里选出要注入的那些。
     *
     * 三条规则（与 `rag_index.h::select_injections` 逐条对齐）：
     *   · `maxItems` 截断；
     *   · `minScore` 以下的一条都不要 —— **不是"取分数最高的几条"**，
     *     而是"够格的才有资格"，全都不够格时返回空（+ 调用方不注入）；
     *   · `index < 0` 的丢弃（无效行号）。
     *
     * `minScore <= 0` 退化为"不按分数过滤"（保留一个显式关阈值用于对比测试的口子）。
     * NaN 的分数一律丢掉（`!(score >= minScore)` 对 NaN 为真）。
     */
    fun select(sorted: List<Pick>, maxItems: Int, minScore: Float): List<Pick> {
        if (maxItems <= 0) return emptyList()
        val out = ArrayList<Pick>(maxItems)
        for (p in sorted) {
            if (out.size >= maxItems) break
            if (minScore > 0f && !(p.score >= minScore)) continue
            if (p.index < 0) continue
            out.add(p)
        }
        return out
    }

    /**
     * 把选中的条目拼成注入片段。格式与 `rag_index.h::render_injection` **逐字节一致**：
     *
     * ```
     * [知识库]
     * <第一条正文>
     * <第二条正文>
     * ```
     *
     * 为什么不用自由格式：**可判别**才能让"到底注入了没有"在回答里看得出来。
     *
     * 为什么每条**只拼正文、不带 `（来源: <标签>）`**：注入块整段是**普通上下文文本**，
     * prompt 里有什么模型就可能抄什么 —— 行尾那段来源尾巴被抄进回答，就成了用户看到的
     * 「（来源: 追加-xx）」。代码里没有任何一句 prompt 要求标注来源，所以它带不带全是
     * 模型自己决定；**给它一个没用的尾巴，就是在给模型一个可抄的东西**。本条去掉尾巴，
     * 模型就没得抄。来源本身没丢：仍落盘、在「维护知识库」列表里可见，也仍在 `local_rag`
     * 与 `/health` 的 `rag_*` 里，只是不再进 prompt。
     *
     * `picks` 里的 `index` 是**索引里的行号**，`rows` 必须按行号对齐（下标即行号）；
     * 空正文 / 越界行号跳过（**不崩**）；一条都不剩时返回**空串**（而不是只有 `[知识库]`
     * 一个孤零零的表头）—— 后者会让守卫的"非空即已注入"判据恒真。
     */
    fun render(picks: List<Pick>, rows: List<String>): String {
        val sb = StringBuilder()
        for (p in picks) {
            if (sb.isEmpty()) sb.append(MARK).append('\n')
            if (p.index < 0 || p.index >= rows.size) continue
            val row = rows[p.index]
            if (row.isEmpty()) continue
            sb.append(row)
            sb.append('\n')
        }
        return sb.toString()
    }

    /** [renderStable] 的两种产出：拼进 prompt 的那一段 + 这一轮实际写进去多少字（供读数与日志）。 */
    data class Stable(val block: String, val chars: Int)

    /**
     * 注入块的**定长**版本（ISSUE #330 真机复盘）：同一条命中、不同轮次，
     * 拼进 prompt 的那一段**逐字节相同** —— 变长的那一侧靠"截到预算"和"补空"抹平。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么这是复用能不能成立的关键（不是锦上添花）
     * ══════════════════════════════════════════════════════════════════════
     * KV 前缀复用是**逐 token 比前缀**：注入块拼在"本轮提问"之前，所以块里
     * 任何一个字节变了，公共前缀就**断在块的头一枚**上，后面整段历史全部重算。
     *
     * 而检索结果天生是变长的：命中的是第 778 条还是第 597 条，正文长短差出几十
     * 到几百 token。真机上「开知识库 + 开向量模型」时复用很好、"不开向量模型"时
     * 波动，差别就在这里 —— 后者每轮命中的条数与正文长度都在跳。
     *
     * 所以把块做成"定长"：每一条都**恰好** `perItemChars` 个字（按 UTF-16 码元截、
     * 往前退到完整码点，不留半截代理对），不够的用空格补。于是：
     *   · 同一条命中 -> 完全相同的字节 -> 前缀继续接得上；
     *   · 换了命中 -> 从块的头一枚就分叉（与改前一样，但这是**信息真的变了**）。
     *
     * ⚠ 定长 = **上界**，不是"一定要这么多字"：短正文后面会跟一串空格。那些空格
     *   进入 prompt 是真实的 token 成本，所以 `perItemChars` 由调用方按容量给，
     *   不给大的默认值。`chars == 0` 时退回 [readable] 的"原样 + 不垫不截"形态。
     *
     * ⚠ 表头（`[知识库]`）与每条的换行都**只依赖条数**，不依赖正文 —— 那条
     *   "命中 1 条与命中 3 条"的分叉点会落在第二、三条的头一枚上，这也是信息真的变了。
     *
     * @param picks 已按分数降序、已过滤的命中（[select] 的产出）
     * @param rows  **按行号对齐**的正文（下标即行号，见 [render]）
     * @param perItemChars 每条**最多**占多少个 UTF-16 码元（0 = 不截不补，按原样拼）
     */
    fun renderStable(picks: List<Pick>, rows: List<String>, perItemChars: Int): Stable {
        val sb = StringBuilder()
        var chars = 0
        for (p in picks) {
            if (p.index < 0 || p.index >= rows.size) continue
            val row = rows[p.index]
            if (row.isEmpty()) continue
            if (sb.isEmpty()) sb.append(MARK).append('\n')
            val piece = if (perItemChars <= 0) row else fitToWidth(row, perItemChars)
            sb.append(piece)
            sb.append('\n')
            chars += piece.length + 1
        }
        return Stable(sb.toString(), chars)
    }

    /**
     * 把一行正文压成**恰好** `width` 个 UTF-16 码元：超了按码点边界截，短了补空格。
     *
     * 截断只能落在码点边界上（不留半截代理对）：半个代理对过 JNI / 进 tokenizer
     * 是一条**非法序列**，而它的症状是"偶发一个字变成替换符"，与复用毫无关系的
     * 样子 —— 这类问题不该由一个"为了省 token"的函数顺手引入。
     */
    fun fitToWidth(row: String, width: Int): String {
        if (width <= 0) return ""
        if (row.length == width) return row
        if (row.length < width) return row + " ".repeat(width - row.length)
        // 超长：从 width 往前退到一个**完整码点**的起点。
        var end = width
        if (end > 0 && Character.isHighSurrogate(row[end - 1])) end--
        if (end <= 0) end = width.coerceAtMost(row.length)
        val head = row.substring(0, end)
        // 退了一个码元时用空格补满 —— 定长是硬约束，短一个码元就白做（后面照样断）。
        return if (head.length == width) head else head + " ".repeat(width - head.length)
    }

    /**
     * 把注入片段拼到**最后一条 user 消息的正文之前**（块在前、提问在后）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不放 system 段（这是本函数存在的唯一理由）
     * ══════════════════════════════════════════════════════════════════════
     * system 段在 prompt 的**最前面（pos 0）**，而注入块每轮都不同（检索结果每轮重算）。
     * 于是分叉点落在 pos 0 附近，KV 前缀复用的公共前缀只剩下 system 开头那几个 token ——
     * `plan_reuse` 的逐 token 最长公共前缀算出来就是这么点，`reuse` 恒为 0。
     * 用户看到的是"开着知识库，KV 复用基本没有"（关掉知识库立刻恢复）。
     *
     * 挪到最后一条 user 之前之后，分叉点落在**本轮提问处**：
     * ```
     * [system][历史各轮…][注入块  本轮提问]      ← 本轮
     * [system][历史各轮…][上一轮回答][注入块  新提问]  ← 下一轮
     *                  ↑ 公共前缀到"上一轮回答"为止
     * ```
     * 复用的前缀 = 之前**每一轮**的完整对话，与关掉知识库时同量级。
     *
     * 为什么是**拼进 user 正文**而不是**另插一条 user 消息**：后者会让模板看到
     * 两条连续 user，严格交替角色的模板（本仓库支持的模型里就有）会串味或报错。
     * 拼进正文则**消息角色序列一个字节都没变**，只在最后一格的前面加了内容 ——
     * 同样落在"本轮提问处"这个分叉点上，复用量一致。
     *
     * `sys` 这一段（用户的设定）**一个字节都不动**：它仍是 prompt 最稳定的部分，
     * 也正是要被复用的那一段。
     *
     * 三条硬契约（与 [enabled] 的"关着就零注入"同源）：
     *   · `block` 为空 -> 原样返回（空注入 = 一个字节都不动）；
     *   · 没有 `user` 消息（比如只有 system 的首次调用）-> 原样返回，
     *     宁可这次不注入，也不凭空造一条消息出来；
     *   · 拼的是**最后一条** user：它就是本轮提问，注入的意义跟着它。
     */
    fun attachBeforeLastUser(
        msgs: List<Pair<String, String>>, block: String
    ): List<Pair<String, String>> {
        if (block.isEmpty()) return msgs
        val at = msgs.indexOfLast { it.first == "user" }
        if (at < 0) return msgs
        val body = msgs[at].second
        val merged = if (body.isEmpty()) block.trimEnd('\n')
                     else block.trimEnd('\n') + "\n\n" + body
        return msgs.toMutableList().also { it[at] = "user" to merged }
    }

    /**
     * 决定要不要走检索。**这是"开关关着时一个字节都不注入"的唯一判据点。**
     *
     * 三个条件缺一不可：
     *   · `enabled` —— 用户在 App 里勾了「检索」；
     *   · `kbName` 非空 —— 有选中的知识库（没选 = 没配，直接不检索）；
     *   · `query` 非空 —— 没有查询词就没有可检索的东西。
     *
     * 为什么单独抽出来判、而不是让调用方写三个 if：这三个条件在 App 内聊天与
     * （阶段 3 的）远端路径上**都必须一致**。各写一遍就会漂移，而漂移的方向
     * 恰好是"多注入一个字节"，没有任何现象指向它。
     */
    fun shouldSearch(enabled: Boolean, kbName: String?, query: String): Boolean =
        enabled && !kbName.isNullOrBlank() && query.isNotBlank()

    /**
     * 从已有 system 段里剥掉上一次注入的片段（重新生成时用）。
     *
     * ⚠ 自第 4 步起注入**不再落在 system 段**（改到 [attachBeforeLastUser]），
     * 所以这个"剥 system 段末尾"的助手已经不在产品路径上 —— 保留它是因为用户仍可能
     * 手动把带 `[知识库]` 的内容粘进 system 输入框，那种情形下它仍然是正确的剥离方式。
     *
     * 为什么需要它：App 的历史里存的是**用户自己的 system**（`SessionStore`），
     * 注入只发生在"拼给模型的那一份"上，不落盘。
     * 所以正常路径下不会有残留；但当 system 段本身就是被注入过的文本
     * （比如用户手动把带 `[知识库]` 的内容粘进 system 输入框）时，
     * 再次注入会把它当"用户设定"保留下来 -> 与本次检索结果**两份并列**。
     * 剥掉可以避免"知识库内容被当成用户设定"这种很难归因的重复。
     *
     * 只剥**末尾**的那一段（`[知识库]` 到结尾）：中间出现的 `[知识库]`
     * 视为用户正文的一部分，不动。
     *
     * **循环剥**而不是只剥一次：连着注入两轮（`attach` 把第二份接在第一份之后）
     * 时，末尾只剩第二份，剥一次会把**第一份**留在那儿 —— 于是"用户设定"里
     * 混着上一轮的知识库内容，再次注入就变成两份并列（单测里真抓到这一条）。
     */
    fun stripPrevious(sys: String): String {
        var s = sys
        while (true) {
            val i = s.lastIndexOf("\n$MARK")
            val next = when {
                i >= 0 -> s.substring(0, i)
                s.startsWith(MARK) -> ""
                else -> return s
            }
            s = next.trimEnd('\n')
        }
    }
}
