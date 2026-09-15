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
    @Volatile private var stopRequested = false
    private lateinit var mmapEt: EditText
    private lateinit var loadBtn: Button
    private lateinit var genBtn: Button
    private lateinit var serverBtn: Button
    private lateinit var lanCb: android.widget.CheckBox
    private lateinit var thinkCb: android.widget.CheckBox
    private lateinit var flashCb: android.widget.CheckBox
    private lateinit var portEt: EditText
    private lateinit var serverTv: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var rootScroll: ScrollView
    // ---- tab 吸顶 + 三页左右滑动 + 设置页分组折叠 ----
    private var curTab = 1
    private val pageScrollY = IntArray(3)
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
            if (i == 2) renderLog()
            if (::chatBar.isInitialized) chatBar.visibility = if (i == 0) View.VISIBLE else View.GONE
            if (i == 0) { autoFollowChat = true; rootScroll.post { followChatBottom() } }
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
        serverBtn.setPadding(0, px(4), 0, 0)
        pageSet.addView(serverBtn, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        serverTv = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF666666.toInt())
            setPadding(0, px(2), 0, px(2))
        }
        pageSet.addView(serverTv)

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
        // 默认关闭思考（SmolLM3/Qwen3 等混合思考模型；客户端 enable_thinking 参数可覆盖）
        thinkCb = android.widget.CheckBox(this).apply {
            text = "默认关闭思考（客户端可用 enable_thinking 覆盖）"
            textSize = 13f
            isChecked = ModelStore.disableThinking(this@EngineActivity)
            setOnCheckedChangeListener { _, b ->
                ModelStore.setDisableThinking(this@EngineActivity, b)
                HttpApi.disableThinkingDefault = b
            }
        }
        pageSet.addView(thinkCb)
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
        val labRowG = labRow("parallelN 并行序列数", "batchSize 逻辑批大小", "ubatchSize 物理批大小")
        val gridRowG = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gridRowG.addView(parallelNEt, lp(0, 1)); gridRowG.addView(batchSizeEt, lp(0, 1)); gridRowG.addView(ubatchSizeEt, lp(0, 1))
        // 布局：HTP 开关提到「加载与性能」分组之上（它决定 native 变体选择、需重启，
        // 与"改后重新加载模型生效"的参数不属同一类）；cache K/V/mmap 行移到 parallelN 行之后，
        // 使 mmap 说明紧邻其输入框
        pageSet.addView(label("── NPU / Hexagon（HTP，改后需重启 App）──"))
        listOf(htpSwitch, quotaGroup, quotaHint, htpHintTv).forEach { addFull(it) }
        pageSet.addView(label("── 加载与性能（改后需重新加载模型）──"))
        listOf(labRowA, gridRowA, gpuHintTv, labRowG, gridRowG, labRowB, gridRowB).forEach { addFull(it) }
        pageSet.addView(label("mmap=0 为无映射直读（内存占用约翻倍，仅供诊断推理卡顿）"))
        addFull(flashCb); addFull(flashHint)   // (点2): FA 属加载期参数，紧跟 cache K/V

        pageSet.addView(label("── 生成与采样（对话时生效）──"))
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
            if (generating) { stopRequested = true; LlmEngine.abort(); statusTv.text = "停止中…" }
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
        val logHint = label("日志已实时写入内部 filesDir/logs/（崩溃也不丢）；上面两个导出按钮输出到公共「下载」目录")
        logHint.setPadding(logHint.paddingLeft, px(6), logHint.paddingRight, 0)
        pageLog.addView(logHint)
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

    override fun onDestroy() {
        pollRunning = false
        LlmEngine.logSink = null  // 防 Activity 泄漏（Handler 持引用继续 post）
        super.onDestroy()
    }

    private fun renderLog() {
        if (!::logTv.isInitialized) return
        logTv.text = LlmEngine.recentLogs().takeLast(120).joinToString("\n")
    }

    // ---- 模型库 ----

    @SuppressLint("SetTextI18n")
    private fun refreshModelList() {
        listContainer.removeAllViews()
        val sel = modelFile?.name
        val models = ModelStore.list(this)
        if (models.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "（模型库为空：点“添加模型”选择第一个 GGUF）"
                textSize = 12f; setPadding(0, px(4), 0, px(4))
            })
        }
        for (f in models) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val isSel = f.name == sel
            val ext = ModelStore.isExternal(this, f.name)
            val missing = ext && !f.isFile
            val tv = TextView(this).apply {
                text = run {
                    val al = ModelStore.aliasOf(this@EngineActivity, f.name)
                    (if (isSel) "● " else "○ ") + (if (ext) "🔗 " else "") + al +
                        (if (al != f.name.removeSuffix(".gguf")) " · ${f.name}" else "") +
                        if (missing) "  ⚠ 原文件已丢失" else "  (${f.length() / 1048576} MB)"
                }
                textSize = 13f
                if (isSel) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, px(6), 0, px(6))
            }
            tv.setOnClickListener { selectModel(f) }
            tv.setOnLongClickListener {
                promptAlias(f, true)
                true
            }
            val del = btn(if (ext) "解" else "删")
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
            "mmap" to mmapEt.text.toString()))
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

    private fun startServerPoll() {
        pollRunning = true
        val r = object : Runnable {
            override fun run() {
                if (!pollRunning) return
                if (HttpApi.isRunning) {
                    serverBtn.text = "停止服务"
                    val host = if (HttpApi.bindAll) HttpApi.lanIp() ?: "127.0.0.1" else "127.0.0.1"
                    serverTv.text = if (LlmEngine.hasModel) {
                        "服务运行中: http://$host:${HttpApi.PORT}/v1（模型: ${LlmEngine.modelDesc()}）"
                    } else {
                        "服务运行中: http://$host:${HttpApi.PORT}/v1（无模型：/v1/models 为空，生成返回 503）"
                    }
                } else {
                    serverBtn.text = "启动服务"
                    serverTv.text = if (LlmEngine.hasModel) "服务未运行（已加载模型，启动后直接挂载）" else "服务未运行"
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
        val maxTok = maxEt.text.toString().toIntOrNull() ?: 512
        val temp = tempEt.text.toString().toFloatOrNull() ?: 0.8f
        val topK = topKEt.text.toString().toIntOrNull() ?: 0
        val repPen = repEt.text.toString().toFloatOrNull() ?: 1f
        val repN = repeatLastNEt.text.toString().toIntOrNull() ?: 64
        val topP = topPEt.text.toString().toFloatOrNull() ?: 0.95f
        val minP = minPEt.text.toString().toFloatOrNull() ?: 0f
        val presPen = presEt.text.toString().toFloatOrNull() ?: 0f
        val freqPen = freqEt.text.toString().toFloatOrNull() ?: 0f

        Thread {
            var n = 0
            var done = false
            var errd = false
            val sb = StringBuilder()
            try {
                synchronized(LlmEngine.genLock) {
                    var prompt = LlmEngine.applyChatTemplate(messages, addAss = true)
                    if (ModelStore.disableThinking(this@EngineActivity) &&
                        LlmEngine.chatTemplate().contains("enable_thinking")) {
                        prompt = prompt + "<think>\n\n</think>\n\n"  // 追加在末尾（紧贴assistant生成后缀），置于开头会诱导模型模仿输出空think块
                    }
                    LlmEngine.newSampler(temp, topP, minP, topK = topK,
                        repPenalty = repPen, penaltyN = repN, freqPenalty = freqPen,
                        presencePenalty = presPen)
                    val err = LlmEngine.startCompletion(prompt, maxTok)
                    if (err != null) {
                        errd = true
                        ui.post { Toast.makeText(this@EngineActivity, err, Toast.LENGTH_SHORT).show(); statusTv.text = err }
                        return@Thread
                    }
                    while (n < maxTok && !stopRequested) {
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

