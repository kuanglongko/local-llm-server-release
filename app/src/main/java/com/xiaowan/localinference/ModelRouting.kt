package com.xiaowan.localinference

/**
 * 多模型路由的**纯逻辑**：把一次请求解析成一个候选模型。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它回答什么、不回答什么
 * ══════════════════════════════════════════════════════════════════════════
 * 回答：请求里的 `model` / 端点角色 / 请求参数（tools、ctx）应该落到哪个
 * 候选项；没命中时是"明确失败"还是"按配置兜底"。
 *
 * **不**回答：候选模型现在加载了没有、能否再装一份、内存够不够 —— 那是
 * registry 与 LRU 的职责。把"选谁"和"装不装得下"分开，路由才能在宿主上
 * 穷举单测，而不用起 native。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么请求名字只认别名 / 文件名，不认 key
 * ══════════════════════════════════════════════════════════════════════════
 * [ModelRouteCandidate.key] 是内部身份（通常是规范化绝对路径），可能包含
 * 目录结构。请求体里的 `model` 是**外部输入**，允许它命中 key 就等于允许
 * `../../xxx` 一类路径参与选择。匹配只看 [alias] / [fileName]，与
 * [ModelLibrary.findByAliasOrName] 的边界一致。
 */
data class ModelRouteCandidate(
    /** 内部身份（建议 = 规范化路径 + 加载参数指纹）。只用于 runtime 查表。 */
    val key: String,
    /** `/v1/models` 对外展示的 id。 */
    val alias: String,
    /** 模型文件名（通常带 `.gguf`）。 */
    val fileName: String,
    /** 对话 / 向量 / 未分类。只有 [ModelRole.CHAT] 能承接生成请求。 */
    val role: ModelRole,
    /** 动态选择时的优先级，越大越优先。 */
    val priority: Int = 0,
    /** 是否支持工具调用（动态选择用；静态命中不看它）。 */
    val supportsTools: Boolean = false,
    /** 训练上下文上限；`0` = 未知，动态筛选时不拿它排除。 */
    val ctxTrain: Int = 0,
    /** 用户标记为常驻，不允许 LRU 驱逐（路由仍可命中它）。 */
    val pinned: Boolean = false,
    /**
     * 内部规范化路径。**只用于**把命中的候选项交给运行时，不参与请求匹配。
     * 空 = 调用方按 [fileName] 再解析一次（测试/旧构造点）。
     *
     * 为什么要单独存：同名文件可能同时出现在应用内副本与外部引用里，而
     * `findByAliasOrName` 固定先命中应用内副本 —— 只用 [fileName] 回查会让
     * "命中的是外部条目、加载的却是同名内部文件"，key 与 path 自此分叉。
     */
    val path: String = "",
) {
    /** 请求里的名字是否命中这一项。别名优先，文件名可省略 `.gguf`。 */
    fun matches(name: String): Boolean {
        val want = name.trim()
        if (want.isEmpty()) return false
        if (alias == want) return true
        if (fileName == want) return true
        if (fileName.endsWith(".gguf", ignoreCase = true) &&
            fileName.dropLast(5) == want) return true
        return false
    }

    fun describe(): String = if (alias.isNotBlank()) alias else fileName
}

enum class ModelRoutingMode { STATIC, DYNAMIC }

sealed class ModelRouteDecision {
    /** 命中一个候选。[reason] 只进日志与 `/health`，不参与后续判定。 */
    data class Hit(val candidate: ModelRouteCandidate, val reason: String) : ModelRouteDecision()

    /** 请求明确指定了一个不存在的 / 不能用于对话的模型。**不要**静默兜底。 */
    data class NotFound(val message: String) : ModelRouteDecision()

    /** 名字同时命中多个不同模型（别名冲突）。拒绝比随便挑一个安全。 */
    data class Conflict(val message: String) : ModelRouteDecision()
}

/**
 * 路由规则的唯一实现。无 Android / 无 JNI，宿主可单测。
 *
 * 解析顺序固定为：
 *   ① 请求显式 `model` —— 命中且唯一才放行；不存在 / 非对话模型 -> 明确失败。
 *   ② 静态预设（`defaultModel`）—— 显式配置优先于任何动态规则。
 *   ③ 动态规则（仅 [ModelRoutingMode.DYNAMIC]）—— 按 tools / ctx / 优先级挑。
 *   ④ 兜底（`fallbackModel`）—— 只在前三步都没命中时使用。
 *   ⑤ 全空 -> 明确失败。
 */
object ModelRouter {

    fun resolve(
        requestedModel: String?,
        candidates: List<ModelRouteCandidate>,
        mode: ModelRoutingMode = ModelRoutingMode.STATIC,
        defaultModel: String? = null,
        fallbackModel: String? = null,
        needsTools: Boolean = false,
        requiredCtx: Int = 0,
    ): ModelRouteDecision {
        val chat = candidates.filter { it.role == ModelRole.CHAT }

        // ① 请求显式指定：必须是对话模型，且只能命中一个 key。
        val wanted = requestedModel?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) {
            val hits = candidates.filter { it.matches(wanted) }
            if (hits.isEmpty()) {
                return ModelRouteDecision.NotFound(
                    "请求指定的模型「$wanted」不在模型库中；请用 /v1/models 返回的 id/别名")
            }
            val byKey = hits.distinctBy { it.key }
            if (byKey.size > 1) {
                return ModelRouteDecision.Conflict(
                    "请求指定的模型「$wanted」同时命中 ${byKey.size} 个模型（别名冲突）：" +
                        byKey.joinToString("、") { it.describe() } +
                        "；请到模型库改成唯一别名")
            }
            val hit = byKey.first()
            if (hit.role != ModelRole.CHAT) {
                return ModelRouteDecision.NotFound(
                    "请求指定的模型「$wanted」是${roleLabel(hit.role)}，不能用于对话推理")
            }
            return ModelRouteDecision.Hit(hit, "请求 model 字段")
        }

        // ② 显式预设 / 上次成功：名字无效时继续往下走，不在这里把整个池判死；
        //    但命中多个不同 key 时同样拒绝 —— 预设也是"指定了一个名字"，
        //    静默挑第一个就是"答了另一个模型"。
        val preset = defaultModel?.trim()?.takeIf { it.isNotEmpty() }
        if (preset != null) {
            val hits = chat.filter { it.matches(preset) }.distinctBy { it.key }
            if (hits.size > 1) {
                return ModelRouteDecision.Conflict(
                    "预设模型「$preset」同时命中 ${hits.size} 个模型：${hits.joinToString("、") { it.describe() }}" +
                        "；请到模型库改成唯一别名")
            }
            if (hits.size == 1) return ModelRouteDecision.Hit(hits[0], "预设 / 上次成功")
        }

        // ③ 动态选择：只在明确打开 dynamic 时启用。
        if (mode == ModelRoutingMode.DYNAMIC && chat.isNotEmpty()) {
            val usable = chat.filter { c ->
                (!needsTools || c.supportsTools) &&
                    (requiredCtx <= 0 || c.ctxTrain <= 0 || c.ctxTrain >= requiredCtx)
            }
            // 没有任何候选满足硬条件时才退到"只要对话模型"——
            // 这样"长 ctx 请求"不会因为所有模型都没标 ctxTrain 而直接 404。
            val pool = usable.ifEmpty { chat }
            val picked = pool.sortedWith(
                compareByDescending<ModelRouteCandidate> { it.priority }
                    .thenBy { it.key }
            ).first()
            val why = buildString {
                append("动态选择")
                if (needsTools) append("（tools）")
                if (requiredCtx > 0) append("（ctx≥$requiredCtx）")
                if (usable.isEmpty()) append("：无候选满足硬条件，回退到任意对话模型")
            }
            return ModelRouteDecision.Hit(picked, why)
        }

        // ④ 兜底：静态模式下也允许显式配置，但只在前三步都没命中时生效。
        //    与预设同一条纪律：命中多个不同 key 时明说冲突，不静默挑一个。
        val fallback = fallbackModel?.trim()?.takeIf { it.isNotEmpty() }
        if (fallback != null) {
            val hits = chat.filter { it.matches(fallback) }.distinctBy { it.key }
            if (hits.size > 1) {
                return ModelRouteDecision.Conflict(
                    "兜底模型「$fallback」同时命中 ${hits.size} 个模型：${hits.joinToString("、") { it.describe() }}" +
                        "；请到模型库改成唯一别名")
            }
            if (hits.size == 1) return ModelRouteDecision.Hit(hits[0], "兜底模型")
        }

        // ⑤ 没有可用目标：把原因说清楚，便于用户回模型库配置。
        val any = chat.firstOrNull()
        return ModelRouteDecision.NotFound(
            if (any == null) "模型库中没有可用于对话的模型；请先导入对话模型"
            else "没有模型匹配本次请求；请设置预设模型，或打开动态路由 / 配置兜底模型")
    }

    private fun roleLabel(role: ModelRole): String = when (role) {
        ModelRole.EMBED -> "向量模型"
        ModelRole.CHAT -> "对话模型"
        ModelRole.UNKNOWN -> "未分类模型"
    }
}
