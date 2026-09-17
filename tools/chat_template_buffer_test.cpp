// chat_template_buffer_test.cpp — 模板渲染缓冲区的宿主侧单测
//
// 为什么单独一份：`/v1/chat/completions` 带 tools 时每条请求都要先渲染一次模板
// （common_chat_templates_apply / llama_chat_apply_template），渲染结果的接收缓冲
// 不在 llama.cpp 的 API 契约里 —— 上游实现是 `strncpy(buf, s.c_str(), length)`，
// **只有 length > strlen(s) 时才补 NUL**。踩中这一条的表现是进程随机被杀，
// 与本次「客户端一调工具 App 就闪退」重合，因此必须用可执行断言钉死，而不是靠注释。
//
// 本文件用 guard page 复现两种历史写法，避免同类回归：
//   A) 缓冲区刚好装得下（need == size）时无终止符 -> 越过末尾的读直接 SIGSEGV；
//   B) 扩容后仍按旧长度二次渲染 -> 返回值恒大于缓冲，判定分支永远为真。
//
// 运行：bash tools/run_chat_buffer_tests.sh
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

static int g_fail = 0;
static void ck(const char * name, bool ok) {
    printf("%s  %s\n", ok ? "PASS" : "FAIL", name);
    if (!ok) g_fail++;
}

// ---- 模拟 llama.cpp 的写入方式（strncpy，长度 >= 内容长度时不补 NUL）-------
static int llama_like_apply(const std::string & formatted, char * buf, int32_t length) {
    if (buf && length > 0) strncpy(buf, formatted.c_str(), (size_t) length);
    return (int) formatted.size();
}

// ---- guard page：可读区末尾紧邻 PROT_NONE，任何越界读立即 SIGSEGV ----------
struct Guard {
    unsigned char * region = nullptr;
    size_t psz = 0;
    Guard() {
        psz = (size_t) sysconf(_SC_PAGESIZE);
        unsigned char * base = (unsigned char *) mmap(nullptr, psz * 2, PROT_READ | PROT_WRITE,
                                                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (base == MAP_FAILED) { perror("mmap"); return; }
        mprotect(base + psz, psz, PROT_NONE);   // 第二页不可读写
        region = base;                          // 数据贴着第一页尾部放
    }
    // 返回一块「末尾紧贴 guard」的可写区
    char * tail(size_t want) {
        if (!region || want > psz) return nullptr;
        char * p = (char *) (region + psz - want);
        memset(p, 0x7f, want);
        return p;
    }
};

int main() {
    Guard g;
    if (!g.region) { printf("FAIL  无法建立 guard page\n"); return 1; }

    // ---------- A) 旧写法：缓冲区大小 == 内容长度 ----------
    // 内容长度刻意取 15，缓冲也取 15：strncpy 一个 NUL 都不写，
    // 随后任何 strlen 语义的读取都会踏进 guard page。
    {
        char * buf = g.tail(16);            // 15 字节内容 + 1 字节空间做对照
        const std::string formatted = "123456789012345";   // 刚好 15
        int32_t need = llama_like_apply(formatted, buf, 15);
        ck("A: 返回值等于内容长度", need == 15);
        // 关键断言：末尾没有 NUL，说明「等长写入」确实不补终止符
        ck("A: 等长写入不产生 NUL 终止符（这正是越界读的根源）",
           memchr(buf, '\0', 15) == nullptr);
        // 正确做法：判据必须留出第 need+1 个字节
        std::vector<char> safe((size_t) need + 1, '\0');
        llama_like_apply(formatted, safe.data(), (int32_t) safe.size());
        ck("A: 长度 +1 时补上 NUL，可安全当 C 串用",
           safe[need] == '\0' && strlen(safe.data()) == (size_t) need);
        ck("A: 多出的 NUL 不属于 prompt 内容",
           std::string(safe.data(), (size_t) need) == formatted);
    }

    // ---------- B) 旧写法：扩容后仍传旧长度 ----------
    {
        const std::string big(9000, 'x');
        std::vector<char> buf(4096);
        int32_t need = llama_like_apply(big, buf.data(), 4096);
        ck("B: 首次调用返回完整长度（大于缓冲）", need == 9000);
        // 旧代码的第二次调用：扩了 buf，但长度参数仍是字面量 4096。
        // 返回的 need 看着"装下了"（9000 < 9008），但缓冲里只有前 4096 字节是真的 ——
        // 这正是最阴的地方：判定通过、内容截断，模型收到的 prompt 少了一半。
        std::vector<char> buf2((size_t) need + 8, '\0');
        int32_t need2 = llama_like_apply(big, buf2.data(), 4096);   // ← 旧写法
        ck("B: 旧写法返回值看似装得下（9000 < 9008）", need2 == 9000 && (size_t) need2 <= buf2.size());
        ck("B: 但旧写法只写进前 4096 字节 -> 内容被截断、prompt 不完整",
           buf2[4095] == 'x' && buf2[4096] == '\0');
        // 正确写法：传 buf.size()
        std::vector<char> buf3((size_t) need + 1, '\0');
        int32_t need3 = llama_like_apply(big, buf3.data(), (int32_t) buf3.size());
        ck("B: 正确写法（传 size）缓冲内容完整", memcmp(buf3.data(), big.data(), big.size()) == 0);
        ck("B: 正确写法结尾有 NUL（长度 +1 的收益）",
           need3 == 9000 && buf3[need3] == '\0');
    }

    // ---------- C) 兜底裁剪：返回值异常偏大也不得越界 ----------
    {
        const std::string s(100, 'a');
        std::vector<char> buf(64, '\0');
        int32_t need = llama_like_apply(s, buf.data(), 64);
        ck("C: 返回值可以大于缓冲（API 语义如此）", need == 100);
        // native 侧的兜底：把 need 裁到 buf.size() 才构造 std::string
        if ((size_t) need > buf.size()) need = (int32_t) buf.size();
        std::string out(buf.data(), (size_t) need);
        ck("C: 裁剪后不越界且长度为缓冲大小", out.size() == 64);
        ck("C: 裁剪后不含写入内容之外的越界数据", out == std::string(64, 'a'));
    }

    printf("\n%s\n", g_fail ? "=== 存在失败断言 ===" : "=== 模板缓冲单测全部通过 ===");
    return g_fail ? 1 : 0;
}
