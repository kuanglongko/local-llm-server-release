// chat_abi.h — 与 librnllama*.so 之间的 ABI 边界：**只剩纯标量**
//
// ═══════════════════════════════════════════════════════════════════════════
// 这个文件存在的唯一理由：把 PR #13 那个"带 tools 必闪退"的根因封死
// ═══════════════════════════════════════════════════════════════════════════
// 背景（证据见 tools/check_abi.py 与 PR #13 评论）：
//
//   e31cea8 起，llama_jni.cpp 直接 #include "chat/include/chat.h" 并调用
//   common_chat_templates_apply / common_chat_parse / common_chat_tools_parse_oaicompat。
//   这几个函数在 vendor 的 librnllama*.so 里是**带 C++ ABI 的符号**，
//   参数含 std::string / std::vector / common_json（v1.13.0 起取代
//   nlohmann::ordered_json 成为跨边界类型）：
//
//     · mangled name 由 STL 决定（libc++ → NSt6__ndk1…，libstdc++ → NSt7__cxx11…）
//     · 结构体字段偏移同样由 STL 决定
//
//   扫过 v1.13.0 的变体：C++ 符号仍是 __ndk1，__cxx11 **0** 个。
//   库是 libc++ 编的，而且 _LIBCPP_ABI_VERSION == 1（NDK 默认）——
//   也就是 libc++ 的**非** alternate-string-layout：std::string 24B、SSO 22 字节。
//
// 所以只要 llama_jni.cpp 在 NDK 下（-DANDROID_STL=c++_static → libc++）编译，
// 传进来的 common_chat_templates_inputs 布局就和库内部**完全一致**，
// 上面那三个函数是可以正常调用的。真正要防的是**"看起来能编过"**：
//
//   · 宿主 g++（libstdc++）编同一个 .cpp：符号变成 St7__cxx11，库里没有 →
//     链接期缺失（本项目有 -Wl,--no-undefined，会直接失败，属"好失败"）；
//   · 但只要有人把 --no-undefined 去掉、或换了头文件，就会变成"链上了但布局不同"
//     → 库按自己的偏移读我们传的引用 → 野指针 → SIGSEGV/SIGABRT，
//       崩在库内部，日志里连一行遗言都没有（真机日志正是这个现象）。
//
// ═══════════════════════════════════════════════════════════════════════════
// 本文件的策略
// ═══════════════════════════════════════════════════════════════════════════
// ① 仍然 include chat.h —— 因为**头必须与 .so 同源**：这里正是 vendor v1.13.0
//   配套的裁剪头（common_json 的 32B pimpl 布局与库对得上）。绕开它自己
//    做一份影子声明，等于把"两边同源"这个唯一保证扔掉。
// ② 真正加的是**编译期护栏**：下面每条 static_assert 都钉住一个具体事实。
//    任何一条不成立，就说明"头与库不同源"或"STL 不是库那套"，
//    编译立即失败 —— 把 ABI 问题从运行时 SIGSEGV 提前成编译期报错。
// ③ 再加**运行时护栏**：apply 之前先确认模板确实生成了 PEG 解析器，
//    否则返回 null 走纯文本，绝不让异常/abort 穿过 JNI 帧。
//
// 想改这个文件前先读第 ① 条：不要试图"解耦 chat.h"，
// 那份解耦本身就是本次故障的成因。
#pragma once

#include <cstddef>
#include <string>
#include <type_traits>
#include <vector>

// 唯一允许 include 的 vendor 头。路径与 CMakeLists 的 include 目录一致。
#include "chat/include/chat.h"

// v1.13.0 起跨边界 JSON 类型是 common_json（pimpl），nlohmann 只在本仓库
// 内部拼装输出 JSON 时使用，不再参与库的 ABI。仍然显式带上并钉版本，
// 免得哪天本地序列化行为随头文件悄悄变化。
#include "nlohmann/json.hpp"

namespace abi {

// ── 护栏 1：必须是 libc++，且是 ABI v1（非 alternate layout） ──────────────
// 库里全是 NSt6__ndk1（= libc++ __1）。用 libstdc++ 编 = 符号和布局双不匹配。
#if defined(__GLIBCXX__)
#error "chat_abi.h: 本文件必须用 libc++（NDK）编译。检测到 libstdc++：符号会是 St7__cxx11，与 librnllama*.so 的 NSt6__ndk1 不匹配，链接期缺失或运行时布局错位。"
#endif
#if !defined(_LIBCPP_VERSION)
#error "chat_abi.h: 未检测到 libc++。请确认 CMake 的 -DANDROID_STL=c++_static 与 NDK 工具链生效。"
#endif

// ── 护栏 2：std::string 必须是 24B / SSO 22 ──────────────────────────────
// 这两条同时钉住"libc++ ABI v1"，也间接钉住"头与库同源"：
// 若哪天有人把 _LIBCPP_ABI_VERSION 提到 2（alternate layout），
// string 的数据会挪到 offset 0，sizeof 仍 24 但布局变了，库会读错。
static_assert(sizeof(std::string) == 24,
              "std::string 不是 24B：说明 STL 或 ABI 版本与 librnllama*.so 不一致");
static_assert(std::is_trivially_destructible<std::string>::value == false, "健全性检查");

// libc++ 的短串长度存在最后 1 字节；alternate layout 下存在第 1 字节。
// 用一条只在 ABI v1 成立的探针：把 string 拷进缓冲区后按 ABI v1 语义读回。
// （编译期无法直接取布局，故用运行期断言，见 abi::self_check()。）

// ── 护栏 3：跨边界的 JSON 类型必须是 common_json 的 32B pimpl ─────────────
// v1.13.0 的 chat.h 用 common_json 代替了 nlohmann::ordered_json。它的大小/
// 对齐是库与头之间的硬约定；nlohmann 只在本仓库内部用来拼输出串。
static_assert(sizeof(common_json) == 32, "common_json 不是 32B：与 librnllama*.so 的 pimpl 布局不一致");
static_assert(alignof(common_json) == 8, "common_json 对齐不是 8B");
#if !defined(NLOHMANN_JSON_VERSION_MAJOR)
#error "chat_abi.h: 没有把 nlohmann/json.hpp 带进来（本地输出 JSON 需要它）"
#endif
static_assert(NLOHMANN_JSON_VERSION_MAJOR == 3 &&
              NLOHMANN_JSON_VERSION_MINOR == 12 &&
              NLOHMANN_JSON_VERSION_PATCH == 0,
              "nlohmann json 版本不是 3.12.0 —— 本地输出 JSON 的口径变了，请核对后更新");

// ── 护栏 4：逐字段钉住 common_chat_templates_inputs 的布局 ────────────────
// 这是**真正被跨边界传引用的结构体**，也是"布局错位→野指针"的现场。
// 每个字段的偏移和总尺寸都写死；上游加字段/改顺序会立刻编不过。
static_assert(offsetof(common_chat_templates_inputs, messages)               == 0,   "inputs.messages 偏移变了：头与 librnllama*.so 不同源");
static_assert(offsetof(common_chat_templates_inputs, grammar)                == 24,  "inputs.grammar 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, json_schema)            == 48,  "inputs.json_schema 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, add_generation_prompt)  == 72,  "inputs.add_generation_prompt 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, continue_final_message) == 76,  "inputs.continue_final_message 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, use_jinja)              == 80,  "inputs.use_jinja 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, tools)                  == 88,  "inputs.tools 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, tool_choice)            == 112, "inputs.tool_choice 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, parallel_tool_calls)    == 116, "inputs.parallel_tool_calls 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, reasoning_format)       == 120, "inputs.reasoning_format 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, enable_thinking)        == 124, "inputs.enable_thinking 偏移变了");

// ⚠ 上面这条断言只保证「字段位置对」，**钉不住"有没有给它赋值"**：
//     enable_thinking 是 C++ 默认 true 的 bool，不赋值 = 恒为"思考开"，
//     编译期、运行期都不报错 —— 真机表现是「勾了默认关闭思考也没用」。
//     2026-09-19 就是踩在这里：MiniCPM5 模板按它决定生成后缀吐不吐 "<think>\n"，
//     而 llama_jni.cpp 三处 common_chat_templates_inputs 此前一处都没设过它。
//     现在三处（无 tools 渲染 / 带 tools 渲染 / 解析）都必须显式赋值，
//     由 tools/run_thinking_tests.sh 的源码级断言钉死。
static_assert(offsetof(common_chat_templates_inputs, now)                    == 128, "inputs.now 偏移变了（此字段占 8B，其后才是 chat_template_kwargs）");
static_assert(offsetof(common_chat_templates_inputs, chat_template_kwargs)   == 136, "inputs.chat_template_kwargs 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, add_bos)                == 160, "inputs.add_bos 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, add_eos)                == 161, "inputs.add_eos 偏移变了");
static_assert(offsetof(common_chat_templates_inputs, force_pure_content)     == 162, "inputs.force_pure_content 偏移变了");
static_assert(sizeof(common_chat_templates_inputs) == 168,
              "common_chat_templates_inputs 总尺寸变了 —— 上游字段增删。"
              "请重新逐字段核对本文件与 chat.h，再更新断言，不要只改数字。");

// ── 护栏 6：v1.13.0 新增/改动的跨边界结构体逐字段钉住 ─────────────────────
// common_chat_msg 增加了 extra_json（DeepSeek V4.1 的非 OpenAI 键），
// common_chat_params.prompt 从 std::string 变成 common_chat_prompt、
// parser 从序列化串变成 common_peg_arena —— 这三处都是 1.13.0 的 ABI 断点。
static_assert(sizeof(common_chat_msg_content_part) == 104, "content_part 尺寸变了");
static_assert(offsetof(common_chat_msg_content_part, type)         == 0,   "content_part.type 偏移变了");
static_assert(offsetof(common_chat_msg_content_part, text)         == 24,  "content_part.text 偏移变了");
static_assert(offsetof(common_chat_msg_content_part, name)         == 48,  "content_part.name 偏移变了");
static_assert(offsetof(common_chat_msg_content_part, extra_fields) == 72,  "content_part.extra_fields 偏移变了");

static_assert(sizeof(common_chat_msg) == 192, "common_chat_msg 尺寸变了");
static_assert(offsetof(common_chat_msg, role)              == 0,   "msg.role 偏移变了");
static_assert(offsetof(common_chat_msg, content)           == 24,  "msg.content 偏移变了");
static_assert(offsetof(common_chat_msg, content_parts)     == 48,  "msg.content_parts 偏移变了");
static_assert(offsetof(common_chat_msg, tool_calls)        == 72,  "msg.tool_calls 偏移变了");
static_assert(offsetof(common_chat_msg, reasoning_content) == 96,  "msg.reasoning_content 偏移变了");
static_assert(offsetof(common_chat_msg, tool_name)         == 120, "msg.tool_name 偏移变了");
static_assert(offsetof(common_chat_msg, tool_call_id)      == 144, "msg.tool_call_id 偏移变了");
static_assert(offsetof(common_chat_msg, extra_json)        == 168, "msg.extra_json 偏移变了");

static_assert(sizeof(common_chat_tool) == 80, "common_chat_tool 尺寸变了");
static_assert(offsetof(common_chat_tool, defer_loading) == 72, "tool.defer_loading 偏移变了");

static_assert(sizeof(common_chat_prompt_part) == 32, "prompt_part 尺寸变了");
static_assert(offsetof(common_chat_prompt_part, text)     == 0,  "prompt_part.text 偏移变了");
static_assert(offsetof(common_chat_prompt_part, is_input) == 24, "prompt_part.is_input 偏移变了");
static_assert(sizeof(common_chat_prompt) == 24, "common_chat_prompt 尺寸变了");

static_assert(sizeof(common_chat_msg_delimiter) == 56, "msg_delimiter 尺寸变了");
static_assert(offsetof(common_chat_msg_delimiter, role)      == 0,  "delimiter.role 偏移变了");
static_assert(offsetof(common_chat_msg_delimiter, delimiter) == 8,  "delimiter.delimiter 偏移变了");
static_assert(offsetof(common_chat_msg_delimiter, tokens)    == 32, "delimiter.tokens 偏移变了");
static_assert(sizeof(common_chat_msg_delimiters) == 24, "msg_delimiters 尺寸变了");

static_assert(sizeof(common_chat_params) == 312, "common_chat_params 尺寸变了");
static_assert(offsetof(common_chat_params, format)            == 0,   "params.format 偏移变了");
static_assert(offsetof(common_chat_params, prompt)            == 8,   "params.prompt 偏移变了（v1.13.0 起是 common_chat_prompt）");
static_assert(offsetof(common_chat_params, grammar)           == 32,  "params.grammar 偏移变了");
static_assert(offsetof(common_chat_params, grammar_lazy)      == 56,  "params.grammar_lazy 偏移变了");
static_assert(offsetof(common_chat_params, generation_prompt) == 64,  "params.generation_prompt 偏移变了");
static_assert(offsetof(common_chat_params, supports_thinking) == 88,  "params.supports_thinking 偏移变了");
static_assert(offsetof(common_chat_params, thinking_start_tag) == 96, "params.thinking_start_tag 偏移变了");
static_assert(offsetof(common_chat_params, thinking_end_tags) == 120, "params.thinking_end_tags 偏移变了");
static_assert(offsetof(common_chat_params, grammar_triggers)  == 144, "params.grammar_triggers 偏移变了");
static_assert(offsetof(common_chat_params, preserved_tokens)  == 168, "params.preserved_tokens 偏移变了");
static_assert(offsetof(common_chat_params, additional_stops)  == 192, "params.additional_stops 偏移变了");
static_assert(offsetof(common_chat_params, parser)            == 216, "params.parser 偏移变了（v1.13.0 起是 common_peg_arena）");
static_assert(offsetof(common_chat_params, message_delimiters) == 288, "params.message_delimiters 偏移变了");

static_assert(sizeof(common_chat_input) == 48, "common_chat_input 尺寸变了");
static_assert(offsetof(common_chat_input, text)   == 0,  "input.text 偏移变了");
static_assert(offsetof(common_chat_input, tokens) == 24, "input.tokens 偏移变了");

static_assert(sizeof(common_chat_parser_params) == 136, "common_chat_parser_params 尺寸变了");
static_assert(offsetof(common_chat_parser_params, format)            == 0,  "parser_params.format 偏移变了");
static_assert(offsetof(common_chat_parser_params, generation_prompt) == 8,  "parser_params.generation_prompt 偏移变了");
static_assert(offsetof(common_chat_parser_params, debug)             == 56, "parser_params.debug 偏移变了");
static_assert(offsetof(common_chat_parser_params, parser)            == 64, "parser_params.parser 偏移变了");

// ── 护栏 5：常用成员类型尺寸 ────────────────────────────────────────────
static_assert(sizeof(std::vector<std::string>)             == 24, "vector<string> 尺寸异常");
static_assert(sizeof(std::map<std::string, std::string>)   == 24, "map<string,string> 尺寸异常");
static_assert(sizeof(std::chrono::system_clock::time_point) == 8,  "time_point 尺寸异常");

// ── 运行时自检：把上面几条"编译期钉不住"的部分在启动时验一遍 ──────────────
// 返回所有失败项的说明（空字符串 = 全通过）。
// 调用点：backendInit() 之后打一次，进日志，便于远程排查。
const char * self_check();

} // namespace abi
