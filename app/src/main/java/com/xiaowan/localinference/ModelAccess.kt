package com.xiaowan.localinference

import android.content.Context

/**
 * 推理入口的**统一门面**：HTTP 与 App 内聊天都从这里拿「模型就绪 + 活跃推理」
 * 凭证，不再各自判断走按需加载还是多模型池。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要多一层
 * ══════════════════════════════════════════════════════════════════════════
 * 按需加载（[ModelLifecycle]）与多模型池（[MultiModelEngine]）返回的租约类型
 * 不同，但调用方关心的只有两件事：**现在可以推理了吗**、**结束时归还**。
 * 把这两种租约包成同一个 [ModelAccessResult.Ready]，调用点就不会出现
 * 「HTTP 走了多模型、App 还在走旧状态机」这种半接线的形态。
 *
 * 两个开关都关（[ModelServingMode.MANUAL]）时调用点整体旁路：请求不经过
 * 本对象，行为与引入按需/多模型之前逐字节一致。分派优先级只由
 * [ModelServingMode.fromFlags] 决定 —— 两边同时打开时多模型胜出，按需的
 * 单模型状态机不再参与这次请求。
 */
object ModelAccess {

    /**
     * 当前**真正生效**的服务模式。唯一优先级在 [ModelServingMode.fromFlags]：
     * 多模型 > 按需 > 手动。设置页与 `/health` 也读同一份判决，避免三个地方
     * 各写一遍 `if` 后出现「界面说按需、请求却走多模型」。
     */
    fun mode(ctx: Context? = null): ModelServingMode =
        ModelServingMode.fromFlags(
            multiModelEnabled = MultiModelEngine.isEnabled(ctx),
            onDemandEnabled = ModelLifecycle.isEnabled(),
        )

    /** 是否需要在推理前后 acquire/release。任一开关打开即为真。 */
    fun isEnabled(ctx: Context? = null): Boolean = mode(ctx) != ModelServingMode.MANUAL

    /**
     * 确保模型就绪并登记一次活跃推理。返回的 [ModelAccessResult.Ready.lease]
     * 必须在推理结束的 `finally` 里交给 [release]；漏还一次，空闲/LRU 卸载就
     * 永远等不到 0。
     *
     * 分派**只**看 [mode]：两边同时打开时多模型胜出，按需的单模型状态机
     * 完全旁路。手动模式不应走到这里（调用方先查 [isEnabled]），真走到了
     * 就明确报错，而不是悄悄按旧状态机加载。
     */
    fun acquire(
        cancel: () -> Boolean = { false },
        progress: (String) -> Unit = {},
        requestedModel: String? = null,
    ): ModelAccessResult = when (mode()) {
        ModelServingMode.MULTI_MODEL ->
            MultiModelEngine.acquire(cancel, progress, requestedModel)
        ModelServingMode.ON_DEMAND ->
            when (val r = ModelLifecycle.acquire(cancel, progress, requestedModel)) {
                is ModelLifecycleCore.Result.Ready ->
                    ModelAccessResult.Ready(ModelAccessLease(r.lease))
                is ModelLifecycleCore.Result.Failed ->
                    ModelAccessResult.Failed(
                        r.message, r.retryable,
                        resourceExhausted = LoadFailure.looksLikeResourceExhaustion(r.message))
                is ModelLifecycleCore.Result.QueueFull ->
                    ModelAccessResult.QueueFull(r.limit)
                ModelLifecycleCore.Result.Canceled -> ModelAccessResult.Canceled
            }
        ModelServingMode.MANUAL ->
            ModelAccessResult.Failed(
                "模型访问未启用（按需加载与多模型均关闭）", retryable = false)
    }

    fun release(lease: ModelAccessLease?) {
        lease?.release()
    }
}

sealed class ModelAccessResult {
    data class Ready(val lease: ModelAccessLease) : ModelAccessResult()
    /**
     * @param resourceExhausted true = 更像「内存装不下」，HTTP 层据此回独立的
     *   错误类型（而不是与「模型不存在 / 开关关闭」共用一句 model_unavailable）。
     */
    data class Failed(
        val message: String,
        val retryable: Boolean = true,
        val resourceExhausted: Boolean = false,
    ) : ModelAccessResult()
    data class QueueFull(val limit: Int) : ModelAccessResult()
    object Canceled : ModelAccessResult()
}

/**
 * 一次推理的凭证。内部要么是 [ModelLifecycleCore.Lease]，要么是
 * [MultiModelEngine.Lease]；对外只暴露幂等的 [release]。
 */
class ModelAccessLease internal constructor(
    private val delegate: Any,
) {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    fun release() {
        if (!released.compareAndSet(false, true)) return
        when (delegate) {
            is ModelLifecycleCore.Lease -> ModelLifecycle.release(delegate)
            is MultiModelEngine.Lease -> delegate.release()
        }
    }
}
