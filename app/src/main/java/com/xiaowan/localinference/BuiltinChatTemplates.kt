package com.xiaowan.localinference

/**
 * 模型没带 `tokenizer.chat_template` 时的**按架构内置模板**表。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么需要它
 * ═══════════════════════════════════════════════════════════════════════════
 * 上游 `common_chat_templates_init` 在模型没有模板时**恒定回落 ChatML**（见
 * vendor 的 chat.cpp：`CHATML_TEMPLATE_SRC`）。ChatML 本身能跑，但它没有
 * `enable_thinking` 分支 —— 对 Qwen3 这类“模板里有关思考开关”的模型，
 * 「关闭思考」就变成了**关不掉**：模板不预填空 think 块，软开关又因为生成后缀
 * 里没有 ` thinking` 而判定“不适用”，模型于是仍然自己产出一大段思考 token。
 *
 * 典型现场（2026-11-07 真机日志）：Huihui Qwen3 0.6B Q8_0 的 GGUF 里没有
 * chat_template，关思考时仍 decode 78/85 个 token（约 6.3/6.9 s），而带模板的
 * 同架构模型关思考只要 7/17 个 token（约 0.6/1.2 s）。两者 decode 吞吐完全一样
 * （12.3~13.1 tok/s），差的只是“看不看得见的思考 token”。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么是**允许列表**，不是按架构名猜
 * ═══════════════════════════════════════════════════════════════════════════
 * 同一个架构名可能对应多份模板（微调版会改 system/tools/思考段），模板还与
 * tokenizer/special token 强绑定。猜错不会抛异常，只会把 prompt 拼成模型不认识的
 * 形状 —— 比通用 ChatML 更糟，而且同样“哑得不响”。
 *
 * 所以这里只收录**逐字核对过官方模板**的架构；未收录的一律返回 null，调用方
 * 保持原行为（Kotlin 侧 ChatML 回落）。新增一条必须同时补：
 *   · 模板来源与版本的注释；
 *   · `tools/thinking/BuiltinChatTemplatesTest.kt` 的形状断言。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 已收录
 * ═══════════════════════════════════════════════════════════════════════════
 * · qwen3 / qwen3moe：Qwen3 官方 ChatML 模板（含 tools 分支与
 *   `enable_thinking is false -> ' thinking\n\n</think>\n\n'`）。
 *   与真机 `qwen3-0.6b-abliterated.Q8_0.gguf` 元数据里的模板逐字一致，
 *   也是本仓库最早验证过“模板自己关思考”的那一份。
 *
 * 另外 [upgradeOutdated] 负责**模型自带模板的替换**：旧版 gemma4 模板换成同版本
 * 官方模板。它和上面的「无模板回落」是两件事 —— 一个补缺、一个升级，判据不同。
 */
object BuiltinChatTemplates {

    /**
     * 按 `general.architecture` 取内置模板；未收录返回 null。
     *
     * 归一化只做 `trim + lowercase`：GGUF 里出现过大小写/尾随空格不同的写法，
     * 但**不做前缀匹配**（`qwen3` 不能顺手吃掉 `qwen3vl` / `qwen3next` ——
     * 那些家族的模板并不相同）。
     */
    fun forArchitecture(architecture: String?): String? {
        val arch = architecture?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (arch) {
            "qwen3", "qwen3moe" -> QWEN3
            else -> null
        }
    }

    /** 这个架构是否已有经过验证的内置模板（只用于日志/诊断，不参与渲染判定）。 */
    fun supports(architecture: String?): Boolean = forArchitecture(architecture) != null

    /**
     * Qwen3 官方 ChatML 模板（tools + enable_thinking 两支都保留）。
     *
     * ⚠ 这是 **Jinja 原文**，不是 Kotlin 字符串：里面的 `\n` 是给 minja 解析的
     * 转义，必须保持“反斜杠 + n”两个字符。Kotlin 用 raw string 承载正是为此，
     * 不要改成普通字符串再手写转义。
     */
    private val QWEN3: String = """
        {%- if tools %}
            {{- '<|im_start|>system\n' }}
            {%- if messages[0].role == 'system' %}
                {{- messages[0].content + '\n\n' }}
            {%- endif %}
            {{- "# Tools\n\nYou may call one or more functions to assist with the user query.\n\nYou are provided with function signatures within <tools></tools> XML tags:\n<tools>" }}
            {%- for tool in tools %}
                {{- "\n" }}
                {{- tool | tojson }}
            {%- endfor %}
            {{- "\n</tools>\n\nFor each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call><|im_end|>\n" }}
        {%- else %}
            {%- if messages[0].role == 'system' %}
                {{- '<|im_start|>system\n' + messages[0].content + '<|im_end|>\n' }}
            {%- endif %}
        {%- endif %}
        {%- set ns = namespace(multi_step_tool=true, last_query_index=messages|length - 1) %}
        {%- for message in messages[::-1] %}
            {%- set index = (messages|length - 1) - loop.index0 %}
            {%- if ns.multi_step_tool and message.role == "user" and message.content is string and not(message.content.startswith('<tool_response>') and message.content.endswith('</tool_response>')) %}
                {%- set ns.multi_step_tool = false %}
                {%- set ns.last_query_index = index %}
            {%- endif %}
        {%- endfor %}
        {%- for message in messages %}
            {%- if message.content is string %}
                {%- set content = message.content %}
            {%- else %}
                {%- set content = '' %}
            {%- endif %}
            {%- if (message.role == "user") or (message.role == "system" and not loop.first) %}
                {{- '<|im_start|>' + message.role + '\n' + content + '<|im_end|>' + '\n' }}
            {%- elif message.role == "assistant" %}
                {%- set reasoning_content = '' %}
                {%- if message.reasoning_content is string %}
                    {%- set reasoning_content = message.reasoning_content %}
                {%- else %}
                    {%- if '</think>' in content %}
                        {%- set reasoning_content = content.split('</think>')[0].rstrip('\n').split('<think>')[-1].lstrip('\n') %}
                        {%- set content = content.split('</think>')[-1].lstrip('\n') %}
                    {%- endif %}
                {%- endif %}
                {%- if loop.index0 > ns.last_query_index %}
                    {%- if loop.last or (not loop.last and reasoning_content) %}
                        {{- '<|im_start|>' + message.role + '\n<think>\n' + reasoning_content.strip('\n') + '\n</think>\n\n' + content.lstrip('\n') }}
                    {%- else %}
                        {{- '<|im_start|>' + message.role + '\n' + content }}
                    {%- endif %}
                {%- else %}
                    {{- '<|im_start|>' + message.role + '\n' + content }}
                {%- endif %}
                {%- if message.tool_calls %}
                    {%- for tool_call in message.tool_calls %}
                        {%- if (loop.first and content) or (not loop.first) %}
                            {{- '\n' }}
                        {%- endif %}
                        {%- if tool_call.function %}
                            {%- set tool_call = tool_call.function %}
                        {%- endif %}
                        {{- '<tool_call>\n{"name": "' }}
                        {{- tool_call.name }}
                        {{- '", "arguments": ' }}
                        {%- if tool_call.arguments is string %}
                            {{- tool_call.arguments }}
                        {%- else %}
                            {{- tool_call.arguments | tojson }}
                        {%- endif %}
                        {{- '}\n</tool_call>' }}
                    {%- endfor %}
                {%- endif %}
                {{- '<|im_end|>\n' }}
            {%- elif message.role == "tool" %}
                {%- if loop.first or (messages[loop.index0 - 1].role != "tool") %}
                    {{- '<|im_start|>user' }}
                {%- endif %}
                {{- '\n<tool_response>\n' }}
                {{- content }}
                {{- '\n</tool_response>' }}
                {%- if loop.last or (messages[loop.index0 + 1].role != "tool") %}
                    {{- '<|im_end|>\n' }}
                {%- endif %}
            {%- endif %}
        {%- endfor %}
        {%- if add_generation_prompt %}
            {{- '<|im_start|>assistant\n' }}
            {%- if enable_thinking is defined and enable_thinking is false %}
                {{- '<think>\n\n</think>\n\n' }}
            {%- endif %}
        {%- endif %}
    """.trimIndent()

    /**
     * 模型**自带**的模板若是旧版 gemma4，换成同一 llama.cpp commit 的官方模板。
     *
     * 为什么需要这一步：llama.cpp 的 `common_chat_template_detect_params_init` 会把
     * 缺 `{#- OpenAI Chat Completions:` 标记的 gemma4 模板判成旧版，每轮初始化都往
     * stderr 打一行 `detected an outdated gemma4 chat template ... Consider updating
     * to the official template`，再走 `common_chat_params_init_gemma4_legacy` 的
     * 兼容改写。兼容路径本身能用，但它是**为旧模板兜底**的：工具响应要由库在渲染前
     * 改写成旧模板认的形状，而新版模板自己就会前扫连续的 `role:tool` 消息。
     *
     * 这里在**宿主侧**换成官方模板，等于把上游建议的「更新模板」做掉：
     *   · 告警不再出现（模板里带官方标记）；
     *   · 走的是新模板自己的工具响应分支，而不是库的兼容改写；
     *   · 旧 GGUF 不需要重打包。
     *
     * ⚠ 判据必须与上游逐字一致（`'<|tool_call>call:'` + 缺官方标记），否则会出现
     *   「宿主以为换了、库仍判旧版」或反过来 —— 两种都只体现为 prompt 形状不对，
     *   不抛异常。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么升级时还要看模型自己那份的「思考关闭形状」
     * ══════════════════════════════════════════════════════════════════════════
     * 官方模板在 `enable_thinking=false` 时会在生成后缀里预填一个**空的思考段**：
     *
     *     <|turn>model\n<|channel>thought\n<channel|>
     *
     * 对 Google 官方权重，这段是「思考段已经空着闭合，直接写正文」的提示。但不少
     * GGUF / 微调模板（真机 gemma-4-E4B-Uncensored 就是）**没有**这一段：它们关闭
     * 思考时后缀只有 `<|turn>model\n`，模型据此直接写正文。把这类模型强行换成带空
     * 思考段的官方模板，模型会把推理写进**正文通道** —— 客户端看不到思考标签，
     * 但收到的整段内容都是推理（2026-11-12 真机）。
     *
     * 所以升级只换「工具响应怎么渲染」，**思考关闭形状沿用模型自己那份**：
     *   · 自带空思考段 `<|channel>thought\n<channel|>`（官方系模板）→ 用官方原文；
     *   · 不带（老 GGUF / 微调模板）→ 用去掉空思考段的官方模板，后缀回到
     *     `<|turn>model\n`，与升级前逐字一致。
     *
     * 这样既满足上游的 `outdated` 判据（拿掉 legacy 告警、工具响应走模板自身分支），
     * 又不改这个模型真正依赖的生成后缀形状。
     */
    fun upgradeOutdated(own: String): String? {
        if (own.isBlank()) return null
        val isGemma4 = own.contains("'<|tool_call>call:'")
        val isOfficial = own.contains("{#- OpenAI Chat Completions:")
        if (!isGemma4 || isOfficial) return null
        // 形状的判据只看**生成后缀会不会预填空思考段**（逐字含源码里的 `\n` 两个字符）。
        // 自带这段说明它本来就是官方那一套；不带则必须保留「只有 <|turn>model」的老形状。
        // 不能只找 `<|channel>thought`：官方模板渲染历史推理时也用它，但那不是预填。
        return if (own.contains("<|channel>thought\\n<channel|>")) GEMMA4 else GEMMA4_NO_EMPTY_THOUGHT
    }

    /** 官方模板里「关思考时预填空思考段」的那三行（生成后缀分支，逐字含原文 `\n`）。 */
    private const val GEMMA4_GEN_TAIL_WITH_EMPTY_THOUGHT = """        {{- '<|turn>model\n' -}}
        {%- if not enable_thinking | default(false) -%}
            {{- '<|channel>thought\n<channel|>' -}}
        {%- endif -%}
"""

    /**
     * Gemma 4 官方模板（llama.cpp commit `8642541`，与 vendor 预编译库同源）：
     * `models/templates/google-gemma-4-31B-it.jinja`，16448 B。
     *
     * 与 [QWEN3] 一样是 **Jinja 原文**：`\n` 必须保持「反斜杠 + n」两个字符。
     * 这份模板带官方标记 `{#- OpenAI Chat Completions:`，上游据此不再走 legacy
     * 兼容路径，也就不会再打那行 outdated 告警。
     */
    private val GEMMA4: String = """
        {%- macro format_parameters(properties, required) -%}
            {%- set standard_keys = ['description', 'type', 'properties', 'required', 'nullable'] -%}
            {%- set ns = namespace(found_first=false) -%}
            {%- for key, value in properties | dictsort -%}
                {%- set add_comma = false -%}
                {%- if key not in standard_keys -%}
                    {%- if ns.found_first %},{% endif -%}
                    {%- set ns.found_first = true -%}
                    {{ key }}:{
                    {%- if value['description'] -%}
                        description:<|"|>{{ value['description'] }}<|"|>
                        {%- set add_comma = true -%}
                    {%- endif -%}
                    {%- if value['type'] | upper == 'STRING' -%}
                        {%- if value['enum'] -%}
                            {%- if add_comma %},{%- else -%} {%- set add_comma = true -%} {% endif -%}
                            enum:{{ format_argument(value['enum']) }}
                        {%- endif -%}
                    {%- elif value['type'] | upper == 'ARRAY' -%}
                        {%- if value['items'] is mapping and value['items'] -%}
                            {%- if add_comma %},{%- else -%} {%- set add_comma = true -%} {% endif -%}
                            items:{
                            {%- set ns_items = namespace(found_first=false) -%}
                            {%- for item_key, item_value in value['items'] | dictsort -%}
                                {%- if item_value is not none -%}
                                    {%- if ns_items.found_first %},{% endif -%}
                                    {%- set ns_items.found_first = true -%}
                                    {%- if item_key == 'properties' -%}
                                        properties:{
                                        {%- if item_value is mapping -%}
                                            {{- format_parameters(item_value, value['items']['required'] | default([])) -}}
                                        {%- endif -%}
                                        }
                                    {%- elif item_key == 'required' -%}
                                        required:[
                                        {%- for req_item in item_value -%}
                                            <|"|>{{- req_item -}}<|"|>
                                            {%- if not loop.last %},{% endif -%}
                                        {%- endfor -%}
                                        ]
                                    {%- elif item_key == 'type' -%}
                                        {%- if item_value is string -%}
                                            type:{{ format_argument(item_value | upper) }}
                                        {%- else -%}
                                            type:{{ format_argument(item_value | map('upper') | list) }}
                                        {%- endif -%}
                                    {%- else -%}
                                        {{ item_key }}:{{ format_argument(item_value) }}
                                    {%- endif -%}
                                {%- endif -%}
                            {%- endfor -%}
                            }
                        {%- endif -%}
                    {%- endif -%}
                    {%- if value['nullable'] %}
                        {%- if add_comma %},{%- else -%} {%- set add_comma = true -%} {% endif -%}
                        nullable:true
                    {%- endif -%}
                    {%- if value['type'] | upper == 'OBJECT' -%}
                        {%- if value['properties'] is defined and value['properties'] is mapping -%}
                            {%- if add_comma %},{%- else -%} {%- set add_comma = true -%} {% endif -%}
                            properties:{
                            {{- format_parameters(value['properties'], value['required'] | default([])) -}}
                            }
                        {%- elif value is mapping -%}
                            {%- if add_comma %},{%- else -%} {%- set add_comma = true -%} {% endif -%}
                            properties:{
                            {{- format_parameters(value, value['required'] | default([])) -}}
                            }
                        {%- endif -%}
                        {%- if value['required'] -%}
                            {%- if add_comma %},{%- else -%} {%- set add_comma = true -%} {% endif -%}
                            required:[
                            {%- for item in value['required'] | default([]) -%}
                                <|"|>{{- item -}}<|"|>
                                {%- if not loop.last %},{% endif -%}
                            {%- endfor -%}
                            ]
                        {%- endif -%}
                    {%- endif -%}
                    {%- if add_comma %},{%- else -%} {%- set add_comma = true -%} {% endif -%}
                    type:<|"|>{{ value['type'] | upper }}<|"|>}
                {%- endif -%}
            {%- endfor -%}
        {%- endmacro -%}
        {%- macro format_function_declaration(tool_data) -%}
            declaration:{{- tool_data['function']['name'] -}}{description:<|"|>{{- tool_data['function']['description'] -}}<|"|>
            {%- set params = tool_data['function']['parameters'] -%}
            {%- if params -%}
                ,parameters:{
                {%- if params['properties'] -%}
                    properties:{ {{- format_parameters(params['properties'], params['required']) -}} },
                {%- endif -%}
                {%- if params['required'] -%}
                    required:[
                    {%- for item in params['required'] -%}
                        <|"|>{{- item -}}<|"|>
                        {{- ',' if not loop.last -}}
                    {%- endfor -%}
                    ],
                {%- endif -%}
                {%- if params['type'] -%}
                    type:<|"|>{{- params['type'] | upper -}}<|"|>}
                {%- endif -%}
            {%- endif -%}
            {%- if 'response' in tool_data['function'] -%}
                {%- set response_declaration = tool_data['function']['response'] -%}
                ,response:{
                {%- if response_declaration['description'] -%}
                    description:<|"|>{{- response_declaration['description'] -}}<|"|>,
                {%- endif -%}
                {%- if response_declaration['type'] | upper == 'OBJECT' -%}
                    type:<|"|>{{- response_declaration['type'] | upper -}}<|"|>}
                {%- endif -%}
            {%- endif -%}
            }
        {%- endmacro -%}
        {%- macro format_argument(argument, escape_keys=True) -%}
            {%- if argument is string -%}
                {{- '<|"|>' + argument + '<|"|>' -}}
            {%- elif argument is boolean -%}
                {{- 'true' if argument else 'false' -}}
            {%- elif argument is mapping -%}
                {{- '{' -}}
                {%- set ns = namespace(found_first=false) -%}
                {%- for key, value in argument | dictsort -%}
                    {%- if ns.found_first %},{% endif -%}
                    {%- set ns.found_first = true -%}
                    {%- if escape_keys -%}
                        {{- '<|"|>' + key + '<|"|>' -}}
                    {%- else -%}
                        {{- key -}}
                    {%- endif -%}
                    :{{- format_argument(value, escape_keys=escape_keys) -}}
                {%- endfor -%}
                {{- '}' -}}
            {%- elif argument is sequence -%}
                {{- '[' -}}
                {%- for item in argument -%}
                    {{- format_argument(item, escape_keys=escape_keys) -}}
                    {%- if not loop.last %},{% endif -%}
                {%- endfor -%}
                {{- ']' -}}
            {%- else -%}
                {{- argument -}}
            {%- endif -%}
        {%- endmacro -%}
        {%- macro strip_thinking(text) -%}
            {%- set ns = namespace(result='') -%}
            {%- for part in text.split('<channel|>') -%}
                {%- if '<|channel>' in part -%}
                    {%- set ns.result = ns.result + part.split('<|channel>')[0] -%}
                {%- else -%}
                    {%- set ns.result = ns.result + part -%}
                {%- endif -%}
            {%- endfor -%}
            {{- ns.result | trim -}}
        {%- endmacro -%}

        {%- macro format_tool_response_block(tool_name, response) -%}
            {{- '<|tool_response>' -}}
            {%- if response is mapping -%}
                {{- 'response:' + tool_name + '{' -}}
                {%- for key, value in response | dictsort -%}
                    {{- key -}}:{{- format_argument(value, escape_keys=False) -}}
                    {%- if not loop.last %},{% endif -%}
                {%- endfor -%}
                {{- '}' -}}
            {%- else -%}
                {{- 'response:' + tool_name + '{value:' + format_argument(response, escape_keys=False) + '}' -}}
            {%- endif -%}
            {{- '<tool_response|>' -}}
        {%- endmacro -%}

        {%- set ns = namespace(prev_message_type=None) -%}
        {%- set loop_messages = messages -%}
        {{- bos_token -}}
        {#- Handle System/Tool Definitions Block -#}
        {%- if (enable_thinking is defined and enable_thinking) or tools or messages[0]['role'] in ['system', 'developer'] -%}
            {{- '<|turn>system\n' -}}

            {#- Inject Thinking token at the very top of the FIRST system turn -#}
            {%- if enable_thinking is defined and enable_thinking -%}
                {{- '<|think|>\n' -}}
                {%- set ns.prev_message_type = 'think' -%}
            {%- endif -%}

            {%- if messages[0]['role'] in ['system', 'developer'] -%}
                {{- messages[0]['content'] | trim -}}
                {%- set loop_messages = messages[1:] -%}
            {%- endif -%}

            {%- if tools -%}
                {%- for tool in tools %}
                    {{- '<|tool>' -}}
                    {{- format_function_declaration(tool) | trim -}}
                    {{- '<tool|>' -}}
                {%- endfor %}
                {%- set ns.prev_message_type = 'tool' -%}
            {%- endif -%}

            {{- '<turn|>\n' -}}
        {%- endif %}

        {#- Pre-scan: find last user message index for reasoning guard -#}
        {%- set ns_turn = namespace(last_user_idx=-1) -%}
        {%- for i in range(loop_messages | length) -%}
            {%- if loop_messages[i]['role'] == 'user' -%}
                {%- set ns_turn.last_user_idx = i -%}
            {%- endif -%}
        {%- endfor -%}

        {#- Loop through messages -#}
        {%- for message in loop_messages -%}
            {%- if message['role'] != 'tool' -%}
            {%- set ns.prev_message_type = None -%}
            {%- set role = 'model' if message['role'] == 'assistant' else message['role'] -%}
            {#- Detect continuation: suppress duplicate <|turn>model when previous non-tool message was also assistant -#}
            {%- set prev_nt = namespace(role=None, found=false) -%}
            {%- if loop.index0 > 0 -%}
                {%- for j in range(loop.index0 - 1, -1, -1) -%}
                    {%- if not prev_nt.found -%}
                        {%- if loop_messages[j]['role'] != 'tool' -%}
                            {%- set prev_nt.role = loop_messages[j]['role'] -%}
                            {%- set prev_nt.found = true -%}
                        {%- endif -%}
                    {%- endif -%}
                {%- endfor -%}
            {%- endif -%}
            {%- set continue_same_model_turn = (role == 'model' and prev_nt.role == 'assistant') -%}
            {%- if not continue_same_model_turn -%}
                {{- '<|turn>' + role + '\n' }}
            {%- endif -%}

            {#- Render reasoning/reasoning_content as thinking channel -#}
            {%- set thinking_text = message.get('reasoning') or message.get('reasoning_content') -%}
            {%- if thinking_text and loop.index0 > ns_turn.last_user_idx and message.get('tool_calls') -%}
                {{- '<|channel>thought\n' + thinking_text + '\n<channel|>' -}}
            {%- endif -%}

                    {%- if message['tool_calls'] -%}
                        {%- for tool_call in message['tool_calls'] -%}
                            {%- set function = tool_call['function'] -%}
                            {{- '<|tool_call>call:' + function['name'] + '{' -}}
                            {%- if function['arguments'] is mapping -%}
                                {%- set ns_args = namespace(found_first=false) -%}
                                {%- for key, value in function['arguments'] | dictsort -%}
                                    {%- if ns_args.found_first %},{% endif -%}
                                    {%- set ns_args.found_first = true -%}
                                    {{- key -}}:{{- format_argument(value, escape_keys=False) -}}
                                {%- endfor -%}
                            {%- elif function['arguments'] is string -%}
                                {{- function['arguments'] -}}
                            {%- endif -%}
                            {{- '}<tool_call|>' -}}
                        {%- endfor -%}
                        {%- set ns.prev_message_type = 'tool_call' -%}
                    {%- endif -%}

                    {%- set ns_tr_out = namespace(flag=false) -%}
                    {%- if message.get('tool_responses') -%}
                        {#- Legacy: tool_responses embedded on the assistant message (Google/Gemma native) -#}
                        {%- for tool_response in message['tool_responses'] -%}
                            {{- format_tool_response_block(tool_response['name'] | default('unknown'), tool_response['response']) -}}
                            {%- set ns_tr_out.flag = true -%}
                            {%- set ns.prev_message_type = 'tool_response' -%}
                        {%- endfor -%}
                    {%- elif message.get('tool_calls') -%}
                        {#- OpenAI Chat Completions: forward-scan consecutive role:tool messages -#}
                        {%- set ns_tool_scan = namespace(stopped=false) -%}
                        {%- for k in range(loop.index0 + 1, loop_messages | length) -%}
                            {%- if ns_tool_scan.stopped -%}
                            {%- elif loop_messages[k]['role'] != 'tool' -%}
                                {%- set ns_tool_scan.stopped = true -%}
                            {%- else -%}
                                {%- set follow = loop_messages[k] -%}
                                {#- Resolve tool_call_id to function name -#}
                                {%- set ns_tname = namespace(name=follow.get('name') | default('unknown')) -%}
                                {%- for tc in message['tool_calls'] -%}
                                    {%- if tc.get('id') == follow.get('tool_call_id') -%}
                                        {%- set ns_tname.name = tc['function']['name'] -%}
                                    {%- endif -%}
                                {%- endfor -%}
                                {#- Handle content as string or content-parts array -#}
                                {%- set tool_body = follow.get('content') -%}
                                {%- if tool_body is string -%}
                                    {{- format_tool_response_block(ns_tname.name, tool_body) -}}
                                {%- elif tool_body is sequence and tool_body is not string -%}
                                    {%- set ns_txt = namespace(s='') -%}
                                    {%- for part in tool_body -%}
                                        {%- if part.get('type') == 'text' -%}
                                            {%- set ns_txt.s = ns_txt.s + (part.get('text') | default('')) -%}
                                        {%- endif -%}
                                    {%- endfor -%}
                                    {{- format_tool_response_block(ns_tname.name, ns_txt.s) -}}
                                {%- else -%}
                                    {{- format_tool_response_block(ns_tname.name, tool_body) -}}
                                {%- endif -%}
                                {%- set ns_tr_out.flag = true -%}
                                {%- set ns.prev_message_type = 'tool_response' -%}
                            {%- endif -%}
                        {%- endfor -%}
                    {%- endif -%}

                    {%- if message['content'] is string -%}
                        {%- if role == 'model' -%}
                            {{- strip_thinking(message['content']) -}}
                        {%- else -%}
                            {{- message['content'] | trim -}}
                        {%- endif -%}
                    {%- elif message['content'] is sequence -%}
                        {%- for item in message['content'] -%}
                            {%- if item['type'] == 'text' -%}
                                {%- if role == 'model' -%}
                                    {{- strip_thinking(item['text']) -}}
                                {%- else -%}
                                    {{- item['text'] | trim -}}
                                {%- endif -%}
                            {%- elif item['type'] == 'image' -%}
                                {{- '<|image|>' -}}
                                {%- set ns.prev_message_type = 'image' -%}
                            {%- elif item['type'] == 'audio' -%}
                                {{- '<|audio|>' -}}
                                {%- set ns.prev_message_type = 'audio' -%}
                            {%- elif item['type'] == 'video' -%}
                                {{- '<|video|>' -}}
                                {%- set ns.prev_message_type = 'video' -%}
                            {%- endif -%}
                        {%- endfor -%}
                    {%- endif -%}

                {%- if ns.prev_message_type == 'tool_call' and not ns_tr_out.flag -%}
                    {{- '<|tool_response>' -}}
                {%- elif not (ns_tr_out.flag and not message.get('content')) -%}
                    {{- '<turn|>\n' -}}
                {%- endif -%}
            {%- endif -%}
        {%- endfor -%}

        {%- if add_generation_prompt -%}
            {%- if ns.prev_message_type != 'tool_response' and ns.prev_message_type != 'tool_call' -%}
                {{- '<|turn>model\n' -}}
                {%- if not enable_thinking | default(false) -%}
                    {{- '<|channel>thought\n<channel|>' -}}
                {%- endif -%}
            {%- endif -%}
        {%- endif -%}
    """.trimIndent()

    /**
     * [GEMMA4] 去掉「关思考预填空思考段」后的版本：生成后缀只剩 `<|turn>model\n`。
     *
     * 只给**自己那份模板不带空思考段**的模型用（见 [upgradeOutdated] 的说明）。
     * 除这一段外与官方模板逐字相同 —— 由 `BuiltinChatTemplatesTest` 断言
     * （标记仍在、生成后缀回到模型原有形状、工具响应仍是官方分支）。
     */
    private val GEMMA4_NO_EMPTY_THOUGHT: String =
        GEMMA4.replace(GEMMA4_GEN_TAIL_WITH_EMPTY_THOUGHT, "        {{- '<|turn>model\\n' -}}\n")

}
