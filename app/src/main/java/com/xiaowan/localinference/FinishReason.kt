package com.xiaowan.localinference

/**
 * `finish_reason` 的取值与优先级 —— 一条**纯判据**，两个端点共用。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单开一个文件（原来那一行错在哪）
 * ══════════════════════════════════════════════════════════════════════════
 * 此前两处都是这一行：
 *
 *     val fin = if (cancelled) "cancelled" else if (toolCallsJson != null) "tool_calls" else "stop"
 *
 * **没有 `length`**。生成循环是 `while (n < maxTok)`，被 `max_tokens` 硬截断时
 * `n == maxTok`、条件不成立而退出，落到那个 `else` 支 —— 服务端于是对客户端说
 * "模型说完了"，而真相是"我说到一半被掐了"。
 *
 * 这条错误的代价比"少一个枚举值"重得多：
 *   · SDK 侧按 `finish_reason == "length"` 决定要不要续写 / 截断重试。
 *     报成 `stop`，半截输出会被**当成完整回答**存库、缓存、丢弃；
 *   · 它还是**静默**的 —— HTTP 200、响应体合法、没有异常。调用方唯一的线索
 *     只是"回答看起来断了"，而那与"模型本来就话少"完全同形。
 * 与 `stop` 那一轮定下的准则同族：**缺的字段是被静默忽略的**，而这里更糟 ——
 * 字段在、值也在，只是**值错了**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 优先级：cancelled > length > tool_calls > stop
 * ══════════════════════════════════════════════════════════════════════════
 * 四档都要能区分，且**顺序本身是判据**（顺序错了就是某一档永远不可达）：
 *
 *   · `cancelled` 最高：调用方区分得了「模型说完了」与「我自己不要了」，
 *     前者可以存库、后者只能丢弃。都报 stop 会让客户端把半截输出当完整回答。
 *     （OpenAI 枚举里没有这个值，理由见 `HttpApi` 原处那段注释。）
 *   · `length` 次之：**被截断**这件事比"命中了工具调用"更该让调用方知道 ——
 *     截断意味着 tool_calls 的 JSON 本身可能就是不完整的，按它去执行会出错。
 *     「被取消且正好跑满 max」取哪个：取 `cancelled`。取消是**外部意图**，
 *     而"跑满了"只是它顺带够到了上限；报 length 会让客户端以为"再拉长上限
 *     就能拿到完整输出"，而实际上重试会被再次取消。
 *   · `tool_calls` 再次：命中工具时正文通常为空，不报这个值客户端（OpenAI SDK /
 *     LangChain）根本不会去执行工具。
 *   · `stop` 兜底：模型自己收尾（EOS）或命中 `stop` 序列。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * "截断"判据锚在**循环怎么退出的**，不锚在 `n >= maxTok`
 * ══════════════════════════════════════════════════════════════════════════
 * 调用方必须在循环**正常跑到条件不成立**时把 [HIT_LIMIT] 置真，而不是在收尾处
 * 回头看 `n >= maxTok` —— 后者会把「最后一步恰好落在上限上」也算成截断：
 *
 *     n 到 maxTok-1 时 step() 吐出一个 token，n 变成 maxTok，循环条件不成立 -> 截断（对）
 *     n 到 maxTok-1 时 step() 返回 null（EOS），n 停在 maxTok-1            -> stop（对）
 *
 * 而 `break` 退出的三条路（取消 / step 返回 null / 断连探测）都**不是**截断，
 * 它们各自有更高优先级的取值。用一个显式标志而不是推导，是为了让"这一轮到底
 * 为什么结束"只有**一个**来源，免得三个 break 点各推一次、必然漂移。
 */
object FinishReason {

    const val CANCELLED = "cancelled"
    const val LENGTH = "length"
    const val TOOL_CALLS = "tool_calls"
    const val STOP = "stop"

    /**
     * 算这一轮该报哪个 `finish_reason`。
     *
     * @param cancelled 客户端断连 / `/v1/abort` 导致退出（见 [RequestCancel.Token.cancelled]）
     * @param hitLimit  循环是**跑满 `max_tokens`** 退出的（调用方在循环正常结束时置真）
     * @param hasToolCalls 输出解析出了 tool_calls（正文通常为空）
     */
    fun of(cancelled: Boolean, hitLimit: Boolean, hasToolCalls: Boolean): String = when {
        cancelled -> CANCELLED
        hitLimit -> LENGTH
        hasToolCalls -> TOOL_CALLS
        else -> STOP
    }

    /**
     * 取值是否在 OpenAI 的枚举里（`cancelled` 是自定的，见 [of] 的注释）。
     *
     * 给守卫用：它按**枚举值**判，而不是按"文件里出现过 length 这四个字母"——
     * 后者把 `if (false) "length"` 之类的写法也认成实现了。
     */
    val OPENAI_VALUES = listOf(LENGTH, TOOL_CALLS, STOP)
}
