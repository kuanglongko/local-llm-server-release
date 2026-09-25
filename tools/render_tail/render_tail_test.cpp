// 渲染出口 / 尾窗口的**宿主侧行为测试**（模块 F 两条修复）。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么要有它（而不是只留源码守卫）
// ═══════════════════════════════════════════════════════════════════════════
// `run_render_tail_guard.sh` 钉的是"结构没改回去" —— 它证明不了**这套结构在
// 非法 UTF-8 输入下真的对**。而 F-1 的全部症状只在非法序列上出现：
//   overlong `C0 80`  -> 旧计数侧 1、旧发出侧 2
//   CESU-8 `ED A0 80` -> 旧计数侧 1、旧发出侧 3
// 合法 UTF-8 下两边恒等（真机常见模型全无症状），所以"跑一遍看它绿"什么也测不出。
//
// 做法：**从真源码逐字抽取**两边的实现，对齐跑：
//   · 计数侧 = `llama_jni.cpp` 的 `utf16_len`（写进长度段的那个数）；
//   · 发出侧 = `utf8_safe.h` 的 `new_string_utf8_safe` 所走的同一条解码路径
//     （同一份 `utf8_decode_each` + `utf8_push_code_unit`）。
// 抽取而不是维护副本：副本会随源码漂移，那时测的是一份没人看的旧代码。
// 抽取由本文件上方 `#include` 的 `render_tail_extract.inc` 完成（脚本生成）。
//
// 两侧判据各跑一遍：
//   ① F-1：20 万例合法 UTF-8 + 50 万例随机字节 + 256 个单字节 + 8 个已知对抗串，
//           `utf16_len` 必须逐例等于发出侧的 code unit 数；
//   ② F-2：尾窗口分类（Kotlin 侧 `ThinkStream.classifyTail` 的宿主复刻，
//           逐字对照源码里的实现）必须与 C++ 侧按**字节**窗口算出同一档。
//
// 运行：bash tools/run_render_tail_tests.sh
#include "render_tail_extract.inc"
#if defined(RENDER_TAIL_HAVE_OLD)
#include "old_extract.inc"
#endif

#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <vector>

static int fails = 0;
static void ck(const std::string & name, bool ok, const std::string & why = "") {
    if (ok) { printf("  ok   %s\n", name.c_str()); }
    else {
        printf("  FAIL %s%s\n", name.c_str(), why.empty() ? "" : ("  <- " + why).c_str());
        fails++;
    }
}

// ── 发出侧：真机上 new_string_utf8_safe 走的就是这两步 ────────────────────
static std::vector<jchar> issue_side(const std::string & s) {
    std::vector<jchar> out;
    out.reserve(s.size());
    utf8_decode_each(s.data(), s.size(), [&out](uint32_t cp) { utf8_push_code_unit(out, cp); });
    return out;
}

static std::string hex(const std::string & s) {
    std::string r;
    char b[4];
    for (unsigned char c : s) { snprintf(b, sizeof(b), "%02x", c); r += b; }
    return r;
}

// ── ① F-1：长度段 == 发出串长度 ─────────────────────────────────────────
static void test_suffix_len() {
    printf("① F-1：长度段与发出串同源\n");
    struct { const char * name; const char * bytes; } known[] = {
        {"overlong C0 80",   "\xC0\x80"},
        {"CESU-8 ED A0 80",  "\xED\xA0\x80"},
        {"emoji 合法 4B",     "\xF0\x9F\x98\x80"},
        {"emoji 截断 2B",     "\xF0\x9F"},
        {"孤立续字节 80",      "\x80"},
        {"UTF-8 中文 3B",     "\xE4\xB8\xAD"},
        {"纯 ASCII",          "abc"},
        {"overlong + XYZ",   "\xC0\x80XYZ"},
    };
    for (auto & k : known) {
        const std::string s(k.bytes);
        const size_t lenField = utf16_len(s);
        const size_t issued = issue_side(s).size();
        char buf[160];
        snprintf(buf, sizeof(buf), "已知对抗串 %-16s 长度段=%zu 实发=%zu", k.name, lenField, issued);
        ck(buf, lenField == issued, "bytes=" + hex(s));
    }
    // 全部 256 个单字节
    size_t singleBad = 0;
    for (int b = 0; b < 256; b++) {
        std::string s(1, (char) (unsigned char) b);
        if (utf16_len(s) != issue_side(s).size()) singleBad++;
    }
    ck("256 个单字节全部一致", singleBad == 0, std::to_string(singleBad) + " 处不一致");

    std::mt19937 rng(20260922u);
    // 合法 UTF-8 随机串（含 emoji / 汉字 / 组合符 / 边界码点）
    const std::vector<uint32_t> alph = {
        0x41, 0x7A, 0x4E2D, 0x6587, 0x1F600, 0x10FFFF, 0x20AC, 0x0301, 0x7F, 0x800, 0xFFFF, 0x0
    };
    size_t legalBad = 0;
    for (int t = 0; t < 200000; t++) {
        std::string s;
        int n = 1 + (int) (rng() % 12);
        for (int k = 0; k < n; k++) {
            uint32_t cp = alph[rng() % alph.size()];
            if (cp < 0x80) s += (char) cp;
            else if (cp < 0x800) { s += (char) (0xC0 | (cp >> 6)); s += (char) (0x80 | (cp & 0x3F)); }
            else if (cp < 0x10000) {
                s += (char) (0xE0 | (cp >> 12));
                s += (char) (0x80 | ((cp >> 6) & 0x3F));
                s += (char) (0x80 | (cp & 0x3F));
            } else {
                s += (char) (0xF0 | (cp >> 18));
                s += (char) (0x80 | ((cp >> 12) & 0x3F));
                s += (char) (0x80 | ((cp >> 6) & 0x3F));
                s += (char) (0x80 | (cp & 0x3F));
            }
        }
        if (utf16_len(s) != issue_side(s).size()) legalBad++;
    }
    ck("20 万例合法 UTF-8 全部一致", legalBad == 0, std::to_string(legalBad) + " 处不一致");

    size_t randBad = 0;
    std::string firstBad;
    for (int t = 0; t < 500000; t++) {
        std::string s;
        int n = 1 + (int) (rng() % 20);
        for (int k = 0; k < n; k++) s += (char) (unsigned char) (rng() & 0xFF);
        if (utf16_len(s) != issue_side(s).size()) {
            randBad++;
            if (firstBad.empty()) firstBad = hex(s);
        }
    }
    ck("50 万例随机字节全部一致", randBad == 0,
       std::to_string(randBad) + " 处不一致，首个 bytes=" + firstBad);
}

// ── ② F-2：C++ 侧判据与 Kotlin 侧判据同档 ───────────────────────────────
// C++ 侧**直接调真机编进去的那一份**（probe_util.h 的 `classify_think_tail`，
// 经 llama_jni.cpp 的 `classify_rendered_think_tail` 转发）——
// 不是"另写一份近似"。这也是判据本体被搬进 probe_util.h 的**全部理由**：
// 只有真实现能在宿主上跑，"两侧同档"才是被证过的，而不是被假设的。
static int cpp_classify(const std::string & prompt) {
    switch (classify_rendered_think_tail(prompt)) {
        case ThinkTailShape::kOpenOnly: return 1;
        case ThinkTailShape::kClosed:   return 2;
        default:                        return 0;
    }
}

// Kotlin 侧：`ThinkStream.classifyTail` 的等价物。Kotlin 那份是同一判据在
// JVM 侧的实现（`toByteArray(UTF_8)` + 续字节回退）。
//
// 这里**必须**真的把两侧的窗口口径都算一遍：如果直接复用上面的 `cpp_classify`，
// "两侧同档"就变成自证（同一份代码当然同档），那这条断言等于没写。
// 两份是否同档由本函数逐个用例断言；Kotlin 侧源码是否还是"按字节"由
// `run_render_tail_guard.sh` 断言（`toByteArray(Charsets.UTF_8)` + 续字节回退）。
static int kt_classify(const std::string & rp) {
    if (rp.empty()) return 0;
    const std::string & bytes = rp;              // Kotlin 的 toByteArray(UTF_8) 就是这份字节
    size_t from = bytes.size() > ktWindowBytes() ? bytes.size() - ktWindowBytes() : 0;
    while (from < bytes.size() && ((unsigned char) bytes[from] & 0xC0) == 0x80) from++;
    const std::string tail = bytes.substr(from);
    const std::string OPEN = "<think>", CLOSE = "</think>";
    const size_t at = tail.rfind(OPEN);
    if (at == std::string::npos) return 0;
    if (tail.find(CLOSE, at) != std::string::npos) return 2;
    return 1;
}

static std::u16string rep16(char16_t unit, int n) {
    std::u16string r;
    for (int i = 0; i < n; i++) r += unit;
    return r;
}

static std::string rep(const std::string & unit, int n) {
    std::string r;
    for (int i = 0; i < n; i++) r += unit;
    return r;
}

static void test_tail_window() {
    printf("② F-2：两侧尾窗口同档\n");
    auto run = [](const char * name, const std::string & p, int expect) {
        const int a = cpp_classify(p), b = kt_classify(p);
        char buf[200];
        snprintf(buf, sizeof(buf), "%-34s C++=%d Kotlin=%d", name, a, b);
        ck(buf, a == b && a == expect, a != b ? "两侧分叉" : "与期望档位不符");
    };
    // 真机后缀（纯 ASCII，两种窗口恰好覆盖同一段 —— 这正是旧版全绿的原因）
    run("全 ASCII 后缀（真机常见）", std::string(60, 'x') + "<|im_start|>assistant\n<think>\n", 1);
    run("MiniCPM5 关思考（已闭合）", std::string(60, 'x') + "<|im_start|>assistant\n<think>\n\n</think>\n\n", 2);
    run("无标签", std::string(500, 'x'), 0);
    // ── 中文：256 字节 ≈ 85 个汉字。旧版 Kotlin 用 code unit（= 256 个汉字），
    //    所以**分叉只发生在"标签被推到 256 字节窗口之外"的那一侧**。
    //    `rep("汉", N) + "<think>"` 的标签恒在末尾 → 恒在窗口内，三种 N 都测不出分叉。
    //    要构造分叉，必须让标签**落在 256..256*? 之间**：先写标签、后面再跟足够长的
    //    多字节正文，把标签推出字节窗口，但仍在 code unit 窗口内。
    run("84 汉字 + <think>（标签在窗口内）", rep("汉", 84) + "<think>", 1);
    run("200 汉字 + <think>（标签在窗口内）", rep("汉", 200) + "<think>", 1);
    // 标签在字节窗口外、code unit 窗口内 —— 旧版 Kotlin 会误判成 openOnly。
    run("50 汉字 + <think> + 100 汉字（字节窗口外）", rep("汉", 50) + "<think>" + rep("字", 100), 0);
    run("20 汉字 + <think> + 300 汉字（字节窗口外）", rep("汉", 20) + "<think>" + rep("字", 300), 0);
    // emoji：4 字节 1 字符，两侧口径差 4 倍。
    std::string emoji;
    for (int i = 0; i < 300; i++) emoji += "\xF0\x9F\x98\x80";
    run("300 emoji + <think>（标签在窗口内）", emoji + "<think>", 1);
    run("20 emoji + <think>（标签在窗口内）", std::string(20, 'x') + emoji + "<think>", 1);
    // ⚠ 这一支我第一版写错过：`N emoji + <think>` 的标签**恒在末尾**，恒在窗口内，
    // 无论 N 多大 —— 我原先按"80 emoji = 320 字节 > 256"判它"窗口外"，是错的
    // （窗口从末尾往前取，末尾的标签当然在里面）。这正是本项目反复强调的那件事：
    // 期望值也要被算过，不能凭"字节数比窗口大"就想当然。
    // 真正会让两侧分叉的是"标签**不在末尾**"，见下一条。
    run("80 emoji + <think>（标签在窗口内）", emoji.substr(0, 80 * 4) + "<think>", 1);
    run("3 emoji + <think> + 200 emoji（字节窗口外）", emoji.substr(0, 3 * 4) + "<think>" + emoji.substr(0, 200 * 4), 0);
    // 窗口正好切在多字节序列中间（续字节回退）：85 汉字 = 255 字节，标签在 255..262
    run("窗口切在汉字中间（续字节回退）", rep("汉", 85) + "<think>", 1);
    run("85 汉字 + <think> + 汉字（切点落续字节）", rep("汉", 85) + "<think>" + rep("汉", 100), 0);
    // 空串
    run("空串", std::string(), 0);
}

// ── ③ 反例对照：旧写法**必须**在这两组输入上暴露问题 ─────────────────────
// 一条只会绿的断言测不出任何东西。"修复是对的"必须与"旧写法确实会红"同时成立，
// 否则要么用例没打在这个洞上，要么这条判据本来就是恒真的。
// 旧实现由 `extract.py --old` 从 `main` 的四个文件里**逐字抽取**，不是手抄。
#if defined(RENDER_TAIL_HAVE_OLD)
// 旧发出侧的字面语义：与旧 utf8_safe.h 的 `new_string_utf8_safe` 的 push 逐字同构。
// 它的"每次只前进 1 字节、重新解码"正是与旧 utf16_len 分叉的根源。
static size_t old_issue_side_len(const std::string & str) {
    if (str.empty()) return 0;
    const unsigned char * s = (const unsigned char *) str.data();
    const size_t len = str.size();
    size_t n = 0, i = 0;
    while (i < len) {
        unsigned char c = s[i];
        if (c < 0x80) { n += 1; i++; continue; }
        uint32_t need, cp;
        if      ((c & 0xE0) == 0xC0) { need = 1; cp = c & 0x1Fu; }
        else if ((c & 0xF0) == 0xE0) { need = 2; cp = c & 0x0Fu; }
        else if ((c & 0xF8) == 0xF0) { need = 3; cp = c & 0x07u; }
        else { n += 1; i++; continue; }
        if (i + need >= len) { n += 1; i++; continue; }
        bool ok = true;
        for (uint32_t k = 1; k <= need; k++) {
            unsigned char cc = s[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        const bool overlong = (need == 1 && cp < 0x80u) || (need == 2 && cp < 0x800u) || (need == 3 && cp < 0x10000u);
        if (!ok || cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu) || overlong) { n += 1; i++; continue; }
        n += (cp < 0x10000u) ? 1 : 2;
        i += need + 1;
    }
    return n;
}

static void test_negative() {
    printf("③ 反例对照：旧写法必须暴露出问题\n");
    // F-1：旧 utf16_len vs 旧发出侧
    const std::string cases[] = {std::string("\xC0\x80"), std::string("\xED\xA0\x80"),
                                 std::string("\xC0\x80XYZ")};
    size_t diverge = 0;
    for (auto & c : cases) if (old_utf16_len(c) != old_issue_side_len(c)) diverge++;
    ck("旧 F-1：3 个对抗串里至少 1 处长度段 != 发出串（期望 > 0）", diverge > 0,
       "旧写法在 3 个对抗串上都没暴露出问题 —— 说明用例没打在这个洞上");
    // 随机 2 万例（跑满 50 万在 CI 上偏慢，2 万足以证明"会红"）
    std::mt19937 rng(999u);
    size_t randDiverge = 0;
    for (int t = 0; t < 20000; t++) {
        std::string s;
        int n = 1 + (int) (rng() % 20);
        for (int k = 0; k < n; k++) s += (char) (unsigned char) (rng() & 0xFF);
        if (old_utf16_len(s) != old_issue_side_len(s)) randDiverge++;
    }
    ck("旧 F-1：2 万例随机字节里出现不一致（期望 > 0）", randDiverge > 0,
       "旧写法在 2 万例随机字节上一次都没分叉 —— 说明用例没打在这个洞上");

    // F-2：旧 Kotlin 判据（UTF-16 窗口）vs 真 C++ 判据（字节窗口）。
    // 旧 C++ 判据（`old_classify_impl`）也一并跑：它的窗口也是字节，与真实现同档 ——
    // 这一条说明"旧版的错只在 Kotlin 那一侧"，不是"C++ 也错了"。
    struct { const char * name; std::string p; std::u16string u16; } fc[] = {
        {"50 汉字 + <think> + 100 汉字",
         rep("汉", 50) + "<think>" + rep("字", 100),
         rep16(0x6C49, 50) + u"<think>" + rep16(0x5B57, 100)},
        {"20 汉字 + <think> + 300 汉字",
         rep("汉", 20) + "<think>" + rep("字", 300),
         rep16(0x6C49, 20) + u"<think>" + rep16(0x5B57, 300)},
        {"3 emoji + <think> + 200 emoji",
         rep("\xF0\x9F\x98\x80", 3) + "<think>" + rep("\xF0\x9F\x98\x80", 200),
         rep16(0xD83D, 3) + rep16(0xDE00, 3) + u"<think>"
             + rep16(0xD83D, 200) + rep16(0xDE00, 200)},
    };
    size_t oldCppAgree = 0, tailDiverge = 0;
    for (auto & c : fc) {
        const int real = cpp_classify(c.p);         // 真实现（字节窗口）
        const OldRenderedThinkShape oc = old_classify_impl(c.p);
        const int oldCpp = oc == OldRenderedThinkShape::kOpenOnly ? 1
                         : (oc == OldRenderedThinkShape::kClosed ? 2 : 0);
        if (real == oldCpp) oldCppAgree++;
        // 旧 Kotlin 镜像吃的是 **UTF-16 code unit**（Java 的 String.length 口径），
        // 所以必须喂 u16string —— 喂 UTF-8 字节等于又换了一次口径，测出的分叉是假的。
        const int mirrorOld = old_kt_classify(c.u16);
        printf("       %-32s 真实现=%d 旧镜像=%d\n", c.name, real, mirrorOld);
        if (real != mirrorOld) tailDiverge++;
    }
    ck("旧 F-2：旧 C++ 判据与真实现同档（错只在 Kotlin 侧）", oldCppAgree == 3,
       std::to_string(3 - oldCppAgree) + " 个用例上旧 C++ 判据也分叉了");
    ck("旧 F-2：3 个非 ASCII 用例里至少 1 处两侧分叉（期望 > 0）", tailDiverge > 0,
       "旧镜像在非 ASCII 用例上一次都没分叉 —— 说明用例没打在这个洞上");
}
#endif

int main() {
    printf("=== 渲染出口 / 尾窗口 宿主测 ===\n");
    test_suffix_len();
    test_tail_window();
#if defined(RENDER_TAIL_HAVE_OLD)
    test_negative();
#else
    printf("③ 反例对照：SKIP（未提供 --old 抽取单元）\n");
#endif
    printf("\n%s（失败 %d）\n", fails == 0 ? "全部通过" : "存在失败", fails);
    return fails == 0 ? 0 : 1;
}
