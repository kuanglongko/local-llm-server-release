package com.xiaowan.localinference

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 「按需加载模型」的 Android 侧门面。
 *
 * [ModelLifecycleCore] 只描述状态迁移；本对象负责把真实世界的四件事接上：
 *   · 目标模型从哪来（预设 / 上次成功记录）；
 *   · 真正调用 [LlmEngine] 加载/释放；
 *   · 空闲计时器（可配置秒数，默认 60）；
 *   · 后台线程（加载与 free 都不能压在 HTTP/UI 线程上）。
 *
 * 与 [RequestWakeLock] 同一套生命周期：`App.onCreate` 初始化一次，进程内只有
 * 一份。[InferenceService.onCreate] 再调一次是 no-op。这样 App 内聊天与 HTTP
 * 两条入口看到的是**同一个**活跃推理计数与同一张等待队列 —— 各自持有一份计数
 * 会让"还有人在推理"这件事在另一条入口上不可见，空闲卸载就会在解码中途发生。
 */
object ModelLifecycle {

    @Volatile private var appCtx: Context? = null

    @Volatile private var coreRef: ModelLifecycleCore? = null

    /**
     * 一个单线程调度器同时承担两件事：空闲计时的**安排**、以及把 free 挪出
     * 调用线程。用同一根线程意味着「计时到点」与「真正 free」不会互相超车；
     * `removeOnCancelPolicy` 让被取消的计时任务当场离开延迟队列（否则每次
     * 新请求都会留一枚要到原定时刻才消失的任务）。
     */
    private val scheduler: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "model-lifecycle").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }

    /** free 单独一根线程：加载线程在 `loadLock` 上等它时，计时任务仍要能跑。 */
    private val unloadExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "model-lifecycle-unload").apply { isDaemon = true }
        }
    }

    @Volatile private var enabledFlag = false

    /**
     * 进程级初始化。幂等；重复调用只更新 application context。
     *
     * 必须在任何入口读 [isEnabled] / [acquire] 之前调用 —— `App.onCreate` 是最早
     * 且必然经过的位置（App 内聊天不经过 Service，只在 Service 里初始化会让
     * 「只开 App 聊天」拿到未初始化的状态机）。
     */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        appCtx = app
        if (coreRef != null) return
        synchronized(this) {
            if (coreRef != null) return
            enabledFlag = OnDemandSettings.enabled(app)
            val core = ModelLifecycleCore(
                targetProvider = { requested -> resolveTarget(app, requested) },
                engineLoadedPath = { LlmEngine.loadedPath },
                loadModel = { path -> loadTarget(app, path) },
                unloadModel = { unloadTarget() },
                maxWaiters = { OnDemandSettings.maxQueue(app) },
                idleTimeoutMs = { OnDemandSettings.idleSeconds(app) * 1000L },
                scheduleIdle = { delayMs, task ->
                    val f = scheduler.schedule(
                        { runCatching { task() } }, delayMs, TimeUnit.MILLISECONDS)
                    val cancel: () -> Unit = { f.cancel(false) }
                    cancel
                },
                // free 与加载不能共用一根线程：加载线程正握着 `loadLock`，而
                // `unload()` 要等同一把锁；把 free 丢回调度器会与"等待加载结束"
                // 的那条路径互等。单独的 unload 线程让 `unload()` 自己去排队。
                runAsync = { task -> unloadExecutor.execute { runCatching { task() } } },
                log = { msg -> LlmEngine.uiLog(msg) },
                enabled = enabledFlag,
            )
            coreRef = core
            // 进程启动时可能已经有一份手动/服务路径加载好的模型（例如 Activity
            // 重建、Service 先起）。状态机初始是 UNLOADED，必须当场对一次账，
            // 否则开关打开后第一次请求会再加载一遍（白花几十秒）。
            if (LlmEngine.hasModel) {
                if (enabledFlag) rememberLoadedPath(app, LlmEngine.loadedPath)
                core.noteExternalModelLoaded()
            }
        }
    }

    /** 当前开关值。以状态机为准，磁盘只在初始化时读一次。 */
    fun isEnabled(): Boolean = enabledFlag

    /** UI 勾选/取消。会同步状态机（关掉时按规则释放模型，见 Core.setEnabled）。 */
    fun setEnabled(on: Boolean) {
        enabledFlag = on
        val c = coreRef ?: return
        c.setEnabled(on)
    }

    /**
     * 确保模型就绪并登记一次活跃推理。
     *
     * 返回的 [ModelLifecycleCore.Result.Ready.lease] 必须在推理结束的 finally 里
     * 交还 [release]；漏还一次，空闲卸载就永远等不到 0。
     */
    fun acquire(
        cancel: () -> Boolean = { false },
        progress: (String) -> Unit = {},
        /**
         * 请求体里携带的模型名（OpenAI `model` / Anthropic `model`）。
         *
         * **只有**「按需加载」与「允许请求指定模型」两个开关都开着时才生效；
         * 任一关闭都归一成 `null`，于是状态机走原来的「预设 / 上次成功」路径。
         * 归一化放在这里而不是 HTTP 层：App 聊天与 HTTP 两条入口共用同一个判断，
         * 将来再加端点也不会漏。
         */
        requestedModel: String? = null,
    ): ModelLifecycleCore.Result {
        val c = coreRef
            ?: return ModelLifecycleCore.Result.Failed(
                "按需加载状态机未初始化（App 尚未完成启动）", retryable = false)
        val ctx = appCtx
        val wanted = requestedModel?.trim()?.takeIf { it.isNotEmpty() }
        val effective = if (wanted != null && ctx != null &&
            OnDemandSettings.requestModelEnabled(ctx)) wanted else null
        return c.acquire(cancel, progress, effective)
    }

    fun release(lease: ModelLifecycleCore.Lease?) {
        coreRef?.release(lease)
    }

    fun snapshot(): ModelLifecycleCore.Snapshot? = coreRef?.snapshot()

    /** 服务停止等场景：释放模型但保留开关；下次请求仍可自动加载。 */
    fun requestUnload(reason: String) {
        coreRef?.requestUnload(reason)
    }

    /** 手动加载 / 服务启动加载成功后调用：把已经在内存里的模型交给状态机。 */
    fun noteExternalModelLoaded() {
        coreRef?.noteExternalModelLoaded()
    }

    /** 手动卸载 / 服务停止卸载后调用：让状态机回到 UNLOADED。 */
    fun noteExternalModelUnloaded() {
        coreRef?.noteExternalModelUnloaded()
    }

    /**
     * 补记「上次成功加载的模型」。
     *
     * 主写入点在 [LlmEngine.loadModel] 的成功分支，条件是 `appCtx != null` ——
     * 服务先起、`LlmEngine.init` 后跑的那条路（`InferenceService` 的加载线程里
     * `init` 与 `loadModel` 之间隔着一次 ctx 水位推送）会漏。症状很隐蔽：用户手动
     * 加载过一份、开了按需、预设留空 → 冷启动后报「还没有可用模型」。
     *
     * **只在开关打开时补**：默认关的那一档必须逐字节同旧行为（一格 prefs 都不写）。
     */
    private fun rememberLoadedPath(ctx: Context, path: String?) {
        val p = path?.trim().orEmpty()
        if (p.isEmpty()) return
        if (OnDemandSettings.lastModelPath(ctx) == p) return
        OnDemandSettings.setLastModelPath(ctx, p)
    }

    // ---- 目标解析 ----

    /**
     * 解析本次要加载的模型。
     *
     * [requested] 非空 = 调用方明确要求某个模型（仅当「允许请求指定模型」开着时
     * 门面才会传进来）。此时**不再看预设**：名字解析不到就明确失败，绝不静默
     * 退回预设 —— 否则调用方以为"换模型成功"，实际跑的是另一个权重。
     */
    private fun resolveTarget(
        ctx: Context,
        requested: String? = null,
    ): ModelLifecycleCore.TargetResult {
        if (!requested.isNullOrBlank()) {
            val name = requested.trim()
            val f = ModelLibrary.findByAliasOrName(ctx, name)
                ?: return ModelLifecycleCore.TargetResult.Fail(
                    "请求指定的模型「$name」不在模型库中；请用 /v1/models 返回的 id/别名，" +
                        "或到「设置 → 按需加载模型」选择预设模型")
            if (!f.isFile) {
                return ModelLifecycleCore.TargetResult.Fail(
                    "请求指定的模型「$name」已被删除（${f.name}）")
            }
            if (ModelLibrary.roleOf(ctx, f.name) == ModelRole.EMBED) {
                return ModelLifecycleCore.TargetResult.Fail(
                    "请求指定的模型「$name」是向量模型，不能装进对话槽")
            }
            return ModelLifecycleCore.TargetResult.Ok(
                ModelLifecycleCore.Target(f.absolutePath, f.name))
        }

        val preset = OnDemandSettings.presetModel(ctx)
        if (preset.isNotBlank()) {
            val f = ModelLibrary.findByName(ctx, preset)
            if (f == null || !f.isFile) {
                return ModelLifecycleCore.TargetResult.Fail(
                    "预设模型「$preset」不存在或已被删除；请到「设置 → 按需加载模型」重新选择")
            }
            if (ModelLibrary.roleOf(ctx, f.name) == ModelRole.EMBED) {
                return ModelLifecycleCore.TargetResult.Fail(
                    "预设模型「$preset」是向量模型，不能装进对话槽；请重新选择对话模型")
            }
            return ModelLifecycleCore.TargetResult.Ok(
                ModelLifecycleCore.Target(f.absolutePath, f.name))
        }

        val last = OnDemandSettings.lastModelPath(ctx)
        if (last.isNotBlank()) {
            val f = File(last)
            if (f.isFile) {
                return ModelLifecycleCore.TargetResult.Ok(
                    ModelLifecycleCore.Target(f.absolutePath, f.name))
            }
            return ModelLifecycleCore.TargetResult.Fail(
                "上次成功加载的模型已不存在（${f.name}）；请到「设置 → 按需加载模型」选择预设模型")
        }

        return ModelLifecycleCore.TargetResult.Fail(
            "按需加载已开启，但还没有可用模型：请到「设置 → 按需加载模型」选择预设模型，" +
                "或先手动加载一次模型（成功后会自动记为「上次成功」）")
    }

    /**
     * 用**已保存的引擎参数**加载目标模型。
     *
     * 为什么不给按需路径单独一套参数：App 内聊天、HTTP 服务、STICKY 自愈三条路
     * 共用 `engine_params`；另起一套会让"我在设置页改了 ctx，按需加载却还是旧值"
     * 成为静默事实。这里只限定**模型身份**（预设/上次成功），不另存参数。
     */
    private fun loadTarget(ctx: Context, path: String): String? {
        if (!LlmEngine.init(ctx)) {
            return "引擎初始化失败：${LlmEngine.lastInitError ?: "native 库不可用"}"
        }
        val p = ServerSettings.engineParams(ctx)
        val rawGpu = p["gpu"].orEmpty()
        val gpu = NumInput.intOrNull(rawGpu)?.takeIf { it >= 0 } ?: 0
        if (NumInput.intOrNull(rawGpu) == null && NumInput.normalize(rawGpu).isNotEmpty()) {
            LlmEngine.uiLog("[按需] gpu 参数不可用（原文=\"${NumInput.escaped(rawGpu)}\"）→ 按 0（纯 CPU）")
        }
        val nCtx = p["ctx"]?.toIntOrNull() ?: 4096
        val nThreads = p["threads"]?.toIntOrNull() ?: LlmEngine.defaultThreads()
        val mmap = (p["mmap"]?.toIntOrNull() ?: 1) != 0
        val cacheK = p["cacheK"]?.toIntOrNull() ?: 0
        val cacheV = p["cacheV"]?.toIntOrNull() ?: 0
        val parallelN = p["parallelN"]?.toIntOrNull() ?: 0
        val batchSize = p["batchSize"]?.toIntOrNull() ?: 0
        val ubatchSize = p["ubatchSize"]?.toIntOrNull() ?: 0
        val flash = ServerSettings.flashAttn(ctx)
        LlmEngine.uiLog("[按需] 自动加载 ${File(path).name}（gpu=$gpu, ctx=$nCtx, threads=$nThreads, " +
            "mmap=$mmap, flash=$flash, slots=$parallelN）")
        LlmEngine.pushCtxWatermark(ctx)
        val err = LlmEngine.loadModel(
            path, nGpuLayers = gpu, nCtx = nCtx, nThreads = nThreads,
            flashAttn = flash, useMmap = mmap, cacheK = cacheK, cacheV = cacheV,
            parallelN = parallelN, batchSize = batchSize, ubatchSize = ubatchSize)
        if (err == null) {
            HttpApi.currentModel = ModelLibrary.aliasOf(ctx, File(path).name)
            LlmEngine.uiLog("[按需] 模型已就绪：${LlmEngine.modelDesc()}")
        }
        return err
    }

    private fun unloadTarget() {
        LlmEngine.unload()
        HttpApi.currentModel = null
    }
}
