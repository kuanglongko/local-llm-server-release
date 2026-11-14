#include "spec_abi.h"
#include "spec_common.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <vector>

using llama_tokens = std::vector<llama_token>;

struct SpecHandle {
    common_speculative * spec = nullptr;
    llama_model * draft_model = nullptr; // 外部 draft 模型（MTP / NGram 时为 nullptr）
    // 多 slot 时 draft 权重只加载一份：共享句柄不拥有模型，卸载顺序由建池方
    // 保证（先拆 extras 再拆 slot 0）。漏了这个标志就会对同一份权重 free 多次。
    bool owns_draft_model = true;
    llama_context * draft_ctx = nullptr; // 本层持有（NGram 不建）
    common_params_speculative params;
    int32_t n_max = 0;
    int type = 0;
    // NGram 自投机用的 token 历史（**不含**当前待验证的 id_last）：
    //   · spec_begin 用整段 prompt 重置；
    //   · 每步接受后在 spec_accept 里追加 id_last + 被接受的草稿；
    //   · spec_draft 把它作为 dp.prompt，上游 ngram-simple 在历史里找相同
    //     n-gram 后面的 m-gram 当草稿。没有模型前向，也就没有 q/拒绝采样。
    llama_tokens history;
    // 上一次 spec_draft 的输入/输出，供 spec_accept 推进 history。
    llama_tokens last_draft;
    llama_token last_id_last = LLAMA_TOKEN_NULL;
    bool has_last_draft = false;
    // 概率化草稿的 q：每枚草稿一份候选分布（id + p）。只在 spec_draft 调用期间
    // 有效，下一次 spec_draft 会清空重填。
    std::vector<std::vector<llama_token_data>> draft_q;
    SpecStats stats{};
};

static void set_err(char * err, int cap, const char * fmt, ...) {
    if (!err || cap <= 0) return;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(err, (size_t) cap, fmt, ap);
    va_end(ap);
}

// 「draft 不上 NPU」：把 HTP 从 draft 模型的设备表里去掉，其余设备原样保留。
//
// llama_model_params 的默认值（devices=nullptr）表示"整池都可用"；一旦显式给了
// 设备表，加载器就只在这几台上分层。这里不能只把 tensor_split 的 HTP 份额设 0：
// 设备仍在池里，HTP 仍可能分到层。返回的数组由本函数持有，llama 在
// llama_prepare_model_devices() 里会把指针拷进 model->devices，不保存这个指针。
static ggml_backend_dev_t * draft_device_list(bool exclude_htp) {
    if (!exclude_htp) return nullptr; // 关 = 与改动前逐字节相同
    static std::vector<ggml_backend_dev_t> devs;
    devs.clear();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        const char * nm = ggml_backend_dev_name(d);
        if (nm && strncmp(nm, "HTP", 3) == 0) continue;
        devs.push_back(d);
    }
    if (devs.empty()) return nullptr; // 池里只有 HTP（本机不存在）：退回默认，不伪造空表
    devs.push_back(nullptr);
    return devs.data();
}

static llama_context_params make_ctx_params(int n_ctx, int n_batch, int n_ubatch,
                                            int n_threads, int cache_k, int cache_v,
                                            int flash_attn, llama_context_type type,
                                            llama_context * ctx_tgt) {
    llama_context_params cp = llama_context_default_params();
    if (n_ctx > 0) cp.n_ctx = (uint32_t) n_ctx;
    if (n_batch > 0) cp.n_batch = (uint32_t) n_batch;
    if (n_ubatch > 0) cp.n_ubatch = (uint32_t) n_ubatch;
    if (n_threads > 0) {
        cp.n_threads = n_threads;
        cp.n_threads_batch = n_threads;
    }
    // 与目标 ctx 同一条 seq：本仓库并发靠 slot 数买，seq 不分路。
    cp.n_seq_max = 1;
    cp.n_rs_seq = 0;
    cp.ctx_type = type;
    cp.ctx_other = ctx_tgt;
    if (cache_k > 1) cp.type_k = (ggml_type) cache_k;
    if (cache_v > 1) cp.type_v = (ggml_type) cache_v;
    cp.flash_attn_type = flash_attn ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                                    : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    // V 量化必须开 FA（与目标 ctx 同一条库约束）。
    if (ggml_is_quantized(cp.type_v)) cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    return cp;
}

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
        bool            probabilistic,
        bool            draft_exclude_htp,
        llama_model   * draft_model_shared,
        char          * err,
        int             err_cap) {
    if (!model_tgt || !ctx_tgt) {
        set_err(err, err_cap, "目标 model/ctx 为空");
        return nullptr;
    }
    // 设置页的 mode 与上游枚举只在 1/3 两档重合；2（NGram）要映射到
    // COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE(6)。映射收在这里，调用方只认
    // 「0 关 / 1 draft / 2 ngram / 3 MTP」四个数。
    if (type == 2) type = (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE;
    const bool is_ngram = type == (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE;
    if (type != (int) COMMON_SPECULATIVE_TYPE_DRAFT_MTP &&
        type != (int) COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE &&
        !is_ngram) {
        set_err(err, err_cap, "不支持的投机类型 %d（支持 MTP / draft-simple / NGram）", type);
        return nullptr;
    }
    if (n_max <= 0) n_max = 1;
    // Phase 1 的草稿缓冲是固定 64 格（见 llama_jni.cpp 的 spec_step_impl）。
    if (n_max > 64) n_max = 64;

    const bool tgt_rec = llama_model_is_recurrent(model_tgt) || llama_model_is_hybrid(model_tgt);
    if ((type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE || is_ngram) && tgt_rec) {
        // 验证后会用 seq_rm 从目标 KV 删掉未接受的位置；循环/混合记忆做不到部分删除
        // （只能靠 rs 序列），继续跑会在第一次拒绝时 abort。draft-simple 与 NGram
        // 都是"验证批 + 回滚未接受尾部"这条路径，同一个限制。
        set_err(err, err_cap, "目标模型是循环/混合记忆，draft-simple / NGram 不支持");
        return nullptr;
    }

    auto * h = new SpecHandle();
    h->type = type;
    h->n_max = n_max;
    h->stats.n_max = n_max;
    h->params.types = { (common_speculative_type) type };
    h->params.draft.n_max = n_max;
    h->params.draft.n_min = 0;
    h->params.draft.p_min = 0.0f;
    h->params.draft.backend_sampling = true;
    // 只有 draft-simple 支持"概率化草稿 + 返回 q"。MTP 的草稿链是固定的，
    // 打开它只会改行为、不会给调用方任何可用于拒绝采样的分布。
    h->params.draft.probabilistic =
        (type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE) && probabilistic;
    h->params.draft.ctx_tgt = ctx_tgt;

    const int ctx_n = n_ctx > 0 ? n_ctx : (int) llama_n_ctx(ctx_tgt);
    const int n_batch_eff = n_batch > 0 ? n_batch : (int) llama_n_batch(ctx_tgt);
    const int n_ubatch_eff = n_ubatch > 0 ? n_ubatch : (int) llama_n_ubatch(ctx_tgt);

    if (type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_MTP) {
        // 模型必须真的带 nextn（MTP）层；否则 llama_init_from_model 的 MTP 路径
        // 没有可用的头，没必要走到 abort 边缘。
        const int32_t n_nextn = llama_model_n_layer_nextn(model_tgt);
        if (n_nextn <= 0) {
            set_err(err, err_cap, "目标模型没有 nextn/MTP 层（n_layer_nextn=%d）", (int) n_nextn);
            delete h;
            return nullptr;
        }
        // 循环/混合记忆的回滚只能靠 rs 序列；目标 ctx 必须按 n_max 分配过。
        // 目标 ctx 是调用方建的，这里只核对，不替它重建。
        if (tgt_rec && (int) llama_n_rs_seq(ctx_tgt) < n_max) {
            set_err(err, err_cap,
                    "MTP + 循环/混合记忆需要目标 ctx 的 n_rs_seq >= %d（实际 %u）："
                    "必须在建目标 ctx 时就带上，建完补不了",
                    n_max, (unsigned) llama_n_rs_seq(ctx_tgt));
            delete h;
            return nullptr;
        }
        llama_context_params cp = make_ctx_params(ctx_n, n_batch_eff, n_ubatch_eff,
                                                  n_threads, cache_k, cache_v, flash_attn,
                                                  LLAMA_CONTEXT_TYPE_MTP, ctx_tgt);
        h->draft_ctx = llama_init_from_model(model_tgt, cp);
        if (!h->draft_ctx) {
            set_err(err, err_cap, "MTP draft ctx 创建失败");
            delete h;
            return nullptr;
        }
    } else if (is_ngram) {
        // NGram 自投机不加载任何模型、不建 draft ctx：草稿完全来自 token 历史，
        // 目标模型那一批 decode 就是唯一的计算。这里什么都不做，等下面
        // common_speculative_init 建出 ngram-simple 实现即可。
    } else {
        if ((!draft_path || !draft_path[0]) && !draft_model_shared) {
            set_err(err, err_cap, "外部 draft 模型路径为空");
            delete h;
            return nullptr;
        }
        if (draft_model_shared) {
            // 多 slot：slot 0 已经加载并校验过词表，这里只共享权重。
            // 所有权不转移 —— 见 SpecHandle::owns_draft_model 的注释。
            h->draft_model = draft_model_shared;
            h->owns_draft_model = false;
        } else {
            llama_model_params mp = llama_model_default_params();
            mp.n_gpu_layers = n_gpu_layers;
            mp.load_mode = LLAMA_LOAD_MODE_MMAP;
            mp.lazy_mode = LLAMA_LAZY_MODE_OFF;
            // 关 = nullptr（整池，含 HTP），与改动前相同；开 = 仅非 HTP 设备。
            mp.devices = draft_device_list(draft_exclude_htp);
            h->draft_model = llama_model_load_from_file(draft_path, mp);
            if (!h->draft_model) {
                set_err(err, err_cap, "draft 模型加载失败：%s", draft_path);
                delete h;
                return nullptr;
            }
            if (!common_speculative_are_compatible(model_tgt, h->draft_model)) {
                set_err(err, err_cap, "draft 模型词表与目标模型不兼容");
                llama_model_free(h->draft_model);
                delete h;
                return nullptr;
            }
        }
        llama_context_params cp = make_ctx_params(ctx_n, n_batch_eff, n_ubatch_eff,
                                                  n_threads, cache_k, cache_v, flash_attn,
                                                  LLAMA_CONTEXT_TYPE_DEFAULT, ctx_tgt);
        h->draft_ctx = llama_init_from_model(h->draft_model, cp);
        if (!h->draft_ctx) {
            set_err(err, err_cap, "draft ctx 创建失败");
            if (h->owns_draft_model) llama_model_free(h->draft_model);
            delete h;
            return nullptr;
        }
    }

    h->params.draft.ctx_dft = h->draft_ctx;
    h->spec = common_speculative_init(h->params, 1);
    if (!h->spec) {
        set_err(err, err_cap, "common_speculative_init 返回空（实现不可用）");
        if (h->draft_ctx) llama_free(h->draft_ctx);
        if (h->draft_model && h->owns_draft_model) llama_model_free(h->draft_model);
        delete h;
        return nullptr;
    }
    // 上游对 NGram 报的 n_max 是 m-gram 宽度（默认 48），与用户请求的上限
    // 不是一回事。这里一律再按请求值收一刀：用户要 3 就只草拟 3 枚，不给
    // "请求没写 n_max" 这种场景悄悄放大到 48。MTP 可能被库进一步压小
    // （nextn 层数），min 也保留那个真值。
    h->n_max = std::max(1, std::min((int) common_speculative_n_max(h->spec), n_max));
    h->stats.n_max = h->n_max;
    return h;
}

void spec_free(SpecHandle * h) {
    if (!h) return;
    if (h->spec) common_speculative_free(h->spec);
    if (h->draft_ctx) llama_free(h->draft_ctx);
    if (h->draft_model && h->owns_draft_model) llama_model_free(h->draft_model);
    delete h;
}

llama_model * spec_draft_model(const SpecHandle * h) {
    return h ? h->draft_model : nullptr;
}

int spec_n_max(const SpecHandle * h) { return h ? h->n_max : 0; }

bool spec_reject_enabled(const SpecHandle * h) {
    return h && h->type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE &&
           h->params.draft.probabilistic;
}

bool spec_needs_target_hidden(const SpecHandle * h) {
    return h && h->type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_MTP;
}

bool spec_process(SpecHandle * h, llama_context * ctx_tgt,
                  const int32_t * tokens, int n, int pos0, int seq_id) {
    (void) ctx_tgt;
    if (!h || !h->spec) return false;
    // NGram 的 process 是空操作（历史由 spec_begin / spec_accept 维护），
    // 它没有 draft ctx，不能走下面"必须有 draft_ctx"的路径。
    if (h->type == (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE) return true;
    if (!h->draft_ctx || !tokens || n <= 0) return false;
    common_batch batch(h->draft_ctx);
    for (int i = 0; i < n; ++i) {
        batch.add((llama_token) tokens[i], (llama_pos) (pos0 + i), (llama_seq_id) seq_id, true);
    }
    return common_speculative_process(h->spec, batch);
}

int spec_draft(SpecHandle * h, int seq_id, int pos0, int32_t id_last,
               float temp, uint32_t seed, bool want_q,
               int32_t * out, int cap) {
    if (!h || !h->spec || !out || cap <= 0) return -1;
    llama_tokens result;
    auto & dp = common_speculative_get_draft_params(h->spec, (llama_seq_id) seq_id);
    dp.drafting = true;
    dp.n_max = cap;
    dp.pos0 = (llama_pos) pos0;
    dp.id_last = (llama_token) id_last;
    // NGram 只在 token 历史里查找，`prompt` 必须指向历史（不含 id_last）。
    // 其余实现（draft-simple / MTP）用不到它，维持 nullptr。
    dp.prompt = h->type == (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE ? &h->history : nullptr;
    dp.result = &result;
    // q 只在"draft-simple + 概率化草稿 + 调用方要"时给出。注意 result_q 同时是
    // 上游 draft-simple 的"是否进入采样式草稿 / 是否按 temp 重调采样器"的开关，
    // 所以不能在要拒绝采样时还传 nullptr。
    h->draft_q.clear();
    const bool with_q = want_q && h->type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE &&
                        h->params.draft.probabilistic;
    dp.result_q = with_q ? &h->draft_q : nullptr;
    // 温度与种子对齐目标采样链（只有 draft-simple 用；MTP 维持原来的固定值）。
    if (h->type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_SIMPLE) {
        dp.temp = temp > 0.0f ? temp : 1.0f;
        dp.seed = seed;
    } else {
        dp.temp = 1.0f;
        dp.seed = LLAMA_DEFAULT_SEED;
    }

    common_speculative_draft(h->spec);

    // 草稿实现会把草稿链写进 draft ctx（MTP 还会按 head 写多层 KV）。
    //
    // MTP：目标模型随后会解码同一批 token，而 `common_speculative_process`
    // 要求那一段位置是空的（它会重新写入正确的 KV）—— 与上游
    // `memory.seq_rm(spec_ctx, pos0, -1)` 同一条纪律。所以这里 trim 回 pos0，
    // 由调用方的 `spec_process` 重放验证批。
    //
    // draft-simple：**保留**这段 KV，从而整步跳过 `spec_process` 的验证批重放
    // （验证批通常 2~4 token，正好落在 Adreno CoopK 窄批 kernel 的启用区间；
    // 重放本身是纯重复，省掉它不改变正确性）。调用方下一步开头会按「已接受前缀」
    // `spec_trim` 把拒绝的尾巴剪掉。
    //
    // ⚠ 但上游 `draft()` 有一个必须在这里补上的缺口：它把草稿链逐枚入批推进，
    //   而**达到 n_max 的最后一枚只 push 进 result、不再入批**（见上游循环里
    //   `if (n_max <= result.size()) { drafting = false; continue; }` 在
    //   `batch.add()` 之前）。所以 draft ctx 里恒缺最后一枚草稿。
    //   旧路径靠每步 `spec_process([tok, d1..dk])` 重放整批把这一枚补上；现在
    //   跳过重放就必须在这里补，否则目标一旦「全部草稿都接受」，下一位写入
    //   就会跳过一个空位（目标 Y 比 draft ctx 的 X 大 2），此后 draft ctx
    //   永久落后一格、每步 `llama_process` 全部 -1、草稿链静默归零。
    //   真机现场：X 分别冻在 29 / 337 / 461，草稿从 ~1.7 枚/步掉到 0.1 枚/步。
    //
    //   补齐用**单 token** 批：单 token 走 matvec 快路径，不碰被关闭的窄批
    //   kernel。先问 draft ctx 的真实末尾（`llama_memory_seq_pos_max`），只喂
    //   真正缺的那几枚 —— 正常情况下就是最后一枚，别的分支（p_min 提前停等）
    //   已经写过时不会重复写。
    if (h->type == (int) COMMON_SPECULATIVE_TYPE_DRAFT_MTP) {
        spec_trim(h, seq_id, pos0);
    } else if (h->type == (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE) {
        // NGram 没有 draft ctx/KV，草稿来自 history；这里不 trim。
        // 被拒绝的尾部不会进 history（只有 spec_accept 追加已接受的那一段）。
    } else {
        auto * mem = llama_get_memory(h->draft_ctx);
        const int want_end = pos0 + 1 + (int) result.size();  // 独占上界
        int have = 0;
        if (mem) {
            const llama_pos pmax = llama_memory_seq_pos_max(mem, (llama_seq_id) seq_id);
            have = pmax >= 0 ? (int) pmax + 1 : 0;
        }
        if (have < pos0) {
            // 连 id_last 都没落进 draft ctx（上游 process 失败）：不装作成功，
            // 交给调用方按 -1 降级，避免在错位的 KV 上继续草拟。
            return -1;
        }
        if (have < want_end) {
            std::vector<llama_token> tail;
            tail.reserve((size_t) (want_end - have));
            for (int p = have; p < want_end; ++p) {
                tail.push_back(p == pos0 ? (llama_token) id_last
                                         : result[(size_t) (p - pos0 - 1)]);
            }
            if (!spec_process(h, nullptr, tail.data(), (int) tail.size(), have, seq_id)) {
                return -1;
            }
        }
    }

    h->stats.draft_calls++;
    int n = (int) result.size();
    if (n > cap) n = cap;
    for (int i = 0; i < n; ++i) out[i] = (int32_t) result[i];
    // 记下本次输入/输出，供 spec_accept 推进 NGram 的 history（其余实现不用）。
    h->last_id_last = (llama_token) id_last;
    h->last_draft.assign(result.begin(), result.begin() + n);
    h->has_last_draft = true;
    h->stats.draft_tokens += (int64_t) result.size();
    return n;
}

int spec_draft_q_count(const SpecHandle * h, int i) {
    if (!h || i < 0 || (size_t) i >= h->draft_q.size()) return 0;
    return (int) h->draft_q[(size_t) i].size();
}

bool spec_draft_q_at(const SpecHandle * h, int i, int k, int32_t * id, float * p) {
    if (!h || i < 0 || k < 0 || (size_t) i >= h->draft_q.size()) return false;
    const auto & row = h->draft_q[(size_t) i];
    if ((size_t) k >= row.size()) return false;
    if (id) *id = (int32_t) row[(size_t) k].id;
    if (p)  *p  = row[(size_t) k].p;
    return true;
}

void spec_trim(SpecHandle * h, int seq_id, int pos0) {
    if (!h) return;
    if (pos0 < 0) pos0 = 0;
    // NGram 没有 draft ctx，要"剪"的是 token 历史：只许缩短到已提交前缀，
    // 绝不许把历史补长（补长等于编造没发生过的 token）。
    if (h->type == (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE) {
        if (h->history.size() > (size_t) pos0) h->history.resize((size_t) pos0);
        h->has_last_draft = false;
        return;
    }
    if (!h->draft_ctx) return;
    auto * mem = llama_get_memory(h->draft_ctx);
    if (mem) llama_memory_seq_rm(mem, (llama_seq_id) seq_id, (llama_pos) pos0, -1);
}

void spec_accept(SpecHandle * h, int seq_id, int n_accepted) {
    if (!h || !h->spec) return;
    if (n_accepted < 0) n_accepted = 0;
    if (n_accepted > 65535) n_accepted = 65535;
    // NGram：提交 id_last 与本次被接受的草稿，下一步的查找历史里必须有它们。
    // 顺序与目标 KV 完全一致：[前缀][id_last][d0][d1]…
    if (h->type == (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE && h->has_last_draft) {
        if (h->last_id_last != LLAMA_TOKEN_NULL) h->history.push_back(h->last_id_last);
        const size_t take = std::min((size_t) n_accepted, h->last_draft.size());
        h->history.insert(h->history.end(), h->last_draft.begin(),
                          h->last_draft.begin() + take);
        h->has_last_draft = false;
    }
    common_speculative_accept(h->spec, (llama_seq_id) seq_id, (uint16_t) n_accepted);
    h->stats.accept_tokens += n_accepted;
    if (n_accepted > 0) h->stats.accepted_rounds++;
}

void spec_begin(SpecHandle * h, int seq_id, const int32_t * tokens, int n) {
    if (!h || !h->spec) return;
    llama_tokens prompt;
    if (tokens && n > 0) prompt.assign(tokens, tokens + n);
    // NGram 的历史从整段已进目标 KV 的 prompt 起算；id_last 会在该轮第一次
    // spec_draft / spec_accept 里接上（见 spec_draft 的 prompt 说明）。
    if (h->type == (int) COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE) {
        h->history = prompt;
        h->has_last_draft = false;
    }
    common_speculative_begin(h->spec, (llama_seq_id) seq_id, prompt);
}

void spec_get_stats(const SpecHandle * h, SpecStats * out) {
    if (!out) return;
    if (!h) {
        std::memset(out, 0, sizeof(*out));
        return;
    }
    *out = h->stats;
}
