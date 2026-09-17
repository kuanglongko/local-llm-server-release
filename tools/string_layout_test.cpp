// 宿主侧单测：钉住 libc++ ABI v1 短串布局的实际字节位置。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么要有这一份
// ═══════════════════════════════════════════════════════════════════════════
// chat_abi.cpp 的 self_check() 里有一条"std::string 布局必须是 libc++ ABI v1"的
// 运行时自检。它原先写成读 **末字节**（buf[23]）并断言 `buf[23]>>1 == size`，
// 这在 libc++ ABI v1 上**恒为假** —— 真机日志因此一直打：
//
//   [abi] !! 自检失败: std::string 布局不是 libc++ ABI v1：末字节=0，期望 size<<1=10。
//
// 真实布局（小端 + libc++ ABI v1 + non-alternate layout）：
//   byte[0]  : bit0 = __is_long_，bit1..7 = size      →  "abcde" 时 = 0x0a
//   byte[1..22]: 串数据
//   byte[23] : 恒为 0（串数据末尾的 NUL / padding）
//
// 所以判据必须落在 **byte[0]**，不是 byte[23]。
//
// 这份测试不依赖 NDK：它在**宿主** STL 上跑，验的是"探针的判据函数"本身的
// 逻辑正确性（用显式构造的字节数组喂进去），而不是宿主 STL 的布局。
// 这样即使 CI 用 libstdc++ 编，也依然能拦住"又写回末字节"这种回归。
//
// 运行：bash tools/run_string_layout_tests.sh
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

// ── 从 chat_abi.cpp 摘出的判据（与线上保持同一逻辑） ──────────────────────
// 返回 "" 表示通过；否则返回失败说明。传入的是 string 对象的原始字节。
static bool libcxx_abi_v1_short_string_ok(const unsigned char * bytes, size_t nbytes,
                                          size_t expect_size, char * err, size_t errlen) {
    if (nbytes != 24) {
        snprintf(err, errlen, "对象不是 24B（=%zu）：不是 libc++ ABI v1", nbytes);
        return false;
    }
    const unsigned char b0 = bytes[0];
    // bit0 = __is_long_（短串必须 0）；bit1..7 = size
    if ((b0 & 0x01u) != 0) {
        snprintf(err, errlen, "byte[0]=0x%02x 的 bit0 为 1，说明是长串或 alternate layout", (unsigned) b0);
        return false;
    }
    if ((size_t) (b0 >> 1) != expect_size) {
        snprintf(err, errlen, "byte[0]=0x%02x → size=%u，期望 %zu",
                 (unsigned) b0, (unsigned) (b0 >> 1), expect_size);
        return false;
    }
    return true;
}

static int g_ok = 0, g_bad = 0;
static void check(const char * name, bool cond) {
    if (cond) { printf("PASS  %s\n", name); g_ok++; }
    else      { printf("FAIL  %s\n", name); g_bad++; }
}

int main() {
    char err[256];

    // ① 手工构造 libc++ ABI v1 小端短串 "abcde"（size=5, is_long=0）
    {
        unsigned char b[24];
        memset(b, 0, sizeof(b));
        b[0] = (unsigned char) ((5 << 1) | 0);   // 0x0a
        memcpy(b + 1, "abcde", 5);
        check("ABI v1 短串(size=5) 判据通过",
              libcxx_abi_v1_short_string_ok(b, 24, 5, err, sizeof(err)));
    }

    // ② 回归：旧判据（只读末字节）在同一份正确字节上必须**失败** ——
    //    以此固化"末字节不是 size 的存放位置"这个事实。
    {
        unsigned char b[24];
        memset(b, 0, sizeof(b));
        b[0] = (unsigned char) ((5 << 1) | 0);
        memcpy(b + 1, "abcde", 5);
        const bool old_judge = ((b[23] >> 1) == 5);
        check("旧判据(读末字节)在正确布局上必失败 → 说明它是错的", !old_judge);
    }

    // ③ 手工构造长串（is_long=1）：bit0 为 1，判据必须拦住
    {
        unsigned char b[24];
        memset(b, 0, sizeof(b));
        b[0] = 0x0a | 0x01;                      // is_long=1
        check("长串(is_long=1) 被判据拦住",
              !libcxx_abi_v1_short_string_ok(b, 24, 5, err, sizeof(err)));
    }

    // ④ 手工构造 alternate layout（ABI v2）的短串：数据从 offset 0 开始 → b[0]='a'
    {
        unsigned char b[24];
        memset(b, 0, sizeof(b));
        memcpy(b, "abcde", 5);                   // alternate: data at offset 0
        b[23] = (unsigned char) ((5 << 1) | 0);  // alternate 把 size 放末字节
        check("alternate layout(ABI v2) 被判据拦住",
              !libcxx_abi_v1_short_string_ok(b, 24, 5, err, sizeof(err)));
    }

    // ⑤ 尺寸不符（非 24B）必须被拦住
    {
        unsigned char b[32];
        memset(b, 0, sizeof(b));
        check("非 24B 对象被拦住",
              !libcxx_abi_v1_short_string_ok(b, 32, 0, err, sizeof(err)));
    }

    // ⑥ 空串：size=0 → byte[0]=0x00
    {
        unsigned char b[24];
        memset(b, 0, sizeof(b));
        check("空串(size=0) 判据通过",
              libcxx_abi_v1_short_string_ok(b, 24, 0, err, sizeof(err)));
    }

    // ⑦ 边界：SSO 上限 22 字节
    {
        unsigned char b[24];
        memset(b, 0, sizeof(b));
        b[0] = (unsigned char) ((22 << 1) | 0);  // 0x2c
        check("SSO 上限 size=22 判据通过",
              libcxx_abi_v1_short_string_ok(b, 24, 22, err, sizeof(err)));
    }

    printf("=== string 布局判据：%d 通过 / %d 失败 ===\n", g_ok, g_bad);
    return g_bad == 0 ? 0 : 1;
}
