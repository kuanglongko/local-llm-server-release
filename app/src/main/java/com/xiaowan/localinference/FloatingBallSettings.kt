package com.xiaowan.localinference

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 「显示悬浮球」的开关、位置与权限三件事。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么单列一个对象
 * ═══════════════════════════════════════════════════════════════════════════
 * 与 [OnDemandSettings] 同一个理由：这些格子回答的是「悬浮球怎么活」，
 * 而 [ServerSettings] 回答的是「HTTP 服务怎么跑」。混进去之后，下一个改端口的人
 * 会看见 `overlay_x/y` 两格坐标，并且很难判断它们跟服务有没有关系。
 *
 * 存 `Prefs.UI`（界面状态），不新增 store：悬浮球是**界面装饰**，
 * 它不参与任何推理/协议面，与「折叠区展开状态」同类。
 *
 * ⚠ 默认**关**：关 = 悬浮球模块完全不初始化（零开销、零权限申请）。
 *   [SYSTEM_ALERT_WINDOW] 是「在其他应用上层显示」的高敏感权限，不能因为
 *   升级就替用户打开一个系统级 overlay —— 那和偷偷申请无障碍是同一类事。
 */
object FloatingBallSettings {

    /** 主开关。默认关 = 与引入本功能之前逐字节同行为。 */
    private val enabledPref = BoolPref(Prefs.UI, "floating_ball_enabled", false)

    /**
     * 上次拖动后停留的位置（屏幕像素，`-1` = 还没拖过）。
     *
     * 存**绝对像素**而不是比例：比例在旋转/换分辨率时更"稳"，但用户拖动时看到的
     * 就是像素位置，存比例再乘回去会让"我明明贴着边"变成"离边差一点"。
     * 越界由 [FloatingBallState.clamp] 在**每次使用前**修正（屏幕可能变小了）。
     */
    private val xPref = IntPref(Prefs.UI, "floating_ball_x", -1) { it }
    private val yPref = IntPref(Prefs.UI, "floating_ball_y", -1) { it }

    fun enabled(ctx: Context): Boolean = enabledPref.get(ctx)

    /**
     * 勾选/取消。
     *
     * 关闭时顺手**抹掉坐标**：留着它会让"关了再开"回到上次那个角落，
     * 而用户很可能正是因为"挡着了"才关的 —— 重新打开就该回到默认位置。
     */
    fun setEnabled(ctx: Context, on: Boolean) {
        enabledPref.set(ctx, on)
        if (!on) clearPosition(ctx)
    }

    fun savedX(ctx: Context): Int = xPref.get(ctx)

    fun savedY(ctx: Context): Int = yPref.get(ctx)

    fun hasPosition(ctx: Context): Boolean = savedX(ctx) >= 0 && savedY(ctx) >= 0

    /**
     * 把开关落成"开"。
     *
     * ⚠ 只有**系统权限真值已经到位**时才能调 —— 这一句是本功能唯一的"开关 = 开"
     *   写入点，谁调它谁负责保证 [canDrawOverlays]。落早一步（例如 `startActivity`
     *   之前就落）会让用户在授权页按返回时留下"开关开着、没有球"的状态，
     *   而那个状态用户无从自查（他不是没点，他是点了没给权限）。
     */
    fun commitEnabled(ctx: Context) {
        enabledPref.set(ctx, true)
    }

    fun savePosition(ctx: Context, x: Int, y: Int) {
        xPref.set(ctx, x)
        yPref.set(ctx, y)
    }

    fun clearPosition(ctx: Context) {
        xPref.clear(ctx)
        yPref.clear(ctx)
    }

    // ---- 权限（本文件是 `SYSTEM_ALERT_WINDOW` 的唯一判据处）----

    /**
     * 「在其他应用上层显示」是否已授予。
     *
     * API 26 起没有运行时申请这条路，只能读 [Settings.canDrawOverlays] 并跳系统页。
     * 例外：`targetSdk <= 22` 的老应用装上即视为已授予 —— 本仓库 targetSdk=28，
     * 不走那一档，但把它写出来，是因为"读不到就返回 true"是最常见的错误兜底。
     */
    fun canDrawOverlays(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (t: Throwable) {
        // 读不到 = 判不清。返回 false（不显示），而不是 true：
        // 猜错方向的代价是 addView 抛 BadTokenException，而那时用户只会看到"没有悬浮球"。
        false
    }

    /**
     * 跳系统授权页。
     *
     * ⚠ 必须带 `package:`：不带的话部分 ROM 打开的是"应用列表"，用户要自己找到本应用；
     *   带了则直接落在本应用那一格开关上（少三步，且不会找错应用）。
     *   `FLAG_ACTIVITY_NEW_TASK` 是硬要求 —— 调用方可能是 Service（非 Activity context）。
     */
    fun requestOverlayIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 系统页解析不到时的降级：整份「特殊应用权限」列表。
     *
     * 与 [KeepAlive] 的逐级降级同一条纪律 —— 各家 ROM 对 `ACTION_MANAGE_OVERLAY_PERMISSION`
     * 的处理不一致，硬跳失败会让用户看到一句"打不开"就结束。
     */
    fun fallbackPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
