package com.xiaowan.localinference

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件名里那个时间戳的**唯一**出处。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么值得单独一个对象
 * ══════════════════════════════════════════════════════════════════════════
 * `yyyyMMdd-HHmmss` 此前在三处各拼一遍（导出文件名、崩溃探针文件名、
 * 会话日志文件名），三份逐字相同。它看起来只是"格式化一下"，但有两处
 * 语义是**隐式的**，改一处忘一处就会让文件排不出先后：
 *
 *   · **Locale 必须是 `Locale.US`**：用默认 Locale 时，阿拉伯语等区域会把
 *     数字换成当地数字，`yyyyMMdd` 就不再是 ASCII —— 而 `LogFileStore`
 *     认"上一次会话"是按文件名字典序取最大（见那边的注释），换一套数字后
 *     排序规则直接失效。
 *   · **时区必须是本机时区**：用户看文件名的第一反应是"我几点导的"，
 *     统一成 UTC 会让他对着一个差 8 小时的数发呆。这里刻意**不**指定
 *     TimeZone，保持 `SimpleDateFormat` 的默认（= 本机）。
 *
 * `SimpleDateFormat` 不是线程安全的，所以每次调用新建一个实例；导出/建会话
 * 都是低频动作，这点分配不值得引入 ThreadLocal。
 */
object TimeFmt {

    /** 文件名字戳的格式串（同时被 [LogFileStore] 用来反解会话顺序）。 */
    const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

    /**
     * 当前时刻的 `yyyyMMdd-HHmmss`（US Locale、本机时区）。
     *
     * @param now 毫秒时间戳；单测要固定读数时传它，业务调用点不传。
     */
    fun stamp(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(now))
}
