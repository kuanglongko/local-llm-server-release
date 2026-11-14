package com.xiaowan.localinference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 悬浮球的**独立宿主**：只负责窗口 / 亮灭屏 / 药丸生命周期，别的什么都不做。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么从 [InferenceService] 里搬出来（而不是继续挂在它身上）
 * ═══════════════════════════════════════════════════════════════════════════
 * 旧实现把控制器挂在常驻推理服务上，于是"显示一个球"与"跑本地推理"被绑成
 * 一件事：
 *   · 用户**不想**起推理服务、只想要个状态球时，做不到 —— 球没有独立入口；
 *   · 服务停 → 球消失。可球要显示的恰恰是"服务停没停"，它不该随被观察者
 *     一起消失；
 *   · 推理服务的生命周期（加载模型、HTTP 监听、唤醒锁）与一个 overlay
 *     窗口的生命周期**风险等级完全不同**，混在一起就没有单独的失败域。
 *
 * 所以拆成两个进程级前台服务，各自独立起停：
 *   · [InferenceService] —— 加载模型 / 监听端口 / 推理（本类**完全不碰**）；
 *   · 本类              —— 窗口 / 亮灭屏 / 药丸（**不**加载模型、**不**开端口、
 *     **不**持 WakeLock、**不**推理）。
 *
 * 两者之间**没有**直接调用，只通过进程级的 [ModelStatusManager] 交换事实：
 * 推理侧往里写（服务起停 / 模型加载卸载 / 推理开始结束），本类订阅它、
 * 变化时刷一次药丸。[EngineActivity] 的开关是拉起/停掉**本类**的唯一入口。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么必须是前台服务
 * ═══════════════════════════════════════════════════════════════════════════
 * overlay 窗口本身不需要前台服务；需要的是**进程不被随手回收**：
 * 一个只挂广播、没有可见组件的进程，在后台随时可能被清掉，于是"球不知何时
 * 就没了"。前台服务给的是"这个进程此刻有用户可见的职责"这一层含义 ——
 * 它**不是**保活开关（不改 oom_score_adj、不换冻结豁免），只是让职责如实可见。
 *
 * ⚠ 本类**不**做任何推理/网络：它的用途说明写在 AndroidManifest 的
 *   `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 里，改它之前先读那份说明。
 */
class FloatingBallService : Service() {

    companion object {
        const val ACTION_START = "com.xiaowan.localinference.FLOATING_BALL_START"
        const val ACTION_STOP = "com.xiaowan.localinference.FLOATING_BALL_STOP"

        // 与推理服务**各用各的渠道**：用户可以在系统设置里单独关掉"悬浮球"
        // 这一条通知，而不影响推理服务的通知（或反过来）。
        private const val CHANNEL_ID = "floating_ball_v1"
        private const val NOTIF_ID = 2

        /**
         * 拉起本服务（幂等）。开关**已由调用方落盘** —— 这里只负责把服务拉起来，
         * 不读也不写开关（写盘只发生在 [FloatingBallSettings] 的调用点上）。
         */
        fun start(ctx: Context) {
            val app = ctx.applicationContext
            runCatching {
                // minSdk=26，`startForegroundService` 恒可用。本服务必须在 onCreate
                // 里立刻 `startForeground`，否则系统在 5s 后判 ANR。
                app.startForegroundService(
                    Intent(app, FloatingBallService::class.java).setAction(ACTION_START))
            }.onFailure { LlmEngine.uiLog("[悬浮球] 启动悬浮球服务失败：${it.message}") }
        }

        /**
         * 冷启动恢复：进程（重新）起来时，把上次开着的悬浮球接回来。
         *
         * 为什么需要单独一条路：开关只是"用户意图"，它不会自己拉起服务。
         * 用户关掉 App / 进程被系统清掉后，[start] 的唯一调用点（设置页开关）
         * 不会在冷启动时再跑一遍 —— 于是出现"开关明明开着，进来却没有球，
         * 关一下再开才回来"。这里在 [App.onCreate] 里按同一份判据补一次，
         * 让"意图"跨进程生效。
         *
         * 只读、不写任何设置：权限被撤时**不**在启动路径里改开关（那时可能
         * 是灭屏/后台，`canDrawOverlays` 的读数不可靠）；交给设置页的
         * `onReturnFromOverlaySettings` 在用户真正看见界面时处理。
         */
        fun restore(ctx: Context) {
            val app = ctx.applicationContext
            if (FloatingBallSettings.enabled(app) && FloatingBallSettings.canDrawOverlays(app)) {
                start(app)
            }
        }

        /** 停掉本服务（幂等；没在跑时是 no-op）。 */
        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            runCatching { app.stopService(Intent(app, FloatingBallService::class.java)) }
                .onFailure { LlmEngine.uiLog("[悬浮球] 停止悬浮球服务失败：${it.message}") }
        }

        /**
         * 长按复制的服务地址（含 `/v1`）。
         *
         * 与通知里的那一行**同一个判据**（`bindAll` → 局域网 IP，否则回环）：
         * 用户从通知抄下来的地址与长按复制的地址必须是同一个，
         * 否则"我复制的这个打不开、通知里那个能打开"会变成一条查不出原因的现场。
         */
        fun serverUrl(): String {
            val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
            return "http://$host:${HttpApi.PORT}/v1"
        }
    }

    /** 药丸控制器。本服务的进程内实例；[onDestroy] 后置空。 */
    @Volatile private var controller: FloatingBallController? = null

    /** 订阅句柄：反注册时要用**同一个**引用（lambda 每次都是新对象）。 */
    @Volatile private var listener: ModelStatusManager.Listener? = null

    /** 状态回调的落点：View 只能在主线程碰，而状态变化可能来自任意线程。 */
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 前台服务必须在 onCreate（或 onStartCommand 之前）立刻进入前台。
        startForeground(NOTIF_ID, buildNotification())
        val c = FloatingBallController(
            context = applicationContext,
            // 这一层让药丸读到的永远是**进程级真值**，而不是本服务自己攒的
            // 第二份状态 —— 服务起停/模型换格只有一处出处（ModelStatusManager）。
            status = { ModelStatusManager.snapshot() },
            serverUrl = { serverUrl() },
            // 权限被运行期撤销：控制器那边已把开关落成关并说清原因；
            // 这里停掉自己，不留"服务在跑、球却永远建不出来"的空转进程。
            onDisabledByPermissionLoss = { main.post { stopSelf() } },
            log = { msg -> LlmEngine.uiLog(msg) },
        )
        controller = c
        val l = ModelStatusManager.Listener { onStatus() }
        listener = l
        ModelStatusManager.subscribe(l)
        // 开关关着时 start() 内部一行也不做（不注册广播、不建窗口）；
        // 打开的那一档才注册亮灭屏并按当前屏幕状态落一次窗口。
        c.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 开关是唯一入口：若它被非本服务的路径关掉（或授权被撤、控制器回调到
        // stopSelf 之前的竞态），这里兜一次，不让服务空转着挂一条通知。
        if (!FloatingBallSettings.enabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // STICKY：进程被系统回收后由系统重建（null intent 也落在这里），
        // 重建后 onCreate 会重新订阅并显示药丸 —— 球不该因为一次回收就消失。
        return START_STICKY
    }

    /**
     * 旋转/分辨率变化时重算药丸位置。
     *
     * 控制器自己收不到这个回调（它不是 View/Activity）；Service 是它的宿主，
     * 所以由这里转发。不转发的话，横竖屏切换后药丸还按旧屏幕宽高夹取，
     * 可能停在屏幕外 —— 症状是"转一下球就不见了"。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    /**
     * [ModelStatusManager] 的订阅回调（可能来自加载线程 / HTTP 线程 / 生成线程）。
     * View 只能在主线程碰，所以先切主线程；投递时再取一次 controller，避免
     * 服务在投递与执行之间被销毁后还去碰旧 View。
     */
    private fun onStatus() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            controller?.onStatusChanged()
        } else {
            main.post { controller?.onStatusChanged() }
        }
    }

    override fun onDestroy() {
        listener?.let { ModelStatusManager.unsubscribe(it) }
        listener = null
        // 反注册亮灭屏广播 + removeView + 清引用。
        controller?.stop()
        controller = null
        super.onDestroy()
    }

    /**
     * 建通知渠道（幂等）。系统里对已存在的渠道是 no-op，用户改过的设置不会被覆盖。
     *
     * 用 LOW + 显式静音：这是一条**状态展示**通知，不是要引起注意的事件；
     * 响铃/振动/角标全关，只求"进程有用户可见职责"这一层含义。
     */
    private fun ensureChannel(nm: NotificationManager) {
        val ch = NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_LOW)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        ensureChannel(nm)
        // 通知可点开 App，直接指向 EngineActivity（不经 MainActivity 跳板）。
        val openApp = Intent(this, EngineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalLLM 悬浮球")
            .setContentText("在其它应用上层显示本地推理状态")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }
}
