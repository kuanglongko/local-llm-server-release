package com.xiaowan.localinference

import org.json.JSONArray

/**
 * `/v1/chat/completions` 的 `content` **数组**形态（多模态分段）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独一个判据文件（ISSUE #452 第 2 步）
 * ══════════════════════════════════════════════════════════════════════════
 * 旧写法只取 `type == "text"` 的段，其余（`image_url` / `input_audio` / `file` …）
 * **一个字节都不说**就扔掉。而 SDK 会照着自己那张模型能力表照发 —— 于是
 * 客户端以为模型看过图了，模型其实只看到一句没有图的话，两边都不报错。
 *
 * 本服务没有视觉/音频通路（native 侧没有 mmproj，也没有音频解码），这是**能力边界**，
 * 不是裁剪问题。能力边界必须说出口：静默丢用户输入比裁剪危险得多，
 * 因为裁剪至少会在 `local_ctx` 上留痕，而"丢了一张图"只有用户自己看得出来。
 *
 * 所以判据只有一条：出现非 text 段 -> 400（而**不是**"照旧丢、响应里留痕"）。
 * 理由：这一轮模型已经答错了，留痕救不回来；而客户端拿到 400 能当场改请求
 * （去掉图 / 换端点），那是它唯一能自救的动作。
 */
object ContentParts {

    /** 认得的段类型（本服务只处理文本）。其余一律按"不支持"报出去。 */
    private const val TEXT = "text"

    /**
     * `content` 数组里第一个**不支持**的段类型（`null` = 全是 text，或者根本没有类型名）。
     *
     * 没有 `type` 的段按不支持算：OpenAI 语义里它是必填的，缺了说明这份请求本身就是
     * 猜出来的形状 —— 猜它是不是文本，等于替调用方编一个他没写过的字段。
     */
    fun unsupported(content: JSONArray): String? {
        for (k in 0 until content.length()) {
            val o = content.optJSONObject(k) ?: continue
            val type = o.optString("type", "")
            if (type != TEXT) return if (type.isEmpty()) "(缺少 type)" else type
        }
        return null
    }

    /** 把 `content` 数组里各 text 段的正文按原序拼起来（原行为，逐字节不变）。 */
    fun textOf(content: JSONArray): String {
        val sb = StringBuilder()
        for (k in 0 until content.length()) {
            val o = content.optJSONObject(k) ?: continue
            if (o.optString("type") == TEXT) sb.append(o.optString("text"))
        }
        return sb.toString()
    }

    /**
     * 400 的文案。**说清"这是能力边界"与"它没被悄悄丢掉"** —— 只报一句
     * `unsupported content type` 的话，调用方会去改参数猜哪个字段非法，
     * 而真相是这条路根本不存在。
     */
    fun rejectMessage(type: String): String =
        "不支持的内容类型 $type：本服务只处理文本输入（没有视觉/音频通路），" +
            "这一轮没有把用户输入悄悄丢掉 —— 请去掉该段，或改用支持多模态的模型与端点"
}
