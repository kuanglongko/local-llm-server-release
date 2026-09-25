// stop_match_test.cpp — 「stop 匹配语义」的行为对照测（真源码 vs 独立参考实现）
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么单有 stop_sampler_test.cpp 还不够
// ═══════════════════════════════════════════════════════════════════════════
// G-1 的形态是：**同一段文本、不同 tokenizer 分片，结论不同**。要抓住它，必须
// 拿"成千上万种分片"去撞，而不是挑几条顺手的用例。这里做两件事：
//
//   ① 参考实现对照：独立写一份**只按整串后缀**判定的参考（不复用头文件里的
//      relevant/hit_cut_index），随机生成 stop + 分片 + 喂 token，
//      断言逐字节一致。两侧都错成同一个样子的概率极低 —— 这正是它的价值。
//   ② 反例对照：把 `main` 的**旧实现**逐字抽出来跑同一批输入，断言它确实会不一致
//      （否则这条测试等于什么都没测）。
//
// 判定口径（真源码与参考实现共用的**唯一**定义）：
//   · 命中：喂完某个 token 后，`已喂入的整串`以某条 stop 结尾 -> 切在"末尾那条
//     stop 的起点"，本轮结束；
//   · 暂扣：整串的某个后缀是某条 stop 的真前缀 -> 该后缀不下发（等下一个 token）；
//   · 其余：整串（连暂扣）一起下发。
// 逐 token 采样固有的限制：一个 token **内部**藏着更早的命中点也只在 token 边界处
// 才动手 —— 参考实现按同一限制写（否则测的是另一个问题）。
//
// 运行：bash tools/run_stop_match_tests.sh
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

#include "stop_sequences.h"

// ── 独立参考实现（不复用 stop_sequences.h 的 relevant / hit_cut_index）────────
// 直接把"整串"拼出来，用 std::string 的 ends_with / prefix 语义判，写法与头文件
// 完全不同 —— 两侧同时写错的概率因此被压到很低。
static bool ref_ends_with(const std::string & s, const std::string & suf) {
    return s.size() >= suf.size() && s.compare(s.size() - suf.size(), suf.size(), suf) == 0;
}
static std::string ref_sim(const std::vector<std::string> & stops,
                           const std::vector<std::string> & pieces, bool * hit) {
    std::string full;
    for (const std::string & p : pieces) {
        full += p;
        size_t cut = full.size();
        bool any = false;
        for (const std::string & s : stops) {
            if (s.empty()) continue;
            if (ref_ends_with(full, s)) {
                any = true;
                const size_t at = full.size() - s.size();
                if (at < cut) cut = at;
            }
        }
        if (any) { *hit = true; return full.substr(0, cut); }
    }
    *hit = false;
    return full;
}

// ── 真源码路径：逐 token 喂 advance，最后取 generated + withheld ─────────────
static std::string hdr_sim(const std::vector<std::string> & stops,
                           const std::vector<std::string> & pieces, bool * hit,
                           bool heads_ready) {
    stopseq::MatchState st;
    st.stops = stops;
    bool head[256] = {false};
    for (const std::string & p : pieces)
        for (size_t i = 0; i < p.size(); i++) head[(unsigned char) p[i]] = true;
    for (const std::string & p : pieces) {
        stopseq::advance(st, p, [&head](unsigned char b) { return head[b]; }, heads_ready);
    }
    *hit = st.hit;
    return st.generated + st.withheld;
}

// ── 旧实现（逐字复刻 main 上的 relevant + advance 命中分支）──────────────
// 复刻而不是"另写一份近似"：它要能重现 G-1 的两个症状。
namespace oldimpl {
static bool relevant_old(const std::vector<std::string> & stops, const std::string & cur,
                         const std::string & next, bool * exact) {
    *exact = false;
    if (next.empty()) return false;
    for (int pass = 0; pass < 2; pass++) {
        for (const std::string & s : stops) {
            if (s.empty()) continue;
            for (size_t have = (cur.size() < s.size() ? cur.size() : s.size()); ; have--) {
                const size_t k = cur.size() - have;
                if (cur.compare(k, have, s, 0, have) == 0) {
                    const size_t rem = s.size() - have;
                    if (next.size() >= rem) {
                        if (next.compare(0, rem, s, have, rem) == 0 && pass == 0) {
                            *exact = true; return true;
                        }
                    } else if (pass == 1 && next.compare(0, next.size(), s, have, next.size()) == 0) {
                        return true;
                    }
                }
                if (have == 0) break;
            }
        }
    }
    return false;
}
static std::string old_sim(const std::vector<std::string> & stops,
                           const std::vector<std::string> & pieces, bool * hit) {
    std::string gen, wh;
    for (const std::string & p : pieces) {
        bool exact = false;
        if (!relevant_old(stops, gen + wh, p, &exact)) { gen += wh + p; wh.clear(); continue; }
        if (exact) { wh.clear(); *hit = true; return gen; }
        wh += p;   // 旧写法：裸累加
    }
    *hit = false;
    return gen + wh;
}
}  // namespace oldimpl

int main(int argc, char ** argv) {
    const int n = argc > 1 ? atoi(argv[1]) : 200000;
    std::mt19937 rng(20240922);
    static const char * ALPHA[] = {"a", "ab", "abc", "abc\n", "</e", " \xe7\xad\x94"};
    const int NA = (int) (sizeof(ALPHA) / sizeof(ALPHA[0]));
    auto pick = [&](const char * A, int minL, int maxL) {
        const size_t L = strlen(A);
        int len = minL + (int) (rng() % (unsigned) (maxL - minL + 1));
        std::string out;
        for (int k = 0; k < len; k++) out += A[rng() % L];
        return out;
    };

    int fail = 0, old_fail = 0, cases = 0;
    for (int t = 0; t < n; t++) {
        std::vector<std::string> stops, pieces;
        const int ns = 1 + (int) (rng() % 3);
        for (int i = 0; i < ns; i++) {
            const char * A = ALPHA[rng() % NA];
            stops.push_back(pick(A, 1, 4));
        }
        const int np = 1 + (int) (rng() % 6);
        for (int i = 0; i < np; i++) {
            const char * A = ALPHA[rng() % NA];
            pieces.push_back(pick(A, 1, 3));
        }
        bool h1 = false, h2 = false, h3 = false;
        const std::string got = hdr_sim(stops, pieces, &h1, false);
        const std::string ref = ref_sim(stops, pieces, &h2);
        const std::string old = oldimpl::old_sim(stops, pieces, &h3);
        cases++;
        if (got != ref || h1 != h2) {
            fail++;
            if (fail <= 5) {
                printf("FAIL  真实现与参考不一致: stops=[");
                for (auto & s : stops) printf("%s|", s.c_str());
                printf("] pieces=[");
                for (auto & p : pieces) printf("%s|", p.c_str());
                printf("] got=%s ref=%s hit=%d/%d\n", got.c_str(), ref.c_str(), (int) h1, (int) h2);
            }
        }
        if (old != ref || h3 != h2) old_fail++;
    }

    printf("随机对照：%d 例\n", cases);
    printf("  真实现 vs 参考         不一致 %d (期望 0)\n", fail);
    printf("  旧实现 vs 参考         不一致 %d (期望 > 0，这是 G-1 本身)\n", old_fail);

    int bad = 0;
    auto ck = [&](const char * name, bool ok) {
        printf("%s  %s\n", ok ? "ok  " : "FAIL", name);
        if (!ok) bad++;
    };
    ck("真实现与参考实现逐字节一致", fail == 0);
    ck("旧实现确实会不一致（反例对照成立，G-1 真实存在）", old_fail > 0);

    // ── 定向用例：G-1a 漏判 / G-1b 假命中，逐条给出期望值 ──
    {
        struct C { const char * name; std::vector<std::string> stops; std::vector<std::string> pieces; const char * expect; int hit; };
        std::vector<C> cs = {
            { "G-1a: stop=\\n, token=abc\\n", { "\n" }, { "abc\n" }, "abc", 1 },
            { "G-1a: stop=</end>, token=答</end>", { "</end>" }, { "\xe7\xad\x94</end>" }, "\xe7\xad\x94", 1 },
            { "G-1a: 跨 token 答案 + </e + nd>", { "</end>" }, { "\xe7\xad\x94", "</e", "nd>" }, "\xe7\xad\x94", 1 },
            { "G-1a: stop 起点在已放行区内（答</e | nd>）", { "</end>" }, { "\xe7\xad\x94</e", "nd>" }, "\xe7\xad\x94", 1 },
            { "G-1b: stop=END, token=ENDING", { "END" }, { "ENDING" }, "ENDING", 0 },
            { "G-1b: stop=ab, token=abc", { "ab" }, { "abc" }, "abc", 0 },
            { "G-1b: stop=\\n\\n, token=\\n\\nmore", { "\n\n" }, { "\n\nmore" }, "\n\nmore", 0 },
            { "分叉：</e 之后 xx 必须一字不漏", { "</end>" }, { "</e", "xx" }, "</exx", 0 },
            { "无 stop：原文放行", {}, { "a", "b" }, "ab", 0 },
        };
        for (auto & c : cs) {
            bool h = false;
            const std::string got = hdr_sim(c.stops, c.pieces, &h, false);
            ck(c.name, got == std::string(c.expect) && h == (c.hit != 0));
        }
    }

    // ── 不变式：withheld 非空时必须是某条 stop 的真前缀 ──
    // 这条不变式是 can_continue_fast 与"暂扣一定还有救"的全部依据。
    // 破坏它的形态是：某个字节被留在 withheld 里，但它根本不是任何 stop 的前缀 ——
    // 那时 withheld 永远不可能被拼成 stop，只能靠 can_continue_fast 兜底放行，
    // 而兜底一旦因词表信息不足而误判，就会永久吞字。
    {
        int viol = 0; long checked = 0;
        std::mt19937 r2(777);
        for (int t = 0; t < 80000; t++) {
            std::vector<std::string> stops, pieces;
            {
                std::string s; const int L = 1 + (int) (r2() % 4);
                for (int i = 0; i < L; i++) s += (char) ('a' + r2() % 3);
                stops.push_back(s);
            }
            const int np = 1 + (int) (r2() % 4);
            for (int i = 0; i < np; i++) {
                std::string p; const int L = 1 + (int) (r2() % 2);
                for (int k = 0; k < L; k++) p += (char) ('a' + r2() % 3);
                pieces.push_back(p);
            }
            stopseq::MatchState st; st.stops = stops;
            bool head[256] = {false};
            for (auto & p : pieces) for (size_t i = 0; i < p.size(); i++) head[(unsigned char) p[i]] = true;
            for (auto & p : pieces) {
                stopseq::advance(st, p, [&head](unsigned char b) { return head[b]; }, true);
                if (st.hit) break;
                checked++;
                if (!st.withheld.empty()) {
                    bool ok = false;
                    for (auto & s : stops)
                        if (!s.empty() && s.size() > st.withheld.size() &&
                            s.compare(0, st.withheld.size(), st.withheld) == 0) ok = true;
                    if (!ok) viol++;
                }
            }
        }
        printf("withheld 不变式：检查 %ld 个中间态，违反 %d\n", checked, viol);
        ck("withheld 非空时必是某条 stop 的真前缀", viol == 0);
    }

    // ── emitted 账：回退不得越过"已下发"边界 ──
    // split_release_withhold 会把 generated 的尾巴**回退**进 withheld，于是
    // `generated` 可能变短。若回退越过了"已下发"的界线，nativeStep 的
    // `emitted > generated.size()` 钳制就会把尚未下发的字节**永久丢掉**（或多发一遍）。
    // 这里按 nativeStep 的两条账各模拟一遍（钳制 vs 理想）逐字节对照。
    {
        std::mt19937 r3(31337);
        int clampFired = 0, mismatch = 0;
        for (int t = 0; t < 80000; t++) {
            std::vector<std::string> stops, pieces;
            {
                std::string s; const int L = 1 + (int) (r3() % 3);
                for (int i = 0; i < L; i++) s += (char) ('a' + r3() % 4);
                stops.push_back(s);
                if (r3() % 2) {
                    std::string s2; const int L2 = 1 + (int) (r3() % 3);
                    for (int i = 0; i < L2; i++) s2 += (char) ('a' + r3() % 4);
                    stops.push_back(s2);
                }
            }
            const int np = 1 + (int) (r3() % 5);
            for (int i = 0; i < np; i++) {
                std::string p; const int L = 1 + (int) (r3() % 3);
                for (int k = 0; k < L; k++) p += (char) ('a' + r3() % 4);
                pieces.push_back(p);
            }
            stopseq::MatchState st; st.stops = stops;
            bool head[256] = {false};
            for (auto & p : pieces) for (size_t i = 0; i < p.size(); i++) head[(unsigned char) p[i]] = true;
            size_t emitted = 0, emitted_nc = 0;
            std::string out_clamp, out_no;
            for (auto & p : pieces) {
                stopseq::advance(st, p, [&head](unsigned char b) { return head[b]; }, true);
                if (st.hit) break;
                { size_t e = emitted; if (e > st.generated.size()) { e = st.generated.size(); clampFired++; }
                  out_clamp += st.generated.substr(e); emitted = st.generated.size(); }
                if (emitted_nc <= st.generated.size()) { out_no += st.generated.substr(emitted_nc); emitted_nc = st.generated.size(); }
            }
            if (out_clamp != out_no) mismatch++;
        }
        printf("emitted 账：钳制触发 %d 次，与理想账不一致 %d 例\n", clampFired, mismatch);
        ck("generated 回退不得越过已下发边界（钳制不触发）", clampFired == 0);
        ck("钳制路径与理想账逐字节一致", mismatch == 0);
    }

    printf("\n%s\n", bad == 0 ? "=== stop 匹配行为测 全部通过 ===" : "=== stop 匹配行为测 失败 ===");
    return bad == 0 ? 0 : 1;
}
