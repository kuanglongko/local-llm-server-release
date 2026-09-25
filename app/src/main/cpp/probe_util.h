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
// 渲染结果尾部形状（`<think>` / `</think>`）：**与 Kotlin 侧同一个判据本体**
// ══════════════════════════════════════════════════════════════════════════
// 为什么这一段必须在**宿主可编**的头里（而不是只留在 llama_jni.cpp）：
//
// 这条判据原先有三份各自实现 —— C++ 的 `classify_rendered_think_tail`、
// Kotlin 的 `RenderedPrompt.openAtStartForTest`、以及 `ThinkingControl` 里的
// `renderedPromptEndsWithThinkingStart`。三处都叫"256 尾窗口"，但 C++ 取 256
// **字节**、另两处取 256 个 **UTF-16 code unit**（256 字节 ≈ 85 个汉字 →
// 覆盖范围差 3 倍）。现有用例**全是 ASCII 后缀**，两种窗口恰好覆盖同一段 ——
// 于是分叉了却全绿：镜像给出的正是"判据没漂"的**假信心**。
//
// 收口方式：判据**本体只有这里一处**（`classify_think_tail`），
//   · 真机侧 `llama_jni.cpp` 的 `classify_rendered_think_tail` 直接调它；
//   · 宿主测（`tools/render_tail/render_tail_test.cpp`）**也**直接调它 ——
//     不是"另写一份近似"，所以"两侧同档"才是真的被证过的。
//
// 窗口从**末尾**取，单位是**字节**（后缀要拼回 prompt、prompt 要按字节交给
// tokenizer，字节口径才不会在多字节后缀上错位）；切点若落在多字节序列中间，
// 就把开头的续字节丢掉（与 `std::string::substr` 的裸字节切片同向）。
enum class ThinkTailShape {
    kNone,      // 后缀里没有思考段（Gemma / Qwen-Instruct 一类）
    kOpenOnly,  // 以未闭合的 `<think>` 结尾（LFM2.5 硬编码；MiniCPM5 + enable_thinking=true）
    kClosed,    // 后缀里已经出现完整的思考段（MiniCPM5 + enable_thinking=false）
};

// 尾窗口大小，**单位是 UTF-8 字节**。
// Kotlin 侧对应 `ThinkStream.TAIL_WINDOW_BYTES`，两侧必须同值同单位 ——
// 由 `tools/run_render_tail_guard.sh` 从两份源码里各自抽出数值正面比对。
static const size_t kThinkTailWindowBytes = 256;

static inline ThinkTailShape classify_think_tail(const std::string & prompt) {
    if (prompt.empty()) return ThinkTailShape::kNone;
    size_t from = prompt.size() > kThinkTailWindowBytes ? prompt.size() - kThinkTailWindowBytes : 0;
    // 落在续字节（10xxxxxx）上就一直往前挪，保证窗口起点是字符边界。
    while (from < prompt.size() && ((unsigned char) prompt[from] & 0xC0) == 0x80) from++;
    const std::string tail = prompt.substr(from);
    static const std::string OPEN  = "<think>";
    static const std::string CLOSE = "</think>";
    const size_t at = tail.rfind(OPEN);
    if (at == std::string::npos) return ThinkTailShape::kNone;
    // 最后一个开标签之后若已经有闭合标签 —— 思考段已闭合（"关思考"那一支），
    // 模型接下来写的是正文，不能当成"思考段已开"。
    if (tail.find(CLOSE, at) != std::string::npos) return ThinkTailShape::kClosed;
    return ThinkTailShape::kOpenOnly;
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

// ══════════════════════════════════════════════════════════════════════════
// grammar 采样器的「预填文本」（纯字符串运算，可单测）
// ══════════════════════════════════════════════════════════════════════════
// 这是本类故障的**第五处**成因，也是前四轮都没修净的那一处。前四轮修的全是
// "传给库的那个生成后缀对不对"，而这一处是：**库推出来的 grammar 需要被"喂"**。
//
// 上游的用法（llama.cpp `common/sampling.cpp`，逐字）：
//
//     // Feed generation prompt tokens to the grammar sampler so it advances past
//     // tokens the template already placed in the prompt.
//     // Only applies to output-format and tool-call grammars; user-supplied grammars must not be prefilled.
//     if (grmr && !params.grammar_lazy && common_grammar_needs_prefill(params.grammar)) {
//         for (const auto & token : prefill_tokens) llama_sampler_accept(grmr, token);
//     }
//
// 为什么必须预填：GBNF 是**从根节点开始**匹配生成出来的 token 的。而模板产出的
// grammar 根节点**以生成前缀的字面量开头**（MiniCPM5 是硬编码的
// `p.literal("<|im_start|>assistant\n")`，见上游 chat.cpp 的 minicpm5 分支），
// 这个字面量**已经在 prompt 里了**（模板自己写进去的），模型不会、也不该再吐一遍。
// 于是不预填时 grammar 从根节点起步，要求第一个 token 就是 `<|im_start|>` ——
// 模型被逼着把模板已经写好的那段尾巴**再吐一遍**，再才轮到 JSON。
// 真机表现就是本轮反复出现的那两条：
//     content = "<|im_start|>assistant\n{...}"   ← 被截掉头部（客户端丢掉特殊 token）
//     content = "<|im_start|>assistant\n[ ]"     ← 完整形态；数组的预算被尾巴花光
//
// 预填多少：**grammar 首字面量 与 真实生成后缀 的最长公共前缀**。
//   · MiniCPM5 关思考：后缀 41B（`<|im_start|>assistant\n<think>\n\n</think>\n\n`），
//     首字面量 22B（`<|im_start|>assistant\n`）→ 预填 22B，正好把 grammar 推过那段字面量，
//     之后 grammar 要的就是裸 JSON，与模型实际续写的位置对齐；
//   · 两者完全相同的模板 → 预填整段；
//   · 形状不符（公共前缀只有几个字节）→ 只预填那一小段 —— **不做任何猜测性补齐**：
//     预填多了会让 grammar 越过它该在的位置（症状与不预填相反：约束落在 JSON 中间）。
//
// 空字面量 / 空后缀一律返回空串（= 不预填），退化成引入本修复之前的行为。
static inline std::string grammar_prefill_from_literal(const std::string & rootLiteral,
                                                       const std::string & genSuffix) {
    if (rootLiteral.empty() || genSuffix.empty()) return "";
    const size_t n = rootLiteral.size() < genSuffix.size() ? rootLiteral.size() : genSuffix.size();
    size_t i = 0;
    while (i < n && rootLiteral[i] == genSuffix[i]) i++;
    return rootLiteral.substr(0, i);
}

// ══════════════════════════════════════════════════════════════════════════
// 预填之后**还剩一段没人管**：错位段（第十处成因，0.9.96 修）
// ══════════════════════════════════════════════════════════════════════════
// 上面那个函数的产物只是"grammar 愿意跳过的前缀"，它**不保证**跳完之后
// grammar 的位置与模型续写的位置对上。真机上两者正好差一段：
//
//     grammar 愿意跳过： `<|im_start|>assistant\n`           22B（GBNF 根首字面量）
//     真 prompt 的后缀： `<|im_start|>assistant\n<think>\n\n</think>\n\n`  41B
//     差的那段（mismatch）：`<think>\n\n</think>\n\n`        19B
//
// 于是 grammar 越过了 22B 就**站在 JSON 的起点**（它在等 `{` / `[`），而模型实际
// 续写的下一个 token 是 `\n`（模板写死的思考块的一部分）。**这个 token 会被
// grammar 判成非法、logit 置 -inf** —— 模型只有两条路：
//   · 报 EOG 提前收尾（真机表现：有时直接空/极短输出）；
//   · 硬着头皮吐模板教它的那段**思考块**（模板的生成前缀本来就是"接着这里写"），
//     而这段正好是 `space ::= | " " | "\n"{1,2} [ \t]{0,20}` **能匹配**的东西 ——
//     于是 19B 从"被禁"变成"被放行"，**被当成 JSON 之前的空白合法吃掉**，
//     随后补一个**收尾用的生成前缀复述**（真机 0.9.96 实测尾标是
//     `<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think`），再才轮到 JSON。
// 两条路都是故障，而 ninth 轮的 `mismatch=19B(...)` 读数**早就在指它**：
// 当时那张判据表把这一格写成"宿主侧无解，要修在库（chat.cpp）或换 enable_thinking 口径" ——
// **这句是错的**。宿主侧有解，而且不需要碰库、不需要换思考开关：
// 就是**让 grammar 也把错位段咽下去**（连同它自己）。见下。
//
// ── 为什么修在"预填"这一侧（而不是去改 prompt）：────────────────────────────
// prompt 是模板渲染出来的，改了它就要动渲染（分歧面更大：MiniCPM5 / Qwen3 /
// LFM2.5 / ChatML 回落各一条），而且真机的 41B 后缀**本来是对的** —— 它确实
// 写在 prompt 里、模型确实要接着说它。要改的不是 prompt，是
// **"grammar 以为模型已经说到哪了"这一个记账**：把它从 22B 修到 41B。
//
// ── 为什么这不是 0.9.90 那次的回归（同一件事的两面镜）：────────────────────
// 0.9.90 之所以 SIGABRT，是因为它把 41B **交给 tokenizer 切**、再把切出来的
// piece **整段 accept** —— token 是原子的，末位 piece 跨过结尾时会把 19B 之外的
// 字节（`{` / `"` 之类真 JSON 的起点）也喂进去，grammar 当场空栈 abort。
// 本函数走的是**另一条路**：错位段**不切 token**，而是把它**作为一段文本拿去试**，
// 让 grammar 自己告诉我们"这段它认不认"：
//   · 认（它正好是 grammar 的某个可选分支，如 space）-> 吃下，落点推到真后缀末尾；
//   · 不认（严格模式 / 别的模板 / mismatch 里混了 JSON）-> 一个字节都不动，
//     退回 0.9.92 之后的行为。
// 所以本函数**只可能让落点更准，不可能喂进 grammar 不认的字节** —— 因为判据
// 就是 grammar 自己那份"认不认"。
//
// ── 为什么用"试探"而不是在这里写死 `space ::= | " " | ...` 的方言：────────
// 写死等于把这段绑死在库当前版本的 SPACE_RULE 上；库一改（或换个模板把
// mismatch 换成别的东西）就会静默失效，正是本项目反复踩的那类。判据必须来自
// **运行时那一份 grammar 采样器**（调用方传进来的 tryAccept 回调）。
struct prefill_advance_result {
    std::string accepted;     // 除"字面量预填"之外**另外**咽下的字节（空 = 没多吃）
    size_t      fromBytes = 0;// 试探起点（= 字面量预填长度）
    size_t      toBytes   = 0;// 推进后的落点（= accepted 吃满时的字节位置）
};

// ══════════════════════════════════════════════════════════════════════════
// 标记 / 回滚：错位段试探必须**全成功才留下**（第十处成因的"别引入新问题"底线）
// ══════════════════════════════════════════════════════════════════════════
// llama.cpp 的 grammar 采样器**没有公开的克隆接口**，所以"试一段、不行就退回去"
// 必须由调用方自己给：先打一个标记（`llama_sampler_accept` 是不透明 API，标记
// 只能由实现侧提供——真机上用 `llama_grammar_clone` / 链重建，本函数只负责
// **判定该不该试、试到哪**，所以它拿到的是两个最朴素的回调）。
//
// 判据在这儿（纯逻辑，可离线单测）：
//   · step(key)        推进一个 piece，返回 false = grammar 这一次不接受它；
//   · rollback()       把 grammar 退回试探之前（**只在失败时**调用）。
// 全成功后 accepted 里的每一个 piece 已经**留在了 grammar 里** —— 这正是我们要的
// （落点从 22B 推到 41B）。任一失败：先 rollback 再返回空 accepted —— 于是
// grammar 的状态与"从没试探过"逐字节相同。
//
// 空段（mismatch 为空 / 起点 >= 终点）一律不做任何试探：那说明**本来就对上了**，
// 再去 accept 任何东西都是无端改行为。
template <typename StepF, typename RollbackF>
static inline prefill_advance_result advance_grammar_past_mismatch(
        size_t prefillBytes, const std::string & genSuffix,
        const std::string & mismatch, StepF step, RollbackF rollback) {
    prefill_advance_result r;
    r.fromBytes = prefillBytes;
    r.toBytes   = prefillBytes;
    (void) rollback;   // 只在失败那条路上用到；空段/不成前缀的那些分支要留它不被"未使用"警告
    // 只有"预填确实是真后缀的前缀、且后面还剩一段"时才谈得上错位（与
    // grammar_fit_check 同一口径：不满足时 mismatch 本就是空的，这里再兜一次，
    // 免得有人拿别处的字符串当 mismatch 传进来）。
    if (mismatch.empty() || genSuffix.size() <= prefillBytes) return r;
    if (prefillBytes > genSuffix.size()) return r;                    // 越界 -> 另一类故障
    if (genSuffix.compare(prefillBytes, mismatch.size(), mismatch) != 0) return r;  // 不是那一段

    // 逐字节推进（不切 token）：grammar 的位置本来就是字节级的，而错位段往往
    // 只有一个 token 那么长，切 token 只会重新引入"末位 piece 跨界"那一类问题。
    for (size_t i = 0; i < mismatch.size(); i++) {
        if (!step(mismatch[i])) {
            // 有一个字节 grammar 不吃 -> 整段资格作废（不是"停在吃下的那几个字节"）：
            // 半推一截会让落点落在**真后缀中间**（比如 `<think>` 之后），那比不推更糟
            // —— grammar 会以为模型已经写完了思考块、开始等 JSON，而模型下一步正写着
            // 那段思考块的剩余部分。要么推到它该在的位置，要么一个字节都不动。
            rollback();
            return r;
        }
    }
    r.accepted = mismatch;
    r.toBytes  = prefillBytes + mismatch.size();
    return r;
}

// ══════════════════════════════════════════════════════════════════════════
// 预填量（0.9.90 的口径，0.9.92 **已弃用**）：库算的生成后缀 ∩ 渲染侧真后缀
// ══════════════════════════════════════════════════════════════════════════
// ⚠ 这份口径在真机上**会导致 SIGABRT**，已从 `gbnf_from_json_schema` 移除；
//   保留函数只为让 root_literal_probe_test 能对照 0.9.90 那份回归（桩⑳ 用它）。
//   新代码**不要**再拿它算预填量。
//
// ══════════════════════════════════════════════════════════════════════════
// ⚠ 更正（0.9.93）：下面这段"space 不能匹配空串 -> 全体候选 -inf"的推断**已被证伪**
// ══════════════════════════════════════════════════════════════════════════
// 库内（`common/json-schema-to-grammar.cpp`，与 vendor 同版本，逐字）：
//     const std::string SPACE_RULE = "| \" \" | \"\\n\"{1,2} [ \\t]{0,20}";
// 经 `_rules["space"] = SPACE_RULE` 产出 GBNF：
//     space ::= | " " | "\n"{1,2} [ \t]{0,20}
// **第一个分支就是空** —— `space` 能匹配空串。所以"短喂让全体候选被置 -inf"
// 这条推断不成立，"必须喂满 41B"也就没有依据（喂满反而正是真机 SIGABRT 的成因）。
// 下面这段文字**保留原文**只作为诊断史（它解释的是为什么当初改成取库后缀），
// 不再作为任何实现的理由。详见 HTP-STATUS 第二十七节 27.1。
// 与上面 [grammar_prefill_from_literal] 的**区别**（当时以为的成因，事后证明诊断错位）：
// 上面那份取「grammar 首字面量 ∩ 真后缀」，而首字面量往往**短于**真后缀
// —— MiniCPM5/Qwen3 真后缀 41B，首字面量只有 22B。（当时把这 19B 的短喂解释成
// "grammar 卡在不能匹配空串的 space() 节点上、全体候选被置 -inf" —— **该解释已证伪**，
// 见上方更正。）
//
// 所以预填量改由**两个后缀来源**取交：
//   · `cp.generation_prompt`：库在同一次 templates_apply 里算出的生成后缀
//     （其长度直接反映库"以为"的续写位置）；
//   · 渲染侧真后缀：模型实际要接着续写的那段尾巴（已写进 prompt）。
// 二者取最长公共前缀 = 既保证喂进 grammar 的文本确实出现在 prompt 里
// （绝不喂进一段不存在的文本），又能在两侧理解分叉时让交集自动变短 ——
// 于是"分叉"这件事会在 prefill 字节数上直接显形，而不是静默短喂。
//
// 空串一律返回空串（= 不预填），退化成引入本修复之前的行为。
static inline std::string grammar_prefill_from_suffixes(const std::string & libSuffix,
                                                        const std::string & renderedSuffix) {
    if (libSuffix.empty() || renderedSuffix.empty()) return "";
    const size_t n = libSuffix.size() < renderedSuffix.size() ? libSuffix.size() : renderedSuffix.size();
    size_t i = 0;
    while (i < n && libSuffix[i] == renderedSuffix[i]) i++;
    return libSuffix.substr(0, i);
}

// ══════════════════════════════════════════════════════════════════════════
// grammar 预填的「就位点」判据（纯字符串运算，可单测）
// ══════════════════════════════════════════════════════════════════════════
// 为什么要有这一段：`prefill=22B peg_lit=22B` 这样的日志**读不出对错**。
// 前八处成因里，两次真机 abort 与 Qwen3 的"代码块"故障都发生在
// **预填之后、下一个 token 的采样那一步**，而那里此前一条日志都没有。
// 所以这里把"预填把 grammar 推到哪、真 prompt 的下一个字节是什么、两者是否一致"
// 算出来 —— 它不改变任何行为，只让那一步在日志里可见（第 5 段用于落日志）。
//
// 口径说明（**先前写反过，这里按库源码逐字改**）：
// 一律用**字节**比较。真 prompt 的续写可能从非 ASCII 字符开始，
// 库内 PEG 的切分本身也是按字节位置做的，所以字节口径与 grammar 的落点同源。
//
// 这段此前被写成"错位段以 `space` 规则无法匹配的字符开头就必须报警"，并断言
// `space` **不能匹配空串** —— 那句是错的：库内（`common/json-schema-to-grammar.cpp`）
//     const std::string SPACE_RULE = "| \" \" | \"\\n\"{1,2} [ \\t]{0,20}";
// 经 `_rules["space"] = SPACE_RULE` 产出 GBNF 的
//     space ::= | " " | "\n"{1,2} [ \t]{0,20}
// **第一个可选分支就是空** —— 它能匹配空串。据此推出的"短喂 19B 让全体候选变 -inf"
// 也就没有依据（见 HTP-STATUS 第二十七节 27.1 的更正）。所以这里不报警，
// 只**如实地把错位段打出来**：是它自己，还是它的长度，才是新信息。
struct grammar_fit_result {
    size_t      expectBytes = 0;   // 预填把 grammar 推到的字节位置
    size_t      actualBytes = 0;   // 真 prompt 里生成后缀的实际长度
    std::string mismatch;          // 真 prompt 在 expectBytes 处、而预填文本没有的那一段（空 = 无错位）
    int         aligned     = 0;   // 1 = 预填长度与真后缀长度一致
};

static inline grammar_fit_result grammar_fit_check(const std::string & prefill,
                                                   const std::string & genSuffix) {
    grammar_fit_result r;
    r.expectBytes = prefill.size();
    r.actualBytes = genSuffix.size();
    r.aligned     = (r.expectBytes == r.actualBytes) ? 1 : 0;
    // 错位段：真后缀中"预填之后的、而预填没覆盖到"的那一段。
    // 只在预填确实是真后缀前缀时才有意义（否则这是另一种故障：喂了 prompt 里没有的字节）。
    if (r.expectBytes < r.actualBytes && !prefill.empty() &&
        genSuffix.compare(0, prefill.size(), prefill) == 0) {
        r.mismatch = genSuffix.substr(r.expectBytes);
    }
    return r;
}

// 错位段是否"看起来像"被模板写死、而 grammar 不认的一段结构（think 块）。
// 只用于**日志可读性**（把可疑的错位标出来），不参与任何控制流，也不决定成败。
static inline bool looks_like_think_mismatch(const std::string & mismatch) {
    return mismatch.find("<think>") != std::string::npos ||
           mismatch.find("</think>") != std::string::npos;
}

// ══════════════════════════════════════════════════════════════════════════
// 把 grammar 根节点字面量对齐到**真后缀**（第十一处成因，0.9.97 修）
// ══════════════════════════════════════════════════════════════════════════
// `0.9.96` 想修的是"预填之后还剩一段错位（`<think>\n\n</think>\n\n`）没被 grammar 咽下"，
// 做法是**把错位段喂给 grammar**（逐字节试探、全成功才留下）。真机复测：
//
//     [schema] 错位段未被 grammar 接受（19B），落点保持 22B（退回旧行为，不硬喂）
//     [schema] 已挂载 grammar 采样器：gbnf=1099B root=root prefill=3 tok(已预填) fed=22B/22B
//
// 既不是"没试"，也不是"重放中断" —— 是**第一个字节 `<` 就被 grammar 判非法**。
// 为什么非法：grammar 里**根本没有 think 产生式**。库内 Qwen3 分支
// （`common_chat_params_init_qwen3_coder`，与 vendor 的 librnllama 同源）：
//
//     auto reasoning = p.eps();
//     if (supports_reasoning && extract_reasoning) {
//         reasoning = p.optional("<think>" + p.space() + ... + "</think>");
//     }
//     ...
//     auto extract_reasoning = inputs.reasoning_format != COMMON_REASONING_FORMAT_NONE;
//
// 而 `common_chat_templates_inputs::reasoning_format` 的 C++ 默认值是
// `COMMON_REASONING_FORMAT_NONE`，宿主侧**从未赋值**（全仓库 grep 无一处）。
// 于是 `extract_reasoning=false` -> `reasoning = p.eps()` -> 根节点退化成
//
//     root ::= "<|im_start|>assistant\n" <json-schema 的 rule>       ← 没有 think 那一支
//
// **这一段错位段是 grammar 的产生式**里不存在的字节，靠"喂"无论如何喂不进去 ——
// `0.9.96` 的方案在构造上就不可能成立，真机那行"未被接受"是对**正确行为**的如实记录。
//
// ── 本函数做什么：把根节点那个字面量换成**真后缀** ─────────────────────────
// 库的两份产物对"续写位置"理解不同（真机读数早已同时给出）：
//     cp.generation_prompt = 41B  `<|im_start|>assistant\n<think>\n\n</think>\n\n`
//     cp.grammar 根字面量  = 22B  `<|im_start|>assistant\n`
// 差的那 19B 就是模板**写死在 prompt 里**的思考块（Qwen3 关思考那一支）。
// grammar 不认它，是因为库按 `reasoning_format=NONE` 构建 PEG 时**没把它算进去**。
//
// 修法不碰库、不改 prompt、不换思考开关：**在 GBNF 文本上把根节点那个字面量
// 由 22B 换成 41B**。换完之后 grammar 自己就声明"我从真后缀之后开始"，与模型
// 实际续写的位置**逐字节对齐**：
//
//     root ::= "<|im_start|>assistant\n<think>\n\n</think>\n\n" <json-rule>
//
// 于是预填也可以名正言顺地喂满 41B（那些字节现在是 **grammar 自己声明的字面量**，
// 不是"我们硬塞进去的"）—— 这正是本处与此前几次的关键区别：
//   · `0.9.90`：grammar 仍是 22B，却把 41B **按 token 整段 accept** -> 空栈 abort；
//   · `0.9.96`：同样 22B 的 grammar，改用逐字节试探 -> 第一个字节就被拒；
//   · 本轮：**先把 grammar 改成 41B**，再去喂 41B —— 每一步都在 grammar 的
//     产生式之内，既不会 abort，也不会被拒。
//
// ── 判据（可离线单测）─────────────────────────────────────────────────────
// 只做一件事：把 GBNF 里**根节点那个字面量**的转义形态换掉。
//   · 找不到那个字面量（库改了 GBNF 形状 / 别的模板）-> 原样返回，一个字不动；
//   · 没有错位段（真后缀 == 字面量）-> 原样返回（本来就是对齐的）；
//   · 真后缀不以字面量开头（另一类故障）-> 原样返回（不猜）。
// 换的时候只替换**第一处**出现（根规则在最前，见 build_grammar 的输出顺序），
// 且要求它前面是 `root ::= ` —— 避免改到别处恰好同形的字面量。
// ⚠ 分隔符是 ` ::= ` 不是 ` = `：GBNF 由库内 `format_grammar()` 拼出，逐字为
//     ss << kv.first << " ::= " << kv.second << '\n';
//   写成 ` = ` 会永远匹配不上 —— 而对齐静默失效的症状恰好是"什么都没变"，
//   与"没修"逐字相同，只能靠真机读数分辨。所以单测里专门留了 ⑩ 钉这个分隔符。
//
// GBNF 的转义口径必须与库内 `format_literal` **逐字一致**。库内那张表是**六个**字符
// （`common/json-schema-to-grammar.cpp` 与用户 fork 的 `common/common.h` 同源，逐字）：
//
//     static std::unordered_map<char, std::string> GRAMMAR_LITERAL_ESCAPES = {
//         {'\r', "\\r"}, {'\n', "\\n"}, {'"', "\\\""}, {'-', "\\-"}, {']', "\\]"}, {'\\', "\\\\"}
//     };
//
// ⚠ 这里先前只转义了 **四个**（`\r` `\n` `"` `\`），漏掉 `-` 与 `]` —— 这是一条**真的**
//   会炸的缺陷，只是真机 Qwen3 的 41B 后缀恰好只含 `\n`，于是一直没显形：
//   · `find(needle)` 用我们的转义，而 GBNF 里是库的转义 -> 一旦字面量里出现 `-` 或 `]`
//     就**找不到** needle -> 对齐静默失效（症状与"没修"逐字相同）；
//   · 即便 needle 找得到、把 realSuffix 写回 GBNF 时少转义 `-` / `]`，产出的 GBNF
//     就**语法错**（`-` 在字符类里有区间含义、`]` 会提前闭合字符类）。
//   两条都属于"换一种模型/模板才炸"的那类，离线单测里已按库内表逐字钉住（见
//   root_literal_probe_test 的 `-` / `]` 两条断言与 `tools/run_grammar_prefill_bytes_tests.sh`）。
static inline std::string gbnf_escape_literal(const std::string & literal) {
    std::string out;
    out.reserve(literal.size() + 2);
    for (char c : literal) {
        switch (c) {
            case '\r': out += "\\r";  break;
            case '\n': out += "\\n";  break;
            case '"':  out += "\\\""; break;
            case '-':  out += "\\-";  break;   // 库内表里有它（字符类里 `-` 是区间符号）
            case ']':  out += "\\]";  break;   // 库内表里有它（`]` 会提前闭合字符类）
            case '\\': out += "\\\\"; break;
            default:   out += c;      break;
        }
    }
    return out;
}

static inline std::string gbnf_realign_root_literal(const std::string & gbnf,
                                                    const std::string & pegLiteral,
                                                    const std::string & realSuffix) {
    if (gbnf.empty() || pegLiteral.empty() || realSuffix.empty()) return gbnf;
    // 没有错位段（真后缀与字面量逐字节相同）-> 本来就是对齐的，一个字都不动。
    if (realSuffix == pegLiteral) return gbnf;
    // 真后缀必须**以字面量开头**才是"同一个根节点、只差一段尾巴"这一类；
    // 否则是另一种故障（库的字面量不在 prompt 的续写位置上），不猜。
    if (realSuffix.size() <= pegLiteral.size()) return gbnf;
    if (realSuffix.compare(0, pegLiteral.size(), pegLiteral) != 0) return gbnf;

    // 只认**根规则**那一处：GBNF 文本里根规则以 `root ::= ` 起行（见 format_grammar()）。
    static const std::string kRootPrefix = "root ::= ";
    // ⚠ 口径必须是"**把 pegLiteral 转义一遍**再去找"，而不是"把 GBNF 里的字面量解回来
    //   与原文比"：GBNF 文本里那一段**已经**是 `gbnf_escape_literal(pegLiteral)` 的形态
    //   （库内 format_literal 的转义表与本文件 gbnf_escape_literal 逐字一致）。
    //   这里**绝不能**再解一次转义：GBNF 里的 `\\n` 是"反斜杠 + n"两个可见字符，
    //   而真机 pegLiteral 末尾那个换行是 **0x0A 一个字节** —— 两者逐字节**本来就相同**
    //   （同样两个字符）。多解一道就变成 0x0A，与 GBNF 文本对不上，find 恒 npos，
    //   对齐静默失效（症状与"没修"逐字相同）。上一版就是在这里多解了一道。
    const std::string fromLit = "\"" + gbnf_escape_literal(pegLiteral) + "\"";
    const std::string needle = kRootPrefix + fromLit;
    const size_t at = gbnf.find(needle);
    if (at == std::string::npos) return gbnf;   // 形状不符 -> 不改写
    // 只改第一处（就是根规则自己）：把它之后的那个字面量换成真后缀。
    const size_t litAt = at + kRootPrefix.size();
    const std::string toLit = "\"" + gbnf_escape_literal(realSuffix) + "\"";
    std::string out = gbnf;
    out.replace(litAt, fromLit.size(), toLit);
    return out;
}

// ══════════════════════════════════════════════════════════════════════════
// 预填的**字节账**：声明量 != 实喂量 = grammar 被永久写坏（第十二处成因，0.9.99 修）
// ══════════════════════════════════════════════════════════════════════════
// `0.9.97` 把 grammar 的根字面量从 22B 改写成了 41B（真后缀），判据是
// `gbnf=916B -> 939B`、`根字面量对齐：peg_lit=22B -> 真后缀=41B（已对齐）` —— 真机
// 这两行**都出现了**，说明改写本身生效了。但同一份日志里还有一个数字被读漏了：
//
//     [schema] 已挂载 grammar 采样器：gbnf=939B root=root prefill=7 tok(已预填) fed=41B/41B
//
// `fed=41B/41B` 是**算出来的**（= prefillEff.size()），不是"真喂进去多少"。
// 而 grammar 是**字节级**状态机，预填那一跳走的是**token**（`llama_sampler_accept`）：
// 一个 token 是原子的，它的 piece 里可能**同时**含"grammar 声明的字面量"与
// "字面量之后、模型还没开始写"的字节。真机上 Qwen3-0.6B 的
// `<|im_start|>assistant\n<think>\n\n</think>\n\n` 若被切成"以 41B 的字面量为前缀
// 的某个 token"，喂进去的字节就**多于** 41B，grammar 被推过它自己声明的落点。
//
// 推过之后不抛异常（上游 `accept_chr` 只是把匹配不上的栈丢掉），代价是**语法被
// 悄悄写坏**：grammar 站在"JSON 已经写完"的地方，此后 JSON 的 `{`/`[` 全都匹配不上
// -> 全体候选置 -inf。离线上把这条链逐字节复算过（见
// tools/run_grammar_prefill_bytes_tests.sh 的 A/B/C 三组对照）：喂过界之后，
// 下一步无论吐什么都会把栈清空并抛 "empty grammar stack"。
//
// ── 为什么这不是"再多喂一点"能解决的 ──────────────────────────────────────
// 与 `0.9.90` 的 SIGABRT 是同一条链的两端：
//   · `0.9.90`：把 41B **按 token 整段 accept**，末位 piece 跨界 -> 空栈 abort（进程死）；
//   · 本轮：改写 grammar 之后按 token 喂，piece 跨过 **grammar 自己声明的 41B**
//           -> 栈被推过界，不 abort，但**语法永久失效**（请求活着，输出不受约束）。
// 两者的共同前提都是"**用 token 去喂一个字节级的东西**"。所以本处的修法不是调数字，
// 而是**改口径**：预填一律走 `llama_grammar_accept_str`（字节级，逐字节喂），
// 于是"喂进去的字节数"与"grammar 声明的字节数"**恒等**，token 边界再也插不进来。
//
// ── 本函数做什么：算**能喂多少**（纯字符串，可离线单测）─────────────────────
// 输入是"grammar 自己声明的那个字面量"（`declared`，= 改写后的真后缀）与
// "预填文本"（`prefill`，同一份）。判据只有一条：
//
//     **预填文本必须是声明字面量的前缀，且两者长度相等才叫"账平"。**
//
//   · 不等 -> 只喂到 `min(两者公共前缀长度)`，并把差额报出来（留痕，不静默）；
//   · 声明字面量不是预填文本的前缀（另一类故障：改写没生效 / 模板换了）
//     -> **不喂**（退回旧行为），并报原因。
//
// 为什么不在这里"补齐"到声明长度：预填文本以外的字节**模型还没写**，
// 凭空补进去就是替模型写它下一步要写的东西 —— 那正是 `0.9.96` 想干的事，
// 也已经被真机证明不可能成立（grammar 的产生式里没有那段）。
struct prefill_byte_plan {
    std::string feed;          // 实际要喂的字节（字节级口径，恒为 declared 的前缀）
    size_t      declaredBytes = 0;  // grammar 声明的字面量长度（它"以为"模型已经说到哪）
    size_t      prefillBytes  = 0;  // 传入的预填量（对齐成立时 = 真后缀长度）
    int         balanced      = 0;  // 1 = 声明量 == 实喂量（账平）
    int         usable        = 0;  // 1 = 可以喂（前缀关系成立）；0 = 不喂，退回旧行为
    std::string why;           // 不喂 / 不满的原因（进日志）
};

static inline prefill_byte_plan plan_grammar_prefill_bytes(const std::string & declared,
                                                           const std::string & prefill) {
    prefill_byte_plan r;
    r.declaredBytes = declared.size();
    r.prefillBytes  = prefill.size();
    if (prefill.empty()) {
        r.why = "预填量为空（不喂，退回旧行为）";
        return r;
    }
    if (declared.empty()) {
        // grammar 没声明字面量（例如根节点不是字面量）：那就照原样喂，账按预填量算。
        r.feed     = prefill;
        r.usable   = 1;
        r.balanced = 1;
        r.why      = "grammar 未声明字面量（按预填量喂）";
        return r;
    }
    // ⚠ 前缀关系只在 **prefill.size() <= declared.size()** 时才可能成立：
    //   反了（`prefill.size() < declared.size()` 就判不喂）会把**短喂**也一起挡掉 ——
    //   而短喂正是 0.9.94 之前的正常行为，挡掉它等于把"能约束的请求"降级成无约束。
    //   这个方向错得很隐蔽：`declared.compare(0, prefill.size(), prefill)` 在两侧长度
    //   不同的时候**仍然返回 0**（它只比前 prefill.size() 个字节），如果条件写成
    //   "size 更小 **或** 前缀不等"，短喂那条路就会被后面那半句放过、被前面那半句挡住 ——
    //   单测里"短喂照喂"那条断言当场变红（本轮实测），否则它会一路静默到真机。
    if (prefill.size() > declared.size() ||
        declared.compare(0, prefill.size(), prefill) != 0) {
        // 预填文本**不是**声明字面量的前缀 —— 这是"两者对续写位置理解不同"那一类，
        // 补不齐、也不该猜。**不喂**：grammar 从根节点起步是它自己认识的起点，
        // 而喂一段它不认的字节正是 0.9.90/0.9.96 两次失败的做法。
        r.why = "预填文本不是 grammar 声明字面量的前缀（不喂，退回旧行为）";
        return r;
    }
    r.usable = 1;
    // 预填是声明量的前缀（含相等）：能喂的就是预填自己。
    // 少于声明量 -> 照样喂（那是 0.9.94 之前的行为），但**必须留痕** ——
    // "账不平"是判据，不是错误：短喂本身不置 -inf（space 能匹配空串）。
    r.feed     = prefill;
    r.balanced = (prefill.size() == declared.size()) ? 1 : 0;
    if (!r.balanced) {
        r.why = "预填少于 grammar 声明量（短喂；grammar 不会因此写坏）";
    }
    return r;
}
