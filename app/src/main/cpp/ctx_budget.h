// ctx_budget.h —— 上下文预算的**纯逻辑**（宿主可编、可单测）
//
// ══════════════════════════════════════════════════════════════════════════
// 为什么单开头文件（而不是把这段写进 llama_jni.cpp）
// ══════════════════════════════════════════════════════════════════════════
// 「这一轮能装多少 / 装不下时丢几条 / 为什么没命中」这三件事，判据必须是**一处**：
//   · native 侧拿它去"从尾部重算 prompt"，并把结论落进这一格的账本；
//   · Kotlin 的 `ContextBudget` 用**同一套规则**算"发请求之前还装得下几条"
//     （两边各判一次必然漂移，而漂移的后果是客户端以为发了 6 轮、服务端只吃下 4 轮，
//     两边都不报错）。
// 所以规则只能有一份，且必须能在宿主上编译（没有 llama.h）。这里用 `long long`
// 表示 token id，与 kv_prefix.h 同一条理由。
//
// 与 kv_prefix.h 的分工：那边算"KV 里能留多少"（复用），这边算"这一轮能装多少"
// （预算）。共同前提是**同一串 token**，所以谁也别自己再 tokenize 一遍。
#ifndef LOCAL_LLM_CTX_BUDGET_H
#define LOCAL_LLM_CTX_BUDGET_H

#include <algorithm>
#include <cstddef>
#include <string>
#include <vector>

#include "ctx_watermark.h"
#include "kv_longchat.h"

namespace ctxbudget {

/**
 * 生成 token 的**保底**空间，同时也是"最后一枚 token 要留给采样"的口径
 * （`step_impl` 里的判据就是 `n_used + 2 >= n_ctx`）。
 *
 * 为什么不是 0：`n_ctx` 是 prompt 与生成**共用**的一池位置。prompt 把池子填满时，
 * 下一步的判据立刻成立 —— 调用方会看到"一步都没取就结束"，而那表现为
 * "客户端拿了个空回答、finish_reason 报 stop"。所以这一格必须被显式留出来。
 */
const int kMinGenTokens = 2;

/**
 * 水位上限的缺省值（%）—— **转引** `ctxwatermark::kDefaultWatermarkPct`，不在这里
 * 另写一份（`kv_longchat.h` 也要用它，而它不能被本文件 include：本文件已经
 * include 了 `kv_longchat.h`，反过来的 include 就是循环依赖）。
 *
 * 为什么必须同源：两处各写一份时，一边按 85% 裁、一边按 100% 算 —— 两边都不报错，
 * 只是 native 那一刀又落回来（把 Kotlin 好不容易稳住的 KV 前缀起点每轮往后推一格）。
 */
const int kDefaultWatermarkPct = ctxwatermark::kDefaultWatermarkPct;

/** 水位可选项（%）—— 同样转引（与 Kotlin 的 `ContextWatermarkPct.LADDER` 同值同序）。 */
const int * const kWatermarkLadder = ctxwatermark::kWatermarkLadder;
const int kWatermarkLadderCount = ctxwatermark::kWatermarkLadderCount;

/**
 * 越线那一刀的落点：丢完之后 `prompt + gen` 占水位线的**多少**（%）。
 *
 * 与 Kotlin 的 `ContextBudget.HALF_RATIO_PCT` **同值**（守卫钉着）。这条线回答"丢到哪"，
 * 不回答"什么时候丢"（后者是 `avail − gen` 那条起判线）：历史涨到起判线那一刻动手，
 * 丢完则落到 `avail × kHalfRatioPct` 上，离起判线留出一大截 —— 那就是"丢一次管几轮"。
 *
 * ⚠ 调小它 = 历史留得更少、两次动手之间更长；调大它 = 留得更多、动手更频繁。
 *   这是唯一的一条取舍，别再从 `max_tokens` 那一侧绕回去（ISSUE #586 第三回合）。
 */
const int kHalfRatioPct = 50;

/**
 * 一轮的预算结论。三个字段各自对应一种用户看得见的现象，缺一个就归因不了：
 *   · `prompt` 变小 = 「我说的事它怎么忘了」（丢了最老的轮次）；
 *   · `gen` 变小    = 「怎么话说到一半就断」（截到剩余空间）；
 *   · `trimmed`     = 「我明明发了 8 条，它只当 4 条」（上下文装不下）。
 */
struct Plan {
    /** 真正能喂进 prefill 的 prompt token 数（从尾部倒着算，装多少算多少）。 */
    int prompt = 0;
    /** 这一轮实际允许生成的 token 上限（≤ 调用方要的 max_tokens）。 */
    int gen = 0;
    /** 为装下 prompt 而**丢掉**的 token 数（0 = 一个都没丢，与今天逐字节一致）。 */
    int trimmed = 0;
    /** 为装下而**削掉的生成长度**（`max_tokens - gen`；0 = 一个都没削）。 */
    int gen_trimmed = 0;
    /** prompt 本身已经不可能装下（连保底生成空间都留不出）。 */
    bool overflow = false;
    /**
     * prompt 已按 Tokenizer 的**真 token 数**从头部丢到能装下 —— 调用方**不要**再说
     * 「装不下、请调大上下文长度」（ISSUE #330 真机：客户端一边收 400，一边看着
     * 服务端自己把老轮次丢掉了）。
     *
     * 与 [overflow] 是互斥的两档：前者 =「我丢了一刀，这一轮照样能答」，
     * 后者 =「丢到只剩 1 tok 也塞不进去，这一轮注定失败」。
     */
    bool prompt_trimmed = false;
    /**
     * 这一轮的生成空间**没兑现地板**（`gen < kGenFloor`）—— 那一轮注定很短。
     *
     * 它存在是因为 `gen` 这个数**看不出**这件事：削到 64 与削到 2 都是"生成上限"，
     * 而后者在真机上表现为"模型只吐了一两个字"，然后那条空回答被客户端落盘。
     * 归因时读的是**同一个数**，所以"这一轮为什么这么短"必须单独说得出。
     */
    bool gen_floor_missed = false;
    /**
     * 丢完之后还剩多少**余量**（`avail - prompt - gen`；≈0 = 尾巴顶在边界上）。
     *
     * ⚠ 这一格不改任何落值，它只回答"下一轮还能不能接上"：余量为 0 意味着
     *   这一轮把位置用到了最后一枚 —— 下一轮历史只要再长，就必然再丢一次，
     *   起点每轮往后挪一步、KV 前缀每轮断。而每一格读数看着都合法
     *   （`prompt_trimmed > 0`、`reuse > 0`），所以这件事必须有一个数说出来。
     *
     * 真机口径（每格 4096 / max_tokens 1024 / 历史每轮涨约 300）：
     *   `spare == 0` 的那些轮，`start` 每轮 +300，`reuse` 每轮都在但都接不上。
     */
    int spare = 0;
    /**
     * 这一轮用的水位（%; 0 = 水位未参与判据 —— `ctx <= 0` 那条早退分支）。
     *
     * 它必须报出来：水位是**跨轮**行为的唯一解释变量（"多久丢一次"），而
     * `prompt` / `gen` / `trimmed` 三个数在不同水位下可以长得一模一样。
     */
    int watermark_pct = 0;
    /**
     * 这一轮的丢弃是**按水位丢一半**（ISSUE #426 方案 B）：`prompt_trimmed` 只说明
     * "丢过"，而这个字段说明**下一轮会不会再丢**。
     *
     * 两件事在读数上完全同形，处置却相反：
     *   · `dropped_to_watermark = true` -> 这一轮丢完回到了落点线（`avail × 比例`），
     *     后面几十轮不动；
     *   · `false` 而 `prompt_trimmed = true` -> 贴线丢到刚好装下，下一轮几乎必然再丢。
     *
     * ⚠ 它**不进 /health**：这一格曾经是"`trimmed > 0` 且 `prompt + gen <= avail`"，
     *   而走到这里时另一支已经保证 `<= avail`（否则就 overflow 了）—— 于是它恒等于
     *   `trimmed > 0`，与 `prompt_trimmed` 共线。共线的格子比不报更糟：读的人一定会
     *   挑那个名字最像结论的，而它在真机上一格信息量都没有。
     *   真正要回答的两件事由 [drop_room] 与 [drop_overshoot] 各报一格。
     */
    bool dropped_to_watermark = false;
    /**
     * 丢完之后离**水位线**还剩多少（`avail - prompt - gen`；0 = 尾巴顶在水位上）。
     *
     * 「丢一次」与「每轮都丢」的分界线。它回答的是"**下一轮**还接不接得上"：
     *   · `drop_room > 0` -> 历史还能长这么多枚才会再越线（几十轮的尺度）；
     *   · `drop_room == 0` -> 下一轮必然再丢，起点每轮后移一步。
     *
     * ⚠ 与 [Plan::spare] 不是同一件事：`spare` 量的是**整格**（`ctx - 2`），
     *   这一格量的是**水位**。水位 85% 的那一轮 `spare` 可能有几百枚，而
     *   `drop_room` 是 0 —— 前者说"离墙还远"，后者说"已经贴着水位、下一轮就要丢"。
     *   报 `spare` 还不够：它看着像"还有余量"，而用户问的正是"为什么还在丢"。
     */
    int drop_room = 0;
    /**
     * 这一次比"这一轮活下来所必需的"多丢了多少（`cut1 - mustTrim`；0 = 一枚都没多丢）。
     *
     * 它回答的是"这一刀丢得**值不值**"：多丢的每一枚都是**主动**忘掉的记忆，
     * 换来的是"下一轮不用马上再丢"。没有这个数时，`trimmed` 一大就只能猜是
     * "容量不够"还是"水位在主动收紧"—— 而两者的处置完全相反（调 ctx vs 调水位）。
     *
     * 越线那一轮它与 `drop_room` 一起读：多丢了 208 枚、换回 208 枚余量。
     */
    int drop_overshoot = 0;
    /**
     * 这一轮判据用的**估算系数**（×1000；1000 = 没校准）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这一侧也要有这一格（ISSUE #452 ⑤）
     * ══════════════════════════════════════════════════════════════════════════
     * 校准做在**宿主那侧**（宿主没有 tokenizer，估算只能按字符折算，而它必然偏高
     * 一点：真机 `prompt_real 2649 / prompt_est 4036`）。这一侧按**真 token** 判，
     * 不需要也不该用系数 —— 但 `/health` 上 `prompt_real` 与 `prompt_est` 是并排报的，
     * 而校准之后 `prompt_est` 是**乘过系数**的数：同一个真值可能配 2825（系数 1.0）
     * 或 1977（0.7），差值的解读一正一负、处置完全相反。
     *
     * 所以系数必须跟着**同一份账本**走出去。它不参与任何判据（`plan()` 收下它只为
     * 原样带出），与 `spare` / `drop_room` 同一条纪律。
     */
    int estimate_scale_x1000 = 1000;
};

/**
 * 「这一轮丢完之后还剩多少余量」—— `avail - prompt - gen`，且不小于 0。
 *
 * 判据是**余量**而不是"丢没丢"：真机上「丢了一次」和「每轮都在丢」都能报出
 * `trimmed > 0`，而只有余量说得清下一轮接不接得上。余量为 0 的那一轮等于
 * "这一轮把位置用到了最后一枚"，下一轮历史再长一枚就得再丢一枚。
 *
 * ⚠ 它**不参与任何判据**（不改 `prompt` / `gen` / `trimmed`），只让 `/health`
 *   与日志能说清"为什么这一轮接上了、那一轮没接上"。今天这个洞恰好在
 *   "每一格读数都合法"的地方：`prompt_trimmed` 有值、`reuse` 有值、`start`
 *   = `start_prev + 300`，而没有任何一个数说得出"它每轮都要重算"。
 */
inline int spareOf(int prompt, int gen, int ctx) {
    const int avail = ctx - kMinGenTokens;
    const int s = avail - prompt - gen;
    return s > 0 ? s : 0;
}

/**
 * 位置上限：这一轮**还能生成几枚**（`n_ctx - 2 - n_used`，且不小于 0）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么它必须与 [Plan::gen] 分开算（ISSUE #330 真机）
 * ══════════════════════════════════════════════════════════════════════════
 * `Plan::gen` 回答的是"prompt 与生成怎么分这一池位置"，而真正拦住生成的是
 * `step_impl` 入口那条 `n_used + 2 >= n_ctx`。两者只在 prompt 贴着容量线时相等，
 * 一旦有余量就分叉 —— 而**分叉的那一侧才是事实**：
 *
 * 真机第 3 轮（每格 4096 / 请求 max_tokens 1024 / prompt 2377）：
 *   `gen_cap 1024`（预算一分没削）、`spare 693`（还有 693 格空位），
 *   而实际只生成了 **99** 枚就收尾 —— `n_used` 停在 2476，位置只走了一小段。
 *   这一轮卡住的既不是预算也不是位置：**"还想要、但位置不够"这件事根本没人算**。
 *   `step_impl` 判"跑满"只看 `n_rem`，判"位置没了"只看 `n_used + 2 >= n_ctx`；
 *   两条线各判各的，于是 `min(n_rem, wall)` 这个真正的上限从来没有一个数表示。
 *
 * 所以报这一格，并与 `n_rem` 并排读：`min` 两个才是"这一轮实际能给多少"。
 *   · 只报 `n_rem` -> "请求 1024、实际给 99、KV 还空着 1618 格" 三件事同时为真，
 *     而 `/health` 上每一格都合法，读的人只能回去猜；
 *   · 用 `Plan::gen` 冒充它 -> 那是**预算**的数，与位置无关，方向还更乐观。
 */
inline int stopWallOf(int n_used, int ctx) {
    if (ctx <= 0) return 0;
    const int wall = ctx - kMinGenTokens - (n_used > 0 ? n_used : 0);
    return wall > 0 ? wall : 0;
}

/**
 * 归一 —— **转引** `ctxwatermark::watermarkNormalize`（判据本体在 `ctx_watermark.h`）。
 *
 * 留这一个名字是为了让 `ctxbudget::` 这一侧的调用点读起来仍然只有一个来源
 * （`ctxbudget::watermarkNormalize` / `kvlongchat::planKeep` 的默认值都走它）。
 * 转引而不是复制：两份实现迟早漂，而漂的那一侧永远是"设置页 85%、判据按别的"。
 */
inline int watermarkNormalize(int pct) { return ctxwatermark::watermarkNormalize(pct); }

/**
 * 「丢完之后离水位还有多远」—— `min(ctx × 水位, ctx - 1) - prompt - gen`，且不小于 0。
 *
 * 与 [Plan::drop_room] 同一个数，抽出来只为让单测与守卫能各自钉一遍（两处各算一遍
 * 迟早漂：一处按整格、一处按水位是最常见的错法 —— 症状是"还有余量"却每轮再丢）。
 */
inline int dropRoomOf(int prompt, int gen, int ctx, int watermark_pct) {
    if (ctx <= 0) return 0;
    const int wm = watermarkNormalize(watermark_pct);
    const int room = ctx - 1;
    if (room <= 0) return 0;
    const int avail = std::min((int) ((long long) ctx * wm / 100LL), room);
    const int r = avail - prompt - gen;
    return r > 0 ? r : 0;
}

/**
 * 算一轮的预算。
 *
 * @param prompt_tokens 整段 prompt 的 token 数（调用方刚 tokenize 出来的）
 * @param max_tokens    调用方要的生成上限
 * @param ctx           **这一格**的 n_ctx（多 slot 时是切分后的每份，不是总量）
 * @param watermark_pct 水位（%）
 * @param seq_cap       **这一条 seq** 的容量（调用方传 `seq_ctx_limit`，问库的
 *                      `llama_n_ctx_seq`；0 = 没量到 / 不参与，按 `ctx` 判）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么"这一格有多大"与"这一条 seq 能排到几号"是两个数（ISSUE #426 真机）
 * ══════════════════════════════════════════════════════════════════════════
 * 两路（App / HTTP）各占一条 seq，而库里每条 seq 的容量是 `n_ctx / n_seq_max`
 * ——**不是** `n_ctx`。同一格上 `n_ctx=4096 / n_seq_max=2` 时：预算按 4096 放行，
 * prefill 按每条 seq 排号，`prompt=2056` 的那一轮写到后面就要用位置号 2048 起，
 * 而 2048 已经落在这一条 seq 的区间（0..2047）之外 —— 库当场拒绝：
 * `[diag] FAIL: prefill decode rc!=0 at offset 2048`。
 *
 * 这一行看起来像"KV 坏了"，实际是预算与排号用了两把尺子，而且是**静默**的：
 * 每一格读数都合法（`prompt_trimmed 0`、`spare 1014`、`gen_cap 1024`），
 * 因为 `spare` 量的是整格。所以容量必须以**这一条 seq** 为准。
 *
 * `ctx` 与 `seq_cap` 的关系就是**取小**：`seq_cap` 是这一条 seq 能排的位置数
 * （位置号 `0..seq_cap-1`），`ctx` 是整格的总量。每格只开一条 seq 时两个数相等
 * （多格池的常态），这就是"与改动前逐字节相同"的那一档。
 *
 * 硬约束两条：`prompt + gen <= avail`（水位与位置两条线取小），且 `gen >= kMinGenTokens`。
 *
 * 装不下时的让法，**顺序不可换**（#330 回归根因）：先把 `gen` 削到保底
 * （`max_tokens` 是上限、不是合同，削它零副作用），仍然装不下才从头部丢 prompt
 * （保留尾部 = 最近的对话）。反过来先削 prompt，就会每轮砍在同一处，
 * 把 KV 前缀复用砍没 —— 表现为"复用一百多、新算一千多"。
 */
inline Plan plan(int prompt_tokens, int max_tokens, int ctx,
                 int watermark_pct = kDefaultWatermarkPct,
                 int seq_cap = 0,
                 int estimate_scale_x1000 = 1000) {
    Plan p;
    // 估算系数**原样带出**（ISSUE #452 ⑤）：这一侧按真 token 判，用不上它 ——
    // 它只是宿主那侧判据的输入，而 `/health` 要把"那个输入是多少"与结论并排报。
    // ⚠ 不在这里钳位、不在这里参与任何比较：两处各钳一遍就是两把尺子
    //   （钳位与滞回只在 `ContextBudget.PromptCalibration` 一处）。
    p.estimate_scale_x1000 = estimate_scale_x1000 > 0 ? estimate_scale_x1000 : 1000;
    p.prompt = prompt_tokens > 0 ? prompt_tokens : 0;
    p.gen = max_tokens > 0 ? max_tokens : 0;
    if (ctx <= 0) return p;   // 上下文未知（未加载）：不在这里判，交给旧行为
    // ── 「这一格」与「这一条 seq」是两个数（ISSUE #426 第四回合）──────────────
    //
    // 库里 `n_ctx_seq = n_ctx / n_seq_max`：位置号只能排到**这一条 seq** 的容量，
    // 而 `ctx` 是这一格**所有 seq 加起来**的总量。单格池里 App 与 HTTP 共用那一档
    // （两条 seq）拿 `ctx` 判，会让 2056 tok 的 prompt 一分不丢地放行，然后在
    // `offset 2048` 上被库拒掉 —— 每格读数都合法（`prompt_trimmed 0`），
    // 只有那行 `decode rc!=0` 说真话。
    //
    // 归一在这里做一次：`seq_cap <= 0`（量不到 / 单 seq）一律落回 `ctx`，
    // 于是"一条 seq 就是一格"的那一档（多格池的常态）逐字节不变。
    const int cap = (seq_cap > 0 && seq_cap < ctx) ? seq_cap : ctx;
    // 归一到最接近的档位（与 Kotlin `ContextWatermarkPct.normalize` 同一条判据）：
    // 档位之外的数（83）在手感上与 85 没有区别，而它会让"设置页显示一个值、
    // 判据按另一个值算"这类漂移无从发现。
    p.watermark_pct = watermarkNormalize(watermark_pct);
    // ① 生成空间先按整格容量钳一刀：`max_tokens` 是调用方的意愿，不是保证 ——
    //    当它比整格还大时（客户端把 max_tokens 也设成 ctx 的常见情形），
    //    再怎么让也放不下，只能钳。留 1 个位置给 prompt（否则没有可续写的上下文）。
    const int room = cap - 1;
    if (room <= 0) {
        p.overflow = true;
        p.trimmed = p.prompt;
        p.prompt = 0;
        p.gen = 0;
        return p;
    }
    if (p.gen > room) p.gen = room;

    // ② 装不下时**先削 gen，再削 prompt**。
    //
    // ⚠ 这个顺序是 ISSUE #330 的回归根因，反过一次：以前写的是"先削 prompt"，
    //   理由是"避免话说到一半就断"。但被削的 gen 是**上限**（客户端愿意接受多长），
    //   而 prompt 是 **KV 复用的账本** —— 削上限不会让话断，削 prompt 一定断缓存，
    //   而且断在**每轮相同的那条线**上：prompt 一旦越过 `ctx - max_tokens`，
    //   之后每一轮都被砍在同一处，公共前缀永远停在被砍的那一刀上，
    //   于是"复用一百多、新算一千多"。用户看到的就是"修完 ctx 之后复用没了"。
    //
    // 让的顺序：先削 gen 到保底（这一步零副作用），还不够才动 prompt。
    //
    // ── 水位上限（ISSUE #426 方案 B）─────────────────────────────────────────
    // `avail` 此前是 `ctx - kMinGenTokens`（把整格吃满，只给采样留 2 枚）。
    // 那是"能装多少装多少"，代价是**每轮都贴着容量线跑**：历史每涨几百 token
    // 就得再砍掉一段，起点每轮后移 —— 真机读数 108 → 408 → 708 就是它。
    //
    // 现在把可用空间压到 `ctx × 水位`（默认 85%）。水位是**目标**不是硬墙：
    // 丢光了历史仍装不下时，最后一档（只留尾部提问）按整格 `avail_hard` 判 ——
    // 否则 ctx 4096 + 水位 85% 会把一条 3600 tok 的提问判成装不下，而它明明进得去。
    const int avail_hard = cap - kMinGenTokens;
    const int avail_wm = (int) ((long long) cap * p.watermark_pct / 100LL);
    // ⚠ 不许把 `avail` 抬到下限之上：`avail <= 0` 是"这一格连保底生成空间都留不出"
    //   的唯一判据（下面那个 `if`），抬一下就把那件事抹掉了 —— 而它恰好是最该 400
    //   的那一档（ctx=2 时会算出 avail=2、prompt 1 + gen 1 "装得下"，然后 native
    //   撞墙报一句谁也看不懂的话）。水位只是**收紧**它，不负责给它兜底。
    const int avail = std::min(avail_wm, room);
    if (p.prompt + p.gen > avail) {
        int over = p.prompt + p.gen - avail;
        // ⚠ 让位**只让到地板**，不是一路让到 2（ISSUE #330 长对话收口）。
        //
        // 真机读数（每格 4096 / max_tokens 1024）：跨过容量线之后 gen 是这样被吃的
        //   794 → 494 → 194 → 2 → 2 …
        // 也就是说"越过线"这件事是由**空回答**来表现的（gen=2 时 `step_impl` 第一步
        // 就满足 `n_rem <= 0`）。而那条空回答会被客户端落盘，下一轮历史里夹一条
        // `assistant("")` —— 用户说的"一超过上下文就开始胡说"就是它。
        //
        // 所以 gen 只让到 [kvlongchat::kGenFloor]（约一两句话），剩下的缺口交给 prompt
        // 那一侧：宁可这一轮少看几轮历史，也要保证这一轮有能读的回答。
        // ⚠ 地板是"这一轮至少有得读"的目标值，**不是**硬钳：prompt 本身装不下时
        //   下面的 ③ 会兑现它，兑现不了（prompt 只剩最小几枚）才让它降级 —— 降级与否
        //   由 `gen_floor_missed` 报出来，而不是让 `over` 悄悄留着去归零。
        const int floorCap = std::min(kvlongchat::kGenFloor, room / 2);
        const int genFloor = std::max<int>(kMinGenTokens, floorCap);
        // 让位**之前**的生成长度（意愿值）。`p.gen` 在这一跳之后就不再是它了，
        // 而"这一轮本来会占多少"（`prompt + gen`）只能用这个数算 —— 见下面 `toHalf`。
        // 水位量的正是 `prompt + gen` 这条线，两者必须同一个 gen，否则减半的基数
        // 比水位线小一截（`prompt + genFloor`），同一轮里多丢一倍历史。
        const int genWant = p.gen;
        const int cut2 = std::max(0, std::min(over, p.gen - genFloor));
        p.gen -= cut2;
        over -= cut2;
        // 生成空间已到地板仍然装不下：这时才从头部丢 prompt（保留尾部 = 最近的对话）。
        p.gen_trimmed = cut2;
        // 「这一轮**够不够**」按 `prompt + genFloor` 判，而不是 `prompt + gen`（削过之后的）。
        //
        // ⚠ 这一行是 ISSUE #452 真机「裁剪之后只吐几个字」的直接成因。上一版的判据是
        //   `over > 0`（= 削完 gen 之后 `prompt + gen` 仍越过水位），于是：
        //     · 真 token 3300（`avail` 3481）：gen 1024→181，`0 + 181 <= 3481` -> **不丢历史**；
        //     · 真 token 3600（刚越线）：gen 1024→64，`prompt + 64 = 3664 > 3481` -> 丢一大段；
        //   两条线之间只差 15 枚 prompt，落点却差了 1800 多枚历史。而"按落点线丢"这一刀本来
        //   就该在**贴线**的时候动手（丢一次、之后几十轮不动），不该在"只多 15 枚"时
        //   先按缺口丢一遍、再按落点线丢第二遍。
        //   按 `prompt + genFloor` 判：只有"gen 让到地板之后仍然放不下这一轮的基本回答"
        //   才值得把历史砍成一半。真机上这等于把那一刀往后推约 120 枚（`1024 - 64 - 2`），
        //   同时不再在"刚越线"的那一轮多丢 400 枚（`drop_overshoot`）。
        const bool stillTight = p.prompt + genFloor > avail;
        bool halved = false;
        if (over > 0 && stillTight) {
            halved = true;
            // ⚠ 丢 prompt 只消掉"与**地板**冲突"的那部分缺口。以前一步丢 `over`
            //   （= 把这一轮想要的生成长度也当成用量），于是 gen 掉到地板的那几轮
            //   prompt 也被多砍一刀 —— 而那一刀正砍在复用的头上（起点后移、前缀
            //   永久丢一段，真机 108/408/708）。剩下的缺口回到 gen 头上，
            //   地板在这里是**读数**不是硬钳（硬钳会让 `prompt + gen > ctx` 越界）。
            // ⚠ 名字与常量都搬到 `kvlongchat` 了（ISSUE #577 Layer 4）：
            //   "丢头至少留几枚"与"至少给多长回答"（`kGenFloor`）此前共用一个数，
            //   于是其中一个方向的那一档永远走不到。现在它们是两个常量。
            const int promptKeepFloor = kvlongchat::promptKeepMin;
            // 缺口里"必须由 prompt 让掉的部分"：`prompt + 地板 > avail`，
            // 也就是"再不让 prompt，这一轮就只能吐几个字"（地板在 kv_longchat.h）。
            // 其余缺口一律由 gen 让 —— 它只是**上限**，让多少都不影响前缀连续性。
            //
            // ⚠ 这里用的是**兑现得了的**地板 `genFloor`，不是常量 `kGenFloor`：
            //   窄格上 `genFloor` 被 `room / 2` 钳小（ctx 100 -> 49），而常量仍是 64。
            //   拿常量当"这一轮必须有"的底座，会把 15 枚**根本不存在**的地板算成缺口 ——
            //   `mustTrim` 于是偏大 15，`drop_overshoot` 被它吃成 0，`trimmed` 多丢 15 枚
            //   （ctx 100 那一轮：179 而不是 164，丢完还没贴着水位、余量剩 15）。
            //   症状是"读数说一枚都没多丢，实际却多丢了"—— 两格互相打架。
            //   地板是**目标**，兑现不了的那一截不算缺口（那正是 `gen_floor_missed`
            //   要报的事），所以取 `min(genFloor, kGenFloor)` 且不低于保底生成空间。
            const int minPrompt = std::max<int>(kMinGenTokens,
                                                std::min<int>(kvlongchat::kGenFloor, genFloor));
            // 「活下来所必需的缺口」= `prompt + 地板 - avail`：保住地板之后仍越线的部分。
            // ⚠ 它可能**大于** `over`（`over` 是削完 gen 之后的缺口，而地板比削完的 gen
            //   更宽）—— 那时 `promptOver` 就是这一轮真正要由 prompt 让掉的量。
            const int mustTrim = std::max(0, p.prompt + minPrompt - avail);
            // `mustTrim` 就是"这一轮活下来所必需由 prompt 让掉的量"：它可能**大于**
            // `over`（`over` 是削完 gen 之后的缺口，而地板比削完的 gen 更宽）——
            // 小于 `over` 的那一截由 gen 让（它只是上限）。
            const int promptOver = mustTrim;
            // ── 越线就丢到**水位的一半**，而不是"丢到刚好装下"（ISSUE #426 方案 B）──
            //
            // 用户原话：「超限后，之后生成的内容直接都没关系了」。机制不是丢得多，
            // 而是**每轮都在丢**：丢到刚好装下的那一轮之后，历史再涨几百 token
            // 就又越线，起点每轮后移一次，模型每轮看到的历史都是新的一段。
            //
            // 所以这里在一次丢到位的基础上再多丢一点：让 `prompt + gen` 落在
            // **水位的一半**（`avail/2`）—— 等价于"留下的 prompt 再涨一格、
            // `gen` 再涨一格"才又越线，也就是十几二十轮的尺度。
            //
            // ⚠ 水位是「`prompt + gen`」的线，减半也要减**这整条**（见下面 `toHalf`
            //   那一段）：只把 prompt 砍到"自己的一半"，剩下的 `gen` 那一截还能撑，
            //   于是同一轮里既丢了一倍的历史、又没换回"下次别再丢"。
            //
            // ⚠ 只**多丢**、不**少丢**：`want` 是两者的较大值（`promptOver` 是"这一轮
            //   活下来所必需的"，`toHalf` 是"下次别再丢"所希望的）。取小值会让这一轮
            //   装不下 —— 那是 400，比多忘几轮严重得多。
            // ⚠ 下限 `promptKeepFloor` 不动：丢到只剩 1 枚就等于"这轮没有上下文"。
            // ⚠ 落点算错过两次，两次都是"基数里那个 `gen` 不对"：
            //     · `prompt + 64`（让位**之后**的 gen）—— 它把"留下多少历史"算成
            //       "prompt 自己的一半"，同一轮里多丢一倍（ISSUE #488 真机：
            //       prompt 3700 只留 1882，而按水位该留 2457）；
            //     · `(prompt + gen) / 2` —— 基数对了一半（用意愿值），但**目标算错**：
            //       那是"这一轮本来会占多少"的一半，不是"水位线的一半"。于是丢完仍
            //       贴着水位线，下一轮历史涨几百枚就又越线（ISSUE #503 真机连着
            //       `丢最老 12 条` → `丢最老 14 条`，每轮 `复用 202 / 新算 1156`）。
            //   现在只认一条：**丢完之后 `prompt + gen` 落在 `avail × kHalfRatioPct`**
            //   （ISSUE #586 第三回合：落点由**占用比**定，不由 `max_tokens` 定）。
            //
            // ⚠ 落点本体在 `kvlongchat::halfWatermarkKeep`（宿主单测钉住）：这里只调用，
            //   不再自己算一遍 —— 两处各算一遍时，"落点跟不跟着 `max_tokens` 走"
            //   这件事就会在一处修好、另一处照旧（真机：`max_tokens 1024` 与 `64`
            //   两档丢得一样多，而落点本该是比例那条线）。
            //
            //   丢完之后 `prompt + gen` 落在比例线上：
            //     · 再丢一次得先让历史涨到起判线（`avail − gen`），那是几十轮的余量；
            //     · `gen` 只是在目标里**占位** —— `max_tokens` 越大，留下的 prompt 越小；
            //       而"两次动手之间隔多远"由比例本身定，不被 `max_tokens` 牵着漂。
            // ⚠ 第二个实参是**这一轮的 gen**（让位之后：`p.gen`），不是 `genWant` ——
            //   "丢完之后 `prompt + gen` 落在比例线上"这句里的 `gen` 是**真的会用**
            //   的那个数。拿意愿值算，落点就跟着 `max_tokens` 走，而它不该。
            const int halfKeep = kvlongchat::halfWatermarkKeep(
                p.prompt, p.gen, cap, p.watermark_pct, kHalfRatioPct);
            const int toHalf = halfKeep > 0 ? std::max(0, p.prompt - halfKeep) : 0;
            const int want = std::max(promptOver, toHalf);
            const int cut1 = std::min(want, std::max(0, p.prompt - promptKeepFloor));
            p.prompt -= cut1;
            p.trimmed = cut1;
            // 「这一轮必需」与「按落点线多丢」的差额记下来：`promptOver` 是**这一段里**
            // 这一轮活下来所必需的（`prompt + 地板 - avail`），`cut1` 里超出它的那截
            // 就是主动多忘的。⚠ 与旧版的差别在这一格：旧版拿 `over`（削完 gen 之后的
            // 缺口）当"必需的"，于是"越线但还塞得下地板"的那一轮会被记成
            // `drop_overshoot = 0`，而实际上它丢了 400 多枚本来不必丢的历史。
            // ⚠ 这里只记"这一段交给 prompt 的缺口"，最终值在出口那段收口 ——
            //   后面 ③ 还会再补几刀，而 ③ 让掉的属于"必需"，不是"多丢"。
            // ⚠ 先清零再赋值：上面「先让 gen」那一段可能已经写过一格（`cut2`），
            //   而那与"多丢历史"无关 —— 漏了这一句会把削掉的 gen 也算成多丢的回忆。
            p.drop_overshoot = std::max(0, cut1 - promptOver);
            // `over` 只减"必需的"那部分：多丢出去的是**主动忘掉的**，不是缺口。
            // 两者混在一起会让下一段的 `over` 变成负数，而负数在那里等价于 0 ——
            // 这次恰好不出错，但读的人会以为"缺口被丢 prompt 补掉了"。
            over -= std::min(over, cut1);
            // 剩下的缺口**回到 gen 头上**（它只是上限，让多少都不影响前缀连续性）：
            // `prompt` 已经一动不动地留住了 `cut1` 之后的长度，而复用的命就是它。
            // 地板在这一步可能被破（那一轮注定短），`gen_trimmed` 与 `/health` 的
            // `gen_cap` 会如实报出来 —— 地板是**读数**，不是硬钳（硬钳会越界）。
            //
            // ⚠ `cut3` 先把 gen 让到**地板**为止：剩下的缺口才是"地板被破"
            //   （那一轮注定短，读数会报出来）。这一步以前是 `min(over, p.gen)`，
            //   而 `over` 里含着"本该由 prompt 吸收"的那部分 —— 于是一步把 gen 削到 2，
            //   地板刚在上一段生效，这里又被抹平了。
            if (over > 0) {
                const int give = std::max(0, p.gen - genFloor);
                const int cut3 = std::min(over, give);
                p.gen -= cut3;
                p.gen_trimmed += cut3;
                over -= cut3;
            }
        }
        // 没走到"落点线"那一档（`stillTight == false`：gen 让到地板之后这一轮其实
        // 塞得下）—— 那么这一段一枚历史都不该丢，`drop_overshoot` 也必须是 0。
        // ⚠ 这一句救的是旧版那个读数：`drop_overshoot` 记的是"多丢了多少"，而
        //   "一枚都没多丢"与"这一档根本没进来"必须同值（0）—— 否则 `/health` 上会
        //   出现"它的 overshoot 比删掉的那段历史还大"，读的人只能怀疑读数坏了。
        if (!halved) p.drop_overshoot = 0;

        // ── ③ 还差的那些缺口**当轮就让到位**（本轮的判据就是靠这一段成立的）───────
        //
        // ⚠ 这一段是「多轮对话之后每一轮都 400」的根因（ISSUE #426 真机读数：
        //   「约 4100 tok 的 prompt 塞不进 ctx 4096（要给生成留 2 tok）」）。
        //   上面两段让位都带**下限**：gen 只让到地板（64）、prompt 只让到"与地板冲突"
        //   的那部分 —— 而这两条下限**未必同时兑现**：prompt 顶在 `avail - 64` 附近时
        //   `mustTrim` 是个位数（`cut1 = over = gen - 64` 就到顶了），于是 `over` 留着
        //   一截。旧判据只看 `over > 0`，于是**剩下的往往只有几枚**也照样归零 -> 400。
        //
        //   现象的形状很固定：越聊越长 -> 某一轮起每轮都 400；而缺的量太小，
        //   小到"调大 ctx"或"调小 max_tokens"都能让它时有时无 —— 所以从读数上
        //   一直像是用户在调参，而不是服务端留了一口气没让。
        //
        //   让的次序在这一段是**反过来**的（与 ② 不同，这不是笔误）：剩下的缺口先由
        //   **prompt 尾部**让 —— 它的下限是 1 枚、永远让得动，让掉的又是**最老的那
        //   一段**（正是该忘的历史）；`gen` 只在地板之下还有余地时才跟着让（它只是上限）。
        //
        //   为什么 ② 里反过来（先 gen、后 prompt）、这一段又正回来：② 那时的缺口
        //   是**整段**的（往往上千枚），多砍 prompt 就是多断一截复用；而走到这里时
        //   前面两段已经把"该由 gen 让的"让完了，剩下的通常只有几枚 —— 那时
        //   "这一轮能不能答"比"复用少几枚"重要得多。
        //
        //   两个下限都碰到底才归零 —— 而 `avail <= 0`（ctx 小到连保底生成空间都
        //   留不出）是唯一"再怎么让也装不下"的那一档。
        if (over > 0) {
            const int byPrompt = std::min(over, std::max(0, p.prompt - 1));
            p.prompt -= byPrompt;
            p.trimmed += byPrompt;
            over -= byPrompt;
            if (over > 0) {
                const int byGen = std::min(over, std::max(0, p.gen - kMinGenTokens));
                p.gen -= byGen;
                p.gen_trimmed += byGen;
                over -= byGen;
            }
        }
        // 「多丢了多少」= 这一段交给 prompt 去补的缺口（`drop_overshoot` 此刻存的是
        // 那个值）减去**残余**缺口 —— 丢完还剩的缺口是后面 ③/让 gen 吃掉的，
        // 那部分不是"主动多忘的"，不能算进这一格。
        //
        // ⚠ 必须在这里收口，不能在当时那一行写 `cut1 - promptOver`：`promptOver`
        //   与 `cut1` 之间的差里还混着"后面还会由 gen 让掉"的那一截（实测
        //   4300/1024/4096 那一轮会算成 1363，而真正的多丢是 1299 —— 差的 64
        //   正是随后又被让掉的 gen）。差值要拿**最终的** `over` 算才自洽。
        p.drop_overshoot = std::max(0, p.drop_overshoot - over);
        // 地板被破（那一轮注定短）：如实报出来，调用方据此说"这一轮回答会很短"，
        // 而不是让它表现为"模型只吐了一两个字"。
        p.gen_floor_missed = p.gen < genFloor;
        // 「这一轮是按水位丢的」= 丢完之后**回到了水位以内**（丢一半的自然结果）。
        // ⚠ 判据用 `avail`（水位线）而不是 `avail_hard`（整格）：贴线丢到刚好装下的
        //   那一轮同样落在 `avail_hard` 以内，但它的下一轮几乎必然再丢 —— 两件事
        //   必须在读数上分得开，否则用户看到 `prompt_trimmed > 0` 只会得出
        //   "又砍了"，而他真正要知道的是"这一刀之后还会不会每轮砍"。
        //
        // ⚠ **不许改回按整格判**（或把 `fits` 的日常判据换成 `avail_hard`）。理由不是
        //   "少丢几枚更省"，而是那条路就是把 ISSUE #426 修掉的 bug 装回去：按整格丢到
        //   刚好装下的那一轮，下一个几百 token 就又越线，起点每轮后移一次，模型每轮
        //   看到的历史都是新的一段（真机起点 108 → 408 → 708，`lcp` 恒 0，复用归零）。
        //   "整格"只在下面 `fits` 那一处出场：prompt 已让到 1 枚仍越水位时，再按整格
        //   看一次"到底进不进得去"—— 那是**兜底判活**，不是裁剪目标。
        //   要动这一条，先读那条 ISSUE 的真机读数，别只看"15% 在空转"。
        //
        // ⚠ 这一格**只是判据的局部量**，不进 /health：走到这里时 `prompt + gen <= avail`
        //   几乎恒成立（否则就 overflow 了），于是它与 `trimmed > 0` 同真同假。
        //   透出去回答问题的两格是下面那两个（`drop_room` / `drop_overshoot`）。
        p.dropped_to_watermark = p.trimmed > 0 && p.prompt + p.gen <= avail;
        // 判据只看**丢完之后**还装不装得下：`p.prompt + p.gen <= avail`。
        // 丢干净了就"装得下"= 这一轮能答（只是忘了最老的几轮）。
        //
        // ⚠ 这一支曾是两次 400 的根因（ISSUE #330 / #426），坏法一模一样：
        //   「prompt 本身超过 `ctx - 2`」的场次里 `over` 一定 > 0，于是**即使
        //   上面已经把 prompt 丢到装得下**，判据也照样归零。两次都是拿
        //   "还差多少"当结论，而不是"丢完之后装不装得下"。
        //   真机原话：「约 4100 tok 的 prompt 塞不进 ctx 4096（要给生成留 2 tok）」。
        //
        // 真正装不下只有一种：`avail <= 0` —— ctx 小到连保底生成空间都留不出，
        // 那时丢到只剩 1 tok 也塞不进去。**显式**判失败（调用方必须报错，不许静默继续）。
        //
        // ⚠ 水位之后多一层：水位是**目标**，不是硬墙。丢弃已经把 prompt 压到只剩
        //   尾部（1 枚都让不动了）却仍越过水位线时，剩下的缺口按**整格**判一次 ——
        //   否则 ctx 4096 + 水位 85% 会把一条 3600 tok 的提问判成装不下，而它明明
        //   进得去。判据的顺序是"先按水位、再按整格"，不是反过来：反过来的话
        //   水位就白设了（整格永远放行）。
        bool fits = avail > 0 && p.prompt >= 1 && p.gen >= std::min(kMinGenTokens, room);
        if (fits && p.prompt + p.gen > avail) {
            // 走到这里说明 prompt 已经让到 1 枚还越线 —— 按整格再看一次。
            fits = p.prompt + p.gen <= avail_hard;
            if (fits) p.dropped_to_watermark = false;   // 落到整格上，不算"回到落点线"
        }
        if (!fits) {
            p.overflow = true;
            p.trimmed = prompt_tokens > 0 ? prompt_tokens : 0;
            p.prompt = 0;
            p.gen = 0;
            p.dropped_to_watermark = false;
            // 装不下那一档：两个读数都没有意义（prompt/gen 都归零了，报正数会被读成
            // "还有余地 / 这一刀丢得值"）。与 `spare` 同一条纪律。
            p.drop_room = 0;
            p.drop_overshoot = 0;
        } else {
            p.prompt_trimmed = p.trimmed > 0;
        }
    }
    // 余量在**所有**出口都重算一次（含上面两条早退分支之外的路径）：
    // 它是 `avail - prompt - gen` 的同一个差值，两处各写一遍就会漂。
    // ⚠ 余量按 `cap`（这一条 seq 真能排多少），不是 `ctx`：按整格算出来的 `spare`
    //   会在单格池两路共用那一档报"还有 1014 枚" —— 而那一格的位置号早就排到
    //   头了，`used 2048 / spare 1014` 同时成立正是 2048 冒出来的现场。
    p.spare = spareOf(p.prompt, p.gen, cap);
    // 「离水位还有多远」与 `spare`（离**整格**还有多远）同一处落：两格量的是同一件事
    // 在两条线上的样子，各写一遍必然漂 —— 而漂的那一侧会报"还有余量"，下一轮照旧再丢。
    // ⚠ 只有 `ctx > 0` 才有水位可言（未加载那一档两格都是 0，不冒充"贴着水位"）。
    if (ctx > 0 && p.watermark_pct > 0 && !p.overflow) {
        p.drop_room = dropRoomOf(p.prompt, p.gen, cap, p.watermark_pct);
    }
    return p;
}

/**
 * 从尾部倒着算：装得下多少个 token，以及**从哪个下标开始**。
 *
 * 为什么是尾部而不是头部：KV 前缀复用的分叉点必须落在"本轮提问处"
 * （见 `RagInject.attachBeforeLastUser` 的注释）。从头部切会让每轮的起点往前挪，
 * 于是**每一轮都全量 prefill**；从尾部切则让保留下来的那段前缀与上一轮同形 ——
 * 丢一次只是这一次多付一次 prefill，之后回到稳定形态。
 *
 * @param tokens 整段 prompt 的 token 序列
 * @param keep   预算允许保留的 token 数（[Plan::prompt]）
 * @return 应当从哪个下标开始喂（0 = 一个都没丢，与今天逐字节一致）
 */
template <typename Token>
inline size_t trim_start(const std::vector<Token> & tokens, int keep) {
    if (keep <= 0) return 0;
    if ((int) tokens.size() <= keep) return 0;
    return tokens.size() - (size_t) keep;
}

/**
 * 「从这里开始喂会不会切在半句话上」—— 起点落在**可锯断处**时返回 true。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 这一格解决的是"谁去补那半枚 token"（ISSUE #452 第 3 步）
 * ══════════════════════════════════════════════════════════════════════════
 * [trim_start] 是逐 token 从头部切的，它一定会切在任意一枚上：一个汉字被拆成两枚
 * byte token 时，丢一枚留一枚 —— 留下的是半截字节，模型得替我们猜那半截是什么。
 * 这既不是"少了几轮历史"、也不是"缓存没接上"，但它同样让回答**掉质量**，
 * 而且读数上完全看不见（`trimmed` 一样大、`reuse` 一样有值）。
 *
 * ⚠ 判据**故意不查词表**：`trim_start` 是模板、够不着 `llama_vocab_get_attr` /
 *   `llama_vocab_get_text`（见 `include/llama.h`），而这四个重载收的是
 *   `llama_token` / `long long` 这类**整数**，全靠模板推导从 `std::vector<Token>`
 *   调进来。想让这里按 token 属性判，就得把符号改成收一个"边界探针"回调，
 *   连带 `tools/ctx_budget_test.cpp` 的桩一起改 —— 换来的判据在中文上收益有限
 *   （不少模型一个汉字一到两枚 byte token，词边界本来就不在 token 边界上）。
 *   所以这里只认**字节层面**的锯断处，代价为零、可离线验：
 *     · 首字节是 ASCII 空白（`0x20` / `\t` / `\r` / `\n`）：token 以自己的空白开头，
 *       上一枚与它之间本来就是一个可断的间隔；
 *     · 首字节**不是** UTF-8 续字节（`10xxxxxx`）：它是一段多字节字符的**开头**，
 *       从这里切不会留下裸的半枚字节。
 *
 * ⚠ 它**只回答"能不能在这里切"，不回答"该切在哪"**：落点由 [trim_start_snapped]
 *   决定，且方向恒为"往前挪"（多丢），绝不往回（少丢就撞 native 那一刀）。
 */
template <typename Token>
inline bool is_cut_boundary(const std::vector<Token> & tokens, size_t i) {
    if (i == 0) return true;                 // 从头喂，天然不切在中间
    if (i >= tokens.size()) return true;     // 越界 = 调用方问题，别在这里加戏
    const long long t = static_cast<long long>(tokens[i]);
    // 只认"本身就是一枚字节"的 token（0..255）：词表里更大的 id 是合并出来的
    // 子词，它的第一个字节没有定义 —— 那时宁可认为"能切"，也不猜。
    if (t < 0 || t > 255) return true;
    const unsigned char b = (unsigned char) t;
    if (b == 0x20 || b == (unsigned char) '\t' || b == (unsigned char) '\r' ||
        b == (unsigned char) '\n') return true;
    return (b & 0xC0) != 0x80;               // 不是 UTF-8 续字节
}

/**
 * [trim_start] 之后再把起点**往前挪**到最近的可锯断处（只多丢、不少丢）。
 *
 * 方向单一是有原因的：往回挪就是少丢，少丢就装不下 —— 撞 native 那一刀报 400，
 * 而 400 比"多忘几枚"严重得多。所以 `snapped <= raw` 恒成立，`snapped == 0`
 * 就是"一枚都没丢"，与 [trim_start] 逐字节一致。
 *
 * ⚠ 上限 `max_extra` 不许放开：往前挪最多走这么多枚，走不到就不挪（宁可切在中间
 *   也不要把一整轮历史成全这个"边界"）。它由调用方按"每条消息/每次裁剪愿意多花"
 *   给一个**常数**，不许按整段 prompt 的比例算 —— 那会让大 prompt 丢得更多，
 *   方向正好反了。
 */
template <typename Token>
inline size_t snap_start(const std::vector<Token> & tokens, size_t raw, int max_extra) {
    if (raw == 0 || raw >= tokens.size() || max_extra <= 0) return raw;
    const size_t floorAt = raw > (size_t) max_extra ? raw - (size_t) max_extra : 0;
    for (size_t i = raw; i > floorAt; i--) {
        if (is_cut_boundary(tokens, i)) return i;
    }
    return raw;
}

/**
 * [trim_start] 的落点再挪到可锯断处 —— 供"手上只有 `keep`、没有起点"的调用方。
 *
 * 三个入口同一份判据：`trim_start(tokens, keep)` 先算出起点，[snap_start] 再往前挪。
 * 分成两个函数是必要的：真正丢头的那一处（`llama_jni.cpp`）已经有绝对值 `from`，
 * 让它先反算回 `keep` 再算一遍起点，等于把同一个数换两次单位 —— 换错一次就多丢一轮。
 */
template <typename Token>
inline size_t trim_start_snapped(const std::vector<Token> & tokens, int keep, int max_extra) {
    return snap_start(tokens, trim_start(tokens, keep), max_extra);
}

/** 供日志用的一行摘要（判据本身不依赖字符串，只为「一眼可分」）。 */
inline std::string describe(const Plan & p, int ctx) {
    if (p.overflow) {
        // 文案要给**能自救的那个数字**（ISSUE #426）：`trimmed == prompt_tokens` 说明
        // 上面已经把这整段 prompt 都丢掉了 —— 也就是"连一轮提问本身都塞不进这一格"。
        // 只说"装不下"，用户唯一能做的是调 ctx，而他并不知道要调到多少。
        return "上下文预算：装不下（整段 prompt " + std::to_string(p.trimmed) + " tok 丢完仍放不进 ctx " +
               std::to_string(ctx) + " —— 这一格只剩 " + std::to_string(ctx - kMinGenTokens) +
               " tok 可用，保底生成空间 " + std::to_string(kMinGenTokens) + " tok）";
    }
    // 「丢完了、但生成空间没兑现地板」单独一档（ISSUE #426 读数：那一轮的回答只有
    // 一两个字，而 `describe` 说的是"装得下 / 丢最老 N tok"，读的人以为它只是忘了
    // 几轮历史）。它与 `spare == 0` 常同时出现，但不是同一件事：余量 0 说的是
    // "下一轮接不接得上"，这一档说的是"**这一轮**几乎没有回答"。
    if (p.gen_floor_missed) {
        return "上下文预算：装得下（但生成空间只剩 " + std::to_string(p.gen) + " tok，回答会很短；ctx " +
               std::to_string(ctx) + "，丢最老 " + std::to_string(p.trimmed) + " tok）";
    }
    if (p.trimmed <= 0 && p.gen_trimmed <= 0) {
        return "上下文预算：装得下（prompt " + std::to_string(p.prompt) + " + 生成 " +
               std::to_string(p.gen) + " ≤ ctx " + std::to_string(ctx) + "）";
    }
    if (p.trimmed <= 0) {
        // 只削了生成长度：prompt 一个字节都没动，KV 前缀仍然连续。
        return "上下文预算：prompt " + std::to_string(p.prompt) + " tok 原样保留，生成长度削到 " +
               std::to_string(p.gen) + "（ctx " + std::to_string(ctx) + "）";
    }
    // 「按水位丢一半」**排在削 gen 那一支之前**：越线那一轮几乎必然同时削 gen
    // （gen 先让到地板），而被削 gen 那一支吞掉之后，读数读起来像"只是话变短了"——
    // 用户要的恰恰是"我丢了一半历史"这件事，以及"之后几十轮不用再丢"。
    if (p.dropped_to_watermark) {
        std::string s = "上下文预算：越过水位 " + std::to_string(p.watermark_pct) + "% —— 丢最老 " +
                        std::to_string(p.trimmed) + " tok（保留尾部 prompt " + std::to_string(p.prompt) +
                        " + 生成 " + std::to_string(p.gen) + "）";
        if (p.drop_overshoot > 0) {
            s += "，其中 " + std::to_string(p.drop_overshoot) + " tok 是为了下次别再丢";
        }
        if (p.drop_room > 0) {
            s += "；离水位还有 " + std::to_string(p.drop_room) + " tok，之后几十轮起点不动";
        } else {
            s += "；丢完正贴着水位（下一轮历史再长就会再丢一次），可把水位调低一档";
        }
        if (p.gen_trimmed > 0) {
            s += "（生成长度同时被削到 " + std::to_string(p.gen) + "）";
        }
        return s;
    }
    if (p.gen_trimmed > 0) {
        // 两件事**同时**发生时要并排说：只说"丢最老"会让人以为是容量不够到连
        // 生成空间都保不住，只说"削长度"会把"历史被裁过"这件事藏起来。
        return "上下文预算：丢最老 " + std::to_string(p.trimmed) + " tok 装下（保留尾部 prompt " +
               std::to_string(p.prompt) + "），生成长度同时削到 " + std::to_string(p.gen) +
               "（ctx " + std::to_string(ctx) + "）";
    }
    return "上下文预算：丢最老 " + std::to_string(p.trimmed) + " tok 才装下（保留尾部 prompt " +
           std::to_string(p.prompt) + " + 生成 " + std::to_string(p.gen) + " ≤ ctx " +
           std::to_string(ctx) + "）";
}

} // namespace ctxbudget

#endif // LOCAL_LLM_CTX_BUDGET_H
