// llama_jni.cpp — Kotlin <-> llama.cpp JNI 桥（rnllama 构建：CPU + OpenCL + Hexagon HTP）
// 设计：生成循环在 Kotlin 线程驱动，JNI 每次 step 返回一个 token piece。
// prompt 预填充分块 decode；**KV 前缀复用**见下方 `kv_prefix.h` 与 nativeStartCompletion：
// 本轮 prompt 若与上一轮进过 KV 的 token 序列共享前缀，就直接留用那段 KV、
// 只 prefill 多出来的尾巴，不再每轮从零重算（多轮对话/带 tools 的固定段收益最大）。
// 复用不成立时（换模型 / 无公共前缀 / prompt 过长）行为与"每轮全清"逐字节相同。
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <cstdarg>
#include <vector>
#include <cctype>
#include <cstdio>
#include <variant>          // std::get_if：从 PEG arena 现场取根节点字面量（见 parse 输入对齐）
#include <atomic>           // Session::abort / round_epoch：跨线程置位/读取必须是原子（见那边注释）
#include <csignal>
#include <ctime>
#include <signal.h>         // sigaltstack / stack_t：SA_ONSTACK 的前提（见 probe_install_alt_stack）
#include <cerrno>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>   // SYS_gettid
#include <sys/types.h>
#include <sys/wait.h>      // waitpid（探针自举读系统属性）
#include <sys/mman.h>      // mmap/madvise：归还库 MAP_POPULATE 预填充的权重页（见 release_populated_pages）
#include <sys/stat.h>      // fstat：GGUF 预读前的前置检查（见 gguf_header_precheck）
// malloc_trim：让**分配器自己**把已 free、但还没归还内核的匿名页交还
// （见 nativeReclaimReleasedHeap）。只在 glibc 上叫这个名；Android(bionic)
// 没有等价接口 —— 那边就**什么都不做**（理由见本函数上方 ISSUE #274 那段：
// 绕过分配器去猜哪段匿名内存是空闲的，会抹掉活对象）。
#if defined(__GLIBC__)
#include <malloc.h>
#endif

#include "llama.h"
#include "ggml-backend.h"   // dev_count/dev_get/dev_name，枚举 tensor_split 下标
#include "gguf.h"
#include "utf8_safe.h"
#include "stop_sequences.h"   // stop / stop_sequences 匹配语义（纯函数，宿主侧有单测）
#include "kv_prefix.h"        // KV 前缀复用的切分语义（纯函数，宿主侧有单测）
#include "embed_text.h"      // 向量嵌入的归一化/池化/检索语义（纯函数，宿主侧有单测）
#include "rag_index.h"       // 知识库索引的切块/指纹/头布局（纯函数，宿主侧有单测）
                             // 阶段 1 的**判定**部分放在这里，是为了让"索引与模型不匹配"
                             // 这类**不崩不报错**的失效能被宿主侧单测钉住；
                             // 落盘/读盘在 Kotlin 侧（RagIndexStore.kt）。
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

// 属性名（`getprop` 可读）。写成常量而不是各处字面量：
// 判据脚本与文档都按这同一个名字对齐。
//
// ⚠ 0.9.125 起**不再是权重重排开关的判定通道**（真机上实测读不到，见
// `model_use_extra_bufts` 上方那段）。保留常量只为一件事：覆盖通道的**原始读数**
// 要进日志，好让"属性到底读到了什么"有据可查。
static const char * const kPropExtraRepack = "lm_extra_repack";

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

// 探针工具（escape_for_probe / 取 PEG 根节点字面量 / generation_prompt 对齐）
// 单独拆成一份头文件：宿主下能独立编译并跑单测。
// 为什么值得拆 —— 这三段都是**纯字符串/纯数据**运算，却是本轮修复的核心判定；
// 留在本文件里就只能靠 review 肉眼保证（本文件在宿主上编不过：jni.h / llama.h /
// chat.h 都是 NDK 与真机的）。拆出去之后真机与单测共用同一份实现。
#include "probe_util.h"
// GGUF 预读前的文件头判据（纯逻辑，宿主单测跑的是同一份）：见 gguf_precheck.h
#include "gguf_precheck.h"
// 探针开关判据（显式关闭 / 自举缓冲只记不写）：纯逻辑，宿主可编，
// 与真机编进去的是同一份 —— 见该头文件开头的理由。
#include "probe_flag.h"


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
// 不依赖任何 JNI 调用、不依赖 Kotlin 传参，只读系统属性决定**往哪写**、
// 加载期故障就记哪。JNI 入口这次调用若是第一次，就把「Kotlin 已传过来的目录」
// 升级成正式落盘配置。
//
// ⚠ 自举阶段**只记不写**（本轮修复 D-4 确立，此后不得回退）：
//   自举与正式落盘是**两个不同的文件**（自举只能按系统属性/包名猜目录，
//   而 Kotlin 传的是 filesDir/logs，真机上通常不是同一个路径）。
//   旧写法在自举时就 `probe_raw()` 落盘，随后把自举缓冲清零，
//   于是「回灌到正式文件」永远只写 0 字节 —— 一个**不可能成立的组合**。
//   现在自举只往 g_boot_log 里记，第一次 nativeProbeInit 时统一落进正式文件；
//   若探针压根没启用，这些事件留在缓冲里由 recall 交回 logcat（仍然不写文件）。

static const char * const kProbeDirProps[] = {
    "debug.localinference.probe.dir",   // 显式指定落盘目录（不依赖 App 上下文）
    "debug.localinference.probe",       // 兼容简写：值本身即目录
    "localinference.probe.dir",
};
// 受控自举开关：**只在「显式关闭」方向生效**（见 probe_flag_off）。
// 历史上这组常量是死代码 —— 注释写着「JNI_OnLoad 时读取」，全仓库 0 处读取，
// 于是用户在设置页关掉探针，native 侧照样全量落盘（D-1）。
// 现在它真的参与判定：值属于 kProbeOffValues 即视为「显式关闭」。
static const char * const kProbeFlagProps[] = {
    "debug.localinference.probe.on",    // 受控自举开关（JNI_OnLoad 时读取）
    "localinference.probe.on",
};
// 显式关闭的取值集合（不区分大小写）。**故意不接受 "on" 这个方向**：
// 探针是取证工具，任何一次误判为「开着」都会静默写盘并换掉日志 sink，
// 而「该开却没开」至少还能由 Kotlin 侧（settings 里的用户意图）打开。
// 所以这里的判据是单向的 fail-safe：只有明确的否才否决。
static const char * const kProbeOffValues[] = {"0", "false", "no", "off", "disable", "disabled", "null", "nil", "none"};

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
// 自举期事件（含失败原因）。**只记不写** —— 唯一消费者是第一次 nativeProbeInit
// （probe_bootstrap_flush）；探针若始终没启用，则由 probe_bootstrap_recall 交回 logcat。
// 以前这里注释写的是"正式探针落盘时回灌"，但实现里自举当场就 probe_raw 掉并清零，
// 那个"回灌"恒为 0 字节 —— 见 probe_flag.h 的 ②。
static char g_boot_log[1024];
static size_t g_boot_log_len = 0;
static int g_boot_flush_tries = 0;      // 自举事件交代过几次（正常最多 1 次）
static bool g_boot_off_by_prop = false; // 自举开关属性显式要求关闭

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

// 自举事件落进正式文件：**只此一次**。
// 旧实现里这一步恒为 0 字节（自举当场已 probe_raw 掉并清零），于是 Kotlin 侧那条
// 排查指令「probe-native.log 里应该有一条 [boot]」从来没兑现过 —— 而它恰恰被写成
// 「一条都没有才说明 native 没跑起来」，方向正好是反的（D-4）。
// 现在：自举只往缓冲里记，这里第一次（且仅这一次）写到已打开的正式 fd 上。
// 判据本体在 probe_flag.h 的 probe_bootstrap_write()，宿主可真编真跑。
static void probe_bootstrap_flush() {
    if (!g_probe_attempted) return;
    // 交付字节数的**唯一口径**是 probe_bootstrap_write（宿主可真编真跑的那份判据）：
    // 它另外承担「同一段现场只交代一次」—— 旧写法无条件下发，节流靠调用方自觉，
    // 而调用方（nativeProbeInit）在进程里可以被调到不止一次。
    size_t n = probe_bootstrap_write(g_boot_log, g_boot_log_len, &g_boot_flush_tries, 1);
    if (n > 0) probe_raw(g_boot_log, n);
}

// 探针**始终没被启用**时，把自举事件交回 logcat。
// 为什么必须交代：自举可能在系统属性指定的目录里留下了「加载期故障」的记录，
// 而这条记录只存在于 g_boot_log 里、任何文件里都没有；用户上报「探针不起作用」时
// 「native 到底自举到了没有、往哪写、为什么没写」正是第一现场。
// 只打 logcat、**不建文件** —— 与 D-1 那条「别在用户不知情时落盘」是同一条纪律。
static void probe_bootstrap_recall() {
    if (!g_probe_attempted || g_boot_log_len == 0) return;
    if (g_boot_flush_tries > 0) return;   // 已经落过盘了，别重复刷屏
    g_boot_flush_tries++;
    if (g_boot_off_by_prop) {
        LOGE("probe bootstrap: 开关属性显式要求关闭，自举已按关闭处理（不写文件）；"
             "早期事件如下（%zu 字节）", g_boot_log_len);
    } else {
        LOGE("probe bootstrap: 探针未启用（设置页未开），自举只记不写；"
             "早期事件如下（%zu 字节）", g_boot_log_len);
    }
    // 逐段交回：logcat 单条上限约 4KB，自举缓冲最大 1KB，但仍然分段打，
    // 免得一行超长被截断（截断掉的恰好是失败原因那一行就有意思了）。
    for (size_t off = 0; off < g_boot_log_len; ) {
        char line[256];
        size_t n = 0;
        while (off + n < g_boot_log_len && n < sizeof(line) - 1 && g_boot_log[off + n] != '\n') n++;
        if (n > sizeof(line) - 1) n = sizeof(line) - 1;
        for (size_t i = 0; i < n; i++) line[i] = g_boot_log[off + i];
        line[n] = '\0';
        if (n > 0) LOGE("probe boot: %s", line);
        off += n;
        if (off < g_boot_log_len && g_boot_log[off] == '\n') off++;
    }
}

// ── 探针文件的重置：本会话一份、上一会话一份 ────────────────────────────────
//
// 为什么必须重置：探针 fd 用的是 O_APPEND，文件从 App 装好到卸载**只有一个**，
// 既没有重置也没有上限 —— 它不是"日志"，是 native 全量输出 + stderr 遗言 +
// 逐 token 原始流三样一起往里灌。一次长回答就是上万行，几百 MB 是迟早的事。
//
// 为什么是 rename 而不是 unlink：崩前那几行恰恰在**前面**。整份删掉等于把上一次
// 现场（用户最可能想导出的那一份）扔掉，所以本代变更成 `probe-native.prev.log`，
// 下一代再把它覆盖掉 —— 只留两代，但一代都不白丢。
//
// 为什么在这里（nativeProbeInit 开文件前）：那是全进程唯一打开探针文件的点，
// 重置跟着它走 = 每个进程生命周期恰好一次，不需要任何额外的"启动"信号。
//
// 失败**不阻断**取证：rename 失败（目标被占/文件系统不配合）只是留着上一代，
// 也留一行痕迹 —— 绝不因为重置不了就不开探针。
static void probe_rotate_prev(const char * path) {
    std::string prev = std::string(path) + ".prev";
    if (rename(path, prev.c_str()) != 0) {
        // ENOENT = 上一代不存在（首次启动/已重置），是正常态，不打日志免得刷屏。
        // 其它 errno 才说明"这次没重置成"，必须留痕。
        if (errno != ENOENT)
            LOGE("probe: 上代会话文件未能滚动（%s → %s，errno=%d %s）；"
                 "本会话照常开，但上一段现场仍在当前文件里", path, prev.c_str(), errno, strerror(errno));
    }
}

// 自举：**只决定「往哪写」并记录做了什么，不打开文件、不落盘、不改 g_probe_on**。
// 打开文件与 g_probe_on 的唯一权威是 nativeProbeInit（用户意图）。
// 返回值 = 是否执行了自举（首次为 true），与「探针最终开没开」无关。
static bool probe_bootstrap() {
    if (g_bootstrapped) return false;
    g_bootstrapped = true;
    g_probe_attempted = true;

    probe_boot_note("[boot] native 探针自举（JNI_OnLoad 阶段，未依赖任何 JNI 调用）\n");

    // ── 受控开关：这里是 kProbeFlagProps 唯一的读取点（此前是死代码）──
    // 只看「值」不看「在不在」：`setprop debug.localinference.probe.on 0` 必须被读成关闭。
    // 只在「显式关闭」方向生效（fail-safe），见 probe_flag.h 的 ①。
    {
        const char * vals[kProbeFlagPropCount];
        char vbuf[kProbeFlagPropCount][512];
        size_t nv = 0;
        for (size_t i = 0; i < kProbeFlagPropCount; i++) {
            if (probe_getprop(kProbeFlagProps[i], vbuf[i], sizeof(vbuf[i]))) vals[nv++] = vbuf[i];
        }
        if (nv > 0 && probe_flag_off(vals, nv, kProbeOffValues,
                                     sizeof(kProbeOffValues) / sizeof(kProbeOffValues[0]))) {
            g_boot_off_by_prop = true;
        }
        probe_boot_note("[boot] 开关属性：读取 %zu/%zu 条，显式关闭=%s\n",
                        nv, (size_t) kProbeFlagPropCount, g_boot_off_by_prop ? "是" : "否");
    }

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
    if (dir) probe_boot_note("[boot] 目录来源=系统属性\n");
    else     probe_boot_note("[boot] 目录来源=包名默认推导\n");
    probe_boot_note("[boot] 拟落盘=%s（自举阶段不打开、不写入）\n", path);

    // 这里**故意**不 open。诊断只到「会往哪写」为止：
    //   · 打开与否由用户意图决定（nativeProbeInit）；
    //   · 先开一个再被接管，等于在用户没开探针时也建了一个文件 —— 正是 D-1。
    // 落盘若失败（目录不存在、无权限），由 nativeProbeInit 用**同一个** path 去 open
    // 并把 errno 与尝试过的候选目录写进 [boot]，可读性比在自举里静默退回更好的位置。
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
// 安装**只做一次**（幂等）。nativeProbeInit(on=true) 在本进程里可以被调到不止一次：
// `LlmEngine.init()` 顶上的 `if (loaded) return true` 只在 init **成功**后才成立，而
// startProbe 排在 backendInit 之前 —— backendInit 抛异常 / 加载失败时 loaded 仍为 false，
// 探针却已经装过一轮。用户再点一次"加载"，或服务再起一轮，就会走到第二次安装。
// 安装本身是覆盖式的（危险的是 g_old_* 被改写，见 probe_signal 的转发段）：
// 第二次装完 g_old_segv 里存的是**探针自己**，于是"链式转发"变成跳到自己 ——
// 信号现场一行都留不下，而症状与"根本没崩 signal"同形（排障者会去查错方向）。
static bool g_signals_installed = false;
// 备用信号栈（SA_ONSTACK 的**前提**，见 probe_install_signals）。
// 用静态数组而不是 malloc：栈溢出型 SIGSEGV 恰恰可能发生在堆已崩之后，
// 且 sigaltstack 必须挂在一块**从不被复用**的内存上（free 掉它等于埋雷）。
static const size_t kAltStackSize = 64 * 1024;
static char   g_alt_stack[kAltStackSize];
static bool   g_alt_stack_ready = false;

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
    } else if (old && old->sa_handler == SIG_IGN) {
        // ⚠ 此前这里是 `old->sa_handler(sig)`（对 SIG_IGN 来说等于 `(void(*)(int))1`，
        //    **不是**一个可以调用的函数），随后又补了一句 `_exit(128 + sig)` ——
        //    于是"本进程此前忽略这个信号"被**升级成致命退出**。
        // 文件头写的是"绝不吞掉信号，也不改变原有行为"，而这一支恰恰是替进程决定去死。
        // 真正需要 _exit 的只有上面 g_in_handler 那个**递归保护**（handler 自己又崩了）。
        // 这里直接返回：忽略就是忽略，语义原样成立。
        return;
    }
    // 原 handler 若正常返回，保持默认行为
    signal(sig, SIG_DFL);
    raise(sig);
}

// 装备用信号栈。**必须在设 SA_ONSTACK 之前**：SA_ONSTACK 的语义是
// "有备用栈就用它"，没有备用栈时它是一个 no-op —— handler 仍跑在那个**已经爆掉的栈**上。
// 而本项目最需要信号现场的恰好包括渲染 / PEG 解析的深递归，那类崩溃正是爆栈 SEGV：
// 只有 SA_ONSTACK 而没有 sigaltstack，等于在等一个永远不会来的现场。
static void probe_install_alt_stack() {
    if (g_alt_stack_ready) return;
    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp   = g_alt_stack;
    ss.ss_size = kAltStackSize;
    ss.ss_flags = 0;
    // MINSIGSTKSZ 是下限；小机器上也可能配不出，配不出就**不设 SA_ONSTACK**（见下）。
    if (kAltStackSize < (size_t) MINSIGSTKSZ) {
        LOGE("probe: 备用信号栈 %zu 字节 < MINSIGSTKSZ(%d)，跳过 sigaltstack", kAltStackSize, (int) MINSIGSTKSZ);
        return;
    }
    if (sigaltstack(&ss, nullptr) != 0) {
        LOGE("probe: sigaltstack 安装失败（errno=%d %s），SA_ONSTACK 将不设置", errno, strerror(errno));
        return;
    }
    g_alt_stack_ready = true;
}

static void probe_install_signals() {
    // 幂等：第二次进入直接返回，**保留第一次拿到的 g_old_***。
    // 不这么做的话 g_old_segv 会被覆盖成 probe_signal 自己 —— 转发链变成一个环，
    // 信号现场全丢（见 g_signals_installed 的注释）。
    if (g_signals_installed) {
        jp("[probe] signal handlers 已装过（幂等跳过，保留原 handler 链）\n");
        return;
    }
    probe_install_alt_stack();
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = probe_signal;
    // SA_ONSTACK 只在**确实装了**备用栈时才设：设了而没装是无害但误导的
    // （读代码的人会以为爆栈现场已经处理好了）。
    sa.sa_flags = SA_SIGINFO | (g_alt_stack_ready ? SA_ONSTACK : 0);
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &g_old_segv);
    sigaction(SIGABRT, &sa, &g_old_abrt);
    sigaction(SIGBUS,  &sa, &g_old_bus);
    sigaction(SIGILL,  &sa, &g_old_ill);
    sigaction(SIGFPE,  &sa, &g_old_fpe);
    g_signals_installed = true;
    jp("[probe] signal handlers installed (SEGV/ABRT/BUS/ILL/FPE, altstack=%s)\n",
       g_alt_stack_ready ? "yes" : "no");
}


static JavaVM * g_vm = nullptr;
static jobject   g_log_cb   = nullptr; // 全局引用：LlmLogBridge 实例
static jmethodID g_log_mid  = nullptr;

// 探针开启时 llama.cpp 的日志出口。
//
// 此前它是个空函数 —— 理由是"探针模式下 log sink 换成空操作，两条路径下处理代码
// 完全一致，只有最终写出点不同"，但"丢弃"这个写出点让**探针恰恰丢掉了最值钱的东西**：
// 从 backendInit 起，凡走 llama 日志系统的输出（GGUF header 解析、tensor 装载、
// 采样告警…）整段归零，而这些正是崩前最常出现的内容。用户开探针是为了取证，
// 结果反而不如不开（不开至少走 JVM 回调落进会话文件）。
//
// 现在改成落盘：只经 jp()（异步信号安全的那套），不经 JVM —— 保留"旁路 JVM 回调"
// 这个防观测者效应的本意，只是不再丢内容。级别前缀保留，便于区分 error 与 info。
static void probe_log_sink(enum lm_ggml_log_level level, const char * text, void *) {
    if (!text || !*text) return;
    jp("[llama/%d] %s", (int) level, text);
}

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

// ══════════════════════════════════════════════════════════════════════════════
//                       内存可观测 + 归还预填充页（mmap 真正省内存）
// ══════════════════════════════════════════════════════════════════════════════
// 为什么需要这一段（ISSUE #132 第二轮）：
//
// 第一轮把 `useMmap` 显式写进 `mp.load_mode` 之后，日志已经能读到
// `load_mode=MMAP`，映射也确实建立了 —— 但用户实测**内存纹丝不动**。
// 原因不在本仓库，在 vendor 预编译的 librnllama 里：
//
//   `llama_model_base::load_tensors()` 调 `ml.init_mappings(true, …)`，
//   prefetch 参数**硬编码 true**（上游 llama.cpp 如此，本变体同源）。
//   `init_mappings(prefetch=true)` → `llama_mmap(file, /*prefetch=*/-1, …)`，
//   而形参是 `size_t`，-1 即 SIZE_MAX，于是：
//     · Linux 路径 `if (prefetch && …) flags |= MAP_POPULATE;` → mmap 时
//       **内核立即把整个 GGUF 灌进物理内存**；
//     · 且 `if (prefetch > 0)` 恒真，再对整个文件 `POSIX_MADV_WILLNEED`。
//
// 于是 mmap"映射上了"，RSS 却照样 ≈ 模型大小 —— 与用户看到的
// 「占用都是模型大小加上 kv 大小」逐字吻合。这不是 mmap 没生效，
// 是**预填充把页全拉进来了**；`load_mode` 只决定映射与否，控制不了 prefetch。
//
// 本仓库对 vendor 的 .so 有一条硬约束：**不改写**（见 vendor/*/README.md，
// 变体按指令集分档编译，改了运行时探测就失去意义）。所以不动 .so，
// 在**加载完成之后**把那些预填充的干净页还回内核。
//
// ⚠⚠ 第一版在这里做了一个**错的前提**，必须记下来（本模块第五次"看起来对、
//    其实没量到"）：它写的是
//
//      「自己再 mmap 同一个文件 + DONTNEED ⇒ 同一份 page cache，
//        库那侧的页一并放掉」
//
//    前半句对、后半句错。`MADV_DONTNEED` **只清调用它的那个 VMA 的页表项**；
//    库那份映射是**另一个 VMA**，它自己的页表项原封不动 —— 于是
//    `madvise` 返回 0（"成功"）、日志照打「已归还预填充页」，**RSS 一页都没降**。
//    用户 gpu=0 那轮 `RSS 261 MB → 4884 MB（省 -4622 MB）` 就是这么来的：
//    不是"省了负数"，是**根本没还**。（Runner 上真代码复现，见 HTP-STATUS 第五十九节。）
//
// 正确做法：**别另开映射，直接对「库已有的那份映射」下手。** 库的映射就在本
// 进程地址空间里，从 `/proc/self/maps` 按模型路径把区间找出来，对**那些地址**
// 调 `madvise(MADV_DONTNEED)` —— 不需要改 vendor `.so`，也不需要知道库的内部指针。
// 放掉的是**干净页**：权重仍在映射地址上，后续按需缺页从文件重新读入，
// 不改变任何计算结果，只把"常驻整模型"变成"用多少读多少"（这正是 mmap 本该有的语义）。
//
// ⚠ 有效性必须能自证：所以下面同时把 RSS 前后值、**实际归还的区间条数与字节数**
// 打进日志。没有这几个数，"到底有没有省"又只能靠装机读 /proc —— 与上一轮同一个教训。
// 只报"已归还"是**恒真**的：这次就是这么骗过判据的。
// 读 /proc/self/status 的 VmRSS（KB）。取不到返回 -1，绝不猜。
static long proc_rss_kb() {
    FILE * f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long v = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            long n = -1;
            if (sscanf(line + 6, "%ld", &n) == 1) v = n;
            break;
        }
    }
    fclose(f);
    return v;
}

// ──────────────────────────────────────────────────────────────────────────────
//  maps / smaps 里「这一行的路径列是不是我们的模型文件」——**唯一的路径判据**
// ──────────────────────────────────────────────────────────────────────────────
// 这一处必须是**一个**函数，被 `proc_file_mapped_kb`（读驻留）和
// `release_populated_pages`（去 DONTNEED）**共用**。理由不是省代码，而是本轮
// 真机事故的成因就在"两处各写一份、只要有一处判错，诊断读数与释放动作就对不上"：
//
//   · 用户报的 `[mmap释放] 区间 2 个 / 5072 MB｜GGUF 文件映射驻留 2601 MB → 0 MB`
//     这份读数**自相矛盾** —— 若那 5072 MB 真是模型映射且真被还掉，驻留必须归零；
//     而"驻留归零"与"RSS 只从 270 降到 2925、里面仍是 2601 MB 文件页"又是两回事。
//   · 根因：**内核在 VMA 未改名时会追加 " (deleted)" 后缀**（`vma_set_name` 只在
//     名字为空/被取消删除时改写 `vm_file` 的 dentry 名）。此时 maps/smaps 里那一行是
//
//        7f..-7f.. r--s 00000000 08:01 12345   /storage/.../Gemma-4-E4B.gguf (deleted)
//
//     旧判据要求"行尾正好是 path"，于是**一个区间都认不到**。
//     又因为 smaps 的 Rss 是**按 VMA 名称**归属的，同一份驻留页会被记为
//     `... .gguf (deleted)`：读驻留的函数跟着认不到 → 报 0；而释放函数若在
//     另外一版里认到了（或它的失败分支）→ 读数与动作脱节。**正是这种"两处各判一次、
//     判错的方向还不一样"的组合，产出了上面那条不可能同时成立的读数。**
//
// 判据（两个方向都与真实内核行为对齐）：
//   ① 行尾正好是 path（原名未变；唯一不受"名字含空格"影响的形态）；
//   ② 第 5 字段（inode 之后那一列，内核在此处补足对齐空格）以 path 开头，
//      且其后**正好**是 " (deleted)" —— 只认这一个内核后缀，不放宽成"以 path 开头"，
//      否则 `/a/model.gguf.bak`、`/a/model.gguf.tmp` 这类名字会被误命中。
//   为什么按"第 5 字段"定位而不是 strstr：模型文件名里**有空格**（用户的就是
//   `ai models/Gemma-4-E4B…`），而内核对路径里的空格会做 `\040` 转义 ——
//   字段内不会出现裸空格，所以第 5 字段正好是完整路径（含转义）。
//
// ⚠ 不做 realpath/readlink 兜底：那要走额外 syscall，且**不改变结论**（inode 相同、
//   名字不同，DONTNEED 仍打在同一个 VMA 的地址范围上）。少一条路 = 少一个假绿入口。
static bool maps_line_is_path(const char * line, size_t n, const char * path, bool * is_deleted) {
    if (is_deleted) *is_deleted = false;
    if (!line || !path || !*path) return false;
    const size_t plen = strlen(path);
    // ① 行尾正好是 path（且前面是空白或行首）
    if (n >= plen && strcmp(line + n - plen, path) == 0) {
        if (n == plen || line[n - plen - 1] == ' ') return true;
    }
    // ② 内核追加 " (deleted)"：路径列以 path 开头、其后正好是该后缀。
    const char * col = nullptr;
    {
        int fields = 0;
        const char * q = line;
        for (;;) {
            while (*q == ' ') q++;
            if (!*q) break;
            while (*q != ' ' && *q) q++;
            fields++;
            if (fields == 5) { col = q; while (*col == ' ') col++; break; }
        }
    }
    if (col) {
        size_t rl = strlen(col);
        if (rl > plen && strncmp(col, path, plen) == 0 &&
            strcmp(col + plen, " (deleted)") == 0) {
            if (is_deleted) *is_deleted = true;
            return true;
        }
    }
    return false;
}

// 读 /proc/self/smaps，累计**文件映射的驻留量**（Rss 列，只统计文件支持的行）。
// 为什么还要这一个：mmap 生效时 VmRSS 本来就可能高（页在、但可回收），
// 只有"文件映射驻留"这一列才直接对应"GGUF 有多少页真在内存里"。
// 它也是用户装机判据（`r--s` 映射）的数值化版本，省得人去读 maps。
static long proc_file_mapped_kb(const char * path) {
    if (!path || !*path) return -1;
    FILE * f = fopen("/proc/self/smaps", "r");
    if (!f) return -1;
    char line[1024];
    bool in_target = false;
    long total = 0;
    while (fgets(line, sizeof(line), f)) {
        size_t n = strlen(line);
        while (n > 0 && (line[n-1] == '\n' || line[n-1] == '\r')) line[--n] = '\0';
        // 块头形如：`7f..-7f.. r--s 00000000 08:01 12345 /path`。
        // 判据：以 16 进制地址范围开头（首字段含 '-' 且以 hex 起始），而非"行里出现过 '-'"。
        bool is_header = false;
        {
            const char * sp = strchr(line, ' ');
            if (sp && sp > line && memchr(line, '-', (size_t)(sp - line)) != nullptr) {
                is_header = true;
                for (const char * q = line; q < sp; q++)
                    if (*q != '-' && !isxdigit((unsigned char) *q)) { is_header = false; break; }
            }
        }
        if (is_header) {
            // 走**唯一**的路径判据（见 maps_line_is_path 上方注释）：原名 + "(deleted)" 两种。
            // 旧版只认"行尾正好是 path"，于是在内核给 VMA 打了 " (deleted)" 的机器上
            // **一个区间都认不到、驻留恒报 0** —— 与释放那侧的读数自相矛盾。
            in_target = maps_line_is_path(line, n, path, nullptr);
            continue;
        }
        if (in_target && strncmp(line, "Rss:", 4) == 0) {
            long v = -1;
            if (sscanf(line + 4, "%ld", &v) == 1 && v > 0) total += v;
            in_target = false;
        }
    }
    fclose(f);
    return total;
}

// 把库 MAP_POPULATE 预填充进内存的 GGUF 页归还给内核。
// 返回：0 = 成功归还了至少一个区间，-1 = 失败/无可归还。失败**不影响加载结果**：
// 最坏情况就是内存没省下来（回到修复前），不会让模型不可用。
//
// 实现要点（第一版的错法见上方注释）：**对库自己那份映射 DONTNEED**，
// 而不是对我们另建的一份。库的映射区间从 `/proc/self/maps` 按路径找。
// 为什么用 maps 而不是"自己再 mmap 一次"：见上，MADV_DONTNEED 的作用域是**单个 VMA**，
// 另建一份是另一个 VMA，对它的 DONTNEED 碰不到库那份（Runner 上已实测：
// 另建 + DONTNEED 后 RSS 392104 KB（没降），对库那份 DONTNEED 后 1476 KB）。
//
// `n_done` / `bytes` 是**自证点**：只报"已归还"是恒真的（第一版就栽在这），
// 必须报出"真的认了几个区间、覆盖多少字节"，否则日志与"什么都没做"同形。
static int release_populated_pages(const char * path, int * n_ranges, long * bytes, int * n_deleted_out) {
#if defined(__linux__) && defined(_POSIX_MAPPED_FILES)
    if (n_ranges)     *n_ranges     = 0;
    if (bytes)        *bytes        = 0;
    if (n_deleted_out) *n_deleted_out = 0;
    if (!path || !*path) return -1;
    FILE * f = fopen("/proc/self/maps", "r");
    if (!f) {
        jlog("[mmap释放] 打不开 /proc/self/maps，跳过：%s", strerror(errno));
        return -1;
    }
    char line[1024];
    int n_done = 0;
    int n_deleted = 0;   // 其中有多少条是走了内核 " (deleted)" 后缀认出来的（自证用）
    long total = 0;
    while (fgets(line, sizeof(line), f)) {
        size_t n = strlen(line);
        while (n > 0 && (line[n-1] == '\n' || line[n-1] == '\r')) line[--n] = '\0';
        // 走**唯一**的路径判据 —— 与 `proc_file_mapped_kb` 是同一个函数
        // （`maps_line_is_path`）：原名 / 内核追加 " (deleted)" 两种都认。
        // ⚠ 这一点是本轮真机事故的关键：旧版两处各写一份、口径还不一样，
        //   同一台机器上"释放认到了、驻留认证 0"，读数自相矛盾（见 helper 上方注释）。
        bool is_deleted = false;
        if (!maps_line_is_path(line, n, path, &is_deleted)) continue;
        // 块头形如 `7f..-7f.. r--s 00000000 08:01 12345 /path`。
        // 只对**可读的文件映射**下手（`r--s` / `r--p`）：不碰任何非映射行，
        // 也不能碰我们自己没有读权限的块（不可读的映射本就不会有驻留权重页）。
        char * dash = strchr(line, '-');
        if (!dash) continue;
        char * sp = strchr(line, ' ');
        if (!sp || sp < dash) continue;
        char perms[8] = {0};
        if (sscanf(sp + 1, "%7s", perms) != 1) continue;
        if (perms[0] != 'r') continue;
        unsigned long lo = strtoul(line, nullptr, 16);
        unsigned long hi = strtoul(dash + 1, nullptr, 16);
        if (hi <= lo) continue;
        // 对**库那份映射的地址**直接 DONTNEED —— 这才是真的清页表项。
        // 不改变文件内容，也不影响后续读取（会被重新缺页读入）。
        int rc = madvise((void *) lo, (size_t)(hi - lo), MADV_DONTNEED);
        if (rc == 0) {
            n_done++;
            if (is_deleted) n_deleted++;
            total += (long)(hi - lo);
        } else {
            // 单个区间失败不放弃其余区间：模型文件常有多个分段映射。
            jlog("[mmap释放] 区间 [%#lx,%#lx) DONTNEED 失败，跳过：%s",
                 lo, hi, strerror(errno));
        }
    }
    fclose(f);
    if (n_ranges)      *n_ranges      = n_done;
    if (bytes)         *bytes         = total;
    if (n_deleted_out) *n_deleted_out = n_deleted;
    if (n_done == 0) {
        // 一个区间都没找到 = 库那份映射不在 maps 里（replaced/匿名/或被 mremap 掉），
        // 此时**如实报"没还"**，不要打"已归还"—— 第一版正是打了恒真的"已归还"。
        jlog("[mmap释放] /proc/self/maps 里没找到模型映射区间，未归还");
        return -1;
    }
    return 0;
#else
    (void) path; (void) n_ranges; (void) bytes; (void) n_deleted_out;
    return -1;
#endif
}

// ──────────────────────────────────────────────────────────────────────────────
//  推理期 RSS：为什么必须在**跑完一轮之后**再量一次
// ──────────────────────────────────────────────────────────────────────────────
// ⚠ 前置声明：`unloadModelInternal` 的**定义**在本文件更下面（文件尾，与 JNI 导出
//   分开写）。本区块自己要用 `g_rss_peak_kb` 的复位值语义，而复位发生在那里 ——
//   为避免"先用后定义"，这里把它与下方那段一起声明。C++ 里 static 函数
//   不声明就用会被判"未声明"直接编不过（0.9.120 之后这一族真编不过过，
//   而 CI 只跑静态守卫、不编 native，所以没被拦住）。
// ⚠ `Session` 本身在下方才定义（与它的字段注释放在一起）；这里只需它的**名字**，
//   所以先做一个不完整类型的前置声明 —— 不能直接写 `Session & s` 而不声明它，
//   否则是"未知类型名"（本守卫 ① 正是逮这个）。
struct Session;
static void unloadModelInternal(Session & s);
#define LM_RSS_PEAK_RESET (-1)   // 复位值：-1 = 还没量到过（见 g_rss_peak_kb）

// 本仓库此前只有 `[mmap释放]` 一处 RSS 探针，它打在 `loadModel` 里 ——
// 于是所有读数都是**加载完成态**。真机上由此产生过一个具体的误读：用户看到的
// 「占用变多」是加载刚完那一刻的数，而推理一起跑，KV 会随 token 线性增长、
// compute buffer 按 ctx 分配，当时的日志里连一次 `kv_rounds > 0` 都没有 ——
// **读数与问题不在同一个时刻**，谁也没法据此判"到底吃多少内存"。
//
// 所以这里在**每一轮推理真正跑起来之后**（prefill 成功）采一次 VmRSS，
// 只在收尾时打一行「峰值」。为什么只打一次而不是每步都打：
//   · RSS 只增不减（KV 只涨），峰值本身就是结论；
//   · `nativeStep` 是热路径，每步读一次 /proc 会把"观测"变成"开销"。
// 峰值跨**整个加载周期**累计，`unloadModelInternal` 处复位 —— 换模型后重新累计，
// 于是"这个模型真正吃过多少"是一个能对账的数。
static long g_rss_peak_kb = LM_RSS_PEAK_RESET;

// 采样一次并更新峰值。-1（读不到）不参与，绝不猜。
static void rss_note_peak() {
    long kb = proc_rss_kb();
    if (kb <= 0) return;
    if (kb > g_rss_peak_kb) g_rss_peak_kb = kb;
}

// ──────────────────────────────────────────────────────────────────────────────
//  设备池分类：哪台能承接 mmap 权重（决定权重是「映射」还是「拷一份」）
// ──────────────────────────────────────────────────────────────────────────────
// ⚠ 前置声明：`split_desc_append` 的**定义**在本文件更下面（追加式格式化工具，
//   含 tensor_split 那一段附近的注释）。本区块的 `classify_mmap_devices` /
//   `log_mmap_device_diag` 都在它**之前**调用它，
//   而 C++ 里没有前置声明时「先用后定义」直接编不过（"was not declared in this
//   scope"）。这不是风格问题：0.9.120 之后这一族是**真编不过**的（CI 只跑静态
//   守卫与 sh 脚本、不编 native，所以没被拦住），直到接 APK 的流水线才暴露。
//   声明与定义必须**逐字同签名**，改一处要同时改两处；`tools/run_static_order_guard.sh`
//   会守着这条（它逐条比对每个 static 函数的「首次使用」不早于「声明/定义」）。
static void split_desc_append(char * out, size_t cap, int * w, const char * fmt, ...);
// 为什么必须**运行时**问库，而不是照抄一份名单：
//   上游 `llama_model_base::load_tensors()` 用「默认缓存类型能不能
//   `buffer_from_host_ptr(映射地址)`」当唯一判据。这个能力写在**各后端自己的
//   device iface 里**，且 vendor 是预编译的，我们看不到也改不了。名单一旦写死，
//   库升级（例如 Hexagon 的 opt_hostbuf 从 false 变 true）就会悄悄失效。
//   所以这里读库的**能力位**，不读它的设备名/版本。
//
// 读到的三件事（日志里逐台打出来，用户不必去猜）：
//   1. 池内各设备的登记顺序（`mp.tensor_split` 按同一下标索引）；
//   2. 每台的**默认缓存类型名 + 是不是 host**，以及因此该组走映射还是拷贝；
//   3. 有没有"把非文件缓冲改注册成文件支持"的手段 —— 有则"映射"不等于"独占内存"。
//
// ⚠ 只报"池里有几台能承接映射"是不够的：本模块两次误判都出在"从设备名推组"。
// 上游分组是**按层**的（`get_layer_buft_list`），池里的设备**不等于**实际会出现的组：
// `n_gpu_layers=0` 时 OpenCL 组压根不存在（所有层都落回 CPU 组），而
// `0 < k < n_layer` 时 OpenCL 组才是真的（那几层本该进 VRAM）。
// 所以逐台把 `默认buft=… host=…` 打出来，是让"哪些组真实存在、各自走哪条路"
// 从设备名可以推出来 —— 而不是只留一个"几台能承接"的数字给人猜。
//
// 结论会**直接打出**判定行，因为这一条决定成败，不能只留在读代码的人脑子里。
enum MmapDevClass {
    MMAP_DEV_TAKES_MAPPING,   // 默认缓存类型能把映射地址包成缓冲（CPU）→ 权重真映射
    MMAP_DEV_COPIES,          // 有默认缓存类型但不支持 host ptr（OpenCL/HTP）→ 权重拷一份
    MMAP_DEV_NO_BUFT,         // 连默认缓存类型都没有 → 加载期就会被跳过
};

static const char * mmap_dev_class_name(MmapDevClass c) {
    switch (c) {
        case MMAP_DEV_TAKES_MAPPING: return "承接映射(CPU_Mapped)";
        case MMAP_DEV_COPIES:        return "需拷一份(匿名缓冲)";
        default:                     return "无默认缓存类型";
    }
}

// 逐台分类设备池。返回"能承接映射"的台数（0 表示本轮 mmap 一个权重都省不下来）。
// 为什么用 `dev_get_props` + `buffer_from_host_ptr` 而不是 `supports_buft`：
//   · `supports_buft` 问的是"这台认不认这个缓存类型"，默认缓存类型恒为自己认自己，
//     答不出"认不认 host ptr"；
//   · `buffer_from_host_ptr` 就是上游拿来当门控的那个能力位，同源同语义。
// 两处能力位（dev props 与 buft 是否 host）**都要**看：`props.caps.buffer_from_host_ptr`
// 为真而默认 buft 不是 host 的机型，走的仍是 else 分支（拷一份），只信一个会误判。
static int classify_mmap_devices(char * out, size_t cap) {
    int w = 0;
    int n_take = 0;
    const size_t n = lm_ggml_backend_dev_count();
    for (size_t i = 0; i < n; i++) {
        lm_ggml_backend_dev_t d = lm_ggml_backend_dev_get(i);
        const char * nm = d ? lm_ggml_backend_dev_name(d) : nullptr;
        MmapDevClass cls = MMAP_DEV_NO_BUFT;
        const char * buft_name = nullptr;
        bool default_is_host = false;
        if (d) {
            lm_ggml_backend_buffer_type_t bt = lm_ggml_backend_dev_buffer_type(d);
            if (bt) {
                buft_name = lm_ggml_backend_buft_name(bt);
                lm_ggml_backend_dev_props props;
                lm_ggml_backend_dev_get_props(d, &props);
                const bool can_host_ptr = props.caps.buffer_from_host_ptr;
                default_is_host = lm_ggml_backend_buft_is_host(bt);
                cls = (can_host_ptr && default_is_host) ? MMAP_DEV_TAKES_MAPPING
                                                        : MMAP_DEV_COPIES;
            }
        }
        if (cls == MMAP_DEV_TAKES_MAPPING) n_take++;
        // 逐台把**判据本身**打出来：默认缓存类型的名字 + 它是不是 host + 该组
        // 走映射还是拷贝。日志里出现 `CPU_Mapped` 才叫"这一组真映射了"，
        // 只报设备名的话，用户仍要从设备名去推断组，等于没报。
        split_desc_append(out, cap, &w, "%s#%zu %s[默认buft=%s host=%s]=%s", i ? " " : "", i,
                          nm ? nm : "?", buft_name ? buft_name : "无",
                          default_is_host ? "是" : "否", mmap_dev_class_name(cls));
    }
    // "把非文件缓冲改注册成文件支持"的手段：本变体里没有（OpenCL/HTP 均无此 proc）。
    // 有它时，匿名缓冲与映射可能被合并成同一段 mappable 存储，"映射"就不再等价于
    // "不和匿名缓冲抢内存"。这里只做**存在性**报告，不假装理解它的语义。
    bool has_register_anon = false;
    for (size_t i = 0; i < n; i++) {
        lm_ggml_backend_dev_t d = lm_ggml_backend_dev_get(i);
        if (!d) continue;
        lm_ggml_backend_reg_t reg = lm_ggml_backend_dev_backend_reg(d);
        if (reg && lm_ggml_backend_reg_get_proc_address(reg, "lm_ggml_backend_register_anon_mmap")) {
            has_register_anon = true;
            break;
        }
    }
    split_desc_append(out, cap, &w, " ｜ 匿名缓冲可改注册为文件支持=%s",
                      has_register_anon ? "有" : "无");
    return n_take;
}

// [mmap诊断] 一行把结论说尽：池里有几台能承接映射、池的顺序是什么、匿名缓冲有没有救。
// 为什么要在加载前就打：加载前已知的结论，与加载后读 /proc 得到的现象可以对上；
// 少了这一行，"内存没降"又要靠读代码去猜是哪条路。
static void log_mmap_device_diag() {
    char desc[512] = {0};
    const int n_take = classify_mmap_devices(desc, sizeof(desc));
    if (n_take > 0) {
        jlog("[mmap诊断] 池内 %d 台设备可承接 mmap 权重 ｜ %s", n_take, desc);
    } else {
        jlog("[mmap诊断] ⚠ 池内**没有**设备能承接 mmap 权重 —— 按层分组后，"
             "落到每组上的权重都只能「读进引擎自己的匿名缓冲」，内存 ≈ 模型大小 ｜ %s", desc);
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  权重重排（repack）：`use_extra_bufts` 是全流程里**唯一**能"少一份拷贝"的开关
// ──────────────────────────────────────────────────────────────────────────────
// 为什么是它：`mmap=1` 时 CPU 组的权重走映射（`CPU_Mapped`，内核不通过缓冲记账），
// 但 q4_K/q6_K 这类量化还会被**重排**成 `q4_K_8x8`/`q6_K_8x8` 另存一份 ——
// 那一份是**匿名内存**，与映射里的原始权重**并存**。真机日志里它是除模型本体外
// 最大的单一可回收项 —— 但**开销随 `gpu_layers` 强烈变化**（CPU 组层数越多越大，
// 全部层交给 GPU/NPU 时可以是 0），且三个读数都是默认档（=开）下量的、
// **本仓库从未做过开关对照**。所以这里只写机制、不写单点数字：
// 拿某一格峰值当通用收益说，用户换一组 `gpu_layers` 就对不上。
//
// 它的开关是 `llama_model_params::use_extra_bufts`，本仓库此前**从未显式设过** ——
// 与 `load_mode` / `devices` 同一种病：开关只落一半，另一半交给 vendor 的默认值。
//
// ── 上一版（0.9.124）为什么走了属性通道，以及它为什么被收回 ──────────────────
// 上一版把判定交给系统属性 `lm_extra_bufts`，指望用 `getprop` 做"同一个 APK 内
// 开/关对照"。真机实测（root 机、`setprop lm_extra_bufts 0`、重启 App）之后，
// 日志里**仍**是 `use_extra_bufts=1（库默认，本仓库未显式设过）`，两轮 `CPU_REPACK`
// 读数一分没少 —— **属性这条路没通**（具体数值见 HTP-STATUS §52）。
//
// 更糟的是它**不可诊断**：`probe_getprop` 用 `fork` + `execl(/system/bin/getprop)`
// 读，下面这几种处境在日志里长得**一模一样**，且都退化成"库默认"：
//   · 属性压根没设过；           `getprop` 返回空串；
//   · 属性被 init / sepolicy 拦掉，子进程读不到；
//   · `/system/bin/getprop` 在该 ROM 上不存在或不可执行（`_exit(127)`）；
//   · App 域根本不允许 fork/exec（SELinux）。
// 于是"我设了、但没生效"与"没设"分不出来 —— 一台机器上白跑一轮，还得靠人再问一次。
//
// ── 本版的做法：判定改走**仓库自己**的显式设置，属性降为覆盖 ─────────────────
// 判据按优先级从高到低，**每一级的原始读数都进日志**（否则又回到"分不出"）：
//   ① Java 侧显式设置的档位（设置页的 `use_extra_bufts`，经 `nativeSetExtraBufts`
//      传下来；-1 = 没设过）—— 这是主通道：不依赖 root、不依赖 sepolicy、
//      不依赖 `getprop` 子进程，且**在 App 内可见可改**；
//   ② 系统属性 `lm_extra_bufts`（有 root 的人可以用它压过设置，做无人值守对照）；
//   ③ 库默认（true）。
// 三档都在日志里标出来源，并附上①的原始值与②的原始读数 —— 一轮日志就能定位
// "这一档到底是谁定的"，不再需要隔空猜。
//
// 默认**不动**（= true）：关掉省的是内存、付的是 CPU 侧速度，属用户取舍，
// 本模块只负责把档位变得**可设、可读、可追溯**，不替用户拍板。
static const char * g_extra_bufts_prop_raw = "（未读取）";   // ② 的原始读数，只给日志
static int g_extra_bufts_ui = -1;                            // ① Java 侧设置：-1 = 未设过
// 本轮**实际落给库**的值（`mp.use_extra_bufts` 的落值本身）：
// 与 ①②③ 的判定分开记 —— 否则"设置页写了 0"与"落给库的是 0"还是两件事。
static int g_extra_bufts_applied = -1;                       // -1 = 本次加载还没落值
// 这一档造成的加载增量（加载后回报；负数 = 量不到）
static long long g_repack_bytes = -1;
static const char * g_repack_measure_note = "（本次加载未量到）";

// Java 侧把设置页里的值传下来。**必须能区分"没设过"与"显式设为 0"**，
// 所以用 -1 而不是 false 当哨兵：丢了这一位，用户关掉 repack 之后
// 下一轮加载会静默回到库默认值，而日志看起来"一切正常"。
// ── 这里必须自带 extern "C" ───────────────────────────────────────────────────
// 本函数定义在文件里那个大 `extern "C" {`（JNI_OnLoad 之前）**之外**，
// 少了这层链接规格，C++ 会把名字重整成
// `_Z83Java_com_xiaowan_localinference_LlmEngine_nativeSetExtraBufts...`，
// 而 JVM 只按**未重整**的 `Java_..._nativeSetExtraBufts` 去 dlopen 里找 ——
// 结果就是装机后 `[加载] 异常：UnsatisfiedLinkError`，且报的正是本符号。
// 同一个文件里其余 JNI 导出都在那个 `extern "C"` 块内，所以只有本函数会中招。
extern "C" {

// 新旧两个名字**写同一个变量**，且都落在同一个 `extern "C"` 块里：
//   · `nativeSetRepack` 是 0.9.127 起的主名（与日志/设置页的措辞统一）；
//   · `nativeSetExtraBufts` 是旧名的别名，**只为**跨版本覆盖安装时 .so 与 APK
//     版本错配能有一层兜底（Java 侧优先调新名，仅在新名缺失时才回退）。
// 两个都必须有 C 链接：少一层就退化成 `_Z83Java_...`，JVM 按未重整名找 → 装机即
// `UnsatisfiedLinkError`（0.9.125 真机现场，守卫见 run_mmap_device_guard 第 ⑩ 条）。
JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeSetRepack(JNIEnv *, jclass, jint mode) {
    g_extra_bufts_ui = (mode == 0 || mode == 1) ? (int) mode : -1;
}

JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeSetExtraBufts(JNIEnv *, jclass, jint mode) {
    g_extra_bufts_ui = (mode == 0 || mode == 1) ? (int) mode : -1;
}

}   // extern "C"

// 判定 + 记下 ② 的原始读数（不改变任何行为，只为让日志能自证）。
static bool model_use_extra_bufts() {
    if (g_extra_bufts_ui == 0) return false;
    if (g_extra_bufts_ui == 1) return true;
    char v[32] = {0};
    const bool got = probe_getprop(kPropExtraRepack, v, sizeof(v));
    if (!got) {
        // 读不到 ≠ 读到空：这里把两种都标明确，否则"设了没生效"仍要靠猜。
        g_extra_bufts_prop_raw = "（读不到：getprop 未返回任何值）";
        return true;
    }
    g_extra_bufts_prop_raw = v;   // 静态字面量或本次读入的值，供紧跟其后的日志使用
    for (const char * const * p = kProbeOffValues; *p; ++p) {
        if (strcmp(v, *p) == 0) return false;
    }
    return true;
}

// 把"这一轮的 repack 档位是什么、**是谁定的**"打进日志。
//
// 为什么档位必须成对报两次（这里 + 加载后的 `log_repack_applied`）：
//   · 加载**前**只能报"判定"（三级的原始读数）；
//   · 真的有没有省下来，要看加载**后** `mp.use_extra_bufts` 落成的值与加载增量。
// 旧版日志只有加载**前**一行，于是"设置页写了 0、CPU_REPACK 却还在"这件事
// 只能靠人去翻 `llama_model_loader: ... cannot be used with CPU_REPACK` 那句间接推，
// 本轮把它变成一行直接读数。
static void log_extra_bufts_diag() {
    g_extra_bufts_prop_raw = "（未读取）";
    // ⚠ 这里**不能**重置 `g_extra_bufts_applied` —— 它记的是"落给库的值"，
    // 由赋值处（`mp.use_extra_bufts = ...` 之后）写入，本函数在**之前**跑。
    // 0.9.128 的真机现场正是这一行：本函数先把它重置成 -1，赋值随后又被它冲掉，
    // 于是加载后 `[repack结果] 落给库 use_extra_bufts=-1` **恒为哨兵值**，
    // 与"没落值"同形 —— 用户关掉 repack 之后仍看不出档位到底有没有到库。
    // 重置已挪到**赋值之前**（`mp.use_extra_bufts = ...` 上方那一行）。
    g_repack_bytes = -1;
    g_repack_measure_note = "（本次加载未量到）";
    const bool on = model_use_extra_bufts();
    const char * src = g_extra_bufts_ui >= 0 ? "设置页档位（仓库自有）"
                    : (strncmp(g_extra_bufts_prop_raw, "（", 3) == 0 ? "库默认（本仓库未设过）"
                                                                   : "系统属性 lm_extra_repack");
    // ⚠ 这一行报的是**判定结果**，不是"落给库的值" —— 后者在 `mp.use_extra_bufts`
    // 赋值处记下，并单独起一行日志。中间的差值（参数没传到 native / 传了没落值）
    // 只能靠两行对齐才发现，这正是本行与那一行都必须存在的原因。
    jlog("[repack] use_extra_bufts=%d（来源：%s）｜ 设置页档位=%s，属性 lm_extra_repack=%s"
         " ｜ 开启时 q4_K/q6_K 会被重排另存一份（日志里表现为 CPU_REPACK 缓冲），"
         "这份是匿名内存、与映射里的原始权重并存；关闭省下这份拷贝但 CPU 侧算力可能变慢"
         " —— 取舍开关，不是必开项",
         on ? 1 : 0, src,
         g_extra_bufts_ui < 0 ? "未设过" : (g_extra_bufts_ui ? "1" : "0"),
         g_extra_bufts_prop_raw);
}

// ── 这一档到底有没有省下内存：量"加载动作造成的 RSS 差"，量不到就说量不到 ──────
// 为什么量这个差、而不是去查库的内部计数：
//   · 库在 `llama_model_loader: ... cannot be used with CPU_REPACK` 那行里报的
//     `type q4_K: 336 tensors` 是**张量个数**，且那一行本身是把这些张量**移出**
//     repack 的名单 —— 反复用它反推 repack 大小，正是本项目已经在别的模块上
//     栽过一次的"锚字符串、不锚结构"；
//   · 库另有 `llama_model_loader: repack: ...` 一行，但它的**出现与否**同样
//     取决于库版本与日志级别，把它当判据会在换变体时静默失效。
// 所以这里只量**本进程可观测的事实**：加载动作前后 RSS 的差。
// 它**不等于** repack 的净大小（`[mmap释放]` 还会再归还预填充页），
// 所以文案里必须写明"含模型本体与映射页" —— 不能让人把它读成 repack 净占用。
static long g_repack_rss_before_kb = -1;

static void measure_repack_before() {
    g_repack_bytes = -1;
    g_repack_rss_before_kb = proc_rss_kb();
    g_repack_measure_note = "（本次加载未量到）";
}

static void measure_repack_after() {
    const long after = proc_rss_kb();
    if (g_repack_rss_before_kb < 0 || after < 0) {
        g_repack_measure_note = "（读不到 /proc/self/status 的 VmRSS：本机禁读，无法量差）";
        return;
    }
    g_repack_bytes = (after - g_repack_rss_before_kb) * 1024LL;
    g_repack_measure_note =
        "（报的是**加载动作造成的 RSS 差**：含模型本体与映射页，不是 repack 净占用 —— "
        "repack 关掉后这项差会变小，但不会变成 0）";
}

// 加载**后**：把"落给库的值"与"这一档实际造成的加载增量"打进日志。
//
// 为什么落值要**单独**再报一次：`[repack]` 那行报的是**判定**（谁定的档）。
// 判定 → 落值 中间还隔着"参数跨 JNI 有没有到 / 有没有被后面覆盖 / 库有没有认这一档"，
// 只报判定的话，这几步里任何一步断开都与"设置页没写进去"同形 ——
// 本模块已经因为"同形"白跑过两轮真机实测（见上方属性通道那一段）。
static void log_repack_applied() {
    jlog("[repack结果] 落给库 use_extra_bufts=%d（设置页=%s）｜ 加载增量 RSS %lld MiB"
         " ｜ %s",
         g_extra_bufts_applied,
         g_extra_bufts_ui < 0 ? "未设过" : (g_extra_bufts_ui ? "1" : "0"),
         g_repack_bytes / (1024LL * 1024LL),
         g_repack_measure_note);
}

// ──────────────────────────────────────────────────────────────────────────────
//  设备池的归属：为什么"不钉池"才是对的（收回第五轮那次的过度修复）
// ──────────────────────────────────────────────────────────────────────────────
// 上一版（0.9.121，PR #135）在这里做了这样一件事：`mmap=1` 时把设备池**钉到 CPU
// 唯一一台**（`mp.devices = g_cpu_only_devs`）。理由是当时认为"池里有 OpenCL/HTP
// 就必然多出一组走拷贝，整份权重必被读进匿名缓冲"。**那个前提是错的，本条收回。**
//
// 上游 `llama_model_base::load_tensors()` 的真实结构是**两层**：
//
//   1) 先按**层**决定每一层归哪个 buft（`get_layer_buft_list(il)`）：
//        il 落在 [i_gpu_start, i_gpu_start + act_gpu_layers) → 该 GPU 的 buft
//        其余（含 output 层）                                → CPU 默认 buft
//   2) 再按 **buft 分组**（`ctx_map` 的键），对**每一组**单独判走不走映射：
//        if (use_mmap && use_mmap_buffer && props.caps.buffer_from_host_ptr && is_default_buft)
//            → 把映射地址包成缓冲（CPU 默认 buft → 缓冲名 `CPU_Mapped`，free_buffer=NULL，
//              内核不通过它记账，RSS 只随被真正读到的页增长）
//        else
//            → `lm_ggml_backend_alloc_ctx_tensors_from_buft()` 真分配匿名内存再读进去
//
// 关键在**分组是按层分的**，不是"池里有什么设备就有什么组"：
//
//   · `n_gpu_layers=0` → `i_gpu_start = n_layer`、`act_gpu_layers = 0`，
//     **所有层**（含 output）都落回 CPU 组；OpenCL 的默认 buft 根本不会被任何
//     `create_tensor` 用到 → 不会进 `ctx_map` → **那一组压根不存在**。
//   · 0 < k < n_layer → 天然两组：CPU 组（`is_default_buft && buffer_from_host_ptr`
//     → 走**映射**）+ OpenCL 组（走 alloc，**但这不是 bug**：那几层本来就要拷进
//     VRAM，占的是显存不是 RAM）。
//
// 也就是说用户说的「加载到 CPU 的层 mmap、加载到 OpenCL 的层进 VRAM」**就是上游行为**：
// `mmap` 与 `OpenCL` 本来不互斥。上一版"钉到 CPU"是**过度修复**，代价是
// **用户一旦要 mmap 就强制失去全部 GPU/NPU 加速**，而这个代价本来不必付。
//
// 于是这一版回到"全池参与"，并且**显式写出来**：
//   `mp.devices = nullptr` 不是"没人赋过值、碰巧用了库默认"，而是本仓库的契约。
//   为什么必须显式：库默认恰好也是 NULL，所以"不写"与"写 nullptr"行为上等价、
//   语义上不等价 —— 前者谁也不知道这是契约还是漏写，正是 `da30046` 修过的同一种病
//   （开关只落一半，另一半交给库的默认值）。这里两支都写，日志把归属讲清楚。
//
// 后续若要再收紧，得先换判据 —— 注意 `lm_ggml_backend_dev_by_type(CPU)` 这条**不能**再拿来挑
// "那台能映射的设备"：它的语义是 "CPU device using system memory"，在带 IGPU 的机器上
// 可能返回 integrated GPU，而那台**没有** `buffer_from_host_ptr` → 会得到
// "日志说已钉到 CPU、映射却没生效"。判据必须读能力位（`buffer_from_host_ptr` +
// 默认 buft 是否 host），不能读类型名/设备名。
// 本版不再挑设备，所以这条只作为"下次别走回头路"的注记留在这里。

struct Session {
    llama_model    * model = nullptr;
    llama_context  * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler  * smpl  = nullptr;
    int   n_ctx     = 0;
    int   n_used    = 0;   // 本轮已入 KV 的 token 数
    int   n_rem     = 0;   // 剩余可生成 token 上限
    std::string pending;  // 不完整 UTF-8 尾部

    // ── 取消（abort）的跨线程语义 ─────────────────────────────────────────
    // 置位方**不在生成线程上**：`/v1/abort` 与断连探测来自 HTTP 连接线程
    // （http-conn），App 的「停止」来自主线程；读取方（nativeStep / prefill 循环）
    // 在生成线程。裸 `bool` 在 C++ 上是**数据竞争（UB）** —— 编译器可以合法地把
    // 它提升进寄存器只读一次，于是"取消"有时永远看不见。必须是原子。
    //
    // 语义保留"只置位、不清位"（清除由 startCompletion 在**新轮次的开头**做），
    // 因为置位方与清除方不同线程：谁先谁后无法在这里判定。
    std::atomic<bool> abort{false};

    // ── 取消的**归属**（round_epoch）─────────────────────────────────────
    // Kotlin 侧 `RequestCancel.Token` 已经把「谁在跑」与「取消打给谁」收在同一个
    // 对象上；但最后一跳 `nativeAbort()` 此前把归属**丢掉了** —— 它无条件置
    // `abort=true`，于是"上一轮迟到的取消"会停掉下一轮（`RequestCancel` 文件头
    // 记录的那个真实事故，换到了 native 侧发生）。
    //
    // round_epoch：每次 startCompletion 进入时自增，作为"这一轮的编号"。
    //              生成线程持 genLock 跑，只有它写，所以可以在 `startCompletion`
    //              返回之后由同线程读出来当本轮编号用。
    //              带归属的取消只有与它一致时才真的置位 abort（见 nativeAbort）。
    //              0 是"无归属信息"的哨兵：一律不置位。
    std::atomic<long long> round_epoch{0};

    // ── 可观测面：区分「没跑过」与「跑过但账本已失效」────────────────────
    // `kv_valid=false` 同时覆盖两种完全不同的处境：
    //   · 从未跑过任何请求（冷启动）—— 正常；
    //   · 跑过、但账本被作废（abort / 换模型 / prompt 超长）—— 故障或取消。
    // 只报 kv_valid 会让这两者同形，读 /health 的人无从分辨。kv_rounds 记录
    // "实际跑完 prefill 的轮次数"，0 = 从未跑过。纯读数，不参与任何判定。
    int   kv_rounds = 0;

    // 本轮**已经进 KV（并在账本里）的生成 token 数**。与 Kotlin 侧"已下发的步数"
    // 是两个口径：取消发生在"取 token 之前"时，最后一步可能已经进 KV 但它的 SSE 帧
    // 还没发出去（或被 ThinkStream 暂存/或被 tools 缓冲），客户端据此拼回的对话历史
    // 会比引擎手里的短一截。E-4 的处置是**只加可观测面、不回滚**：
    // 回滚要引入新的分支与新的失效模式，而收益只是"少算一个 token"。
    // 有了这个数，取消时的日志就能给出「账本里有 X、下发了 Y」而不必靠猜。
    int   gen_in_ledger = 0;

    // ── KV 前缀复用的账本（见 kv_prefix.h）──────────────────────────────
    // 上一轮**进过 KV** 的完整 token 序列（prompt + 生成出来的部分）。
    // 位置语义就是 KV 里的 pos 0..size-1，因此它同时是"KV 里有什么"和"KV 里是什么"。
    std::vector<long long> last_tokens;
    // 这份账本是否仍然有效。**任何**改动 KV 或换模型的路径都必须把它置 false：
    // 猜错的代价是模型读到别人的历史（不报错、不崩溃，只答非所问）。
    bool   kv_valid   = false;
    // 上一轮命中复用的 token 数 / 上一轮 prefill 的 token 数，供 /health 与日志用。
    int    last_reuse = 0;
    int    last_prefill_tokens = 0;
    // 上一轮的**逐 token 最长公共前缀**长度（不设门槛，见 kvprefix::Plan::lcp）。
    // 与 last_reuse 成对读才能区分"差一点没命中"与"两轮 prompt 真的不一样"：
    // 只报 reuse=0 时这两种在日志/health 上完全同形。
    int    last_lcp = 0;

    // ── 角色标记：这一份 Session 承载的是**对话**还是**嵌入** ──────────────
    // 双常驻（chat + embed）之后进程里同时存在两份 Session，而它们的加载参数
    // 约束是**相反**的：
    //   · 对话:  embeddings=false，KV 要被复用（n_ctx 按对话长度给）；
    //   · 嵌入:  embeddings=true，KV 是纯冗余（不做自回归），n_ctx 只按
    //            最长单条文本给，否则白占一份大 KV。
    // 若这个标记丢了，`unloadEmbedInternal` 之类按角色派发的路径就会去拆错那一份，
    // 而症状是"聊到一半模型没了"（不崩、只是下一次加载），极难归因。
    // 默认 false = 对话，与引入本特性前的唯一那份 Session 同义（向后兼容）。
    bool  embedding = false;

    // ── 嵌入这一份的池化类型（`llama_pooling_type(ctx)` 的原值）────────────
    // 句向量取哪一行**由模型自己声明**，不是我们能定的：BGE 系在 GGUF 里写 CLS
    // （取第 0 个 token），生成式 decoder 写 NONE（取最后一个）。
    // 取错的表现是"向量算出来了、语义完全无关"，而且**不报错** ——
    // BGE 上更是 100% 表现为"零范数"，因为非 CLS 位置没有过 LayerNorm，
    // 范数量级不可控，float32 一平方就下溢成 0。
    // 加载时读一次存下来（-1 = 库没报出来，按 last-token 处理，见 plan_pooling）。
    int   pool_type = -1;

    // ── 嵌入这一份的**合批容量**（= 建 ctx 时写进 `n_seq_max` 的那个数）──────
    // 为什么把它记在 Session 上而不是直接读 `kEmbedBatchRows`：
    // 合批能不能跑，取决于**这一份 ctx 实际**容得下几个 seq，而那是建 ctx 时
    // 定下的。将来若有人按设备内存调 `n_seq_max`（低端机给小值），
    // 写死的常量就会与 ctx 实际容量分岔 —— 于是 `seq_id` 越界、向量不对、接口 200。
    // 所以这里存**实际**落值，nativeEmbedBatch 只信它。
    int   seq_max = 0;

    // ── 嵌入这一份的**每条**文本可用位置数（= 库的 `n_ctx_seq` = `n_ctx/n_seq_max`）
    // 与 `n_ctx`（**总量**）不是一个数，本轮真踩到过：把 `n_seq_max` 提到 8
    // 而 `n_ctx` 仍是 512 时，每条的预算掉到 64，而"截断到 n_ctx"这条判据
    // 还在按 512 截 —— 于是 65~512 token 的文本会带着越界的 `pos` 上库
    //（`sequence positions are not continuous` / 静默算错）。
    // 截断与"每条能不能装下"只信这个数；`n_ctx` 仅用于日志里报总量。
    int   n_ctx_seq = 0;

    // ── 嵌入这一份的**两道门**（= 建 ctx 时显式设的 `n_batch` / `n_ubatch`）──
    // 0.9.212 起 `nativeLoadEmbedModel` 会把这两个数**显式设**成 `kEmbedGate`（4096）——
    // 在此之前它俩是**库默认**（`n_batch=2048`，`n_ubatch=min(n_batch,512)`），
    // 于是两扇门都只有"名义上够用"，真机上是这么崩的：
    //   · bge-large-zh-v1.5（BERT，非因果 → 库走 `llama_encode`）在一次提交
    //     token 和 > 512 时撞 `:1426` 的 `n_ubatch >= n_tokens`，**abort**；
    //   · Qwen3-Embedding（因果 → `llama_decode`）在 > 2048 时撞 `:1711`。
    // 这两个数记在 Session 上而不是直接读常量：将来若有人按设备内存调它，
    // 写死的常量就会与 ctx 实际落值分岔 —— 而那正是本仓库反复吃过的一族。
    // 读数是**库的实数**（`llama_n_batch` / `llama_n_ubatch`），不是我们传的值。
    int   n_batch  = 0;
    int   n_ubatch = 0;
};
static Session S;   // 对话（chat）—— 生成路径全部走它，与引入本功能前逐字节相同
// ── 嵌入（embed）用的第二份 Session ────────────────────────────────────────
// 为什么是**独立的一份**而不是给 S 加 slot 维度：生成路径有 120+ 处 `S.` 引用，
// 把它们整体改成"按 slot 取"是一次高风险重构（改错的成本是段错误 = 进程级闪退）。
// 而双常驻真正需要的只有"两个模型同时在内存里"这一件事 —— 嵌入路径是一条**全新**的
// 码路，它自己持一份 Session 即可，与本仓库 stop_sequences.h / kv_prefix.h
// "把新逻辑隔离成独立单元"的做法同源。
//
// ⚠ 两份 Session 各自的**生命周期**必须彼此独立：加载 embed 不许碰 S，
// 卸载 chat 不许碰 E。这一点由 tools/run_dual_model_guard.sh 正面钉住
// （"加载 embed 的路径里不得出现卸载 S 的调用"）。
static Session E;   // 嵌入（embed），embedding=true

// ── 最近一次嵌入失败的**可归因**详情（方案 C）──────────────────────────────
// 为什么要有它，而不是把详情只写进日志：
//   · 用户看不到 logcat。他看到的只有 UI 上那句"检查嵌入模型与内存" —— 而那句
//     话在**两个不同的根因**下都成立，等于没有排查方向；
//   · 100 条里坏 3 条时，"哪 3 条"在日志里根本没有记录，只能二分重试；
//   · 而失败详情（范数 / token 数 / 维度 / 池化类型）在**失败当场**才有，
//     事后拿不回来。
// 所以每次 nativeEmbed 开头复位、失败时写入，Kotlin 侧失败后立刻取走。
// ⚠ 不是线程安全容器：嵌入路径本身由 Kotlin 的 embedLock 串行化
//（与同一份 E Session 的约束同源 —— 并发读它本来就不允许）。
static embedtext::EmbedFailure g_embed_fail;

// 上一次 `nativeEmbedBatch` 里**失败的那几条**（原下标）。
// 与 `g_embed_fail` 同一套"失败详情在失败当场取走"的模式，但承载的东西不同：
//   · `g_embed_fail` 是**一条**的归因（范数/维度/token 数）；
//   · 这里是**一批里哪几条**坏了 —— 合批时失败是**部分**的，而这个下标集合
//     是调用方唯一能据以"把这几条的正文标出来"的东西（返回数组里那一段是全零，
//     而全零与"真的零向量"同形，没有区分能力）。
// ⚠ 同样不是线程安全容器：与 `g_embed_fail` 同源，嵌入路径由 Kotlin 的
// embedLock 串行化。每次 nativeEmbedBatch 开头清空。
static std::vector<int> g_embed_batch_failed;

static void embed_failure_reset() {
    g_embed_fail = embedtext::EmbedFailure();
    g_embed_batch_failed.clear();
}

static void embed_failure_set(int reason, int nTok, int nEmbd, int nEmbdOut, int pooling, double norm) {
    g_embed_fail.reason = reason;
    g_embed_fail.n_tokens = nTok;
    g_embed_fail.n_embd = nEmbd;
    g_embed_fail.n_embd_out = nEmbdOut;
    g_embed_fail.pooling = pooling;
    g_embed_fail.norm = norm;
}

static void unloadModelInternal(Session & s); // 释放采样器/上下文/模型（定义见文件尾）

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

// token -> 文本片段。
//
// **special 必须传 true**（llama.h: "If true, special tokens are rendered in the output."）。
// 历史故障（真机日志 0.9.69）：模型输出被记成
//         name="get_weather"> name="city">Beijing
// —— 恰好是完整工具调用 <function name="get_weather"> <param name="city">Beijing</param> </function>
// 把 MiniCPM5 的 preserved_tokens（"<function" / "<param" / "</param>" / "</function>"）
// **逐个删掉**之后的样子。这些标记在词表里是 special token，special=false 时
// llama_token_to_piece 直接跳过不吐 —— 于是解码出来的文本里连工具标记都没有，
// PEG 的 tool_open(p.literal("<function name=\"")) 永远匹配不上，tool_calls 恒为 0。
//
// 这是与「解析器没 load」「前缀没对齐」并列的**第四条独立成因**，且发生在最上游：
// 解码阶段就把标记弄丢了，后面怎么修解析都对不上。
// 传 true 只影响 special token 的**文本化**，不改变采样、不改变 EOG 判定
// （EOG 仍由 llama_vocab_is_eog 单独判、在那之前就 return）。
// 代价是正文里若真出现 "<|im_end|>" 这类控制 token，现在也会按字面吐出 ——
// 但那本来就是模型输出的一部分，且比"静默丢标记"可查得多。
static std::string token_to_piece(llama_token t, bool renderSpecial) {
    char buf[256];
    int n = llama_token_to_piece(S.vocab, t, buf, sizeof(buf), 0, renderSpecial);
    if (n < 0) { // 缓冲不足，按需扩
        std::vector<char> big((size_t)(-n) + 8);
        n = llama_token_to_piece(S.vocab, t, big.data(), (int32_t) big.size(), 0, renderSpecial);
        if (n < 0) return "";
        return std::string(big.data(), (size_t) n);
    }
    return std::string(buf, (size_t) n);
}

// 解码主路径：**必须**把 special token 文本化，否则工具调用标记会被静默丢掉（见上）。
static inline std::string token_to_piece(llama_token t) { return token_to_piece(t, /*renderSpecial=*/true); }

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
    // 自举**只记不写**：不打开文件、不落盘、不改 g_probe_on。
    // 探针开不开由 nativeProbeInit（用户意图）决定，自举只负责把"加载期发生了什么"
    // 记进缓冲，交给第一次 nativeProbeInit 落盘，或由 probe_bootstrap_recall 交回 logcat。
    probe_bootstrap();
    return JNI_VERSION_1_6;
}

// 探针初始化：Kotlin 传入可写目录，native 侧自己开文件直写，绝不依赖 JVM。
// 探针关着时走 jni_log_cb（进 JVM ring + 会话文件）、开着时走 probe_log_sink（直写探针文件）：
// 两条路径下 llama.cpp 的输出处理代码完全一致，只有最终写出点不同，
// 不会出现「开了探针就换了一条执行路径」的观测者效应。
//
// 这一次调用同时是「认领自举」的时机：自举阶段已开的 fd 被接管，
// 早期事件（含失败原因）回灌到正式文件；自举没触发时行为与原来完全一致。
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit(JNIEnv * env, jclass, jstring jdir, jboolean on) {
    mark_jni_entry("nativeProbeInit");
    const bool off = (on != JNI_TRUE);

    // 先按 Kotlin 的参数收口：关探针 = 关文件、清 g_probe_on。**任何一次调用都要走到这里**，
    // 包括 off=false 的那次 —— 见下方"关也要显式告知 native"。
    if (g_probe_fd >= 0) { close(g_probe_fd); g_probe_fd = -1; }
    g_probe_on = false;

    if (off) {
        g_bootstrapped = true;
        g_probe_attempted = true;
        // 显式关：既不写文件，也不把自举事件刷进 logcat（用户要的就是安静）。
        // 但自举开关若是"用户在设置里开了、却被属性强关"，必须留一行可查的痕迹。
        if (g_boot_off_by_prop)
            LOGI("probe: 关闭（on=false）；注意自举开关属性此前已要求关闭，两者一致");
        return JNI_FALSE;
    }

    // 自举开关属性的"显式关闭"优先于 Kotlin 传入的 true：
    // 它是**非 root 会话**下唯一可用的强关手段（settings 里关掉探针仍需重启 App 才生效）。
    // 方向 fail-safe：即使 Kotlin 与属性打架，也不落盘（宁可少取证，不可偷写盘）。
    if (g_boot_off_by_prop) {
        g_bootstrapped = true;
        g_probe_attempted = true;
        LOGE("probe: Kotlin 请求开启，但自举开关属性显式要求关闭 → 按关闭处理（不落盘）");
        return JNI_FALSE;
    }

    if (!jdir) { g_bootstrapped = true; g_probe_attempted = true; return JNI_FALSE; }

    const char * dir = env->GetStringUTFChars(jdir, nullptr);
    if (!dir) { g_bootstrapped = true; g_probe_attempted = true; return JNI_FALSE; }
    std::string path = std::string(dir) + "/probe-native.log";
    env->ReleaseStringUTFChars(jdir, dir);
    probe_rotate_prev(path.c_str());
    g_probe_fd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (g_probe_fd < 0) {
        // 打不开就**不静默**：errno + 尝试过的路径都记进自举缓冲，
        // 由 probe_bootstrap_recall 交回 logcat（此时也没别的地方可写）。
        probe_boot_note("[boot] !! Kotlin 目录打不开：%s（errno=%d %s）\n",
                        path.c_str(), errno, strerror(errno));
        g_bootstrapped = true;
        g_probe_attempted = true;
        probe_bootstrap_recall();
        return JNI_FALSE;
    }

    g_probe_on = true;
    g_bootstrapped = true;
    g_probe_attempted = true;

    probe_fmt("\n===== native probe attached (pid=%d, 目录来自 Kotlin filesDir) =====\n", (int) getpid());
    // 自举期事件（开关属性读了什么、目录来源、准备往哪写）在这里**第一次且唯一一次**落盘。
    // 只此一次是有意的：重复写会让同一段现场出现两遍，按行数推断"自举跑了几次"就会读错。
    probe_bootstrap_flush();
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
    // 探针开启时走 probe_log_sink（直写探针文件，不再丢弃）：两条路径下 llama.cpp
    // 的输出处理代码完全一致，只有最终写出点不同（JVM 回调 vs 探针 fd），
    // 保证「开探针」不改变线程/锁行为。
    // g_probe_on 在这里是**结果**而不是猜测：它只由 nativeProbeInit（用户意图）置位，
    // 自举不再预先把它打开 —— 此前"用户没开探针但 native 已自举开文件"会让这行
    // 把原生日志整段丢进 sink，用户既看不到日志、也不知道探针在写盘（D-1）。
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

// 预读之前的前置守卫：**只读文件头**，判 GGUF 魔数与版本，绝不进 `lm_gguf_init_from_file`。
//
// 为什么要有这一层（Issue #238）：
//   `lm_gguf_init_from_file(no_alloc=true)` 并不只是"读 header" —— 它会把**整份元数据**
//   分配出来，含 tokenizer 的 151936 条词表字符串，走几十万次 malloc/free。
//   而这一次 malloc 风暴发生在 `nativeProbeGguf`（一个**纯装饰性**的量化预读）里，
//   调用它的是 `EngineActivity.doLoad` 里 `Thread { ... }.start()` 的裸 Java 线程 ——
//   native 段错误没有任何 Java 层 try/catch 接得住，进程直接没。
//
//   真机现场（tombstone_03）：
//     #00 libc arena_run_heap_remove+888        ← arena 元数据链被解引用
//     #05 libc je_malloc → #06 operator new
//     #10 librnllama lm_gguf_init_from_file_ptr+228
//     #12 libllmjni  Java_..._nativeProbeGguf+172
//   寄存器里 151936（=0x25180，Qwen3 词表）出现两次 —— 正是那轮词表 malloc。
//
// 本守卫的取舍：**宁可预读失败，也不让预读有机会把进程带走**。
// 判据只用 O_RDONLY 读前 8 字节 + stat 尺寸，不解析、不分配、不碰堆。
// 魔数/尺寸不对时返回原因串，Kotlin 侧按"读不出"处理（回退按文件名判量化），
// 而不是让库去解析一个它没准备好的文件。注意：**它不能证明文件一定合法**
// （合法 GGUF 也可能被截断），只是把"明显不是 GGUF / 明显不完整"这一档挡在外面。

// 真机侧：读文件头 + 交判据（判据本身在 gguf_precheck.h，宿主可单测）。
// ⚠ 这个函数**不解析 GGUF**、不分配堆内存 —— 它存在的唯一目的就是不把
//   明显读不了的文件交给 lm_gguf_init_from_file（见头文件注释）。
static bool gguf_header_precheck(const char * path, std::string * why) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (why) *why = std::string("打不开文件（errno=") + std::to_string(errno) + "）";
        return false;
    }
    unsigned char hdr[24];
    ssize_t got = pread(fd, hdr, sizeof(hdr), 0);
    struct stat st;
    const long long size = (fstat(fd, &st) == 0) ? (long long) st.st_size : -1;
    close(fd);
    if (got < 0) got = 0;
    return gguf_header_check(hdr, (long long) got, size, why);
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

    // 先过一道**不分配、不解析**的文件头检查（见 gguf_header_precheck）。
    // 这一步存在的唯一理由：把"明显不能读"的文件挡在 lm_gguf_init_from_file 之外 ——
    // 后者会为整份元数据（含十几万条词表）打一场 malloc 风暴，而它跑在一个
    // 没有 Java 层兜底的裸线程上，一旦在那场风暴里出问题就是进程级 SIGSEGV。
    // 预读失败不再是"静默回退"：原因要能被 Kotlin 日志读到（`[HTP判定] 预读不可用`）。
    {
        std::string why;
        if (!gguf_header_precheck(cpath, &why)) {
            // 先把返回串拼进具名变量再调 mk()：mk 的形参是 const char*，
            // 直接传临时 std::string 的 c_str() 也能活，但那活着的理由
            // （"临时对象活到全表达式结束"）太细，不该让下一个人去推。
            const std::string msg = "文件头检查未通过：" + why;
            env->ReleaseStringUTFChars(jpath, cpath);
            LOGI("gguf probe: 前置检查未通过 -> %s", why.c_str());
            return mk("", "-1", "", msg.c_str());
        }
    }

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

    unloadModelInternal(S); // 定义在下方；只拆对话那份，**不碰** E（双常驻）
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
    // ── mmap 开关：必须**显式**写进 load_mode ────────────────────────────
    // 这一行此前是 `if (!useMmap) mp.load_mode = LLAMA_LOAD_MODE_NONE;` —— 开的时候
    // 「什么都不做，靠默认值」，关的时候才写一次。看着对称，实际把开关的一半交给了
    // 一个**我们并不拥有**的默认值：
    //   · `llama_model_default_params()` 是 librnllama 变体里的实现（vendor 预编译，
    //     同一份 llama.h 被四个变体各自编进去）。它现在给的是 AUTO，但那是**库的**选择，
    //     不是本仓库的契约；库一升级/换变体，"开"这一支就可能悄悄变成另一档；
    //   · 更现实的一条：上面的 `mp.tensor_split = g_split`（NPU 配额）与 `n_gpu_layers`
    //     已经在改同一个结构体，**只剩这一档没有显式落值**。读这段代码的人只能推断
    //     "默认=想要的"，而真机实测对不上（见下）。
    // 所以两支都**显式**写：这既是契约，也是本文件唯一能自证的地方（日志把
    // 实际交给库的那一档原样打出来）。
    //
    // ⚠ 关于用户报的"设 1 没省内存"：把这两个常量当参数传进
    // `llama_model_load_from_file` 是**唯一**的开关方式 —— 用
    // `llama_load_mode_from_str`（本变体在 .rodata 里有 mmap/mlock/mmap+mlock/none
    // 四个字面量，函数本体 556B）会走进它自己的名字解析，本仓库**没有**编它，
    // 依赖它对未知串的行为取决于第三方实现。常量是编译期契约，不依赖库的字符串解析。
    mp.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    // ── 权重重排：**显式**落值，不再靠 vendor 的默认 ────────────────────
    // 与上面 `load_mode` 的教训同形：`model_use_extra_bufts()` 在属性未设置时
    // 返回库默认值（true），行为与本改动前**逐字节相同** —— 但"没写"读起来像漏掉，
    // 谁也不知道这是设计还是疏忽。这里把值写进去，档位由日志自证。
    // 重置"落值"这一位必须发生在**赋值之前**（判定日志在更早处，且不再重置它）——
    // 否则这一位会被自己的重置语句清掉，加载后恒读成 -1（见 log_extra_bufts_diag 上方）。
    g_extra_bufts_applied = -1;
    mp.use_extra_bufts = model_use_extra_bufts();
    // 落值本身记下来，加载后单独起一行回报（见 log_repack_applied 上方）：
    // "判定"与"落给库的"是两件事，中间任何一步断开都要能一眼看出。
    g_extra_bufts_applied = mp.use_extra_bufts ? 1 : 0;
    // 把结论打成一行：日志里必须能读到**这一轮到底交了什么**，否则
    // "开关生效没有"只能靠 `ls -l /proc/<pid>/maps | wc -l` 这种事后取证，
    // 与本项目其它开关（flash / KV 量化 / 配额）的可观测口径也不一致。
    jlog("[加载] mmap=%d ｜ 传给库的 load_mode=%s（%d）｜ gpu_layers=%d ｜ 路径=%s",
         useMmap ? 1 : 0,
         mp.load_mode == LLAMA_LOAD_MODE_MMAP ? "MMAP" : "NONE",
         (int) mp.load_mode, (int) nGpuLayers, modelPath.c_str());
    // 加载**前**就把"池里谁承接得起映射"问清楚。这一条是"内存升不升"的先行判据：
    // 池里只 CPU 能承接时，权重落在 `CPU_Mapped` 缓冲上 —— 缓冲只是把映射地址
    // 包了一层（`free_buffer = NULL`，内核不通过它记账），RSS 只随被真正读到的页增长。
    // 反之（池里还有 OpenCL/HTP 这类"需拷一份"的设备）权重会按缓存类型分组，
    // 那一组整份读进匿名缓冲 —— 必须先说清楚，否则用户只会看到
    // "日志说 MMAP 了、内存却没降"，重新掉回这一轮的困惑。
    log_mmap_device_diag();
    // repack 档位必须与"池里谁承接得起映射"一起在加载**前**报出：
    // 加载后 RSS 里多出来的那份匿名拷贝，只有对着这一行才认得出是 repack。
    log_extra_bufts_diag();

    // ── 设备池归属：显式"全池参与"，不钉到 CPU ──────────────────────────────
    // 上一版在这里把 mmap=1 的池钉到 CPU 唯一一台，代价是**强制失去 GPU/NPU 加速**。
    // 那个前提（"池里有 OpenCL/HTP 就必然有一组走拷贝"）是错的：分组是按**层**分的
    // （详见这段函数体上方 `设备池归属` 那一段注释），CPU 组始终走映射，
    // 与 OpenCL 在不在池里无关。所以这里回到全池，并把"全池"写成**契约**。
    //
    // 为什么两支都显式写 `nullptr`，而不是"什么都不做、靠库默认值"：
    // `llama_model_default_params()` 给的**恰好**也是 NULL，所以行为等价 ——
    // 但"没写"读起来像漏掉，谁也不知道这是设计还是疏忽。本仓库已经在
    // `da30046`（load_mode 只落一半、另一半交给库默认值）上吃过这个亏，
    // 同一个形态不在设备池上再犯一次。日志把两支的归属都讲清楚。
    if (useMmap == JNI_TRUE) {
        mp.devices = nullptr;
        jlog("[mmap池] mmap=1 → 全池参与（显式 nullptr）｜ 按层分组：CPU 组走映射，"
             "GPU/NPU 组进显存 —— 两者不互斥，加速不受影响");
    } else {
        mp.devices = nullptr;
        jlog("[mmap池] mmap=0（直读档）→ 全池参与（显式 nullptr）｜ 权重按设计常驻内存，"
             "GPU/NPU 加速不受影响");
    }
    long rss_before_kb = proc_rss_kb();
    measure_repack_before();          // 建 repack 缓冲前先记一次 baseline
    S.model = llama_model_load_from_file(modelPath.c_str(), mp);
    measure_repack_after();           // 加载后量差 → 这一档**实建**了多少
    log_repack_applied();
    if (!S.model) { LOGE("model load failed: %s", modelPath.c_str()); return JNI_FALSE; }
    S.vocab = llama_model_get_vocab(S.model);

    // ── mmap 开关的**下半场**：把库预填充进来的页还回去 ────────────────────
    // 上游 `load_tensors()` 里 `init_mappings(true, …)` 的 prefetch 是硬编码的，
    // `load_mode=MMAP` 只保证"映射建立"，挡不住它顺手 `MAP_POPULATE` 把整个
    // GGUF 拉进物理内存 —— 于是映射在、RSS 却照样是模型大小（用户报的正是这个）。
    // 这里在加载**之后**用同一份 page cache 把那些干净页交还内核（详见函数注释）。
    //
    // 只在用户要求 mmap 时做：`useMmap=false` 走的是真直读，权重本来就该常驻，
    // 对它 DONTNEED 只会让后续推理反复回读文件（把"诊断档"拖成更慢的档）。
    if (useMmap == JNI_TRUE) {
        int  n_ranges  = 0;
        int  n_deleted = 0;
        long n_bytes   = 0;
        // ── 读数有三个**时刻**，此前被压成两个，才产出"自相矛盾"的那一行 ──
        // 用户 0.9.132 报回来的两轮读数形如：
        //   [repack结果] 加载增量 RSS 5720 MiB
        //   [mmap释放] 区间 2 个 / 5072 MB ｜ RSS 258 MB → 3086 MB（省 -2827 MB）
        //             ｜ GGUF 文件映射驻留 2856 MB → 0 MB
        // 同一行里"驻留归零"（说文件页没了）与"RSS 反而涨 2827"（说文件页还在）**不可能
        // 同时成立** —— 但矛盾不在两个探针，在这行**把三个时刻压成了两个**：
        //
        //   时刻 A = 加载**前**（空进程，~258 MB）        ← rss_before_kb
        //   时刻 B = 加载**后**、释放**前**（峰值，~5980 MB）← 此前**从未**量过
        //   时刻 C = 释放**后**（~3086 MB）              ← rss_after_kb
        //
        // 于是 `(A - C)` 被写成"（省 %ld MB）"，而它算的是"加载+释放之后净剩多少" ——
        // A 是空进程、C 必然更高，**这个数恒为负**，与"释放有没有生效"毫无关系。
        // 同时"驻留 2856 MB"取的是**时刻 B 之前**的值，与时刻 C 的 RSS 摆在同一行、
        // 却不标时刻 —— 读的人自然把 2856 当成"释放后还在"，于是判成自相矛盾。
        //
        // 真正该报的是 **B → C**：释放动作本身回收了多少。所以这里补量时刻 B，
        // 并把三个时刻**各自标出来**，让这一行自证而不是自打脸。
        const long rss_loaded_kb      = proc_rss_kb();          // 时刻 B：加载后、释放前
        const long file_map_before_kb = proc_file_mapped_kb(modelPath.c_str());  // 时刻 B 的驻留
        if (release_populated_pages(modelPath.c_str(), &n_ranges, &n_bytes, &n_deleted) == 0) {
            long rss_after_kb = proc_rss_kb();                   // 时刻 C：释放后
            long file_map_kb  = proc_file_mapped_kb(modelPath.c_str());
            // 这一行同样是**自证点**：没有它，"到底省了没有"只能装机读 /proc，
            // 与上一轮 load_mode 日志同一个教训。
            // ⚠ 第一版正是**只报"已归还"**而没报量 —— 那条是恒真的，
            // 于是"一个页都没还"与"真的还了"在日志里同形。现在报出
            // **实际认到的区间条数、覆盖字节数**，以及文件映射驻留的前后对比。
            // 命中方式也要自证：内核给 VMA 追加 " (deleted)" 的机型上，"认到几个区间"
            // 必须能解释成"其中几个是走 deleted 后缀认出来的" —— 否则下次再出
            // 本条事故那种"两处读数自相矛盾"时，又要从头推一遍（见 maps_line_is_path）。
            // 三个数**各标时刻**，缺一个都会被读成另一个时刻的值（下面"读数三时刻"那段）：
            //   · 释放前/释放后 + 本次释放回收 = **B → C 的释放动作**
            //   · 加载总增量 = **A → B 的加载动作**，即上一行 `[repack结果]` 的同一个数
            //     （这里只作对照，不重复报数值，避免同一行里两个"增量"又混成一个）
            jlog("[mmap释放] 已对库映射归还预填充页 ｜ 区间 %d 个 / %ld MB%s ｜ "
                 "RSS 释放前 %ld MB → 释放后 %ld MB（本次释放回收 %ld MB，正数=真回收）｜ "
                 "本次加载总增量见上一行 `[repack结果]`（A→B）｜ "
                 "GGUF 文件映射驻留 %ld MB → %ld MB（均为释放前后各自实测）",
                 n_ranges, n_bytes / (1024 * 1024),
                 n_deleted > 0 ? "（含内核 (deleted) 后缀 %d 个）" : "",
                 rss_loaded_kb / 1024, rss_after_kb / 1024,
                 (rss_loaded_kb >= 0 && rss_after_kb >= 0) ? (rss_loaded_kb - rss_after_kb) / 1024 : -1,
                 file_map_before_kb / 1024, file_map_kb / 1024);
        } else {
            jlog("[mmap释放] 未能归还预填充页（不影响加载，最坏情况：内存占用回到未释放形态）"
                 "｜ 文件映射驻留 %ld MB", file_map_before_kb / 1024);
        }
    } else {
        jlog("[mmap释放] mmap=0（直读档），权重按设计常驻，不做归还");
    }

    // ── 共享存储（FUSE）：这里只做**提示**，不做判定 ────────────────────────
    // 上一轮我把这条写成"一票否决 + 请复制到应用内"，**两半都不准确**，这里收回：
    //   · 应用私有模型目录 `getExternalFilesDir()` 也在 `/storage/emulated/0/…` 这个
    //     挂载下。Android 11+ 的 FUSE passthrough 生效时（内核 5.4+ 且 MediaProvider
    //     开启），该挂载下的 mmap 页同样是**共享 page cache**，一份就够。
    //     于是"复制到应用内"根本换不到省内存 —— 原来那条建议会把人送去白搬 4~8 GB。
    //   · 反过来，在**没有** passthrough 的机型上，公共目录与私有目录一样吃亏，
    //     此时唯一能绕开的办法是让映射落在**下层真实文件**上，例如走
    //     `content://` 的 fd 交给 native（`/proc/self/fd/N`）而不是反解成裸路径。
    // 是否 passthrough 由 ROM/内核决定，native 侧连一个可靠的探测量都没有，
    // 所以这里**不下结论**，只把路径记下来，与上面的 `[mmap释放]` 读数对照着用：
    // 归还后 RSS 真落下来了 → 这条路是共享的；没落 → 大概率在 FUSE 拷页路径上。
    if (modelPath.find("/storage/emulated/") == 0 || modelPath.find("/sdcard/") == 0) {
        jlog("[mmap提示] 模型位于共享存储路径 %.*s… —— 该挂载是否走 FUSE passthrough"
             "决定 mmap 页能否共享（Android 11+ 与内核/ROM 相关，native 侧探不出来）。"
             "判据看上一行的 [mmap释放] 读数：RSS 真落下来即共享。",
             48, modelPath.c_str());
    }

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

// ══════════════════════════════════════════════════════════════════════════
//  嵌入（embedding）模型：加载 / 卸载 / 前向
// ══════════════════════════════════════════════════════════════════════════
//
// 为什么单独一条加载路径，而不是给 nativeLoadModel 加个 role 参数：
// 两者的**加载约束是相反的**，混在一条函数里迟早会有一条分支写错：
//   · 对话:  `embeddings=false`，KV 要留给前缀复用（n_ctx 按对话长度给）；
//   · 嵌入:  `embeddings=true`，KV **是纯冗余**（嵌入不做自回归，没有可复用的 KV），
//             `n_ctx` 只按最长单条文本给（512 足够），否则白占一份大 KV ——
//             而这一份是**不可协商**的内存（见下）。
// 混在一起还会让"加载嵌入模型时误拆对话模型"变成可能（unload 的第一行），
// 而症状是"聊到一半模型没了"，极难归因。
//
// ⚠ `embeddings=true` 会在 ctx 里**常驻一份 output buffer**（每层的输出都要留下来
// 供 `llama_get_embeddings_*` 取），所以 n_ctx 给多大，这份就按多大分配。
// 嵌入模型不需要长上下文（检索是逐条的、每条就一两句），n_ctx 给小值
// 是"既省内存、又不影响功能"的**唯一**正确档位。默认 512。

/** 嵌入模型的**每条**文本可用上下文（默认）。它直接决定常驻 output buffer 的大小。 */
static const int kEmbedDefaultCtx = 512;
/** 嵌入 ctx 的**总量**上限（所有序列加起来的 KV 格数）。超过它一律钳到上限：
 *  **不报错**（嵌入模型的长文本应先分块，而不是让整个 ctx 跟着涨）。放开的代价是
 *  一份在手机上吃不下的大 KV —— 后者会让加载直接失败，用户看到"加载失败"
 *  却不知道是 ctx 给大了。 */
static const int kEmbedMaxCtx = 4096;

/**
 * 一次合批最多塞几条（= 合批用掉的 seq 数上限）。
 *
 * ⚠ **这个数与 `n_ctx` 是同一个数的两面，必须一起算**（本轮真踩到过）：
 * 库里 `n_ctx_seq = n_ctx / n_seq_max` 是**每个序列**能用的位置数
 *（vendor 文案原文：`%s: n_ctx_seq = %u`，且 `n_ctx is not divisible by
 * n_seq_max - rounding down to %u`）。于是：
 *   · 把 `n_seq_max` 提到 8 而 `n_ctx` 仍是 512 时，`n_ctx_seq` 掉到 **64** ——
 *     单条路径里一条 300 token 的文本，`pos` 从 0 排到 299，**越过了 64**，
 *     库报 `sequence %d positions are not continuous` 或直接算错；
 *   · 也就是说"给合批开 8 个 seq"这件事，**必须同时把 `n_ctx` 放大 8 倍**，
 *     否则它把**单条**路径一起弄坏（而单条路径此前是对的）。
 * 所以下面 `n_ctx = 每条预算 × kEmbedBatchRows`（再按 `kEmbedMaxCtx` 钳），
 * 而 `n_seq_max = kEmbedBatchRows` —— 两处由同一个数推出。
 *
 * 取 8 的理由：合批的收益来自"摊薄每次 decode 的固定开销"，而这个开销按
 * **整批 token 数**算；一条 400 字 ≈ 300 token，8 条约 2400 token —— 已经超过
 * `n_batch` 默认的 2048，会触发库内部按 ubatch 再切。再往上加只是把切分
 * 次数堆上去，固定开销省不动了，而内存峰值（整批 output buffer）线性涨。
 * 另外 8 与嵌入线程数（2~8）同量级：条数远超核数时，同一批判里也只有一个
 * 前向在跑，塞更多条目只是排更长的队。
 */
static const int kEmbedBatchRows = 8;

/**
 * 嵌入 ctx 的**两道门**（`n_batch` / `n_ubatch`）显式设成的值（0.9.212）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么必须显式设：它们是**两道独立的门**，而此前一个都没传
 * ═══════════════════════════════════════════════════════════════════════
 * 嵌入这条加载路径此前 `cp.n_batch` / `cp.n_ubatch` **一个都没传**（只有内联的
 * 对话模型 `nativeLoadModel` 有 `batchSize` / `ubatchSize` 两个入参），于是两个
 * 都是库默认值，用户与日志里都看不出来。两道门各管一支：
 *
 *   · `n_batch`  —— 因果模型（Qwen3-Embedding → `llama_decode`）那条
 *     `llama-context.cpp:1711` 的 `LM_GGML_ASSERT(n_tokens_all <= cparams.n_batch)`；
 *   · `n_ubatch` —— 非因果模型（BERT 系 bge-large → 库改走 `llama_encode`）那条
 *     `:1426` 的 `LM_GGML_ASSERT(cparams.n_ubatch >= n_tokens && "encoder requires
 *     n_ubatch >= n_tokens")`。
 *
 * 真机上正是后者把用户挡死的：**bge-large-zh-v1.5 合批 8 → 4 → 2 全崩、只有 1 不崩**。
 * 原因不是"批太大"，而是批大小（`n_seq_max`）拧的是**另一个旋钮** —— 一条 430 token
 * 的块，2 条一合就 > 512，无论把批调到几，门都还是那扇 512 的门；只有 1 条时
 * `n_seqs == 1`，那道门退化成恒真，所以"只有 1 不崩"。
 *
 * ⚠ release（NDK 默认 `-DNDEBUG`）里断言失败 = `abort()`：不是"这一批失败"，
 * 是整个进程当场消失 —— 用户看到的是"建索引闪退"，日志最后一行只有知识库进度。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 为什么是 `n_ctx`（4096）而不是"够用就好"
 * ═══════════════════════════════════════════════════════════════════════
 * 8 条满长（8 × 511 = 4088）必须一次装得下，否则每一批都要切段（多一次 decode），
 * 而"满长"不是理论值：bge-large 在中文语料上条条压在 511 的截断点上（真机读数）。
 *
 * ⚠ 内存代价**没有白付**：`embeddings=true` 时库按 `n_ctx` 分配 KV 与 output
 * buffer（那份本来就是 4096 的量级），`n_batch` / `n_ubatch` 只决定"内部缓冲与
 * 计算图按多大切" —— 真机上表现为多出来的 compute buffer。装出来后要看
 * `[embed内存]` 那一行的 RSS 增量（0.9.212 补的读数），不够就往下调这一档。
 *
 * ⚠ `n_batch % n_ubatch == 0` 是库自己断言过的（除不尽时 `ubatch` 那一路会
 * 报 `n_batch is not divisible by n_ubatch`）—— 所以这两个数**只能一起改**，
 * 而它们的值必须 ≤ `kEmbedMaxCtx`（= n_ctx 上限），否则请求一个比自己上下文
 * 还大的批，库当场拒绝建 ctx（症状是"嵌入模型加载失败"，而用户看不懂为什么）。
 */
static const int kEmbedGate = 4096;

JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel(
        JNIEnv * env, jclass, jstring jpath, jint nCtx, jint nThreads, jboolean useMmap) {
    JNI_SPAN("loadEmbedModel");
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return JNI_FALSE;
    std::string modelPath(path);
    env->ReleaseStringUTFChars(jpath, path);

    // ⚠ 只拆 **E**，绝不碰 `S`（对话那份）。这一行是"双常驻"的契约本身：
    // 写成 `unloadModelInternal(S)` 会让"在 App 里选一个嵌入模型"顺手把正在用的
    // 对话模型拆掉 —— 不崩、只是下一次生成变成"没加载模型"。守卫正面钉这一条。
    unloadModelInternal(E);

    // mmap 与对话模型同一口径：显式写两支，不靠库默认（见 nativeLoadModel 的
    // `load_mode` 那段 —— "开关只落一半"是本仓库吃过亏的地方）。
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                 // 嵌入走 CPU：HTP/OpenCL 的 output buffer 语义未验，先不冒险
    mp.load_mode = useMmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    mp.use_extra_bufts = model_use_extra_bufts();
    mp.devices = nullptr;
    E.model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!E.model) { LOGE("embed model load failed: %s", modelPath.c_str()); return JNI_FALSE; }
    E.vocab = llama_model_get_vocab(E.model);

    llama_context_params cp = llama_context_default_params();
    // ── 容量收口：`kEmbedBatchRows × 每条预算 ≤ 两道门` ─────────────────────
    // ⚠ 这一节是 0.9.213 的**唯一**一处把"条数"与"每条预算"绑起来的地方。
    // 此前它们是两个各写一处的数（`kEmbedBatchRows` 8 + 调用方给的 perSeq），
    // 而"8 条满长能不能一次装下"这件事**没有任何一处代码在管** —— 它只活在
    // 两个常量的巧合里：`kEmbedMaxCtx`(4096) 恰好等于 `kEmbedGate`(4096)，
    // 于是 `ctx ≤ gate`，于是 `rows × perSeqCtx ≤ ctx ≤ gate`。
    // **改任一个常量都会破**，而破的形态不是报错，是 `abort()`：
    // 若哪天 `kEmbedMaxCtx` 提到 8192 而 `kEmbedGate` 仍是 4096，则
    // `perSeqCtx = 8192/8 = 1024`，8 条满长 = 8 × 1023 = 8184 > 4096 = 门 →
    // 提交那一刻进程消失。所以这里不再靠巧合，改成**算一次、且由构造保证**：
    //   ① 两道门的**绝对**上限先定（`gatesAbs`）；
    //   ② 每条的预算由它**除**出来（`perSeqMax`）—— 不是"先取预算再乘条数"；
    //   ③ 调用方给的 perSeq 一律钳进 `[1, perSeqMax]`。
    // 于是 `kEmbedBatchRows × perSeqCtx ≤ ctx ≤ gatesAbs` 是**恒真**的，
    // 与两个常量各是多少无关。
    //
    // 两道门取哪个数：`n_batch`(因果/Qwen3 那扇 `:1711`) 与 `n_ubatch`
    // (非因果/BERT 系 bge 那扇 `:1426`) 必须**同源**，所以这一节根本不让它们
    // 分开算 —— 只算一个 `gate`，两处各赋一次（见下）。
    const int gatesAbs = kEmbedGate < kEmbedMaxCtx ? kEmbedGate : kEmbedMaxCtx;
    // 每条预算的上限：由门**除**条数得出，而不是"先取预算再乘条数再钳总量"。
    // 后者看着等价，但它把"装不装得下"变成了一个**事后**结论（`ctx ≤ gate` 靠
    // 两个常量相等），而这里它是**构造性**的。
    int perSeqMax = gatesAbs / kEmbedBatchRows;
    if (perSeqMax < 1) perSeqMax = 1;         // 门比条数还小时兜底（不崩，只是每条 1 格）
    // ── `n_ctx` 与 `n_seq_max` 必须一起算（见 kEmbedBatchRows 那段推导）──────
    // `nCtx` 的语义是**每条文本**的预算（调用方/默认值都是这个意思），
    // 而库里 `n_ctx_seq = n_ctx / n_seq_max`。合批要占用 `kEmbedBatchRows` 个
    // seq，所以总量必须是"每条预算 × 条数"。
    //
    // ⚠ 顺序不能反：先把"每条预算"钳到上限、再乘条数 —— 那样总量会变成
    // `kEmbedMaxCtx × 8`，一份手机吃不下的大 KV（症状是"加载失败"，而用户
    // 看到的原因与"上下文给大了"毫无关系）。先乘、再钳总量才是对的那一支。
    int perSeq = nCtx > 0 ? (int) nCtx : kEmbedDefaultCtx;
    if (perSeq < 1) perSeq = kEmbedDefaultCtx;
    // ⚠ 钳的是"每条预算"（不是总量）：这一下就是上面那条恒等式的**来源**。
    // 用户把这一格填到 4096 时，他实际拿到的是 512 —— 而那一格现在会拿到这条
    // 收口的读数（`[embed内存]` / `embed model ready`），不再静默失效。
    if (perSeq > perSeqMax) perSeq = perSeqMax;
    long long total = (long long) perSeq * (long long) kEmbedBatchRows;
    if (total > (long long) kEmbedMaxCtx) total = kEmbedMaxCtx;
    // `n_ctx` 不能小于条数（否则每条连 1 个位置都没有 —— 除法会把每条的预算
    // 抹成 0，库直接拒绝加载）。钳一个下限，宁可少批几条也不许加载失败。
    if (total < (long long) kEmbedBatchRows) total = kEmbedBatchRows;
    // ⚠ 必须是 `kEmbedBatchRows` 的**整数倍**：库对除不尽的 `n_ctx / n_seq_max`
    // 会"rounding down"（vendor 文案原文），而那一下**不报错** —— 每条的预算
    // 悄悄少一格，最后一条文本恰好撞到就失败。向下取整到条数的整数倍，
    // 让"我们以为的每条预算"与"库给的"逐格相等。
    total = (total / kEmbedBatchRows) * kEmbedBatchRows;
    const int ctx = (int) total;
    cp.n_ctx = (uint32_t) ctx;
    // 每条的预算（= 库会算出的 `n_ctx_seq`）—— 单条路径的截断判据用它，
    // 合批的每条也一样。**不**直接用调用方给的字面值：总量被钳之后，
    // 每条的预算跟着变了，用旧值截断会让"截断点"与"库的位置上限"分岔。
    const int perSeqCtx = ctx / kEmbedBatchRows;
    // 嵌入不做自回归 —— KV 是纯冗余，但**不支持"不分配"**：llama.cpp 在
    // embeddings=true 时照样按 n_ctx 分配 KV。既然要占，就用最省的一档（F16 默认）；
    // 量化 KV 只会引入一条"精度损失 + 需要 flash attn"的分支，对嵌入毫无收益。
    int nt = nThreads > 0 ? nThreads : 4;
    cp.n_threads = nt; cp.n_threads_batch = nt;
    // ⚠ `n_seq_max` 必须 ≥ 合批条数：合批靠 `seq_id` 隔离条目（见 embed_text.h 的
    // `plan_batch`），而 `seq_id >= n_seq_max` 时库的行为是**未定义**的
    // （不是崩，是那几条的 KV 落进不存在的序列 —— 向量不对、接口 200）。
    // 两处只有这一个数（`kEmbedBatchRows`），不各写一个字面量。
    cp.n_seq_max = (uint32_t) kEmbedBatchRows;
    // ── 两道门：**显式设**，不留库默认（0.9.212 修的真机 abort）─────────────
    // 见 `kEmbedGate` 那段：`n_batch` 管因果模型那扇（`:1711`），`n_ubatch` 管
    // 非因果模型那扇（`:1426`）。两条断言都在 `llama_decode` / `llama_encode`
    // 的**正常路径**上、且在库自己按 ubatch 切分**之前** —— 所以"库会替我切"
    // 救不了，release 里就是 `abort()`。
    //
    // ⚠ 钳到 `ctx`：请求一个比自己上下文还大的批，库当场拒绝建 ctx
    //（症状是"嵌入模型加载失败"，而用户完全看不出是批参数给大了）。
    // `kEmbedGate` 与 `kEmbedMaxCtx` 同值只是**当下**的事实，钳这一下是防
    // 将来只改一处 —— 两处由 `min` 收口，改任一处都不会请求到越界的批。
    //
    // ⚠ 但**只钳到 ctx 是不够的**（0.9.213 收口的那一条，见上面 `gatesAbs` 那段）：
    // `gate` 必须同时 ≤ `perSeqMax × kEmbedBatchRows`，否则"8 条满长一次装不装得下"
    // 会重新变成巧合。这里两个上界一起取 `min`，而 `perSeqMax` 正是从 `gatesAbs`
    // 除出来的 —— 于是这条恒等式在**两个常量各是多少**之外成立。
    const int gate = kEmbedGate < ctx ? kEmbedGate : ctx;
    cp.n_batch  = (uint32_t) gate;
    cp.n_ubatch = (uint32_t) gate;
    // ⚠ 这里**不再**量"建 ctx 前后的 RSS 差"（0.9.212 那一版量过，量错了，见下面
    // `[embed内存]` 那一节）：基线取在模型加载**之后**时，那 892 MB 里已经含了
    // 模型自己 mmap 的页，于是量出来的是"建 ctx 时恰好被 touch 的几页"（真机 +3 MB），
    // 而一份 4096 的 ctx 光 output buffer 就不止 3 MB。一个量不准却像量准了的数，
    // 比没有读数更坏。要真量差值，基线得挪到加载模型**之前** —— 那是另一件事。
    E.ctx = llama_init_from_model(E.model, cp);
    // 记下**实际**落值（见 Session::seq_max 的说明）：合批只信这个数。
    E.seq_max = kEmbedBatchRows;
    if (!E.ctx) {
        LOGE("embed ctx create failed (n_ctx=%d)", ctx);
        llama_model_free(E.model); E.model = nullptr; E.vocab = nullptr;
        return JNI_FALSE;
    }
    E.n_ctx = (int) llama_n_ctx(E.ctx);
    // ⚠ 取库自己算出来的每条预算（`llama_n_ctx_seq`），**不是**我们自己除的那个。
    // 两者若有分岔（例如库对 n_ctx 做了别的钳制），截断点必须跟库走 ——
    // 用我们算的那个数就是在赌"库的钳制与我们的一致"，而赌输的表现是
    // 某几条静默算出错误向量。库不给（返回 0）时回落到我们算的值。
    const uint32_t nCtxSeqLib = llama_n_ctx_seq(E.ctx);
    E.n_ctx_seq = nCtxSeqLib > 0 ? (int) nCtxSeqLib : perSeqCtx;
    // ⚠ 两道门也读**库的实数**（`llama_n_batch` / `llama_n_ubatch`），不是 `cp` 的值。
    // 两者分岔时（库对 `n_batch` 有别的钳制、或将来有人改回"不传"）必须能一眼看出：
    // 打 `cp` 的值等于担保"库收了我们给的那个数"，而那正是本仓库被推翻过两轮的假设。
    E.n_batch  = (int) llama_n_batch(E.ctx);
    E.n_ubatch = (int) llama_n_ubatch(E.ctx);
    // ── 容量读数：这一档**最多能一次编几条满长**（0.9.213 收口）──────────────
    // 见 `batch_full_rows_fit` 那段：这个数此前只能靠"拿两个常量去推"，而本仓库
    // 正是靠推在 PR #285 里错了两轮。现在它由**库的实数**算出来、写进日志。
    //
    // ⚠ 两道门取**较小**的那个：因果模型（Qwen3）看 `n_batch`，非因果（bge）
    // 看 `n_ubatch` —— 同一个进程里也可能两份模型同时驻留，而"这一批能不能提交"
    // 由**实际走的那一扇**决定。取 `min` 是保守且正确的：装得下就意味着两扇都过。
    // 两扇分岔时（库有自己的钳制）这一行的值会**小于** `kEmbedBatchRows`,
    // 那就是"满长那批会被切段"的先兆 —— 看得见，而不是等某天 abort。
    const int gateMin = E.n_batch < E.n_ubatch ? E.n_batch : E.n_ubatch;
    const int fullRowsFit = embedtext::batch_full_rows_fit(gateMin, E.n_ctx_seq);
    if (fullRowsFit > 0 && fullRowsFit < kEmbedBatchRows) {
        jlog("[embed] ⚠ 容量余量不足：两道门取小 = %d，每条满长 = %d tok → 一次只装得下 %d 条满长"
             "（合批上限 %d 条）。满长的那几批会**按条目边界切段**（安全，但多 decode 几次）；"
             "要一次装下就调小「嵌入每批上下文」或改用词表更省的模型",
             gateMin, E.n_ctx_seq - 1, fullRowsFit, kEmbedBatchRows);
    }
    {
        // ── 这一档的内存账单：报**理论量**，不报"建 ctx 造成的 RSS 差"─────────
        // ⚠ 0.9.212 那一版报的是"建 ctx 前后 RSS 的差"，而它**量错了东西**：
        // 基线取在**模型加载之后**，那 892 MB 里已经含了模型自己 mmap 进来的页
        //（bge-large-zh-v1.5-q8_0 本身体量就在那个量级）—— 于是"建 ctx 时恰好
        // 被 touch 的那几页"被当成了"这一档 ctx 自己吃的"，真机读数是 `+3 MB`，
        // 而一份 4096 的 ctx 光 output buffer 就不止 3 MB。**一个量不准却看起来
        // 像量准了的数**，比没有读数更坏（本仓库吃过这一类亏）。
        //
        // 所以这一版只报**算得准的**：几份按 `n_ctx` / `n_batch` 分配的 buffer 的
        // 理论字节数（`n_ctx × 每元素字节`，元素数就在手上，不涉及任何猜测），
        // 外加**进程 RSS 的绝对值**（标成"含模型 mmap"，不许当增量读）。
        // 差值哪天真要量，得把基线挪到**加载模型之前**，那是另一件事。
        //
        // 三份 buffer 按 `embeddings=true` 的实际分配口径列（llama.cpp）：
        //   · KV    = n_ctx × n_layer × 2(K/V) × n_embd_kv × 2B（F16 默认）
        //   · out   = n_ctx × n_embd_out × 4B（F32 —— 池化结果要保精度）
        //   · compute = 按 n_batch/n_ubatch 切的那份，**只有真机量得出来**，不报数
        // 前两份是上界（部分模型 K/V 维度与 n_embd 不等，这里按 n_embd 估并
        // **标注为估算**）；它们的用处是"哪个量级"—— 调小这一格时它跟着降，
        // 而"降多少"是线性的、可预期的。
        const long rss_after_ctx_kb = proc_rss_kb();
        const long long nEmbd   = (long long) llama_model_n_embd(E.model);
        const long long nEmbdOut= (long long) llama_model_n_embd_out(E.model);
        const long long nLayer  = (long long) llama_model_n_layer(E.model);
        // KV：每层每位置 2 个张量（K/V）各 n_embd 个 F16（2 B）
        const long long kv_mb   = (long long) E.n_ctx * nLayer * 2 * nEmbd * 2 / (1024 * 1024);
        // output：每位置 n_embd_out 个 F32（4 B）
        const long long out_mb  = (long long) E.n_ctx * nEmbdOut * 4 / (1024 * 1024);
        jlog("[embed内存] n_ctx=%d n_batch=%d n_ubatch=%d（每条预算 %d tok，一次最多 %d 条满长）"
             "估算 KV≈%lld MB + output≈%lld MB（按 n_layer=%lld/n_embd=%lld/n_embd_out=%lld 估，"
             "不含按 n_batch 切的 compute buffer）｜ 进程 RSS %ld MB（**含模型 mmap**，别当增量读）。"
             "内存高就调小「嵌入每批上下文」—— 上面两个估算值会跟着线性降；批大小那格不影响这里",
             E.n_ctx, E.n_batch, E.n_ubatch, E.n_ctx_seq, fullRowsFit,
             kv_mb, out_mb, nLayer, nEmbd, nEmbdOut, rss_after_ctx_kb / 1024);
    }
    // ⚠ `llama_set_embeddings(ctx, true)` 是**必调**的一跳：不调时
    // `llama_get_embeddings*` 取到的是 nullptr 或未初始化的 output buffer
    // （取决于库版本），而症状是**静默返回零向量** —— 检索全空，接口 200。
    // 放在 ctx 建好之后立刻调，与"ctx 在则 embeddings 在"这个不变式绑定。
    llama_set_embeddings(E.ctx, true);
    E.embedding = true;      // 角色标记：卸载日志、守卫、以及"这是嵌入那份"的判据都读它
    // 池化类型**必须**在这里读：它由 ctx 持有（`llama_pooling_type(ctx)`），
    // 而 nativeEmbed 只有 ctx 可用。放在与 `embeddings=true` 同一段落里，
    // 让"与嵌入相关的加载期取值"集中在同一处 —— 散开写迟早会漏一个。
    E.pool_type = (int) llama_pooling_type(E.ctx);
    E.n_used = 0; E.n_rem = 0;
    E.pending.clear();
    E.kv_rounds = 0;
    embed_failure_reset();

    // ── warm-up：用一次真实前向把 output buffer **写干净**（方案 B 的一条）────
    // 这一段的**目的**就是它自己写的那句话：让 ctx 里那块 output buffer
    // 从"未初始化"变成"被真实前向写过"。它的判据不是"跑没跑过前向"，
    // 而是"**每一行**是不是都被写过"—— 这两件事早先被当成了一件，见下。
    //
    // ═══════════════════════════════════════════════════════════════════════
    // ⚠⚠ 早先那版（BOS 单 token、只要一行输出）有三处必然的错
    // ═══════════════════════════════════════════════════════════════════════
    //  ① **CLS 上直接进程级崩溃**。库的门禁在 `llama_batch_allocr::init`
    //     （vendor `.so` 里符号 `_ZN18llama_batch_allocr4initE…`），认的是
    //     `llama_batch.logits[]` 覆盖了所有 input token 没有：
    //       `pooled embedding requires that all tokens are output
    //        (n_outputs_all = %d, n_tokens_all = %d)`
    //     而 `init` 在 `llama_context::decode` 的**正常路径**上；
    //     release（NDK 默认 `-DNDEBUG`）里 `ABORT` 就是 `abort()` →
    //     BGE 系（`pooling=2`）**每次加载嵌入模型都崩**，连下面那句
    //     "失败但不影响加载"都来不及打。
    //  ② **"库会替我们补"补的是真实 input**。`llama_context::decode` 里那条
    //     `embeddings required but some input tokens were not marked as
    //     outputs -> overriding` 补的是 **input token** —— 而这里只有 BOS 一个，
    //     "补"完还是只有第 0 行：**warm-up 想干的事根本没干成**，
    //     只剩一条假绿日志。
    //  ③ 于是"只要一个 output 就够"这条推理**每一步都错**：库对 NONE/LAST
    //     确实只要一个，但"只要一个"的**代价**是剩下的行一个字节都没写 ——
    //     而 output buffer 是整块分配的，"读一行"按 `n_embd` 走，与"写了哪几行"
    //     无关。要它变干净只能**整段都进 output**。
    //
    // 所以现在的口径只有一条：**batch 与真实编码路径同形** —— 同一套
    // `plan_pooling` 置位、同一个"给库的门禁"判据（`warmup_plan_usable`）。
    // warm-up 与真实路径不再各写一套置位规则，也就不存在"两处契约不同"。
    {
        // 取一条**真实**的短文本（不是 BOS 单点）：BOS 只有一个 token，
        // 而 `warmup_plan_usable` 的下限是 2 —— 它同时保证
        // "LAST 档读的那一行"与"第 0 行"不是同一个位置。
        static const char * kWarmupText = "warm up";
        int need = llama_tokenize(E.vocab, kWarmupText, (int32_t) strlen(kWarmupText),
                                  nullptr, 0, true, true);
        if (need < 0) need = -need;
        std::vector<llama_token> wtoks((size_t) (need > 0 ? need : 1));
        const int got = llama_tokenize(E.vocab, kWarmupText, (int32_t) strlen(kWarmupText),
                                       wtoks.data(), (int32_t) wtoks.size(), true, true);
        if (got <= 0) {
            // 取不到 token 就跳过：热身是"去掉不确定性"，不是功能前提。
            // 但**必须留日志** —— 静默跳过就是又一条假绿。
            jlog("[embed] warm-up 跳过：tokenize 失败（不影响加载；首条真实文本仍会尝试）");
        } else {
            wtoks.resize((size_t) got);
            const int wtok = (int) wtoks.size();
            // **同一个计划函数**：真实路径读哪个 pooling 就走哪一档，
            // warm-up 不许另立一套（那正是上一版与库门禁分家的地方）。
            const embedtext::PoolPlan wplan = embedtext::plan_pooling(E.pool_type, wtok);
            if (!embedtext::warmup_plan_usable(wplan, E.pool_type, wtok)) {
                // 走到这里说明"这一次热身连形状都不合法"（token 太少 / 池化方式不支持 /
                // 整段进不了 output）。**不加载失败**，但要说清是哪一条不成立，
                // 否则用户看到的是"加载好了、检索全空"。
                jlog("[embed] warm-up 跳过：计划不可用（pooling=%d, n_tokens=%d, valid=%d）"
                     "（不影响加载；首条真实文本仍会尝试）",
                     E.pool_type, wtok, wplan.valid ? 1 : 0);
            } else {
                const bool all_rows = embedtext::warmup_wrote_all_rows(wplan, wtok);
                llama_batch wb = llama_batch_init(wtok, 0, 1);
                if (wb.token) {
                    for (int i = 0; i < wtok; i++) {
                        wb.token[i]     = wtoks[(size_t) i];
                        wb.pos[i]       = (llama_pos) i;
                        wb.n_seq_id[i]  = 1;
                        wb.seq_id[i][0] = 0;
                        // 逐位照计划置：这是与真实路径**同形**的唯一含义。
                        wb.logits[i]    = wplan.want_logits[(size_t) i] ? 1 : 0;
                    }
                    wb.n_tokens = (int32_t) wtok;
                    if (llama_decode(E.ctx, wb) != 0) {
                        // warm-up 失败**不影响加载成功**：真前向失败多半是这条
                        // 固定文本的问题，第一条真实文本仍可能成功。把它变成
                        // "加载失败"等于用一次热身把整个功能判死，代价远大于收益。
                        jlog("[embed] warm-up decode 失败（不影响加载；首条真实文本仍会尝试）");
                    } else if (!all_rows) {
                        // **如实**说明这一档只写了部分行：NONE/LAST 的库契约就是
                        // "至少一个 output"，warm-up 写不满是库决定的，不是 bug。
                        // 但不许假称"写干净了" —— 那会让下一个人以为未初始化
                        // 内存这个不确定性已经消除（这正是上一版的假绿）。
                        jlog("[embed] warm-up 只写了 %d/%d 行（pooling=%d 的库契约是"
                             "「至少一个 output」，整段进不了 output；未初始化内存这个"
                             "不确定性**未完全消除**）",
                             wplan.output_count, wtok, E.pool_type);
                    }
                    llama_batch_free(wb);
                } else {
                    jlog("[embed] warm-up 跳过：batch_init 失败（不影响加载）");
                }
                llama_memory_seq_rm(llama_get_memory(E.ctx), 0, -1, -1);
            }
        }
    }

    char desc[256] = {0};
    llama_model_desc(E.model, desc, sizeof(desc));
    // 一并打出 pooling / n_embd_out：这三个数决定了"句向量取哪一行、按什么步长读"，
    // 而它们**只有真机加载时才知道**（GGUF 元数据）。用户报"归一化失败"时，
    // 这一行是唯一能立刻分辨"池化取错"还是"维度不匹配"的证据 ——
    // 没有它就只能靠猜，或者让用户自己去看 GGUF 元数据。
    // 一并打出 n_seq_max / n_ctx_seq / 每条预算：合批之后 `n_ctx`（总量）与
    // `n_ctx_seq`（**每条**文本能用的位置数）不是同一个数（差 kEmbedBatchRows 倍），
    // 而"某条文本被截断"这个现象只由后者决定。日志里少打前者时，读的人会拿
    // 总量去解释截断点（方向错、且与真实原因差 8 倍）。
    //
    // ⚠ 再打 **`n_batch` 与 `n_ubatch`**（0.9.211 补）—— 这两个数是**两道独立的门**，
    // 而这一行此前一个都没打：
    //   · `n_batch`      —— 因果模型（Qwen3，走 `llama_decode`）那条 `:1711`
    //                       `n_tokens_all <= n_batch` 的整批容量门；
    //   · `n_ubatch`     —— 非因果模型（BERT 系 bge，走 `llama_encode`）那条 `:1426`
    //                       `cparams.n_ubatch >= n_tokens` 的物理切分门。
    // 0.9.211 之前嵌入这条加载路径 `cp.n_batch` / `cp.n_ubatch` **一个都没显式设**，
    // 所以这两个数**全是库默认**（`n_batch=2048`，`n_ubatch=min(n_batch,512)`）——
    // bge-large 那道 512 的门就是这么来的。0.9.212 起它们由 `kEmbedGate` 显式设。
    // **必须读库的实数**（`llama_n_batch` / `llama_n_ubatch`，落在 `E.n_batch` /
    // `E.n_ubatch`），不许打我们传给 `cp` 的那个值 —— 打 `cp` 等于担保"库收下了
    // 我们给的数"，而那正是本仓库被打回来过两次的那种假设。
    // ⚠ 再补 **`full_rows_fit`**（0.9.213）：这一档"最多一次装几条满长"。
    // 它是"两道门 × 每条预算"那条不变式的**读数**（见 `batch_full_rows_fit`）——
    // 有了它，读日志的人不必自己拿两个数去推（本仓库正是靠推在 PR #285 里错了两轮）。
    // 值 < `kEmbedBatchRows` 时说明离切段只差一步，那一行会在上面额外预警。
    LOGI("embed model ready: %s, n_ctx=%d, n_ctx_seq=%d, seq_max=%d, threads=%d, "
         "n_embd=%d, n_embd_out=%d, pooling=%d, n_batch=%d, n_ubatch=%d, full_rows_fit=%d",
         desc, E.n_ctx, E.n_ctx_seq, E.seq_max, nt, (int) llama_model_n_embd(E.model),
         (int) llama_model_n_embd_out(E.model), E.pool_type, E.n_batch, E.n_ubatch,
         fullRowsFit);
    // ── 0.9.212 起这两个数**是我们设的**（`kEmbedGate`），所以再报一次"设进去的"
    //    与"库收下的"是否一致 —— 分岔时（库有自己的钳制、或有人改回不传）
    //    必须能一眼看出，而不是等到某天 abort 了再回头推。
    if (E.n_batch != gate || E.n_ubatch != gate) {
        jlog("[embed] ⚠ 两道门没按 kEmbedGate=%d 落值：n_batch=%d n_ubatch=%d（库有自己的钳制？）"
             "—— 这会让满长的 %d 条一次装不下（切段仍安全，但那几批会多 decode 几次）",
             kEmbedGate, E.n_batch, E.n_ubatch, kEmbedBatchRows);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeHasEmbedModel(JNIEnv *, jclass) {
    return (E.model && E.ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbedDim(JNIEnv *, jclass) {
    if (!E.model) return 0;
    return (jint) llama_model_n_embd(E.model);
}

/**
 * 输出侧维度（`llama_model_n_embd_out`）—— 库据它分配 output buffer，
 * 而 `llama_get_embeddings_ith` 按 `n_embd` 步长读。两者在部分模型上不等，
 * 不等时读一行是未定义行为（见 nativeEmbed 顶部那段注释）。
 * 暴露出来是为了让 `/health` 与日志能**同时报出**这两个数 ——
 * 它们的差值就是"为什么某些条归一化失败"的直接答案。
 */
JNIEXPORT jint JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbedOutDim(JNIEnv *, jclass) {
    if (!E.model) return 0;
    return (jint) llama_model_n_embd_out(E.model);
}

/** 嵌入模型声明的池化类型原值（-1 = 取不到 / UNSPECIFIED）。 */
JNIEXPORT jint JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbedPooling(JNIEnv *, jclass) {
    if (!E.ctx) return -1;
    return (jint) E.pool_type;
}

/**
 * 最近一次嵌入失败的详情，编码成一个**固定字段顺序**的字符串：
 * `reason|n_tokens|n_embd|n_embd_out|pooling|norm`
 *
 * 为什么用字符串而不是再开 6 个 JNI 出口：这些字段只在"失败之后立刻被读一次"
 * 这一个场景里用，拆成 6 个方法就有 6 处返回值可能被写成常数（而它们都
 * 不影响功能、只影响归因 —— 归因错了没人会发现）。一个方法一次取齐，
 * 解析与拼装的两侧由守卫正面钉住（字段名/顺序/个数）。
 * 没失败时返回空串（`reason` 为 none 时不该被展示为"失败详情"）。
 */
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbedLastFailure(JNIEnv * env, jclass) {
    if (g_embed_fail.reason == embedtext::EmbedFailure::NONE) return env->NewStringUTF("");
    char buf[256] = {0};
    snprintf(buf, sizeof(buf), "%s|%d|%d|%d|%d|%.6g",
             embedtext::failure_name(g_embed_fail.reason),
             g_embed_fail.n_tokens, g_embed_fail.n_embd, g_embed_fail.n_embd_out,
             g_embed_fail.pooling, g_embed_fail.norm);
    return new_string_utf8_safe(env, buf);
}

/**
 * 嵌入模型的短描述（`llama_model_desc`）。与 `nativeModelDesc` 同一口径，
 * 但读的是 **E** —— 双常驻下两份 Session 各有各的模型，用 `nativeModelDesc`
 * 去报嵌入模型只会拿到对话模型那一份，症状是 `/health` 里
 * `model` 与 `embed_model` 永远是同一个字符串（看不出到底加载上没有）。
 */
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbedModelDesc(JNIEnv * env, jclass) {
    if (!E.model) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(E.model, desc, sizeof(desc));
    // 与 nativeModelDesc 同因：desc 按字节截断，中文名可能留下半个多字节序列
    return new_string_utf8_safe(env, desc);
}

/**
 * token id 数组 -> 文本（用**嵌入模型那一份** vocab）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要它：`/v1/embeddings` 的 `input` 允许是预 tokenize 的 id 数组
 * ══════════════════════════════════════════════════════════════════════════
 * 此前本端点**明确拒绝** id 数组（`EmbeddingsApi.fromRequest` 的注释至今保留着
 * 那份理由：不同模型的 token 语义不同，静默重编码等于换了个输入）。
 * 用户拍板要求支持之后，做法只能是**诚实的**一条：把 id 用**我们自己的 vocab**
 * 还原成文本，再走与字符串输入**完全相同**的那条路（tokenize -> 前向 -> 归一化）。
 * 这样：
 *   · 调用方给的 id 若本来就来自本模型（同一份 vocab），还原-再分词能往返，
 *     编码的正是它以为的那段文本；
 *   · id 若越界（不在 vocab 里）-> 返回空串，调用方报 400 并**说明是越界**，
 *     而不是拿一个与输入无关的向量、还回 200。
 *
 * 不在这里做前向，是因为"id -> 文本"与"文本 -> 向量"是两件事：
 * 前者只需要 vocab（不需要 ctx），后者才算。分开也让它可被宿主侧单测覆盖。
 *
 * `renderSpecial=true`（与生成侧 token_to_piece 同一口径）：special token
 * 一并文本化，否则 id 里的 BOS/EOS 会被悄悄吞掉，"还原出来的文本"与输入
 * 不再一一对应 —— 而调用方无从发现。
 *
 * 越界 id -> 整个函数返回**空串**（而不是跳过该 id 继续）：跳过会让
 * "还原出来的文本"与 id 数组不再对应，向量指向一段调用方没给过的文本。
 */
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeDetokenize(
        JNIEnv * env, jclass, jintArray jids) {
    JNI_SPAN("detokenize");
    if (!jids) return env->NewStringUTF("");
    if (!E.vocab) {
        jlog("[detokenize] 无嵌入模型 vocab，返回空串");
        return env->NewStringUTF("");
    }
    const jsize n = env->GetArrayLength(jids);
    if (n <= 0) return env->NewStringUTF("");
    std::vector<jint> ids((size_t) n);
    env->GetIntArrayRegion(jids, 0, n, ids.data());
    if (env->ExceptionCheck()) { env->ExceptionClear(); return env->NewStringUTF(""); }

    const int32_t nVocab = llama_vocab_n_tokens(E.vocab);
    std::string out;
    for (jsize i = 0; i < n; ++i) {
        const llama_token t = (llama_token) ids[(size_t) i];
        // 越界就是越界：不 clamp、不跳过、不猜。调用方拿到空串会 400，
        // 而那正是它需要的信号（"你给的 id 不属于我这份 vocab"）。
        if (t < 0 || (int32_t) t >= nVocab) {
            jlog("[detokenize] id[%d]=%d 越界（vocab=%d），整条拒绝", (int) i, (int) t, (int) nVocab);
            return env->NewStringUTF("");
        }
        out += token_to_piece(t, /*renderSpecial=*/true);
        // 防御性上界：一条 input 不可能有意义的超长文本（与 MAX_INPUT_CHARS 同量级
        // 的上游判据在 Kotlin 侧）。这里只挡"病态 id 数组把内存吃光"。
        if (out.size() > 1u << 20) {
            jlog("[detokenize] id 数组还原文本超过 1MiB，整条拒绝");
            return env->NewStringUTF("");
        }
    }
    return new_string_utf8_safe(env, out.c_str());
}

// ── 前向：一条文本 -> 一个已归一化的向量 ────────────────────────────────────
//
// 返回 jfloatArray（长度 = n_embd），失败返回 null（**不抛** —— 异常穿过 JNI 帧是 UB）。
// 调用方按 null 处理为"这一条没有可用向量"，而不是崩。
//
// 为什么手工填 batch 而不是用 `llama_batch_get_one`：后者把 `logits` 全置 true 且
// 不区分序列，而我们只需要**最后一个 token** 的输出（last-token pooling），
// 且 `llama_batch` 本身是导出结构体（token/embd/pos/n_seq_id/seq_id/logits 全可见），
// 手工填是合法且唯一精确的做法。
JNIEXPORT jfloatArray JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbed(
        JNIEnv * env, jclass, jstring jtext) {
    JNI_SPAN("embed");
    embed_failure_reset();
    if (!E.ctx || !E.model || !E.vocab) {
        jlog("[embed] 无嵌入模型，返回 null");
        embed_failure_set(embedtext::EmbedFailure::BAD_INPUT, 0, 0, 0, -1, 0.0);
        return nullptr;
    }
    if (!jtext) return nullptr;
    const char * t = env->GetStringUTFChars(jtext, nullptr);
    if (!t) return nullptr;
    std::string text(t);
    env->ReleaseStringUTFChars(jtext, t);
    if (text.empty()) {
        jlog("[embed] 空文本，返回 null（不编造向量）");
        embed_failure_set(embedtext::EmbedFailure::BAD_INPUT, 0, 0, 0, -1, 0.0);
        return nullptr;
    }

    // ── 维度这一对照必须在**读向量之前**成立（方案 B 的核心）────────────────
    // `llama_get_embeddings_ith` 按 **n_embd** 步长走（头文件 `output_ids[i]*n_embd`），
    // 而库按 **n_embd_out** 分配 output buffer，两者在部分模型上不相等。
    // 不等时"读一行"的行为是**未定义**的：n_embd_out 更小就跨进了别的 buffer
    //（含未初始化的 NaN），表现为"某些条归一化失败"且**时好时坏** ——
    // 因为失败取决于那块内存之前被谁写过。用户报的"改改词序某次成功之后
    // 就再也不失败"正是这个机理：成功的那次把 buffer 写干净了。
    // 所以这里**拒绝继续**，并把两个实数一起报出来（只报"失败"时，
    // `n_embd != n_embd_out` 与"文本有问题"长得一模一样）。
    const int nEmbd = (int) llama_model_n_embd(E.model);
    const int nEmbdOut = (int) llama_model_n_embd_out(E.model);
    const int poolType = E.pool_type;
    if (!embedtext::output_dim_ok(nEmbd, nEmbdOut)) {
        jlog("[embed] 输出维度不匹配：n_embd=%d, n_embd_out=%d（库按 n_embd_out 分配 output buffer，"
             "按 n_embd 读会跨到别的 buffer）。这一条拒绝编码。", nEmbd, nEmbdOut);
        embed_failure_set(embedtext::EmbedFailure::DIM_MISMATCH, 0, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }

    // tokenize（带 BOS）。need<0 = 所需长度；0 = 空串（上面已挡）。
    int need = llama_tokenize(E.vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (need == 0) {
        embed_failure_set(embedtext::EmbedFailure::BAD_INPUT, 0, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }
    if (need < 0) need = -need;
    std::vector<llama_token> toks((size_t) need);
    if (llama_tokenize(E.vocab, text.c_str(), (int32_t) text.size(),
                       toks.data(), (int32_t) toks.size(), true, true) < 0) {
        jlog("[embed] tokenize 第二趟失败");
        embed_failure_set(embedtext::EmbedFailure::BAD_INPUT, 0, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }
    // 超长：**截断到 ctx-1**，而不是失败。嵌入的输入是"一条要检索的短文本"，
    // 超过 ctx 说明分块没做好 —— 但让整次检索失败比"用前 N 个 token 的语义"更糟
    //（前者是功能不可用，后者只是这一条略差）。两条路都有日志可辨。
    // ⚠ 截断点是**每条的预算**（`E.n_ctx_seq`），不是 ctx 总量 ——
    // 合批引入 `n_seq_max>1` 之后这两个数差 `kEmbedBatchRows` 倍，
    // 按总量截会让文本带着越界的 `pos` 上库（见 Session::n_ctx_seq 的说明）。
    if ((int) toks.size() >= E.n_ctx_seq) {
        jlog("[embed] 文本 %d tok 超过每条预算 %d，截断到 %d（分块策略应更小）",
             (int) toks.size(), E.n_ctx_seq, E.n_ctx_seq - 1);
        toks.resize((size_t) (E.n_ctx_seq - 1));
    }
    if (toks.empty()) {
        embed_failure_set(embedtext::EmbedFailure::TRUNCATED_EMPTY, 0, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }

    // 每轮前清 KV：嵌入是**无状态**的逐条前向，条目之间不能互相看见
    //（复用上一条的 KV 会让这一条的向量混进上一条的语义 —— 检索全错，无声无息）。
    llama_memory_seq_rm(llama_get_memory(E.ctx), 0, -1, -1);

    // ── 池化要取哪个 token：由**模型自己声明的 pooling_type** 决定 ────────────
    // 这一段是方案 A 的接口位。`pool_row_index` 是纯函数（宿主侧有单测钉住四种
    // 类型的下标），native 只负责把"模型怎么说的"喂进去 —— 判据不在这一层，
    // 免得真机上没法验的语义散在 JNI 里。
    //
    // ⚠ 下标语义与下面 `logits[]` 的置位方式**必须同时变**：只改一处就是
    // "检索全空 / 接口 200"（见 embedtext::output_slot_index 的注释）。
    const int nTok = (int) toks.size();
    const embedtext::PoolPlan plan = embedtext::plan_pooling(poolType, nTok);
    if (!plan.valid) {
        // 模型声明的池化类型我们还不支持（RANK / 未知）。**不猜** ——
        // 猜错的表现是"向量算出来了、语义全错"，比直接拒绝难查得多。
        jlog("[embed] 池化类型 %d 不受支持（n_tokens=%d），这一条拒绝编码", poolType, nTok);
        embed_failure_set(embedtext::EmbedFailure::BAD_INPUT, nTok, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }
    // ── 计划自洽自检（在填 batch **之前**）────────────────────────────────
    // 三个字段（`row` / `output_slot` / `want_logits`）必须互相支持。
    // 判据是纯函数 `plan_is_coherent`（宿主侧有单测）：`row` 说"读最后一个
    // token"，`output_slot` 就必须**真的**指到那一行。只改一处时的报应不是
    // "这一条失败"，而是静默的"读的不是那一行" —— 真机上表现为
    // BGE 零范数 / 生成式模型读到 BOS（`范数≈1e-30`），而接口照常 200。
    if (!embedtext::plan_is_coherent(plan, nTok)) {
        // ⚠ 这一行**不打印** `plan.batch_last_slot`：它是"读取点用了它"的判据位，
        // 出现在一条日志里会让那条判据**恒真**（把读取点改回硬编码下标时，
        // 日志那一行还在，守卫照样绿 —— 本仓库反复记录的那类自欺）。
        jlog("[embed] 池化计划不自洽：pooling=%d row=%d output_slot=%d "
             "（读的位置与语义不是同一行 —— 这是填充逻辑的 bug，不是模型问题）",
             poolType, (int) plan.row, plan.output_slot);
        embed_failure_set(embedtext::EmbedFailure::POOLING_CONTRACT, nTok, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }

    llama_batch b = llama_batch_init(nTok, 0, 1);
    if (!b.token) {
        jlog("[embed] batch_init 失败");
        embed_failure_set(embedtext::EmbedFailure::BAD_INPUT, nTok, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }
    for (int i = 0; i < nTok; i++) {
        b.token[i]     = toks[(size_t) i];
        b.pos[i]       = (llama_pos) i;
        b.n_seq_id[i]  = 1;
        b.seq_id[i][0] = 0;
        // 只要**参与池化的那些 token**的输出（见 plan_pooling）：
        //   · NONE / LAST  -> 只要最后一个（output 数组恰好 1 个元素）；
        //   · CLS / MEAN   -> **整段都要**（不是"只用第 0 行"）。
        // ⚠ CLS 那一支是本轮才修对的：库对 CLS 要求"整段都在 output 里"
        //（vendor 判据原文 `pooled embedding requires that all tokens are output`），
        // 而早先的实现只给第 0 个 token 置 1 —— 于是 `llama_decode` 直接返回非 0，
        // BGE 系**条条失败**，且失败被记成 `decode_failed`（读起来像内存问题）。
        // 读取侧仍然只读下标 0（CLS 的语义），"多要几行"只多占常驻 output buffer。
        b.logits[i]    = plan.want_logits[i] ? 1 : 0;
    }
    b.n_tokens = (int32_t) nTok;

    // ── 提交给库**之前**先自检：我们填的 logits[] 满足库对该池化方式的前置要求吗 ──
    // 为什么必须在 decode **之前**：违反这条约束时库的报应不是"这一条失败"，
    // 而是 ①CLS/MEAN 的 decode 恒失败（`pooled embedding requires that all
    // tokens are output`）或 ②NONE 的 `n_outputs >= 1` 断言失败（**进程崩溃**）。
    // 两者在真机上都被读成"模型/内存有问题"，而真实的毛病只是"我们没把该要的行要上"。
    // 判据是纯函数 `embedtext::logits_ok_for_library`（宿主侧有单测），
    // 这里只负责把它变成一条**能归因**的失败记录。
    if (!embedtext::logits_ok_for_library(poolType, plan.want_logits, nTok)) {
        jlog("[embed] 池化契约不满足：pooling=%d 要求 %s 都在 output 里，实际只要了 %d/%d 个 token"
             "（这是填充逻辑的 bug，不是模型问题）",
             poolType, embedtext::library_computes_pooling(poolType) ? "整段" : "至少一个",
             (int) std::count(plan.want_logits.begin(), plan.want_logits.end(), (char) 1), nTok);
        llama_batch_free(b);
        embed_failure_set(embedtext::EmbedFailure::POOLING_CONTRACT, nTok, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }

    if (llama_decode(E.ctx, b) != 0) {
        jlog("[embed] decode 失败（n_tokens=%d）", (int) b.n_tokens);
        llama_batch_free(b);
        // decode 失败后 KV 里是半截内容 —— 立刻清掉，否则下一条会读到它。
        llama_memory_seq_rm(llama_get_memory(E.ctx), 0, -1, -1);
        embed_failure_set(embedtext::EmbedFailure::DECODE_FAILED, nTok, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }
    llama_batch_free(b);

    // ── 取向量：按池化计划的**输出槽下标**读，再按池化方式合成 ───────────────
    // `llama_get_embeddings_ith(ctx, i)` 的 `i` 是 **output 数组下标**，不是 batch 下标 ——
    // 头文件原文：`llama_get_embeddings(ctx) + ctx->output_ids[i]*n_embd`，
    // 而 `output_ids` 只收**那些 `llama_batch.logits[j] != 0` 的 token**，
    // 按它们在 batch 里出现的顺序**连续排列**。
    // 所以"第几个 output"由 plan_pooling 给出（NONE/LAST 恒为 0；CLS 为 0；
    // MEAN 需要全部），写成 `n_tokens-1` 时只要文本超过 1 个 token 就取到无效下标，
    // 表现为 `llama_get_embeddings_ith` 返回 null → 检索全空，**而接口 200**。
    std::vector<float> vec((size_t) nEmbd, 0.0f);
    // 读哪一路由 `read_from_seq` 判：**库把池化结果写进 `embd_seq` 的那三档
    // （MEAN / CLS / LAST）读 seq**，只有 `NONE`（逐 token 写 `embd.data`）读 ith。
    //
    // ⚠ CLS 走 seq 是 `554fa67` 才对的，LAST 走 seq 是 `0.9.163` 才补的第三档。
    // 早先只有 MEAN 走 seq，CLS / LAST 都走了 ith —— 而
    // `ith` 的前提是 `get_embeddings(ctx)` 有效，那一支**只在 pooling==NONE 时成立**，
    // CLS 下读的是逐 token 的输出区（池化结果在 `result_embd_pooled` 那个独立张量里），
    // 拿到的是**全零**而不是 null，于是绕过 no_output 判据、直落 `范数=0`。
    // 详见 `embedtext::read_from_seq` 上方的推导与用户日志的那一行对照。
    //
    // ⚠ 与 `plan.row` 的关系：`plan.row` 与 `read_from_seq` 读的是**同一条**
    // 库前提（`library_gives_seq`），只是名字不同 —— 前者说"库给了 pooled buffer，
    // 这一档该走 seq"，后者是那条路在读取侧的**调用入口**。两处都由它分岔。
    if (embedtext::read_from_seq(poolType)) {
        // **MEAN(1) / CLS(2) / LAST(3)：读库池化好的那一个**（`llama_get_embeddings_seq`）。
        //
        // ⚠ LAST 是 `0.9.163` 才补进来的第三档。前两档修完后 `pooling=3`
        //（Qwen3-Embedding）**照样**条条失败，因为：
        //   · MEAN/CLS/LAST 三档在库那一侧都把池化结果写进 `embd_seq`，
        //     **`embd.data` 一个字节都不写**（只有 NONE 会写它）；
        //   · 而早先 `read_from_seq` 只认 {1,2}（两档），`pooling=3` 落到 `ith` 支 ——
        //     读一个"分配了但从未写入"的 buffer，拿到**全零**（不是 null，
        //     所以绕过 no_output 判据），于是直落 `范数=0`。
        // 症状之所以看着像"模型不行"：`pooling=2` 的 BGE 走 seq 照常能用，
        // 只有生成式 decoder 嵌入模型（写 LAST）全败 —— 换模型恰好掩盖了它。
        //
        // 为什么 MEAN 不手工累加：库对 MEAN 的池化是它与图一起做的（含 padding
        // 处理），我们按 output 行做算术平均只是**近似**它 ——
        // 而"看起来正常的近似向量"正是本仓库最不想要的那类东西（分数像真的、
        // 语义错、不报错）。我们不再有第二套平均实现，就不存在与库漂移的可能。
        // 为什么 CLS / LAST 也走这里：池化结果在库的 `embd_seq` 里，
        // `ith` 那条路（`embd.data`）对这两档根本没有数据。
        float * pooled = llama_get_embeddings_seq(E.ctx, 0);
        if (!pooled) {
            jlog("[embed] get_embeddings_seq 返回 null（pooling=%d 应给出池化向量，n_tokens=%d, n_embd=%d）",
                 poolType, nTok, nEmbd);
            embed_failure_set(embedtext::EmbedFailure::NO_OUTPUT, nTok, nEmbd, nEmbdOut, poolType, 0.0);
            return nullptr;
        }
        std::memcpy(vec.data(), pooled, sizeof(float) * (size_t) nEmbd);
    } else {
        // ── 从 output 数组里取目标那一项 ─────────────────────────────────
        // 走到这里的**只有最后一档**：`plan.row == LAST_TOKEN`（= `pooling` 为
        // `NONE(0)` / `UNSPECIFIED(-1)`，库把逐 token 输出写进 `embd.data`）。
        // MEAN / CLS / LAST(3) 都被上面的 `read_from_seq` 带走了。
        //
        // `slot` **不是** `n_tokens-1`，而是"目标行在 output 数组里的**排位**"
        // （`output_ids` 只收 `logits[j] != 0` 的 token，按出现顺序连续排列 ——
        // 头文件原文）。现在只置了最后一个位置 → 它在 output 里排 0。
        // 写成 `0` / `n_tokens-1` 都只在"恰好只置了一项"时能对上，
        // 一旦库给的 output 更多就**静默读到别的行**（BOS → 范数 1e-30）。
        const int slot = plan.row == embedtext::SeqRow::LAST_TOKEN
                         ? plan.batch_last_slot : plan.output_slot;
        float * raw = llama_get_embeddings_ith(E.ctx, slot);
        if (!raw) {
            // 取不到 = 库没把 output 留下来（embeddings 没设上 / 库版本不同）。
            // 这是**静默返回零向量**的那条路，必须留日志，否则表现为"检索全空"。
            jlog("[embed] get_embeddings_ith 返回 null（output_idx=%d, row=last_token=%d, n_tokens=%d, n_embd=%d）",
                 slot, plan.row == embedtext::SeqRow::LAST_TOKEN ? 1 : 0, nTok, nEmbd);
            embed_failure_set(embedtext::EmbedFailure::NO_OUTPUT, nTok, nEmbd, nEmbdOut, poolType, 0.0);
            return nullptr;
        }
        std::memcpy(vec.data(), raw, sizeof(float) * (size_t) nEmbd);
    }

    // ── 归一化：**每一档都由我们做 L2**（库不做） ────────────────────────
    // 判据是 `normalize_pooling(poolType)`（纯函数，宿主侧有单测）。
    //
    // ⚠ 0.9.160 曾让"库给 pooled buffer 的档"跳过归一化，理由是"库自己归过了"。
    // 那是**错的**：`build_pooling`（vendor 的上游源码，与本仓库 vendor `.so` 里
    // `result_embd_pooled` 张量名对得上）对 MEAN/CLS/LAST **不做 L2**；
    // 做归一化的是 `cui-llama.rn` 的 RN 包装层（`common_embd_normalize`），
    // 而本 App 直接调 C API `llama_get_embeddings_seq`，**不经过那一层**。
    // 所以拿到的永远是原始池化向量，必须自己归一化（检索侧的 `cosine` 是纯点积，
    // 前提就是两边都归一化过）。
    //
    // 归一化失败（零向量/NaN）时**丢弃**这条：塞进索引会让它与任何查询的余弦
    // 都是 0/0 = NaN，排序未定义（见 embed_text.h 的 l2_normalize）。
    // ⚠ 报出**实际范数**（而不是只说"失败"）：`norm=0`（真零向量）与
    // `norm=1e-38`（下溢）指向完全不同的毛病，而只报"失败"时两者长得一样。
    const double norm = embedtext::norm_of(vec.data(), vec.size());
    const bool we_normalize = embedtext::normalize_pooling(poolType);
    if (we_normalize && !embedtext::l2_normalize(vec.data(), vec.size())) {
        // `取自=` 把"这一档的向量从哪个位置来"写进日志 —— 它既是给人看的，
        // 也是**版本判据**：`0.9.162` 起 `pooling=3` 会走到 LIB_POOLED，
        // 日志里再出现 `pooling=3 … 取自=最后一个 token 的行` 就说明包没换对。
        jlog("[embed] 归一化失败（范数=%.3g，n_tokens=%d，pooling=%d，取自=%s，n_embd=%d，n_embd_out=%d），丢弃这一条",
             norm, nTok, poolType,
             plan.row == embedtext::SeqRow::LIB_POOLED ? "库池化的那一份（get_embeddings_seq）"
                : plan.row == embedtext::SeqRow::LAST_TOKEN ? "最后一个 token 的行" : "output 数组首项",
             nEmbd, nEmbdOut);
        embed_failure_set(embedtext::EmbedFailure::ZERO_NORM, nTok, nEmbd, nEmbdOut, poolType, norm);
        return nullptr;
    }
    // 万一将来某一档真的改由库归一化（`normalize_pooling` 归 false），
    // 那一份也要有一次"有限性"检查 —— 不判失败标准（我们不是它的作者），
    // 但 NaN/Inf 一旦进索引，余弦排序就未定义。丢弃比污染整库便宜。
    if (!we_normalize && !std::isfinite(norm)) {
        jlog("[embed] 库给的池化向量不是有限数（范数=%.3g，n_tokens=%d，pooling=%d），丢弃这一条",
             norm, nTok, poolType);
        embed_failure_set(embedtext::EmbedFailure::ZERO_NORM, nTok, nEmbd, nEmbdOut, poolType, norm);
        return nullptr;
    }
    // ⚠ 分配长度与填充长度**必须是同一个数**（`nEmbd`）。
    // 这里曾写成 `NewFloatArray(nEmbdOut)` + `SetFloatArrayRegion(out, 0, nEmbd, ...)`：
    // 两个数不相等时，返回的数组比填进去的多出一截**静默的零尾**
    //（`jfloatArray` 创建即全零），而调用方拿到的长度是 `nEmbdOut` ——
    // `buildIndex` 用 `v.size != dim` 判、`EmbeddingsApi` 只用 `vec.isEmpty()` 判，
    // 于是那截零尾要么让这一条被判失败（看起来像模型抽风），要么**直接进索引**
    //（归一化后方向偏离，命中率凭空下降，接口 200）。
    //
    // 当前上游 `output_dim_ok` 要求 `nEmbd == nEmbdOut` 严格相等，所以两种长度
    // 恰好相同、这处不爆 —— 但那是**另一道闸**在挡，它挡的是"值相不相等"，
    // 从来没有保证"分配数 == 填充数"。闸一旦放宽（`embed_text.h` 里那段注释
    // 正在讨论"输出维度更长时语义被截掉一截"），这里就会静默错。
    // 所以两个数**只写一个**：分配也用 `nEmbd`。
    // 判据落在 `tools/embed_robust/check_return_contract.py`（锚的是"同一个标识符"）。
    jfloatArray out = env->NewFloatArray(nEmbd);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, nEmbd, vec.data());
    return out;
}


// ── 合批前向：n 条文本 -> n 个已归一化向量（**一次** `llama_decode`）──────────
//
// ISSUE #269 的另一半：PR #273 把"线程数按本机核数"拿回来了（多核这一份），
// 但流水线仍然是**一条一次前向**。每条一次 decode 的固定开销（建图 / 分配 /
// 调度 / 同步）与 token 数无关，在 400 字这种小条目上占了大头。这里按
// `embed_text.h` 的 `plan_batch` 把 n 条塞进同一个 `llama_batch`，
// **一次** decode 算完。
//
// ═══════════════════════════════════════════════════════════════════════════
// ⚠⚠ 隔离靠 `seq_id`，**不是**靠"反正以前也是清 KV"
// ═══════════════════════════════════════════════════════════════════════════
// 单条路径每轮开头 `llama_memory_seq_rm(seq 0, -1, -1)` —— 那是隔离的**做法**。
// 合批时显然不能"每条之间清 KV"（那就退化成逐条了），于是隔离只能靠把第 j 条
// 的 token 标 `seq_id = j`：库建注意力掩码时按 seq 分块，不同 seq 互不可见
// （vendor `.so` 里 `llm_graph_context::build_inp_attn_kv` 那一支）。
//
// 也就是说：合批的正确性前提是"库真的按 seq_id 隔离"，而不是"我们没清 KV"。
// 这两件事必须分开记（隔离本身不由本文件证明，本文件只保证"我们填对了"）：
//   · 我们填对的判据 = `embedtext::batch_is_valid`（纯函数，宿主侧有单测）；
//   · 隔离由库保证 —— `plan_batch` 的注释里写明了它的依据。
//
// 每一轮开头仍然清**所有** seq（`seq_rm(0..n_seq_max-1, -1, -1)`）：上一批的
// KV 不该被这一批看见。**不许**只清 seq 0 —— 那样第二排之后的条目会复用到
// 上一批同号 seq 的 KV，向量里混进上一批的语义，而接口 200。
//
// ═══════════════════════════════════════════════════════════════════════════
// 返回契约：`null` = **整批不可用**（调用方回落逐条），`FloatArray(n*dim)` = 逐条向量
// ═══════════════════════════════════════════════════════════════════════════
// 与单条路径"一条失败返回 null"不同，合批里失败可能只发生在**某几条**上
// （某条太长触发某个退化）。三种处置各自对应一类错：
//   · 整批 decode 失败 -> 返回 null，调用方**回落逐条**（单条路径能独立归因，
//     而合批这一层的失败信息量少得多）；
//   · 某条归一化失败 -> 该条在返回数组里留**全零**，并**把它的下标带出去**
//     （`nativeEmbedBatchFailed()`）。全零向量不会进索引 —— Kotlin 侧按
//     `norm == 0` 判失败（与单条路径同一条判据），所以"哪条失败"这件事
//     在两侧是同一个答案，不需要第二套协议；
//   · 某条 tokenize 失败 / 空 -> 同上（全零 + 下标）。
// 为什么不用"整批 null"兜住所有失败：那会让批里**一条**坏文本把整批 8 条
// 打回逐条重跑，用户看到的进度会莫名倒退；而且坏的那条在逐条路径上会被
// 正常归因（单条路径本来就有失败明细）。
//
// ⚠ 与单条路径的**池化口径必须逐位一致**（见 `plan_batch` 那段）：每条的
// `pos` 从 0 起、每条的池化计划按**它自己的** token 数算。任何一处漂移都会
// 让"合批的向量"与"逐条的向量"不是同一个 —— 用户改了批大小，检索结果就变，
// 而没有任何日志指向这里。
//
// ═══════════════════════════════════════════════════════════════════════════
// 0.9.208：MEAN / CLS / LAST(3) 也走这里了（以前它们被挡在合批外）
// ═══════════════════════════════════════════════════════════════════════════
// 这一版撤掉了"库自己池化的档不合批"那道闸（理由见 `embed_text.h::batchable_pooling`）：
// 那三档的向量在库的 `embd_seq` 里，**每个 seq 一行**，第 j 条读
// `llama_get_embeddings_seq(ctx, j)` —— 与单条路径（实参写死 0）是同一个 API。
// 于是取向量这一支**按 `read_from_seq` 分岔**（与单条路径同一个判据），
// 而 `batch_output_layout_ok`（"每条恰好一个 output"）只对读 `ith` 的那一档成立 ——
// CLS/MEAN 是**整段**置 1（库的前置门禁），拿它去卡会恒假并把这些档再次打回逐条。
JNIEXPORT jfloatArray JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch(
        JNIEnv * env, jclass, jobjectArray jtexts) {
    JNI_SPAN("embedBatch");
    embed_failure_reset();
    if (!jtexts) return nullptr;
    if (!E.ctx || !E.model || !E.vocab) {
        jlog("[embedBatch] 无嵌入模型，返回 null（调用方回落逐条）");
        return nullptr;
    }
    const jsize nRows = env->GetArrayLength(jtexts);
    if (nRows <= 0) return nullptr;

    const int nEmbd = (int) llama_model_n_embd(E.model);
    const int nEmbdOut = (int) llama_model_n_embd_out(E.model);
    const int poolType = E.pool_type;
    // 维度这一对照与单条路径**同一判据**（见 nativeEmbed 里那段推导）：
    // 不等时读一行是未定义行为，绝不能拿它去填一批。
    if (!embedtext::output_dim_ok(nEmbd, nEmbdOut)) {
        jlog("[embedBatch] 输出维度不匹配：n_embd=%d, n_embd_out=%d，整批拒绝（回落逐条）", nEmbd, nEmbdOut);
        embed_failure_set(embedtext::EmbedFailure::DIM_MISMATCH, 0, nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }
    // 这一档能不能合批。**0.9.208 起这条判据不再排除任何一档**（见
    // `embed_text.h::batchable_pooling` 上方那段：`embd_seq` 在多 seq 下
    // 就是"每个 seq 一行"，第 j 条读 `get_embeddings_seq(ctx, j)`）——
    // 所以它现在只在 `plan_pooling` 本来就不认的档（RANK / 未知）上为假。
    //
    // ⚠ 保留这个调用点（而不是删掉）：它是"哪些档能合批"这**一条判据的
    // 唯一落点**，删了就散进 `plan_batch` 与下面的 if 里 —— 而那正是本仓库
    // 反复记录的"同一份清单抄成两份"。守卫正面钉住这里仍然问它。
    // **拒绝时返回 null 让调用方回落逐条** —— 不是静默降级成"合批但用错口径"。
    if (!embedtext::batchable_pooling(poolType)) {
        jlog("[embedBatch] pooling=%d 不在可合批的档里（RANK/未知），返回 null 回落逐条", poolType);
        return nullptr;
    }
    // 条数上限：**超过就返回 null 回落逐条**，而不是截断（截断会让调用方按
    // 数组长度对齐时错位 —— 与 EmbeddingsApi 拒绝 MAX_INPUTS 同一条理由）。
    if ((int) nRows > E.seq_max) {
        jlog("[embedBatch] 批过大（%d > seq_max=%d），返回 null 回落逐条", (int) nRows, E.seq_max);
        return nullptr;
    }

    // ── 逐条 tokenize（在填 batch **之前**，因为整批长度要靠它算出来）──────
    std::vector<std::vector<llama_token>> toks((size_t) nRows);
    std::vector<int> counts((size_t) nRows, 0);
    std::vector<char> bad((size_t) nRows, 0);   // 这一条 tokenize 不出来 / 空
    for (jsize j = 0; j < nRows; j++) {
        jstring js = (jstring) env->GetObjectArrayElement(jtexts, j);
        if (js) {
            const char * t = env->GetStringUTFChars(js, nullptr);
            if (t) {
                std::string text(t);
                env->ReleaseStringUTFChars(js, t);
                if (!text.empty()) {
                    int need = llama_tokenize(E.vocab, text.c_str(), (int32_t) text.size(),
                                              nullptr, 0, true, true);
                    if (need < 0) need = -need;
                    if (need > 0) {
                        toks[(size_t) j].resize((size_t) need);
                        if (llama_tokenize(E.vocab, text.c_str(), (int32_t) text.size(),
                                           toks[(size_t) j].data(), (int32_t) toks[(size_t) j].size(),
                                           true, true) >= 0) {
                            // 超长：与单条路径**同一口径**截断到 ctx-1（见 nativeEmbed
                            // 里那段）。这里**不**改口径：合批与单条的截断点不同的话，
                            // 同一条文本在两条路径上编码的不是同一段 —— 向量不同、
                            // 长度相同、不报错。
                            if ((int) toks[(size_t) j].size() >= E.n_ctx_seq)
                                toks[(size_t) j].resize((size_t) (E.n_ctx_seq - 1));
                            counts[(size_t) j] = (int) toks[(size_t) j].size();
                        }
                    }
                }
            }
            env->DeleteLocalRef(js);
        }
        if (counts[(size_t) j] <= 0) bad[(size_t) j] = 1;
    }

    // ── 合批计划（纯函数）：**失败的条目按 0 token 处理**，不参与本批 ────────
    // 为什么把坏条目剔出去而不是整批拒绝：批里一条坏文本不该让其余 7 条多跑
    // 一次（用户看到的进度会倒退）。坏条目的向量位置留全零，由 Kotlin 侧按
    // 与单条路径**同一条判据**（norm==0）判失败并报出下标 —— 见 nativeEmbedBatchFailed。
    std::vector<int> live;          // 参与本批的行下标（按原顺序）
    std::vector<int> liveCounts;
    for (jsize j = 0; j < nRows; j++) {
        if (!bad[(size_t) j]) { live.push_back((int) j); liveCounts.push_back(counts[(size_t) j]); }
    }
    if (live.empty()) {
        jlog("[embedBatch] 全批 %d 条都 tokenize 不出（空文本/失败），返回 null 回落逐条", (int) nRows);
        return nullptr;
    }
    const embedtext::BatchPlan plan = embedtext::plan_batch(poolType, liveCounts, E.seq_max);
    if (!plan.valid) {
        jlog("[embedBatch] 合批计划不可用（pooling=%d, 条数=%d），返回 null 回落逐条",
             poolType, (int) live.size());
        return nullptr;
    }
    // 自洽自检（纯函数，宿主侧有单测）——与单条路径的 `plan_is_coherent` 同一
    // 位置的同一类判据：填 batch 之前先把"我们填的东西对不对"问一遍。
    if (!embedtext::batch_is_valid(plan, poolType)) {
        jlog("[embedBatch] 合批计划不自洽（n_tokens=%d, n_rows=%d）——这是填充逻辑的 bug，"
             "返回 null 回落逐条", plan.n_tokens, plan.n_rows);
        embed_failure_set(embedtext::EmbedFailure::POOLING_CONTRACT, plan.n_tokens,
                          nEmbd, nEmbdOut, poolType, 0.0);
        return nullptr;
    }
    // output 排位判据：**只对"每条恰好一个 output"那一类**成立（NONE / UNSPECIFIED，
    // 读 `ith`）。`batch_output_layout_ok` 的判据是"整批恰有 n_rows 个 1"，
    // 而 CLS/MEAN 那条路是**整段都置 1**（库的前置门禁要求，见 `plan_pooling`）
    // —— 拿它去卡会恒假，于是这两档在合批里**又一次**被打回逐条
    //（上一版是 `batchable_pooling` 挡的，这一版闸撤了：若这里不改，
    //  症状是"判据说能合批、循环里全在回落"，而日志里两个方向的话都说得通）。
    //
    // 所以这个判据**按档分岔**：只有"读 ith"的那一档才要求每条一个 1。
    // 它在那一条路是 `ith(j)` 取到"第 j 条最后一行"的**全部依据**；
    // 读 `embd_seq` 的那一档根本不经过 output 数组，拿它去卡是问错了问题。
    if (!embedtext::read_from_seq(poolType) && !embedtext::batch_output_layout_ok(plan)) {
        jlog("[embedBatch] output 布局不是『每条一个』（n_rows=%d），返回 null 回落逐条", plan.n_rows);
        return nullptr;
    }

    // ── 清**所有** seq 的 KV（见上面那段：只清 seq 0 会让后面几条复用上一批的 KV）──
    for (int j = 0; j < E.seq_max; j++)
        llama_memory_seq_rm(llama_get_memory(E.ctx), (llama_seq_id) j, -1, -1);

    // ═══════════════════════════════════════════════════════════════════════
    //  ⚠⚠ `n_tokens <= n_batch` —— 库在 `decode` 的**正常路径**上断言它
    // ═══════════════════════════════════════════════════════════════════════
    // 这一处是 0.9.209 修的**真机闪退**。用户在 104 条上复现，栈是：
    //
    //   LM_GGML_ASSERT(n_tokens_all <= cparams.n_batch) failed
    //   /Users/vali/Desktop/dev/react_native/cui-llama.rn/cpp/llama-context.cpp:1711
    //   ... llama_decode ... Java_..._nativeEmbedBatch
    //
    // release（NDK 默认 `-DNDEBUG`）里断言失败 = `abort()`：不是"这一批失败"、
    // 不是"回落逐条"，而是**整个进程当场消失**（用户看到的"建索引闪退"）。
    //
    // 为什么合批**必然**撞上它：单条路径一条一次 decode，每次最多
    // `n_ctx_seq - 1` 个 token，与 `n_batch`（默认 2048）无关 —— 所以合批
    // 之前从来不崩。合批把 8 条塞进**同一个** batch：8 × 511 = 4088 > 2048。
    // 于是"这一批里长条目凑够几条"就崩，看起来像随机闪退（与文本内容无关）。
    //
    // ⚠ 库自己会按 `n_ubatch` 再切（`ubatch.n_tokens == n_seq_tokens * n_seqs`），
    // 但那条断言在**切分之前** —— "库会替我切"救不了这一批，进程在那之前就没了。
    // 所以只能我们自己**在提交之前**按整批容量切成几段，一段一次 `llama_decode`。
    //
    // ⚠ 切分只按**整条**切：把一条文本劈成两半提交，它的池化结果就不是这一条的
    // 语义了（而长度、维度、归一化全对）。某一条单独就超 `n_batch` 时，
    // 整批只能返回 null 让调用方**逐条**跑（单条路径不受 `n_batch` 约束）。
    const int nBatch = (int) llama_n_batch(E.ctx);
    if (nBatch <= 0) {
        // 取不到 `n_batch`（库版本/符号问题）。判据此时**放行**（见
        // `batch_fits_n_batch` 那段：宁可让库报它自己的错，也不让一个取不到的
        // 数把整条合批路径永久关掉）—— 但必须留一行，否则"这一批为什么没拦"
        // 在事后完全查不出来。
        jlog("[embedBatch] 取不到 n_batch（llama_n_batch 返回 %d），容量判据放行 ——"
             "若随后 abort 在 llama_decode，先看这一行", nBatch);
    }
    // 这一段里**只有一处**回答"能一次提交几条"（`batch_chunk_rows`）。
    // `batch_fits_n_batch` 不再单独调一次来与它对口径 —— 那会变成同一件事
    // 由两个函数各答一遍，而两个答案分叉时的报应落在**进程**上（abort）。
    // 它的职责因此回到"问『整批装不装得下』"这个**单一**的问题上：下面每个
    // 分段在提交前都拿它过一遍（判据仍排在 `llama_decode` 之前，且是提交的**门禁**，
    // 而不是装饰），`nBatch <= 0` 时**放行**的语义也仍然只有它一处。
    // 两份口径的分家由构造保证：分段长度取 `batch_chunk_rows(本段的 counts)`，
    // 于是每段自己的总和按构造 `<= nBatch`（在能装下的前提下）。
    int chunkRows = embedtext::batch_chunk_rows(liveCounts, nBatch);
    if (chunkRows <= 0) {
        // 0 = 某一条自己就装不进整批。**不是**"这一批不能用合批"，
        // 而是"合批这条路对这条文本无解"—— 单条路径一条一次 decode，照常能跑。
        jlog("[embedBatch] 有单条超过 n_batch=%d（本批 %d 条），返回 null 回落逐条"
             "（单条路径一条一次 decode，不受整批容量约束）", nBatch, plan.n_rows);
        return nullptr;
    }
    if (chunkRows < plan.n_rows) {
        // 装不下时**按条目边界切成几段、在同一次调用里接着编**（而不是整批回落逐条）。
        //
        // ⚠ 出口的选择（0.9.209 与 0.9.210 的分界）写下来免得下一个人改回去。
        // 三条路都"看起来对"，代价差一个数量级：
        //   · 整批返回 null，调用方回落逐条 —— 安全，但**整段批大小一起掉到逐条**
        //     （4~8 条全废，104 条 = 104 次 decode）。0.9.209 就是这么写的；
        //   · 只提交前几条、余下**记成失败** —— 那几条会进 `buildIndex` 的
        //     `failedIdx` → 走 `savePartial` → **整个库**被标成"索引待重建"，
        //     而用户看到的是"这几条坏了"。真相只是"这一批排不下"—— 那属于
        //     **合批这一层**的事，不该渗透成"这个库要重建"；
        //   · **接着编**（本版）—— 余下几条留在同一批的循环里继续提交，
        //     一行都不进 `failedIdx`，落盘行数也不变。不变量是"所有活条都提交过"，
        //     **不是**"一次提交完"。
        //
        // 逐条是这第三条路的**下界**（`chunkRows` 取到 1 时它退化成逐条），
        // 所以它不会比逐条更慢，也不会比逐条更不准 —— 每一段用的都是**同一个**
        // `plan` 里的 `BatchRow`（同一份 `pos` / `seq_id` / `logits` 计划）。
        jlog("[embedBatch] 整批 %d tok 超过 n_batch=%d（本批 %d 条），按容量切段提交"
             "（首段 %d 条；单条路径不受整批容量约束）",
             plan.n_tokens, nBatch, plan.n_rows, chunkRows);
    }

    // ── 逐段提交 ─────────────────────────────────────────────────────────
    // 返回值：长度 = nRows * nEmbd 的扁平数组（**包括**坏条目 —— 它的那一段是全零）。
    // 为什么长度按 nRows 而不是 live.size()：调用方按原下标对齐落盘
    //（`rows[i].vector`），数组长度一变就会整体错位 —— 而错位不报错。
    jfloatArray out = env->NewFloatArray(nRows * nEmbd);
    if (!out) return nullptr;
    std::vector<float> flat((size_t) nRows * (size_t) nEmbd, 0.0f);
    // 失败条目的下标（原下标），Kotlin 侧取走 —— 与 `lastEmbedFailure` 同一套
    // "失败详情在失败当场取走"的模式（见 g_embed_fail 的说明）。
    g_embed_batch_failed.clear();

    int submitted = 0;                       // 已经提交过的**条数**（分段推进）
    int decodeCount = 0;                     // 本批实际 decode 了几次（读数：切段可观测）
    while (submitted < plan.n_rows) {
        // 从 `submitted` 起**这一段**能一次提交几条。
        // ⚠ 重算而不是"切一整轮再写死"：`nBatch` 是库给的**绝对**上限，
        // 而"能装几条"取决于这一段自己的 token 数 —— 写死首段的条数会在
        // 后面几段上悄悄越界（而那正是 abort）。
        std::vector<int> rest(liveCounts.begin() + submitted, liveCounts.end());
        const int segRows = embedtext::batch_chunk_rows(rest, nBatch);
        if (segRows <= 0) {
            // 不可能到这里：`submitted` 之前的每一段都按"自己的总和 <= nBatch"切完，
            // 剩下的第一段若为 0 说明剩下的第一条**自己**就超容量 ——
            // 而它当初会被首段判据返回 0（同一条 `liveCounts`）。
            // 留着一行日志：真出现时它指向的是"判据被改坏了"，不是"这批太大"。
            jlog("[embedBatch] 切段自检失败：第 %d 条起 `batch_chunk_rows` 返回 0 ——"
                 "这是判据的 bug（返回 null 回落逐条）", submitted);
            embed_failure_set(embedtext::EmbedFailure::POOLING_CONTRACT, plan.n_tokens,
                              nEmbd, nEmbdOut, poolType, 0.0);
            return nullptr;
        }
        // ⚠ 这一段的 token 数是**段内总量**（`segRows` 条各自的 token 之和），
        // **不是** `lastRow.begin + lastRow.n` —— 后者的 `begin` 是**整批**下标的
        // 起点，第二段起就带上了前面几段的偏移（8 条满长时第二段会算出 4088），
        // 于是"这一段装得下"的判据恒假、整批回落逐条 —— 收益静默丢掉，不报错。
        // 段内总量按 `plan` 逐条加：它是这一段的**真实**提交数
        //（`plan.rows[*].n` 就是这一条 tokenize 出来的 token 数）。
        int segTokens = 0;
        for (int j = 0; j < segRows; j++)
            segTokens += plan.rows[(size_t) (submitted + j)].n;
        // 提交前的门禁（判据在 `llama_decode` **之前**，且是**这一段的**读数）：
        // `nBatch <= 0` 时 `batch_chunk_rows` 返回全部条数，这里同样放行 ——
        // 两份口径在"取不到"这一格上一致（不一致时下面这条会松掉，
        // 而松掉的代价是 abort，所以宁可在这里再问一次）。
        if (!embedtext::batch_fits_n_batch(segTokens, nBatch)) {
            jlog("[embedBatch] 容量判据互相矛盾（本段 %d tok, n_batch=%d, 段内 %d 条）——"
                 "这是判据的 bug，返回 null 回落逐条", segTokens, nBatch, segRows);
            return nullptr;
        }

        llama_batch b = llama_batch_init(segTokens, 0, 1);
        if (!b.token) {
            jlog("[embedBatch] batch_init 失败（n_tokens=%d，第 %d 段）", segTokens, decodeCount + 1);
            return nullptr;
        }
        // ⚠ 槽位是**段内**下标（`g - segBegin`）：这一段的第一个 token 必须落在
        // `b` 的第 0 格 —— 直接用**整批**下标 `g` 时，第二段会从 2044 那一格
        // 开始写而 `b` 只有 2044 格（越界写堆），且第 0..2043 格留空。
        // `pos` / `seq_id` / `logits` 仍全从计划取（用整批下标 `g`）——
        // 那是"每条的 pos 从 0 起"与"第 j 条用 seq j"的**唯一**真相来源。
        const int segBegin = plan.rows[(size_t) submitted].begin;
        for (int j = 0; j < segRows; j++) {
            const int gi = submitted + j;                                  // 计划里的行号
            const embedtext::BatchRow & r = plan.rows[(size_t) gi];
            const std::vector<llama_token> & tk = toks[(size_t) live[(size_t) gi]];
            for (int k = 0; k < r.n; k++) {
                const int g = r.begin + k;
                const int slot = g - segBegin;                             // 段内槽位
                b.token[slot]     = tk[(size_t) k];
                b.pos[slot]       = (llama_pos) plan.pos_of[(size_t) g];
                b.n_seq_id[slot]  = 1;
                b.seq_id[slot][0] = (llama_seq_id) plan.seq_of[(size_t) g];
                b.logits[slot]    = plan.want_logits[(size_t) g] ? 1 : 0;
            }
        }
        b.n_tokens = (int32_t) segTokens;

        // ── 提交前的读数（0.9.211 补）────────────────────────────────────────
        // 用户报"合批 <N 就闪退"时，**最要紧的那个数**是"这一批真实提交了多少
        // token / 几条" —— 它在 0.9.210 及以前**一行都没打**，于是"2 条为什么
        // 也会崩"只能靠倒推（而两个方向的倒推都说得通：块太大 / 门太小）。
        //
        // 一行同时给出三个数，让两道门**当场可比**：
        //   · 本批条数 / 本批 token 数 —— 真实提交量；
        //   · `n_batch` / `n_ubatch`   —— 库的两扇门（`llama_n_batch` /
        //     `llama_n_ubatch`，与 `embed model ready` 那一行同源）。
        // 因果模型的门是 `n_batch`（`:1711`），非因果是 `n_ubatch`（`:1426`）——
        // 两行并排看就知道撞的是哪一扇，不必再去猜模型的架构。
        //
        // ⚠ 打的是**这一段**（切段后）的真实数，不是整批 —— 每段一次 decode，
        // 撞门也发生在**段**这一级。整批数在上面那条"按容量切段提交"的日志里。
        jlog("[embedBatch] 提交：本段 %d 条 / %d tok（第 %d 段，本批共 %d 条），"
             "n_batch=%d n_ubatch=%d",
             segRows, segTokens, decodeCount + 1,
             plan.n_rows, nBatch, (int) llama_n_ubatch(E.ctx));

        // 提交给库**之前**的 logits 自检 —— 与单条路径同一道闸（`logits_ok_for_library`），
        // 但**按条目**问（拿整批去问会得到错的答案：它按 nTokens 判长度，
        // 而每条的库约束是"该条整段 / 至少一个"）。`batch_is_valid` 内部已经逐条问过，
        // 这里不再重复 —— 只留一条说明，避免下一个人以为漏了。
        if (llama_decode(E.ctx, b) != 0) {
            jlog("[embedBatch] decode 失败（本段 n_tokens=%d，第 %d/%d 段），返回 null 回落逐条",
                 (int) b.n_tokens, decodeCount + 1, plan.n_rows);
            llama_batch_free(b);
            // decode 失败后 KV 里是半截内容 —— 全部 seq 立刻清掉（与单条路径同源）。
            for (int j = 0; j < E.seq_max; j++)
                llama_memory_seq_rm(llama_get_memory(E.ctx), (llama_seq_id) j, -1, -1);
            embed_failure_set(embedtext::EmbedFailure::DECODE_FAILED, plan.n_tokens,
                              nEmbd, nEmbdOut, poolType, 0.0);
            return nullptr;
        }

        // ── 取这一段的向量 + 归一化 ──────────────────────────────────────
        // ⚠ 取向量这一支**按档分岔**（`read_from_seq`），与单条路径**同一个判据**：
        //   · MEAN(1) / CLS(2) / LAST(3)：池化结果在库的 `embd_seq` 里，
        //     走 `llama_get_embeddings_seq(ctx, seq_id)`；合批时第 j 条的 seq_id == 计划行号
        //     （由 `plan_batch` 保证），所以实参就是 `gi` —— 单条路径那里写死了 0，
        //     因为它只有一个 seq，**不是**因为"只有第 0 行有数据"。
        //   · NONE(0) / UNSPECIFIED(-1)：库逐 token 写 `embd.data`，走 `ith`，
        //     排位 == 这一段内的序（`batch_output_layout_ok` 保证整批"每条一个 1"，
        //     所以这一段内的排位就是段内下标 j）。
        //
        // 上一版这里**只**走 `ith`，而 `batchable_pooling` 把三档挡在外面，
        // 所以没暴露。闸一撤（0.9.208）若这里不改，BGE / Qwen3 会读到
        // `embd.data` 那块**从未写入**的内存（池化结果在 `result_embd_pooled`
        // 那个独立张量里）——全零 -> 逐条 `ZERO_NORM`，而合批这一层会把它们记成
        // "第 j 条失败"，看起来像"合批把好条目弄坏了"，实际上是我们问了错的那个 API。
        //
        // ⚠ 越界判据按**这一段**的条数（`segRows`），不是整批 —— 库的 `embd_seq`
        // 里这一轮只有这一段那几条被写过（每段之前都清过所有 seq，见上面）。
        const bool from_seq = embedtext::read_from_seq(poolType);
        for (int j = 0; j < segRows; j++) {
            const int gi = submitted + j;              // 计划里的行号（== 这一条的 seq 号）
            const int rowIdx = live[(size_t) gi];      // 调用方看到的原下标
            // 取值下标：走 seq 时是**那条的 seq 号**（= gi），走 ith 时是**段内 output 排位**（= j）。
            // 合批按段提交时这两个数**不再同值**（`gi` 是整批行号、`j` 是段内排位）——
            // 但**来历**本来就不同（一个是 seq 号、一个是 output 数组下标），
            // 所以这里仍分两处写：任一侧的布局一变，改动点各自清楚。
            const int slot = from_seq ? embedtext::pooled_vector_slot(gi) : j;
            // 越界必须**在调用前**变成一条能归因的拒绝：`get_embeddings_seq` 对越界
            // 下标没有契约（可能 null、可能返回别的行），而"读到看似正常的别的行"
            // 正是本仓库最不想要的那类失败（长度维度全对、语义是别人的）。
            if (from_seq && !embedtext::pooled_slot_is_row(slot, segRows)) {
                jlog("[embedBatch] seq 下标越界（slot=%d, 本段 %d 条），整批拒绝——这是填充逻辑的 bug",
                     slot, segRows);
                embed_failure_set(embedtext::EmbedFailure::POOLING_CONTRACT, plan.n_tokens,
                                  nEmbd, nEmbdOut, poolType, 0.0);
                // ⚠ 这里**不许**再 `llama_batch_free(b)` —— `b` 已经在 decode 之后
                // 还回去了，双重释放是进程级崩溃，而它的现场指向的是这里，
                // 不是真正的错（越界下标）。
                return nullptr;
            }
            float * raw = from_seq ? llama_get_embeddings_seq(E.ctx, (llama_seq_id) slot)
                                   : llama_get_embeddings_ith(E.ctx, slot);
            if (!raw) {
                jlog("[embedBatch] %s(%d) 返回 null（第 %d 条留零向量）",
                     from_seq ? "get_embeddings_seq" : "get_embeddings_ith", slot, rowIdx);
                g_embed_batch_failed.push_back(rowIdx);
                embed_failure_set(embedtext::EmbedFailure::NO_OUTPUT, plan.rows[(size_t) gi].n,
                                  nEmbd, nEmbdOut, poolType, 0.0);
                continue;
            }
            std::vector<float> vec(raw, raw + nEmbd);
            const double norm = embedtext::norm_of(vec.data(), vec.size());
            if (!embedtext::l2_normalize(vec.data(), vec.size())) {
                jlog("[embedBatch] 第 %d 条归一化失败（范数=%.3g，n_tokens=%d，pooling=%d），留零向量",
                     rowIdx, norm, plan.rows[(size_t) gi].n, poolType);
                g_embed_batch_failed.push_back(rowIdx);
                embed_failure_set(embedtext::EmbedFailure::ZERO_NORM, plan.rows[(size_t) gi].n,
                                  nEmbd, nEmbdOut, poolType, norm);
                continue;
            }
            std::memcpy(flat.data() + (size_t) rowIdx * (size_t) nEmbd,
                        vec.data(), sizeof(float) * (size_t) nEmbd);
        }
        llama_batch_free(b);

        // ⚠ 这一段**必须先还 batch 再清 KV**（顺序反了就是"清掉正要读的 seq"）。
        // 清的是**所有** seq（见本函数顶部那段：只清段内那几个会让下一段
        // 复用上一段的 KV，向量里混进上一段的语义，而接口 200）。
        for (int j = 0; j < E.seq_max; j++)
            llama_memory_seq_rm(llama_get_memory(E.ctx), (llama_seq_id) j, -1, -1);

        decodeCount++;
        submitted += segRows;
    }
    // 切段的读数：**一次调用替库省了几次 decode** 只有这里说得出来。
    // 只切一段（正常路径）时也打一行 —— 它同时是"这一批没有长到需要切"的证据，
    // 否则"切段到底生效没生效"只能靠倒推（而两个方向的倒推都说得通）。
    if (decodeCount > 1) {
        jlog("[embedBatch] 本批 %d 条解码 %d 次（n_batch=%d，逐条本要 %d 次）",
             plan.n_rows, decodeCount, nBatch, plan.n_rows);
    }
    // 坏条目（tokenize 不出来 / 空）也记进失败下标 —— 它们没有进批，
    // 但调用方必须知道"是哪几条"，否则那几条的正文会在 UI 上看起来正常。
    for (jsize j = 0; j < nRows; j++)
        if (bad[(size_t) j]) g_embed_batch_failed.push_back((int) j);
    // ⚠ 这里**没有**"没提交的那几条"要记：`submitted` 走到这里必然 == `plan.n_rows`
    //（上面那个 while 的退出条件就是它）。早先的版本在这里加过一个"从 submitRows
    // 到 plan.n_rows"的尾巴循环 —— 它是**恒不执行**的死代码，而更坏的是它会让人
    // 以为"切段"这件事已经做了。
    // 反过来，"切段"这件事**必须**在循环里做完：给调用方的契约只有两种
    //（返回 null = 整批回落逐条 / 返回数组 = 这一批每条一个向量），
    // 少算几条而不记下标时，那几条的正文会在 UI 上看起来正常。

    env->SetFloatArrayRegion(out, 0, nRows * nEmbd, flat.data());
    return out;
}

/**
 * 上一次 `nativeEmbedBatch` 里**失败的那几条**（原下标，升序、逗号分隔）。
 *
 * 为什么不能只靠"返回数组里那一段是全零"来判：全零与"这一条真的是零向量"
 * 在数值上同形，而后者在 `l2_normalize` 的契约里是要**丢弃**的（它没有方向）。
 * 也就是说"全零"这个读数本身没有区分能力，必须由产生它的那一层把**原因与下标**
 * 带出来 —— 与 `nativeEmbedLastFailure`（单条路径）同一个取数模式。
 *
 * 返回值是逗号分隔的下标串（空串 = 本批全成功）。调用方在**同一把锁内**立刻取走。
 */
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatchFailed(JNIEnv * env, jclass) {
    if (g_embed_batch_failed.empty()) return env->NewStringUTF("");
    std::string out;
    for (size_t i = 0; i < g_embed_batch_failed.size(); i++) {
        if (i) out += ",";
        out += std::to_string(g_embed_batch_failed[i]);
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeModelDesc(JNIEnv * env, jclass) {
    if (!S.model) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(S.model, desc, sizeof(desc));
    // desc 按字节截断，中文模型名可能留下半个多字节序列 → 必须走安全解码
    return new_string_utf8_safe(env, desc);
}

// ═══════════════════════════════════════════════════════════════════════════
//  模型档案（GGUF 元数据）—— `/v1/models/{id}` 与 `[模型档案]` 日志行的唯一取数口
// ═══════════════════════════════════════════════════════════════════════════
//
// 为什么值得做：App 里关于"这个模型到底是什么"的可读的数**一个都没有** ——
// 只有一行 256 字节的 `llama_model_desc`（库自己拼的短描述）。
// 而 `ModelStore.Row.bytes` 是**文件字节**，与**权重字节**、**参数个数**、
// **上下文上限**是三件完全不同的事。本仓库反复出现的坑正是"哪两个数不是一回事"
//（文件名写着 4B 实际是别的、文件名写着 Q4_K_M 实际是 IQ4_XS，见 `[HTP判定]` 那一族）。
// 把库真实读出来的档案接到 `[模型档案]` 日志与 `/v1/models/{id}` 上之后，
// "这个包是不是这次构建""这份权重到底多大"就不必靠 `ls -l` 和猜。
//
// 返回 String[8]（**顺序是接口**，Kotlin 侧 ModelProfile.fromNative 按同一顺序解）：
//   [0] 权重张量字节（llama_model_size，**不是**文件字节）
//   [1] 参数个数（llama_model_n_params）
//   [2] 模型名（GGUF `general.name`）
//   [3] 训练上下文上限（`llama.context_length`，取不到时回落 llama_model_n_ctx_train）
//   [4] 架构名（`general.architecture`）
//   [5] 词表大小
//   [6] 量化 dtype（`general.file_type`，库内部枚举值，字符串形式）
//   [7] GGUF 元数据键值对条数
// 每个数**各自**可以取不到（未加载 / 该字段缺失）；取不到一律回空串，
// **绝不**回 "0" —— 0 与"读不到"在客户端完全同形，而"0 层"是个不存在的东西。
//
// 关于 `llama_model_meta_val_str` 的截断，这里必须写死：
//   它把值**写进你给的 buffer**，返回**实际长度**，且按头文件约定这个长度**可以 ≥
//   buffer_size**（返回值 -1 才表示失败）。也就是说 buffer 小了会**静默截断**：
//   不报错、不抛异常，返回一个"看起来正常"的短串。这正是本仓库最擅长记的那类
//   "哑得不响"的错。所以下面逐字段判 `n >= (int) sizeof(buf)` —— 判据取 **>=**
//   而不是 >：等长说明写满了、无法区分"正好写满"与"被截断"，宁可当截断处理。
//   8 KiB 对 GGUF 里全部 kv 字符串都远远够（最长的是 tokenizer 词表被拼成的大串，
//   而下面根本不请求那个键）。
//
// 关于 `llama_model_size` / `llama_model_n_params` 的溢出：两者声明是 `uint64_t`，
// 而 JNI 的 `jlong` 是**有符号** 64 位。正常模型（几十 GB、几十 B 参数）离 2^63 差着
// 九个数量级，但真溢出了会静默变成一个**负数**。所以就地夹到 `INT64_MAX`：
//   · 夹了也是错的（数不准），但那是**看得见**的错（客户端拿到的不是个合法量级）；
//   · 不夹则表现为"这个模型大小是 -3 GB"，比截断更难认。宁可要看得见的错。
static const char * model_meta_query(const char * key) {
    // 静态缓冲：本函数只由 JNI 调用（单线程语义与 S 的其他访问者相同），
    // 且返回值立刻被拷进 std::string 数组，不跨调用存活。
    static char buf[8192];
    buf[0] = '\0';
    if (!S.model) return nullptr;
    const int n = llama_model_meta_val_str(S.model, key, buf, sizeof(buf));
    if (n < 0) return nullptr;                       // 键不存在 / 库侧失败
    if (n >= (int) sizeof(buf)) return nullptr;      // 截断了 —— 当"读不到"，不回半截值
    buf[sizeof(buf) - 1] = '\0';                     // 防御：库未按约定置尾零时兜底
    return buf[0] ? buf : nullptr;                   // 空值同样按"读不到"处理
}

JNIEXPORT jobjectArray JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeModelMeta(JNIEnv * env, jclass) {
    // 8 个字段的取值顺序**是接口**：Kotlin 侧 ModelProfile.fromNative 按同一顺序解，
    // 两边由 tools/run_models_profile_guard.sh 钉住同一个 8。
    const int kFields = 8;
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    // 初值**必须是**一个真实空串、且**只造一个**：
    //   · 用 `nullptr` 当初值会让数组里是 null —— Kotlin 侧声明是
    //     `Array<String>`（非空元素），解包处会 NPE，而那是"档案读不到"
    //     最不该付的代价；
    //   · 每格各造一个 `NewStringUTF("")` 则一次产生 8 个 local ref。
    //     local ref 表默认 512 格，而这个函数每次 `/v1/models` 都调一次 ——
    //     泄漏的形态是"跑一阵子之后这个线程上的任何 JNI 调用都失败"，
    //     与本改动毫无表面关联，最难查。
    jstring empty = env->NewStringUTF("");
    jobjectArray out = env->NewObjectArray(kFields, strCls, empty);
    if (empty) env->DeleteLocalRef(empty);
    env->DeleteLocalRef(strCls);
    if (!out) return nullptr;

    auto put = [&](int i, const std::string & v) {
        jstring s = new_string_utf8_safe(env, v.c_str());
        if (s) { env->SetObjectArrayElement(out, i, s); env->DeleteLocalRef(s); }
    };
    auto put_u64 = [&](int i, uint64_t v) {
        // 夹到 INT64_MAX：见上方注释（溢出要变成看得见的错）
        if (v > (uint64_t) INT64_MAX) v = (uint64_t) INT64_MAX;
        put(i, std::to_string((long long) v));
    };

    if (!S.model) return out;   // 未加载：8 个空串，调用方据此判「档案不可用」

    put_u64(0, (uint64_t) llama_model_size(S.model));    // 权重张量字节（≠ 文件字节）
    put_u64(1, (uint64_t) llama_model_n_params(S.model));// 参数个数
    // ⚠ 下标**不可颠倒**：上面两个都是 `uint64_t` 返回。写反了不报错、类型都对，
    //   只会让"权重 4 GB / 参数 0.3 个"这种读数悄悄出现在 /v1/models 上。
    {
        char buf[256] = {0};
        if (llama_model_meta_val_str(S.model, "general.name", buf, sizeof(buf)) >= 0) put(2, buf);
    }
    {
        // n_ctx_train = GGUF 声明的**训练时**上下文上限，不是用户设的 n_ctx。
        // 取不到时回落到 `llama_model_n_ctx_train`（同一个量的库侧接口）。
        const char * v = model_meta_query("llama.context_length");
        if (v) put(3, v);
        else {
            const int n = (int) llama_model_n_ctx_train(S.model);
            if (n > 0) put(3, std::to_string(n));
        }
    }
    {
        char buf[256] = {0};
        if (llama_model_meta_val_str(S.model, "general.architecture", buf, sizeof(buf)) >= 0) put(4, buf);
    }
    put(5, std::to_string((int) llama_vocab_n_tokens(S.vocab ? S.vocab : llama_model_get_vocab(S.model))));
    {
        char buf[256] = {0};
        if (llama_model_meta_val_str(S.model, "general.file_type", buf, sizeof(buf)) >= 0) put(6, buf);
    }
    put(7, std::to_string((int) llama_model_meta_count(S.model)));
    return out;
}

JNIEXPORT jint JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeCtxSize(JNIEnv *, jclass) { return S.n_ctx; }
JNIEXPORT jint JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeCtxUsed(JNIEnv *, jclass)  { return S.n_used; }
// KV 前缀复用的可观测面（/health 用）：上一轮复用了多少 token、新算了多少 token、
// 以及**已经跑完 prefill 的轮次数**。
// 返回 int[4] = { last_reuse, last_prefill_tokens, kv_rounds, last_lcp }。
//
// 为什么必须暴露前两个：复用是**纯加速**，效果不体现为任何状态变化 —— 没有这两个数，
// "缓存到底有没有生效"只能靠读 native 日志，而日志默认是关的。
//
// 为什么必须暴露第三个（kv_rounds）：`kv_valid=false` 同时覆盖两种处境 ——
// 「从未跑过任何请求」与「跑过、但账本已被作废（abort / 换模型 / prompt 超长）」。
// 只报 kv_valid 会让它们同形，而一个是冷启动、另一个是故障/取消。
// kv_rounds==0 即"从未跑过"，> 0 且 kv_valid==false 即"跑过但账本已失效"。
JNIEXPORT jintArray JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeKvCacheStats(JNIEnv * env, jclass) {
    jintArray a = env->NewIntArray(4);
    if (!a) return nullptr;
    jint v[4] = { (jint) S.last_reuse, (jint) S.last_prefill_tokens, (jint) S.kv_rounds,
                  (jint) S.last_lcp };
    env->SetIntArrayRegion(a, 0, 4, v);
    return a;
}

// 取"当前这一轮的编号"。调用方在 startCompletion **成功**之后、同线程立刻调用
// （生成循环持 genLock，任何时刻只有一轮，所以读到的就是本轮编号），
// 把编号绑进 `RequestCancel.Token`，取消时回传给 nativeAbort 做归属核对。
// 没有这一跳，Kotlin 侧的归属就只防到 JNI 门口 —— 见 nativeAbort 的注释。
JNIEXPORT jlong JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeCurrentEpoch(JNIEnv *, jclass) {
    return (jlong) S.round_epoch.load();
}

// 本轮已进 KV 账本的生成 token 数（可观测面，见 Session::gen_in_ledger）。
// 取消收尾时与"已下发的步数"对账：两者不等说明有一段内容引擎算过、客户端没收到，
// 客户端若按"我收到的文本"拼历史，下一轮就与引擎手里的对不上。
// 推理期 RSS 峰值（MB；-1 = 还没跑过任何一轮或读不到）。
// 与 `[mmap释放]` 的加载态读数**成对读**：加载态说明"装载花了多少"，
// 这里说明"真正用起来之后到过多少" —— 优化内存时后者才是要盯的数。
JNIEXPORT jint JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeRssPeakMb(JNIEnv *, jclass) {
    return (jint) (g_rss_peak_kb > 0 ? g_rss_peak_kb / 1024 : -1);
}

// 「free 了但 RSS 不降」的那一脚，只在**卸载后空闲**这一刻由 Kotlin 调（见 LlmEngine
// 的 reclaimReleasedHeap）。返回值是该打的一行日志（空串 = 不报读）。
//
// 为什么需要这一脚（0.9.130 现场）：
//   repack 的拷贝、KV、compute buffer 都是**匿名页**。`free` 只把它们交回**分配器**，
//   而 CPU 侧的分配器默认会**缓存刚释放的堆段**（glibc 的 M_TRIM_THRESHOLD /
//   M_MMAP_THRESHOLD、Android 上的 scudo size-class 缓存），于是 VmRSS 一分不降 ——
//   应用列表里显示的占用同源，用户看到的是"关掉 repack 也没省"。
//   要拿回来只有两条路：`malloc_trim(0)`（把空闲堆段还给内核），或整进程重启。
//
// 做法（**只留能自证安全的那一条**，理由见下方 ISSUE #274）：
//   ① 先读一次 RSS 作基线；
//   ② `malloc_trim(0)`（只有 glibc 有这个名字，见文件头的条件 include）；
//   ③ bionic 上没有 ② —— 于是**什么都不做**（旧版在这里补了一段匿名段 DONTNEED，
//      正是它崩了进程，见下方 ISSUE #274 那段）。
//   ④ 再读一次，把前后值打进日志 —— **自证**，否则"到底还了没有"又只能靠装机读 /proc。
//
// ⚠ 只用"本进程内一定成功"的手段，**不 fork 任何子进程**：本仓库此前在 native probe 里
//   `fork`+`execl` 读系统属性，在国产 ROM 上被域策略整族拦掉（见 `probe_getprop` 那段）。
//   `mallinfo2`/`mallinfo` 同属 glibc 专有 —— 因此不用它，只在日志里报 RSS 差值。
//
// 失败**不影响任何结果**：最坏情况就是"没多还这一份"，与不做之前完全一致。
//
// ═══════════════════════════════════════════════════════════════════════════
// ⚠⚠ 0.9.1xx 现场（ISSUE #274）：旧实现把整段匿名堆 DONTNEED 掉，直接崩进程
// ═══════════════════════════════════════════════════════════════════════════
// 旧实现在这里从 /proc/self/maps 挑出**最大的 `rw-p` 私有匿名区间**，对整段
// `madvise(MADV_DONTNEED)`，理由是"私有匿名段里只会有本进程的堆/静态区，
// DONTNEED 不影响任何被引用的数据"。**这句是错的**，而且是致命的那种错：
//
//   · bionic 的 scudo 把主堆做成一整块 `rw-p` 匿名映射（就是"最大区间"命中
//     的那一块）。它里面**同时**装着活对象与空闲块 —— DONTNEED 不看这些，
//     它按页清零。于是活对象的字节被抹成 0。
//   · 被抹掉的包括 scudo 分配器的 **chunk 头**。下一次 `free()` 读到全 0 的头，
//     认不出这是自己发出去的块，直接 `abort()`：
//
//       [stderr] Invalid 0x732a679000 passed to free: value not allocated,
//                chunk: 0x732a600000 mapbits: 0 msg:
//
//     这就是用户报的"加载对话模型闪退"——崩在**卸载/加载交替的那一刻**，
//     而不是加载本身：STOP 卸模型 → 这一脚抹堆 → START 重新加载时
//     分配器第一次 `free` 就撞上被抹坏的头（现场见探针日志，
//     上一行正是 `[内存] 卸载[对话] RSS 1844 MB → 1844 MB`，紧随其后就是那行 stderr）。
//
// 教训与本仓库 `release_populated_pages` 那次**同源**：DONTNEED 只能打在
// **自己完全拥有、且确定里面没有活数据**的区间上。mmap 那条路是文件映射
// （按路径认出来的，页是干净的、丢了自己会缺页读回），所以安全；
// "最大的匿名段"没有任何东西保证它里头没有活对象 —— 它是**猜**的。
// 一条把"猜"当成判据的内存路径，迟早用一次 SIGABRT 来收学费。
//
// 因此本函数**只保留能自证安全的那条路**：
//   · glibc：`malloc_trim(0)` —— 由分配器自己判断哪些段可还，它知道哪里是空闲的；
//     bionic 没有这个接口（scudo 也没有等价物），那就**什么都不做**。
//   · 不碰任何匿名区间。宁可"这一脚没省下内存"，也不做一件可能抹掉活对象的事。
//     "没省下"是性能问题，"抹掉"是崩溃问题；两者不能放在同一个取舍里比。
//
// 保留这一段的意义仍是自证：把 RSS 前后值打进日志，让"到底还了没有"可读 ——
// 只是现在它能报的最大额度就是 malloc_trim 的那一份。
extern "C" JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeReclaimReleasedHeap(JNIEnv * env, jclass) {
    const long before_kb = proc_rss_kb();

    bool trimmed = false;
#if defined(__GLIBC__)
    trimmed = (malloc_trim(0) != 0);
#endif

    const long after_kb = proc_rss_kb();
    const long freed_kb = (before_kb >= 0 && after_kb >= 0) ? (before_kb - after_kb) : -1;

    if (freed_kb <= 0) return nullptr;
    char msg[256];
    // bionic 上没有 malloc_trim：这一脚在 Android 上是 no-op，如实写出来 ——
    // 不写成"已归还"（那会是一条恒真的假读数，本仓库为这种事付过学费）。
    const char * how = trimmed ? "已调用" : "本 libc 无此接口（不还：匿名段 DONTNEED 会抹活对象，已移除）";
    snprintf(msg, sizeof(msg),
             "[内存] 归还空闲堆段 RSS %ld MB → %ld MB（还 %ld MB）｜ malloc_trim=%s",
             before_kb / 1024, after_kb / 1024, freed_kb / 1024, how);
    return env->NewStringUTF(msg);
}

JNIEXPORT jint JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeRoundLedgerTokens(JNIEnv *, jclass) {
    return (jint) S.gen_in_ledger;
}
// KV 账本是否有效（/health 用）：false 表示下一轮必然全量 prefill。
// 单独一个出口而不是塞进上面的数组：它的语义是"缓存在不在"，
// 与"上一轮命中了多少"是两件事，混在一起会让 /health 的读法变得依赖历史。
JNIEXPORT jboolean JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeKvCacheValid(JNIEnv *, jclass) {
    return S.kv_valid ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeChatTemplate(JNIEnv * env, jclass) {
    if (!S.model) return env->NewStringUTF("");
    const char * t = llama_model_chat_template(S.model, nullptr);
    return t ? new_string_utf8_safe(env, t) : env->NewStringUTF("");
}

// jobjectArray(jstring) -> std::vector<std::string>；null 数组返回空。
// 元素为 null 时跳过；UTF 转换失败也跳过（宁可少一条 stop，也不要在这里抛异常过 JNI）。
static std::vector<std::string> jstring_array_to_vector(JNIEnv * env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!env || !arr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        auto js = (jstring) env->GetObjectArrayElement(arr, i);
        if (!js) continue;
        const char * sp = env->GetStringUTFChars(js, nullptr);
        if (sp) {
            out.emplace_back(sp);
            env->ReleaseStringUTFChars(js, sp);
        }
        env->DeleteLocalRef(js);
    }
    return out;
}

// ──────────────────────────────────────────────────────────────────────────────
// stop 序列采样器（OpenAI `stop` 协议，去掉超长匹配尾部）
// ──────────────────────────────────────────────────────────────────────────────
// 匹配语义在 stop_sequences.h（纯函数，宿主侧有同一份源码的单测）。这里只做采样器外壳。
//
// 为什么放在 native 而不是「生成后对字符串做 indexOf」：
//   1. 必须**边生成边**拦。`stream=true` 时若先整段生成再截断，客户端已经收到了
//      越过 stop 之后的垃圾内容，撤回不了 —— 那是协议级错误，不是体验问题。
//   2. 必须能处理「stop 串跨多个 token」：单看一个 token 的文本判不出命中，
//      要等到能确定"再怎么接也接不到 stop 了"才能把暂扣的那截放出去。
//      这个判断只有 token_to_piece 说得清，Kotlin 侧做不了。
//   3. 采样**之后**剔除（而不是在候选 logits 里掩掉）：不改变随机流。
//      掩 logits 会让同一个 seed 在「带 stop」与「不带 stop」时走出不同的样本序列，
//      排障时两条路径对不上，得不偿失。
struct stop_match_ctx {
    stopseq::MatchState st;
    /**
     * "词表里是否存在以该字节开头的 token piece" —— 256 项位图，挂载时算一次。
     *
     * 为什么需要它：暂扣期间每步都要判断"这个前缀还接得上吗"，若每步遍历 15 万词表
     * 并逐个 token_to_piece（可能堆分配），生成速度会被直接拖垮。
     * 而判定所需的信息只有"下一个字节能不能接上"，因此把它预先算成 O(1) 查表。
     * 见 stop_sequences.h:can_continue_fast 的说明。
     */
    bool head_byte[256] = {false};
    bool heads_ready = false;

    /**
     * `st.generated` 中**已经下发过**的字节数。见 stop_sampler_take_released：
     * 暂扣被放行时 generated 会一次多出好几段，nativeStep 必须按这个下标取增量，
     * 否则要么重复下发、要么把暂扣那截永远漏掉。
     *
     * 不可回退：它是"已经发给客户端了"的账，只增不减。
     */
    size_t emitted = 0;

    bool has_head(unsigned char b) const { return heads_ready && head_byte[b]; }
};

static const char * stop_sampler_name(const llama_sampler * /*smpl*/) { return "local-stop"; }

static void stop_sampler_apply(struct llama_sampler * smpl, llama_token_data_array * cur_p);
static void stop_sampler_accept(struct llama_sampler * smpl, llama_token token);
static void stop_sampler_reset(struct llama_sampler * smpl);
static struct llama_sampler * stop_sampler_clone(const struct llama_sampler * smpl);
static void stop_sampler_free(struct llama_sampler * smpl);

// 逐字段显式初始化，**不用** `= {}` 聚合省略：
//   · 省略成员虽然会被 C++ 值初始化为 nullptr（正确行为），
//     但一旦上游调整 llama_sampler_i 的字段顺序，省略写法不会报错、却会静默错位；
//   · 显式写全之后，字段增删/换序都会变成编译错误，这正是我们要的。
static struct llama_sampler_i stop_sampler_iface = {
    /* .name                = */ stop_sampler_name,
    /* .accept              = */ stop_sampler_accept,
    /* .apply               = */ stop_sampler_apply,
    /* .reset               = */ stop_sampler_reset,
    /* .clone               = */ stop_sampler_clone,
    /* .free                = */ stop_sampler_free,
    // 以下为 experimental 的「后端采样」接口。全置 nullptr = 明确声明"不支持"，
    // 采样链会回落到普通 CPU 路径（即本采样器的 apply 一定被调用到）——
    // 这是正确行为，绝不能把它写成非空指针。
    /* .backend_init        = */ nullptr,
    /* .backend_accept      = */ nullptr,
    /* .backend_set_input   = */ nullptr,
    /* .backend_reset       = */ nullptr,
    /* .copy_state          = */ nullptr,
};

// ── 采样器外壳：在哪一步判定 ──────────────────────────────────────────────
// 判定放在 **accept**（而不是 apply）里，理由是"哪个 token 会被真正采用"这件事
// 只有 accept 说得清：apply 阶段要靠 `cur_p->selected` 反查，那是 llama.cpp 的
// 内部约定（`llama_sampler_sample` 的等价写法），一旦某个选择器没按约定写
// `selected`，这里就会读到一个越界/错误的下标 —— 而"读错 token"在本采样器里的
// 后果是**错误地判成 stop，整段输出被吞**，是最不能接受的一类 bug。
//
// accept 的调用点由 `llama_sampler_sample` 保证（它先 apply 再 accept），
// 拿到的就是最终会被解码、会被下发的那个 token。这里不做任何猜测。
static void stop_sampler_apply(struct llama_sampler * /*smpl*/, llama_token_data_array * /*cur_p*/) {
    // 本采样器是"观察者"：不修改候选分布（不掩 logits、不改 p），
    // 因此 apply 什么都不做。**这不是可省略的空函数** ——
    // llama_sampler_i::apply 是必填项（见 llama.h 注释），留空实现是刻意的语义声明。
}

static void stop_sampler_accept(struct llama_sampler * smpl, llama_token token) {
    auto * c = (stop_match_ctx *) smpl->ctx;
    if (!c) return;
    // 状态机本身在 stop_sequences.h:advance —— **与宿主侧单测共用同一份源码**。
    // 这里只做"取 token 文本 + 落日志"的外壳，不重写任何判定。
    //
    // 曾经把判定顺序手抄在本函数里（含一条 `if (!withheld.empty()) return;` 的
    // 早退），而单测里另抄了一份、恰好漏掉那条早退：线上多 token 的 stop 直接
    // 卡死（永不命中、暂扣永不放行），测试却全绿。教训见 stop_sequences.h:advance。
    const bool had_withheld = !c->st.withheld.empty();
    const std::string next = token_to_piece(token);
    const bool released = stopseq::advance(
        c->st, next, [c](unsigned char b) { return c->has_head(b); }, c->heads_ready);
    if (c->st.hit) { jlog("[stop] 命中 stop 序列，结束本轮生成\n"); return; }
    if (released && had_withheld) {
        jp("[stop] 暂扣前缀已放行（前缀分叉或词表接不上），共 %d 字节\n", (int) c->st.generated.size());
    }
}

static void stop_sampler_reset(struct llama_sampler * smpl) {
    auto * c = (stop_match_ctx *) smpl->ctx;
    if (!c) return;
    c->st.reset();
    // emitted 必须一起清零：它是"generated 里已下发到哪"的账，
    // st.reset() 之后 generated 已空，账留着会让下一轮的首批正文被整段跳过。
    c->emitted = 0;
}

static struct llama_sampler * stop_sampler_clone(const struct llama_sampler * smpl) {
    auto * c = (stop_match_ctx *) smpl->ctx;
    if (!c) return nullptr;
    return llama_sampler_init(&stop_sampler_iface, new stop_match_ctx(*c));
}

static void stop_sampler_free(struct llama_sampler * smpl) {
    if (!smpl) return;
    delete (stop_match_ctx *) smpl->ctx;
    smpl->ctx = nullptr;
}

// 链上是否挂着 stop 采样器。nativeStep 需要据此决定走"增量放行"还是"无条件下发"。
static bool stop_sampler_present(struct llama_sampler * chain) {
    if (!chain) return false;
    const int n = llama_sampler_chain_n(chain);
    for (int i = 0; i < n; i++) {
        struct llama_sampler * sm = llama_sampler_chain_get(chain, i);
        if (sm && sm->iface && sm->iface == &stop_sampler_iface) return true;
    }
    return false;
}

// 取「状态机新放行、但还没下发过」的文本。
//
// 为什么需要单独记住一个下标：st.generated 是**单调增长**的已放行缓冲，
// 而 nativeStep 每步只该下发"新增的那一段"。暂扣会让 generated 出现跳跃
// （暂扣部分连同当前 token 一起放行），所以不能简单地 `+= token_to_piece(tok)`。
// 这里用 ctx 里的 emitted 下标做账：只返回 generated 中尚未取走的部分。
// 无 stop 采样器时返回空串（调用方回落到原逻辑，行为不变）。
static std::string stop_sampler_take_released(struct llama_sampler * chain) {
    if (!chain) return std::string();
    const int n = llama_sampler_chain_n(chain);
    for (int i = 0; i < n; i++) {
        struct llama_sampler * sm = llama_sampler_chain_get(chain, i);
        if (!sm || !sm->iface || sm->iface != &stop_sampler_iface) continue;
        auto * c = (stop_match_ctx *) sm->ctx;
        if (!c) return std::string();
        const std::string & g = c->st.generated;
        // emitted 是"已经下发给客户端的字节数"，只增不减。
        //
        // ⚠ 与 stop_sequences.h 的 split_release_withhold 有一处必须说清的耦合：
        // 那个函数会把 `generated` 的**尾巴回退**进 `withheld`（一条 stop 可能从
        // `generated` 的中途开始），于是 `generated` 会**变短**。若回退越过了
        // 这条"已下发"界线，下面的钳制就会把尚未下发的字节永久丢掉（或多发一遍）。
        // 它**不会**越过：字节只有在"已被证明不是任何 stop 前缀"时才会从 withheld
        // 放行，而放行之后不可能再被重新证明是 stop 前缀 —— 回退区间恒在
        // [emitted, ...) 之内。这条由 tools/run_stop_match_tests.sh 的
        // "emitted 账"一组用随机流逐字节对照钉住（钳制必须一次都不触发）。
        // 钳制本身保留为**异常状态下的保守兜底**（宁可少发也不要读越界）。
        if (c->emitted > g.size()) c->emitted = g.size();
        std::string out = g.substr(c->emitted);
        c->emitted = g.size();
        return out;
    }
    return std::string();
}

// 链上是否已命中 stop（供 nativeStep 结束本轮）；无 stop 采样器时恒 false。
static bool stop_sampler_did_hit(struct llama_sampler * chain) {
    if (!chain) return false;
    const int n = llama_sampler_chain_n(chain);
    for (int i = 0; i < n; i++) {
        struct llama_sampler * sm = llama_sampler_chain_get(chain, i);
        if (!sm || !sm->iface || sm->iface != &stop_sampler_iface) continue;
        auto * c = (stop_match_ctx *) sm->ctx;
        return c && c->st.hit;
    }
    return false;
}

// ── 结构化输出（response_format）的两个辅助函数：**前置声明** ──────────────
// 这两者定义在文件后半（[attach_grammar_sampler] / [gbnf_from_json_schema]），
// 但 nativeNewSampler 在它们之前就要调用。它们都是 `static`（内部链接），
// 没有前置声明时 C++ 直接报 "was not declared in this scope" ——
// 本地 kotlinc 只查 Kotlin、走不到这里，只有真编 native 才会暴露。
// 声明与定义必须逐字一致（含 static），否则又变成"声明一个、定义另一个"。
// 改动后由 tools/run_jni_schema_syntax.sh（抽取单元真编）与结构化输出守卫一起钉住。
// 产物：GBNF 原文 + **grammar 采样器要预填的那段文本**（见 grammar_prefill_from_literal）。
// 两者必须一起产出：预填文本要拿 grammar 的**首字面量**去算，而首字面量只有
// templates_apply 产出的 PEG（cp.parser）里才有 —— 出了这个函数就只剩一串 GBNF 文本，
// 再从文本里反解字面量等于自己写一个 GBNF 解析器（那是新的故障面）。
struct schema_grammar_result {
    std::string gbnf;
    std::string prefill;      // 空 = 不预填（退化成引入本修复之前的行为）
    std::string pegLiteral;   // 从 PEG 现场取到的首字面量（**仅日志**；空 = 没取到）
    std::string pegWhy;       // 没取到首字面量的原因（仅日志）
};
static schema_grammar_result gbnf_from_json_schema(const char * schemaJson, const char * tmplOverride,
                                                   const char * genPrompt, bool thinkingOn);
static bool attach_grammar_sampler(llama_sampler * chain, const char * gbnf, const char * prefill,
                                    const char * genSuffixForMismatch, const char * pegLiteral);

// newSampler(temp, topP, minP, topK, repPenalty, penaltyN, freqPenalty, presencePenalty, seed, stops, schema, genPrompt, tmpl)
// 采样链顺序（与 llama.cpp 官方示例一致：截断在前、温度在截断之后、dist 收尾）：
//   grammar -> penalties -> top_k -> top_p -> min_p -> temp -> dist
// grammar 只在带 response_format 时入链，且**必须排在所有选择器之前**：
// 它的作用是"把不合语法的候选 logit 置 -inf"，属于约束而非选择（见 attach_grammar_sampler）。
// 惩罚必须排在所有截断之前：否则被 top_k/top_p/min_p 砍掉的 token 无法被惩罚救回，
// 出现重复时惩罚力度显著弱于配置值。
// temp<=0 视为贪心（此时忽略其余采样参数，与 llama.cpp 一致）。
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeNewSampler(
        JNIEnv * env, jclass, jfloat temp, jfloat topP, jfloat minP, jint topK,
        jfloat repPenalty, jint penaltyN, jfloat freqPenalty, jfloat presencePenalty, jlong seed,
        jobjectArray jstops, jstring jschema, jstring jgenPrompt, jstring jtmpl,
        jboolean jthinkingOn, jintArray jbiasIds, jfloatArray jbiasVals) {
    jp(">> newSampler temp=%.3f topP=%.3f minP=%.3f topK=%d rep=%.3f n=%d freq=%.3f pres=%.3f seed=%lld"
       " schema=%s genPrompt=%s think=%d bias=%d\n",
       (double) temp, (double) topP, (double) minP, (int) topK, (double) repPenalty, (int) penaltyN,
       (double) freqPenalty, (double) presencePenalty, (long long) seed,
       jschema ? "有" : "无", jgenPrompt ? "有" : "无", (int) (jthinkingOn == JNI_TRUE),
       jbiasIds ? (int) env->GetArrayLength(jbiasIds) : 0);
    if (!S.ctx) { jp("<< newSampler 无 ctx -> false\n"); return JNI_FALSE; }
    if (S.smpl) { llama_sampler_free(S.smpl); S.smpl = nullptr; }
    llama_sampler * ch = llama_sampler_chain_init(llama_sampler_chain_default_params());

    // 结构化输出（response_format）：**先于**任何选择器插入 grammar 约束（见
    // attach_grammar_sampler 的判据 ③）。schema 为 null = 调用方没要求结构化输出，
    // 此时这一步是空操作，链序与引入本特性之前**逐字节相同**。
    //
    // 放在 greedy 分支**之前**，让 temp<=0 的贪心解码同样受约束 ——
    // 否则"结构化输出 + temperature=0"这一组合会静默不受约束（贪心分支里没有选择器，
    // grammar 依旧有效，所以约束必须已经挂上）。
    {
        const char * sc = jschema ? env->GetStringUTFChars(jschema, nullptr) : nullptr;
        const char * tm = jtmpl ? env->GetStringUTFChars(jtmpl, nullptr) : nullptr;
        if (sc) {
            // 生成后缀（渲染侧给出）：null = 无 / 未知 -> 退回旧口径（见 gbnf_from_json_schema）。
            // 传空串是**有意义**的：调用方明确说"这一轮没有生成后缀"。
            const char * gp = jgenPrompt ? env->GetStringUTFChars(jgenPrompt, nullptr) : nullptr;
            // 两步分开：先让模板引擎把 schema 变成 GBNF，再把 GBNF 编成采样器。
            // 任一步失败都只记录、不失败请求（见 gbnf_from_json_schema 的判据 ②）。
            const schema_grammar_result sg =
                gbnf_from_json_schema(sc, tm, gp, jthinkingOn == JNI_TRUE);
            // 第四个实参：**真后缀**（= 渲染侧交回的那段 cp.generation_prompt）。
            // 预填只把 grammar 推过"首字面量"那一截；真后缀比它长的那一段（错位段）
            // 也要一起咽下去，否则 grammar 的落点与模型续写的位置差着那一段 ——
            // 见 advance_grammar_past_mismatch 的文件头（第十处成因）。
            attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,
                                   sg.pegLiteral.c_str());
            if (gp) env->ReleaseStringUTFChars(jgenPrompt, gp);
        }
        if (tm) env->ReleaseStringUTFChars(jtmpl, tm);
        if (sc) env->ReleaseStringUTFChars(jschema, sc);
    }

    // logit_bias（OpenAI `logit_bias`）：给指定 token 的 logit 加偏置。
    //
    // 位置：**在所有选择器与贪心分支之前**，紧跟 grammar（上面那段）。两条理由：
    //   · 语义上它是"改候选分数"，属于约束类而不是选择类（与 grammar 同一族）；
    //   · `llama_sampler_init_logit_bias` 的语义是"候选表里 id 相等就加偏置"，
    //     而候选表在 top_k/top_p/min_p 之后已经被砍小 —— 放在后面会让
    //     "被砍掉那个 token"的偏置完全不起作用（正偏置尤其明显：它本该把
    //     一个低分 token 拉回来，放在截断之后就再也拉不回来了）。
    // 且放在贪心分支**之前**：`temperature=0` 时贪心同样要受偏置影响。
    //
    // 词表外的 id 是**空操作**（候选表里没有它就不匹配），不越界、不崩 ——
    // 所以 Kotlin 侧只做格式校验，语义校验交给这里。仍要报一行数量，
    // 让"偏置挂上了几条"可查（挂成 0 条与"没给"在客户端看起来一样，但在
    // 日志里必须区分得开）。
    {
        const int n_bias = jbiasIds ? env->GetArrayLength(jbiasIds) : 0;
        const int n_vals = jbiasVals ? env->GetArrayLength(jbiasVals) : 0;
        // 两个平行数组长度不等 = 调用方契约破缺。**不**按较短的那个截断使用：
        // 那会把一个"Kotlin 侧接线写错"的 bug 静默成一个"偏置少了几条"的现象。
        // 报错并整条不挂 —— 宁可这一轮没有偏置，也不要一半。
        if (n_bias > 0 && n_bias == n_vals) {
            std::vector<jint> ids((size_t) n_bias);
            std::vector<jfloat> vals((size_t) n_vals);
            env->GetIntArrayRegion(jbiasIds, 0, n_bias, ids.data());
            env->GetFloatArrayRegion(jbiasVals, 0, n_vals, vals.data());
            std::vector<llama_logit_bias> lb;
            lb.reserve((size_t) n_bias);
            for (int i = 0; i < n_bias; i++) lb.push_back(llama_logit_bias{ (llama_token) ids[i], vals[i] });
            llama_sampler_chain_add(ch,
                llama_sampler_init_logit_bias(llama_vocab_n_tokens(S.vocab), n_bias, lb.data()));
            jlog("[bias] 已挂载 logit_bias 采样器：%d 条\n", n_bias);
        } else if (n_bias != n_vals) {
            jlog("[bias] logit_bias 的 id/bias 数组长度不一致（%d vs %d）-> 本轮不挂偏置\n", n_bias, n_vals);
        }
    }

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
    // stop 序列采样器（若给了）挂在**链尾**（选择器之后）。位置本身不参与判定
    // （判定在 accept 里，见上方说明），但放在链尾有两个好处：
    //   · 语义直观：它是"输出侧的过滤器"，排在所有"选哪些候选"的采样器之后；
    //   · 不会插进 temp/dist 之间影响既有的链路顺序（那条顺序有单测钉着）。
    std::vector<std::string> stops = jstring_array_to_vector(env, jstops);
    if (!stops.empty()) {
        auto * sc = new stop_match_ctx();
        sc->st.stops = stops;
        sc->st.withheld.reserve(32);
        // 预计算"词表里以某字节开头的 token piece 是否存在"。只在挂载时扫一次词表
        // （15 万级），生成期每步只是一次位图查表 —— 否则这段循环会成为新的生成瓶颈。
        const int n_vocab = llama_vocab_n_tokens(S.vocab);
        for (int t = 0; t < n_vocab; t++) {
            const std::string p = token_to_piece((llama_token) t);
            if (!p.empty()) sc->head_byte[(unsigned char) p[0]] = true;
        }
        sc->heads_ready = true;
        llama_sampler_chain_add(ch, llama_sampler_init(&stop_sampler_iface, sc));
        jlog("[stop] 已挂载 stop 采样器：%d 条", (int) stops.size());
    }
    S.smpl = ch;
    return JNI_TRUE;
}

// KV 账本作废。**所有**改动 KV / 换模型的路径都必须调它，否则下一轮会拿一份
// "以为是自己的"历史去复用（见 Session::kv_valid 的注释）。
// 不在这里做清理动作（KV 由调用方自己清），只作废判据 —— 职责单一，不易漏。
static void kv_invalidate(const char * why) {
    if (S.kv_valid) jp("[kv] 账本作废：%s\n", why ? why : "未说明");
    S.kv_valid = false;
    S.last_tokens.clear();
    S.last_reuse = 0;
    S.last_prefill_tokens = 0;
    S.last_lcp = 0;
    // 注意：**不动** S.kv_rounds —— 它是"跑过几轮"的单调计数，不是账本状态。
    // 作废账本(这份缓存不能用)与"这一轮没跑过"(计数器)是两件事；
    // 把计数一起清零会把 abort 后的 /health 变回"从未跑过"的样子（E-5 要修的就是它）。
}

// startCompletion(prompt, maxTokens):
//   tokenize(含 BOS) -> 算 KV 前缀复用计划 -> 删掉不匹配的尾巴 -> 只 prefill 尾巴。
//
// ══════════════════════════════════════════════════════════════════════════
// 这是一个**纯加速**改动：任何一步不成立都必须能干净地退化成"全清 + 全量 prefill"
//（即引入本功能之前的逐字节行为）。因此这里没有一处 `return JNI_FALSE` 是"因为缓存
// 出问题"——缓存相关的分支只会让 reuse 变成 0，绝不会让请求失败。
// ══════════════════════════════════════════════════════════════════════════
JNIEXPORT jboolean JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeStartCompletion(
        JNIEnv * env, jclass, jstring jprompt, jint maxTokens) {
    JNI_SPAN("startCompletion");
    if (!S.ctx || !S.smpl) { jp("<< startCompletion 无 ctx/smpl -> false\n"); return JNI_FALSE; }
    const char * p = env->GetStringUTFChars(jprompt, nullptr);
    if (!p) return JNI_FALSE;
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    // 本轮开始前的状态清理：清 pending / n_rem / gen_in_ledger，并清上一次的 abort 标志。
    //
    // ⚠ 这里**不**、也**不需要**复位采样器（stop 状态机）。原文写的是"采样器复位
    //   （stop 状态机等）"，而本文件从未调用过 `llama_sampler_reset` —— 那句话与实现
    //   分叉，是个谎言。真实的不变量是**另一条**：
    //     `S.smpl` 在每轮生成前都被 `nativeNewSampler` **整条重建**
    //     （先 `llama_sampler_free(S.smpl)`，再 `llama_sampler_chain_init`），
    //     而 `newSampler` 由调用方在持 `genLock` 时、`startCompletion` **之前**调，
    //     于是 `stop_match_ctx`（st / emitted / head_byte）每轮都是全新的。
    //   所以"上一轮的暂扣/命中状态泄漏到下一轮"这条路径**不成立**。
    //
    //   代价：`stop_sampler_reset`（iface 里的 .reset）因此**当前没有调用点**，
    //   是"接线正确但没人走"的一段代码。保留它是因为它是 `stop_match_ctx` 状态的
    //   唯一复位入口 —— 一旦将来改成"链复用/跨轮重入"，漏掉它就会让下一轮
    //   首批正文被整段跳过（emitted 账没清）。这一点由
    //   `tools/run_gen_state_guard.sh` 正面钉住（"链由 newSampler 重建"与
    //   "reset 没有调用点"必须**同时**成立，二者只剩一个就是真 bug）。
    //
    // 注意：**不**在这里清 KV、也**不**在这里作废账本 —— 复用与否由下面的计划决定。
    //
    /// 领取本轮的编号并清除上一轮的取消标志。
    /// 顺序要紧：先自增编号，再清 abort —— 反过来的话，一个"针对新编号"的取消
    /// 可能在清位之后才到，那就把新轮次的取消**吞掉了**（取消生效但看起来没生效）。
    /// 自增在前则相反：迟到的取消带的是**旧**编号，会被 nativeAbort 的归属判定挡掉，
    /// 这正是我们要的方向（宁可漏停一轮，不可错停一轮）。
    S.round_epoch.fetch_add(1);
    S.abort.store(false);
    S.pending.clear(); S.n_rem = (int) maxTokens;
    S.gen_in_ledger = 0;

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
    if ((int) tokens.size() >= S.n_ctx) {
        jlog("[diag] FAIL: %d tokens >= ctx %d", (int) tokens.size(), S.n_ctx);
        // prompt 过长时**先把上一轮的 KV 账本作废**：本轮注定失败，不能让它把
        // 一份还算能用的缓存留着"看起来有效"（见 kv_prefix.h 约束 ③）。
        kv_invalidate("prompt 超长，本轮注定失败");
        llama_memory_seq_rm(llama_get_memory(S.ctx), 0, -1, -1);
        S.n_used = 0;
        return JNI_FALSE;
    }

    // ── KV 前缀复用：算出"能留几个 / 要算几个" ───────────────────────────
    //
    // ⚠ 这一段是**纯加速**：它只决定"KV 里留下什么、还要算多少"，
    // 绝不允许返回失败。缓存相关的问题一律退化成"全清 + 全量 prefill"，
    // 否则一个本意为提速的功能会变成新的可用性风险
    // （tools/run_kv_cache_guard.sh 有一条断言专门钉这个性质）。
    std::vector<long long> cur(tokens.begin(), tokens.end());
    kvprefix::Plan plan = kvprefix::plan_reuse(S.last_tokens, cur, S.kv_valid, S.n_ctx);

    llama_memory_t mem = llama_get_memory(S.ctx);
    if (plan.hit()) {
        // 留下的那截 KV 就是 pos [0, reuse)；把这条序列上**更长**的部分整段清掉。
        // 用 seq_rm(0, reuse, -1) 而不是 seq_rm(0,-1,-1)+重建：前者对"上一轮更长"
        // 的情形会精确丢掉尾巴，对"上一轮更短"的情形什么都不删（本来就没有），
        // 两种情况都不需要分支判断。
        //
        // 为什么 `reuse == 0` 时不走这条：`seq_rm(0, 0, -1)` 语义上就是"全删"，
        // 但把它和"命中"混在一条码路上会让日志与判据难以核对。分支显式写开。
        llama_memory_seq_rm(mem, 0, (llama_pos) plan.reuse, -1);
        S.n_used = plan.reuse;
        jp("[kv] %s（上一轮账本 %zu tok，kv_valid=1）\n",
           kvprefix::describe(plan).c_str(), S.last_tokens.size());
    } else {
        llama_memory_seq_rm(mem, 0, -1, -1);
        S.n_used = 0;
        jp("[kv] %s（上一轮账本 %zu tok，kv_valid=%d）\n",
           kvprefix::describe(plan).c_str(), S.last_tokens.size(), (int) S.kv_valid);
    }

    // abort（取消）判定：放在缓存应用段**之后**、任何 decode 之前。
    // 它属于"取消"这条业务路径，不属于缓存 —— 所以它会（也应该）
    // `return JNI_FALSE`。守卫里那条"缓存问题绝不让请求失败"的断言
    // 只覆盖**复用计划的应用段**，到这一行为止；写成这样是为了让"缓存的失败面"
    // 与"业务的失败面"在源码上物理分开，而不是靠注释声明。
    // 已经算出的 KV 是既成事实（回滚不了），但绝不再多算一步。
    //
    // **不**把 S.n_used 清零：走到这里时它已经如实反映 KV 里的内容
    //（命中分支 = plan.reuse，未命中分支 = 0，那一条已经 `seq_rm` 全清）。
    // 清零会让 /health 的 ctx_used 在"KV 里确实有 reuse 个 token"时报 0 —— 读数撒谎。
    if (S.abort.load()) { kv_invalidate("本轮开始即被 abort"); return JNI_FALSE; }

    // ── prefill 剩余的尾巴（复用命中时就是"多出来的那一段"）───────────────
    // **不动** plan.reuse 这个下标以下的内容：那部分 KV 是上一轮就存在的。
    // 从 reuse 开始逐块 decode，位置由 llama.cpp 自己按已用长度推进 —— 这与
    // "全量 prefill"是同一条码路，只是起点不同，不存在第二套位置管理。
    const int n_batch = (int) llama_n_batch(S.ctx);
    const size_t start = (size_t) plan.reuse;
    for (size_t off = start; off < tokens.size(); off += (size_t) n_batch) {
        if (S.abort.load()) {
            // prefill 中途被打断：KV 里现在是一段**半截 prompt**，位置连续但语义不完整。
            // 它仍然是一段"正确的 token 前缀"，理论上可复用 —— 但那种复用会让下一轮
            // 把这段半截 prompt 当历史，收益极小、判断成本极高。一律作废，宁慢不错。
            //
            // 作废账本（= 不让下一轮复用这段半截 prompt）与"KV 里到底有几个 token"
            // 是两件事，这里的处理必须分开：
            //   · 前者：kv_invalidate，立即生效（下一轮必然全量 prefill，随后那条
            //     `seq_rm(0,-1,-1)` 会把半截 prompt 真正清掉）；
            //   · 后者：**保留** S.n_used 的实况值，不再写死 0。
            // 旧写法 `S.n_used = 0` 让 /health 的 ctx_used 在"KV 里明明有 12 个
            // token"时报 0，而它偏偏与 kv_cache_valid=false 同时出现 —— 读日志的人
            // 会以为"KV 已清、可以放心复用"，方向正好读反。
            jlog("[kv] prefill 被中断（abort），KV 账本作废；KV 里现有 %d tok（半截 prompt，下一轮全清）",
                 S.n_used);
            kv_invalidate("prefill 被中断");
            return JNI_FALSE;
        }
        int32_t n = (int32_t) std::min((size_t) n_batch, tokens.size() - off);
        if (llama_decode(S.ctx, llama_batch_get_one(tokens.data() + off, n)) != 0) {
            jlog("[diag] FAIL: prefill decode rc!=0 at offset %zu (n=%d)", off, n);
            kv_invalidate("prefill decode 失败");
            // 同上：不作废"KV 里有几个 token"这个读数，只作废账本。
            return JNI_FALSE;
        }
        S.n_used += n;
    }
    S.last_prefill_tokens = (int) (tokens.size() - start);
    S.last_reuse = plan.reuse;
    S.last_lcp = plan.lcp;
    // 这一轮真的跑起来了（prefill 完整成功）。/health 用它区分
    // 「从未跑过」与「跑过但账本已失效」——见 Session::kv_rounds 的注释。
    S.kv_rounds++;

    // ── 记账：这一轮的 KV 现在**完整地**包含这整段 token 序列 ─────────────
    // 写在 prefill 成功之后（失败路径都已 `return JNI_FALSE`，账本保持作废）。
    S.last_tokens.assign(tokens.begin(), tokens.end());
    S.kv_valid = true;

    // 推理真的跑起来了 —— 这一句之后才有"推理态"的 RSS 可量（见 rss_note_peak 注释）。
    rss_note_peak();
    jlog("[diag] prefill OK: %d tokens（新算 %d / 复用 %d）, first=%d last=%d",
         (int) tokens.size(), S.last_prefill_tokens, plan.reuse,
         tokens.front(), tokens.back());
    jp("[kv] 本轮账本已更新：%d tok，kv_valid=1；下一轮可复用的最长前缀就是它\n",
       (int) S.last_tokens.size());
    return JNI_TRUE;
}

// step() -> 完整 UTF-8 片段；""=继续但本步无字；null=结束（EOG/abort/超限/错误）
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeStep(JNIEnv * env, jclass) {
    if (!S.ctx || !S.smpl || S.abort.load()) return nullptr;
    if (S.n_used + 2 >= S.n_ctx || S.n_rem <= 0) return nullptr;

    // ══════════════════════════════════════════════════════════════════════════
    // `llama_sampler_sample` **内部已经调过 accept**（见 include/llama.h 的注释：
    // "Shorthand for: ... llama_sampler_apply(smpl, &cur_p); auto token = ...;
    // llama_sampler_accept(smpl, token); return token;"）。
    //
    // 所以这里**绝不能**再补一次 `llama_sampler_accept(S.smpl, tok)`：
    //   · 对无状态采样器（top_k / top_p / temp / dist）重复 accept 无害，
    //     所以这个 bug 在不带 stop 的请求上一直看不出来；
    //   · 对 **stop 采样器** 是致命的 —— 它是有状态的（st.generated 单调累积），
    //     每步被喂两遍同一个 token 文本，generated 就把该 token 记两遍，
    //     而 nativeStep 按 generated 的**增量**下发（stop_sampler_take_released），
    //     于是每个增量块都是「X + X」，客户端看到的就是正文整段重复
    //     （真机 2026-09-19 抓到：`你好你好！我！我...`，且只在带 stop 时出现）。
    //     注意状态机本身没错，错在"喂了两遍"；单测直接调 advance 也不会复现。
    llama_token tok = llama_sampler_sample(S.smpl, S.ctx, -1);
    // stop 序列命中：这个 token（及被暂扣的部分）不下发、不进 KV，本轮直接结束。
    // 放在 EOG 判定之前：stop 命中也必须收尾，且要早于 token_to_piece（避免把被截断的内容文本化）。
    if (stop_sampler_did_hit(S.smpl)) { jlog("[stop] 命中 stop 序列，结束本轮生成\n"); return nullptr; }
    // EOG **必须**在这一步之后立刻判掉，且要在 token_to_piece 之前 ——
    // 因为 token_to_piece 现在按 renderSpecial=true 走，会把 special token 文本化，
    // 而 <|im_end|> 这类结束标记正是 EOG。先判 EOG 就保证它不会漏进正文。
    if (llama_vocab_is_eog(S.vocab, tok)) return nullptr;

    // 把采样出的 token 喂回 KV
    llama_batch b = llama_batch_get_one(&tok, 1);
    if (llama_decode(S.ctx, b) != 0) {
        // 解码失败时 KV 里这一格是空的/半截的，而 S.last_tokens 里没有它 ——
        // 两边就此错位。作废账本，避免下一轮拿一段对不上的历史去复用。
        kv_invalidate("step 的 decode 失败");
        return nullptr;
    }
    S.n_used++; S.n_rem--;
    // KV 账本要跟着 KV 一起长：生成出来的 token 也是"KV 里已有内容"的一部分。
    // 不记这一笔，下一轮就只会复用 prompt 段、把上一轮的回复整段重算
    // —— 多轮对话里恰恰是"回复段"越来越长（README 说的长 prompt 痛点）。
    //
    // 账本仍有效（kv_valid）才追加：无效时追加会凭空造出一段从未进过 KV 的历史。
    if (S.kv_valid) { S.last_tokens.push_back((long long) tok); S.gen_in_ledger++; }

    // token_to_piece(tok) 内部走 renderSpecial=true：工具标记（<function/<param/…）
    // 是模型词表里的 special token，不文本化就会被丢掉，解析端再也对不上（见其注释）。
    //
    // stop 采样器的**暂扣**语义必须在这里兑现：被暂扣的 token 已经在 accept 里
    // 记进 st.withheld，它可能最终属于 stop（那就整段丢掉），也可能被放行
    // （前缀分叉 / 词表接不上）。因此判定"本步该下发什么"要以**状态机放行的文本**
    // 为准，而不是无条件下发 token_to_piece(tok)：
    //   · 此前无条件 `S.pending += token_to_piece(tok)`，暂扣期间的 token 照样
    //     被发了出去 —— stop 串的前缀泄漏进正文，等于"漏拦"；
    //   · 暂扣被放行时又只补当前这一个 token，最早被暂扣的那截**永远发不出去** ——
    //     正文少一截（"多拦"）。
    // 两处合并成一句：只把「相对上次已下发内容，状态机新放行出来的那部分」发出去。
    const std::string piece = token_to_piece(tok);
    // 有 stop 采样器时以**状态机放行的增量**为准（见上方说明）；
    // 没有 stop 采样器时（请求没带 stop）保持原样，无条件下发本 token。
    // 两条路径必须分开写：把"无采样器"也走增量路径会让 released 恒为空，
    // 于是**所有**普通对话的正文都不再下发 —— 这是最严重的一类回归。
    const bool has_stop_sampler = stop_sampler_present(S.smpl);
    if (has_stop_sampler) {
        S.pending += stop_sampler_take_released(S.smpl);
    } else {
        S.pending += piece;
    }
    std::string out = take_complete_utf8(S.pending);
    // take_complete_utf8 已保证不吐半截序列，仍走安全解码做双保险：
    // 模型输出里的孤立代理对 / 非法序列同样会让 CheckJNI abort（表现为对话中闪退）
    return new_string_utf8_safe(env, out.c_str());
}

// ══════════════════════════════════════════════════════════════════════════
// 渲染结果里「思考段」的形状判定（纯函数，宿主侧 run_thinking_tests 直接钉）
// ══════════════════════════════════════════════════════════════════════════
// 判据**必须与渲染发生的地方同源**。原先由 Kotlin 侧扫描 prompt 字符串猜，
// 而库的 generation_prompt（= 带生成后缀减不带的公共前缀之后的剩余部分）本身就
// 只覆盖「这一轮的生成后缀」这一小段，语义比"全文找标签"精确得多；而且模板细节
// 一变（空白、换行、顺序），宿主那份手写近似的判据就会静默失灵。
// 2026-09-19 真机报障（思考模式下客户端只收到很短的一段）就是它失灵的结果：
// MiniCPM5 在 enable_thinking=true 下吐 `<think>\n`（只有开标签、没有 `</think>`），
// 宿主扫描 `<think>` 之后找不到 `</think>` 就认定"思考段已经由 prompt 开好了"，
// 而模板**自带**的那段 <think> 只代表"模板自己吐了一个开标签"、
// 并不代表"闭合标签会在模型输出里出现"。于是模型输出里的 `</think>` 被状态机
// 当作「思考段内的裸闭合标签」静默剥离，整段思考留在 reasoning、
// 正文一律留在 content 之前的思考段里 → 客户端拿到的 content 远短于真实回答。
// 尾部形状的判据**本体在 `probe_util.h` 的 `classify_think_tail`** —— 本文件只调它。
//
// 为什么不在本文件里自己实现：这条判据原先有**三份**各自实现（本文件一份、
// `RenderedPrompt.openAtStartForTest` 一份、`ThinkingControl` 一份），三处都叫
// "256 尾窗口"，但本文件取 256 **字节**、另两处取 256 个 **UTF-16 code unit**
// （256 字节 ≈ 85 个汉字 → 覆盖范围差 3 倍）。用例全是 ASCII 后缀，两种窗口恰好
// 覆盖同一段，于是分叉了却全绿 —— 镜像给出的正是"判据没漂"的假信心。
//
// 放到 `probe_util.h` 的另一个理由：那里**宿主可编**。宿主测因此能直接调
// **真机编进去的那一份**，而不是另写一份近似来"证明两侧同档"。
using RenderedThinkShape = ThinkTailShape;
static constexpr ThinkTailShape kRenderedThinkShapeNone     = ThinkTailShape::kNone;
static constexpr ThinkTailShape kRenderedThinkShapeOpenOnly = ThinkTailShape::kOpenOnly;
static constexpr ThinkTailShape kRenderedThinkShapeClosed   = ThinkTailShape::kClosed;

static RenderedThinkShape classify_rendered_think_tail(const std::string & prompt) {
    return classify_think_tail(prompt);
}

// 渲染结果的**语义标注**，随 prompt 一起返回给宿主。
//
// 为什么不能只返回 prompt 字符串：上面那个判据必须由**渲染这一侧**给出，
// 宿主侧字符串近似已经出过一次真机故障（见 classify_rendered_think_tail 的注释）。
// 用一个字节作前缀携带它，Host 侧剥掉即得 prompt —— 不必为它另开 JNI 出口
// （另开出口就要再加一份签名、一次 jstring 往返，以及"两边取值不一致"的新窗口）。
static const char * const kRenderedPromptLeadIn  = "I";  // 调用方开启思考，且后缀自带未闭合的开标签
static const char * const kRenderedPromptLeadOut = "O";  // 后缀里没有待闭合的思考段（关思考 / 本来就没有）

// 渲染结果的**回传格式**（一个字符串同时携带三件事，见 RenderedPrompt.kt 的 parse）：
//
//     <I|O> <8 位十六进制生成后缀长度> <生成后缀原文> <prompt 原文>
//
// ⚠ 长度单位是 **UTF-16 code unit**，不是 UTF-8 字节。
// 这个字符串过 JNI 后是 Java `String`，而宿主侧按 `substring(start, end)` 切 ——
// `substring` 的索引是 UTF-16 code unit。两边单位不一致时，**纯 ASCII 后缀恰好相等**
// （22/30/41 字节那几种都是 ASCII），所以真机上不会有任何症状；一旦模板的生成后缀里
// 出现非 ASCII（非英文模板、全角标点、emoji），切点就错位：后缀被截短或 prompt
// 头部被当成后缀吃掉。这类"只在某些模型上才错"的静默故障正是本项目反复踩的那类，
// 所以单位按宿主侧的口径定，而不是按这里的自然口径。
// 唯一来源：`utf16_len()`（与 Java 的 `String.length()` 同义）。
//
// 为什么不另开一个 JNI 出口把"生成后缀"单独返回：那会多一次 jstring 往返，也就多一个
// "两次取值不一致"的窗口 —— 而本类存在的**全部理由**就是消灭这种不一致
// （见上面 classify_rendered_think_tail 的注释与 RenderedPrompt 文件头）。
//
// 为什么长度用**固定宽度十六进制**而不是分隔符：生成后缀里可能含任意字符
// （`<|im_start|>assistant\n` 本身就有换行），用分隔符就必须再定义一套转义，
// 而"再定义一套转义"是这一类协议最常见的 bug 来源。固定 8 位十六进制无歧义：
// 越界 / 非十六进制 -> 调用方按"这一版 native 没带后缀"处理（退回旧口径），不猜。
//
// 空后缀是**有意义**的：`add_generation_prompt=false` 时它确实是空的，
// 与"没带这个字段"（旧版 native / 解析失败）必须区分开 —— 判据是"首字符是不是 I/O
// 且紧随其后 8 位是十六进制"，而不是"长度是否为 0"。
static const size_t kRenderedPromptSuffixHexLen = 8;

// Java `String.length()` 口径：UTF-16 code unit 数。
//
// ══════════════════════════════════════════════════════════════════════════
// 它为什么必须与 `new_string_utf8_safe` 共用同一个解码器
// ══════════════════════════════════════════════════════════════════════════
// 这个数被写进回传格式的**长度段**，宿主据此 `substring(8, 8 + n)` 切出生成后缀。
// 所以它必须等于"**实际发出去那一串**"的长度 —— 而实际发出去那一串是
// `new_string_utf8_safe` 解码出来的。两者对同一份字节算出不同的 code unit 数，
// 切点就偏，prompt 头部会被当成后缀吃掉（或后缀被截短）。
//
// 上一版正是两个**各自实现**的计数器：这里遇到非法首字节就 `i += 1, n += 1`，
// 而 `new_string_utf8_safe` 会把 2 字节的 overlong `C0 80` 解成 **2 个** U+FFFD
// （每次只前进 1 字节、重新解码）。于是：
//     overlong `C0 80`     -> 这里 1，发出侧 2
//     CESU-8 `ED A0 80`    -> 这里 1，发出侧 3
// 20 万例**合法** UTF-8 随机串 fuzz 下两边 0 不一致（所以真机常见模型全无症状，
// 那几种后缀都是纯 ASCII），50 万例随机字节下 17.4 万不一致 —— 全在非法序列上。
// 触发路径是第三方 GGUF 模型自带的 chat 模板里含非法字节（模板原文直接进
// `cp.generation_prompt`），属于"只在某些模型上才错"的静默故障。
//
// 所以这里**不再自己数**：解码只由 `utf8_safe.h` 的 `utf8_decode_each` 一处实现，
// 计数与发出共用它（发出侧见 utf8_safe.h 的 `new_string_utf8_safe`，同一个函数）。
//
// 口径（与 Java `String.length()` 同义）：
//   · BMP 内字符（含汉字）记 1；
//   · 代理对（U+10000 以上，如 emoji）记 2，与 Java 一致；
//   · 非法 UTF-8 序列每前进 1 字节就记 1 个替换符 —— 这正是 `new_string_utf8_safe`
//     的行为（它也是每次只前进 1 字节、尽量恢复其后内容），两边因此逐字节一致。
static size_t utf16_len(const std::string & s);

static jstring new_rendered_prompt(JNIEnv * env, const std::string & prompt,
                                   const std::string & genSuffix) {
    const RenderedThinkShape shape = classify_rendered_think_tail(prompt);
    const bool openAtStart = (shape == kRenderedThinkShapeOpenOnly);
    jp("[think] 渲染结果形状=%s open_at_start=%d tail=%s gen_suffix=%s(%zuB)\n",
       shape == kRenderedThinkShapeOpenOnly ? "open-only" :
       (shape == kRenderedThinkShapeClosed ? "closed" : "none"),
       (int) openAtStart,
       escape_for_probe(prompt.substr(prompt.size() > 60 ? prompt.size() - 60 : 0), 80).c_str(),
       escape_for_probe(genSuffix, 60).c_str(), genSuffix.size());
    char hex[kRenderedPromptSuffixHexLen + 1];
    snprintf(hex, sizeof(hex), "%08zx", utf16_len(genSuffix));   // 单位见格式说明：UTF-16 code unit
    std::string out;
    out.reserve(1 + kRenderedPromptSuffixHexLen + genSuffix.size() + prompt.size());
    out += (openAtStart ? kRenderedPromptLeadIn : kRenderedPromptLeadOut);
    out.append(hex, kRenderedPromptSuffixHexLen);
    out += genSuffix;
    out += prompt;
    return new_string_utf8_safe(env, out.c_str());
}

// ══════════════════════════════════════════════════════════════════════════
// 共享的 UTF-8 -> UTF-16 解码器（长度段与"真正发出的那一串"的唯一口径）
// ══════════════════════════════════════════════════════════════════════════
// 上面 `utf16_len` 的前置声明在这里落地。它**不是**一份解码实现 —— 解码本体是
// `utf8_safe.h` 的 `utf8_decode_each`（本文件已经 include 它），与发出侧
// `new_string_utf8_safe` 用的是**同一个函数**。这里只是它的一个"只计数、不建串"
// 的调用点：`push` 换成累加，省掉一次 vector 分配（这条路径每次模板渲染都会走到）。
//
// 为什么不是"把 `new_string_utf8_safe` 拉过来数一遍长度"：那要额外分配一次 vector。
// 为什么不能"各写一份"：已经出过一次静默错位（见 `utf16_len` 上方说明）。
// 所以本文件**不得**再出现第二份解码循环 —— 守卫 `run_render_tail_guard.sh` 正面断言。
static size_t utf16_len(const std::string & s) {
    size_t n = 0;
    // 与 new_string_utf8_safe 的 push 逐字同构：< 0x10000 记 1、否则记 2。
    utf8_decode_each(s.data(), s.size(), [&n](uint32_t cp) { n += (cp < 0x10000u) ? 1 : 2; });
    return n;
}

// ══════════════════════════════════════════════════════════════════════════
// 结构化输出（OpenAI `response_format`）：JSON Schema -> GBNF -> grammar 采样器
// ══════════════════════════════════════════════════════════════════════════
// ⚠️ 一条必须先说清楚的**能力事实**（决定了本特性的形状，别再往错的方向查）：
//
// 我们**不能**自己把 JSON Schema 转成 GBNF。`json_schema_to_grammar` 这个符号虽然
// 在 librnllama*.so 的 .dynsym 里，但：
//   · 它的唯一重载是 `void(const nlohmann::ordered_json &, bool)` —— **只输出到
//     grammar-builder 回调，不返回字符串**，且依赖的 `common_grammar_options` 结构
//     并不在 vendor 提供给我们的裁剪头里（自己声明 = 赌 ABI 布局，正是 chat_abi.h
//     存在要防的那类事故）；
//   · 更重要的是：全库**没有任何一处调用它**（`bl` 目标逐一扫过，命中 0；也没有
//     任何 PLT/GOT 槽指向它）。它是这份预编译库里的死代码。
// 所以"自己转 GBNF"这条路在本 vendor 下**不成立**，硬走只会拿到一个永远不生效的
// 空 grammar（而它不报错 —— 正是本项目反复踩的"哑得不响"）。
//
// 库**真正支持**的结构化输出路径是内置模板引擎那一条：
//   `common_chat_templates_apply(tmpls, in)` 里给 `in.json_schema` 赋值，
//   返回的 `cp.grammar` 就是该 schema 对应的 GBNF（由库内部的模板/自动解析器生成）。
// 这条路已由工具调用链路验证过是可用的（同一套 templates_apply + PEG）。
//
// 因此本函数做两件事，**第二件才是关键**：
//   ① 把 `in.json_schema` 传给模板引擎（"告诉它要什么形状"）；
//   ② 拿 `cp.grammar`（GBNF 字符串）去 `llama_sampler_init_grammar` 建采样器。
// 只做 ①等于把 grammar 交给库又丢掉；只做 ②没有 GBNF 可编译。
//
// ══════════════════════════════════════════════════════════════════════════
// 三条不可退让的判据
// ══════════════════════════════════════════════════════════════════════════
// ① **默认路径逐字节不变**：`schemaJson == nullptr` 时本函数不产生任何副作用
//    （不加采样器、不改链序）。用同一个入口处理"没给 schema"是最容易引入回归的
//    写法（顺手加一个空 grammar 采样器就够改变输出分布），所以第一行就分流。
//
// ② **库的能力边界不是调用方的错**。模板引擎对不支持的 schema 会抛异常
//    （库里可见 "Unrecognized schema: " / "type must be array, but is " /
//    "failed to parse grammar" / "JSON schema conversion failed:" 等消息），
//    而异常穿过 JNI 帧是 UB -> SIGABRT。所以这一跳必须就地 catch，
//    并且**降级成无约束采样**而不是让请求失败：调用方拿到 200 + 自由文本
//    （与引入本特性前一致），而不是 500。"schema 写得不完美"不该等于"服务不可用"。
//
// ③ **grammar 采样器放在所有选择器之前**。它的作用是"把不合语法的候选 logit
//    置 -inf"，属于**约束**（与 penalties 同类），不是选择。放在 dist 之后等于不生效，
//    放在 temp 之后会先被温度改变分布再被砍。既有链序
//    penalties -> top_k -> top_p -> min_p -> temp -> dist 有单测钉着，
//    这里只**在 penalties 之前插入**，不动既有相对顺序。
//
// 空串的**语义**（Kotlin 侧 JsonSchemaFormat.schemaArg 保证）：表示 `json_object`，
// 即"要 JSON 约束但没有具体结构"。这里把它折成一个最小的对象 schema
// （`{"type":"object"}`）再交给模板引擎 —— 直接传空串会被当成"没给 schema"，
// 于是 `json_object` 这个类型会静默失效（输出仍是自由文本）。
//
// 返回值：true = 已挂上 grammar 采样器；false = 未挂（调用方按无约束继续）。
// **不抛异常、不失败请求** —— 见 ②。
//
// 职责拆成两半，不要合并：本函数只负责"把 GBNF 编译成采样器"，
// 而"schema -> GBNF"在 [gbnf_from_json_schema] 里单独做（那一跳要进库 templates_apply，
// 是历史上真机闪退的现场，单独放一处便于就地 catch 与留痕）。
// ══════════════════════════════════════════════════════════════════════════
// grammar 的「首字面量」-> grammar 采样器要预填的文本
// ══════════════════════════════════════════════════════════════════════════
// 从 PEG arena 现场取**根节点开头那个字面量**。为什么要"往下钻"而不是只看根：
// 库里的 grammar 根往往是一个**序列**（MiniCPM5 的 response_format 分支是
// `p.literal("<|im_start|>assistant\n") + reasoning + content(schema)`），
// 不是单个 literal —— 只看根节点会取不到，然后静默不预填（正是本类故障的形态）。
// 所以递归钻过 sequence / tag / atomic / rule / schema / ac 这些"透明包装"，
// 直到拿到第一个 literal 或确认取不到。取不到一律返回空串 = 不预填。
//
// 顺序取**第一个有内容的子节点**（epsilon 之类"什么都不匹配"的跳过）—— 与 GBNF
// 生成时 `common_peg_arena::build_grammar` 的序列拼接规则一致（它也会跳过空串子节点）。
static std::string peg_leading_literal(const common_peg_arena & arena,
                                       common_peg_parser_id id, int depth) {
    if (depth > 32) return "";                       // 防环（rule/ref 互引）
    if (arena.empty()) return "";
    const common_peg_parser_variant * node = nullptr;
    try {
        node = &arena.get(id);
    } catch (...) {
        return "";
    }
    if (!node) return "";
    return std::visit([&](const auto & p) -> std::string {
        using T = std::decay_t<decltype(p)>;
        if constexpr (std::is_same_v<T, common_peg_literal_parser>) {
            return p.literal;
        } else if constexpr (std::is_same_v<T, common_peg_sequence_parser>) {
            for (const auto & child : p.children) {
                const std::string lit = peg_leading_literal(arena, child, depth + 1);
                if (!lit.empty()) return lit;
            }
            return "";
        } else if constexpr (std::is_same_v<T, common_peg_rule_parser>) {
            return peg_leading_literal(arena, p.child, depth + 1);
        } else if constexpr (std::is_same_v<T, common_peg_tag_parser>) {
            return peg_leading_literal(arena, p.child, depth + 1);
        } else if constexpr (std::is_same_v<T, common_peg_atomic_parser>) {
            return peg_leading_literal(arena, p.child, depth + 1);
        } else if constexpr (std::is_same_v<T, common_peg_schema_parser>) {
            return peg_leading_literal(arena, p.child, depth + 1);
        } else if constexpr (std::is_same_v<T, common_peg_ac_parser>) {
            return peg_leading_literal(arena, p.child, depth + 1);
        } else if constexpr (std::is_same_v<T, common_peg_ref_parser>) {
            // ref 要解引用才能继续钻；arena.get_rule 在取不到时会抛，必须包住。
            try {
                return peg_leading_literal(arena, arena.get_rule(p.name), depth + 1);
            } catch (...) {
                return "";
            }
        } else if constexpr (std::is_same_v<T, common_peg_choice_parser>) {
            // 选择分支的"首字面量"只有在所有分支同前缀时才有意义；这里保守地取
            // 第一个分支的字面量 —— 与 GBNF 生成的口径一致（它按分支顺序拼 choice）。
            for (const auto & child : p.children) {
                const std::string lit = peg_leading_literal(arena, child, depth + 1);
                if (!lit.empty()) return lit;
            }
            return "";
        } else {
            return "";   // eps / 字符类 / until / gbnf 等：开头是字面量的可能性无法判定
        }
    }, *node);
}

// 把 templates_apply 产出的 PEG 解析成「grammar 首字面量」——**预填量由它决定**
// （见 gbnf_from_json_schema：prefill = 首字面量 ∩ 真后缀），同时落日志供判据。
// 它不可省：grammar 自己声明的"要跳过的前缀"就是这段字面量，出这一层就只剩 GBNF 文本，
// 再想反解等于自己写一个 GBNF 解析器（那是新的故障面）。
// 解析不了（旧 C++ ABI / 空串 / 畸形）一律返回空串（= 不预填，退化成老行为），原因写进 why：
//   · 没取到字面量       -> PEG 形状变了 / ABI 对不上，该查库；
//   · 字面量比真后缀短   -> 只喂字面量会**短喂**（约束落点偏一格，不崩）；
//   · 两者对不上（分叉） -> 该查模板 / 思考开关 / add_generation_prompt 的透传。
static std::string schema_peg_leading_literal(const std::string & parserSerialized,
                                              std::string & why) {
    if (parserSerialized.empty()) { why = "cp.parser 为空（该模板不产 PEG）"; return ""; }
    common_peg_arena arena;
    try {
        arena.load(parserSerialized);
    } catch (const std::exception & e) {
        why = std::string("PEG 反序列化抛异常: ") + e.what();
        return "";
    } catch (...) {
        why = "PEG 反序列化抛未知异常";
        return "";
    }
    if (arena.empty()) { why = "PEG arena 为空"; return ""; }
    const std::string lit = peg_leading_literal(arena, arena.root(), 0);
    if (lit.empty()) why = "根首字面量为空串（开头不是字面量）";
    return lit;
}

static bool attach_grammar_sampler(llama_sampler * chain, const char * gbnf, const char * prefill,
                                    const char * genSuffixForMismatch, const char * pegLiteral) {
    if (!chain || !gbnf || !*gbnf) return false;
    if (!S.vocab) return false;

    // ══════════════════════════════════════════════════════════════════════
    // 先把根节点字面量对齐到**真后缀**（第十一处成因，0.9.97 修）
    // ══════════════════════════════════════════════════════════════════════
    // 库按 `reasoning_format=NONE`（宿主从未赋值）建 PEG 时，**没有**把模板
    // 写死在 prompt 里的思考块算进产生式：真机那两份产物因此对"续写位置"理解不同
    //     cp.generation_prompt = 41B  `<|im_start|>assistant\n<think>\n\n</think>\n\n`
    //     cp.grammar 根字面量  = 22B  `<|im_start|>assistant\n`
    // 于是那 19B 是 grammar 产生式里**不存在**的字节 —— `0.9.96` 想"喂给它"
    // 在构造上就不可能成立（真机：`错位段未被 grammar 接受（19B）`，第一个字节
    // `<` 就被判非法）。修法：**在 GBNF 文本上把根节点那个字面量换成真后缀**，
    // 让 grammar 自己声明"我从真后缀之后开始"。见 gbnf_realign_root_literal。
    const std::string realSuffix = genSuffixForMismatch ? std::string(genSuffixForMismatch) : std::string();
    const std::string pegLit = pegLiteral ? std::string(pegLiteral) : std::string();
    const std::string gbnfEffective = gbnf_realign_root_literal(gbnf, pegLit, realSuffix);
    // 对齐生效时，预填也要跟着喂满**真后缀**（那些字节现在是 grammar 自己声明的
    // 字面量，不是我们硬塞的）—— 否则 grammar 声明了 41B、我们只喂 22B，
    // 落点又差回那 19B。这正是 0.9.96 那张"喂 22B、期望 41B"错配的来源。
    bool realigned = (gbnfEffective != gbnf);
    // 对齐必须**能被完整喂进去**才算数：grammar 的字面量现在是真后缀，而预填要走
    // tokenizer。若真后缀的末尾落在 token 中间（末位 piece 跨界），预填那一步的
    // "只减不增"收敛会把末尾几个字节丢掉 -> grammar 声明 41B、我们只喂 <41B，
    // 落点又错开，且这次错在 grammar **已经声明**的范围内（更难查）。
    // 这类输入一律**放弃对齐**（退回老行为），不做"半对齐"——与 0.9.96 "不作半推"同一底线。
    if (realigned) {
        int need = llama_tokenize(S.vocab, realSuffix.c_str(), (int32_t) realSuffix.size(),
                                  nullptr, 0, false, true);
        if (need < 0) need = -need;
        std::vector<llama_token> toks((size_t) need);
        bool exact = false;
        if (need > 0 &&
            llama_tokenize(S.vocab, realSuffix.c_str(), (int32_t) realSuffix.size(),
                           toks.data(), (int32_t) toks.size(), false, true) >= 0) {
            size_t bytes = 0;
            for (size_t i = 0; i < toks.size(); i++) {
                const std::string piece = token_to_piece(toks[i]);
                // 首个 piece 若被 tokenizer 补了前导空格（而原文没有），预填那一跳会
                // 跳过它 —— 对齐时也要按同一口径算，否则这里判"对齐"、那边却少喂。
                if (i == 0 && !piece.empty() && !realSuffix.empty() &&
                    std::isspace((unsigned char) piece[0]) &&
                    !std::isspace((unsigned char) realSuffix[0])) {
                    continue;
                }
                bytes += piece.size();
            }
            exact = (bytes == realSuffix.size());
        }
        if (!exact) {
            realigned = false;
            jp("[schema] 根字面量对齐放弃：真后缀 %zuB 不是整 token 边界（不作半对齐）\n",
               realSuffix.size());
            jlog("[schema] grammar 根字面量未对齐（真后缀非整 token 边界）");
        }
    }
    std::string prefillEff = prefill ? std::string(prefill) : std::string();
    // 最终交给 grammar 编译器的 GBNF：对齐**成立**时用改写后的，否则用原件。
    // 两者必须与 prefillEff 同步（对齐成立 <-> 喂真后缀），否则就是"声明 41B、喂 22B"
    // 那种错配（0.9.96 的形态）。
    const std::string gbnfFinal = realigned ? gbnfEffective : std::string(gbnf);
    if (realigned) prefillEff = realSuffix;

    llama_sampler * gs = nullptr;
    try {
        // 根符号：模板引擎产出的 GBNF 统一以 `root` 为入口（库里可见
        // "JSON missing 'root' field" 这条校验消息 —— 正是产物必须自带 root 的证据）。
        gs = llama_sampler_init_grammar(S.vocab, gbnfFinal.c_str(), "root");
    } catch (const std::exception & e) {
        jp("[schema] llama_sampler_init_grammar 抛异常: %s -> 降级为无约束\n", e.what());
        jlog("[schema] GBNF 无法编译（%s），已降级为无约束采样", e.what());
        return false;
    } catch (...) {
        jp("[schema] llama_sampler_init_grammar 未知异常 -> 降级为无约束\n");
        jlog("[schema] GBNF 编译未知异常，已降级为无约束采样");
        return false;
    }
    if (!gs) {
        // llama.h 的口径：空 grammar 返回空 grammar 采样器，解析失败返回 NULL。
        // 两者在这里都是"没约束上"，必须留痕 —— 否则调用方只会看到"我给了 schema
        // 但输出还是自由文本"，而服务端一声不响。
        jp("[schema] grammar 采样器为 NULL（GBNF 解析失败）-> 降级为无约束\n");
        jlog("[schema] GBNF 解析失败，已降级为无约束采样");
        return false;
    }
    // ══════════════════════════════════════════════════════════════════════
    // 预填：把"已经在 prompt 里的生成前缀"喂给 grammar，让它越过那段字面量
    // ══════════════════════════════════════════════════════════════════════
    // 不预填时 grammar 从**根节点**起步，要求第一个生成 token 就是
    // `<|im_start|>`（MiniCPM5 硬编码的根字面量）—— 而那段已经在 prompt 里了，
    // 模型被迫把它再吐一遍，JSON 才轮到。（真机表现：content 前面多一段生成前缀、
    // 数组只剩 `[ ]`。）这是本类故障的**第五处**成因，也是前四轮一直没修净的那处：
    // 前四轮修的都是"传给库的生成后缀对不对"，而这一处是"库推出来的 grammar
    // 需要被喂"。上游的用法见 llama.cpp common/sampling.cpp：
    //     for (const auto & token : prefill_tokens) llama_sampler_accept(grmr, token);
    //
    // 预填不进链、只在这里做一次（accept 是对采样器**状态**的推进，不是链上的一环）。
    // 预填失败（tokenize 异常 / accept 越界）**不得**让请求失败：grammar 退回
    // "不预填"就是引入本修复之前的行为，也就是老症状；让它失败则等于把能用的请求弄挂。
    int n_prefill = 0;
    size_t fed_prefill_bytes = 0;   // 实际喂进 grammar 的查询字节数（判据用，见下方日志）
    // 对齐生效时喂**真后缀**（见上面的 gbnf_realign_root_literal）：此时 grammar
    // 根字面量已经是 41B，喂 41B 才与它声明的落点一致。未生效时沿用原预填量。
    if (!prefillEff.empty()) {
        try {
            const std::string pf = prefillEff;
            // ══════════════════════════════════════════════════════════════
            // 预填的**字节账**（第十二处成因，0.9.99 修）
            // ══════════════════════════════════════════════════════════════
            // grammar 是**字节级**状态机，而这一跳走的是 **token**（`llama_sampler_accept`）。
            // token 是原子的：一个 piece 里可能同时含"grammar 声明的字面量"与
            // "字面量之后、模型还没开始写"的字节 —— 喂进去就**多于**声明量，
            // grammar 被推过它自己声明的落点。
            //
            // 推过界**不抛异常**（上游 accept_chr 只是把匹配不上的栈丢掉），代价是
            // 语法被悄悄写坏：grammar 站在"JSON 已经写完"的地方，此后 `{` / `[`
            // 全都匹配不上 -> 全体候选置 -inf。真机 `0.9.98` 的读数正是它：
            //     gbnf=939B（= 916 + 23，根字面量确实从 22B 改成了 41B）
            //     根字面量对齐：peg_lit=22B -> 真后缀=41B（已对齐）
            //     fed=41B/41B  <- 这是**算出来**的，不是"真喂进去多少"
            // 三条一起看 = "改写生效了、账也报了，但实喂量与声明量的关系从没被核对过"。
            //
            // 所以这一跳增加一条**硬判据**：实际喂进去的字节**不得跨过 grammar 声明的
            // 字面量末尾**（跨界的那个 piece 整个不喂，与既有的"预填收敛"同一底线，
            // 只是收敛线从"预填文本长度"换成"grammar 声明的落点"——后者才是 grammar
            // 真正认识的那条线）。声明量与实际喂量都由 plan_grammar_prefill_bytes 算，
            // 真机与单测共用同一份实现。
            const prefill_byte_plan plan = plan_grammar_prefill_bytes(
                realigned ? realSuffix : pegLit, pf);
            if (!plan.usable) {
                jp("[schema] 预填字节账：%s（声明 %zuB / 预填 %zuB）-> 不预填\n",
                   plan.why.c_str(), plan.declaredBytes, plan.prefillBytes);
                jlog("[schema] grammar 预填跳过（%s）", plan.why.c_str());
            }
            int need = llama_tokenize(S.vocab, pf.c_str(), (int32_t) pf.size(), nullptr, 0, false, true);
            if (need < 0) need = -need;
            std::vector<llama_token> toks((size_t) need);
            if (plan.usable && need > 0 &&
                llama_tokenize(S.vocab, pf.c_str(), (int32_t) pf.size(),
                               toks.data(), (int32_t) toks.size(), false, true) >= 0) {
                // 第一个 piece 若被 tokenizer 补了前导空格（而原文没有），要去掉它 ——
                // 与 llama.cpp common/sampling.cpp 的 prefill 口径逐字一致，否则会把
                // 一个不属于 grammar 的空格喂进去。
                //
                // ══════════════════════════════════════════════════════════════
                // 逐 piece 喂之前，先把整段收敛到「预填文本自身长度」以内
                // ══════════════════════════════════════════════════════════════
                // token 是**原子**的，grammar 的位置是**字节级**的：一个 token 若跨过
                // 预填结尾（前一半在预填内、后一半在外），整段该 token 文本都会被 accept。
                // 上游 llama-grammar.cpp 的空栈断言（LM_GGML_ASSERT(!stacks.empty())）
                // 是 lm_ggml_abort —— 会杀掉**整个进程**（真机栈顶就是
                // `llama_grammar_apply_impl` ← `llama_sampler_sample` ← `nativeStep`）。
                // 收敛规则（保守，只减不增）：从最后一个 piece 往前丢，直到
                // 「已收下的字节数 + 该 piece 的字节数 <= 预填字节数」。
                //
                // ⚠ 这一条**不是** 0.9.90/0.9.91 真机 abort 的成因（那次的切点是整除的，
                //   收敛一次都没触发；真因见 gbnf_from_json_schema 里的 prefill 上限）。
                //   留着它是防另一类输入：预填文本一旦不是 token 边界的整数倍，
                //   跨界 token 同样能把 grammar 推成空栈。两条各管一类，别互相替代。
                // ⚠ 判据必须贴住**预填字节数**（pf.size()）：用 toks.size() 或 piece 个数
                //   在含 CJK / 多字节 piece 的模板上会整体错位，而纯 ASCII 用例毫无症状。
                // 收敛线 = **grammar 声明的落点**（plan.declaredBytes），不是预填文本长度。
                // 两者在这条修复之前总是相等（未对齐时 declared 取 pegLit、pf 也是按它算的），
                // 所以旧口径在这条路上不会少喂一个字节 —— 但对齐生效之后 declared 是
                // 真后缀（41B）、pf 也是同一份，仍相等。真正防的是**另一类输入**：
                // tokenizer 把"声明量之后"的字节并进最后一个 piece（真机 Qwen3 上
                // `<think>` / `</think>` 都是特殊 token，很容易与相邻字节粘在一个 piece 里）。
                // 那个 piece 整喂会让 grammar 越过它自己声明的落点 -> 语法被写坏。
                const size_t boundary = plan.usable ? plan.declaredBytes : pf.size();
                size_t i_first = toks.size();
                {
                    size_t bytes = 0;
                    for (size_t i = 0; i < toks.size(); i++) {
                        const std::string piece = token_to_piece(toks[i]);
                        const size_t next = bytes + piece.size();
                        if (next > boundary) break;   // 这个 piece 跨过喂入口径的末尾 -> 不喂
                        bytes = next;
                        i_first = i + 1;
                    }
                    if (i_first < toks.size()) {
                        jp("[schema] ⚠ 预填收敛：%d 个 token 中只喂前 %d 个"
                           "（第 %d 个跨过预填结尾 %zuB，整喂会让 grammar 空栈 abort）\n",
                           (int) toks.size(), (int) i_first, (int) i_first + 1, boundary);
                        jlog("[schema] grammar 预填收敛：只喂前 %d/%d 个 token（末位跨界）",
                             (int) i_first, (int) toks.size());
                    }
                }
                for (size_t i = 0; i < i_first; i++) {
                    const std::string piece = token_to_piece(toks[i]);
                    if (i == 0 && !piece.empty() && !pf.empty() &&
                        std::isspace((unsigned char) piece[0]) && !std::isspace((unsigned char) pf[0])) {
                        continue;
                    }
                    llama_sampler_accept(gs, toks[i]);
                    n_prefill++;
                    fed_prefill_bytes += piece.size();
                }
            }

            // ══════════════════════════════════════════════════════════════
            // 错位段：预填越过了首字面量之后，真后缀**还剩一段没有咽下去**
            // ══════════════════════════════════════════════════════════════
            // 这是第十处成因（0.9.96 修），也是九轮"判据全绿、症状一次没变"的收口处：
            // 预填把 grammar 推过 22B 的首字面量之后，grammar 就**站在 JSON 的起点**了
            // （它在等 `{` / `[`），而模型实际要写的下一个 token 是模板写死的
            // `<think>\n\n</think>\n\n` 那 19B。这个 token 会被 grammar 判成非法、
            // logit 置 -inf —— 模型于是要么报 EOG 提前收尾、要么把那段思考块硬吐出来
            // （它正好是 `space` 规则能匹配的东西，会被当成"JSON 之前的空白"吃掉），
            // 随后再补一个**收尾用的生成前缀复述**，最后才轮到 JSON。
            //
            // 修法：把错位段也喂给 grammar。**逐字节试探、全成功才留下** ——
            // grammar 不认就整段回滚（见 advance_grammar_past_mismatch 的判据），
            // 所以它只可能让落点更准，不可能喂进 grammar 不认的字节。
            //
            // ⚠ 与 0.9.90 那次 SIGABRT 的区别（别把这条也当成"补喂"）：那次是把 41B
            //   交给 tokenizer 切、piece 整段 accept，末位跨界把 JSON 的头几个字节也
            //   喂了进去 -> 空栈 abort。这里**不切 token**（位置本来就是字节级的），
            //   而且每个字节都要 grammar 自己点头才算数。
            size_t advanced_bytes = 0;
            // ⚠ 根字面量对齐生效时这一段**不跑**：错位段已经被写进 grammar 的根字面量里
            //   （prefillEff = 真后缀），grammar 与真后缀此时逐字节对齐、没有错位段可咽。
            //   仍按老口径去"喂错位段"反而是拿一段 grammar 不认的字节去试（0.9.96 的形态）。
            if (!realigned && prefill && *prefill && genSuffixForMismatch) {
                const std::string pf = prefill;
                const grammar_fit_result fitForAdvance = grammar_fit_check(pf, realSuffix);
                const prefill_advance_result adv = advance_grammar_past_mismatch(
                    pf.size(), realSuffix, fitForAdvance.mismatch,
                    // step：grammar 认这一个字节吗？
                    //
                    // 诚实的口径（真机实测，别把它读成"clone 能试"）：`llama_sampler_clone`
                    // 会**带着内部状态一起复制** grammar 采样器，而库内 grammar 状态是
                    // 共享指针的栈 —— 所以对克隆体 accept 失败时，原件的栈**也可能已被
                    // 动过一半**。所以下面"失败就整段回滚"用的是**丢弃这个克隆体**，
                    // 而不是"把原件退回标记"：原件只在**全成功**时才被推进（逐字节
                    // 重放一遍 accepted），于是"不认"这条路上原件一个字节都没动过。
                    [&](char c) -> bool {
                        llama_sampler * trial = nullptr;
                        try {
                            trial = llama_sampler_clone(gs);
                        } catch (...) { return false; }
                        if (!trial) return false;
                        // 克隆体自带原件此刻的全部状态，所以只喂**这一个字节**。
                        bool ok = false;
                        try {
                            const std::string one(1, c);
                            int need = llama_tokenize(S.vocab, one.c_str(), 1, nullptr, 0, false, true);
                            if (need < 0) need = -need;
                            if (need == 1) {
                                llama_token t = LLAMA_TOKEN_NULL;
                                if (llama_tokenize(S.vocab, one.c_str(), 1, &t, 1, false, true) >= 0) {
                                    llama_sampler_accept(trial, t);
                                    ok = true;
                                }
                            } else {
                                // tokenizer 不认这一字节（例如它只是一个多字节序列的前半）
                                // -> 不试。**不猜**：猜就是把 grammar 交给我们自己的推测。
                                ok = false;
                            }
                        } catch (...) { ok = false; }
                        llama_sampler_free(trial);
                        return ok;
                    },
                    // rollback：**不碰原件**。原件此刻仍是"只预填过首字面量"的状态，
                    // 而不认的那条路本来就要退回这个状态。
                    []() {});
                // 全成功后**逐字节重放**到原件上（此时才真的动 gs）。
                // 不认的那条路 adv.accepted 为空 -> 这个循环一次都不进 -> 原件零改动。
                if (!adv.accepted.empty()) {
                    for (char c : adv.accepted) {
                        const std::string one(1, c);
                        int need = llama_tokenize(S.vocab, one.c_str(), 1, nullptr, 0, false, true);
                        if (need < 0) need = -need;
                        llama_token t = LLAMA_TOKEN_NULL;
                        if (need == 1 &&
                            llama_tokenize(S.vocab, one.c_str(), 1, &t, 1, false, true) >= 0) {
                            llama_sampler_accept(gs, t);
                            n_prefill++;
                            fed_prefill_bytes += 1;
                            advanced_bytes += 1;
                        } else {
                            // 重放途中 tokenizer 突然不认了（极罕见）—— 宁可在这里停下
                            // 并留痕，也不要喂进一个我们没验证过的 token。
                            jlog("[schema] 错位段重放中断（第 %zu 字节 tokenizer 不认），落点停在 %zuB",
                                 advanced_bytes + 1, pf.size() + advanced_bytes);
                            break;
                        }
                    }
                }
                if (advanced_bytes > 0) {
                    jp("[schema] 错位段已咽下：prefill %zuB -> %zuB（另 accept %zuB = %s；"
                       "grammar 自己点头的字节，不是按 token 硬喂）\n",
                       pf.size(), pf.size() + advanced_bytes, advanced_bytes,
                       escape_for_probe(adv.accepted, 60).c_str());
                    jlog("[schema] grammar 落点推进：%zuB -> %zuB（错位段 %zuB 已让 grammar 咽下）",
                         pf.size(), pf.size() + advanced_bytes, advanced_bytes);
                } else if (!fitForAdvance.mismatch.empty()) {
                    // 不认 -> 一个字节没动。必须留痕：这一轮的症状会与老版本**逐字相同**，
                    // 没有这行就分不出"没试过"与"试了、grammar 不认"。
                    jp("[schema] 错位段未被 grammar 接受（%zuB），落点保持 %zuB"
                       "（退回旧行为，不硬喂）\n",
                       fitForAdvance.mismatch.size(), pf.size());
                    jlog("[schema] 错位段 %zuB 未被 grammar 接受，落点保持 %zuB",
                         fitForAdvance.mismatch.size(), pf.size());
                }
            }
        } catch (const std::exception & e) {
            // 预填中途抛异常 = grammar 的状态机已经被**打破**（例如空栈）。
            // 此时**绝不能**把这个采样器挂进链：它下一次被 sample 就会在
            // llama_grammar_apply_impl 的空栈断言上 lm_ggml_abort，直接杀进程
            // ——这正是 0.9.90 真机"闪退"的形态（进程没了，连错误码都没有）。
            // 所以这里就地 free + 返回 false，整条链退化成无约束（老行为），
            // 请求继续、文本仍可读；只是这一轮没有结构化约束。
            jp("[schema] ⚠ grammar 预填失败（%s）-> 丢弃 grammar 采样器（否则采样时空栈 abort）\n", e.what());
            jlog("[schema] grammar 预填失败（%s），已丢弃 grammar（退回无约束采样）", e.what());
            llama_sampler_free(gs);
            return false;
        } catch (...) {
            jp("[schema] ⚠ grammar 预填未知异常 -> 丢弃 grammar 采样器\n");
            jlog("[schema] grammar 预填未知异常，已丢弃 grammar（退回无约束采样）");
            llama_sampler_free(gs);
            return false;
        }
    }
    // 插到链**最前**（penalties 之前），见上面判据 ③。grammar 采样器由链持有，
    // 链释放时会一并 free（llama_sampler_chain_add 的所有权约定），这里不再管。
    llama_sampler_chain_add(chain, gs);
    // 判据口径：除"计算出的预填量"外，再报**实际喂进去的字节数**。
    // 真机那次 abort 的日志里 `prefill=41B` 与 `gen_prompt=41B` "看着相等"、
    // 读成"对齐了、没问题"，而崩溃恰恰发生在 accept 那一跳 —— 因为 41B 是**算出来的**，
    // 真正喂进去的可能是被 token 边界收敛过的另一段。两个数字必须分开报，
    // 否则下一次还会读成"没问题"。走到这一行 = 预填没有抛异常 = 采样器状态是干净的。
    // ⚠ `fed=` 是**实际喂进去的**字节数（逐 piece 累加），`/` 后面是**打算喂的**字节数。
    // 旧版把分子写成 `prefillEff.size()` —— 那是"算出来的"，与"真喂进去多少"无关，
    // 于是 `fed=41B/41B` 在"只喂了 22B"时**照样是 41B/41B**，读数把故障读成了正常
    // （0.9.98 真机就是被这一行带偏的）。分子必须来自累加器。
    jp("[schema] 已挂载 grammar 采样器：gbnf=%zuB root=root prefill=%d tok(%s)"
       " fed=%zuB/%zuB\n",
       gbnfFinal.size(), n_prefill, !prefillEff.empty() ? "已预填" : "无",
       fed_prefill_bytes, prefillEff.size());
    // 账不平 = 实喂量与声明量不一致 —— 这是本处（第十二处成因）的独有签名。
    // `0.9.98` 真机 `fed=` 那一行读不到这一点，它报的是"打算喂多少"。
    if (realigned) {
        if (fed_prefill_bytes == realSuffix.size()) {
            jp("[schema] 预填字节账：账平（实喂 %zuB == grammar 声明的根字面量 %zuB）\n",
               fed_prefill_bytes, realSuffix.size());
            jlog("[schema] grammar 预填字节账：账平 %zuB == %zuB",
                 fed_prefill_bytes, realSuffix.size());
        } else {
            jp("[schema] ⚠ 预填字节账：账不平（实喂 %zuB != grammar 声明的根字面量 %zuB）"
               "—— grammar 的落点与模型续写的位置错开 %zuB，症状与未对齐逐字相同\n",
               fed_prefill_bytes, realSuffix.size(),
               realSuffix.size() > fed_prefill_bytes ? realSuffix.size() - fed_prefill_bytes
                                                     : fed_prefill_bytes - realSuffix.size());
            jlog("[schema] ⚠ grammar 预填字节账不平：%zuB != %zuB",
                 fed_prefill_bytes, realSuffix.size());
        }
    }
    // 根字面量对齐的读数（第十一处成因）：`对` 时预填量 = 真后缀长度（41B），
    // 且 fed 应当等于它（grammar 声明的字面量 = 我们喂的那段，逐字节一致）。
    if (realigned || !realSuffix.empty()) {
        jp("[schema] 根字面量对齐：peg_lit=%zuB -> 真后缀=%zuB（%s；%s）\n",
           pegLit.size(), realSuffix.size(), realigned ? "已对齐" : "未对齐",
           realigned ? "grammar 自己声明从真后缀之后开始" : "真后缀与字面量不符，保持原 grammar");
        jlog("[schema] grammar 根字面量 %zuB -> %zuB（%s）",
             pegLit.size(), realSuffix.size(), realigned ? "已对齐到真后缀" : "未对齐，保持原样");
    }
    // 落点判据：`prefill=` 是**算出来**要越过的长度，`fed=` 是实际喂进去的字节。
    // 错位段咽下之后 fed 会**大于** prefill —— 这不是异常，正是本处修复生效的读数
    // （grammar 被推到了真后缀的末尾，而不是停在首字面量处）。
    // ⚠ 报的是 **gbnfFinal.size()**（真正交给 grammar 编译器的那份），不是原件 `gbnf`。
    // 真机 0.9.98 的日志里同一轮出现两个数（`gbnf=939B` 与 `GBNF 916B`），
    // 差 23B 就是根字面量对齐的那一下 —— 而 `[mark]` 报的 916B 是**对齐之前**的原件，
    // 看日志的人会以为"对齐没生效"（这正是 0.9.98 那轮把 `[mark]` 与 `[schema]` 并排看时
    // 最容易误判的地方）。原件与改写件必须报同一个数，否则读数自己就在撒谎。
    // （%zu 要的是长度，而 gbnfFinal 是 std::string —— 取长度只能用 .size()，见 0.9.98 的编译错。）
    jlog("[schema] 已挂载 grammar 采样器（GBNF %zuB，预填 %d tok / %zuB）",
         gbnfFinal.size(), n_prefill, fed_prefill_bytes);
    return true;
}

// 把 response_format 里的 schema 交给模板引擎，取出它生成的 GBNF；失败返回空串。
//
// 与渲染路径**共用同一次** templates_apply 是做不到的（渲染发生在 newSampler 之前、
// 且两者生命周期不同），所以这里单独 apply 一次 —— 但要注意它的代价与风险：
//   · 这一跳会进库（历史上真机闪退的现场就在 templates_apply），所以必须就地 catch；
//   · 拿到的 GBNF 只用于**约束采样**，与渲染出的 prompt 是两份独立产物，
//     所以 messages 不必与渲染时相同（schema 约束与对话内容无关）。
//
// ══════════════════════════════════════════════════════════════════════════
// 但**两件东西必须与渲染侧逐字对齐**，否则约束会落在错误的位置上
// ══════════════════════════════════════════════════════════════════════════
// ① **模板**：渲染用的是 App 取到的运行时模板（`llama_model_chat_template`），
//    这里若传空串让库自选，库内用哪份模板由 `minja::resolve_template` 决定 ——
//    与运行时那份**可能不是同一份**（而且它还带 `json_schema` 的自动改写分支）。
//    分叉的后果是"GBNF 按另一个模板算"或"压根产不出 GBNF"，两者都不报错。
//    所以模板由调用方透传（`tmplOverride`），与渲染侧同源。
//
// ② **生成后缀**：库把生成后缀当作"**已经咽下去的**"前缀，于是只对"前缀之后的部分"
//    施加约束（JSON 在生成后缀**之后**才开始）。用一个常量（如 `true`）去近似真实的
//    `add_generation_prompt`，就可能在模板带思考块时分叉 —— 而模型的实际续写发生在
//    **真实 prompt 的**生成后缀之后，它对不上就会自己把那个尾巴补一遍再满足 grammar。
//    真机（MiniCPM5-2B-Q4_K_M + json_schema）两例正是同一内核：
//        content = ":assistant\n{...}"         ← 头部被截（客户端丢掉 <|im_start| 一段）
//        content = "<|im_start|>assistant\n[ ]" ← 模型自己吐了生成后缀，然后只能给个空数组
//    所以后缀也由调用方透传（`genPrompt`）：null = 无 / 未知 -> 退回旧口径 `true`，
//    空串 = 调用方明确说"这一轮没有生成后缀"（渲染侧 add_generation_prompt=false）。
static schema_grammar_result gbnf_from_json_schema(const char * schemaJson, const char * tmplOverride,
                                                   const char * genPrompt, bool thinkingOn) {
    schema_grammar_result out;
    if (!schemaJson) return out;
    if (!S.model) return out;
    // 空串 = `json_object`（见上方说明）：折成最小对象 schema。
    const std::string schema = (*schemaJson == '\0') ? std::string("{\"type\":\"object\"}") : std::string(schemaJson);
    const std::string tmpl = tmplOverride ? tmplOverride : "";

    // 模板与渲染侧同源（见上面判据 ①）。取不到（空串）时退回"库按模型自选"，
    // 那是引入本特性之前的行为，不是新分叉。
    common_chat_templates_ptr tmpls;
    try {
        tmpls = common_chat_templates_init(S.model, tmpl);
    } catch (const std::exception & e) {
        jp("[schema] templates_init 异常: %s -> 无 grammar\n", e.what());
        jlog("[schema] 模板初始化异常（%s），已降级为无约束采样", e.what());
        return out;
    } catch (...) {
        jp("[schema] templates_init 未知异常 -> 无 grammar\n");
        jlog("[schema] 模板初始化未知异常，已降级为无约束采样");
        return out;
    }
    if (!tmpls) { jp("[schema] templates_init -> NULL\n"); return out; }

    common_chat_templates_inputs in;
    in.use_jinja = true;
    // 生成后缀：**只在调用方明确给了**（非 null）时才用它决定 add_generation_prompt。
    // 空串 = "确认没有生成后缀" -> false；null = 未知 -> 保留 C++ 默认（true，即旧口径）。
    // 判据必须写成"指针非空"而不是"串非空"：把"没有生成后缀"误当成"未知"，
    // 就会给一个没有生成后缀的 prompt 施加"生成后缀之后"的 grammar。
    const bool haveGenPrompt = (genPrompt != nullptr);
    if (haveGenPrompt) in.add_generation_prompt = (*genPrompt != '\0');
    // ══════════════════════════════════════════════════════════════════════
    // ④ 思考开关：必须与渲染侧同一个值 —— 这是「换模型才暴露」那次故障的根因
    // ══════════════════════════════════════════════════════════════════════
    // `common_chat_templates_inputs::enable_thinking` 的 C++ 默认值是 `true`。
    // 这一跳此前只设了 add_generation_prompt、**没设它** —— 于是同一个模板被求值两次：
    //     渲染侧: enable_thinking=false -> 生成后缀 41B，并写进 prompt
    //     这一跳: 默认 true             -> cp.generation_prompt 只有 22B/30B
    // 库据此推出 `literal(短后缀) + optional(<think>,</think>) + space() + JSON`，
    // 而真 prompt 里思考块**已经写死**。当时据此推断"space() 不能匹配空串、全体候选
    // 被置 -inf" —— ⚠ **该推断已在 0.9.93 证伪**：库内
    //     space ::= | " " | "\n"{1,2} [ \t]{0,20}
    // 第一个分支就是空，它能匹配空串。所以"思考开关透传"这件事**本身仍然是对的**
    // （两份 PEG 该出自同一次求值），但"短喂会让约束整个失效"不是它成立的理由。
    // 详见 HTP-STATUS 第二十七节 27.1 与 probe_util.h 的 grammar_fit_check 注释。
    //
    // 真机实据（probe-20260921-151504.txt，Qwen3 三例逐字一致）：
    //     gen_suffix=<|im_start|>assistant\n<think>\n\n</think>\n\n   41B  ← 真 prompt 的尾巴
    //     gen_prompt=<|im_start|>assistant\n                            22B  ← 库按「思考开」算的
    // MiniCPM5 例 gen_prompt=`...<think>\n`(30B) 同理：也是「思考开」那一支的形状。
    //
    // 透传它**不改变语义**：库默认值本来就是 true，渲染侧也一直是这个取值
    // （ThinkingControl.resolve：请求覆盖 > 全局默认）。这里只是把渲染侧那份
    // **显式**对齐过来，让两份 PEG 出自同一次模板求值。
    in.enable_thinking = thinkingOn;
    // 关键：把 schema 交给模板引擎 —— 它才会在 cp.grammar 里产出对应的 GBNF。
    in.json_schema = schema;

    common_chat_params cp;
    try {
        cp = common_chat_templates_apply(tmpls.get(), in);
    } catch (const std::exception & e) {
        // 这一条是**正常的能力边界**，不是代码 bug：schema 写了库不支持的写法。
        // 用 jlog 让调用方在 App 日志里看到原因，而不是只看到"没约束上"。
        jp("[schema] templates_apply 抛异常: %s -> 无 grammar（降级为无约束）\n", e.what());
        jlog("[schema] schema 不受支持或无法转成 GBNF（%s），已降级为无约束采样", e.what());
        return out;
    } catch (...) {
        jp("[schema] templates_apply 未知异常 -> 无 grammar\n");
        jlog("[schema] schema 转换未知异常，已降级为无约束采样");
        return out;
    }
    if (cp.grammar.empty()) {
        jp("[schema] 模板未产出 grammar（该模板可能不支持 json_schema）-> 降级为无约束\n");
        jlog("[schema] 模板未产出 GBNF，已降级为无约束采样");
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // grammar 的「首字面量」：决定采样器要不要预填、预填多少（本类故障第五处成因）
    // ══════════════════════════════════════════════════════════════════════
    // 库推出来的 GBNF **以生成前缀的字面量开头**（MiniCPM5 硬编码
    // `p.literal("<|im_start|>assistant\n")`，见上游 chat.cpp 的 minicpm5 分支），
    // 而那段字面量**已经在 prompt 里**了。不预填时 grammar 从根节点起步、
    // 要求第一个 token 就是 `<|im_start|>`，模型被迫把模板写好的尾巴再吐一遍
    // —— 真机表现正是 content 前面多一段 / 数组只剩 `[ ]`。
    //
    // 预填量 = 「grammar 首字面量」∩「渲染侧真后缀」的**最长公共前缀**。
    // ══════════════════════════════════════════════════════════════════════
    // 上限必须是 **grammar 自己的首字面量**，不是库算的生成后缀 —— 这是本轮
    // 真机 SIGABRT 的真根因（0.9.90 的 prefill = gen_prompt 41B 引入的回归）。
    // ══════════════════════════════════════════════════════════════════════
    // 真机现场（probe-20260921-172241.txt，Qwen3 + 关思考 + json_schema）：
    //     gen_prompt=41B(…) rendered_suffix=41B prefill=41B peg_lit=22B(已取到) think=0
    //     ⚠ grammar 预填失败（Unexpected empty grammar stack after accepting piece: <think>）
    //     llama-grammar.cpp:942: LM_GGML_ASSERT(!stacks.empty()) failed -> SIGABRT
    //
    // 库在这一次请求里给出了**两份互相矛盾**的位置信息：
    //   · `cp.generation_prompt` = 41B，含 `<think>\n\n</think>\n\n` 那段思考块；
    //   · `cp.grammar`（GBNF）的根首字面量只有 22B = `<|im_start|>assistant\n`，
    //     之后紧跟 JSON —— 也就是说 **grammar 根本不知道有思考块那段**。
    // 于是把 41B 全喂进去时，喂到 `<think>`（token 151667）那一步 grammar 已经无栈可推，
    // 抛 "empty grammar stack"；这一抛**只是异常**，可 grammar 的状态机已被打破，
    // 而 0.9.90 的 catch 分支**仍把被打坏的采样器挂进链**（只记 n_prefill=-1），
    // 于是下一个 token 的 `llama_sampler_sample` 走到同一个断言 -> **整个进程 abort**。
    // 这就是"闪退"：不是慢、不是出错，是进程被 lm_ggml_abort 直接杀掉。
    //
    // 为什么 0.9.89（按首字面量取交）不崩：它最多喂 22B，grammar 那一步是走得通的。
    // 0.9.90 为了让 Qwen3 的 grammar 落点更"准"，把上限换成了 41B 的库后缀 ——
    // 换来了这次 abort。**上限只能取二者中 grammar 真能咽下的那一个**：
    // 首字面量是 grammar 自己声明的"我要跳过的前缀"，比库的另一份产物可靠。
    const std::string renderedSuffix = genPrompt ? std::string(genPrompt) : std::string();
    out.pegLiteral = schema_peg_leading_literal(cp.parser, out.pegWhy);
    out.prefill = grammar_prefill_from_literal(out.pegLiteral, renderedSuffix);

    // 就位点判据：预填把 grammar 推到哪 / 真 prompt 的下一个字节是什么 / 两者是否一致。
    // 只落日志，不改变任何行为 —— 前八处成因里有两次 abort 与 Qwen3 的"代码块"故障
    // 都发生在**采样那一步**，而那里此前一条日志都没有，只能靠 prefill 数字反推。
    const grammar_fit_result fit = grammar_fit_check(out.prefill, renderedSuffix);

    jp("[schema] templates_apply 产出 grammar: schema=%zuB gbnf=%zuB lazy=%d triggers=%zu"
       " tmpl=%zuB add_gen_prompt=%d(%s) gen_prompt=%zuB(%s) rendered_suffix=%zuB"
       " prefill=%zuB peg_lit=%zuB(%s) think=%d\n",
       schema.size(), cp.grammar.size(), (int) cp.grammar_lazy, cp.grammar_triggers.size(),
       tmpl.size(), (int) in.add_generation_prompt,
       haveGenPrompt ? (in.add_generation_prompt ? "调用方说这一轮有生成后缀" : "调用方说这一轮没有生成后缀")
                     : "调用方未提供（退回默认 true）",
       cp.generation_prompt.size(), escape_for_probe(cp.generation_prompt, 40).c_str(),
       renderedSuffix.size(), out.prefill.size(),
       out.pegLiteral.size(), out.pegWhy.empty() ? "已取到" : out.pegWhy.c_str(),
       (int) in.enable_thinking);
    // 就位点那一行：这是本轮新增的**唯一**一条"能看见采样那一步"的判据。
    // fit=0 表示预填长度 != 真后缀长度 —— 此时模型实际要续写的位置与 grammar 的落点错开，
    // 但**不等于**故障（库内 `space ::= | " " | "\n"{1,2} [ \t]{0,20}` 能匹配空串，
    // 短喂本身不置 -inf）。真正要修的是**错位段** mismatch 的内容：
    // 它是模板写死、而 grammar 不认的那一段（Qwen3 是 `<think>\n\n</think>\n\n`）。
    jp("[schema] grammar 就位点：prefill=%zuB 之后 grammar 期望续写 / 真 prompt 后缀=%zuB"
       " fit=%d mismatch=%zuB(%s)%s\n",
       fit.expectBytes, fit.actualBytes, fit.aligned,
       fit.mismatch.size(), escape_for_probe(fit.mismatch, 60).c_str(),
       looks_like_think_mismatch(fit.mismatch) ? "  <- 错位段含 think 块（库的 PEG 不认它）" : "");
    // 判据（几个数字一起看，缺一个都可能误判）：
    //   · tmpl 非 0 且与渲染侧同一份            -> 模板没有分叉；
    //   · gen_prompt 与 rendered_suffix **同长** -> 两侧对"生成后缀"的理解一致（think 透传生效）；
    //     gen_prompt 明显短于 rendered_suffix    -> 思考开关没透传；
    //   · **prefill <= peg_lit**                 -> 预填量在 grammar 能咽下的范围内
    //     （0.9.91 回归前 prefill 会等于 41B > peg_lit 22B，此后必 abort）；
    //   · peg_lit 比 gen_prompt 短               -> 库的两份产物对"续写位置"理解不同
    //     （Qwen3 关思考 + json_schema 的思考块）；差的那 19B 就是 grammar 不认的部分，
    //     也是 0.9.90 全喂进去后 abort 的那一段。看到这个差值不等于故障，但别再去补喂它。
    //   · fit=0 + mismatch 非空                  -> 续写位置与 grammar 落点错开，
    //     mismatch 就是错位段本身（上面那行会把它原文打出来）。
    //     ⚠ 别再把错位段解释成"全体候选被置 -inf"：库内 space 规则带空分支、能匹配空串
    //       （见 probe_util.h 的 grammar_fit_check 注释与 HTP-STATUS 第二十七节 27.1）。
    out.gbnf = cp.grammar;
    return out;
}

// applyChatTemplate(tmpl, roles[], contents[], addAss, enableThinking) -> 渲染后的 prompt；失败返回 null
//
// ══════════════════════════════════════════════════════════════════════════
// 为什么这里不能用 `llama_chat_apply_template`（旧版轻量接口）
// ══════════════════════════════════════════════════════════════════════════
// 旧接口只接收 role/content 文本，**没有 enable_thinking 这个入参** ——
// 也就是说走它渲染出来的生成后缀永远是模板「思考开」的那一支。
// MiniCPM5 的模板尾部是：
//
//     {%- if enable_thinking is defined %}
//         {%- if enable_thinking is false %}{{- '<think>\n\n</think>\n\n' }}
//         {%- elif enable_thinking is true %}{{- '<think>\n' }}
//
// 于是「设置页勾上默认关闭思考」在 MiniCPM5 上从来就没生效过：库在渲染这一步
// 就把 `<think>\n` 写进 prompt 了，Kotlin 侧再怎么补都只是往后叠。
// 2026-09-19 真机报障（客户端多一个 think 标签 + 一直推理）的**第一个**成因就在这。
//
// 换成 `common_chat_templates_apply` 之后这个字段可传，模板自己会吐正确的
// 闭合块（`\n\n</think>\n\n`），比宿主拼出来的更准（它知道自己的换行怎么写）。
// 顺带与带 tools 的那条路径用上同一套模板引擎，两条渲染路径不再有两份语义。
//
// 注意：这里显式传 `use_jinja = true`。库对「模型没带模板」是回落内置模板，
// 而内置模板的挑选依赖 use_jinja 的逻辑；旧接口行为等价，换过来之后要保持一致。
JNIEXPORT jstring JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeApplyChatTemplate(
        JNIEnv * env, jclass, jstring jtmpl, jobjectArray roles, jobjectArray contents,
        jboolean addAss, jboolean enableThinking) {
    JNI_SPAN("applyChatTemplate");
    jp(">> applyChatTemplate(无 tools) n=%d et=%d\n",
       roles ? (int) env->GetArrayLength(roles) : -1, (int) (enableThinking == JNI_TRUE));
    if (!S.model) { jp("<< applyChatTemplate 无模型 -> null\n"); return nullptr; }

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
        // jr == jc 时无条件 DeleteLocalRef 两次等于释放两次，CheckJNI 会直接 abort。
        if (jr) env->DeleteLocalRef(jr);
        if (jc && jc != jr) env->DeleteLocalRef(jc);
    }

    common_chat_templates_ptr tmpls;
    try {
        tmpls = common_chat_templates_init(S.model, tmpl.empty() ? "" : tmpl);
    } catch (const std::exception & e) {
        jp("<< applyChatTemplate templates_init 异常: %s\n", e.what());
        return nullptr;
    } catch (...) {
        jp("<< applyChatTemplate templates_init 未知异常\n");
        return nullptr;
    }
    if (!tmpls) { jp("<< applyChatTemplate templates_init -> NULL\n"); return nullptr; }

    common_chat_templates_inputs in;
    in.use_jinja = true;
    in.add_generation_prompt = (addAss == JNI_TRUE);
    // 思考开关：默认关闭思考时必须真的传下去，否则模板永远停在「思考开」那一支。
    in.enable_thinking = (enableThinking == JNI_TRUE);
    in.messages.reserve(n);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = rs[i];
        m.content = cs[i];
        in.messages.push_back(std::move(m));
    }

    common_chat_params cp;
    try {
        cp = common_chat_templates_apply(tmpls.get(), in);
    } catch (const std::exception & e) {
        jp("<< applyChatTemplate templates_apply 异常: %s\n", e.what());
        return nullptr;
    } catch (...) {
        jp("<< applyChatTemplate templates_apply 未知异常\n");
        return nullptr;
    }
    if (cp.prompt.empty()) { jp("<< applyChatTemplate 渲染结果为空 -> null\n"); return nullptr; }
    jp("<< applyChatTemplate ok: prompt_len=%zu et=%d tail=%.40s\n",
       cp.prompt.size(), (int) in.enable_thinking,
       escape_for_probe(cp.prompt.substr(cp.prompt.size() > 40 ? cp.prompt.size() - 40 : 0), 60).c_str());
    // 语义标注随 prompt 一起返回：取值必须由渲染这一侧给（见左侧注释）。
    return new_rendered_prompt(env, cp.prompt, cp.generation_prompt);
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
        jstring jtoolsJson, jstring jtoolChoice, jboolean parallelToolCalls, jboolean addAss,
        jboolean enableThinking) {
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
    // 思考开关必须显式传：C++ 默认是 true，而 MiniCPM5 模板会据此决定生成后缀吐
    // 不吐 `<think>\n`。不传 = 「默认关闭思考」在这类模型上恒不生效（真机 2026-09-19 报障）。
    in.enable_thinking = (enableThinking == JNI_TRUE);
    jp("[tools] templates_inputs: add_generation_prompt=%d enable_thinking=%d\n",
       (int) in.add_generation_prompt, (int) in.enable_thinking);
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

    // 语义标注与无 tools 路径同一份实现、同一份判据（两条渲染路径不得各判一次，
    // 否则换个入口就换一种行为 —— 正是本项目反复踩的"改一处漏一处"）。
    return new_rendered_prompt(env, cp.prompt, cp.generation_prompt);
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
        jstring jtoolsJson, jstring jtoolChoice, jboolean parallelToolCalls, jboolean addAss,
        jboolean enableThinking) {
    JNI_SPAN("applyChatTemplateTools");
    try {
        return applyChatTemplateToolsImpl(env, jtmpl, roles, contents,
                                          jtoolsJson, jtoolChoice, parallelToolCalls, addAss,
                                          enableThinking);
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
        JNIEnv * env, jstring jtext, jstring jtoolsJson, jstring jtmpl, jboolean addAss,
        jboolean enableThinking) {
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
    // ── 关键分叉点：解析侧的 add_generation_prompt 必须与渲染侧**同一个值** ──
    // cp.generation_prompt 是「带生成后缀的整段 prompt」减「不带的」的公共前缀之后的剩余部分
    // （上游 common_chat_template_generation_prompt_impl），而 common_chat_parse 会把它
    // **前拼**到输入上（effective_input = generation_prompt + input）。
    // 因此这个量就是 PEG 根节点要匹配的那个字面前缀。
    //
    // 之前这里写死 false，与渲染侧的 true 分叉，真机后果（0.9.68/0.9.69 日志）：
    //   MiniCPM5 模板在 add_generation_prompt 下才会吐 "<|im_start|>assistant\n"，
    //   加上 enable_thinking（C++ 默认 true）再吐 "<think>\n"，于是
    //       解析侧 generation_prompt = "<|im_start|>assistant\n<think>\n"（30B）
    //   而 PEG 根节点要的是   literal("<|im_start|>assistant\n")（22B）
    //   差的那 8B 正是 "<think>\n" —— 与日志里 content_len - text_len = 8 精确吻合。
    //   根节点一上来就匹配不上，PEG 回退到纯内容，tool_calls 恒为 0、content 吃下全文。
    // 这是「崩得响」之外那种「哑得不响」的静默降级，比崩溃更难查（不报错、不崩溃、
    // 只在客户端表现为接口 200 但没收到工具调用）。
    in.add_generation_prompt = (addAss == JNI_TRUE);
    // **必须与渲染侧同一个值**，理由与 add_generation_prompt 完全一致（见上面那段注释）：
    // cp.generation_prompt 会由 common_chat_parse 前拼到输入上，而它随
    // enable_thinking 变化 —— MiniCPM5 在 true 下生成后缀多一段 "<think>\n"，
    // 解析侧的根节点前缀就会与渲染侧对不上，PEG 一上来就匹配失败、tool_calls 静默变 0。
    in.enable_thinking = (enableThinking == JNI_TRUE);
    jp("[parse] templates_inputs: add_generation_prompt=%d enable_thinking=%d\n",
       (int) in.add_generation_prompt, (int) in.enable_thinking);
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

    // ══════════════════════════════════════════════════════════════════════
    // 第三条真因：`generation_prompt` 的形状与 PEG 根节点的字面量不一致
    // ══════════════════════════════════════════════════════════════════════
    // common_chat_parse 会**自己**把 params.generation_prompt 前拼到输入上：
    //     effective_input = params.generation_prompt + input
    // 而 PEG 的根节点是一个**字面量**（MiniCPM5 为 "<|im_start|>assistant\n"）。
    // 库要求输入以这个字面量开头；两者形状不一致时根节点一上来就匹配不上，
    // PEG 回退成"整段都是 content" —— tool_calls 恒为 0，不报错、不崩溃。
    //
    // generation_prompt 由上游 common_chat_template_generation_prompt_impl 算出：
    // 同一模板渲染 add_generation_prompt=false / =true 两次，取公共前缀之后的剩余部分。
    // MiniCPM5 在 add_generation_prompt=true 且 enable_thinking（C++ 默认 true）时，
    // 这个量是 "<|im_start|>assistant\n" + "<think>\n"（30B），而根节点要的是 22B。
    //
    // 关于那个「+8」：**它只是这段历史的线索，不是当前的判据**。
    // 0.9.68/0.9.69 两份日志的 content_len - text_len 都是 8，与 30 - 22 吻合，
    // 当时据此定位到"解析侧 generation_prompt 比根节点多一个 <think>\n"。
    // 但**差值本身不能反过来证明修复有效**：Kotlin 侧的 text_len 是 UTF-16 code unit、
    // 这边 content_len 是 UTF-8 字节，输出里只要有一个汉字这两个数就不可比。
    // 所以本轮不再把「差值」当验收条件，改用两条**同构**的判据（见下面日志）：
    //   · generation_prompt == root_literal（两边都是这边量的字节数）
    //   · effective_input 以 root_literal 开头
    //
    // 对齐规则（实现与单测都在 probe_util.h，这里只调用）：
    //   · 已对齐              -> 一个字节都不动；
    //   · 字面量 + 尾巴       -> 尾巴前移到输入最前，effective_input 逐字节等价；
    //   · 形状不符（不以字面量开头）-> 库给的 generation_prompt 原样保留，
    //                            只把字面量补进输入最前，保证 effective_input 以它开头。
    // 根节点字面量**从 arena 现场取**，不写死任何模板名 —— 写死等于把这条路绑死在
    // MiniCPM5/Qwen 一家上，换个模板（llama 的 <|start_header_id|>、gemma 等）就静默
    // 失效，正是本项目反复踩的那类"不崩但不干活"。
    {
        const std::string gp = pp.generation_prompt;
        // 从 PEG 现场取根节点字面量（实现见 probe_util.h，宿主下有单测兜住）。
        // 取不到（根非 literal / 越界 / 空串）时 rootLiteral 为空 -> 不改写、按原样交给库。
        const root_literal_probe_result rootProbe =
            probe_root_literal<common_peg_arena, common_peg_literal_parser>(pp.parser);
        const std::string & rootLiteral = rootProbe.literal;

        jp("[parse] 输入对齐：root_is_literal=%d root_literal=%s%s%s\n",
           (int) rootProbe.isLiteral, escape_for_probe(rootLiteral, 60).c_str(),
           rootProbe.why.empty() ? "" : "  why=", rootProbe.why.c_str());

        const std::string textOriginal = text;   // 改写前的输入（回退用）
        gen_prompt_align_result al = align_generation_prompt(gp, rootLiteral, text);

        // ── 逐字节等价性自检（情形① 才需要） ──
        // 情形① 是"尾巴前移"，理论上 effective_input 应与改写前**完全相同**：
        //     gp + text  ==  (rootLiteral + tail) + text  ==  rootLiteral + (tail + text)
        // 真算出两边比对一次。不等就说明改写动了语义 —— 宁可整个放弃改写
        // （按原样交给库），也不让这次修复把原本能工作的输入改坏。
        // 这是"一定不要引入新问题"的底线做法：新增路径永远只能更保守。
        std::string effectiveHead;
        {
            if (al.mode == 1) {
                const bool equivalent = (al.generation_prompt + al.input) == (gp + textOriginal);
                if (!equivalent) {
                    jp("[parse] ⚠ 输入对齐：改写后 effective_input 与原「前缀+输入」不一致 -> 回退为不改写\n");
                    jlog("[tools] generation_prompt 对齐会改变输入语义，已回退为不改写（保守）");
                    al.generation_prompt = gp;
                    al.input             = textOriginal;
                    al.patched.clear();
                    al.changed           = false;
                    al.mode              = 0;
                }
            }
            pp.generation_prompt = al.generation_prompt;
            text                 = al.input;
            effectiveHead        = pp.generation_prompt + text;
        }

        if (al.mode == 1) {
            jp("[parse] 输入对齐：generation_prompt %zuB -> %zuB（尾部 %s 共 %zuB 前移到输入，"
               "effective_input 与原「前缀+输入」逐字节等价）\n",
               gp.size(), al.generation_prompt.size(),
               escape_for_probe(al.patched, 60).c_str(), al.patched.size());
        } else if (al.mode == 2) {
            jp("[parse] 输入对齐：generation_prompt 不以 root_literal 开头 -> 原样保留 %zuB，"
               "只把字面量 %s 前拼进输入（%zuB）\n",
               gp.size(), escape_for_probe(rootLiteral, 60).c_str(), al.patched.size());
        } else {
            jp("[parse] 输入对齐：无需改写（根非字面量 / 已对齐 / 已回退）\n");
        }

        jp("[parse] generation_prompt_len=%zu generation_prompt=%s\n",
           pp.generation_prompt.size(), escape_for_probe(pp.generation_prompt, 120).c_str());
        jp("[parse] effective_input_len=%zu effective_input_head=%.160s\n",
           effectiveHead.size(), escape_for_probe(effectiveHead.substr(0, 160), 200).c_str());
        // 判据（下次照这几行看）：
        //   root_is_literal=1 且 generation_prompt == root_literal            -> 根节点能匹配上
        //   effective_input_head 以 root_literal 开头                         -> 输入前缀也对齐了
        // 两条都满足却仍是 tool_calls=0，才说明问题不在前缀对齐上（那时的第一嫌疑
        // 是模型压根没按模板语法输出，而不是解析器这边）。
        if (rootProbe.isLiteral && pp.generation_prompt != rootLiteral) {
            jp("[parse] ⚠ 输入对齐：generation_prompt 与 root_literal 不一致 -> 大概率仍会 tool_calls=0\n");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 四级成因的日志分流：把「解析侧的问题」与「解码侧的问题」分开
    // ══════════════════════════════════════════════════════════════════════
    // 本 PR 一共定位了四条独立成因，任何一条单独存在都会让 tool_calls 恒为 0：
    //   ① pp.parser 没 load（PEG 空 arena -> 静默降级成纯内容）
    //   ② 解析侧 add_generation_prompt 与渲染侧不一致（前缀来源就不同）
    //   ③ generation_prompt 的形状 ≠ PEG 根节点字面量（MiniCPM5 恒带 "<think>\n"）
    //   ④ **解码时 special token 没文本化**（`<function`/`<param` 等被直接丢掉）
    // ①②③ 都在"喂给解析器的东西"上，已在上面的代码里修掉。
    // ④ 发生在更上游的 nativeStep（见 token_to_piece 的注释），也已修掉。
    //
    // 下面这段只做**日志分流**，不改输入：如果 text 看起来像"被删掉工具标记"的片段，
    // 就直接指向④，避免下次又在解析器上绕圈。
    {
        // MiniCPM5 的工具标记。它们既是 PEG 的 tool_open/tool_close 字面量，
        // 也是模型词表里的 special token —— 同一个东西，两个身份。
        static const char * kToolMarkers[] = { "<function", "<param", "</param>", "</function>" };
        bool hasAnyMarker = false;
        for (const char * m : kToolMarkers) {
            if (text.find(m) != std::string::npos) { hasAnyMarker = true; break; }
        }
        // 「像内层片段」：以 " name=" / "> name=" 开头（正是 <function / <param 被删后的残骸），
        // 且整段没有任何完整工具标记。这个形状在真机日志里出现过（0.9.69）。
        const bool looksStripped =
            !hasAnyMarker &&
            (text.compare(0, 6, " name=") == 0 || text.compare(0, 7, "> name=") == 0);
        if (looksStripped) {
            jp("[parse] ⚠ 解码侧可疑：text 以 %s 开头且不含任何工具标记；"
               "形状与 special token 未文本化的残骸一致 -> 先确认 token_to_piece 走的是 renderSpecial=true"
               "（真机 0.9.69 的 text_head 正是这个形状）\n",
               escape_for_probe(text.substr(0, 40), 60).c_str());
            jlog("[tools] 解码文本疑似丢失工具标记（special token 未文本化），解析大概率拿不到 tool_calls");
        }
    }

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
        JNIEnv * env, jclass, jstring jtext, jstring jtoolsJson, jstring jtmpl, jboolean addAss,
        jboolean enableThinking) {
    JNI_SPAN("parseToolCalls");
    try {
        return parseToolCallsImpl(env, jtext, jtoolsJson, jtmpl, addAss, enableThinking);
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

// 取消「编号为 roundEpoch 的那一轮生成」。
//
// ══════════════════════════════════════════════════════════════════════════
// 为什么必须带编号（而不是"谁调都停当前那一轮"）
// ══════════════════════════════════════════════════════════════════════════
// 置位方来自 HTTP 连接线程 / 主线程，与生成线程不是同一个。这一跳此前**丢掉了归属**
// （无条件 `S.abort = true`），于是有两类错：
//   ① 迟到的取消停错对象 —— 一个已经结束的轮次的取消，会把**此时才开始的下一轮**
//      一起停掉。Kotlin 侧 `RequestCancel` 专门为此做了 token 归属（见其文件头），
//      而最后一跳把它丢了，等于那套归属只防到了 JNI 门口。
//   ② 数据竞争 —— 裸 bool 的跨线程读写是 UB，编译器可以把读取提升到寄存器只读一次。
//      `abort` 现在是 atomic（见 Session 的定义），语义仍是"只置位"。
//
// roundEpoch = 调用方要停的那一轮的编号（由 nativeCurrentEpoch 在那一轮开始时取到）。
// 编号与当前轮次不一致 = 那一轮已经不是"当前这一轮"了 -> **一律不停**（fail-safe
// 指向"漏停"而不是"错停"：漏停只多烧一次算力，错停会让用户看到别人的请求被掐断）。
// 0 = 调用方没有归属信息（老接口的语义）-> 同样不停，并留一行日志说明为什么。
JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeAbort(JNIEnv *, jclass, jlong roundEpoch) {
    const long long cur = S.round_epoch.load();
    if (roundEpoch == 0 || (long long) roundEpoch != cur) {
        // 归不上不生效，但**必须留痕**：否则"取消没反应"会被归因成网络/客户端问题。
        jp(">> abort 被忽略：归属 %lld != 当前轮次 %lld（停错对象比漏停危险）\n",
           (long long) roundEpoch, cur);
        return;
    }
    jp(">> abort 轮次 %lld\n", cur);
    S.abort.store(true);
    // ══════════════════════════════════════════════════════════════════════
    // 作废 KV 账本 —— 这是 E-2 的核心一笔，**不能**省。
    // ══════════════════════════════════════════════════════════════════════
    // 被打断的那一轮会在 KV 里留下「prompt + 已经算进去的若干生成 token」，
    // 而账本（S.last_tokens / S.kv_valid）此前**照样有效**。下一轮于是把这段
    // 残留当成"可复用的历史"接着算 —— `kv_prefix.h` 文件头点名要防的失效模式
    // （多留一段 -> 模型读到错位的历史 -> 答非所问，但接口 200、不报错）
    // 正是由这一个出口造成的。
    //
    // 为什么在这里作废而不是"顺手把 KV 一起清掉"：KV 不是线程安全的，
    // 而本函数跑在 HTTP 连接线程 / 主线程上。清 KV 只能在生成线程做 ——
    // 作废账本之后，下一轮 startCompletion 会走"未命中"分支，
    // 那里的 `seq_rm(0,-1,-1)` 才是真正把残留清掉的那一笔（同线程、安全）。
    kv_invalidate("本轮被 abort（残留不作复用候选）");
}

// 主动丢弃 prompt 缓存：清 KV + 作废账本。
//
// 为什么需要一个显式出口：不做它是"能自己失效"的（前缀不匹配就不复用），
// 但**旧前缀会一直占着 KV 空间**直到下一次请求把它覆盖 —— 手机上的 KV
// 就是显存/内存，用户改了 system prompt 之后那几 MB 属于纯浪费。
// 语义上它必须**同时**清 KV 与作废账本：只清其中一个，
// 下一次就会拿一份"以为还在"的账本去复用一段已经被删掉的 KV。
JNIEXPORT void JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeKvCacheReset(JNIEnv *, jclass) {
    jp(">> kvCacheReset\n");
    kv_invalidate("显式请求丢弃");
    if (S.ctx) llama_memory_seq_rm(llama_get_memory(S.ctx), 0, -1, -1);
    S.n_used = 0;
}

JNIEXPORT void JNICALL Java_com_xiaowan_localinference_LlmEngine_nativeFreeSampler(JNIEnv *, jclass) {
    if (S.smpl) { llama_sampler_free(S.smpl); S.smpl = nullptr; }
}


JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeUnloadModel(JNIEnv *, jclass) { jp(">> unloadModel\n"); unloadModelInternal(S); }

// 卸载**嵌入**模型（只拆 E，绝不碰 S）。与 nativeUnloadModel 分开是两个原因：
//   1. 双常驻下"卸载对话模型"不该顺带把嵌入模型也拆了 —— 那会让每次换对话模型
//      都白丢一份嵌入模型与（更贵的）它的 KV；
//   2. 分开之后各自的守卫可以正面断言"这条路径没有动另一份 Session"，
//      而共用一条会被参数化掩盖（参数写错就静默拆错那一份）。
JNIEXPORT void JNICALL
Java_com_xiaowan_localinference_LlmEngine_nativeUnloadEmbedModel(JNIEnv *, jclass) { jp(">> unloadEmbed\n"); unloadModelInternal(E); }

} // extern "C"

// 释放一份 Session 持有的采样器 / 上下文 / 模型。**参数化**而不是只对 S 动手：
// 双常驻之后进程里有 S（对话）与 E（嵌入）两份，两者都必须能被独立、干净地拆掉。
// 参数化同时把"拆错那一份"这类错误变成**看得见**的（调用点写的是哪一个变量），
// 而不是靠"约定只对 S 动手"——约定在 6 个月后没人记得。
static void unloadModelInternal(Session & s) {
    // 换模型 / 卸载：KV 与它的账本一起没了 —— **必须先作废账本**。
    // 漏掉这一笔的失效很隐蔽：换模型后第一轮会按旧账本"命中复用"，
    // 于是把上一个模型算出的 KV 当成新模型的历史喂进去（不崩、不报错，
    // 只输出一份与 prompt 无关的内容）。这是本功能唯一一个"静默答非所问"的入口。
    //
    // 只对**对话**那份作废账本：KV 前缀复用的账本是 chat 路径的东西（kv_prefix.h），
    // 嵌入路径不读它。但对 E 调用 kv_invalidate 也无害（E 从不写 last_tokens），
    // 所以这里不做角色分支 —— 少一个分支就少一处能写错的地方。
    kv_invalidate("模型卸载/换模型");
    // 峰值跟着模型走：换模型后重新累计，否则新模型会继承上一个模型的峰值，
    // 读数与"这次加载"对不上（而这一行本来就是对账用的）。
    g_rss_peak_kb = LM_RSS_PEAK_RESET;
    // ── 卸载也必须**自证**：RSS 前后各量一次 ────────────────────────────────
    // 为什么：0.9.130 用户报「『关』了 repack、内存也有下降，但应用列表里占用
    // 一直很高」。列表显示的数是 **VmRSS**，它把**已释放但未归还内核的匿名页**
    // 也算在内 —— repack 的拷贝、KV、compute buffer 全是这一类。于是
    // 「free 了」与「列表里的数降了」是两件事，此前没有任何读数能把它们分开，
    // 用户只能看到"占用不降"，而日志静默。这里把前后值打进日志，让
    // "到底降了多少、由哪一次释放造成"成为可读的事（同 `[mmap释放]` 那条自证）。
    //
    // ⚠ 只读 /proc/self/status 的 VmRSS，不猜：读不到就报 -1（与其它探针同一口径）。
    const long rss_before_kb = proc_rss_kb();
    if (s.smpl)  { llama_sampler_free(s.smpl);   s.smpl = nullptr; }
    if (s.ctx)   { llama_free(s.ctx);            s.ctx = nullptr; }
    if (s.model) { llama_model_free(s.model);    s.model = nullptr; s.vocab = nullptr; }
    const long rss_after_kb = proc_rss_kb();
    // 角色必须出现在日志里：双常驻下"哪一份被拆了"是排障的第一判据 ——
    // 一行 `[内存] 卸载[嵌入] …` 与 `[内存] 卸载[对话] …` 一眼可辨，
    // 而共用一行会在"聊到一半模型没了"这类故障里把方向指错。
    jlog("[内存] 卸载[%s] RSS %ld MB → %ld MB（还 %ld MB）｜ 读数是 VmRSS：已 free 但未归还"
         "内核的匿名页仍计入其中，应用列表里的「占用」同源",
         s.embedding ? "嵌入" : "对话",
         rss_before_kb / 1024, rss_after_kb / 1024,
         (rss_before_kb >= 0 && rss_after_kb >= 0) ? (rss_before_kb - rss_after_kb) / 1024 : -1);
    s.n_ctx = 0; s.n_used = 0; s.n_rem = 0;
    s.pending.clear();
    // 角色标记复位：卸载之后这两份 Session 都回到"空的对话槽"状态。
    // 不复位会让下一次"加载对话模型"往一个 embedding=true 的槽里写，
    // 而那个标记决定加载参数（embeddings=true/false）—— 直接串味。
    s.embedding = false;
    // 池化类型同理复位（见 Session::pool_type 的注释）：不复位时，
    // "卸载嵌入 → 加载对话模型 → 误走嵌入路径"会带着上一份模型的池化类型。
    // 与 embedding 标记同一段、同一理由：**角色相关的加载期状态必须整组复位**，
    // 漏一个就是一个只在特定顺序下出现的静默故障。
    s.pool_type = -1;
}

