// kv_diff_where.h —— 「这一轮的复用为什么没了」的**定位**（纯逻辑，宿主可编、可单测）
//
// ══════════════════════════════════════════════════════════════════════════
// 这个文件只回答一件事：**公共前缀断掉的那一处，两侧的字节为什么不同？**
// ══════════════════════════════════════════════════════════════════════════
// 真机上用户连着问了五轮的是同一句话：「它明明可以复用，为什么断在 152」。
// 而在此之前所有读数都只回答"断在第几枚"——**没说是谁的字节变了**。于是每一次
// 排查都走向服务端的参数（调 ctx、调 max_tokens、调 parallelN），越调越不像。
//
// 成因只有三种，处置**完全相反**（另加一档"是**我们自己**扔的"，见 ④）：
//
//   ① `kExtra`      —— 本轮 prompt 的头一段里夹带了别的东西（知识库注入块、
//                      客户端把历史截短后重新拼的前缀）。分叉点会**正好压在**
//                      那段内容的头一枚上 —— 因为那段是每轮新生成的字节。
//                      处置：让那段内容跨轮不变（定长/定宽/固定位置），或搬到尾部。
//
//   ② `kPrevMoved`  —— 上一轮的对话历史这一轮**不在最前面**了（客户端回传的历史
//                      变了：被别的实现截过、换了会话、插了一条进去）。指纹能在
//                      中间找到它，但前面多出了东西 —— 那不是我们砍的。
//                      处置：查客户端/对端回传的历史，服务端参数一律没用。
//
//   ③ `kPrevGone`   —— 上一轮那段历史这一轮**一处都找不到**。它不是"被挪了"，
//                      是真的换了一份内容（换了会话 / 换了 system / 换了模板）。
//
// 三种都不是时才是 ④ `kUnknown` —— 两侧字节都对得上本会话自己的内容、
// 开头也同形，那才轮到"判据本身有问题"。
//
// ⑤ `kPrevTrimmed`（ISSUE #503 真机）：上一轮那**整段**在本轮逐枚相同，而本轮只是它的
//   一截**开头** —— 上一轮的开头还在、变的是我们喂进去的起点。它与 ③ 在旧读数上
//   **同值**（`prev_head_in_cur == -1`：上一轮开头那 512 枚在本轮一枚也放不下），
//   于是 ③ 那句「两轮喂进去的不是同一份对话」会在明明是我们自己丢的历史上下结论。
//   处置：查 `prompt_trimmed` / `start` / `start_prev` / `spare`，别去动客户端。
//
// ⑥ `kStartShifted`（同一条真机的**现状**）：断点在**本轮之内**、两侧内容都对得上，
//   分不开的只有一件事 —— 本轮的起点比上一轮的起点往前挪了几枚。它是"每轮全量
//   prefill"那条自锁在读数上的样子：内容没变、起点每轮往后滑，于是每一轮都只能
//   接上一小截。处置在 [Geometry::start_prev] / [Geometry::start]：`start != start_prev`
//   就是那件事本身，改客户端参数一辈子也改不出来。
//
//   它与 ⑤ 的差别只说一件事：⑤ 是"整段都对上了、只是更短"（断点顶在本轮末尾），
//   ⑥ 是"分叉在本轮之内"（后半截还得重新算）。
//
// ⚠ 这个文件**不猜**：所有判据都是「下标落在哪个区间」这种可被数据直接回答的问题。
//   前六轮里栽过的两次（把"我们自己砍的"说成"对端的历史变了"、把"开头就变了"
//   说成"回复没被带回来"）都是因为给了成因一个名字却没有能证伪它的数。
#ifndef LOCAL_LLM_KV_DIFF_WHERE_H
#define LOCAL_LLM_KV_DIFF_WHERE_H

#include <algorithm>
#include <string>
#include <vector>

namespace kvwhere {

/** 断点两侧的差异属于哪一类（顺序即优先级，见 [where]）。 */
enum DiffCause {
    /** 本轮 prompt 里夹带的那段额外内容（注入块等）—— 断点压在它头上。 */
    kExtra = 0,
    /** 上一轮的历史被**挪到后面**了（指纹在中间找得到，但不在 0）。 */
    kPrevMoved = 1,
    /** 上一轮的历史这一轮一处都找不到（换了内容，不是被挪）。 */
    kPrevGone = 2,
    /** 都解释不了：两侧都是本会话自己的内容、开头也同形。 */
    kUnknown = 3,
    /**
     * 上一轮那**整段**在本轮逐枚相同，且本轮就是它的**开头一截** ——
     * 也就是说：本轮一枚都没被换，是我们自己把上一轮的开头扔掉了（丢历史 / 起点后移）。
     * 这一档与 [kPrevGone] 在 `prev_head_in_cur` 上**同值**（都是 -1），只有
     * [Geometry::prev_prefix_in_cur] 分得开；而两者的处置正好相反。
     *
     * ⚠ 编号排在最后是刻意的：前四档的值不许动 —— 日志/探活里人眼认的是这些编号。
     */
    kPrevTrimmed = 4,
    /**
     * 断点落在**本轮之内**、两侧内容对得上，而**起点挪了**（`start != start_prev`）。
     * 见文件头 ⑥：这是"每轮全量 prefill"的自锁在读数上的样子。
     */
    kStartShifted = 5,
    /**
     * 本轮 prompt **整段**都是上一轮账本的前缀（`prev_prefix_in_cur == prompt == fork_at`）
     * —— 断点就在本轮末尾，两侧一枚都没被换过。
     *
     * 与 [kPrevTrimmed] 的差别只有一个方向：⑤ 是"上一轮更长、本轮是它的开头一截"，
     * 这一档是"本轮更短"，而**两者的断点位置一样**（`fork_at == 本轮长度`），
     * 于是 `prevCovered` 的后半截（`prev_head_span` 盖到 `prev_len - fork_at`）
     * 对这一档恒假 —— 上一轮断点之后那一截**本轮里根本没有**，比不了。
     * 以前它落进 [kUnknown]（「这一档才轮到复用判据本身」），而真相是本轮该复用
     * `total - 1` 枚、且它早就复用了（ISSUE #503 真机第一屏 `reuse 0` 那一轮）。
     */
    kCurInsidePrev = 6,
};

/**
 * 断点两侧的几何读数。
 *
 * 全部是**下标**，没有一个是"推断出来的成因"：
 *   · `fork_at`            断点在本轮 prompt 里的下标（-1 = 没有分叉）；
 *   · `extra_first/len`    本轮 prompt 里夹带内容的区间（first<0 = 本轮没夹带）；
 *   · `extra_first_prev`   同一段内容在**上一轮** prompt 里的起点（<0 = 上一轮没有）；
 *   · `prev_head_in_cur`   上一轮 prompt 的**开头这一段**在本轮 prompt 里的首次出现下标
 *                          （<0 = 一处都找不到）；
 *   · `head_cmp`           上一轮账本开头那一段在本轮**尝试了几次**（0 = 没存，没法比）。
 *                          ⚠ 它是**步数**，不是"比过多少内容"：上一轮开头整段还在本轮里时，
 *                          第一次尝试就命中、循环当场 break，于是它等于**首次出现的位置**。
 *                          拿它当覆盖范围会把"整段都比过"读成"只比了一小截"（ISSUE #503 第二轮）；
 *   · `prev_head_span`     真正比过的枚数（= 那一次比对盖住了上一轮账本开头多少枚）；
 *   · `prev_len`           上一轮账本**整段**多长（prompt 段 + 生成段）；
 *   · `prev_prefix_in_cur` 上一轮的整段开头在本轮逐枚相同几枚（-1 = 没法量）；
 *   · `start` / `start_prev` 本轮与上一轮**从第几枚起喂**（位置，同一坐标系）。
 *     ⚠ 后面这几格不是修饰：`prev_head_in_cur` 在"本轮是上一轮的前缀"时会报 -1
 *     （那 ≤512 枚在本轮放不下），光看它会判成「换了会话」—— 方向正好相反。
 */
struct Geometry {
    int fork_at = -1;
    int extra_first = -1;
    int extra_len = 0;
    int extra_first_prev = -1;
    int prev_head_in_cur = -1;
    int head_cmp = 0;
    /** 上一轮账户本**整段**有多少枚（0 = 没有账本）。 */
    int prev_len = 0;
    /**
     * `prev_head_in_cur >= 0` 时，那一次比对**真的盖住**上一轮账本开头多少枚（0 = 没比）。
     *
     * 它治的是本文件第三次栽在同一个坑上：`prev_head_in_cur` 是"找得到几次"的**步数**，
     * 不是"比过多少内容"。上一轮开头在本轮**整段都在**时，那段指纹（`kLedgerHead` 枚）
     * 一放就命中、循环当场 `break` —— `head_cmp` 于是等于**首次出现的位置**（真机：473），
     * 而不是"比过了 473 枚"。拿它当覆盖范围，就会把"整段都比过"说成"只比了前 473 枚"，
     * 结论正好反过来（真机第二屏就是这么被读成"换了会话"的）。
     * 覆盖范围只有一个来源：那一次比对真的比了多少枚。
     */
    int prev_head_span = 0;
    /**
     * 本轮从第几枚起喂、上一轮从第几枚起喂（`start != start_prev` = 起点挪了）。
     * 两格都是**位置**，同一坐标系（整段 prompt 的下标）。
     */
    int start = 0;
    int start_prev = 0;
    /**
     * 上一轮那**整本**账在本轮 prompt 里还在不在（从第 0 枚起逐枚相同几枚；-1 = 没法量）。
     *
     * 这一格治的是 `prev_head_in_cur` 的**假阴性**：后者找的是"上一轮开头那 ≤512 枚
     * 在本轮的首次出现"，一旦本轮 prompt 就是上一轮账本的**前缀**（丢过历史之后的常态），
     * 那 512 枚在本轮一枚都不完整存在 —— 它报 -1，于是 [where] 判成 `kPrevGone`，
     * 报告里出现「两轮喂进去的不是同一份对话（换了会话 / 换了 system / 模板每轮在变）」。
     * 而真相恰恰相反：本轮整段都在上一轮里，是**我们自己**把开头扔了。
     * 两者的处置完全相反（一个去查客户端回传的历史，一个查我们自己的裁剪）。
     */
    int prev_prefix_in_cur = -1;
};

/**
 * 断点那一枚在**两侧**分别是什么，以及「上一轮那一段在本轮还找得到几枚」。
 *
 * 它比 [Geometry] 更贴地一层：`Geometry` 说的是"断点落在哪个区间"，
 * 这里说的是"断点上那两枚 token 的 id 是什么、上一轮被跳过的这段在下游还在不在"。
 *
 * 三格之外还有一个用途：`lcp` 两侧开头逐枚相同、却停在 301 时，光看长度
 * 分辨不出「内容变了」与「这一轮少喂了一段」—— 而这两者的处置完全相反
 * （前者查客户端回传的历史，后者查我们自己的裁剪）。[elided_in_cur] 就是
 * 给这件事一个能被数据直接回答的落点。
 */
struct ForkPeek {
    /** 断点那一枚在**上一轮账本**里的 token id（-1 = 那个位置没有内容）。 */
    int prev_tok = -1;
    /** 同一位置在**本轮 prompt** 里的 token id（-1 = 本轮更短，没有这一枚）。 */
    int cur_tok = -1;
    /**
     * 上一轮账本里 `[0, fork)` 这段，本轮 prompt 里**还找得到几枚**（-1 = 没法量）。
     *
     * 只在两侧开头真的逐枚相同（也就是"那一段本来该一模一样"）时量：
     *   · 等于 `fork` —— 那一段在本轮照旧在，断点在它之后（查断点之后的内容）；
     *   · 小于 `fork`（含 0）—— 上一轮那段在本轮**被换掉了**，这就是"少喂了一段"。
     *
     * ⚠ 只量、不判：这三格**不参与任何**复用判据（读数不许反过来改 KV 怎么切）。
     */
    int elided_in_cur = -1;
};

/**
 * 量出 [ForkPeek] 的三格（纯读，O(fork)）。
 *
 * @param prev     上一轮账本（`Session::last_tokens`，prompt 段 + 生成段）。
 * @param prev_valid 账户本当前有效。无效时整段返回 -1（`prev` 是一段**已经不存在**
 *                 的历史，量出来的 id 只会把读的人指去查一个不存在的输入）。
 * @param fork     断点（`plan.lcp`；<0 = 没有分叉）。
 * @param cur      本轮 prompt 的 token 序列。
 */
inline ForkPeek fork_peek(const std::vector<long long> & prev, bool prev_valid,
                          int fork, const std::vector<long long> & cur) {
    ForkPeek k;
    if (!prev_valid || fork < 0) return k;
    if ((size_t) fork < prev.size()) k.prev_tok = (int) prev[(size_t) fork];
    if ((size_t) fork < cur.size())  k.cur_tok  = (int) cur[(size_t) fork];
    // 只有"两侧开头本该一模一样"（即 fork 之前的那些枚逐枚相等）时，"上一轮那段
    // 在本轮还找得到几枚"才有意义 —— 否则量出来的是"两段不同的内容恰好有共同子串"。
    // 判据就取最短的那一侧：`fork` 之内任一枚不同，这一格就保持 -1（不量）。
    const size_t n = std::min(prev.size(), cur.size());
    size_t i = 0;
    while (i < (size_t) fork && i < n && prev[i] == cur[i]) i++;
    if (fork == 0) { k.elided_in_cur = 0; return k; }
    if (i < (size_t) fork) return k;
    k.elided_in_cur = fork;
    return k;
}

/**
 * `cur` 的开头在 `prev` 里**每一处**出现的位置（升序）。
 *
 * 与 [find_cur_head_in_prev]（只给第一处）配套用：`plan_reuse` 的 ⑦ 那一档会
 * 从第一处起逐枚复核，复核不过就**一段都不跳** —— 而"第一处恰好是对位置的"
 * 那种场面（历史里本来就有一模一样的开头）会因此白丢一次复用。
 * 所以候选要**全给出来**，由调用方按顺序复核（见 `kv_prefix::plan_reuse`）。
 *
 * 为什么用「首枚 + 末枚」筛：只比首枚时，历史里同首枚的位置可能有几十处，
 * 每一处都要逐枚复核一遍（O(n·m)）；加上末枚这一道，候选通常落到个位数。
 * 这不是判据，只是剪枝 —— 真正的判据仍然是逐枚复核。
 */
inline std::vector<size_t> find_cur_head_in_prev_all(const std::vector<long long> & prev,
                                                     const std::vector<long long> & cur) {
    std::vector<size_t> out;
    if (cur.size() < 2 || prev.size() < 2) return out;
    // 「头两枚 + 末枚」三枚一起筛：头一枚单独筛太松（真实历史里同首枚的位置几十处，
    // 每处都要逐枚复核一遍），而末枚那一道在 `cur` 比 `prev` 还长时压根不生效。
    // ⚠ 这三枚必须**取出来存成值**，不许在循环里直接拿 `cur[...]` 比：
    //   `prev` 与 `cur` 都可能是**长向量**，而 `std::vector::operator[]` 走的是
    //   引用路径；一旦有一处写错成 `cur` 自己的下标，循环里就是自比、
    //   候选表恒空（这一档失效时**不报错**，只是复用回到门槛以下）。
    const size_t cn = cur.size();
    const long long f0 = (long long) cur[0];
    const long long f1 = (long long) cur[1];
    const long long last = (long long) cur[cn - 1];
    // ⚠ 扫的位置必须让 `cur` 的**整段**落在 `prev` 之内：这一段是要被整段平移过去
    //   复用的，末枚越过 `prev` 的候选连比都比不了（真机账本通常比下一条 prompt
    //   短一截，所以这一条不是理论上的边界）。
    //
    // ⚠ 从 0 起扫：`at == 0` 是最常见的一档（"本轮这一整段就在上一轮最前面"），
    //   而"不含 0"只是**调用方**的取舍（起点 0 不需要平移），不是这里该丢的候选。
    for (size_t at = 0; at < prev.size(); at++) {
        if (at + cn > prev.size()) break;               // 整段放不下：这一处不用比
        if (prev[at] != f0 || prev[at + 1] != f1) continue;
        if (prev[at + cn - 1] != last) continue;
        out.push_back(at);
    }
    return out;
}

/**
 * 「上一轮的对话被挪到了后面」这一档里，**偏移小到能救**的那条线。
 *
 * 与 `kvprefix::kMinReuseTokens`（复用门槛 16）同值，但**不引用它**：本头是
 * `kv_prefix.h` 的依赖（`kv_prefix.h` include 本文件），反过来 include 就是循环。
 * 两处各自是一个编译期常量，改动时要成对改 —— 守卫 `tools/run_kv_continue_guard.sh`
 * 钉住"两者相等"，否则日志会把"能救的场次"指成故障（或反过来）。
 */
const int kPrevMovedAlignable = 16;

/**
 * 夹带内容是否**能解释**断点。
 *
 * 判据：断点必须落在 `[extra_first, extra_first + extra_len)` 之内（含头部一枚）。
 * `extra_first < 0`（本轮没夹带）或 `extra_len <= 0` 一律不算 —— 这一档的意义
 * 全在"断点**正好**压在夹带内容的头一枚上"，放宽成"附近"就等于什么都没说。
 */
inline bool breaksAtExtra(const Geometry & g) {
    if (g.fork_at < 0 || g.extra_first < 0 || g.extra_len <= 0) return false;
    return g.fork_at >= g.extra_first && g.fork_at < g.extra_first + g.extra_len;
}

/**
 * 上一轮账户本的**整段**在本轮 prompt 里逐枚相同几枚（O(min(两侧))，纯读）。
 *
 * 与 [find_cur_head_in_prev_all] 方向相反：那个问"本轮的开头在上一轮哪里"，
 * 这个问"上一轮的开头在本轮还剩几枚"。两者都不能替代对方 ——
 * 丢过历史之后，本轮就是上一轮的**前缀**，那时上一轮的整段开头在本轮一枚也放不下，
 * 而本轮整段都在上一轮里。判据只有这一个数回答得了。
 *
 * @param prev      上一轮账本（prompt 段 + 生成段）
 * @param prev_valid 账本当前有效；无效时返回 -1（拿一段不存在的历史去比，得出的
 *                  "还在"与"不在"都是假的）
 * @param cur       本轮 prompt 的 token 序列
 */
inline int prev_prefix_in_cur(const std::vector<long long> & prev, bool prev_valid,
                              const std::vector<long long> & cur) {
    if (!prev_valid || prev.empty() || cur.empty()) return -1;
    const size_t n = std::min(prev.size(), cur.size());
    size_t i = 0;
    while (i < n && prev[i] == cur[i]) i++;
    return (int) i;
}

/**
 * 本轮**整段都逐枚相同地躺在上一轮账本里**（= 变的是我们喂进去的起点，不是内容）。
 *
 * 两半合起来才是一个事实，少一半就会被反着读：
 *
 *   · **前半截**（第 0 枚到断点）—— `prev_prefix_in_cur >= min(fork_at, prev_len)`。
 *     量法是 `prev_prefix_in_cur` 那个整段逐枚比，所以这一格是"比到哪儿停"，
 *     **不比 `head_cmp`**：后者是"上一轮开头那 ≤512 枚在本轮找得到几次"的步数。
 *   · **后半截**（断点之后那一截，本轮没喂的那一段）—— 只认
 *     `prev_head_span`（那一次比对真的盖住上一轮账本开头多少枚）：
 *     覆盖到 `prev_len - fork_at` 才算比过。`prev_head_span == 0` 是"上一轮
 *     没存开头"，那时后半截**无从比** —— 于是只剩前半截可依，另一半不许当成比过。
 *
 * ⚠ 这一对本文件第三次栽在同一个坑上（`head_cmp >= lcp` → ISSUE #452、
 *   `head_cmp >= prev_prefix` → ISSUE #503 第一版）：**覆盖范围**与**长度/位置**
 *   是两件事。真机两屏 `head_cmp 200` / `head_cmp 473` 都是**正常的**（上一轮开头
 *   整段还在时，指纹第一次尝试就命中、当场 break），却被当成"只比了一小截"。
 */
inline bool prevCovered(const Geometry & g) {
    // 一枚都没比出来（-1 没法量 / 0 开头就不同）：不是这一档。
    if (g.prev_prefix_in_cur <= 0) return false;
    // 没有断点（-1 = 整段都在前缀里；0 = 开头第一枚就不同）—— 后者的处置是"内容变了"，
    // 不是"我们扔了开头"，所以这一档要一个**落在本轮之内**的断点才谈得上。
    if (g.fork_at <= 0) return false;
    // 账本不比本轮短的那一份不可能"装着"本轮——那一档只说明开头一段恰好一样。
    if (g.prev_len < g.prev_prefix_in_cur) return false;
    // 前半截：本轮前 `fork_at` 枚（封顶到上一轮账本长度）与上一轮逐枚相同，得比过。
    const int head_need = std::min(g.fork_at, g.prev_len);
    if (head_need > 0 && g.prev_prefix_in_cur < head_need) return false;
    // 后半截：断点之后那一截也得比过（真比过的枚数 = `prev_head_span`，不是 `head_cmp`）。
    const int tail_need = g.prev_len - g.fork_at;
    if (tail_need > 0 && g.prev_head_span < tail_need) return false;
    return true;
}

/**
 * 「起点挪了」那一档（文件头 ⑥）：两侧内容都对得上，唯一的差别是**从第几枚喂起**。
 *
 * 三条都要有，缺一条就是别的成因：
 *   · 断点落在本轮之内（`0 < fork_at < ` 本轮比完的那一整段）—— 断点顶在本轮末尾
 *     是"本轮更短"，归 ⑤；
 *   · 前半截逐枚相同（`prev_prefix_in_cur >= fork_at`）—— 内容没变；
 *   · 两个起点必须**不相等**（`start != start_prev`）。`start_prev < 0` 表示"上一轮
 *     没跑过"，那也是不等 —— 但那种场次没有"上一轮的起点"可比，只能报"这一轮从
 *     第几枚起喂"这半句，所以 [describe] 里会分开说。
 */
inline bool prevStartShifted(const Geometry & g) {
    if (g.fork_at <= 0) return false;
    if (g.prev_prefix_in_cur < g.fork_at) return false;
    if (g.start == g.start_prev) return false;
    // 整段都被装下了（[prevCovered] 成立）时"起点不等"只是还没对齐，接得上就是接得上 ——
    // 那一档归 ⑤，这里的处置（查预算为什么多丢了几枚）对它不成立。
    return !prevCovered(g);
}

/**
 * 本轮 prompt **整段**都在上一轮账本里，且断点正压在本轮末尾（文件头 ⑦）。
 *
 * 判据三条，缺一条就是别的成因：
 *   · `prev_prefix_in_cur == fork_at` 且 `fork_at >= kPrevMovedAlignable`（复用门槛同值）：
 *     从第 0 枚起
 *     逐枚相同的那一截**就是**本轮喂进去的整段（不是"只比过一截"）；
 *   · `prev_len >= fork_at`：上一轮账本**至少**装着这么长；
 *   · `start` 与 `start_prev` 相等（或 `start_prev < 0` = 上一轮没跑过）：起点没挪。
 *     起点挪了是 ⑥ 的事，那一档的处置是"查预算为什么多丢了几枚"。
 *
 * ⚠ 这一档**不改任何判据**，只把"这一轮本该复用 `total-1` 枚"说出口：
 *   真机上 `prev_prefix_in_cur == prompt_real` 而 `reuse == 0` 的那一屏，
 *   此前落进 [kUnknown] —— 「两侧都是本会话自己的内容、开头也同形 ——
 *   这一档才轮到复用判据本身」，读起来像"判据坏了"，而真相是 prompt 比门槛短。
 */
inline bool curInsidePrev(const Geometry & g) {
    if (g.fork_at <= 0) return false;
    if (g.fork_at < kPrevMovedAlignable) return false;      // 太短：门槛本来就是净亏
    if (g.prev_prefix_in_cur != g.fork_at) return false;    // 只比过一截 / 断点在中间
    if (g.prev_len < g.fork_at) return false;               // 账本装不下本轮
    return g.start_prev < 0 || g.start == g.start_prev;     // 起点没挪（挪了归 ⑥）
}

/**
 * 断点两侧的差异**是什么**。
 *
 * 顺序即优先级：能落到具体区间的一档优先，剩下的才归「都解释不了」。
 * `kPrevMoved` 要求 `prev_head_in_cur > 0`（**严格大于** 0）：等于 0 时说明
 * 上一轮那段前缀还在最前面 —— 也就是说，它和本轮一致，断点在别处，
 * 轮不到「历史被挪了」这一档来解释。
 */
inline DiffCause where(const Geometry & g) {
    if (breaksAtExtra(g)) return kExtra;
    if (g.prev_head_in_cur > 0) return kPrevMoved;
    if (prevCovered(g)) return kPrevTrimmed;
    if (prevStartShifted(g)) return kStartShifted;
    if (curInsidePrev(g)) return kCurInsidePrev;
    if (g.prev_head_in_cur < 0 && g.head_cmp > 0) return kPrevGone;
    return kUnknown;
}

/**
 * 一句话（日志与 /health 共用，判据本身不依赖字符串）。
 *
 * 每一档都说**两侧各是什么**，而不是只说"对不上"—— 后者与"缓存坏了"逐字同形，
 * 读的人只能回去猜。
 */
inline std::string describe(const Geometry & g) {
    const std::string at = "第 " + std::to_string(g.fork_at) + " tok";
    switch (where(g)) {
        case kExtra: {
            const int head = g.extra_first;
            std::string s = at + " 起分叉，断点落在本轮 prompt 的**夹带段**里（token [" +
                            std::to_string(g.extra_first) + ", " +
                            std::to_string(g.extra_first + g.extra_len) + ")），" +
                            std::to_string(g.extra_len) + " tok 那段是每轮新生成的字节";
            if (g.extra_first_prev < 0) {
                s += "；上一轮这一段定位不出来（长度或内容变了），所以前缀从这里断开";
            } else if (g.extra_first_prev != head) {
                s += "；上一轮它落在 token " + std::to_string(g.extra_first_prev) +
                     "，位置每轮在变 —— 位置一变，后面整段历史都对不上";
            } else {
                s += "；上一轮它也在同一位置（token " + std::to_string(head) +
                     "），断的是这段内容本身：同一条问答命中了不同的条目，或条目正文变了";
            }
            // ⚠ 收窄过（ISSUE #503 真机）：原来这里断言"要改的是这段内容的生成方式"，
            //   而"断点压在夹带段头一枚"还盖着另一种情形 —— 夹带段**每轮一模一样**、
            //   变的是我们自己的起点（水位线那轮把开头扔了，夹带段连着被扔的尾巴一起
            //   被挪到了新起点）。那时改生成方式一辈子也改不出效果。
            //   所以只钉住"这一刀落在这一截"，成因继续由 [where] 的子档与
            //   `prev_prefix_in_cur` 说 —— 一句话说死一件事，别替用户挑修法。
            s += "。调 ctx / parallelN / max_tokens 都不会让它变好；"
                 "这一截是每轮新拼的，还是我们自己的起点挪到了它头上 —— 看 "
                 "`prev_head_span` 与 `prev_prefix_in_cur` 这两格再决定改哪一侧";
            return s;
        }
        case kPrevMoved: {
            std::string s = at + " 起分叉，而上一轮 prompt 的开头这一段在本轮的第 " +
                   std::to_string(g.prev_head_in_cur) + " 枚才出现 —— **上一轮的对话被挪到了后面**"
                   "（本轮最前面多出了别的内容）";
            // 偏移在门槛之内时**是能救的**：把起点跳过这一小段、KV 平移回 pos 0 即可
            // （`plan_reuse` 的 ⑦ 那一档，位置来自 `find_cur_head_in_prev` 的逐枚复核）。
            // 只说"查对端"会把一条已经会自愈的路指成故障 —— 真机上知识库注入块
            // 就在最前面十几枚，偏移量恒 ≤ 门槛。
            if (g.prev_head_in_cur <= kPrevMovedAlignable) {
                s += "；偏移只有 " + std::to_string(g.prev_head_in_cur) +
                     " tok，在复用门槛之内 —— 这一档本该跳过它做平移复用，"
                     "读到这句说明那一步没成立（把这几格贴回来）";
            } else {
                s += "；偏移 " + std::to_string(g.prev_head_in_cur) +
                     " tok，超出复用门槛 —— 平移的代价大于收益，不会走「跳过起点」那一档。"
                     "这不是服务端砍的，查对端回传的历史";
            }
            return s;
        }
        case kPrevTrimmed: {
            std::string s = at + " 起分叉，而上一轮那整段在本轮从第 0 枚起逐枚相同";
            if (g.prev_prefix_in_cur >= 0 && g.prev_len > 0) {
                s += "（比到 " + std::to_string(g.prev_prefix_in_cur) + " / 上一轮账本 " +
                     std::to_string(g.prev_len) + " tok）";
            }
            s += " —— 本轮就是上一轮的**开头一截**，一枚都没被换掉：是我们自己把开头扔了，"
                 "断点不在内容上、在**起点**上（水位线 / 上下文预算那一刀，或起点后移）。"
                 "查 `prompt_trimmed` / `start` / `start_prev` / `spare` / `drop_room`，"
                 "调客户端参数没用";
            return s;
        }
        case kStartShifted: {
            std::string s = at + " 起分叉，两侧内容在断点之前逐枚相同（" +
                std::to_string(g.prev_prefix_in_cur) + " tok）—— 对不上的**不是内容，是起点**："
                "本轮从第 " + std::to_string(g.start) + " 枚起喂";
            if (g.start_prev < 0) {
                s += "，上一轮还没跑过（这一轮就是基线）";
            } else {
                s += "，上一轮从第 " + std::to_string(g.start_prev) +
                     " 枚起喂 —— 每轮都往后挪一截的话，KV 里攒下的那一段就永远接不上，"
                     "于是每轮全量 prefill";
            }
            s += "。查 `prompt_trimmed` / `drop_room` / `spare`，以及预算这一轮为什么多丢了几枚"
                 "（`budget.prompt` 比上一轮留下的那一段短一点点就会走到这里）";
            return s;
        }
        case kCurInsidePrev: {
            std::string s = at + " 起断点，而本轮喂进去的整段（" + std::to_string(g.fork_at) +
                " tok）**就是**上一轮账本的前缀 —— 断点在本轮末尾，两侧一枚都没被换过";
            s += "（上一轮账本 " + std::to_string(g.prev_len) + " tok）。这一档该复用 " +
                 std::to_string(g.fork_at > 0 ? g.fork_at - 1 : 0) +
                 " tok（末枚留给自己 decode）；读到 `reuse 0` 说明拦它的是门槛 / 容量 / "
                 "账本有效位，不是前缀对不上 —— 本轮 prompt 短于 " +
                 std::to_string(kPrevMovedAlignable) + " tok 时全量 prefill 本来就是净亏";
            return s;
        }
        case kPrevGone:
            return at + " 起分叉，且上一轮 prompt 的开头这一段本轮**一处都找不到** —— "
                   "两轮喂进去的不是同一份对话（换了会话 / 换了 system / 模板每轮在变）";
        default:
            return at + " 起分叉，两侧都是本会话自己的内容、开头也同形 —— "
                   "这一档才轮到复用判据本身（把这几格贴回来）";
    }
}

} // namespace kvwhere

#endif // LOCAL_LLM_KV_DIFF_WHERE_H
