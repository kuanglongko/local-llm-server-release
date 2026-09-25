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
    /** 是否命中缓存（reuse > 0）。日志与 /health 用。 */
    bool hit() const { return reuse > 0; }
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

/**
 * 算出这一轮的复用计划。
 *
 * @param prev   上一轮**已经进 KV 的整段 token 序列**（含生成出来的那部分）。
 *               上一轮为空（首次请求 / 换模型 / 已释放）时传空 vector 即可。
 * @param cur    本轮 prompt 的 token 序列。
 * @param prev_valid 上一轮的 KV 是否**仍在**且仍然属于同一序列。
 *               凡是"KV 已经被动过"的情况都必须传 false —— 见下面的理由。
 * @param total_ctx KV 总容量（n_ctx）。
 *
 * 三处保守约束，每一条都对应一种"看起来能复用、实际会读错历史"的真实情形：
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
 */
inline Plan plan_reuse(const std::vector<long long> & prev,
                       const std::vector<long long> & cur,
                       bool prev_valid,
                       int total_ctx) {
    Plan p;
    p.total = (int) cur.size();
    p.to_decode = p.total;
    if (!prev_valid || prev.empty() || cur.empty()) return p;   // ①
    if (total_ctx > 0 && p.total >= total_ctx) return p;        // ③

    const int lcp = common_prefix_len(prev, cur);
    if (lcp < kMinReuseTokens) return p;

    // ④ 复用长度绝不允许达到本轮的整段 prompt：
    //    prefill 的最后一个 token 必须**被 decode 一次**，下一次采样才有 logits 可采。
    //    若把整段都当"已缓存的"，采样步会在一个没有任何 logits 的上下文上取值。
    //    （短 prompt 恰好等于上一轮前缀 + 空生成时就会踩到，是本函数最隐蔽的一处。）
    int reuse = std::min(lcp, p.total - 1);
    // ⑤ 位置不能越过 KV 容量（防御性：正常路径下 ③ 已拦住）
    if (total_ctx > 0 && reuse >= total_ctx) reuse = total_ctx > 0 ? total_ctx - 1 : 0;
    if (reuse < kMinReuseTokens) return p;

    p.reuse = reuse;                                            // ②
    p.to_decode = p.total - reuse;
    return p;
}

/** 供日志用的一行摘要（判据本身不依赖字符串，只为"一眼可分"）。 */
inline std::string describe(const Plan & p) {
    if (!p.hit()) return "KV 前缀复用：未命中（全量 prefill " + std::to_string(p.total) + " tok）";
    return "KV 前缀复用：命中，复用 " + std::to_string(p.reuse) + " tok，只需 prefill " +
           std::to_string(p.to_decode) + " tok（共 " + std::to_string(p.total) + " tok）";
}

} // namespace kvprefix

#endif // LOCAL_LLM_KV_PREFIX_H
