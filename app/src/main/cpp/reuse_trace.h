// reuse_trace.h —— 长对话跨轮的**两个读数**（纯逻辑，宿主可编、可单测）
//
// ══════════════════════════════════════════════════════════════════════════
// 这个文件只说两件事，都是**读数**，不是结论：
//   ① 这一轮是「接上上一轮」还是「重新决定起点」（`decision`）；
//   ② 若想接上、空间不够而让掉了额度，让掉多少（`refuse`）。
// ══════════════════════════════════════════════════════════════════════════
// 为什么把「起点为什么动了」那套五档（曾经的 `rediscover`）删掉：
//
// 它试图从 (起点, 上一轮起点, 本轮丢头数, 开头是否同形) 四个数里**推断成因**
//（我们砍的 / 起点在漂 / 前面那段变了 / 模板在变）。真机上这四档全都看不出来 ——
// 用户报的「复用只剩一百多」同时能被其中三档解释，而每档的处置相反。于是那几格
// 反倒培养了一种习惯：**拿推断当结论**，然后再拿结论去指挥用户改参数（五轮返工
// 都出在这里）。判据一旦读不出真实成因，它就不该有名字。
//
// 现在只留"能由数据本身回答"的：起点有没有动（`start` / `start_prev` / `reused`），
// 以及"这一轮接上了没有"（`aligned`）。成因仍可能有多个，但**不再替用户断言**。
#ifndef LOCAL_LLM_REUSE_TRACE_H
#define LOCAL_LLM_REUSE_TRACE_H

#include <string>

namespace reusetrace {

/** 这一轮的起点是怎么定的。 */
const int kDecisionNone    = 0;  // 没丢头：整段 prompt 全进（起点 0）
const int kDecisionAligned = 1;  // 起点落在上一轮的起点上 —— 之前攒下的前缀接得上
const int kDecisionFresh   = 2;  // 从本轮尾部重新决定起点 —— 之前的前缀接不上

/**
 * 判「接上了没有」。
 *
 * @param start      本轮新算的起点（0 = 从头 prefill）
 * @param prev_start 上一轮的起点（0 = 上一轮没丢过头 / -1 = 没有基线）
 * @param kept       本轮为装下而保留的 prompt 长度（0 = 调用方没丢头）
 *
 * 三档互斥，且每一档都能从这三个数**直接看出来**，没有推断：
 *   · `kept <= 0`          -> `kDecisionNone`；
 *   · `prev_start > 0` 且 `start == prev_start` -> `kDecisionAligned`（这是复用的**唯一**来源）；
 *   · 其余                 -> `kDecisionFresh`。
 */
inline int decision(int start, int prev_start, int kept) {
    if (kept <= 0) return kDecisionNone;
    if (prev_start > 0 && start == prev_start) return kDecisionAligned;
    return kDecisionFresh;
}

/** 一句话（与 `aligned` 同源；措辞只说读数，不说成因）。 */
inline std::string describeDecision(int code, int start, int prev_start) {
    switch (code) {
        case kDecisionAligned:
            return "起点接上上一轮（第 " + std::to_string(start) + " tok）—— 之前攒下的前缀仍然可用";
        case kDecisionFresh:
            return "本轮重新决定起点（第 " + std::to_string(start) + " tok；上一轮起点 " +
                   (prev_start > 0 ? std::to_string(prev_start) : std::string("无")) +
                   "）—— 之前攒下的前缀这一轮接不上";
        default:
            return "没丢头：整段 prompt 全进";
    }
}

/**
 * 想接上、但**额度不允许**时让掉了多少 —— `0` = 接上了（或没想接）。
 *
 * `want` 是"接到上一轮起点要多长"（= prompt − prev_start），`kept` 是实际留下的长度。
 * 让掉的量是"本来能少丢多少"：接上时它必须恒为 0（那正是"对齐"的含义），
 * 接不上时它就是这一轮为回答让出去的前缀长度 —— 一个可以用"调大每格容量 /
 * 调小 max_tokens"直接消掉的数。
 *
 * ⚠ 它与「prompt 超了容量」那件事分开报：超容量由 `prompt_trimmed` 说，
 *   这里只说"为了接上而没接成"的那段额度。
 */
inline int refuse(int want, int kept) {
    if (want <= 0 || kept <= 0) return 0;
    const int r = want - kept;
    return r > 0 ? r : 0;
}

/**
 * 想接上、**空间**却说接不上时，差了多少额度 —— `0` = 接上了（或没想接）。
 *
 * 与 [refuse] 是同一件事的两半，而两半的**处置完全不同**：
 *   · [refuse] 报的是"接上了之后还剩多少内容被让掉"（`want - kept`）；
 *   · 这一个报的是"要接上还差多少 token"（`want + gen - avail`）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么必须单独报（ISSUE #330 插队项的读数）
 * ══════════════════════════════════════════════════════════════════════════
 * `ctxbudget::plan` 的容量按"**用户填的 ctx**"算，而 KV 实际要装 `prompt + gen`
 * 个位置。用户在设置页填 4096、每轮 prompt 涨到 3400、`max_tokens` 1024 时
 * `3400 + 1024 > 4096` —— 于是"起点钉住上一轮"每轮都撑不住，退回按缺口丢，
 * 起点继续往后滑。读数上它长得像 bug（`aligned=0` + `prompt_trimmed>0`），
 * 真相是**填的数自相矛盾**：这个数就是"矛盾有多大"。
 *
 * 处置：差几 tok 就调大 ctx 几 tok（或把 max_tokens 调小几十）。它不是缺陷，
 * 是一笔可以算清的账 —— 而"算不清"正是用户在真机上查了五轮的那件事。
 */
inline int shortfall(int want, int gen, int avail) {
    if (want <= 0) return 0;
    const int need = want + (gen > 0 ? gen : 0) - (avail > 0 ? avail : 0);
    return need > 0 ? need : 0;
}

} // namespace reusetrace

#endif // LOCAL_LLM_REUSE_TRACE_H
