#!/bin/sh
# native 工具解析的「解析器必须自己装回去」静态断言。
#
# 为什么单独一份：真机日志（probe-20260917-192446.txt）里
#     [parse] <<< templates_apply ok  format=peg-native(2)  parser_len=7946
#     [parse] <<< common_chat_parse ok  content_len=173 tool_calls=0
# 模型已经吐出完整、合法的 <tool_call>{...}</tool_call>，但 tool_calls 恒为 0、
# content 恒等于全文（= text 151B + generation_prompt 22B）。
#
# 根因：common_chat_parser_params(const common_chat_params &) **只搬 format 与
# generation_prompt**（见 chat.h），而 common_chat_parse 的实现是
#     return common_chat_peg_parse(params.parser, input, is_partial, params);
# 且 common_chat_peg_parse 里
#     parser = src_parser.empty() ? 纯内容解析器 : src_parser;
# 所以 pp.parser 为空时，整段输出被当成 content 吃掉，永远解析不出工具调用。
# cp.parser 是 PEG 的序列化串（save() 的产物），必须显式 arena.load(cp.parser)。
#
# 这类错「不崩溃、只是静默不干活」，比崩溃更难查，因此用断言钉死。
set -e
cd "$(dirname "$0")/.."
CPP=app/src/main/cpp/llama_jni.cpp
UTIL=app/src/main/cpp/probe_util.h   # 探针工具：取 PEG 根节点字面量 + generation_prompt 对齐
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
# 只抽出 parseToolCallsImpl 的函数体（到第一个顶格 } 为止），避免误命中渲染路径
awk '/static jstring parseToolCallsImpl/{f=1} f{print} f&&/^\}/{exit}' "$CPP" > "$TMP"

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "parse 路径显式 load PEG（pp.parser.load(cp.parser)）" \
  "grep -q 'pp.parser.load(cp.parser);' \$TMP"
c "load 在 common_chat_parse 之前" \
  "[ \$(grep -n 'pp.parser.load(cp.parser);' \$TMP | head -1 | cut -d: -f1) -lt \$(grep -n 'common_chat_parse(text' \$TMP | head -1 | cut -d: -f1) ]"
c "load 有 try/catch（不使异常穿过 JNI）" \
  "grep -A3 'pp.parser.load(cp.parser);' \$TMP | grep -q 'catch'"
c "load 后显式判空（防静默降级成纯内容）" \
  "grep -q 'PEG load 后仍为空' \$TMP && grep -q 'pp.parser.empty()' \$TMP"
c "load 失败时降级为 kNoToolCalls（不外抛）" \
  "grep -q 'PEG load 异常' \$TMP && grep -q 'PEG load 未知异常' \$TMP && [ \$(grep -c 'return env->NewStringUTF(kNoToolCalls);' \$TMP) -ge 3 ]"
c "load 成功后输出 root/size 便于远程复核" \
  "grep -q 'pp.parser.root()' \$TMP && grep -q 'pp.parser.size()' \$TMP"
# 回归：不许再出现「声称 common_chat_parse 自己推导解析器」的旧注释
c "不再声称由 common_chat_parse 自行推导" \
  "! grep -q '由 common_chat_parse 自己去推导' \$CPP"
# 渲染路径已确认会产出解析器（cp.parser 非空），否则 load 无从谈起
c "渲染路径判空 cp.parser（load 的前提）" \
  "grep -q 'cp.parser.empty()' \$CPP"

# ── 第二轮真因：解析侧的 add_generation_prompt 与渲染侧分叉 ──
# 上一轮只修了「PEG 没装进 parser_params」，但 0.9.68/0.9.69 真机仍是 tool_calls=0。
# 日志证据：content_len - text_len = 8（两版都一样），那 8B 是 "<think>\n"。
# 根因：解析侧写死 add_generation_prompt=false，渲染侧传 true，于是
#   解析侧 cp.generation_prompt = "<|im_start|>assistant\n<think>\n"（30B）
#   而 PEG 根节点要的是      literal("<|im_start|>assistant\n")（22B）
# 而 common_chat_parse 会把这个量**前拼**到输入上，根节点一上来就匹配不上。
# 注意：这条和「PEG 有没有 load」是**两个独立缺陷**，各自都能单独让 tool_calls=0。
c "parse 侧 add_generation_prompt 不再写死 false" \
  "! grep -q 'in.add_generation_prompt = false;' \$TMP"
c "parse 侧 add_generation_prompt 取自 addAss（与渲染侧同源）" \
  "grep -q 'in.add_generation_prompt = (addAss == JNI_TRUE);' \$TMP"
c "parseToolCallsImpl 接收 addAss 形参" \
  "grep -q 'jstring jtmpl, jboolean addAss,\$' \$TMP"
c "parseToolCallsImpl 接收 enableThinking 形参（与渲染侧同源，否则前缀形状对不上）" \
  "grep -q 'jboolean enableThinking) {' \$TMP"
c "JNI 入口 nativeParseToolCalls 接收 addAss" \
  "grep -q 'jstring jtmpl, jboolean addAss,\$' \$CPP"
c "JNI 入口 nativeParseToolCalls 接收 enableThinking" \
  "grep -q 'jboolean enableThinking) {' \$CPP"
c "Kotlin native 声明同步带 addAss 与 thinkingOn" \
  "grep -q 'text: String, toolsJson: String?, tmpl: String, addAss: Boolean, thinkingOn: Boolean' \$KT"
c "Kotlin parseToolCalls 把 addAss 透传进 native" \
  "grep -q 'nativeParseToolCalls(text, toolsJson, tmpl, addAss, thinkingOn)' \$KT"
c "Kotlin parseToolCalls 把 thinkingOn 透传进 native（与渲染同源）" \
  "grep -q 'thinkingOn: Boolean = true' \$KT"
# 调用点必须显式写 addAss=true，与 applyChatTemplateWithTools(..., addAss = true) 同取一值。
# 不写而靠默认值也能对，但显式写能让"两处必须一致"这件事在 diff 里可见。
c "HttpApi 解析调用点显式传 addAss = true" \
  "grep -q 'parseToolCalls(' \$HTTP && grep -q 'addAss = true, thinkingOn = thinkingOn)' \$HTTP"

# ── 探针：这对数是这次查了两轮才推出来的，必须留在日志里 ──
c "探针打出 generation_prompt 原文与长度" \
  "grep -q 'generation_prompt_len=' \$TMP && grep -q 'generation_prompt=' \$TMP"
c "探针打出 effective_input（前拼后的真实输入）" \
  "grep -q 'effective_input_len=' \$TMP && grep -q 'effective_input_head=' \$TMP"
c "generation_prompt 经转义后再落盘（换行可辨）" \
  "grep -q 'escape_for_probe(pp.generation_prompt,' \$TMP"
# escape_for_probe 已随探针工具搬进 probe_util.h（宿主下可单测，见
# tools/run_root_literal_probe_tests.sh）。这里查两处：定义在 UTIL、使用在 CPP。
c "escape_for_probe 定义在 probe_util.h" \
  "grep -q 'static inline std::string escape_for_probe' \$UTIL"
c "escape_for_probe 转义 \\n（判据行的可读性靠它）" \
  "grep -q 'case .\\\\n.: out += ' \$UTIL"
c "llama_jni.cpp include probe_util.h（同一份实现，非另抄一份）" \
  "grep -q '#include \"probe_util.h\"' \$CPP"

# ── 第三轮真因：generation_prompt 形状与 PEG 根节点字面量不一致 ──
# 上面的 addAss 对齐只保证"两侧取值一致"，不保证"形状与根节点要求一致"。
# 库要求 effective_input 以 PEG 根节点那个字面量开头；不一致时根节点一上来就
# 匹配不上，PEG 回退成纯内容 —— tool_calls 恒为 0，不报错、不崩溃。
# 已观测到的两种形状都要收得住：
#   · generation_prompt = 字面量 + 尾巴（MiniCPM5 的 "<think>\n"，30B）-> 情形①（尾巴前移）
#   · generation_prompt 与字面量全然不同（形状不符）                    -> 情形②（只补输入）
# 修法落在"把 generation_prompt 对齐到根节点字面量"上，而不是再调模板参数。
# 注意：日志里的 content_len - text_len = 8 只是当年发现这条的线索，**不是判据**——
# 一个数是 UTF-16 code unit、一个是 UTF-8 字节，输出含汉字时不可比。
# 实现搬进了 probe_util.h（真机与宿主单测共用同一份），所以断言落在 UTIL 上；
# 同时必须确认 llama_jni.cpp 真的调用了它 —— "搬走了但没人用"会静默失效。
c "从 PEG arena 现场取根节点字面量（不写死模板名）" \
  "grep -q 'std::get_if<LiteralParserT>' \$UTIL"
c "取根节点有 try/catch（get 越界不外抛，异常不得穿 JNI 帧）" \
  "grep -q 'catch (const std::exception & e) {' \$UTIL && grep -q 'taken = true;' \$UTIL"
c "llama_jni.cpp 调 probe_root_literal（真机走的就是单测那份）" \
  "grep -q 'probe_root_literal<common_peg_arena, common_peg_literal_parser>(pp.parser)' \$TMP"
c "llama_jni.cpp 调 align_generation_prompt" \
  "grep -q 'align_generation_prompt(gp, rootLiteral, text)' \$TMP"
c "对齐结果确实写回 pp.generation_prompt 与 text" \
  "grep -q 'pp.generation_prompt = al.generation_prompt;' \$TMP && grep -q 'text                 = al.input;' \$TMP"
c "改写的输入确实传给了 common_chat_parse" \
  "grep -q 'common_chat_parse(text, /\*is_partial=\*/false, pp)' \$TMP"
c "generation_prompt 被对齐成根节点字面量" \
  "grep -q 'r.generation_prompt = rootLiteral;' \$UTIL"
c "多出的尾巴并回输入（交给 content 规则吃掉）" \
  "grep -q 'r.input             = r.patched + text;' \$UTIL"
c "根不是字面量时不做改写（保持原样交给库）" \
  "grep -q '没有可信字面量' \$UTIL && grep -q 'if (rootLiteral.empty()) return r;' \$UTIL"
c "已对齐时一个字节都不动（不引入新问题）" \
  "grep -q 'if (genPrompt == rootLiteral) return r;' \$UTIL"
c "形状不符时保守：不改库给的 generation_prompt，只补进输入" \
  "grep -q 'r.changed = false;' \$UTIL && grep -q 'r.input   = rootLiteral + text;' \$UTIL"
c "逐字节等价自检存在（不等就回退为不改写）" \
  "grep -q '回退为不改写' \$TMP"
c "不再依赖 addAss 单点对齐（形状对齐才是收口条件）" \
  "grep -q 'root_is_literal=' \$TMP && grep -q 'root_literal=' \$TMP"
c "include <variant>（get_if 的来源，显式而非靠传递包含）" \
  "grep -q '#include <variant>' \$UTIL"

# ── 第四轮真因（本轮）：解码时 special token 没文本化，工具标记被直接丢掉 ──
# 真机 0.9.69 的 text_head = ' name="get_weather"> name="city">Beijing'
# 恰好是完整工具调用 <function name="..."><param name="...">Beijing</param></function>
# **逐个删掉** <function / <param / </param> / </function> 之后的样子
# —— 因为它们在模型词表里是 special token，而 llama.h 写得很清楚：
#     @param special If true, special tokens are rendered in the output.
# 我们以前传的是 false，所以 PEG 的 tool_open 永远匹配不上。
# 这一条发生在最上游，解码阶段就把标记弄丢了，后面怎么修解析都对不上。
c "token_to_piece 带 renderSpecial 形参" \
  "grep -q 'static std::string token_to_piece(llama_token t, bool renderSpecial)' \$CPP"
c "解码主路径用 renderSpecial=true（关键）" \
  "grep -q 'token_to_piece(t, /\*renderSpecial=\*/true)' \$CPP"
c "llama_token_to_piece 的 special 实参取自 renderSpecial" \
  "grep -q 'llama_token_to_piece(S.vocab, t, buf, sizeof(buf), 0, renderSpecial)' \$CPP"
c "不再有写死 special=false 的 token_to_piece 调用" \
  "! grep -q 'llama_token_to_piece(S.vocab, t, .*, 0, false)' \$CPP"
# 这里原来 grep 的是 `S.pending += token_to_piece(tok);`。补齐 stop 之后该行被
# 换成「按状态机放行的增量下发」（`S.pending += piece;` / `+= stop_sampler_take_released`），
# 于是 grep 落空、`[ -lt ]` 直接报 "argument expected" —— 断言失效为**报错**而不是 FAIL。
# 改为匹配仍然存在、且语义等价的「本步把 token 文本化」那一行：EOG 判定必须早于它。
c "EOG 判定在 token_to_piece 之前（防 <|im_end|> 漏进正文）" \
  "[ \$(grep -n 'llama_vocab_is_eog(S.vocab, tok)' \$CPP | head -1 | cut -d: -f1) -lt \$(grep -n 'const std::string piece = token_to_piece(tok);' \$CPP | head -1 | cut -d: -f1) ]"
c "解码侧可疑形状有留痕（不再在解析器上绕圈）" \
  "grep -q '解码侧可疑' \$TMP"
# ── 第三条：enable_thinking 必须显式传（C++ 默认 true，不传 = 恒"思考开"）──
# 这一条编译期拦不住：字段存在、默认值合理、不赋值也不报错，真机只表现为
# "设置页勾了默认关闭思考也没用"。三条路径（无 tools 渲染 / 带 tools 渲染 / 解析）
# 都必须赋值，缺一条就有一种请求形态关不掉思考。
c "无 tools 渲染路径显式设 enable_thinking" \
  "grep -q 'in.enable_thinking = (enableThinking == JNI_TRUE);' \$CPP"
c "三条路径都设了 enable_thinking（计数 = 3）" \
  "[ \$(grep -c 'in.enable_thinking = (enableThinking == JNI_TRUE);' \$CPP) -eq 3 ]"
c "无 tools 渲染不再走 llama_chat_apply_template（旧接口不认 enable_thinking）" \
  "! grep -q 'llama_chat_apply_template(' \$CPP"
c "无 tools 渲染也走 common_chat_templates_apply（与带 tools 路径同一套引擎）" \
  "grep -q 'Java_com_xiaowan_localinference_LlmEngine_nativeApplyChatTemplate(' \$CPP && \
   awk '/nativeApplyChatTemplate\\(/,/^}/' \$CPP | grep -q 'common_chat_templates_apply'"
c "带 tools 渲染的 enable_thinking 由形参透传" \
  "grep -q 'jboolean enableThinking) {' \$CPP"
c "解析路径的 enable_thinking 由形参透传" \
  "grep -q 'in.enable_thinking = (enableThinking == JNI_TRUE);' \$TMP"

# ── 第四条：JNI 形参个数不能两边漂移 ──
# 形参个数不一致 = 运行时 UnsatisfiedLinkError，而它**只在真机调用到那一刻**才报，
# 编译期（两边各自都合法）与离线单测都发现不了。这里按"Kotlin 声明的形参个数"
# 与"C++ 函数形参个数"逐个数一遍。
c "Kotlin nativeApplyChatTemplate 声明 5 个形参（含 thinkingOn）" \
  "awk '/fun nativeApplyChatTemplate\\(/,/\\): String\\?/' \$KT | grep -q 'thinkingOn: Boolean): String?'"
c "Kotlin nativeApplyChatTemplateTools 声明 8 个形参（含 thinkingOn）" \
  "awk '/fun nativeApplyChatTemplateTools\\(/,/\\): String\\?/' \$KT | grep -q 'thinkingOn: Boolean): String?'"
c "Kotlin nativeParseToolCalls 声明 5 个形参（含 thinkingOn）" \
  "awk '/fun nativeParseToolCalls\\(/,/\\): String/' \$KT | grep -q 'addAss: Boolean, thinkingOn: Boolean): String'"
c "C++ 对应入口的尾参也是 enableThinking" \
  "grep -q 'jboolean addAss, jboolean enableThinking) {' \$CPP && \
   grep -q 'jboolean parallelToolCalls, jboolean addAss,\$' \$CPP"
c "C++ 无 tools 渲染入口形参序列与 Kotlin 一一对应（addAss, enableThinking）" \
  "grep -q 'jboolean addAss, jboolean enableThinking) {' \$CPP"
# 5 处 = 3 个 JNI 入口 + 2 个 Impl（无 tools 的渲染直接写在入口里，没有单独 Impl）
c "C++ 的 3 个 JNI 入口与 2 个 Impl 都接收 enableThinking 尾参（共 5 处）" \
  "[ \$(grep -c 'jboolean enableThinking) {' \$CPP) -eq 5 ]"


c "留痕判定同时看开头形状与是否含标记" \
  "grep -q 'looksStripped' \$TMP && grep -q 'kToolMarkers' \$TMP"

if [ "$bad" -eq 0 ]; then
    echo "=== 工具解析（PEG load + 前缀对齐）单测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 工具解析（PEG load + 前缀对齐）单测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
