#pragma once
// ──────────────────────────────────────────────────────────────────────────
//  MTP / speculative decoding 用到的上游 ABI 最小子集。
//
//  为什么需要这一份：本项目链接的是 vendor/cui-llama.rn-v1.13.0 预编译的
//  librnllama*.so，它导出了 common_speculative_* / common_batch 的 C++ 符号，
//  但仓库里没有随附对应的 common.h（chat/include/common.h 是裁剪版，只有
//  chat 需要的部分）。要让这些符号可用，必须按**同一个版本**的字段顺序声明
//  结构体 —— 字段名可以不带、字段顺序/类型不能错，否则流传到 .so 里的引用
//  布局不同，表现是静默读错值或崩溃。
//
//  下面每个结构体逐字段照抄 upstream v1.13.0
//  vendor/llama.cpp/common/common.h（commit e1e725d6）。方法体全部省掉：
//  我们只按引用传给 .so 里的实现，不调用成员函数；common_batch 的成员函数
//  由 .so 导出、这里只声明。
//
//  ⚠ 升级 vendor 时本文件必须与 upstream common.h 一起复核；字段顺序/类型
//    任何变化都会让静态断言之外的运行期行为悄悄错位。
// ──────────────────────────────────────────────────────────────────────────

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <type_traits>
#include <vector>

#include "llama.h"
#include "ggml.h"

// ── 与 include/llama-cpp.h 同义（只在本单元用得到 batch_ext 的 unique_ptr）──
struct llama_batch_ext_deleter {
    void operator()(llama_batch_ext * batch) { llama_batch_ext_free(batch); }
};
using llama_batch_ext_ptr = std::unique_ptr<llama_batch_ext, llama_batch_ext_deleter>;

using llama_tokens = std::vector<llama_token>;

// ── upstream common.h 的枚举 ─────────────────────────────────────────────
enum common_speculative_type {
    COMMON_SPECULATIVE_TYPE_NONE,
    COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE,
    COMMON_SPECULATIVE_TYPE_DRAFT_EAGLE3,
    COMMON_SPECULATIVE_TYPE_DRAFT_MTP,
    COMMON_SPECULATIVE_TYPE_DRAFT_DFLASH,
    COMMON_SPECULATIVE_TYPE_DRAFT_DSPARK,
    COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE,
    COMMON_SPECULATIVE_TYPE_NGRAM_MAP_K,
    COMMON_SPECULATIVE_TYPE_NGRAM_MAP_K4V,
    COMMON_SPECULATIVE_TYPE_NGRAM_MOD,
    COMMON_SPECULATIVE_TYPE_NGRAM_CACHE,
    COMMON_SPECULATIVE_TYPE_COUNT,
};

// ── upstream common.h:73 ─────────────────────────────────────────────────
struct common_cpu_params {
    int      n_threads                   = -1;
    bool     cpumask[GGML_MAX_N_THREADS] = {false};
    bool     mask_valid                  = false;
    enum ggml_sched_priority priority    = GGML_SCHED_PRIO_NORMAL;
    bool     strict_cpu                  = false;
    uint32_t poll                        = 50;
};

// ── upstream common.h:288 ────────────────────────────────────────────────
struct common_params_model {
    std::string path        = "";
    std::string url         = "";
    std::string hf_repo     = "";
    std::string hf_file     = "";
    std::string docker_repo = "";
};

// ── upstream common.h:331 ────────────────────────────────────────────────
struct common_params_speculative_draft {
    int32_t n_max = 3;
    int32_t n_min = 0;

    float p_split = 0.1f;
    float p_min   = 0.0f;

    bool backend_sampling = true;

    bool probabilistic = false;

    common_params_model mparams;

    llama_context * ctx_tgt = nullptr;
    llama_context * ctx_dft = nullptr;

    int32_t n_gpu_layers = -1;

    int32_t n_ubatch = 0;

    ggml_type cache_type_k = GGML_TYPE_F16;
    ggml_type cache_type_v = GGML_TYPE_F16;

    common_cpu_params cpuparams;
    common_cpu_params cpuparams_batch;

    std::vector<ggml_backend_dev_t> devices;

    std::vector<llama_model_tensor_buft_override> tensor_buft_overrides;
};

struct common_params_speculative_ngram_mod {
    int32_t n_match = 24;
    int32_t n_max = 64;
    int32_t n_min = 48;
};

struct common_params_speculative_ngram_map {
    uint16_t size_n   = 12;
    uint16_t size_m   = 48;
    uint16_t min_hits = 1;
};

struct common_params_speculative_ngram_cache {
    std::string lookup_cache_static;
    std::string lookup_cache_dynamic;
};

// ── upstream common.h:380 ────────────────────────────────────────────────
struct common_params_speculative {
    std::vector<enum common_speculative_type> types = { COMMON_SPECULATIVE_TYPE_NONE };

    double synth_len = -1.0;
    std::vector<double> synth_rates;

    common_params_speculative_draft draft;

    common_params_speculative_ngram_mod ngram_mod;
    common_params_speculative_ngram_map ngram_simple;
    common_params_speculative_ngram_map ngram_map_k;
    common_params_speculative_ngram_map ngram_map_k4v;

    common_params_speculative_ngram_cache ngram_cache;
};

// ── upstream common.h:1097 ───────────────────────────────────────────────
struct common_batch {
    struct token {
        llama_token  id;
        std::array<llama_pos, GGML_MROPE_SECTIONS> pos;
        llama_seq_id seq_id;
        bool         output;
        llama_embd   embd;
        llama_embd   state;
        std::vector<llama_seq_id> seq_ids_extra;
        int32_t      decision_order = 0;
    };

    std::vector<token> tokens;
    llama_batch_ext_ptr batch;

    int32_t n_pos = 1;

    common_batch() = default;
    common_batch(struct llama_context * ctx);

    llama_batch_ext * get();
    llama_batch_ext * get_sub_batch(int32_t off, int32_t n);

    bool has_token() const { return !tokens.empty() && tokens[0].id != LLAMA_TOKEN_NULL; }
    bool has_embd () const { return !tokens.empty() && tokens[0].embd.data != nullptr; }

    void clear();

    int32_t add(llama_token id, llama_pos pos, llama_seq_id seq_id, bool output);
    int32_t add(llama_token id, llama_pos pos, const std::vector<llama_seq_id> & seq_ids, bool output);
    bool add_seq(int32_t idx, llama_seq_id seq_id);
    bool set_output(int32_t idx, bool value);
    bool set_embd(int32_t idx, llama_embd embd);
    bool set_embd_state(int32_t idx, llama_embd state);
    int32_t add_embd(llama_embd embd, const llama_pos * pos, llama_seq_id seq_id, bool output);

    int32_t size() const { return (int32_t) tokens.size(); }
};

// ── upstream common.h:331 后面（speculative.h 里）────────────────────────
struct common_speculative_draft_params {
    bool drafting = false;

    int32_t n_max = -1;

    llama_pos   pos0;
    llama_token id_last;

    const llama_tokens * prompt = nullptr;

    llama_tokens * result = nullptr;

    std::vector<std::vector<llama_token_data>> * result_q = nullptr;

    float    temp = 1.0f;
    uint32_t seed = LLAMA_DEFAULT_SEED;
};

struct common_speculative;

// ── upstream speculative.h 的函数声明（定义在预编译 .so 里）─────────────
bool common_speculative_are_compatible(const llama_model * model_tgt, const llama_model * model_dft);

common_speculative * common_speculative_init(common_params_speculative & params, uint32_t n_seq);

void common_speculative_free(common_speculative * spec);

common_speculative_draft_params & common_speculative_get_draft_params(common_speculative * spec, llama_seq_id seq_id);

void common_speculative_begin(common_speculative * spec, llama_seq_id seq_id, const llama_tokens & prompt);

bool common_speculative_process(common_speculative * spec, const common_batch & batch);

void common_speculative_draft(common_speculative * spec);

void common_speculative_accept(common_speculative * spec, llama_seq_id seq_id, uint16_t n_accepted);

int32_t common_speculative_n_max(const common_speculative * spec);
int32_t common_speculative_n_max(const common_params_speculative * spec);

void common_speculative_print_stats(const common_speculative * spec);

// ── 预编译库导出、但本仓库 include/llama.h 未声明的扩展 API ──────────────
extern "C" {
int32_t llama_model_n_layer_nextn(const struct llama_model * model);
struct llama_context * llama_get_ctx_other(const struct llama_context * ctx);
bool llama_set_embeddings_nextn(struct llama_context * ctx, bool value, bool masked);
const float * llama_get_embeddings_nextn(struct llama_context * ctx);
const float * llama_get_embeddings_nextn_ith(struct llama_context * ctx, int32_t i);
bool llama_set_nextn_layer_offset(struct llama_context * ctx, int32_t offset);
}

// ── ABI 字段静态断言 ───────────────────────────────────────────────────────
// 这些结构体是**照抄**预编译 .so 的布局，不是本项目自己的数据结构。字段顺序/
// 类型被谁"顺手整理"一下，编译器不会报错（两边各自都能编过），运行期才在
// .so 里读错位置 —— 症状是草稿乱码或崩溃。断言只钉**与 stdlib 无关**的部分：
//   · 前导标量的偏移（POD 区间，libc++/libstdc++ 同值）；
//   · 关键字段的相对顺序（含 std::string/vector 的区间只比先后，不比绝对偏移，
//     因为宿主守卫可能用 libstdc++、真机用 libc++）；
//   · 枚举值（.so 按整数收，值变了等于换了另一种投机实现）。
static_assert(COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE == 1, "speculative 枚举值必须与 upstream 一致");
static_assert(COMMON_SPECULATIVE_TYPE_DRAFT_MTP == 3, "speculative 枚举值必须与 upstream 一致");

static_assert(offsetof(common_cpu_params, n_threads) == 0, "common_cpu_params.n_threads 必须在首位");
static_assert(offsetof(common_cpu_params, cpumask) == sizeof(int), "common_cpu_params.cpumask 偏移漂移");
static_assert(offsetof(common_cpu_params, mask_valid) > offsetof(common_cpu_params, cpumask),
              "common_cpu_params 字段顺序漂移");
static_assert(offsetof(common_cpu_params, poll) > offsetof(common_cpu_params, strict_cpu),
              "common_cpu_params 字段顺序漂移");

static_assert(offsetof(common_params_speculative_draft, n_max) == 0, "draft.n_max 必须在首位");
static_assert(offsetof(common_params_speculative_draft, n_min) == 4, "draft.n_min 偏移漂移");
static_assert(offsetof(common_params_speculative_draft, p_split) == 8, "draft.p_split 偏移漂移");
static_assert(offsetof(common_params_speculative_draft, p_min) == 12, "draft.p_min 偏移漂移");
static_assert(offsetof(common_params_speculative_draft, backend_sampling) == 16,
              "draft.backend_sampling 偏移漂移");
static_assert(offsetof(common_params_speculative_draft, probabilistic) == 17,
              "draft.probabilistic 偏移漂移");
static_assert(offsetof(common_params_speculative_draft, ctx_tgt) <
              offsetof(common_params_speculative_draft, ctx_dft), "draft ctx 顺序漂移");

static_assert(offsetof(common_params_speculative, types) == 0, "speculative.types 必须在首位");
static_assert(offsetof(common_params_speculative, draft) <
              offsetof(common_params_speculative, ngram_mod), "speculative 字段顺序漂移");
static_assert(offsetof(common_params_speculative, ngram_mod) <
              offsetof(common_params_speculative, ngram_cache), "speculative 字段顺序漂移");

static_assert(offsetof(common_batch, tokens) == 0, "common_batch.tokens 必须在首位");
static_assert(offsetof(common_batch, batch) > offsetof(common_batch, tokens),
              "common_batch 字段顺序漂移");
static_assert(offsetof(common_speculative_draft_params, drafting) == 0,
              "draft_params.drafting 必须在首位");
static_assert(offsetof(common_speculative_draft_params, n_max) == 4,
              "draft_params.n_max 偏移漂移");
static_assert(std::is_same<decltype(common_speculative_draft_params::pos0), llama_pos>::value,
              "draft_params.pos0 类型漂移");
static_assert(std::is_same<decltype(common_speculative_draft_params::id_last), llama_token>::value,
              "draft_params.id_last 类型漂移");
