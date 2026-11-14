package com.xiaowan.localinference

/**
 * 设置页数值输入框的解析：**全角数字 → 半角、去空白与零宽字符**，再转 Int。
 *
 * 为什么必须有这一层：`gpu layers` 原来直接写
 * `gpuEt.text.toString().toIntOrNull() ?: 0`。中文输入法在「全角」档下打出的是
 * `１５`，或者粘贴时尾部带一个不可见空格，`toIntOrNull()` 一律返回 null，
 * 于是被 `?: 0` **静默**变成"纯 CPU"：
 *
 *   - 界面上框里明明写着 15；
 *   - 日志里只有 `[加载] … gpu_layers=0`，与用户手填 0 完全同形；
 *   - 之后每次加载都是全 CPU，看起来像"GPU 突然不能用了"。
 *
 * 真机现场（0.9.305）正是这一形态：用户说"第三轮之后 gpu layers 全是 15，
 * 但只能全部用 CPU"，日志里四轮是 `33 → 0 → 0 → 0`。
 *
 * 因此这里做两件事：
 *   1. **能认的写法直接认**（全角数字、全角正负号、首尾/中间空白、零宽字符）；
 *   2. 仍然解析不出时返回 `null` —— 调用方**必须报错并中止加载**，不许再兜成 0。
 *
 * 只做字符串归一，不依赖 Android，可在宿主侧直接单测。
 */
object NumInput {

    /** 归一化：全角数字/正负号转半角，去掉所有空白与零宽字符。 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch == '＋' -> sb.append('+')
                ch == '－' || ch == '−' -> sb.append('-')
                // 普通空白 + 不换行空格（U+00A0 / U+2007 / U+202F）。
                // Java 的 isWhitespace 对 NBSP 返回 false，只 trim 会漏掉它。
                ch.isWhitespace() || ch == '\u00A0' || ch == '\u2007' || ch == '\u202F' -> {}
                ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 归一化后转 Int；空串或仍含非数字字符时返回 `null`（**不是 0**）。 */
    fun intOrNull(raw: String): Int? = normalize(raw).toIntOrNull()

    /**
     * 日志里的可读转义：保留普通字符，把换行/制表/引号/控制字符写成转义。
     *
     * 为什么不能直接把 `raw` 塞进 `"…"`：一个换行就能把一行日志拆成两行，
     * 下一个人会以为加载发生了两次；而 `\r` 还能把前半行覆盖掉。
     */
    fun escaped(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch == '\u007F' ->
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 现场取证用：长度 + 每个 UTF-16 码点。
     *
     * 「框里看着是 15、交给加载却是 0」如果只打原文，全角 `１` 与半角 `1`
     * 在日志里几乎同形，尾随空格/零宽字符更是完全看不见。把码点打出来，
     * 下一次现场就能直接确认是哪种字符，而不是继续推理。
     */
    fun describe(raw: String): String {
        val cps = StringBuilder()
        for (ch in raw) {
            if (cps.isNotEmpty()) cps.append(' ')
            cps.append("U+").append(ch.code.toString(16).uppercase().padStart(4, '0'))
        }
        return "len=${raw.length} 码点=[$cps]"
    }
}
