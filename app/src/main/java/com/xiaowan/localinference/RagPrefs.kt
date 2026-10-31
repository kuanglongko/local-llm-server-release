package com.xiaowan.localinference

import android.content.Context

/**
 * RAG 检索的**开关与库名**四格：App 内一对、远端（HTTP）一对。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么从 [RagInject] 搬出来
 * ══════════════════════════════════════════════════════════════════════════
 * [RagInject] 的头部写着"纯逻辑、可离线单测"，但它此前在同一对象里塞了
 * `enabled` / `currentKb` / `remoteEnabled` / `remoteKb` 四组读写（每组都要
 * `android.content.Context`）。后果不是"不好看"，而是**那份离线单测的边界
 * 说不清**：谁都可以在下一次改动里顺手往 [RagInject] 加一个 prefs 调用，
 * 而编译期不会拦（宿主单测只编挑选的文件，Android 类型在那边能解析）。
 * 搬到一个**只做存储**的对象之后，[RagInject] 里再出现 `Context` 就是显眼的。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么 App 内与远端必须是两对键
 * ══════════════════════════════════════════════════════════════════════════
 * `enabled` 读**默认 true**：App 内聊天的人就在设置那一屏，他知道自己配了
 * 知识库；默认不勾的后果是"配好了但从来没用上"，而它没有任何提示。
 * 远端 HTTP 相反，`remoteEnabled` 读**默认 false**：服务端是**无状态**的，
 * 不知道调用方想不想要 —— 默认注入等于用调用方没要求过的内容污染回答，
 * 而接口照 200，这与幻觉同形。两个默认值针对的是两种消费方。
 *
 * 库名同理必须独立：`currentKb` 是**单一全局值**，在 App 里点任何一条库都会改它。
 * 若远端跟着它走，就会出现"你在 App 里切到库 B 看了两眼，此时远端某个客户端
 * 正在轮询 —— 它会**静默地**换成库 B"。这类故障排查成本极高（回答变差，
 * 却没有任何现象指向"库换了"）。所以远端单独一个库名，切换动作必须是
 * **显式**发生在"远端库名"那一格上。
 *
 * ⚠ 键名里的字面量**不能改**：它们已经是用户设备上落盘的值，改名等于让所有
 *   升级用户的开关与库名凭空丢失（读不到就落默认值）。
 * ⚠ 这里的四条读写纪律全部来自 [BoolPref] / [StringPref]（与第 5 批的
 *   `Embed*Pref` 同一套），本对象只提供键、默认值与转调。
 */
object RagPrefs {

    /** App 内「检索」开关的键。与 [KEY_CURRENT] 同一个 store（[Prefs.RAG]）。 */
    const val KEY_ENABLED = "rag_enabled"

    /** App 内「当前知识库名」的键；""= 没选（等价于"没配知识库"）。 */
    const val KEY_CURRENT = "rag_current_kb"

    /** 远端检索开关的键（与 [KEY_ENABLED] **必须不同**，理由见对象头）。 */
    const val KEY_REMOTE_ENABLED = "rag_remote_enabled"

    /** 远端使用的知识库名的键（与 [KEY_CURRENT] **必须不同**）。 */
    const val KEY_REMOTE_KB = "rag_remote_kb"

    /**
     * 「注入块进历史」开关的键：上一轮拼给模型的块，下一轮对同一条历史 user
     * 原样拼回去（方案 C）。默认 **true** —— 它是纯加速（让上一轮 prompt 成为
     * 这一轮的前缀），不改检索结果；关掉只是回到"每轮重算上一轮那段"的旧开销。
     *
     * ⚠ 与 [KEY_ENABLED] / [KEY_REMOTE_ENABLED] 都不同：它不决定"要不要检索"，
     *   只决定"检索出来的块记不记账、下一轮回放不回放"。三个判据各管一件事，
     *   合成一个键就会出现"关掉回放 = 关掉知识库"这种没人要求过的联动。
     */
    const val KEY_HISTORY_ENABLED = "rag_history_enabled"

    /**
     * 相似度阈值（百分数 0~100）的键。低于它的条目**一条都不注入**。
     *
     * 存在理由：0.35 是一个跟嵌入模型/语料强相关的经验值，写死时用户只能接受它。
     * 阈值太低会把弱相关内容塞进回答（比"没命中"更坏，因为它看起来像有依据）；
     * 太高则明明库里有也不注入。两条方向的症状都是"回答质量变差"，而日志里只有
     * 一句"低于阈值"，用户没法判断该调哪边。
     *
     * 默认 **35**（= [RagInject.DEFAULT_MIN_SCORE] × 100），与升级前逐字节一致。
     */
    const val KEY_MIN_SCORE_PCT = "rag_min_score_pct"

    /** 阈值默认百分数（= 0.35）。改这里必须同步看 [RagInject.DEFAULT_MIN_SCORE]。 */
    const val DEFAULT_MIN_SCORE_PCT = 35

    /** App 内开关；**键不存在时默认 true**。 */
    private val enabledPref = BoolPref(Prefs.RAG, KEY_ENABLED, true)

    /** 注入块进历史（回放）；**键不存在时默认 true**（见 [KEY_HISTORY_ENABLED]）。 */
    private val historyPref = BoolPref(Prefs.RAG, KEY_HISTORY_ENABLED, true)

    /** 相似度阈值；读、写两侧都过 [RagInject.normalizeMinScorePct]，非法值不会落盘。 */
    private val minScorePctPref = IntPref(
        Prefs.RAG, KEY_MIN_SCORE_PCT, DEFAULT_MIN_SCORE_PCT, RagInject::normalizeMinScorePct)

    /** App 内当前库名；缺键 = ""。 */
    private val currentPref = StringPref(Prefs.RAG, KEY_CURRENT, "")

    /**
     * 远端开关；**键不存在时默认 false**（远端的默认是"关"）。
     *
     * 写成 `getBoolean(key, true)` 会把"没配过"读成"开着" —— 于是升级到本版本后，
     * 所有没动过设置的人**突然开始**被注入知识库内容，而他们从没要求过。
     * 判据由 `run_rag_remote_guard.sh` 正面钉住。
     */
    private val remoteEnabledPref = BoolPref(Prefs.RAG, KEY_REMOTE_ENABLED, false)

    /** 远端库名；缺键 = ""（= 远端不注入）。 */
    private val remoteKbPref = StringPref(Prefs.RAG, KEY_REMOTE_KB, "")

    /** 检索开关是否开启（App 内；默认 true）。 */
    fun enabled(ctx: Context): Boolean = enabledPref.get(ctx)

    fun setEnabled(ctx: Context, on: Boolean) = enabledPref.set(ctx, on)

    /** 当前选中的知识库名；""= 没选。 */
    fun currentKb(ctx: Context): String = currentPref.get(ctx)

    fun setCurrentKb(ctx: Context, name: String) = currentPref.set(ctx, name)

    /** 远端检索开关是否开启（默认 false）。 */
    fun remoteEnabled(ctx: Context): Boolean = remoteEnabledPref.get(ctx)

    fun setRemoteEnabled(ctx: Context, on: Boolean) = remoteEnabledPref.set(ctx, on)

    /** 注入块是否进历史并在后续轮次回放（默认 true；只影响缓存/回放，不影响检索）。 */
    fun historyEnabled(ctx: Context): Boolean = historyPref.get(ctx)

    fun setHistoryEnabled(ctx: Context, on: Boolean) = historyPref.set(ctx, on)

    /** 检索相似度阈值（百分数，默认 35）。 */
    fun minScorePct(ctx: Context): Int = minScorePctPref.get(ctx)

    fun setMinScorePct(ctx: Context, pct: Int) = minScorePctPref.set(ctx, pct)

    /**
     * 检索用的 0..1 阈值。**两条路都从这里取**（App / HTTP），不各自除 100 ——
     * 换算写两遍就会出现"设置页 35、这一侧 0.0035"这种从未同时成立过的组合。
     */
    fun minScore(ctx: Context): Float = RagInject.minScoreOfPct(minScorePct(ctx))

    /** 远端使用的知识库名（**独立于** [currentKb]）；""= 没配。 */
    fun remoteKb(ctx: Context): String = remoteKbPref.get(ctx)

    fun setRemoteKb(ctx: Context, name: String) = remoteKbPref.set(ctx, name)
}
