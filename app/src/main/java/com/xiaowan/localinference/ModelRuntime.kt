package com.xiaowan.localinference

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个模型运行时的**静态身份与配置**。
 *
 * [key] 必须包含「模型路径 + 加载参数指纹」：同一份 GGUF 用不同 ctx / slot 数
 * 加载，是**两个不同的运行时**（KV 容量、内存占用、并发槽都不同）。只用路径
 * 做 key 会让"请求 A 要 ctx=4096、请求 B 要 ctx=32768"静默复用同一份错误配置。
 *
 * ⚠ 本类型是纯数据，不带 Android / JNI 依赖；[ModelRuntime] 才持有状态机。
 */
data class ModelRuntimeSpec(
    /** 内部身份：路径 + 参数指纹。 */
    val key: String,
    /** 模型文件绝对路径。 */
    val path: String,
    /** 文件名（带 `.gguf`）。 */
    val fileName: String,
    /** `/v1/models` 对外 id。 */
    val alias: String,
    /** 只允许 [ModelRole.CHAT] 进入对话运行时池。 */
    val role: ModelRole,
    /** 动态路由用。 */
    val supportsTools: Boolean = false,
    /** 训练上下文上限；0 = 未知。 */
    val ctxTrain: Int = 0,
    /** 动态路由优先级。 */
    val priority: Int = 0,
    /** 用户标记常驻。 */
    val pinned: Boolean = false,
    /** 估算占用（权重 + KV + compute buffer）。0 = 未知。 */
    val estimatedBytes: Long = 0,
    /** 加载参数指纹（ctx / slots / gpu / mmap 等），只进 key 与日志。 */
    val paramsFingerprint: String = "",
) {
    /** 转成路由候选。 */
    fun toCandidate(): ModelRouteCandidate = ModelRouteCandidate(
        key = key,
        alias = alias,
        fileName = fileName,
        role = role,
        priority = priority,
        supportsTools = supportsTools,
        ctxTrain = ctxTrain,
        pinned = pinned,
        path = path,
    )

    fun describe(): String = if (alias.isNotBlank()) alias else fileName
}

/**
 * 一个模型运行时的**完整生命周期**：独立状态机 + 独立 slot 池 + 独立 LRU 统计。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么每个 runtime 各持一份 [ModelLifecycleCore]
 * ══════════════════════════════════════════════════════════════════════════
 * 现有 core 的状态、等待队列、活跃租约、空闲计时都是**单目标**的。多模型池
 * 不是"一个 core 管多个目标"，而是"每个目标一个 core"：
 *   · A 正在加载时，B 仍可独立处于 LOADED 并继续接请求；
 *   · A 加载失败进入 ERROR，不影响 B 的下一次 acquire；
 *   · A 的空闲计时到点只释放 A，不会看 B 的活跃计数。
 *
 * 所有 native 动作（加载 / 卸载 / 生成）都由注入回调落到**这个 runtime 的
 * 句柄**上；[ModelRuntime] 自己不知道 native 是全局 `S` 还是别的容器。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 Slots 的关系
 * ══════════════════════════════════════════════════════════════════════════
 * slot 池是**每模型独立**的：同一份权重可以开多格，但不同权重不能共用同一张
 * slot 表（否则 A 的 slot 1 会落到 B 的 ctx 上）。当前阶段 [slotPool] 只是
 * 占位注入；接线时由 native runtime 自己持有池，Kotlin 侧只读尺寸。
 */
class ModelRuntime(
    val spec: ModelRuntimeSpec,
    private val engineLoadedPath: () -> String?,
    private val loadModel: (String) -> String?,
    private val unloadModel: () -> Unit,
    private val maxWaiters: () -> Int,
    private val idleTimeoutMs: () -> Long,
    /**
     * 是否允许空闲计时卸载。多模型池接「常驻」：常驻必须同时挡住空闲计时，
     * 不能只挡住 LRU/容量/内存驱逐。
     */
    private val idleAllowed: () -> Boolean = { !spec.pinned },
    private val scheduleIdle: (Long, () -> Unit) -> (() -> Unit),
    private val runAsync: (() -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /** 这一份模型允许同时开几路推理；0 = 由 native/调度器决定，Kotlin 不拦。 */
    private val maxSlots: Int = 0,
) {

    /**
     * 独立状态机。目标固定为本 runtime 的路径：**路由已经在 registry 完成**，
     * core 不再解释请求里的模型名（那是单模型模式下的兼容路径）。
     */
    private val core = ModelLifecycleCore(
        targetProvider = { _ ->
            ModelLifecycleCore.TargetResult.Ok(
                ModelLifecycleCore.Target(spec.path, spec.fileName))
        },
        engineLoadedPath = engineLoadedPath,
        loadModel = { path ->
            val err = loadModel(path)
            if (err == null) {
                loadedAtMs = clock()
                log("[多模型] 已加载 ${spec.describe()}（${spec.paramsFingerprint}）")
            }
            err
        },
        unloadModel = {
            try {
                unloadModel()
            } finally {
                loadedAtMs = 0
                log("[多模型] 已释放 ${spec.describe()}")
            }
        },
        maxWaiters = maxWaiters,
        idleTimeoutMs = idleTimeoutMs,
        idleAllowed = idleAllowed,
        scheduleIdle = scheduleIdle,
        runAsync = runAsync,
        log = log,
        // 多模型池里的 runtime 由 registry 决定何时建、何时拆；core 自身的
        // "开关关掉就卸载"语义在这里恒为开。总开关在 registry / 门面层。
        enabled = true,
    )

    /** 最近一次真正拿到租约或归还租约的时间；LRU 只认这个，不认健康检查时间。 */
    @Volatile private var lastUsedAtMs: Long = clock()

    /** 进入 LOADED 的时间；0 = 未加载。 */
    @Volatile private var loadedAtMs: Long = 0

    /** 估算占用，可由 native 加载完成后回填。 */
    @Volatile var estimatedBytes: Long = spec.estimatedBytes
        private set

    @Volatile var pinned: Boolean = spec.pinned

    val key: String get() = spec.key
    val label: String get() = spec.describe()
    val role: ModelRole get() = spec.role

    /** 这一份模型允许同时开几路；0 = 未声明。 */
    val slots: Int get() = maxSlots

    fun setEstimatedBytes(bytes: Long) {
        estimatedBytes = bytes.coerceAtLeast(0L)
    }

    /**
     * 更新「常驻」标记，并**当场**重算空闲计时。
     *
     * 只改字段不重算的话，用户点「常驻」之前已排上的计时会继续走；虽然
     * [ModelLifecycleCore.unloadIfIdle] 还会再查一次标记，但界面上的
     * `idlePending` 仍为真，读起来像「设了常驻还在倒计时」。
     */
    fun updatePinned(on: Boolean) {
        if (pinned == on) return
        pinned = on
        core.reconsiderIdle()
    }

    fun acquire(
        cancel: () -> Boolean = { false },
        progress: (String) -> Unit = {},
    ): AcquireResult {
        val r = core.acquire(cancel, progress)
        return when (r) {
            is ModelLifecycleCore.Result.Ready -> {
                lastUsedAtMs = clock()
                AcquireResult.Ready(RuntimeLease(this, r.lease))
            }
            is ModelLifecycleCore.Result.Failed ->
                AcquireResult.Failed(
                    r.message, r.retryable,
                    resourceExhausted = LoadFailure.looksLikeResourceExhaustion(r.message))
            is ModelLifecycleCore.Result.QueueFull ->
                AcquireResult.QueueFull(r.limit)
            ModelLifecycleCore.Result.Canceled -> AcquireResult.Canceled
        }
    }

    internal fun releaseLease(lease: ModelLifecycleCore.Lease?) {
        // 归还也算一次"使用结束"：LRU 的 lastUsed 取结束时刻，避免一个刚跑完
        // 的长请求被当成"很久没用"（它的开始时间可能在几分钟前）。
        if (lease != null) lastUsedAtMs = clock()
        core.release(lease)
    }

    /** 服务停止 / LRU / 容量驱逐：释放模型但保留 runtime 配置，下次可重建。 */
    fun requestUnload(reason: String) {
        core.requestUnload(reason)
    }

    fun setEnabled(on: Boolean) = core.setEnabled(on)

    fun noteExternalModelLoaded() = core.noteExternalModelLoaded()

    fun noteExternalModelUnloaded() = core.noteExternalModelUnloaded()

    fun snapshot(): ModelResidency {
        val s = core.snapshot()
        return ModelResidency(
            key = spec.key,
            label = spec.describe(),
            state = when (s.state) {
                ModelLifecycleCore.State.UNLOADED -> ModelResidencyState.UNLOADED
                ModelLifecycleCore.State.LOADING -> ModelResidencyState.LOADING
                ModelLifecycleCore.State.LOADED -> ModelResidencyState.LOADED
                ModelLifecycleCore.State.UNLOADING -> ModelResidencyState.UNLOADING
                ModelLifecycleCore.State.ERROR -> ModelResidencyState.ERROR
            },
            activeLeases = s.activeInferences,
            waiters = s.waiters,
            lastUsedAtMs = lastUsedAtMs,
            loadedAtMs = loadedAtMs,
            estimatedBytes = estimatedBytes,
            pinned = pinned,
            priority = spec.priority,
        )
    }

    /** 这一份模型当前的 core 快照（调试 / 旧门面兼容用）。 */
    fun lifecycleSnapshot(): ModelLifecycleCore.Snapshot = core.snapshot()

    fun lastError(): String? = core.snapshot().lastError

    override fun toString(): String = "ModelRuntime(${spec.describe()}, ${snapshot().state})"
}

/** 一次成功 acquire 的凭证：**同时**持有 runtime 与 core 租约。 */
class RuntimeLease internal constructor(
    val runtime: ModelRuntime,
    internal val coreLease: ModelLifecycleCore.Lease,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) runtime.releaseLease(coreLease)
    }

    internal fun isReleased(): Boolean = released.get()
}

sealed class AcquireResult {
    data class Ready(val lease: RuntimeLease) : AcquireResult()
    /**
     * @param resourceExhausted true = 这次失败更像「内存装不下」（见 [LoadFailure]）。
     *   调用方（registry）据此先按 LRU 释放空闲模型再重试；false 时驱逐别的模型
     *   没有任何帮助，不该白白踢掉用户正在热的模型。
     */
    data class Failed(
        val message: String,
        val retryable: Boolean = true,
        val resourceExhausted: Boolean = false,
    ) : AcquireResult()
    data class QueueFull(val limit: Int) : AcquireResult()
    object Canceled : AcquireResult()
}
