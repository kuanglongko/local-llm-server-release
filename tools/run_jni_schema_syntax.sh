#!/bin/sh
# 宿主侧对 `llama_jni.cpp` 里**坏过的几个函数**做真语法检查（g++ -fsyntax-only）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么不是"编译 llama_jni.cpp 本体"
# ═══════════════════════════════════════════════════════════════════════════
# 本体要 NDK（android/log.h、libc++、真 jni.h）。宿主上做不了，硬做只会得到一大堆
# 与真实构建无关的红，没人会看（最终被 `|| true` 掉，比没有更坏）。
#
# 但本仓库已经**真的栽过一次**：`0.9.86` 那轮 `llama_jni.cpp` 里两个 static 函数
# 定义在调用点之后、缺前置声明，编译直接报错，而这轮"102 条断言 + 41 个离线套件全绿"
# 一条都没拦住 —— 因为没有任何一条会编译这个文件。
#
# 所以这里退一步做**能做的**：把与 `response_format` 相关的几个函数体
# （`gbnf_from_json_schema` / `attach_grammar_sampler`，以及 0.9.88 改坏的渲染出口
# `new_rendered_prompt` + 它的两个依赖 `utf16_len` / `classify_rendered_think_tail`）
# 从源码里**逐字抽出来**，
# 依赖用最小桩顶替，交给 g++ 做语法检查。它能挡住的是**同形态**的错：
#   · 函数签名 / 形参表改动后与前置声明不一致；
#   · 函数体内语法错、类型错、未声明标识符。
# 它挡不住的（写在 HTP-STATUS 里，别当成已覆盖）：整文件能不能编过、模板实例化、
# 与 librnllama 的 ABI 一致性。
#
# 抽取而不是维护副本：副本会随源码漂移，那时测的是一份没人看的旧代码。
set -e
cd "$(dirname "$0")/.."

CXX=${CXX:-g++}
SRC=app/src/main/cpp/llama_jni.cpp
PY=${PYTHON:-python3}

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）—— 与既有脚本同一契约。
# 写它是因为本仓库栽过：`.cnb.yml` 里写了 REQUIRED=0，脚本却根本不求值它，
# 于是"允许退化"是一句空话（见 HTP-STATUS 第十八节）。
missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"; exit 0; fi
    echo "缺少 $1"; exit 2
}
command -v "$CXX" >/dev/null 2>&1 || missing "$CXX（本检查需要宿主 C++ 编译器）"
command -v "$PY"  >/dev/null 2>&1 || missing "python3"
[ -f "$SRC" ] || missing "$SRC"

OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
UNIT="$OUT/unit.cpp"

# 抽取脚本（唯一"知道源码形状"的地方；改函数名/签名时这里要一起改，断言会红）
"$PY" - "$SRC" "$UNIT" <<'EOF'
import sys
src = open(sys.argv[1], encoding='utf-8').read()

def grab(sig):
    """从**定义**处抓（不是前置声明处）。

    判据：签名后面 300 字符内出现 `) {`（定义）而不是 `);`（声明）。
    第一版直接在源码里 `index('static std::string gbnf_from_json_schema(')`，
    命中的是**前置声明**，抽出来的"函数体"其实是声明 —— g++ 当场报
    `declared 'static' but never defined`，正是这条检查该报的错（它自己也差点被
    当成"源码有问题"）。所以这里显式跳过声明。
    """
    i = -1
    while True:
        try:
            i = src.index(sig, i + 1)
        except ValueError:
            sys.exit("抽取失败：源码里找不到定义 `" + sig + "`（函数改名/签名改了？"
                     "本脚本与源码形状绑定，请一起更新）")
        win = src[i : i + 400]
        if ') {' in win:
            break
    j = src.index('\n}\n', i) + 3
    return src[i:j]

gbnf = grab('static schema_grammar_result gbnf_from_json_schema(const char * schemaJson, const char * tmplOverride,')
grammar = grab('static bool attach_grammar_sampler(llama_sampler * chain, const char * gbnf, const char * prefill,')
# 0.9.93：就位点判据（grammar_fit_check / looks_like_think_mismatch）也在 probe_util.h 里，
# 经 #include 一并进抽取单元 —— 它在 gbnf_from_json_schema 里被调用，编不过就红。
# 0.9.89：grammar 采样器要**预填**生成前缀（否则模型被迫把模板写好的尾巴再吐一遍）。
# 这两段是本轮新增的判定本体 —— 抽进来一起编，让"取字面量 / 算预填"改坏了在宿主侧就红。
leadlit = grab('static std::string peg_leading_literal(const common_peg_arena & arena,')
prefillfn = grab('static std::string schema_peg_leading_literal(const std::string & parserSerialized,')
# 渲染出口：本轮（0.9.88）改的就是它 —— 它原先只回传 prompt，把生成后缀丢在 native 里，
# 于是结构化输出的 grammar 与真 prompt 分叉（真机故障）。抽进来一起编，
# 让"这个函数的签名 / 拼串改坏了"能在宿主侧就红，而不是等装机。
# 抽取顺序必须与源码里的**定义顺序**一致（C++ 要求先声明后使用）：
#   utf16_len（1471 前置声明 / 1503 定义）→ new_rendered_prompt（1473）
#   → 别名 + 三档常量 + 转发（1500 之后）
#
# ⚠ `grab` 从**定义**处抓到下一个 `\n}\n`：`new_rendered_prompt` 夹在
# `utf16_len` 的前置声明与定义之间，所以抓 `utf16_len` 时不会把它带出来。
#
# 判据本体现在在 `probe_util.h`（模块 F 收口：原先三份实现、窗口单位还不一致），
# llama_jni.cpp 只剩「别名 + 三档常量 + 转发」那层包装，必须**整段一起抽**。
# utf8_safe.h 的解码设施（`utf16_len` 依赖它；长度段与发出串共用同一份 = F-1 本体）
UTF8 = '../app/src/main/cpp/utf8_safe.h'
try:
    hdr_src = open(UTF8, encoding='utf-8').read()
except IOError:
    hdr_src = open('app/src/main/cpp/utf8_safe.h', encoding='utf-8').read()

def grab_hdr(sig):
    i = hdr_src.index(sig)
    j = hdr_src.index('\n}\n', i) + 3
    return hdr_src[i:j]

utf8_enum = hdr_src[hdr_src.index('enum class Utf8Decode'):
                    hdr_src.index('\n};\n', hdr_src.index('enum class Utf8Decode')) + 4]
utf8_push = grab_hdr('static inline void utf8_push_code_unit')
utf8_dec  = grab_hdr('template <typename PushFn>\nstatic inline Utf8Decode utf8_decode_each')

utf16 = grab('static size_t utf16_len(const std::string & s) {')
rendered = grab('static jstring new_rendered_prompt(JNIEnv * env, const std::string & prompt,')
thinkshape = grab('using RenderedThinkShape = ThinkTailShape;')

head = '''// 由 tools/run_jni_schema_syntax.sh 从 llama_jni.cpp **逐字抽取**生成，勿手改。
// 依赖全部用最小桩顶替 —— 只为让这两个函数体真的过一遍编译器。
//
// PEG 类型**不自造桩**，直接 include 真头 peg-parser.h：本轮新增的判定
// （从 PEG arena 现场取 grammar 首字面量）完全依赖它的字段布局，
// 用自造桩测等于测一份我们自己写的镜像（本项目在"宿主侧镜像 native 逻辑"上栽过：
// 镜像与真实现分叉后，测试全绿而真机照错）。真头只依赖 nlohmann/json_fwd
// （仓库内已 vendor），宿主上编得动。
#include "chat/include/peg-parser.h"
#include "probe_util.h"   // grammar_prefill_from_literal + grammar_fit_check + gbnf_realign_root_literal（真机与单测共用同一份）
// utf8_safe.h：`utf16_len` 的解码器本体（模块 F 收口 —— 长度段与发出串共用同一份）。
// 它的 `new_string_utf8_safe` 依赖 jni.h，本单元已有 jstring/JNIEnv 的最小桩，
// 但签名不一致会冲突，所以这里只**转发**它提供的解码设施。
// utf8_safe.h 本体依赖 jni.h（宿主没有），所以只把里面的**解码设施**抄进来：
// 它们是 `static inline` 纯函数，与 utf8_safe.h 逐字同源（见 run_render_tail_tests.sh
// 的抽取：那边从真头文件逐字抽，能编；本单元只做语法检查，够用）。
// ⚠ 这里**不要**自造一份等价物 —— 本项目反复栽在"宿主侧镜像 native 逻辑"上。
// 之所以能接受，是因为"长度段与发出串共用同一个解码器"这条判据的**行为**断言
// 在 run_render_tail_tests.sh 里对真头文件做（50 万例随机字节），本单元只挡签名/语法。
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <cstddef>
#include <algorithm>
#include <exception>
static void jp(const char*, ...) {}
static void jlog(const char*, ...) {}
// escape_for_probe 不再自造桩：probe_util.h（真机与单测共用）已经提供，
// 自造会与它构成重载歧义（本脚本第一次跑就是这么红的 —— 宁可红，也不要
// 一个"看起来编过了"的假绿）。
// 抽取单元里 gbnf_from_json_schema 的返回类型：真源码里定义在 llama_jni.cpp 的
// 前置声明块（本轮新增）。这里逐字复刻它的字段，否则抽出来的函数体不认识这个类型。
struct schema_grammar_result {
    std::string gbnf;
    std::string prefill;
    std::string pegLiteral;
    std::string pegWhy;
};
typedef int llama_token;
static int llama_tokenize(const void*, const char*, int, llama_token*, int, bool, bool) { return 0; }
static std::string token_to_piece(llama_token) { return ""; }
// 渲染出口用到的三个最小桩（jstring 在真源码里是 JNI 类型）。
typedef void* jstring;
typedef void* JNIEnv;
typedef unsigned short jchar;   // 真机是 jni.h 的 jchar = uint16_t（utf8_safe.h 用到）
static jstring new_string_utf8_safe(JNIEnv*, const char*) { return nullptr; }
// 判据本体（probe_util.h 的 ThinkTailShape + classify_think_tail）已在抽取片段里
// 经 #include "probe_util.h" 进来了 —— **不要**在这里另造桩：自造会与真定义冲突
// （第一次跑就是这么红的），而且"自造一份等价物"本身就是本项目反复栽的那类错。
// 这里只补 llama_jni.cpp 自己那层的别名与三档常量。
static const char * const kRenderedPromptLeadIn  = "I";
static const char * const kRenderedPromptLeadOut = "O";
static const size_t kRenderedPromptSuffixHexLen = 8;
struct llama_sampler;
static llama_sampler * llama_sampler_init_grammar(const void*, const char*, const char*) { return nullptr; }
static void llama_sampler_chain_add(llama_sampler*, llama_sampler*) {}
static void llama_sampler_accept(llama_sampler*, llama_token) {}
// 错位段推进用到的两个桩（0.9.96）：clone 试喂 + LLAMA_TOKEN_NULL 哨兵。
// 真源码里的用法是"克隆一个、试喂一个字节、不行就丢克隆体"，所以这里只需给出
// 一个可判空的最小手势 —— 语法检查看的正是"这些调用有没有写对"。
static llama_sampler * llama_sampler_clone(const llama_sampler*) { return nullptr; }
static const llama_token LLAMA_TOKEN_NULL = -1;
static void llama_sampler_free(llama_sampler*) {}
namespace s_impl {
struct S_t { void* vocab = nullptr; void* model = nullptr; };
static S_t S;
}
using s_impl::S;
struct common_chat_templates;
namespace ptr_impl {
template<class T> struct ptr_t {
    T* p = nullptr;
    T* get() const { return p; }
    explicit operator bool() const { return p != nullptr; }   // 源码里用 `if (!tmpls)`
};
}
using common_chat_templates_ptr = ptr_impl::ptr_t<common_chat_templates>;
struct common_chat_templates_inputs {
    bool use_jinja = true;
    bool add_generation_prompt = true;
    bool enable_thinking = true;
    std::string json_schema;
};
struct common_chat_params {
    std::string grammar;
    std::string generation_prompt;
    std::string parser;   // PEG 的序列化串：grammar 首字面量从这里取（本轮新增）
    bool grammar_lazy = false;
    std::vector<std::string> grammar_triggers;
};
static common_chat_templates_ptr common_chat_templates_init(void*, const std::string&) { return {}; }
static common_chat_params common_chat_templates_apply(common_chat_templates*, const common_chat_templates_inputs&) { return {}; }
'''
# 顺序必须与源码里的**定义顺序**一致：schema_grammar_prefill / peg_leading_literal
# 定义在 gbnf_from_json_schema 之前（真源码里也一样），反过来 C++ 直接
# "was not declared in this scope" —— 这正是本脚本要挡的那类形态。
open(sys.argv[2], 'w', encoding='utf-8').write(
    head + leadlit + '\n' + prefillfn + '\n' + gbnf + '\n' + grammar + '\n'
    + utf8_enum + '\n' + utf8_push + '\n' + utf8_dec + '\n' + utf16 + '\n'
    + thinkshape + '\n' + rendered + '\n')
EOF

# -Werror：这里只编两个函数，警告等于信号（真机上被埋在几千行里的时候不是）。
"$CXX" -std=c++17 -fsyntax-only -Wall -Wextra -Werror -Wno-unused-function \
    -I app/src/main/cpp -I app/src/main/cpp/chat/include "$UNIT"

echo "=== JNI 结构化输出函数语法检查：PASS（抽取单元 $(basename "$UNIT")）==="
