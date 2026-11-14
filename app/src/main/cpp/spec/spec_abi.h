#pragma once
// 薄 C++ 适配层：把上游 common_speculative_* / common_batch 的 C++ 形状
// 收在一个不透明句柄后面，llama_jni.cpp 只依赖本头 + 下面的纯函数。
//
// 为什么要多这一层：llama_jni.cpp 已经很大，直接把 common_params_speculative、
// common_batch 这些上游结构体铺进去，会让「升级 vendor 时要复核的字段」散落在
// 生成热路径里。这里把 ABI 面收成一处（spec_common.h），本文件只负责生命周期
// 与坏路径兜底。

#include <cstdint>

struct llama_model;
struct llama_context;

struct SpecHandle;

// type 取值（与设置页 mode 同一口径）：
//   0 = 关（不建）
//   1 = 外部 draft 模型（draft-simple）
//   2 = NGram 自投机（用 token 历史做查找，**不加载任何 draft 模型、不建 draft ctx**）
//   3 = MTP（内置 nextn 层）
// 其余值一律拒绝。返回 nullptr 时把原因写进 err（可为空）。
SpecHandle * spec_create(
        llama_model   * model_tgt,
        llama_context * ctx_tgt,
        const char    * draft_path,
        int             type,
        int             n_max,
        int             n_ctx,
        int             n_batch,
        int             n_ubatch,
        int             n_threads,
        int             n_gpu_layers,
        int             cache_k,
        int             cache_v,
        int             flash_attn,
        // 概率化草稿（仅 draft-simple）：draft 按目标温度采样并返回候选分布 q，
        // 供调用方做拒绝采样。MTP 忽略此参数（它的草稿采样链不受影响）。
        bool            probabilistic,
        // 外部 draft 是否**排除 HTP**（仅 draft-simple 且由本次调用自己加载时生效）。
        // true = draft 模型只用 OpenCL/CPU；false = 与改动前相同（整池可选，含 HTP）。
        // 目标模型的设备池不受影响 —— 这里改的只是 draft 那份 llama_model_params。
        bool            draft_exclude_htp,
        // 外部 draft 模型的**可选共享句柄**（多 slot 用）：
        //   · nullptr —— 本次调用自己加载并持有 draft 模型（单 slot / slot 0）；
        //   · 非空   —— 直接复用，**不接管所有权**，只为本格新建 draft ctx。
        // 多 slot 时权重只加载一份；每个目标 ctx 仍然各有一份 draft ctx/KV。
        // MTP 忽略此参数（draft ctx 从目标模型建，不涉及外部 draft 模型）。
        llama_model   * draft_model_shared,
        char          * err,
        int             err_cap);

// 本句柄持有的外部 draft 模型（MTP 时为 nullptr）。多 slot 建池时，slot 0
// 建完后把它传给其余各格做共享；所有权仍在创建它的那个句柄手里。
llama_model * spec_draft_model(const SpecHandle * h);

void spec_free(SpecHandle * h);

int  spec_n_max(const SpecHandle * h);

// 这个句柄是否真的具备"概率化草稿 + 拒绝采样"能力：draft-simple 且加载期
// `probabilistic` 落值为真。用于加载日志与启动自检 —— 只读 `g_spec_load`
// 会被"请求期开关"误导，实际生效值必须从 ABI 句柄里读回来。
bool spec_reject_enabled(const SpecHandle * h);

// 这种投机实现的 `spec_process` 是否依赖目标模型的 hidden state。
//
// MTP 要读 `llama_get_embeddings_nextn_ith`，而复用段没有重新 decode 就没有
// 新的 hidden state —— 调用方必须为它放弃 KV 复用（全量 prefill）。
// draft-simple 只用 token 在 draft ctx 上重放，复用段可以由调用方补喂给 draft
// ctx，因此它允许复用。
bool spec_needs_target_hidden(const SpecHandle * h);

// 把一段已经进目标 KV 的 token 喂给投机实现（MTP 用它补齐 draft KV 并缓存
// 目标 hidden state；draft-simple 用它把同样的 token 解码进 draft ctx）。
bool spec_process(SpecHandle * h, llama_context * ctx_tgt,
                  const int32_t * tokens, int n, int pos0, int seq_id);

// 生成草稿。返回写入 out 的条数；-1 = 失败（调用方按"本轮不投机"降级）。
//
// temp/seed：draft 采样器的温度与随机种子。只有 draft-simple 会用它（对齐目标
// 采样分布，拒绝采样需要 p 与 q 同温）；MTP 内部固定按原值走。
// want_q：是否要求返回每枚草稿的候选分布 q（`spec_draft_q_*`）。只对开启了
// 概率化草稿的 draft-simple 生效；其余情况 q 为空，调用方回落到"逐 token 匹配"。
int spec_draft(SpecHandle * h, int seq_id, int pos0, int32_t id_last,
               float temp, uint32_t seed, bool want_q,
               int32_t * out, int cap);

// 概率化草稿：第 i 枚草稿的候选分布 q 的候选数（0 = 这一枚没有 q）。
int spec_draft_q_count(const SpecHandle * h, int i);

// 读第 i 枚草稿的第 k 个候选（id + 概率）。越界 / 无 q 返回 false。
bool spec_draft_q_at(const SpecHandle * h, int i, int k, int32_t * id, float * p);

// 丢弃 draft ctx 在 pos0 之后的 KV：上一轮验证时草稿链可能写入了未被接受的
// token，下一轮草稿只能看到"已提交前缀"。
void spec_trim(SpecHandle * h, int seq_id, int pos0);

void spec_accept(SpecHandle * h, int seq_id, int n_accepted);

void spec_begin(SpecHandle * h, int seq_id, const int32_t * tokens, int n);

struct SpecStats {
    int64_t draft_calls;
    int64_t draft_tokens;
    int64_t accept_tokens;
    int64_t accepted_rounds;
    int32_t n_max;
};

void spec_get_stats(const SpecHandle * h, SpecStats * out);
