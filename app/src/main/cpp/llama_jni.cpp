// llama_jni.cpp — Kotlin <-> llama.cpp JNI 桥（rnllama 构建：CPU + OpenCL + Hexagon HTP）
// 设计：生成循环在 Kotlin 线程驱动，JNI 每次 step 返回一个 token piece，
// prompt 预填充整段 decode，KV 每轮 clear 重算（简单可靠）。
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <cstdarg>
#include <vector>
#include <cctype>
#include <cstdio>

#include "llama.h"
#include "ggml-backend.h"   // dev_count/dev_get/dev_name，枚举 tensor_split 下标
#include "gguf.h"
#include "utf8_safe.h"

#define TAG "LlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)


static JavaVM * g_vm = nullptr;
static jobject   g_log_cb   = nullptr; // 全局引用：LlmLogBridge 实例
static jmethodID g_log_mid  = nullptr;

static void jni_log_cb(enum lm_ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!g_vm || !g_log_cb || !g_log_mid || !text) return;
    // Android NDK 的 AttachCurrentThread 签名是 (JNIEnv**, void*)，与桌面 JVM 的 (void**, void*) 不同
    JNIEnv * env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) return;
    jstring s = new_string_utf8_safe(env, text);
    if (s) {
        env->CallVoidMethod(g_log_cb, g_log_mid, (jint) level, s);
        env->ExceptionClear(); // 日志回调绝不向上抛
        env->DeleteLocalRef(s);
    }
    // llama 线程为长生命周期，且无法区分是否本函数创建的 attach，故不 detach。
}


// 插桩：关键节点日志同时进 UI ring（经 jni_log_cb）与 logcat
static void jlog(const char * fmt, ...) {
    char buf[512];
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    LOGI("%s", buf);
    jni_log_cb((enum lm_ggml_log_level) 4, buf, nullptr); // level 4 → UI 错误色
}

struct Session {
    llama_model    * model = nullptr;
    llama_context  * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler  * smpl  = nullptr;
    int   n_ctx     = 0;
    int   n_used    = 0;   // 本轮已入 KV 的 token 数
    int   n_rem     = 0;   // 剩余可生成 token 上限
    bool  abort     = false;
    std::string pending;  // 不完整 UTF-8 尾部
};
static Session S;

static void unloadModelInternal(); // 释放采样器/上下文/模型（定义见文件尾）

// ---------- UTF-8 完整序列切分：只下发完整字符，尾部留到下一 token ----------
static std::string take_complete_utf8(std::string & buf) {
    size_t ok = 0;
    while (ok < buf.size()) {
        unsigned char c = (unsigned char) buf[ok];
        size_t len = 1;
        if      (c < 0x80) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else { ok++; continue; } // 非法字节，丢弃
        if (ok + len > buf.size()) break; // 尾部不完整
        bool valid = true;
        for (size_t i = 1; i < len; i++)
            if (((unsigned char) buf[ok + i] & 0xC0) != 0x80) { valid = false; break; }
        if (!valid) { ok++; continue; }
        ok += len;
    }
    std::string out = buf.substr(0, ok);
    buf.erase(0, ok);
    return out;
}

static std::string token_to_piece(llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(S.vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) { // 缓冲不足，按需扩
        std::vector<char> big((size_t)(-n) + 8);
        n = llama_token_to_piece(S.vocab, t, big.data(), (int32_t) big.size(), 0, false);
        if (n < 0) return "";
        return std::string(big.data(), (size_t) n);
    }
    return std::string(buf, (size_t) n);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) { g_vm = vm; return JNI_VERSION_1_6; }

// backendInit(logBridge) — 在 System.loadLibrary 之后、loadModel 之前调用一次
JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_backendInit(JNIEnv * env, jclass, jobject bridge) {
    if (g_log_cb) return;
    g_log_cb = env->NewGlobalRef(bridge);
    jclass cls = env->GetObjectClass(g_log_cb);
    g_log_mid  = env->GetMethodID(cls, "onNativeLog", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    llama_log_set(jni_log_cb, nullptr);
    llama_backend_init();
    LOGI("llama backend init (hexagon/opencl/cpu)");
}

// 加载**前**只读 GGUF header（no_alloc=true，不 mmap 权重、不占内存、毫秒级），
// 按 HTP 受理 mul_mat 的真实语义统计「有多少字节的矩阵乘权重能上 NPU」，
// 取代原先靠文件名正则猜量化的做法 —— 文件名可能标错，猜错就得重新加载一遍大模型。
// 判定依据 ggml-hexagon.cpp:2783 supported_mul_mat：src0 类型 ∈
// {Q4_0,Q4_1,Q8_0,IQ4_NL,MXFP4}（这五种还要求 ne[0]%32==0）或 {F16,F32}，default → false。
// output/token_embd/position 不计入：HTP 明确拒收 lm-head（ne[1]>32768），它们落 CPU 本就是正确行为。
// 返回 String[4] = {主类型大写名, 受理百分比, "1"/"0" HTP可算, 说明}；读不到时 {("", -1, "", 原因)}。
JNIEXPORT jobjectArray JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeProbeGguf(JNIEnv * env, jclass, jstring jpath) {
    auto mk = [&](const char *a, const char *b, const char *c, const char *d) -> jobjectArray {
        jclass sc = env->FindClass("java/lang/String");
        jobjectArray out = env->NewObjectArray(4, sc, nullptr);
        const char *v[4] = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            jstring s = env->NewStringUTF(v[i] ? v[i] : "");
            env->SetObjectArrayElement(out, i, s);
            env->DeleteLocalRef(s);
        }
        return out;
    };

    const char *cpath = env->GetStringUTFChars(jpath, nullptr);
    if (!cpath) return mk("", "-1", "", "路径为空");
    struct lm_gguf_init_params params;
    params.no_alloc = true;
    params.ctx = nullptr;
    struct lm_gguf_context *ctx = lm_gguf_init_from_file(cpath, params);
    env->ReleaseStringUTFChars(jpath, cpath);
    if (!ctx) return mk("", "-1", "", "GGUF header 读取失败");

    const int64_t n = lm_gguf_get_n_tensors(ctx);
    long long ok_bytes = 0, all_bytes = 0;
    std::string bad_types;
    std::string main_type;
    long long main_bytes = -1;
    for (int64_t i = 0; i < n; i++) {
        const int64_t *ne = lm_gguf_get_tensor_ne(ctx, i);
        const char *tname = lm_gguf_get_tensor_name(ctx, i);
        if (!ne || !tname) continue;
        if (ne[1] <= 1) continue;  // 1D（norm/bias）不参与 matmul，本就该在 CPU
        std::string sname(tname);
        if (sname.find("output") != std::string::npos ||
            sname.find("token_embd") != std::string::npos ||
            sname.find("position") != std::string::npos) continue;

        const enum lm_ggml_type t = lm_gguf_get_tensor_type(ctx, i);
        const long long bytes = (long long) lm_gguf_get_tensor_size(ctx, i);
        all_bytes += bytes;

        bool sup;
        switch (t) {
            case LM_GGML_TYPE_Q4_0:
            case LM_GGML_TYPE_Q4_1:
            case LM_GGML_TYPE_Q8_0:
            case LM_GGML_TYPE_IQ4_NL:
            case LM_GGML_TYPE_MXFP4:
                sup = (ne[0] % 32) == 0 && ne[1] <= 32768;   // 与 ggml-hexagon.cpp:2795-2806 一致
                break;
            case LM_GGML_TYPE_F16:
            case LM_GGML_TYPE_F32:
                sup = true;
                break;
            default:
                sup = false;
                break;
        }
        if (sup) {
            ok_bytes += bytes;
        } else if (const char *tn = lm_ggml_type_name(t)) {
            if (bad_types.find(tn) == std::string::npos) {
                if (!bad_types.empty()) bad_types += "/";
                bad_types += tn;
            }
        }
        // 主类型 = 按字节数最多的那个（Q4_K_M 这种混合量化才有意义）
        const char *tn = lm_ggml_type_name(t);
        if (tn && bytes > main_bytes) { main_bytes = bytes; main_type = tn; }
    }
    lm_gguf_free(ctx);

    for (char &ch : main_type) ch = (char) toupper((unsigned char) ch);
    const int cov = all_bytes > 0 ? (int) (ok_bytes * 100 / all_bytes) : -1;
    const bool usable = all_bytes > 0 && cov >= 95;
    char buf[48];
    snprintf(buf, sizeof(buf), "%d", cov);
    std::string detail = "HTP 受理 " + std::to_string(cov) + "% 权重";
    if (!bad_types.empty()) detail += "，被拒类型: " + bad_types;
    if (all_bytes == 0) detail = "没找到参与 matmul 的权重张量";
    LOGI("gguf probe: type=%s coverage=%d usable=%d (%s)", main_type.c_str(), cov, usable ? 1 : 0, detail.c_str());
    return mk(main_type.c_str(), buf, usable ? "1" : "0", detail.c_str());
}

// ---- 「NPU 配额」= mp.tensor_split ----
// tensor_split 只改「每台拿多少比例」，设备一个不删：HTP 因 64 位对齐
// (needs_aligned_size(...,64)) 拒收的那些层仍然回到 GPU/CPU。这条回落依赖 GPU 仍在
// 设备列表里 —— 若把 OpenCL 整个摘掉，被拒的层就无处回落、全部落到 CPU。
// 依据（本仓 include）：llama.h:322 tensor_split 是长度 llama_max_devices() 的比例数组；
// ggml-backend.h:241/242/181 dev_count/dev_get/dev_name 可按注册顺序枚举全部设备。
// llama loader 正是按这个注册顺序逐台打 "using device %s"，因此 Kotlin 侧观测到的池顺序
// 应当与此处枚举完全一致 —— 两个独立来源互验，Kotlin 不参与猜下标。
static float g_split[64];

// 追加式格式化。必须钳制 w：snprintf 在截断时返回「本该写入的长度」，
// 若直接累加，w 可能超过 cap，之后 cap - w 在 size_t 下会下溢成巨值 → 越界写。
static void split_desc_append(char * out, size_t cap, int * w, const char * fmt, ...) {
    if (*w < 0 || (size_t) *w + 1 >= cap) return;   // 全用 size_t 比较，避免有/无符号混比
    va_list ap; va_start(ap, fmt);
    const int k = vsnprintf(out + *w, cap - (size_t) *w, fmt, ap);
    va_end(ap);
    if (k > 0) *w += k;
    if (*w < 0 || (size_t) *w >= cap) *w = (int) cap - 1;
}

static bool build_htp_split(int quota_pct, char * out, size_t cap) {
    if (cap < 64) { out[0] = '\0'; return false; }
    out[0] = '\0';
    int w = 0;
    const size_t maxd = llama_max_devices();
    const size_t n    = lm_ggml_backend_dev_count();
    split_desc_append(out, cap, &w, "dev_count=%zu llama_max_devices=%zu", n, maxd);
    if (n == 0 || maxd == 0 || n > sizeof(g_split) / sizeof(g_split[0])) {
        split_desc_append(out, cap, &w, " ｜ 超出可处理范围，配额档不生效");
        return false;
    }
    bool is_htp[64];
    int n_htp = 0, n_other = 0;
    for (size_t i = 0; i < n; i++) {
        lm_ggml_backend_dev_t d = lm_ggml_backend_dev_get(i);
        const char * nm = d ? lm_ggml_backend_dev_name(d) : nullptr;
        is_htp[i] = (nm != nullptr && strncmp(nm, "HTP", 3) == 0);
        if (is_htp[i]) n_htp++; else n_other++;
    }
    if (n_htp == 0) {
        split_desc_append(out, cap, &w, " ｜ 池内无 HTP 设备，配额档不生效");
        return false;
    }
    const int qp = quota_pct < 0 ? 0 : (quota_pct > 100 ? 100 : quota_pct);
    const float per_htp   = (float) qp / 100.0f / (float) n_htp;
    const float per_other = n_other > 0 ? (100.0f - (float) qp) / 100.0f / (float) n_other : 0.0f;
    // 全零 split 会让 loader 无从下手（配额 0% 且池里没有非 HTP 设备时会出现），判为不生效。
    if (per_htp <= 0.0f && per_other <= 0.0f) {
        split_desc_append(out, cap, &w, " ｜ 配额算出全零（quota=0%% 且无承接设备），不生效");
        return false;
    }
    split_desc_append(out, cap, &w, " ｜ HTP=%d台 其余=%d台 ｜ split=", n_htp, n_other);
    for (size_t i = 0; i < n; i++) {
        lm_ggml_backend_dev_t d = lm_ggml_backend_dev_get(i);
        const char * nm = d ? lm_ggml_backend_dev_name(d) : "?";
        g_split[i] = is_htp[i] ? per_htp : per_other;
        split_desc_append(out, cap, &w, "%s#%zu %s=%.3f", i ? " " : "", i, nm ? nm : "?", g_split[i]);
    }
    for (size_t i = n; i < maxd && i < 64; i++) g_split[i] = 0.0f;   // 未用到的槽位必须为 0
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel(
        JNIEnv * env, jclass, jstring jpath, jint nGpuLayers, jint nCtx,
        jint nThreads, jboolean flashAttn, jboolean useMmap, jint cacheK, jint cacheV,
        jint parallelN, jint batchSize, jint ubatchSize, jint npuQuotaPct) {
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;
    std::string modelPath(path);
    env->ReleaseStringUTFChars(jpath, path);

    unloadModelInternal(); // 定义在下方
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = (int) nGpuLayers;
    // NPU 配额档。npuQuotaPct<0 = 自动（tensor_split 保持 NULL，与本功能引入前逐字节相同）
    if (npuQuotaPct >= 0) {
        char sd[512] = {0};
        if (build_htp_split((int) npuQuotaPct, sd, sizeof(sd))) {
            mp.tensor_split = g_split;
            jlog("[NPU配额] 请求 NPU=%.0f%% ｜ %s", (double) npuQuotaPct, sd);
        } else {
            mp.tensor_split = nullptr;
            jlog("[NPU配额] 请求 %d%% 未生效，按自动分配继续 ｜ %s", (int) npuQuotaPct, sd);
        }
    } else {
        mp.tensor_split = nullptr;
        jlog("[NPU配额] 自动（未传 tensor_split，沿用 llama/HTP 内建份额）");
    }
    if (!useMmap) mp.load_mode = LLAMA_LOAD_MODE_NONE; // 无mmap整模直读（默认 useMmap=true 保持原行为）
    S.model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!S.model) { LOGE("model load failed: %s", modelPath.c_str()); return JNI_FALSE; }
    S.vocab = llama_model_get_vocab(S.model);

    llama_context_params cp = llama_context_default_params();
    if (nCtx > 0) cp.n_ctx = (uint32_t) nCtx;
    if (cacheK > 0) cp.type_k = (enum lm_ggml_type) cacheK; // KV cache 类型: 0=f16默认, 8=q8_0, 2=q4_0
    if (cacheV > 0) cp.type_v = (enum lm_ggml_type) cacheV;
    int nt = nThreads > 0 ? nThreads : 4;
    cp.n_threads = nt; cp.n_threads_batch = nt;
    // 批次与序列参数（<=0 保留 llama.cpp 默认：n_seq_max=1, n_batch/n_ubatch=2048）
    if (parallelN  > 0) cp.n_seq_max = (uint32_t) parallelN;
    if (batchSize  > 0) cp.n_batch    = (uint32_t) batchSize;
    if (ubatchSize > 0) cp.n_ubatch   = (uint32_t) ubatchSize;
    cp.flash_attn_type = flashAttn == JNI_TRUE
            ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    if (cacheV > 0 && cacheV != 1) {
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED; // V 缓存量化必须开 FA
        LOGI("cache_v quantized -> force flash attention");
    }
    S.ctx = llama_init_from_model(S.model, cp);
    if (!S.ctx) { LOGE("ctx create failed"); llama_model_free(S.model); S.model = nullptr; S.vocab = nullptr; return JNI_FALSE; }
    S.n_ctx = (int) llama_n_ctx(S.ctx);
    char desc[256] = {0};
    llama_model_desc(S.model, desc, sizeof(desc));
    LOGI("model ready: %s, n_ctx=%d, threads=%d, gpu=%d, cache_k=%d, cache_v=%d, parallel_n=%d, batch_size=%d, ubatch_size=%d", desc, S.n_ctx, nt, nGpuLayers, cacheK, cacheV, cp.n_seq_max, cp.n_batch, cp.n_ubatch);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeModelDesc(JNIEnv * env, jclass) {
    if (!S.model) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(S.model, desc, sizeof(desc));
    // desc 按字节截断，中文模型名可能留下半个多字节序列 → 必须走安全解码
    return new_string_utf8_safe(env, desc);
}

JNIEXPORT jint JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeCtxSize(JNIEnv *, jclass) { return S.n_ctx; }
JNIEXPORT jint JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeCtxUsed(JNIEnv *, jclass)  { return S.n_used; }

JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeChatTemplate(JNIEnv * env, jclass) {
    if (!S.model) return env->NewStringUTF("");
    const char * t = llama_model_chat_template(S.model, nullptr);
    return t ? new_string_utf8_safe(env, t) : env->NewStringUTF("");
}

// newSampler(temp, topP, minP, topK, repPenalty, penaltyN, freqPenalty, seed) — temp<=0 视为贪心
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeNewSampler(
        JNIEnv *, jclass, jfloat temp, jfloat topP, jfloat minP, jint topK,
        jfloat repPenalty, jint penaltyN, jfloat freqPenalty, jfloat presencePenalty, jlong seed) {
    if (!S.ctx) return JNI_FALSE;
    if (S.smpl) { llama_sampler_free(S.smpl); S.smpl = nullptr; }
    llama_sampler * ch = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(ch, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(ch, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(ch, llama_sampler_init_temp((float) temp));
        if (topP > 0.0f && topP < 1.0f) llama_sampler_chain_add(ch, llama_sampler_init_top_p((float) topP, 1));
        if (minP > 0.0f)                llama_sampler_chain_add(ch, llama_sampler_init_min_p((float) minP, 1));
        if (repPenalty != 1.0f || freqPenalty != 0.0f) {
            llama_sampler_chain_add(ch, llama_sampler_init_penalties(
                llama_vocab_n_tokens(S.vocab), penaltyN, repPenalty, freqPenalty, presencePenalty));
        }
        llama_sampler_chain_add(ch, llama_sampler_init_dist((uint32_t) seed));
    }
    S.smpl = ch;
    return JNI_TRUE;
}

// startCompletion(prompt, maxTokens): 清 KV -> tokenize(含 BOS) -> 分块 decode
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeStartCompletion(
        JNIEnv * env, jclass, jstring jprompt, jint maxTokens) {
    if (!S.ctx || !S.smpl) return JNI_FALSE;
    const char * p = env->GetStringUTFChars(jprompt, nullptr);
    if (!p) return JNI_FALSE;
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    llama_memory_seq_rm(llama_get_memory(S.ctx), 0, -1, -1);
    S.pending.clear(); S.n_used = 0; S.n_rem = (int) maxTokens; S.abort = false;

    int need = llama_tokenize(S.vocab, prompt.c_str(), (int32_t) prompt.size(),
                              nullptr, 0, true, true);
    {
        std::string head;
        for (unsigned char c : prompt.substr(0, 160)) head += (c >= 32 && c < 127) ? (char) c : '.';
        jlog("[diag] prompt_len=%d head=%s", (int) prompt.size(), head.c_str());
        jlog("[diag] tokenize_need=%d", need);
    }
    std::vector<llama_token> tokens;
    // 探测调用约定：返回所需 token 数的负值 -N 是正常结果；仅 0 表示空串
    if (need == 0) { jlog("[diag] FAIL: empty prompt, tokenize returned 0"); return JNI_FALSE; }
    if (need < 0) need = -need;
    tokens.resize((size_t) need);
    if (llama_tokenize(S.vocab, prompt.c_str(), (int32_t) prompt.size(),
                       tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        jlog("[diag] FAIL: tokenize second pass"); return JNI_FALSE;
    }
    if ((int) tokens.size() >= S.n_ctx) { jlog("[diag] FAIL: %d tokens >= ctx %d", (int) tokens.size(), S.n_ctx); return JNI_FALSE; }

    const int n_batch = (int) llama_n_batch(S.ctx);
    for (size_t off = 0; off < tokens.size(); off += (size_t) n_batch) {
        if (S.abort) return JNI_FALSE;
        int32_t n = (int32_t) std::min((size_t) n_batch, tokens.size() - off);
        if (llama_decode(S.ctx, llama_batch_get_one(tokens.data() + off, n)) != 0) {
            jlog("[diag] FAIL: prefill decode rc!=0 at offset %zu (n=%d)", off, n);
            return JNI_FALSE;
        }
        S.n_used += n;
    }
    jlog("[diag] prefill OK: %d tokens, first=%d last=%d", (int) tokens.size(), tokens.front(), tokens.back());
    return JNI_TRUE;
}

// step() -> 完整 UTF-8 片段；""=继续但本步无字；null=结束（EOG/abort/超限/错误）
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeStep(JNIEnv * env, jclass) {
    if (!S.ctx || !S.smpl || S.abort) return nullptr;
    if (S.n_used + 2 >= S.n_ctx || S.n_rem <= 0) return nullptr;

    llama_token tok = llama_sampler_sample(S.smpl, S.ctx, -1);
    llama_sampler_accept(S.smpl, tok);
    if (llama_vocab_is_eog(S.vocab, tok)) return nullptr;

    // 把采样出的 token 喂回 KV
    llama_batch b = llama_batch_get_one(&tok, 1);
    if (llama_decode(S.ctx, b) != 0) return nullptr;
    S.n_used++; S.n_rem--;

    S.pending += token_to_piece(tok);
    std::string out = take_complete_utf8(S.pending);
    // take_complete_utf8 已保证不吐半截序列，仍走安全解码做双保险：
    // 模型输出里的孤立代理对 / 非法序列同样会让 CheckJNI abort（表现为对话中闪退）
    return new_string_utf8_safe(env, out.c_str());
}

// applyChatTemplate(tmpl, roles[], contents[], addAss) -> 渲染后的 prompt；失败返回 null
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeApplyChatTemplate(
        JNIEnv * env, jclass, jstring jtmpl, jobjectArray roles, jobjectArray contents, jboolean addAss) {
    const char * tmpl = env->GetStringUTFChars(jtmpl, nullptr);
    if (!tmpl) return nullptr;
    std::string t(tmpl);
    env->ReleaseStringUTFChars(jtmpl, tmpl);
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> rs, cs;
    rs.reserve(n); cs.reserve(n);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto jc = (jstring) env->GetObjectArrayElement(contents, i);
        const char * rp = jr ? env->GetStringUTFChars(jr, nullptr) : nullptr;
        const char * cp = jc ? env->GetStringUTFChars(jc, nullptr) : nullptr;
        rs.emplace_back(rp ? rp : "");
        cs.emplace_back(cp ? cp : "");
        if (rp && jr) env->ReleaseStringUTFChars(jr, rp);
        if (cp && jc) env->ReleaseStringUTFChars(jc, cp);
        if (jr) env->DeleteLocalRef(jr);
        if (jc) env->DeleteLocalRef(jc);
    }
    std::vector<llama_chat_message> msgs;
    msgs.reserve(n);
    for (jsize i = 0; i < n; i++) msgs.push_back({ rs[i].c_str(), cs[i].c_str() });
    std::vector<char> buf(4096);
    int32_t need = llama_chat_apply_template(t.c_str(), msgs.data(), msgs.size(),
                                             addAss == JNI_TRUE, buf.data(), (int32_t) buf.size());
    if (need > (int32_t) buf.size()) {
        buf.resize((size_t) need + 8);
        need = llama_chat_apply_template(t.c_str(), msgs.data(), msgs.size(),
                                         addAss == JNI_TRUE, buf.data(), (int32_t) buf.size());
    }
    if (need < 0) return nullptr;
    return new_string_utf8_safe(env, std::string(buf.data(), (size_t) need).c_str());
}

JNIEXPORT void JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeAbort(JNIEnv *, jclass) { S.abort = true; }

JNIEXPORT void JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeFreeSampler(JNIEnv *, jclass) {
    if (S.smpl) { llama_sampler_free(S.smpl); S.smpl = nullptr; }
}


JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeUnloadModel(JNIEnv *, jclass) { unloadModelInternal(); }

} // extern "C"

static void unloadModelInternal() {
    if (S.smpl)  { llama_sampler_free(S.smpl);   S.smpl = nullptr; }
    if (S.ctx)   { llama_free(S.ctx);            S.ctx = nullptr; }
    if (S.model) { llama_model_free(S.model);    S.model = nullptr; S.vocab = nullptr; }
    S.n_ctx = 0; S.n_used = 0; S.n_rem = 0;
    S.pending.clear();
}

