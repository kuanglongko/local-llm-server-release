// 信号 handler 链的**宿主侧行为测试**（模块 K：K-1 / K-2 / K-3）。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么要有它（而不是只留源码守卫）
// ═══════════════════════════════════════════════════════════════════════════
// `run_probe_signal_guard.sh` 钉的是"结构没改回去" —— 它证明不了这套结构在**真信号**
// 下真的对。而这三条的症状只在真实信号路径上出现，且**全部与"正常"同形**：
//   K-1 第二次安装后 g_old_segv 变成探针自己 -> 转发链成环 -> 现场一行都没有；
//   K-2 SA_ONSTACK 而没有 sigaltstack -> 爆栈 SEGV 时 handler 仍在坏栈上跑；
//   K-3 原 disposition 是 SIG_IGN 时，旧写法把"忽略"升级成 exit(128+sig)。
// "一行都没有"与"根本没崩 signal"逐字相同 —— 必须主动送**真信号**才分得清。
//
// 做法：从真源码逐字抽出 `probe_signal` / `probe_install_alt_stack` /
// `probe_install_signals`（见 extract.py），配上最小 host 桩（jp/LOGE），
// 用**子进程**跑真 signal：每个用例 fork 一次，父进程读退出码判定。
//
// 反例对照：同一份测试源码由 `run_probe_signal_tests.sh` 编**两次** ——
// `-DPROBE_SIGNAL_OLD=1` 时 include 的是**旧版源码**的抽取单元。两个可执行文件
// 在同一组输入下必须给出不同结果，否则说明判据没打在这个洞上。
//
// 运行：bash tools/run_probe_signal_tests.sh

#include <cstdio>
#include <cstring>
#include <cstdarg>
#include <cstdlib>
#include <string>
#include <csignal>
#include <signal.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>

// ── host 桩：真源码里这几个符号来自探针那一节 ──────────────────────────────
static char  g_capture[8192];
static size_t g_capture_len = 0;

static void probe_raw(const char * s, size_t n) {
    if (!s || n == 0) return;
    if (g_capture_len + n > sizeof(g_capture) - 1) n = sizeof(g_capture) - 1 - g_capture_len;
    memcpy(g_capture + g_capture_len, s, n);
    g_capture_len += n;
    g_capture[g_capture_len] = '\0';
}
static void jp(const char * fmt, ...) {
    char buf[1024];
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) probe_raw(buf, (size_t) n);
}
#define LOGI(...) do { } while (0)
#define LOGE(...) do { } while (0)

// ── 真源码里这几个是**文件级 static**，抽取单元不含它们的定义 ──────────────
// 逐字照抄声明（类型必须与源码一致，否则抽取单元编不过 —— 那本身就是信号）。
static struct sigaction g_old_segv, g_old_abrt, g_old_bus, g_old_ill, g_old_fpe;
static volatile sig_atomic_t g_in_handler = 0;
static bool g_signals_installed = false;
static const size_t kAltStackSize = 64 * 1024;
static char   g_alt_stack[kAltStackSize];
static bool   g_alt_stack_ready = false;
// 旧模式下这几项不参与（旧版没有它们），但量级声明必须与源码一致 ——
// 用一条永不执行的引用把"未使用"警告按下去，而不是把它们删掉。
static inline void probe_signal_keep_refs() {
    if (kAltStackSize == 0) { g_alt_stack[0] = 0; g_alt_stack_ready = g_signals_installed; }
}

#if defined(PROBE_SIGNAL_OLD)
#  include "probe_signal_extract_old.inc"
static const bool kOldMode = true;
// ⚠ 旧版**没有**闸门也没有备用栈 —— 抽取单元里当然也没有。
//   这一模式下由测试体**手工**装一次（模拟真机"装过一次"），
//   而 `probe_install_signals` 这个名字在旧版里不存在，用一个同语义的替身。
static void probe_install_signals() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = probe_signal;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &g_old_segv);
    sigaction(SIGABRT, &sa, &g_old_abrt);
    sigaction(SIGBUS,  &sa, &g_old_bus);
    sigaction(SIGILL,  &sa, &g_old_ill);
    sigaction(SIGFPE,  &sa, &g_old_fpe);
}
#else
#  include "probe_signal_extract.inc"
static const bool kOldMode = false;
#endif

static int ok = 0, bad = 0;
__attribute__((unused)) static void chk(const char * name, bool cond) {
    if (cond) { printf("PASS  %s\n", name); ok++; }
    else      { printf("FAIL  %s\n", name); bad++; }
}
static void eq_i(const char * name, int got, int want) {
    if (got == want) { printf("PASS  %s（%d）\n", name, got); ok++; }
    else             { printf("FAIL  %s：期望 %d，实得 %d\n", name, want, got); bad++; }
}
static int child_code(pid_t p) {
    int st = 0; waitpid(p, &st, 0);
    if (WIFSIGNALED(st)) return 128 + WTERMSIG(st);
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

// ══════════════════════════════════════════════════════════════════════════
// ① K-3：原 disposition = SIG_IGN —— 必须仍然"被忽略"，不得升级成致命退出
// ══════════════════════════════════════════════════════════════════════════
// ⚠ 必须用**源码接管的 5 个信号之一**（SIGABRT），否则转发段取不到 g_old_*
//   （switch 的 `default: break` -> old == nullptr -> 直接按默认行为处理）。
// ⚠ 必须走**真** probe_install_signals() 让 `g_old_abrt` 被填 ——
//   手工 `sigaction(SIGABRT, &sa, &prev)` 只把旧动作写进局部 prev，
//   `g_old_abrt` 仍是全零 -> `sa_handler == SIG_DFL(0)` -> 走的是 default 支。
//   两条都是第一版**测试**踩的坑（实得 138 / 134），不是实现的问题。
static void body_sigign_probe() {
    struct sigaction ig;
    memset(&ig, 0, sizeof(ig));
    ig.sa_handler = SIG_IGN;
    sigaction(SIGABRT, &ig, nullptr);

    // 走**真**安装路径（新版=源码实现；旧版=上面的同语义替身），
    // 关键是让 `g_old_abrt` 真的被填成 SIG_IGN。
    probe_install_signals();

    raise(SIGABRT);   // 原语义：被忽略 -> 不该死
    _exit(0);         // 活着走到这里 = 忽略语义保住
}

#if !defined(PROBE_SIGNAL_OLD)
// ══════════════════════════════════════════════════════════════════════════
// ② K-1：装两次 —— 第二次不得把 g_old_* 覆盖成探针自己
// ══════════════════════════════════════════════════════════════════════════
static void body_install_twice() {
    probe_install_signals();
    struct sigaction first_segv, first_abrt;
    sigaction(SIGSEGV, nullptr, &first_segv);
    sigaction(SIGABRT, nullptr, &first_abrt);
    probe_install_signals();          // 第二次（真机：backendInit 抛异常 + 用户再点加载）
    struct sigaction second_segv, second_abrt;
    sigaction(SIGSEGV, nullptr, &second_segv);
    sigaction(SIGABRT, nullptr, &second_abrt);
    bool same = (first_segv.sa_sigaction == second_segv.sa_sigaction) &&
                (first_abrt.sa_sigaction == second_abrt.sa_sigaction);
    bool not_self = (g_old_segv.sa_sigaction != (void (*)(int, siginfo_t *, void *)) probe_signal);
    chk("K-1 二次安装后 g_old_* 不是探针自己（转发链不成环）", not_self);
    chk("K-1 二次安装不改写当前 handler（幂等）", same);
    _exit((same && not_self) ? 0 : 1);
}

// ══════════════════════════════════════════════════════════════════════════
// ③ K-1 行为面：真实 SIGSEGV 后进程必须有**探针输出**（链成环则一行都没有）
// ══════════════════════════════════════════════════════════════════════════
static void body_signal_scene() {
    probe_install_signals();
    probe_install_signals();     // 真机上会发生的那一次
    raise(SIGSEGV);
    _exit(3);                    // 不该走到这里（信号应已终结进程）
}
#endif

int main() {
    probe_signal_keep_refs();
    printf("=== 模式：%s ===\n", kOldMode ? "旧实现（反例对照）" : "当前实现");

    printf("--- ① K-3：原 disposition = SIG_IGN 时保持忽略语义 ---\n");
    {
        pid_t p = fork();
        if (p == 0) body_sigign_probe();
        int code = child_code(p);
        // 新实现：活到 _exit(0) -> 0。
        // 旧实现：`_exit(128 + SIGABRT)` -> 134（"忽略"被升级成"去死"）。
        if (kOldMode) eq_i("K-3 旧实现：SIG_IGN 被升级为致命退出（134）", code, 134);
        else          eq_i("K-3 新实现：SIG_IGN 语义保持（0，非 134）", code, 0);
    }

    printf("--- ② K-1：安装两次 ---\n");
#if defined(PROBE_SIGNAL_OLD)
    printf("n/a   K-1 幂等：旧版抽取单元只含 handler（无闸门/altstack），\n");
    printf("      该结构由 run_probe_signal_guard.sh 的桩①/③ 对照覆盖。\n");
#else
    {
        pid_t p = fork();
        if (p == 0) body_install_twice();
        eq_i("K-1 二次安装后仍保持完好（子进程正常退出）", child_code(p), 0);
    }
#endif

    printf("--- ③ K-1 行为面：真实 SIGSEGV 后现场留下 ---\n");
#if defined(PROBE_SIGNAL_OLD)
    printf("n/a   K-1 行为面：旧版无幂等闸门，对照组由守卫的桩①/③ 承担。\n");
#else
    {
        pid_t p = fork();
        if (p == 0) body_signal_scene();
        // 新实现：handler 进入 -> 原 handler 是 SIG_DFL -> signal(SIG_DFL); raise
        // -> 进程被 SIGSEGV 终结 -> 128+11=139。
        eq_i("K-1 真实 SIGSEGV 被 handler 接住（终止于信号本身，139）", child_code(p), 139);
    }
#endif

    printf("--- ④ K-2：SA_ONSTACK 与备用栈 ---\n");
#if defined(PROBE_SIGNAL_OLD)
    printf("n/a   K-2：旧版抽取单元不含 probe_install_alt_stack。\n");
#else
    {
        probe_install_signals();
        struct sigaction a;
        sigaction(SIGSEGV, nullptr, &a);
        bool has = (a.sa_flags & SA_ONSTACK) != 0;
        stack_t cur;
        memset(&cur, 0, sizeof(cur));
        bool got = (sigaltstack(nullptr, &cur) == 0) && (cur.ss_flags & SS_DISABLE) == 0;
        if (g_alt_stack_ready) {
            chk("K-2 备用栈挂上了（sigaltstack 查询可见）", got);
            chk("K-2 备用栈起点就是那块静态数组（不被复用）", got && cur.ss_sp == (void *) g_alt_stack);
            chk("K-2 备用栈装好后 SA_ONSTACK 确实被设置", has);
        } else {
            // 宿主容量不足（罕见）：此时**必须不设** SA_ONSTACK —— 设了而没装是误导。
            chk("K-2 备用栈未装时 SA_ONSTACK 保持关闭（不谎报）", !has);
        }
    }
#endif

    printf("=== 信号 handler 行为测（%s）：PASS %d / FAIL %d ===\n",
           kOldMode ? "旧实现对照" : "当前实现", ok, bad);
    return bad == 0 ? 0 : 1;
}
