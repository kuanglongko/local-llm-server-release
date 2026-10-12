package com.xiaowan.localinference

/**
 * 模型档案：库**真读出来的**那几个数，与「文件有多大」是两件事。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么必须把它们分开列
 * ═══════════════════════════════════════════════════════════════════════════
 * 本仓库反复出现的坑是**「哪两个数不是一回事」**：文件名写着 `Q4_K_M`、实际是
 * `IQ4_XS`（`[HTP判定]` 那一族）；文件名写着 `4B`、那是文件名；`ModelStore.Row.bytes`
 * 是**文件字节**（含元数据/词表/对齐填充），既不等于**权重字节**，也不告诉你
 * **参数个数**与**上下文上限**。
 *
 * 于是"这个包是不是这次构建""这份权重到底多大"只能靠 `ls -l` 和猜。这一份就是
 * 把那几个数从库里读出来、各自**分开**呈现 —— 不合并、不推导、不用一个充另一个。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 三条约定（都是"不这么做就会静默说错话"）
 * ═══════════════════════════════════════════════════════════════════════════
 * ① **取不到就是 null，不是 0**。0 与"读不到"在客户端完全同形，而"0 层"是个
 *    不存在的东西 —— 那会让 `GET /v1/models/{id}` 的消费者把"这字段没有"读成"它是 0"。
 * ② **不缓存**。缓存要回答"换模型时怎么失效"，而失效判据正是静默 bug 的温床；
 *    档案是低频读（`/v1/models` 轮询），重读的成本远低于错读。
 * ③ **数值一律走 `Long`**。权重字节/参数个数在 native 侧是 `uint64_t`，
 *    Kotlin 侧用 `Int` 会静默溢出成负数 —— 而"负数个参数"这种读数比缺字段更难认。
 */
data class ModelProfile(
    /** 权重张量字节数（≠ 文件字节；文件里还有元数据/词表/对齐填充）。 */
    val tensorBytes: Long?,
    /** 参数个数（GGUF 里声明的总参数量，不区分是否被量化）。 */
    val paramCount: Long?,
    /** 模型名（GGUF `general.name`，通常比文件名长且更准确）。 */
    val name: String?,
    /** **训练时**声明的上下文上限（`llama.context_length`），不是用户设的 `n_ctx`。 */
    val ctxTrain: Int?,
    /** 架构名（`general.architecture`，如 `qwen3` / `minicpm5` / `lfm2`）。 */
    val architecture: String?,
    /** 词表大小。 */
    val vocabSize: Int?,
    /** 量化类型（`general.file_type`，如 `15` = MOSTLY_Q4_K_M）。 */
    val fileType: String?,
    /** GGUF 元数据键值对条数 —— 「这个包是不是完整/是不是这次构建」的粗读数。 */
    val metaCount: Int?,
) {
    /** 档案**整体**有没有可读内容。全空（未加载 / 旧 .so 不导出该符号）时为 false。 */
    val available: Boolean
        get() = tensorBytes != null || paramCount != null || name != null ||
            ctxTrain != null || architecture != null || vocabSize != null ||
            fileType != null || metaCount != null

    /**
     * 一行日志用的可读形式。**只报读到的字段** —— 缺的字段直接不出现，
     * 不写 "?"、不写 0：`[模型档案]` 这一行的用途是与 `[mmap释放]` 的读数对照，
     * 混进占位符就等于让两行对不上账时无从判断是"没有"还是"读错"。
     */
    fun describe(): String {
        val parts = ArrayList<String>(8)
        tensorBytes?.let { parts += "权重 ${humanBytes(it)}" }
        paramCount?.let { parts += "参数 ${humanCount(it)}" }
        ctxTrain?.let { parts += "训练上下文 $it" }
        architecture?.let { parts += "架构 $it" }
        vocabSize?.let { parts += "词表 $it" }
        fileType?.let { parts += "dtype $it" }
        metaCount?.let { parts += "元数据 $it 条" }
        name?.let { parts += "名 $it" }
        return parts.joinToString(" ｜ ")
    }

    companion object {
        /**
         * 从 native 的 `String[8]` 解析。**顺序是接口**：native 侧 `nativeModelMeta`
         * 按同一顺序写，两边由 `tools/run_models_profile_guard.sh` 钉住同一个 8。
         *
         * 越界/短数组一律当"读不到"：这是**跨版本**场景（新 Kotlin 撞旧 .so）的唯一
         * 兜底，崩在这里等于"档案这个可选装饰把 /v1/models 整个接口带崩"。
         */
        fun fromNative(a: Array<String>?): ModelProfile {
            fun s(i: Int): String? = a?.getOrNull(i)?.takeIf { it.isNotEmpty() }
            fun l(i: Int): Long? = s(i)?.toLongOrNull()
            fun n(i: Int): Int? = s(i)?.toIntOrNull()
            return ModelProfile(
                tensorBytes = l(0),
                paramCount = l(1),
                name = s(2),
                ctxTrain = n(3),
                architecture = s(4),
                vocabSize = n(5),
                fileType = s(6),
                metaCount = n(7),
            )
        }

        /** 1.5 GiB / 512 MiB 这种；小于 1 GiB 才退到 MiB。 */
        fun humanBytes(v: Long): String {
            val mib = v / (1024.0 * 1024.0)
            return if (mib >= 1024) String.format(java.util.Locale.US, "%.2f GiB", mib / 1024)
            else String.format(java.util.Locale.US, "%.0f MiB", mib)
        }

        /** 参数个数：0.6B / 4B / 1.2B。**必须按十进制 B**（模型厂商标的就是十进制）。 */
        fun humanCount(v: Long): String {
            val b = v / 1e9
            return if (b >= 1) String.format(java.util.Locale.US, "%.2fB", b)
            else String.format(java.util.Locale.US, "%.0fM", v / 1e6)
        }
    }
}
