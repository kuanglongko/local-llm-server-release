// rag_index_test.cpp — 知识库索引「纯逻辑」的宿主侧单测
//
// 与 app/src/main/cpp/rag_index.h **共用同一份**头文件，所以这里绿了
// 才代表线上分块/指纹/头布局/注入选择是对的。
//
// 为什么这些判据必须在 CI 里跑：索引的失效模式全是**静默**的 ——
//   · 切在 UTF-8 码点中间 -> 那一段永远编码失败，"某几段检索不到"，
//     没有任何日志指向分块；
//   · 块与块之间留空洞 -> 中间几个字节的正文永远进不了索引，
//     "找不到"与"文档里没有"表现完全一样，无法归因；
//   · 指纹漏字段 -> 换模型/换切块参数后旧索引被当新的用，检索命中旧向量，
//     排序全乱而接口 200；
//   · 头读错位 -> 把指纹当向量读，维度"对得上"，检索永远无意义；
//   · 空库当"建好了" -> 检索恒为空且无提示。
// 都不抛异常、不崩溃、不报错。
#include "rag_index.h"

#include <cstdio>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>

using namespace ragindex;

static int g_pass = 0, g_fail = 0;
static void ok(bool cond, const char * what) {
    if (cond) { g_pass++; printf("PASS  %s\n", what); }
    else      { g_fail++; printf("FAIL  %s\n", what); }
}
// 重载：本轮的分块用例名字是**拼出来的**（带 target / overlap 实值），
// 钉住"是哪一档红的" —— 一条笼统的"分块：UTF-8 对齐"分不出 target=1 还是 target=7。
static void ok(bool cond, const std::string & what) { ok(cond, what.c_str()); }
static void eq(const std::string & a, const std::string & b, const char * what) {
    if (a == b) { g_pass++; printf("PASS  %s\n", what); }
    else { g_fail++; printf("FAIL  %s\n     got=[%s]\n     want=[%s]\n", what, a.c_str(), b.c_str()); }
}
static void eqi(long a, long b, const char * what) {
    if (a == b) { g_pass++; printf("PASS  %s\n", what); }
    else { g_fail++; printf("FAIL  %s  got=%ld want=%ld\n", what, a, b); }
}

// ── UTF-8 起始字节判定 ─────────────────────────────────────────────────────
static void test_utf8_start() {
    ok(is_utf8_start((unsigned char) 'a'), "is_utf8_start('a') = true");
    ok(is_utf8_start((unsigned char) 0xE4), "is_utf8_start(0xE4 中文首字节) = true");
    ok(!is_utf8_start((unsigned char) 0x80), "is_utf8_start(0x80 continuation) = false");
    ok(!is_utf8_start((unsigned char) 0xBF), "is_utf8_start(0xBF continuation 上界) = false");
    ok(is_utf8_start((unsigned char) 0xC0), "is_utf8_start(0xC0 二字节首字节) = true");
}

// 把 i 向前对齐到码点起始处
static void test_align() {
    const std::string s = "中";   // 3 字节 E4 B8 AD
    eqi((long) align_utf8_back(s, 0), 0, "对齐：i=0 原样");
    eqi((long) align_utf8_back(s, 1), 0, "对齐：i=1（continuation）向前到 0");
    eqi((long) align_utf8_back(s, 2), 0, "对齐：i=2（continuation）向前到 0");
    eqi((long) align_utf8_back(s, 3), 3, "对齐：i=len 原样（等于末尾）");
    eqi((long) align_utf8_back(s, 99), 3, "对齐：i>len 钳到 len");
    std::string empty;
    eqi((long) align_utf8_back(empty, 0), 0, "对齐：空串返回 0（不越界）");
}

// ── 分块 ───────────────────────────────────────────────────────────────────
static void test_chunk_empty() {
    std::vector<std::string> v = chunk_text("", ChunkCfg{});
    eqi((long) v.size(), 0, "分块：空文本 -> 0 块");
    v = chunk_text("   \n\n  \t ", ChunkCfg{});
    eqi((long) v.size(), 0, "分块：全空白 -> 0 块（不进索引）");
}

static void test_chunk_single() {
    ChunkCfg cfg; cfg.targetChars = 100;
    std::vector<std::string> v = chunk_text("短文本", cfg);
    eqi((long) v.size(), 1, "分块：短文本 -> 1 块");
    eq(v[0], "短文本", "分块：短文本内容不变");
}

static void test_chunk_utf8_safe() {
    // 目标 4 字节而中文 3 字节一字：切点必然落在 continuation 上，
    // 实现必须向前对齐，绝不能切出非法 UTF-8。
    ChunkCfg cfg; cfg.targetChars = 4; cfg.overlapChars = 0;
    const std::string text = "中文中文中文中文";   // 12 字 = 36 字节
    std::vector<std::string> v = chunk_text(text, cfg);
    ok(v.size() >= 2, "分块：中文长文本被切成多块");
    bool allStart = true, allNonEmpty = true;
    std::string joined;
    for (const auto & p : v) {
        if (p.empty()) allNonEmpty = false;
        if (!is_utf8_start((unsigned char) p[0])) allStart = false;
        joined += p;
    }
    ok(allNonEmpty, "分块：每块 trim 后非空");
    ok(allStart, "分块：每块首字节都是 UTF-8 码点起始（绝不切在中间）");
    // 无缝：overlap=0 时，拼起来必须逐字节等于原文（无空洞、无重排）
    eq(joined, text, "分块：overlap=0 时拼回 == 原文（无空洞）");
}

static void test_chunk_paragraph_preferred() {
    ChunkCfg cfg; cfg.targetChars = 20; cfg.overlapChars = 0;
    const std::string text = "第一节\n\n第二节\n\n第三节";
    std::vector<std::string> v = chunk_text(text, cfg);
    ok(v.size() >= 2, "分块：有段落分隔时切成多块");
    // 第一块应尽量收在段落边界（末尾不含下一段的开头）
    ok(v[0].find("第二节") == std::string::npos, "分块：优先切在段落边界（第一块不含下一段）");
}

static void test_chunk_overlap() {
    // ⚠ 语料必须**带分隔符**：重叠只在**软切**那一轮保留，而"整段没有分隔符"
    //   的语料（原来的 `abcdefghijklmnopqrstuvwxyz` 就是）全是硬截 —— 硬截轮现在
    //   **不重叠**（回退 overlap 只会把已消化区重切一遍，见 chunk_text 的说明）。
    //   拿无分隔符语料断言"相邻块保留重叠"，等于要求实现去做那件被修掉的事。
    ChunkCfg cfg; cfg.targetChars = 16; cfg.overlapChars = 4;
    const std::string text = "abcdefghij。klmnopqrst。uvwxyz";
    std::vector<std::string> v = chunk_text(text, cfg);
    ok(v.size() >= 2, "分块：overlap 时切成多块");
    // 相邻块必须有重叠（否则跨边界句子无人能检索到）
    bool overlapped = false;
    for (size_t i = 0; i + 1 < v.size(); i++) {
        if (v[i].size() > 4 && v[i + 1].find(v[i].substr(v[i].size() - 4)) != std::string::npos)
            overlapped = true;
    }
    ok(overlapped, "分块：相邻块保留重叠（跨边界语义不丢）");
}

// overlap >= target 会死循环；实现必须自己收敛
static void test_chunk_overlap_ge_target() {
    ChunkCfg cfg; cfg.targetChars = 8; cfg.overlapChars = 8;
    const std::string text = std::string(200, 'x');
    std::vector<std::string> v = chunk_text(text, cfg);
    ok(!v.empty(), "分块：overlap>=target 不返回空（不死循环）");
    ok(v.size() <= 300, "分块：overlap>=target 块数有界（确实在前进）");
}

// target<=0 时退化成整篇一块，不是死循环
static void test_chunk_target_zero() {
    ChunkCfg cfg; cfg.targetChars = 0; cfg.overlapChars = 0;
    const std::string text = "hello world";
    std::vector<std::string> v = chunk_text(text, cfg);
    eqi((long) v.size(), 1, "分块：target<=0 退化成整篇一块");
    eq(v[0], "hello world", "分块：target<=0 内容完整");
}

// maxChunks 上限：超大文件不能把内存吃光
static void test_chunk_max_chunks() {
    ChunkCfg cfg; cfg.targetChars = 4; cfg.overlapChars = 0; cfg.maxChunks = 5;
    const std::string text = std::string(1000, 'a');
    std::vector<std::string> v = chunk_text(text, cfg);
    eqi((long) v.size(), 5, "分块：块数被 maxChunks 截住（防内存爆）");
}

// ── 撞上限 = **内容真的没进索引**，这件事必须能被判出来（本轮新增）────────
//
// 为什么单列一组：`hardCuts`（块边界糙）与 `truncated`（块没了）后果差一个量级 ——
// 前者是召回率低，后者是**丢内容**。而"丢内容"与"文档里本来就没有"在检索上
// **逐字相同**（都是"搜不到"），用户只能靠反复换关键词去猜。
// 它此前完全没有痕迹：`chunk_text` 撞上限就 return，调用方只报块数。
//
// ⚠ 最容易写歪的那一处：判据必须是"**还没切完**"，不是"块数正好等于上限" ——
// "正好切满且到文末"是**完整**的，报成截断会让用户白建一遍，
// 而且会让这条日志在正常库上天天出现（用户从此无视它）。
static void test_chunk_truncated_flag() {
    // ① 撞上限：1000/4 = 250 块，上限 5 -> 截断
    {
        ChunkCfg cfg; cfg.targetChars = 4; cfg.overlapChars = 0; cfg.maxChunks = 5;
        bool tr = false;
        std::vector<std::string> v = chunk_text(std::string(1000, 'a'), cfg, nullptr, &tr);
        eqi((long) v.size(), 5, "截断：块数仍被上限截住");
        ok(tr, "截断：撞上限 -> truncated = true（内容少了，必须报出来）");
    }
    // ② 没撞上限（短文本）
    {
        ChunkCfg cfg; cfg.targetChars = 4; cfg.overlapChars = 0; cfg.maxChunks = 5;
        bool tr = true;
        chunk_text(std::string(10, 'a'), cfg, nullptr, &tr);
        ok(!tr, "截断：短文本 -> truncated = false（不报假警）");
    }
    // ③ **正好切满且到文末** = 完整，不是截断（判据是"还没切完"，不是"块数 == 上限"）
    {
        ChunkCfg cfg; cfg.targetChars = 4; cfg.overlapChars = 0; cfg.maxChunks = 3;
        bool tr = true;
        std::vector<std::string> v = chunk_text(std::string(12, 'a'), cfg, nullptr, &tr);
        eqi((long) v.size(), 3, "截断：12/4 = 3 块，恰好等于上限");
        ok(!tr, "截断：正好切满且到文末 -> truncated = false（那一份是**完整**的）");
    }
    // ④ 空串
    {
        ChunkCfg cfg;
        bool tr = true;
        chunk_text("", cfg, nullptr, &tr);
        ok(!tr, "截断：空串 -> truncated = false（不崩、不误报）");
    }
    // ⑤ targetChars <= 0（整篇一块那条退化路径）不算截断
    {
        ChunkCfg cfg; cfg.targetChars = 0; cfg.maxChunks = 5;
        bool tr = true;
        chunk_text(std::string(500, 'a'), cfg, nullptr, &tr);
        ok(!tr, "截断：target<=0 整篇一块 -> 不报截断（那条路不发散）");
    }
    // ⑥ 截断与硬截是两件事：默认上限下 1000 个 'a' 是 1 块（不截断但可能硬截）
    {
        ChunkCfg cfg; cfg.targetChars = 4; cfg.overlapChars = 0; cfg.maxChunks = 1000;
        int hc = 0; bool tr = true;
        chunk_text(std::string(100, 'A'), cfg, &hc, &tr);
        ok(hc > 0 && !tr, "截断：硬截与截断可分辨（硬截 4 次、未截断）");
    }
    // ⑦ nullptr 必须被接受（可选出参，同 hardCuts）
    {
        ChunkCfg cfg; cfg.targetChars = 4; cfg.maxChunks = 5;
        std::vector<std::string> v = chunk_text(std::string(100, 'a'), cfg, nullptr, nullptr);
        ok(!v.empty(), "截断：truncated 传 nullptr 不崩（可选出参）");
    }
}

// 重叠时块必须严格前进（否则死循环）
static void test_chunk_progress() {
    ChunkCfg cfg; cfg.targetChars = 6; cfg.overlapChars = 3;
    const std::string text = std::string(60, 'z');
    std::vector<std::string> v = chunk_text(text, cfg);
    ok(v.size() > 1 && v.size() < 100, "分块：重叠模式下块数有界（每轮严格前进）");
}

// ── 分块：本轮新增的「2 级 + 硬截」规则 ─────────────────────────────────────
//
// 这一组钉的是**规则本身**，而不只是"能切出块"。每一条都对应一个真实故障：
//   · 用户原始需求（五级阶梯）里的两条硬伤，各有一条正例与一条反例；
//   · "硬截"必须能被数出来（它要进日志，见 LlmEngine.buildIndex）；
//   · 规则表与 Kotlin 侧必须逐条一致（守卫 `chunk_seps_consistent` 兜底）。

// 硬截计数：窗口内一个句读都没有时必须**如实计数**。
// 为什么数它：硬截块的语义是被凭空截断的，检索天生不准，而它此前没有任何痕迹。
static void test_chunk_hard_cut_counted() {
    ChunkCfg cfg; cfg.targetChars = 20; cfg.overlapChars = 0;
    int hc = -1;
    chunk_text(std::string(100, 'A'), cfg, &hc);
    eqi(hc, 4, "分块：无标点 -> 每轮都是硬截（100/20 = 5 轮，末轮到文末不算 = 4）");

    hc = -1;
    chunk_text("甲。乙。丙。丁。", cfg, &hc);
    eqi(hc, 0, "分块：句读充足 -> 0 次硬截（不误报）");

    // nullptr 必须被接受（调用方不关心计数时不该崩）
    std::vector<std::string> v = chunk_text(std::string(50, 'B'), cfg, nullptr);
    ok(!v.empty(), "分块：hardCuts 传 nullptr 不崩（可选出参）");
}

// ⚠ 用户阶梯里的硬伤①：句子级分隔符会切在 UTF-8 码点中间。
// 本实现要求"候选切点对齐后 <= pos 就当没找到"，所以**任何** target 都不许切出
// 非法 UTF-8。这里把 target 压到 1 字节（小于一个中文码点）跑一遍 —— 那是
// 唯一会走到"向前对齐退到 pos、只能向后推进一个码点"这条路的输入。
static void test_chunk_never_illegal_utf8() {
    const std::string text = "中文。中文！中文？中文；中文，中文、中文";
    for (int tg = 1; tg <= 12; tg++) {
        ChunkCfg cfg; cfg.targetChars = tg; cfg.overlapChars = 0;
        int hc = 0;
        std::vector<std::string> v = chunk_text(text, cfg, &hc);
        bool allStart = true, allNonEmpty = true;
        for (const auto & p : v) {
            if (p.empty()) allNonEmpty = false;
            else if (!is_utf8_start((unsigned char) p[0])) allStart = false;
        }
        ok(allNonEmpty, "分块：target=" + std::to_string(tg) + " 时每块非空");
        ok(allStart, "分块：target=" + std::to_string(tg) + " 时每块首字节是码点起始（绝不切在中间）");
    }
    // 4 字节 emoji 同样（"一个字比 target 还长"的另一形态）
    for (int tg = 1; tg <= 6; tg++) {
        ChunkCfg cfg; cfg.targetChars = tg; cfg.overlapChars = 0;
        std::vector<std::string> v = chunk_text("😀😀😀a😀", cfg, nullptr);
        bool allStart = true;
        for (const auto & p : v) if (p.empty() || !is_utf8_start((unsigned char) p[0])) allStart = false;
        ok(allStart, "分块：emoji + target=" + std::to_string(tg) + " 时也不切在码点中间");
    }
}

// ⚠ 用户阶梯里的硬伤②："窗口内找不到就往前找最近的一次"会**整块丢字**。
// 判据不能锚"块内容拼起来等于原文"（块是 trim 过的，`\n` 本来就会被裁掉 ——
// 那是 trim 的定义，不是丢字）。真正的判据是**切点严格覆盖**：
// `s_0 == 0`、`e_last == len`、相邻 `s_{i+1} == e_i`。下面用同一套控制流
// 把切点复算出来断言（复算与实现是否脱钩，由 `chunk_no_gap` 那条守卫兜底）。
static std::vector<std::pair<size_t, size_t> > chunk_spans(const std::string & text,
                                                          const ChunkCfg & cfg) {
    std::vector<std::pair<size_t, size_t> > sp;
    const size_t len = text.size();
    if (len == 0) return sp;
    int target = cfg.targetChars > 0 ? cfg.targetChars : (int) len;
    int overlap = cfg.overlapChars > 0 ? cfg.overlapChars : 0;
    if (overlap >= target) overlap = target > 1 ? target / 2 : 0;
    const int maxChunks = cfg.maxChunks > 0 ? cfg.maxChunks : 1;
    size_t pos = 0;
    while (pos < len && (int) sp.size() < maxChunks) {
        size_t end = pos + (size_t) target;
        if (end > len) end = len;
        bool cutByRule = false;
        if (end < len) {
            size_t cut = 0;
            if (pick_chunk_cut(text, pos, end, cut)) {
                end = cut;
                cutByRule = true;
            } else {
                size_t hard = align_utf8_back(text, end);
                if (hard <= pos) {
                    hard = next_utf8_start(text, pos + 1);
                    if (hard <= pos) hard = len;
                }
                end = hard > pos ? hard : len;
            }
        }
        sp.push_back(std::make_pair(pos, end));
        if (end >= len) break;
        // 与 chunk_text 同一条控制流：**硬截那一轮不重叠**（见那边的说明）。
        size_t next;
        if (cutByRule) {
            next = end > (size_t) overlap ? end - (size_t) overlap : 0;
            next = align_utf8_back(text, next);
        } else {
            next = end;
        }
        if (next <= pos) next = end;
        if (next <= pos) next = pos + 1;
        pos = next;
    }
    return sp;
}

// 硬截轮**不重叠**：回退 overlap 得到的下一块，其窗口仍在上一轮已消化过的区间里，
// 那一轮同样找不到分隔符、同样硬截 —— 于是那段正文被切两遍、硬截计数被灌水。
// 判据：整段无分隔符的语料，相邻块**不出现**"重叠 ≥ overlap 的公共片段"。
static void test_chunk_hard_cut_no_overlap() {
    // ① 最小例（10/4）：旧实现 4 块 硬截 3，相邻块公共片段 6 > overlap=4。
    {
        ChunkCfg cfg; cfg.targetChars = 10; cfg.overlapChars = 4;
        int hc = 0;
        std::vector<std::string> v = chunk_text(std::string(9, 'a') + " " + std::string(4, 'b') +
                                                " " + std::string(9, 'c') + " ", cfg, &hc);
        // ① 用"每块首字节在原文里的下标"判"有没有被切两遍"：块内容被 trim 过，
        //    拼内容不可靠；这里改用**覆盖区间**（chunk_spans）。
        std::vector<std::pair<size_t, size_t> > sp = chunk_spans(
            std::string(9, 'a') + " " + std::string(4, 'b') + " " + std::string(9, 'c') + " ", cfg);
        bool overlaps = false;
        for (size_t i = 0; i + 1 < sp.size(); i++) {
            // 后一块起点 < 上一块终点 = 有重叠
            if (sp[i + 1].first < sp[i].second) overlaps = true;
        }
        ok(!overlaps, "分块：窗口内无分隔符（全硬截）时相邻块不重叠（不再把已消化区重切）");
        ok(hc <= (int) sp.size(), "分块：硬截次数不超过块数（不再灌水）");
    }
    // ② 纯 ASCII 长文（默认 480/64 的量级缩小版）：硬截轮之间不许重叠
    {
        ChunkCfg cfg; cfg.targetChars = 100; cfg.overlapChars = 10;
        const std::string text = std::string(1000, 'A');
        std::vector<std::pair<size_t, size_t> > sp = chunk_spans(text, cfg);
        bool overlaps = false;
        for (size_t i = 0; i + 1 < sp.size(); i++)
            if (sp[i + 1].first < sp[i].second) overlaps = true;
        ok(!overlaps, "分块：纯 A 长文（无任何分隔符）相邻块不重叠");
        ok(sp.back().second == text.size(), "分块：纯 A 长文仍覆盖到文末（不丢尾）");
    }
    // ③ 守正例：**软切**那一轮必须**保留**重叠（跨边界语义不丢，别再改回去）
    {
        ChunkCfg cfg; cfg.targetChars = 16; cfg.overlapChars = 4;
        const std::string text = "abcdefghij。klmnopqrst。uvwxyz";
        std::vector<std::pair<size_t, size_t> > sp = chunk_spans(text, cfg);
        bool overlaps = false;
        for (size_t i = 0; i + 1 < sp.size(); i++)
            if (sp[i + 1].first < sp[i].second) overlaps = true;
        ok(overlaps, "分块：软切（有分隔符）时相邻块**保留**重叠（守正例）");
    }
}

static void test_chunk_no_gap() {
    // 旧实现会在这里丢一个字节（`\n` 所在的那一轮 `cut <= pos` -> 前进 1 字节）
    struct { const char * name; std::string text; int target; } cs[] = {
        { "9A+\\n+26B",     std::string(9, 'A') + "\n" + std::string(26, 'B'), 10 },
        { "9A+\\n+26B t20", std::string(9, 'A') + "\n" + std::string(26, 'B'), 20 },
        { "段落+长无标点",    "第一段\n\n" + std::string(100, 'C'),              20 },
        { "纯中文无换行",     [] { std::string s; for (int i = 0; i < 300; i++) s += "天地玄黄宇宙洪荒"; return s; }(), 480 },
        { "句读密集",        [] { std::string s; for (int i = 0; i < 100; i++) s += "这是一句话。"; return s; }(), 64 },
    };
    for (const auto & c : cs) {
        for (int ov : { 0, 3, 64 }) {
            ChunkCfg cfg; cfg.targetChars = c.target; cfg.overlapChars = ov;
            std::vector<std::pair<size_t, size_t> > sp = chunk_spans(c.text, cfg);
            if (sp.empty()) { ok(false, std::string("分块：") + c.name + " 不该切出 0 块"); continue; }
            bool okAll = true;
            if (sp[0].first != 0) okAll = false;
            if (sp.back().second != c.text.size()) okAll = false;
            for (size_t i = 0; i < sp.size(); i++) {
                if (sp[i].second <= sp[i].first) okAll = false;
                if (!is_utf8_start((unsigned char) c.text[sp[i].first])) okAll = false;
                if (i + 1 < sp.size()) {
                    // overlap == 0 时必须严丝合缝；有 overlap 时后一块只许**提前**开始
                    if (ov == 0) { if (sp[i + 1].first != sp[i].second) okAll = false; }
                    else if (sp[i + 1].first > sp[i].second || sp[i + 1].first < sp[i].first) okAll = false;
                    if (sp[i + 1].first < sp[i].first) okAll = false;   // 绝不倒退
                }
            }
            ok(okAll, std::string("分块：") + c.name + "（overlap=" + std::to_string(ov) +
                      "）切点严格覆盖（无空洞、无倒退、起点码点对齐）");
        }
    }
}

// 降级次序：段落优先于句读，句读优先于硬截。
static void test_chunk_degrade_order() {
    // 有段落 -> 切在段落
    {
        ChunkCfg cfg; cfg.targetChars = 20; cfg.overlapChars = 0;
        int hc = 0;
        std::vector<std::string> v = chunk_text("第一节\n\n第二节\n\n第三节", cfg, &hc);
        eqi(hc, 0, "降级：有段落分隔 -> 不硬截");
        ok(!v.empty() && v[0].find("第二节") == std::string::npos,
           "降级：第一级收在段落边界（不含下一段）");
    }
    // 没有段落、只有句读 -> 切在句读
    {
        ChunkCfg cfg; cfg.targetChars = 24; cfg.overlapChars = 0;
        int hc = 0;
        std::vector<std::string> v = chunk_text("甲甲甲甲甲。乙乙乙乙乙。丙丙丙丙丙。丁丁丁丁丁", cfg, &hc);
        eqi(hc, 0, "降级：有句读（无段落）-> 不硬截");
        bool endsAtPunct = false;
        for (const auto & p : v) if (!p.empty() && p.compare(p.size() - 3, 3, "。") == 0) endsAtPunct = true;
        ok(endsAtPunct, "降级：第二级收在句读之后（末尾是「。」）");
    }
    // 句读都在窗口**之外** -> 硬截（**绝不**去窗口外找）
    {
        ChunkCfg cfg; cfg.targetChars = 12; cfg.overlapChars = 0;
        int hc = 0;
        std::vector<std::string> v = chunk_text(std::string(100, 'A') + "。", cfg, &hc);
        ok(hc > 0, "降级：句读在窗口外 -> 走硬截（不往窗口外找）");
    }
    // `、` 与 `；` 属句读级：应当被采用（它们不是"凑数的标点"）
    {
        ChunkCfg cfg; cfg.targetChars = 24; cfg.overlapChars = 0;
        int hc = 0;
        chunk_text("甲甲甲甲甲、乙乙乙乙乙、丙丙丙丙丙、丁丁丁丁丁", cfg, &hc);
        eqi(hc, 0, "降级：顿号「、」被当作句读（不硬截）");
        hc = 0;
        chunk_text("甲甲甲甲甲；乙乙乙乙乙；丙丙丙丙丙；丁丁丁丁丁", cfg, &hc);
        eqi(hc, 0, "降级：全角分号「；」被当作句读（不硬截）");
    }
    // ⚠ 半角空格与 `-` **不在表内**：它们语义增益极低，却是"整块丢字"的主要触发源。
    {
        ChunkCfg cfg; cfg.targetChars = 24; cfg.overlapChars = 0;
        int hc = 0;
        chunk_text(std::string(60, 'A'), cfg, &hc);
        ok(hc > 0, "降级：纯英文无标点仍走硬截（空格/`-` 不进分隔符表）");
    }
}

// 分隔符表：两条判据 —— 表不能被改短，且 C++ / Kotlin 必须逐条相同。
static void test_chunk_seps_table() {
    // ⚠ 那一侧的**同数**判据由 `tools/rag_index/check_chunk_rules.py` 逐条比对
    //   （本文件只保证 C++ 这一份没被改短）。表是**共享**的：还有一个消费方
    //   `RagArchive.splitLongLine`（导入超长正文时切成多条条目）——
    //   "同一条正文按行导入与按库建索引切得不一样"就是表分家时的症状。
    eqi((long) chunk_sep_count(), 14, "分隔符表：恰好 14 条（与 Kotlin 侧 SOFT_CUT_SEPS 同数）");
    // 段落级恰好 1 条（`\n\n`）；其余都是句读级
    int para = 0;
    for (size_t i = 0; i < chunk_sep_count(); i++) if (kChunkSeps[i].preferParagraph) para++;
    eqi(para, 1, "分隔符表：段落级恰好 1 条（\\n\\n）");
    // 用户需求里点到名的这几条必须在（它们是把"五级"收成"两级"时**保留**下来的）
    const char * must[] = { "\n\n", "\n", "。", "！", "？", "；", "，", "、" };
    for (const char * m : must) {
        bool found = false;
        for (size_t i = 0; i < chunk_sep_count(); i++) if (std::string(kChunkSeps[i].sep) == m) found = true;
        ok(found, std::string("分隔符表：含 ") + m);
    }
    // ⚠ 空格与 `-` / `·` **必须不在表内**（它们是硬伤②的触发源）
    const char * mustNot[] = { " ", "-", "·" };
    for (const char * m : mustNot) {
        bool found = false;
        for (size_t i = 0; i < chunk_sep_count(); i++) if (std::string(kChunkSeps[i].sep) == m) found = true;
        ok(!found, std::string("分隔符表：不含「") + m + "」（语义增益低、却是丢字的主要触发源）");
    }
}

// 切块**规则版本**必须进指纹：只改规则、不改块大小时，旧索引必须被作废。
// 否则"用旧边界算的向量命中、注入新边界切出的正文" —— 静默的。
static void test_chunk_rule_version_in_fingerprint() {
    IndexFingerprint a;
    a.modelPath = "/m/bge.gguf"; a.modelBytes = 100; a.modelMtime = 200;
    a.dim = 384; a.targetChars = 480; a.overlapChars = 64;
    a.chunkRuleVersion = 2; a.version = 3;
    IndexFingerprint b = a;
    b.chunkRuleVersion = 3;
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：切块规则版本变 -> 失效（块大小一个都没动也一样）");
    // 它必须与 t/o 分开一格（同一格会被 t/o 覆盖掉，等于没加）
    std::string fp = fingerprint_of(a);
    ok(fp.find("k2") != std::string::npos, "指纹：规则版本以独立前缀 `k` 落盘");
    ok(fp.find("k2") != fp.find("t480"), "指纹：`k` 与 `t` 是两格（不共用前缀）");
}

// ── 指纹 ───────────────────────────────────────────────────────────────────
static void test_fingerprint() {
    IndexFingerprint a;
    a.modelPath = "/m/bge.gguf"; a.modelBytes = 100; a.modelMtime = 200;
    a.dim = 384; a.targetChars = 480; a.overlapChars = 64; a.version = 1;
    IndexFingerprint b = a;
    ok(fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：同输入 -> 同指纹（确定性）");

    b.modelMtime = 201;
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：mtime 变 -> 失效（就地替换）");
    b = a; b.modelBytes = 101;
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：size 变 -> 失效（重新拷贝）");
    b = a; b.dim = 768;
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：维度变 -> 失效（换 embed 模型）");
    b = a; b.targetChars = 240;
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：切块参数变 -> 失效（块边界全变）");
    b = a; b.overlapChars = 0;
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：重叠参数变 -> 失效");
    b = a; b.version = 2;
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：格式版本变 -> 失效");
    b = a; b.modelPath = "/m/bge2.gguf";
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：路径变 -> 失效（不能确认是同一份权重）");
    // 正文 hash 进指纹：这是"文档与索引各自合法、配在一起却错"的唯一判据。
    // 少算它的后果 = 用户改了正文而检索仍用旧向量命中、注入新正文，
    // 排序看着像随机，接口 200、日志干净。
    b = a; b.textsHash = texts_hash_of("新正文");
    ok(!fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：正文 hash 变 -> 失效（改了正文没重建）");
    b = a;
    ok(fingerprint_matches(fingerprint_of(a), fingerprint_of(b)),
       "指纹：正文 hash 不变 -> 仍有效（对照，防恒失效 -> 每次都在重建）");
    // 取回：指纹里的 x 字段就是正文 hash（Kotlin 侧据此把"指纹不匹配"
    // 拆成"模型/参数变了"与"正文变了"两种诊断）
    //
    // ⚠ 上面那些用例的 `textsHash` 是**空串**，而 `texts_hash_in` 对空值
    //   **明确返回 false**（"空值不等于没改过" —— 与 Kotlin 侧 textsHashInFp
    //   同一条口径）。所以取回这一条必须用一份**真 hash** 的指纹。
    //   首版拿 a（空 hash）去判"能取回"，于是它恒假 —— 而这不是实现错了，
    //   是判据用错了样本。
    IndexFingerprint withHash = a;
    withHash.textsHash = texts_hash_of("正文一");
    std::string got;
    ok(texts_hash_in(fingerprint_of(withHash), got), "指纹：能取回 x 字段");
    eq(got, withHash.textsHash, "指纹：取回的 x 字段 == 传入的正文 hash");
    ok(!texts_hash_in(fingerprint_of(a), got), "指纹：x 为空值 -> 取不到（空值 != 没改过）");
    ok(!texts_hash_in("v1" "\x1f" "p/m.gguf" "\x1f", got),
       "指纹：没有 x 字段时取不到（旧格式索引 -> 判为不一致）");

    // 拼接歧义：路径里塞控制字符之外的内容，不能与"字段错位"等价
    IndexFingerprint c; c.modelPath = "x1"; c.modelBytes = 2;
    IndexFingerprint d; d.modelPath = "x"; d.modelBytes = 12;
    ok(!fingerprint_matches(fingerprint_of(c), fingerprint_of(d)),
       "指纹：字段边界明确（\x1f 分隔，无拼接歧义）");

    // ── 正文**行数**进指纹（阶段 3 追加一条 / 删除单条）──────────────────
    // 为什么行数也要算进去：追加一条时"索引里有多少行向量"与"正文里有多少行"
    // 可能**故意不相等**（新加的那条正文落了盘、向量还没算出来）。
    // 单看正文 hash 看不出来（hash 只证明"是哪一份正文"）——
    // 而"少几行"在界面上就是"我加的没生效"，与"没命中"长得一模一样。
    IndexFingerprint e; e.modelPath = "/m/bge.gguf"; e.dim = 8; e.rows = 3;
    IndexFingerprint f = e;
    ok(fingerprint_matches(fingerprint_of(e), fingerprint_of(f)),
       "指纹：行数相同 -> 同指纹（确定性，防「多算一项就恒失效」）");
    f.rows = 4;
    ok(!fingerprint_matches(fingerprint_of(e), fingerprint_of(f)),
       "指纹：行数变 -> 失效（索引落后于正文，必须重建）");
    // 默认 -1（老格式 / 只读路径不传）：与"真实行数"永不相等，于是
    // "不知道行数"这件事不会被当成"行数对得上"。
    IndexFingerprint g; g.modelPath = "/m/bge.gguf"; g.dim = 8;   // rows 默认 -1
    ok(!fingerprint_matches(fingerprint_of(e), fingerprint_of(g)),
       "指纹：不知道行数（-1）不与任何真实行数相等");

    // ── 嵌入身份进指纹（用户拍板）───────────────────────────────────────
    // 池化方式（CLS / LAST / MEAN）**直接决定那个向量是什么**：同一份文本、
    // 同一个模型、同一个维度，按 CLS 取与按 last 取得到的向量完全不同。
    // 指纹里没有它时，"升级了取向量的方式"这件事在旧索引上完全不可见 ——
    // 用旧语义的向量命中、注入新语义的正文，分数像随机、接口 200、日志干净。
    IndexFingerprint h; h.modelPath = "/m/bge.gguf"; h.dim = 8;
    h.embedBytes = "100"; h.embedMtime = "200"; h.pooling = "2"; h.embedOutDim = "8";
    IndexFingerprint h2 = h;
    ok(fingerprint_matches(fingerprint_of(h), fingerprint_of(h2)),
       "指纹：嵌入身份相同 -> 同指纹（确定性）");
    IndexFingerprint h3 = h; h3.pooling = "3";
    ok(!fingerprint_matches(fingerprint_of(h), fingerprint_of(h3)),
       "指纹：池化方式变 -> 失效（同一维度下向量完全不是一回事）");
    IndexFingerprint h4 = h; h4.embedOutDim = "16";
    ok(!fingerprint_matches(fingerprint_of(h), fingerprint_of(h4)),
       "指纹：模型声明的 n_embd_out 变 -> 失效（库按它分配 buffer）");
    IndexFingerprint h5 = h; h5.embedMtime = "201";
    ok(!fingerprint_matches(fingerprint_of(h), fingerprint_of(h5)),
       "指纹：嵌入模型 mtime 变 -> 失效（换成了同尺寸的另一份）");
    // 空串 = 不知道：**空串不等于任何已知值** —— 否则"不知道"会被当成"相等"，
    // 于是一个语义已变的索引被继续用（那正是这一组字段要挡住的东西）。
    IndexFingerprint u; u.modelPath = "/m/bge.gguf"; u.dim = 8;
    IndexFingerprint k; k.modelPath = "/m/bge.gguf"; k.dim = 8; k.pooling = "2";
    ok(!fingerprint_matches(fingerprint_of(u), fingerprint_of(k)),
       "指纹：一边知道池化、一边不知道 -> 不相等（宁可重建，不要错用）");
    IndexFingerprint u2; u2.modelPath = "/m/bge.gguf"; u2.dim = 8;
    ok(fingerprint_matches(fingerprint_of(u), fingerprint_of(u2)),
       "指纹：两边都不知道嵌入身份 -> 相等（不把「不知道」当差异，否则永远要重建）");

    // ── 合批每批条数进指纹（ISSUE #269 第 2 步：合批可见可调）────
    // 为什么必须进：合批是"一次前向编 n 条"，而不同批大小下每条的位置预算可以不同——
    // "两条路径算出同一个向量"是一个**待验证的断言**（第 3 步才去逐位验），
    // 不是已知事实。不进指纹 = 用户把批大小从 1 改成 8、旧索引仍被当成
    // "仍然可用"—— 而新旧向量一旦不逐位相同，检索就是"用一种语义命中、注入另一种语义的正文"。
    IndexFingerprint g1; g1.modelPath = "/m/bge.gguf"; g1.dim = 8; g1.embedBatch = 8;
    IndexFingerprint g2 = g1;
    ok(fingerprint_matches(fingerprint_of(g1), fingerprint_of(g2)),
       "指纹：批大小相同 -> 同指纹（确定性）");
    g2.embedBatch = 1;
    ok(!fingerprint_matches(fingerprint_of(g1), fingerprint_of(g2)),
       "指纹：批大小变 -> 失效（关合批后必须重建）");
    // 必填：旧格式指纹（没有 `g` 这一格）必须被判成"重建"，
    // 而**不是**"看不到就当默认相等 → 放行"。
    std::string fpNoG = fingerprint_of(g1);
    const std::string needle = std::string("g") + std::to_string(g1.embedBatch) + "\x1f";
    ok(fpNoG.find(needle) != std::string::npos, "指纹：批大小以独立前缀 `g` 落盘");
    // `g` 必须与 `d`（维度）是**两格**：共用前缀时 `fpFields()` 会把后一个静默丢掉
    //（= 批大小不参与比对）—— 而字符串本身照样合法。判据是"两个前缀各自
    // 出现的位置不同"，比"字符串里有没有 g"这种存在性断言硬。
    ok(fpNoG.find("g8\x1f") != fpNoG.find("d8\x1f"),
       "指纹：`g` 与 `d`（维度）是两格（不共用前缀）");
}

// ── 正文 hash（与 Kotlin 侧共用同一份向量表）─────────────────────────────
//
// 为什么要有"共用向量表"：FNV-1a 在两边各写一遍，**各自的单测都能自洽**
// （同一个实现算两遍当然一样）。只有拿同一份 (输入 -> 期望值) 喂两边，
// 才能发现"其中一边写错了"。表由一份独立实现（python）生成。
//
// ⚠ 表里第一行是空输入：正文文件不存在时我们按"0 字节"算 hash，
//   所以这条不是边界怪例，是**真实路径**。
static void test_texts_hash(const char * vectorsPath) {
    // 先钉住算法本身的两个不变量（不依赖文件是否可读）
    ok(texts_hash_of("") == std::string("cbf29ce484222325"),
       "正文hash：空输入 = FNV 偏移基数（0 字节也走这条真实路径）");
    eqi((long) texts_hash_of("abc").size(), 16, "正文hash：定长 16 位十六进制");
    ok(texts_hash_of("abc") != texts_hash_of("abd"),
       "正文hash：改一个字节即变（否则改正文不作废索引）");

    FILE * f = std::fopen(vectorsPath, "rb");
    if (!f) {
        g_fail++;
        printf("FAIL  正文hash：读不到共用向量表 %s\n", vectorsPath);
        return;
    }
    char line[8192];
    int n = 0, bad = 0;
    while (std::fgets(line, sizeof(line), f)) {
        std::string l(line);
        while (!l.empty() && (l.back() == '\n' || l.back() == '\r')) l.pop_back();
        if (l.empty() || l[0] == '#') continue;
        const size_t t1 = l.find('\t');
        if (t1 == std::string::npos) continue;
        const size_t t2 = l.find('\t', t1 + 1);
        if (t2 == std::string::npos) continue;
        const std::string want = l.substr(0, t1);
        const std::string hex = l.substr(t1 + 1, t2 - t1 - 1);
        const std::string what = l.substr(t2 + 1);
        std::string raw;
        for (size_t i = 0; i + 1 < hex.size(); i += 2) {
            auto nib = [](char c) -> int {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                return 0;
            };
            raw.push_back((char) ((nib(hex[i]) << 4) | nib(hex[i + 1])));
        }
        n++;
        if (texts_hash_of(raw) != want) {
            bad++;
            printf("FAIL  正文hash 与共用向量表不符：%s\n     got=[%s] want=[%s]\n",
                   what.c_str(), texts_hash_of(raw).c_str(), want.c_str());
        }
    }
    std::fclose(f);
    ok(n >= 8, "正文hash：共用向量表读到 8 条以上（读少了说明表被改坏）");
    ok(bad == 0, "正文hash：全部与共用向量表一致（与 Kotlin 侧同一份契约）");
}

// ── 头布局 ─────────────────────────────────────────────────────────────────
static void test_header_roundtrip() {
    Header h; h.fingerprint = std::string("abc") + '\x1f' + "def"; h.dim = 384; h.count = 7;
    const std::string buf = encode_header(h);
    Header g; size_t hl = 0;
    ok(decode_header(buf, g, hl), "头：反序列化成功");
    eq(g.fingerprint, h.fingerprint, "头：指纹逐字节还原（含 \\x1f）");
    eqi(g.dim, h.dim, "头：dim 还原");
    eqi(g.count, h.count, "头：count 还原");
    eqi((long) hl, (long) buf.size(), "头：headerLen == 编码长度");
}

static void test_header_reject() {
    Header g; size_t hl = 0;
    ok(!decode_header("", g, hl), "头：空串被拒");
    ok(!decode_header("RAG", g, hl), "头：长度不足被拒");
    ok(!decode_header("XXXX" + std::string(12, '\0'), g, hl), "头：magic 不符被拒（不猜）");

    Header h; h.fingerprint = "fp"; h.dim = 4; h.count = 1;
    std::string buf = encode_header(h);
    ok(!decode_header(buf.substr(0, buf.size() - 1), g, hl),
       "头：指纹区被截断 -> 整体拒绝（不当半截能用）");

    Header bad; bad.fingerprint = "fp"; bad.dim = 0; bad.count = 1;
    ok(!decode_header(encode_header(bad), g, hl), "头：dim=0 荒谬值被拒");

    Header huge; huge.fingerprint = "fp"; huge.dim = 999999; huge.count = 1;
    ok(!decode_header(encode_header(huge), g, hl), "头：dim 超上限被拒");
}

static void test_vectors_bytes() {
    eqi((long) vectors_bytes(10, 384), (long) (10 * 384 * 4), "向量区字节数 = count*dim*4");
    eqi((long) vectors_bytes(0, 384), 0, "向量区：count=0 -> 0 字节（空库合法）");
    eqi((long) vectors_bytes(-1, 384), 0, "向量区：负 count -> 0（不溢出）");
    eqi((long) vectors_bytes(10, 0), 0, "向量区：dim=0 -> 0");
}

// ── 检索选择 ───────────────────────────────────────────────────────────────
static void test_select() {
    std::vector<Pick> s = {{0, 0.9f}, {1, 0.5f}, {2, 0.1f}, {3, 0.85f}};
    std::vector<Pick> r = select_injections(s, 2, 0.0f);
    eqi((long) r.size(), 2, "选择：maxItems 生效");
    eqi(r[0].index, 0, "选择：保序（不重排）");
    eqi(r[1].index, 1, "选择：取前 N 条");

    r = select_injections(s, 10, 0.4f);
    eqi((long) r.size(), 3, "选择：minScore 过滤掉低分");
    bool low = false;
    for (const auto & p : r) if (p.score < 0.4f) low = true;
    ok(!low, "选择：低于阈值的一条都不注入");

    r = select_injections(s, 2, 0.99f);
    eqi((long) r.size(), 0, "选择：全部低于阈值 -> 一条都不注入（不硬塞）");

    // NaN 必须被挡（否则排序未定义）
    std::vector<Pick> nan = {{0, NAN}};
    r = select_injections(nan, 5, 0.3f);
    eqi((long) r.size(), 0, "选择：NaN 分数被阈值挡住");

    // 负下标挡掉
    std::vector<Pick> bad = {{-1, 0.9f}};
    r = select_injections(bad, 5, 0.0f);
    eqi((long) r.size(), 0, "选择：负下标被丢弃");
}

static void test_render() {
    std::vector<Pick> picks = {{0, 0.9f}, {1, 0.8f}};
    std::vector<std::string> rows = {"甲的内容", "乙的内容"};
    const std::string s = render_injection(picks, rows);
    ok(s.find("[知识库]") == 0, "注入：以 [知识库] 起头（可判别）");
    ok(s.find("甲的内容") != std::string::npos, "注入：含第一条正文");
    ok(s.find("乙的内容") != std::string::npos, "注入：含第二条正文");

    // 关键：每条**只有正文一行**，不带来源尾巴（尾巴会被模型抄进回答）
    const std::string want = "[知识库]\n甲的内容\n乙的内容\n";
    ok(s == want, "注入：逐字节 = 表头 + 每条正文一行（无来源尾巴）");
    ok(s.find("来源") == std::string::npos, "注入：不含来源修饰（模型没得抄）");

    // 空内容跳过（进了索引也不该注入空行）
    std::vector<std::string> rows2 = {"", "有内容"};
    const std::string s2 = render_injection(picks, rows2);
    ok(s2 == "[知识库]\n有内容\n", "注入：空条目被跳过（不产生空行、不留空位）");

    // 一条都没选中 -> 空串（"一个字节都不注入"）
    const std::string s4 = render_injection({}, rows);
    ok(s4.empty(), "注入：无命中 -> 空串（不注入任何字节）");

    // 下标越界不崩
    std::vector<Pick> p5 = {{99, 0.9f}};
    const std::string s5 = render_injection(p5, rows);
    ok(s5.empty(), "注入：下标越界 -> 跳过（不崩）");

    // 部分越界 -> 其余条照渲染（不整份丢）
    std::vector<Pick> p6 = {{99, 0.9f}, {0, 0.8f}};
    const std::string s6 = render_injection(p6, rows);
    ok(s6 == "[知识库]\n甲的内容\n", "注入：部分越界时跳过那一条、其余照渲染");
}

int main(int argc, char ** argv) {
    // 共用向量表路径：默认相对仓库根（tools/rag_index/...），可由 argv[1] 覆盖。
    // 与 Kotlin 侧的单测读同一份文件（那边走环境变量，见 run_rag_index_store_tests.sh）。
    const char * vectors = argc > 1 ? argv[1] : "rag_index/texts_hash_vectors.tsv";
    test_utf8_start();
    test_align();
    test_chunk_empty();
    test_chunk_single();
    test_chunk_utf8_safe();
    test_chunk_paragraph_preferred();
    test_chunk_overlap();
    test_chunk_overlap_ge_target();
    test_chunk_target_zero();
    test_chunk_max_chunks();
    test_chunk_truncated_flag();
    test_chunk_progress();
    test_chunk_hard_cut_counted();
    test_chunk_never_illegal_utf8();
    test_chunk_no_gap();
    test_chunk_hard_cut_no_overlap();
    test_chunk_degrade_order();
    test_chunk_seps_table();
    test_chunk_rule_version_in_fingerprint();
    test_fingerprint();
    test_texts_hash(vectors);
    test_header_roundtrip();
    test_header_reject();
    test_vectors_bytes();
    test_select();
    test_render();
    printf("\nPASS %d / FAIL %d\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
