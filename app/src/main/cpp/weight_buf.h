// weight_buf.h — 从 llama.cpp 的 `load_tensors: <设备> model buffer size = X MiB`
// 行里抽出「落点 → 大小」的纯函数。
//
// 为什么抽出来：这段解析在**日志回调**里跑（只在一次加载窗口内），解析错了
// 不会崩，只会让 `[权重落点]` 打出一行**看起来完全合理**的假读数 —— 而它正是
// 跨轮比较「省没省内存」时唯一可信的那把尺子（`[repack结果]` 的 RSS 差跨轮
// 不可比，见 HTP-STATUS §111.5）。假读数比没有读数更坏，所以这里不靠肉眼，
// 由宿主单测 tools/weight_buf_test.cpp 逐条钉（用真机编进去的同一份头文件）。
//
// 输入形状（库的实际输出，`text` 可能一次带多行）：
//   "load_tensors:   CPU_Mapped model buffer size =   377.71 MiB\n"
//   "load_tensors:   CPU_REPACK model buffer size =   223.12 MiB\n"
//   "load_tensors:       OpenCL model buffer size =   380.95 MiB\n"
#pragma once

#include <cstddef>
#include <cstdio>    // std::snprintf
#include <cstdlib>   // std::strtod
#include <string>
#include <vector>

struct WeightBufEntry {
    std::string dev;    // "CPU_Mapped" / "CPU_REPACK" / "OpenCL" / …（空则 "?"）
    std::string size;   // 原样保留（含单位），显示用
    double      mib;    // 数值，合计用（库统一打 MiB）
};

namespace weight_buf_detail {

inline void trim_left(std::string & s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.erase(0, 1);
}

inline void trim_right(std::string & s) {
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t' || s.back() == '\r'))
        s.pop_back();
}

}   // namespace weight_buf_detail

// 解析一段日志文本；最多返回 max_entries 条（0 = 不限）。
// 只认带 "model buffer size" 的行；其余行原样跳过（不是该行就**不要猜**）。
inline std::vector<WeightBufEntry> weight_buf_parse(const char * text, std::size_t max_entries) {
    using namespace weight_buf_detail;
    std::vector<WeightBufEntry> out;
    if (!text || !*text) return out;

    const std::string all(text);
    std::size_t pos = 0;
    while (pos < all.size()) {
        if (max_entries && out.size() >= max_entries) break;
        std::size_t end = all.find('\n', pos);
        if (end == std::string::npos) end = all.size();
        const std::string line = all.substr(pos, end - pos);
        pos = end + 1;

        const std::size_t m = line.find("model buffer size");
        if (m == std::string::npos) continue;
        const std::size_t eq = line.find('=', m);
        if (eq == std::string::npos) continue;

        std::string val = line.substr(eq + 1);
        trim_left(val);
        trim_right(val);
        if (val.empty()) continue;

        // 设备名 = 行首（去掉 "load_tensors:" 之类的前缀）到 " model buffer size" 之前。
        // 前缀按**第一个冒号**切：只在这一段形状里成立，所以只对带该短语的行用。
        std::size_t s = 0;
        const std::size_t colon = line.find(':');
        if (colon != std::string::npos && colon < m) s = colon + 1;
        while (s < m && (line[s] == ' ' || line[s] == '\t')) ++s;
        std::string dev = line.substr(s, m - s);
        trim_right(dev);

        WeightBufEntry e;
        e.dev  = dev.empty() ? "?" : dev;
        e.size = val;
        e.mib  = std::strtod(val.c_str(), nullptr);
        out.push_back(e);
    }
    return out;
}

// 追加进固定容量槽位的账本。<capacity>/<used> 都以“槽位”为单位，
// <dst> 必须至少有 capacity 个槽位；写入从 dst[used] 开始。
//
// 关键边界：used >= capacity 时**一条也不写**，返回 0。
// 不能把这个 0 继续传给 weight_buf_parse —— 那里 0 表示“不限条数”，
// 0.9.308–0.9.313 的 `[权重落点]` 越界正是这么来的：8 个槽位写满后
// room==0，日志回调却继续把第 9 条起写进相邻全局量（真机日志出现过 21 条）。
// 这段放在头文件里，是为了让宿主单测跑**同一份**追加逻辑，而不是复刻品。
inline constexpr std::size_t kWeightBufSlotBytes = 96;

inline std::size_t weight_buf_append(const char * text, std::size_t capacity,
                                     char (*dst)[kWeightBufSlotBytes], std::size_t used,
                                     double & mib, double & gpu_mib, bool & known) {
    if (used >= capacity) return 0;
    const std::size_t room = capacity - used;   // 明确 > 0，避免 0=不限 的语义陷阱
    std::size_t n = 0;
    for (const WeightBufEntry & e : weight_buf_parse(text, room)) {
        std::snprintf(dst[used + n], kWeightBufSlotBytes, "%s=%s",
                      e.dev.c_str(), e.size.c_str());
        ++n;
        mib += e.mib;
        known = true;
        // 非 CPU 落点 = 加速器组（OpenCL/Vulkan/Metal/HTP…）。统一内存下它同样是
        // 物理内存，只是不进 VmRSS。
        if (e.dev.compare(0, 3, "CPU") != 0) gpu_mib += e.mib;
    }
    return n;
}
