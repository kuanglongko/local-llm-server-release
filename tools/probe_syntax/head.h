// 探针开关/自举抽取单元的桩头（只为让真函数体过一遍编译器）。
// ⚠ 判据本体（probe_flag.h / probe_util.h）**include 真头**，不自造 —— 见 extract.py 的说明。
#include <cstdio>
#include <cstring>
#include <cstdarg>
#include <cstddef>
#include <string>
#include <cerrno>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <csignal>
#include <signal.h>
#include "probe_util.h"
#include "probe_flag.h"
typedef const char* jstring;
typedef unsigned char jboolean;
typedef int jclass;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define LOGI(...) probe_fmt(__VA_ARGS__)
#define LOGE(...) probe_fmt(__VA_ARGS__)
#define TAG "probe-syntax-unit"
#define JNIEXPORT
#define JNICALL
static int g_probe_fd = -1;
static bool g_probe_on = false;
static void probe_raw(const char * s, size_t n) { (void) s; (void) n; }
static void probe_fmt(const char * fmt, ...) { (void) fmt; }
static void jp(const char * fmt, ...) { (void) fmt; }
static void mark_jni_entry(const char *) {}
// 信号 handler 那一节的桩：真源码里它们用的是 g_probe_fd / jp / LOGE。
// 这里只给"名字对上、类型对上"的最小桩 —— 判据本体（幂等 / 备用栈 / SIG_IGN）
// 在 run_probe_signal_guard.sh 里从**同一份字节**上抽，不在这里重写。
static struct sigaction g_old_segv, g_old_abrt, g_old_bus, g_old_ill, g_old_fpe;
static volatile sig_atomic_t g_in_handler = 0;
static bool g_signals_installed = false;
static const size_t kAltStackSize = 64 * 1024;
static char g_alt_stack[kAltStackSize];
static bool g_alt_stack_ready = false;
static void probe_signal(int sig, siginfo_t * info, void * uctx) {
    (void) sig; (void) info; (void) uctx;
}
static void probe_install_alt_stack();
static void probe_install_signals();
static bool g_bootstrapped = false;
static bool g_probe_attempted = false;
static char g_boot_log[1024];
static size_t g_boot_log_len = 0;
static int g_boot_flush_tries = 0;
static bool g_boot_off_by_prop = false;
static bool g_probe_opt_in = false;
static const char * const kProbeDirProps[] = {"debug.localinference.probe.dir"};
static const char * const kProbeFlagProps[] = {"debug.localinference.probe.on"};
static const char * const kProbeOffValues[] = {"0", "false"};
struct FakeEnv { const char * GetStringUTFChars(jstring, void*) { return "/tmp"; }
                 void ReleaseStringUTFChars(jstring, const char*) {} };
typedef FakeEnv JNIEnv;
