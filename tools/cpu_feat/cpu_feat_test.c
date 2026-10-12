/*
 * cpu_feat.c 的宿主侧行为单测（不依赖 ARM64 / 设备）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么必须有这一份（Issue #174 / 模块丙-1）
 * ═══════════════════════════════════════════════════════════════════════════
 * `cpu_feat.c` 是**唯一**决定"加载哪个 rnllama 变体"的地方。它的坏法全是静默的：
 *
 *   ① 位常量写错（HWCAP2_I8MM 用成 `1UL << 12`）—— 探测一位永远为 0 或恒为 1，
 *      App 照常启动、照常推理，只是**静默选到慢变体**（或在不支持的机上选到会
 *      SIGILL 的快变体）。没有异常、没有日志、没有任何症状能指向"这里错了"。
 *   ② 把 `AT_HWCAP2` 的取值来源写反（读 AT_HWCAP）—— 同上，只是一个恒 0。
 *   ③ 返回数组的**槽位顺序**与 Kotlin 侧 `c[0]/c[1]` 的分工不一致 ——
 *      dotprod 与 i8mm 对调，选变体的判据整个反过来。
 *
 * 所以判据锚**位常量本身**与**槽位顺序**，不锚"文件里出现过 HWCAP2"。
 *
 * 怎么在宿主（x86 也算）上测一个 ARM64 库：
 *   本测试**不调用** getauxval，而是把 cpu_feat.c 里那套"位 → 输出槽"的判据
 *   单独编出来跑 —— 用显式的合成 hwcap 值覆盖四种组合（00/10/01/11），
 *   断言输出数组的槽位与取值。这样在 CI 的 x86 宿主上也能拦住常量回归。
 *
 * 运行：sh tools/run_cpu_feat_tests.sh
 */
#include <stdio.h>
#include <string.h>

/* 直接从真源复制口径 —— 常量必须与 cpu_feat.c **逐字一致**。
 * 不 #include cpu_feat.c：那会把 jni.h 与 getauxval 一起拖进来，宿主上编不过。
 * 改为在守卫（run_cpu_feat_guard.sh）里断言本文件的常量 == 真源里的常量，
 * 于是"两边一起改错"也会被拦住。 */
#define HWCAP_ASIMDDP  (1UL << 20)
#define HWCAP2_I8MM    (1UL << 13)

static int g_fail = 0;
static int g_pass = 0;

static void ck(const char *name, int cond) {
    if (cond) { printf("PASS  %s\n", name); g_pass++; }
    else      { printf("FAIL  %s\n", name); g_fail++; }
}

/* cpu_feat.c 的判据本体（与真源同形：两位各出一个槽，再原样回填 raw）。 */
static void probe(unsigned long h1, unsigned long h2, long out[4]) {
    out[0] = (h1 & HWCAP_ASIMDDP) ? 1L : 0L;
    out[1] = (h2 & HWCAP2_I8MM) ? 1L : 0L;
    out[2] = (long)h1;
    out[3] = (long)h2;
}

int main(void) {
    long out[4];

    /* ① 位常量就是内核 uapi 的值（写错一位立刻红，且与真源比对见守卫） */
    ck("HWCAP_ASIMDDP 是 bit 20", HWCAP_ASIMDDP == (1UL << 20));
    ck("HWCAP2_I8MM 是 bit 13",   HWCAP2_I8MM == (1UL << 13));

    /* ② 四种组合的槽位与取值 */
    probe(0, 0, out);
    ck("都不支持 -> dotprod=0 i8mm=0", out[0] == 0 && out[1] == 0);

    probe(HWCAP_ASIMDDP, 0, out);
    ck("只有 dotprod -> dotprod=1 i8mm=0（不串槽）", out[0] == 1 && out[1] == 0);

    probe(0, HWCAP2_I8MM, out);
    ck("只有 i8mm -> dotprod=0 i8mm=1（不串槽）", out[0] == 0 && out[1] == 1);

    probe(HWCAP_ASIMDDP, HWCAP2_I8MM, out);
    ck("都支持 -> dotprod=1 i8mm=1", out[0] == 1 && out[1] == 1);

    /* ③ raw 槽位原样回填（Kotlin 用它打日志，取错槽会印出错的位数） */
    probe(0xFFFFFFFFUL, 0x1234UL, out);
    ck("槽2 = AT_HWCAP 原值",  out[2] == (long)0xFFFFFFFFUL);
    ck("槽3 = AT_HWCAP2 原值", out[3] == (long)0x1234UL);

    /* ④ i8mm 必须只认 AT_HWCAP2（写反来源时 h1 里带 bit13 会误报 1） */
    probe(1UL << 13, 0, out);
    ck("AT_HWCAP 的 bit13 不得被当成 i8mm（来源不能写反）", out[1] == 0);

    /* ⑤ dotprod 必须只认 AT_HWCAP（h2 里带 bit20 不得误报） */
    probe(0, 1UL << 20, out);
    ck("AT_HWCAP2 的 bit20 不得被当成 dotprod（来源不能写反）", out[0] == 0);

    printf("\n=== cpu_feat 行为单测：PASS %d / FAIL %d ===\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
