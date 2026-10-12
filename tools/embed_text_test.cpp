// embed_text_test.cpp — 向量嵌入纯逻辑的宿主侧单测
//
// 与 llama_jni.cpp **共用同一份** app/src/main/cpp/embed_text.h（不是重抄 ——
// 重抄就等于没测）：这里绿了，才代表线上算出的向量、池化下标与检索顺序是对的。
//
// 专钉**没有异常、没有日志、也不崩溃**的故障：
//   1. 不归一化 -> 检索按向量长度排序，"总是命中同几条"，语义全错；
//   2. 零向量塞进索引 -> 余弦 0/0 = NaN，排序未定义；
//   3. 池化取错 token / 越界 -> 读到一段无关上下文，或越界读段错误；
//   4. 维度对不上 -> 点积读越界 / 结果全错，而数组长度看起来没问题；
//   5. 同分顺序不稳定 -> 真机验收不可复现。
#include "embed_text.h"

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

static int g_pass = 0, g_fail = 0;

static void ok(bool cond, const std::string & what) {
    if (cond) { g_pass++; printf("  ok   %s\n", what.c_str()); }
    else      { g_fail++; printf("  FAIL %s\n", what.c_str()); }
}

static bool near(float a, float b, float eps = 1e-5f) { return std::fabs(a - b) < eps; }

int main() {
    printf("== 组 1：L2 归一化 ==\n");
    {
        float v[] = {3.0f, 4.0f};
        ok(embedtext::l2_normalize(v, 2), "正常向量归一化返回 true");
        ok(near(v[0], 0.6f) && near(v[1], 0.8f), "3,4 -> 0.6,0.8");

        float u[] = {1.0f};
        ok(embedtext::l2_normalize(u, 1), "单元素归一化返回 true");
        ok(near(u[0], 1.0f), "1 -> 1");

        float z[] = {0.0f, 0.0f};
        ok(!embedtext::l2_normalize(z, 2), "零向量必须**拒绝**（不能编造方向）");
        ok(z[0] == 0.0f && z[1] == 0.0f, "零向量被拒时不许改写原数组");

        float nanv[] = {NAN, 1.0f};
        ok(!embedtext::l2_normalize(nanv, 2), "NaN 必须拒绝（否则整条向量被污染）");
        float infv[] = {INFINITY, 0.0f};
        ok(!embedtext::l2_normalize(infv, 2), "Inf 必须拒绝");

        ok(!embedtext::l2_normalize(nullptr, 4), "null 指针必须拒绝，不许崩");
        float e[] = {1.0f};
        ok(!embedtext::l2_normalize(e, 0), "长度 0 必须拒绝");

        // 归一化后模长必须为 1（这是"余弦=点积"能成立的前提）
        float w[] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        embedtext::l2_normalize(w, 5);
        double s = 0; for (float x : w) s += (double) x * x;
        ok(near((float) s, 1.0f, 1e-5f), "归一化后模长平方 = 1");
    }

    printf("== 组 2：last-token pooling 下标 ==\n");
    {
        ok(embedtext::last_token_index(1) == 0, "单 token -> 下标 0");
        ok(embedtext::last_token_index(5) == 4, "5 token -> 下标 4");
        ok(embedtext::last_token_index(0) == -1, "空文本 -> -1（**不是** 0，取 0 会读到无意义上下文）");
        ok(embedtext::last_token_index(-3) == -1, "负数 -> -1");
    }

    printf("== 组 2b：output 数组下标（≠ batch 下标，最易写错的一处）==\n");
    {
        // 只给最后一个 token 置 logits=1 -> output 数组恰好 1 个元素 -> 读下标 0。
        // 写成 n_tokens-1 时，只要超过 1 个 token，output_ids[i] 就无效，
        // llama_get_embeddings_ith 返回 null -> 检索全空而接口 200。
        ok(embedtext::first_output_index(1) == 0, "1 token -> 读 output[0]");
        ok(embedtext::first_output_index(5) == 0, "5 token -> 仍是 output[0]（不是 4！）");
        ok(embedtext::first_output_index(1000) == 0, "1000 token -> 仍是 output[0]");
        ok(embedtext::first_output_index(0) == -1, "空文本 -> -1（丢弃这一条）");
        ok(embedtext::first_output_index(-2) == -1, "负数 -> -1");
        // 明确钉住"这两个下标不是一回事"——它们同名的可能性正是本 bug 的入口。
        ok(embedtext::first_output_index(5) != embedtext::last_token_index(5),
           "output 下标与 batch 下标在多 token 时**必须不同**（防被写成同一个）");
    }

    printf("== 组 3：池化取行 ==\n");
    {
        // 3 个 token、每行 2 维： [[1,2],[3,4],[5,6]]
        const float flat[] = {1, 2, 3, 4, 5, 6};
        std::vector<float> out;
        ok(embedtext::pool_row(flat, 6, 2, 2, out), "取最后一行成功");
        ok(out.size() == 2 && out[0] == 5 && out[1] == 6, "取到的是最后一行的值");
        ok(embedtext::pool_row(flat, 6, 2, 0, out) && out[0] == 1, "取第 0 行");
        ok(embedtext::pool_row(flat, 6, 2, 1, out) && out[0] == 3, "取第 1 行");

        ok(!embedtext::pool_row(flat, 6, 2, 3, out), "下标越界必须拒绝，不许读越界内存");
        ok(!embedtext::pool_row(flat, 6, 2, -1, out), "下标 -1 必须拒绝");
        ok(!embedtext::pool_row(nullptr, 6, 2, 0, out), "null 必须拒绝");
        ok(!embedtext::pool_row(flat, 6, 0, 0, out), "nEmbd=0 必须拒绝");
        // flat 长度不够：声称 3 行但只给了 4 个数
        ok(!embedtext::pool_row(flat, 4, 2, 2, out), "flat 长度不足必须拒绝（否则越界读）");
    }

    printf("== 组 4：余弦与 top-k ==\n");
    {
        float a[] = {1, 0, 0};
        float b[] = {1, 0, 0};
        float c[] = {0, 1, 0};
        float d[] = {-1, 0, 0};
        ok(near(embedtext::cosine(a, b, 3), 1.0f), "同向 -> 1");
        ok(near(embedtext::cosine(a, c, 3), 0.0f), "正交 -> 0");
        ok(near(embedtext::cosine(a, d, 3), -1.0f), "反向 -> -1");
        ok(near(embedtext::cosine(a, b, 0), 0.0f), "长度 0 -> 0（不是 NaN）");
        ok(near(embedtext::cosine(nullptr, b, 3), 0.0f), "null -> 0，不许崩");

        // 3 行、每行 2 维，query = [1,0]
        const float m[] = {
            0.0f, 1.0f,   // 与 query 正交 -> 0
            1.0f, 0.0f,   // 与 query 同向 -> 1
            0.6f, 0.8f,   // 与 query 余弦 0.6
        };
        float q[] = {1.0f, 0.0f};
        auto hits = embedtext::top_k(m, 3, 2, q, 3);
        ok(hits.size() == 3, "top_k 返回 3 条");
        ok(hits[0].index == 1 && near(hits[0].score, 1.0f), "最相似的是第 1 行（score 1）");
        ok(hits[1].index == 2 && near(hits[1].score, 0.6f), "其次是第 2 行（score 0.6）");
        ok(hits[2].index == 0 && near(hits[2].score, 0.0f), "最后是第 0 行（score 0）");

        auto k2 = embedtext::top_k(m, 3, 2, q, 2);
        ok(k2.size() == 2, "k=2 只返回 2 条");
        ok(k2[0].index == 1 && k2[1].index == 2, "k=2 取的是分数最高的两条");

        auto k9 = embedtext::top_k(m, 3, 2, q, 9);
        ok(k9.size() == 3, "k > 行数时只返回行数那么多（不越界）");

        // 稳定性：两行同分时按下标小的在前，且多次调用结果一致
        const float same[] = {1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f};
        float q2[] = {1.0f, 0.0f};
        auto s1 = embedtext::top_k(same, 3, 2, q2, 3);
        auto s2 = embedtext::top_k(same, 3, 2, q2, 3);
        ok(s1[0].index == 0 && s1[1].index == 1, "同分时下标小的优先");
        bool sameOrder = s1.size() == s2.size();
        for (size_t i = 0; sameOrder && i < s1.size(); i++)
            sameOrder = (s1[i].index == s2[i].index) && (s1[i].score == s2[i].score);
        ok(sameOrder, "同一次查询多次调用顺序完全一致（可复现）");

        ok(embedtext::top_k(m, 0, 2, q, 3).empty(), "0 行 -> 空（不是崩）");
        ok(embedtext::top_k(m, 3, 0, q, 3).empty(), "nEmbd=0 -> 空");
        ok(embedtext::top_k(m, 3, 2, q, 0).empty(), "k=0 -> 空");
        ok(embedtext::top_k(nullptr, 3, 2, q, 3).empty(), "null 矩阵 -> 空，不许崩");
        ok(embedtext::top_k(m, 3, 2, nullptr, 3).empty(), "null 查询 -> 空，不许崩");
    }

    printf("== 组 5：输出维度对照（方案 B 的核心判据）==\n");
    {
        // 相等才放行：`llama_get_embeddings_ith` 按 n_embd 步长读，库按 n_embd_out
        // 分配 —— 不等时"读一行"是未定义行为，n_embd_out 小时跨进别的 buffer
        //（含未初始化的 NaN），表现为"某些条失败且时好时坏"，与文本内容无关。
        ok(embedtext::output_dim_ok(1024, 1024), "维度相等 -> 放行");
        ok(!embedtext::output_dim_ok(1024, 512), "n_embd_out 更小（越界读）必须拒绝");
        ok(!embedtext::output_dim_ok(512, 1024), "n_embd_out 更大（语义被截）必须拒绝");
        ok(!embedtext::output_dim_ok(0, 0), "两侧都取不到 -> 拒绝（不是'相等'）");
        ok(!embedtext::output_dim_ok(1024, 0), "n_embd_out 取不到 -> 拒绝");
        ok(!embedtext::output_dim_ok(0, 1024), "n_embd 取不到 -> 拒绝");
        ok(!embedtext::output_dim_ok(-1, -1), "负数（库报错）-> 拒绝");
    }

    printf("== 组 6：池化计划（方案 A）==\n");
    {
        // NONE(0) / UNSPECIFIED(-1)：语义是"取最后一个 token"，
        // 而库把逐 token 输出写进 `embd.data` → 走 `ith`。
        for (int t : {0, -1}) {
            auto p = embedtext::plan_pooling(t, 4);
            ok(p.valid, std::string("pooling=") + std::to_string(t) + " -> 有效计划");
            ok(p.pool == embedtext::Pool::LAST, "  归到 LAST（该档的正确池化语义）");
            ok(p.output_count == 1, "  只要 1 个 output");
            ok(p.row == embedtext::SeqRow::LAST_TOKEN, "  语义 = 读最后一个 token 那一行");
            // ⚠ 这里曾经把**排位**与**位置**当成一个数：断言 `batch_last_slot == 3`
            //    （batch 位置），而实现填的是 **0**（output 排位）。两者只在
            //    "output 恰好整段都在"时相等，LAST 档只置了最后一项 → 排位 0、
            //    位置 nTokens-1，**永不相等** → 每条嵌入被判"计划不自洽"。
            //    真机日志：`[embed] 池化计划不自洽：pooling=3 row=0 output_slot=0`。
            //
            //    正确口径只有一条：**读的那一项换算回 batch 位置，必须落在最后**。
            //    0 是排位（output 里第 0 项），它对应的位置由 `output_slot_to_batch` 说。
            ok(p.batch_last_slot == 0 && p.output_slot == 0,
               "  排位都是 0（output 里只有我们置的 1 项，它排第 0）");
            ok(embedtext::output_slot_to_batch(p, p.output_slot) == 3,
               "  第 0 项换算回 batch 位置 == 3（== nTokens-1，这才是「读的是最后一行」）");
            ok(p.output_slot == p.batch_last_slot,
               "  两个字段同源（都指 output 里同一个排位，不许各写一个字面量）");
            ok(p.want_logits.size() == 4 && p.want_logits[3] == 1 &&
               p.want_logits[0] == 0 && p.want_logits[2] == 0,
               "  只给最后一个 token 置 logits（库只要求≥1 个）");
            ok(embedtext::logits_ok_for_library(t, p.want_logits, 4),
               "  该计划满足库对 NONE/LAST 的契约（至少一个 output）");
            ok(embedtext::plan_is_coherent(p, 4), "  计划自洽（row/slot/logits 互相支持）");
        }
        // ⚠ 单 token 的退化情形：排位 0 与位置 0 数值相同，最容易把两个
        //   语义混成一个。这里钉住"换算"在退化情形下也成立。
        {
            auto p = embedtext::plan_pooling(0, 1);
            ok(p.valid && p.batch_last_slot == 0 && p.output_slot == 0 && p.want_logits[0] == 1,
               "单 token（NONE）：排位与位置都是 0（数值相同但由换算函数给出）");
            ok(embedtext::output_slot_to_batch(p, 0) == 0,
               "  第 0 项换算回 batch 位置 == 0");
            ok(embedtext::plan_is_coherent(p, 1), "  单 token 计划自洽");
        }
        // ═══════════════════════════════════════════════════════════════════
        // LAST(3)：**与 NONE(0) 同语义，不同 buffer** —— 这是本轮修的 bug。
        // ═══════════════════════════════════════════════════════════════════
        // 生成式 decoder 嵌入模型（Qwen3-Embedding，GGUF 里写 pooling=3）走这一档。
        // 库把池化结果写进 `embd_seq`（`get_embeddings_seq` 读它），
        // **`embd.data` 一个字节都不写** —— 所以这一档**必须**走 seq。
        // 早先 `read_from_seq` 只认 {1,2}、`plan.row` 只认 LAST_TOKEN，
        // 于是 `pooling=3` 走 `ith` 去读一个"分配了但从未写入"的 buffer：
        // 拿到**全零**（不是 null，绕过 no_output 判据）→ `范数=0` 条条失败。
        // 而 `pooling=2` 的 BGE 走 seq 照常能用 → 看起来像"这个模型不行"。
        {
            auto p = embedtext::plan_pooling(3, 4);
            ok(p.valid, "pooling=3(LAST) -> 有效计划");
            ok(p.pool == embedtext::Pool::LAST, "  归到 LAST（语义仍是 last-token）");
            ok(p.pool_type_raw == 3, "  原始 pool 值随计划走（判据要知道是 3 不是 0）");
            ok(p.row == embedtext::SeqRow::LIB_POOLED,
               "  ⚠ LAST 读**库池化的那一份**（embd_seq）—— 不是 ith 的 BOS 行，也不是未写入的 embd.data");
            ok(p.output_slot == -1,
               "  LIB_POOLED 档不给 output_slot（一个「看起来能用的下标」会被误当成读的位置）");
            ok(embedtext::read_from_seq(3), "  read_from_seq(3) 必须为真（本次失效就是它漏了 3）");
            ok(embedtext::library_gives_seq(3), "  library_gives_seq(3) 必须为真（唯一真相来源）");
            ok(embedtext::plan_is_coherent(p, 4),
               "  计划自洽（row=LIB_POOLED 在这一档才是对的）");
            // 只要 1 个 output 仍然成立：库对 LAST 的门禁与 NONE 同档（至少一个）。
            ok(p.want_logits[3] == 1 && p.output_count == 1, "  仍然只要最后一行进 output");
            ok(embedtext::logits_ok_for_library(3, p.want_logits, 4),
               "  满足库对 LAST 的契约（至少一个 output）");
            // 我们归一化：库对池化结果**不做 L2**（见 normalize_pooling 的注释）。
            ok(embedtext::normalize_pooling(3), "  LAST 由我们做 L2（库不做）");
        }
        // CLS(2)：BGE 系。**读**的是第 0 个 token 那一行，
        // 但**要**的必须是整段 —— 库对 CLS 的前置校验是
        // `pooled embedding requires that all tokens are output`。
        // 早先的"只给第 0 个置 1"在这里是**错**的：它在库那一层直接 decode 失败，
        // 表现为 BGE 系条条失败（且被记成 decode_failed，读起来像内存问题）。
        {
            auto p = embedtext::plan_pooling(2, 5);
            ok(p.valid && p.pool == embedtext::Pool::CLS, "pooling=2 -> CLS");
            ok(p.row == embedtext::SeqRow::LIB_POOLED,
               "  CLS 读库池化的那一份（embd_seq）—— CLS 的定义就是第 0 个 token");
            ok(p.output_count == 5 && p.output_slot == -1,
               "  整段 5 行都进 output（满足库的前置校验），output_slot 留 -1（不走 ith）");
            int ones = 0; for (char c : p.want_logits) ones += (c ? 1 : 0);
            ok(ones == 5, "  CLS 必须把**整段**都标成 output（库的前置校验，5/5）");
            ok(p.want_logits[0] == 1 && p.want_logits[4] == 1,
               "  首尾都要置 1（不是只置第 0 个）");
            ok(embedtext::logits_ok_for_library(2, p.want_logits, 5),
               "  该计划满足库对 CLS 的契约（自检函数放行）");
            // ⚠ CLS 的向量**也**由我们归一化：库对池化结果不做 L2
            //（`build_pooling` 只在 RANK 支过 cls_norm 分类头）。
            // 0.9.160 那句"CLS 由库池化 + L2"是错的，见 normalize_pooling 的注释。
            ok(p.read_all_rows, "  CLS 走库给的 pooled buffer（read_all_rows）");
            ok(p.row == embedtext::SeqRow::LIB_POOLED,
               "  CLS 读库给的 seq 向量（不是 ith 的逐 token 区）");
            ok(embedtext::normalize_pooling(2),
               "  CLS 由我们归一化（库不做 L2 —— 检索侧 cosine 是纯点积，前提是两边都归过）");
        }
        // MEAN(1)：要全部行的输出。
        {
            auto p = embedtext::plan_pooling(1, 3);
            ok(p.valid && p.pool == embedtext::Pool::MEAN, "pooling=1 -> MEAN");
            ok(p.row == embedtext::SeqRow::LIB_POOLED,
               "  MEAN 读库给的 seq 向量（不是我们逐行平均）");
            ok(p.read_all_rows, "  MEAN 走库给的 pooled buffer");
            ok(p.output_count == 3, "  MEAN 需要全部 3 行");
            int ones = 0; for (char c : p.want_logits) ones += (c ? 1 : 0);
            ok(ones == 3, "  每个 token 都要置 logits（库按整段做平均）");
            ok(embedtext::logits_ok_for_library(1, p.want_logits, 3),
               "  该计划满足库对 MEAN 的契约（整段）");
            ok(embedtext::library_computes_pooling(1),
               "  MEAN 由**库**算（我们读 embeddings_seq，不自己累加）");
            ok(!embedtext::library_computes_pooling(2) && !embedtext::library_computes_pooling(3),
               "  CLS/LAST 不在 library_computes_pooling 里（那个谓词只管\"库自己按 seq 平均\"）");
            ok(embedtext::normalize_pooling(1), "  MEAN 也由我们归一化（库不做 L2）");
        }
        // RANK(4)：rerank 模型，输出是分类头、不是句向量 —— 必须拒绝。
        ok(!embedtext::plan_pooling(4, 3).valid, "pooling=4(RANK) 必须拒绝（不猜）");
        ok(!embedtext::plan_pooling(999, 3).valid, "未知 pooling 值必须拒绝");
        ok(!embedtext::plan_pooling(0, 0).valid, "0 token 必须拒绝（空文本不编造向量）");
        ok(!embedtext::plan_pooling(2, -1).valid, "负 token 数必须拒绝");
    }

    printf("== 组 6b：库对 logits[] 的前置契约（本轮的核心判据）==\n");
    {
        // ═══════════════════════════════════════════════════════════════════
        // 为什么必须有这一组
        // ═══════════════════════════════════════════════════════════════════
        // `llama_batch.logits[]` 是**给库的指令**，而库对几种池化方式有自己的前置
        // 校验（vendor 原文）：
        //   · CLS / MEAN：`pooled embedding requires that all tokens are output`；
        //   · NONE：`n_outputs >= 1`（一个都没有 = **断言失败 = 进程崩溃**）。
        //
        // 早先的实现对 CLS **只给第 0 个 token 置 1** —— 它在语义上"取对了位置"，
        // 却在库那一层直接踩校验：`llama_decode` 返回非 0，BGE 系（pooling=CLS）
        // **条条失败**。这正是"BGE 系仍 100% 失败"的原因，而失败被记成
        // decode_failed，读起来像内存/模型问题。
        //
        // 判据必须同时钉两头：**满足库**（这一组）与**读对位置**（组 6）。
        // 只钉一头就是"改对了语义、踩崩了库"（当时就是这样）。
        std::vector<char> all5 = {1, 1, 1, 1, 1};
        std::vector<char> only0 = {1, 0, 0, 0, 0};
        std::vector<char> onlyLast = {0, 0, 0, 0, 1};
        std::vector<char> none5 = {0, 0, 0, 0, 0};

        ok(embedtext::logits_ok_for_library(2, all5, 5), "CLS + 整段 -> 满足（唯一正确形态）");
        ok(!embedtext::logits_ok_for_library(2, only0, 5),
           "CLS + 只置第 0 个 -> **不满足**（库直接拒 decode，BGE 条条失败的根因）");
        ok(!embedtext::logits_ok_for_library(2, onlyLast, 5), "CLS + 只置最后一个 -> 不满足");

        ok(embedtext::logits_ok_for_library(1, all5, 5), "MEAN + 整段 -> 满足");
        ok(!embedtext::logits_ok_for_library(1, only0, 5), "MEAN + 只置第 0 个 -> 不满足");

        ok(embedtext::logits_ok_for_library(0, onlyLast, 5), "NONE + 只置最后一个 -> 满足");
        ok(embedtext::logits_ok_for_library(3, onlyLast, 5), "LAST + 只置最后一个 -> 满足");
        ok(embedtext::logits_ok_for_library(-1, onlyLast, 5), "UNSPECIFIED + 只置最后一个 -> 满足");
        ok(!embedtext::logits_ok_for_library(0, none5, 5),
           "NONE + 一个都不置 -> 不满足（库的 n_outputs>=1 断言失败 = 崩溃）");
        ok(!embedtext::logits_ok_for_library(3, none5, 5), "LAST + 一个都不置 -> 不满足");

        // RANK / 未知：我们根本不提交，这里也必须说不满足（免得将来"顺手放行"）。
        ok(!embedtext::logits_ok_for_library(4, all5, 5), "RANK -> 不满足（不该提交）");
        ok(!embedtext::logits_ok_for_library(999, all5, 5), "未知 pooling -> 不满足");

        // 长度对不上 = 判据本身失效，必须拒绝（否则"没检查"会被读成"检查过了"）。
        ok(!embedtext::logits_ok_for_library(2, all5, 4), "want_logits 长度 ≠ nTokens -> 拒绝");
        ok(!embedtext::logits_ok_for_library(2, {}, 0), "nTokens=0 -> 拒绝");

        // ── 计划与契约必须**天然**一致（不能靠"两处都写对了"）──────────────
        // 这一条是把组 6 与组 6b 绑在一起：任何一处的改动只要让两者不再同时成立，
        // 这里就红。RANK 不在内（plan_pooling 已拒，根本没有 want_logits）。
        for (int t : {0, 1, 2, 3, -1}) {
            for (int n : {1, 2, 7}) {
                auto p = embedtext::plan_pooling(t, n);
                ok(p.valid && embedtext::logits_ok_for_library(t, p.want_logits, n),
                   std::string("plan_pooling(") + std::to_string(t) + "," + std::to_string(n) +
                   ") 的计划满足库的契约");
            }
        }
    }

    printf("== 组 6c：向量从哪一路读（read_from_seq）==\n");
    {
        // 这一组钉的是"CLS 该读库池化好的那个、而不是逐 token 那个"。
        // 它对应一条**真实发生过**的失效：CLS 走了 `get_embeddings_ith`，
        // 而那条路的定义建在 `get_embeddings(ctx)` 有效之上 —— 后者只在
        // pooling==NONE 时成立。于是 BGE 读出全零（不是 null），
        // 绕过 no_output 判据直落 `范数=0`，用户看到的就是那条日志。
        //
        // 判据口径：**库把这一档的池化结果放进 `embd_seq` 就读 seq**。
        // vendor 里那个 switch 只把 `NONE` 写进 `embd.data`；`MEAN / CLS / LAST`
        // 三档全部写进 `embd_seq`。所以是**三档**，不是两档。
        ok(embedtext::read_from_seq(2), "CLS -> seq（池化结果在库的 embd_seq 里）");
        ok(embedtext::read_from_seq(1), "MEAN -> seq（库按 seq 平均）");
        ok(embedtext::read_from_seq(3),
           "LAST -> seq（⚠ 第三档：embd.data 一个字节都不写，写 ith 必拿到全零）");
        ok(!embedtext::read_from_seq(0), "NONE -> ith（唯一逐 token 写 embd.data 的档）");
        ok(!embedtext::read_from_seq(-1), "UNSPECIFIED -> ith（库侧实际会归成 NONE）");
        ok(!embedtext::read_from_seq(4), "RANK -> ith（虽然根本不提交，也不该走 seq）");

        // ⚠ 与 `library_computes_pooling` 的**包含关系**必须成立：
        // 库自己池化的那几档一定是走 seq 的那几档。两者若漂移，
        // 就会出现"库算了池化、我们却按 token 读"——正是本次的失效形态。
        for (int t : {0, 1, 2, 3, -1, 4}) {
            if (embedtext::library_computes_pooling(t))
                ok(embedtext::read_from_seq(t),
                   std::string("library_computes_pooling(") + std::to_string(t) +
                   ") 蕴含 read_from_seq 为真");
        }

        // MEAN 仍在 seq 那一支（回归保护：不能因为加 CLS 而把 MEAN 挪走）。
        ok(embedtext::read_from_seq(1) && embedtext::library_computes_pooling(1),
           "MEAN 同时满足两条（回归：旧口径不变）");
    }

    printf("== 组 6d：计划自洽 + 归一化口径同源（本 PR 的核心判据）==\n");
    {
        // ═══════════════════════════════════════════════════════════════════
        // 这一组守的是**本轮真 bug**：`row`（读哪一行）与 `output_slot`
        // （去输出数组的第几项）是两个不同的东西，而它们在一个特定前提下
        // 数值相同 —— 那个前提是"我们只给最后一位置了 logits"，由**别处**
        // 的代码决定。前提一改，读的就不是最后一行了，而错误是静默的。
        //
        // 真机上的报应（用户日志）：
        //   `[embed] 归一化失败（范数=3e-30 … pooling=3 …）`
        // pooling=3 是 LAST —— 读到的是**第 0 个 token**（生成式 decoder 里
        // 是 BOS，没有前文，隐藏态量级极小），而 BOS 行归一化必然拒绝。
        // 而 pooling=0（NONE）时库会给出正确的 last 向量，于是"两个生成式
        // 模型一个全败一个全成"—— 同一处式子分岔出来的两种命运。
        // ═══════════════════════════════════════════════════════════════════
        for (int t : {0, -1}) {
            auto p = embedtext::plan_pooling(t, 9);
            ok(p.row == embedtext::SeqRow::LAST_TOKEN, "LAST 语义：读最后一个 token 那一行");
            // ⚠⚠ 本 PR 修的正是这两行：上一版断言 `batch_last_slot == 8`
            //    （**batch 位置**），而字段存的是 **output 排位**（= 0）——
            //    于是自洽判据要求 0 == 8，恒假，**每一条嵌入都被拒**。
            //    正确口径：排位是 0（output 里只有 1 项），而它**换算回 batch
            //    位置**才是 8。判据只能按后者问，不能按排位比大小。
            ok(p.batch_last_slot == 0 && p.output_slot == 0,
               "  排位是 0（output 里只有我们置的 1 项）");
            ok(embedtext::output_slot_to_batch(p, p.output_slot) == 8,
               "  第 0 项换算回 batch 位置 == 8（== nTokens-1）");
            ok(embedtext::plan_is_coherent(p, 9), "  自洽");
            // 关键反例①：**库给的 output 变成整段**（譬如库自己补了 logits），
            // 而我们仍读第 0 项 —— 那时第 0 项是**第 0 个 token**（BOS），必须拒。
            auto bad = p; bad.want_logits.assign(9, 1);
            ok(!embedtext::plan_is_coherent(bad, 9),
               "  整段都进 output 却仍读第 0 项 -> 不自洽（读到的是 BOS 那一行）");
            // 关键反例②：读的排位越界（output 里没有这一项）—— 读取点会拿到 null。
            auto bad2 = p; bad2.output_slot = 5; bad2.batch_last_slot = 5;
            ok(!embedtext::plan_is_coherent(bad2, 9),
               "  读的排位越界 -> 不自洽（output 里没有第 5 项）");
            // 关键反例③：两个字段各指一个排位（读取点用其中一个，语义用另一个）。
            auto bad3 = p; bad3.batch_last_slot = 1;
            ok(!embedtext::plan_is_coherent(bad3, 9),
               "  output_slot 与 batch_last_slot 不一致 -> 不自洽");
            // 关键反例④：没给最后一个 token 置 logits（output 为空，库会断言崩）。
            auto bad4 = p; bad4.want_logits[8] = 0;
            ok(!embedtext::plan_is_coherent(bad4, 9),
               "  没给最后一位置 logits -> 自洽失败（output 为空时库会断言崩）");
        }
        // 换算函数本身：它把"output 排位"与"batch 位置"分成两个数 ——
        // 这是本 PR 的核心，单独钉住。
        {
            auto p = embedtext::plan_pooling(0, 5);      // NONE：只置最后一位
            ok(embedtext::output_slot_to_batch(p, 0) == 4,
               "排位 0 -> batch 位置 4（两者不等，正是上一版混掉的那一步）");
            ok(embedtext::output_slot_to_batch(p, 1) == -1,
               "排位 1 不存在（output 里只有 1 项）-> -1");
            ok(embedtext::output_slot_to_batch(p, -1) == -1, "负排位 -> -1");
            auto q = embedtext::plan_pooling(2, 5);      // CLS：整段
            ok(embedtext::output_slot_to_batch(q, 0) == 0 &&
               embedtext::output_slot_to_batch(q, 4) == 4,
               "整段都进 output 时，排位 == 位置（CLS 就是这一档）");
        }
        // ═══════════════════════════════════════════════════════════════════
        // 「库给 pooled buffer 的档」= {MEAN, CLS, LAST} —— 三档，不是两档。
        // ═══════════════════════════════════════════════════════════════════
        ok(embedtext::library_gives_seq(1) && embedtext::library_gives_seq(2) &&
           embedtext::library_gives_seq(3),
           "库给 pooled buffer 的档：MEAN(1) / CLS(2) / LAST(3)");
        ok(!embedtext::library_gives_seq(0) && !embedtext::library_gives_seq(-1),
           "库不给 pooled buffer 的档：NONE(0) / UNSPECIFIED(-1)");
        // ⚠ `normalize_pooling` **不再是** `library_gives_seq` 的背面 ——
        // 那个等式被证伪了：库对池化结果**不做 L2**（`build_pooling` 只在 RANK
        // 支过 `cls_norm`），做归一化的是 `cui-llama.rn` 的 RN 包装层，而本 App
        // 直接调 C API，不经过它。所以**每一档都由我们归一化**。
        for (int t : {0, 1, 2, 3, -1}) {
            ok(embedtext::normalize_pooling(t),
               std::string("  我们自己归一化（pooling=") + std::to_string(t) +
               "：库不做 L2，检索侧 cosine 是纯点积，前提是两边都归一化过）");
            auto p = embedtext::plan_pooling(t, 3);
            ok(p.read_all_rows == embedtext::library_gives_seq(t),
               "  read_all_rows 与 library_gives_seq 同源（同一处库条件的两个用法）");
            ok(p.pool_type_raw == t, "  原始 pool 值随计划走（LAST 的分岔靠它）");
        }
        // 不受支持的档也要**明确**（不是"碰巧为真"）：RANK 根本拿不到计划。
        ok(!embedtext::plan_pooling(4, 3).valid, "RANK 仍然拒绝，谈不上归一化口径");
        // read_from_seq 是"读取侧入口"，必须与 library_gives_seq 同源 ——
        // 它俩读的是同一条库前提，抄成两处就是本次这类漂移的回程票。
        for (int t : {0, 1, 2, 3, -1, 4}) {
            ok(embedtext::read_from_seq(t) == embedtext::library_gives_seq(t),
               std::string("  read_from_seq(") + std::to_string(t) +
               ") 与 library_gives_seq 同源");
        }
    }

    printf("== 组 6e：warm-up 的计划判据（本次修复的核心）==\n");
    {
        // ═══════════════════════════════════════════════════════════════════
        // 这一组守的是本 PR 的 bug：**warm-up 的 batch 必须与真实路径同形**。
        // ═══════════════════════════════════════════════════════════════════
        // 早先那版用 BOS 单 token、只要一行输出，于是：
        //   ① CLS（BGE 系）**无法通过库的前置门禁** —— `llama_batch_allocr::init`
        //      里那条 `pooled embedding requires that all tokens are output`
        //      在 release 里是 `abort()`，也就是**每次加载嵌入模型都进程级崩溃**；
        //   ② NONE/LAST 档库"替我们补"补的是**真实 input**（只有 BOS 一个），
        //      "补"完还是只有第 0 行 —— **每一行都被写过**这件事根本没做到，
        //      而日志说它做了（假绿）。
        //
        // 所以判据分三层，缺一层都拦不住上面两类：
        //   · 形状下限：`nTokens >= kWarmupMinTokens`（1 个 token 时"整段"= 第 0 行，
        //     与早先那版**等价** —— warm-up 白做）；
        //   · 给库的门禁：`logits_ok_for_library`（CLS/MEAN 整段；NONE/LAST 至少一个）；
        //   · "有没有真的写满"：`warmup_wrote_all_rows` —— **如实回答**，不假装。

        // ── ① 形状下限：1 个 token 必须判"不可用" ────────────────────────
        {
            auto p1 = embedtext::plan_pooling(2, 1);      // CLS + 1 token
            ok(p1.valid, "CLS(2)+1 token 本身是个有效计划（真前向能用）");
            ok(!embedtext::warmup_plan_usable(p1, 2, 1),
               "  但 warm-up 不能用它：1 个 token 时「整段」= 第 0 行，热身等于没做");
            auto p0 = embedtext::plan_pooling(0, 1);      // NONE + 1 token
            ok(!embedtext::warmup_plan_usable(p0, 0, 1),
               "NONE(0)+1 token 同样判不可用（退化点，与库档无关）");
            auto p2 = embedtext::plan_pooling(2, 2);      // CLS + 2 token
            ok(embedtext::warmup_plan_usable(p2, 2, 2),
               "CLS(2)+2 token 可用（跨过退化点，且整段进 output）");
        }

        // ── ② 给库的门禁：CLS 必须整段（早先那版在这里就该红）────────────
        {
            auto cls = embedtext::plan_pooling(2, 5);
            ok(embedtext::warmup_plan_usable(cls, 2, 5),
               "CLS(2)+5 token 可用 —— 计划把整段都置了 output（库的门禁满足）");
            ok(embedtext::warmup_wrote_all_rows(cls, 5),
               "  且**每一行**都被写过（CLS 的库契约就是整段）");
            // 反面：拿一个"只置最后一个"的 logits 冒充 CLS 的计划 ——
            // 这一条直接复刻早先那版在 CLS 上的形态。
            embedtext::PoolPlan fake = cls;
            fake.want_logits.assign(5, 0);
            fake.want_logits[4] = 1;
            ok(!embedtext::warmup_plan_usable(fake, 2, 5),
               "  ⚠ 只置一行的计划在 CLS 上**不可用**（早先那版正是这一形态 → 库 abort）");
            ok(!embedtext::warmup_wrote_all_rows(fake, 5),
               "  且如实报告「没写满」（不许假绿）");
        }

        // ── ③ "有没有写满"必须如实，不许假装 ─────────────────────────────
        {
            for (int t : {0, 3, -1}) {                    // NONE / LAST / UNSPECIFIED
                auto p = embedtext::plan_pooling(t, 5);
                ok(embedtext::warmup_plan_usable(p, t, 5),
                   std::string("pooling=") + std::to_string(t) +
                   " 的 warm-up 计划可用（库只要≥1 个 output）");
                ok(!embedtext::warmup_wrote_all_rows(p, 5),
                   "  但**写不满整段**（库契约如此）—— 判据必须如实说「没有」");
            }
            auto mean = embedtext::plan_pooling(1, 5);
            ok(embedtext::warmup_plan_usable(mean, 1, 5) && embedtext::warmup_wrote_all_rows(mean, 5),
               "MEAN(1)：可用 + 整段写满（库按整段平均，必须全进 output）");
        }

        // ── ④ 不支持的档一律不可用（与真实路径同一道闸）──────────────────
        {
            auto rank = embedtext::plan_pooling(4, 5);
            ok(!rank.valid && !embedtext::warmup_plan_usable(rank, 4, 5),
               "RANK(4)：计划本身就无效，warm-up 也不许用（不猜）");
            auto unknown = embedtext::plan_pooling(999, 5);
            ok(!embedtext::warmup_plan_usable(unknown, 999, 5),
               "未知 pooling：warm-up 不许用");
            // 长度错位 = 判据失效，必须拒绝（否则"没检查"会被读成"检查过了"）。
            auto good = embedtext::plan_pooling(2, 5);
            ok(!embedtext::warmup_plan_usable(good, 2, 4),
               "nTokens 与 want_logits 长度不一致 -> 拒绝（防止置位越界）");
            ok(!embedtext::warmup_wrote_all_rows(good, 4),
               "  写满判据同样拒绝长度错位");
            ok(!embedtext::warmup_plan_usable(good, 2, 0) && !embedtext::warmup_wrote_all_rows(good, 0),
               "0 token -> 两条判据都拒绝");
        }

        // ── ⑤ 与真实路径**同一套**计划（不许另立一套置位规则）────────────
        // 这一条把"warm-up 与真实路径同形"钉成结构关系：凡是 warm-up 可用的
        // 形态，它的 `want_logits` 都必须**就是** `plan_pooling` 的产物。
        for (int t : {0, 1, 2, 3, -1}) {
            for (int n : {2, 3, 8}) {
                auto p = embedtext::plan_pooling(t, n);
                ok(embedtext::warmup_plan_usable(p, t, n),
                   std::string("  plan_pooling(") + std::to_string(t) + "," + std::to_string(n) +
                   ") 的产物天然可用于 warm-up（同一套置位规则）");
            }
        }
    }

    printf("== 组 7：范数上报（方案 C 的数值依据）==\n");
    {
        // `norm=0`（真零向量）与 `norm=1e-38`（下溢）是**两种病**，
        // 只报"失败"时它们长得一样 —— 所以 norm_of 必须报实值。
        float z[] = {0.0f, 0.0f, 0.0f};
        ok(embedtext::norm_of(z, 3) == 0.0, "全零 -> 0（真零向量）");
        float tiny[] = {1e-20f, 1e-20f};
        double nt = embedtext::norm_of(tiny, 2);
        ok(nt > 0.0 && nt < 1e-19, "极小范数被如实报出（不是被当成 0）");
        float v[] = {3.0f, 4.0f};
        ok(near((float) embedtext::norm_of(v, 2), 5.0f, 1e-4f), "3,4 -> 范数 5");
        float nanv[] = {NAN, 1.0f};
        ok(!std::isfinite(embedtext::norm_of(nanv, 2)), "NaN 如实反映（isfinite 为假）");
        ok(embedtext::norm_of(nullptr, 3) == 0.0, "null -> 0，不许崩");
        ok(embedtext::norm_of(v, 0) == 0.0, "长度 0 -> 0");
    }

    printf("== 组 8：失败原因名（进日志与 meta.json，必须稳定）==\n");
    {
        // 这些字符串会被写进 `meta.json` 的 failReason 并被守卫断言 ——
        // 改名等于让历史索引里的原因码对不上，所以必须是稳定的英文短名。
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::DIM_MISMATCH))
             == "dim_mismatch", "DIM_MISMATCH -> dim_mismatch");
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::ZERO_NORM))
             == "zero_norm", "ZERO_NORM -> zero_norm");
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::NO_OUTPUT))
             == "no_output", "NO_OUTPUT -> no_output");
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::DECODE_FAILED))
             == "decode_failed", "DECODE_FAILED -> decode_failed");
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::BAD_INPUT))
             == "bad_input", "BAD_INPUT -> bad_input");
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::TRUNCATED_EMPTY))
             == "truncated_empty", "TRUNCATED_EMPTY -> truncated_empty");
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::POOLING_CONTRACT))
             == "pooling_contract",
             "POOLING_CONTRACT -> pooling_contract（本轮新增：违反库的池化前置要求）");
        ok(std::string(embedtext::failure_name(embedtext::EmbedFailure::NONE))
             == "none", "NONE -> none（空串不是失败详情）");
        ok(std::string(embedtext::failure_name(-12345)) == "none", "未知码 -> none（不崩）");
    }

    printf("== 组 9：合批计划（ISSUE #269 的另一半）==\n");
    {
        // ① 基本形态：三条长度 3/5/2 -> 段首尾相接、seq 从 0 起、pos 各自从 0 起。
        //    `pos` 写成整批全局下标时 RoPE 一转，向量变成"另一条文本在另一段的
        //    语义"，而长度 / 维度 / 归一化全对 —— 所以这三样逐项钉。
        {
            std::vector<int> c{3, 5, 2};
            embedtext::BatchPlan p = embedtext::plan_batch(0, c, 8);
            ok(p.valid, "pooling=NONE 三条可以合批");
            ok(p.n_tokens == 10 && p.n_rows == 3, "整批 token=10 / 条数=3");
            ok(embedtext::batch_is_valid(p, 0), "计划自洽");
            ok(embedtext::batch_output_layout_ok(p), "output 布局是『每条一个』");
            const int wantSeq[10] = {0,0,0,1,1,1,1,1,2,2};
            const int wantPos[10] = {0,1,2,0,1,2,3,4,0,1};
            bool seqOk = true, posOk = true, oneOk = true;
            for (int i = 0; i < 10; i++) {
                if (p.seq_of[(size_t) i] != wantSeq[i]) seqOk = false;
                if (p.pos_of[(size_t) i] != wantPos[i]) posOk = false;
            }
            int ones = 0;
            for (char ch : p.want_logits) if (ch) ones++;
            if (ones != 3) oneOk = false;
            ok(seqOk, "seq_of 逐项 = {0,0,0,1,1,1,1,1,2,2}（隔离靠它）");
            ok(posOk, "pos_of 是**seq 内**位置（不是整批全局下标）");
            ok(oneOk, "整批恰好 3 个 output（每条一个 last-token）");
            // 每个 seq 的 pos 必须从 0 起 —— 单独钉一遍（上面那串是硬编码的期望）
            bool fromZero = true;
            for (const auto & r : p.rows) if (p.pos_of[(size_t) r.begin] != 0) fromZero = false;
            ok(fromZero, "每条的第一个 token 的 pos 都是 0");
        }

        // ② ⚠⚠ 这一组的**方向在 0.9.208 反过来了**，而它是本轮最要紧的一组。
        //
        //    上一版这里断言 "MEAN/CLS/LAST 不合批"（那时 `embd_seq` 多 seq 下的
        //    布局没验过，不放行）。这一轮验过了：那三档的向量**每个 seq 一行**，
        //    第 j 条读 `get_embeddings_seq(ctx, j)` —— 与单条路径同一个 API。
        //    于是**每一档都能合批**，而 BGE（CLS）/ Qwen3-Embedding（LAST）
        //    这两个用户手上的模型才第一次真的拿到合批收益。
        //
        //    ⚠ 断言写成"**五档全都能合批**"而不是"MEAN/CLS/LAST 能合批"：
        //    前者把 NONE/UNSPECIFIED 也一起钉住 —— 撤闸时最容易出的错是
        //    "补上了新的、弄丢了旧的"（补 MEAN/CLS/LAST 时把 NONE 也挡了），
        //    而那个方向的症状与原来一样（合批一次都没生效）。
        //    唯一仍须为假的是 `plan_pooling` 本来就不认的档（RANK(4) / 未知）——
        //    它们是另一件事（我们没有那种向量），不是"没验过"。
        {
            std::vector<int> c{3, 5};
            ok(embedtext::batchable_pooling(0), "NONE(0) 合批");
            ok(embedtext::batchable_pooling(1), "MEAN(1) 合批（0.9.208：embd_seq 每 seq 一行，已验）");
            ok(embedtext::batchable_pooling(2), "CLS(2) 合批（BGE 系就是这一档）");
            ok(embedtext::batchable_pooling(3), "LAST(3) 合批（Qwen3-Embedding 就是这一档）");
            ok(embedtext::batchable_pooling(-1), "UNSPECIFIED(-1) 合批（按 last-token 处理）");
            ok(!embedtext::batchable_pooling(4), "RANK(4) 仍不合批（我们没有那种向量，与合批无关）");
            ok(!embedtext::batchable_pooling(99), "未知档仍不合批（不猜）");
            // 计划本身也要成立 —— 只放行 `batchable_pooling` 而 `plan_batch`
            // 里还有一处 `continue` 的话，"判据说能合批、循环里全在回落"。
            for (int t : {0, 1, 2, 3, -1}) {
                embedtext::BatchPlan p = embedtext::plan_batch(t, c, 8);
                ok(p.valid && p.n_rows == 2, "plan_batch 对 pooling=" + std::to_string(t) + " 成立");
                ok(embedtext::batch_is_valid(p, t),
                   "  pooling=" + std::to_string(t) + " 的计划自洽");
            }
            ok(!embedtext::plan_batch(4, c, 8).valid, "RANK(4) 的 plan_batch 必须 invalid");
        }

        // ②b 池化向量的**行号**：合批里第 j 条该读 `embd_seq` 的第几行。
        //    这条判据的存在理由：单条路径那里实参写死 0（因为只有一个 seq），
        //    合批必须把 seq 号传进去 —— 传错了症状是"8 条文本共用同一段语义"
        //    （或全零），而长度、维度、归一化**全对**，检索结果像是随机。
        {
            // 行号 == seq 号（由 plan_batch 保证 `BatchRow::seq == j`）
            ok(embedtext::pooled_vector_slot(0) == 0, "第 0 条读第 0 行");
            ok(embedtext::pooled_vector_slot(7) == 7, "第 7 条读第 7 行（不是恒 0）");
            // 与计划里的 seq 对得上：逐条要求 rows[j].seq == j，取的行号就是它
            embedtext::BatchPlan p = embedtext::plan_batch(2, {3, 5, 2}, 8);
            bool aligned = p.valid;
            for (int j = 0; j < p.n_rows && aligned; j++) {
                if (p.rows[(size_t) j].seq != embedtext::pooled_vector_slot(j)) aligned = false;
            }
            ok(aligned, "每条的取值行号 == 它在计划里的 seq（隔离与取值同源）");
            // 越界判据：批里有 n 行，取第 n 行是读**别的批**
            ok(embedtext::pooled_slot_is_row(0, 3), "0 在 3 行之内");
            ok(embedtext::pooled_slot_is_row(2, 3), "2 在 3 行之内（末行也算）");
            ok(!embedtext::pooled_slot_is_row(3, 3), "3 越界（只有 0..2）");
            ok(!embedtext::pooled_slot_is_row(-1, 3), "负数越界");
            ok(!embedtext::pooled_slot_is_row(0, 0), "空批没有任何行可读");
            // 逐条对照：计划里每一行的取值下标都必须过这道判据
            //（写反的话上面那几条还能单独绿，而线上每条都越界）
            bool allInRange = p.valid;
            for (int j = 0; j < p.n_rows && allInRange; j++) {
                if (!embedtext::pooled_slot_is_row(embedtext::pooled_vector_slot(j), p.n_rows))
                    allInRange = false;
            }
            ok(allInRange, "计划里每一行的取值下标都能过越界判据（不是纸面上的判据）");
        }

        // ③ 边界：空批 / 含 0 token / 超 seq 容量 —— 一律 invalid（调用方回落逐条，
        //    而不是"丢掉那几条"或"越界填 seq_id"）。
        {
            ok(!embedtext::plan_batch(0, {}, 8).valid, "空批 -> invalid");
            ok(!embedtext::plan_batch(0, {3, 0, 2}, 8).valid, "含 0 token -> invalid");
            ok(!embedtext::plan_batch(0, {1,1,1,1,1,1,1,1,1}, 8).valid, "9 条 > seqCap=8 -> invalid");
            ok(embedtext::plan_batch(0, {1,1,1,1,1,1,1,1}, 8).valid, "恰好 8 条可以（边界不算超）");
            ok(embedtext::plan_batch(0, {1,1}, 0).valid, "seqCap<=0 视为不限 -> 可以");
        }

        // ④ 单条合批也要成立（批大小 1 时不能退化成 invalid —— 那会让
        //    "最后一批只有 1 条"的场景凭空回落逐条）。
        {
            std::vector<int> c{7};
            embedtext::BatchPlan p = embedtext::plan_batch(0, c, 8);
            ok(p.valid && p.n_rows == 1 && p.n_tokens == 7, "单条批成立（3/5/2 的尾巴场景）");
            ok(embedtext::batch_is_valid(p, 0), "单条批自洽");
            ok(p.pos_of[0] == 0 && p.pos_of[6] == 6, "单条批的 pos 从 0 连续");
            ok(p.want_logits[6] == 1 && p.want_logits[0] == 0, "单条批只要最后一个 token");
        }

        // ⑤ 篡改判据：把某条的 pos 改成全局下标 / 把 seq 抄错 —— `batch_is_valid`
        //    必须红（否则这道自检是恒真的，与没有等价）。
        {
            std::vector<int> c{3, 2};
            embedtext::BatchPlan p = embedtext::plan_batch(0, c, 8);
            ok(embedtext::batch_is_valid(p, 0), "（篡改前）自洽");
            p.pos_of[3] = 3;                      // 第 2 条第一个 token 写成全局下标
            ok(!embedtext::batch_is_valid(p, 0), "pos 被改成全局下标 -> 判据必须红");
            embedtext::BatchPlan q = embedtext::plan_batch(0, c, 8);
            q.seq_of[4] = 0;                      // 第 2 条的第二个 token 归错 seq
            ok(!embedtext::batch_is_valid(q, 0), "seq 抄错 -> 判据必须红");
            embedtext::BatchPlan r = embedtext::plan_batch(0, c, 8);
            r.n_rows = 3;                         // 行数与 rows 不符
            ok(!embedtext::batch_is_valid(r, 0), "行数与 rows 不符 -> 判据必须红");
        }
    }

    printf("== 组 10：整批容量（n_batch）—— 真机闪退的那一条 ==\n");
    {
        // ═══════════════════════════════════════════════════════════════════
        // 这一组钉的是 0.9.209 修的**进程级 abort**：
        //   LM_GGML_ASSERT(n_tokens_all <= cparams.n_batch) failed
        // 它的失效形态不是"这一批失败"，而是**整个进程消失**（建索引闪退），
        // 所以判据必须能离线跑、能逐项对照 —— 不能只靠真机上看"崩不崩"。
        // ═══════════════════════════════════════════════════════════════════

        // ① 装得下 / 装不下 / 贴边 —— 判据是 `<=`（库的断言也是 `<=`）。
        ok(embedtext::batch_fits_n_batch(2048, 2048), "恰好等于 n_batch 装得下（库的断言是 <=）");
        ok(!embedtext::batch_fits_n_batch(2049, 2048), "超一个就装不下（这一个就是 abort）");
        ok(embedtext::batch_fits_n_batch(1, 2048), "单 token 当然装得下");
        ok(!embedtext::batch_fits_n_batch(0, 2048), "0 token 的批不是『装得下』（空批没有意义）");
        ok(embedtext::batch_fits_n_batch(9999, 0), "n_batch 取不到（<=0）-> 不拦（宁可让库报它自己的错）");

        // ② ⚠⚠ 真机那一批的**具体数值**：8 条 × 511 token = 4088 > 2048。
        //    这一条是"为什么它必崩"的最小复现 —— 不是"差不多会崩"。
        {
            const int perSeq = 512;              // kEmbedMaxCtx / kEmbedBatchRows = 4096/8
            const int cap = 2048;                // llama.cpp 的 n_batch 默认值
            ok(8 * (perSeq - 1) == 4088, "8 条各 511 token = 4088（真机那一批的大小）");
            ok(8 * (perSeq - 1) > cap, "4088 > 2048 —— 这一批**必然**撞上库的断言");
            // 单条路径一次最多 511 个 token，与 2048 无关 —— 所以合批之前不崩。
            ok(perSeq - 1 <= cap, "单条路径的 511 个 token 与 n_batch 无关（所以合批之前从来不崩）");
        }

        // ③ 切段：装得下时**原样**（一次提交，合批的收益就是这一次）；
        //    装不下时按**整条**切，且切出来的那一段自己必须装得下。
        {
            std::vector<int> c{3, 5, 2};
            ok(embedtext::batch_chunk_rows(c, 10) == 3, "整批恰好 10 -> 全部一次提交");
            ok(embedtext::batch_chunk_rows(c, 9) == 2, "成了 9 -> 只能先提交前两条（3+5）");
            ok(embedtext::batch_chunk_rows(c, 8) == 2, "8 -> 前两条（3+5=8，恰好贴边）");
            ok(embedtext::batch_chunk_rows(c, 7) == 1, "7 -> 只有第 1 条（3；再加 5 就 8 > 7）");
            // ⚠ 反面：`{3,5,2}` 里 5 自己 > 3 —— 这时**整批**返回 0，不是"只提交 3"。
            // 判据是"批里任意一条超容量 -> 整批无解"，因为切段只按整条切、
            // 不许把 5 那条跳过去（跳过去 = 那一条的正文会被静默丢掉）。
            ok(embedtext::batch_chunk_rows(c, 3) == 0, "3 -> 0（5 自己就超，不许跳过它）");
            ok(embedtext::batch_chunk_rows(c, 0) == 3, "n_batch 取不到 -> 不切（与上面同一条口径）");
            ok(embedtext::batch_chunk_rows({}, 8) == 0, "空批 -> 0 条");
        }
        // ④ ⚠ 切出来的那一段**自己**必须装得下 —— 逐项对照（写反 min/max 时
        //    ③ 里那几条可能单独绿，而线上每一段都越界）。
        {
            std::vector<int> c{7, 7, 7, 7};
            for (int cap = 1; cap <= 30; cap++) {
                const int k = embedtext::batch_chunk_rows(c, cap);
                if (k <= 0) continue;
                int sum = 0;
                for (int j = 0; j < k; j++) sum += c[(size_t) j];
                if (sum > cap) { ok(false, "切出来的那一段自己就超容量（判据写反了）"); break; }
                // 再多加一条就必须超 —— 否则它切得太保守（合批收益白白变小）
                if (k < (int) c.size() && sum + c[(size_t) k] <= cap) {
                    ok(false, "还能多装一条却提前切了（切得过于保守）"); break;
                }
            }
            ok(true, "切段逐项对照：每一段自己装得下，且再多一条就装不下");
        }
        // ⑤ 单条自己就超容量 -> 返回 0（调用方据此**回到单条路径**，
        //    单条路径一条一次 decode，不受整批容量约束）。
        {
            ok(embedtext::batch_chunk_rows({3000}, 2048) == 0,
               "单条 3000 > 2048 -> 0（合批对它无解，回到单条路径）");
            ok(embedtext::batch_chunk_rows({3, 3000, 2}, 2048) == 0,
               "批里**任意**一条超容量 -> 整批 0（不许悄悄跳过它）");
            // 反面：单条恰好等于容量时是能提交的（不该被误判成"超"）
            ok(embedtext::batch_chunk_rows({2048}, 2048) == 1,
               "单条恰好等于 n_batch -> 能提交（<= 不是 <）");
        }

        // ═══════════════════════════════════════════════════════════════════
        // 这一组钉 0.9.213 收口的那条不变式：
        //   `kEmbedBatchRows × 每条预算(截断后) ≤ 两道门`
        // 它此前**没有任何一处代码在管** —— 只活在两个常量相等的巧合里
        //（`kEmbedMaxCtx`(4096) 恰等于 `kEmbedGate`(4096)），改任一个都会破，
        // 而破的形态是 `abort()`（"建索引闪退"）。`batch_full_rows_fit` 是
        // 它的**读数**：这一档最多能一次装几条满长。
        // ═══════════════════════════════════════════════════════════════════

        // ⑥ 真机那一档：门 4096、每条预算 512 -> 满长 = 511，4096/511 = 8。
        //    恰好多一条（9 × 511 = 4599 > 4096）就装不下 —— 这就是"余量只有 8 格"
        //    的**算出来的**说法（此前只能靠读日志的人自己推）。
        {
            ok(embedtext::batch_full_rows_fit(4096, 512) == 8,
               "门 4096 / 每条 512 -> 8 条满长（4096/511 = 8）");
            ok(8 * (512 - 1) <= 4096, "8 条满长 = 4088 ≤ 4096 —— 这一档真的装得下");
            ok(9 * (512 - 1) > 4096, "9 条满长 = 4599 > 4096 —— 再多一条就装不下");
            // 反面：若有人把 kEmbedMaxCtx 独自提到 8192 而门仍是 4096，
            // 每条预算会涨到 1024（ctx/8），此时 8 条满长 = 8×1023 = 8184 > 4096 ——
            // 而这一档的读数会当场掉到 4，让"该切段了"看得见。
            ok(embedtext::batch_full_rows_fit(4096, 1024) == 4,
               "门 4096 / 每条 1024 -> 只装得下 4 条满长（不是 8）—— 读数是护栏");
        }

        // ⑦ "满长"取 `perSeq - 1`（= 截断后的真实上限），不是 `perSeq`。
        //    取 `perSeq` 会**多报**：门 4096、每条 512 时算出 4096/512 = 8，
        //    看着一样 —— 但每条 511 是 4088，4096/512 恰好整除会掩盖"余量 8 格"
        //    这件事；换一组数就露出来了（下面这一对）。
        {
            // 门 1024、每条预算 512 -> 满长 511，1024/511 = 2（而 1024/512 = 2，同值）
            ok(embedtext::batch_full_rows_fit(1024, 512) == 2, "门 1024 / 每条 512 -> 2 条满长");
            // 门 1535、每条 512 -> 满长 511，1535/511 = 3（而 1535/512 = 2 —— 少报一条）
            ok(embedtext::batch_full_rows_fit(1535, 512) == 3,
               "门 1535 / 每条 512 -> 3 条满长（用 perSeq 算会少报成 2）");
            ok(3 * (512 - 1) <= 1535 && 4 * (512 - 1) > 1535, "3 条装得下、4 条装不下 —— 与返回值一致");
        }

        // ⑧ 取不到 / 退化输入 -> 0（"这个数算不出来"，调用方据此**不预警**）。
        //    0 不等于"安全"：它只是没有判据可依 —— 与 `batch_fits_n_batch` 的
        //    `nBatch <= 0` 放行同一条口径（宁可让库报它自己的错）。
        {
            ok(embedtext::batch_full_rows_fit(0, 512) == 0, "门取不到（0）-> 0（算不出来）");
            ok(embedtext::batch_full_rows_fit(-1, 512) == 0, "门是负数 -> 0");
            ok(embedtext::batch_full_rows_fit(4096, 1) == 0, "每条预算 1 -> 0（满长退化成 0 格）");
            ok(embedtext::batch_full_rows_fit(4096, 0) == 0, "每条预算 0 -> 0");
            ok(embedtext::batch_full_rows_fit(1, 2) == 1, "门 1 / 每条 2 -> 满长 1，装得下 1 条");
            ok(embedtext::batch_full_rows_fit(4095, 512) == 8,
               "门 4095 / 每条 512 -> 仍是 8（4095/511 = 8.01 向下取整，8×511=4088 ≤ 4095）");
            // ⚠ 反面：门再小一格（4087）就只装得下 7 条 —— 而"按 512 算"会算出 4087/512 = 7，
            //    看着一样，但那是巧合。换一组（门 1535）就露出来：按 511 算是 3，
            //    按 512 算是 2 —— 少报一条会让真机撞 abort 的那批被判"装得下"。
            ok(embedtext::batch_full_rows_fit(4087, 512) == 7,
               "门 4087 / 每条 512 -> 7 条（8 条满长 4088 > 4087，装不下）");
        }

        // ═══════════════════════════════════════════════════════════════════
        // 这一组钉建索引**状态行**上那个"已耗时"的格式（`format_elapsed`）。
        // 它与 Kotlin 侧 `RagProgress.elapsed` 是**同一条口径**的两份实现
        //（一个在 C++ 头、一个在 Kotlin），所以两边的边界值集合必须逐项相同 ——
        // 漂移的形态是"日志里 1:23、界面上 1:5"，两者都"没错"，只是说的不是
        // 同一件事，而这种漂移没有任何迹象。
        // ═══════════════════════════════════════════════════════════════════

        // ⑨ 秒位**必须**补零：`1:5` 会被读成"1 分 5 秒"与"1 分 50 秒"分不清，
        //    而这一行每 250 ms 换一次、要挂几十分钟 —— 位宽抖动就是噪声。
        {
            char b[32];
            embedtext::format_elapsed(b, sizeof(b), 0);
            ok(std::string(b) == "0:00", "0 ms -> 0:00");
            embedtext::format_elapsed(b, sizeof(b), 5000);
            ok(std::string(b) == "0:05", "5 秒 -> 0:05（秒位补零）");
            embedtext::format_elapsed(b, sizeof(b), 65000);
            ok(std::string(b) == "1:05", "65 秒 -> 1:05（**不是** 1:5）");
            embedtext::format_elapsed(b, sizeof(b), 59000);
            ok(std::string(b) == "0:59", "59 秒 -> 0:59");
            embedtext::format_elapsed(b, sizeof(b), 60000);
            ok(std::string(b) == "1:00", "60 秒 -> 1:00（进位边界）");
        }

        // ⑩ 小时档：≥1 小时才出现，且分/秒**两个**都补零（`1:01:01` 不是 `1:1:1`）。
        {
            char b[32];
            embedtext::format_elapsed(b, sizeof(b), 3599000);
            ok(std::string(b) == "59:59", "3599 秒 -> 59:59（小时档下沿）");
            embedtext::format_elapsed(b, sizeof(b), 3600000);
            ok(std::string(b) == "1:00:00", "3600 秒 -> 1:00:00（小时档上沿）");
            embedtext::format_elapsed(b, sizeof(b), 3661000);
            ok(std::string(b) == "1:01:01", "3661 秒 -> 1:01:01（时/分/秒都进位）");
            embedtext::format_elapsed(b, sizeof(b), 10000000);
            ok(std::string(b) == "2:46:40", "10000 秒 -> 2:46:40（小时档）");
        }

        // ⑪ 负数（墙钟回跳 / 时钟被拉歪）：按 0 处理，**不许**出现负时长。
        {
            char b[32];
            embedtext::format_elapsed(b, sizeof(b), -5000);
            ok(std::string(b) == "0:00", "负数 -> 0:00（不出现 -0:05）");
            embedtext::format_elapsed(b, sizeof(b), -1);
            ok(std::string(b).find('-') == std::string::npos, "任何输入都不出现负号");
        }

        // ⑫ 缓冲区退化输入：不许越界写（cap 为 0 / 指针为空时**直接返回**）。
        {
            char b[4];
            embedtext::format_elapsed(b, sizeof(b), 3661000);
            ok(std::string(b) == "1:0", "cap 不够 -> 截断而不是越界写（snprintf 语义）");
            embedtext::format_elapsed(nullptr, 8, 1000);   // 不许崩
            embedtext::format_elapsed(b, 0, 1000);         // 不许崩
            ok(true, "指针为空 / cap 为 0 时直接返回（不崩、不写）");
        }
    }

    printf("\n=== embed_text: PASS %d / FAIL %d ===\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
