package com.xiaowan.localinference

/**
 * **进度回执**与普通日志的分界（native 发、Kotlin 收）。
 *
 * 与 [RagProgress.PREFIX] 同一套机制：`logSink` 里按前缀分流，进度只更新状态行，
 * 不进 ring、不落盘、不进 logcat。区别只是 [RagProgress] 那一档是建索引，
 * 这一档是 native 侧的长任务（prefill）。
 *
 * ⚠ 前缀在 native 侧也有一份（`llama_jni.cpp` 的 `kProgressTag`），两处必须逐字
 *   相同。写成常量而不是各自散落字面量，至少让搜索点只有两个。
 */
object LogProgress {

    /** 与 `llama_jni.cpp::kProgressTag` 逐字相同。 */
    const val PREFIX = "[进度] "

    /** 这一条是不是进度回执（不是日志）。多 slot 时 native 会在前面加 `[sN]`。 */
    fun isProgress(line: String): Boolean = indexOfPrefix(line) >= 0

    /** 去掉前缀后的正文（状态行只显示正文）。 */
    fun body(line: String): String {
        val i = indexOfPrefix(line)
        if (i < 0) return line.trim()
        return line.substring(i + PREFIX.length).trim()
    }

    /** `[进度]` 在行内的起点；没有返回 -1。 */
    private fun indexOfPrefix(line: String): Int {
        if (line.startsWith(PREFIX)) return 0
        // 多 slot：`[sN][进度] ...`（见 native 的 `slot_tag`）。
        if (line.startsWith("[s")) {
            val close = line.indexOf(']')
            if (close > 0 && line.startsWith(PREFIX, close + 1)) return close + 1
        }
        return -1
    }
}
