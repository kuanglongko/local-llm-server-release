// rag_index.h — 知识库索引的「纯逻辑」部分：分块、指纹、头布局、检索选择
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么把这段单独拆一个头文件
// ═══════════════════════════════════════════════════════════════════════════
// 索引一旦错，症状**全部是静默的**，而且比嵌入本身更隐蔽 ——
// 嵌入错了至少"每条都不准"，索引错了是"大部分对、少数几条错"，看起来像模型幻觉：
//
//   · 指纹漏了字段（比如没算 n_ctx / 切块参数）-> 用户换了切块大小或模型，
//     旧索引被当成新的继续用。症状：检索命中一段**旧模型算的**向量，
//     余弦值量纲对不上，排序全乱，而接口 200、日志干净。
//
//   · 指纹算了 mtime 却没算 size —— 反过来也一样。ONNX/GGUF 的"就地替换"
//     会保留 size 改 mtime，而"重新拷贝同尺寸文件"会保留 mtime 改 inode。
//     两种都真实存在，所以两个都得算。
//
//   · 头布局（magic / version / dim / count / 指纹长度）读错一位 ->
//     把指纹当向量读。向量维度对得上（都是 float），**不崩**，
//     只是检索结果永远无意义。所以头必须定长、必须显式校验。
//
//   · 空知识库 / 全部条目编码失败 -> count = 0。若把它当"建好了"，
//     检索恒为空而没有任何提示；若把它当"失败"，用户第一次用 RAG
//     会看到"索引构建失败"（其实只是文档太短）。必须区分"空但有效"
//     与"没建过"（文件不存在）。
//
//   · 分块切在 UTF-8 码点中间 -> 那一段永远编码失败（或被静默替换成 U+FFFD），
//     表现为"某些段落检索不到"，没有任何一条日志会指向切块。
//
// 本文件只做四件事，都不碰 llama / JNI / Android：
//   1. 分块（按段落边界聚拢到目标字符数，绝不切在 UTF-8 码点中间）；
//   2. 指纹（模型路径 / 大小 / mtime / 维度 / 切块参数 -> 一个稳定字符串）；
//   3. 头布局（定长字段 + 校验，含"版本不匹配即作废"）；
//   4. 检索选择（top-k 命中后，哪些条目该拼进 prompt）。
//
// 与 embed_text.h / kv_prefix.h / stop_sequences.h 同一套路：
// 判定抽成纯函数，宿主侧用真数据钉住。
#ifndef LOCAL_LLM_RAG_INDEX_H
#define LOCAL_LLM_RAG_INDEX_H

#include <cstdint>
#include <string>
#include <vector>

namespace ragindex {

// ── 1. 分块 ────────────────────────────────────────────────────────────────

/**
 * 判断 `c` 是否是一个 UTF-8 码点的**起始字节**。
 *
 * 为什么必须判这个：分块下标是按**字节**算的，而中文一个字 3 字节。
 * 切在 continuation 字节（0b10xxxxxx）上，切出来那一段开头的字节序列
 * 不是合法 UTF-8 —— tokenizer 会把它变成替换字符或直接失败，
 * 表现为"某几段永远检索不到"，而没有任何一条日志指向分块。
 *
 * 返回 true 当且仅当 `(c & 0xC0) != 0x80`。
 */
inline bool is_utf8_start(unsigned char c) {
    return (c & 0xC0u) != 0x80u;
}

/**
 * 把 `i` 向前（向字符串开头）调整到一个 UTF-8 码点起始处。
 * 已经在起始处 / `i >= len` / `len == 0` 时原样返回。
 *
 * 为什么"向前"而不是"向后"：块与块之间必须**无缝覆盖**（前一块的结束
 * 就是后一块的开始），否则中间会漏掉几个字节的正文 —— 那一段永远进不了索引，
 * 而检索"找不到"与"文档里没有"在表现上完全一样，无法归因。
 * 向前调整只是让块短几个字节，不产生空洞。
 */
inline size_t align_utf8_back(const std::string & s, size_t i) {
    const size_t len = s.size();
    if (len == 0) return 0;
    if (i >= len) return len;
    while (i > 0 && !is_utf8_start((unsigned char) s[i])) i--;
    return i;
}

/**
 * 从 `i` 向后（向字符串尾部）找下一个 UTF-8 码点起始字节；找不到返回 `len`。
 *
 * 只在一种情形下需要它：**`targetChars` 小于一个码点的字节数**（比如 target=1
 * 而中文字是 3 字节）。此时"向前对齐"会退到 `pos` 本身、等于不前进，
 * 而 `pos + 1` 会切在码点中间 —— 那一块 encode 必然失败。
 * 所以这一档只能**向后**推进到一个完整码点的起点：块比 target 长几个字节，
 * 但至少是一段合法 UTF-8（宁可块大，不可切碎）。
 */
inline size_t next_utf8_start(const std::string & s, size_t i) {
    const size_t len = s.size();
    if (i >= len) return len;
    while (i < len && !is_utf8_start((unsigned char) s[i])) i++;
    return i;
}

/**
 * 切块参数。默认值给的是"手机上一次能装 100 条笔记"这个规模下的甜点值。
 *
 * `ruleVersion` 是**切块规则**的版本（不是索引格式版本，两者独立演进）。
 * 为什么它必须存在：`targetChars` / `overlapChars` 只描述"块多大"，
 * 描述不了"从哪儿切"。规则一改（比如本轮把 5 级降级收成 2 级），
 * 这两个数一个都不变 —— 指纹于是不变，旧索引被当成"仍然可用"：
 * **用旧边界算的向量命中、注入新边界切出的正文**，排序看着像随机，
 * 接口 200、日志干净。所以规则版本必须与那两个数一起进指纹。
 */
struct ChunkCfg {
    int targetChars = 480;   // 每块目标字符数（不是 token —— token 数要模型才知道）
    int overlapChars = 64;   // 相邻块重叠字符数（保住跨越边界的语义）
    int maxChunks = 16384;   // 单库块数上限（防一份超大文件把内存吃光）
                             // 4096 -> 16384（用户拍板）：按默认 480/64 算，
                             // 4096 块 ≈ 170 万字符 ≈ 一个 4~5 MB 的纯文本 ——
                             // 比这更大的文档**会被静默切掉后半段**（见 chunk_text 的
                             // `truncated` 出参）。抬到 16384 的代价是向量区更大
                             // （384 维 ≈ 24 MB / 768 维 ≈ 48 MB 常驻内存），
                             // 而检索是暴力全扫、O(count × dim)。
                             // ⚠ 这个值**必须进指纹**（`c` 那一格）：不进的话旧索引
                             // 会被当成"仍然可用"，于是"按 4096 切出的向量"与
                             // "按 16384 切出的正文"混在一起 —— 排序像随机、接口 200。
    // 切块规则版本（进指纹；见上）。
    // 2 -> 3（本轮）：`kChunkSeps` 补进了 `…` `:` 两条（为了让 Kotlin 侧那张
    // 共享表能同时服务"建库切块"与"导入切段"，按**并集**收口 —— 导入那条路的
    // 结果一个字没变，建库这条路的边界真的变了：同样 480 字窗口里遇到省略号 /
    // 英文冒号，从"硬截"变成"软切"）。只改"从哪儿切"、`targetChars` 与
    // `overlapChars` 一个都不动 —— 不升这个数，旧索引会被继续当成可用，
    // 而那些向量是按旧边界算的。这正是这一格存在的全部理由。
    int ruleVersion = 3;     // 切块规则版本（进指纹；见上）
};

/**
 * 一个候选分隔符。
 *
 * `preferParagraph` 只影响**选择顺序**，不影响"窗口内找不到就不选"这条不变式：
 * 段落分隔符（`\n\n`）比句读（`\n` / `。` …）优先，因为段落边界是作者
 * **显式**画下的语义切分；句读只是"这句话说完了"。
 *
 * ⚠ `sep` 必须是合法 UTF-8，且**匹配点的末尾就是块边界**（切在分隔符之后）。
 */
struct ChunkSep {
    const char * sep;
    bool preferParagraph;
};

/**
 * 切块用的分隔符表 —— **只有两级**，且这是与 Kotlin 侧 `TextBoundary.SOFT_CUT_SEPS`
 * 的**逐条契约**（两边必须一模一样，否则建库/检索的块边界分家）。
 *
 * ⚠ 那张表在 Kotlin 侧是**共享**的（`LlmEngine` 只转发，不自己存一份）：
 *   `RagArchive.splitLongLine` 也用它来决定"导入的一条超长正文切成几条条目"。
 *   两边各写一张的代价不是"重复几行"，是**改一处另两处不会红** —— 而"切在哪儿"
 *   一岔开，症状是"同一份文档建库与导入切得不一样"，逐字相同地看不出来。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么只有两级（而不是"段落 > 句子 > 逗号 > 顿号 > 空格 > 硬截"五级）
 * ═══════════════════════════════════════════════════════════════════════════
 * 分级越多、"窗口内找不到就往前找最近的一次"这种兜底越多，而兜底里有两条
 * **会切出错块**的路径，穷举都撞出来了：
 *
 *   ① **切在 UTF-8 码点中间**。`。！？；，、` 全是 3 字节中文标点，而切点是按
 *      **字节偏移**记的 —— 谁也没保证它落在码点边界上。后果不是"语义断了"，
 *      是那一块 encode 必然失败（`skipped++`、整库标"需重建"）。
 *
 *   ② **整块丢字**。若某一轮窗口内一个分隔符都没有，而往回找到的那个分隔符
 *      落在 `pos` 之前，`cut <= pos`，兜底"至少前进 1 字节"就把
 *      `pos` 到那个分隔符之间的正文**整段跳过**。丢的字节数不多、不报错、块数正常，
 *      而"检索找不到"与"文档里没有"表现完全一样 —— 无法归因。
 *
 * 第 4、5 级（空格 / `-` / `·`）在中文里一找一个准，等于把 480 的目标长度压到
 * 实际 200~400 字节：块数、编码耗时、检索粒度全都跟着变，而语义增益极低。
 * 所以它们**不进表**。有效的只有段落与句读这两级，第三级就是硬截。
 *
 * `、`（顿号）与 `；`（全角分号）留在表内：它们是**句读级**，不是"凑数的标点"，
 * 且与半角 `;` 同义 —— 中文正文里成对出现，不收会切出明显不自然的块。
 */
inline const ChunkSep kChunkSeps[] = {
    { "\n\n", true  },   // 段落
    { "\n",   false },   // 换行
    { "。", false },     // 句号
    { "！", false },
    { "？", false },
    { "；", false },
    { "，", false },     // 逗号
    { "、", false },     // 顿号
    { "…", false },      // 省略号
    { "!",  false },
    { "?",  false },
    { ";",  false },
    { ",",  false },
    { ":",  false },
};

/**
 * 分隔符表的元素个数。**Kotlin 侧必须给出同一个数** ——
 * `tools/run_rag_index_guard.sh` 逐条比对两边的表（改了一边没改另一边 = 红）。
 */
inline size_t chunk_sep_count() {
    return sizeof(kChunkSeps) / sizeof(kChunkSeps[0]);
}

/**
 * 窗口内找一个合法切点。返回 `true` 且写出 `outCut`（块边界，已按 UTF-8 对齐）。
 *
 * 三条规则，缺一不可：
 *   1. 只在 **`(pos, pos+target]`** 内找（`<= pos` 的匹配一律不算 —— 那是
 *      上一轮已经消化过的正文，拿它当切点就是 ② 那条"整块丢字"）；
 *   2. 候选切点先 `align_utf8_back`；**对齐后 `<= pos` 就当没找到**，
 *      继续降级，绝不"退化成前进 1 字节"（这条同时堵住 ① 与 ②）；
 *   3. 先段落、后句读；同级里取**最靠右**的（块尽量接近 target）。
 *
 * "窗口内找不到"**是正常结局**，不是错误：调用方据此走硬截。
 * 绝不去窗口外找 —— 那是 ② 的另一半成因。
 */
inline bool pick_chunk_cut(const std::string & text, size_t pos, size_t end,
                           size_t & outCut) {
    for (int pass = 0; pass < 2; pass++) {           // pass 0 = 段落，1 = 句读
        size_t best = 0;
        bool found = false;
        for (size_t i = 0; i < chunk_sep_count(); i++) {
            const ChunkSep & cs = kChunkSeps[i];
            const bool isPara = cs.preferParagraph;
            if ((pass == 0) != isPara) continue;
            const std::string sep(cs.sep);
            const size_t n = sep.size();
            // 匹配点 `p` 必须满足 pos < p+n <= end；`rfind` 的第二个参数
            // 是"允许匹配的最右起始位置"，所以上界是 end-n（end < n 时没有候选）。
            if (end < n) continue;
            const size_t p = text.rfind(sep, end - n);
            if (p == std::string::npos) continue;
            const size_t cutRaw = p + n;
            if (cutRaw <= pos || cutRaw > end) continue;      // 规则 1
            const size_t cut = align_utf8_back(text, cutRaw);
            if (cut <= pos) continue;                         // 规则 2
            if (!found || cut > best) { best = cut; found = true; }
        }
        if (found) { outCut = best; return true; }
    }
    return false;
}

/**
 * 分块：把 `text` 切成若干段，每段不超过 `targetChars` 字节
 * （除"无法再切"的极端情形），且**每段都是合法 UTF-8 边界**。
 *
 * 策略（2 级降级 + 硬截）：
 *   1. 段落 `\n\n`；
 *   2. 句读 `\n` `。` `！` `？` `；` `，` `、` `!` `?` `;` `,`；
 *   3. 硬截 `pos + targetChars`（同样按 UTF-8 对齐）。
 *
 * 与前一轮（"窗口内找不到就往前找最近的一次"）的差别，是本轮修复的全部内容：
 *   · **不往窗口外找**。"找不到"是正常结局；
 *   · 候选切点 **对齐后 <= pos 就当没找到**，继续降级 —— 不再有"前进 1 字节"
 *     那条会把正文整段吞掉的兜底；
 *   · 每轮**严格前进 1 个完整码点**（硬截也按码点对齐），于是
 *     `overlap == 0` 时所有块拼回去**逐字节等于原文**。
 *
 * 恒等式（由单测钉住）：
 *   · 每块 trim 后非空；
 *   · 每块首字节是 UTF-8 起始字节；
 *   · 块数 ≤ maxChunks；
 *   · **无空洞（严格覆盖）**：实现内部的切点 `[s_i, e_i)` 满足 `s_0 == 0`、
 *     `e_last == len`、`s_{i+1} == e_i`（`overlap == 0` 时）—— 于是**每个字节
 *     都恰好落在一块里**，"整块丢字"不可能发生。这一条是严格覆盖的**真正形式**，
 *     由 `test_chunk_no_gap` 直接对切点断言（而不是"拼块内容 == 原文"，见下）。
 *
 * ⚠ 一条**看着更自然、实际是错的**断言：**"`overlap == 0` 时把块拼回去逐字节
 *   等于原文"**。它不成立，而且不是本实现的 bug：块是 **trim 过**的
 *   （丢掉首尾空白），而分隔符切点让块**常以 `\n` 结尾** —— 那个 `\n` 进不了
 *   任何一块的内容。旧实现同样如此（我拿旧实现跑同一份随机语料：6060/21459
 *   不相等，新实现 5384/22212，还略好一点）。
 *   所以不变式要锚在**切点**上（覆盖性），不能锚在**块内容拼接**上 ——
 *   那是 trim 的定义，不是丢字。（本轮我第一版探针就是这么写错的。）
 *   · `targetChars <= 0` 时退化成"整篇一块"（不是死循环）；
 *   · 每轮严格前进（重叠模式下块数有界）。
 *
 * `hardCuts`（可空）：**硬截发生了几次**。为什么要把这件事报出来 ——
 * 硬截 = "窗口内一个句读都没有"，那种块在检索里天生不准（语义被拦腰截断），
 * 而它此前**没有任何痕迹**：用户只看到"有些文件检索不到"，无从下手。
 * 计数由调用方写进日志，见 `LlmEngine.buildIndex` 的"有 N 块是硬截的"。
 *
 * `truncated`（可空）：**是否因为撞上 maxChunks 而提前收工**（= 文档后半段
 * 根本没进索引）。为什么它必须与 `hardCuts` 分开报 ——
 *   · `hardCuts` 说的是"块**边界**不好"（内容全在，只是切得糙）；
 *   · `truncated` 说的是"块**没了**"（内容真的少了一截）。
 * 两者此前都没有痕迹，而后果差一个量级：前者是召回率低，后者是**丢内容**。
 * 而"丢内容"与"文档里本来就没有"在检索上**逐字相同**（都是"搜不到"）——
 * 用户只能靠反复换关键词去猜。所以调用方必须在两条路上分别写日志。
 *
 * ⚠ 判据是"**没走到文末就退出了**"（实现里的 `brokeByEnd` 标志），
 * 不是"块数 == maxChunks"、也不是 `pos < len`：一份文档正好切出 maxChunks 块、
 * 且最后一块正好到文末，那是**完整**的 —— 报成截断会让用户白白去重建一遍；
 * 而 `pos < len` 更坏（末块的开头当然 < len），会让**每份正常文档**都被报成截断。
 */
inline std::vector<std::string> chunk_text(const std::string & text, const ChunkCfg & cfg,
                                           int * hardCuts = nullptr,
                                           bool * truncated = nullptr) {
    std::vector<std::string> out;
    if (hardCuts) *hardCuts = 0;
    if (truncated) *truncated = false;
    const size_t len = text.size();
    if (len == 0) return out;
    int target = cfg.targetChars > 0 ? cfg.targetChars : (int) len;
    int overlap = cfg.overlapChars > 0 ? cfg.overlapChars : 0;
    if (overlap >= target) overlap = target > 1 ? target / 2 : 0;   // 重叠 >= 目标 = 不前进死循环
    const int maxChunks = cfg.maxChunks > 0 ? cfg.maxChunks : 1;

    size_t pos = 0;
    // "正常收尾"的显式标志（见函数尾部的 truncated 判据）：
    // 只有 `end >= len` 那一支才算"切完了"，循环条件失败才算"被截住"。
    bool brokeByEnd = false;
    while (pos < len && (int) out.size() < maxChunks) {
        size_t end = pos + (size_t) target;
        if (end > len) end = len;

        // 只在**没有到文末**时才找边界；到文末就整段收掉。
        // `cutByRule` = 本轮是**软切**（在窗口内找到了分隔符）还是**硬截**。
        // 它决定"下一块起点"能不能重叠 —— 见下面那一段的说明，这是本轮修的那处。
        bool cutByRule = false;
        if (end < len) {
            size_t cut = 0;
            if (pick_chunk_cut(text, pos, end, cut)) {
                end = cut;
                cutByRule = true;
            } else {
                // 第三级：硬截。**必须落在码点边界上**，这是与"往前找分隔符"
                // 那条老路径最关键的区别：
                //   · 先 `align_utf8_back(end)` —— 中文尾字节正好落在 target 上时
                //     向前退一个码点（块短几字节，语义不损）；
                //   · 仍 `<= pos`（**target 小于一个码点**，比如 target=1 而字是
                //     3 字节）时**向后**推进到下一个码点起点，绝不 `pos + 1`。
                //     `pos + 1` 会切在码点中间，那一块 encode 必然失败
                //     （`skipped++`、整库标"需重建"）—— 老实现就是栽在这条兜底上。
                // 向后推进仍要防死循环：`next_utf8_start` 最多走到 `pos+k`（k ≤ 4），
                // 而 pos 每轮严格前进，所以循环仍收敛。
                if (hardCuts) (*hardCuts)++;
                size_t hard = align_utf8_back(text, end);
                if (hard <= pos) {
                    hard = next_utf8_start(text, pos + 1);
                    if (hard <= pos) hard = len;      // 理论上不可达（末字节）
                }
                end = hard > pos ? hard : len;
            }
        }

        std::string piece = text.substr(pos, end - pos);
        // trim：只裁首尾的 ASCII 空白与 UTF-8 不必管（空白都是 ASCII）
        size_t b = 0, e = piece.size();
        while (b < e && (unsigned char) piece[b] <= ' ') b++;
        while (e > b && (unsigned char) piece[e - 1] <= ' ') e--;
        if (e > b) out.push_back(piece.substr(b, e - b));

        if (end >= len) { brokeByEnd = true; break; }
        // 下一块从 overlap 之前开始（并按 UTF-8 对齐），但必须**严格前进**。
        //
        // ⚠ 硬截那一轮**不重叠**（`next = end`）。为什么 —— 重叠的用途是"让语义
        //   跨块的那一段两边都看得见"。而硬截 = 窗口内**一个分隔符都没有**，回退
        //   `overlap` 得到的下一块，其窗口右端仍是"本轮起点 + target"，也就是**整段
        //   区间仍在上一轮已消化过的范围里**，那一轮同样找不到分隔符、同样硬截：
        //     · `overlap` 那段正文被切**两遍**（相邻块最长公共片段 ≥ overlap，
        //       检索时同一段正文互相抢 top-k）；
        //     · 硬截计数被灌水（用户看到"有 N 块是硬截的"，N 里一半是重切）。
        //   软切那一轮**保留**重叠：切点由分隔符决定，"语义跨边界"是真需求。
        //   代价：硬截段变"不重叠"—— 但硬截段本来就是"切得糙、召回率低"，
        //   重叠也救不回来；而**不重复**是净收益。
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
    // 循环退出有两种原因：
    //   · `pos >= len` 或最后一块走到文末（`break` 那一支）—— **切完了**；
    //   · `out.size() >= maxChunks` —— **被上限截住**，文档后半段一个字节都没进索引。
    // 后者必须报出去（它此前完全没有痕迹，见 truncated 出参的说明）。
    //
    // ⚠ 判据不能写成 `pos < len`：最后一块走到文末时 `pos` 指向那一块的**开头**，
    //   它当然 `< len` —— 于是每一份正常文档都会被报成"截断"。
    //   本文件第一版就是这么写的，`test_chunk_truncated_flag` 的四条正例当场抓到。
    //   正确形态只有一个：`break` 那一支**显式**声明"切完了"，剩下的才是截断。
    //   `brokeByEnd` 就是那个显式声明（初值 false = 不是正常收尾）。
    if (truncated) *truncated = !brokeByEnd;
    return out;
}

// ── 2. 指纹 ────────────────────────────────────────────────────────────────

/** 索引身份：任一字段变化都必须作废重建。 */
struct IndexFingerprint {
    std::string modelPath;    // 嵌入模型路径
    long long   modelBytes = 0;   // 模型文件大小（"重新拷贝同尺寸文件"改 mtime 不改大小）
    long long   modelMtime = 0;   // 模型文件 mtime（"就地替换"改 mtime 可能不改大小）
    int         dim = 0;          // 向量维度（换 embed 模型最直接的信号）
    int         targetChars = 0;  // 切块参数（改了参数、块边界就变，旧索引不可用）
    int         overlapChars = 0;
    // 切块**规则**版本（见 ChunkCfg.ruleVersion）：只改「从哪儿切」而不改块大小时，
    // 上面两个数一个都不变 —— 没有它，旧边界算的向量会被继续用来命中新边界切出的正文。
    int         chunkRuleVersion = 0;
    // 单条块数上限（见 ChunkCfg.maxChunks）。为什么它也必须进指纹：
    // 它同样只影响"切出多少块"，而 `targetChars`/`overlapChars`/`ruleVersion`
    // 一个都不变 —— 4096 -> 16384 这一改，大文档从"被截断"变成"完整"，
    // 两个版本的块集合**根本不同**。不进指纹 = 旧索引（按 4096 截断的）
    // 被当成"仍然可用"，而它其实是**半份**：后半段的正文一个向量都没有。
    // 症状与"文档里没有那一段"逐字相同（搜不到），接口 200、日志干净。
    int         maxChunks = 0;
    std::string textsHash;        // **正文**内容 hash（见下）
    int         rows = -1;        // 正文文件的行数（-1 = 老格式，没有这一项）
    // ── 嵌入身份（对着 Kotlin 侧 FpInput 的那四个可空字段，空串 = 不知道）─────
    // 为什么"模型路径 + 大小 + mtime + 维度"还不够，要再加这四个：
    //   · **池化方式**（CLS / LAST / MEAN）直接决定那个向量是什么。同一份文本、
    //     同一个模型、同一个维度，按 CLS 取与按 last 取得到的向量**完全不同**。
    //     指纹里没有它时，升级（池化语义修正）之后旧索引会被当成"仍然可用"：
    //     用 last 语义的向量去命中、注入按 CLS 算出来的正文 ——
    //     分数看着像随机，接口 200、日志干净。
    //   · **n_embd_out**（模型声明的输出维度）是"库按它分配 buffer"的那个数，
    //     与运行时读向量的步长 `dim` 是**两条路**。混成一个名字之后，
    //     "其中一个改了而另一个没改"就查不出来。
    //   · 大小 + mtime 在这里**再写一遍**不是冗余：Kotlin 侧的 FpInput 里
    //     `embedBytes`/`embedMtime` 是"嵌入这份"的身份（与对话模型同目录、
    //     大版本切换时最容易搞混），而 `modelBytes`/`modelMtime` 是历史字段。
    //     两者同源、值相等，但**类型不同**（一个可空 = 可以"不知道"），
    //     名字必须分开写，否则"不知道"会被写成 0 这个**貌似已知**的值。
    std::string embedBytes;       // 模型字节数（空串 = 不知道）
    std::string embedMtime;       // 模型 mtime（空串 = 不知道）
    std::string pooling;          // 池化类型原值（空串 = 不知道）
    std::string embedOutDim;      // 模型声明的 n_embd_out（空串 = 不知道）
    // 合批的**每批条数**（ISSUE #269 第 2 步：合批可见可调）。
    // 为什么它**必须**进指纹：合批是"一次前向编 n 条"，而不同的批大小
    // 下，每条文本的**截断点可以不同**（每条的位置预算受批大小影响）——
    // 即使当下实现里两条路径逐位相同，"两条路径算出的是同一个向量"也是一个
    // **待验证的断言**（第 3 步才去逐位验）。不进指纹的后果：用户把批大小
    // 从 1（关合批）改成 8，旧索引被当成"仍然可用"—— 而新旧向量一旦不逐位相同，
    // 检索就是"用一种语义命中、注入另一种语义的正文"（排序像随机，接口 200）。
    // ⚠ 它是**必填**（非可选）：旧索引里没有这一格，那就应当被判成"认不出来、重建一次"
    //（与 `c` / `k` 同一条路）—— 而**不是**"默认相等 → 放行"。
    int         embedBatch = 0;
    int         version = 0;      // 索引格式版本（本文件的头版本，不是 App 版本）
};

/**
 * 正文内容 hash（FNV-1a 64 位，小写十六进制）。
 *
 * 为什么要把它算进指纹：**索引不是只有向量**。检索用向量命中行号，注入却要拿
 * **那一行的正文**去拼 prompt —— 于是"向量"与"正文"是一对，任何一半变了，
 * 另一半就不再描述同一件事：
 *
 *   · 用户改了正文但没重算向量 -> 检索命中"旧语义"的向量、注入"新内容"的正文：
 *     排序看着像随机，接口 200、日志干净；
 *   · 正文文件整个丢了 -> 每一条命中都取不到正文，注入侧渲染出空串，
 *     而"注入了 3 条"照样写进日志（回答里什么都没有，无法归因）。
 *
 * 这两种都是"文档与索引各自都合法、配在一起却错"。把它们焊进同一个指纹后，
 * 不一致 = 指纹不匹配 = **当作没有索引**（重建），与换模型走同一条路 ——
 * 不需要第二套"作废"状态，也没有第二个地方能忘记维护它。
 *
 * FNV-1a 是最小可用选择：确定性、无依赖、逐字节敏感（改一个字节即变），
 * 且 C++ 与 Kotlin 两边能用同一串 10 行代码写出**完全相同**的结果
 * （`tools/rag_index/texts_hash_vectors.tsv` 是两边共用的向量表，各语言单测都读它）。
 * 这里**不需要**抗碰撞（它不是安全边界，是自己写自己读的一致性标记）。
 */
inline std::string texts_hash_of(const std::string & raw) {
    // FNV-1a 64：offset basis 14695981039346656037，prime 1099511628211。
    // 用 uint64_t 自然回绕（无符号溢出是良定义的），不引入任何依赖。
    unsigned long long h = 14695981039346656037ULL;
    for (size_t i = 0; i < raw.size(); i++) {
        h ^= (unsigned long long) (unsigned char) raw[i];
        h *= 1099511628211ULL;
    }
    // 手工转小写十六进制：`%llx` 依赖 stdio，而本文件刻意不引任何头
    // （被 llama_jni.cpp 与宿主单测共用，越少的依赖越不容易在某一边编不过）。
    static const char * kHex = "0123456789abcdef";
    std::string out(16, '0');
    for (int i = 15; i >= 0; i--) {
        out[(size_t) i] = kHex[(int) (h & 0xFULL)];
        h >>= 4;
    }
    return out;
}

/**
 * 指纹字符串。**必须是确定性的**（同输入永远同输出）——它会被落进索引文件头，
 * 每次加载时逐字节比对。含路径，所以"把模型换个目录"也算变了（合法：
 * 我们无法确认两个路径指向同一份权重，宁可变）。
 *
 * 用 `\x1f`（ASCII Unit Separator）分隔字段：它在路径里不可能出现
 * （路径是要交给文件系统 API 的，控制字符进不去），于是不会出现
 * "两份不同输入拼出同一个字符串"这类拼接歧义 —— 那会让指纹失效**静默**。
 *
 * 字段：v=格式版本 / p=模型路径 / b=模型大小 / m=模型 mtime / d=维度 /
 *       t=块目标字符数 / o=块重叠字符数 / **k=切块规则版本** / **c=单条块数上限** /
 *       **x=正文 hash** / **r=正文行数** / e=嵌入模型字节数 / n=嵌入模型 mtime /
 *       q=池化方式 / z=输出维度。后四个可以为**空串**（= 不知道），
 *       它们不参与"待重建"的判定。
 * ⚠ 字段集合是与 Kotlin 侧 `RagIndexStore.fingerprintOf` 的**契约**：
 *   少算一个 = 那一类变化不再作废索引（旧向量被继续使用）。
 *   两边的集合由 `tools/rag_index/check_fp_fields.py` 逐字段比对。
 */
inline std::string fingerprint_of(const IndexFingerprint & fp) {
    std::string s;
    s += "v";      s += std::to_string(fp.version);      s += '\x1f';
    s += "p";      s += fp.modelPath;                    s += '\x1f';
    s += "b";      s += std::to_string(fp.modelBytes);   s += '\x1f';
    s += "m";      s += std::to_string(fp.modelMtime);   s += '\x1f';
    s += "d";      s += std::to_string(fp.dim);          s += '\x1f';
    s += "t";      s += std::to_string(fp.targetChars);  s += '\x1f';
    s += "o";      s += std::to_string(fp.overlapChars); s += '\x1f';
    // `k` = 切块规则版本。与 `t`/`o` 分开一格是刻意的：规则改了、块大小没改时，
    // 只有这一格会变（另见 ChunkCfg.ruleVersion 的说明）。
    s += "k";      s += std::to_string(fp.chunkRuleVersion); s += '\x1f';
    // `c` = 单条块数上限。为什么它要**单独一格**而不是并进 `k`（规则版本）：
    // `k` 回答"规则是什么"，`c` 回答"上限是多少"，而用户看到 `k` 变了时
    // 无法知道是改了哪条规则（他是要去动手的）。分开之后日志能直说
    // "块数上限 变了：索引 4096 ≠ 当前 16384" —— 一句可执行的话。
    s += "c";      s += std::to_string(fp.maxChunks);    s += '\x1f';
    s += "x";      s += fp.textsHash;                    s += '\x1f';
    // `r` = 正文文件的行数。为什么行数也要进指纹：追加一条时"索引里有多少行向量"
    // 与"正文里有多少行"可能**故意不相等**（新加的那条还没算出向量）——
    // 单看正文 hash 是看不出来的（hash 只证明"是哪一份正文"）。
    // `rows` 与 `count` 不等 = 索引落后于正文 = 需重建，这一条对着 Kotlin 侧
    // 的 [RagIndexStore.rowsInFingerprint] 逐字段比对（check_fp_fields.py）。
    s += "r";      s += std::to_string(fp.rows);         s += '\x1f';
    // `e` / `n` / `q` / `z` = 嵌入身份（用户拍板：池化方式与输出维度必须进指纹，
    // 否则"升级了取向量的方式"这件事在旧索引上完全不可见）。
    // 空串 = 不知道（Kotlin 侧同理），而**空串不等于任何值** ——
    // 于是"不知道"永远不会被当成"相等"而放行一个语义已变的索引。
    s += "e";      s += fp.embedBytes;                   s += '\x1f';
    s += "n";      s += fp.embedMtime;                   s += '\x1f';
    s += "q";      s += fp.pooling;                      s += '\x1f';
    s += "z";      s += fp.embedOutDim;                  s += '\x1f';
    // `g` = 合批每批条数（batch **g**roup size）。为什么用 `g` 而不是
    // `b`：`b` 已经是"模型字节数"，而前缀一旦撞车，`fpFields()` 会把后一个静默丢掉
    //（= 那个字段不参与比对）。与上面那些字段一样，它是与 Kotlin 侧
    // `RagIndexStore.fingerprintOf` 的契约，由 check_fp_fields.py 逐字段比对。
    s += "g";      s += std::to_string(fp.embedBatch);   s += '\x1f';
    return s;
}

/**
 * 从一份指纹里取出正文 hash（`x` 字段）。取不到返回 false。
 *
 * 为什么在 C++ 侧也要有这个取法：Kotlin 侧要靠它把"指纹不匹配"拆成
 * 「模型/参数变了」与「正文变了」两种 —— 它们在用户看来是完全不同的处置
 * （前者要换回模型，后者只要重建索引）。两边的取法必须一致，否则拆出来的
 * 诊断会互相矛盾（一个说"模型变了"、另一个说"正文变了"）。
 */
inline bool texts_hash_in(const std::string & fingerprint, std::string & out) {
    const char sep = '\x1f';
    size_t pos = 0;
    while (pos <= fingerprint.size()) {
        size_t end = fingerprint.find(sep, pos);
        if (end == std::string::npos) end = fingerprint.size();
        if (end > pos + 1 && fingerprint[pos] == 'x') {
            out = fingerprint.substr(pos + 1, end - pos - 1);
            return true;
        }
        if (end == fingerprint.size()) break;
        pos = end + 1;
    }
    return false;
}

/** 两份指纹是否等价（== 索引可用；!= 必须作废重建）。 */
inline bool fingerprint_matches(const std::string & a, const std::string & b) {
    return a == b;
}

// ── 3. 头布局 ──────────────────────────────────────────────────────────────

// magic 选的是一个不可能与"被误当成别的文件"混淆的值：
// 前 4 字节 'R''A''G''1'。读取时先判 magic，不是就当"没有索引"，
// **绝不去猜**（猜错就是把别的文件当向量读，不崩、只是检索全错）。
inline const char * kMagic = "RAG1";

struct Header {
    std::string fingerprint;   // 变长，但带长度前缀
    int dim = 0;
    int count = 0;
};

/**
 * 序列化头：`magic(4) | fpLen(4 LE) | dim(4 LE) | count(4 LE) | fp(fpLen)`。
 *
 * 为什么定长字段摆在变长指纹**之前**：读的时候要先拿到 `fpLen`
 * 才知道该读多少字节的指纹。把指纹放前面就必须先猜长度（或读到分隔符）——
 * 而指纹里含路径，路径里什么都可能有，分隔符方案在"路径恰好含分隔符"时
 * 会静默截断，于是**指纹比对变成永久失败或永久成功**。
 */
inline std::string encode_header(const Header & h) {
    std::string s;
    s.append(kMagic, 4);
    const uint32_t fpLen = (uint32_t) h.fingerprint.size();
    const uint32_t dim = (uint32_t) (h.dim < 0 ? 0 : h.dim);
    const uint32_t cnt = (uint32_t) (h.count < 0 ? 0 : h.count);
    for (int i = 0; i < 4; i++) s.push_back((char) ((fpLen >> (8 * i)) & 0xFF));
    for (int i = 0; i < 4; i++) s.push_back((char) ((dim   >> (8 * i)) & 0xFF));
    for (int i = 0; i < 4; i++) s.push_back((char) ((cnt   >> (8 * i)) & 0xFF));
    s += h.fingerprint;
    return s;
}

/**
 * 反序列化头。返回 false 表示"这不是我们的索引 / 文件被截断"，
 * 调用方据此当作**没有索引**（重建），而不是当"读到一半也能用"。
 *
 * 为什么任何一步不成立都整体拒绝：部分可信的头比没有头更危险 ——
 * 它会让调用方按一个**错的 dim/count** 去读向量数组，读到的是错位的 float。
 */
inline bool decode_header(const std::string & buf, Header & out, size_t & headerLen) {
    headerLen = 0;
    if (buf.size() < 16) return false;                         // magic + 3 个 u32
    if (buf.compare(0, 4, kMagic, 4) != 0) return false;
    auto u32 = [&](size_t off) -> uint32_t {
        return (uint32_t) ((unsigned char) buf[off])
             | ((uint32_t) ((unsigned char) buf[off + 1]) << 8)
             | ((uint32_t) ((unsigned char) buf[off + 2]) << 16)
             | ((uint32_t) ((unsigned char) buf[off + 3]) << 24);
    };
    const uint32_t fpLen = u32(4), dim = u32(8), cnt = u32(12);
    if (fpLen > 4096 || dim == 0 || dim > 65536) return false;  // 荒谬值 = 不是我们的文件
    if (buf.size() < 16 + (size_t) fpLen) return false;         // 截断
    out.fingerprint.assign(buf, 16, fpLen);
    out.dim = (int) dim;
    out.count = (int) cnt;
    headerLen = 16 + (size_t) fpLen;
    return true;
}

/** 向量区应有多少字节；调用方据此判"文件是不是被截断"。 */
inline size_t vectors_bytes(int count, int dim) {
    if (count <= 0 || dim <= 0) return 0;
    return (size_t) count * (size_t) dim * sizeof(float);
}

// ── 4. 检索选择 ────────────────────────────────────────────────────────────

/** 一条检索命中：在索引里的行号 + 分数。 */
struct Pick {
    int index = -1;
    float score = 0.0f;
};

/**
 * 从已排序的命中里选出**要拼进 prompt** 的那些。
 *
 * 两条规则，都是为了"少注入废话"：
 *   · `minScore`：低于阈值的**一条都不要**。检索没有命中时，正确的
 *     行为是"不注入"（模型自己答），而不是"把最不像的几条硬塞进去"——
 *     后者会用一个无关段落**污染**回答，且看不出是谁干的。
 *   · `maxItems`：条数上限。拼多了会把有限的 ctx 挤掉，还会稀释真正相关的那条。
 *
 * `minScore <= 0` 时退化为"不按分数过滤"（只受 maxItems 限制）——
 * 保留一个显式关掉阈值的口子，便于对比测试。
 */
inline std::vector<Pick> select_injections(const std::vector<Pick> & sorted,
                                           size_t maxItems, float minScore) {
    std::vector<Pick> out;
    for (const auto & p : sorted) {
        if ((int) out.size() >= (int) maxItems) break;
        if (minScore > 0.0f && !(p.score >= minScore)) continue;   // NaN 也挡在这里
        if (p.index < 0) continue;
        out.push_back(p);
    }
    return out;
}

/**
 * 把选中的条目拼成注入片段。
 *
 * 格式刻意固定且可判别：以 `[知识库]` 起头、**每条一行纯正文**。
 * 为什么不用自由格式：**可判别**才能让"到底注入了没有"在回答里看得出来，
 * 也才能让守卫断言"开关关闭时一个字节都不注入"。
 *
 * 每条**只拼正文、不带来源尾巴**：来源（`（来源: <标签>）`）曾是行尾的一段
 * 普通上下文文本，模型看得见就会把整行（含尾巴）抄进回答。用户要的是"召回自然、
 * 别复述元信息"，所以这里不再给它可抄的尾巴。来源仍落盘、在「维护知识库」列表
 * 与 `local_rag` / `/health` 的 `rag_*` 里可见，只是不再进 prompt。
 *
 * `picks` 里的 `index` 是**索引里的行号**，`rows` 必须按行号对齐（下标即行号）；
 * 越界/空正文的那条跳过（**不崩**），一条都不剩时返回空串。
 */
inline std::string render_injection(const std::vector<Pick> & picks,
                                    const std::vector<std::string> & rows) {
    std::string s;
    for (const auto & p : picks) {
        if (p.index < 0 || (size_t) p.index >= rows.size()) continue;
        if (rows[(size_t) p.index].empty()) continue;
        if (s.empty()) s += "[知识库]\n";
        s += rows[(size_t) p.index];
        s += "\n";
    }
    return s;
}

} // namespace ragindex

#endif // LOCAL_LLM_RAG_INDEX_H
