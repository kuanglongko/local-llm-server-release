package com.xiaowan.localinference

/**
 * 多模型池：把 [ModelRuntime] 按 key 管理起来，并负责**容量**这一层。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它不做什么
 * ══════════════════════════════════════════════════════════════════════════
 * · 不解析请求里的模型名 —— 那是 [ModelRouter] 的职责；
 * · 不碰 native —— 加载/卸载由注入的 [ModelRuntime] 回调执行；
 * · 不在持有 registry 锁时加载/卸载 —— 那会把一条几十秒的 native 调用
 *   变成所有请求的全局停顿，且与 core 的锁形成反向获取。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 容量判决
 * ══════════════════════════════════════════════════════════════════════════
 * `maxLoaded` 是**硬上限**。达到上限时按 [ModelResidencyPolicy.selectForCapacity]
 * 选 LRU 候选，调用 `requestUnload` 并等它真正 UNLOADED，再创建新 runtime。
 * 有活跃租约 / 等待者 / pinned 的模型不会被选；因此"全部在用"时请求会得到
 * 可重试的容量错误，而不是被静默换成一个错误模型。
 *
 * ⚠ 这里等待的是**状态机的状态**，不是"卸载函数返回"：现有 core 的卸载是
 *   异步提交的，`requestUnload` 只登记意图；必须看到 UNLOADED 才能把 key
 *   从 map 里移走，否则新 runtime 会和旧 native 资源短暂并存。
 */
class ModelRuntimeRegistry(
    private val factory: (ModelRuntimeSpec) -> ModelRuntime,
    private val maxLoaded: () -> Int,
    private val loadWaitMs: () -> Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
) {

    private val lock = Any()

    /** 已创建的运行时。创建后即使处于 UNLOADED 也保留，便于复用配置。 */
    private val runtimes = LinkedHashMap<String, ModelRuntime>()

    /** 正在创建中的 key：同一 key 只允许一个 loader，其他请求等它。 */
    private val creating = HashSet<String>()

    fun acquire(
        spec: ModelRuntimeSpec,
        cancel: () -> Boolean = { false },
        progress: (String) -> Unit = {},
    ): AcquireResult {
        val deadline = clock() + loadWaitMs().coerceAtLeast(1L)
        // 这一轮请求为了「装下」而释放的空闲模型份数。只用于失败文案：
        // 用户需要知道服务端确实试过腾内存，而不是一句干巴巴的「加载失败」。
        var oomEvicted = 0
        while (true) {
            if (safeCancel(cancel)) return AcquireResult.Canceled
            // 状态机的空闲计时会把模型卸到 UNLOADED，但 runtime 仍留在表里。
            // 这些条目已经不占内存，不能继续挤占 maxLoaded 的名额，否则
            // 「两份模型都闲到自动卸载」之后，第三份会一直判"池已满"直到超时。
            pruneUnloaded()
            var existing: ModelRuntime? = null
            var reserved = false
            var waitForCreator = false
            synchronized(lock) {
                existing = runtimes[spec.key]
                if (existing == null) {
                    if (spec.key in creating) {
                        waitForCreator = true
                    } else if (runtimes.size + creating.size < maxLoaded().coerceAtLeast(1)) {
                        creating.add(spec.key)
                        reserved = true
                    }
                }
            }

            val rtNow = existing
            if (rtNow != null) {
                val r = rtNow.acquire(cancel, progress)
                if (r is AcquireResult.Failed && r.resourceExhausted) {
                    oomEvicted += evictIdleForOom(spec, deadline)
                    if (oomEvicted > 0 && clock() < deadline) continue
                }
                return finalize(r, spec, oomEvicted)
            }

            if (waitForCreator) {
                if (clock() >= deadline) {
                    return AcquireResult.Failed(
                        "模型「${spec.describe()}」正在被另一个请求加载，等待超时", retryable = true)
                }
                sleepBriefly()
                continue
            }

            if (reserved) {
                var newRt: ModelRuntime? = null
                try {
                    newRt = factory(spec)
                } catch (t: Throwable) {
                    synchronized(lock) { creating.remove(spec.key); notifyAllLocked() }
                    return AcquireResult.Failed(
                        "创建模型运行时失败：${t.javaClass.simpleName}: ${t.message}",
                        resourceExhausted = LoadFailure.looksLikeResourceExhaustion(t.message))
                }
                synchronized(lock) {
                    runtimes[spec.key] = newRt
                    creating.remove(spec.key)
                    notifyAllLocked()
                }
                val r = newRt.acquire(cancel, progress)
                if (r is AcquireResult.Failed && r.resourceExhausted) {
                    oomEvicted += evictIdleForOom(spec, deadline)
                    if (oomEvicted > 0 && clock() < deadline) continue
                }
                return finalize(r, spec, oomEvicted)
            }

            // 容量不足：先按 LRU 腾位置。只有确认目标 key 已经卸载，才进入下一轮
            // 重新抢创建位；否则继续等（可能别的请求也在腾）。
            val plan = planCapacity(spec)
            if (plan.keys.isEmpty()) {
                if (clock() >= deadline) {
                    return AcquireResult.Failed(
                        "模型池已满（上限 ${maxLoaded()}），且没有可释放的空闲模型；" +
                            "请稍后重试或调大多模型驻留上限", retryable = true)
                }
                sleepBriefly()
                continue
            }
            val freed = evictAndWait(plan, deadline)
            if (!freed) {
                if (clock() >= deadline) {
                    return AcquireResult.Failed(
                        "模型池已满（上限 ${maxLoaded()}），等待空闲模型释放超时", retryable = true)
                }
                sleepBriefly()
            }
            // 无论是否全部腾出，都回到循环顶部重新看容量（可能已有别的请求先占位）。
        }
    }

    fun release(lease: RuntimeLease?) {
        lease?.release()
    }

    /** 服务停止 / 设置关闭：逐个登记卸载，但不阻塞调用线程；返回已登记的 key。 */
    fun requestUnloadAll(reason: String): List<String> {
        val all = synchronized(lock) { runtimes.values.toList() }
        all.forEach { it.requestUnload(reason) }
        return all.map { it.key }
    }

    /**
     * 按 key 登记卸载**单个**运行时（手动卸载用）；返回是否命中。
     *
     * 与 [requestUnloadAll] 同一条语义：只登记、不阻塞。runtime 自己的状态机决定
     * 什么时候真正 free —— 正在推理时排到空闲后，加载中则加载完立刻卸（见
     * `ModelLifecycleCore.requestUnload`）。因此调用方不需要先判断活跃租约。
     */
    fun requestUnload(key: String, reason: String): Boolean {
        val rt = synchronized(lock) { runtimes[key] } ?: return false
        rt.requestUnload(reason)
        return true
    }

    /** 等待所有运行时离开 LOADED/LOADING/UNLOADING；超时返回 false。 */
    fun awaitAllUnloaded(timeoutMs: Long = 5_000L): Boolean {
        val deadline = clock() + timeoutMs.coerceAtLeast(1L)
        while (true) {
            val busy = snapshot().any {
                it.state == ModelResidencyState.LOADED ||
                    it.state == ModelResidencyState.LOADING ||
                    it.state == ModelResidencyState.UNLOADING
            }
            if (!busy) return true
            if (clock() >= deadline) return false
            sleepBriefly()
        }
    }

    /** 空闲驱逐：按 LRU 选闲够久的，执行并等待。返回实际卸载的 key。 */
    fun evictIdle(
        idleMs: Long,
        minResidencyMs: Long = 0,
        protectKeys: Set<String> = emptySet(),
        timeoutMs: Long = loadWaitMs(),
    ): List<String> {
        val plan = ModelResidencyPolicy.selectForIdle(
            snapshot(), clock(), idleMs, minResidencyMs, protectKeys)
        return execute(plan, clock() + timeoutMs.coerceAtLeast(1L))
    }

    /** 内存压力驱逐：执行 [ModelResidencyPolicy.selectForMemory] 的计划。 */
    fun evictForMemory(
        availableBytes: Long,
        totalBytes: Long,
        highPct: Int,
        lowPct: Int,
        minFreeBytes: Long = 0,
        minResidencyMs: Long = 0,
        protectKeys: Set<String> = emptySet(),
        timeoutMs: Long = loadWaitMs(),
    ): List<String> {
        val plan = ModelResidencyPolicy.selectForMemory(
            snapshot(), clock(), availableBytes, totalBytes, highPct, lowPct,
            minFreeBytes, minResidencyMs, protectKeys)
        return execute(plan, clock() + timeoutMs.coerceAtLeast(1L))
    }

    fun snapshot(): List<ModelResidency> {
        val all = synchronized(lock) { runtimes.values.toList() }
        return all.map { it.snapshot() }
    }

    /**
     * 「常驻」设置变更后，把新标记同步到**已经存在**的 runtime。
     *
     * 只改设置、不改已建运行时是不够的：用户先加载 A 再把它设为常驻时，
     * A 的 runtime 里那份 `pinned` 仍是旧值，快照会继续报「非常驻」，
     * 空闲计时也不会被撤掉。这里逐个更新，并由 [ModelRuntime.updatePinned]
     * 当场重算计时。
     */
    fun refreshPinned(isPinned: (ModelRuntime) -> Boolean) {
        val all = synchronized(lock) { runtimes.values.toList() }
        all.forEach { it.updatePinned(isPinned(it)) }
    }

    fun runtime(key: String): ModelRuntime? = synchronized(lock) { runtimes[key] }

    fun loadedCount(): Int = snapshot().count { it.state == ModelResidencyState.LOADED }

    // ---- 内部 ----

    /**
     * 移除已经卸载且没有租约 / 等待者的 runtime。
     *
     * 只由 [acquire] 在容量判决前调用：它改变的是「池里还剩几个名额」这一件事。
     * 不在每轮 LRU 扫描里顺手清，是因为扫描与请求可以并发，留着条目不影响
     * 驱逐判决（[ModelResidencyPolicy] 只挑 LOADED）。
     */
    private fun pruneUnloaded() {
        synchronized(lock) {
            val it = runtimes.entries.iterator()
            while (it.hasNext()) {
                val rt = it.next().value
                val s = rt.snapshot()
                if ((s.state == ModelResidencyState.UNLOADED ||
                        s.state == ModelResidencyState.ERROR) &&
                    s.activeLeases == 0 && s.waiters == 0) {
                    it.remove()
                    log("[多模型] 清理已卸载运行时 ${s.describe()}")
                }
            }
        }
    }

    /**
     * 加载因**资源不足**失败后，先按 LRU 释放空闲模型，返回实际释放的份数。
     *
     * 只挑「空闲、未被保护、非目标」的 LOADED 模型（[ModelResidencyPolicy] 的
     * `canEvict` 已经排除了有活跃租约 / 等待者 / 常驻的）。正在推理的模型绝不
     * 因为「另一份装不下」而被抽走脚下的 ctx —— 那种失败比 OOM 本身更难排查。
     *
     * 返回 0 = 没有可释放的，调用方应停止重试并给出可操作的错误。
     */
    private fun evictIdleForOom(spec: ModelRuntimeSpec, deadline: Long): Int {
        // OOM 路径按**估算占用**腾：一次能腾够就不必让请求反复重试；估算未知
        // （<=0）时退回"只赶一个最久未用的"。
        val plan = planCapacity(spec, spec.estimatedBytes.coerceAtLeast(0L))
        if (plan.keys.isEmpty()) return 0
        log("[多模型] 加载 ${spec.describe()} 内存不足，先释放空闲模型再重试：" +
            plan.keys.joinToString("、"))
        val done = execute(plan, deadline)
        return done.size
    }

    /**
     * 资源不足的最终错误必须**说清三件事**：是内存问题、已经试过释放、用户能做什么。
     * 直接把 native 那句 `failed to allocate ...` 抛给客户端，读的人只会以为是代码 bug。
     */
    private fun finalize(r: AcquireResult, spec: ModelRuntimeSpec, evicted: Int): AcquireResult {
        if (r !is AcquireResult.Failed || !r.resourceExhausted) return r
        val tried = if (evicted > 0)
            "已释放 $evicted 份空闲模型后仍不足"
        else
            "池中没有可释放的空闲模型（其余模型都在推理或被设为常驻）"
        return r.copy(
            message = "内存不足，加载「${spec.describe()}」失败：$tried。${
                "请调小上下文长度 / 并发数、减少最多驻留份数，或关闭其他应用后重试。"}${
                "（原始错误：${r.message}）"}",
            retryable = true)
    }

    /**
     * 容量驱逐计划。`requiredBytes = 0`（默认）= 池是**按份数**限容的，只要腾出
     * 一个名额即可；OOM 重试路径才按估算占用一次腾够（见 [evictIdleForOom]）。
     */
    private fun planCapacity(incoming: ModelRuntimeSpec, requiredBytes: Long = 0L): EvictionPlan =
        ModelResidencyPolicy.selectForCapacity(
            snapshot(), clock(), requiredBytes,
            protectKeys = setOf(incoming.key))

    private fun evictAndWait(plan: EvictionPlan, deadline: Long): Boolean {
        var allFreed = true
        for (key in plan.keys) {
            val rt = synchronized(lock) { runtimes[key] } ?: continue
            rt.requestUnload("容量驱逐")
            if (!waitUnloaded(rt, deadline)) {
                allFreed = false
                continue
            }
            synchronized(lock) {
                // 只有确实离开 LOADED 才移除；否则保留，避免下次误判"已腾出"。
                if (rt.snapshot().state == ModelResidencyState.UNLOADED ||
                    rt.snapshot().state == ModelResidencyState.ERROR) {
                    runtimes.remove(key)
                    log("[多模型] 容量驱逐已释放 $key")
                }
            }
        }
        return allFreed
    }

    private fun execute(plan: EvictionPlan, deadline: Long): List<String> {
        if (plan.isEmpty) return emptyList()
        val done = ArrayList<String>()
        for (key in plan.keys) {
            val rt = synchronized(lock) { runtimes[key] } ?: continue
            rt.requestUnload("LRU 驱逐")
            if (!waitUnloaded(rt, deadline)) {
                log("[多模型] LRU 驱逐等待超时：$key（仍有活跃推理/等待者？）")
                continue
            }
            synchronized(lock) {
                if (rt.snapshot().state == ModelResidencyState.UNLOADED ||
                    rt.snapshot().state == ModelResidencyState.ERROR) {
                    runtimes.remove(key)
                    done += key
                    log("[多模型] LRU 驱逐已释放 $key")
                }
            }
        }
        return done
    }

    private fun waitUnloaded(rt: ModelRuntime, deadline: Long): Boolean {
        while (true) {
            val st = rt.snapshot().state
            if (st == ModelResidencyState.UNLOADED || st == ModelResidencyState.ERROR) return true
            // LOADING 时 requestUnload 只登记"加载完立即释放"；仍要等它走完。
            if (clock() >= deadline) return false
            sleepBriefly()
        }
    }

    private fun safeCancel(cancel: () -> Boolean): Boolean =
        runCatching { cancel() }.getOrDefault(false)

    private fun sleepBriefly() {
        try {
            Thread.sleep(20L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 调用方持 [lock]。Kotlin 的 `Any` 没有 notifyAll，显式转成 java.lang.Object。 */
    private fun notifyAllLocked() {
        (lock as java.lang.Object).notifyAll()
    }
}
