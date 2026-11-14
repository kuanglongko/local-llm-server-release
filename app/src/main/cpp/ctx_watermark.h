// ctx_watermark.h —— 上下文水位（%）的**纯逻辑**：缺省档、五档梯子、归一。
//
// ══════════════════════════════════════════════════════════════════════════
// 为什么单独一个文件（而不是留在 ctx_budget.h 里）
// ══════════════════════════════════════════════════════════════════════════
// 水位是**两个**判据的共同输入：
//   · `ctxbudget::plan`      —— 这一轮能装多少（按真 token）；
//   · `kvlongchat::planKeep` —— 这一轮该保留多少（越过容量之后的取舍）。
// 而 `ctx_budget.h` 已经 include 了 `kv_longchat.h`（它要用 `kGenFloor`），
// 反过来的 include 就是循环依赖 —— 编译不过，于是"两份判据共用一份水位"这件事
// 会退化成"各写一份"，而各写一份的症状正是这一族 bug 的模样：一边按 85% 裁、
// 一边按 100% 算，两边都不报错。
//
// 所以**数与归一**放这里（无依赖、宿主可编），两个判据各自 include 它。
// 与 Kotlin 的关系同 `kv_longchat.h` 那一路：`ContextBudget.DEFAULT_WATERMARK_PCT`
// / `ContextWatermark.DEFAULT_PCT` / `ContextWatermarkPct` 三处与这里**同值同义**，
// 由接线守卫钉住（分叉时判红）。
#ifndef LOCAL_LLM_CTX_WATERMARK_H
#define LOCAL_LLM_CTX_WATERMARK_H

namespace ctxwatermark {

/**
 * 水位上限的缺省值（%）—— 与 Kotlin 侧 `ContextBudget.DEFAULT_WATERMARK_PCT` /
 * `ContextWatermark.DEFAULT_PCT` **同值同义**（ISSUE #426 方案 B）。
 *
 * 三处同值由接线守卫钉住。分叉的后果与 `kMinGenTokens` 那一对完全同形：
 * 客户端按 85% 裁、native 按 100% 算 —— 两边都不报错，只是 native 那一刀
 * 又落回来（把 Kotlin 好不容易稳住的 KV 前缀起点每轮往后推一格）。
 */
const int kDefaultWatermarkPct = 85;

/**
 * 水位可选项（%）。与 Kotlin 的 `ContextWatermarkPct.LADDER` 同值同序。
 *
 * 95 那一档是 ISSUE #586 用户点名加的：「加大 ctx 的情况下，水位只到 0.9，有点浪费」——
 * `ctx 32768` 时 85% 与 95% 差着 3276 枚，那是十几轮历史。
 */
const int kWatermarkLadder[] = {70, 80, 85, 90, 95};
const int kWatermarkLadderCount = 5;

/**
 * 把任意水位百分数归一到**五档之一**（与 Kotlin `ContextWatermarkPct.normalize` 同一条判据）。
 *
 * 三件事：钳到 `50..95`、吸附到最近的档位、缺省落 `kDefaultWatermarkPct`。
 * 吸附的理由见 Kotlin 那份的注释（83 与 85 在手感上没有区别，而它会让"设置页显示
 * 一个值、判据按另一个值算"这类漂移无从发现）。
 *
 * ⚠ `<= 0` 落**默认档**，而不是"关掉水位"：一个读错的 0 若被当成"不限"，
 *   用户会以为关掉了水位、实际每轮都在断 —— 那正是本 issue 要消除的那件事。
 */
inline int watermarkNormalize(int pct) {
    // ⚠ `<= 0` 是"没设过/读错了"，不是"一个很小的值"：先钳到 50 再吸附会落到
    //   70 那一档 —— 于是"没设过的人"与"刻意调成 70 的人"在读数上同形，
    //   而默认值明明是 85。所以这一档必须先单独放行到默认值。
    if (pct <= 0) return kDefaultWatermarkPct;
    int v = pct < 50 ? 50 : (pct > 95 ? 95 : pct);
    int best = kDefaultWatermarkPct;
    int bestD = -1;
    for (int i = 0; i < kWatermarkLadderCount; i++) {
        int d = kWatermarkLadder[i] > v ? kWatermarkLadder[i] - v : v - kWatermarkLadder[i];
        if (bestD < 0 || d < bestD) { bestD = d; best = kWatermarkLadder[i]; }
    }
    return best;
}

} // namespace ctxwatermark

#endif // LOCAL_LLM_CTX_WATERMARK_H
