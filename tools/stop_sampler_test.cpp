// stop_sampler_test.cpp — `stop` / `stop_sequences` 匹配语义的宿主侧单测
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么必须有这一份（而不是靠真机试）
// ═══════════════════════════════════════════════════════════════════════════
// 拦 stop 的采样器跑在**生成循环里**，出错的表现只有两种，都极难归因：
//   A) 漏拦   —— stop 之后的内容被当正文吐给客户端（stream=true 时**收不回来**）；
//   B) 多拦   —— 正文被吞掉一截（没有异常、没有日志，只表现为"模型少说了半句"）。
// 两者在真实模型上的触发条件（stop 串跨 token、词表里没有 continuation…）
// 靠人工试几乎试不出来，所以这里把语义钉死。
//
// 本文件直接 `#include "stop_sequences.h"` —— 与 llama_jni.cpp **同一份源码**，
// 不是照抄。在测试里重抄逻辑等于没测：改错了线上、测试照样绿。
//
// 采样器外壳（apply/accept/命中后结束本轮）依赖 Session 与采样链，宿主侧起不来，
// 因此这里只测它调用的两个纯函数，并显式模拟"暂扣期间不接受"这条外壳约定。
//
// 运行：bash tools/run_stop_sampler_tests.sh
#include <cstdio>
#include <string>
#include <vector>

#include "stop_sequences.h"

static int g_fail = 0;

static void ck(const char * name, bool ok) {
    printf("%s  %s\n", ok ? "PASS" : "FAIL", name);
    if (!ok) g_fail++;
}

// ── 模拟「逐 token 走一遍生成循环」的放行结果 ─────────────────────────────
// 返回 {放行文本, 是否命中}。pieces 是各步采样出来的 token 文本。
//
// 关键：这里**不再手抄**判定顺序，而是直接调 stop_sequences.h:advance ——
// 与 llama_jni.cpp:stop_sampler_accept **同一份源码**。
// 此前是手抄的（注释写着"刻意复刻"），结果抄漏了一条早退，线上多 token 的 stop
// 卡死、测试全绿。重抄一律算没测，这类教训只犯一次。
//
// 传 vocab 时用它做 continuation 自检（空 vocab = 模拟"总能继续"）。
struct StepResult {
    std::string out;
    bool hit;
};

static StepResult run(const std::vector<std::string> & stops,
                      const std::vector<std::string> & pieces,
                      const std::vector<std::string> & vocab) {
    stopseq::MatchState st;
    st.stops = stops;
    bool head[256] = {false};
    for (const std::string & v : vocab) if (!v.empty()) head[(unsigned char) v[0]] = true;
    // 空 vocab 沿用**改动前**的语义：表示"不复刻位图"，即只要 withheld 非空就
    // 无条件认为还能继续（这些用例关心的是匹配语义本身，不是 continuation 自检）。
    // 自检那条路径由专门用例（传非空 vocab）覆盖，不能靠空 vocab 表达"接不上"——
    // 那样会把"没有词表信息"和"词表里拼不出续接"两件事混成一件。
    const bool heads_ready = !vocab.empty();
    for (const std::string & p : pieces) {
        stopseq::advance(st, p, [&head](unsigned char b) { return head[b]; }, heads_ready);
    }
    // 未命中时，暂扣部分按语义"还没确定"，但它是会被下发的正文 —— 计入 out。
    return { st.generated + st.withheld, st.hit };
}

// ── 模拟 nativeStep 的**真实下发路径**（含 emitted 增量记账）────────────────
// run() 返回的是"最终应该出现在响应里的文本"（终态视角）；
// 这里返回的是"逐 token 实际下发给客户端的内容"（时序视角）。
// 两者都要对：前者防吞字/漏拦，后者防"暂扣的前缀被当成正常 token 提前发出去"
// 以及"暂扣被放行时那截永远发不出去"——它们在终态里看不出来，只在下发时序里暴露。
static StepResult run_stream(const std::vector<std::string> & stops,
                             const std::vector<std::string> & pieces,
                             const std::vector<std::string> & vocab) {
    stopseq::MatchState st;
    st.stops = stops;
    bool head[256] = {false};
    for (const std::string & v : vocab) if (!v.empty()) head[(unsigned char) v[0]] = true;
    const bool heads_ready = !vocab.empty();
    size_t emitted = 0;
    std::string out;
    for (const std::string & p : pieces) {
        stopseq::advance(st, p, [&head](unsigned char b) { return head[b]; }, heads_ready);
        if (st.hit) return { out, true };          // nativeStep: 命中 -> 结束，不再下发
        // nativeStep: 只把状态机**新放行**的部分并入下发缓冲
        if (emitted > st.generated.size()) emitted = st.generated.size();
        out += st.generated.substr(emitted);
        emitted = st.generated.size();
    }
    return { out, false };
}

int main() {
    // ── 1. 基本命中：stop 串本身不出现在输出里 ──
    {
        auto r = run({ "</end>" }, { "你", "好", "</end>", "垃圾" }, {});
        ck("命中即停：stop 之后的 token 不出现", r.out == "你好");
        ck("命中标记置位", r.hit);
    }
    {
        auto r = run({ "</end>" }, { "你好", "</end>" }, {});
        ck("整段 token 正好等于 stop", r.out == "你好" && r.hit);
    }

    // ── 2. stop 串跨 token（这次改动最核心的场景）──
    {
        // stop = "</end>"，但模型拆成 "</e" + "nd>" 两个 token 输出
        auto r = run({ "</end>" }, { "答", "</e", "nd>" }, {});
        ck("跨 token 命中：中间那截不被吐出来", r.out == "答");
        ck("跨 token 命中标记置位", r.hit);
    }
    {
        // 拆得更碎
        auto r = run({ "</end>" }, { "答", "<", "/", "e", "n", "d", ">" }, {});
        ck("逐字符拆分的 stop 也能命中", r.out == "答" && r.hit);
    }

    // ── 3. 只是前缀、最后没命中：内容必须**完整**放出来（不许吞字）──
    {
        auto r = run({ "</end>" }, { "</e", "xx" }, {});
        ck("前缀后分叉：暂扣部分必须补回", r.out == "</exx");
        ck("前缀后分叉：不算命中", !r.hit);
    }
    {
        // 一条很长的 stop，模型输出它的前半截但后面分叉了
        auto r = run({ "STOP_SEQUENCE_2024" }, { "STOP_SEQ", "UENCE_20", "25" }, {});
        ck("长 stop 前半截分叉：一字不漏", r.out == "STOP_SEQUENCE_2025" && !r.hit);
    }

    // ── 4. 一条 stop 的尾部恰好是另一条 stop 的前缀 ──
    {
        // 先出现 "ab"：它是 "abc" 的真前缀，必须暂扣
        auto r = run({ "abc", "ab" }, { "x", "ab" }, {});
        ck("尾部是另一条 stop 时按命中处理（ab 命中）", r.out == "x" && r.hit);
    }
    {
        auto r = run({ "abc" }, { "x", "ab", "d" }, {});
        ck("暂扣后分叉：'ab' 必须补回", r.out == "xabd" && !r.hit);
    }

    // ── 5. 多条 stop，命中任意一条都要停 ──
    {
        auto r = run({ "AAA", "BBB" }, { "1", "BB", "B", "2" }, {});
        ck("第二条 stop 跨 token 命中", r.out == "1" && r.hit);
    }
    {
        auto r = run({ "AAA", "BBB" }, { "1", "2", "3" }, {});
        ck("多条 stop 都不命中时原文放行", r.out == "123" && !r.hit);
    }

    // ── 6. 空 stop 串必须被忽略（否则 indexOf("")==0 语义 -> 输出恒为空）──
    {
        auto r = run({ "" }, { "a", "b", "c" }, {});
        ck("空 stop 串被忽略，不吞任何正文", r.out == "abc" && !r.hit);
    }
    {
        auto r = run({ "", "" }, { "abc" }, {});
        ck("多个空 stop 串同样被忽略", r.out == "abc" && !r.hit);
    }

    // ── 7. 词表 continuation 自检：拼不出 continuation 时必须放行，不许吞字 ──
    {
        // stop = "</end>"，模型输出 "<"（真前缀），但词表里**没有任何 token**
        // 能继续 "</end>" -> 必须立刻放行 "<"，否则永久吞字
        auto r = run({ "</end>" }, { "<", "x" }, { "x", "y" });
        ck("词表无法继续该前缀：立即放行，不吞字", r.out == "<x" && !r.hit);
    }
    {
        // 词表里**有**能继续的 token -> 保持暂扣，最终正确命中。
        // 注意这里给的是「能接着 withheld 往下拼」的 token 集合（真实词表总是远大于此），
        // 给少了会走上一支的"立即放行"分支 —— 那是自检在正常工作，不是 bug。
        auto r = run({ "</end>" }, { "<", "/", "e", "n", "d", ">" }, { "/", "e", "n", "d", ">" });
        ck("词表可继续时保持暂扣并正确命中", r.out.empty() && r.hit);
    }
    {
        // 自检必须是"保守"的：拿不准（词表能继续）就继续等，不能提前放行。
        // 这一条与上一条成对存在 —— 一旦有人把 can_continue 改成"默认 false"，
        // 上一条会挂；改成"默认 true"而漏掉"无法继续"，再上一条会挂。
        auto r = run({ "</end>" }, { "前", "<", "/", "e" }, { "/" });
        ck("自检放行后正文一字不漏（'</e' 被补回）", r.out == "前</e" && !r.hit);
    }

    // ── 7.5 回归：暂扣后必须**继续**推进状态机（曾经的致命早退）──
    // 线上 accept 曾经写成"withheld 非空就直接 return"，于是多 token 的 stop
    // 第一步暂扣之后就再也不判了：stop 永不命中（走不到 exact），暂扣部分
    // 永不放行（跑不到 continuation 自检），生成被静默冻结在那里。
    // 这一组用例专门钉住"暂扣之后每步都还在推进"。
    {
        // 最直白的复现：跨 token 的 stop，第二步必须能命中。
        // 旧实现里这一条会挂（hit 恒 false，out 里堆着 withheld 却永远不发）。
        auto r = run({ "</end>" }, { "<", "/", "e", "n", "d", ">" }, { "/", "e", "n", "d", ">" });
        ck("回归：暂扣后仍能推进到命中（不许卡死）", r.hit && r.out.empty());
    }
    {
        // 对称的那一半：暂扣后分叉，正文必须一字不漏地放出来。
        // 旧实现里 withheld 永不放行，这一条也会挂（正文被永久吞掉）。
        auto r = run({ "</end>" }, { "前", "<", "/", "e", "X" }, { "/", "e", "X" });
        ck("回归：暂扣后分叉，正文一字不漏放行", r.out == "前</eX" && !r.hit);
    }
    {
        // 暂扣期间**多个 token** 连续推进（不只是"再喂一个就分叉"）。
        auto r = run({ "END_OF_TURN" }, { "a", "END", "_OF", "_TU", "RN" }, { "_", "O", "F", "T", "U", "R", "N" });
        ck("回归：暂扣可跨多个 token 连续推进到命中", r.out == "a" && r.hit);
    }
    {
        // 命中之后不得把被暂扣的 stop 前缀拼回输出（stop 串本身不能出现）。
        auto r = run({ "STOP" }, { "正", "文", "S", "T", "O", "P" }, { "T", "O", "P" });
        ck("回归：命中后暂扣区必须清空（stop 串不出现）", r.out == "正文" && r.hit);
    }

    // ── 7.6 下发时序：暂扣的前缀不能被当成正文提前发出去 ──
    // 这一组钉的是 nativeStep 侧的账（run() 的终态视角看不出来）：
    //   · 漏拦：暂扣期间仍把 token 当正文发出去 -> stop 串的前缀泄漏；
    //   · 多拦：暂扣被放行时只补当前 token，最早那截再也发不出去。
    {
        auto r = run_stream({ "</end>" }, { "答", "</e", "nd>", "垃圾" }, { "/", "e", "n", "d", ">" });
        ck("下发时序：跨 token 命中时前缀不泄漏", r.out == "答" && r.hit);
    }
    {
        auto r = run_stream({ "</end>" }, { "前", "</e", "xx" }, { "/", "e", "x" });
        ck("下发时序：暂扣被放行时一字不漏", r.out == "前</exx" && !r.hit);
    }
    {
        // 暂扣跨多个 token 后分叉：这些 token 一个都不能丢
        auto r = run_stream({ "END_OF_TURN" }, { "a", "END", "_OF", "x" }, { "_", "O", "F", "x" });
        ck("下发时序：多 token 暂扣后分叉，全部补回", r.out == "aEND_OFx" && !r.hit);
    }
    {
        // 与终态视角对照：两个视角的结论必须一致
        auto rs = run_stream({ "</end>" }, { "正", "文", "</e", "XX" }, { "/", "X" });
        auto rf = run({ "</end>" }, { "正", "文", "</e", "XX" }, { "/", "X" });
        ck("下发时序与终态一致", rs.out == rf.out && rs.hit == rf.hit);
    }

    // ── 7.7 回归：没有 stop 采样器时正文必须照常下发 ──
    // nativeStep 现在按"有没有挂 stop 采样器"分两条路：
    //   挂了 -> 只发状态机新放行的增量；没挂 -> 无条件下发 token_to_piece(tok)。
    // 若把"没挂"也走增量路径，released 恒为空，**所有普通对话的输出都会变成空**。
    // 这是最严重的一类回归，单独钉一条。
    {
        // 复刻"没挂采样器"的下发路径：每步就是把 token 文本原样累加
        std::string out;
        for (const char * p : { "你", "好", "世界" }) out += p;
        ck("回归：无 stop 采样器时正文照常下发", out == "你好世界");
    }
    {
        // 对照：挂了采样器但一次都没命中，下发内容也必须与无采样器时**逐字节一致**
        auto r = run_stream({ "ZZZ" }, { "你", "好", "世界" }, { "Z" });
        ck("回归：挂采样器但未命中，输出与无采样器一致", r.out == "你好世界" && !r.hit);
    }

    // ── 7.8 G-1：stop 起点落在 token **内部**（本组是修复的核心场景）──
    // 旧实现枚举"stop 能从输出的哪个位置开始"，却要求匹配**从 next[0] 起**，
    // 于是 stop 起点落在 token 偏移 > 0 处时整条判据看不见 —— stop 完全不生效，
    // 而且不报错。旧单测每个用例的 stop 都恰好从 token 边界开始，所以从未暴露。
    //
    // 注意这一组用的是**多字符 token**（BPE 一个 piece 就是多字符，`</end>`、
    // `abc\n` 都是常态），不是逐字符喂。
    {
        auto r = run({ "\n" }, { "abc\n" }, {});
        ck("G-1a：stop 在 token 内部结尾（abc\\n 配 stop \\n）", r.out == "abc" && r.hit);
    }
    {
        auto r = run({ "</end>" }, { "</end>" }, {});
        ck("G-1a：整 token 正好以 stop 结尾", r.out.empty() && r.hit);
    }
    {
        auto r = run({ "```" }, { "x```" }, {});
        ck("G-1a：stop 在 token 内部（x``` 配 stop ```）", r.out == "x" && r.hit);
    }
    {
        auto r = run({ "User:" }, { "xUser:" }, {});
        ck("G-1a：stop 在 token 内部（xUser: 配 stop User:）", r.out == "x" && r.hit);
    }
    {
        // 中文正文 + token 内部的 stop：正文必须一字不漏，stop 必须不出现
        auto r = run({ "</end>" }, { "答</end>" }, {});
        ck("G-1a：中文正文 + token 内部 stop", r.out == "答" && r.hit);
    }
    // ── 7.9 G-1：stop **不在**结尾时不得假命中（旧实现的另一半）──
    // 旧实现 `len(next) >= rem` 只比前 rem 字节，却把**整个 token** 吞掉：
    // stop 后面还有别的字节时也判成命中，正文被整段丢掉。
    {
        auto r = run({ "END" }, { "ENDING" }, {});
        ck("G-1b：stop 不在结尾（ENDING 配 END）不得命中", r.out == "ENDING" && !r.hit);
    }
    {
        auto r = run({ "ab" }, { "abc" }, {});
        ck("G-1b：stop 不在结尾（abc 配 ab）不得命中", r.out == "abc" && !r.hit);
    }
    {
        auto r = run({ "\n\n" }, { "\n\nmore" }, {});
        ck("G-1b：stop 不在结尾（\\n\\nmore 配 \\n\\n）不得命中", r.out == "\n\nmore" && !r.hit);
    }
    // ── 7.10 与「非流式截断」对齐：同一段输出两侧切点必须相同 ──
    // Kotlin 侧 `StopSequences.truncate` 取**首个**命中点。这里逐 token 判定的
    // 结论必须与它一致（**token 边界**上的一致 —— 一个 token 内部即使藏着更早的
    // 命中点，采样循环也只能在边界处动手，这是逐 token 采样的固有限制）。
    {
        auto r = run({ "</end>" }, { "答", "</e", "nd>", "垃圾" }, { "/", "e", "n", "d", ">" });
        ck("与截断对齐：跨 token 命中，正文完整且不含 stop", r.out == "答" && r.hit);
    }
    {
        // stop 起点在 generated 内部（`答</e` 里的 `</e`），next 才收尾：
        // 旧实现会在 `</e` 那一步判"无关"而放行，于是 stop 再也接不成。
        auto r = run({ "</end>" }, { "答</e", "nd>" }, { "/", "e", "n", "d", ">" });
        ck("与截断对齐：stop 起点在已放行区内也必须能命中", r.out == "答" && r.hit);
    }
    {
        // 反面对照：起点在 generated 内部，但后面分叉 —— 必须一字不漏地放行
        auto r = run({ "</end>" }, { "答</e", "xx" }, { "/", "e", "x" });
        ck("起点在已放行区内且分叉：正文一字不漏", r.out == "答</exx" && !r.hit);
    }

    // ── 8. 空输入 / 空 stop 列表的边界 ──
    {
        auto r = run({}, { "a", "b" }, {});
        ck("无 stop 序列：原文放行", r.out == "ab" && !r.hit);
    }
    {
        auto r = run({ "X" }, {}, {});
        ck("无输出：空串且不命中", r.out.empty() && !r.hit);
    }

    // ── 9. 直接测纯函数本身（不经过模拟循环）──
    {
        stopseq::MatchState st;
        st.stops = { "END" };
        bool exact = false;
        ck("relevant: 完全相等 -> exact",
           stopseq::relevant(st, "END", &exact) && exact);
        ck("relevant: 真前缀 -> 非 exact",
           stopseq::relevant(st, "EN", &exact) && !exact);
        ck("relevant: 无关 -> false", !stopseq::relevant(st, "XYZ", &exact));
        ck("relevant: 空候选 -> false", !stopseq::relevant(st, "", &exact));
    }
    {
        // 已经放行了 "EN"，再来 "D" 应当命中（暂扣/已放行都要参与匹配）
        stopseq::MatchState st;
        st.stops = { "END" };
        st.generated = "EN";
        bool exact = false;
        ck("relevant 计入已放行部分", stopseq::relevant(st, "D", &exact) && exact);
    }
    {
        // 不变式：暂扣 != 已放行。full() 必须把两者拼起来
        stopseq::MatchState st;
        st.stops = { "END" };
        st.generated = "EN";
        st.withheld = "";
        ck("full() = generated + withheld", st.full() == "EN");
        st.withheld = "D";
        ck("full() 含暂扣部分", st.full() == "END");
        st.reset();
        ck("reset 清空全部状态", st.full().empty() && !st.hit && st.stops.size() == 1);
    }

    // ── 10. can_continue_fast 的字节级保守判定 ──
    {
        stopseq::MatchState st;
        st.stops = { "</end>" };
        st.generated = "";
        st.withheld = "</e";
        // 词表里有以 'n' 开头的 piece（要继续 "</end>" 就需要 'n'）
        auto has_n = [](unsigned char b) { return b == 'n'; };
        ck("can_continue_fast: 有 token 能接上 -> true", stopseq::can_continue_fast(st, has_n));
        auto has_z = [](unsigned char b) { return b == 'z'; };
        ck("can_continue_fast: 没有 token 能接上 -> false", !stopseq::can_continue_fast(st, has_z));
        ck("can_continue_fast: 暂扣为空 -> true（无需判定）",
           stopseq::can_continue_fast(stopseq::MatchState{ {}, "", "", false }, has_z));
        // 已经写完整条 stop 时不存在"继续"可言
        stopseq::MatchState full;
        full.stops = { "AB" };
        full.withheld = "AB";
        ck("can_continue_fast: 已完整匹配 -> false", !stopseq::can_continue_fast(full, has_n));
    }

    printf("\n%s\n", g_fail == 0 ? "=== stop_sampler_test 全部通过 ===" : "=== stop_sampler_test 失败 ===");
    return g_fail == 0 ? 0 : 1;
}
