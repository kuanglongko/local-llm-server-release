package com.xiaowan.localinference

/**
 * 「让路」要用的**取消闸门**：把「取消这一格上的一轮」这件事从引擎里**摘出来**，
 * 让纯逻辑层（[SlotScheduler]）不必再认识引擎（ISSUE #452 第 4 步）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这个文件（`c1ace37` 之后分叉出来的那一条）
 * ══════════════════════════════════════════════════════════════════════════
 * ISSUE #450 把「让路」的取消从 [RequestCancel.cancelSlot] 换成了
 * `LlmEngine.requestAbort(slot)` —— 差的正是 **prefill**：前者只置位 Kotlin 侧的
 * 标志，而生成循环要先从 `step()` 里出来才看得到它；prefill 是一次阻塞的 native
 * 调用，只有 `requestAbort` 会把它送到 native。这个理由今天仍然成立。
 *
 * 代价是 [SlotScheduler] 从「纯逻辑」变成了「认识引擎」：
 *
 *   · `tools/run_slot_tests.sh` / `run_ctx_budget_tests.sh` / `run_health_tests.sh`
 *     只编纯逻辑那几个文件（`LlmEngine.kt` 依赖 Android / org.json / native，
 *     宿主编不进来）→ `kotlinc` 报 `unresolved reference 'LlmEngine'`；
 *   · 而这三份套件的契约是「缺工具链 → SKIP；有工具链 → 编译错就红」，
 *     于是**没有 kotlinc 的 CI 上永远绿**，只有真跑单测的人才会撞见 ——
 *     判据在 `tools/run_kt_test_runner_guard.py`（它纯源码级，正因为它要防的
 *     就是「工具链缺件」）。
 *
 * 修法不是把判据放宽：那个依赖**确实**越界了（调度器不该认识引擎）。做法是把
 * 「取消这一格」这件事本身变成**纯逻辑**（[RequestCancel.cancelSlotOrMark]），
 * 再把「还要送到 native」这一跳交给引擎**自己注册进来**（[install]）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是「引擎注册进来」而不是「调度器去问引擎」
 * ══════════════════════════════════════════════════════════════════════════
 * 方向反过来之后，依赖只剩一条：`LlmEngine -> AbortGate`（它本来就依赖纯逻辑层）。
 * 调度器只认一个 `(Int) -> Unit`，所以：
 *   · 它在宿主上编得进来，判据能跑，而且**跑的仍是产品那条路**；
 *   · 没注册时（任何非产品场景：单测、还没起引擎）退回
 *     [RequestCancel.cancelSlotOrMark] —— 置位照旧生效，只是没人送 native。
 *
 * ⚠ 注册是**幂等覆盖**，不是追加：这一条链上只许有一个引擎。真出现两个，
 *   「让路」会停在旧的实例上 —— 而那正是"看起来工作"的失效形态（日志有、请求停）。
 *
 * ⚠ 不用 `object` 的 `var` 存函数就完事了吗：可以，但那样「什么时候注册的」
 *   「注册的是谁」就没有落点了。这里留 [installed] 只为让单测能钉住"注册上了"，
 *   **不参与任何判定**。
 */
object AbortGate {

    /**
     * 引擎注册进来的那一条：取消**这一格**上正在跑的那一轮（含 prefill 窗口）。
     *
     * 语义必须与 `LlmEngine.requestAbort` 一致（返回"被取消的那一轮"或 null，
     * 表里没轮次时也要把取消送到 native）。这里只声明形状，不复制判据。
     */
    private var send: ((Int) -> RequestCancel.Token?)? = null

    /**
     * 引擎启动时把自己接进来（`LlmEngine` 的 `init` 里调一次）。
     *
     * 覆盖而不是"只认第一个"：进程重启、类重载、单测里换实例，都只会把链换成
     * 当前这个引擎 —— 「拒绝第二个」会让后来那个永远不生效，而症状是取消打不动
     * 却处处报成功。
     */
    fun install(fn: (Int) -> RequestCancel.Token?) {
        send = fn
    }

    /**
     * 卸载（只给宿主单测用）：把一个**桩**摘掉，回到"没有引擎"那一档。
     *
     * 为什么不能"再 install 一个"了事：注册是覆盖，所以任何一次 install 都会让链上
     * 永远有一条。单测里那个桩（只记 hits、返回 null）若一直挂着，后面每一次
     * `SlotScheduler.acquire` 的让路都会当场取消别人的轮次 —— 症状是"后面那些用例
     * 莫名其妙地开始 FAIL"，而它们自己一行都没改。产品路径不用它（引擎的 `init`
     * 只 install、不卸载：进程里只有一个引擎）。
     */
    fun uninstall() {
        send = null
    }

    /** 是否已有引擎接进来（只给单测与日志用，**不参与判定**）。 */
    val installed: Boolean get() = send != null

    /**
     * 取消 [slot] 上正在跑的那一轮（`SlotScheduler.acquire` 让路时唯一该走的口）。
     *
     * 没引擎时退回 [RequestCancel.cancelSlot]（只置位、**不记账**）：那一句在 prefill
     * 窗口里置不了位（这正是 #450 要修的那一幕），但它至少不改语义 —— 而"没引擎"只发生在
     * 宿主单测与非产品场景里。"只置位"而不是"置位 + 记账"是因为这一跳**送不到 native**：
     * 记下的账没有兑现的对象，只会在这一格的下一轮上炸开。
     *
     * ⚠ "没引擎"和"引擎说没轮次"是**两件事**，不能共用 `?:`：后者是一个合法的答案
     *   （取消已记账、已送 native），再兜一次会把账记两遍（见函数体注释）。
     */
    fun cancel(slot: Int): RequestCancel.Token? {
        // 没引擎时记下的那一笔**无处可去**（没有任何 native 侧可以把 prefill 打断），
        // 留在账上就会兑现在这一格的下一轮上 —— 而那一轮与这次让路毫无关系
        // （ISSUE #450 自检的残留，见 `RequestCancel.settleUnclaimed`）。所以直接丢掉：
        // 语义上退回"只置位"（[RequestCancel.cancelSlot]），不记账。
        val fn = send ?: return RequestCancel.cancelSlot(slot)
        // ⚠ 不能写成 `send?.invoke(slot) ?: cancelSlotOrMark(slot)`：`?:` 在"引擎那条
        //   返回 null"时也会走右边，而引擎返回 null 的意思恰恰是**"这一格上没有已登记
        //   的轮次"**（prefill 窗口，取消已经记账、并且已经送到 native）—— 再补一次
        //   `cancelSlotOrMark` 等于把同一笔账记两遍，第二笔会在下一轮登记时**莫名其妙地
        //   把它取消掉**。闸门只负责转发，不负责兜底：引擎那条自己就是唯一的判据。
        return fn(slot)
    }
}
