package com.xiaowan.localinference

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 进程内推理主页：模型库(导入/选择/删除) -> 加载 -> 流式对话+统计 -> 本地服务开关。
 * 恢复 OpenAI 兼容本地服务（进程内直连，不再 exec 外部二进制）+ 模型管理
 * （已导入模型可二次选择与删除，同名导入自动去重）。
 */
class EngineActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var modelTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var outTv: TextView
    private lateinit var logTv: TextView
    private lateinit var promptEt: EditText
    private lateinit var sysEt: EditText
    // 多会话管理（SessionStore）；sessionTv 在聊天页顶部显示当前会话标题
    private var currentSessionId: String = ""
    private lateinit var sessionTv: TextView
    private var sessionDialog: android.app.AlertDialog? = null // 跟踪会话管理对话框
    // 等运行时权限回调后要继续登记的引用三元组：name / path / uri
    private var pendingLink: Triple<String, String, String>? = null
    private lateinit var ctxEt: EditText
    private lateinit var gpuEt: EditText
    /** 非 hexagon 变体（纯 CPU）时显示"GPU 不可用"原因，并锁定 gpu layers 输入。 */
    private lateinit var gpuHintTv: TextView
    /** HTP（Hexagon NPU）开关与其状态说明。 */
    private lateinit var htpSwitch: android.widget.Switch
    private lateinit var htpHintTv: TextView
    /** 崩溃探针开关与说明（native 直写日志 + 信号现场，专治「一调工具就闪退」取证） */
    private lateinit var probeSwitch: android.widget.Switch
    private lateinit var probeHintTv: TextView
    /** 标题按实际生效后端显示，不再笼统承诺"HTP/OpenCL/CPU 自动"。 */
    private lateinit var backendTitleTv: TextView
    private lateinit var thrEt: EditText
    private lateinit var tempEt: EditText
    private lateinit var maxEt: EditText
    private lateinit var topKEt: EditText
    private lateinit var repEt: EditText
    private lateinit var repeatLastNEt: EditText
    private lateinit var topPEt: EditText
    private lateinit var minPEt: EditText
    private lateinit var cacheKEt: EditText
    private lateinit var cacheVEt: EditText
    private lateinit var parallelNEt: EditText
    private lateinit var batchSizeEt: EditText
    private lateinit var ubatchSizeEt: EditText
    private lateinit var presEt: EditText
    private lateinit var freqEt: EditText
    // -- 三页 Tab --
    private lateinit var tabRow: LinearLayout
    private lateinit var pageChat: LinearLayout
    private lateinit var pageSet: LinearLayout
    private lateinit var pageLog: LinearLayout
    private lateinit var stopBtn: Button
    private lateinit var clearBtn: Button
    /**
     * 本页「停止」按钮的**一次性**意图。生命周期严格是**一轮生成**：
     * 置位方是按钮回调（主线程），读取方是生成线程，**每一轮开跑时清零**
     * （位置见 [doGenerate] 里的注释：`genLock` 之内、`startCompletion` 之前）。
     *
     * ══════════════════════════════════════════════════════════════════
     * 为什么必须清零（Issue #154：停一次之后整个 App 再也生成不了）
     * ══════════════════════════════════════════════════════════════════
     * 它此前是**进程级**字段：`doGenerate()` 里从来没有把它复位过，于是
     * 「按下停止」= 把 `stopRequested` **永久**置为 true。表现分两层：
     *
     *   · 看得见的一层：本页后续每一轮的生成循环
     *     `while (n < maxTok && !stopRequested && ...)` 第一判就是 false ——
     *     一个 token 都不生成，而 `generating`/`genBtn`/`statusTv` 全按"跑完一轮"
     *     正常收尾（`done = true`）。UI 上点「生成」像在跑，实际是空转，
     *     且**不会抛异常、不会打日志**，只有真的盯着 `n=0` 才看得出来。
     *   · 更坏的一层：`generating` 由 finally 复位，所以上面那个空转是"瞬间结束"的，
     *     但它同时把**会话相关的一票守卫**（`showSessionDialog` / `doCreateSession`
     *     的 `if (generating) return` 只是其一）与 `genBtn.isEnabled` 卷进同一段
     *     竞态里；用户读到的是「点生成没反应 / 切不了会话 / 开不了新对话」，
     *     而这一切的起点只是"刚才按过一次停止"。
     *
     * 为什么不在**收尾**清零、而放在**开跑前**：清零属于"新一轮开始"这个动作。
     * 收尾那条路径上，前面还排着几个提前 `return` 的分支（模型未加载、参数非法、
     * prompt 为空），每个分支都得各自记得清一次 —— 漏一条，同一个毛病就原样
     * 复发（"停一次，之后再点生成还是零 token"）。放在生成线程**真正开跑**
     * 那一行，因果是单向的：谁开跑，谁负责让上一轮的停止意图作废。
     * HTTP 路径不读这个字段（它有自己的 `RequestCancel.Token`），所以这里
     * 的生命周期与"跨请求残留"无关。
     */
    @Volatile private var stopRequested = false
    private lateinit var mmapEt: EditText
    /**
     * repack 档位：`null` = 没设过（→ 库默认开）、`0` = 关、`1` = 开。
     *
     * 为什么是**单选组**而不是上一版的输入框：上一版把它做成一个 13sp 的输入框，
     * 说明写着"留空=默认开"，而真机实测里用户**连它有没有生效都判不出来** ——
     * 输入框不显示"当前是哪一个档"，`""`（没设过）与"填了又被清掉"在界面上同形。
     * 三档互斥、且"没设过"本身就是一个可见档位，单选组才是它该有的形态。
     */
    private var repackMode: Int? = null
    private var repackGroup: android.widget.RadioGroup? = null
    private lateinit var loadBtn: Button
    private lateinit var genBtn: Button
    private lateinit var serverBtn: Button
    /** 存活探测：从 App 内真发 HTTP 打 /health，结论写进 probeResultTv。 */
    private lateinit var probeBtn: Button
    private lateinit var probeResultTv: TextView
    @Volatile private var probing = false
    private lateinit var lanCb: android.widget.CheckBox
    /** CORS 开关（白名单式，默认开）+ 额外来源输入框。 */
    private lateinit var corsCb: android.widget.CheckBox
    private lateinit var corsExtraEt: EditText
    private lateinit var corsHintTv: TextView
    /** 接口鉴权：token 只读展示框 + 生成/复制/关闭。 */
    private lateinit var authEt: EditText
    private lateinit var authRow: LinearLayout
    private lateinit var authHintTv: TextView
    private lateinit var thinkCb: android.widget.CheckBox
    private lateinit var flashCb: android.widget.CheckBox
    private lateinit var portEt: EditText
    private lateinit var serverTv: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var rootScroll: ScrollView
    private lateinit var logTrimTv: TextView
    // ---- tab 吸顶 + 三页左右滑动 + 设置页分组折叠 ----
    private var curTab = 1
    private val pageScrollY = IntArray(3)
    /** 日志页「跟随新行」开关：切到日志页时置位，用户一上滑即清除（与聊天页 autoFollowChat 同义）。 */
    private var logFollowBottom = true
    private var logLastTotal = -1
    private var logLastTrimmed = -1
    private var selectTabFn: ((Int) -> Unit)? = null
    private lateinit var swipeDetector: GestureDetector
    private val sectionBodies = ArrayList<Triple<TextView, LinearLayout, String>>()

    /**
     * 打在控件上的「面板外」标记：collapsify 收集分组内容时遇到它即停手，使该控件及其后
     * 内容留在页面顶层，不随任何分组折叠而隐藏。用引用比较，避免与其它用途的 tag 冲突
     * （NPU 配额 RadioButton 的 tag 是 Int）。
     */
    private val UNGROUPED = Any()
    // (点5): 聊天页输入区移出滚动区常驻底部，输出在上方自行向下刷新
    private lateinit var chatBar: LinearLayout
    private var autoFollowChat = true

    private var modelFile: File? = null
    private var generating = false
    /** 本轮生成是否已经报过「卡住」，防看门狗重复刷屏 */
    private var hangReported = false
    private var importing = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pollRunning = false
    /** 模型库列表的渲染代际：只允许最新一次取数的结果落到视图上（见 refreshModelList） */
    private var modelListGen = 0

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun field(hint: String, text: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(text)
        textSize = 13f
        setSingleLine(true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // peek 不拦截：横向 fling 交给 detector 切页，纵向照旧给 ScrollView
        if (::swipeDetector.isInitialized) swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** 聊天输出向下刷新。只在聊天页、且用户没有主动上滑看历史时生效。 */
    private fun followChatBottom() {
        if (curTab != 0 || !autoFollowChat) return
        if (!::rootScroll.isInitialized || rootScroll.childCount == 0) return
        val c = rootScroll.getChildAt(0)
        rootScroll.scrollTo(0, (c.bottom - rootScroll.height).coerceAtLeast(0))
    }

    private fun chatAtBottom(): Boolean {
        if (!::rootScroll.isInitialized || rootScroll.childCount == 0) return true
        val c = rootScroll.getChildAt(0)
        return c.bottom - (rootScroll.scrollY + rootScroll.height) <= px(32)
    }

    /**
     * 当前是否贴底（与 chatAtBottom 同一判据，日志页复用；留名是为了调用处可读）。
     * 命名带 is 前缀：这里只表达「是否贴底」这一判断，不能与 [logFollowBottom] 这类标志位同名，
     * 否则调用处 `logFollowBottom = logAtBottom()` 会被读成赋值给一个只读判断（编译期直接报错）。
     */
    private fun isLogAtBottom(): Boolean = chatAtBottom()

    private fun paintSection(head: TextView, title: String, open: Boolean) {
        head.text = (if (open) "▼ " else "▶ ") + title +
            (if (open) "" else "  点击展开")
    }

    private fun setAllSections(open: Boolean) {
        val pref = getSharedPreferences("ui_prefs", MODE_PRIVATE)
        for ((head, body, title) in sectionBodies) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            pref.edit().putBoolean("sec:" + title, open).apply()
            paintSection(head, title, open)
        }
    }

    /**
     * 以「── 标题 ──」分隔 label 为组头，把其后到下一组头之前的控件收进可折叠
     * 容器。纯后处理重排，不改动任何现有 addView 顺序；展开状态写 prefs，下次进入沿用。
     */
    private fun collapsify(page: LinearLayout, openByDefault: Set<String>) {
        val pref = getSharedPreferences("ui_prefs", MODE_PRIVATE)
        // (点3): 默认展开集换了（NPU+加载 → 加载+生成）。老设备 prefs 里存着上一版的
        // sec:* 覆盖值会直接压掉新默认，故按版本号清一次，之后仍尊重用户手动折叠。
        if (pref.getInt("sec_ver", 0) != 2) {
            val ed = pref.edit()
            pref.all.keys.filter { it.startsWith("sec:") }.forEach { ed.remove(it) }
            ed.putInt("sec_ver", 2).apply()
        }
        val sep = "──"
        var idx = 0
        while (idx < page.childCount) {
            val head = page.getChildAt(idx)
            val raw = if (head is TextView) (head.text?.toString() ?: "") else ""
            if (head is TextView && raw.startsWith(sep + " ")) {
                var j = idx + 1
                while (j < page.childCount) {
                    val nx = page.getChildAt(j)
                    if (nx.tag === UNGROUPED) break   // 面板外（全局）控件：不并入本组
                    if (nx is TextView && (nx.text?.toString() ?: "").startsWith(sep + " ")) break
                    j++
                }
                if (j > idx + 1) {
                    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    // 倒序搬移：正序 removeViewAt 会让后续索引漂移
                    for (k in j - 1 downTo idx + 1) {
                        val v = page.getChildAt(k)
                        val vlp = v.layoutParams
                        page.removeViewAt(k)
                        body.addView(v, 0, vlp)
                    }
                    page.addView(body, idx + 1, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val title = raw.trim().removePrefix(sep + " ").removeSuffix(sep).trim()
                    val key = "sec:" + title
                    val open = pref.getBoolean(key, openByDefault.any { title.contains(it) })
                    head.setTypeface(head.typeface, Typeface.BOLD)
                    head.setTextColor(0xFF1A73E8.toInt())
                    head.setPadding(0, px(12), 0, px(2))
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    paintSection(head, title, open)
                    head.setOnClickListener {
                        val now = body.visibility != View.VISIBLE
                        body.visibility = if (now) View.VISIBLE else View.GONE
                        pref.edit().putBoolean(key, now).apply()
                        paintSection(head, title, now)
                    }
                    sectionBodies.add(Triple(head, body, title))
                    idx += 2; continue
                }
            }
            idx++
        }
    }

    private fun addSectionToggleBar(page: LinearLayout) {
        if (sectionBodies.isEmpty()) return
        val bar = Button(this).apply {
            textSize = 11f; setBackgroundColor(0x00000000); setTextColor(0xFF1A73E8.toInt())
            text = "收起全部分组"
            setOnClickListener {
                val anyOpen = sectionBodies.any { it.second.visibility == View.VISIBLE }
                setAllSections(!anyOpen)
                text = if (anyOpen) "展开全部分组" else "收起全部分组"
            }
        }
        bar.text = if (sectionBodies.any { it.second.visibility == View.VISIBLE })
            "收起全部分组" else "展开全部分组"
        val at = page.indexOfChild(sectionBodies.first().first)
        page.addView(bar, if (at >= 0) at else 1, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setPadding(0, px(8), 0, px(2))
    }

    private fun btn(s: String) = Button(this).apply { text = s; textSize = 13f }

    /**
     * 导出文件名带上**会话 id**（日志文件自身的时间戳），而不只记导出时刻：
     * 同一会话可能导出多份文件，只靠导出时刻无法区分各自属于哪一次现场。
     */
    private fun exportName(prefix: String, sid: String? = null): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val idPart = sid?.takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""
        return "%s%s-%s.txt".format(prefix, idPart, stamp)
    }

    /** 一行参数名标签，与下方 gridRow 的输入框按同权重对齐。 */
    private fun labRow(vararg names: String): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in names) r.addView(TextView(this).apply {
            text = n; textSize = 10f; setTextColor(0xFF999999.toInt())
            setPadding(0, px(4), 0, px(1))
        }, lp(0, 1))
        return r
    }

    /**
     * repack 档位的**三档单选组**：没设过（库默认，开）/ 关 / 开。
     *
     * 为什么三档都要显式给出来，而不是"留空=默认"：
     * 上一版就是个留空即默认的输入框，而"没设过"在界面上**看不见** ——
     * 用户既不知道当前是哪一档，也没法在改过之后回到"跟随库默认"。
     * 更关键的是 native 侧要靠这个区分"谁定的档"（日志里的「来源」），
     * 所以第三种状态必须在 UI 上同样可选、且与 `0` 明显并列。
     *
     * 与上面的 NPU 配额组同一套写法（`View.generateViewId()` + 加完再 `check()`）：
     *   · id 用运行时生成，不新开 res/values/ids.xml —— 本仓库的资源文件只有
     *     `strings.xml`，为一个 UI 常量新开一类资源不值当；
     *   · 初始选中**不能**在 addView 之前预置 `isChecked`（RadioGroup 在
     *     onChildViewAdded 里记账，预置会让 checkedId 与显示脱钩 —— 见上面配额组那段），
     *     所以先记下"该勾哪个"的 id，加完再 `check()`；
     *   · 「还原默认设置」要回勾这一档，因此 id 存在字段 `repackDefaultId` 上。
     */
    private var repackDefaultId = View.NO_ID

    private fun repackRadioGroup(): android.widget.RadioGroup {
        val g = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        var initId = View.NO_ID
        // 显式标注 `Int?`：`"…" to null` 会被推成 `Pair<String, Nothing?>`，
        // 三个混在一起虽能推出 `Int?`，但写明白了才不会被后续改动悄悄改窄。
        listOf(
            "没设过（跟随库默认 = 开）" to (null as Int?),
            "关（省一份匿名拷贝，CPU 侧可能变慢）" to 0,
            "开（显式打开重排，与默认一致）" to 1
        ).forEach { pair ->
            val rb = android.widget.RadioButton(this).apply {
                text = pair.first; textSize = 13f; tag = pair.second
                id = View.generateViewId()
                if (repackMode == pair.second) initId = id
                if (pair.second == null) repackDefaultId = id
            }
            // 必须 MATCH_PARENT + 权重 0：`lp(0, 1)` 会给出「宽=0dp、weight=1」的
            // 横向参数，而本组是 VERTICAL —— weight 在纵向 LinearLayout 里分的是**高度**，
            // 于是每个 RadioButton 被拉成整屏高的空块（三档就是三大片空白，文字挤在中间），
            // 表现正是"全是空白、没法调"。宽同理由 0dp 撑不开。见 run_repack_lp_guard.sh。
            g.addView(rb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (initId != View.NO_ID) g.check(initId)
        g.setOnCheckedChangeListener { grp, id ->
            val v = grp.findViewById<android.widget.RadioButton>(id)?.tag as? Int
            if (v == repackMode) return@setOnCheckedChangeListener
            repackMode = v
            persistParams()
            LlmEngine.uiLog("[repack] 设置页档位已改为 " + (v?.toString() ?: "没设过") +
                "；重新加载模型后日志会给出 [repack] / [repack结果] 两行作为凭据")
        }
        return g
    }

    private fun lp(w: Int, weight: Int) = LinearLayout.LayoutParams(
        if (w == 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT, weight.toFloat()).apply {
        setMargins(px(2), px(2), px(2), px(2))
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (用户点2): 横向 fling 切三页，免去回页首点 tab
        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val s0 = e1 ?: return false
                val dx = e2.x - s0.x; val dy = e2.y - s0.y
                val need = resources.displayMetrics.widthPixels * 0.28f
                // 必须水平主导（|dx|>|dy|*1.8）且够快，否则那是竖向滚列表或在选文字
                if (kotlin.math.abs(dx) < need || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.8f)
                    return false
                if (kotlin.math.abs(vx) < 300f) return false
                val fn = selectTabFn ?: return false
                val next = curTab + (if (dx < 0) 1 else -1)
                if (next < 0 || next > 2) return false
                fn(next); return true
            }
        })
        rootScroll = ScrollView(this)
        val pad = px(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        rootScroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 三页 Tab ----
        fun tabBtn(t: String): Button = Button(this).apply {
            text = t; setBackgroundColor(0x00000000); setTextColor(0xFF1A73E8.toInt())
        }
        fun makePage(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageChat = makePage(); pageSet = makePage(); pageLog = makePage()
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chatTabBtn = tabBtn("聊天"); val setTabBtn = tabBtn("设置"); val logTabBtn = tabBtn("日志")
        fun selectTab(i: Int) {
            val on = 0xFF1A73E8.toInt(); val off = 0xFF888888.toInt()
            listOf(chatTabBtn, setTabBtn, logTabBtn).forEachIndexed { j, b ->
                b.setTextColor(if (j == i) on else off)
                b.setTypeface(b.typeface, if (j == i) Typeface.BOLD else Typeface.NORMAL)
            }
            pageScrollY[curTab] = rootScroll.scrollY       // 记住离开页的位置
            curTab = i
            pageChat.visibility = if (i == 0) View.VISIBLE else View.GONE
            pageSet.visibility = if (i == 1) View.VISIBLE else View.GONE
            pageLog.visibility = if (i == 2) View.VISIBLE else View.GONE
            if (i == 2) { renderLog(); logFollowBottom = true }
            if (::chatBar.isInitialized) chatBar.visibility = if (i == 0) View.VISIBLE else View.GONE
            // 聊天页与日志页都默认贴底：日志页贴底后，新追加的行自然出现在窗口里，
            // 不需要用户手动往下滑（切走再切回时用上一页位置，所以这里强制贴底一次）。
            if (i == 0) { autoFollowChat = true; rootScroll.post { followChatBottom() } }
            else if (i == 2) { rootScroll.post { followLogBottom() } }
            else { val y = pageScrollY[i]; rootScroll.post { rootScroll.scrollTo(0, y) } }
        }
        chatTabBtn.setOnClickListener { selectTab(0) }
        setTabBtn.setOnClickListener { selectTab(1) }
        logTabBtn.setOnClickListener { selectTab(2) }
        selectTabFn = ::selectTab
        tabRow.setBackgroundColor(0xFFFAFAFA.toInt())
        tabRow.addView(chatTabBtn, lp(0, 1)); tabRow.addView(setTabBtn, lp(0, 1)); tabRow.addView(logTabBtn, lp(0, 1))
        // tabRow 改由外层 outer 持有（吸顶），不再进 rootScroll
        root.addView(pageChat, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pageSet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pageLog, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        selectTab(1)

        // 括号里的后端字样跟随实际生效路径更新（见 renderBackendState）：
        // HTP 需显式开启、且只对受支持量化的模型生效，不能在标题里预先承诺。
        backendTitleTv = TextView(this).apply {
            text = "本地推理 · 模型库 + OpenAI 服务"
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        }
        pageSet.addView(backendTitleTv)

        // 版本号常驻显示：此前界面上看不到版本，装机后无法确认设备跑的是哪个构建，
        // 排查"改了没生效"只能靠反推。用 versionCode 而非 longVersionCode——后者 API 28 才有，
        // 本包 minSdk 26，在 26/27 上会抛 NoSuchFieldError。
        val versionTv = TextView(this).apply {
            text = runCatching {
                val pi = packageManager.getPackageInfo(packageName, 0)
                "v" + pi.versionName + " · code " + pi.versionCode
            }.getOrElse { "v?" }
            textSize = 11f; setTextColor(0xFF888888.toInt())
            setPadding(0, px(2), 0, 0)
        }
        pageSet.addView(versionTv)

        modelTv = TextView(this).apply {
            textSize = 12f; setPadding(0, px(6), 0, px(2))
            setSingleLine(false)
        }
        pageSet.addView(modelTv)
        // (点1): 服务开关是最高频操作，从「本地服务」分组里拿出来常驻可见
        serverBtn = btn("启动服务")
        serverBtn.setOnClickListener { toggleServer() }
        probeBtn = btn("存活探测")
        probeBtn.setOnClickListener { doProbe() }
        // 「打开测试页」与「存活探测」是一对：探测回答"通不通"，
        // 测试页回答"通的是不是我要的东西"（能不能真聊一句）。
        val webBtn = btn("打开测试页")
        webBtn.setOnClickListener { openWebPage() }
        // 两个按钮同排：探测就是"启动/停止之后紧接着要做的那件事"，
        // 放到下面的折叠分组里等于把最需要它的场景（服务起不来）藏起来。
        val srvRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        srvRow.addView(serverBtn, lp(0, 1)); srvRow.addView(probeBtn, lp(0, 1))
        srvRow.addView(webBtn, lp(0, 1))
        pageSet.addView(srvRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        serverTv = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF666666.toInt())
            setPadding(0, px(2), 0, px(2))
        }
        pageSet.addView(serverTv)
        // 探测结论常驻显示：服务连不上时用户要能反复看这份结论，
        // 而不是"结果一闪而过，只能靠 Toast 记"。
        probeResultTv = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF666666.toInt())
            setPadding(0, px(2), 0, px(2))
            setTextIsSelectable(true)   // 结论要能长按复制走（贴给别人/贴进 issue）
            visibility = View.GONE
        }
        pageSet.addView(probeResultTv)

        val row0 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pickBtn = btn("添加模型(.gguf)")
        pickBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, REQ_PICK)
        }
        loadBtn = btn("加载模型")
        loadBtn.setOnClickListener { doLoad() }
        val cpuBtn = btn("纯CPU诊断")
        cpuBtn.setOnClickListener { doLoadCpuOnly() }
        val unloadBtn = btn("卸载模型")
        unloadBtn.setOnClickListener { doUnload() }
        row0.addView(pickBtn, lp(0, 1)); row0.addView(loadBtn, lp(0, 1))
        row0.addView(cpuBtn, lp(0, 1)); row0.addView(unloadBtn, lp(0, 1))
        pageSet.addView(row0)

        // ---- 模型库列表：点击选择，右侧删除 ----
        pageSet.addView(label("模型库（点击选择 · “删”移除并释放空间）"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageSet.addView(listContainer)

        // ---- 本地服务区 ----
        pageSet.addView(label("── 本地服务（OpenAI 兼容，供局域网调用）──"))
        // 端口可自定义（1024-65535），启动服务时保存并生效
        val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val p0 = ModelStore.serverPort(this)
        portEt = field(p0.toString(), p0.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        portRow.addView(label("端口"))
        portRow.addView(portEt, lp(0, 1))
        pageSet.addView(portRow)
        // 局域网访问开关（默认关；开启后无鉴权，仅限可信网络）
        lanCb = android.widget.CheckBox(this).apply {
            text = "允许局域网访问（绑定 0.0.0.0，无鉴权）"
            textSize = 13f
            isChecked = ModelStore.lanAccess(this@EngineActivity)
        }
        pageSet.addView(lanCb)

        // ---- 接口鉴权（Bearer token）----
        //
        // 为什么放进「本地服务」段：端口、局域网开关、CORS、token 说的都是
        // **"谁能以什么方式连上这个服务"** —— 它们本来就是一组，不属于生成参数。
        // 仍紧跟 CORS：两者是同一件事的两半 —— CORS 决定"浏览器肯不肯把响应交给
        // 页面"，token 决定"谁有资格调用"，只开一个都不算能安全地用。
        //
        // token 用**只读输入框**展示而不是只放进提示文字里：它要能被选中、复制、
        // 粘进 Open WebUI / curl。用 EditText 而不是 TextView 是因为长按选择文本
        // 在 EditText 上是标配交互，用户不用学。
        pageSet.addView(label("── 接口鉴权（仅生成端点）──"))
        authRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        authEt = field("未开启（生成端点不要求 token）", ModelStore.apiToken(this@EngineActivity)).apply {
            isEnabled = false            // 只读：允许选中/复制，但不接受手改（避免手抄出错）
            setTextIsSelectable(true)
            textSize = 12f
        }
        authRow.addView(authEt, lp(0, 1))
        val authBtnCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        authBtnCol.addView(btn("生成 token").apply {
            setOnClickListener {
                val t = ApiAuth.regenerate()
                ModelStore.setApiToken(this@EngineActivity, t)
                ApiAuth.token = t
                refreshAuthUi()
                LlmEngine.uiLog("[鉴权] 已生成新 token（长度 ${t.length}），仅 /v1/ 生成端点要求它")
            }
        })
        authBtnCol.addView(btn("复制").apply {
            setOnClickListener {
                val t = ModelStore.apiToken(this@EngineActivity)
                if (t.isEmpty()) {
                    LlmEngine.uiLog("[鉴权] 还没有 token，先点「生成 token」")
                    statusTv.text = "还没有 token，先点「生成 token」"
                    return@setOnClickListener
                }
                copyToClipboard(t)
            }
        })
        authBtnCol.addView(btn("关闭鉴权").apply {
            setOnClickListener {
                ModelStore.setApiToken(this@EngineActivity, "")
                ApiAuth.token = ""
                refreshAuthUi()
                LlmEngine.uiLog("[鉴权] 已关闭：生成端点恢复为不要求 token（与升级前同行为）")
            }
        })
        authRow.addView(authBtnCol)
        pageSet.addView(authRow)
        authHintTv = label(authHintText()).apply { setTextColor(0xFFB26A00.toInt()) }
        pageSet.addView(authHintTv)
        refreshAuthUi()

        // ---- CORS（白名单式）----
        //
        // 为什么把开关和"额外来源"都放在页面上，而不是只写进 README：
        // 与 parallelN 那条说明同一个理由 —— 调参的人就在这一页。
        // 但这里更硬：CORS 是**安全边界**，页面上的措辞必须让人当场明白
        // 「放行 = 任何网页都能调这台手机」，否则它会被当成一个"打不开就勾上"的开关。
        corsCb = android.widget.CheckBox(this).apply {
            text = "允许浏览器跨源调用（CORS，白名单式）"
            textSize = 13f
            isChecked = ModelStore.corsEnabled(this@EngineActivity)
            setOnCheckedChangeListener { _, b ->
                ModelStore.setCorsEnabled(this@EngineActivity, b)
                HttpApi.corsEnabled = b
            }
        }
        pageSet.addView(corsCb)
        val corsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        corsExtraEt = field("", ModelStore.corsExtraOrigins(this@EngineActivity).joinToString("\n"))
        corsExtraEt.setSingleLine(false)
        corsExtraEt.setOnFocusChangeListener { _, has ->
            if (!has) saveCorsExtra()
        }
        corsRow.addView(corsExtraEt, lp(0, 1))
        pageSet.addView(corsRow)
        corsHintTv = label(corsHintText()).apply { setTextColor(0xFFB26A00.toInt()) }
        pageSet.addView(corsHintTv)

        // flash attention 开关（开启后 KV cache 额外支持 24=iq4_nl；iq4_xs 无 KV LUT，不支持）
        flashCb = android.widget.CheckBox(this).apply {
            text = "启用 Flash Attention（cache K=iq4_nl(24) 或 cache V 已量化时必须开启）"
            textSize = 13f
            setPadding(0, px(2), 0, 0)
            isChecked = ModelStore.flashAttn(this@EngineActivity)
            setOnCheckedChangeListener { _, b ->
                ModelStore.setFlashAttn(this@EngineActivity, b)
            }
        }
        val flashHint = label("提示：24=iq4_nl 需开启 Flash Attention；若 NPU 内核缺失会导致加载失败，此时回退 0/8/2。")   // (点2): 随 FA 移到下方分组

        // ---- 参数区 ----
        // 参数固化 —— 优先读上次保存值，首次进入用默认值；修改即时保存
        val savedParams = ModelStore.engineParams(this)
        // 注意 field(hint, text) 的参数顺序：写反会把保存值塞进 hint，文本恒为默认值
        fun paramField(key: String, def: String) = field(def, savedParams[key] ?: def)
        ctxEt = paramField("ctx", "4096")
        gpuEt = paramField("gpu", "0")
        thrEt = paramField("threads", "4")
        tempEt = paramField("temp", "0.8")
        maxEt = paramField("max", "512")
        topKEt = paramField("topK", "0")
        repEt  = paramField("repeat", "1")
        repeatLastNEt = paramField("repeatLastN", "64")
        topPEt = paramField("topP", "0.95")
        minPEt = paramField("minP", "0")
        cacheKEt = paramField("cacheK", "0")
        cacheVEt = paramField("cacheV", "0")
        parallelNEt = paramField("parallelN", "1")
        batchSizeEt = paramField("batchSize", "2048")
        ubatchSizeEt = paramField("ubatchSize", "512")
        presEt  = paramField("pres", "0")
        freqEt  = paramField("freq", "0")
        mmapEt  = paramField("mmap", "1")
        // 没设过（`null`）不是 "1"：库默认本来就是开，写死 "1" 会让
        // "用户没碰过"与"用户显式开"在日志里同形，而 native 要靠这个区分来源。
        // 传进 UI 的原始值就是持久化的原始值（字面量，不做归一）。
        repackMode = (savedParams["repack"] ?: "").takeIf { it == "0" || it == "1" }?.toInt()
        val paramWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { persistParams() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(ctxEt, gpuEt, thrEt, tempEt, maxEt, topKEt, repEt, repeatLastNEt, topPEt, minPEt,
            cacheKEt, cacheVEt, presEt, freqEt, mmapEt, parallelNEt, batchSizeEt, ubatchSizeEt).forEach { it.addTextChangedListener(paramWatcher) }

        // 参数名固定显示在输入框上方；按类别分组：加载与性能 / 生成与采样 / 重复与惩罚
        fun addFull(v: android.view.View) = pageSet.addView(v, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val labRowA = labRow("ctx 上下文长度", "gpu layers (0=CPU, 99=OpenCL)", "threads 线程数")
        val gridRowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowA.addView(ctxEt, lp(0, 1)); gridRowA.addView(gpuEt, lp(0, 1)); gridRowA.addView(thrEt, lp(0, 1))
        // 只有 hexagon 变体内置 OpenCL/HTP 后端；其它机型加载的是纯 CPU 变体，
        // 此提示默认隐藏，由 renderBackendState() 在 native 初始化后按需显示并锁定输入框。
        gpuHintTv = label("").apply {
            setTextColor(0xFFB26A00.toInt()); visibility = View.GONE
        }
        // ---- HTP（Hexagon NPU）开关 ----
        // 默认关闭＝只走 OpenCL/CPU（多数机型唯一可用路径）。打开后 init 会释放 HTP 段库、
        // 设 ADSP_LIBRARY_PATH + LM_GGML_HEXAGON_NDEV 并优先加载 hexagon 变体。
        // native 变体只在进程首次 init 时选定（模型可能已加载），故不做热切换，改完需重启生效。
        htpSwitch = android.widget.Switch(this).apply {
            text = "HTP（Hexagon NPU）"
            textSize = 13f
            setPadding(0, px(14), 0, px(2))
            isChecked = LlmEngine.isHtpEnabled(this@EngineActivity)
        }
        // ---- NPU 配额档（llama_model_params.tensor_split）----
        // 机制：默认份额恒定 ≈2/3（体积差数倍而份额几乎不变），显式传值可推到 96%，
        // 说明 2/3 只是默认结果而非硬上限。
        // 但人工档并不比自动档更快。逐机速度属于开发测试数据，不写进代码与 UI，
        // 以免被当成通用结论。
        // ⇒ 它不是性能旋钮，唯一用途是按体积把权重从 GPU 显存挪到 NPU（显存吃紧时诊断）。
        // 本档不删任何设备，HTP 拒收的层仍回落到 GPU/CPU。下标在 native 侧枚举。
        val quotaGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val quotaNow = LlmEngine.npuQuotaPct(this)
        var quotaInitId = View.NO_ID
        listOf(
            "自动（推荐）" to LlmEngine.NPU_QUOTA_AUTO,
            "NPU 优先 85%" to 85,
            "NPU 尽量 95%" to 95
        ).forEach { pair ->
            val rb = android.widget.RadioButton(this).apply {
                text = pair.first
                textSize = 12f
                tag = pair.second
                id = View.generateViewId()
                // 注意：这里不能预置 isChecked=true。RadioGroup 是在 onChildViewAdded
                // 里给子按钮记账的，加进去之前就 checked 会让 checkedId 与实际显示脱钩，表现为
                // 「点了第二档、第一档圆圈不取消」。初始选中统一用下面的 check(id) 表达。
                if (quotaNow == pair.second) quotaInitId = id
            }
            quotaGroup.addView(rb, LinearLayout.LayoutParams(-1, -2))
        }
        if (quotaInitId != View.NO_ID) quotaGroup.check(quotaInitId)
        val quotaHint = label("")
        // 警示只在切到人工档时出现，自动档保持中性灰不打扰。真实速度因机型/量化而异，
        // 不写进 UI，以免被当成通用结论。
        val applyQuotaHint: (Int) -> Unit = { pct ->
            if (pct < 0) {
                quotaHint.setTextColor(0xFF8A93A0.toInt())
                quotaHint.text = "权重在各设备间的分配由引擎自动决定。"
            } else {
                quotaHint.setTextColor(0xFFF6A822.toInt())
                quotaHint.text = "⚠ 这不是性能旋钮：它只按体积把权重从 GPU 挪到 NPU（显存吃紧时排查用），" +
                    "不会更快。建议保持「自动」。"
            }
        }
        applyQuotaHint(quotaNow)
        var quotaSyncing = false
        quotaGroup.setOnCheckedChangeListener { grp, id ->
            // 兜底：显示态一律强制与 checkedId 对齐（quotaSyncing 防重入）
            if (!quotaSyncing && id != View.NO_ID) {
                quotaSyncing = true
                for (i in 0 until grp.childCount) {
                    val child = grp.getChildAt(i)
                    if (child is android.widget.RadioButton) child.isChecked = child.id == id
                }
                quotaSyncing = false
            }
            val pct = grp.findViewById<android.widget.RadioButton>(id)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            if (pct == LlmEngine.npuQuotaPct(this)) return@setOnCheckedChangeListener
            LlmEngine.setNpuQuotaPct(this, pct)
            applyQuotaHint(pct)
            LlmEngine.uiLog("[引擎] NPU 配额档 → " + (if (pct < 0) "自动" else pct.toString() + "%") +
                "；下次加载模型即生效（不需重启 App）。" +
                (if (pct < 0) "" else "提醒：人工档只把权重从 GPU 挪到 NPU，不是性能旋钮"))
            renderBackendState()
        }

        htpHintTv = label("").apply { setTextColor(0xFF666666.toInt()) }
        htpSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LlmEngine.isHtpEnabled(this)) return@setOnCheckedChangeListener
            LlmEngine.setHtpEnabled(this, checked)
            LlmEngine.uiLog("[引擎] HTP 已${if (checked) "开启" else "关闭"}，需重启 App 生效")
            Toast.makeText(this, "已保存，请完全退出并重启 App 生效", Toast.LENGTH_LONG).show()
            renderBackendState()
        }
        val labRowB = labRow("cache K（0=f16, 8=q8_0, 2=q4_0, 24=iq4_nl 需开flash）", "cache V（同左，V量化必须开flash）", "mmap（1=默认省内存）")
        val gridRowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowB.addView(cacheKEt, lp(0, 1)); gridRowB.addView(cacheVEt, lp(0, 1)); gridRowB.addView(mmapEt, lp(0, 1))
        val labRowG = labRow("parallelN 并行序列数（当前未生效）", "batchSize 逻辑批大小", "ubatchSize 物理批大小")
        val gridRowG = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowG.addView(parallelNEt, lp(0, 1)); gridRowG.addView(batchSizeEt, lp(0, 1)); gridRowG.addView(ubatchSizeEt, lp(0, 1))
        // parallelN 的说明必须写在输入框**旁边**，不能只留在 README 里：
        // 它现在只把 n_seq_max 传给库（llama_jni.cpp），而服务端仍是**单并发** ——
        // 生成循环持 LlmEngine.genLock，第二个请求直接 503（App 内聊天占用时同样 503）。
        // 也就是说填多大都**不产生并发能力**，只有一个吃满 n_ctx 的代价。
        // 不说明的话，调参的人会以为"没变快 = 手机不行"，而真相是这个旋钮现在拧不动。
        // 真并发 slot 调度属 Issue #40 第 3 项，**已搁置**（手机端收益太小）；
        // 该行随功能一起保留、不删输入框 —— 删掉会让已保存的参数无处可改，
        // 且未来若恢复此特性还要改回 UI 与持久化两处。
        val parallelNHintTv = label("parallelN 当前不产生并发：服务端仍是单并发（第二个请求直接 503，不会排队）。" +
            "填 >1 只是把 n_seq_max 传给库，**不会**让多个请求同时跑，反而会按份数瓜分 n_ctx → 单请求可用上下文变小。" +
            "建议保持 1。真并发 slot 调度已搁置（手机端收益太小）。").apply {
            setTextColor(0xFFB26A00.toInt())
        }
        // 布局：HTP 开关提到「加载与性能」分组之上（它决定 native 变体选择、需重启，
        // 与"改后重新加载模型生效"的参数不属同一类）；cache K/V/mmap 行移到 parallelN 行之后，
        // 使 mmap 说明紧邻其输入框
        pageSet.addView(label("── NPU / Hexagon（HTP，改后需重启 App）──"))
        listOf(htpSwitch, quotaGroup, quotaHint, htpHintTv).forEach { addFull(it) }
        pageSet.addView(label("── 加载与性能（改后需重新加载模型）──"))
        listOf(labRowA, gridRowA, gpuHintTv, labRowG, gridRowG, parallelNHintTv, labRowB, gridRowB).forEach { addFull(it) }
        // (用户点1) mmap 的说明必须**紧跟 mmap 输入框**：上一版把它放在 repack 三档之后，
        // 于是 mmap 的设置框与它的说明被 repack 那一整块（标签 + 三档 + 提示）隔开，
        // 读的人得自己把两段接起来。这里插回 gridRowB（含 mmapEt）正下方，
        // 与上面 parallelN 的处理同一条规矩：说明紧邻其输入框。
        addFull(label("mmap=0 为无映射直读（内存占用约翻倍，仅供诊断推理卡顿）").apply {
            setTextColor(0xFF666666.toInt())
        })
        // 为什么把 repack 抬成设置项：它此前只有系统属性一条通道，而真机上
        // `getprop` 读不到时**与"没设过"同形** —— 用户按说明设了、日志却说"库默认"，
        // 一轮实测白跑（见 native `model_use_extra_bufts` 的注释）。抬到这里之后，
        // 档位在 App 内可设、随参数持久化、并由 `[repack]` 一行报出来源。
        addFull(labRow("repack 权重重排（q4_K/q6_K 另存一份匿名拷贝；改后需重新加载模型）"))
        repackGroup = repackRadioGroup()
        addFull(repackGroup!!)
        // 注意：`.apply { }` 要挂在 **label(...) 的返回值**上，不能挂在
        // `addFull(...)` 上 —— 后者的返回类型是 Unit（`pageSet.addView` 的结果被
        // 丢弃），`setTextColor` 在那里根本不存在，编译器会报 unresolved reference。
        // 这一档的**主要功能与注意事项**只留一行，紧贴三档：
        // 上方**已**有一行 "repack 权重重排（…改后需重新加载模型）" 报功能，
        // 这里只补三个必须当场知道的操作要点 —— 省什么、付什么、改完怎么确认。
        // 曾经这里还堆着两段长文（日志两行怎么对账、0.9.127 的 LayoutParams 缺陷），
        // 用户反馈"太啰嗦"。排查过程属于 README / HTP-STATUS 那一层，
        // 设置页只留"按钮干什么、要注意什么"。
        // ⚠ 文案只讲机制、不带读数：曾经这里写着「真机 22 层那轮 1137 MiB」，那是
        // **`gpu_layers=22` 那一格**的峰值（不是"CPU 有 22 层"），且三个读数全是
        // 默认档（=开）下量的、**从没做过开关对照** —— 用户拿 `gpu_layers=0` 去比
        // 会对不上（可能是 0），反倒怀疑档位没落地，和后半句"选了关却是 1"混成一个症状。
        // 数值属于 README / HTP-STATUS 那一层，且写在那里也要连量条件一起写。
        addFull(label("关：省下随 CPU 层数增长的一份匿名拷贝（只是权重的第二份排布，精度不变），" +
            "代价是 CPU 侧可能变慢。改后需重新加载模型，并在日志里确认 [repack结果] 的落值" +
            "（选了「关」却是 1 = 档位没落到库上）。").apply {
            setTextColor(0xFF666666.toInt())
        })
        addFull(flashCb); addFull(flashHint)   // (点2): FA 属加载期参数，紧跟 cache K/V

        pageSet.addView(label("── 生成与采样（对话时生效）──"))
        // 默认关闭思考（SmolLM3/Qwen3 等混合思考模型；客户端 enable_thinking 参数可覆盖）。
        // 从「本地服务」段挪到「生成与采样」段：它决定的是**生成本身**，
        // 与下面几个采样参数同一类；留在服务段会跟端口/CORS/鉴权这些"怎么连"的开关混在一起。
        thinkCb = android.widget.CheckBox(this).apply {
            text = "默认关闭思考（客户端可用 enable_thinking 覆盖）"
            textSize = 13f
            isChecked = ModelStore.disableThinking(this@EngineActivity)
            setOnCheckedChangeListener { _, b ->
                ModelStore.setDisableThinking(this@EngineActivity, b)
                HttpApi.disableThinkingDefault = b
            }
        }
        addFull(thinkCb)
        val labRowC = labRow("max tokens 生成长度上限", "temperature 温度", "top_p（0.95 默认）")
        val gridRowC = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowC.addView(maxEt, lp(0, 1)); gridRowC.addView(tempEt, lp(0, 1)); gridRowC.addView(topPEt, lp(0, 1))
        val labRowD = labRow("top_k（0=关）", "min_p（0=关）")
        val gridRowD = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowD.addView(topKEt, lp(0, 1)); gridRowD.addView(minPEt, lp(0, 1))
        listOf(labRowC, gridRowC, labRowD, gridRowD).forEach { addFull(it) }

        pageSet.addView(label("── 重复与惩罚（防复读）──"))
        val labRowE = labRow("repeat penalty（1=关）", "repeat last_n 惩罚窗口")
        val gridRowE = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowE.addView(repEt, lp(0, 1)); gridRowE.addView(repeatLastNEt, lp(0, 1))
        val labRowF = labRow("frequency_penalty（0=关）", "presence_penalty（0=关）")
        val gridRowF = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowF.addView(freqEt, lp(0, 1)); gridRowF.addView(presEt, lp(0, 1))
        listOf(labRowE, gridRowE, labRowF, gridRowF).forEach { addFull(it) }

        // ---- 崩溃探针 ----
        // 为什么单独摆一个开关：这条链路的闪退此前取证不到 —— 日志回调在 abort 前会丢，
        // JVM 的 UncaughtExceptionHandler 覆盖不到 native abort，客户端只看到连接被重置。
        // 打开后 native 侧自己开文件直写全量日志（含原生日志与逐 token 输出），
        // 信号 handler 会把 SIGSEGV/SIGABRT 的信号号、故障地址、触发时刻写进去。崩完重开 App 一键导出。
        probeSwitch = android.widget.Switch(this).apply {
            text = "崩溃探针（诊断用，需重启 App 生效）"
            textSize = 13f
            setPadding(0, px(14), 0, px(2))
            isChecked = LlmEngine.probeEnabled
        }
        probeHintTv = label("").apply { setTextColor(0xFF666666.toInt()) }
        probeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LlmEngine.probeEnabled) return@setOnCheckedChangeListener
            LlmEngine.setProbeEnabled(this, checked)
            applyProbeHint()
            Toast.makeText(this,
                if (checked) "已开启，请完全退出并重启 App；复现闪退后重开 App 点「导出崩溃探针」"
                else "已关闭，重启 App 后恢复常规日志",
                Toast.LENGTH_LONG).show()
        }
        // (点6): 探针整块移到「重复与惩罚」之下、「还原默认设置」之上。
        // 它是诊断入口，不是常规参数：放在面板内跟参数同构会让人误以为是调参项，
        // 而紧贴全局操作区又能和"把上面所有分组恢复默认"的语义连起来。
        pageSet.addView(label("── 崩溃取证（探针）──"))
        addFull(probeSwitch); addFull(probeHintTv)

        // ---- 丢弃 prompt 缓存（KV 前缀复用）----
        // 多轮对话默认会复用上一轮已经算进 KV 的那段前缀（只算"新增的那部分"），
        // 长 system prompt / 带 tools 的固定段收益最大。这个按钮是**手动兜底**：
        // 换了 system prompt、改了模板之后旧前缀本来就匹配不上（判据是逐 token 比前缀），
        // 但那几 MB KV 会一直占着显存直到被覆盖 —— 手机上就等于可用上下文凭空变少。
        // 所以给一个显式出口，而不是"等它自己失效"。
        pageSet.addView(label("── 推理缓存（KV 前缀复用）──"))
        addFull(label("多轮对话会复用上一轮已算好的 prompt 前缀，只算新增部分。换 system 提示词后点下面按钮可立即释放旧缓存。").apply {
            setTextColor(0xFF666666.toInt())
        })
        val kvBtn = btn("丢弃 prompt 缓存")
        kvBtn.setOnClickListener {
            LlmEngine.resetKvCache()
            // 提示必须报**结果**而不是"已点击"：缓存本来就可能是空的
            //（首轮 / 刚换过模型），笼统地说"已清空"会和 /health 里
            // kv_cache_valid=false 的现象对不上。
            Toast.makeText(this,
                "已释放：下一轮将全量重算 prompt（之后自动重新建立缓存）",
                Toast.LENGTH_LONG).show()
        }
        addFull(kvBtn)

        // 还原默认设置（同时重置端口与局域网开关）。
        // 它作用于整页所有分组，不是「重复与惩罚」这一组的局部操作，因此打上 UNGROUPED
        // 标记留在面板外：该组默认折叠时按钮依然可见，也不会被"收起全部分组"一起藏掉。
        val resetHint = label("全局操作：下面按钮会把上面所有分组恢复到默认值").apply {
            setTextColor(0xFF666666.toInt())
        }
        resetHint.tag = UNGROUPED
        addFull(resetHint)
        val resetBtn = btn("还原默认设置")
        resetBtn.tag = UNGROUPED
        resetBtn.setOnClickListener {
            ctxEt.setText("4096"); gpuEt.setText("0"); thrEt.setText("4")
            tempEt.setText("0.8"); maxEt.setText("512"); topKEt.setText("0")
            repEt.setText("1"); repeatLastNEt.setText("64"); topPEt.setText("0.95")
            minPEt.setText("0"); cacheKEt.setText("0"); cacheVEt.setText("0")
            parallelNEt.setText("1"); batchSizeEt.setText("2048"); ubatchSizeEt.setText("512")
            presEt.setText("0"); freqEt.setText("0"); mmapEt.setText("1")
            repackMode = null
            if (repackDefaultId != View.NO_ID) repackGroup?.check(repackDefaultId)
            persistParams()
            portEt.setText("8080"); lanCb.isChecked = false
            ModelStore.setServerPort(this, 8080)
            ModelStore.setLanAccess(this, false)
            Toast.makeText(this, "已还原默认设置（加载类参数需重新加载模型生效）", Toast.LENGTH_SHORT).show()
        }
        pageSet.addView(resetBtn, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 会话管理行——显示当前会话标题，点击弹会话列表（新建/切换/重命名/删除）
        // (点5): 提示词/输入框/按钮固定到屏幕下方，聊天页本体只剩输出
        chatBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(pad, px(6), pad, px(4))
            visibility = if (curTab == 0) View.VISIBLE else View.GONE
        }
        sessionTv = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF3D5AFE.toInt())
            setPadding(px(4), px(6), px(4), px(6))
            isSingleLine = true
            setOnClickListener { showSessionDialog() }
        }
        pageChat.addView(sessionTv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        sysEt = EditText(this).apply {
            hint = "system 提示词（可选）"
            textSize = 13f; setSingleLine(false); minLines = 1; maxLines = 2
        }
        chatBar.addView(sysEt, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        promptEt = EditText(this).apply {
            hint = "输入消息"
            textSize = 13f; setSingleLine(false); minLines = 1; maxLines = 4
        }
        chatBar.addView(promptEt, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        genBtn = btn("生成")
        genBtn.setOnClickListener { doGenerate() }
        stopBtn = btn("停止")
        stopBtn.setOnClickListener {
            // 停止按钮同时接受两种意图，否则「点了没反应」正是最难解释的那种现象：
            //   · 本页聊天在生成（generating）      -> 停它（原来的行为）；
            //   · 本页没生成、但 HTTP 请求在跑      -> 停那一轮（此前这里完全没反应，
            //     用户只能去别的客户端断开；`/v1/abort` 与按钮现在打的是同一个东西）。
            // 两条都走 RequestCancel 的**当前轮次**，不存在"停错对象"的可能。
            val httpBusy = HttpApi.isGenerating
            if (!generating && !httpBusy && !RequestCancel.active) {
                Toast.makeText(this, "当前没有正在生成的请求", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (generating) { stopRequested = true; statusTv.text = "停止中…" } else { statusTv.text = "正在停止外部请求…" }
            // 走 LlmEngine.requestAbort（唯一入口）：同时标记 token 并把**带归属的**取消
            // 送到 native。只标记 token 的话，prefill 阶段（一次阻塞的 native 调用）
            // 停不下来 —— 用户会看到"点了停止没反应"，直到那一大段 prompt 算完。
            LlmEngine.requestAbort()
        }
        clearBtn = btn("清空对话")
        clearBtn.setOnClickListener {
            if (generating) {
                Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("清空对话")
                .setMessage("清空当前对话上下文与历史？")
                .setPositiveButton("清空") { _, _ ->
                    SessionStore.clearMessages(this@EngineActivity, currentSessionId); renderChat(null)
                    Toast.makeText(this@EngineActivity, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        val chatBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chatBtnRow.addView(genBtn, lp(0, 1)); chatBtnRow.addView(stopBtn, lp(0, 1)); chatBtnRow.addView(clearBtn, lp(0, 1))
        chatBar.addView(chatBtnRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        statusTv = TextView(this).apply { textSize = 12f; setPadding(0, px(8), 0, 0) }
        pageChat.addView(statusTv)

        outTv = TextView(this).apply {
            textSize = 14f; setPadding(0, px(8), 0, px(8))
            setTextIsSelectable(true)
        }
        pageChat.addView(outTv)

        val logRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val logRefreshBtn = btn("刷新")
        logRefreshBtn.setOnClickListener { renderLog() }
        val logClearBtn = btn("清空")
        logClearBtn.setOnClickListener {
            LlmEngine.clearLogs()
            renderLog()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }
        // 原导出写死 filesDir/logs/，那是应用私有目录，文件管理器看不到也发不出去。
        // 现在落公共「下载」，并把真实路径回显；同时提供「导出上次会话」——崩溃后重开 App
        // 想取回死前那份日志时不必先复现。
        val logExportBtn = btn("导出本次")
        logExportBtn.setOnClickListener {
            try {
                val name = exportName("log", LogFileStore.currentSessionId)
                // 读会话**文件全文**，不读 recentLogs()（内存 ring，上限 300 行）：
                // ring 一旦被上百行 "." 填满，关键的生效证明行会被挤出窗口，那一轮就再也无法回溯。
                // 文件是完整且已落盘的，直接用它。
                val body = LogFileStore.currentText().ifBlank {
                    LlmEngine.recentLogs().joinToString("\n")   // 兜底：文件写失败时退回内存
                }
                val where = LogExport.saveText(this, name, body)
                Toast.makeText(this, "已导出：$where", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        val logPrevBtn = btn("导出上次会话")
        logPrevBtn.setOnClickListener {
            val prev = LogFileStore.previousFile
            if (prev == null || !prev.exists()) {
                Toast.makeText(this, "没有上次会话的日志文件", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    // 第二段同样用**本次会话文件全文**（含 [HTP旋钮] 生效行、
                    // 崩溃告警、加载过程），不再用 300 行内存窗口。
                    val body = prev.readText() + "\n\n" +
                        "==== 本次会话（重启后·全文） ====\n" +
                        (LogFileStore.currentText().ifBlank { LlmEngine.recentLogs().joinToString("\n") })
                    val where = LogExport.saveText(this, exportName("log-prev", LogFileStore.previousSessionId), body)
                    Toast.makeText(this, "已导出：$where", Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        logRow.addView(logRefreshBtn, lp(0, 1)); logRow.addView(logClearBtn, lp(0, 1))
        logRow.addView(logExportBtn, lp(0, 1)); logRow.addView(logPrevBtn, lp(0, 1))
        pageLog.addView(logRow)
        // 「导出崩溃探针」单列一行：这是排查 native abort 的主入口，不能和上面四个挤一排。
        val logProbeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val logProbeBtn = btn("导出崩溃探针")
        logProbeBtn.setOnClickListener {
            val native = LlmEngine.probeText()
            if (native.isBlank() && !LlmEngine.probeEnabled) {
                Toast.makeText(this, "探针未开启：设置页打开「崩溃探针」后重启 App 再复现", Toast.LENGTH_LONG).show()
            } else {
                try {
                    // 三段合一：native 原始探针（含信号现场最值钱）+ Kotlin 侧本次全文 +
                    // 上次会话（上一次闪退那一轮就是它）
                    val body = buildString {
                        appendLine("==== native 探针（信号现场与全量原生日志）====")
                        appendLine(native.ifBlank { "(空：进程未挂上探针或本次是首次启动)" })
                        appendLine()
                        appendLine("==== Kotlin 侧本次会话全文 ====")
                        appendLine(LogFileStore.currentText())
                        appendLine()
                        appendLine("==== 上次会话（上一次闪退那一轮）====")
                        appendLine(if (LogFileStore.previousAvailable()) LogFileStore.previousText() else "(无)")
                        appendLine()
                        appendLine("==== 环境 ====")
                        appendLine("device=${LlmEngine.deviceProfile}")
                        appendLine("backend=${LlmEngine.backendDesc} nativeTag=${LlmEngine.nativeTag}")
                        appendLine("boot=${InferenceService.BOOT_ID} probeOn=${LlmEngine.probeEnabled} " +
                                "attached=${LlmEngine.probeAttachedNow}")
                    }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val where = LogExport.saveText(this, "probe-$stamp.txt", body)
                    Toast.makeText(this, "已导出：$where", Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        logProbeRow.addView(logProbeBtn, lp(0, 1))
        pageLog.addView(logProbeRow)
        val logHint = label("日志已实时写入内部 filesDir/logs/（崩溃也不丢）；导出按钮输出到公共「下载」目录。" +
            "「导出崩溃探针」= native 探针 + 本次会话 + 上次会话，排查 native 闪退只需这一份")
        logHint.setPadding(logHint.paddingLeft, px(6), logHint.paddingRight, 0)
        pageLog.addView(logHint)
        // 截断说明：默认不可见。日志页只渲染最近若干行，若 ring 里还有更早的行，
        // 这里如实说明「显示了多少、被截掉多少、完整日志去哪儿取」——
        // 页面上一行模型信息都没有（见 renderBackendState 的实测层分布）时，
        // 至少能一眼判断是"没渲染到"而不是"引擎没打这行"。
        logTrimTv = TextView(this).apply {
            textSize = 10f; setTextColor(0xFFB26A00.toInt())
            setPadding(0, px(2), 0, px(2))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        pageLog.addView(logTrimTv)
        logTv = TextView(this).apply {
            textSize = 10f; setTextColor(0xFF888888.toInt())
            typeface = Typeface.MONOSPACE
        }
        pageLog.addView(logTv)

        // (需求3): 设置页分组折叠。NPU 与「加载与性能」默认展开，其余收起，状态持久化
        collapsify(pageSet, setOf("加载与性能", "生成与采样"))
        addSectionToggleBar(pageSet)
        // (点4): tab 挪到页面底部（拇指可及），其上方是常驻输入条
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(rootScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(chatBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        tabRow.setBackgroundColor(0xFFF2F2F2.toInt())
        outer.addView(tabRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // 用户上滑翻看历史时暂停跟随，回到底部自动恢复；布局变化（键盘挤压）后补一次跟随
        rootScroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
            autoFollowChat = chatAtBottom()
            // 日志页同理：用户上滑查看历史后停止跟随，回到底部后自动恢复跟随。
            // 判据用「是否贴底」而不是「位移方向」——恢复跟随必须是显式回到最底，
            // 否则往下滑一点点就又开始被拽，比不跟随更难用。
            if (curTab == 2) logFollowBottom = isLogAtBottom()
        }
        rootScroll.addOnLayoutChangeListener { _: View, _: Int, _: Int, _: Int, bottom: Int,
                                                 _: Int, _: Int, _: Int, _: Int ->
            if (bottom > 0) followChatBottom()
        }
        setContentView(outer)
        initSessions() // 多会话初始化（含旧单会话迁移）

        // 原生日志 → 界面（ring buffer 回放 + 实时追加）
        // 合并渲染——日志风暴下每轮消息队列最多一次 renderLog，
        // 防 ui.post 堆积占满主线程（修复「多次刷 /v1/models 必卡顿断联」）。
        val logRenderPending = java.util.concurrent.atomic.AtomicBoolean(false)
        LlmEngine.logSink = {
            if (logRenderPending.compareAndSet(false, true)) {
                ui.post {
                    logRenderPending.set(false)
                    renderLog()
                }
            }
        }
        renderLog()
        renderChat(null)

        // onCreate 初始化失败时立刻显示原因（此前失败被静默忽略，点加载才崩）
        if (!LlmEngine.init(applicationContext)) {
            statusTv.text = "引擎初始化失败：" + (LlmEngine.lastInitError ?: "未知原因")
        }
        // native 变体与 HTP 开关都已确定，统一刷新后端标题/GPU 输入框/HTP 状态说明
        renderBackendState()

        // 恢复上次选中的模型
        val lastName = ModelStore.selectedName(this)
        if (lastName.isNotEmpty()) {
            ModelStore.findByName(this, lastName)?.let { f ->
                modelFile = f
                updateModelTv(f)
                probeAndRender(f)   // 首屏恢复选中模型后也预读一次
            }
        }
        refreshModelList()
        startServerPoll()
    }

    /**
     * 退出 App 时**主动释放模型**（含 `[内存] 卸载 RSS …` 与归还空闲堆段）。
     *
     * 为什么必须显式做：在此之前本仓库**没有任何退出路径**释放模型 —— `onDestroy` 只清
     * 监听与线程池，模型要靠系统杀进程才回收。于是"占用降不下来"只能靠用户去设置页
     * 点「卸载模型」，而**没有任何一行日志会提醒他**；0.9.130 现场正是这样：
     * 用户改完 repack 档位重新加载、看到内存也降了一点，但应用列表里的占用一直很高。
     *
     * 为什么放在 `onDestroy` 之后：销毁只在"Activity 真的要走"时才发生（旋转/多窗口
     * 重建也会走它），所以在它里面判定 `isFinishing` —— 旋转重建会**保活**服务，
     * 此时释放再重载白付一次加载代价；而 `isFinishing == true` 是"这次退出是终局"的
     * 判据。服务在跑时提前返回：那说明保活是用户显式开的（`/v1/models` 还在对外服务），
     * 释放模型等于把服务打成 503 —— 服务停掉时它自己会调 `LlmEngine.unload()`
     * （见 InferenceService 的 ACTION_STOP）。
     *
     * @return true = 本次真的做了释放（日志已落），false = 有意不释放（原因已写进状态栏）
     */
    private fun unloadOnExit(): Boolean {
        if (!LlmEngine.hasModel) return false
        val serving = HttpApi.isRunning
        LlmEngine.uiLog("[退出] 正在释放模型（持有模型退出会一直占着内存，直到系统杀进程）")
        LlmEngine.unload()
        HttpApi.currentModel = null
        LlmEngine.uiLog(
            if (serving) "[退出] 模型已释放，服务仍在运行 —— 模型没了，生成会返回 503"
            else "[退出] 模型已释放（见上一行 [内存] 卸载 RSS … 的前后读数）"
        )
        return true
    }

    override fun onDestroy() {
        pollRunning = false
        LlmEngine.logSink = null  // 防 Activity 泄漏（Handler 持引用继续 post）
        // 这个 executor 是每次 onCreate 现建的，不关就会随每次重建各留一个线程；
        // 而它跑的任务（lambda / 视图更新）都捕获了 this，等于可观测的 Activity 泄漏。
        // shutdownNow：队列里排着的任务在 Activity 已销毁后没有任何存在意义。
        ioExecutor.shutdownNow()
        // 退出即释放（判定与理由见 unloadOnExit）：必须放在 super.onDestroy() **之前**，
        // 否则还要跟已拆掉的视图/监听抢资源。释放只碰 native 与 shared prefs，安全。
        if (isFinishing) unloadOnExit()
        super.onDestroy()
    }

    /** 探针状态一行话：开没开、挂没挂上、文件多大（UI 上直接可判断，不必翻文件） */
    private fun applyProbeHint() {
        if (!::probeHintTv.isInitialized) return
        probeHintTv.text = when {
            !LlmEngine.probeEnabled ->
                "关闭中。开启后 native 会自己开文件直写全量日志（含原生日志与逐 token 输出），" +
                    "并捕获 SIGSEGV/SIGABRT 的信号号/故障地址/触发时刻；崩完重开 App 点「导出崩溃探针」，" +
                    "把文件发给我即可定位。"
            else -> {
                val f = LlmEngine.probeFile
                val size = f?.let { if (it.exists()) it.length() else 0L } ?: 0L
                "已开启 ｜ 落盘=${f?.absolutePath ?: "(重启 App 后建立)"} ｜ 当前 ${size / 1024} KB ｜ " +
                    "native 已挂载=${LlmEngine.probeAttachedNow}"
            }
        }
        probeHintTv.setTextColor(if (LlmEngine.probeEnabled) 0xFFB26A00.toInt() else 0xFF666666.toInt())
    }

    private fun renderLog() {
        applyProbeHint()
        if (!::logTv.isInitialized) return
        val lines = LlmEngine.recentLogs()
        val total = lines.size
        val shown = minOf(total, LOG_VIEW_LINES)
        logTv.text = lines.takeLast(shown).joinToString("\n")
        if (curTab != 2) return
        val trimmed = total - shown
        if (logLastTotal != total || logLastTrimmed != trimmed) {
            // 截断说明单独一行，且在滑动容器内、不随日志内容一起滚动（页头常驻）。
            logTrimTv.visibility = if (trimmed > 0) View.VISIBLE else View.GONE
            if (trimmed > 0)
                logTrimTv.text = "仅显示最近 $shown 行（更早的 $trimmed 行已滚出窗口；" +
                    "要完整日志请用上方「导出」）"
            logLastTotal = total; logLastTrimmed = trimmed
        }
        // 与聊天页同样只跟随「用户本来就在底部」的情形：上滑查看历史时不许被拽回底部。
        // 注意 ring 是 2000 行、这里只渲染最近 120 行，所以「贴底」判据不能用控件高度——
        // 用 once-once 标志：切到日志页时置位，用户一上滑即清除。
        if (logFollowBottom) rootScroll.post { followLogBottom() }
    }

    /**
     * 日志页贴底。渲染完立刻 post，依赖的是同一个 measure/layout 周期，
     * 因此不必等下一帧（日志风暴下来回等待会明显滞后）。
     */
    private fun followLogBottom() {
        if (curTab != 2 || !logFollowBottom) return
        if (!::rootScroll.isInitialized || rootScroll.childCount == 0) return
        val c = rootScroll.getChildAt(0)
        rootScroll.scrollTo(0, (c.bottom - rootScroll.height).coerceAtLeast(0))
    }

    // ---- 模型库 ----

    /**
     * 刷新模型库列表。
     *
     * **取数与渲染分离**：`ModelStore.rows()` 要走 prefs（`getAll` 一次）+ `listFiles`
     * + 每行一次 `stat`，耗时随模型数线性增长（N=50 时可达数百毫秒）—— 所以它必须在
     * `ioExecutor` 上跑，主线程只做 `removeAllViews` + `addView`。
     *
     * 代际号 [modelListGen] 用来丢弃过期结果：连点两次选中时，先发的那次可能后回，
     * 拿旧快照去渲染会让列表"闪回"上一个选中态。
     */
    private fun refreshModelList() {
        val myGen = ++modelListGen
        val sel = modelFile?.name
        ioExecutor.execute {
            val rows = runCatching { ModelStore.rows(this) }.getOrNull()
            ui.post { if (myGen == modelListGen) renderModelList(rows.orEmpty(), sel) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderModelList(rows: List<ModelStore.Row>, sel: String?) {
        listContainer.removeAllViews()
        if (rows.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "（模型库为空：点“添加模型”选择第一个 GGUF）"
                textSize = 12f; setPadding(0, px(4), 0, px(4))
            })
        }
        for (r in rows) {
            val f = r.file
            val isSel = f.name == sel
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(this).apply {
                text = (if (isSel) "● " else "○ ") + (if (r.external) "🔗 " else "") + r.alias +
                    (if (r.alias != f.name.removeSuffix(".gguf")) " · ${f.name}" else "") +
                    if (r.missing) "  ⚠ 原文件已丢失" else "  (${r.bytes / 1048576} MB)"
                textSize = 13f
                if (isSel) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, px(6), 0, px(6))
            }
            tv.setOnClickListener { selectModel(f) }
            tv.setOnLongClickListener {
                promptAlias(f, true)
                true
            }
            val del = btn(if (r.external) "解" else "删")
            del.textSize = 11f
            del.setPadding(px(8), 0, px(8), 0)
            del.setOnClickListener { confirmDelete(f) }
            row.addView(tv, lp(0, 1))
            row.addView(del, lp(ViewGroup.LayoutParams.WRAP_CONTENT, 0))
            listContainer.addView(row)
        }
    }

/** 输入/修改模型别名；首导入默认= 文件名去 .gguf。 */
    private fun promptAlias(f: File, isRename: Boolean) {
        val input = EditText(this).apply {
            setText(ModelStore.aliasOf(this@EngineActivity, f.name))
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isRename) "修改模型别名" else "给模型起个简短名字")
            .setMessage("客户端 /v1/models 和 model 参数将显示此名字")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val al = input.text.toString().trim()
                if (al.isNotEmpty()) {
                    ModelStore.setAlias(this, f.name, al)
                    if (modelFile?.name == f.name && LlmEngine.hasModel)
                        HttpApi.currentModel = al
                    refreshModelList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectModel(f: File) {
        modelFile = f
        ModelStore.setSelected(this, f.name)
        updateModelTv(f)
        refreshModelList()
        // 选中即预读 GGUF header（毫秒级，不加载模型），换模型当场就能看到真实量化
        probeAndRender(f)
        Toast.makeText(this, "已选择 ${f.name}", Toast.LENGTH_SHORT).show()
    }

    /** 后台预读一个文件的 GGUF header 并刷新提示区（内存/缓存命中时几乎瞬时）。 */
    private fun probeAndRender(f: File) {
        ioExecutor.execute {
            runCatching { LlmEngine.peekGguf(applicationContext, f) }
            ui.post { renderBackendState() }
        }
    }

    private fun updateModelTv(f: File) {
        modelTv.text = "当前: ${f.name}  (${f.length() / 1048576} MB)"
    }

    /**
     * 应用内条目=真删文件；就地引用条目=只解除登记。
     * 这里分叉是硬性的：对引用条目走 f.delete() 会直接删掉用户「下载」里的原始模型，
     * 而按钮上写的却是"从模型库移除"，属于不可接受的误伤。
     */
    private fun confirmDelete(f: File) {
        val ext = ModelStore.isExternal(this, f.name)
        AlertDialog.Builder(this)
            .setTitle(if (ext) "移除引用" else "删除模型")
            .setMessage(
                if (ext) "把 ${f.name} 从模型库移除？\n原文件不会被删除，仍在：\n${ExternalModel.shortPath(f.absolutePath)}"
                else "删除 ${f.name}？\n(${f.length() / 1048576} MB，不可恢复)"
            )
            .setPositiveButton(if (ext) "移除引用" else "删除") { _, _ ->
                ioExecutor.execute {
                    val ok = if (ext) ExternalModel.remove(this, f.name) != null else f.delete()
                    ui.post {
                        if (modelFile?.name == f.name) {
                            modelFile = null
                            modelTv.text = "未选择模型"
                        }
                        // 文件没了，预读缓存也要一起作废，防止同名新文件继承旧判定
                        LlmEngine.forgetProbe(applicationContext, f.absolutePath)
                        renderBackendState()
                        refreshModelList()
                        Toast.makeText(this, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            promptImportMode(uri)
        }
    }

    /**
     * 选完文件后先问清落盘方式。省空间与"不依赖原文件"两种诉求都成立，交给用户当场选，
     * 而不是替他决定；解析不出稳定路径时只剩复制一条路，也如实说明原因。
     */
    @SuppressLint("SetTextI18n")
    private fun promptImportMode(uri: Uri) {
        if (importing) {
            Toast.makeText(this, "导入进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val name = ExternalModel.displayName(this, uri)
        if (!name.lowercase().endsWith(".gguf")) {
            Toast.makeText(this, "请选择 .gguf 文件（当前：$name）", Toast.LENGTH_LONG).show()
            return
        }
        // 库里已有同名（副本或引用）→ 直接选用，避免第二条路径再占一份空间
        ModelStore.findByName(this, name)?.let { exist ->
            selectModel(exist)
            Toast.makeText(this, "模型库已有 $name，直接选用", Toast.LENGTH_SHORT).show()
            return
        }
        val path = ExternalModel.resolvePath(this, uri)
        if (path == null) {
            AlertDialog.Builder(this)
                .setTitle("无法就地引用")
                .setMessage("$name 的来源不提供可直读的文件路径（非文件系统类 provider），只能复制一份到应用内。")
                .setPositiveButton("复制到应用内") { _, _ -> importModel(uri) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val mb = File(path).length() / 1048576
        // 选项一律走按钮：setItems 的列表区在部分 OEM 主题下不渲染，用户只会看到"取消"
        AlertDialog.Builder(this)
            .setTitle("添加模型 $name")
            .setMessage(
                "路径：${ExternalModel.shortPath(path)}\n大小：$mb MB\n\n" +
                    "就地引用 — 不复制，省 $mb MB，原文件须留在原位\n" +
                    "复制 — 多占 $mb MB，之后可随意删原文件"
            )
            .setPositiveButton("🔗 就地引用") { _, _ -> linkExternal(name, path, uri) }
            .setNeutralButton("📋 复制") { _, _ -> importModel(uri) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 就地引用要按裸路径读文件，必须先拿到存储权限；被拒就退化为复制。 */
    private fun linkExternal(name: String, path: String, uri: Uri) {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLink = Triple(name, path, uri.toString())
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORE
            )
            return
        }
        commitLink(name, path, uri.toString())
    }

    private fun commitLink(name: String, path: String, uriStr: String) {
        val f = File(path)
        if (!ExternalModel.add(this, name, path, uriStr)) {
            Toast.makeText(this, "引用登记失败，请改用「复制到应用内」", Toast.LENGTH_LONG).show()
            return
        }
        modelFile = f
        ModelStore.setSelected(this, name)
        updateModelTv(f)
        refreshModelList()
        probeAndRender(f)
        Toast.makeText(this, "已就地引用 $name（未复制，省 ${f.length() / 1048576} MB）",
            Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORE) return
        val p = pendingLink ?: return
        pendingLink = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            commitLink(p.first, p.second, p.third)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("未授予文件读取权限")
            .setMessage("就地引用需要「文件读取」权限（按路径直读原文件）。\n可以拒绝后改走复制，同样能正常使用。")
            .setPositiveButton("复制到应用内") { _, _ -> importModel(Uri.parse(p.third)) }
            .setNeutralButton("再试一次") { _, _ -> linkExternal(p.first, p.second, Uri.parse(p.third)) }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun importModel(uri: Uri) {
        if (importing) {
            Toast.makeText(this, "导入进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        // 大模型复制耗时可达数十秒，必须放后台，否则主线程被文件流卡死触发 ANR
        importing = true
        loadBtn.isEnabled = false
        genBtn.isEnabled = false
        serverBtn.isEnabled = false
        probeBtn.isEnabled = false
        modelTv.text = "开始导入…"
        statusTv.text = "导入模型中…"
        ioExecutor.execute {
            try {
                val (dst, existed) = doImport(uri)
                ui.post {
                    selectModel(dst)
                    statusTv.text = "就绪"
                    Toast.makeText(this,
                        if (existed) "同名模型已存在，直接选用（未重复导入）" else "导入完成",
                        Toast.LENGTH_SHORT).show()
                    promptAlias(dst, existed)
                }
            } catch (e: Exception) {
                ui.post {
                    modelTv.text = "导入失败"
                    statusTv.text = "就绪"
                    Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                ui.post {
                    importing = false
                    loadBtn.isEnabled = true
                    genBtn.isEnabled = true
                    serverBtn.isEnabled = true
                    probeBtn.isEnabled = true
                }
            }
        }
    }

    /**
     * 导入：复制到应用模型库。
     * 去重：同名文件已存在则直接复用，不再重复复制浪费空间。
     * 返回 (目标文件, 是否命中已有文件)。
     */
    @SuppressLint("Range")
    private fun doImport(uri: Uri): Pair<File, Boolean> {
        var name = "model.gguf"
        var srcSize = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let {
                    name = if (it.lowercase().endsWith(".gguf")) it else "$it.gguf"
                }
                val sz = c.getColumnIndex(OpenableColumns.SIZE)
                if (sz >= 0) srcSize = c.getLong(sz)
            }
        }
        name = name.substringAfterLast('/').take(180)

        val dst = File(ModelStore.modelsDir(this), name)
        if (dst.isFile) return dst to true // 已存在：直接复用

        val tmp = File(dst.parentFile, "$name.part")
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 20) // 1MB，减少 JNI 边界往返
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    copied += n
                    if (copied - lastReport >= 64L shl 20) { // 每 64MB 上报一次进度
                        lastReport = copied
                        ui.post {
                            modelTv.text = "导入中: $name  ${copied / 1048576}" +
                                (if (srcSize > 0) "/${srcSize / 1048576}" else "") + " MB"
                        }
                    }
                }
                output.flush()
                if (srcSize > 0 && copied != srcSize) {
                    tmp.delete()
                    throw IllegalStateException("复制不完整: $copied / $srcSize")
                }
            }
        } ?: throw IllegalStateException("无法打开所选文件")
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        return dst to false
    }

    // ---- 加载 ----

    /**
     * FA/KV 统一校验。llama.cpp 硬约束：
     * - cache K = iq4_nl(24)：非 FA 路径无对应 KV LUT，必须开 Flash Attention；
     * - cache V 已量化（≠0 且 ≠f16(1)）：V 缓存量化强制要求 FA；
     * 满足任一条件而 FA 未开启时返回 false 并 Toast 提示用户打开开关。
     */
    private fun checkFaForKv(flash: Boolean, ck: Int, cv: Int): Boolean {
        if (flash) return true
        val kBad = ck == 24
        val vBad = cv != 0 && cv != 1
        if (kBad || vBad) {
            val reason = if (kBad && vBad) "cache K=iq4_nl(24) 且 cache V 已量化"
                else if (kBad) "cache K=iq4_nl(24)" else "cache V 已量化"
            Toast.makeText(this, "检测到 $reason，请先开启 Flash Attention 开关", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun doLoad() {
        val mf = modelFile ?: run { Toast.makeText(this, "请先在模型库选择模型", Toast.LENGTH_SHORT).show(); return }
        // 就地引用的原文件可能已被移动/删除；提前拦住，别让 native 层抛难懂的错
        if (!mf.isFile) {
            statusTv.text = "未加载：模型文件不存在"
            Toast.makeText(this, "模型文件不存在：${mf.name}\n（就地引用的原文件可能已被移动或删除）",
                Toast.LENGTH_LONG).show()
            return
        }
        if (generating || HttpApi.isGenerating) {
            statusTv.text = "生成进行中，请先停止生成再加载/切换模型"
            Toast.makeText(this, "生成进行中，请稍后再加载模型", Toast.LENGTH_SHORT).show()
            return
        }
        loadBtn.isEnabled = false
        statusTv.text = "加载中…"
        val ctx = ctxEt.text.toString().toIntOrNull() ?: 4096
        val gpu = gpuEt.text.toString().toIntOrNull() ?: 0
        val thr = thrEt.text.toString().toIntOrNull() ?: 4
        val ck = cacheKEt.text.toString().toIntOrNull() ?: 0
        val cv = cacheVEt.text.toString().toIntOrNull() ?: 0
        val mm = (mmapEt.text.toString().toIntOrNull() ?: 1) != 0
        // 以开关当前值为准并回写持久化，避免 prefs 与 UI 不一致时加载到旧状态
        val flash = flashCb.isChecked
        ModelStore.setFlashAttn(this, flash)
        // FA/KV 校验 —— cache K=iq4_nl(24) 或 V 已量化时必须开启 Flash Attention
        if (!checkFaForKv(flash, ck, cv)) {
            loadBtn.isEnabled = true
            statusTv.text = "未加载：请先开启 Flash Attention"
            return
        }
        val parallelN = parallelNEt.text.toString().toIntOrNull() ?: 0
        val batchSize = batchSizeEt.text.toString().toIntOrNull() ?: 0
        val ubatchSize = ubatchSizeEt.text.toString().toIntOrNull() ?: 0
        Thread {
            // 后台线程内任何异常都兜住，避免未捕获异常直接闪退，错误统一回显到状态栏
            val err = try {
                LlmEngine.loadModel(mf.absolutePath, nGpuLayers = gpu, nCtx = ctx, nThreads = thr, flashAttn = flash, useMmap = mm, cacheK = ck, cacheV = cv, parallelN = parallelN, batchSize = batchSize, ubatchSize = ubatchSize)
            } catch (t: Throwable) {
                LlmEngine.uiLog("[加载] 异常：${t.javaClass.name}: ${t.message}")
                "加载异常：${t.javaClass.simpleName}: ${t.message}"
            }
            ui.post {
                loadBtn.isEnabled = true
                statusTv.text = if (err == null) {
                    HttpApi.currentModel = ModelStore.aliasOf(this@EngineActivity, mf.name)
                    LlmEngine.uiLog(if (HttpApi.isRunning) "[加载] 完成，已挂载到运行中的服务: ${LlmEngine.modelDesc()}" else "[加载] 完成（本地模式）: ${LlmEngine.modelDesc()}")
                    "已加载: ${LlmEngine.modelDesc()} | ctx=${LlmEngine.contextSize()}"
                } else err
                // 量化白名单提示依赖已加载模型，换模型后必须刷新（否则 Q4_K_M 用户看不到警告）
                renderBackendState()
                renderLog()
            }
        }.start()
    }

    private fun doLoadCpuOnly() {
        val mf = modelFile ?: run { Toast.makeText(this, "请先在模型库选择模型", Toast.LENGTH_SHORT).show(); return }
        loadBtn.isEnabled = false
        statusTv.text = "加载中(纯CPU)…"
        val cvCpu = cacheVEt.text.toString().toIntOrNull() ?: 0
        val ckCpu = cacheKEt.text.toString().toIntOrNull() ?: 0
        // 诊断按钮同样做 FA/KV 校验
        if (!checkFaForKv(flashCb.isChecked, ckCpu, cvCpu)) {
            loadBtn.isEnabled = true
            statusTv.text = "未加载：请先开启 Flash Attention"
            return
        }
        Thread {
            val mm = (mmapEt.text.toString().toIntOrNull() ?: 1) != 0
            val parallelN = parallelNEt.text.toString().toIntOrNull() ?: 0
            val batchSize = batchSizeEt.text.toString().toIntOrNull() ?: 0
            val ubatchSize = ubatchSizeEt.text.toString().toIntOrNull() ?: 0
            // 诊断路径同样兜底，未初始化/异常不闪退
            val err = try {
                LlmEngine.loadModel(mf.absolutePath, nGpuLayers = 0, nCtx = 4096, nThreads = 4,
                    flashAttn = flashCb.isChecked, useMmap = mm, cacheK = ckCpu, cacheV = cvCpu,
                    parallelN = parallelN, batchSize = batchSize, ubatchSize = ubatchSize)
            } catch (t: Throwable) {
                LlmEngine.uiLog("[加载] 异常：${t.javaClass.name}: ${t.message}")
                "加载异常：${t.javaClass.simpleName}: ${t.message}"
            }
            ui.post {
                loadBtn.isEnabled = true
                statusTv.text = if (err == null) {
                    HttpApi.currentModel = ModelStore.aliasOf(this@EngineActivity, mf.name)
                    "已加载(纯CPU): ${LlmEngine.modelDesc()} | ctx=${LlmEngine.contextSize()}"
                } else err
                renderBackendState()
                renderLog()
            }
        }.start()
    }

    /**
     * (applyGpuCapability) → 统一刷新「后端」相关 UI。
     *
     * 三处联动：标题括号里的实际后端、GPU 层数输入框可用性、HTP 开关下方的状态说明。
     * 只有 hexagon 变体把 OpenCL/Hexagon 后端编进了 so；i8mm/dotprod/base 是纯 CPU 构建，
     * 填 gpu layers 不会有任何效果（LlmEngine.loadModel 会强制归零），因此禁用输入框并写明原因，
     * 免得用户长期对着一个失效参数排查"为什么没加速"。
     */
    private fun renderBackendState() {
        val wantHtp = LlmEngine.isHtpEnabled(this)
        val tag = LlmEngine.nativeTag
        // 开关存的不等于本进程实际采用的值 → 需要重启才生效（native 变体选定后不再更换）
        val pending = wantHtp != LlmEngine.htpRequested
        var warn = false

        backendTitleTv.text = "本地推理 · 模型库 + OpenAI 服务（后端：${LlmEngine.backendDesc}）"

        if (LlmEngine.gpuCapable) {
            gpuHintTv.visibility = View.GONE
            gpuEt.isEnabled = true
        } else {
            gpuHintTv.text = when {
                tag == null -> "native 尚未加载成功，GPU 层数暂不可用（请先解决上方引擎初始化错误）"
                wantHtp -> "已请求 HTP，但本机 hexagon 变体不可用，实际加载 $tag（纯 CPU）：GPU 层数已锁定 0"
                else -> "本机 native 变体=$tag（纯 CPU 构建，无 OpenCL/HTP 后端），GPU 层数已锁定为 0"
            }
            gpuHintTv.visibility = View.VISIBLE
            // 与引擎实际生效值保持一致；setText 会经 paramWatcher 持久化，避免重启后又恢复成无效值
            if (gpuEt.text.toString() != "0") gpuEt.setText("0")
            gpuEt.isEnabled = false
        }

        val quants = LlmEngine.htpSupportedQuants.joinToString("/")
        // ---- 提示区面向「当前选中的模型」，不再只看已加载那个 ----
        // 修掉两个老问题：(1) 预读只在 loadModel 里跑，换模型时必须等加载完才显示；
        // (2) 卸载后引擎状态清了但本页不刷新，会继续显示上一个模型的信息，误导判断。
        val loadedPath = LlmEngine.loadedPath
        val target = modelFile ?: loadedPath?.let { File(it) }
        val targetIsLoaded = target != null && loadedPath != null && target.absolutePath == loadedPath
        val probe = target?.let { runCatching { LlmEngine.peekGguf(applicationContext, it) }.getOrNull() }
        val fileQuant = LlmEngine.quantOfFile(target?.absolutePath)
        val shownQuant = probe?.realQuant ?: fileQuant
        val quantOk = LlmEngine.quantOkOf(probe, fileQuant)   // null=header 与文件名都给不出 → 不下结论
        val mismatch = probe != null && !LlmEngine.labelsMatch(fileQuant, probe.realQuant)
        val srcTag = when {
            probe == null -> "按文件名推测"
            probe.cached -> "沿用上次探测结果"
            targetIsLoaded -> "GGUF header 直读（该模型已加载）"
            else -> "GGUF header 预读（无需加载）"
        }
        val who = if (target == null) "未选择模型" else target.name
        val quantTxt = shownQuant?.let { "「$it」" } ?: "量化未知"
        val htpHintMain = when {
            pending -> "\u26a0 已保存「${if (wantHtp) "开启" else "关闭"}」，本进程仍按" +
                "${if (LlmEngine.htpRequested) "HTP" else "OpenCL/CPU"}运行，完全退出并重启 App 后生效"
            target == null ->
                "未选择模型：先在上方模型库选一个，才能判断它的矩阵乘能否走 NPU（支持 $quants）"
            !targetIsLoaded ->
                "\u25b8 以下针对已选「$who」= $quantTxt（$srcTag），它还没加载，所以只是预判：" +
                (when {
                    quantOk == null -> " 读不出实际类型，加载后会立即改用实测值"
                    quantOk == true -> " 加载后矩阵乘可上 NPU" + (if (probe != null) "（实测受理 ${probe.coverage}%）" else "")
                    else -> " 不在 NPU 支持列表内，加载后矩阵乘会全部落回 OpenCL/CPU，HTP 反而更慢"
                }) + "；当前引擎后端：" + LlmEngine.backendDesc
            wantHtp && quantOk == false ->
                "\u26a0 当前模型 $quantTxt 不在 NPU 支持列表（$quants）内：" +
                "矩阵乘会全部落回 OpenCL/CPU，HTP 只认领零散 op、反而多付跨设备同步，实测更慢。" +
                "建议换用上述量化的模型，或关闭本开关"
            LlmEngine.htpActive ->
                "已生效" + (shownQuant?.let { "（$it）" } ?: "") +
                "：仅 $quants 的矩阵乘上 NPU。长输入（>500 token）预填充约快 4.5\u00d7，" +
                "逐字生成慢约一半。本轮权重路径：" +
                "HTP + OpenCL + CPU 混合；实测层分布见下（$srcTag）"
            wantHtp -> "已开启但未生效：本机 hexagon 变体不可用（原因见日志「native 变体」行）"
            else -> "未启用：NPU 不参与调度。开启后长输入（>500 token）预填充约快 4.5\u00d7，" +
                "但逐字生成慢约一半，且仅 $quants 的矩阵乘能上 NPU；日常使用建议保持关闭"
        }
        // 量化类型 / 命名与体积不符 / 层落点这三条自证信息并到 HTP 提示行（见 buildHtpHint）
        val covSuffix = if (probe != null && probe.coverage >= 0)
            "\n实测 ${probe.realQuant}：HTP 受理 ${probe.coverage}% 权重" +
                (if (probe.strictUsable) "，矩阵乘可上 NPU" else "，大部分矩阵乘上不了 NPU") +
                (if (probe.cached) "（缓存值，加载后会重新实测）" else "") else ""
        val mismatchSuffix = if (mismatch)
            "\n\u26a0 文件名标「${fileQuant ?: "?"}」与实际 $shownQuant 不符，已按实际类型判定——不需要为此重新加载" else ""
        val mm = HtpProbe.measured()
        htpHintTv.text = htpHintMain + covSuffix + mismatchSuffix +
            (if (mm.isEmpty()) "" else "\n实测层分布：$mm")
        if (pending || (wantHtp && quantOk == false) ||
            (wantHtp && !LlmEngine.htpActive && tag != null)) warn = true
        val mismatchAlert = mismatch && wantHtp && LlmEngine.htpActive
        htpHintTv.setTextColor(if (warn || mismatchAlert) 0xFFB26A00.toInt() else 0xFF666666.toInt())
    }

    // ---- 本地服务 ----

    /** 将参数区全部当前值写入持久化存储（输入变化时自动调用）。 */
    private fun persistParams() {
        ModelStore.setEngineParams(this, mapOf(
            "ctx" to ctxEt.text.toString(), "gpu" to gpuEt.text.toString(),
            "threads" to thrEt.text.toString(), "temp" to tempEt.text.toString(),
            "max" to maxEt.text.toString(), "topK" to topKEt.text.toString(),
            "repeat" to repEt.text.toString(), "repeatLastN" to repeatLastNEt.text.toString(),
            "topP" to topPEt.text.toString(), "minP" to minPEt.text.toString(),
            "cacheK" to cacheKEt.text.toString(), "cacheV" to cacheVEt.text.toString(),
            "parallelN" to parallelNEt.text.toString(), "batchSize" to batchSizeEt.text.toString(), "ubatchSize" to ubatchSizeEt.text.toString(),
            "pres" to presEt.text.toString(), "freq" to freqEt.text.toString(),
            "mmap" to mmapEt.text.toString(),
            "repack" to (repackMode?.toString() ?: "")))
    }

    private fun toggleServer() {
        if (HttpApi.isRunning) {
            if (generating || HttpApi.isGenerating) {
                statusTv.text = "生成进行中，请先停止生成再停止服务"
                Toast.makeText(this, "生成进行中，请稍后再停止服务", Toast.LENGTH_SHORT).show()
                return
            }
            startService(Intent(this, InferenceService::class.java).setAction(InferenceService.ACTION_STOP))
            statusTv.text = "停止服务中…（模型将一并卸载）"
            Toast.makeText(this, "停止服务…", Toast.LENGTH_SHORT).show()
        } else {
            val mf = modelFile ?: run {
                Toast.makeText(this, "请先在模型库选择模型", Toast.LENGTH_SHORT).show(); return
            }
            // 启动前校验并保存端口，同步注入 HttpApi
            val port = portEt.text.toString().toIntOrNull()?.takeIf { it in 1024..65535 } ?: run {
                Toast.makeText(this, "端口需为 1024-65535", Toast.LENGTH_SHORT).show(); return
            }
            ModelStore.setServerPort(this, port)
            HttpApi.PORT = port
            // 保存并应用局域网开关
            ModelStore.setLanAccess(this, lanCb.isChecked)
            HttpApi.bindAll = lanCb.isChecked
            HttpApi.disableThinkingDefault = thinkCb.isChecked
            // 启动服务前 FA/KV 校验
            val ckSrv = cacheKEt.text.toString().toIntOrNull() ?: 0
            val cvSrv = cacheVEt.text.toString().toIntOrNull() ?: 0
            if (!checkFaForKv(flashCb.isChecked, ckSrv, cvSrv)) return
            val i = Intent(this, InferenceService::class.java).setAction(InferenceService.ACTION_START)
                .putExtra(InferenceService.EXTRA_MODEL_PATH, mf.absolutePath)
                .putExtra(InferenceService.EXTRA_CTX, ctxEt.text.toString().toIntOrNull() ?: 4096)
                .putExtra(InferenceService.EXTRA_THREADS, thrEt.text.toString().toIntOrNull() ?: 4)
                .putExtra(InferenceService.EXTRA_GPU, gpuEt.text.toString().toIntOrNull() ?: 0)
                .putExtra(InferenceService.EXTRA_CACHE_K, ckSrv)
                .putExtra(InferenceService.EXTRA_CACHE_V, cvSrv)
                .putExtra(InferenceService.EXTRA_MMAP, if ((mmapEt.text.toString().toIntOrNull() ?: 1) != 0) 1 else 0)
                .putExtra(InferenceService.EXTRA_FLASH, if (flashCb.isChecked) 1 else 0)
                .putExtra(InferenceService.EXTRA_PARALLEL_N, parallelNEt.text.toString().toIntOrNull() ?: 0)
                .putExtra(InferenceService.EXTRA_BATCH_SIZE, batchSizeEt.text.toString().toIntOrNull() ?: 0)
                .putExtra(InferenceService.EXTRA_UBATCH_SIZE, ubatchSizeEt.text.toString().toIntOrNull() ?: 0)
            startForegroundService(i)
            Toast.makeText(this, "启动服务中…", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 存活探测：真发一次 GET /health，把结论写进 probeResultTv。
     *
     * 刻意**不**先看 HttpApi.isRunning 就短路成"服务没启动"：那个标志位只代表
     * 本进程自认为在监听，而探测要回答的正是"端口到底通不通"。反过来也一样——
     * 服务未启动时照发，拿到"连接被拒绝"也是一条明确结论（比一句"请先启动服务"更有用，
     * 因为它同时证明了端口是空的）。唯一的例外是地址绑定：局域网关闭时服务只绑回环，
     * 探测必须打 127.0.0.1，否则用户会看到"局域网 IP 不通"这种误导性结论。
     */
    /**
     * 保存「额外放行的来源」。
     *
     * 刻意**不清洗、不改写用户输入**（只做去空行），但**逐条规范化后判断非法**：
     * 把 `http://A:80` 悄悄改成 `http://a` 会让用户照着回填时对不上，
     * 而"填错但没人说"正是白名单类配置最典型的坏法 —— 所以非法条目直接报出来。
     */
    private fun saveCorsExtra() {
        if (!::corsExtraEt.isInitialized) return
        val raw = corsExtraEt.text.toString()
        ModelStore.setCorsExtraOrigins(this, raw)
        val bad = raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            .filter { CorsPolicy.normalize(it).isEmpty() }
        if (bad.isNotEmpty()) {
            LlmEngine.uiLog("[CORS] 以下来源写法非法、已忽略（需要 http(s)://host[:port]，不带路径）: " +
                bad.joinToString(" "))
        }
        // 立即生效（不必重启服务）：来源是请求期的判据，不是绑定期的。
        for (o in ModelStore.corsExtraOrigins(this)) CorsPolicy.addOrigin(o)
        if (::corsHintTv.isInitialized) {
            corsHintTv.text = corsHintText()
        }
    }

    /**
     * 鉴权那一栏的说明文案。**必须当场说清三件事**，否则用户会凭想象使用：
     *   · 只有哪几个端点要 token（否则会以为 `/health` 也要，然后把它填进探针配置，
     *     结果是"探针报不可达"）；
     *   · 第三方 UI 该往哪儿填（`Authorization: Bearer`，也就是多数 UI 的「API Key」框）；
     *   · 它**不是**访问控制（与 CORS 一样，`curl` / 脚本从来不受这两者约束）。
     */
    private fun authHintText(): String {
        val on = ModelStore.apiToken(this).isNotEmpty()
        val head = if (on) "鉴权**已开启**：POST /v1/chat/completions、POST /v1/completions、" +
            "POST /v1/abort 要求 `Authorization: Bearer <token>`。"
        else "鉴权**未开启**：生成端点不要求 token（与升级前同行为）。"
        return head +
            "GET /health、GET /v1/models、GET / 自带测试页与 OPTIONS 预检**一律免鉴权**" +
            "（否则服务自身的存活探测会把自己判成不可达、浏览器预检也会被拦）。" +
            "第三方 UI 把 token 填进它的「API Key」框即可（走的就是 Authorization: Bearer）。" +
            "**它不是访问控制**：token 只挡没带凭据的调用方，挡不住同网段能直连的设备；" +
            "请在可信网络使用。"
    }

    /** 生成/清除 token 之后刷新这一栏的展示与文案（两处都要改，不能只改一处）。 */
    private fun refreshAuthUi() {
        if (!::authEt.isInitialized) return
        val t = ModelStore.apiToken(this)
        authEt.setText(t)
        authEt.hint = "未开启（生成端点不要求 token）"
        // 提示里**不回显** token 片段：截图 / Issue 里飞出去就白做了。
        if (::authHintTv.isInitialized) authHintTv.text = authHintText()
    }

    /**
     * 把 token 复制进剪贴板。
     *
     * 为什么值得一个按钮：token 的唯一用途就是被**抄进别的客户端**，
     * 而它 24 位、混大小写 —— 让用户在手机上手抄必然出错，出错的表现是
     * "401 token 不正确"，用户会以为鉴权本身坏了。复制把这条抄写环节删掉。
     */
    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("local-llm-server token", text))
            statusTv.text = "token 已复制到剪贴板"
            LlmEngine.uiLog("[鉴权] token 已复制到剪贴板（不写日志内容）")
        } catch (t: Throwable) {
            // 部分 ROM 限制后台剪贴板访问：不要静默失败，把 token 显示出来让用户手动复制。
            statusTv.text = "复制失败：${t.message}；请长按上面的框手动复制"
            LlmEngine.uiLog("[鉴权] 复制失败: ${t.message}")
        }
    }

    private fun corsHintText(): String {
        val extra = ModelStore.corsExtraOrigins(this)
        val head = "额外放行的来源（每行一个，如 http://192.168.1.10:3000）。"
        val tail = "留空 = 只放行本机（localhost / 本机 IP）与 file:// 打开的页面。" +
            "**不要填 \"*\"**：本接口无鉴权，回 \"*\" 等于你在浏览器里打开的任何一个网页" +
            "都能调用这台手机的模型。" +
            "同源访问（浏览器直接打开 http://手机IP:端口/ 的自带测试页）不需要 CORS。"
        return head + tail + if (extra.isEmpty()) "" else "\n当前额外放行 " + extra.size + " 条。"
    }

    /**
     * 用浏览器打开服务端的「自带测试页」。
     *
     * 为什么值得一个按钮：这个页面存在的全部意义就是"能马上验证手机上的服务通不通"，
     * 而它的地址（本机 IP + 端口）恰恰是用户最容易抄错的东西（用 127.0.0.1 在别的
     * 设备上打开、端口抄成旧的）。按钮直接按**当前实际绑定**拼地址，把这条抄写环节删掉。
     *
     * 服务没在跑时不打开空白页，而是明说原因（与"探测"按钮的态度一致：
     * 结论要能指导下一步动作）。
     */
    private fun openWebPage() {
        if (!HttpApi.isRunning) {
            LlmEngine.uiLog("[CORS] 自带测试页打不开：服务未启动")
            statusTv.text = "服务未启动，先在设置页点「启动服务」"
            return
        }
        val host = if (HttpApi.bindAll) (HttpApi.lanIp() ?: "127.0.0.1") else "127.0.0.1"
        val url = "http://$host:${HttpApi.PORT}/"
        LlmEngine.uiLog("[CORS] 打开自带测试页: $url")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (t: Throwable) {
            // 设备上没有浏览器（或 OEM 拦截了外部 Intent）时不要静默失败：
            // 把地址显示出来让用户自己复制，比"点了没反应"有用。
            statusTv.text = "无法打开浏览器，请手动访问 $url"
            LlmEngine.uiLog("[CORS] 打开浏览器失败: ${t.message}；地址 $url")
        }
    }

    private fun doProbe() {
        if (probing) return   // 连点不叠加：并发探测会互相覆盖结论
        probing = true
        probeBtn.isEnabled = false
        val port = portEt.text.toString().toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: ModelStore.serverPort(this)
        // 端口框与已运行服务可能不一致（改过没重启），先如实说明用的是哪个端口，
        // 免得用户对着"不通"的结论怀疑服务，实际只是探测了另一个端口。
        val runningPort = HttpApi.PORT
        // 这里取 `isRunning`（实际状态）而不是 `isDesiredRunning`：探测要回答的是
        // "端口到底通不通"，所以"按哪个端口去打"必须贴当前真实绑定。
        // 意图标志（desired）是给看门狗判"该不该自愈"用的，两者语义不同，别混用。
        val live = HttpApi.isRunning
        val host = if (live && HttpApi.bindAll) (HttpApi.lanIp() ?: "127.0.0.1") else "127.0.0.1"
        val probePort = if (live) runningPort else port
        probeResultTv.visibility = View.VISIBLE
        probeResultTv.setTextColor(0xFF666666.toInt())
        probeResultTv.text = "探测中… GET http://$host:$probePort/health"
        LlmEngine.probeMark("[探测] 开始 GET /health host=$host port=$probePort " +
            "服务自报运行中=$live(端口=$runningPort) 端口框=$port")

        Thread {
            val r = HealthCheck.probe(host, probePort)
            val v = HealthCheck.verdict(r)
            val report = buildString {
                append(HealthCheck.format(r, host, probePort))
                // 端口框与探测端口不一致时补一句怎么办，避免用户只看到"不通"两个字
                if (probePort != port) {
                    append("\n⚠ 探测的是运行中服务的端口 $probePort；端口框里是 $port（改动需重启服务生效）")
                }
            }
            ui.post {
                probing = false
                probeBtn.isEnabled = true
                probeResultTv.text = report
                probeResultTv.setTextColor(when (v) {
                    HealthCheck.Verdict.ALIVE -> 0xFF1B7F3B.toInt()
                    HealthCheck.Verdict.DEGRADED -> 0xFFB26A00.toInt()
                    HealthCheck.Verdict.UNREACHABLE -> 0xFFC62828.toInt()
                })
                // 探测结论不只在设置页留着，聊天页状态栏也同步一份——
                // 用户此时通常正拿着另一台设备连不上，想立刻看到结论。
                statusTv.text = report.lineSequence().first()
                renderLog()
            }
            // 探测结果本身也进日志：探针模式抓 native 闪退时，
            // "崩之前 /health 是活是死"是区分"服务假活"与"真崩"的关键一行。
            LlmEngine.probeMark("[探测] 结果 $v http=${r.httpCode} status=${r.status} " +
                "model_loaded=${r.modelLoaded} busy=${r.busy} ms=${r.elapsedMs} " +
                "err=${r.transportError ?: "-"}")
            LlmEngine.uiLog("[探测] $v http=${r.httpCode ?: "-"} status=${r.status ?: "-"} " +
                "模型已加载=${r.modelLoaded ?: "-"} 耗时=${r.elapsedMs}ms" +
                (r.transportError?.let { " 原因=$it" } ?: ""))
        }.apply { name = "health-probe"; isDaemon = true }.start()
    }

    private fun startServerPoll() {
        pollRunning = true
        val r = object : Runnable {
            override fun run() {
                if (!pollRunning) return
                if (HttpApi.isRunning) {
                    serverBtn.text = "停止服务"
                    val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                    serverTv.text = if (LlmEngine.hasModel) {
                        "服务运行中: http://$host:${HttpApi.PORT}/v1（模型: ${LlmEngine.modelDesc()}）\n" +
                            "自带测试页: http://$host:${HttpApi.PORT}/ （浏览器直接打开即可聊）"
                    } else {
                        "服务运行中: http://$host:${HttpApi.PORT}/v1（无模型：/v1/models 为空，生成返回 503）"
                    }
                } else {
                    serverBtn.text = "启动服务"
                    serverTv.text = if (LlmEngine.hasModel) "服务未运行（已加载模型，启动后直接挂载）" else "服务未运行"
                    // 服务停了，上一次探测的结论就过期了——留着会让"服务运行中"与
                    // "✅存活"同屏出现，比不显示更糟。数据保留（不 clear），只改标题行。
                    if (probeResultTv.visibility == View.VISIBLE &&
                        probeResultTv.text.startsWith("✅")
                    ) {
                        // 只有"结论是存活"的这一份需要作废；❌/⚠ 本来就还是在说问题，
                        // 服务停了它们只会更准确，不必改。
                        probeResultTv.text = probeResultTv.text
                            .replaceFirst(Regex("^✅ 存活"), "⬜ 已过期（服务已停止）")
                    }
                }
                ui.postDelayed(this, 1500)
            }
        }
        ui.post(r)
    }

    // ---- 生成 ----

    private fun doUnload() {
        if (!LlmEngine.hasModel) {
            Toast.makeText(this, "当前没有已加载的模型", Toast.LENGTH_SHORT).show()
            return
        }
        if (generating || HttpApi.isGenerating) {
            statusTv.text = "生成进行中，请先停止生成再卸载模型"
            Toast.makeText(this, "生成进行中，请稍后再卸载", Toast.LENGTH_SHORT).show()
            return
        }
        val wasServing = HttpApi.isRunning
        LlmEngine.unload()
        HttpApi.currentModel = null
        LlmEngine.uiLog(if (wasServing) "[卸载] 模型已卸载，服务保持运行（/v1/models 为空，生成返回 503）" else "[卸载] 模型已卸载（内存已释放）")
        statusTv.text = if (wasServing) "已卸载模型（服务运行中，暂无可用模型）" else "已卸载模型（内存已释放）"
        // 卸载后必须重画提示区，否则 HTP 说明还停留在上一个模型的信息上
        renderBackendState()
        Toast.makeText(this, "模型已卸载", Toast.LENGTH_SHORT).show()
    }

    /** 渲染聊天记录（system 不显示）；streaming 非空时作为正在生成的 AI 消息追加显示。 */
    private fun renderChat(streaming: String?) {
        val sb = StringBuilder()
        val hist = chatHistory(sysEt.text.toString().trim())
        for ((i, m) in hist.withIndex()) {
            if (i == 0 && m.first == "system") continue
            sb.append(if (m.first == "user") "我" else "AI").append("：").append(m.second).append("\n\n")
        }
        if (streaming != null) sb.append("AI：").append(streaming)
        outTv.text = sb.toString().trim()
        outTv.post { followChatBottom() }   // (点5): 新内容出现在下方，无需手动划到底
    }

    private fun doGenerate() {
        if (generating) return
        if (!LlmEngine.hasModel) {
            statusTv.text = "模型未加载：先点「加载模型」"
            Toast.makeText(this, "模型未加载：先点「加载模型」", Toast.LENGTH_SHORT).show()
            return
        }
        val p = promptEt.text.toString().trim()
        if (p.isEmpty()) { Toast.makeText(this, "输入消息", Toast.LENGTH_SHORT).show(); return }
        // system 提示词按会话持久化（失焦/生成时回写）
        sysEt.clearFocus()
        val sys = sysEt.text.toString().trim()
        SessionStore.setSystem(this@EngineActivity, currentSessionId, sys)
        SessionStore.appendUser(this@EngineActivity, currentSessionId, p)
        SessionStore.maybeAutoTitle(this@EngineActivity, currentSessionId, p)
        refreshSessionTitle()
        val messages = chatHistory(sys)
        renderChat(null)
        generating = true; genBtn.isEnabled = false
        statusTv.text = "生成中…"
        // 挂上卡死看门狗（只在 HTP 开时判定，避免长 prompt 纯 CPU 预填充被误报）
        LlmEngine.lastProgressAt = System.currentTimeMillis()
        hangReported = false
        ui.removeCallbacks(hangWatch)
        ui.postDelayed(hangWatch, 5000)
        val t0 = System.currentTimeMillis()
        // 参数区的解析 / 校验复用 SamplingParams：与 HTTP 入口同一套规则，默认值只有一处。
        // 此前 UI 侧解析失败一律“回落到默认值”，用户把 temperature 打成 1.5.0 只会静默按 0.8 跑。
        val (sp, spErr) = SamplingParams.fromRequest(JSONObject().apply {
            put("temperature", tempEt.text.toString().trim())
            put("top_p", topPEt.text.toString().trim())
            put("min_p", minPEt.text.toString().trim())
            put("top_k", topKEt.text.toString().trim())
            put("repeat_penalty", repEt.text.toString().trim())
            put("repeat_last_n", repeatLastNEt.text.toString().trim())
            put("frequency_penalty", freqEt.text.toString().trim())
            put("presence_penalty", presEt.text.toString().trim())
            put("max_tokens", maxEt.text.toString().trim())
        })
        if (sp == null) {
            val msg = spErr ?: "采样参数非法"
            statusTv.text = msg
            Toast.makeText(this@EngineActivity, msg, Toast.LENGTH_SHORT).show()
            ui.removeCallbacks(hangWatch) // 校验失败提前 return，别把卡死看门狗留着
            return
        }
        val maxTok = sp.maxTokens

        Thread {
            var n = 0
            var done = false
            var errd = false
            val sb = StringBuilder()
            // 取消归属（见上面 beginCancelable 处）。finally 里必须摘除，否则 App 侧
            // 每生成一次就留下一个"永远在跑"的轮次，之后 /v1/abort 会打空。
            var cancel: RequestCancel.Token? = null
            try {
                synchronized(LlmEngine.genLock) {
                    // 上一轮「停止」的意图在本轮开跑前作废（Issue #154）。
                    //
                    // 位置有两处讲究，都不能挪：
                    //   · 在 `synchronized(genLock)` **之内**：锁外清零会与「主线程
                    //     刚点完停止」的那一瞬间交错（清零把那一轮的停止意图抹掉，
                    //     用户看到的是"点了停止反而开始生成"）。持锁之后，本轮与
                    //     上一轮的停止意图已经全无关系。
                    //   · 在 `startCompletion` **之前**：中断动作必须**先于生成入口
                    //     装好再动**。反过来写（先起生成、再清标志）就有一步窗口里
                    //     旧标志仍然为 true，而那段窗口恰好是"prefill 一整段"。
                    stopRequested = false
                    // 思考开关与 HTTP 入口同一套实现（ThinkingControl），此处不再自己拼字符串。
                    // 两条判据都不能省：
                    //   · thinkingOn 要传给渲染 —— MiniCPM5 模板按它决定生成后缀吐不吐
                    //     `<think>\n`，不传就等于"思考永远开着"，本页的开关在这类模型上形同虚设；
                    //   · 生效判定要带上渲染结果 —— LFM2.5 这类模型的"思考开"是模板后缀硬编码的
                    //     （模板里没有 enable_thinking 变量），只看模板原文判不出来。
                    val chatTemplate = LlmEngine.chatTemplate()
                    val thinkingOn = !ModelStore.disableThinking(this@EngineActivity)
                    val rendered = LlmEngine.applyChatTemplate(messages, addAss = true, thinkingOn = thinkingOn)
                    val soft = ThinkingControl.softSwitchApplies(thinkingOn, chatTemplate, rendered.text)
                    val prompt = if (soft) ThinkingControl.applyToPrompt(rendered.text, thinkingOn, chatTemplate)
                                 else rendered.text
                    // 日志写清走的是哪条路：只写"软开关=不适用"会让「模板自己关的」与
                    // 「模板根本没有思考段」看起来一样，而这两者的失败模式完全不同。
                    LlmEngine.uiLog("[聊天] 思考=" + (if (thinkingOn) "开" else "关") + " " + when {
                        thinkingOn -> "按思考开渲染"
                        soft -> "软开关=已注入空 think 块（模板关不掉）"
                        ThinkingControl.templateSupportsEnableThinking(chatTemplate) -> "模板自带 enable_thinking，由库/模板自己关"
                        else -> "软开关=不适用（模板没有思考段）"
                    })
                    LlmEngine.newSampler(sp.temp, sp.topP, sp.minP, seed = sp.seed,
                        topK = sp.topK, repPenalty = sp.repeatPenalty, penaltyN = sp.repeatLastN,
                        freqPenalty = sp.freqPenalty, presencePenalty = sp.presencePenalty)
                    val err = LlmEngine.startCompletion(prompt, maxTok)
                    if (err != null) {
                        errd = true
                        ui.post { Toast.makeText(this@EngineActivity, err, Toast.LENGTH_SHORT).show(); statusTv.text = err }
                        return@Thread
                    }
                    // 登记取消归属：与 HTTP 路径共用一份状态，于是「HTTP /v1/abort」
                    // 与「停止按钮」打的是同一轮，两边的收尾也走同一条路径。
                    cancel = LlmEngine.beginCancelable()
                    while (n < maxTok && !stopRequested && !cancel.requested) {
                        val piece = LlmEngine.step() ?: break
                        n++
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            val s = piece
                            ui.post { renderChat(sb.toString()) }
                        }
                    }
                    done = true
                    val sec = (System.currentTimeMillis() - t0) / 1000f
                    val tps = if (sec > 0.01f) n / sec else 0f
                    val text = sb.toString()
                    ui.post {
                        if (text.isNotBlank()) {
                            SessionStore.appendAssistant(this@EngineActivity, currentSessionId, text); renderChat(null)
                        }
                        statusTv.text = "完成: $n tok | %.1f tok/s | ctx %d/%d".format(
                            tps, LlmEngine.contextUsed(), LlmEngine.contextSize())
                    }
                }
            } catch (e: Throwable) {
                errd = true
                ui.post { statusTv.text = "出错: ${e.message}" }
            } finally {
                cancel?.let { LlmEngine.endCancelable(it) }
                generating = false
                ui.removeCallbacks(hangWatch)   // 
                ui.post {
                    genBtn.isEnabled = true
                    if (!done && !errd) {
                        if (sb.isNotBlank()) {
                            SessionStore.appendAssistant(this@EngineActivity, currentSessionId, sb.toString())
                            renderChat(null)
                            statusTv.text = "已停止"
                        } else statusTv.text = "已中止"
                    }
                }
            }
        }.start()
    }

    /**
     * HTP 卡死看门狗。生成中若连续 [HANG_SEC] 秒既没有一条有效日志、也没有一个新
     * token，就把事实写进日志（导出时带着）并把状态栏改成明确提示——因为这种挂法
     * 「停止」是够不着 native 线程的，用户需要知道只能强杀，而不是反复点按钮。
     */
    private val hangWatch = object : Runnable {
        override fun run() {
            if (!generating || hangReported) return
            if (!LlmEngine.htpRequested) {
                ui.postDelayed(this, 3000); return
            }
            val idle = (System.currentTimeMillis() - LlmEngine.lastProgressAt) / 1000L
            if (idle >= HANG_SEC) {
                hangReported = true
                LlmEngine.uiLog(
                    "[卡住] 生成中连续 ${idle}s 无日志且无新 token（HTP 开）→ native 线程大概率卡在" +
                            " DSP 等回包，「停止」打断不了它，只能彻底杀掉应用。若刚才是超长 prompt 预填充则可能是误报。"
                )
                runOnUiThread {
                    statusTv.text = "疑似 native 卡死（${idle}s 无进展）：停止无效，请强杀应用后再改旋钮"
                    android.widget.Toast.makeText(
                        this@EngineActivity,
                        "疑似 HTP 卡死：${idle}s 无日志无输出。「停止」够不着 native 线程，请强杀应用",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
            ui.postDelayed(this, 3000)
        }
    }

    companion object {
        private const val REQ_PICK = 42

        /**
         * 日志页一次渲染多少行。ring 有 2000 行，一次全塞进 TextView 会让主线程排版明显变慢，
         * 而日志页默认贴底、用户关心的是**最新**那些行（探针摘要就在尾部）。
         * 被截掉的旧行不静默丢弃：页头有「仅显示最近 N 行」的说明，完整日志走导出。
         */
        private const val LOG_VIEW_LINES = 120
        private const val REQ_STORE = 43
        private const val HANG_SEC = 30L
    }

    // ================= 多会话管理 =================

    /** 当前会话的对话历史（system 置顶，仅用于喂模型）。 */
    private fun chatHistory(sys: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (sys.isNotEmpty()) out.add("system" to sys)
        for (m in SessionStore.load(this, currentSessionId)) out.add(m.role to m.content)
        return out
    }

    private fun refreshSessionTitle() {
        sessionTv.text = "☰ " + SessionStore.titleOf(this, currentSessionId) + "（点击切换/管理）"
    }

    /** 初始化会话：迁移旧单会话数据，恢复 system 输入框。 */
    private fun initSessions() {
        // hotfix: 会话初始化兜底——任何异常都不让 App 闪退，降级回旧版单会话行为
        runCatching {
            currentSessionId = SessionStore.currentId(this) ?: ""
            if (currentSessionId.isEmpty()) currentSessionId = SessionStore.create(this) ?: ""
            sysEt.setText(SessionStore.systemOf(this, currentSessionId))
        }.onFailure {
            currentSessionId = ""
            android.util.Log.e("LocalLLM", "initSessions failed", it)
        }
        renderChat(null); refreshSessionTitle()
    }

    /** 切换到指定会话（生成中禁止，由调用方检查）。 */
    private fun switchSession(id: String) {
        // 防御——若该会话已被删除（文件不存在）则忽略点击并刷新列表
        if (!SessionStore.exists(this, id)) { showSessionDialog(); return }
        if (id == currentSessionId) return
        SessionStore.setSystem(this, currentSessionId, sysEt.text.toString().trim())
        currentSessionId = id
        sysEt.setText(SessionStore.systemOf(this, id))
        renderChat(null); refreshSessionTitle()
    }

    /** 会话列表对话框：新建 / 切换 / 长按重命名或删除。 */
    private fun showSessionDialog() {
        if (generating) { Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show(); return }
        // 打开前把当前 system 输入回写，避免切走时丢失编辑
        SessionStore.setSystem(this, currentSessionId, sysEt.text.toString().trim())
        val infos = SessionStore.list(this)
        if (infos.isEmpty()) { doCreateSession(); return }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val labels = infos.map { i ->
            buildString {
                if (i.id == currentSessionId) append("✓ ")
                append(i.title)
                append("  · ").append(i.turns).append("条 · ").append(fmt.format(java.util.Date(i.updatedAt)))
            }
        }.toTypedArray()
        // 长按会话项 → 重命名/删除（先 create 再挂监听，避免 setItems+show 双重显示）
        sessionDialog?.dismiss() // 关闭旧的管理对话框，避免叠加
        val dialog = AlertDialog.Builder(this)
            .setTitle("会话管理（${infos.size}/${SessionStore.MAX_SESSIONS}）")
            .setItems(labels) { _, which ->
                val fresh = SessionStore.list(this)
                if (which in fresh.indices) switchSession(fresh[which].id) else showSessionDialog()
            }
            .setPositiveButton("＋ 新建") { _, _ -> doCreateSession() }
            .setNeutralButton("关闭", null)
            .create()
        val lv = dialog.listView ?: return  // AlertDialog.setItems 列表可用时才挂长按
        lv.setOnItemLongClickListener { _, _, pos, _ -> val fresh = SessionStore.list(this); if (pos in fresh.indices) showSessionActions(fresh[pos]); true }
        dialog.show()
        sessionDialog = dialog
    }

    private fun doCreateSession() {
        if (generating) { Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show(); return }
        val id = SessionStore.create(this)
        if (id == null) {
            Toast.makeText(this, "会话已达 ${SessionStore.MAX_SESSIONS} 个上限，请先删除旧会话", Toast.LENGTH_LONG).show()
            return
        }
        currentSessionId = id
        sysEt.setText("")
        renderChat(null); refreshSessionTitle()
        sessionDialog?.dismiss(); sessionDialog = null // 新建后关闭管理对话框
        Toast.makeText(this, "已新建会话", Toast.LENGTH_SHORT).show()
    }

    /** 长按会话项的重命名/删除入口。 */
    private fun showSessionActions(info: SessionStore.Info) {
        AlertDialog.Builder(this)
            .setTitle(info.title)
            .setItems(arrayOf("✏️ 重命名", "🗑 删除")) { _, which ->
                if (which == 0) promptRename(info) else confirmDeleteSession(info)
            }
            .show()
    }

    private fun promptRename(info: SessionStore.Info) {
        val input = EditText(this).apply {
            setText(info.title); setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名会话")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                SessionStore.rename(this, info.id, input.text.toString())
                if (info.id == currentSessionId) refreshSessionTitle()
                sessionDialog?.dismiss(); sessionDialog = null // 关闭旧对话框再刷新
                showSessionDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteSession(info: SessionStore.Info) {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("删除「${info.title}」？该会话的全部消息将不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                SessionStore.delete(this, info.id)
                sessionDialog?.dismiss(); sessionDialog = null // 删除后关闭旧管理对话框，避免叠加
                showSessionDialog() // 立即刷新列表显示删除结果
                if (info.id == currentSessionId) {
                    currentSessionId = SessionStore.currentId(this) ?: ""
                    if (currentSessionId.isEmpty()) {
                        doCreateSession()
                    } else {
                        sysEt.setText(SessionStore.systemOf(this, currentSessionId))
                        renderChat(null); refreshSessionTitle()
                    }
                }
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                showSessionDialog() // 立即刷新会话列表（含删除非当前会话的情况）
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

