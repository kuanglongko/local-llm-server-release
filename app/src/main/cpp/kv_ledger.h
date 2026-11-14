// kv_ledger.h — 「账本 == KV」这条不变量（纯逻辑，宿主可编、可单测）
//
// ══════════════════════════════════════════════════════════════════════════
// 这个文件只回答一个问题：**这一轮的账本与本轮入口时那条 seq 上的 KV 对得上吗？**
// ══════════════════════════════════════════════════════════════════════════
// 不变量（ISSUE #577 Layer 3）：
//
//     账本长度 == 这条 seq 上 `[0, pos_max]` 连续存在的位置数，任何时刻成立。
//
// 在此之前，这条不变量**没有名字**。于是每换一个场次就冒出一处新的复核：
//   · 命中那一刀以为自己截到 `reuse`；
//   · `plan.skip` 那一刀以为自己搬成了；
//   · 折回那一刀以为自己删干净了；
//   · `abort` 那一刀以为自己缩到实况了。
// 每一处各写一遍"我这一刀之后应该剩多少"，就必然有一处漏了 —— 漏掉的那一处
// 不崩、不报错，只是下一轮 `reuse` 落在一个**不存在**的位置上：位置对得上、
// 槽位是空的（或别人的）。真机上是"每轮全量 prefill"和"答非所问"两个症状，
// 而日志里每一行都说得通。
//
// 所以这里只做三件事：
//   · [Entry] / [check_entry] —— 入口**只验一次**：两份账（账本长度 / KV 实况）
//     对不对得上。对不上就只有一种处置：**降级为全量**（缓存是纯加速）。
//   · [requiredContinuous] / [satisfied] —— 每一刀之后**该**是多少。
//     这是判据的唯一一份，三个出口（清空 / 截断 / 清账本）落完之后都拿它核。
//   · [land] —— 落完之后把账本长度收到实况。**收缩**永远安全，**增长**永远是错 ——
//     所以收缩照做，增长只喊一声、不改数（改了就是"账本比 KV 长"，那正是要防的）。
//
// 为什么记"长度"而不是"位置集合"：这条 seq 上的位置**恒从 0 开始、恒连续**
// （prefill 从 `start` 起一枚一枚往后排，生成接着往上加）。一旦它不连续，
// 那已经是另一类坏（库把位置搬了 / 别人动了这条 seq），长度这个数根本没有意义 ——
// 而 `pos_min != 0` 正是它的判据。所以"集合"在这里退化成"长度 + 起点必须为 0"。
//
// ⚠ **混合 / 循环记忆**（`llama_model_is_hybrid` / `is_recurrent`）是这条判据的
//   唯一例外，而且不是"宽容一点"：那类记忆里循环态**只有一格**（记的是最后处理到
//   的位置），`llama_memory_seq_pos_min` 返回的是"注意力段起点与循环态起点里更大的
//   那个" —— 于是一条装满了的 seq 天然读成 `pos_min == pos_max == ledger - 1`。
//   拿普通 transformer 的 `pos_min == 0` 去核它，每一轮都判"两份账打架"，接着
//   `kv_invalidate` 清掉整条 KV —— 真机表现就是"每轮全量 prefill"。
//   这类模型能核的只有**末格**：`pos_max == ledger - 1`（以及读数不是非法负值）。
#ifndef LOCAL_LLM_KV_LEDGER_H
#define LOCAL_LLM_KV_LEDGER_H

#include <string>

namespace kvledger {

/** 入口那一刻的两份账（都是**读数**，判据只看它们对不对得上）。 */
struct Entry {
    bool valid = false;     // 账本是否仍然有效（`Session::kv_valid`）
    int  ledger = 0;        // 账本长度（`last_tokens.size()`）
    int  pos_min = 0;       // 这条 seq 上**最小**位置号（-1 = 这条 seq 一枚都没有）
    int  pos_max = 0;       // 这条 seq 上**最大**位置号（-1 = 这条 seq 一枚都没有）
    int  n_used = 0;        // 整格的"已入 KV"计数（`Session::n_used`，跨 seq 的总和）
    int  seq_limit = 0;     // 这一条 seq 的容量（0 = 不知道）
    /**
     * 这份记忆是不是**混合 / 循环**（`llama_model_is_hybrid || is_recurrent`）。
     *
     * 置真时 `pos_min` 不再是"账本起点"，而是"注意力段与循环态里更靠后的那个"：
     * 循环态只有最后一格，所以它天然等于 `pos_max`。判据因此只核末格。
     */
    bool split_memory = false;
};

/**
 * 一份账本与一条 seq 上的实况是否对得上。
 *
 * 普通 transformer：`pos_min == 0` 是"连续"那一半，`pos_max == ledger - 1` 是"到末尾"。
 * 混合 / 循环记忆：只核 **`pos_max == ledger - 1`** —— `pos_min` 由循环态决定，
 * 拿它当账本起点会把每一轮都判成"对不上"（见文件头那段真机现场）。
 */
inline bool consistent(int ledger, int pos_min, int pos_max, bool split_memory = false) {
    if (ledger <= 0) return pos_min < 0;          // 空账本 <=> 这条 seq 上一枚都没有
    if (split_memory) {
        // 循环态只有最后一格：能核的是"末格正好在账本末尾"。
        if (pos_min < 0) return false;            // 非法读数不当地址用
        return pos_max == ledger - 1 && pos_min <= pos_max;
    }
    if (pos_min != 0) return false;               // 不从头开始 = 中间被搬过
    return pos_max == ledger - 1;                 // 连续到账本末尾
}

/**
 * 入口只验一次。
 *
 * @return true = 两份账对得上（或账本本来就无效，这一路没有可比的东西）
 *
 * ⚠ 账本无效时恒真：那是"上一轮没有"，不是"两份账打架"。把它也判成打架，
 *   第一次运行 / 换模型之后那一轮就会去作废一本已经不存在的账（无意义），
 *   而日志会多出一句谁也没法处置的"对不上"。
 */
inline bool check_entry(const Entry & e) {
    if (!e.valid) return true;
    return consistent(e.ledger, e.pos_min, e.pos_max, e.split_memory);
}

/** 这一条 seq 上有没有东西（`pos_max < 0` = 一枚都没有）。 */
inline bool seq_empty(int pos_max) { return pos_max < 0; }

/**
 * 某一刀落完之后，这条 seq 上**应当**还有多少枚连续位置。
 *
 * 三档，与"动 KV 位置"的三个出口一一对应（ISSUE #577 Layer 1）：
 *   · 清空：0（不管之前有多少）；
 *   · 截断：`keep`（但不会**变多**——库里本来只有 5 枚时截到 10 枚还是 5 枚）；
 *   · 不动：`prev_len`（原样）。
 *
 * ⚠ 第二档的 `min` 是本函数存在的理由：`kv_truncate_seq(keep)` 在
 *   `pos_max < keep - 1` 时**什么都不删**（库里没有 `[keep, ∞)`），
 *   于是"我截到 100 枚"这句话在实况只有 5 枚时是错的。判据写成
 *   `min(keep, prev_len)` 之后，"该剩多少"与"真剩多少"才可能相等。
 */
inline int requiredContinuous(int prev_len, int keep) {
    if (keep < 0) return prev_len;                // -1 = 这一刀不是截断
    if (keep <= 0) return 0;
    return prev_len < keep ? prev_len : keep;
}

/** `satisfied(pos_max, want)`：实况是不是"该有的那几枚"。`want == 0` 要求空。 */
inline bool satisfied(int pos_max, int want) {
    if (want <= 0) return pos_max < 0;
    return pos_max == want - 1;
}

/** 落完之后的对账结论（`land` 的出口，日志与 /health 共用同一份判据）。 */
struct Landed {
    bool ok    = false;     // 账本长度已经等于实况连续长度
    bool grew  = false;     // 实况比账本长 —— **绝不**把它写进账本（那是在编历史）
    int  after = 0;         // 落完之后账本该是多少（= 实况连续长度）
};

/**
 * 落完之后把账本长度收到实况。
 *
 * @param ledger_len  落之前**该有**的账本长度（`requiredContinuous` 的落值）
 * @param pos_max     落之后这条 seq 上的最大位置号（-1 = 空）
 *
 * 两条方向不对称，这是有意的：
 *   · 实况**比账本短**（`pos_max + 1 < ledger_len`）：收到实况。账本比 KV 长
 *     就是把没进 KV 的 token 当成已进 KV —— 下一轮 `reuse` 落在空槽位上，
 *     而那正是这一层要消掉的那类坏。收缩永远安全。
 *   · 实况**比账本长**（`pos_max + 1 > ledger_len`）：不改。多出来的那几枚
 *     是谁的、是不是本轮的，账本没有记；写进去就是凭空造一段从未进过 KV 的
 *     历史。这一档只喊一声 —— 它出现就说明**有一处动 KV 的路径没落账**，
 *     那才是要修的因，而不是在这里把数字凑齐。
 *
 *     例外：调用方若用 `want` 声明了"这一刀之后应到的长度"，而实况正好等于它
 *     （`satisfied(pos_max, want)`），那这段增长是**调用方自己刚 decode 进去的**
 *     —— prefill 每一批就是这种形态（账本要等整轮成功才 assign）。接线侧据此
 *     跳过喊话；本函数仍只报读数，不替调用方判断。
 */
inline Landed land(int ledger_len, int pos_max) {
    Landed r;
    const int actual = seq_empty(pos_max) ? 0 : pos_max + 1;
    r.after = (actual < ledger_len) ? actual : ledger_len;
    r.grew  = (actual > ledger_len);
    r.ok    = (actual == ledger_len);
    return r;
}

/** 一句话（日志与 /health 共用；判据本身不依赖字符串）。 */
inline std::string describe(const Entry & e, bool ok) {
    if (!e.valid) return "账本无效（这一轮没有可比的东西）";
    // ⚠ 混合 / 循环记忆上**不许**说 `[0, pos_max]`：那类记忆的 `pos_min` 是
    //   循环态的最后一格（= `pos_max`），后面并没有一整段 `[0, pos_max]` 的连续 KV
    //   可以按这个区间去索引。把读数说成区间，读日志的人会顺着去推"起点是 0"，
    //   而判据偏偏不核这一格 —— 两句话各自成立、合起来误导。
    const std::string kv = seq_empty(e.pos_max)
        ? std::string("这条 seq 上一枚都没有")
        : (e.split_memory
            ? ("这条 seq 的**末格**在 " + std::to_string(e.pos_max) +
               "（混合/循环记忆：循环态只有最后一格，最小位置读数 " +
               std::to_string(e.pos_min) + " 不是账本起点）")
            : ("这条 seq 占着 [0, " + std::to_string(e.pos_max) + "]"));
    if (ok) return "账本 " + std::to_string(e.ledger) + " tok == " + kv;
    const std::string where = e.split_memory
        ? ("末格 " + std::to_string(e.pos_max))
        : ("起点 " + std::to_string(e.pos_min));
    return "账本 " + std::to_string(e.ledger) + " tok != " + kv +
           "（" + where + "，seq 容量 " +
           std::to_string(e.seq_limit) + "）—— 有一处动 KV 的路径没落账，"
           "本轮降级为全量 prefill（缓存是纯加速，宁可慢一轮）";
}

} // namespace kvledger

#endif // LOCAL_LLM_KV_LEDGER_H
