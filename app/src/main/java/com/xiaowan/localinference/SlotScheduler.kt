package com.xiaowan.localinference

/**
 * 并发 slot 调度：**纯逻辑**部分（slot 表、acquire/release、容量判决）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这个文件（Issue #330 阶段 2）
 * ══════════════════════════════════════════════════════════════════════════
 * 今天的服务端是**单并发**：`HttpApi.busy` 是一枚 CAS、`LlmEngine.genLock` 把
 * 生成循环串行化，第二个请求直接 503。`parallelN` 虽然已经通到 native，但只是
 * 设了 `n_seq_max` —— 并发能力一分没多，反而按份数瓜分 `n_ctx`。
 *
 * 本文件只回答三个问题，**不碰 native、不碰 HTTP 接线**（那是阶段 3 / 阶段 4）：
 *   ① 有哪几个 slot、各自被谁占着；
 *   ② 此刻还能不能发起一路生成（[acquire]）；
 *   ③ 一路生成结束时把哪个 slot 还回来（[release]）。
 *
 * 之所以能在**纯逻辑层**做完就单测：slot 表的失效模式全是静默的 ——
 * 泄漏（release 漏调 = 之后每个请求都 503）、串位（release 别人的 slot =
 * 两路并发写到同一个 ctx）都不抛异常、不打日志，只能靠断言钉住。
 * 把这三件事从 native 挪开，阶段 3 就只剩「每 slot 一份 ctx」的机械改动。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * `n_ctx` **不按份切分**（容量语义在 [ctxPerSlot]）
 * ══════════════════════════════════════════════════════════════════════════
 * 设置页那一格 = **每格**容量；份数只决定开几格（`1..SLOT_MAX`）。
 *
 * 曾经这里写着「每格 = `n_ctx / slot 数`，除不尽回落单 slot」，而那条口径有两个
 * 后遗症（ISSUE #426 第七、八回合的真机现场）：
 *   · 用户填 8192 开 2 格，报出来每格 4096 —— 他按 4096 反推再把 8192 填一遍，
 *     "我填的数被写死"与"它按份切了"永远说不清；
 *   · 份数参与算容量之后，任何一处用别的 ctx 重算一遍都能凑出一套自洽的数
 *     （真机 `ctx_size 12288 / slots_total 3`：格数与容量互相喂）。
 * 库里 `n_ctx_seq = n_ctx / n_seq_max` 那条约束仍在，但它管的是**一格里的 seq 数**
 *（恒 1，见 `paths_of`），与格数无关。
 */
object SlotScheduler {

    /**
     * slot 数的取值域（设计文档 §4 / §11 第 1 点）。
     *
     * 上限 4 不是性能口径而是内存口径：每 slot 一份 ctx，4 份 KV + 4 份 compute
     * buffer 在手机上已经接近可承受的上限；再高就只是把单路速度拖到不可用。
     */
    const val SLOT_MAX = 4

    /** 默认 slot 数。单 slot 时行为与今天逐字节一致，所以默认值必须落在那一档。 */
    const val SLOT_DEFAULT = 1

    /**
     * 一次生成的**占用凭证**。调用方在整个生成期持有它（`finally` 里 [release]），
     * 之后不得再读写它的字段 —— 同一份 slot 可能已被别人 acquire。
     *
     * 用类而不是 Int：Int 可以被任何人复读、传给别的线程，而「谁持有哪个 slot」
     * 必须是**可判定的**（同一份 lease 重复 release 要能被认出来）。
     *
     * [fixedSlot] 区分这个下标是**被点名的**还是**被挑中的**。两者都只是"发出那一刻
     * 在几号"，运行中要不要认它一律回表里查身份（见 [currentSlotOf]）—— 这个标志
     * 只用于读数与日志，不参与"这一轮在哪一格"的判定。
     */
    class Lease(val slot: Int, val fixedSlot: Boolean = false)

    /**
     * 发起一路生成的请求方。HTTP 与 App 内聊天各一个（设计文档 §5：
     * App 内聊天也占 slot，否则它没有任何背压，会把 HTTP 全堵掉）。
     *
     * 只用于 [describe] 与日志/`/health` 的可观测面，**不参与 acquire 判定** ——
     * 判定只看有没有空 slot，不看来源（按来源限流是另一种策略，本期不做）。
     */
    enum class Source { HTTP, APP }

    /**
     * App 内聊天**固定占的那个 slot**（设计文档 §9.2.2）。
     *
     * 为什么把它写在调度器这一层：这条约定同时被三处读 —— App 生成路径（[acquireAt]）、
     * 「停止」按钮的判据（只看这个下标）、以及取消归属（`RequestCancel` 的
     * `LEGACY_SLOT`，同值同义）。三处各写一个字面量 0 的话，改约定就要靠人去搜；
     * 而这条约定错了的症状是「按钮点了没反应」或「停掉了别人的回答」——
     * 前者不报错，后者用户看得见但归因不到。
     *
     * ⚠ 它是**保留位**，不只是"App 碰巧会用 0"（Issue #330 真机复盘的第三层）。
     * 见 [acquire] 那条：HTTP 挑空位时**必须绕开**这一格，否则 App 永远开不了口。
     */
    const val APP_SLOT = 0

    /**
     * slot 的占用情况。
     *
     * `owner` 是**发出去的那一份 lease 本身**（身份令牌），不只是来源 ——
     * [release] 靠它判"来还的确实是当初拿走的那一份"，见那条的注释。
     */
    private class Slot(
        @Volatile var source: Source? = null,
        @Volatile var owner: Lease? = null,
        /**
         * 单格池里**另一路**的那个来源（"这一格是两路共用的"）。
         *
         * 只在 [take] 发现"这一格已经被别的来源占着"时才写 —— 也就是单 slot 且
         * App 与 HTTP 撞在同一格上。多 slot 时这一格恒为 null（占用是独占的）。
         */
        @Volatile var sharedWith: Source? = null,
    )

    private val lock = Any()

    /** slot 表。构造后长度不变（slot 数在加载时定死，运行中不改 —— 见 §10.5）。 */
    private var slots: Array<Slot> = emptyArray()

    /**
     * 本次加载开出的 slot 数（`parallelN` 的**实际落值**）。
     *
     * 未加载时为 1：没有模型时不存在并发问题，取 1 让「还没加载」与「加载成单 slot」
     * 在 /health 上同形（都是「此刻最多一路」），不必多一个状态。
     */
    @Volatile var total: Int = SLOT_DEFAULT
        private set

    /**
     * 加载时定下 slot 数（换模型 = 拆整池重建，设计文档 §5）。
     *
     * @param want 用户要求的 slot 数（`parallelN` 的 UI 原值）
     * @param nCtx **用户填的上下文长度**（设置页那一格）
     * @return 实际落值，以及为什么（钳位都在这里，调用方写日志用）
     *
     * ══════════════════════════════════════════════════════════════════════
     * `nCtx` 是**每一格**的容量，不再按份切分（ISSUE #426 第八回合）
     * ══════════════════════════════════════════════════════════════════════
     * 真机那一屏是「设置页 ctx 8192 / parallelN 2 → 探活 `slots_total 3`」。
     * 格数永远是 `coerceIn(want, 1, SLOT_MAX)` 这一个值 —— 想让它变成 3，
     * 只能是**别处又按份数算了一遍**，而且用的是另一个（更大的）ctx。
     *
     * 那种算法就在这里：`ctxPerSlot = nCtx / want`。于是设置页填 8192、
     * 要 2 格 → 每格 4096 → 用户按"我填了 8192"的直觉把 8192 再填一次/
     * 或换档重载，8192 还能被 3 整除（8192/3 除不尽才对，但 12288/3=4096
     * 恰好整除）……总之份数与容量互相喂，读数上凑出一套自洽的数。
     *
     * 更要紧的是这条口径本来就与 native 相反了一整轮（§11.5 起）：
     * `load_model_impl` 收到的 `nCtx` 是**每份**容量、`cp.n_ctx = nCtxPerSlot`，
     * 它**不再除第二次**。而这里把用户填的数先除以份数再交下去 —— 用户填
     * 8192、要 2 格，模型拿到的每格就是 4096，界面报的也是 4096，于是"我填
     * 8192 怎么只有 4096"每轮都要重新解释一遍。
     *
     * 所以：**设置页那一格 = 每格容量**，份数只决定开几格（`1..SLOT_MAX`）。
     * `ctxPerSlot` 保留为同一件事的别名（`ctxPerSlot(nCtx, n) == nCtx`），
     * 是"每格"这个语义的唯一名字，不再承担切分。
     *
     * ⚠ 老档位的用户（`parallelN` 一直是 2、ctx 填 8192）会看到每格从 4096 变成
     *   8192、总量从 8192 变成 16384：内存占用按格数 × 每格增长，这是**语义
     *   变更**而不是回归 —— `/health` 的 `ctx_per_slot` / `ctx_actual` 两格与
     *   `slots_total` 一起并排报，读一遍就能看出这一次到底开了多少。
     */
    fun open(want: Int, nCtx: Int): Resolved {
        val effective = want.coerceIn(1, SLOT_MAX)
        synchronized(lock) {
            slots = Array(effective) { Slot() }
            total = effective
            ctxPerSlotLoaded = ctxPerSlot(nCtx, effective)
        }
        val why = when {
            want < 1 -> "不足 1，按单 slot"
            effective < want -> "按上限钳到 $effective（上限 $SLOT_MAX）"
            else -> "按请求值"
        }
        return Resolved(effective, ctxPerSlot(nCtx, effective), why)
    }

    /**
     * 结果：实际 slot 数、每份 ctx、以及一句话原因（给日志与 /health）。
     *
     * [nativeArg] 与 [ctxPerSlot] 同值，单独留一个名字是为了让调用点的语义明确：
     * 传给 native 的是**每份容量**，不是用户填的总量。两个名字指向同一个数，
     * 但读 `nativeLoadModelSlot(nativeArg)` 的人不会再以为"native 那边还会切"。
     */
    class Resolved(val slots: Int, val ctxPerSlot: Int, val why: String) {
        val nativeArg: Int get() = ctxPerSlot
    }

    /**
     * 加载时定下的每份容量（`/health` 的 `ctx_per_slot` 的唯一真值）。
     *
     * 为什么不拿 native 的读数反算：`ctxSize / slots_total` 要求两个数**同时**
     * 正确，而这一条正是被"切两遍"打破过的地方（native 报 4096、总量算出来该是
     * 8192 —— 两个数各自自洽，和谁都对不上）。这里存的是**判决时的那个数**，
     * 与 `Resolved.ctxPerSlot` 同源。
     */
    @Volatile private var ctxPerSlotLoaded: Int = 0

    /** 每份容量的落值（未加载时为 0）。 */
    val loadedCtxPerSlot: Int get() = ctxPerSlotLoaded

    /**
     * **每格**的 `n_ctx`（设置页那一格就是它；ISSUE #426 第八回合）。
     *
     * 曾经这里是唯一的切分点（`nCtx / slotCount`），那正是"设 2 格、实际 3 格"
     * 与"填 8192、每格 4096"两条现场共同的入口：份数参与算容量，容量再被读数
     * 拿去解释，两个数互相喂。现在它**不做除法** —— 一格的任务是一条流，
     * 容量是用户填的那个数；份数只决定开几格。
     *
     * 参数保留 `slotCount` 只为不破坏既有调用点与守卫的签名（它已不参与结果）。
     */
    fun ctxPerSlot(nCtx: Int, slotCount: Int = total): Int {
        (slotCount)   // 不再参与：每格容量与份数无关
        return if (nCtx > 0) nCtx else 0
    }

    /**
     * 用 **native 的真值**重定池尺寸（Issue #330 阶段 5 真机复盘）。
     *
     * ══════════════════════════════════════════════════════════════════
     * 为什么必须有这一条（这是「只看到 s0、KV 互相顶掉」的根因）
     * ══════════════════════════════════════════════════════════════════
     * [open] 在**加载之前**判决，而 native 侧那次加载**可能没开出那么多份**：
     *   · 建 pool 时第 2 份 ctx 的 `llama_init_from_model` 失败（显存/内存不够）；
     *   · 或者旧的无 slot 加载入口被走到（它恒 `slotCount=1`）。
     * native 那两种情况下都会**把池子拆回 1 份**（`g_slots_extra.clear()` +
     * `g_slot_count = 1`），但 [open] 已经写下的 `total` **不会跟着改回来**。
     *
     * 于是调度器照旧把 slot 1..N-1 发出去，而 native 的 `slot_of(1)` 因为池子
     * 空了**回落 slot 0** —— 两路（甚至三路）请求静默地挤在同一份 ctx 上，
     * 读同一个 KV、写同一个 KV。用户看到的就是「日志里只有 s0」+「KV 一直互相顶掉」：
     * 不崩、不报错、接口 200，正是设计文档第三节方案 B 的失效模式。
     *
     * 判据取 native 的 `slotCount()`（`g_slot_count`）而不是"加载返回成功"：
     * 返回成功只说明 `S` 建出来了，与"建出了几份"是两件事 —— 后者才是池的尺寸。
     * 而这两件事同形正是本仓库反复栽的那一类（见 §8）。
     *
     * [why] 是给日志用的一句话；只有**真的收窄了**才值得打，否则每轮加载都多一行噪声。
     *
     * @return 收窄之后实际可用的 slot 数
     */
    fun syncTo(nativeSlots: Int, nativeCtxPerSlot: Int = -1): Int = synchronized(lock) {
        val real = nativeSlots.coerceIn(1, SLOT_MAX)
        val wasTotal = total
        // ⚠ 每份容量也要按 native 真值重定（不只是份数）：native 报的 `n_ctx` 是
        //   **建出来的那一份**的实际容量，而判决值在"第 2 份建失败 / n_ctx 被平台钳"
        //   时会与它对不上。以前这里只对齐份数，于是 `/health` 的 `ctx_per_slot`
        //   一直是**判决时的愿望值** —— 而客户端要按它压缩 prompt。
        if (nativeCtxPerSlot > 0) ctxPerSlotLoaded = nativeCtxPerSlot
        if (real == slots.size && real == total) { lastSyncNote = ""; return real }
        // 只有"native 说比我现在认为的少"才是故障（native 说得多也不可能 ——
        // 池是加载时建的，调度器不会自己长大）。两者都按 native 的真值重定，
        // 因为唯一真值就是池子本身。
        val shrunk = real < wasTotal
        // 直接重开一张表。此刻是「加载后、生成前」，表本来就该是空的
        //（加载的闸门是 `idle`，见 §9.2.4），所以重建不会踩到任何在跑的 slot ——
        // 这也是为什么这个函数**只许**在加载路径上调，不能顺手拿它当"运行时改尺寸"。
        slots = Array(real) { Slot() }
        total = real
        lastSyncNote =
            if (!shrunk) ""
            else "native 只开出 $real 份（请求 $wasTotal），调度器按真值收窄 —— " +
                "不收窄的话 slot $real.. 会落到 native 的越界回落上，两路挤同一份 ctx"
        return real
    }

    /** 上一次 [syncTo] 收窄的一句话原因（没发生收窄时为空串）。加载路径打日志用。 */
    @Volatile var lastSyncNote: String = ""
        private set

    /**
     * 换模型 / 卸载时按整池处理：整张表清零（不许拆别人的 slot）。
     *
     * 重建整张表而不是"只把各格清零"：`slots.size` 必须与 [total] 恒等 ——
     * [acquire] 的保留位判断读的正是表长（多 slot 才跳 [APP_SLOT]）。
     * 只清格不改表长的话，`reset()`（`total=1`）之后表里仍留着上一轮的 2 格，
     * 于是"单 slot 时 HTTP 该拿到 slot 0"这条会被误判成多 slot、被跳过 ——
     * 单 slot 的 HTTP 全部 503，而服务看起来"没坏"。
     */
    fun reset() {
        synchronized(lock) {
            slots = Array(SLOT_DEFAULT) { Slot() }
            total = SLOT_DEFAULT
            ctxPerSlotLoaded = 0
        }
    }

    /**
     * 要一个 slot；拿不到返回 null（调用方据此回**带原因的** 503，不排队）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么必须**绕开** [APP_SLOT]（Issue #330 真机复盘第三层）
     * ══════════════════════════════════════════════════════════════════════
     * 用户报的原话：「我在外部客户端先生成，再 App 内生成，App 内直接 toast
     * 提示 s0 被占用」，且日志里**只有 `[s0]`**。
     *
     * 根因是两条各自成立、合起来打架的口径：
     *   · 阶段 4 让 HTTP 走「挑第一个空位」—— 没有别人时，**空位就是 slot 0**；
     *   · 阶段 5 让 App **钉死** slot 0（`acquireAt(APP_SLOT)`），因为「停止」按钮
     *     的判据与动作都只看 slot 0（§9.2.2）。
     *
     * 于是「先 HTTP 后 App」这一幕里，HTTP 先把 slot 0 拿走了，App 再要 slot 0
     * 就只能拿到 null —— **App 永远开不了第二路**，而 HTTP 那一路照跑。
     * 「保留 slot 0 给 App」这条约定此前只写在 **App 那一侧**（[acquireAt]），
     * HTTP 那一侧完全不知道有这么回事，所以 HTTP 把它当普通空位占了。
     *
     * 修法不是让 App 改成"随便找个空位"（那会退掉阶段 5 的钉法、按钮又失灵），
     * 而是把**保留**这件事也写进 [acquire]：挑空位时**跳过 [APP_SLOT]**。
     *
     * 只有一格时**不跳**：`total == 1` 时那个空位就是 slot 0，而单 slot 必须与
     * 改动前逐字节一致 —— 今天 HTTP 用的正是 slot 0。跳过它等于把单 slot 的 HTTP
     * 全部拒掉（503），那是把「保留」这条改成「禁用」（见 `check_single_slot_compat`）。
     *
     * 判据仍然**不看来源**：HTTP 与 App 对等（§5）。这里跳过的只是**位置**，
     * 不是"看是谁在要"——App 走 [acquireAt] 拿它，HTTP 走这里让出它。
     */
    fun acquire(source: Source): Lease? = synchronized(lock) {
        val reserved = if (slots.size > 1) APP_SLOT else -1
        for (i in slots.indices) {
            if (i == reserved) continue
            if (slots[i].source == null) return take(i, source)
            // ── 单格池：App 那一路正占着这一格 ────────────────────────────────
            //
            // 这一档以前直接走下面的 null（= 503 busy），于是**单 slot + App 在生成
            // 期间，HTTP 一律连不上**。而它与"两路抢同一份 ctx"是同一件事的两面：
            // 放行则两轮写同一份 KV（用户报的「KV 每次都被顶掉」），拒绝则远端一句
            // 503 busy 而宿主那一路照跑。所以处置不是"放"也不是"拒"，
            // 而是**把那一轮让出来**：App 的取消有唯一所指（它的登记在 slot 0 上，
            // 见 `RequestCancel`），另一路要开口只能这样换。
            //
            // 三个条件缺一不可：
            //   ① 这是单格池（多 slot 时该由保留位让开，不该抢）；
            //   ② 那一格上跑的是 **App**（同一个来源要两路时，照旧是 503）；
            //   ③ 那一格上确实有一轮在跑（[RequestCancel.isSlotBusy]）——
            //      光看 `source` 会在"刚 acquire、还没登记"的那一小段上误停。
            if (slots.size == 1 && slots[i].source == Source.APP && RequestCancel.isSlotBusy(i)) {
                // ⚠ 走闸门，不是直接 [RequestCancel.cancelSlot]（ISSUE #450）。
                // 两者只差一跳，但差的正是**prefill**：`cancelSlot` 只把 Kotlin 侧的
                // token 置位，而生成循环要先从 `step()` 里出来才看得到它 ——
                // prefill 是一次阻塞的 native 调用，挡不住。让路因此要等这一大段
                // prompt 算完（长 prompt 上好几秒），与"让路"的本意（立刻空出来）相反。
                // 引擎注册进 [AbortGate] 的那一跳会在置位之外再把**带轮次编号**的取消
                // 送到 native（见 `LlmEngine.abortRound`）。
                //
                // ⚠ 也**不要**在这里写 `LlmEngine.xxx`（ISSUE #452 第 4 步）：本文件是
                // 纯逻辑层，`run_slot_tests.sh` / `run_ctx_budget_tests.sh` /
                // `run_health_tests.sh` 三份套件只编纯逻辑文件 —— 引一次引擎，
                // 那三份套件就在有 kotlinc 的机器上编不过，而在没有 kotlinc 的 CI 上
                // 永远 SKIP（"看起来只是环境缺件"）。闸门的形状就是这么来的。
                AbortGate.cancel(i)
                return null
            }
        }
        null
    }

    /**
     * 写表：把 `i` 这一格交给 `source` 并发出**固定下标**的 lease（见 [Lease.fixedSlot]）。
     *
     * 为什么发固定下标的那一种：[acquire] 的动作是"挑一个空位"，它签的不是任何具体
     * 下标，所以 HTTP 那一路的取消**必须**回到调度器问（[slotOfSource] / [currentSlotOf]）；
     * 而 [acquireAt] 签的是一个点名的下标，它的取消可以直接打过去。
     * 这两件事此前是同一个形状，而"拿它去打取消"的失效方向相反：
     * 挑来的下标会变（打空）、点名的下标不会变（打得到）。见 [currentSlotOf]。
     *
     * ⚠ 调用方持 [lock]。
     */
    private fun take(i: Int, source: Source, fixed: Boolean = false): Lease {
        val lease = Lease(i, fixed)
        val prev = slots[i].source
        // ⚠ 单格池里的**顶替**：`slots[i].source` 只放得下一个名字，于是 [acquire] /
        //   [acquireAt] 在"这一格已被占"时只能让后到的那个写进去（`acquireAt` 的判据是
        //   `source != null` 会拒，单 slot 的 HTTP 走 [acquire] 才撞得上这一支）。
        //   这一刻必须**留痕**：不留痕的话"两路挤在同一份 ctx 上"在 `/health` 与日志上
        //   完全同形于"只有一路在跑"，而它的后果是两路各自的 KV 前缀互相作废
        //   （用户报的「KV 每次都被顶掉」，见 [writerOf]）。
        if (prev != null && prev != source) slots[i].sharedWith = prev
        slots[i].source = source
        slots[i].owner = lease
        return lease
    }

    /**
     * 要**指定**的那个 slot；它已被占时返回 null（调用方据此拒绝开跑）。
     *
     * 与 [acquire] 的区别只有一条：不挑空位。存在的理由是 [Source.APP] 那一路 ——
     * App 内聊天**必须**落在 slot 0（设计文档 §9.2.2：它旁边的「停止」按钮判据与动作
     * 都只看 slot 0），所以它要的不是「随便给一个空位」。若让它走 [acquire]，
     * 「池子被 HTTP 占了 slot 0、App 拿到 slot 1」这一幕下按钮判据（只看 slot 0）
     * 会报「没在跑」，而 App 其实正在生成 —— 用户点了没反应。
     *
     * 为什么是「先问过调度器、再由它点名」，而不是「调用方自己按 0 去取」：
     * 唯一真值是调度器那张表，外部按下标自己拿（`acquireAt` 之外的写法）
     * 就没有任何东西保证它拿的是真的空位 —— 而这一层的失效是静默串位。
     */
    fun acquireAt(slot: Int, source: Source): Lease? = synchronized(lock) {
        if (slot !in slots.indices) return null
        if (slots[slot].source != null) return null
        take(slot, source, fixed = true)
    }

    /**
     * 归还一个 slot。**同一份 lease 重复 release 是 no-op**（幂等）。
     *
     * 为什么必须是幂等：调用方在 `finally` 里 release，而异常路径与取消路径
     * 可能都走到它。写成「按下标无条件清空」的话，第二次调用清掉的是**别人**
     * 刚 acquire 的同一个下标 —— 那是两路并发写到同一个 ctx 的入口。
     *
     * 判据必须是**身份**（`===`），不是下标：`Lease` 只带一个下标，而同一个
     * 下标会被反复发出去（A 还了、B 拿到 `Lease(1)`；此时 A 那份 `Lease(1)`
     * 若迟到再还一次，按下标判就会把 **B 正在跑的那一份**清掉）。
     * 所以表里存的是当初发出去的那一份 lease 本身，`release` 只认它。
     */
    fun release(lease: Lease?) = synchronized(lock) {
        if (lease == null) return
        val i = lease.slot
        if (i !in slots.indices) return
        // 身份不符 = 这一格已经换了主人（或早就还过），什么都不做。
        if (slots[i].owner !== lease) return
        slots[i].source = null
        slots[i].owner = null
        // 共用标记跟着占用一起清：还回来之后这一格没人写了，"两路共用"不再成立。
        // 漏掉这一句的话，单格池里跑过一轮 App+HTTP 之后，`/health` 会永远报
        // `slot_owner: ["app+http"]` 而池子其实是空的。
        slots[i].sharedWith = null
    }

    /**
     * 这一格此刻的**占用凭证**；空位（或下标越界）返回 null（只作读数与"有人在吗"的判据）。
     *
     * 与 [leaseOfSource] 的分工：那个按**来源**反查，答的是"我这一路在哪一格"；
     * 这一条按**下标**正查，答的是"这一格上有没有一轮"。后者是取消欠账的存亡判据
     * （`LlmEngine.slotOccupied`）—— 欠账记下的语义是"这一格上那一刻正在跑的那一轮
     * 该停"，所以"这一格此刻有没有人"必须问这张表：它从 [acquire] 那一刻就成立，
     * 而 `RequestCancel` 的登记要到 `startCompletion` 返回之后才有（prefill 窗口里是空的）。
     *
     * ⚠ 不参与"能不能取消"的判定：取消一律按**下标**打，靠凭证反查会把"迟到的取消
     *   打到别人"那族重新引回来（见 [currentSlotOf] 的注释）。
     */
    fun leaseAt(slot: Int): Lease? = synchronized(lock) {
        if (slot !in slots.indices) null else slots[slot].owner
    }

    /**
     * 此刻 `lease` 这一轮**真正占用的是哪一格**；没在跑（已还 / 被接管）返回 **-1**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么 lease 上那个 `slot` 不能当答案（ISSUE #403：slot 1 的停止失效）
     * ══════════════════════════════════════════════════════════════════════
     * `slot` 只对 [acquireAt] 是**定义**（调用方点名要这一格，`acquireAt` 要么给到
     * 这一格、要么给 null），对 [acquire] 只是"发出那一刻的第一个空位"。
     * 多 slot 上线后池子的形态是「HTTP 占 1..N-1 + App 占 0」，而**单 slot** 时
     * HTTP 拿的就是 slot 0（[acquire] 的保留位只在 `total > 1` 时生效 —— 那一条
     * 不能退，退了单 slot 的 HTTP 全 503）。于是「HTTP 那一路的 lease.slot」在
     * 运行中会变：上一次 HTTP 跑在 slot 1，它还没收尾，下一路 HTTP 在单 slot 池里
     * 拿到 slot 0 —— 而**停的那个按钮**按 `lease.slot` 去打，打的是一个空槽。
     *
     * 症状就是本 Issue 的原话：「按了停止，app 还是后台算完了」，客户端只看到
     * `Fetch request has been canceled`（它自己断了，服务端没收到 cancel）。
     *
     * 判据收在一处：**lease 只是一个"这一轮"的凭证，格子的真值只在表里**。
     * `slots[i].owner === lease` 是唯一能证明"那一格还在跑我这一轮"的依据
     *（[release] 也是拿它判的，两处同一个真值）—— 它对 [acquireAt] 与 [acquire]
     * 两种来源**同样成立**：身份对上就说明这一格此刻确实是这一轮的。
     *
     * ⚠ 曾经这里还有一条 `fixedSlot` 过滤（"挑来的下标不作数"）。它把 [acquire]
     * 的 lease **一律**判成 -1，而 HTTP 的两个生成端点全部走 [acquire] —— 表现就是
     * 每个请求都在取消登记处拿到 null、回 503「slot N 已被占用（调度器与取消注册表
     * 不一致）」，服务看起来彻底坏了（2026-10 真机：客户端一请求就失败）。
     * "挑来的下标"要防的是**拿它去打取消**（它在运行中会变），而不是"不许它报格子"；
     * 打取消的判据已经在 [slotOfSource] / `LlmEngine.sourceSlot` 那一侧，
     * 这里只答"这一轮现在还在这一格吗"，与 release 同一个真值。
     */
    fun currentSlotOf(lease: Lease?): Int = synchronized(lock) {
        if (lease == null) return -1
        val i = lease.slot
        if (i !in slots.indices) return -1
        if (slots[i].owner !== lease) return -1
        i
    }

    /**
     * 按**来源**找它此刻正在跑的那一格；没有返回 -1。给"取消一路 HTTP"用。
     *
     * 为什么需要它：`/v1/abort` 不带 `slot` 时的旧语义是"停那一路"，而单 slot
     * （也是默认档）里 HTTP 与 App 都用 slot 0 —— 无参 `/v1/abort` 与「停止」按钮
     * 的取消都打在 slot 0 上，这正是「App 的回答被外部客户端掐断」那一族。
     * 反过来，外部客户端想停自己那一路时，`slot` 缺省 0 会打空（ISSUE #403）。
     *
     * 只在"这个来源只占一格"时有人认它：多 slot 时同一个来源可能占好几格
     * （`slots[0]` 与 `slots[1]` 都是 HTTP），"按来源"就不再是"那一路"。
     * `LlmEngine.sourceSlot` 因此还要求"那一格上真有已登记的轮次"，两处合起来才给答案。
     */
    fun slotOfSource(source: Source): Int = synchronized(lock) {
        slots.indexOfFirst { it.source == source }
    }

    /**
     * 这一来源此刻那一格的**占用凭证**；没有返回 null。
     *
     * 存在的理由只有一个（ISSUE #450）：`/v1/abort` 不带 `slot` 时要问"HTTP 那一路
     * 还在不在它那一格上"，而这个问题在**prefill 期间**问不出答案 —— 取消登记表
     * 要到 `startCompletion` 返回之后才有这一轮（见 `LlmEngine.sourceSlot`）。
     * 占用的真值从 `acquire` 那一刻就成立，所以判据落在这里。
     *
     * ⚠ 只答"谁占着这一格"，**不参与**"能不能取消"的判定：取消一律按**下标**
     * 打（[cancelSlot]），靠凭证反查会把"迟到的取消打到别人"那族重新引回来。
     */
    fun leaseOfSource(source: Source): Lease? = synchronized(lock) {
        val i = slots.indexOfFirst { it.source == source }
        if (i < 0) null else slots[i].owner
    }

    /**
     * 此刻这一格**实际由谁在写**（`/health` 的 `slot_owner` 的来源，ISSUE #403 第三次现场）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么 `source` 这一格不够（用户原话：日志里任何时候都没有 s0/s1）
     * ══════════════════════════════════════════════════════════════════════
     * 单 slot（默认档）时 HTTP 与 App 内聊天**共用 slot 0**（[acquire] 的保留位只在
     * `total > 1` 时才让开），而 `source` 一格里只放得下一个名字：后到的那个**顶掉**先到的
     * （详见 [take]）。于是这一格的 `source` 只等于"最后一个拿到它的是谁"，
     * 而"其实还有另一路也压在这份 ctx 上"这件事**一个字段都没记** ——
     * `/health` 的 `slot_active` 也因此报不出"这两轮挤在同一格"。
     *
     * 这一格答的是同一件事的**另一面**：这一格现在是几路在写。
     * 只作读数，不参与任何判定 —— 判据仍然是 [acquire]（有没有空位）。
     *
     * ⚠ 它与 [currentSlotOf] 的 `owner === lease` 不冲突：那一条问"这一轮还在不在这一格"，
     * 这一条问"这一格里挤了几路"，两者的真值都是 `slots[i]`，只是读的字段不同。
     */
    fun sharedAt(slot: Int): Boolean = synchronized(lock) {
        slot in slots.indices && slots[slot].sharedWith != null
    }

    fun writerOf(slot: Int): Source? = synchronized(lock) {
        if (slot !in slots.indices) null else slots[slot].sharedWith ?: slots[slot].source
    }

    /**
     * 此刻有几格是**两路共用**的（`/health` 的 `slots_shared` 的来源）。
     *
     * 单 slot 时 App 与 HTTP 都在这一格上，而"谁在写"只报得出一个名字 ——
     * 用户看到的"KV 每次都被顶掉"就是这一幕的后果（两路各自的 [KV_CLEAR_ON_SHARED_TAKEOVER]
     * 那一笔会把对方的前缀清掉，接口 200、不报错）。把它变成可读的数，
     * 才不用再靠"日志里只有 s0"去反推。
     */
    val sharedCount: Int get() = synchronized(lock) { slots.count { it.sharedWith != null } }

    /** 此刻被占用的 slot 数（`/health` 的 `slots_busy`）。 */
    val busy: Int get() = synchronized(lock) { slots.count { it.source != null } }

    /**
     * **非生成期路径的唯一闸门**（设计文档 §9.2.4，用户已确认）：
     * `doLoad` / `doUnload` / `toggleServer` / 清空对话 / 换模型，判据只有这一个数。
     *
     * 为什么不用 `generating || HttpApi.isGenerating`：那两个量在多 slot 下
     * 含义已经从「有生成在跑」**漂成**「池里还有 slot 被占」，却仍由两个来源拼出来 ——
     * 调用点一多就会有一处漏拼。而按池空闲只有一个数，`busy == 0` 就是「没人跑」，
     * 与「卸载不许拆别人的 slot」是同一件事。
     *
     * ⚠ 它**不是**「可以生成」的判据：那是 [acquire] 的事（占满返回 null，不排队）。
     */
    val idle: Boolean get() = busy == 0

    /** 各 slot 的占用来源数组，按下标对齐（`/health` 的 `slot_active`）；空位为 null。 */
    fun active(): List<Source?> = synchronized(lock) { slots.map { it.source } }

    /** 按来源统计占用数（503 文案要能说出「HTTP x / App 内聊天 y」）。 */
    fun countBySource(): Map<Source, Int> = synchronized(lock) {
        slots.mapNotNull { it.source }.groupingBy { it }.eachCount()
    }

    /**
     * slot 占满时的一句话说明（503 文案用，设计文档 §5）。
     *
     * 之所以要「带原因」：今天笼统一句「已有生成任务进行中」分不清是没 slot
     * 还是没加载模型，而这两件事的处置完全相反。
     */
    fun busyReason(): String {
        val bySource = countBySource()
        val http = bySource[Source.HTTP] ?: 0
        val app = bySource[Source.APP] ?: 0
        // 单格池那一档必须单独说：否则用户读到「1 个 slot 已全部占用」会去调 parallelN，
        // 而真相是"App 那一轮刚被让位、还没收尾"（见 [acquire] 的第三条条件）。
        // 这两种情形的处置完全相反：一个是"等这一轮收尾"，一个是"去加载并发"。
        if (total <= 1 && app > 0) {
            return "server busy（单格池 = 1 个 slot，App 内聊天正占用它：" +
                "另一路请求已把 App 那一轮让出来，等它收尾即可；" +
                "要让两路同时存在，加载时把 parallelN 设为 2）"
        }
        return "server busy（$total 个 slot 已全部占用：HTTP $http / App 内聊天 $app）"
    }
}
