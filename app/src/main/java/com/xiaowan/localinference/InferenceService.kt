package com.xiaowan.localinference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * 前台服务：承载本地推理 + OpenAI 兼容 HTTP 服务。
 *
 * 为进程内方案：确保 LlmEngine 已加载模型 -> 启动 HttpApi(127.0.0.1:8080)。
 * 不 exec 外部 llama-server 二进制：部分 OEM 的 W^X 与后台冻结策略下该路径不可用。
 * 若模型已由 UI 页加载则直接复用，不重复加载。
 */
class InferenceService : Service() {

    companion object {
        const val ACTION_START = "com.xiaowan.localinference.START"
        const val ACTION_STOP = "com.xiaowan.localinference.STOP"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_CTX = "ctx"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_GPU = "gpu"
        const val EXTRA_CACHE_K = "cache_k"
        const val EXTRA_CACHE_V = "cache_v"
        const val EXTRA_MMAP = "mmap"
        // 批次参数（<=0 表示保留 llama.cpp 默认）
        const val EXTRA_FLASH = "flash_attn"
        const val EXTRA_PARALLEL_N = "parallelN"
        const val EXTRA_BATCH_SIZE = "batchSize"
        const val EXTRA_UBATCH_SIZE = "ubatchSize"
        /** 本次启动的进程 pid + 启动时刻：探针文件里靠它对齐「哪一次运行」 */
        val BOOT_ID: String = "${android.os.Process.myPid()}-" +
            java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        // 渠道创建后 importance 即锁定，提级必须换新 ID 才生效；旧 ID 在下方删除。
        // v1(llm_server) -> v2(llm_server_v2, LOW->DEFAULT) -> v3(本次, DEFAULT->HIGH)
        private const val CHANNEL_ID = "llm_server_v3"
        private val LEGACY_CHANNEL_IDS = arrayOf("llm_server", "llm_server_v2")
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 进程级的请求唤醒锁只在这里初始化一次。放 onCreate 而不是每个
        // ACTION_START 分支：STICKY 自动恢复 / 已加载模型直接挂载 / 冷启动
        // 三条路都要有同一份计数，任何一个分支漏掉都会变成「部分请求没锁」。
        RequestWakeLock.init(applicationContext)
        // 按需加载的状态机也必须**全进程一份**：App 内聊天不经过本服务，
        // 而 HTTP 推理与 App 聊天共用同一份「活跃推理计数」。只在服务里初始化
        // 会让两条入口各算一份，空闲卸载看不到另一路还在跑。
        ModelLifecycle.init(applicationContext)
        // 悬浮球的状态源：本服务在跑 = 一条**独立**事实，与模型在不在内存里无关。
        // 上报给进程级 [ModelStatusManager]，由独立的 [FloatingBallService] 订阅显示。
        // 上报点放在 onCreate（三条启动分支共用）而不是 onStartCommand 的某个分支 ——
        // 漏一条就是"某些启动方式药丸还说无服务"。
        ModelStatusManager.setServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LlmEngine.probeMark("[服务] onStartCommand action=${intent?.action} startId=$startId boot=$BOOT_ID")
        when (intent?.action) {
            ACTION_STOP -> {
                // 停止服务 = 关 HTTP + 卸载模型。
                // ⚠ 必须先 `HttpApi.stop()` 再卸载模型：stop() 会置 desired=false
                // （看门狗据此**不**自愈），并收敛在途连接 —— 若顺序反了，
                // 卸载期间一个还在途的生成请求会打到已卸载的引擎上
                // （`LlmEngine.unload()` 只持 `loadLock`，不持 `genLock`，也不看 `hasModel`）。
                HttpApi.stop()
                // 停止服务 = 这一代租约不会再有人归还。必须在服务收尾时把
                // 系统锁摘掉；旧租约之后迟到的 release 会被 WakeLeaseCore 判成
                // no-op，不会减掉下一代请求的引用。
                RequestWakeLock.forceRelease()
                val od = ModelLifecycle.snapshot()
                if (MultiModelEngine.isEnabled(this)) {
                    // 多模型池有自己的一组运行时：逐个登记卸载（后台线程执行，
                    // 主线程不等）。有活跃推理 / 等待者的那一份会等最后一路结束
                    // 再释放，与按需模式同一条纪律。
                    val keys = MultiModelEngine.requestUnloadAll("服务停止")
                    LlmEngine.uiLog("[服务] 已停止；多模型池释放 ${keys.size} 份（后台）")
                    // 多模型开启前可能已经用旧路径加载过一份（runtime 0）。池子
                    // 不会碰它；不在这里释放就会在停止服务后继续占着内存。
                    if (LlmEngine.hasModel) {
                        try {
                            LlmEngine.unload()
                            ModelLifecycle.noteExternalModelUnloaded()
                            LlmEngine.uiLog("[服务] 已停止并卸载旧单模型路径驻留的模型")
                        } catch (t: Throwable) {
                            LlmEngine.uiLog("[服务] 卸载旧单模型路径模型异常: ${t.message}")
                        }
                    }
                } else if (od?.enabled == true) {
                    // 按需模式统一交给状态机，不能在这里直接 `LlmEngine.unload()`：
                    //   · LOADING —— 直接 unload 会阻塞在 loadLock 上等整轮加载
                    //     结束（主线程等几十秒 = ANR）；
                    //   · LOADED 且还有活跃推理 —— 那可能是 **App 内聊天**（两条入口
                    //     共用一份计数），直接 free 会把它脚下的 ctx 抽走；
                    //   · UNLOADING —— 释放已经在 unload 线程上跑，再调一次只会
                    //     陪它等同一把锁。
                    // requestUnload 对这四种状态分别做了正确处置：加载完成后释放 /
                    // 等最后一路结束 / 什么都不做。真正 free 在后台线程，主线程不等。
                    ModelLifecycle.requestUnload("服务停止")
                    LlmEngine.uiLog(
                        "[服务] 已停止；" + when (od.state) {
                            ModelLifecycleCore.State.LOADING -> "模型仍在加载，完成后释放"
                            ModelLifecycleCore.State.UNLOADING -> "模型正在释放"
                            ModelLifecycleCore.State.UNLOADED,
                            ModelLifecycleCore.State.ERROR -> "当前无驻留模型"
                            ModelLifecycleCore.State.LOADED ->
                                if (od.activeInferences > 0)
                                    "仍有 ${od.activeInferences} 路推理，结束后释放模型"
                                else "模型由按需状态机释放"
                        })
                } else if (LlmEngine.hasModel) {
                    try {
                        LlmEngine.unload()
                        ModelLifecycle.noteExternalModelUnloaded()
                        LlmEngine.uiLog("[服务] 已停止并卸载模型（内存已释放）")
                    } catch (t: Throwable) {
                        LlmEngine.uiLog("[服务] 卸载模型异常: ${t.message}")
                    }
                } else {
                    ModelLifecycle.noteExternalModelUnloaded()
                    LlmEngine.uiLog("[服务] 已停止（无已加载模型）")
                }
                HttpApi.currentModel = null
                // 药丸要立刻变 ⚪：这一格若等 onDestroy 才推，用户会看到
                // "服务已经停了、球还说推理中"的窗口（stopSelf 是异步的）。
                ModelStatusManager.setServiceRunning(false)
                // 保证下次「启动服务」必走完整加载流程：切换的 ctx/线程/GPU 参数生效、日志刷新。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 已有模型（UI 先加载 / 本地模式）→ 直接挂到服务上，不重复加载
                if (LlmEngine.hasModel) {
                    startForeground(NOTIF_ID, buildNotification("启动中…"))
                    // 挂载现有模型时也要把 `/v1/models` 的当前模型名补上：
                    // 按需模式下「停止服务」会把 currentModel 清空，而 App 内聊天
                    // 可能在卸载真正开始前撤销释放、把模型留在内存里；此时再启动
                    // 服务走的就是这一支，不补的话列表里那条挂不上 profile（档案），
                    // 客户端会以为"模型在内存里但读不到档案"。
                    HttpApi.currentModel = LlmEngine.loadedPath?.let {
                        ModelLibrary.aliasOf(applicationContext, File(it).name)
                    }
                    HttpApi.appContext = applicationContext
                    HttpApi.PORT = ServerSettings.serverPort(applicationContext)
                    HttpApi.bindAll = ServerSettings.lanAccess(applicationContext)
                    applyCorsWhitelist(applicationContext)
                    applyApiToken(applicationContext)
                    HttpApi.start()
                    // 水位推到 native（ISSUE #426）：这条路**不经过界面**——
                    // 冷启动先起前台服务、再打开 MainActivity 时，`LlmEngine.init` 已经
                    // 跑完、`nativeSetCtxWatermark` 却还没人调（它此前只在 `EngineActivity`
                    // 里推）。于是 native 按默认 85% 判，直到界面起来才换成用户设的那一档。
                    // 若用户设的是 70%（想更稳），这段窗口里每一轮都按 85% 判 —— 而屏幕上
                    // 没有任何东西说这件事。
                    LlmEngine.pushCtxWatermark(applicationContext)
                    ModelLifecycle.noteExternalModelLoaded()
                    LlmEngine.uiLog("[服务] 检测到已加载模型，直接挂载: ${LlmEngine.modelDesc()}（未重新加载）")
                    Thread {
                        try { Thread.sleep(400) } catch (_: InterruptedException) {}
                        val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                        updateNotification("运行中 http://$host:${HttpApi.PORT}（已挂载现有模型）")
                    }.start()
                    return START_STICKY
                }
                // ── 按需加载 / 多模型：服务直接起，**不预加载**任何模型 ──────
                //
                // 按需模式的核心形态：HTTP 端口立刻可达，第一个推理请求才触发
                // 加载。若在这里仍按 EXTRA_MODEL_PATH 预加载，"按需"就退化成了
                // "启动服务即常驻"，后面的空闲释放也永远不会发生。
                //
                // 多模型模式同一条纪律，而且更硬：目标由请求的 `model` 字段 /
                // 路由配置决定，启动时预加载某一份只会额外占内存、并与池子里的
                // 同名模型重复驻留（旧路径的模型在 runtime 0，池子看不见它）。
                val multiModel = MultiModelEngine.isEnabled(this)
                if (ModelLifecycle.isEnabled() || multiModel) {
                    startForeground(NOTIF_ID, buildNotification(
                        if (multiModel) "启动中…（多模型）" else "启动中…（按需加载）"))
                    HttpApi.currentModel = null
                    HttpApi.appContext = applicationContext
                    HttpApi.PORT = ServerSettings.serverPort(applicationContext)
                    HttpApi.bindAll = ServerSettings.lanAccess(applicationContext)
                    applyCorsWhitelist(applicationContext)
                    applyApiToken(applicationContext)
                    HttpApi.disableThinkingDefault = ServerSettings.disableThinking(applicationContext)
                    HttpApi.start()
                    LlmEngine.uiLog(if (multiModel)
                        "[服务] 多模型已开启：不预加载模型，请求到达时按 model 路由 / 加载"
                    else
                        "[服务] 按需加载已开启：不预加载模型，请求到达时自动加载")
                    Thread {
                        try { Thread.sleep(400) } catch (_: InterruptedException) {}
                        val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                        updateNotification(
                            if (multiModel)
                                "运行中 http://$host:${HttpApi.PORT}（多模型：请求到达时加载）"
                            else
                                "运行中 http://$host:${HttpApi.PORT}（按需加载：请求到达时加载模型）")
                    }.start()
                    return START_STICKY
                }
                val path = intent.getStringExtra(EXTRA_MODEL_PATH)
                if (path.isNullOrBlank()) {
                    LlmEngine.uiLog("[服务] 启动失败：无已加载模型且未指定模型路径")
                    // 收尾与 ACTION_STOP 同一条纪律：先 HttpApi.stop() 再 stopSelf()。
                    // 只 stopSelf() 的话 watchdog 每 30s 探活、连续 2 次失败就重启 listener，
                    // 而这里的 desired 仍为 true —— 要靠 onDestroy() 兜（它是异步的，慢于 30s）。
                    HttpApi.stop()
                    stopSelf(); return START_NOT_STICKY
                }
                val ctxSize = intent.getIntExtra(EXTRA_CTX, 4096)
                val threads = intent.getIntExtra(EXTRA_THREADS, 4)
                val gpu = intent.getIntExtra(EXTRA_GPU, 0)
                // 修复——服务启动加载此前漏传 flashAttn（恒 false），与 UI 加载按钮/自动恢复分支不一致
                // flash 优先取 Intent extra（toggleServer 已显式传入），prefs 兜底
                val flash = (intent.getIntExtra(EXTRA_FLASH, -1)).let { if (it >= 0) it != 0 else ServerSettings.flashAttn(applicationContext) }
                val ck = intent.getIntExtra(EXTRA_CACHE_K, 0)
                val cv = intent.getIntExtra(EXTRA_CACHE_V, 0)
                val mm = intent.getIntExtra(EXTRA_MMAP, 1) != 0
                val parallelN = intent.getIntExtra(EXTRA_PARALLEL_N, 0)
                val batchSize = intent.getIntExtra(EXTRA_BATCH_SIZE, 0)
                val ubatchSize = intent.getIntExtra(EXTRA_UBATCH_SIZE, 0)
                startForeground(NOTIF_ID, buildNotification("启动中…"))
                // HTTP 先于模型加载启动 —— /health 立即可达，
                // 加载再慢或失败也能从 /health 看到状态，不再出现"服务连不上"。
                HttpApi.currentModel = ModelLibrary.aliasOf(applicationContext, File(path).name)
                HttpApi.appContext = applicationContext
                // start 前从设置注入自定义端口
                HttpApi.PORT = ServerSettings.serverPort(applicationContext)
                // 注入局域网访问开关
                HttpApi.bindAll = ServerSettings.lanAccess(applicationContext)
                applyCorsWhitelist(applicationContext)
                applyApiToken(applicationContext)
                // 注入思考默认开关
                HttpApi.disableThinkingDefault = ServerSettings.disableThinking(applicationContext)
                HttpApi.start()
                Thread {
                    if (!LlmEngine.init(applicationContext)) {
                        updateNotification("初始化失败：native 引擎不可用（/health 可查状态）")
                        return@Thread
                    }
                    // 水位推到 native：**在加载模型之前**。设成加载之后也行（它不改 n_ctx），
                    // 但那样"第一轮请求"与"推送"之间存在一段按默认 85% 判的窗口 ——
                    // 而这段时间恰好是用户刚起来就发第一条消息的时候。
                    LlmEngine.pushCtxWatermark(applicationContext)
                    if (!LlmEngine.hasModel) {
                        updateNotification("加载模型 ${File(path).name} (gpu=$gpu)…")
                        val err = LlmEngine.loadModel(
                            path, nGpuLayers = gpu, nCtx = ctxSize, nThreads = threads,
                            flashAttn = flash, useMmap = mm, cacheK = ck, cacheV = cv,
                            parallelN = parallelN, batchSize = batchSize, ubatchSize = ubatchSize)
                        if (err != null) {
                            updateNotification("模型加载失败：$err（/health 可查状态）")
                            return@Thread
                        }
                    }
                    // 服务路径的加载不经过状态机；成功之后要让它接管这份驻留模型，
                    // 否则按需开关打开时状态机会以为引擎是空的、再加载一遍。
                    ModelLifecycle.noteExternalModelLoaded()
                    val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                    updateNotification("运行中 http://$host:${HttpApi.PORT}")
                }.start()
            }
            else -> {
                // intent==null = 系统杀进程后 STICKY 自动重启。
                // 历史缺陷：此分支空转 —— startForeground 未调用（超时 ANR 风险）且模型不加载
                // → 出现「PID 在但端口不监听」的僵尸态，必须手动打开 App 才能恢复。
                // 现自动用 ModelLibrary / ServerSettings 保存的选中模型与参数重载，实现断联自愈。
                startForeground(NOTIF_ID, buildNotification("自动恢复中…"))
                // 按需 / 多模型：系统重启后**恢复 HTTP 端口即可**，模型等第一个请求再加载。
                // 这里若按 selectedName 预加载，进程被杀重启就会把模型永久拉回内存，
                // 空闲释放形同虚设；多模型下还会与池子里的同名模型重复驻留。
                val multiModel = MultiModelEngine.isEnabled(this)
                if (ModelLifecycle.isEnabled() || multiModel) {
                    HttpApi.currentModel = null
                    HttpApi.appContext = applicationContext
                    HttpApi.PORT = ServerSettings.serverPort(applicationContext)
                    HttpApi.bindAll = ServerSettings.lanAccess(applicationContext)
                    applyCorsWhitelist(applicationContext)
                    applyApiToken(applicationContext)
                    HttpApi.disableThinkingDefault = ServerSettings.disableThinking(applicationContext)
                    HttpApi.start()
                    LlmEngine.uiLog(if (multiModel)
                        "[服务] 系统重启服务：多模型已开启，等待请求按 model 路由 / 加载"
                    else
                        "[服务] 系统重启服务：按需加载已开启，等待请求触发模型加载")
                    Thread {
                        val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                        updateNotification(
                            if (multiModel)
                                "运行中 http://$host:${HttpApi.PORT}（多模型：请求到达时加载）"
                            else
                                "运行中 http://$host:${HttpApi.PORT}（按需加载：请求到达时加载模型）")
                    }.start()
                    return START_STICKY
                }
                val name = ModelLibrary.selectedName(applicationContext)
                val mf = if (name.isNotBlank()) ModelLibrary.findByName(applicationContext, name) else null
                if (mf != null && mf.isFile) {
                    HttpApi.currentModel = ModelLibrary.aliasOf(applicationContext, mf.name)
                    HttpApi.appContext = applicationContext
                    HttpApi.PORT = ServerSettings.serverPort(applicationContext)
                    HttpApi.bindAll = ServerSettings.lanAccess(applicationContext)
                    applyCorsWhitelist(applicationContext)
                    applyApiToken(applicationContext)
                    HttpApi.disableThinkingDefault = ServerSettings.disableThinking(applicationContext)
                    HttpApi.start()
                    LlmEngine.uiLog("[服务] 检测到系统重启服务，自动恢复: ${mf.name}")
                    val p = ServerSettings.engineParams(applicationContext)
                    // 与设置页同一条解析（全角数字/空白先归一）。旧 prefs 里可能存着
                    // 「１５」——直接 toIntOrNull() 会得到 0，系统重启后静默变纯 CPU。
                    val rawGpuPref = p["gpu"].orEmpty()
                    val parsedGpu = NumInput.intOrNull(rawGpuPref)
                    val gpu = parsedGpu?.takeIf { it >= 0 } ?: 0
                    if (parsedGpu == null || parsedGpu < 0) {
                        if (NumInput.normalize(rawGpuPref).isNotEmpty()) {
                            LlmEngine.uiLog("[服务] 自动恢复：gpu 参数不可用（原文=\"" +
                                "${NumInput.escaped(rawGpuPref)}\" ｜ ${NumInput.describe(rawGpuPref)}）→ 按 0（纯 CPU）")
                        }
                    }
                    val ctxSize = p["ctx"]?.toIntOrNull() ?: 4096
                    val threads = p["threads"]?.toIntOrNull() ?: 4
                    val mmap = (p["mmap"]?.toIntOrNull() ?: 1) != 0
                    val ck = p["cacheK"]?.toIntOrNull() ?: 0
                    val cv = p["cacheV"]?.toIntOrNull() ?: 0
                    val parallelN = p["parallelN"]?.toIntOrNull() ?: 0
                    val batchSize = p["batchSize"]?.toIntOrNull() ?: 0
                    val ubatchSize = p["ubatchSize"]?.toIntOrNull() ?: 0
                    val flash = ServerSettings.flashAttn(applicationContext) // 自动恢复：prefs 即最新（doLoad/toggleServer 均回写）
                    Thread {
                        if (!LlmEngine.init(applicationContext)) {
                            updateNotification("自动恢复失败：native 引擎不可用（/health 可查状态）")
                            return@Thread
                        }
                        // 自动恢复这条路**更**需要它：进程被杀之后重启，用户可能压根不会
                        // 再打开界面，于是 native 侧永远停在默认档 —— 而他设的是 70%。
                        LlmEngine.pushCtxWatermark(applicationContext)
                        if (!LlmEngine.hasModel) {
                            updateNotification("自动恢复：加载 ${mf.name} (gpu=$gpu, ctx=$ctxSize)…")
                            val err = LlmEngine.loadModel(
                                mf.absolutePath, nGpuLayers = gpu, nCtx = ctxSize, nThreads = threads,
                                flashAttn = flash, useMmap = mmap, cacheK = ck, cacheV = cv,
                                parallelN = parallelN, batchSize = batchSize, ubatchSize = ubatchSize)
                            if (err != null) {
                                updateNotification("自动恢复失败：$err（/health 可查状态）")
                                return@Thread
                            }
                        }
                        ModelLifecycle.noteExternalModelLoaded()
                        val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                        updateNotification("运行中 http://$host:${HttpApi.PORT}（已自动恢复）")
                        LlmEngine.uiLog("[服务] 自动恢复完成: ${LlmEngine.modelDesc()}")
                    }.start()
                } else {
                    LlmEngine.uiLog("[服务] 系统重启服务但未找到已选模型，自动停止")
                    HttpApi.stop()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    /**
     * 把 Bearer token 从设置注入 [ApiAuth]。
     *
     * 与 [applyCorsWhitelist] 同一个道理：不接这一步，"设置页填了 token"就只是
     * 存进了 prefs，服务端压根不认 —— 而那种失效**完全不报错**：用户以为开了鉴权，
     * 实际接口仍然裸奔（或反过来，token 永远对不上）。三个启动分支都要走这一步，
     * 漏掉任何一个都会制造"同一个设置有的路径生效、有的不生效"。
     *
     * 空 token = 鉴权关闭（保持旧行为），不做任何隐式兜底。
     */
    private fun applyApiToken(ctx: android.content.Context) {
        val t = ServerSettings.apiToken(ctx)
        ApiAuth.token = t
        LlmEngine.uiLog(if (t.isEmpty()) "[服务] 鉴权未开启（生成端点不要求 token）"
                        else "[服务] 鉴权已开启：仅 /v1/ 生成端点要求 Authorization: Bearer")
    }

    /**
     * 把 CORS 白名单从设置注入 [HttpApi]。
     *
     * 为什么在白名单**默认集之外**还要接这一步：默认只放行回环与本机 IP，
     * 局域网内**另一台机器**上的前端（例如 PC 上的 Open WebUI）默认不在里面。
     * 用户加了来源就必须真的生效 —— 一个"填了没用"的白名单，最终会把所有人
     * 逼回 `Access-Control-Allow-Origin: *`，那正是本次刻意不做的形态。
     *
     * 逐条规范化；**非法串直接丢弃并留日志**，绝不静默变成"放行一切"。
     */
    private fun applyCorsWhitelist(ctx: android.content.Context) {
        CorsPolicy.enabled = ServerSettings.corsEnabled(ctx)
        CorsPolicy.clearOrigins()
        for (raw in ServerSettings.corsExtraOrigins(ctx)) {
            if (!CorsPolicy.addOrigin(raw)) {
                LlmEngine.uiLog("[服务] CORS 白名单条目无效已忽略: $raw")
            }
        }
    }

    /**
     * 建渠道 + 清理旧渠道，每个进程生命周期只做一次。
     *
     * 为什么加 [channelReady]：`buildNotification` 每 400ms 被调一次（刷新文案），
     * 而删/建/查渠道都是 binder 跨进程调用，且只在首次有意义 —— 后续每次重做是纯空转。
     *
     * 用 @Volatile 而非同步：这里允许"并发多跑一次"（结果幂等），
     * 加锁反而把 binder 调用塞进锁里。
     */
    @Volatile
    private var channelReady = false

    private fun ensureChannel(nm: NotificationManager) {
        if (channelReady) return
        // 先删旧渠道，避免系统设置里残留"死项"（用户点了改了却不再有效果）。
        for (id in LEGACY_CHANNEL_IDS) {
            try {
                nm.deleteNotificationChannel(id)
                // 删完复核一次：某些系统会"复活"用户手动改过的渠道。
                // 只在这里查一次（每进程一次），进 buildNotification 就会变成每 400ms 两次 binder 调用。
                if (nm.getNotificationChannel(id) != null) {
                    LlmEngine.uiLog("[服务] 旧通知渠道 $id 删不掉（通常是被手动改过设置），" +
                        "系统设置里可能仍看到它")
                }
            } catch (t: Throwable) {
                LlmEngine.uiLog("[服务] 清理旧通知渠道 $id 失败: ${t.message}")
            }
        }
        // 无条件 createNotificationChannel：对已存在的渠道是 no-op，用户改过的设置不会被覆盖。
        // 用 "if (getChannel==null)" 才建的话，一次失败的创建会让渠道永久缺失，
        // 而下面 `Notification.Builder(this, CHANNEL_ID)` 会照常发出一个**没有渠道**的通知。
        val ch = NotificationChannel(CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)  // 提级但显式静音：要的是可感知判定，不是响声
        ch.enableVibration(false)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
        channelReady = true
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // 本次再提一档：DEFAULT -> HIGH，同时换新渠道 ID（importance 建后不可改，提级必须换 ID）。
        // 背景：部分 OEM 的内存管理把非"可感知"的 FGS 判为可冻结
        // （日志特征："don't check adj for non-perceptible fgs app"），
        // 渠道 importance 正是其可感知判定的输入之一。
        // 保持显式静音：提级只为进入可感知判断，不换来响铃/振动打扰。
        // 老实说：提升概率有限，这一档的主要收益是把「通知级别」这个变量排除掉。
        ensureChannel(nm)
        // 通知必须可点开 App。此前漏了 `setContentIntent`：服务运行时点通知
        // 只有系统默认的"什么也不做"，用户看到的是"点了没反应"。
        //
        // 直接指向 EngineActivity，**不经过 MainActivity**：
        //   · MainActivity 只是个立即 `finish()` 的跳板，Android 12+ 对
        //     "通知 -> 中转 Activity -> 真正页面"有 trampoline 限制；
        //   · EngineActivity 是 exported=false，但 PendingIntent 以本应用身份
        //     发出，系统允许它启动本应用的私有 Activity。
        // `CLEAR_TOP | SINGLE_TOP`：App 已在后台时把原页面带回前台并复用，
        // 不叠出第二份聊天页（不会丢当前会话 / 输入框里还没发的字）。
        val openApp = Intent(this, EngineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalLLM Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            // category/visibility 辅助 OEM 的可感知性判定
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        // ⚠ 这里**不再**顺手推药丸状态：药丸的状态源已改为进程级的
        //   [ModelStatusManager]，它由**事实发生处**上报（引擎 load/unload、
        //   服务起停、推理 begin/end），而不是由"通知文案更新"这一出口旁路。
        //   挂在通知上必然漏：通知文案有九处更新点，而模型换格/推理开始结束
        //   根本不在这些点上。
    }

    override fun onDestroy() {
        // 非用户意图的销毁（系统回收、OEM 冻结杀进程）也走 stop()：
        // 本进程的监听 socket 与在途连接必须收敛，不留给"进程内的僵尸 LISTEN"。
        // 注意这与 A-2 的 desired 语义**不冲突**：服务真销毁了就不该再自愈，
        // 下次启动由 ACTION_START / STICKY 分支重新置 desired。
        HttpApi.stop()
        // 悬浮球的状态源：服务真销毁了，读数要立刻变 ⚪（用户意图停止走 ACTION_STOP
        // 已报一次，这里是系统回收 / OEM 杀进程的兜底）。幂等。
        ModelStatusManager.setServiceRunning(false)
        // 非用户意图的销毁（系统回收 / OEM 杀进程）同样可能留下在途租约；
        // 进程若还能执行到这里，就把系统锁摘掉。进程被直接杀死时系统会回收
        // WakeLock，但正常 onDestroy 这一条不该依赖那个兜底。
        RequestWakeLock.forceRelease()
        super.onDestroy()
    }
}
