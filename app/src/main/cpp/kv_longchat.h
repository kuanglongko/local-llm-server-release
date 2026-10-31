// kv_longchat.h —— 长对话**越过容量之后**的取舍判据（纯逻辑，宿主可编、可单测）
//
// ══════════════════════════════════════════════════════════════════════════
// 这个文件只回答一个问题：**prompt 装不下时，这一轮从哪里开始喂？**
// ══════════════════════════════════════════════════════════════════════════
// 第 4 块之后的做法是"保留最后 keep 枚 token" —— 也就是**起点跟着滑窗尾巴走**。
// 真机读数（ISSUE #330 `1.1.txt`，每格 4096 / max_tokens 1024 / 每轮历史涨约 300）：
//
//   轮  prompt  keep  丢头   起点  上轮起点  LCP   gen
//    9    3300  3300     0      0        0   3000   794
//   10    3600  3600     0      0        0   3300   494
//   11    3900  3900     0      0        0   3600   194   ← 生成上限被吃到 194
//   12    4200  4092   108    108        0      0     2   ← 一过线：复用归零、只剩 2 tok
//   13    4500  4092   408    408      108      0     2
//   14    4800  4092   708    708      408      0     2   ← 起点每轮后移，不会自己恢复
//
// 这张表同时解释了用户报的三件事：
//   · 「生成过长之后就开始重算」= 第 12 轮起起点 108/408/708 一路后移，上一轮攒下的
//     前缀被**永久**丢掉一段，`lcp` 恒 0；
//   · 「一超过上下文就开始胡说」= 同几轮 gen 掉到 2，客户端把那条空回复落盘，
//     下一轮历史里就夹着一条空 assistant，角色序列与训练形状不再同形；
//   · 「之前也断，只是对话短没触发」= 第 9~11 轮 gen 已经在被吃（还没丢头，所以看起来正常）。
//
// 所以这里的两个判据都只做一件事：**让"让位"的顺序与真机上的代价对齐**。
//
//   ① [planKeep] —— 这一轮该保留多少 prompt。优先让掉 gen（它只是**上限**），
//      只让到地板 [kGenFloor]，绝不让到 2；剩下的缺口才由 prompt 吸收。
//   ② [startFor] —— 起点。能接上上一轮就接上（前缀接得上，复用仍然成立），
//      接不上才退回"保留最近的内容"。
//
// **一条不变量把上面两条串起来（ISSUE #330 第 1 块）**：
//
//     已进 KV 的前缀只增不减 —— 本轮起点必须 ≥ 上一轮起点，
//     而且**能不动就绝不动**。
//
// 上一版把"能不能对齐"当成一个需要额外争取的条件（先看缺口、再问撑不撑得住），
// 于是缺口每轮涨多少、起点就后移多少。现在反过来：只要上一轮那个起点还留在本轮
// 内容里、且留下它不越界，就钉在它上面 —— 它不是"优化项"，它是**默认项**。
//
// 装得下时两者都退回现状：一个字段都不动，与引入本特性前逐字节一致。
#ifndef LOCAL_LLM_KV_LONGCHAT_H
#define LOCAL_LLM_KV_LONGCHAT_H

#include <algorithm>
#include <string>

// 水位归一只有一份（`ctxwatermark::watermarkNormalize`）：`planKeep` 也要按水位判
// "装得下"，而它自己再钳一遍就会与 `ctxbudget::plan` 分叉 —— 分叉的症状是
// "预算说装得下、这里又从头部砍一刀"，两边都不报错。
//
// ⚠ 只能 include 这个**无依赖**的头，不能 include `ctx_budget.h`：那个文件反过来
//   include 本文件（它要用 `kGenFloor`），双向 include 就是循环依赖、编译不过 ——
//   而"编译不过"会把人逼回"各写一份水位"，那正是要消掉的那件事。
#include "ctx_watermark.h"
// 复用门槛只有一份（`kvprefix::kMinReuseTokens`）：`startForByLcp` 要拿它判
// "十几枚值不值得动 KV"，而另写一个字面量就是第二把尺子（见该函数里的注释）。
#include "kv_prefix.h"

namespace kvlongchat {

/**
 * 生成上限的**让位地板** —— 越过容量线时最多把 `max_tokens` 让到这里。
 *
 * 为什么不是 `ctxbudget::kMinGenTokens`（那个 2 是"最后一枚留给采样"的机器下限）：
 * 真机上"话只说了一两个字"就是 gen 掉到 2 的那几轮。64 枚约等于一两句完整的话 ——
 * 用户至少拿得到一个能读的回答，而不是一条会被落盘、再去毒化下一轮历史的空回复。
 *
 * ⚠ 它**不是硬钳**（硬钳会让 `prompt + gen > ctx`，那是越界）：prompt 本身装不下时，
 * [planKeep] 会一路丢 prompt；连 prompt 都丢到 1 枚还不够，那一轮才是真的装不下。
 */
const int kGenFloor = 64;

/**
 * 丢头那一刀的**下限**：prompt 至少留几枚（`0` 会让调用方以为"空 prompt"）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么它必须与 [kGenFloor] 是**两个**常量（ISSUE #577 Layer 4）
 * ══════════════════════════════════════════════════════════════════════════
 * 这两个数此前**共用一个名字**（`ctx_budget.h` 里丢头那一刀写的是
 * `if (keep < kGenFloor) return 0`、`promptKeepMin` 又是另一处的一字面量），
 * 而它们问的是两个方向相反的问题：
 *
 *   · [kGenFloor]   —— "这一轮**至少要给**多长回答"（生成长度的地板，防的是空回复）；
 *   · [promptKeepMin] —— "丢头时**至少要留**几枚 prompt"（防的是 `prompt == 0`
 *     被调用方读成"没有 prompt"）。
 *
 * 共用一个数的时候，真机上必然有一档永远走不到：落点线那一档用 `keep < kGenFloor`
 * 当"连地板都放不下就别丢了"，而落点本身是 `half − gen` —— 窄格（`avail/2 < 64`）
 * 与 `gen ≥ 64` 两档都会落进那个分支，于是"丢一半"在 `ctx < 302` 的格子上从不生效，
 * 而那正是用户把上下文调到最小的时候。
 *
 * ⚠ 拆开之后**两个方向各自说自己那个数**：谁想把 [promptKeepMin] 拿去当生成地板，
 *   就得显式写出来 —— 那时候至少读的人看得出来这是另一件事。
 */
const int promptKeepMin = 1;

/**
 * 越线那一刀的**落点**：丢完之后 `prompt + gen` 该落在哪。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要单独抽出这一格（ISSUE #503 真机：「越线之后每轮都丢一点点」）
 * ══════════════════════════════════════════════════════════════════════════
 * 这一刀的目的是"丢一次，换几十轮不动"。落点算错，那个目的就没了：丢完仍贴着水位线，
 * 下一轮历史涨几百枚就又越线 —— 起点每轮后移一步、KV 前缀每轮断。真机连着三轮
 * `丢最老 12 条` → `丢最老 14 条` → 每轮 `复用 202 / 新算 1156` 就是它。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 落点由**占用比**定，不由 `max_tokens` 定（ISSUE #586 第三回合）
 * ══════════════════════════════════════════════════════════════════════════
 * 目标合计 = `avail × ratio_pct`（`avail = ctx × 水位`），留下 `keep = 目标合计 − gen`。
 * `gen` 是**在里面占位**，不是额外的扣减项 —— 于是 `max_tokens` 越大，留下的 prompt
 * 越小（回答本来就要占位置），而"丢一次管几轮"由比例本身决定，不再被 `gen` 牵着漂。
 *
 * 前任的两个基数都不成立：`avail / 2` 忽略 `max_tokens`，落完 `avail/2 + gen` 离起判线
 * 只剩几百枚（`4096 / 85% / 1024` 约三轮，不是几十轮）；按触发线取半（`(avail − gen) / 2`）
 * 则在 `gen` 逼近半格时塌成 0 甚至负数 —— 而大 `max_tokens` 正是大 ctx 用户的选法。
 *
 * ⚠ 比例必须与 Kotlin 的 `ContextBudget.HALF_RATIO_PCT` **同值**（`plan` 传
 *   `ctxbudget::kHalfRatioPct`）：同一条路（App 内聊天）上两边算两个落点，等于
 *   客户端丢一遍、服务端再按另一条线丢一遍。
 * ⚠ `prompt` 已经在落点线以内时返回 `prompt`（一枚不丢）—— 这一档本来就不该越线。
 *
 * @param prompt 丢头之前的整段 prompt token 数
 * @param gen    这一轮预算愿意给的生成长度（`Plan::gen`，让位之后）
 * @param ctx    这一格的 n_ctx（或这一条 seq 的容量，与 `plan` 同一把尺子）
 * @param watermark_pct 这一轮的水位（%；`<= 0` 走默认档，归一与 `plan` 同一份）
 * @param ratio_pct 落点占水位线的比例（%；`<= 0` 走 `ctxbudget::kHalfRatioPct`）
 * @return 丢完之后应当留下的 prompt 枚数（`>= 0`；`0` = 没有可算的，调用方按原判据走）
 */
inline int halfWatermarkKeep(int prompt, int gen, int ctx, int watermark_pct, int ratio_pct) {
    if (prompt <= 0 || ctx <= 0) return 0;
    const int wm = ctxwatermark::watermarkNormalize(watermark_pct);
    const int avail = (int) ((long long) ctx * wm / 100LL);
    // ⚠ `ratio_pct` 缺省值由调用方给（`ctxbudget::kHalfRatioPct`）—— 这里不 include
    //   `ctx_budget.h`（它会回头 include 本文件，循环依赖编译不过）。
    const int pct = ratio_pct > 0 ? ratio_pct : 50;
    const int line = (int) ((long long) avail * pct / 100LL);
    const int g = gen > 0 ? gen : 0;
    const int keep = line - g;
    // 落点线连**丢头的下限**都放不下（窄格 / 水位很低 / `gen` 比落点线还大）：
    // 没有"丢一次"可言，交给原判据（`0` = 没算出来）。
    //
    // ⚠ 这里判的是 [promptKeepMin]，**不是** [kGenFloor]（ISSUE #577 Layer 4）：
    //   这个函数的返回值是"留下几枚 **prompt**"，而 `kGenFloor` 是"至少给多长**回答**"。
    //   共用一个数时，`gen ≥ 64` 或 `avail × 比例 < 64`（最小上下文那几格）都落进这个
    //   分支 —— 于是"丢一次"在最小上下文的格子上从不生效，而那正是用户把上下文调到
    //   最小的时候。
    if (keep < promptKeepMin) return 0;
    return keep < prompt ? keep : prompt;
}

/** 对齐上一轮时，最多从**尾部**（本轮最新内容）让掉多少 token。 */
const int kAlignMaxTailDrop = 8192;

/**
 * 丢头那一刀"为了切在可锯断处"最多多丢几枚（[ctxbudget::trim_start_snapped]）。
 *
 * 它必须是个**常数**，不许按整段 prompt 的比例算：按比例算的话大 prompt 丢得更多，
 * 而"多丢几枚"的正当理由是"别把半枚汉字留在开头"，与这一轮有多长没有关系。
 *
 * 16 的来由：中文常见字 3 字节一枚 byte token，一个 UTF-8 字符最多 4 枚；
 * 连 16 枚都找不到一个可锯断处，说明那一段根本不是按字节切的（合并子词），
 * 那时再往前挪就是白丢历史了。
 */
const int kCutSnapTokens = 16;

/**
 * 这一轮该保留多少 prompt token。
 *
 * @param prompt   整段 prompt 的 token 数（真 token）
 * @param max_gen  调用方要的生成长度（`max_tokens`）
 * @param ctx      这一格的 n_ctx
 * @param want_start 想对齐的起点（上一轮的起点；<=0 = 不对齐）。
 *                传 0 就是第一版行为（保留尾部，起点跟滑窗走）。
 * @param watermark_pct 这一轮的水位（%，ISSUE #426 方案 B）。`<= 0` = 用默认档；
 *                归一走 `ctxwatermark::watermarkNormalize`（与 `ctxbudget::plan` 同一份判据）。
 * @return `keep` = 保留的 prompt 长度（≥1）；`0` = 连 1 枚都留不出（装不下）
 *
 * 让位顺序（每条都对应一种"看起来能跑、其实错"的写法）：
 *   ① 装得下 -> 一枚都不让（`keep == prompt`，调用方据此保持逐字节兼容）；
 *   ② 先让 gen，只让到 [kGenFloor]。以前一步让到 2，于是"越过线"这件事由
 *      **空回答**来表现，而空回答会被写进历史；
 *   ③ 仍装不下才丢 prompt：先尽力保住 `want_start`（对齐 = 前缀还能复用），
 *      保不住时按"把缺口补齐"丢 —— 也就是第一版的行为。
 *
 * 「装得下」的线是**水位线**（`min(ctx × 水位, ctx - 1)`），不是整格 `ctx - 2`。
 *
 * ⚠ 这一格曾经漏了水位（`avail = ctx - 2`），而 `ctxbudget::plan` 已经按水位判 ——
 *   于是同一个会话里两把尺子：`plan` 认为"越线了、要丢"，`planKeep` 认为"整格还装得下、
 *   一枚不让"，并把预算已经算好的保留量**按缺口再砍一刀**（真机 ctx 4096 / max_tokens
 *   1024 / 水位 85%：prompt 3600 那一轮 `plan` 丢到 1768 + gen 64，而 `planKeep`
 *   又按整格算缺口、把 gen 削回 481 —— 越线那一轮的回答反而更长、下一轮照旧再丢）。
 *   两把尺子只在 `plan` 里统一是没有用的：接线守卫钉"native 判据按水位收紧"时
 *   锚的是 `plan`，而这条路走的是 `planKeep`。
 *
 * 为什么"对齐"要排在"多留一枚"之前：复用的价值是**整段前缀**（几百到几千 token
 * 的 prefill 时间），而多留的那几枚只是"本轮多看一句话"。两者不在一个量级上。
 */
inline int planKeep(int prompt, int max_gen, int ctx, int want_start,
                    int watermark_pct = ctxwatermark::kDefaultWatermarkPct) {
    if (prompt <= 0 || ctx <= 0) return prompt > 0 ? prompt : 0;
    const int room = ctx - 1;                        // 最后一枚要留给采样
    if (room <= 0) return 0;
    int gen = std::min(std::max(max_gen, 0), room);
    // ① 装得下：prompt 一枚不动。
    //    判据与 ctxbudget::plan 同一条：`prompt + gen <= min(ctx × 水位, ctx - 1)`。
    //
    // ⚠ `avail_wm` 不许抬到 `ctx - 2` 之上（水位只收紧、不放大）；`room` 只是
    //   "最后一枚留给采样"那道钳，两处都取最小才是这一轮真正的可用空间。
    const int wm = ctxwatermark::watermarkNormalize(watermark_pct);
    const int avail_wm = (int) ((long long) ctx * wm / 100LL);
    const int avail = std::min(avail_wm, room);
    if (prompt + gen <= avail) return prompt;
    // ② gen 让到地板（不再让到 2）。
    const int floorGen = std::max(2, std::min(kGenFloor, room - 1));
    if (gen > floorGen) gen = floorGen;
    if (prompt + gen <= avail) return prompt;
    // ③ prompt 吸收缺口。
    //
    // ⚠ 这一档是 ISSUE #330 第 1 块的核心：**起点能不动就绝不动**。
    //   上一版的判据是"先看缺口多大、再问接不接得上"，于是缺口每轮涨 300、
    //   起点每轮也挪 300（真机 108/408/708）。现在反过来写：**只要上一轮那个起点
    //   还留在本轮内容里、空间撑得住，就钉在它上面**（那正是"已进 KV 的前缀只增不减"），
    //   只有连它都保不住时才让起点后移。
    //
    //   判据用的 `want_start` 已经由调用方收口成"上一轮的起点"（不是"上次想对齐的位置"），
    //   所以这条不变量在连续多轮里自洽：接上之后起点不再变，复用只是"上一轮的整段前缀"。
    const int over = prompt + gen - avail;
    const int want = want_start > 0 ? prompt - want_start : 0;   // 接上要保留的长度
    // ③-a 撑得住：留下 [want_start, prompt) 这 want 枚，起点正好是上一轮起点。
    if (want > 0 && want + gen <= avail && want <= kAlignMaxTailDrop) {
        return want;
    }
    // ③-b 撑不住（接上要丢的内容太多）：按"把缺口补齐"丢 —— 起点跟滑窗走。
    //      退回它不会比现状更坏，而硬撑着接上会把本轮提问丢掉。
    //      `refuse` 会把"本来能少丢多少"如实报出来（见 reuse_trace.h）。
    const int keep = prompt - over;
    return keep > 0 ? keep : 0;
}

/**
 * 这一轮从哪个下标开始喂 prompt。
 *
 * @param prompt     整段 prompt 的 token 数
 * @param keep       [planKeep] 的落值（要保留多少）
 * @param prev_start 上一轮的起点（`Session::kv_last_start`；0 = 没有基线）
 * @param can_align  上一轮那一段前缀是否**在本轮内容里**（[canAlign] 的落值）
 * @return 起点下标（0 = 一枚都不丢）
 *
 * ⚠ 它只管"从哪一枚起喂"，管不了"起点之前那几格是谁的"：`can_align` 来自
 *   `lcp >= prev_start`，只是"起点之前逐 token 相同"的必要条件。所以调用方拿到
 *   非 0 的起点之后还要过 [startCovered]（ISSUE #577 Layer 2）—— 那一档才是
 *   "乱码"（位置对得上、槽位是别人的）的唯一出口。
 */
inline int startFor(int prompt, int keep, int prev_start, bool can_align) {
    if (prompt <= 0) return 0;
    if (keep <= 0) return 0;              // 装不下：调用方会显式失败，这里不给越界值
    if (keep >= prompt) return 0;         // 一枚不丢（逐字节兼容那一档）
    if (can_align && prev_start > 0 && prev_start < prompt) {
        // 起点就是上一轮起点 —— 留下的每一枚都与上一轮同形，复用仍然成立。
        return prev_start;
    }
    // 接不上时的唯一落法：从尾部保留 keep 枚。**不许**拿"旧起点 + 缺口"去凑 ——
    // 那个数每轮都在变，于是每一轮都从一个新位置起算，攒下的前缀永远接不上
    // （真机 108 → 408 → 708 就是这么来的）。
    return prompt - keep;
}

/**
 * 这一轮**能不能**把起点对齐到上一轮。
 *
 * 前提只有一条：上一轮起点之前的整段前缀**在本轮里逐 token 相同** ——
 * 那是 `kvprefix::plan_reuse` 算出来的 `lcp`，不是这里能重新推的。
 * 所以判据写成"`lcp >= prev_start`"：上一轮从 `prev_start` 开始喂，本轮前 `prev_start`
 * 枚一模一样，于是"从上一轮起点继续"与"从头开始"在本轮是同一段内容。
 *
 * 反例（不能对齐）：上一轮起点 708、本轮 lcp 只有 9 —— 开头就变了（换了会话 /
 * 模板 / system 在变）。对齐会把两段不同的内容当成接续：位置照样对得上，槽位却是空的。
 */
inline bool canAlign(int prev_start, int lcp) { return prev_start > 0 && lcp >= prev_start; }

/**
 * 接不上时，起点该不该**声明**在公共前缀的断点上。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它治的是"丢过一次之后每轮全量"这条自锁（ISSUE #452 第 1 步）
 * ══════════════════════════════════════════════════════════════════════════
 * 上一轮丢过历史之后，这一轮的 prompt 是上一轮的**后缀**（客户端/预算把最老那几段
 * 扔掉了）。而 [canAlign] 要的是 `lcp >= prev_start` —— 后缀场景下 `lcp` **恰好等于**
 * 本轮的起点 `from`，比 `prev_start` 小，差一枚不成立。
 *
 * 于是 `startFor` 退回"从尾部保留最近 keep 枚"，起点跟着滑窗走；下一轮又是后缀、
 * 又差一枚 —— `reuse` 从此归零，而且**不会自己恢复**。真机那场
 * `reuse 286 / 新算 1529` 之后再没动过，就是这条自锁。
 *
 * 关键事实：`lcp` 本身就是**锚**。它说"本轮前 lcp 枚与上一轮逐 token 相同"，
 * 那么从第 `lcp` 枚起喂，KV 里 `[0, lcp)` 那段位置上的内容与这一轮的前 `lcp` 枚
 * 完全对得上 —— 起点声明成 `lcp` 是**可验证的事实**，不是猜。
 *
 * ⚠ 三条门槛，缺一不可：
 *   · `lcp >= kMinReuseTokens`：动 KV 有固定开销（`seq_rm` + 后续 prefill 的位置重排），
 *     复用不到十几枚是净亏，而且会给日志刷满 `reuse=2` 这类假命中；
 *   · `lcp < prompt`：起点必须落在 prompt 之内（本轮的最后一枚一定要被 decode 一次，
 *     否则采样步没有 logits 可采 —— 与 `plan_reuse` 约束 ④ 同一条理由）；
 *   · `lcp < prev_start`：只在"接不上"的那一档生效。`lcp >= prev_start` 时
 *     `canAlign` 已经成立，走 [startFor] 的对齐分支，**不许**在这里另起一个落点。
 *
 * 它**不**改变"丢多少"这件事的任何判据（`keep` 由预算给），只改"从哪一枚开始喂"。
 * 而且声明出来的起点仍然要通过 native 那边的逐枚复核 —— 这条函数只给候选落点。
 */
inline bool startForByLcp(int prompt, int prev_start, int lcp) {
    if (prompt <= 0 || lcp <= 0) return false;
    // ⚠ 门槛用 `kvprefix::kMinReuseTokens`（16），不在这里重写一个字面量：
    //   两处各写一个数时，"复用门槛"这件事就有两把尺子，而它们从不吵架 ——
    //   只是有一天改了一处，另一处继续按旧值判，症状是"复用时有时无"。
    //   `kv_prefix.h` 是纯头文件（只依赖 `kv_diff_where.h`），没有反过来 include 本文件。
    if (lcp < kvprefix::kMinReuseTokens) return false;   // 太短：动 KV 净亏
    if (lcp >= prompt) return false;           // 起点必须落在 prompt 之内（末枚要 decode）
    if (prev_start > 0 && lcp >= prev_start) return false;  // 接得上，走对齐分支
    return true;
}

/**
 * 「起点接得上上一轮、但**预算的 keep 装不下**它」时，起点该落在哪（ISSUE #452 真机）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它治的是"一开知识库就掉到 286、之后每轮都只有几百枚复用"这条链
 * ══════════════════════════════════════════════════════════════════════════
 * 真机（`reuse 286 / lcp 286 / start 286 / ledger 2030 / prompt 1680`）：
 * 上一轮从 286 起喂、账本 2030 枚；本轮是同一份对话，最前面多出十几枚注入块。
 *
 * `canAlign(286, 286)` **是成立的**（`lcp >= prev_start` 取等即真），所以
 * [startFor] 走的是对齐分支 —— 但它要求"留下 `want = prompt - prev_start` 枚
 * (= 1394) 也装得下"（`want + gen <= avail`）。水位 85% 时 `avail = 3481`、
 * `gen` 已被让到地板 64 —— 1394 + 64 是装得下的，所以真正让它掉出去的是
 * **`planKeep` 给的 `keep` 比 `want` 小**：预算按"补齐缺口"只留了最后一截，
 * `startFor(prompt, keep, prev_start, true)` 见到 `keep < prompt - prev_start`
 * 就没法把起点钉在 286 上，退回 `prompt - keep` —— 与上一轮的起点不是同一个位置。
 * 于是复用的只剩"上一轮那段里的尾巴"，真机 `reuse 286` 就是这么来的。
 *
 * 判据：起点钉在上一轮起点上之后，本轮从它到 prompt 末尾这一整段**都还得进模型**
 * （只是 KV 里那段前缀能沿用）。所以这条路**不改"丢多少"**，只回答一件事：
 * 既然 `canAlign` 说接得上，起点就不该为了凑 `keep` 而往后再挪。
 *
 * 四条约束，缺一不可：
 *   · `canAlign` 必须成立（接不上走 [startForByLcp] / [alignStartShifted]）；
 *   · `prev_start < prompt`：起点落在 prompt 之内（末枚要 decode 一次）；
 *   · `prompt - prev_start + gen <= avail`：留下的整段还要装得下 —— 装不下就真的
 *     没得选，退回"从尾部保留"（那时多留一枚都是越界，比丢历史严重得多）；
 *   · `prompt - prev_start >= kMinReuseTokens`：只剩十几枚时省不下什么，不值得动 KV。
 *
 * @param prompt     本轮整段 prompt（丢头之前的枚数）
 * @param gen         这一轮预算愿意给的生成长度（`budget.gen`）
 * @param avail      这一轮可用空间（水位线与"最后一枚留给采样"取小）
 * @param prev_start 上一轮的起点（0 = 没有基线）
 * @param lcp        本轮与上一轮账本的逐 token 公共前缀
 * @return 起点（= `prev_start`）；0 = 这一档不适用（走原来的 `startFor`）
 */
inline int alignStartKept(int prompt, int gen, int avail, int prev_start, int lcp) {
    if (prompt <= 0 || prev_start <= 0) return 0;
    if (!canAlign(prev_start, lcp)) return 0;          // 接不上：不归这一档管
    if (prev_start >= prompt) return 0;                // 起点必须落在 prompt 之内
    const int want = prompt - prev_start;
    if (want < kvprefix::kMinReuseTokens) return 0;    // 只剩十几枚：净亏
    if (avail > 0 && want + (gen > 0 ? gen : 0) > avail) return 0;  // 真的装不下
    return prev_start;
}

/**
 * 「账本**向后挪了**」时，起点该声明在哪（ISSUE #452 真机最后一块）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 与 [startForByLcp] 的分工
 * ══════════════════════════════════════════════════════════════════════════
 * [startForByLcp] 声明的是 `lcp` —— 它治"上一轮的开头**还在最前面**"那一档
 * （`lcp` = 本轮前多少枚与上一轮相同）。真机另一种形态是：本轮最前面多出十几枚，
 * 于是上一轮的开头在本轮**第 lcp 枚**才出现（`lcp` 是那个偏移，不是长度）。
 * 这时"上一轮从 prev_start 起喂的那一段"在本轮的位置是 `lcp + prev_start`。
 *
 * 声明成它是**可验证的事实**：`plan_reuse` 的 ⑦ 那一档已经按
 * `find_cur_head_in_prev_all` 逐枚复核过「`prev[v...]` == `cur[0...]`」，
 * 这里只是把"本轮从哪一枚起才有缓存"这件事说出来。
 *
 * 三条门槛（与 [startForByLcp] 同一条理由）：
 *   · `lcp >= kMinReuseTokens`：偏移太小等于没偏移，动 KV 净亏；
 *   · `shift < prompt`：起点必须落在 prompt 之内（末枚要 decode 一次）；
 *   · 剩下的那截 `>= kMinReuseTokens`：挪过去只剩十枚时是纯粹的净亏。
 *
 * @param prompt     本轮整段 prompt（丢头之前的枚数）
 * @param prev_start 上一轮的起点（0 = 没有基线）
 * @param lcp        本轮与上一轮账本的逐 token 公共前缀（= 账本开头在本轮的落点）
 * @param ledger_len 上一轮账本长度（prompt 段 + 生成段；0 = 不知道）
 * @return 声明出来的起点（> 0）；0 = 这一档不适用
 */
inline int alignStartShifted(int prompt, int prev_start, int lcp, int ledger_len) {
    if (prompt <= 0 || lcp <= 0) return 0;
    if (lcp < kvprefix::kMinReuseTokens) return 0;   // 偏移太小：动 KV 净亏
    if (lcp >= prompt) return 0;                     // 起点必须落在 prompt 之内
    const int shift = lcp + (prev_start > 0 ? prev_start : 0);
    if (shift <= 0 || shift >= prompt) return 0;
    // 挪完之后还剩多少可复用：上限是账本里 `prev_start` 之后那一段（含生成段），
    // 下限是"本轮从这一枚起还剩多少"。取小 —— 两个都不是可选的。
    const int cover = (ledger_len > 0 && prev_start > 0) ? ledger_len - prev_start : 0;
    const int remain = std::min(prompt - shift, cover);
    if (remain < kvprefix::kMinReuseTokens) return 0;
    return shift;
}

/**
 * 「本轮整段都在上一轮里」时，起点该落回第 0 枚（ISSUE #503 真机第三回合）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它治的是"水位线之后每轮全量 prefill"这条自锁的**另一半**
 * ══════════════════════════════════════════════════════════════════════════
 * 真机（`ctx 4096 / 水位 85% / max_tokens 1024`，两屏都复现）：
 *
 *   轮  prompt_real  prev_prefix_in_cur  prev_start  start  reuse  prefill
 *    8      2610            2610            243      200    200     2410
 *    9      1664            1664            471      473    473     1191
 *
 * `prev_prefix_in_cur == prompt_real` 是**比过的数**（从第 0 枚起逐枚相同），
 * 意思是"本轮喂进去的这一整段，一字不改地躺在上一轮账本里"。可起点（`start`）
 * 却比上一轮（`prev_start`）多滑了一截，于是 `reuse` 只剩到那个断点为止 ——
 * 每轮滑一点，KV 里攒下的那段**永远接不上**。
 *
 * 上面三条路（[startFor] 对齐 / [startForByLcp] / [alignStartShifted]）都不管这一档，
 * 原因是它们的前提都是"上一轮的**开头**还在本轮最前面"：
 *   · `canAlign` 要 `lcp >= prev_start`，而这一档的 `lcp == prompt_real` 是**本轮真长**、
 *     与 `prev_start` 不在同一个坐标里（上一轮账本比它长，还得算上生成段）；
 *   · `startForByLcp` 的第三条门槛（`lcp < prev_start`）会把它挡在外面 ——
 *     它以为"接得上就走对齐"，而对齐在上一格已经失败了。
 *
 * 判据只有一句：**内容一枚没变，起点就没有理由动**。上一轮从 `prev_start` 起喂了
 * `prev_start + prev_prefix_in_cur` 枚，本轮这 `prev_prefix_in_cur` 枚正是那一整串 ——
 * 起点落回 0 之后，留下的每一枚都与上一轮同位置、同内容（`reuse` 因此直接等于
 * `prev_start + prev_prefix_in_cur`），省掉的是**整段**重新 prefill。
 *
 * ⚠ 这是**唯一**一档允许起点往前挪（`from < prev_start`）：其余各档的位移方向恒为
 *   往后（多丢），那时"少丢"要拿 400 来换。这一档不同 —— 往前挪的不是新内容，
 *   是上一轮**已经算过、且逐枚比过**的那一段，KV 里现成就有。
 *
 * 四条门槛，缺一不可：
 *   · `prev_prefix_in_cur >= kMinReuseTokens`（且 `>= 0`，-1 = 没法量）：
 *     动 KV 有固定开销，十几枚不值当 —— 与 [startForByLcp] 同一条理由；
 *   · `ledger_len >= prev_prefix_in_cur`：上一轮账本**至少**装了这么长。比本轮短的
 *     那一份不可能"装着"本轮的整段，那种场次只说明开头一段恰好一样；
 *   · `prev_prefix_in_cur == prompt`：**整段**都比过。只比过一截（真机
 *     `prev_prefix_in_cur 202 / prompt 1931`）时起点 0 是错的 —— 那一截之后的内容
 *     与账本不同，从 0 起喂会让模型读到错位的历史，而 `reuse` 还很大、不崩不报错；
 *   · `prev_start > 0`：没有基线时"落回第 0 枚"本来就是这个默认值，没什么可钉的。
 *
 * @param prompt              本轮整段 prompt（丢头之前的枚数）
 * @param prev_start          上一轮的起点（`Session::kv_last_start`；0 = 没有基线）
 * @param prev_prefix_in_cur  从第 0 枚起，本轮与上一轮账本逐枚相同的枚数
 *                            （-1 = 这一轮没法量：账本无效 / 本轮为空）
 * @param ledger_len          上一轮账本长度（prompt 段 + 生成段；0 = 不知道）
 * @return 起点（恒为 0 —— 「落回第 0 枚」就是这一档的唯一落值）；-1 = 这一档不适用
 */
inline int startForWholeInPrev(int prompt, int prev_start, int prev_prefix_in_cur,
                               int ledger_len) {
    if (prompt <= 0 || prev_start <= 0) return -1;          // 没有基线：不用钉
    if (prev_prefix_in_cur < kvprefix::kMinReuseTokens) return -1;
    if (ledger_len < prev_prefix_in_cur) return -1;         // 账本装不下本轮的整段
    // ⚠ 必须是**整段**（`== prompt`），不是"至少比过一段"（`<= prompt`）。
    //
    //   写成 `<=` 时，`prev_prefix_in_cur 202 / prompt 1931` 这一档也会返回 0 ——
    //   于是起点被钉到 0，而第 202..1931 枚与上一轮账本**并不相同**（那一段根本没
    //   比过）。KV 里 `[0, 202)` 是对的，往后每一枚都错位，模型读到的是别人的历史。
    //   而它不崩、不报错、`reuse` 还很大 —— 正是这个文件最怕的那类坏法。
    //   真机探活正好落在这一档：`prev_prefix_in_cur 202 / prompt_real 1931`。
    if (prev_prefix_in_cur != prompt) return -1;            // 只比过一截：起点 0 会错位
    return 0;
}

/**
 * 「起点不是 0」这一档，KV 里 `[0, start)` 那一段**真的**是本轮的这 `start` 枚吗。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 它治的是**乱码**（ISSUE #577 Layer 2）
 * ══════════════════════════════════════════════════════════════════════════
 * 上面几条路（[canAlign] / [startForByLcp] / [alignStartShifted] / [startForWholeInPrev]）
 * 的判据全部只覆盖**起点之前**那一段：`canAlign` 判的是 `lcp >= prev_start`，
 * 而 `lcp` 是"前多少枚逐 token 相同" —— 它说得出 `[0, prev_start)` 对得上，
 * 说不出 `[prev_start, start)` 这一段（本轮**直接拿来用**的那几格）是谁的。
 *
 * 于是真机上出现了这种坏法：KV 里 `[0, start)` 还留着**上一轮**的内容，本轮
 * 从 `start` 起喂，`reuse = start` —— 位置全对、HTTP 200、不报错，而模型读到的
 * 前 `start` 枚是别人的历史。第三轮起吐词表碎片。
 *
 * 判据写成"**逐枚**比 `[0, start)`"：读得到的部分逐枚核，读不到（账本短于起点 /
 * 账本无效 / 起点越界）一律算**不过**。少一枚都不敢放行 —— 这条判据的失效形态
 * 是静默的，代价只有"退到全量 prefill"，反过来则是答非所问。
 *
 * ⚠ 起点为 0 时恒真，且**不读账本**（那一档没有"起点之后直接拿来用"这件事，
 *   见 [startFor] 的第一条：`keep >= prompt -> 0`）。所以这一条与"装得下起点
 *   恒 0"互补，而不是叠加品 —— 两条同时作用于同一档时，它们会互相打脸。
 *
 * @param start       本轮要声明的起点（0 = 一枚都不丢，恒真）
 * @param ledger_len  上一轮进 KV 的账本长度（prompt 段 + 生成段；0 = 没有账本）
 * @param same        账本与本轮 `[0, min(start, ...))` 逐枚相同的枚数
 *                    （调用方真的比过的那个数，-1 = 没法量）
 * @return true = 起点之后那几格可以用
 */
inline bool startCovered(int start, int ledger_len, int same) {
    if (start <= 0) return true;                  // 没有"起点之后直接拿来用"
    if (ledger_len <= 0 || same < 0) return false; // 账本无效 / 没比过：不敢放行
    if (ledger_len < start) return false;         // 账本装不下起点之前那一段
    return same >= start;                         // 逐枚比过，且盖住了 `[0, start)`
}

/** 一句话（日志与 /health 共用，判据本身不依赖字符串）。 */
inline std::string describeStart(int prompt, int keep, int prev_start, int start) {
    if (start <= 0) return "起点未动（" + std::to_string(prompt) + " tok 全进）";
    if (start == prev_start && prev_start > 0) {
        return "起点与上一轮对齐（" + std::to_string(start) + " tok）："
               "从尾部让掉 " + std::to_string(prompt - start) + " tok，前 " +
               std::to_string(start) + " 枚仍可复用";
    }
    return "起点后移到 " + std::to_string(start) + " tok（保留最近 " +
           std::to_string(keep) + " tok）—— 上一轮前缀接不上";
}

} // namespace kvlongchat

#endif // LOCAL_LLM_KV_LONGCHAT_H
