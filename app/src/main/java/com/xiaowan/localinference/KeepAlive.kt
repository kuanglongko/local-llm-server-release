package com.xiaowan.localinference

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 「后台保活」：把**权限状态**与**冻结痕迹**变成可读的一行，并给出能跳过去的入口。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 先说清这一块能做什么、不能做什么（Issue #159）
 * ═══════════════════════════════════════════════════════════════════════════
 * 冻结（cgroup freezer）的判据只看内核的 `MemFree` / `SwapFree`，与"是不是前台服务"、
 * "有没有通知"、「有没有无障碍权限」**完全无关**，而且是**一次判定**的结果 ——
 * 这正是"有时候才卡"的由来。所以这里**不承诺**"开了就不会被冻"。
 *
 * 它解决的是另一件同样真实、却一直没人做的事：**用户以为自己开好了，其实没生效**。
 * 现场最典型的形态是"我明明锁了后台、给了权限，还是卡" —— 而这三种状态在系统设置里
 * 分散在三个页面、在 App 里此前**一个都看不到**：
 *   · 无障碍服务：`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里有本服务的
 *     组件名才算真开（声明了 `<service>` ≠ 用户开了）；
 *   · 电池优化：`PowerManager.isIgnoringBatteryOptimizations(包名)`；
 *   · 后台冻结痕迹：只能从 `/health` 的 `frozen_*` 读（本进程在被冻期间跑不了代码，
 *     自报"我没被冻"是做不到的）。
 *
 * 三条都是**读系统真实状态**，不是读本 App 自己的标志位 —— 与「存活探测」同一条纪律：
 * 标志位只能证明对象自认为如何，说明不了系统那边到底成没成。
 *
 * ## 三条硬纪律（写在这里，改动时别破坏）
 *
 * 1. **只展示、不代改**。无障碍服务的开关只能由用户在系统设置里打开（这是 Android 的
 *    设计，也是本功能唯一的"同意"入口）。本类**没有任何**代码去写
 *    `ENABLED_ACCESSIBILITY_SERVICES` —— 那样做等于绕过用户同意，且需要
 *    `WRITE_SECURE_SETTINGS`（普通应用拿不到，拿到了也不该用）。
 * 2. **状态读取全部走"失败即未知"**。任何一次跨进程查询（`PackageManager` /
 *    `PowerManager` / `Settings.Secure`）都可能抛，而"抛了就当成已开启"是这一族里
 *    最坏的写法：用户会看到一个绿色结论，然后拿着它去排查别的问题。
 *    所以这里是三态（[OnOff.ON] / [Off] / [Unknown]），**未知就报未知**。
 * 3. **引导跳转逐级降级**。各家 ROM 的电池优化页/自启动页不是同一份代码，
 *    `ACTION_*` 常量对不上的机型会直接 `ActivityNotFoundException`。
 *    所以先试厂商页、再试原生页，全失败时给出**可照抄的路径文字**（见 [batterySteps]），
 *    而不是弹一句"跳转失败"就完事。
 *
 * 界面侧的入口只有一处：[EngineActivity] 设置页的「后台保活」分组。
 * 它**不进** `/health` 契约：那是服务端的协议面，而这三样是**这台设备**的本地状态
 * （客户端在另一台机器上读到一个 `accessibility:true` 也没有任何可行动含义）。
 */
object KeepAlive {

    /** 三态。**不许**把它折成 Boolean —— 那会把"查不到"伪装成"没开"。 */
    enum class OnOff { ON, OFF, UNKNOWN }

    /**
     * 无障碍服务的组件（`包名/服务全限定名`）。
     *
     * 用 `ComponentName(context, Class)` 现算而**不**拼字符串常量：包名在
     * `applicationId` 与 `namespace` 可不一致（本仓库两者相同，但那是巧合，
     * 不是可以依赖的约束 —— 拼错的表现恰好是"永远显示未开启"，且不报错）。
     */
    fun accessibilityComponent(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, KeepAliveAccessibilityService::class.java.name)

    /** 本服务在系统设置里的扁平化名字（`ENABLED_ACCESSIBILITY_SERVICES` 用的就是它）。 */
    fun accessibilityFlattened(ctx: Context): String =
        accessibilityComponent(ctx).flattenToString()

    /**
     * 无障碍服务是否**真的开着**。
     *
     * 判据是 `ENABLED_ACCESSIBILITY_SERVICES` 里有没有本组件 —— 与系统设置页那个开关
     * 是同一份数据。**不用** `AccessibilityManager.getEnabledAccessibilityServiceList()`
     * 那种"看得到哪些服务"的读法：它返回的是**可用**列表（含已声明未启用的），
     * 拿它判开关会得到一个恒真的结论。
     *
     * 字符串比较做**大小写不敏感**：不同 ROM 回写的扁平化形式大小写不统一
     * （`ComponentName.flattenToString()` 与 `flattenToShortString()` 也会给出不同结果），
     * 逐字节比会在某些机型上把"已开启"读成"未开启"，而那种误报会直接导致用户反复开关。
     */
    fun accessibilityState(ctx: Context): OnOff = try {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = accessibilityFlattened(ctx)
        val short = accessibilityComponent(ctx).flattenToShortString()
        val hit = enabled.split(':').any { it.equals(me, true) || it.equals(short, true) }
        if (hit) OnOff.ON else OnOff.OFF
    } catch (_: Throwable) {
        OnOff.UNKNOWN
    }

    /**
     * 是否已「忽略电池优化」（Doze / 后台限制豁免）。
     *
     * 为什么它值得占一行：这是**同样由用户授予、但没有无障碍那么敏感**的那条路。
     * 很多 ROM 上"忽略电池优化"对存活率的改善不亚于无障碍，而它只是一次系统弹窗。
     * 所以引导顺序是**先劝它、再提无障碍**（见 [batterySteps] 的文案）。
     */
    fun batteryOptimizationState(ctx: Context): OnOff = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) OnOff.UNKNOWN
        else if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) OnOff.ON else OnOff.OFF
    } catch (_: Throwable) {
        OnOff.UNKNOWN
    }

    /**
     * 从 `/health` 的冻结痕迹生成一行摘要（`null` = 服务此刻读不到，不报数）。
     *
     * 只读**已经取到的**那份结果（[HealthCheck.Result]），不在这里再发一次请求：
     * 请求是「存活探测」的事，这里的职责是把结论翻译成"后台保活"视角的一句话。
     * 冻结痕迹只有服务端能报（被冻期间本进程跑不了代码），所以它是这段里唯一
     * **不能**靠本地查询得到的读数 —— 缺了它，"开了权限到底有没有用"又只能靠体感。
     */
    fun freezeLine(r: HealthCheck.Result?): String {
        if (r == null) return "冻结痕迹：服务未在跑或未探测（先启动服务再点「存活探测」）"
        // 「这次探测本身没成」与「探测成了、但服务端这份响应没带冻结读数」是两件
        // 完全不同的事，**不许**用 `||` 并成同一句"服务端没报这个字段"——
        // 那样用户会去查"服务端字段"，而真因是"服务根本没在跑 / 探测被打断"。
        // 这一行存在的全部理由就是终结"靠猜"，指向错方向等于没写。
        // 分法与 HealthCheck.format 同源：先按 transportError 判这次探测成没成，
        // 成了再看字段缺不缺。
        r.transportError?.let { return "冻结痕迹：本次探测失败（$it），冻结痕迹读不到" }
        if (r.frozenCount == null) {
            return "冻结痕迹：服务端未报 frozen_* 字段（服务可达，但这份响应不含冻结读数）"
        }
        val n = r.frozenCount ?: 0
        if (n <= 0) return "冻结痕迹：没检出过被冻（自启动服务起）"
        return "冻结痕迹：上次 ${(r.frozenMs ?: 0) / 1000}s，累计 $n 次 / " +
            "${(r.frozenTotalMs ?: 0) / 1000}s" +
            if (r.restartPending == true) "，并欠着一次空闲后重启" else ""
    }

    /** 状态行的文案（三态各自一位，未知必须说"查不到"，不许折成"未开"）。 */
    fun onOffText(v: OnOff, onText: String, offText: String): String = when (v) {
        OnOff.ON -> "已开启（$onText）"
        OnOff.OFF -> "未开启（$offText）"
        OnOff.UNKNOWN -> "查不到（系统接口拒绝或不可用，请到系统设置里自己确认）"
    }

    /** 打开系统的「无障碍」设置页。 */
    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * 打开「忽略电池优化」的**申请**页（带包名，系统会直接列出本应用）。
     *
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 是 API 23+ 的正式入口，
     * 比让用户去列表里翻要少三步。它在部分 ROM 上仍然会被拦（返回 false），
     * 所以调用方必须准备好降级文案 —— 见 [batterySteps]。
     *
     * ⚠ 本 action **必须**在 AndroidManifest 里声明同名 `uses-permission`
     *   （normal 权限，无需运行时申请）。漏声明的后果不是崩，而是**静默降级**：
     *   action 解析不到 -> 掉到「优化列表」，或弹出没有本应用条目的总列表。
     *   那种"跳转看起来成功了、但用户找不到自己"与"ROM 拦了"长得一模一样，
     *   归因会指向机型而真因在本项目 —— 见 manifest 里那条权限的注释。
     */
    fun requestIgnoreBatteryOptimizationIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))

    /** 打开系统的「电池优化」总列表页（上面那个被拦时的第一层降级）。 */
    fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 打开本应用的详情页（第二层降级：用户能从这里进「省电策略 / 自启动 / 后台限制」）。 */
    fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))

    /**
     * 动作失败时给用户**照抄得下去**的路径，而不是一句"跳转失败"。
     *
     * 为什么必须逐级给出而不是只说"去系统设置里开一下"：这四步分散在四个不同页面，
     * 各家 ROM 的名字还不一样 —— 用户拿着"去设置里开一下"这句话只能自己摸，
     * 摸不到就回过头认为"App 这个功能是坏的"。
     */
    val batterySteps: List<String> = listOf(
        "1) 「忽略电池优化 / 无限制省电」：设置 → 应用 → 本应用 → 电池 → 选「无限制 / 不优化」",
        "2) 「自启动」：设置 → 应用 → 本应用 → 自启动（部分 ROM 在「权限管理」里）",
        "3) 「后台限制」：设置 → 应用 → 本应用 → 省电策略 → 选「无限制」",
        "4) 最近任务里给本应用**加锁**（下拉图标 / 长按菜单），不同 ROM 位置不同",
    )

    /**
     * 无障碍开启后当前的**实际生效形态**（给排障用）。
     *
     * 为什么值得读：用户开了无障碍但 `eventTypes=0`、`flags=0` 时，在系统看来服务
     * "在跑"，而实际上它什么也不监听 —— 那种形态与"根本没开"在下游表现上可能完全是
     * 两回事，但外观一模一样。把它读出来（而不是假设形参即事实），才能在
     * "开了还是被冻"的现场区分"没开"与"开了但形态不对"。
     *
     * ⚠ 只读**已启用列表**（`getEnabledAccessibilityServiceList`）而**不**用
     *   `PackageManager.getInstalledServices()` 那一类"系统里装了哪些服务"的查询：
     *   后者需要 `QUERY_ALL_PACKAGES` 之类的可见性声明才可靠，而本功能只关心
     *   **自己** —— 读别人的服务列表既超范围、又会在各家 ROM 上读出不同结果。
     *
     * 一条读数用它自己的形状说话：`events=0x0 · flags=0x0` 就是"活着但什么都不看"，
     * 与 `KeepAliveAccessibilityService` 里钉的形态**同源**。
     */
    fun serviceInfoSummary(ctx: Context): String = try {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val cn = accessibilityComponent(ctx)
        val info = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.firstOrNull { it.id?.let { id -> ComponentName.unflattenFromString(id) == cn } == true }
        if (info == null) "未在系统已启用列表里"
        else "已启用 · events=0x${Integer.toHexString(info.eventTypes)}" +
            " · flags=0x${Integer.toHexString(info.flags)}"
    } catch (_: Throwable) {
        "读取失败"
    }

    /**
     * `<service>` 声明在不在 —— 与"用户没开"分开报。
     *
     * 存在意义：声明被误删 / 被 manifest 合并吃掉时，系统设置页里根本不会出现这一项，
     * 而界面上只会显示"未开启"，用户会反复去设置里找。
     *
     * 读法刻意**不**走 `PackageManager.getInstalledServices()`（那需要可见性声明，
     * 且返回的是"系统里装了什么"而不是"本应用声明了什么"）：改用带 `setPackage` 的
     * intent 查询 `queryIntentServices(q, 0)` —— 只查**自己**，且判据是系统设置页
     * 认的那同一个 action（系统自己也是按 action 找服务的，两边同源）。
     * 查不到时返回**空列表**（不抛），`any {}` 自然为 false，等于"没声明"。
     * 其它异常一律按"已声明"处理：它只是排障提示，不是判据本体，
     * 反过来（抽风就报"没声明"）会让每次系统接口抖动都变成一次误报。
     */
    fun serviceDeclared(ctx: Context): Boolean = try {
        val cn = accessibilityComponent(ctx)
        // 走 ComponentName 形式的 intent 查询：不引入包可见性依赖，且只看自己
        val q = android.content.Intent("android.accessibilityservice.AccessibilityService")
            .setPackage(ctx.packageName)
        ctx.packageManager.queryIntentServices(q, 0)
            .any { it.serviceInfo?.let { si ->
                ComponentName(si.packageName, si.name) == cn
            } == true }
    } catch (_: Throwable) {
        true
    }

    /** 版本无关的提示：`Build.VERSION` 只用于文案，不参与任何判定。 */
    val apiLevel: Int get() = Build.VERSION.SDK_INT
}
