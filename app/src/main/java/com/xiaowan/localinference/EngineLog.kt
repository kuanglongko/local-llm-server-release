package com.xiaowan.localinference

/**
 * 日志页的**内存取数层**：ring、过滤口径、清空、导出取数。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独一层（第 7 批 H6 的 EngineLog.kt）
 * ══════════════════════════════════════════════════════════════════════════
 * `LlmEngine.kt` 同时承担 native 桥、生成、嵌入、建库、探针与日志。
 * 其中"日志页看到什么"这一半只跟内存里的 ring + 两维过滤有关，
 * 却和 native 调用搅在同一份文件里 —— 改筛选口径要先翻过几千行 native 声明。
 *
 * 所以这里只装**纯内存**的那一半：
 *   · ring（探针模式 2000 行 / 常规 300 行，重复行折叠）；
 *   · 两维过滤口径（标签集合 + 关键字），以及先筛后取的 [recentLogs]；
 *   · 给导出/崩溃现场用的 [recentLogsUnfiltered]（过滤是界面开关，不是数据属性）；
 *   · [clearLogs]（行与口径一起清，否则下次进来是"筛选项还在、一行都没有"）。
 *
 * 仍然留在 `LlmEngine` 的：`pushLog` 的**副作用编排**（探针直写、落盘、
 * `HtpProbe`、单调钟打点）。那些与 native / 文件 / 看门狗耦合，不往下搬；
 * 这里只负责"把一行放进内存并折叠"。`LlmEngine` 保留同名的薄门面，
 * 调用点一个都不用改。
 */
object EngineLog {

    /** UI 侧日志钩子：EngineActivity 设置后，原生日志同时转发到界面与 ring buffer。 */
    @Volatile var logSink: ((String) -> Unit)? = null

    private val logRing = ArrayDeque<String>()

    /**
     * 日志页的**取数口径**：标签多选的集合 + 任意关键字。两者都是空 = 不过滤。
     *
     * 过滤**必须在这一层做**，不能在 `EngineActivity.renderLog` 那 120 行渲染结果上做：
     * ring 是 2000 行、界面只渲染尾部 120 行，某个标签的行完全可能全部落在窗口之外 ——
     * 在渲染结果上过滤，用户看到的是**空白页**，而空白页在日志页的语义是"没记日志"。
     * 在 ring 上过滤时，取的是"整条 ring 里最后 120 条满足条件的行"，判据与用户的理解一致。
     *
     * ⚠ **写这两个字段的只有主线程**（`EngineActivity` 的一个入口），所以不变量
     * "至少有一维在筛"在同一次赋值里就能守住；`@Volatile` 而不是加锁，是因为
     * 写入方只有主线程、读取方是四个并发 `pushLog` 线程 —— 读到上一轮的值最多让
     * 某一帧多/少一行，而把它塞进 [logRing] 的临界区只会让日志风暴下的写入多等一次。
     */
    @Volatile private var logFilterKeys: Set<String> = emptySet()
    @Volatile private var logFilterKeyword: String = ""

    /**
     * 设置日志页的取数口径，**两维一次写完**。
     *
     * 为什么坚持在这一个方法里写、不把两个字段直接开放给 UI：关键字与档位是**同一个视图**
     * 的两维，分两次赋值的那一瞬间存在一个"只有一个维度旧值、另一个维度新值"的口径，
     * 而且**两维都空**这件事必须被明确处理（= 不过滤）—— 让 UI 自己判断"是不是该两维都置空"
     * 等于把这套语义复制到第二个地方，第一个改、第二个忘，就会退化成"点了没反应"。
     */
    fun setLogFilter(keys: Set<String>, keyword: String) {
        logFilterKeys = HashSet(keys)
        logFilterKeyword = keyword
    }

    /** 当前口径是不是"不过滤"（两维都空）。界面上的解释行要用它决定说不说自己。 */
    fun logFilterActive(): Boolean = logFilterKeys.isNotEmpty() || logFilterKeyword.isNotEmpty()

    /** 当前口径的档位集合（只读快照，给界面画选中态）。 */
    fun logFilterKeysNow(): Set<String> = logFilterKeys

    /** 当前口径的关键字（只读快照）。 */
    fun logFilterKeywordNow(): String = logFilterKeyword

    /**
     * 日志页的取数：与不过滤时**同一把锁、同一份顺序**，只是先筛后取尾。
     * 只有渲染日志页该用它 —— 其余用途见 [recentLogsUnfiltered]。
     */
    fun recentLogs(): List<String> = synchronized(logRing) {
        val keys = logFilterKeys
        val kw = logFilterKeyword
        if (keys.isEmpty() && kw.isEmpty()) logRing.toList()
        else logRing.filter {
            LogFileStore.LogFilter.matches(keys, it) &&
                LogFileStore.LogFilter.keywordMatches(kw, it)
        }
    }

    /**
     * **不受过滤档位影响**的全量取数，只给"导出"与"崩溃现场"用。
     *
     * 为什么必须与 [recentLogs] 分开：过滤是**界面上的一个视图开关**，不是数据的属性。
     * 让它渗进导出，用户点「导出本次」拿到的就是"当前那一档的几行" ——
     * 而他导出日志的场景恰恰是"页面看不全、要拿给别人看"，那时他需要的是**全部**；
     * 渗进崩溃现场更坏：`crash_last.txt` 会在某个档位恰好没有命中时变成空文件，
     * 而崩溃现场**只有这一次机会**。两个方向都不报错，只是第二次想导出时已经没有现场了。
     */
    fun recentLogsUnfiltered(): List<String> = synchronized(logRing) { logRing.toList() }

    /**
     * ring 里的**原始**行数（不受过滤影响）。
     *
     * 与 [recentLogs]`.size`（过滤后的命中数）必须分开：日志页的空白页提示要同时说
     * "这一档没有命中"和"整段日志其实有多少行"。只给命中数的话，用户没法区分
     * "这档真没有"与"ring 本来就空"—— 而这两种情况该做的事完全不同。
     */
    fun ringSize(): Int = synchronized(logRing) { logRing.size }

    /**
     * 清空**必须同时清过滤条件**（两维都清）：只清行不清条件时，用户下次进日志页看到的是
     * "上次选的筛选还在、但一行都没有" —— 与"日志坏了"不可区分，
     * 而这正是本仓库反复出现的「数据在、界面看不见」同族故障。
     */
    fun clearLogs() = synchronized(logRing) {
        logRing.clear(); ringBase = null; ringRep = 0
        logFilterKeys = emptySet(); logFilterKeyword = ""
    }

    private var ringBase: String? = null
    private var ringRep = 0

    /** 探针模式下 ring 放宽：原始 token 流不再折叠，300 行窗口一眨眼就满 */
    private const val PROBE_RING = 2000

    /**
     * 把一行放进内存 ring。
     *
     * @param probe 探针模式：**不折叠重复**（崩溃前最后那几条常常正是逐 token 的 "."），
     *   窗口放宽到 [PROBE_RING]。
     *
     * ⚠ 这里**不碰** `lastProgressAt`：探针模式下的日志行不得被当成"有进展"
     *（见 `LlmEngine.pushLog` 的说明）。单调钟打点留在调用方，本层只存字。
     */
    fun push(line: String, probe: Boolean) {
        synchronized(logRing) {
            if (probe) {
                logRing.addLast(line)
                while (logRing.size > PROBE_RING) logRing.removeFirst()
                return
            }
            // 连续重复行折叠成 "xxx ×N"：生成期 llama 会逐 token 往 stderr 打 "."，
            // 一次回答就是上百行，既占满 300 行的 ring（把关键行挤出窗口），
            // 也让日志页全是噪声。折叠只作用于内存视图与文件，原始 token 流在聊天页仍有。
            if (line == ringBase) {
                ringRep++
                logRing.removeLast()
                logRing.addLast("$line ×$ringRep")
            } else {
                ringBase = line
                ringRep = 1
                logRing.addLast(line)
                while (logRing.size > 300) logRing.removeFirst()
            }
        }
    }

    /** 把一行推给界面（`EngineActivity` 的日志页 / 状态行）。 */
    fun emit(line: String) {
        try { logSink?.invoke(line) } catch (_: Exception) {}
    }
}
