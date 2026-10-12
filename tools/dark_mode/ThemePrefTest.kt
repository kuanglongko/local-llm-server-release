package com.xiaowan.localinference

/**
 * 「暗色模式」三档判定的**宿主侧行为测试** —— 跑的是 `ThemePref` 的**真实现**
 * （不是复刻的控制流）。
 *
 * 为什么它能直接跑真实现：`ThemePref` 里带 `Context` 的那几个函数各自只有
 * 一行（读写 SharedPreferences / 读 Configuration），**逻辑一行都不在那里**。
 * 档位判定（`normalize` / `isDark` / `followsSystem` / `themeDark`）全是纯函数，
 * 所以宿主下没有"android.jar 是抛 `Stub!` 的空壳"这个问题 ——
 * 与 `SamplingParams` / `SessionQuota` 那些纯逻辑文件同族。
 *
 * 依赖 kotlinc/JDK，缺则 SKIP（与其它宿主单测脚本同一口径）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 这一份测的是**边界**，不是"三个 if 写对了"
 * ══════════════════════════════════════════════════════════════════════════
 * 三档判定的坏法全都是**零症状**的：不崩、不报错、日志干净，
 * 只表现为"我选了它，它没照做"。所以真正要钉的是这几个边界：
 *
 *   · **默认必须是关闭**（需求原文）—— 而且"没读过"与"用户选过关闭"在
 *     行为上等价、在读数上必须分得开；
 *   · **跟随系统时，系统深浅必须真的参与判定**——写成恒 `false` 会让
 *     "跟随系统"退化成"关闭"，而用户在系统里切来切去都看不到任何变化，
 *     只会以为这个功能坏了；
 *   · **非法值不许漏到判定里**——读到 7 时若不归一，`when` 的 `else` 会把
 *     一个**错的值**悄悄当成"关闭"（抄错一位的失效方式一模一样）。
 */
private var failed = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) failed++
}

fun main() {
    println("== 暗色模式档位（ThemePref）==")

    // ── ① 三档的定义与顺序（设置页按它铺单选，一处改动即可）──────────────
    ck("三档就是 关/开/跟随系统 三个，不多不少", ThemePref.MODES.size == 3)
    ck("三档值互不相等（合成同一个值 = 三档变两档，点哪档都一样）",
        ThemePref.MODES.toSet().size == 3)
    ck("三个常量与 MODES 同源（不是两处各写一遍）",
        ThemePref.MODES.toSet() == setOf(ThemePref.OFF, ThemePref.ON, ThemePref.SYSTEM))
    ck("出厂默认是**关闭**（需求原文：「默认关闭」）", ThemePref.DEFAULT == ThemePref.OFF)

    // ── ② 三档的判定：这是本功能全部行为的那一张真值表 ────────────────────
    for (sys in listOf(false, true)) {
        val sysName = if (sys) "系统深色" else "系统浅色"
        ck("关闭档：$sysName 下仍是亮色（不跟随）", !ThemePref.isDark(ThemePref.OFF, sys))
        ck("打开档：$sysName 下都是暗色（始终暗）", ThemePref.isDark(ThemePref.ON, sys))
        ck("跟随系统档：$sysName 下 = 系统的深浅",
            ThemePref.isDark(ThemePref.SYSTEM, sys) == sys)
    }
    // 这一条是"跟随系统"这个功能存在与否的分界：两次输入不同、两次输出必须不同。
    ck("跟随系统：系统浅→亮、系统深→暗，两次结果**不同**（写了恒 false 就在这红）",
        ThemePref.isDark(ThemePref.SYSTEM, false) != ThemePref.isDark(ThemePref.SYSTEM, true))

    // ── ③ 归一：非法值必须在落地之前被吃掉 ────────────────────────────────
    ck("归一：三个正当值原样通过", ThemePref.MODES.all { ThemePref.normalize(it) == it })
    ck("归一：`7` 这种越界值回落到默认（不回落到某个"碰巧"的档）",
        ThemePref.normalize(7) == ThemePref.DEFAULT)
    ck("归一：`-1` 同样回落到默认（负值是最容易被 `when` 的 else 静默吃掉的）",
        ThemePref.normalize(-1) == ThemePref.DEFAULT)
    ck("归一：`Int.MIN_VALUE` 也是默认（极端值不会把它推成别的档）",
        ThemePref.normalize(Int.MIN_VALUE) == ThemePref.DEFAULT)
    ck("判定侧也做了归一：传入脏值时结果等于默认档的结果",
        ThemePref.isDark(7, true) == ThemePref.isDark(ThemePref.DEFAULT, true))

    // ── ④ followsSystem：只有一档需要"跟着系统配置重画" ──────────────────
    ck("只有「跟随系统」这一档需要重建界面",
        ThemePref.followsSystem(ThemePref.SYSTEM) &&
            !ThemePref.followsSystem(ThemePref.ON) &&
            !ThemePref.followsSystem(ThemePref.OFF))
    // 另两档若也要重建，用户在系统切色时会白闪一次、还丢掉正在输入的正文。
    ck("「打开」档不跟系统走（否则系统切色会白闪 + 丢正文）",
        !ThemePref.followsSystem(ThemePref.ON))

    // ── ⑤ themeDark 与 isDark 是同一个答案（两处各写一遍迟早分叉）────────
    var same = true
    for (m in ThemePref.MODES + listOf(-3, 9)) for (s in listOf(false, true)) {
        if (ThemePref.themeDark(m, s) != ThemePref.isDark(m, s)) same = false
    }
    ck("themeDark 与 isDark 在全部输入上逐项相同（同一个答案，不是两份实现）", same)

    // ── ⑥ 文案：三档各有**互不相同**的名字，且不与"跟随系统"这件事脱钩 ────
    val labels = ThemePref.MODES.map { ThemePref.label(it) }
    ck("三档文案互不相同（两档同文案 = 用户根本分不出自己选了哪个）",
        labels.toSet().size == 3)
    ck("「跟随系统」这一档的文案里写着"跟随系统"（否则用户不知道它跟的是什么）",
        ThemePref.label(ThemePref.SYSTEM).contains("跟随系统"))
    ck("「关闭」这一档的文案里写着"关闭"", ThemePref.label(ThemePref.OFF).contains("关闭"))
    ck("「打开」这一档的文案里写着"打开"", ThemePref.label(ThemePref.ON).contains("打开"))
    ck("**没读过**的档位（越界值）拿到的文案不撒谎：它等于默认那一档",
        ThemePref.label(42) == ThemePref.label(ThemePref.DEFAULT))

    // ── ⑦ 「还原默认」必须让"用户设过没有"这个读数归位 ────────────────────
    // ⚠ 这一条**接线守卫抓不到**（它只钉"clear 的调用点在不在"），而它的坏法
    //   完全静默：`clear` 若被改成"把默认值写进键里"，行为上**看起来一样**
    //   （都是"回到关闭"），但 `isSet` 恒为 true —— 于是"还原默认"这句话
    //   在读数上不成立（同 `EmbedCtxPref` / `EmbedBatchPref` 那条老账）。
    //
    //   宿主下没有 SharedPreferences，所以这里只能钉**语义那一半**：
    //   `DEFAULT` 必须等于 `OFF`，即"还原默认"与"用户选了关闭"在**行为**上
    //   等价 —— 否则"删键"与"写默认值"就不再是同一件事的两种做法。
    //   （另一半由 `ThemePref.clear` 与 `isSet` 的源码结构在守卫里钉。）
    ck("还原默认之后的行为 = 用户选「关闭」之后的行为（默认档与 OFF 是同一档）",
        ThemePref.DEFAULT == ThemePref.OFF)
    ck("「还原默认」不会把用户推到一个他没见过的档上",
        ThemePref.MODES.contains(ThemePref.DEFAULT))

    println(if (failed == 0) "== 暗色模式档位：全部通过 ==" else "== 暗色模式档位：$failed 条失败 ==")
    if (failed != 0) kotlin.system.exitProcess(1)
}
