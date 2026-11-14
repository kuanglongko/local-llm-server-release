package com.xiaowan.localinference

/**
 * 中间层按「服务端上一轮告诉我的轮次坐标」在**客户端原件**上删整条消息（ISSUE #452 第 3 步）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它解决的是哪一件事
 * ══════════════════════════════════════════════════════════════════════════
 * 客户端每轮把**整份历史**重新送一遍。服务端这一轮实际喂进 prefill 的是
 * 「预算裁过 + 知识库注入过」的另一份 —— 上一轮它丢掉了最老的 N 轮，而客户端不
 * 知道 N 是多少（`local_ctx.messages` 只给了「几条」这个数）。
 *
 * 于是这一轮：客户端把丢掉的 N 轮又送回来了，服务端**再从最老开始丢一次**。
 * 两次丢的结果看起来一样，但那一段 token 每轮都被重新 prefill —— 起点在
 * 「客户端送的全量」与「服务端裁过的后缀」之间来回跳，KV 复用永远接不上。
 *
 * 中间层要做的就是把这件事提前：**按上一轮的坐标，先在客户端原件上删掉那几轮**，
 * 送上去的那一份就已经是服务端会保留的样子。删的是整条消息、按轮对齐 ——
 * 它不需要 tokenizer，也不需要复刻模板。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 判据只有一条：别猜
 * ══════════════════════════════════════════════════════════════════════════
 * 坐标对不上（客户端历史被编辑过、换了会话、system 变了、条数比上一轮少）时
 * **原样转发**，一个字节都不删。理由不是保守，是**代价不对称**：
 *   · 少删一轮 = 服务端这一轮自己再丢一次，行为与不带中间层完全一样（可接受）；
 *   · 删错一轮 = 用户手里的对话被悄悄改掉，而模型照常回答（不可接受）。
 * 所以下面每一处判断都朝"不删"那一侧倒，且删不下时给一句原因
 *（`RelayVerdict.reason`），让 `/health` 与日志说得清「为什么这轮没删」。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 首轮 system 只有一个来源：服务端留下的那一份
 * ══════════════════════════════════════════════════════════════════════════
 * 「首条 system 不动」在这里只有一个正确口径：**服务端这一轮喂进去的那一条**。
 * 它有两个读法，差一个 `kept`：
 *   · 客户端原件的第一条 —— 我们**不知道**服务端有没有把它丢掉（预算可以从最老的
 *     user 丢起，也可以连 system 一起丢）；
 *   · 上一轮那份坐标里第一条还没被丢的消息 —— 它才是服务端手里那份的开头。
 * 两者在"服务端留下了 system"时相同，在"服务端丢了 system"时**只差一条**。
 *
 * 差这一条会毁掉整层（ISSUE #452 真机「3~4 轮之后 KV 又不复用」）：
 * 坐标说"该从第 1 轮起"，而我们**自己**把 system 按"头"留下、又从第 1 轮起取：
 * 服务端把 system 一起丢掉时，这样送上去的那一份就比它手里的**少**一条 system，
 * 于是每一轮它都得从头 prefill（`head_same = 0`、`reuse` 归零），而中间层的每一格
 * 读数都正常。更毒的是它在下一轮**换了个样子回来**：这一轮的坐标按"服务端吃到的那份"
 * 算，那份里没有 system，"该删几轮"于是少 1 —— 我们少删一轮；就这样一轮少删一轮，
 * 一直少到 prompt 装不下、坐标翻成 `ask_only`（"全丢光了"），整层退回直连。
 * 现场读数正是这样：`fork_why` 报「两轮喂进去的不是同一份对话」，
 * 而两轮对话本来就是同一份 —— 差的是我们自己多发/少发的那一条 system。
 *
 * ⚠ 两边的说法（"多发"还是"少发"）取决于坐标里的 `m` 是对**哪一份**数的：
 *   它数的是**服务端保留集**，而我们切的是**客户端原件**。两者在"服务端留下了 system"
 *   时相等，在"服务端把 system 也丢了"时差一条 —— 所以**绝不许**在原件上另算一个"头"。
 *
 * 所以"该删几轮"与"留下的那份从哪一条开始"**必须来自同一份坐标**：
 * 前者是 `rounds` 里第一条 `kept` 的序号，后者是它的 `m`（保留集下标）。
 * 以前者算轮数、按"首条 system 之后"另算一个起点，就是上面那个自吃。
 * 今天三个入口（上一轮坐标 / 首帧沿用 / 起喂点）都收敛在 [cutFrom]，起点只写一遍。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它**不**做的事（三条纪律，与 `RoundGeometry` 同源）
 * ══════════════════════════════════════════════════════════════════════════
 *   · 不 tokenize、不估算 token 数 —— 裁剪粒度是「整轮」，精度它用不上；
 *   · 不动首条 system（它在 pos 0，服务端的预算也从不丢它）；
 *   · 不主动多删（不"猜服务端下一轮会丢多少"）—— 只删服务端**上一轮已经说明**
 *     它丢了的那几轮。提前多删一轮会让本轮的输入与上一轮不同，正是 #426 修掉的东西。
 */
object RelayTrim {

    /** 没报起喂点时的占位（[Prev.prefix] 见到它就当"没有"）。 */
    val EMPTY_PREFIX = IntArray(0)

    /** 上一轮服务端报回来的那几轮（`local_rounds.rounds[i]` 里 relay 真正要的两个数）。 */
    class RoundMark(
        /** 轮序号（跨轮稳定；服务端从 0 起，含被丢的那些）。 */
        val index: Int,
        /** 这一轮第一条消息在**服务端保留集**里的下标；`-1` = 已被丢掉。 */
        val first: Int,
        /** 这一轮还在不在服务端的 prompt 里。 */
        val kept: Boolean
    )

    /**
     * 上一轮那一屏读数（中间层唯一要记住的东西）。
     *
     * @param rounds  `local_rounds.rounds` 原样（顺序即轮序）
     * @param dropped 服务端上一轮丢掉的消息条数（`local_ctx.messages`）
     * @param askOnly 服务端上一轮是不是「历史丢光了」
     */
    class Prev(
        val rounds: List<RoundMark>,
        val dropped: Int,
        val askOnly: Boolean,
        /**
         * 这份读数来自**首帧**（ISSUE #452「拿回真流式」）：它只有"上一轮留下的那几轮
         * 还在"这一件事（`local_ctx.kept_rounds = 1`），**没有轮次坐标**。
         *
         * [apply] 据此走"沿用上一轮的决定"那一档 —— 中间层上一轮删了几轮，这一轮
         * 就照删几轮（客户端的消息只会往后长，那几轮还在原处）。这不是猜：它复用的是
         * **上一轮已经对齐过的那份事实**，而上一轮的对齐是有坐标依据的。
         *
         * ⚠ 千万别把它当成"坐标都在、一轮都没丢"：那会让 [apply] 算出一个删 0 轮的
         *   结论，于是下一轮照旧全量转发 —— 中间层静默退化成直连，而每一格读数都正常。
         */
        val keptRounds: Boolean = false,
        /**
         * 上一轮**客户端报上来的轮数**（`local_ctx.rounds` 的条目数，也就是服务端那一轮
         * 见到的轮数），`0` = 没量到 / 旧服务端没报。
         *
         * ══════════════════════════════════════════════════════════════════════
         * 为什么"轮数对不对"要拿**这个**比，而不是 `rounds.size`
         * ══════════════════════════════════════════════════════════════════════
         * `rounds` 是服务端**保留集**的坐标表 —— 它只有"服务端这一轮**真丢过**"的时候
         * 才有值（不丢就整格省略）。而中间层恰恰是那个"不丢"的原因：它上一轮已经
         * 前置删过，服务端这一轮一条都没再丢。
         *
         * 于是旧判据 `本轮轮数 < rounds.size` 在稳态下恒为真：`rounds` 停在**服务端
         * 自己丢历史的那一轮**看见的轮数（那份是"客户端原件"的轮数，含中间层删掉的
         * 那些），而本轮客户端送来的是同一份原件 —— 两边的数永远差着"中间层删掉的
         * 那几轮"。每一轮都报"历史被改过，原样转发"，`relay_forwarded` 恒 0，
         * 而服务端那边其实**一直按中间层送的那份在复用**。
         *
         * 所以比的是"上一轮服务端见到的轮数"，它才是同一个坐标系里的数。
         */
        val prevRoundCount: Int = 0,
        /**
         * 服务端报的"起喂点"几格（`local_ctx.prompt_prefix`；见 `RelayLedger.prefixOf`）。
         *
         * 空数组 = 这一轮没报。它回答的是 [carryOver] 答不了的那个问题：
         * **服务端这一轮真丢了历史，而中间层上一轮没删过**（INV-#452 现场：第一轮
         * relay 进来时 `carriedRounds == 0`，于是"照上一轮的决定"等于什么都不做）。
         * 此时唯一能用的证据就是服务端自己说的"我从哪儿开始喂"，把它换算成客户端
         * 原文里的消息边界，就是该从哪一条起保留。
         */
        val prefix: IntArray = EMPTY_PREFIX
    )

    /**
     * 这一轮该怎么删。
     *
     * @param messages 真正要转发的那一份（已经删过的）
     * @param droppedRounds 删掉了几轮（0 = 没删，原样转发）
     * @param reason 人话原因（`/health` 与日志共用；删成功时也写一句，别只报数）
     * @param coordRounds 服务端这一轮看到的**总轮数**（客户端原件口径：`kept + dropped`）。
     *   交给 `RelayLedger` 记成下一轮的 [Prev.prevRoundCount]，判据本身不读它。
     */
    class Verdict(
        val messages: List<Pair<String, String>>,
        val droppedRounds: Int,
        val reason: String,
        /**
         * 服务端这一轮一共见到几轮（客户端原件口径：留下的 + 又丢的 + 中间层上一轮
         * 前置删掉的）。交给 `RelayLedger` 记成下一轮的 [Prev.prevRoundCount]，
         * 判据本身不读它。
         */
        val coordRounds: Int = 0,
        /**
         * 留下来的那份在**输入**里从第几条开始（`-1` = 没删）。
         *
         * 它只服务一件事：下一轮**只有首帧坐标**时，"照上一轮的决定删"要知道
         * "从哪一条起"（见 [carryOver]）。用"首条 system 之后"去顶替它，会在服务端
         * 把 system 一起丢掉时每轮多发/少发一条（ISSUE #452，见文件头）。
         *
         * ⚠ 与 [messages] 同一次计算里产出，别让调用方自己再算一遍 ——
         *   算第二遍就等于有两个"起点"，而它们分叉的那一天不会报错。
         */
        val keepFrom: Int = -1
    )

    /**
     * 切轮：与服务端 `RoundGeometry.of` **同一条切法**（一条 user 连同其后所有连续
     * assistant 算一轮；首条 system 不属任何一轮；开头的孤 assistant 不成轮）。
     *
     * ⚠ 这里**必须**与 `RoundGeometry.of` 一致，但**不共用**它：那一份吃的是
     *   「服务端保留集」，这一份吃的是「客户端原件」。两者共用一个函数就会有人
     *   把"服务端的裁剪结果"当输入传进来 —— 而它就是中间层手里没有的那一份。
     *   `tools/relay/check_relay_wiring.py` 钉住两边的切法字符串同形。
     *
     * @return 每一轮 `[首条下标, 末条下标)`（开区间），按原序
     */
    fun rounds(messages: List<Pair<String, String>>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = if (messages.firstOrNull()?.first == "system") 1 else 0
        // 开头的孤 assistant 不成轮（它对应的提问早就不在了）—— 跳过，但不并入下一轮：
        // 并进去等于把一段无主的回答当成"这一轮的开头"，删的时候会连着删错。
        while (i < messages.size && messages[i].first == "assistant") i++
        while (i < messages.size) {
            if (messages[i].first != "user") { i++; continue }
            val first = i
            i++
            while (i < messages.size && messages[i].first == "assistant") i++
            out.add(first until i)
        }
        return out
    }

    /**
     * 按上一轮的坐标，把服务端已经丢掉的那几轮从客户端原件里删掉。
     *
     * 判据顺序（每一步失败都朝"不删"倒，并给一句原因）：
     *   ① 上一轮没有坐标 -> 不删（`local_rounds` 只在丢过历史的轮次出现）；
     *   ② 本轮客户端的轮数比上一轮**少** -> 不删（历史被改过，坐标对不上）；
     *   ③ 上一轮保留的第一轮，本轮按序号找得到、且角色序列一致 -> 删它前面的那几轮；
     *   ④ 删完之后首条 system 之后不许出现"没有提问的 assistant 残句"
     *      （与服务端 `resumeShape` 同一条形状纪律，理由见那边的注释）。
     *
     * @param messages 客户端这一轮送来的全量消息（**原件**，不修改它）
     * @param prev 上一轮那一屏读数（`null` = 没有上一轮 / 进程刚起 / 换了会话）
     */
    fun apply(messages: List<Pair<String, String>>, prev: Prev?): Verdict {
        if (prev == null) return Verdict(messages, 0, "没有上一轮坐标（首轮 / 刚启动 / 换了会话）")
        if (prev.rounds.isEmpty()) {
            return if (prev.keptRounds) carryOver(messages, prev.prefix)
            else Verdict(messages, 0, "上一轮没报坐标（服务端那一轮没丢过历史）")
        }
        val keptIdx = prev.rounds.indexOfFirst { it.kept }
        if (keptIdx <= 0) {
            // `-1` = 上一轮全丢光了（`ask_only`），`0` = 一条都没丢。
            // 前者其实是**该删**的一档，但它与"坐标不可用"在读数上太像，而误删的代价
            // 是不可接受的 —— 这一档交给服务端自己再丢一次（与不带中间层同形）。
            return Verdict(messages, 0,
                if (keptIdx < 0) "上一轮历史全丢光了（ask_only），坐标不可用 —— 原样转发"
                else "上一轮一轮都没丢 —— 原样转发")
        }
        // 服务端保留的第一轮，它的**轮序号**就是"服务端这一轮又丢了几轮"（相对它
        // 收到的那一份 = 客户端原件减掉中间层上一轮删掉的 `carriedRounds` 轮）。
        // 所以落到**客户端原件**上该删的累计轮数是两者相加 —— 少加 `carriedRounds`
        // 就是本层此前的 bug：中间层每轮只删服务端"这一轮"丢的那几轮，上一轮已经
        // 删过的轮次于下一轮又被客户端送回来，前缀起点在"原件"与"服务端保留集"
        // 之间来回跳，KV 复用永远接不上。
        //
        // ⚠ 两个数出自两个坐标系（`index` 相对服务端输入，`carriedRounds` 相对原件），
        //   但相加是合法的：中间层上一轮交给服务端的就是"原件删掉 carriedRounds 轮"。
        //   判据只有一条前提 —— 客户端历史只会往后长、那几轮还在原处，它由
        //   `RelayLedger.matchesChain` 的前缀比对先钉住。
        val serverDropped = prev.rounds[keptIdx].index
        val dropRounds = RelayTrim.carriedRounds + serverDropped
        return cutFrom(messages, rounds(messages), dropRounds, "按上一轮的坐标",
            prev.prevRoundCount)
    }

    /**
     * 真删：删掉最老 `dropRounds` 轮，首条 system 留着，删完修形状。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 切的位置按**轮**算
     * ══════════════════════════════════════════════════════════════════════════
     * 坐标里的 `m` 是**服务端保留集**下标，不是客户端原件下标：服务端删掉最老几轮
     * 之后，保留集从 `m` 起；而中间层切的是原件，原件里那几轮**还在**。拿 `m`
     * 去切原件就是文件头那个自吃（每轮跟服务端手里那份差着被删的那几轮）。
     * 所以这一刀真正的位置是**轮**：删完 `dropRounds` 轮之后，留下来的那份从
     * `cut[dropRounds].first` 起。`m` 不参与定位 —— 它只描述服务端保留集内部，
     * 与原件之间差着"服务端丢掉的条数 + 中间层丢掉的条数"，两份坐标系没有一处
     * 公共锚点可比。跨轮对齐靠的是 `index`（轮序号）+ 累计 `carriedRounds`。
     *
     * [apply] / [carryOver] 两个入口共用这一处：判据各写一遍，必然有一天只改了一处 ——
     * 而那种漂移的表现是"某条路径删在多一句的地方"。
     *
     * 判据顺序（每一步失败都朝"不删"倒，并给一句原因）：
     *   ① 轮数对不对（比的是**上一轮服务端见到的总轮数**，见 [Prev.prevRoundCount]）；
     *   ② 轮数够不够（**删完还得有得剩** —— 删光等于替用户清空对话）；
     *   ③ 被删的那一段里只许有 user / assistant（不许悄悄删掉第二条 system）；
     *   ④ 删完不许留下"没有提问的 assistant 残句"（与服务端 `resumeShape` 同一条）。
     *
     * @param cut 本轮的轮（[rounds]，调用方算一次就够）
     * @param dropRounds 该删几轮（**客户端原件口径**的累计值）
     * @param why 这一刀的出处（日志与 `/health` 用：上一轮坐标 / 首帧沿用 / 起喂点）
     * @param prevRoundCount 上一轮服务端见到的**总轮数**（`0` = 旧服务端没报 / 没量到；
     *   那时退回比 `rounds.size`，宁可少删一轮也不误删 —— 见 [Prev.prevRoundCount]）
     */
    private fun cutFrom(messages: List<Pair<String, String>>, cut: List<IntRange>,
                        dropRounds: Int, why: String,
                        prevRoundCount: Int = 0): Verdict {
        if (dropRounds <= 0) return Verdict(messages, 0, "$why：该删的轮数是 0 —— 原样转发")
        // 轮数对不对：比的是**上一轮服务端见到的轮数**（`prevRoundCount`），不是
        // `rounds.size` —— 后者是"服务端保留集"的条目数，只在服务端真丢过历史的
        // 那一轮才有值，而中间层恰恰让它此后都别再丢。拿它比等于每轮都判"历史被改过"，
        // 于是 `relay_forwarded` 恒 0，而服务端那边其实一直按中间层送的那份在复用
        // （见 [Prev.prevRoundCount]）。旧服务端没报那个数（= 0）时退回老判据。
        if (prevRoundCount > 0 && cut.size < prevRoundCount) {
            return Verdict(messages, 0,
                "$why：本轮只有 ${cut.size} 轮，上一轮报了 $prevRoundCount 轮 —— 历史被改过，原样转发")
        }
        // ⚠ 门槛是"删完还得有得剩"（`>`，不是 `>=`）：删光等于替用户清空对话，
        //   而删少一轮只是"这一轮没省下"，代价不对称。
        if (cut.size <= dropRounds) {
            return Verdict(messages, 0,
                "$why：该删 $dropRounds 轮，但本轮只有 ${cut.size} 轮（删完就一条不剩）—— 原样转发")
        }
        // ⚠ 这一段是"从 `byRounds` 起原序保留、前面整段删掉"的**唯一**实现 —— 别改成
        //   `messages.drop(from)` 之类的链式写法：`drop` 与"保留首条 system"两件事
        //   分开写之后，某一天就只剩其中一件了（而那样读起来仍然是对的）。
        val head = if (messages.firstOrNull()?.first == "system") 1 else 0
        val byRounds = cut[dropRounds].first
        // 被删掉的那一整段里只许有 user / assistant：首条 system 之外的任何一条
        // system（客户端自己插的第二段提示）被静默删掉，用户手里的对话就变了，
        // 而模型照常回答 —— 这一档与"坐标对不上"同样朝"不删"倒。
        // ⚠ 它**不**参与定位（定位只由轮序号 `cut[dropRounds].first` 给）：只在删之前
        //   核一遍要被删的角色，与 `m` / `keepFrom` 属于哪个坐标系无关。
        for (k in head until byRounds) {
            val role = messages[k].first
            if (role != "user" && role != "assistant") {
                return Verdict(messages, 0,
                    "$why：要删的那一段里有 $role 消息 —— 坐标不可信，原样转发")
            }
        }
        val out = ArrayList<Pair<String, String>>(messages.size - byRounds + head)
        for (k in 0 until head) out.add(messages[k])
        for (k in byRounds until messages.size) out.add(messages[k])
        // 形状：首条 system 之后紧跟 assistant 是"提问已经不在了"的残句，删掉它
        // （与服务端 `resumeShape` 同一条纪律；这里只删不加，一个字节都不新增）。
        val shaped = dropLeadingOrphans(out)
        // 起点跟着形状修正挪：`dropLeadingOrphans` 只会往后丢孤儿 assistant，
        // 所以"留下来的那份"是从 `byRounds` 之后第一条 `user` 起（它本身已经是某轮的首条）。
        var kept = byRounds
        while (kept < messages.size && messages[kept].first != "user") kept++
        if (kept >= messages.size) kept = -1
        return Verdict(shaped, dropRounds,
            "$why，删掉最老 $dropRounds 轮（${messages.size - shaped.size} 条消息）",
            // 服务端这一轮一共见到几轮 = 本轮原件轮数 + 这一刀删掉的（`cut` 是原件口径，
            // 本来就含 `dropRounds`）—— 交给下一轮跟客户端送来的轮数比。
            coordRounds = cut.size,
            keepFrom = kept)
    }

    /**
     * 「上一轮删了几轮，这一轮照删几轮」—— 首帧那一份坐标能支撑的唯一结论。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这不是"猜着删"
     * ══════════════════════════════════════════════════════════════════════════
     * 首帧只说了一件事：服务端**接上了上一轮留下的那几轮缓存**（`lcp > 0`）。
     * 也就是说，服务端这一轮的输入 = 中间层上一轮送上去的那一份的那些轮次 ——
     * 而那一份的**头部是中间层自己删过的**（删了几轮、从哪一条起，它自己记着）。
     *
     * 客户端的消息只会往后长（这一条是 [apply] 全部判据的前提，从第一版起就在），
     * 所以"上一轮我删掉的那几轮"这一轮还在原处、还是那几轮 —— 照删就是了。
     * 这一步不做任何新的推断，它只是把**上一轮已经对齐过的那份事实**再用一次。
     *
     * ⚠ 三条门槛，缺一不可（每一条都朝"不删"倒）：
     *   · 上一轮真的删过（`carriedRounds <= 0` 时没有可沿用的决定）；
     *   · "从哪一条起"这一轮还算得出来（[carriedKeepFrom] 有值,且落在原件里）；
     *   · 要删的那一段里只有 user/assistant，且删完不留孤儿（与 [apply] 同一条纪律）。
     *
     * ⚠ 首帧这一档**没有** `m`（那一份坐标里没有 `rounds`），所以"从哪一条起"只能用
     *   自己上一轮记下的那一格 [carriedKeepFrom]（上一轮真删时算出来的原件下标）。
     *   **不许**回去按"首条 system 之后"另算一个头 —— 那正是文件头那个自吃
     *   （服务端把 system 也丢了时，每轮少发一条）。
     *
     * ⚠ 这里读的 `carriedRounds` 是**累计值**：首帧那一份没有 `rounds`，唯一能沿用的
     *   就是"上一轮从原件上删掉的那些轮次这一轮还在原处"，而上一轮删的正是累计值。
     *   服务端这一轮若又丢了 k 轮，它会在**响应头**里带 `rounds`（不走进这一档）。
     */
    private fun carryOver(messages: List<Pair<String, String>>, prefix: IntArray): Verdict {
        val cut = rounds(messages)
        val dropRounds = carriedRounds
        // 上一轮中间层自己没删过 —— 但服务端**这一轮真丢了历史**（它报了起喂点的字符位）。
        // 那一档不能"照上一轮的决定"（那是 0 轮），只能按服务端说的起点定位。
        // 这正是 INV-#452 现场：第一轮 relay 进来 `carriedRounds == 0`，服务端每轮都在丢，
        // 而中间层一次都没删过 —— `relay_forwarded` 恒 0、`kv_reuse_tokens` 只剩 system。
        if (dropRounds <= 0) return byPrefix(messages, cut, prefix)
        // ⚠ 门槛是"**删完还得有得剩**"（`cut.size > dropRounds`），不是 `>=`：
        //   客户端的消息只会往后长，而"这一轮刚好只有上一轮删掉的那几轮"意味着
        //   历史被改短过（或者它本来就是同一份被重发）—— 删完一条不剩等于替用户
        //   清空了对话，而删 0 轮只是"这一轮没省下"，代价不对称。
        if (cut.size <= dropRounds) {
            return Verdict(messages, 0,
                "首帧坐标：要沿用上一轮的 $dropRounds 轮，但本轮只有 ${cut.size} 轮" +
                    "（删完就一条不剩）—— 原样转发",
                // 首帧那一份里没有 `rounds`，所以"上一轮服务端见到几轮"只能是**推**的：
                // 本轮客户端原件的轮数。客户端的消息只会往后长（[apply] 全部判据的前提），
                // 所以它要么等于上一轮、要么更大 —— 两种都不会让下一轮判成"历史被改过"。
                coordRounds = cut.size)
        }
        // 首帧这一档没有 `m`（那份坐标里没有 `rounds`），起点只能用上一轮算出来的那一格。
        // ⚠ 这一档不传 `prevRoundCount`：上一轮那份读数里根本没有轮数（没有 `rounds` 表），
        //   拿"推"出来的数去判"历史被改过"就是自己跟自己比。
        //
        // 上一轮记下的起点（原件下标）这一轮应当**落在同一处**：客户端历史只会往后长，
        // 那几轮还在原处。对不上说明中间层自己的账与原件已经分叉（进程里换了会话 /
        // 上一轮重写失败但记忆照写），这一档退回"不删"—— 与 [apply] 同一条代价不对称。
        if (carriedKeepFrom >= 0 && cut.size > dropRounds &&
            cut[dropRounds].first != carriedKeepFrom) {
            return Verdict(messages, 0,
                "首帧坐标：上一轮记下的起点 $carriedKeepFrom 与按 $dropRounds 轮算出的 " +
                    "${cut[dropRounds].first} 对不上 —— 原样转发")
        }
        return cutFrom(messages, cut, dropRounds, "首帧坐标：沿用上一轮的决定")
    }

    /**
     * 中间层**累计**已经从客户端原件上删掉了几轮（`RelayLedger.markForwarded` 的落值）。
     *
     * 它是**累计值**，不是"上一轮新删了几轮"：上一轮删掉的 N 轮这一轮客户端会原样
     * 送回来，所以这一轮要从原件上删的仍然是那 N 轮；服务端若又丢了 k 轮，就变成
     * N+k（见 [apply] 里 `carriedRounds + index`）。写成"上一轮新增"会把服务端
     * 已经丢掉的轮次每轮少删一遍，前缀起点在原件与保留集之间来回跳 —— 那正是
     * relay 端点这一层此前没达到目的的原因。
     *
     * 它（连同 [carriedKeepFrom]）是 [carryOver] 的全部输入，也是这一层**唯一**一处
     * 跨轮记忆 —— 所以只由 [remember] 写，而 [remember] 只由调用方在"这一轮定下之后"
     * 调一次。`0` = 没删过（首轮 / 上一轮坐标不可用）。
     */
    @Volatile
    var carriedRounds: Int = 0
        private set

    /**
     * 上一轮留下来的那份在**客户端原件**里从第几条开始（`-1` = 没删过 / 没算出来）。
     *
     * 与 [carriedRounds] 必须成对写：一个答"删了几轮"，一个答"删到哪一条"。
     * 只有轮数时，首帧那一档只能回去按"首条 system 之后"猜一个起点 —— 而那个猜法
     * 在服务端把 system 一起丢掉时会**每轮多/少发一条**（ISSUE #452，见文件头）。
     */
    @Volatile
    var carriedKeepFrom: Int = -1
        private set

    /**
     * 按**服务端报的起喂点**定位：客户端原文里的哪一条消息，是它真正开始喂的地方。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么用"字符比例"而不是"token 位"
     * ══════════════════════════════════════════════════════════════════════════
     * 中间层没有 tokenizer（它不该有，见文件头），token 位它读不懂；而整段 prompt
     * 就是客户端那份 messages 渲染出来的 —— 每条消息的**累计字符数**就是消息边界，
     * 中间层自己算得出来。
     *
     * 服务端报的是 `chars_start` —— 起喂点在客户端原文里的**字符位**（换算率是它这一轮
     * 自己量到的，见 `prompt_prefix`）。这里只在自己那份 messages 上累加 `content.length`
     * 去对齐，不再做第二套换算：多一处换算就多一种"切在半句上"的方式。
     *
     * ⚠ 三条门槛，每一步都朝"不删"倒（与 [apply] 同一条纪律）：
     *   · 起点在 0 附近 / 没报 → 不删（服务端一枚都没动，或者它没量到）；
     *   · 换出来的边界不落在任何一条消息的**开头** → 不删（宁可少删一轮，也不切在半句上）；
     *   · 删完一条不剩 → 不删。
     *
     * @param parts `local_ctx.prompt_prefix` 的几格：`tok_start` / `tok_end` / `chars_start` /
     *   `cpq_x1000` / `tail_rounds`（最后这一格可以没有 —— 那时不做轮边界核对）
     */
    private fun byPrefix(messages: List<Pair<String, String>>, cut: List<IntRange>,
                         parts: IntArray): Verdict {
        if (parts.size < 3) return Verdict(messages, 0, "首帧坐标：上一轮没删过 —— 原样转发")
        val tokStart = parts[0]
        val tokEnd = parts[1]
        if (tokEnd <= 0) return Verdict(messages, 0, "首帧坐标：起喂点量不到 —— 原样转发")
        // `chars_start` 就是服务端说的"我从客户端原文的第几个字符开始喂"——
        // 它在 0 附近（`tok_start <= 0` 会被换算成 0）意味着"服务端一枚都没动"，
        // 那一档由下面 `want <= 0` 与"落在第一条开头"两条判据一起兜住，口径相同。
        // 它已经把 token 位换算好了（换算率是服务端这一轮自己量到的，见 `prompt_prefix`）。
        // 中间层只在自己的 messages 上累加 `content.length` 去对齐，不做第二套换算。
        val want = parts[2].toLong()
        if (want <= 0) return Verdict(messages, 0, "首帧坐标：服务端一枚都没动 —— 原样转发")
        // 客户端这条轴：第 k 条消息的**起点**累计字符数（首条 system 也占位）。
        val starts = LongArray(messages.size)
        var acc = 0L
        for (k in messages.indices) { starts[k] = acc; acc += messages[k].second.length }
        if (acc <= 0) return Verdict(messages, 0, "首帧坐标：本轮正文为空 —— 原样转发")
        // 只认"落在某一条消息开头附近"的边界。
        //
        // ⚠ 容差必须**小于最短的一条消息**，否则它连相邻的两条都分不开：一条 40 字符的
        //   消息，容差 25 就已经能同时罩住它和它后面那条 —— 那时"落在半句上"也会被
        //   吸附到某个边界上，而这正是"切错半轮"的入口。所以取**最短消息长的一半**，
        //   再与整段的 2% 取小：短会话（每条都是长段正文）也不会被这条规则放宽。
        //   `>= 1` 兜住"全是单字符消息"那种极端形状 —— 那时边界本身也只有 1 字符宽。
        val shortest = messages.filter { it.second.isNotEmpty() }.minOfOrNull { it.second.length } ?: 0
        if (shortest <= 0) return Verdict(messages, 0, "首帧坐标：本轮正文为空 —— 原样转发")
        val tol = minOf(shortest / 2, (acc / 50).toInt()).coerceAtLeast(1).toLong()
        var cutAt = -1
        var best = Long.MAX_VALUE
        for (k in messages.indices) {
            val d = Math.abs(want - starts[k])
            if (d > tol) continue
            // 首条 system 不能当切口（它在 pos 0，服务端的预算也从不丢它）。
            if (k == 0 && messages[0].first == "system") continue
            if (d < best) { best = d; cutAt = k }
        }
        if (cutAt < 0)
            return Verdict(messages, 0,
                "首帧坐标：起喂点（字符 $want）不落在任何一条消息的开头 —— 原样转发")
        // 切在 `cutAt` 上：它前面那些消息服务端这一轮没喂，正是该删的。
        var dropRounds = cut.count { it.first < cutAt }
        if (dropRounds >= cut.size)
            return Verdict(messages, 0, "首帧坐标：起喂点落在最后一轮之后 —— 坐标不可信，原样转发")
        // 起喂点算出来的那条消息就是 `cut[dropRounds].first` —— 它**不是**
        // "首条 system 之后"，所以这一档本来就没有自吃；起点仍交给 [cutFrom]，
        // 让"怎么切"只有一处。
        return cutFrom(messages, cut, dropRounds, "首帧坐标：按服务端报的起喂点")
    }

    /**
     * 记下这一轮的决定（[apply] 的调用方在**发出请求之前**调，与记账同一处）。
     *
     * @param droppedRounds 这一轮删了几轮（[Verdict.droppedRounds]）
     * @param keepFrom 留下来的那份在客户端原件里的起点（`messages` 的下标）；
     *   删了 0 轮时传 `-1`（没有"留下来的那份"这回事）。
     */
    fun remember(droppedRounds: Int, keepFrom: Int = -1) {
        val d = droppedRounds.coerceAtLeast(0)
        carriedRounds = d
        carriedKeepFrom = if (d > 0 && keepFrom >= 0) keepFrom else -1
    }

    /**
     * 首条 system 之后不许出现"没有提问的 assistant 残句"。
     *
     * 与服务端 `ContextBudget.resumeShape` **同一条纪律**：删只删，不加任何东西。
     * 中间层多写一个字节，服务端下一轮的输入就与这一轮不同，KV 前缀立刻断在那一枚上。
     */
    private fun dropLeadingOrphans(msgs: List<Pair<String, String>>): List<Pair<String, String>> {
        if (msgs.isEmpty()) return msgs
        val head = if (msgs.first().first == "system") 1 else 0
        var from = head
        while (from < msgs.size && msgs[from].first == "assistant") from++
        if (from == head) return msgs
        if (from >= msgs.size) return msgs
        val out = ArrayList<Pair<String, String>>(msgs.size - from + head)
        for (k in 0 until head) out.add(msgs[k])
        for (k in from until msgs.size) out.add(msgs[k])
        return out
    }
}
