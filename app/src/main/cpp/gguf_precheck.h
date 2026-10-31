// gguf_precheck.h —— GGUF 预读前的**纯逻辑**文件头检查（宿主可编）。
//
// 为什么单独拆一份（与 probe_util.h / probe_flag.h / stop_sequences.h 同样的理由）：
// 它是"哪些文件不交给库去解析"的唯一判据，值得有单测钉住，而不是靠 review 肉眼保证。
// 真机那边 llama_jni.cpp 直接 include 这份原件 —— 单测跑的判据与真机跑的是同一份，
// 不会出现"另写一份近似去验证另一份近似"的判据漂移。
//
// 背景（Issue #238）：`lm_gguf_init_from_file(no_alloc=true)` 并不只是"读 header"，
// 它会把**整份元数据**分配出来（含 tokenizer 的十几万条词表字符串），
// 走几十万次 malloc/free。而这一次调用发生在 `nativeProbeGguf`（一个纯装饰性的
// 量化预读）里，跑在 `Thread { ... }.start()` 的裸 Java 线程上 ——
// native 段错误没有任何 Java 层 try/catch 接得住，进程直接没。
//
//   真机现场（tombstone_03）：
//     #00 libc arena_run_heap_remove+888        ← arena 元数据链被解引用
//     #05 libc je_malloc → #06 operator new
//     #10 librnllama lm_gguf_init_from_file_ptr+228
//     #12 libllmjni  Java_..._nativeProbeGguf+172
//   寄存器里 151936（=0x25180，Qwen3 词表）出现两次 —— 正是那轮词表 malloc。
//
// 本守卫的取舍：**宁可预读失败，也不让预读有机会把进程带走**。
// 判据只读前 24 字节 + 文件尺寸，不解析、不分配、不碰堆。
// ⚠ 它**不能证明文件一定合法**（合法 GGUF 也可能被截断），
//   只把"明显不是 GGUF / 明显不完整 / 头部计数明显荒谬"这一档挡在外面。

#ifndef GGUF_PRECHECK_H
#define GGUF_PRECHECK_H

#include <cstdint>
#include <cstdio>
#include <string>

// 纯逻辑部分：从已读到的头部字节判"能不能交给库"。
//   hdr  —— 文件开头至少 24 字节（不足时 got 传实际读到的字节数）
//   got  —— 实际读到的字节数
//   size —— 文件总尺寸（未知传 -1）
// 返回 true = 允许继续预读；false = why 里写明原因。
//
// 单独抽出来的理由：真机侧要读文件（open/pread/fstat），单测侧要构造各种畸形头部 ——
// 两者只有这一层是共同的，把 IO 留在外面，判据本身就能在宿主上跑。
static inline bool gguf_header_check(const unsigned char * hdr, long long got,
                                     long long size, std::string * why) {
    auto fail = [&](const char * m) -> bool { if (why) *why = m; return false; };
    if (got < 8) return fail("文件头读不满 8 字节（文件为空或被截断）");
    // magic 小端 "GGUF"
    if (!(hdr[0] == 'G' && hdr[1] == 'G' && hdr[2] == 'U' && hdr[3] == 'F'))
        return fail("魔数不是 GGUF");
    if (got >= 24) {
        const uint32_t ver = (uint32_t) hdr[4] | ((uint32_t) hdr[5] << 8) |
                             ((uint32_t) hdr[6] << 16) | ((uint32_t) hdr[7] << 24);
        if (ver < 2 || ver > 3) {
            if (why) *why = "GGUF 版本 " + std::to_string(ver) + " 不在支持范围（2/3）";
            return false;
        }
        // n_tensors / n_kv：**只做合理性上界**，不当合法性判据。
        // 真值是"元数据里真有多少条"，本函数不解析。这里挡的是"指针层面明显荒谬"——
        // 库会按这两个数去分配数组，一个被写坏的巨大值就是一次必然失败的巨型 malloc。
        uint64_t n_tensors = 0, n_kv = 0;
        for (int i = 0; i < 8; i++) n_tensors |= ((uint64_t) hdr[8 + i]) << (8 * i);
        for (int i = 0; i < 8; i++) n_kv      |= ((uint64_t) hdr[16 + i]) << (8 * i);
        const uint64_t kMaxTensors = 1ull << 20;   // 100 万张量：任何真实模型都远低于此
        const uint64_t kMaxKv      = 1ull << 22;   // 400 万条 kv：同上
        if (n_tensors == 0 || n_tensors > kMaxTensors) {
            if (why) *why = "张量数 " + std::to_string(n_tensors) + " 不合理";
            return false;
        }
        if (n_kv > kMaxKv) {
            if (why) *why = "元数据条数 " + std::to_string(n_kv) + " 不合理";
            return false;
        }
    }
    if (size >= 0 && size < 1024) return fail("文件小于 1 KiB，不可能是有效 GGUF");
    return true;
}

#endif // GGUF_PRECHECK_H
