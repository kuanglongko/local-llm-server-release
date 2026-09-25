package com.xiaowan.localinference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
        // 渠道创建后 importance 即锁定，提级必须换新 ID 才生效；旧 ID 在下方删除
        private const val CHANNEL_ID = "llm_server_v2"
        private const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                if (LlmEngine.hasModel) {
                    try {
                        LlmEngine.unload()
                        LlmEngine.uiLog("[服务] 已停止并卸载模型（内存已释放）")
                    } catch (t: Throwable) {
                        LlmEngine.uiLog("[服务] 卸载模型异常: ${t.message}")
                    }
                } else {
                    LlmEngine.uiLog("[服务] 已停止（无已加载模型）")
                }
                HttpApi.currentModel = null
                // 保证下次「启动服务」必走完整加载流程：切换的 ctx/线程/GPU 参数生效、日志刷新。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 已有模型（UI 先加载 / 本地模式）→ 直接挂到服务上，不重复加载
                if (LlmEngine.hasModel) {
                    startForeground(NOTIF_ID, buildNotification("启动中…"))
                    HttpApi.appContext = applicationContext
                    HttpApi.PORT = ModelStore.serverPort(applicationContext)
                    HttpApi.bindAll = ModelStore.lanAccess(applicationContext)
                    applyCorsWhitelist(applicationContext)
                    applyApiToken(applicationContext)
                    HttpApi.start()
                    LlmEngine.uiLog("[服务] 检测到已加载模型，直接挂载: ${LlmEngine.modelDesc()}（未重新加载）")
                    Thread {
                        try { Thread.sleep(400) } catch (_: InterruptedException) {}
                        val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                        updateNotification("运行中 http://$host:${HttpApi.PORT}（已挂载现有模型）")
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
                val flash = (intent.getIntExtra(EXTRA_FLASH, -1)).let { if (it >= 0) it != 0 else ModelStore.flashAttn(applicationContext) }
                val ck = intent.getIntExtra(EXTRA_CACHE_K, 0)
                val cv = intent.getIntExtra(EXTRA_CACHE_V, 0)
                val mm = intent.getIntExtra(EXTRA_MMAP, 1) != 0
                val parallelN = intent.getIntExtra(EXTRA_PARALLEL_N, 0)
                val batchSize = intent.getIntExtra(EXTRA_BATCH_SIZE, 0)
                val ubatchSize = intent.getIntExtra(EXTRA_UBATCH_SIZE, 0)
                startForeground(NOTIF_ID, buildNotification("启动中…"))
                // HTTP 先于模型加载启动 —— /health 立即可达，
                // 加载再慢或失败也能从 /health 看到状态，不再出现"服务连不上"。
                HttpApi.currentModel = ModelStore.aliasOf(applicationContext, File(path).name)
                HttpApi.appContext = applicationContext
                // start 前从设置注入自定义端口
                HttpApi.PORT = ModelStore.serverPort(applicationContext)
                // 注入局域网访问开关
                HttpApi.bindAll = ModelStore.lanAccess(applicationContext)
                applyCorsWhitelist(applicationContext)
                applyApiToken(applicationContext)
                // 注入思考默认开关
                HttpApi.disableThinkingDefault = ModelStore.disableThinking(applicationContext)
                HttpApi.start()
                Thread {
                    if (!LlmEngine.init(applicationContext)) {
                        updateNotification("初始化失败：native 引擎不可用（/health 可查状态）")
                        return@Thread
                    }
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
                    val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                    updateNotification("运行中 http://$host:${HttpApi.PORT}")
                }.start()
            }
            else -> {
                // intent==null = 系统杀进程后 STICKY 自动重启。
                // 历史缺陷：此分支空转 —— startForeground 未调用（超时 ANR 风险）且模型不加载
                // → 出现「PID 在但端口不监听」的僵尸态，必须手动打开 App 才能恢复。
                // 现自动用 ModelStore 保存的选中模型与参数重载，实现断联自愈。
                startForeground(NOTIF_ID, buildNotification("自动恢复中…"))
                val name = ModelStore.selectedName(applicationContext)
                val mf = if (name.isNotBlank()) ModelStore.findByName(applicationContext, name) else null
                if (mf != null && mf.isFile) {
                    HttpApi.currentModel = ModelStore.aliasOf(applicationContext, mf.name)
                    HttpApi.appContext = applicationContext
                    HttpApi.PORT = ModelStore.serverPort(applicationContext)
                    HttpApi.bindAll = ModelStore.lanAccess(applicationContext)
                    applyCorsWhitelist(applicationContext)
                    applyApiToken(applicationContext)
                    HttpApi.disableThinkingDefault = ModelStore.disableThinking(applicationContext)
                    HttpApi.start()
                    LlmEngine.uiLog("[服务] 检测到系统重启服务，自动恢复: ${mf.name}")
                    val p = ModelStore.engineParams(applicationContext)
                    val gpu = p["gpu"]?.toIntOrNull() ?: 0
                    val ctxSize = p["ctx"]?.toIntOrNull() ?: 4096
                    val threads = p["threads"]?.toIntOrNull() ?: 4
                    val mmap = (p["mmap"]?.toIntOrNull() ?: 1) != 0
                    val ck = p["cacheK"]?.toIntOrNull() ?: 0
                    val cv = p["cacheV"]?.toIntOrNull() ?: 0
                    val parallelN = p["parallelN"]?.toIntOrNull() ?: 0
                    val batchSize = p["batchSize"]?.toIntOrNull() ?: 0
                    val ubatchSize = p["ubatchSize"]?.toIntOrNull() ?: 0
                    val flash = ModelStore.flashAttn(applicationContext) // 自动恢复：prefs 即最新（doLoad/toggleServer 均回写）
                    Thread {
                        if (!LlmEngine.init(applicationContext)) {
                            updateNotification("自动恢复失败：native 引擎不可用（/health 可查状态）")
                            return@Thread
                        }
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
        val t = ModelStore.apiToken(ctx)
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
        CorsPolicy.enabled = ModelStore.corsEnabled(ctx)
        CorsPolicy.clearOrigins()
        for (raw in ModelStore.corsExtraOrigins(ctx)) {
            if (!CorsPolicy.addOrigin(raw)) {
                LlmEngine.uiLog("[服务] CORS 白名单条目无效已忽略: $raw")
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // 渠道从 IMPORTANCE_LOW 提级到 IMPORTANCE_DEFAULT。
        // 背景：部分 OEM 的内存管理会把 LOW 级通知的 FGS 判为 non-perceptible
        // （日志特征："don't check adj for non-perceptible fgs app"），这是其后台冻结的判据之一；
        // DEFAULT 级可被识别为可感知前台服务，换取冻结豁免评估。
        // 有意不用 IMPORTANCE_HIGH：那会每次启动弹横幅+响铃，属于真实打扰，得不偿失。
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.deleteNotificationChannel("llm_server")  // 清理旧渠道，避免设置里残留死项
            val ch = NotificationChannel(CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_DEFAULT)
            ch.setSound(null, null)  // DEFAULT 但显式静音：提级只为感知判定，不做声音打扰
            ch.enableVibration(false)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalLLM Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            // category/visibility 辅助 OEM 的可感知性判定
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        // 非用户意图的销毁（系统回收、OEM 冻结杀进程）也走 stop()：
        // 本进程的监听 socket 与在途连接必须收敛，不留给"进程内的僵尸 LISTEN"。
        // 注意这与 A-2 的 desired 语义**不冲突**：服务真销毁了就不该再自愈，
        // 下次启动由 ACTION_START / STICKY 分支重新置 desired。
        HttpApi.stop()
        super.onDestroy()
    }
}
