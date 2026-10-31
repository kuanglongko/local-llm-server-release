package com.xiaowan.localinference

/**
 * 一整套界面颜色的**纯逻辑容器**（零 android 依赖：不 import 任何东西）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这一层（而不是两个 `if (dark)` 在取色处）
 * ══════════════════════════════════════════════════════════════════════════
 * `EngineActivity` 是本仓库唯一的一屏界面，而它是**全代码装配**的
 * （没有一个 XML 布局），颜色是逐处 `setTextColor(0xFF...)` 硬编码进去的 ——
 * 几十处。系统主题只覆盖"没有被显式设色"的那一层，所以只做 `setTheme()`
 * 的结果是：对话框暗了、页面还是亮的。
 *
 * 于是只有两条路：
 *
 *   · 在每一处取色点写 `if (dark) 0x... else 0x...` —— 几十处各写一遍；
 *     漏一处就是"这一行在暗色下看不见"，而**看不见的地方不会被任何测试
 *     或截图发现**（它只在用户切到暗色时才出现）；
 *   · 把颜色收成**一组有名字的字段**，取色点只说"我要什么语义的颜色"
 *     （正文 / 次要文字 / 警示 / 气泡底），由这里给出当前主题下那一列。
 *
 * 本文件是第二条路的那一组字段。**字段名即语义**：加一个新的取色点时必须
 * 先在这里找一个已有的语义字段，找不到才加字段 —— 加字段的成本就是
 * "两列都要给出一个值"，而漏给一个值编译不过（Kotlin 的具名参数），
 * 比任何注释都硬。
 *
 * ⚠ 刻意**不**做 `ThemePalette.dark(t)` 这种"从一个主题推导另一个"的变换：
 *   暗色不是浅色的数学变换。逐项手写的两列**可以核对**（守卫逐字段比对），
 *   推导出来的那一列只能"看起来差不多"，而"差不多"在长时间阅读的界面上
 *   就是"读不下去"。
 *
 * ⚠ 也刻意**不**引入 `android.graphics.Color` 的 `Color.parseColor("#...")`：
 *   本对象要能在宿主单测里直接构造（`android.graphics.Color` 在宿主
 *   android.jar 里是抛 `Stub!` 的空壳，同 [PureLogic] 一族的老问题）。
 *   所以颜色一律用 `Int` 字面量（`0xFF121212.toInt()`），不带任何 android 依赖。
 */
data class ThemePalette(
    /** 页面底色（`rootScroll` 那层，也是整屏的"背景"）。 */
    val background: Int,
    /** 卡片/面板底色（输入条、吸底栏这类"浮在页面上的一层"）。 */
    val surface: Int,
    /** 次级面板底色（底部 tab 行、分组容器这类）。 */
    val surfaceAlt: Int,
    /** 分隔线（输入条上方那 1dp）。 */
    val divider: Int,
    /** 控件默认前景色（给"没有显式设色"的控件兜底用）。 */
    val onSurface: Int,
    /** 主正文（日志正文、库列表里"在线"的那一条）。 */
    val textPrimary: Int,
    /** 常规正文/说明行（设置页里绝大多数说明文字）。 */
    val textSecondary: Int,
    /** 弱化文字（日志正文、次要提示）。 */
    val textMuted: Int,
    /** 更弱的文字（"还没有对话"、气泡角色标签）。 */
    val textFaint: Int,
    /** 输入框占位/参数名标签。 */
    val hint: Int,
    /** 图标/小标签（角色卡那一行）。 */
    val icon: Int,
    /** 可点的蓝色（tab 选中、折叠组头、会话标题）。 */
    val link: Int,
    /** 次强调（会话标题的浅蓝）。 */
    val accent: Int,
    /** 警示（"要重新加载模型""库里没有这条"这类**要看一眼**的提示）。 */
    val warn: Int,
    /** 错误（失败、不可达）。 */
    val error: Int,
    /** 正常（探活"通"）。 */
    val ok: Int,
    /** 选中的过滤 chip 底色。 */
    val chipOn: Int,
    /** 选中的过滤 chip 字色。 */
    val chipOnText: Int,
    /** 未选中的过滤 chip 字色。 */
    val chipOffText: Int,
    /** 「我」的气泡底色。 */
    val userBubble: Int,
    /** 「我」的气泡正文色。 */
    val userBubbleText: Int,
    /** 「AI」的气泡底色。 */
    val aiBubble: Int,
    /** 「AI」的气泡正文色。 */
    val aiBubbleText: Int,
)

// ══════════════════════════════════════════════════════════════════════
// 两列配色：浅色（本仓库历史色值，逐字）与暗色（逐项手写）
//
// 这一层原先放在 EngineActivity.kt 里。搬出来之后，`ThemePalette.kt` 成为
// 「字段声明 + 两列取值」的唯一文件：字段与取值在同一个文件里，漏一个
// 字段的代价仍是编译不过，而取值点（`pal.xxx`）继续只在 Activity 里。
// ══════════════════════════════════════════════════════════════════════

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
val LIGHT_PALETTE = ThemePalette(
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
val DARK_PALETTE = ThemePalette(
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
