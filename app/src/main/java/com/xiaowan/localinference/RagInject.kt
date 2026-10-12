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

    /**
     * 开关的持久化键（`SharedPreferences` 里那份）。
     *
     * 与 `ModelStore` / `SessionStore` 的键分开存：RAG 开关是**全局**状态，
     * 不属于任何单个会话（会话切换不该改变"知识库用不用"）。
     */
    const val KEY_ENABLED = "rag_enabled"
    const val KEY_CURRENT = "rag_current_kb"

    /**
     * 远端（HTTP）单独一份开关与库名，**与 App 内那两个键分开**（阶段 3）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须是两个键，而不是复用 [KEY_ENABLED] / [KEY_CURRENT]
     * ══════════════════════════════════════════════════════════════════════
     * App 内聊天的人就在设置那一屏，他知道自己配了知识库，默认**开**符合预期；
     * 而远端 HTTP 是**无状态**的，服务端不知道调用方想不想要 —— 默认注入等于
     * 用调用方没要求过的内容污染回答，而接口照 200，这与幻觉同形。
     * 两个默认值针对的是两种消费方，所以两个开关、两个默认值。
     *
     * 库名同理必须独立：`currentKb` 是**单一全局值**，在 App 里点任何一条库都会改它。
     * 若远端跟着它走，就会出现"你在 App 里切到库 B 看了两眼，此时远端某个客户端
     * 正在轮询 —— 它会**静默地**换成库 B"。这类故障排查成本极高（回答变差，
     * 却没有任何现象指向"库换了"）。所以远端单独一个库名，切换动作必须是
     * **显式**发生在"远端库名"那一格上。
     *
     * 为什么远端库名不"跟随当前库"：跟随看着方便，实际把那类静默切换又请回来了
     * （"跟随"= 用一个会被别处改动的值当输入）。默认值就一个：空 = 没配 = 不注入。
     */
    const val KEY_REMOTE_ENABLED = "rag_remote_enabled"
    const val KEY_REMOTE_KB = "rag_remote_kb"

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
            if (p.index < 0 || p.index >= rows.size) continue
            val row = rows[p.index]
            if (row.isEmpty()) continue
            if (sb.isEmpty()) sb.append(MARK).append('\n')
            sb.append(row)
            sb.append('\n')
        }
        return sb.toString()
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
     * 远端请求要不要走检索 —— **与 App 内那条路径共用同一个 [shouldSearch]**，
     * 只是"开关"与"库名"取的是远端那一对键。
     *
     * 为什么不在这里再写一遍三条件：那正是漂移的起点（多注入一个字节，零症状）。
     * 本函数只做一件事 —— 把"远端该读哪两个值"定下来，判定本身交给 [shouldSearch]。
     *
     * ⚠ 与 `/v1/embeddings` **没有任何关系**（用户口径 4：完全解耦）。embeddings
     * 是无状态逐条算向量，它不读这个开关、也不该因为这个开关关着就被拒绝。
     * 两者的唯一共同点是"都用得到嵌入模型"，而那由各自的入口独立判断。
     */
    fun shouldSearchRemote(ctx: android.content.Context, query: String): Boolean =
        shouldSearch(remoteEnabled(ctx), remoteKb(ctx), query)

    // ── 持久化（App 内的开关与当前库名）────────────────────────────────────
    //
    // 为什么开关读**默认 true**：App 内聊天的人就在设置那一屏，他知道自己配了知识库；
    // 默认不勾的后果是"配好了但从来没用上"，而它没有任何提示。远端 HTTP 相反，
    // 默认不注入（服务端不知道调用方想不想要）—— 两个默认值针对的是两种消费方。
    private const val PREFS = "rag_store"

    private fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    /** 检索开关是否开启；**键不存在时默认 true**。 */
    fun enabled(ctx: android.content.Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: android.content.Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 当前选中的知识库名；""= 没选（等价于"没配知识库"）。 */
    fun currentKb(ctx: android.content.Context): String =
        prefs(ctx).getString(KEY_CURRENT, "").orEmpty()

    fun setCurrentKb(ctx: android.content.Context, name: String) {
        prefs(ctx).edit().putString(KEY_CURRENT, name).apply()
    }

    /**
     * 远端检索开关是否开启；**键不存在时默认 false**（远端的默认是"关"）。
     *
     * 这个默认值是与 App 内那个（默认 true）**刻意相反**的，理由见
     * [KEY_REMOTE_ENABLED]：远端无状态，服务端猜不出调用方要不要。
     * 读默认值时写成 `getBoolean(key, true)` 会把"没配过"读成"开着" ——
     * 于是升级到本版本后，所有没动过设置的人**突然开始**被注入知识库内容，
     * 而他们从没要求过。判据由 `run_rag_remote_guard.sh` 正面钉住。
     */
    fun remoteEnabled(ctx: android.content.Context): Boolean =
        prefs(ctx).getBoolean(KEY_REMOTE_ENABLED, false)

    fun setRemoteEnabled(ctx: android.content.Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_REMOTE_ENABLED, on).apply()
    }

    /** 远端使用的知识库名（**独立于** [currentKb]）；""= 没配，等价于"远端不注入"。 */
    fun remoteKb(ctx: android.content.Context): String =
        prefs(ctx).getString(KEY_REMOTE_KB, "").orEmpty()

    fun setRemoteKb(ctx: android.content.Context, name: String) {
        prefs(ctx).edit().putString(KEY_REMOTE_KB, name).apply()
    }

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
