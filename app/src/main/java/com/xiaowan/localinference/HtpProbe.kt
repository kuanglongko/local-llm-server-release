package com.xiaowan.localinference

/**
 * 从日志里被动统计「HTP 到底有没有参与计算图」。
 *
 * 为什么需要它：进程「没崩」不等于 NPU 在算——设备被彻底踢出计算图后同样不崩，
 * 表现为 backend_ptrs.size() 骤降、HTPx-REPACK 权重行整段消失、全部层给了别的设备，
 * 等于悄悄关掉了 NPU。只看「崩没崩」会得出完全错误的结论。
 * 因此加载完成后自动打一行摘要，不必人工在几千行日志里 grep。
 *
 * 数据来源是 native 日志 + stderr（两者都汇流到 LlmEngine.pushLog）。本对象只做只读匹配、
 * 不改任何行为；reset() 清零，因此 CPU 回退重试后的摘要反映**最后一次**加载。
 *
 * 权重行的格式（注意 llama 还会打 "CPU Repack" 这种带空格的设备名）：
 * load_tensors:          HTP0 model buffer size =      <n> MiB
 * load_tensors:    HTP0-REPACK model buffer size =   <n> MiB
 * load_tensors:       OpenCL model buffer size =   <n> MiB
 * load_tensors:      CPU Repack model buffer size =  <n> MiB
 */
object HtpProbe {

    /** -1 = 日志里没出现该行（例如 native 侧根本没走到 llama_context 构造）。 */
    @Volatile var backends: Int = -1
        private set

    /** `offloaded 30/37 layers to GPU` —— GPU 计数含 OpenCL，不等于 HTP 层数。 */
    @Volatile var offloaded: Int = -1
        private set
    @Volatile var totalLayers: Int = -1
        private set

    /** `Hexagon Arch version v79`：DSP 段库实际加载的架构版本。 */
    @Volatile var arch: String? = null
        private set

    /** `allocated op-queue : batch-size 1024 depth 16 …`（设备初始化期，两种结果里都有，只作信息）。 */
    @Volatile var opQueue: String? = null
        private set

    /** `failed to query hwinfo (0x…)` 次数：skel 与本机 CDSP driver 不匹配的首要嫌疑征兆。 */
    @Volatile var hwinfoFail: Int = 0
        private set

    /** 设备名 → 权重 MiB。设备名归一化后形如 HTP0 / HTP0-REPACK / OpenCL / CPU / CPU-Repack。 */
    private val bufs = LinkedHashMap<String, Double>()

    private val reBackends = Regex("""backend_ptrs\.size\(\)\s*=\s*(\d+)""")
    private val reOffload = Regex("""offloaded\s+(\d+)/(\d+)\s+layers""")
    private val reArch = Regex("""Arch version\s+(v\d+)""")
    private val reQueue = Regex("""allocated op-queue\s*:\s*(.+)""")

    /**
     * 设备名用「非空格词 + 可选 -REPACK」显式匹配，比宽松通配更安全：
     * 时间戳 `[hh:mm:ss.SSS]`、`[stderr]`、`load_tensors:` 这些前缀都被字符集天然排除。
     */
    private val reBuf = Regex("""\b(CPU Repack|HTP\d+(?:-REPACK)?|OpenCL|CPU|CUDA\d*|Vulkan|SYCL)\s+model buffer size\s*=\s*([\d.]+)\s*MiB""")

    /** native 偶尔把同一行重发（callback 与 stderr 双路），用它防止重复累加。 */
    private val seen = HashSet<String>()

    /** 每层真正落在哪个设备。之前只能看到权重 MiB，看不到「哪几层归 NPU」，
     * 于是把「Q8_0 100% 受算」当成「NPU 在主导」，实际默认模式只有 25/37 层在 HTP。 */
    @Volatile var htpLayers = 0; private set
    @Volatile var gpuLayers = 0; private set
    @Volatile var cpuLayers = 0; private set
    private val reAssign = Regex("""assigned to device ([A-Za-z0-9]+)""")

    /**
     * 设备池登记顺序。llama 按 `using device` 出现的次序切层，
     * mp.tensor_split 也按同一个下标索引，所以顺序必须在运行时记录，不能靠猜。
     * 顺带记下各自上报的空闲内存：有的设备一律上报 0 MiB，
     * 却仍拿到大部分层 → 份额并不是由这些上报值算出来的。
     */
    @Volatile private var pool = emptyList<Pair<String, Int>>()
    private val reDev = Regex("""using device (\S+).*? - (\d+) MiB free""")

    fun reset() {
        backends = -1; offloaded = -1; totalLayers = -1
        arch = null; opQueue = null; hwinfoFail = 0
        bufs.clear(); seen.clear()
        htpLayers = 0; gpuLayers = 0; cpuLayers = 0
        pool = emptyList()
    }

    /** 每个日志行喂一次。用便宜的关键词预筛，避免对上万行跑 6 个正则。 */
    fun observe(line: String) {
        if (line.length < 8 || line.length > 600) return
        if (!(line.contains("HTP") || line.contains("backend_ptrs") || line.contains("offloaded") ||
                line.contains("op-queue") || line.contains("hwinfo") || line.contains("Arch version") ||
                line.contains("model buffer size") || line.contains("assigned to device") ||
                line.contains("using device"))
        ) return
        runCatching {
            if (line.contains("using device")) {
                reDev.find(line)?.let { md ->
                    val dn = md.groupValues[1]
                    if (pool.none { it.first == dn })
                        pool = pool + (dn to (md.groupValues[2].toIntOrNull() ?: -1))
                }
                return@runCatching
            }
            if (line.contains("assigned to device")) {
                if (seen.add(line)) reAssign.find(line)?.groupValues?.get(1)?.let { dn ->
                    when {
                        dn.startsWith("HTP") -> htpLayers++
                        dn.startsWith("CPU") -> cpuLayers++
                        else -> gpuLayers++
                    }
                }
                return@runCatching
            }
            if (line.contains("model buffer size")) {
                if (!seen.add(line)) return@runCatching   // 整行去重：重发不再累加
                for (m in reBuf.findAll(line)) {
                    val key = m.groupValues[1].trim().replace(' ', '-')
                    bufs[key] = (bufs[key] ?: 0.0) + (m.groupValues[2].toDoubleOrNull() ?: 0.0)
                }
                return@runCatching
            }
            if (line.contains("backend_ptrs"))
                reBackends.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { backends = it }
            if (line.contains("offloaded"))
                reOffload.find(line)?.let {
                    offloaded = it.groupValues[1].toIntOrNull() ?: offloaded
                    totalLayers = it.groupValues[2].toIntOrNull() ?: totalLayers
                }
            if (line.contains("Arch version")) reArch.find(line)?.let { arch = it.groupValues[1] }
            if (line.contains("allocated op-queue"))
                reQueue.find(line)?.let { opQueue = it.groupValues[1].trim() }
            if (line.contains("failed to query hwinfo")) hwinfoFail++
        }
    }

    private fun sum(prefix: String): Double =
        bufs.filterKeys { it.startsWith(prefix) }.values.sum()

    /** HTP 设备数：HTP0..HTPn 去重（不含 -REPACK 重复计数）。 */
    private fun htpDevs(): Int =
        bufs.keys.mapNotNull { Regex("""^HTP(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct().size

    /** 设备池按登记顺序打印，下标即 tensor_split 索引。 */
    fun poolDesc(): String =
        if (pool.isEmpty()) ""
        else pool.mapIndexed { i, d -> "#%d %s(%dMiB空闲)".format(i, d.first, d.second) }.joinToString(" ")

    /** NPU 层份额（%）；返回 -1 表示尚未探测到，调用方不许把 0 当结论。 */
    fun npuSharePct(): Int {
        val n = htpLayers + gpuLayers + cpuLayers
        return if (n == 0) -1 else htpLayers * 100 / n
    }

    /** HTP 在设备池里的下标；找不到返回 -1。 */
    fun htpPoolIndex(): Int = pool.indexOfFirst { it.first.startsWith("HTP") }

    /** 一行运行时摘要：层落点 + 权重落点 + NPU份额 + 设备池。没跑到 load_tensors 时返回空串，UI 不许拿它编话。 */
    fun measured(): String {
        val n = htpLayers + gpuLayers + cpuLayers
        if (n == 0 && bufs.isEmpty()) return ""
        val htpW = sum("HTP"); val gpuW = sum("OpenCL")
        val base = "%d/%d层在NPU(HTP=%d GPU=%d CPU=%d) ｜ 权重 HTP=%.0f OpenCL=%.0f CPU=%.0f MiB".format(
            htpLayers, n, htpLayers, gpuLayers, cpuLayers, htpW, gpuW, sum("CPU"))
        // 份额是本轮关键线索：4B 与 0.6B 体积差 6 倍，NPU 层份额都稳在 ~68% → 配额制，不是容量制。
        val share = if (n == 0) "" else
            " ｜ NPU份额=" + (htpLayers * 100 / n) + "%" +
                (if (gpuW < 1.0) "" else " 权重比=" + "%.2f".format(htpW / gpuW) + ":1")
        return base + share + (if (pool.isEmpty()) "" else " ｜ 池 " + poolDesc())
    }

    fun summary(): String {
        val htp = sum("HTP")
        val cl = sum("OpenCL")
        val cpu = sum("CPU")
        val parts = ArrayList<String>(9)
        parts += "backend=" + (if (backends >= 0) backends.toString() else "?")
        parts += if (htpDevs() > 0) "HTP=%d设备/%.0fMiB".format(htpDevs(), htp) else "HTP=0"
        parts += "OpenCL=%.0fMiB".format(cl)
        if (cpu > 0) parts += "CPU=%.0fMiB".format(cpu)
        if (offloaded >= 0) parts += "offload=$offloaded/${if (totalLayers > 0) totalLayers else "?"}"
        arch?.let { parts += "arch=$it" }
        opQueue?.let { parts += "op-queue=有" }
        if (hwinfoFail > 0) parts += "hwinfo失败=$hwinfoFail"
        val verdict = when {
            htp <= 0.0 ->
                "判定：HTP 未拿到任何权重 → 本轮等同关 NPU（跑的是 OpenCL/CPU），不能当作 HTP 成功"
            htpDevs() > 0 ->
                "判定：HTP 在计算图内 → 本轮「崩/不崩」才是有效的 HTP 路径结果"
            else ->
                "判定：日志里没出现权重分配行（可能未到 load_tensors），请结合原始行判断"
        }
        return LogFileStore.TAG_HTP + " " + parts.joinToString(" ") + " ｜ " + verdict
    }
}
