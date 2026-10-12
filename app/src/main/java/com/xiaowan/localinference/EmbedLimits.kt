package com.xiaowan.localinference

/**
 * 嵌入这一块**三个数的唯一出处**（默认批大小 / 合批上限 / 检索对照取前几名）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么这三个数要从 `LlmEngine` 搬到这里
 * ═══════════════════════════════════════════════════════════════════════════
 * 它们是**纯数字**，却一直住在 `LlmEngine` 里 —— 而 `LlmEngine.kt` 依赖 Android
 * （`Context` / native / `org.json`），**编不到宿主上**。本仓库那几十份
 * `tools/run_*_tests.sh` 的做法是"只编被测对象 + 测试本体（都是纯逻辑）"，
 * 所以凡是要读这三个数的测试就都编不过：
 *
 *   · `tools/run_embed_parity_tests.sh`：单测读 `LlmEngine.kEmbedBatchDefault` /
 *     `kRetrieveParityTopK` -> `unresolved reference 'LlmEngine'`，`exit 1`；
 *   · `tools/run_health_tests.sh`：`HealthCheck.kt` 第 526 行读
 *     `LlmEngine.kEmbedBatchRows`（"嵌入每条预算"那一格要拿它算 ctx 总量）
 *     -> 同一句 `unresolved reference`。
 *
 * ⚠ 这两份**都不是**"工具链缺失"：有 `kotlinc` 时它们是**编不过**，
 *   而两轮 PR（#308 / #309）都把这份读数记成了"本机 SKIP（无 kotlinc/JDK）"。
 *   修好运行器之后，两份套件当场多报出 **13 条真失败**（断言主语取错行 /
 *   测试输入 `1.0f + 1e-9f` 在 f32 里是空操作 / 措辞少一个字恒假）。
 *   这一族与 `run_kt_test_source_guard.py` 记的那次同因：
 *   **"编不过"与"环境缺件"在汇总里长得一模一样**。
 *
 * 所以把数搬到纯逻辑层：`kotlinc` 编得到、单测读得到、守卫照样钉得住
 * （`check_embed_batch_wiring.py` 核 Kotlin 与 native **同一个数**，
 * `check_embed_parity_wiring.py` / `check_embed_retrieve_wiring.py` 核样本条数对齐）。
 *
 * ⚠ 名字保留 `kEmbedBatchDefault` / `kEmbedBatchRows` / `kRetrieveParityTopK`
 *   这**三个原名**（`LlmEngine` 里仍以同名常量转发）—— 改名会让几十处既有守卫
 *   与本仓库的历史记录（"`kEmbedBatchRows × 每条预算 ≤ 两个门`"那些推导）
 *   一次性失去锚点，而这里的目的是**让测试编得过**，不是重命名。
 *
 * 纯常量，不碰 Android / 文件 / native —— 见 `EmbedParityProbe` 同一个规矩。
 */
object EmbedLimits {

    /**
     * 默认每批编几条（用户没填、或填了个非法值时用它）。
     *
     * 取 8 的道理与 native 侧同一个：合批摊薄的是"每次 decode 的固定开销"，
     * 而这个开销按整批 token 数算 —— 8 条 × 约 300 token ≈ 2400，已经越过
     * `n_batch` 默认的 2048，库会自己再切 ubatch。再往上只是把切分堆上去。
     */
    const val kEmbedBatchDefault = 8

    /**
     * native 侧合批的**真实上限**（= 建嵌入 ctx 时写进 `n_seq_max` 的 `kEmbedBatchRows`）。
     *
     * 与 native 那一份**同一个数**：超过它时 native 会返回 `Unavailable`
     * 让我们回落逐条 —— 那就白批了（每批都在回落，速度反而比逐条还差
     * 一点点，而日志里只有一行）。判据正面钉住两处相等
     * （`check_embed_batch_wiring.py`）。
     *
     * ⚠ 为什么在 Kotlin 这边**又写一份**（而不是从 native 读）：合批上限是
     * "建 ctx 时的 n_seq_max"，那个值只在加载完成后才知道，而设置页要在加载**之前**
     * 就能给出合法区间。两处各写一份的风险由守卫抵消（不等就红）。
     */
    const val kEmbedBatchRows = 8

    /**
     * 「检索对照」取前几名（与用户实际用的 top-k 同一档，见 `RagInject.DEFAULT_TOP_K`）。
     *
     * 它与"语料够不够长"是同一件事的两面：语料必须严谨地超过它的两倍，
     * 「某一条被挤出前 3」才看得见（见 `EmbedParityProbe.RETRIEVE_TEXTS` 的对象头）。
     */
    const val kRetrieveParityTopK = 3

    /**
     * 「**长短悬殊**」那组样本的标签 —— 也是**落进 `/health`** 的那一组。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么两个组名也要住在纯逻辑层
     * ═══════════════════════════════════════════════════════════════════════
     * `HealthCheck.kt`（探活报告）要按**取样的那一组**换措辞：等长组按构造
     * 必然稳，它全同**答不了**"长短悬殊那批换批大小会不会改答案" —— 而后者
     * 才是「`g` 能不能按库放行」的依据（真机现场 PR #301 正是拿等长组那句去
     * 答长短悬殊那组的问题）。于是报告要能认出组名，而 `HealthCheck.kt`
     * 编在宿主上（**不带** `LlmEngine.kt`）—— 组名写在 `LlmEngine` 里时
     * 那句引用就是 `unresolved reference`（本对象头记的正是这一族）。
     *
     * 所以与那三个数同源：数值/字面量住在纯逻辑层，`LlmEngine` 里同名转发
     * （一个数、一个出处）。
     */
    const val kLongShortLabel = "长短悬殊"

    /**
     * 「**等长**」那组样本的标签（只作对照与日志回显，**不落** `/health`）。
     *
     * ⚠ 与 [kLongShortLabel] **不是同一个常量**、也**不是同一组样本**：两格
     *   都取长短悬殊那组（放行 `g` 取决于**最坏**的那组）。共用一个名字会让
     *   "改的是哪一格"看不出来（本仓库反复记录的那族）。
     */
    const val kSameLenLabel = "等长"
}
