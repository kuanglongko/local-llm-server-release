// probe_util.h — 探针里那几段「纯字符串 / 纯数据」的工具逻辑
//
// ═══════════════════════════════════════════════════════════════════════════
// 这个文件为什么存在
// ═══════════════════════════════════════════════════════════════════════════
// 本机没有 NDK。"generation_prompt 要不要改写、往哪改写" 这条判定是**本轮修复的
// 核心**，而它此前只写在 llama_jni.cpp 里 —— 那份文件在宿主上连编译都过不去
// （jni.h / llama.h / chat.h 全是 NDK 与真机的），于是这条判定只能靠 review
// 肉眼保证，没有测试能兜住。而它一旦写错，后果正是本项目反复踩的那类故障：
// **不崩、不报错，只是 tool_calls 恒为 0**。
//
// 所以把里面**不依赖真机 SDK** 的三段摘出来放这里：
//   1. escape_for_probe           —— 控制字符转义（日志里区分 "\n" 在哪）
//   2. probe_root_literal         —— 从 PEG arena 现场取根节点字面量
//   3. align_generation_prompt    —— 把 generation_prompt 对齐到根节点前缀
//
// 真机（llama_jni.cpp）与单测（tools/run_root_literal_probe_tests.sh）**共用同一份
// 实现**：单测跑的就是真机编进去的那个函数，不是抄一份相似逻辑。这样"根节点取法"
// 和"对齐规则"才有断言兜底，不靠人眼。
//
// 第 2 段要用到 PEG 的两个字段名（literal / get / root），真机上是 chat.h +
// peg-parser.h 里的真类型（模板参数决定），单测里传等价的小类型 —— 字段名与
// peg-parser.h 逐字一致（见 tools/run_root_literal_probe_tests.sh 里的对照）。
#pragma once

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <variant>

// 把控制字符转义成可见形式（\n -> 字面 \n、\t -> \t），并截断到 max 字节。
// 为什么需要它：generation_prompt / effective_input 这类字符串里**关键的区分点就是
// 换行在哪、有几个**。直接原样打进探针会真换行，日志里看着像两行、分不清是内容还是
// 分隔；而且 `<|im_start|>assistant\n<think>\n` 这种"多了一段尾巴"的差别肉眼极易漏。
// 转义后 `...\n<think>\n` 一眼可见，这正是上轮排查缺的那一行。
static inline std::string escape_for_probe(const std::string & in, size_t maxBytes) {
    std::string out;
    out.reserve(in.size() + 8);
    for (unsigned char c : in) {
        if (out.size() >= maxBytes) { out += "...(截断)"; break; }
        switch (c) {
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof(b), "\\x%02x", c);
                    out += b;
                } else {
                    out += (char) c;
                }
        }
    }
    return out;
}

// ══════════════════════════════════════════════════════════════════════════
// 从 PEG arena 里取「根节点字面量」
// ══════════════════════════════════════════════════════════════════════════
// 为什么必须是"现场取"而不是写死：解析侧要做的唯一一件事是「让输入以 PEG 根节点
// 要求的那个前缀开头」。写死 "<|im_start|>assistant\n" 等于把这条路绑死在
// MiniCPM5/Qwen 一家上 —— 换个模板（llama 用 <|start_header_id|>、gemma 又不同）
// 就静默失效，正是本项目反复踩的那类"不崩但不干活"。
//
// 取不到的一律视为"非字面量根"，调用方据此**不做任何改写**，按原样交给库：
//   · 根节点不是 literal（sequence / choice / rule / 前缀包装…）
//   · 下标越界或 arena 内数据不自洽
//   · literal 是空串（空串前缀对齐没有意义，改了反而多一次风险）
//
// 取的时候**只读**，不写回 arena：arena 由库持有，改它等于改库的内部状态。
struct root_literal_probe_result {
    bool        isLiteral = false;   // 根节点是 literal 且字面量非空
    bool        taken     = false;   // "取不到"（越界/抛异常），而非"取到但不是 literal"
    std::string literal;             // 取到的字面量（isLiteral 时有效）
    std::string why;                 // 取不到的原因，进日志用
};

template <typename ArenaT, typename LiteralParserT>
static inline root_literal_probe_result probe_root_literal(const ArenaT & arena) {
    root_literal_probe_result r;
    // 调用点已判过 arena.empty()；这里再兜一次，避免 get() 在空 arena 上被调。
    try {
        const auto & rootNode = arena.get(arena.root());
        if (const auto * lit = std::get_if<LiteralParserT>(&rootNode)) {
            r.literal = lit->literal;
            if (r.literal.empty()) {
                r.why = "root_literal 为空串";
            } else {
                r.isLiteral = true;
            }
        } else {
            r.why = "根节点不是 literal";
        }
    } catch (const std::exception & e) {
        r.taken = true;
        r.why   = std::string("取根节点抛异常: ") + e.what();
    } catch (...) {
        r.taken = true;
        r.why   = "取根节点抛未知异常";
    }
    return r;
}

// ══════════════════════════════════════════════════════════════════════════
// 把 generation_prompt 对齐到 PEG 根节点要的前缀（纯字符串运算，可单测）
// ══════════════════════════════════════════════════════════════════════════
// 背景：common_chat_parse 会**自己**把 params.generation_prompt 前拼到输入上：
//     effective_input = params.generation_prompt + input            （上游 chat.cpp）
// 而 PEG 根节点是一个字面量。库要求输入以这个字面量开头，两者不一致时根节点一上来
// 就匹配不上，PEG 回退成"整段都是 content" —— tool_calls 恒为 0，不报错、不崩溃。
//
// 对齐只做一件事：**保证 effective_input 以根节点字面量开头**。判据也只有一条
// —— generation_prompt 是不是以根节点字面量为前缀。两种情形：
//
//   ① 是前缀（generation_prompt = 字面量 + 尾巴）：
//        把尾巴前移到输入最前面，generation_prompt 收成裸字面量。
//        于是 effective_input = 字面量 + 尾巴 + text，与原前缀拼接**逐字节等价**，
//        一个字节不丢、不增。MiniCPM5 就是这条：尾巴是模板的 "<think>\n"。
//
//   ② 不是前缀（两者是两个不同的东西，形状不符）：
//        **不动 generation_prompt**，只把字面量前拼到输入最前面，
//        让 effective_input 同样以字面量开头。保守做法的关键好处是**不改库的语义**：
//        库要输入以这个前缀开头，那就补上；而不是替库改写它自己算出的量。
//
// 判据③ 已经相等 —— 原样返回，不做任何改写（这条是"别引入新问题"的底线：
// 已经对齐的输入必须一个字节都不动）。
struct gen_prompt_align_result {
    std::string generation_prompt;  // 交给 pp.generation_prompt 的值
    std::string input;              // 交给 common_chat_parse 的输入
    std::string patched;            // 前拼进输入的那段（日志判定用）
    bool        changed = false;    // 是否改过库给的 generation_prompt
    int         mode    = 0;        // 0=已对齐未改写 1=情形① 2=情形②
};

static inline gen_prompt_align_result align_generation_prompt(
        const std::string & genPrompt, const std::string & rootLiteral, const std::string & text) {
    gen_prompt_align_result r;
    r.generation_prompt = genPrompt;
    r.input             = text;
    if (rootLiteral.empty()) return r;                  // 没有可信字面量 -> 不改写

    if (genPrompt == rootLiteral) return r;             // ③ 已经对齐 -> 一个字节都不动

    if (genPrompt.size() > rootLiteral.size() &&
        genPrompt.compare(0, rootLiteral.size(), rootLiteral) == 0) {
        // ① 字面量 + 尾巴：尾巴前移，等价改写
        r.patched           = genPrompt.substr(rootLiteral.size());
        r.generation_prompt = rootLiteral;
        r.input             = r.patched + text;
        r.changed           = true;
        r.mode              = 1;
        return r;
    }
    // ② 形状不符：generation_prompt 原样保留，只把字面量补进输入
    r.patched = rootLiteral;
    r.input   = rootLiteral + text;
    r.changed = false;
    r.mode    = 2;
    return r;
}
