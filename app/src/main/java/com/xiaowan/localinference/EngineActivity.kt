package com.xiaowan.localinference

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Layout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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

    // ══════════════════════════════════════════════════════════════════════
    // 暗色模式（需求：「默认关闭，可选跟随系统」）
    //
    // 为什么**必须**有一个集中的取色点，而不是在各个控件上改用
    // `?attr/colorBackground` 之类的主题引用：
    // 本 Activity 从头到尾都是 `new TextView(this)` / `new Button(this)`，
    // 颜色是**显式** `setTextColor(0xFF...)` 写死的（全文件几十处），
    // 而 `setTheme()` 只影响那些**没有被显式设色**的地方。也就是说：
    // 不把这一层换掉，切了主题之后页面还是亮的 —— 只是对话框暗了，
    // 而这正是"看着做了、其实没做"。
    //
    // 判据：一组色**只有一份来源**。浅色那一列就是本仓库原本的色值
    // （逐字搬迁，不改一个字节），暗色那一列是对应的暗底版本。
    // 两者靠 [ThemePalette] 的字段名对齐，而不是靠两处 if —— 后者迟早分叉。
    // ══════════════════════════════════════════════════════════════════════

    /** 浅色一列（= 本仓库的历史色值；改这里的任何一个数，等于改所有页面的外观）。 */
    private val LIGHT_PALETTE = ThemePalette(
        background = 0xFFFAFAFA.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceAlt = 0xFFF2F2F2.toInt(),
        divider = 0xFFE0E0E0.toInt(),
        onSurface = 0xFF202124.toInt(),
        textPrimary = 0xFF000000.toInt(),
        textSecondary = 0xFF666666.toInt(),
        textMuted = 0xFF888888.toInt(),
        textFaint = 0xFF9E9E9E.toInt(),
        hint = 0xFF999999.toInt(),
        icon = 0xFF616161.toInt(),
        link = 0xFF1A73E8.toInt(),
        accent = 0xFF3D5AFE.toInt(),
        warn = 0xFFB26A00.toInt(),
        error = 0xFFC62828.toInt(),
        ok = 0xFF1B7F3B.toInt(),
        chipOn = 0xFFD6E4FF.toInt(),
        chipOnText = 0xFF1A4FBF.toInt(),
        chipOffText = 0xFF444444.toInt(),
        userBubble = 0xFFD2E3FC.toInt(),
        userBubbleText = 0xFF0B2A4A.toInt(),
        aiBubble = 0xFFF1F3F4.toInt(),
        aiBubbleText = 0xFF202124.toInt())

    /**
     * 暗色一列。**不是**把浅色取反 —— 逐项按"底 → 前景"重排：
     *
     *   · 底色走 Material 的 `#121212` 一族（纯黑在 OLED 上对比过硬，
     *     而 `#121212` 是官方暗色推荐，配合分层 surface 仍然看得出卡片）；
     *   · 正文色用 `#E8EAED`（官方暗色正文），**不用纯白**：纯白在暗底上
     *     会晕（halation），长文本读久了累；
     *   · 强调色整体**提亮**（`1A73E8` → `8AB4F8`）：同一个蓝色在暗底上
     *     对比度会掉一半，不提亮就成了"能看见但读不清"。
     *     同理 `B26500` → `FFB74D`、`C62828` → `FF6B6B`、`1B7F3B` → `81C995`。
     *
     * ⚠ 「用户气泡 vs AI 气泡」这一对必须**在暗底上也分得开**（这正是气泡
     *   功能存在的理由）：所以不是把浅蓝反向变成深蓝，而是取一深一浅两个
     *   **可区分**的暗色档（用户 `#2B4A6F` / AI `#2A2B2D`）+ 各自的高对比正文。
     */
    private val DARK_PALETTE = ThemePalette(
        background = 0xFF121212.toInt(),
        surface = 0xFF1E1F22.toInt(),
        surfaceAlt = 0xFF2A2B2D.toInt(),
        divider = 0xFF3C4043.toInt(),
        onSurface = 0xFFE8EAED.toInt(),
        textPrimary = 0xFFE8EAED.toInt(),
        textSecondary = 0xFFAAB0B6.toInt(),
        textMuted = 0xFF9AA0A6.toInt(),
        textFaint = 0xFF8A8F94.toInt(),
        hint = 0xFF9AA0A6.toInt(),
        icon = 0xFF9AA0A6.toInt(),
        link = 0xFF8AB4F8.toInt(),
        accent = 0xFF8AB4F8.toInt(),
        warn = 0xFFFFB74D.toInt(),
        error = 0xFFFF6B6B.toInt(),
        ok = 0xFF81C995.toInt(),
        chipOn = 0xFF2B4A6F.toInt(),
        chipOnText = 0xFFBBD6FF.toInt(),
        chipOffText = 0xFFBDC1C6.toInt(),
        userBubble = 0xFF2B4A6F.toInt(),
        userBubbleText = 0xFFE8F0FE.toInt(),
        aiBubble = 0xFF2A2B2D.toInt(),
        aiBubbleText = 0xFFE8EAED.toInt())

    /**
     * 本次 onCreate 生效的颜色。**只在这里赋值一次**（在 `super.onCreate()`
     * 之前就定下来），之后全文件只读它 ——
     * "跟随系统"的用户在系统里切了深浅后走 `onConfigurationChanged` 重建
     * Activity，那时会重新赋值一遍；不存在"运行到一半换了色而一半控件还旧"
     * 的中间态。
     */
    private lateinit var pal: ThemePalette

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var modelTv: TextView
    private lateinit var statusTv: TextView
    /**
     * 聊天记录容器：**一条消息一个气泡 View**，不是"一个 TextView 拼全部"。
     *
     * ══════════════════════════════════════════════════════════════════
     * 为什么必须换掉"拼字符串"（而不是给它加个字体/背景）
     * ══════════════════════════════════════════════════════════════════
     * 旧形态把整段历史拼成一个字符串塞进一个 TextView，于是：
     *   · 无法"只重绘一条" —— 单条删除 / 重新生成都要求某一条单独换掉；
     *   · 流式时每来一个 token 就重建**整屏**文本（长会话每 token 一次全量 setText）；
     *   · 我 / AI 靠 `"我："` / `"AI："` 前缀区分，而那段前缀与喂模型的
     *     `chatHistory` 是同一套拼接逻辑 —— 文案一改就可能影响喂模型的内容。
     * 换成按条建 View 之后，这三件事各自有了正确的位置。
     */
    private lateinit var chatContainer: LinearLayout
    /**
     * 第 i 条消息对应的气泡容器（与 `renderHistory()` 建出来的 View 一一对应）。
     *
     * 存在的理由只有一个：**长按删除要知道"删的是哪一条"**。点击监听会捕获循环
     * 变量，而列表随时可能被重绘 —— 所以查下标必须**当场从 View 反查**，
     * 不能信捕获时记下的那个值。重建列表时整份清空，不留悬空引用。
     */
    private val chatBubbles = mutableListOf<View>()
    /**
     * 正在生成的那条气泡的正文 View；null = 没有在生成。
     *
     * 流式**只改它的 text**，不重建任何 View：整屏重建会让长会话每 token
     * inflate 几十个 View，那正是换掉旧写法的原因之一。收尾时它连同气泡一起
     * 并入 `chatBubbles`（重新渲染一次），此后它不再是"可变的那一条"。
     */
    private var streamingBody: TextView? = null
    private lateinit var logTv: TextView
    private lateinit var promptEt: EditText
    private lateinit var sysEt: EditText

    // ---- 角色卡（V1）：聊天页那一行 ----
    /** 卡片选择行（标签 + 下拉 + 「管理卡片」）。没有卡片时整行**仍然在**。 */
    private lateinit var cardRow: LinearLayout
    private lateinit var cardLabel: TextView
    private lateinit var cardSpinner: android.widget.Spinner
    private lateinit var cardManageBtn: Button

    /**
     * 卡片状态行（常驻，不是 Toast）。三种状态各自一句：
     * 用的是快照 / 卡片已不在库里 / 卡片改了而本会话没跟着变。
     *
     * 为什么不合并成一句"卡片状态异常"：三者的**处置**完全不同 ——
     * 一个是"正常，只是提醒你改卡片不会影响本会话"，一个是"你得重新挂一张"，
     * 一个是"要不要用新内容覆盖本会话"。糊成一句话用户不知道点它做什么。
     */
    private lateinit var cardHintTv: TextView

    /**
     * 下拉列表**当前这一屏**对应哪几张卡（下标 → id 的字典）。
     *
     * 为什么不让 `onItemSelected` 直接拿 position 去 `RoleCardStore.get` 里对：
     * 下拉的内容会随卡片库变化重填，而重填那一刻框架会**触发一次**
     * `onItemSelected`（选中下标 0）—— 那一次不是用户选的。用一个"正在填充"
     * 的开关把那一拍吃掉，是本仓库对 spinner 的标准做法（否则每次刷新列表
     * 都会顺手把当前会话的卡片改掉）。
     */
    private var cardPickerItems: List<String> = emptyList()
    private var cardPickerFilling = false
    /**
     * 旧会话迁移被上限挡住时的**常驻**提示（不是 Toast）。
     *
     * 用常驻行而不是 Toast 弹一次：Toast 在冷启动那一瞬弹出，用户正在看别处
     * （或者干脆没解锁就看到主界面）就会漏掉，之后**再也不会出现** ——
     * 而他要解决的是「我的旧聊天记录不见了」，重启一次才发现提示会更像"又坏了"。
     * 常驻行则一直挂在那儿，直到他删掉几个会话、下次启动迁移成功才消失。
     */
    private var migrateHintTv: TextView? = null
    private var migrateHint: String? = null

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
    // -- 四页 Tab（聊天 / 设置 / 知识库 / 日志）--
    private lateinit var tabRow: LinearLayout
    private lateinit var pageChat: LinearLayout
    private lateinit var pageSet: LinearLayout
    /** 知识库专页：从设置页拆出来，见 [pageRag] 的挂载处注释（设置页太长）。 */
    private lateinit var pageRag: LinearLayout
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
    /**
     * 最近一次「存活探测」的结构化结论。
     *
     * 缓存它而不是让「后台保活」状态行自己再发一次请求：进一次设置页就打一次自己的
     * `/health`，在**后台被冻**那一刻正好会挂满一个超时（而那一刻恰恰是用户最想
     * 打开这一页的时候）。所以状态行只消费这份**已经取到的**读数，
     * 没有读数时如实说"先探测"，不猜。
     */
    @Volatile private var lastProbeResult: HealthCheck.Result? = null
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
    /**
     * 「后台保活」状态行（Issue #159）。
     *
     * 为什么它必须常驻在设置页而不是只写进 README：这三样状态（无障碍 / 忽略电池优化 /
     * 冻结痕迹）此前**一个都看不到** —— 用户"以为自己开好了、其实没生效"没有任何反馈面。
     * 它的内容是**读系统真值**算出来的（见 [KeepAlive]），不是本 App 自己的标志位。
     */
    private lateinit var keepAliveTv: TextView
    private lateinit var portEt: EditText
    private lateinit var serverTv: TextView
    private lateinit var listContainer: LinearLayout
    // ---- 嵌入模型（RAG 双常驻的那一份）----
    //
    // 为什么它必须与对话模型**分列**在 UI 上：此前 `LlmEngine.loadEmbedModel` 写好了
    // 却**没有任何调用方** —— 用户点"加载模型"永远只走对话那条路，于是"按对话模型的
    // 步骤加载向量模型"必然表现为"两个模型互相顶掉"（其实是两个文件装进了同一个槽）。
    // 分开的列表 + 分开的按钮，是让"装进哪个槽"这件事在 UI 上不再有歧义。
    private lateinit var embedListContainer: LinearLayout
    private lateinit var embedStatusTv: TextView
    private lateinit var embedLoadBtn: Button
    private lateinit var embedUnloadBtn: Button
    /** 渲染代际：只允许最新一次取数的结果落到视图上（同 refreshModelList）。 */
    private var embedListGen = 0
    /** 当前选定的嵌入模型文件；null= 没选。与 [modelFile] 各自独立。 */
    private var embedFile: File? = null
    /**
     * 上一次画嵌入状态行时**驻留**的是哪个文件（`LlmEngine.embedPath` 的快照）。
     *
     * 与 [shownLoadedPath] 同一用途、同一理由（Issue #228 的**嵌入侧**那一半）：
     * 让轮询能发现"嵌入这一份的驻留值在别处变了"。嵌入没有"服务侧卸载"那条路径
     * （`stopEmbed` 只在会话结束时会调，不是 App 内操作），但轮询判据与对话侧
     * **同构**地钉住，就不必逐个路径去论证"这条不会变"——每论证一次就多一个
     * 可能漏的路径，而漏掉的表现正是"界面说在、其实不在"。
     *
     * ⚠ 同 [shownLoadedPath]：`String?`，`null` 留给"从未取过"，不用来代表"未加载"。
     */
    private var shownEmbedPath: String? = null

    // ---- 知识库（RAG 阶段 2）----
    //
    // 为什么库管理放进「设置」而不是聊天页：建索引是**低频、重操作**（100 条约 5~30 秒），
    // 而聊天页的每一次交互都要为"点错一下会不会触发重建"担心。放进设置页 +
    // 显式按钮，让"重操作"只发生在用户明确要它发生的时候。
    private lateinit var ragStatusTv: TextView
    private lateinit var ragToggleCb: android.widget.CheckBox
    private lateinit var ragBuildBtn: Button
    private lateinit var ragSaveBtn: Button
    private lateinit var ragDropBtn: Button
    /** 「删库」（ISSUE #259）：把磁盘上这个库整个抹掉，与「删索引」不是同一个动作。 */
    private lateinit var ragDeleteBtn: Button
    private lateinit var ragKbEt: EditText
    /**
     * 「每批编几条」（ISSUE #269 第 2 步。合批可见可调）。
     *
     * 为什么它是一个**输入框**而不是一个下拉档：上限由 native 的
     * `n_seq_max` 决定（见 [LlmEngine.kEmbedBatchRows]），而输入框 + 读取时钳到区间
     * 比“固定几个选项”更能对付将来的变化；而且与这一页其他参数
     * （库名 / 远端库名）同一套交互（失焦即保存）。
     */
    private lateinit var ragBatchEt: EditText
    /**
     * 「嵌入每批上下文（每条文本）」（0.9.212）。
     *
     * 为什么它是一个**输入框**而不是一个下拉档：这一格的合法区间上界
     * （[LlmEngine.kEmbedCtxMax]）要在**加载之前**就知道（设置页要立刻能提示非法值），
     * 而"真能被库接受多少"是设备相关的（手机上给太大就是加载失败）。
     * 输入框 + 读取时钳区间，比"固定几个选项"更能对付这种"区间已知、真值未知"的参数，
     * 也与这一页其他几格（批大小 / 线程数 / 库名）同一套交互。
     */
    private lateinit var ragEmbedCtxEt: EditText
    /** 远端知识库开关与库名（阶段 3）—— 与 [ragToggleCb] / [ragKbEt] **各是一份**。 */
    private lateinit var ragRemoteToggleCb: android.widget.CheckBox
    private lateinit var ragRemoteKbEt: EditText
    private lateinit var ragDocEt: EditText
    private lateinit var ragListContainer: LinearLayout
    /**
     * 导出库 / 导入库 / 导入 .txt·.md 三个按钮（知识库的"生命周期出口"，
     * 见 doExportRag / importRagArchiveFile / importRagTextFile）。
     *
     * 「导入库」= 读一份**已经是一条条条目**的库文件（信封 / 裸 JSON 数组 / 裸 JSONL），
     * 直接追加进库；「导入 .txt / .md」= 读一份**连着的一整篇文字**，按行切开、
     * **回填进编辑器**，等用户点「建库/重建」—— 也就是"粘贴建库"那条路。
     * 两者不是同一个动作，所以不是同一个按钮（见下方那段注释）。
     */
    private lateinit var ragExportBtn: Button
    private lateinit var ragTextImportBtn: Button
    private lateinit var ragImportBtn: Button
    /** 渲染代际（同 [embedListGen]）：丢弃过期的库列表。 */
    private var ragListGen = 0
    /**
     * 状态行"库里几条"那一次**主线程**轻读的上限（字节）。
     *
     * ⚠ 这个数不是"性能调优值"，是一条**判据的闸门**：这一读跑在主线程上
     *（状态行是"我点了之后到底成没成"的唯一反馈面，异步渲染会让它慢半拍地
     * 显示上一步的结论 —— 那正是"骗人的绿字"的来源），所以它必须小到
     * 一次读**不可能**被用户看见。64 KB 在低端机上是一次 sub-ms 的读，
     * 而它覆盖了绝大多数库（几十到几百条短笔记）。
     *
     * 超过这一档的库走 [RagIndexStore.textsCountCapped] 的 `null`（= 数不完），
     * 状态行就不报这个数，等库列表的补齐快照到位 —— 也就是"宁可少一个数字，
     * 不要卡一下"。它**不是**在鼓励大库 —— 大库的条数照样会显示，只是由
     * 后台算好再送到屏幕上。
     */
    private val RAG_STATUS_COUNT_CAP_BYTES = 64 * 1024
    /**
     * 正文编辑器（[ragDocEt]）的取数代际。与 [ragListGen] 分开：
     * 列表与正文是两次独立的后台读，混用一个计数器会让"读列表"把"读正文"的结果丢掉。
     */
    private var ragDocGen = 0
    /** 建库进行中：重建按钮在这期间必须禁用（否则连点两次并发编码同一份嵌入 Session）。 */
    private var ragBuilding = false
    /**
     * 编辑器里装的是哪个库的正文。
     *
     * 为什么需要它（而不是读 [ragKbEt]）：库名输入框允许用户**手打一个新名字**，
     * 那一刻它与编辑器的内容就不再是同一个库了。切库/保存都要问"框里这份正文属于谁"，
     * 而"当前输入框里的名字"回答不了这个问题 —— 拿它当答案会把 A 库的正文写进 B 库。
     */
    private var ragDocOwner = ""
    /**
     * 编辑器有没有未保存的改动（与 [ragDocOwner] 那份磁盘内容逐字比较得来的）。
     *
     * 为什么必须有：切库会把编辑器内容**整段换掉**。没有这个标记时，用户改了正文
     * 又顺手点了另一个库 —— 改动**静默消失**，而界面上一切正常（新的内容、新的状态行）。
     * 这类"改了但没存、也没人告诉你"是本屏最贵的一种数据丢失（100 条笔记要重打）。
     */
    private var ragDocDirty = false
    /**
     * 正文框现在**是不是一片只读预览**（`false` = 它仍是那个可编辑的输入框）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么这一位非有不可（P1-a，ISSUE #228）
     * ═══════════════════════════════════════════════════════════════════════
     * 这个框此前是**唯一**能"改全文"的地方，代价是它必须一次装下整个库 ——
     * 而 `EditText` 装多少就排多少版（全量 `StaticLayout`），于是库一大，
     * "点开库"这个动作本身就要等几秒到十几秒。截断（[RagUiCap.MAX_DOC_CHARS]）
     * 只是把最坏那一档挡掉，门槛仍随库的大小走。
     *
     * P1-a 把它反过来：框只预览**当前这一页**（与条目列表同一页），且**只读**。
     * 编辑改走两处已经存在的路 —— 每条一个「删」、「追加」一条。
     * 收益是"几十 MB 也能秒开"；代价是失去"在框里改全文"（而那种编辑在几千条
     * 的库上本来就已经不可用：框装不下）。
     *
     * [!] 它**必须是状态**，不能由"库大不大"就地算：判据是"磁盘上有没有这个库"
     *     —— 见 [RagUiCap.docIsSlice] 里那段（新建、未落盘的库只有一个
     *     `false`，因为磁盘上没有任何副本可以拿来做预览）。
     * [!] 写点收在 [setRagDocSliced] 一处：框的只读属性、说明行、以及
     *     "取正文不再走框"的判据必须**同生同灭** —— 分开各写一次必然漂移，
     *     而漂移的形态是"框看着是只读的，可某一条路还在拿它当全文用"（静默丢内容）。
     */
    private var ragDocSliced = false
    /**
     * 框里**现在**画着的那一片：`ragDocSliceRows` 是这一片逐行的原始内容
     * （来自 [ragRowsShown] 的下标即行号那份表，`null` = 那一行读不出来），
     * `ragDocSliceFirst` 是这一片第一行在**正文文件里的行号**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须把"画出去的那一份"留下来（P1-b）
     * ══════════════════════════════════════════════════════════════════════
     * 保存时的判据是 [RagUiCap.docEditPlan]：它拿"框里现在的内容"与
     * "**画出去时**的那一份"逐行比，只把**变了的行**回写。所以它必须知道两件事：
     *   · 每一行**原来**是什么（才判得出哪几行变了）—— [ragDocSliceRows]；
     *   · 第 0 行对应**正文文件**的哪一行（才写得到对的地方）—— [ragDocSliceFirst]。
     *
     * ⚠ 这两个数**不许**在保存那一刻现算：[ragRowsPage] 可能已经被翻页改过、
     *   [ragRowsShown] 可能已经被删除改过 —— 现算出来的区间说的是"现在这一页"，
     *   而框里装的可能是**上一页**（用户翻了页却还没重画）。两者一分家，
     *   回写就落在**另一段行**上，而那种错位不报错（屏幕上一切正常）。
     *   所以它们与 [renderRagDocSlice] 的 `setText` **同一次**被写，且只在那里写。
     */
    private var ragDocSliceRows: List<String?> = emptyList()
    private var ragDocSliceFirst = 0
    /**
     * 是否**正在**程序化回填编辑器。
     *
     * 为什么需要这个旗标：`setText` 也会触发 `TextWatcher`，于是"点库 1 回填库 1 的内容"
     * 会被记成"用户改动了正文"——切库立刻弹一次"要不要保存"，而用户什么都没改。
     * 那会让这个确认框很快变成"闭眼点掉"的噪声，真正该拦的那次一起被点掉。
     */
    private var ragDocLoading = false
    // ---- 知识库条目编辑（RAG 阶段 3：追加一条 / 删除单条）----
    private lateinit var ragNewEt: EditText
    private lateinit var ragRowsContainer: LinearLayout
    private lateinit var ragAppendBtn: Button
    /** 条目渲染代际（与 [ragListGen] / [ragDocGen] 各自独立，理由同那两处）。 */
    private var ragRowsGen = 0
    /**
     * 上一次渲染出来的条目行（行号 + 摘要）。
     *
     * 为什么留一份：删除按钮只知道自己点的是**第几个**视图，而删除要的是
     * **正文文件里的行号**。用视图下标当行号在"有坏行被跳过"时必然错位 ——
     * 那时删掉的是隔壁那一行，而界面上看起来完全正常（条目数少了一个）。
     * 所以渲染时把行号一并记下来，点击时按它取。
     */
    private var ragRowsShown: List<Pair<Int, String?>> = emptyList()
    /**
     * 库名 -> 正文条数。由 [refreshRagList] 在**后台**从同一次目录遍历里顺手填上。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么要有这一份缓存（而不是让状态行自己去数）
     * ═══════════════════════════════════════════════════════════════════════
     * 状态行需要"当前库有几条"，而唯一的真值是 `texts.jsonl` 的正文行数 ——
     * 而 [RagIndexStore.textsCount] 为了得到它会**把整个文件读进内存再按行切**。
     * 100 条短笔记是几十 KB，主线程上做只值几毫秒；7021 条（用户报的那个库）
     * 是 6 MB、300 万字符 —— 而 [renderRagStatus] 会被库列表刷新、切库、导入、
     * 建库结束一路调用，于是这一屏每次重画都付一次整文件读。
     *
     * 而 `RagIndexStore.list()` **已经在后台**为每个库读了一遍正文（它要数行数、
     * 还要算正文 hash），同一时刻同一份文件被读了两次 —— 一次在后台、一次在
     * 主线程上。把后台那次的结果留下来，主线程这一次就变成一次哈希查表。
     *
     * [!] 它是**渲染快照**，不是真值：真值永远在磁盘上。所以：
     *   · 只有 [refreshRagList] 写它（唯一的写点）；
     *   · 查不到时**宁可不报这个数**（而不是回落到 0）：`null` 在这里的意思是
     *     "这份快照还没到"，而 0 的意思是"这个库是空的" —— 两者在屏幕上是同一个
     *     字，说出口的却是相反的结论。也**不许**为了补齐它再读一次文件
     *     （那正是要拆掉的东西）；
     *   · 任何写盘动作（保存 / 导入 / 追加 / 删除 / 建库）之后都跟着一次
     *     [refreshRagList]，所以它不是"会停在旧值上"的那种缓存。
     */
    private var ragListEntries: Map<String, Int> = emptyMap()
    /**
     * 库名 -> 那个库**完整**的一份 [RagIndexStore.Info]（条数、dim、hasIndex、inSync…）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 它和 [ragListEntries] 是一件事的两半（一个是"几条"，一个是"这整行"）
     * ═══════════════════════════════════════════════════════════════════════
     * 库列表现在画**两层**（见 [refreshRagList]）：骨架只有名字（一次 `listFiles`），
     * 补齐那层才带"几条 · 状态"（逐库读正文，贵的那一半）。而"补齐"要有个东西说明
     * **哪些名字已经配上了数字**——那个东西就是这张表：
     *   · 名字**在**表里 -> 那一行可以画数字（来自这里的真值）；
     *   · 名字**不在** -> 只画名字，一个数字都不报（见 [renderRagList] 的说明）。
     *
     * ⚠ 不复用 [ragListEntries] 当这张表：那个是 `Map<String, Int>`，只能回答
     *   "几条"，回答不了"这个库我量过了吗"与"它什么状态"（`hasIndex` / `inSync` /
     *   `isPending` 全没有）。而"要不要画数字"这个判据一旦退化成"条数 != 0"，
     *   就会把**量到 0 条**与**还没量到**混成一个 —— 那正是本仓库最忌讳的那种
     *   "两个意思挤在同一个字上"（`0 条` 与"不知道几条"在屏幕上是同一行字）。
     *
     * [!] 写点只有 [loadRagListCounts] 的补齐那一跳（它是唯一真的读过每个库正文的地方）。
     *     骨架那一层**不许**写它 —— 骨架的 [RagIndexStore.Info.entries] 是**索引**里
     *     那个数（可能落后于正文），写进来就是"把过期数当量过的数"。
     */
    private var ragListCounts: Map<String, RagIndexStore.Info> = emptyMap()
    /**
     * 编辑器里现在这份正文**是不是被截过的**（只装了磁盘/文件的前 [RagUiCap.MAX_DOC_CHARS] 个字符）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么这个旗标非有不可（而不是"反正框里那份看着是全的"）
     * ═══════════════════════════════════════════════════════════════════════
     * `ragDocEntries()` 把编辑器里的内容当成**要索引的全部**。而回填一旦超限就只装
     * 了头部 —— 此时用户点「建库/重建」，索引出来的就是**一个缺了尾巴的库**：
     * 建库成功、条数看着正常、界面上一切正常，只是后半段的关键词一个都搜不到。
     * 这与"文档里本来就没有"**逐字相同**，没有任何现象指向原因。
     *
     * 所以这里必须能把"框里这份不是全部"这件事**传出去**，让那条路要么拒绝、
     * 要么把用户引到"用磁盘上那份"（那条路读的是完整文件）。
     *
     * [!] 它只在**回填**的那一处被写（[loadRagDoc] —— `.txt / .md` 导入那条路
     *     自 `0.9.198` 起改成直接落库、由 `loadRagDoc` 回读，见 [importRagTextFile]），
     *     且用户一开始编辑（`ragDocDirty`）就该清掉 —— 那时框里的内容是他自己敲的，
     *     不再是"某份文件的头部"。清理点与脏标记同一处，见 [ragDocEt] 的 TextWatcher。
     */
    private var ragDocClipped = false
    /**
     * 框里这份正文**被截之前**有多少字符（`0` = 没截，或还没回填过）。
     *
     * 为什么必须单独留一个数：[ragDocClipped] 只回答“这份是不是头部”，而**头部是
     * 谁的头部、原本多长**这两个数在框里**读不出来** —— 框里只有头部本身。
     * 而“全文共 N 字符”正是用户判断“我该把文档切成几份”的唯一依据
     * （[RagUiCap.MAX_DOC_CHARS] 是那个分母）。不存它，反馈里只能写“这一份太长”。
     *
     * [!] 与 [ragDocClipped] **同生同灭**（只在两处回填被写、被用户编辑清掉）：
     *     两处各写一次必然漂移，而漂移的形态是“框里明明是头部却说全文就这么长”。
     *     写点见 [setRagDocText] 的调用处与 [ragDocEt] 的 TextWatcher。
     */
    private var ragDocFullChars = 0
    /**
     * 正文框**是不是收起的**（见 [RagUiCap.DOC_COLLAPSED_LINES]）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 它修的是一件与内容无关的事：**往下滚的通行费**
     * ═══════════════════════════════════════════════════════════════════════
     * 框能装 [RagUiCap.MAX_DOC_CHARS]（4 万字符 ≈ 88 条、几百行）—— 而
     * `EditText` 是 `WRAP_CONTENT`：装多少就**占多高**，4 万字符在这一页上是
     * 十几屏。排在它下面的恰好是用户最常去的地方（[ragAppendRow] 的「追加」、
     * [ragRowsContainer] 的条目列表），于是每次都要先滑过那十几屏。
     * 用户报的原话：*"有的时候滑倒底部去追加或者按条查看，滑动太慢"*。
     *
     * [!] 收起**只改显示**（`maxLines`），一个字符都不动：框里的 `text` 是同一份，
     *     [ragDocEntries] / [doSaveTexts] / [doBuildIndex] 读到的还是全部内容。
     *     判据见 `run_rag_doc_fold_guard.sh` —— 它正面钉住"收起不许碰 text"。
     * [!] 它**不持久化**，也不跨会话继承：收起/展开是"这一屏现在要看什么"，
     *     不是内容的一部分。与 [ragRowsPage] 不持久化同一条理由
     *     （那一个是"现在在第几页"）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 冷启动默认 = **收起**（ISSUE #259 的用户拍板，此前是展开）
     * ═══════════════════════════════════════════════════════════════════════
     * 上一版按"内容看得见是默认状态"给了展开，而那句话是**错的取舍**：
     * 这一页的正文框是**编辑器**（要改的内容），不是**阅读器**（要看的内容）——
     * 而用户进这一页十次里有九次是为了别的事（点库、追加、看条目、建库）。
     * 展开态下框占十几屏，把「追加」与条目列表一路推到屏幕外，
     * 于是"打开知识库页"这个动作本身先付一次十几屏的滑动。
     * 用户报的原文：*"文字预览的窗口默认设为折叠，用户需要时自己打开"*。
     *
     * ⚠ 收起**不等于**内容不见了 —— 框仍占 [RagUiCap.DOC_COLLAPSED_LINES] 行
     *   （看得见里面有东西），按钮上还报着字符数，下面那行说明写着"内容没有被改动"。
     *   这三样是默认收起这个决定**能被接受**的前提：只把框藏起来而不出声，
     *   与"这份内容没了"长得一样（见 [paintRagDocFold]）。
     *   ⚠ 但它**不是** [RagUiCap.docCollapsedNote] 的唯一来源 —— 那一行只在框里
     *   **有内容**时才出现（空框不必解释"里面有什么"），所以默认收起的那一屏
     *   通常是"矮框 + 带字符数的按钮"，而不是"矮框 + 两行说明"。
     */
    private var ragDocCollapsed = true
    /** 正文框那个「收起 / 展开」按钮（文案随状态与内容量变化，见 [paintRagDocFold]）。 */
    private lateinit var ragDocFoldBtn: Button
    /** 收起态下正文框位置上的那一行说明（展开时 GONE，见 [RagUiCap.docCollapsedNote]）。 */
    private lateinit var ragDocFoldNoteTv: TextView
    /**
     * 只读预览态下、正文框**上面**那一行说明（见 [RagUiCap.docEditableNote]）。
     *
     * 为什么必须在框**上面**：这一行是"这个框为什么敲不进去 + 要改去哪儿改"，
     * 而框在只读态下可能很短（一页几条时）也可能很长（一页 60 条时）——
     * 挂在下面的话，它在长框的那一屏上要滑过整段正文才看得见，
     * 而看不见的那一次用户就会去猜（猜的结果是"这功能坏了"）。
     */
    private lateinit var ragDocPreviewNoteTv: TextView
    /**
     * 条目列表**当前是第几页**（1 起数）。见 [RagUiCap.rowPageCount]。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 它为什么必须存在（而不是继续用"一屏铺 300 行"）
     * ═══════════════════════════════════════════════════════════════════════
     * 在分页进来之前，"铺多少"**就是**"能看到多少"——于是 [RagUiCap.MAX_ROWS]
     * 这一个数同时背着两个互相矛盾的诉求：小一点才不卡，大一点才找得到。
     * 用户报的"窗口太小，滑动找 300 条、40 万字，也是一场灾难"正是这个矛盾，
     * 而它**没有解**：300 → 60 只是把"卡且难找"换成"不卡但更找不到"。
     *
     * 分页把这个耦合拆开之后，"降上限"这个动作才第一次成立：
     *   · 铺多少 = [RagUiCap.MAX_ROWS]（60 行 = 180 个 View，性能闸门）；
     *   · 能看到多少 = 页数覆盖全部条目（可达性）。
     *
     * [!] 翻页**只改这一个数然后重画**，不重新读盘：[ragRowsShown] 里已经是
     *     这个库的**全部**条目（`refreshRagRows` 读一次就全在内存里），
     *     翻页只是换一段 `subList`。所以"翻页"在几千条的库上和几十条一样快
     *     —— 如果为了翻页再读一次文件，这个功能会比它要解决的问题更慢。
     *
     * [!] 它**不持久化**、也不跨库继承：换库时归 1（见 [refreshRagRows]）。
     *     跨库继承页号会让"点一个新库"落到一个空的第 88 页上 —— 而那一屏
     *     看起来与"这个库是空的"逐字相同。
     */
    private var ragRowsPage = 1
    /**
     * **已新建、但磁盘上还没有任何东西**的那个库名（空串 = 没有这样的库）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 它存在是为了让"新建一个库"这个动作**先在界面上成立**（ISSUE #259）
     * ═══════════════════════════════════════════════════════════════════════
     * 用户的原话：*"新建库后应该在库列表里有体现，马上生成一个选项，如果本次未添加
     * 内容，那么默认丢弃，如果执行了手动输入（粘贴），或者文本倒入，或者库导入，
     * 那么库应该立即保存"*。
     *
     * 在此之前，"新建一个库"这件事**没有自己的时态**：库列表读的是磁盘
     *（`RagIndexStore.list` 按目录列），于是"我刚新建的库"在屏幕上不存在 ——
     * 用户点「新建库」之后看到的是一个**什么都没变**的页面（列表里没有它、
     * 正文框里还留着上一个库的内容），唯一能确认"我点了"的证据是那句 Toast。
     * 而那句 Toast 与"界面没反应"合起来的读法是"这个按钮坏了"。
     *
     * [!] 它**只是一个名字**，不代表磁盘上有任何东西 —— 那正是它与
     *     `RagIndexStore.dirOf` 的区别，也是"本次未添加内容就丢弃"能成立的原因：
     *     丢弃它不需要删任何文件（本来就没有），只是把这个字符串清空。
     *     ⚠ 反过来说，**不许**为了"让它在列表里"去建一个空目录：
     *     空目录会被 `list()` 判成一个"库"（那里只要目录里有东西才算，但
     *     `dirOf` 的 `mkdirs` 一建就是空目录），于是"丢弃"就变成了"留下一个
     *     点不动、删不掉的幽灵行"（本仓库对"看起来能用但用不了"的判据一向是红的）。
     *
     * [!] 它的生命周期只有三条出口：
     *   · 有内容落盘（粘贴 / 导入文档 / 导入库）-> 由 [clearPendingKb] 清掉，
     *     紧接着 [refreshRagList] 会让它作为**真实库**出现在列表里（无感衔接）；
     *   · 切换到别的库 -> 由 [clearPendingKb] 清掉 = **丢弃**（用户要的默认行为）；
     *   · 名非法 / 已存在 -> 不进这个状态（[newRagKb] 里当场拒绝并说明）。
     */
    private var ragPendingKb = ""
    /**
     * **本次导入的目标库名**（空串 = 没有正在进行的导入）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么必须冻结，而不能在导入落地时现读其它几个信号
     * ═══════════════════════════════════════════════════════════════════════
     * 用户报的现场（ISSUE #259）：*"打开 app，点击已存在的库 test，然后在库名编辑处
     * 修改库名为 test1，点击确定，库状态显示新建了库 test1，改名不生效"*。
     *
     * 链子是这样断的：
     *   ① 「导入文档」那一下子**会弹系统文件选择器**（`startActivityForResult`）——
     *      那是**另一个 App**；本页的 [ragKbEt] 会在它出现的过程中**失去焦点**；
     *   ② 而库名框的失焦处理器正是 [commitRagKbName]（"失焦即保存"）。它把
     *      `currentKb` 覆盖成**输入框里的字面值**，并且因为 `ragDocOwner`
     *      此刻是空串而走进了"打开新库"那一支 —— 于是正文框被清空、
     *      `ragPendingKb` 被设成那个名字；
     *   ③ 用户从文件选择器回来（`onActivityResult`），内容落盘时读的是
     *      "当前选中的库"，而**它刚刚被自己覆盖过**。
     *
     * 三个信号（输入框的字面值 / `currentKb` / 归属）在那几百毫秒里说的是三件事，
     * 而落盘只认其中一个 —— 症状就是"库状态显示新建了库，改名不生效"。
     *
     * [!] 它在**弹选择器之前**取一次快照，落地时**只读它**。
     *     不读输入框（用户可能在选择器里改了别的库名）、不读 `currentKb`
     *     （它会被失焦那条路覆盖）—— 这个快照是"用户在按「导入库」那一刻
     *     心里想的那个库"的唯一真值。
     * [!] 它**不持久化**：一次导入结束（成功或失败）就清掉。留着的话，
     *     下一次与它无关的落盘会拿到一个过期的目标库名 ——
     *     而"写进了上一次那个库"在屏幕上没有任何现象。
     */
    private var ragImportTarget = ""
    /** 正在进行的导入来自哪条路（`.txt / .md` 与库文件在确认框里的口径不同）。 */
    private var ragImportFromText = false
    /** 「新建库」按钮（它做的事见 [newRagKb]）。 */
    private lateinit var ragNewKbBtn: Button
    /**
     * 新建库"一有内容就落盘"的**代际**（见 [maybeSchedulePendingAutosave]）。
     *
     * 为什么是代际而不是 `removeCallbacks`：用户连续打字时每一次都要把上一次的
     * 落盘计划作废，而"作废"必须包含**已经跑在后台线程里的那一次** ——
     * `removeCallbacks` 只挡得住还没跑的 Runnable，挡不住已经进 `ioExecutor` 的。
     * 于是那个慢的落盘会在后面追上来，把**更晚**的那份内容写成磁盘上的版本
     *（而屏幕上框里是最新的那份）—— 这类"写回的比看到的老"没有任何现象。
     * 一个自增的整数在落地前复核一次，就把这件事收干净。
     */
    private var ragPendingSaveGen = 0
    private lateinit var rootScroll: ScrollView
    private lateinit var logTrimTv: TextView
    /**
     * 上一次把**进度回执**画到屏幕上的时刻（毫秒）。见 [PROGRESS_MS]。
     *
     * 为什么按时间节流而不是按条数：进度回执的数量由**块数**决定（一份 3 MB 的
     * 整篇切出上万块），而"多少块算多"没有稳定的答案 —— 条数节流在"块很大、
     * 每块都很慢"的那一半上完全失效（次数不多，但每次都撞在最贵的一帧上）。
     * 时间节流与"人眼能看出在动"的尺度直接对齐，与块数无关。
     */
    private var lastProgressAt = 0L
    /**
     * 上一条进度回执的**正文**（前缀之后那一段，见 [RagProgress.line]）。
     *
     * 为什么要存这一份：[PROGRESS_PREFIX] 那一行会被**两个地方**写 ——
     * [updateProgressLine]（每次回执）与 `selectTab`（切回知识库页时）。
     * 切页那一跳如果只写一句静态的"（进度见日志）"，用户手里唯一的
     * "它还在走"的证据就被擦掉了 —— 而切页回来的用户正是回来找这个答案的
     * （ISSUE #259 第 ④ 条，用户原话："建索引提示丢失"）。
     * 存一份最后读数，切页时原样带出来，下一条回执（≤250 ms）再刷新它。
     *
     * ⚠ 存的是**正文**而不是整行：拼接（前缀 + 提醒语）只允许有一处写法，
     *   [PROGRESS_PREFIX] 在文本里出现几次由守卫钉住。
     */
    private var lastProgressBody = ""
    /**
     * 日志页顶部的**常驻状态带**：直说当前处于哪一档日志模式。
     *
     * 为什么必须有：用户开了崩溃取证模式后回日志页，最直接的困惑是"日志怎么少了/怎么全是骨架"
     * —— 而根因是取证模式改变了日志的落点与叙述方式。这条状态带是那次困惑的**直接解药**：
     * 第一眼就知道"难怪不对"，而不必去翻设置页里那个开关还在不在。
     */
    private lateinit var logModeTv: TextView
    /** 日志页过滤条上的 chip（计时 / 内存 / HTP / 仅错误），下标与 [LogFileStore.LogFilter.labels] 一一对应。 */
    private val logFilterChips = ArrayList<TextView>()
    /**
     * 当前生效的标签档位（chip 文案的集合）。只在这里保存一份，不读控件文案 —— 控件会被重建。
     *
     * **空集合 = 不按标签筛**（不是"全不放行"）：界面上就是一个 chip 都没选。
     * 这里是"哪些档选中"的唯一判据，chip 的选中态由它画出来（见 [paintLogFilterChips]）。
     */
    private val logFilterKeys = mutableSetOf<String>()
    /** 关键字搜索框。只用于取值与回填，**判据不读它**（判据只有一个写点：applyLogFilter）。 */
    private lateinit var keywordEt: EditText
    // ---- tab 吸顶 + 四页左右滑动 + 设置页/知识库页分组折叠 ----
    private var curTab = 1
    private val pageScrollY = IntArray(4)
    /** 日志页「跟随新行」开关：切到日志页时置位，用户一上滑即清除（与聊天页 autoFollowChat 同义）。 */
    private var logFollowBottom = true
    private var logLastTotal = -1
    private var logLastTrimmed = -1
    /** 上一次写给 [logTrimTv] 的整句说明（截断 + 过滤的合成结果），避免每帧重排这一行。 */
    private var logLastNote = ""
    /**
     * 上一次写给 [logTv] 的正文全文快照（过滤态下判"内容变了没"，见 [renderLog]）。
     *
     * 过滤态下不每帧赋值（赋值会清掉用户的文本选择），但也**不能**只在切页时赋值 ——
     * 那会让正文停在旧快照、而说明行的命中数一路涨，页面说自相矛盾的话。
     * 所以判据落在"内容变了没"上，初值 `null` 保证冷启动那一帧必写。
     */
    private var logLastBody: String? = null
    /**
     * 本帧是否为"刚切到日志页 / 刚换过滤档"（见 [renderLog] 的取值分流）。
     *
     * 只在 [renderLog] 的一次调用内有意义：置位 → 渲染 → 复位，三步在同一个方法里成对出现。
     * 之所以要它，是为了把「内容变了要重画」与「只是又来了几行新日志」这两种调用分开 ——
     * 后者在过滤档位下不该重画（重画会清掉用户的文本选择）。
     */
    private var justSwitchedTab = false

    private var selectTabFn: ((Int) -> Unit)? = null

    /**
     * 打在控件上的「面板外」标记：collapsify 收集分组内容时遇到它即停手，使该控件及其后
     * 内容留在页面顶层，不随任何分组折叠而隐藏。用引用比较，避免与其它用途的 tag 冲突
     * （NPU 配额 RadioButton 的 tag 是 Int）。
     */
    private val UNGROUPED = Any()
    // (点5): 聊天页输入区移出滚动区常驻底部，输出在上方自行向下刷新
    private lateinit var chatBar: LinearLayout
    /** 输入条与上方滚动区之间的细分隔线（只在聊天页可见，与 chatBar 同步显隐）。 */
    private var chatBarDivider: View? = null
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
    /**
     * 上一次画状态行 / 列表时**驻留**的是哪个文件（`LlmEngine.loadedPath` 的快照）。
     *
     * 用途只有一个：让轮询能发现"驻留值在别处变了"。`停止服务` 那条路径
     * （`InferenceService.ACTION_STOP`）会在**服务侧**卸载模型，本页任何按钮回调
     * 都不会被执行到 —— 没有这个字段时，服务都把模型卸掉了，这一屏还写着"已加载"。
     *
     * ⚠ 必须是 `String`（未加载 = 空串）而不是 `String?`：`null` 要留给"从未取过"，
     * 否则首帧就会把"本来就没加载"误判成一次变化，白画一次；
     * 更要紧的是反方向 —— 用 `loadedPath?.let{}` 那种写法时，**卸载**（值变 null）
     * 永远不会触发重画，而那恰好是这条判据存在的理由。
     */
    private var shownLoadedPath: String? = null

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun field(hint: String, text: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(text)
        textSize = 13f
        // 控件默认前景色：`EditText` 的字色在我们显式设色之前由**主题**给，
        // 而 `setTheme()` 换的那一套只保证"标题栏/对话框"那一层 —— 输入框
        // 自己那一份在部分 ROM 上仍是亮色系。这里显式给一次，暗色下才不会
        // 出现"输入的字看不见"（输入框里本来就可能是空的，看不见也不会有人报）。
        setTextColor(pal.onSurface)
        setSingleLine(true)
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

    /**
     * 给聊天页面挂**左右滑动翻页**：左右是手势（scrollTo 永远不会改变 x），
     * 所以纵向滚动与翻页互不干扰 —— 不需要额外判方向、也不需要动 rootScroll。
     *
     * [!] 这是**唯一**的翻页手势处理。此前还有一个挂在 dispatchTouchEvent 上的
     * GestureDetector.onFling（旧实现）：同一滑动手势两个入口都会调
     * [selectTabFn]，于是**一次滑动翻两页**。而两者的触发条件不同（fling 看速度、
     * 这里看位移），快滑时两个都中、慢滑时只中一个 —— 表现就是"往前一页页走、
     * 往回却从第三页直接跳回第一页"，看着像方向有关的怪 bug，其实只是重复处理。
     * 两个入口删掉一个（这里保留），一次滑动就只翻一页。
     *
     * 为什么不用 ViewPager：本页是整棵 LinearLayout 手工搭起来的，换 ViewPager
     * 要重构三层布局；而我们要的只是"切一个页下标"，20 行够了。
     * 副作用也要说清：左右滑动落在**输入框**上时会被 EditText 自己吃掉
     * （文本框内要能左右移光标）—— 这是对的，不是漏了。
     */
    private fun attachSwipePager(w: View, total: Int) {
        var downX = 0f
        var downY = 0f
        var moved = false
        w.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; moved = false }
                MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(ev.x - downX) > px(48)) moved = true }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    // 三项都要：位移够大（不是手抖）、横向为主（不抢纵向滚动）、且未被打断
                    if (moved && kotlin.math.abs(dx) > px(80) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        val next = curTab + (if (dx < 0) 1 else -1)
                        val fn = selectTabFn
                        if (fn != null && next in 0 until total) { fn(next); return@setOnTouchListener true }
                    }
                }
            }
            false   // 不消费：纵向滚动、点击、长按照旧交给真正的控件
        }
    }

    private fun paintSection(head: TextView, title: String, open: Boolean) {
        head.text = (if (open) "▼ " else "▶ ") + title +
            (if (open) "" else "  点击展开")
    }

    private fun setSections(sections: List<Triple<TextView, LinearLayout, String>>, open: Boolean) {
        val pref = getSharedPreferences("ui_prefs", MODE_PRIVATE)
        for ((head, body, title) in sections) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            pref.edit().putBoolean("sec:" + title, open).apply()
            paintSection(head, title, open)
        }
    }

    /**
     * 以「── 标题 ──」分隔 label 为组头，把其后到下一组头之前的控件收进可折叠
     * 容器。纯后处理重排，不改动任何现有 addView 顺序；展开状态写 prefs，下次进入沿用。
     */
    private fun collapsify(page: LinearLayout, openByDefault: Set<String>): List<Triple<TextView, LinearLayout, String>> {
        val mine = ArrayList<Triple<TextView, LinearLayout, String>>()
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
                    head.setTextColor(pal.link)
                    head.setPadding(0, px(12), 0, px(2))
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    paintSection(head, title, open)
                    head.setOnClickListener {
                        val now = body.visibility != View.VISIBLE
                        body.visibility = if (now) View.VISIBLE else View.GONE
                        pref.edit().putBoolean(key, now).apply()
                        paintSection(head, title, now)
                    }
                    mine.add(Triple(head, body, title))
                    idx += 2; continue
                }
            }
            idx++
        }
        return mine
    }

    /**
     * 在 `page` 的分组头之前插一条「收起/展开全部分组」。
     *
     * `sections` 必须是 [collapsify] 对**这一页**返回的那一份，不能共用一个全局列表：
     * 四页 Tab 下有设置页与知识库页两套分组，混在一起会让知识库页那条按钮去折叠
     * 设置页的组（按了没反应/错页反应），而且全局列表的 `first()` 往往是**别的页**
     * 的第一个组，在本页 `indexOf` 恒为 -1，按钮会被安到错误的插入位。
     */
    private fun addSectionToggleBar(page: LinearLayout,
                                    sections: List<Triple<TextView, LinearLayout, String>>) {
        if (sections.isEmpty()) return
        val bar = Button(this).apply {
            textSize = 11f; setBackgroundColor(0x00000000); setTextColor(pal.link)
            text = "收起全部分组"
            setOnClickListener {
                val anyOpen = sections.any { it.second.visibility == View.VISIBLE }
                setSections(sections, !anyOpen)
                text = if (anyOpen) "展开全部分组" else "收起全部分组"
            }
        }
        bar.text = if (sections.any { it.second.visibility == View.VISIBLE })
            "收起全部分组" else "展开全部分组"
        val at = page.indexOfChild(sections.first().first)
        page.addView(bar, if (at >= 0) at else 1, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setPadding(0, px(8), 0, px(2))
        // 默认字色在这里给一次：`label()` 是设置页/知识库页说明行的**唯一出口**
        // （全文件十几处），所以暗色下"说明文字还亮着"这整类失效在结构上
        // 不可能出现 —— 除非有人绕过它自己 new 一个 TextView（那正是守卫要钉的）。
        setTextColor(pal.textSecondary)
    }

    private fun btn(s: String) = Button(this).apply { text = s; textSize = 13f }

    /**
     * 把一行控件放进**横向可滚动**容器（日志页的过滤 chip 用）。
     *
     * 为什么不是 `LinearLayout.HORIZONTAL` 直接加进页面：五个 chip 在窄屏上要么被裁掉、
     * 要么被挤到第二行；而被裁掉的那个在"要看全部档位"的一排里是最坏的结果 ——
     * 用户不会想到去横滑，只会以为"这功能只有三个档"。横向滚动条自身不显示
     * （滚动条会把这一行撑高，而它下面就是日志正文）。
     */
    private fun newHorizontalScroll(row: LinearLayout): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, px(8), 0, 0)
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /**
     * 导出文件名带上**会话 id**（日志文件自身的时间戳），而不只记导出时刻：
     * 同一会话可能导出多份文件，只靠导出时刻无法区分各自属于哪一次现场。
     */
    private fun exportName(prefix: String, sid: String? = null, ext: String = "txt"): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val idPart = sid?.takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""
        return "%s%s-%s.%s".format(prefix, idPart, stamp, ext)
    }

    /**
     * 把当前配色压到**页面外壳**上（根滚动区 + 底部 tab 行 + 输入条那条分隔线）。
     *
     * 为什么单独抽一个方法、而且在 `setContentView` **之前**调一次、在这三个
     * 控件建好之后**再**调一次：
     * `rootScroll` / `chatBarDivider` 是 `onCreate` 里就地 new 出来的局部量
     * （`outer` 更是），而 tab 行与根滚动区要在第一帧就是暗的 —— 等
     * "onResume 再刷一遍"会让暗色用户每次进 App 先闪一下白底（那就是本仓库
     * 一贯在防的"看着像没生效"）。派生自 [pal]，所以调两次的结果必然一致；
     * 唯一要保证的是**这两次都在**（守卫钉的是这个调用关系，不是"赋值出现过"）。
     */
    private fun applyThemeChrome() {
        // 根滚动区：整屏的底色。不刷它的话，任何一页的空白处都还是亮的。
        rootScroll.setBackgroundColor(pal.background)
        // 底部 tab 行：它是吸底的，暗色下与页面底色差一档才看得出是"另一层"。
        tabRow.setBackgroundColor(pal.surfaceAlt)
        // 输入条本体 + 它上方那条 1dp 分隔线（暗色下浅色线会变成一道刺眼的白线）。
        chatBar.setBackgroundColor(pal.surface)
        chatBarDivider?.setBackgroundColor(pal.divider)
    }

    /** 一行参数名标签，与下方 gridRow 的输入框按同权重对齐。 */
    private fun labRow(vararg names: String): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in names) r.addView(TextView(this).apply {
            text = n; textSize = 10f; setTextColor(pal.hint)
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

    /**
     * 暗色模式三档的单选组。
     *
     * ⚠ 保留引用**只为**「还原默认设置」要把它回勾到默认档 —— 同 [repackGroup]。
     *   没有这个引用时，"还原了默认"这句话在界面上看不见（用户得自己去数
     *   哪个圆点是亮的），而本项默认是**关闭**，选过「打开」的人正好是
     *   最需要看见这一跳的人。
     */
    private var darkModeGroup: android.widget.RadioGroup? = null

    /**
     * 默认那一档（= [ThemePref.DEFAULT]，关闭）对应的单选按钮 id。
     *
     * 与 [repackDefaultId] 同一用途，但**不是**同一件事：repack 的"默认"是
     * "没设过（跟随库默认）"，本项的默认是一个**具体的档位**（关闭）。
     * 混用同一个 id 会让"还原默认"回勾到别人的档上，而两个单选组的 id 都是
     * `View.generateViewId()` 现场生成的，勾错也不会崩、只是显示与真相不符。
     */
    private var darkModeDefaultId = View.NO_ID

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

    /**
     * 「暗色模式」的三档单选组：关闭 / 打开 / 跟随系统。
     *
     * 需求原文是「默认关闭，可选跟随系统」——[ThemePref.MODES] 就是这三档的
     * 唯一来源，文案由 [ThemePref.label] 给出（守卫逐档对账，防止某一档被
     * 悄悄改名或漏铺 —— 漏一档的失效是"某一档选了没反应"，而那在界面上
     * 完全看不出来）。
     *
     * ⚠ 与 [repackRadioGroup] 同一套写法（`View.generateViewId()` + 加完再
     *   `check()` + `MATCH_PARENT`）：
     *   · 预置 `isChecked` 会让 `checkedId` 与显示脱钩（RadioGroup 在
     *     `onChildViewAdded` 里记账），所以先记 id、加完再 `check()`；
     *   · 这里**没有** `lp(0, 1)` 那种横向参数 —— 纵向组里 weight 分的是**高度**，
     *     三个 RadioButton 会被拉成三大片空白（模块 M5 那个真机现场）。
     */
    private fun darkModeRadioGroup(): android.widget.RadioGroup {
        val g = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val cur = ThemePref.get(this)
        var initId = View.NO_ID
        ThemePref.MODES.forEach { mode ->
            val rb = android.widget.RadioButton(this).apply {
                text = ThemePref.label(mode); textSize = 13f
                // tag 存档位值：点击回调**不从下标反推**档位（下标与档位的对应
                // 关系一旦有人调整 MODES 顺序就会静默错位，表现是"点打开成了跟随系统"）。
                tag = mode
                setTextColor(pal.textPrimary)
                id = View.generateViewId()
                if (mode == cur) initId = id
                // 默认那一档的 id 单独记下来（「还原默认设置」要回勾它）
                if (mode == ThemePref.DEFAULT) darkModeDefaultId = id
            }
            g.addView(rb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (initId != View.NO_ID) g.check(initId)
        g.setOnCheckedChangeListener { grp, id ->
            val mode = grp.findViewById<android.widget.RadioButton>(id)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            if (mode == ThemePref.get(this)) return@setOnCheckedChangeListener
            ThemePref.set(this, mode)
            // 提示必须说清**代价**（本仓库对提示的一贯口径）：
            // 换了档位之后当前这一屏**不会**立刻变 —— 因为我们把"这一屏该用什么色"
            // 定在了 onCreate 那一刻（`pal` 是 `lateinit val` 语义），
            // 半途换色会留下"一半暗一半亮"的中间态。所以这里如实说出重启。
            val sysNow = if (ThemePref.followsSystem(mode)) "（系统当前是" +
                (if (ThemePref.systemDark(this)) "深色" else "浅色") + "）" else ""
            Toast.makeText(this,
                if (ThemePref.isDark(mode, ThemePref.systemDark(this)) == (pal === DARK_PALETTE))
                    "已存下这一档$sysNow；下次打开应用时生效"
                else "已存下这一档$sysNow；重启应用后生效（本屏不会中途换色）",
                Toast.LENGTH_LONG).show()
            // 落一条日志：这是"我明明选过、它却说没设过"那类问题的唯一凭据面
            // （同 [repack] 那条）。走 uiLog 而不是 println —— 它进的是日志页
            // 与「导出崩溃探针」那一份，用户取得到。
            LlmEngine.uiLog("[主题] 暗色模式档位已改为 " + ThemePref.label(mode) +
                "（存进 ThemePref.KEY；重启应用后生效）")
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
        // ⚠ `setTheme()` **必须**排在 `super.onCreate()` 之前：Activity 的装饰
        //   （窗口背景、系统控件的默认 style）是在 super 那一跳里按当前主题建好的，
        //   放到之后调用只会改到"还没建的下一批视图" —— 表现就是"切了暗色，
        //   对话框暗了、页面闪一下白再变暗"（本仓库同族的失效：看着做了、其实晚了一步）。
        //
        // 档位与"系统现在是不是深夜"都**只读一次**，落进 [pal]；本文件里所有
        // 取色点一律从 [pal] 取，不再有第二处 `if (dark)`。
        // 需求原文是「默认关闭，可选跟随系统」，三档的判定在 [ThemePref.isDark]
        // （纯逻辑，宿主单测直接跑那一份）。
        val mode = ThemePref.get(this)
        val dark = ThemePref.themeDark(mode, ThemePref.systemDark(this))
        pal = if (dark) DARK_PALETTE else LIGHT_PALETTE
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar
                 else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        rootScroll = ScrollView(this)
        val pad = px(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        rootScroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // (需求4): 左右滑动翻页。挂在这一个节点上正是为了**跟手势走**：
        // 若挂在 setContentView 的 outer 上，它会盖住 rootScroll 与 tabRow，
        // 连按钮都点不动（onTouch 恒返回 false 也救不回来：外层的监听在父层，
        // 子树之外不会有事件）。
        attachSwipePager(rootScroll, 4)

        // ---- 四页 Tab（聊天 / 设置 / 知识库 / 日志）----
        // 为什么把「知识库」从设置页拆出来单开一页：知识库那一段（嵌入模型 + 库管理 +
        // 正文编辑 + 条目列表）本身就有七八个可折叠分组那么长，塞在设置页中间会把
        // 「加载与性能」「生成与采样」这些高频项挤到很远，翻起来像在爬。
        // 它不是设置的一个小节，而是**一整套独立的编辑流程**，值得有自己的页。
        // 位置定在「设置」与「日志」之间：左边是配置、右边是观察，知识库夹在中间符合
        // 「先配好、再看结果」的顺序（而不是把它甩到最后，那样反而更难点到）。
        fun tabBtn(t: String): Button = Button(this).apply {
            text = t; setBackgroundColor(0x00000000); setTextColor(pal.link)
        }
        fun makePage(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageChat = makePage(); pageSet = makePage(); pageRag = makePage(); pageLog = makePage()
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chatTabBtn = tabBtn("聊天"); val setTabBtn = tabBtn("设置")
        // 日志页页签名：「日志」→「调试」。改名理由：这一页现在装着崩溃取证开关、
        // 导出崩溃探针、纯 CPU 加载 —— 它们**都不是"日志"**，共同的判据是
        // "只在出问题的时候才有用"。叫「日志」会让用户以为这里只有滚动文本，
        // 找不到上面那几个开关；而叫「调试与运行」会撒谎 —— 服务启没启、模型跑没跑
        // 看的是设置页的「启动服务」和聊天页，这一页一个字都不回答"运行"。
        val ragTabBtn = tabBtn("知识库"); val logTabBtn = tabBtn("调试")
        fun selectTab(i: Int) {
            val on = pal.link; val off = pal.textMuted
            listOf(chatTabBtn, setTabBtn, ragTabBtn, logTabBtn).forEachIndexed { j, b ->
                b.setTextColor(if (j == i) on else off)
                b.setTypeface(b.typeface, if (j == i) Typeface.BOLD else Typeface.NORMAL)
            }
            pageScrollY[curTab] = rootScroll.scrollY       // 记住离开页的位置
            curTab = i
            pageChat.visibility = if (i == 0) View.VISIBLE else View.GONE
            pageSet.visibility = if (i == 1) View.VISIBLE else View.GONE
            pageRag.visibility = if (i == 2) View.VISIBLE else View.GONE
            pageLog.visibility = if (i == 3) View.VISIBLE else View.GONE
            if (i == 2 && ::ragStatusTv.isInitialized) {
                // 切到知识库页时刷一次状态行：它是"库名/开关存住没有"的常驻反馈面，
                // 而这几个值在别处（另一个预览页、其它控件）也可能被改过 —— 进来先对齐。
                //
                // ⚠⚠ 但**建索引进行中不许重画**（ISSUE #259 第 ④ 条）。
                //    用户报的原话：*"点击建索引，此时有建索引的相关提示，我切换页面后，
                //    再回到知识库页面，**建索引提示丢失**"*。
                //    原因就是这一跳：`renderRagStatus` 把状态行整段换成"这个库现在
                //    是什么状态"，而那条"正在建索引…（第 N/M 块）"的进度行是
                //    [updateProgressLine] 写上去的 —— 两处都写同一个 TextView，
                //    后写的赢。切一次页就把唯一那条"它还在干活"的证据擦掉了，
                //    而剩下的那行静止文字（"该库不存在…"）读起来像"它没在干活"。
                //
                //    正确做法是**让进度行留着**：它本来就是"我点了之后到底成没成"
                //    那个问句的答案，而切页回来的用户正是回来找这个答案的。
                //    （进度自己会走：`updateProgressLine` 每 ≥PROGRESS_MS 写一次，
                //      建完/失败后由 [startBuildIndex] 的回调重画。）
                if (ragBuilding) {
                    // 切页这一跳**必须保留最后一条读数**（而不是退回一句静态话）。
                    //
                    // 用户报的原话是"切换页面后建索引提示丢失"——那条提示的价值全在
                    // "**它还在走**"这个判断上，而一句静止的"（进度见日志；它还在跑）"
                    // 恰恰把这个判断交回到用户手里（他得去翻日志才知道到底动没动）。
                    // 有了 [lastProgressLine]，切回来看到的仍是那一行**带读数**的进度，
                    // 而且 250 ms 后就会被下一条回执刷新 —— 两处写同一个 TextView
                    // 的竞争（后写的赢）在这里不再产生"退化成一句静态话"的结果。
                    ragStatusTv.text = buildString {
                        append(PROGRESS_PREFIX)
                        append(lastProgressBody.ifEmpty {
                            "正在建索引/重建索引…（切换到本页前的进度见日志；它还在跑）"
                        })
                        append("　·　慢是正常的，请等它走完（不要切页/滑动）")
                    }
                    ragStatusTv.setTextColor(pal.warn)
                } else {
                    renderRagStatus()
                }
            }
            if (i == 3) {
                // 切到日志页 = 内容整段换掉，正文必须重写（过滤档位下的稳态渲染不写正文，
                // 见 renderLog 里关于"重排会清掉文本选择"的说明）。
                justSwitchedTab = true; renderLog(); justSwitchedTab = false
                logFollowBottom = true
            }
            if (::chatBar.isInitialized) chatBar.visibility = if (i == 0) View.VISIBLE else View.GONE
            // 分隔线与输入条同进同退：否则切到日志页时输入条没了、线还留着，
            // 看起来像页面上多了一条划痕。
            chatBarDivider?.visibility = if (i == 0) View.VISIBLE else View.GONE
            // 聊天页与日志页都默认贴底：日志页贴底后，新追加的行自然出现在窗口里，
            // 不需要用户手动往下滑（切走再切回时用上一页位置，所以这里强制贴底一次）。
            if (i == 0) { autoFollowChat = true; rootScroll.post { followChatBottom() } }
            else if (i == 3) { rootScroll.post { followLogBottom() } }
            else { val y = pageScrollY[i]; rootScroll.post { rootScroll.scrollTo(0, y) } }
        }
        chatTabBtn.setOnClickListener { selectTab(0) }
        setTabBtn.setOnClickListener { selectTab(1) }
        ragTabBtn.setOnClickListener { selectTab(2) }
        logTabBtn.setOnClickListener { selectTab(3) }
        selectTabFn = ::selectTab
        tabRow.setBackgroundColor(pal.surfaceAlt)
        tabRow.addView(chatTabBtn, lp(0, 1)); tabRow.addView(setTabBtn, lp(0, 1))
        tabRow.addView(ragTabBtn, lp(0, 1)); tabRow.addView(logTabBtn, lp(0, 1))
        // tabRow 改由外层 outer 持有（吸顶），不再进 rootScroll
        root.addView(pageChat, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pageSet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pageRag, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
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
            textSize = 11f; setTextColor(pal.textMuted)
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
            textSize = 11f; setTextColor(pal.textSecondary)
            setPadding(0, px(2), 0, px(2))
        }
        pageSet.addView(serverTv)
        // 探测结论常驻显示：服务连不上时用户要能反复看这份结论，
        // 而不是"结果一闪而过，只能靠 Toast 记"。
        probeResultTv = TextView(this).apply {
            textSize = 11f; setTextColor(pal.textSecondary)
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
        val unloadBtn = btn("卸载模型")
        unloadBtn.setOnClickListener { doUnload() }
        row0.addView(pickBtn, lp(0, 1)); row0.addView(loadBtn, lp(0, 1))
        row0.addView(unloadBtn, lp(0, 1))
        // 「纯CPU诊断」原本挤在这一排里，和"加载/卸载"这些真动作同构，看不出它是个
        // **排障入口**（它把 gpu layers 强制归零、ctx/线程也写死，是"怀疑 GPU 路径有问题时
        // 试一次"）。已搬到日志/调试页工具栏，改名「纯 CPU 加载」—— 那一页的工具都是
        // "出问题的时候才用"，与它的用途同属一类。
        pageSet.addView(row0)

        // ---- 模型库列表：点击选择，右侧删除 ----
        pageSet.addView(label("模型库（点击选择 · “删”移除并释放空间）"))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageSet.addView(listContainer)

        // ---- 知识库专页（自设置页拆出）----
        // 页首标题：四页共用一条 tabRow，但只有设置页有 backendTitleTv。知识库页
        // 单独给一行标题，用户切过来一眼知道"这是知识库，不是设置"。
        pageRag.addView(TextView(this).apply {
            text = "知识库（RAG）"
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        })
        pageRag.addView(label("嵌入模型 + 库管理 + 正文编辑 + 条目列表。左侧「设置」管模型与采样，" +
            "此处只管知识库。").apply {
            setTextColor(pal.textMuted); textSize = 11f
        })

        // ---- 嵌入模型（RAG）：与对话模型**各占一份**，同时驻留 ----
        //
        // 为什么不复用上面那个"加载模型"按钮：那个按钮永远把文件装进**对话**槽。
        // 用户按它的步骤去加载向量模型，看到的是"两个模型互相顶掉"——其实是
        // 两个文件先后装进同一个槽、后者顶掉前者。这不是 bug 而是缺口（当时
        // 根本没有"加载嵌入模型"这个入口）。分开列出来，是让这件事不再有歧义。
        pageRag.addView(label("── 嵌入模型（RAG 检索用，与对话模型同时驻留）──"))
        embedStatusTv = label("").apply { setTextColor(pal.textSecondary) }
        // 这里用 pageRag.addView 展开写、而不是复用下方那个局部函数 addFull：
        // addFull 的声明在本段之后，Kotlin 局部函数不提升（前向引用编译不过）。
        pageRag.addView(embedStatusTv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val embedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        embedLoadBtn = btn("加载嵌入模型")
        embedLoadBtn.setOnClickListener { doLoadEmbed() }
        embedUnloadBtn = btn("卸载嵌入模型")
        embedUnloadBtn.setOnClickListener { doUnloadEmbed() }
        embedRow.addView(embedLoadBtn, lp(0, 1))
        embedRow.addView(embedUnloadBtn, lp(0, 1))
        pageRag.addView(embedRow)
        embedListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageRag.addView(embedListContainer)

        // ---- 知识库（RAG 阶段 2）：库管理 + 检索开关 ----
        //
        // 为什么开关**默认勾上**、而远端默认不注入：这两个默认值看似矛盾，
        // 其实针对的是两种消费方 —— App 内聊天的人就在这一屏，他知道自己配了
        // 知识库，默认用是符合预期的；而远端 HTTP 是**无状态**的，服务端不知道
        // 调用方想不想要知识库，默认注入 = 用没要求过的内容污染回答（接口还 200）。
        // 所以远端由请求里的字段显式决定（阶段 3）。
        pageRag.addView(label("── 知识库（RAG 检索，App 内聊天生效）──"))
        ragStatusTv = label("").apply { setTextColor(pal.textSecondary) }
        pageRag.addView(ragStatusTv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ragToggleCb = android.widget.CheckBox(this).apply {
            text = "在 App 内聊天中使用知识库（默认开）"
            textSize = 13f
            isChecked = RagInject.enabled(this@EngineActivity)
            setOnCheckedChangeListener { _, v ->
                RagInject.setEnabled(this@EngineActivity, v)
                renderRagStatus()
            }
        }
        pageRag.addView(ragToggleCb)

        // ── 远端知识库（阶段 3）：**独立开关 + 独立库名**，默认关 ──
        //
        // 为什么与上面那个开关**分开两个控件、两个键**（而不是"一个开关管两边"）：
        // 两者针对的是两种消费方 —— App 内聊天的人就在这一屏，默认开符合预期；
        // 远端 HTTP 是**无状态**的，服务端不知道调用方想不想要，默认注入等于
        // 用调用方没要求过的内容污染回答（接口还 200，与幻觉同形）。
        //
        // 库名也必须独立：`currentKb` 是单一全局值，在 App 里点任何一条库都会改它。
        // 远端跟着它走就会出现"我在 App 里切到库 B 看了两眼，远端某个正在轮询的
        // 客户端**静默**换成了库 B" —— 这类"配置在背后被改了"的故障排查成本极高
        //（回答变差，却没有任何现象指向"库换了"）。所以远端库名只能在这一格上改。
        ragRemoteToggleCb = android.widget.CheckBox(this).apply {
            text = "允许远端（HTTP）调用使用知识库（默认关）"
            textSize = 13f
            isChecked = RagInject.remoteEnabled(this@EngineActivity)
            setOnCheckedChangeListener { _, v ->
                RagInject.setRemoteEnabled(this@EngineActivity, v)
                renderRagStatus()
            }
        }
        pageRag.addView(ragRemoteToggleCb)
        val ragRemoteKbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ragRemoteKbRow.addView(label("远端库名"))
        ragRemoteKbEt = field("远端调用的知识库名（留空 = 远端不注入）",
            RagInject.remoteKb(this@EngineActivity)).apply {
            // 失焦即保存：与库名、system 输入框同一套交互习惯。
            // **不做"跟随当前库"** —— 跟随看着方便，实际把上面那段说的静默切换
            // 又请回来了（"跟随"= 用一个会被别处改动的值当输入）。
            setOnFocusChangeListener { _, has -> if (!has) saveRagRemoteKbName() }
        }
        ragRemoteKbRow.addView(ragRemoteKbEt, lp(0, 1))
        // 「确定」按钮：与失焦落盘**并存**，不是二选一。
        // 失焦落盘是既有的交互习惯（system/库名同款），不能因为"用户看不见反馈"
        // 就把它撤掉 —— 撤掉会让"敲完直接点建库/启动服务"这类自然的连续操作丢字。
        // 缺的是**信号**：一个可点的「确定」既让"我怎么才算改好了"有答案（点它），
        // 又顺手给失焦那条路补一次提示（见 saveRagRemoteKbName 的成功 Toast）。
        ragRemoteKbRow.addView(btn("确定").apply {
            textSize = 12f
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { saveRagRemoteKbName(announce = true) }
        })
        pageRag.addView(ragRemoteKbRow)
        // 生效时机说明：这一行**常驻**，不是 Toast —— Toast 一闪而过，问"怎么没生效"
        // 的人正是在错过那一下之后才回头找的，那时屏上必须还有字。
        pageRag.addView(label("↑ 改完点「确定」或点空白处即保存；远端那次 HTTP 调用就会用上").apply {
            setTextColor(pal.textMuted)
            textSize = 11f
        })
        // ── 「每批编几条」（ISSUE #269 第 2 步：合批可见可调）──────────
        //
        // [!] 为什么这一格非要存在：合批落地后，批大小 8 写死在代码里 ——
        //     用户改不了、看不到，而它正是本 issue 里那个"线程数输入框对建库
        //     完全无效"的同一个病：参数在某处写死、或在某处断掉，界面上
        //     看不出来。"1 = 关合批"给了一个合法的对照点（逐条路径）。
        //
        // [!] 与库名 / 远端库名同一套交互（失焦即保存），另给一个「确定」
        //     让"我到底改好了没" 有答案（见 saveRagRemoteKbName 那段）。
        val ragBatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 默认值显示为**当前生效值**（默认 8）而非 0：输入框里显示 0 会让人
        // 以为"关了合批"，而 0 的真正含义是"没设过"（实际跑 8）。一个读数与真相
        // 相反的默认值，比没有这一格更坏 —— 所以这里把默认翻译成人话。
        ragBatchEt = field("每批编几条（1=关合批，上限 ${LlmEngine.kEmbedBatchRows}）",
            if (EmbedBatchPref.isSet(this)) EmbedBatchPref.get(this).toString()
            else LlmEngine.kEmbedBatchDefault.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setOnFocusChangeListener { _, has -> if (!has) saveRagBatchSize(announce = false) }
        }
        ragBatchRow.addView(label("合批"))
        ragBatchRow.addView(ragBatchEt, lp(0, 1))
        ragBatchRow.addView(btn("确定").apply {
            textSize = 12f
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { saveRagBatchSize(announce = true) }
        })
        pageRag.addView(ragBatchRow)
        // 下一行**常驻**（不是 Toast）：Toast 一闪而过，而问"怎么没生效"
        // 的人正是在错过那一下之后才回头找的 —— 与远端库名那行同一理由。
        pageRag.addView(label(
            "↑ 一次前向同时编几条：越大越快，但限于建 ctx 时的序列数（${LlmEngine.kEmbedBatchRows}），" +
            "超了会被钳到该值并记一行日志。改后**需重建索引**才能用上新步长；" +
            "1 = 关合批（逐条编，慢但可用来对比）。").apply {
            setTextColor(pal.textMuted)
            textSize = 11f
        })
        // ── 「嵌入每批上下文」（0.9.212：内存那一格看得见、改得动）──────────
        //
        // [!] 为什么这一格非要存在：用户在真机上撞到的是"合批 8→4→2 都闪退"
        //     （bge-large-zh-v1.5），根因是嵌入 ctx 的两道门（`n_batch` / `n_ubatch`）
        //     此前**一个都没显式设**，全是库默认 —— 而它们由**这一格**推出来。
        //     把它做成输入框有两个作用，缺一不可：
        //       ① "内存占用为啥高"有答案、有旋钮：KV / output / compute buffer
        //          三项都按这一格乘出来的 ctx 总量分配，而**批大小那格不影响内存**；
        //       ② 大块文本（> 每条的预算）被截断时有得调：调大它，每条的截断点跟着涨。
        //
        // [!] 与批大小同一套交互（失焦即保存 + 「确定」），且同样把默认翻译成人话
        //     —— 输入框里显示 0（"没设过"的真义）会让人以为"上下文是 0"。
        val ragCtxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ragEmbedCtxEt = field("嵌入每批上下文（每条文本，上限 ${LlmEngine.kEmbedCtxMax}）",
            if (EmbedCtxPref.isSet(this)) EmbedCtxPref.get(this).toString()
            else LlmEngine.kEmbedCtxDefault.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setOnFocusChangeListener { _, has -> if (!has) saveRagEmbedCtx(announce = false) }
        }
        ragCtxRow.addView(label("嵌入 ctx"))
        ragCtxRow.addView(ragEmbedCtxEt, lp(0, 1))
        ragCtxRow.addView(btn("确定").apply {
            textSize = 12f
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { saveRagEmbedCtx(announce = true) }
        })
        pageRag.addView(ragCtxRow)
        // [!] 这一行必须把**内存归因**写出来（用户原话：*"如果成功了再加一个内存
        //     占用为啥高的提示"*）。只写"越大越占内存"是不够的 —— 用户会把
        //     它读成"越慢越占"，于是去调**批大小**那格，而那格对内存毫无影响。
        //     所以这里点名三项：KV / output / compute buffer，且点明"改后要重新加载嵌入模型"。
        pageRag.addView(label(
            "↑ 每条文本的上下文预算（默认 ${LlmEngine.kEmbedCtxDefault}）：它乘" +
            "${LlmEngine.kEmbedBatchRows} 决定嵌入模型常驻内存（KV / output / compute buffer）" +
            " —— **内存高看这一格，批大小那格不影响内存**。改后需重新加载嵌入模型才生效。").apply {
            setTextColor(pal.textMuted)
            textSize = 11f
        })
        val ragRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // [!] 按钮名 = 它做的事（ISSUE #259 第 ② 条）。旧名"建库/重建"会被读成
        //     "从零建一个库"，而它实际做的是"给这个库算索引" —— 库（目录 + 正文）
        //     早就存在，这个动作只算向量。用户的原话：*"现在 app 内的建库/重建按钮，
        //     实际是新建库索引，应该改为建索引/重建索引"*。
        //     ⚠ 变量名 `ragBuildBtn` 与日志里的"建库"字样**保留**：它们描述的是
        //       `LlmEngine.buildIndex` 那条路（函数名如此），改名只到用户看得见的那一层。
        ragBuildBtn = btn("建索引/重建索引")
        ragBuildBtn.setOnClickListener { doBuildIndex() }
        // 「保存正文」与「建索引/重建索引」是两件事，刻意分成两个按钮：
        //   · 保存正文 = 把编辑**落盘**（几十毫秒），但**不重算向量** -> 索引随之失效，
        //     状态行会说明"正文已改：需重建"；
        //   · 建索引/重建索引 = 落盘 + 逐条重算向量（100 条约 5~30 秒）-> 恢复可检索。
        // 合成一个按钮的后果是"想保存一下"必须付一次重建，而重建期间这一屏是死的。
        //   ⚠ 两者都**不改库名、不换目录** —— 它们算的是同一个库的向量。
        //
        // [!] 它**不进 `ragRow`**（ISSUE #259 第 ③ 条）：那一行在页面**最上面**，
        //     而正文框在下面 —— 两者之间隔着库名行、导入导出那一片。用户的原话：
        //     *"保存知识库正文的按钮放到文本框上面，与文本框挨着"*。
        //     理由不是"好看"：保存正文改的是**框里那份内容**，所以它必须挨着框 ——
        //     隔着半屏找按钮时，人不会把"我改了框"与"顶上一个按钮"联起来，
        //     而这条路的后果是"改完没保存就切库/退出，改动没了"。
        //     它现在与「收起/展开」同一行、紧贴在正文框**上方**（见下面的挂载处）。
        ragSaveBtn = btn("保存正文")
        ragSaveBtn.setOnClickListener { doSaveTexts() }
        // [!] 名字 = 它做的事（ISSUE #259 第 ② 条）。上一版这里叫「删索引（连正文
        //     一起删）」—— 那是**把名字改去迁就错的行为**（底下 `drop` 确实连正文
        //     一起删了）。用户点出来：*"删索引为啥要删正文，是你按钮写错了还是
        //     逻辑写错了"*。**是逻辑写错了**，所以这一轮修的是 `drop`（只删索引、
        //     正文留着），名字跟着回到它本来的意思。
        //     ⚠ 名字里**不许**再出现"正文"两个字：出现就说明有人又把行为改回去了。
        ragDropBtn = btn("删索引")
        ragDropBtn.setOnClickListener { doDropIndex() }
        // ── 「删库」 ─────────────────────────────────────────────────────
        // 用户的原话：*"有了新建库的按钮和逻辑，应该有删库按钮"*。
        //
        // [!] 它**不进 `ragRow`**（那一行说的是"这个库的当前状态"里与索引有关的那两个，
        //     而"删库"是**终结**；挤在一起时"删库"会紧挨着状态按钮），
        //     也不并进 `ragIoRow`：那一行说的是"带出去 / 拿进来"（库的生命周期），
        //     而"删库"是**终结**。挤在一起时"删库"紧挨着"导入库"，
        //     而这两个动作的误触代价差着几个数量级（一个是不可撤销地丢内容）。
        ragDeleteBtn = btn("删库（连同索引与正文）")
        ragDeleteBtn.setOnClickListener { doDeleteKb() }
        // ⚠ 「保存正文」**不在这里** —— 它在正文框上方与框挨着（ISSUE #259 第 ③ 条，
        //   理由见它在下面的定义处）。这一行现在只剩"这个库的当前状态"里与**索引**
        //   有关的那两个（建索引、删索引），一行两个按钮，窄屏上也看得清字。
        ragRow.addView(ragBuildBtn, lp(0, 1))
        ragRow.addView(ragDropBtn, lp(0, 1))
        pageRag.addView(ragRow)
        pageRag.addView(ragDeleteBtn)
        // ── 导出 / 导入（正文层）──
        // 为什么单独一行而不是并进上面那行：上面两个按钮说的都是"这个库的当前状态"
        // （重建 / 删），而导出与导入说的是"把它带出去 / 拿进来"——
        // 是**库的生命周期**那一类，与当前状态无关（导出甚至在没索引时也该能用）。
        // 挤进同一行的另一个后果是四个按钮在窄屏上各自短到看不清字。
        val ragIoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ragExportBtn = btn("导出库")
        ragExportBtn.setOnClickListener { doExportRag() }
        ragImportBtn = btn("导入库")
        ragImportBtn.setOnClickListener { pickRagArchiveFile() }
        ragIoRow.addView(ragExportBtn, lp(0, 1))
        ragIoRow.addView(ragImportBtn, lp(0, 1))
        pageRag.addView(ragIoRow)
        // ── 「导入文档（.txt / .md）」单独一行，而且**刻意不并进上面那行** ──
        // 三件事必须同时成立，缺一个就会出现"按钮名与做的事不符"（这正是它被改名的原因）：
        //   ① 它读的是**一整篇连着写的文档**（笔记 / 说明书 / 会议记录），按行切开
        //      落库 —— 与「导入库」（读一份**已经是一条条条目**的库文件）不是同一个动作，
        //      所以它的名字里必须写清文件类型；
        //   ② 它服务于一个**具体的**痛点：Android 剪贴板装不下几百 KB 的笔记，
        //      而同一份笔记作为文件是完整的 —— 用户手里有文件，只是没路走；
        //   ③ 它**不碰**嵌入模型（落盘 ≠ 算向量），落盘之后仍要点「建库/重建」才能检索 ——
        //      所以它站在「建库/重建」那组按钮的语义旁边，而不是"带出去 / 拿进来"那组。
        // 不并进 ragIoRow 的另一个理由：四个按钮在窄屏上各自短到看不清字，
        // 而"导入库"与"导入文档"被看成同一个按钮，正是这一族误解的开头。
        //
        // ⚠ 名字里的"到正文框"这一版**已经去掉**（ISSUE #259）：它现在**直接落盘**，
        //   框只是落盘之后回读出来的窗口。名字里留着一个不再为真的去向，
        //   就是下一次"按钮名与做的事不符"。（见 [importRagTextFile]。）
        ragTextImportBtn = btn("导入文档（.txt / .md，按行落库）")
        ragTextImportBtn.setOnClickListener {
            // 编辑器里已有未保存的改动时先问：这一跳会**整段换掉**编辑器，
            // 与"切库"是同一件危险，所以走同一道闸 [confirmDiscardRagDoc]。
            confirmDiscardRagDoc("导入文件") { pickRagTextFile() }
        }
        pageRag.addView(ragTextImportBtn)
        pageRag.addView(label(
            "↑ 导出 = 带走向量以外的正文（换机/备份）；导入库 = 追加合并（.json/.jsonl）。\n" +
            ".txt / .md 是一整篇文字：一行一条直接写进新库，再把正文回读进下面的框给你核对" +
            " —— 剪贴板装不下的长笔记走这条路。\n" +
            "落盘不等于能检索：以上都要再点「建索引/重建索引」算向量。").apply {
            setTextColor(pal.textMuted)
            textSize = 11f
        })
        ragKbEt = field("知识库名（字母/数字/中文/-_/.）", RagInject.currentKb(this@EngineActivity)).apply {
            // 失焦即保存：与 system 输入框同一套交互习惯（生成时也会回写一次）。
            // ⚠ 失焦这一跳**也会切正文框**（见 [commitRagKbName]）—— 因为"库名框"
            //   与"正文框"必须始终说同一件事：只改名字而不换内容，就会出现
            //   "输入框里写着 B、框里装的是 A 的正文"，而用户下一步点「建库/重建」
            //   会**把 A 的内容建成 B 的库**（不崩、不报错、建库成功）。这正是
            //   用户报的"保留上一个库的内容误导用户"。
            setOnFocusChangeListener { _, has -> if (!has) commitRagKbName(announce = false) }
        }
        val ragKbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ragKbRow.addView(label("库名"))
        ragKbRow.addView(ragKbEt, lp(0, 1))
        // 与远端库名同款「确定」：给它一个看得见的落点，而不是只能"点到别处等它自己存"。
        ragKbRow.addView(btn("确定").apply {
            textSize = 12f
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { commitRagKbName(announce = true) }
        })
        // ── 「新建库」 ─────────────────────────────────────────────────────
        // 为什么必须有这个按钮（而不是继续靠"在库名框里打一个新名字"）：
        //   · 打名字**没有提交动作** —— 用户打完之后不会知道"它算不算已经建了"，
        //     而那时列表里也确实什么都没有（列表读磁盘，磁盘上还没这个库）；
        //   · 更重要的是"新建"与"改名选中"在旧交互里**长得完全一样**，
        //     而两者的后果相反：前者要清空正文框（那是一个新库）、后者要装载那个库的正文。
        // 一个显式的按钮把这件事变成用户**说了算**的动作，也给了"按钮名 == 做的事"
        // 这条底线一个落点（见 [newRagKb]）。
        ragNewKbBtn = btn("新建库").apply {
            textSize = 12f
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { newRagKb() }
        }
        ragKbRow.addView(ragNewKbBtn)
        pageRag.addView(ragKbRow)
        pageRag.addView(label("↑ 改完点「确定」或点空白处即切换/保存为当前库；" +
            "「新建库」= 用左边那个名字开一个新库（正文框会清空，有内容后自动落盘）").apply {
            setTextColor(pal.textMuted)
            textSize = 11f
        })
        // ⚠ 提示语按 P1-a 改口径：这个框在**已有的库**上是只读预览
        //   （只显示当前一页），只有**新建、还没落盘**的库才拿它当输入框。
        //   留一句"粘贴笔记/文档，一行一条"会让用户在预览态下对着一个敲不进去的框
        //   反复试 —— 而"这个框到底能不能敲"在两个状态下长得一样，只有这句提示能分。
        ragDocEt = field("正文预览（只读）／新建的库可在此粘贴，一行一条", "").apply {
            minLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // 编辑即置脏（只置位，不做任何 IO）：切库前要问"要不要先保存"，
            // 而那个判据必须来自用户真实敲过的内容，不是"我以为他没改"。
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // 程序化回填时也会触发本回调，靠 [ragDocLoading] 区分"用户敲的"与"我填的"
                    if (!ragDocLoading) {
                        ragDocDirty = true
                        // 用户一动手，框里这份就不再是"某份文件的头部"了 —— 它是
                        // 用户自己敲的内容，[ragDocClipped] 那条判据（"这不是全部"）
                        // 从这里起不再成立。不在这里清的话，用户手动补全了尾巴、
                        // 点建库时仍会被"框里不是全部"拦住 —— 一句假警告。
                        // [ragDocFullChars] 与它同生同灭：用户一动手，“全文共 N 字符”
                        // 这个数就不再有出处（框里现在是他敲的内容，长度就是它本身）。
                        ragDocClipped = false
                        ragDocFullChars = 0
                        // 旗标刚被清掉 —— 按钮上那句"只是前一部分"必须当场跟着消失，
                        // 否则用户手动补全了尾巴，按钮还在说"这只是前一部分"（假警告）。
                        paintRagDocFold()
                        // ── 新建库：一有内容就**立刻落盘**（ISSUE #259）──────────
                        // 用户的原话：*"如果本次未添加内容，那么默认丢弃，如果执行了
                        // 手动输入（粘贴），或者文本倒入，或者库导入，那么库应该立即保存"*。
                        //
                        // 为什么只在"新建的、还没落盘的库"上这么做：对**已有**的库，
                        // 边打边落盘会让 `texts.jsonl` 随时变、索引随时失同步
                        //（用户还在打字，"检索已停用"就一闪一闪），而那条路本来就有
                        // 明确的落盘动作（「保存正文」/「建库/重建」）。新库没有这个问题
                        //（它连索引都还没有），而它**必须**落盘才算"建成了" ——
                        // 不落盘的话，那个库名只活在内存里，用户一离开就没了。
                        maybeSchedulePendingAutosave()
                    }
                }
            })
        }
        // ── 正文框的「收起 / 展开」（与「保存正文」同一行，见下）──
        // 位置在框**上面**（不是下面），这是刻意的：展开时框高十几屏，
        // 放在下面的按钮要滑过整段正文才够得着 —— 而人只会在"准备往下走"之前
        // 收一次，那一刻他正在框的开头。收起之后框只剩几行，这一行按钮就贴在
        // 「追加」那行的上方，"收起来 -> 去追加"是一条连续的动作。
        //
        // 触区刻意做大（占满剩余宽度 + 独立按钮样式）：它是一个"开关"，不是"操作"，
        // 按错的代价是"什么都没发生"，而按不到会让人以为这功能不存在。
        ragDocFoldBtn = btn("").apply {
            textSize = 12f
            setBackgroundColor(0x00000000)
            setTextColor(pal.link)
            setOnClickListener { setRagDocCollapsed(!ragDocCollapsed) }
        }
        // ── 只读预览态的那一行说明（P1-a）────────────────────────────────
        // ⚠ 位置在「收起/展开」那一行**上面**、正文框更上面：它是"这个框为什么
        //   敲不进去 + 要改去哪儿改"，而框在只读态下可能很长 —— 挂在下面的话，
        //   长框那一屏要滑过整段正文才看得见，而看不见的那一次用户就会去猜
        //   （猜的结果是"这功能坏了"）。
        // ⚠ 非预览态（新建、还没落盘的库）时它 GONE：那时框**就是**输入框，
        //   说"这个框只读"是假话，而假警告会让真需要它的那一次也被无视。
        ragDocPreviewNoteTv = label("").apply {
            setTextColor(pal.warn)
            textSize = 11f
            visibility = View.GONE
        }
        pageRag.addView(ragDocPreviewNoteTv)
        // ── 这一行 = 「收起/展开」+「保存正文」，整体挂在正文框**上方** ──
        // 「保存正文」为什么在这里（ISSUE #259 第 ③ 条）：
        //   · 它改的是**框里那份内容**，所以必须与框挨着（隔着半屏找按钮时，
        //     人不会把"我改了框"与"顶上一个按钮"联起来，后果是"改完没保存
        //     就切库/退出，改动没了"）；
        //   · 与折叠按钮**同一行**而不是自己占一行：收起之后正文框只剩 3 行，
        //     两行按钮会把「追加」推下去 —— 而收起的目的正是让下面那些够得着。
        //   · ⚠ 它在框**上面**（与折叠按钮同一个理由）：展开态下框高十几屏，
        //     挂在下面要滑过整段正文才够得着。
        // ⚠ 次序（折叠在左、保存在右）是刻意的：折叠是高频的"看一下/收起来"，
        //   紧贴左边拇指区；保存在右边，误触的代价是"白存一次"（不丢东西）。
        val ragDocTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ragDocTopRow.addView(ragDocFoldBtn, lp(0, 1))
        ragSaveBtn.textSize = 12f
        ragSaveBtn.setPadding(px(10), 0, px(10), 0)
        ragDocTopRow.addView(ragSaveBtn)
        pageRag.addView(ragDocTopRow)
        // 收起态的说明行。展开时 `visibility` 为 GONE —— 展开时框自己就说明了一切，
        // 这一行只会是噪音（而噪音会让真需要它的那一次也被无视）。
        ragDocFoldNoteTv = label("").apply {
            setTextColor(pal.textMuted)
            textSize = 11f
            visibility = View.GONE
        }
        pageRag.addView(ragDocFoldNoteTv)
        pageRag.addView(ragDocEt, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 建完这一屏先画一次：冷启动是**收起**态（见 [ragDocCollapsed] 里那段），
        // 所以这里必须跟着画一次 —— 不画的话按钮文案与框高停在控件的初始值上，
        // 而初始值是"没折过"，与字段说的相反（两个信号打架，用户看到的那个说"展开"）。
        paintRagDocFold()
        ragListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageRag.addView(ragListContainer)
        // ── 追加一条 / 删除单条（阶段 3）──
        // 为什么单列一个输入框而不是"让用户在正文框里敲完再保存"：
        // 保存正文会让**整个索引**失效（正文与向量是一对，改了正文就得重算），
        // 而"补一条笔记"不该以"检索停用 + 几十秒重建"为代价。这里的追加只编码
        // **这一条**，其余向量原位保留 —— 两条路的成本差着两个数量级。
        val ragAppendRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ragNewEt = field("要追加的一条（会直接进索引，无需重建）", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        ragAppendRow.addView(ragNewEt, lp(0, 1))
        ragAppendBtn = btn("追加")
        ragAppendBtn.setOnClickListener { doAppendEntry() }
        ragAppendRow.addView(ragAppendBtn)
        pageRag.addView(ragAppendRow)
        ragRowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageRag.addView(ragRowsContainer)

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
        authHintTv = label(authHintText()).apply { setTextColor(pal.warn) }
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
        corsHintTv = label(corsHintText()).apply { setTextColor(pal.warn) }
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
        // 默认值按**本机核数**给，不再写死 4：写死的默认值只能对一种机器正确
        //（4 核机上刚好、8 核旗舰上浪费一半、6 核中端机上也不对）。
        // 见 LlmEngine.defaultThreads() 里"为什么取 availableProcessors 而不是只挑性能核"。
        thrEt = paramField("threads", LlmEngine.defaultThreads().toString())
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
            setTextColor(pal.warn); visibility = View.GONE
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
                quotaHint.setTextColor(pal.textMuted)
                quotaHint.text = "权重在各设备间的分配由引擎自动决定。"
            } else {
                quotaHint.setTextColor(pal.warn)
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

        htpHintTv = label("").apply { setTextColor(pal.textSecondary) }
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
            setTextColor(pal.warn)
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
            setTextColor(pal.textSecondary)
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
            setTextColor(pal.textSecondary)
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

        // ---- 崩溃取证模式的开关**已搬到日志/调试页页首**（见 pageLog 的挂载处注释）----
        // 它是"排障工具"而不是"设置项"：改的是日志的落点与叙述方式。留在设置页时
        // 它离用户产生困惑的那一屏（日志页）隔着两页，而日志页上早已在写着
        // 「到设置页「崩溃取证」打开它」这种指路文案 —— 现在三样东西（开关、状态带、
        // 导出按钮）同屏，那句指路文案也一并删掉了。

        // ---- 丢弃 prompt 缓存（KV 前缀复用）----
        // 多轮对话默认会复用上一轮已经算进 KV 的那段前缀（只算"新增的那部分"），
        // 长 system prompt / 带 tools 的固定段收益最大。这个按钮是**手动兜底**：
        // 换了 system prompt、改了模板之后旧前缀本来就匹配不上（判据是逐 token 比前缀），
        // 但那几 MB KV 会一直占着显存直到被覆盖 —— 手机上就等于可用上下文凭空变少。
        // 所以给一个显式出口，而不是"等它自己失效"。
        pageSet.addView(label("── 推理缓存（KV 前缀复用）──"))
        addFull(label("多轮对话会复用上一轮已算好的 prompt 前缀，只算新增部分。换 system 提示词后点下面按钮可立即释放旧缓存。").apply {
            setTextColor(pal.textSecondary)
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

        // ---- 设置页分组：后台保活（无障碍 / 忽略电池优化 / 冻结痕迹）----
        //
        // 为什么放在设置页、而且**必须常显状态**：用户原来只能从"调用会不会卡"这个
        // 体感去猜权限到底开没开，而三样状态分散在系统设置的三个不同页面里
        // （无障碍 / 电池优化 / 自启动），此前 App 里一个都看不到。
        // 于是"我明明开了还是卡"变成了一个没有反馈面的处境 —— 那正是本轮要终结的。
        //
        // 判据全部来自 [KeepAlive] 读**系统真值**（`ENABLED_ACCESSIBILITY_SERVICES` /
        // `isIgnoringBatteryOptimizations` / `/health` 的 frozen_*），
        // 不用本 App 自己的标志位：标志位只能证明"自己认为如何"。
        pageSet.addView(label("── 后台保活（改的是系统权限，不是本应用参数）──"))
        keepAliveTv = label(keepAliveText()).apply { setTextColor(pal.textSecondary) }
        addFull(keepAliveTv)
        // 「刷新」与「跳转」分开：跳转可能因为 ROM 不认这个 ACTION 而失败，
        // 那种情况下用户会回到这个页面 —— 状态行必须能手动重读一次，
        // 否则他只能杀掉 App 再进来（那是把一次查询失败当成了一次配置失败）。
        val kaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kaRow.addView(btn("刷新状态").apply {
            setOnClickListener { refreshKeepAlive() }
        }, lp(0, 1))
        kaRow.addView(btn("开无障碍").apply {
            setOnClickListener { openAccessibilitySettings() }
        }, lp(0, 1))
        kaRow.addView(btn("免电池优化").apply {
            setOnClickListener { openBatteryOptimizationSettings() }
        }, lp(0, 1))
        addFull(kaRow)
        // 逐级降级要走的最后一步：厂商/原生的引导页都打不开时，这里是唯一还有用的入口。
        addFull(btn("应用详情（自启动 / 省电策略）").apply {
            setOnClickListener { openAppDetails() }
        })
        addFull(label("说明：无障碍与电池优化都只能由你在系统设置里授予，本应用不会代改。" +
            "内核冻结判据只看内存，开了也不承诺不被冻 —— 这两项只提高存活率，" +
            "真正把「卡死」降级成「明确失败 + 快速恢复」的是服务端那几条（见 README「后台保活」）。")
            .apply { setTextColor(pal.warn) })

        // ---- 设置页分组：外观（暗色模式）----
        //
        // 为什么单开一组、而且**不**并进上面任何一个分组：它改的是"整页长什么样"，
        // 与 ctx / 采样 / 保活都无关；并进别的组会出现"想调暗色得先展开『加载与性能』"
        // 这种找不到的处境（本仓库对"功能在、找不到"的判据一向是硬的）。
        pageSet.addView(label("── 外观（只影响本应用的界面，不改变系统设置）──"))
        addFull(label("暗色模式默认**关闭**；选「跟随系统」后，你在系统里切换深色/浅色，本应用下次回到前台时跟着变。" +
            "本应用只读取系统的深浅状态，不会替你改系统设置（那需要普通应用拿不到的权限）。")
            .apply { setTextColor(pal.textSecondary) })
        darkModeGroup = darkModeRadioGroup()
        addFull(darkModeGroup!!)

        // 还原默认设置（同时重置端口与局域网开关）。
        // 它作用于整页所有分组，不是「重复与惩罚」这一组的局部操作，因此打上 UNGROUPED
        // 标记留在面板外：该组默认折叠时按钮依然可见，也不会被"收起全部分组"一起藏掉。
        val resetHint = label("全局操作：下面按钮会把上面所有分组恢复到默认值").apply {
            setTextColor(pal.textSecondary)
        }
        resetHint.tag = UNGROUPED
        addFull(resetHint)
        // 「还原默认设置」要**先问一句**：它一次性覆盖整页参数（含模型加载类），
        // 且就在面板最底下、与上面那些分组只差一次误触 —— 而它**没有撤销**：
        // 用户改过的 ctx / gpu / 采样值不留存任何地方，被覆盖之后只能凭记忆重填。
        // 全 App 其余"一键改一批"的破坏性动作都走同一道闸（删会话 / 批量清理 /
        // 导入落盘 / 切库丢草稿），这里没有理由例外 —— 触屏上的误触是常态，
        // 而"还原默认"从按钮名上看不出它动的是**整页**（提示行写了，但提示行不拦手）。
        val resetBtn = btn("还原默认设置")
        resetBtn.tag = UNGROUPED
        resetBtn.setOnClickListener { confirmResetDefaults() }
        pageSet.addView(resetBtn, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 会话管理行——显示当前会话标题，点击弹会话列表（新建/切换/重命名/删除）
        // (点5): 提示词/输入框/按钮固定到屏幕下方，聊天页本体只剩输出
        chatBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            setPadding(pad, px(6), pad, px(4))
            visibility = if (curTab == 0) View.VISIBLE else View.GONE
        }
        sessionTv = TextView(this).apply {
            textSize = 13f; setTextColor(pal.accent)
            setPadding(px(4), px(6), px(4), px(6))
            isSingleLine = true
            setOnClickListener { showSessionDialog() }
        }
        pageChat.addView(sessionTv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        // 迁移异常提示行：**默认隐藏**，只有真的被上限挡住/落盘失败时才显示。
        // 点一下直达会话管理（用户要做的就是删几个旧会话）。
        migrateHintTv = TextView(this).apply {
            textSize = 12f; setTextColor(pal.warn)
            setPadding(px(4), px(4), px(4), px(6))
            setOnClickListener { showSessionDialog() }
            visibility = View.GONE
        }
        pageChat.addView(migrateHintTv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 角色卡（V1）────────────────────────────────────────────────────
        //
        // 为什么是**下拉 + 一行常驻状态**，不是"再开一个设置页"：
        // 卡片决定的是 system 那一段，而 system 就显示在它下面那个输入框里 ——
        // 两者放在同一屏、上下相邻，"我在用的到底是哪一段"才是**一眼可见**的。
        // 放进设置页就会变成"卡片在那边选、system 在这边打"，用户看不出
        // 谁盖住了谁（而 V1 的口径恰恰是：**有快照时手打的那段不生效**）。
        //
        // ⚠ 刻意**不做**"输入框里直接显示卡片内容"：卡片与手打是两份数据，
        //   显示成一份之后，用户改输入框会以为在改卡片（实际只改了手打那份，
        //   而它此刻不生效）—— "改了半天没变化"正是本仓库最爱钉的那类现象。
        cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cardLabel = TextView(this).apply {
            textSize = 12f; setTextColor(pal.icon)
            setPadding(px(2), px(2), px(6), px(2))
            text = "角色卡"
        }
        cardRow.addView(cardLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cardSpinner = android.widget.Spinner(this).apply {
            id = View.generateViewId()
        }
        cardRow.addView(cardSpinner, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cardManageBtn = btn("管理卡片")
        cardManageBtn.setOnClickListener { showCardDialog() }
        cardRow.addView(cardManageBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chatBar.addView(cardRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        // 下拉的选中回调：**只认用户的手势**。
        //
        // ⚠ 重填下拉（`refreshCardRow`）会触发一次 `onItemSelected(position=0)`——
        //   那是框架在"列表换了"时的通知，**不是**用户选了"（不用卡片）"。
        //   不用 `cardPickerFilling` 把那一拍吃掉，每次切会话/建卡都会顺手把
        //   当前会话的卡片摘掉（静默、不可恢复）。
        cardSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (cardPickerFilling) return
                pickCard(pos)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        // 卡片状态行：**常驻**，不是 Toast。
        // 它要说的是三件"否则看不见"的事：用的是快照、卡片已不在库里、
        // 卡片改了而本会话没跟着变。Toast 是一次性的，而这三件事都是**状态**
        // （同本仓库对"会话超限提示必须常驻"的那条教训）。
        cardHintTv = TextView(this).apply {
            textSize = 12f; setTextColor(pal.warn)
            setPadding(px(4), px(2), px(4), px(4))
            visibility = View.GONE
            // 点它 = 做它说的那件事，不是"再开一个对话框"。
            //
            // ⚠ 这句提示只在"卡片改了"与"卡片被删了"两种状态下可见，而两者的
            //   处置不同：前者可以**用新内容覆盖本会话**，后者只能**重挂一张**
            //   （卡片已经不是库里那张了，覆盖动作没有来源）。
            //   所以这里必须按当前状态分派，不能恒走 [refreshCardSnapshot]
            //   —— 那张卡已经没了，refresh 会静默什么都不做（`?: return`），
            //   用户点一下"没反应"，而他刚被提示"点这里"。
            setOnClickListener {
                val id = SessionStore.cardIdOf(this@EngineActivity, currentSessionId)
                if (id.isEmpty() || RoleCardStore.get(this@EngineActivity, id) == null) showCardDialog()
                else refreshCardSnapshot()
            }
        }
        chatBar.addView(cardHintTv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
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
                    SessionStore.clearMessages(this@EngineActivity, currentSessionId)
                    // 清空**才**该全清：历史一条不剩 = 没有可以复用的前缀，旧 KV 纯白占显存。
                    // 这与"删单条 / 重跑"不同 —— 那两处删的是尾巴，分叉点之前的前缀本来还能用，
                    // 所以它们**不**作废（否则就是"删末条后 KV 完全没复用"那个 bug）。
                    LlmEngine.resetKvCache()
                    renderChat(null)
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

        // 聊天记录容器。**注意它不在 rootScroll 里**（pageChat 整体就在那个 ScrollView
        // 内部），所以这里不该再套一个可滚动容器 —— 嵌套滚动会让"删除一条之后
        // 页面跳到哪儿"变成一件说不清的事，而贴底判据（chatAtBottom）也只认
        // rootScroll 这一个滚动源。
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(6), 0, px(6))
        }
        pageChat.addView(chatContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 常驻状态带：**放在最上面**（日志页第一眼），
        // 说清当前是常规日志模式还是崩溃取证模式 —— 见 logModeTv 的注释。
        logModeTv = TextView(this).apply {
            textSize = 12f
            setPadding(px(6), px(6), px(6), px(6))
            setTextIsSelectable(true)
        }
        pageLog.addView(logModeTv)
        applyProbeStatusBand()

        // ---- 崩溃取证模式的开关（自设置页搬来）----
        // 为什么搬到这一页、以及为什么**紧挨状态带**：这三样东西本来就是一回事 ——
        // 状态带在说"当前日志落在哪一档"，开关是改这一档的唯一入口，而「导出崩溃探针」
        // 是这一档下唯一要做的动作。此前它们分居两页，日志页上还写着
        // 「到设置页「崩溃取证」打开它并重启 App」这种指路文案 —— 一句话把用户
        // 从"出问题的那一屏"赶走。
        //
        // 「探针」这个词对第一次用的人不构成任何信息，反而像"日志的增强模式"。
        // 它实际是"证据保全模式"：会改变日志的落点与叙述方式。所以名字里必须直说
        // "会改变日志行为"，并点明只在排查闪退时开。
        probeSwitch = android.widget.Switch(this).apply {
            text = "崩溃取证模式（会改变日志落点，仅在排查闪退时开）"
            textSize = 13f
            setPadding(0, px(2), 0, px(2))
            isChecked = LlmEngine.probeEnabled
        }
        probeHintTv = label("").apply { setTextColor(pal.textSecondary) }
        // ⚠「必须完全退出重启才生效」不许退回 Toast：它是本开关唯一让人**以为开了
        // 其实没生效**的地方，而 Toast 一闪而过。所以放在开关正下方、常驻。
        // 这一行也是与下方「导出崩溃探针」之间的"距离引导"：改了这里就得重启，
        // 重启回来第一眼撞上的就是那一排导出按钮。
        val probeRestartTv = label("⚠ 改完必须完全退出并重启 App 才生效（native 在冷启动时决定日志落点）").apply {
            setTextColor(pal.warn); textSize = 11f
        }
        probeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == LlmEngine.probeEnabled) return@setOnCheckedChangeListener
            LlmEngine.setProbeEnabled(this, checked)
            applyProbeHint()
            Toast.makeText(this,
                if (checked) "已开启，请完全退出并重启 App；复现闪退后重开 App 点「导出崩溃探针」"
                else "已关闭，重启 App 后恢复常规日志",
                Toast.LENGTH_LONG).show()
            applyProbeStatusBand()
        }
        pageLog.addView(probeSwitch, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pageLog.addView(probeRestartTv)
        pageLog.addView(probeHintTv)

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
                // 读会话**文件全文**，不读内存 ring（上限 300 行）：
                // ring 一旦被上百行 "." 填满，关键的生效证明行会被挤出窗口，那一轮就再也无法回溯。
                // 文件是完整且已落盘的，直接用它。
                val body = LogFileStore.currentText().ifBlank {
                    // 兜底：文件写失败时退回内存。**必须取全量**（recentLogsUnfiltered），
                    // 不能用日志页当前那一档 —— 导出的是证据，不是"我现在正在看的那一屏"。
                    LlmEngine.recentLogsUnfiltered().joinToString("\n")
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
                        (LogFileStore.currentText().ifBlank {
                            LlmEngine.recentLogsUnfiltered().joinToString("\n")
                        })
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
        // 两个「导日志」按钮长得一样、名字都像"导全部"，第一次用的人分不出该点哪个 ——
        // 所以把**分工**写在按钮正下方一行小字里：常规叙述 vs 信号现场。
        pageLog.addView(label("「导出本次/上次会话」= 常规日志叙述，排查「为什么慢 / 为什么判定不对」；" +
            "「导出崩溃探针」= 信号现场，排查「为什么闪退」。" +
            "两份合起来才是全量（见顶部状态带）").apply {
            textSize = 10f
            setTextColor(pal.textMuted)
        })
        // 「导出崩溃探针」单列一行：这是排查 native abort 的主入口，不能和上面四个挤一排。
        val logProbeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val logProbeBtn = btn("导出崩溃探针")
        logProbeBtn.setOnClickListener {
            val native = LlmEngine.probeText()
            // 空态**不能一句话盖三种成因**：原来一句"进程未挂上探针或本次是首次启动"，
            // 但它其实还可能是"探针开着、这次却没有任何 native 输出" —— 一种文案指向三个方向，
            // 用户按错误的那个去查，只会查一件没发生的事。这里按成因分开说。
            val probeEmptyReason = when {
                !LlmEngine.probeEnabled ->
                    "(空：崩溃取证模式未开启 —— 打开本页顶部那个开关并重启 App，再复现问题)"
                !LlmEngine.probeAttachedNow ->
                    "(空：探针文件已建立但 native 未挂上（见下方环境行 attached=false）—— " +
                        "logcat 搜 `probe:` 看原因)"
                else ->
                    "(空：探针已挂载，但本次运行还没有任何 native 输出 —— 先复现一次再导出)"
            }
            if (native.isBlank() && !LlmEngine.probeEnabled) {
                Toast.makeText(this, "崩溃取证模式未开启：打开本页顶部那个开关，重启 App 后再复现",
                    Toast.LENGTH_LONG).show()
            } else {
                try {
                    // 三段合一：native 原始探针（含信号现场最值钱）+ Kotlin 侧本次全文 +
                    // 上次会话（上一次闪退那一轮就是它）
                    val body = buildString {
                        appendLine("==== native 探针（信号现场与全量原生日志）====")
                        appendLine(native.ifBlank { probeEmptyReason })
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
        // 「纯 CPU 加载」：原设置页「纯CPU诊断」按钮，搬到这个工具栏里。
        // 它做的事（gpu layers 归零 + 固定 ctx/线程）是**排障动作**，不是配置 ——
        // 混在"加载模型/卸载模型"那一排时看不出区别，而它恰好会在"怀疑 GPU 路径
        // 有问题"时才被用到，那一刻用户手里握着的就是这一页。
        val logCpuLoadBtn = btn("纯 CPU 加载")
        logCpuLoadBtn.setOnClickListener { doLoadCpuOnly() }
        logProbeRow.addView(logCpuLoadBtn, lp(0, 1))
        pageLog.addView(logProbeRow)
        // 「批大小一致性检查」（ISSUE #269 第 5 步）——
        // ⚠ **定位是"常驻探针"，不是"临时诊断"**（0.9.231 改）。
        //   它当初存在的理由是回答一句待验断言（"不同批大小算出的向量是否逐位相同"），
        //   那句**已经答完**了（真机读数：长短悬殊那组 `different`、4.510e-03）——
        //   按"临时诊断用完就撤"的算法它该退场。但它手里还攥着一件**没有别的入口**
        //   的事：它是"批大小到底会不会改语义 / 改答案"这件事在真机上**唯一的取样点**。
        //   语料是固定的两组合成样本，而真实的库里有没有比它们更坏的批
        //   （更长的、更不等长的、真踩到截断的）**谁也没量过** —— 而"`g` 能不能按库
        //   放行"恰恰取决于**最坏**的那组。所以它留在这一页，作为"怀疑批大小有问题时，
        //   手边就有的那把尺子"，而不是一次性的验证工装。
        //   名字跟着改：旧名「向量逐位比对」只说了它量的**一半**（还有"检索对照"
        //   那半格），且"逐位比对"听起来像内部实现；新名说的是用户要判断的那件事。
        // 它单列一行，不与上面那些"导日志 / 加载"同排 —— 它不是日志操作，
        // 也不是加载操作，而是一次**只读的取样**：跑同一批文本两条路，
        // 比出来的结论直接决定"改批大小要不要重建索引"。
        val logParityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val logParityBtn = btn("批大小一致性检查")
        logParityBtn.setOnClickListener {
            // ⚠ **这一整趟必须离开主线程**（真机现场：8 批建索引成功 -> 1 批建索引成功
            // -> 点这个按钮（当时还叫「向量逐位比对」）-> App 没了）。它一次要跑四组对照、其中两组还要把
            // 「长短悬殊」那组（含一条 ~1180 token 的超长文本）各编两遍 ——
            // 主线程上要跑几十秒。本页所有重活（建库 / 取列表 / 编码）都走 [ioExecutor]，
            // 只有它漏在外面：主线程被占满 -> ANR / 被系统杀掉，而现场看起来像
            // "native 崩了"（探针里没有任何信号记录，因为它根本没崩在 native）。
            logParityBtn.isEnabled = false
            ioExecutor.execute {
              try {
                // ⚠ **两组样本各跑一遍**（不是并成一批）：`TEXTS` 是长短悬殊 + 超长，
                // `SAME_LEN` 是四条等长。并成一批时等长那几条的结论会被悬殊那几条
                // 淹掉，而"是哪几条不同"正是要问的东西；分开跑才能把
                // 「批布局」与「不等长的 padding/切分」这两个来源分开（见 SAME_LEN 对象头）。
                // ⚠ 对外读数（`/health`）只取**长短悬殊**那一组 —— 它与检索那格同源：
                //   放行 `g` 取决于**最坏**的那组，而等长组按构造必然稳（每条字符数
                //   全等，唯一变量只剩批布局），落它等于**用必然成立的那组去回答
                //   "换批大小会不会改答案"**（真机现场 PR #301 正是如此：`/health`
                //   报"4/4 逐位相同"，而同一份日志里长短悬殊是「真的不同 1 条」）。
                //   等长组仍照跑、仍照打日志（它把"批布局"这一个变量单独摆出来），
                //   只是不落 `/health`。两组各带 label，入口据此决定落哪一组。
                val longShort = LlmEngine.probeEmbedBatchParity(
                    EmbedParityProbe.TEXTS, LlmEngine.LONG_SHORT_LABEL)
                val sameLen = LlmEngine.probeEmbedBatchParity(
                    EmbedParityProbe.SAME_LEN, LlmEngine.SAME_LEN_LABEL)
                // ⚠ 向量那一格**答不了**用户真正要问的那句（"换批大小重建后我搜出来的
                // 还是不是同几条"）：两者之间没有蕴含关系（见 EmbedRetrieveParity 对象头）。
                // 所以同一个按钮里再跑一格**检索对照**：同批文本按两种批大小各编一套
                // 向量、用同一组查询互查，比"命中的行下标"。
                //
                // ⚠ **两侧的批大小必须真的不同**。这里原来写的是
                // `if (curBatch == baseBatch) 1 else baseBatch` —— 用户把设置改成 1
                // （正是这次复现的配置）时 `curBatch=1`、`baseBatch=8` 不等，于是
                // `otherBatch` 取到 8，**两侧都是 8**：比的是"8 对 8"，结论恒为
                // "答案没变"。那是一个**假的**断言成立，比没有结论更坏 —— 它会让人
                // 据此放行 `g`（本仓库反复记录的一族，见守卫里"两侧同一个批大小"
                // 那条判据的说明）。改成：一侧取**用户当前配置**，另一侧取一个
                // **与它不同**的值（配置恰好是默认值时选 1，否则选默认值）。
                val curBatch = EmbedBatchPref.get(this)
                val baseBatch = LlmEngine.kEmbedBatchDefault
                val otherBatch = if (curBatch != baseBatch) curBatch
                                 else if (baseBatch != 1) 1 else 2
                // ⚠ 语料用 [EmbedParityProbe.RETRIEVE_TEXTS]（**8 条**，长短悬殊），
                // 不是 [EmbedParityProbe.TEXTS]（4 条）。4 条语料 + topK=3 时
                // 任何查询的 Top-3 都是"4 条里去掉 1 条"，命中集合几乎恒等 ——
                // 那格会永远报"没换答案"，而它不是因为答案稳，是样本太小。
                // 语料撑到 topK 的两倍以上，"某一条被挤出前 3"才看得见。
                // ⚠ 两组各带一个 label，入口据此决定**哪一组落进 `/health`**：
                //   长短悬殊那组（唯一能改变答案的）才是 `/health` 要报的那条 ——
                //   不带 label 时两次 `Last.record` 会**无条件覆盖**，而后跑的
                //   等长那组按构造就是稳的，于是 `/health` 报的永远是"必然成立"那句。
                val retrieve = LlmEngine.probeRetrieveParity(
                    EmbedParityProbe.RETRIEVE_TEXTS, EmbedParityProbe.RETRIEVE_TEXTS_QUERIES,
                    baseBatch, otherBatch, label = LlmEngine.LONG_SHORT_LABEL)
                val sameLenRetrieve = LlmEngine.probeRetrieveParity(
                    EmbedParityProbe.SAME_LEN, EmbedParityProbe.SAME_LEN_QUERIES,
                    baseBatch, otherBatch, label = LlmEngine.SAME_LEN_LABEL)
                val msg = if (longShort == null && sameLen == null && retrieve == null) {
                    "没有结论：未加载嵌入模型，或合批那一侧整批不可用。" +
                        "先「加载嵌入模型」，再看日志里 [embedBatch] 那行"
                } else {
                    // 各组各说一句（日志里已经分行打了，这里合并回显，
                    // 因为 Toast 只显示得下一屏）。
                    listOf(
                        longShort?.let { "长短悬殊：${it.describe()}" } ?: "长短悬殊：无结论",
                        sameLen?.let { "等长：${it.describe()}" } ?: "等长：无结论",
                        retrieve?.let { "检索对照：${it.describe()}" } ?: "检索对照：无结论",
                        sameLenRetrieve?.let { "检索对照（等长）：${it.describe()}" }
                            ?: "检索对照（等长）：无结论",
                    ).joinToString("\n")
                }
                LlmEngine.uiLog("[批大小检查] $msg")
                ui.post {
                    logParityBtn.isEnabled = true
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
              } catch (t: Throwable) {
                // 后台线程上抛出去就是"未捕获异常 -> 进程没了"（本仓库记录过一族：
                // 现场看起来像 native 崩，实际是 Java 侧没人接）。兜住并说清是哪一层。
                LlmEngine.uiLog("[批大小检查] 异常：${t.javaClass.name}: ${t.message}")
                ui.post {
                    logParityBtn.isEnabled = true
                    Toast.makeText(this, "比对异常：${t.javaClass.simpleName}: ${t.message}",
                        Toast.LENGTH_LONG).show()
                }
              }
            }
        }
        logParityRow.addView(logParityBtn, lp(0, 1))
        pageLog.addView(logParityRow)
        // ⚠ 条数**从常量取**，不手抄：手抄的份在 0.9.222 把两组各 6 条改成各 4 条时
        // 就地漂了（说明里还是"等长 5 条"，而真跑的是 4 条）—— 那行字正是让人
        // 对读数的手段，写错的条数会让人按**错的样本数**去解释结论。由守卫正面钉住
        // （判据读的是那两句 `size`，不是"文件里出现过 4 这个数字"）。
        // ⚠ 面板上的说明有**体积**上限（`tools/repack_lp/check_hint_length.py` 的
        // HINT_MAX，设置页与日志页共用同一把尺子）。这一段一度堆到 541 字（0.9.231
        // 又长到 704 字），把排查过程（哪一格量什么、反例怎么读）全塞进了按钮下面
        // —— 那是 README / HINT-STATUS 那一层的东西，面板要的是"按钮干什么"。
        // 收敛成三句：**它是什么**（常驻探针）、**量什么**（两组样本各走两条路、
        // 逐位比）、**结论决定什么**。细节留在上面的注释与本行上方的实现里，
        // 判据也还在（条数从常量取）。
        pageLog.addView(label("「批大小一致性检查」——常驻探针：两组样本" +
            "（悬殊 ${EmbedParityProbe.TEXTS.size} / 等长 ${EmbedParityProbe.SAME_LEN.size} 条）" +
            "各走逐条与合批两条路，逐位比向量并各自重复；" +
            "⚠ 向量不同≠检索结果不同（「检索对照」另说）。" +
            "结论决定改批大小要不要重建索引").apply {
            textSize = 10f
            setTextColor(pal.textMuted)
        })
        // 过滤条：chip 文案全部来自 LogFileStore.LogFilter.labels（判据与标签同源）。
        // 放成**独立一行**而不是挤进上面两排按钮：日志页的按钮已经两排了，
        // 这一排的语义与它们不同（它不产生动作，只改取数口径），混在一起会让人
        // 以为"按标签过滤"是一次性操作，用完就没。
        val logFilterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logFilterChips.clear()
        for (label in LogFileStore.LogFilter.labels) {
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                // 点击区必须够大：这是日志页唯一"改了立刻能看出来"的控件，而它自己就
                // 长在一屏灰字里 —— 窄按钮在这块背景上很难一次点中。
                setPadding(px(10), px(5), px(10), px(5))
                isClickable = true
                // 多选：点一下加/减，不是"换档"。所以这里不调 applyLogFilter(label)，
                // 而是把"这一档是否选中"的取反映射交给 toggleLogFilterKey。
                setOnClickListener { toggleLogFilterKey(label) }
            }
            logFilterChips.add(chip)
            logFilterRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 横滑容器：四个 chip + 清空按钮在小屏上会挤到换行/被裁掉，而这一排正是要
        // "一眼看全有哪些档"的地方。外层 rootScroll 是纵向的，横滑不冲突。
        pageLog.addView(newHorizontalScroll(logFilterRow))
        // 关键字搜索：**不加**"搜索"按钮 —— 输入即筛（见 keywordEt 的 TextWatcher）。
        // 加一个按钮等于要求用户按两次（打字 + 点），而日志页的诉求是"边看边收窄"。
        keywordEt = EditText(this).apply {
            hint = "关键字（大小写不敏感）"
            textSize = 12f
            setSingleLine(true)
            setPadding(px(8), px(4), px(8), px(4))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = applyLogFilter()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        // 清空键：关键字很容易打错一个字母，而打错的症状正好是"日志少了/空白"——
        // 必须有一键回到全部，不要逼着用户逐字删（清空会触发 TextWatcher → 重新筛）。
        val clearKwTv = TextView(this).apply {
            text = "×"
            textSize = 16f
            setPadding(px(10), 0, px(10), 0)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { keywordEt.setText("") }
        }
        val kwRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kwRow.addView(keywordEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        kwRow.addView(clearKwTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pageLog.addView(kwRow)
        // 进页面时恢复上次的口径。放在建完 chip 与输入框之后：applyLogFilter 里要刷选中态。
        // **读回必须校验**（档位逐个对 labels、关键字最多保留 [KEYWORD_MAX]）：
        // prefs 里的脏值直接喂进判据 = 一整页空白，而空白页会被读成"没记日志"。
        //
        // 顺序有讲究：先填集合，再 setText。setText 会触发 TextWatcher → applyLogFilter，
        // 那个回调里要用**刚填好的**集合，否则第一帧的选中态是空的（而且会被写回 prefs）。
        // 最后再显式调一次：文字与初值相同时 TextWatcher 不会触发，那一次不能省。
        val pref = getSharedPreferences("ui_prefs", MODE_PRIVATE)
        logFilterKeys += pref.getStringSet(LOG_FILTER_KEY, emptySet())
            .orEmpty().filter { LogFileStore.LogFilter.labels.contains(it) }
        keywordEt.setText(pref.getString(LOG_KEYWORD_KEY, "").orEmpty().take(KEYWORD_MAX))
        // 恢复态与点击走**同一个入口**（applyLogFilter），它是唯一的写点 ——
        // 这是"点了 chip 但取数口径没跟上"这类半截状态在结构上不可能出现的原因。
        applyLogFilter()
        val logHint = label("日志已实时写入内部 filesDir/logs/（崩溃也不丢）；导出按钮输出到公共「下载」目录。" +
            "「导出崩溃探针」= native 探针原文 + Kotlin 侧本次会话 + 上次会话，排查 native 闪退只需这一份")
        logHint.setPadding(logHint.paddingLeft, px(6), logHint.paddingRight, 0)
        pageLog.addView(logHint)
        // 截断说明：默认不可见。日志页只渲染最近若干行，若 ring 里还有更早的行，
        // 这里如实说明「显示了多少、被截掉多少、完整日志去哪儿取」——
        // 页面上一行模型信息都没有（见 renderBackendState 的实测层分布）时，
        // 至少能一眼判断是"没渲染到"而不是"引擎没打这行"。
        logTrimTv = TextView(this).apply {
            textSize = 10f; setTextColor(pal.warn)
            setPadding(0, px(2), 0, px(2))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        pageLog.addView(logTrimTv)
        logTv = TextView(this).apply {
            textSize = 10f; setTextColor(pal.textMuted)
            typeface = Typeface.MONOSPACE
        }
        pageLog.addView(logTv)

        // (需求3): 设置页分组折叠。NPU 与「加载与性能」默认展开，其余收起，状态持久化
        val setPageSections = collapsify(pageSet, setOf("加载与性能", "生成与采样"))
        addSectionToggleBar(pageSet, setPageSections)
        // 知识库页同样分组折叠：它自己就有「嵌入模型」「知识库」两个分组头。
        // 默认展开这两组 —— 这一页的全部内容都是用户来这里要看的东西，没有"次要"之分。
        val ragSections = collapsify(pageRag, setOf("嵌入模型", "知识库"))
        addSectionToggleBar(pageRag, ragSections)
        // (点4): tab 挪到页面底部（拇指可及），其上方是常驻输入条
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(rootScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 输入条与上方滚动区之间的**一条细分隔线**：两边都是浅色，不加线时输入框
        // 看起来是"浮"在聊天记录中间、不知道从哪里开始。用 1dp 的纯色 View
        // （不是给 chatBar 画单边 border）—— 后者要自己搭 GradientDrawable 的
        // layer-list，而这里只需要一条线。只在聊天页挂（其它页没有输入条）。
        chatBarDivider = View(this).apply {
            setBackgroundColor(pal.divider)
            visibility = if (curTab == 0) View.VISIBLE else View.GONE
        }
        outer.addView(chatBarDivider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, px(1)))
        outer.addView(chatBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        tabRow.setBackgroundColor(pal.surfaceAlt)
        outer.addView(tabRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // 用户上滑翻看历史时暂停跟随，回到底部自动恢复；布局变化（键盘挤压）后补一次跟随
        rootScroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
            autoFollowChat = chatAtBottom()
            // 日志页同理：用户上滑查看历史后停止跟随，回到底部后自动恢复跟随。
            // 判据用「是否贴底」而不是「位移方向」——恢复跟随必须是显式回到最底，
            // 否则往下滑一点点就又开始被拽，比不跟随更难用。
            if (curTab == 3) logFollowBottom = isLogAtBottom()
        }
        rootScroll.addOnLayoutChangeListener { _: View, _: Int, _: Int, _: Int, bottom: Int,
                                                 _: Int, _: Int, _: Int, _: Int ->
            if (bottom > 0) followChatBottom()
        }
        // 外壳三件（根滚动区 / tab 行 / 输入条与分隔线）此时都已建好，先压一次色：
        // `outer` 的底色是窗口背景给的，而**第一帧**就在 setContentView 之后马上画
        // —— 等到 onCreate 末尾再压，暗色用户每次冷启动都会先看到一帧白底。
        applyThemeChrome()
        setContentView(outer)
        // 再压一次：上面那次压的是"已经建好的那三件"，而 tab 行与输入条是在
        // setContentView 之前才 addView 进去的（`outer` 彼时尚不完整），
        // 这一次才是真的落色（两次都派生自同一个 `pal`，结果必然一致）。
        applyThemeChrome()
        initSessions() // 多会话初始化（含旧单会话迁移）

        // 原生日志 → 界面（ring buffer 回放 + 实时追加）
        // 合并渲染——日志风暴下每轮消息队列最多一次 renderLog，
        // 防 ui.post 堆积占满主线程（修复「多次刷 /v1/models 必卡顿断联」）。
        val logRenderPending = java.util.concurrent.atomic.AtomicBoolean(false)
        LlmEngine.logSink = { line ->
            // ── 进度回执走**另一条闸** ──────────────────────────────────
            // 合并渲染那条路解决的是"同一瞬间涌进来 N 行"（每轮消息队列一次
            // renderLog），但它挡不住"一秒涌进来一千轮"：每轮都渲染一次日志页
            // （2000 行 ring 的排版 + 标签筛选），主线程照样被按死在排版上。
            // 逐条编码恰好就是那种形态 —— 一份 3 MB 的整篇切出上万块，
            // 每 32 块回一次进度，一趟就是几百次全量重排。
            //
            // 所以进度**另走一道**：只更新状态行那一个 TextView（见 updateProgressLine），
            // 并且按**时间**（≥ [PROGRESS_MS] 一次）而不是按条数节流 ——
            // 条数节流挡不住"块很大、每块都很慢"的另一半。
            // 真正要复盘的日志（开始 / 拒绝 / 结束 / 失败明细）走的仍是上面那条路，
            // 一条不少（见 [LlmEngine.uiProgress] 的说明）。
            if (line.startsWith(PROGRESS_PREFIX)) {
                val now = System.currentTimeMillis()
                // 丢掉的那些中间数字**不往下走**（既不进 renderLog 那条闸，也不重排任何东西）。
                //
                // ⚠ 这里**不能用 `return@logSink`**：`LlmEngine.logSink` 是
                // `((String) -> Unit)?` —— 一个**普通函数类型**属性，它的 lambda 是
                // 非 inline 的，标签 `logSink` 不生成内联点，`return@logSink` 直接
                // 编译不过（`'return' is prohibited here.`）。同理也不能写裸 `return`。
                // 改成把"之后那些行"整段放进 else —— 与 `return` 等价，且在旧语法下合法。
                if (now - lastProgressAt >= PROGRESS_MS) {
                    lastProgressAt = now
                    ui.post { updateProgressLine(line) }
                }
            } else if (logRenderPending.compareAndSet(false, true)) {
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
                updateModelTv()
                probeAndRender(f)   // 首屏恢复选中模型后也预读一次
            }
        }
        refreshModelList()
        // 恢复上次选中的嵌入模型（与对话模型各存一个键，互不影响）
        ModelStore.embedModelName(this).takeIf { it.isNotEmpty() }?.let { n ->
            ModelStore.findByName(this, n)?.let { embedFile = it }
        }
        refreshEmbedList()
        // 知识库：库名恢复 + **正文回填** + 列表刷新 + 状态行（开关值已在建控件时读持久化）
        //
        // 回填这一步是"退出重进后输入框空白、点谁都没内容"的直接解药：库的内容一直在
        // `texts.jsonl` 里，此前只是**没有任何入口把它读回来**，于是看起来像"没存住"。
        ragKbEt.setText(RagInject.currentKb(this))
        loadRagDoc(RagInject.currentKb(this))
        refreshRagList()
        // 启动时也把条目列表读出来（否则"库里有什么"要等用户点一次库才看得见，
        // 而这一屏本来就是"我上次加的那条还在不在"的检查入口）。
        refreshRagRows(RagInject.currentKb(this))
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

    /**
     * 回到前台时重读「后台保活」三项状态。
     *
     * 为什么必须在这里读、而不是只在 onCreate 读一次：这三样状态**都只能在系统设置里改**
     * （无障碍开关、电池优化白名单、任务锁），用户点完我们的跳转按钮去系统设置改完、
     * 再回到 App 时，`onCreate` 不会再跑一遍 —— 只在 onCreate 读的话，用户会看到
     * **改之前**的那一行结论，然后据此判断"我明明开了，这个 App 还说没开"。
     * 那种"结论与现场相反"的读数比不显示更坏：它会把人引去排查别的地方。
     */
    /**
     * 「跟随系统」时，用户在系统里切了深色/浅色之后回到本应用 —— 这一跳必须重建。
     *
     * 为什么不能"就地换一遍颜色"：本页几十处颜色是 `onCreate` 里**一次性**按
     * 当时那一档刷上去的（连气泡的 `GradientDrawable` 也是当场生成并挂上的）。
     * 想就地换，就得把每一处取值点都改成"每次重画都要重新问一次主题"——
     * 而漏一处的症状是**某一小块颜色不跟着变**，只在"系统切色之后不重启"
     * 这条路径上出现（截图、单测、冷启动全都看不见）。
     * `recreate()` 走的是同一份 `onCreate`，所以三处落值天然一致：
     * 不存在"有的页暗、有的页亮"。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 只在「跟随系统」这一档重建：另外两档下系统深浅与本应用无关，
        // 重建会白闪一次、还会把正在输入的正文与滚动位置丢掉（那是实打实的损失）。
        if (ThemePref.followsSystem(ThemePref.get(this))) recreate()
    }

    override fun onResume() {
        super.onResume()
        refreshKeepAlive()
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

    // ---- 后台保活（Issue #159 的 B 方案：无障碍 + 电池优化引导 + 状态可见）----

    /**
     * 「后台保活」状态行的文案：**三项状态 + 最后一份冻结痕迹**。
     *
     * 这个函数的产物是用户唯一能"看着核对自己到底开没开"的东西，所以两条纪律：
     *   1. 三项都报三态，**查不到就说查不到**（见 [KeepAlive.onOffText]）——
     *      折成"未开"会让一次系统接口抽风变成一次误报，用户会去反复开关；
     *   2. 冻结痕迹取自**最后一次「存活探测」的结论**（缓存字段），
     *      不在这里另发一次 HTTP —— 那样每次进设置页都会打一次自己的服务，
     *      而后台被冻时这个请求本身就会挂住。没有结论时说清"先探测"。
     */
    private fun keepAliveText(): String {
        val acc = KeepAlive.accessibilityState(this)
        val bat = KeepAlive.batteryOptimizationState(this)
        return buildString {
            append("无障碍服务：").append(
                KeepAlive.onOffText(acc, "部分 ROM 会据此提高存活优先级", "系统设置 → 无障碍"))
            // `<service>` 声明被误删时，系统设置里根本不会出现这一项 ——
            // 那种情况下"未开启"会让用户反复去设置里找，所以单独点出来。
            if (acc != KeepAlive.OnOff.ON && !KeepAlive.serviceDeclared(this@EngineActivity)) {
                append("\n⚠ 本应用的无障碍服务在系统列表里查不到（安装包声明可能缺失，重新安装一次）")
            }
            append("\n忽略电池优化：").append(
                KeepAlive.onOffText(bat, "Doze 下不会被限流", "点下面「免电池优化」一次跳转即可"))
            append("\n").append(KeepAlive.freezeLine(lastProbeResult))
            if (acc == KeepAlive.OnOff.ON) {
                append("\n无障碍实际形态：").append(KeepAlive.serviceInfoSummary(this@EngineActivity))
            }
        }
    }

    /** 重读三项状态并回填（进设置页、回到前台、点「刷新状态」时都走这里）。 */
    private fun refreshKeepAlive() {
        if (!::keepAliveTv.isInitialized) return
        keepAliveTv.text = keepAliveText()
    }

    /**
     * 引导到系统「无障碍」页。
     *
     * 刻意**不**在这里代用户开启：写 `ENABLED_ACCESSIBILITY_SERVICES` 等于绕过用户同意，
     * 且需要普通应用拿不到的 `WRITE_SECURE_SETTINGS`。跳不过去时给可照抄的路径
     * （那是各家 ROM 的差异，不是错误）。
     */
    private fun openAccessibilitySettings() {
        try {
            startActivity(KeepAlive.accessibilityIntent())
            LlmEngine.uiLog("[保活] 已跳转无障碍设置；开启本应用的服务后回到本页会自动重读状态")
        } catch (t: Throwable) {
            // 日志与 Toast 只在**失败**这一支：成功那支写在这里会让"跳转失败"与
            // "已跳转"两行同时出现，读日志的人分不清到底跳没跳成。
            LlmEngine.uiLog("[保活] 跳转无障碍设置失败：${t.message}")
            Toast.makeText(this, "打不开无障碍设置页，请到：设置 → 无障碍 → 已安装的服务",
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 引导「忽略电池优化」，**逐级降级**。
     *
     * 三级顺序是刻意的：带包名的申请页最省事（系统直接列出本应用）→ 电池优化总列表
     * （厂商改过申请页时用）→ 应用详情页（前两者都不认时的兜底）。任一跳成功即返回；
     * 全失败才把 [KeepAlive.batterySteps] 那四步文字贴出来 —— 一句"跳转失败"对用户
     * 等于没有信息，而这四步分散在四个页面、各家名字还不一样。
     */
    private fun openBatteryOptimizationSettings() {
        val attempts = listOf(
            "申请页" to KeepAlive.requestIgnoreBatteryOptimizationIntent(this),
            "优化列表" to KeepAlive.batteryOptimizationListIntent(),
            "应用详情" to KeepAlive.appDetailsIntent(this),
        )
        for ((name, intent) in attempts) {
            val ok = try {
                startActivity(intent); true
            } catch (t: Throwable) {
                LlmEngine.uiLog("[保活] 跳转「$name」失败：${t.message}"); false
            }
            if (ok) {
                LlmEngine.uiLog("[保活] 已跳转「$name」（电池优化）；选「无限制 / 不优化」后回本页自动重读")
                return
            }
        }
        // 三级全失败：给出照抄得下去的路径，而不是"跳转失败"四个字。
        LlmEngine.uiLog("[保活] 三个电池优化入口都打不开，改为文字指引")
        Toast.makeText(this, "打不开电池优化页面，请手动设置：\n" +
            KeepAlive.batterySteps.joinToString("\n"), Toast.LENGTH_LONG).show()
    }

    /** 应用详情页：自启动 / 省电策略 / 后台限制都在它的下一层，各家 ROM 位置不同。 */
    private fun openAppDetails() {
        try {
            startActivity(KeepAlive.appDetailsIntent(this))
        } catch (t: Throwable) {
            LlmEngine.uiLog("[保活] 跳转应用详情失败：${t.message}")
            Toast.makeText(this, "打不开应用详情页，请手动设置：\n" +
                KeepAlive.batterySteps.joinToString("\n"), Toast.LENGTH_LONG).show()
        }
    }

    /** 探针状态一行话：开没开、挂没挂上、文件多大（UI 上直接可判断，不必翻文件） */
    private fun applyProbeHint() {
        if (!::probeHintTv.isInitialized) return
        probeHintTv.text = when {
            !LlmEngine.probeEnabled ->
                "关闭中（常规日志模式，日常看日志就用它）。开启后（开关就在上面）native 会自己开文件直写" +
                    "全量日志（含原生日志、stderr 遗言与逐 token 输出），并捕获 " +
                    "SIGSEGV/SIGABRT 的信号号/故障地址/触发时刻；崩完重开 App 点「导出崩溃探针」，" +
                    "把文件发给我即可定位。"
            else -> {
                val f = LlmEngine.probeFile
                val size = f?.let { if (it.exists()) it.length() else 0L } ?: 0L
                "已开启（崩溃取证模式）｜ 探针文件=${f?.absolutePath ?: "(重启 App 后建立)"}" +
                    " 当前 ${size / 1024} KB ｜ native 已挂载=${LlmEngine.probeAttachedNow}\n" +
                    "两份日志分工：probe-native.log = native 全量 + stderr 遗言 + 逐 token 原始流 " +
                    "+ 信号现场；session-*.log = Kotlin 侧全量（不折叠）。合起来才是全量。\n" +
                    "本代从零开始写：上次运行那一份已滚成 probe-native.log.prev（只留两代，崩溃现场不丢）。" +
                    "目前**没有上限**，文件会一直长 —— 真觉得占地方就关掉开关、或直接把文件删了。"
            }
        }
        probeHintTv.setTextColor(if (LlmEngine.probeEnabled) pal.warn else pal.textSecondary)
    }

    /**
     * 日志页顶部的常驻状态带：**直说当前处于哪一档**。见 [logModeTv] 的注释。
     *
     * 两档的措辞必须分别说清"日志现在落在哪里"，而不是笼统的"已开启/已关闭" ——
     * 用户的困惑正是"日志怎么少了"，所以答案必须是"少了的那部分去哪了、要全量看哪份"。
     * 取证模式下**不说"只进 probe-native.log"**：修完 A 层后是两份文件分工
     * （探针文件 = native 全量 + 信号现场；会话文件 = Kotlin 侧全量），
     * 写成"只此一份"就是第二种谎。
     */
    private fun applyProbeStatusBand() {
        if (!::logModeTv.isInitialized) return
        logModeTv.text = if (LlmEngine.probeEnabled) {
            "⚠ 崩溃取证模式已开：native 全量 + stderr 遗言 + 逐 token 原始流 + 信号现场进 " +
                "probe-native.log；Kotlin 侧全量（不折叠）仍进 session-*.log。" +
                "两份合起来才是全量 —— 日常看日志请用下方开关关掉它。"
        } else {
            "常规日志模式 ｜ 最近 ${LogFileStore.KEEP} 个会话保留在 filesDir/logs/（session-*.log）"
        }
        logModeTv.setTextColor(
            if (LlmEngine.probeEnabled) pal.warn else pal.textSecondary)
    }

    /**
     * 渲染日志页。
     *
     * 正文 = **当前口径（标签多选 + 关键字）下、ring 里的最后 [LOG_VIEW_LINES] 行**
     * （取数见 `LlmEngine.recentLogs`：先按判据筛、再取尾）。注意顺序不能反 ——
     * 先取尾 120 行再筛，等于"在屏幕上过滤"，某标签的行整段滚出窗口时会呈现空白页。
     */
    private fun renderLog() {
        applyProbeHint()
        applyProbeStatusBand()
        if (!::logTv.isInitialized) return
        // 过滤在 **ring 层**（LlmEngine.recentLogs 按当前判据取数），不在这 120 行渲染结果上做：
        // ring 2000 行、界面只渲染尾部 120 行，某标签的行完全可能整段落在窗口之外 ——
        // 在渲染结果上过滤，用户看到的是空白页，而空白页会被读成"没记日志"。
        val lines = LlmEngine.recentLogs()
        val total = lines.size
        val shown = minOf(total, LOG_VIEW_LINES)
        // 「有没有在筛」只看**一个源**（LlmEngine 的那份口径），不看本地字段：
        // 正文写不写、说明行说不说，都必须与"取数到底筛没筛"同一个判据，
        // 否则会出现"正文是全量、说明行却说命中 3 行"这种自相矛盾的页面。
        val filtered = LlmEngine.logFilterActive()
        // 过滤档位下**只在正文内容真的变了时**才写：TextView 每次赋值都会清掉选择，
        // 而用户看完「导出本次」的提示后常要长按复制页面上的某一行 —— 生成期每秒
        // 都在打日志，那个选择会被下一帧无声地清掉。不过滤时没有这个顾虑（本来就在刷新）。
        //
        // ⚠ 这里此前是 `if (filtered) { if (justSwitchedTab) logTv.text = tail }` ——
        // 即"过滤态下**只**在切页/换档时写"。它是一个二选一：要么实时（清掉选择）、
        // 要么冻结（正文停在旧快照）。而**说明行是每次重算的**，于是页面开始说假话：
        //
        //     T1 点「HTP」-> 正文 2 行、说明"命中 2 行"      （一致）
        //     T2 稳态来新行 -> 正文**仍是 2 行**、说明"命中 3 行"  ← 自相矛盾
        //
        // 最坏的一支：命中数 0 -> 正 时正文不重写，用户看到"命中 3 行"、下面一行都没有。
        //
        // 所以判据从"什么时候写的"改成"**内容变了没**"：把上次写进控件的快照存下来比对。
        // 没变就不写（选择保住了），变了就写（页面不再自相矛盾）—— 两个目标不再互斥。
        val tail = lines.takeLast(shown).joinToString("\n")
        if (filtered) {
            // 冷启动那一帧也必须写：logLastBody 初值为 null，与任何 tail 都不相等。
            if (justSwitchedTab || logLastBody != tail) {
                logTv.text = tail
                logLastBody = tail
            }
        } else {
            logTv.text = tail
            logLastBody = tail
        }
        if (curTab != 3) return
        val trimmed = total - shown
        // 说明行是**两个判据的合成**：截断（行数上限）与过滤（命中数）。
        // 只改其中一个而让另一个留着旧值，页面就会开始说假话 —— 所以合并成一个 commit 判据。
        val note = when {
            filtered -> {
                val ringAll = LlmEngine.ringSize()
                val keys = LlmEngine.logFilterKeysNow()
                val kw = LlmEngine.logFilterKeywordNow()
                val what = LogFileStore.LogFilter.describe(keys, kw)
                val hint = LogFileStore.LogFilter.emptyHint(keys, kw, total, ringAll)
                if (hint.isNotEmpty()) hint
                else "筛 $what：命中 $total 行（整段日志共 $ringAll 行）" +
                    (if (trimmed > 0) "；仅显示最近 $shown 行，更早的 $trimmed 行已滚出窗口" else "") +
                    "。要完整日志请用上方「导出」"
            }
            trimmed > 0 -> "仅显示最近 $shown 行（更早的 $trimmed 行已滚出窗口；" +
                "要完整日志请用上方「导出」）"
            else -> ""
        }
        if (logLastTotal != total || logLastTrimmed != trimmed || logLastNote != note) {
            // 说明单独一行，且在滑动容器内、不随日志内容一起滚动（页头常驻）。
            logTrimTv.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
            logTrimTv.text = note
            logLastTotal = total; logLastTrimmed = trimmed; logLastNote = note
        }
        // 与聊天页同样只跟随「用户本来就在底部」的情形：上滑查看历史时不许被拽回底部。
        // 注意 ring 是 2000 行、这里只渲染最近 120 行，所以「贴底」判据不能用控件高度——
        // 用 once-once 标志：切到日志页时置位，用户一上滑即清除。
        if (logFollowBottom) rootScroll.post { followLogBottom() }
    }

    /**
     * 点一个 chip：**取反**它，而不是"换成它"。
     *
     * 多选与单选的差别全在这里：点「计时」在单选下是"只看计时"，在多选下是
     * "计时这一档的选中状态翻转"。所以这个函数只改集合，判据/持久化/重绘都交给
     * [applyLogFilter] —— 三件事共用一个入口，不存在"点了 chip 但口径没跟上"的半截状态。
     */
    private fun toggleLogFilterKey(label: String) {
        if (!LogFileStore.LogFilter.labels.contains(label)) return
        if (!logFilterKeys.remove(label)) logFilterKeys.add(label)
        applyLogFilter()
    }

    /**
     * 把**界面上的当前口径**落到取数、持久化与控件三处。
     *
     * 四件事必须一起做，缺任何一件都会留下一个"看不出是 bug"的界面：
     *   ① 判据只写 [LlmEngine.setLogFilter] 这一个源（不存第二份在控件文案里）；
     *   ② 筛完**贴底标志复位为真** —— 筛之前用户可能在翻历史（标志已清），
     *      筛完内容整段换掉，此时不跟随的话最新命中的行不在视野里，
     *      观感正是本仓库那条老毛病"数据在、界面看不见"；
     *   ③ 口径写 prefs，退出重进沿用（否则每次进日志页都要重新点一遍、重新打一遍）；
     *   ④ 是筛选（不是"又来了几行新日志"）**必须重写正文**：稳态渲染在筛的时候不写正文
     *      （见 renderLog），不主动写一次就等于点了没反应。
     *
     * 关键字**在这里统一 trim**：判据拿到的永远是去空白后的串。不然"搜 `  `"（两个空格）
     * 会去匹配每一行里的空格 —— 看似"没筛"，实则筛了，而说明行会显示"筛 含「  」"。
     */
    private fun applyLogFilter() {
        val kw = if (::keywordEt.isInitialized)
            keywordEt.text?.toString().orEmpty().trim().take(KEYWORD_MAX) else ""
        LlmEngine.setLogFilter(logFilterKeys, kw)
        getSharedPreferences("ui_prefs", MODE_PRIVATE).edit()
            .putStringSet(LOG_FILTER_KEY, HashSet(logFilterKeys))
            .putString(LOG_KEYWORD_KEY, kw)
            .apply()
        paintLogFilterChips()
        logFollowBottom = true
        // 筛选是"换了要显示的内容"，必须重写正文：筛选中时的稳态渲染不写正文（见 renderLog）。
        justSwitchedTab = true
        renderLog()
        justSwitchedTab = false
    }

    /** chip 选中态只有一处判据（[logFilterKeys]），不读控件自身状态——控件会被重建。 */
    private fun paintLogFilterChips() {
        for ((i, chip) in logFilterChips.withIndex()) {
            val on = i < LogFileStore.LogFilter.labels.size &&
                LogFileStore.LogFilter.labels[i] in logFilterKeys
            chip.setBackgroundColor(if (on) pal.chipOn else 0x00000000)
            chip.setTextColor(if (on) pal.chipOnText else pal.chipOffText)
        }
    }

    /**
     * 日志页贴底。渲染完立刻 post，依赖的是同一个 measure/layout 周期，
     * 因此不必等下一帧（日志风暴下来回等待会明显滞后）。
     */
    private fun followLogBottom() {
        if (curTab != 3 || !logFollowBottom) return
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
    // ---- 嵌入模型（RAG）----
    //
    // 三条**静默**失效被这一组入口终结：
    //   ① 没有入口 —— `loadEmbedModel` 调用方为 0，"加载向量模型"实际装进对话槽；
    //   ② 加载前不检查是否已在生成 —— 与换对话模型共用同一类竞态（native 侧两份
    //      Session 彼此独立，但"换嵌入模型"仍会拆掉正在被检索用的那一份）；
    //   ③ 加载结果不可读 —— 此前 `/health` 报不出嵌入那一份，用户只能翻日志。

    private fun refreshEmbedList() {
        val myGen = ++embedListGen
        val sel = embedFile?.name
        // 驻留值在**提交任务时**取快照，不留到渲染回调里现取 —— 与 refreshModelList
        // 同一条理由：中间用户可能点了加载/卸载，现取会把"加载前的取数"与"加载后的
        // 驻留值"拼在同一屏上，那正是本 issue 要终结的那类自相矛盾。
        val loaded = loadedEmbedFile()?.name
        ioExecutor.execute {
            val rows = runCatching { ModelStore.rows(this) }.getOrNull()
            ui.post { if (myGen == embedListGen) renderEmbedList(rows.orEmpty(), sel, loaded) }
        }
    }

    /**
     * 画嵌入模型列表。
     *
     * 与 [renderModelList] 同构（Issue #228 的嵌入侧那一半）：此前这一份**只**标
     * 选中态，于是对话侧修好之后，嵌入侧仍在说反话 —— "我点了 b 想换、内存里还是 a"
     * 在这张列表上照样完全看不出来。两个标记各给各的、允许同时出现在不同的行上。
     */
    @SuppressLint("SetTextI18n")
    private fun renderEmbedList(rows: List<ModelStore.Row>, sel: String?, loaded: String?) {
        embedListContainer.removeAllViews()
        if (rows.isEmpty()) {
            embedListContainer.addView(TextView(this).apply {
                text = "（模型库为空：到上方“添加模型”导入一个 embedding GGUF，如 bge-small）"
                textSize = 12f; setTextColor(pal.textFaint)
                setPadding(0, px(4), 0, px(4))
            })
        }
        for (r in rows) {
            val f = r.file
            val isSel = f.name == sel
            val isLoaded = f.name == loaded
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // 行标三态，口径与对话列表**逐字对齐**（▶ = 在内存里，● = 仅选中）。
            // 为什么要对齐：两张表读的是同一件事的两半，措辞一旦分家，用户就没法
            // 把它们放在一起读。⚠ 这里的说明刻意不写成行标本身的字面量 ——
            // 守卫是按函数体取文本的，注释里的同形字面量会把"行标被改掉"掩盖成
            // 绿灯（`tools/run_embed_state_guard.sh` 第 5 节按函数体判措辞）。
            val mark = when {
                isSel && isLoaded -> "▶● "
                isLoaded -> "▶  "
                isSel -> "●  "
                else -> "○  "
            }
            val tailTxt = when {
                r.missing -> "  ⚠ 原文件已丢失"
                isSel && isLoaded -> "  (${r.bytes / 1048576} MB) · 已加载，检索正在用它"
                isLoaded -> "  (${r.bytes / 1048576} MB) · 在内存里，但当前选中的是别的（未切换）"
                isSel -> "  (${r.bytes / 1048576} MB) · 已选中，尚未加载"
                else -> "  (${r.bytes / 1048576} MB)"
            }
            val tv = TextView(this).apply {
                text = mark + r.alias + tailTxt
                textSize = 13f
                if (isSel || isLoaded) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, px(6), 0, px(6))
            }
            tv.setOnClickListener { selectEmbedModel(f) }
            row.addView(tv, lp(0, 1))
            embedListContainer.addView(row)
        }
        renderEmbedStatus()
    }

    /**
     * 当前**驻留内存**的嵌入模型文件；没有=null。
     *
     * 判据只有一处：`LlmEngine.embedPath`（引擎真值）。**不能**拿本页的 [embedFile]
     * （"用户选中的"）回答这个问题 —— 它点一下列表就变，与内存里是不是那一份无关。
     * 旧画法正是取它，于是"选了 b 还没加载、a 还在内存里"被画成"嵌入模型：b"。
     */
    private fun loadedEmbedFile(): File? = LlmEngine.embedPath?.let { File(it) }

    /**
     * 嵌入那一份的**常驻**状态行。
     *
     * 判据全部读**引擎真值**（`LlmEngine.hasEmbedModel` / `embedDim` /
     * `embedPath`），不读本页自己的变量：本页变量只能证明"我以为如何"，
     * 而这一屏最容易出现的困惑恰恰是"我点了加载，到底装上没有"。
     *
     * ⚠ 旧写法虽然在"装没装上"这一点上问对了引擎，却把**问句问错了对象**：
     * 它只报一个 `已选 <选中名>`，并在这行里带上 `n_embd` —— 于是"已选中 b、
     * 内存里是 a"被读成"嵌入模型：b（768）"。对话侧那一半（Issue #228 本体）
     * 修掉的问题是同一个，只是那份读的是 `modelFile`，这份读的是 `embedFile`。
     *
     * 所以这一行现在**同时报两态**，并且真正驻留的那一份是主角（它决定检索能不能跑）：
     *   · `已加载: <驻留文件>` ← `LlmEngine.embedPath`，检索实际用的就是它；
     *   · `已选中: <选中文件>` ← [embedFile]，点一下列表就变，不代表已在内存里；
     *   · 两者不同时显式提示"内存里仍是 …、检索用的是它"。
     *
     * 为什么不把"选中"那一半删掉只报驻留：选择是**用户下一步动作的落点**
     * （点「加载」装的就是它），抹掉之后"我要加载哪个"在界面上失去落点。
     * 两件事都报，才是这一屏唯一不误导的画法。
     */
    @SuppressLint("SetTextI18n")
    private fun renderEmbedStatus() {
        if (!::embedStatusTv.isInitialized) return
        shownEmbedPath = LlmEngine.embedPath.orEmpty()
        val loaded = LlmEngine.hasEmbedModel
        val loadedF = loadedEmbedFile()
        val sel = embedFile
        // 驻留那一半：n_embd 是**已加载那一份**的属性，所以必须与它的文件名绑在一起写。
        // 分开写（`已加载: a` 与 `n_embd=768` 各在一处）时，"a 是 768 还是选中那份是 768"
        // 在文本上无从分辨 —— 真实模型换了维度时，这行就会变成一句读不出错的错话。
        val loadedText = if (loaded) {
            val n = loadedF?.name ?: "未知文件（引擎报在，路径取不到）"
            "已加载: $n（n_embd=${LlmEngine.embedDim}）"
        } else {
            "未加载（未加载时任何检索都必然为空）"
        }
        val selText = when {
            sel == null -> "未选中"
            !sel.isFile -> "已选中: ${sel.name}（原文件已丢失）"
            else -> "已选中: ${sel.name}"
        }
        embedStatusTv.text = when {
            // 两者不同：这是本行此前最容易骗人的场景，必须点破"检索实际用哪一份"。
            loaded && sel != null && loadedF?.name != sel.name ->
                "$loadedText · ⚠ 但选中了「${sel.name}」——点「加载嵌入模型」才会真正切换（现在检索用的仍是前者）"
            // 都在位且一致。
            loaded -> "$loadedText · 检索正在用它 · 对话模型${if (LlmEngine.hasModel) "同时在位" else "未加载"}"
            // 没驻留：选中态照报，好让"下一步装哪个"仍有落点。
            else -> "$loadedText · $selText"
        }
        embedStatusTv.setTextColor(if (loaded) pal.textSecondary else pal.warn)
        if (::embedUnloadBtn.isInitialized) embedUnloadBtn.isEnabled = loaded
        if (::embedLoadBtn.isInitialized) embedLoadBtn.isEnabled = sel != null
    }

    private fun selectEmbedModel(f: File) {
        embedFile = f
        ModelStore.setEmbedModelName(this, f.name)
        refreshEmbedList()
        Toast.makeText(this, "已选择嵌入模型 ${f.name}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 读「threads 线程数」输入框 —— **唯一**的取值处。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么要收成一个函数
     * ═══════════════════════════════════════════════════════════════════════
     * 这个数字此前在两处各写了一遍 `toIntOrNull() ?: 4`（对话模型加载、服务启动），
     * 而嵌入模型那一处**连兜底都没有** —— 它压根不读这个输入框（见 [doLoadEmbed]）。
     * 两处各写一遍的形态下，"某个入口的兜底值不是 4"是迟早的事 ——
     * 而它的症状是**同一个输入框在不同入口下行为不同**（输入框清空后，
     * 一处按 4 跑、另一处按默认核数跑），没有任何日志能分辨。
     *
     * 兜底值取 [LlmEngine.defaultThreads] 而不是 4：输入框被清空 = "我没给值"，
     * 那就该按本机核数去猜（与输入框的默认值同一个口径），而不是按一个
     * 与设备无关的常数。写死 4 的形态正是本 issue 的成因。
     */
    private fun parseThreads(): Int =
        thrEt.text.toString().trim().toIntOrNull()?.takeIf { it > 0 } ?: LlmEngine.defaultThreads()

    /**
     * 加载嵌入模型。**不碰对话模型**（双常驻的契约，由 native 侧的
     * `unloadModelInternal(E)` 保证，见 [LlmEngine.loadEmbedModel]）。
     */
    private fun doLoadEmbed() {
        val f = embedFile ?: run {
            Toast.makeText(this, "请先在上方选择嵌入模型", Toast.LENGTH_SHORT).show()
            return
        }
        if (!f.isFile) {
            embedStatusTv.text = "未加载：模型文件不存在"
            Toast.makeText(this, "模型文件不存在：${f.name}", Toast.LENGTH_LONG).show()
            return
        }
        // 与对话模型加载同一条判据：生成期换模型会让**正在跑的那一轮**用到一份
        // 被拆掉又重建的 ctx。嵌入与生成各持一份 Session，所以这里比换对话模型
        // 宽松一点（不影响对话），但索引构建期同样不该被换掉底下的模型。
        embedLoadBtn.isEnabled = false
        embedStatusTv.text = "嵌入模型加载中…"
        // ⚠ 两个 View 的值必须在**主线程**上先读完（而不是进了下面那个线程再读）：
        // `saveRagEmbedCtx()` 读的是 `ragEmbedCtxEt.text`，而 Android 的 UI 工具包
        // **不是线程安全的** —— 在后台线程读它是未定义行为（最软是读到旧值，
        // 最硬是闪退）。与 `startBuildIndex` 那里同一条纪律。
        val embedCtxForLoad = saveRagEmbedCtx()
        Thread {
            val err = try {
                // ⚠ 线程数**必须**从这里取（与对话模型同一个输入框）——
                // 此前这里不传参，走的是 `loadEmbedModel` 里写死的 4：
                // 用户在设置页把线程数从 4 调到 8，建库速度**一点都不变**，
                // 而屏幕上没有任何东西说明"这个参数对嵌入不生效"。
                // 8 核机器上这就是"一半核心永远闲着"，用户只能得出"手机就这么慢"。
                //
                // ⚠ 上下文预算同理（0.9.212）：它是"内存高"的那一格，而 native 侧
                // `nCtx <= 0` 时取默认值 —— 不传的话设置页那个框就是第二个
                // "线程数输入框"（能填、能存、对加载没用）。两个 View 的读取都在
                // **主线程**完成（`Thread {` 之前），理由见 `startBuildIndex` 那段。
                LlmEngine.loadEmbedModel(f.absolutePath, nCtx = embedCtxForLoad,
                    nThreads = parseThreads())
            } catch (t: Throwable) {
                LlmEngine.uiLog("[嵌入] 异常：${t.javaClass.name}: ${t.message}")
                "加载异常：${t.javaClass.simpleName}: ${t.message}"
            }
            ui.post {
                embedLoadBtn.isEnabled = true
                if (err == null) {
                    Toast.makeText(this, "嵌入模型已加载（n_embd=${LlmEngine.embedDim}）", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
                renderEmbedStatus()
                // 加载完成后驻留值变了：**列表标记**也必须重画，否则"在内存里"的
                // `▶` 还停在上一个模型那一行（或不出现），与实际相反。
                refreshEmbedList()
                renderLog()
            }
        }.start()
    }

    private fun doUnloadEmbed() {
        if (!LlmEngine.hasEmbedModel) {
            Toast.makeText(this, "当前没有已加载的嵌入模型", Toast.LENGTH_SHORT).show()
            return
        }
        LlmEngine.unloadEmbedModel()
        // 卸载后必须重画：状态行的读数就是本页唯一的反馈面，不重画会继续显示
        // 上一个模型的 n_embd，与"没卸载成功"完全同形。
        renderEmbedStatus()
        // 列表同理：那个 `▶` 会继续挂在已经空掉的引擎上 —— 与本 issue 对话侧
        // 卸载后不重画是同一个病。
        refreshEmbedList()
        Toast.makeText(this, "嵌入模型已卸载（对话模型不受影响）", Toast.LENGTH_SHORT).show()
    }

    // ---- 知识库（RAG 阶段 2）----
    //
    // 静默失效被这一组入口逐条终结：
    //   ① 建库时不检查嵌入模型 -> 逐条 embed 返回 null -> 整库向量全空，
    //      而"建库成功"照样返回（表现为"检索永远为空"，看不出是模型没加载）；
    //   ② 库名非法被静默改造成另一个名字 -> 用户以为在用 A 库、实际读写 B 库；
    //   ③ 开关状态只存在于内存 -> 重启后静默回到默认值，与用户上次的选择不符；
    //   ④ **编辑器不回填**（阶段 2 补充）-> 点库看不到库里的内容，重启后输入框空白，
    //      于是"维护知识库"只能靠每次重粘全量；本条与下面 ⑤ 是同一件事的两半；
    //   ⑤ **改了的正文静默消失**（阶段 2 补充）-> 切库会把编辑器整段换掉，
    //      用户改了没保存就切走，改动无声无息地没了（界面上一切正常）。

    /**
     * 刷新知识库列表。**分两层**：先出"有哪些库"（一次 listFiles，毫秒级），
     * 再补"每个库几条 · 什么状态"（每库读一次正文，几十毫秒 × 库数）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 修的是"点一个 2500 条的大库会卡住整张库列表"（连点都点不动）
     * ═══════════════════════════════════════════════════════════════════════
     * 症状是：选中一个几千条的大库之后，点别的库那一行**选不中** ——
     * 屏幕上那个 ● 一直挂在原来那个库上。而它既不是"点击没生效"，也不是"选中状态
     * 没刷新"，是**点击压根没被派发**：主线程在那一刻正忙。
     *
     * 忙在哪：这一条路上的每一步都在主线程上做 O(全库字节数) 的活。
     *   · `RagIndexStore.list` 为**每一个库**读完整的 `texts.jsonl`
     *     （[RagIndexStore.Info.entries] 是正文行数、还要算正文 hash）；
     *   · `renderRagList` 把库**逐个**画成 TextView —— 每个都建 View、量、排、画；
     *   · `renderRagStatus` 再数一次当前库的行数（0.9.195 已改成查表，见那里）。
     * 100 条短笔记（几十 KB）看不出来 —— 这段代码的注释当初就是按那个量级写的。
     * 到了 2500 条，一次刷新就是**十几 MB 的读 + 2500 行的解析 + 上千个 View**，
     * 全在一帧里。点击排在这一帧后面，于是"点不动"。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么"每次点库都刷一遍整列表"本身是错的设计
     * ═══════════════════════════════════════════════════════════════════════
     * 点库只改两件事：当前库是谁、正文框里是哪份正文。**"我有哪些库、各几条"
     * 一个字节都没变** —— 而每一次点击都把它整份重算重画。库越大，这一次白算越贵，
     * 于是"库越大越点不动"。这就是这个 bug 会反复出现的原因：上一轮把**单次刷新**
     * 变便宜了（分页 / 上限 / 快照），它只是把门槛往后推了推，**触发它的乘积还在**。
     *
     * 所以这里把两件事分开（同 [RagUiCap] 把"铺多少"与"能看到多少"拆开的思路）：
     *   · [renderRagListSkeleton] —— **只列名字**：一次 `listFiles`，
     *     与库里的字节数无关。任何一次选择/切页/提交名字都先出它，**立刻**；
     *   · [renderRagList] —— 补上"几条 · 状态"：它要逐库读正文，是贵的那一半，
     *     由 [loadRagListCounts] 在**后台**算完再补画。
     *
     * ⚠⚠ 这不是"先把旧数字画上去、等一下再换新的"（那正是本仓库最忌讳的
     *   "骗人的绿字"）：骨架那一版**一个数字都不报**。屏幕从第一帧起就没有
     *   任何一句是过期的，字是一行一行**长出来**的。
     *   同理由 `renderRagList` 只在两层里都被调用：先画名字，再画数字。
     */
    private fun refreshRagList() {
        // ── 先出骨架：只读**名字**，不碰任何正文 ─────────────────────────────
        // 代际与"能否被复用"都在渲染函数里管（[renderRagListSkeleton]）：
        // 骨架要能被下一次画骨架**直接顶掉**（它才是最新的一次取数），
        // 而 [loadRagListCounts] 那一层不许顶掉更新的那一层。
        renderRagListSkeleton()
        loadRagListCounts()
    }

    /** 画库列表的**骨架**：只有"我有哪些库"，没有条数与状态（那些是贵的那一半）。 */
    private fun renderRagListSkeleton() {
        val myGen = ++ragListGen
        val names = runCatching { RagIndexStore.liveInfos(this) }.getOrNull()
        if (myGen != ragListGen) return
        renderRagList(names.orEmpty())
    }

    /**
     * 后台算"每个库几条 · 什么状态"，算完补画一次。
     *
     * [!] 代际用的是 [ragListGen]：骨架先自增一次，于是**上一次**的补画作废
     * （它算的是用户已经离开的那一屏）。而**这一次**的骨架与补画共用同一个号
     * —— 骨架在等号后面画的，补画才被允许落到同一屏上。
     */
    private fun loadRagListCounts() {
        val myGen = ragListGen
        ioExecutor.execute {
            // ── 自愈：先把表里"与磁盘目录撞名"的坏行修掉（ISSUE #259）────────
            // 这是给被旧版本改坏过的磁盘兜底：老版本允许把 `test` 改名成 `test1`
            //（磁盘上已有目录 `test1`），盘上留下 `test<TAB>test1` 这样一行，
            // 于是两个库同名、点哪个都落到同一个目录。光挡新的改名不够 ——
            // 那份坏表还在，下次进来照样是坏的。
            // ⚠ 在**后台**做（它要 listFiles 扫目录），且**先修再列**：修完再
            //   `list` 才能立刻看到正确的显示名（否则这一次仍画成两行同名）。
            runCatching {
                val live = RagIndexStore.liveDirs(this)
                RagIndexStore.repairNames(this, live)
            }
            val rows = runCatching { RagIndexStore.list(this) }.getOrNull()
            ui.post {
                if (myGen != ragListGen) return@post
                // [!] 这两行**必须**在补画之前、且与补画同一个世代：先写"我量过了"
                //     （[ragListCounts]），再画 —— 反过来的话，[renderRagList] 会
                //     在"库里还没有这批数"的那一瞬间画出一整屏留白。
                ragListCounts = rows.orEmpty().associate { it.name to it }
                renderRagList(rows.orEmpty())
            }
        }
    }

    /**
     * 画库列表。**同一个函数画两层**（见 [refreshRagList]）：骨架那一层只有名字，
     * 补齐那一层才带"几条 · 状态"。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 数字可以缺席，**名字不许缺席**
     * ═══════════════════════════════════════════════════════════════════════
     * 两层画的是同一张列表，所以这里只有一个"要不要画数字"的分岔 ——
     * 而它必须由**数据在不在**决定，不能由"上一次画到哪儿"决定：
     *
     *   · [ragListCounts] 里**有**这个库 -> 画它的条数与状态（真值）；
     *   · 没有（骨架这一层，或那个库是新冒出来的）-> **一个数字都不报**，
     *     那一行的"几条 · 状态"整段留白。
     *
     * ⚠⚠ 留白是对的，**填一个可能过期的数才是错的**。而最容易写出的那种错是
     *   "数字还没到就先显示 0 条"：`0 条` 与"我还没量到"在屏幕上逐字相同，
     *   说出口的却是相反的结论 —— 本仓库对"两个意思挤在同一个字上"一贯是红的
     *   （同 [RagIndexStore.textsCountCapped] 的 `null` / `ragListEntries` 的说明）。
     *   用户看到一个好好的库写着"0 条"，下一步多半是去重导 —— 而导入是追加合并。
     *
     * ⚠ 判据按**库名**取，不按下标取：骨架与补齐是两次独立的取数，中间用户可能
     *   删了/加了一个库，按下标取会把 A 的条数画到 B 的名字后面。
     *   ⚠⚠ 但**这一版仍可能对不上**：库在骨架之后被改名/新建，补齐那份里就没有它
     *   （-> 留白，安全的方向）；反过来"补齐里有、骨架里没有"只会让补齐这一遍
     *   多画一行（它用的是自己的 rows）。两个方向都不报错数。
     */
    @SuppressLint("SetTextI18n")
    private fun renderRagList(rows: List<RagIndexStore.Info>) {
        // ── 条数快照：**只由完整那一层写** ───────────────────────────────────
        // [renderRagStatus] 靠它查"当前库几条"。骨架那一层的 [Info.entries] 是
        // 索引里那个数（可能落后于正文），拿它当"库里有几条"就是**报一个可能过期的数**
        // —— 所以骨架**不写**这张表（写了就等于说"我量过了"）。
        // 写点只有这一处：见 [ragListEntries] 的说明。
        val withCounts = rows.isNotEmpty() && rows.all { it.name in ragListCounts }
        if (withCounts) {
            ragListEntries = rows.associate { it.name to ragListCounts.getValue(it.name).entries }
        }
        ragListContainer.removeAllViews()
        if (rows.isEmpty()) {
            ragListContainer.addView(TextView(this).apply {
                text = "（暂无知识库：填库名 + 粘贴文本 → 点「建索引/重建索引」）"
                textSize = 12f; setTextColor(pal.textFaint)
                setPadding(0, px(4), 0, px(4))
            })
        }
        val cur = RagInject.currentKb(this)
        // ── 一屏最多画多少个库 ─────────────────────────────────────────────
        // `ragListContainer` 长在外层那个 `rootScroll`（ScrollView）里，**不做**
        // 任何虚拟化：丢进去 N 个 TextView，measure/layout 就实打实做 N 次。
        // 库数正常时（几十个）这只是常数；而"用户的库多到几百上千"不是不可能
        // （导入是按名字建库的），那时这一屏会跟着条目列表一起把一帧的预算吃光
        // —— 见 [RagUiCap] 的说明（用户报的"页面空白、什么都看不到"）。
        // ⚠ 截的**不是数据**：库还在磁盘上、还能导出；截的只是"这一次画多少个"，
        //   且下面必须把"还有多少个没画"说出来（不说话的截断才是错的）。
        // ⚠ 上限只从 [RagUiCap] 取，不在这里另写一个数（两处各写一个必然漂移）。
        val shown = if (rows.size > RagUiCap.MAX_LIBS) rows.subList(0, RagUiCap.MAX_LIBS) else rows
        for (r in shown) {
            val isCur = r.name == cur
            // 三态**必须分开说**（它们此前挤在一句话里，而处置完全不同）：
            //   · 没有索引      -> "待重建"：这个库是**导入**来的（或从未建过），
            //                     正文在、检索用不了，点「建库/重建」就好；
            //   · 有索引但不同步 -> "正文已改"：索引被指纹判为不可用，
            //                     同样是点重建，但用户还应该知道**他改过正文**；
            //   · 有索引且同步   -> 什么都不说。
            // 把前两者说成同一句话的后果：用户带着一个新导入的库去看
            // "正文已改"，然后去翻自己什么时候改过正文 —— 而他从没改过。
            // ⚠ **四**态（本轮补了 pending 那一档）：判据是 [RagIndexStore.Info.isPending]，
            //   它与 [RagIndexStore.Info.hasIndex] 是**两个问题** —— 上一版把
            //   "还没落盘"也画成"待重建"，而两者的处置完全相反：
            //   · 待重建 -> 点「建索引/重建索引」就能救回来；
            //   · 未落盘 -> 里面**一条内容都没有**，点重建只会得到"请先粘贴要索引的文本"。
            //   混成一句话的后果正是用户报的第 ③ 条疑问（"这俩按钮有啥区别"）：
            //   他在一个 0 条的库上把每个按钮都点了一遍，而屏幕上每个字都说"去重建"。
            // ⚠ 五态（本轮补了"可续"那一档）：判据收在 [RagResumeUi.state] 一处
            //   （纯逻辑，宿主单测钉得住）—— 列表与详情页不各写一句，
            //   否则同一件事在两个地方会漂成两句不同的话。
            //   "可续"与"待重建"必须分开：前者点一下只补缺口，后者整份重来，
            //   两者耗时差几十分钟，而它们是用户判断"这一下要等多久"的唯一依据。
            val state = RagResumeUi.state(
                isPending = r.isPending, hasIndex = r.hasIndex, inSync = r.inSync,
                resumable = r.resumable, resumeCount = r.resumeCount, rowsTotal = r.entries)
            val dimTxt = if (r.hasIndex) "dim=${r.dim}"
                else if (r.isPending) "还没有目录"
                else "无索引"
            // [ragListCounts] 里没有这个库 = 还没量到（见本函数的说明）——
            // 此时**一个数字都不报**，而不是回落到 `r.entries`（那是索引里那个数，
            // 保存正文之后它会落后于正文；把它画出来就是报一个过期数）。
            val counts = ragListCounts[r.name]
            val tv = TextView(this).apply {
                // 条目数读的是**正文行数**（RagIndexStore.list 的真值），不是 meta 里
                // 那个"上次重建时"的数字 —— 保存正文之后前者才是"库里有几条"。
                // 数字缺席时那一整段留白（连 `· dim` 一起）：留下 `○ kb1  · 无索引`
                // 会让"几条"读成"0 条"（少一个数，多一个句读，屏幕上是另一句话）。
                val detail = if (counts == null) ""
                    else "  ${counts.entries} 条 · $dimTxt" + state
                text = (if (isCur) "● " else "○ ") + r.name + detail
                textSize = 13f
                if (isCur) setTypeface(typeface, Typeface.BOLD)
                // [!] 颜色也要跟着那四档走：橙色 = "有东西但用不了"（待重建 / 正文改过），
                //     蓝色 = "还不是一个真库"（未落盘，与列表末尾那一行同色）。
                //     全按橙色画的后果是"未落盘"被读成"建坏了"，而它的处置只是"先把内容放进去"。
                setTextColor(
                    if (r.isPending) pal.link
                    else if (!r.hasIndex || !r.inSync) pal.warn
                    else pal.textPrimary)
                setPadding(0, px(6), 0, px(6))
                setOnClickListener { selectRagKb(r.name) }
            }
            ragListContainer.addView(tv)
        }
        // ── 新建但**还没落盘**的那个库：它必须出现在列表里 ────────────────
        // 用户的原话：*"新建库后应该在库列表里有体现，马上生成一个选项"*。
        // 在此之前它压根进不了这张列表（列表读磁盘，而它只在 [ragPendingKb] 里），
        // 于是点「新建库」之后屏幕**什么都没变** —— 与"这个按钮坏了"同形。
        //
        // [!] 画在最后、且**明说"还没有内容"**：它不是一个真实存在的库，
        //     而"列表里有一个 0 条的库"与"磁盘上真有一个空库"在屏幕上完全一样 ——
        //     后者是删不掉的幽灵（没有「删索引」的对象），前者一离开就消失。
        //     这一句（以及它的颜色）就是两者的分水岭。
        // [!] 它**不进** [ragListEntries]（那张表是"库名 -> 条数"的真值快照，
        //     由后台遍历磁盘得来）：把一个还没落盘的库塞进去，等于让状态行报出
        //     一个磁盘上不存在的库的条数。
        //   ⚠⚠ 但**已经落盘的 pending 不再画这一行**：用户报的第 ② 条
        //   （*"在库名编辑处，输入 test2，点击建库，库状态位置会出现 test2 新库，
        //   有短暂延迟"*）就是这两行**打架**的样子 ——
        //   落盘完成之前是蓝色那一行"未落盘"，落盘之后 `ragPendingKb`
        //   要等后台那次刷新回来才清，而 `list()` 已经把同一个库当成真库列出来了。
        //   于是同一个名字在列表里出现两次（一行"0 条 · 未落盘"、一行"N 条"），
        //   用户看到的"延迟"正是这两行切换的那一下。
        //   判据：**磁盘上已经有了这个库，就不再画 pending 那一行** ——
        //   pending 的全部意义就是"磁盘上还没有它"。
        if (ragPendingKb.isNotEmpty() && shown.none { it.name == ragPendingKb }) {
            val isCur = ragPendingKb == cur
            ragListContainer.addView(TextView(this).apply {
                text = (if (isCur) "● " else "○ ") + ragPendingKb +
                    "  0 条 · 未落盘（还没有目录；粘贴/导入内容后自动保存，离开即丢弃）"
                textSize = 13f
                if (isCur) setTypeface(typeface, Typeface.BOLD)
                setTextColor(pal.link)
                setPadding(0, px(6), 0, px(6))
                setOnClickListener {
                    // ⚠ 点这一行 = "切到"它，**不是**"再新建一次"。
                    //   所以已经在它上面时什么都不做（[selectRagKb] 是同一条判据）——
                    //   直接调 [switchRagDocTo] 会在"已经打开着它"时走
                    //   [openNewRagKb] 那条路，而那条路会**清空正文框**：
                    //   用户刚粘进去、还没落盘的内容就这么没了（而屏幕上看起来
                    //   只是"我又点了一下这一行"）。
                    selectRagKb(ragPendingKb)
                }
            })
        }
        // 截了就**必须说**：用户看不到某个库时会以为"它没了"，而去重新导入
        // —— 而导入是追加合并，重导等于把那些条目再加一份。
        RagUiCap.moreLibsLabel(shown.size, rows.size)?.let { msg ->
            ragListContainer.addView(TextView(this).apply {
                text = msg
                textSize = 12f; setTextColor(pal.warn)
                setPadding(0, px(4), 0, px(4))
            })
        }
        renderRagStatus()
    }

    /**
     * 点一个库：把它设为当前库，并把它的正文**回填进编辑器**。
     *
     * 这是本阶段要终结的那个现象的直接解药：在此之前点库只改了"当前库名"，
     * 编辑框里留着的还是上一个库（或空白）的内容 —— 于是"点库 1 看到的是库 2 的文本"、
     * "重启后输入框空白、点谁都没内容"，而库里其实**有**内容（`texts.jsonl` 一直在磁盘上），
     * 只是没有任何入口把它读回来。
     *
     * 有未保存改动时**先问**（见 [confirmDiscardRagDoc]）：切换会把编辑器整段换掉。
     */
    private fun selectRagKb(name: String) {
        // ⚠ 归一到**切库的唯一入口** [switchRagDocTo]。这里此前自己写了一套
        //   （`setCurrentKb` + `loadRagDoc` + `refreshRagList`），而"新建库"那条路
        //   需要的是"清空 + pending" —— 两套并存时，点列表里那个 pending 行
        //   （它不在磁盘上，`loadRagDoc` 会读出一份空的）与点一个真实库
        //   走的是同一条路，而它们该做的事不同。
        //   "已经是这个库了就不重读"那条判据也收在 [switchRagDocTo] 里
        //   （它必须在那儿 —— 那里才看得见 `forceNew`，而重读会把光标位置清掉）。
        switchRagDocTo(name)
    }

    /**
     * 把 `name` 这个库的正文读进编辑器（后台读，主线程渲染）。
     *
     * 为什么切库时**立刻清空**编辑器（而不是等读回来再替换）：库大时这一步要几十
     * 毫秒到几百毫秒，而期间编辑器里还是**上一个库的内容** —— 用户在那几百毫秒里
     * 看到的是错的，那正是"点库 1 显示库 2 的内容"那个现象本身的样子。
     * 清空至少不会把错的内容当成对的读。（"正在读取…"的提示在这里不值得加：
     * 它会让编辑器闪烁一行非正文内容，而真实等待在手机上几乎不可感知。）
     */
    private fun loadRagDoc(name: String) {
        if (!::ragDocEt.isInitialized) return
        ragDocOwner = name
        ragDocDirty = false
        // ── P1-a/P1-b：这个框不再装"整个库"，只装**当前这一页**（可编辑）────────
        // 从上往下数，旧写法做过三件事，现在只有第三件还在：
        //   ① 后台 `readEntries(...).joinToString("\n")` 拼一份 6 MB 级的全文；
        //   ② `clipDoc` 截到 4 万字符，再拼一句"框里只是头部"的说明；
        //   ③ `setText` 把那一份塞进 EditText（**全量布局**，秒级）。
        // ①②的产出只服务③，而③是"点开库要等"的那一步 —— 整条链的前提是
        // "框里要装整个库"。P1-a 把这个前提去掉了：框里装的是**一页**（60 条量级），
        // 于是③退回几十毫秒以内，①②彻底不需要。
        //
        // ⚠ 预览的画点**不在**这里，而在 [renderRagRows] 的末尾：数据源是
        //   [ragRowsShown]（`refreshRagRows` 一次 `readEntriesAligned` 读进内存的
        //   全部条目），翻页/删除之后重画预览与重画列表走的是**同一跳** ——
        //   两处各画一次必然漂移，而漂移的形态是"列表在第 3 页、框里还在第 1 页"，
        //   那正是这条改动要消掉的东西。
        // ⚠ 因此这里**不再有** `ioExecutor`：没有了"读整库 + 拼整库"这两件贵事，
        //   剩下的一次读盘在 [refreshRagRows] 里、本来就在后台。
        // [!] 先清空、再预览：这一跳与 [renderRagRows] 之间用户可能已经看到
        //   **上一个库**的预览（尤其库大时）。清空至少不会把错的当成对的读
        //   —— 与旧版"切库立刻清空"同一条理由。
        setRagDocText("")
        setRagDocSliced(true)
        // 上一片的记录随切库作废：框里现在是空的，而 [ragDocSliceRows] 还留着
        // 上一个库的行 —— 那一刻若有任何一条路按它回写，写的就是**别的库的行号**
        // （而且不报错）。它会在 [renderRagDocSlice] 里被重新填上。
        ragDocSliceRows = emptyList()
        ragDocSliceFirst = 0
        // ── 条目列表**不在这里等**（P0）──────────────────────────────────────
        // ⚠ 必须在 `ragDocOwner = name` **之后**发起：`refreshRagRows` 落地前
        //   要复核归属（`ragDocOwner != name` 就作废），归属还没写上就被它看到，
        //   这一次刷新会被自己丢掉，列表要等下一次才出来。
        // ⚠ 它同时负责**把预览画出来**（见 [renderRagRows] 末尾）：列表与预览
        //   共用这一次读盘的结果，不再各读一份。
        refreshRagRows(name)
    }

    /**
     * 刷新条目列表（后台读，主线程渲染）。
     *
     * 为什么要有一份**逐行**的列表（而不是复用库列表里那个"共 N 条"）：
     * 删除的对象是**某一行**。不给出行，用户就只能靠"删最后一条"或"整库重建"
     * 来清理一条写错的内容 —— 而"我加错了想撤掉"是编辑里最常见的动作。
     */
    private fun refreshRagRows(name: String) {
        val myGen = ++ragRowsGen
        if (name.isEmpty()) { ragRowsPage = 1; renderRagRows(emptyList()); return }
        ioExecutor.execute {
            // ⚠⚠ 必须用 [RagIndexStore.readEntriesAligned]（**保留行号**），
            //     不是 [RagIndexStore.readEntries]（把空行/坏行**跳过并压紧**）。
            //
            // 为什么这一条是"删错行"的根因：列表里每一行的「删」按下的那一刻，
            // 传出去的那个数字会被 [confirmDeleteEntry] -> [doDeleteEntry] ->
            // [RagIndexStore.removeEntry] 当成**正文文件里的行号**（它用
            // `lines[index]` 取那一行）。而 `readEntries` 返回的是**压紧后**的表：
            // 正文里只要出现一个空行或一个坏行，下标 i 就不再等于行号 ——
            // 此后界面上第 i 行显示的是**另一条**，点它的「删」，删掉的是隔壁那一行。
            //
            // 症状极其隐蔽：不崩、不报错、Toast 照样说「已删除」。而更坏的是
            // 索引侧（它按行号搬向量）与正文侧从此**整体错位一格** ——
            // 检索命中的下标与注入的正文说的不再是一件事（分数对、内容错）。
            // 空行那条会被 `lines[index].isEmpty()` 顺带挡住（表现为"点了删没反应"），
            // 而**坏行（非空但非法 JSON）挡不住**，它会真的被删掉。
            //
            // `readEntriesAligned` 正是为这件事存在的（注入侧已经在用它）：
            // 坏行/空行留 `null` 占位，**下标即行号**。这里把 `null` 也渲染成一行
            // （它是"这一行读不出来"，不是"这一条内容为空"），于是行号永远对齐。
            val rows = runCatching {
                RagIndexStore.readEntriesAligned(this, name).map { it?.text }
            }.getOrElse { emptyList() }
            ui.post {
                if (myGen != ragRowsGen) return@post      // 过期结果：用户又切了库
                if (ragDocOwner != name) return@post      // 落地前复核归属（同 loadRagDoc）
                // 页号归 1 —— 见 [ragRowsPage] 的说明（跨库继承页号会让"点一个新库"
                // 落到一个空的第 88 页上，而那一屏与"这个库是空的"逐字相同）。
                //
                // ⚠ 归 1 放在**这里**（代际判据之内）而不是 [refreshRagRows] 的开头：
                //   那里只是发起一次读，读回来之前用户可能已经点了另一个库 ——
                //   在外面归零会把这个还没落地的新库的页号归错。
                //
                // ⚠ 这一条也覆盖"删除后重画"：删掉某个库的最后一条时页号可能越界，
                //   而 [renderRagRows] 会把它钳回"还有内容的最后一页"——归 1 与钳制
                //   不冲突（归 1 在最前面发生，钳制只在渲染那一刻兜底）。
                ragRowsPage = 1
                renderRagRows(rows)
            }
        }
    }

    /**
     * 渲染条目列表。每行：序号 + 摘要 + 「删」。
     *
     * [!] 序号是**正文文件里的行号**（下标），不是"第几条非空行"。两者在正文里
     * 有空行时不相等，而用错了不会报错：删掉的是隔壁那一行，界面上只是少了一条。
     * 所以这里显式 `forEachIndexed` 记下标，而不是 `filter` 之后再数。
     */
    private fun renderRagRows(rows: List<String?>) {
        if (!::ragRowsContainer.isInitialized) return
        ragRowsContainer.removeAllViews()
        // 行号 = 下标（`readEntriesAligned` 保证）。末尾那个由结尾换行产生的空段
        // 也会占一行 —— 它没有内容，不显示在列表里（它不是"一条"，删它没有意义，
        // 而 `removeEntry` 也会以 `lines[index].isEmpty()` 拒掉）。
        ragRowsShown = rows.mapIndexed { i, t -> i to t }
        if (rows.all { it == null }) {
            ragRowsContainer.addView(TextView(this).apply {
                text = "（这个库还没有条目：在下面那个框里敲一条，点「追加」）"
                textSize = 12f; setTextColor(pal.textFaint)
                setPadding(0, px(4), 0, px(4))
            })
            // 空库也要把预览画成"空的"：不回填的话框里还留着**上一个库**的预览，
            // 而"打开了这个库"与"框里是上一个库"在屏幕上是同一屏 —— 用户会照着
            // 那份错的内容去点「删」/「追加」。预览归空是这一跳唯一安全的形态。
            renderRagDocSlice()
            return
        }
        // ── 一屏最多铺多少行：**分页**，不是截断 ──────────────────────────
        // 这一屏是用户报的那个"卡住"的正面：7021 条的一个库 = 21000 多个 View
        // （每行 LinearLayout + TextView + Button），一次 measure/layout 全在主线程、
        // 全在一帧里，而它和库列表、状态行、按钮排在同一个 `rootScroll` 里 ——
        // 前面那棵树没量完，后面的东西一个都画不出来（"页面空白"就是这个）。
        //
        // ⚠ 这里从"截断"改成了"分页"，是这一屏最关键的一处口径变化：
        //   截断只画前 N 行，**后面的一律摸不到** —— 于是 N 就不敢小
        //   （小了等于功能缩水），而 N 一大就回到卡死。分页只画当前页的 N 行，
        //   而所有页加起来覆盖全部条目 —— 于是 N 可以放心地小。
        //   用户的原话（"窗口太小，滑动找 300 条、40 万字，也是一场灾难"）
        //   说的正是"铺多少"与"能看到多少"被压在同一个数上这件事。
        //
        // ⚠ 页号先按总数**钳制**再取区间（见 [RagUiCap.rowPageRange]）：
        //   用户删条目会把当前页删空，那时最自然的落点是"还有内容的最后一页"，
        //   而不是一张空列表（空列表与"这个库空了"长得一样）。
        // ⚠ 行号 `i` 仍然是**正文文件里的行号**（下标即行号，见 `readEntriesAligned`）：
        //   分页换的是"画哪一段下标"，不是"重排行号"，所以每一行的行号与磁盘
        //   一一对应，点「删」删的就是它 —— 这一条不能因为分页而变。
        // ⚠ 翻页**不重新读盘**：[ragRowsShown] 里已经是这个库的全部条目，
        //   翻页只换一段 `subList`（见 [ragRowsPage]）。
        val range = RagUiCap.rowPageRange(ragRowsShown.size, ragRowsPage)
        // 顺手把页号落回钳制后的真值：否则"删空末页"之后这个数会停在越界值上，
        // 而每一次重画都要再钳一次 —— 两份真值迟早说不到一起。
        ragRowsPage = if (range.isEmpty()) 1 else range.first / RagUiCap.ROWS_PER_PAGE + 1
        val shownRows = if (range.isEmpty()) emptyList() else ragRowsShown.subList(range.first, range.last + 1)
        for ((i, t0) in shownRows) {
            // 空段（行尾换行产生的那一格）与"读不出来的坏行"都不给「删」按钮：
            //   · 空段不是条目，删它没有意义（`removeEntry` 也会拒）；
            //   · 坏行删掉它会让正文与索引整体错位（索引里没有它），必须先去
            //     「建库/重建」把它连同正文一起规范掉 —— 这一点写在状态行里。
            // 关键：**行号 i 仍然照原样保留**，跳过的只是"渲染这一行"，不是"重排行号"。
            if (t0 == null || t0.isEmpty()) {
                if (t0 == null) ragRowsContainer.addView(TextView(this).apply {
                    text = "${i + 1}. （这一行读不出来，索引未采用它）"
                    textSize = 12f; setTextColor(pal.error)
                    setPadding(0, px(4), 0, px(4))
                })
                continue
            }
            val t = t0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                // 摘要只取前若干字：列表是给"找到要删的那一行"用的，
                // 全文在这里没有价值（编辑器里已经能看全文了）。
                val one = t.replace('\n', ' ')
                text = "${i + 1}. " + if (one.length > 60) one.substring(0, 60) + "…" else one
                textSize = 12f
                setPadding(0, px(4), 0, px(4))
                // ── 断行策略（P3）────────────────────────────────────────────
                // 默认是 `BALANCED` + 连字符：前者为"行宽尽量均匀"多跑一遍断行
                // 计算，后者还要查连字符字典。这一行是**摘要**（60 字以内、
                // 单行高度），断得均匀没有任何价值 —— 而这一页有 60~180 个这样
                // 的 View，每个都多做一次，就是那半秒的差别。
                // `SIMPLE` = 贪心断行（一次线性扫描），够用且更快。
                setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }, lp(0, 1))
            val del = btn("删")
            del.textSize = 11f
            del.setPadding(px(8), 0, px(8), 0)
            // [!] 捕获的是**下标 i**（行号），不是"第几个视图"，也不是渲染时的行数。
            del.setOnClickListener { confirmDeleteEntry(i, ragRowsShown.getOrNull(i)?.second.orEmpty()) }
            // 上面那个 `i` 就是**正文文件里的行号**（不是列表位置）——这是全屏唯一
            // 允许构造删除行号的地方，见 [refreshRagRows] 的说明。
            row.addView(del)
            ragRowsContainer.addView(row)
        }
        // ── 翻页条 ───────────────────────────────────────────────────────
        // 只有一页时 `pageLabel` 返回 `null`：几十条的库是常态，那时"第 1/1 页"
        // 是纯噪音，而噪音会让真出现多页时的那一行也被无视。
        val label = RagUiCap.pageLabel(ragRowsShown.size, ragRowsPage)
        if (label != null) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // ⚠ 「上一页」/「下一页」按**钳制后的页号**判可用性（不是按 ragRowsPage
            //   这个字段本身）：字段可能暂时越界（刚删完末页），按它判会让两个方向
            //   同时灰掉 —— 而那时其实还有上一页可翻。
            val cur = ragRowsPage
            val pages = RagUiCap.rowPageCount(ragRowsShown.size)
            // 只画这一行文案，不画输入框：在一个小屏上给"跳页"配一个可编辑的框
            // 会让它和正文框抢焦点，而翻页的落点通常就在附近（前后来回几页）。
            bar.addView(btn("上一页").apply {
                textSize = 12f
                setPadding(px(8), 0, px(8), 0)
                isEnabled = cur > 1
                alpha = if (cur > 1) 1f else 0.4f
                setOnClickListener { gotoRagRowsPage(cur - 1) }
            })
            bar.addView(TextView(this).apply {
                text = "  $label  "
                textSize = 12f
                setTextColor(pal.textSecondary)
            }, lp(0, 1))
            bar.addView(btn("下一页").apply {
                textSize = 12f
                setPadding(px(8), 0, px(8), 0)
                isEnabled = cur < pages
                alpha = if (cur < pages) 1f else 0.4f
                setOnClickListener { gotoRagRowsPage(cur + 1) }
            })
            ragRowsContainer.addView(bar)
        }
        // 末尾再说一句"这只是一页"：翻页条说的是范围，这一句说的是**它不再是全部**
        // —— 两者缺一个，用户都会把"这一页"读成"我的库"。而**没有**这件事的代价
        // 是"找不到 -> 以为丢了 -> 重导"，而导入是追加合并，重导等于条目翻倍。
        //
        // ⚠ 措辞必须是"这一页"（[RagUiCap.rowsPageNote] 的形态），不能是
        //   "只显示了**前** N 条"：分页之后显示的可能是第 3 页的第 121–180 条 ——
        //   "前"这个字会把人引到"继续往下滑"，而列表到底了就是到底了。
        //   `shownRows.size` 恒等于这一页的大小（末页可能更短），总数是全部条目。
        RagUiCap.rowsPageNote(shownRows.size, ragRowsShown.size)?.let { msg ->
            ragRowsContainer.addView(TextView(this).apply {
                text = msg
                textSize = 12f; setTextColor(pal.warn)
                setPadding(0, px(4), 0, px(4))
            })
        }
        // ── 正文框的只读预览**跟着列表一起画**（P1-a）────────────────────────
        // 两件事在这里合一：条目列表与正文框预览说的是**同一页**（同一段行号）。
        // 所以它的画点就在这个函数的末尾 —— 列表刚按 [ragRowsPage] 钳好页、
        // 刚把 [ragRowsShown] 落定，此刻画预览读到的就是那两个真值。
        //
        // [!] 只读态以外（新建、还没落盘的库）**不画**：那时框是用户正在敲的
        //     输入框，往里面 `setText` 会把他的内容盖掉 —— 这是本仓库最贵的一类
        //     数据丢失（见 [ragDocSliced] 里那段）。
        if (ragDocSliced) renderRagDocSlice()
    }

    /**
     * 翻到条目列表的第 `page` 页。
     *
     * ⚠ **只重画，不重读盘**：[ragRowsShown] 里已经是这个库的全部条目
     *   （`refreshRagRows` 读过一次），翻页只换一段 `subList`。
     *   如果翻页走一次 `refreshRagRows`，几千条的库每翻一页都要重读一遍
     *   `texts.jsonl`（6 MB）—— 那这个功能会比它要解决的问题更慢。
     *
     * ⚠ 页号由 [RagUiCap.rowPageRange] 钳制，这里**不做范围判断**：
     *   调用处（按钮）已经按钳制后的页号判了可用性，再判一次就是两份真值。
     */
    private fun gotoRagRowsPage(page: Int) {
        val total = ragRowsShown.size
        if (total <= 0) return
        val pages = RagUiCap.rowPageCount(total)
        val next = page.coerceIn(1, pages)
        if (next == ragRowsPage) return
        ragRowsPage = next
        // 重画用的是**行号→文本**那份表，不是"当前页"——重画自己会按新页号取区间。
        // 传 `ragRowsShown` 的原始行序（`map { it.second }`）即可，行号仍是下标。
        renderRagRows(ragRowsShown.map { it.second })
        // 翻页会换掉一整屏条目 —— 把视口带回列表顶部，否则用户停在**新一页的
        // 第 40 行**上，看到的是"我翻了页但内容看着没变"（相邻页的行长得像）。
        if (::rootScroll.isInitialized && ::ragRowsContainer.isInitialized) {
            rootScroll.post { rootScroll.smoothScrollTo(0, ragRowsContainer.top) }
        }
    }

    /**
     * 「追加」：把输入框里那一条编码成向量并追加到索引末尾。
     *
     * 与「保存正文」的关键差别：**这里不碰编辑器**。追加是"库里多一条"，
     * 编辑器里那份正文是"未保存的草稿"——两者可以并存。把追加写进编辑器
     * （"顺手把它也加进文本框"）会让"用户改过的草稿"被覆盖掉，
     * 而那是本屏刚刚花力气挡住的东西。
     */
    private fun doAppendEntry() {
        if (ragBuilding) return
        val name = saveRagKbName() ?: run {
            Toast.makeText(this, "请先填一个合法的知识库名", Toast.LENGTH_SHORT).show(); return
        }
        // 追加的目标是**当前库**，不是键盘上那个还没失焦的名字 —— 但这里两者
        // 已经在 saveRagKbName 里对齐过了。真正要挡的是"库名框改成了 B、
        // 而列表里选中的还是 A"这种状态：用户以为在往 B 里加。
        if (ragDocOwner.isNotEmpty() && ragDocOwner != name) {
            Toast.makeText(this, "当前选中的是「$ragDocOwner」。要往「$name」里加，请先点列表里的那个库",
                Toast.LENGTH_LONG).show()
            return
        }
        val text = ragNewEt.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "先在上面那个框里敲一条内容", Toast.LENGTH_SHORT).show(); return
        }
        if (!RagIndexStore.isPresent(this, name)) {
            Toast.makeText(this, "「$name」还没有索引：先填正文点「建索引/重建索引」", Toast.LENGTH_LONG).show()
            return
        }
        if (!LlmEngine.hasEmbedModel) {
            Toast.makeText(this, "尚未加载嵌入模型：追加要编码这一条", Toast.LENGTH_LONG).show()
            return
        }
        ragBuilding = true
        renderRagStatus()
        ragStatusTv.text = "追加中…（只编码这一条）"
        Thread {
            val err = try {
                LlmEngine.addEntry(this, name, text)
            } catch (t: Throwable) {
                LlmEngine.uiLog("[知识库] 追加异常：${t.javaClass.name}: ${t.message}")
                "追加异常：${t.javaClass.simpleName}: ${t.message}"
            }
            ui.post {
                ragBuilding = false
                if (err == null) {
                    // 成功才清输入框：失败时留着，用户可以直接改一处再点一次。
                    ragNewEt.setText("")
                    // 追加不改编辑器里的草稿 -> 磁盘上的正文与编辑器里那份**不再相同**，
                    // 也就是说"保存正文"的脏标记语义还在，但库里已经多了一条。
                    // 状态行会同时说清这两件事（见 renderRagStatus 的条目数）。
                    Toast.makeText(this, "已追加 1 条到「$name」", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
                renderLog()
                refreshRagList()
                // 追加**没有**动 `ragDocOwner` 那份正文（编辑器是独立的草稿），
                // 所以这里按**归属**刷条目列表，而不是按输入框。
                refreshRagRows(ragDocOwner.ifEmpty { name })
                renderRagStatus()
            }
        }.start()
    }

    /**
     * 删除某一行的确认框。
     *
     * 为什么必须确认：删除是**唯一不可撤销**的动作（没有回收站，正文只留一份），
     * 而列表里的每一行都只显示前 60 字 —— 用户完全可能点错隔壁那一行。
     * 确认框里把**原文全文**亮出来，让"我要删的是不是这一条"可判定。
     */
    private fun confirmDeleteEntry(index: Int, text: String) {
        val name = ragDocOwner.ifEmpty { RagInject.currentKb(this) }
        if (name.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("删除第 ${index + 1} 条？")
            // 预览截断走 [TextBoundary.clipAtCharBoundary]（按代理对安全），**不**用裸
            // `substring(0, 300)`：切在 emoji 中间会留下一个孤立高代理，确认框里显示成 `?`
            // —— 而用户正是靠这段预览判断"要删的是不是这一条"。纯显示问题（不落盘），
            // 与 A4 同源，一并跟上同一条纪律。
            .setMessage((if (text.length > 300) TextBoundary.clipAtCharBoundary(text, 300) + "…" else text) +
                "\n\n（正文与向量一起删掉，后面的行号前移一位；不可撤销）")
            .setPositiveButton("删除") { _, _ -> doDeleteEntry(name, index) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 后台删除一行，回到主线程刷新。 */
    private fun doDeleteEntry(name: String, index: Int) {
        if (ragBuilding) return
        ragBuilding = true
        renderRagStatus()
        Thread {
            val err = try {
                LlmEngine.deleteEntry(this, name, index)
            } catch (t: Throwable) {
                LlmEngine.uiLog("[知识库] 删除异常：${t.javaClass.name}: ${t.message}")
                "删除异常：${t.javaClass.simpleName}: ${t.message}"
            }
            ui.post {
                ragBuilding = false
                if (err == null) Toast.makeText(this, "已删除第 ${index + 1} 条", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                renderLog()
                refreshRagList()
                // ── 删成功时**不重读盘**（P3）────────────────────────────────
                // 删除是"某一行没了、其后每一行前移一位"，而这一刻这件事是
                // **已知的** —— 磁盘上的新正文就是"删掉那一行再拼回去"，没有
                // 任何需要重新猜的东西。再 `refreshRagRows` 一次 = 把 6 MB 的
                // `texts.jsonl` 重新读一遍 + 重新解析 + 重铺 180 个 View，
                // 只为得出一个我们已经知道的表。
                //
                // ⚠ 只在**成功**那一支走本地推算：失败时磁盘没动，本地那份仍然
                //   是对的（什么都不用做），而"照旧重读"会把一次失败变得比成功还贵。
                if (err == null) dropRowLocally(name, index)
                renderRagStatus()
            }
        }.start()
    }

    /**
     * 「第 `index` 行刚被删掉」之后，**本地**把条目表与这一页改对（不重读盘）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么"重读一遍"是错的
     * ═══════════════════════════════════════════════════════════════════════
     * [RagIndexStore.removeEntry] 做的是一件**完全确定**的事：把 `lines[index]`
     * 拿掉、其后每一行前移一位、重写正文与向量。于是"删完之后磁盘上是哪张表"
     * 根本不需要读 —— 它就是"把本地这张表按下标 `index` 摘掉一格"。
     * 重读一次是拿 6 MB 的读盘换一个已知的答案，而这段时间主线程上还排着
     * 用户的下一步操作（这一族 bug 的第一形态就是"点了没反应"）。
     *
     * ⚠⚠ 本地推算**只有在本地那份与磁盘逐行一致时才成立**，所以这里先验不变量：
     *   下标即行号（`i == it.first`）—— 那是 [refreshRagRows] 用
     *   `readEntriesAligned` 建表时就钉住的（错位的话"删错行"那个老缺陷就回来了）。
     *   对不上就**退回重读**：宁可慢一次，绝不按一份来路不明的表推算行号
     *   —— 错行号的症状是"删掉的是隔壁那一行"，而屏幕上一切正常。
     *
     * ⚠ 重画的是**这一页**（与翻页同一条路，`ragRowsShown` 已在内存里），
     *   不是"只摘掉一个 View"：后面那些行的标号都得跟着前移（第 5 条变第 4 条），
     *   逐 View 改标号与重建这一页是同一件事，但前者多一份"哪些 View 对应哪些行"
     *   的账 —— 而这份账一旦记错，屏幕上显示的行号与点「删」传出去的行号就分家了。
     *   顺带页号也要重钳（删空末页时落在"还有内容的最后一页"），这也在重画里。
     */
    private fun dropRowLocally(name: String, index: Int) {
        if (ragDocOwner != name) return
        // 推算本身在 [RagUiCap.dropRowAt]（纯逻辑，宿主侧可单测）：它**顺带验了**
        // "下标即行号"那条不变量 —— 对不上返回 `null`，这里退回重读。
        // ⚠ 这条不变量是"删错隔壁那一行"那个老缺陷的正面判据，不是可选项：
        //   把两个动作合进一个函数的理由就是**不许有人只做"摘格子"而忘了验对齐**。
        val next = RagUiCap.dropRowAt(ragRowsShown, index)
        if (next == null) { refreshRagRows(name); return }
        ragRowsShown = next
        renderRagRows(ragRowsShown.map { it.second })
    }

    /**
     * 程序化写编辑器（不置脏、不触发用户编辑的判据）。
     *
     * @param clipped 这一份是不是**被截过的头部**。它必须由调用方传，不在函数里猜 ——
     *   "框里的字比某个数少"判不出来（一篇短的正文与一份被截的头部看起来一样），
     *   而猜错的代价是：要么拿一个缺了尾巴的库去建索引（静默丢内容），
     *   要么对一份完整的正文报"这不是全部"（假警告）。两者都比"多传一个参数"贵。
     */
    private fun setRagDocText(text: String, clipped: Boolean = false, fullChars: Int = 0) {
        if (!::ragDocEt.isInitialized) return
        ragDocLoading = true
        try { ragDocEt.setText(text) } finally { ragDocLoading = false }
        ragDocClipped = clipped
        // 没截时清零：留着上一次那个数会让“没截”的那一屏回执说出上一份文档的长度。
        ragDocFullChars = if (clipped) fullChars else 0
        // 按钮上的字符数与"只是前一部分"那个旗标刚变了，跟着重画一次 ——
        // 不在这里画的后果是：换库之后按钮还报着**上一个库**的字符数，
        // 而它与收回起态一起出现在屏幕上时，没有任何现象指向"这个数是旧的"。
        paintRagDocFold()
    }

    /**
     * 把正文框切成「**一片**（磁盘上这个库的第 k 页）」/「整框（新建、未落盘）」
     * 两态之一（P1-a 立的开关，P1-b 改了它的**含义**）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * P1-b 改了这里什么：框**不再是只读的**
     * ══════════════════════════════════════════════════════════════════════
     * P1-a 把框设成只读，是因为旧版「保存正文」会拿框里的内容**整份覆盖**
     * `texts.jsonl` —— 而框里只有一页，那一次覆盖会把整个库砍成一页。
     * 挡它的办法当时是"不许编辑"。
     *
     * P1-b 把挡法换掉了：框**可以编辑**，但保存走 [RagIndexStore.replaceRowsInPlace]
     * ——按行偏移只回写**改过的那几行**，其余行、其余页的真值仍来自磁盘。
     * 于是"编辑"这件事不再需要被禁掉：它不再有"拿一页覆盖全库"的可能。
     *
     * ⚠ 这就是为什么这个开关的两个状态**都**必须可编辑，而两态的差别只在
     *   "框里装的是一页（磁盘上有源）"还是"框就是全部（磁盘上没有源）"——
     *   出错的从来不是"能不能敲"，是"敲完之后拿哪一份当真值"。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么两态的输入属性仍然收在**一个**开关里
     * ══════════════════════════════════════════════════════════════════════
     * 可输入性由 `keyListener` / `inputType` / `setSingleLine` 合起来表达。
     * 分开各设一次 —— 后果是**半可输入**：框看着能敲，换个入口（点一下、长按、
     * 回车）又不行，而 [doSaveTexts] 那条判据已经在按"框能改"行事了。
     * 所以写点收在这一处，进/出各调一次。
     *
     * @param slice `true` = 框装的是磁盘上那个库的**一页**；`false` = 框就是全部
     *              （新建、还没落盘的库 —— 磁盘上没有任何副本可以拿来做源）
     */
    private fun setRagDocSliced(slice: Boolean) {
        if (!::ragDocEt.isInitialized) return
        ragDocSliced = slice
        // 两态都**可编辑**（P1-b）—— 见上面那段。区别不在输入属性上，
        // 在"取真值的方向"上（那一处收在 [doSaveTexts] / [ragDocEntries] 的判据里）。
        ragDocEt.setFocusable(true)
        ragDocEt.setFocusableInTouchMode(true)
        ragDocEt.setCursorVisible(true)
        // **必须**把 keyListener 与 inputType 一起放回去 —— 只放 `inputType`
        // 而不换 keyListener 的写法在这里是恒无效的（`setKeyListener(null)`
        // 之后 `inputType` 不再驱动可输入性）。P1-a 曾经把 keyListener 置 null
        // 来立"只读"，P1-b 之后**这一个动作整个去掉**：留着它就会造出
        // "属性说可编辑、实际敲不进去"的半态 —— 而它正是本屏最贵的那种死路。
        ragDocEt.keyListener = android.text.method.TextKeyListener.getInstance()
        ragDocEt.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        ragDocEt.setSingleLine(false)
        // ⚠⚠ **必须**在最后重画一次折叠状态 —— 这是 ISSUE #228 第 ② 条的修复点。
        //
        //     [ragDocEt] 是在 [field] 里用 `setSingleLine(true)` 建出来的（它给
        //     单行输入框用），而**上面那一句** `setSingleLine(false)` 会顺手把
        //     `maxLines` 复位成「不限」（Android 的 `applySingleLine` 语义：
        //     关掉单行 = 回到 `Integer.MAX_VALUE`）。
        //
        //     于是时序上出现了一次**覆盖**：
        //       setRagDocText(...)   // 内部 paintRagDocFold()：收起态 maxLines = 3  ✅
        //       setRagDocSliced(...) // setSingleLine(false)：把 maxLines 打回 MAX_VALUE ❌
        //     净效果：字段 [ragDocCollapsed] 是 `true`（按钮写着「▶ 展开正文框」、
        //     说明行写着「正文框已收起」），而框**在屏幕上其实是展开的**——
        //     两个信号打架，用户看到的那个（框高）说"展开"。
        //
        //     症状正是用户报的「点两次才收得起来」：第一次点 `setRagDocCollapsed(false)`
        //     （因为字段还说收起），框"展开"（本来就没收起 -> 看不出变化）；
        //     第二次才真的 `setRagDocCollapsed(true)` 把它收掉。
        //
        //     [!] 修法是把"谁最后说话"固定下来：输入属性设完之后，**折叠状态必须是
        //     最后一笔**。反过来（在 setRagDocSliced 之前画）都修不掉，因为
        //     `setSingleLine(false)` 恒在最后覆盖它。判据见
        //     `tools/rag_doc_fold/check_collapse_after_sliced.py`（钉的是**次序**）。
        paintRagDocFold()
    }

    /**
     * 按当前页把正文框画成**这一片的可编辑内容**（P1-b；P1-a 时是只读预览）。
     *
     * 装的是 [RagUiCap.docPreviewRange] 那一段（与条目列表**同一页、同一段行号**），
     * 拼法走 [RagUiCap.docPreviewText]（保留占位行 —— 行号必须与列表逐行对齐）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 它同时把"这一片是什么"记下来（[ragDocSliceRows] / [ragDocSliceFirst]）
     * ══════════════════════════════════════════════════════════════════════
     * 保存时 [RagUiCap.docEditPlan] 要拿"画出去的那一份"与"框里现在的"逐行比，
     * 而它需要两个数：每一行**原来**是什么、第 0 行对应**正文文件**的哪一行。
     * 这两个数只在此处、只在这一跳被写 —— 且与 `setText` **同一次**（分开写就会
     * 出现"框里是第 2 页、记录说第 1 页"，而回写落在错的行上、不报错）。
     *
     * ⚠ 它**不做任何 IO**：数据来自 [ragRowsShown]（`refreshRagRows` 已读进内存的
     *   全部条目）。翻页/删除之后重画这一片就走这一条 —— 一个磁盘调用都没有。
     */
    private fun renderRagDocSlice() {
        if (!::ragDocEt.isInitialized) return
        val total = ragRowsShown.size
        val range = RagUiCap.docPreviewRange(total, ragRowsPage)
        val rows = if (range.isEmpty()) emptyList()
                   else ragRowsShown.subList(range.first, range.last + 1).map { it.second }
        // 先记"这一片是什么"，再 setText —— 顺序反了的话，`setText` 触发的
        // TextWatcher 会看到一份还没落定的记录（虽然它此刻不读那两项，
        // 但把顺序固定下来，下一个往 TextWatcher 里加判据的人不会踩到它）。
        ragDocSliceRows = rows
        ragDocSliceFirst = if (range.isEmpty()) 0 else range.first
        setRagDocText(RagUiCap.docPreviewText(rows))
        setRagDocSliced(true)
        if (::ragDocPreviewNoteTv.isInitialized) {
            val note = RagUiCap.docEditableNote(total, ragRowsPage)
            val body = note ?: ""
            if (ragDocPreviewNoteTv.text?.toString() != body) ragDocPreviewNoteTv.text = body
            val vis = if (note == null) View.GONE else View.VISIBLE
            if (ragDocPreviewNoteTv.visibility != vis) ragDocPreviewNoteTv.visibility = vis
        }
    }

    /**
     * 收起 / 展开正文框。**只改显示**，不动一个字符。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么"收起"必须是 `maxLines`，而不是 `visibility = GONE`
     * ══════════════════════════════════════════════════════════════════════
     * `GONE` 让这个框从屏幕上**消失**，而它与"这份内容不在了"长得一模一样 ——
     * 本仓库对"静默变少"的判据一向是硬的（见 [RagUiCap.docTruncatedOnDiskNote]
     * 那一族：一个不说话的截断与在别处一样坏）。留 3 行
     * （[RagUiCap.DOC_COLLAPSED_LINES]）的效果是：**看得见里面有东西**，
     * 只是不占那十几屏。
     *
     * `maxLines` 顺带给了框自己的滚动条（`EditText` 超出行数即可内部滚动）——
     * 收起之后想在框里翻一下也还行，而不是必须先展开。
     *
     * [!] 这里的 `maxLines = Int.MAX_VALUE` 是"不限"而不是"随便给个大数"：
     *     Android 的 `setMaxLines` 语义就是上限，`Int.MAX_VALUE` 等于回到
     *     `WRAP_CONTENT` 的原样高度（与从未折叠过的框逐字相同）。
     */
    private fun setRagDocCollapsed(collapsed: Boolean) {
        ragDocCollapsed = collapsed
        paintRagDocFold()
    }

    /**
     * 按 [ragDocCollapsed] 把按钮文案、说明行、以及正文框的高度画出来。
     *
     * 三个数都必须来自**同一处真值**（框里现在有多少字符 = `ragDocEt.text.length`，
     * 以及旗标 [ragDocClipped]）：按钮上那句、说明行那句、框本身的高度
     * 说的是同一件事 —— 分开各写一份必然漂移，而漂移的形态是
     * "按钮说 88 条、框里其实已经被截掉一半"（本仓库最忌讳的那类）。
     *
     * ⚠ 它**不碰 text**：整个函数的全部写入只有 `maxLines` / 两个文案 /
     *   说明行的可见性。收起一次就丢内容，是这一处唯一不可接受的失效形态。
     */
    private fun paintRagDocFold() {
        if (!::ragDocEt.isInitialized) return
        val chars = ragDocEt.text?.length ?: 0
        // 收起态下框要能看见"里面还有东西"，所以保留 [DOC_COLLAPSED_LINES] 行；
        // 展开时回到不限高（= 与从未折叠过的框完全一致）。
        val lines = if (ragDocCollapsed) RagUiCap.DOC_COLLAPSED_LINES else Int.MAX_VALUE
        // 同上面那条：`setMaxLines` 也会走一次 requestLayout，值没变就别碰
        // （本函数在每一次敲键时都会被调到）。
        if (ragDocEt.maxLines != lines) ragDocEt.maxLines = lines
        // 竖滚动条只在收起时显示：展开时它是 WRAP_CONTENT、永远不需要滚，
        // 而常驻的滚动条会给"这一页还能往下滚"一个假信号（真正的滚动源是外层 rootScroll）。
        if (ragDocEt.isVerticalScrollBarEnabled != ragDocCollapsed) {
            ragDocEt.isVerticalScrollBarEnabled = ragDocCollapsed
        }
        // ⚠ 只在**文案真的变了**时才赋值。本函数会被 [ragDocEt] 的 TextWatcher 每一次
        //   敲键调到（"只是前一部分"那个旗标跟着编辑走），而 `setText` 即使内容相同
        //   也会走一次 requestLayout/invalidate —— 在输入框里每敲一个字重排两个
        //   TextView，是那种"单看都对、加起来让打字变涩"的开销。
        if (::ragDocFoldBtn.isInitialized) {
            val label = RagUiCap.docToggleLabel(ragDocCollapsed, chars, ragDocClipped)
            if (ragDocFoldBtn.text?.toString() != label) ragDocFoldBtn.text = label
        }
        if (::ragDocFoldNoteTv.isInitialized) {
            val note = if (ragDocCollapsed) RagUiCap.docCollapsedNote(chars, ragDocClipped) else null
            val body = note ?: ""
            if (ragDocFoldNoteTv.text?.toString() != body) ragDocFoldNoteTv.text = body
            val vis = if (note == null) View.GONE else View.VISIBLE
            if (ragDocFoldNoteTv.visibility != vis) ragDocFoldNoteTv.visibility = vis
        }
    }

    /**
     * 编辑器里的内容 → 条目列表。**一行一条**，空行丢弃（与建库同一口径）。
     *
     * 来源标签统一取 `粘贴-<库名>`：编辑框里只有正文，没有"来源"这一列 ——
     * 让用户在多行文本框里再维护一列来源，比"来源永远是粘贴"更容易出错。
     * 阶段 3 做「追加/删除单条」时会带上来源参数（那时来源才有第二个取值）。
     */
    private fun ragDocEntries(name: String): List<RagIndexStore.Entry> {
        // ══════════════════════════════════════════════════════════════════════
        // P1-b：切片态下这个函数**必须返回空**（它是一个"整库"的取数口）
        // ══════════════════════════════════════════════════════════════════════
        // 这个函数的名字是"编辑器里的内容",而它的**语义**一直是"要写成整个库的
        // 那份内容"（调用方：`runRagSave` / `autosavePendingKb` / `importEntries`，
        // 三个都是"整份落盘"）。框缩成一页之后，这个语义**不再成立**：
        // 框里那一页**不是**整个库，把它当整个库写下去 = 把库砍成一页。
        //
        // ⚠ 所以这里不是"多一个判据"，而是把这条路的**入口堵死**：切片态下
        //   返回空 -> 三个调用方各自以"没有内容"收场（`docs.isEmpty()` 早就
        //   有分支），而不是拿着**一页**去整份覆盖。这比"在每个调用点上判一次"
        //   可靠：调用点会随功能增加，而这里只有一处。
        //   本仓库对"同一判据要在 N 处记得写"的结论一向是"必然会漂移"。
        //
        // [!] 切片态下"要索引/要落盘"的两条正路都**不经过这里**：
        //   · 建索引 -> [doBuildIndex] 走 [startBuildDisk]（读磁盘）；
        //   · 改字   -> [saveRagDocSlice] 走 [RagIndexStore.replaceRowsInPlace]。
        //   所以这一次返回空**不会**让任何一条正路断掉 —— 它只断掉"拿一页当全库"
        //   那条路。这条判据由 `run_rag_doc_preview_guard.sh` 正面钉住。
        if (ragDocSliced) return emptyList()
        return ragDocEt.text.toString().split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { RagIndexStore.Entry(it, "粘贴-$name") }
    }

    /**
     * 有未保存改动时先问再切/再走。`proceed` 是"确实要丢掉改动"之后要做的事。
     *
     * 为什么必须有这一步：切库会把编辑器**整段换掉**。没有它时，用户改了正文又顺手
     * 点了另一个库 —— 改动静默消失，而界面上一切正常（新内容、新状态行），
     * 没有任何迹象说明"刚才那几行没存"。这是本屏最贵的一种数据丢失。
     */
    private fun confirmDiscardRagDoc(action: String, proceed: () -> Unit) {
        if (!ragDocDirty) { proceed(); return }
        // 归属为空 = 编辑器里那份正文不属于任何库（还没建过库就敲了字）。
        // 此时**照样要问**：不问的话，用户敲的那些字在切库时无声无息地没了。
        // 只是没有"保存"这个选项 —— 没有目标库，保存到哪里都是错的。
        val owner = ragDocOwner
        val b = android.app.AlertDialog.Builder(this)
        if (owner.isEmpty()) {
            b.setTitle("编辑器里有未保存的内容")
                .setMessage("$action 会丢弃这些内容。它们还不属于任何知识库 —— " +
                    "要保留请先取消，填好库名后点「建索引/重建索引」。")
                .setNeutralButton("丢弃") { _, _ ->
                    ragDocDirty = false
                    proceed()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // ── 「保存」在这条路上走哪一条，取决于这个库**落盘了没有** ──────────────
        // ⚠ 新建的库（[ragPendingKb]）**没有索引**，而 [runRagSave] 底下的
        //   `LlmEngine.saveTexts` 明确要求"库里已有索引"（它是"改写一个已存在的库"
        //   那条路）。拿它去存 pending 会得到一句"该库还没有索引：先点「建库/重建」"
        //   —— 而「建库/重建」对一个还没落盘的库同样走不通（它要拿正文去编码，
        //   而那条路要求先确认"这份正文属于谁"，pending 的归属还没有磁盘实体）。
        //   于是"保存"这个按钮会变成一条死路：用户点它、失败、再点取消、
        //   改动还在、他还是走不了。这正是本仓库反复收口的"看起来能用但用不了"。
        //
        //   所以 pending 的"保存"落到 [autosavePendingKb] 那条路（`importEntries`，
        //   与「导入库」同一个函数、同一种落盘形态）。这也是用户那句
        //   "执行了手动输入（粘贴）……那么库应该立即保存"在这里的兑现：
        //   用户在切走之前点「保存」，就是把那份内容落成一个真库。
        val pending = ragPendingKb.isNotEmpty() && ragPendingKb == owner
        // ── P1-b：「保存」在**切片态**下不再是 [runRagSave]（整份覆盖）────────────
        // 这个 `else` 分支（既不是 pending、又脏了）在 P1-b 之后**只可能**
        // 落在"磁盘上有个库、框里是它的一页"这一种情形上 —— 而那正是"整份覆盖
        // = 把库砍成一页"的那条路。所以这里改走 [saveRagDocSlice]（按行偏移回写）。
        //
        // ⚠ 这与 [doSaveTexts] 里那条判据是**同一个判据**，但它在这里更隐蔽：
        //   用户点的是"切走/丢弃前的保存"，他不会想到这一步会**整份重写**
        //   正文。两处都要挡，挡法必须是同一条路（否则一处挡一处漏，
        //   而漏的那一次恰好在"用户以为自己在丢内容"的时候）。
        b.setTitle("「$owner」有未保存的改动")
            .setMessage(if (pending)
                "$action 会丢弃这些改动。这是一个**新建的库**，还没有落盘。" +
                    "要先保存吗？\n\n（保存 = 把这个库建出来：写正文，不建索引；" +
                    "检索要等「建索引/重建索引」后才生效）"
            else if (ragDocSliced)
                "$action 会丢弃这一页里未保存的改动。要先保存吗？\n\n" +
                    "（保存**只写这一页改过的那几行**，其余页一个字节都不动；" +
                    "改动过的条目要等「建索引/重建索引」后才按新内容检索）"
            else "$action 会丢弃这些改动。要先保存吗？\n\n" +
                "（保存只写正文，不重算向量；检索要等「建索引/重建索引」后才生效）")
            .setPositiveButton("保存") { _, _ ->
                if (pending) {
                    // 立刻落盘（不等聚拢窗口：用户已经明确说了"要保存"）。
                    // 代际先自增一次，让**已经排在队列里**的那一跳作废 ——
                    // 它写下的是一份与用户此刻点「保存」时不同的内容。
                    val myGen = ++ragPendingSaveGen
                    val docs = ragDocEntries(owner)
                    autosavePendingKbNow(owner, docs, myGen) { proceed() }
                } else if (ragDocSliced) {
                    // 切片态：按行偏移回写**这一片**（不许整份覆盖），落完再 proceed。
                    // ⚠ `proceed` 必须被走到（用户点的是"保存并切走"）：卡在弹框上
                    //   比丢掉这次编辑更坏 —— 与 [autosavePendingKbNow] 同一条口径。
                    val plan = RagUiCap.docEditPlan(
                        ragDocSliceRows, ragDocSliceFirst, ragDocEt.text?.toString().orEmpty())
                    if (plan == null) {
                        // 算不出计划（行数变了 / 改到坏行）：这次改动**存不了**，
                        // 于是只能丢 —— 但必须说出来，不能静默。
                        Toast.makeText(this,
                            "这一页的改动没法按行写回（只支持改字、不支持增删行）—— " +
                                "这次改动已丢弃。要补/删条目请用「追加」或列表里的「删」",
                            Toast.LENGTH_LONG).show()
                        ragDocDirty = false
                        proceed()
                        return@setPositiveButton
                    }
                    runRagSliceSave(owner, plan) { proceed() }
                } else {
                    val docs = ragDocEntries(owner)
                    runRagSave(owner, docs) { proceed() }
                }
            }
            .setNeutralButton("丢弃") { _, _ ->
                ragDocDirty = false
                proceed()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「保存」被显式点下时的那条落盘（不经过聚拢窗口），落完再执行 `proceed`。
     *
     * 与 [autosavePendingKb] 的分工：那个是"用户停手了，顺手存一下"（失败只提示），
     * 这个是"用户点了保存"（**必须**在结束前走完 `proceed`，否则切库那一步永远不发生
     * —— 而用户已经点过「保存」，他会以为 App 卡在弹框上）。
     */
    private fun autosavePendingKbNow(name: String, docs: List<RagIndexStore.Entry>,
                                     myGen: Int, proceed: () -> Unit) {
        if (docs.isEmpty()) { proceed(); return }
        if (ragBuilding) { proceed(); return }
        ragBuilding = true
        ioExecutor.execute {
            val written = runCatching {
                RagIndexStore.importEntries(this, name, docs)
            }.getOrDefault(-1)
            ui.post {
                ragBuilding = false
                if (written > 0) {
                    LlmEngine.uiLog("[知识库] 新库「$name」已落盘：$written 条（手动保存）")
                    ragDocDirty = false
                    pendingKbLanded()
                    renderRagStatus()
                    refreshRagRows(name)
                    Toast.makeText(this, "「$name」已保存（$written 条）—— 检索需「建索引/重建索引」",
                        Toast.LENGTH_LONG).show()
                } else {
                    // 落盘失败时**照旧往下走**（用户点的是"保存并切走"，卡在弹框上
                    // 比丢掉这次编辑更坏），但必须说清它没存住。
                    Toast.makeText(this, "「$name」保存失败（存储空间？）—— 这次改动没有写进磁盘",
                        Toast.LENGTH_LONG).show()
                }
                proceed()
            }
        }
    }

    /**
     * 把一条**进度回执**画到状态行上（不是日志，见 [LlmEngine.uiProgress]）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 它修的是"界面卡住、点一下闪退"里那个**观感**的一半
     * ═══════════════════════════════════════════════════════════════════════
     * 一份 3 MB 的整篇建库要切成上万块、逐条编码几十分钟，而这段路以前在屏幕上
     * 只有一句静态的「正在建库…」。用户得出的结论只能是"卡死了" —— 于是他去点屏幕，
     * 点在滑动中的列表上，系统把这一串触摸当成 fling，而每个触摸都要走一遍
     * 已经排了几千个子 View 的布局树，最后 OOM / 闪退（用户报的原文）。
     *
     * 一个**走得动的计数**就把"卡死"与"慢"分开：前者没有出路，后者等就是。
     * 两者的处置完全不同，而它们在屏幕上此前长得一模一样。
     *
     * [!] 只碰这一个 TextView（不 renderLog、不刷列表、不碰条目容器）：
     *     这几样各自是一次全量重排，而本函数每秒会被叫四次、持续几十分钟。
     * [!] 用**全量替换**而不是追加：进度是"现在到哪儿了"，不是一串要留痕的历史。
     */
    private fun updateProgressLine(line: String) {
        if (!::ragStatusTv.isInitialized) return
        val body = line.removePrefix(PROGRESS_PREFIX).trim()
        // 记下最后读数：切页那一跳要原样带出来（见 lastProgressBody 的说明）。
        // ⚠ 先记再判 `isInitialized` 之外的一切：这一行是**读数**，与"屏幕
        //    上那个 TextView 现在在不在"无关。
        lastProgressBody = body
        // 切页回来 / 建库回调重画之后，那一跳写的是"进度见日志"这句**没有读数**的
        // 兜底话（见 selectTab）—— 而下一行回执会在 ≤250 ms 内把它换掉。
        // 这里不做"哪一句更好"的判断：回执本身就是**最新**的读数，直接覆盖。
        ragStatusTv.text = buildString {
            append(PROGRESS_PREFIX)
            append(body)
            // 中途提醒用户**别动**：见上面那段关于 fling 的说明 ——
            // 这条路的每一次触摸都要穿过一棵已经很大的布局树。
            append("　·　慢是正常的，请等它走完（不要切页/滑动）")
        }
        ragStatusTv.setTextColor(pal.warn)
    }

    /**
     * 知识库状态行。
     *
     * 判据全部读**引擎/存储真值**（库列表、嵌入是否驻留），不读本页变量：
     * 本页变量只能证明"我以为如何"，而这一屏最容易出现的困惑恰恰是
     * "我点了建库，到底建上了没有 / 为什么检索没内容"。
     */
    @SuppressLint("SetTextI18n")
    private fun renderRagStatus() {
        if (!::ragStatusTv.isInitialized) return
        val on = RagInject.enabled(this)
        val kb = RagInject.currentKb(this)
        val embedOk = LlmEngine.hasEmbedModel
        val exists = kb.isNotEmpty() && RagIndexStore.indexFile(this, kb)?.isFile == true
        // 正文在不在 —— 与"有没有索引"是**两个问题**。只有正文 + 没有索引 = 待重建，
        // 那是**故意**存在的状态（`importEntries` 就是那样落盘的），不是"这个库不存在"。
        // 此前状态行只问前者（`exists`），于是"导进一个新库"这件事在状态行上
        // 被读成"该库没有索引（需建库）"—— 那句话把用户引去点「建库/重建」，
        // 而**这一步本身没错**，错的是它同时把"库确实建好了、正文确实写进去了"
        // 这件事实挡在了后面（用户看到列表里没有这个库，只能怀疑导入没成功）。
        val hasTexts = kb.isNotEmpty() && RagIndexStore.isPresent(this, kb)
        // 正文与索引是否一致 —— 只有索引在时问才有意义（否则"不一致"是废话）。
        // 走的是与检索路径**同一对判据**（textsInSync），不是另写一个近似：
        // 状态行说"就绪"而检索实际上不生效，比没有状态行更坏。
        //
        // [!] 这一次取值会**在主线程上读一次索引头 + 正文文件**（100 条短笔记 = 几十 KB，
        //     毫秒级）。为什么不丢到后台：状态行是"我点了之后，到底成没成"的唯一反馈面，
        //     异步渲染会让它慢半拍地显示上一步的结论 —— 那正是"骗人的绿字"的来源。
        //     真要放大到几千条时，这里应当改成"后台算、状态行显示上一次的结果 +
        //     一行『计算中』"，而不是悄悄让它变慢。
        val inSync = exists && RagIndexStore.textsInSync(this, kb)
        // ── 条数：**查表**，不再读盘 ────────────────────────────────────────
        // [RagIndexStore.textsCount] 会把整份 `texts.jsonl` 读进内存再 `split('\n')`，
        // 而它此前在这一条路径上被调了**两次**（"待重建"那一支一次、末尾那条又一次）。
        // 100 条短笔记（几十 KB）看不出来 —— 上面那段代码的注释当初就是按那个量级
        // 写的，它甚至预先写下了"真要放大到几千条时，这里应当改成…"。这一次真的放大了：
        // 用户报的现场是一个 7021 条、6 MB 的库，而 [renderRagStatus] 会被库列表刷新、
        // 切库、导入、建库结束一路调用 —— 于是每次重画都要在**主线程**上读 12 MB、
        // 切两次 300 万个字符："进了知识库页整个卡住、连正常的库都看不到"。
        //
        // 现在改查 [ragListEntries]（[RagIndexStore.list] 在后台遍历时**本来就要**
        // 读一遍正文来数行数，顺手的数留下来即可）：主线程这一次变成一次哈希查表。
        //
        // [!] 为什么仍然留在主线程（而不是把整个函数丢到后台）：状态行是"我点了之后
        //     到底成没成"的唯一反馈面，异步渲染会让它慢半拍地显示上一步的结论 ——
        //     那正是"骗人的绿字"的来源。真正的解法是让这一次取值**便宜到可以在主线程做**。
        //
        // [!] 查不到时**宁可不报这个数**（而不是回落到 0）：见 [ragListEntries] 的说明 ——
        //     `null` 是"快照还没到"，`0` 是"这个库是空的"，在屏幕上是同一个字、
        //     说出口的却是相反的结论。
        // ── 条数：**先问快照，再问一次有界轻读** ────────────────────────────
        // [ragListEntries] 是库列表补齐那一层写下的快照（真值来自 `list()` 的正文行数）。
        // 但"点库"这一跳**不会**刷新库列表（[switchRagDocTo] 只切当前库与正文框）——
        // 于是刚建好索引的库在那张快照里可能还没有（或是上一次的旧数），
        // 而"我这一下到底建成了几条"正是用户此刻唯一的疑问。
        //
        // 所以再问一次 [RagIndexStore.textsCountCapped]：**有界**读，
        // 小文件给精确数、大文件直接说"数不完"（`null`）。它取代的是
        // `RagIndexStore.textsCount`（整份读 + `split`）—— 那个在几千条的库上
        // 是主线程上几百毫秒到秒级的一次卡顿，而 [renderRagStatus] 会被切库、
        // 建库结束、导入……一路调用。
        //
        // [!] 三个值必须仍是**三个意思**（本仓库对此一贯是红的）：
        //   · `0`        —— 这个库是空的（`kb` 为空 / 磁盘上没正文）；
        //   · 一个正数   —— 快照或轻读量到的条数；
        //   · `null`     —— **还没量到 / 这一档量不完**。
        //     最后那个**宁可不报这个数**（屏幕上少一段"N 条"），也不回落到 0 ——
        //     回落到 0 会让一个好好的大库在状态行上写着"0 条"，而用户下一步
        //     多半是去重导（导入是追加合并，重导 = 条目翻倍）。
        val cnt: Int? = if (kb.isEmpty() || !hasTexts) 0
            else ragListEntries[kb]
                ?: RagIndexStore.textsCountCapped(this, kb, RAG_STATUS_COUNT_CAP_BYTES)
        // ── 磁盘上到底有没有这个库：**读列表那份真值**，不自己拼路径 ─────────
        //
        // ⚠⚠ 这一处是 ISSUE #259 第 ④ / ⑤ 条的落点，而它此前问错了对象：
        //     `hasTexts` 是"`texts.jsonl` **文件在不在**"，`exists` 是"`index.bin`
        //     在不在" —— 两者都**不回答**"磁盘上有没有这个库"。于是：
        //
        //       · 用户先点过一个**已有**的库（`currentKb` 是它），再在库名框里打了
        //         一个新名字、还没提交就去看状态行 —— 那一行报的是**新名字**这个库，
        //         `hasTexts` 为 false，于是屏幕上写着"⚠ 该库不存在（还没落盘任何东西）"。
        //         用户的读法是"我的库没了"（他报的原话），而其实那个老库好好的，
        //         只是当前库被**输入框里的字面值**顶掉了（已由 [commitRagKbName]
        //         与 [ragImportTarget] 两侧收口；状态行这一句是同一族的第三个信号）。
        //       · 更坏的一层：`RagIndexStore.list` 认的是**目录**（它比 `texts.jsonl`
        //         宽 —— 一个 `texts.jsonl` 为空文件的库照样是一个库），而状态行
        //         认的是文件。两个判据在"建库中断留下半份正文"那种库上**必然分家**，
        //         症状正是用户报的"库列表里能看到它、状态行说它不存在"。
        //
        //     所以状态行改问**库列表那份真值**（[ragListEntries] 里同时有"这个库在不在"
        //     与"几条"），与列表 / 按钮用的是同一把尺子 —— 两个信号从此不可能打架。
        val listed: Int? = ragListEntries[kb]
        val diskExists = listed != null || (kb.isNotEmpty() && RagIndexStore.isPresent(this, kb))
        // 这个库连**索引文件**都没有（"待重建"形态）—— 与 [exists] 是同一件事，
        // 单列一个名字是为了让下面的判断读起来是"有库但没索引"，不是"索引在不在"。
        val noIndex = !exists
        val parts = ArrayList<String>()
        parts.add(if (on) "检索：开" else "检索：关（App 内聊天不会注入知识库）")
        parts.add(if (kb.isEmpty()) "未选库" else "当前库：$kb")
        when {
            // [!] 这一支必须在 `!exists` 那支**之前**：正文在、索引不在 = **待重建**，
            //     是导入之后最正常的中间状态。说成"该库没有索引"虽然字面上也对，
            //     但它没说出"你导进来的东西**在**"—— 而这正是用户此刻唯一的疑问。
            //
            // ⚠⚠ 判据是 [diskExists]（**磁盘上有没有这个库**），不是 `hasTexts`
            //     （`texts.jsonl` 这个**文件**在不在）：两者的差别见 [diskExists] 那一段
            //     —— 杀进程留下的"半份正文"库，`list` 认得、`hasTexts` 认不得，
            //     而状态行说"该库不存在"时用户唯一能做的就是去删它（却找不到按钮）。
            kb.isNotEmpty() && noIndex && diskExists -> parts.add(
                "⚠ 正文在" + (if (cnt != null) "（$cnt 条）" else "") +
                    "、索引待建：检索为空，点「建索引/重建索引」")
            // ── 新建但**还没落盘**的那个库（[ragPendingKb]）──────────────────
            // [!] 这一支必须在"该库不存在"那一支**之前**：pending 时 `exists` 与
            //     `diskExists` 都是 false（磁盘上确实没有它），于是它会落到下面那句
            //     "磁盘上没有…这个库"上 —— 字面没错，但把"那是我刚点过「新建库」
            //     开出来的一个库"这件事**说丢了**，而用户此刻唯一的疑问正是
            //     "我新建的那个库在哪"。两句连着出现还会显得自相矛盾
            //     （"不存在"+"新建的"），所以这里是**替换**、不是追加。
            //     这一行必须与列表里那一行（"0 条 · 未落盘"）说的是同一件事。
            kb.isNotEmpty() && kb == ragPendingKb -> parts.add(
                "新建的库（未落盘）：粘贴正文/导入文档/导入库后自动保存，直接切走即丢弃")
            // ── 库名框里那个名字，磁盘上真的什么都没有 ──────────────────────
            // [!] 这一支**不是**"还没落盘"（上面那支管 pending），而是"这个名字
            //     连一个目录都没有"。它有一条非常容易踩到的来路：库名框里那串字
            //     **还没有提交**（用户改了名、还没失焦/点「确定」，就去看状态行）——
            //     而 [RagInject.currentKb] 是**提交过**的那个真值。所以这里必须把
            //     "点一下「确定」"写进句子里，否则用户会以为这个库真的不见了。
            //
            //     ⚠ 上一版这里写的是"该库不存在（还没落盘任何东西）"，用户报的
            //       现场里他手里其实有一个好好的库（只是名字对不上）—— 那句话的
            //       读法是"我的库没了"，于是他去文件管理器里翻、或者重新导入一份
            //       （导入是追加合并，重导等于条目翻倍）。
            kb.isNotEmpty() && !diskExists -> parts.add(
                "⚠ 磁盘上没有「$kb」这个库：点「新建库」开它，" +
                    "或从列表里点一个已有的（库名框里那串字还没点「确定」时也会走到这里）")
            noIndex && !inSync -> parts.add("⚠ 正文已改：检索已停用，点「建索引/重建索引」恢复")
            noIndex && !embedOk -> parts.add("⚠ 嵌入模型未驻留（检索必然为空，先加载嵌入模型）")
            !noIndex -> parts.add("索引就绪")
        }
        if (ragDocDirty) parts.add("正文未保存")
        // ⚠ 框里这份**不是全部**时，状态行必须常驻说这一句 —— 它是用户唯一能看到的
        // "别拿这份去建库"的依据（Toast 会消失，而这条判据要在整段编辑期间都成立）。
        // ⚠⚠ 但**说什么**取决于磁盘上有没有这份内容（[ragDocOwner] 是那个分水岭）：
        //   · 有（框里是某个库的正文的头部）-> “建库请用磁盘那份”是对的；
        //   · 没有（纯文本刚导入、还没落盘）-> 磁盘上根本没有这份内容，
        //     而“用磁盘那份”会去读**另一个库**。上一轮这里不分情况，于是那条路
        //     上的状态行也在说一句假话（用户照着点，索引了别的库）。
        if (ragDocClipped) parts.add(
            if (ragDocOwner.isEmpty())
                "⚠ 正文框里只是头部，且磁盘上还没有这份内容（框里是唯一副本）：请分小库导入"
            else "⚠ 正文框里只是头部（内容太多装不下），建库请用磁盘那份")
        // 条目数读**磁盘上的正文行数**（与库列表同一份真值）。它是"我追加的那条
        // 到底进去了没有"的唯一可见答案 —— 没有它，追加与删除只能靠"再点一次看看"。
        // 待重建那一支上面已经报过条数了，不重复。
        if (exists && cnt != null) parts.add("$cnt 条")
        // ── 远端那一路的状态（阶段 3）──
        // 为什么必须在这一行里出现：远端是**无状态**的，那个开关与库名在设置页里
        // 而效果（注入）发生在别人的客户端上。用户唯一能自查的地方就是这里 ——
        // 没有它，"远端到底在用哪个库、开着没有"只能靠回忆自己刚才点过什么。
        // 库名与 App 内那个**分开显示**：两个值独立之后，"我以为用的是 A、
        // 远端用的是 B"这件事必须一眼看得出来（这正是把它们分开的目的）。
        // 取值一律读**持久化真值**（不读输入框里的字面值）：输入框只在失焦时才落盘，
        // 读它会把"用户敲了一半还没失焦"当成"已配置"，而两者在状态行上必须能分开。
        val rOn = RagInject.remoteEnabled(this)
        val rKb = RagInject.remoteKb(this)
        parts.add(if (rOn) "远端：开" else "远端：关（HTTP 调用不注入知识库）")
        parts.add(if (rKb.isEmpty()) "远端未配库（= 不注入）" else "远端库：$rKb")
        ragStatusTv.text = parts.joinToString(" · ")
        ragStatusTv.setTextColor(
            if (on && exists && embedOk && inSync) pal.textSecondary else pal.warn)
        // 保存正文只在"该库**已有索引**"时可点 —— ⚠ 这条判据**故意**比库列表严。
        //
        // 库列表现在按目录列（一个只有正文的库照样显示，那是导入后的正常中间状态），
        // 而这里仍然要求**索引**在。两者的差别不是疏忽：
        //   · 列表要回答"**我有哪些库**" —— 待重建的库必须算一个，否则用户点不到它、
        //     正文也永远读不回来（那正是用户报的那条死路）；
        //   · 保存正文要回答"**这次保存之后，我还能找回这些字吗**" —— 一个从没建过
        //     索引的库，正文只在这一处（没有向量、没有备份），把编辑器里的内容
        //     整份写进去就等于覆盖掉用户唯一的副本。要求索引在位，是让"第一次落盘"
        //     只能走「建库/重建」（那条路会同时算向量、并对内容做校验）。
        // ⚠ 「保存正文」在 pending 上**也要可点**：一个还没有索引的新库，
        //   它的"保存"就是 [autosavePendingKbNow]（`importEntries`）那条路 ——
        //   而它在**盘上没有正文**时同样必须走得通（不然用户新粘了一大段，
        //   在自动保存落地之前点了「保存正文」，得到的是"该库还没有索引"，
        //   而那句话对"我要把我的内容存下来"这件事给不出任何出路）。
        val saveBtnOk = (exists || (kb.isNotEmpty() && kb == ragPendingKb)) && !ragBuilding
        if (::ragSaveBtn.isInitialized) ragSaveBtn.isEnabled = saveBtnOk
        // ══════════════════════════════════════════════════════════════════════
        // 两个删除按钮：判据都是"**磁盘上有没有这个库**"，而不是"有没有索引"
        // ══════════════════════════════════════════════════════════════════════
        // ⚠⚠ 这里修的是 ISSUE #259 第 ⑤ 条（用户报的原话：*"建库时我退出了 app，
        //    下次进入库页面，建了一半的库因为 app 退出没索引，此时我不想建库，
        //    那么我没有任何操作手段删库，**按钮全部不可用**"*）。
        //
        // 旧判据是 `exists && !ragBuilding`（`exists` = `index.bin` 在不在）与
        // `hasTexts`（`texts.jsonl` 在不在）——
        // **两个都要求"库里有东西"**，而"杀进程留下的半成品"恰好是那种
        // "有目录、但正文是空的（或只有一半）、索引根本没来得及写"的库。
        // 于是它进得了列表、点得动、而**每一个按钮都是灰的**：
        //   · 「建索引」要正文 —— 没有；
        //   · 「删索引」要索引 —— 没有；
        //   · 「删库」要 `hasTexts` —— 那个文件是空的/不在；
        // 用户唯一的出路是去文件管理器里删目录（而这是本仓库注释里早就写过、
        // 又自己造出来的"点不动也删不掉的幽灵行"）。
        //
        // 现在的判据只有一个：`diskExists`（**这个库在磁盘上存在吗**，与库列表
        // 同一把尺子）。存在 -> 两个删除按钮都能点。它们的**分工**写在按钮名里：
        //   · 「删索引」= 只清索引（向量），正文与目录都留着（点「建索引」可恢复）；
        //   · 「删库（连同索引与正文）」= 连目录一起抹掉（不可撤销）。
        // 对一个 0 条的半成品库，两者的差别只是"留不留一个空目录" ——
        // 而两条路都真的走得通（以前是两条都走不通）。
        //
        // [!] 对 **pending**（还没落盘）的库仍然**禁用「删库」**：它磁盘上没有任何
        //     东西，"删除"的语义在那里是"丢弃"，而那件事由"切走"完成。
        //     但状态行会把这条出口说出来（见 [diskExists] 那一段与下面那句提示）。
        //     ⚠ 「删索引」在 pending 上同样禁用（没有索引可删）。
        if (::ragDropBtn.isInitialized) ragDropBtn.isEnabled = diskExists && !ragBuilding
        if (::ragDeleteBtn.isInitialized) {
            ragDeleteBtn.isEnabled = diskExists && kb != ragPendingKb && !ragBuilding
        }
        // ── 建索引 / 重建索引 的判据也要跟着"库存在"这件事走 ────────────────
        // 旧判据只有一个 `!ragBuilding` —— 于是「建索引」在一个**根本不存在的库**
        // 上也是可点的，点下去得到的是"该库还没有索引：先填正文…"（一句循环论证：
        // 它让你去点你自己刚点过的那个按钮）。判据改成"这个库在磁盘上有东西，
        // 或者它正是那个刚新建、等着落盘的 pending" —— 两者都有内容可索引。
        if (::ragBuildBtn.isInitialized) {
            ragBuildBtn.isEnabled = !ragBuilding && (diskExists || kb == ragPendingKb)
        }
        // 追加要编码这一条 -> 必须已有索引 + 嵌入模型在位。缺一个时按钮**禁用**
        // （而不是点了才报错）：这两种缺失在状态行里都已经写明了原因，禁用让
        // "为什么点不动"与"上面那行警告"直接对上。
        if (::ragAppendBtn.isInitialized) ragAppendBtn.isEnabled = exists && embedOk && !ragBuilding
    }

    /**
     * 保存库名（失焦 / 建库前 / 点「确定」）。非法名**不静默改写**，只提示。
     *
     * @param announce true = 用户是**主动点「确定」**触发的。失焦那条路不弹成功 Toast
     *   （每点一次空白就弹一下太吵），但两条路都会刷新状态行 —— 那才是"存住了没有"
     *   的常驻反馈面；主动点按钮时再补一句 Toast，让"我按的这一下到底生效没"当场有答案。
     */
    private fun saveRagKbName(announce: Boolean = false): String? {
        val raw = ragKbEt.text.toString().trim()
        if (raw.isEmpty()) {
            RagInject.setCurrentKb(this, "")
            renderRagStatus()
            if (announce) Toast.makeText(this, "已清空当前库（检索不注入）", Toast.LENGTH_SHORT).show()
            return null
        }
        val safe = RagIndexStore.sanitizeName(raw)
        if (safe == null) {
            Toast.makeText(this, "库名非法：只允许字母/数字/中文/-/_/.，且非空、≤64 字", Toast.LENGTH_LONG).show()
            return null
        }
        RagInject.setCurrentKb(this, safe)
        renderRagStatus()
        if (announce) Toast.makeText(this, "当前库已设为「$safe」", Toast.LENGTH_SHORT).show()
        return safe
    }

    /**
     * 用户**提交**了库名框（失焦 / 点「确定」）—— 提交之后**还要把正文框切过去**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么"只存名字"是不够的（这一条正是用户报的那个误导）
     * ══════════════════════════════════════════════════════════════════════
     * 旧行为：[saveRagKbName] 只把新名字写进 `currentKb`，正文框**一个字符都不动**。
     * 于是"输入框里写着 B、框里装的是 A 的正文"是一个正常可达的状态 —— 而
     * 下一步点「建库/重建」会把 **A 的正文建成 B 的库**（`ragDocEntries(name)` 读框里
     * 那份、`name` 读输入框里那个），不崩、不报错、建库成功。用户的原话：
     * *"文本框应该默认为空，而不是保留上一个库的内容误导用户"*。
     *
     * 所以提交这一跳必须**成对**发生：名字进 `currentKb`，正文框跟着换
     *（已有那个库 -> 读它的正文；没有 -> 当作新建，清空并进 [ragPendingKb]）。
     * 两个信号从此说的是同一件事，而"输框里的名字"与"框里的正文"对不上这件事
     * 在提交路径上**不可能**发生。
     *
     * [!] 走 [confirmDiscardRagDoc] 那道闸：切换会把正文框整段换掉，有未保存的
     *     改动时必须先问（这与点列表里的库是同一条规则 —— 两条路都换正文框）。
     * [!] 名字**没变**（等于 [ragDocOwner]，或等于当前的 pending）时什么都不做：
     *     失焦是高频事件，而"每次点空白就重读一遍库"会让光标位置反复被清掉
     *     （更糟的是大库那一次读盘）。
     */
    private fun commitRagKbName(announce: Boolean) {
        val safe = saveRagKbName(announce)
        // 清空（用户把库名删光了）时不切正文框：那一步的语义是"以后不注入知识库"，
        // 不是"打开另一个库" —— 把框也清掉会让"我只想关掉检索"变成"我的草稿没了"。
        if (safe == null) return
        // 名字没变就什么都不做：失焦是高频事件，而重读会把光标位置清掉。
        if (safe == ragDocOwner || safe == ragPendingKb) { refreshRagList(); return }
        // ── 「新建但还没落盘」的库上改名 = 改那个内存里的名字 ──────────────────
        // [!] 这一支走不了 [isRenameOfCurrent]：pending 在磁盘上还没有目录，
        //     "按磁盘身份判是不是改名"这件事对它**不成立**（两个都解析不出目录）。
        //     而它的改名比已有库**更便宜**（改一个字符串），也更必须做对：
        //     `ragDocOwner` 与那个名字必须一起走，否则它会从"新建态"掉进
        //     "框里是一份没有归属的内容"（下一步「保存正文」会说"先建索引"，
        //     而那个库连目录都还没有 —— 用户唯一能做的是重来一遍）。
        if (ragDocOwner.isNotEmpty() && ragDocOwner == ragPendingKb) {
            // 新名字不能撞上一个已存在的库：那一步的语义是"切到那个库去"，
            // 而不是"把当前这份还没落盘的内容改名成它"（后者会让下一次自动落盘
            // **追加进那个已有的库**，条目翻倍而界面上一切正常）。所以这里交回
            // switchRagDocTo（磁盘上有那个库 -> 它走"切库/丢弃草稿"那条有闸的路）。
            if (RagIndexStore.isPresent(this, safe)) { switchRagDocTo(safe); return }
            val old = ragDocOwner
            ragPendingKb = safe
            RagInject.setCurrentKb(this, safe)
            ragDocOwner = safe
            ragKbEt.setText(safe)
            // 还没落盘 -> 不需要写任何文件、不需要重建（目录名还没诞生）。
            Toast.makeText(this, "新库改名为「$safe」（还没落盘，有内容时会用新名字保存）",
                Toast.LENGTH_SHORT).show()
            LlmEngine.uiLog("[知识库] 新建库改名：$old -> $safe（未落盘）")
            renderRagStatus()
            refreshRagList()
            return
        }
        switchRagDocTo(safe)
    }

    /**
     * 把**当前这个库**改名成 [display]：换显示名，**不换目录、不重建索引**（ISSUE #259）。
     *
     * 用户的原话：*"现在选中库，然后在库名输入的地方更换新名字，保存的话会直接
     * 生成一个新库，改名不生效，我认为应该直接生效，且不用重建索引"*。
     *
     * 三件事必须一起做对，缺一件都会留下一个说不清的状态：
     *   ① 表里那一行改掉（[RagIndexStore.rename]）—— `rag/` 下那个目录名**一个字符
     *      都不动**，所以 `index.bin` / `texts.jsonl` 全部原地不动，索引不用重算。
     *      这正是"不用重建索引"能成立的全部内容；
     *   ② `ragDocOwner` / 库名框 / `currentKb` 一起换成新名字 —— 它们各自是
     *      "谁在屏幕上显示"、"用户打的字"、"检索读的那些"。三个里漏一个的症状是
     *      "列表里是新名、状态行是旧名"这类**两个信号打架**；
     *   ③ **不碰正文框**（不 `loadRagDoc`）：内容没变，重读是多余的一次后台 IO，
     *      而且会把用户的光标位置清掉（他可能正改到一半）。
     *
     * [!] 写盘失败**必须如实说**（[RagIndexStore.rename] 返回 false）：说了"已改名"
     *     而其实没写盘，用户下次进来看到旧名字 —— 而"改名没生效"与"我记错了"
     *     在屏幕上一模一样，他会再改一次（第二次照旧）。
     * [!] 这一步**不进** [confirmDiscardRagDoc]：它不换正文，所以"未保存的改动"
     *     一件都不会丢。加那道闸的后果是用户改个名字都要回答一次"要不要先保存"，
     *     而那个问题与改名无关。
     */
    private fun doRenameCurrentKb(display: String) {
        val owner = ragDocOwner
        val dir = RagIndexStore.dirOf(this, owner)?.name ?: run {
            // 归属不是任何磁盘目录（理论不可达：调用方已判过 isRenameOfCurrent）。
            // 不静默当成"新建"：那会建出一个用户没要的库，而旧内容还在原处。
            Toast.makeText(this, "「$owner」在磁盘上找不到，无法改名（试试重进知识库页）",
                Toast.LENGTH_LONG).show()
            return
        }
        if (!RagIndexStore.rename(this, dir, display)) {
            Toast.makeText(this, "改名没生效（可能重名或写盘失败）—— 磁盘上还是「$owner」",
                Toast.LENGTH_LONG).show()
            // ── 三个信号**一起**回滚，缺一个就是"两个信号打架" ──────────────────
            // [!] 输入框回滚成真值：留着用户打的那个名字，屏幕上就会出现
            //     "库名框里是新名、列表里是旧名"，而两个都是"我刚才操作的结果"。
            // [!] `currentKb` **必须一起回滚** —— 调用方 [saveRagKbName] 在进这里
            //     之前已经把当前库设成了 `display`（那是"提交名字"这个动作的一部分）。
            //     不回滚的话，检索会一直去找一个**不存在的库**：查不到命中、接口 200、
            //     日志干净 —— 而屏幕上库名框写着 `owner`（看起来一切正常）。
            //     这正是本仓库那族最难查的静默错："两个信号说的是两件事，而
            //     用户看到的那个说的是'成了'"。
            ragKbEt.setText(owner)
            RagInject.setCurrentKb(this, owner)
            renderRagStatus()
            refreshRagList()
            return
        }
        LlmEngine.uiLog("[知识库] 改名：$owner -> $display（目录仍为 $dir，索引未重建）")
        ragDocOwner = display
        RagInject.setCurrentKb(this, display)
        ragKbEt.setText(display)
        Toast.makeText(this, "已改名为「$display」（内容与索引都没动，不用重建）",
            Toast.LENGTH_LONG).show()
        renderRagStatus()
        refreshRagList()
    }

    /**
     * 把一个库名**打开**到正文框里：磁盘上有就读它的正文，没有就按"新建"处理。
     *
     * 这是切库的**唯一入口**（点列表里的库、提交库名框、点「新建库」都落到这里），
     * 因为"读已有库"与"开一个新库"这两件事必须**在同一处**分家 —— 分成两处时，
     * 迟早有一处忘了清正文框（那正是用户报的"保留上一个库的内容误导用户"）。
     *
     * ⚠⚠ `name` 是**显示名**（用户看到、打出来的那个），不是目录名。
     *    两者的对应关系由 [RagIndexStore.dirOf] 那一层负责（ISSUE #259 的改名）。
     *    [ragDocOwner] 也**统一存显示名** —— 混用两套名字会让下面那条
     *    "已经是这一个了"的判据在改过名的库上永远不成立（于是每次点它都重读一遍，
     *    更糟的是走到"新建"那一支去清空正文框）。这一族的错没有症状，只有
     *    "内容有时会不见"，所以口径必须**只有一条**。
     *
     * @param forceNew true = 调用方已经确定这是**新建**（点「新建库」那条路，
     *   连"磁盘上同名库存在"都已经判过了）。false = 由这里按磁盘现状判。
     */
    private fun switchRagDocTo(name: String, forceNew: Boolean = false) {
        // ⚠ 已经是这一个了 -> **什么都不做**（只刷状态）。
        //   没有这一条，"点两次「新建库」"或"点列表里那一行 pending"会走
        //   [openNewRagKb]，而它**清空正文框** —— 用户刚粘进去、还没落盘的内容
        //   就此消失，而屏幕上看起来只是"我又点了一下"。
        if (name == ragDocOwner) {
            RagInject.setCurrentKb(this, name)
            renderRagStatus()
            return
        }
        // ── 已经是**磁盘上某个库**的另一个名字 -> 那是改名，不是切库 ────────────
        // [!] 这一支必须在这里（而不是在 commitRagKbName 里另写一套）：
        //     "点列表里的库"与"提交库名框"走的是同一个入口，而"提交的名字其实
        //     就是当前那个库的新名字"这件事，只有在这里看得见（`ragDocOwner`
        //     与 `name` 同时在手上）。写在调用方的话，点列表那条路会绕开它 ——
        //     而"列表里的名字"与"库名框里的名字"不同步正是这一族的老毛病。
        // [!] `forceNew` 为 true 时跳过（「新建库」那条路已经明确说了这是新的）。
        if (!forceNew && isRenameOfCurrent(name)) { doRenameCurrentKb(name); return }
        val onDisk = !forceNew && RagIndexStore.isPresent(this, name)
        confirmDiscardRagDoc(if (onDisk) "切换到「$name」" else "打开新库「$name」") {
            if (onDisk) {
                // 已有库：交回原来的路（它会清空 + 后台读 + 回填 + 刷条目列表）。
                clearPendingKb()
                RagInject.setCurrentKb(this, name)
                ragKbEt.setText(name)
                loadRagDoc(name)
            } else {
                // 新建库：**清空正文框**并进 pending —— 一个还没有内容的库，
                // 框里必须是空的（留着上一个库的正文就是那句"误导"）。
                openNewRagKb(name)
            }
            refreshRagList()
        }
    }

    /**
     * 提交上来的这个名字，是不是**当前正开着的那个库**的改名（而不是另一个库 / 新库）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 旧判据为什么恒假（这就是用户报的"改名还是失败"）
     * ══════════════════════════════════════════════════════════════════════
     * 旧写法是"比较磁盘身份"：`dirOf(owner)` 与 `dirOf(name)` 解析出的目录是同一个。
     * 而 `dirOf` 是**显示名 -> 目录名**的正查，查不到时**回落成它自己**
     *（[RagKbName.Map.dirOf]）。改名的那一刻，新名字在表里**必然还不存在**
     *（它就是这次要写进去的那个），于是 `dirOf(新名字) == 新名字`，
     * 而 `dirOf(owner)` 是那个**旧目录**（比如 `kb1`）—— 两者永不相等，
     * **除非用户输入的新名字恰好等于目录名**。
     *
     * 所以旧判据把每一次改名都判成"打开一个新库"，而屏幕上的表现正是用户报的：
     * *"修改库名为 test1，点击确定，库状态显示新建了库 test1，改名不生效"*。
     * 这不是"判据写错了一个字"，是**判据方向搞反了**：改名要写的那条映射
     * 在判断发生的那一刻一定还不存在，拿它去反查目录名必然落空。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 改成一句话就能判完的形状
     * ══════════════════════════════════════════════════════════════════════
     * "改名" = **当前真有一个库开着**（归属非空、且在磁盘上），
     * 而**新名字没有被别的库占用**。两条都成立时，这个名字只能有一个去处：
     * 给当前这个库当新显示名（目录名一个字符都不动）。
     *
     *   · 归属为空（框里是一份没有库的内容）-> 不是改名，走"打开/新建"；
     *   · 当前库在磁盘上不存在（pending，或磁盘被清过）-> 不是改名
     *     （pending 那条改的是一个内存里的字符串，见 [commitRagKbName] 里单独那一支）；
     *   · 新名字被别的库占着 -> 不是改名，而是"切到那个库去"（[switchRagDocTo]
     *     下面那条 `onDisk` 分支会接管，且它走 [confirmDiscardRagDoc] 那道闸 ——
     *     这正是我们要的：切库会换正文框，必须让用户知道）。
     *
     * [!] **不需要**再比较目录。名字没被占用 + 当前库在磁盘上，这两条已经排除了
     *     "整份正文被写进别人的库"那个风险 —— 那条风险的全部来源是"新名字其实
     *     指向了另一个已存在的库"，而它被 `checkName` 当场挡下。
     * [!] `checkName` 拿**当前库的目录名**去问（不是新名字）：改名成自己 = 无操作，
     *     必须放行；用新名字去问会把"重新提交同一个名字"（失焦高频路径）拦掉。
     */
    private fun isRenameOfCurrent(name: String): Boolean {
        val owner = ragDocOwner
        if (owner.isEmpty() || owner == name) return false
        // pending 上的"改名"= 改一个还没落盘的库名，它不需要磁盘那一套（见调用方）。
        if (owner == ragPendingKb) return false
        // 当前开的那个库必须**在磁盘上真有一个目录** —— 否则"改名"没有对象
        //（标量改名会变成"给一个不存在的库换了名字"，而它的内容无处安放）。
        val ownerDir = RagIndexStore.dirOf(this, owner) ?: return false
        if (!RagIndexStore.isPresent(this, owner)) return false
        // 新名字必须还没被**别的库**占用。被占用时那一步的语义是"切到那个库"，
        // 而不是"把当前这个库改成那个名字"（后者会让表里出现两行同名，而之后
        // `dirOf(那个名字)` 返回哪个目录取决于行的先后 —— 静默错）。
        //
        // ⚠⚠ 唯一性必须**同时看表与磁盘目录**（`liveDirs`）—— 这正是用户报的
        //    "点任意一个库都同时选中两个、名字一起变"的根子：在 `test` 上改成
        //    `test1` 时，表是空的（升级上来的用户从没改过名），于是只查表的判据
        //    说"没人占用"，而磁盘上**明明有一个目录叫 `test1`**。放行之后两个库
        //    共用显示名 `test1`：列表两行同时画 `●`，`dirOf("test1")` 还按行的
        //    先后把真正的 `test1` 目录遮蔽掉。传 `liveDirs` 之后这一条当场挡下，
        //    落到下面 `isPresent` 那一支 —— 那才是"切到那个库去"的正解（带丢弃闸）。
        return RagIndexStore.readNames(this)
            .checkName(ownerDir.name, name, RagIndexStore.liveDirs(this)) == null
    }

    /**
     * 「新建库」按钮：拿库名框里的名字开一个新库。
     *
     * 三条拒绝都有明确出口（不静默、也不静默改名）：
     *   · 名字空 -> 说"先填一个名字"（而不是用默认名建一个用户没要的库）；
     *   · 名字非法 -> 与 [saveRagKbName] 同一句提示；
     *   · **磁盘上已经有同名库** -> 拒绝新建，并把他**切过去**（那才是他想要的）。
     *     这一条是要害：静默当"新建"处理的话，他会以为在开一个新库，
     *     而磁盘上那个库的正文**原样躺在里面**，接着他点「导入」——
     *     追加合并到一个他以为是空的库里，条目翻倍而界面上一切正常。
     */
    private fun newRagKb() {
        if (ragBuilding) {
            Toast.makeText(this, "正在建库，请稍候再新建", Toast.LENGTH_SHORT).show(); return
        }
        val raw = ragKbEt.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "先在左边那个框里填一个库名，再点「新建库」", Toast.LENGTH_LONG).show()
            return
        }
        val safe = RagIndexStore.sanitizeName(raw)
        if (safe == null) {
            Toast.makeText(this, "库名非法：只允许字母/数字/中文/-/_/.，且非空、≤64 字", Toast.LENGTH_LONG).show()
            return
        }
        if (RagIndexStore.isPresent(this, safe)) {
            // ⚠ 不静默当新建：磁盘上这个库**有内容**，而"新建"的读法会让用户
            //   以为它是空的（接着导入就变成追加进一个非空库）。
            Toast.makeText(this, "磁盘上已经有「$safe」这个库了 —— 帮你切过去；" +
                "要另一个新库请换个名字", Toast.LENGTH_LONG).show()
            switchRagDocTo(safe)
            return
        }
        // ── 显示名唯一性（ISSUE #259）────────────────────────────────────────
        // `isPresent` 问的是"这个**目录**在不在"，而改名之后"哪个库叫什么"
        // 由那张表说了算：表里已经有一个叫 `safe` 的库（它的目录名是别的字）时，
        // 再开一个同名的新库会让**两个库同名** —— 而"我往哪个库里导入"这个问句
        // 就没有答案了（屏幕上两行长得一样，症状是"内容进了另一个库"）。
        // [!] 这里**不让用户"抢名字"**：抢名字的后果是那个老库在列表里被顶掉，
        //     而它的文件原样躺在磁盘上 —— 用户会以为"我的库被覆盖了"。
        //     所以拒绝 + 说清"那个名字被占了"，让他改一个（或先去改名/删掉它）。
        // ⚠ `liveDirs` 在这里是**防御性**的：`dir == display == safe`，所以磁盘那
        //   一支（`safe != dir && safe in liveDirs`）恒不触发 —— 目录同名的情况
        //   上面 `isPresent(safe)` 已经拦过了。真正在这里起作用的是**表那一列**
        //   （别的目录名借用了这个显示名）。带上 `liveDirs` 只为与 [rename] /
        //   [isRenameOfCurrent] 同一套口径 —— 三处各写一个变体迟早会分家。
        val nameMsg = RagIndexStore.readNames(this)
            .checkName(safe, safe, RagIndexStore.liveDirs(this))
        if (nameMsg != null) {
            Toast.makeText(this, "不能新建「$safe」：$nameMsg", Toast.LENGTH_LONG).show()
            return
        }
        switchRagDocTo(safe, forceNew = true)
    }

    /**
     * 真正开一个**还没有落盘**的新库：清空正文框、把名字记进 [ragPendingKb]。
     *
     * 三件事必须一起做，缺一件就会留下一个说不清的状态：
     *   ① 正文框清空（[setRagDocText] 会一并把截断旗标与字符数归零）；
     *   ② 条目列表清空（新库还没有条目 —— 留着上一个库的行会让"点删"删向一个
     *      在这个库里不存在的行号）；
     *   ③ `currentKb` 与库名框都设成它（提交路径已经做过，但这条路也被
     *      「新建库」以外的地方调到，重复设一次无副作用）。
     *
     * [!] **不建目录、不写任何文件** —— pending 的全部意义就是"磁盘上还没有它"。
     *     见 [ragPendingKb] 里那段（为了让它进列表而建空目录，代价是留下一个
     *     点不动也删不掉的幽灵行）。
     */
    private fun openNewRagKb(name: String) {
        ragPendingKb = name
        RagInject.setCurrentKb(this, name)
        ragKbEt.setText(name)
        // 归属也设成它：此后"框里这份属于谁"的答案是那个新库（还没落盘的那个），
        // 而"要不要自动落盘"靠 `ragDocOwner == ragPendingKb` 这个判据决定
        //（见 [maybeSchedulePendingAutosave] —— 那一处不看别的，只看归属）。
        ragDocOwner = name
        ragDocDirty = false
        // ── 这一个库是**可编辑输入**态（P1-a 里唯一的 `false` 支）───────────
        // 判据是"磁盘上有没有这个库"，而不是"哪个库"：pending 的目录根本还没
        // 建出来，磁盘上**没有任何副本**可以拿来做只读预览 —— 此时框不是
        // "某个库的窗口"，它**就是**那份内容本身。把它设成只读等于把这个入口
        // 整个关掉（ISSUE #259 用户要的"粘贴后立即落盘"就没有路了）。
        // [!] 它必须写在 `setRagDocText("")` **之前**：`setRagDocText` 会触发
        //   `paintRagDocFold`，而只读态与可编辑态下框的呈现（光标、能否聚焦）
        //   是两副样子 —— 顺序反了会让第一帧停在错的形态上。
        setRagDocSliced(false)
        setRagDocText("")
        ragRowsPage = 1
        renderRagRows(emptyList())
        renderRagStatus()
        Toast.makeText(this, "已新建「$name」（还没有内容，未落盘）—— " +
            "粘贴正文/导入文档/导入库后会自动保存；直接离开则丢弃", Toast.LENGTH_LONG).show()
    }

    /**
     * 清掉 [ragPendingKb]（= "丢弃那个还没落盘的新库"）。
     *
     * 它只清那个字符串：pending 从来没有在磁盘上留下任何东西，所以"丢弃"
     * 不需要删文件。⚠ 这正是"未添加内容就默认丢弃"能干净成立的原因；
     * 一旦有内容落盘，那个库就不再是 pending（[refreshRagList] 会把它当真实库
     * 列出来），"丢弃"这个动作也就**不再可达** —— 落盘的东西不会被无声拿走。
     */
    private fun clearPendingKb() {
        ragPendingKb = ""
    }

    /**
     * 有内容**落盘到了 [ragPendingKb]** 之后调它 —— pending 就此结束（它已经是个真库了）。
     *
     * 与 [clearPendingKb] 是**同一件事**（都只是清那个字符串），为什么还单列一个名字：
     * 两处的**语义完全不同**，而它们将来会想走不同的方向 ——
     * 一条是"丢弃一个空的新库"，一条是"新库落地了"。现在合成一个函数看起来更短，
     * 但那样写下来，读代码的人会以为"落盘"与"丢弃"是同一种处置。
     * 本仓库对这种"两件事共用一个动作"的历史教训很具体（见 docTruncatedOnDiskNote 那段）。
     *
     * [!] 落盘之后**必须**跟着一次 [refreshRagList]：那一刻起这个库才进得了列表
     *     （列表读磁盘，而它此前只在 [ragPendingKb] 这个内存变量里）。
     */
    private fun pendingKbLanded() {
        if (ragPendingKb.isEmpty()) return
        // ── 落盘 = 目录名就此诞生，把表里那一行补上（ISSUE #259）──────────────
        // [!] 为什么在**这里**写表、而不是在 [openNewRagKb]（"新建"那一刻）：
        //     那一刻磁盘上还没有目录，写进去的行指向一个不存在的目录 ——
        //     而表的作用是"解释一个**已有**目录叫什么"，不是一个愿望清单。
        //     （旧版本这里连表都没有：pending 的显示名就等于目录名，所以它不需要
        //     这一行；改名功能落地之后，用户完全可以在落盘**之前**改过名，
        //     那时"目录名 == 显示名"就不再成立了。）
        // [!] 目录名取 sanitizeName(显示名)：落盘走的是
        //     `RagIndexStore.importEntries` / `buildIndex`，两者都用
        //     [RagIndexStore.dirOf] 解析目录 —— 而在表里还没有这个库的行时，
        //     `dirOf` 回落成它自己，也就是**显示名净过之后的那个字符串**。
        //     这一行必须与那一处**同一个口径**，否则落盘之后列表里会出现
        //     一个"名字查不到目录"的库（它回落成目录名显示）。
        val dir = RagIndexStore.sanitizeName(ragPendingKb)
        if (dir != null && dir != ragPendingKb) {
            // 显示名与目录名不同 -> 表里需要一行。写失败只记日志（不打断流程）：
            // 后果是那个库显示成目录名（名字回退了），而库与内容都在。
            if (!RagIndexStore.rename(this, dir, ragPendingKb)) {
                LlmEngine.uiLog("[知识库] 新库「${ragPendingKb}」的显示名没写进表（将按目录名显示）")
            }
        }
        ragPendingKb = ""
        refreshRagList()
    }

    /**
     * 用户在**新建的、还没落盘的库**里敲了/粘了东西 —— 排一次"立刻落盘"。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不是"每次敲键立即写盘"
     * ══════════════════════════════════════════════════════════════════════
     * `texts.jsonl` 的写入是"整份重写"（[RagIndexStore.importEntries] 的语义就是
     * `已有正文 + 新增`，而新库的"已有"是空的，所以这里等于整份写一次）。
     * 逐键写一篇几万字的正文 = 每敲一个字重写几 MB —— 那不是"立即保存"，是卡。
     * 所以聚一下：停手 [PENDING_AUTOSAVE_MS] 毫秒之后落一次。
     *
     * [!] 它**不是** debounce 的"承诺慢慢存"：用户停下来就落盘，而"停下来"在这里
     *     通常就是他粘完那一大段（粘贴只触发一次 `afterTextChanged`，
     *     所以粘贴这条路第一跳就是终态）。真正被聚掉的只有"一个字一个字敲"。
     * [!] 代际在旁边那个字段里，理由见 [ragPendingSaveGen]。
     * [!] 它只服务 **pending**：已有的库不走这条路（见 TextWatcher 那处的说明）。
     */
    private fun maybeSchedulePendingAutosave() {
        val pending = ragPendingKb
        // 归属必须是那个 pending —— 用户可能已经切走了（此时 [ragPendingKb]
        // 与 [ragDocOwner] 都变了），而"把上一个库的正文写进这个新库"是这里唯一
        // 会造成数据错的形态。
        if (pending.isEmpty() || ragDocOwner != pending) return
        val myGen = ++ragPendingSaveGen
        ui.postDelayed({
            if (myGen != ragPendingSaveGen) return@postDelayed
            if (ragPendingKb != pending || ragDocOwner != pending) return@postDelayed
            autosavePendingKb(pending, myGen)
        }, PENDING_AUTOSAVE_MS)
    }

    /**
     * 把 pending 库的正文**落盘**（同步在后台线程），落地后 pending 结束。
     *
     * 用的是 [RagIndexStore.importEntries] 而不是 `saveTexts` —— 后者**要求库里
     * 已有索引**（它是"改写一个已存在的库"那条路），而新库连目录都还没有。
     * 导入那条路恰好就是"把一个没有向量的库建出来"的语义（它建目录、写正文、
     * 不动索引），也正是「导入库」用的那条 —— **两条路同一口径**，
     * 于是"新建后粘贴"与"新建后导入"在磁盘上留下的是同一种东西。
     *
     * ⚠ 空内容**不落盘**：用户可能敲了两个空格又删掉，那时不该诞生一个空库
     *   （"本次未添加内容，默认丢弃"是用户明确要的行为）。判据必须在这里做，
     *   而不是在 [maybeSchedulePendingAutosave] 里 —— 那边是"排个计划"，
     *   到这里才是"要不要真的写"。
     */
    private fun autosavePendingKb(name: String, myGen: Int) {
        val docs = ragDocEntries(name)
        if (docs.isEmpty()) return          // 没有内容 = 还是新建态，不落盘、不建目录
        ioExecutor.execute {
            val written = runCatching {
                RagIndexStore.importEntries(this, name, docs)
            }.getOrDefault(-1)
            ui.post {
                // 代际/归属复核：这次落盘是不是还属于"当前这个 pending"。
                // ⚠ 内容可能在这期间又变了 —— 那时 [maybeSchedulePendingAutosave]
                //   已经排了下一跳，而这一跳写下的是一份旧内容；**不当作失败**，
                //   继续让下一次覆盖它（这正是代际要防的那件事的反面：这里允许
                //   旧内容先落地，但绝不把它当成终态去宣告成功）。
                if (myGen != ragPendingSaveGen || ragPendingKb != name) return@post
                if (written <= 0) {
                    // 落盘失败**必须出声**：pending 的整个意义就是"它还没有落盘"，
                    // 而失败之后界面上的样子与"还没落盘"逐字相同 ——
                    // 不说，用户会以为已经存好了（然后离开，内容按"未添加"被丢弃）。
                    Toast.makeText(this, "「$name」自动保存失败（存储空间？）—— " +
                        "内容还在上面那个框里，请点「建索引/重建索引」重试", Toast.LENGTH_LONG).show()
                    return@post
                }
                LlmEngine.uiLog("[知识库] 新库「$name」已落盘：$written 条（自动保存）")
                // 落盘了 -> 它不再是 pending（列表里会作为真实库出现）。
                // ⚠ 只在这里清：清早了（还在写盘时）用户会看到列表里那个库先变成
                //   "真实库"、随后写盘失败 —— 一个"看着在、其实没有"的行。
                pendingKbLanded()
                renderRagStatus()
                // 条目列表也跟着刷：新库此前一行都没有（`ragRowsShown` 是空的），
                // 而现在磁盘上有 N 条 —— 不刷的话用户看到的是"库建好了、一条都没进去"。
                refreshRagRows(name)
            }
        }
    }

    /**
     * 保存**远端**库名（失焦即存）。与 [saveRagKbName] 同一套校验，但写的是另一个键。
     *
     * 空串是合法取值（= 远端不注入），所以这里**不**把空串当成"非法"去拦 ——
     * 拦了会让用户没法把远端库名改回空（唯一的退出方式是卸载重装）。
     * 非法名同样不静默改写，只提示（与 App 内那条一致）。
     *
     * @param announce true = 用户主动点「确定」。此时补一句成功 Toast 并刷新状态行，
     *   把"改完这个框到底生效了没有"当场讲清（失焦路只刷状态行，不弹 Toast）。
     */
    private fun saveRagRemoteKbName(announce: Boolean = false): String? {
        val raw = ragRemoteKbEt.text.toString().trim()
        if (raw.isEmpty()) {
            RagInject.setRemoteKb(this, "")
            renderRagStatus()
            if (announce) Toast.makeText(this, "远端库已清空（远端不注入知识库）", Toast.LENGTH_SHORT).show()
            return null
        }
        val safe = RagIndexStore.sanitizeName(raw)
        if (safe == null) {
            Toast.makeText(this, "远端库名非法：只允许字母/数字/中文/-/_/.，且非空、≤64 字",
                Toast.LENGTH_LONG).show()
            return null
        }
        RagInject.setRemoteKb(this, safe)
        renderRagStatus()
        if (announce) Toast.makeText(this, "远端库已设为「$safe」", Toast.LENGTH_SHORT).show()
        return safe
    }

    /**
     * 保存「每批编几条」（失焦即存；ISSUE #269 第 2 步）。
     *
     * ── 为什么读的是"归一后的值"而不是"用户填的原文"──────────────
     * 上限由 native 的 `n_seq_max` 决定（[LlmEngine.kEmbedBatchRows]）。用户填 16 而上限是 8 时，
     * **不能静默忽略** —— 静默忽略的症状与"生效了"在界面上逐字相同，而用户会一直
     * 以为 16 在跑。所以这里把它钳后**写回输入框**（让界面与真相一致）并（在
     * 主动点「确定」时）说一句。与线程数那个输入框同一条教训：
     * 参数被静默忽略，比参数报错更坏。
     *
     * @param announce true = 用户主动点「确定」。此时补一句 Toast。
     */
    private fun saveRagBatchSize(announce: Boolean = false): Int {
        val raw = ragBatchEt.text.toString().trim()
        // 空框 = "没给值"，按默认值跑（与 [parseThreads] 同一条口径）。
        val want = raw.toIntOrNull() ?: LlmEngine.kEmbedBatchDefault
        val got = EmbedBatchPref.normalize(want)
        // 写回输入框：只在值真的变了时才动（否则用户正在输入时
        // 会被强制改文本，光标也会跳）。
        if (raw != got.toString()) ragBatchEt.setText(got.toString())
        EmbedBatchPref.set(this, got)
        if (announce) {
            val clipped = got != want
            Toast.makeText(this,
                if (clipped) "批大小超过上限（${LlmEngine.kEmbedBatchRows}），已按 $got 跑"
                else "批大小已设为 $got" + if (got <= 1) "（关合批——每次只编一条）" else "",
                Toast.LENGTH_SHORT).show()
        }
        return got
    }

    /**
     * 保存「嵌入每批上下文」（失焦即存；0.9.212）。
     *
     * ── 为什么与批大小那格同一套写法 ─────────────────────────────────────
     * 上限由 [LlmEngine.kEmbedCtxMax] 决定（native 侧 `kEmbedMaxCtx`，且 ctx 总量
     * 不能超过它）。用户填 8192 时必须**写回钳后的值**并说一句 —— 静默忽略的
     * 症状与"生效了"在界面上逐字相同，而代价是"嵌入模型加载失败"时用户去怀疑
     * GGUF 坏了（他完全看不出是这一格给大了）。
     *
     * ⚠ 生效时机与批大小**不同**：批大小是每批现取的（改完重建索引即可），
     *   而这一格是**建 ctx 时**定下的 —— 改完必须重新加载嵌入模型。
     *   所以这一格要显式说"需重新加载"，不能让用户按批大小那条经验去猜。
     *
     * @param announce true = 用户主动点「确定」。此时补一句 Toast。
     */
    private fun saveRagEmbedCtx(announce: Boolean = false): Int {
        val raw = ragEmbedCtxEt.text.toString().trim()
        // 空框 = "没给值"，按默认值跑（与 [parseThreads] / saveRagBatchSize 同一条口径）。
        val want = raw.toIntOrNull() ?: LlmEngine.kEmbedCtxDefault
        val got = EmbedCtxPref.normalize(want)
        // 写回输入框：只在值真的变了时才动（否则用户正在输入时会被强制改文本）。
        if (raw != got.toString()) ragEmbedCtxEt.setText(got.toString())
        EmbedCtxPref.set(this, got)
        if (announce) {
            val clipped = got != want
            Toast.makeText(this,
                if (clipped) "每条预算超过上限（${LlmEngine.kEmbedCtxMax}），已按 $got 存" +
                    "（超了会让嵌入模型加载失败）"
                else "每条预算已设为 $got —— 需**重新加载嵌入模型**才生效",
                Toast.LENGTH_SHORT).show()
        }
        return got
    }

    /**
     * 建库 / 重建。
     *
     * 文本来源是那一块粘贴框，**一行一条**（空行丢弃）：这是手机上唯一不引入
     * 文件选择器 / 存储权限的输入方式，也正好对应"10~100 条短笔记"这个设计规模。
     */
    private fun doBuildIndex() {
        if (ragBuilding) return
        val name = saveRagKbName() ?: run {
            Toast.makeText(this, "请先填一个合法的知识库名", Toast.LENGTH_SHORT).show()
            return
        }
        // ══════════════════════════════════════════════════════════════════════
        // P1-a：建索引的**取正文口径按框的两态分家**（ISSUE #228）
        // ══════════════════════════════════════════════════════════════════════
        // 这个框在 P1-a 之后有两种身份，而它们"要索引的正文"来自**两个不同的地方**：
        //
        //   · **只读预览态**（这个库在磁盘上有目录）—— ⌈框里只有**一页**⌋，
        //     它**不是**"要索引的全部"。要索引的那份在磁盘上（`texts.jsonl`），
        //     走 [startBuildDisk]。⚠ 这是 P1-a 之后**唯一**会出错的地方：
        //     拿框里那一页去建，会静默切出一个只有 60 条的库，而回执说"索引已重建"
        //     —— 屏幕上每一个字都在说成了，实际九成内容没进去。
        //   · **可编辑态**（新建、还没落盘的那个库）—— 磁盘上**没有**这个库，
        //     框**就是**要索引的全部，走下面 `ragDocEntries` 那一条（与旧版一致）。
        //
        // ⚠ 分水岭用 [ragDocSliced]（= "磁盘上有没有这个库"），**不是**库名
        //   输入框的字面值：库名是用户随时可改的一个字符串，拿它当"这份正文属于
        //   哪个库"的证据，本仓库一向判红（见 [doSaveTexts] 为何认 [ragDocOwner]）。
        // ⚠ 两条路都**先认归属**：预览态的库必须是 [ragDocOwner] 那个（框里那份
        //   是从它读来的），否则"库名框写着 B、而框里是 A 的预览"这种分家会让
        //   建出的库是"B 这个名字装着 A 的内容"（不崩不报错，用户报的那个入口）。
        if (ragDocSliced) {
            // 预览态：框里只有一页，取正文必须走磁盘。
            // 归属为空或与库名不符时不许建 —— 那不是"框里这份的来源"，建出来
            // 就是一个名字与内容对不上的库。
            if (ragDocOwner.isEmpty() || ragDocOwner != name) {
                Toast.makeText(this,
                    "正文框是「${ragDocOwner.ifEmpty { "（不属于任何库）" }}」的只读预览，" +
                        "而库名是「$name」—— 先点列表里的「$name」或点「确定」把两者对齐",
                    Toast.LENGTH_LONG).show()
                renderRagStatus()
                return
            }
            // 这个库磁盘上有没有正文：没有（新建但已落盘、且正文还没写？）时
            // 明确说清，不静默建一个空库。
            val onDisk = runCatching { RagIndexStore.entriesCountOf(this, name) }.getOrDefault(0)
            if (onDisk <= 0) {
                Toast.makeText(this, "「$name」在磁盘上没有正文 —— 请用下面的「追加」逐条补进去",
                    Toast.LENGTH_LONG).show()
                renderRagStatus()
                return
            }
            // 直接走"用磁盘上那份"那条路（读盘 + 建库全在后台，不经过编辑器）。
            startBuildDisk(name)
            return
        }
        // ── 可编辑态（新建、还没落盘）：框就是要索引的全部 ────────────────────
        // ── 库名框与正文框**必须是同一个库**，否则不许建 ────────────────────
        // 这一条挡的是本屏最贵的一种静默错：`ragDocEntries(name)` 读的是**正文框**，
        // 而 `name` 读的是**库名框** —— 两者一旦分家，建出来的库是"B 这个名字装着
        // A 的正文"（建库成功、条数正常、搜得到东西，只是搜到的是另一个库的内容）。
        // 用户报的"保留上一个库的内容误导用户"说的就是这个入口。
        //
        // 分家怎么发生：库名框允许用户手打一个新名字（[saveRagKbName] 只写
        // `currentKb`，**不碰正文框**）。所以这里不替用户选，而是把他引到那个
        // 唯一说得清的动作上 —— 点「确定」/「新建库」（它们会把正文框一起切过去，
        // 见 [commitRagKbName] / [newRagKb]）。
        //
        // [!] 只在**框里有内容**时拦：空框不存在"把别的库的内容建成这个库"这件事。
        //     ⚠ 空框那一路（下面 `docs.isEmpty()`）在 P1-a 之后**只剩一种来路**：
        //       可编辑态下用户还没敲任何内容 —— 旧版那条"框空但磁盘上有内容 ->
        //       问用哪一份"的分支已经不可达（磁盘上的库走的是上面预览态那一支）。
        if (ragDocOwner != name && ragDocEt.text.isNotEmpty()) {
            Toast.makeText(this,
                "正文框里是「${ragDocOwner.ifEmpty { "（不属于任何库）" }}」的内容，" +
                    "而库名是「$name」—— 先点「确定」或「新建库」把两者切到同一个库",
                Toast.LENGTH_LONG).show()
            return
        }
        if (!LlmEngine.hasEmbedModel) {
            Toast.makeText(this, "尚未加载嵌入模型：索引必须用它算向量", Toast.LENGTH_LONG).show()
            renderRagStatus()
            return
        }
        val docs = ragDocEntries(name)
        if (docs.isEmpty()) {
            // 可编辑态下空框 = 用户还没敲/粘任何内容 —— 直接说清，不建空库。
            // ⚠ 旧版这里还有一条"框空但磁盘上有内容 -> 问用哪一份"的分支：P1-a
            //   之后**不可达**了 —— 那个库在磁盘上有目录时框走的是只读预览态
            //   （上面那一支已经接管），走不到这里。所以这里只剩"真的没内容"一种。
            Toast.makeText(this, "还没有内容：先把要索引的文本粘进下面那个框（一行一条）",
                Toast.LENGTH_LONG).show()
            return
        }
        // 条数够大时先问一句（用户拍板：**简短**提示）。为什么不直接开：
        // 建库的耗时由条数主导，而这一路的唯一起点是用户的一次点击 ——
        // 让他按下去之后才知道贵，那时他已经在等，且不敢动（不知道还要多久）。
        // 一句"约 N 分钟"就能让他自己决定是现在建还是等一下。
        // ⚠ 条数小的时候**不打扰**（返回 null）：绝大多数库只有十几条，
        //    每次弹框的后果是用户闭眼点确定 —— 真有一条贵的提示时也没人看了。
        // 显式把**真实**的块数上限传进去（不靠 RagCost 里那份同值抄写）：
        // 它决定"撞上限时要不要换一句话说"，而那句话一旦与真实上限不符，
        // 用户会按错的方向去拆文档。
        val prompt = RagCost.buildPrompt(docs.size, RagIndexStore.ChunkParams().maxChunks)
        if (prompt != null) {
            AlertDialog.Builder(this)
                .setTitle("建库要花点时间")
                .setMessage(prompt)
                .setPositiveButton("开始") { _, _ -> startBuildIndex(name, docs) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        startBuildIndex(name, docs)
    }

    /**
     * 用**磁盘上的正文**建索引（不是正文框里那份）。
     *
     * 为什么单独一条路（而不是"让用户先点库、等回填补好再点建库"）：回填是异步的，
     * 且中途切库/切页会把这次回填作废（见 [loadRagDoc] 的代际判据）—— 那条路在
     * 用户手里就是"我点了建库、它说框是空的、我再点回去、它还是空的"，
     * 而磁盘上明明有东西（用户报的原文）。
     *
     * 读盘 + 建库整个在后台线程；不经过编辑器，所以**不动** [ragDocOwner] 那份归属
     * （编辑器里是什么就还是什么 —— 这条路的语义是"用磁盘那份建索引"，
     * 不是"把磁盘那份搬进编辑器"）。
     */
    private fun startBuildDisk(name: String) {
        if (ragBuilding) return
        if (!LlmEngine.hasEmbedModel) {
            Toast.makeText(this, "尚未加载嵌入模型：索引必须用它算向量", Toast.LENGTH_LONG).show()
            renderRagStatus()
            return
        }
        ragBuilding = true
        renderRagStatus()
        // ⚠ 说清**读的是哪个库**（不是“磁盘上的正文”这种没有主语的句子）：
        //   这条路唯一会出错的方式就是“读了一个不是用户此刻在想的东西”，而那种错
        //   在回执里表现为一句**正确**的“索引已重建：X”（X 确实建好了，只是不是他要的）。
        //   把库名放进进行中的那一行，“他以为在索引 A、实际在索引 B”这件事
        //   在等待期间就看得见 —— 而不是建完才发现（那时几十秒已经花掉了）。
        ragStatusTv.text = "正在读取「$name」的正文…"
        Thread {
            val docs = runCatching { RagIndexStore.readEntries(this, name) }.getOrElse { emptyList() }
            ui.post {
                if (docs.isEmpty()) {
                    ragBuilding = false
                    ragStatusTv.text = ""
                    Toast.makeText(this, "磁盘上那份读不出条目（可能已损坏）—— 试试点库回填后看看",
                        Toast.LENGTH_LONG).show()
                    refreshRagList()
                    return@post
                }
                // 条数大时同样先问一句（与 doBuildIndex 同一判据，两处不能各算一套）
                val prompt = RagCost.buildPrompt(docs.size, RagIndexStore.ChunkParams().maxChunks)
                if (prompt != null) {
                    ragBuilding = false
                    renderRagStatus()
                    AlertDialog.Builder(this)
                        .setTitle("建库要花点时间")
                        .setMessage(prompt)
                        .setPositiveButton("开始") { _, _ -> startBuildIndex(name, docs) }
                        .setNegativeButton("取消", null)
                        .show()
                    return@post
                }
                startBuildIndex(name, docs)
            }
        }.start()
    }

    /**
     * 真正开始建库（确认框 / 小批量直通车都落到这里）。
     *
     * 为什么单独一个函数：确认框那一段必须"只负责问"，否则"问"与"做"的代码
     * 混在一起时，两条入口（直接建 / 确认后建）里的状态标志迟早会漏一个 ——
     * 那时症状是"按钮灰了却永远不恢复"（用户以为卡死）。
     */
    private fun startBuildIndex(name: String, docs: List<RagIndexStore.Entry>) {
        ragBuilding = true
        ragBuildBtn.isEnabled = false
        ragSaveBtn.isEnabled = false
        // 进行中的那行也报量级（不只是条数）：等的时候没有参照物才是焦虑的来源。
        ragStatusTv.text = RagCost.buildingLine(docs.size)
        // ⚠ 批大小**必须在主线程上先读完**（而不是进了下面那个线程再读）：
        // [saveRagBatchSize] 读的是 `ragBatchEt.text`，而那是一个 View 的属性 ——
        // Android 的 UI 工具包**不是线程安全的**，在后台线程上读它是未定义行为
        //（最软的结果是读到一个旧值，最硬的是闪退）。这一读与 `ragStatusTv`
        // 那一行同位置：都是"开工前在主线程上把要用的东西取定"。
        val embedBatchForBuild = saveRagBatchSize()
        Thread {
            // ── 开工前先问一句"这一下是续还是重来"（读盘在后台线程，不卡主线程）──
            // 用户真正怕的是"点一下又白等两小时"。而"能不能续"只有读了 index.bin
            // 才知道（[RagIndexStore.list] 会算 resumable），所以这一读放在
            // **后台线程**里、读完再用 ui.post 更新那一行 —— 放在主线程上是 IO 违规。
            // ⚠ 这只是**提示**（上界，见 [RagIndexStore.Info.resumable] 的说明）；
            //   真正的续编判据在 [LlmEngine.buildIndex] 里（它知道当前模型身份），
            //   判归判、提示归提示，两者不许共用一份近似（那正是以前那类静默失效）。
            runCatching {
                val info = RagIndexStore.list(this).firstOrNull { it.name == name }
                if (info != null) {
                    val hint = RagResumeUi.buildHint(
                        hasIndex = info.hasIndex, inSync = info.inSync,
                        resumable = info.resumable, resumeCount = info.resumeCount,
                        rowsTotal = info.entries)
                    if (hint != null) {
                        ui.post { if (ragBuilding) ragStatusTv.text = RagCost.buildingLine(docs.size) + "\n" + hint }
                    }
                }
            }
            val err = try {
                // ⚠ 批大小**从设置读**（而不是用默认参数）—— 这一句就是
                //   "合批可调"这件事真正接上的地方。不接的话设置页那个框
                //   会与本 issue 初始那个"线程数输入框"一模一样：能填、能存、
                //   就是对建库没用。
                //   钳尤在 [LlmEngine.kEmbedBatchLimit] 一处做（与设置页共用）。
                LlmEngine.buildIndex(this, name, docs, embedBatchSize = embedBatchForBuild)
            } catch (t: Throwable) {
                LlmEngine.uiLog("[知识库] 建库异常：${t.javaClass.name}: ${t.message}")
                "建库异常：${t.javaClass.simpleName}: ${t.message}"
            }
            ui.post {
                ragBuilding = false
                // 建库结束后**必须清掉**最后读数：它会在下一次切页时被原样带出来，
                // 而那时这一轮早已结束 —— 于是一个**已完成的**库看起来像"还在跑"
                //（正是"骗人的绿字"的镜像：骗人的进度字）。下面那句
                // `renderRagStatus()` 会把状态行换成"这个库现在是什么状态"。
                lastProgressBody = ""
                ragBuildBtn.isEnabled = true
                // "部分失败"返回的是一段**说明**（不是 null），但它与"真失败"必须分开：
                // 前者正文已经落盘、只是索引待重建（用户要做的还是点「建库/重建」，
                // 但前提是把那几条坏内容改掉）；后者什么都没写成。
                // 只看 `err == null` 会把前者的"正文已保存"一并否掉，于是用户
                // 反复重试同样的内容 —— 因为界面上看不出这次与上次的差别。
                val partial = err != null && err.startsWith("成功 ")
                Toast.makeText(this, err ?: "索引已重建：$name",
                    if (err == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                // 重建成功后（含部分失败）编辑器里这份正文就是**库里那份**了 ——
                // 归属与"已保存"都要跟着更新，否则状态行会一直显示"正文未保存"。
                if (err == null || partial) {
                    ragDocOwner = name
                    ragDocDirty = false
                    RagInject.setCurrentKb(this, name)
                    ragKbEt.setText(name)
                    // 建库成功 = 正文已经落盘（`buildIndex` 先写正文再算向量），
                    // 于是那个"新建但未落盘"的库从此是个真库 —— pending 结束。
                    // ⚠ 只在这两个**成功**分支里清：失败时磁盘上可能什么都没有，
                    //   清早了就等于把"离开即丢弃"这条出口也一起关掉
                    //   （库名还挂在界面上，而它其实没落盘）。
                    if (name == ragPendingKb) pendingKbLanded()
                }
                renderLog()
                refreshRagList()
            }
        }.start()
    }

    /**
     * 「保存正文」：只把编辑器里的内容写进 `texts.jsonl`，**不重算向量**。
     *
     * 为什么单列一个动作（而不是让用户去点「建库/重建」）：编辑是**多轮**的
     * （加一条、改一句、删一段），每轮都重建意味着 100 条要等 5~30 秒；
     * 而用户此刻要的只是"我的编辑不会丢"。保存是毫秒级的。
     *
     * ⚠ 保存后索引会失效（指纹里的正文 hash 对不上了），检索随之停用 ——
     * 状态行会明确显示「正文已改：检索已停用」，点「建库/重建」恢复。
     * 这一条刻意**不做成静默**：正文与向量是一对，改了正文还用旧向量去命中，
     * 就是"用旧语义的向量命中、注入新内容的正文"（排序像随机，接口 200、日志干净）。
     */
    private fun doSaveTexts() {
        // ── P1-b：这个按钮在「一片」态下**不再是整份覆盖** ─────────────────────
        // 框在切片态里只装**一页**（60 条量级）。旧版（P1-a 之前）拿框里那份
        // 去 [runRagSave]，走的是 `saveTexts` 的"整份重写"语义 —— 那会把
        // `texts.jsonl` **整份覆盖成那一页**，磁盘上原来那份完整正文就此没了、
        // 且没有副本。P1-a 因此把这个按钮**禁掉**（框只读）。
        //
        // P1-b 把它**接回来，但换了一条路**：[saveRagDocSlice] —— 按行偏移只回写
        // **改过的那几行**，其余行与其余页的真值仍来自磁盘
        // （见 [RagIndexStore.replaceRowsInPlace]）。于是"编辑这一页"重新可用，
        // 而"拿一页覆盖全库"这条路径**从代码上不存在**（这个分支里没有任何一处
        // 调用 [runRagSave] / `saveTexts`）。
        //
        // ⚠ 判据是 [ragDocSliced]（"磁盘上有没有这个库"），**不是**框里内容的多少：
        //   一个刚翻到的空页、一个只读了一行的页，与"整个库"在屏幕上长得一样。
        if (ragDocSliced) { saveRagDocSlice(); return }
        // 保存的**目标库是编辑器的归属**（[ragDocOwner]），不是库名输入框里的字面值。
        // 原因：用户把库名框改成"B"之后，编辑器里装的仍是"A"的正文 —— 若按输入框存，
        // 就是把 A 的正文写进 B（B 里原有的内容被整份覆盖），而界面上没有任何异常。
        // 这类"写错库"比报错难查得多，所以这里认归属、不认输入框。
        val owner = ragDocOwner
        if (owner.isEmpty()) {
            Toast.makeText(this, "编辑器里还不是任何库的正文：先「建索引/重建索引」建一个",
                Toast.LENGTH_LONG).show()
            return
        }
        val typed = ragKbEt.text.toString().trim()
        if (typed.isNotEmpty() && RagIndexStore.sanitizeName(typed) != owner) {
            Toast.makeText(this,
                "编辑器里是「$owner」的正文。要保存到「$typed」请先点列表里的那个库",
                Toast.LENGTH_LONG).show()
            return
        }
        // ── 新建的、还没落盘的库：这条路的"保存"就是把它建出来 ────────────────
        // ⚠ 放在**截断判据之前**：pending 的正文框从来不会被回填（它的内容都是
        //   用户自己敲/粘的），所以 [ragDocClipped] 恒为 false —— 但那不是"所以
        //   可以跳过"，而是"这条判据在 pending 上没有意义"。顺序写反会挡住那条路
        //   （pending 的 `owner` 在 [runRagSave] 下必然失败：没有索引）。
        if (owner == ragPendingKb) {
            val docs = ragDocEntries(owner)
            if (docs.isEmpty()) {
                Toast.makeText(this, "编辑器是空的：一行一条，空行会被丢弃",
                    Toast.LENGTH_SHORT).show()
                return
            }
            // 手动保存 = 用户明确要它落盘：代际自增，让已经排在聚拢窗口里的那一跳作废。
            val myGen = ++ragPendingSaveGen
            autosavePendingKbNow(owner, docs, myGen) { }
            return
        }
        // ── 框里这份只是**头部**时不许保存 ────────────────────────────────
        // ⚠ 这一条与「建库/重建」那条是**同一类病**，但更致命，而且此前漏掉了：
        //   「建库」拿头部去的后果是"切出一个缺尾巴的库"（检索不到后半段，但正文还在）；
        //   「保存」拿头部去的结果是 **`texts.jsonl` 被整份覆盖成那个头部** ——
        //   磁盘上原来那份完整正文**就此没了**，且没有任何副本。
        //   随后"检索不到后半段"与"本来就没有后半段"逐字相同，连恢复的余地都没有。
        //
        //   它此前不发作，只是因为 [RagUiCap.MAX_DOC_CHARS] 定得很大（40 万字符），
        //   大到"框装得下"和"库有多长"这两件事几乎总是重合。这一轮把它降到 4 万，
        //   于是**一个两千条的库必然踩到这一支** —— 降上限的动作把一条静默的
        //   数据丢失路径从"几乎不可达"变成"常见"。这正是必须在这里补上的原因。
        //
        // 处置与「建库」一致：不替用户选，只把走得通的路摆出来。这里**只有一条**
        // 真正安全的路（正文已经被覆盖过一次的东西不能靠"用磁盘那份"救回来，
        // 因为磁盘那份此刻是完整的、而框里这份是头部）—— 所以拒绝保存，
        // 并明确告诉他"要改全文请分小库维护"，而不是让他点一次就丢一半内容。
        if (ragDocClipped) {
            AlertDialog.Builder(this)
                .setTitle("正文框里只是头部，不能保存")
                .setMessage("「$owner」的正文太长，正文框里只装了前一部分" +
                    "（磁盘上那份是完整的，没有被改动）。\n\n" +
                    "直接保存会用框里这份**覆盖**磁盘上那份 —— 后半段就找不回来了。\n\n" +
                    "要改全文：把文档切成更小的文件、分成几个库导入；" +
                    "要在库里补一条，请用下面的「追加」。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val docs = ragDocEntries(owner)
        if (docs.isEmpty()) {
            Toast.makeText(this, "编辑器是空的：一行一条，空行会被丢弃", Toast.LENGTH_SHORT).show()
            return
        }
        runRagSave(owner, docs) { }
    }

    /**
     * 「保存本页改动」（P1-b）：把框里这一片**改过的行**按行偏移写回磁盘。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 这条路径存在的全部理由：让"编辑"与"整份覆盖"脱钩
     * ══════════════════════════════════════════════════════════════════════
     * P1-a 之前，"在框里改一条"与"把整个库覆盖成框里那份"是**同一个动作**
     * （框里装的就是全库，所以那时候它是对的）。框缩成一页之后，同一个动作
     * 会把库砍成一页 —— 这是本仓库最贵的一类静默丢失（不崩、不报错、
     * 屏幕上一切正常），所以 P1-a 干脆把编辑禁掉了。
     *
     * 这一版把两件事拆开：**框只负责说"改了什么"**，其余每个字节的真值来自磁盘。
     * 判据在 [RagUiCap.docEditPlan] 里（纯函数、宿主侧可单测），这里只负责
     * "把它算出来 -> 让用户确认 -> 落盘 -> 如实报数"。
     *
     * ⚠⚠ 拒绝的三种情况都必须**说清是哪一种**，不能一概"失败"：
     *   · 行数变了 -> 用户增/删了行。这不是故障，是**本版不支持的编辑**，
     *     而两条真的走得通的路就在同一屏上（「追加」/ 每条「删」）——
     *     不说清他就会反复试同一个动作；
     *   · 改到了一行"读不出来"的 -> 那一行磁盘上是坏的，框里那串是我们编的
     *     占位话。把它当新内容写下去 = 用一句不打紧的话盖掉磁盘上那段原字节；
     *   · 一行都没改 -> 这不是编辑。**不许产生写盘**（也不该把索引判成需重建）：
     *     本仓库对"看了一眼再保存"的口径是幂等的。
     */
    private fun saveRagDocSlice() {
        if (ragBuilding) return
        // 归属：框里那一页是从**这个库**读来的。库名输入框的字面值在这一条路上
        // 完全不用（它是可随手改的字符串，拿它当"这份正文属于谁"的证据，
        // 本屏其他地方一向判红 —— 见 [doSaveTexts] 认 [ragDocOwner] 那段）。
        val name = ragDocOwner
        if (name.isEmpty()) {
            Toast.makeText(this, "还没有选中任何库的正文：先点上面列表里的一个库",
                Toast.LENGTH_LONG).show()
            return
        }
        if (ragRowsShown.isEmpty() || ragDocSliceRows.isEmpty()) {
            Toast.makeText(this, "这一片还没有内容可以保存", Toast.LENGTH_SHORT).show()
            return
        }
        val plan = RagUiCap.docEditPlan(
            ragDocSliceRows, ragDocSliceFirst, ragDocEt.text?.toString().orEmpty())
        if (plan == null) {
            // 三种拒绝各有各的话 —— 见函数头那段。区分"行数变了"与其余两种，
            // 是因为它最常发生（用户很容易顺手回车），而它的下一步**不是**"重试"。
            val edited = ragDocEt.text?.toString().orEmpty()
            val lines = RagUiCap.docEditLines(edited)
            val msg = when {
                lines.size != ragDocSliceRows.size ->
                    "这一版只支持**改字**，不支持在框里增删行。\n\n" +
                        "· 想补一条：用下面的「追加」；\n" +
                        "· 想删一条：点上面列表里那一条的「删」。\n\n" +
                        "（保存会把每一行按**行号**写回磁盘，行数一变，它之后每一行的" +
                        "行号都要跟着挪 —— 而那条路一旦挪错，删掉的会是隔壁那一行）"
                edited == RagUiCap.docPreviewText(ragDocSliceRows) ->
                    "这一片没有改动，不用保存"
                lines.any { it.isEmpty() } ->
                    "框里有**空行**。空行不是条目（它在正文里会让『行号即下标』" +
                        "这条口径失效），所以保存不了。\n\n要删掉某一条请点上面列表里" +
                        "那一条的「删」；只是想把两条之间的空行去掉，请把它填上内容。"
                else ->
                    "这一片里有**读不出来**的行（显示为「${RagUiCap.DOC_UNREADABLE_ROW}」），" +
                        "而它被改动了。\n\n那一行在磁盘上是坏的，框里这串是占位说明 —— " +
                        "把它当新内容写下去会盖掉磁盘上那段原字节。\n\n" +
                        "要修它请点上面的「建索引/重建索引」（`用磁盘上那份`），" +
                        "那一跳会把坏行连同正文一起规范掉。"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        confirmSaveRagDocSlice(name, plan)
    }

    /**
     * 「保存本页改动」的确认框：把"要写哪几行、写进哪个库"先说清，再落盘。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么这一跳必须有一次确认
     * ══════════════════════════════════════════════════════════════════════
     * 保存的后果在屏幕上**看不出来**：框里还是那一页、列表还是那些条 ——
     * 而真相是"磁盘上第 121 行的正文被换掉了"。用户此刻唯一能核对的是
     * **改前改后那两行**，所以确认框要把它们摆出来（原文 vs 新文）。
     * 尤其重要的是**行号**：它把"我改的是第几个格子"与"磁盘上的第几行"
     * 对上 —— 而这两个数在界面上别处都不出现。
     *
     * ⚠ 不是"确认=多一次点击"的仪式：删除那一跳也有确认（[confirmDeleteEntry]），
     *   两者是同一类（不可撤销地改磁盘正文）。少这一跳的代价是"改错一行直接落盘"。
     */
    private fun confirmSaveRagDocSlice(name: String, plan: RagUiCap.DocEditPlan) {
        val sb = StringBuilder()
        for (k in plan.indices.indices) {
            val row = plan.indices[k]
            val before = ragDocSliceRows.getOrNull(row - ragDocSliceFirst)
            sb.append("第 ${row + 1} 行：\n")
            sb.append("  原：" ).append(oneLine(before)).append('\n')
            sb.append("  新：" ).append(oneLine(plan.texts[k])).append('\n')
            if (k >= 9 && k < plan.indices.size - 1) { sb.append("…（另有 ${plan.indices.size - k - 1} 行）\n"); break }
        }
        // ⚠ 这一句在 `0.9.236` 换了口径：保存**不再**只写正文，它把改过那几行的
        //   向量**一起重算** —— 所以"要不要重建"这件事**必须**说对。旧口径
        //   （改字 = 整库重建）是个很强的心理门槛，而它正是这一版要消掉的负担；
        //   继续印那句话会让用户去找重建按钮（白等几十分钟）。
        //   ⚠ 兜底那一支（编码失败 / 索引不同源）**也**要如实说 —— 所以句子写成
        //   "会重算这几条的向量（无需重建）"，而"待重建"那件事由**保存之后**的
        //   回执按三态说（那时才知道编没编出来）。
        sb.append("\n（只改上面这 ${plan.changed} 行；这一片其余行与**其余页**不动。" +
            "保存时会**只重算这几条的向量**并写回 —— 改完即可检索，**无需重建**）")
        AlertDialog.Builder(this)
            .setTitle("写回「$name」的 ${plan.changed} 行？")
            .setMessage(sb.toString())
            .setPositiveButton("保存") { _, _ -> runRagSliceSave(name, plan) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 摘要一行（确认框里用）：换行压成空格、超长截断 —— 只影响**显示**。 */
    private fun oneLine(s: String?): String {
        if (s == null) return RagUiCap.DOC_UNREADABLE_ROW
        val one = s.replace('\n', ' ')
        return if (one.length > 80) one.substring(0, 80) + "…" else one
    }

    /**
     * 后台把这一片的改动写回磁盘，回到主线程如实报数。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 落盘走 [LlmEngine.rewriteRows]：**只重算改过那几行的向量**
     * ══════════════════════════════════════════════════════════════════════════
     * P1-b 那一版走的是 [RagIndexStore.replaceRowsInPlace]（**只写正文**）——
     * 于是改完一个字，整个索引被判"需重建"，检索停用，用户要付一次全库重编码
     * （几千条 = 几十分钟）。而"改错别字 / 补标点"是编辑里最常见的动作。
     *
     * ISSUE #228 用户点名要的正是这一条（原话：*"改内容、行数不变 → 重算改那一行"*）。
     * [LlmEngine.rewriteRows] 把改过的那几行**向量一起重算并写回**，索引与正文
     * 随即重新同步 —— 检索**照常可用**，命中的就是改完的新内容，**无需重建**。
     *
     * ⚠⚠ 但它是**尽力而为**：编码失败 / 索引与当前模型不同源 / 索引只是半份时，
     *   它会自己退回"只写正文 + 索引待重建"（P1-b 的老口径，改动**不会丢**）。
     *   所以这一跳的回执必须按 [LlmEngine.RewriteResult] 的**三态**分家 ——
     *   把"改动在、只是要重建"说成"失败"会让用户重敲一遍，而更坏的是他会
     *   去重导（导入是追加合并，重导 = 条目翻倍）。
     *
     * ⚠ 这条路径上**不许**出现 `saveTexts` / `runRagSave`（那是"整份覆盖"，
     *   在这里等于"把库砍成一页"）。由 `tools/run_rag_doc_preview_guard.sh` 静态钉住。
     */
    private fun runRagSliceSave(name: String, plan: RagUiCap.DocEditPlan, after: () -> Unit = {}) {
        if (ragBuilding) { after(); return }
        ragBuilding = true
        renderRagStatus()
        ragStatusTv.text = "正在改字并重算这一页的 ${plan.changed} 条向量…"
        ioExecutor.execute {
            val res = try {
                LlmEngine.rewriteRows(this, name, plan.indices, plan.texts)
            } catch (t: Throwable) {
                LlmEngine.uiLog("[知识库] 按行改字异常：${t.javaClass.name}: ${t.message}")
                LlmEngine.RewriteResult.Failed("按行改字异常：${t.javaClass.simpleName}")
            }
            ui.post {
                ragBuilding = false
                when (res) {
                    // ── 索引重算过的那几行 -> **仍然可用**（本轮新增的那条路）──────
                    is LlmEngine.RewriteResult.IndexRebuilt -> {
                        LlmEngine.uiLog("[知识库] 「$name」按行改字 ${plan.changed} 行（第 " +
                            "${plan.indices.joinToString(",") { (it + 1).toString() }} 行）" +
                            "——只重算这几条向量，索引仍然可用（无需重建）")
                        ragDocDirty = false
                        Toast.makeText(this,
                            RagUiCap.docEditVectorNote(name, plan),
                            Toast.LENGTH_LONG).show()
                    }
                    // ── 正文写回了、索引待重建 -> 改动**没丢**，别说成失败 ──────────
                    is LlmEngine.RewriteResult.TextsOnly -> {
                        LlmEngine.uiLog("[知识库] 「$name」按行改字 ${plan.changed} 行只写回了正文" +
                            "（${res.why}）—— 索引待重建")
                        // 与 [LlmEngine.RewriteResult.IndexRebuilt] 的差别**不在**脏标记上
                        // （两种都"改动已落盘"，所以两态都清 ragDocDirty）——而在
                        // "检索还能不能用"：这一态磁盘上的索引与正文不同源，状态行会
                        // 另外说「正文已改：需重建」。清掉脏标记是**对的**：框里那份
                        // 已经写进磁盘了，再挂"未保存"就是在说一句假话。
                        ragDocDirty = false
                        Toast.makeText(this,
                            "已把 ${plan.changed} 行改动写回「$name」，但**索引待重建**（${res.why}）。\n\n" +
                                "改动没丢；改过的条目要等点一次「建索引/重建索引」后才按新内容检索。",
                            Toast.LENGTH_LONG).show()
                    }
                    is LlmEngine.RewriteResult.Failed -> {
                        LlmEngine.uiLog("[知识库] 按行改字没写进磁盘：${res.why}")
                        Toast.makeText(this,
                            "这一页的改动没有写进磁盘：${res.why}\n\n点一下这个库重新载入再试。",
                            Toast.LENGTH_LONG).show()
                    }
                }
                renderLog()
                renderRagStatus()
                // ⚠ `after` 在**成功与失败之后都**要走到（见 [confirmDiscardRagDoc]：
                //   用户点的是"保存并切走"，失败时卡在弹框上比丢掉这次编辑更坏）。
                after()
                // ⚠ 不在这里刷新条目列表/这一片：写回**没有改行数**，
                //   屏幕上那份（[ragRowsShown] / 框里这一页）与磁盘**仍然逐行一致**
                //   （只换了内容，且换的就是框里现在显示的那些）。
                //   重读一次会把用户刚看的视口位置清掉，而它换不来任何新信息。
                //   ⚠ 索引重算那条路**也不刷新**：向量是后台的，屏幕上没有一处显示它。
            }
        }
    }

    /** 后台保存正文（**整份**覆盖：只在"框就是全部"那一态用），回到主线程刷新。
     *  `after` 在保存结束后（无论成败）调用。 */
    private fun runRagSave(name: String, docs: List<RagIndexStore.Entry>, after: () -> Unit) {
        if (ragBuilding) return
        ragBuilding = true
        ragSaveBtn.isEnabled = false
        Thread {
            val err = try {
                LlmEngine.saveTexts(this, name, docs)
            } catch (t: Throwable) {
                LlmEngine.uiLog("[知识库] 保存正文异常：${t.javaClass.name}: ${t.message}")
                "保存异常：${t.javaClass.simpleName}: ${t.message}"
            }
            ui.post {
                ragBuilding = false
                ragSaveBtn.isEnabled = true
                if (err == null) {
                    Toast.makeText(this, "正文已保存（检索需「建索引/重建索引」后才生效）",
                        Toast.LENGTH_LONG).show()
                    // 保存成功 = 编辑器里这份就是库里那份，脏标记可以清了
                    ragDocOwner = name
                    ragDocDirty = false
                    RagInject.setCurrentKb(this, name)
                    ragKbEt.setText(name)
                    // 落盘到了那个"新建但未落盘"的库 -> 它从此是个真库（pending 结束）。
                    // 放在成功分支里：失败时磁盘上仍然没有它，pending 必须留着，
                    // 否则用户看到的是一个既不在列表里、也没落盘的库（无影无踪）。
                    if (name == ragPendingKb) pendingKbLanded()
                } else {
                    // 失败**不**清脏标记：清了就等于告诉用户"存住了"，而磁盘上没有。
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
                renderLog()
                refreshRagList()
                after()
            }
        }.start()
    }

    /**
     * 「删库」：把磁盘上这个库**整个抹掉**（索引 + 正文 + 目录），不可撤销。
     *
     * 用户的原话：*"有了新建库的按钮和逻辑，应该有删库按钮"*。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么不能靠既有的「删索引」顶替它
     * ══════════════════════════════════════════════════════════════════════════
     * 「删索引」把 `index.bin` 与 `texts.jsonl` 都删掉，而**目录留着** ——
     * 于是那个库在列表里变成一行 **0 条**。用户以为"我把它删了"，屏幕上却还有它，
     * 而且**没有任何按钮能对一行 0 条的库起作用**（删索引要索引在、建索引要正文在）。
     * 那正是 `RagIndexStore.list` 注释里那段"点不动也删不掉的幽灵行"的同一形态 ——
     * 只不过这次是我们自己造的。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 三道闸（顺序不能换）
     * ══════════════════════════════════════════════════════════════════════════
     * ① 目标必须是**磁盘上真有的库**（`isPresent`）。对 pending 的库删 = 一个
     *    空动作，而屏幕上会弹一句"已删除"—— 用户以为内容没了，其实它还在框里。
     *    pending 有它自己的出口（切换即丢弃，见 [clearPendingKb]）。
     * ② 确认框里必须**报出条数**：这是"删的是哪个库"唯一的量化证据。只写库名时，
     *    用户在一个改过名的库上看到的正是他刚打的名字 —— 而那与"我删了另一个库"
     *    长得一样。
     * ③ 删完要**清归属**（[ragDocOwner] / 脏标记 / 正文框）：库没了，框里那份正文
     *    已经不属于任何库 —— 留着它，下一次「保存正文」的判据会把它当成
     *    某个"磁盘上找不到的库"（虽然现在会被 `owner.isEmpty()` 挡住，
     *    但那是**碰巧**，不是设计）。
     */
    private fun doDeleteKb() {
        if (ragBuilding) {
            Toast.makeText(this, "正在建索引/导入，请稍候再删", Toast.LENGTH_SHORT).show(); return
        }
        val name = saveRagKbName() ?: run {
            Toast.makeText(this, "请先填一个合法的知识库名", Toast.LENGTH_SHORT).show()
            return
        }
        // ① 目标必须是磁盘上真有的库（pending 不走这条路 —— 它没落盘）。
        //
        // ⚠⚠ 判据是 [RagIndexStore.isPresent]（**目录里有东西**），不是
        //     `indexFile(...).isFile`（有没有**索引**）。这两者的差别正是
        //     ISSUE #259 第 ⑤ 条：一个"建库时被杀进程打断"的库**没有索引**，
        //     但它在磁盘上、在列表里、而且**必须删得掉**（用户的原话：
        //     *"此时我不想建库，那么我没有任何操作手段删库"*）。
        //     按索引判的结果是：那一行点得动、而「删库」永远返回"磁盘上没有这个库"。
        if (!RagIndexStore.isPresent(this, name)) {
            if (name == ragPendingKb) {
                // 还没落盘的新库："删"它的语义是"丢弃"。这一句必须与「新建库」
                // 那句说明说的是同一件事（"直接离开则丢弃"），否则用户会在两处
                // 看到两种说法，而他会按更保守的那个去理解（以为内容已经存了）。
                Toast.makeText(this, "「$name」还没落盘（没有可删的东西）——" +
                    "切到别的库就会丢弃它", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "磁盘上没有「$name」这个库", Toast.LENGTH_LONG).show()
            }
            return
        }
        val entries = runCatching { RagIndexStore.entriesCountOf(this, name) }.getOrDefault(0)
        // ── 0 条的库也要能删，而且要说清"删掉的是什么" ──────────────────────
        // 用户报的第 ③ 条（*"新建 test3 库…点击删索引（连正文一起删），库直接被删了；
        // 新建 test4 库…删库（连同索引与正文），库直接被删了；**那么这俩按钮有啥区别**"*）
        // 问的正是这一处：对一个 0 条的库，两个按钮的差别只剩"目录留不留" ——
        // 而这件事以前**一个字都没写在确认框里**（他之所以看到"库直接被删了"，
        // 是因为列表里那个 0 条的幽灵行消失了，而那两行本来就该消失）。
        //
        // 所以确认框按条数分两句说：
        //   · 有内容 -> 说清会丢什么（这是唯一一次能反悔的机会）；
        //   · 0 条   -> 直接说"这个库里一条内容都没有"，并把两个按钮的差别点出来
        //               （用户此刻的疑问就是它，而答案只有一个短句）。
        val body = if (entries > 0)
            "会**整个删掉**这个库：索引、正文（$entries 条）、目录，全部。\n\n" +
                "⚠ 没有撤销，也没有回收站 —— 删了就找不回来。\n" +
                "想先留一份：点「导出库」把正文存到下载目录，再删。\n\n" +
                "（只需要丢掉索引、留着正文：点「删索引」—— 它不动正文。）"
        else
            "这个库里**一条内容都没有**（可能是建索引没走完就退出了）。\n\n" +
                "「删库」= 连目录一起删掉；\n" +
                "「删索引」= 只清掉索引、正文和目录都留着。\n\n" +
                "对这个库，两者的差别只是「以后 rag/ 下还留不留一个空目录」—— " +
                "要它彻底消失就点下面这个。"
        AlertDialog.Builder(this)
            .setTitle("删库：$name")
            .setMessage(body)
            .setPositiveButton("删除") { _, _ ->
                val ok = runCatching { RagIndexStore.delete(this, name) }.getOrDefault(false)
                if (!ok) {
                    // 失败必须如实说：说成"已删除"而目录还在，用户下次进页面
                    // 会看到它、然后怀疑自己刚才点了什么（同 RagIndexStore.delete 那段）。
                    Toast.makeText(this, "没删掉：「$name」还在磁盘上（被占用？）",
                        Toast.LENGTH_LONG).show()
                    refreshRagList()
                    return@setPositiveButton
                }
                LlmEngine.uiLog("[知识库] 删库：$name（$entries 条）")
                // ③ 清归属 + 清掉框里那份"已经不属于任何库"的正文。
                if (name == ragDocOwner) {
                    ragDocOwner = ""
                    ragDocDirty = false
                    setRagDocText("")
                }
                if (name == ragPendingKb) clearPendingKb()
                // 当前库就是它 -> 把"当前库"也清掉（否则检索会一直去找一个
                // 已经不存在的库：查不到命中、接口 200、日志干净）。
                if (RagInject.currentKb(this) == name) {
                    RagInject.setCurrentKb(this, "")
                    ragKbEt.setText("")
                }
                Toast.makeText(this, "已删除「$name」", Toast.LENGTH_SHORT).show()
                refreshRagList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「删索引」：只作废索引（向量），**正文一个字节都不动**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 它以前连正文一起删，而那**是逻辑错了**（ISSUE #259 第 ② 条）
     * ══════════════════════════════════════════════════════════════════════
     * 用户的原话：*"删索引按钮，应该不删正文啊，删索引为啥要删正文，是你按钮写错了
     * 还是逻辑写错了"*。**是逻辑写错了** —— 按钮名叫"删索引"，而底下
     *（[RagIndexStore.drop]）把 `texts.jsonl` 一起删了。当初那个按钮的**名字里
     * 补了半句**（"连正文一起删"）来让名字与行为对齐；那是**把名字改去迁就错的
     * 行为**。正确的修法是把行为改回来：删索引 = 只删可再生的那一半（向量），
     * 正文（唯一的、不可再生的那一半）留着。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 两个删除动作现在**真的**不同（这正是用户问的那个问句的答案）
     * ══════════════════════════════════════════════════════════════════════
     * | 动作     | 索引 | 正文 | 目录 | 列表里        | 可撤销 |
     * |----------|------|------|------|---------------|--------|
     * | 删索引   | 删   | 留   | 留   | 留着（待重建）| 能（重建索引）|
     * | 删库     | 删   | 删   | 删   | 消失          | 不能   |
     *
     * 于是删索引之后那一行**还在列表里**、状态是"待重建"，点「建索引/重建索引」
     * 就能恢复检索 —— 用户的正文一条都没丢。删库之后那一行消失。
     * 以前两者看起来一样（正文都丢），那正是用户问"这俩按钮有啥区别"的原因。
     *
     * [!] 归属（[ragDocOwner]）**不清**：索引没了，但那个库还在、正文还在，
     *     框里这份仍属于它 —— 下一步「建索引/重建索引」就要拿它去算向量。
     *     （以前要清，是因为"库"跟着正文一起没了；现在它没没。）
     * [!] 返回值仍是"索引删掉了没有"。索引本来就不在（库里只有正文）时返回
     *     false —— 这里说"本来就没有索引"，而不是"已删除"（那会让用户以为
     *     自己刚做了一次有效操作）。
     */
    private fun doDropIndex() {
        val name = saveRagKbName() ?: return
        val hadIndex = RagIndexStore.indexFile(this, name)?.isFile == true
        val ok = runCatching { RagIndexStore.drop(this, name) }.getOrDefault(false)
        if (!ok) {
            // 判据是"索引文件还在不在"（`drop` 的返回值就是这个），而不是"这次调用有没有抛异常"。
            Toast.makeText(this, "没删掉索引：「$name」的索引还在磁盘上（被占用？）",
                Toast.LENGTH_LONG).show()
            refreshRagList()
            return
        }
        LlmEngine.uiLog("[知识库] 删索引：$name（索引" +
            (if (hadIndex) "已删" else "本来就没有") + "；正文与目录保留）")
        // 说清"剩下什么" —— 这是用户刚点完按钮唯一在看的地方。
        // 尤其要说清**正文还在**：以前那句说的是"索引与正文都删了"，
        // 用户据此以为内容没了，于是去重导（而导入是追加合并，重导 = 条目翻倍）。
        Toast.makeText(this,
            if (hadIndex) "已删掉「$name」的索引（**正文还在**，点「建索引」可恢复检索）"
            else "「$name」本来就没有索引（正文还在；点「建索引」可让它可以检索）",
            Toast.LENGTH_LONG).show()
        refreshRagList()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  知识库导出 / 导入（正文层）
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 这两条路之前**不存在**：正文只有在"点库回填 -> 改 -> 保存/重建"里活着，
    // 而「删索引」把 index.bin 与 texts.jsonl 一起删。换手机 / 重装 = 归零。
    //
    // 三条纪律（与聊天记录导出同一套，见 exportSessions）：
    //   · 导出是纯读，不引用界面筛选状态，也不要求嵌入模型在线；
    //   · 导入**只增不改**，同名库=追加合并，绝不覆盖；
    //   · 落盘前先**弹确认**（导入不可撤销），且文案里必须写清"导入后需重建"。

    /**
     * 导出当前库的正文到公共「下载」目录。
     *
     * 导出的是**正文**，不带向量 —— 理由见 [RagArchive] 的文件头：
     * 带上向量就会出现"指纹对不上、接口 200、排序像随机"那条静默错。
     * 所以这里一次都不访问 native，也不要求嵌入模型已加载
     * （导出最典型的场景是"准备换手机"，那时模型可能压根没装）。
     */
    private fun doExportRag() {
        val name = saveRagKbName() ?: return
        if (RagIndexStore.dirOf(this, name) == null) {
            Toast.makeText(this, "请先填一个合法的知识库名", Toast.LENGTH_SHORT).show(); return
        }
        statusTv.text = "正在导出知识库…"
        ioExecutor.execute {
            // 读文件 + 序列化都在后台（库大时几百 KB~几 MB，主线程会掉帧）。
            val json = runCatching { LlmEngine.exportRagArchive(this, name) }.getOrNull()
            ui.post {
                statusTv.text = ""
                if (json == null) {
                    Toast.makeText(this, "没有可导出的内容（库「$name」为空或不存在）",
                        Toast.LENGTH_LONG).show()
                    return@post
                }
                val fileName = exportName("rag", sanitizeFilePart(name), ext = "json")
                try {
                    // MIME 必须显式 application/json，同聊天记录导出
                    // （text/plain 会让文件管理器把它当纯文本，分享出去变 .txt）。
                    val where = LogExport.saveText(this, fileName, json, mime = "application/json")
                    LlmEngine.uiLog("[知识库] 导出「$name」→ $where")
                    Toast.makeText(this, "已导出知识库「$name」\n$where\n（只含正文，导入后需重建索引）",
                        Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 把库名净成文件名片段。
     *
     * 与 [RagIndexStore.sanitizeName] **不是**同一件事：那个是目录名（`.` 合法），
     * 而这里是**文件名**的一部分，`/` 与 `..` 会让文件落到别处。库名已经被
     * sanitizeName 挡过一遍（不含 `/`），但导出可能作用在一个尚未保存的输入框值上，
     * 所以这里再挡一次 —— 宁可文件名少几个字符，也不要写到意料之外的地方。
     */
    private fun sanitizeFilePart(raw: String): String {
        val t = raw.trim().replace('/', '_').replace('\\', '_')
        return t.ifEmpty { "kb" }.take(32)
    }

    /**
     * 打开系统文件选择器挑一份**知识库文件**（.json / .jsonl）—— 也就是「导入库」。
     *
     * 名字里的 Archive 就是这件事：它读的是**已经是一条条条目**的库文件
     *（本应用导出的信封 / 裸 JSON 数组 / 裸 JSONL），原样追加进库。
     * 一份连着写的 `.txt` / `.md` **不走这条** —— 那条走 [pickRagTextFile]
     *（回填编辑器）。两个按钮两个动作，请求码也是两个。
     */
    /**
     * **弹系统文件选择器之前**冻结本次导入的目标库名（见 [ragImportTarget] 的长注释）。
     *
     * 为什么放在这里、而不是放在落盘那一刻现取：选择器是**另一个 App**，
     * 它出现的过程中本页的库名框会失焦，而失焦那条路会覆盖 `currentKb`
     * 并在 `ragDocOwner` 为空时把用户打的名字当成"新建库"。
     * 那一次覆盖发生在"用户已经说了要导入"之后，所以**不能**让它决定目标库。
     *
     * [!] 冻结的是 [RagInject.currentKb]（**持久化真值**），不是输入框里的字面值：
     *     输入框从 `0.9.199` 起只在**提交**（点「确定」/失焦）时才与真值同步，
     *     而"用户敲了名字但还没提交就点了导入"这条路上，输入框里那个名字
     *     从来不是一个库 —— 拿它当目标库就是"静默建出一个用户没要的库"。
     *     ⚠ 同一条判据下 [ragDocOwner] 也不读：它同样可能为空
     *       （用户还没点过任何库），此时目标库应当是"当前选中的那个"（也就是空，
     *       交给确认框让用户填）—— 而不是输入框里那个半成品名字。
     * [!] **冲突要当场说**：输入框里的名字与真值不同（用户改了名没提交又来导入）时
     *     不替他选，把两者都摆出来。这两件事的后果差着几个数量级：
     *     选错方向不是"多打一次字"，而是"整份文档进了一个他没打算要的库"。
     */
    private fun freezeImportTarget(fromText: Boolean = false) {
        ragImportTarget = RagInject.currentKb(this)
        ragImportFromText = fromText
        val typed = ragKbEt.text.toString().trim()
        val safeTyped = RagIndexStore.sanitizeName(typed)
        if (typed.isNotEmpty() && safeTyped != null && safeTyped != ragImportTarget) {
            Toast.makeText(this,
                "库名框里写着「$safeTyped」，但**当前选中的库**是「" +
                    (ragImportTarget.ifEmpty { "（未选）" }) + "」—— 导入会进当前选中的那个。" +
                    "要用「$safeTyped」，请先点「确定」再导入",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun clearImportTarget() {
        ragImportTarget = ""
        ragImportFromText = false
    }

    private fun pickRagArchiveFile() {
        freezeImportTarget()
        // 与 pickArchiveFile 同一条理由：type 用 */* 而不是 application/json。
        // SAF 上不少文件管理器把 .json / .jsonl 报成 text/plain 或
        // application/octet-stream，写死 application/json 会让"明明在，却选不中" ——
        // 而那种现象会被读成"这个 App 不支持导入"。真实性由 RagArchive.decode 的
        // 形状判据兜底：选错文件会被明确拒绝。
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_IMPORT_RAG)
    }

    /**
     * 打开系统文件选择器挑一份**纯文本**（.txt / .md）——
     * 这是"剪贴板装不下长文"那条诉求的入口。
     *
     * `type` 用 text 通配（而不是全通配）：这条路只吃 .txt / .md，一个 PDF 被选进来
     * 只会得到一句"这看起来不是文本文件"。但"选不中"是同一族里的另一种坏 ——
     * 不少文件管理器把 `.md` 报成 `application/octet-stream`，所以再补一行
     * `EXTRA_MIME_TYPES`。**真实性由内容判**（见 [RagArchive.readText] 的三道闸），
     * MIME 只决定"能不能在选文件界面里点中它"。
     */
    private fun pickRagTextFile() {
        freezeImportTarget(fromText = true)
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            // 给具体类型 + 一个 `*/*` 兜底：只写 `text/plain` 会把 .md
            //（部分机型报 text/markdown）挡在外面，而用户对这种"明明在，却选不中"
            // 的读法是"这个 App 不支持导入 md"。
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain", "text/markdown", "text/x-markdown", "*/*"))
        }, REQ_IMPORT_RAG_TEXT)
    }

    /**
     * 读回选中的纯文本（.txt / .md）：**后台切段，直接落盘**，与「导入库」同一个容量。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 这一版把上一版的语义**倒过来**：落盘那份是真值，正文框是它的一个窗口
     * ═══════════════════════════════════════════════════════════════════════
     * 上一版这条路是"读进正文框、等用户点「建库/重建」才落盘"，于是它被卡在框上：
     *   · 框一次只装 [RagUiCap.MAX_DOC_CHARS]（4 万字符）；
     *   · 而这条路**不落盘**，所以框里被截之后「建库/重建」必须**拒绝**
     *     （拿头部去建会切出一个缺尾巴的库，而它与"文档本来就这么长"逐字相同）。
     * 两条合起来的结果是：**3 MB 的文档进来一个字都不会落库**，而屏幕上给出的
     * 出路只有"把文档切小、分成几个库导入"—— 那是把 App 该干的活推给用户。
     *
     * 用户问的正是这件事：「按照现在的设计，多大的文档可以直接导入」——
     * 而当时的答案是"这条路 4 万字符、那条路 16 MB / 16384 条"，两条并排的按钮
     * 差着两个数量级，屏幕上没有一个字提示该换按钮。
     *
     * ⚠⚠ 两条路差两个数量级的**根**不是"上限定错了"，是**落盘时机**：
     *   框是**编辑器**（要显示、要排版、要能敲），它的容量天然小一个数量级；
     *   把"导入"这个动作挂在框上，就等于把它的上限挂在了编辑器的显示能力上。
     *   所以这里改的不是那个数，是**时机**：解析完就落盘（[RagIndexStore.importEntries]
     *   是同一条路，「导入库」用它、容量也一样），框只是**落盘之后回读出来的窗口**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么"先落盘"不会变成"未经确认就改了磁盘"
     * ══════════════════════════════════════════════════════════════════════
     * 上一版那条注释担心的两件事，这一版都用**同一句确认框**解掉（[confirmRagImport]）：
     *   · "用户还没看清内容就改掉了库" -> 确认框里报出条数与目标库，
     *     并且**库名是照文件名预填、用户可以改**的（不静默落到当前库）；
     *   · "某一行是不是变成了一条，没有地方能核对" -> 落盘后 [doImportRagEntries]
     *     会切到那个库并 [loadRagDoc] 把正文**回读进框**，框里就是刚写下去的东西。
     *     核对点从"落盘之前"挪到了"落盘之后"，而它现在是**同一份磁盘内容**的回读 ——
     *     这比"我以为我导了什么"强。
     *
     * ⚠ 导入仍然是**追加合并、不可撤销**，所以确认框必须照旧说清"已有 N 条 → 导入后 M 条"。
     *   这一点一个字都不能省：它是用户唯一一次能反悔的机会。
     *
     * 上一步（点按钮时）已经问过"编辑器里的改动要不要先保存"——见调用点
     * [confirmDiscardRagDoc]。
     */
    private fun importRagTextFile(uri: Uri) {
        if (ragBuilding) {
            Toast.makeText(this, "正在建库，请稍候再导入", Toast.LENGTH_SHORT).show(); return
        }
        statusTv.text = "正在读取文本文件…"
        // 读整份文件 + 切行/切段都在后台：一篇几 MB 的文档在主线程读+切段会掉帧甚至 ANR
        //（且切段是逐字符走 [TextBoundary] 的，比 `split('\n')` 贵）。
        // 走本页既有的 ioExecutor（不是裸 Thread）：它随 Activity 关闭一起
        // shutdownNow，裸 Thread 会在页面已销毁后仍然持有 Context。
        ioExecutor.execute {
            val name = ExternalModel.displayName(this, uri)
            val text = try {
                RagArchive.readText(contentResolver, uri)
            } catch (e: RagArchive.NotText) {
                // ⚠ 只记**我们自己的**一句原因，不记 Uri（Uri 里可能带用户名 ——
                //   这条日志会进日志页、导出、崩溃探针，见 RagArchive.readText 的说明）。
                //   要查"是哪份文件"，靠用户手上的文件本身，不靠把它写进日志。
                LlmEngine.uiLog("[知识库] 纯文本导入失败：${e.reason}")
                ui.post {
                    statusTv.text = ""
                    Toast.makeText(this, e.reason, Toast.LENGTH_LONG).show()
                }
                return@execute
            } catch (t: Throwable) {
                LlmEngine.uiLog("[知识库] 纯文本导入异常：${t.javaClass.simpleName}")
                ui.post {
                    statusTv.text = ""
                    Toast.makeText(this, "读取失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            val parsed = RagArchive.decodePlainText(text, name)
            ui.post {
                statusTv.text = ""
                if (!parsed.ok) {
                    // 失败文案必须说清是**哪种**失败：选错文件（JSON / 二进制）与
                    // "文件是空的"处置完全不同，一句"导入失败"会让用户反复重试同一个文件。
                    Toast.makeText(this, "导入失败：${parsed.error}", Toast.LENGTH_LONG).show()
                    return@post
                }
                // 走**同一条**导入流程（确认框 + 落盘 + 回读），不另开一份：
                // 两条路各写一套"确认什么、报什么、落盘后干什么"的代价不是重复几行，
                // 是**改一处另一处不会红** —— 而这两条路今天起容量与结果完全相同。
                confirmRagImport(parsed, fromTextFile = true)
            }
        }
    }

    /**
     * 读回选中的文件并走导入流程。
     *
     * "读文件 + 解析"整个在后台线程：一份几 MB 的笔记文件在主线程读+解析会掉帧
     * 甚至 ANR。三种失败（读不出来 / 形状不认识 / 没有可用条目）都是**明确提示**，
     * 没有一条是静默返回 —— 静默返回会让用户以为"导入了但库是空的"。
     */
    private fun importRagArchiveFile(uri: Uri) {
        if (ragBuilding) {
            Toast.makeText(this, "正在建库，请稍候再导入", Toast.LENGTH_SHORT).show(); return
        }
        statusTv.text = "正在读取正文文件…"
        ioExecutor.execute {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("无法打开文件")
            }.getOrElse { e ->
                ui.post {
                    statusTv.text = ""
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            val parsed = RagArchive.decode(text)
            ui.post {
                statusTv.text = ""
                if (!parsed.ok) {
                    // 失败文案必须说清是**哪种**失败：选错文件 / 版本不支持 / 内容为空
                    // 的处置完全不同，一句"导入失败"会让用户反复重试同一个文件。
                    Toast.makeText(this, "导入失败：${parsed.error}", Toast.LENGTH_LONG).show()
                    return@post
                }
                confirmRagImport(parsed)
            }
        }
    }

    /**
     * 落盘前问一次 —— 导入会往库里加内容，且**没有撤销**。
     *
     * 文案里三件事必须出现，缺一件都会造成误解：
     *   ① 库里已有 N 条 + 本次 M 条 → 共 N+M 条（"追加合并"要说得像人话）；
     *   ② "导入后需点「建库/重建」才能检索"（否则用户对着搜不出东西的库反复试）；
     *   ③ 要覆盖的用户怎么走（**删库**再导入；「删索引」顶替不了它，见下那句）——
     *      直接给他路径，而不是让他自己猜。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 目标库怎么定：**两条导入路共用同一条规则**（ISSUE #259）
     * ══════════════════════════════════════════════════════════════════════
     * ① 文件信封里带了 `library`（本 App 导出的 `.json` 才有，跨设备导入时那才是
     *    "这份文件属于哪个库"）-> 用它；
     * ② 没带（裸 JSON 数组 / 裸 JSONL / **`.txt` · `.md`**）-> 用**当前选中的库**。
     *
     * ⚠ `.txt / .md` 上一版落在①里：它把**文件名**当库名（`RagArchive.plainLibName`，
     *   已删）。于是它和「导入库」的语义分家了 —— 用户想往现有库里追加一篇文档，
     *   唯一的办法是**先把文件改名**成库名；而更坏的形态是静默建出第二个库
     *   （他以为在往 A 里追加，磁盘上却多了一个"文件名"库，A 里一条没多）。
     *   现在它落在②：**先选库、再导入**，与 jsonl 逐字一致。
     *   见 `tools/run_rag_plain_import_guard.sh` 的判据（文件名只进来源标签）。
     *
     * ⚠ 不论走哪一支，`suggested` 都只是**输入框的预填值**，用户可以在确认框里改。
     *   但**不许静默落库** —— 落错库就是把别人的笔记混进当前库，而界面上什么都不说。
     *   所以下面那段文案会把"为什么预填这个名字"讲出来。
     */
    @SuppressLint("SetTextI18n")
    private fun confirmRagImport(parsed: RagArchive.Parsed, fromTextFile: Boolean = false) {
        // ⚠⚠ 目标库名读的是 [ragImportTarget]（**弹选择器之前冻结的那个快照**），
        //     不是此刻的 `currentKb`。两者的差别不是"严谨一点"：从按下「导入库」
        //     到这份文件解析完，中间隔着一次系统文件选择器 —— 而那期间本页的库名框
        //     失焦、`currentKb` 被覆盖成用户打了一半的名字（见 [ragImportTarget]）。
        //     读此刻的真值，就是把"我改了名但还没提交"读成"我就是要往这个新库里导"。
        // [!] 快照为空是**合法**的（用户从没选过库）：下面那句 hint 会让他自己填。
        //     这里刻意不做任何兜底 —— 凭空造一个库名正是本仓库判红过的写法
        //     （它会让"我明明选了库"与"App 给我编了一个库"在屏幕上长得一样）。
        val cur = ragImportTarget
        // ① 信封里带的库名（净过之后仍合法才算数）；② 都没有 -> 当前选中的库；
        // ③ 连当前库都空（用户从没选过库）-> 交回空串，由输入框的 hint 让用户自己填。
        //    ⚠ 不再兜底成 "导入的库" 那个凭空造出来的名字：它会让"我明明选了库"
        //      与"App 给我编了一个库"在屏幕上长得一样（而后者会静默建一个新库）。
        val fromEnvelope = runCatching { RagIndexStore.sanitizeName(parsed.library) }.getOrNull()
        val fromCurrent = RagIndexStore.sanitizeName(cur)
        val suggested = fromEnvelope ?: fromCurrent ?: ""
        val nameEt = EditText(this).apply {
            setText(suggested)
            setSingleLine(true)
            // 目标库为空（用户还没选过库）时**必须自己填** —— hint 说清这一点，
            // 而"导入"那一下会挡住空名（见下），不静默落到某个默认库上。
            hint = if (suggested.isEmpty()) "目标库名（必填）" else ""
        }
        val existing = RagIndexStore.entriesCountOf(this, suggested)
        val skippedMsg = buildString {
            if (parsed.skipped > 0) {
                append("\n⚠ 文件里有 ${parsed.skipped} 条无法识别的行（缺 t/s 字段或超长），将被跳过。")
            }
            // 条数比文件里多时要说清为什么（长条目被切成了多条）。
            // ⚠ 这句话本轮**换了位置**：`.txt / .md` 那条路以前自己在回执里说它
            //   （`applyRagTextImport`，已删），现在两条路共用这一个确认框 ——
            //   而对硬切来说，落在确认框里**才是对的**：导入是不可撤销的，
            //   用户点「导入」**之前**就有权知道"库里会多出多少条、
            //   以及为什么不是文件里那个数"，而不是落盘之后放一个 Toast。
            if (parsed.splitNoPunct > 0) {
                append("\nℹ 另有 ${parsed.splitNoPunct} 段是硬切的（超长且无句读）—— " +
                    "它们仍然全部进库，只是段边界落在字上。")
            }
        }
        val msg = buildString {
            append("文件里有 ${parsed.entries.size} 条正文。\n")
            append("导入 = **追加合并**到目标库，不会覆盖或修改已有条目。\n")
            // ── "目标库为什么是这个名字" —— 必须说，而且按**来源**分两句 ──────
            // 这两句的作用不是礼貌，是让"我往哪个库里导"在点「导入」**之前**可判定。
            // 不说的话，用户看到的只是一个预填好的名字，而它的来路分两种：
            //   · 信封自带（.json 导出件）-> 这是文件自己的意思；
            //   · 当前选中的库（裸数组 / JSONL / .txt / .md）-> 这是**他在列表里选的那个**。
            // 第二种尤其要说：`.txt` 那条路上一版拿**文件名**当库名，用户对
            // "目标库从哪儿来"的先验正好是反的（他以为会是文件名）—— 不说清，
            // 他会以为 App 又给他建了一个新库，或者反过来，以为追加进了文件名库。
            when {
                fromEnvelope != null -> append("目标库「").append(suggested)
                    .append("」来自**文件里带的库名**（本 App 导出的文件才有）。\n")
                fromCurrent != null -> append("目标库「").append(suggested)
                    .append("」= **你当前选中的库**（列表里点中的那个）—— ")
                    .append("要换成别的库，直接改下面这一格。\n")
                else -> append("⚠ 你还没有选中任何库，请在下面填一个**目标库名**")
                    .append("（已有同名库 = 追加进它；没有 = 当场建一个）。\n")
            }
            // `.txt / .md` 那一版多一句"落盘之后框里看到的是什么" ——
            // 这一版的核对点从"落盘之前"挪到了"落盘之后"（见 importRagTextFile），
            // 而用户对这一改动**没有任何先验**：他上一版点完看到的是框里被填满，
            // 这一版点完看到的是"库建好了、框里是它的正文"。不说清，他会以为
            // "框里这份还要我自己点一下建库才算数"。
            if (fromTextFile) {
                append("导入后这个库会**当场建好**（正文落盘），并把它的正文回读进下面的")
                append("正文框给你核对 —— 不必再点「建索引/重建索引」，那是算向量的事。\n")
            }
            append("当前「").append(suggested).append("」已有 ").append(existing).append(" 条 → 导入后 ")
                .append(existing + parsed.entries.size).append(" 条。\n")
            append("⚠ 导入后索引作废（正文变了、向量还是旧的），需点「建索引/重建索引」才能检索。\n")
            // 导入是**另一个能把条数堆很大**的入口（用户可能导几千条），
            // 而"重建要多久"由条数主导 —— 同一句话必须在这里也说一遍，
            // 否则用户会以为"导入完就能搜"，点重建时才发现要等几分钟。
            // 复用 [RagCost]：不在这里另算一套（两处各算一遍必然漂移）。
            val after = existing + parsed.entries.size
            if (after >= RagCost.WARN_ENTRIES) {
                val (lo, hi) = RagCost.estimateSeconds(after)
                append("\n⚠ 重建要逐条编码：$after 条，").append(RagCost.humanSeconds(lo, hi))
                    .append("。\n")
            }
            // ⚠⚠ 这一句给的是「覆盖」的**唯一**走法，而它必须指向「删库」，
            //     不是「删索引」—— 这是 ISSUE #228 报的一处**文案与行为不符**：
            //     用户照旧句子走了一遍，发现导入之后条目**翻倍**了。
            //
            //     根因：「删索引」的语义在 ISSUE #259 第 ② 条改过了 —— 它现在
            //     **只作废索引、正文一条不动**（正是用户当年要的那个行为，
            //     见 doDropIndex 的类头）。于是"删索引再导入"落在磁盘上的是
            //     "老正文还在 + 新正文追加进来" = **追加合并**，恰恰是这一句
            //     要用户避开的那个结果。要真的覆盖，必须先让目标库**连正文
            //     一起没了**，而那个动作只有「删库」做得到。
            //
            // [!] 不能再退回"删索引连正文一起删"那种写法：那正是 ISSUE #259
            //     判过的逻辑错误（用户的原话"删索引为啥要删正文"）。改的是**这句
            //     提示**，不是按钮的行为 —— 判决与 doDeleteKb/doDropIndex 的
            //     分工一致：要覆盖 -> 删库 + 导入；只想重算向量 -> 删索引。
            append("若要的其实是**覆盖**：先在列表里点该库 → 点「删库」，再导入。\n")
            append("（「删索引」顶替不了这一步：它只作废向量、正文一条不动，导入仍是追加合并。）")
            append(skippedMsg)
        }
        // ⚠ 标题必须按**来源**分流：`.txt / .md` 那一版以前走的是"回填编辑器"，
        //   于是它的文案里没有任何一句"会写盘"；现在它落盘了，而确认框若还写着
        //   上一版的口气，用户会以为"只是读进框里"—— 那是本仓库最忌讳的那种
        //   "提示与动作不符"（他以为不可撤销的那一步不存在，就没细看条数）。
        val title = if (fromTextFile) "导入文档（追加合并，会写盘）"
        else "导入库（追加合并）"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("导入") { _, _ ->
                val target = RagIndexStore.sanitizeName(nameEt.text.toString())
                if (target == null) {
                    // 空名与非法名同一句提示：两者的处置都是"改一个合法名字再点一次"，
                    // 而分成两句会让"我什么都没填"被读成"App 不让我导入"。
                    Toast.makeText(this, "库名非法（字母/数字/中文/-/_/.，非空且 ≤64 字）",
                        Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                doImportRagEntries(target, parsed)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 真正落盘（后台），然后把"这个库现在需要重建"这件事**画到界面上**。
     *
     * 导入之后必须做的三件界面动作，缺一件用户就会以为导入没生效：
     *   · 切到目标库（点库才会回填编辑器，用户才看得见刚导进来的东西）；
     *   · 刷库列表（`list()` 按 index.bin 列库 —— 索引刚被删，条目数也会变）；
     *   · 状态行（它会输出"正文已改，需重建"那一句，是用户唯一能看到的指路牌）。
     */
    private fun doImportRagEntries(target: String, parsed: RagArchive.Parsed) {
        // 快照用掉了就清：留着的话，下一次与它无关的落盘会拿到一个过期的目标库名
        //（"写进了上一次那个库"在屏幕上没有任何现象 —— 见 [ragImportTarget]）。
        // [!] 这句话在**清除快照之前**读 —— 清完再读就永远是 false 了
        //     （`ragImportFromText` 与目标库名是一对，同生同灭）。
        //     它今天只用于日志的一行，但把它读成常量会让"这两条路共用一条导入流程"
        //     这件事在日志里看不出来。
        val fromTextFile = ragImportFromText
        clearImportTarget()
        statusTv.text = "正在导入…"
        ioExecutor.execute {
            LlmEngine.uiLog("[知识库] 导入" + (if (fromTextFile) "文档" else "库") +
                " -> 「$target」：${parsed.entries.size} 条" +
                (if (parsed.skipped > 0) "（跳过 ${parsed.skipped} 行）" else ""))
            val result = runCatching { LlmEngine.importRagEntries(this, target, parsed.entries) }
                .getOrElse { t ->
                    LlmEngine.uiLog("[知识库] 导入异常：${t.javaClass.name}: ${t.message}")
                    "导入异常：${t.javaClass.simpleName}: ${t.message}"
                }
            ui.post {
                statusTv.text = ""
                val ok = result.startsWith("已导入")
                if (ok) {
                    // 编辑器里这份正文的归属仍是旧库（或空），先清掉：
                    // 接着 loadRagDoc 会用新库的正文把它整段换掉，而 ragDocDirty
                    // 必须在那之前归零，否则切库确认框会拿一份"其实已经被覆盖"的
                    // 内容来问用户要不要保存。
                    ragDocOwner = ""
                    ragDocDirty = false
                    RagInject.setCurrentKb(this, target)
                    ragKbEt.setText(target)
                    loadRagDoc(target)
                }
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                // ── 导入把内容写进了那个"新建但未落盘"的库 -> 它落地了 ──────────
                // 放在**成功**分支里：写盘失败时磁盘上仍然没有它，pending 必须留着
                //（留着才有"离开即丢弃"这条出口，而清早了就是"库名还在、内容没了"）。
                // ⚠ 顺序：先 [pendingKbLanded] 再 refreshRagList —— 它自己也会刷一次，
                //   但下面这次刷新是在"归属/currentKb 都已经切到 target"之后，两次
                //   刷新的结果一致（幂等），多一次只是几毫秒的后台遍历。
                // ── 目标库正是那个"新建但未落盘"的库 -> 它落地了 ────────────────
                // 形状是"用户先点「新建库」开了一个库，再往里导入文档"。
                // `ragPendingKb` 只在**与目标库同名**时才算落地（否则用户导入的是另一个库，
                // 那个新建的空库还没落盘、也不该因为这次导入被清掉）。
                // ⚠ 判据必须是"落盘成功"（ok）：失败时磁盘上仍然没有它，
                //   清早了就是"库名还挂在界面上、而它其实没有目录"。
                if (ok && target == ragPendingKb) pendingKbLanded()
                refreshRagList()
            }
        }
    }

    private fun refreshModelList() {
        val myGen = ++modelListGen
        val sel = modelFile?.name
        // 驻留值必须在**提交任务时**取一次快照，不能留到渲染回调里现取：
        // 中间用户可能点了加载/卸载，渲染时就取到"新值"，于是这一屏把**加载前**的
        // 取数（rows）与**加载后**的驻留值拼在一起 —— 那正是"显示与实际不符"的同一族病。
        // 取快照至少保证这一屏内部自洽；下一次加载/卸载都会再调本函数拿到新一轮。
        val loaded = loadedFile()?.name
        ioExecutor.execute {
            val rows = runCatching { ModelStore.rows(this) }.getOrNull()
            ui.post { if (myGen == modelListGen) renderModelList(rows.orEmpty(), sel, loaded) }
        }
    }

    /**
     * 画模型库列表。
     *
     * `sel` 与 `loaded` 是**两个不同的问句**，此前只画了前者：
     *   · [sel]     = 用户点中的那一个（点一次就变，不代表已在内存里）；
     *   · [loaded]  = 引擎真正驻留的那一个（`LlmEngine.loadedPath`，只有加载/卸载才变）。
     * 只标选中态时，"我点了 b、a 还在内存里"这件事在列表上完全看不出来 ——
     * 一眼扫过去 b 带 `●`，看着就像"在用 b"。所以两个标记各给各的、
     * **允许同时出现在不同的行上**，并把含义写进行标。
     */
    @SuppressLint("SetTextI18n")
    private fun renderModelList(rows: List<ModelStore.Row>, sel: String?, loaded: String?) {
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
            val isLoaded = f.name == loaded
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // 行标三态：内存中 / 仅选中 / 都没有。两者并存时写清"谁在内存、内存里还有谁"。
            val mark = when {
                isSel && isLoaded -> "▶● "
                isLoaded -> "▶  "
                isSel -> "●  "
                else -> "○  "
            }
            val tailTxt = when {
                r.missing -> "  ⚠ 原文件已丢失"
                isSel && isLoaded -> "  (${r.bytes / 1048576} MB) · 已加载，正在用"
                isLoaded -> "  (${r.bytes / 1048576} MB) · 在内存里，但当前选中的是别的（未切换）"
                isSel -> "  (${r.bytes / 1048576} MB) · 已选中，尚未加载"
                else -> "  (${r.bytes / 1048576} MB)"
            }
            val tv = TextView(this).apply {
                text = mark + (if (r.external) "🔗 " else "") + r.alias +
                    (if (r.alias != f.name.removeSuffix(".gguf")) " · ${f.name}" else "") + tailTxt
                textSize = 13f
                if (isSel || isLoaded) setTypeface(typeface, Typeface.BOLD)
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
        updateModelTv()
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

    /**
     * 当前**驻留内存**的那个模型的文件名；没有=null。
     *
     * 判据只有一处：`LlmEngine.loadedPath`（引擎真值）。**不能**拿本页的 [modelFile]
     * （那是"用户选中的"）或 `HttpApi.currentModel`（那是协议里的别名）来回答这个问题 ——
     * 这两个都可能在**没加载**新模型的情况下就变了：
     *   · 点列表里的另一个模型 -> `modelFile` 立刻换掉，而内存里还是旧的那份；
     *   · 给已加载模型改别名 -> `HttpApi.currentModel` 跟着换，内存里没变。
     * 用户看到的"当前是 b"和"内存里是 a"就是这么来的：状态行取的是选中值，不是驻留值。
     */
    private fun loadedFile(): File? = LlmEngine.loadedPath?.let { File(it) }

    /**
     * 模型库第一行（`当前:`）的文案。
     *
     * 选中与驻留**是两件事**，各报各的；两者不一致时显式点出来 ——
     * 这正是本行此前最容易误导人的场景：用户点了 b 想切换、还没加载，
     * 而 a 还在内存里，只报"当前: b"会让人以为 a 已经让位了。
     */
    private fun modelStateText(): String {
        val sel = modelFile
        val loaded = loadedFile()
        val loadedTxt = loaded?.name?.let { "已加载: $it" } ?: "未加载任何模型"
        if (sel == null) return "$loadedTxt（未选择模型）"
        val selTxt = "已选中: ${sel.name}  (${sel.length() / 1048576} MB)"
        if (loaded == null) return "$selTxt · $loadedTxt"
        if (loaded.name == sel.name) return "$selTxt · 已加载（同一份，正在用）"
        return "$selTxt · ⚠ 但内存里仍是「${loaded.name}」——点「加载模型」才会真正切换"
    }

    /**
     * 重画模型状态行。只读当前选中（[modelFile]）与引擎驻留两份真值。
     *
     * 顺手记下"此刻画的是哪一份驻留值"（[shownLoadedPath]），轮询据此判有没有变化。
     * 放在这里是因为**所有**重画路径都必走它 —— 散在各调用点漏掉一处，
     * 后果就是轮询多画一次（无害）或漏报一次变化（有害，而这正是要防的）。
     */
    private fun updateModelTv() {
        shownLoadedPath = LlmEngine.loadedPath.orEmpty()
        modelTv.text = modelStateText()
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
                            // 复用统一文案：删掉的可能正是**内存里**那一份，
                            // 直接写死"未选择模型"会把驻留状态一起抹掉。
                            updateModelTv()
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
        // 聊天记录导入与模型导入共用"选文件"这套，但请求码必须分开：
        // 共用一个码时，选模型的那次回调会被聊天导入的解析器吃掉（反向亦然），
        // 而症状是"选完文件什么都没发生" —— 两个功能互相抵消。
        if (requestCode == REQ_IMPORT_CHAT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importArchiveFile(uri)
        }
        // 知识库导入同理各走各的：四个码（42 选模型 / 43 聊天记录 / 44 导入库 /
        // 45 导入 .txt·.md）合一个就会出现"选完文件什么都没发生"。
        if (requestCode == REQ_IMPORT_RAG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importRagArchiveFile(uri)
        }
        // 「导入 .txt / .md」必须**自己一个码**：它与上面那个共用码时的症状不是崩，
        // 而是"选完文本文件后被当库文件解析" -> 一次信息很足的失败提示，用户却会
        // 读成"我这个 md 不能用"，然后去改文件内容（完全错的方向）。
        if (requestCode == REQ_IMPORT_RAG_TEXT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importRagTextFile(uri)
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
        updateModelTv()
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
        val thr = parseThreads()
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
                // 加载完成后驻留值变了：状态行与列表标记都要重画（否则"在内存里"的
                // 标记还停在旧的那一行上，与实际相反）。
                updateModelTv()
                refreshModelList()
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
                updateModelTv()
                refreshModelList()
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
        htpHintTv.setTextColor(if (warn || mismatchAlert) pal.warn else pal.textSecondary)
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
                .putExtra(InferenceService.EXTRA_THREADS, parseThreads())
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
        probeResultTv.setTextColor(pal.textSecondary)
        probeResultTv.text = "探测中… GET http://$host:$probePort/health"
        LlmEngine.probeMark("[探测] 开始 GET /health host=$host port=$probePort " +
            "服务自报运行中=$live(端口=$runningPort) 端口框=$port")

        Thread {
            val r = HealthCheck.probe(host, probePort)
            val v = HealthCheck.verdict(r)
            // 结论缓存一份给「后台保活」状态行（见 [lastProbeResult] 的理由：
            // 那一页不该为了显示冻结痕迹再打一次自己的服务）。
            lastProbeResult = r
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
                    HealthCheck.Verdict.ALIVE -> pal.ok
                    HealthCheck.Verdict.DEGRADED -> pal.warn
                    HealthCheck.Verdict.UNREACHABLE -> pal.error
                })
                // 探测结论不只在设置页留着，聊天页状态栏也同步一份——
                // 用户此时通常正拿着另一台设备连不上，想立刻看到结论。
                statusTv.text = report.lineSequence().first()
                // 探测结论里含冻结痕迹（frozen_*），「后台保活」那一行要跟着更新 ——
                // 否则用户点了探测、看到报告里"上次被冻 63s"，而设置页那行还写着
                // "没检出过被冻"，两份读数打架时他会信哪一份都错。
                refreshKeepAlive()
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
            // ⚠ `embed_parity_*` / `embed_retrieve_*` 那十几个字段**不进探活报告正文**
            // （见 `HealthCheck.format` 的 kdoc：日常探活里它们看完也不知道该做什么），
            // 但**完整落在这一页**：`/health` 里报了什么，日志里就有对应的一行 ——
            // 不然那十几个字段就成了"写在服务端、谁也没读过"的摆设
            // （本仓库反复记录的那族：算出来没人读 == 没算）。
            for (line in HealthCheck.rawEmbedProbeLines(r)) LlmEngine.uiLog(line)
        }.apply { name = "health-probe"; isDaemon = true }.start()
    }

    private fun startServerPoll() {
        pollRunning = true
        val r = object : Runnable {
            override fun run() {
                if (!pollRunning) return
                // 驻留值可能被**本页之外**的路径改掉（最典型：`停止服务` 由
                // InferenceService 在服务侧卸载模型）；发现与上一次画的不一样就重画
                // 状态行与列表标记。轮询本来就是最省事的唯一观察点，
                // 而"卸掉了还写着已加载"会让用户对着一个空引擎反复点生成。
                val loadedNow = LlmEngine.loadedPath.orEmpty()
                if (loadedNow != shownLoadedPath) {
                    shownLoadedPath = loadedNow
                    updateModelTv()
                    refreshModelList()
                }
                // 嵌入那一份同构地钉一条。它没有"服务侧卸载"这条路径，但**判据不该
                // 只在有已知路径时才立** —— 立在这里，"嵌入驻留值从别处变了"这件事
                // 无论将来从哪冒出来都会被看见；不立，就只能靠每次改代码的人记得
                // 回来补一处重画（本仓库反复吃过这个亏）。
                val embedNow = LlmEngine.embedPath.orEmpty()
                if (embedNow != shownEmbedPath) {
                    shownEmbedPath = embedNow
                    renderEmbedStatus()
                    refreshEmbedList()
                }
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
        // 卸载后驻留值没了：状态行与列表标记一起重画。不重画的话列表里那个 `▶`
        // 会继续挂在已经空掉的引擎上 —— "看着还在内存里"正是本例要终结的说法。
        updateModelTv()
        refreshModelList()
        // 卸载后必须重画提示区，否则 HTP 说明还停留在上一个模型的信息上
        renderBackendState()
        // 嵌入状态行里同时报「对话模型在不在位」，所以对话卸载也要重画它 ——
        // 否则那一行会一直写着"对话模型同时在位"，而它其实已经被卸载了。
        renderEmbedStatus()
        Toast.makeText(this, "模型已卸载", Toast.LENGTH_SHORT).show()
    }

    /**
     * 渲染聊天记录：一条消息一个气泡；[streaming] 非空时在其后挂一条"正在生成"的可变气泡。
     *
     * 调用点保持旧名 `renderChat(null)` 不变（全是"重绘一遍"的语义），而流式那一路
     * 不再走这里 —— 它走 [setStreamingText]，只改那一条的文本（**不重建 View**，
     * 否则长会话每 token 要 inflate 几十个 View）。
     */
    private fun renderChat(streaming: String?) {
        renderHistory()
        // 流式那一条不进 chatBubbles：它随时会被收尾/取消路径丢弃（重新渲染一次
        // 就从列表里消失了），混进可删除索引会让长按反查到一个已经不存在的位置。
        streamingBody = streaming?.let { addBubble("assistant", it, mutable = true) }
        if (chatContainer.childCount == 0) {
            chatContainer.addView(TextView(this).apply {
                text = "还没有对话。在下方输入后点「生成」。"
                textSize = 12f
                setTextColor(pal.textFaint)
                setPadding(px(4), px(10), px(4), px(10))
            })
        }
        chatContainer.post { followChatBottom() }   // (点5): 新内容出现在下方，无需手动划到底
    }

    /** 按当前会话重建整屏气泡（system 不显示）。 */
    private fun renderHistory() {
        chatContainer.removeAllViews()
        chatBubbles.clear()
        streamingBody = null
        val hist = chatHistory(sysEt.text.toString().trim())
        for ((i, m) in hist.withIndex()) {
            if (i == 0 && m.first == "system") continue
            addBubble(m.first, m.second, mutable = false)
        }
    }

    /**
     * 建一条气泡，返回"正文 View"（[mutable] = true 时它就是那条可变气泡，
     * 上层逐 token 改它的 text，绝不重建 View）。
     *
     * 视觉上怎么分左右：**不靠 `gravity`**。它管的是"文字在这条 View 内部怎么对齐"，
     * 而消息很长时 View 撑满整行 —— 那时长消息和短消息看起来都在同一位置，
     * 分不出谁说的。所以这里的判据是**整条气泡的左右缩进**（user 缩左、assistant 缩右）：
     * 无论文字多长，缩进都在，左右感也就都在。
     * 这个取舍的代价是长消息的可视宽度少 48dp —— 手机上比"分不清谁说的"划算。
     *
     * 长按 → 删除这一条。**下标必须当场反查**（`chatBubbles.indexOf`）：
     * 监听里捕获的循环变量在列表被重绘之后就指向别的消息了，而重绘随时会来。
     */
    private fun addBubble(role: String, text: String, mutable: Boolean): TextView {
        val isUser = role == "user"
        val body = TextView(this).apply {
            this.text = text
            textSize = 14f
            // **不要 setTextIsSelectable(true)**：TextView 一旦可选中，长按就被它
            // 拿走去做"选文字"，外层 wrap 的 setOnLongClickListener 永远收不到 ——
            // 症状正是"长按 AI 的输出或者我的输入都没反应"（手指落在文字上，
            // 而文字占了气泡的绝大部分面积）。复制走气泡外的复制按钮 / 长按菜单里的
            // "复制这一条"，不让"能选文字"和"能长按删除"抢同一个手势。
            setPadding(px(8), px(2), px(8), px(2))
            setTextColor(if (isUser) pal.userBubbleText else pal.aiBubbleText)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 气泡本体的可见形状：圆角 + 底色。只靠缩进不算气泡 ——
            // 那只是"对话布局有变化"，看不出这是一段独立消息。
            background = bubbleBackground(isUser)
            setPadding(px(10), px(6), px(10), px(6))
            addView(bubbleHeader(role), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 左右缩进不同 = 一眼能分出谁说的话（配合气泡底色，左右感与"这是气泡"都在）
            setPadding(if (isUser) px(48) else 0, px(4), if (isUser) 0 else px(48), px(4))
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 长按落在整条气泡上（不只是文字上），手指才好按。
        // **必须 isLongClickable**：子 View 不接管时长按才会浮上来，
        // 不显式置位这道闸门会被子 View 的行为反噬（正文若可选中就吃掉了）。
        wrap.isLongClickable = true
        wrap.setOnLongClickListener { v ->
            val idx = chatBubbles.indexOf(v)
            if (idx >= 0) confirmDeleteMessage(idx)
            true
        }
        chatContainer.addView(wrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 可变的那条（流式）不进 chatBubbles：它随时会被收尾/取消路径丢弃，
        // 混进可删除列表会让长按删到一个已经不存在的位置。
        if (!mutable) chatBubbles.add(wrap)
        return body
    }

    /**
     * 气泡底色与圆角。**这是"看起来像气泡"的唯一来源** —— 上一版只有左右缩进，
     * 在真机上就只是"对话布局有变化"，看不出一条条独立消息。
     * user 与 assistant 用两个明显不同的色，深浅都在浅色底上留足对比。
     */
    private fun bubbleBackground(isUser: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = px(12).toFloat()
        setColor(if (isUser) pal.userBubble else pal.aiBubble)
    }

    /** 角色标签：只在这里出现一次（不再把 "我："/"AI：" 拼进正文，与喂模型的拼接彻底脱钩）。 */
    private fun bubbleHeader(role: String): TextView {
        val isUser = role == "user"
        return TextView(this).apply {
            text = if (isUser) "我" else "AI"
            textSize = 10f
            setTextColor(pal.textFaint)
            gravity = if (isUser) Gravity.END else Gravity.START
        }
    }

    /**
     * 流式：只改正在生成的那一条的文本。
     *
     * 这里**不能**调 `renderChat(ps)` —— 那会重建整屏气泡，长会话每 token
     * inflate 几十个 View。旧写法（一个 TextView 拼全部）没有这个问题，
     * 所以"换成气泡"这件事必须在流式这条路上同样成立，否则就是拿性能换外观。
     */
    private fun setStreamingText(text: String) {
        streamingBody?.text = text
    }

    /**
     * 长按气泡 → 对**这一条**动手。[index] 由 [addBubble] 在点击那一刻从 View
     * 反查得到（`chatBubbles.indexOf`），不是闭包捕获的循环变量。
     *
     * 三个出口：
     *   · 复制 —— 正文不再可选中（选中会吃掉长按，见 [addBubble]），
     *     所以"要复制"的出口只能跟着长按菜单一起给；
     *   · 重新生成 —— 只在长按的是**最后一条**时给。语义是"从这一条往下重跑"：
     *     长按 assistant = 重写这一条答案；长按 user = 这一句重新问一遍。
     *     两条路径**都**是重写（旧答案不留、用同一句问话再采一个新答案）——
     *     与主流客户端一致：那里没有"半成品"概念，被停止的回答也算历史的一条，
     *     对它「重新生成」永远等于"换一个"，不是"接着往下写"。见 [startGeneration]。
     *     刻意**不**沿用"最后一条 user"那条口径 —— 那个口径下面的重新生成
     *     把最后一条 user **之后**的东西全丢了再重跑；用户长按的那一条才是它要动的范围。
     *     不是最后一条时**不给这个出口**（而不是给了再灰掉）：菜单里出现一个点了没反应的按钮，
     *     正是最难解释的那类现象。
     *   · 删除 —— 只删这一条（见 [SessionStore.deleteAt]）。
     *
     * 不做"删 user 连带删 assistant"：用户要的就是"任意删、全靠我决定"。
     */
    private fun confirmDeleteMessage(index: Int) {
        if (generating) { Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show(); return }
        val msgs = SessionStore.load(this, currentSessionId)
        val msg = msgs.getOrNull(index) ?: return
        val who = if (msg.role == "user") "我" else "AI"
        val brief = msg.content.replace('\n', ' ').take(24)
        val isLast = index == msgs.size - 1
        // 重生成只有一个口径：丢掉这一条、用同一句问话重采（旧答案不留）。
        // 被「停止」打断的半截回答**也落盘了**（见 [startGeneration]），
        // 于是它和正常答完的那条在存储层长得一样 —— 无需、也不该再分两种语义。
        val regen = if (!isLast) null else "重新生成这一条"
        val builder = AlertDialog.Builder(this)
            .setTitle("这一条消息")
            .setMessage("$who：$brief${if (msg.content.length > 24) "…" else ""}\n\n" +
                when {
                    regen == null -> "删掉后不再进入喂给模型的历史。"
                    else -> "重新生成会丢掉这一条，用同一句问话再采一个新答案（旧答案不会留着）。"
                })
            // 复制放在这里：正文不再可选中（选中会吃掉长按，见 addBubble），
            // 于是"要复制"的出口只能跟着长按菜单一起给，否则用户就丢了这个能力。
            .setNeutralButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("chat message", msg.content))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("删除") { _, _ ->
                SessionStore.deleteAt(this, currentSessionId, index)
                // 删掉一条**不改 KV** —— 这是"删末条之后 KV 完全没复用"的第二个入口，
                // 与 [regenerateLast] 同一个 bug。分叉点之前那一段前缀本来就还能用，
                // plan_reuse 的逐 token 公共前缀会复用到分叉点、并自己 `seq_rm(0, reuse, -1)`
                // 砍掉后面那截。在这里调 `resetKvCache()`（全清）= 把还能用的前缀一起扔了，
                // 下一轮从 token 0 全量重算。残留尾巴不会读错（见 [regenerateLast] 第 3 条）。
                renderChat(null)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
        // 中位按钮只在"长按的是末条"时挂上（`regen` 为 null 表示不挂）。
        // 用 Builder 的 `setNeutralButton`，不是 `AlertDialog.setButton` ——
        // 后者不在 Builder 上（`Unresolved reference`，只在编 APK 时才暴露）。
        if (regen != null) builder.setNeutralButton(regen) { _, _ -> regenerateLast(index) }
        builder.show()
    }

    /**
     * 从第 [index] 条开始重跑。
     *
     * ══════════════════════════════════════════════════════════════════
     * 为什么边界是"第 index 条"而不是"最后一条 user"
     * ══════════════════════════════════════════════════════════════════
     * 长按的那一条**就是**用户要动的范围，这不需要再猜。曾经想统一成"最后一条 user"
     * （那才是"上一轮提问"），但那条口径会把最后一条 user **之后**的东西全丢掉，
     * 丢掉的范围比用户指的那一条大，且不可恢复。
     *
     * ══════════════════════════════════════════════════════════════════
     * 三条顺序上的硬约束
     * ══════════════════════════════════════════════════════════════════
     * 1. **截断用"这条之前"当边界**（`truncateTo(index)` 保留前 index 条）：长按末条时
     *    它等于"原样重跑"，只换 seed 与 sampler —— 这正是用户要的"换一个答案"。
     * 2. **是 user 就把这一条重新落盘当本轮提问**（assistant 不用，重跑时由模型重写）：
     *    截断会把它自己也带走，不补回的话本轮的 prompt 就少掉这次提问本身，
     *    模型会直接从 system 之后开始续写。
     *    内容取自**存储里这一条**，不从输入框取 —— 用户可能已经在输入框里敲下一句了，
     *    那一句跟本次重跑毫无关系。
     * 3. **这里绝不能作废 KV（那条断言是"不得出现"，不是"必须出现"）**：重跑点**之前**的
     *    历史一条没动，KV 前缀在这一段上本来还能复用；上一版在这里调 `resetKvCache()`，
     *    而它是**全清**（`seq_rm(0,-1,-1)`），于是下一轮 `plan_reuse` 第一行
     *    `kv_valid == false` 直接返回 `reuse = 0` —— **从 token 0 全量重算**。
     *    用户看到的就是"删末条 / 重跑末条之后 KV 完全没复用"。
     *
     *    复用本来就是自动的：`plan_reuse` 用**逐 token 比最长公共前缀**定 reuse，
     *    命中分支自己会 `seq_rm(0, reuse, -1)` 精确砍掉分叉点之后的尾巴。所以这里
     *    只要**不碰** KV，复用立刻回来；残留的那段尾巴也不会读错（见 [confirmDeleteMessage]
     *    与 kv_prefix.h 的入口条件），代价只是它多占显存到下次覆盖。
     *
     *    **作用域只能靠断言钉住**：多一次是全清（丢缓存、复用归零），少一次是无所谓
     *    （plan_reuse 自己会砍）。这条断言的方向是"不得出现 `resetKvCache`"——
     *    上一版守卫钉的是"里面有 `resetKvCache()`"，方向正好钉反，这是它全绿而 bug 还在的原因。
     */
    private fun regenerateLast(index: Int) {
        if (generating) { Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show(); return }
        if (!LlmEngine.hasModel) {
            statusTv.text = "模型未加载：先点「加载模型」"
            Toast.makeText(this, "模型未加载：先点「加载模型」", Toast.LENGTH_SHORT).show()
            return
        }
        val target = SessionStore.load(this, currentSessionId).getOrNull(index) ?: return
        // 从长按的那一条开始**一律**重跑：把它从历史里丢掉再重采一个答案。
        // 「停止」的半截回答也落盘了（见 [startGeneration]），所以这里不再有"保留半截接着写"
        // 那一支 —— 末条 assistant 长得一样，重跑就是重写。
        // `truncateTo` 返回 false 表示"没有变化"（截断点已经在末尾）—— 那不是失败，
        // 末条是 user 时要接着把它重新落盘，否则重跑的 prompt 会少掉这次提问本身。
        val truncated = SessionStore.truncateTo(this, currentSessionId, index)
        // 重问同一句时补回这条 user（截断把它带走了）。内容**从存储取的这一条**，
        // 不从输入框取 —— 用户可能已经改了输入框准备问下一句，那一句跟本次重跑无关。
        if (target.role == "user" && SessionStore.load(this, currentSessionId).size == index) {
            SessionStore.appendUser(this, currentSessionId, target.content)
        }
        if (!truncated && target.role == "assistant") return   // 下标已失效：原地不动
        // 这里**不碰 KV**：截断点之前的历史一条没动，plan_reuse 自会据"逐 token 公共前缀"
        // 复用到这一点、并精确砍掉分叉点之后的尾巴（见上面第 3 条）。上一版在此调
        // `resetKvCache()`（全清）把整段前缀也扔了，才是"重跑后 KV 完全不复用"的根因。
        startGeneration(preparedMessages())
    }

    /**
     * 本轮要喂给模型的消息 = system（若有）+ 落盘历史 + **本轮的 RAG 注入**。
     *
     * 普通生成与「重新生成」共用这一个出口：两边的差别只在**调它之前历史被改成了什么**，
     * 而不是"拼历史的方式"。各拼一份就是判据漂移的起点（比如 system 只在一处被带上），
     * RAG 注入同理 —— 它是"喂给模型的那一份"的组成部分，必须跟着这一处走。
     *
     * 检索结果**不落盘、不进 `SessionStore`**，每轮按当前开关与当前提问重算：
     *   · 存进历史会让上一轮的检索内容变成下一轮的**上下文**，而它对下一轮未必相关 ——
     *     模型会顺着它继续引用，表现为"答着答着跑题了"，且看不出是谁干的；
     *   · 用户消息是**提问**、system 是**设定**（都持久），检索是**本轮的附加信息**，
     *     生命周期不同。
     *
     * 注入位置是第 4 步改的：拼到**最后一条 user 之前**，不再进 system 段。
     * 原因只有一条 —— system 在 pos 0，而注入块每轮都变，于是 KV 前缀复用的分叉点
     * 被拉到最前面，`reuse` 恒为 0（用户报的"开了知识库就没缓存了"）。
     * 挪到本轮提问处之后，公共前缀 = 之前每一轮的完整对话。详见
     * [RagInject.attachBeforeLastUser]。
     */
    private fun preparedMessages(): List<Pair<String, String>> {
        sysEt.clearFocus()
        val sys = sysEt.text.toString().trim()
        SessionStore.setSystem(this@EngineActivity, currentSessionId, sys)
        // ⚠ 回写的是**手打的那一段**，与"实际生效的那一段"（可能是快照）无关。
        //   两段必须各存各的：合成一段之后，用户挂卡时手打的 system 会被快照
        //   顶掉、摘卡时又找不回来 —— 不可恢复。
        val msgs = chatHistory(sys)
        val rag = ragBlockFor(msgs) ?: return msgs
        // 注入**只动最后一条 user 的正文**（拼在提问之前）：消息角色序列一个字节不变，
        // 分叉点落在"本轮提问处"。system 段与其余历史一条都不动 —— 它们正是要被 KV 复用的部分。
        return RagInject.attachBeforeLastUser(msgs, rag.block)
    }

    /**
     * RAG 注入的**唯一入口**：按当前开关 + 当前库 + 最后一条用户消息去检索，
     * 返回要拼进 prompt 的片段（第 4 步起拼在最后一条 user 之前）；null = 本次不注入。
     *
     * 为什么拿"最后一条 user 消息"当查询：它就是用户这一轮真正在问的东西。
     * 用整段历史当查询会把前几轮的话题也平均进来，查询向量偏离当前意图，
     * 命中率反而下降 —— 而"检索不到"与"知识库里确实没有"表现完全一样，无法归因。
     */
    private fun ragBlockFor(history: List<Pair<String, String>>): LlmEngine.RagBlock? {
        val lastUser = history.lastOrNull { it.first == "user" }?.second.orEmpty()
        return LlmEngine.ragContext(
            this, RagInject.currentKb(this), lastUser, RagInject.enabled(this))
    }

    /** 解析参数区 → 采样参数。非法时提示并返回 null（调用方负责收尾，别把看门狗留着）。 */
    private fun currentSamplingParams(): SamplingParams? {
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
        }
        return sp
    }

    /** 用户点「生成」：把输入框这一句落盘成一条 user，再重跑一次。 */
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
        SessionStore.appendUser(this@EngineActivity, currentSessionId, p)
        SessionStore.maybeAutoTitle(this@EngineActivity, currentSessionId, p)
        refreshSessionTitle()
        startGeneration(preparedMessages())
    }

    /**
     * 真正开跑一轮生成。**所有起点共用这一段**（新问一句 / 重新生成 / 记录被截断后的重跑）：
     * 收尾、看门狗、停止归属、落盘都只有这一份实现。
     *
     * 注意这里的**先后次序**：本轮那条"正在生成"的可变气泡在 [renderChat] 里建，
     * 而调用方负责在进来之前把历史调成要重跑的样子。反过来（先建气泡再截断历史）
     * 就等于"在还没确定新答案能不能开始时，先把旧的拿掉"—— 参数非法或 native 拒绝
     * 启动时，用户看到的正是"我的答案凭空消失了"。
     *
     * **「停止」后的半截回答照样落盘**（与主流客户端一致）：停止只表示"我不等了"，
     * 不改变"这条已经存在"这件事。于是历史里不会再出现"半成品"这种状态 ——
     * 成功与停止两条收尾路径都只在"有没有产出内容"这一个条件下落盘，
     * 没有第二套判据，也就不需要区分"页面上那条是不是历史里的"标志位。
     */
    private fun startGeneration(messages: List<Pair<String, String>>) {
        val sp = currentSamplingParams() ?: return
        // 这一句多了一条"正在生成"的可变气泡（空文本），用户会看到它从空开始长字。
        // 必须**在 `generating = true` 之前**建好：生成中禁止的一切操作（切换会话 /
        // 删除）都以 generating 为判据，次序反了会出现"已开始生成但页面上还没有它的位置"。
        renderChat("")
        val maxTok = sp.maxTokens
        generating = true; genBtn.isEnabled = false
        statusTv.text = "生成中…"
        // 挂上卡死看门狗（只在 HTP 开时判定，避免长 prompt 纯 CPU 预填充被误报）
        LlmEngine.lastProgressAt = System.nanoTime()
        hangReported = false
        ui.removeCallbacks(hangWatch)
        ui.postDelayed(hangWatch, 5000)
        val t0 = System.nanoTime()  // G3：只作差，必须单调钟（见 lastProgressAt 注释）

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
                            // 流式只改"正在生成的那一条"的文本，**不重建任何 View**：
                            // 旧写法每 token 重建整屏（长会话每 token inflate 几十个 View），
                            // 那正是换掉"拼字符串"的原因之一。
                            val ps = sb.toString()
                            ui.post { setStreamingText(ps) }
                        }
                    }
                    done = true
                    val sec = (System.nanoTime() - t0) / 1_000_000_000f
                    val tps = if (sec > 0.01f) n / sec else 0f
                    val text = sb.toString()
                    ui.post {
                        if (text.isNotBlank()) {
                            // 落盘 + 定型气泡。停止路径在 finally 的分支里做同一件事
                            //（文案不同），两处的落盘条件都必须只看"有没有产出内容"。
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
                        // 停止 = 「这一轮到此为止」，**但已产出的半截回答照样落盘**：
                        // 与主流客户端一致 —— 停止只表示"我不等了"，不改变"这条已经存在"。
                        // 于是它和正常答完的那条在存储层长得一样，「重新生成」对两者
                        // 是同一种语义（重写/换一个），也不再需要区分"半成品"的标志位。
                        // 落盘这件事**只在这一份收尾里做**（成功路径也走这里）：
                        // 两条路各写一份就会各有各的落盘条件。
                        if (sb.isNotBlank()) {
                            SessionStore.appendAssistant(this@EngineActivity, currentSessionId, sb.toString())
                            renderChat(null)
                            statusTv.text = "已停止（已保存，可长按这条回答 → 重新生成）"
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
            val idle = (System.nanoTime() - LlmEngine.lastProgressAt) / 1_000_000_000L
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
        private const val REQ_IMPORT_CHAT = 43
        // 知识库导入也**必须**用独立请求码：44 与上面两个都不共用。
        // 共用一个码时选文件那次回调会被另一个功能的解析器吃掉，而症状是
        // "选完文件什么都没发生" —— 两个功能互相抵消（REQ_IMPORT_CHAT 的注释同因）。
        private const val REQ_IMPORT_RAG = 44
        // 「导入 .txt / .md」同样自己一个码 —— 它与 REQ_IMPORT_RAG **不是同一个动作**
        //（那个直接写库、这个回填编辑器），共用码的结果就是上面说的"互相抵消"。
        private const val REQ_IMPORT_RAG_TEXT = 45

        /**
         * 日志页一次渲染多少行。ring 有 2000 行，一次全塞进 TextView 会让主线程排版明显变慢，
         * 而日志页默认贴底、用户关心的是**最新**那些行（探针摘要就在尾部）。
         * 被截掉的旧行不静默丢弃：页头有「仅显示最近 N 行」的说明，完整日志走导出。
         */
        private const val LOG_VIEW_LINES = 120

        /** 日志页标签多选的持久化键（ui_prefs）。存 chip 文案的**集合**，见 LogFilter.labels。 */
        private const val LOG_FILTER_KEY = "log_filter"

        /**
         * 日志页关键字的持久化键（ui_prefs）。
         *
         * 与档位分开存：两者是**两个维度**，合成一个串会让"清掉关键字"这种操作
         * 顺带动到档位（而用户只是想把搜索框清空）。
         */
        private const val LOG_KEYWORD_KEY = "log_keyword"

        /**
         * 关键字长度上限。它只防"粘贴一整篇进去"这种把每帧 × 2000 行的
         * `contains` 变成明显卡顿的输入，不是判据的一部分。
         */
        private const val KEYWORD_MAX = 128

        private const val REQ_STORE = 43
        private const val HANG_SEC = 30L

        /**
         * 导入确认框里**逐条列出**被丢人设时，最多列几条。
         *
         * 一份 200 会话的备份若逐条展开，确认框会长成一篇文章 —— 用户直接关掉，
         * 那条提示等于没有。所以列前 N 条 + 一行"另有 M 个未列出"：
         * 前 N 条够他认出"是不是我那段"，总数仍然如实报出来。
         */
        private const val MAX_SYSTEM_DROPPED_LIST = 8

        /**
         * 每一条被丢人设的**预览**字符数，超出部分用 `…` 标出。
         *
         * 刻意**不静默截**：截了却不标记，用户会把"只显示了 40 个字"
         * 当成"我的人设本来就只有 40 个字"—— 这正是本功能通篇在防的事。
         */
        private const val SYSTEM_DROPPED_PREVIEW = 40

        /**
         * 进度回执的识别前缀 —— **不在这里定义**，取 [RagProgress.PREFIX]。
         *
         * ⚠ 这里此前写着这个常量的一份**副本**，而发方（`LlmEngine`）写的是
         * `"[知识库] 正在编码："` —— 两个字符串从第一天起就不相等（这份多
         * "逐条"两个字），于是 `line.startsWith(PROGRESS_PREFIX)` **恒假**：
         *   · 进度**从来没有**走到 [updateProgressLine]；
         *   · 每一行 `正在编码：37/210` 都落进**日志渲染**那条闸（2000 行 ring
         *     的排版 + 标签筛选），一次建库几百轮全量重排 ——
         *     正是 `uiProgress` 那段注释里"上万行不进 ring"要防的事。
         * 判据恒假 = 整条保护一行都没生效，而它**看起来**一直在。
         *
         * 修法：只有一处定义（[RagProgress.PREFIX]），收发两边都从它取。
         * 守卫正面钉住"这个文件里不再出现第二份字面前缀"。
         */
        private val PROGRESS_PREFIX = RagProgress.PREFIX

        /**
         * 两次进度回执之间**至少**隔多少毫秒（不到就整条丢掉）。
         *
         * 250 ms ≈ 每秒 4 次：人眼看着是连续的，而每次只动一个 TextView 的文本 ——
         * 与"每来一条就重排一次 2000 行日志页"完全不是一个量级。
         * 丢掉的是**中间那些数字**，不是"进度"这件事本身（下一条会补上更大的数）；
         * 真要逐条对账，日志页里另有一行汇总（切了多少块、多少条失败）。
         */
        private const val PROGRESS_MS = 250L

        /**
         * 「新建库」里"一有内容就落盘"的**聚拢窗口**（毫秒）。见 [maybeSchedulePendingAutosave]。
         *
         * 600 ms 是一个"粘贴已经结束、而逐字敲还没停手"的位置：
         *   · 粘贴**只触发一次** `afterTextChanged`，所以这条路第一跳就是终态
         *     （600 ms 之后落盘，用户感知不到等待）；
         *   · 逐字敲的人在这段时间里通常还在敲，于是这一次写盘被下一跳取代 ——
         *     真正落盘的是他停手之后那份（[ragPendingSaveGen] 钉住"只有最后一份算数"）。
         * 再短会把"打字"变成"每两次键击写一次整份正文"；再长则偏离用户要的"立即保存"。
         */
        private const val PENDING_AUTOSAVE_MS = 600L
    }

    // ================= 多会话管理 =================

    /**
     * 当前会话的对话历史（system 置顶，仅用于喂模型）。
     *
     * `sys` 是**用户手打的那一段**（`sysEt`）。这一轮起"实际生效的 system"要过
     * [RoleCard.effectiveSystem] 一道：会话挂了角色卡就用**快照**，没挂才用手打的。
     * ⚠ 这道判据**只在这一处** —— `preparedMessages` 与"重新生成"都走它，
     *   两处各判一次就是"改了卡片那一侧没跟着改"的起点（同本仓库对
     *   `preparedMessages` 那条纪律：喂给模型的那一份只有一个出口）。
     */
    private fun chatHistory(sys: String): List<Pair<String, String>> {
        val snapshot = SessionStore.cardSnapshotOf(this, currentSessionId)
        val effective = RoleCard.effectiveSystem(snapshot, sys)
        val out = mutableListOf<Pair<String, String>>()
        if (effective.isNotEmpty()) out.add("system" to effective)
        for (m in SessionStore.load(this, currentSessionId)) out.add(m.role to m.content)
        return out
    }

    // ================= 角色卡（V1）=================

    /**
     * 重填聊天页那一行：下拉的条目 + 状态行。
     *
     * 它必须与 [refreshSessionTitle] 在同一条渲染路径上（切会话 / 新建 / 删除 /
     * 改卡片都调它）—— 分成两处刷，就会出现"标题已经换了、卡片还是上一条会话的"
     * 这种自相矛盾的画面。
     *
     * ⚠ 下拉的**下标 ↔ id** 映射交给 [cardPickerItems]，`onItemSelected` 拿到的
     *   position 一律经它翻译一次。直接 `RoleCardStore.load(...)[position]` 是
     *   错的：下拉里比库里多一个"（不用卡片）"档，差一就是"选了 A 挂上 B"。
     */
    private fun refreshCardRow() {
        if (!::cardSpinner.isInitialized) return
        val cards = RoleCardStore.load(this).cards
        val usedId = SessionStore.cardIdOf(this, currentSessionId)
        val snapshot = SessionStore.cardSnapshotOf(this, currentSessionId)

        // 档 0 恒为"不用卡片" —— 它是**一个可见的档位**，不是一个"空"。
        // 少了它，摘卡就只能靠"再选一张别的"，而用户想做的是"这张不要了"。
        val names = mutableListOf("（不用卡片）")
        val ids = mutableListOf("")
        for (c in cards) { names.add(c.name); ids.add(c.id) }
        cardPickerItems = ids

        // 选中的下标：按**id**回查，不按名字（两张卡可能同名，而 id 唯一）。
        // 卡片已经被删（id 不在列表里）时落到 0 —— 但**不清会话的快照**：
        // 那个会话照旧用着快照里那段文本，这正是 V1 的核心（见 RoleCard 文件头 ③）。
        val at = ids.indexOf(usedId).coerceAtLeast(0)
        cardPickerFilling = true
        cardSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names)
        cardSpinner.setSelection(at)
        cardPickerFilling = false

        cardHintTv.text = cardHintText(cards, usedId, snapshot)
        cardHintTv.visibility = if (cardHintTv.text.isEmpty()) View.GONE else View.VISIBLE
        // 没有卡片时下拉里只有"（不用卡片）"一档 —— 仍然显示，用户才知道
        // "这个功能在这儿、只是我还没建卡"。整行藏起来会让他以为功能不存在。
        cardManageBtn.text = if (cards.isEmpty()) "＋ 建卡片" else "管理卡片"
    }

    /**
     * 状态行要说的话。**纯函数**（入参决定输出），所以它能在宿主上被穷举 ——
     * 而这一段的每一句都对应一个"否则看不见"的状态，正是最该被穷举的部分。
     *
     * 五态，各自的处置都不同：
     * - 没用卡片 → 空串（常态，不出声；绝大多数会话都是这样）；
     * - 卡片仍与快照逐字相同 → 空串（正常，不必打扰）；
     * - 卡片**改了**内容（快照与库里那张不一样）→ 说"本会话用的是挂卡那一刻的内容"，
     *   点一下可以做"用新内容覆盖本会话"（这是用户唯一能做的动作）；
     * - 卡片**被删了**（id 不在库里）→ 说"卡片已删除，本会话仍用着它的内容"，
     *   这是 V1 最想让人看见的一条（只存 id 的版本在这里人设就没了）；
     * - 卡片**没挂上但快照在**（数据被手改过）→ 按"已删除"同一句处理：
     *   对用户来说这两件事的后果一样（列表里找不到那张卡了）。
     */
    private fun cardHintText(cards: List<RoleCard.Card>, usedId: String, snapshot: String): String {
        if (snapshot.isEmpty()) return ""
        val hit = cards.firstOrNull { it.id == usedId }
        return when (RoleCard.match(usedId, snapshot, cards)) {
            RoleCard.Match.OK -> ""
            RoleCard.Match.STALE -> if (hit == null)
                "⚠ 角色卡已删除，本会话仍用着它当时的内容（点这里重挂一张）"
            else
                "⚠ 卡片「${hit.name}」已修改，本会话用着挂卡时的内容（点这里用新内容覆盖）"
            RoleCard.Match.NONE -> ""
        }
    }

    /** 下拉里选了一张卡：把**快照**写进当前会话（不是只记一个 id）。 */
    private fun pickCard(position: Int) {
        val id = cardPickerItems.getOrNull(position) ?: return
        if (id == SessionStore.cardIdOf(this, currentSessionId)) return
        val card = if (id.isEmpty()) null else RoleCardStore.get(this, id)
        // id 非空却在库里找不到：下拉是刚填的，理论上不会发生；真发生就**不动**
        // （挂一张不存在的卡 = 静默换成空人设，比不响应更坏）。
        if (id.isNotEmpty() && card == null) return
        // ⚠ 切卡**必须作废 KV 账本**：system 在 pos 0，前缀是逐 token 比的，
        //   换掉 system 段命中长度就是 0 —— 不复用是**对**的，但那几 MB 白占着，
        //   而且用户不知道"点一下卡片 = 整段历史重算"。这一句与「清空对话」
        //   「删除会话」走的是同一个出口（LlmEngine.resetKvCache），
        //   它们要作废的原因也同源：**前缀赖以成立的那段历史被换掉了**。
        LlmEngine.resetKvCache()
        SessionStore.setCard(this, currentSessionId, id, card?.system ?: "")
        refreshCardRow()
        val msg = if (card == null) "已改为不使用卡片（system 用输入框里那一段）"
                  else "已切换到「${card.name}」；换了 system 段，本轮已丢弃 prompt 缓存"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 用卡片库里的**最新内容**覆盖本会话的快照（状态行点一下就走这里）。 */
    private fun refreshCardSnapshot() {
        val id = SessionStore.cardIdOf(this, currentSessionId)
        val card = RoleCardStore.get(this, id) ?: return
        LlmEngine.resetKvCache()
        SessionStore.setCard(this, currentSessionId, id, card.system)
        refreshCardRow()
        Toast.makeText(this, "已用「${card.name}」的最新内容覆盖本会话（prompt 缓存已丢弃）",
            Toast.LENGTH_SHORT).show()
    }

    /**
     * 卡片管理对话框：**载入 → 编辑 → 另存为**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么没有隐式的"保存卡"
     * ══════════════════════════════════════════════════════════════════════
     * 上一轮我提过一个诱人的形态：把输入框里那段 system 顺手存成一张卡。
     * **不做**，理由是它有一条静默路径：用户点「换成卡片」时，会以为这是在
     * "用现在这段文本"，而实际上如果它同时**改写了卡片库**，那些用了同名卡片的
     * 会话就在用户不知情的情况下被改了人设（改卡不动会话是 V1 的口径，
     * 但"顺手存一张新的"会让库里多出一张与旧的重名的卡，而**重名在下拉里
     * 长得一模一样**）。所以：编辑是编辑，存是存，两个按钮。
     *
     * 载入一张卡进编辑器**不改变**任何会话 —— 它只是把文字放进两个输入框。
     */
    private fun showCardDialog() {
        val loaded = RoleCardStore.load(this)
        if (loaded.fault == RoleCard.CardFault.UNREADABLE) {
            Toast.makeText(this, "卡片库读取失败（文件损坏）：卡片仍在磁盘上，未做任何改动",
                Toast.LENGTH_LONG).show()
        }
        val now = loaded.cards
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val listTv = TextView(this).apply {
            textSize = 12f; setTextColor(pal.icon)
            setPadding(px(12), px(4), px(12), px(4))
            text = when {
                now.isEmpty() -> "还没有卡片。下面填名字与内容，点「另存为新卡」。"
                else -> "共 ${now.size} 张（上限 ${RoleCard.MAX_CARDS}）"
            }
            if (loaded.dropped > 0) text = text.toString() +
                "\n⚠ 有 ${loaded.dropped} 张卡片读取失败（缺名字或内容超长），未列出"
            if (loaded.fault == RoleCard.CardFault.OVER_LIMIT) text = text.toString() +
                "\n⚠ 超过上限 ${RoleCard.MAX_CARDS} 张，只读进最近的那些"
        }
        box.addView(listTv)
        val lv = android.widget.ListView(this).apply { id = View.generateViewId() }
        box.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nameEt = EditText(this).apply { hint = "卡片名"; textSize = 13f; setSingleLine(true) }
        box.addView(nameEt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bodyEt = EditText(this).apply {
            hint = "卡片内容 = 会话的 system"; textSize = 13f
            setSingleLine(false); minLines = 3; maxLines = 6
        }
        box.addView(bodyEt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 正在编辑哪一张（空串 = 新建）。它决定「保存修改」是改哪一张，
        // 也决定"另存为"的语义 —— 两个动作共用一个编辑器，靠这一个变量分开。
        var editingId = ""
        val saveBtn = Button(this).apply { text = "保存修改"; isEnabled = false }
        val saveAsBtn = Button(this).apply { text = "另存为新卡" }
        val delBtn = Button(this).apply { text = "删除"; isEnabled = false }
        row.addView(saveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(saveAsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(delBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        lateinit var adapter: android.widget.BaseAdapter
        lateinit var reload: () -> Unit
        // ⚠ 先声明再赋值：`fun reload()` 里要引用 adapter，而局部函数不能前向引用
        //   （本仓库在 [showCleanupDialog] 里踩过一次：同一作用域同名两遍，
        //   `kotlinc` 直接报 conflicting declarations）。适配器同样自持一份
        //   "当前显示哪几张"——列表在编辑/删除之后要重建，而 **列表本体**
        //   不该被当成"库"来用（同 session 列表那条口径）。
        var rows: List<RoleCard.Card> = now

        adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = rows[position].id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@EngineActivity).apply {
                    textSize = 14f
                    setPadding(px(16), px(10), px(16), px(10))
                }
                val c = rows[position]
                // 卡片在列表里的样子必须与下拉里**同名**：两处措辞不同，
                // 用户就没法把"我在这选的那张"与"那边显示的那张"对上。
                tv.text = c.name
                return tv
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            val c = rows.getOrNull(pos) ?: return@setOnItemClickListener
            editingId = c.id
            nameEt.setText(c.name); bodyEt.setText(c.system)
            saveBtn.isEnabled = true; delBtn.isEnabled = true
            listTv.text = "正在编辑「${c.name}」；「保存修改」改这一张，「另存为新卡」建一张新的"
        }
        saveBtn.setOnClickListener {
            val name = RoleCard.normalizeName(nameEt.text.toString())
            val body = RoleCard.normalizeSystem(bodyEt.text.toString())
            val bad = RoleCard.validate(name, body)
            if (bad != null) { Toast.makeText(this, bad, Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (!RoleCardStore.update(this, editingId, name, body)) {
                Toast.makeText(this, "保存失败（旧卡片未动）", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            reload()
            // ⚠ 这一句是本功能最重要的一条口径，必须**每次保存都看见**：
            //   改卡片**不会**动任何会话（会话存的是挂卡那一刻的快照）。
            //   不说明的话，用户改完卡片回聊天页发现"没变化"，
            //   而他会以为是没保存上 —— 于是反复改、反复"没生效"。
            Toast.makeText(this,
                "已保存「$name」。已挂卡的会话**不会**跟着变（它们用挂卡时的内容），" +
                    "要覆盖请在聊天页点那行提示", Toast.LENGTH_LONG).show()
        }
        saveAsBtn.setOnClickListener {
            val name = RoleCard.normalizeName(nameEt.text.toString())
            val body = RoleCard.normalizeSystem(bodyEt.text.toString())
            val bad = RoleCard.validate(name, body)
            if (bad != null) { Toast.makeText(this, bad, Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (RoleCardStore.add(this, name, body) == null) {
                Toast.makeText(this,
                    "新建失败：卡片数已达上限 ${RoleCard.MAX_CARDS}（或写盘失败），请先删几张",
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            reload()
            Toast.makeText(this, "已新建卡片「$name」", Toast.LENGTH_SHORT).show()
        }
        delBtn.setOnClickListener {
            val c = rows.firstOrNull { it.id == editingId } ?: return@setOnClickListener
            // 删除**不可恢复**，所以问一次。而提示里必须说清"用了它的会话不会失效"——
            // 上一版没有这句，用户会以为删卡 = 那些会话的人设也没了，于是不敢删。
            AlertDialog.Builder(this)
                .setTitle("删除卡片「${c.name}」")
                .setMessage("已挂这张卡的会话**仍然能正常聊天**（它们用的是挂卡时那份内容），" +
                    "只是列表里不再显示这张卡。此操作不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    if (!RoleCardStore.delete(this, c.id)) {
                        Toast.makeText(this, "删除失败（卡片库未改动）", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    editingId = ""
                    nameEt.setText(""); bodyEt.setText("")
                    saveBtn.isEnabled = false; delBtn.isEnabled = false
                    reload()
                    Toast.makeText(this, "已删除卡片（会话不受影响）", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        fun doReload() {
            val fresh = RoleCardStore.load(this)
            rows = fresh.cards
            adapter.notifyDataSetChanged()
            listTv.text = when {
                rows.isEmpty() -> "还没有卡片。下面填名字与内容，点「另存为新卡」。"
                else -> "共 ${rows.size} 张（上限 ${RoleCard.MAX_CARDS}）"
            }
            // 卡片库变了 → 聊天页那一行（下拉 + 状态）跟着重画。
            // 不重画的话，用户在这里新建了一张卡、切回聊天页却发现下拉里没有它，
            // 只能靠切一次会话（触发 refreshSessionTitle）才刷出来。
            refreshCardRow()
        }
        reload = { doReload() }

        val dlg = AlertDialog.Builder(this)
            .setTitle("角色卡（卡片内容 = 会话的 system）")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    private fun refreshSessionTitle() {
        sessionTv.text = "☰ " + SessionStore.titleOf(this, currentSessionId) + "（点击切换/管理）"
        // 卡片行与标题**同一个渲染点**：分开刷会出现"标题已经换到新会话、
        // 下拉还挂着上一条会话的卡"这种自相矛盾的画面（同下面 migrateHint 那条）。
        refreshCardRow()
        // 提示与标题**同一个渲染点**：分开刷会出现"删完会话回到聊天页，
        // 会话已经建好了、提示还挂着"这种自相矛盾的画面。
        migrateHintTv?.let { tv ->
            val hint = migrateHint
            if (hint.isNullOrEmpty()) tv.visibility = View.GONE
            else { tv.text = hint; tv.visibility = View.VISIBLE }
        }
    }

    /** 初始化会话：迁移旧单会话数据，恢复 system 输入框。 */
    private fun initSessions() {
        // hotfix: 会话初始化兜底——任何异常都不让 App 闪退，降级回旧版单会话行为
        runCatching {
            // 迁移的结局**只在这一处认领**（currentId 内部那次调用只取值、不要结论）：
            // 换个地方读同一个状态，就会出现"删掉几个会话后切一下会话、
            // 提示自己消失或又冒出来"，而用户没法判断到底迁没迁。
            when (SessionStore.migrateIfNeeded(this)) {
                SessionStore.MigrateResult.BLOCKED_BY_LIMIT ->
                    migrateHint = "⚠ 旧聊天记录未能迁移：会话正文总量已达 ${SessionQuota.MAX_TOTAL_CHARS} 字符上限，" +
                        "请先删掉一些旧会话（旧记录仍在，删除后重启即会自动迁移）"
                SessionStore.MigrateResult.FAILED ->
                    migrateHint = "⚠ 旧聊天记录迁移失败（写入出错）：旧记录仍在，请查看日志后再试"
                // 没有旧文件是**绝大多数用户**的常态，不是错误 —— 出声会变成
                // 每次冷启动都报一次"迁移失败"，用户被吵到会去删数据。
                SessionStore.MigrateResult.OK, SessionStore.MigrateResult.NO_LEGACY -> Unit
            }
            currentSessionId = SessionStore.currentId(this) ?: ""
            if (currentSessionId.isEmpty()) currentSessionId = SessionStore.create(this) ?: ""
            sysEt.setText(SessionStore.systemOf(this, currentSessionId))
        }.onFailure {
            currentSessionId = ""
            android.util.Log.e("LocalLLM", "initSessions failed", it)
        }
        renderChat(null); refreshSessionTitle()
    }

    /**
     * 切换到指定会话（生成中禁止，由调用方检查）。
     *
     * @return 真的切过去了。三种"没切"分别对应三种调用方该做的事：
     *   · 会话已被删除 → 已就地重开列表，调用方**不该**再管这一屏；
     *   · 点的就是当前会话 → 什么都没变；
     *   · 生成中 → 什么都没变（提示已由 [showSessionDialog] 那一层给过）。
     *   上一版返回 `Unit`，调用方只能"假设它成功了" —— 而点一条已经被
     *   别处删掉的会话时，界面会先把对号挪到那条**并不存在**的会话上。
     */
    private fun switchSession(id: String): Boolean {
        // 防御——若该会话已被删除（文件不存在）则忽略点击并刷新列表
        if (!SessionStore.exists(this, id)) { showSessionDialog(); return false }
        if (id == currentSessionId) return false
        SessionStore.setSystem(this, currentSessionId, sysEt.text.toString().trim())
        currentSessionId = id
        sysEt.setText(SessionStore.systemOf(this, id))
        renderChat(null); refreshSessionTitle()
        return true
    }

    /** 会话列表对话框：新建 / 切换 / 长按重命名或删除。 */
    /**
     * 会话管理：搜索 + 切换 + 新建 / 导出全部 / 清理 / 关闭。
     *
     * ══════════════════════════════════════════════════════════════════
     * 搜索为什么必须真的过滤**列表控件**，而不是重开一次对话框
     * ══════════════════════════════════════════════════════════════════
     * 上一版用 `AlertDialog.setItems` —— 它的适配器是 `ArrayAdapter<String>`，
     * 从中**只能拿回字符串**，于是"点击第几行"要再拿 `list()` 的下标去对：
     * 一旦列表被筛过，这两套下标就错位了（症状是"点第 3 行打开了第 7 个会话"，
     * 最难解释的那类 bug）。所以这里自己造一个 `BaseAdapter` 装 `SessionStore.Info`
     * 本身，点击/长按拿到的**永远是 Info**，中间没有下标换算这一层。
     *
     * **筛选只是渲染层的取数**：`rows` 是"屏幕上显示哪几行"，任何删除路径
     * 一律重新 `SessionStore.list()` 取全库 —— 否则用户在搜索状态下点删除，
     * 删掉的会是"看起来只有三条"里被他勾中的那些，而他的其它会话也在
     * 同一批里被误伤（症状是"我的会话不见了"，与手动删同形）。
     */
    private fun showSessionDialog() {
        if (generating) { Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show(); return }
        // 打开前把当前 system 输入回写，避免切走时丢失编辑
        SessionStore.setSystem(this, currentSessionId, sysEt.text.toString().trim())
        val infos = SessionStore.list(this)
        if (infos.isEmpty()) { doCreateSession(); return }
        sessionDialog?.dismiss() // 关闭旧的管理对话框，避免叠加
        // 标题报"会话数 + 正文占用"两样：个数是用户数得清的（也是软提示线），
        // 字符占用才是硬上限 —— 只报个数会让用户以为"没到 200 就能一直建"。
        val used = SessionStore.usedChars(this)
        val countWarn = if (SessionQuota.shouldWarnCount(infos.size)) " · 会话较多，建议清理" else ""
        // ── 导入放哪儿：**导出全部 / 新建 同一排里，明确写着「导入」** ──────────────
        //
        // 上一版把「⬇ 导入记录文件」挂在**长按会话项**的菜单里。那个位置有它的道理
        // （导入是往库里加会话的动作，而长按天然带着"你正在看哪个会话"的上下文），
        // 但它有个说不通的后果：**导入是唯一一个不针对某个会话的动作，却要求先
        // 长按某一个会话才会出现**。用户的原话是"导入没有显示按钮，只能点击对话列表
        // 才弹出，不直观" —— 他在列表里找过，没有；把列表扫完了还没找到。
        // 一个"想导入自己的备份"的人，第一眼看到的是「导出全部」，找不到它的对偶，
        // 只能去猜"是不是要先点开某一条"。
        //
        // 现在导出的对偶**就在同一层**：打开会话管理就能同时看到「导出全部」与
        // 「导入」，不必先点开某一条。长按菜单里那一项**保留** —— 它已经被用的人
        // 记熟了，且没坏处（两处都指向 `pickArchiveFile()` 这一个实现）。
        //
        // 四个动作（新建 / 导出全部 / 导入 / 关闭）放不进三个按钮位，所以这样分：
        // 标题栏留给「导入」（它是**不针对某一条**的动作，与"整库"同层最自然），
        // 正文里「＋ 新建」「导出全部」并排。刻意**不用 `setNeutralButton`**：
        // 框架 `android.app.AlertDialog` 的 `BUTTON_NEUTRAL` 是后来插入的常量，
        // 运行时 `getButton(BUTTON_NEUTRAL)` 取不到按钮（这正是本轮修掉的闪退根因，
        // 见 [showCleanupDialog]）。按钮位只用一个 Positive，不碰 Neutral。
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val newBtn = Button(this).apply { text = "＋ 新建"; setOnClickListener { doCreateSession() } }
        val expBtn = Button(this).apply { text = "导出全部"; setOnClickListener { exportSessions(all = true) } }
        actions.addView(newBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(expBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(makeSessionListView(infos), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("会话管理（${infos.size} 个 · ${used}/${SessionQuota.MAX_TOTAL_CHARS} 字符$countWarn）")
            .setPositiveButton("导入") { _, _ -> pickArchiveFile() }
            .setNegativeButton("关闭", null)
            .setView(content)
            .create()
        dialog.show()
        sessionDialog = dialog
    }

    /**
     * 造会话管理的**内容视图**：一个搜索框 + 一个自持适配器的 ListView。
     *
     * 返回 `View` 而不是直接改 `dialog`：这样"对话框壳"与"内容"的分工是单向的 ——
     * 内容里不许 `dismiss()` / 重开对话框（那会让"输入一下搜索词"重建整层
     * 对话框，输入法焦点每敲一个字就丢一次）。
     *
     * @param infos 打开那一刻的全库快照，**只用来做初值**。搜索框每一次变化
     *   都会重新 `SessionStore.list()` 取数（而不是把这快照过滤两遍）：
     *   删除/导入都可能改库，而"搜到的结果与刚才打开时的那一份不一样"
     *   是用户看得出来的。
     */
    private fun makeSessionListView(infos: List<SessionStore.Info>): View {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = "搜索标题（输入即筛）"
            textSize = 13f; setSingleLine(true)
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // 搜索无匹配时的**空态行**：代替"每敲一个字弹一次 Toast"的写法。
        // 输入是一个**连续动作**（"会议记录"要按 4 次），逐次弹 Toast 会把屏幕
        // 糊满、并且挡住下面刚要出现的匹配行；而改成空态行之后，提示只在
        // "筛选结果为空"这一个状态下持续可见，用户删掉两个字它自己就消失。
        val emptyTv = TextView(this@EngineActivity).apply {
            textSize = 13f
            setTextColor(pal.textFaint)
            setPadding(px(16), px(16), px(16), px(16))
            visibility = View.GONE
        }
        root.addView(emptyTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val lv = android.widget.ListView(this).apply { id = android.view.View.generateViewId() }
        root.addView(lv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        /**
         * 把一行画成文字。**标记的判据是入参**，不直接读 [currentSessionId] ——
         * 见下面 adapter 里 `selected` 那段：这个函数是纯的（同一份入参永远画出
         * 同一行），所以"屏幕上这一行画的是哪一条"能在测试里被穷举，而不必
         * 依赖一个会在别处被改写的字段。
         */
        fun label(i: SessionStore.Info, selectedId: String): String = buildString {
            if (i.id == selectedId) append("✓ ")
            append(i.title)
            append("  · ").append(i.turns).append("条 · ").append(fmt.format(java.util.Date(i.updatedAt)))
        }
        // 适配器**自持**一份"当前显示哪几行"，并同时充当"全库 → 行"的字典：
        // 点击拿到的永远是 Info 本身（不是下标），所以"筛过之后点哪行"
        // 与"切哪个会话"之间没有下标换算这一层 —— 而那一层正是错位的来源。
        val adapter = object : android.widget.BaseAdapter() {
            var rows: List<SessionStore.Info> = infos

            /**
             * 「现在这一屏上，哪一条该打勾」= **选中的那一个会话 id**。
             *
             * ══════════════════════════════════════════════════════════════
             * 为什么它是适配器自己的状态，而不是直接读 `currentSessionId`
             * ══════════════════════════════════════════════════════════════
             * 用户报的那条：「切换会话时，会话前的对号不会跟随选择的会话进行变化」。
             * 根因就在这里 —— 此前 `getView` 里画的是 `i.id == currentSessionId`，
             * 而 `currentSessionId` 是 **Activity 的字段**：
             *
             *   · 适配器**不会被通知**它变了（`getView` 只在滚动/重建那一拍跑），
             *     而这一屏在切完之后**一次都不会重建** —— `switchSession` 只改
             *     `currentSessionId` 并回聊天页，这一层停在原处；
             *   · 于是对号**钉死在打开对话框那一刻的那一条**上。用户切了别的会话、
             *     回来点「☰」重新打开，它才跟着动 —— 而你问的正是"切换会话时"。
             *
             * 把判据收进适配器之后，"谁画这一屏"与"这一屏现在该长什么样"是
             * **同一个对象**：切换只要 `selected = picked.id` + `notifyDataSetChanged()`，
             * 与"搜索筛完要重画"是同一个动作、同一条路径。**能画出旧状态的路径
             * 根本不存在** —— 这比"记得在切完之后再 notify 一次"可靠得多。
             *
             * ⚠ `convertView` 复用：行视图是回收的，所以标记**不能存在行里**
             *   （同 [showCleanupDialog] 里 `selected` 不能存行里的那条教训：
             *   滚一下勾就跳到别的会话上）。它只存在这里，每次 `getView` 现算。
             */
            var selected: String = currentSessionId

            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = rows[position].id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@EngineActivity).apply {
                    textSize = 14f
                    setPadding(px(16), px(12), px(16), px(12))
                }
                tv.text = label(rows[position], selected)
                return tv
            }
        }
        lv.adapter = adapter
        // 输入即筛（与日志页同一手感，没有"搜索"按钮）。节流交给 ListView 自己的
        // 布局节奏：这里只重算"显示哪几行"，不碰文件。
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val fresh = SessionStore.list(this@EngineActivity)
                val kw = s?.toString() ?: ""
                adapter.rows = SessionCleanup.filter(fresh, kw)
                adapter.notifyDataSetChanged()
                // 空态只表达"这一屏现在没有匹配行"，**不是**一个事件 —— 所以它
                // 不用 Toast（一次性、会叠加、还会挡住列表），而是在列表位置
                // 常驻一行。关键字为空时不算"无匹配"（那时是"还没输"）。
                emptyTv.text = "没有标题匹配「${kw}」的会话"
                emptyTv.visibility = if (adapter.rows.isEmpty() && kw.isNotBlank()) View.VISIBLE else View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        // ══════════════════════════════════════════════════════════════════
        // 点一条 = 切过去 + **关掉这一层**（不是留在原地把对号挪一下）
        // ══════════════════════════════════════════════════════════════════
        // 上一版切完**留在这个对话框里**，于是有两处说不通：
        //
        //   ① 用户已经切过去了，而屏幕上仍是那一张「会话管理（N 个 · X 字符）」
        //      列表 —— 他要做的事做完了，唯一的出路是自己找到「关闭」；
        //   ② 那一层的**标题**（会话数 · 正文占用）取自打开那一刻的库快照。
        //      切换本身不改库，所以它此刻是对的；但同一个对话框里还有
        //      「＋ 新建」「🗑 删除」「🧹 批量清理」，用户点完任一个再回到
        //      这张列表时，标题里的数字已经与库里对不上了 —— 而它看起来
        //      和打开时一模一样。留着这一层，就等于留着"一个显示着过期数字
        //      的标题"，没有任何机制在库变了之后重算它。
        //
        // 关掉之后就没有这个问题：要看列表再点一次「☰」，那一次是**新打开**，
        // 标题就是当场算的。与「新建」（`doCreateSession` 里 `dismiss`）同一条口径 ——
        // 会话被换掉、界面就该回到聊天页，而不是把用户留在一个已经不指向
        // 他刚选的那条会话的列表里。
        lv.setOnItemClickListener { _, _, pos, _ ->
            val picked = adapter.rows.getOrNull(pos) ?: return@setOnItemClickListener
            // 先把这一屏调成"已经切过去了"：即使切换被拦下（生成中 / 已被别处删掉），
            // 用户看到的也对得上真实状态 —— 拦下了就什么都没变，不许先把对号挪走。
            if (!switchSession(picked.id)) return@setOnItemClickListener
            adapter.selected = picked.id
            adapter.notifyDataSetChanged()
            showSessionDialogDismiss()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val picked = adapter.rows.getOrNull(pos) ?: return@setOnItemLongClickListener false
            showSessionActions(picked); true
        }
        return root
    }

    private fun doCreateSession() {
        if (generating) { Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show(); return }
        val id = SessionStore.create(this)
        if (id == null) {
            // 拒绝的原因是"正文总量顶到硬上限"，不是"个数到 50"—— 报出真实数字，
            // 用户才知道该删几个（"已用 X / 上限 Y"比"已达上限"可行动得多）。
            val used = SessionStore.usedChars(this)
            Toast.makeText(this, "会话空间已满（已用 $used/${SessionQuota.MAX_TOTAL_CHARS} 字符），请先删除一些旧会话",
                Toast.LENGTH_LONG).show()
            return
        }
        currentSessionId = id
        sysEt.setText("")
        renderChat(null); refreshSessionTitle()
        sessionDialog?.dismiss(); sessionDialog = null // 新建后关闭管理对话框
        Toast.makeText(this, "已新建会话", Toast.LENGTH_SHORT).show()
    }

    /**
     * 长按会话项的动作菜单：重命名 / 导出这一条 / 导入 / 批量清理 / 删除。
     *
     * 「导入」现在**首访入口在会话管理对话框上**（`导出全部` 旁边那个「导入」，
     * 见 [showSessionDialog]）。这里这一项保留：长按菜单是"针对这一条会话"的，
     * 而它同时是"我人在列表里、顺手想导一份"的第二条路 —— 两个入口都指向
     * [pickArchiveFile] 这一个实现，没有第二份导入逻辑。
     * 菜单项的顺序是**破坏性递增**：改名的（可逆）在最前，删除（不可恢复）在最后，
     * 中间夹着三个都与"文件/整库"有关的动作 —— 误点一下也不会毁掉任何东西。
     */
    private fun showSessionActions(info: SessionStore.Info) {
        AlertDialog.Builder(this)
            .setTitle(info.title)
            .setItems(arrayOf("✏️ 重命名", "⬆ 导出这一条", "⬇ 导入记录文件", "🧹 批量清理…", "🗑 删除")) { _, which ->
                when (which) {
                    0 -> promptRename(info)
                    1 -> exportSessions(all = false, only = info.id)
                    2 -> pickArchiveFile()
                    3 -> showCleanupDialog()
                    else -> confirmDeleteSession(info)
                }
            }
            .show()
    }

    /**
     * 「按时间批量清理」：先按"多少天没用过"列出候选，**全部默认不勾**，
     * 勾完再删。
     *
     * ══════════════════════════════════════════════════════════════════
     * 为什么默认一个都不勾
     * ══════════════════════════════════════════════════════════════════
     * 批量删是不可恢复的，而"多少天算旧"这条线**从来不会与用户心里的
     * 那个日子刚好对齐**：选了 30 天，恰好有一条 31 天前的重要会话，
     * 预勾选就等于把它一起带走，而用户按下"删除"时只会以为自己在清垃圾。
     * 所以默认全不勾、必须**显式勾选**才允许删除 —— 这一步的代价是多点
     * 几下，换来的是"删掉的都是用户点名要删的"。
     *
     * 另外两处口径：
     * - **候选列表不含当前会话**：删掉它虽然合法（`SessionStore.delete`
     *   会切到最近一个），但"我明明在跟它说话，它却在待清理清单里"
     *   是最容易误勾的一条。要删它，列表页长按就有专用的删除项。
     * - **删除之后按全库重新取数**（不是把勾过的从显示里抹掉）：顺带把
     *   "勾了又取消、或期间库被别的路径改过"的对账一次做完 ——
     *   见 [SessionCleanup.resolve]。
     */
    private fun showCleanupDialog() {
        if (generating) { Toast.makeText(this, "生成中，请先停止", Toast.LENGTH_SHORT).show(); return }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        // 天数档位是固定三档（见 [SessionCleanup.DAY_CHOICES]）：批量删不可恢复，
        // 而"填数字"在手机上要起键盘、要校验，还容易被当成搜索框。
        val now = System.currentTimeMillis()
        val daysGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        SessionCleanup.DAY_CHOICES.forEachIndexed { idx, d ->
            val rb = android.widget.RadioButton(this).apply {
                text = "$d 天"; id = idx; textSize = 14f
            }
            daysGroup.addView(rb)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = "未使用超过"; textSize = 14f; setPadding(px(8), px(12), px(4), px(12))
        })
        row.addView(daysGroup)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(4), px(4), px(4), px(4))
        }
        box.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val hint = TextView(this).apply {
            textSize = 12f; setTextColor(pal.warn)
            setPadding(px(12), px(4), px(12), px(8))
        }
        box.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val lv = android.widget.ListView(this).apply { id = android.view.View.generateViewId() }
        box.addView(lv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val check = android.widget.CheckBox(this).apply { textSize = 14f; setPadding(px(12), px(8), px(12), px(8)) }
        box.addView(check, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 已勾选的 id 集合：ListView 会回收行视图，勾选态**不能存在行里** ——
        // 存行里的话滚一下选中态就跟着跳到别的会话上（症状是"删的是别的会话"）。
        val selected = linkedSetOf<String>()
        var candidates: List<SessionStore.Info> = emptyList()
        // ⚠ 局部函数的**声明顺序**在 Kotlin 里是有约束的：局部函数不能前向引用，
        // 而对象表达式（适配器）的方法体里引用 `syncDeleteButton` 也算在声明点解析。
        // 所以顺序钉死成：`dialogRef` → `syncDeleteButton` → 适配器 → `rebuild`。
        //
        // `delBtn` 只有**一处**声明，位置在它唯一的赋值点（`rebuild()` 之后）——
        // 曾一度在这里先写一个 `lateinit var delBtn`、又在建按钮时写
        // `val delBtn = ...`，同一个作用域里同名两遍，`kotlinc` 直接报
        // `conflicting declarations`（EngineActivity.kt 编不过，整个 APK 出不来）。
        // 而 `lateinit var` 本身是多余的：局部函数**可见**在它之后声明的局部变量，
        // `rebuild()` 与适配器都排在赋值之后，不存在"提前引用"的问题。所以同步
        // 只碰 `delBtn` 这一个视图，不去问对话框"哪个常量才是删除键"。（这正是
        // 上一版闪退的根：框架 `AlertDialog.BUTTON_NEUTRAL` 是后来插入的常量，
        // 运行时拿不到按钮。）
        lateinit var dialogRef: AlertDialog
        lateinit var delBtn: Button
        fun syncDeleteButton() {
            // 「删除」按钮的可用性只由"有没有真的勾了什么"决定：默认全不勾时
            // 它是**灰的**，用户必须先做一次显式动作 —— 这条是"不误删"的兜底。
            delBtn.isEnabled = SessionCleanup.canDelete(selected, candidates.map { it.id }.toSet())
        }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = candidates.size
            override fun getItem(position: Int): Any = candidates[position]
            override fun getItemId(position: Int): Long = candidates[position].id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val i = candidates[position]
                val v = (convertView as? android.widget.CheckedTextView) ?: android.widget.CheckedTextView(this@EngineActivity).apply {
                    textSize = 14f
                    setPadding(px(12), px(12), px(12), px(12))
                }
                v.text = "${i.title}  · ${i.turns}条 · 上次 ${fmt.format(java.util.Date(i.updatedAt))}"
                v.isChecked = selected.contains(i.id)
                v.setOnClickListener {
                    if (selected.contains(i.id)) selected.remove(i.id) else selected.add(i.id)
                    v.isChecked = selected.contains(i.id)
                    syncDeleteButton()
                }
                return v
            }
        }
        lv.adapter = adapter
        // 档位切换与"全选"两个口子都走**同一个重建函数**：能勾的地方只有一处，
        // 于是"全选之后手工取消一条"与"逐条勾"得到的是同一份状态。
        fun rebuild() {
            val fresh = SessionStore.list(this)
            candidates = SessionCleanup.olderThan(fresh, now, SessionCleanup.DAY_CHOICES[daysGroup.checkedRadioButtonId])
                .filter { it.id != currentSessionId }
            // 勾选状态跨"换档位"保留：用户勾了一条 30 天的、再切到 90 天看还剩谁，
            // 切回来时那条还该是勾着的 —— 否则"我明明勾过了"会变成一次误删或漏删。
            adapter.notifyDataSetChanged()
            hint.text = if (candidates.isEmpty())
                "没有未使用超过 ${SessionCleanup.DAY_CHOICES[daysGroup.checkedRadioButtonId]} 天的会话"
            else "共 ${candidates.size} 个候选（默认不勾选，请逐条确认后再删）"
            // 复选框必须**跟着当前档位重算**：`selected` 是跨档保留的（用户勾了一条
            // 30 天的、切到 90 天看还剩谁，切回来时那条还该是勾着的），而"全选本档"
            // 是真的会被切档改掉状态的 —— 若不在这里回写，画面会自相矛盾：
            // 复选框还勾着，而新档位的候选一个都没进 `selected`（切档时若恰好在
            // 上一档勾过全选，用户会以为新档位也已全选，实际只删得动上一条档位）。
            // `check` 用的是 `setOnClickListener`（不是 `setOnCheckedChangeListener`），
            // 所以这里回写不会反过来触发一次"全选/取消"。
            check.isChecked = candidates.isNotEmpty() && candidates.all { selected.contains(it.id) }
            syncDeleteButton()
        }
        daysGroup.check(SessionCleanup.DAY_CHOICES.indexOf(SessionCleanup.DEFAULT_DAY_CHOICE).coerceAtLeast(0))
        daysGroup.setOnCheckedChangeListener { _, _ -> rebuild() }
        check.setOnClickListener {
            // 全选/取消全选只作用于**当前档位列出来的那些**（不是整个库）：
            // 用户的意图是"这一档我一个都不想留"，而不是"清空整库"。
            if (check.isChecked) candidates.forEach { selected.add(it.id) } else candidates.forEach { selected.remove(it.id) }
            adapter.notifyDataSetChanged(); syncDeleteButton()
        }
        // 「删除」做成 box 里的**自有按钮**，不用 `setPositiveButton`。
        //
        // 为什么不用：这个对话框的壳是**建在 `box` 之后**的，靠 `dialogRef` 在
        // 点击回调里回手同步按钮可用性。而 `AlertDialog.BUTTON_NEUTRAL` 是
        // **后来插入的**常量（本仓库零第三方依赖、用的是框架 `android.app.AlertDialog`）——
        // 运行时 `getButton(BUTTON_NEUTRAL)` 给回 **null**：老机型上 `btn?.isEnabled`
        // 静默失效（删除按钮永远是亮的，`canDelete` 这道"不误删"的闸门被漏过），
        // 新机型上 `btn.isEnabled` 直接 **NPE 闪退**（点「批量清理」就崩）。
        // 按钮放在视图里，可用性只由**同一个变量**决定，中间没有 `dialogRef`
        // 这一层、也没有"哪个常量对应哪个按钮"的猜测。
        delBtn = Button(this).apply {
            text = "删除"
            isEnabled = false // 默认一个都没勾，按钮必须是灰的（同 canDelete 的口径）
            setOnClickListener {
                // 删除永远是**库层取数**：重取全库再取交集，不拿屏幕上的候选清单动手。
                val infos = SessionStore.list(this@EngineActivity)
                val targets = SessionCleanup.resolve(infos, selected)
                if (targets.isEmpty()) { Toast.makeText(this@EngineActivity, "没有可删除的会话", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dialogRef.dismiss()
                deleteSessions(targets)
            }
        }
        box.addView(delBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            // 与上方"全选本档"之间留一口气：删除是不可恢复的破坏性动作，
            // 紧贴着复选框时容易在"想取消某条勾选"时误触。留白也是分组信号 ——
            // 它和上方那一片"选择候选"的控件不是同一组东西。
            topMargin = px(10)
            bottomMargin = px(4)
        })
        check.text = "全选本档"
        // 先 `rebuild()` 把候选与按钮可用性算出来，再建对话框 —— 两者都在同一帧完成，
        // 所以"打开时按钮是灰的"不依赖 show() 之后的任何回手同步。
        //
        // 这也正是 `delBtn` 能是 `lateinit` 而不会"提前引用"的原因：`rebuild()` 与
        // 适配器都排在**赋值之后**，没有一条路径能在这行之前读到它。
        rebuild()
        val dialog = AlertDialog.Builder(this)
            .setTitle("批量清理会话")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()
        dialogRef = dialog
        dialog.show()
    }

    /**
     * 批量删掉一批会话，并把**受影响的**界面收场做干净。
     *
     * 三条收场，每条都对应一个"删完之后看着像坏了"的现象：
     *
     * ① **当前会话被删掉了**：`SessionStore.delete` 会把当前 id 切到最近一个，
     *    但界面还挂着旧标题与旧历史 —— 用户看到的是"删完之后聊天记录还在"。
     *    所以这里在删完之后重新认领一次 `currentId`，为空则新建一个。
     * ② **KV 账本要作废**：被删的会话里可能正有一条是"当前会话的前身"，
     *    而 KV 前缀是按 tokens 复用的 —— 历史被换掉之后仍拿着旧前缀，
     *    模型会**从别人的上下文里接着往下写**。这一条**没有任何可观测的症状**
     *    （复用的判据是逐 token 比前缀，分叉了自然不复用），正是这个仓库
     *    最爱钉的那类：看不见的收益，也就看不见的丢失。
     * ③ **列表页要按全库重画**：不能把"刚删掉的几条"从显示里抹掉 ——
     *    那会让"列表里剩下的条数"与"库里真实条数"在下一次打开时对不上。
     */
    private fun deleteSessions(targets: List<SessionStore.Info>) {
        val removedCurrent = targets.any { it.id == currentSessionId }
        targets.forEach { SessionStore.delete(this, it.id) }
        // 删会话 = 当前上下文可能被换掉，KV 前缀不再成立 —— 全清是**这里**该做的
        // （与"删单条消息"相反：那条删的是尾巴，plan_reuse 自己会砍，不得动 KV）。
        LlmEngine.resetKvCache()
        if (removedCurrent) {
            currentSessionId = SessionStore.currentId(this) ?: ""
            if (currentSessionId.isEmpty()) { doCreateSession() } else {
                sysEt.setText(SessionStore.systemOf(this, currentSessionId))
                renderChat(null); refreshSessionTitle()
            }
        }
        showSessionDialogDismiss()
        showSessionDialog()   // 按全库重画（不是把刚删的从显示里抹掉）
        Toast.makeText(this, "已删除 ${targets.size} 个会话", Toast.LENGTH_SHORT).show()
    }

    /**
     * 导出会话到公共「下载」目录。
     *
     * @param all true = 导出库里全部会话，false = 只导出 [only] 这一条。
     *
     * ⚠ **导出不受任何界面状态影响**：不读日志页的筛选、不读会话列表的搜索 ——
     * 导出的语义是"把库里的记录原样带走"，掺进"我现在看到的几行"就是另一件事了
     * （与日志导出同一条纪律，见 [recentLogsUnfiltered]）。
     *
     * 生成中**允许**导出：导出是纯读，不会与生成抢 native 锁。
     * 这与"新建/切换/删除要拦生成"不同 —— 那几个会改 KV 账本赖以成立的历史。
     */
    private fun exportSessions(all: Boolean, only: String? = null) {
        showSessionDialogDismiss()
        val sessions = if (all) SessionStore.exportAll(this)
        else listOfNotNull(only?.let { SessionStore.exportOne(this, it) })
        if (sessions.isEmpty()) {
            Toast.makeText(this, "没有可导出的会话", Toast.LENGTH_SHORT).show()
            return
        }
        val json = ChatArchive.encode(sessions, System.currentTimeMillis())
        val name = exportName(if (all) "chat-all" else "chat", if (all) null else only, ext = "json")
        try {
            // MIME 必须显式给 application/json：text/plain 会让文件管理器把它当纯文本
            // 打开（点开是记事本、分享出去变 .txt），内容没错但用起来别扭。
            val where = LogExport.saveText(this, name, json, mime = "application/json")
            Toast.makeText(this, "已导出 ${sessions.size} 个会话（${sessions.sumOf { it.messages.size }} 条消息）\n$where",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 打开系统文件选择器挑一份聊天记录文件。 */
    private fun pickArchiveFile() {
        showSessionDialogDismiss()
        // type 用 */* 而不是 application/json：SAF 上不少文件管理器把 .json 报成
        // text/plain 或 application/octet-stream，写死 application/json 会让
        // "明明在，却选不中" —— 而那种现象会被读成"这个 App 不支持导入"。
        // 真实性由 decode() 的 format 判据兜底：选错文件会被明确拒绝。
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_IMPORT_CHAT)
    }

    /**
     * 读回选中的文件并导入。
     *
     * 整个"读文件 + 解析"都在**后台线程**：一份几 MB 的记录文件在主线程读+解析
     * 会掉帧甚至 ANR。解析失败、格式不对、读不出来 —— 三种都是**明确提示**，
     * 没有一条是静默返回。
     */
    private fun importArchiveFile(uri: Uri) {
        statusTv.text = "正在读取记录文件…"
        // 走本页既有的 ioExecutor（不是裸 Thread）：它是单线程、随 Activity 关闭
        // 一起 shutdownNow 的那一个，裸 Thread 会在页面已销毁后仍然持有 Context。
        ioExecutor.execute {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("无法打开文件")
            }.getOrElse { e ->
                runOnUiThread {
                    statusTv.text = ""
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            val parsed = ChatArchive.decode(text)
            runOnUiThread {
                statusTv.text = ""
                if (!parsed.ok) {
                    // 失败文案必须说清是哪种失败：选错文件 / 版本不支持 / 内容为空
                    // 的处置完全不同，一句"导入失败"会让用户反复重试同一个文件。
                    Toast.makeText(this, "导入失败：${parsed.error}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                confirmImport(parsed)
            }
        }
    }

    /** 落盘之前问一次 —— 导入会改变会话列表，且**没有撤销**。 */
    private fun confirmImport(parsed: ChatArchive.Parsed) {
        val total = parsed.sessions.sumOf { it.messages.size }
        // 预算按**字符**算，不按个数：个数到 200 只是提示线，真正的闸门是字符总量。
        // 这里先给用户看到"这批能进去几个"，而不是写完一半才发现满了。
        val incoming = parsed.sessions.map { SessionStore.charsOf(it) }
        val accept = SessionQuota.acceptCount(SessionStore.usedChars(this), incoming)
        val msg = buildString {
            append("文件里有 ").append(parsed.sessions.size).append(" 个会话（").append(total).append(" 条消息）。\n")
            append("导入 = 追加为新会话，不会覆盖或修改现有会话。\n")
            if (accept < parsed.sessions.size) {
                append("⚠ 空间不足：按 "
                    + SessionQuota.MAX_TOTAL_CHARS + " 字符上限，这批只能导入前 ").append(accept)
                    .append(" 个，其余 ").append(parsed.sessions.size - accept).append(" 个不会导入。\n")
            }
            if (parsed.skipped > 0) {
                append("⚠ 文件里有 ").append(parsed.skipped).append(" 条无法识别的记录（非 user/assistant 角色或超长正文），将被跳过。\n")
            }
            // 「人设不跟着走」这条**必须出在这里**，而且是**分开的一段**：
            // 它与上面那条"脏数据跳过"的处置完全相反 —— 脏数据用户管不着，
            // 而人设是他自己写的、本来期待导入后能用，回来一看没了。
            // 只说"部分内容被跳过"会把这两件事糊成一件，用户既不知道该不该在意，
            // 也不知道该重做哪一段。所以这里说清三样：**有几段**、**是哪些会话**、
            // **内容是什么（截断展示）**，以及**本机为什么收不下**。
            if (parsed.systemDropped > 0) {
                append("⚠ 文件里有 ").append(parsed.systemDropped)
                    .append(" 个会话自带 system 人设，本机不会一并导入（导入只收对话正文）。\n")
                append(systemDroppedDetail(parsed)).append("\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("导入聊天记录")
            .setMessage(msg)
            .setPositiveButton("导入") { _, _ ->
                val n = SessionStore.importAll(this, parsed.sessions)
                // 导入后重画：当前会话可能已经换成刚导入的那一条。
                currentSessionId = SessionStore.currentId(this) ?: currentSessionId
                sysEt.setText(SessionStore.systemOf(this, currentSessionId))
                renderChat(null); refreshSessionTitle()
                val skipped = if (parsed.skipped > 0) "，跳过 ${parsed.skipped} 条" else ""
                // 确认框点过就没了，而这一句是**结果**那一侧唯一的留痕 ——
                // 导入丢人设必须在"事后的那一句话"里也能看见，否则用户
                // 关掉对话框之后再去回想"刚才是说了什么吗"就无从对证。
                val sysDrop = if (parsed.systemDropped > 0)
                    "\n⚠ ${parsed.systemDropped} 个会话的 system 人设未导入（本机只收对话正文）" else ""
                Toast.makeText(this, "已导入 $n 个会话$skipped$sysDrop", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 把"被丢掉的 system 人设"渲染成**逐条**的说明行。
     *
     * 为什么要逐条而不是只报个数：用户要做的判断是"这一段重要吗"。
     * 只给个数时他只有两个选择 —— 无视（万一是要紧的那段呢），
     * 或者整份重来（白干）。给出**是哪个会话、开头是什么**，他至少认得出。
     *
     * 每一条截到 [SYSTEM_DROPPED_PREVIEW] 个字符：确认框是一屏的，一份
     * 200 个会话的备份若逐条全文展开，对话框会变成一篇文档，用户直接关了。
     * 超出部分用 `…` 标出（**不能静默截** —— 用户会把"没显示完"当成
     * "本来就这么多"，那正是这份文件通篇在防的事）。
     */
    private fun systemDroppedDetail(parsed: ChatArchive.Parsed): String {
        val hit = parsed.sessions.filter { !it.systemDropped.isNullOrEmpty() }
        if (hit.isEmpty()) return ""
        val oneLine = { s: String -> s.replace('\n', ' ').trim() }
        val shown = hit.take(MAX_SYSTEM_DROPPED_LIST).joinToString("\n") { s ->
            val body = oneLine(s.systemDropped.orEmpty())
            val head = if (body.length > SYSTEM_DROPPED_PREVIEW)
                body.take(SYSTEM_DROPPED_PREVIEW) + "…" else body
            "　「${oneLine(s.title)}」：$head"
        }
        val more = if (hit.size > MAX_SYSTEM_DROPPED_LIST)
            "\n　…另有 ${hit.size - MAX_SYSTEM_DROPPED_LIST} 个会话的人设未列出" else ""
        return shown + more
    }

    /** 关掉会话管理对话框（导出/导入会离开这一层，留着它会和结果提示叠在一起）。 */
    private fun showSessionDialogDismiss() {
        sessionDialog?.dismiss(); sessionDialog = null
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
                // 这里**只重画一次**、且放在收场之后。曾经在 `if` 之前先调用了一次
                // `showSessionDialog()`，末尾又调一次：删非当前会话时是同一屏被
                // "dismiss 再重建"两遍（对话框闪一下），删当前会话时更乱 ——
                // 第一次建的列表会在 `doCreateSession()` 里被 dismiss 掉，
                // 用户看到对话框自己灭了一下又冒出来。重画的唯一时机是"库改完了"。
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
                showSessionDialog() // 唯一的重画点：按全库刷新列表（含删除非当前会话的情况）
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 点「还原默认设置」时**先问一次** —— 见设置页那个按钮处的注释。
     *
     * ⚠ 这里是**先问后做**：确认框弹出来的时候一个参数都还没动。改成"先还原再弹
     *   「确定要还原吗」"（或者干脆只弹个"已还原"的提示）都不算数 —— 那时用户看到
     *   的对话框不再是"要不要"，而是"已经发生了"，误触的代价一分没省。
     *   守卫 tools/run_reset_default_guard.sh 钉的正是这条次序。
     *
     * 确认按钮**不写"确定"**：按钮名要说清它干什么。用户在弹窗上读到的唯一一句话
     * 就是这句 `setMessage`，而"确定"在"删除 / 导入 / 切库"这些框里长得一模一样，
     * 点错一次就是全页参数没了。
     */
    private fun confirmResetDefaults() {
        AlertDialog.Builder(this)
            .setTitle("还原默认设置")
            .setMessage("把设置页里所有分组一次性恢复成默认值，" +
                "并重置端口（8080）与局域网开关（关）。\n\n" +
                "你自己调过的参数**不会保留**，也不会自动备份 —— 还原只能用默认值重来，" +
                "没有撤销。\n\n" +
                "（端口与局域网开关**立即生效**；ctx / gpu / 线程数这些加载类参数" +
                "要重新加载模型才生效，已加载的模型仍按旧参数跑。" +
                "外观那组里的暗色模式同样要重开应用才看得到变化。）")
            .setPositiveButton("还原") { _, _ -> restoreDefaultParams() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 真正的还原动作（唯一写点）。
     *
     * 抽成具名方法而不是内联在点击回调里，是为了让"确认之后才动参数"**可判定**：
     * 这个函数体只允许出现在 [confirmResetDefaults] 的按钮回调里。它一旦被别处
     * （或 `onCreate` 之类）调用，就等于"参数会在用户没同意时被覆盖"，
     * 而那种坏法在界面上与正常还原**完全同形**。
     */
    private fun restoreDefaultParams() {
        ctxEt.setText("4096"); gpuEt.setText("0"); thrEt.setText(LlmEngine.defaultThreads().toString())
        tempEt.setText("0.8"); maxEt.setText("512"); topKEt.setText("0")
        repEt.setText("1"); repeatLastNEt.setText("64"); topPEt.setText("0.95")
        minPEt.setText("0"); cacheKEt.setText("0"); cacheVEt.setText("0")
        parallelNEt.setText("1"); batchSizeEt.setText("2048"); ubatchSizeEt.setText("512")
        presEt.setText("0"); freqEt.setText("0"); mmapEt.setText("1")
        repackMode = null
        if (repackDefaultId != View.NO_ID) repackGroup?.check(repackDefaultId)
        persistParams()
        // ── 知识库那两格也要**真的**回到默认（本轮补的口子）──────────────────
        // 它们此前**不在**还原范围内：`EmbedBatchPref` / `EmbedCtxPref` 的键
        // 原样留着，而 [EmbedBatchPref.isSet] 仍为 true —— 用户以为自己
        // "还原成默认了"，实际跑的还是上次设的值。
        //
        // ⚠ 为什么这一条**比看起来严重**：批大小进索引指纹（`g`）。
        //   用户设过 8（与默认相同）-> 还原 -> 键还在 -> `/health` 仍报"设过"；
        //   而更坏的是他设过 1（逐条）之后还原 -> 设置页显示回默认 8，
        //   但**跑的还是 1** -> 索引按 `g=1` 建、检索按 `g=1` 比，看着正常，
        //   直到他把批大小"从默认 8"改成别的：那一改在指纹上等于 1->N，
        //   与设置页显示的"8 -> N"**不是同一件事**（用户无从归因）。
        //
        // 所以这里是"删键 + 把输入框写回默认值"两件一起：删键让 [isSet] 归位，
        // 写回输入框让界面与真相一致（`get()` 的语义是"没设过 -> 0 -> 用默认"，
        // 而输入框里显示 0 会被读成"批大小是 0"）。次序：先删键、再写框 ——
        // 写框若哪天触发了失焦保存，`clear` 已经先落地（不会把默认值又写回键里）。
        // ── 暗色模式也要**真的**回到默认（默认 = 关闭）────────────────────────
        // 它**不进** `engine_params`（那一份是模型加载参数），所以只能落在这个
        // 还原体里，且刻意放在**最后一行之前**、`persistParams()` **之后**：
        // 插在"回勾 repack 档 → 落盘"这一对之间会把它们拆开，而那一对的
        // **相邻性**正是 `run_reset_default_guard_tests.sh` 的桩⑤钉住的
        // （本仓库对"两行换个位置"这一族一向有判据）。
        //
        // 两条缺一不可：
        //   · **删键**（不是"把 OFF 写进键里"）—— `ThemePref.isSet` 的判据是
        //     "键在不在"，键还在就等于"用户设过"，用户以为自己还原了而读数
        //     说他设过（与 `EmbedCtxPref` / `EmbedBatchPref` 那条理由完全同形）；
        //   · 单选组回勾默认档 —— 否则界面显示的是上一档、跑的是关闭，
        //     "我明明还原了，圆点还在『打开』上"正是本仓库最爱钉的那类现象。
        ThemePref.clear(this)
        if (darkModeDefaultId != View.NO_ID) darkModeGroup?.check(darkModeDefaultId)
        EmbedBatchPref.clear(this)
        EmbedCtxPref.clear(this)
        ragBatchEt.setText(LlmEngine.kEmbedBatchDefault.toString())
        ragEmbedCtxEt.setText(LlmEngine.kEmbedCtxDefault.toString())
        portEt.setText("8080"); lanCb.isChecked = false
        ModelStore.setServerPort(this, 8080)
        ModelStore.setLanAccess(this, false)
        Toast.makeText(this, "已还原默认设置（加载类参数需重新加载模型生效）", Toast.LENGTH_SHORT).show()
    }
}

