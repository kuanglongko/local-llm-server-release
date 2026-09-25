// kv_prefix_test.cpp — KV 前缀复用切分语义的宿主侧单测
//
// 与 llama_jni.cpp **共用同一份** app/src/main/cpp/kv_prefix.h（不是重抄 ——
// 重抄就等于没测）：这里绿了，才代表线上算出的「留几个 / 算几个」是对的。
//
// 专钉三类**没有异常、没有日志、也不崩溃**的故障：
//   1. 多留一段（切在 token 中间 / 位置对不上）-> 模型读到错位历史，
//      表现为「答非所问」「重复上一轮结尾」，排查时会被归因成"模型就这德行"；
//   2. 少留一段 -> 只是慢，效果静默消失，谁都看不出"本该更快"；
//   3. 整段当已缓存（reuse == total）-> 采样步在没有任何 logits 的上下文上取值。
#include "kv_prefix.h"

#include <cstdio>
#include <string>
#include <vector>

static int g_pass = 0, g_fail = 0;

static void ok(bool cond, const std::string & what) {
    if (cond) { g_pass++; printf("  ok   %s\n", what.c_str()); }
    else      { g_fail++; printf("  FAIL %s\n", what.c_str()); }
}

using V = std::vector<long long>;

// 造一段"像真实 prompt"的序列：起点 1000，长度 n，步长 1。
static V seq(size_t n, long long base = 1000) {
    V v;
    for (size_t i = 0; i < n; i++) v.push_back(base + (long long) i);
    return v;
}
// 在前缀之后接一段新 token（模拟"多了一轮对话"）。
static V cat(const V & a, size_t n, long long base = 90000) {
    V v = a;
    for (size_t i = 0; i < n; i++) v.push_back(base + (long long) i);
    return v;
}

int main() {
    printf("== 组 1：最长公共前缀（逐 token，不做任何「可逆 tokenize」假设）==\n");
    {
        ok(kvprefix::common_prefix_len(seq(10), seq(10)) == 10, "完全相同 -> 全长");
        ok(kvprefix::common_prefix_len(seq(10), cat(seq(10), 5)) == 10, "一个含另一个 -> 短的那个全长");
        ok(kvprefix::common_prefix_len(seq(40), seq(3)) == 3, "公共前缀取较短长度");
        ok(kvprefix::common_prefix_len(V{}, seq(5)) == 0, "空 vs 非空 -> 0");
        ok(kvprefix::common_prefix_len(V{}, V{}) == 0, "空 vs 空 -> 0");
        V a = seq(5); V b = seq(5); b[3] = 777;
        ok(kvprefix::common_prefix_len(a, b) == 3, "第 4 个 token 不同 -> 3");
        // 关键：公共前缀必须是**位置对齐**的前缀，不能"跳过不同的中间那一段继续比"。
        V x{1, 2, 3, 4, 5, 6};
        V y{1, 2, 9, 9, 5, 6};
        ok(kvprefix::common_prefix_len(x, y) == 2, "中间分叉 -> 只取到分叉点（不允许跳过）");
        // 逐 token 而非逐字节：两个序列"字节上像"但 token 化不同，必须判成不同。
        V t1{100, 200, 300}; V t2{100, 201, 300};
        ok(kvprefix::common_prefix_len(t1, t2) == 1, "逐 token 比较（不按字符串/字节近似）");
    }

    printf("== 组 2：门槛 —— 短公共前缀不值得复用（净亏 + 假命中）==\n");
    {
        const int K = kvprefix::kMinReuseTokens;
        // 上一轮 30 tok，本轮共享前 K-1 个后分叉：不达门槛 -> 不复用
        V prev = seq(30);
        V cur = cat(seq(K - 1), 10);
        auto p = kvprefix::plan_reuse(prev, cur, true, 4096);
        ok(p.reuse == 0, "共享 " + std::to_string(K - 1) + " tok < 门槛 -> 不复用");
        ok(p.to_decode == (int) cur.size(), "不复用时 to_decode == total（全量 prefill）");
        // 恰好等于门槛：可以复用
        V cur2 = cat(seq(K), 10);
        auto p2 = kvprefix::plan_reuse(prev, cur2, true, 4096);
        ok(p2.reuse == K, "共享恰好 " + std::to_string(K) + " tok == 门槛 -> 复用");
        ok(p2.to_decode == (int) cur2.size() - K, "to_decode = total - reuse");
        ok(p2.total == (int) cur2.size(), "total 恒为本轮 token 数");
    }

    printf("== 组 3：账本无效一律不复用（宁可慢，不可读错历史）==\n");
    {
        V prev = seq(200);
        V cur = cat(seq(200), 20);
        auto p = kvprefix::plan_reuse(prev, cur, /*prev_valid=*/true, 4096);
        ok(p.reuse == 200 && p.to_decode == 20, "账本有效 -> 复用满额 200");
        auto q = kvprefix::plan_reuse(prev, cur, /*prev_valid=*/false, 4096);
        ok(q.reuse == 0, "账本无效 -> 不复用（换模型/卸载/解码失败后的必然后果）");
        ok(q.to_decode == (int) cur.size(), "账本无效 -> 全量 prefill");
        // 空账本同上
        auto r = kvprefix::plan_reuse(V{}, cur, true, 4096);
        ok(r.reuse == 0, "空账本 -> 不复用（首轮请求）");
    }

    printf("== 组 4：绝不允许 reuse == total（采样必须有一个 logits）==\n");
    {
        // 本轮 prompt 恰好是上一轮 token 序列的**真前缀**：公共前缀 == 本轮全长。
        V prev = seq(300);                 // 上一轮很长（含生成出来的回复）
        V cur  = seq(200);                 // 本轮 prompt 反而是它的一段前缀
        auto p = kvprefix::plan_reuse(prev, cur, true, 4096);
        ok(p.reuse == 199, "公共前缀 == 本轮全长时，reuse 必须少 1（留最后一个 token 给 prefill）");
        ok(p.to_decode == 1, "to_decode 至少为 1");
        // 等长同序列
        V same = seq(200);
        auto q = kvprefix::plan_reuse(same, same, true, 4096);
        ok(q.reuse == 199 && q.to_decode == 1, "完全相同序列 -> reuse = total - 1");
        // 短于门槛的"完全相同"应彻底不复用（reuse 会掉到门槛以下）
        V tiny = seq(5);
        auto r = kvprefix::plan_reuse(tiny, tiny, true, 4096);
        ok(r.reuse == 0, "极短序列即使完全相同也不复用（省不下东西）");
    }

    printf("== 组 5：prompt 超长（>= n_ctx）必须失败得干净 ==\n");
    {
        V prev = seq(100);
        V cur  = cat(seq(100), 5000);
        auto p = kvprefix::plan_reuse(prev, cur, true, 4096);
        ok(p.reuse == 0, "total >= n_ctx -> 不复用（本轮注定失败，不许把缓存改坏）");
        ok(p.to_decode == (int) cur.size(), "超长 -> 全量（调用方随后按 prompt 过长失败）");
        // 恰好等于 n_ctx
        V exact = seq(4096);
        auto q = kvprefix::plan_reuse(seq(4096), exact, true, 4096);
        ok(q.reuse == 0, "total == n_ctx -> 同样不复用");
        // n_ctx 未知（0）时不做这层拦截，但 reuse 仍受 total-1 约束
        V big = cat(seq(100), 5000);
        auto r = kvprefix::plan_reuse(seq(100), big, true, 0);
        ok(r.reuse == 100 && r.to_decode == (int) big.size() - 100, "n_ctx 未知时不做超长拦截（约束 ④ 仍生效）");
    }

    printf("== 组 6：复用不改变「要算的 token 总数」（守恒）==\n");
    {
        const char * cases[] = {"短前缀", "长前缀", "完全相同", "中间分叉"};
        V prevs[4] = { seq(20), seq(500), seq(300), seq(300) };
        V curs[4]  = { cat(seq(20), 5), cat(seq(500), 888), seq(300), cat(seq(7), 50) };
        for (int i = 0; i < 4; i++) {
            auto p = kvprefix::plan_reuse(prevs[i], curs[i], true, 8192);
            ok(p.reuse + p.to_decode == p.total,
               std::string(cases[i]) + "：reuse + to_decode == total（守恒）");
            ok(p.reuse >= 0 && p.to_decode >= 1, std::string(cases[i]) + "：to_decode 恒 >= 1");
            ok(p.reuse <= (int) curs[i].size(), std::string(cases[i]) + "：reuse 不越界");
        }
    }

    printf("== 组 7：命中判据只认 reuse > 0（日志/health 的口径）==\n");
    {
        V prev = seq(100), cur = cat(seq(100), 10);
        ok(kvprefix::plan_reuse(prev, cur, true, 4096).hit(), "复用 > 0 -> hit");
        ok(!kvprefix::plan_reuse(prev, cur, false, 4096).hit(), "不复用 -> 不 hit");
        ok(!kvprefix::plan_reuse(V{}, V{}, true, 4096).hit(), "空 -> 不 hit");
    }

    printf("== 组 8：describe 不得把「未命中」说成命中（日志是唯一可观测面）==\n");
    {
        V prev = seq(100), cur = cat(seq(100), 10);
        auto hit = kvprefix::describe(kvprefix::plan_reuse(prev, cur, true, 4096));
        auto miss = kvprefix::describe(kvprefix::plan_reuse(prev, cur, false, 4096));
        ok(hit.find("命中") != std::string::npos, "命中描述含「命中」");
        ok(hit.find("复用 100") != std::string::npos, "命中描述带上复用 token 数");
        ok(miss.find("未命中") != std::string::npos, "未命中描述含「未命中」");
        ok(miss.find("复用 100") == std::string::npos, "未命中描述不许出现复用量");
    }

    printf("\n%s：PASS %d / FAIL %d\n", g_fail == 0 ? "PASS" : "FAIL", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
