package com.xiaowan.localinference

import android.os.Build
import android.util.Log

/**
 * 进程内 llama.cpp 推理引擎。按机型 CPU 能力选择上游预编译变体：
 * - hexagon：CPU + OpenCL GPU + Hexagon HTP NPU（仅高通机型，依赖系统 libcdsprpc.so）
 * - i8mm / dotprod / base：纯 CPU（按 ARM 扩展等级从高到低回退）
 *
 * 职责：
 * - 加载 APK 内置 librnllama（dlopen 后直接 JNI，无需 exec）
 * - 从 assets 释放 Hexagon HTP 段库（libggml-htp-vXX.so）并设置 ADSP_LIBRARY_PATH
 * - 暴露 loadModel / chat 流式生成 / abort / unload
 */
object LlmEngine {
    private const val TAG = "LlmEngine"

    // 上游 rnllama 嵌入的 ggml hexagon host 段库（按 SoC Hexagon 版本 dlopen）。
    private val HTP_LIBS = arrayOf(
        "libggml-htp-v69.so", "libggml-htp-v73.so", "libggml-htp-v75.so",
        "libggml-htp-v79.so", "libggml-htp-v81.so"
    )

    @Volatile private var loaded = false
    @Volatile private var currentPath: String? = null

    // ---- HTP（Hexagon NPU）开关 ----
    // 与模型参数同一个 SharedPreferences 库，UI 写、引擎读，收敛在一处，
    // 避免 EngineActivity / InferenceService / ensureReady 三个入口各传各的 forceHtp 造成不一致。
    private const val PREF_HTP = "htp_experimental"

    /** 用户是否要求启用 HTP（默认 false = 只走 OpenCL/CPU）。 */
    fun isHtpEnabled(ctx: android.content.Context): Boolean =
        ctx.applicationContext.getSharedPreferences(Prefs.MODEL, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_HTP, false)

    fun setHtpEnabled(ctx: android.content.Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(Prefs.MODEL, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HTP, enabled).apply()
    }

    // ---- 「NPU 配额」（mp.tensor_split）----
    // 默认份额约为 NPU 2/3、GPU 1/3，且与模型体积无关。
    // 本档位只改这个比例，**不删任何设备**：被 NPU 拒收的层仍可回落到 GPU/CPU。
    // 设备一旦被摘掉，回落就没有去处，那些层会整段落到 CPU 上跑。
    const val NPU_QUOTA_AUTO = -1
    private const val PREF_NPU_QUOTA = "npu_quota_pct"

    /** -1 = 自动（不传 tensor_split，行为与本功能引入前完全一致）；appCtx 未就绪时同样回落到自动。 */
    fun npuQuotaPct(ctx: android.content.Context? = appCtx): Int =
        ctx?.applicationContext?.getSharedPreferences(Prefs.MODEL, android.content.Context.MODE_PRIVATE)
            ?.getInt(PREF_NPU_QUOTA, NPU_QUOTA_AUTO) ?: NPU_QUOTA_AUTO

    fun setNpuQuotaPct(ctx: android.content.Context, pct: Int) {
        ctx.applicationContext.getSharedPreferences(Prefs.MODEL, android.content.Context.MODE_PRIVATE)
            .edit().putInt(PREF_NPU_QUOTA, pct).apply()
    }

    // ---- 上下文水位（ISSUE #426 方案 B）----
    //
    // 这一格的值 native 也要用（它按**真 token** 再判一次），而 native 侧没有
    // SharedPreferences 通道 —— 所以每次改动都要显式推过去。
    //
    // 推送点有两个，缺一个就是"设置页改了、native 还按旧档判"：
    //   · App 启动（[init] 之后）—— 冷启动时把磁盘上的值灌进去；
    //   · 设置页每点一次档位 —— 这一格**不需要重载模型**，用户预期是立刻生效。
    @JvmStatic private external fun nativeSetCtxWatermark(pct: Int)

    /**
     * 把设置页的水位（%）推到 native。归一在 native 侧也做一次，见那边的注释。
     *
     * appCtx 未就绪时不做任何事（早期启动路径）：native 侧的初值已经是默认档，
     * 而"没推过"与"推了默认值"在行为上等价 —— 下一次 [pushCtxWatermark] 会补上。
     */
    fun pushCtxWatermark(ctx: android.content.Context? = appCtx) {
        val c = ctx ?: return
        runCatching { nativeSetCtxWatermark(ContextWatermark.get(c)) }
            .onFailure { uiLog("[ctx] 水位推送失败：${it.message}（native 按默认 85% 判）") }
    }

    /** 本次进程首次 init 时实际采用的 HTP 取值（native 变体选定后不再改变）。 */
    @Volatile var htpRequested = false
        private set

    /** native 初始化失败原因（部分机型/鸿蒙 ABI 或 dlopen 不兼容时用于诊断）。 */
    @Volatile var lastInitError: String? = null
        private set
    @Volatile private var appCtx: android.content.Context? = null

    /**
     * **每 slot 一把**生成互斥锁（Issue #330 阶段 4 收尾）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不是一把全局锁（这里原来就是一把）
     * ══════════════════════════════════════════════════════════════════════
     * 多 slot 上线**必须**把这一把拆开 —— 否则 `SlotScheduler` 发得出 slot 1，
     * 而两路生成在 `genLock` 上排成一队：接口 200、`[s1]` 也出得来，只是**从头到尾
     * 串行**，并发能力一分没多。这正是本仓库最怕的那类失效（不崩、不报错、
     * 只是"填了没变快"），所以判据要钉在结构上而不是"看行为像不像并发"。
     *
     * 拆成**按下标取**之后，两件事同时成立：
     *   · 同一个 slot 上的不同请求仍然互斥（不可能有两路写同一份 ctx）；
     *   · 不同 slot 之间**没有**任何共享锁 —— 它们各持一份 `llama_context` /
     *     `llama_sampler` / KV 账本，本来就不该排队。
     *
     * 为什么还要留着锁，而不是"每个 slot 天然独占、干脆不加锁"：
     * 生成循环里 `newSampler` → `startCompletion` → `beginCancelable` 三步是一个
     * **不可分割的次序**（`beginCancelable` 取 native 编号的前提正是"刚
     * `startCompletion` 过的那一份"）。即使 slot 是独占的，这个次序仍要防
     * 「同一 slot 的第二路在第一步之后插进来」—— 而那只可能来自调用方的 bug，
     * 加锁是把它变成"排队"而不是"交叉写"。
     *
     * ⚠ 下标按 [SlotScheduler.SLOT_MAX] 固定长度。越界一律回落 slot 0 的那一把 ——
     * 与 native `slot_of` 的越界口径同源（宁可当 slot 0 用，也不返回一个会拼进日志的
     * 假下标）。正常路径上不会越界：slot 由 [SlotScheduler] 发出，域就是 0..SLOT_MAX-1。
     */
    private val slotLocks = Array(SlotScheduler.SLOT_MAX) { Any() }

    /** 取某 slot 的生成锁。**同 slot 互斥、跨 slot 并行**（见 [slotLocks] 的注释）。 */
    fun slotLock(slot: Int): Any = slotLocks[if (slot in slotLocks.indices) slot else 0]

    /**
     * 旧的全局生成锁：**保留名字，但已不再是生成路径的互斥手段**
     * （生成循环一律走 [slotLock]）。留着它是因为 `run_ui_service_guard.sh`
     * 等守卫锚的是它的**存在**与"谁持它"，删名会连带把那些判据的锚点打断；
     * 而它的值域已经没有任何调用方。
     *
     * ⚠ **不要再往生成循环里加 `synchronized(genLock)`** —— 加回去就等于把
     * 并发重新关掉，而症状仍然是"看着能跑，其实一路排队"。
     */
    @Deprecated("生成路径改用 slotLock(slot)；全局锁会把多 slot 并发重新串行化")
    val genLock = Any()

    /**
     * 生成循环内**除了 `LlmEngine.step()` 之外**唯一该做的收尾判定：本轮是否已被取消。
     *
     * 为什么收在引擎里而不是让每个调用方各自查标志位：
     * 「谁在跑」与「取消打给谁」必须对同一个对象生效（见 [RequestCancel] 文件头，
     * 那里记着「迟到的取消停掉别人的轮次」这个真实事故）。把归属状态放在引擎内，
     * 任何一条新入口（HTTP / UI / 以后可能有的别的）都不会漏登记、也不会登记错。
     *
     * 生成期**不逐毫秒轮询**取消标志：`nativeStep()` 一步就是一个 token，粒度天然
     * 是一步一判；本轮已经结束（[step] 返回 null）之后再取消也不会有人读它。
     */
    val currentCancel: RequestCancel.Token? get() = RequestCancel.currentToken()

    /**
     * 引擎此刻是否在跑一轮生成（含 HTTP 生成与 App 内聊天）。
     *
     * 与 [RequestCancel.active] 取同一个真值：取消归属就是「这一轮正在跑」的登记。
     * 两者各算各的，迟早会出现「`/v1/abort` 说停了、忙检查还说在跑」这种对不上的状态。
     *
     * **覆盖范围是「prefill 成功 → 收尾」**（登记与摘除各在一端）。更早的
     * 「请求已受理、还在渲染 prompt」那段不在其中 —— 那一段由 HttpApi 的 `busy`
     * 覆盖，忙检查必须两半都看（见 `HttpApi.isGenerating` 的注释）。
     */
    val isGenerating: Boolean get() = RequestCancel.active

    /**
     * 生成开始：登记取消归属并返回本轮 token，交给调用方在循环里逐 step 判定。
     *
     * 必须在持 [slotLock] 时调用（[startCompletion] 之后立刻）。**不**放进
     * [startCompletion] 里做，是因为 prefill 失败时那一轮根本没跑起来，
     * 此时登记了就得有人负责摘除，多一条容易漏的路径。
     */
    fun beginCancelable(slot: Int = LEGACY_SLOT, owner: Any? = null): RequestCancel.Token? {
        val t = RequestCancel.enterSlot(slot, owner) ?: return null
        // 把本轮的 native 编号绑进 token —— 取消打回 native 时要靠它核对归属。
        // 取值时机正确性：调用方在 `startCompletion` **成功后**、仍在**本 slot 的**
        // `slotLock(slot)` 内调本函数，而 native 的编号在 startCompletion 入口按
        // **该 slot** 自增。同一 slot 的生成循环持着同一把锁，所以此刻
        // `nativeCurrentEpochSlot(slot)` 就是本轮编号，不存在"取到别人编号"的窗口
        //（拿不到 ctx/smpl 编不出的场景见 native 侧注释）。
        t.bindNativeEpoch(runCatching { nativeCurrentEpochSlot(slot) }.getOrDefault(0L))
        // "登记之前就有人在按停止"在这里兑现（ISSUE #450）：prefill 期间表里没有轮次，
        // `/v1/abort` 只能记账（见 [RequestCancel.cancelSlotOrMark]），账落在这里 ——
        // 否则那一轮会一路算满 `max_tokens`，而客户端只看到自己断流。
        // 与上面那次取编号在**同一个锁**里（调用方持本 slot 的 slotLock），
        // 所以不存在"取完编号、兑现之前"插进另一轮的可能。
        if (RequestCancel.adopt(slot, t)) {
            uiLog(slotTag(slot) + "取消在登记之前就到了：本轮立即收尾（prefill 已被 native 打断）")
        }
        return t
    }

    /** 生成结束（正常/异常/取消任一）：摘除取消归属。重复调用安全。 */
    fun endCancelable(t: RequestCancel.Token) = RequestCancel.leave(t)

    /**
     * 请求取消**当前正在生成的那一轮**，并把归属一路送到 native。
     *
     * 这是**唯一**的取消入口（`/v1/abort`、心跳/断连探测、App 停止按钮都走它）：
     *   · 没有轮次在跑 -> 返回 null，什么也不做（"取消了不存在的请求"必须可判定）；
     *   · 有轮次在跑   -> 标记 token + 带编号调 `nativeAbort`。
     *
     * 为什么必须收在一处：Kotlin 侧的 `cancel.requested` 只覆盖"循环自己查标志"
     * 这一条路径（`kotlin` 循环在 `step()` 之前查），**管不到 prefill** ——
     * prefill 是一次阻塞的 native 调用，唯一能打断它的是 native 的 `S.abort`。
     * 此前没有任何路径把取消送到 native，于是"取消"在 prefill 阶段完全无效，
     * 表现为「点了停止，还要等这一大段 prompt 算完」。
     */
    fun requestAbort(): RequestCancel.Token? = requestAbort(LEGACY_SLOT)

    /**
     * 把 [requestAbort] 接到 [AbortGate] 上（初始化时一次）。
     *
     * 为什么要有这一步：[SlotScheduler] 的「让路」要的正是 [requestAbort]（它会打断
     * prefill，见 ISSUE #450），而调度器是**纯逻辑**、宿主要单测它（`run_slot_tests.sh`
     * 等三份套件只编纯逻辑文件）。调度器直接写 `LlmEngine.xxx` 就把那三份套件钉死了：
     * `kotlinc` 报 `unresolved reference`，而 CI 上没有 kotlinc —— 于是"永远绿"。
     * 方向反过来（引擎认识闸门）之后，依赖只剩一条，且那条本来就是对的。
     *
     * 覆盖注册而不是"只认第一个"：见 [AbortGate.install]。
     */
    init {
        AbortGate.install { slot -> requestAbort(slot) }
    }

    /**
     * 取消**指定 slot** 上正在生成的那一轮（`/v1/abort` 带 `slot` 时走它，阶段 4）。
     *
     * 缺省 slot 0 = [requestAbort] 的无参版，与今天的客户端兼容。**只停这一个 slot** ——
     * 一个客户端不许掐掉别人的回答（设计文档 §6）。该 slot 没有轮次时返回 null。
     */
    fun requestAbort(slot: Int): RequestCancel.Token? = requestAbort(slot, occupant = null)

    /**
     * 同上，但由调用方给出**这一格的主人**（`SlotScheduler.Lease`）—— `owner` 就是
     * 那一轮的身份，取消打空时账记在它头上（ISSUE #450 第二次现场）。
     *
     * 为什么要让调用方传而不是这里自己 `leaseAt(slot)` 查：两者在**正常时刻**同值，
     * 但在"刚 acquire、还没写进表"与"已被接管"这两档上会分叉，而这两档正是出错的那两档。
     * 手里有 lease 的调用方（App 生成线程、HTTP 连接线程）知道"我这一轮是谁"，
     * 而这一格上"现在是谁"要到表里查 —— 由知道的那一方给，判据才只有一条。
     * 不给（null）= 只按格打，打空不记账：那正是 prefill 窗口里"有没有人都不知道"的
     * 情况，记下来的账没人认领，只会变成很久之后某个无关请求的意外取消。
     */
    fun requestAbort(slot: Int, occupant: SlotScheduler.Lease?): RequestCancel.Token? {
        // ⚠ ISSUE #450：**打空也要把取消送到 native**。
        //
        // 打空的窗口不是边角料，它正好是 prefill：一轮的登记发生在
        // `startCompletion` **之后**，而 `startCompletion` 内部就把整段 prompt 的
        // prefill 跑完了。于是"客户端在等第一个 token 时点停止"这一刻，表里是空的 ——
        // 旧写法在这里 `return null`，取消到此为止，服务端把 `max_tokens` 算满，
        // 客户端只看到 `Fetch request has been canceled`（它自己断的）。
        // 用户报的就是这一幕（「按了停止，app 还是后台算完了」）。
        //
        // 两跳的顺序是判据，不能换：
        //   ① 先按表置位（有轮次时这一步就够，与旧行为逐字节一致）；打空时把账
        //      记在**这一格的主人**头上（ISSUE #450 第二次现场：记在格子上迟早会
        //      被同格的下一条请求吃掉，见 `RequestCancel` 文件头）；
        //   ② **总是**再按"此刻这一格的编号"把取消送到 native（`abortSlotByEpoch`）。
        //      旧写法只在 token 没拿到编号时才走这一跳，而"取消早到（记在主人头上）
        //      + 登记晚到（prefill 已经跑完）"会让 token 上带着**本轮已经生成**的那个
        //      编号 —— 按它送等于用本轮的号核对本轮，置位发生在 prefill **之后**，
        //      停止照旧落空（ISSUE #450 第五次现场）。
        //      native 侧 `abort_impl` 自己核对编号：对不上就不生效并留痕 ——
        //      它不会停到"别的轮次"上，因为只看这一格。
        val t = RequestCancel.cancelSlotOrMark(slot, occupant)
        // 两跳都由 [abortSlotByEpoch] 负责：带 token 先按它的编号送一遍（已生成那一档），
        // 再按**此刻**这一格的编号送一遍（prefill 那一档 —— 两跳的编号在"取消早到 +
        // 登记晚到"时会分叉，见那个函数的注释）。
        abortSlotByEpoch(slot, t)
        // 打空之后把**无处可去**的那一笔作废：这一格真的空着（`occupant == null`）
        // 时账根本没记（`cancelSlotOrMark` 那一支不记），所以这里只需要处理
        // "记了、但主人已经收尾"这一档 —— 它由一个明确的作废入口收掉，
        // 而不是旧版那条自相矛盾的 `settleUnclaimed(cancelsAt(slot))`
        // （判据"这一格有人吗"恰恰是记账那一刻成立的，于是它永远结不掉）。
        if (t == null && RequestCancel.abandon(occupant)) {
            uiLog("abort: slot $slot 上的那一轮已经收尾，这笔取消无处可去，已丢弃（不会连累下一轮）")
        }
        return t
    }

    /**
     * prefill 窗口里的取消：送 native 时**总是**先用「此刻这一格的编号」核过一遍。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不能只在"token 还没绑编号"时才走这一条（ISSUE #450 第五次现场）
     * ══════════════════════════════════════════════════════════════════════
     * 旧写法是二选一：`t?.nativeEpoch != 0L` 就按 token 上那个编号送，否则现取一次。
     * 而这两个编号在真机上**不等**的情况恰恰就是 prefill 窗口：
     *
     *   · `beginCancelable` 在 `startCompletion` **返回之后**才登记，轮到它登记时
     *     prefill 已经跑完了 —— 于是它取到的 `nativeEpoch` 是**本轮已经开始生成**
     *     的那个号，而不是"这一刻正在 prefill"的号；
     *   · 但取消可能在**登记之前**就到了，那笔账记在主人头上（`cancelSlotOrMark`），
     *     随后被 `adopt` 兑现 —— 兑现的这一刻，`nativeEpoch` 已经绑好了，
     *     于是取消走"按 token 编号"那一跳。
     *
     * 两件事同时成立时（取消早到 + 登记晚到），按 token 编号送就等于**用本轮的号
     * 去核对本轮**：native 那边编号一致、置位成功，而置位发生在 prefill **之后** ——
     * 而 prefill 是一次阻塞调用，它只在自己的循环里查 `S.abort`。于是"停止"落空，
     * 服务端照旧把 prompt 算完、把 `max_tokens` 算满。症状与 #450 第一版逐字相同。
     *
     * 所以这一跳改成**两条都跑**：
     *   · ① 按 token 上已绑的编号送（已生成的那一档，与旧行为逐字节一致）；
     *   · ② 再按**此刻**这一格的编号送一遍（`nativeAbortSlot` 内部有编号核对，
     *     对不上就不生效并留痕 —— 漏停只多烧算力，错停会让用户看到别人的回答被掐断）。
     * 两条都是**幂等置位**，重复调用没有副作用。先 ① 后 ② 是刻意的：② 现取的号
     * 是"此刻这一格正在跑的那一轮"，越靠近用户按下停止的那一刻取，越对得上；
     * 反过来（先取号再置位）中间可能夹进一整轮的开始与收尾，取到的号就成了别人的。
     */
    private fun abortSlotByEpoch(slot: Int, t: RequestCancel.Token? = null) {
        if (t != null) abortRound(t)
        val epoch = runCatching { nativeCurrentEpochSlot(slot) }.getOrDefault(0L)
        if (epoch == 0L) return
        runCatching { nativeAbortSlot(slot, epoch) }
    }

    /**
     * 某一来源此刻**在哪一格**跑；找不到唯一的一格返回 -1（ISSUE #403）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么取消不能只按下标，也不能只按 lease 上的那个数
     * ══════════════════════════════════════════════════════════════════════
     * 单 slot（默认档）时 HTTP 与 App 内聊天**共用 slot 0**，而 `/v1/abort`
     * 不带 `slot` 的旧语义是"停那一路"。于是"缺省打 slot 0"在两个方向上都错：
     *   · 0 上是 App 那一轮时，外部客户端一按停止就掐掉别人的回答；
     *   · 0 上是它自己时，`lease.slot` 又是**挑来的**下标（见
     *     `SlotScheduler.currentSlotOf`），池子一变就打空 —— **打空不报错**，
     *     服务端照旧跑完 `max_tokens`，客户端只看到自己那侧
     *     `Fetch request has been canceled`。这就是本 Issue 的现场。
     *
     * 判据因此建在两个真值上：调度器表里的**来源**（谁占着格子）与
     * `RequestCancel` 表里的**登记**（那一格上跑的是哪一轮）。
     * 只在"这个来源在池子里恰好占一格、且那一格上确实有它的一轮"时给答案 ——
     * 其余一律 -1（多 slot 时 HTTP 可能占好几格，"我这一路"没有唯一所指）。
     *
     * ⚠ 「确实有它的一轮」**不是** `isSlotBusy`（ISSUE #450）。表里的登记发生在
     * `startCompletion` **之后**，而 prefill 就在 `startCompletion` 里面跑 ——
     * 于是"客户端刚发出请求、还在等第一个 token 时点停止"这一刻，格子里是它的，
     * 表里却是**空**的。旧判据（`isSlotBusy`）在这里回 -1，`/v1/abort` 于是
     * 一路落到 `aborted:false`，服务端把 `max_tokens` 算满，客户端只看到自己断流
     * （`Fetch request has been canceled`）—— 用户报的「按了停止，app 还是后台算完了」。
     * 现在改为问调度器"这一轮还在不在这一格"（`currentSlotOf` / `owner === lease`），
     * 它从 `acquire` 成功那一刻就成立，覆盖 prefill。
     */
    private fun sourceSlot(source: SlotScheduler.Source): Int {
        val s = SlotScheduler.slotOfSource(source)
        if (s < 0) return -1
        return if (SlotScheduler.currentSlotOf(SlotScheduler.leaseOfSource(source)) == s) s else -1
    }

    /**
     * 取消**某一来源**正在生成的那一轮（`/v1/abort` 不带 `slot` 时走它，ISSUE #403）。
     *
     * 找到就打它自己的那一格；找不到返回 null —— 与 [requestAbort] 同一语义
     * （"取消了不存在的请求"必须可判定，且**绝不**兜底去停别的 slot）。
     *
     * ⚠ 打这一格时**必须把那格的主人一起交下去**（`occupant`）。少这一跳的后果
     * 只在 prefill 里现形，而且方向正好是"看起来已经处理了"：
     *   · `RequestCancel.cancelSlotOrMark(slot, null)` 在表里没有轮次时**不记账**
     *     （"记下来也没有人认领"，见那条的注释）—— 而 prefill 窗口里表里本来就是空的；
     *   · 于是 `abort_case` 落到 `unowned`（"这一格有人，但记不到它主人头上"），
     *     回给客户端的是 `aborted:false`；
     *   · 那一轮随后正常登记（`beginCancelable`），一点取消也没收到，照旧把
     *     `max_tokens` 算满。现场表现就是本 Issue 那句「客户端 prefill 阶段停止无效」。
     * 主人就在手里（[SlotScheduler.leaseOfSource] 刚问过它），没有任何理由不传。
     */
    fun requestAbortSource(source: SlotScheduler.Source): RequestCancel.Token? {
        // 按来源问不出"我这一路在哪一格"（多 slot 时它可能占好几格）：这里**什么都不做**，
        // 也不去碰任何一笔账 —— 我连自己是哪一格都不知道，就无从判断"那一格上的账
        // 该不该作废"。旧版在这里把**所有格子**的账都结了一遍（`settleAllUnclaimed`），
        // 而它的判据是"这一格此刻有没有人"，恰恰是记账那一刻成立的 —— 于是那些账
        // 一笔也结不掉，只是白跑一遍；现在账记在**身份**上（见 `RequestCancel` 文件头），
        // 身份不明时就该原样留着：留着的那一笔只会被它自己的主人兑现（`adopt`），
        // 不会被别人吃掉。
        val s = sourceSlot(source)
        if (s < 0) return null
        // 主人**就是**刚才用来判"还在不在这一格"的那份 lease（`sourceSlot` 的判据是
        // `currentSlotOf(leaseOfSource(source)) == s`）—— 两处取同一份，不再问第二次。
        return requestAbort(s, SlotScheduler.leaseOfSource(source))
    }

    /**
     * 已持有本轮 token 的调用方（心跳 / 断连探测：它们按"这一次探测判定离开"自己
     * `cancel.request()` 过）在这里补上"送到 native"那一跳。
     *
     * 与 [requestAbort] 分开是因为归属已经确定：重复 `cancelCurrent()` 会踩到
     * "取消一次不成、再取消一次成功"的错觉（第二次会打到**下一个**轮次上）。
     *
     * ⚠ 编号为 0（`beginCancelable` 那一跳取不到号）时 native 会**丢弃**这条取消
     *   （"编号 0 = 没有归属"）。这一档以前沉默，现在至少留一句日志：它是这一族里
     *   最安静的一种失效，而它的原因（native 还没建 ctx / 库没 load）一定在别处有痕迹。
     */
    fun abortRound(t: RequestCancel.Token) {
        // 按 token 上记的 slot 送取消（阶段 4 接线）：一个槽位上的取消若打到别的槽，
        // 就是「用户看到别人的回答被掐断」—— 而归属已经在 token 上绑好了，
        // 这里只消费它，不再另算一次"当前是哪一路"。
        //
        // ⚠ `nativeEpoch` 是 `beginCancelable` 那一刻取的，取不到时留 0；而 native 侧
        //   `abort_impl` 对「编号 0」的处理是**丢弃**（"没有归属"）。于是"取不到号"
        //   这一档看起来像"取消送到了 native"，实际什么都没发生 —— 而它是这一族里
        //   最安静的一种（ISSUE #450 第十一次现场）。这里至少让它说一句；
        //   真正能兜住这一档的是 [abortSlotByEpoch] 的第二跳（按此刻的编号送）。
        if (t.nativeEpoch == 0L) {
            uiLog("abort: slot ${t.slot} 上的这一轮取不到 native 轮次编号（编号 0），" +
                "这条取消到 native 会被丢弃 —— 取消仍按 Kotlin 侧标志生效")
        }
        runCatching { nativeAbortSlot(t.slot, t.nativeEpoch) }
    }

    // ---- 崩溃探针 ----
    // 为什么要有它：这条链路上的闪退此前取证不到 —— 日志回调在 abort 前会丢，
    // JVM 的 UncaughtExceptionHandler 覆盖不到 native abort，客户端只看到连接被重置。
    // 探针把「谁进去了、走到哪一步、崩在哪」写进 native 自己持有的 fd，进程被 abort
    // 杀死时内核缓冲里的数据仍在。
    private const val PREFS_PROBE = Prefs.PROBE
    private const val PREF_PROBE_ON = "probe_on"
    /** 探针是否由用户打开；打开后所有日志一律落 native 探针文件（含全量原生日志）。 */
    @Volatile var probeEnabled: Boolean = false
        private set
    @Volatile var probeFile: java.io.File? = null
        private set
    @Volatile private var probeAttached = false

    fun loadProbeSetting(ctx: android.content.Context) {
        probeEnabled = ctx.applicationContext
            .getSharedPreferences(PREFS_PROBE, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_PROBE_ON, false)
    }

    fun setProbeEnabled(ctx: android.content.Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS_PROBE, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_PROBE_ON, on).apply()
        probeEnabled = on
    }

    /**
     * 挂载 native 探针。**必须在 loadNative() 之后**调用，且必须在 [backendInit] 之前。
     *
     * 为什么必须在 loadNative 之后：`nativeProbeInit` 定义在 llama_jni.cpp、
     * 编进 `libllmjni_<tag>.so`，**不在** libcpufeat.so 里（后者只导出 cpuCaps）。
     * 库没 load 就调它，JVM 只会给一个 UnsatisfiedLinkError。
     *
     * 但顺序对了不等于挂得上，所以**不要把 UnsatisfiedLinkError 当成单一病因**：
     * 覆盖安装（Android 不替换已存在的 lib/arm64-v8a 下的 .so）、CMake 判 up-to-date 跳过
     * native 重编、变体名与实际产物对不上，都会给出一模一样的报错。三种成因的区分办法：
     *   · 顺序/变体名问题 → `[引擎] native 变体=...` 那行在不在、变体名对不对；
     *   · .so 没换新       → 搜 logcat `JNI_OnLoad 已进入`（每次 load 到这个库都会打一行）；
     *   · 符号确实不在库里 → 上面两行都在，仍报 UnsatisfiedLinkError。
     * 另外 native 侧现在会自行自举探针（JNI_OnLoad），所以**即使这一跳失败**，
     * probe-native.log 里也应该有 `[boot]` 开头的内容 —— 一条都没有，才说明 native 完全没跑起来。
     * （前提：用户确实开了探针。自举**只记不写**，文件只由本函数这次调用建立；
     *  用户没开时自举事件只进 logcat，不建文件、不落盘 —— 别把它读成"native 没跑"。）
     *
     * 为什么必须在 backendInit 之前：backendInit 里的 `llama_log_set` 只选一次 sink，
     * 且探针要能接住 backendInit 自身与后续模型加载、渲染、解析各阶段的崩溃。
     *
     * 代价说清楚：探针接不住 `dlopen` 阶段（.so 静态初始化）的崩溃 —— 那需要先 load 才能挂，
     * 循环依赖。但那个阶段崩了 App 根本起不来，是另一个现象；而真正出问题的
     * 模型加载 / prompt 渲染 / 解析 / 生成四个阶段全在覆盖范围内。
     *
     * @return null=挂载成功；否则为失败原因（用于 UI 直接提示，不静默失败）
     */
    fun startProbe(ctx: android.content.Context): String? {
        // ⚠ 这里**不得**因 !probeEnabled 提前 return —— 见下方那次「关」的调用。
        return try {
            val dir = java.io.File(ctx.applicationContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            probeFile = java.io.File(dir, "probe-native.log")
            // nativeProbeInit 在 libllmjni_<tag>.so 里（不在 libcpufeat.so），
            // 库没加载就调只会拿到 UnsatisfiedLinkError。这里先挡一道，把原因说清楚。
            //
            // 注意这一跳失败**不代表取证失败**：native 侧在 JNI_OnLoad 里会自行跑一次
            // 自举（**只记不写**），所以日志里「Kotlin 挂载失败」但 probe-native.log 有
            // `[boot]` 内容是正常组合 —— 那说明崩点就在 Kotlin → native 之间，而不是 native 内部。
            // ⚠ 「关」也必须**显式**告知 native，且这一跳要排在所有提前 return **之前**。
            // 此前 !probeEnabled 时直接 return，native 侧永远收不到那次 on=false 的调用，
            // 于是它只能靠自举（无条件打开）猜 —— 用户在设置页关掉探针，native 照样
            // 全量落盘并把日志 sink 换成空操作。现在开关在这里有**唯一**的事实来源。
            //
            // 参数就是 probeEnabled 本人（**不许写死 true**）：写死等于把上面那个故障
            // 换个位置原样搬回来。native 侧无论 on 真假都会先把 fd 收口，所以这次调用
            // 是「用户关掉探针」唯一能传达到 native 的时机。
            //
            // 代价说清楚：native 库没加载时（nativeTag == null）这次调用也发不出去了 ——
            // 那种情况下 native 根本没跑起来，没有文件可关，用 logcat 那两行区分即可。
            if (nativeTag == null) return if (probeEnabled) "native 库尚未加载（loadNative 未成功），探针无处挂载" else null
            probeAttached = nativeProbeInit(dir.absolutePath, probeEnabled)
            if (!probeEnabled) return null
            if (!probeAttached) {
                return "nativeProbeInit 返回 false（可能被自举开关属性强关，或文件打不开/无写权限；" +
                    "logcat 搜 `probe:` 与 `probe boot:` 看原因）"
            }
            probeMark("Kotlin 侧已挂载探针，${
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
            null
        } catch (t: Throwable) {
            probeAttached = false
            if (probeEnabled) "探针挂载异常：${t.javaClass.simpleName}: ${t.message}" else null
        }
    }

    val probeAttachedNow: Boolean get() = probeAttached

    /**
     * 探针打点：写 native fd，同时镜像进 Kotlin 日志文件（进程没崩时便于一次导出看完）。
     *
     * 落盘用 [LogFileStore.appendRaw] 而不是 [LogFileStore.append]：探针模式一律不折叠，
     * 与 [pushLog] 的探针分支保持同一条口径（否则同一份文件里两套折叠规则打架）。
     */
    fun probeMark(msg: String) {
        if (!probeEnabled) return
        try { nativeProbeMark(msg) } catch (_: Throwable) {}
        LogFileStore.appendRaw("[probe] $msg")
    }

    /** 探针原始文本（native 直写的那份，含信号现场）。 */
    fun probeText(): String = runCatching {
        probeFile?.let { if (it.exists()) it.readText() else "" } ?: ""
    }.getOrDefault("")

    /**
     * 上一代的探针原文（native 在 `nativeProbeInit` 打开文件**之前**，把上一份
     * `rename` 成的 `probe-native.log.prev`）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么它必须也进「导出崩溃探针」
     * ══════════════════════════════════════════════════════════════════════════
     * 崩溃现场**就在 `.prev` 里**。这不是"多导一份更全"，而是导出按钮存在的
     * 那条理由本身：
     *
     *   · 用户复现闪退 -> 进程死了 -> **他得重开 App 才能点「导出崩溃探针」**；
     *   · 而重开 App 就是一次冷启动，`probe_rotate_prev` 在 `nativeProbeInit`
     *     打开文件**之前**执行 —— 崩掉那一代的全部内容被 `rename` 成 `.prev`；
     *   · 于是"崩溃探针"里的 `probe-native.log` 是**这一次开机**的，
     *     而用户想给的那一段（信号现场 `!!! ===== SIGNAL n =====`、最后几行、
     *     `>> 函数名` 没有配对 `<<` 的那一处）**一个字都不在里面**。
     *
     * 此前这一份只能靠用户自己进文件管理器翻 `Android/data/.../logs/`
     * （而那个目录在 Android 11+ 上正是普通用户最难碰到的地方），或者靠
     * `session-*.log` 那半份 Kotlin 侧叙述去倒推。按钮下方写着"排查 native 闪退
     * 只需这一份"—— 少了 `.prev` 时那句话是假的。
     *
     * 与 [probeText] 分开取名、分开取文件：两者是**两代**，合并进一个函数会让
     * "导出的是哪一代"变成调用点的实现细节（而它恰恰是读日志的人第一个要问的）。
     */
    fun probePrevText(): String = runCatching {
        val dir = probeFile?.parentFile ?: return@runCatching ""
        val prev = java.io.File(dir, "probe-native.log.prev")
        if (prev.exists()) prev.readText() else ""
    }.getOrDefault("")

    /** 上一代探针是否存在且非空（与 [probeText] 的空态判据同形，UI 分档提示要用）。 */
    val probePrevAvailable: Boolean
        get() = runCatching {
            val dir = probeFile?.parentFile ?: return@runCatching false
            java.io.File(dir, "probe-native.log.prev").length() > 0L
        }.getOrDefault(false)

    /** 是否已加载模型（区别于 isLoaded：后者仅表示 native 库初始化完成）。 */
    val hasModel: Boolean get() = loaded && currentPath != null

    /**
     * UI 侧日志钩子：EngineActivity 设置后，原生日志同时转发到界面与 ring buffer。
     *
     * 实现在 [EngineLog]（第 7 批 H6）；这里保留同名属性与门面，
     * `EngineActivity` 的赋值点一个都不用改。
     */
    var logSink: ((String) -> Unit)?
        get() = EngineLog.logSink
        set(value) { EngineLog.logSink = value }

    fun setLogFilter(keys: Set<String>, keyword: String) = EngineLog.setLogFilter(keys, keyword)
    fun logFilterActive(): Boolean = EngineLog.logFilterActive()
    fun logFilterKeysNow(): Set<String> = EngineLog.logFilterKeysNow()
    fun logFilterKeywordNow(): String = EngineLog.logFilterKeywordNow()
    fun recentLogs(): List<String> = EngineLog.recentLogs()
    fun recentLogsUnfiltered(): List<String> = EngineLog.recentLogsUnfiltered()
    fun ringSize(): Int = EngineLog.ringSize()
    fun clearLogs() = EngineLog.clearLogs()

    /**
     * 进 ring buffer 的同时**实时落盘**（filesDir/logs/session-*.log）。
     * 原来日志只在内存里，native SIGABRT 瞬间进程就没了，用户根本来不及点导出——
     * 而这正是最需要日志的时刻。落盘只 flush 不 fsync，不引入可感知延迟。
     */
    /**
     * 卡死看门狗的计时基准：最近一次"确实有进展"的时刻（一条非噪音日志，
     * 或一步解码）。native 线程卡在 DSP 等回包时，Kotlin 侧的 stopRequested / abort 都
     * 够不着它，只能靠外部计时把「卡死」变成可判定的事实。
     *
     * **必须用 [System.nanoTime]（单调钟）**：这只被用作"两个时刻相减"的差，
     * 而不是"某时某刻"的时间戳。`currentTimeMillis` 受 NTP 校准 / 用户改表影响会跳变：
     * 前跳 → `now - lastProgressAt` 瞬间巨大 → 看门狗误报 native 卡死（状态栏引导用户
     * 强杀，丢会话）；后跳 → 差值可能为负 → `idle >= HANG_SEC` 恒假 → 真实卡死永不上报。
     * 差值的两个端点必须取自同一口单调钟，改一处就够不着另一处（读方在 EngineActivity）。
     */
    @Volatile var lastProgressAt: Long = 0L

    private fun pushLog(t: String) {
        // 探针开启时**不过滤噪音、不折叠重复**：崩溃前最后那几条恰恰经常是
        // llama 逐 token 打的 "."，折叠会把它们挤掉。
        //
        // 三条落点**同时**保留（此前只落 native 探针文件，会话文件整段缺失）：
        //   ① native 探针文件 —— 经 nativeProbeMark 直写 fd，崩瞬间也不丢；
        //   ② Kotlin 会话文件 —— 经 LogFileStore.appendRaw 落盘（**不折叠**），
        //      这样"导出崩溃探针"里"Kotlin 侧本次会话全文"那段不再是空的；
        //   ③ 内存 ring（放宽到 PROBE_RING），日志页能看到。
        // stderr 那份也走这里（StderrPump → uiLog → pushLog），于是 ggml_abort
        // 那类"只往 fd 2 打"的遗言同样会落进会话文件 —— 此前它在探针模式下丢失。
        if (probeEnabled) {
            // ⚠ 探针模式**不得推新 lastProgressAt**（这里此前正是这么写的）。
            //
            // 探针开着时，llama 逐 token 往 stderr 打 "." 这类回调**每一行**都会
            // 独立"确认有进展"，于是卡死看门狗（看着 lastProgressAt）在取证模式下
            // 永久静默 —— "服务没死但僵着"这个已知故障，**只在你开探针准备排查它时
            // 变得不可检测**，方向恰好是反的。
            //
            // 探针的职责是"把现场写下来"，不是"报告进展"。真正证明有进展的是
            // 下面 `decode`/`onToken` 那两处显式打点（不在本函数里），
            // 与探针开不开无关。
            try { nativeProbeMark(t) } catch (_: Throwable) {}
            LogFileStore.appendRaw(t)
            EngineLog.push(t, probe = true)
            HtpProbe.observe(t)
            EngineLog.emit(t)
            return
        }
        // 纯探测噪音不进 ring、不进文件，只累计一条计数说明（见 LogFileStore.isNoise）
        if (LogFileStore.isNoise(t)) {
            LogFileStore.noteNoise(t)
            return
        }
        // ⚠ 必须是**单调钟**，且与读方（`hangWatch` 的 `System.nanoTime() - lastProgressAt`）
        // 同口。这里此前写的是 `System.currentTimeMillis()` —— 单位差 1e6 倍，
        // 两者相减永远是个巨大负数，于是「连续 N 秒无进展」那条告警**永远触发不了**
        // （真实卡死被静音）。见 run_monotonic_clock_guard.sh 的 G1。
        lastProgressAt = System.nanoTime()
        LogFileStore.flushNoise()
        // 连续重复行折叠成 "xxx ×N"（折叠与窗口在 EngineLog.push 里）。
        EngineLog.push(t, probe = false)
        HtpProbe.observe(t)
        LogFileStore.append(t)
    }

    /** 原生日志回调对象：onNativeLog(level: Int, text: String) */
    private val logBridge = object : Any() {
        @Suppress("unused")
        fun onNativeLog(level: Int, text: String) {
            val t = text.trimEnd('\n')
            when (level) {
                3 -> Log.w(TAG, t)
                4 -> Log.e(TAG, t)
                else -> Log.i(TAG, t)
            }
            if (t.isBlank()) return
            pushLog(t)
            try { logSink?.invoke(t) } catch (_: Exception) {}
        }
    }

    val isLoaded: Boolean get() = loaded

    /** 业务侧状态日志，与原生日志同管线（ring buffer + 落盘 + logSink + logcat）。 */
    fun uiLog(t: String) {
        Log.i(TAG, t)
        pushLog(t)
        try { logSink?.invoke(t) } catch (_: Exception) {}
    }

    /**
     * **进度回执**：一条日志，但**不落盘、不进日志 ring**，只在 UI 上活到下一句覆盖它。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么必须有这一个入口（而不是继续用 [uiLog]）
     * ═══════════════════════════════════════════════════════════════════════
     * 逐条编码那种循环按**块**说话：一份 3 MB 的整篇文档切出上万块，
     * 一趟建库就是上万行日志。而 [uiLog] 的去向有四个 ——
     * `pushLog`（ring buffer + 通知 UI）→ `logSink`（日志页重绘）→ **落盘**
     * （`LogFileStore` 轮转）→ `logcat`。上万行同时砸进去，主线程排在
     * RingBuffer 的监听器上做排版，而用户看到的是**整个界面卡住、点一下就闪退**
     *（用户报的原文）。
     *
     * 所以这一档的语义**刻意**定成"说进度"而不是"留证据"：
     *   · 不进 ring：日志页里不留一万行噪声（真正要复盘的"开始/结束/失败"走 uiLog）；
     *   · 不落盘、不进 logcat：它是**屏幕上的一个数字**，不是证据；
     *   · 但它**照样穿过 logSink** —— 那一层由 UI 自己用 `post` 做节流
     *     （见 `EngineActivity` 的进度行），而不是在这里 sleep 或丢帧。
     *
     * ⚠ 这一条**不是**"少记日志"：该留的判据（切了多少块、多少条失败、结论是什么）
     * 全在 [uiLog] 那一档里，一条不少。省掉的只是"第 3741 块好了"这种只在
     * 屏幕上活一秒的信息。
     */
    fun uiProgress(t: String) {
        try { logSink?.invoke(t) } catch (_: Exception) {}
    }

    /**
     * 加载 native 库 + backend init。
     * - HTP 关闭（默认）：**不**释放 HTP 段库、不设 ADSP_LIBRARY_PATH，
     * Hexagon 设备不会注册进 ggml，调度器只有 CPU + OpenCL
     * （gpu=99 直接走 OpenCL/Adreno，不再被 NPU 抢走后 abort）。
     * - HTP 开启：释放段库 + ADSP_LIBRARY_PATH 让 NPU 参与调度（能力边界见 htpSupportedQuants）。
     *
     * @param forceHtp null（默认）= 读 SharedPreferences 里的「HTP」开关；
     * 显式传值仅用于诊断路径覆盖。native 变体一旦选定，本进程内不会再换，改开关需重启 App。
     * 返回是否成功。
     */
    @Synchronized
    fun init(context: android.content.Context, forceHtp: Boolean? = null): Boolean {
        if (loaded) return true
        appCtx = context.applicationContext
        val useHtp = forceHtp ?: isHtpEnabled(context)
        htpRequested = useHtp
        return try {
            // 先建实时日志文件并接管 stderr，才能接住后端初始化阶段的报错
            // （HTP 的 ggml_abort 就是在这之后不久发生的）
            LogFileStore.init(context)
            StderrPump.start { uiLog("[stderr] $it") }
            uiLog("[日志] 本次会话实时写入 filesDir/logs/${LogFileStore.currentFile?.name}")
            if (LogFileStore.previousUnclean) {
                val n = LogFileStore.previousMarkCount
                val note = if (n > 0) "（期间写过 $n 次切后台标记、末尾却不是＝真中断）" else ""
                uiLog("[日志] ⚠ 上次会话 ${LogFileStore.previousSessionId} 末尾无正常结束标记$note，可能在加载中/生成中崩溃或被系统杀死；日志页点「导出上次会话」可回溯死前输出。")
            }
            if (useHtp) prepareHtpLibs(context)
            // HTP 调试旋钮面板不开放给用户：
            // LM_GGML_HEXAGON_* 这些变量高通无公开文档，个别取值会把崩溃变成永久卡死，
            // 界面上摆出来无收益只有风险。
            // 此处只保留 prepareHtpLibs 这一条必需路径。
            loadNative(useHtp)
            // 探针只在 loadNative **成功之后**才挂得上（nativeProbeInit 在 llmjni_<tag> 里），
            // 又必须赶在 backendInit 之前（backendInit 只选一次 log sink）。
            // 这两个约束把挂载点唯一钉死在这里；见 startProbe 的注释。
            startProbe(context)?.let { probeMark("!! 探针挂载失败：$it") }
            probeMark("即将 backendInit")
            backendInit(logBridge)
            probeMark("backendInit 成功")
            loaded = true
            lastInitError = null
            Log.i(TAG, "llmjni[$nativeTag] loaded & backend init ok (htp=$useHtp)")
            if (useHtp && nativeTag != "hexagon")
                uiLog("[引擎] 已请求 HTP，但 hexagon 变体不可用，实际加载 $nativeTag（纯 CPU，HTP 未生效）")
            uiLog("[引擎] 后端：${backendDesc}")
            true
        } catch (t: Throwable) {
            lastInitError = "${t.javaClass.name}: ${t.message}"
            Log.e(TAG, "init failed", t)
            uiLog("[引擎] native 初始化失败：$lastInitError")
            // 非高通机型（天玑 / 麒麟 / Exynos）诊断，便于按错误文案直接定位
            uiLog("[引擎] 设备环境：$deviceProfile")
            diagnoseInitFailure(t.message)
            false
        }
    }

    /** → 设备环境快照（变体选择与 native 加载失败 / SIGILL 诊断用）。 */
    val deviceProfile: String
        get() {
            val mfr = runCatching { Build.SOC_MANUFACTURER }.getOrNull().orEmpty()
            val model = runCatching { Build.SOC_MODEL }.getOrNull().orEmpty()
            val name = if (model.isNotBlank() && !model.equals("unknown", true)) "$mfr $model".trim()
                       else runCatching { Build.HARDWARE }.getOrDefault("?")
            val c = cpuCapsCache
            val caps = if (c == null) "探测=失败"
                       else "dotprod=${c[0] == 1L} i8mm=${c[1] == 1L}" +
                           " hwcap=0x${c[2].toULong().toString(16)} hwcap2=0x${c[3].toULong().toString(16)}"
            return "SoC=$name ABI=${Build.SUPPORTED_ABIS.joinToString("/")} 变体=${nativeTag ?: "未加载"} $caps"
        }

    /**
     * 按 CPU 扩展等级挑一个上游预编译变体加载（从最优到最保守依次尝试，失败自动降级）。
     *
     * 只有 hexagon 变体带 OpenCL GPU + Hexagon NPU，也只有它 DT_NEEDED 高通专有 libcdsprpc.so，
     * 因此"高通 SoC"时才优先选它（主力高通机的 NPU/GPU 路径保持不变）；非高通机型
     * （天玑/麒麟鸿蒙机）直接选纯 CPU 变体，从根上绕开 libcdsprpc 缺失与 smmla SIGILL，
     * 无需任何改二进制的 hack。
     */
    private fun loadNative(forceHtp: Boolean) {
        probeMark("loadNative 开始（forceHtp=$forceHtp）")
        System.loadLibrary("cpufeat")
        cpuCapsCache = runCatching { cpuCaps() }.getOrNull()
        val c = cpuCapsCache
        val dotprod = c != null && c[0] == 1L
        val i8mm = c != null && c[1] == 1L
        val qcom = isQualcommSoC()
        val order = ArrayList<String>(4)
        if (forceHtp || (qcom && i8mm)) order += "hexagon"
        if (i8mm) order += "i8mm"
        if (dotprod) order += "dotprod"
        order += "base"
        var last: Throwable? = null
        for (tag in order) {
            try {
                probeMark("即将 loadLibrary llmjni_$tag")
                System.loadLibrary("llmjni_$tag")
                probeMark("loadLibrary llmjni_$tag 成功")
                nativeTag = tag
                uiLog("[引擎] native 变体=$tag（dotprod=$dotprod i8mm=$i8mm 高通=$qcom）")
                return
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "native variant $tag unavailable: ${t.message}")
            }
        }
        throw last ?: UnsatisfiedLinkError("no llmjni_* variant available")
    }

    /** 本机 CPU 能力 [dotprod, i8mm, hwcap, hwcap2]；libcpufeat 未加载时为 null。 */
    @Volatile private var cpuCapsCache: LongArray? = null

    /** 实际加载的 native 变体：hexagon / i8mm / dotprod / base。 */
    @Volatile var nativeTag: String? = null
        private set

    /** 当前变体是否带 GPU/NPU 后端（只有 hexagon 变体编进了 OpenCL + Hexagon）。 */
    val gpuCapable: Boolean get() = nativeTag == "hexagon"

    /** HTP 是否真正生效——既请求了 HTP，又确实加载到了 hexagon 变体。 */
    val htpActive: Boolean get() = htpRequested && gpuCapable

    /**
     * HTP 后端受理 mul_mat 的量化类型白名单。
     * 对齐 ggml-hexagon.cpp `supported_mul_mat()`（v1.12.2）：只有这些类型的矩阵乘会真正上 NPU，
     * 其余（Q4_K_M / Q5_K_M / Q6_K 等 K-quant 全家）走 default → 被 HTP 拒收、落回 OpenCL/CPU。
     * 此时 HTP 仍会认领 rms_norm/softmax/rope 等旁路 op，于是计算图被切成三块、每层付跨设备同步，
     * 结果可能反而比纯 OpenCL 更慢，故不以「能开 HTP」作为性能推荐依据。
     */
    val htpSupportedQuants: List<String> = listOf("Q4_0", "Q4_1", "Q8_0", "IQ4_NL", "MXFP4", "F16", "F32")

    /** 从 GGUF 文件名提取量化标识（与 HttpApi 同源正则）；取不到=null。 */
    fun quantOf(fileName: String): String? =
        Regex("""(?i)(iq\d+[a-z0-9_]*|q\d+[a-z0-9_]*|f16|bf16|f32)""")
            .find(fileName)?.value?.uppercase()

    /**
     * GGUF header 预读结果。不加载权重（`no_alloc=true`，毫秒级），用于在**第一次加载之前**
     * 就拿到真实量化和「HTP 能受理多少比例的矩阵乘权重」，避免按文件名猜错后被迫重载一遍大模型。
     */
    data class GgufProbe(val realQuant: String, val coverage: Int, val strictUsable: Boolean, val detail: String,
        /** true=来自上次预读的持久缓存（本次未重新读 header）；UI 需如实标注来源。 */
        val cached: Boolean = false)

    /**
     * 文件身份 = 路径 + 大小 + mtime，**预读缓存唯一的命中判据**。
     *
     * 只按路径命中的写法会静默用错：模型文件可以被**就地替换**（外部路径重新下载完成、
     * 续传落盘、adb push 覆盖），路径一个字没变而内容全换。此时旧判定会被继续当成
     * 「这个文件的真实量化」，一路写进 [probeGguf] 的返回值、loadModel 的 HTP 判定
     * 与 `[HTP判定]` 日志 —— 表现是"文件名与实际类型不符"的提示指向一个已经不存在的文件。
     *
     * `peekGguf` 的持久缓存**本来**就是 size+mtime 双校验（"文件被替换或续传完成自动失效"），
     * 而内存缓存这一层此前只有路径 —— 两处口径分叉，正是判据漂移的起点。
     * 现在两侧共用本判据，不再各写一份近似。
     */
    private class ProbeIdentity(val path: String, val length: Long, val mtime: Long) {
        override fun equals(other: Any?): Boolean =
            other is ProbeIdentity && other.path == path && other.length == length && other.mtime == mtime
        override fun hashCode(): Int = path.hashCode() * 31 * 31 + length.hashCode() * 31 + mtime.hashCode()
    }

    private fun probeIdentityOf(path: String): ProbeIdentity {
        val f = java.io.File(path)
        return ProbeIdentity(path, f.length(), f.lastModified())
    }

    @Volatile private var probeId: ProbeIdentity? = null
    @Volatile private var probeVal: GgufProbe? = null

    /** 最近一次预读结果；null=读不出（非 GGUF / header 损坏 / 该库无此符号）。 */
    val ggufProbe: GgufProbe? get() = probeVal

    /** 只对「当前已加载模型」成立的预读结果；path 不匹配（换了文件没重载）时返回 null，UI 应忽略。 */
    val htpProbeForLoaded: GgufProbe?
        get() = if (probeId?.path != null && probeId!!.path == currentPath) probeVal else null

    /** 文件名标称与实际类型不符（只看当前已加载模型）。true 时 UI 亮色提示；判定已按实际类型走，不需重载。 */
    val quantMismatch: Boolean?
        get() = htpProbeForLoaded?.let { !labelsMatch(loadedQuant, it.realQuant) }

    /**
     * 预读 GGUF header（不加载权重，毫秒级）。命中内存缓存时**不再打 native**，
     * 命中判据是 [ProbeIdentity]：路径、大小、mtime 三者全同才算同一个文件。
     *
     * 为什么不能只看路径：[probeIdentityOf] 的注释里写了那条静默故障的完整链路 ——
     * 就地替换后的旧判定会一直被当成"这个文件的真实量化"用下去。
     */
    fun probeGguf(path: String): GgufProbe? {
        val id = probeIdentityOf(path)
        if (probeId == id) return probeVal
        val a = try {
            nativeProbeGguf(path)
        } catch (t: Throwable) {
            uiLog("[HTP判定] 预读不可用（${t.javaClass.simpleName}），回退按文件名判断")
            null
        }
        val p = if (a != null && a.size >= 4 && a[0].isNotEmpty() && a[1] != "-1")
            GgufProbe(a[0], a[1].toIntOrNull() ?: -1, a[2] == "1", a[3]) else null
        // "读不出" 必须出声：此前它只体现在"日志里少一行 [HTP判定]"，
        // 与"这次根本没调预读"完全同形。a[3] 是 native 给的**原因**
        // （文件头检查未通过 / header 读取失败 / 路径为空…），如实转述。
        // Issue #238 之后这一点更重要：native 的前置检查正是为了**不**把
        // 读不了的文件交给库去解析，所以"没读出来"是设计内结果，不该静默。
        if (p == null) {
            val why = a?.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: "未返回原因"
            uiLog("[HTP判定] 预读不可用（$why），回退按文件名判断")
        }
        probeId = id
        probeVal = p
        return p
    }

    private fun probePersistKey(path: String) =
        "ggufprobe:" + java.lang.Integer.toHexString(path.hashCode()) + ":" + path.length

    /**
     * 给 UI 用的「不加载也能拿到量化信息」入口。四级来源，从快到慢：
     * 1. 本进程内存（之前预读过的任意文件，不限已加载模型）
     * 2. 持久缓存（key=path，再用 size+mtime 双校验，文件被替换或续传完成自动失效）
     * 3. native 现读 header（毫秒级；前提是 lib 已加载，onCreate 的 init 正常就满足）
     * 4. null（native 初始化失败的机型且无缓存）
     * 与 [probeGguf] 的区别：本函数会落盘缓存、命中缓存时不打 native，且绝不触发 init。
     */
    fun peekGguf(ctx: android.content.Context, f: java.io.File): GgufProbe? {
        val path = f.absolutePath
        val id = ProbeIdentity(path, f.length(), f.lastModified())
        if (probeId == id && probeVal != null) return probeVal
        val sp = ctx.applicationContext.getSharedPreferences(Prefs.MODEL, android.content.Context.MODE_PRIVATE)
        sp.getString(probePersistKey(path), null)?.let { tag ->
            val p = tag.split('|', limit = 6)
            if (p.size >= 6 && p[0] == f.length().toString() && p[1] == f.lastModified().toString())
                return GgufProbe(p[2], p[3].toIntOrNull() ?: -1, p[4] == "1", p[5], cached = true)
        }
        if (nativeTag == null) return null   // 不为「看一眼」付 init 的代价（释放段库 + backendInit）
        val fresh = probeGguf(path) ?: return null
        val v = f.length().toString() + "|" + f.lastModified() + "|" + fresh.realQuant + "|" +
            fresh.coverage + "|" + (if (fresh.strictUsable) "1" else "0") + "|" + fresh.detail
        ctx.applicationContext.getSharedPreferences(Prefs.MODEL, android.content.Context.MODE_PRIVATE)
            .edit().putString(probePersistKey(path), v).apply()
        return fresh
    }

    /** 删除/替换模型文件时清掉它的预读缓存，避免残留脏判定。 */
    fun forgetProbe(ctx: android.content.Context, path: String) {
        ctx.applicationContext.getSharedPreferences(Prefs.MODEL, android.content.Context.MODE_PRIVATE)
            .edit().remove(probePersistKey(path)).apply()
        if (probeId?.path == path) { probeId = null; probeVal = null }
    }

    /**
     * 「这个文件能不能吃到 HTP」的纯判定，不依赖引擎当前加载了谁。
     * 以 GGUF header 里的实际类型为准，文件名只在读不出时兜底；两者都缺 = null（UI 不下结论）。
     */
    fun quantOkOf(probe: GgufProbe?, fileQuant: String?): Boolean? {
        probe?.let { if (it.coverage >= 0) return it.strictUsable }
        val q = fileQuant ?: return null
        return htpSupportedQuants.any { q.startsWith(it) }
    }

    /**
     * 文件名标签与 ggml 实际类型是否同一回事。K-quant 的 _S/_M/_L 只是混合比例，
     * 实际主类型是 q4_k/q5_k/q6_k，所以 Q4_K_M ↔ Q4_K 算一致，不该报命名错误。
     */
    fun labelsMatch(fileQuant: String?, realQuant: String?): Boolean {
        if (fileQuant == null || realQuant == null) return true   // 信息不全就不报错
        if (fileQuant == realQuant) return true
        return realQuant.endsWith("_K") && fileQuant.startsWith(realQuant)
    }

    /** 当前真正驻留在内存里的模型路径；未加载=null。区别于「用户在列表里选中的文件」。 */
    val loadedPath: String? get() = if (loaded) currentPath else null

    /** 当前已加载模型的量化标识；未加载=null。 */
    val loadedQuant: String? get() = quantOfFile(currentPath)

    /** 从任意 gguf 文件路径（或文件名）取量化标识；取不到=null。 */
    fun quantOfFile(path: String?): String? =
        path?.substringAfterLast('/')?.let { quantOf(it) }

    /**
     * 当前模型能否被 HTP 加速。
     * null=还没加载模型或文件名里读不出量化类型（此时 UI 不应下判断）；
     * false=不在白名单，开 HTP 只会更慢，UI 需要明确提示。
     */
    val htpQuantOk: Boolean?
        get() {
            htpProbeForLoaded?.let { if (it.coverage >= 0) return it.strictUsable }   // 实际类型优先
            val q = loadedQuant ?: return null
            return htpSupportedQuants.any { q.startsWith(it) }                // 文件名兜底
        }

    /** 给 UI 与日志用的一句话后端描述，只报已确认生效的路径。 */
    val backendDesc: String
        get() = when {
            nativeTag == null -> "未加载"
            htpActive -> "HTP + OpenCL + CPU"
            gpuCapable -> "OpenCL + CPU"
            else -> "CPU（变体 $nativeTag）"
        }

    private fun isQualcommSoC(): Boolean {
        val mfr = runCatching { Build.SOC_MANUFACTURER }.getOrNull().orEmpty()
        val hard = runCatching { Build.HARDWARE }.getOrNull().orEmpty()
        if (mfr.contains("qualcomm", true) || hard.startsWith("qcom", true)) return true
        // API<31 没有 Build.SOC_*（NoSuchFieldError）/ 部分 ROM 填 unknown：退回 /proc/cpuinfo
        return runCatching { java.io.File("/proc/cpuinfo").readText() }
            .getOrDefault("").contains("Qualcomm", true)
    }

    /** 把 dlopen 报错翻译成人话，并给出下一步动作。 */
    private fun diagnoseInitFailure(msg: String?) {
        val m = msg ?: return
        when {
            m.contains("libcdsprpc") ->
                uiLog("[提示] 加载到了高通专用变体但系统没有 libcdsprpc.so：非高通机型本应选 dotprod/i8mm 变体，请把本条日志连同「native 变体」行一起反馈开发者")
            m.contains("libOpenCL") ->
                uiLog("[提示] OpenCL 库缺失：GPU/HTP 变体不可用（纯 CPU 变体不依赖此库），请连同「native 变体」行反馈")
            m.contains("cannot locate symbol") ->
                uiLog("[提示] 系统 GPU 驱动缺少所需 OpenCL 符号，请把本条日志反馈开发者")
        }
    }

    /** 确保 native 引擎就绪；onCreate 的 init 失败时用缓存 Context 自动重试。 */
    @Synchronized
    fun ensureReady(): Boolean {
        if (loaded) return true
        val c = appCtx ?: return false
        return init(c) // 不显式传值，统一按 HTP 开关（SharedPreferences）决策
    }

    /** 把 assets/ggml-hexagon 下的段库释放到 filesDir/htp 并设置 ADSP_LIBRARY_PATH。 */
    private fun prepareHtpLibs(context: android.content.Context) {
        val ctx = context.applicationContext
        val dir = java.io.File(ctx.filesDir, "htp")
        if (!dir.exists()) dir.mkdirs()
        // 关键修正：段库指纹不一致时，先删旧文件再重新提取。
        // 原逻辑只在「文件不存在或长度为 0」时提取，等于把第一代 skel 永久钉死在设备上——
        // 换过 assets 也白换，覆盖安装后 DSP 仍加载老段库。
        // 老 skel 缺 htp_iface_hwinfo（典型报错：HTP0 failed to query hwinfo (0x80000414)），
        // host 只能按默认 vtcm / 线程数去切 op-batch，DSP 执行首个 batch 即失联 →
        // ggml-hexagon.cpp:1583 dspqueue_read failed 0x0000002e → abort。HTP 崩溃根因即此。
        val stamp = java.io.File(dir, ".asset-stamp")
        val want = "htp-v1.12.2-hwinfo-1" // 更换 assets/ggml-hexagon 下任一 skel 时必须同步改这里
        if (runCatching { stamp.readText().trim() }.getOrDefault("") != want) {
            for (name in HTP_LIBS) runCatching { java.io.File(dir, name).delete() }
            Log.i(TAG, "htp skel 指纹变更为 $want，已清理旧段库待重新提取")
        }
        for (name in HTP_LIBS) {
            val out = java.io.File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                try {
                    ctx.assets.open("ggml-hexagon/$name").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    android.system.Os.chmod(out.absolutePath, 0b111101101) // 0755
                } catch (e: Exception) {
                    Log.w(TAG, "skip $name: ${e.message}") // 某些构建可能只带部分版本
                }
            }
        }
        // 指纹落盘：无论个别版本是否缺失都写，避免每次启动重复删除重提。
        runCatching { stamp.writeText(want) }
        try {
            android.system.Os.setenv("ADSP_LIBRARY_PATH", dir.absolutePath, true)
            // 不在 native 侧硬编码以下两项，回到上游默认：
            //   · NDEV：曾用 4（依据是 ndev 过大时报 failed to reserve new session），
            //     但上游默认本就是 1，且多出来的 HTP1..N 若不接算子只占资源。
            //   · VERBOSE：常开会把日志撑到几十 MB 量级并干扰速度观测，故不默认打开；
            //     排查时通过 LM_GGML_HEXAGON_VERBOSE=1 临时开启。
            Log.i(TAG, "ADSP_LIBRARY_PATH=${dir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "setenv failed: ${e.message}")
        }
    }

    /**
     * 串行化所有「动模型状态」的操作（加载 / 卸载 / 换模型）。
     *
     * 为什么非要它：0.9.70 那次「初次安装后乱码」的日志里，同一个进程里
     * `>> loadModel` 出现了 **两次**，分别来自 tid=8596 与 tid=8695，间隔 4 秒、
     * 各自都跑满了约 24 秒，两条 `<< loadModel` 才前后脚返回。
     * 之后那一轮生成 300 token 全是词表碎片（` patribora@akar站©站站()||]>=...`），
     * 且**正好卡在 max_tokens 上限**〔n=300＝max_tokens〕——典型的长出垃圾然后撞上限。
     * 卸载再重新加载（只剩一条 loadModel）后，同一个模型、同一个请求立刻正常。
     *
     * 结论：这不是模型/解码器坏了，是**两个加载同时动同一份 native 状态**。
     * 加载耗时 20 秒以上，而 UI 早把「加载模型」按钮重新置灰前的窗口、服务启动时的
     * 自动挂载、切后台回来后的恢复，都可能在同一时刻各提一次加载。
     *
     * 互斥只保护「状态迁移」，不防抖（不合并请求）：后到的请求排队执行，
     * 语义仍是"最后调用的那次生效"。这样既不会并发，也不会把用户的加载请求悄悄吞掉——
     * 吞掉请求会让界面显示"已加载"而 native 里其实还是旧模型，比并发更难查。
     */
    private val loadLock = Any()

    /** [waitIdleForPool] 的最长等待（ms）。取值理由见 native `LM_POOL_WAIT_MAX_MS` 的注释。 */
    private const val POOL_WAIT_MAX_MS = 20000L

    /** 等待超过这个数就写一行日志（ms）。比生成一轮的时间尺度小两个数量级，够灵敏也够安静。 */
    private const val POOL_WAIT_LOG_MS = 300L

    /** 正在加载中：用于 UI 与日志判据（日志里必须能一眼看出"已有一次加载在跑"）。 */
    @Volatile var loadInFlight: Boolean = false
        private set

    /**
     * 等整池的生成轮次收尾（ISSUE #348）。**必须在 `synchronized(loadLock)` 之前调**。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么需要它
     * ══════════════════════════════════════════════════════════════════════════
     * `loadLock` 只互斥「加载 / 卸载 / 换模型」这几种**管理**动作，**完全不覆盖生成**：
     * 生成循环持的是 `slotLock(slot)`（那把锁存在的意义正是"同 slot 排队、跨 slot 并行"），
     * 而 `unloadLocked()` 一把都不碰。于是「拆掉正在跑的那一份 ctx / 采样器 / 模型」
     * 是一条**真的可达**的路径 —— 它的失效形态不是某次请求报错，是**整个进程**的
     * 信号级崩溃（用户报的"闪退"）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么在这里等、而不是在 native 里死等
     * ══════════════════════════════════════════════════════════════════════════
     * **死锁面只有一个：等的时候不能持 `loadLock`。**
     *   · 本函数持 `loadLock` 等生成 -> 生成线程在 `step()` 返回后可能正排队等
     *     `loadLock`（`doLoad` 那条路径会从生成线程的调用栈上进来）-> 互锁。
     *   · 所以顺序只能是「先等（无锁）-> 后进 `loadLock`」：Kotlin 这一层做等待，
     *     native 那侧只留一道**幂等的最后核对**（`acquire_pool_exclusive`，
     *     走到那里 `loadLock` 已到手，正常情况下必为"没人在跑"）。
     *
     * ⚠ UI 测试页那条路（`nativeNewSampler` 直接进 native）不经 `loadLock`，
     * 所以它靠的是 native 侧的 `free_sampler_guarded`。两层各管一半，不是重复。
     *
     * @return true = 已无轮次（可以拆解）；false = 等到上限仍有人在跑（按旧行为继续）
     */
    fun waitIdleForPool(why: String): Boolean {
        if (SlotScheduler.idle) return true          // 快路径：池子空着，连 native 都不必问
        // ⚠ 这里**不做**"排除自己那一轮"：本函数的三个调用点（`loadModel` /
        //   `unload` / `unloadLocked`）全部是**管理动作**，它们要判的正是"整池能不能拆"
        //   —— 自己这一路（若有）也占着池子，排除它才是错的。native 侧需要排除的
        //   只有 `newSampler` 那一条（它自己就是一轮的起点，不排除就是自己等自己
        //   20s），在那边按**线程**做，见 `Session::active_round_owner`。
        val t0 = android.os.SystemClock.elapsedRealtime()
        val ok = runCatching { nativeWaitPoolIdle(POOL_WAIT_MAX_MS) }.getOrDefault(true)
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        // 留痕：否则"卸载怎么慢了半秒"在日志里完全看不见，读日志的人只会以为卸载本来就慢。
        if (ms >= POOL_WAIT_LOG_MS)
            uiLog("[拆解] 等生成收尾 ${ms}ms（$why）" + if (ok) "" else "——超过上限，按旧行为继续")
        return ok
    }

    /** 加载 GGUF 模型并建上下文。返回错误信息，null=成功。 */
    fun loadModel(
        path: String,
        nGpuLayers: Int = 99,
        nCtx: Int = 4096,
        nThreads: Int = 4,
        flashAttn: Boolean = false,
        useMmap: Boolean = true,
        cacheK: Int = 0,
        cacheV: Int = 0,
        parallelN: Int = 0,
        batchSize: Int = 0,
        ubatchSize: Int = 0
    ): String? {
        // ── 等生成收尾**必须在这一行之前**（ISSUE #348）──────────────────────
        // 进 `loadLock` 之后 native 会做"整池拆旧"，而 `loadLock` 不覆盖生成
        // （生成持的是 `slotLock`）。等在这一侧 = 无锁等待，不会与"生成线程排队等
        // loadLock"互锁；顺序反了就是死锁，理由见 [waitIdleForPool] 的注释。
        waitIdleForPool("loadModel 换模型/加载")
        return synchronized(loadLock) {
            if (loadInFlight) uiLog("[加载] 已有一次加载在进行中，本次排队等待其结束（并发加载会把 native 状态弄脏，见 loadLock 注释）")
            loadInFlight = true
            try {
                loadModelLocked(path, nGpuLayers, nCtx, nThreads, flashAttn, useMmap,
                    cacheK, cacheV, parallelN, batchSize, ubatchSize)
            } finally {
                loadInFlight = false
            }
        }
    }

    /** [loadModel] 的实体；调用方已持有 [loadLock]，这里不再加锁（避免重入与锁序问题）。 */
    private fun loadModelLocked(
        path: String,
        nGpuLayers: Int,
        nCtx: Int,
        nThreads: Int,
        flashAttn: Boolean,
        useMmap: Boolean,
        cacheK: Int,
        cacheV: Int,
        parallelN: Int,
        batchSize: Int,
        ubatchSize: Int
    ): String? {
        // 未初始化不再抛 IllegalStateException（后台线程未捕获 → 整机闪退），
        // 改为自动重试 init，仍失败则把原因作为错误字符串返回给 UI 显示。
        if (!ensureReady()) {
            return "引擎初始化失败：${lastInitError ?: "native 库尚未加载（无可用 Context）"}"
        }
        // 模型库支持切换：加载新路径前先卸载旧模型，避免泄漏旧上下文
        if (hasModel) {
            unloadLocked()
            currentPath = null
        }
        // i8mm/dotprod/base 是纯 CPU 变体，里面根本没有 OpenCL/Hexagon 后端；
        // 硬传 n_gpu_layers>0 只会让 native 报"unknown backend device"，这里直接归零并说明原因。
        var gpuLayers = nGpuLayers
        if (!gpuCapable && gpuLayers > 0) {
            uiLog("[提示] native 变体 ${nativeTag ?: "未知"} 无 GPU/NPU 后端（只有 hexagon 变体带 OpenCL+HTP），gpu layers 归零，按纯 CPU 运行")
            gpuLayers = 0
        }
        // 大 ctx + OpenCL 提前告警（compute buffer 随 ctx 平方级膨胀）
        if (gpuLayers > 0 && nCtx > 32768) {
            uiLog("提示：ctx=$nCtx > 32768，OpenCL compute buffer 需要超大显存，可能分配失败（失败将自动回退纯 CPU）")
        }
        // 判定源改为**先读 GGUF header 拿实际类型**（不加载权重，毫秒级），文件名只做兜底。
        // 这样即使文件名标错（或压根没写量化），首遍加载就能判对，不用「加载→发现没生效→重载」。
        val quant = quantOf(path.substringAfterLast('/'))
        val probe = probeGguf(path)
        val mismatch = probe != null && !labelsMatch(quant, probe.realQuant)
        if (probe != null) {
            uiLog("[HTP判定] 实际类型 ${probe.realQuant}：${probe.detail} → ${if (probe.strictUsable) "矩阵乘可上 NPU" else "矩阵乘上不了 NPU"}")
            if (mismatch)
                uiLog("⚠ 文件名写的是 $quant，实际是 ${probe.realQuant}；已按实际类型判定，不必为此重新加载")
        }
        // 加载前先把 repack 档位交给 native（**显式**下发，未设过传 -1 而非 0）：
        // 与 `load_mode` / `devices` 同一课 —— 开关必须落在仓库自己的判定上，
        // 且"没设过"要能与"显式设为 0"区分开，否则用户关掉之后下一轮会静默回到库默认。
        // appCtx 未就绪时传 -1（未设过）：与 npuQuotaPct 的兜底同一个口径，
        // 不读 prefs 比读错 prefs 安全。
        setRepackMode(appCtx?.let { ServerSettings.extraBufts(it) } ?: -1)
        // 加载前先清计数，保证下面的 [HTP参与] 摘要只描述这一次加载
        HtpProbe.reset()
        val quota = npuQuotaPct()
        // ---- slot 数判决（ISSUE #330 阶段 4）----
        //
        // `parallelN`（UI 的「并发 slot 数」）在这里**第一次真正生效**：调度器决定
        // 实际开出几份 ctx（上限 / 整除 / 回落全在 [SlotScheduler.open] 里），
        // 判决结果与原因落一行日志 —— 没这行「我说了 4、实际只开了 1」是看不出原因的。
        // ⚠ 切分**只在这里做一次**：`nCtx`（设置页填的数）由 [SlotScheduler.open]
        // 切成每份，交给 native 的是 `resolved.nativeArg`（= 每份容量）。
        // native 侧**不再除** `slotCount` —— 之前两边各切一刀，用户填 16000、
        // `parallelN=2` 时每格只剩 4000（"App 不知道为什么切了两遍"）。
        val resolved = SlotScheduler.open(if (parallelN > 0) parallelN else 1, nCtx)
        // ⚠ 「填的数」与「每份」都报出来：用户填 16000 时只看到"每份 8000"会去
        //   改设置（那正是"被写死/被切两遍"那类误读的入口）。单 slot 时两者同值，
        //   那句"总量"就不必重复念（单 slot 与改动前的日志逐字一致）。
        // 格数与每格容量**分开说**（ISSUE #426 第八回合）：设置页那一格就是每格容量，
        // 格数只决定开几格。曾经这里写着"总量 N 按份切分" —— 而那句话本身就是
        // 「设 2 格、实际 3 格」那类读数对不上的入口：份数参与算容量，容量再被读数拿去解释。
        uiLog("[slot] 并发 slot 数 = ${resolved.slots}，每格 ctx=${resolved.ctxPerSlot}" +
            (if (resolved.slots > 1) "（总量 ${resolved.ctxPerSlot * resolved.slots}）" else "") +
            " · ${resolved.why}")
        val slotCount = resolved.slots
        val perSlotCtx = resolved.nativeArg
        var err = if (nativeLoadModelSlot(slotCount, path, gpuLayers, perSlotCtx, nThreads, flashAttn, useMmap, cacheK, cacheV, parallelN, batchSize, ubatchSize, quota)) null
        else "加载失败（GPU 路径，详见上方日志）"
        // GPU/OpenCL 分配失败自动回退纯 CPU 重试（native 失败路径已清理干净，可安全重试）
        if (err != null && gpuLayers > 0) {
            uiLog("GPU(n_gpu_layers=$gpuLayers) 加载失败，自动回退纯 CPU 重试…")
            HtpProbe.reset()   // 回退后权重重分，摘要要以最终这次为准
            err = if (nativeLoadModelSlot(slotCount, path, 0, perSlotCtx, nThreads, flashAttn, useMmap, cacheK, cacheV, parallelN, batchSize, ubatchSize, NPU_QUOTA_AUTO)) {
                uiLog("纯 CPU 加载成功（如需 GPU：减小 ctx 或降低 GPU 层数后再加载）")
                null
            } else "模型加载失败（GPU 与 CPU 均失败：检查 GGUF 完整性 / ctx 是否超出可用内存）"
        }
        // ── 池尺寸必须以 **native 的真值**收口（Issue #330 阶段 5 真机复盘）──
        //
        // 上面那次 `SlotScheduler.open(...)` 是在**加载之前**判决的，而 native
        // 可能压根没开出那么多份：某一份 ctx 建失败会 `g_slots_extra.clear()`、
        // `g_slot_count` 归 1。此时若不同步，调度器就会把 slot 1.. 发出去，而
        // native 的 `slot_of(1)` 因池子空了**回落 slot 0** —— 两路静默挤在同一份
        // ctx 上（读同一个 KV、写同一个 KV）。
        // 真机表现正是：「日志里只有 s0」+「KV 一直互相顶掉」，不崩、不报错。
        //
        // ⚠ 这一步在**失败路径上也要走到**：上面 `err != null` 时也执行到这里，
        //   而那种情况恰恰是池子只剩 `S` 的那一种 —— 漏掉它就把最需要收窄的场景放过了。
        // 判据是 native 的 `g_slot_count`（[slotCount]），不是"这次加载返回成功"——
        // 后者只说明 `S` 建出来了，与"一共建出了几份"是两件事，而这两件事同形
        // 正是本仓库反复栽的那一类（设计文档 §8）。
        // 每份容量也按 native 的真值收口（传 `nativeCtxSize()` = 建出来的那一份的
        // 实际容量）：`/health` 的 `ctx_per_slot` 与 `ContextBudget.capacity` 都读它，
        // 报的必须是"它实际用的"，不是"判决时的愿望值"。
        val realSlots = SlotScheduler.syncTo(slotCount(), runCatching { nativeCtxSize() }.getOrDefault(0))
        if (SlotScheduler.lastSyncNote.isNotEmpty()) uiLog("[slot] ${SlotScheduler.lastSyncNote}")
        else if (realSlots != slotCount) uiLog("[slot] 调度器池尺寸 $slotCount != native $realSlots，已按 native 收窄")
        // 一行把"填的值 → 生效值"说全：总量、每份、份数三者的关系只有这一处能自证。
        // 少这一行，"我填 16000 怎么只剩 4000"就只能靠 /health 反推。
        //
        // 分两件事说，别混成一句：
        //   · `perSlotCtx != nCtx` —— 是**我们按份切的**（设计文档 §4 的语义）；
        //   · `contextSize() != perSlotCtx` —— 是**native 没照做**（平台钳 / 建失败），
        //     这种才是"设置没生效"，必须报出来，否则用户会以为是自己填错了。
        if (err == null) {
            val real = contextSize()
            if (real > 0 && real != perSlotCtx)
                uiLog("⚠ [slot] native 实际每份 ctx=$real ≠ 请求的 $perSlotCtx（平台受限/建份失败），以实际为准")
            if (perSlotCtx != nCtx)
                uiLog("[slot] 填的 n_ctx=$nCtx 是**每格**容量；$realSlots 格合计 ${perSlotCtx * realSlots}")
        }
        // 一行判定本轮 HTP 到底有没有参与——"没崩"本身不是结论，
        // HTP 被踢出计算图后的"没崩"更不是。
        uiLog(HtpProbe.summary())
        HtpProbe.measured().takeIf { it.isNotEmpty() }?.let { uiLog("${LogFileStore.TAG_HTP_MEASURED} $it") }
        // 份额与设备池单独一行：只报实际观测到的值，不写预期。
        val sharePct = HtpProbe.npuSharePct()
        val devPool = HtpProbe.poolDesc()
        if (sharePct >= 0 || devPool.isNotEmpty())
            uiLog("[HTP配额] NPU层份额=" + (if (sharePct < 0) "待实测" else "$sharePct%") +
                    " ｜ HTP在池下标=#" + HtpProbe.htpPoolIndex() +
                    (if (devPool.isEmpty()) "" else " ｜ $devPool"))
        // 模型档案单独一行，与上面的读数**分开**：上面那两行描述"这一次加载怎么算的"，
        // 这一行描述"加载进来的到底是什么"。混在一行会让两种口径在同一条日志里打架。
        //
        // 只在**加载成功**时报：失败时 S.model 已清空，档案必然读不到，
        // 报一行空的 `[模型档案]` 会被读成"这个模型没有档案" —— 而实际是"这次没加载成"。
        // 另外它**不算是错误**：档案是纯只读的装饰，读不到就不报，绝不因此让加载失败。
        if (err == null) {
            val prof = modelProfile()
            if (prof.available) uiLog("${LogFileStore.TAG_PROFILE} ${prof.describe()}")
        }
        if (err == null) currentPath = path
        return err
    }

    fun modelDesc(): String = nativeModelDesc()

    /**
     * 模型档案：库**真读出来的**那几个数（权重字节 / 参数个数 / 上下文上限 / 层数 /
     * 词表 / dtype / 元数据条数），供 `/v1/models/{id}` 与 `[模型档案]` 日志行。
     *
     * 为什么要有它：App 里此前只有一个 `modelDesc()`（库自己拼的 256 字节短描述），
     * 而 `ModelLibrary.Row.bytes` 是**文件字节** —— 「文件多大」「权重多大」「多少个参数」
     * 「上下文上限多少」是**四件不同的事**，本仓库踩过的坑几乎全是"把两个数当成一回事"。
     *
     * 每次调用都重读 native（`/v1/models` 是低频轮询端点，不做缓存）：
     * 缓存就要回答"换模型时怎么失效"，而失效判据恰好是这类静默 bug 的温床。
     */
    fun modelProfile(): ModelProfile = ModelProfile.fromNative(
        runCatching { nativeModelMeta() }.getOrNull())

    /**
     * **这一格**的 `n_ctx`（总量 ÷ slot 数）—— 只读 slot 0。
     *
     * 语义与引入多 slot 之前一致（那时每格就是总量），所以既有调用点一行不用改；
     * 但凡是"拿它和用户填的 ctx 比"的地方都必须用 [effectiveCtxTotal]，
     * 否则多路并发时会把"按份切分"读成"ctx 被写死了"。
     */
    fun contextSize(): Int = nativeCtxSize()
    fun contextUsed(): Int = nativeCtxUsed()

    /**
     * 用户**填的那个** ctx 总量（= 请求的每份 × 池里实际几份）；0 = 没加载 / 不知道。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么它不同于 [effectiveCtxTotal]（ISSUE #392 真机）
     * ══════════════════════════════════════════════════════════════════════
     * [effectiveCtxTotal] 是「它**实际用**了多少」= `llama_n_ctx()` × 份数 ——
     * 而 `llama_n_ctx()` 报的是**建出来的那一份**，平台钳过、或第 2 份建失败时
     * 与请求值不同。拿它去填 `/health` 的 `ctx_size`（那个字段的语义是"用户填的总量"）
     * 就把两件事混成了一个数：用户填 16128 + `parallelN=2` 时报成 `ctx_size: 32256`
     * —— 一个他从没填过的数（每份拿到的本来就是用户填的那个数，再乘一遍就翻倍了）。
     *
     * 取的是 **native 记的请求值**（`g_n_ctx_requested`，加载成功那一刻落的），
     * 不是 `SlotScheduler.loadedCtxPerSlot`：后者会被 `syncTo` 改写成 native 的
     * 实际值 —— 那正是"请求值在读数里消失"的入口。
     */
    fun requestedCtxTotal(): Int {
        val per = ctxRequestedPerSlot()
        if (per <= 0) return 0
        return per * slotCount().coerceAtLeast(1)
    }

    /**
     * 本次加载**真正生效**的 ctx 总量 = 每格 × 池里实际有几份。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不能拿 [contextSize] 去和"我填的 ctx"比（ISSUE #330 用户报的 bug）
     * ══════════════════════════════════════════════════════════════════════
     * `ctx=8192` + `parallelN=2` 时 `contextSize()` 是 **4096**（那是每格），
     * 而用户填的 8192 其实**完全生效了**（8192 是总量，按份切分是刻意的语义）。
     * 两者摆在一起看，就变成"我改了无效，被写死成 8192" —— 读数在撒谎，
     * 而用户在设置页按这个数往下调，反而**真的**把总量调小了。
     *
     * 判据只用 native 的两个真值（每格 / 份数），Kotlin 侧不自己乘一遍：
     * 份数在"第 2 份 ctx 建失败"时会从 2 回落到 1，那时总量也跟着变，
     * 用 `SlotScheduler.total`（加载前判决值）就会算出 8192 而实际只有 4096。
     */
    fun effectiveCtxTotal(): Int {
        val per = nativeCtxSize()
        if (per <= 0) return 0
        return per * slotCount().coerceAtLeast(1)
    }

    /** 每格**请求**值（0 = 没加载 / 旧入口没传）。与 [effectiveCtxTotal] 的差值就是"设置没照做"。 */
    fun ctxRequestedPerSlot(): Int =
        runCatching { nativeCtxRequestedPerSlot() }.getOrDefault(0).coerceAtLeast(0)

    /**
     * 本次加载时**请求的 slot 数**（0 = 没加载 / 旧入口没传）。
     *
     * 与 [slotCount] 成对：那个是"开出几份"，这个是"我要几份"。两者不等时
     * `/health` 才能把「设置没生效」指出来 —— 而这件事以前只有一个数可读，
     * 于是用户那句「我设 2 格、实际 3 格」既无法证实也无法证伪（ISSUE #426）。
     *
     * 值来自 native 落账（`g_slots_want`），不是 `SlotScheduler.total`：后者是
     * 调度器的判决值，拿它去和它自己管出来的池子比恒等 —— 那就成了恒真的读数。
     *
     * ⚠ 出口必须真的读 native 的 `g_slots_want`（[nativeSlotsRequested]）——
     *   native 侧那个 JNI 导出早就写好了，缺的一直是这一层 Kotlin 出口，
     *   而 `HttpApi` 已经在调它：少这一层时**源码编不过**（`Unresolved reference`）。
     */
    fun slotsRequested(): Int =
        runCatching { nativeSlotsRequested() }.getOrDefault(0).coerceAtLeast(0)

    /**
     * 状态行用的一句话：`ctx 总量/每格`。
     *
     * 单 slot 时只说一个数（"ctx 8192"），多 slot 时两个都报 —— 这正是
     * "我填的 ctx 改了无效、被写死"那条反馈的症结：只报每格时用户以为自己填的
     * 数值没生效（其实它生效了，只是被按份切分），于是反过来把设置改小。
     */
    /**
     * **每格**的 ctx 容量（= native 的 `S.n_ctx`，本来就已是每格）。
     *
     * 存在的理由只有一个：`ContextBudget.capacity(总量, 路数)` 吃的是**总量**，
     * 而注入块的长度上限要的是**每格**。把 `contextSize()` 喂给 `capacity()` 就是
     * 又除了一次 —— 那是 §4 明令禁止的"按份切分切两遍"（真机 16000 → 4000）。
     * 给每格一个自己的名字，调用点就不可能喂错。
     */
    fun ctxPerSlot(): Int = nativeCtxSize().coerceAtLeast(0)

    /**
     * **这一条 seq** 能排到的位置数（= native 的 `seq_ctx_limit`；0 = 没量到）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么它要单独成一个出口，而不是直接拿 [ctxPerSlot]
     * ══════════════════════════════════════════════════════════════════════
     * 库里「这一条 seq 能排到第几个位置」是 `n_ctx_seq = n_ctx / n_seq_max`，
     * 而 `n_ctx` 是这一格**所有 seq 加起来**的总量。往一格里塞第二条 seq 就等于
     * 把每格的位置白砍一半（真机：`ctx 8192 / 2 格 -> 每格 4096 -> 每条 2048`，
     * 用户按 4096 压 Max Context，prefill 第二批从位置号 2048 起步就越界，
     * 库当场拒绝 `decode rc!=0 at offset 2048`）。
     *
     * 本仓库现在的口径是**一格一条 seq**（native `paths_of` 恒 1，两路的分工由
     * **格**完成）—— 于是这个数与 [ctxPerSlot] 相等，预算侧传不传它是同一个结果。
     * 但判据仍然按它写：哪天有人再往一格里塞第二条，这里不会静默退化成
     * "按整格放行"（那正是一次次 `decode rc!=0` 的来源），而 `/health` 的
     * `seq_ctx` 与 `ctx` 一并列出会让那件事立刻可见。
     *
     * ⚠ 值的来源是 native 落账的 `kv_last_seq_ctx`（预算**那一刻**按哪条线判的），
     *   不是这里拿 `ctxPerSlot / n_seq_max` 再除一遍 —— 库对每条 seq 的容量做过对齐，
     *   除出来的数与它可能不等，而除大了就是上面那一行 `decode rc!=0`。
     *   `<= 0`（没加载 / 没跑过）由调用方退回整格口径。
     *
     * ⚠ 账本作废（换模型 / 卸载 / 显式丢弃 / 上一轮 abort）之后这一格必须报 0
     *   （ISSUE #452 真机）：它记的是"上一轮按哪条线判的"，账本都没了就没有
     *   "上一轮"可言。以前 `kv_invalidate` 漏清它，于是真机出现
     *   `ctx: 4096` 配 `seq_ctx: 2` —— 一个**从未被写过**的初值（2），
     *   读的人只能怀疑这一格被切成了两条 seq（而那件事根本没发生）。
     *   `kv_last_gen_cap` / `kv_last_ctx` 不跟着作废是另一条纪律（它们是**事实**），
     *   这一格不是：它是"那次预算的落值"，属于账本。
     */
    fun seqCtxPerSeq(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[LEDGER_LAST_SEQ_CTX] }.getOrDefault(0).coerceAtLeast(0)

    fun ctxSummary(): String {
        val per = ctxPerSlot()
        if (per <= 0) return "ctx=?"
        val total = effectiveCtxTotal()
        return if (total == per) "ctx=$total" else "ctx=$total（每格 $per × ${slotCount()} 路）"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  并发 slot 调度（Issue #330 阶段 4）：生成侧一律按 slot 取
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 阶段 3 把 native 的 ctx 拆成了「每 slot 一份」并给出了两套 JNI 声明：
    // 带 slot 的（`*Slot`）与旧的无 slot 的（等价 slot 0）。**阶段 4 接线**在这里，
    // 口径与设计文档 §9.2.7 / §9.2.10 一致：
    //   · 生成路径一律走带 slot 的声明，`slot` 走**形参**（不是全局"当前 slot"）——
    //     全局态在多 slot 下就是"某一路读到别人的数"的入口；
    //   · 旧声明一个都不删（native 侧还在，Kotlin 侧也保留 `external`），
    //     但**不再有新的调用点**：它们的存在只为"阶段 3 落地期间的兼容"。
    //   · 旧的无 slot 读数（[contextUsed] / [kvCacheValid] / [lastReuseTokens] …）
    //     语义保留 = **slot 0**（§9.2.10「只增不改」）：改旧字段语义会让今天的
    //     客户端与探针在升级那一刻读到错的东西。

    /** 旧的无 slot API 的语义归属（阶段 2/3 的 `LEGACY_SLOT`，同名同值）。 */
    const val LEGACY_SLOT = 0

    /** 这一路是谁（只进读数与复用门槛，不参与任何判据）。0 = 没报。 */
    const val WRITER_NONE = 0
    /** App 内聊天这一路。 */
    const val WRITER_APP = 1
    /** HTTP 这一路（`/v1/chat/completions`、`/v1/completions`）。 */
    const val WRITER_HTTP = 2

    /**
     * 生成路径日志的 slot 前缀；**只有"这一格确实要分"时才返回空串之外的形态**
     * （设计文档 §8.1，判据在 [slotTagFor]）。
     *
     * 为什么会有"不分"这一档：单 slot 时加前缀等于把今天全部生成日志的行文改一遍，
     * 既有锚字符串的守卫全要重认 —— 那是白得的破坏面，而且会让
     * 「slot=1 行为与今天逐字节一致」这条在日志上不再成立。
     *
     * ⚠ 但"要不要分"不能只看 native 的格数（ISSUE #403 第三次现场）。真机上 App 与
     * HTTP 全程都落在 slot 0，用户看到的是**任何时候都没有 `[sN]`**，而"两路共用一格"
     * 正是他最需要看出来的那件事。判据因此改成 [slotTagFor] 那一条：格数 > 1，
     * **或**日志行带 `shared` 标记（= 这一格是两路共用的）。
     */
    fun slotTag(slot: Int): String = if (slotTagFor(slotCount(), sharedSlot = false)) "[s$slot] " else ""

    /**
     * 「这一行要不要带 `[sN]`」的**唯一判据**（纯函数，宿主可单测）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不能只按 native 格数判（ISSUE #403 第三次现场）
     * ══════════════════════════════════════════════════════════════════════
     * 原来的判据是 `nativeSlotCount() > 1`。而单格池（默认档，也是这位用户的那一档）
     * 里 HTTP 与 App 内聊天**共用 slot 0** —— 于是「哪一格」这个问题在日志上
     * 一个标记都没有，用户的原话是「任何时候，日志都没有 s0/s1」。
     * 他真正要问的不是"一共开了几格"，而是"**这一行是哪一路写的**"。
     *
     * 所以判据放宽一条：**这一格是两路共用的**时候也打前缀，并且由 [slotTagLabel]
     * 在括号里点明共用 —— 只打 `[s0]` 而不说共用，读者会以为另一路在别的格上。
     *
     * 单格、独占（今天最常见的形态）仍然**一个字都不加**：那条"日志行文逐字不变"
     * 的约束是 §8.1 的承诺，放宽它换不来任何信息。
     *
     * @param slotCount native 的格数（[slotCount]）
     * @param sharedSlot 这一格此刻是不是两路共用（[SlotScheduler.writerOf] 报得出来）
     */
    fun slotTagFor(slotCount: Int, sharedSlot: Boolean): Boolean = slotCount > 1 || sharedSlot

    /** 前缀的**正文**（不含尾空格）：多格时 `[sN]`，单格共用时 `[s0 共用]`。 */
    fun slotTagLabel(slot: Int, slotCount: Int, sharedSlot: Boolean): String = when {
        slotCount > 1 -> "[s$slot]"
        sharedSlot -> "[s$slot 共用]"
        else -> ""
    }

    /**
     * 日志行用的前缀（含尾空格）：受本轮请求头 `X-Log-Tag: 1` 控制
     * （`/health` 的 `log_tag` 说得清"为什么我不打、它打"）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么要有这个开关（ISSUE #403 第三次现场）
     * ══════════════════════════════════════════════════════════════════════
     * 用户在 App 自带聊天 + 外部客户端两路上都看不到 `[sN]`，而这两路的日志都走
     * **单格占用**那条老路（§8.1：单格独占时一个字都不加）。服务端没法自己分辨
     * "这一路是 App 还是外部客户端 —— 用户是不是正在比对两边的日志"。
     *
     * 所以把"要不要分"交出去一格：请求头带 `X-Log-Tag: 1` 的那一轮，服务端**无声**
     * 加成 `[sN]`；不带的一律与改动前逐字相同（既有锚字符串的守卫一条都不用改）。
     *
     * ⚠ 只加"哪一格"这一件事，不改行文 —— 加完仍然可以按老锚字符串 grep，
     *   因为老锚字符串都在前缀**之后**。
     */
    fun slotTagLine(slot: Int, requestedLogTag: Boolean): String {
        val n = slotCount()
        // 这一格是不是两路共用：**只看这一格**（`sharedCount > 0` 那种"别的格在共用"
        // 不算 —— 那是别人的行，别给这一行加上误导性的标记）。
        val shared = n <= 1 && SlotScheduler.sharedAt(slot)
        val label = slotTagLabel(slot, n, shared)
        if (label.isEmpty()) {
            // 单格独占：只有调用方明确要了才加（这是"我这一格就是我这一路"的那一档）。
            return if (requestedLogTag) "[s$slot] " else ""
        }
        return "$label "
    }

    /** 本次加载开出的 slot 数（native 真值；未加载时为 1）。 */
    fun slotCount(): Int = runCatching { nativeSlotCount() }.getOrDefault(1)

    /** 按 slot 读已入 KV 的 token 数（`/health` 的 `ctx_used` 仍取 slot 0，见 §9.2.10）。 */
    fun contextUsed(slot: Int): Int = runCatching { nativeCtxUsedSlot(slot) }.getOrDefault(0)

    /** 按 slot 读上一轮复用的 token 数。 */
    fun lastReuseTokensOf(slot: Int): Int =
        runCatching { nativeKvCacheStatsSlot(slot)[0] }.getOrDefault(0)

    /** 按 slot 读上一轮新算的 token 数。 */
    fun lastPrefillTokensOf(slot: Int): Int =
        runCatching { nativeKvCacheStatsSlot(slot)[1] }.getOrDefault(0)

    /** 按 slot 读上一轮的公共前缀 token 数。 */
    fun lastLcpTokensOf(slot: Int): Int =
        runCatching { nativeKvCacheStatsSlot(slot)[3] }.getOrDefault(0)

    /**
     * 按 slot 读**已跑完 prefill 的轮次数**。
     *
     * 少这一个取值函数时，`kv_slots[i].rounds` 是拿 `nativeKvCacheStatsSlot(i)[2]`
     * 直接取出来的，而 `[2]` 是 `kv_rounds`、`[3]` 是 `last_lcp` —— 谁写的都"看着对"。
     * 真机的 `rounds:901` 正是那一行的现场：901 枚公共前缀被读成了 901 轮。
     * 名字钉在这里之后，下标只在**一个**函数里出现。
     */
    fun kvRoundsOf(slot: Int): Int =
        runCatching { KvSnapshot.roundsOf(nativeKvCacheStatsSlot(slot)) }.getOrDefault(0)

    /** 按 slot 读本轮已进 KV 账本的生成 token 数（取消收尾时与"已下发的步数"对账）。 */
    fun roundLedgerTokensOf(slot: Int): Int =
        runCatching { nativeRoundLedgerTokensSlot(slot) }.getOrDefault(0)

    /**
     * 「这一轮的账本别作废」（ISSUE #414，用户拍板的第 2 档）。
     *
     * 在此之前，`nativeAbort` 一置位就把整本账作废，而 `finish_reason=length`
     * 本质上也是一次 abort（生成被位置上限 / `n_rem` 拦下）—— 于是**上下文满了**、
     * 这一种最该被救的场面，反而和用户按停止一样：下一轮全量重算几千枚 prompt。
     *
     * native 侧现在改成"只截尾巴"（`kvprefix::truncate_ledger`），这里补的是那一笔
     * 执行时机：`length` 收尾发生在"取值之前"，那一拍没有任何 token 进 KV、
     * `n_used` 与账本本来就是对得上的。此时若已经有人按了停止，走 `abort_impl`
     * 那条路即可（它会在同一个位置按实况截）—— 两个都做一遍结果一样，
     * 但日志会说两句互相矛盾的话，所以调用方按 `cancel.requested` 择一。
     */
    fun keepKvLedger(slot: Int) {
        runCatching { nativeKeepKvLedger(slot) }
    }

    /**
     * KV 前缀复用的可观测面：`last_reuse`（上一轮复用了多少 token）与
     * `lastPrefillTokens`（上一轮**新算**了多少 token）。
     *
     * 为什么必须暴露到 Kotlin：复用是**纯加速**，它不改变任何接口状态 ——
     * 没有这两个数，"缓存到底有没有生效"就只能去读 native 日志，
     * 而 native 日志默认是关的（探针要人先开）。收到 /health 里之后，
     * 任何一次真实请求都能顺带把缓存命中率读出来。
     */
    val lastReuseTokens: Int get() = runCatching { nativeKvCacheStats()[0] }.getOrDefault(0)
    val lastPrefillTokens: Int get() = runCatching { nativeKvCacheStats()[1] }.getOrDefault(0)

    /**
     * 上一轮的**逐 token 最长公共前缀**长度（不设门槛）。与 [lastReuseTokens]
     * **必须成对读** —— 这是第 5 步给 KV 复用加的可观测面。
     *
     * 只有 `reuse` 时，"没命中"是个笼统结论，它至少有两种成因、处置相反：
     *   · `lastLcpTokens` 很大（如 300）却没命中 -> 是**门槛/容量**拦下的
     *     （prompt 已达 n_ctx，或 `total-1 < 16`），属于"差一点"；
     *   · `lastLcpTokens` 很小（如 2）-> 两轮 prompt **真的不一样** ——
     *     开了知识库时检索结果每轮都变，分叉点就落在最前面的 system 段里，
     *     这是**注入位置**的问题（第 4 步），调参没用。
     * 只报 `reuse=0` 时这两种在日志与 /health 上逐字相同，用户只能猜。
     */
    val lastLcpTokens: Int get() = runCatching { nativeKvCacheStats()[3] }.getOrDefault(0)

    /**
     * 已经跑完 prefill 的轮次数。与 [kvCacheValid] **必须成对读**：
     * `kvRounds == 0` = 从未跑过任何请求（冷启动）；
     * `kvRounds > 0 && !kvCacheValid` = 跑过、但账本已被作废（abort / 换模型 /
     * prompt 超长）。只看 [kvCacheValid] 的话这两种处境完全同形 ——
     * 一个是正常的冷启动，一个是取消或故障，读 `/health` 的人无从分辨。
     */
    val kvRounds: Int get() = runCatching { nativeKvCacheStats()[2] }.getOrDefault(0)

    /**
     * 本轮已进 KV 账本的生成 token 数（见 [nativeRoundLedgerTokens]）。
     * 供生成循环在取消收尾时记一行"账本里有 X、下发了 Y"，不要用它做判定。
     */
    val roundLedgerTokens: Int get() = runCatching { nativeRoundLedgerTokens() }.getOrDefault(0)

    /**
     * KV 账本是否有效。false = **下一轮必然全量 prefill**（换模型 / 卸载 /
     * 上一轮解码失败 / prompt 超长之后都是这个状态）。
     */
    val kvCacheValid: Boolean get() = runCatching { nativeKvCacheValid() }.getOrDefault(false)

    // ── /health 的按 slot 账本读数（Issue #330 收口）──────────────────────────
    // HttpApi 那份 `kv_slot` / `kv_slots` 从这里取数（`c747b61` 只接了 HttpApi 一侧，
    // 这三个函数当时漏了，main 因此编不过）。名字是守卫与文档已经钉死的那三个，
    // 别就地改：`tools/run_kv_cache_guard.sh` / `tools/slot_http/check_http_slot_wiring.py`
    // 逐字认它们。

    /** 顶层那五个 `kv_*` 字段读的是哪一格（= [LEGACY_SLOT]，§9.2.10「只增不改」）。 */
    fun kvStatsLegacySlot(): Int = LEGACY_SLOT

    /**
     * `/health` 顶层那五个 `kv_*` **实际**读的是哪一格（ISSUE #452 真机）。
     *
     * 与 [kvStatsLegacySlot]（恒 0，语义归属）分工：那个回答"这几个字段按设计属于谁"，
     * 这个回答"它们这一屏上的数来自哪一格"。两者曾经恒等，于是只用 HTTP（请求落在
     * slot 1）时探活报出 `slot_in_use: 0 / kv_rounds: 0`，而同一份响应里
     * `kv_slots[1].rounds: 6` —— 自相矛盾的那一屏，读的人只能得出"缓存一次没生效"。
     *
     * 判据：**最近一轮真跑过 prefill 的那一格**（`rounds` 最大；并列取下标小的）。
     * 一格都没跑过（冷启动）退回 [LEGACY_SLOT] —— 那时谁是 0 都一样，
     * 而"退回 0"与"读过 0"在读数上必须同形（否则冷启动那一屏又是两个数字打架）。
     *
     * ⚠ 这里只挑"读哪一格"，不参与任何调度判据：它读的是账本快照，两次探活之间不会变。
     */
    fun kvStatsLiveSlot(): Int =
        KvSnapshot.liveSlot(
            runCatching { kvStatsAllSlots() }.getOrNull() ?: emptyArray(),
            LEGACY_SLOT)

    /**
     * 每格的账本快照，逐格走带 slot 的 native 声明：
     * `int[5] = {used, reuse, prefill, rounds, lcp}`（`/health` 的 `kv_slots[i]`）。
     *
     * 格数取 [slotCount] 的 native 真值，不是写死的常量 —— 写死就变成
     * "加载了几路都只报第一路"，而那不报错。
     */
    fun kvStatsAllSlots(): Array<IntArray> =
        Array(slotCount()) { i ->
            intArrayOf(
                contextUsed(i),          // 0: used    —— 这一格 KV 里装了多少 token
                lastReuseTokensOf(i),    // 1: reuse   —— 上一轮复用了多少
                lastPrefillTokensOf(i),  // 2: prefill —— 上一轮新算了多少
                kvRoundsOf(i),           // 3: rounds  —— 跑完 prefill 的轮次（**不是** lcp）
                lastLcpTokensOf(i),      // 4: lcp     —— 逐 token 最长公共前缀
            )
        }

    /** 按 slot 读账本有效性（走带 slot 的 native 声明，不退化到 slot 0）。 */
    fun kvCacheValidOf(slot: Int): Boolean =
        runCatching { nativeKvCacheValidSlot(slot) }.getOrDefault(false)

    // ── 归属三格 + 上下文掐断标志（ISSUE #330）──────────────────────────────
    //
    // 这两个东西都只**透传**，不推导：判据在 native，宿主侧另算一遍必然漂移
    // （"reuse 说命中、归属说不是"），而那种矛盾在 /health 上看起来完全正常。

    /**
     * 上一轮的归属读数：`int[8] = {reuse_owner, reserved, last_gen_cap, last_ctx,
     * used_self, round_used_self, writer, seq_left}`。
     *
     * ⚠ 第 2 格**已废弃**（ISSUE #330 去重）：它原本是 `lcp_reason`，四档里有两档
     * 与已有读数逐轮等价（`DroppedByUs` = `prompt_trimmed > 0`、`DroppedByPeer`
     * = 未命中），`Blocked` 与"未命中"只差一个常量门槛。同真同假的数放在一起，
     * 读的人一定会挑那个最像结论的 —— 而它在真机上往往是错的那一格。
     * 现在这一格恒 0，只为不改数组长度（`/health` 的展开下标不用跟着挪）。
     *
     * 后两个（`last_gen_cap` / `last_ctx`）保留：它们是"被掐断"与"被裁掉"的
     * 唯一原始依据 —— 客户端拿着 `finish_reason=length` 回来问"为什么"，
     * 这两个数能当场回答"是我只给你留了 128 个位置"，而不是靠复现。
     *
     * 末格 `seq_left` 是**本路那条 seq 还剩多少位置**（容量取库的真值
     * `llama_n_ctx_seq`，已用取本路实况）。它与 `used_self` 成对：那一格答"我用了多少"，
     * 这一格答"我还能用多少"。两路共用一个 slot 时这一份 ctx 被切成两条 seq，
     * 于是这两个数都**不是** `ctx_size` 能回答的 —— 让读的人自己去 `ctx_size / 2`
     * 减一遍，算出来的数一旦与 native 的判据分叉，症状只是某轮突然 400。
     * `< 0` = 两路已经挤爆这一格（ISSUE #426：此时只在 App 或 HTTP 一侧用）。
     *
     * 末三格是「一格里 App 与 HTTP 各一条 seq」之后才有的读数：
     *   · `used_self`       —— 本路那条 seq **此刻**用到哪儿（本轮在涨）；
     *   · `round_used_self` —— 本路那条 seq **上一轮收尾**时用到哪儿（定值）；
     *   · `writer`          —— 上一轮是哪一路在这个槽位上跑的（0 未知 / 1 App / 2 HTTP）。
     *
     * 为什么非要它们：`used` 报的是**整格**（`n_used`）。两路都在用时它会超过
     * `ctx_size` —— 那是真事（另一路也占着位置），但只有它时用户问的那句
     * 「我这一路还剩多少余量」没有任何数能回答，读起来就是"装爆了"。
     * 两个 take 分开报：混成一格时"本轮用了多少"与"上一轮用了多少"共用一个答案。
     *
     * 末格 `last_seq_ctx`（ISSUE #426 真机 `decode rc!=0 at offset 2048`）：
     * 倒二格是「本路那条 seq 还剩多少位置」（`seq_left`，与 `used_self` 成对：
     * 一个答"我用掉多少"、一个答"我还能用多少"；`< 0` = 这一格被两路挤爆）。
     * 上一轮预算按**哪条线**判的（`last_seq_ctx`）不在这里 —— 它在**账本**末格
     * （[LEDGER_LAST_SEQ_CTX]），与 `last_ctx` 并排读：两者不等 = 这一格的容量被
     * seq 切过，而"预算说装得下、prefill 说位置号越界"只有这两个数摆在一起才解得开。
     */
    fun kvAttributionOf(slot: Int): IntArray =
        runCatching { nativeKvAttributionSlot(slot) }.getOrDefault(IntArray(8))

    /** 账本第 30 格：上一轮预算**按哪条线**判的（这一条 seq 的容量 `last_seq_ctx`）。 */
    const val LEDGER_LAST_SEQ_CTX = 29

    /**
     * 账本第 47 格：**上一轮是谁在这个格子里写的**（0 未知 / 1 App / 2 HTTP，`kv_valid` 假时恒 0）。
     *
     * 与 [kvWriterOf]（第 26 格）是同一件事，差别只有一条、而它正是要害：
     * **这一格跟着账本一起作废**。账本没了就没有"上一轮"可言，而第 26 格是
     * "是谁写进来的"这一**事实**，不随缓存失效 —— 拿它判"这个格子上一轮是不是我跑过"
     * 会在清缓存之后的第一轮给出一个**看起来有依据**的答案。
     *
     * 它唯一的用处：`trimTagOf` 判「**这条会话**上一轮真的丢过」时，先答应不该
     * 拿这一格的起点 —— 起点是 slot 的，不是会话的（见 `EngineActivity.trimTagOf`）。
     */
    fun kvLedgerWriterOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[LEDGER_LEDGER_WRITER] }.getOrDefault(0)

    /** 账本第 47 格的下标（`0` 未知 / `1` App / `2` HTTP）。 */
    const val LEDGER_LEDGER_WRITER = 46

    /**
     * 上一轮账本的**构成**与**断点**：`int[47] = {prompt 段, 生成段, 总长, 断点,
     * 上轮长, 本轮长, prompt_trimmed, gen_trimmed, head_same, head_cmp, fork_in_head,
     * fork_prev, start, start_prev, prompt_real, prompt_est, inject_chars,
     * decision, align_reject}`。
     *
     * 后四格是「起点有没有漂」与「估算 vs 真 token」两个跨轮读数：
     * `start` / `start_prev` 是相邻两轮的**新算起点**（各自单看都正常，一减才知道
     * 起点在往后挪 —— 那才是"复用不会自己恢复"的那一类）；`prompt_real` / `prompt_est`
     * 是 native 真 token 数与客户端估算值（差值 = 窄带宽度）。
     *
     * 最后一格 `fork_prev` 是分叉那一枚**在上一轮账本里的下标**（-1 = 无分叉）。
     * 少了它，落点只能拿"本轮 prompt 长度"倒推，于是"上一轮的历史本轮没被带回来"
     * 会被读成"有东西每轮在变"——方向正好相反（真机 152/661/1371 那场）。
     *
     * 后三格是"日志里那句结论能不能成立"的依据：`fork_why` 的
     * 「落在上一轮回复段开头 —— 回复没被带回来」只有在**断点之前逐 token 相等**
     * 时才成立（见 native 侧 `Session::kv_head` 的注释）。
     *
     * 与 [kvAttributionOf] 分工：那边回答"**是谁**动的刀"（我们 / 对端 / 门槛），
     * 这边回答"**刀落在第几个 token**"。真机上"复用只剩一百多"这类现象，
     * 只靠前者会被读成"对端裁短了"，而实际常常是"上一轮那段回复没被带回来"——
     * 两者在日志上长得一样，处置却完全相反。
     */
    fun kvLedgerOf(slot: Int): IntArray =
        runCatching { nativeKvLedgerSlot(slot) }.getOrDefault(IntArray(47))

    /**
     * 上一轮起点的 **token 坐标**：`int[3] = {tok_start, tok_end, dropped_tok}`（ISSUE #452）。
     *
     * 它与 [kvReuseGeomOf] 的 `start` 是**两个坐标系**：那个是"丢头之后"的位置号
     * （本轮这一次 prefill 看到的那一份），这里是**整段 prompt**（丢头之前）里的下标。
     * 中间层要把客户端原文的字符偏移换算成 token 位置，而那条换算只在整段 prompt 上
     * 成立 —— 拿丢头之后的数去切，切得齐，位置却少了一截（正好差 `dropped_tok`）。
     *
     * `tok_end <= 0` 就是"这一轮没有坐标"：中间层据此**不切**（原样直发），
     * 与"切齐了"必须分得开 —— 猜一个位置去删用户的对话，是这一层唯一不可接受的坏法。
     */
    fun turnOriginOf(slot: Int): IntArray =
        runCatching { nativeKvLedgerSlot(slot).let { intArrayOf(it[39], it[40], it[41]) } }
            .getOrDefault(intArrayOf(0, 0, 0))

    /**
     * 复用的**几何**四格：`int[4] = {lcp, reuse, start, ledger}`（ISSUE #452 第 0 步）。
     *
     * 这四个数 native 早就算好了（`plan.lcp` / `plan.reuse` / `kv_last_start` /
     * `last_tokens.size()`），但此前只出现在 `/health` 与日志里 —— 而**裁剪方**
     * （客户端 / 中间层）唯一读得到的是响应体，那里一个都没有。于是"这一轮复用了多少 /
     * 起点在哪 / 公共前缀多长"只能翻服务端日志才看得见，裁剪只能猜。
     *
     * 三个数成对读才有意义：
     *   · `lcp`    —— 本轮与上一轮的逐 token 公共前缀（0 = 一枚都不相同）；
     *   · `reuse`  —— 真正沿用下来的枚数（0 = 没命中）；
     *   · `start`  —— 本轮新算的起点（0 = 从头）。`reuse + start` 在通常那一档
     *     等于 `ledger`，而 ⑦（跳过起点复用）那一档不等 —— 那正是"起点被跳过了"
     *     唯一读得出来的地方；拿 `reuse` 当起点就会把两千多枚报成 152。
     *   · `ledger` —— 本轮 prefill 成功后账本有多长（0 = 还没写账本 / 失败）。
     *
     * ⚠ 只透传，不在这里重算：判据在 native 的 `plan_reuse`，宿主另算一份必然漂移，
     *   而那种漂移的表现是"响应说从 170 起、/health 说 168"，两个数各自都合法。
     */
    fun kvReuseGeomOf(slot: Int): IntArray =
        runCatching { nativeKvLedgerSlot(slot).let { intArrayOf(it[35], it[36], it[37], it[38]) } }
            .getOrDefault(intArrayOf(0, 0, 0, 0))

    /**
     * 断点那两枚 token 与「上一轮被跳过的那段在本轮还找得到几枚」（ISSUE #452）。
     *
     * `int[4] = {fork_prev_tok, fork_cur_tok, elided_len, head_ledger_len}` ——
     * 账本第 32~35 格。四格**不参与任何判据**，只回答「为什么每轮都只接上开头 300 枚」：
     *   · `fork_prev_tok` / `fork_cur_tok`（-1 = 那一侧没有这一枚）—— 断点上那两枚的 token id
     *     并排，**内容变了**与**这一轮短了**当场分得开；
     *   · `elided_len`（-1 = 没法量）—— 上一轮 `[0, lcp)` 那段在本轮还找得到几枚：
     *     等于 `lcp` = 那一段照旧在、断点在它之后；小于 `lcp` = 上一轮那段**被换掉了**；
     *   · `head_ledger_len`（0 = 上一轮没存）—— 上一轮存的开头有多长，与 `head_cmp` 成对读：
     *     `head_cmp < head_ledger_len` = 本轮 prompt 更短，上一轮存的开头没比完，
     *     那时「断点在更后面」这句话不成立。
     *
     * ⚠ 别拿 `fork_prev_tok != fork_cur_tok` 单独下结论：两个 id 不同只说明"这一枚不同"，
     *   说不出它是因为内容变了、还是因为这一轮少了前面一段（`elided_len` 才是那个答案）。
     */
    fun kvForkPeekOf(slot: Int): IntArray =
        runCatching { nativeKvLedgerSlot(slot).let { intArrayOf(it[31], it[32], it[33], it[34]) } }
            .getOrDefault(intArrayOf(-1, -1, -1, 0))

    /**
     * 断点两侧的**几何**读数：`int[4] = {extra_first, extra_len, extra_first_prev, prev_head_in_cur}`。
     *
     * 前面那些格只回答"断在第几枚"，回答不了用户真正在问的那一句
     * 「它明明可以复用，为什么断在 152」—— 因为**没说是谁的字节变了**。
     * 于是每一次排查都走向服务端的参数（调 ctx、调 max_tokens、调 parallelN），越调越不像。
     *
     * 四格都是**下标**，没有一格是推断：
     *   · `extra_first` / `extra_len` —— 本轮 prompt 里夹带的额外内容（知识库注入块等）
     *     落在 token `[first, first+len)`；`first=-1` = 本轮没夹带；
     *   · `extra_first_prev` —— 同一段内容在**上一轮** prompt 里的起点（-1 = 上一轮没有）；
     *   · `prev_head_in_cur` —— 上一轮 prompt 的**开头这一段**在本轮 prompt 里的首次出现
     *     下标（-1 = 一处都找不到）。它把「上一轮的历史被挪到了后面」与
     *     「上一轮那段历史本轮根本没被带回来」分开 —— 两者的处置完全相反；
     *   · `prev_prefix_in_cur` —— 上一轮**整段**账本在本轮逐枚相同几枚（-1 = 没法量）。
     *     上一格在"本轮是上一轮的前缀"（丢过历史之后的常态）时必然报 -1，
     *     只看它就会把"我们自己扔了开头"判成"两轮喂的不是同一份对话"（ISSUE #503）。
     *
     * 判据本体在 `kv_diff_where.h`（宿主可编、可单测），native 与日志共用同一份；
     * 这里只取数，不在 Kotlin 侧重算（重算就会出现"日志说在夹带段、/health 说在历史段"）。
     */
    /**
     * 上一轮**是谁**写进这一格的 KV 账本（0 未知 / 1 App / 2 HTTP）。
     *
     * 单格池里 App 与 HTTP 轮替占同一格时，换路那一轮逐 token 公共前缀只到
     * system 段就断（两路的 prompt 构造不同）—— 于是"每轮都整段重算"是数学，
     * 不是缓存坏了。这一格把它变成可读的数：`writer` 与上一轮不同 = 换了路。
     */
    fun kvWriterOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[25] }.getOrDefault(0)

    fun kvGeometryOf(slot: Int): IntArray =
        runCatching {
            nativeKvLedgerSlot(slot).let { intArrayOf(it[19], it[20], it[21], it[22], it[42]) }
        }.getOrDefault(intArrayOf(-1, 0, -1, -1, -1))

    /**
     * 上一轮那**整段**账本在本轮逐枚相同几枚（-1 = 没法量；`/health` 的
     * `prev_prefix_in_cur`，账本下标 42 = 第 43 格）。
     *
     * 与 `diff_prev_head_at` 成对读，两者在"本轮是上一轮的前缀"时**必然分叉**：
     * 后者找的是"上一轮开头那 ≤512 枚在本轮的首次出现"，而丢过历史之后那 512 枚
     * 在本轮一枚也放不下 —— 它报 -1，`fork_why` 就写成「两轮喂进去的不是同一份对话」。
     * 这一格等于本轮 prompt 长度时，「本轮整段都在上一轮里」是**可验证的事实**：
     * 变的是我们喂进去的起点，不是对端换的历史（两者处置相反）。
     */
    fun kvPrevPrefixInCurOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[42] }.getOrDefault(-1)

    /**
     * 那一次比对**真的比过**上一轮账本开头的多少枚（账本第 44 格）。
     *
     * 与 `head_cmp` 分工：`head_cmp` 是"上一轮开头那 ≤512 枚在本轮找得到几次"的**步数**
     * —— 上一轮开头整段还在本轮里时，第一次尝试就命中、循环当场 break，于是它等于
     * **首次出现的位置**（真机：473）。拿它当"比过多少内容"就会把"整段都比过了"
     * 读成"只比了前 473 枚"，结论正好反过来（ISSUE #503 真机第二轮）。
     */
    fun kvPrevHeadSpanOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[43] }.getOrDefault(0)

    /**
     * 断点的**种类**（账本第 46 格）：`0` = 内容在中间断掉（常态），
     * `1` = 本轮整段都是上一轮账本的前缀（断点在本轮末尾，不是内容变了）。
     *
     * 与 [kvLedgerOf] 的第 4 格（断点下标）成对读：只读那一格时，"断在本轮末尾"
     * 与"断在中间"可能同值（都等于本轮长度），而处置相反 —— 前者本来就该复用
     * `prompt_real - 1` 枚，后者要查模板与注入（ISSUE #503 真机第一屏：
     * `fork_at -1 / reuse 0`，日志却说「没命中不是前缀对不上」）。
     */
    fun kvForkKindOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[45] }.getOrDefault(0)

    /**
     * 本轮从第几枚起喂（账本第 45 格，**整段 prompt** 的坐标系 = 丢头之前）。
     *
     * 只在与 `start_prev` 并排时才读得出东西：两者相等就是"接上了上一轮"，不等就是
     * "起点又挪了一截"。单看任何一个都正常 —— 而后者正是「水位线之后每轮全量
     * prefill」那条自锁。
     */
    fun kvTokStartOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[44] }.getOrDefault(0)

    /**
     * 「这一轮接上了没有 / 想接而让掉了多少」—— `int[2] = {decision, align_reject}`。
     *
     * `decision`：`0` 没丢头 / `1` 起点接上上一轮（前缀可用）/ `2` 重新决定起点（接不上）。
     * `align_reject`：想接上、但额度不允许而让掉的 token 数（0 = 接上了 / 没想接）。
     *
     * ⚠ 这里**不回答"为什么没接上"**。曾经有一版（`rediscover` 五档）从
     * (起点, 上一轮起点, 丢头数, 开头是否同形) 里推成因，真机上那几档互相覆盖，
     * 它先后把「我们自己砍的」说成「对端的历史变了」、把「开头就变了」说成
     * 「回复没被带回来」—— 用户照着改了五轮客户端，一次也没对。
     * 读数能被数据直接回答，成因不能；不能的就别给它一个名字。
     */
    fun decisionOf(slot: Int): IntArray =
        runCatching { nativeKvLedgerSlot(slot).let { intArrayOf(it[17], it[18]) } }
            .getOrDefault(intArrayOf(0, 0))

    /**
     * 本轮注入块的**字符数**（0 = 本轮没注入）。`/health` 的 `inject_chars` 取它。
     *
     * 只透传（native 落账、这里取），不在 Kotlin 侧重算：两条路（HTTP / App）
     * 各自的块宽由各自的入口定，重算一份就会出现"报的宽度与实际拼进 prompt 的
     * 宽度不同"，而那种偏差没有任何现象 —— 只表现为"前缀莫名断在块头"。
     */
    fun injectCharsOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[16] }.getOrDefault(0)

    /**
     * 上一轮**丢完之后还剩多少余量**（0 = 尾巴顶在边界上；`/health` 的 `spare`）。
     *
     * 它是「为什么这一轮接上了、那一轮没接上」唯一能自证的那个数：
     *   · `spare > 0` -> 下一轮历史还能长这么多枚才需要再丢一次，起点可以不动；
     *   · `spare == 0` -> 这一轮把位置用到了最后一枚，**下一轮必然再丢一次**，
     *     起点每轮往后挪一步，`reuse` 每轮都有值但每轮都接不上。
     *
     * 真机（每格 4096 / max_tokens 1024 / 历史每轮涨约 300）上正是这一档：
     * `prompt_trimmed` 有值、`reuse` 有值、`start = start_prev + 300` —— 每一格都合法，
     * 而没有任何一个数说得出"它每轮都要重算"。判据本体在 `ctxbudget::spareOf`。
     */
    fun kvSpareOf(slot: Int): Int =
        runCatching { KvSnapshot.spareOf(nativeKvLedgerSlot(slot)) }.getOrDefault(0)

    /**
     * 水位三格：`int[3] = {watermark_pct, drop_room, drop_overshoot}`。
     *
     * ISSUE #426 方案 B。`kvSpareOf` 回答"下一轮还接不接得上"（量的是**整格**），
     * 这三格回答"它按什么在判 / 这一轮丢完离水位多远 / 多丢的到底换回了什么"：
     *   · `watermarkPct`   = 用户设的那一档（`/health` 的 `ctx_watermark`）；
     *   · `dropRoom`       = 丢完离水位还剩多少（0 = 下一轮历史再长就再丢一次）；
     *   · `dropOvershoot`  = 比"只丢缺口"多丢了多少（= 主动忘掉的记忆，换下一轮不丢）。
     *
     * ⚠ 这里原来报的是 `dropped_to_watermark`：它的判据（`trimmed > 0 && prompt+gen <= avail`）
     *   在能走到的地方恒成立，于是与 `prompt_trimmed` 共线 —— 透出去只会让人
     *   把"丢过"读成"按水位丢过"。已换成上面两格（各自能被数据直接回答）。
     */
    /**
     * 上一轮 prompt 的**真 token 数**（`prompt_real`，账本第 14 格；`<= 0` = 没量到）。
     *
     * 它是估算校准唯一的**分子**（ISSUE #452 ⑤）：宿主没有 tokenizer，所以"估算偏高
     * 多少"这件事只有 native 量得出来。写在预算**之前**（丢头之前），所以它量的是
     * "客户端发来的那一段"，与估算量的是同一段文本 —— 两个数不同源就没法相除。
     *
     * ⚠ 读的是**账本**那一格，不是 `nativeTakeCtxBudgetNumbersSlot` 那一对
     *   （那是"读即清"的失败归因，只在 400 时才有值）。这里要的是**每一轮**都有的数。
     */
    fun promptRealOf(slot: Int): Int =
        runCatching { nativeKvLedgerSlot(slot)[14] }.getOrDefault(0)

    fun kvWatermarkOf(slot: Int): IntArray =
        runCatching { KvSnapshot.watermarkOf(nativeKvLedgerSlot(slot)) }
            .getOrDefault(intArrayOf(-1, -1, -1))

    /**
     * 生成**为什么停**：`int[4] = {gen_planned, n_rem, stop_wall, pos_gap}`（见 native 声明）。
     *
     * 实际能生成 `min(n_rem, stop_wall)` 枚，三格谁最小谁就是那一轮断掉的原因：
     * · `gen_planned` 最小 —— 调用方自己只要这么点；
     * · `n_rem` 最小       —— 预算削过（看 `gen_cap` / `gen_trimmed`）；
     * · `stop_wall` 最小   —— 位置不够（`n_ctx - 2 - n_used`），与预算无关。
     *
     * 最后一档是 ISSUE #330 真机那条「请求 1024、实际给 99、KV 还空着 1618 格」：
     * 三件事同时为真，而只报 `gen_cap` 时它们是同一句话。
     *
     * 第四格 `pos_gap` = `spare - stop_wall`：`spare`/`stop_wall` 各自都合法，
     * 差额才回答"位置是不是被 prompt 吃掉了"（真机 `spare 490` / `stop_wall 1514`
     * 差出来的 1024 正是这一轮请求的生成长度 —— 位置没被偷走，别去调 ctx）。
     * 默认值给 `IntArray(4)`：长度对不上时取下标会越界，而这里要的是"没报 = -1"。
     */
    fun genStopOf(slot: Int): IntArray =
        runCatching { KvSnapshot.genStopOf(nativeGenStopSlot(slot)) }
            .getOrDefault(KvSnapshot.defaultGenStop())

    /** 上一轮分叉点的一句话（空串 = 没有分叉可说）。判据在 native，这里只透传。 */
    fun kvForkWhyOf(slot: Int): String =
        runCatching { nativeKvForkWhySlot(slot) }.getOrDefault("")

    /**
     * 本轮**有没有动过 prompt**（预算从头部丢掉的 token 数）。
     *
     * `> 0` 是我们自己砍断了 KV 前缀 —— 这种轮次之后复用必然掉；
     * `0` 则说明"复用没命中"跟我们无关（去看客户端带回了什么）。
     * 这一格就是「复用没了是不是我砍的」的分界线，不必再靠日志倒推。
     */
    fun promptTrimmedOf(slot: Int): Int =
        runCatching { nativePromptTrimmedSlot(slot) }.getOrDefault(0)

    /** 本轮是否因「账本与 KV 对不上」而降级（只降级、不作废账本）。 */
    fun ledgerMismatchOf(slot: Int): Boolean =
        runCatching { nativeLedgerMismatchSlot(slot) }.getOrDefault(false)

    /**
     * 上一次 `step()` 是**因为上下文/生成上限**而结束的吗。
     *
     * 它把 `step()` 返回 null 的五类成因里"被掐断"那一类单独拎出来 ——
     * 不拎的话，「模型说完了」与「上下文满了」在协议上完全同形（都报 stop）。
     */
    fun stepCtxExhausted(slot: Int): Boolean =
        runCatching { nativeStepCtxExhaustedSlot(slot) }.getOrDefault(false)

    /**
     * 上一轮 prefill 是否**因上下文装不下**而失败（读即清）。
     *
     * 存在的理由：`startCompletion` 失败时对调用方只有一个 nil，而它至少覆盖
     * 「prompt 装不下」与「decode 真出错」两件处置相反的事。此前那句
     * "prefill 失败（prompt 过长或 decode 出错）"把两个相反的猜测一起说出口，
     * 等于什么都没说。读即清，免得下一个请求背上上一次的成因。
     */
    fun takeCtxBudgetFailed(slot: Int): Boolean =
        runCatching { nativeTakeCtxBudgetFailedSlot(slot) }.getOrDefault(false)

    /**
     * 这一轮的 prefill 是**被取消**掉的（读即清）。
     *
     * 与 [takeCtxBudgetFailed] 是**两件事**，必须分开读：那一格答"装不下"（处置 = 调容量），
     * 这一格答"有人按了停止 / 客户端走了"（处置 = 什么都不用改，重发就行）。
     * 两件事都让 `startCompletion` 的 native 调用返回 false，于是它们在调用方那里
     * 曾经同形 —— 而 `handleChat` 那时把失败一律折成 **400**，客户端读到的是
     * 「prefill 失败（decode 出错，详见日志）」。真机上用户报的「我没点停止，
     * 它就 abort 了、也看不到显示」（ISSUE #450 第十四次现场）里那一句 400 就是
     * 这么来的：它是**取消**，不是故障，更不该被说成 decode 出错。
     *
     * 现在 `startCompletion` 把这一次读取的结论装进 [StartFailure.cancelled]，
     * HTTP 层据此回 499（取消）而不是 400 —— 本函数仍保持"读即清"，
     * 一次归因只属于那一轮。
     */
    fun takePrefillCancelled(slot: Int): Boolean =
        runCatching { nativeTakePrefillCancelledSlot(slot) }.getOrDefault(false)

    /**
     * 给**渲染后**的 prompt 原文数一遍 token（`-1` = 数不了：没加载模型 / 空串）。
     *
     * 与 [ContextBudget.promptTokensOf] 的区别就是它的全部意义：那个数的是"消息"，
     * 这个数的是**真会进 prefill 的那一段文本**（渲染 + 注入之后）。/health 上
     * 两格并排（`prompt_est` / `prompt_real`），差值才读得出"是我们高估白丢了历史"
     * 还是"我们低估、native 砍了一刀"—— 两件事的处置完全相反。
     */
    fun countPromptTokens(prompt: String): Int =
        runCatching { nativeCountPromptTokens(prompt) }.getOrDefault(-1)

    /**
     * 本词表的估算系数（`real / est`，×1；读不到 / 没加载 -> 1.0 = 不校准）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 这个数只有一个来处：native 每轮量到的真 token 数（ISSUE #452 ⑤）
     * ══════════════════════════════════════════════════════════════════════════
     * 宿主侧没有 tokenizer，所以估算只能是"按字符分类折算"，而它**必然**偏高一点
     * （真机 `prompt_real 2649 / prompt_est 4036`）。偏高本身不危险，但那一截是
     * 白丢的历史 —— 而 native 那边其实还装得下。
     *
     * 所以拿真值把估算**往下**校准一档（`ContextBudget.PromptCalibration`：只向下、
     * 有界 0.70~1.00（判据口径低到 0.55，见 `PROMPT_CALIB_JUDGE_MIN`）、样本不足不动、
     * 差不到 2% 不动）。判据里用它的唯一一处是 `ContextBudget.plan(estimateScale = …)`，
     * 那里按两个口径各读一次（报告 / 判据），**存的是同一个数**。
     *
     * ⚠ 键必须是**词表自己的**那一份（会话里换了模型 = 换了一份 tokenizer）：
     *   拿 A 的系数去量 B 的 prompt 不会报错，只会悄悄偏一截。
     * ⚠ `toX1000` / `fromX1000` 而不是直接存 Double：存盘与日志按同一个整数印，
     *   免得"日志 0.72、判据 0.716"这种两边都看着对的漂移。
     */
    fun promptEstScale(vocab: String): Double =
        ContextBudget.PromptCalibration.fromX1000(promptEstScaleX1000[vocab.trim()] ?: 0)

    /**
     * 用**这一轮**的真值/估算给系数喂一份样本（`real` / `est` 都来自同一次 `startCompletion`）。
     *
     * 三条纪律，缺一条就不该记（见 `ContextBudget.PromptCalibration`）：
     *   · `est` 必须是**这一轮判据真的用过的那个数**（已含当时的系数），否则乘自己；
     *   · `fresh` 是"连续新鲜样本数"——估算与真值差不到 1% 的那一轮**重置**它
     *     （那是常态，没有信息量），不累计也就不会拿日常的抖动去动判据；
     *   · 键不认识（换了模型 / 空串）就整份丢掉 —— 写错键的系数会跟着那个词表一路错下去。
     *
     * 两个方向各有一套计数器，**互不借道**：往下 3 份（`promptCalibFresh`），
     * 往上 6 份（`promptCalibRise`，见 [ContextBudget.PromptCalibration.rise]）。
     * 共用一个计数器是最容易写出的那个坏形态 —— "估算偏低"攒够 3 份之后，
     * 一旦来一份偏高的样本就会被同一次读数一起用掉。
     */
    fun observePromptReal(vocab: String, real: Int, est: Int) {
        val key = ContextBudget.PromptCalibration.keyOf(vocab.trim(), modelVocabKey(), embedVocabKey())
            ?: return
        if (!ContextBudget.PromptCalibration.acceptable(real, est)) return
        // ⚠ `est` 是**报告口径**（`prompt_est`，实测表），而系数要作用在**判据口径**上。
        //   两个口径的余量来源不同（见 `ContextBudget.PROMPT_CALIB_JUDGE_MIN`）：判据天生
        //   比报告高 `判据表 / 实测表` 那一截。所以先把样本折成"判据按照这个系数会估多少"，
        //   再比 —— 否则 `real / est` 一直在说"估算偏低"，而实际上判据只是**表**更保守，
        //   系数被这条比值按住不动，#488 的 B 档一个字都修不掉。
        val judgeEst = judgeSideOf(est)
        if (!ContextBudget.PromptCalibration.acceptable(real, judgeEst)) return
        val old = promptEstScale(key)
        if (ContextBudget.PromptCalibration.closeEnough(old, real, judgeEst)) {
            // 「两把尺子对上了」是常态：两个计数器都清 —— 否则日常的抖动攒够份数就会动判据。
            promptCalibFresh.remove(key)
            promptCalibRise.remove(key)
            return
        }
        val ratio = real.toDouble() / judgeEst.toDouble()
        // 「估算偏低」与「估算偏高」是两份证据，各自攒各自的；**另一侧一旦出现就清零** ——
        // 同向才算"连续"，否则三轮里夹一份反向的也能凑够数（那正是抖动被当成信号）。
        val low = ContextBudget.PromptCalibration.lowSide(old, real, judgeEst)
        // 往下的证据：只有 `!low` 那一侧才攒。另一侧一旦出现就清零（见上面那段）。
        val fresh = if (low) 0 else (promptCalibFresh[key] ?: 0) + 1
        // 往上的证据：只有 `low` 那一侧才攒。
        val freshUp = if (low) (promptCalibRise[key] ?: 0) + 1 else 0
        if (low) promptCalibRise[key] = freshUp else promptCalibFresh[key] = fresh
        // 反向样本把另一个计数器清掉 —— 少了这一行，两个方向就都在"跨侧累计"，
        // 而那份累计永远不会被谁消费掉（门槛按另一侧的份数判），症状是系数该动的时候不动。
        if (low) promptCalibFresh.remove(key) else promptCalibRise.remove(key)
        val next = ContextBudget.PromptCalibration.next(old, ratio, fresh, freshUp)
        if (next == old) return
        promptEstScaleX1000[key] = ContextBudget.PromptCalibration.toX1000(next)
        promptCalibFresh.remove(key)
        promptCalibRise.remove(key)
        // 必须留痕：判据被改过这件事**不能**只体现在"这一轮忽然少丢了几条"上 ——
        // 那是用户在真机上唯一看得见的东西，且它长得像"坏了"。
        // 两个方向分开说：往下是"估算原来偏高"，往上是"我们调低的那个折扣该收了"，
        // 而后者恰恰意味着**前几轮丢得多是校准自己造成的**（读日志的人要能对出因果）。
        uiLog("[ctx] 估算校准：真 $real / 估 $est tok —— 系数 " +
            "${ContextBudget.PromptCalibration.toX1000(old)} → " +
            "${ContextBudget.PromptCalibration.toX1000(next)} /1000" +
            (if (next > old) "（估算不再偏高，把折扣收回来；上界仍是 1.0）"
             else "（估算偏高，只向下调；不会因为低估而放宽）"))
    }

    /**
     * 把**报告口径**的估算折成「判据按照当前系数会估多少」—— 校准样本的分母。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么样本分母必须是判据口径（ISSUE #488 的 B 档）
     * ══════════════════════════════════════════════════════════════════════════
     * `prompt_est` 用**实测表**（0.62），判据用**保守表**（0.80）外加 `safetyOf` 的余量 ——
     * 同一份文本上判据天生高 `ContextBudget.JUDGE_TO_REPORT_PCT` 那一截。而 `real / est`
     * 若拿报告口径当分母，这条比值永远 ≈ 判据表 / 实测表（会一直说"估算偏低"），
     * 系数被 [ContextBudget.PromptCalibration.lowSide] 按住**只能往上走**，
     * 于是"判据偏高"这件事永远修不掉 —— 而它正好就是"2457 就到了"。
     *
     * 折法是**比值**，不是重算：两边用的是同一份 messages、同一个后缀、同一笔余量，
     * 差别只有那张表和系数。所以 `判据 ≈ est × 判据表/实测表` 在整段上是同量纲的。
     * 用常数近似而不是把 `tok()` 再跑一遍：样本是"这一轮"的，而 `tok()` 要 messages，
     * 这一层拿不到（也不该拿 —— 校准是判据的输入，不是它的副本）。
     *
     * @param est 这一轮**判据真的用过的那个数**之前的报告口径估算（`prompt_est`）
     */
    private fun judgeSideOf(est: Int): Int =
        if (est <= 0) est
        else Math.round(est.toDouble() * ContextBudget.JUDGE_TO_REPORT_PCT / 100.0).toInt()

    /**
     * 两份词表的键 —— **必须与"这次加载的是哪份模型"同源**。
     *
     * 拿文件名而不是拿 `vocab.hashCode()`：前者在 `/health` 与日志上都读得懂，
     * 而"系数存在哪个键上"正是排查时要对的第一个数。空串 = 没加载，调用方会丢掉样本。
     */
    private fun modelVocabKey(): String = loadedPath.orEmpty()

    private fun embedVocabKey(): String = embedPath.orEmpty()

    /**
     * 估算系数（×1000，按词表分键）。
     *
     * ⚠ 只活在内存里，**不落盘**：它是"这次加载的这份词表 + 这段语料"的近期比值，
     *   跨进程留着只会让下一次冷启动的第一轮用一个来历不明的系数 —— 那正是
     *   "低估 → native 每轮砍头部"的老路，而它没有任何读数能追。冷启动从 1.0
     *   （不校准）起，三份新鲜样本之后自己会到该到的位置。
     */
    private val promptEstScaleX1000 = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 连续新鲜样本数（每个词表一份；估算与真值差不到 1% 的那一轮清零）。 */
    private val promptCalibFresh = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 连续"估算偏低"的样本数（每个词表一份；往上抬的门槛比往下高，见
     * `ContextBudget.PROMPT_CALIB_UP_SAMPLES`）。
     *
     * 与 [promptCalibFresh] **不共用**：两个方向的证据不能互相借 ——
     * 共用一份计数器时，一句"偏低"的读数攒够 3 份就被当成"该往下调"的证据用掉了。
     */
    private val promptCalibRise = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 与 [takeCtxBudgetFailed] **同一次**归因的两个数：失败那一轮 prompt 的真 token 数
     * 与客户端估算值（`0` = 没传）。
     *
     * 只在 [takeCtxBudgetFailed] 为真之后调 —— 两格一起读、一起清（native 侧一个出口），
     * 分成两次读会出现"标志读到了、数被下一个请求覆盖了"的缝。
     *
     * 存在的理由：400 的文案必须能报出"这段 prompt 真有多少 tok、这一格多少"。
     * 只说"装不下"，用户唯一能做的就是调 ctx —— 而在真机上超出量往往只有几十枚，
     * 他却要为此重载一次模型。
     */
    fun takeCtxBudgetNumbers(slot: Int): Pair<Int, Int> =
        runCatching {
            val a = nativeTakeCtxBudgetNumbersSlot(slot) ?: return 0 to 0
            (if (a.size > 0) a[0] else 0) to (if (a.size > 1) a[1] else 0)
        }.getOrDefault(0 to 0)

    /**
     * 主动丢弃 prompt 缓存。存在的理由：**显式开关**比"等它自己失效"可控。
     * 用户换了一整套 system prompt / 把模板改了之后，旧前缀本来就匹配不上
     * （复用判据是逐 token 比前缀，不匹配自然不复用），但那时候 KV 里还留着一大段
     * 用不上的数据白占显存 —— 在手机上这直接等于少了几 MB 可用 KV 空间。
     *
     * ⚠ **整池**作废（[nativeKvCacheResetAll]，设计文档 §9.2.3，用户已确认）。
     * 这条路径的 6 个 UI 调用点都在 UI 线程上，而「前缀赖以成立的那段历史」是
     * **某一路**的（用户可能正在 App 里聊、HTTP 那一路在跑）。UI **不知道**自己在哪一路，
     * 所以一律走 `All`：多清一份的代价是那一路下一轮全量 prefill（慢一次），
     * 少清一份的代价是「模型读到换掉之前的 system 段」（静默答非所问）——
     * 两个方向的代价不对称，宁可多清。
     *
     * 形态上它同时是「登记作废」：真正清 KV 由各 slot 下一轮 `startCompletion` 去做
     * （KV 不是线程安全的，见 native 侧 `abort` 那一段的同源理由）。
     */
    fun resetKvCache() {
        // ⚠ **按池空闲的闸门**（设计文档 §9.2.4，用户已确认）：本函数的形态是
        // 「登记作废、由各 slot 下一轮自己清」，登记本身很快；但它的调用点全是
        // **非生成期路径**（换 system / 清空对话 / 删除会话 / 换角色卡），
        // 而"前缀赖以成立的那段历史被换掉了"这件事在**有人正在跑**时清，等于
        // 把那一轮脚下的账本抽走 —— 那一轮仍会跑完（KV 已被算过），但它之后
        // 一切复用判断都落在"没有账本"上，表现为"接下来很多轮都全量"。
        //
        // 判据只有一个数（`SlotScheduler.idle` -> 池里没人占），不拼
        // `generating || HttpApi.isGenerating`：那两个量多 slot 后含义已经从
        // 「有生成在跑」漂成「池里还有 slot 被占」，却仍由两个来源拼出来。
        //
        // 等不到（超上限）也照样登记：**宁慢不错** —— 少清一份的代价是
        // "模型读到换掉之前的 system 段"（静默答非所问），而多等一次只是慢。
        waitIdleForPool("resetKvCache 丢弃 prompt 缓存")
        runCatching { nativeKvCacheResetAll() }
    }


    /** 取模型内置 chat 模板（Jinja 原文），空串=未知。 */
    fun chatTemplate(): String = nativeChatTemplate()

    /**
     * 用模型模板渲染多轮对话。roles/contents 等长；失败回落到 ChatML。
     *
     * [thinkingOn] 是这一轮**最终**的思考开关（请求覆盖 > 全局默认，见 `ThinkingControl.resolve`），
     * 传给库的 `common_chat_templates_inputs::enable_thinking`。
     *
     * **必须传**：C++ 侧这个字段默认是 `true`，而 MiniCPM5 的模板按它决定生成后缀吐不吐
     * `<think>\n` —— 不传就等于"思考永远开着"，设置页「默认关闭思考」在这类模型上恒不生效
     * （2026-09-19 真机报障的第一个成因）。模板里没有 `enable_thinking` 变量时传下去不改变
     * 渲染结果（LFM2.5），那类由 `ThinkingControl` 的软开关兜底，所以两条路径共用同一个取值。
     */
    fun applyChatTemplate(
        messages: List<Pair<String, String>>,
        addAss: Boolean = true,
        thinkingOn: Boolean = true
    ): RenderedPrompt {
        val roles = Array(messages.size) { messages[it].first }
        val contents = Array(messages.size) { messages[it].second }
        val rendered = nativeApplyChatTemplate(chatTemplate(), roles, contents, addAss, thinkingOn)
        if (rendered != null) return RenderedPrompt.parse(rendered)
        // 回落：ChatML。**回落路径不得声明"思考段已开"** ——
        // ChatML 拼出来的后缀里没有 <think>，模型输出里的 `</think>` 是它自己吐的，
        // 必须由状态机去解析配对；这里若报 openAtStart=true，整段回答会被当思考段吞掉。
        val sb = StringBuilder()
        for ((r, c) in messages) sb.append("<|im_start|>").append(r).append('\n').append(c).append("<|im_end|>\n")
        if (addAss) sb.append("<|im_start|>assistant\n")
        // 回落路径的生成后缀是**宿主自己拼的**那段（见上一行）—— 这里必须显式带上，
        // 否则结构化输出的 grammar 又会退回"库自算的那个尾巴"，与真 prompt 分叉。
        // 值取自 RequestContext.CHATML_GEN_SUFFIX（唯一来源，与上一行逐字一致，
        // 由守卫断言两处字面量相同）；addAss=false 时**必须**是空串而不是 null：
        // 空串 = "确认没有生成后缀"，null = "不知道" —— 后者会让 native 按
        // "有生成后缀"推导 grammar（见 RequestContext.genPromptArg 的注释）。
        return RenderedPrompt(
            sb.toString(), openAtStart = false,
            generationSuffix = if (addAss) RequestContext.CHATML_GEN_SUFFIX else "")
    }

    /**
     * 带工具定义渲染 prompt。tools 为空数组、无模型或渲染失败时返回 null，调用方回落 [applyChatTemplate]。
     *
     * 必须走这条路径才能支持工具调用：`llama_chat_apply_template` 只吃 role/content，
     * 工具清单进不了 prompt（工具定义是写给模型看的，不入 prompt 模型就不知道自己有什么函数可用），
     * 于是永远吐不出 tool_calls。
     *
     * @param toolsJson OpenAI 格式的 tools 数组（`[{"type":"function","function":{...}}]`）
     * @param toolChoice "auto" / "required" / "none"。
     *   注意：**不要传 null**。JNI 只能把 null 变成空串 `""`，而 vendor 库对空串是
     *   throw `std::invalid_argument`（不是返回 AUTO），异常穿 JNI 即 SIGABRT。
     *   请求侧 [ToolCalls.parseToolChoice] 已保证非 null；此处的 null 兜底仍然保留，
     *   native 侧也另有一道同源兜底（两道防线都要有）。
     */
    fun applyChatTemplateWithTools(
        messages: List<Pair<String, String>>,
        toolsJson: String,
        toolChoice: String? = null,
        parallelToolCalls: Boolean = true,
        addAss: Boolean = true,
        thinkingOn: Boolean = true
    ): RenderedPrompt? {
        if (toolsJson.isBlank() || toolsJson == "[]" || toolsJson == "null") return null
        val roles = Array(messages.size) { messages[it].first }
        val contents = Array(messages.size) { messages[it].second }
        val tmpl = chatTemplate()
        probeMark("[工具] 即将渲染带 tools 的 prompt：msgs=${messages.size} tools_len=${toolsJson.length} " +
                "chat_template_len=${tmpl.length}（=0 表示模型没带模板）choice=${toolChoice ?: "auto"}")
        return try {
            // thinkingOn 也要传：库的 enable_thinking 与上面那段同理，且它会影响
            // generation_prompt（进而影响工具解析的前缀对齐），渲染侧与解析侧必须同源。
            val r = nativeApplyChatTemplateTools(
                tmpl, roles, contents, toolsJson, toolChoice, parallelToolCalls, addAss, thinkingOn)
            probeMark("[工具] 带 tools 渲染返回：${if (r == null) "null（回落无工具路径）" else "len=${r.length}"}")
            r?.let { RenderedPrompt.parse(it) }
        } catch (t: Throwable) {
            uiLog("[工具] 模板渲染失败，回落无工具路径：${t.message}")
            null
        }
    }

    /**
     * 从模型输出解析 tool_calls。
     *
     * 返回 Pair(content, toolCallsJson)：toolCallsJson 为 null 表示没有工具调用。
     * 语法随模板变化（Qwen 的 `<tool_call>`、Llama 的 `[TOOL_CALLS]` 等），
     * 由 native 侧 `common_chat_parse` 按模板归一，Kotlin 侧不做正则猜测。
     */
    fun parseToolCalls(
        text: String,
        toolsJson: String,
        addAss: Boolean = true,
        thinkingOn: Boolean = true
    ): Pair<String, String?>? {
        if (text.isEmpty()) return null
        val tmpl = chatTemplate()
        probeMark("[工具] 即将调用 nativeParseToolCalls：text_len=${text.length} " +
                "tools_len=${toolsJson.length} tmpl_len=${tmpl.length} add_ass=${addAss} " +
                "text_head=${text.take(200).replace("\n", "\\n")}")
        // addAss 必须与**渲染时**同一个值：解析器是按模板推导的 PEG，而 cp.generation_prompt
        // （会被 common_chat_parse 前拼到输入上、进而决定 PEG 根节点能否匹配）随
        // add_generation_prompt 变化。两边不一致 = 根节点前缀对不上 = tool_calls 恒为 0。
        // thinkingOn 同理必须与**渲染时**同一个值：它决定 cp.generation_prompt 的形状
        // （MiniCPM5 在 enable_thinking=true 下多一段 "<think>\n"），而那个量会被
        // common_chat_parse 前拼到输入上。两边不一致 = PEG 根节点前缀对不上 = tool_calls 恒为 0。
        val raw = try { nativeParseToolCalls(text, toolsJson, tmpl, addAss, thinkingOn) } catch (t: Throwable) {
            probeMark("[工具] nativeParseToolCalls 抛出异常：${t.javaClass.name}: ${t.message}")
            uiLog("[工具] 解析失败：${t.message}")
            null
        } ?: run { probeMark("[工具] nativeParseToolCalls 返回 null"); return null }
        probeMark("[工具] nativeParseToolCalls 返回 len=${raw.length} head=${raw.take(200)}")
        if (raw.isEmpty() || raw == "null") return null
        return try {
            val o = org.json.JSONObject(raw)
            val calls = o.optJSONArray("toolCalls")
            if (calls == null || calls.length() == 0) null
            else o.optString("content", "") to calls.toString()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 配置采样链，顺序与 llama.cpp 官方示例一致：
     * `penalties -> top_k -> top_p -> min_p -> temp -> dist`。
     * temp<=0 视为贪心（其余采样参数被忽略）。topK<=0 关闭；topP/minP<=0 或 >=1 关闭。
     *
     * 入参由 [SamplingParams.check] 校验过，native 侧不再重复兜底：
     * NaN/Inf、越界值都会让 llama.cpp 的采样器行为未定义。
     *
     * [generationPrompt] 是这一轮**实际渲染出来的**生成后缀（prompt 里模型要接着续写的那段尾巴）。
     * 它只在带 `response_format` 时有意义，用途是让 GBNF 约束与真实 prompt 对齐 ——
     * 详见 [ResponseFormat.GenerationPrompt] 的注释（不对齐的后果是「输出前面多一段
     * 模型自己吐的生成标记」）。
     */
    fun newSampler(temp: Float, topP: Float, minP: Float, seed: Long = 0,
                   topK: Int = 0, repPenalty: Float = 1f, penaltyN: Int = 0,
                   freqPenalty: Float = 0f, presencePenalty: Float = 0f,
                   stops: List<String> = emptyList(),
                   responseFormat: ResponseFormat = ResponseFormat.None,
                   generationPrompt: String? = null,
                   chatTemplateOverride: String? = null,
                   thinkingOn: Boolean = true,
                   biasIds: IntArray? = null, biasVals: FloatArray? = null,
                   slot: Int = LEGACY_SLOT): Boolean =
        // 阶段 4 起**生成侧一律走带 slot 的那份声明**（[nativeNewSamplerSlot]）：
        // 无 slot 的旧声明在阶段 3 只是"等价 slot 0"的兼容入口，阶段 4 接线之后
        // 没有人该再用它。默认参数 `LEGACY_SLOT` 让既有调用点（UI 测试页等）
        // 一行不改仍成立，语义与阶段 3 的旧声明**逐字节相同**。
        nativeNewSamplerSlot(slot, temp, topP, minP, topK, repPenalty, penaltyN, freqPenalty, presencePenalty, seed,
            // 空列表传 null（而不是空数组）：native 侧据此判断"要不要挂 stop 采样器"，
            // 挂一个什么都不匹配的采样器只是白搭一次链上调用。
            if (stops.isEmpty()) null else stops.toTypedArray(),
            // 三态映射（None -> null / JsonObject -> "" / JsonSchema -> 原文）的唯一来源，
            // 不在这里另判一次（两处各判一次必然漂移）。
            JsonSchemaFormat.schemaArg(responseFormat),
            // 生成后缀：null 表示"无 / 未知"，native 侧退回 `add_generation_prompt=true` 的旧口径。
            generationPrompt,
            // 模板：必须与**渲染侧同一份**。传 null/空串 = 让库按模型自选 ——
            // 那是引入本特性之前的行为，但库内自选的那份与运行时模板可能不是同一份，
            // grammar 会按别的模板算（或干脆产不出来），且不报错。
            chatTemplateOverride,
            // 思考开关：**必须与渲染侧同一个值**，理由见 nativeNewSampler 的说明与
            // gbnf_from_json_schema 的判据 ④。回落 ChatML 那条路也把它带下去。
            thinkingOn,
            // logit_bias 的平行数组。两者同源（[ProtocolFields.biasIds]/[biasValues]
            // 来自同一个 `List<Bias>`），所以长度天然相等 —— native 侧仍会再判一次
            // 并在不等时整条不挂（守 "宁可不挂，也不要一半"）。
            biasIds, biasVals)

    /**
     * `startCompletion` 失败时的**归因**，不是一句裸字符串。
     *
     * 以前失败只回一句 `String?`，于是「被取消」（有人按了停止 / 探针发现客户端走了）、
     * 「装不下」、「decode 真出错」在调用方那里**同形** —— 而三件的处置完全相反：
     * 取消只需重发，装不下要调容量，decode 出错要看日志。HTTP 层因此把一次正常的取消
     * 回成 `400 Bad Request`，客户端读成「我这条请求写错了」。
     *
     * [cancelled] 是唯一会改变 HTTP 状态码的字段：[HttpApi] 据此回 **499
     * Client Closed Request**（理由见那边的注释），其余两类仍是 400。
     */
    class StartFailure(
        val message: String,
        val cancelled: Boolean,
    )

    /**
     * 开始一轮生成：清 KV -> tokenize(含 BOS) -> 分块 prefill。
     * 返回失败归因（见 [StartFailure]），null=成功。
     */
    // ---- 生成计时（**每 slot 一份**，Issue #330 阶段 4 收尾）----
    // 此前 App 完全不记 tokens/s：任何 A/B（HTP / OpenCL / CPU、不同 ngl）只能比"崩没崩"，
    // 比不了"快不快"，实验结论缺半边。不用改 native——nativeStep 每次恰好解码 1 个 token
    // （llama_jni.cpp: llama_batch_get_one(&tok,1) + llama_decode），按调用次数计数即 token 数；
    // prefill 耗时＝nativeStartCompletion 这一次调用的墙上时间。
    //
    // ⚠ 这几笔账**原来是全局单份**：多 slot 之后两路会互相冲掉对方的分母与起点，
    //   症状是 decode tok/s 报出一个"看着正常、其实是混合产物"的数（甚至把别人那路
    //   的结束读成自己的结束）。所以它们必须与 ctx / 账本一样**下沉到每 slot** ——
    //   这不是可观测面的锦上添花，`genActive` 还参与"这一轮是否结束"的判定。
    //
    // 用固定长度数组按 slot 下标取（域由 [SlotScheduler.SLOT_MAX] 界定）；越界
    // 一律回落 slot 0，与 native `slot_of` 的口径同源。
    private val genPromptTok = IntArray(SlotScheduler.SLOT_MAX)
    private val genPrefillMs = LongArray(SlotScheduler.SLOT_MAX)
    private val genTok = IntArray(SlotScheduler.SLOT_MAX)
    private val genT0 = LongArray(SlotScheduler.SLOT_MAX)
    private val genActive = BooleanArray(SlotScheduler.SLOT_MAX)
    /** 首个 token 落地的时刻（= startCompletion 返回 → 第一次 step 拿到字）。见 [logPrefillReadout]。 */
    private val genFirstTokMs = LongArray(SlotScheduler.SLOT_MAX)

    /** slot 下标的规范化（越界回落 0），与 native `slot_of` 同源。 */
    private fun gs(slot: Int): Int = if (slot in 0 until SlotScheduler.SLOT_MAX) slot else 0

    /**
     * 开一轮生成。
     *
     * 后两个形参是**只管读数**的两个透传量（ISSUE #330 第 4 块），一个字节都不参与判据：
     *   · [promptEstTokens] —— 客户端按估算算出的 prompt token 数（0 = 没算 / 不知道）。
     *     它与 native tokenize 出来的**真** token 数并排进 `/health`，差值就是
     *     "预算说一条都没丢、native 却从头部砍了一刀"那条窄带的宽度。以前这条窄带
     *     在读数上完全看不见：客户端报 `prompt_trimmed: 0`，native 报了也没人对得上。
     *   · [injectChars] —— 本轮知识库注入块的字符数（0 = 本轮没注入）。块宽度一变，
     *     本轮提问那一段就全变、前缀断在块头 —— 而"块到底多宽"以前只在一条 uiLog 里。
     */
    fun startCompletion(
        prompt: String,
        maxTokens: Int = 256,
        slot: Int = LEGACY_SLOT,
        promptEstTokens: Int = 0,
        injectChars: Int = 0,
        writer: Int = WRITER_NONE,
        /**
         * 本轮**判据用的估算系数**（×1000，[promptEstScale] 的落值；1000 = 没校准）。
         *
         * 它只跟着账本进 `/health`（`est_scale` 那一格），不参与任何 native 判据 ——
         * native 按真 token 判，用不上系数。但 `prompt_est` 是**乘过它**的数，
         * 两个数不并排报，同一个 `prompt_real` 配 2825（系数 1.0）与 1977（0.7）
         * 读起来只像"估算忽然变准了"，而那正是我们自己调低的（ISSUE #452 ⑤）。
         *
         * 由本函数自己按词表去取，**不要求调用方传**：判据用哪个系数与这里报哪个
         * 系数必须是同一个值，两处各传一次就会分叉（而分叉只表现为 `/health` 上
         * 一个对不上的数）。
         */
        estScaleX1000: Int = ContextBudget.PromptCalibration.toX1000(
            promptEstScale(loadedPath.orEmpty())),
    ): StartFailure? {
        val i = gs(slot)
        val t0 = android.os.SystemClock.elapsedRealtime()
        if (!nativeStartCompletionSlot(slot, prompt, maxTokens, promptEstTokens, injectChars, writer,
                estScaleX1000)) {
            genActive[i] = false
            // ── 先判"是不是被取消的"，再判"是不是装不下"（ISSUE #450 第十四次现场）──
            //
            // 这两件事共用同一个返回码（native 对失败只回 false），而处置完全相反：
            //   · 取消   = 有人按了停止 / 探针发现客户端走了 —— 重发同一个 prompt 就行，
            //              客户端什么都不用改；
            //   · 装不下 = 调容量 / 裁历史；
            //   · 其余   = decode 真出错，去看日志。
            // 旧写法把取消一路落到最后那句「prefill 失败（decode 出错，详见日志）」，
            // 而真机日志里的 `prefill 被中断（abort）` 与"decode 出错"在读者眼里同义 ——
            // 用户拿着这句 400 来问「服务器凭什么自己 abort」，而服务端只是忠实执行了
            // 一次取消（多半是 prefill 探针写失败判定的 GONE）。
            // 归因**在这里就拆开**：以前调用方只拿到一句 nil，只能一律回 400；
            // 现在 [StartFailure.cancelled] 让 HTTP 层回 499（取消不是请求格式错误）。
            if (takePrefillCancelled(slot)) {
                return StartFailure(
                    "prefill 已被取消（这一轮没有算完，不是容量或解码问题）：" +
                        "重发同一个请求即可 —— 取消来源见日志里那一行「prefill 被中断（abort）」" +
                        "或「探测到客户端已离开（写失败）」",
                    cancelled = true)
            }
            // 失败面**拆成两种**（ISSUE #330）：此前一律回一句
            // "prefill 失败（prompt 过长或 decode 出错）" —— 它把两个处置完全相反
            // 的猜测同时说出口（调上下文 / 看日志），等于什么都没说。
            // 判据只有一个来源（native 的一次性标志，读即清），这里不重新推导；
            // 数字也来自同一次归因（真 token 数 / 客户端估算值），两者一起读、一起清。
            if (takeCtxBudgetFailed(slot)) {
                val (real, est) = takeCtxBudgetNumbers(slot)
                // 每格容量：`nativeCtxSize()` 已经是**每格**（总量在 `contextSize()`），
                // 两格各取一遍会出现"报总量、比每格"的错配。
                val per = ctxPerSlot()
                return StartFailure(
                    "上下文装不下：prompt 真 " + (if (real > 0) "$real" else "?") + " tok" +
                        (if (est > 0) "（客户端估 $est）" else "") +
                        "，这一格容量 $per tok（${slotCount()} 格，每格各自一份，合计 ${effectiveCtxTotal()}）" +
                        "—— 超出 " + (if (real > 0) "${(real - per).coerceAtLeast(0)}" else "?") +
                        " tok，调大上下文或让客户端裁短历史",
                    cancelled = false)
            }
            return StartFailure("prefill 失败（decode 出错，详见日志）", cancelled = false)
        }
        val t1 = android.os.SystemClock.elapsedRealtime()
        genPromptTok[i] = contextUsed(slot).coerceAtLeast(0)
        genPrefillMs[i] = (t1 - t0).coerceAtLeast(1L)
        genTok[i] = 0; genT0[i] = t1; genActive[i] = true
        genFirstTokMs[i] = 0L
        // 读数**不在这里**打：`nativeStartCompletionSlot` 返回时 prefill 只算了尾格
        // logits，真正的 prefill 是在 `step` 里边生成边跑完的 —— 此刻拿到的时间既不是
        // prefill 耗时、也不是"整段 prompt 已经进 KV"，写成 `prefill X tok / Yms` 是
        // 读数在撒谎（见 [logPrefillReadout]）。这里只记账。
        return null
    }

    /**
     * 本轮的 prefill / 首 token 读数。**由第一个 token 落地时调用**（`step` 里），
     * 因为那才是 `genPrefillMs` 与 `lastPrefillTokensOf` 两个数**终于都成立**的时刻。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须挪到这一刻（旧写法是读数在撒谎，比 bug 本身更容易误导排查）
     * ══════════════════════════════════════════════════════════════════════
     * `startCompletion` 只是把 prompt 的**尾格** decode 一次，剩下的 token 是在
     * 生成循环里"边生成边 prefill"的（这正是为什么每块只要尾格 logits）。于是：
     *   · 旧行 `prefill {n_used} tok / {t1-t0}ms` 的分子是**整段 prompt**（含复用段），
     *     分母却只是"开始 prefill 到 tail decode 完成"——比值的语义不存在；
     *   · 显式填 pos 之后 prefill 真的变快了，这一行的偏差反而**更大**（分子没变、
     *     分母变小），于是它看起来"提速了"，而真相是"读数换了口径"。
     *
     * 现在报的是两个各自成立的数，各带各的分母：
     *   · `prefill {newTok} tok` —— **本轮新算**的 token（native 的 lastPrefillTokens，
     *     不含复用段）。分母是同一段窗口，`tok/s` 才是真的 prefill 速度；
     *   · `首 token {firstTokMs}ms` —— 整段耗时（含 prefill + 首个采样）。客户端
     *     感受到的延迟是**这个数**，不是上面那个比值。
     * 两条分开读：想比"模型快不快"看第一条，想比"用户等多久"看第二条。
     */
    private fun logPrefillReadout(slot: Int, i: Int) {
        val promptTok = genPromptTok[i]
        val newTok = lastPrefillTokensOf(slot)
        val reused = lastReuseTokensOf(slot)
        val prefillMs = genPrefillMs[i]
        val firstTokMs = genFirstTokMs[i]
        // ⚠ 这几行读数**必须按 slot 取**（设计文档 §9.2.10）：无 slot 的旧读数是
        // 「最后动过账本的那一路」，多 slot 下看起来完全正常、只是不是你以为的那一路。
        if (newTok > 0) {
            uiLog(slotTag(slot) + "${LogFileStore.TAG_TIME} prefill %d tok / %dms = %.1f tok/s".format(
                    newTok, prefillMs, newTok * 1000.0 / prefillMs))
        } else {
            // newTok == 0 只可能出现在降级路径上（账本与 KV 对不上、或复用把整段都
            // 当已缓存）。**不能**拿 promptTok 顶上 —— 那正是旧行撒谎的写法。
            uiLog(slotTag(slot) + "${LogFileStore.TAG_TIME} prefill 本轮新算 0 tok（读数不可用，见上一行 [kv] 日志）")
        }
        // 整段耗时单独一行、单独叫法：它是客户端感受到的延迟，与上面的比值不是一回事。
        uiLog(slotTag(slot) + "${LogFileStore.TAG_TIME} 首 token %dms（含 prefill %dms / 整段 prompt %d tok）".format(
                firstTokMs, prefillMs, promptTok))
        if (reused > 0) {
            uiLog(slotTag(slot) + "${LogFileStore.TAG_TIME} KV 前缀复用：复用 %d tok / 新算 %d tok（共 %d）".format(
                    reused, newTok, promptTok))
        } else {
            // 未命中时把**公共前缀**一起报出来（第 5 步）：没有它，「差一点没命中」
            // 与「两轮 prompt 真不一样」在日志上逐字相同，用户只能猜是门槛拦的
            // 还是注入位置的问题（开着知识库时检索结果每轮都变，分叉点在最前面）。
            uiLog(slotTag(slot) + "${LogFileStore.TAG_TIME} KV 前缀复用：未命中（公共前缀 %d tok / 门槛 16），全量 prefill %d tok".format(
                    lastLcpTokensOf(slot), newTok))
        }
    }

    /** 采样一步并返回一个完整 UTF-8 片段；null=结束。 */
    fun step(slot: Int = LEGACY_SLOT): String? {
        val i = gs(slot)
        val s = nativeStepSlot(slot)
        if (s != null) lastProgressAt = System.nanoTime()
        // 计时账本按 **slot**：另一路在跑时不得影响本轮的 token 计数与起点。
        if (genActive[i]) {
            if (s != null) {
                // 第一个 token 落地 = prefill 真的跑完了（边生成边 prefill 的那个尾巴）。
                // 此刻 native 的 lastPrefillTokens 也已经是本轮的终值，读数才成立。
                if (genFirstTokMs[i] == 0L) {
                    genFirstTokMs[i] = (android.os.SystemClock.elapsedRealtime() - genT0[i]).coerceAtLeast(1L)
                    logPrefillReadout(slot, i)
                }
                genTok[i]++
            } else {
                finishGeneration(slot)
            }
        }
        return s
    }

    /** 一轮生成收尾：decode 速度连同引擎/HTP 状态一起落日志，便于跨配置对比。 */
    private fun finishGeneration(slot: Int = LEGACY_SLOT) {
        val i = gs(slot)
        genActive[i] = false
        val ms = (android.os.SystemClock.elapsedRealtime() - genT0[i]).coerceAtLeast(1L)
        // 带 slot 前缀：两路并行时这一行必须说得出**是哪一路**的读数（§8.1）。
        uiLog(slotTag(slot) + "${LogFileStore.TAG_TIME} decode %d tok / %dms = %.1f tok/s ｜ 引擎=%s HTP=%s".format(
                genTok[i], ms, genTok[i] * 1000.0 / ms, nativeTag ?: "?", htpRequested))
        // 内存这一栏必须有一个**推理态**的数：此前唯一的 RSS 探针打在 loadModel 里，
        // 于是"加载完成态"被当成"跑起来的占用"，优化时瞄错了时刻（见 native 那段注释）。
        val peak = runCatching { nativeRssPeakMb() }.getOrDefault(-1)
        if (peak > 0) uiLog("${LogFileStore.TAG_MEM} 推理期 RSS 峰值 %d MB（加载态读数见 [mmap释放]，两者成对读）".format(peak))
    }

    /**
     * 中断当前生成（下一次 step 返回 null）。
     *
     * 保留这个公开名（UI 侧历史调用点），但**必须**带上归属：裸的无编号取消会把
     * 一个迟到的调用打到下一轮上（见 `RequestCancel` 文件头与本文件 [requestAbort]）。
     * 没有轮次在跑时它演化为 no-op —— 这正是我们要的语义：取消不存在的轮次不该
     * 产生任何副作用，也不该"预支"给下一次。
     */
    fun abort(): RequestCancel.Token? = requestAbort()

    /** 释放采样器/上下文/模型。与 [loadModel] 共用同一把锁：卸载与加载交错同样会污染 native 状态。 */
    fun unload(): Unit {
        // 同上：等待在锁**外**（ISSUE #348）。用户报的现场① 正是这一条 ——
        // HTTP 那一路还在生成时点「卸载模型」，会话日志停在 `[内存] 卸载[对话]`
        // 那一行、没有结束标记（进程被信号级杀死）。
        waitIdleForPool("unload 卸载模型")
        synchronized(loadLock) { unloadLocked() }
    }

    /**
     * [unload] 的实体；调用方已持有 [loadLock]（与 `loadModelLocked` 同一约定，
     * 避免重入与锁序问题）。换模型那条路径也走它 —— 卸载的**副作用**（释放后
     * 报读、归还未释放的匿名页）只有一处，不会出现"这条路做了、那条路漏了"。
     *
     * ⚠ 这里**也**等一次整池收尾（ISSUE #348 第 2 条）—— 与 [unload] 里那一处
     * 不是重复，而是**唯一**覆盖"换模型"这条路的那一处：
     *
     *   · [unload]          —— 用户点「卸载模型」。等待必须在**锁外**（持
     *     `loadLock` 等生成 = 与"生成线程排队等同一把锁"互锁）。
     *   · [loadModelLocked] —— 加载前的"先拆旧的"（`if (hasModel) unloadLocked()`）。
     *     它**已经持着 `loadLock`**，所以那一处只能等在这里：走到这一行时锁在手，
     *     生成线程不可能再新开一轮（`newSampler` / `startCompletion` 都在
     *     `slotLock` 里，而拆解动作在 `loadLock` 里），于是"整池此刻真的空着"
     *     是一个**当场能确证**的事实，不是一个希望。
     *
     *   判据落在**函数**上而不是**调用点**上：将来多一个 `unloadLocked()` 的
     *   调用方，它自动被覆盖，不需要谁记得再补一次（上一版正是只点了
     *   `loadModel` / `unload` 两个名字，而换模型那一处两个都不在）。
     *   幂等：[unload] 那条路刚在锁外等过，这里再问一次时池已空，
     *   [waitIdleForPool] 的快路径（`SlotScheduler.idle`）零开销返回。
     */
    private fun unloadLocked() {
        waitIdleForPool("unloadLocked 拆整池")
        if (!loaded) return
        java.util.Arrays.fill(genActive, false)   // 换模型/卸载时复位（整池），避免旧计数串进下一轮
        nativeFreeSampler()
        // 释放这一幕必须**当场报读**：`[内存] 卸载 RSS …` 由 native 在
        // `llama_model_free` 前后各量一次 VmRSS 得到。此前卸载这条路径上
        // 一行读数都没有，用户只能去任务管理器看"占用降不下来"，
        // 日志静默（0.9.130 现场）。
        nativeUnloadModel()
        // 整池拆完 -> 调度器的表也跟着清零（否则 slots_total 还停在旧模型的值，
        // 而池子已经空了：`/health` 会说"有 2 个 slot"而它们其实一个都不存在）。
        // ⚠ 与 native 的 `unload_model_pool()` 同源：**按整池**，不许拆一半。
        SlotScheduler.reset()
        currentPath = null
        // 卸载要连预读状态一起作废，否则 UI 会继续显示上一个模型的信息（误导）
        probeId = null
        probeVal = null
        // 释放之后再把"还没还给内核的页"补一脚（见 [reclaimReleasedHeap]）：
        // 卸载完还留在 RSS 里的只剩下这一类，这一脚是**净收益**，且只跑一次。
        reclaimReleasedHeap()
    }

    /**
     * 让分配器把"已 free、但还没归还内核的匿名页"交还，并报前后 RSS。
     *
     * 为什么必须有这一脚：**CPU 侧的默认分配器会缓存刚释放的堆段**（glibc 的
     * `M_TRIM_THRESHOLD`/`M_MMAP_THRESHOLD`、scudo 的 size class 缓存，Android 上
     * 是后者）。它的直接后果是 —— repack 的匿名拷贝、KV、compute buffer 虽然都
     * `free` 了，**VmRSS 照样不降**。于是应用列表里显示的占用一直很高，
     * 看起来像"repack 根本没省"，而实际省下来的只是"还没归还"。
     *
     * 只有让**分配器自己**动手（`malloc_trim(0)`）或整进程重启能把这段要回来。
     * ⚠ 旧版在 native 侧绕过分配器、直接对"最大的匿名段"补 `madvise(DONTNEED)`，
     * **那条路会抹掉活对象并把进程 abort 掉**（ISSUE #274 的闪退根因），已移除 ——
     * 详见 native `nativeReclaimReleasedHeap` 上方的注释。bionic 没有 `malloc_trim`
     * 等价接口，那边现在**什么都不做**：宁可这一脚不省内存，也不做一件可能崩的事。
     *
     * 判据（与 `[mmap释放]` 同一套写法，**自证**）：前后各读一次 VmRSS 打进日志。
     *
     * 为什么只在**卸载后空闲**这一刻做，不在加载后做：`malloc_trim` 会把页还掉，
     * 下一次分配又要重新缺页（代价是加载/首轮推理变慢）。空闲时调用是净收益。
     *
     * 为什么**必须**在 native 里做而不是 Kotlin：判据是"运行期拿到的 `mallinfo`/RSS
     * 真实变化"，而且是 native 自己 malloc 出来的匿名页；Kotlin 那侧的 System/API
     * 看不见这些块，也唤不动 native 的分配器。**不 fork 任何子进程** ——
     * 本仓库此前在 native probe 里用 `fork`+`execl` 读系统属性，在国产 ROM 上被
     * 域策略整族拦掉（见 `probe_getprop` 那段），同一种病不再犯一次。
     *
     * 失败**不影响任何结果**：最坏就是"没多还这一份"，与不做之前完全一致。
     */
    private fun reclaimReleasedHeap() {
        val log = runCatching { nativeReclaimReleasedHeap() }.getOrNull() ?: return
        if (log.isNotEmpty()) uiLog(log)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  嵌入（embedding）模型 —— 双常驻：与对话模型**同时**驻留
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 为什么单独一把锁：嵌入路径与生成路径**不共用** native 状态（各持一份
    // Session，见 native 侧 `S` / `E`），所以它们不需要互相等。用 `loadLock`
    // 会让"检索一条"排在"生成一整轮"后面 —— 那正是双常驻要消掉的等待。
    //
    // ⚠ 但仍**必须**有自己的锁：同一份嵌入 Session 上的两次加载/卸载交错，
    // 与 0.9.70 那次"两个 loadModel 同时动 native 状态"是同一个病。
    private val embedLock = Any()

    /** 嵌入模型是否已驻留（与对话模型无关）。 */
    val hasEmbedModel: Boolean
        get() = try { nativeHasEmbedModel() } catch (t: Throwable) { false }

    /** 嵌入向量维度；0 = 没有可用嵌入模型。供索引指纹与检索校验用。 */
    val embedDim: Int
        get() = try { nativeEmbedDim() } catch (t: Throwable) { 0 }

    /**
     * 输出侧维度（`llama_model_n_embd_out`）；0 = 取不到。
     *
     * 与 [embedDim] 分开报的理由：库按输出侧维度分配 output buffer，而读取按
     * `n_embd` 步长走 —— 两者不等时"读一行"是未定义行为（读到相邻 buffer，
     * 含未初始化的 NaN）。症状正是用户报的"某些条归一化失败、且时好时坏"。
     * 只报一个维度时，这两个数的**差值**（真正的根因）根本无从看见。
     */
    val embedOutDim: Int
        get() = try { nativeEmbedOutDim() } catch (t: Throwable) { 0 }

    /** 嵌入模型声明的池化类型：0=NONE / 1=MEAN / 2=CLS / 3=LAST / -1=取不到。 */
    val embedPooling: Int
        get() = try { nativeEmbedPooling() } catch (t: Throwable) { -1 }

    /**
     * 嵌入这一份**每条文本实际生效**的预算（= 库的 `n_ctx_seq`）；0 = 没加载。
     *
     * ⚠ 与 `EmbedCtxPref.get()`（用户填的**设置值**）是两个数，必须一起读：
     * native 把设置值钳到 `kEmbedCtxMax / kEmbedBatchRows`（0.9.241 起 512），
     * 所以"填 1024 实际按 512 跑"是完全正常的 —— 而只报设置值时，那种情形
     * 与"填了并且生效"在界面上逐字相同（用户只能得出"这格没用"）。
     */
    val embedCtxPerSeq: Int
        get() = try { nativeEmbedCtxPerSeq() } catch (t: Throwable) { 0 }

    /**
     * 这一份嵌入 ctx **实际拿到**的 seq 数（0.9.242）；0 = 没加载。
     *
     * ⚠ 与 `EmbedBatchPref.get()`（设置值）/ [kEmbedBatchRows]（天花板）是**三个**数：
     * 条数可调之后，"我填了 1"与"这一份 ctx 真只有 1 个 seq"必须分得开 ——
     * 后者才是合批能塞几条的**真值**（`nativeEmbedBatch` 的 `nRows > E.seq_max`
     * 那道门认的就是它；越界时是"向量不对、接口 200"，不是崩）。
     */
    val embedSeqMax: Int
        get() = try { nativeEmbedSeqMax() } catch (t: Throwable) { 0 }

    /** 两道门的**生效值**（`n_batch`/`n_ubatch` 取小，库的实数）；0 = 没加载。 */
    val embedGate: Int
        get() = try { nativeEmbedGate() } catch (t: Throwable) { 0 }

    /**
     * 嵌入 ctx 的**总格数**（= native 的 `E.n_ctx`）；0 = 没加载。
     *
     * ⚠ 这是「省内存」那件事的**唯一**直接读数：KV / output buffer 都按它分配。
     * 另外三个旋钮（每条预算 / 条数 / 两道门）都只是它的输入 —— 用户调小条数
     * 却发现内存没怎么降时，答案就在这里（`n_ctx = 每条预算 × 条数` 与
     * 「嵌入 ctx 总量」取小，后者默认 2048 会顶住）。
     */
    val embedCtxTotal: Int
        get() = try { nativeEmbedCtxTotal() } catch (t: Throwable) { 0 }

    /**
     * 最近一次嵌入失败的**可归因**详情（解析自 native 的固定格式串）。
     *
     * 为什么把它带出来而不是只留日志：用户看不到 logcat，而 UI 上那句
     * "检查嵌入模型与内存"在**两个不同根因**下都成立（池化取错 / 维度不匹配），
     * 等于没有排查方向。同时"哪一条失败"从来没有被记下来 —— 100 条坏 3 条时
     * 只能二分重试，而失败详情在失败当场才拿得到，事后回不来。
     */
    data class EmbedFail(
        val reason: String,
        val nTokens: Int,
        val nEmbd: Int,
        val nEmbdOut: Int,
        val pooling: Int,
        val norm: Double
    ) {
        /**
         * 给用户看的一句话（含实值，而不是无差别的那句"检查内存"）。
         *
         * ⚠ 两处**不能只有"可能是"**：
         *   · `zero_norm` 在多条上都出现时，它**不是**"可能不符"，而是**确定不符** ——
         *     `pooling=$poolingText` 已经把话说到明处（CLS 却按 LAST 取，范数必然下溢）。
         *     说"可能"会让用户去查内存、换模型，方向是错的。
         *   · `decode_failed` 必须带上池化方式：库里 `llama_decode` 失败的一个**确定**
         *     来源就是"CLS 却只把部分 token 标成 output"（库的前置校验直接拒）。
         *     不带 pooling 时它与"内存不够"长得一模一样。
         * 实值（token 数 / 维度 / 池化）全部打出来 —— 用户拿到的必须是**信息**。
         */
        fun describe(): String = when (reason) {
            "dim_mismatch" -> "输出维度不匹配（n_embd=$nEmbd, n_embd_out=$nEmbdOut）—— 该模型与当前读取方式不兼容"
            "zero_norm" -> "向量范数为 $norm（零向量/下溢）；池化方式 ${poolingText()} 与取值位置不符 —— 这一条无法编码"
            "no_output" -> "库没有留下该 token 的输出（embeddings 未生效或库版本不同）；pooling=$pooling"
            "decode_failed" -> "前向计算失败（n_tokens=$nTokens, pooling=${poolingText()}）—— 该池化方式的前置条件不满足"
            "pooling_contract" -> "池化契约不满足（pooling=${poolingText()}，n_tokens=$nTokens）—— 填充逻辑的 bug，不是模型问题"
            "truncated_empty" -> "截断后没有可用 token"
            "bad_input" -> "输入无法编码（空文本 / tokenize 失败）"
            else -> "编码失败（$reason）"
        }

        /** 池化方式的**原值 + 人话**：光看 `2` 谁也不知道那是 CLS。 */
        fun poolingText(): String = "$pooling（${
            when (pooling) {
                0 -> "NONE/last"; 1 -> "MEAN"; 2 -> "CLS"; 3 -> "LAST"; -1 -> "UNSPECIFIED"
                else -> "未知"
            }
        }）"
    }

    /**
     * 解析 native 的失败详情串（`reason|n_tokens|n_embd|n_embd_out|pooling|norm`）。
     * 空串 / 格式不符返回 null —— **不猜**：归因错了比没有归因更糟。
     */
    private fun parseEmbedFailure(raw: String): EmbedFail? {
        if (raw.isEmpty()) return null
        val f = raw.split('|')
        if (f.size != 6) return null
        return try {
            EmbedFail(f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toDouble())
        } catch (t: Throwable) { null }
    }

    /** 取走"最近一次嵌入失败"的详情；没失败返回 null。 */
    fun lastEmbedFailure(): EmbedFail? =
        try { parseEmbedFailure(nativeEmbedLastFailure()) } catch (t: Throwable) { null }

    /**
     * 嵌入模型的短描述；空串 = 没有已驻留的嵌入模型。
     *
     * 为什么要有它：`/health` 需要能**分开**报出"对话那份"与"嵌入那份"分别是什么。
     * 只有 [modelDesc] 时，双常驻下两份读数是同一个字符串 —— 于是"嵌入到底装上没有"
     * 在探活里完全看不出来，用户只能靠翻日志猜。
     */
    fun embedModelDesc(): String =
        try { nativeEmbedModelDesc() } catch (t: Throwable) { "" }

    /**
     * 本机可用的**性能核数** —— 建库/嵌入这类"纯 CPU 密集"工作的默认线程数。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么要有这个函数（而不是继续写死 4）
     * ═══════════════════════════════════════════════════════════════════════
     * 用户报的现象：8e（骁龙 8 Elite，8 核）手机上建索引 **1 秒 1.3 条**，
     * 7000 条约等于两小时。而 `loadEmbedModel` 的默认值写死 `nThreads = 4`，
     * 且 UI 侧调它时**不传线程数** —— 于是那台 8 核机器上有一半核心全程闲着，
     * 而屏幕上唯一的读数是一句走得极慢的进度。
     *
     * 这不是"参数没调好"，是**默认值与设备无关**：同一个 4 在 4 核机器上是对的、
     * 在 8 核旗舰上浪费一半、在 6 核中端机上也不对。写死的默认值只能对一种机器正确。
     *
     * ⚠ 取的是 `availableProcessors()`（**含大小核在内**），不是
     * "只挑性能核"。理由：本仓库没有可靠的 Android 大小核判据
     * （`/sys/devices/system/cpu/cpuN/cpu_capacity` 不是所有 ROM 都给，且
     *  `taskset` 亲和性在部分机型上会反而把线程全排到小核）。而 llama.cpp
     * 自己会做负载均衡 —— 给多了顶多是"小核拖一点后腿"，
     * 给少了是**确定的、线性的一半算力白扔**。两害相权取其小。
     *
     * ⚠ 上限 8：线程再往上对内存带宽已经饱和（嵌入是逐条的小前向，
     * 每次都要重读权重），多线程只剩同步开销。下限 2：单线程会让本来能跑的
     * 机器慢到不可用。
     */
    fun defaultThreads(): Int = try {
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    } catch (_: Throwable) {
        4
    }

    /**
     * 加载嵌入模型。**不碰**对话模型（双常驻的契约）。
     *
     * 返回错误信息，null=成功。与 [loadModel] 同一口径：不抛异常给调用方，
     * 因为它在后台线程上被调，未捕获的异常会闪退整机。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 四个旋钮（0.9.242 起 `rows` / `gate` / `ctxCap` 三个是新增的）
     * ═══════════════════════════════════════════════════════════════════════
     * 用户的原话：*"有必要加一个参数么，让用户可以设置 kEmbedMaxCtx / kEmbedGate
     * 和 kEmbedBatchRows 么，这样可以让低端机调低，降低内存占用"* —— 而这三个数
     * 此前**同一个值扮演两个角色**（上限 + 落值），于是"想更省"只能改常量重新发版。
     *
     *   · [nCtx]   —— **每条**文本的预算（默认 512）。决定截断点，也乘出 ctx 总量；
     *   · [rows]   —— 一次合批几条（= `n_seq_max`，默认 [kEmbedBatchRows]）；
     *   · [gate]   —— 两道门（`n_batch`/`n_ubatch` 设成同一个数，默认 [kEmbedGateMax]）；
     *   · [ctxCap] —— ctx **总量**上限（默认 [kEmbedCtxMax]）。
     *
     * ⚠ **省内存要动 [ctxCap]**：`embeddings=true` 时的 KV / output / compute buffer
     * 三项都按 `n_ctx` 分配，而 `n_ctx = 每条预算 × rows` 再与 [ctxCap] 取小 ——
     * [rows] 只是那个乘积的一个因子（调小它，`n_ctx` 仍可能被别的项顶到 [ctxCap]）。
     * 三个数**一起**给才说得清"这一档到底吃多少"，所以设置页把它们放在同一组、
     * 各给一行读数（`[embed内存]` 那行打的就是落值）。
     *
     * 每个数都是"上限内的意愿值"：native 侧还会做构造性收口
     *（`perSeqMax = min(gate, ctxCap) / rows`，见 native 的 `gatesAbs` 段），
     * 所以传大了不会 abort —— 只会被钳，并在 `容量落值` 那行报出来。
     * `<=0` = 用天花板那一档（老调用方、以及升级上来的 prefs 走这一支，
     * 行为与 0.9.241 逐字相同）。
     *
     * nThreads 默认走 [defaultThreads]（本机核数，钳 2~8）—— 见那里的长注释。
     * ⚠ 建库耗时**直接乘在这个数上**：它是逐条前向，线程数就是吞吐的线性因子。
     * UI 那边（`EngineActivity.doLoadEmbed`）传的是用户在设置页填的值，
     * 所以这里那个默认值只在"调用方没给"时才生效。
     */
    fun loadEmbedModel(
        path: String,
        nCtx: Int = 0,
        nThreads: Int = defaultThreads(),
        useMmap: Boolean = true,
        rows: Int = 0,
        gate: Int = 0,
        ctxCap: Int = 0
    ): String? = synchronized(embedLock) {
        if (!ensureReady()) {
            return "引擎初始化失败：${lastInitError ?: "native 库尚未加载（无可用 Context）"}"
        }
        embedPath = null
        // ⚠ 三个新旋钮在 Kotlin 侧**先归一**（`<=0` 当天花板、超天花板钳住）：
        // native 侧也做同一件事，但两处各写一遍时迟早分岔，而分岔的症状是
        // "输入框里 2、日志里 4，界面上一个字都没有"（本仓库记过多次的那族）。
        // 这里归一之后传下去的是**合理的数**，native 那层再兜一次底。
        val wantRows = if (rows <= 0) kEmbedBatchRows else kEmbedBatchLimit(rows)
        val wantGate = if (gate <= 0) kEmbedGateMax else kEmbedGateLimit(gate)
        val wantCtxCap = if (ctxCap <= 0) kEmbedCtxMax else kEmbedCtxLimit(ctxCap)
        val ok = try {
            nativeLoadEmbedModel(path, nCtx, nThreads, useMmap, wantRows, wantGate, wantCtxCap)
        } catch (t: Throwable) {
            uiLog("[嵌入] 加载失败：${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!ok) return "嵌入模型加载失败（检查 GGUF 完整性 / ctx 是否超出可用内存）"
        embedPath = path
        // 批级读数的词典是"这个模型在这台机器上的合批命中率"（见 [EmbedBatchStats.reset] 的说明）——
        // 换模型必须清零，否则从"合批型"换到"回落型"时，用户看到的仍是上一个
        // 模型的命中率 —— 那个数字**看着正常**，只是描述的不是眼前这个模型。
        EmbedBatchStats.reset()
        // 逐位比对的结论同理（见 [EmbedParity.Last.reset] 的说明）：它描述的是
        // "这个模型"的批行为，换模型后留着上一个模型的结论会被读成眼前这个的。
        EmbedParity.Last.reset()
        // 检索对照的结论同理（它描述的是"这个模型换批大小后答案稳不稳"）——
        // 换模型后留着上一条会被读成眼前这个模型的（同一个病）。
        EmbedRetrieveParity.Last.reset()
        uiLog("[嵌入] 已加载：${path.substringAfterLast('/')}（n_embd=${embedDim}，与对话模型同时驻留）")
        null
    }

    /** 卸载嵌入模型（只拆嵌入那一份，对话模型不受影响）。 */
    fun unloadEmbedModel() = synchronized(embedLock) {
        try { nativeUnloadEmbedModel() } catch (_: Throwable) {}
        embedPath = null
    }

    /** 当前驻留的嵌入模型路径；null=未加载。 */
    @Volatile var embedPath: String? = null
        private set

    /**
     * 嵌入模型指纹的**唯一取值处**（建库与检索都走它）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么必须"只有一处"
     * ═══════════════════════════════════════════════════════════════════════
     * 用户报的现象：**建库成功，检索时说"嵌入模型或切块参数与建库时不同"**。
     * 这类 bug 的形态只有一种 —— 建库时算出的指纹与检索时算出的指纹**不是同一份**：
     *
     *   · `embedPath` 两处拼法不同（一个规范化、一个是原样）；
     *   · 一处用 `embedDim`、另一处用 `embedOutDim` 或反了；
     *   · 池化类型 / 模型 stat 只在一处取过（另一处漏了 → 一个写值、一个写空串）。
     *
     * 而它们的**症状完全一样**：界面说"参数变了"，用户去换模型（本来没问题），
     * 或者反复重建（重建完还是那句）—— 因为差异不在值，在**取值的路**。
     * 所以这里把"模型那一半"的取值收成**一个函数**，两处（建库 / 检索）都调它。
     * 判据正面钉住"全仓 `FpInput` 的构造点恰好只有这一个函数"
     * （`tools/rag_index/check_fp_wiring.py single-source`）。
     *
     * ⚠ [ChunkParams] 由调用方传入而不是在这里取默认值：建库时用的是**用户传进来
     * 的那一份**（可能有非默认值），检索时用的是默认值 —— 两边都从同一个入口取
     * 才不会再分岔（[buildIndex] 把它透传下来）。
     *
     * `null` 的字段 = **不知道**（`.txt_embed` 那份手写配置没有模型元数据），
     * 见 [RagIndexStore.FpInput] —— 不猜、也不写哨兵值。
     */
    private fun embedFpInput(cp: RagIndexStore.ChunkParams,
                             embedBatch: Int): RagIndexStore.FpInput? {
        val path = embedPath ?: return null
        val stat = RagIndexStore.statModel(path) ?: return null
        val (bytes, mtime) = stat
        // 维度：**同一处**取，且取的就是实际读取向量用的那个（`n_embd`）。
        // 与"模型声明的 n_embd_out"分开报（后者只做归因，不进判据的 dim 那一格），
        // 免得两个数在名字上分不清 —— 见 FpInput.embedOutDim 的说明。
        val dim = embedDim
        if (dim <= 0) return null
        return RagIndexStore.FpInput(
            embedPath = path,
            modelBytes = bytes,
            modelMtime = mtime,
            dim = dim,
            cp = cp,
            embedBytes = bytes,
            embedMtime = mtime,
            pooling = embedPooling,
            embedOutDim = embedOutDim,
            // 合批每批条数：由调用方传进来（建库用"实际跑的那个" ——
            // 已经被 [kEmbedBatchLimit] 钳过；检索用"当前配置的那个"）。两处
            // 各取一遍的话，"建库时跑 8、检索时算成 1"会让指纹**永远不匹配**
            // （= 每次打开都在重建，只是慢，所以没人会去查）。
            embedBatch = embedBatch
        )
    }

    /**
     * 把一条文本编码成**已归一化**的向量；返回 null = 这一条没有可用向量
     * （空文本 / 没加载嵌入模型 / 归一化失败）。
     *
     * 不抛异常：调用方（索引构建、检索）在后台线程上跑，未捕获会闪退；
     * 而且"这一条失败"本来就是正常路径（空段落、超长截断后为空），
     * 不该让整次索引构建挂掉。
     */
    fun embed(text: String): FloatArray? = synchronized(embedLock) {
        try {
            nativeEmbed(text) ?: run {
                // 失败时把 native 的结构化详情翻译成一句**带实值**的话 ——
                // 原文 "归一化失败（零范数/NaN）" 在"池化取错"与"维度不匹配"两个
                // 根因下都会出现，用户据此只能猜到"内存/模型坏了"，方向是错的。
                lastEmbedFailure()?.let { uiLog("[嵌入] 这一条失败：${it.describe()}") }
                null
            }
        } catch (t: Throwable) {
            uiLog("[嵌入] 编码失败：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 合批编码的结果（ISSUE #269 的另一半）。
     *
     * 为什么要一个专门的类型，而不是"`Array<FloatArray?>` 里 null 表示失败"：
     * 合批的**整批不可用**（回落逐条）与**某几条失败**是两件完全不同的事，
     * 处置也相反 ——
     *   · 整批不可用 -> 调用方**回落逐条**（那才是唯一能归因的路）；
     *   · 某几条失败 -> 调用方**照收其余几条**，把坏的那几条正文留下、标记需重建。
     * 用一个"null 既表示整批失败又表示某条失败"的容器，这两件事在调用点
     * 长得一模一样，而它们的正确处置是相反的 —— 那种情况下写对是运气。
     */
    sealed class BatchEmbed {
        /** 整批不可用（维度不匹配 / 该池化档不合批 / 批过大 / decode 失败 / 全批 tokenize 不出）。 */
        object Unavailable : BatchEmbed()

        /**
         * 批跑完了。`vectors[i]` 与输入的 `i` 一一对应；失败的那几条是 `null`
         * （**不会**是零向量 —— 零向量在这里没有区分能力，见 [embedBatch] 的说明）。
         */
        class Ok(val vectors: List<FloatArray?>) : BatchEmbed()
    }

    /**
     * 把 n 条文本**一次前向**编码（ISSUE #269 的另一半，PR #273 明确没走的那条）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么它必须存在：每条一次 `llama_decode` 的固定开销与 token 数**无关**
     * ══════════════════════════════════════════════════════════════════════════
     * PR #273 拿回来的是"线程数按本机核数"（多核那一份算力）。而流水线本身
     * 仍是**一条一次前向**：每次 decode 都要建图、分配、调度、同步 ——
     * 在 400 字（≈300 token）这种小条目上，这份固定开销占了大头。
     * 合批就是把这 n 份开销压成一份。
     *
     * 返回 [BatchEmbed.Unavailable] 时调用方**必须回落逐条**（不是"这一批全失败"）：
     * 单条路径有完整的失败归因（`lastEmbedFailure`），合批这一层的失败信息量
     * 少得多，把它当成"全批失败"会让用户看到"一批一批地坏"，而真相只是
     * "这批不适合合批"。
     *
     * `dim` 不在这里取，由调用方传：它必须与**落盘时**写进索引头的那个数
     * 同一个值（`buildIndex` 里已经有一个 `dim`），这里再取一次就有分岔的余地。
     */
    fun embedBatch(texts: List<String>, dim: Int): BatchEmbed = synchronized(embedLock) {
        if (texts.isEmpty() || dim <= 0) return@synchronized BatchEmbed.Unavailable
        try {
            val flat = nativeEmbedBatch(texts.toTypedArray()) ?: return@synchronized BatchEmbed.Unavailable
            // ⚠ 长度必须是**严格的** `texts.size * dim`：写了坏条目也是这个长度
            //（它的那一段是全零，见 native 侧）。长度对不上说明协议碎了，
            // 这时候**回落逐条**而不是"按能读多少读多少" —— 后者会让向量与正文
            // 整体错位，而错位不报错。
            if (flat.size != texts.size * dim) {
                uiLog("[嵌入] 合批返回长度不对（${flat.size} != ${texts.size} * $dim），回落逐条")
                return@synchronized BatchEmbed.Unavailable
            }
            val failed = parseBatchFailed(nativeEmbedBatchFailed())
            val out = ArrayList<FloatArray?>(texts.size)
            for (i in texts.indices) {
                val v = FloatArray(dim)
                System.arraycopy(flat, i * dim, v, 0, dim)
                // ⚠ **两条**判据都要，各自堵一个方向（与单条路径的 `norm==0` 同源）：
                //   · 下标在 `failed` 里（native 明确报了失败）；
                //   · 向量是**全零** —— 这一条是兜底的，因为它不需要 native
                //     把下标报对。合批里失败条目的那一段就是全零，
                //     而若 `nativeEmbedBatchFailed()` 解析失败（`parseBatchFailed`
                //     整段作废成空集），只看下标就会把那些全零当成**合法向量**
                //     收进索引 —— 而归一化后的全零向量与任何查询的余弦都是
                //     0/0 = NaN，排序未定义（正是 l2_normalize 拒绝它的理由）。
                //     单条路径**天然**有这道闸（native 返回 null），合批这条
                //     扁平数组没有，所以必须在这里补上。
                if (failed.contains(i) || isAllZero(v)) { out.add(null); continue }
                out.add(v)
            }
            if (failed.isNotEmpty()) {
                uiLog("[嵌入] 合批里 ${failed.size}/${texts.size} 条失败（下标 ${failed.joinToString(",")}），" +
                    "正文保留、这些条目的向量缺席")
            }
            BatchEmbed.Ok(out)
        } catch (t: Throwable) {
            uiLog("[嵌入] 合批编码失败：${t.javaClass.simpleName}: ${t.message}（回落逐条）")
            BatchEmbed.Unavailable
        }
    }

    /**
     * 「同一批文本，逐条编 vs 合批编，向量是不是**逐位相同**」的真机对照
     * （ISSUE #269 第 5 步）—— 把 0.9.218 里那条**待验断言**量出来。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须有这个入口（而不是"改批大小以后眼睛看看检索还行"）
     * ══════════════════════════════════════════════════════════════════════
     * 0.9.218 把批大小 `g` 单独定级（`FpDiff.onlyBatch`），但**不授权沿用**：
     * 「不同批大小下算出的向量逐位相同」这个断言**尚未在真机上验过**。
     * 断言未验就沿用，索引会变成两种语义拼起来的 —— 症状是"检索分数像随机、
     * 接口 200、日志干净"。而"眼睛看看检索还行"验不了这件事：两种向量混用时
     * 分数**看着像个正常数**，只有逐位比才能说清。
     *
     * 所以这里把两条路径**在同一台机器、同一次调用里**跑一遍：
     *   · `embed`      —— 逐条路径（每次一条，`nativeEmbed`）；
     *   · `embedBatch` —— 合批路径（一次 n 条，`nativeEmbedBatch`）；
     * 然后把两个向量表交给 [EmbedParity.compare] 逐位比。
     *
     * ⚠ 顺序**固定**为"先逐条、后合批"（不是"哪条路先跑"随便）：
     *   KV / 中间缓冲在两条路之间共享，先跑谁**理论上**不影响已归一化的输出，
     *   但一旦有残留状态（上一批的 KV 没清干净），"先跑的那条路"会是被污染的
     *   那一侧。固定顺序让这个**偏差方向**在结果里恒为同一侧 —— 复现时可判。
     *
     * `texts` 由调用方给（诊断入口用同一批文本、同一个批大小）。返回 null =
     * 没加载嵌入模型 / 维度取不到（那时对照没有意义，如实说没有，而不是报"相同"）。
     */
    fun probeEmbedBatchParity(texts: List<String>, label: String = ""): EmbedParity.Report? {
        val dim = embedDim
        if (dim <= 0 || texts.isEmpty()) {
            uiLog("[嵌入] 逐位比对跳过：${if (dim <= 0) "维度取不到" else "没有可比对的文本"}（不报结论）")
            return null
        }
        // 逐条那一侧：用与建库**同一条**编码函数（不是另写一遍 `nativeEmbed`
        // 直调）—— 另写一遍就等于比的是"另一段代码算出来的向量"，
        // 而它和后者的差异与批大小无关。
        //
        // ⚠ 这里**每条编两次**（`aRepeat`）：第二遍仍是同一条路、同一台机、
        // 同一条文本。它回答的是**另一个问题** —— 首轮真机那条 `firstDiffDim=0`
        // 到底是"批布局改语义"还是"编码本身不确定"。只跑一次时这两件事
        // 混在一个读数里，分不开（见 `EmbedParity.Report.repeatRows`）。
        // **合批那条路下面也重复一遍**（`aBatchedRepeat`）：两条路各查各的
        // 重现性，少一条都答不了"差异来自哪里"（见 `Report.repeatBatchedRows`）。
        val singles = ArrayList<FloatArray?>(texts.size)
        val repeats = ArrayList<FloatArray?>(texts.size)
        for (t in texts) {
            singles.add(embed(t))
            repeats.add(embed(t))
        }
        // 合批那一侧：同一批文本、同一次前向。
        val batched = when (val r = embedBatch(texts, dim)) {
            is BatchEmbed.Ok -> r.vectors
            else -> {
                // 合批整批不可用（回落逐条）时**不能**报"不相同"——
                // 那一侧根本没有向量，是**缺失**。如实说，让用户先看
                // `[embedBatch]` 那行（它给了不可用的原因）。
                uiLog("[嵌入] 逐位比对：合批那一侧整批不可用（见上面的 [embedBatch] 行）—— 本次无结论")
                return null
            }
        }
        // ⚠ **合批那条路也要重复一遍**（同一批文本、再走一次 `embedBatch`）。
        // 只重复逐条那条路时，读数里还剩一格没被排掉：合批自己复现不复现得了自己。
        // 那一格有差异的话，"跨路不同"里就混着"合批自身不确定"这一份 ——
        // 而它与"批布局改语义"是两件事（前者先查合批，后者才谈 `g` 收不收窄）。
        // 合批第二次仍整批不可用 = 这一格**没测到**（不是"全绿"）：如实报空。
        val batchedRepeat = when (val r2 = embedBatch(texts, dim)) {
            is BatchEmbed.Ok -> r2.vectors
            else -> {
                uiLog("[嵌入] 逐位比对：合批那一侧第二次没跑出来 —— " +
                    "「合批自己重复」这一格**没测到**（不报成全绿）")
                emptyList()
            }
        }
        val report = EmbedParity.compare(
            singles, batched, dim, aRepeat = repeats, aBatchedRepeat = batchedRepeat)
        val tag = if (label.isEmpty()) "" else "（$label）"
        uiLog("[嵌入] ${report.describe()}$tag")
        // 逐条明细（同一次调用里紧接着那行结论打）—— `describe()` 只说得出计数，
        // 而"是哪一条不同"直接决定下一步：整批都不同 = 合批这条路整体不可信；
        // 只有某几条不同 = 去查那几条（不等长合批的 padding / 超长那条的截断点）。
        // 首轮真机读数是「6 条里 1 条不同」，而当时**没有任何地方**说得出是哪一条
        // —— `Row.maxAbsDiff` / `Row.firstDiffDim` 算了却没人读（同"读数要有落点"）。
        // 全绿时这一节是空的（几十条逐位相同会把上面那行结论淹掉）。
        for (line in report.detailLines()) uiLog("[嵌入] $line")
        // 结论同时落进对外读数（`/health` 的 embed_parity_*）—— 只在日志里说
        // 时，用户要先动手翻日志，而这条结论是**跨版本**要对的那条（见 Last 的说明）。
        //
        // ⚠ 只把**长短悬殊那组**落进对外读数，缺 label 时落第一组。`/health`
        // 只有一组字段，而两组样本**必须分开报**（见 `EmbedParityProbe.SAME_LEN`
        // 对象头：并成一批时等长那几条会被悬殊那几条淹掉）。
        //
        // ⚠⚠ 取哪一组**不是自明的**，这里原来取的是**等长**那组，理由是"它把
        // 批布局这一个变量单独摆出来" —— 那条理由本身没错，但它答的是**另一个
        // 问题**。真机现场（PR #301 那本 `/health`）把它暴露出来了：报告里
        // 「逐位相同 4/4 条 —— 整批逐位相同，批大小是纯加速旋钮（可考虑沿用）」
        // 取自**等长**组，而同一份日志里长短悬殊那组是「真的不同 1 条、
        // 最大分量差 4.510e-03」。于是读者拿到的结论恰好是**用构造上偏稳的
        // 那组去回答"换批大小会不会改答案"** —— 而这格存在的全部理由就是判
        // 「`g` 能不能按库放行」。等长组按构造就是稳的（每条字符数全等，
        // 唯一变量只剩批布局），它永远说不出"会不会变"。
        //
        // 与检索那格（`probeRetrieveParity`）**取相反的一组**、理由同源：
        // 那边取长短悬殊（"答案会不会变"），这边也取长短悬殊（"向量会不会变"）——
        // 两格问的都是"能不能放行 `g`"，而放行与否取决于**最坏**的那组。
        // 落盘时把组名一起带出去（`Last.record(report, label)`）：只报数字不报
        // 来源时，"长短悬殊也稳"会被从必然稳的那组推出来（见 `State.group`）。
        if (label.isEmpty() || label == LONG_SHORT_LABEL) EmbedParity.Last.record(report, label)
        return report
    }

    /**
     * 「**换批大小重建索引后，检索结果还是不是同一些行**」的真机对照
     * （ISSUE #269 第 5 步：与 [probeEmbedBatchParity] 配对的那一半）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么这一层必须存在（`probeEmbedBatchParity` 答不了它）
     * ═══════════════════════════════════════════════════════════════════════
     * [probeEmbedBatchParity] 量的是"**同一批文本**、逐条 vs 合批，向量逐位相同吗"。
     * 而"改批大小要不要重建索引 / 能不能沿用"要问的是**另一句**：
     * "两套索引**搜出来的东西**还是不是同几条"。这两句之间**没有蕴含关系**：
     *
     *   · 向量逐位不同（`firstDiffDim=0`、`4.5e-03`）**不代表**答案会变 ——
     *     4e-03 的差落在 top-k 的第几名上，是一个**要量**的问题；
     *   · 向量逐位相同也**不代表**答案一样（索引里还有正文行序、切分、分数比较）。
     *
     * 做法：把 [texts] 按 [batchA] / [batchB] 各编一遍（两套向量），再用同一组
     * [queries] 各自检索，比"命中的行下标序列"。判据落在**集合**上（见
     * [EmbedRetrieveParity] 对象头：集合变了 = 答案变了；只名次变了 = 打平处换序）。
     *
     * ⚠ **不落盘、不碰知识库**：两套向量都只在内存里（这条对照是"排除法"，
     * 不是建库）。落盘会让它变成一个"用户没点过的知识库"，而它只是诊断。
     *
     * ⚠ 两套都**复用同一条编码入口** [embedBatch]（不是另写一遍 `nativeEmbed`）——
     * 另写一遍就变成比"另一段代码算出来的向量"，与批大小无关（同
     * [probeEmbedBatchParity] 里那条说明）。
     *
     * 返回 null = **没得到结论**（维度取不到 / 没有文本 / 没有查询 / 某一侧整批
     * 不可用）。这一格**必须**是 null 而不是"空结论"：后者会被读成"答案一样"。
     */
    fun probeRetrieveParity(
        texts: List<String>,
        queries: List<String>,
        batchA: Int,
        batchB: Int,
        topK: Int = kRetrieveParityTopK,
        label: String = "",
    ): EmbedRetrieveParity.Report? {
        val dim = embedDim
        if (dim <= 0 || texts.isEmpty() || queries.isEmpty()) {
            uiLog("[检索对照] 跳过：${if (dim <= 0) "维度取不到" else "没有文本或没有查询"}（不报结论）")
            return null
        }
        // ── 两侧批大小必须**真的不同**，否则这一格测的是"自己对自己" ──────────
        // ⚠ 这是一道**兜底闸**（真机那轮闪退现场逼出来的）：入口写的本来就是
        //   "两侧各编一套、只差批大小"，可一旦两侧取到同一个数（真机上真发生过：
        //   `otherBatch = if (curBatch == baseBatch) 1 else baseBatch` 在
        //   用户把配置设成 1 时让两侧都成了 8），比的就是"8 对 8" —— 命中集合
        //   **恒等**、`answerChanged` 恒 0。那是一个**假的**"答案没变"，比"没有结论"
        //   更坏：没有结论时人还会去查，假成立时人会据此**放行 `g`** —— 而这条
        //   结论正是"`g` 能不能按库放行"的全部依据。
        //   所以这里当场拦下并报**"没量到"**（不是"一致"）：两侧相同 = 这一格
        //   没有任何信息量，只有 entry 侧改对（`EngineActivity` 的推导）才算修好，
        //   入口这层负责让"忘记改"时也**不会**报出一个假的绿。
        if (batchA == batchB || batchA <= 0 || batchB <= 0) {
            uiLog("[检索对照] 没量到：两侧批大小相同（$batchA vs $batchB）—— " +
                "它测的是『自己对自己』，命中集合恒等，不报结论")
            return null
        }
        // 每一侧都按**同一个入口**编（顺序也同一份）：两套向量之间只差批大小。
        val vecA = embedAllInMemory(texts, dim, batchA) ?: return null
        val vecB = embedAllInMemory(texts, dim, batchB) ?: return null
        // ⚠ 查询向量**用哪一侧的批大小**：用 [batchB] 那侧 —— 真机上的场景是
        // "索引是按 batchA 建的，现在配置改成了 batchB"，于是查询来自新配置那一批
        // （那正是索引里会混着两种语义的那个场景）。用 batchA 查会让两侧都"自洽"，
        // 恰好把要量的那件事抹掉。
        val qvRows = embedRowsInMemory(queries, dim, batchB)
            ?: return null
        if (qvRows.any { it == null || it.size != dim }) {
            uiLog("[检索对照] 查询向量没算出来 —— 本次无结论")
            return null
        }
        val qv = qvRows.map { it!! }
        val indicesA = ArrayList<List<Int>>(queries.size)
        val indicesB = ArrayList<List<Int>>(queries.size)
        val scoreA = ArrayList<List<Float>>(queries.size)
        val scoreB = ArrayList<List<Float>>(queries.size)
        for (q in qv) {
            val a = RagIndexStore.rank(vecA, texts.size, dim, q, topK, 0f)
            val b = RagIndexStore.rank(vecB, texts.size, dim, q, topK, 0f)
            indicesA.add(a.hits.map { it.index })
            indicesB.add(b.hits.map { it.index })
            scoreA.add(a.hits.map { it.score })
            scoreB.add(b.hits.map { it.score })
        }
        val report = EmbedRetrieveParity.compare(
            queries, indicesA, indicesB, batchA, batchB, topK, scoreA, scoreB)
        val tag = if (label.isEmpty()) "" else "（$label）"
        uiLog("[检索对照] ${report.describe()}$tag")
        for (line in report.detailLines()) uiLog("[检索对照] $line")
        // ⚠ 只把**长短悬殊**那组落进对外读数（缺 label 时落第一组）—— 与
        //   `probeEmbedBatchParity` 里那条「只落等长那组」同源，但**取的是相反的一组**：
        //   · 向量那格取**等长**组：它把"批布局"这一个变量单独摆出来；
        //   · 检索这格取**长短悬殊**组：等长组按构造就是稳的（见 `SAME_LEN` 对象头），
        //     把它落进 `/health` 等于**用必然成立的那组去回答"答案会不会变"**。
        //   ⚠ 这条闸是**后加**的：`Last.record` 原先在两个调用点上各记一次、**无条件
        //     覆盖**，于是 `/health` 永远报的是后跑的那组（等长）—— 而长短悬殊那组
        //     （唯一能改变答案的）的读数**一次都没进过** `/health`。
        if (label.isEmpty() || label == LONG_SHORT_LABEL) EmbedRetrieveParity.Last.record(report)
        return report
    }

    /**
     * 把 [texts] 逐条摊成每行一个向量（**只在内存里**，不落盘）。
     *
     * ⚠ [batch] 决定的是"几条一起进 `embedBatch`"，**不是"编几次"**：语料与
     *   查询向量**都要**走这个入口。原来查询那一句写的是
     *   `embedBatch(listOf(q), dim)` —— 那是**写死的 1 条一批**，与
     *   "查询来自 [batchB] 那一侧"这条要求（见 `probeRetrieveParity` 里那段说明）
     *   只在 `batchB == 1` 时才偶然一致。用户配置不是 1 时，这一格比的就成了
     *   "batchA 建的库 vs 1 条一批的查询"，与要量的那件事无关 —— 而它**看起来仍然成立**。
     *
     * 返回 null = **某一批整批不可用**（那就不该拿去比检索 —— 结果是"缺失"
     * 不是"不同"，同 [probeEmbedBatchParity] 的规矩）。
     */
    private fun embedRowsInMemory(texts: List<String>, dim: Int, batch: Int): List<FloatArray?>? {
        val rows = if (batch > 0) batch else kEmbedBatchDefault
        val out = ArrayList<FloatArray?>(texts.size)
        var i = 0
        while (i < texts.size) {
            val end = minOf(i + rows, texts.size)
            val r = embedBatch(texts.subList(i, end), dim)
            if (r !is BatchEmbed.Ok) {
                uiLog("[检索对照] 批大小 $rows 那一侧第 ${i / rows + 1} 段整批不可用 —— 本次无结论")
                return null
            }
            for (k in 0 until (end - i)) out.add(r.vectors.getOrNull(k))
            i = end
        }
        return out
    }

    /**
     * 把 [texts] 按 [batch] 条一批编完，摊成一条扁平的 `FloatArray`
     * （**只在内存里**，不落盘；检索要的是这种布局）。
     *
     * 返回 null = **某一批整批不可用**或**某条没有向量**（那就不该拿去比检索 ——
     * 结果是"缺失"不是"不同"，同 [probeEmbedBatchParity] 的规矩）。
     */
    private fun embedAllInMemory(texts: List<String>, dim: Int, batch: Int): FloatArray? {
        val rows = if (batch > 0) batch else kEmbedBatchDefault
        val vectors = embedRowsInMemory(texts, dim, batch) ?: return null
        val out = FloatArray(texts.size * dim)
        for ((k, v) in vectors.withIndex()) {
            if (v == null || v.size != dim) {
                uiLog("[检索对照] 批大小 $rows 那一侧第 ${k + 1} 条没有向量 —— 本次无结论")
                return null
            }
            System.arraycopy(v, 0, out, k * dim, dim)
        }
        return out
    }

    /** 检索对照用的查询集（写死 —— 与 [EmbedParityProbe] 同一条理由：跨版本要对上同一张图）。 */
    val RETRIEVE_PARITY_QUERIES: List<String> = EmbedParityProbe.RETRIEVE_QUERIES

    /**
     * 检索对照取前几名（与用户实际用的 top-k 同一档，见 `RagInject.DEFAULT_TOP_K`）。
     *
     * 数值出处是 [EmbedLimits.kRetrieveParityTopK] —— 搬过去是为了让宿主单测
     * 编得过（本对象依赖 Android，编不到宿主上）。见 `EmbedLimits` 的对象头。
     */
    const val kRetrieveParityTopK = EmbedLimits.kRetrieveParityTopK
    /**
     * 「等长」那组样本的标签（用于日志回显与界面分组，**不落** `/health`）。
     *
     * ⚠ 字面量的**唯一出处**是 [EmbedLimits.kSameLenLabel]（转发，不在这里
     *   另写一份）：`HealthCheck.kt` 要认出组名按组换措辞，而它编在宿主上
     *   （**不带** `LlmEngine.kt`）—— 组名只写在这里时那句引用编不过。
     */
    const val SAME_LEN_LABEL = EmbedLimits.kSameLenLabel

    /**
     * 「长短悬殊」那组样本的标签 —— 也是**落进 `/health`** 的那一组
     * （`probeEmbedBatchParity` / `probeRetrieveParity` 各自只落这一组）。
     *
     * ⚠ 与 [SAME_LEN_LABEL] **不是同一个常量**、也**不是同一组样本**：两格
     *   都取长短悬殊那组（放行 `g` 取决于**最坏**的那组），等长组只作对照 ——
     *   共用一个名字会让"改的是哪一格"看不出来（本仓库反复记录的那族）。
     *   出处同 [SAME_LEN_LABEL]：[EmbedLimits.kLongShortLabel]。
     */
    const val LONG_SHORT_LABEL = EmbedLimits.kLongShortLabel

    /**
     * 解析 `nativeEmbedBatchFailed()` 的下标串（逗号分隔；空串 = 全成功）。
     *
     * 单个下标解析不出来时**整段作废**（返回空集）：那种情况下"哪些条失败了"
     * 是未知的，而返回一个**部分**集合会让调用方以为其余都成功了 ——
     * 失败条目的正文会带着"我已成功"的假象进索引（或是被当成成功计数）。
     * 未知就该是未知，宁可让调用方多判一轮（它会发现下标集合与全零段对不上）。
     */
    /**
     * 这一段向量是不是**全零**（零向量的方向不存在，必须丢弃）。
     *
     * 与 native 的 `l2_normalize` 同一条判据（那里全零会返回 false）：合批这条
     * 扁平数组里没有"null"这个位置，失败条目只能体现为全零 —— 于是"全零"
     * 在这里必须被判成失败，否则它会被当成合法向量收进索引（见 `embedBatch`
     * 里那段说明）。`Float` 的 `-0.0f == 0.0f` 成立，所以这一条也能抓住
     * "带负号的全零"（那种值打印起来像一个正常数）。
     */
    private fun isAllZero(v: FloatArray): Boolean {
        for (x in v) if (x != 0.0f) return false
        return true
    }

    private fun parseBatchFailed(raw: String): Set<Int> {
        if (raw.isEmpty()) return emptySet()
        val out = LinkedHashSet<Int>()
        for (piece in raw.split(',')) {
            val v = piece.trim().toIntOrNull() ?: return emptySet()
            out.add(v)
        }
        return out
    }

    /**
     * 把一串 token id 还原成文本（用嵌入模型那一份 vocab）。
     *
     * 返回 null = 还原失败（没加载嵌入模型 / 有 id 越界 / 数组为空）。
     * `EmbeddingsApi` 拿它把 `input: [1,2,3]` 变成文本后再走 [embed] ——
     * 这样"id 数组"与"字符串"两种输入最终**共用同一条**编码路径，
     * 不会出现"两条路各算一遍、结果却不一样"的分岔。
     */
    fun detokenize(ids: IntArray): String? = synchronized(embedLock) {
        if (ids.isEmpty()) return@synchronized null
        try {
            nativeDetokenize(ids).ifEmpty { null }
        } catch (t: Throwable) {
            uiLog("[嵌入] id 还原失败：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  知识库索引构建（RAG 阶段 1）—— 分块 -> 逐块 embed -> 落盘
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 为什么"构建"必须是一个**独立于检索**的显式动作：
    // 索引里的向量是**用某个特定 embed 模型 + 某组切块参数**算出来的。把它绑在
    // 请求路径上（"检索时发现没有索引就顺手建"）会有两个后果 —— 第一次提问要
    // 等整份文档编码完（长文档 = 分钟级，且期间 HTTP 请求超时、客户端重试、
    // 于是又建一遍），以及"重建"这件事对调用方完全不可见（它只是慢）。
    // 所以构建是显式入口，检索侧只负责"有就用、没有就当没配"。

    /**
     * 构建（或重建）一个知识库的索引。
     *
     * 返回 null = 成功；否则是给用户看的失败原因。**不抛异常**（后台线程上跑）。
     *
     * 关键约束（每一条都对应一类静默失效）：
     * - **先加载嵌入模型**再算：没加载就 embed 会逐条返回 null，于是整库向量全空，
     *   而"建库成功"照样返回 —— 表现为"检索永远为空"，看不出是模型没加载。
     * - 维度从 [embedDim] 取，不写死：写死会在换模型后把错维度的向量落盘，
     *   而 decode 之后维度"对得上"（因为索引头也是错的），点积读的是错位 float。
     * - 空条目**丢弃**；编码失败的条目**正文留下、向量缺席、整库标记需重建**
     *   （见 [RagIndexStore.savePartial]）：塞零向量会让余弦变 NaN，而**整条丢掉**
     *   会让用户看不见自己敲的那条（100 条坏 3 条时只能二分重试）。
     * - 分块结果为空（文档太短/全空白）时**不落盘**（见 [RagIndexStore.Load.empty] 的三态）：
     *   落一个 count=0 的文件是合法的（"库确实是空的"），但"用户给了内容却切不出块"
     *   必须报错，否则用户以为建好了。
     *
     * 返回值契约（UI 依赖它区分三种结局，**不能只看 null/非 null**）：
     * - `null`            —— 全部成功，索引可直接检索；
     * - `"成功 N 条，…"` —— **部分失败**：正文已全部落盘，索引待重建（用户改掉坏的那几条再重建）；
     * - 其它字符串         —— 真失败（没写成任何东西）。
     */
    fun buildIndex(ctx: android.content.Context, name: String, docs: List<RagIndexStore.Entry>,
                   cp: RagIndexStore.ChunkParams = RagIndexStore.ChunkParams(),
                   nCtx: Int = 0,
                   embedBatchSize: Int = kEmbedBatchDefault): String? {
        val dir = RagIndexStore.dirOf(ctx, name)
            ?: return "库名非法（只允许字母/数字/中文/-/_/.，且非空）"
        if (!hasEmbedModel) return "尚未加载嵌入模型（索引必须用它算向量）"
        val dim = embedDim
        if (dim <= 0) return "嵌入模型维度取不到（加载可能不完整）"

        // ── 批大小：归一到 native 的实际上限（ISSUE #269 第 2 步）───────────
        // 调用方给的是**用户意愿**（设置页填的数），而能不能合批由
        // native 的 `n_seq_max`（= [kEmbedBatchRows]，建 ctx 时写死）决定 ——
        // 超了就是每批都被拒、回落逐条（合批白做而界面看不出）。
        // 所以这里**当场钳到上限**并记一笔日志：用户填了 16 而上限是 8，
        // 他必须在日志里看到"按 8 跑"—— 否则他会以为 16 生效了，
        // 而真相是设置被静默地忽略了（与本 issue 最初那个
        // "线程数输入框对建库完全无效"是同一个病）。
        val batch = kEmbedBatchLimit(embedBatchSize)
        if (batch != embedBatchSize) {
            uiLog("[知识库] 批大小 $embedBatchSize 超过 native 上限 ${kEmbedBatchRows} 或非正，" +
                "已按实际能跑的 $batch 跑（合批上限由建 ctx 时的序列数决定）")
        }

        // 指纹只给"与模型有关的那一半"：**正文那一半由 save 现算**（它才知道最终
        // 写下去的字节）。让调用方自己算完整指纹有一个致命窗口 —— 它算的 hash 与
        // 实际写下的字节之间隔着一次 encodeTexts，任何一边改了编码，写完的索引
        // 就**立刻失效**，症状是"建库成功、检索永远为空"，没有日志指向这里。
        //
        // ⚠ 这一半**只从这里取**（[embedFpInput]）：检索侧走的是同一个函数。
        // 两处各写一遍的形态，正是"建库成功 → 检索说『模型或参数已变』"的成因。
        val fp = embedFpInput(cp, batch)
            ?: return "嵌入模型文件不可读或维度取不到：${embedPath ?: "?"}"

        // ── 分块 + 逐条编码 ────────────────────────────────────────────────
        // 编码在 embedLock 内逐条做（embed() 自己加锁），所以这里不再包一层：
        // 包了会与 embed() 的 synchronized 互斥同一把锁（可重入，无害），
        // 但会让"构建期间无法 abort/卸载嵌入模型"这个窗口显得像设计而非巧合。
        val texts = ArrayList<String>()
        val sources = ArrayList<String>()
        var totalChunks = 0
        var skipped = 0
        // 硬截计数：**一个库一条日志**，不是一块一条（块多的时候会把日志刷爆，
        // 而用户要的是"这份文档为什么检索不准"这个整体判断）。
        var hardCuts = 0
        // 被 maxChunks 截住的条目：**不是块数，是条目数**。用户能动手的对象是
        // "哪一条文档太大"，报"共被截掉多少块"对他没有意义（他也改不了块数）。
        var truncatedDocs = 0
        for (d in docs) {
            if (d.text.isBlank()) continue
            // 分块按字符算（与 rag_index.h 同一口径）；native 侧 tokenize 后
            // 超 ctx 会截断并有日志，所以这里不必精确控 token —— 但块必须明显小于
            // n_ctx，否则每块都被截断，语义只剩开头。
            val hc = IntArray(1)
            val tr = BooleanArray(1)
            for (piece in chunk(d.text, cp, hc, tr)) {
                totalChunks++
                texts.add(piece.text)
                sources.add(d.source)
            }
            hardCuts += hc[0]
            if (tr[0]) truncatedDocs++
        }
        if (totalChunks == 0) return "没有可索引的内容（文档全为空或切不出块）"
        // ── 硬截要说话（用户拍板：记进日志）─────────────────────────────────
        // 为什么这条日志值钱：硬截 = "窗口内一个句读都没有"，那种块的开头/结尾
        // 是**凭空截断**的 —— 语义被拦腰截断，检索命中率天生偏低。而它此前
        // **没有任何痕迹**：用户看到的是"这份文档有些内容搜不到"，据此只能猜
        // （换模型？改阈值？重写文档？），方向全靠碰。
        // 一句 `N/M 块是硬截的` 就把"哪一份文档、多严重"变成可操作的信息
        // （给那份文档补换行/句读，或干脆接受它的召回率）。
        // ⚠ 只统计**切块阶段**的硬截；`skipped`（编码失败）是另一件事，分开报。
        if (hardCuts > 0) {
            uiLog("[知识库] $name 有 $hardCuts 块是硬截的（窗口内无句读，" +
                "共切 $totalChunks 块）——这些块的语义可能被截断，补上换行/句读可改善召回")
        }
        // ── 被 maxChunks 截断也要说话（用户拍板：记进日志）──────────────────
        // 为什么这条**比硬截那条更值钱**：硬截只是"块边界糙"，内容全在；
        // 截断是**内容真的没进索引** —— 文档后半段一个字节都没算向量。
        // 而它此前**完全没有痕迹**：`chunk()` 撞上限就 return，`buildIndex`
        // 只报 `totalChunks`，于是"这份文档只索引了前半段"与"文档本来就这么多"
        // 在日志与界面（条目数是**正文行数**，不是块数）上**逐字相同**。
        // 用户唯一能观察到的现象是"后半段搜不到"，而那与"文档里没有"一模一样 ——
        // 只能靠反复换关键词去猜，方向全靠碰。
        // 一句 `N 条被截断（单条上限 M 块）` 就把"是哪一份、为什么、怎么办"
        // 变成可操作的信息：拆成几条小的，或者接受"这份只索引前半段"。
        // ⚠ 与硬截**分开报**：两者根因不同（句读少 vs 文档太大），处置也不同。
        if (truncatedDocs > 0) {
            uiLog("[知识库] $name 有 $truncatedDocs 条被截断：单条上限 ${cp.maxChunks} 块" +
                "（约 ${cp.maxChunks} 块 × ${cp.targetChars} 字），超出的部分**未进索引**——" +
                "拆成几条再建库，或缩短这一条")
        }

        // ── 续编（本次新增）：能不能接着上次编 ────────────────────────────────
        // ⚠ 判据**只有一条**：把"将要写下的正文"与磁盘上那份索引的指纹比一次。
        //   指纹里已经含了正文 hash + 正文行数 + 模型身份 + 切块参数 + 批大小——
        //   任何一项与上次落盘时不一致，[RagIndexStore.load] 就交出 none（不是 partial），
        //   于是这里退化成"从头编"。**不许**用"块列表看起来一样"来近似它：
        //   那正是本仓库反复吃过的"两个数各推一遍，分岔时静默失效"。
        //
        // 为什么在**编码开始之前**就要把整份正文编出来：指纹里的正文 hash 是
        // 完整正文的 hash（[RagIndexStore.savePartial] 也是这么写的），所以
        // "这次会写下什么字节"必须在续编判据之前就知道。这一步只编码 JSON、
        // 不算向量，相对一次前向可以忽略。
        val allRows = ArrayList<RagIndexStore.Entry>(texts.size)
        for (k in texts.indices) allRows.add(RagIndexStore.Entry(texts[k], sources.getOrElse(k) { "" }))
        // ⚠ 正文只编一次（两次调 `encodeTexts` 是白算，且哪天它带了随机/时间成分就会分岔）
        val allBytes = RagIndexStore.encodeTexts(allRows).toByteArray(Charsets.UTF_8)
        val fullFp = RagIndexStore.fingerprintOf(
            fp, RagIndexStore.textsHashOf(allBytes), RagIndexStore.rowsOf(allBytes))
        // 前缀：上次已经算过的向量（半份索引）。null = 没有可续的（首建 / 指纹不符 / 齐整）。
        val prefix = RagIndexStore.loadPrefix(ctx, name, fullFp)
        val startFrom = if (prefix == null) 0 else RagIndexStore.resumeFrom(prefix.first, texts.size)
        // ── 作废闸要**出声**（不许静默从头）──────────────────────────────────
        // 磁盘上明明有索引、却续不上（= 判据判了"从头"）—— 这件事必须说出来，
        // 否则用户遇到的是"我明明点的续跑，它又跑了两小时"，而日志里没有一行
        // 指向原因。作废的三类来源各不相同，处置也不同：
        //   · 换了嵌入模型 / 切块参数 / 批大小 -> 指纹不符（[RagIndexStore.lastDiff] 指名字段）；
        //   · 正文改过（条数或内容变了）        -> 正文 hash 不符；
        //   · 索引本来就齐整                    -> 没有缺口，不该续（上面 loadPrefix 返回 null）。
        // ⚠ 只在前两种情况出声（第三种"齐整"不是作废，是"已经编完了"，报它反而制造困惑）。
        if (prefix == null && RagIndexStore.isPresent(ctx, name)) {
            val d = RagIndexStore.lastDiff
            if (!d.same) {
                uiLog("[知识库] $name 没有可续的半份索引（${d.field}：${d.detail}）—— 从头编")
                // ── "只差批大小"要**多说一句"能救多少"**（ISSUE #269 第 5 步）─────
                // `g` 与别的字段是两类东西（见 [RagIndexStore.FpDiff.onlyBatch]）：
                // 换模型 / 正文改过 -> 那 N 块**真的**救不回来；而只差批大小 ->
                // 那 N 块**语义同源**，只是"不同批大小的向量是否逐位相同"还没验
                //（第 5 步那个真机逐位比对）。不验之前按**不可沿用**处置（安全），
                // 但用户必须被告知**有多少块是可救的** —— 否则他看到一行"从头编"
                // 只能认为这几十几分钟全白费，而真相是"换个批大小回来就能续上"。
                if (d.onlyBatch && d.salvage > 0) {
                    uiLog("[知识库] $name ↑ 其中前 ${d.salvage}/${texts.size} 块**语义同源**（只差批大小）：" +
                        "把「每批编几条」改回来（或重建）即可沿用；在此之前不同批大小的向量" +
                        "是否逐位相同**尚未验证**（ISSUE #269 第 5 步），所以这次不敢沿用")
                }
            }
        }
        if (prefix != null && startFrom > 0) {
            // ── 续编要**说出来**（不许静默从头）───────────────────────────────
            // 用户要的就是"下次能接上"，而他判断"接上了没有"的唯一依据是这一行。
            // 只报"开始建索引"在他眼里与"从头跑"逐字相同 —— 而两者耗时差几十分钟。
            uiLog("[知识库] $name 检测到上次建到 $startFrom/${texts.size} 块（指纹一致），" +
                "从第 ${startFrom + 1} 块接着编，前 $startFrom 块沿用上次的结果")
        }

        // 计数：`okCount` 含**沿用**的前缀条数（前 `startFrom` 块），不含失败的。
        var okCount = startFrom
        val rows = ArrayList<RagIndexStore.Entry>(texts.size)
        // 前缀向量原样搬运：**不重算**（重算是续编存在的意义），也不改它的顺序——
        // 向量区第 i 行必须就是正文第 i 行（见 [RagIndexStore.savePartial] 的说明）。
        if (prefix != null && startFrom > 0) {
            val pf = prefix.second
            for (k in 0 until startFrom) {
                val off = k * dim
                val v = if (off + dim <= pf.size) pf.copyOfRange(off, off + dim) else FloatArray(0)
                rows.add(RagIndexStore.Entry(texts[k], sources.getOrElse(k) { "" }, v))
            }
        }
        // ── 逐条编码，并把**失败的那几条**记下来（方案 C）──────────────────
        // 早先的实现只 `skipped++`，连"哪一条"都不记 —— 而失败详情在失败当场
        // 才拿得到，事后回不来；用户能看到的只有 UI 上那句"检查嵌入模型与内存"，
        // 而那句话在两个不同根因下都成立，等于没有排查方向。
        // 所以这里逐条收集：正文（用于定位）+ 原因（用于判断该怎么修）。
        // 失败明细带上**自己的下标**（不是靠 indexOf 反查）：同一个 EmbedFail 值
        // 可能出现多条（同一根因），`fails.indexOf(it)` 会永远命中第一条 ——
        // 于是"#N 条失败"里的 N 是错的，而它恰恰是用户去定位的那把钥匙。
        val fails = ArrayList<Pair<Int, EmbedFail>>()   // ≤ kMaxReportedFails 条，避免刷爆界面
        val failedIdx = ArrayList<Int>()                // 全部失败的下标（进 meta.json）
        // ── 进度回执（不是日志，见 [uiProgress]）────────────────────────────
        // 为什么这一屏非要有进度不可：建库的耗时由**块数**主导，而块数由文档长度
        // 决定 —— 一份 3 MB 的整篇能切出上万块，就是几十分钟。此前那几十分钟里
        // 屏幕上只有一句静态的「正在建库…」，用户唯一能得出的结论是"卡死了"，
        // 于是他去点屏幕（用户报的原文："点击卡住的页面，app 闪退"）。
        // 一个走得动的计数就把"卡死"与"慢"分开 —— 两者的处置完全不同。
        //
        // ⚠ 只走 uiProgress：见那里的注释（上万行不进 ring / 不落盘 / 不进 logcat）。
        //
        // ── 一批一批地编码（ISSUE #269 的另一半）───────────────────────────
        // 不再是"一条一次 `nativeEmbed`"，而是**一次前向编 n 条**（`kEmbedBatch`）。
        // 这一半 PR #273 明确没走，理由写在它自己的"放弃方案"里：
        // "嵌入每条是独立前向（每条前都要清 KV），合批要先证明条目间语义不串"。
        // 那件事现在**已经证明并落到判据上**了：隔离靠 `llama_batch` 的 `seq_id`
        // （库按 seq 分块建注意力掩码，不同 seq 互不可见），每条的 `pos` 从 0 起 ——
        // 见 native 侧 `nativeEmbedBatch` 与 `embed_text.h::plan_batch`。
        //
        // ⚠ 合批**不改变失败口径**：哪几条失败照样一条不漏地记进 `failedIdx`。
        // 合批这一层只是"更快的同一条路"，它的失败语义必须与逐条**逐项相同** ——
        // 否则"批大小"就渗透进索引内容了（用户改了批大小，哪几条进索引就变了，
        // 而没有任何日志指向这里）。
        // ⚠ 开工时刻取**单调钟**（不是墙钟）：这一行要报"已耗费时间"，
        //   而墙钟做差会被 NTP 跳变/用户改时间拉歪（甚至算成负数）。
        //   本仓库对此有一族专门的判据（`run_monotonic_clock_guard.sh` G1），
        //   取钟的那一处**只在这里**，`RagProgress` 只收算好的毫秒数。
        val buildT0 = System.nanoTime()
        // ── 开工前先取一份**累计**读数（"本段"是怎么来的）────────────────────
        // [EmbedBatchStats] 记的是**进程级累计**（跨多次建库累加，见它的对象头），
        // 而这行日志要说给用户听的是"我点的**这一次**建库走了哪条路"。
        // 两者不是一回事：此前拿累计值当"本轮"，于是 `合批 20/20 条（5 批）`
        // 会被读成"这次编了 20 条"，其实是第 5 次建库之后的累计 —— 而用户
        // 恰恰是拿这一行去比"批4 vs 批1"，两个几乎相等的累计值会被读成
        // "批大小没生效"（真因只是相邻两次之间只差 4 条）。
        // 所以开工先留一份基线，收尾时 [EmbedBatchStats.delta] 一减就是本段。
        val statsBefore = EmbedBatchStats.snapshot()
        // ── 定期落盘（本次新增）：中途被杀不再"从头跑" ────────────────────────
        // ⚠ 落盘粒度按**块数**，不是按条数：一条 3 MB 的长文档能切出一万块，
        //   "每 100 条存一次"在它面前一辈子碰不到（用户提的"每 100 条"是因为
        //   他导入的多是短文 —— 那是样本恰好短，不是规则）。再叠一个
        //   "至少每 3 分钟一次"兜住长尾（慢机器上 2000 块可能超过 3 分钟）。
        // ⚠ 这里落的是 [RagIndexStore.saveProgress]（正文全量、向量只留前缀），
        //   所以**不需要新文件格式**；下次 [RagIndexStore.loadPrefix] 就靠它续编。
        //   而"落一份半成品"绝不会被误当"能检索"—— 指纹里正文行数与向量行数不等，
        //   load 见到就是 [RagIndexStore.Load.partial]。
        // ⚠ "正文全量"这四个字是**契约**，不是形容词：写成前缀就是**删正文**
        //   （见下面那个 bug 的说明）。
        val persistEveryChunks = kEmbedResumePersistChunks
        val persistEveryMs = kEmbedResumePersistMs
        var lastPersistAt = System.nanoTime()
        var persistedUpto = startFrom
        var i = startFrom
        while (i < texts.size) {
            // ⚠ 库名照旧带着（`（xs002）`）：整屏上只有这一行说得清"是哪一趟" ——
            //   而这个 App 允许**同时开着好几个库**，用户切来切去时
            //   "这趟是在建哪个"是比"走到第几条"更前面的问题。
            uiProgress(RagProgress.PREFIX + RagProgress.line(
                i, totalChunks, (System.nanoTime() - buildT0) / 1_000_000L) + "（$name）")
            val end = minOf(i + batch, texts.size)
            val slice = texts.subList(i, end)
            // ── 批级计时（ISSUE #269 第 1 步：先量）──────────────────────
            // 合批是**纯加速**：生效与不生效在协议上完全看不出来（同样的 200、
            // 同样的向量）。没有这一笔读数，"到底省了多少"只能靠推断 ——
            // 而推断对不对，只有真机上看得到。所以这里按**批**计时（不按条：
            // 逐条计时是上万次取时钟，而它们的和就是这一笔）。
            val batchT0 = System.nanoTime()
            val r = embedBatch(slice, dim)
            val got: List<FloatArray?> = when (r) {
                is BatchEmbed.Ok -> {
                    EmbedBatchStats.record(EmbedBatchStats.Sample(
                        EmbedBatchStats.Route.BATCHED, end - i,
                        (System.nanoTime() - batchT0) / 1_000_000L))
                    r.vectors
                }
                is BatchEmbed.Unavailable -> {
                    // 整批不可用 -> **回落逐条**（不是"这批全失败"）。单条路径有完整
                    // 的失败归因，合批这一层没有 —— 把"这批不适合合批"当成"这批全坏"
                    // 会让用户看到一批一批地坏，而真相只是批大小或模型档位的问题。
                    // ⚠ 回落只影响**这一批**，不改动批大小之外的任何东西。
                    // ⚠ 回落这一批**不计入合批耗时**（它走的是逐条路径，那份耗时
                    //   不是合批省下的）—— 但批次与条数**必须记**，否则看不出
                    //   "整轮建库一条都没合批"这个关键读数。
                    EmbedBatchStats.record(EmbedBatchStats.Sample(
                        EmbedBatchStats.Route.FELL_BACK, end - i,
                        (System.nanoTime() - batchT0) / 1_000_000L))
                    List(end - i) { k -> embed(texts[i + k]) }
                }
            }
            for (k in i until end) {
                val src = sources.getOrElse(k) { "" }
                val v = got.getOrNull(k - i)
                if (v == null || v.size != dim) {
                    // 失败的**正文照样留下**（用户敲的东西不许丢），向量缺席，
                    // 整库标为"需重建" —— 与用户拍板的口径一致（见 RagIndexStore.savePartial）。
                    rows.add(RagIndexStore.Entry(texts[k], src))
                    failedIdx.add(k)
                    skipped++
                    if (fails.size < kMaxReportedFails) {
                        fails.add(k to (lastEmbedFailure()
                            ?: EmbedFail("unknown", 0, dim, embedOutDim, embedPooling, 0.0)))
                    }
                    continue
                }
                rows.add(RagIndexStore.Entry(texts[k], src, v))
                okCount++
            }
            i = end
            // ── 定期落盘（只在**没有失败**时落）───────────────────────────────
            // ⚠ 有失败时不落：失败会让 `rows` 里出现"缺向量"的行，而
            //   [RagIndexStore.savePartial] 的语义是"正文全量、向量只留**连续前缀**"
            //   —— 一旦这批有失败，后续块的向量**不能往前挤**（挤了就是整体错位，
            //   检索分数对、注入内容错，且不报错）。
            //   所以这里只在"到目前为止全部成功"时才把前缀落盘；
            //   有失败时这一轮的**收尾**（下面的 savePartial）会按老口径整份写下，
            //   而那份是"待重建"的（用户改掉坏的那几条再重建）。
            //   换句话说：**续编只服务于"一路顺利但被杀"这一种场景**，
            //   它不会把"有失败的那一轮"变成可续 —— 那是两件事，不能混。
            if (failedIdx.isEmpty() &&
                (i - persistedUpto >= persistEveryChunks ||
                    (System.nanoTime() - lastPersistAt) / 1_000_000L >= persistEveryMs)) {
                // 前缀落盘：**正文全量 + 前 i 块有向量**。
                // ⚠ 这里**不能**把 `rows` 直接传给 `savePartial`：`rows` 此刻只装到
                //   第 i 块，是"已编过的前缀"，不是"这份库的正文"。`savePartial`
                //   的契约是"传进来的就是**整份正文**"，于是它会把前缀当整份写下去
                //   —— 后 `texts.size - i` 块的字**从 `texts.jsonl` 里消失**，
                //   而索引与剩下的正文**自洽**（count == 行数-1、正文 hash 对得上），
                //   下次进来判成 `Load.valid`：界面报"库建成功了"，条目数却是
                //   截断后的那个。这不是"少算几块"，是**用户导进来的正文被删掉**
                //   （用户报的："导 1000 条、建到 216 条被杀、重启只剩 216 条"）。
                //   同一处还连带第二个错：指纹里的正文 hash/行数会按**前缀**算，
                //   于是下次 `loadPrefix(fullFp)` 永远比对不上 —— 续编一次都不生效
                //   （而它不报错，只是"又从 0 开始"）。
                //   所以"前缀向量 + 全量正文"的拼接收口到
                //   [RagIndexStore.saveProgress] 一处：调用方只报"编完了几块"。
                //   这样"传错列表"在类型上就不可能发生 —— 比在调用点写一句
                //   "注意要传全量"可靠：那句注释当时**也在**（就写在这上面），
                //   而它没拦住这个 bug。
                // 已编那 `i` 块的向量摊平（`rows` 装的正是这段前缀，顺序即行号）。
                val prefixVec = FloatArray(i * dim)
                for (k in 0 until i) {
                    val v = rows[k].vector
                    if (v.size == dim) System.arraycopy(v, 0, prefixVec, k * dim, dim)
                }
                val okP = RagIndexStore.saveProgress(ctx, name, fp, dim, i,
                    texts, sources, prefixVec,
                    partialMeta(name, i, texts.size, dim, cp))
                if (okP) {
                    persistedUpto = i
                    lastPersistAt = System.nanoTime()
                    uiLog("[知识库] $name 阶段性落盘：已编 $i/${texts.size} 块（中途退出可从这里接着编）")
                } else {
                    // 落盘失败**不中止**建库（存储空间/权限问题不该让已经算好的白费），
                    // 但要说出来 —— 静默的后果是"下次还是从头跑"，而用户不知道为什么。
                    uiLog("⚠ [知识库] $name 阶段性落盘失败（已编 $i/${texts.size} 块）——" +
                        "若中途退出，下次会从头编")
                }
            }
        }
        if (rows.isEmpty()) return "没有可索引的内容（文档全为空或切不出块）"

        // ── 落盘：全成功走 save（索引可用），有失败走 savePartial（正文在、索引待重建）
        // 这条分界决定了用户下一步该做什么，所以两条路都必须显式存在，不能合并 ——
        // 合并成一个"尽力而为"的默认行为会让"建好了能检索"与"建好了但不能检索"
        // 在界面上长得一样。
        val stale = failedIdx.isNotEmpty()
        val metaObj = org.json.JSONObject()
            .put("library", name)
            .put("entries", if (stale) okCount else rows.size)
            .put("rows", rows.size)
            .put("chunks", totalChunks)
            .put("skipped", skipped)
            .put("dim", dim)
            .put("targetChars", cp.targetChars)
            .put("overlapChars", cp.overlapChars)
            // 嵌入模型路径：取的是**实际加载进来的那一份**（`embedPath`），不是
            // 调用方给的字面值 —— 文件可以被覆盖、路径可以带软链，fp 里写的也是这个。
            // ⚠ 这两个字符（模型身份）此前是 `modelPath`，6f40ff2 改名为 `embedPath`
            //   时漏掉了这一处 → 整条 fluent 链类型推断断掉、连带 1645 行的 `.put` 一起报错。
            .put("model", embedPath ?: "")
            .put("modelOutDim", embedOutDim)
            .put("pooling", embedPooling)
            .put("builtAt", System.currentTimeMillis())
            // 失败的下标与原因进 meta.json：这是"刷新后仍能看见哪几条是坏的"的
            // 唯一载体（日志会滚、UI 会重画）。字段名固定、原因码是英文短名
            //（见 embed_text.h 的 failure_name），守卫正面钉住这两点。
            .put("failedIndices", org.json.JSONArray(failedIdx))
            .put("failReason", fails.firstOrNull()?.second?.reason ?: "")
        val meta = metaObj.toString()

        val okSave = if (stale) {
            RagIndexStore.savePartial(ctx, name, fp, dim, rows, meta)
        } else {
            // 全成功：向量与正文行号一一对应，指纹与正文一致 → 索引**可直接检索**。
            // 这里刻意仍然走 save（而不是 savePartial）—— 它带"向量行数 == 正文行数"
            // 的硬校验，是"能检索"这个结论的判据本身。
            val flat = FloatArray(rows.size * dim)
            for (i in rows.indices) System.arraycopy(rows[i].vector, 0, flat, i * dim, dim)
            RagIndexStore.save(ctx, name, fp, dim, flat, meta, rows)
        }
        if (!okSave) return "索引写入失败（存储空间？）"

        if (stale) {
            // 汇报必须**同时**给出四件事：成功几条 / 失败几条 / 失败的是哪几条（含原因）
            // / **这些失败说明什么、该做什么**。
            //
            // ⚠ 第四件是本轮补上的。前一轮只给了前三件 —— 数据很全，结论为零：
            // 用户看到"#1 向量范数为 0.0 …… 池化方式可能与该模型不符"之后，
            // 仍然不知道该去查什么（"可能不符"是猜测，不是结论），于是他去换模型
            // 或者反复重建，而真正的毛病是**填充逻辑没满足库的前置条件**。
            // 数据不缺、缺的是把数据变成一个**可执行**的判断 —— 见 [verdictOf]。
            val detail = fails.joinToString("；") { (i, f) -> "#${i + 1} ${f.describe()}" }
            val more = if (failedIdx.size > fails.size) "，另有 ${failedIdx.size - fails.size} 条同类失败" else ""
            val verdict = verdictOf(fails.map { it.second }, okCount, failedIdx.size)
            uiLog("[知识库] $name 索引已生成但**待重建**：成功 $okCount / 失败 ${failedIdx.size} 条" +
                "（切块 $totalChunks，正文 $rows.size 行已保留），dim=$dim")
            uiLog("[知识库] 失败明细：$detail$more")
            uiLog("[知识库] 结论：$verdict")
            // 部分失败这条路也要报批级路由：它正是"为什么这批慢"的唯一线索，
            // 而失败与回落常常同时出现（同一个档位问题）。
            logBatchRoute(name, batch, statsBefore)
            return "成功 $okCount 条，${failedIdx.size} 条编码失败（正文已保留，但索引需重建）—— " +
                "$detail$more\n结论：$verdict"
        }

        uiLog("[知识库] $name 索引已重建：$okCount 条（切块 $totalChunks，跳过 $skipped），dim=$dim")
        logBatchRoute(name, batch, statsBefore)
        return null
    }

    /**
     * 把**这一段建库**的批级路由说一句（ISSUE #269 第 1 步：先量）。
     *
     * ── 为什么非要这一行 ───────────────────────────────────
     * 合批是**纯加速**：生效与不生效在协议上完全看不出来。此前唯一的
     * 读数是"回落时打一行 `[embedBatch]`"—— 而那行只在**出错**时出现：
     *  · 合批正常工作时，日志里**什么都没有**；
     *  · 合批一次都没生效时，也只有一行行 Unavailable，而那些行与
     *    "某一条文本空"混在一起，看不出"整轮一条都没合批"。
     *
     * 合一句就把"到底省了多少"从推断变成读数 —— 而这个读数
     * 又正好是第 3 步（把 MEAN / CLS / LAST 试到可合批）要的基准。
     *
     * ── [before] 这个参数是干什么的 ───────────────────────────
     * 报的是**本段增量**，不是累计。[EmbedBatchStats] 记的是进程级累计量
     * （跨多次建库累加），而这一行说给用户听的是"我点的这一次建库"。
     * 把累计值写成"本轮"是本仓库真出过的一处措辞错：用户拿这行去比
     * "批4 vs 批1"时，读到的是两个几乎相等的累计值（相邻两次只差 4 条），
     * 会读成"批大小没生效"。所以开工时留一份基线 [before]，此处一减就是本段。
     *
     * ⚠ 只在**批级**打（一轮建库一行），不逐条打：逐条打会在一个万条库上
     * 产生一万行（正是 [uiProgress] 那段说的"上万行不进 ring"的原因）。
     * ⚠ 那两处 `hint` 的判据也一并从"本段"读：一句话里"回落 N 批"是段的数，
     *   而"合批一次都没生效"这个结论必须与它同源 —— 拿累计值判 hint、
     *   拿段值报数字，会出现"本段合批 4 批"配"合批一次都没生效"的自相矛盾。
     */
    private fun logBatchRoute(name: String, batch: Int, before: EmbedBatchStats.Snapshot) {
        val s = EmbedBatchStats.delta(before, EmbedBatchStats.snapshot())
        // 合批一次都没生效时这一行必须**明说**原因方向（档位？批大小？）——
        // 否则用户只看到"回落 N 批"，而下一步该去哪儿看完全没线索。
        val hint = when {
            s.fellBackBatches <= 0L -> ""
            s.batchedBatches <= 0L ->
                // ⚠ 0.9.208 起 pooling 档位**不再**是"合批不生效"的可能原因
                //（所有 `plan_pooling` 认的档都能合批了，详见
                // `embedtext::batchable_pooling`）。这句话此前写的是
                // "MEAN/CLS/LAST 那三档不合批" —— 留着它就是一句**过期且指向错方向**
                // 的排查提示（用户会去换模型，而真正的原因在批大小或某几条文本上）。
                "（本段合批**一次都没生效**：先看上面的 `[embedBatch]` 行——" +
                    "它报的是这一批为什么被拒，多半是批大小超过 native 上限）"
            else -> "（本段部分回落：看上面的 `[embedBatch]` 行定位是档位还是某条文本）"
        }
        uiLog("[知识库] $name 批级读数（本段批大小=$batch，只算这一次建库）：" +
            "${EmbedBatchStats.describe(s, EmbedBatchStats.kScopeThisBuild)}$hint")
    }

    /** 汇报给用户的失败明细上限：再多就刷爆界面，而"有几条"另有总数。 */
    private val kMaxReportedFails = 20

    /**
     * 建索引时**每编多少块**就把"已算好的前缀"落一次盘（续编的粒度）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么是**块数**而不是条数
     * ═══════════════════════════════════════════════════════════════════════
     * 用户提的是"每 100 条存一次"——那是按**正文条数**算的，而建库的耗时由
     * **块数**主导：一条 3 MB 的长文档能切出一万块，100 条这个粒度在它面前
     * 一辈子碰不到（一条就有一万块）。所以粒度必须与耗时同源。
     *
     * 取 2000 的道理：按合批 8 条一批、每批几十毫秒到几百毫秒估，2000 块 ≈
     * 几十秒到几分钟落一次 —— 中途被杀最多重跑这一档。再小（比如 200）
     * 会让落盘本身成为可观开销（每次都是全量重写 [RagIndexStore.savePartial]，
     * 而它要写整份正文 + 全部前缀向量）。
     * ⚠ 与 [kEmbedResumePersistMs] 是**两道闸**（谁先到算谁）：块数闸管"编得快"，
     *   时间闸管"编得慢"（慢机器上 2000 块可能超过 3 分钟，那时时间闸先到）。
     */
    private const val kEmbedResumePersistChunks = 2000

    /**
     * 建索引时**至少每多少毫秒**落一次盘（与 [kEmbedResumePersistChunks] 并行的第二道闸）。
     *
     * 取 3 分钟：用户提的"每 3 分钟一次"。它兜住的是"块很大 / 机器很慢"那种
     * 一档都编不完的情形 —— 只有块数闸的话，一份上万块的文档在慢机器上
     * 可能十几分钟才落一次，而中途被杀就白费那十几分钟。
     */
    private const val kEmbedResumePersistMs = 3 * 60 * 1000L

    /**
     * 阶段性落盘（续编用）写进 `meta.json` 的那份说明。
     *
     * ⚠ 它与收尾那份 meta 是**两份**：收尾那份带 `failedIndices` / `failReason`
     *（"哪几条坏了"），而阶段性的这份**必然没有失败**（有失败就不落，见 [buildIndex]），
     * 所以它不写那两个字段。共用一份的后果是：阶段性那份里 `failedIndices`
     * 是空数组、"没有失败"与"还没编到"在 meta 上长得一样 —— 而 meta 是
     * 排障时唯一一眼能看的东西。
     *
     * `entries` 与 `rows` 都写**预计总数**（不是已编数）：meta 描述的是"这个库
     * 应该有多少条"，而不是"这次跑到哪了"—— 后者由 `index.bin` 头里的 count 说。
     * 两份都写"已编数"会让"中途退出"与"库里就这么几条"在 meta 上分不开。
     */
    private fun partialMeta(name: String, done: Int, total: Int, dim: Int,
                            cp: RagIndexStore.ChunkParams): String = org.json.JSONObject()
        .put("library", name)
        .put("entries", total)
        .put("rows", total)
        .put("chunks", total)
        .put("skipped", 0)
        .put("dim", dim)
        .put("targetChars", cp.targetChars)
        .put("overlapChars", cp.overlapChars)
        .put("model", embedPath ?: "")
        .put("modelOutDim", embedOutDim)
        .put("pooling", embedPooling)
        .put("builtAt", System.currentTimeMillis())
        // 续编进度**自报**（只给人看，不参与判据）：真值在 index.bin 的 count 里，
        // 但排障时"这份 index.bin 到底编到哪了"能在一眼处看到，比去算 count 省事。
        .put("resumeUpto", done)
        .toString()

    /**
     * 逐条编码时**每多少条**回一次进度。
     *
     * 取 32 的道理：一条嵌入在手机上约几毫秒到几十毫秒，32 条 ≈ 0.1~1 秒 ——
     * 那就够让计数"看着在走"了，而 UI 侧还有一层时间节流（见 EngineActivity 的
     * 进度行），两道闸合起来保证不管块数多大，主线程每秒最多排几次版。
     * 取 1 会把每个块都过一遍整流（上万次跨线程 post），取 1000 又会让小库
     * 一次进度都不报（那正是"卡死"的观感）。
     */
    private const val kProgressEvery = 32

    /**
     * 每批默认塞几条（ISSUE #269 的另一半；第 2 步起可调）。
     *
     * ⚠ 它是**默认值**，不再是硬编进循环里的常量：真正跑多少由
     * [buildIndex] 的 `embedBatchSize` 参数决定（设置页可改），经 [kEmbedBatchLimit] 钳到
     * native 的上限。
     *
     * 数值与那段"为什么是 8"的推导都在 [EmbedLimits.kEmbedBatchDefault] ——
     * 搬过去是为了让宿主单测编得过（本文件依赖 Android，编不到宿主上）。
     */
    const val kEmbedBatchDefault = EmbedLimits.kEmbedBatchDefault

    /**
     * native 侧合批的**真实上限**（= 建嵌入 ctx 时写进 `n_seq_max` 的 `kEmbedBatchRows`）。
     *
     * 与 native 那一份**同一个数**，判据正面钉住（`check_embed_batch_wiring.py`）；
     * 为什么 Kotlin 这边也留一份、以及"不等就白批"的那段推导，见
     * [EmbedLimits.kEmbedBatchRows]。
     */
    const val kEmbedBatchRows = EmbedLimits.kEmbedBatchRows

    /**
     * **每条**文本的上下文预算（默认，= native 侧 `kEmbedDefaultCtx`）。
     *
     * ⚠ 与 [kEmbedBatchRows] 是同一个数的两面，**必须一起看**：native 侧
     * `n_ctx = 每条预算 × kEmbedBatchRows`（再按 [kEmbedCtxMax] 钳），
     * 而两道门（`n_batch` / `n_ubatch`）设成这个总量 —— "8 条满长能不能一次装下"
     * 就是 `8 × (预算-1) ≤ 总量` 这件事。
     *
     * 它同时是**内存主项**：`embeddings=true` 时 KV / output / compute buffer
     * 都按它分配。所以"内存占用为啥高"的答案通常在这一格，而不是在批大小那格
     *（批大小只决定"一次塞几条"，不改 ctx 容量）。
     *
     * ⚠ 0.9.241 起批上限从 8 降到 4（省内存），而**这一格没动**：总量 =
     * 512 × 4 = 2048，恰好等于 [kEmbedCtxMax]。要继续省就往下调这一格
     *（它线性地决定 KV / output buffer）。
     */
    const val kEmbedCtxDefault = 512

    /**
     * 每条预算的**上限**（= native 侧 `kEmbedMaxCtx` 与 `n_ctx` 总量的上限）。
     *
     * 与 native 那个常量**同一个数**（守卫正面钉两处相等，见
     * `check_embed_gate_settings.py`）：设置页要在加载**之前**就知道合法区间，
     * 而那个价值只有加载完成后才由库给出 —— 所以只能在这儿再写一份 + 守卫比对。
     *
     * ⚠ 之所以不是"想填多大就多大"：这个数直接乘出 ctx 总量，而 ctx 总量决定
     * 一份**不可协商**的内存分配。给大了的表现是"嵌入模型加载失败"，
     * 而用户完全看不出是这一格给大了（他会以为 GGUF 坏了）—— 与"嵌入 n_ctx
     * 必须被钳"那条守卫守的是同一件事。
     *
     * ⚠ 4096 → 2048（0.9.241，用户要省内存）：默认档 512 没变，变的是**上限**。
     * 真能被库吃下的每条的预算 = `上限 / 批上限`（0.9.241 起 2048/4 = 512），
     * 所以填 512 就是顶格；填更大只会被钳到 512 并在读数里说出来。
     *
     * 数值实体在 [EmbedLimits.kEmbedCtxRowsCap]（宿主侧报告要算"生效值"，
     * 而它编不到本文件），这里只转发 —— 两处由守卫核相等。
     */
    const val kEmbedCtxMax = EmbedLimits.kEmbedCtxRowsCap

    /**
     * 把用户意愿钳到 native 真能跑的区间：`1..kEmbedCtxMax`。
     *
     * 与 [kEmbedBatchLimit] 同一形态、同一理由：设置页（限制输入）与加载
     *（真正传给 native）两处各钳一遍时迟早分岔，而分岔的症状是
     * "输入框里 4096、日志里跑 512，界面上一个字都没有"。
     * 非正数当默认值：0 在 native 侧是"用默认"，而这一格填 0 的用户意图是
     * "别给我搞坏"，不是"要一个 0 的上下文"。
     */
    fun kEmbedCtxLimit(want: Int): Int =
        if (want <= 0) kEmbedCtxDefault else minOf(want, kEmbedCtxMax)

    /**
     * 两道门（`n_batch` / `n_ubatch`）的**天花板**（= native 侧 `kEmbedGateCap`）。
     *
     * 它与 [kEmbedCtxMax] **必须是同一个数**（0.9.241 起都是 2048），但**不是**
     * 同一个常量的两个名字 —— 判据核的是"两处相等"，而它们各自代表一件事：
     * 门按**整批 token 数**切内部缓冲与计算图（0.9.242 起可由用户下调），
     * ctx 总量决定 KV / output buffer 的大小。0.9.242 之后两者都往下可调，
     * 而"往下"不会让满长那批装不下（`perSeqMax` 反而变大），所以放开下限是安全的；
     * 天花板仍必须钉住 —— 门给大了的表现是 compute buffer 暴涨（真机上"加载很慢
     * 然后 OOM"），而不是某条断言。
     *
     * 数值实体在 [EmbedLimits.kEmbedGateCap]（宿主侧报告要算"生效值"）。
     */
    const val kEmbedGateMax = EmbedLimits.kEmbedGateCap

    /**
     * 把用户意愿钳到 native 真能跑的区间：`1..kEmbedGateMax`（0 = 天花板）。
     *
     * 与 [kEmbedCtxLimit] 同一形态、同一理由：设置页（限制输入）与加载
     *（真正传给 native）两处各钳一遍时迟早分岔。
     */
    fun kEmbedGateLimit(want: Int): Int =
        if (want <= 0) kEmbedGateMax else minOf(want, kEmbedGateMax)

    /**
     * 把用户意愿钳到**native 真能跑的区间**：`1..kEmbedBatchRows`。
     *
     * 一个纯函数（可离线单测），为什么：它被**设置页**（限制输入）与
     * **建库**（真正跑多少）两处调用，两处各写一份时很容易分岔 ——
     * 而分岔的症状是"输入框里写 16、日志里跑 8，界面上一个字都没有"（正是
     * 本 issue 最初那个"线程数输入框对建库无效"的同一形态）。
     *
     * 下限是 1（= 关合批，每次只编一条 —— 逐条路径本来就是合法的，用户要对比时需要它）。
     * 非正数（ 0 / 负）当 1：0 在循环里是死循环（`end == i` 永远不进）——
     * 而"死循环"是比"跑慢" 更坏的一种失败（界面卡死而不报错）。
     */
    fun kEmbedBatchLimit(want: Int): Int =
        if (want <= 0) 1 else minOf(want, kEmbedBatchRows)

    /**
     * 切块用的分隔符表 —— **与 `rag_index.h` 的 `kChunkSeps` 是逐条契约**。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么只有两级（段落 + 句读），以及为什么必需与 C++ 侧一模一样
     * ═══════════════════════════════════════════════════════════════════════
     * 上一版这里是"段落 > 换行 > 任意位置"三级，而 C++ 侧同源。用户提出的
     * 五级阶梯（再加逗号 / 顿号 / 空格 / `-`）被否掉了，理由写在 `rag_index.h`
     * 的 `kChunkSeps` 注释里（两条会切出错块的路径，穷举都撞出来了）：
     * ① 切在 UTF-8 码点中间（`。` 是 3 字节，而切点是**字节偏移**）；
     * ② 窗口内找不到就往回找最近的一次，`cut <= pos` 时兜底成"前进 1 字节"，
     *    于是 `pos` 到那次匹配之间的正文**整段丢掉**，不报错、块数正常。
     * 修法就是这份表现在这个样子：两级 + "窗口内找不到就硬截"这一条铁律。
     *
     * 两份**表**与**次序**都必须逐条相等：`t`/`o` 只描述"块多大"，
     * 描述不了"从哪儿切"。表分了家而两个数相同 -> 指纹相同 -> 旧索引被当新的用，
     * 而"用旧边界算的向量命中、注入新边界切出的正文"是**静默**的。
     * `tools/run_rag_index_guard.sh` 的 `chunk_seps_consistent` 逐条比对。
     *
     * ⚠ 表本身**不在这个文件里**：它是 [TextBoundary.SOFT_CUT_SEPS]。而它还有
     *   第二个消费方 —— `RagArchive.splitLongLine`（导入超长正文时切成多条条目）。
     *   那边此前抄了第二份表（并多出 `…` `:`、少了段落级），那份表**没有任何判据
     *   绑着**：改这里或改那里，另一处不会红，而症状是"同一份正文按行导入与按库
     *   建索引切得不一样"，逐字相同地看不出来。合并成一份之后，本文件只剩
     *   "怎么用"，"用什么"在那一处。
     */
    private val kChunkSeps: List<Pair<String, Boolean>> get() = TextBoundary.SOFT_CUT_SEPS

    /** [kChunkSeps] 里最长分隔符的宽度（code unit）—— 找切点时的回看距离。 */
    private val kSepMaxWidth: Int = TextBoundary.maxSepWidth()

    /** 失败结论的实现在 [EmbedProgress]；这里保留薄门面，调用点无需改。 */
    private fun verdictOf(fails: List<EmbedFail>, okCount: Int, failCount: Int): String =
        EmbedProgress.verdictOf(fails, okCount, failCount)

    /**
     * 把编好的正文**只保存**（不重算向量、不重建索引）。RAG 阶段 2 的「保存正文」用它。
     *
     * 返回 null = 已保存；否则是给用户看的失败原因。**不抛异常**（后台线程上跑）。
     *
     * 为什么先要求"该库已有索引"：**"保存"这件事的语义是"改写一个已经建好的库"**
     * （`texts.jsonl` 与 `index.bin` 行号一一对应，而这里只改前者）。
     * 一个从来没有索引的库要落盘，走的是另一条路 ——「建索引/重建索引」或
     * pending 的自动保存（`importEntries`）—— 那条路会把正文与向量一起建起来。
     *
     * [!] 两条判据是**分开**问的（库在不在 / 索引在不在），因为它们的处置不同：
     *     · 库不存在 -> "先新建或导入一个"；
     *     · 库在、索引不在（待重建）-> "先点建索引/重建索引"。
     *     合成一句"该库还没有索引"的后果：用户拿着一个**已经导入完正文**的库
     *     点保存，得到的指引是"先点建索引/重建索引"，而那句话对"我要把这次编辑
     *     存下来"给不出出路（他只能整库重建，几十秒）。
     *
     * ⚠ 保存后索引会**失效**（指纹里的正文 hash 对不上了）：正文与向量是一对，
     * 改了正文而没重算向量，检索会用旧语义命中、注入新正文。用户必须点
     * 「建库/重建」才恢复可检索 —— 状态行会明确这么说，不靠用户自己猜。
     */
    fun saveTexts(ctx: android.content.Context, name: String, docs: List<RagIndexStore.Entry>): String? {
        if (RagIndexStore.dirOf(ctx, name) == null) return "库名非法（只允许字母/数字/中文/-/_/.，且非空）"
        if (!RagIndexStore.isPresent(ctx, name)) return "磁盘上没有这个库：先新建或导入一个"
        if (RagIndexStore.indexFile(ctx, name)?.isFile != true) return "该库还没有索引：先点「建索引/重建索引」"
        if (!RagIndexStore.saveTexts(ctx, name, docs)) return "正文写入失败（存储空间？）"
        val sync = RagIndexStore.textsInSync(ctx, name)
        uiLog("[知识库] $name 正文已保存：${docs.size} 条" +
            if (sync) "（与索引一致，检索不受影响）"
            else "（与索引不一致，检索已停用：点「建库/重建」恢复）")
        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  导入 / 导出（知识库正文）—— 不需要嵌入模型，纯文件读写
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 导出某个库的正文为信封 JSON（见 [RagArchive]）。返回 (文件名正文, 内容)，
     * 库空 / 库名非法返回 null。
     *
     * 为什么**不经过 native**：导出只是把 `texts.jsonl` 换个壳，一条向量都不碰。
     * 把它接到嵌入模型上会让"导出"在模型没加载时不可用 —— 而导出的最典型场景
     * 恰恰是"准备换手机/重装"，那时候模型可能根本没装。
     */
    fun exportRagArchive(ctx: android.content.Context, name: String): String? {
        if (RagIndexStore.dirOf(ctx, name) == null) return null
        val rows = RagIndexStore.exportEntries(ctx, name)
        if (rows.isEmpty()) return null
        return RagArchive.encode(name, rows, System.currentTimeMillis())
    }

    /**
     * 导入正文到某个库（**合并追加**，不覆盖）。
     *
     * 返回给用户看的**结果文案**（不是 null/异常二态）：条数、跳过数、
     * 以及"索引已作废，请点重建"这句**必须**说出来的话。
     *
     * 为什么必须说"需重建"而不能只说"导入成功"：合并之后正文比索引多，
     * 检索已经停了。用户如果以为"导入完就能搜"，他会对着一个搜不出东西的库
     * 反复试同一个关键词 —— 而那是本仓库最忌讳的那种"没有任何现象指向原因"。
     */
    fun importRagEntries(ctx: android.content.Context, name: String,
                         entries: List<RagIndexStore.Entry>): String {
        if (RagIndexStore.dirOf(ctx, name) == null) {
            return "库名非法（只允许字母/数字/中文/-/_/.，且非空）"
        }
        val hadIndex = RagIndexStore.isPresent(ctx, name)
        val total = RagIndexStore.importEntries(ctx, name, entries)
        if (total < 0) return "导入失败：正文写入失败（存储空间？或超过单库上限）"
        uiLog("[知识库] $name 导入 ${entries.size} 条 → 共 $total 条" +
            if (hadIndex) "（原索引已作废：正文变了，向量必须重算）" else "（该库尚无索引）")
        return "已导入 ${entries.size} 条，共 $total 条。\n" +
            (if (hadIndex) "⚠ 原索引已作废（正文变了、向量还是旧的）——点「建库/重建」才能重新检索"
             else "该库还没有索引——点「建库/重建」后才能检索")
    }

    /**
     * 导入的**索引重建**：只编码**新来的那几条**，库里已有的向量逐位沿用。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 这条路径是"导入不该整库重建"那句话的落点（ISSUE #228 的第 ③ 条）
     * ══════════════════════════════════════════════════════════════════════════
     * 此前导入只有一条路：[RagIndexStore.importEntries] —— 它写完正文就
     * **把 `index.bin` 删掉**（因为没有新条目的向量，留着旧索引会变成
     * "旧向量配新正文"）。于是"补一条笔记"的代价是**整库重建**：
     * 一个上万块的库就是几十分钟。而用户的动机从来不是"重建"，是"补进去"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么"只编新的那几条"是对的（而 [addEntry] 那条路为什么不行）
     * ══════════════════════════════════════════════════════════════════════════
     * 分块的**边界**由单条正文自己决定（[chunk] 从 pos=0 起、在自己的文本里
     * 找切点）—— 所以"新来的这几条切出几个块"与库里已经有什么**无关**，
     * 已有的那些向量**逐位仍然是对的**，一行都不用重算。
     *
     * ⚠ 这条理由**只在库那一侧做的是"逐条建库"时才成立**。
     *   `RagArchive.decodePlainText`（`.txt / .md` 那条导入）会把超长的一行
     *   先切段（[RagArchive.EMBED_MAX_CHARS]）再落成**多条**正文；而一条正文
     *   如果是**建库**时被切的（[chunk] 自己切，不只是按 448 硬切），
     *   它的切点与"先按 448 切段再逐段 chunk"**不是同一套**（实测：
     *   同 2 万字文本，整条建库 45 块、先切 448 再建 46 块，且块**内容**不同）。
     *   所以本函数**先按同一套规则把新条目 pave 一遍**（长条目先切 448），
     *   并**沿用磁盘那份索引的正文 hash** —— 已有的正文我们一个字节都不动，
     *   由它自己决定那块是否同源。不同源时 [RagIndexStore.loadPrefix] 会
     *   老实返回 null，这条路退化成"编新块"，而不是编出一份语义混杂的索引。
     *   ⚠ 但"整段库是按 448 切好之后建库的"这一条**没法验**：正文里不带"切点记号"，
     *   只能由**建库那一侧**对新建的库保证（[chunk] 修好之后，自建库与导入
     *   两条路切出来的是同一套块）。在这个前提下，本函数的结论是**可证**的；
     *   在此之前，它的失效形态是"前缀向量与后段不是同一套边界"，
     *   而那个形态**不报错**（分数看着像随机）—— 所以下面每一道闸都只做减法。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * `pave` 那条预切段
     * ══════════════════════════════════════════════════════════════════════════
     * [paveLongEntries] 把超过 [RagArchive.EMBED_MAX_CHARS] 的一条**切成多条**
     * （与 [RagArchive] 的纯文本导入同一套切点），于是：
     *   · 落进 `texts.jsonl` 的是**切好的那些段**，与"用 .txt 导入同一份内容"
     *     **逐条一致** —— 同一份数据不该因外壳不同而条目数不同（那一族判据在
     *     `run_rag_archive_guard.sh` 的 ⑨ 里）；
     *   · 每一段的向量都只覆盖它自己那 448 字以内，不会被嵌入层按 n_ctx 静默截断。
     * ⚠ 它**只切新来的**。库里已有的长条目**不碰**（碰它就是改正文 = 整库重建，
     *   而那正是这条路要避免的）。代价是一条老式导入留下的 3 万字整条，
     *   它的向量只覆盖开头 —— 这一条**必须说出来**（下面的日志），
     *   否则用户永远不知道为什么那一条检索不准。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 六道闸（任一不过就退回"[RagIndexStore.importEntries] + 需重建"）
     * ══════════════════════════════════════════════════════════════════════════
     *   ① 库名合法、该库磁盘上**有索引与正文**（新库/待重建的库没有可沿用的前缀）；
     *   ② `readState` 的三条自查（正文行数、`count == rows-1`、维度）全成立；
     *   ③ 当前指纹与**磁盘那份**逐字段相同（只许差 `g` 一处，见 [indexUsableForImport]）；
     *   ④ 当前嵌入模型已加载、维度取得到；
     *   ⑤ `texts.jsonl` 可解析（[RagIndexStore.readEntries] 里有坏行 = 那份"已有"的
     *      正文我们并不真的认识，谈"沿用它的向量"没有依据）；
     *   ⑥ 新条目满一条可编码的都没有（空文本 / 全被切没了）—— 那更像选错了文件。
     *
     * ⚠ 只差 `g` 是**放行**的，与 [rewriteRows] 那条路**相反**。理由是这两件事
     *   搬的东西不同：改字要**新算**向量（批大小会渗进新算的那几条），而这里搬的是
     *   **磁盘上已有的**那批向量，一个都不新算 —— 沿用与批大小无关。
     *   到了收尾算指纹那一刻，用当前设置（[EmbedBatchPref]）写下去，
     *   与检索侧（[search] / [indexUsableForRewrite]）同一把尺子。
     *
     * @return [ImportIndexResult]：沿用了几条 / 新编了几块 / 该干什么。
     */
    fun importRagEntriesWithIndex(ctx: android.content.Context, name: String,
                                  incoming: List<RagIndexStore.Entry>,
                                  embedBatchSize: Int = kEmbedBatchDefault): ImportIndexResult {
        if (incoming.isEmpty()) return ImportIndexResult.None
        if (RagIndexStore.dirOf(ctx, name) == null) return ImportIndexResult.None
        if (!RagIndexStore.isPresent(ctx, name)) return ImportIndexResult.None
        if (RagIndexStore.indexFile(ctx, name)?.isFile != true) return ImportIndexResult.None
        if (!hasEmbedModel) return ImportIndexResult.None
        val dim = embedDim
        if (dim <= 0) return ImportIndexResult.None
        // 闸③：只差批大小才放行（[indexUsableForImport] 里那句"为什么与改字相反"）。
        if (!indexUsableForImport(ctx, name)) return ImportIndexResult.None
        val old = RagIndexStore.readEntries(ctx, name)
        if (old.isEmpty()) return ImportIndexResult.None
        // ── pave 新条目：长条目先按 [RagArchive.EMBED_MAX_CHARS] 切段 ─────────
        val paved = paveLongEntries(incoming)
        val fresh = paved.entries.filter { it.text.isNotBlank() }
        if (fresh.isEmpty()) return ImportIndexResult.None
        // 库里已有的正文**一个字节都不动**：那 N 块向量说的是这 N 行，
        // 动一行就是"另一次建库"。老式长条目只报出来（见函数头）。
        val legacy = old.count { it.text.length > RagArchive.EMBED_MAX_CHARS }
        if (legacy > 0) {
            uiLog("[知识库] $name 库里已有 $legacy 条超长条目（导入之前就在），" +
                "它们的向量只覆盖开头一段 —— 要修请点「建库/重建」")
        }
        // ── 闸②：库里那份索引此刻自洽（[appendScan] 里会再验一次，这里先分出口）──
        //
        // 判据是 [RagIndexStore.readState] 的同一个不变式：**段数 == 条目数 + 1**
        // （末那 1 格是自产文件的结尾换行产生的空段，见 [RagIndexStore.rowsOf]）。
        //   · 良构：`N` 条 -> 段数 `N+1`，两边的 `split('\n')` 一致；
        //   · 中间有坏行/空行：段数**不变**（`N+1`），而 [RagIndexStore.readEntries]
        //     跳过它 -> 只读出 `N-1` 条 —— 差 2 格，正是要拒的形状。
        //
        // ⚠⚠ 别再写成"对齐表的**长度**"那一版：`readEntriesAligned` 给结尾那个空段也
        //   留一格，良构库上它恒等于 `N+1`，拿它跟 `N` 比在**每一个良构库**上都成立
        //   （ISSUE #228：日志"31 行对齐 / 30 条可读"）—— 每次导入都在这一闸被拒，
        //   增量这条路等于不存在，100% 复现。
        // ⚠ 也别写成"对齐表里的**非 null 个数**"：它与 `readEntries` 跳过坏行的口径
        //   **完全一样**，两边恒等 -> 判据恒真、抓不到任何坏行（同一个坑的另一面）。
        //   两种写法都"看起来在数行"，只有**段数 vs 条目数**这一对有信息量。
        val rowsOld = RagIndexStore.readTexts(ctx, name)
            ?.let { RagIndexStore.rowsOf(it) } ?: return ImportIndexResult.None
        if (rowsOld != old.size + 1) {
            uiLog("[知识库] $name 正文里有读不出来的行（$rowsOld 行对齐 / ${old.size} 条可读）" +
                "—— 这次不走增量")
            return ImportIndexResult.None
        }
        // ── 第一步：正文追加 + 已有向量搬到新索引前缀 ──────────────────────────
        //
        // ⚠⚠ 批大小与模型半指纹**在 appendScan 之前**算好，且**同一份**交给它。
        //     让 `appendScan` 自己从磁盘那份指纹里取（老写法）会有一个致命的不等：
        //     它取的是**磁盘那份**、而下面收尾算"整库指纹"用的是**当前设置** ——
        //     两者一旦不完全相同（改过模型文件 / 改过「合批」那一格），刚写下的
        //     索引当场被判"正文 hash 变了"（ISSUE #228 复现日志那一行），
        //     前缀取不回来，于是新块全部重算、再用 `save` 覆盖 ——
        //     **库里原有的向量静默消失**，而界面上报的是"导入成功"。
        //     一份指纹有两个来源，就是下一次分家的开始：这里收成**一处**。
        val cp = RagIndexStore.ChunkParams()
        val batch = kEmbedBatchLimit(embedBatchSize)
        val fpIn = embedFpInput(cp, batch)
            ?: return ImportIndexResult.NeedsRebuild("嵌入模型文件不可读或维度取不到")
        val scan = RagIndexStore.appendScan(ctx, name, fresh, fresh.size, fpIn)
        when (scan) {
            RagIndexStore.ScanResult.OK -> { }
            RagIndexStore.ScanResult.TEXT_ONLY -> {
                // 正文已落盘、索引没跟上 —— 正是 [RagIndexStore.importEntries]
                // 那条路会留下的状态（需重建）。⚠ **不许再导一次**：正文已经在
                // 盘上了，再走一遍老路就是"同一次导入落两份"（用户看到的"内容变双份"）。
                uiLog("[知识库] $name 增量导入的索引没跟上（正文已落盘）—— 点「建库/重建」")
                return ImportIndexResult.NeedsRebuild("索引没跟上（正文已落盘）")
            }
            RagIndexStore.ScanResult.FAILED -> {
                // 一个字节都没写 —— 退回老路补一次（那里会真把正文落盘）。
                uiLog("[知识库] $name 增量导入落盘失败（正文没写成）—— 退回老路重导")
                return ImportIndexResult.Failed
            }
            RagIndexStore.ScanResult.BROKEN -> {
                uiLog("[知识库] $name 追加的行数与预期不符（索引与正文对不上）—— 这次不走增量")
                return ImportIndexResult.None
            }
            RagIndexStore.ScanResult.NONE -> return ImportIndexResult.None
        }
        // ── 第二步：编**新来的那几条**（库里的向量一个都不重算）────────────────
        // ⚠ 只装**新导入那些块**的正文（向量按批编的就是它们）。
        //   落盘那一份正文走 `allRows`（整库），见收尾那段说明。
        val texts = ArrayList<String>()
        for (r in fresh) {
            for (piece in chunk(r.text, RagIndexStore.ChunkParams())) {
                texts.add(piece.text)
            }
        }
        if (texts.isEmpty()) {
            // 正文已经落盘（用户的字没丢），只是切不出块 —— 走老口径"需重建"。
            uiLog("[知识库] $name 新导入的正文切不出块（全空白？）—— 索引待重建")
            return ImportIndexResult.NeedsRebuild("导入的正文切不出块（全空白？）")
        }
        // 收尾要用的指纹：**正文那一半从磁盘现读**（`appendScan` 刚写下去的那份
        // 字节），而不是在这里 `encodeTexts(allRows)` 重编一遍。
        //
        // ⚠⚠ 这是"前缀取得回来吗"的关键一格。`appendScan` 算的 hash 是它
        //     **实际写下的字节**；这里若自己重编一份，两者之间隔着一次
        //     `readEntries` -> `encodeTexts` 的往返 —— 任何一处编码差异
        //     （JSON 键序 / 转义 / 末尾换行）都会让指纹差一格，于是
        //     `load` 判"正文 hash 变了"，前缀取不回来。**同一份字节只 hash 一次**：
        //     用盘上那份，两处必然相等。
        val textsBytes = RagIndexStore.readTexts(ctx, name)
            ?: return ImportIndexResult.NeedsRebuild("正文读不出来（存储空间？）")
        val fullFp = RagIndexStore.fingerprintOf(fpIn, RagIndexStore.textsHashOf(textsBytes),
            RagIndexStore.rowsOf(textsBytes))
        // 整库的条目表：`allRows` 只用来数行（写下去的正文由 [RagIndexStore.save]
        // 从它自己现编的那份算 hash）—— 两边的行数由同一条不变式绑住。
        val allRows = ArrayList<RagIndexStore.Entry>(old.size + fresh.size)
        allRows.addAll(old)
        allRows.addAll(fresh)
        // ══════════════════════════════════════════════════════════════════
        //  可沿用的前缀：它必须**恰好盖住库里已有的那 `old.size` 行**
        // ══════════════════════════════════════════════════════════════════
        // 前缀是"整库前 N 行的向量"。要拼出一份**行号对齐**的整库索引，只有
        // 一种形状成立：`N == old.size`（老行一条不缺），再往后接新编的那些。
        //
        // ⚠⚠ 此前这里写的是 `startFrom = min(prefix.first, texts.size)`，然后拿它
        //     **当 `texts` 的下标**去跳过新块、再把 `vecs` 前段当老行的向量 ——
        //     两个身份混在一个数上。一旦 `prefix == null`（0）或者两个数不等，
        //     拼出来的就是"第 i 行的向量配第 j 行的正文"：分数看着正常、
        //     注入的内容是隔壁那一行，**不崩、不报错**（ISSUE #228 的
        //     「导入两次内容变双份 / 前 24 条丢失」正是这一族）。
        //
        // 判据只做减法：盖不满就**如实退回**"正文在、索引待重建"，绝不近似。
        // 正文是**已经落盘**的（第一步就写了），所以退回时**绝不能**再走老路
        // 补导一次 —— 那会让同一次导入落两份（用户看到的"内容变双份"）。
        val prefix = reusablePrefix(ctx, name, fullFp)
        if (prefix == null || prefix.first != old.size) {
            val d = RagIndexStore.lastDiff
            val why = if (prefix == null) "${d.field}：${d.detail}" else "前缀只盖住 ${prefix.first} 行"
            uiLog("[知识库] $name 没有可沿用的索引前缀（$why）——" +
                "新导入的 ${texts.size} 块要重算，但库里已有的 ${old.size} 行凑不齐向量，" +
                "索引待重建（正文已落盘）")
            return ImportIndexResult.NeedsRebuild("库里已有的 ${old.size} 行凑不齐向量")
        }
        // 沿用了几条 = 前缀盖住的那几行（= 库里已有的行数，一个不差）。
        val reusedEntries = prefix.first
        val statsBefore = EmbedBatchStats.snapshot()
        // `vecs` 从**前缀之后**接着装：它现在逐位对应 `allRows` 的**尾段**（新块）。
        val vecs = ArrayList<FloatArray?>(texts.size)
        var i = 0
        var failed = 0
        while (i < texts.size) {
            val end = minOf(i + batch, texts.size)
            // 与 [buildIndex] 同一条批级读数（`EmbedBatchStats.record`）——
            // 这条路此前**一笔都不记**，于是收尾那句"批级读数"读到的是 0 条，
            // 被说成"这一段建库一条都没编过（建库多半在开头就失败了）"：
            // 明明刚编完若干块，日志却在报一次并不存在的故障（ISSUE #228 的日志）。
            val batchT0 = System.nanoTime()
            val r = embedBatch(texts.subList(i, end), dim)
            val got: List<FloatArray?> = when (r) {
                is BatchEmbed.Ok -> {
                    EmbedBatchStats.record(EmbedBatchStats.Sample(
                        EmbedBatchStats.Route.BATCHED, end - i,
                        (System.nanoTime() - batchT0) / 1_000_000L))
                    r.vectors
                }
                is BatchEmbed.Unavailable -> {
                    EmbedBatchStats.record(EmbedBatchStats.Sample(
                        EmbedBatchStats.Route.FELL_BACK, end - i,
                        (System.nanoTime() - batchT0) / 1_000_000L))
                    List(end - i) { k -> embed(texts[i + k]) }
                }
            }
            for (k in i until end) {
                val v = got.getOrNull(k - i)
                if (v == null || v.size != dim) { vecs.add(null); failed++ } else vecs.add(v)
            }
            i = end
        }
        if (vecs.size != texts.size) return ImportIndexResult.NeedsRebuild("编码结果与块数不符")
        // ── 收尾：`save` 的硬校验（向量行数 == 正文行数）就是"能检索"的判据本身 ──
        val meta = org.json.JSONObject()
            .put("library", name)
            .put("entries", texts.size)
            .put("rows", texts.size)
            .put("chunks", texts.size)
            .put("skipped", failed)
            .put("dim", dim)
            .put("targetChars", cp.targetChars)
            .put("overlapChars", cp.overlapChars)
            .put("model", embedPath ?: "")
            .put("modelOutDim", embedOutDim)
            .put("pooling", embedPooling)
            .put("builtAt", System.currentTimeMillis())
            .put("failedIndices", org.json.JSONArray())
            .put("failReason", if (failed > 0) "unknown" else "")
            .toString()
        // ⚠⚠ 写下去的**正文必须是整库**（库里已有的 + 新来的），而向量必须是
        //     **与它行号对齐的那一整份** —— 这是 ISSUE #228 "导入直接覆盖旧知识库"
        //     的根因所在。
        //
        //     这里原来传的是 `save(..., rows = 由 texts 构造的那一份)`，而 `texts`
        //     只装**新导入的块**。`save` 的语义是"整库覆盖"（它写 `texts.jsonl`
        //     = `encodeTexts(rows)`），于是：
        //       · 第一步 `appendScan` 刚把"已有 + 新来的"写进 `texts.jsonl`（对的）；
        //       · 收尾 `save` 又用**只有新来的那些**把它整份覆盖 ——
        //         **库里原有的条目从磁盘上消失**（用户说的"覆盖旧知识库"）。
        //     而它**不会**被 `save` 的硬校验拦住：那份 `rows` 的条数与
        //     `vecs` 的条数恰好相等（都是新块），校验过。
        //     指纹也由 `save` 现算（按那几行），于是新写下的索引与那几行**自洽** ——
        //     界面上报"导入成功"，库里却只剩刚导进来的那几条。
        //
        //     所以这里把两边都拼成**整库**（行号逐位对齐）：
        //       · 正文 = `allRows`（已有 + 新来的）；
        //       · 向量 = 前缀那 `old.size` 行（**逐位搬，一个都不重算**）+ 新编的
        //         那 `texts.size` 块；
        //       · `save` 的硬校验（向量行数 == 正文行数）于是恰好守住
        //         "两条路拼出来的是一整份对齐的库"这件事。
        //
        // ⚠⚠ 顺序是**判据的一部分**：老行在前（前缀）、新块在后，两者靠
        //     "`old.size + texts.size == allRows.size`"这一条绑住。写反了、或
        //     把 `vecs` 的前段当老行的向量（ISSUE #228 的老写法）就是整体错位：
        //     分数看着正常、注入的是隔壁那一行，**不报错**。
        val allVecs = ArrayList<FloatArray?>(allRows.size)
        for (k in 0 until old.size) {
            val off = k * dim
            allVecs.add(prefix.second.copyOfRange(off, off + dim))
        }
        for (k in texts.indices) allVecs.add(vecs[k])
        if (allVecs.size != allRows.size) {
            // 拼不成整库（行数对不上）—— 宁可退"待重建"，也不写一份少半截的库。
            uiLog("[知识库] $name 增量导入的向量行数（${allVecs.size}）与整库正文" +
                "（${allRows.size}）对不上 —— 索引待重建")
            return ImportIndexResult.NeedsRebuild("向量与整库正文行数对不上")
        }
        val flatAll = FloatArray(allRows.size * dim)
        var missing = 0
        for (k in allVecs.indices) {
            val v = allVecs[k]
            if (v == null || v.size != dim) { missing++; continue }
            System.arraycopy(v, 0, flatAll, k * dim, dim)
        }
        if (missing > 0) {
            // 有行没有向量（前缀不全 / 某块编失败）：整份**不能**当成齐整的库落盘 ——
            // 那会让"第 k 行没有向量"变成一条永远检索不到、也不报错的正文。
            // 走老口径：正文留着（`appendScan` 已经写了），索引待重建。
            uiLog("[知识库] $name 增量导入有 $missing 行没有向量 —— 正文已落盘，索引待重建")
            return ImportIndexResult.NeedsRebuild("$missing 行没有向量")
        }
        val ok = RagIndexStore.save(ctx, name, fpIn, dim, flatAll, meta, allRows)
        logBatchRoute(name, batch, statsBefore)
        if (!ok) {
            // 索引没写成 —— 正文是**已经落盘的**（第一步就写了），所以不是"导入失败"，
            // 而是"正文在、索引待重建"，与老口径逐字相同。
            uiLog("[知识库] $name 增量导入的索引写入失败 —— 正文已落盘，索引待重建")
            return ImportIndexResult.NeedsRebuild("索引写入失败（存储空间？）")
        }
        val rebuilt = texts.size
        if (failed > 0) {
            // 有块编不出来：`save` 的硬校验会拒（向量行数对不上）—— 走到这里只可能是
            // `vecs[k]!!` 在失败行上抛了。所以这一支是**防御**：真出现就退老口径。
            uiLog("[知识库] $name 增量导入有 $failed 块编码失败 —— 索引待重建")
            return ImportIndexResult.NeedsRebuild("$failed 块编码失败")
        }
        // ⚠ 两个数都要**如实**：`reusedEntries` 是这次真的沿用下来的行数（前缀盖住的），
        //   而 `texts.size` 是这次真的新编的块数。它们相加 == 索引里的行数
        //   （= 这次写下去的全部向量），这是收尾唯一能自证"索引完整"的等式。
        uiLog("[知识库] $name 增量导入完成：沿用库里已有的 $reusedEntries 行，" +
            "只新编 $rebuilt 块 —— **无需重建**，检索照常可用")
        return ImportIndexResult.Indexed(fresh.size, reusedEntries, rebuilt)
    }

    /**
     * 把要导入的条目里**超长的那几条**先切段（[RagArchive.EMBED_MAX_CHARS]）。
     *
     * 切点用的是 [RagArchive.splitLongLine] 那一份（与 `.txt / .md` 导入、
     * 与信封 / 裸数组 / JSONL 三条形状汇合处**同一套**）：同一份数据不该因为
     * 走哪条导入路而切得不一样。本函数只负责"哪些条目要 pave"这一层筛子。
     *
     * `nosplit` 是"硬切发生了几次"（窗口里一个句读都没有）—— 它要如实报出来，
     * 否则用户看到的是"这条为什么检索不太准"，而线索为零。
     */
    private fun paveLongEntries(incoming: List<RagIndexStore.Entry>): PaveResult {
        var nosplit = 0
        val out = ArrayList<RagIndexStore.Entry>(incoming.size)
        for (e in incoming) {
            if (e.text.length <= RagArchive.EMBED_MAX_CHARS) { out.add(e); continue }
            for (seg in RagArchive.splitLongLine(e.text) { nosplit++ }) {
                out.add(RagIndexStore.Entry(seg, e.source))
            }
        }
        return PaveResult(out, nosplit)
    }

    /** [paveLongEntries] 的产物：切好的条目 + 硬切计数。 */
    private class PaveResult(val entries: List<RagIndexStore.Entry>, val nosplit: Int)

    /** [importRagEntriesWithIndex] 的结果。三态 —— UI 要三句不同的话。 */
    sealed class ImportIndexResult {
        /**
         * 索引**已经跟上**（检索照常可用，命中的就是新导入的内容）。
         *
         * @param added   本次落进正文的新条目数（pave 之后的）
         * @param reused  库里原有、向量被沿用下来的条目数
         * @param rebuilt 本次真正编码过的块数
         */
        data class Indexed(val added: Int, val reused: Int, val rebuilt: Int) : ImportIndexResult()
        /**
         * 正文**已落盘**、索引待重建（`why` 是人话原因）。**不是失败** —— 字没丢。
         *
         * ⚠⚠ 走到这一支 = `appendScan` 已经把新条目写进 `texts.jsonl` 了。
         * 所以调用方**绝不能再导一次**（那会让同一次导入落两份 —— 用户看到的
         * "重复导入本次内容两次、最后的内容变双份"）。它该做的只有一句提示：
         * 点「建库/重建」。
         */
        data class NeedsRebuild(val why: String) : ImportIndexResult()
        /**
         * 这条路走不通，而且**一个字节都没写**（库不合条件 / [RagIndexStore.appendScan]
         * 的 BROKEN）。调用方应当退回老的"只落正文"那条（[importRagEntries]）。
         */
        object None : ImportIndexResult()
        /** 正文没写成（一个字节都没写）—— 调用方可以退回老路补一次。 */
        object Failed : ImportIndexResult()
    }

    private fun flatOf(vecs: List<FloatArray?>, dim: Int): FloatArray {
        val flat = FloatArray(vecs.size * dim)
        for (k in vecs.indices) {
            val v = vecs[k] ?: continue
            System.arraycopy(v, 0, flat, k * dim, dim)
        }
        return flat
    }

    /**
     * 「磁盘上那份索引能不能被**导入**沿用」—— [importRagEntriesWithIndex] 的前置判据。
     *
     * ⚠ 它与 [indexUsableForRewrite] 长得几乎一样、判据却**故意相反**：
     *   · 改字（那条）**只认齐整**：它要新算向量，批大小会渗进新算的那几条，
     *     所以 `g` 不同就不敢动；半份也不能改（后面那些行本来就没向量）。
     *   · 导入（这条）**半份也认、只差 `g` 也认**：它搬的全是**磁盘上已有的**
     *     向量，一个都不新算，所以批大小与它无关；而"半份"正是上一次导入或
     *     上一次被杀留下的状态，恰恰是这条路最该救的那个场景。
     * 判据仍然是**同一把尺子**：[RagIndexStore.load]。两处各写一遍"模型路径
     * 相同吗"的近似，就是下一次分家。
     */
    /**
     * 「磁盘上那份索引里**已经算好的向量前缀**」—— 导入收尾的**唯一**取值处。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么不能直接调 [RagIndexStore.loadPrefix]
     * ══════════════════════════════════════════════════════════════════════════
     * [RagIndexStore.loadPrefix] 只认 [RagIndexStore.Load.partial]。而 `load` 走到
     * 那一支之前有**两道**闸，`appendScan` 落下的那份很可能卡在**第一道**上：
     *
     *   · 第一道 = 指纹比对。`load` 拿它与 `expectFp` **逐字段**比（`compareFp`），
     *     不等就 `none` —— 还轮不到"count vs 正文档数"那一步。
     *     用户日志那一行（`没有可沿用的索引前缀（x：正文 hash 变了：…）`）
     *     说的就是这一道：盘上那份索引**描述的正文**已经不是当前这一份。
     *     ⇒ 这时候不该"重算 + 覆盖"，而该**如实退回**（"正文在、索引待重建"）。
     *   · 第二道 = `count == 正文档数 - 1`（齐整）-> `valid` -> `loadPrefix` 给 null。
     *     那不是"没有可沿用的东西"，而是"前缀就在文件里、只是没有缺口"。
     *
     * 两条都要回答，所以"取前缀"这件事收在**这一个函数**里（调用方只关心
     * "有多少块能用"），而不是让调用方去猜 `loadPrefix` 为什么返回 null。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 三支准入（判据只有一把尺子：`load` / `compareFp`）
     * ══════════════════════════════════════════════════════════════════════════
     *   ① `load` 判 `partial`（"自洽地少了一截"）—— 这就是 `loadPrefix` 那一支，
     *      直接取它；
     *   ② `load` 判 `valid`（**齐整**）—— 取前 `count` 条。
     *      `count == 正文档数-1` 时"前 count 条"**就是全部**；
     *   ③ `load` 说不可用、但**只差 `g`**（批大小）—— 放行。
     *      理由与 [indexUsableForImport] 逐字相同：这里搬的全是**磁盘上已有的**
     *      向量，一个都不新算，批大小与"搬"这件事无关。
     *      ⚠ 这一支**必须**在，否则"用户改过「合批」那一格"就会退化成
     *        "一份向量都搬不动、新块全部重算"—— 那正是 [appendScan] 里
     *        约束② 那一大段要防的形态。判据用 [RagIndexStore.FpDiff.onlyBatch]，
     *        不自己比字符串。
     *
     * ⚠ 三支都要求**语义同源**（模型 / 切块 / 正文 hash / 行数）：
     *   ① ② 由 `load` 的指纹比对保证；③ 由 `compareFp` 的"语义字段全同 +
     *   只差 `g`"保证。所以"换过模型"或"正文被改过"时一份向量都搬不动 ——
     *   调用方会走 [ImportIndexResult.None]，与老口径逐字相同。
     *
     * ⚠ 不许在这里"尽力而为"：34 条向量里认得 30 条也**不能**搬 —— 那是
     *   "向量与正文行号错位"，而错位不报错（分数正常、注入的是隔壁那一行）。
     *
     * ⚠ 取回来的条数与"库里已有的条目数"不必相等：前缀短了（上一次导入只搬了
     *   一部分 / 上一次建库没建完）时，调用方会看到"有行没有向量"从而**退回
     *   「待重建」**——正文留着、索引如实作废，而不是写一份少半截的库。
     *   这条**必须**由调用方判（只有它知道"整库有多少行"）。
     */
    private fun reusablePrefix(ctx: android.content.Context, name: String,
                               expectFp: String): Pair<Int, FloatArray>? {
        // ① 指纹同源 + 自洽地少了一截：`loadPrefix` 那一支。
        RagIndexStore.loadPrefix(ctx, name, expectFp)?.let { return it }
        val raw = RagIndexStore.readIndexBytes(ctx, name) ?: return null
        val d = RagIndexStore.lastDiff
        // ② 指纹逐字段相同，但 `load` 判的是**齐整**（`count == 正文档数 - 1`）：
        //    前缀就在文件里，只是没有缺口。取前 `count` 条。
        //    ⚠ 判据必须是 `d.same`：只差 `g` 时走 ③；语义不同源时一份都不取。
        if (d.same) {
            val parsed = RagIndexStore.decode(raw) ?: return null
            val dim = parsed.first
            val count = parsed.second
            val vec = parsed.third
            if (dim <= 0 || count <= 0 || count * dim > vec.size) return null
            // `count` 与正文的关系已由 `load` 比过（`valid` 的定义就是
            // "count == r-1"）—— 这里不再推一遍：两处各推一遍就是下一次分家。
            return count to vec.copyOfRange(0, count * dim)
        }
        // ③ 只差 `g` 那一格：`load` 判 none（`compareFp` 报 onlyBatch），但向量
        //    语义同源 —— 理由见函数头 ③。
        // ⚠ 判据是 **`onlyBatch`**，不是 `!same`：两者只在"差了好几处"时分叉，
        //   而那一档恰恰一份都不许取（语义不同源）。写成 `!same` 会把"差了好几处"
        //   也放进来，取回一段**旧语义**的向量当本轮前缀 —— 检索分数对、内容全错。
        //   （这一行曾被一次无关改动连带改成 `d.same`，方向正好反了：`d.same` 时
        //   上面那一支已经 return，于是这一支永不生效 —— 改过「合批」的库永远
        //   取不回前缀，旧向量被整段重算覆盖。）
        if (!d.onlyBatch) return null
        val textsRaw = RagIndexStore.readTexts(ctx, name) ?: return null
        // 「可续」的界与正文 hash 一致都由这个纯函数回答（与 `load` 内部同一份）——
        // 它不比模型身份，而那一半已经由 `onlyBatch` 这一支回答过了。
        val count = RagIndexStore.resumableCount(raw, textsRaw) ?: return null
        val parsed = RagIndexStore.decode(raw) ?: return null
        val dim = parsed.first
        val vec = parsed.third
        if (dim <= 0 || count <= 0 || count * dim > vec.size) return null
        return count to vec.copyOfRange(0, count * dim)
    }

    private fun indexUsableForImport(ctx: android.content.Context, name: String): Boolean {
        val cp = RagIndexStore.ChunkParams()
        val fpIn = embedFpInput(cp, EmbedBatchPref.get(ctx)) ?: return false
        val textsRaw = RagIndexStore.readTexts(ctx, name) ?: return false
        val fp = RagIndexStore.fingerprintOf(
            fpIn, RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))
        // ⚠ 必须**真的去问** `RagIndexStore.load`：它是「这份索引能不能被沿用」的
        //   唯一尺子（与检索同一份）。写死 `Load.none` 之后下面两行恒假，整条
        //   判据就只剩「只差 g」那一支 —— 而 `load` / `lastDiff` 的副作用
        //   （`lastDiff` 由它写）也就不会发生，那一支读到的永远是上一轮的残值。
        val l = RagIndexStore.load(ctx, name, fp)
        if (l is RagIndexStore.Load.valid || l is RagIndexStore.Load.partial) return true
        // 只差 `g` 一处时放行（上面那段说明了为什么这里与改字相反）。
        // ⚠ 判据用 [RagIndexStore.FpDiff.onlyBatch]，不自己比字符串。
        val d = RagIndexStore.lastDiff
        return !d.same && d.onlyBatch
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  追加一条 / 删除单条（RAG 阶段 3）—— 只编码一条，其余向量原样搬
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 阶段 2 之前，"加一条"只有两条路：重粘全量再重建（100 条要等 5~30 秒），
    // 或者改完正文只保存（索引立刻失效、检索停用）。于是"维护知识库"在用户手里
    // 永远是一次**整库操作**，而他想做的往往只是"补一句笔记"。
    //
    // 这一层只做两件事：把**一条**文本编码成向量，然后把它交给存储层去做
    // 追加/删除。判据（行号对齐 / 指纹 / 三态）全在 [RagIndexStore] 里，
    // 这里只负责"别把没编码成功的东西当成编码成功"。

    /**
     * 追加一条：只编码这一条，索引里其余向量原样搬。
     *
     * 返回 null = 成功；否则是给用户看的失败原因。**不抛异常**（后台线程上跑）。
     *
     * 先决条件与建库**同一组**（缺一不可，缺哪个都有对应的静默失效）：
     * · 库名合法（非法名不静默回落成另一个名字）；
     * · 该库**已有索引**（没有索引的目录不会出现在库列表里，写进去等于写进一个
     *   谁也看不见的孤儿目录 —— "存成功了但看不到"比报错难查得多）；
     * · 嵌入模型已加载（没加载时 embed() 返回 null，而"追加成功"照样显示）；
     * · 该库的指纹**对得上**（正文被改过 / 换过模型时，先把向量补齐会造成
     *   "旧向量 + 新正文"的一份索引 —— 那不是"追加"，那是"假装它是齐的"）。
     *
     * ⚠ 最后一条是本函数与"保存正文"的分工线：**改过正文的库不许追加**，
     * 必须先「建库/重建」。追加是"索引本来是齐的，我只是在末尾补一条"，
     * 而"索引本来就不齐"的情况下补齐末尾，只会让不齐的那部分更难发现。
     */
    fun addEntry(ctx: android.content.Context, name: String, text: String,
                 source: String = ""): String? {
        if (RagIndexStore.dirOf(ctx, name) == null) return "库名非法（只允许字母/数字/中文/-/_/.，且非空）"
        if (!RagIndexStore.isPresent(ctx, name)) return "磁盘上没有这个库：先新建或导入一个"
        if (RagIndexStore.indexFile(ctx, name)?.isFile != true) return "该库还没有索引：先点「建索引/重建索引」"
        if (!hasEmbedModel) return "尚未加载嵌入模型（向量只能用它算）"
        val dim = embedDim
        if (dim <= 0) return "嵌入模型维度取不到（加载可能不完整）"
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "这一条是空的（空文本编码不出来，也不会进索引）"
        // 超长先截到一块的大小：追加**不做分块**（一条进正文、一条进向量区，
        // 行号一一对应）。让一条超长文本整条进去的后果是它被 native 截断到 n_ctx，
        // 向量只覆盖开头那部分语义，而检索命中它时注入的却是**全文** ——
        // 相关性判断与注入内容说的不是一件事。这里明确截到目标块大小并提示。
        val cp = RagIndexStore.ChunkParams()
        // ⚠ 截断**必须出声**（第 3 步，用户拍板）：旧代码注释写着"并提示"，但代码里
        // 没有提示 —— 于是用户以为整条进去了，实际只索引了前 [targetChars] 个字，
        // 而检索命中它时注入的是**全文**。也就是说「相关性判断」与「注入内容」
        // 说的不是一件事：用户看到"这条命中得很准"，模型看到的却是另一段文本。
        // 这里截断后仍照常编码（不改变行为），只多给一句可操作的话。
        val truncated = trimmed.length > cp.targetChars
        // ⚠ 按**字符边界**截（不是裸 `substring`）：截断点直接决定落进
        //    `texts.jsonl` 的那条正文。切在代理对中间会留下一个孤立代理 ——
        //    内存里 JSON 往返与 `==` 全都通过（单测用 `.length` 断言恒绿），
        //    而 `toByteArray(UTF_8)` 时被替换成 `?`（不可逆）。
        //    分块路径（[chunk]）早就走 `alignUtf8` 做同一件事，这里此前漏了。
        //    与 `rag_index.h` 的 `align_utf8_back` 同源，见
        //    [TextBoundary.clipAtCharBoundary]。
        val clipped = if (truncated) TextBoundary.clipAtCharBoundary(trimmed, cp.targetChars) else trimmed
        val v = embed(clipped) ?: run {
            // 失败详情**带上这一条的内容**（截断到 40 字）：用户点「追加」时
            // 他知道自己刚敲了什么，但 100 条里其他坏条目他不知道 —— 而"哪一条"
            // 是唯一能让他动手的信息。早先那句"检查内存"在两个根因下都成立，
            // 方向还是错的（真正的原因多半是池化/维度，不是内存）。
            val why = lastEmbedFailure()?.describe() ?: "编码失败"
            val head = if (trimmed.length > 40) trimmed.substring(0, 40) + "…" else trimmed
            return "这一条追加失败：「$head」—— $why"
        }
        if (v.size != dim) return "编码维度不符（${v.size} != $dim，模型可能被换过）"
        val src = if (source.isNotBlank()) source else "追加-$name"
        return when (RagIndexStore.appendEntries(ctx, name,
            listOf(RagIndexStore.Entry(clipped, src, v)))) {
            RagIndexStore.EditResult.OK -> {
                uiLog("[知识库] $name 追加 1 条（只编码这一条，其余向量原位保留）")
                // 成功也要把截断说出来：这一条的**向量**只覆盖了前 [cp.targetChars]
                // 个字，而检索命中它时注入的是全文 —— 不提示，用户永远不知道
                // "这条为什么检索不太准"。
                if (truncated) "已追加，但这一条超过 ${cp.targetChars} 字，只索引了前 ${cp.targetChars} 字" +
                    "（检索命中时仍会注入全文）。建议拆成多条追加。" else null
            }
            RagIndexStore.EditResult.STALE -> {
                uiLog("[知识库] $name 追加了正文但向量没落进去，索引需重建")
                "正文已追加，但索引没跟上（需点「建库/重建」才可检索）"
            }
            RagIndexStore.EditResult.FAILED ->
                "追加失败：该库的索引与正文对不上（或正文被改过）—— 请点「建库/重建」"
        }
    }

    /**
     * 「改内容、行数不变」→ **只重算改过的那几行向量**，正文与向量一起写回
     * （ISSUE #228；返回 null = 全部写回且索引**仍然可用**）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 这条路径解决的正是用户问的那件事
     * ══════════════════════════════════════════════════════════════════════════
     * 用户原话：*"改内容、行数不变 → 重算改那一行。"*
     *
     * 在此之前，在正文框里改一个字只有两条路：
     *   · 「保存正文」—— 只写 `texts.jsonl`，索引随即失效，**要整库重建**
     *     （几千条 = 几十分钟）；
     *   · 「删掉那一条 + 追加一条」—— 换了行号，也换了向量。
     * 改错别字 / 补标点 / 修一句被切坏的话，是编辑里最常见的动作，而它此前
     * 无论走哪条都**不划算**。这一条把它的代价降到"一条前向"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么"只算改过的那几行"成立
     * ══════════════════════════════════════════════════════════════════════════
     * 一条正文的向量**只由那一条正文决定**（切块在建库时就定了，这里不动块集合）。
     * 所以改第 3、7 行时，其余每一行的向量**逐位仍然是对的** —— 这正是
     * [RagIndexStore.replaceRowsWithVectors] 只按行偏移替换的依据。
     *
     * ⚠ 前提是**索引本来就是齐整且与正文同步的**（见那个函数里 ③ 那段）。
     *   不成立时整次退回"只写正文 + 报需重建"（[RagIndexStore.replaceRowsInPlace]
     *   那条老路）—— 调用方据返回值区分，见 [RewriteResult]。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么**必须**先确认"当前索引可用"，而不是直接编码
     * ══════════════════════════════════════════════════════════════════════════
     * 1. 指纹里那 12 格（模型身份 / 池化 / 切块参数 / 批大小）是从**磁盘上那份索引**
     *    照搬的（见 [RagIndexStore.replaceRowsWithVectors]）。若当前嵌入模型与
     *    那份索引的模型**不是同一份**，我们算出来的向量与索引里其余行**不同源** ——
     *    写下去就是"一份索引里两种语义"，而它**不报错**（分数看着像随机）。
     *    这一条用 [RagIndexStore.load] 那把**与检索同源**的尺子挡：
     *    "索引可用" = "指纹与当前模型/参数逐字段相同"，正是我们需要的那个前提。
     *    自己另写一个"模型路径相同吗"的近似判据就是下一次分家（本仓库吃过的）。
     * 2. 索引**半份**（建库中途被杀）时后面那些行没有向量，谈不上"改哪一行"。
     *    这种情况 [RagIndexStore.replaceRowsWithVectors] 也会拒 —— 但先在这里
     *    分出来，是为了给用户**不同的一句话**（续编 vs 重建），而不是笼统失败。
     *
     * @param indices 正文文件里的行号（可乱序）
     * @param texts   与 [indices] 逐位对齐的新正文（**解码之后**的）
     * @return [RewriteResult.IndexRebuilt] = 索引仍然可用（检索照常，命中的是新内容）；
     *         [RewriteResult.TextsOnly]   = 正文写回了、但索引**待重建**
     *         （改动不丢，检索停用 —— 与老口径逐字相同）；
     *         [RewriteResult.Failed]      = 一个字节都没写。
     */
    fun rewriteRows(ctx: android.content.Context, name: String,
                    indices: List<Int>, texts: List<String>): RewriteResult {
        if (indices.isEmpty() || indices.size != texts.size) return RewriteResult.Failed(
            "没有要改的行")
        if (RagIndexStore.dirOf(ctx, name) == null) return RewriteResult.Failed(
            "库名非法（只允许字母/数字/中文/-/_/.，且非空）")
        if (RagIndexStore.indexFile(ctx, name)?.isFile != true) return RewriteResult.Failed(
            "该库还没有索引：先点「建索引/重建索引」")
        if (!hasEmbedModel) return RewriteResult.Failed(
            "尚未加载嵌入模型：改字要重算这一条的向量")
        val dim = embedDim
        if (dim <= 0) return RewriteResult.Failed("嵌入模型维度取不到（加载可能不完整）")

        // ── 编码**改过的那几条**（逐条，不是整库）───────────────────────────
        // ⚠ 逐条而不是合批：这里的条数就是"用户改了几行"（通常 1~3 条），
        //   合批那点固定开销的节省在这个量级上等于零，而合批会引入一个真问题 ——
        //   `g`（每批条数）进了指纹，而磁盘上那份索引的 `g` 是**建库时**的值。
        //   这里编码用的是"改了几条"，与那个值无关；指纹那一格仍然照搬磁盘那份
        //   （见 [RagIndexStore.replaceRowsWithVectors]），所以不会写出一份
        //   "指纹说 g=8、实际是 1 条编出来的"的索引。逐条编码让这件事根本没有歧义。
        val vecs = ArrayList<FloatArray>(texts.size)
        val failed = ArrayList<Int>()
        for (k in texts.indices) {
            val t = texts[k].trim()
            if (t.isEmpty()) return RewriteResult.Failed(
                "第 ${indices[k] + 1} 行改成了空的 —— 空文本编码不出来（要删这一条请用列表里的「删」）")
            val v = embed(t)
            if (v == null || v.size != dim) {
                failed.add(k)
                vecs.add(FloatArray(0))
            } else {
                vecs.add(v)
            }
        }
        // ── 有任何一条编不出来 -> **整次不写**，退回"只写正文 + 待重建" ────────
        // 为什么不是"成功的写回、失败的那条留着"：那会写出一份"部分新语义"的索引
        // ——成功的行是新向量、失败的行还是旧向量（而它的正文**已经变了**），
        // 于是那一行"向量与正文不同源"，症状是"这一条检索不准"，
        // 而它**没有任何现象**。
        // 退回老路（只写正文）之后，整个索引被判为"需重建" —— 一个说得清、
        // 且用户下一步动作唯一的状态。详见 [RewriteResult] 的三态。
        if (failed.isNotEmpty()) {
            val why = lastEmbedFailure()?.describe() ?: "编码失败"
            val rows = failed.map { indices[it] + 1 }.joinToString(",")
            val textsWritten = RagIndexStore.replaceRowsInPlace(ctx, name, indices, texts)
            uiLog("[知识库] $name 有 ${failed.size} 条（第 $rows 行）重算向量失败（$why）——" +
                "已只写回正文，索引待重建")
            return if (textsWritten == RagIndexStore.EditResult.OK)
                RewriteResult.TextsOnly("第 $rows 行没能重算向量（$why）")
            else RewriteResult.Failed("第 $rows 行重算失败，且正文也没写进磁盘")
        }

        // ── 先确认"磁盘上那份索引此刻可用" —— 否则退回老路（见函数头 1/2）──────
        // 尺子与检索**同源**：`embedFpInput` + 磁盘上的正文 + 当前的批大小偏好。
        // 批大小取 [EmbedBatchPref]（而不是"本次编了几条"）：判据是"当前配置下
        // 这份索引还算不算数"，与 [search] 那一处逐字相同。
        if (!indexUsableForRewrite(ctx, name)) {
            val textsWritten = RagIndexStore.replaceRowsInPlace(ctx, name, indices, texts)
            uiLog("[知识库] $name 索引与当前模型/参数不同源（或只是半份）——" +
                "这次只写回正文，索引待重建")
            return if (textsWritten == RagIndexStore.EditResult.OK)
                RewriteResult.TextsOnly("索引当前不可用（与建库时的模型/参数不同，或只是半份）")
            else RewriteResult.Failed("正文没能写进磁盘（行号对不上或磁盘满）")
        }

        return when (RagIndexStore.replaceRowsWithVectors(ctx, name, indices, texts, vecs)) {
            RagIndexStore.EditResult.OK -> {
                uiLog("[知识库] $name 按行改字 ${indices.size} 条（第 " +
                    "${indices.joinToString(",") { (it + 1).toString() }} 行）——" +
                    "只重算这几条的向量，其余向量原位保留，**无需重建**")
                RewriteResult.IndexRebuilt
            }
            // `STALE` = 索引写下去了、正文没写上（或反过来）。两个文件此刻**不同源**，
            // 于是索引会被判"需重建" —— 与"只写了正文"同一种可恢复状态，
            // 但对用户是另一句话（"这次没改全"）。
            RagIndexStore.EditResult.STALE -> {
                uiLog("[知识库] $name 按行改字只写进去了一半（索引与正文不同源），索引待重建")
                RewriteResult.TextsOnly("这次只写进去了一半，索引待重建")
            }
            RagIndexStore.EditResult.FAILED -> {
                // 走到这里说明上面两道闸都过了、而写盘那一跳仍拒了 ——
                // 最可能的是"读盘之后、写盘之前库被改过"（行号对不上）。
                // 退回老路再试一次，让用户的改动至少落到正文里。
                val textsWritten = RagIndexStore.replaceRowsInPlace(ctx, name, indices, texts)
                uiLog("[知识库] $name 按行改字落盘被拒（索引状态在读取之后变了）")
                if (textsWritten == RagIndexStore.EditResult.OK)
                    RewriteResult.TextsOnly("索引状态在读取之后变了")
                else RewriteResult.Failed("这一页的改动没能写进磁盘 —— 请点一下这个库重新载入再试")
            }
        }
    }

    /**
     * 「磁盘上那份索引此刻还算数吗」—— 给 [rewriteRows] 用的前置判据。
     *
     * ⚠ 它与 [search] **共用同一把尺子**（同源取值 + [RagIndexStore.load]），
     *   不是另写一个"模型路径相同吗"的近似：后者的失效形态是"索引看着可用、
     *   实际是另一种语义"，而它不报错。判据只回答"能不能按行改字"，
     *   不回答"检索会不会命中" —— 所以它**不**碰 topK / 阈值。
     */
    private fun indexUsableForRewrite(ctx: android.content.Context, name: String): Boolean {
        val cp = RagIndexStore.ChunkParams()
        val fpIn = embedFpInput(cp, EmbedBatchPref.get(ctx)) ?: return false
        val textsRaw = RagIndexStore.readTexts(ctx, name) ?: return false
        val fp = RagIndexStore.fingerprintOf(
            fpIn, RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))
        // ⚠ 只认 [RagIndexStore.Load.valid]：半份（partial）**不可**按行改字 ——
        //   它后面那些行本来就没有向量（见函数头 2）。
        return RagIndexStore.load(ctx, name, fp) is RagIndexStore.Load.valid
    }

    /**
     * [rewriteRows] 的结果。**三态**，不是"成功/失败"两态。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么必须分三态（而不是返回一个 `String?` 的失败原因）
     * ══════════════════════════════════════════════════════════════════════════
     * 这三态在界面上要三句**不同**的话，而用户下一步动作也各不相同：
     *
     *   · [IndexRebuilt] —— 改完了，**索引仍然可用**。这是本轮新增的那条路：
     *     检索照常，命中的就是新内容。回执应当说"无需重建"。
     *   · [TextsOnly]    —— 改动**没丢**（正文写回磁盘了），但索引判为待重建。
     *     它与 P1-b 的老口径**逐字相同**（`replaceRowsInPlace` + 指纹过期）。
     *     用户下一步：点「建索引/重建索引」。回执**必须**说清"改动在、检索停用"，
     *     否则他会以为这次保存没生效，然后再改一遍（或者更坏：重导，条目翻倍）。
     *   · [Failed]       —— 一个字节都没写。回执要给"怎么重试"。
     *
     * 把 [TextsOnly] 与 [Failed] 压成一种（"失败"）是本仓库反复记录过的那种错：
     * 报失败时用户以为"没存上"、重敲一遍；而实际是"存上了、只是还搜不到"。
     * 这两种情况的下一步操作完全不同（重敲 vs 点重建）。
     */
    sealed class RewriteResult {
        /**
         * 正文与向量都写回了，**索引仍然可用**（正文 hash 与向量已重新对齐）。
         *
         * 不含任何载荷：这条路成功时"改了几行、写进了哪个库"由调用方（UI）
         * 自己知道，而"无需重建"是它唯一需要额外说的一句。
         */
        object IndexRebuilt : RewriteResult()

        /**
         * 正文写回了、索引**待重建**（`why` 是人话原因，用于日志/回执）。
         *
         * ⚠ 它**不是失败**：用户的字一个都没丢。别把它显示成"保存失败"。
         */
        data class TextsOnly(val why: String) : RewriteResult()

        /** 一个字节都没写（`why` 是给用户看的失败原因）。 */
        data class Failed(val why: String) : RewriteResult()
    }

    /**
     * 删除**某一行**（正文与向量同时移掉）。
     *
     * [index] 是**正文文件的行号**（检索命中报的那个行号）。
     * 返回 null = 成功；否则是给用户看的失败原因。**不抛异常**。
     *
     * 这里不需要嵌入模型 —— 删除只搬向量，不编码任何东西。缺模型时把删除
     * 也一并禁掉是"顺手加一道闸"，而它挡住的是一个真实场景：用户在没加载
     * 模型的机器上想清理一条笔记，做不到，只能整库删掉重来。
     */
    fun deleteEntry(ctx: android.content.Context, name: String, index: Int): String? {
        if (RagIndexStore.dirOf(ctx, name) == null) return "库名非法（只允许字母/数字/中文/-/_/.，且非空）"
        if (RagIndexStore.indexFile(ctx, name)?.isFile != true) return "该库还没有索引"
        return when (RagIndexStore.removeEntry(ctx, name, index)) {
            RagIndexStore.EditResult.OK -> {
                uiLog("[知识库] $name 删除第 ${index + 1} 行（正文与向量一起移掉，后面的行前移一位）")
                null
            }
            RagIndexStore.EditResult.STALE -> {
                uiLog("[知识库] $name 删除了正文但索引没跟上，需重建")
                "正文已删除，但索引没跟上（需点「建库/重建」才可检索）"
            }
            // 行号不合法是**最常见**的失败形态（用户点的那一行在两次刷新之间
            // 已经被删过 / 索引与正文本来就不齐），所以这句话要说清"怎么刷新"，
            // 而不是一句"删除失败"。
            RagIndexStore.EditResult.FAILED ->
                "这一行删不了：索引与正文对不上（或它已经是最后一条）—— 刷新后重试，或点「建库/重建」"
        }
    }

    /**
     * 某一行在**当前正文文件**里的正文与来源（UI 渲染用，读失败返回 null）。
     *
     * 与 [textAt] 是同一份实现，但那条路专为"检索注入"服务；这里显式再引一次，
     * 是为了让 UI 侧不必知道注入那条路的任何细节。
     */
    fun entryAt(ctx: android.content.Context, name: String, index: Int): RagIndexStore.Entry? =
        try { RagIndexStore.entryAt(ctx, name, index) } catch (t: Throwable) { null }

    /**
     * 分块（Kotlin 侧）。与 `rag_index.h` 的 `chunk_text` / `pick_chunk_cut`
     * **逐条同口径**（不再"允许边界略有差异"——见下）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 老口径被本轮改掉了
     * ═══════════════════════════════════════════════════════════════════════
     * 原先这里写着"两份实现只要求块大小与重叠一致，边界允许略有差异（分块本来
     * 就不是唯一解）"。那个口径带来过一个真实故障的方向：两边的**降级规则**分家
     * 之后，"建库时按 A 规则切、检索时按 B 规则算指纹"，而指纹里只有 `t`/`o`
     * 两个数，差异**完全不可见**。所以本轮把两份实现的规则表与降级次序焊死，
     * 由 `chunk_seps_consistent` 与 `chunk_rule_consistent` 两条判据正面钉住。
     *
     * 规则（与 C++ 侧同一张表、同一个次序）：
     *   1. 段落 `\n\n`；2. 换行 `\n`；3. 句读 `。！？；，、!?;,`；4. 硬截 `pos + target`。
     *
     * 三条不变式：
     *   · 窗口只在 `(pos, pos+target]` 内找 —— **不往窗口外找**（那是"整块丢字"的成因）；
     *   · 候选切点对齐后 `<= pos` 就当**没找到**，继续降级（不许退化成"前进 1 字节"）；
     *   · 每轮**严格前进 1 个完整码点**，硬截也按码点对齐。
     *
     * @param hardCuts 出参：硬截发生了几次。为什么报出来见 [buildIndex] ——
     *   硬截 = "窗口内一个句读都没有"，那种块天生检索不准，而它此前没有任何痕迹。
     * @param truncated 出参：**是否撞上 `maxChunks` 提前收工**（= 文档后半段没进索引）。
     *   与 [hardCuts] 是两件事：那个说"块边界糙"（内容全在），这个说"块没了"
     *   （**内容真的少了一截**）。后者在检索上与"文档里本来就没有"逐字相同，
     *   所以必须由调用方写成日志，见 [buildIndex]。
     */
    fun chunk(text: String, cp: RagIndexStore.ChunkParams,
              hardCuts: IntArray? = null,
              truncated: BooleanArray? = null): List<RagIndexStore.Entry> {
        if (hardCuts != null && hardCuts.isNotEmpty()) hardCuts[0] = 0
        if (truncated != null && truncated.isNotEmpty()) truncated[0] = false
        val out = ArrayList<RagIndexStore.Entry>()
        if (text.isEmpty()) return out
        // targetChars <= 0 = 整篇一块（与 C++ 侧同一条退化路径）。
        if (cp.targetChars <= 0) {
            if (text.isNotBlank()) out.add(RagIndexStore.Entry(text.trim(), ""))
            return out
        }
        var overlap = cp.overlapChars
        if (overlap >= cp.targetChars) overlap = if (cp.targetChars > 1) cp.targetChars / 2 else 0
        var pos = 0
        // "正常收尾"的显式标志（见函数尾部的 truncated 判据）：只有"最后一块走到
        // 文末"那一支才算切完了，循环条件失败才算被截住。与 C++ 侧同一条控制流。
        var brokeByEnd = false
        while (pos < text.length && out.size < cp.maxChunks) {
            var end = minOf(pos + cp.targetChars, text.length)
            // `cutByRule` = 本轮是**软切**（窗口内找到了分隔符）还是**硬截**。
            // 它决定"下一块起点"能不能重叠 —— 与 C++ 侧同一条控制流，见下面那一段。
            var cutByRule = false
            if (end < text.length) {
                val cut = pickChunkCut(text, pos, end)
                if (cut > pos) {
                    end = cut
                    cutByRule = true
                } else {
                    // 第三级：硬截。对齐后不许退化成"前进 1 字节"——
                    // 中文尾字节落在 target 上时向前一个码点，仍 <= pos 才取下一个码点。
                    if (hardCuts != null && hardCuts.isNotEmpty()) hardCuts[0]++
                    var hard = alignUtf8(text, end)
                    if (hard <= pos) {
                        // target 小于一个码点时"向前对齐"会退到 pos 本身 —— 那一档
                        // 只能**向后**推进到下一个码点起点。`pos + 1` 会切在码点中间，
                        // 那一块 encode 必然失败（老实现栽的就是这条兜底）。
                        hard = nextUtf8Start(text, pos + 1)
                        if (hard <= pos) hard = text.length
                    }
                    end = if (hard > pos) hard else text.length
                }
            }
            val piece = text.substring(pos, minOf(end, text.length)).trim()
            if (piece.isNotEmpty()) out.add(RagIndexStore.Entry(piece, ""))
            if (end >= text.length) { brokeByEnd = true; break }
            // 下一块从 overlap 之前开始（并按字符边界对齐），但必须**严格前进**。
            //
            // ⚠ 硬截那一轮**不重叠**（`next = end`）。重叠的用途是"让语义跨块的那
            //   一段两边都看得见"，而硬截 = 窗口内**一个分隔符都没有**：回退 overlap
            //   得到的下一块，其窗口右端仍是"本轮起点 + target"，整段区间仍在上一轮
            //   已消化过的范围里 —— 那一轮同样找不到分隔符、同样硬截。于是
            //     · `overlap` 那段正文被切**两遍**（相邻块最长公共片段 ≥ overlap，
            //       检索时同一段正文互相抢 top-k）；
            //     · 硬截计数被灌水（"有 N 块是硬截的"里一半是重切）。
            //   软切那一轮**保留**重叠：切点由分隔符决定，"语义跨边界"是真需求。
            //   与 C++ 侧 `chunk_text` 逐条同形（差分测试钉着）。
            var next = if (cutByRule) alignUtf8(text, (end - overlap).coerceAtLeast(0)) else end
            if (next <= pos) next = end
            if (next <= pos) next = pos + 1
            pos = next
        }
        // 退出循环有两种原因：
        //   · 最后一块走到文末（`brokeByEnd`）—— **切完了**；
        //   · `out.size >= maxChunks` —— **被上限截住**，后半段一个字节都没进索引。
        // 后者必须报出去（它此前完全没有痕迹，见 [truncated] 出参的说明）。
        // ⚠ 判据不能写成 `pos < text.length`：最后一块走到文末时 `pos` 指向那一块的
        //   **开头**，它当然小于 length —— 于是每一份正常文档都会被报成"截断"。
        //   （C++ 侧第一版就是这么写的，`test_chunk_truncated_flag` 的四条正例当场抓到。）
        if (truncated != null && truncated.isNotEmpty()) truncated[0] = !brokeByEnd
        return out
    }

    /**
     * 窗口内找一个合法切点（返回 `<= pos` 表示"没找到，走硬截"）。
     *
     * 与 `rag_index.h` 的 `pick_chunk_cut` **同一条控制流**：先段落、后句读；
     * 同级里取最靠右的；候选点先对齐、对齐后 `<= pos` 就当没找到。
     */
    private fun pickChunkCut(text: String, pos: Int, end: Int): Int {
        // 实现搬到了 [TextBoundary.pickCut]：**同一张表、同一条控制流**要与
        // RagArchive 的导入切段共用（那边此前抄了第二份 + 第二张表，且没有任何
        // 判据绑着它）。这里只留一个转发，接口不变 —— 调用点与判据都不用动。
        return TextBoundary.pickCut(text, pos, end, kSepMaxWidth)
    }

    /**
     * 把下标 `i` 向前调整到一个**字符边界**（Kotlin 的 Char = UTF-16 code unit）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 这里修掉了两个真实缺陷（本轮差分测试撞出来的，都不是新增的）
     * ═══════════════════════════════════════════════════════════════════════
     * 旧实现是 `var j = i.coerceIn(0, s.length); while (j > 0 && (s[j].code and 0xC0) == 0x80) j--`
     * —— 它把 **C++ 那套按字节的判断**（continuation 是 `0b10xxxxxx`）直接搬到
     * Kotlin 的 **UTF-16 code unit** 上了，于是有两处错：
     *
     *   ① **越界**：`coerceIn(0, s.length)` 允许 `j == s.length`，而
     *      `while` 的条件先读 `s[j]` 再判 `j > 0` —— `s[s.length]` 直接抛
     *      `StringIndexOutOfBoundsException`。实测 `alignUtf8("甲文乙", 3)` 必抛。
     *      （Java/Kotlin 的 `&&` 从左到右求值，所以判据顺序真的是先取下标。）
     *   ② **误判**：`s[j].code and 0xC0 == 0x80` 判的是"这个 UTF-16 code unit
     *      的低字节长得像 continuation"。`文` = U+6587，`0x87 and 0xC0 == 0x80`
     *      —— 于是**正常的一个汉字被当成半个字符**，函数往回退一格，
     *      切点被凭空前移。实测 `alignUtf8("甲文乙", 1)` 返回 0 而不是 1。
     *      在 CJK 正文里这种"低字节恰好是 0x80~0xBF"的字**很常见**，
     *      于是块边界会随文档内容漂移，而两边（C++ 按字节 / Kotlin 按字符）
     *      切出的块从此对不上 —— 症状是"同一份文档建库与追加切得不一样"。
     *
     * 正确的判断只有一条：**Kotlin 的下标只有在代理对中间才是"半个字符"**。
     * 即 `s[j]` 是 low surrogate（U+DC00~U+DFFF）时，它和前面的 high surrogate
     * 构成一个码点，应当一起收进前一块 —— 于是退到那个 high surrogate 上。
     * BMP 字符（含全部 CJK）永远不在码点中间，所以这个函数对它们**恒等**，
     * 与 C++ 那套"按字节对齐"在语义上等效（C++ 的字节对齐也是为了保证
     * 不切在码点中间，只是它的单位是字节）。
     */
    private fun alignUtf8(s: String, i: Int): Int {
        if (s.isEmpty()) return 0
        // ⚠ `i >= s.length` 先**原样返回 length**，不能落进下面那个 `coerceIn`：
        //   旧写法 `coerceIn(0, s.length)` 允许 j == length，而 `while` 要读 s[j]
        //   —— `s[s.length]` 直接抛 StringIndexOutOfBoundsException（见上面 ①）。
        //   这里显式分流：到末尾/越界 -> length（调用方把它当"这一段收干净了"）。
        if (i >= s.length) return s.length
        // 低代理区：只有落在代理对中间才需要往前收一格。
        var j = i.coerceAtLeast(0)
        while (j > 0 && s[j].isLowSurrogate()) j--
        return j
    }

    /**
     * 从 `i` **向后**找下一个字符边界；找不到返回 `length`。
     *
     * 只在 `targetChars` 小于一个码点时（比如 target=1 而一"字"占好几个 code unit）
     * 需要：那时向前对齐会退到 `pos`、等于不前进，而 `pos + 1` 会切在代理对中间。
     * 与 `rag_index.h` 的 `next_utf8_start` 是同一个函数的两份写法。
     *
     * ⚠ 判断与 [alignUtf8] 同一口径：只有 **low surrogate** 才是"半个字符"
     *   （旧实现用 `(code and 0xC0) == 0x80`，对 `文` U+6587 会误判 —— 见 [alignUtf8]）。
     */
    private fun nextUtf8Start(s: String, i: Int): Int {
        var j = i.coerceIn(0, s.length)
        while (j < s.length && s[j].isLowSurrogate()) j++
        return j
    }

    /**
     * 从某个知识库检索与 `query` 最相关的若干条，连同**为什么是这个结果**。
     *
     * 返回 [Search] 三态：[Search.Hits]（有可用索引，可能 0 条命中）/
     * [Search.NoIndex]（没有可用索引，附逐字段差异）/ [Search.QueryFailed]（查询编码失败）。
     * 三态的**举证与处置都不同** —— 见 [Search] 的说明。早先把它们压成
     * `List<SearchHit>?` 是用户报的"日志说成功却不引用"的一部分成因。
     *
     * 为什么检索**不**顺手重建索引：见 [buildIndex] 的说明。这里只负责
     * "有就用"，且任何一步失败都降级成"没有索引"，绝不抛异常、绝不让请求失败。
     *
     * ── 一次检索的**结果 + 理由**（[Search]）───────────────────────────────
     *
     * 为什么不能只返回 `List<SearchHit>?`（第 2 步，用户拍板）：
     * 旧签名把三件**完全不同**的事压成同一个出口，而它们的处置**不同**：
     *
     *   · 索引不可用      -> 用户要去重建 / 换回模型（配置问题）；
     *   · 查询编码失败    -> 本轮是**偶发**的（模型/内存状态），重试或看 native 原因，
     *                        用户**不该**去动索引 —— 旧代码把它和"没命中"混在一起；
     *   · 真的没命中      -> 正常，什么都不用做（库里确实没有相关内容）。
     *
     * 三者写进日志的话**逐字不同**，用户与守卫都能一眼分清是哪一个。
     *
     * ⚠ 实现体刻意**留在 [search] 里**、不做"一行转发到另一个函数"：
     * `tools/run_rag_index_guard.sh` 一组判据锚的是 **`fun search` 的函数体**里
     * 有没有那一跳（`embedFpInput` / `fingerprintOf` / `load` / `rowsOf`）。
     * 把实现搬走、`search` 只留一行转发，会让那些判据**全部变红** —— 而实现其实
     * 一字没改。本仓库反复记录过这类"实现搬走反而判红"的坑。
     */
    sealed class Search {
        /** 有可用索引：`rank.hits` 是过阈值并截断到 top-k 的命中；`rank.best` 是最高分。 */
        data class Hits(val rank: RagIndexStore.Ranked) : Search()
        /** 没有可用索引（库不存在 / 指纹不符 / 文件损坏）—— 处置是重建或换回模型。 */
        data class NoIndex(val detail: String, val field: String, val stale: Boolean) : Search()
        /** 查询向量编码失败（偶发，与索引无关）—— 处置是重试/看 native 原因，**别动索引**。 */
        data class QueryFailed(val why: String) : Search()
    }

    fun search(ctx: android.content.Context, name: String, query: String,
               topK: Int = 3, minScore: Float = 0.35f): Search {
        if (query.isBlank()) return Search.Hits(RagIndexStore.Ranked(emptyList(), Float.NaN, 0))
        if (!hasEmbedModel) return Search.NoIndex("尚未加载嵌入模型", "embed", false)
        if (embedDim <= 0) return Search.NoIndex("嵌入模型维度取不到", "embed", false)
        // 指纹用的切块参数必须与建库时**同一份**。索引文件头里落的就是建库时的
        // 那一份，所以这里只要"当前配置"，它变 = 指纹不符 = 没索引（重建）。
        val cp = RagIndexStore.ChunkParams()
        // 正文 hash 从**当前磁盘上的正文文件**现算：
        //   · 正文文件缺失（阶段 1 的老索引 / 被手动删了）按 0 字节算 —— 与
        //     "本来是空库"用的是同一份输入，所以老索引会（且只会在换版本时）
        //     一次性被判为要重建，而不是靠"幸好偶然对上了"。
        //   · 读正文这件事只有几十 KB（100 条短笔记），比一次 prefill 便宜几个数量级，
        //     而它能挡住"用旧向量命中、注入新正文"这类**不报错**的错位。
        val textsRaw = RagIndexStore.readTexts(ctx, name) ?: ByteArray(0)
        // ⚠ 与建库**同一个取值处**（[embedFpInput]）。两处各写一遍的后果是
        // "建库成功、检索说参数变了"，而它不指向任何一处具体代码 ——
        // 用户只能反复重建（重建完还是那句）。
        // 批大小与建库**同一个取值处**（[EmbedBatchPref]）。检索这一边读的是
        // "当前配置的那个"（而不是本次建库实际跑的那个 —— 检索时没有"本次"）：
        // 用户把批大小从 8 改成 1，旧索引就该被判成"不可用"（重建）——
        // 而它正好就是"改批大小必须重建索引"这条心智负担的执行处。
        val fpIn = embedFpInput(cp, EmbedBatchPref.get(ctx))
            ?: return Search.NoIndex("嵌入模型身份取不到", "p", false)
        // ⚠⚠ `rows` 必须**现算**并传进来，不能用 `fingerprintOf` 的默认值 `-1`：
        // `r`（正文行数）是 [RagIndexStore.compareFp] 的**必比字段**，而索引里
        // 写的永远是真实行数（如 2）—— 省略它就变成"2 ≠ -1"，于是**每一次检索**
        // 都被判"索引不可用：正文行数 变了"，而索引本身完全没问题。
        // 真机日志逐字对应：
        //   [知识库] 索引不可用（"tes2"）：正文行数 变了：索引 正文行数=2 ≠ 当前 -1
        // 这正是用户说的"第三次建库成功、却无法在对话中使用"。
        //
        // 为什么这里读正文的**行数**而不是"从 meta 里拿"：正文文件才是
        // "库里有几条"的唯一真值（见 [RagIndexStore.rowsOf] 的说明），
        // 而这份字节本来就已经读进来了（上面那一步），再数一次行数是零成本。
        val fp = RagIndexStore.fingerprintOf(
            fpIn, RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))

        val loaded = RagIndexStore.load(ctx, name, fp)
        if (loaded is RagIndexStore.Load.none && RagIndexStore.isPresent(ctx, name)) {
            // 把"指纹不匹配"拆成**可操作**的诊断 —— 它们在用户看来是两回事
            // （换回模型 vs 重建索引），而现象完全一样（回答里什么都不带）。
            //
            // ⚠ 判据从"正文 hash 是否相等"换成了 [RagIndexStore.compareFp] 的
            // **逐字段**结果：旧版只比正文 hash，于是"换了模型"与"池化语义变了"
            // 与"切块参数变了"全被归成同一句"模型或切块参数与建库时不同" ——
            // 用户拿着这句话**没法动手**（不知道该换模型还是该重建，
            // 也不知道是哪一项）。用户拍板的正是这一条：不要静默作废。
            val d = RagIndexStore.lastDiff
            // 真·待重建：索引是我们**故意**写下的半份（建库时有条目失败）。
            // 那句日志由调用方（[ragContext]）按 [Search.NoIndex.stale] 分别印，
            // 因为"待重建"与"真不可用"用户处置不同，必须给两句不同的话。
            return Search.NoIndex(d.detail, d.field, d.stale)
        }
        // 四态**显式分开**（判据锚这里，不许退化成两态）：
        //   none    -> 没有可用索引（上面已附逐字段差异）；
        //   empty   -> 索引有效但**库里一条都没有**：这不是"没索引"，不该触重建；
        //   partial -> **半份索引**（建索引途中被杀留下的）。指纹与当前配置一致，
        //              缺的只是尾段向量 —— 它**不能检索**（缺的那些行永远搜不到，
        //              而这与"没命中"长得一样），但它是**可续**的（点「建索引」
        //              接着编，不用重头跑）。所以这里必须把它当"没索引"报出来，
        //              且**给它一句不同的话**（"续编"而不是"重建"）；
        //   valid   -> 有向量区，继续算 query 向量。空库判成 none 的后果是
        //              "每次检索都触发一次重建"，看起来像正常行为。
        when (loaded) {
            is RagIndexStore.Load.none -> return Search.NoIndex("索引文件缺失或损坏", "file", false)
            is RagIndexStore.Load.empty -> return Search.NoIndex("空", "x", false)
            is RagIndexStore.Load.partial -> return Search.NoIndex(
                "索引只编到 ${loaded.count}/${loaded.rowsTotal} 条（上次中途结束）—— 点「建索引」接着编即可",
                "partial", true)
            is RagIndexStore.Load.valid -> Unit   // 落到下面继续算
        }
        val valid = loaded as RagIndexStore.Load.valid
        // ⚠⚠ 查询编码失败**必须**在这里与"没命中"分开（第 2 步，用户拍板）：
        // 旧代码写的是 `embed(query) ?: return emptyList()` —— 于是"本轮查询向量
        // 没算出来（偶发）"与"库里确实没有相关内容（正常）"**共用同一句日志**
        // `检索 0 条命中`，用户从日志上分不出是哪一种，而处置**完全不同**：
        // 前者别动索引、重试即可，后者什么都不用做（调阈值/加内容）。
        // 这层静默降级正是用户报的"时好时坏、日志说加入成功但不引用"的来源之一。
        val q = embed(query) ?: return Search.QueryFailed(lastEmbedFailure()?.describe() ?: "查询向量编码失败")
        if (q.size != valid.dim) {
            // 维度不符 = 换了模型但指纹没跟上（正常路径下指纹已挡，这里是兜底）。
            return Search.NoIndex("查询维度 ${q.size} ≠ 索引维度 ${valid.dim}", "d", false)
        }
        return Search.Hits(RagIndexStore.rank(valid.vec, valid.count, valid.dim, q, topK, minScore))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RAG 检索 -> 注入片段（RAG 阶段 2）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 一次检索的产出：可拼进 prompt 的文本 + **它来自哪个库、几条**。
     *
     * 为什么要带上库名与条数（而不是只返回那个字符串）：阶段 3 的远端路径要把
     * "这一轮到底注入了没有"变成**协议层可见**的（[RagProtocol]）—— 而"注入了几条"
     * 是那个字段的全部内容，它只有在注入发生的**当场**才拿得到。让调用方
     * 从最终文本里去数（比如数 `（来源: ` 出现几次）是在拿**呈现格式**当判据：
     * 正文里恰好含这个串就会数错，而错的方向是"报了一个没发生过的注入条数"。
     */
    data class RagBlock(val block: String, val kb: String, val count: Int)

    // 为什么检索要单独有一层（而不是调用方自己 search + 拼字符串）：
    // 它把"该不该检索"、"检索到了几条"、"拼出来是不是空"这三件事收敛到一处，
    // 于是"开关关闭时一个字节都不注入"**只有一个判据点**（[RagInject.shouldSearch]）。
    // 各写一遍的漂移方向恰好是"多注入一个字节"，而它没有任何现象。
    //
    // 返回 null = **本次不注入**（开关关 / 没选库 / 没查询词 / 没索引 / 没命中）。
    // 非空 = 可直接拼进 prompt 的文本（以 [RagInject.MARK] 起头）+ 库名 + 条数。
    // **绝不抛异常**：它在生成前的拼 prompt 路径上跑，抛出去等于整轮失败。
    //
    // ⚠ `perItemChars` 是**注入块的长度上界**，不是"想读多少"：取值 > 0 时每条
    //   都压成恰好这么多个 UTF-16 码元（[RagInject.renderStable]），于是同一条命中
    //   在不同轮次里**逐字节相同**，KV 前缀才接得上。取 0 = 老行为（原样拼）。
    //   这条不是优化：块一变长，公共前缀就断在块的头一枚，整段历史全量重算 ——
    //   真机上"开知识库就复用波动"的来源正是它。
    fun ragContext(
        ctx: android.content.Context,
        kbName: String?,
        query: String,
        enabled: Boolean,
        topK: Int = RagInject.DEFAULT_TOP_K,
        minScore: Float = RagInject.DEFAULT_MIN_SCORE,
        perItemChars: Int = 0
    ): RagBlock? = try {
        // ⚠ 判据走 [RagInject.shouldSearch]，**不在这里再写一遍**。
        //   上一版这里内联成 `if (!(enabled && kbName != null))`（少了 `query` 那一项），
        //   而 `RagInject.shouldSearch` 因此变成了一个**没有任何调用点**的函数 ——
        //   界面/远端都以为"判据只有一处"，实际是两处、且两处条件不同：
        //   内联那份少了 `query.isNotBlank()`，于是空查询会走到 `search(...)`。
        //   漂移方向正是"多跑一次检索"（症状至多是"多一条 0 命中日志"），
        //   而注释里那句"只有一个判据点"从那一刻起就不再成立。
        if (!RagInject.shouldSearch(enabled, kbName, query)) null
        else {
            when (val r = search(ctx, kbName!!, query, topK = topK, minScore = minScore)) {
                // ── 「没有可用索引」：配置问题（重建 / 换回模型）──────────────
                // 两种情形的处置**不同**，所以给两句不同的话：真·待重建（索引是我们
                // 故意写下的半份，点重建即可）与真不可用（指纹不符/文件损坏）。
                is Search.NoIndex -> {
                    if (r.stale) {
                        uiLog("[知识库] 索引待重建（\"$kbName\"）：${r.detail} —— 点「建库/重建」即可")
                    } else {
                        uiLog("[知识库] 检索跳过：\"$kbName\" 没有可用索引（${r.detail}）（字段 ${r.field}）")
                    }
                    null
                }
                // ── 「查询编码失败」：偶发，与索引无关，**别动索引** ──────────
                // 旧代码把它和"没命中"混成同一句，用户从日志上分不出是哪一种，
                // 而处置完全相反（这个要重试/看 native 原因，那个什么都不用做）。
                is Search.QueryFailed -> {
                    uiLog("[知识库] 查询向量编码失败（本轮不注入）：${r.why} —— 与索引无关，重试即可")
                    null
                }
                // ── 「有可用索引」：命中与否 + 最高分（可观测面）────────────
                // ⚠ 最高分是**过滤之前**的：没有它，「差一点」与「完全不像」
                //   在日志上逐字相同（都是"0 条命中"），用户没法判断该调阈值还是该加内容。
                is Search.Hits -> {
                    val hits = r.rank.hits
                    if (hits.isEmpty()) {
                        uiLog("[知识库] 检索 0 条命中（最高分 ${fmtScore(r.rank.best)} / 阈值 $minScore），不注入")
                        null
                    } else {
                        val picks = RagInject.select(
                            hits.map { RagInject.Pick(it.index, it.score) }, topK, minScore)
                        if (picks.isEmpty()) {
                            uiLog("[知识库] 命中 ${hits.size} 条但全部低于阈值 ${minScore}，不注入")
                            null
                        } else {
                            // ⚠⚠ `rows` **必须按行号对齐**（下标 `i` == 索引第 `i` 行），
                            // 长度是**全库行数**而不是 `picks.size`。
                            //
                            // 为什么：`RagInject.render` 的契约是"按行号取源"（`rows[p.index]`，
                            // 与 `rag_index.h::render_injection` 逐字对应）。旧代码在这里
                            // **按命中顺序压紧**地 add（`rows[0]` 是"第 1 条命中"的正文），
                            // 两者只在"命中的正好是第 0、1、2…行"时才偶然相等。于是有两个
                            // 症状同源，且都不报错：
                            //   · 命中的行号全部 ≥ picks.size -> `p.index >= rows.size` ->
                            //     整块被跳过 -> 用户看到的「选中 N 条但正文为空」；
                            //   · 命中的行号部分落在 0..picks.size-1 -> 注入的是**另一行**的
                            //     正文（分数对、内容错）。
                            // 这就是用户报的"选中一条，但内容为空"。
                            //
                            // 为什么一次读全库而不是逐条 [textAt]：见
                            // [RagIndexStore.readEntriesAligned] 的说明 —— 既要对齐行号，
                            // 又要避免"每条各读一次全文"；它读一次文件即可。
                            val all = RagIndexStore.readEntriesAligned(ctx, kbName)
                            val rows = ArrayList<String>(all.size)
                            for (e in all) rows.add(e?.text.orEmpty())
                            // 块做成**定长**（`perItemChars > 0` 时）：同一条命中逐字节相同，
                            // KV 前缀才接得上；变长的那一侧由截断/补空抹平。
                            val stable = RagInject.renderStable(picks, rows, perItemChars)
                            val block = stable.block
                            if (block.isEmpty()) {
                                // ⚠ "越界"与"正文为空"是**两件不同的事**，处置也不同，
                                // 不许共用一个出口（旧代码就是共用，于是"整块为空"能安静发生：
                                // 用户只看到一句"正文为空"，而真正的原因是行号对不上）。
                                //   · 越界 = 索引里的行号指向了正文文件之外 —— 两个文件不同步；
                                //   · 空正文 = 那一行本来就（或坏了）没有正文 —— 内容问题。
                                val outOfRange = picks.count { it.index >= rows.size || it.index < 0 }
                                val emptyText = picks.size - outOfRange -
                                    picks.count { it.index in rows.indices && rows[it.index].isNotEmpty() }
                                uiLog(
                                    "[知识库] 选中 ${picks.size} 条但正文为空，不注入" +
                                        "（正文文件 ${rows.size} 行；越界 $outOfRange 条 / 空正文 $emptyText 条）" +
                                        "—— 越界=索引与正文不同步，点「建库/重建」；空正文=那一行内容本身为空")
                                null
                            } else {
                                uiLog("[知识库] 注入 ${picks.size} 条（阈值 ${minScore}）：" +
                                    picks.joinToString { "#${it.index}=%.3f".format(it.score) } +
                                    // 定长块的真实成本要说出来：短正文补的空格也是 token，
                                    // 而"我明明只命中一条却多算了 N 个 token"必须当场能对上账。
                                    (if (perItemChars > 0)
                                        "（块定长 ${stable.chars} 字 / 每条上限 $perItemChars）" else ""))
                                RagBlock(block, kbName, picks.size)
                            }
                        }
                    }
                }
            }
        }
    } catch (t: Throwable) {
        // 检索失败**绝不能**让这一轮请求失败（与 kv_prefix 同一条准则：
        // 附带的加速/增强能力不该变成新的可用性风险）。降级成"没配知识库"。
        uiLog("[知识库] 检索异常（本轮不注入）：${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * 分数打点：`NaN` 打成 `n/a`（= 一条都没算过），其余三位小数。
     *
     * 为什么不用 `"%.3f".format(x)` 一把梭：`format(NaN)` 会打出 `"NaN"`，
     * 而用户看到"最高分 NaN"只会更困惑 —— 我们的语义是"没有可报的分数"，
     * 那就写 `n/a`。这一点在空库（rows=0）上必现。
     */
    private fun fmtScore(x: Float): String = if (x.isNaN()) "n/a" else "%.3f".format(x)

    // ---- native ----
    /** libcpufeat.so 提供，返回 [dotprod, i8mm, hwcap, hwcap2]。 */
    @JvmStatic private external fun cpuCaps(): LongArray
    /**
     * native 崩溃探针开关。on=true 时 native 侧自己开文件直写（不经 JVM、不经 llama log 系统），
     * 并把 SIGSEGV/SIGABRT/... 的现场写进同一文件；开启期间所有 JVM 回调都被旁路，
     * 以免探针自身成为观测者效应。返回是否挂上。
     */
    @JvmStatic private external fun nativeProbeInit(dir: String, on: Boolean): Boolean

    /** Kotlin 侧埋点：把「要开始做 X」写进 native 探针文件；探针关闭时是空操作。 */
    @JvmStatic private external fun nativeProbeMark(msg: String)
    @JvmStatic private external fun backendInit(logBridge: Any)
    /** 只读 GGUF header 预探真实量化，返回 {主类型, HTP受理%, HTP可算"1"/"0", 说明}。 */
    @JvmStatic private external fun nativeProbeGguf(path: String): Array<String>

    @JvmStatic private external fun nativeLoadModel(
        path: String, nGpuLayers: Int, nCtx: Int, nThreads: Int,
        flashAttn: Boolean, useMmap: Boolean, cacheK: Int, cacheV: Int,
        parallelN: Int, batchSize: Int, ubatchSize: Int, npuQuotaPct: Int): Boolean

    /**
     * 带 slot 数的加载（Issue #330 阶段 3）。[slotCount] 在**形参第一位** ——
     * 同是 `Int` 时靠位置取值没有编译器兜底（见 `run_jni_boundary_guard.py`）。
     *
     * native 侧**自己再校验一次整除**（不信任调用方）：除不尽回落单 slot。
     * 旧的 [nativeLoadModel] 保留、等价 `slotCount = 1`；阶段 4 接线时才切。
     */
    @JvmStatic private external fun nativeLoadModelSlot(
        slotCount: Int, path: String, nGpuLayers: Int, nCtx: Int, nThreads: Int,
        flashAttn: Boolean, useMmap: Boolean, cacheK: Int, cacheV: Int,
        parallelN: Int, batchSize: Int, ubatchSize: Int, npuQuotaPct: Int): Boolean
    @JvmStatic private external fun nativeModelDesc(): String

    /**
     * GGUF 元数据档案，返回 String[8]（顺序是接口，见 native 侧同名函数的注释与
     * [ModelProfile.fromNative]）。未加载模型时返回 8 个空串。
     */
    @JvmStatic private external fun nativeModelMeta(): Array<String>
    @JvmStatic private external fun nativeCtxSize(): Int
    /**
     * 已入 KV 的 token 数。旧的无 slot 声明：**语义保留 = slot 0**
     * （设计文档 §9.2.10「只增不改」）—— 多 slot 后它读到的本来会是
     * 「最后动过账本的那一路」，看起来完全正常、只是不是你以为的那一路。
     */
    @JvmStatic private external fun nativeCtxUsed(): Int
    /** 按 slot 读已入 KV 的 token 数（[slot] 在形参第一位）。 */
    @JvmStatic private external fun nativeCtxUsedSlot(slot: Int): Int

    /** 本次加载开出的 slot 数（`/health: slots_total`，阶段 4 接入）。 */
    @JvmStatic private external fun nativeSlotCount(): Int
    /**
     * 本次加载时**请求的每份 ctx**（0 = 没加载 / 旧入口没传）。
     * 与 [nativeCtxSize] 成对：那个是"建出来实际用的"，这个是"我要的"。
     */
    @JvmStatic private external fun nativeCtxRequestedPerSlot(): Int
    /**
     * 本次加载时**请求的 slot 数**（0 = 没加载 / 旧入口没传）。
     * 与 [nativeSlotCount] 成对：那个是"开出几份"，这个是"我要几份"。
     */
    @JvmStatic private external fun nativeSlotsRequested(): Int
    /**
     * KV 前缀复用统计，返回 int[4] = {上一轮复用的 token 数, 上一轮新算的 token 数,
     * 已跑完 prefill 的轮次数, 上一轮的公共前缀 token 数}。与 [lastReuseTokens] /
     * [lastPrefillTokens] / [kvRounds] / [lastLcpTokens] 成对，见那边的说明。
     */
    @JvmStatic private external fun nativeKvCacheStats(): IntArray
    /** 按 slot 读 KV 复用统计（[slot] 在形参第一位）。旧声明语义 = slot 0。 */
    @JvmStatic private external fun nativeKvCacheStatsSlot(slot: Int): IntArray
    /**
     * 按 slot 读归属读数：`int[8] = {reuse_owner, reserved, last_gen_cap, last_ctx,
     * used_self, round_used_self, writer, seq_left}`（第 2 格已废弃，恒 0；
     * 末格 `seq_left` = 本路那条 seq 还剩多少位置，见 [kvAttributionOf]）。
     * 与 [kvAttributionOf] 成对，见那边的读法说明。
     */
    @JvmStatic private external fun nativeKvAttributionSlot(slot: Int): IntArray
    /**
     * 上一轮账本的构成与分叉点（见 [kvLedgerOf] 的读法说明）。
     *
     * `int[39]` = prompt 段 / 生成段 / 总长 / 分叉点 / 上一轮账本长 /
     * 本轮 prompt 长 / 本轮丢掉的 prompt tok / 本轮削掉的生成长度 /
     * 开头逐枚是否相等 / 实际比了几枚 / 断点是否落在被比的那段之内 /
     * **分叉那一枚在上一轮账本里的下标**（-1 = 无分叉）/
     * **本轮新算的起点 / 上一轮的起点 / native 真 token 数 / 客户端估算值 /
     * 本轮注入块字符数 / 起点怎么定的（0 没丢头 / 1 接上 / 2 重新决定）/
     * 想接而让掉的额度**（ISSUE #330 第 4 块 + 第 1、3 块）/
     * **本轮真正新算的起点**（= 跳过的枚数；`start` 是复用下来的枚数，两者相加 = 整段 prompt）/
     * **断点两侧的几何四格**（`extra_first` / `extra_len` / `extra_first_prev` /
     * `prev_head_in_cur`，见 [kvGeometryOf]）/
     * **丢完之后的余量**（[kvSpareOf]；0 = 尾巴顶在边界上，下一轮必然再丢一次）/
     * **上一轮预算按哪条线判的**（第 30 格 `last_seq_ctx` = 这一条 seq 的容量；
     * 与 `last_ctx` 并排读，两者不等就说明这一格的容量被 seq 切过）。
     *
     * 第 20 格（ISSUE #330 收口）之所以单独一格：跳过起点命中时 `start` 是
     * "复用了几枚"（可能两千多），而用户要对的数是"新算从第几枚开始"（152）。
     * 把两千多报成起点，读数比现象大一个数量级 —— 谁都对不上。
     *
     * 第 21~23 格（ISSUE #330 第 7 块）回答"断点两侧各是什么"：前面那些格只说
     * "断在第几枚"，说不出"谁的字节变了"。四格全是下标，没有推断。
     *
     * 格数必须与 native 的 `NewIntArray(39)` 同值（`tools/kv/ledger_cap_check.py` 逐字钉着
     * 这两处的数字）：少解一格时那几格恒为 0，
     * 而 0 是一个**合法取值**（"第 0 枚就分叉" / "从头 prefill" / "没注入"），
     * 于是落点、漂移、块宽都会被静默判错。
     */
    @JvmStatic private external fun nativeKvLedgerSlot(slot: Int): IntArray
    /**
     * 生成**为什么停**：`int[4] = {gen_planned, n_rem, stop_wall, pos_gap}`。
     *
     * 与 [kvAttributionOf] 的 `last_gen_cap` 分工：那一格是"预算愿意给多少"，
     * 这三格回答"实际能生成几枚、是谁拦的"——`min(n_rem, stop_wall)`；
     * 第四格 `pos_gap`（`spare - stop_wall`）回答"位置是不是被 prompt 吃掉了"。
     * 真机上出事的那一轮：`gen_cap 1024`（预算一分没削）、`spare 693`（还有空位），
     * 而 `stop_wall 99`（位置只够这么多），只报 `gen_cap` 时三种处境同形。
     */
    @JvmStatic private external fun nativeGenStopSlot(slot: Int): IntArray
    /** 本轮从头部丢掉的 prompt token 数（0 = 一个都没丢，KV 前缀仍连续）。 */
    @JvmStatic private external fun nativePromptTrimmedSlot(slot: Int): Int
    /** 本轮是否因「账本与 KV 对不上」而降级（只降级、不作废）。 */
    @JvmStatic private external fun nativeLedgerMismatchSlot(slot: Int): Boolean
    /** 分叉点落在哪一截（一句话，由 native 的 `fork_where` 出，两边不各拼一份）。 */
    @JvmStatic private external fun nativeKvForkWhySlot(slot: Int): String
    /** 上一次 `step()` 是否因上下文/生成上限而结束（旧的无 slot 声明，语义 = slot 0）。 */
    @JvmStatic private external fun nativeStepCtxExhausted(): Boolean
    /** 按 slot 读同一个标志（多 slot 下必须读自己那一路）。 */
    @JvmStatic private external fun nativeStepCtxExhaustedSlot(slot: Int): Boolean
    /** 这一轮 prefill 是否**被取消**（**读即清**，与下一条两份归因互不覆盖）。 */
    @JvmStatic private external fun nativeTakePrefillCancelledSlot(slot: Int): Boolean
    /** 上一轮 prefill 是否因上下文装不下而失败（**读即清**，一次性归因）。 */
    @JvmStatic private external fun nativeTakeCtxBudgetFailedSlot(slot: Int): Boolean
    /** 给渲染后的 prompt 数 token（`-1` = 数不了）。 */
    @JvmStatic private external fun nativeCountPromptTokens(prompt: String): Int
    /** 同一次归因的两个数：`[真 token 数, 客户端估算值]`（**读即清**，与上一条成对）。 */
    @JvmStatic private external fun nativeTakeCtxBudgetNumbersSlot(slot: Int): IntArray?
    /** KV 账本是否有效（下一轮能否复用）。旧的无 slot 声明：语义保留 = slot 0。 */
    @JvmStatic private external fun nativeKvCacheValid(): Boolean
    /** 按 slot 读账本有效性（[slot] 在形参第一位）。 */
    @JvmStatic private external fun nativeKvCacheValidSlot(slot: Int): Boolean
    /**
     * 丢弃 prompt 缓存（清 KV 并作废账本）。旧的无 slot 声明：保留、等价 slot 0。
     *
     * ⚠ UI 那 6 个调用点一律走 [nativeKvCacheResetAll]（设计文档 §9.2.3）：
     * 「多清一份」只是那一路下一轮全量 prefill（慢一次），「少清一份」是
     * 模型读到换掉之前的 system 段（静默答非所问）。两个方向的代价不对称。
     */
    @JvmStatic private external fun nativeKvCacheReset()

    /** 按 slot 丢弃 prompt 缓存（[slot] 在形参第一位）。 */
    @JvmStatic private external fun nativeKvCacheResetSlot(slot: Int)

    /** 整池丢弃 prompt 缓存：每个 slot 各清一份。UI 一律走这条。 */
    @JvmStatic private external fun nativeKvCacheResetAll()
    @JvmStatic private external fun nativeChatTemplate(): String
    @JvmStatic private external fun nativeApplyChatTemplate(
        tmpl: String, roles: Array<String>, contents: Array<String>, addAss: Boolean,
        thinkingOn: Boolean): String?
    /** 带 tools 的模板渲染：工具定义进 prompt（旧接口不认 tools，工具调用永远不触发）。 */
    @JvmStatic private external fun nativeApplyChatTemplateTools(
        tmpl: String, roles: Array<String>, contents: Array<String>,
        toolsJson: String?, toolChoice: String?, parallelToolCalls: Boolean, addAss: Boolean,
        thinkingOn: Boolean): String?
    /**
     * 从输出文本解析 tool_calls，返回 JSON；无工具调用或解析失败返回 "null"。
     *
     * 必须传入**渲染时的同一个模板**：解析器是按模板推导出的 PEG，两边不一致会解析错。
     */
    @JvmStatic private external fun nativeParseToolCalls(
        text: String, toolsJson: String?, tmpl: String, addAss: Boolean, thinkingOn: Boolean): String
    /**
     * [stops] 为 null / 空表示不带 stop 序列；[schema] 为 null 表示不约束输出格式（见 [ResponseFormat]）。
     * [genPrompt] 为这一轮实际渲染出的生成后缀；null 表示"无 / 未知"，native 侧退回旧口径
     * （见 [ResponseFormat.GenerationPrompt]）。
     * [tmpl] 为这一轮的 chat 模板原文（与渲染侧同源）；空串表示"按模型自选"。
     */
    /**
     * [biasIds] / [biasVals] 是 `logit_bias` 的**平行数组**（长度必须相等，见
     * [ProtocolFields.biasIds]）。null = 不带偏置，此时 native 侧不挂偏置采样器、
     * 采样链与引入本特性之前**逐字节相同**。
     */
    @JvmStatic private external fun nativeNewSampler(temp: Float, topP: Float, minP: Float, topK: Int, repPenalty: Float, penaltyN: Int, freqPenalty: Float, presencePenalty: Float, seed: Long, stops: Array<String>?, schema: String?, genPrompt: String?, tmpl: String?, thinkingOn: Boolean, biasIds: IntArray?, biasVals: FloatArray?): Boolean
    /**
     * 按 slot 重建采样器（Issue #330 阶段 3）。[slot] 在**形参第一位**。
     * 旧声明 [nativeNewSampler] 保留、等价 slot 0；阶段 4 接线时才切。
     */
    @JvmStatic private external fun nativeNewSamplerSlot(slot: Int, temp: Float, topP: Float, minP: Float, topK: Int, repPenalty: Float, penaltyN: Int, freqPenalty: Float, presencePenalty: Float, seed: Long, stops: Array<String>?, schema: String?, genPrompt: String?, tmpl: String?, thinkingOn: Boolean, biasIds: IntArray?, biasVals: FloatArray?): Boolean
    @JvmStatic private external fun nativeStartCompletion(prompt: String, maxTokens: Int): Boolean
    /** 按 slot 做 prefill（[slot] 在形参第一位）。旧声明保留、等价 slot 0。 */
    /**
     * 按 slot 做 prefill（[slot] 在形参第一位）。
     *
     * [writer] 是**这一路是谁**（[WRITER_APP] / [WRITER_HTTP]，0 = 没报）。
     * 它只进读数与复用门槛：native 侧用它把"上一轮是另一路、所以公共前缀从中间就断"
     * 这句话说出来，并在换路那一轮放宽"复用太短就别复用"的门槛 ——
     * 单格池里 App 与 HTTP 轮替占着同一格时，那个门槛会让每一轮都整段重算
     *（用户报的「kv 每次都被顶掉」，ISSUE #403 第三次现场）。
     */
    @JvmStatic private external fun nativeStartCompletionSlot(
        slot: Int, prompt: String, maxTokens: Int, estTokens: Int, injectChars: Int, writer: Int,
        estScaleX1000: Int
    ): Boolean
    @JvmStatic private external fun nativeStep(): String?
    /** 按 slot 走一步（[slot] 在形参第一位）。旧声明保留、等价 slot 0。 */
    @JvmStatic private external fun nativeStepSlot(slot: Int): String?
    /**
     * 取消编号为 [roundEpoch] 的那一轮生成。编号由 [nativeCurrentEpoch] 在轮次开始时取得。
     * 编号与当前轮次不一致（或为 0）时**一律不生效** —— 宁漏停，不错停；见 native 侧注释。
     */
    @JvmStatic private external fun nativeAbort(roundEpoch: Long)
    /**
     * 取消指定 slot 上编号为 [roundEpoch] 的那一轮（Issue #330 阶段 3）。
     * [slot] 在**形参第一位**。**只停这一个 slot** —— 一个客户端不许掐掉别人的回答。
     */
    @JvmStatic private external fun nativeAbortSlot(slot: Int, roundEpoch: Long)
    /** 取当前轮次编号（供 [beginCancelable] 绑定归属；未在生成时是"最后一次的编号"）。
     *  旧的无 slot 声明：语义保留 = slot 0。 */
    @JvmStatic private external fun nativeCurrentEpoch(): Long
    /** 取指定 slot 上的轮次编号（[slot] 在形参第一位）。 */
    @JvmStatic private external fun nativeCurrentEpochSlot(slot: Int): Long
    /**
     * 本轮已进 KV 账本的生成 token 数。取消收尾时与"已下发的步数"对账：
     * 两者不等 = 有一段内容引擎算过、客户端没收到（E-4 的可观测面，**不回滚**）。
     */
    @JvmStatic private external fun nativeRoundLedgerTokens(): Int
    /** 按 slot 读本轮已进账本的生成 token 数（[slot] 在形参第一位）。 */
    @JvmStatic private external fun nativeRoundLedgerTokensSlot(slot: Int): Int
    /**
     * 「这一轮的账本别按'被打断'处理」（`length` 收尾用，见 [keepKvLedger]）。
     *
     * 语义只有一句：**别再按"被打断"处理这一轮的 KV**。真去截尾巴的判据仍在
     * native（`kvprefix::truncate_ledger`），这里不重复推导。
     */
    @JvmStatic private external fun nativeKeepKvLedger(slot: Int)
    /**
     * 权重重排（repack）档位：`0` = 关、`1` = 开、其它 = **没设过**（native 落回库默认）。
     *
     * 走 JNI 参数而不是系统属性：属性那条链要 `fork` + `exec /system/bin/getprop`，
     * 在真机上读不到时**与"没设过"同形**（见 native `model_use_extra_bufts` 的注释），
     * 而这一档是"少一份匿名拷贝"的唯一旋钮，判定必须落在仓库自己的存储上。
     *
     * 命名：本方法原名 `nativeSetExtraBufts`，与**库内部字段** `use_extra_bufts` 同名，
     * 于是日志/设置页/代码三处对"这一档叫什么"各叫各的（用户对着日志找不着设置项）。
     * 0.9.127 起统一叫 `repack`，并为跨版本改名留一个**别名**（见下）。
     */
    // 注意：本方法（以及下面的旧名别名）在 native 侧的定义**必须落在**
    // `extern "C"` 作用域内。少了那层链接规格，C++ 会重整符号名，
    // JVM 就找不到它 —— 0.9.125 装机后正是这么报 UnsatisfiedLinkError 的
    // （0.9.126 修；守卫见 run_mmap_device_guard 第 ⑩ 条，按**结构**钉，
    // 不按"名字在文件里出现过"钉）。
    @JvmStatic private external fun nativeSetRepack(mode: Int)

    /**
     * 旧名别名（0.9.127 起弃用）：**只有**在装到了不含 [nativeSetRepack] 的旧 .so
     * （覆盖安装少解压某个 ABI 变体）时才可能命中，命中即有日志、不静默 ——
     * 否则就是"改名把功能改哑了，而日志看不出为什么"。
     */
    @JvmStatic private external fun nativeSetExtraBufts(mode: Int)

    /**
     * 把档位交给 native；优先新名，**只在**新名缺失时才回退旧名。
     * 为什么不是"两个都调"：两个符号在同一个 .so 里写同一个变量，都调等于
     * 把"到底哪条链生效"重新变成不可观测 —— 本轮要修的正是这个病。
     */
    private fun setRepackMode(mode: Int) {
        try {
            nativeSetRepack(mode)
        } catch (t: UnsatisfiedLinkError) {
            uiLog("[repack] ⚠ 本包 native 未导出 nativeSetRepack（旧 .so？）→ 回退旧名；" +
                "档位来源在日志里会写成旧名，报告问题时请一并附上本行")
            try {
                nativeSetExtraBufts(mode)
            } catch (t2: UnsatisfiedLinkError) {
                uiLog("[repack] ⚠ 旧名也不可用 → 本轮档位落回库默认（设置页的值**未生效**）")
            }
        }
    }

    /** 推理期 RSS 峰值（MB）；-1 = 还没跑过任何一轮或读不到。见 native `rss_note_peak`。 */
    @JvmStatic private external fun nativeRssPeakMb(): Int

    /**
     * 卸载后补一脚"把已释放的匿名页还给内核"，返回该打的一行日志（空串 = 无需报读）。
     * 见 [reclaimReleasedHeap] 的注释：这一脚对付的是"free 了但 RSS 不降"的分配器缓存。
     */
    @JvmStatic private external fun nativeReclaimReleasedHeap(): String

    @JvmStatic private external fun nativeFreeSampler()
    /** 按 slot 释放采样器（[slot] 在形参第一位）。旧声明保留、等价 slot 0。 */
    @JvmStatic private external fun nativeFreeSamplerSlot(slot: Int)
    @JvmStatic private external fun nativeUnloadModel()

    /**
     * 等整池的生成轮次收尾（ISSUE #348）。`maxMs` 到点即返回 false（**不**永久阻塞）。
     *
     * native 侧是所有 `active_round` 标志的唯一真值来源 —— Kotlin 的
     * `SlotScheduler.busy` 只是"池里有没有 slot 被占"，它**看不见**"占着的那一路
     * 是不是真的在跑"（`newSampler` → `startCompletion` 之间的窗口、以及
     * UI 测试页那条不经调度器的路都在它之外）。所以真正的等待必须问 native，
     * `busy` 只配当快路径的前置判据。
     */
    @JvmStatic private external fun nativeWaitPoolIdle(maxMs: Long): Boolean

    // ── 嵌入模型（双常驻的第二份 Session；与对话模型互不影响）──────────────
    /**
     * 加载嵌入模型。三个旋钮（0.9.242：`rows` / `gate` / `ctxCap` 是本轮新增的
     * 后两个 + 第一个，全部 `<=0` 时由 native 取天花板那一档）：
     *   · `nCtx`     —— **每条**文本的预算（native 再乘 `rows` 得 ctx 总量）；
     *   · `rows`     —— 一次合批几条（= `n_seq_max`，天花板 [kEmbedBatchRows]）；
     *   · `gate`     —— 两道门（`n_batch` / `n_ubatch`，天花板 [kEmbedGateMax]）；
     *   · `ctxCap`   —— ctx **总量**上限（天花板 [kEmbedCtxMax]）。
     *
     * ⚠ 三个数必须**一起**给：它们各自是另一个的约束（见 native 的 `gatesAbs`
     * 那一段）—— 只把 `rows` 调到 1 而 `ctxCap` 仍留 2048 时，省下的只是
     * `n_seq_max`，那 2048 格 KV 仍在（`n_ctx` 由 `每条预算 × rows` 与
     * `ctxCap` 取小，而 2048 是个上限、不是落值）。省内存要动 [ctxCap]。
     */
    @JvmStatic private external fun nativeLoadEmbedModel(
        path: String, nCtx: Int, nThreads: Int, useMmap: Boolean,
        rows: Int, gate: Int, ctxCap: Int): Boolean
    /** 卸载嵌入模型（只拆嵌入那一份）。 */
    @JvmStatic private external fun nativeUnloadEmbedModel()
    /** 嵌入模型是否已驻留。 */
    @JvmStatic private external fun nativeHasEmbedModel(): Boolean
    /** 嵌入向量维度；0 = 无可用嵌入模型。 */
    @JvmStatic private external fun nativeEmbedDim(): Int
    /** 嵌入模型的短描述；空串 = 没有已驻留的嵌入模型。 */
    @JvmStatic private external fun nativeEmbedModelDesc(): String
    /**
     * 输出侧维度（`llama_model_n_embd_out`）。库按它分配 output buffer，
     * 而读取按 `n_embd` 步长走 —— 两者不等时读一行是未定义行为（见 native 侧注释）。
     * 0 = 无可用嵌入模型。
     */
    @JvmStatic private external fun nativeEmbedOutDim(): Int

    /** 嵌入模型声明的池化类型原值（-1 = 取不到 / UNSPECIFIED）。 */
    @JvmStatic private external fun nativeEmbedPooling(): Int

    /** 嵌入每条文本**实际生效**的预算（库的 `n_ctx_seq`）；0 = 没加载。 */
    @JvmStatic private external fun nativeEmbedCtxPerSeq(): Int

    /** 这一份嵌入 ctx **实际拿到**的 seq 数（= 建 ctx 时的 `n_seq_max`）；0 = 没加载。 */
    @JvmStatic private external fun nativeEmbedSeqMax(): Int

    /** 两道门的生效值（`n_batch`/`n_ubatch` 取小，库的实数）；0 = 没加载。 */
    @JvmStatic private external fun nativeEmbedGate(): Int

    /** ctx 的**总格数**（= 库的 `n_ctx`）；0 = 没加载。 */
    @JvmStatic private external fun nativeEmbedCtxTotal(): Int

    /**
     * 最近一次嵌入失败的详情，格式固定：
     * `reason|n_tokens|n_embd|n_embd_out|pooling|norm`；空串 = 没失败。
     */
    @JvmStatic private external fun nativeEmbedLastFailure(): String

    /** 一条文本 -> 已归一化向量；null = 这一条没有可用向量（**不抛**）。 */
    @JvmStatic private external fun nativeEmbed(text: String): FloatArray?

    /**
     * n 条文本 -> 扁平向量数组（长度 = `texts.size * n_embd`），**一次**前向算完。
     *
     * 返回 null = **整批不可用**，调用方回落逐条（见 native 侧那段推导：
     * 维度不匹配 / 该池化档不支持合批 / 批过大 / decode 失败 / 全批都 tokenize 不出）。
     *
     * ⚠ 失败可以是**部分**的：某几条归一化失败时，它在数组里留全零，
     * 而"哪几条"要另问 [nativeEmbedBatchFailed] —— 全零与"真的零向量"同形，
     * 单看数组分不出来（见 native 侧那句注释）。
     */
    @JvmStatic private external fun nativeEmbedBatch(texts: Array<String>): FloatArray?

    /**
     * 上一次 [nativeEmbedBatch] 里失败的那几条（**原下标**，逗号分隔；空串 = 全成功）。
     *
     * ⚠ 必须在**同一把锁内、紧跟着那一次调用**取走：它是 native 侧一个可变容器
     * （见 `g_embed_batch_failed`），与 [nativeEmbedLastFailure] 同一条约定。
     */
    @JvmStatic private external fun nativeEmbedBatchFailed(): String

    /**
     * token id 数组 -> 文本（用**嵌入模型那一份** vocab）。空串 = 还原失败
     * （没加载嵌入模型 / id 越界 / 数组为空）。
     *
     * 给 `/v1/embeddings` 的 `input: [1,2,3]` 形态用：id 先还原成文本，再走
     * 与字符串输入**完全相同**的前向路径（见 native 侧 `nativeDetokenize` 注释里
     * 为什么不能对 id 数组直接前向、也不能由调用方自己重编码）。
     */
    @JvmStatic private external fun nativeDetokenize(ids: IntArray): String
}
