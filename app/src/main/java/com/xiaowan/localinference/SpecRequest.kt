package com.xiaowan.localinference

import org.json.JSONObject

/**
 * 请求级投机解码开关（`speculative` 字段）。
 *
 * 设计边界（Phase 1，与 native 的能力面严格对齐）：
 *  - **加载期**决定实现类型（MTP / 外部 draft）与草稿模型；请求期**不允许**切换。
 *    请求里出现 `type` / `draft_model` / `draftModel` 一律 400 —— 那需要重建 ctx，
 *    而请求期只有一次 JNI 调用，做不到也不该假装做到。
 *  - 请求期允许两件事：关掉本轮投机（`false` 或 `{"enabled": false}`），
 *    以及把本轮草稿上限压小（`{"n_max": 2}`，不能超过加载期 `n_max`）。
 *  - 字段缺省 = 使用加载期配置（开着就开）；**不写**任何 native 状态，
 *    于是 App 内聊天那条不经过 HTTP 的路径不受影响。
 *
 * 支持的形状：
 *   "speculative": true | false
 *   "speculative": {"enabled": true, "n_max": 2}
 */
object SpecRequest {
    const val MAX_N_MAX = 16

    data class Parsed(
        val enabled: Boolean,
        val nMax: Int,                 // 0 = 用加载期配置
        val notices: List<String> = emptyList(),
    )

    /**
     * @return null + err 表示协议级非法（调用方回 400）；缺省字段返回 [Parsed] 默认值。
     */
    fun fromRequest(j: JSONObject): Pair<Parsed?, String?> {
        if (!j.has("speculative")) return Parsed(enabled = true, nMax = 0) to null
        return when (val v = j.opt("speculative")) {
            is Boolean -> Parsed(enabled = v, nMax = 0) to null
            is JSONObject -> fromObject(v)
            else -> null to "speculative 必须是布尔值或对象"
        }
    }

    private fun fromObject(o: JSONObject): Pair<Parsed?, String?> {
        // 加载期决策字段：请求期出现就是误用，明确拒绝而不是静默忽略
        // （静默忽略会让客户端以为"这轮换了 draft 模型"，实际没换）。
        for (k in listOf("type", "draft_model", "draftModel", "draft_path", "draftPath")) {
            if (o.has(k)) {
                return null to "speculative.$k 只能在加载前配置（请求期不允许切换投机类型/草稿模型）"
            }
        }
        val enabled = when (val e = o.opt("enabled")) {
            null, JSONObject.NULL -> true
            is Boolean -> e
            else -> return null to "speculative.enabled 必须是布尔值"
        }
        val rawN = o.opt("n_max")
        if (rawN != null && rawN != JSONObject.NULL && rawN !is Number) {
            return null to "speculative.n_max 必须是整数"
        }
        // `Number` 也接受 `2.5`；org.json 对 `2.5` 给 Double，`toInt()` 会静默截成 2
        // —— 客户端以为自己要了 2.5、实际跑 2，读数对不上却没有任何一行报错。
        // 非整数一律 400（与 n_max 是"离散草稿数"的语义一致）。
        if (rawN is Number && rawN.toDouble() != Math.floor(rawN.toDouble())) {
            return null to "speculative.n_max 必须是整数"
        }
        val n = (rawN as? Number)?.toInt() ?: 0
        if (n < 0 || n > MAX_N_MAX) {
            return null to "speculative.n_max 必须在 0..$MAX_N_MAX（0 = 用加载期配置）"
        }
        return Parsed(enabled = enabled, nMax = n) to null
    }

    fun describe(p: Parsed): String =
        "投机请求：enabled=${p.enabled}" + if (p.nMax > 0) "，n_max=$p.nMax" else ""
}
