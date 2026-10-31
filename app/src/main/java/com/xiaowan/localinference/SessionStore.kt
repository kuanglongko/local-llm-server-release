package com.xiaowan.localinference

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多会话管理。存储布局（均在 ctx.filesDir）：
 * - sessions/s_<id>.json —— 每会话一个文件
 *   {id,title,system,cardId,cardSnapshot,createdAt,updatedAt,messages:[{role,content}]}
 * - sessions_current.txt —— 当前会话 id
 * - chat_session.json     —— 旧版单会话文件，首次访问时自动迁移为「会话 1」
 * UI 入口在 EngineActivity 聊天页；生成中禁止切换/删除由调用方保证。
 *
 * ⚠ 结构里的 `cardId` / `cardSnapshot` 是**角色卡（V1）**加的两个字段。
 *   加字段**不改文件名、不改版本号**（同 `ChatArchive` 那条口径：读侧一律
 *   `optString(...)` 缺省处理）—— 老会话文件照样能打开，只是那两条数据为空，
 *   语义正好是"这个会话没用卡片"，与 V1 之前的行为逐字相同。
 */
object SessionStore {

    /** 发给模型的历史字符数上限，防撑爆 ctx。裁剪留痕见 [capChars]。 */
    const val MAX_CHARS = 24000

    /** 裁剪时**保底保留**的消息条数（至少留住最近一轮问答）。 */
    const val MIN_MSGS = 2

    /**
     * 每会话最多留存的**消息条数**（[append] 的条数保险丝）。
     *
     * `40 条 = 20 轮问答` —— 这个换算关系钉在这里，因为代码里没有 "20" 这个数：
     * 上一版注释写着"最多 20 轮"，而实现只写 `> 40`，谁把 40 改成 60 都不知道
     * 它代表轮数。轮数只在注释里活着，条数才是真家伙。
     */
    const val MAX_MSGS = 40

    private const val TAG = "SessionStore"
    private const val DIR = "sessions"
    private const val CUR = "sessions_current.txt"
    private const val LEGACY = "chat_session.json"

    data class Msg(val role: String, val content: String)

    data class Info(
        val id: String, val title: String, val system: String,
        val createdAt: Long, val updatedAt: Long, val turns: Int
    )

    /**
     * 旧单会话迁移的**结果**。
     *
     * 为什么不是一个 `Boolean`：三种结局对用户的意义完全不同，而此前它们
     * 全都塌成同一个 `Unit`（`?: return`），第三种的症状是
     * 「我的旧聊天记录不见了」——
     * - [OK]：迁成功了。
     * - [NO_LEGACY]：本来就没有旧文件（绝大多数用户）。**这不是错误**，
     *   不能出声，否则每次冷启动都报一次"迁移失败"，用户会去删数据。
     * - [BLOCKED_BY_LIMIT]：**有**旧文件、但库里正文总字符数已达
     *   [SessionQuota.MAX_TOTAL_CHARS]，迁移被挡住了。旧文件留在原地
     *   （下次启动重试），但**必须说出来** —— 否则用户看到的是记录凭空消失，
     *   而真相是"被配额挡住了"。这两者的处置完全不同（一个是数据没了，
     *   一个是删掉一些旧会话就好）。
     * - [FAILED]：旧文件在、读取或写入失败。同样是"记录不见了"，
     *   但处置是看日志，不是删会话 —— 所以与 [BLOCKED_BY_LIMIT] 分开。
     */
    enum class MigrateResult { OK, NO_LEGACY, BLOCKED_BY_LIMIT, FAILED }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun fileOf(ctx: Context, id: String): File? =
        if (id.isEmpty()) null else File(dir(ctx), "s_$id.json")

    @Synchronized
    fun currentId(ctx: Context): String? {
        // 显式丢弃迁移结果：这里只负责"读当前 id"。迁移的**结局**只由
        // EngineActivity.initSessions 一处认领并在页面上说出来 ——
        // 在读取路径里也顺手报一次，会出现"删掉几个会话后切一下会话，
        // 提示自己消失了或者又冒出来"，用户没法判断到底迁没迁。
        migrateIfNeeded(ctx)
        val f = File(ctx.filesDir, CUR)
        val id = runCatching { f.readText().trim() }.getOrNull()
        if (!id.isNullOrEmpty() && fileOf(ctx, id)?.exists() == true) return id
        return list(ctx).firstOrNull()?.id?.also { setCurrent(ctx, it) }
    }

    private fun setCurrent(ctx: Context, id: String) {
        runCatching { File(ctx.filesDir, CUR).writeText(id) }
    }

    /**
     * 新建会话；**配额不够**返回 null（空会话没有正文，只判"还能不能放"）。
     *
     * 判据是"还能不能放下一个新的空会话"：空会话本身 0 字符，所以只在库已经
     * 顶到硬上限时才拒绝 —— 这正是我们要的方向（老库超限后仍可整理，但不再增长）。
     * 标题默认「会话 N」。
     */
    @Synchronized
    fun create(ctx: Context): String? {
        // fix: create 不再调用 migrateIfNeeded（旧版 renameTo 失败会导致无限递归 StackOverflow）
        if (!SessionQuota.decide(usedChars(ctx), 0L).allowed) return null
        val id = System.currentTimeMillis().toString(36) + ((Math.random() * 1e4).toInt().toString(36))
        val now = System.currentTimeMillis()
        val obj = JSONObject()
            .put("id", id).put("title", "会话 ${list(ctx).size + 1}")
            .put("system", "").put("createdAt", now).put("updatedAt", now)
            // 角色卡（V1）：新会话是"不用卡片"这一档 —— 空串是那个语义的编码，
            // 不是"还没设过"。见 [cardSnapshotOf] 那条注释。
            .put("cardId", "").put("cardSnapshot", "")
            .put("messages", JSONArray())
        runCatching { fileOf(ctx, id)?.writeText(obj.toString()) }
        setCurrent(ctx, id)
        return id
    }

    /** 按更新时间倒序列出全部会话。损坏文件跳过。 */
    @Synchronized
    fun list(ctx: Context): List<Info> {
        val out = mutableListOf<Info>()
        dir(ctx).listFiles { f -> f.name.startsWith("s_") && f.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                out.add(Info(
                    o.optString("id", f.name.removePrefix("s_").removeSuffix(".json")),
                    o.optString("title", "未命名"),
                    o.optString("system", ""),
                    o.optLong("createdAt", 0), o.optLong("updatedAt", 0),
                    o.optJSONArray("messages")?.length() ?: 0
                ))
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    /**
     * 全部会话消息正文的**总字符数** —— 配额判据的输入（见 [SessionQuota]）。
     *
     * 为什么统计"正文"而不是文件字节数：配额防的是"喂给模型的内容体量"，
     * 而 JSON 里的字段名、标题、system 都是定长的小量，混进来只会让
     * 数字随实现细节漂移（改个字段名就变），算不清也说不清。
     *
     * **损坏文件按 0 计**（读不出来的不占额度）：读失败的文件本来就不该
     * 把用户卡在配额外，而它也不会真的被喂给模型。这与 [list] 跳过损坏文件同一口径。
     */
    @Synchronized
    fun usedChars(ctx: Context): Long {
        var sum = 0L
        dir(ctx).listFiles { f -> f.name.startsWith("s_") && f.name.endsWith(".json") }?.forEach { f ->
            sum += runCatching {
                val arr = JSONObject(f.readText()).optJSONArray("messages") ?: return@runCatching 0L
                var n = 0L
                for (i in 0 until arr.length()) n += arr.getJSONObject(i).optString("content").length
                n
            }.getOrDefault(0L)
        }
        return sum
    }

    /** 一份导出/待导入会话的正文量（与 [usedChars] 同一口径）。 */
    fun charsOf(s: ChatArchive.Session): Long = s.messages.sumOf { it.content.length.toLong() }

    @Synchronized
    fun rename(ctx: Context, id: String, title: String): Boolean = mutate(ctx, id) {
        it.put("title", title.trim().ifEmpty { it.optString("title", "会话") })
    }

    fun titleOf(ctx: Context, id: String): String = readObj(ctx, id)?.optString("title") ?: ""

    /** 会话文件是否存在（用于删除后防御性校验，避免幽灵复活）。 */
    fun exists(ctx: Context, id: String): Boolean = fileOf(ctx, id)?.exists() == true

    /** system 提示词按会话持久化。 */
    @Synchronized
    fun setSystem(ctx: Context, id: String, sys: String): Boolean = mutate(ctx, id) { it.put("system", sys) }

    fun systemOf(ctx: Context, id: String): String = readObj(ctx, id)?.optString("system", "") ?: ""

    /**
     * 会话当前的**角色卡快照**（空串 = 这个会话不用卡片）。
     *
     * ⚠ 「不用卡片」与「用了卡片但内容是空的」在这里**同形**（都是空串）。
     *   这是刻意的：内容为空的卡片在 [RoleCard.validate] 那一层就存不进去，
     *   所以"空串"在数据上只可能表示"没有卡片"。少一个状态就少一处要维护的判据。
     */
    fun cardSnapshotOf(ctx: Context, id: String): String =
        readObj(ctx, id)?.optString("cardSnapshot", "") ?: ""

    /** 会话用的是**哪一张**卡片（空串 = 没用卡片）。只在界面上用来认人/比对新鲜度。 */
    fun cardIdOf(ctx: Context, id: String): String =
        readObj(ctx, id)?.optString("cardId", "") ?: ""

    /**
     * 给会话挂上（或摘掉）一张卡片。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么存的是**内容快照**，而不是"卡片 id + 到时候去库里取"
     * ══════════════════════════════════════════════════════════════════════
     * 只存 id 的话，卡片一删/一改名，那个会话的人设就**静默没了** ——
     * 而它照旧能打开、照旧能聊、照旧 200，只是"回答怪怪的"。
     * 那是这个仓库里最难归因的一类（与"模型抽风"完全同形）。
     *
     * 存快照的代价是"改了卡片，已有会话不跟着变"。这个代价**可见**
     * （`RoleCard.match` 判成 STALE，聊天页那一行会说出来），
     * 而上面那个代价不可见。两个方向都要付一点，就选**代价可见**的那个。
     *
     * `cardSnapshot` 传空串 = 摘掉卡片（会话回到"用用户手打的 system"）。
     */
    @Synchronized
    fun setCard(ctx: Context, id: String, cardId: String, cardSnapshot: String): Boolean =
        mutate(ctx, id) { it.put("cardId", cardId).put("cardSnapshot", cardSnapshot) }

    /** 这个会话用了快照里的那一份卡片内容（判据本身在 [RoleCard.match]，这里只取数）。 */
    fun cardSnapshotOfPair(ctx: Context, id: String): Pair<String, String> =
        cardIdOf(ctx, id) to cardSnapshotOf(ctx, id)

    /**
     * 这个会话的**注入块渲染宽度**（每条正文多少个 UTF-16 码元；0 = 还没定过）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么要落盘，而不是"每次按当前容量算"
     * ══════════════════════════════════════════════════════════════════════
     * 宽度来自「每格容量 ÷ 4 ÷ 条数」，而每格容量随设置页 ctx 变。
     * 于是**改一次并发数，块里每一条都短一截** —— 块之后的那一段每轮都对不上，
     * KV 前缀断在块头，历史全部重来。真机上表现为"改过 parallelN 之后复用
     * 忽然变差"，而日志里每一句都正常。
     *
     * 宽度是**跨轮不变**的东西，就不能由会变的量决定。所以只在会话第一次渲染
     * 注入块时定一次，之后跟着会话走：容量再变（换 parallelN / 换模型）也不挪。
     *
     * 与 `cardSnapshot` / `cardId` 同一条口径：加字段**不改文件名、不改版本号**，
     * 读侧 `optInt(..., 0)` 缺省处理 —— 老会话照样能打开，只是那格是 0（= 还没定过）。
     */
    fun injectWidthOf(ctx: Context, id: String): Int =
        readObj(ctx, id)?.optInt("injectWidth", 0) ?: 0

    /** 记下这个会话的注入块宽度（只在第一次定，[SessionStore.injectWidthOf] 是判据）。 */
    @Synchronized
    fun setInjectWidth(ctx: Context, id: String, width: Int): Boolean =
        mutate(ctx, id) { it.put("injectWidth", width.coerceAtLeast(0)) }

    /**
     * 这个会话「每一轮注入块」的**回放表**：消息内容身份 -> 当时拼进去的那一段。
     *
     * 存的是**会话级**字段，不是逐条消息的附加字段。理由不是"好不好看"：
     * [writeMessages]（`deleteAt` / `truncateTo`）只重写 `messages` 这一格，
     * 写在消息对象上的附加字段会在任何一次删除/截断里**静默消失**，
     * 而消失的后果是"那一轮的块下一轮拼不回来"—— 前缀断开，且没有任何读数指向它。
     *
     * 键是内容身份（[RagInject.historyKey]，角色 + 正文 + 出现序号），不是下标：
     * 历史从头部被预算丢掉时下标会整体位移，而文本不会。
     *
     * 与 `injectWidth` / `cardSnapshot` 同一条口径：加字段**不改文件名、不改版本号**，
     * 读侧缺省为空表 —— 老会话照样能打开，只是还没有可回放的块（等价于功能刚打开）。
     */
    @Synchronized
    fun ragBlocksOf(ctx: Context, id: String): Map<String, String> {
        val arr = readObj(ctx, id)?.optJSONArray("ragBlocks") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("key", "")
            val v = o.optString("block", "")
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** 覆盖这个会话的回放表；与旧表相同则不写盘（同一轮重复调用不产生无谓 IO）。 */
    @Synchronized
    fun setRagBlocks(ctx: Context, id: String, blocks: Map<String, String>): Boolean {
        if (ragBlocksOf(ctx, id) == blocks) return false
        return mutate(ctx, id) { o ->
            val arr = JSONArray()
            for ((k, v) in blocks) {
                if (k.isEmpty() || v.isEmpty()) continue
                arr.put(JSONObject().put("key", k).put("block", v))
            }
            o.put("ragBlocks", arr)
        }
    }

    /**
     * 把所有会话的回放表都清掉（改相似度阈值时调用）。
     *
     * 为什么不是"只清当前会话"：阈值是一个**全局**设置，但回放表是每会话一份。
     * 当前会话清了、别的会话还留着按旧阈值攒下的块 —— 用户切过去后旧块照样回放，
     * 表现就是"阈值改了，这个对话里没用"。
     *
     * 只删 `ragBlocks` 这一格，不动 `messages` / `injectWidth` / `trimTag`：
     * 清回放表不该产生"历史没了"这种可见副作用。
     */
    @Synchronized
    fun clearAllRagBlocks(ctx: Context) {
        dir(ctx).listFiles { f -> f.name.startsWith("s_") && f.name.endsWith(".json") }
            ?.forEach { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    if (o.has("ragBlocks")) {
                        o.remove("ragBlocks")
                        f.writeText(o.toString())
                    }
                }
            }
    }

    /**
     * 这个会话的**裁剪起点标记**（`null` = 没有 / 作废了）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么它必须落盘（ISSUE #488 第五回合）
     * ═════════════════════════════════════════════════════════════════════
     * 标记本来是纯内存态，判据是"只在内存里、绑在当前会话 id 上"，理由是
     * "用户随时可能删聊天记录、切会话、重新加载模型" —— 那三条都靠**入口清**兜住。
     * 但有一类时刻**没有任何入口**：进程被系统杀掉 / 回到前台时 Activity 重建。
     *
     * 那时模型**还留在 native 里**（`unloadOnExit()` 的判据是 `isFinishing`，
     * 杀进程不走那条路，见 [EngineActivity] 那一处），KV 与"上一轮真喂进去的起点"
     * 都还在；而 `trimTagFrom` 已经回到 0。于是那一轮 `callerTrimmed == 0` ——
     * 主动治理重新按水位丢一遍，落点与上一轮真喂进去的起点对不上，KV 复用只剩
     * system 段。"每轮全量 prefill"换了个入口回来一次，而日志里每一行都合法。
     *
     * 落盘之后重建时能把它读回来，`trimTagOf` 的四条判据（会话 id / 每格容量 /
     * system 身份 / 起点那条消息的身份）会各自复核 —— 任何一条对不上就作废，
     * 所以"读回一个过期的标记"不会比"没有标记"更坏：最坏情况就是回到没有它的样子。
     *
     * ⚠ 存储形态是一条 JSON 串（`{for,from,posCap,sysKey,fromKey}`），**不用五个字段**：
     *   五个字段各写一遍，加载时任何一处缺失都是一个"半个标记"（`from` 有、`sysKey`
     *   没有 —— 于是判据里那条比较恒假、标记明明过期却被放行）。整体读出、整体作废。
     * ⚠ 加字段**不改文件名、不改版本号**，与 `cardSnapshot` / `injectWidth` 同一条口径：
     *   老会话里没有这一格，读到 `null` = 没有标记，与改动前逐字节相同。
     */
    data class TrimTag(
        val forId: String, val from: Int, val posCap: Int,
        val sysKey: String, val fromKey: String
    )

    @Synchronized
    fun trimTagOf(ctx: Context, id: String): TrimTag? {
        val raw = readObj(ctx, id)?.optString("trimTag", "") ?: ""
        if (raw.isEmpty()) return null
        // 整条读不出来就当没有（**不许**退化成"部分字段可用"）：半个标记比没标记更危险，
        // 它的 `from` 看着合法、复核却恒假 —— 于是过期标记被当成有效的一直用下去。
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        val from = o.optInt("from", 0)
        val posCap = o.optInt("posCap", -1)
        val forId = o.optString("for", "")
        val sysKey = o.optString("sysKey", "")
        val fromKey = o.optString("fromKey", "")
        if (from <= 0 || forId.isEmpty() || sysKey.isEmpty() || fromKey.isEmpty()) return null
        return TrimTag(forId, from, posCap, sysKey, fromKey)
    }

    @Synchronized
    fun setTrimTag(ctx: Context, id: String, tag: TrimTag): Boolean = mutate(ctx, id) {
        it.put("trimTag", org.json.JSONObject()
            .put("for", tag.forId).put("from", tag.from).put("posCap", tag.posCap)
            .put("sysKey", tag.sysKey).put("fromKey", tag.fromKey).toString())
    }

    @Synchronized
    fun clearTrimTag(ctx: Context, id: String): Boolean = mutate(ctx, id) { it.remove("trimTag") }

    @Synchronized
    fun load(ctx: Context, id: String): MutableList<Msg> {
        val arr = readObj(ctx, id)?.optJSONArray("messages") ?: return mutableListOf()
        val out = mutableListOf<Msg>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Msg(o.optString("role", "user"), o.optString("content", "")))
        }
        return out
    }

    @Synchronized
    fun appendUser(ctx: Context, id: String, content: String) = append(ctx, id, Msg("user", content))

    @Synchronized
    fun appendAssistant(ctx: Context, id: String, content: String) = append(ctx, id, Msg("assistant", content))

    /**
     * 删掉第 [index] 条消息（0 基，与 [load] 的顺序一致）。
     *
     * ══════════════════════════════════════════════════════════════════
     * 为什么不做"删 user 连带删它下面那条 assistant"
     * ══════════════════════════════════════════════════════════════════
     * 上一版设计里我建议连带删（"否则历史里留下一个答非所问的空对"），
     * 但用户的口径是"user 和 AI 的信息都可以任意删除，全靠用户做决定" ——
     * 连带删等于替用户做了一个它没要求的决定，而且**删错了不可恢复**。
     * 所以这里是纯粹的一条对一条，配对与否不在这里判。
     *
     * 越界（含 index 为负、会话消息已被别的路径改短）一律不改动并返回 false：
     * 静默不动是**安全**的失败方向（用户看到"没删掉"，而不是"删了别的一条"）。
     */
    @Synchronized
    fun deleteAt(ctx: Context, id: String, index: Int): Boolean {
        val msgs = load(ctx, id)
        if (index !in msgs.indices) return false
        msgs.removeAt(index)
        return writeMessages(ctx, id, msgs)
    }

    /**
     * 截断：只保留前 [keep] 条。「重新生成」用它把"这条之后"的历史丢掉再重跑
     * （`keep` = 那条的下标，长按末条时等于原样重跑）。
     *
     * `keep >= size` 返回 false = "没有变化"，而**不是**失败：末条就是重跑边界时
     * 走的正是这一支，调用方据此决定"要不要把这条 user 重新落盘"。
     */
    @Synchronized
    fun truncateTo(ctx: Context, id: String, keep: Int): Boolean {
        val msgs = load(ctx, id)
        if (keep <= 0) return writeMessages(ctx, id, mutableListOf())
        if (keep >= msgs.size) return false      // 没变化：不写盘、不刷新 updatedAt
        return writeMessages(ctx, id, msgs.subList(0, keep).toMutableList())
    }

    /** 把整份消息列表写回。**不**走 [append] 的裁剪保险丝 —— 见 [append] 的注释。 */
    private fun writeMessages(ctx: Context, id: String, msgs: MutableList<Msg>): Boolean =
        mutate(ctx, id) {
            val arr = JSONArray()
            for (m in msgs) arr.put(JSONObject().put("role", m.role).put("content", m.content))
            it.put("messages", arr).put("updatedAt", System.currentTimeMillis())
        }

    /** 清空当前会话消息（保留标题与 system）。 */
    @Synchronized
    fun clearMessages(ctx: Context, id: String): Boolean = mutate(ctx, id) {
        it.put("messages", JSONArray())
        it.remove("ragBlocks")
        it.put("updatedAt", System.currentTimeMillis())
    }

    /** 删除会话文件；若删的是当前会话则切到最近一个。 */
    @Synchronized
    fun delete(ctx: Context, id: String) {
        runCatching { fileOf(ctx, id)?.delete() }
        if (currentId(ctx) == id) setCurrent(ctx, list(ctx).firstOrNull()?.id ?: "")
    }

    /** 首条用户消息自动生成摘要标题（仅当标题仍是默认「会话 N」时）。 */
    @Synchronized
    fun maybeAutoTitle(ctx: Context, id: String, firstUser: String) {
        val t = titleOf(ctx, id)
        if (t.startsWith("会话")) {
            val short = firstUser.replace('\n', ' ').trim()
            // 截标题走 [TextBoundary.clipAtCharBoundary]（按代理对安全），**不**用裸
            // `take(20)`：首条消息第 20/21 个 code unit 恰好落在 emoji 中间时，
            // `take(20)` 会切出一个**孤立高代理**，进 JSON 再落盘 / 渲染时被替换成
            // `?` —— 用户看到的是"标题末尾有个怪符号"，与"这条消息打错字"同形。
            // 同一条纪律在 [ChatArchive]/[RagArchive] 的截断处已经立了，这里跟上。
            val clipped = if (short.length > 20) TextBoundary.clipAtCharBoundary(short, 20) + "…" else short
            mutate(ctx, id) { it.put("title", clipped.ifEmpty { t }) }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 导入 / 导出（与 [ChatArchive] 的分工）
    // ══════════════════════════════════════════════════════════════════════
    //
    // 分工只有一条：**文件布局的知识留在本对象里，格式的知识留在 ChatArchive 里**。
    // 导出/导入若在别处自己拼 `File(dir(ctx), "s_$id.json")`，等于把"会话文件名长什么样"
    // 复制了第二份 —— 改命名规则那天，会静默地"导出空记录"或"导入进不去"。
    //
    // 两处出口都返回**人话**：字节数与路径由调用方去拼提示文案，
    // 但"到底导出了几个会话"只有这里知道。

    /** 导出用的快照：把会话文件读成 [ChatArchive.Session]（不含裁剪，原样带走）。 */
    @Synchronized
    fun exportAll(ctx: Context): List<ChatArchive.Session> {
        val ids = list(ctx).map { it.id }
        return ids.mapNotNull { exportOne(ctx, it) }
    }

    /**
     * 导出单个会话。不存在时返回 null（而不是一个空壳会话）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 信封里的 `system` 写的是**实际生效的那一段**，不是手打的那一段
     * ══════════════════════════════════════════════════════════════════════
     * 上一版这里写的是 `o.optString("system")` —— 那只是用户**手打**的文本。
     * 会话一旦挂了角色卡，真正喂给模型的是 `cardSnapshot`（[RoleCard.effectiveSystem]），
     * 于是导出件里：手打那段在、**卡片那段不在**。后果是两条，都很难归因：
     *
     *   ① 导出是用户**唯一**能审计"到底喂了什么"的出口（聊天页只看得到气泡，
     *      `system` 那一条在 [EngineActivity.renderHistory] 里被跳过、也不进日志）；
     *      它把生效的人设漏掉，用户就得到"卡片内容根本没注入对话"这个**错误结论** ——
     *      而事实相反（prompt 里确实有），他只会去重挂卡、反复试，症状一直不变。
     *   ② 导出的语义是"把库里的记录原样带走"，记录里**不含**这次对话实际用的
     *      system 段，等于带走的是一份"少了一样东西"的记录。
     *
     * 所以这里过一道 [RoleCard.effectiveSystem]（**与 [EngineActivity.chatHistory] 同一个
     * 判据来源**，不在这里另写一份）：挂了卡就写快照，没挂才写手打的 —— 与喂模型
     * 的那一份逐字节相同。`cardId` / `cardSnapshot` 本身仍然**不进信封**（卡片库是
     * 本机的东西，一个指向不存在卡片的 id 比没有更坏，见 `ChatArchive.Session` 的注释）；
     * 进信封的是它们**合成出来的那一段文本**，而它本来就已经是信封的一部分（`system`）。
     */
    @Synchronized
    fun exportOne(ctx: Context, id: String): ChatArchive.Session? {
        val o = readObj(ctx, id) ?: return null
        return ChatArchive.Session(
            id = o.optString("id", id),
            title = o.optString("title", "未命名"),
            // 生效的 system = 快照优先（与 chatHistory 同一判据，不另写一份）。
            system = RoleCard.effectiveSystem(
                o.optString("cardSnapshot", ""), o.optString("system", "")),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            messages = load(ctx, id).toList(),
            // 角色卡（V1）：**这两个 id/快照字段不进信封**（`ChatArchive` 不认识它们）——
            // 卡片库是本机的东西，"把对话带走"不带卡。但卡片的**内容**已经在上面的
            // `system` 里带出去了（那正是"这一段设定确实生效了"的证据）。
            cardId = "",
            cardSnapshot = "",
        )
    }

    /**
     * 导入一批会话，返回 **实际写入的条数**。
     *
     * 三条口径：
     *
     * ① **每条导入都是新会话**（新 id、新标题后缀），不做"按 id 覆盖"。覆盖会
     *    静默毁掉本机已有的对话 —— 而用户导入的动机通常是"补一份回来"，
     *    不是"取代现有的"。合并方向永远是"只增不改"。
     *
     * ② **按字符预算收，收不下就停在那一份上并报告**，不是先写一半再说。
     *    能收几条由 [SessionQuota.acceptCount] 按**从前往后**算，停下的条数
     *    由调用方展示（"因空间不足，有 N 个未导入"）—— 静默截断会让用户
     *    以为文件就这么多。
     *
     * ③ **整个写入过程同步、逐条落盘**。中途失败（磁盘满）时已写入的会留下，
     *    返回的条数就是真实条数 —— 不假装"全有或全无"，因为回滚要走删文件，
     *    而删文件本身也会失败，那就变成第二层不确定。
     */
    @Synchronized
    fun importAll(ctx: Context, sessions: List<ChatArchive.Session>): Int {
        // 标题集合**只取一次**，并**把本次已定的名字也记进去**：
        // 一次导入里若有两个同名会话（很常见 —— 导出的文件里就有两个「会话 1」），
        // 不记进去的话它们会被定成同一个标题，而"两条一模一样的会话"
        // 让用户没法分清哪个是哪个。顺带把每轮一次 `list()` 的 O(n²) 收成一次。
        val titles = list(ctx).map { it.title }.toMutableSet()
        // 预算在**导入前**算一次：能收几条是"这批会话有多大"的函数，与写入顺序无关。
        val quota = SessionQuota.acceptCount(usedChars(ctx), sessions.map { charsOf(it) })
        var written = 0
        var firstNew: String? = null
        for (s in sessions) {
            if (written >= quota) break
            val title = ChatArchive.uniqueTitle(s.title, titles)
            val id = writeImported(ctx, s, title) ?: continue
            titles.add(title)
            written++
            if (firstNew == null) firstNew = id
        }
        // 本次导入的**第一条**成为当前会话：用户点完"导入"就该看到它，
        // 而不是停在原来的会话上以为"什么都没发生"。
        // 用 writeImported 返回的 id，不用 `list().first()` —— 后者依赖
        // "导入的 updatedAt 一定最大"这个巧合，而同秒内导入多条时排序不稳定。
        firstNew?.let { setCurrent(ctx, it) }
        return written
    }

    /**
     * 落一条导入的会话。
     *
     * **不复用 [create]**：那个函数按"会话 N"命名并写空 messages，还要走上限检查，
     * 之后还得再 [mutate] 一次把标题与消息覆盖上去 —— 中间那一拍如果被打断
     * （进程被杀），库里就留下一个"空的新会话"，而用户看不出它是失败留下的。
     * 这里**一次写完**：新 id + 全部字段在同一份 JSON 里。
     */
    private fun writeImported(ctx: Context, s: ChatArchive.Session, title: String): String? {
        val id = System.currentTimeMillis().toString(36) + ((Math.random() * 1e4).toInt().toString(36))
        val now = System.currentTimeMillis()
        // 导入的会话同样过**保险丝**（[MAX_MSGS]/[MAX_CHARS]），且用的是与
        // [append] 同一个 [trimToCap] —— 与 [append] 之间**不抄第二份**判断。
        // 不过这一道的话：一份外部导出、含 200 条消息 / 单条正文 10 万字符的
        // 会话会原样落盘，而 [chatHistory]/[preparedMessages] 对 load() 的结果
        // **没有任何二次裁剪** —— 于是"喂给模型的历史体量"这道闸在导入这条路上
        // 是敞开的（可能顶爆 ctx / 明显变慢），而界面上"已导入 N 个会话"看起来
        // 完全正常。裁剪**落盘前**做（而不是导入后再等下一次 [append] 顺手裁），
        // 与本仓库"落盘即契约"的口径一致：磁盘上的会话一定符合库的约定。
        val msgs = s.messages.map { JSONObject().put("role", it.role).put("content", it.content) }.toMutableList()
        trimToCap(ctx, id, msgs)
        val arr = JSONArray()
        msgs.forEach { arr.put(it) }
        val o = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("system", s.system)
            // createdAt 保留**原文件的**（记录是哪天聊的）；updatedAt 落成 now，
            // 否则"刚导入的会话"在按更新时间倒序的列表里会排到很后面，用户找不到。
            .put("createdAt", if (s.createdAt > 0) s.createdAt else now)
            .put("updatedAt", now)
            .put("messages", arr)
        return runCatching {
            fileOf(ctx, id)?.writeText(o.toString())
            id
        }.getOrNull()
    }

    /**
     * 旧版单会话 → 「会话 1」无损迁移；迁移后删除旧文件。
     *
     * 返回**可区分的结局**（见 [MigrateResult]）。此前的写法是
     * `createNoMigrate(ctx) ?: return` —— 会话数满了就**静默 return**，
     * 旧文件留在原地，用户看到的是「我的旧聊天记录不见了」。
     * 那是最安全的方向（不动比半迁移好），但**没有出声**，
     * 于是用户无法归因，只会以为数据丢了。
     *
     * 旧文件**不删**：留在原地，下次启动重试。删了才是真丢。
     */
    @Synchronized
    fun migrateIfNeeded(ctx: Context): MigrateResult {
        val legacy = File(ctx.filesDir, LEGACY)
        if (!legacy.exists()) return MigrateResult.NO_LEGACY
        val msgs = runCatching {
            val arr = JSONArray(legacy.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                JSONObject().put("role", o.optString("role", "user")).put("content", o.optString("content", ""))
            }
        }.getOrElse { emptyList() }
        // 先要一个空会话：拿不到说明撞上了会话数上限（**不是**写入失败）。
        val id = createNoMigrate(ctx) ?: return MigrateResult.BLOCKED_BY_LIMIT
        if (msgs.isNotEmpty()) {
            val ok = mutate(ctx, id) {
                val a = JSONArray()
                msgs.forEach { a.put(it) }
                it.put("messages", a)
                val firstUser = msgs.firstOrNull { m -> m.optString("role") == "user" }?.optString("content")
                if (firstUser != null) {
                    val s = firstUser.replace('\n', ' ').trim()
                    // 同 [maybeAutoTitle]：裸 `take(20)` 会切在代理对中间（见那里的说明）。
                    it.put("title", if (s.length > 20) TextBoundary.clipAtCharBoundary(s, 20) + "…" else "会话 1")
                }
            }
            // 正文没落盘就不要删旧文件：否则"迁移"变成了"删掉旧记录"。
            // mutate 已经建过一个空壳会话，用户会看到多一个空会话 —— 比丢记录可接受。
            if (!ok) return MigrateResult.FAILED
        }
        // 迁移完成后直接删除旧文件；renameTo 在部分 ROM 上会静默失败，导致下次启动重复迁移甚至循环
        runCatching { legacy.delete() }
        return MigrateResult.OK
    }

    /** 仅供 migrateIfNeeded 使用：不做任何迁移检查的裸建会话。 */
    private fun createNoMigrate(ctx: Context): String? {
        if (!SessionQuota.decide(usedChars(ctx), 0L).allowed) return null
        val id = System.currentTimeMillis().toString(36) + ((Math.random() * 1e4).toInt().toString(36))
        val now = System.currentTimeMillis()
        val obj = JSONObject()
            .put("id", id).put("title", "会话 ${'$'}{list(ctx).size + 1}")
            .put("system", "").put("createdAt", now).put("updatedAt", now)
            .put("messages", JSONArray())
        runCatching { fileOf(ctx, id)?.writeText(obj.toString()) }
        setCurrent(ctx, id)
        return id
    }

    private fun readObj(ctx: Context, id: String): JSONObject? {
        val f = fileOf(ctx, id) ?: return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun mutate(ctx: Context, id: String, block: (JSONObject) -> Unit): Boolean {
        val o = readObj(ctx, id) ?: return false
        block(o)
        return runCatching { fileOf(ctx, id)?.writeText(o.toString()); true }.getOrDefault(false)
    }

    /**
     * 追加一条并按保险丝裁剪。
     *
     * **只有这里做裁剪**：删除（[deleteAt]）与截断（[truncateTo]）走 [writeMessages]，
     * 不再裁剪。理由：那两个动作的整个目的就是「用户指哪一条就精确地动哪一条」，
     * 顺手再裁一次会让"我明明只删了中间一条，最早的几条怎么也少了"——
     * 用户看不出这是保险丝干的，只会以为删除功能坏了。
     * 而下一轮 [append] 本来就会把超限的部分裁掉，不差这一步。
     */
    private fun append(ctx: Context, id: String, msg: Msg) {
        val o = readObj(ctx, id) ?: return
        val arr = o.optJSONArray("messages") ?: JSONArray()
        arr.put(JSONObject().put("role", msg.role).put("content", msg.content))
        // 沿用旧版保险丝：最多 [MAX_MSGS] 条（= 20 轮）/ [MAX_CHARS] 字符，防撑爆 ctx
        val msgs = (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
        trimToCap(ctx, id, msgs)
        val kept = JSONArray()
        msgs.forEach { kept.put(it) }
        o.put("messages", kept).put("updatedAt", System.currentTimeMillis())
        runCatching { fileOf(ctx, id)?.writeText(o.toString()) }
    }

    /**
     * 保险丝的**唯一**落点（[MAX_MSGS] 条数 + [MAX_CHARS] 字符，防撑爆 ctx）。
     *
     * 为什么抽成一个函数，而不是让 [append] 与 [writeImported] 各写一遍：
     * 导入与追加是**两个入口**，却必须守**同一条**契约（"落盘的会话一定
     * ≤ [MAX_MSGS] 条、≤ [MAX_CHARS] 字符"）。各写一份时，改了一处另一处
     * 不红 —— 而分家的形态正是"导入这条路上保险丝是敞开的"（本轮修的 B1）。
     * 抽成一处之后，"往库里落一条会话"就必然过这里，没有第二条路。
     *
     * 末尾那条历史（`msgs.last()`）被 [capChars] **整体保留**——对导入而言
     * 它就是文件里的最后一条消息，与 [append] 里"用户这一轮说的话"是同一个位置。
     */
    private fun trimToCap(ctx: Context, id: String, msgs: MutableList<JSONObject>) {
        while (msgs.isNotEmpty() && msgs[0].optString("role") == "assistant") msgs.removeAt(0)
        while (msgs.size > MAX_MSGS) msgs.removeAt(0)
        capChars(ctx, id, msgs)
    }

    /**
     * 字符数上限（[MAX_CHARS]）的落实。
     *
     * ═══════════════════════════════════════════════════════════════════
     * 一条**固定的**消息永远排在最后
     * ═══════════════════════════════════════════════════════════════════
     * 上一版从**最新**的一条往前截，`substring(0, len - cut)` 保头 ——
     * 于是一次超限的裁剪**先把当轮提问的尾部啃掉**。用户的症状是
     * 「我这段代码贴进去它只看到一半，还答得头头是道」，与"模型抽风"完全同形。
     *
     * 现在的口径：**末条整体保留，裁剪从次新的往前做，谁超限谁被从尾部收窄。**
     * 末条就是用户这一轮真正要说的话（提问：代码/报错堆栈；或最新一轮的回答）。
     * 历史被从尾部收窄是**有意**的：模型早就读完了那几轮，而提问还没被读。
     *
     * "被截断的永远是历史，不是本轮"这条不变量由一个**固定下标**保证 ——
     * `removals` 记下条目被往左挪了几格，逐个下标取"当前条"时才不会把相邻的
     * 那条（已经改窄的）当成自己、把新露出来的"最早历史"又啃一遍。
     * 靠 `minOf(over, cur.length)` 顺手越过空条目是不行的：那会静默少截、
     * 把超限留给下一轮，而下一轮的末条已换人。
     *
     * 留痕按**两级分开**报（丢了几条历史 / 截了几条多少字符）——
     * 只说"已裁剪"时，用户分不清是保险丝动了（历史太老）还是这次贴得太多。
     */
    private fun capChars(ctx: Context, id: String, msgs: MutableList<JSONObject>) {
        fun total() = msgs.sumOf { it.optString("content").length }
        val before = total()
        val count0 = msgs.size
        // 一级：从最早的历史开始丢，丢到下限 [MIN_MSGS] 条为止。
        while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)
        val droppedMsgs = count0 - msgs.size
        if (total() <= MAX_CHARS) {
            if (droppedMsgs > 0) {
                Log.w(TAG, "会话 $id 超过 $MAX_CHARS 字符（$before -> ${total()}），已丢弃最早 $droppedMsgs 条历史")
            }
            return
        }
        // 二级：末条**整个不动**。从次新的往前收窄（保头丢尾），谁超限谁被截。
        val last = msgs.lastIndex
        var over = total() - MAX_CHARS
        var cutMsgs = 0
        var cutChars = 0
        val removals = IntArray(msgs.size)
        for (i in last - 1 downTo 0) {
            if (over <= 0) break
            val at = i - removals[i]                 // i 条被往左挪过几格
            if (at !in 0 until last) continue        // 已被丢掉的 i 条：跳过
            val cur = msgs[at].optString("content")  // 序号比 i 小的都还在 i 的左边，
            val cut = minOf(over, cur.length)        // 不会因裁剪而消失
            if (cut <= 0) continue
            msgs[at].put("content", cur.substring(0, cur.length - cut))
            removals[at]++
            cutMsgs++
            cutChars += cut
            over -= cut
        }
        Log.w(TAG, "会话 $id 超过 $MAX_CHARS 字符（$before -> ${total()}）：丢弃最早 $droppedMsgs 条历史，" +
            "截断 $cutMsgs 条历史共 $cutChars 字符；末条一字未动" +
            if (over > 0) "，末条自身仍超限（超出 $over 字符，已整体保留）" else "")
    }
}
