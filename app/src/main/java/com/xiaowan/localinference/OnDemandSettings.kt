package com.xiaowan.localinference

import android.content.Context

/**
 * 「按需加载模型」的独立开关与三格配置。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么单列一个对象，而不是继续塞进 [ServerSettings]
 * ═══════════════════════════════════════════════════════════════════════
 * [ServerSettings] 回答的是「HTTP 服务怎么跑、native 加载参数是什么」；
 * 本对象回答的是「模型什么时候在内存里」—— 两者同属 `model_store`，但语义
 * 完全不同。混在一起最容易出现的坏法是：以后有人把「空闲超时」当成 HTTP
 * 超时去改，或者把「上次成功加载」当成用户选择（`ModelLibrary.selectedName`）
 * 去读，两个值看起来都叫"模型"，实际一个是历史事实、一个是当前选择。
 *
 * 存储键与 [Prefs.MODEL] 不新增 store：这些设置与模型/引擎参数本来就在同一
 * 份用户配置里，升级时不需要迁移。
 */
object OnDemandSettings {
    /** 总开关。默认关 = 与引入本功能之前逐字节同行为。 */
    private val enabledPref = BoolPref(Prefs.MODEL, "on_demand_enabled", false)

    /**
     * 是否允许**请求体里的 `model` 字段**选择要加载的模型。
     *
     * 默认关：与引入本开关之前完全一致 —— 请求里的 `model` 只被当作回显标签，
     * 真正加载谁仍由「预设 / 上次成功」决定。打开后，请求给出的名字优先；
     * 名字解析不到时明确报错，**不会**静默退回预设（那会让调用方以为换模型成功）。
     */
    private val requestModelPref = BoolPref(Prefs.MODEL, "on_demand_request_model", false)

    /** 预设模型文件名；空串 = 用「上次成功加载」记录。 */
    private val presetPref = StringPref(Prefs.MODEL, "on_demand_preset_model", "")

    /** 空闲超时（秒）。0/未设 -> [DEFAULT_IDLE_SECONDS]；读侧统一钳到 [IDLE_MIN]..[IDLE_MAX]。 */
    const val DEFAULT_IDLE_SECONDS = 60
    const val IDLE_MIN = 5
    const val IDLE_MAX = 86_400

    private val idlePref = IntPref(
        Prefs.MODEL, "on_demand_idle_seconds", 0,
        { if (it <= 0) DEFAULT_IDLE_SECONDS else it.coerceIn(IDLE_MIN, IDLE_MAX) })

    /** 加载期间最多挂起几个请求。0/未设 -> [DEFAULT_MAX_QUEUE]。 */
    const val DEFAULT_MAX_QUEUE = 10
    const val QUEUE_MIN = 1
    const val QUEUE_MAX = 100

    private val queuePref = IntPref(
        Prefs.MODEL, "on_demand_max_queue", 0,
        { if (it <= 0) DEFAULT_MAX_QUEUE else it.coerceIn(QUEUE_MIN, QUEUE_MAX) })

    /**
     * 最近一次**成功加载**的对话模型绝对路径。
     *
     * 它不是 `ModelLibrary.selectedName`：后者是「用户此刻在列表里点了谁」，
     * 可以在没加载的情况下改来改去；本键只在 `LlmEngine.loadModel` 真正成功
     * 之后才写，是「这台设备上一次确实把哪份权重装进过内存」的事实。
     * 两者混用会让「预设留空 -> 用上次成功」变成「用用户刚点的那一项」——
     * 而那一项可能从未加载成功过。
     */
    private val lastPathPref = StringPref(Prefs.MODEL, "on_demand_last_model_path", "")

    fun enabled(ctx: Context): Boolean = enabledPref.get(ctx)

    fun setEnabled(ctx: Context, on: Boolean) = enabledPref.set(ctx, on)

    /** 请求体 `model` 字段是否参与选择模型（默认关）。 */
    fun requestModelEnabled(ctx: Context): Boolean = requestModelPref.get(ctx)

    fun setRequestModelEnabled(ctx: Context, on: Boolean) = requestModelPref.set(ctx, on)

    /** 预设模型文件名；空串 = 跟随上次成功记录。 */
    fun presetModel(ctx: Context): String = presetPref.get(ctx).trim()

    fun setPresetModel(ctx: Context, name: String) {
        presetPref.set(ctx, name.trim())
    }

    fun idleSeconds(ctx: Context): Int = idlePref.get(ctx)

    fun setIdleSeconds(ctx: Context, seconds: Int) = idlePref.set(ctx, seconds)

    fun maxQueue(ctx: Context): Int = queuePref.get(ctx)

    fun setMaxQueue(ctx: Context, n: Int) = queuePref.set(ctx, n)

    fun lastModelPath(ctx: Context): String = lastPathPref.get(ctx).trim()

    fun setLastModelPath(ctx: Context, path: String) {
        lastPathPref.set(ctx, path)
    }

    /**
     * 「还原默认设置」用：删掉**用户参数**的键，回到"从没设过"。
     *
     * 只删开关 / 请求指定模型 / 预设 / 空闲 / 排队，**不删** [lastModelPath] ——
     * 它是"这台设备上次确实装进过哪份权重"的历史事实，不是用户调过的参数；
     * 还原后用户再打开按需加载，仍然应该能直接复用这份记录。
     *
     * 删键而不是写默认值：与设置页其它分组同一条纪律 —— `isSet()` 的判据是
     * "键在不在"，写一个默认值进去会让读数永远报"用户设过"。
     */
    fun clear(ctx: Context) {
        enabledPref.clear(ctx)
        requestModelPref.clear(ctx)
        presetPref.clear(ctx)
        idlePref.clear(ctx)
        queuePref.clear(ctx)
    }
}
