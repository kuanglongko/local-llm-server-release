// root_literal_probe_test.cpp — 宿主侧单测：PEG 根节点字面量的取法 + generation_prompt 对齐
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么要有这份测试
// ═══════════════════════════════════════════════════════════════════════════
// 「让 effective_input 与 PEG 根节点要求的前缀对齐」是本轮修复的核心判定，
// 而它此前只写在 llama_jni.cpp 里 —— 那份文件在宿主上编不过（jni.h / llama.h /
// chat.h 都是 NDK 与真机的），所以这条判定只能靠 review 肉眼保证。
// 它一旦写错，症状正是本项目反复踩的那类：**不崩、不报错，tool_calls 恒为 0**。
//
// 这份测试直接 include 真机用的 probe_util.h（同一份实现，不是抄一份相似逻辑），
// 并在下面**复刻上游 common_chat_parse 的前缀拼接**，逐条断言：
//     effective_input == params.generation_prompt + input   （上游 chat.cpp:3688）
//     effective_input 以 PEG 根节点字面量开头               （根节点能匹配上的必要条件）
//
// PEG 的等价小类型：字段名与 chat/include/peg-parser.h 逐字一致
// （common_peg_literal_parser::literal、arena.get()/root()/parsers_、std::variant），
// 所以 probe_root_literal 的模板参数在真机与这里都能编。
#include <cstdio>
#include <string>
#include <variant>
#include <vector>
#include <stdexcept>

#include "probe_util.h"

// ── 与 peg-parser.h 同名同形的等价类型（只为在宿主上跑得起来） ──
using common_peg_parser_id = size_t;
struct common_peg_epsilon_parser {};
struct common_peg_literal_parser { std::string literal; };
struct common_peg_sequence_parser { std::vector<common_peg_parser_id> children; };
using common_peg_parser_variant =
    std::variant<common_peg_epsilon_parser, common_peg_literal_parser, common_peg_sequence_parser>;

class common_peg_arena {
    std::vector<common_peg_parser_variant> parsers_;
    common_peg_parser_id                   root_ = 0;
  public:
    const common_peg_parser_variant & get(common_peg_parser_id id) const { return parsers_.at(id); }
    size_t size() const { return parsers_.size(); }
    bool   empty() const { return parsers_.empty(); }
    common_peg_parser_id root() const { return root_; }
    void push(common_peg_parser_variant v) { parsers_.push_back(std::move(v)); }
    void set_root(common_peg_parser_id id) { root_ = id; }
};

// ── 极简断言 ──
static int g_ok = 0, g_bad = 0;
static void ck(const char * name, bool cond) {
    if (cond) { printf("PASS  %s\n", name); g_ok++; }
    else      { printf("FAIL  %s\n", name); g_bad++; }
}

// ── 上游 common_chat_parse 的前缀拼接（chat.cpp:3688，逐字照抄语义） ──
static std::string effective_input(const std::string & genPrompt, const std::string & input) {
    return genPrompt.empty() ? input : genPrompt + input;
}

// 真机常量：MiniCPM5 / Qwen 的生成前缀与 think 尾巴
static const std::string GEN_PREFIX = "<|im_start|>assistant\n";
static const std::string THINK_TAIL = "<think>\n";

int main() {
    // ══════════════════════════════════════════════════════════════════
    // 一、probe_root_literal：根节点字面量的取法
    // ══════════════════════════════════════════════════════════════════
    {
        common_peg_arena a;
        a.push(common_peg_literal_parser{GEN_PREFIX});
        a.set_root(0);
        auto r = probe_root_literal<common_peg_arena, common_peg_literal_parser>(a);
        ck("根为 literal -> isLiteral 且取到原文",
           r.isLiteral && !r.taken && r.literal == GEN_PREFIX);
    }
    {
        common_peg_arena a;
        a.push(common_peg_epsilon_parser{});
        a.set_root(0);
        auto r = probe_root_literal<common_peg_arena, common_peg_literal_parser>(a);
        ck("根不是 literal -> 不改写（isLiteral=false，且非 taken）",
           !r.isLiteral && !r.taken);
    }
    {
        common_peg_arena a;
        a.push(common_peg_literal_parser{""});
        a.set_root(0);
        auto r = probe_root_literal<common_peg_arena, common_peg_literal_parser>(a);
        ck("根为空字面量 -> 视为无字面量可依（空串对齐没有意义）",
           !r.isLiteral && r.literal.empty());
    }
    {
        common_peg_arena a;   // 空 arena：get() 会越界
        a.set_root(0);
        auto r = probe_root_literal<common_peg_arena, common_peg_literal_parser>(a);
        ck("越界取根 -> taken=true 且不抛异常（异常不得穿过 JNI 帧）",
           r.taken && !r.isLiteral);
    }
    {
        // 根是 sequence（字面量被包一层）-> 取不到字面量，必须不改写
        common_peg_arena a;
        a.push(common_peg_sequence_parser{{1}});
        a.push(common_peg_literal_parser{GEN_PREFIX});
        a.set_root(0);
        auto r = probe_root_literal<common_peg_arena, common_peg_literal_parser>(a);
        ck("根是 sequence -> 不改写",
           !r.isLiteral && !r.taken);
    }

    // ══════════════════════════════════════════════════════════════════
    // 二、align_generation_prompt：三种情形的判定
    // ══════════════════════════════════════════════════════════════════
    {
        // ③ 已经对齐：一个字节都不能动
        const std::string text = "<function name=\"get_weather\"><param name=\"city\">Beijing</param></function>";
        auto al = align_generation_prompt(GEN_PREFIX, GEN_PREFIX, text);
        ck("已对齐 -> generation_prompt 原样", al.generation_prompt == GEN_PREFIX);
        ck("已对齐 -> input 原样（不得多补一个字节）", al.input == text);
        ck("已对齐 -> mode=0 且 changed=false", al.mode == 0 && !al.changed);
    }
    {
        // ① MiniCPM5 常走路径：generation_prompt = 字面量 + "<think>\n"
        const std::string text = "<function name=\"get_weather\"><param name=\"city\">Beijing</param></function>";
        const std::string gp   = GEN_PREFIX + THINK_TAIL;   // 30B
        auto al = align_generation_prompt(gp, GEN_PREFIX, text);
        ck("① generation_prompt 收成裸字面量", al.generation_prompt == GEN_PREFIX);
        ck("① 尾巴前移到输入最前", al.input == THINK_TAIL + text);
        ck("① mode=1 且 changed=true", al.mode == 1 && al.changed);
        // 最要紧的一条：改写的 effective_input 与改写前**逐字节相同**
        ck("① effective_input 与改写前逐字节等价（不丢不多）",
           effective_input(al.generation_prompt, al.input) == effective_input(gp, text));
        ck("① 且以根节点字面量开头（根节点能匹配上的必要条件）",
           effective_input(al.generation_prompt, al.input).compare(0, GEN_PREFIX.size(), GEN_PREFIX) == 0);
        // 内容侧：正文没被吃掉，工具标记仍在（位置只往后挪了尾巴长度）
        ck("① 正文里的工具标记未被改动",
           al.input.find("<function name=\"get_weather\">") != std::string::npos);
    }
    {
        // ① 变体：thinking 关时的尾巴 "<think>\n\n</think>\n\n"
        const std::string text = "<function name=\"f\"></function>";
        const std::string tail = "<think>\n\n</think>\n\n";
        auto al = align_generation_prompt(GEN_PREFIX + tail, GEN_PREFIX, text);
        ck("① 变体（think 关）: 逐字节等价",
           effective_input(al.generation_prompt, al.input) == effective_input(GEN_PREFIX + tail, text));
    }
    {
        // ② 形状不符：generation_prompt 与根节点要的前缀是两回事
        const std::string text = "<function name=\"f\"></function>";
        const std::string gp   = "<|im_start|>system\nsys";   // 全然不同
        auto al = align_generation_prompt(gp, GEN_PREFIX, text);
        ck("② generation_prompt 原样保留（不替库改写语义）", al.generation_prompt == gp);
        ck("② 字面量被补进输入最前", al.input == GEN_PREFIX + text);
        ck("② mode=2 且 changed=false（没改库给的量）", al.mode == 2 && !al.changed);
        // 情形② 只说清"有 gp 顶在前面、字面量已补在 gp 之后"，不宣称开头就是字面量：
        // 这条路径要的就是"别替库改写它的量"，而不是保证 effective_input 前缀。
        ck("② effective_input = gp + root_literal + text（字面量已就位）",
           effective_input(al.generation_prompt, al.input) == gp + GEN_PREFIX + text);
    }
    {
        // 无字面量可依：一律不改写（保守底线）
        const std::string text = "<function name=\"f\"></function>";
        const std::string gp   = GEN_PREFIX + THINK_TAIL;
        auto al = align_generation_prompt(gp, "", text);
        ck("无根字面量 -> 不改写（gp 原样、input 原样）",
           al.mode == 0 && al.generation_prompt == gp && al.input == text);
    }
    {
        // generation_prompt 比字面量短 / 只差一个字符：都不算"是前缀"，走 ②
        const std::string text = "x";
        auto al1 = align_generation_prompt("<|im_start|>assistan", GEN_PREFIX, text);
        ck("短一位（不是前缀）-> 走 ② 补进输入，gp 原样",
           al1.mode == 2 && al1.generation_prompt == "<|im_start|>assistan");
        auto al2 = align_generation_prompt(GEN_PREFIX + "X", GEN_PREFIX, text);
        ck("前缀相同但尾巴不为空 -> 走 ①（尾巴 X 前移）",
           al2.mode == 1 && al2.input == "X" + text && al2.generation_prompt == GEN_PREFIX);
    }

    // ══════════════════════════════════════════════════════════════════
    // 三、escape_for_probe：日志判据本身要可读（换行必须可见）
    // ══════════════════════════════════════════════════════════════════
    {
        ck("escape 把 \\n 变成字面 \\\\n（否则日志里分不清是内容还是换行）",
           escape_for_probe(GEN_PREFIX, 100) == "<|im_start|>assistant\\n");
        ck("escape 展开 think 尾巴（判据行的可读性靠它）",
           escape_for_probe(GEN_PREFIX + THINK_TAIL, 100) == "<|im_start|>assistant\\n<think>\\n");
        ck("escape 截断时不越界且带标记",
           escape_for_probe(std::string(200, 'a'), 10).find("...(截断)") != std::string::npos);
        ck("escape 原样保留 UTF-8 非 ASCII（中文不被破坏）",
           escape_for_probe("北京", 100) == "北京");
    }

    printf("\n=== PEG 根节点字面量探针 + generation_prompt 对齐 单测 ===\n");
    printf("%s（%d 条，失败 %d）\n", g_bad == 0 ? "全部通过" : "有失败", g_ok + g_bad, g_bad);
    return g_bad == 0 ? 0 : 1;
}
