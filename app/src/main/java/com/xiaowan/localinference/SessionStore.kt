package com.xiaowan.localinference

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多会话管理。存储布局（均在 ctx.filesDir）：
 * - sessions/s_<id>.json —— 每会话一个文件 {id,title,system,createdAt,updatedAt,messages:[{role,content}]}
 * - sessions_current.txt —— 当前会话 id
 * - chat_session.json     —— 旧版单会话文件，首次访问时自动迁移为「会话 1」
 * UI 入口在 EngineActivity 聊天页；生成中禁止切换/删除由调用方保证。
 */
object SessionStore {

    const val MAX_SESSIONS = 50   // 上限，超出提示先删旧的

    /** 发给模型的历史字符数上限，防撑爆 ctx。裁剪留痕见 [capChars]。 */
    const val MAX_CHARS = 24000

    /** 裁剪时**保底保留**的消息条数（至少留住最近一轮问答）。 */
    const val MIN_MSGS = 2

    private const val TAG = "SessionStore"
    private const val DIR = "sessions"
    private const val CUR = "sessions_current.txt"
    private const val LEGACY = "chat_session.json"

    data class Msg(val role: String, val content: String)

    data class Info(
        val id: String, val title: String, val system: String,
        val createdAt: Long, val updatedAt: Long, val turns: Int
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun fileOf(ctx: Context, id: String): File? =
        if (id.isEmpty()) null else File(dir(ctx), "s_$id.json")

    @Synchronized
    fun currentId(ctx: Context): String? {
        migrateIfNeeded(ctx)
        val f = File(ctx.filesDir, CUR)
        val id = runCatching { f.readText().trim() }.getOrNull()
        if (!id.isNullOrEmpty() && fileOf(ctx, id)?.exists() == true) return id
        return list(ctx).firstOrNull()?.id?.also { setCurrent(ctx, it) }
    }

    private fun setCurrent(ctx: Context, id: String) {
        runCatching { File(ctx.filesDir, CUR).writeText(id) }
    }

    /** 新建会话；达到上限返回 null。标题默认「会话 N」。 */
    @Synchronized
    fun create(ctx: Context): String? {
        // fix: create 不再调用 migrateIfNeeded（旧版 renameTo 失败会导致无限递归 StackOverflow）
        if (list(ctx).size >= MAX_SESSIONS) return null
        val id = System.currentTimeMillis().toString(36) + ((Math.random() * 1e4).toInt().toString(36))
        val now = System.currentTimeMillis()
        val obj = JSONObject()
            .put("id", id).put("title", "会话 ${list(ctx).size + 1}")
            .put("system", "").put("createdAt", now).put("updatedAt", now)
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

    /** 清空当前会话消息（保留标题与 system）。 */
    @Synchronized
    fun clearMessages(ctx: Context, id: String): Boolean = mutate(ctx, id) {
        it.put("messages", JSONArray()).put("updatedAt", System.currentTimeMillis())
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
            mutate(ctx, id) { it.put("title", if (short.length > 20) short.take(20) + "…" else short.ifEmpty { t }) }
        }
    }

    /** 旧版单会话 → 「会话 1」无损迁移；迁移后旧文件改名备份。 */
    private fun migrateIfNeeded(ctx: Context) {
        val legacy = File(ctx.filesDir, LEGACY)
        if (!legacy.exists()) return
        val msgs = runCatching {
            val arr = JSONArray(legacy.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                JSONObject().put("role", o.optString("role", "user")).put("content", o.optString("content", ""))
            }
        }.getOrElse { emptyList() }
        val id = createNoMigrate(ctx) ?: return
        if (msgs.isNotEmpty()) {
            mutate(ctx, id) {
                val a = JSONArray()
                msgs.forEach { a.put(it) }
                it.put("messages", a)
                val firstUser = msgs.firstOrNull { m -> m.optString("role") == "user" }?.optString("content")
                if (firstUser != null) {
                    val s = firstUser.replace('\n', ' ').trim()
                    it.put("title", if (s.length > 20) s.take(20) + "…" else "会话 1")
                }
            }
        }
        // 迁移完成后直接删除旧文件；renameTo 在部分 ROM 上会静默失败，导致下次启动重复迁移甚至循环
        runCatching { legacy.delete() }
    }

    /** 仅供 migrateIfNeeded 使用：不做任何迁移检查的裸建会话。 */
    private fun createNoMigrate(ctx: Context): String? {
        if (list(ctx).size >= MAX_SESSIONS) return null
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

    private fun append(ctx: Context, id: String, msg: Msg) {
        val o = readObj(ctx, id) ?: return
        val arr = o.optJSONArray("messages") ?: JSONArray()
        arr.put(JSONObject().put("role", msg.role).put("content", msg.content))
        // 沿用旧版保险丝：最多 20 轮 / 24k 字符，防撑爆 ctx
        var msgs = (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
        while (msgs.isNotEmpty() && msgs[0].optString("role") == "assistant") msgs.removeAt(0)
        while (msgs.size > 40) msgs.removeAt(0)
        capChars(ctx, id, msgs)
        val kept = JSONArray()
        msgs.forEach { kept.put(it) }
        o.put("messages", kept).put("updatedAt", System.currentTimeMillis())
        runCatching { fileOf(ctx, id)?.writeText(o.toString()) }
    }

    /**
     * 字符数上限（[MAX_CHARS]）的落实。
     *
     * 旧写法把两件事塞进同一个 while 条件：
     * `while (sum > MAX_CHARS && msgs.size > 2) msgs.removeAt(0)`
     * 而 `size > 2` 是**先决条件**不是保底 —— 消息数正好为 2 时整条循环短路，
     * 「保险丝」在**最需要它**的场景（一轮问答各上万字，例如贴整段代码让它改）
     * 恰好完全失效：注释声称最多 24k 字符，实况可以远超，且没有任何提示。
     *
     * 现在拆成两级，且**两级都做**（只丢历史不够：「两条各 12k+12k」时条数已是保底，
     * 而每条单独看都不超限，总长仍会突破上限）：
     * 1. 先从最早的历史开始丢，丢到下限 [MIN_MSGS] 条为止；
     * 2. 若仍超限，从**最新**的一条往前逐条截断（保留每条开头 —— 提问/结论在开头），
     *    直到总长落回上限内。
     *
     * 留痕很重要 —— 否则症状是 native 侧静默截断 prompt 前半段，表现为「答非所问」。
     */
    private fun capChars(ctx: Context, id: String, msgs: MutableList<JSONObject>) {
        fun total() = msgs.sumOf { it.optString("content").length }
        val before = total()
        while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)
        if (total() <= MAX_CHARS) return
        // 从最新往前截，把超出的部分摊到各条上（先动最新的，越老的历史越完整）。
        var over = total() - MAX_CHARS
        for (i in msgs.indices.reversed()) {
            if (over <= 0) break
            val cur = msgs[i].optString("content")
            val cut = minOf(over, cur.length)
            if (cut > 0) {
                msgs[i].put("content", cur.substring(0, cur.length - cut))
                over -= cut
            }
        }
        Log.w(TAG, "会话 $id 超过 $MAX_CHARS 字符（$before -> ${total()}），已裁剪最早历史并截断末条")
    }
}
