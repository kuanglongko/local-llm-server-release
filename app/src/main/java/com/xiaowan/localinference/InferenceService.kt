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
                // 保证下次「启动服务」必走完整加载流程：切换的 ctx/线程/GPU 参数生效、日志刷新。
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
                HttpApi.stop()
                HttpApi.currentModel = null
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
                    stopSelf()
                }
            }
        }
        return START_STICKY
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
        HttpApi.stop()
        super.onDestroy()
    }
}
