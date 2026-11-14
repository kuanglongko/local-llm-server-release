package com.xiaowan.localinference

/**
 * `SharedPreferences` 的 **store 名唯一出处**。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么必须收口到一处
 * ═══════════════════════════════════════════════════════════════════════
 * store 名是一个**字符串主键**：写进去与读出来必须逐字相同，而编译器对
 * `getSharedPreferences("rag_store", ...)` 与 `getSharedPreferences("rag_stroe", ...)`
 * 一视同仁 —— 后者不报错，只是永远读到默认值。症状是"设置保存了、但下次进来
 * 又变回去"，或者"`/health` 报的值与设置页不一致"，两种都**没有任何日志**。
 *
 * 收口之前 `"rag_store"` 在 8 个文件里各写一遍（6 个 `Embed*Pref` / `ContextWatermark` /
 * `ThemePref` / `RagInject`），`"model_store"` 曾在 `ModelStore` 与 `LlmEngine` 各写一遍
 *（前者已按职责拆成 `ModelLibrary` / `ServerSettings`，两者都引用 [MODEL]），
 * `"ui_prefs"` 在 `EngineActivity` 里出现 4 次。改其中一处而漏掉另一处，
 * 就是上面那种静默失败。
 *
 * ⚠ 这些**字面量本身不能改**：它们已经是用户设备上落盘的目录名，改了等于
 *   让所有升级用户的设置凭空丢失。本文件只负责"只有这一处写它"。
 */
object Prefs {

    /** 界面状态：折叠区的展开/收起（`sec:*` / `sec_ver`）、日志页过滤档位与关键字。 */
    const val UI = "ui_prefs"

    /** 知识库怎么用：检索开关、当前库名、嵌入三格（批大小 / ctx / 两道门）、
     *  上下文水位、主动裁剪、暗色档位。 */
    const val RAG = "rag_store"

    /** 模型名录 + 服务端设置（端口 / 局域网 / 思考默认 / CORS / token / flash-attn /
     *  引擎参数）。`LlmEngine` 读的是同一个 store 里的 NPU 相关几格。 */
    const val MODEL = "model_store"

    /** native 探针开关（`probe_on`）。与 [MODEL] 分开是刻意的：探针是排障用的临时开关，
     *  不该让"清空模型设置"顺手把它关掉。 */
    const val PROBE = "probe_prefs"

    /** 「就地引用」的外部 GGUF 登记簿（绝对路径 + uri），**不复制**模型文件。 */
    const val EXTERNAL = "external_models"
}
