package com.xiaowan.localinference

/**
 * 模型在库里**是干什么用的**：对话/生成，还是向量/嵌入。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要这个类型（而不是"两张表都列全部文件"）
 * ══════════════════════════════════════════════════════════════════════════
 * 对话模型与嵌入模型共用同一个模型库目录，但**装进的是两个不同的槽**：
 * 对话槽走 `LlmEngine.loadModel`，嵌入槽走 `LlmEngine.loadEmbedModel`。
 * 用户从同一张表里点错时，App 不会当场报错 —— 它只是把一份不该装进来的
 * 权重装进另一个槽，症状要到"聊天没输出"或"检索永远为空"才出现。
 * 所以在**选择之前**就把两类分开，是唯一能在误点发生前拦住它的位置。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么还要"猜"
 * ══════════════════════════════════════════════════════════════════════════
 * 只在导入时问一次是不够的：升级前已经导入的模型没有角色记录，用户手里
 * 也常常是一批文件名很清楚的 GGUF。纯靠手选会让老用户每次打开都要把
 * 一整库重新标一遍。所以这里给一个**只读文件名**的启发式：
 *   · 文件名里带 `embed` / `bge` / `e5` / `gte` / `minilm` 等 -> 向量；
 *   · 带 `instruct` / `chat` / `qwen` / `llama` / `gemma` 等 -> 对话；
 *   · 都认不出 -> `UNKNOWN`（两个列表都显示并标"未分类"，不替用户猜）。
 * 猜错的代价被控制在"默认值选错一次"，因为导入对话框要求用户确认，
 * 列表里也随时可以点「类型」改。**绝不**把猜测当成不可改的事实。
 *
 * 这个类型是纯 Kotlin（不引 Android），所以文件名判据可以在宿主侧穷举单测；
 * 持久化由 [ModelLibrary] 负责。
 */
enum class ModelRole(val id: String) {
    CHAT("chat"),
    EMBED("embed"),
    UNKNOWN("unknown");

    companion object {
        /**
         * 从持久化字符串解析。`null` / 空串 / 未知值一律 `UNKNOWN`。
         *
         * 注意：[UNKNOWN] 有两种来源，语义不同 ——
         *   · **没有存过**（`roleOf` 会先按文件名猜，见 [ModelLibrary.roleOf]）；
         *   · **明确存成 `unknown`**（用户在「类型」里选了"未分类"，压过自动识别）。
         * 两者都落到本函数返回 `UNKNOWN`，因为"这个模型现在算哪一类"确实都是
         * 未分类；区别只在 [ModelLibrary] 读键时做的那一次 `null` 判断。
         */
        fun fromId(raw: String?): ModelRole =
            values().firstOrNull { it.id == raw } ?: UNKNOWN

        /**
         * 只按文件名猜角色；认不出返回 [UNKNOWN]。
         *
         * 判据刻意**不做**整串 `contains`，而是要求命中词两侧是"非字母数字"边界：
         * `e5` 这种两字符词如果直接 `contains`，`test5` / `qwen2.5` 一类名字会
         * 被误判成向量模型；误判的结果是"对话模型从对话列表里消失"，比不猜更糟。
         */
        fun guess(fileName: String): ModelRole {
            val s = fileName.lowercase().let {
                if (it.endsWith(".gguf")) it.dropLast(5) else it
            }
            if (s.isBlank()) return UNKNOWN
            if (s.contains("向量") || s.contains("嵌入")) return EMBED
            if (s.contains("对话")) return CHAT
            if (EMBED_HINTS.any { hasToken(s, it) }) return EMBED
            if (CHAT_HINTS.any { hasToken(s, it) }) return CHAT
            return UNKNOWN
        }

        /** [hint] 是否作为一个独立词出现在 [s] 里（两侧非 `[a-z0-9]`）。 */
        private fun hasToken(s: String, hint: String): Boolean {
            var from = 0
            while (true) {
                val i = s.indexOf(hint, from)
                if (i < 0) return false
                val leftOk = i == 0 || !s[i - 1].isLetterOrDigit()
                val end = i + hint.length
                // 右侧允许**数字**：`qwen3` / `llama3` / `chatglm3` 是"同一个词根 + 版本号"，
                // 不是另一个词。左侧仍要求严格边界（`codegemma` 的 `gemma` 前是字母，
                // 所以它单独列进 CHAT_HINTS，而不是放宽成任意子串）。
                val rightOk = end == s.length || !s[end].isLetterOrDigit() || s[end].isDigit()
                if (leftOk && rightOk) return true
                from = i + 1
            }
        }

        /**
         * 向量/嵌入模型的常见文件名标记。命中即**优先于**对话标记：
         * `qwen3-embedding-0.6b` 里既有 `qwen` 也有 `embedding`，它必须算向量。
         */
        private val EMBED_HINTS = listOf(
            "embed", "embedding", "embeddinggemma", "bge", "e5", "gte",
            "jina", "nomic", "mxbai", "minilm", "text2vec", "m3e", "bce",
            "stella", "arctic-embed", "instructor", "voyage", "harrier",
            "text-embedding", "paraphrase", "sentence-transformers",
            "multi-qa", "all-minilm", "e5-mistral", "qwen3-embedding",
            "granite-embedding",
        )

        /**
         * 对话/生成模型的常见文件名标记。
         *
         * 只收"出现就基本能确定"的词：宁可落到 `UNKNOWN` 让用户确认，
         * 也不要把一个名字里恰好有 `yi` 的文件直接塞进对话列表。
         */
        private val CHAT_HINTS = listOf(
            "instruct", "chat", "chatglm", "qwen", "llama", "mistral",
            "mixtral", "gemma", "phi", "deepseek", "yi", "glm", "internlm",
            "baichuan", "falcon", "mamba", "rwkv", "smollm", "tinyllama",
            "vicuna", "zephyr", "openchat", "solar", "command-r", "gpt",
            "gpt2", "granite", "nemotron", "olmo", "exaone", "hermes",
            "dolphin", "wizard", "minicpm", "llava", "moondream",
            "codellama", "coder", "reasoner", "codegemma",
        )
    }
}
