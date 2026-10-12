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
