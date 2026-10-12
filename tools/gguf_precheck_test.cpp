// GGUF 预读前置检查的宿主单测。
//
// 为什么这份单测值钱：它跑的**不是**复刻品，而是真机编进去的同一份
// `app/src/main/cpp/gguf_precheck.h`（用 -I 直接 include 仓库里的原件）。
// 「另写一份近似去验证另一份近似」正是判据漂移的发生方式。
//
// 判据的必要性（Issue #238 真机现场）：nativeProbeGguf 把整份 GGUF 元数据
// （含 151936 条词表）交给库去 malloc，而那一轮 malloc 风暴在真机上
// 崩在 libc 的 arena 里（arena_run_heap_remove，fault addr=0x8）。
// 本检查的作用是"**不**把读不了的文件交给库"—— 它挡不住所有非法文件，
// 所以下面的用例同时钉住"它确实挡住了什么"和"它不假装挡得住什么"。
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "gguf_precheck.h"

static int ok = 0, bad = 0;
static void chk(const char * name, bool cond) {
    if (cond) { printf("PASS  %s\n", name); ok++; }
    else      { printf("FAIL  %s\n", name); bad++; }
}

// 构造一个最小合法 GGUF 头：magic + ver + n_tensors + n_kv
static std::vector<unsigned char> head(uint32_t ver, uint64_t n_tensors, uint64_t n_kv) {
    std::vector<unsigned char> v(24, 0);
    v[0] = 'G'; v[1] = 'G'; v[2] = 'U'; v[3] = 'F';
    v[4] = ver & 0xff; v[5] = (ver >> 8) & 0xff; v[6] = (ver >> 16) & 0xff; v[7] = (ver >> 24) & 0xff;
    for (int i = 0; i < 8; i++) v[8 + i]  = (n_tensors >> (8 * i)) & 0xff;
    for (int i = 0; i < 8; i++) v[16 + i] = (n_kv >> (8 * i)) & 0xff;
    return v;
}

static bool pass(const std::vector<unsigned char> & h, long long size, std::string * why = nullptr) {
    std::string w;
    return gguf_header_check(h.data(), (long long) h.size(), size, why ? why : &w);
}

static const long long kBig = 600ll * 1024 * 1024;   // 一个 0.6B Q8_0 模型的量级

int main() {
    printf("── ① 合法头：必须放行 ───────────────────────────────────\n");
    {
        auto h = head(3, 290, 32);          // Qwen3-0.6B 的真实形状量级
        chk("v3 + 290 张量 + 32 条元数据 放行", pass(h, kBig));
    }
    {
        auto h = head(2, 1, 1);             // 老版本、极小
        chk("v2 最小头 放行", pass(h, kBig));
    }

    printf("── ② 魔数：不是 GGUF 就必须挡下 ──────────────────────────\n");
    {
        auto h = head(3, 290, 32);
        h[0] = 'g';                         // 大小写差一位
        chk("魔数 'gGUF' 挡下", !pass(h, kBig));
        std::string why;
        pass(h, kBig, &why);
        chk("原因里说明是魔数", why.find("魔数") != std::string::npos);
    }
    {
        std::vector<unsigned char> h(24, 'X');
        chk("全 'X' 头 挡下", !pass(h, kBig));
    }

    printf("── ③ 截断 / 空文件 ──────────────────────────────────────\n");
    {
        auto h = head(3, 290, 32);
        std::vector<unsigned char> shortH(h.begin(), h.begin() + 4);   // 只有 magic
        chk("只有 4 字节 挡下", !pass(shortH, 4));
        std::string why;
        pass(shortH, 4, &why);
        chk("原因里说明读不满", why.find("读不满") != std::string::npos);
    }
    {
        auto h = head(3, 290, 32);
        h.resize(12);                       // 够 magic，够不上 n_kv
        chk("只有 12 字节（有 magic 无计数）放行 → 由库处理", pass(h, kBig));
        chk("空文件（got=0）挡下", !pass(h, 0));
    }

    printf("── ④ 版本范围 ───────────────────────────────────────────\n");
    {
        chk("ver=0 挡下", !pass(head(0, 290, 32), kBig));
        chk("ver=1 挡下", !pass(head(1, 290, 32), kBig));
        chk("ver=3 放行", pass(head(3, 290, 32), kBig));
        chk("ver=4 挡下", !pass(head(4, 290, 32), kBig));
        chk("ver=0xFFFFFFFF 挡下", !pass(head(0xFFFFFFFFu, 290, 32), kBig));
        std::string why;
        pass(head(4, 290, 32), kBig, &why);
        chk("原因里带版本号", why.find("版本 4") != std::string::npos);
    }

    printf("── ⑤ 头部计数上界（库里要按它分配数组）──────────────────\n");
    {
        chk("n_tensors=0 挡下", !pass(head(3, 0, 32), kBig));
        chk("n_tensors=100万 放行（边界内）", pass(head(3, 1ull << 20, 32), kBig));
        chk("n_tensors=100万+1 挡下", !pass(head(3, (1ull << 20) + 1, 32), kBig));
        chk("n_tensors=UINT64_MAX 挡下", !pass(head(3, ~0ull, 32), kBig));
        chk("n_kv=400万 放行（边界内）", pass(head(3, 290, 1ull << 22), kBig));
        chk("n_kv=400万+1 挡下", !pass(head(3, 290, (1ull << 22) + 1), kBig));
        std::string why;
        pass(head(3, 290, ~0ull), kBig, &why);
        chk("原因里带条数", why.find("元数据条数") != std::string::npos);
    }

    printf("── ⑥ 文件尺寸 ───────────────────────────────────────────\n");
    {
        auto h = head(3, 290, 32);
        chk("< 1 KiB 挡下", !pass(h, 100));
        chk("== 1024 放行", pass(h, 1024));
        chk("尺寸未知(-1) 不因此挡下", pass(h, -1));
    }

    printf("── ⑦ 它**不**假装能挡住的（诚实边界）────────────────────\n");
    printf("    下面这些是"合法头但内容有问题"：本检查放行，交给库 —— 别读成"通过=文件一定好"\n");
    {
        auto h = head(3, 290, 32);
        chk("被截断的真实文件（头合法、size 400MB 但实际更短）仍放行", pass(h, kBig));
        std::vector<unsigned char> zeros(24, 0);
        zeros[0]='G'; zeros[1]='G'; zeros[2]='U'; zeros[3]='F';
        zeros[7]=3; zeros[8]=1;               // ver=3(高字节), n_tensors=1
        chk("n_kv 全 0 的合成头 放行（0 条 kv 合法）", pass(zeros, kBig));
    }

    printf("\n%d passed, %d failed\n", ok, bad);
    return bad == 0 ? 0 : 1;
}
