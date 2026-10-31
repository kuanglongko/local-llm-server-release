package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 请求正文的**摘要**（ISSUE #452「明文落日志」那个诉求的落点）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是摘要而不是明文
 * ══════════════════════════════════════════════════════════════════════════
 * 客户端每轮把**整份历史**重新送一遍。真正要回答的问题是「两轮喂进去的是不是
 * 同一份对话」—— 这件事只需要**逐条 + 逐字节可比**，不需要看见内容：
 *
 *   · 每条给一个短指纹（同内容必然同指纹，改一个字必然不同）；
 *   · 指纹按消息顺序连起来再取一次 —— 那一枚就是「这一整份对话」的身份。
 *
 * 两轮的 `msgs` 相同、`head` 却不同，就说明**变的是我们自己拼的那一段**
 * （system / 模板 / 知识库注入块），跟客户端没关系。这正是 `fork_why` 那句
 * 「两轮喂的不是同一份对话」一直答不出的那一半。
 *
 * 而落明文会带来三件事，一件都不划算：
 *   · 日志文件会自动轮转保留 [LogFileStore.KEEP] 份，**明文会话留在设备上**，
 *     比临时 commit 更难撤（改完代码回退，盘上的 txt 还在）；
 *   · 日志页/导出/反馈默认就在给人看，谁贴一份日志就把整段对话贴出去了；
 *   · 我们排查「复用为什么断」根本不需要正文（断点是 token 位置，不是语义）。
 *
 * 所以这一个开关**只加可观测性，不加泄露面**，长期留着也不会出事 ——
 * 无需「排查完再回退 commit」。
 *
 * 格式（`[body]` 前缀，与 HttpApi 里那条请求日志同一族）：
 * ```
 * [body] chat msgs=4 head=3f2a1b0c9d 逐条:
 *   0 system  len=1821 sha=8c1d… head="你是…"(可读时)
 *   1 user    len=1130 sha=4e07…
 * ```
 * 正文只在**明确打开** `debugLogBodies` 时才出现，且截断到 [EXCERPT] 字符。
 */
object RequestBodyDigest {

    /** 开关关掉后 `[body]` 一行都不打（协议字段/预算那几行 `[http]` 保持原样）。 */
    @Volatile var enabled: Boolean = true

    /**
     * 是否连正文片段一起打（**默认关**）。
     *
     * 摘要已经够回答「是不是同一份对话」；这一档只在你需要看「变了的那一条
     * 到底长什么样」时打开，属于明文输出，所以默认必须是关。
     */
    @Volatile var debugLogBodies: Boolean = false

    /** 正文片段最多打几个字符（**字符，不是字节**；开 UTF-8 写盘后两者不再一致）。 */
    const val EXCERPT = 200

    /** 指纹取前几位的十六进制字符。太短会撞、太长刷屏。 */
    private const val FP = 10

    private fun sha(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(FP)
        for (k in 0 until d.size) {
            if (sb.length >= FP) break
            sb.append(Character.forDigit((d[k].toInt() shr 4) and 0xF, 16))
            if (sb.length >= FP) break
            sb.append(Character.forDigit(d[k].toInt() and 0xF, 16))
        }
        return sb.toString()
    }

    /** 一条消息的片段：**不换行**、不做转义猜测，只把控制字符换成可见点。 */
    fun excerpt(s: String): String {
        if (s.isEmpty()) return ""
        val n = minOf(s.length, EXCERPT)
        val sb = StringBuilder(n + 3)
        for (k in 0 until n) {
            val c = s[k]
            sb.append(if (c == '\n') '⏎' else if (c < ' ') '.' else c)
        }
        if (s.length > EXCERPT) sb.append('…')
        return sb.toString()
    }

    /**
     * 整份对话的日志行。
     *
     * @param kind `chat` / `cmpl` —— 两条端点各打自己的，便于在日志里分开数。
     * @param msgs 已经解析完、即将进预算的那一份（顺序即喂进 prefill 的顺序）。
     */
    fun describe(kind: String, msgs: List<Pair<String, String>>): String {
        if (!enabled) return ""
        val ids = StringBuilder()
        for ((_, c) in msgs) ids.append(sha(c))
        val head = if (msgs.isEmpty()) sha("") else sha(ids.toString())
        val sb = StringBuilder()
        sb.append("$kind msgs=${msgs.size} head=$head 逐条:")
        val withBody = debugLogBodies
        for (i in msgs.indices) {
            val role = msgs[i].first
            val c = msgs[i].second
            sb.append("\n  ").append(i).append(' ').append(role)
                .append(" len=").append(c.length)
                .append(" sha=").append(sha(c))
            if (withBody) sb.append(" head=\"").append(excerpt(c)).append('"')
        }
        return sb.toString()
    }

    /**
     * 原生侧的 `messages` 数组 -> 两条端点共用的形状。
     *
     * 与 `HttpApi.handleChat` 的解析**同一条判据**（`role` 缺省 `user`、
     * `content` 数组只取 text 段），但**不复制**那份逻辑：这里只服务日志，
     * 拿不到就按空串记 —— 摘要宁可少一格，也不该在日志里改变请求的解析结果。
     */
    fun pairsOf(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            var c = m.optString("content")
            val ca = m.optJSONArray("content")
            if (ca != null) c = ContentParts.textOf(ca)
            out.add(role to c)
        }
        return out
    }

    /** `/v1/completions` 的裸 prompt：当成**一条** user 记，与预算侧同一口径。 */
    fun pairsOfPrompt(prompt: String): List<Pair<String, String>> = listOf("user" to prompt)
}
