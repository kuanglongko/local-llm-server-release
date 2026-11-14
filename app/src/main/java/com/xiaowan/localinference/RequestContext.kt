package com.xiaowan.localinference

/**
 * 一次生成请求里「**该用哪个 chat 模板**」这件事的**纯逻辑**收敛点。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须收敛成一处（它已经造成过一次真实故障）
 * ══════════════════════════════════════════════════════════════════════════
 * 同一个请求里，prompt 的渲染与 GBNF 的推导发生在**两个不同的时间点**：
 *
 *   ① 渲染 prompt（`applyChatTemplate` / `applyChatTemplateWithTools`）：
 *      用 [chatTemplateOf] 给的模板渲染出这一轮真正的 prompt，并以生成后缀结尾；
 *   ② 推导 grammar（`llama_jni.cpp` 的 `gbnf_from_json_schema`）：
 *      为了拿到 schema 对应的 GBNF，要再问库一次 `common_chat_templates_apply`。
 *
 * ② 这一跳**不需要整轮对话**（grammar 只与 schema 有关、与内容无关），但**必须回答一个
 * 问题：用哪个模板**。原先它写死传空串（"让库按模型自选"），于是与 ① 分叉：
 *
 *   · ① 用的是**运行时**模板（App 通过 `llama_model_chat_template` 取到的原文）；
 *   · ② 用的是**库内**那份模板 —— 与运行时模板可能不是同一份。
 *
 * 两个后果同时出现（这台服务在手机上，模型是用户自己导入的，而 gbnf 的推导路径
 * 恰好还会被 template 的 `json_schema` 分支改写）：
 *   · 产出 GBNF 但形状按别的模板算 —— 约束错形状；
 *   · 或者压根产不出 GBNF（"已降级为无约束采样"）—— schema 看着像生效了、其实没有。
 *   两者都**不报错**，属于本项目反复踩的那类"哑得不响"。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 另一个必须一起带下去的东西：生成后缀
 * ══════════════════════════════════════════════════════════════════════════
 * ② 那一跳还要回答「这一轮的 prompt 是否以生成后缀结尾」。答案**只有渲染侧知道**：
 * 模板里 `add_generation_prompt` 分支在渲染时可能是「裸的 `<|im_start|>assistant\n`」，
 * 也可能是「带着思考块」的一整段（取决于模板与 `enable_thinking`），而它由**渲染时的
 * messages + enable_thinking** 决定 —— 用一个 `true` 常量去近似它，就是拿一个与
 * 真实 prompt 不同的尾巴去问库。
 *
 * 为什么这一条会变成用户可见的 bug：库在推导 GBNF 时会把生成后缀当作"**已经咽下去的**"
 * 前缀，只对"前缀之后的部分"施加约束；而实际解码时 prompt 已经在 KV 里、模型接在
 * 它后面续写。前缀对不上时，模型会**先把那个尾巴自己吐一遍**再去满足 grammar ——
 * 表现就是 `content` 以 `<|im_start|>assistant\n` 开头（或直接吐一个空数组）。
 * 真机数据见 [genPromptArg] 的注释。
 *
 * 所以这里的两件事**必须由调用方一起传下去**，且只在这一处推导：
 * 模板原文 + 这一轮渲染出的生成后缀。
 */
object RequestContext {

    /**
     * 这一轮该用哪个 chat 模板原文。**运行时模板优先**，取不到时用空串
     * （= 让库按模型自选，即引入本特性之前的行为）。
     *
     * 为什么不直接写 `chatTemplate().ifEmpty { null }`：Kotlin 侧的取值来源已经
     * 有两个（`LlmEngine.chatTemplate()` 与空串回落），把归一化放在这里，
     * 渲染侧与 GBNF 侧共用同一个函数，才不会出现"一处判空、一处没判"。
     */
    fun chatTemplateOf(runtimeTemplate: String?): String = runtimeTemplate ?: ""

    /**
     * 把「这一轮渲染结果 + addAss」折成给 native 的生成后缀入参。
     *
     * · `addAss == false`（渲染时没要生成后缀）-> `""`：**空串不是 null**。
     *   空串 = "确实是空的"，让库按 `add_generation_prompt=false` 推导 grammar；
     *   null = "不知道"，让库退回旧口径（见 native 侧 `gbnf_from_json_schema`）。
     *   两者混用会让"不要生成后缀"的请求被当成"未知"而按"有生成后缀"约束。
     * · 渲染失败（[RenderedPrompt] 为 null，native 已回落宿主拼的 ChatML）时，
     *   仍然把**宿主实际用的那段后缀**送下去 —— 取不到就返回 null（退回旧口径），
     *   不猜。
     */
    fun genPromptArg(rendered: RenderedPrompt?, addAss: Boolean): String? {
        if (!addAss) return ""
        return rendered?.generationSuffix
    }

    /**
     * 宿主**回落 ChatML** 时用的生成后缀（`LlmEngine.applyChatTemplate` 的最后一行）。
     *
     * 常量放这里而不是散在调用点：它必须与 [LlmEngine.applyChatTemplate] 的回落拼法
     * **逐字一致**，而那里已经是第三处拼 ChatML 的地方（C++ 侧回落、Kotlin 侧回落、
     * 解析侧取模板）。给 grammar 传错尾巴的后果与"没传"不同（后者的表现是
     * content 前面多一段模型自己吐的生成标记），所以只在这一处写死，并由守卫断言
     * 它与 `LlmEngine` 里的字面量相同。
     */
    const val CHATML_GEN_SUFFIX = "<|im_start|>assistant\n"
}
