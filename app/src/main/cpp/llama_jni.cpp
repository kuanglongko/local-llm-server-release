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
#include <csignal>
#include <ctime>
#include <cerrno>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>   // SYS_gettid
#include <sys/types.h>
#include <sys/wait.h>      // waitpid（探针自举读系统属性）

#include "llama.h"
#include "ggml-backend.h"   // dev_count/dev_get/dev_name，枚举 tensor_split 下标
#include "gguf.h"
#include "utf8_safe.h"
// chat_abi.h 是**唯一**允许 include vendor chat.h 的地方：它在编译期钉住
// STL ABI（libc++）、json 版本（3.12.0）与结构体逐字段偏移，
// 任何一条不成立都会在这里编不过，而不是等真机 SIGSEGV。
#include "chat_abi.h"

#define TAG "LlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ══════════════════════════════════════════════════════════════════════════════
//                           崩溃探针（native-probe）
// ══════════════════════════════════════════════════════════════════════════════
// 目的：工具调用路径上的闪退此前完全无法取证 ——
//   · 走 llama_log_set 的日志在 abort 前会丢（fd 2 重定向只接得住「直写 stderr」的那部分）；
//   · JVM 的 UncaughtExceptionHandler 覆盖不到 native abort；
//   · 客户端只看得到「连接被重置」，服务端连一行遗言都没留下。
//
// 本探针三条独立通路，互不依赖，任何一条活着就能留下现场：
//   1. jp()  —— 关键节点直写 fd（probe-native.log），write 即时进内核页缓存，
//               不经 JVM、不经 llama log 系统，abort 瞬间也不会丢；
//   2. signal handler —— SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE 里把信号号、故障地址、
//               触发时刻写进同一个 fd，再链式转发给原 handler（绝不吞信号、不改原行为）；
//   3. JNI_SPAN —— 每个 JNI 入口打「进入 / 返回」，谁进去没出来一眼可见。
//
// 开关：设置页「崩溃探针」（默认关）。开着的时候所有日志（含逐 token 输出）都走这里，
// 并旁路 JVM 日志回调；关着时 jp() 第一行就返回，开销为零。
// 落盘目录由 Kotlin 经 nativeProbeInit 传入（filesDir/logs），与 Kotlin 侧日志同目录。
static int  g_probe_fd  = -1;
static bool g_probe_on  = false;

static void probe_raw(const char * s, size_t n) {
    if (!g_probe_on || g_probe_fd < 0 || !s || n == 0) return;
    ssize_t w = write(g_probe_fd, s, n);
    (void) w;
}

static void probe_fmt(const char * fmt, ...) {
    char buf[1024];
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    if (n > (int) sizeof(buf) - 1) n = (int) sizeof(buf) - 1;
    probe_raw(buf, (size_t) n);
}

// 带毫秒时间戳与线程号的探针行；fmt 后需自带换行。
// 注意：probe_signal 里也会调它，因此它只允许用异步信号安全的东西 ——
// 当前实现是 clock_gettime/localtime_r/vsnprintf/write，都不加锁、不分配内存。
// 一旦往这里加 std::string / malloc / 日志回调，崩溃时就会变成「handler 自己再崩」。
static void jp(const char * fmt, ...) {
    if (!g_probe_on || g_probe_fd < 0) return;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tmv;
    time_t sec = ts.tv_sec;
    localtime_r(&sec, &tmv);
    char head[64];
    int hn = snprintf(head, sizeof(head), "%02d:%02d:%02d.%03d [tid=%ld] ",
                      tmv.tm_hour, tmv.tm_min, tmv.tm_sec, (int) (ts.tv_nsec / 1000000),
                      (long) gettid());
    probe_raw(head, hn > 0 ? (size_t) hn : 0);
    char buf[2048];
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) probe_raw(buf, (size_t) (n > (int) sizeof(buf) - 1 ? (int) sizeof(buf) - 1 : n));
}

// ---- 探针自举：把「探针装反了方向」这个 bug 一次性钉死 ----
//
// 历史故障（用户实测）：日志里只有 `[probe] !! 探针挂载失败：UnsatisfiedLinkError:
// No implementation found for ...LlmEngine.nativeProbeInit`，然后整段会话一条 native
// 探针行都没有。三层原因层层叠加，每一层都能单独让探针失效：
//
//   ①  `nativeProbeInit` 必须先 load 到 libllmjni_<tag>.so 才能调（System.loadLibrary 之后）；
//   ②  而 `nativeProbeInit` 又是**唯一**一条能让 native 侧知道「探针开着」的通路；
//   ③  Kotlin 侧因此拿不到任何信息去判断「我这台机该 load 哪几个变体名」。
//
// 于是出现死结：`.so` 里的符号没被 load（或名字/构建对不上）→ JNI 找不到实现 →
// 挂载失败 → 无落盘 → 排查时连「native 到底有没有跑过」都无从确认。
//
// 破法：把方向反过来。native 侧启动阶段（JNI_OnLoad）先自己跑一次受控自举 ——
// 不依赖任何 JNI 调用、不依赖 Kotlin 传参，只读系统属性 + env 决定往哪写、
// 加载期故障就记哪。JNI 入口这次调用若是第一次，就地认领这次自举，
// 把「Kotlin 已传过来的目录」升级成正式落盘配置；自举阶段的早期事件同时回灌到正式文件。

static const char * const kProbeDirProps[] = {
    "debug.localinference.probe.dir",   // 显式指定落盘目录（不依赖 App 上下文）
    "debug.localinference.probe",       // 兼容简写：值本身即目录
    "localinference.probe.dir",
};
static const char * const kProbeFlagProps[] = {
    "debug.localinference.probe.on",    // 受控自举开关（JNI_OnLoad 时读取）
    "localinference.probe.on",
};

// `getprop <key>` 的受控读取：只接受 [A-Za-z0-9._/-] 的可信值，
// 经 pipe 拿回来，避免把任意系统属性内容当路径用。
static bool probe_getprop(const char * key, char * out, size_t cap) {
    out[0] = '\0';
    int pfd[2];
    if (pipe(pfd) != 0) return false;
    pid_t pid = fork();
    if (pid < 0) { close(pfd[0]); close(pfd[1]); return false; }
    if (pid == 0) {
        dup2(pfd[1], STDOUT_FILENO);
        close(pfd[0]); close(pfd[1]);
        execl("/system/bin/getprop", "getprop", key, (char *) nullptr);
        _exit(127);
    }
    close(pfd[1]);
    size_t n = 0;
    ssize_t r;
    while (n + 1 < cap && (r = read(pfd[0], out + n, cap - 1 - n)) > 0) n += (size_t) r;
    out[n] = '\0';
    close(pfd[0]);
    int st = 0;
    waitpid(pid, &st, 0);
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char) out[i];
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                  c == '.' || c == '_' || c == '/' || c == '-';
        if (!ok) { out[0] = '\0'; return false; }
    }
    while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r')) out[--n] = '\0';
    return n > 0;
}

static bool probe_prop_has(const char * const * keys, size_t n) {
    char v[512];
    for (size_t i = 0; i < n; i++) if (probe_getprop(keys[i], v, sizeof(v))) return true;
    return false;
}

// true = 自举被调用过（无论最终有没有写成功）。JNI_OnLoad 与 nativeProbeInit 都会调它，
// 靠 g_bootstrapped 保证只跑一次；g_probe_attempted 供入口自检判断「探针这条通路到底通没通」。
static bool g_bootstrapped = false;
static bool g_probe_attempted = false;
static char g_boot_log[1024];   // 自举期事件（含失败原因），正式探针落盘时回灌
static size_t g_boot_log_len = 0;

static void probe_boot_note(const char * fmt, ...) {
    if (g_boot_log_len + 2 >= sizeof(g_boot_log)) return;
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(g_boot_log + g_boot_log_len, sizeof(g_boot_log) - g_boot_log_len, fmt, ap);
    va_end(ap);
    if (n > 0) {
        g_boot_log_len += (size_t) n;
        if (g_boot_log_len >= sizeof(g_boot_log)) g_boot_log_len = sizeof(g_boot_log) - 1;
    }
}

// 关闭自举（JNI 入口认领时调用）：把早期事件整理成一条说明回灌进正式探针文件。
static void probe_bootstrap_flush() {
    if (!g_probe_attempted) return;
    if (g_boot_log_len > 0) probe_raw(g_boot_log, g_boot_log_len);
}

static bool probe_bootstrap() {
    if (g_bootstrapped) return false;
    g_bootstrapped = true;
    g_probe_attempted = true;

    const char * dir = nullptr;
    char dirbuf[512];
    if (probe_getprop(kProbeDirProps[0], dirbuf, sizeof(dirbuf)))       dir = dirbuf;
    else if (probe_getprop(kProbeDirProps[1], dirbuf, sizeof(dirbuf)))  dir = dirbuf;
    else if (probe_getprop(kProbeDirProps[2], dirbuf, sizeof(dirbuf)))  dir = dirbuf;

    // 没有显式目录时按包名推一个应用可写目录：探针文件本来就写在
    // <外部私有目录>/files/logs 下（Kotlin 传的就是它），原生侧同样可写，
    // 因此自举不需要 Kotlin 先跑起来。
    char path[640];
    if (dir) {
        snprintf(path, sizeof(path), "%s/probe-native.log", dir);
    } else {
        snprintf(path, sizeof(path), "/sdcard/Android/data/com.xiaowan.localinference/files/logs/probe-native.log");
    }

    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) {
        // 目录可能还不存在（App 尚未创建）：退到 /data/local/tmp 这个
        // shell 可读的位置，保证「加载期故障」至少留下痕迹，而不是静默。
        snprintf(path, sizeof(path), "/data/local/tmp/localinference-probe-native.log");
        fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    }
    if (fd < 0) {
        // 彻底写不出去：至少让 logcat 里有据可查，不静默。
        LOGE("probe bootstrap: 无法打开探针文件（dir=%s）: %s", dir ? dir : "(默认)", strerror(errno));
        return true;
    }
    probe_boot_note("[boot] native 探针自举（JNI_OnLoad 阶段，未依赖任何 JNI 调用）\n");
    if (dir) probe_boot_note("[boot] 目录来源=系统属性\n");
    else     probe_boot_note("[boot] 目录来源=包名默认推导\n");
    probe_boot_note("[boot] 文件=%s（fd=%d）\n", path, fd);
    g_probe_fd = fd;
    g_probe_on = true;
    probe_raw(g_boot_log, g_boot_log_len);
    g_boot_log_len = 0;
    return true;
}

// JNI 入口自检：哪怕探针没开，也把「native 被调过」这一事实留在 logcat 里。
// 排查「UnsatisfiedLinkError 到底是 .so 没 load 还是符号缺失」时，这行是分水岭。
static void mark_jni_entry(const char * fn) {
    if (!g_probe_attempted) LOGE("JNI entry reached: %s（探针未自举：应属受控关闭）", fn);
}

// ---- 每个 JNI 入口成对打点：谁进去没出来，一眼可见 ----
struct JniSpan {
    const char * fn;
    explicit JniSpan(const char * f) : fn(f) { mark_jni_entry(fn); jp(">> %s\n", fn); }
    ~JniSpan() { jp("<< %s\n", fn); }
};
#define JNI_SPAN(name) JniSpan _jni_span_##__LINE__(name)

// ---- 信号 handler：只在探针开着时安装，原 handler 照旧链式转发 ----
static struct sigaction g_old_segv, g_old_abrt, g_old_bus, g_old_ill, g_old_fpe;
static volatile sig_atomic_t g_in_handler = 0;

static void probe_signal(int sig, siginfo_t * info, void * uctx) {
    if (g_in_handler) _exit(128 + sig);   // 递归触发（handler 自己也崩）：直接退出，别死循环
    g_in_handler = 1;
    jp("!!! ===== SIGNAL %d (%s) =====\n", sig, strsignal(sig));
    if (info) {
        jp("!!! fault addr=%p  code=%d  errno=%d\n",
           info->si_addr, info->si_code, errno);
    }
    // 不调 backtrace()：Android 上它需要 libexecinfo / unwind 表，容易自己再崩。
    // 这里只打「进程内已知的进度标记」，靠 Kotlin 侧 mark 推断崩在哪一步；
    // 完整调用栈由 tombstones（/data/tombstones）给出，日志里那行 addr 也能对上。
    jp("!!! 末次进度标记见 v1.12 上文（每条 >> 都在找配对的 <<）\n");
    jp("!!! ===== SIGNAL END =====\n");
    (void) uctx;
    // 转发给原 handler：绝不吞掉信号，也不改变原有行为
    struct sigaction * old = nullptr;
    switch (sig) {
        case SIGSEGV: old = &g_old_segv; break;
        case SIGABRT: old = &g_old_abrt; break;
        case SIGBUS:  old = &g_old_bus;  break;
        case SIGILL:  old = &g_old_ill;  break;
        case SIGFPE:  old = &g_old_fpe;  break;
        default: break;
    }
    if (old && (old->sa_flags & SA_SIGINFO) && old->sa_sigaction) {
        old->sa_sigaction(sig, info, uctx);
    } else if (old && old->sa_handler == SIG_DFL) {
        signal(sig, SIG_DFL);
        raise(sig);
    } else if (old && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
    }
    if (old && old->sa_handler == SIG_IGN) _exit(128 + sig);
    // 原 handler 若正常返回，保持默认行为
    signal(sig, SIG_DFL);
    raise(sig);
}

static void probe_install_signals() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = probe_signal;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &g_old_segv);
    sigaction(SIGABRT, &sa, &g_old_abrt);
    sigaction(SIGBUS,  &sa, &g_old_bus);
    sigaction(SIGILL,  &sa, &g_old_ill);
    sigaction(SIGFPE,  &sa, &g_old_fpe);
    jp("[probe] signal handlers installed (SEGV/ABRT/BUS/ILL/FPE)\n");
}


static JavaVM * g_vm = nullptr;
static jobject   g_log_cb   = nullptr; // 全局引用：LlmLogBridge 实例
static jmethodID g_log_mid  = nullptr;

static void probe_log_sink(enum lm_ggml_log_level, const char *, void *) {}

static void jni_log_cb(enum lm_ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!g_vm || !g_log_cb || !g_log_mid || !text) return;
    // jni.h 里有 C++ 重载：AttachCurrentThread(JNIEnv** p_env, void* thr_args)。
    // 参数类型就是 JNIEnv**，直接传 &env 即可；不能 cast 成 void**，
    // 那会选到 C 的可变参版本（void** 形参），NDK 的 clang 会直接报参数类型不匹配。
    // 也不要写 ...AsDaemon：本函数只在日志回调线程上跑，普通 attach 语义已足够。
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

// 库加载入口：**探针的关键一步在这里**。
//
// 之前只在 nativeProbeInit 里开文件，等于把「能不能取证」押在
// 「Kotlin 是否成功 load 到这个 .so 并调到这个符号」上；一旦那一步失败
// （UnsatisfiedLinkError / 变体名对不上 / 变体在运行时排除），
// native 侧就完全静默 —— 而它恰恰是崩点所在的那一侧。
// 现在 JNI_OnLoad 先自己跑一次受控自举：只读系统属性与 env，不依赖任何 JNI 调用，
// 加载期故障（例如另一个 .so 的静态初始化崩了）也能留下痕迹。
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    g_vm = vm;
    LOGE("JNI_OnLoad 已进入（libllmjni 已加载）");
    probe_bootstrap();
    return JNI_VERSION_1_6;
}

// 探针初始化：Kotlin 传入可写目录，native 侧自己开文件直写，绝不依赖 JVM。
// 正常模式（on=false）下把 jni_log_cb 换成空操作：探针接管后代码路径保持一致，
// 不会出现「开了探针就换了一条执行路径」的观测者效应。
//
// 这一次调用同时是「认领自举」的时机：自举阶段已开的 fd 被接管，
// 早期事件（含失败原因）回灌到正式文件；自举没触发时行为与原来完全一致。
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit(JNIEnv * env, jclass, jstring jdir, jboolean on) {
    mark_jni_entry("nativeProbeInit");
    const bool off = (on != JNI_TRUE);
    // 先按 Kotlin 的参数收口：关探针 = 关文件，这一步不能因为自举而漏掉。
    if (g_probe_fd >= 0) { close(g_probe_fd); g_probe_fd = -1; }
    g_probe_on = !off;
    if (off) { g_bootstrapped = true; return JNI_FALSE; }
    if (!jdir) { g_bootstrapped = true; return JNI_FALSE; }

    const char * dir = env->GetStringUTFChars(jdir, nullptr);
    if (!dir) { g_probe_on = false; g_bootstrapped = true; return JNI_FALSE; }
    std::string path = std::string(dir) + "/probe-native.log";
    env->ReleaseStringUTFChars(jdir, dir);
    g_probe_fd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (g_probe_fd < 0) { g_probe_on = false; g_bootstrapped = true; return JNI_FALSE; }

    probe_fmt("\n===== native probe attached (pid=%d, 目录来自 Kotlin filesDir) =====\n", (int) getpid());
    // 自举期事件（如「getprop 读不到」「退到 /data/local/tmp」）在这里回灌，
    // 免得它们只留在早期那个可能已被替换的 fd 里。
    probe_bootstrap_flush();
    g_bootstrapped = true;
    g_probe_attempted = true;
    probe_install_signals();
    return JNI_TRUE;
}

// Kotlin 侧埋点：把「这一步要开始了」写进 native fd，探针关着时是空操作。
JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeProbeMark(JNIEnv * env, jclass, jstring jmsg) {
    if (!g_probe_on || !jmsg) return;
    const char * m = env->GetStringUTFChars(jmsg, nullptr);
    if (m) { jp("[mark] %s\n", m); env->ReleaseStringUTFChars(jmsg, m); }
}

// backendInit(logBridge) — 在 System.loadLibrary 之后、loadModel 之前调用一次
JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_backendInit(JNIEnv * env, jclass, jobject bridge) {
    JNI_SPAN("backendInit");
    if (g_log_cb) return;
    g_log_cb = env->NewGlobalRef(bridge);
    jclass cls = env->GetObjectClass(g_log_cb);
    g_log_mid  = env->GetMethodID(cls, "onNativeLog", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    // 探针开启时换成空操作 sink：两条路径下 llama.cpp 的输出处理代码完全一致，
    // 只有最终写出点不同（JVM 回调 vs 丢弃），保证「开探针」不改变线程/锁行为。
    llama_log_set(g_probe_on ? probe_log_sink : jni_log_cb, nullptr);
    llama_backend_init();
    LOGI("llama backend init (hexagon/opencl/cpu) probe=%d", g_probe_on ? 1 : 0);
    // ABI 自检：编译期 static_assert 管住偏移/尺寸，运行期这一条管住
    // "本单元与 librnllama*.so 是否同一套 STL"。不通过就打日志，
    // 工具路径本来就有纯文本兜底，不必在此阻断。
    {
        const char * err = abi::self_check();
        if (err && *err) { jp("[abi] !! 自检失败: %s\n", err); LOGE("chat ABI self-check failed: %s", err); }
        else             { jp("[abi] 自检通过（libc++ ABI v1，inputs=168B）\n"); }
    }
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
    JNI_SPAN("probeGguf");
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
    JNI_SPAN("loadModel");
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

// newSampler(temp, topP, minP, topK, repPenalty, penaltyN, freqPenalty, presencePenalty, seed)
// 采样链顺序（与 llama.cpp 官方示例一致：截断在前、温度在截断之后、dist 收尾）：
//   penalties -> top_k -> top_p -> min_p -> temp -> dist
// 惩罚必须排在所有截断之前：否则被 top_k/top_p/min_p 砍掉的 token 无法被惩罚救回，
// 出现重复时惩罚力度显著弱于配置值。
// temp<=0 视为贪心（此时忽略其余采样参数，与 llama.cpp 一致）。
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeNewSampler(
        JNIEnv *, jclass, jfloat temp, jfloat topP, jfloat minP, jint topK,
        jfloat repPenalty, jint penaltyN, jfloat freqPenalty, jfloat presencePenalty, jlong seed) {
    jp(">> newSampler temp=%.3f topP=%.3f minP=%.3f topK=%d rep=%.3f n=%d freq=%.3f pres=%.3f seed=%lld\n",
       (double) temp, (double) topP, (double) minP, (int) topK, (double) repPenalty, (int) penaltyN,
       (double) freqPenalty, (double) presencePenalty, (long long) seed);
    if (!S.ctx) { jp("<< newSampler 无 ctx -> false\n"); return JNI_FALSE; }
    if (S.smpl) { llama_sampler_free(S.smpl); S.smpl = nullptr; }
    llama_sampler * ch = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(ch, llama_sampler_init_greedy());
    } else {
        // 任一惩罚非默认值即启用 penalties。presencePenalty 必须一起判：
        // 此前只判 repeat/frequency，单独调 presence_penalty 时整个采样器不会入链，参数被静默丢弃。
        // penaltyN<=0 = 关闭惩罚窗口，此时惩罚采样器本身是 noop，不必入链。
        if (penaltyN > 0 &&
            (repPenalty != 1.0f || freqPenalty != 0.0f || presencePenalty != 0.0f)) {
            llama_sampler_chain_add(ch, llama_sampler_init_penalties(
                llama_vocab_n_tokens(S.vocab), penaltyN, repPenalty, freqPenalty, presencePenalty));
        }
        if (topK > 0)                        llama_sampler_chain_add(ch, llama_sampler_init_top_k(topK));
        if (topP > 0.0f && topP < 1.0f)      llama_sampler_chain_add(ch, llama_sampler_init_top_p((float) topP, 1));
        if (minP > 0.0f && minP < 1.0f)      llama_sampler_chain_add(ch, llama_sampler_init_min_p((float) minP, 1));
        llama_sampler_chain_add(ch, llama_sampler_init_temp((float) temp));
        // seed 已在 Kotlin 侧规整过（SamplingParams.normalizeSeed）：绝不会等于 LLAMA_DEFAULT_SEED，
        // 否则 llama_sampler_init_dist 会当成「随机种子」，与调用方指定的固定 seed 语义反转。
        llama_sampler_chain_add(ch, llama_sampler_init_dist((uint32_t) seed));
    }
    S.smpl = ch;
    return JNI_TRUE;
}

// startCompletion(prompt, maxTokens): 清 KV -> tokenize(含 BOS) -> 分块 decode
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeStartCompletion(
        JNIEnv * env, jclass, jstring jprompt, jint maxTokens) {
    JNI_SPAN("startCompletion");
    if (!S.ctx || !S.smpl) { jp("<< startCompletion 无 ctx/smpl -> false\n"); return JNI_FALSE; }
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
    jp(">> applyChatTemplate(无 tools) n=%d\n", roles ? (int) env->GetArrayLength(roles) : -1);
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
        // 原写法 `rp && jr` 中 jr 是冗余条件（rp 非空已蕴含 jr 非空），
        // 真正的后果在下面：jstring 可能被复用（jr == jc），无条件 DeleteLocalRef
        // 两次等于释放两次 —— CheckJNI 下直接 abort 掉进程。
        if (rp) env->ReleaseStringUTFChars(jr, rp);
        if (cp) env->ReleaseStringUTFChars(jc, cp);
        if (jr) env->DeleteLocalRef(jr);
        if (jc && jc != jr) env->DeleteLocalRef(jc);
    }
    std::vector<llama_chat_message> msgs;
    msgs.reserve(n);
    for (jsize i = 0; i < n; i++) msgs.push_back({ rs[i].c_str(), cs[i].c_str() });
    // 预分配按上游 common/chat.cpp 同款算：Σ(role+content) + 其 1/4，而不是写死 4096。
    // 写死小缓冲 + 等长二次调用会让 `need > buf.size()` 的判定永真，见下方安全裁剪。
    size_t alloc = 0;
    for (jsize i = 0; i < n; i++) {
        size_t m = rs[i].size() + cs[i].size();
        alloc += m + m / 4;
    }
    std::vector<char> buf(alloc ? alloc : 256);
    int32_t need = llama_chat_apply_template(t.c_str(), msgs.data(), msgs.size(),
                                             addAss == JNI_TRUE, buf.data(), (int32_t) buf.size());
    if (need > (int32_t) buf.size()) {
        // llama.cpp 内部是 strncpy(buf, formatted.c_str(), length)：
        // 只有 length > 实际长度时才会补 NUL，等长/偏小时**不补**。
        // 故长度参数必须比 need 多留 1，且必须真的扩容后重调（照着旧长度重调等于白调）。
        buf.assign((size_t) need + 1, '\0');
        need = llama_chat_apply_template(t.c_str(), msgs.data(), msgs.size(),
                                         addAss == JNI_TRUE, buf.data(), (int32_t) buf.size());
    }
    if (need < 0) return nullptr;
    // 兜底裁剪：无论返回值多大都不得越过实际缓冲，否则会读越界构造 std::string。
    if ((size_t) need > buf.size()) need = (int32_t) buf.size();
    // 结尾的 \0 属于 llama.cpp 的 strncpy 填充，不是 prompt 内容，不能让模型看到。
    return new_string_utf8_safe(env, std::string(buf.data(), (size_t) need).c_str());
}

// ---- 工具调用（OpenAI tools / function calling）----
//
// llama_chat_apply_template（旧版轻量接口）**不认 tools**：它只接收 role/content 文本，
// 工具定义根本进不了 prompt，模型自然永远不会吐 tool_calls。要支持工具调用必须走
// common_chat_templates_apply —— 它按模板的 tools 分支渲染，返回的 prompt 里带上工具清单，
// 同时给出 grammar / parser 等解析参数。
//
// ══════════════════════════════════════════════════════════════════════════
// ABI 说明（这层以前缺失，是"带 tools 必闪退"的根因）
// ══════════════════════════════════════════════════════════════════════════
// common_chat_* 这一族在 librnllama*.so 里是**带 C++ ABI 的符号**，参数含
// std::string / std::vector / nlohmann::ordered_json。它们的 mangled name 与
// 结构体字段偏移都由 STL 决定。扫过 vendor 的 4 个变体：__ndk1 符号 2413~2415 个，
// __cxx11 **0** 个 —— 库是 libc++（ABI v1，非 alternate layout）编的。
//
// 因此本文件必须：① 在 NDK + libc++ 下编译（CMake 已设 -DANDROID_STL=c++_static）；
// ② 用与库**同源**的 chat.h（vendor v1.12.2 配套的那份裁剪头）。
// 这两条现在由 chat_abi.h 的 static_assert 在编译期钉死，
// 任何一条不成立都编不过 —— 而不是等真机 SIGSEGV 之后再回来猜。
//
// 结构体尺寸已核对：sizeof(common_chat_templates_inputs) == 168
// （原 PR 描述里写的 208 是按 libstdc++ 规则算的，属另一个 ABI 的数值，已由断言纠正。）

// 把 "解析失败" 收敛成一个哨兵：调用方只需比对字符串，不必区分异常类型。
static const char * const kNoToolCalls = "null";

// 渲染带 tools 的 prompt。
//
// 返回：渲染后的 prompt 字符串；无模板 / 渲染失败 → null。
// 关键约束：**绝不向上抛**。C++ 异常一旦穿过 JNI 帧就是 UB，
// NDK 下通常直接 std::terminate → SIGABRT，本函数的 catch 拦不住
// （异常是在库内部抛的，穿过两层帧才到这里；而 ggml 的 abort 更是拦不住，
//  所以下面还加了"模板有没有给出解析器"的前置校验）。
static jstring applyChatTemplateToolsImpl(
        JNIEnv * env, jstring jtmpl, jobjectArray roles, jobjectArray contents,
        jstring jtoolsJson, jstring jtoolChoice, jboolean parallelToolCalls, jboolean addAss) {
    if (!S.model) { jp("<< applyChatTemplateTools 无模型 -> null\n"); return nullptr; }
    const char * tmplC = jtmpl ? env->GetStringUTFChars(jtmpl, nullptr) : nullptr;
    std::string tmpl = tmplC ? tmplC : "";
    if (tmplC) env->ReleaseStringUTFChars(jtmpl, tmplC);

    const jsize n = roles ? env->GetArrayLength(roles) : 0;
    std::vector<std::string> rs, cs;
    rs.reserve(n); cs.reserve(n);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto jc = (jstring) env->GetObjectArrayElement(contents, i);
        const char * rp = jr ? env->GetStringUTFChars(jr, nullptr) : nullptr;
        const char * cp = jc ? env->GetStringUTFChars(jc, nullptr) : nullptr;
        rs.emplace_back(rp ? rp : "");
        cs.emplace_back(cp ? cp : "");
        if (rp) env->ReleaseStringUTFChars(jr, rp);
        if (cp) env->ReleaseStringUTFChars(jc, cp);
        // 两个局部引用必须各自判重再放：org.json 可能让相同内容复用同一个 jstring，
        // jr == jc 时无条件 DeleteLocalRef 两次等于释放两次，CheckJNI 会直接 abort 掉进程。
        if (jr) env->DeleteLocalRef(jr);
        if (jc && jc != jr) env->DeleteLocalRef(jc);
    }

    const char * tc = jtoolsJson ? env->GetStringUTFChars(jtoolsJson, nullptr) : nullptr;
    std::string toolsJson = tc ? tc : "";
    if (tc) env->ReleaseStringUTFChars(jtoolsJson, tc);
    const char * ch = jtoolChoice ? env->GetStringUTFChars(jtoolChoice, nullptr) : nullptr;
    std::string toolChoice = ch ? ch : "";
    if (ch) env->ReleaseStringUTFChars(jtoolChoice, ch);

    // 1) 用模型自带模板初始化（模板为空时由 chat 层自动挑选内置模板）
    //    走带 tmpl_tools 的重载：它把"工具渲染用哪个模板"与"普通对话用哪个"分开，
    //    Qwen 等模板的 tools 分支在 tmpl_tools 里，只传 tmpl 会拿到不带工具的变体。
    jp("[tools] >>> templates_init(tmpl_len=%zu)\n", tmpl.size());
    common_chat_templates_ptr tmpls;
    try {
        tmpls = common_chat_templates_init(S.model, tmpl.empty() ? "" : tmpl);
    } catch (const std::exception & e) {
        jp("[tools] <<< templates_init 异常: %s\n", e.what());
        jlog("[tools] templates_init 异常: %s", e.what());
        return nullptr;
    } catch (...) {
        jp("[tools] <<< templates_init 未知异常\n");
        return nullptr;
    }
    if (!tmpls) { jp("[tools] <<< templates_init -> NULL\n"); jlog("[tools] templates_init 失败"); return nullptr; }
    jp("[tools] <<< templates_init ok\n");

    common_chat_templates_inputs in;
    in.use_jinja = true;
    in.add_generation_prompt = (addAss == JNI_TRUE);
    in.messages.reserve(n);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = rs[i];
        m.content = cs[i];
        in.messages.push_back(std::move(m));
    }

    // 2) 解析 tools 数组（OpenAI oaicompat 格式）
    const bool hasTools = !toolsJson.empty() && toolsJson != kNoToolCalls;
    if (hasTools) {
        jp("[tools] >>> tools JSON parse(len=%zu) 与 oaicompat 转换\n", toolsJson.size());
        try {
            auto j = nlohmann::ordered_json::parse(toolsJson);
            // 必须显式判 is_array：传 {"a":1} 时 nlohmann 不抛异常，会被当成空工具列表，
            // 渲染出的 prompt 里没有工具定义，客户端却以为工具已生效 -> 永远收不到 tool_calls 且无任何报错。
            if (!j.is_array()) {
                jp("[tools] <<< tools 不是数组 -> null\n");
                jlog("[tools] tools 不是数组");
                return nullptr;
            }
            in.tools = common_chat_tools_parse_oaicompat(j);
        } catch (const std::exception & e) {
            jp("[tools] <<< tools 解析异常: %s\n", e.what());
            jlog("[tools] 解析 tools 失败: %s", e.what());
            return nullptr;
        } catch (...) {
            jp("[tools] <<< tools 解析未知异常\n");
            jlog("[tools] 解析 tools 未知异常");
            return nullptr;
        }
        // tool_choice 归一。这里有**两道**必须在的防线，缺一就闪退：
        //
        // ① 空串/空值前置判断：请求方（Kotlin）未传 tool_choice 时，经 JNI 只能是空串。
        //    而 vendor 库的 common_chat_tool_choice_parse_oaicompat("") 是
        //    **throw std::invalid_argument("Invalid tool_choice: ")`，不是返回 AUTO**
        //    （真机日志里 libc++abi 原样打出的就是这句，冒号后为空即证据）。
        //    旧注释写「空串命中的是内置模板里的空分支，语义等同 auto」，与库实现相反，已删。
        //    所以空串必须在这里就换成 AUTO，绝不能让空串进库。
        //
        // ② try/catch：即使是非空串，库对未识别值同样 throw。异常从库内 unwind 穿过本
        //    JNI 帧 = UB → std::terminate → SIGABRT，本函数外层的 catch 拦不住（异常诞生在
        //    更里层，且这是"穿过 JNI 边界"的问题，不是"没人 catch"的问题）。
        //    因此这一跳必须就地 catch 并降级成 AUTO，绝不外抛。
        if (toolChoice.empty()) {
            in.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
            jp("[tools] tool_choice 为空 -> 显式 AUTO（库对空串是抛异常，不可直传）\n");
        } else {
            try {
                // 按头文件口径只有 const std::string& 一个重载（c_str() 会隐式构造临时串，
                // 徒增一次分配且掩盖真实签名），这里直接传 std::string 本身。
                in.tool_choice = common_chat_tool_choice_parse_oaicompat(toolChoice);
            } catch (const std::exception & e) {
                jp("[tools] <<< tool_choice 解析异常: %s -> 退回 AUTO\n", e.what());
                jlog("[tools] tool_choice \"%s\" 无法识别，退回 AUTO: %s", toolChoice.c_str(), e.what());
                in.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
            } catch (...) {
                jp("[tools] <<< tool_choice 解析未知异常 -> 退回 AUTO\n");
                in.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
            }
        }
        in.parallel_tool_calls = (parallelToolCalls == JNI_TRUE);
        jp("[tools] <<< oaicompat ok: n=%zu choice=%s parallel=%d\n",
           in.tools.size(), toolChoice.c_str(), (int) in.parallel_tool_calls);
    } else {
        jp("[tools] 无 tool_choice/工具数组，按纯文本模板渲染\n");
    }

    // 3) 渲染 —— 这里是**唯一**会进库的地方，也是历史上真机闪退的现场。
    jp("[tools] >>> common_chat_templates_apply（库内首跳，崩点曾在此）\n");
    common_chat_params cp;
    try {
        cp = common_chat_templates_apply(tmpls.get(), in);
    } catch (const std::exception & e) {
        jp("[tools] <<< templates_apply 抛异常: %s\n", e.what());
        jlog("[tools] templates_apply 异常: %s", e.what());
        return nullptr;
    } catch (...) {
        jp("[tools] <<< templates_apply 未知异常\n");
        jlog("[tools] templates_apply 未知异常");
        return nullptr;
    }
    jp("[tools] <<< templates_apply ok: prompt_len=%zu format=%s(%d) parser_len=%zu\n",
       cp.prompt.size(), common_chat_format_name(cp.format), (int) cp.format, cp.parser.size());
    if (cp.prompt.empty()) { jp("[tools] 渲染结果为空 -> null\n"); jlog("[tools] 渲染结果为空"); return nullptr; }

    // 前置校验：模板没给出解析器时，后面 common_chat_parse 那条路会 GGML_ASSERT
    // （那是 abort 不是 throw，catch 拦不住）。这里提前判掉，按纯文本处理。
    // 附注：**不能**把这个判据当成"渲染成功"的必要条件 —— 有些模板确实不生成 PEG，
    //      那种情况下 prompt 仍然是对的，所以只在 parse 侧用它做闸门，这里只记录。
    if (cp.parser.empty()) jp("[tools] 提示：模板未生成解析器，后续 parse 将按纯文本处理\n");

    return new_string_utf8_safe(env, cp.prompt.c_str());
}

// ══════════════════════════════════════════════════════════════════════════
// JNI 边界 catch-all：**这是"任何参数组合都不能让服务端进程死"的最后一道墙**
// ══════════════════════════════════════════════════════════════════════════
// C++ 异常穿过 JNI 帧是 UB，NDK 下通常 std::terminate -> SIGABRT。
// 上面各个 Impl 里虽然已经对已知的库调用逐点 try/catch，但"已知"是会漏的：
// vendor 预编译库内部还有多少条 throw 路径、将来换一版模板会不会新增，
// 都不能靠逐个枚举来保证。所以入口这一层再兜一次，任何漏网异常都收敛成
// 返回值（null / kNoToolCalls），绝不外抛 —— 宁可工具调用降级成纯文本，
// 也不让作为服务端的 App 进程消失。
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeApplyChatTemplateTools(
        JNIEnv * env, jclass, jstring jtmpl, jobjectArray roles, jobjectArray contents,
        jstring jtoolsJson, jstring jtoolChoice, jboolean parallelToolCalls, jboolean addAss) {
    JNI_SPAN("applyChatTemplateTools");
    try {
        return applyChatTemplateToolsImpl(env, jtmpl, roles, contents,
                                          jtoolsJson, jtoolChoice, parallelToolCalls, addAss);
    } catch (const std::exception & e) {
        jp("!! applyChatTemplateTools 边界捕获异常: %s -> null\n", e.what());
        jlog("[tools] 渲染边界捕获异常，按纯文本处理: %s", e.what());
        return nullptr;
    } catch (...) {
        jp("!! applyChatTemplateTools 边界捕获未知异常 -> null\n");
        jlog("[tools] 渲染边界捕获未知异常，按纯文本处理");
        return nullptr;
    }
}

// 从模型输出文本里解析工具调用，返回 OpenAI 兼容 JSON 字符串；无工具调用返回 "null"。
//
// 为什么要 native 解析：tool_calls 的语法由**模板**决定（Qwen 用 <tool_call>，Llama 用
// [TOOL_CALLS]，Mistral 用 [TOOL_CALLS] 变体…）。common_chat_parse 会按模板推导出 PEG
// 解析器，把任意模板的语法统一归一成 common_chat_msg.tool_calls —— 在 Kotlin 侧用正则
// 硬编码任何一种语法都会漏掉其它模板。
//
// 返回值形如：{"content":"...","toolCalls":[{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}]}
// parse 失败返回 "null"（调用方按纯文本处理）。
static jstring parseToolCallsImpl(
        JNIEnv * env, jstring jtext, jstring jtoolsJson, jstring jtmpl) {
    if (!S.model || !jtext) { jp("<< parseToolCalls 无模型/无文本 -> null\n"); return env->NewStringUTF(kNoToolCalls); }

    const char * tp = env->GetStringUTFChars(jtext, nullptr);
    std::string text = tp ? tp : "";
    if (tp) env->ReleaseStringUTFChars(jtext, tp);

    const char * mp = jtmpl ? env->GetStringUTFChars(jtmpl, nullptr) : nullptr;
    std::string tmplOverride = mp ? mp : "";
    if (mp) env->ReleaseStringUTFChars(jtmpl, mp);

    const char * tcp = jtoolsJson ? env->GetStringUTFChars(jtoolsJson, nullptr) : nullptr;
    std::string toolsJson = tcp ? tcp : "";
    if (tcp) env->ReleaseStringUTFChars(jtoolsJson, tcp);

    // ---- 探针：把 parse 的全部输入特征落盘（崩溃发生在下面几行之内时，这是唯一现场） ----
    // 只打长度/头部/模板是否为内置 vs 自定义，不整段 dump（工具清单可能很长，但头部足够定位）。
    {
        bool hasTools = !toolsJson.empty() && toolsJson != kNoToolCalls;
        bool ov = !tmplOverride.empty();
        jp("[parse] text_len=%zu tools_len=%zu tmpl_override=%s(%zu) text_head=%.200s\n",
           text.size(), toolsJson.size(), ov ? "自定义" : "空=用内置", tmplOverride.size(),
           text.substr(0, 200).c_str());
        jp("[parse] tools_head=%.300s\n", hasTools ? toolsJson.substr(0, 300).c_str() : "(无)");
    }

    // 用与渲染时**同一个模板**建 templates：解析器是按模板推导出来的 PEG，
    // 两边模板不一致（例如 App 里选了自定义模板、这里却让 chat 层自选内置）会解析错。
    jp("[parse] >>> templates_init\n");
    common_chat_templates_ptr tmpls;
    try {
        tmpls = common_chat_templates_init(S.model, tmplOverride.empty() ? "" : tmplOverride);
    } catch (const std::exception & e) {
        jp("[parse] <<< templates_init 异常: %s\n", e.what());
        return env->NewStringUTF(kNoToolCalls);
    } catch (...) {
        jp("[parse] <<< templates_init 未知异常\n");
        return env->NewStringUTF(kNoToolCalls);
    }
    jp("[parse] <<< templates_init -> %s\n", tmpls ? "ok" : "NULL");
    if (!tmpls) return env->NewStringUTF(kNoToolCalls);

    common_chat_templates_inputs in;
    in.use_jinja = true;
    in.add_generation_prompt = false;
    if (!toolsJson.empty() && toolsJson != kNoToolCalls) {
        try {
            auto j = nlohmann::ordered_json::parse(toolsJson);
            if (j.is_array()) {
                in.tools = common_chat_tools_parse_oaicompat(j);
                jp("[parse] oaicompat tools 解析 ok, n=%zu\n", in.tools.size());
            } else {
                jp("[parse] tools 不是数组 -> 空工具列表\n");
            }
        } catch (const std::exception & e) {
            jp("[parse] tools JSON 解析异常: %s\n", e.what());
        } catch (...) { jp("[parse] tools JSON 解析未知异常\n"); }
    }

    // 只有先 apply 一次，format / parser 字段才会被填好 —— parse 依赖它挑选语法
    jp("[parse] >>> templates_apply 开始（库内首跳）\n");
    common_chat_params cp;
    try {
        cp = common_chat_templates_apply(tmpls.get(), in);
    } catch (const std::exception & e) {
        jp("[parse] <<< templates_apply 抛异常: %s\n", e.what());
        jlog("[tools] parse 前 apply 异常: %s", e.what());
        return env->NewStringUTF(kNoToolCalls);
    } catch (...) {
        jp("[parse] <<< templates_apply 未知异常\n");
        return env->NewStringUTF(kNoToolCalls);
    }

    // 判据：parser 为空 = 模板没给出 PEG 语法，common_chat_parse 会走 GGML_ASSERT（abort）。
    // 探针开启时输出 parser 头 120 字符以便复核判据本身是否可用。
    if (cp.parser.empty()) {
        jp("[parse] <<< templates_apply ok 但模板未生成解析器 -> 返回 null\n");
        jlog("[tools] 模板未生成解析器，跳过解析（按纯文本处理）");
        return env->NewStringUTF(kNoToolCalls);
    }
    jp("[parse] <<< templates_apply ok  format=%s(%d)  parser_len=%zu  parser_head=%.120s\n",
       common_chat_format_name(cp.format), (int) cp.format, cp.parser.size(),
       cp.parser.substr(0, 120).c_str());

    // 构造 parser_params。**必须自己把 PEG 装回去**：
    //   . common_chat_parser_params(const common_chat_params &) 只搬 format 与
    //     generation_prompt（见 chat.h），parser 与 message_delimiters 都不搬；
    //   . common_chat_parse(input, partial, pp) 的实现是
    //         return common_chat_peg_parse(pp.parser, input, partial, pp);
    //     而 common_chat_peg_parse 里：
    //         parser = src_parser.empty() ? 纯内容解析器 : src_parser;
    //     即 pp.parser 为空时**降级成"整段都是 content"**，
    //     于是 tool_calls 恒为 0、content 恒为全文（真机日志正是 content_len=text_len+22、
    //     tool_calls=0，那 +22 是 generation_prompt 被当前缀拼进 effective_input 的长度）。
    //   . cp.parser 不是 PEG 本体，而是 PEG 的**序列化串**（`common_peg_arena::save()` 的产物，
    //     形如 {"parsers":[...]}），上游 autoparser 也是先 `arena.load(auto_params.parser)`
    //     再用的。所以这里必须显式 load。
    common_chat_parser_params pp(cp);
    pp.parse_tool_calls = true;
    try {
        pp.parser.load(cp.parser);
    } catch (const std::exception & e) {
        jp("[parse] <<< PEG load 异常: %s -> 按纯文本处理\n", e.what());
        jlog("[tools] PEG load 异常（按纯文本处理）: %s", e.what());
        return env->NewStringUTF(kNoToolCalls);
    } catch (...) {
        jp("[parse] <<< PEG load 未知异常 -> 按纯文本处理\n");
        jlog("[tools] PEG load 未知异常（按纯文本处理）");
        return env->NewStringUTF(kNoToolCalls);
    }
    // load 完再确认一次：装进去的是真有结构的解析器，而不是空壳。
    // 空壳会让 common_chat_parse 静默降级成"整段 content"，tool_calls 恒为 0 ——
    // 那种"不崩但永远解析不出工具"比崩溃更难查，所以这里显式判空并留痕。
    if (pp.parser.empty()) {
        jp("[parse] <<< PEG load 后仍为空（save/load 不匹配）-> 按纯文本处理\n");
        jlog("[tools] PEG load 后仍为空，跳过解析");
        return env->NewStringUTF(kNoToolCalls);
    }
    jp("[parse] parser_params 构造完成（已 load PEG，root=%d n=%zu）\n",
       (int) pp.parser.root(), pp.parser.size());

    common_chat_msg msg;
    jp("[parse] >>> common_chat_parse 开始（如果这一行后面没有 <<<，崩点就是它）\n");
    try {
        msg = common_chat_parse(text, /*is_partial=*/false, pp);
    } catch (const std::exception & e) {
        jp("[parse] <<< common_chat_parse 抛异常: %s\n", e.what());
        jlog("[tools] parse 异常（按纯文本处理）: %s", e.what());
        return env->NewStringUTF(kNoToolCalls);
    } catch (...) {
        jp("[parse] <<< common_chat_parse 未知异常\n");
        jlog("[tools] parse 未知异常（按纯文本处理）");
        return env->NewStringUTF(kNoToolCalls);
    }
    jp("[parse] <<< common_chat_parse ok  content_len=%zu tool_calls=%zu\n",
       msg.content.size(), msg.tool_calls.size());

    if (msg.tool_calls.empty()) { jp("[parse] 无工具调用 -> 返回 null\n"); return env->NewStringUTF(kNoToolCalls); }

    // 归一成扁平 JSON，Kotlin 侧零依赖（org.json）即可消费。
    // 多数模板不吐 call id（OpenAI 的 call_xxx 是服务端生成的），此处补一个按序稳定的 id：
    // 客户端要把它回填进后续 role=tool 消息才能对上号，留空会让上游 SDK 拼不出合法请求。
    nlohmann::ordered_json out;
    out["content"] = msg.content;
    out["toolCalls"] = nlohmann::ordered_json::array();
    for (size_t i = 0; i < msg.tool_calls.size(); i++) {
        const auto & t = msg.tool_calls[i];
        nlohmann::ordered_json c;
        c["id"]        = t.id.empty() ? ("call_" + std::to_string(i)) : t.id;
        c["name"]      = t.name;
        c["arguments"] = t.arguments;
        out["toolCalls"].push_back(std::move(c));
    }
    std::string dumped = out.dump();
    jp("[parse] 归一化完成 len=%zu\n", dumped.size());
    return new_string_utf8_safe(env, dumped.c_str());
}

// 同 nativeApplyChatTemplateTools：入口兜底，解析链路的任何漏网异常都转成 "null"，
// 调用方按纯文本处理，绝不让异常穿过 JNI 帧。
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeParseToolCalls(
        JNIEnv * env, jclass, jstring jtext, jstring jtoolsJson, jstring jtmpl) {
    JNI_SPAN("parseToolCalls");
    try {
        return parseToolCallsImpl(env, jtext, jtoolsJson, jtmpl);
    } catch (const std::exception & e) {
        jp("!! parseToolCalls 边界捕获异常: %s -> null\n", e.what());
        jlog("[tools] 解析边界捕获异常，按纯文本处理: %s", e.what());
        return env->NewStringUTF(kNoToolCalls);
    } catch (...) {
        jp("!! parseToolCalls 边界捕获未知异常 -> null\n");
        jlog("[tools] 解析边界捕获未知异常，按纯文本处理");
        return env->NewStringUTF(kNoToolCalls);
    }
}

JNIEXPORT void JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeAbort(JNIEnv *, jclass) { jp(">> abort\n"); S.abort = true; }

JNIEXPORT void JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeFreeSampler(JNIEnv *, jclass) {
    if (S.smpl) { llama_sampler_free(S.smpl); S.smpl = nullptr; }
}


JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeUnloadModel(JNIEnv *, jclass) { jp(">> unloadModel\n"); unloadModelInternal(); }

} // extern "C"

static void unloadModelInternal() {
    if (S.smpl)  { llama_sampler_free(S.smpl);   S.smpl = nullptr; }
    if (S.ctx)   { llama_free(S.ctx);            S.ctx = nullptr; }
    if (S.model) { llama_model_free(S.model);    S.model = nullptr; S.vocab = nullptr; }
    S.n_ctx = 0; S.n_used = 0; S.n_rem = 0;
    S.pending.clear();
}

