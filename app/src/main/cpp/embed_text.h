// embed_text.h — 向量嵌入里那几段「纯数值 / 纯切分」逻辑（无 JNI / 无 llama 依赖）
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么把这段单独拆一个头文件
// ═══════════════════════════════════════════════════════════════════════════
// 嵌入向量一旦算错，症状**极难归因**：
//   · 不归一化 -> 余弦相似度不再等价于点积，检索按"向量长度"排序，
//     命中结果看起来"随机"（模型明明没坏，接口也 200）；
//   · 维度取错（拿 n_embd 当 n_embd_out，或把多层输出当成一层）-> 向量里
//     混进别的层的数据，检索全错，但**数组长度对得上**，谁都不会怀疑；
//   · 池化取错位置（取第一个 token 而不是最后一个）-> BGE 这类模型用的是
//     最后一层 [CLS] 或 mean，取错位置等于用了一个语义无关的向量；
//   · 空文本 / 超长文本不设界 -> 算出 NaN 或越界读，检索全空，接口还是 200。
//
// 以上每一条都**不抛异常、不崩溃、不报错**，只在检索结果上表现为"没那么准"，
// 而那正是本功能唯一的价值。所以这些判定必须是纯函数、必须能被宿主侧用真数组
// 钉住（与 kv_prefix.h / stop_sequences.h 同一套路）。
//
// 本文件只做三件事，都不碰 llama API：
//   1. L2 归一化（原地，带零范数保护）；
//   2. 池化取第几个 token 的下标决策（last-token pooling 的语义）；
//   3. 余弦相似度 / top-k 选择（检索侧，App 与守卫共用同一份判据）。
#ifndef LOCAL_LLM_EMBED_TEXT_H
#define LOCAL_LLM_EMBED_TEXT_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <vector>

namespace embedtext {

/**
 * 原地 L2 归一化。返回 false 表示**没有归一化**（范数为零或非有限），
 * 调用方据此决定是否丢弃这一条（绝不能把全零向量塞进索引：
 * 它与任何向量的余弦都是 0/0 = NaN，排序结果未定义）。
 *
 * 为什么必须归一化：本仓库的检索用**余弦相似度**，而余弦 = 点积 / 模长。
 * 归一化之后检索退化成一次点积，是手机上唯一负担得起的做法。
 * 不归一化时"向量长"的文档会无条件排在前面 —— 与语义无关，
 * 表现为"检索结果总是同几条"，而没有任何一条日志会提示这件事。
 *
 * 为什么零向量要拒绝而不是"归一化成一个安全值"：零向量的方向不存在，
 * 把它变成任意方向都是在**编造**一个语义。拒绝（返回 false）让调用方
 * 明确知道"这一条没有可用的向量"，比悄悄塞一个假向量好。
 */
inline bool l2_normalize(float * v, size_t n) {
    if (v == nullptr || n == 0) return false;
    double sum = 0.0;
    for (size_t i = 0; i < n; i++) {
        const double x = (double) v[i];
        sum += x * x;
    }
    if (!(sum > 0.0) || !std::isfinite(sum)) return false;   // 全零 / NaN / Inf
    const double inv = 1.0 / std::sqrt(sum);
    if (!std::isfinite(inv)) return false;
    for (size_t i = 0; i < n; i++) v[i] = (float) ((double) v[i] * inv);
    return true;
}

/**
 * last-token pooling 要取的那个 token 在 batch 里的下标。
 *
 * `nTokens` = 这一条文本 tokenize 之后的 token 数。为什么取**最后一个**：
 * BGE / nomic 这类句向量模型把整句的语义压在最后一个位置（解码器式模型的
 * 最后一层最后一个 token 见过全部前文），这也是本仓库 vendor 库里
 * `llama_get_embeddings_ith` 配合 `logits[last]=1` 的用法。
 *
 * 返回 -1 表示**这条文本没有可用 token**（空串 / tokenize 失败）。
 * 把它与"下标 0"分开是刻意的：空文本取下标 0 会读到一段**没有意义的**
 * 上下文（可能全是 special token），检索全错却看不出。
 */
inline int last_token_index(int nTokens) {
    return nTokens > 0 ? nTokens - 1 : -1;
}

/**
 * 该去读**第几个 output** 才能拿到 last-token pooling 的那个向量。
 *
 * ⚠ 这个数**不是** [last_token_index]（不是 `n_tokens-1`），两者极易混：
 *
 *   · `llama_get_embeddings_ith(ctx, i)` 的 `i` 是 **output 数组下标**，
 *     头文件原文 `llama_get_embeddings(ctx) + ctx->output_ids[i]*n_embd`；
 *   · `output_ids` 只收**那些 `llama_batch.logits[j] != 0` 的 token**，
 *     按它们在 batch 里出现的顺序**连续排列**（头文件原文）。
 *
 * 本实现只给最后**一个** token 置 `logits=1`（见 nativeEmbed 的填 batch 段），
 * 所以 output 数组恰好 1 个元素，它的下标是 **0**。
 * 写成 `n_tokens-1` 时，只要文本超过 1 个 token，`output_ids[n_tokens-1]` 就无效，
 * `llama_get_embeddings_ith` 返回 null → 检索全空而**接口 200**。
 *
 * 返回值是"我们**确实**要了 1 个 output 时的那个下标"：没有 token 时返回 -1
 * （调用方据此丢弃这一条），否则恒为 0。
 */
inline int first_output_index(int nTokens) {
    return nTokens > 0 ? 0 : -1;
}

/**
 * 检查「输出侧维度」与「模型上报维度」的关系是否成立。**只做判定，不做钳制**。
 *
 * ⚠ 为什么必须单独有一条判据：`llama_get_embeddings_ith` 是按 **n_embd** 步长
 * 走的（头文件 `ctx->output_ids[i]*n_embd`），而库在 `embeddings=true` 时
 * 按 **n_embd_out** 分配 output buffer，两者**在部分模型上不相等**
 * （Qwen3-Embedding 这类 decoder 式嵌入模型就是）。
 *
 * 当前实现给最后一个 token 置 `logits=1`，只读**一行**：
 *   · `n_embd_out > n_embd` 时 buffer 比我们按 n_embd 读的更长 —— 读不越界，
 *     但那一行只覆盖了输出的前 n_embd 个分量，语义被截掉一截，**不报错**；
 *   · `n_embd_out < n_embd` 时 buffer 比我们读的短 —— 越界读，
 *     读到的是**别的 buffer 的数据**（含未初始化的 NaN），
 *     表现为"某些条归一化失败"且**时好时坏**（取决于那块内存之前被写过什么）。
 *
 * 后一种正是用户报的"第一次失败、改改词序某次成功之后就再也不失败"的物质基础：
 * 失败由内存状态决定，与文本内容无关。所以这里**要求相等**（不是 >=），
 * 不相等时明确降级（调用方丢弃这一条并报出实值），而不是继续读。
 *
 * `nEmbdOut <= 0` 视为"库没报出来"（取不到），也不允许继续 —— 与"相等"分开报，
 * 免得把"这个符号拿不到"误当"这个模型不匹配"。
 */
inline bool output_dim_ok(int nEmbd, int nEmbdOut) {
    return nEmbd > 0 && nEmbdOut > 0 && nEmbd == nEmbdOut;
}

/**
 * 池化：从「每个 token 一个 n_embd 维向量」的扁平数组里取出目标 token 的向量。
 *
 * `flat` 的布局是 `nTokens * nEmbd`（`llama_get_embeddings_ith` 的 shape 约定）。
 * 越界（idx < 0 或 flat 尺寸不够）时返回 false 且**不写 out** ——
 * 宁可让调用方丢弃这一条，也不要读一段越界内存（那是段错误，
 * 而段错误在本项目的成本是**进程级闪退**）。
 */
inline bool pool_row(const float * flat, size_t flatLen, int nEmbd,
                     int idx, std::vector<float> & out) {
    if (flat == nullptr || nEmbd <= 0 || idx < 0) return false;
    const size_t need = ((size_t) idx + 1) * (size_t) nEmbd;
    if (flatLen < need || need > flatLen) return false;
    out.assign(flat + (size_t) idx * (size_t) nEmbd,
               flat + (size_t) idx * (size_t) nEmbd + (size_t) nEmbd);
    return true;
}

/**
 * 一条文本编码失败时的**可归因**详情（方案 C 的载体）。
 *
 * 为什么必须结构化成字段、而不是一句日志：现在失败只会输出
 * 「归一化失败（零范数/NaN），丢弃这一条」—— 这句话在**两个不同的根因**下
 * 都会出现（池化取错位置导致范数下溢 / 输出维度不匹配导致读到脏内存），
 * 而用户拿到的提示是「检查嵌入模型与内存」。两个根因的修法完全不同，
 * 一句无差别的话等于没有排查方向。
 *
 * 更关键的是：**"哪一条"从来没有被记下来**。100 条里坏 3 条时，
 * 用户只能靠二分法重试；而这 3 条的正文其实就在他粘贴的文本里。
 *
 * 所以失败详情必须带：原因码 + 实际数值（范数 / token 数 / 维度 / 池化类型）。
 * 数值是这里最有价值的部分 —— `norm=0` 与 `norm=1e-30` 指向完全不同的毛病，
 * 而只报"失败"时两者长得一样。
 */
struct EmbedFailure {
    enum Reason {
        NONE = 0,          ///< 没失败
        BAD_INPUT,         ///< 空文本 / tokenize 失败 / 无可用 token
        TRUNCATED_EMPTY,   ///< 截断后没有 token 了
        DIM_MISMATCH,      ///< n_embd != n_embd_out（输出 buffer 与读取步长不是一对）
        NO_OUTPUT,         ///< llama_get_embeddings_ith 返回 null（embeddings 没设上 / 库版本）
        ZERO_NORM,         ///< 归一化失败（零范数 / NaN / Inf）
        DECODE_FAILED,     ///< llama_decode 返回非 0
        POOLING_CONTRACT,  ///< 我们填的 logits[] 违反库对该池化方式的前置要求（decode 之前就拦）
    };
    int reason = NONE;
    int n_tokens = 0;      ///< 实际参与前向的 token 数（截断后的）
    int n_embd = 0;        ///< 模型上报的隐藏维度（也是读取步长）
    int n_embd_out = 0;    ///< 模型上报的输出维度（库据此分配 output buffer）
    int pooling = -1;      ///< llama_pooling_type 的原值（-1 = 取不到）
    double norm = 0.0;     ///< 归一化前算出的范数（ZERO_NORM 时是判定依据：0 / 极小 / NaN）
};

/** 失败原因码 -> 稳定英文短名（进日志 / 进 `meta.json` 的 failReason，不随文案改）。 */
inline const char * failure_name(int reason) {
    switch (reason) {
        case EmbedFailure::BAD_INPUT:       return "bad_input";
        case EmbedFailure::TRUNCATED_EMPTY: return "truncated_empty";
        case EmbedFailure::DIM_MISMATCH:    return "dim_mismatch";
        case EmbedFailure::NO_OUTPUT:       return "no_output";
        case EmbedFailure::ZERO_NORM:       return "zero_norm";
        case EmbedFailure::DECODE_FAILED:   return "decode_failed";
        case EmbedFailure::POOLING_CONTRACT:return "pooling_contract";
        default:                            return "none";
    }
}

/** 向量平方和的平方根（不修改入参）。**不判失败** —— 失败判定在 l2_normalize 里，
 *  这里只负责"报出实际值"（方案 C 要求把 norm 带出去，`0` 与 `1e-38` 是两种病）。 */
inline double norm_of(const float * v, size_t n) {
    if (v == nullptr || n == 0) return 0.0;
    double sum = 0.0;
    for (size_t i = 0; i < n; i++) {
        const double x = (double) v[i];
        sum += x * x;
    }
    return std::sqrt(sum);
}

/** 池化方式（由模型自己在 GGUF 里声明，我们不猜）。 */
enum class Pool {
    LAST,  ///< 取最后一个 token 的输出（生成式 / decoder 式嵌入模型）
    CLS,   ///< 取第 0 个 token 的输出（BERT 系句向量模型，BGE 就是这一类）
    MEAN,  ///< batch 内逐维平均
};

/**
 * 句向量（sequence embedding）该从**哪一行**读。这一层是**读的位置**，
 * 与"要不要池化归一化"是两件事 —— 后者见 [normalize_pooling]。
 *
 * ⚠ 为什么它与 [Pool] 不是同一个枚举：`Pool` 说的是"这条文本的语义向量在
 * 哪个**位置**"，而 `LLAMA_POOLING_TYPE_LAST(3)` / `LLAMA_POOLING_TYPE_NONE(0)`
 * 说的是"**库**把这一份算成句向量"。库侧 `get_embeddings_seq()` 的内部实现
 * 对这两档都取 **tokens.back()** 那一行（本仓库 vendor 里已复核），
 * 所以我们要去的也是那一行 —— 而不是 `output` 数组里的第 0 项。
 *
 * 这两者混同过一次，代价是整条嵌入路径不可用：
 *   `LLAMA_POOLING_TYPE_LAST` 下 `llama_get_embeddings_ith(ctx, 0)` 读到的是
 *   `output_ids[0] == 0`，也就是**第 0 个 token**那一行。生成式 decoder 里
 *   第 0 个 token 是 BOS —— 它没有前文，BOS 位置的隐藏态量级极小
 *   （实测范数 ~1e-30，见 `[embed] 归一化失败（范数=3e-30…）`），
 *   归一化必然拒绝。而 `LLAMA_POOLING_TYPE_NONE(0)`（Qwen3-Embedding 走这一档）
 *   恒为 true，`embedding_seq` 存在 → 库会给出**正确**的 last 向量，
 *   于是"两个生成式模型一个全败一个全成"。
 *   修法是**同一处**：语义是 last 时就去读**最后一个 token 的行**
 *（`output_slot = batch_last_slot = nTokens-1`），两档一起对。
 */
enum class SeqRow {
    LAST_TOKEN,  ///< 读**最后一个 token** 那一行（`get_embeddings_ith`）
    LIB_POOLED,  ///< 走 `llama_get_embeddings_seq`（MEAN / CLS / LAST：库池化好的那一份）
};

/**
 * 一次池化要"怎么取"的完整计划 —— 由纯函数算出来，native 只负责执行。
 *
 * ⚠ `want_logits` 与 `output_slot` 是**一对**，必须同时成立：
 *   · `want_logits[j]` 决定 `llama_batch.logits[j]` 置不置 1（= output 数组里有没有这一项）；
 *   · `output_slot` 决定去读 output 数组的第几个元素。
 * 只改一处就会出现"我们没要这一行、却去读它"（返回 null → 检索全空，接口 200），
 * 或者"要了 N 行、只读第 0 行"（CLS/MEAN 静默退化成 LAST）。
 * 两条都是**不报错**的，所以这两个字段由同一个函数一起产出。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠⚠ 还有第三个一体的东西，前两版都漏了：**库自己也要看 `logits[]`**
 * ═══════════════════════════════════════════════════════════════════════════
 * 上面那两个字段管的是"我们从 output 里取什么"。但 `llama_batch.logits[]`
 * 同时是**给库的指令**，而库对几种池化方式有**自己的前置校验**（vendor 的
 * `librnllama.so` 里，两处判据原文）：
 *
 *   · `pooling_type == CLS`（本仓库用原生符号 `llama_pooling_type(ctx)` 读到的**真值**：
 *     UNSPECIFIED=-1 / NONE=0 / MEAN=1 / CLS=2 / LAST=3 / RANK=4）—
 *     库要求**整段都在 output 里**：
 *     `pooled embedding requires that all tokens are output (n_outputs_all = %d, n_tokens_all = %d)`
 *   · `pooling_type == NONE`（生成式 decoder）— 库只要求**至少一个** output：
 *     一段里一个都没有时 `n_outputs >= 1` 断言失败（= 崩）。
 *
 * 也就是说：**"只给第 0 个 token 置 logits"这条早先的 CLS 修法是错的** ——
 * 它在库那一层直接触发上面第二条校验，`llama_decode` 返回非 0，于是
 * BGE 系（pooling=CLS）100% 失败，而失败被记成 `decode_failed`，看起来
 * 像"内存不够/模型坏了"。这正是"BGE 系条条失败"在 `0.9.156` 之后**仍然**复现的原因。
 *
 * 结论（也是本函数现在的口径）：
 *   · `want_logits` = "库的要求" ∪ "我们想要的" —— 两者**一起**置位；
 *   · `logits_ok_for_library` 是这条规则的**可判定形式**（宿主侧有单测），
 *     native 在填完 batch 后立刻自检，把"违反库约束"在调 decode **之前**就变成
 *     一条能归因的失败（`POOLING_CONTRACT`），而不是一次莫名其妙的 decode 失败。
 *
 * 为什么"多置几个 logits"没有代价：output 数组只在**我们要读的那几行**上被读，
 * 多留几行只是多占 `n_tokens * n_embd * 4` 字节的常驻 output buffer
 *（512 token × 1024 维 ≈ 2 MB），换来的是**不违反库的契约**。
 * 反过来（为了省这点内存去踩库的断言）代价是整条嵌入路径不可用。
 */
struct PoolPlan {
    bool valid = false;
    Pool pool = Pool::LAST;
    // ⚠ **原始** pool 值（0/1/2/3/-1）必须随计划一起走。
    // 为什么不能只留 `pool`：`Pool::LAST` 把 `NONE(0)` 与 `LAST(3)` 合并了，
    // 而这两者的池化结果在库的**两个不同 buffer** 里（前者 `embd.data`，
    // 后者 `embd_seq`）。判据要能问"这一档的向量从哪个 buffer 来"，
    // 就必须知道原始值 —— 只凭 `p.pool` 问不出来（这正是 LAST 漏掉的那次分岔）。
    int pool_type_raw = -1;
    SeqRow row = SeqRow::LAST_TOKEN;  ///< 句向量从哪一行读（见 SeqRow 的说明）
    int output_slot = -1;             ///< 非 LIB_POOLED 时要读的 output 下标
    int batch_last_slot = -1;         ///< 最后一个 token 在 output 数组里的下标（LAST 要用）
    bool read_all_rows = false;       ///< true = 库给了 pooled buffer，别再自己取一行
    std::vector<char> want_logits;    ///< 长度 == nTokens
    int output_count = 0;             ///< 预计的 output 数组长度
};

/**
 * 这个池化方式**由库自己算**，还是由我们按 token 平均算出来。
 *
 * ⚠ 这两条路的差别不是"实现细节"，而是**能不能这么用**：
 *   · `LAST` / `CLS` 是"从库给的那一段里挑一行" —— 任意挑一行都成立，
 *     所以只要保证我们挑的那一行确实在 output 里；
 *   · `MEAN` 是"整段的加权平均" —— 库的计算前提是**整段都在 output 里**，
 *     我们按部分行做的平均**不等于**库会给出的那个向量。
 *
 * 这个谓词存在的理由：库对"池化时哪些 token 必须在 output 里"是**有约束**的
 *（见 `plan_pooling` 上方那段），而约束对不同池化方式**不一样**。
 * 把"我们想要哪些行"（`want_logits`）与"库要求哪些行"（这里）分开，
 * 是为了让两者能各自演进、且在同一个地方对照 —— 见 `logits_ok_for_library`。
 */
inline bool library_computes_pooling(int poolTypeRaw) {
    return poolTypeRaw == 1;   // LLAMA_POOLING_TYPE_MEAN：库自己按 seq 取平均
}

/**
 * 库的 `llama_get_embeddings_seq()` 对这一档该不该给出 pooled buffer。
 *
 * ⚠ 这是**读哪个 buffer** 与**要不要我们归一化**的分界，三个用法都由它一处分岔：
 *   · `plan_pooling` 的 `read_all_rows`（"库给了 pooled buffer，别再自己取一行"）；
 *   · `read_from_seq`（读取侧的调用入口：走 `get_embeddings_seq` 还是 `ith`）；
 *   · `normalize_pooling`（库归过了的档我们不再归）。
 * 把它们抄成几处是本仓库反复记录的那类错：迟早只改一处，而症状是
 * "库里给的那一份被我们丢了一半"（读到了 pooled buffer 却仍自己取一行、
 * 或读对了却按另一套口径判失败）—— 静默、且只在某一档 pooling 上出现。
 */
inline bool library_gives_seq(int poolTypeRaw) {
    // ⚠⚠ 三档，不是两档 —— `LAST(3)` 漏掉过一次，代价是整条嵌入路径在
    // **生成式 decoder 嵌入模型**（Qwen3-Embedding，GGUF 里写 pooling=3）上
    // 条条 `范数=0`，而 BGE（CLS）照常能用，于是看起来像"这个模型不行"。
    //
    // vendor 库的实际布局（`llama-context.cpp` 里两处逐字对应的 switch，
    // 按 `cparams.pooling_type` 分岔）：
    //   · `NONE(0)`          → 逐 token 输出写进 `embd.data`（`get_embeddings_ith` 读它）；
    //   · `MEAN(1)/CLS(2)/LAST(3)` → 池化结果写进 `embd_seq`（`get_embeddings_seq` 读它），
    //                          **`embd.data` 一个字节都不写**。
    // 也就是说 `ith` 那条路的前提是 `pooling == NONE`（`llama.h` 明文：
    // `llama_get_embeddings()` "when pooling_type == NONE … otherwise returns NULL"），
    // 而 `LAST` 与 `MEAN/CLS` 一样**不**满足这个前提。
    //
    // 为什么漏掉 `LAST` 时读出来是**全零**而不是 null：`embd.data` 在
    // `embeddings=true` 时**照样分配**（只是不写），于是 `get_embeddings_ith`
    // 通过 `embd.data == nullptr` 那道判据、返回一个指向从未写入缓冲区的指针。
    // 零向量绕过 `no_output` 判据，直落 `ZERO_NORM`。
    //
    // 上游同一份库的参考实现（cui-llama.rn `rn-slot-manager.cpp` 的
    // `get_embedding_ptr` lambda）就是**先无条件试 `get_embeddings_seq`**、
    // 取不到才回落到 `ith` —— 它不分池化档，因为这个 buffer 对所有非 NONE 档
    // 都是**唯一的**池化出口。
    //
    // 关于"库归过没归过"：这一档**不关归一化** —— 库对池化结果**不做 L2**
    //（`build_pooling` 里只有 RANK 走 `cls_norm` 那个分类头，MEAN/CLS/LAST
    //  出来的都是**原始**池化向量）。归一化口径另有一条判据，见 `normalize_pooling`。
    return poolTypeRaw == 1 || poolTypeRaw == 2 || poolTypeRaw == 3;
}

/**
 * 库**算完句向量之后还会不会再归一化一次** —— 决定我们该不该自己归一化。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么必须有这一条（而不是"我们总归一化一次最保险"）
 * ═══════════════════════════════════════════════════════════════════════════
 * 库按 `normalize=[ctx 决定]` 走：在池化那一支上它**已经把向量归一化过**
 *（L2）。我们再归一化一次不会把方向改坏 —— 于是"多归一化一次"看起来是无害的。
 * 它有害的地方在于**归因**：库里已经在归一化，我们却仍在日志里说
 *「归一化失败（范数=0.5…）」—— 明明拿到了一个长度 0.5 的**合法**向量，
 * 却因为"我们期望它是 1"而判失败、丢掉这一条。用户看到的是"归一化失败"，
 * 于是去查自己的文本/模型，而真实原因是我们对**同一个量**用了两套口径。
 *
 * 更硬的一条：`normalize_pooling` 归 false 的那一档，**必须真的不去读那一行**。
 * 因为"库归一化过的那一段"与"我们直接读的原始那一段"不是同一个数
 *（前者是池化+归一化后的，后者是未池化的原始激活）—— 用 `l2_normalize`
 * 的返回值去判断"这一档能不能用"是**错的判据**：它会把"读错行"
 *（原始激活，量级可能极小）报成"库的向量有问题"。
 * 所以判据是：**先确定这一档的向量从哪里来，再只对它归一化** ——
 * 库里已经给的那一份（`read_all_rows` / `read_from_seq` true）原样信任，
 * 不再二次归一化。
 */
inline bool normalize_pooling(int poolTypeRaw) {
    // ⚠⚠ 这里**不再是** `library_gives_seq` 的背面 —— 那个等式被证伪了。
    //
    // 0.9.160 曾写成 `!library_gives_seq(t)`，理由是"库给 pooled buffer 的档，
    // 它自己已经归一化过"。查了 vendor 对应的上游源码（`llama-graph.cpp` 的
    // `build_pooling`，与本仓库 vendor `.so` 里 `result_embd_pooled` 那个张量名
    // 逐字对得上）：**库对池化结果不做任何 L2 归一化** —— 只有 `RANK` 那一支
    // 会过 `cls_norm` 那个分类头（`build_norm(..., LLM_NORM, ...)`），
    // `MEAN/CLS/LAST` 出来的都是**原始**池化向量。
    //
    // 那句"库自己归一化了"来自 `cui-llama.rn` 的 **RN 包装层**
    //（`rn-slot-manager.cpp` 里 `common_embd_normalize(...)` + 那句
    //  "Normalized embedding data: 0: %f …" 调试日志）。那一层**我们不走**：
    // 本 App 直接调 `llama_get_embeddings_seq` 这个 C API，拿到的是**未归一化**的。
    //
    // 所以本函数现在**恒为真** —— 所有档都由我们做 L2。判据见
    // `tools/embed_robust/check_pooling_wiring.py` 对它的正面要求。
    //
    // 保留这个函数（而不是删掉、直接在 native 里调 `l2_normalize`）的理由：
    // "这一档要不要归"是一个**有语义的**判断，将来若某档真的改由库归一化，
    // 改动点只有这里一处，而读取侧（`read_from_seq`）不必跟着动。
    (void) poolTypeRaw;
    return true;
}

/**
 * 由模型的 `pooling_type`（`llama_pooling_type` 的**原值**，不做枚举耦合）与
 * token 数算出池化计划。`poolTypeRaw`：0=NONE / 1=MEAN / 2=CLS / 3=LAST
 * （-1=UNSPECIFIED / 4=RANK）。
 *
 * ⚠ 为什么 UNSPECIFIED 归到 LAST 而不是报错：`llama_pooling_type(ctx)` 在
 * 部分模型上会返回 -1（GGUF 里没写这个键）。这一类**恰恰是生成式 decoder**
 * （Qwen3-Embedding 就是），它的正确池化就是 last-token —— 这是本仓库
 * 一直以来的行为，改成报错会让本来能用的模型全部不可用。
 * 而 RANK（rerank 模型）**必须**拒绝：它的输出是分类头、不是句向量，
 * 拿它当句向量检索的结果是"看起来有分数、语义全错"。
 *
 * 空 token（`nTokens <= 0`）时返回 `valid=false` —— 与"池化类型不支持"分开，
 * 但都让调用方丢弃这一条（绝不编造向量）。
 */
inline PoolPlan plan_pooling(int poolTypeRaw, int nTokens) {
    PoolPlan p;
    if (nTokens <= 0) return p;
    p.pool_type_raw = poolTypeRaw;
    switch (poolTypeRaw) {
        case 2:  p.pool = Pool::CLS;  break;   // LLAMA_POOLING_TYPE_CLS
        case 1:  p.pool = Pool::MEAN; break;   // LLAMA_POOLING_TYPE_MEAN
        case 0:                                // NONE（生成式 decoder）
        case 3:                                // LAST
        case -1: p.pool = Pool::LAST; break;   // UNSPECIFIED：见上方注释，按 last-token 处理
        default: return p;                     // RANK 等：**不猜**，拒绝
    }
    p.want_logits.assign((size_t) nTokens, 0);
    p.read_all_rows = library_gives_seq(poolTypeRaw);
    switch (p.pool) {
        case Pool::MEAN:
            // MEAN：库自己按 seq 做平均（`llama_get_embeddings_seq(ctx, 0)`），
            // 前提是**整段都在 output 里**。全部置 1 是唯一的正确做法。
            for (int i = 0; i < nTokens; i++) p.want_logits[(size_t) i] = 1;
            p.output_count = nTokens;
            p.row = SeqRow::LIB_POOLED;
            break;
        case Pool::CLS:
            // ⚠ CLS 有两件事要同时成立，别把它们混成一件：
            //
            // ① **给库的**：CLS 要求**整段都在 output 里**
            //    （vendor 判据原文见上文 `pooled embedding requires that all tokens
            //     are output`）。只置第 0 个的话 `llama_decode` 直接返回非 0 ——
            //    这就是"BGE 系条条失败"在 0.9.156 之后仍然复现的原因。
            //    → `want_logits` 整段置 1。
            //
            // ② **我们读哪一路**：CLS 的池化结果由库算、写进 `embd_seq`
            //    （`library_gives_seq(2)` 为真）→ 走 `get_embeddings_seq`。
            //    库那一份**就是**第 0 个 token 的池化向量（CLS 的定义），
            //    我们不需要、也**不应该**再去 `output` 数组里挑一行 ——
            //    `ith` 那条路（`embd.data`）对 CLS 根本没有数据。
            //    → `row = LIB_POOLED`、`output_slot = -1`。
            //
            // 0.9.160~0.9.161 这里写的是 `row = FIRST_OUTPUT / output_slot = 0`：
            // 读取点虽然被 `read_from_seq` 带到了 seq（所以 BGE 能用），但计划
            // 里的 `row` 说的是"去 output 数组读第 0 项" —— 一个**没被使用的**
            // 字段说着另一套做法，正是本仓库反复记录的那类伏笔。
            for (int i = 0; i < nTokens; i++) p.want_logits[(size_t) i] = 1;
            p.output_count = nTokens;          // 整段都进 output（满足库的 CLS 门禁）
            p.row = SeqRow::LIB_POOLED;
            break;
        case Pool::LAST:
            // LAST / NONE / UNSPECIFIED：句向量 = **最后一个 token 那一行**。
            //
            // ⚠ 这里曾经写成 `output_slot = 0`，理由是"我们只要了 1 个 output，
            // 所以它在 output 数组里是第 0 项" —— 那句话对，但**读到的不是
            // 最后一个 token**：我们只给 batch 的**最后一个**位置置了
            // `logits=1`，所以 `output_ids[0] == nTokens-1`，`ith(0)` 确实是
            // 最后一行。可一旦"库给的 output 比我们要的多"（CLS/MEAN 整段、
            // 或库自己补 `logits`），`output_ids[0]` 就变成**第 0 个 token** ——
            // 生成式 decoder 里那是 BOS，没有前文，隐藏态量级极小，
            // 归一化必然拒绝（真机日志：`范数=3e-30`，13/18/25 个 token 全败）。
            // 现在语义**显式**成"最后一个 token 那一行"，不再依赖 output 数组
            // 恰好只有 1 项这个**别处**的性质（它由另一段代码决定，改那里没人会
            // 想到此处 —— 这就是本 bug 的成因）。
            //
            // 只给最后一个位置置 `logits=1` 仍然是最省的（库对 NONE/LAST 只要求
            // "至少一个"output），而它同时保证了 `batch_last_slot` 一定在 output 里。
            p.want_logits[(size_t) (nTokens - 1)] = 1;
            p.output_count = 1;
            p.batch_last_slot = 0;             // 只我们置的那一项 → 它在 output 里排第 0
            // ── 这一档里"读哪一路"**必须再分岔一次** ────────────────────────
            // `Pool::LAST` 这一支覆盖三个**原始** pool 值：`NONE(0)` / `LAST(3)`
            // / `UNSPECIFIED(-1)`。三者的**语义**都是 last-token，但库把它们放在
            // **两个不同的 buffer** 里：
            //   · `NONE(0)`      → 逐 token 写 `embd.data`，读 `ith`；
            //   · `LAST(3)`      → 池化后写 `embd_seq`，读 `seq`（`embd.data` 不写！）。
            // 所以这里的判据**不能是 `p.pool`**（它把两者合并了），必须是原始值 ——
            // 也就是 `library_gives_seq(poolTypeRaw)`。
            //
            // 漏掉这一次分岔的代价（本次真机）：`pooling=3` 的 Qwen3-Embedding
            // 走 `ith` 去读一个**从未写入**的 buffer，拿到全零 →
            // `范数=0` 条条失败；而 `pooling=2` 的 BGE 走 `seq` 照常能用，
            // 于是看起来像"这个模型不行"。
            p.row = library_gives_seq(poolTypeRaw) ? SeqRow::LIB_POOLED : SeqRow::LAST_TOKEN;
            p.output_slot = p.row == SeqRow::LIB_POOLED ? -1 : p.batch_last_slot;
            break;
    }
    if (p.row == SeqRow::LIB_POOLED) {
        // 库给 pooled buffer 的档：`output_slot` 不用，留 -1（而不是 0）——
        // 一个"看起来能用的下标"会在将来被误当成读的位置。
        // ⚠ 这里**只对 `row` 已经是 LIB_POOLED 的档**再兜一次底：
        // MEAN 在 switch 里就设了它，LAST 按 `library_gives_seq` 分岔后也可能设它。
        p.output_slot = -1;
    } else if (p.output_slot < 0) {
        return PoolPlan();                     // 防御：不该发生，宁可拒绝也不猜
    }
    p.valid = true;
    return p;
}

/**
 * 池化计划**是否自洽** —— 三个字段（`row` / `output_slot` / `want_logits`）
 * 必须互相支持：只改一处就是"我们没要这一行、却去读它"（返回 null →
 * 检索全空而接口 200）或"读的不是语义要的那一行"（BGE 上零范数，
 * 生成式 decoder 上读到 BOS）。这两种都**不报错**，所以由纯函数一次判清。
 *
 * 判据（每条都对应一次真实故障）：
 *   · `row == LAST_TOKEN` 时，**去 output 数组读的那一项**必须真的是 batch 里
 *     最后一个 token 那一行。判据不是"`output_slot` 等于某个数"，而是
 *     `output_slot_to_batch(p, output_slot) == nTokens-1` —— 见下面那个换算函数。
 *   · `row == LIB_POOLED`（MEAN / CLS / LAST(3)）时，整段/最后一行的
 *     `want_logits` 也必须是对的（那两档的库门禁不同，见 `logits_ok_for_library`）。
 *   · `row == LIB_POOLED`（MEAN）时，整段都必须在 output 里（库的池化前提）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠⚠ 这一版**又修了一次同一个式子**，因为上一版的判据把两件事当成了一个：
 * ═══════════════════════════════════════════════════════════════════════════
 * `output_slot` 是 **output 数组里的排位**（`ith(i)` 的实参），而
 * `batch_last_slot` 上一版被写成"== nTokens-1"（**batch 位置**）——
 * 于是判据要求一个 batch 下标去等于一个 output 下标，**两者只在
 * "output 恰好整段都在"时相等**。LAST 档只给最后一位置 logits，
 * output 里只有 1 项，它的**排位是 0**、而**位置是 nTokens-1**：
 * 判据要求 0 == nTokens-1，恒假 → **每一条嵌入都被判"计划不自洽"**。
 *
 * 真机日志（0.9.160 之后）逐字对应：
 *   `[embed] 池化计划不自洽：pooling=3 row=0 output_slot=0`
 * —— `row=0`(LAST_TOKEN) / `output_slot=0` 是**正确的**（那一项就是最后一行），
 * 被判不自在的是判据自己。
 *
 * 所以这一版把"排位 → 位置"的换算**显式成一个函数**（唯一真相来源），
 * 判据只问"按这个换算，读的那一项是不是语义要的那一行"。
 */
inline bool plan_is_coherent(const PoolPlan & p, int nTokens);

/**
 * **output 数组里第 `slot` 项对应 batch 的哪个位置** —— 唯一真相来源。
 *
 * `output_ids` 只收 `llama_batch.logits[j] != 0` 的 token，**按 j 升序连续排列**，
 * 所以第 `slot` 项对应的 batch 位置就是"`want_logits` 里第 `slot` 个 1 的下标"。
 *
 * 为什么要它单独存在：上一版判据把"output 排位"直接当成"batch 位置"比大小，
 * 两者**只在特定布局下相等**（output 恰好整段、或恰好只读最后一项）。
 * 把换算写成函数之后，"读哪一项"与"那一项是不是最后一行"由一个式子说清，
 * 谁都不能再各写一个字面量。
 *
 * 返回 -1 表示越界（该排位在 output 里不存在 —— 那时读取点会拿到 null）。
 */
inline int output_slot_to_batch(const PoolPlan & p, int slot) {
    if (slot < 0) return -1;
    int seen = 0;
    for (int i = 0; i < (int) p.want_logits.size(); i++) {
        if (!p.want_logits[(size_t) i]) continue;
        if (seen == slot) return i;
        seen++;
    }
    return -1;   // 该排位超出 output 数组
}

inline bool plan_is_coherent(const PoolPlan & p, int nTokens) {
    if (!p.valid || nTokens <= 0) return false;
    if ((int) p.want_logits.size() != nTokens) return false;
    switch (p.pool) {
        case Pool::MEAN:
            if (p.row != SeqRow::LIB_POOLED) return false;
            for (int i = 0; i < nTokens; i++) if (!p.want_logits[(size_t) i]) return false;
            return true;
        case Pool::CLS:
            // CLS 的向量来自库的 `embd_seq`（`library_gives_seq(2)` 为真）——
            // 与 MEAN 同一条路。`output_slot` 必须留 -1。
            if (p.row != SeqRow::LIB_POOLED) return false;
            if (p.output_slot != -1) return false;
            // 但**给库**的 logits 仍必须整段置 1（CLS 的前置校验）。
            for (int i = 0; i < nTokens; i++) if (!p.want_logits[(size_t) i]) return false;
            return true;
        case Pool::LAST: {
            // ⚠ 这一档读哪一路**由原始 pool 值决定**，不由 `pool` 决定 ——
            // `Pool::LAST` 同时覆盖 `NONE(0)`（读 ith）与 `LAST(3)`（读 seq）。
            // 判据必须问 `library_gives_seq(p.pool_type_raw)`：写成
            // "`row == LAST_TOKEN` 就对了"会在 `pooling=3` 上**绿着**通过，
            // 而那一档真的去读了一个从未写入的 buffer（本次失效）。
            if (library_gives_seq(p.pool_type_raw)) {
                // LAST(3)：库把池化结果放进 `embd_seq`，走 `get_embeddings_seq`。
                // `output_slot` 必须留 -1（不给一个"看起来能用"的下标）。
                return p.row == SeqRow::LIB_POOLED && p.output_slot == -1;
            }
            // NONE(0) / UNSPECIFIED(-1)：库逐 token 写 `embd.data`，读 ith。
            if (p.row != SeqRow::LAST_TOKEN) return false;
            // 语义要的是"最后一个 token 那一行"：读的那一项换算回 batch 位置，
            // 必须落在 nTokens-1。写成"output_slot == 0"或"== nTokens-1"
            // 都是在拿**排位**与**位置**比大小 —— 那正是上一版的错。
            if (output_slot_to_batch(p, p.output_slot) != nTokens - 1) return false;
            // 排位自带的那个字段也必须与之一致（它给读取点用，不能各写一个数）
            if (output_slot_to_batch(p, p.batch_last_slot) != nTokens - 1) return false;
            if (p.batch_last_slot != p.output_slot) return false;
            return true;
        }
    }
    return false;
}

/**
 * **库那一层**对 `logits[]` 的前置约束是否被满足 —— 只有"是否"，没有"怎么办"。
 *
 * 这两条规则直接来自 vendor 里的判据（原文见 `plan_pooling` 上方），
 * 所以它们是**可判定的**，不该留在 native 里靠"填对了就行"：
 *   · CLS(2)  / MEAN(1)：**整段**都必须在 output 里（`all_outputs` 为真）；
 *   · NONE(0) / LAST(3) / UNSPECIFIED(-1)：**至少一个** output（否则库的
 *     `n_outputs >= 1` 断言失败，那是**进程级崩溃**，不是"这一条失败"）；
 *   · RANK(4) / 未知：我们根本不提交（`plan_pooling` 已经拒了），这里也算不满足。
 *
 * 为什么值得单独一个函数而不是"在填 batch 的地方 `continue` 掉"：
 * 违反这条约束的后果是**整条嵌入路径不可用**（decode 全败），而"不可用"在
 * 真机上会被读成"模型/内存有问题"。把它做成一个有名有据的判据之后，
 * native 才能在调 `llama_decode` **之前**把它报成 `pooling_contract` ——
 * 用户拿到的是一句"该池化方式要求整段都在 output 里"，而不是一句 decode 失败。
 */
/**
 * 这一条向量该**从哪个 API 读** —— `get_embeddings_seq`（池化后的那个）还是
 * `get_embeddings_ith`（逐 token 的那个）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠⚠ 这是"BGE 系条条失败"在 9b7335a 之后**仍然**复现的直接原因
 * ═══════════════════════════════════════════════════════════════════════════
 * `llama.h` 对这两个 API 的**取值前提**是分开写的（原文见 `llama_get_embeddings`
 * 与 `llama_get_embeddings_ith` 的注释）：
 *
 *   · `llama_get_embeddings(ctx)` 只在 **`pooling_type == NONE`** 时有效，
 *     "otherwise, returns NULL"；而 `get_embeddings_ith(ctx, i)` 的定义就是
 *     `get_embeddings(ctx) + output_ids[i]*n_embd` —— 它是**建在那个前提上**的。
 *   · `llama_get_embeddings_seq(ctx, seq_id)`："Returns NULL if pooling_type is
 *     NONE / RANK …; otherwise: float[n_embd]" —— 这**才是**池化模式的入口。
 *
 * 而 BGE 声明的是 CLS（本仓库读到 `pooling=2`），**不是 NONE**。
 * 早先的实现只把 MEAN 走了 `seq`，CLS / LAST 都走 `ith` —— 对 LAST 恰好成立
 *（LAST 与 NONE 是同一形态），**对 CLS 就是读错了 buffer**：库把池化结果写进
 * `result_embd_pooled` 那个独立张量，而 `ith` 指向的是逐 token 的输出区。
 * 读出来是**全零**（不是 null —— null 会被下面的判据拦住并报 no_output），
 * 于是恰好落进 `范数=0` 这一档，正是用户看到的那一行：
 *
 *     [embed] 归一化失败（范数=0，n_tokens=13，pooling=2，n_embd=1024，n_embd_out=1024）
 *
 * 判据取"该池化方式**是不是库自己算的**"（= `library_computes_pooling` 的推广）：
 * 只要库会自己池化（MEAN / CLS），就该读 `seq`；只有库不池化的那几档
 *（NONE / LAST / UNSPECIFIED）才逐 token 读 `ith`。
 *
 * ⚠ 这里**不做**自动回落（先试 seq 再试 ith）：两条路给出的是**不同的向量**，
 * 静默回落等于"哪个不空用哪个"，会让同一条文本在不同的库版本上得到不同的语义 ——
 * 那正是本仓库反复在堵的那类"看起来正常、其实换了含义"的失效。
 * 走错路时必须**报出来**，由 `NO_OUTPUT` / `ZERO_NORM` 归因。
 */
inline bool read_from_seq(int poolTypeRaw) {
    return library_gives_seq(poolTypeRaw);         // MEAN / CLS / LAST：库自己池化
}

inline bool logits_ok_for_library(int poolTypeRaw, const std::vector<char> & want, int nTokens) {
    if (nTokens <= 0 || (int) want.size() != nTokens) return false;
    int ones = 0;
    for (char c : want) if (c) ones++;
    switch (poolTypeRaw) {
        case 2:   // CLS：整段必须都在 output 里
        case 1:   // MEAN：同上
            return ones == nTokens;
        case 0:   // NONE
        case 3:   // LAST
        case -1:  // UNSPECIFIED
            return ones >= 1;
        default:  // RANK / 未知：不提交
            return false;
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  warm-up 前向：把 ctx 里那块 output buffer 从"未初始化"写成"被写过"
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么必须有这一族函数（而不是"反正第一条会写它"）：
 * `embeddings=true` 时 ctx 里那块 output buffer 是**新分配的未初始化内存**，
 * 而"读一行"要读的字节数（按 `n_embd` 走）可能多于"写一行"写下的
 *（库按 `n_embd_out` 分配）。两者不等时，第一条读到的**有一部分是没被写过的
 * 内存** —— 里面可能是 NaN。于是现象是"刚加载时某几条失败，之后就好了"：
 * 成功的那次把 buffer 写正常了，失败取决于那块内存之前被谁写过，
 * 与文本内容**完全无关**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠⚠ warm-up 的**目的**不是"跑一次前向"，是"让每一行都被写过"
 * ═══════════════════════════════════════════════════════════════════════════
 * 这个区别是本文件里第二处"两个东西被当成一个"（第一处见 `SeqRow`）。
 * 早先的实现把 warm-up 写成"用 BOS 跑一次，只要一行输出"，理由是
 * "库对 NONE/LAST 只要求至少一个 output"。那条推理**每一步都错**：
 *
 *   · **CLS 上直接 abort**。库的门禁在 `llama_batch_allocr::init`（vendor
 *     `librnllama.so` 里符号 `_ZN18llama_batch_allocr4initERK11llama_batchRK11llama_vocabPK14llama_memory_ijjb`），
 *     检查的是 **`llama_batch.logits[]`** 覆盖了所有 input token 没有：
 *       `%s: pooled embedding requires that all tokens are output (n_outputs_all = %d, n_tokens_all = %d)`
 *     而 `llama_batch_allocr::init` 在 `llama_context::decode` 的**正常路径**上 ——
 *     `LLAMA_ABORT` 在 release（NDK 默认 `-DNDEBUG`）里就是 `abort()`，
 *     于是 BGE 系（`pooling=2`）**每次加载嵌入模型都走进进程级崩溃**，
 *     连"warm-up 失败但不影响加载"这句兜底日志都来不及打。
 *
 *   · **"库会替我们补"补的是真实 input**。`llama_context::decode` 里有另一条
 *     原文：`%s: embeddings required but some input tokens were not marked as
 *     outputs -> overriding`。它把没标 output 的 **input token** 补上 ——
 *     而 warm-up 的 batch 只有 BOS **一个** token，"补"完了还是只有第 0 行。
 *     于是**warm-up 想干的事根本没干成**：除了第 0 行，buffer 里其余字节
 *     一个都没被写过。这比"失败"更坏：它是一条**假绿**（日志说成功、
 *     实际没生效）。
 *
 * 所以 warm-up 的 batch 必须**与真实编码路径同形**：同样的 token 数、
 * 同样的 `logits[]` 布局（照 `plan_pooling` 置位）。这样一来
 *   ① 不再踩 CLS 门禁（整段都在 output 里）；
 *   ② 不再依赖"库会替我们补"；
 *   ③ 写满的行数与真实路径**逐行一致** —— 它去掉的那个不确定性，
 *      正是真实路径会读到的那块内存。
 *
 * 判据做成纯函数（宿主侧有单测），所以"warm-up 有没有满足库的门禁"
 * 是一条**可判定**的断言，而不是"跑一遍看日志"。
 */

/** warm-up 要用的 token 数下限。 */
/**
 * 一条固定短文本的 token 数**下限**，低于它 warm-up 就退化：
 *   · 1 个 token 时 `plan_pooling` 的 CLS/MEAN 整段 = 1 行，与早先那版
 *     "只写第 0 行"**等价** —— 等于白做；
 *   · 2 个 token 起，`LAST_TOKEN` 档读的那一行（`nTokens-1`）与第 0 行
 *     才不是同一个位置，"每一行都被写过"才有内容。
 * 取 2 是**下限**不是推荐值：真实文本远长于此，这里只要跨过"退化点"。
 */
static const int kWarmupMinTokens = 2;

/**
 * warm-up 的 batch 该长什么样 —— 与真实编码路径**同一个形状**。
 *
 * `plan` 由 `plan_pooling(poolTypeRaw, nTokens)` 给出（即真实路径用的那一份），
 * 本函数只回答"这个计划能不能给 warm-up 用"，不另立一套置位规则。
 *
 * 判据（每条都对应一次真实故障）：
 *   · `nTokens >= kWarmupMinTokens` —— 1 个 token 时 CLS/MEAN 的整段只有一行，
 *     与早先"只写第 0 行"等价，warm-up 变成假绿（见上面那段推导）；
 *   · `plan.valid` —— RANK / 未知池化方式不猜（与真实路径同一道闸）；
 *   · `plan.want_logits.size() == nTokens` —— 长度错位 = 置位越界；
 *   · `logits_ok_for_library(...)` —— **给库的门禁**必须满足。这一条是本次
 *     失效的直接判据：早先那版在 CLS 上让它恒假，而没有任何东西去问它。
 */
inline bool warmup_plan_usable(const PoolPlan & plan, int poolTypeRaw, int nTokens) {
    if (nTokens < kWarmupMinTokens) return false;
    if (!plan.valid) return false;
    if ((int) plan.want_logits.size() != nTokens) return false;
    if (!logits_ok_for_library(poolTypeRaw, plan.want_logits, nTokens)) return false;
    return true;
}

/**
 * warm-up **是否真的把每一行都写过了** —— 由计划反推，不靠运行期观察。
 *
 * "被写过"= 该行在 `logits[]` 里被标了 output（库只对 output 行写结果）。
 * 所以判据是：`want_logits` 里 1 的个数 **等于** `nTokens` ——
 * 即整段都在 output 里。CLS / MEAN 天然如此；NONE / LAST 档**按定义做不到**
 * （它们只要一行），这是库的契约决定的，不是我们能选的。
 *
 * ⚠ 对做不到整段的档**不判失败**，但要如实回答"写满了没有" ——
 * 因为那一档 warm-up 的收益本来就有限（`embd.data` 只被写过 1 行），
 * 而"假装写满了"会让下一个人据此以为未初始化内存这个不确定性已经消除。
 * 调用方（native）据此决定日志措辞，不据此拒绝加载。
 */
inline bool warmup_wrote_all_rows(const PoolPlan & plan, int nTokens) {
    if (nTokens <= 0 || (int) plan.want_logits.size() != nTokens) return false;
    for (int i = 0; i < nTokens; i++) if (!plan.want_logits[(size_t) i]) return false;
    return true;
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  多条目合批：把 n 条文本塞进**一次** `llama_decode`
 * ═══════════════════════════════════════════════════════════════════════════
 * PR #273 拿回来的是"一半算力"（线程数按本机核数），它**明确没走**的另一半
 * 就是这里：此前每条文本各跑一次前向，n 条 = n 次 `llama_decode`。每次
 * decode 的固定开销（建图 / 分配 / 调度 / 同步）与 token 数**无关**，
 * 所以小条目（400 字 ≈ 几百 token）上这一份开销占了大头 ——
 * 这正是"1 秒 1.3 条"里除多核之外的另一块白扔。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠⚠ 为什么"合批"必须先把**条目隔离**证明清楚，而不是"反正以前也是清 KV"
 * ═══════════════════════════════════════════════════════════════════════════
 * 单条路径每轮开头 `llama_memory_seq_rm(seq 0, -1, -1)` 清 KV —— 那是**隔离**
 * 的做法（上一条的 KV 不许被这一条看见）。合批时显然不能"每条之间清 KV"
 * （那就退化成逐条了），于是隔离只能靠 **`llama_batch` 的 `seq_id`**：
 *
 *   · 第 j 条文本的所有 token 标 `seq_id = j`、`pos` 从 0 重新计；
 *   · 库在建注意力掩码时按 `seq_id` 分块（vendor `.so` 里
 *     `llm_graph_context::build_inp_attn_kv` 那一支），**不同 seq 之间不互相看**。
 *
 * 也就是说：合批的**正确性前提**是"库真的按 seq_id 隔离了注意力"，
 * 而不是"我们没清 KV 但运气好"。这一条在下面被做成**可判定的**判据
 * （[batch_is_valid]）—— 它不看库，只看我们填给库的东西自不自洽：
 *   ① 每条各自占一段连续 slot；
 *   ② 每条的 `pos` 从 0 开始、连续递增（seq 内位置，不是全局位置）；
 *   ③ 每条至少 1 列 output（库对 NONE/LAST 要求 ≥1，见 `logits_ok_for_library`）；
 *   ④ 条数 ≤ 库能容纳的 seq 数上限。
 * 判据全绿只说明"我们没写错"，隔离本身由库的 seq 语义保证 —— 两者分开记，
 * 免得将来隔离出问题时误以为是我们这边的问题（方向读反，排障全错）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠ 池化口径**不许**因为合批而变：向量必须与逐条跑时**逐位相同**
 * ═══════════════════════════════════════════════════════════════════════════
 * 同一条文本，合批跑出来的向量与单条跑出来的必须是同一个（否则"建库用的
 * 是哪条路"这件事会渗透进检索结果 —— 用户改了批大小，检索命中率就变了，
 * 而没有任何日志指向这里）。所以：
 *   · `pos` 每 seq 从 0 起（单条路径也是从 0 起）；
 *   · 池化计划由**每条的 token 数**各自算（`plan_pooling(poolType, nTok_j)`）——
 *     不是"整批共用一个计划"：CLS/MEAN 要求**该条整段**都在 output 里，
 *     拿整批的 token 数去套会在条目长度不等时错位；
 *   · 归一化仍由我们做（`normalize_pooling` 恒为真）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠ 哪些档**不能**合批：MEAN / CLS / LAST(3)—— 它们读的是 `embd_seq`
 * ═══════════════════════════════════════════════════════════════════════════
 * `llama_get_embeddings_seq(ctx, seq_id)` 是**按 seq_id 取**的，理论上合批可用；
 * 但 `embd_seq` 在库那一侧的分配口径（每个 seq 一行 vs 全局一行）在本仓库
 * **没有验过**（vendor 版本每季在动）。而池化口径一旦漂移，症状是
 * "合批的向量与单条的向量不一样"—— 静默、只在某些条目上体现、且看起来像
 * "模型抽风"。所以本轮**只对 `pooling == NONE(0)` / `UNSPECIFIED(-1)` 合批**
 * （走 `ith` 那条路，逐 token 输出，`output_ids` 的排位是我们自己填的、
 * 可判定），其余档**回落逐条** —— 回落要有日志，不许静默。
 *
 * `UNSPECIFIED(-1)` 归进可合批这一支的理由与 `plan_pooling` 一致：
 * 它按 last-token 处理，而 last-token 这一档在 `library_gives_seq` 里是 false
 * （只有 MEAN/CLS/LAST(3) 是 true）—— 两条判据由**同一个函数**分岔，见下。
 */

/** 合批里一条文本的 token 段（`[begin, begin+n)`）与它占的 seq 号。 */
struct BatchRow {
    int seq = -1;      ///< 这一条用的 `seq_id`（= 它在批里的下标）
    int begin = -1;    ///< 该条第一个 token 在批里的全局下标
    int n = 0;         ///< 该条的 token 数（> 0）
};

/** 一次合批的完整计划 —— 由纯函数算出，native 只负责照填 `llama_batch`。 */
struct BatchPlan {
    bool valid = false;
    int n_tokens = 0;            ///< 整批 token 数（所有条目之和）
    int n_rows = 0;              ///< 条目数（= 用掉的 seq 数）
    std::vector<BatchRow> rows;
    std::vector<char> want_logits;  ///< 长度 == n_tokens，逐 token 的 output 标记
    // ⚠ 类型是 `int` 而**不是** `llama_seq_id` / `llama_pos`：本文件不依赖 llama
    //（它要能被宿主侧单测直接编译，见文件头）。native 填 `llama_batch` 时逐个转。
    // 把类型换成 llama 的会让这个头文件第一次需要 llama.h —— 那一步走错之后，
    // 这里全部判据就再也不能离线跑了（而它们恰恰是唯一能离线跑的部分）。
    std::vector<int> seq_of;  ///< 长度 == n_tokens，每个 token 属于哪个 seq
    std::vector<int> pos_of;  ///< 长度 == n_tokens，每个 token 在**自己 seq 内**的位置
};

/**
 * 这一档池化**能不能合批**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ⚠⚠ 0.9.208 起：**所有档都能合批** —— 这条判据不再排除任何一档
 * ═══════════════════════════════════════════════════════════════════════════
 * 上一版这里写的是 `return !library_gives_seq(poolTypeRaw);`，理由：
 * "库自己池化的档（MEAN/CLS/LAST）向量取自 `embd_seq`，那份 buffer 在多 seq
 * 下的布局**本仓库没验过**"。那是**当时的诚实**（没验过就不放行），
 * 但它把 BGE（CLS）与 Qwen3-Embedding（LAST）挡在合批之外 —— 也就是
 * 用户手上那两个模型**一条都没合批上**，一次前向永远只编一条。
 *
 * 这一轮把"没验过"补成了"验过"（判据见 [pooled_vector_slot] 与下面的推导），
 * 于是这条闸**不再需要**：`embd_seq` 在多 seq 下就是**每个 seq 一行**，
 * 第 j 条的向量在 `llama_get_embeddings_seq(ctx, j)` 那一行 —— 与单条路径
 * （`get_embeddings_seq(ctx, 0)`）是**同一个 API**，只是实参不同。
 *
 * ⚠ 为什么这个函数**还留着**（而不是删掉让所有档都进合批）：它是"合批的
 * 适用档"这**一条判据的唯一落点**。删掉它，"哪些档能合批"就散进
 * `plan_batch` 的调用点与 native 的 if 里 —— 而本仓库反复记录的那类错正是
 * "同一份档位清单被抄成两份，迟早只改一处"。保留它、让它恒为真、并让
 * 判据锚住"它必须仍然被调用"（`check_batchable_scope.py`），
 * 比删掉它更安全：下一档（RANK 之类）出现时，改动点仍然只有这一处。
 *
 * ⚠ 唯一**仍然**被拒绝的档是 `plan_pooling` 本来就拒的那几个（RANK / 未知）——
 * 那一条在 `plan_batch` 里由 `plan_pooling(...).valid` 兜住（它对每一条都问一次），
 * 不必在这里再列一遍清单。
 */
inline bool batchable_pooling(int poolTypeRaw) {
    // 只认"`plan_pooling` 认的档"。清单本身仍然只有 `plan_pooling` 一份 ——
    // 这里**不列**任何具体档位字面量（列了就是第二份清单）。
    // `nTokens` 取 1：这里问的是"这一档"，不是"这一条能不能编"；
    // 空 token 由 `plan_batch` 逐条判（它拿到的是每条的真实 token 数）。
    return plan_pooling(poolTypeRaw, 1).valid;
}

/**
 * 合批时**第 j 条的池化向量**该从哪里取 —— `embd_seq` 里的哪一行。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么需要这一个函数：多 seq 下 `embd_seq` 的布局**是这次唯一的新知识**
 * ═══════════════════════════════════════════════════════════════════════════
 * 单条路径读的是 `llama_get_embeddings_seq(E.ctx, 0)` —— 实参写死 0，
 * 因为那时只有一个 seq。合批引入 `n` 个 seq 之后，"第 j 条的向量在哪一行"
 * 就成了一个**必须有判据的问题**，而猜错的症状是**静默**的：
 *   · 若库其实是"全局一行"（所有 seq 的池化结果挤在同一行），
 *     那 `get_embeddings_seq(ctx, 1..n-1)` 取到的是**同一个向量**
 *     （或越界/null）—— 8 条不同的文本会得到 8 个一模一样的向量，
 *     检索结果像是"总是命中同几条"，而接口 200、日志干净；
 *   · 若库是"每 seq 一行"而我们只取第 0 行，那 8 条文本共用第 0 条的语义。
 *
 * 判据（三条，都锚在 **vendor `.so` 的字符串**上，可在本机核对）：
 *   ① `embd_seq` 是 `llama_get_embeddings_seq(ctx, seq_id)` 的**唯一**出口 ——
 *      该 API 的实参是 `seq_id`，库按它索引（头文件语义），所以"每个 seq
 *      一个出口"这件事**由 API 的形状决定**，不是拟合出来的；
 *   ② 库对 `n_ctx` 的切分是 `n_ctx / n_seq_max`（`.so` 里逐字：
 *      `%s: n_ctx is not divisible by n_seq_max - rounding down to %u`）——
 *      即每个 seq **各自**有一段独立的位置区间与 KV，池化也逐 seq 做；
 *   ③ `n_seq_max` 上限是**每 seq 的 token 数**（`.so` 里 `n_tokens % ubatch.n_seqs_unq == 0`
 *      与 `ubatch.n_tokens == n_seq_tokens * n_seqs`），也就是说库把
 *      "n 个 seq"当成 n 条等长/不等长的独立序列处理 —— 不等长时它自己
 *      用 `split_equal` 补齐。**每 seq 一行**是这套语义下唯一自洽的布局。
 *
 * ⚠ 这条判据**不是**"跑一遍看它不报错"：不报错在两个方向上都成立
 *（取同一个向量不报错、越界可能返回 null 也可能返回别的行的指针）。
 * 它是"我们按 API 的语义取第 j 行"，由 native 侧照填、由下面的
 * [pooled_slot_is_row] 判"这个下标在不在批内"。
 */
inline int pooled_vector_slot(int rowIdx) {
    // 行号就是 seq 号 —— 由 `plan_batch` 保证（`BatchRow::seq == j`，
    // 且 `batch_is_valid` 逐条要求 `r.seq == j`）。
    return rowIdx;
}

/**
 * 合批里"第 `slot` 行的池化向量"这个取值是否**落在这批之内**。
 *
 * 判据只回答"我们填给库的东西自不自洽"（与 `batch_is_valid` 同一口径）：
 *   · `slot >= 0`；
 *   · `slot < n_rows` —— 批里只有 n 行，取第 n 行是读**别的批**（或未写入的行）。
 *
 * 为什么单独一条：`llama_get_embeddings_seq` 在下标越界时的行为**没有契约**
 *（可能 null、可能返回一个"看起来能用"的指针）。而"读到一个看似正常的
 * 别的行的指针"正是本仓库最不想要的那类失败（长度、维度、归一化全对，
 * 语义是别人的）。所以越界必须在**调用之前**变成一条能归因的拒绝。
 */
inline bool pooled_slot_is_row(int slot, int nRows) {
    return slot >= 0 && nRows > 0 && slot < nRows;
}

/**
 * 整批 token 数**装得进库的 `llama_batch`** 吗 —— 装不进时必须拦在
 * `llama_decode` **之前**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么这一条**必须**存在：装不进时库的报应不是"这一批失败"，是**进程没了**
 * ═══════════════════════════════════════════════════════════════════════════
 * `llama_batch_allocr::init`（在 `llama_context::decode` 的**正常路径**上）
 * 有一条断言（vendor `.so` 里逐字）：
 *
 *     LM_GGML_ASSERT(n_tokens_all <= cparams.n_batch) failed
 *     /Users/.../cpp/llama-context.cpp:1711
 *
 * release（NDK 默认 `-DNDEBUG`）里断言失败 = `abort()` —— 不是"这一批失败"、
 * 不是"回落逐条"，而是**整个进程当场消失**。用户看到的是"建索引闪退"，
 * 而日志的最后一行是知识库进度（没有任何一行说得出"批太大了"）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么"批大小 8"这件事**必然**撞上它（真机 104 条就复现了）
 * ═══════════════════════════════════════════════════════════════════════════
 * 单条路径一条一次 `llama_decode`，每次最多 `n_ctx_seq - 1` 个 token ——
 * 与 `n_batch` 无关（`n_batch` 默认 2048，而 per-seq 预算 512）。
 * 合批把 **8 条塞进同一个 batch**：8 × 511 = 4088 > 2048 = `n_batch`。
 * 于是"某 8 条恰好都接近截断上限"的那一批**必崩** —— 与文本内容无关，
 * 只与"这一批里长条目凑够了几条"有关。这解释了为什么它看起来像随机闪退：
 * 104 条里只要有一批凑齐 3 条长文本（3×511=1533 还安全，4 条就 2044 贴边），
 * 越往后越容易撞上。
 *
 * ⚠ 判据取的是"**整批** token 数 vs `n_batch`"，**不是**"每条 token 数 vs
 * `n_ctx_seq`"：前者是库对**一次提交**的门禁（撞上去是 abort），后者是我们
 * 自己的截断口径（超了只是这一条被截短）。两者长得像、后果完全不同 ——
 * 把后者当成前者（或反之）时，一边是"能跑但算错"，一边是"进程没了"。
 *
 * `n_batch <= 0` 视为"库没报出来"（取不到），此时**放行**：判据不成立时
 * 宁可让库报它自己的错，也不要让一个取不到的数把整条合批路径永久关掉 ——
 * 但那条路必须留日志（见 `nativeEmbedBatch` 里那一处）。
 */
inline bool batch_fits_n_batch(int nTokens, int nBatch) {
    if (nTokens <= 0) return false;
    if (nBatch <= 0) return true;     // 取不到 -> 不拦（见上方说明）
    return nTokens <= nBatch;
}

/**
 * 这一批里**从起点开始能一次提交**的条数 —— 只有它在**真的装不下**时才小于总数。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么"交给库自己按 ubatch 切"救不了这一批
 * ═══════════════════════════════════════════════════════════════════════════
 * 库确实会按 `n_ubatch` 再切（`ubatch.n_tokens == n_seq_tokens * n_seqs`），
 * 所以"一条 400 字的 8 条"在**物理**上是可行的 —— 但那是"库内部切"，
 * 前提是**我们提交的那一次**先过 `n_tokens_all <= n_batch` 那道断言。
 * 断言在切分**之前**，所以进程在那之前就没了。
 *
 * 于是出口只剩"提交之前按**整条**切成几段"，而切法只有这一条：
 *   · 装得下（返回值 == 全部条数）-> 原样提交（**一次** `llama_decode`，
 *     合批的收益就是这一次）；
 *   · 装不下 -> 从这个起点开始能一次提交的**条数**就是本函数的返回值，
 *     调用方**按这个条数切出一段提交，余下几条留在同一批里接着编**
 *    （0.9.210）。不变量是"所有活条都提交过"，**不是**"一次提交完"——
 *     因此调用方返回的向量数组仍是**满的**，落盘行数也不变。
 *
 * ⚠ 为什么不"只提交前几条、余下**记成失败**"：那几条会进 `buildIndex` 的
 * `failedIdx` → 走 `savePartial` → **整个库**被标成"索引待重建"，而用户看到的
 * 是"这几条坏了"。真相只是"这一批排不下"—— 那属于**合批这一层**的事，
 * 不该渗透成"这个库要重建"。切段**不是**那个东西：余下几条在同一个循环里
 * 继续提交，一行都不进 `failedIdx`。
 *
 * ⚠ 也**不许**把某一条的 token 切成两半提交：那一条的池化结果就不是它自己的
 * 语义了 —— 而长度、维度、归一化**全对**。所以某一条**单独**就超过容量时
 * 返回值取 **0**（合批对它无解，不是"这一批不能用合批"）—— 而那一条的
 * 出口只能是**整批返回 null 回落逐条**（单条路径一条一次 decode，
 * 每次最多 `n_ctx_seq - 1` 个 token，与 `n_batch` 无关 —— 它本来就能跑，
 * 这也正是"合批之前从来不崩"的原因）。
 *
 * 返回：从这个起点开始能一次提交的条数（整批装得下时 == `counts.size()`，
 * 也就是"这一批一次就能编完"）；某一条自己就超容量 / 空批 -> 0。
 * ⚠ 切段时调用方复用它算**每一段**的长度（`batch_chunk_rows(余下的 counts, nBatch)`）
 * —— 段长写死首段那个数会让后面几段悄悄越界，而越界的报应是 `abort`。
 */
inline int batch_chunk_rows(const std::vector<int> & counts, int nBatch) {
    if (counts.empty()) return 0;
    if (nBatch <= 0) return (int) counts.size();    // 取不到 -> 不切（与上面同一条口径）
    long long acc = 0;
    for (int j = 0; j < (int) counts.size(); j++) {
        const long long n = (long long) counts[(size_t) j];
        // 某一条**自己**就超过整批容量 -> 合批这条路对它无解（见上方说明）
        if (n > (long long) nBatch) return 0;
        if (acc + n > (long long) nBatch) return j;  // 到这儿为止是能一次提交的条数
        acc += n;
    }
    return (int) counts.size();
}

/**
 * 这一档（门 = `nBatch`，每条预算 = `perSeq`）**最多能一次提交几条满长**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么要有这个数：因为"8 条满长装得下"此前**没有任何一处代码在管**
 * ═══════════════════════════════════════════════════════════════════════════
 * 它是 0.9.213 收口的那条不变式的**读数**：`条数 × 每条预算(截断后) ≤ 门`。
 * 此前这个关系只活在两个常量的巧合里（`kEmbedMaxCtx` 恰等于 `kEmbedGate`，
 * 于是 `ctx ≤ gate`，于是 `rows × perSeqCtx ≤ gate`）—— 改任一个常量都会破，
 * 而破的形态不是报错、是 `abort()`（真机上就是"建索引闪退"）。
 *
 * 有了它，`embed model ready` 那一行能把"**这一档最多 N 条满长**"直接写出来，
 * 用户与日志读的人都**不用自己拿两个数去推** —— 本仓库正是靠"推"在 PR #285
 * 的讨论里连续推错过两轮（一次拿没量过的 511 当实际值，一次把两道门当成一道）。
 *
 * ⚠ "满长"取 `perSeq - 1`：真正提交的是**截断后**的 token 数，而截断判据是
 * `>= perSeq` 就 `resize(perSeq - 1)`（`nativeEmbed` / `nativeEmbedBatch` 同一条），
 * 所以一条的真实上限是 `perSeq - 1` —— 用它算才与提交时的数字对得上。
 * 用 `perSeq` 算会**少报一条**而在真机撞 abort 的那批上恰好判"装得下"。
 *
 * 返回：能一次装下的满长条数；`perSeq <= 1` 或 `nBatch <= 0`（取不到）-> 0
 * （**0 表示"这个数算不出来"**，调用方据此不预警 —— 不预警不等于安全，
 * 只是没有判据可依，与 `batch_fits_n_batch` 的 `nBatch <= 0` 放行同一条口径）。
 */
inline int batch_full_rows_fit(int nBatch, int perSeq) {
    if (nBatch <= 0 || perSeq <= 1) return 0;
    return nBatch / (perSeq - 1);
}

/**
 * 把一段**已耗费时间**（毫秒）说成建索引那条状态行里能用的**人话**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么这一段要放在纯逻辑里，而不是在 JNI 里拼字符串
 * ═══════════════════════════════════════════════════════════════════════════
 * 建索引的耗时由**块数**主导：一份 3 MB 的整篇能切出上万块、跑几十分钟。
 * 那几十分钟里用户手上唯一的参照物就是这条状态行，而"已耗费 2731 秒"
 * 这种读数**读不出来** —— 他得自己在心里除一遍 60，而这正是本项目反复
 * 吃过的那类账（`RagCost` 那一族：把秒报成"三十几分钟"而真机是两小时）。
 *
 * 所以换算放在**能被宿主侧单测钉住**的纯函数里（边界全是手算的：
 * 59/60/61 秒、59/60 分、恰好 1 小时、负数、溢出），JNI 只负责把数喂进来。
 *
 * ⚠ 报的是**已耗时**（从开工到此刻），不是"剩余时间"：剩余要靠
 * `已耗时 / 已完成 × 剩余条数` 推，而块与块之间的耗时能差一个数量级
 * （一句短笔记 vs 一块满长的整篇）—— 推出来的剩余时间会**看起来精确**，
 * 而它错得没边。本仓库对"看起来量准了的假读数"判据一向是硬的
 * （见 `[embed内存]` 那个 `+N MB` 被换掉那件事）。
 *
 * 返回：`LL:MM:SS`（≥1 小时）/ `M:SS`（<1 小时）；`ms <= 0` 返回 `0:00`。
 * 定长零填充（`01:05` 而不是 `1:5`）—— 状态行每 250 ms 换一次文本，
 * 位数一变就是一次肉眼可见的抖动，而这条行要挂几十分钟。
 */
inline void format_elapsed(char * out, size_t cap, long long ms) {
    if (out == nullptr || cap == 0) return;
    if (ms < 0) ms = 0;
    const long long total = ms / 1000;
    const long long h = total / 3600;
    const long long m = (total % 3600) / 60;
    const long long s = total % 60;
    if (h > 0) {
        snprintf(out, cap, "%lld:%02lld:%02lld", h, m, s);
    } else {
        snprintf(out, cap, "%lld:%02lld", m, s);
    }
}

/**
 * 由**各条的 token 数**算出合批计划。空批 / 任一条 0 token / 超出 seq 上限
 * 一律 `valid = false`（调用方据此**回落到逐条**，不猜、不丢条目）。
 *
 * `seqCap` 是库能同时容纳的序列数上限（本仓库按 `n_seq_max` 给；<=0 视为不限，
 * 但仍要求 ≤ 条目数）。超过它必须拒绝而不是"拆成两批"—— 拆批是调用方的决定
 * （它知道该按几批跑），这一层只回答"这一批能不能这么填"。
 */
inline BatchPlan plan_batch(int poolTypeRaw, const std::vector<int> & tokenCounts, int seqCap) {
    BatchPlan p;
    if (tokenCounts.empty()) return p;
    if (!batchable_pooling(poolTypeRaw)) return p;   // 见上面那段：只拒 `plan_pooling` 不认的档
    if (seqCap > 0 && (int) tokenCounts.size() > seqCap) return p;
    int total = 0;
    for (int n : tokenCounts) {
        if (n <= 0) return p;      // 空条目不参与合批（调用方在切块期就该滤掉）
        total += n;
    }
    p.n_tokens = total;
    p.n_rows = (int) tokenCounts.size();
    p.rows.reserve(tokenCounts.size());
    p.want_logits.assign((size_t) total, 0);
    p.seq_of.assign((size_t) total, 0);
    p.pos_of.assign((size_t) total, 0);
    int at = 0;
    for (int j = 0; j < (int) tokenCounts.size(); j++) {
        const int n = tokenCounts[(size_t) j];
        BatchRow r;
        r.seq = j;
        r.begin = at;
        r.n = n;
        // 每条各自算池化计划：**不是**整批一个（见文件头那段）。
        //
        // ⚠ 这里**不再**要求 `pp.row == LAST_TOKEN`。上一版要求过，理由是
        // "库自己池化的档（MEAN/CLS/LAST）不合批"—— 那条闸 0.9.208 已撤
        //（见 `batchable_pooling` 上方那段：`embd_seq` 的多 seq 布局验过了，
        // 第 j 条读 `get_embeddings_seq(ctx, j)`）。留着这个 `continue` 的话，
        // 每一档都会在这里被打回逐条，而 `batchable_pooling` 恒为真这件事
        // 就变成了**假绿**（判据说放行、循环里悄悄返回空计划）。
        // 现在是"每条照它自己的 token 数算计划"，MEAN/CLS/LAST 的
        // `want_logits` 整段置 1（那是**给库的门禁**，与读哪一路无关）。
        const PoolPlan pp = plan_pooling(poolTypeRaw, n);
        if (!pp.valid || (int) pp.want_logits.size() != n) return p;
        for (int k = 0; k < n; k++) {
            const size_t g = (size_t) (at + k);
            p.seq_of[g] = j;
            // ⚠ `pos` 是**seq 内**位置（从 0 起），不是整批的全局下标。
            // 写成全局下标时，第 j 条的第二个 token 位置是"前面所有条目的 token
            // 数之和 + 1"—— 词表里位置一错，RoPE 一转，向量就变成"另一条文本
            // 在另一段的语义"，而**长度、维度、归一化全对**，只有检索结果不准。
            p.pos_of[g] = k;
            p.want_logits[g] = pp.want_logits[(size_t) k];
        }
        at += n;
        p.rows.push_back(r);
    }
    p.valid = true;
    return p;
}

/**
 * 合批计划**自洽**吗 —— 判据只锚"我们填给库的东西"，不看库。
 *
 * 四条（每条对应一类"不报错的错位"）：
 *   · 段与 seq 一一对应、不重叠、不留洞：`rows[j].begin` 必须等于前一段的末位；
 *   · 每条至少 1 个 token、`begin` 落在批内；
 *   · `pos` 每 seq 从 0 起、严格 +1（见 `plan_batch` 里那段：位置错位不报错）；
 *   · **库的 logits 前置契约**逐条成立（`logits_ok_for_library`）——
 *     拿整批的 `want_logits` 去问会得到错的答案（它按 nTokens 判长度），
 *     所以这里**按条目**问。
 */
inline bool batch_is_valid(const BatchPlan & p, int poolTypeRaw) {
    if (!p.valid || p.n_rows <= 0 || p.n_tokens <= 0) return false;
    if ((int) p.rows.size() != p.n_rows) return false;
    if ((int) p.want_logits.size() != p.n_tokens) return false;
    if ((int) p.seq_of.size() != p.n_tokens) return false;
    if ((int) p.pos_of.size() != p.n_tokens) return false;
    int expect = 0;
    for (int j = 0; j < p.n_rows; j++) {
        const BatchRow & r = p.rows[(size_t) j];
        if (r.seq != j) return false;
        if (r.begin != expect) return false;      // 段必须首尾相接
        if (r.n <= 0) return false;
        if (r.begin + r.n > p.n_tokens) return false;
        for (int k = 0; k < r.n; k++) {
            const size_t g = (size_t) (r.begin + k);
            if (p.seq_of[g] != j) return false;
            if ((int) p.pos_of[g] != k) return false;
        }
        std::vector<char> want(p.want_logits.begin() + r.begin,
                               p.want_logits.begin() + r.begin + r.n);
        if (!logits_ok_for_library(poolTypeRaw, want, r.n)) return false;
        expect += r.n;
    }
    return expect == p.n_tokens;
}

/**
 * 合批时某个 token 的向量该去 output 数组读第几项。
 *
 * 单条路径的 `plan.batch_last_slot` 是"我们只要了 1 个 output，它在 output
 * 数组里排 0"。合批时**每一行只要自己那 1 个 output**（NONE/UNSPECIFIED 档），
 * 所以整批的 output 数组长度 == 条目数，第 j 条的 output 排位就是 j ——
 * 也就是说 `ith(j)` 取到的正是第 j 条的最后一行。
 *
 * ⚠ 这条等式只在"每行恰好要 1 个 output"时成立。写成"按 token 下标去
 * `output_slot_to_batch` 换算"会在 `want_logits` 变多时静默错位 ——
 * 所以这里**不换算**，直接由 `plan_batch` 的构造保证（见它的循环里
 * 只对 last token 置 1），并在 [batch_is_valid] 里要求整批恰有 n_rows 个 1。
 */
inline bool batch_output_layout_ok(const BatchPlan & p) {
    if (!p.valid) return false;
    int ones = 0;
    for (char c : p.want_logits) if (c) ones++;
    return ones == p.n_rows;
}

/**
 * 两个已归一化向量的余弦相似度（= 点积）。维度不等返回 0（呼叫方应据此跳过）。
 *
 * 维度不等**不抛异常**：索引里的向量维度可能与当前模型不一样（用户换了
 * embed 模型但索引没重建）。这时正确行为是"这个库的索引作废/跳过"，
 * 而不是崩。返回 0 让排序自然把它排到最后，同时调用方另有指纹判据拦在前面。
 */
inline float cosine(const float * a, const float * b, size_t n) {
    if (a == nullptr || b == nullptr || n == 0) return 0.0f;
    double dot = 0.0;
    for (size_t i = 0; i < n; i++) dot += (double) a[i] * (double) b[i];
    return (float) dot;
}

/**
 * 一次检索的结果：命中的行下标 + 相似度。
 * `score` 越大越相近；同分时按下标小的优先（稳定排序 = 可复现，
 * 便于真机验收时把"同一次查询"跑出同样的结果）。
 */
struct Hit {
    int index = -1;
    float score = 0.0f;
};

/**
 * 暴力 top-k：对 `matrix`（`nRows` 行、每行 `nEmbd` 维、行优先）逐行算余弦，
 * 取分数最高的 `k` 行。维度不足 / 参数非法时返回空（调用方降级为"检索为空"，**不崩**）。
 *
 * 为什么不上向量索引库（FAISS / sqlite-vec）：它们都要原生库，
 * 会破本仓库「Kotlin 侧零第三方运行时依赖」的底线；而 100 条 × 384 维
 * 的全量点积在手机上就是微秒级 —— 这个规模根本不需要索引结构。
 *
 * 稳定性：`std::stable_sort` + 「同分取下标小」的显式比较，
 * 让同一次查询在同一份数据上**永远**返回同一个顺序（否则真机验收会
 * 出现"这次检索到 A、下次检索到 B"的不可复现结果）。
 */
inline std::vector<Hit> top_k(const float * matrix, size_t nRows, int nEmbd,
                              const float * query, size_t k) {
    std::vector<Hit> hits;
    if (matrix == nullptr || query == nullptr || nEmbd <= 0 || nRows == 0 || k == 0) return hits;
    for (size_t r = 0; r < nRows; r++) {
        const float * row = matrix + r * (size_t) nEmbd;
        Hit h;
        h.index = (int) r;
        h.score = cosine(row, query, (size_t) nEmbd);
        hits.push_back(h);
    }
    std::stable_sort(hits.begin(), hits.end(), [](const Hit & a, const Hit & b) {
        if (a.score != b.score) return a.score > b.score;
        return a.index < b.index;
    });
    if (hits.size() > k) hits.resize(k);
    return hits;
}

} // namespace embedtext

#endif // LOCAL_LLM_EMBED_TEXT_H
