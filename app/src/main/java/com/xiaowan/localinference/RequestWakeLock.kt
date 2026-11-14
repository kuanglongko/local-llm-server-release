package com.xiaowan.localinference

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 请求级 `PARTIAL_WAKE_LOCK` 的 Android 侧门面。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 它解决的是什么
 * ═══════════════════════════════════════════════════════════════════════════
 * 前台服务只保证「进程被系统当作前台」，**不保证 CPU 在整段推理期间不睡**：
 * 客户端已经收到 200、SSE 心跳也还在写，但屏幕熄灭 / Doze 之后 CPU 仍可能被
 * 降频或挂起，表现为「一条长回答生成到一半突然变慢、甚至几十秒没有新帧」。
 * 请求级锁把「这一条连接正在推理」这个事实告诉系统，直到响应最后一个字节
 * 写完（`handleRelayChat` 的 `finally` 补写也算在内）才放。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么是**一把共享锁 + 引用计数**，而不是每请求一把
 * ═══════════════════════════════════════════════════════════════════════════
 * 每请求一把锁的泄漏面随并发数增长，且 `release` 漏一把就是一把独立泄漏；
 * 本类只建一把锁，`WakeLeaseCore` 负责「第一个请求拿、最后一个请求放」。
 * 每个租约仍各有独立的 30 分钟超时 —— 超时只摘掉它自己那份引用，不影响
 * 其它正在推理的请求（判据见 `WakeLeaseCore`）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 拿不到锁时**不阻断推理**
 * ═══════════════════════════════════════════════════════════════════════════
 * `WAKE_LOCK` 是普通权限，正常安装即有；但 `PowerManager` 仍可能因为 OEM /
 * 系统服务异常而抛。`WakeLeaseCore` 会把失败折成「继续跑 + 一行日志」，
 * `/health` 上则表现为 `wake_lock_held=false`、`wake_lock_refs>0` —— 让真机上
 * 「请求在跑，但没拿到系统锁」可被当场看见，而不是靠体感猜。
 */
object RequestWakeLock {

    /**
     * 单个租约的超时。30 分钟按用户确认的档位：
     * 覆盖 HTP 正常速度与绝大多数 CPU 长生成，同时把泄漏时的耗电上界压在一小时内。
     * 超时**不是**「请求最多跑 30 分钟」：它只释放这一份引用，推理本身不受影响。
     */
    const val TIMEOUT_MS: Long = 30L * 60L * 1000L

    private const val TAG_NAME = "LocalLLM:inference"

    @Volatile
    private var core: WakeLeaseCore? = null

    /**
     * 由 `App.onCreate` 调用一次（进程级），`InferenceService.onCreate` 再调一次。
     * 重复调用是 no-op。
     *
     * 为什么必须两处都有：App 内对话不经过 `InferenceService`，只在服务里初始化
     * 会让「只开 App 聊天、没启动常驻服务」拿到 null 租约；而 HTTP 一侧又可能
     * 在服务重启窗口里短暂并存两代监听器。`WakeLeaseCore` 必须**全进程一份**，
     * 否则「两把系统锁 + 两套计数」与「每请求一把」是同一个坏法。
     */
    fun init(ctx: Context) {
        if (core != null) return
        synchronized(this) {
            if (core != null) return
            val app = ctx.applicationContext ?: ctx
            val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = try {
                power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_NAME)?.apply {
                    // 共享锁由这一层自己数引用；系统侧的引用计数必须关掉，
                    // 否则 acquire/release 的配对会在「0 -> 1 / 1 -> 0」之外
                    // 再叠一层系统计数，漏一次就永久持有。
                    setReferenceCounted(false)
                }
            } catch (t: Throwable) {
                LlmEngine.uiLog("[wakelock] 创建 PARTIAL_WAKE_LOCK 失败（后续请求继续推理，不持有系统锁）：${t.message}")
                null
            }
            // 正常归还时会 cancel 掉这份 30 分钟的任务。`ScheduledThreadPoolExecutor`
            // 默认**不会**把已取消的任务从延迟队列里移除 —— 高并发下取消项会一直
            // 攒到原定超时时刻（30 分钟），看起来"锁都放对了"，进程内存却随请求
            // 数增长。`removeOnCancelPolicy = true` 让取消当场生效。
            val scheduler = ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "llm-wakelock-timeout").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
            core = WakeLeaseCore(
                timeoutMs = TIMEOUT_MS,
                platformAcquire = {
                    // 空 = 初始化时就没建出来（已留过日志）；返回 false 由 core 统一报。
                    if (wakeLock == null) false else {
                        wakeLock.acquire()
                        true
                    }
                },
                platformRelease = {
                    if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
                },
                scheduleTimeout = { delayMs, task ->
                    val future = scheduler.schedule(
                        task, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    // 返回「取消」函数。必须显式接住再返回：直接写
                    // `scheduler.schedule(...)\n{ future.cancel(false) }` 会被 Kotlin
                    // 解析成给 schedule 传了第四个参数（尾随 lambda），
                    // 而不是返回一个取消闭包 —— kt_check 报的是
                    // “actual type is Unit, but Function0<Unit> was expected”。
                    val cancel: () -> Unit = { future.cancel(false) }
                    cancel
                },
                log = { LlmEngine.uiLog(it) },
            )
        }
    }

    /** 申请一个请求租约；未初始化时返回 null（调用方仍应有配对的 release）。 */
    fun acquire(): WakeLeaseCore.Lease? = core?.acquire()

    /** 归还租约。幂等；`null` 安全。 */
    fun release(lease: WakeLeaseCore.Lease?) {
        core?.release(lease)
    }

    /** 服务停止/销毁时的兜底：摘掉全部租约并释放系统锁。返回摘掉的租约数。 */
    fun forceRelease(): Int = core?.forceRelease() ?: 0

    /** `/health` 的最小读数；未初始化时四项都是 0 / false。 */
    fun snapshot(): WakeLeaseCore.Snapshot =
        core?.snapshot() ?: WakeLeaseCore.Snapshot(
            held = false, refs = 0, timeouts = 0, forceReleases = 0)
}
