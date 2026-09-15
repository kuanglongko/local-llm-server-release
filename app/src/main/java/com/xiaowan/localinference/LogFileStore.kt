package com.xiaowan.localinference

import java.io.File
import java.io.RandomAccessFile
import java.io.OutputStreamWriter

/**
 * 崩溃取证：日志实时落盘。
 *
 * 为什么必须做：此前日志只活在 [LlmEngine.logRing]（内存，上限 300 行）。native 侧
 * SIGABRT 时进程瞬间消失，用户来不及点「导出 txt」——等于**崩溃日志必然丢失**，
 * 而这恰恰是最需要日志的时刻。
 *
 * 现在每条日志写入 filesDir/logs/session-<启动时间>.log 并 flush。注意只 flush 不 fsync：
 * 进程崩溃时内核页缓存里的数据不会丢（只有整机掉电/内核 panic 才丢），用零延迟换来崩溃可查。
 *
 * 同时把 native 的 stderr 也接进来（见 [StderrPump]），因为 ggml_abort 之类的死前最后
 * 一句话只往 stderr 打，不进 logcat 也不进我们的日志回调。
 */
object LogFileStore {

    /** 保留最近几个会话文件，够回溯；再多的没价值且占空间 */
    private const val KEEP = 8
    private const val PREFIX = "session-"
    private const val END_MARK = "## session-end-clean"

    /**
     * 运行时日志的行首标签，给人眼读日志和按标签 grep 用。集中一处定义，将来若新增按标签取数的
     * 解析方，只改这里。目前没有任何代码反向解析它们。
     */
    const val TAG_HTP = "[HTP参与]"
    const val TAG_HTP_MEASURED = "[HTP实测]"
    const val TAG_TIME = "[计时]"

    private var root: File? = null
    private var writer: OutputStreamWriter? = null
    private val lock = Any()

    /** 本次会话的日志文件（未初始化时为 null） */
    @Volatile var currentFile: File? = null
        private set

    /** 上一个会话文件；若它没写正常结束标记，很可能就是崩溃那一次 */
    @Volatile var previousFile: File? = null
        private set

    /** 上次会话是否缺少正常结束标记（注意：手动杀进程也会缺，只能作为线索而非结论） */
    @Volatile var previousUnclean: Boolean = false
        private set

    /** 上次会话写过几次"切后台"结束标记。计数必须每次会话重置：标记只追加不清理，
     * 一次会话切 8 次后台就有 8 个标记，之后再崩也会被"全文包含"判成正常退出。 */
    @Volatile var previousMarkCount: Int = 0
        private set

    /** 会话 id（= 文件名去掉 .log）：导出文件名与日志头部用它对应当次现场，避免用导出时刻猜时间。 */
    @Volatile var currentSessionId: String? = null
        private set
    @Volatile var previousSessionId: String? = null
        private set

    /** 幂等：任何入口在写日志前都可安全调用 */
    @Synchronized
    fun init(context: android.content.Context) {
        if (writer != null) return
        val r = File(context.applicationContext.filesDir, "logs")
        root = r
        runCatching { if (!r.exists()) r.mkdirs() }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val cur = File(r, "$PREFIX$stamp.log")
        currentFile = cur
        currentSessionId = cur.name.removeSuffix(".log")
        fileBase = null; fileRep = 0   // 新会话：折叠计数从 0 起

        // 先认上一次会话（排除本次新建的空文件）
        val prev = runCatching {
            r.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".log") && f != cur }
                ?.maxByOrNull { it.lastModified() }
        }.getOrNull()
        previousFile = prev
        previousSessionId = prev?.name?.removeSuffix(".log")
        if (prev != null && prev.length() > 0) {
            val (clean, cnt) = cleanMarkStat(prev)
            previousUnclean = !clean
            previousMarkCount = cnt
        }

        runCatching {
            // 只留最近 KEEP 个（含本次）
            val all = r.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            all.drop(KEEP - 1).forEach { runCatching { it.delete() } }

            writer = OutputStreamWriter(java.io.FileOutputStream(cur, true), Charsets.UTF_8)
        }
    }

    /**
     * 上次会话是否**干净结束**。判据：最后一个非空行 == 结束标记。
     *
     * 不能用「全文是否包含标记」判定：同一次会话里可能既写过结束标记、又真的崩了，
     * 早已写过 8 个切后台标记。改成末行判据后残留一个反向问题：若切后台后又继续写日志
     * 才被杀，末行不是标记 → 会多报一次告警。宁可多报也不漏报，且提示里带标记次数供判断。
     * 只读尾部 64KB：标记总在末尾附近，崩溃现场没必要全文扫。返回 (是否干净, 标记次数)。
     */
    private fun cleanMarkStat(f: File): Pair<Boolean, Int> = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length().toInt()
            val n = minOf(len, 65536)
            if (n <= 0) return@use true to 0
            raf.seek((len - n).toLong())
            val buf = ByteArray(n)
            raf.readFully(buf)
            val lines = String(buf, Charsets.UTF_8).split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }
            (lines.lastOrNull() == END_MARK) to lines.count { it == END_MARK }
        }
    }.getOrDefault(true to 0)

    private var fileBase: String? = null
    private var fileRep = 0

    /** 噪音行的累计计数与样例（用于恢复输出时补一条"省略 N 条"说明） */
    private var noiseRun = 0
    private var noiseSample = ""

    /**
     * HTP 的 VERBOSE=1 输出里有两类**纯探测噪音**：逐缓冲区的 device-supports-buft、
     * 逐算子逐设备的 supports-op。两类合计能占掉整份日志九成以上行数，而它们只回答
     * "这个缓冲区/这个算子这台设备支不支持"，对定位崩点零价值，
     * 却把有用的 [HTP参与] 摘要与 push batch 行全冲掉，并把导出文件撑大一个数量级。
     *
     * 规则：
     * - `device-supports-buft` 全丢（无论 0/1，都是逐设备扫）；
     * - `supports-op` 只丢判定为 `(0)`（不支持）的那些，`(1)`（真正被哪台设备接走）**保留**，
     * 这样仍能看出算子最终落在哪台设备，只是不再打印另外 3 台的"不支持"。
     */
    fun isNoise(line: String): Boolean =
        line.contains("device-supports-buft") ||
                (line.contains("supports-op") && line.trimEnd().endsWith("(0)"))

    /** 记一条噪音（在真正写出前由 [flushNoise] 补一条计数说明） */
    fun noteNoise(line: String) {
        if (noiseRun == 0) noiseSample = line
        noiseRun++
    }

    fun flushNoise() {
        val n = noiseRun
        if (n <= 0) return
        noiseRun = 0
        append("$noiseSample ｜同类噪音共 $n 条已省略（LM_GGML_HEXAGON_VERBOSE=0 可整块关掉）")
    }

    /**
     * 追加一行（内部串行化；绝不在此再调 Log* 以外的日志路径，避免自激）。
     *
     * 文件侧同样折叠连续重复行：一次回答可能写下上百行 "."，把关键行挤出去。
     * 导出后人工 grep 与阅读都被淹掉。
     *
     * 保留策略（避免"缓冲到断开才写"导致崩溃前最后一段丢失）：
     * - 第 1 次出现立即写；
     * - 之后每满 100 次再写一条 "xxx ×N"，所以文件始终是**实时推进**的，不存在缓冲窗口；
     * - 以 "##" 开头的控制行（结束标记等）永不折叠，否则末行判据会被 "##xxx ×2" 破坏。
     */
    fun append(line: String) {
        val w = writer ?: return
        val out = if (line.startsWith("##")) {
            fileBase = null; fileRep = 0
            line
        } else if (line == fileBase) {
            fileRep++
            if (fileRep % 100 != 0) return
            "$line ×$fileRep"
        } else {
            fileBase = line; fileRep = 1
            line
        }
        synchronized(lock) {
            runCatching {
                w.write(out)
                w.write("\n")
                w.flush()
            }
        }
    }

    /**
     * 正常退出时写结束标记。**不关 writer**：用户切后台再回前台是同一进程同一会话，
     * 关掉会导致后续日志静默丢失。标记只作为"这一次走完了退出流程"的线索。
     */
    fun markCleanExit() {
        runCatching { append(END_MARK) }
    }

    fun currentText(): String = runCatching {
        currentFile?.let { if (it.exists()) it.readText() else "" } ?: ""
    }.getOrDefault("")

    /** 崩溃那次的内容：先把文件 flush 出去再读 */
    fun previousText(): String = runCatching {
        previousFile?.let { if (it.exists()) it.readText() else "" } ?: ""
    }.getOrDefault("")

    fun previousAvailable(): Boolean =
        runCatching { (previousFile?.length() ?: 0L) > 0L }.getOrDefault(false)
}
