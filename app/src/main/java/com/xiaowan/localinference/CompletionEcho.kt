package com.xiaowan.localinference

import org.json.JSONObject

/**
 * `/v1/completions` 的 `echo` / `suffix`。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独一个文件
 * ══════════════════════════════════════════════════════════════════════════
 * 这两个字段此前**请求里根本没被读**，于是 `echo: true` 与 `echo: false` 走同一条
 * 码路、拿到同一份 text —— 客户端以为自己拿到了 `prompt + completion`，实际只有
 * completion。与 [ProtocolFields] 文件头记的那类失效同形：**静默忽略**，200 照回。
 *
 * 判据（OpenAI text_completion 语义）：
 *   · `echo: true`  -> `choices[0].text` = prompt + completion
 *   · `echo: false` -> `choices[0].text` = completion（默认，字段缺失也是这个）
 *   · `usage.prompt_tokens` / `total_tokens` **不受 echo 影响**：echo 只改返回文本的
 *     形状，不改"算了多少 token"。把回显段算进 completion 会让按 token 计费的客户端
 *     把同一份 prompt 算两遍。
 *
 * `suffix` 在本实现里被**明确拒绝**（见 [errSuffix]）：OpenAI 里它要求走 FIM
 * （fill-in-the-middle）特殊 token，而裸补全没有那条通路 —— 收下它等于
 * "参数生效了一半"，比 400 更难排查。
 */
object CompletionEcho {

    const val errEcho = "echo 必须是布尔值（true = text 里带上 prompt 原文）"
    const val errSuffix = "suffix 暂不支持（裸补全没有 FIM 通路；收下它会让参数只生效一半）"

    /**
     * 解析 `echo` / `suffix`。返回 (echo, 错误) 二选一，错误可直接作为 400 正文。
     *
     * 只认 JSON 布尔：字符串 `"true"` / 数字 `1` 一律 400。理由与 [SamplingParams]
     * 文件头同一条 —— 显式给了值却解析成"没给"，调用方从输出上分不出来。
     */
    fun fromRequest(j: JSONObject): Pair<Boolean, String?> {
        // 空串 suffix = "没给"（UI 清空参数框），不算错误。
        if (j.has("suffix") && !j.isNull("suffix")) {
            val s = j.opt("suffix")
            if (s !is String || s.isNotEmpty()) return false to errSuffix
        }
        if (!j.has("echo") || j.isNull("echo")) return false to null
        return when (val v = j.opt("echo")) {
            is Boolean -> v to null
            else -> false to errEcho
        }
    }

    /**
     * 要回显的那段 prompt。
     *
     * 必须是**喂给模型的那一整串**：JSONArray 形式的 prompt 在 [HttpApi] 里已经用
     * `\n` 连成一段再进 prefill，回显若换一种拼法，客户端「text 减 prompt」就会切错位置。
     * 所以这里只做"echo 才回显"，不做任何裁剪 / 归一化。
     */
    fun echoPrefix(prompt: String, echo: Boolean): String? = if (echo) prompt else null

    /** 非流式拼装：`prefix == null` 时原样返回（与引入本特性之前逐字节相同）。 */
    fun joinText(prefix: String?, completion: String): String =
        if (prefix == null) completion else prefix + completion

    /**
     * 流式：回显段作为**首帧**单独下发。
     *
     * 不并进第一帧的两个理由：第一帧的到达时间会取决于模型多久吐出第一个 token，
     * 而客户端常见的"按帧累加的空闲超时"会在「prompt 很长 + 首 token 很慢」时开断；
     * 且空串不该多出一帧（只会让客户端多做一次拼接）。
     */
    fun headPiece(prefix: String?): String? = prefix?.takeIf { it.isNotEmpty() }
}
