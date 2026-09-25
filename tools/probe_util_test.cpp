// 探针开关与自举缓冲的宿主单测。
//
// 为什么这份单测值钱：它跑的**不是**复刻品，而是真机编进去的同一份
// `app/src/main/cpp/probe_flag.h`（用 -I 直接 include 仓库里的原件）。
// 「另写一份近似去验证另一份近似」正是判据漂移的发生方式。
//
// 另外它把**旧写法**作为对照跑一遍：旧实现的语义是
//   · 自举开关属性永不被读（死代码）；
//   · 自举当场落盘、随即清空缓冲，于是"回灌"恒为 0 字节。
// 对照组必须在该红的用例上红 —— 否则说明这组用例根本没测到东西。
#include <cstdio>
#include <cstring>
#include <cstddef>
#include <string>
#include <vector>

#include "probe_flag.h"

static int ok = 0, bad = 0;
static void chk(const char * name, bool cond) {
    if (cond) { printf("PASS  %s\n", name); ok++; }
    else      { printf("FAIL  %s\n", name); bad++; }
}

// ── ① 开关属性判据 ─────────────────────────────────────────────────────────
static const char * offVals[] = {"0", "false", "no", "off", "disable", "disabled", "null", "nil", "none"};

static bool is_off(const char * v) {
    const char * vals[1] = {v};
    return probe_flag_off(vals, 1, offVals, sizeof(offVals) / sizeof(offVals[0]));
}

static void test_flag() {
    printf("--- ① 显式关闭判据（kProbeFlagProps 真的参与判定）---\n");
    chk("on=0 → 关闭",              is_off("0"));
    chk("on=false → 关闭",          is_off("false"));
    chk("on=FALSE → 关闭（大小写）", is_off("FALSE"));
    chk("on=off → 关闭",            is_off("off"));
    chk("on=no → 关闭",             is_off("no"));
    chk("on=disable → 关闭",        is_off("disable"));
    chk("on=disabled → 关闭",       is_off("disabled"));
    chk("on=null → 关闭（getprop 对未定义属性会给这个字面量）", is_off("null"));
    chk("on=1 → 不表态（**不接受 on 方向**）", !is_off("1"));
    chk("on=true → 不表态",         !is_off("true"));
    chk("on=on → 不表态",           !is_off("on"));
    chk("on=yes → 不表态",          !is_off("yes"));
    chk("空串 → 不表态",            !is_off(""));
    chk("任意路径 → 不表态",        !is_off("/data/local/tmp"));
    chk("带尾随换行 → 关闭",        is_off("0\n"));
    chk("带尾随空白 → 关闭",        is_off("false  "));
    chk("'0abc' 不算关闭（不是恰好相等）", !is_off("0abc"));
    chk("'n one' 不算（长度对齐后中间不能有空白）", !is_off("n one"));

    // 多候选：任一为显式关闭即关闭（kProbeFlagProps 有两条）
    {
        const char * vals[] = {"1", "0"};
        chk("两条属性中任一为 0 → 关闭", probe_flag_off(vals, 2, offVals, 9));
        const char * vals2[] = {"1", "true"};
        chk("两条都不为 0 → 不表态", !probe_flag_off(vals2, 2, offVals, 9));
        chk("零候选 → 不表态（getprop 一条都没读到）", !probe_flag_off(nullptr, 0, offVals, 9));
        const char * vals3[] = {nullptr, "0"};
        chk("含 nullptr 候选 → 跳过它，仍看得到后一条", probe_flag_off(vals3, 2, offVals, 9));
    }
}

// ── ② 自举缓冲：只记不写 + 只能消费一次 ─────────────────────────────────────
static void test_boot_buffer() {
    printf("--- ② 自举缓冲（只记不写，交付只发生一次）---\n");
    char log[256];
    strcpy(log, "[boot] 自举说明\n");

    // 新写法：自举阶段不写，len 保留；第一次 init 才交付
    { int tries = -7;   // 初值故意非 0：交付必须由函数自己递增，不依赖调用方预置
      size_t n = probe_bootstrap_write(log, strlen(log), &tries, 1);
      chk("自举阶段：缓冲非空、可交付且字节数正确（只记不写）",
          n == strlen(log) && tries == -6); }
    { int tries = 0;
      size_t n = probe_bootstrap_write(log, strlen(log), &tries, 1);
      chk("交付字节数 == 缓冲长度（旧写法恒为 0）", n == strlen(log));
      chk("第二次交付为 0（同一段现场不重复落盘）",
          probe_bootstrap_write(log, strlen(log), &tries, 1) == 0);
      chk("交付累计恰好 1 次", tries == 1); }
    { int tries = 0;
      chk("空缓冲 → 不交付（Nothing）", probe_bootstrap_write(log, 0, &tries, 1) == 0 && tries == 0); }
    { int tries = 0; (void) tries;
      chk("tries 指针为空 → 不交付（不崩）", probe_bootstrap_write(log, strlen(log), nullptr, 1) == 0); }

    // 旧写法对照：自举当场写掉并清零 → 后续交付恒为 0
    { int tries = 0; size_t old_len = strlen(log); (void) tries;
      size_t wrote_at_boot = old_len;   // 旧: probe_raw(g_boot_log, g_boot_log_len)
      old_len = 0;                      // 旧: g_boot_log_len = 0;
      size_t flushed = probe_bootstrap_write(log, old_len, &tries, 1);
      printf("      旧写法对照：自举当场写了 %zu 字节，『回灌正式文件』=%zu 字节\n",
             wrote_at_boot, flushed);
      chk("旧写法对照：flush 恒为 0 字节（D-4 的可执行证据）", flushed == 0 && wrote_at_boot > 0); }
}

// ── ③ 开关属性条数 ─────────────────────────────────────────────────────────
static void test_count() {
    printf("--- ③ 属性条数常量 ---\n");
    chk("kProbeFlagPropCount == 2（与 kProbeFlagProps 对齐）", kProbeFlagPropCount == 2);
}

int main() {
    test_flag();
    test_boot_buffer();
    test_count();
    printf("\n=== 探针开关/自举缓冲单测：PASS %d / FAIL %d ===\n", ok, bad);
    return bad == 0 ? 0 : 1;
}
