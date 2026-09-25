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
//   参数含 std::string / std::vector / nlohmann::ordered_json：
//
//     · mangled name 由 STL 决定（libc++ → NSt6__ndk1…，libstdc++ → NSt7__cxx11…）
//     · 结构体字段偏移同样由 STL 决定
//
//   扫过 v1.12.2 的 4 个变体：__ndk1 符号 2413~2415 个，__cxx11 **0** 个。
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
// ① 仍然 include chat.h —— 因为**头必须与 .so 同源**：这里正是 vendor v1.12.2
//   配套的裁剪头（json 3.12.0 与库的 json_abi_v3_12_0 对得上）。绕开它自己
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

// ── 护栏 3：nlohmann json 版本必须与库的 json_abi_v3_12_0 一致 ────────────
// 库导出的 symbol 里写着 json_abi_v3_12_0；版本不一致时 namespace 会不同，
// common_chat_tools_parse_oaicompat 的 mangled name 也就对不上。
#if !defined(NLOHMANN_JSON_VERSION_MAJOR)
#error "chat_abi.h: chat.h 没有把 nlohmann/json.hpp 带进来，头文件不完整"
#endif
static_assert(NLOHMANN_JSON_VERSION_MAJOR == 3 &&
              NLOHMANN_JSON_VERSION_MINOR == 12 &&
              NLOHMANN_JSON_VERSION_PATCH == 0,
              "nlohmann json 版本不是 3.12.0 —— 与 librnllama*.so 里的 json_abi_v3_12_0 不符，"
              "common_chat_tools_parse_oaicompat 会因 namespace 不同而链接失败");

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

// ── 护栏 5：常用成员类型尺寸 ────────────────────────────────────────────
static_assert(sizeof(std::vector<std::string>)             == 24, "vector<string> 尺寸异常");
static_assert(sizeof(std::map<std::string, std::string>)   == 24, "map<string,string> 尺寸异常");
static_assert(sizeof(std::chrono::system_clock::time_point) == 8,  "time_point 尺寸异常");

// ── 运行时自检：把上面几条"编译期钉不住"的部分在启动时验一遍 ──────────────
// 返回所有失败项的说明（空字符串 = 全通过）。
// 调用点：backendInit() 之后打一次，进日志，便于远程排查。
const char * self_check();

} // namespace abi
