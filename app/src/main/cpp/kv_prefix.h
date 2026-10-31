// kv_prefix.h — KV 前缀复用（prompt cache）的**切分语义**（纯函数，无 JNI / llama 依赖）
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么把这段单独拆一个头文件
// ═══════════════════════════════════════════════════════════════════════════
// 这段逻辑决定「上一轮的 KV 里哪一截能留给这一轮用」，而它的失效模式是
// **没有异常、没有日志、也没有崩溃**的那一类：
//   · 少留一段 -> 只是慢（效果消失，谁都看不出来"本该更快"）；
//   · 多留一段（切在 token 中间 / 位置对不上）-> 模型读到错位的历史，
//     表现为「答非所问」「重复上一轮的结尾」—— 但接口 200、不报错，
//     排查时会被归因到"模型本来就这德行"。
// 所以切分必须是**纯函数**、必须能被宿主侧用真字节钉住，而不是靠 review。
// 拆出来之后 tools/kv_prefix_test.cpp 直接编译**同一份源码**跑断言，
// 不用 NDK、不用设备、不用模型（与 stop_sequences.h / probe_util.h 同一套路）。
//
// 本文件只做两件事，都不碰 llama API：
//   1. 给定「上轮的整段 token 序列」与「本轮 token 序列」，算出**最长公共前缀**长度；
//   2. 把"复用这个前缀"翻译成一个**可判定**的决策（复用 N 个 token / 不复用）。
// 真正去动 KV 的调用（llama_memory_seq_rm / seq_cp）留在 llama_jni.cpp，
// 因为它与 Session 的耦合是真实的，测不了也没必要测。
#ifndef LOCAL_LLM_KV_PREFIX_H
#define LOCAL_LLM_KV_PREFIX_H

#include <algorithm>
#include <cstddef>
#include <string>

#include "kv_diff_where.h"
#include <vector>

namespace kvprefix {

/** 一次复用的决策结果；调用方按 [reuse] 决定"删到哪 / 从哪继续 prefill"。 */
struct Plan {
    /**
     * 可复用的 token 数（KV 里前 reuse 个 token 的位置仍然正确）。
     * 0 = 不复用（与引入本功能前的行为逐字节相同：全清 + 全量 prefill）。
     */
    int reuse = 0;
    /** 本轮仍需 prefill 的 token 数 = total - reuse。 */
    int to_decode = 0;
    /** 本轮整段 prompt 的 token 数。 */
    int total = 0;
    /**
     * 本轮与上一轮的**逐 token 最长公共前缀**长度（**不设门槛**，也不需要命中）。
     *
     * 为什么要单独留这个数（第 5 步，用户拍板要的可观测性）：
     * 「没命中」是个**笼统结论**，它至少有两种成因，而处置完全相反：
     *
     *   · `lcp` 很大（比如 300）却仍没命中 -> 只可能是**门槛或容量**拦下的
     *     （`total >= n_ctx`，或 `total - 1 < kMinReuseTokens`）——
     *     属于"差一点就命中了"，值得查为什么会这样；
     *   · `lcp` 很小（比如 2）-> 两轮 prompt **真的不一样**（例如开了知识库、
     *     检索结果每轮都变，分叉点就在最前面的 system 段里）——
     *     这是设计使然，调参也没用，要改的是注入位置。
     *
     * 只报 `reuse=0` 时这两种在日志上**逐字相同**，用户只能猜。
     * 它是 `plan_reuse` 的**副产品**（本来就要算公共前缀），零额外代价。
     */
    int lcp = 0;
    /**
     * 公共前缀**断在第几号 token**（= `lcp`）。日志与 /health 用。
     *
     * 为什么 `lcp` 之外还要单独一个字段：`lcp=152` 只说"断了多长"，
     * 说不出"断在哪一截上" —— 而两种成因的处置完全相反：
     *   · 落在上一轮 prompt 的尾部 = 上一轮那段回复没被带回来（续写差异，数学）；
     *   · 落在 system 段中间     = 有东西每轮都在变（注入位置 / 客户端裁历史，是 bug）。
     * 写成与 `lcp` 同值的命名读数，是为了让日志与 /health 各自表述同一件事时
     * 不必一个说"分叉点"一个说"公共前缀"，把读者绕进去。
     */
    int fork_at = -1;
    /**
     * 分叉那一枚**在上一轮账本里的下标**（-1 = 无分叉）。
     *
     * 为什么 fork_at 还不够：它只是同一个下标，可两侧的"同一位置"并不是同一个东西 ——
     * 上一轮的账本是「prompt 段 + 生成段」，本轮的 prompt 只到"本轮提问"为止。
     * 想判断"断点落在上一轮的回复段，还是落在 prompt 段中间"，必须知道**上一轮的
     * prompt 段有多长**，而 11 格读数只有调用方存得下来（它自己 assign 账本）。
     * 所以把它一并落进 `Session` 的账本快照，下一轮才比得出落点。
     */
    int fork_prev = -1;
    /**
     * 上一轮账本**开头一小段**里逐 token 相同的枚数（0 = 调用方没给，不参与判定）。
     *
     * 它答的是另一件事：`lcp` 说"公共前缀多长"，它说"**开头**那一截是不是一模一样"。
     * 两者在「我们（或对端）从头部砍过一刀」的场面上完全相反 —— `lcp` 只有一两枚
     * （位置全错开了），而开头那段其实逐 token 相同（内容没变，只是起点变了）。
     * `plan_reuse` 的约束 ⑦ 就是照它判的：**开头变了才叫"对不上"，起点变了不算**。
     */
    int head_lcp = 0;
    /**
     * 起点被跳过多少枚（0 = 从 pos 0 对齐，与今天逐字节一致）。
     *
     * 只有约束 ⑦ 那一档会 > 0：KV 里 `[0, total - skip)` 仍然是被记住的内容，
     * 但它对应本轮的 `[skip, total)` —— 调用方必须按新起点重排位置。
     * 它同时也是"这一轮**为什么**起点不在 0"的唯一原始依据（日志与 /health 共用）。
     */
    int skip = 0;
    /**
     * `fork_at` 的**种类**（ISSUE #503 真机第一屏）：`0` = 内容在中间断掉（常态），
     * `1` = 本轮整段是上一轮账本的前缀（断点就在本轮末尾，不是"内容变了"）。
     *
     * 两件事在读数上曾经同值（`fork_at == total` / `-1`），而处置完全相反：
     * 前者要去查模板与注入，后者**本来就该命中**（`reuse = min(lcp, total-1)`）。
     * 少了这一格，`lcp == total` 那一档的日志会说「本轮整串都在上一轮前缀里，
     * 没命中不是前缀对不上」—— 与同一个 `Plan` 的 `reuse` 值直接打脸。
     */
    int fork_at_kind = 0;
    static const int kForkAtEnd = 1;
    /** 上一轮账本的 token 数（`prev_valid=false` 时为 0）。两侧长度并排才读得出"断在哪一截"。 */
    int prev_len = 0;
    /** 是否命中缓存（reuse > 0）。日志与 /health 用。 */
    /** 命中 = 至少有一段已缓存内容可以直接用（含 ⑦ 那一档：跳过起点后整段可用）。 */
    bool hit() const { return reuse > 0 || skip > 0; }
};

/**
 * 最长公共前缀长度（逐 token 比较，按 `llama_token` 的数值）。
 *
 * 为什么是**逐 token**而不是逐字节：KV 的粒度就是一个 token 一个位置。
 * 按字节算出一个能整除的"公共前缀"再切回 token，看着更"精确"，
 * 实际上是把判据建立在"tokenize 是可逆的"这个不成立的前提上
 * （BPE 的合并是贪心的、有上下文的：同一个前缀串在更长输入里可能被切成不同的 token）。
 * 逐 token 比是最保守、也是唯一不需要额外假设的判据。
 *
 * 用 `std::vector<long long>` 而不是 `std::vector<llama_token>` 是刻意的：
 * 本头文件必须能在宿主上编（那里没有 llama.h），而 llama_token 就是 int32。
 */
inline int common_prefix_len(const std::vector<long long> & a,
                             const std::vector<long long> & b) {
    const size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) i++;
    return (int) i;
}

/**
 * 本轮 prompt 的**开头这段**在上一轮账本里第一次出现的位置（-1 = 一处都找不到）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要它（这是「跳过起点复用」唯一的判据）
 * ══════════════════════════════════════════════════════════════════════════
 * `common_prefix_len` 回答的是「两串**从 0 开始**有多少枚相同」，而"跳过起点"要回答的
 * 是另一件事：「本轮这段内容在上一轮的**哪个位置**」。两者只在 `lcp > 0` 时都非空，
 * 数值却毫无关系 —— 拿前者当后者用，就是把一段内容对不上的 KV 平移上来
 *（不崩、不报错、只是答非所问，见 `plan_reuse` 的 ⑦）。
 *
 * ⚠ 这里**只找锚**（本轮头一枚在上一轮出现的位置），整段是否真的对得上由
 *   `plan_reuse` 的闸① 逐枚复核 —— 两处都全比一遍是白花 O(n·m)，而只信锚又不够
 *   （同一个 token id 在别处出现太常见了）。分工是"这里找位置、那里验内容"。
 *
 * `at` > 0 才有意义：等于 0 表示本轮**以**上一轮开头 —— 那是 ② 的旧路径。
 */
inline int find_cur_head_in_prev(const std::vector<long long> & prev,
                                 const std::vector<long long> & cur,
                                 size_t from = 1) {
    if (cur.empty() || prev.empty()) return -1;
    const long long n = (long long) cur.size();
    if (n <= (long long) from) return -1;
    const long long first = cur.front();
    const long long last = (long long) prev.size() - 1;
    for (long long at = (long long) from; at <= last; at++) {
        if (prev[(size_t) at] == first) return (int) at;
    }
    return -1;
}

/**
 * 复用门槛：公共前缀至少要有这么长才值得复用。
 *
 * 为什么不是"有 1 个 token 就复用"：复用的净收益是 `省下的 prefill - KV 操作的固定开销`，
 * 而 KV 操作要 `llama_memory_seq_rm` 掉不匹配的尾巴（有真实开销，尤其 HTP/OpenCL 上
 * KV 在设备内存里）。1~2 个 token 的"复用"是净亏，而且会给日志刷上一堆
 * `reuse=2` 之类的假命中，把真正有价值的命中淹掉。
 *
 * 取 16 是保守值：单轮工具调用重发的 system+tools 段通常是数百 token 起，
 * 16 这个门槛只会滤掉"本来就没什么可省"的短对话。
 */
const int kMinReuseTokens = 16;

// ⚠ 这里原先还有一组 `kLcpReason*` 原因码（无分叉 / 我们丢的 / 对端丢的 / 差一点），
//   已删（ISSUE #330 去重）：四档里两档与已有读数**同真同假**
//   （`DroppedByUs` ⟺ `prompt_trimmed > 0`、`DroppedByPeer` ⟺ 未命中），
//   由它派生的 `dropped_by_us` 正是用户点名"这个不重复吗"的那一格。
//   同真同假的读数摆在一起，读的人一定会挑那个最像结论的 —— 而它在真机上常是错的。
//   `Blocked`（够长却没命中）真正要的东西是"那段账本还在不在"，由 `used` /
//   `ledger_prompt` 直接给出，不必再起一个名字。

/**
 * 算出这一轮的复用计划。
 *
 * @param prev   上一轮**已经进 KV 的整段 token 序列**（含生成出来的那部分）。
 *               上一轮为空（首次请求 / 换模型 / 已释放）时传空 vector 即可。
 * @param cur    本轮 prompt 的 token 序列。
 * @param prev_valid 上一轮的 KV 是否**仍在**且仍然属于同一序列。
 *               凡是"KV 已经被动过"的情况都必须传 false —— 见下面的理由。
 * @param total_ctx KV 总容量（n_ctx）。
 * @param n_used  调用方此刻**认为** KV 里已有多少 token（`Session::n_used`）。
 *                负值 = 不提供该读数，跳过下面的约束 ⑥。
 *
 * 六条保守约束，每一条都对应一种"看起来能复用、实际会读错历史"的真实情形：
 *
 * ① `prev_valid == false` 一律不复用。调用方在**任何**可能改动 KV 的操作
 *    （卸载模型、换模型、Abort 后强制收缩、上一轮因错误提前返回）之后都要传 false。
 *    这里不"聪明地"去猜 KV 还在不在 —— 猜错的代价是模型读到别人的历史。
 *
 * ② 复用的前提是**本轮 prompt 以那段 token 开头**。做不到"中间某段相同就复用中间段"：
 *    KV 是按位置索引的（pos 0..N），跳过前 k 个 token 直接复用中间的，
 *    位置就全错位了，必须配合 seq_add 平移 —— 而平移在本项目的并发形态下
 *    （单序列、生成循环持锁）没有任何收益。宁可少复用，不可错位置。
 *
 * ③ `total >= n_ctx` 时不复用（调用方随后会以"prompt 过长"失败）。
 *    这时若还复用，会把一个**注定失败**的请求的 KV 先改坏，
 *    下一轮的缓存也跟着失效 —— 失败要失败得干净。
 *
 * ⑥（运行时保险，默认不触发）账本里记着 `prev` 这一整串都进过 KV，而 KV 里
 *    实际只剩 `n_used` 个 token —— 两边**自相矛盾**。这说明有一条改动 KV
 *    的路径没走 `kv_invalidate`（`seq_rm` / `seq_add` / `state_seq_set` 都会）。
 *    这时按 `prev` 去复用，等于复用一段**已经不在 KV 里**的历史：位置照样
 *    对得上，槽位却是空的。代价是不报错、HTTP 200、日志还写着"命中"。
 *    所以宁可在这一轮退化成"全量 prefill"（慢一次），也不拿一份对不上的账本
 *    去命中；调用方会随即 `kv_invalidate`，让失效暴露在 /health 上。
 *    ⑥ 为什么是第六条：①~⑤ 都在本函数内闭合，只有它依赖"调用方另一份账本"。
 *
 * @param head_lcp 上一轮账本**开头一小段**里逐 token 相同的枚数（-1 = 调用方没给）。
 *                它开的是约束 ⑦ 那一档：**开头一样、只是里面从第 lcp 枚起分开**
 *                （注入块/时间戳每轮换一句，而后面整段历史没变）时，跳过前 lcp 枚
 *                复用后面的内容。没给（-1）时这一档整段不生效，判据与改动前逐字节一致。
 *
 * ⑦（跳过起点复用）—— 与 ② 不矛盾：② 说的是"不能只命中中间一段"（那需要平移位置），
 *    而这一档命中的是 `cur` 的**整段**，位置从 0 重排即可，`skip` 就是那个偏移量；
 *    过了逐枚复核 `prev[skip ...] == cur[0 ...]` 之后，留下来的每一枚都对应它自己那段内容。
 *
 *    ⚠ 这里原先是**判据的错位**（ISSUE #330 真机复现）：只判 `lcp > 0` 就把
 *      `prev[lcp ...]` 当成 `cur[0 ...]` 用 —— 而 `lcp` 是**公共前缀的长度**，
 *      不是"本轮内容从上一轮哪里开始"。`lcp = 152` 只说明「前 152 枚相同、第 152 枚起
 *      不同」，**不**说明「本轮的头一枚在上一轮的第 152 枚」。于是 `seq_add(-skip)`
 *      把一段**内容对不上**的 KV 平移到 pos 0 上，而 `n_used = reuse` 让每一枚的位置
 *      看着都合法 —— 不崩、不报错、HTTP 200，只是模型读到错位的历史（答非所问）。
 *      现在位置只来自 `find_cur_head_in_prev`，并且必须过逐枚复核；过不了就一段都不跳。
 *      为什么闸② 是"`usable > reuse0`"而不是"`prev.size() > n_used`"：
 *      账本里多出来的**尾巴**（上一轮生成出来、这一轮 prompt 早就分叉了）本来
 *      就不会被复用，用更长的那串判会让纯加速的功能无谓地频繁失效。
 */
inline Plan plan_reuse(const std::vector<long long> & prev,
                       const std::vector<long long> & cur,
                       bool prev_valid,
                       int total_ctx,
                       int n_used = -1,
                       int head_lcp = -1) {
    Plan p;
    p.total = (int) cur.size();
    p.to_decode = p.total;
    // ① 之前就要把 `prev_len` 收口：账本**无效**时上一轮的长度不是一个"读数"，
    //    而是一段已经不存在的历史。带着它出去，日志会把"首次运行"写成
    //    「上轮 400 / 本轮 20」—— 那两个数各自都合法、看起来完全正常，
    //    但它指的是一份根本不存在的账本（与 `kv_valid=false` 的语义直接矛盾）。
    //    组 13 的 ⑥ 就是钉这一条（它在改动前那段代码上一直红着）。
    p.prev_len = prev_valid ? (int) prev.size() : 0;
    if (!prev_valid || prev.empty() || cur.empty()) return p;   // ①
    if (total_ctx > 0 && p.total >= total_ctx) return p;        // ③

    // 「开头有没有变」：`head_lcp >= 0` 表示调用方给了上一轮账本的开头一小段。
    //   `head_lcp < 0` = 没给（冷启动 / 账本作废）—— 那时**不许**拿它放行任何东西，
    //   判据退回下面逐 token 的公共前缀（与改动前逐字节一致）。
    // 判据分两档，因为两者的处置完全相反：
    //   · 开头**真的变了**（head_lcp 很小）-> 这份账本对本轮没用，连 ⑦ 都不许开；
    //   · 开头一样、只是**里面**出现差异（head_lcp 很大）-> 正是 ⑦ 要救的那一档。
    const bool head_changed = (head_lcp >= 0) && (head_lcp < kMinReuseTokens);
    p.head_lcp = head_lcp;
    const int lcp = common_prefix_len(prev, cur);
    p.lcp = lcp;                                                // 可观测面：无论是否命中都记下
    // 分叉点：`common_prefix_len` 的副产品，零额外代价。只在**真的有分叉**时才落值 ——
    // 本轮整串都落在上一轮前缀里（`lcp == total`）不叫分叉，落一个 -1 比落 0 好读：
    // 0 会被读成"第 0 个 token 就断了"。
    //
    // ⚠ 「没有分叉」**不等于**「没有可复用的」（ISSUE #503 真机第一屏）：`lcp == total`
    //   的那一档是"本轮 prompt 是上一轮账本的前缀"——越线丢历史之后每一轮都是它。
    //   以前这里不落 `fork_at`，下游（日志 / `fork_where` / `/health`）就把它读成
    //   「本轮整串都在上一轮前缀里，没命中不是前缀对不上」，而同一屏的 `reuse` 是 0：
    //   两句话各自都成立，合起来把人指去查缓存，而那一轮就是该复用。
    //   所以这一档照样落 `fork_at`（= `total`），由 `fork_at_kind` 说清"这是本轮末尾、
    //   不是内容在中间断掉"。
    p.fork_at = lcp;
    p.fork_prev = lcp;
    if (lcp >= p.total) p.fork_at_kind = Plan::kForkAtEnd;
    // ⑦ 「跳过起点复用」—— 本轮开头有 `v` 枚新内容，从第 `v` 枚起才与上一轮接上，
    //    于是把 KV 里那一段平移到 pos 0 上继续用（`skip` = 平移量，`reuse` = 留下几枚）。
    //
    // 真机现场（ISSUE #330）：公共前缀常年停在 152，而后面那一大段历史其实
    // **一个字都没变**；旧判据要求"本轮以上一轮那段开头"，于是整段白算一遍。
    //
    // ══════════════════════════════════════════════════════════════════════
    // 这一档唯一要钉死的事：`skip` 是**位置**，不是"公共前缀有多长"
    // ══════════════════════════════════════════════════════════════════════
    // `lcp` 回答"两串从 0 起有多少枚相同"，它**不是**"本轮这段在上一轮的第几枚"。
    // 拿 `lcp` 当 `skip` 会把一段内容对不上的 KV 平移到 pos 0 上：不崩、不报错、
    // 接口 200，只是模型读到错位的历史（答非所问）—— 这正是此前那一版的错，
    // 也是本函数里唯一会**静默**伤害回答质量的地方。
    //
    // 两道闸，缺一不可：
    //   · 闸① 逐枚复核：`prev[v ...]` 与 `cur[0 ...]` 必须真的相同（比到两侧先耗尽）。
    //     **没比过的内容不许当接续** —— 少了这一道，"位置对了、内容是别人的"；
    //   · 闸② 收益：平移后留下的 `usable` 枚要比 ② 给出的 `reuse0` 多出
    //     `kMinReuseTokens` 以上，否则动 KV（`seq_rm` + `seq_add`）净亏。
    //
    // 开关是 `head_lcp >= 0`（调用方比过上一轮账本的开头一段），**不是**
    // `!head_changed`：真机的主要场面是「上一轮开头塞着新内容、本轮把它换掉」，
    // 那时第 0 枚就不同（`head_lcp == 0`），而后面几千枚一字未动 —— 正是最该救的场面。
    if (head_lcp >= 0) {
        // ── 候选位置要**全试**，不能只试第一处 ─────────────────────────────
        //
        // `find_cur_head_in_prev` 只给"本轮首枚在上一轮里第一次出现的位置"，而
        // 历史里本来就常常有别的消息以同一枚 token 开头（真机：知识库注入块让
        // 本轮的开头前十几枚变成上一轮没有的内容，偏移量 ≤ 门槛，而"首枚第一次出现"
        // 恰好落在一段对不上的位置上）。那时第一处复核不过，整档就**一段都不跳**,
        // 于是"偏移只有 16 枚、本该平移复用"的场次退化成全量 prefill ——
        // 而不报错、不崩，只有 `reuse` 归零。
        //
        // 现在按顺序逐个复核，取**第一个过闸**的候选。位置仍然只来自逐枚复核，
        // 判据一个字都没放宽（⑧ 那条"位置不是 lcp"的教训仍然成立）。
        const std::vector<size_t> cands = kvwhere::find_cur_head_in_prev_all(prev, cur);
        // 老的"只试第一处"路径保留在候选表为空时的位置：两者应当同解，
        // 但候选表的剪枝（首枚 + 末枚）可能漏掉它 —— 那时代码退回原来的行为。
        std::vector<size_t> order;
        for (size_t c : cands) order.push_back(c);
        const int first = find_cur_head_in_prev(prev, cur, 1);
        if (first > 0 && std::find(order.begin(), order.end(), (size_t) first) == order.end())
            order.push_back((size_t) first);
        // 闸②的基线：本轮自己就有 `lcp` 枚能直接用（不平移）。
        const int reuse0 = std::max(0, std::min(lcp, p.total - 1));
        for (size_t v : order) {
            // 闸①：从 `v` 起逐枚相同，比到两侧先耗尽的那一侧。
            // ⚠ 比的必须是 `prev`（上一轮账本）与 `cur`（**本轮 prompt**）—— 自比会让
            //   闸门恒真（等于没闸），而它的失效形态正是"位置对得上、内容是别人的"。
            // 不看越界：本轮比上一轮长是常态，"KV 里只留到上一轮末尾"本来就合法。
            bool seg_ok = (v > 0 && (int) v < p.prev_len && (int) v < p.total);
            for (size_t k = v; seg_ok && k < prev.size(); k++)
                if (prev[k] != cur[k - v]) seg_ok = false;
            if (!seg_ok) continue;
            // 闸②：能把 `prev[v ...]` 整段平移到 pos 0（最多到 `total` 枚）。
            const int cover = (int) prev.size() - (int) v;
            const int usable = std::min(p.total - (int) v, cover);
            if (usable >= kMinReuseTokens && usable - reuse0 >= kMinReuseTokens) {
                p.skip = (int) v;
                p.reuse = usable;
                p.to_decode = p.total - usable;
                return p;
            }
        }
    }
    if (lcp < 1) return p;

    // ④ 复用长度绝不允许达到本轮的整段 prompt：
    //    prefill 的最后一个 token 必须**被 decode 一次**，下一次采样才有 logits 可采。
    //    若把整段都当"已缓存的"，采样步会在一个没有任何 logits 的上下文上取值。
    //    （短 prompt 恰好等于上一轮前缀 + 空生成时就会踩到，是本函数最隐蔽的一处。）
    int reuse = std::min(lcp, p.total - 1);
    // ⑤ 位置不能越过 KV 容量（防御性：正常路径下 ③ 已拦住）
    if (total_ctx > 0 && reuse >= total_ctx) reuse = total_ctx > 0 ? total_ctx - 1 : 0;
    if (reuse < kMinReuseTokens) return p;

    // ⑥ 账本自相矛盾（复用长度 > KV 里实有的 token 数）：放弃命中。
    //    保留 `p.lcp`，可观测面仍然说得出"本来能命中多少"。
    if (n_used >= 0 && reuse > n_used) return p;

    p.reuse = reuse;                                            // ②
    p.to_decode = p.total - reuse;
    return p;
}

/**
 * 分叉点落在**哪一截**上（一句话，供日志与 /health 复用）。
 *
 * 三类的处置完全不同，而在只报 `公共前缀 N tok` 时它们**逐字相同**：
 *   · 落在上一轮**回复段**（`fork_prev >= prev_prompt`）—— 上一轮的回复没被带回来，
 *     或对端回传的历史与本轮不是同一份；改任何服务端参数都没用；
 *   · 本轮 prompt 比上一轮 prompt 段**短** —— 客户端裁了历史，或者我们这一格
 *     装不下（`[ctx] 丢最老` 那一行会同时出现）；
 *   · 两侧都还有余量却对不上 —— 系统段/模板在变（服务端侧），或者历史被换掉了。
 *
 * ⚠ 判据必须用 `fork_prev`（分叉那一枚**在上一轮账本里的下标**），不能拿
 *   `fork_at` 去和「本轮长度」推：真机上出现过 上轮 prompt 1371 + 生成 497 /
 *   本轮 prompt 661 / 公共前缀 152 的场次，`152 < 661` 据此被读成
 *   「落在 prompt 段中间 ⇒ 有东西每轮在变」，而真相是上一轮那段历史本轮没被
 *   带回来 —— 方向正好相反，还把人往服务端参数上引。`fork_prev` 才是同一个
 *   坐标系里的那个下标（= `fork_at`，只是语义上明确"它属于上一轮"）。
 *
 * ⚠ 最后一类**不再替用户断言「这才是 bug」**：两侧都有余量时，成因既可能是
 *   服务端在变（模板/注入），也可能是**对端每轮回传的历史不同** —— 后者改服务端
 *   参数一样没用。把两种都说出来，读者才分得清"该改哪一侧"。
 *
 * 真机上「开头同形」这条尤其重要（四轮误判里两次是它）：两轮的 prompt 都在
 * 每格容量之下、长度也相仿时，"中间"只可能是历史段不同 —— 那时改服务端参数没用。
 *
 * ⚠ 判据只用 `lcp` / `fork_at` / `fork_prev` / `prev_len` / `total` / `prev_prompt` /
 *   `head_same` 这几个已有的数，不引入新的状态：日志里给出的每个结论都能在 /health
 *   上逐字段复核。
 *
 * ⚠ 「开头逐 token 相同」这句话**必须先满足一个前提**：被比的那一段真的覆盖到了
 *   断点（`head_cmp >= lcp`，或者调用方给了 `lcp` 之外的口径）。
 *
 *   以前没有这道前提，判据只有 `head_same == (i == cmp)` —— 而 `lcp > cmp` 时
 *   这个条件**无条件成立**（前 512 枚当然全等，断点在 2476）。于是日志用一次
 *   512 枚的比对，印了一句关于 2476 枚的结论。真机上那句结论还是错的：
 *   那 17 枚的缺口是**我们自己少喂的**（`n_rem` 之外没有位置上限，收尾比实际
 *   位置早），而报告说的是"对端每轮回传的历史不同"—— 把服务端的问题指给了对端。
 *
 * @param prev_prompt 上一轮**prompt 段**的 token 数；<0 = 调用方不知道（退化成只报位置）
 * @param head_same   上一轮账本开头与本轮是否逐 token 相同（`false` = 不知道/不同形）。
 *                    只影响「中间」那一档的措辞，不改任何复用判据。
 * @param field_lcp   调用方手里那个**"逐 token 公共前缀"**（`Plan::lcp` 的真值）。
 *                    <0 = 调用方没给（老调用点），此时不做前提检查、与改动前逐字节一致。
 */
inline std::string fork_where(const Plan & p, int prev_prompt = -1, bool head_same = false,
                             const kvwhere::Geometry * geo = nullptr, int field_lcp = -1) {
    if (p.fork_at < 0) {
        // 没有断点可言：账本无效 / 上一轮为空 / 本轮为空 —— 这一轮**根本没有上一轮**。
        // ⚠ 这一支以前盖着两种场次（ISSUE #503 真机第一屏）：一种是这里说的"没有上一轮"，
        //   另一种是"本轮整段都是上一轮的前缀"（`lcp == total`，那时也落 -1）——
        //   后者本来就该命中，而它被读成「本轮整串都在上一轮前缀里，没命中不是
        //   前缀对不上」，与同一屏的 `reuse 0` 直接打脸。后者现在落 `fork_at = total`
        //   并走下面那一支，这里只剩"没有上一轮"这一种。
        return "这一轮没有上一轮的可比前缀（账本无效 / 上一轮为空 / 本轮为空）—— 全量 prefill 是唯一落点";
    }
    if (p.fork_at_kind == Plan::kForkAtEnd) {
        // 本轮整段都是上一轮账本的前缀：断点就在本轮末尾，**不是**内容变了。
        // 这一档能不能命中只看两件事：门槛（`kMinReuseTokens`）与账本是否有效 ——
        // 而那时 `reuse = min(total, total-1) = total - 1`，命中是常态。
        const std::string s = "本轮整段（" + std::to_string(p.total) +
                              " tok）都是上一轮账本的前缀 —— 断点在本轮**末尾**，"
                              "不是内容变了；这一档应当复用 " + std::to_string(p.total > 0 ? p.total - 1 : 0) +
                              " tok（末枚留给自己 decode）";
        if (p.total < kMinReuseTokens) {
            return s + "｜本轮只有 " + std::to_string(p.total) + " tok，不足复用门槛 " +
                   std::to_string(kMinReuseTokens) + "，所以全量 prefill（这一档是净亏，不是缓存坏了）";
        }
        return s + "｜读到这句而 reuse 为 0，说明是容量 / 账本 / KV 实有长度拦的，去看 kv_valid 与 used";
    }
    const std::string at = "第 " + std::to_string(p.fork_at) + " tok 起分叉（上轮 " +
                           std::to_string(p.prev_len) + " / 本轮 " +
                           std::to_string(p.total) + " tok）";
    // 几何读数能落到具体区间时，**先说区间**再说落点：只报"落在 prompt 段中间"，
    // 与"缓存坏了"逐字同形，而真机上那一档的两个成因（夹带段在变 / 历史被挪）
    // 处置完全相反，且都**不是**服务端参数能治的。这一句在前提缺失（不知道上一轮
    // prompt 段多长）时同样成立 —— 它就是"断点两侧各是什么"，不需要上一轮长度。
    if (geo != nullptr && kvwhere::where(*geo) != kvwhere::kUnknown) {
        return kvwhere::describe(*geo);
    }
    if (prev_prompt < 0) return at;      // 不知道上一轮 prompt 段多长：只报位置，不猜落点
    // 三类按**从外到内**判，顺序即判据（每一条都只能由它自己命中）：
    //   ① 分叉那一枚已经越过上一轮的 prompt 段 -> 断在上一轮的**回复**里；
    //   ② 分叉落在 prompt 段内、且本轮 prompt 比上一轮 prompt 段还短
    //      -> 本轮被裁过（客户端裁历史，或我们这一格装不下）；
    //   ③ 其余：两侧都还有余量却对不上 -> 系统段/模板在变，或历史被换掉了。
    //
    // ① 的判据用 `fork_prev`（上一轮坐标系里的下标），`fork_prev < 0` 时退回
    // `fork_at`（老快照没有这一格，两者在"有分叉"时同值）。
    //
    // ② 的判据必须拿 `total` 与 `prev_prompt` 比，**不能**拿它与 `prev_len` 比：
    // `prev_len` 是"prompt 段 + 上一轮生成的回复段"，恒大于 prompt 段 ——
    // 用它判 ② 永远不成立，于是"本轮被裁过"会被误报成"有东西每轮都在变"（方向反）。
    const int fp = p.fork_prev >= 0 ? p.fork_prev : p.fork_at;
    if (fp >= prev_prompt) {
        return at + "，落在上一轮**回复段**起点之后 —— 上一轮的回复没被带回来"
                    "（或对端回传的历史与本轮不是同一份），改服务端参数没用";
    }
    if (p.total < prev_prompt) {
        return at + "，本轮 prompt 比上一轮**短**（上轮 prompt 段 " +
               std::to_string(prev_prompt) + " tok）—— 历史被裁过，调 Max Context / parallelN 有用";
    }
    // 「开头逐 token 相同」这句话的前提：比过的范围要**真的盖到断点**。
    //
    // `head_same` 原样透传的是"比过的每一枚都相同"（`head_cmp` 枚全等）。一旦
    // `lcp > cmp`，这条件无条件成立 —— 前 cmp 枚当然全等，因为断点在更后面。
    // 拿它去说"system 段没变"，等于把"只比了 512 枚"说成"整段都相同"。
    // 所以只有 `cmp >= lcp`（断点落在被比的范围之内或正好压在线之外的一枚）时
    // 才允许沿用上面那句；否则降级成"没测过"，并**保留**两侧都还有余量这一档
    // 的两种可能 —— 后者正是"我们少喂了尾巴"所对应的那种。
    //
    // ⚠ `geo->head_cmp` 的口径是「**真的**逐枚比过几枚、且这几枚相同」（ISSUE #452：
    //   它曾报"打算比多少"= 512，而第 286 枚就分开了，于是这条 `cmp >= lcp`
    //   恒成立 —— 那句"开头逐 token 相同"在断点 286 的场次上说了一个关于 512 枚的结论）。
    const int cmp = geo != nullptr ? geo->head_cmp : 0;
    const bool covered = (field_lcp < 0) || (cmp > 0 && cmp >= field_lcp);
    if (head_same && covered) {
        return at + "，落在上一轮 prompt 段**中间** —— 开头逐 token 相同（system 段没变），"
                     "是上一轮**历史段**与本轮对不上：对端每轮回传的历史不同（被裁过 / 只回传部分轮次 / "
                     "注入块长度变了）。服务端调 n_ctx / parallelN / max_tokens 都不会让它变好";
    }
    if (head_same) {
        // 只比了前 cmp 枚：断点在更后面，所以"开头到底同不同形"**没测过**。
        // 这一档**不许**替用户挑一个成因 —— 真机上它同时盖住了两种完全相反的事：
        // 对端换了历史（客户端侧）与我们自己少喂了尾巴（服务端侧，见 `stop_wall`）。
        return at + "，落在上一轮 prompt 段**中间** —— 只比了前 " + std::to_string(cmp) +
                    " tok（断点在更后面），开头同不同形**没测过**：这一档既可能是对端回传的"
                    "历史不同，也可能是**服务端自己少喂了尾巴**（生成被位置上限提前收尾，"
                    "看 /health 的 stop_wall 与 spare）—— 先看这两个数再决定调哪一侧";
    }
    return at + "，落在上一轮 prompt 段**中间** —— 两侧都还有余量：要么 system/模板每轮在变"
                "（服务端侧），要么对端每轮回传的历史不同（客户端侧）";
}

/**
 * 「被打断那一轮留在 KV 里的残留，能留到哪一枚」—— `truncate_ledger` 的判据。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要这一档（ISSUE #414）
 * ══════════════════════════════════════════════════════════════════════════
 * 在此之前，`abort_impl` 一置位就把整本账作废：下一轮 `plan_reuse` 拿到
 * `prev_valid=false`，走全清 + 全量 prefill。代价与打断发生在哪一步无关 ——
 * 哪怕只生成了 3 枚、甚至打断发生在 prefill 阶段，上一轮那几千枚 prompt
 * 也一起白算。
 *
 * 而 KV 里留下的那截**从来都是安全的**：它是「本轮 prompt 的完整前缀 + 已 decode
 * 的一段」，位置连续、token 正确。唯一的危险是**账本比 KV 长**（把没进 KV 的
 * token 当成已进 KV），那由下面这道截断 + `plan_reuse` 的约束 ⑥ 双重兜住。
 *
 * 所以这里不"作废"，只"截到实处"：
 *
 * | 打断发生在 | 账本里最后一枚**确实**进过 KV 的位置 | 截到 |
 * |---|---|---|
 * | prefill 中途 | 最后一个成功 `llama_decode` 的块尾 | `n_used`（**不看门槛**）|
 * | 生成中途（本轮第一枚之后）| 上一轮 prompt 段 + 已 decode 的生成枚数 | `n_used` |
 * | 本轮第一枚之前 | 它接的是**上一轮**的 KV，位置与上一轮账本连续 | 原账本（不截）|
 * | 位置贴着容量线 | 下一轮的 prefill 凑不出一次完整 decode | 作废（全清）|
 *
 * ⚠ 「prefill 中途」那一档**刻意跳过复用门槛**（16 枚）：门槛问的是"上一轮那条
 *   **回复**留下的残尾值不值得为它动一次 KV"，而 prefill 的残段是**本轮 prompt
 *   的真前缀** —— 用户按完停止之后做的下一件事就是重发同一个 prompt。见
 *   `killed_in_prefill` 形参那里的现场。
 *
 * 最后一档是**空间**理由，不是安全理由：`n_used` 离 `n_ctx` 只剩一两枚时，
 * 下一轮的回退（`seq_rm` + 重新 `decode`）会在位置上限上演一遍，收益是负的。
 *
 * ⚠ 本函数**只算出"该截到第几枚"**，不动 KV、不动账本 —— 真正去截的是
 *   `llama_jni.cpp` 里 `apply_pending_kv_trim`（它必须在**生成线程**上跑：
 *   `llama_memory_seq_rm` 不是线程安全的，而 abort 来自 HTTP 连接线程 / 主线程）。
 *   拆开的另一个理由与 `plan_reuse` 同源：判据要能被宿主单测钉住。
 */
struct TrimPlan {
    /** 截断之后的账本长度；0 = 这一轮不值得留（调用方按"未命中"全清）。 */
    int keep = 0;
    /** 是否真的截过（false = 原账本原样可用，一个字节都不用动）。 */
    bool trimmed() const { return keep > 0; }
    /**
     * 打断来自哪一段：`true` = **prefill 中被中断**（本轮还没跑完 prefill），
     * `false` = 生成期被打断（或那段半截 prompt 已覆盖住可留的前缀）。
     *
     * 用处只有一个：**门槛的归属**（见 [truncate_ledger] 的参数说明）。
     * 两个方向的误判代价差得很远：
     *   · prefill 的残留被当成"生成中途" -> 门槛直接把它扔了，而它是本轮**唯一**
     *     可能省下的东西（重发同一个 prompt 就是逐 token 相同的前缀）；
     *   · 生成的残留被当成"prefill 残留" -> 下一轮动一次 KV（`seq_rm` + 位置重排），
     *     那是几百微秒到几毫秒的净亏。
     * 所以这个字段答的是"这一段是从哪儿来的"，不是"值不值得留"。
     */
    bool killed_by_prefill = false;
};

/**
 * 算出「被打断的账本该截到哪一枚」。
 *
 * @param ledger_len 账本当前长度（prompt 段 + 生成段，= KV 里"以为"有的枚数）
 * @param prompt_len 账本的 **prompt 段** 长度（`kv_ledger_prompt`）
 * @param gen_in_ledger 账本的 **生成段** 长度（`kv_ledger_gen`，与上一行相加 = ledger_len）
 * @param n_used  KV 里**实际**有的枚数（`Session::n_used`）
 * @param ctx_limit 这一路的位置容量（两路同格时是每份 seq 的，不是总量）
 * @param min_keep 值得留下的最小枚数（复用门槛：留不到这么多就别留，省一次 seq_rm）
 * @param headroom 留给下一轮的余量：`n_used + headroom > ctx_limit` 时整本作废
 * @param killed_in_prefill 打断发生在 **prefill 里**（这一轮还没跑完 prefill）。
 *        它的残留是**本轮 prompt 的真前缀**而不是"上一轮回复的残尾"，所以不吃
 *        `min_keep` 那道门槛 —— 见函数体里 `keep_short` 那段现场。
 *
 * 三档判据，顺序即优先级：
 *  ① `n_used <= 0`            -> 0：KV 里已经空了，账本没有任何一枚有据。
 *  ② `gen_in_ledger <= 0`     -> 原账本（`prompt_len`）：
 *     打断发生在**本轮第一枚生成出来之前**，KV 里那一段是上一轮的内容，
 *     位置与上一轮账本连续 —— 它还是那份账本本身，只是下面 prefill 再算了一截。
 *     ⚠ 这一档**必须**排在 ③ 之前：它的 `n_used` 是上一轮的残留（比如 800），
 *     拿它去截一份 3000 枚的账本会把上一轮的 prompt 段腰斩（位置对得上、内容没了）。
 *  ③ 其余                     -> `min(n_used, prompt_len + gen_in_ledger)`：
 *     位置口径一律取 `n_used`（KV 的实况），`min` 只是防御（账本比 KV 短时别写越界）。
 */
inline TrimPlan truncate_ledger(int ledger_len, int prompt_len, int gen_in_ledger,
                                int n_used, int ctx_limit, int min_keep,
                                int headroom = 2,
                                bool killed_in_prefill = false) {
    TrimPlan t;
    if (ledger_len <= 0) return t;                      // 没有账本，没什么可截
    if (prompt_len < 0) prompt_len = 0;
    if (gen_in_ledger < 0) gen_in_ledger = 0;
    // ① KV 已经空了：账本里没有任何一枚有据。
    if (n_used <= 0) return t;

    int keep;
    if (gen_in_ledger <= 0) {
        // ② 本轮第一枚之前就被打断：账本原样（= 上一轮那份），不按 n_used 截。
        keep = std::min(ledger_len, prompt_len > 0 ? prompt_len : ledger_len);
    } else {
        // ③ 生成中途被打断：截到 KV 实况（且不超过账本自己）。
        keep = std::min(n_used, prompt_len + gen_in_ledger);
        keep = std::min(keep, ledger_len);
    }

    // 贴着容量线：下一轮的回退在那儿一定撞车，留下它只会让"命中"变成"白算一遍
    // 外加一次 seq_rm"。判据是**位置**，不是长度 —— 留得再长也不如位置够用。
    if (ctx_limit > 0 && n_used + headroom >= ctx_limit) return t;
    // 比门槛还短：复用是一次净亏（见 kMinReuseTokens 的那段）。
    // ⚠ 只对 ③ 生效：② 那一档是"原账本原样可用"，不用动 KV，拿门槛去否掉它
    //   等于凭空把一份好好的缓存作废 —— 门槛问的是"值不值得为它动一次 KV"。
    //
    // ⚠⚠ **prefill 里的中断不看门槛**（这一档原来把它扔掉了，而它是错的）：
    //   门槛 16 枚的前提是"上一轮那条**回复**留下的残尾，短了就是省不下什么"——
    //   回复短 = 下一轮 prompt 多半也不会以它开头。而 prefill 被打断时留下的残段
    //   完全不是这种残尾：它是**本轮 prompt 的一段真前缀**（位置连续、token 正确），
    //   而用户按停止之后做的下一件事**恰恰就是重发同一个 prompt**（客户端只看到
    //   `Broken pipe`，没有任何可操作的信息）—— 那一段的前几百枚是逐 token 相同的。
    //
    //   真机现场（ISSUE #450 第七次，prefill 60s 的长 prompt）：服务端在第一个
    //   `abort` 上就把整本账作废，于是"停止 -> 重发"这条最常见的路径每一轮都从
    //   第 0 枚重算，用户读到的是「算了 60 秒，还是什么都没出来」。
    //   代价那一侧：留下它只是**不动 KV**（残段本来就躺在那儿），唯一要付的是
    //   下一轮按 `n_used` 继续排位置 —— 那笔开销无论留不留都要付。
    const bool keep_short = killed_in_prefill && keep > 0;
    if (gen_in_ledger > 0 && keep < min_keep && !keep_short) return t;
    t.keep = keep;
    t.killed_by_prefill = killed_in_prefill;
    return t;
}

/**
 * 「上一轮被打断之后，账本尾巴上留着几枚**没被客户端带回来**的 token」。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须有这一格（ISSUE #452 真机：一条断掉的 stream 让后面每一轮都全量 prefill）
 * ══════════════════════════════════════════════════════════════════════════
 * 被打断（或连接断开）的那一轮，`abort_impl` 把账本**截到实处**（`truncate_ledger`），
 * 于是账本 = 那段 prompt + 已经 decode 进 KV 的那几枚回复。这是对的：KV 里确实有它们。
 *
 * 问题在**下一轮**：客户端把"没收到尾巴的那条回复"当成正常回复落盘，下一轮送上来
 * 的历史里那条 `assistant` 就**比我们 KV 里那段短**。于是公共前缀断在"客户端那条回复
 * 的末尾"，而账本尾巴上还留着我们多 decode 的那几枚。
 *
 * 这一档本身不奇怪，奇怪的是它的**后果**：
 *   · `plan_reuse` 的 ② 按前缀复用，断点之后的内容本来就不复用 —— KV 里那几枚
 *     残留**挡不住**这一轮命中；
 *   · 但位置是**连续排号**的：KV 里已经有 `reuse + 残留` 枚，这一轮的尾巴要么排在
 *     残留之后（`seq_rm` 掉残留再排，位置正好），要么根本没地方排。
 *   真机读数（`某真机 session`，每格 4096 / 水位 85% / 每轮约 300 tok）：
 *
 *     prompt 2499（生成 431 枚后被重新生成覆盖）  复用 2498，下一轮复用 302
 *     prompt 2633（账本 2757+57）                 复用 302，下一轮复用 301
 *     prompt 2757（账本 2814）                    复用 301
 *
 *   每一轮都"命中 300 枚左右"，而整段 prompt 是 2600+ —— 也就是说**每轮全量重算**。
 *   用户看到的是"上下文没越线、却每轮都截断并全量 prefill"。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它只报"有没有尾巴"，不决定怎么处置
 * ══════════════════════════════════════════════════════════════════════════
 * 「账本尾巴上一共有几枚这一轮用不到」= `ledger_len - reuse`，其中
 *   · 客户端带回来的那段回复（= `cur` 里与前缀接得上的那一截）**会被复用**，
 *     它不算"白留"；
 *   · 剩下那几枚（被打断之后多 decode 出来的、客户端没收到的那一截）才是。
 *
 * 这个数回答的是一个**跨轮**问题：`reuse` 只有几百、而 `cur` 有两千多时，
 * 缺的那两千不是"前缀对不上"，而是"上一轮账本里多出来的尾巴把位置占了"。
 * 日志与 /health 用它把这两件事分开 —— 分开之前它们说的都是
 * 「未命中 / 复用 N 枚」，读的人只能去猜是不是缓存坏了。
 *
 * @param ledger_len 上一轮账本长度（`last_tokens.size()`）；0 = 没有账本
 * @param reuse      本轮 `plan_reuse` 给出的可复用枚数
 * @param total      本轮 prompt 的 token 数
 */
struct LedgerTail {
    /** 账本里这一轮用不到的那几枚（0 = 账本正好是本轮前缀，或账本本来就是空）。 */
    int stale = 0;
    /**
     * 其中有几枚**不在本轮 prompt 里**（`ledger_len - total`，不小于 0）。
     *
     * 它与 [stale] 的差别就是"多出来的尾巴到底长什么样"：
     *   · `outside > 0` -> 账本比本轮 prompt 还长：上一轮多 decode 出来的那几枚
     *     确实在本轮 prompt 之外（被打断的现场就是这个形状）；
     *   · `outside == 0` -> 账本不比本轮长，只是断点之后的内容不一样（两轮真的不同）。
     */
    int outside = 0;
};

inline LedgerTail ledger_tail(int ledger_len, int reuse, int total) {
    LedgerTail t;
    if (ledger_len <= 0) return t;
    if (reuse < 0) reuse = 0;
    if (total < 0) total = 0;
    t.stale = ledger_len > reuse ? ledger_len - reuse : 0;
    t.outside = ledger_len > total ? ledger_len - total : 0;
    return t;
}

/**
 * 「上一轮的账本尾巴其实是**本轮的中间某一段**」—— 把它的位置折过来复用。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它救的是哪一档（ISSUE #452 真机：一条断掉的 stream 之后，每轮都全量 prefill）
 * ══════════════════════════════════════════════════════════════════════════
 * 被打断/连接断开的那一轮多 decode 了几十枚回复 token，客户端没收到、也没落盘。
 * 于是下一轮它送上来的历史里那条 `assistant` 比我们 KV 里短，公共前缀断在那里；
 * 而 KV **位置是连续排号**的：账本尾巴那几十枚照样占着位置 2600+，
 * 本轮 2633 枚的 prompt 只能排在它后面 —— 一装不下，`ctxbudget` 就丢历史、
 * 历史一丢起点就后移，之后**每一轮**都接不上（真机复用 302 / 301 / 301）。
 *
 * 这里不指望"下一轮客户端会把那段补回来"（它不知道有这一段）。能做的只有一件事：
 * 那几十枚**本来就是本轮的内容**（同一段对话的同一段回复，只是在我们这边多了一点点），
 * 所以把它 `seq_cp` 到它在本轮里的位置上，前缀就能一路接下去。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须逐枚复核（这条是整档的要害）
 * ══════════════════════════════════════════════════════════════════════════
 * 「账本尾巴」在本轮里出现的位置只有两种**可判定**的形态，其余一律不做：
 *   · **插在后面**（`cur` 比 `prev` 长，`prev` 整段都是 `cur` 的前缀，多出来的是本轮提问）：
 *     尾巴落在本轮 prompt **之后** —— 那是"下一轮生成"的位置，现在搬没有意义；
 *   · **接在中间**（本轮把那段回复后面的内容也带回来了：提问、甚至更后的轮次）：
 *     尾巴在第 `reuse` 枚起与本轮逐 token 相同。
 *
 * 形态判**内容**，不判长度差：两边都可能是"本轮短"或"本轮长"。
 * 逐枚比对的是 `prev[reuse ...]` 与 `cur[reuse ...]`（同位置同内容才有资格搬）——
 * 少这一道，就是把一段**别人位置上的** KV 搬到本轮的位置上：不崩、不报错、
 * 接口 200，只是模型读到错位的历史。所以宁可少救一轮，不可错搬一次。
 *
 * 另外三条保守约束：
 *   ① `reuse < 门槛` 时不做（与 `plan_reuse` 同一条理由：动 KV 有固定开销）；
 *   ② `reuse == 0` 时不做 —— 那是"上一轮与本轮开头就对不上"（换了会话/模板），
 *      账本尾巴与本轮的关系无从谈起；
 *   ③ 只有落在**本轮所有位置里最后一个**的那一段才搬（`to == cur.size()`）——
 *      尾巴之后本轮再没有内容时，它天然的落点就是末尾；别的情况（本轮比它更长、
 *      后面还跟着别的内容）由 `plan_reuse` 的前缀那一档负责，不在这里越权。
 *
 * @param prev 上一轮账本（prompt 段 + 生成段）
 * @param cur  本轮 prompt
 * @param reuse 前缀复用枚数（[Plan::reuse]）
 */
struct TailMove {
    /** 搬过去之后本轮总位置数（`n_used` 要收到这儿）；0 = 不搬。 */
    int total = 0;
    /** 尾巴要落到本轮的哪个位置（= `reuse`：`prev[reuse ...]` 与 `cur[reuse ...]` 对齐）。 */
    int to = 0;
    /** 搬几枚（== `prev.size() - reuse`）。 */
    int count = 0;
    /** 这些位置**以前排过别的**（本轮尾巴要重排/重算的就它们）—— 供日志与单测。 */
    int from = 0;
    explicit operator bool() const { return count > 0; }
};

inline TailMove fold_tail(const std::vector<long long> & prev,
                          const std::vector<long long> & cur,
                          int reuse) {
    TailMove m;
    if (reuse < kMinReuseTokens) return m;                       // ①
    if ((int) prev.size() <= reuse || (int) cur.size() <= reuse) return m;
    // ③ 尾巴必须正好落在本轮末尾（它后面本轮没有内容）。
    if ((int) cur.size() != (int) prev.size()) return m;
    // 逐枚复核：同位置同内容。
    for (size_t k = (size_t) reuse; k < prev.size(); k++)
        if (prev[k] != cur[k]) return m;
    m.total = (int) prev.size();
    m.to = reuse;
    m.from = reuse;
    m.count = (int) prev.size() - reuse;
    return m;
}

/**
 * 一句话说清「这一轮为什么只复用了这么点」—— 只在**有尾巴**时给。
 *
 * 判据与读法：`stale > 0` 且 `reuse` 远小于 `total` 时，缺的那一段**不是**
 * "前缀对不上"（那由 `fork_where` 说），而是"上一轮账本尾巴比客户端带回来的长"。
 * 两者的处置完全相反：前者要去查模板/注入，后者只要把尾巴丢掉、位置就接上了。
 */
inline std::string describe_tail(const LedgerTail & t, int reuse, int total) {
    if (t.stale <= 0) return "";
    std::string s = "上一轮账本尾巴上还留着 " + std::to_string(t.stale) + " tok 本轮用不到";
    if (t.outside > 0) {
        s += "（其中 " + std::to_string(t.outside) + " tok 根本不在本轮 prompt 里："
             "上一轮被打断/连接断开，多 decode 出来的那几枚客户端没收到）";
    }
    s += "；本轮复用 " + std::to_string(reuse) + " / " + std::to_string(total) + " tok";
    return s;
}

/** 供日志用的一行摘要（判据本身不依赖字符串，只为"一眼可分"）。 */
inline std::string describe(const Plan & p) {
    // 未命中时把**公共前缀**打出来：它是"差一点"与"真的不一样"的唯一分界。
    // 几道闸（门槛 / 容量 / 复用长度 / 调用方的 ⑥）都在 lcp 之后判，所以
    // lcp 大却没命中时只可能是它们之一拦的 —— 这行日志让用户不必猜是哪一类。
    // ⚠ 措辞只说"没复用"+"公共前缀多少"，**不**断言是被门槛拦的：门槛只是
    //   几道闸里的一道（容量、复用长度、调用方的 ⑥ 也会拦），写成
    //   "公共前缀 N tok < 门槛 16" 会在 lcp=60 却没命中时给出自相矛盾的一行。
    if (!p.hit()) return "KV 前缀复用：未命中（公共前缀 " + std::to_string(p.lcp) +
                         " tok / 门槛 16，" + fork_where(p) + "），全量 prefill " +
                         std::to_string(p.total) + " tok";
    if (p.skip > 0) {
        // 跳过起点的那一档要说清三件事：跳了几枚（= 新算几枚）、其中开头多少枚是
        // 逐 token 相同之后才分开的（**没变的部分**）、共用了几枚（复用）。
        return "KV 前缀复用：跳过前 " + std::to_string(p.skip) + " tok（这段需重算），" +
               "共用后面 " + std::to_string(p.reuse) + " tok（前 " +
               std::to_string(p.head_lcp) + " tok 逐 token 相同，第 " +
               std::to_string(p.skip) + " tok 起不同）";
    }
    return "KV 前缀复用：命中，复用 " + std::to_string(p.reuse) + " tok（公共前缀 " +
           std::to_string(p.lcp) + " tok），只需 prefill " +
           std::to_string(p.to_decode) + " tok（共 " + std::to_string(p.total) + " tok）";
}

} // namespace kvprefix

#endif // LOCAL_LLM_KV_PREFIX_H
