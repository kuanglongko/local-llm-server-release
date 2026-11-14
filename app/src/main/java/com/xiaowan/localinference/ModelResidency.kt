package com.xiaowan.localinference

/**
 * 模型驻留项的**只读快照**：LRU 判决只吃这一份，不直接读状态机 / native。
 *
 * 为什么另定义 [ModelResidencyState] 而不是直接引用 `ModelLifecycleCore.State`：
 * LRU 判决要在宿主上穷举单测，而 `ModelLifecycleCore` 的完整构造需要注入加载器、
 * 计时器、卸载器。把"状态"抽成一个五值枚举，判决层就只依赖数据，不依赖任何
 * 可执行副作用；适配层负责把 core 的真实状态映射过来。
 */
enum class ModelResidencyState { UNLOADED, LOADING, LOADED, UNLOADING, ERROR }

/**
 * 一个模型运行时在某一刻的驻留事实。
 *
 * [lastUsedAtMs] 只在**拿到租约 / 推理结束**时更新，不在每次健康检查时更新；
 * 否则一个只被轮询、从未被推理的模型会永远"刚用过"，LRU 永远赶不走它。
 */
data class ModelResidency(
    /** 内部身份（路径 + 参数指纹）。 */
    val key: String,
    /** 显示名，只进日志。 */
    val label: String,
    val state: ModelResidencyState,
    /** 当前持有的推理租约数。>0 时绝不允许驱逐。 */
    val activeLeases: Int,
    /** 正在等待这个模型加载完成的请求数。>0 时同样不驱逐。 */
    val waiters: Int,
    /** 最近一次真正被推理使用的时间（毫秒，单调时钟）。 */
    val lastUsedAtMs: Long,
    /** 进入 LOADED 的时间；用于"刚加载不许立刻赶走"的最小驻留窗口。 */
    val loadedAtMs: Long,
    /** 估算占用（权重 + KV + compute buffer + 投机开销）。0 = 未知。 */
    val estimatedBytes: Long,
    /** 用户标记常驻。 */
    val pinned: Boolean,
    /** 动态选择的优先级；LRU 排序最后才看它。 */
    val priority: Int = 0,
) {
    fun describe(): String = if (label.isNotBlank()) label else key
}

enum class MemoryPressure { NONE, HIGH, CRITICAL }

/**
 * 一次驱逐计划。
 *
 * [enough] = 按 [reclaimedBytes] 估算是否已经满足触发条件。估算不保证 native
 * 真的归还了这么多内存，所以调用方执行完仍要重新采样；`enough=false` 只表示
 * "候选已经用完，仍不满足"，不是"一定失败"。
 */
data class EvictionPlan(
    val keys: List<String>,
    val reclaimedBytes: Long,
    val enough: Boolean,
) {
    val isEmpty: Boolean get() = keys.isEmpty()
}

/**
 * 内存 LRU 的**纯策略**：谁可以被赶走、先赶谁、赶几个。
 *
 * 这里**不执行**卸载，也不碰状态机。执行方必须按计划逐个调用
 * `tryEvict`：状态机在那一步会再次原子检查活跃租约/等待者；这里算出的
 * 候选只是"看起来可驱逐"，不能替代那一道检查。
 */
object ModelResidencyPolicy {

    /**
     * 真正的临界线：与用户设的百分比无关。到 95% 就是"系统随时可能杀进程"，
     * 此时不再保留最后一份驻留。
     */
    private const val CRITICAL_USED_PCT = 95

    /** 配了保底可用时，可用跌破保底的 1/2 也视为临界。 */
    private const val CRITICAL_FLOOR_DIVISOR = 2L

    /**
     * 是否具备被驱逐的**静态条件**。
     *
     * 这些条件与"闲了多久"无关：容量不足时，一个刚加载、但没有任何请求的
     * 模型同样可以赶走（只是受 [minResidencyMs] 保护）。空闲时间由
     * [selectForIdle] 另外筛。
     */
    fun canEvict(
        model: ModelResidency,
        nowMs: Long,
        minResidencyMs: Long = 0,
        protectKeys: Set<String> = emptySet(),
    ): Boolean {
        if (model.key in protectKeys) return false
        if (model.pinned) return false
        if (model.state != ModelResidencyState.LOADED) return false
        if (model.activeLeases > 0 || model.waiters > 0) return false
        // loadedAt 未知（0）时不拿最小驻留窗口拦：宁可让它成为候选，也不
        // 因为一个没报的时间戳永远占着内存。
        if (minResidencyMs > 0 && model.loadedAtMs > 0 &&
            nowMs - model.loadedAtMs < minResidencyMs) return false
        return true
    }

    /** 空闲时间是否已到（只用于 idle 触发，不用于容量触发）。 */
    fun idleReached(model: ModelResidency, nowMs: Long, idleMs: Long): Boolean =
        idleMs <= 0 || (model.lastUsedAtMs > 0 && nowMs - model.lastUsedAtMs >= idleMs)

    /** LRU 顺序：最近最少使用在前；同一时刻按占用大者优先，最后按 key 稳定排序。 */
    fun lruOrder(
        models: List<ModelResidency>,
        nowMs: Long,
        minResidencyMs: Long = 0,
        protectKeys: Set<String> = emptySet(),
    ): List<ModelResidency> = models
        .filter { canEvict(it, nowMs, minResidencyMs, protectKeys) }
        .sortedWith(
            compareBy<ModelResidency> { it.lastUsedAtMs }
                .thenByDescending { it.estimatedBytes }
                .thenBy { it.key }
        )

    /**
     * 空闲卸载：所有"闲够久、且静态可驱逐"的模型，按 LRU 全排。
     *
     * 为什么一次返回多个而不是只返回一个：空闲扫描是周期性后台任务，
     * 一次只赶一个会让"三份都闲了"拖三个扫描周期。执行方仍应逐个 `tryEvict`，
     * 失败一个就跳过，不影响后面的候选。
     */
    fun selectForIdle(
        models: List<ModelResidency>,
        nowMs: Long,
        idleMs: Long,
        minResidencyMs: Long = 0,
        protectKeys: Set<String> = emptySet(),
    ): EvictionPlan {
        val candidates = lruOrder(models, nowMs, minResidencyMs, protectKeys)
            .filter { idleReached(it, nowMs, idleMs) }
        val bytes = candidates.sumOf { it.estimatedBytes.coerceAtLeast(0L) }
        return EvictionPlan(candidates.map { it.key }, bytes, enough = candidates.isNotEmpty())
    }

    /**
     * 容量驱逐：为新模型腾出 [requiredBytes]。
     *
     * 当 [requiredBytes] <= 0（未知大小）时，只赶一个最久未用的；这是"至少
     * 腾一个位置"的语义，避免为了一个未知大小把整个池清空。
     */
    fun selectForCapacity(
        models: List<ModelResidency>,
        nowMs: Long,
        requiredBytes: Long,
        minResidencyMs: Long = 0,
        protectKeys: Set<String> = emptySet(),
    ): EvictionPlan {
        val ordered = lruOrder(models, nowMs, minResidencyMs, protectKeys)
        if (ordered.isEmpty()) return EvictionPlan(emptyList(), 0, enough = false)
        if (requiredBytes <= 0) {
            val one = ordered.first()
            return EvictionPlan(
                listOf(one.key), one.estimatedBytes.coerceAtLeast(0L), enough = true)
        }
        val out = ArrayList<String>()
        var got = 0L
        for (m in ordered) {
            out += m.key
            got += m.estimatedBytes.coerceAtLeast(0L)
            if (got >= requiredBytes) break
        }
        return EvictionPlan(out, got, enough = got >= requiredBytes)
    }

    /**
     * 内存压力驱逐：从 LRU 里逐个挑，直到**已用占比**回到 [lowPct] 以下，
     * 或**可用内存**回到 [minFreeBytes] 之上。
     *
     * **HIGH 档最多驱逐到剩一份 LOADED**：单模型配置（`最多驻留 1`）下，
     * 把最后一份也赶走只会让下一轮请求立刻重载，并把 KV 账本一起作废。
     * 空闲释放由每份 runtime 自己的计时器负责，不靠这里。真正要保命的
     * [MemoryPressure.CRITICAL] 才允许清空。
     *
     * [availableBytes] / [totalBytes] 由调用方从 `ActivityManager.MemoryInfo`
     * 读取；`totalBytes <= 0` 或 `availableBytes < 0` 表示未知，返回空计划，
     * 不猜、不驱逐。
     */
    fun selectForMemory(
        models: List<ModelResidency>,
        nowMs: Long,
        availableBytes: Long,
        totalBytes: Long,
        highPct: Int,
        lowPct: Int,
        minFreeBytes: Long = 0,
        minResidencyMs: Long = 0,
        protectKeys: Set<String> = emptySet(),
    ): EvictionPlan {
        if (totalBytes <= 0 || availableBytes < 0) {
            return EvictionPlan(emptyList(), 0, enough = false)
        }
        val pressure = memoryPressure(availableBytes, totalBytes, highPct, lowPct, minFreeBytes)
        if (pressure == MemoryPressure.NONE) {
            return EvictionPlan(emptyList(), 0, enough = true)
        }
        val ordered = lruOrder(models, nowMs, minResidencyMs, protectKeys)
        val loadedCount = models.count { it.state == ModelResidencyState.LOADED }
        val out = ArrayList<String>()
        var reclaimed = 0L
        for (m in ordered) {
            if (memoryRelieved(availableBytes + reclaimed, totalBytes, lowPct, minFreeBytes)) break
            // HIGH 档保住池里最后一份：它正是下一轮请求要复用的那份，赶走它
            // 只会换来一次重载 + 一次全量 prefill。CRITICAL 才允许清空。
            if (pressure == MemoryPressure.HIGH && out.size >= loadedCount - 1) break
            out += m.key
            reclaimed += m.estimatedBytes.coerceAtLeast(0L)
        }
        return EvictionPlan(
            out, reclaimed,
            enough = memoryRelieved(availableBytes + reclaimed, totalBytes, lowPct, minFreeBytes))
    }

    /**
     * 当前内存压力档。未知（总量 <=0 / 可用 <0）一律 [MemoryPressure.NONE]。
     *
     * 触发条件：
     * · [MemoryPressure.HIGH] = **已用占比 ≥ [highPct] 且 可用 < [minFreeBytes]**。
     *   两个条件是**与**关系：16GB 设备已用 83% 时仍有 2~3GB 可用，只看百分比
     *   会把本来够用的模型赶走。`minFreeBytes = 0` = 用户显式关掉绝对下限，
     *   退回"只看百分比"的旧行为。
     * · [MemoryPressure.CRITICAL] = 已用 ≥ 95%，或可用跌破保底的 1/2。
     *   这一档不看用户设的水位，允许清空池子。
     */
    fun memoryPressure(
        availableBytes: Long,
        totalBytes: Long,
        highPct: Int,
        lowPct: Int,
        minFreeBytes: Long = 0,
    ): MemoryPressure {
        if (totalBytes <= 0 || availableBytes < 0) return MemoryPressure.NONE
        if (availableBytes == 0L) return MemoryPressure.CRITICAL
        val usedPct = ((totalBytes - availableBytes) * 100.0 / totalBytes).toInt()
        // 保底设得比总内存还大时按总内存收口，否则 floor/3 会把所有档都判成临界。
        val floor = minFreeBytes.coerceIn(0L, totalBytes)
        if (usedPct >= CRITICAL_USED_PCT) return MemoryPressure.CRITICAL
        if (floor > 0 && availableBytes < floor / CRITICAL_FLOOR_DIVISOR) {
            return MemoryPressure.CRITICAL
        }
        val high = highPct.coerceIn(1, 99)
        if (usedPct < high) return MemoryPressure.NONE
        if (floor > 0 && availableBytes >= floor) return MemoryPressure.NONE
        return MemoryPressure.HIGH
    }

    /**
     * 是否已经回到"可以停止驱逐"的水位。
     *
     * 百分比与绝对保底是**或**关系：任意一个目标达成就停。否则在 16GB 设备上
     * "降到 low=65%" 往往根本不可达（模型只占其中 1~2GB），会演变成每 20 秒
     * 反复赶同一份模型。
     */
    fun memoryRelieved(
        availableBytes: Long,
        totalBytes: Long,
        lowPct: Int,
        minFreeBytes: Long = 0,
    ): Boolean {
        if (totalBytes <= 0 || availableBytes < 0) return false
        val floor = minFreeBytes.coerceIn(0L, totalBytes)
        if (floor > 0 && availableBytes >= floor) return true
        val low = lowPct.coerceIn(1, 99)
        val usedPct = ((totalBytes - availableBytes) * 100.0 / totalBytes).toInt()
        return usedPct <= low
    }
}
