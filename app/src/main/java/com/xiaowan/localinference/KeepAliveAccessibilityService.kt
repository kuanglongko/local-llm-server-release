package com.xiaowan.localinference

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

/**
 * 「后台保活」用的无障碍服务。**它不做任何辅助功能**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 这个类为什么存在，以及它为什么不监听任何事件
 * ═══════════════════════════════════════════════════════════════════════════
 * 背景（Issue #159）：本服务跑成前台服务后，部分 OEM 仍会在切后台时**冻结**进程
 * （cgroup freezer）。冻结期间端口仍在 `LISTEN`、TCP 握手照样完成，但**没人 accept**，
 * 调用方看到的是"请求一直挂着"，切回前台即恢复。
 *
 * 这类 ROM 的存活判定里有一条是"进程是否可感知"。[KeepAlive] 文件头已写明：
 * **内核的冻结判据只看 MemFree/SwapFree，与无障碍权限无关**，所以本服务**不是**
 * "开了就不会被冻"的开关。它能做的是让系统把本进程当作"用户显式在意的应用"，
 * 在部分 ROM 上抬高存活优先级；效果**因 ROM 而异，不做承诺**（README 里同样写明）。
 *
 * ## 为什么 `onAccessibilityEvent` 是空的（这不是遗漏，是设计）
 *
 * 无障碍服务一旦开始接收窗口内容事件，就等于**能读到用户所有界面的文本** ——
 * 那是这个权限最敏感的部分。[KeepAlive] 的三条硬纪律之一就是"只展示、不代改"，
 * 这里再加一条：**只注册、不监听、不读内容**。
 *
 * 具体做法（三处缺一不可，见 [onServiceConnected] 与 `res/xml/keepalive_accessibility.xml`）：
 *   1. `eventTypes = 0`（xml 里 `android:accessibilityEventTypes` 只声明 `typeWindowStateChanged`
 *      之外的最小集，且运行时再显式置 0）—— 不订阅任何事件；
 *   2. `flags = 0`（**不**要 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`、
 *      **不**要 `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY`）—— 不请求窗口内容；
 *   3. `feedbackType = FEEDBACK_GENERIC` 且 `notificationTimeout = 0`。
 *
 * 这样它在系统里是一个"活着但什么都不看"的服务：既拿不到用户界面内容，
 * 也不产生任何帧回调开销。排障时 [KeepAlive.serviceInfoSummary] 会把
 * 实际的 `events=` / `flags=` 打出来 —— 若哪天有人往 xml 里加了 `typeAllMask`，
 * 那行读数会变，而不是静默地多读一份用户界面。
 *
 * ## 与「存活探测」的关系
 *
 * 本类**不写任何状态到 prefs**，也不自报"我在跑"。用户是否真的开了它，
 * 一律由 [KeepAlive.accessibilityState] 去读 `ENABLED_ACCESSIBILITY_SERVICES`
 * —— 跨进程读系统真值，与「存活探测」"真发一次 HTTP"同一条纪律：
 * 自己设的标志位只能证明自己认为如何。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        // 显式把形态钉成"只看有没有活着"：
        // 不订阅事件（eventTypes = 0）、不请求窗口内容（flags = 0）。
        // 这两行与 res/xml 里的声明是同一件事的运行时加固 —— 只在 xml 里写，
        // 将来有人为了别的用途改 xml 时，这里会被一起改掉（两处都在 = 改一处会红）。
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = 0
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = 0
        info.notificationTimeout = 0
        setServiceInfo(info)
        LlmEngine.uiLog("[保活] 无障碍服务已连接（形态：不订阅事件、不读窗口内容；" +
            "${KeepAlive.serviceInfoSummary(this)}）")
    }

    /**
     * 空实现，且**必须**保持空。
     *
     * 订阅了事件才会走到这里；[onServiceConnected] 已把 `eventTypes` 置 0，
     * 所以正常形态下它根本不会被调用。留一个空体是刻意的：它一旦有内容，
     * 就意味着有人在读用户界面数据 —— 那与这个服务存在的理由（只为了存活）不符。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 有意为空：不读内容、不落日志（落日志会把用户界面的文本写进日志文件）。
    }

    override fun onInterrupt() {
        // 没有需要中断的工作（不订阅事件、不发反馈）。
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        LlmEngine.uiLog("[保活] 无障碍服务被系统或用户断开（存活率可能随之下降）")
        return super.onUnbind(intent)
    }
}
