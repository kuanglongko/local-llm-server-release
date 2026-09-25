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
#include <cctype>
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
    // 二·五、grammar_prefill_from_literal：grammar 采样器要预填多少
    // ══════════════════════════════════════════════════════════════════
    // 这是 0.9.89 那一跳的判定本体：库推出来的 GBNF **以生成前缀的字面量开头**，
    // 而那段字面量已经在 prompt 里了 —— 不预填时 grammar 从根节点起步、逼模型
    // 把模板写好的尾巴再吐一遍（真机表现：content 前面多一段 / 数组只剩 `[ ]`）。
    // 预填量 = 「grammar 首字面量」与「真实生成后缀」的最长公共前缀。
    {
        ck("MiniCPM5 关思考：字面量 22B 是 41B 后缀的前缀 -> 预填整个字面量",
           grammar_prefill_from_literal(GEN_PREFIX,
               GEN_PREFIX + "<think>\n\n</think>\n\n") == GEN_PREFIX);
        ck("MiniCPM5 开思考：同样是字面量做前缀 -> 预填整个字面量",
           grammar_prefill_from_literal(GEN_PREFIX, GEN_PREFIX + THINK_TAIL) == GEN_PREFIX);
        ck("后缀与字面量完全相同 -> 预填整段（不退化成空）",
           grammar_prefill_from_literal(GEN_PREFIX, GEN_PREFIX) == GEN_PREFIX);
        ck("形状不符 -> 只预填公共前缀，不做任何猜测性补齐",
           grammar_prefill_from_literal(GEN_PREFIX, "<|im_start|>user\n") == "<|im_start|>");
        ck("完全不符（首字节就不同）-> 空串 = 不预填",
           grammar_prefill_from_literal(GEN_PREFIX, "hello").empty());
        ck("空字面量 -> 空串（取不到判据就不预填，退化成旧行为）",
           grammar_prefill_from_literal("", GEN_PREFIX).empty());
        ck("空后缀 -> 空串（裸补全没有生成前缀可越）",
           grammar_prefill_from_literal(GEN_PREFIX, "").empty());
        // 预填文本**必须**是真实后缀的前缀（否则会把一段模型没写过的内容喂进 grammar）
        const std::string suffix = GEN_PREFIX + "<think>\n\n</think>\n\n";
        const std::string pf = grammar_prefill_from_literal(GEN_PREFIX, suffix);
        ck("预填文本一定是真实后缀的前缀（grammar 只能越过已写好的部分）",
           suffix.compare(0, pf.size(), pf) == 0);
    }

    // ══════════════════════════════════════════════════════════════════
    // 二·六、grammar_prefill_from_suffixes：0.9.89 的预填口径（本轮真机故障）
    // ══════════════════════════════════════════════════════════════════
    // 上面那份取「字面量 ∩ 真后缀」，在真机上恒为 22B —— 而真后缀是 41B，
    // ⚠ 当时把这 19B 的短喂解释成"卡在不能匹配空串的 space() 节点上"——**已证伪**：
    // 库内 `space ::= | " " | "\n"{1,2} [ \t]{0,20}` 第一个分支就是空，能匹配空串。
    // 下面这些断言保留，是因为它们钉住的是**两份口径的区别**（谁跟谁取交），
    // 与那条被证伪的动机无关。
    // 所以预填量改由「库算后缀 ∩ 真后缀」决定。下面常量逐字取自
    // probe-20260921-151504.txt（15:12 MiniCPM5 / 15:07 Qwen3 两组）。
    {
        const std::string closed = GEN_PREFIX + "<think>\n\n</think>\n\n";   // 41B 关思考
        const std::string openOnly = GEN_PREFIX + THINK_TAIL;                    // 30B 开思考
        // 两侧都按关思考算（think 透传生效）-> 预填整段 41B（不是 22B）
        ck("两侧同形（都关思考 41B）-> 预填整段 41B，不再短喂到 22B",
           grammar_prefill_from_suffixes(closed, closed) == closed &&
           grammar_prefill_from_suffixes(closed, closed).size() == 41);
        // 本轮真根因：库按默认「开思考」算 30B，渲染侧是 41B -> 交集自动变短、暴露分叉
        ck("两侧分叉（库算 30B / 真后缀 41B）-> 交集只到可匹配处，分叉在字节数上显形",
           grammar_prefill_from_suffixes(openOnly, closed) == openOnly);
        // Qwen3 实测：库算 22B、真后缀 41B（不修 think 的形态）
        ck("库算 22B / 真后缀 41B（未透传思考开关）-> 预填只有 22B，正是短喂",
           grammar_prefill_from_suffixes(GEN_PREFIX, closed) == GEN_PREFIX &&
           grammar_prefill_from_suffixes(GEN_PREFIX, closed).size() == 22);
        ck("两侧完全相同 -> 预填整段（不退化成空）",
           grammar_prefill_from_suffixes(closed, closed) == closed);
        ck("首字节就不同 -> 空串 = 不预填（绝不喂进 prompt 里没有的文本）",
           grammar_prefill_from_suffixes("{\"a\":1}", closed).empty());
        ck("空库后缀 / 空真后缀 -> 空串（裸补全与取不到都退化成旧行为）",
           grammar_prefill_from_suffixes("", closed).empty() &&
           grammar_prefill_from_suffixes(closed, "").empty());
        // 预填文本必须是**真后缀**的前缀（否则会把模型没写过的内容喂进 grammar）
        const std::string pf2 = grammar_prefill_from_suffixes(openOnly, closed);
        ck("预填文本一定是真后缀的前缀", closed.compare(0, pf2.size(), pf2) == 0);
        // 多字节：预填按 UTF-16 口径对齐时不能切进代理对（这里只验字符串前缀语义）
        const std::string cjk = GEN_PREFIX + "\u4f60\u597d";
        ck("含 CJK 的后缀：交集按字节前缀算，不碰坏字符",
           grammar_prefill_from_suffixes(cjk, cjk) == cjk);
    }

    // ══════════════════════════════════════════════════════════════════
    // 二·七、预填按 token 字节边界收敛（0.9.91 修的本轮真根因：SIGABRT）
    // ══════════════════════════════════════════════════════════════════
    // 真机现场（probe-20260921-160945.txt）：
    //     gen_prompt=41B(...) rendered_suffix=41B prefill=41B peg_lit=41B think=0   ← 量全对齐
    //     llama-grammar.cpp:942: LM_GGML_ASSERT(!stacks.empty()) failed -> lm_ggml_abort
    //     → SIGNAL 6 (Aborted)，栈顶 llama_grammar_apply_impl ← llama_sampler_sample
    // 上一轮把"对齐量"全修对了却仍在 accept 那一跳崩 —— 真因是**切点**：
    // token 是原子的、grammar 位置是字节级的，最后一个 token 的边界与预填字节数
    // 对不齐时，整段 piece 都会被 accept、多喂几个字节 → grammar 空栈 → abort。
    //
    // 这一节的常量**逐字取自真机探针**（41B 后缀与它的 22B/19B 两半）。
    {
        // 真机 41B 真后缀 = 22B 生成前缀 + 19B think 块（Qwen3 关思考那一支，逐字）
        const std::string realSuffix = "<|im_start|>assistant\n" "<think>\n\n</think>\n\n";
        ck("真机后缀是 41B（逐字取自 probe-20260921-160945.txt）", realSuffix.size() == 41);
        ck("22B + 19B 正好拼出那 41B（22 与 19 就是切点的两个候选位置）",
           std::string("<|im_start|>assistant\n").size() == 22 &&
           std::string("<think>\n\n</think>\n\n").size() == 19 &&
           std::string("<|im_start|>assistant\n").size() +
           std::string("<think>\n\n</think>\n\n").size() == 41);

        // 收敛算术（与 llama_jni.cpp 里保守实现同口径：只减不增，按**字节数**判）。
        // 返回"实际喂进去的字节数"：把每个 piece 累加，累加后会超过 limit 的 piece 不喂。
        auto fedBytes = [](const std::vector<std::string> & pieces, size_t limit) {
            size_t bytes = 0;
            for (const auto & pc : pieces) {
                if (bytes + pc.size() > limit) break;
                bytes += pc.size();
            }
            return bytes;
        };

        // ① 边界对齐：一个 piece 都不丢，预填量一个字节不变（老行为逐字节保留）
        const std::vector<std::string> aligned = {
            "<|im_start|>", "assistant", "\n", "<think>", "\n\n", "</think>", "\n\n"};
        ck("边界对齐（piece 边界恰好落回 41B）-> 41B 全喂，一个字节不少",
           fedBytes(aligned, realSuffix.size()) == 41);

        // ② 切点错位：最后一个 piece **跨过**预填结尾（前一半在内、后一半在外）。
        //    构造：41B 文本的末 2B 与"下一段"的前 4B 被 tokenizer 切进同一个 piece，
        //    于是它从上一步的 39B 处跨过 41B —— 整段不喂。
        //    代价量化：少喂的那段 <= 该 piece 长度，这正是"只减不增、最多丢一个 token"的界。
        const std::vector<std::string> crossing = {
            "<|im_start|>", "assistant", "\n", "<think>", "\n\n", "</think>", "\n\n{\"a\""};
        const size_t fedCross = fedBytes(crossing, realSuffix.size());
        ck("末位 piece 跨过预填结尾 -> 它整段不喂（piece 是原子的，不 partial 喂）",
           fedCross == 39 && 39 + crossing.back().size() > realSuffix.size());
        ck("跨位收敛的代价上界：少喂的字节数 < 最后一个 piece 的长度",
           realSuffix.size() - fedCross < crossing.back().size() &&
           realSuffix.size() - fedCross > 0);

        // ③ 收敛绝不能反把预填变长（喂进 prompt 里没有的字节 = 另一种空栈形态）
        ck("收敛只减不增：实际喂入字节数永不大于预填量（多喂同样会空栈 abort）",
           fedBytes(crossing, realSuffix.size()) <= realSuffix.size() &&
           fedBytes(aligned, realSuffix.size()) <= realSuffix.size());

        // ④ 预填量本身就是"两条后缀取交"算出来的，必须仍是真后缀的前缀
        const std::string pfReal = grammar_prefill_from_suffixes(realSuffix, realSuffix);
        ck("真机 41B 常量：两侧同形 -> 预填整段 41B（不是 22B 的短喂）",
           pfReal == realSuffix);
        ck("预填文本一定落在真后缀上（喂进去的字节 prompt 里确实有）",
           realSuffix.compare(0, pfReal.size(), pfReal) == 0);

        // ⑤ 上游那半为什么不崩：文本 -> token -> 文本恰好闭合。
        //    用"总和恰好等于文本长度"来表达这个不变量（真实 tokenizer 满足它）。
        size_t sum = 0;
        for (const auto & pc : aligned) sum += pc.size();
        ck("切点闭合不变量：全部 piece 长度之和 == 文本长度（上游靠它不越界）",
           sum == realSuffix.size());
    }

    // ══════════════════════════════════════════════════════════════════
    // 二·八、grammar_fit_check：就位点判据（本轮新增的"看得见采样那一步"）
    // ══════════════════════════════════════════════════════════════════
    // 前八处成因里有两次真机 abort、以及 Qwen3 的"代码块"故障，都发生在**预填之后、
    // 下一个 token 的采样**那一步 —— 而那里此前一条日志都没有，只能拿 `prefill=NB`
    // 反推，于是 `prefill=22B peg_lit=22B` 被读成"对齐了、没问题"。
    // 这一节把就位点算术钉住：预期落点 / 真实位置 / 错位段原文 / 是否一致。
    //
    // 常量逐字取自真机探针（probe-20260921-172241.txt / -151504.txt，Qwen3 关思考）：
    //     rendered_suffix = 41B `"<|im_start|>assistant\n" + "<think>\n\n</think>\n\n"`
    //     peg_lit         = 22B `"<|im_start|>assistant\n"`
    {
        const std::string qwenReal = "<|im_start|>assistant\n" "<think>\n\n</think>\n\n";
        ck("真机常量口径：Qwen3 关思考真后缀 41B / 首字面量 22B / 差 19B",
           qwenReal.size() == 41 && std::string("<|im_start|>assistant\n").size() == 22 &&
           std::string("<think>\n\n</think>\n\n").size() == 19);

        // 真机 0.9.92 的形态：prefill = 22B（= peg_lit），真后缀 41B -> 错位 19B
        const std::string prefill22 = grammar_prefill_from_literal("<|im_start|>assistant\n", qwenReal);
        ck("0.9.92 形态：预填 22B（上限取 peg_lit）", prefill22.size() == 22);
        const grammar_fit_result fit22 = grammar_fit_check(prefill22, qwenReal);
        ck("就位点：预期 22B / 真实 41B / fit=0（错位）",
           fit22.expectBytes == 22 && fit22.actualBytes == 41 && fit22.aligned == 0);
        ck("错位段就是那 19B 的 think 块（原文可比，不只是一个长度差）",
           fit22.mismatch == "<think>\n\n</think>\n\n" && fit22.mismatch.size() == 19);
        ck("错位段被标为'含 think 块'（日志里能一眼认出是库不认的那一段）",
           looks_like_think_mismatch(fit22.mismatch));

        // 对齐时：expect == actual，fit=1，且**必须没有错位段**（不得报假警）。
        // 这条是防"判据恒真/恒报警"：一个只会在真机上喊狼来了的判据等于没有判据。
        const grammar_fit_result fitAligned = grammar_fit_check(qwenReal, qwenReal);
        ck("全预填（41B）-> fit=1 且无误报错位段",
           fitAligned.aligned == 1 && fitAligned.mismatch.empty() &&
           fitAligned.expectBytes == 41 && fitAligned.actualBytes == 41);

        // 反过来：预填**长于**真后缀（预填越界）不是"错位段"能表达的事，
        // 那时 mismatch 必须为空 —— 别拿一个不存在的段去解释（另一种故障）。
        ck("预填长于真后缀 -> fit=0 但不得编出一个错位段（那是另一类故障）",
           grammar_fit_check(qwenReal, prefill22).aligned == 0 &&
           grammar_fit_check(qwenReal, prefill22).mismatch.empty());

        // 预填根本不是真后缀的前缀（喂了 prompt 里没有的字节）-> 同样不报"错位段"
        ck("预填不是真后缀的前缀 -> 不报错位段（错位段只在'确实是前缀'时有意义）",
           grammar_fit_check("XXXX", qwenReal).mismatch.empty());

        // 边界：空后缀 / 空预填都不得抛、不得报假警
        ck("空后缀 -> fit=0 且无误报错位段", grammar_fit_check("", "").mismatch.empty());
        ck("空预填 -> 错位段为空（裸补全没有可错的位）",
           grammar_fit_check("", qwenReal).mismatch.empty());
        ck("错位段不含 think 时不打那个标记（标记不能恒亮）",
           !looks_like_think_mismatch("{\"a\":1}"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 二·九、advance_grammar_past_mismatch：错位段要不要让 grammar 咽下（0.9.96 修）
    // ══════════════════════════════════════════════════════════════════
    // 这是在 27.5 那张判据表第二行（"fit=0 + mismatch 是 think 块 -> 宿主侧无解"）
    // 被证伪之后开出来的修法：宿主侧**有**解 —— 让 grammar 把错位段一起咽下去，
    // 落点从 22B 推到 41B，与模型实际续写的位置对齐。
    //
    // 常量逐字取自真机探针（probe-20260921-172241.txt / 本轮 20:50 那三例）：
    //     rendered_suffix = 41B `"<|im_start|>assistant\n" + "<think>\n\n</think>\n\n"`
    //     peg_lit         = 22B `"<|im_start|>assistant\n"`
    //     mismatch        = 19B `"<think>\n\n</think>\n\n"`
    {
        const std::string qwenReal = "<|im_start|>assistant\n" "<think>\n\n</think>\n\n";
        const std::string lit22    = "<|im_start|>assistant\n";
        const std::string mis19    = "<think>\n\n</think>\n\n";
        ck("真机常量：真后缀 41B = 22B 字面量 + 19B 错位段",
           qwenReal.size() == 41 && lit22.size() == 22 && mis19.size() == 19 &&
           lit22 + mis19 == qwenReal);

        // 假 grammar：一个**按前缀匹配**的最小状态机 —— 与真 grammar 的形态同构
        // （真 grammar 认的是一段字面量/可选分支，判据也是"喂进来的字节串是不是它的前缀"）。
        // 离线不可能有真 grammar，所以这里用"哪些字节它认"来表达方言：
        //   · blankOk   —— 只认空白（如库内 `space` 那个可选分支）
        //   · prefixOk  —— 认给定的那段原文（如真机那条路径：错位段就是模板写死的字面量）
        auto feedTo = [](const std::string & allowed) {
            return [allowed](char c) {
                return allowed.find(c) != std::string::npos;
            };
        };
        (void) feedTo;
        // ① 正常路径：19B 全是空白 -> grammar 一路点头 -> 整段咽下
        {
            size_t tried = 0; bool rolled = false;
            auto adv = advance_grammar_past_mismatch(
                lit22.size(), qwenReal, mis19,
                // 真机那条路径：grammar 认得这段错位段（它本来就是模板写进 prompt 的原文，
                // 而库内 space 规则的 `"\n"{1,2} [ \t]{0,20}` 与字面量分支都能吃下它）。
                [&](char) { tried++; return true; },
                [&]() { rolled = true; });
            ck("① 错位段全被 grammar 接受 -> 整段咽下（落点 22B -> 41B）",
               adv.accepted == mis19 && adv.fromBytes == 22 && adv.toBytes == 41);
            ck("① 推进量正好是错位段长度（不增不减）",
               adv.toBytes - adv.fromBytes == 19 && adv.accepted.size() == 19);
            ck("① 成功路径不得回滚", !rolled);
            ck("① 逐字节试到最后一个（不提前收工）", tried == 19);
        }

        // ② 不认：方言只认空白，而错的段是"前两个字节是空白、第三个不是"
        //    -> **整段作废**，不是"停在吃下的那两三个字节"
        {
            bool rolled = false;
            const std::string mixed = "\n\n<think>{";   // 空白 + 非空白混着
            auto adv = advance_grammar_past_mismatch(
                lit22.size(), lit22 + mixed, mixed,
                [](char c) { return std::isspace((unsigned char) c) != 0; },
                [&]() { rolled = true; });
            ck("② 中途有一个字节不认 -> 一个字节都不咽（不是半推一截）",
               adv.accepted.empty() && adv.toBytes == 22);
            ck("② 不认时必须回滚（原件不得停在真假后缀中间）", rolled);
        }
        // ②b 这一条钉住"不作半推"的**代价**：若写成"停在吃下的那几个字节"，
        //     落点会落在真后缀中间（22+2=24B）—— 那比不推更糟：grammar 会以为
        //     空白已经写完、开始等 JSON，而模型下一步正写着 `<think>...`。
        {
            auto adv = advance_grammar_past_mismatch(
                lit22.size(), lit22 + "\n\n<think>{", "\n\n<think>{",
                [](char c) { return std::isspace((unsigned char) c) != 0; },
                []() {});
            ck("②b 半推一截是不可接受的（落点必须仍等于预填量）",
               adv.toBytes == lit22.size());
        }

        // ③ 本来就对齐（mismatch 为空）-> 一次都不试（无端改行为最该防）
        {
            size_t tried = 0;
            auto adv = advance_grammar_past_mismatch(
                qwenReal.size(), qwenReal, "",
                [&](char) { tried++; return true; }, []() {});
            ck("③ 没有错位段 -> 不试任何字节、落点不变",
               tried == 0 && adv.toBytes == 41 && adv.accepted.empty());
        }

        // ④ 传进来的"错位段"其实不在那个位置上 -> 一个字都不试（防调用方传错段）
        {
            size_t tried = 0;
            auto adv = advance_grammar_past_mismatch(
                lit22.size(), qwenReal, "<|im_end|>",
                [&](char) { tried++; return true; }, []() {});
            ck("④ 错位段与真后缀对不上 -> 不试（不拿别处的字符串去喂 grammar）",
               tried == 0 && adv.accepted.empty() && adv.toBytes == 22);
        }

        // ⑤ 边界：预填比真后缀还长（另一类故障）/ 空后缀
        {
            ck("⑤ 预填长于真后缀 -> 不动（那是另一类故障，不是错位）",
               advance_grammar_past_mismatch(41, lit22, mis19,
                   [](char) { return true; }, []() {}).toBytes == 41);
            ck("⑤ 空真后缀 -> 不动",
               advance_grammar_past_mismatch(0, "", "", [](char) { return true; }, []() {}).toBytes == 0);
        }

        // ⑥ 落点推进后，grammar 的位置必须**恰好**是真后缀的末尾（这才是本处的目的）
        {
            auto adv = advance_grammar_past_mismatch(
                lit22.size(), qwenReal, mis19,
                [](char) { return true; }, []() {});
            ck("⑥ 推进后落点 == 真后缀长度（与模型续写位置对齐，不是差一点）",
               adv.toBytes == qwenReal.size());
            ck("⑥ 推进的那段确实是真后缀的子串（不是自己拼的东西）",
               qwenReal.compare(adv.fromBytes, adv.accepted.size(), adv.accepted) == 0);
        }

        // ⑦ 多字节错位段：逐字节推进不得破坏 UTF-8（这里是 4 字节的 emoji）
        {
            const std::string cjkMis = "\xe4\xbd\xa0\xe5\xa5\xbd";   // 你好
            auto adv = advance_grammar_past_mismatch(
                0, cjkMis, cjkMis,
                [](char) { return true; }, []() {});
            ck("⑦ 多字节错位段逐字节咽下后原文逐字相同",
               adv.accepted == cjkMis && adv.toBytes == cjkMis.size());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 二·b 根字面量对齐到真后缀（第十一处成因，0.9.97 修）
    // ══════════════════════════════════════════════════════════════════
    // 真机读数（21:43 那三例）钉死了这一处的形态：
    //     peg_lit=22B  cp.generation_prompt=41B  mismatch=19B(<think>\n\n</think>\n\n)
    //     [schema] 错位段未被 grammar 接受（19B），落点保持 22B
    // 「未被接受」不是"没试"、也不是"重放中断"，是**第一个字节 `<` 就被 grammar 判非法** ——
    // 因为 grammar 里根本没有 think 产生式（`reasoning_format=NONE`）。
    // 所以修法从"喂给 grammar"改成"把 grammar 的根字面量换成真后缀"：
    //     root ::= "<|im_start|>assistant\n" <json>   ->   root ::= "<|im_start|>assistant\n<think>\n\n</think>\n\n" <json>
    {
        // ⚠ 三个常量必须按**真机口径**写：pegLiteral 与 realSuffix 是**原文**
        //   （末尾那个换行是 0x0A 一个字节），GBNF 是**转义形态**（末尾是 `\\` + `n`
        //   两个可见字符）。这正是真机上两份产物的关系，也是本函数唯一要处理的情形。
        //   上一版把 gLit 也照 GBNF 的转义形态写（`\\n` 两字符），于是 gbnf 里
        //   gbnf_escape_literal(gLit) 多转义了一道、与 GBNF 文本对不上，find 恒 npos，
        //   ①⑨⑩ 三条断言恒假 —— 夹具与实现"错到一块去了"，反而掩盖了真问题。
        const std::string gLit = "<|im_start|>assistant\n";       // 22B，末位 0x0A
        const std::string gMis = "<think>\n\n</think>\n\n";     // 19B，合计 41B
        const std::string gReal = gLit + gMis;
        // 库产出的 GBNF 形状（与 format_grammar 输出同构：根规则在最前，字面量按
        // 库内 format_literal 转义 —— 0x0A -> 两字符 \\n）。
        const std::string gbnf =
            "root ::= \"<|im_start|>assistant\\n\" json-value\n"
            "json-value ::= \"{\" space \"}\"\n";

        // ① 命中：根字面量由 22B 换成 41B，其余逐字节不变
        {
            const std::string out = gbnf_realign_root_literal(gbnf, gLit, gReal);
            ck("① 根字面量换成真后缀（22B -> 41B）",
               out.find("\"<|im_start|>assistant\\n<think>\\n\\n</think>\\n\\n\" json-value") != std::string::npos);
            ck("① 换的只是根规则那一个（其余规则逐字节不变）",
               out.find("json-value ::= \"{\" space \"}\"") != std::string::npos);
            ck("① 旧字面量不再出现在根规则位置（没留半截）",
               out.rfind("root ::= \"<|im_start|>assistant\\n\"") == std::string::npos);
        }
        // ② 没有错位段（真后缀 == 字面量）-> 一个字都不动
        {
            ck("② 已经对齐 -> 原样返回（不无端改动）",
               gbnf_realign_root_literal(gbnf, gLit, gLit) == gbnf);
        }
        // ③ 真后缀不以字面量开头（另一类故障）-> 不改（不猜）
        {
            ck("③ 真后缀与字面量不同源 -> 不改写",
               gbnf_realign_root_literal(gbnf, gLit, "<|im_end|>x") == gbnf);
        }
        // ④ 真后缀比字面量短 -> 不改（另一类故障，不是"差一段尾巴"）
        {
            ck("④ 真后缀短于字面量 -> 不改写",
               gbnf_realign_root_literal(gbnf, gLit, "<|im_start") == gbnf);
        }
        // ⑤ 形状不符（GBNF 里找不到根字面量）-> 不改（库改版/别的模板）
        {
            ck("⑤ GBNF 里没有那个根字面量 -> 不改写",
               gbnf_realign_root_literal("root ::= json-value\n", gLit, gReal) ==
               std::string("root ::= json-value\n"));
        }
        // ⑥ 空入参 -> 原样返回（不崩）
        {
            ck("⑥ 空 GBNF / 空字面量 / 空真后缀一律原样返回",
               gbnf_realign_root_literal("", gLit, gReal).empty() &&
               gbnf_realign_root_literal(gbnf, "", gReal) == gbnf &&
               gbnf_realign_root_literal(gbnf, gLit, "") == gbnf);
        }
        // ⑦ 只改**根规则**那一处：若别处恰好有同样的字面量，不得被连带改掉
        {
            const std::string gbnf2 =
                "root ::= \"<|im_start|>assistant\\n\" json-value\n"
                "other ::= \"<|im_start|>assistant\\n\"\n";
            const std::string out = gbnf_realign_root_literal(gbnf2, gLit, gReal);
            ck("⑦ 只替换根规则那一处，别处同形字面量保持原样",
               out.find("other ::= \"<|im_start|>assistant\\n\"") != std::string::npos);
        }
        // ⑧ 转义口径必须与库内 format_literal 逐字一致（\r \n " \ 四个字符）
        {
            ck("⑧ 转义：换行两字符、引号与反斜杠都被转义",
               gbnf_escape_literal("a\nb\"c\\d\re") == "a\\nb\\\"c\\\\d\\re");
            ck("⑧ 转义不碰其它字符（含非 ASCII）",
               gbnf_escape_literal("中文<|im_start|>") == "中文<|im_start|>");
        }
        // ⑨ 真机常量对齐：19B 错位段被"含进"根字面量（本轮修法的全部内容）
        {
            const std::string out = gbnf_realign_root_literal(gbnf, gLit, gReal);
            ck("⑨ 对齐后的根字面量含全部 19B 错位段（think 块不再靠'喂'）",
               out.find("\\n\\n</think>\\n\\n\"") != std::string::npos);
        }
        // ⑩ 分隔符必须是 ` ::= `：库内 format_grammar() 逐字为
        //       ss << kv.first << " ::= " << kv.second << '\n';
        //    写成 "root = " 会永远匹配不上，而对齐失效的症状恰好是"什么都没变"。
        //    这一条把"分隔符"这件事本身钉住，免得下一轮又照 `= ` 写一遍。
        {
            const std::string withEq =
                "root = \"<|im_start|>assistant\\n\" json-value\n";
            ck("⑩ 用 `= ` 分隔的 GBNF 不改写（分隔符口径钉在 ` ::= `）",
               gbnf_realign_root_literal(withEq, gLit, gReal) == withEq);
            ck("⑩ 用 ` ::= ` 分隔的 GBNF 才改写",
               gbnf_realign_root_literal(gbnf, gLit, gReal) != gbnf);
        }
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

    // ══════════════════════════════════════════════════════════════════
    // 四、预填的**字节账**（第十二处成因，0.9.99 修）
    // ══════════════════════════════════════════════════════════════════
    // 真机 `0.9.98` 的三行读数（用户 23:16 贴的原文）：
    //     gbnf=939B                              <- 916 + 23，根字面量确实 22B -> 41B 了
    //     根字面量对齐：peg_lit=22B -> 真后缀=41B（已对齐）
    //     已挂载 grammar 采样器：prefill=7 tok(已预填) fed=41B/41B
    // 三条一起看像"都对了"，而 `fed=41B/41B` 的分子**是算出来的**（prefillEff.size()），
    // 与"真喂进去多少"无关。grammar 是字节级、预填那一跳是 token 级：一个 piece 里
    // 可能同时含"grammar 声明的字面量"与"模型还没开始写"的字节，喂进去就**多于**声明量，
    // grammar 被推过它自己声明的落点；推过界不抛异常，代价是语法被悄悄写坏
    // （`{`/`[` 全匹配不上 -> 全体候选置 -inf）。这一节把那条链**逐条钉住**。
    {
        // ⚠ 夹具必须按**真机口径**写：真后缀是**原文**（末尾的换行是 0x0A 一个字节），
        //   不是 GBNF 里那种两字符 `\n`。上一版把这里写成两字符 `\n`，与 GEN_PREFIX
        //   （末尾真换行）就对不上，"短喂是前缀"那条断言恒假 —— 夹具口径错会**冒充**
        //   实现的错（0.9.98 那轮正是这么被带偏过一次）。
        const std::string declared41 = GEN_PREFIX + "<think>\n\n</think>\n\n";   // 41B（真后缀，末尾真换行）
        const std::string mismatch19 = "<think>\n\n</think>\n\n";                // 19B

        // ① 账平：声明 41B、预填就是那 41B -> usable=1 balanced=1，喂的字节 == 声明量
        const prefill_byte_plan p41 = plan_grammar_prefill_bytes(declared41, declared41);
        ck("账平：预填 == 声明量 -> usable=1 balanced=1",
           p41.usable == 1 && p41.balanced == 1);
        ck("账平：喂进去的字节数**恒等于** grammar 声明的字面量",
           p41.feed.size() == declared41.size() && p41.feed == declared41);

        // ② 短喂（= 0.9.94 之前的行为）：仍是声明量的前缀 -> 可以喂，但**必须记账不平**。
        //    ⚠ 这一条是"两个数字必须分开报"的判据：短喂本身不置 -inf（space 能匹配空串），
        //      但它意味着 grammar 的落点与模型续写的位置差着那一段 —— 症状与未对齐逐字相同。
        const prefill_byte_plan p22 = plan_grammar_prefill_bytes(declared41, GEN_PREFIX);
        ck("短喂：是声明量的前缀 -> usable=1（照喂，退回 0.9.94 行为）",
           p22.usable == 1 && p22.feed == GEN_PREFIX);
        ck("短喂：balanced=0（账不平必须显形，不能靠人眼看 fed==declared）",
           p22.balanced == 0);
        ck("短喂：报出的声明量与预填量分别正确（两个数字分开报）",
           p22.declaredBytes == declared41.size() && p22.prefillBytes == GEN_PREFIX.size());

        // ③ 预填不是声明量的前缀（另一类故障：改写没生效 / 模板换了）-> **不喂**。
        //    补不齐、也不该猜：凭空补进去就是替模型写它下一步要写的东西（0.9.96 的老路）。
        const prefill_byte_plan pBad = plan_grammar_prefill_bytes(declared41, mismatch19);
        ck("预填不是声明量前缀 -> usable=0（不喂，退回旧行为）", pBad.usable == 0);
        ck("不喂时 feed 为空（一个字节都不动）", pBad.feed.empty());
        ck("不喂时 why 非空（不留痕就分不出\"没试过\"与\"试了不行\"）", !pBad.why.empty());

        // ④ 预填为空 -> 不喂（与既有口径一致）
        ck("预填为空 -> 不喂", plan_grammar_prefill_bytes(declared41, "").usable == 0);

        // ⑤ grammar 未声明字面量（根节点不是 literal）-> 照预填量喂，账按预填量算
        const prefill_byte_plan pNoDecl = plan_grammar_prefill_bytes("", GEN_PREFIX);
        ck("grammar 未声明字面量 -> 照预填量喂且账平",
           pNoDecl.usable == 1 && pNoDecl.balanced == 1 && pNoDecl.feed == GEN_PREFIX);

        // ⑥ **反向断言**：多喂一个字节（= token piece 跨过声明量的形态）必须被认出来。
        //    这一条钉的是"收敛线取声明量而不是预填文本长度"这件事的必要性：
        //    如果只比 `feed.size() <= prefillBytes`，多喂的那一字节看不出来。
        const std::string over = declared41 + "x";
        const prefill_byte_plan pOver = plan_grammar_prefill_bytes(declared41, over);
        ck("反向：预填**长于**声明量且不是其前缀 -> usable=0（多喂被挡住）",
           pOver.usable == 0);

        // ⑦ 这条链与"根字面量已对齐"**同时**成立时才构成真机 0.9.98 的场景：
        //    对齐真的生效了（genuine reline），但预填口径把字面量之外的东西也算进去了。
        const std::string gbnf22 = "root ::= \"" + gbnf_escape_literal(GEN_PREFIX) + "\" json\n";
        const std::string gbnfRelined = gbnf_realign_root_literal(gbnf22, GEN_PREFIX, declared41);
        ck("对齐确实生效（真机日志那两行的复算）", gbnfRelined != gbnf22);
        ck("对齐后 gbnf 长了 23B（916 -> 939 的复算，逐字对得上真机读数）",
           gbnfRelined.size() == gbnf22.size() + 23);
        ck("对齐后根字面量 == 真后缀（grammar 声明从真后缀之后开始）",
           gbnfRelined.find(gbnf_escape_literal(declared41)) != std::string::npos);

        // ⑧ **转义表必须与库内 `format_literal` 逐字一致（六个字符）**。
        //    库内那张表（common/json-schema-to-grammar.cpp 与 fork 的 common.h 同源）：
        //        {'\r','\n','"','-',']','\\'}
        //    这里先前只转义了四个、漏掉 `-` 与 `]`。真机 Qwen3 的 41B 后缀恰好只含 `\n`，
        //    所以一直没显形 —— 但换一种模板（生成后缀里带 markdown 列表或 JSON 片段）
        //    就会：要么 find 恒不中（对齐静默失效），要么写回一段**语法错**的 GBNF
        //    （`-` 在字符类里是区间符号、`]` 会提前闭合）。
        ck("转义表含 `-`（漏了它 -> find 不中或写出语法错的 GBNF）",
           gbnf_escape_literal("a-b") == "a\\-b");
        ck("转义表含 `]`（漏了它会提前闭合字符类）",
           gbnf_escape_literal("a]b") == "a\\]b");
        // 库内表只含六个字符，`[` **不**在表里（对照 libg 的 GRAMMAR_LITERAL_ESCAPES）——
        // 这条是**反向断言**：把 `[` 也转义就成了"多转义一道"，与库分叉（0.9.98 那次
        // 多解一道转义、find 恒不中的教训同源）。
        ck("转义表六个字符与库内一字不差（`[` 不转义）",
           gbnf_escape_literal("\r\n\"-[]\\") == "\\r\\n\\\"\\-[\\]\\\\");
        ck("转义不碰普通字符与非 ASCII", gbnf_escape_literal("abc北京") == "abc北京");

        // ⑨ 带 `-` / `]` 的真后缀：改写得出来、且与"库内口径"一致（反向断言：
        //    库里那种转义形态能被找到 —— 漏转义时这条会红）。
        const std::string litDash = "<|im_start|>assistant\n";
        const std::string sufDash = litDash + "<think>a-b]c</think>\n";
        const std::string gDash = "root ::= \""
                                  + gbnf_escape_literal(litDash) + "\" json\n";
        const std::string gDashOut = gbnf_realign_root_literal(gDash, litDash, sufDash);
        ck("带 `-` / `]` 的真后缀也能对齐（对齐不该只在纯 `\n` 后缀上成立）",
           gDashOut != gDash &&
           gDashOut.find(gbnf_escape_literal(sufDash)) != std::string::npos);
    }

    printf("\n=== PEG 根节点字面量探针 + generation_prompt 对齐 单测 ===\n");
    printf("%s（%d 条，失败 %d）\n", g_bad == 0 ? "全部通过" : "有失败", g_ok + g_bad, g_bad);
    return g_bad == 0 ? 0 : 1;
}
