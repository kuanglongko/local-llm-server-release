package com.xiaowan.localinference

/**
 * `/v1/abort` 的**请求侧解析**：从请求体里取出 `slot`（Issue #330 阶段 4）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独一个纯逻辑文件
 * ══════════════════════════════════════════════════════════════════════════
 * `HttpApi` 依赖 Android / org.json / native，宿主编不进来 —— 而这段解析的
 * 失效模式恰恰是**静默的**：
 *
 *   · 把非法 slot **回落成 slot 0**（而不是拒绝）：调用方写错了参数，
 *     结果停掉了**别人**那一轮。症状是"用户看到别人的回答被掐断"——
 *     正是本期最需要避免的形态，而它不报错、不打日志。
 *   · 缺省值判错（把"没给 slot"当成"给了 slot 0 以外的值"）：今天的客户端
 *     全部不带 slot，判错的后果是**所有**既有的 `/v1/abort` 调用都失效，
 *     而调用方只看到 `aborted:false`（与"本来就没人跑"完全同形）。
 *
 * 所以判据锚在**这一段纯逻辑**上，用宿主单测钉住三态（缺省 / 合法 / 非法），
 * 与 `RequestCancel` / `SlotScheduler` 是同一套做法（阶段 2 起的口径）。
 *
 * ⚠ 解析用 `org.json` 会把它拖成 Android 依赖，所以这里**不解析 JSON**，
 * 只处理"已经取出来的那个值"：`HttpApi` 负责 `JSONObject` 那一跳，
 * 把 `opt("slot")` 的结果（`Any?`）交给本文件判 —— 判据与解析分开，
 * 才能只对判据做宿主单测（JSON 那一跳是库的事，`org.json` 已经测过）。
 */
object AbortRequest {

    /**
     * 解析 `slot` 的**三态**结论。
     *
     * 用密封类而不是 `Int?`：`null` 会把"缺省（= slot 0）"与"非法（拒绝执行）"
     * 糊成同一个返回值 —— 而那两件事的处置完全相反（一个照常停 slot 0，
     * 一个必须拒绝、不许停任何人）。
     */
    sealed class Slot {
        /** body 里没有 `slot`（或 body 为空）：今天所有客户端的形态，等价 slot 0。 */
        object Default : Slot()

        /** 合法取值。 */
        data class Value(val slot: Int) : Slot()

        /** 非法（越界 / 类型不对 / 空串）：**拒绝执行**，不回落。 */
        object Invalid : Slot()
    }

    /**
     * 判一个从 body 里取出来的 `slot` 值。`hasSlot=false` 表示 body 里没这个字段。
     *
     * 规则（只有三条，多一条都会引入"看着像正常工作"的静默分叉）：
     *   ① 没给（含 body 为空）-> [Slot.Default]（等价 slot 0，兼容今天的客户端）；
     *   ② 给了且是 `[0, SLOT_MAX)` 的整数 -> [Slot.Value]；
     *   ③ 其余（越界、非整数、布尔、对象、空串）-> [Slot.Invalid]，**不钳位、不回落**。
     *
     * 为什么**不钳位**：`slot=9` 在 4 个 slot 的池子上钳成 3，会让调用方以为
     * "我停的是第 9 路"而实际停掉了第 3 路 —— 而"停错 slot"的后果是
     * 「别人的回答被掐断」，比"拒绝执行"严重得多。拒绝执行至少是**可判定**的。
     */
    fun resolve(hasSlot: Boolean, raw: Any?): Slot {
        if (!hasSlot) return Slot.Default
        val n = when (raw) {
            // JSON 的数字可能是 Int / Long / Double（`1` 与 `1.0` 都能被解析出来）。
            // 判据是**整数语义**：`1.0` 认同，`1.5` 不认（它不是任何 slot 的下标）。
            is Int -> raw
            is Long -> if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt()
                       else return Slot.Invalid
            is Double -> if (raw == Math.floor(raw) &&
                             raw >= Int.MIN_VALUE.toDouble() && raw <= Int.MAX_VALUE.toDouble())
                            raw.toInt() else return Slot.Invalid
            // 字符串形式（`{"slot":"1"}`）—— 宽松接受整数串；空串/非数字串一律非法。
            is String -> raw.trim().toIntOrNull() ?: return Slot.Invalid
            else -> return Slot.Invalid
        }
        return if (n in 0 until SlotScheduler.SLOT_MAX) Slot.Value(n) else Slot.Invalid
    }
}
