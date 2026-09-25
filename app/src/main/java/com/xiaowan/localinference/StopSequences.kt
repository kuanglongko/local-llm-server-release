package com.xiaowan.localinference

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI `stop` / `stop_sequences` 的**纯逻辑**部分：请求侧解析 + 非流式兜底截断。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么不能只靠「生成完再 indexOf 截一刀」
 * ══════════════════════════════════════════════════════════════════════════
 * 那种做法在 `stream=true` 下是**协议级错误**：stop 之后的内容已经在增量块里发给了
 * 客户端，收不回来。所以真正的拦截必须在生成循环里逐个 token 判（native 侧
 * `local-stop` 采样器，见 llama_jni.cpp）。本文件的 [truncate] 只是**非流式的兜底**：
 * 万一 native 侧因为某条路径没挂上采样器而漏拦，至少在响应体里把尾巴切掉。
 *
 * 两处判定的差异，必须写清楚免得后人当成"重复实现"删掉一处：
 *   · native 侧按 token 文本匹配，能处理"stop 字符串跨 token"以及"词表里根本没有
 *     未出现的 stop 字符串、只能暂扣"的情况；
 *   · 这里按整段字符串匹配，简单、可离线单测，但**拿不到未下发的暂扣部分**，
 *     所以它只能修"多吐了"这类错误，修不了"少吐了"。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 什么算"非法"（400），什么只是"性能边界"（截断告警，不失败请求）
 * ══════════════════════════════════════════════════════════════════════════
 * 只对**语义确实错**的取值报 400：空串（`stop: [""]` 会让每个位置都命中，表现为输出
 * 恒为空，极易被误判成"模型坏了"）、非字符串元素、类型不是字符串/数组。
 * 这些无论谁来用都是错的，报错能帮调用方立刻定位。
 *
 * 而"条数多""单条太长"是**性能边界**，不是协议错误：真实客户端完全可能写很多条
 * （`stop` 与 `stop_sequences` 各塞一批、并集十几条是很常见的兼容写法）。
 * 曾把条数上限设成 8 并在超出时直接 400，结果调用方**每个请求**都被拒、客户端完全
 * 没法用 —— 与"补齐协议字段让客户端更好用"的目标正好相反。现在改为：条数超上限只
 * **截断 + 落日志**（见 [capped] / [truncatedNotice]），绝不失败整个请求。
 */
object StopSequences {

    /**
     * 逐 token 匹配时**参与**的 stop 条数上限。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这里是"截断告警"而不是"超出报 400"
     * ══════════════════════════════════════════════════════════════════════════
     * 曾把上限写成 8 并在超出时直接 `400`。后果是一次真实的客户端事故：调用方
     * （兼容 `stop` / `stop_sequences` 两个别名，两边各写一批、并集 9 条）**每个
     * 请求**都被拒，客户端表现为"完全无法生成"；而这个业务上完全正常。
     * 更糟的是它与本特性引入前的行为**反向**：那时 `stop` 被静默忽略、请求照跑。
     * 也就是说"补齐一个协议字段"反而把一个能用的客户端弄挂了。
     *
     * 结论：条数是**性能边界**，不是协议合法性边界。OpenAI 自己也不按条数拒绝请求
     * （文档写"最多 4 条"，但服务端不会因此返回 4xx）。因此这里只保留一个**宽松的**
     * 内部上限用于兜住匹配开销，超出部分**截断 + 落日志**，绝不因此失败整个请求。
     *
     * 64 条远高于任何真实客户端（含把 `stop` / `stop_sequences` 都塞满的兼容写法），
     * 因此实际不会有人被截断；真被截断也只是"少认几条停止串"，不会让生成失败。
     */
    const val MAX_SEQUENCES = 64

    /** 单条 stop 长度上限。再长的"停止串"几乎不可能命中，只会拖慢逐 token 匹配。 */
    const val MAX_LEN = 256

    const val errType = "stop 必须是字符串或字符串数组"

    /** 因超过 [MAX_SEQUENCES] 被截断时的日志文案（不参与 400，仅提示）。 */
    fun truncatedNotice(actual: Int) =
        "stop 去重后 $actual 条，超过内部上限 $MAX_SEQUENCES 条，已只保留前 $MAX_SEQUENCES 条（不影响生成）"

    const val errEmpty = "stop 不能包含空串（空串会匹配每个位置，等于输出恒为空）"
    const val errTooLong = "stop 单条长度不能超过 $MAX_LEN"
    const val errNotString = "stop 数组元素必须都是字符串"

    /**
     * 解析请求里的 stop 序列。同时认 OpenAI 的 `stop` 与 `stop_sequences` 别名。
     *
     * 返回 (序列, 错误) 二选一：序列为 null 表示非法，错误信息可直接回给客户端。
     * 一条都没有时返回空列表（不是 null）—— 「没给」不是错误。
     *
     * 两个字段都给了时**取并集**而不是二选一：旧版 HuggingFace TGI 用 `stop`，
     * vLLM/Anthropic 风格用 `stop_sequences`，客户端可能两个都塞，取并集才不会静默丢一半。
     */
    fun fromRequest(j: JSONObject): Pair<List<String>?, String?> {
        val out = ArrayList<String>()
        for (key in listOf("stop", "stop_sequences")) {
            if (!j.has(key) || j.isNull(key)) continue
            when (val v = j.opt(key)) {
                is String -> {
                    // OpenAI 允许 `stop: "\n"` 这种单串形式。空串同样判非法（见文件头）。
                    val e = add(out, v) ?: continue
                    return null to e
                }
                is JSONArray -> {
                    for (i in 0 until v.length()) {
                        val e = v.opt(i)
                        if (e !is String) return null to errNotString
                        val err = add(out, e) ?: continue
                        return null to err
                    }
                }
                // 数字 / 对象 / 布尔 —— 明确报错，不静默当成"没给"
                else -> return null to errType
            }
        }
        // 上限只在**去重之后**判一次（并集里"重复"不是超额）。超出**不报错**，
        // 只截断：条数是性能边界不是合法性边界，见 [MAX_SEQUENCES] 的说明。
        return out to null
    }

    /**
     * 追加一条并做长度/空串校验；返回错误信息，null = 通过。
     *
     * **重复项按去重处理**（不报错、也不再计入条数）：客户端为了兼容
     * `stop` / `stop_sequences` 两个别名而把同一组串写两遍是常见做法，那时"并集"
     * 只是把同样的内容数了两遍。去重是语义等价的（同一条 stop 出现两次与一次
     * 没有区别），因此在这里做掉。注意这里**不再**因条数超限返回错误 ——
     * 超限由 [capped] 截断，不失败请求（见 [MAX_SEQUENCES]）。
     */
    private fun add(out: MutableList<String>, s: String): String? {
        if (s.isEmpty()) return errEmpty
        if (s.length > MAX_LEN) return errTooLong
        if (out.contains(s)) return null          // 已在集合里：语义等价，忽略
        out.add(s)
        return null
    }

    /**
     * 把解析结果收窄到 [MAX_SEQUENCES] 条（超出部分丢弃，顺序保持）。
     *
     * 与 **400 的区别**：这里丢的是"最不重要的那几条"，请求照常生成；400 会让整个
     * 请求失败（就是那个把客户端弄挂的行为）。调用方应把 [truncatedNotice] 落日志，
     * 让"少认了几条 stop"可见，而不是静默。
     */
    fun capped(stops: List<String>): List<String> =
        if (stops.size <= MAX_SEQUENCES) stops else stops.subList(0, MAX_SEQUENCES)

    /**
     * 非流式兜底截断：返回 [0, 首个命中的 stop 起点) 的子串。
     *
     * 命中语义与 OpenAI 一致：**stop 串本身不出现在返回文本里**。
     * 多条同时能命中时取**最靠前**的那条（等价于"谁先出现谁生效"），
     * 而不是按数组顺序 —— 后者会让 `stop: ["很好", "好"]` 在文本 "好..." 上
     * 取决于调用方写数组的顺序，属于不可预期的行为。
     */
    fun truncate(text: String, stops: List<String>): String {
        if (text.isEmpty() || stops.isEmpty()) return text
        var best = -1
        for (s in stops) {
            if (s.isEmpty()) continue
            val i = text.indexOf(s)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return if (best < 0) text else text.substring(0, best)
    }

    /** 落日志用的一行摘要（stop 串本身可能含换行，必须转义，否则日志会串行）。 */
    fun describe(stops: List<String>): String =
        if (stops.isEmpty()) "stop=[]" else "stop=[${stops.joinToString(",") { it.escaped() }}]"

    private fun String.escaped(): String =
        "\"" + replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\""
}
