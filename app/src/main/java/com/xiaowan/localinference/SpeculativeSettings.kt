package com.xiaowan.localinference

import android.content.Context

/**
 * 投机解码（MTP / 外部 draft / NGram 自投机）的**加载期**配置。
 *
 * 单一真源就在本文件：设置页只写这里的键，LlmEngine 加载前调
 * [LlmEngine] 的 `nativeSetSpecLoadConfig` 原样交给 native。没有第二份副本 ——
 * 与 repack / tiedOutCpu 那两个开关同一条纪律（两个键各写各的会让
 * 界面显示与 native 收到的值永久分叉）。
 *
 * 默认**全关**：mode=0 时 native 不设 load_mtp、不建 draft ctx、不新增日志，
 * 加载/推理/卸载与本功能引入前逐字节相同。
 */
object SpeculativeSettings {
    private const val KEY_MODE = "spec_mode"                 // 0=关 1=draft-simple 2=ngram 3=MTP
    private const val KEY_N_MAX = "spec_n_max"               // 草稿上限
    private const val KEY_DRAFT = "spec_draft_model"         // 外部 draft 的 GGUF 路径
    private const val KEY_DRAFT_NGL = "spec_draft_ngl"       // draft 模型 GPU 层数（-1=跟随）
    private const val KEY_DRAFT_NGL_SET = "spec_draft_ngl_set" // 用户**显式**选过卸载层数
    private const val KEY_ALLOW_GREEDY = "spec_allow_greedy" // 贪心也投机（默认关）
    private const val KEY_REJECT_SAMPLING = "spec_reject_sampling" // 概率化草稿 + 拒绝采样（默认开）
    private const val KEY_DRAFT_EXCLUDE_HTP = "spec_draft_exclude_htp" // draft 不上 NPU（默认关）

    const val MODE_OFF = 0
    const val MODE_DRAFT_SIMPLE = 1
    /**
     * NGram 自投机：不加载 draft 模型、不建 draft ctx，直接在**已生成的 token 历史**
     * 里找相同的 n-gram，把后面跟着的 m-gram 当草稿。成本只有一次查找，草稿质量
     * 取决于文本重复度（代码、日志、结构化输出通常比自由创作高）。
     * 不需要 draft 路径，也没有概率化草稿/拒绝采样（没有 q）。
     */
    const val MODE_NGRAM = 2
    const val MODE_MTP = 3

    const val DEFAULT_N_MAX = 3

    /**
     * `draftGpuLayers` 的「跟随目标」值。
     *
     * 外部 draft 是**独立模型**，`llama_model_params.n_gpu_layers` 不会继承目标模型的
     * 卸载层数。旧默认写 0 = draft 全部跑 CPU —— 真机上这直接成了投机的瓶颈：
     * 每步 3 枚草稿串行跑 CPU 前向，比目标模型本身还贵，开投机反而更慢
     * （2026-11-07 日志：HTP/OpenCL 两种后端开投机后都掉到 ~280ms/step，
     * 而目标单 token 分别是 146 / 93ms）。
     *
     * -1 在 llama.cpp 里是「全部层交给加速器」，对 29 层的 0.6B draft 等价于
     * 跟随目标卸载。加载时由 `LlmEngine` 把 -1 解析成目标的 `gpuLayers`；
     * 目标 GPU 加载失败回退纯 CPU 时，draft 也同步落回 0。
     */
    const val DRAFT_GPU_FOLLOW = -1

    data class Config(
        val mode: Int = MODE_OFF,
        val nMax: Int = DEFAULT_N_MAX,
        val draftPath: String = "",
        /** draft 模型 GPU 层数：-1=跟随目标（推荐），0=纯 CPU，N=前 N 层。 */
        val draftGpuLayers: Int = DRAFT_GPU_FOLLOW,
        /**
         * 贪心（temperature<=0）请求是否也投机。默认 **false**：量化模型下
         * batch 前向与单 token 前向存在数值差，贪心会在高熵位置翻 argmax，
         * 无法保证"开/关投机逐字一致"。要加速可以显式打开并接受差异。
         */
        val allowGreedy: Boolean = false,
        /**
         * draft-simple 是否走「概率化草稿 + 拒绝采样」。默认 **true**：
         * draft 按目标温度采样并返回候选分布 q，验证时按 min(1, p/q) 接受，
         * 比"草稿取 argmax 后逐 token 比对"接受率更高，且**保持目标分布不变**。
         * 关掉 = 回到逐 token 匹配（Phase 1 行为）。贪心请求本来就不走它。
         */
        val rejectSampling: Boolean = true,
        /**
         * 外部 draft 是否**排除 HTP（NPU）**。默认 **false** = 与引入前相同，
         * draft 和目标共用整池（HTP 开时 draft 也可能分到 NPU 层）。
         *
         * 打开后只改 draft 那份 `llama_model_params.devices`：目标模型照旧用
         * HTP+OpenCL+CPU，draft 只用 OpenCL/CPU。原因是 draft 每步只跑 1-4 枚
         * token，NPU 的 launch 开销占主导 —— 真机 HTP 开时 draft 131.5 -> 282.2
         * ms/步，比把它放回 OpenCL 还慢。
         */
        val draftExcludeHtp: Boolean = false,
    ) {
        val enabled: Boolean get() = mode != MODE_OFF && nMax > 0
    }

    /**
     * 读**原始**配置（不做"外部 draft 缺路径就降级"）。
     *
     * 设置页必须用这一份：否则用户先选了「外部 draft」、还没挑文件，
     * 下次打开设置页就会被显示成「关闭」，随后任何一次保存都会把模式真的写回 0
     * —— 用户的选择在两次打开之间静默丢失。[load] 才做降级，因为那是引擎侧
     * 的**生效值**，两者不是同一件事。
     */
    fun read(ctx: Context): Config {
        val p = ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE)
        val mode = p.getInt(KEY_MODE, MODE_OFF).let {
            if (it == MODE_DRAFT_SIMPLE || it == MODE_NGRAM || it == MODE_MTP) it else MODE_OFF
        }
        return Config(
            mode = mode,
            nMax = p.getInt(KEY_N_MAX, DEFAULT_N_MAX).coerceIn(0, 16),
            draftPath = p.getString(KEY_DRAFT, "").orEmpty(),
            draftGpuLayers = readDraftNgl(p),
            allowGreedy = p.getBoolean(KEY_ALLOW_GREEDY, false),
            rejectSampling = p.getBoolean(KEY_REJECT_SAMPLING, true),
            draftExcludeHtp = p.getBoolean(KEY_DRAFT_EXCLUDE_HTP, false),
        )
    }

    /**
     * 读 draft 卸载层数，并把旧版本遗留的 `0` 迁到「跟随目标」。
     *
     * 旧版本（`KEY_DRAFT_NGL_SET` 出现之前）默认写 0 = draft 全 CPU，而且**每次保存
     * 都会把这个 0 落盘**。升级后如果照读，用户从没做过选择却一直吃最慢的配置 ——
     * 2026-11-07 真机日志里 `draft_ngl=0` 就是这么来的（开投机反而更慢）。
     *
     * 区分靠「用户显式选过」标记：
     *   · 有标记：0 是用户主动取消勾选「draft 也放加速器」，原样尊重；
     *   · 无标记且为 0：旧版遗留，迁移为 [DRAFT_GPU_FOLLOW]；
     *   · 无标记但为正数：用户曾在旧版手填层数，也视为有效选择（不做迁移）。
     *
     * 迁移只改**读出来的值**，不偷偷写盘：设置页会显示为已勾选，用户下次保存时
     * 才连同标记一起落盘。这样"打开设置页"本身不会产生写操作。
     */
    private fun readDraftNgl(p: android.content.SharedPreferences): Int {
        val raw = p.getInt(KEY_DRAFT_NGL, DRAFT_GPU_FOLLOW)
        if (raw < 0) return DRAFT_GPU_FOLLOW
        val explicitlyChosen = p.getBoolean(KEY_DRAFT_NGL_SET, false)
        return if (raw == 0 && !explicitlyChosen) DRAFT_GPU_FOLLOW else raw
    }

    /** 引擎侧**生效值**：外部 draft 缺路径视为关，避免让 native 走注定失败的分支。 */
    fun load(ctx: Context): Config {
        val cfg = read(ctx)
        // 外部 draft 没有路径 = 配置不完整：降级为关（而不是让 native 加载失败）。
        return if (cfg.mode == MODE_DRAFT_SIMPLE && cfg.draftPath.isBlank())
            cfg.copy(mode = MODE_OFF) else cfg
    }

    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(Prefs.MODEL, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MODE, cfg.mode)
            .putInt(KEY_N_MAX, cfg.nMax.coerceIn(0, 16))
            .putString(KEY_DRAFT, cfg.draftPath)
            .putInt(KEY_DRAFT_NGL, cfg.draftGpuLayers)
            .putBoolean(KEY_DRAFT_NGL_SET, true)
            .putBoolean(KEY_ALLOW_GREEDY, cfg.allowGreedy)
            .putBoolean(KEY_REJECT_SAMPLING, cfg.rejectSampling)
            .putBoolean(KEY_DRAFT_EXCLUDE_HTP, cfg.draftExcludeHtp)
            .apply()
    }
}
