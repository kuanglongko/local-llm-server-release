// utf8_safe_test.cpp — new_string_utf8_safe 的宿主侧单测
//
// 覆盖核心解码器：真实崩溃场景（llama 按字节截断 tokenizer
// 词表产生的孤立 0xc4 + "..."）、overlong、孤立代理对、越界码位、随机字节 fuzz。
//
// 越界检测用 guard page 而非 ASan（本环境无 libasan）：把输入放到紧贴 PROT_NONE
// 页的位置，任何越过末尾 NUL 的读立即 SIGSEGV —— 对这个 bug 的针对性更强。
//
// 运行：bash tools/run_utf8_tests.sh
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

#include "utf8_safe.h"   // 真实实现，与 Android 构建共用同一份头文件

static int g_fail = 0;
static const jchar REPL = 0xFFFD;

// ---- guard page 基础设施：可读区尾部紧邻 PROT_NONE 页 ----------------------
static unsigned char * g_region = nullptr;
static size_t g_ps = 0;

static void guard_init() {
    g_ps = (size_t) sysconf(_SC_PAGESIZE);
    unsigned char * base = (unsigned char *) mmap(nullptr, g_ps * 2,
                                                  PROT_READ | PROT_WRITE,
                                                  MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) { perror("mmap"); exit(2); }
    if (mprotect(base + g_ps, g_ps, PROT_NONE) != 0) { perror("mprotect"); exit(2); }
    g_region = base;
}

// 复制 n 字节（含末尾 NUL）到紧贴保护页处，返回起始指针
static const char * guard_place(const std::string & s) {
    size_t n = s.size() + 1;
    if (n > g_ps - 64) { printf("input too long for guard page\n"); exit(2); }
    unsigned char * p = g_region + g_ps - n;
    memcpy(p, s.data(), n); // s 本身以 \0 结尾（std::string::c_str）
    return (const char *) p;
}

static JNIEnv g_env;

// 所有解码都经由保护内存，顺带证明无越界读
static std::vector<jchar> decode(const std::string & in) {
    g_env.u16.clear(); g_env.lit.clear();
    g_env.new_string_calls = 0; g_env.new_string_utf_calls = 0;
    const char * p = guard_place(in);
    new_string_utf8_safe(&g_env, p);
    // 空串按设计走 NewStringUTF("")（合法 ASCII 字面量），其余必须走 NewString
    const bool want_new_string = !in.empty();
    if (want_new_string && g_env.new_string_calls == 0) {
        ++g_fail; printf("FAIL expected NewString path for input size %zu\n", in.size());
    }
    if (!want_new_string && g_env.new_string_utf_calls == 0) {
        ++g_fail; printf("FAIL empty input should use NewStringUTF\n");
    }
    return g_env.u16;
}

static void check(const char * name, const std::string & in, const std::vector<jchar> & want) {
    std::vector<jchar> got = decode(in);
    if (got != want) {
        ++g_fail;
        printf("FAIL %-34s got=[", name);
        for (jchar c : got) printf("%04X ", c);
        printf("] want=[");
        for (jchar c : want) printf("%04X ", c);
        printf("]\n");
    } else {
        printf("ok   %-34s (%zu code units, no OOB read)\n", name, got.size());
    }
}

int main() {
    guard_init();

    // 1-4 合法输入必须逐字符保真
    check("ascii", "hello", { 'h','e','l','l','o' });
    check("cjk 你好", "\xE4\xBD\xA0\xE5\xA5\xBD", { 0x4F60, 0x597D });
    check("emoji U+1F600", "\xF0\x9F\x98\x80", { 0xD83D, 0xDE00 });
    check("max codepoint U+10FFFF", "\xF4\x8F\xBF\xBF", { 0xDBFF, 0xDFFF });

    // 5 ★典型崩溃现场：Ċ 的首字节 0xc4 被截断后紧跟 "..."
    check("crash repro 0xc4 + ellipsis", "ab\xC4...", { 'a','b', REPL, '.','.','.' });

    // 6 序列被截在字符串末尾（最易越界读）
    check("truncated 3-byte tail", "\xE4\xBD", { REPL, REPL });
    check("truncated 4-byte tail", "\xF0\x9F\x98", { REPL, REPL, REPL });

    // 7-10 各类非法编码
    check("overlong C0 80", "\xC0\x80", { REPL, REPL });
    check("encoded surrogate ED A0 80", "\xED\xA0\x80", { REPL, REPL, REPL });
    check("out of range F4 90 80 80", "\xF4\x90\x80\x80", { REPL, REPL, REPL, REPL });
    check("stray continuation 0x80", "\x80", { REPL });
    check("illegal lead 0xF5", "\xF5\x80\x80\x80", { REPL, REPL, REPL, REPL });

    // 11-12 脏字节后必须恢复其后可读内容（日志可用性）
    check("recover after broken seq", "\xE4\xBD\xA0\xE5\x41", { 0x4F60, REPL, 'A' });
    check("recover after junk", "\xC4\x8a\xC4.", { 0x10A, REPL, '.' });  // C4 8A = U+010A 'Ċ'

    // 13 空串 / null 仍走 NewStringUTF("") 字面量（合法 ASCII，不经解码器）
    {
        g_env.lit.clear(); g_env.new_string_utf_calls = 0; g_env.new_string_calls = 0;
        new_string_utf8_safe(&g_env, guard_place(""));
        if (g_env.new_string_utf_calls != 1 || !g_env.lit.empty() || g_env.new_string_calls != 0) {
            ++g_fail; printf("FAIL empty should use NewStringUTF(\"\")\n");
        } else printf("ok   empty -> NewStringUTF(\"\")\n");
        g_env.new_string_utf_calls = 0;
        new_string_utf8_safe(&g_env, nullptr);
        if (g_env.new_string_utf_calls != 1) { ++g_fail; printf("FAIL null path\n"); }
        else printf("ok   null  -> NewStringUTF(\"\")\n");
    }

    // 13b nativeModelDesc 的"没模型"返回值必须与"有模型"同在**非空档**。
    // 为什么单列：HttpApi 拼自带测试页时只看 LlmEngine.hasModel 就把
    // modelDesc() 的返回当模型名（不再判 null），而没模型时这条路返回的是
    // 空串。空串走的是 NewStringUTF("") —— 与"有模型但名字为空"分属两档，
    // 这里把两档各自的返回值形状钉住：NewStringUTF("") 是合法 jstring（非 null），
    // 走 NewString 的全在非空档。谁把空串塞进 NewString 档、或反过来，
    // Kotlin 侧同一段代码就会重新变回"可能拿到 null"的形状。
    {
        std::vector<jchar> got = decode("x");   // decode 自带两档断言：非空档必须 NewString
        g_env.lit.clear(); g_env.new_string_utf_calls = 0; g_env.new_string_calls = 0;
        jstring empty_ret = new_string_utf8_safe(&g_env, guard_place(""));
        // 空档：NewStringUTF("")，且返回值非 null（Kotlin 侧才敢不判空）
        if (empty_ret == nullptr || g_env.new_string_utf_calls != 1 || g_env.new_string_calls != 0 ||
            got.size() != 1) {
            ++g_fail; printf("FAIL 空/非空两档的返回值形状被改了（空档 NewStringUTF / 非空档 NewString）\n");
        } else printf("ok   空档 -> NewStringUTF(\"\")、非空档 -> NewString（都是非 null jstring）\n");
    }

    // 14 模拟整条 LFM tokenizer 日志行：长串 + 中段截断 + 尾部省略号
    {
        std::string pre  = "llama_model_loader: - kv 24: tokenizer.ggml.tokens arr[str,128000] = [";
        std::string line = pre;
        for (int i = 0; i < 400; i++) line += "\xC4\x8a";
        line += "\xC4";   // 截断点（原崩溃即在此）
        line += "...\n";
        std::vector<jchar> got = decode(line);
        size_t want_units = pre.size() + 400 + 1 + 4; // ASCII 1:1 + 400 个 Ċ + FFFD + "...\n"
        if (g_env.new_string_calls != 1) { ++g_fail; printf("FAIL long line path\n"); }
        else if (got.size() != want_units) { ++g_fail; printf("FAIL long line len=%zu want=%zu\n", got.size(), want_units); }
        else printf("ok   long log line (%zu bytes -> %zu units)\n", line.size(), got.size());
        if (got.size() < 4 || got[got.size()-1] != '\n' || got[got.size()-2] != '.' ||
            got[got.size()-3] != '.' || got[got.size()-4] != '.') {
            ++g_fail; printf("FAIL long line tail lost\n");
        } else printf("ok   long line '...' + newline preserved\n");
        // 前缀必须仍是那行英文日志（证明没被整体替换成乱码）
        bool prefix_ok = got.size() > 20 && got[0] == 'l' && got[1] == 'l' && got[2] == 'a' && got[3] == 'm' && got[4] == 'a';
        if (!prefix_ok) { ++g_fail; printf("FAIL long line prefix broken\n"); }
        else printf("ok   long line ascii prefix intact\n");
    }

    // 15 fuzz：任意字节 + guard page，确认绝不 OOB 读、绝不产出孤立代理对
    {
        srand(20260913);
        const int kIters = 200000;
        size_t total_units = 0;
        bool abort_early = false;
        for (int it = 0; it < kIters && !abort_early; it++) {
            int n = rand() % 24;
            std::string s;
            for (int i = 0; i < n; i++) {
                int r = rand() % 10;
                int b;
                if (r < 4)      b = rand() % 256;                          // 任意
                else if (r < 7) b = 0xC0 + rand() % 0x40;                  // lead：截断高发
                else if (r < 9) b = 0x80 + rand() % 0x40;                  // continuation
                else            b = 0x20 + rand() % 0x40;                   // ASCII
                s.push_back((char)(unsigned char) b);                       // 注意：不含 \0，交由 c_str
            }
            if (s.find('\0') != std::string::npos) continue;
            std::vector<jchar> got = decode(s);
            total_units += got.size();
            for (size_t i = 0; i < got.size(); i++) {
                jchar c = got[i];
                if (c >= 0xD800 && c <= 0xDBFF) {
                    if (i + 1 >= got.size() || got[i+1] < 0xDC00 || got[i+1] > 0xDFFF) {
                        ++g_fail; printf("FAIL fuzz unpaired high surrogate (seed iter %d)\n", it);
                        abort_early = true; break;
                    }
                    i++;
                } else if (c >= 0xDC00 && c <= 0xDFFF) {
                    ++g_fail; printf("FAIL fuzz lone low surrogate (iter %d)\n", it);
                    abort_early = true; break;
                }
            }
        }
        printf("ok   fuzz 200000 cases via guard page (%zu code units total)\n", total_units);
    }

    printf("\n%s\n", g_fail ? "=== FAILED ===" : "=== ALL PASSED ===");
    return g_fail ? 1 : 0;
}
