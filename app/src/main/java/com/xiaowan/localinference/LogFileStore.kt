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

    /**
     * 保留最近几个会话文件，够回溯；再多的没价值且占空间。
     *
     * 可见性从 private 放宽到 internal：日志页的状态带要如实说"保留最近 N 个会话"，
     * 而这个 N 必须与这里的保留策略同源 —— 界面上再抄一个字面量，
     * 改了策略那天就会开始说假话（本仓库最擅长记的那类静默失真）。
     */
    internal const val KEEP = 8
    private const val PREFIX = "session-"
    private const val END_MARK = "## session-end-clean"

    /**
     * 运行时日志的行首标签，给人眼读日志和按标签 grep 用。集中一处定义，将来若新增按标签取数的
     * 解析方，只改这里。**日志页的过滤判据就在同一个文件下方（[LogFilter]）**，
     * 改标签时两侧一起改，不会出现"标签改了、过滤还是旧串"而静默取不到数。
     */
    const val TAG_HTP = "[HTP参与]"
    const val TAG_HTP_MEASURED = "[HTP实测]"
    const val TAG_TIME = "[计时]"
    /** 内存读数（推理期 RSS 峰值等）：与 `[mmap释放]` 的加载态读数成对读。 */
    const val TAG_MEM = "[内存]"

    /**
     * 模型档案（GGUF 元数据的读数：权重字节 / 参数个数 / 训练上下文 / 架构 …）。
     *
     * **不是筛选档位**：它是加载期的一条读数、出现频率等于"加载了几次"，
     * 加一个只有一两行的档位没有意义。用关键字搜 `[模型档案]` 即可定位 ——
     * 这也是关键字这一维存在的理由之一（此前只有固定几档时，这类一次性读数
     * 只能靠人眼在 120 行窗口里翻）。
     */
    const val TAG_PROFILE = "[模型档案]"

    /**
     * 日志页的取数口径：**标签多选** + **任意关键字**。
     *
     * 两维都只认"行首紧跟着标签"这一种形态（关键字除外：关键字搜的就是**正文**），
     * 与写进文件/ring 的那些行完全一致（见下方的 `append` 与 `LlmEngine.pushLog`）。
     * 刻意**不用** `contains(标签)`：正文/模型输出里出现 `[内存]` 四个字
     * （比如用户问"日志里的[内存]是什么意思"）会被一并捞进来，
     * 那种"过滤结果里混进无关行"没有任何症状，只会让人看着觉得"这过滤不太准"。
     */
    object LogFilter {
        /**
         * chip 的显示文案 → 判据。顺序即 UI 顺序。
         *
         * **这里没有「全部」这一档**（`0.9.143` 删掉了）。原来有，是因为档位是单选：
         * 必须有个东西表示"不筛"。现在标签是**多选**，同一个意思有了更直白的表达：
         * **一个都不选 = 全部**。留着「全部」反而多出一个说不清的组合
         * （「全部」和「计时」一起选中算什么？全部？——那用户点了「计时」为什么还是全部）。
         */
        const val TIME = "计时"
        const val MEM = "内存"
        const val HTP = "HTP"
        const val ERR = "仅错误"

        val labels = listOf(TIME, MEM, HTP, ERR)

        /**
         * 一行日志的"取数视图"：**标签与失败标记只看这个字符串**。
         *
         * `line` 原样留给关键字搜索（用户要搜的就是正文），`t` = **去掉行首空白**后的形态，
         * 标签与失败标记都锚在它上面。
         *
         * 为什么包装成类而不是每次 `line.trimStart()` 三遍：判据要在**两处**跑 ——
         * Kotlin 侧取数，以及宿主复刻测试（`tools/pure_logic/PureLogicTest.kt`）。
         * 两边一旦写法不同，"测试全绿"就只是测了我抄的那一份。
         * 形态只在这里定义一次，复刻侧照抄这一份。
         */
        class Line(val line: String, private val t: String) {
            fun startsWith(prefix: String) = t.startsWith(prefix)
        }

        fun of(line: String) = Line(line, line.trimStart())

        /**
         * 这行是不是一次失败。
         *
         * 判据分两半，**故意不一样**，这是本档最容易写歪的地方：
         *
         *   · `⚠`（真正的告警符号）—— **行内任意位置**都算。原因是本仓库真实的失败行
         *     长这样：`[repack] ⚠ 本包 native 未导出 …`、`[日志] ⚠ 上次会话末尾无结束标记`，
         *     **标签在前、`⚠` 在后**。若也要求它在行首，则"仅错误"这一档在本仓库
         *     几乎永远筛不出东西 —— 而"筛不出东西"与"确实没出错"同形，
         *     用户会得出"没出过错"的结论。这个符号是**人特意敲上去的**，不可能是
         *     正文里自然出现的。
         *   · `[错误]` / `[失败]` / … 这些**方括号标记** —— 只认**行首**。
         *     用行内查找的话，"解释一下什么是`[错误]`码"这类**正常输出**会被算成错误行，
         *     而"错误变多"没有任何症状 —— 只会让人越来越不信这一档。
         *
         * 两半都是**符号级**判据，都**不**认正文里的"失败""错误"汉字：那些字随时会
         * 出现在正常对话与日志正文里（用户就在问"加载失败是什么意思"）。
         */
        fun isError(l: Line): Boolean =
            l.line.contains(WARN_MARK) ||
                l.startsWith("[错误]") ||
                FALLBACK_ERR.any { l.startsWith(it) }

        /** 唯一认的"告警符号"。行内任意位置都算 —— 见 [isError] 的长注释。 */
        private const val WARN_MARK = "⚠"

        /** [isError] 只认的几种**行首**方括号标记；顺序无关，全部是"行首 + 冒号/空格"形态。 */
        private val FALLBACK_ERR =
            listOf("[失败]", "[拒绝]", "[异常]", "[崩溃]", "[crash]", "[error]", "[Error]")

        /** 有没有选"按标签"的那三档（「仅错误」不算：它不是标签，是"这行是不是一次失败"）。 */
        fun anyLabel(keys: Set<String>) = keys.any { it != ERR }

        /** 有没有选「仅错误」这一档。写成函数而不是在别处再抄一次 `ERR in keys`：判据只有一处。 */
        fun errorOnly(keys: Set<String>) = ERR in keys

        /**
         * 这一行是否通过**标签**这一维。
         *
         * 三支判据各自独立，"选中哪几档就放行哪几档命中的行"（多选 = **或**）：
         *   · 计时 / 内存：行首标签；
         *   · HTP：`[HTP参与]` 或 `[HTP实测]`（两个标签，用户眼里是一件事）。
         *
         * 调用方（[matches]）已保证"一个标签档都没选"时不会走到这里。
         */
        private fun labelMatch(keys: Set<String>, l: Line): Boolean =
            (TIME in keys && l.startsWith(TAG_TIME)) ||
                (MEM in keys && l.startsWith(TAG_MEM)) ||
                (HTP in keys && (l.startsWith(TAG_HTP) || l.startsWith(TAG_HTP_MEASURED)))

        /**
         * 当前选中的档位是否放行这一行。
         *
         * 两维之间是**与**；标签多选内部是**或**（"计时或内存"）；
         * 「仅错误」与它们叠加时是**收窄**（"计时里的错误行"）。
         *
         * **一个标签档都没选 = 不按标签筛**（不是"全部不放行"）：界面上就是"一个 chip
         * 都没选"，用户读作"全部"。反过来会让清空选中变成一张空白页，而空白页在日志页的
         * 语义是"没记日志" —— 他又会去查一件没发生的事。
         *
         * 单独选「仅错误」时**不看标签**：否则它就悄悄变成了某一档的子集，
         * 而用户点它想要的是"所有失败，不管属于哪一档"。
         */
        fun matches(keys: Set<String>, line: String): Boolean {
            val l = of(line)
            val byLabel = if (!anyLabel(keys)) true else labelMatch(keys, l)
            return byLabel && (!errorOnly(keys) || isError(l))
        }

        /**
         * 关键字是否命中这一行。
         *
         * 判据是**子串**、且大小写不敏感：用户要搜什么就搜什么（包括他刚在屏幕上看到的
         * 半句话），日志里大小写混着来（`[HTP参与]` / `decode` / `Decode`），
         * 要求他大小写打准只会让他以为自己搜错了。
         *
         * 空关键字 = 不筛。这一支不能省：没有它，"还没打字"会变成"什么都不命中"，
         * 而症状同样是一张空白页。
         */
        fun keywordMatches(keyword: String, line: String): Boolean {
            if (keyword.isEmpty()) return true
            return line.contains(keyword, ignoreCase = true)
        }

        /**
         * 空白页时必须显示的一句话：说清"筛了什么、还剩多少、去哪看全部"。
         *
         * 三件事必须都能看出来，缺一件就是一个会把用户指向错方向的空白页：
         *   1. **筛了什么**（哪些档 + 哪个关键字）—— 否则他不知道为什么只剩这几行；
         *   2. **命中多少 / 整条 ring 有多少** —— 否则区分不了"这档真没有"与
         *      "ring 本来就空"，而这两种情况该做的事完全不同；
         *   3. **出口**（怎么回到全部）—— 否则他只能自己乱点。
         */
        fun emptyHint(keys: Set<String>, keyword: String, hit: Int, total: Int): String {
            if (hit > 0) return ""
            val what = describe(keys, keyword)
            return when {
                total <= 0 -> "日志为空：还没有写入任何日志行。"
                what.isEmpty() -> ""
                else -> "没有同时满足 $what 的日志行（整段日志共 $total 行）；" +
                    "清掉筛选或关键字看全部。"
            }
        }

        /** 把两维筛选写成一句人话（顺序固定：先档位后关键字，便于对照着读）。 */
        fun describe(keys: Set<String>, keyword: String): String {
            val parts = ArrayList<String>(2)
            if (keys.isNotEmpty()) parts += "「" + labels.filter { it in keys }.joinToString("」「") + "」"
            if (keyword.isNotEmpty()) parts += "含「$keyword」"
            return parts.joinToString(" + ")
        }
    }

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

    /**
     * 幂等：任何入口在写日志前都可安全调用。
     *
     * 锁用 [lock]（与 [append] / [noteNoise] / [flushNoise] 同一把，不是 this）：
     * 折叠状态 [fileBase] / [fileRep] 在这里重置，若与写入路径用不同监视器，
     * 重置就会与并发 `append` 交错 —— 那就等于没加锁。
     */
    fun init(context: android.content.Context) {
        synchronized(lock) {
            if (writer != null) return
            val r = File(context.applicationContext.filesDir, "logs")
            root = r
            runCatching { if (!r.exists()) r.mkdirs() }

            val stamp = TimeFmt.stamp()
            val cur = File(r, "$PREFIX$stamp.log")
            currentFile = cur
            currentSessionId = cur.name.removeSuffix(".log")
            fileBase = null; fileRep = 0   // 新会话：折叠计数从 0 起

            // 先认上一次会话（排除本次新建的空文件）。
            //
            // ⚠ 这里必须按**文件名**取最大，不能按 `lastModified()`：`cur` 在上面
            // 就已经建好了，它的 mtime 立刻是 now，按 mtime 排序它永远最大 ——
            // 于是这次筛选会把**本次会话**认成上一次，`previousSessionId` 指向自己。
            // 文件名里本来就带着 `yyyyMMdd-HHmmss`，它才是"谁先谁后"的唯一真值。
            val prev = runCatching {
                r.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".log") && f.name != cur.name }
                    ?.maxByOrNull { it.name }
            }.getOrNull()
            previousFile = prev
            previousSessionId = prev?.name?.removeSuffix(".log")
            if (prev != null && prev.length() > 0) {
                val (clean, cnt) = cleanMarkStat(prev)
                previousUnclean = !clean
                previousMarkCount = cnt
            }

            runCatching {
                // 只留最近 KEEP 个（含本次）。
                //
                // ⚠ 排序口径同上，必须按文件名。旧写法用 `lastModified()`，而本次文件
                // 是**刚刚**创建的（`FileOutputStream` 还没写），它的 mtime 就是 now：
                // 本次永远排第 1，`drop(KEEP - 1)` 于是恰好删掉第 KEEP 个、也就是
                // **上一次会话** —— "导出上次会话"导出的永远是当前会话刚写的头几行。
                // 那句"保留最近 8 个会话，够回溯"从写下那天起就没成立过。
                val all = r.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".log") }
                    ?.sortedByDescending { it.name } ?: emptyList()
                all.drop(KEEP - 1).forEach { runCatching { it.delete() } }

                writer = OutputStreamWriter(java.io.FileOutputStream(cur, true), Charsets.UTF_8)
            }
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

    /**
     * 文件侧的折叠状态。**这四个字段的每一次读写都必须在 [lock] 之内**。
     *
     * 为什么：`append` 会被四个线程并发调用（native 日志回调线程、stderr-pump 线程、
     * UncaughtExceptionHandler 线程、主线程），而折叠逻辑是"读-改-写"三步 ——
     * 锁外做的话，两个线程会各自以为自己是"第一个"，`fileRep` 的增长被打断，
     * 写出的 `×N` 少报（`noiseRun` 同理，且方向是多报）。
     * 这些数字的**全部用途就是给人对账**（"日志少了的那九成行数去哪了"），
     * 失真时它给出的恰恰是错误方向的确定性。内存 ring 侧（[LlmEngine.logRing]）
     * 一直是加锁的，这里原来漏了。
     */
    private var fileBase: String? = null
    private var fileRep = 0

    /** 噪音行的累计计数与样例（用于恢复输出时补一条"省略 N 条"说明）——同样受 [lock] 保护 */
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

    /** 记一条噪音（在真正写出前由 [flushNoise] 补一条计数说明）。计数与样例在同一临界区内配对更新。 */
    fun noteNoise(line: String) {
        synchronized(lock) {
            if (noiseRun == 0) noiseSample = line
            noiseRun++
        }
    }

    /**
     * 补一条「省略 N 条」说明。取数**与清零在同一个临界区内**：
     * 旧写法 `val n = noiseRun; … ; noiseRun = 0` 中间没有独占，
     * 另一线程在此期间 `noteNoise()` 的增量会被计数进 n 又被清零抹掉 —— 实测多报 0.34%~2.08%。
     */
    fun flushNoise() {
        val n: Int
        val sample: String
        synchronized(lock) {
            n = noiseRun
            if (n <= 0) return
            sample = noiseSample
            noiseRun = 0
        }
        append("$sample ｜同类噪音共 $n 条已省略（LM_GGML_HEXAGON_VERBOSE=0 可整块关掉）")
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
        synchronized(lock) {
            val w = writer ?: return
            // 折叠判据与写出必须在同一个临界区内：读-改-写三步分开做时，
            // 两个线程会各自认为自己是"第一个"，`fileRep` 的增长被打断 → 少报。
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
            runCatching {
                w.write(out)
                w.write("\n")
                w.flush()
            }
        }
    }

    /**
     * **不折叠**地追加一行（探针模式专用）。
     *
     * 为什么探针模式必须躲开 [append] 的折叠：折叠的判据是"连续重复行只留第 1 条、
     * 之后每满 100 条写一条 `xxx ×N`"。平时这是对的（不折叠会把 ring 冲爆），
     * 但探针模式存在的意义就是"崩前那几行必须原样在" —— 而 `xxx ×137` 这种汇总行
     * 在崩溃排查里等于没有。最坏的一支：模型正在逐 token 崩，而文件里只剩一条
     * "`×N`"，看不到崩在哪个 token 上。
     *
     * 与 [append] 共用 [lock] 与 [writer]（同一临界区），只是跳过折叠分支。
     * 提交 [END_MARK] 这类 "##" 控制行时同样复位折叠状态，避免它与后续 [append]
     * 的折叠计数打架（探针关掉后 [append] 会继续用这两个字段）。
     */
    fun appendRaw(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            if (line.startsWith("##")) { fileBase = null; fileRep = 0 }
            runCatching {
                w.write(line)
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
