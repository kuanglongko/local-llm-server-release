package com.xiaowan.localinference

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级「模型状态」总线：把散在三条入口上的事实汇成**一份**可订阅的读数。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么要有这一层（而不是让悬浮球继续读 ModelLifecycle.snapshot()）
 * ═══════════════════════════════════════════════════════════════════════════
 * 悬浮球药丸要回答的问题比"按需加载状态机现在在哪一格"更宽：
 *
 *   · [serviceRunning] —— 常驻推理服务到底起没起（[HttpApi.isRunning] 只是
 *     "端口在监听"，而服务停止是一条**独立**的事实，与模型在不在内存里无关）；
 *   · [state] —— 模型处于 UNLOADED / LOADING / LOADED / UNLOADING / ERROR
 *     哪一格。**刻意复用** [ModelLifecycleCore.State]，不另立一套词汇：
 *     同一个进程里不该有两个 "loading" 的含义，否则药丸与日志会各说各话；
 *   · [activeInferences] —— **两条入口共用**的活跃推理计数（App 内聊天 + HTTP）。
 *     这一份**不复用** [ModelLifecycleCore.activeInferences]：那个计数只在
 *     "按需加载"开关打开时才登记（关着时恒 0，它服务的判据是"能不能空闲卸载"）。
 *     药丸要的是"此刻到底有没有在算"，与按需开关无关 —— 常驻模式下同样要有
 *     🔵。所以这里用一枚 [AtomicInteger]，由**所有**推理入口在 begin/end 时上报，
 *     只用于**显示**，不参与任何卸载决策（那些仍归状态机）。
 *   · [modelName] —— 当前模型别名（[ModelLibrary.aliasOf]）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 状态从哪来：**引擎的进出口**，不是状态机的推断
 * ═══════════════════════════════════════════════════════════════════════════
 * 模型真实的加载/卸载**只有两个进出口**：[LlmEngine.loadModel] 与
 * [LlmEngine.unload]（手动加载、服务启动加载、按需加载三条路最终都走它们）。
 * 让这两处直接上报，得到的读数在**任何模式**下都与引擎真值一致 ——
 * 包括常驻模式（按需开关关着、状态机不登记 LOADING 的那一档），
 * 也包括按需模式里"取消卸载又回到 LOADED"这类状态机内部的取消窗口
 * （那时引擎从未真的释放，报 LOADED 才是事实）。
 *
 * ⚠ 本类**零 android 依赖**：读数由 [snapshot] 暴露，用例可在宿主上穷举
 *   （`tools/run_floating_ball_tests.sh`）。订阅回调在**调用者线程**上触发，
 *   View 只能在主线程碰，所以控制器侧必须自己切主线程（见 FloatingBallController）。
 */
object ModelStatusManager {

    /**
     * 一次读数。字段与药丸的判据一一对应，不再另做二次加工。
     *
     * [state] 复用 [ModelLifecycleCore.State]：UNLOADED / LOADING / LOADED /
     * UNLOADING / ERROR。注意它描述的是**模型**，不描述服务 —— 服务在与不在
     * 是 [serviceRunning] 这一格。
     */
    data class Snapshot(
        val serviceRunning: Boolean,
        val state: ModelLifecycleCore.State,
        val activeInferences: Int,
        val modelName: String?,
    )

    /**
     * 订阅者。回调可能来自任意线程（加载线程 / HTTP 线程 / 生成线程），
     * 实现方负责切到能碰 View 的线程。
     */
    fun interface Listener {
        fun onModelStatusChanged(snapshot: Snapshot)
    }

    @Volatile private var serviceRunning = false

    @Volatile private var state: ModelLifecycleCore.State = ModelLifecycleCore.State.UNLOADED

    @Volatile private var modelName: String? = null

    /**
     * 两条入口共用的活跃推理计数。
     *
     * `beginInference`/`endInference` 必须**成对**（推理入口都用 try/finally 包住）：
     * 漏一次 end 会让药丸永久停在 🔵。归零是幂等的——即使某条异常路径多调了
     * 一次 end，也只是夹到 0，不会变成负数（负数会让"有没有在算"的判据失真）。
     */
    private val active = AtomicInteger(0)

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 上一次已广播的读数：用来去重（1→2 路并发不该再推一次）。 */
    private val notifyLock = Any()
    private var lastNotified: Snapshot? = null

    fun snapshot(): Snapshot =
        Snapshot(serviceRunning, state, active.get().coerceAtLeast(0), modelName)

    // ---- 订阅 ----

    fun subscribe(listener: Listener) {
        // 幂等：重复订阅（例如服务被系统重启后控制器重建）不叠第二份回调，
        // 否则同一次状态变化会把 View 刷两遍。
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unsubscribe(listener: Listener) {
        listeners.remove(listener)
    }

    // ---- 服务运行（InferenceService 上报）----

    fun setServiceRunning(running: Boolean) {
        if (serviceRunning == running) return
        serviceRunning = running
        notifyChanged()
    }

    // ---- 模型状态（LlmEngine 的两个进出口上报）----

    fun onModelLoading(name: String?) {
        state = ModelLifecycleCore.State.LOADING
        modelName = name
        notifyChanged()
    }

    fun onModelLoaded(name: String?) {
        state = ModelLifecycleCore.State.LOADED
        modelName = name
        notifyChanged()
    }

    fun onModelUnloading() {
        state = ModelLifecycleCore.State.UNLOADING
        notifyChanged()
    }

    fun onModelUnloaded() {
        state = ModelLifecycleCore.State.UNLOADED
        modelName = null
        notifyChanged()
    }

    fun onModelError() {
        state = ModelLifecycleCore.State.ERROR
        modelName = null
        notifyChanged()
    }

    // ---- 活跃推理（所有推理入口上报）----

    fun beginInference() {
        val n = active.incrementAndGet()
        // 只有 0→1 才改变药丸的显示（1→2 路并发仍是 🔵），不必重复广播。
        if (n == 1) notifyChanged()
    }

    fun endInference() {
        val n = active.updateAndGet { if (it > 0) it - 1 else 0 }
        if (n == 0) notifyChanged()
    }

    // ---- 广播 ----

    /**
     * 读数有"药丸看得见的变化"就广播一次。
     *
     * 判据里 `activeInferences` 只比 `> 0`：1→2 或 2→1 不改变药丸，
     * 重复广播只是把主线程消息队列塞满。
     */
    private fun notifyChanged() {
        val s = snapshot()
        synchronized(notifyLock) {
            val prev = lastNotified
            if (prev != null &&
                prev.serviceRunning == s.serviceRunning &&
                prev.state == s.state &&
                prev.modelName == s.modelName &&
                (prev.activeInferences > 0) == (s.activeInferences > 0)) return
            lastNotified = s
        }
        for (l in listeners) {
            // 一个订阅者抛异常不能拖垮其它订阅者，更不能打断上报方（它可能是
            // 生成线程的 finally）——否则"退出时忘了减计数"会变成"计数永远不减"。
            runCatching { l.onModelStatusChanged(s) }
        }
    }
}
