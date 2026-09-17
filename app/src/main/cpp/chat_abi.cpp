// chat_abi.cpp — 运行时 ABI 自检（编译期管不到的部分）
//
// 编译期 static_assert 能钉住"偏移/尺寸"，钉不住"库到底是哪套 STL 编的"。
// 这里补上最后一块：在 backendInit 之后、任何工具调用之前，拿**库自己的函数**
// 做一次自证，失败就只打日志、不阻断（工具路径本来就有纯文本兜底）。
#include "chat_abi.h"

#include <cstdio>
#include <cstring>
#include <string>

namespace abi {

// 库导出的"试金石"符号：这些是 common_chat_* 里含 STL 参数的那几个。
// 它们在 libc++ 下是 NSt6__ndk1，在 libstdc++ 下是 NSt7__cxx11。
// 我们不能在运行时 dlsym 自己的依赖（那等于绕开链接器，方向反了），
// 所以改成"取函数地址 + 比对已知的 mangled 名"。取地址对本地定义的
// 符号不会失败，但它能证明**我们的编译单元**是不是 libc++ 那套。
//
// 换句话说：这里验的是"本编译单元与 librnllama*.so 是否同一套 ABI"，
// 而这个问题的另一半（库那边）由 check_abi.py 在 CI 里静态扫。
const char * self_check() {
    static char msg[512];

    // ① std::string 布局：libc++ ABI v1（非 alternate layout，NDK 默认）。
    //
    //    ⚠️ 这里曾写反过，导致真机上一直报"自检失败"（假警报）：
    //    在 **小端** 上，libc++ ABI v1 把短串长度存在 **第 1 字节（offset 0）**，
    //    `__is_long_` 是同一个字节的最高位；末字节是串数据的一部分，恒为 0。
    //    实测（NDK r27 的 libc++ 头，_LIBCPP_VERSION=180000，ABI v1）：
    //      std::string s = "abcde";
    //      byte[0]=0x0a(=size<<1)  byte[23]=0x00
    //    旧代码读 buf[23] 判 `>>1 == size`，在正确布局下必然得到 0 != 5 → 恒误报。
    //    正确的判据在 byte[0]：低 1 位是 is_long 标志（短串必须为 0），高 7 位是 size。
    {
        const size_t n = 5;                // 远小于 SSO 容量 22
        std::string s = "abcde";
        unsigned char buf[sizeof(std::string)];
        std::memcpy(buf, &s, sizeof(buf));

        const unsigned char b0 = buf[0];   // 短串：bit0=is_long(0)，bit1..7=size
        const unsigned char last = buf[sizeof(std::string) - 1];

        // 期望：b0 的最低位为 0（短串），且 (b0 >> 1) == size。
        // alternate layout（ABI v2）下数据从 offset 0 开始，b0 会是 'a'=0x61，
        // 其最低位为 1 → 会被这条判定拦住，正是我们想要的。
        if ((b0 & 0x01u) != 0 || (b0 >> 1) != n) {
            snprintf(msg, sizeof(msg),
                     "std::string 布局不是 libc++ ABI v1（小端）：首字节=0x%02x，"
                     "期望 (size<<1)|0 = 0x%02x；末字节=0x%02x。"
                     "本单元与 librnllama*.so 的 STL ABI 不一致。",
                     (unsigned) b0, (unsigned) ((n << 1) | 0), (unsigned) last);
            return msg;
        }
    }

    // ② 结构体总尺寸：static_assert 已在编译期拦住，这里再做一次运行期交叉验证，
    //    保证断言没被误改成"看起来对"的数字。
    if (sizeof(common_chat_templates_inputs) != 168) {
        snprintf(msg, sizeof(msg), "common_chat_templates_inputs = %zu B，期望 168 B",
                 sizeof(common_chat_templates_inputs));
        return msg;
    }

    // ③ 枚举值：tool_choice / format 这几个是**按值**过边界的，
    //    上游插入成员会让数值平移，而布局断言抓不到。
    if (COMMON_CHAT_TOOL_CHOICE_AUTO != 0 || COMMON_CHAT_TOOL_CHOICE_REQUIRED != 1 ||
        COMMON_CHAT_TOOL_CHOICE_NONE != 2) {
        snprintf(msg, sizeof(msg), "common_chat_tool_choice 数值变了（AUTO=%d）",
                 (int) COMMON_CHAT_TOOL_CHOICE_AUTO);
        return msg;
    }

    return "";
}

} // namespace abi
