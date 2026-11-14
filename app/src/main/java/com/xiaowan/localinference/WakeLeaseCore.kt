package com.xiaowan.localinference

/**
 * 「请求级 PARTIAL_WAKE_LOCK」的纯逻辑层：**引用计数 + 每租约独立超时 + 幂等归还**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么把这一层从 Android 里拆出来
 * ═══════════════════════════════════════════════════════════════════════════
 * `PowerManager.WakeLock` 在宿主上编不进来，而这一层的失效**全是静默的**：
 *   · 漏 release —— 系统锁永久持有，耗电与「服务明明闲着却不让 CPU 睡」；
 *   · 重复 release —— 引用计数被多减一次，别人的推理在锁外跑（或被提前放锁）；
 *   · 超时释放时把**整把锁**放掉 —— 另一个仍在推理的请求失去保护；
 *   · forceRelease 之后旧租约又回来 release —— 把新一代的引用减掉。
 * 这四种都不抛异常、不报错，只表现为「有时候快、有时候慢」或「电耗异常」。
 * 所以判据放在纯逻辑层，由宿主单测逐条钉住（`tools/run_wake_lock_tests.sh`）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 共享一把锁，引用计数到零才放
 * ═══════════════════════════════════════════════════════════════════════════
 * 每请求各建一把 `WakeLock` 会让系统的锁表随并发数增长，且 release 漏一把就是
 * 一把独立泄漏。这里统一成**一把** `PARTIAL_WAKE_LOCK`：
 *   · 第一个租约（`refs` 0 → 1）时申请系统锁；
 *   · 中间租约进出不碰系统锁；
 *   · 最后一个租约（`refs` → 0）时释放系统锁。
 * 申请失败不阻断推理（那是保活增强，不是协议前置条件），只把 `held` 报成 false
 * 并留一行日志 —— `/health` 的 `wake_lock_held=false` 与 `wake_lock_refs>0`
 * 并排出现时，读的人能立刻看出「请求在跑，但系统锁没拿到」。
 *
 * @param timeoutMs 每个租约自己的超时（从 acquire 那一刻起算）。超时只摘掉
 *                   **这一个租约**的引用；引用计数归零时才真的放系统锁。
 * @param platformAcquire 真正申请系统锁的回调。返回 false 或抛异常 = 申请失败。
 * @param platformRelease 真正释放系统锁的回调。
 * @param scheduleTimeout 注册一次性超时任务，返回「取消」函数。抽成参数是为了
 *                        宿主单测能用假调度器精确触发某一租约的超时。
 * @param log 失败 / 超时 / 强制释放的日志出口（测试里可收集文本）。
 */
class WakeLeaseCore(
    private val timeoutMs: Long,
    private val platformAcquire: () -> Boolean,
    private val platformRelease: () -> Unit,
    private val scheduleTimeout: (Long, () -> Unit) -> (() -> Unit),
    private val log: (String) -> Unit = {},
) {
    /**
     * 一次请求持有的租约。**只由 [acquire] 构造**；调用方拿到后只需在
     * `finally` 里交回 [release]。`id` 用于区分「同一租约重复 release」与
     * 「新租约用了旧编号」—— 前者必须是 no-op，后者必须正常计数。
     */
    class Lease internal constructor(internal val id: Long)

    /**
     * `/health` 的最小读数。
     *
     * `held` 是**系统锁真的在不在**（申请失败时为 false），`refs` 是当前活着的
     * 请求租约数。两者允许出现 `held=false, refs>0`（申请失败）与
     * `held=true, refs=0`（理论上不该有；出现即说明释放在某个边界上漏了）。
     */
    data class Snapshot(
        val held: Boolean,
        val refs: Int,
        val timeouts: Int,
        val forceReleases: Int,
    )

    private val lock = Any()
    /** 当前活着的租约 id。用「在不在集合里」判幂等，不拿引用计数反推。 */
    private val active = HashSet<Long>()
    /** 租约 id -> 取消超时任务。超时任务自己触发时取消自己是无害的。 */
    private val cancels = HashMap<Long, () -> Unit>()
    private var nextId = 1L
    private var refs = 0
    private var platformHeld = false
    private var timeouts = 0
    private var forcedLeases = 0

    /**
     * 申请一个租约。**不返回 null**：拿不到系统锁也照常返回（推理继续），
     * 失败由日志与 [Snapshot] 如实报出。调用方必须用 `try/finally` 归还。
     */
    fun acquire(): Lease {
        val lease: Lease
        var acquireFailure: Throwable? = null
        var acquireReturnedFalse = false
        synchronized(lock) {
            lease = Lease(nextId++)
            refs++
            active.add(lease.id)
            // 只在 0 -> 1 这一跳碰系统锁。中间租约不申请；申请失败也**不重试**，
            // 否则一次系统抽风会在每个请求上重复打日志、重复撞同一个异常。
            // 这一批请求结束后 refs 归零，下一个请求才有资格重新申请。
            if (refs == 1) {
                try {
                    if (!platformAcquire()) acquireReturnedFalse = true
                    else platformHeld = true
                } catch (t: Throwable) {
                    acquireFailure = t
                }
            }
        }
        if (acquireFailure != null) {
            log("[wakelock] 获取系统锁失败（本轮继续推理，不降级成 503）：${acquireFailure.message}")
        } else if (acquireReturnedFalse) {
            log("[wakelock] 获取系统锁失败（本轮继续推理，不降级成 503）：PowerManager 不可用")
        }
        val cancel = try {
            scheduleTimeout(timeoutMs) { onLeaseTimeout(lease) }
        } catch (t: Throwable) {
            // 超时兜底注册失败不能让请求失败；但必须留痕：这个租约此后只能靠
            // 正常 release / forceRelease 收尾，泄漏时没有第二道保险。
            log("[wakelock] 超时兜底注册失败（租约 ${lease.id} 只能等 release/forceRelease）：${t.message}")
            null
        }
        if (cancel != null) {
            var stale = false
            synchronized(lock) {
                // 极端情况下租约在 scheduleTimeout 返回前就已经 release/forceRelease：
                // 这时不能再把 cancel 塞进表里（那会留下一个永不执行的取消项），
                // 而是当场取消这次注册 —— 与「超时任务看到租约已不在 active 里」
                // 是同一条幂等判据的两端。
                if (active.contains(lease.id)) cancels[lease.id] = cancel else stale = true
            }
            if (stale) runCatching { cancel() }
        }
        return lease
    }

    /**
     * 归还一个租约。**幂等**：同一份 lease 重复归还、`null`、forceRelease 之后
     * 迟到的归还，全部是 no-op，且不会改动任何计数。
     */
    fun release(lease: Lease?) {
        if (lease == null) return
        releaseInternal(lease, timedOut = false)
    }

    /**
     * 服务停止时的强制收尾：摘掉**全部**活着的租约、取消它们的超时任务、
     * 引用归零并释放系统锁。返回这次被强制摘掉的租约数。
     *
     * 之后旧租约再调用 [release] 不会二次减计数（它们已不在 `active` 里）——
     * 否则一次停止会让「下一代」请求的引用被提前清掉。
     */
    fun forceRelease(): Int {
        var dropped = 0
        var cancelList: List<() -> Unit> = emptyList()
        var releaseFailure: Throwable? = null
        var didRelease = false
        synchronized(lock) {
            dropped = active.size
            active.clear()
            cancelList = cancels.values.toList()
            cancels.clear()
            refs = 0
            if (dropped > 0) forcedLeases += dropped
            if (platformHeld) {
                platformHeld = false
                didRelease = true
                // ⚠ 平台释放必须在锁内完成：若把 release 放到锁外，refs 归零与
                // 真正放锁之间会有一个窗口，新请求可以在那里看到 platformHeld=false、
                // 重新 acquire 同一把共享锁 —— 然后旧释放回来把它放掉。核心计数说
                // 「held=true」，系统锁其实已经不在了，而这个组合没有任何报错。
                releaseFailure = releasePlatformLocked()
            }
        }
        for (cancel in cancelList) runCatching { cancel() }
        if (releaseFailure != null) logReleaseFailure(releaseFailure)
        if (dropped > 0) {
            log("[wakelock] forceRelease：摘掉 $dropped 个未归还的租约")
        } else if (didRelease) {
            log("[wakelock] forceRelease：引用已归零，补释放一次系统锁")
        }
        return dropped
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            held = platformHeld,
            refs = refs,
            timeouts = timeouts,
            forceReleases = forcedLeases,
        )
    }

    private fun onLeaseTimeout(lease: Lease) {
        if (!releaseInternal(lease, timedOut = true)) return
        val left = snapshot().refs
        log("[wakelock] 租约 ${lease.id} 超过 ${timeoutMs / 60_000} 分钟未归还，" +
            "已单独释放（剩余引用 $left" +
            (if (left == 0) "，系统锁已放" else "，系统锁仍由其它请求持有") + "）")
    }

    private fun releaseInternal(lease: Lease, timedOut: Boolean): Boolean {
        var cancel: (() -> Unit)? = null
        var releaseFailure: Throwable? = null
        synchronized(lock) {
            // 「在不在 active」是幂等的唯一判据：不能拿 refs 判（重复 release 会把
            // 别人的引用减掉），也不能拿 cancel 判（超时任务自己可能已经把它取走）。
            if (!active.remove(lease.id)) return false
            cancel = cancels.remove(lease.id)
            refs--
            if (timedOut) timeouts++
            if (refs == 0 && platformHeld) {
                platformHeld = false
                // 与 forceRelease 同一条纪律：锁内完成平台释放，避免 refs 归零
                // 与真正放锁之间的窗口被新请求插进来（见 forceRelease 的说明）。
                releaseFailure = releasePlatformLocked()
            }
        }
        if (cancel != null) runCatching { cancel() }
        if (releaseFailure != null) logReleaseFailure(releaseFailure)
        return true
    }

    /** 在核心锁内调用平台释放，异常留到锁外记日志（日志出口可能很慢）。 */
    private fun releasePlatformLocked(): Throwable? = try {
        platformRelease()
        null
    } catch (t: Throwable) {
        // 放锁失败比拿锁失败更少见，但同样不能把请求/超时线程打崩。
        t
    }

    private fun logReleaseFailure(t: Throwable) {
        log("[wakelock] 释放系统锁失败：${t.message}")
    }
}
