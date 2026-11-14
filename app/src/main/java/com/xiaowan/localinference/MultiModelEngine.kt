package com.xiaowan.localinference

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * 多模型池的 Android 侧门面（Stage 3）。
 *
 * 职责链固定为：
 *
 *     ModelAccess.acquire
 *       → 这里选模型（[ModelRouter]，候选来自 [ModelLibrary]）
 *       → [ModelRuntimeRegistry] 管容量 / 状态机 / LRU
 *       → [LlmEngine] 做真正的 native 加载与推理（线程绑定到该 runtime）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么有一把「整段推理」的进程级锁
 * ══════════════════════════════════════════════════════════════════════════
 * Stage 3 的多模型是「多份**驻留** + 单路推理」：不同模型可以同时躺在内存里，
 * 但同一时刻只让一路真正解码。原因是 Kotlin 侧还有一批进程级状态（[SlotScheduler]
 * 表、`LlmEngine.genActive`、加载摘要、投机请求位）尚未按 runtime 拆分；两路
 * 同时解码会让 A 的 slot 读数被 B 覆盖 —— 不崩、不报错，只是读数与取消都指错人。
 *
 * 把 `execLock` 持到**租约归还**而不是只包加载，换来的是：
 *   · LRU 驱逐不可能在解码中途发生（活跃租约本来也会拦住它）；
 *   · 切换 runtime 时重置 [SlotScheduler] 不会踩到另一路的租约；
 *   · 读数是「最后一次推理用的那份」，与旧单模型语义一致。
 *
 * Stage 4 会把 slot 表/生成计数拆成每 runtime 一份，然后才能把这把锁收窄到
 * 加载/卸载（见 `SlotScheduler` 的实例化改造）。
 *
 * ⚠ 默认关闭（[MultiModelSettings.enabled]）。关闭时本对象没有任何调用者，
 *   旧单模型路径逐字节不变。
 */
object MultiModelEngine {

    @Volatile private var appCtx: Context? = null

    /**
     * 最近一次多模型路由的结论（模型 + 原因），只用于 `/health` 与诊断。
     *
     * 为什么不能只写日志：用户看到"答非所问"时，最需要回答的是"这次到底按
     * 哪条规则选了谁"；日志会被后续请求刷掉，且手机上看日志不方便。
     */
    @Volatile private var lastRoute: String? = null

    fun lastRoute(): String? = lastRoute

    /** 整段推理的互斥（理由见文件头）。用可重入锁，避免加载回调里再进一次。 */
    private val execLock = ReentrantLock()

    private val scheduler: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "multimodel-idle").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }

    private val unloadExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "multimodel-unload").apply { isDaemon = true }
        }
    }

    /**
     * LRU / 内存治理的**独立**调度线程。不复用 [scheduler]：单份模型的空闲
     * 卸载任务也排在那一根线程上，而一次驱逐最多等 `loadWaitMs`（默认 15s）；
     * 混在一起会让「三份都闲了」的卸载互相拖后。
     */
    private val governorScheduler: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "multimodel-governor").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }

    /** 治理扫描周期。比默认空闲时间（60s）短，保证「刚闲够就能被看到」。 */
    private const val GOVERNOR_PERIOD_MS = 20_000L

    /**
     * 内存高水位驱逐时的**最小驻留窗口**。不能用「空闲释放秒数」：用户把空闲
     * 设成 24h 时，那份模型会在内存已经紧张时仍被这条窗口挡住。10s 只用来
     * 防止"刚加载完、还没被用就被赶走"的抖动。
     */
    private const val MEMORY_EVICT_MIN_RESIDENCY_MS = 10_000L

    @Volatile private var governorTask: java.util.concurrent.ScheduledFuture<*>? = null

    @Volatile private var registry: ModelRuntimeRegistry? = null

    /** runtime.key -> native runtimeId。id 只增不复用。 */
    private val idsByKey = ConcurrentHashMap<String, Int>()
    private val nextRuntimeId = AtomicInteger(1)

    fun init(context: Context) {
        appCtx = context.applicationContext ?: context
        // 开关是持久化的：上次开着的话，进程一起来就把治理定时器接上。
        // 关闭时 syncGovernor 只做一次读设置 + 什么都不排。
        syncGovernor()
    }

    /**
     * 设置页切换总开关后调用：打开 = 接上治理定时器；关闭 = 停表并要求
     * registry 释放所有运行时（不阻塞调用线程）。
     */
    fun onEnabledChanged(enabled: Boolean) {
        if (enabled) {
            syncGovernor()
        } else {
            stopGovernor()
            requestUnloadAll("多模型已关闭")
        }
    }

    /** 按当前设置启动 / 停止治理定时器。幂等。 */
    fun syncGovernor() {
        val ctx = appCtx ?: HttpApi.appContext ?: return
        if (MultiModelSettings.enabled(ctx)) startGovernor() else stopGovernor()
    }

    @Synchronized
    private fun startGovernor() {
        val cur = governorTask
        if (cur != null && !cur.isCancelled && !cur.isDone) return
        governorTask = governorScheduler.scheduleWithFixedDelay({
            runCatching { governorTick() }
                .onFailure { LlmEngine.uiLog("[多模型] 治理任务异常：${it.message}") }
        }, GOVERNOR_PERIOD_MS, GOVERNOR_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun stopGovernor() {
        governorTask?.cancel(false)
        governorTask = null
    }

    /**
     * 一次治理扫描：**只做内存高水位**。
     *
     * 空闲释放由每份运行时自己的 [ModelRuntime] 计时器负责，扫表这里不再做
     * 第二遍「谁闲够了」：两条路径用同一个 `idleSeconds` 各自判断，只会在
     * 边界附近互相抢（一条刚排上计时、另一条先赶走）—— 更糟的是它们对
     * "最小驻留"的理解不同，日志里会出现两份互相矛盾的释放原因。
     *
     * **用 tryLock**：正在推理时直接跳过这一轮 —— 驱逐本来就不会碰活跃租约，
     * 但跳过能让"这一轮不打断任何推理"这条边界只由一把锁决定，而不是靠两个
     * 各自判断。下一轮扫描会再来。
     */
    private fun governorTick() {
        val ctx = appCtx ?: HttpApi.appContext ?: return
        if (!MultiModelSettings.enabled(ctx)) return
        val reg = registry ?: return
        if (!execLock.tryLock()) return
        try {
            val pinned = protectedKeys(ctx)
            val mem = readMemory(ctx) ?: return
            if (mem.total <= 0L || mem.avail < 0L) return
            val high = MultiModelSettings.memoryHighPct(ctx)
            // 触发/停止判据只由 [ModelResidencyPolicy] 一处决定（已用占比达到
            // high% 触发、回到 low% 停），这里不再用"可用占比"预判一次 ——
            // 两套口径会在阈值附近互相矛盾（预判跳过、策略却认为该驱逐）。
            val low = MultiModelSettings.memoryLowPct(ctx)
            val minFree = MultiModelSettings.minFreeMb(ctx).toLong() * 1024L * 1024L
            val byMem = reg.evictForMemory(
                availableBytes = mem.avail,
                totalBytes = mem.total,
                highPct = high,
                lowPct = low,
                minFreeBytes = minFree,
                minResidencyMs = MEMORY_EVICT_MIN_RESIDENCY_MS,
                protectKeys = pinned,
            )
            if (byMem.isNotEmpty()) {
                val usedMb = (mem.total - mem.avail) / (1024 * 1024)
                val totalMb = mem.total / (1024 * 1024)
                val availMb = mem.avail / (1024 * 1024)
                val usedPct = ((mem.total - mem.avail) * 100.0 / mem.total).toInt()
                LlmEngine.uiLog(
                    "[多模型] 内存高水位（已用 ${usedMb}MB / 共 ${totalMb}MB = ${usedPct}%，" +
                        "可用 ${availMb}MB，阈值 ${high}%→${low}%，" +
                        "保底 ${minFree / (1024 * 1024)}MB）释放 ${byMem.size} 份")
            }
        } finally {
            execLock.unlock()
        }
    }

    private class MemorySnapshot(val avail: Long, val total: Long)

    private fun readMemory(ctx: Context): MemorySnapshot? = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return@runCatching null
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        MemorySnapshot(info.availMem, info.totalMem)
    }.getOrNull()

    fun isEnabled(ctx: Context? = null): Boolean {
        val c = ctx ?: appCtx ?: HttpApi.appContext ?: return false
        return MultiModelSettings.enabled(c)
    }

    /**
     * 一次推理的租约：持有 registry 租约 + 当前线程的 runtime 绑定 + `execLock`。
     * 三者**同时**生效、同时归还；任何一步失败都会回滚已经拿到的那几步。
     */
    class Lease internal constructor(
        private val runtimeId: Int,
        private val previousBinding: Int,
        private val runtimeLease: RuntimeLease,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            try {
                LlmEngine.endRuntimeSession(previousBinding)
            } finally {
                runtimeLease.release()
                execLock.unlock()
            }
        }
    }

    fun acquire(
        cancel: () -> Boolean = { false },
        progress: (String) -> Unit = {},
        requestedModel: String? = null,
    ): ModelAccessResult {
        val ctx = appCtx ?: HttpApi.appContext
            ?: return ModelAccessResult.Failed("多模型池未初始化（App 尚未完成启动）", retryable = false)
        val reg = ensureRegistry(ctx)
        // 等整段推理锁时也要能感知取消：HTTP 的对端可能已经断开，App 可能已按
        // 停止。直接用 `lock()` 会让这条线程一直堵到上一轮解码结束，既占着连接，
        // 又把"取消"推迟到几十秒之后。每 100ms 查一次，语义与 registry 内的等待一致。
        while (true) {
            if (safeCancel(cancel)) return ModelAccessResult.Canceled
            val got = try {
                execLock.tryLock(100L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ModelAccessResult.Canceled
            }
            if (got) break
        }
        var transferred = false
        var poolLease: RuntimeLease? = null
        try {
            if (safeCancel(cancel)) return ModelAccessResult.Canceled

            val candidates = catalog(ctx)
            if (candidates.isEmpty()) {
                return ModelAccessResult.Failed(
                    "模型库中没有可用于对话的模型；请先导入对话模型", retryable = false)
            }
            val wanted = requestedModel
                ?.takeIf { MultiModelSettings.routeByRequestModel(ctx) }
                ?.trim()?.takeIf { it.isNotEmpty() }
            val decision = ModelRouter.resolve(
                requestedModel = wanted,
                candidates = candidates,
                mode = if (MultiModelSettings.routingMode(ctx) == MultiModelSettings.MODE_DYNAMIC)
                    ModelRoutingMode.DYNAMIC else ModelRoutingMode.STATIC,
                defaultModel = defaultModel(ctx),
                fallbackModel = MultiModelSettings.fallbackModel(ctx),
            )
            val hit = when (decision) {
                is ModelRouteDecision.Hit -> decision.candidate
                is ModelRouteDecision.NotFound ->
                    return ModelAccessResult.Failed(decision.message, retryable = false)
                is ModelRouteDecision.Conflict ->
                    return ModelAccessResult.Failed(decision.message, retryable = false)
            }
            lastRoute = "${hit.describe()} ← ${(decision as ModelRouteDecision.Hit).reason}"
            val spec = specOf(ctx, hit)
            val result = reg.acquire(spec, cancel, progress)
            when (result) {
                is AcquireResult.Ready -> {
                    poolLease = result.lease
                    val id = idsByKey[spec.key]
                        ?: return ModelAccessResult.Failed(
                            "内部错误：运行时已创建但缺少 native id（${spec.describe()}）",
                            retryable = false)
                    HttpApi.currentModel = spec.alias
                    val previous = LlmEngine.beginRuntimeSession(id)
                    if (previous < 0) {
                        result.lease.release()
                        return ModelAccessResult.Failed(
                            "模型运行时 #$id 已不存在，请重试", retryable = true)
                    }
                    val acquired = Lease(id, previous, result.lease)
                    transferred = true
                    return ModelAccessResult.Ready(ModelAccessLease(acquired))
                }
                is AcquireResult.Failed ->
                    return ModelAccessResult.Failed(
                        result.message, result.retryable, result.resourceExhausted)
                is AcquireResult.QueueFull ->
                    return ModelAccessResult.QueueFull(result.limit)
                AcquireResult.Canceled -> return ModelAccessResult.Canceled
            }
        } catch (t: Throwable) {
            if (!transferred) poolLease?.release()
            return ModelAccessResult.Failed(
                "多模型入口异常：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // 只有失败 / 取消会走到这里；成功时租约归还时才解锁。
            if (!transferred && execLock.isHeldByCurrentThread) execLock.unlock()
        }
    }

    /**
     * **手动预加载**：把指定对话模型按当前保存的引擎参数装进池子，之后的请求
     * 命中同一个 key 时直接复用，不再二次加载。
     *
     * 为什么需要：多模型开启时，「手动加载」若仍走 `LlmEngine.loadModel`，装进去的是
     * **遗留单模型运行时**，池子完全不知情；第一轮对话按路由发现池里为空 → 整份
     * 重载，用户白等一次加载、KV 账本也一起作废（2026-11-14 真机日志）。
     *
     * 参数对齐：key = `规范化路径 + paramsFingerprint(ctx)`，后者读的是设置页保存值。
     * 输入框改动会即时 `persistParams()`，所以这里**不再另传参数** —— 另传一份
     * 只会和 [acquire] 算出的 key 分叉，预加载就白做了。
     *
     * 成功返回 null；失败返回可直接展示的文案。加载成功后立刻归还租约，模型留在
     * 池里，空闲计时从这一刻开始（默认 60s，用户可自行调「空闲释放(秒)」或设常驻）。
     */
    fun preload(
        alias: String,
        fileName: String,
        progress: (String) -> Unit = {},
    ): String? {
        val ctx = appCtx ?: HttpApi.appContext
            ?: return "多模型池未初始化（App 尚未完成启动）"
        val reg = ensureRegistry(ctx)
        val hit = catalog(ctx).firstOrNull { it.alias == alias || it.fileName == fileName }
            ?: return "模型库中没有可预加载的对话模型：$fileName"
        val spec = specOf(ctx, hit)
        // 与 acquire 共用一把锁：预加载进行中到达的请求会排队，而不是再加载一份。
        execLock.lock()
        try {
            return when (val result = reg.acquire(spec, { false }, progress)) {
                is AcquireResult.Ready -> {
                    // 预加载不参与生成：拿到即还。模型留在池里，空闲计时开始。
                    result.lease.release()
                    HttpApi.currentModel = spec.alias
                    lastRoute = "${spec.alias} ← 手动预加载"
                    LlmEngine.uiLog(
                        "[多模型] 预加载完成：${spec.describe()}（${spec.paramsFingerprint}），" +
                            "下一轮对话直接复用")
                    null
                }
                is AcquireResult.Failed -> result.message
                is AcquireResult.QueueFull -> "排队已满（上限 ${result.limit}），请稍后重试"
                AcquireResult.Canceled -> "预加载已取消"
            }
        } catch (t: Throwable) {
            return "预加载异常：${t.javaClass.simpleName}: ${t.message}"
        } finally {
            execLock.unlock()
        }
    }

    /** 池内驻留项快照（手动卸载与状态展示用）。 */
    fun residents(): List<ModelResidency> = registry?.snapshot() ?: emptyList()

    /**
     * 按 key 登记卸载池内一份；返回是否命中。
     *
     * key 来自 [residents] 的 `ModelResidency.key`（路径 + 参数指纹）。调用方不需要
     * 先判断活跃租约：状态机会把「正在推理」排到空闲后、「加载中」排到加载完。
     */
    fun requestUnloadKey(key: String, reason: String = "[卸载] 手动卸载"): Boolean =
        registry?.requestUnload(key, reason) == true

    /**
     * 按**文件路径**登记卸载池内对应的一份；参数指纹变过也能命中（只比路径段）。
     * 返回 false = 池内没有这个文件。
     */
    fun requestUnloadModel(path: String, reason: String = "[卸载] 手动卸载"): Boolean {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val hit = residents().firstOrNull { it.key.substringBefore('|') == canonical }
            ?: return false
        return requestUnloadKey(hit.key, reason)
    }

    /** 服务停止 / 关闭多模型：登记卸载全部运行时。不阻塞调用线程。 */
    fun requestUnloadAll(reason: String): List<String> =
        registry?.requestUnloadAll(reason) ?: emptyList()

    fun awaitAllUnloaded(timeoutMs: Long): Boolean =
        registry?.awaitAllUnloaded(timeoutMs) ?: true

    fun snapshot(): List<ModelResidency> = registry?.snapshot() ?: emptyList()

    /**
     * 「常驻」名单变更后调用：把新标记同步到池里**已存在**的运行时，
     * 并让它们当场重算空闲计时（常驻 = 立即撤掉卸载计时）。
     */
    fun refreshPinned() {
        val ctx = appCtx ?: HttpApi.appContext ?: return
        registry?.refreshPinned { rt -> isPinned(ctx, rt.spec.alias, rt.spec.fileName) }
    }

    private fun safeCancel(cancel: () -> Boolean): Boolean =
        runCatching { cancel() }.getOrDefault(false)

    private fun protectedKeys(ctx: Context): Set<String> {
        val pinned = MultiModelSettings.pinnedModels(ctx)
        if (pinned.isEmpty()) return emptySet()
        return catalog(ctx)
            .filter { c ->
                pinned.any { MultiModelSettings.pinnedEntryMatches(it, c.alias, c.fileName) }
            }
            .map { it.key }.toSet()
    }

    private fun defaultModel(ctx: Context): String? =
        MultiModelSettings.defaultModel(ctx).takeIf { it.isNotBlank() }
            ?: ModelLibrary.selectedName(ctx).takeIf { it.isNotBlank() }

    private fun catalog(ctx: Context): List<ModelRouteCandidate> =
        ModelLibrary.rows(ctx)
            .asSequence()
            .filter { it.role == ModelRole.CHAT && !it.missing && it.file.isFile }
            .map { row ->
                ModelRouteCandidate(
                    key = keyOf(row.file, ctx),
                    alias = row.alias,
                    fileName = row.file.name,
                    role = row.role,
                    priority = 0,
                    supportsTools = false,
                    ctxTrain = 0,
                    pinned = isPinned(ctx, row.alias, row.file.name),
                    path = canonicalOf(row.file),
                )
            }
            .toList()

    private fun isPinned(ctx: Context, alias: String, fileName: String): Boolean =
        MultiModelSettings.pinnedModels(ctx)
            .any { MultiModelSettings.pinnedEntryMatches(it, alias, fileName) }

    private fun keyOf(file: File, ctx: Context): String =
        "${canonicalOf(file)}|${paramsFingerprint(ctx)}"

    private fun canonicalOf(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun paramsFingerprint(ctx: Context): String {
        val p = ServerSettings.engineParams(ctx)
        val flash = if (ServerSettings.flashAttn(ctx)) 1 else 0
        return listOf("ctx", "gpu", "threads", "mmap", "cacheK", "cacheV", "parallelN",
            "batchSize", "ubatchSize").joinToString(";") { "$it=${p[it].orEmpty()}" } + ";flash=$flash"
    }

    private fun specOf(ctx: Context, candidate: ModelRouteCandidate): ModelRuntimeSpec {
        // 优先用候选自带的路径：同名文件可能同时有应用内副本与外部引用，回查
        // `findByAliasOrName` 会固定命中内部副本，使 key（按候选路径算）与
        // 实际加载路径分叉。只有旧构造点（path 为空）才回查。
        val file = candidate.path.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: ModelLibrary.findByAliasOrName(ctx, candidate.fileName)
            ?: File(candidate.fileName)
        val path = canonicalOf(file)
        return ModelRuntimeSpec(
            key = candidate.key,
            path = path,
            fileName = file.name,
            alias = candidate.alias,
            role = candidate.role,
            supportsTools = candidate.supportsTools,
            ctxTrain = candidate.ctxTrain,
            priority = candidate.priority,
            pinned = candidate.pinned,
            estimatedBytes = runCatching { file.length() }.getOrDefault(0L),
            paramsFingerprint = paramsFingerprint(ctx),
        )
    }

    private fun ensureRegistry(ctx: Context): ModelRuntimeRegistry {
        registry?.let { return it }
        synchronized(this) {
            registry?.let { return it }
            val reg = ModelRuntimeRegistry(
                factory = { spec -> createRuntime(ctx, spec) },
                maxLoaded = { MultiModelSettings.maxLoaded(ctx) },
                loadWaitMs = { MultiModelSettings.loadWaitMs(ctx) },
                log = { msg -> LlmEngine.uiLog(msg) },
            )
            registry = reg
            return reg
        }
    }

    private fun createRuntime(ctx: Context, spec: ModelRuntimeSpec): ModelRuntime {
        val id = nextRuntimeId.getAndIncrement()
        if (!LlmEngine.createRuntimeContainer(id)) {
            throw IllegalStateException("native 运行时容器创建失败（#$id）")
        }
        // 同一个 key 之前可能被 LRU 驱逐过：旧容器已经没有模型在跑，销毁它，
        // 避免 native 侧的运行时表随"加载-驱逐"轮次无限增长。
        val oldId = idsByKey.put(spec.key, id)
        if (oldId != null && oldId != id) LlmEngine.destroyRuntimeContainer(oldId)
        val handle = RuntimeHandle(id)
        val rt = ModelRuntime(
            spec = spec,
            engineLoadedPath = { handle.path },
            loadModel = { path ->
                val err = loadIntoRuntime(ctx, id, path)
                if (err == null) handle.path = path
                err
            },
            unloadModel = {
                try {
                    LlmEngine.unloadModelRuntime(id)
                } finally {
                    handle.path = null
                }
            },
            maxWaiters = { MultiModelSettings.maxWaiters(ctx) },
            idleTimeoutMs = { MultiModelSettings.idleSeconds(ctx) * 1000L },
            // 常驻名单是**实时设置**：用户先加载、后设常驻也要立刻生效。
            // 这里读设置而不是 spec.pinned（后者只是创建时的快照）。
            idleAllowed = { !isPinned(ctx, spec.alias, spec.fileName) },
            scheduleIdle = { delayMs, task ->
                val f = scheduler.schedule({ runCatching { task() } }, delayMs, TimeUnit.MILLISECONDS)
                val cancel: () -> Unit = { f.cancel(false) }
                cancel
            },
            runAsync = { task -> unloadExecutor.execute { runCatching { task() } } },
            log = { msg -> LlmEngine.uiLog(msg) },
            // Stage 3：每份模型先只开 1 格 —— Kotlin 的 SlotScheduler 仍是进程级，
            // 多格要等 Stage 4 的每 runtime 实例化。这里显式写死并在日志里说明，
            // 不让用户设了 parallelN>1 却以为生效了。
            maxSlots = 1,
        )
        LlmEngine.uiLog("[多模型] 创建运行时 #$id ${spec.describe()}（${spec.paramsFingerprint}）")
        return rt
    }

    private class RuntimeHandle(val id: Int) {
        @Volatile var path: String? = null
    }

    /** 用**保存的引擎参数**把模型加载进某个运行时（与按需加载共用同一份设置）。 */
    private fun loadIntoRuntime(ctx: Context, id: Int, path: String): String? {
        if (!LlmEngine.init(ctx)) {
            return "引擎初始化失败：${LlmEngine.lastInitError ?: "native 库不可用"}"
        }
        val p = ServerSettings.engineParams(ctx)
        val rawGpu = p["gpu"].orEmpty()
        val gpu = NumInput.intOrNull(rawGpu)?.takeIf { it >= 0 } ?: 0
        if (NumInput.intOrNull(rawGpu) == null && NumInput.normalize(rawGpu).isNotEmpty()) {
            LlmEngine.uiLog("[多模型] gpu 参数不可用（原文=\"${NumInput.escaped(rawGpu)}\"）→ 按 0（纯 CPU）")
        }
        val nCtx = p["ctx"]?.toIntOrNull() ?: 4096
        val nThreads = p["threads"]?.toIntOrNull() ?: LlmEngine.defaultThreads()
        val mmap = (p["mmap"]?.toIntOrNull() ?: 1) != 0
        val cacheK = p["cacheK"]?.toIntOrNull() ?: 0
        val cacheV = p["cacheV"]?.toIntOrNull() ?: 0
        val parallelN = p["parallelN"]?.toIntOrNull() ?: 0
        if (parallelN > 1) {
            LlmEngine.uiLog("[多模型] 运行时 #$id 暂以单 slot 运行（parallelN=$parallelN，多格见后续阶段）")
        }
        val batchSize = p["batchSize"]?.toIntOrNull() ?: 0
        val ubatchSize = p["ubatchSize"]?.toIntOrNull() ?: 0
        val flash = ServerSettings.flashAttn(ctx)
        LlmEngine.uiLog("[多模型] 加载 #$id ${File(path).name}（gpu=$gpu, ctx=$nCtx, threads=$nThreads, " +
            "mmap=$mmap, flash=$flash, slots=1）")
        LlmEngine.pushCtxWatermark(ctx)
        return LlmEngine.loadModelRuntime(
            runtimeId = id, path = path, nGpuLayers = gpu, nCtx = nCtx, nThreads = nThreads,
            flashAttn = flash, useMmap = mmap, cacheK = cacheK, cacheV = cacheV,
            parallelN = 1, batchSize = batchSize, ubatchSize = ubatchSize)
    }
}
