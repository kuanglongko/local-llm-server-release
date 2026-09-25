#!/bin/sh
# 「结构化输出（response_format）不得破坏既有行为、不得让请求失败」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是只靠 JsonSchemaFormat 的单测）
# ═══════════════════════════════════════════════════════════════════════════
# 请求侧解析的单测（run_json_schema_tests.sh）钉住了"解析得对不对"，但钉不住
# **接线**：这个特性能不能成立，取决于 llama_jni.cpp / LlmEngine.kt / HttpApi.kt
# 里六件事同时做到 —— 每一件漏掉的后果都是**不崩、不报错、HTTP 200**：
#
#   ① schema 为 null 时**不得**挂任何 grammar 采样器（否则所有既有客户端的
#      输出分布被悄悄改掉，这正是"新增可选功能"最该防的一类回归）；
#   ② grammar 采样器必须排在**所有选择器之前**（放在 dist 之后等于不生效：
#      grammar 靠把候选 logit 置 -inf 来约束，而此刻结果已经被选出来了）；
#   ③ 取 GBNF 与编译 GBNF 的每一跳都必须就地 catch —— 库对不支持的 schema
#      是**抛异常**（可见 "Unrecognized schema: " / "failed to parse grammar"），
#      而异常穿过 JNI 帧是 UB -> SIGABRT，是本项目历史上"带 tools 必闪退"的同款成因；
#   ④ 转换失败必须**降级成无约束采样**，绝不让请求失败（"schema 写得不完美"不等于
#      "服务不可用"，且这台服务在手机上、没有第二个副本）；
#   ⑤ Kotlin 侧的 native 形参个数必须与 C++ 一致 —— 不一致 = 运行时
#      UnsatisfiedLinkError，编译期（两边各自都合法）与离线单测都发现不了；
#   ⑥ 三态映射必须唯一来源（None->null / JsonObject->"" / JsonSchema->原文），
#      在 HttpApi 里另判一次必然与 JsonSchemaFormat 漂移；
#   ⑦ **grammar 必须与真实 prompt 对齐**：模板取自渲染侧同一份，生成后缀由渲染侧给出，
#      且「未知（null）」与「确认没有（空串）」不能互换 —— 写死任何一个都是真机故障
#      （content 里多一段 `<|im_start|>assistant\n`、或数组 schema 只剩 `[ ]`）。
#
# 全是静态断言，不依赖任何工具链。运行：sh tools/run_schema_sampler_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
PURE=app/src/main/java/com/xiaowan/localinference/JsonSchemaFormat.kt
SRC=app/src/main/java/com/xiaowan/localinference
CI=.cnb.yml

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在"        "[ -f \$JNI ]"
c "JsonSchemaFormat.kt 是独立判据文件（可被宿主单测）" "[ -f \$PURE ]"

# ── 1) 判据本体：三态映射只在一个地方给出 ──────────────────────────────
c "schemaArg 是唯一的映射点（None->null / JsonObject->空串 / JsonSchema->原文）" \
  "grep -q 'fun schemaArg' \$PURE && grep -q 'ResponseFormat.None -> null' \$PURE && \
   grep -q 'ResponseFormat.JsonObject -> \"\"' \$PURE"
c "Kotlin newSampler 经由 schemaArg 取参（不在调用点另判一次）" \
  "grep -q 'JsonSchemaFormat.schemaArg(responseFormat)' \$KT"
c "HttpApi 不自行折叠 response_format（判据只在 JsonSchemaFormat）" \
  "! grep -q 'schemaArg(' \$HTTP"

# ── 2) ① 默认不变：schema 为 null 时不得挂采样器 ────────────────────────
# 判据锚定在调用点：必须**先判 gbnf 非空**才挂。写成无条件 attach 就等于
# "没给 schema 也加一个空 grammar 采样器"，那会改变所有既有请求的输出分布。
c "调用点先判 gbnf 非空才挂采样器（空 / 失败一律不挂）" \
  "grep -q 'if (!sg.gbnf.empty())$' \$JNI && \
   grep -q 'attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,' \$JNI && \
   grep -q 'sg.pegLiteral.c_str());' \$JNI"
c "attach_grammar_sampler 自身也拒绝空 GBNF（双保险）" \
  "grep -q 'if (!chain || !gbnf || !\*gbnf) return false;' \$JNI"
c "gbnf_from_json_schema 只在给了 schema 时被调用" \
  "grep -q 'jschema ? env->GetStringUTFChars(jschema, nullptr) : nullptr' \$JNI"

# ── 3) ② grammar 必须排在所有选择器之前 ────────────────────────────────
# 判据：attach 的调用行号 < 第一个 llama_sampler_init_penalties / top_k / top_p /
# min_p / temp / dist 的行号。后者的顺序本身另有单测（run_sampling_tests.sh）钉着，
# 这里只管"新加的 grammar 在所有它们之前"。
ATTACH=$(grep -n 'attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,' "$JNI" | head -1 | cut -d: -f1)
FIRST_SEL=$(grep -n 'llama_sampler_init_penalties\|llama_sampler_init_top_k\|llama_sampler_init_top_p\|llama_sampler_init_min_p\|llama_sampler_init_temp\|llama_sampler_init_dist' "$JNI" | head -1 | cut -d: -f1)
c "找得到 grammar 挂载点与第一个选择器" "[ -n \"\$ATTACH\" ] && [ -n \"\$FIRST_SEL\" ]"
c "grammar 排在第一个选择器之前（否则等于不生效）" "[ \"\$ATTACH\" -lt \"\$FIRST_SEL\" ]"
c "grammar 也排在贪心分支之前（temp<=0 时同样受约束）" \
  "[ \"\$ATTACH\" -lt \$(grep -n 'llama_sampler_init_greedy()' \$JNI | head -1 | cut -d: -f1) ]"

# ── 4) ③④ 两跳各自 catch，且失败降级不失败请求 ─────────────────────────
# 断言必须**贴着那一跳**判，不能只 grep "函数里有没有 catch"：
# gbnf_from_json_schema 里有**两处** catch（templates_init 与 templates_apply），
# 只判存在性的话，把 templates_apply 那一处的 catch 删掉照样 PASS
# —— 这正是本自测的桩②抓出来的（第一版就是这么写的）。
# 判据：templates_apply 调用点之后 12 行内必须出现 std::exception 的 catch。
c "取 GBNF 的那一跳（templates_apply）就地 catch" \
  "awk '/cp = common_chat_templates_apply\\(tmpls.get\(\), in\\)/{f=1;n=0} f{print;n++} f&&n>12{exit}' \$JNI | grep -q 'catch (const std::exception'"
c "编译 GBNF 的那一跳（llama_sampler_init_grammar）就地 catch" \
  "awk '/gs = llama_sampler_init_grammar\\(/{f=1;n=0} f{print;n++} f&&n>12{exit}' \$JNI | grep -q 'catch (const std::exception'"
c "两跳都有 catch-all（未知异常也不外抛）" \
  "awk '/cp = common_chat_templates_apply\\(tmpls.get\(\), in\\)/{f=1;n=0} f{print;n++} f&&n>12{exit}' \$JNI | grep -q 'catch (...)' && \
   awk '/gs = llama_sampler_init_grammar\\(/{f=1;n=0} f{print;n++} f&&n>12{exit}' \$JNI | grep -q 'catch (...)'"
# 声明必须先于使用。这两个函数是 `static`（内部链接），定义在文件后半，
# 而 nativeNewSampler 在它们之前调用 —— 缺前置声明时 C++ 是**硬编译错误**
# （`was not declared in this scope`）。
#
# 为什么非要有这一条：本轮真机之前，main 上就是这么坏的 ——
# PR 把两个函数插在 nativeNewSampler **之后**、又没加前置声明，本地
# `scripts/kt_check.sh` 只查 Kotlin、41 个离线套件没有一条会编这个 .cpp
# （g++ 那几份编的是独立小文件，不 include llama_jni.cpp），
# 于是"全部测试全绿、CI 编 APK 才炸"。这类错误的唯一现场就是 native 编译，
# 而它恰恰是离线套件覆盖不到的那一格 —— 所以在这里补一条纯静态判据。
# 三条判据：存在 / 顺序 / 与定义逐字一致。**签名两处都写全**（含续行的形参）——
# 只判函数名的话，"声明旧签名、定义新签名"这种漂移照样绿，而那正是硬编译错误。
PREDECL_GBNF='static schema_grammar_result gbnf_from_json_schema(const char \* schemaJson, const char \* tmplOverride,'
c "两个辅助函数都有前置声明（否则 nativeNewSampler 调用点编不过）" \
  "grep -q '^static bool attach_grammar_sampler(llama_sampler \* chain, const char \* gbnf, const char \* prefill,$' \$JNI && \
   grep -q \"^\$PREDECL_GBNF\" \$JNI"
c "声明出现在调用点之前（声明-定义-调用三者的行号关系）" \
  "[ \$(grep -n '^static bool attach_grammar_sampler(llama_sampler \* chain, const char \* gbnf, const char \* prefill,$' \$JNI | head -1 | cut -d: -f1) -lt \$(grep -n 'attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,' \$JNI | head -1 | cut -d: -f1) ] && \
   [ \$(grep -n \"^\$PREDECL_GBNF\" \$JNI | head -1 | cut -d: -f1) -lt \$(grep -n 'gbnf_from_json_schema(sc, tm, gp, jthinkingOn == JNI_TRUE)' \$JNI | head -1 | cut -d: -f1) ]"
# 判据要数**以 `static` 开头的整行**（声明与定义都长这样），而不是数"文件里出现过 prefill,"
# —— 后者在"声明被删、只剩定义"时照样为真（本条是桩⑩ 抓出来的第三处弱断言）。
# 同一行里数两条：`attach_grammar_sampler(... prefill,` 与
# `gbnf_from_json_schema(... tmplOverride,`，都必须出现 ≥2 次（声明 + 定义）。
c "前置声明与定义逐字一致（防声明一个、定义另一个）" \
  "[ \$(grep -c '^static bool attach_grammar_sampler(llama_sampler .* prefill,$' \$JNI) -ge 2 ] && \
   [ \$(grep -c '^static schema_grammar_result gbnf_from_json_schema(const char .* tmplOverride,$' \$JNI) -eq 2 ]"
c "两个函数都不含 return JNI_FALSE / 抛异常（缓存与 schema 问题都不得让请求失败）" \
  "! awk '/^static bool attach_grammar_sampler/,/^}/' \$JNI | grep -q 'throw' && \
   ! awk '/^static schema_grammar_result gbnf_from_json_schema/,/^}/' \$JNI | grep -q 'throw'"
c "空 GBNF 明确降级并留痕（不静默：探针 + App 日志都要有）" \
  "grep -q '模板未产出 grammar' \$JNI && grep -q '模板未产出 GBNF，已降级为无约束采样' \$JNI"
c "grammar 采样器为 NULL（库返回 NULL 表示 GBNF 解析失败）时降级并留痕" \
  "grep -q 'grammar 采样器为 NULL' \$JNI"
# 落 App 日志（jlog）而不是只进探针：调用方要看得到"为什么没约束上"。
c "降级一律同时落 jlog（用户在 App 日志里能看到原因）" \
  "[ \$(awk '/^static schema_grammar_result gbnf_from_json_schema/,/^}/' \$JNI | grep -c 'jlog') -ge 3 ]"

# ── 5) 不自己实现 schema->GBNF（那条路在本 vendor 下不成立） ─────────────
# 判据：不得直接 include vendor 的 schema 转换声明、不得自己声明它。
# 理由见 llama_jni.cpp 顶部那段"能力事实"：该符号在预编译库里是死代码，
# 自己声明它 = 赌 ABI 布局（chat_abi.h 存在的意义就是防这个）。
c "不自行声明 json_schema_to_grammar（该符号在本 vendor 下是死代码）" \
  "! grep -qE 'json_schema_to_grammar[[:space:]]*\\(' \$JNI"
c "能力事实写进了代码注释（防后人再往错方向查）" \
  "grep -q '全库\*\*没有任何一处调用它\*\*' \$JNI"
c "GBNF 由库的模板引擎产出（cp.grammar）" \
  "grep -q 'cp.grammar.empty()' \$JNI && grep -q 'out.gbnf = cp.grammar;' \$JNI"

# ── 5b) ⑦ grammar 与真实 prompt 对齐（模板同源 + 生成后缀由渲染侧给出） ──
# 这一节防的是**真机已经出现过**的故障：GBNF 约束的位置与模型实际续写的位置不是同一处。
# 表现（MiniCPM5-2B-Q4_K_M + json_schema）：
#   content = ":assistant\n{...}"          ← 模型自己吐了生成后缀，客户端丢掉一段
#   content = "<|im_start|>assistant\n[ ]"  ← 完整形态；token 花在尾巴上，数组只剩 [ ]
c "模板由调用方透传（不写死空串：库内自选那份可能与运行时模板不是同一份）" \
  "grep -q 'templates_init(S.model, tmpl)' \$JNI"
c "生成后缀由调用方透传（不是写死的常量 true）" \
  "grep -q 'in.add_generation_prompt = (\*genPrompt' \$JNI"
c "『未知』与『确认没有』分开判（判指针非空，不是判串非空）" \
  "grep -q 'const bool haveGenPrompt = (genPrompt != nullptr);' \$JNI"
c "对齐相关的取值在日志里可复核（模板长度 + add_generation_prompt + 来源）" \
  "grep -q 'tmpl=%zuB add_gen_prompt=%d' \$JNI"
c "handleChat 把渲染侧那份模板传下去" \
  "grep -q 'chatTemplateOverride = RequestContext.chatTemplateOf(chatTemplate)' \$HTTP"
c "handleChat 把渲染侧的生成后缀传下去（渲染失败时用 ChatML 回落那段）" \
  "grep -q 'RequestContext.CHATML_GEN_SUFFIX' \$HTTP"
c "/v1/completions 明确声明『没有生成后缀』（裸补全）" \
  "grep -q 'ResponseFormat.GenerationPrompt.EMPTY' \$HTTP"
# 数组 schema 曾经被 Kotlin 侧硬编码判 400 —— 真机证明库能接受，那条判据已删。
c "不再硬编码『schema.type 是数组 -> 400』（能力边界交给 native catch + 降级）" \
  "! grep -n 'private fun hasArrayType' \$PURE && ! grep -n 'val errUnsupportedKeyword' \$PURE"
c "生成后缀的映射只有一处（JsonSchemaFormat.genPromptArg），空串非空分列一支" \
  "grep -q 'fun genPromptArg' \$PURE && grep -q 'GenerationPrompt.EMPTY -> \"\"' \$PURE"
c "「调用方给的渲染结果 -> 后缀入参」的折叠也只有一处（RequestContext.genPromptArg）" \
  "grep -q 'fun genPromptArg' \$SRC/RequestContext.kt && \
   grep -q 'fun chatTemplateOf' \$SRC/RequestContext.kt"
c "HttpApi 两个端点都经由这两处取参（不自行折叠）" \
  "grep -q 'RequestContext.genPromptArg' \$HTTP && grep -q 'JsonSchemaFormat.genPromptArg' \$HTTP"

# ── 5c) 生成后缀必须**真的从渲染侧传回来**（0.9.87 就断在这一跳） ────────
# 这一节防的是一次**已经发生过的**故障，且形态很隐蔽：native 新参数加了、Kotlin 新参数也加了、
# 日志里 `genPrompt=` 那个字段也打印了（值恒为"无"）—— 每一处单看都"接上了"，
# 只有把两端连起来看才发现后缀从来没被送回来。
#
# 真因：渲染出口 `new_rendered_prompt` 只回传 prompt + 一个 `I`/`O` 字节，
# 没有把 `cp.generation_prompt` 一起交回；于是 `RenderedPrompt.generationSuffix`
# 恒为 null，`RequestContext.genPromptArg` 只能给 null，native 退回旧口径。
#
# 判据必须是**接线**（谁把谁交给谁），不是"某个字段存在"：后者在"加了字段没接线"时照样绿。
c "渲染出口把生成后缀一起交回（两个调用点都要，缺了它 grammar 会与真 prompt 分叉）" \
  "[ \$(grep -c 'return new_rendered_prompt(env, cp.prompt, cp.generation_prompt);' \$JNI) -eq 2 ]"
c "渲染出口的签名含生成后缀参数（不是只回传 prompt）" \
  "grep -q 'static jstring new_rendered_prompt(JNIEnv \* env, const std::string & prompt,$' \$JNI && \
   grep -q 'const std::string & genSuffix)' \$JNI"
c "宿主 parse 会读取后缀（不再是只剥一个字节）" \
  "grep -q 'generationSuffix = suffix' \$SRC/RenderedPrompt.kt"
c "宿主回落路径也带上生成后缀（渲染失败时不能退回 null）" \
  "grep -q 'generationSuffix = if (addAss) RequestContext.CHATML_GEN_SUFFIX else \"\"' \$KT"
# 回传格式的两端常量必须一致 —— 分叉不会报错，只会让后缀被切错位置。
c "回传格式的长度段宽度两端一致（C++ kRenderedPromptSuffixHexLen == Kotlin SUFFIX_HEX_LEN == 8）" \
  "grep -q 'kRenderedPromptSuffixHexLen = 8' \$JNI && \
   grep -q 'SUFFIX_HEX_LEN = 8' \$SRC/RenderedPrompt.kt"
c "长度段按固定宽度十六进制拼（不是分隔符：后缀自己就含换行）" \
  "grep -q 'snprintf(hex, sizeof(hex), \"%08zx\", genSuffix.size())' \$JNI && \
   grep -q 'padStart' \$SRC/RenderedPrompt.kt || grep -q \"hex.toIntOrNull(16)\" \$SRC/RenderedPrompt.kt"
# 长度单位：必须是 UTF-16 code unit（宿主 substring 的口径），不是 UTF-8 字节。
# ASCII 后缀下两者相等，所以单位错了在常见模型上一点症状都没有 ——
# 换一个含汉字 / emoji 的生成后缀才会错位。这类"只在部分模型上错"的静默故障
# 必须由判据钉住，不能等真机。
c "长度单位是 UTF-16 code unit（不是 genSuffix.size() 字节数）" \
  "grep -q 'snprintf(hex, sizeof(hex), \"%08zx\", utf16_len(genSuffix))' \$JNI && \
   ! grep -q 'snprintf(hex, sizeof(hex), \"%08zx\", genSuffix.size())' \$JNI"
# ⚠ 模块 F 那一轮：`utf16_len` 不再自己解码（自己数的那份与发出侧分叉 ——
# overlong `C0 80` 计数 1、发出 2，宿主侧 50 万例随机字节 35.7 万处不一致）。
# 现在它只是 `utf8_safe.h` 的 `utf8_decode_each` 的一个"只计数"调用点，
# 口径判据变成"代理对仍然记 2"—— 这一点**判据意图不变**，锚点跟着实现走。
c "utf16_len 的实现按 UTF-16 口径（BMP 记 1、代理对记 2）" \
  "grep -q 'static size_t utf16_len' \$JNI && \
   awk '/^static size_t utf16_len/,/^}/' \$JNI | grep -qE 'n \+= [^;]*0x10000u[^;]*\? 1 : 2|cp < 0x10000u'"
c "utf16_len 的解码走共享实现（不再自带第二份解码循环）" \
  "awk '/^static size_t utf16_len/,/^}/' \$JNI | grep -q 'utf8_decode_each'"
c "宿主侧也有多字节 / 代理对用例（ASCII 用例区分不出单位错）" \
  "grep -q 'UTF-16 code unit' tools/json_schema/RenderedPromptWireTest.kt && \
   grep -q '代理对后缀往返不丢' tools/json_schema/RenderedPromptWireTest.kt"
c "回传格式有往返单测（native 编码 -> 宿主 parse，三件事一个不丢）" \
  "[ -f tools/json_schema/RenderedPromptWireTest.kt ] && \
   grep -q 'RenderedPromptWireTestKt' tools/run_json_schema_tests.sh"

# ── 5d) grammar 采样器必须**预填**生成前缀（0.9.89 修的第五处成因） ──────
# 前四轮修的都是"传给库的生成后缀对不对"，而真机始终没修净 —— 因为漏的是另一件事：
# 库推出来的 GBNF **以生成前缀的字面量开头**（MiniCPM5 硬编码
# `p.literal("<|im_start|>assistant\n")`，见上游 chat.cpp 的 minicpm5 分支），
# 而那段字面量**已经在 prompt 里**了。采样器不预填时 grammar 从根节点起步，
# 要求第一个生成 token 就是 `<|im_start|>` —— 模型被迫把模板写好的尾巴再吐一遍，
# JSON 才轮到。真机表现：content 前面多一段 / 数组 schema 只剩 `[ ]`。
#
# 参考实现（上游 llama.cpp common/sampling.cpp）逐字：
#     // Feed generation prompt tokens to the grammar sampler so it advances past
#     // tokens the template already placed in the prompt.
#     if (grmr && !params.grammar_lazy && common_grammar_needs_prefill(params.grammar)) {
#         for (const auto & token : prefill_tokens) llama_sampler_accept(grmr, token);
#     }
#
# 判据必须是**接线断言**（谁对谁调了什么），不能是"存在某个字段"：
# 0.9.87 就是"两端参数都加了、日志字段也打了、值恒为无" —— 存在性断言照样全绿。
c "grammar 采样器在挂链前被 accept 预填（llama_sampler_accept(gs, ...)）" \
  "grep -q 'llama_sampler_accept(gs, toks\[i\]);' \$JNI"
c "预填文本由 gbnf_from_json_schema 一起产出（出了那个函数就只剩 GBNF 文本，取不到字面量）" \
  "grep -q 'out.prefill = grammar_prefill_from_literal(out.pegLiteral, renderedSuffix);' \$JNI"
c "字面量从 cp.parser（PEG 现场）取，不写死任何模型名" \
  "grep -q 'schema_peg_leading_literal(cp.parser' \$JNI && \
   ! grep -qE 'root_literal[[:space:]]*=[[:space:]]*\"<\\|im_start' \$JNI"
# 判据必须是"**真的往下钻了**"（序列分支里递归取子节点），不能只判"代码里出现过
# sequence 这个词" —— 后者在"分支里直接 return 空"时照样绿（本轮桩⑲ 就是这么抓出来的：
# 第一版判据只 grep 了类型名，桩把递归换成 `return "";` 之后守卫仍然全绿）。
# 判据必须贴住**序列分支内部**的递归：这一节第一版只判"函数里出现过 sequence 类型名
# 或递归调用"，而 tag / atomic / choice 等别的分支里也有同样的递归一行 —— 桩⑲ 把序列
# 分支换成 `return "";` 之后守卫照样全绿。现在用 awk 只截序列分支那几行再判。
c "首字面量往下钻序列 / 包装节点（根常是 sequence，只看根会取不到 -> 静默不预填）" \
  "grep -q 'static std::string peg_leading_literal(const common_peg_arena & arena,' \$JNI && \
   awk '/is_same_v<T, common_peg_sequence_parser>/{f=1} f{print}' \$JNI | sed -n '1,6p' | grep -q 'peg_leading_literal(arena, child, depth + 1)'"
# 上限必须是 **grammar 自己的首字面量**（peg_lit），不是库算的生成后缀。
# 真机 abort（probe-20260921-172241.txt）：库给出 gen_prompt=41B（含思考块）而
# GBNF 根首字面量只有 22B —— 按 41B 喂到 `<think>` 时 grammar 无栈可推 -> 空栈 abort。
# 判据必须钉住"取交的两个来源里**有 pegLiteral**"，只判"调了某函数"挡不住这次回归。
c "预填量上限取 grammar 首字面量（pegLiteral ∩ 真后缀），不取库算生成后缀" \
  "awk '/out.pegLiteral = schema_peg_leading_literal/{f=1} f{print}' \$JNI | sed -n '1,3p' | grep -q 'out.prefill = grammar_prefill_from_literal(out.pegLiteral, renderedSuffix);' && \
   ! grep -q 'out.prefill = grammar_prefill_from_suffixes' \$JNI"
c "预填函数（两种口径）都进了抽取单元的 include（probe_util.h）" \
  "grep -q 'static inline std::string grammar_prefill_from_literal' app/src/main/cpp/probe_util.h"
# 判据要贴住**预填失败那两个 catch**（0.9.91 修正后的正确语义）：
# 预填中途抛异常 = grammar 状态机已被打破，**必须就地 free + return false**
# （丢采样器、整链退化成无约束），绝不能把这个采样器挂进链 ——
# 挂上去下一次 sample 就在空栈断言上 lm_ggml_abort，**整个进程闪退**。
# 所以这里判的是"catch 里有 llama_sampler_free(gs) + return false"，
# 并且**不得**出现 `llama_sampler_chain_add`（挂了就是崩）。
c "预填失败时丢弃 grammar 采样器（free + return false），不把坏状态挂进链" \
  "awk '/已丢弃 grammar|已丢弃 grammar 采样器|grammar 预填失败/{f=1} f{print;n++} f&&n>6{exit}' \$JNI | grep -q 'llama_sampler_free(gs)' && \
   awk '/已丢弃 grammar|已丢弃 grammar 采样器|grammar 预填失败/{f=1} f{print;n++} f&&n>6{exit}' \$JNI | grep -q 'return false' && \
   ! awk '/已丢弃 grammar|已丢弃 grammar 采样器|grammar 预填失败/{f=1} f{print;n++} f&&n>6{exit}' \$JNI | grep -qE 'return (JNI_FALSE)|throw'"
c "预填失败不失败请求（丢的只是 grammar 约束，HTTP 仍正常返回自由文本）" \
  "grep -q '已丢弃 grammar（退回无约束采样）' \$JNI"
c "预填结果进了判据日志（prefill= 字节数 + peg_lit= + rendered_suffix=：缺一个都查不出短喂）" \
  "grep -q 'prefill=%zuB peg_lit=%zuB' \$JNI && \
   grep -q 'rendered_suffix=%zuB' \$JNI && \
   grep -q 'think=%d' \$JNI"
c "预填文本是真实后缀的前缀（离线单测钉住：grammar 只能越过已写好的部分）" \
  "grep -q 'grammar_prefill_from_literal' tools/root_literal_probe_test.cpp && \
   grep -q '预填文本一定是真实后缀的前缀' tools/root_literal_probe_test.cpp"
c "预填相关函数进了抽取单元语法检查（改坏了宿主侧就红）" \
  "grep -q \"grab('static std::string schema_peg_leading_literal(const std::string & parserSerialized,')\" tools/run_jni_schema_syntax.sh && \
   grep -q \"grab('static std::string peg_leading_literal(const common_peg_arena & arena,')\" tools/run_jni_schema_syntax.sh"
c "预填函数进了抽取单元的 include（probe_util.h）" \
  "grep -q 'include \"probe_util.h\"' tools/run_jni_schema_syntax.sh && \
   grep -q 'static inline std::string grammar_prefill_from_literal' app/src/main/cpp/probe_util.h"

# ── 5e) 预填按 token 字节边界收敛（0.9.91 加的防线）────────────────────
# 这一节守的是**另一类**输入：预填文本不是 token 边界的整数倍时，跨过预填结尾的
# 那个 token 会被整段 accept，多喂的字节同样能把 grammar 推成空栈 → lm_ggml_abort。
# ⚠ 它不是 0.9.90/0.9.91 真机 abort 的成因（那次切点整除、收敛一次都没触发；
#   真因是 5d 的 prefill 上限取了 41B 的库后缀）。两条各管一类，别互相替代：
#   · 5d 管"喂的**总量**超过 grammar 能咽下的"（上限取 peg_lit）；
#   · 5e 管"总量对、但**切点**落在 token 中间"（收敛到喂入口径的末尾）。
# 所以判据不能只判"accept 被调用了"（那只说明有预填），必须判**喂之前先按字节收敛**，
# 以及**判据日志里 fed=/prefill= 两个数字都在**（真机上分得开"算出来多少"与"实际喂了多少"）。
#
# ⚠ `0.9.99` 把收敛线从 **`pf.size()`（预填文本长度）** 换成了 **`plan.declaredBytes`
#   （grammar 自己声明的落点）** —— 真机 `0.9.98` 的读数（`gbnf=939B` 说明根字面量
#   确实改写成了 41B，而 `fed=41B/41B` 的分子**是算出来的**）暴露的就是这条线的位置问题：
#   grammar 是**字节级**、预填那一跳是**token 级**，一个 piece 里可能同时含"grammar
#   声明的字面量"与"模型还没开始写"的字节 —— 喂进去就多于声明量，grammar 被推过它
#   自己声明的落点；推过界**不抛异常**，代价是语法被悄悄写坏（`{`/`[` 全匹配不上
#   -> 全体候选置 -inf）。所以收敛线取 grammar 的声明量，而不是我们打算喂多少。
#   ⚠ 这两条**都**要判：只判"有收敛"时，"收敛线取错"照样绿 ——
#   而它在"声明量 != 预填长度"的输入上就是 0.9.98 那个故障本身。
c "预填按 token 字节边界收敛（跨过喂入口径末尾的 piece 不喂：整喂会让 grammar 空栈/写坏）" \
  "awk '/int n_prefill = 0;/,/llama_sampler_chain_add\(chain, gs\)/' \$JNI | grep -q 'if (next > boundary) break;' && \
   awk '/int n_prefill = 0;/,/llama_sampler_chain_add\(chain, gs\)/' \$JNI | grep -q 'for (size_t i = 0; i < i_first; i++)'"
# 收敛线必须取 **grammar 声明的落点**（plan.declaredBytes），不是预填文本长度。
c "收敛线取 grammar 声明的落点（plan.declaredBytes），不是 pf.size()" \
  "awk '/int n_prefill = 0;/,/llama_sampler_chain_add\(chain, gs\)/' \$JNI | grep -q 'const size_t boundary = plan.usable ? plan.declaredBytes : pf.size();'"
# 判据必须贴住**字节数**。写成 toks.size() / piece 个数 时，
# 含 CJK 或多字节 piece 的模板会整体错位 —— 而它在 41B 这种纯 ASCII 用例上
# 一点症状都没有（与桩⑰ 是同一类"常见用例区分不出的错"）。
c "收敛判据按**字节数**（piece.size() 累加），不是 token 个数（多字节 piece 会整体错位）" \
  "awk '/size_t i_first = toks.size\(\);/,/if \(i_first < toks.size\(\)\)/' \$JNI | grep -q 'next > boundary' && \
   awk '/size_t i_first = toks.size\(\);/,/if \(i_first < toks.size\(\)\)/' \$JNI | grep -q 'bytes + piece.size()'"
# 收敛只能**少喂**，绝不能因为收敛把预填整个丢掉（那就是退回"不预填"的老症状）。
c "收敛后至少还能喂一个 piece（收敛不得把预填整体丢掉 = 退回老症状）" \
  "awk '/size_t i_first = toks.size\(\);/,/if \(i_first < toks.size\(\)\)/' \$JNI | grep -q 'i_first = i + 1;' && \
   awk '/size_t i_first = toks.size\(\);/,/if \(i_first < toks.size\(\)\)/' \$JNI | grep -q 'if (next > boundary) break;'"
# 收敛这件事必须在日志里留痕：静默少喂一格 = 下一次仍然读不出来
c "收敛发生时留痕（真机上\"看着对齐却崩\"要能一眼看出被收敛过）" \
  "grep -q '预填收敛：%d 个 token 中只喂前 %d 个' \$JNI"
# 判据日志必须同时给出「算出的预填字节数」与「实际喂进去的字节数」：
# 真机那次 abort 的日志里 `prefill=41B` 与 `gen_prompt=41B` "看着相等"，于是被读成
# "对齐了、没问题"，而崩溃恰恰发生在 accept 那一跳。两个数字必须分开报。
c "判据日志同时给出 prefill= 与 fed=（缺一个就分不出\"算出来\"与\"真喂进去\"）" \
  "grep -q 'fed=%zuB/%zuB' \$JNI"
# ⚠ `fed=` 的分子必须是**实际累加的字节数**（fed_prefill_bytes），不是 prefillEff.size()。
#   0.9.98 真机就是被这一行带偏的：`fed=41B/41B` 在"只喂了 22B"时**照样是 41B/41B**，
#   因为它报的是"打算喂多少"。分子写错 = 故障被读成正常，而这条读数此前没有任何断言。
c "fed= 的分子是实际累加的字节数（写成 prefillEff.size() = 算出来的，故障会被读成正常）" \
  "grep -q 'fed_prefill_bytes, prefillEff.size());' \$JNI && \
   ! grep -q 'prefillEff.size(), prefillEff.size());' \$JNI"
c "账不平必须显形（实喂 != 声明量时留痕，否则与未对齐逐字同症状）" \
  "grep -q '预填字节账：账平（实喂 %zuB == grammar 声明的根字面量 %zuB）' \$JNI && \
   grep -q '预填字节账：账不平（实喂 %zuB != grammar 声明的根字面量 %zuB）' \$JNI"
# 预填口径的**纯函数**必须在 probe_util.h（真机与单测共用同一份），且有独立单测。
# 这一段的教训与 5i 同源：口径写在 llama_jni.cpp 里 = 宿主上编不了 = 只能靠 review
# 肉眼保证，而它一旦写错就是"不崩、不报错、只是约束失效"。
c "预填字节账的纯函数在 probe_util.h（真机与单测共用同一份，不写第二份实现）" \
  "grep -q 'static inline prefill_byte_plan plan_grammar_prefill_bytes(' app/src/main/cpp/probe_util.h"
c "真机这一跳走的就是那个纯函数（不另写一份判断）" \
  "grep -q 'plan_grammar_prefill_bytes(' \$JNI && \
   grep -q 'const prefill_byte_plan plan = plan_grammar_prefill_bytes(' \$JNI"
c "该纯函数进了单测（含\"短喂照喂\"与\"多喂被挡\"两条反向断言）" \
  "grep -q 'plan_grammar_prefill_bytes' tools/root_literal_probe_test.cpp && \
   grep -q '反向：预填\*\*长于\*\*声明量且不是其前缀' tools/root_literal_probe_test.cpp"
# 反向断言必须**同时**在两边：单测里判"多喂被挡"，守卫里也得判它还在 ——
# 只判"纯函数在"时，把那条反向断言删掉照样绿（而它正是本处的故障本身）。
c "单测里的'多喂被挡'反向断言被守卫钉住（删了要红）" \
  "grep -q '反向：预填\*\*长于\*\*声明量' tools/root_literal_probe_test.cpp"
c "该纯函数进了抽取单元的语法检查（改坏了宿主侧就红）" \
  "grep -q 'prefill_byte_plan plan_grammar_prefill_bytes' app/src/main/cpp/probe_util.h && \
   grep -q 'include \"probe_util.h\"' tools/run_jni_schema_syntax.sh"
c "收敛防线仍在（它管的是切点落在 token 中间的另一类输入，别被本轮改动顺手删掉）" \
  "grep -q 'if (next > boundary) break;' \$JNI"

# ── 5g) 「grammar 就位点」必须落日志（前八处成因里最缺的那一格）────────────
# 为什么必须有：前八处成因中有两次真机 abort、以及 Qwen3 的"代码块"故障，
# 都发生在**预填之后、下一个 token 的采样**那一步 —— 而那里此前一条日志都没有，
# 只能拿 `prefill=NB` 反推，于是 `prefill=22B peg_lit=22B` 被读成"对齐了、没问题"。
# 所以这一节的判据是"**采样那一步必须有可读的落点信息**"，
# 而不是"某个变量存在"。
c "就位点判据进了日志（prefill 之后 grammar 期望 / 真 prompt 后缀 / 是否一致）" \
  "grep -q 'grammar 就位点：prefill=%zuB 之后 grammar 期望续写 / 真 prompt 后缀=%zuB' \$JNI && \
   grep -q 'fit=%d' \$JNI"
c "错位段（mismatch）原文被打出来（只报字节数仍然读不出是它自己还是它的长度）" \
  "grep -q 'mismatch=%zuB(%s)' \$JNI && \
   grep -q 'escape_for_probe(fit.mismatch, 60)' \$JNI"
c "就位点算术来自 probe_util.h 的纯函数（真机与单测共用同一份，不写第二份实现）" \
  "grep -q 'grammar_fit_check(out.prefill, renderedSuffix)' \$JNI && \
   grep -q 'static inline grammar_fit_result grammar_fit_check' app/src/main/cpp/probe_util.h"
c "就位点纯函数进了离线单测（\`grammar_fit_check\` 有断言兜底）" \
  "grep -q 'grammar_fit_check' tools/root_literal_probe_test.cpp"
# ⚠ 这一条是**更正**，不是新增功能：库内 space 规则的定义是
#     const std::string SPACE_RULE = "| \" \" | \"\\n\"{1,2} [ \\t]{0,20}";
# 经 _rules["space"] = SPACE_RULE 产出 GBNF 的
#     space ::= | " " | "\n"{1,2} [ \t]{0,20}
# **第一个分支就是空** —— 它能匹配空串。此前多处注释/文档断言"space 不能匹配空串、
# 短喂让全体候选变 -inf"，据此推出的诊断已被证伪（HTP-STATUS 第二十七节 27.1）。
# 判据钉住"这份错误解释不得再被写回源码"，免得下一轮又照它推一遍。
# ⚠「更正不得被写回」这条判据**只能钉住实现，不能钉住注释**：
# 第一版写成 `! grep '全体候选被置 -inf' 源码`，而那几处**正是更正本身引用的原文**
# （引用旧解释来标注它已证伪）—— 判据于是必红。这类"禁止出现某词"的断言在
# "该词恰好出现在更正说明里"时恒假，属于本仓库反复记录的那类弱/错断言。
# 正确的形状是：**实现里不得按那个解释去写分支**（预填上限不得退回 41B 的库后缀），
# 以及**更正必须留在源码里**（下面那条 grep 钉住它，缺了也红）。
c "实现不得按'短喂会让约束失效'去补喂（预填上限不得退回库算的 41B 后缀）" \
  "! grep -q 'out.prefill = grammar_prefill_from_suffixes' \$JNI && \
   grep -q 'grammar_prefill_from_literal(out.pegLiteral, renderedSuffix)' \$JNI"
# 判据必须同时钉住**规则原文**与**结论**（"它能匹配空串"）：只钉规则原文时，
# 一处"引用旧解释来标注它已证伪"的注释就足以让判据绿 —— 而更正结论可能已被删。
c "更正结论写进了源码注释（space ::= 的空分支 + 它能匹配空串，缺一不算更正）" \
  "grep -q 'space ::= | \" \" | ' app/src/main/cpp/probe_util.h && \
   grep -q '它能匹配空串' app/src/main/cpp/probe_util.h"

# ── 5h) 错位段必须被 grammar 咽下去（第十处成因，`0.9.96` 修）──────────────
# 前面 5d/5e/5f/5g 全是"预填**越过了多少**"和"越过之后**看得见**"，
# 而没有一条管到**越过的落点与模型续写的位置是不是同一处** —— 这正是第十处成因：
#
#     预填把 grammar 推过 22B 的首字面量之后，grammar 就站在 JSON 的起点了（它在等 `{`），
#     而模型实际要写的下一个 token 是模板写死的 `<think>\n\n</think>\n\n`（19B）。
#     这个 token 会被 grammar 判成非法 -> 置 -inf -> 模型要么提前收尾、要么把那段思考块
#     硬吐出来（它正好是 grammar 的 space 分支能吃的），随后再补一个收尾用的生成前缀复述。
#     真机读数一直在指它（`fit=0 mismatch=19B(<think>\n\n</think>\n\n)`），
#     而 27.5 那张判据表把它写成"宿主侧无解" —— **那句是错的**。
#
# 修法：把错位段也喂给 grammar（逐字节试探、全成功才留下）。
# ⚠ 与 0.9.90 那次 SIGABRT 的区别必须判出来：那次是把 41B **交给 tokenizer 切**、
#   piece 整段 accept，末位跨界把 JSON 的头几个字节也喂了进去 -> 空栈 abort。
#   所以"补喂"这件事本身不是错的，错的形态是"按 token 整段硬喂"。判据必须钉住
#   **补喂那一段是逐字节试出来的**、且**只吃 grammar 点头的字节**。
# ⚠ 判据必须**分两条**：① C++ 侧**真的调了**它；② 纯函数本体在 probe_util.h。
# 写成一条 `grep 'advance_grammar_past_mismatch(' $JNI && grep ... probe_util.h` 时，
# 前半条可以**被注释里的函数名**满足（本处成因段就在注释里提了五次）——
# 桩㉟ 把调用点删掉之后判据照绿，正是这么抓出来的（第四处"桩/判据写歪"）。
c "错位段被喂给 grammar（修法本体：不止预填首字面量，还要咽下错位段）" \
  "grep -q 'const prefill_advance_result adv = advance_grammar_past_mismatch(' \$JNI && \
   grep -q 'static inline prefill_advance_result advance_grammar_past_mismatch' app/src/main/cpp/probe_util.h"
c "补喂的那一段取自真后缀的错位段（不是自己拼的、也不是整个 41B 库后缀）" \
  "grep -q 'grammar_fit_check(pf, realSuffix)' \$JNI && \
   grep -q 'fitForAdvance.mismatch' \$JNI"
# 判据贴住"那一字节是**从错位段里逐字节取出来**的"（`one(1, c)`），
# 而不是"文件里提到过 llama_tokenize(..., 1" —— 预填那一跳也有类似调用（第四处写歪的同款）。
c "补喂**逐字节**（不切 token、不整段 accept）：0.9.90 的 SIGABRT 形态就是整段硬喂" \
  "[ \$(grep -c 'const std::string one(1, c);' \$JNI) -ge 2 ] && \
   grep -q 'llama_tokenize(S.vocab, one.c_str(), 1' \$JNI"
c "grammar 不认的字节 -> 绝不喂进原件（试探走克隆体，全成功才逐字节重放到 gs）" \
  "grep -q 'trial = llama_sampler_clone(gs);' \$JNI && \
   grep -q 'if (!adv.accepted.empty()) {' \$JNI"
c "落点推进与未推进**都要落日志**（未推进时症状与旧版逐字相同，没这行分不出来）" \
  "grep -q '错位段已咽下：prefill %zuB -> %zuB' \$JNI && \
   grep -q '错位段未被 grammar 接受（%zuB），落点保持 %zuB\"' \$JNI"
c "推进的落点判据进单测（含'不作半推'与'不认就整段作废'两条反向断言）" \
  "grep -q 'advance_grammar_past_mismatch' tools/root_literal_probe_test.cpp && \
   grep -q '半推一截是不可接受的' tools/root_literal_probe_test.cpp"
c "纯函数进了抽取单元的语法检查（改坏了宿主侧就红）" \
  "grep -q 'prefill_advance_result advance_grammar_past_mismatch' app/src/main/cpp/probe_util.h && \
   grep -q 'include \"probe_util.h\"' tools/run_jni_schema_syntax.sh"

# ── 5i) 根字面量必须对齐到真后缀（第十一处成因，`0.9.97` 修）───────────
# 5h) 那一版想"把错位段喂给 grammar"。真机复测（21:43 三例）逐字：
#
#     [schema] 错误段未被 grammar 接受（19B），落点保持 22B（退回旧行为，不硬喂）
#     [schema] 已挂载 grammar 采样器：gbnf=1099B root=root prefill=3 tok(已预填) fed=22B/22B
#
# "未被接受"不是"没试"、也不是"重放中断"，是**第一个字节 `<` 就被 grammar 判非法**。
# 为什么非法：库按 `reasoning_format=NONE`（宿主从未赋值 —— 全仓库一处都没有）建 PEG 时
# **没有把模板写死在 prompt 里的思考块算进产生式**。真机那两份产物因此对"续写位置"
# 理解不同：`cp.generation_prompt`=41B（含 think 块）、`cp.grammar` 根字面量=22B。
# 那 19B 是 grammar 产生式里**不存在**的字节 —— 靠"喂"在构造上就不可能成立。
#
# 修法：**在 GBNF 文本上把根节点那个字面量由 22B 换成 41B**（改 grammar，不是喂 grammar）。
# 这一节的判据必须钉住四件事，缺一项都会静默退回旧症状：
c "根字面量对齐的纯函数在 probe_util.h（真机与单测共用同一份，不写第二份实现）" \
  "grep -q 'static inline std::string gbnf_realign_root_literal' app/src/main/cpp/probe_util.h"
c "对齐用真后缀改写根字面量（不是把错位段喂给 grammar）" \
  "grep -q 'gbnf_realign_root_literal(gbnf, pegLit, realSuffix)' \$JNI"
# 对齐生效时**必须**同时改预填量：grammar 声明 41B、只喂 22B = 又差回那 19B。
# 这两行必须**一起**判 —— 只判前者时"对齐了但没喂够"照样绿（那正是 0.9.96 的错配形态）。
c "对齐生效后预填跟着喂满真后缀（声明 41B / 喂 22B = 又差回 19B）" \
  "grep -q 'if (realigned) prefillEff = realSuffix;' \$JNI && \
   grep -q 'const std::string pf = prefillEff;' \$JNI"
# 对齐必须**同步**：GBNF 用改写后的、预填用真后缀，两者同一判据（realigned）驱动。
c "改写的 GBNF 与预填量由**同一个** realigned 判据驱动（不同步 = 声明与实喂错开）" \
  "grep -q 'const std::string gbnfFinal = realigned ? gbnfEffective : std::string(gbnf);' \$JNI && \
   grep -q 'gbnfFinal.c_str(), \"root\"' \$JNI && \
   grep -q 'bool realigned = gbnfEffective != gbnf;\|realigned = (gbnfEffective != gbnf)' \$JNI"
# 对齐**不得半推**：真后缀若不是整 token 边界，预填会被"只减不增"收敛截短，
# 落点又错开（这次错在 grammar 已声明的范围内，更难查）-> 一律放弃对齐。
c "真后缀不是整 token 边界时放弃对齐（不作半对齐）" \
  "grep -q 'exact = (bytes == realSuffix.size());' \$JNI && \
   grep -q '根字面量对齐放弃' \$JNI"
# 对齐是"新增可选行为"：不成立时必须逐字节退回旧实现，且不可改变已有输入。
c "对齐不成立时逐字节退回原 GBNF 与原预填量（不改已有行为）" \
  "grep -q 'std::string prefillEff = prefill ? std::string(prefill) : std::string();' \$JNI && \
   grep -q '根字面量对齐：peg_lit=%zuB -> 真后缀=%zuB' \$JNI"
# ⚠ 转义表必须与库内 `format_literal` **逐字一致（六个字符）**：只有一个地方能写出这份口径。
# 真机 0.9.98 用的 Qwen3 41B 后缀恰好只含换行，所以"少转义 `-` / `]`"这件事
# **在真机上看不出来** —— 但换一种模板（生成后缀里带 markdown 或 JSON 片段）就会：
# 要么 `find` 恒不中（对齐静默失效），要么写回一段**语法错**的 GBNF。
# 判据用 grep -F（固定串）避开嵌套引号：判据本身写错引号 = 守卫恒假，比没有更坏。
c "转义表含右方括号的转义分支（与库内 format_literal 的六个字符逐字一致）" \
  "grep -qF \"case ']':  out += \" app/src/main/cpp/probe_util.h"
c "转义表含短横的转义分支（字符类里它就是区间符号）" \
  "grep -qF \"case '-':  out += \" app/src/main/cpp/probe_util.h"
c "单测里有短横的转义断言（真机后缀只含换行，离线是唯一会红的地方）" \
  "grep -qF 'gbnf_escape_literal(\"a-b\") == \"a' tools/root_literal_probe_test.cpp"
c "单测里有右方括号的转义断言" \
  "grep -qF 'gbnf_escape_literal(\"a]b\") == \"a' tools/root_literal_probe_test.cpp"
c "对齐的纯函数进了单测（含分隔符那条反向断言）" \
  "grep -q 'gbnf_realign_root_literal' tools/root_literal_probe_test.cpp && \
   grep -q '分隔符口径钉在' tools/root_literal_probe_test.cpp"
c "对齐的纯函数进了抽取单元的语法检查（改坏了宿主侧就红）" \
  "grep -q 'std::string gbnf_realign_root_literal' app/src/main/cpp/probe_util.h && \
   grep -q 'gbnf_realign_root_literal' tools/run_jni_schema_syntax.sh || \
   grep -q 'include \"probe_util.h\"' tools/run_jni_schema_syntax.sh"
# `0.9.97` 那轮 `llama_jni.cpp` 真编不过（`strlen(gbnfFinal)`，而它是 `std::string`），
# 而整条流水线**一处都不会红** —— 因为覆盖它的 run_jni_schema_syntax.sh 是以
# `REQUIRED=0` 调的，缺 g++ 就 SKIP。判据：对齐相关的那一跳**不得**再走 REQUIRED=0。
c "JNI 语法检查在 CI 里是 REQUIRED=1（缺 g++ 拦下，不再 SKIP 掉真编译错）" \
  "grep -q 'REQUIRED=1 sh tools/run_jni_schema_syntax.sh' \$CI"
c "日志报的是 gbnfFinal.size()（写 strlen(gbnfFinal) = 编不过，且宿主侧拦得住）" \
  "grep -q 'gbnfFinal.size(), n_prefill' \$JNI && \
   ! grep -q 'strlen(gbnfFinal)' \$JNI"
# ⚠ `[mark]` 那行也必须报**gbnfFinal**（改写后、真正交给编译器的那份），不是原件 `gbnf`。
#   真机 0.9.98 同一轮里出现了 `gbnf=939B` 与 `GBNF 916B` 两个数 —— 差 23B 就是根字面量
#   对齐那一下；看日志的人会据此判成"对齐没生效"，而它其实生效了。
#   两个数字必须来自同一份产物，否则读数自己就在撒谎。
c "mark 行也报 gbnfFinal（报原件 gbnf = 同一轮两个 GBNF 数字，读日志的人会误判）" \
  "! grep -q 'strlen(gbnf), n_prefill' \$JNI"

# ── 6) ⑤ Kotlin / C++ 形参个数一致 ────────────────────────────────────
# 这一节的**核心教训**（本类故障的第六处成因）：
# 只判"C++ 有这三个尾参" + "Kotlin 声明里有 thinkingOn" 是**两条独立的弱断言**，
# 两者都真、而**两端根本对不上**（Kotlin 15 参 vs C++ 13 参）时守卫照样全绿 ——
# 真机上 JVM 按**短符号名**回退解析，多出来的实参被 ABI 忽略，
# 于是 C++ 永远收不到 thinkingOn、静默按默认值跑（正是本轮真根因）。
# 所以必须**把两端参数字面串放在一起比**，外加一条 C++ 调用点真的把实参传下去的断言。
c "C++ 入口接收 jschema / jgenPrompt / jtmpl / thinkingOn 四个尾参" \
  "grep -q 'jobjectArray jstops, jstring jschema, jstring jgenPrompt, jstring jtmpl,' \$JNI && \
   grep -q 'jboolean jthinkingOn) {' \$JNI"
c "Kotlin nativeNewSampler 声明的尾参逐字一致（含 thinkingOn）" \
  "awk '/fun nativeNewSampler\(/,/\): Boolean/' \$KT | grep -q 'stops: Array<String>?, schema: String?, genPrompt: String?, tmpl: String?, thinkingOn: Boolean): Boolean'"
# ⚠ 交叉断言（本轮新增，正是漏掉的那一格）：两端的**尾参个数**必须相等。
# Kotlin 侧数 "," 分隔的形参数，C++ 侧数 JNI 入口的形参数，两者不等 = 断链。
# Kotlin 侧：形参都有 `名字: 类型` 的形状（返回类型在 `)` 之后，用 `\)` 排掉）。
# C++ 侧：JNI 入口形参都有 `j*` 前缀类型（`JNIEnv *` / `jclass` 显式排除）。
c "Kotlin 与 C++ 的 nativeNewSampler 形参**个数相等**（不等 = 静默断链，本轮真根因）" \
  "[ \$(awk '/fun nativeNewSampler\(/,/\): Boolean/' \$KT | tr ',' '\n' | grep -cE '[A-Za-z]+: (Float|Int|Long|Boolean|String|Array<[A-Za-z]+>)\\??') -eq \
    \$(awk '/Java_com_xiaowan_localinference_LlmEngine_nativeNewSampler/,/jboolean jthinkingOn\) \{/' \$JNI | tr ',' '\n' | grep -cE '\\b(jfloat|jint|jlong|jboolean|jstring|jobjectArray)\\b') ]"
c "Kotlin 与 C++ 的 nativeNewSampler **末两个形参**顺序一致（tmpl 在 thinkingOn 之前）" \
  "awk '/fun nativeNewSampler\(/,/\): Boolean/' \$KT | grep -q 'tmpl: String?, thinkingOn: Boolean): Boolean' && \
   awk '/Java_com_xiaowan_localinference_LlmEngine_nativeNewSampler/,/jboolean jthinkingOn\) \{/' \$JNI | grep -q 'jstring jtmpl,' && \
   awk '/Java_com_xiaowan_localinference_LlmEngine_nativeNewSampler/,/jboolean jthinkingOn\) \{/' \$JNI | grep -q 'jboolean jthinkingOn) {'"
c "C++ 调用点把渲染侧那份思考开关真的传给了 gbnf_from_json_schema" \
  "grep -q 'gbnf_from_json_schema(sc, tm, gp, jthinkingOn == JNI_TRUE)' \$JNI"
c "gbnf_from_json_schema 把思考开关写进 templates_inputs（不透传 = 库按默认 true 重算 PEG）" \
  "awk '/static schema_grammar_result gbnf_from_json_schema\(const char \* schemaJson, const char \* tmplOverride,\$/,/^\}$/' \$JNI | grep -q 'in.enable_thinking = thinkingOn;'"
c "Kotlin newSampler 包装层把 responseFormat 透传下去" \
  "grep -q 'responseFormat: ResponseFormat = ResponseFormat.None' \$KT"
# ── 7) ⑥ 两个生成端点都接上了（漏一个 = 那个端点静默不生效） ────────────
c "两个端点各解析一次 response_format（计 2 处）" \
  "[ \$(grep -c 'JsonSchemaFormat.fromRequest(j)' \$HTTP) -eq 2 ]"
c "非法时两个端点都回 400（计 2 处）" \
  "[ \$(grep -c 'response_format 非法' \$HTTP) -eq 2 ]"
c "两个端点都把 respFormat 传进 newSampler（计 2 处）" \
  "[ \$(grep -c 'responseFormat = respFormat' \$HTTP) -eq 2 ]"
c "非 None 时落一行日志（可知这次请求要求了结构化输出）" \
  "grep -q 'JsonSchemaFormat.describe(respFormat)' \$HTTP"

# ── 8) 响应体探针：**唯一**能看见"库拿着 grammar 去采样之后吐了什么"的读数 ──
# 为什么必须有这一节：这条链修了八轮，前八轮的日志里只有 `chat ok: n tok` 一个计数，
# 响应体一个字都没落盘。于是每轮复测都只能靠"用户说还是有代码块"这个结论反推 ——
# 而 `[ ]`（空数组）与 `["a","b","c"]` 恰好都是 13 tok，**计数分不开它们**。
# 这不是"再加一条日志"，是补上**唯一缺失的那一环**：前八轮的判据全都长在
# "我们交给库的值"上，没有一条长在"库交回来的东西"上。
c "响应体探针是纯函数（判据文件里给出，不在 HttpApi 现拼）" \
  "grep -q 'fun probeBody' \$PURE"
c "两个生成端点都落这一行（漏一个 = 那个端点以后查不动）" \
  "[ \$(grep -c 'JsonSchemaFormat.probeBody(JsonSchemaFormat.reqTagOf(id), sb.toString())' \$HTTP) -eq 2 ]"
# 判"关联码真的接上了"，不判"函数签名里有这个形参"—— 本仓库栽在"判字段存在、不判接线"
# 上已经六次（见 §6 的交叉断言）。这三条是它们的后代：
#   ① 请求侧那行必须带 `[id=$reqTag]`（否则输出侧有 id、请求侧没有，还是配不上对）；
#   ② 两个端点的 `reqTag` 必须来自**同一个** `reqTagOf(id)`（不是各自拼串）；
#   ③ `reqTag` 必须在**生成之前**就定型（若挪到生成之后，[body] 拿到的会是空串）。
c "请求侧的 response_format 日志也带同一个关联码（只输出侧带 = 仍配不上对）" \
  "[ \$(grep -c 'emitLog(\"\[id=\$reqTag\] \" + JsonSchemaFormat.describe(respFormat))' \$HTTP) -eq 2 ]"
c "关联码只由 reqTagOf 一处产出（两端各拼一次 = 形态会漂）" \
  "[ \$(grep -c 'JsonSchemaFormat.reqTagOf(id)' \$HTTP) -ge 2 ] && grep -q 'fun reqTagOf' \$PURE"
# 顺序断言：id/reqTag 必须出现在 newSampler 之前（生成之后才定型 = [body] 拿不到码）。
RQ_FIRST=$(grep -n 'val reqTag = JsonSchemaFormat.reqTagOf(id)' "$HTTP" | head -1 | cut -d: -f1)
NS_FIRST=$(grep -n 'LlmEngine.newSampler(' "$HTTP" | head -1 | cut -d: -f1)
c "关联码在**生成之前**定型（挪到生成之后 = [body] 拿到空串）" \
  "[ -n \"\$RQ_FIRST\" ] && [ -n \"\$NS_FIRST\" ] && [ \"\$RQ_FIRST\" -lt \"\$NS_FIRST\" ]"
# 判据贴住**实际落进日志的那个值**，不判"出现了 array= 这个键"：
# 只出现键名而值写死（如 append(-1)）时，`[ ]` 与 3 元素照样分不开 ——
# 那正是这条探针存在的全部理由（n tok 区分不出它们）。这是本自测桩㉙ 抓出来的。
c "探针里的元素个数来自**解析结果**（写死值 = 又一个分不开的计数）" \
  "grep -q 'append(\" array=\").append(elems)' \$PURE && grep -q 'a.length()' \$PURE"
c "探针里有前 80 字符原文（围栏 / think 残段的第一嫌疑在这里）" \
  "grep -q 'const val PROBE_HEAD' \$PURE && grep -q 'head=' \$PURE"
# 转义那一条不能用 grep 字面量去匹配转义序列（引号层数一多必然写歪，而写歪之后
# 它要么恒真、要么恒假）。改成用 python 读源码文件判"存在把 '\\n' 映射成两个字符的
# append 语句"，与源码文本的引号层数无关 —— 与既有做法（用 awk/python 判结构）同源。
# ⚠ 这里**不能**写成 `VAR=$(cmd1 && cmd2 <<HEREDOC && cmd3 || cmd4)`。
#   POSIX 没规定命令替换里「heredoc 重定向 + 紧随其后的 `&&`」怎么收尾：
#   dash 能解析，**bash 报 `syntax error near unexpected token &&`** ——
#   而 `sh -n` / `bash -n` 两份都过（不是静态语法错），于是 CI 接线守卫的
#   判据⑥（`sh -n` 自证语法）抓不到它。后果：换 bash 跑时末尾四条断言
#   **一条都不打印**，守卫只报 `PASS 117`（长得完全不像失败），自己悄悄少跑四条。
#   实测（0.9.101 原样）：
#       sh   -> PASS 121 / FAIL 0   rc=0
#       bash -> PASS 117 / FAIL 0   rc=2 + stderr 里那句语法错
#   改法：把 heredoc 交给**独占一个命令**，`$( )` 里不再出现「heredoc + &&」。
#   反向判据在 `tools/run_ci_wiring_guard.sh` 判据⑦：
#   同一份静态守卫在 sh / bash 下必须**输出逐行相同**（两把尺子量同一个数）。
ESC_OK=no
if grep -q 'fun String.escapeProbe' "$PURE"; then
  if python3 - "$PURE" <<'PYEOF'
import io, sys
src = io.open(sys.argv[1], encoding='utf-8').read()
body = src.split('fun String.escapeProbe')[1]
needle = chr(39) + chr(92) + 'n' + chr(39) + ' -> append('
sys.exit(0 if needle in body else 1)
PYEOF
  then ESC_OK=yes; fi
fi
c "探针把换行转义成可见字符（否则一行日志会被日志文件按行撕开）" \
  "[ \"\$ESC_OK\" = \"yes\" ]"
# 这一条盯的是"剥了围栏之后再说 json=fail"：不剥就把"模型吐了围栏"与
# "模型吐了彻底非法的东西"混成同一条读数，而这两者的修法完全不同。
# ⚠ 判据必须**锚在探针那一处**：`stripCodeFence(content)` 在下发剥离的两个函数里
#   也有（那是行为、不是读数），只 grep 它在不在，会让"探针不剥了"这件事照样 PASS
#   —— 本自测的桩㉛ 抓出来的正是这一处（改了探针、守卫却仍绿）。
# 判据贴住**探针那一处**的相邻两行（`val stripped = stripCodeFence(content)` 紧跟
# `val (ok, kind, elems) = inspectJson(stripped)`）：别处也有 stripCodeFence 调用，
# 只判"文件里存在这一行"会恒真（本自测的桩㉛ 抓出来的）。
# ⚠ 用 awk 判相邻，不用 `grep -P`（GNU 扩展，busybox 没有）—— 与本文件既有做法同源。
PROBE_STRIP_OK=no
if grep -q 'fun stripCodeFence' "$PURE" && \
   awk '/val stripped = stripCodeFence\(content\)/{f=1;next} f&&/val \(ok, kind, elems\) = inspectJson\(stripped\)/{print "yes";exit}' "$PURE" | grep -q yes; then
  PROBE_STRIP_OK=yes
fi
c "围栏在报 json= 之前先剥离（两种成因不得混成一条读数）" \
  "[ \"\$PROBE_STRIP_OK\" = \"yes\" ]"
c "探针**不参与任何控制流**（返回值不落进响应、不改变分支）" \
  "! grep -qE 'probeBody\(.*\).*(if|when|return)' \$HTTP"
c "探针有独立单测（含真机那例逐字：代码围栏 + 3 元素）" \
  "grep -q 'probeBody' tools/json_schema/JsonSchemaFormatTest.kt && \
   grep -q 'pandas' tools/json_schema/JsonSchemaFormatTest.kt"

# ── 9) 下发前剥围栏：**服务端真的动了字节**，且只在安全时动 ────────────────
# 为什么必须有这一节：`0.9.94` 起的 `strip=有围栏已剥` 只是**读数** ——
# `stripCodeFence` 的唯一调用点在 `probeBody` 里，下发的字节一个字都没动。
# 于是一个 `response_format=json_schema` 的请求可以拿到 200 + 一段被围栏包住的
# content，调用方 `json.loads()` 直接抛。这几条盯的就是"读数变成了行为"。
c "剥离是判据文件里的纯函数（不在 HttpApi 现拼）"   "grep -q 'fun stripFenceForDelivery' \$PURE"
# 判据①：None（没要求结构化输出）必须原样下发 —— 否则会改掉"本身就带围栏"的普通文本。
c "未要求结构化输出时原样下发（不得改普通文本的字节）" \
  "grep -q 'if (f is ResponseFormat.None) return content' \$PURE"
# 判据②：剥完必须仍是 JSON，否则退回原件（判据用探针同一份 inspectJson）。
c "剥后非 JSON 时退回原件（不发残段 —— 与探针同一份判据）" \
  "grep -q 'if (!inspectJson(stripped).first) return content' \$PURE"
# 判据③：不剥时**同一实例**返回，调用方据此区分"没动"与"剥了"。
c "不剥时返回同一实例（=== 是对象身份，不是内容相等）" \
  "grep -q 'if (stripped === content) return content' \$PURE"
# 接线：两个生成端点都必须调它（漏一个 = 那个端点仍是老行为）。
c "两个生成端点都下发前剥离（计 2 处）" \
  "[ \$(grep -c 'JsonSchemaFormat.stripFenceForDelivery(respFormat, rawOut)' \$HTTP) -eq 2 ]"
# 顺序断言：剥离必须在**探针之后**（探针读的是"模型真吐了什么"，不是"我们改成了什么"）。
# 顺序反了会让探针从此撒谎 —— 而这正是本仓库反复栽的那类"读数被行为污染"。
PROBE_LN=$(grep -n 'JsonSchemaFormat.probeBody(JsonSchemaFormat.reqTagOf(id), sb.toString())' "$HTTP" | head -1 | cut -d: -f1)
STRIP_LN=$(grep -n 'val rawOut = sb.toString()' "$HTTP" | head -1 | cut -d: -f1)
c "找得到探针与剥离两处调用点" "[ -n \"\$PROBE_LN\" ] && [ -n \"\$STRIP_LN\" ]"
c "剥离排在探针**之后**（否则探针记的就不是模型原话）"   "[ \"\$PROBE_LN\" -lt \"\$STRIP_LN\" ]"
# 服务端改了字节必须留痕，否则下一个人会以为模型本来就吐得干净。
c "剥离动作落一行日志（服务端改了模型输出必须留痕）"   "grep -q 'JsonSchemaFormat.describeDeliveryStrip(respFormat, rawOut)' \$HTTP"
# 不得在 HttpApi 里再判一次(两处判据必然漂移) —— 剥不剥只由判据文件决定。
c "HttpApi 不自行判'有没有围栏'（判据只在 JsonSchemaFormat）"   "! grep -q 'stripCodeFence' \$HTTP"
c "剥离有独立单测（含真机那例、剥后非 JSON 退回、同一实例三条）"   "grep -q 'stripFenceForDelivery' tools/json_schema/JsonSchemaFormatTest.kt && \
   grep -q 'describeDeliveryStrip' tools/json_schema/JsonSchemaFormatTest.kt"

echo ""
if [ "$bad" = "0" ]; then echo "=== 结构化输出守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 结构化输出守卫：PASS $ok / FAIL $bad ==="; exit 1
