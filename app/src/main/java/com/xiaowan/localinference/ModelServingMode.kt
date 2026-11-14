package com.xiaowan.localinference

/**
 * 推理入口**当前真正生效**的服务模式。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要它
 * ══════════════════════════════════════════════════════════════════════════
 * 按需加载（[OnDemandSettings] / [ModelLifecycle]）与多模型池（[MultiModelSettings]
 * / [MultiModelEngine]）各有自己的开关。两者同时打开时，请求只会走一条路 ——
 * 多模型优先（见 [ModelAccess.acquire]）。但设置页与 `/health` 此前把两边都
 * 当成"各自生效"，于是用户会看到「按需空闲 60s、多模型空闲 120s」两条配置，
 * 却没有任何一处回答**哪一条在计时**。
 *
 * 这个枚举只做一件事：把「两个开关」收敛成**唯一**的当前模式。展示层、健康
 * 检查、路由入口都从这里读，不再各自写一遍 `if (multi) ... else if (onDemand)`。
 *
 * 优先级固定为 [MULTI_MODEL] > [ON_DEMAND] > [MANUAL]：多模型是更完整的池化
 * 语义，打开它就应当接管；按需加载保留为单模型模式，等设备验收通过后再收敛。
 */
enum class ModelServingMode(val wireName: String) {
    /** 两个开关都关：与引入按需/多模型之前逐字节相同（手动加载，常驻）。 */
    MANUAL("manual"),

    /** 只开按需加载：单模型懒加载 + 空闲释放。 */
    ON_DEMAND("on_demand"),

    /** 开了多模型：池化路由 / LRU / 内存高水位；按需的单模型状态机被旁路。 */
    MULTI_MODEL("multi_model");

    companion object {
        /**
         * 唯一优先级判决，**纯函数**（宿主侧单测直接钉这条）。
         *
         * 两边同时为真时返回 [MULTI_MODEL]，而不是抛错或让调用方二选一：
         * 升级后用户可能两个开关都留着，这时必须有一个确定的赢家，且所有
         * 入口（HTTP / App 聊天 / 设置页 / `/health`）看到的是同一个。
         */
        fun fromFlags(
            multiModelEnabled: Boolean,
            onDemandEnabled: Boolean,
        ): ModelServingMode = when {
            multiModelEnabled -> MULTI_MODEL
            onDemandEnabled -> ON_DEMAND
            else -> MANUAL
        }
    }
}
