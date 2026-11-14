package com.xiaowan.localinference

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「按需加载模型」的**纯逻辑状态机**（宿主可单测，不碰 Android / JNI）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 它解决的是什么
 * ═══════════════════════════════════════════════════════════════════════
 * 请求入口（App 聊天 / HTTP）只应该知道「我要推理」，不该各自判断「模型在不在」。
 * 本类把这件事收成唯一入口 [acquire]：
 *
 *   UNLOADED / ERROR --(第一个请求)--> LOADING --成功--> LOADED
 *   LOADING --(后续请求)--> 排队等待（有上限）
 *   LOADED --(最后一个活跃推理结束 + 空闲超时)--> UNLOADING --> UNLOADED
 *   UNLOADING --(释放完成前收到请求)--> 取消释放，回到 LOADED
 *
 * 并发约束只有三条，但每条失效都是静默的：
 *   1. **同一时刻只允许一个加载任务**：否则 native 会被两次加载交叉弄脏；
 *   2. **加载成功唤醒所有等待者，失败让所有等待者失败**：否则请求会永远挂着；
 *   3. **活跃推理计数 > 0 时绝不卸载**：否则正在解码的 ctx 被 free，轻则报错、
 *      重则 native 崩。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么把平台动作做成注入
 * ═══════════════════════════════════════════════════════════════════════
 * 宿主上没有 `LlmEngine`、没有 `SharedPreferences`，也没有真的计时器。
 * 注入之后，状态迁移、队列上限、取消、空闲计时、开关关闭等**全部**可以在宿主
 * 上穷举；Android 门面（[ModelLifecycle]）只负责把真实引擎与真实计时器接上。
 *
 * ⚠ 本类**不**替调用方决定"加载哪个模型"：目标由 [targetProvider] 在真正
 *   开始加载的那一刻解析一次。预设/上次记录的变化只影响下一次加载，运行中的
 *   生命周期不会因为设置页改了名字就热切换。
 *
 * ⚠ 唯一的例外是**请求显式指定模型**（[acquire] 的 `requestedModel`）。它只在
 *   门面确认「按需加载 + 允许请求指定模型」两个开关都开着时才会传进来；本类
 *   仍不解释名字，只把名字交给 [targetProvider]。此时若已加载的是另一份模型：
 *     · 没有活跃推理也没有等待者 -> 先释放再加载新目标（一次真正的切换）；
 *     · 还有人在用 / 在等 -> 明确失败，**绝不**把旧模型当新模型交付。
 */
class ModelLifecycleCore(
    /** 解析本次要加载的模型；参数是请求指定的模型名（未指定 = null）。 */
    private val targetProvider: (String?) -> TargetResult,
    /** 引擎当前真实驻留的模型路径；用于接管手动/服务路径的加载与卸载。 */
    private val engineLoadedPath: () -> String?,
    /** 真正执行加载，返回 null=成功；非 null=错误文案。 */
    private val loadModel: (String) -> String?,
    /** 真正执行卸载。 */
    private val unloadModel: () -> Unit,
    /** 加载期间最多挂起多少请求（不含正在加载的那一个）。 */
    private val maxWaiters: () -> Int,
    /** 空闲多久后卸载（毫秒）。 */
    private val idleTimeoutMs: () -> Long,
    /**
     * 这一份模型是否允许被**空闲计时**卸载。默认允许。
     *
     * 多模型池用它接「常驻」：常驻不是只挡住 LRU / 容量 / 内存驱逐，也必须
     * 挡住每份 runtime 自己的空闲计时 —— 否则用户勾了常驻，模型仍会在
     * `idleSeconds` 到点后被状态机放掉（看起来像设置没生效）。
     */
    private val idleAllowed: () -> Boolean = { true },
    /** 安排一次空闲卸载；返回取消函数。 */
    private val scheduleIdle: (Long, () -> Unit) -> (() -> Unit),
    /** 把卸载动作挪出调用线程（响应线程不该被 native free 阻塞）。 */
    private val runAsync: (() -> Unit) -> Unit,
    private val log: (String) -> Unit,
    enabled: Boolean = false,
) {

    enum class State { UNLOADED, LOADING, LOADED, UNLOADING, ERROR }

    data class Target(val path: String, val label: String)

    sealed class TargetResult {
        data class Ok(val target: Target) : TargetResult()
        data class Fail(val message: String) : TargetResult()
    }

    data class Snapshot(
        val enabled: Boolean,
        val state: State,
        val loadedPath: String?,
        val loadedLabel: String?,
        val activeInferences: Int,
        val waiters: Int,
        val idlePending: Boolean,
        val lastError: String?,
    )

    sealed class Result {
        class Ready(val lease: Lease) : Result()
        /** [retryable] = false 表示"开关没开/已关闭"，不是模型本身的问题。 */
        class Failed(val message: String, val retryable: Boolean = true) : Result()
        class QueueFull(val limit: Int) : Result()
        object Canceled : Result()
    }

    /**
     * 一次活跃推理的凭证。只允许归还一次：重复 release 会把别人的计数减掉，
     * 于是空闲卸载可能在另一路还在解码时被触发（症状是"偶发 native 崩"）。
     */
    class Lease internal constructor(private val core: ModelLifecycleCore) {
        private val released = AtomicBoolean(false)
        fun release() = core.release(this)
        internal fun releaseOnce(): Boolean = released.compareAndSet(false, true)
    }

    private val lock = Any()

    @Volatile private var enabledNow = enabled
    private var state = State.UNLOADED
    private var loadedPath: String? = null
    private var loadedLabel: String? = null
    /** 正在加载的目标路径；用于请求指定模型时判断「等的是不是同一份」。 */
    private var loadingPath: String? = null
    private var lastError: String? = null

    private var active = 0
    private var waiters = 0

    /** 空闲计时代际：取消/重排时自增，旧任务醒来发现代际不对就什么也不做。 */
    private var idleGeneration = 0L
    private var idleCancel: (() -> Unit)? = null
    private var idlePending = false

    /** 释放是否已经真正开始（true 之后不能再取消，只能等它完成再重载）。 */
    private var unloadCommitted = false
    /** 释放前被请求取消。 */
    private var unloadCanceled = false
    /** 加载中开关被关闭：加载完成后立即释放。 */
    private var unloadAfterLoad = false
    /** 开关关闭时仍有活跃推理：最后一个推理结束就释放（不再等空闲计时）。 */
    private var unloadWhenIdle = false

    private fun note(msg: String) {
        runCatching { log(msg) }
    }

    private fun safeProgress(progress: (String) -> Unit, msg: String) {
        runCatching { progress(msg) }
    }

    private fun safeCancel(cancel: () -> Boolean): Boolean =
        runCatching { cancel() }.getOrDefault(false)

    /** 调用方持锁。`Any` 在 Kotlin 里没有 wait/notify，必须显式转成 java.lang.Object。 */
    private fun waitLocked(ms: Long) {
        (lock as java.lang.Object).wait(ms)
    }

    /** 调用方持锁。 */
    private fun notifyAllLocked() {
        (lock as java.lang.Object).notifyAll()
    }

    /**
     * 「确保模型就绪」并**同时**登记一次活跃推理。
     *
     * 为什么就绪与计数要在同一个入口完成：若先返回"就绪"、调用方再单独 +1，
     * 中间这段空隙里空闲计时可能恰好到期，模型就在调用方准备推理的瞬间被卸掉。
     * 两者在同一把锁内完成，空隙不存在。
     *
     * @param cancel 返回 true = 调用方已放弃（客户端断开 / 用户点停止）。
     *   排队等待期间每 100ms 检查一次；正在加载的那一个在加载结束后也会再查一次。
     * @param progress 加载/排队进度文案，只在状态变化时回调。
     * @param requestedModel 请求显式指定的模型名；`null` = 走原来的预设/上次成功。
     *   名字由 [targetProvider] 解析；解析失败只影响**这一条请求**，不会把全局
     *   状态推进 ERROR（否则一个写错名字的客户端会让其它请求一起失败）。
     */
    fun acquire(
        cancel: () -> Boolean = { false },
        progress: (String) -> Unit = {},
        requestedModel: String? = null,
    ): Result {
        val requestedName = requestedModel?.trim()?.takeIf { it.isNotEmpty() }
        var requestedResolved = false
        var requestedTarget: Target? = null
        var requestedError: String? = null

        var registered = false
        while (true) {
            // 目标解析可能读文件系统，放在锁外；同一请求只解析一次，之后复用。
            if (requestedName != null && !requestedResolved) {
                val resolved = runCatching { targetProvider(requestedName) }.getOrElse {
                    TargetResult.Fail(
                        "解析请求指定的模型失败：${it.javaClass.simpleName}: ${it.message}")
                }
                when (resolved) {
                    is TargetResult.Ok -> requestedTarget = resolved.target
                    is TargetResult.Fail -> requestedError = resolved.message
                }
                requestedResolved = true
            }

            var startLoad = false
            var wait = false
            var waitMsg: String? = null
            var switchUnload = false

            synchronized(lock) {
                reconcileLocked()

                if (!enabledNow) {
                    if (registered) { registered = false; waiters-- }
                    return Result.Failed("按需加载模型未开启", retryable = false)
                }

                // 请求给的模型名解析不到：只让这一条请求失败。此时不改 state，
                // 下一次不带名字（或带合法名字）的请求仍能照常自动加载。
                if (requestedError != null) {
                    if (registered) { registered = false; waiters--; notifyAllLocked() }
                    return Result.Failed(requestedError!!)
                }

                when (state) {
                    State.LOADED -> {
                        val wanted = requestedTarget
                        if (wanted != null && wanted.path != loadedPath) {
                            // 已加载的是另一份模型。只有"没有任何人在用、也没有人
                            // 在等"时才允许现场切换；否则先卸旧模型会把正在解码的
                            // 那几路打断，而把旧模型交给新请求又是静默的错误答案。
                            if (active > 0 || waiters > 0) {
                                if (registered) {
                                    registered = false; waiters--; notifyAllLocked()
                                }
                                return Result.Failed(
                                    "请求指定的模型「${wanted.label}」与当前驻留模型不同，" +
                                        "且仍有 $active 路推理 / $waiters 个请求在使用或等待；" +
                                        "暂时不能切换，请稍后重试")
                            }
                            if (registered) { registered = false; waiters-- }
                            cancelIdleLocked()
                            // 这次释放由当前请求自己完成，并标记 committed：
                            // 别的请求不能把它撤销回旧模型（它们等的是新目标）。
                            state = State.UNLOADING
                            unloadCommitted = true
                            unloadCanceled = false
                            notifyAllLocked()
                            switchUnload = true
                        } else {
                            if (registered) { registered = false; waiters-- }
                            cancelIdleLocked()
                            active++
                            return Result.Ready(Lease(this))
                        }
                    }

                    State.ERROR -> {
                        if (registered) {
                            // 这一批是**为上一次加载排队**的：加载已经失败，必须
                            // 跟着失败，不能每个等待者都去重试一次（那会把一次失败
                            // 放大成 N 次加载）。新请求（未登记）才会重试。
                            registered = false; waiters--
                            val msg = lastError ?: "模型加载失败"
                            notifyAllLocked()
                            return Result.Failed(msg)
                        }
                        startLoad = true
                    }

                    State.UNLOADED -> {
                        if (registered) { registered = false; waiters-- }
                        startLoad = true
                    }

                    State.LOADING -> {
                        // 正在装的是另一份模型：让本请求等它加载完再判断，等于
                        // 白等一轮后仍要切换；这里直接明确失败（调用方可重试）。
                        val wanted = requestedTarget
                        if (wanted != null && loadingPath != null && wanted.path != loadingPath) {
                            if (registered) {
                                registered = false; waiters--; notifyAllLocked()
                            }
                            return Result.Failed(
                                "正在加载另一个模型（${loadingPath?.substringAfterLast('/')}），" +
                                    "与请求指定的「${wanted.label}」不同；请稍后重试")
                        }
                        if (!registered) {
                            val limit = maxWaiters().coerceAtLeast(1)
                            if (waiters >= limit) return Result.QueueFull(limit)
                            waiters++
                            registered = true
                            waitMsg = "模型正在加载，已排队（第 $waiters 位 / 上限 $limit）"
                        }
                        wait = true
                    }

                    State.UNLOADING -> {
                        val wanted = requestedTarget
                        val sameAsLoaded = wanted == null ||
                            (loadedPath != null && wanted.path == loadedPath)
                        if (!unloadCommitted && sameAsLoaded) {
                            // 释放还没真正开始：撤销它，直接回到 LOADED。
                            // 这是「卸载中收到新请求」唯一划算的处置方式 ——
                            // 已经 free 掉的权重不可能"取消 free"，但还没开始就
                            // 不必让用户白等一次完整重载。
                            unloadCanceled = true
                            state = State.LOADED
                            if (registered) { registered = false; waiters-- }
                            cancelIdleLocked()
                            active++
                            notifyAllLocked()
                            return Result.Ready(Lease(this))
                        }
                        if (!sameAsLoaded) {
                            // 请求要的是另一份模型：不能撤销释放，等它结束后再加载。
                            // 置 committed 让等待循环进入真正的 wait（也告诉别的新
                            // 请求：现在只能等，不能取消）。
                            unloadCommitted = true
                        }
                        if (!registered) {
                            val limit = maxWaiters().coerceAtLeast(1)
                            if (waiters >= limit) return Result.QueueFull(limit)
                            waiters++
                            registered = true
                            waitMsg = "旧模型正在释放，已排队（第 $waiters 位 / 上限 $limit）"
                        }
                        wait = true
                    }
                }
            }

            if (switchUnload) {
                safeProgress(progress, "正在切换模型…")
                val err = runCatching { unloadModel() }.exceptionOrNull()
                if (err != null) {
                    note("[按需] 切换前释放模型异常：${err.javaClass.simpleName}: ${err.message}")
                }
                synchronized(lock) {
                    // 本次释放由本线程完成；其它等待者在 state=UNLOADING 时不会
                    // 再开一次释放（它们只排队）。这里统一回到 UNLOADED，随后
                    // 循环顶部会加载 requestedTarget。
                    state = State.UNLOADED
                    loadedPath = null
                    loadedLabel = null
                    loadingPath = null
                    unloadCommitted = false
                    unloadCanceled = false
                    notifyAllLocked()
                }
                continue
            }

            waitMsg?.let { safeProgress(progress, it) }

            if (startLoad) {
                val resolved = requestedTarget?.let { TargetResult.Ok(it) }
                    ?: runCatching { targetProvider(null) }.getOrElse {
                        TargetResult.Fail(
                            "解析自动加载模型失败：${it.javaClass.simpleName}: ${it.message}")
                    }
                var target: Target? = null
                synchronized(lock) {
                    if (!enabledNow) {
                        if (registered) { registered = false; waiters-- }
                        return Result.Failed("按需加载模型未开启", retryable = false)
                    }
                    // 目标解析期间可能有别的线程先把它变成了 LOADING/LOADED：
                    // 那一个继续排队即可，不重复加载。
                    if (state == State.UNLOADED || state == State.ERROR) {
                        when (resolved) {
                            is TargetResult.Fail -> {
                                state = State.ERROR
                                loadedPath = null
                                loadedLabel = null
                                loadingPath = null
                                lastError = resolved.message
                                notifyAllLocked()
                                return Result.Failed(resolved.message)
                            }
                            is TargetResult.Ok -> {
                                target = resolved.target
                                state = State.LOADING
                                loadedPath = null
                                loadedLabel = null
                                loadingPath = resolved.target.path
                                lastError = null
                                cancelIdleLocked()
                                notifyAllLocked()
                            }
                        }
                    }
                }
                target?.let { return runLoader(it, cancel, progress) }
                // 被别的线程抢先了：回到循环顶部，按当前状态排队/放行。
                continue
            }

            if (wait) {
                if (safeCancel(cancel)) {
                    synchronized(lock) {
                        if (registered) { registered = false; waiters--; notifyAllLocked() }
                    }
                    return Result.Canceled
                }
                synchronized(lock) {
                    if (registered && (state == State.LOADING ||
                            (state == State.UNLOADING && unloadCommitted))) {
                        waitLocked(100)
                    }
                }
            }
        }
    }

    /** 加载线程自己就是第一个请求：加载成功时顺便为它 +1；失败时唤醒所有等待者。 */
    private fun runLoader(
        target: Target,
        cancel: () -> Boolean,
        progress: (String) -> Unit,
    ): Result {
        safeProgress(progress, "正在加载模型 ${target.label}…")
        val err = try {
            loadModel(target.path)
        } catch (t: Throwable) {
            "加载异常：${t.javaClass.simpleName}: ${t.message}"
        }

        var needUnload = false
        var canceledAtEnd = false
        synchronized(lock) {
            if (err != null) {
                loadedPath = null
                loadedLabel = null
                loadingPath = null
                if (enabledNow) {
                    state = State.ERROR
                    lastError = err
                } else {
                    state = State.UNLOADED
                    lastError = null
                }
                unloadAfterLoad = false
                notifyAllLocked()
                return Result.Failed(err)
            }

            loadedPath = target.path
            loadedLabel = target.label
            loadingPath = null
            lastError = null
            if (!enabledNow || unloadAfterLoad) {
                unloadAfterLoad = false
                state = State.UNLOADING
                unloadCommitted = true
                notifyAllLocked()
                needUnload = true
            } else {
                state = State.LOADED
                canceledAtEnd = safeCancel(cancel)
                if (canceledAtEnd) {
                    // 客户端在加载期间走了：模型仍留给其它等待者/下一次请求。
                    maybeScheduleIdleLocked()
                } else {
                    cancelIdleLocked()
                    active++
                }
                notifyAllLocked()
            }
        }

        if (needUnload) {
            safeProgress(progress, "按需加载已关闭，正在释放模型…")
            runCatching { unloadModel() }
            synchronized(lock) {
                state = State.UNLOADED
                loadedPath = null
                loadedLabel = null
                unloadCommitted = false
                unloadCanceled = false
                notifyAllLocked()
            }
            return Result.Failed("按需加载模型已关闭", retryable = false)
        }
        return if (canceledAtEnd) Result.Canceled else Result.Ready(Lease(this))
    }

    /**
     * 归还一次活跃推理。计数归零时才启动空闲计时；有活跃推理时**绝不**安排卸载。
     *
     * 幂等：同一张 [Lease] 重复调用不会重复减计数。
     */
    fun release(lease: Lease?) {
        if (lease == null || !lease.releaseOnce()) return
        var unloadNow = false
        var reason = ""
        synchronized(lock) {
            if (active > 0) active--
            if (active == 0 && state == State.LOADED) {
                if (!enabledNow || unloadWhenIdle) {
                    unloadWhenIdle = false
                    unloadNow = true
                    reason = if (enabledNow) "当前没有活跃推理" else "开关关闭"
                } else {
                    maybeScheduleIdleLocked()
                }
            }
        }
        // `beginUnload` 只做一次状态切换与「排队异步释放」，本身不碰 native。
        // 直接调用（而不是再包一层 runAsync）让 UNLOADING **立刻可见** ——
        // 新请求因此有一段真实窗口可以撤销它，而不是等异步线程排到队才看到。
        if (unloadNow) beginUnload(reason)
    }

    fun setEnabled(on: Boolean) {
        var asyncUnload = false
        var msg = ""
        synchronized(lock) {
            if (enabledNow == on) {
                if (on) {
                    reconcileLocked()
                    if (state == State.LOADED) maybeScheduleIdleLocked()
                }
                return
            }
            enabledNow = on
            if (on) {
                unloadAfterLoad = false
                unloadWhenIdle = false
                reconcileLocked()
                if (state == State.LOADED) maybeScheduleIdleLocked()
                msg = "[按需] 已开启：请求到达时自动加载，空闲 ${idleTimeoutMs() / 1000}s 后释放"
            } else {
                cancelIdleLocked()
                when (state) {
                    State.LOADING -> {
                        // 加载不可中断：完成后立即释放，避免"开关关了模型还常驻"。
                        unloadAfterLoad = true
                        msg = "[按需] 已关闭：当前加载完成后会立即释放模型"
                    }
                    State.LOADED -> {
                        unloadAfterLoad = false
                        if (active == 0) {
                            asyncUnload = true
                            msg = "[按需] 已关闭：正在释放模型（${loadedLabel ?: loadedPath}）"
                        } else {
                            unloadWhenIdle = true
                            msg = "[按需] 已关闭：仍有 $active 路推理，结束后释放模型"
                        }
                    }
                    State.UNLOADING -> {
                        unloadAfterLoad = false
                        msg = "[按需] 已关闭：模型正在释放"
                    }
                    State.ERROR, State.UNLOADED -> {
                        state = State.UNLOADED
                        loadedPath = null
                        loadedLabel = null
                        loadingPath = null
                        lastError = null
                        unloadAfterLoad = false
                        unloadWhenIdle = false
                        msg = "[按需] 已关闭：与引入本功能前同行为（需手动加载，加载后常驻）"
                    }
                }
                // 等待中的请求全部放行（它们会看到 enabledNow=false 而失败）。
                notifyAllLocked()
            }
        }
        if (msg.isNotEmpty()) note(msg)
        // 同上：状态切换留在调用线程，native free 才进异步队列。
        if (asyncUnload) beginUnload("开关关闭")
    }

    /** 服务停止等场景：释放模型，但**不**改变按需开关；下一次请求仍可自动加载。 */
    fun requestUnload(reason: String) {
        var asyncUnload = false
        synchronized(lock) {
            when (state) {
                State.LOADED -> {
                    if (active == 0) asyncUnload = true else unloadWhenIdle = true
                }
                State.LOADING -> unloadAfterLoad = true
                State.ERROR, State.UNLOADED, State.UNLOADING -> {}
            }
        }
        if (asyncUnload) beginUnload(reason)
    }

    /** 手动加载/服务路径加载成功后调用：让状态机接管这份已经在内存里的模型。 */
    fun noteExternalModelLoaded() {
        var schedule = false
        synchronized(lock) {
            if (state == State.LOADING || state == State.UNLOADING) return
            val p = runCatching { engineLoadedPath() }.getOrNull() ?: return
            val changed = p != loadedPath
            state = State.LOADED
            loadedPath = p
            loadedLabel = p.substringAfterLast('/')
            loadingPath = null
            lastError = null
            cancelIdleLocked()
            if (enabledNow) schedule = true
            notifyAllLocked()
            if (changed) note("[按需] 已接管内存中的模型：$loadedLabel")
        }
        if (schedule) synchronized(lock) { maybeScheduleIdleLocked() }
    }

    /** 手动卸载/服务停止后引擎已空：让状态机回到 UNLOADED。 */
    fun noteExternalModelUnloaded() {
        synchronized(lock) {
            if (state == State.LOADING || state == State.UNLOADING) return
            cancelIdleLocked()
            state = State.UNLOADED
            loadedPath = null
            loadedLabel = null
            loadingPath = null
            lastError = null
            notifyAllLocked()
        }
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            runCatching { reconcileLocked() }
            return Snapshot(
                enabled = enabledNow,
                state = state,
                loadedPath = loadedPath,
                loadedLabel = loadedLabel,
                activeInferences = active,
                waiters = waiters,
                idlePending = idlePending,
                lastError = lastError,
            )
        }
    }

    // ---- 内部：状态对账 / 空闲计时 / 卸载 ----

    /**
     * 把状态机的认知与引擎真值对齐。
     *
     * 手动「加载模型」、服务侧加载、`ACTION_STOP` 里的卸载都不经过本类。
     * 如果不在每次入口对一次账，状态机会在"引擎已空"时仍说 LOADED，于是新请求
     * 直接放行到没有模型的 native 上；反过来，手动加载之后状态机说 UNLOADED，
     * 会再加载一遍（白花几十秒）。
     */
    private fun reconcileLocked() {
        val actual = runCatching { engineLoadedPath() }.getOrNull()
        when (state) {
            State.LOADED -> {
                if (actual == null) {
                    state = State.UNLOADED
                    loadedPath = null
                    loadedLabel = null
                    cancelIdleLocked()
                } else if (actual != loadedPath) {
                    loadedPath = actual
                    loadedLabel = actual.substringAfterLast('/')
                }
            }
            State.UNLOADED, State.ERROR -> {
                if (actual != null) {
                    state = State.LOADED
                    loadedPath = actual
                    loadedLabel = actual.substringAfterLast('/')
                    lastError = null
                    if (enabledNow) maybeScheduleIdleLocked()
                }
            }
            State.LOADING, State.UNLOADING -> {}
        }
    }

    /** 调用方持锁。 */
    private fun maybeScheduleIdleLocked() {
        if (!idleAllowed()) {
            // 常驻标记可能在计时已排上之后才打开：把旧计时撤掉，别让它到点
            // 把一份用户明确要求常驻的模型放掉。
            cancelIdleLocked()
            return
        }
        if (!enabledNow || state != State.LOADED || active > 0) return
        // 还有人为这次加载排队时不能开始空闲计时：计时到点会先把模型卸掉，
        // 刚被唤醒的等待者又得从头加载一遍（白等两轮）。等他们拿到 lease
        // 或全部失败后，最后一个 release 再安排。
        if (waiters > 0) return
        cancelIdleLocked()
        val gen = ++idleGeneration
        val timeout = idleTimeoutMs().coerceAtLeast(1_000L)
        idlePending = true
        idleCancel = runCatching {
            // 计时器线程只做「切到 UNLOADING」这一步（很轻），真正的 free 由
            // unloadIfIdle 再丢给异步线程 —— 这样 UNLOADING 在 fire 之后立刻可见。
            scheduleIdle(timeout) { unloadIfIdle(gen) }
        }.getOrNull()
    }

    /** 调用方持锁。 */
    private fun cancelIdleLocked() {
        idleGeneration++
        idlePending = false
        val c = idleCancel
        idleCancel = null
        if (c != null) runCatching { c() }
    }

    /**
     * 「常驻标记」变了之后重新评估空闲计时：打开常驻 = 撤掉计时；关闭常驻 =
     * 如果模型已加载且没有活跃请求/等待者，重新排一次计时。
     *
     * 这是 [idleAllowed] 的配套入口。没有它，已排上的旧计时会继续按原时刻
     * 触发（[unloadIfIdle] 里虽然还会再查一次标记，但状态机会一直显示
     * `idlePending=true`，用户看不出「已改常驻、计时已取消」）。
     */
    fun reconsiderIdle() {
        synchronized(lock) {
            if (state == State.LOADED && idleAllowed()) {
                maybeScheduleIdleLocked()
            } else {
                cancelIdleLocked()
            }
        }
    }

    private fun unloadIfIdle(gen: Long) {
        var go = false
        synchronized(lock) {
            if (!idleAllowed()) {
                // 计时触发与「设为常驻」可能擦肩而过：以触发这一刻的标记为准，
                // 不把已经常驻的模型卸掉。
                idleCancel = null
                idlePending = false
                idleGeneration++
                return
            }
            if (gen != idleGeneration || !enabledNow ||
                state != State.LOADED || active != 0) return
            idleCancel = null
            idlePending = false
            idleGeneration++
            state = State.UNLOADING
            unloadCommitted = false
            unloadCanceled = false
            notifyAllLocked()
            go = true
        }
        if (go) runAsync { executeUnload("空闲超时") }
    }

    /** 由定时器/归还线程异步调用；`beginUnload` 先把状态切到 UNLOADING，让新请求能取消。 */
    private fun beginUnload(reason: String) {
        var go = false
        synchronized(lock) {
            if (state != State.LOADED) return
            if (active > 0) {
                if (!enabledNow) unloadWhenIdle = true else maybeScheduleIdleLocked()
                return
            }
            cancelIdleLocked()
            state = State.UNLOADING
            unloadCommitted = false
            unloadCanceled = false
            notifyAllLocked()
            go = true
        }
        if (go) runAsync { executeUnload(reason) }
    }

    /**
     * 真正释放前**再查一次**活跃计数（需求里的"卸载前置检查"）。
     *
     * 同时把 [unloadCommitted] 置真：从这一刻起新请求不能再取消释放，只能排队
     * 等释放完成后重新加载。两件事必须在同一把锁内完成，否则"检查通过"与
     * "新请求进来"之间会有一步窗口。
     */
    private fun executeUnload(reason: String) {
        var committed = false
        synchronized(lock) {
            if (state != State.UNLOADING) return
            if (unloadCanceled || active > 0) {
                state = State.LOADED
                unloadCommitted = false
                unloadCanceled = false
                if (enabledNow) maybeScheduleIdleLocked() else if (active == 0) unloadWhenIdle = true
                notifyAllLocked()
                return
            }
            unloadCommitted = true
            committed = true
        }
        if (!committed) return
        note("[按需] 正在释放模型（$reason）…")
        val err = runCatching { unloadModel() }.exceptionOrNull()
        if (err != null) note("[按需] 释放模型异常：${err.javaClass.simpleName}: ${err.message}")
        synchronized(lock) {
            state = State.UNLOADED
            loadedPath = null
            loadedLabel = null
            loadingPath = null
            unloadCommitted = false
            unloadCanceled = false
            if (!enabledNow) unloadWhenIdle = false
            notifyAllLocked()
        }
        note("[按需] 模型已释放（$reason）")
    }
}
