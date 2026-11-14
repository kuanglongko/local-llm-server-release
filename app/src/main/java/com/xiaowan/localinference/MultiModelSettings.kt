package com.xiaowan.localinference

import android.content.Context

/**
 * 「多模型路由 + 内存 LRU」的独立开关与配置。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么必须与 [OnDemandSettings] 分开
 * ═══════════════════════════════════════════════════════════════════════
 * [OnDemandSettings] 回答的是「模型什么时候**在**内存里」：总开关、预设、
 * 空闲多久释放、加载期间排多长的队。本对象回答的是**多份模型同时存在时**
 * 才出现的问题：最多驻留几份、按什么规则选模型、内存高水位时先赶谁走。
 *
 * 两者混在一起最容易出现的坏法是：有人把「LRU 空闲时间」当成按需加载的
 * 空闲时间改，或者把「最多驻留数」当成 HTTP 并发数。它们共享 `Prefs.MODEL`
 * 存储（升级不迁移），但**语义与默认值各自独立**。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 默认值纪律
 * ═══════════════════════════════════════════════════════════════════════
 * [enabled] 默认 `false`，且所有读函数在关闭时都不应被调用 —— 多模型路径
 * 完全旁路，旧版单模型路径逐字节不变。其余默认值只有在总开关打开后才生效。
 */
object MultiModelSettings {

    /** 总开关。默认关 = 不进入多模型路由 / 多实例状态机 / LRU 驱逐。 */
    private val enabledPref = BoolPref(Prefs.MODEL, "multi_model_enabled", false)

    /**
     * 路由模式：`static` = 只按显式映射 / 预设 / 上次成功；`dynamic` = 允许按
     * 请求参数（tools、ctx）从候选模型里挑一个。默认 `static`，行为更可预测。
     */
    const val MODE_STATIC = "static"
    const val MODE_DYNAMIC = "dynamic"

    private val modePref = StringPref(Prefs.MODEL, "multi_model_routing_mode", MODE_STATIC)

    /**
     * 最多同时驻留几份对话模型。
     *
     * 默认 2 而不是 4：手机上两份权重 + 各自 ctx/KV 已经是现实上限；再高只是
     * 把每一路的速度拖到不可用。真正的上限仍由内存水位与 [minFreeMb] 决定。
     */
    const val DEFAULT_MAX_LOADED = 2
    const val MAX_LOADED_MIN = 1
    const val MAX_LOADED_MAX = 8

    private val maxLoadedPref = IntPref(
        Prefs.MODEL, "multi_model_max_loaded", DEFAULT_MAX_LOADED,
        { it.coerceIn(MAX_LOADED_MIN, MAX_LOADED_MAX) })

    /**
     * 加载期间每份运行时最多挂起几个请求。**多模型自己的**一格。
     *
     * 不沿用 [OnDemandSettings.maxQueue]：按需加载的排队上限属于单模型状态机，
     * 两者同时打开时按需会被旁路，若仍读那一格，用户改「按需排队」会意外
     * 改变多模型的行为（而多模型的设置页根本没有这个入口）。
     */
    const val DEFAULT_MAX_WAITERS = 10
    const val WAITERS_MIN = 1
    const val WAITERS_MAX = 100

    private val maxWaitersPref = IntPref(
        Prefs.MODEL, "multi_model_max_waiters", DEFAULT_MAX_WAITERS,
        { it.coerceIn(WAITERS_MIN, WAITERS_MAX) })

    /**
     * 多模型的**默认模型**（别名 / 文件名）。空串 = 跟随模型库里当前选中的
     * 那一项，再交给路由器按静态/动态规则处理。
     *
     * 不沿用 [OnDemandSettings.presetModel]：那一格是单模型模式的目标，按需
     * 被旁路后它不该继续左右多模型选谁 —— 否则用户在「按需预设」里选了一个
     * 模型，多模型却静默地把它当成默认模型。
     */
    private val defaultModelPref =
        StringPref(Prefs.MODEL, "multi_model_default_model", "")

    /**
     * 多模型空闲卸载阈值（秒）。与按需加载的空闲时间**分开**：按需加载的计时
     * 是「一份模型没人用多久后释放」；这里的是「池子里某一份模型作为 LRU
     * 候选闲了多久才允许赶走」。
     */
    const val DEFAULT_IDLE_SECONDS = 60
    const val IDLE_MIN = 5
    const val IDLE_MAX = 86_400

    private val idlePref = IntPref(
        Prefs.MODEL, "multi_model_idle_seconds", DEFAULT_IDLE_SECONDS,
        { it.coerceIn(IDLE_MIN, IDLE_MAX) })

    /**
     * 内存**高水位**：**已用**内存占总量的百分比达到这个值时开始 LRU 驱逐。
     *
     * 触发是"已用 >= high **且** 可用低于 [DEFAULT_MIN_FREE_MB]"；驱逐到
     * "已用 <= low" 或 "可用 >= 保底" 才停（见 [DEFAULT_LOW_PCT]）。
     * 默认 80/65 留出滞回，避免在阈值上反复加载/卸载。注意这不是"可用内存
     * 低于 80%"——那样在小内存设备上会过早驱逐（可用 79% 并不紧张）。
     */
    const val DEFAULT_HIGH_PCT = 80
    const val DEFAULT_LOW_PCT = 65
    const val PCT_MIN = 30
    const val PCT_MAX = 95

    private val highPctPref = IntPref(
        Prefs.MODEL, "multi_model_memory_high_pct", DEFAULT_HIGH_PCT,
        { it.coerceIn(PCT_MIN, PCT_MAX) })

    private val lowPctPref = IntPref(
        Prefs.MODEL, "multi_model_memory_low_pct", DEFAULT_LOW_PCT,
        { it.coerceIn(PCT_MIN, PCT_MAX) })

    /**
     * 绝对可用内存下限（MB），默认 1536（1.5GB）。
     *
     * 高水位触发要求**两个条件同时成立**：已用占比达到 [DEFAULT_HIGH_PCT]，
     * 且可用内存低于这个值。只看百分比在 16GB 设备上过于激进 —— 已用 83%
     * 时仍有两三个 GB 可用，把唯一驻留的模型赶走只会让下一轮请求重载。
     * `0` = 显式关掉绝对下限，退回"只看百分比"。
     */
    const val DEFAULT_MIN_FREE_MB = 1536
    const val MIN_FREE_MB_MAX = 8192

    private val minFreeMbPref = IntPref(
        Prefs.MODEL, "multi_model_min_free_mb", DEFAULT_MIN_FREE_MB,
        { it.coerceIn(0, MIN_FREE_MB_MAX) })

    /**
     * 不允许 LRU 驱逐的模型名（别名 / 文件名，逗号分隔）。
     * 嵌入模型默认由调用方另行保护，不依赖这一格。
     */
    private val pinnedPref = StringPref(Prefs.MODEL, "multi_model_pinned", "")

    /**
     * 容量不足时，请求最多等多久（毫秒）让 LRU 腾出位置。
     * 超时后返回可重试错误，而不是无限挂起。
     */
    const val DEFAULT_LOAD_WAIT_MS = 15_000
    const val LOAD_WAIT_MIN_MS = 1_000
    const val LOAD_WAIT_MAX_MS = 120_000

    private val loadWaitPref = IntPref(
        Prefs.MODEL, "multi_model_load_wait_ms", DEFAULT_LOAD_WAIT_MS,
        { it.coerceIn(LOAD_WAIT_MIN_MS, LOAD_WAIT_MAX_MS) })

    /** 路由失败时的兜底模型名；空串 = 不兜底，直接报错。 */
    private val fallbackPref = StringPref(Prefs.MODEL, "multi_model_fallback_model", "")

    /**
     * 请求体 `model` 是否参与多模型路由。默认**开**：多模型模式的核心行为就是
     * 按名字选模型。关掉时一律用默认模型（预设 / 上次成功），与旧单模型路径一致。
     *
     * 与 [OnDemandSettings.requestModelEnabled] 分开：那一格服务按需加载，默认关；
     * 多模型打开后若还沿用那一格，用户不额外打开它就会发现「请求里写了模型名却
     * 永远用同一个模型」—— 这正是多模型最不该有的默认。
     */
    private val routeByRequestPref =
        BoolPref(Prefs.MODEL, "multi_model_route_by_request", true)

    fun routeByRequestModel(ctx: Context): Boolean = routeByRequestPref.get(ctx)

    fun setRouteByRequestModel(ctx: Context, on: Boolean) = routeByRequestPref.set(ctx, on)

    fun enabled(ctx: Context): Boolean = enabledPref.get(ctx)

    fun setEnabled(ctx: Context, on: Boolean) = enabledPref.set(ctx, on)

    fun routingMode(ctx: Context): String {
        val raw = modePref.get(ctx).trim().lowercase()
        return if (raw == MODE_DYNAMIC) MODE_DYNAMIC else MODE_STATIC
    }

    fun setRoutingMode(ctx: Context, mode: String) {
        modePref.set(ctx, if (mode.trim().lowercase() == MODE_DYNAMIC) MODE_DYNAMIC else MODE_STATIC)
    }

    fun maxLoaded(ctx: Context): Int = maxLoadedPref.get(ctx)

    fun setMaxLoaded(ctx: Context, n: Int) = maxLoadedPref.set(ctx, n)

    fun maxWaiters(ctx: Context): Int = maxWaitersPref.get(ctx)

    fun setMaxWaiters(ctx: Context, n: Int) = maxWaitersPref.set(ctx, n)

    fun defaultModel(ctx: Context): String = defaultModelPref.get(ctx).trim()

    fun setDefaultModel(ctx: Context, name: String) = defaultModelPref.set(ctx, name.trim())

    fun idleSeconds(ctx: Context): Int = idlePref.get(ctx)

    fun setIdleSeconds(ctx: Context, seconds: Int) = idlePref.set(ctx, seconds)

    fun memoryHighPct(ctx: Context): Int = highPctPref.get(ctx)

    fun setMemoryHighPct(ctx: Context, pct: Int) = highPctPref.set(ctx, pct)

    fun memoryLowPct(ctx: Context): Int = lowPctPref.get(ctx)

    fun setMemoryLowPct(ctx: Context, pct: Int) = lowPctPref.set(ctx, pct)

    fun minFreeMb(ctx: Context): Int = minFreeMbPref.get(ctx)

    fun setMinFreeMb(ctx: Context, mb: Int) = minFreeMbPref.set(ctx, mb)

    fun pinnedModels(ctx: Context): Set<String> =
        pinnedPref.get(ctx).split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun setPinnedModels(ctx: Context, names: Collection<String>) {
        pinnedPref.set(ctx, names.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))
    }

    /**
     * 一条「常驻」条目是否指这份模型。
     *
     * 认三种写法：别名、文件名、去掉 `.gguf` 的文件名；**忽略大小写**。
     *
     * 为什么要有这个函数而不是各处自己比：设置页可以手输、模型列表可以点按钮，
     * 两个入口必须用**同一条**匹配规则。以前两处各自 `it == alias || it == fileName...`，
     * 手输 `Qwen3-8B` 而别名是 `qwen3-8b` 时设置显示「已保存」，实际既不常驻、
     * 也不会挡住空闲卸载 —— 属于静默失效。
     */
    fun pinnedEntryMatches(entry: String, alias: String, fileName: String): Boolean {
        val e = entry.trim()
        if (e.isEmpty()) return false
        return e.equals(alias, ignoreCase = true) ||
            e.equals(fileName, ignoreCase = true) ||
            e.equals(fileName.removeSuffix(".gguf"), ignoreCase = true)
    }

    fun loadWaitMs(ctx: Context): Long = loadWaitPref.get(ctx).toLong()

    fun setLoadWaitMs(ctx: Context, ms: Int) = loadWaitPref.set(ctx, ms)

    fun fallbackModel(ctx: Context): String = fallbackPref.get(ctx).trim()

    fun setFallbackModel(ctx: Context, name: String) = fallbackPref.set(ctx, name.trim())

    /**
     * 「还原默认设置」：只删本组**用户参数**，不碰按需加载与上次成功记录。
     *
     * 与 [OnDemandSettings.clear] 同一条纪律：删键而不是写默认值，
     * 否则 `isSet()` 会永远报「用户设过」。
     */
    fun clear(ctx: Context) {
        enabledPref.clear(ctx)
        modePref.clear(ctx)
        routeByRequestPref.clear(ctx)
        maxLoadedPref.clear(ctx)
        maxWaitersPref.clear(ctx)
        defaultModelPref.clear(ctx)
        idlePref.clear(ctx)
        highPctPref.clear(ctx)
        lowPctPref.clear(ctx)
        minFreeMbPref.clear(ctx)
        pinnedPref.clear(ctx)
        loadWaitPref.clear(ctx)
        fallbackPref.clear(ctx)
    }
}
