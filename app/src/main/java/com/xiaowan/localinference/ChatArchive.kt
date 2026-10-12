package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天记录的**导入导出格式**（纯逻辑，可在宿主上单测，不碰 Context / 文件系统）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么导出要「自带版本号 + 自带本机可判别的字段」
 * ══════════════════════════════════════════════════════════════════════════
 * 会话文件（`s_<id>.json`）是**内部格式**：它长什么样由 [SessionStore] 说了算，
 * 明天改字段名不该让昨天导出的文件变成"打不开"。所以导出走**独立的信封**：
 *
 *     { "format": "local-llm-chat", "version": 1, "exportedAt": <ms>,
 *       "sessions": [ { id, title, system, createdAt, updatedAt, messages:[{role,content}] } ] }
 *
 * 三条准则，每条都对应一类"静默出错"：
 *
 * ① **格式名与版本号必须对不上就拒绝**，不能"尽力解析"。
 *    `{"sessions": []}` 这种文件在"尽力解析"下会得到"导入 0 个会话"，而
 *    用户读成"我的记录是空的" —— 真相是他选错了文件（比如选了自己的 APK）。
 *    拒绝时的文案必须说清"这不是聊天记录文件"。
 *
 * ② **未知的 role 不能塞进历史**。历史是直接喂给模型的（[SessionStore.Msg] 的
 *    `role` 会被渲染进 chat 模板），一个 `role: "system"` 或某个自造角色混进来，
 *    模型会**把它当指令执行**，而用户只看到"导入后回答怪怪的"。所以只收
 *    `user` / `assistant`，其余**跳过并计数**（不是整份拒绝 —— 一份 500 条的
 *    记录里有一条脏数据就全丢，那是更坏的失败方向）。
 *
 *    但这条判据有**两个后果**，此前它们混在同一个 `skipped` 里，于是其中一个
 *    完全没有出口：
 *
 *    - **消息里的 `role: "system"` 被丢** —— 正确，它确实是脏数据；
 *    - **会话顶层的 `system` 字段被静态丢掉** —— 那不是脏数据，那是**人设**。
 *      用户导出一份"带人设的备份"，换台机器导回来，人设**静默没了**，
 *      而 `skipped` 只数"非 user/assistant 的 role 与超长正文"，一个字都不提。
 *      他的症状是"我的角色怎么不跟着走"，与"模型换了台机器就不听话"完全同形。
 *
 *    所以 [decode] 现在**如实读**会话顶层的 `system`（见 [Session.systemDropped]），
 *    并把它分出来单独计数（[Parsed.systemDropped]，与 [Parsed.skipped] 分开报）。
 *    ⚠ 本版**不**把导入的 `system` 写进会话：那会覆盖本机已有的 `system`，
 *      而 [SessionStore.setSystem] 从某一版起就是用户手打的那一段的地方。
 *      本轮的口径是"**不静默**"而不是"重新开始用别人的 system"——
 *      要改的是"用户不知道它丢了"，不是"它丢了"。
 *
 * ③ **条目数字段一律走 `optLong` 且缺失时给 0，不抛**。导出的文件可能来自
 *    手改（用户会拿记事本改标题再导回来），一个字段类型写错就让整份记录打不开
 *    是最不必要的严格。
 */
object ChatArchive {

    /** 信封的格式名。改它 = 换格式，与 version 一起构成"认得出来"的判据。 */
    const val FORMAT = "local-llm-chat"

    /** 当前格式版本。**只在读侧放宽、写侧恒为当前值**。 */
    const val VERSION = 1

    /**
     * 导入一次能吃的会话数上限。
     *
     * 与 [SessionQuota.MAX_TOTAL_CHARS] 不是同一个量：这个防的是"文件本身有问题"
     * （外部程序生成的巨大列表），那个防的是"库里正文总量太大"。超限**整份拒绝**
     * 而不是截断 —— 静默丢掉后半份记录是不可恢复的。
     */
    const val MAX_IMPORT_SESSIONS = 500

    /** 单条消息内容上限。超长截断并计数（防一份文件撑爆 UI 与后续 prompt）。 */
    const val MAX_CONTENT_CHARS = 100_000

    /** 导出/导入的会话（与 [SessionStore] 的落盘结构同形，但不依赖它）。 */
    data class Session(
        val id: String,
        val title: String,
        val system: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<SessionStore.Msg>,
        /**
         * 角色卡（V1）：这个会话挂的是**哪一张**卡。空串 = 没用卡片。
         *
         * ⚠ 它与 [cardSnapshot] **不进信封**（[encode] 不写、[decode] 不读）——
         *   它们是**本机**的东西，导出的语义是"把对话带走"。理由有两条：
         *   ① 卡片库本身没有导出入口（V1 不做），单独把一个 `cardId` 带出去，
         *      收方解析出来是一个指向**不存在的卡片**的 id（比没有更坏）；
         *   ② 真要看人设，`system` 那一段已经在信封里，而且 [`systemDropped`]
         *      这条出口现在会说清"它没跟着过来"。
         *   两个字段之所以**存在**于这个 data class，只因为它是"一个会话"的
         *   通用形状，而 `SessionStore.exportOne` 用它当返回类型。
         */
        val cardId: String = "",
        /** 角色卡（V1）：这个会话挂卡那一刻的**内容快照**（空串 = 没用卡片）。 */
        val cardSnapshot: String = "",
        /**
         * 这一份的 `system` **没能落进本机**时，它原本是什么（没丢时为 `null`）。
         *
         * 为什么要把丢掉的**内容**带出来而不是只带一个布尔：用户要判断的是
         * "我丢的是不是重要的那一段"。只说"丢了 N 段人设"，他只能二选一 ——
         * 要么无视（可能真的重要），要么整份重来（白干）。把内容带出来，
         * 他至少能看见那是不是他写的那一段（对话框里按会话逐条列）。
         *
         * ⚠ 它**不是**导入格式的一部分（[encode] 不写它，[decode] 不读它）：
         * 一个只在"导入"这条路上有意义的字段，写进信封就是给下一个版本挖坑。
         */
        val systemDropped: String? = null,
    )

    /**
     * 解析结果。
     *
     * `error == null` 才算成功；`skipped` 是被**有意丢掉**的脏数据条数
     * （非 user/assistant 的 role、以及被截断的超长正文）——
     * 它必须报给用户，否则"导入后少了几条"看起来像 bug。
     */
    data class Parsed(
        val sessions: List<Session>,
        val skipped: Int,
        val error: String?,
        /**
         * 被静态丢掉的**人设**条数（会话顶层 `system` 非空，而本版不落它）。
         *
         * 与 [skipped] **分开**报：`skipped` 是"文件里的脏数据"，处置是
         * "不用管"；这里是"文件里的好东西本机用不上"，处置是
         * "记下来、告诉用户"。把两者混在一个数里，用户就没法判断该不该在意。
         */
        val systemDropped: Int = 0,
    ) {
        val ok: Boolean get() = error == null
    }

    // ────────────────────────────────────────────────────────────────────
    // 写：序列化
    // ────────────────────────────────────────────────────────────────────

    /**
     * 把若干会话编成信封 JSON 字符串。
     *
     * 排序由调用方定（[SessionStore.list] 已按 updatedAt 倒序）；这里**保持入参顺序**，
     * 因为"导出全部"想要的就是会话列表里那个顺序。
     */
    fun encode(sessions: List<Session>, exportedAt: Long): String {
        val arr = JSONArray()
        for (s in sessions) {
            val msgs = JSONArray()
            for (m in s.messages) {
                // 导出**不做裁剪**：导出的目的就是"把库里的东西原样带走"。
                // 若在这里套 MAX_CHARS 保险丝，用户会拿到一份"看起来完整、
                // 实际被截掉开头"的记录，而无从判断是导出坏了还是会话本来就短。
                msgs.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("system", s.system)
                    .put("createdAt", s.createdAt)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", msgs)
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("sessions", arr)
            .toString()
    }

    // ────────────────────────────────────────────────────────────────────
    // 读：解析 + 校验
    // ────────────────────────────────────────────────────────────────────

    /**
     * 解析一份导出文件。
     *
     * 判据顺序刻意从"最外层"读到"最内层"：格式名 → 版本 → sessions 数组 →
     * 逐会话 → 逐消息。任何一层不合法都返回**具体**的 error 文案 ——
     * "导入失败"四个字会让用户以为是自己操作错了，而真相可能是选错了文件。
     */
    fun decode(text: String): Parsed {
        val root = runCatching { JSONObject(text) }.getOrElse {
            return Parsed(emptyList(), 0, "不是合法的 JSON 文件")
        }
        val fmt = root.optString("format", "")
        if (fmt != FORMAT) {
            // 这一条是本文件存在的理由：`{"sessions":[]}` 之类的文件若"尽力解析"，
            // 会得到"导入 0 个会话"，用户读成"我的记录是空的"。
            return Parsed(emptyList(), 0,
                "这不是本应用的聊天记录文件（format = 「${fmt.ifEmpty { "缺" }}」，应为「$FORMAT」）")
        }
        val ver = root.optInt("version", -1)
        if (ver <= 0 || ver > VERSION) {
            // 高于当前版本：可能是新版本 App 导出的。**不猜**——
            // 按已知字段尽力解析会静默丢掉新字段，用户拿到的是一份"少了几样东西"的记录。
            return Parsed(emptyList(), 0, "记录文件版本 $ver 不被当前版本支持（支持 1..$VERSION）")
        }
        val sessArr = root.optJSONArray("sessions")
            ?: return Parsed(emptyList(), 0, "记录文件里没有 sessions 列表")
        if (sessArr.length() > MAX_IMPORT_SESSIONS) {
            return Parsed(emptyList(), 0,
                "记录文件里有 ${sessArr.length()} 个会话，超过单次上限 $MAX_IMPORT_SESSIONS（未导入任何内容）")
        }
        var skipped = 0
        var systemDropped = 0
        val out = mutableListOf<Session>()
        for (i in 0 until sessArr.length()) {
            val o = sessArr.optJSONObject(i) ?: continue
            val msgs = mutableListOf<SessionStore.Msg>()
            val msgArr = o.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until msgArr.length()) {
                val mo = msgArr.optJSONObject(j) ?: continue
                val role = mo.optString("role", "")
                // ②：只收 user / assistant。别的 role 会被模型当指令执行，
                // 而症状只是"回答怪怪的"—— 计数报给用户，不是静默带上。
                if (role != "user" && role != "assistant") { skipped++; continue }
                var content = mo.optString("content", "")
                if (content.length > MAX_CONTENT_CHARS) {
                    // ⚠ 按**字符边界**截：裸 `substring` 切在代理对中间会留下一个
                    //    孤立代理，它落进磁盘时被替换成 `?`（不可逆），而内存里
                    //    与 `==` 比较"看着是对的"。详见 [TextBoundary.clipAtCharBoundary]。
                    content = TextBoundary.clipAtCharBoundary(content, MAX_CONTENT_CHARS)
                    skipped++
                }
                msgs.add(SessionStore.Msg(role, content))
            }
            // ⚠ `system` 仍然**要读**，但读的目的是"知道它丢了什么"，不是"收下它"。
            // 上一版这里也是 `optString("system", "")`，看起来一模一样 —— 差别在
            // 它把结果塞进 Session 之后没人再看：`systemDropped` 就是那个"有人看"。
            // 判据：只要它非空，就必须同时进 `systemDropped` 计数与那一条的字段
            //（两处都写是刻意的：计数给"一共丢了几段"的总体结论，字段给"丢的是哪一段"
            // 的逐条说明 —— 只有计数，用户点不出是哪条会话）。
            val sys = o.optString("system", "")
            if (sys.isNotEmpty()) systemDropped++
            out.add(
                Session(
                    id = o.optString("id", ""),
                    title = o.optString("title", "导入的会话"),
                    system = sys,
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    messages = msgs,
                    // 读取侧显式写空：这两个字段**不是**信封的一部分（见 Session 的注释）。
                    cardId = "",
                    cardSnapshot = "",
                    systemDropped = sys.ifEmpty { null },
                )
            )
        }
        if (out.isEmpty()) return Parsed(emptyList(), skipped, "记录文件里没有任何会话", systemDropped)
        return Parsed(out, skipped, null, systemDropped)
    }

    // ────────────────────────────────────────────────────────────────────
    // 合并：导入时的去重与命名
    // ────────────────────────────────────────────────────────────────────

    /**
     * 给导入的会话挑一个**不与现有库冲突**的标题。
     *
     * 为什么不用会话 id 去重：id 是本机生成的（`时间戳36进制+随机`），跨设备
     * 导入时一定不冲突，于是"同一份文件导两次"会得到两份同标题记录 ——
     * 用户看不出哪份是哪份，而他重复导入的真实意图往往是"上次那批没进来"。
     * 所以重名的判据是**标题**：`标题` 已存在 → `标题 (2)` → `(3)` …
     */
    fun uniqueTitle(base: String, existing: Collection<String>): String {
        val t = base.trim().ifEmpty { "导入的会话" }
        if (!existing.contains(t)) return t
        var n = 2
        while (existing.contains("$t ($n)")) n++
        return "$t ($n)"
    }
}
