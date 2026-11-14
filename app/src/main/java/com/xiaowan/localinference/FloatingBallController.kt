package com.xiaowan.localinference

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮球控制器：**读状态 → 渲染药丸 → 转发点击**。就这三件事。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 职责边界（本类刻意"什么都不管"的地方）
 * ═══════════════════════════════════════════════════════════════════════════
 * 它**不**碰推理引擎、**不**管模型生命周期、**不**持 WakeLock、**不**写
 * `ModelLifecycle`/`HttpApi` 的任何字段。理由不是洁癖：`ModelLifecycle` 里
 * 每一格都是有并发不变量的（活跃计数 > 0 时绝不卸载那一类），一个"只是画个球"
 * 的组件去写它，坏法是"偶发 native 崩"，而没有任何现场能指向这里。
 *
 * 所以状态的获取方式有两层，且都不是轮询：
 *   · **拉起**（pull）—— [currentPill] 每次渲染时向注入的 [status] 取一份
 *     [ModelStatusManager.Snapshot]；这一层让"画出来的那一帧"必然是当场的真值；
 *   · **推送**（push）—— 宿主 [FloatingBallService] 订阅 [ModelStatusManager]，
 *     状态真的变了才调一次 [onStatusChanged]。
 * 没有定时器、没有轮询 —— 药丸一秒钟刷一次屏不会有人看见，但那是一份白烧的电。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 亮屏才活（灭屏时 View **完全移除**，不是 `visibility=GONE`）
 * ═══════════════════════════════════════════════════════════════════════════
 * 灭屏期间没有人能看见悬浮球，留着它只有代价：一层 overlay 在系统看来仍然
 * "有窗口在画"，而 Doze 的判定恰恰看这类活动。所以 `ACTION_SCREEN_OFF` 一到
 * 就 `removeView` 并释放 View 引用，`ACTION_SCREEN_ON` 再重建。
 *
 * 这也顺带解决了权限被撤销的那一档：撤销后 `addView` 会抛
 * `BadTokenException`/`SecurityException`，而"下次亮屏再试一次"天然是安全的重试点
 * （见 [showPill] 的 catch）。同一枚异常里既可能是权限问题也可能是窗口问题，
 * 两者的处置不同，所以判据只看 `canDrawOverlays`（系统真值），不看异常类型。
 *
 * ⚠ **不要**在这里注册任何常驻监听（`ACTION_SCREEN_ON` 之外的东西）：
 *   本对象可能在一个进程里活很久，每多一份注册就多一份"忘了反注册"的机会。
 */
class FloatingBallController(
    private val context: Context,
    /**
     * 进程级状态读数（服务在不在跑 / 模型在哪一格 / 活跃推理数 / 模型名）。
     * 控制器**只读**它，不写任何一格 —— 那几条并发不变量归 [ModelStatusManager]
     * 与推理引擎管（见类头"职责边界"）。
     */
    private val status: () -> ModelStatusManager.Snapshot,
    /** 服务地址（长按复制用），由宿主提供 —— 端口/绑定方式只有服务侧知道。 */
    private val serverUrl: () -> String,
    /**
     * 权限在**运行期**被撤销（系统真值说了算，不看异常类型）。控制器这边
     * 只负责"通知宿主"：宿主据此把开关落成关并停掉自己。控制器自己**不**写盘。
     */
    private val onDisabledByPermissionLoss: () -> Unit,
    private val log: (String) -> Unit,
) {

    private val app = context.applicationContext
    private val wm: WindowManager? =
        app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private val touchSlop = ViewConfiguration.get(app).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    /** 药丸 View；null = 当前没有窗口（灭屏 / 未初始化 / 创建失败）。 */
    private var pill: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 此刻是否"亮屏 + 开关开 + 权限在"。三个条件任一变化都要走一遍 [syncWindow]。 */
    private var screenOn = true
    private var started = false
    private var screenReceiver: BroadcastReceiver? = null

    /** 已经画出来的那一条。相等就不重画（见 [onStatusChanged]）。 */
    private var shown: FloatingBallState.Pill? = null

    // ---- 触摸中间态 ----
    private var downRawX = 0f
    private var downRawY = 0f
    private var downAtMs = 0L
    private var downWinX = 0
    private var downWinY = 0
    private var dragging = false
    private var longPressFired = false

    private val longPressCheck = Runnable {
        if (!dragging && pill != null) {
            longPressFired = true
            copyAddress()
        }
    }

    // ---- 生命周期 ----

    /**
     * 服务 onCreate 时调用。注册亮/灭屏广播（**动态注册**，不写进 Manifest ——
     * `ACTION_SCREEN_ON` 不接受静态注册，写了也不会被送达）。
     *
     * 幂等：重复调用只刷新一次 `screenOn`，不会注册第二份广播。
     *
     * 开关关着时**只记下宿主在位**（`started` + 当前亮屏状态），不注册广播、
     * 不查权限、不建窗口。用户随后在设置页打开开关会重新拉起 [FloatingBallService]，
     * 那次 onCreate 会再走一遍本函数并重读亮屏状态 —— 否则「服务在灭屏时起来、
     * 用户解锁后开开关」会一直停在 `screenOn=false`，看起来像"开了却不出球"。
     */
    fun start() {
        started = true
        // 先按"此刻是不是亮屏"落一次初值：服务有可能是灭屏时被拉起来的
        // （STICKY 恢复），那时不该立刻建窗口。
        screenOn = isScreenOnNow()
        // 开关关着 = 本模块**完全不初始化**：不注册广播、不查权限、不建窗口。
        // 这一条是"零开销"的唯一实现处 —— 放到 syncWindow 里不够（那里已经
        // 注册完广播了，一个关着开关的用户也在白收每一次亮灭屏）。
        if (!FloatingBallSettings.enabled(app)) {
            unregisterScreenReceiver()
            removePill()
            shown = null
            return
        }
        registerScreenReceiver()
        syncWindow()
    }

    /** 亮/灭屏广播的注册。**动态注册**：`ACTION_SCREEN_ON` 不接受静态注册。 */
    private fun registerScreenReceiver() {
        // 幂等：宿主每次重建控制器/重新 start 都可能走到这里，不能每调一次就多挂一份。
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON -> setScreenOn(true)
                    Intent.ACTION_SCREEN_OFF -> setScreenOn(false)
                    // ACTION_USER_PRESENT（解锁）刻意不处理：亮屏已经覆盖它，
                    // 而锁屏界面上多一个悬浮球没有任何意义。
                }
            }
        }
        screenReceiver = r
        runCatching {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            app.registerReceiver(r, f)
        }.onFailure {
            // 注册失败时清掉引用，否则下一次 start 会以为已注册
            // 而直接 return，球永远收不到亮灭屏（且没人知道为什么）。
            screenReceiver = null
            log("[悬浮球] 注册亮灭屏广播失败：${it.message}")
        }
    }

    /**
     * 服务 onDestroy。反注册广播 → 移除窗口 → **清空所有引用**。
     *
     * 清空引用不是洁癖：本对象被 Service 持有，而 Service 的 onDestroy 之后
     * 进程可能还活着（Activity 还在），一个残留的 View 会让
     * `WindowManager` 继续持有一份 `TextView` 与其 drawable。
     */
    fun stop() {
        started = false
        unregisterScreenReceiver()
        removePill()
        shown = null
    }

    /** 反注册亮/灭屏广播（幂等；关开关时也走它，保证"关 = 零开销"）。 */
    private fun unregisterScreenReceiver() {
        screenReceiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        screenReceiver = null
    }

    /**
     * 状态源变了（请求到达 / 推理结束 / 服务停止 / 模型换格）。
     *
     * 只在**真的不一样**时才碰 View：`setText` 在同一个字符串上也会触发
     * measure/layout，而请求到达与结束是成对的，一次推理至少两次推送。
     *
     * ⚠ 必须再判一次开关：服务侧的推送是异步的，用户可能刚好在两次推送之间
     *   关掉了开关。只判 `started/screenOn` 的话，下一次推送会把球重新建出来
     *   —— 用户看到的是"关了又自己冒出来"。
     */
    fun onStatusChanged() {
        if (!started || !screenOn) return
        if (!FloatingBallSettings.enabled(app)) {
            // 兜底：开关若被非设置页路径改掉，这里也回到"零开销"。
            unregisterScreenReceiver()
            removePill()
            shown = null
            return
        }
        val next = currentPill()
        val cur = shown
        if (cur != null && cur == next && pill != null) return
        shown = next
        val v = pill ?: run { showPill(); return }
        applyPill(v, next)
    }

    // ---- 窗口 ----

    private fun setScreenOn(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        syncWindow()
    }

    /** 三个条件的唯一汇合点：亮屏 + 开关 + 权限 → 建窗口；否则拆窗口。 */
    private fun syncWindow() {
        val want = started && screenOn && FloatingBallSettings.enabled(app)
        if (!want) {
            removePill()
            shown = null
            return
        }
        if (pill == null) showPill()
        else onStatusChanged()
    }

    /**
     * 建窗口并画上当前状态。
     *
     * 失败一律**静默降级**（不崩、不反复重试）：下次亮屏 / 下次开关变动会再来一遍。
     * 但两种失败要分开处置 ——
     *   · 没有权限：把开关**自动关掉**。留着"开关开着、球不出现"是最坏的一种状态，
     *     用户会反复开关、甚至以为是机型不支持；
     *   · 其它（`BadTokenException` 等）：只留日志，开关不动。
     */
    private fun showPill() {
        val w = wm ?: run { log("[悬浮球] 拿不到 WindowManager，放弃显示"); return }
        if (!FloatingBallSettings.canDrawOverlays(app)) {
            onPermissionLost()
            return
        }
        val v = TextView(app).apply {
            textSize = 12f          // 与 UiText.LABEL 同档；这里不引 UiKit（非 Activity）
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER
            isSingleLine = true
            maxLines = 1
            setShadowLayer(1f, 0f, 1f, 0x66000000)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // API 26 起 TYPE_PHONE 已废弃（走 TYPE_APPLICATION_OVERLAY），
            // minSdk 正好是 26，所以这里不用再分版本。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val p = currentPill()
        applyPill(v, p)
        // 尺寸要等测量之后才知道，而位置必须在 addView 之前给一个值 ——
        // 先按占位尺寸估一个，addView 之后用真实尺寸再夹一次（见 syncPosition）。
        //
        // ⚠ 不加 `FLAG_LAYOUT_NO_LIMITS`：那个 flag 让窗口可以画到状态栏/导航栏之下，
        //   用户把球拖到顶部时**一半压在状态栏里**、触点被系统吃掉 —— 症状是
        //   "球还在，但拖不回来了"。位置一律经 [FloatingBallState.clamp] 夹在有界区域内。
        placeInitially(lp)
        v.setOnTouchListener { _, ev -> onTouch(v, lp, ev) }
        // 测量完成后再夹一次：上面那次用的是占位尺寸，而药丸宽度随文字变
        // （模型名长短不同），靠右摆放时右侧可能被裁掉几个像素。
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                vv: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int,
            ) {
                if (vv !== pill) { vv?.removeOnLayoutChangeListener(this); return }
                if (r - l == or - ol && b - t == ob - ot) return
                syncPosition()
            }
        })
        try {
            w.addView(v, lp)
        } catch (t: Throwable) {
            log("[悬浮球] addView 失败：${t.javaClass.simpleName} ${t.message}")
            // 权限可能在这一瞬间被撤销（用户刚在系统设置里关掉）：系统真值说了算。
            if (!FloatingBallSettings.canDrawOverlays(app)) onPermissionLost()
            return
        }
        pill = v
        params = lp
        shown = p
        syncPosition()
        log("[悬浮球] 已显示：${p.text}")
    }

    private fun removePill() {
        ui.removeCallbacks(longPressCheck)
        val v = pill ?: return
        pill = null
        params = null
        v.setOnTouchListener(null)
        runCatching { wm?.removeView(v) }
    }

    /**
     * 权限真值已经是"没有"，但开关还开着 —— 自动关开关并说清原因。
     *
     * 「静默把开关改掉」在这里是**对的**：用户此刻确实没有这个能力，
     * 留着开关只会让他下次进来以为已经开好了。但必须留一句 toast/日志，
     * 否则"我明明开了怎么又关了"同样没有答案。
     */
    private fun onPermissionLost() {
        removePill()
        shown = null
        if (FloatingBallSettings.enabled(app)) {
            FloatingBallSettings.setEnabled(app, false)
            // 开关自动关掉之后也回到"零开销"：不再收亮灭屏广播。
            unregisterScreenReceiver()
            log("[悬浮球] 已自动关闭开关：没有「在其他应用上层显示」权限")
            ui.post { toast("未授予悬浮窗权限，悬浮球已关闭") }
        }
        // 开关已落成关，宿主 [FloatingBallService] 据此停掉自己 —— 否则会留下
        // 一个"前台服务还在跑、药丸却永远建不出来"的空转进程（还挂着通知）。
        onDisabledByPermissionLoss()
    }

    private fun applyPill(v: TextView, p: FloatingBallState.Pill) {
        v.text = p.text
        val color = when (p.kind) {
            FloatingBallState.Kind.NO_SERVICE -> 0xFF757575.toInt()      // ⚪ 灰
            FloatingBallState.Kind.IDLE_NO_MODEL -> 0xFFF9A825.toInt()   // 🟡 黄
            FloatingBallState.Kind.IDLE_WITH_MODEL -> 0xFF2E7D32.toInt() // 🟢 绿
            FloatingBallState.Kind.RUNNING -> 0xFF1E88E5.toInt()         // 🔵 蓝
            FloatingBallState.Kind.LOADING -> 0xFF8E24AA.toInt()         // 🔄 紫
        }
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(color)
            setStroke(dp(1), 0x33FFFFFF)
        }
        v.contentDescription = when (p.kind) {
            FloatingBallState.Kind.IDLE_WITH_MODEL -> "本地推理服务：${p.fullModelName}"
            else -> p.text
        }
    }

    /**
     * 取当前读数并解析成药丸。
     *
     * 状态源**是注入的**，理论上不该抛（它只是一次字段读取）；但"读不到"这件事
     * 必须有明确出口 —— 按最低优先级（⚪ 无服务）渲染，下一次推送会自愈。
     * 不做重试、不轮询：药丸只是**显示**，它坏了不该反过来影响服务。
     */
    private fun currentPill(): FloatingBallState.Pill {
        val snap = try {
            status()
        } catch (t: Throwable) {
            log("[悬浮球] 状态源暂时不可读，退到最低优先级：${t.javaClass.simpleName} ${t.message}")
            ModelStatusManager.Snapshot(false, ModelLifecycleCore.State.UNLOADED, 0, null)
        }
        return FloatingBallState.resolve(snap)
    }

    // ---- 位置 ----

    private fun placeInitially(lp: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        val margin = dp(FloatingBallState.EDGE_MARGIN_DP)
        if (!FloatingBallSettings.hasPosition(app)) {
            // 默认落右下、输入法上方那一带：右侧是拇指活动区，而中部会压住
            // 主流 App 的正文与底栏（用户第一件事就是把它拖走）。
            lp.x = sw - dp(PILL_PLACEHOLDER_W_DP)
            lp.y = sh * 2 / 3
            return
        }
        val (x, y) = FloatingBallState.clamp(
            FloatingBallSettings.savedX(app), FloatingBallSettings.savedY(app),
            sw, sh, 0, 0, margin)
        lp.x = x
        lp.y = y
    }

    /** addView 之后（以及每次松手/换方向）把位置夹回可见范围。 */
    private fun syncPosition() {
        val lp = params ?: return
        val v = pill ?: return
        val (sw, sh) = screenSize()
        val w = if (v.width > 0) v.width else dp(PILL_PLACEHOLDER_W_DP)
        val h = if (v.height > 0) v.height else dp(PILL_PLACEHOLDER_H_DP)
        val (x, y) = FloatingBallState.clamp(lp.x, lp.y, sw, sh, w, h,
            dp(FloatingBallState.EDGE_MARGIN_DP))
        if (x == lp.x && y == lp.y) return
        lp.x = x
        lp.y = y
        runCatching { wm?.updateViewLayout(v, lp) }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = app.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    // ---- 交互 ----

    private fun onTouch(v: TextView, lp: WindowManager.LayoutParams, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downWinX = lp.x; downWinY = lp.y
                downAtMs = SystemClock.uptimeMillis()
                dragging = false
                longPressFired = false
                ui.postDelayed(longPressCheck, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = movedPx(ev)
                if (!dragging && moved > touchSlop) {
                    dragging = true
                    ui.removeCallbacks(longPressCheck)
                }
                if (dragging) {
                    lp.x = downWinX + (ev.rawX - downRawX).toInt()
                    lp.y = downWinY + (ev.rawY - downRawY).toInt()
                    runCatching { wm?.updateViewLayout(v, lp) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.removeCallbacks(longPressCheck)
                val g = FloatingBallState.gesture(
                    up = ev.actionMasked == MotionEvent.ACTION_UP,
                    movedPx = movedPx(ev),
                    heldMs = SystemClock.uptimeMillis() - downAtMs,
                    slopPx = touchSlop,
                    longPressMs = longPressTimeout,
                    longPressFired = longPressFired,
                )
                when (g) {
                    FloatingBallState.Gesture.DRAG -> {
                        syncPosition()
                        FloatingBallSettings.savePosition(app, lp.x, lp.y)
                    }
                    FloatingBallState.Gesture.TAP -> openApp()
                    // 长按已经在计时器里处置过（复制 + toast），松手不再做第二件事。
                    FloatingBallState.Gesture.LONG_PRESS, FloatingBallState.Gesture.NONE -> Unit
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private fun movedPx(ev: MotionEvent): Int {
        val dx = ev.rawX - downRawX
        val dy = ev.rawY - downRawY
        return kotlin.math.hypot(dx, dy).toInt()
    }

    /**
     * 单击 = 打开主 App。
     *
     * 指向 `EngineActivity` 而不是 `MainActivity`：后者只是个立即 finish 的跳板，
     * 从 overlay 走一跳跳板会闪一下黑屏（与通知 PendingIntent 同一个理由）。
     * `SINGLE_TOP | CLEAR_TOP` 复用已有页面，不叠第二份聊天页。
     */
    private fun openApp() {
        val i = Intent(app, EngineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { app.startActivity(i) }
            .onFailure { log("[悬浮球] 打开主界面失败：${it.message}") }
    }

    /**
     * 长按 = 复制服务地址。
     *
     * 地址由服务侧给（[serverUrl]）：端口可自定义、局域网开关会影响该报哪个 IP，
     * 而这两件事只有服务侧知道。这里**不**自己拼 —— 拼出来的地址一旦与服务实际
     * 监听的不一致，用户会把"复制出来的地址打不开"当成服务坏了。
     *
     * 服务没跑时**不复制**、只给一句明说：那种状态下没有任何地址是有意义的。
     */
    private fun copyAddress() {
        // 服务没跑时根本没有可用地址：`serverUrl()` 会拼出一个"看起来对、连不上"的
        // 回环地址，用户复制走只会以为服务坏了。所以先判服务真值。
        val running = runCatching { status().serviceRunning }.getOrDefault(false)
        if (!running) {
            toast("服务未启动，无地址可复制")
            return
        }
        val url = runCatching { serverUrl() }.getOrDefault("")
        if (url.isBlank()) {
            toast("服务地址暂不可用")
            return
        }
        val ok = runCatching {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LocalLLM Server", url))
        }.isSuccess
        toast(if (ok) "地址已复制" else "复制失败")
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int = (v * app.resources.displayMetrics.density).toInt()

    /**
     * 屏幕此刻是不是亮着。
     *
     * 用 `PowerManager.isInteractive`（"用户能不能与设备交互"）而不是
     * `isScreenOn`（后者已废弃）。读不到就按**亮**处理：宁可多建一次窗口
     * （下一次灭屏广播会立刻拆掉），也不要在一台读不到状态的机器上永远不显示。
     */
    private fun isScreenOnNow(): Boolean = try {
        val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    } catch (t: Throwable) {
        true
    }

    /**
     * 换方向（竖↔横、分屏比例变了）后把位置夹回可见范围。
     *
     * 只夹位置、不重建窗口：重建会让药丸闪一下，而布局参数本来就跟得上。
     * 系统不保证会给 overlay 发配置变更，所以除了这里，每次松手/亮屏也会夹一次。
     */
    fun onConfigurationChanged() {
        syncPosition()
    }

    private companion object {
        /** 药丸未测量时的占位宽/高（dp）；只用于首帧定位，测量完立刻用真值夹取。 */
        const val PILL_PLACEHOLDER_W_DP = 96
        const val PILL_PLACEHOLDER_H_DP = 32
    }
}
