#!/usr/bin/env python3
"""从真源码**逐字抽取**渲染出口相关的实现，生成宿主测用的 .inc。

为什么是抽取而不是维护副本：副本会随源码漂移，那时测的是一份没人看的旧代码。
本脚本与源码形状绑定 —— 改函数名/签名时这里会直接失败（这正是想要的）。

用法：extract.py <llama_jni.cpp> <utf8_safe.h> <probe_util.h> <ThinkStream.kt> <out.inc>
"""
import re
import sys

# `--old <dir>`：从 dir 下的四个**旧版**文件抽取，产出对照用的 .inc。
# 用途：宿主测里对同一组输入跑旧实现，断言"旧写法**确实**会红" ——
# 一条只会绿的断言测不出任何东西（本项目反复强调这一点）。
mode_old = len(sys.argv) > 6 and sys.argv[1] == "--old"
if mode_old:
    src, hdr, pu, kt, out = sys.argv[2:7]
else:
    src, hdr, pu, kt, out = sys.argv[1:6]
SRC = open(src, encoding='utf-8').read()
HDR = open(hdr, encoding='utf-8').read()
PU = open(pu, encoding='utf-8').read()
KT = open(kt, encoding='utf-8').read()


def body(s, sig, label):
    """从**定义**处抓函数体。签名后面 400 字符内出现 `) {` 才算定义（跳过前置声明）。"""
    i = -1
    while True:
        try:
            i = s.index(sig, i + 1)
        except ValueError:
            sys.exit("抽取失败：源码里找不到定义 `%s`（%s）—— 函数改名/签名改了？"
                     "本脚本与源码形状绑定，请一起更新" % (sig, label))
        if ') {' in s[i:i + 400]:
            break
    j = s.index('\n}\n', i) + 3
    return s[i:j]


def span(s, start_sig, end_sig, label):
    try:
        i = s.index(start_sig)
    except ValueError:
        sys.exit("抽取失败：找不到 `%s`（%s）" % (start_sig, label))
    j = s.index(end_sig, i) + len(end_sig)
    return s[i:j]


# ── F-1：解码器（本体在 utf8_safe.h）与计数侧（llama_jni.cpp 的 utf16_len）──
if mode_old:
    # 旧版 utf8_safe.h 里没有具名解码器：整段 `new_string_utf8_safe` 就是发出侧，
    # 但依赖 jni.h。宿主里由宿主测复刻它的 push 语义（`old_issue_side`），
    # 计数侧（旧 `utf16_len`）才是"另一份实现" —— 对照的就是这两者。
    push_fn = ""
    enum_utf8 = ""
    dec = ""
else:
    push_fn = body(HDR, "static inline void utf8_push_code_unit", "F-1 发出侧 push")
    enum_utf8 = span(HDR, "enum class Utf8Decode", "\n};\n", "F-1 解码结果枚举")
    dec = body(HDR, "template <typename PushFn>\nstatic inline Utf8Decode utf8_decode_each",
               "F-1 共享解码器")
utf16 = body(SRC, "static size_t utf16_len(const std::string & s) {", "F-1 计数侧")

if mode_old:
    # 旧版：utf16_len 自己数、判据自己写。这里不设断言（就是对旧形状做对照）。
    win_cpp = None
    win_kt = None
    tail_impl = ""
else:
    assert "utf8_decode_each(s.data()" in utf16, (
        "抽取失败：utf16_len 没有走共享解码器 —— F-1 的修复被改回去了")

# ── F-2：判据本体（probe_util.h）与它的转发（llama_jni.cpp）──────────────
if mode_old:
    # 旧版：C++ 判据在 llama_jni.cpp 里（自带 enum + rfind(OPEN)），
    # Kotlin 窗口是 **UTF-16 code unit**（RenderedPrompt/ThinkingControl 里的 TAIL_WINDOW）。
    i0 = SRC.index("enum class RenderedThinkShape")
    i1 = SRC.index("\n}\n", SRC.index("static RenderedThinkShape classify_rendered_think_tail")) + 3
    tail_impl = SRC[i0:i1]
    win_cpp = re.search(r'kGenPromptTailWindow = (\d+)', SRC)
    win_kt = re.search(r'TAIL_WINDOW = (\d+)', KT)
    if not win_cpp or not win_kt:
        sys.exit("旧版抽取失败：找不到 kGenPromptTailWindow / TAIL_WINDOW")
else:
    assert "return classify_think_tail(prompt);" in SRC, (
        "抽取失败：llama_jni.cpp 没有调用共享判据 —— F-2 的修复被改回去了")
    i0 = PU.index("enum class ThinkTailShape")
    i1 = PU.index("\n}\n", PU.index("static inline ThinkTailShape classify_think_tail")) + 3
    tail_impl = PU[i0:i1]
    win_cpp = re.search(r'kThinkTailWindowBytes = (\d+)', PU)
    win_kt = re.search(r'TAIL_WINDOW_BYTES = (\d+)', KT)
    if not win_cpp or not win_kt:
        sys.exit("抽取失败：找不到两侧窗口常量（C++ kThinkTailWindowBytes / Kotlin TAIL_WINDOW_BYTES）")
    if win_cpp.group(1) != win_kt.group(1):
        sys.exit("抽取失败：两侧窗口不一致 C++=%s Kotlin=%s —— 这就是 F-2 本身"
                 % (win_cpp.group(1), win_kt.group(1)))

header = '''// 由 tools/render_tail/extract.py 从真源码**逐字抽取**生成，勿手改。
//   计数侧   app/src/main/cpp/llama_jni.cpp  的 utf16_len
//   发出侧   app/src/main/cpp/utf8_safe.h    的 utf8_decode_each（new_string_utf8_safe 走的就是它）
//   尾窗口   app/src/main/cpp/probe_util.h   的 classify_think_tail
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
typedef unsigned short jchar;   // 宿主近似（真机是 jni.h 的 jchar = uint16_t）
'''

if mode_old:
    parts = [
        header,
        enum_utf8, push_fn, "", dec, "", utf16, "",
        "// 旧版 C++ 判据（在 llama_jni.cpp 里，自带 enum + rfind）——只用于反例对照。",
        tail_impl, "",
        "",
        "// 旧版 Kotlin 窗口：单位是 **UTF-16 code unit**（这就是 F-2 本身）。",
        "static size_t old_kt_window_bytes() { return %s; }" % win_kt.group(1),
        "",
        "// 旧版 Kotlin 判据：substring(code unit 下标) 取窗口，再找标签。",
        "// 与真源码 `RenderedPrompt.openAtStartForTest` 逐字同构。",
        "// ⚠ 入参是**UTF-16 code unit 序列**（`std::u16string`），不是 UTF-8 字节 ——",
        "// 真源码里 `renderedPrompt.length` 就是 Java 的 code unit 计数，",
        "// 拿 UTF-8 字节去喂它等于把窗口口径又换了一次，测出来的分叉方向是假的。",
        "static int old_kt_classify(const std::u16string & rp) {",
        "    if (rp.empty()) return 0;",
        "    const size_t w = old_kt_window_bytes();",
        "    const std::u16string tail = rp.size() > w ? rp.substr(rp.size() - w) : rp;",
        "    const std::u16string OPEN = u\"<think>\", CLOSE = u\"</think>\";",
        "    const size_t at = tail.rfind(OPEN);",
        "    if (at == std::u16string::npos) return 0;",
        "    if (tail.find(CLOSE, at) != std::u16string::npos) return 2;",
        "    return 1;",
        "}",
        "",
    ]
    # 旧 enum 名是 RenderedThinkShape，把里面的 self-reference 改名以便编译
    # 旧形状与真单元重名：抽出时统一加 `old_` 前缀（枚举档位名保持不变）。
    for i, x in enumerate(parts):
        x = x.replace("static RenderedThinkShape classify_rendered_think_tail(const std::string & prompt) {",
                      "static RenderedThinkShape old_classify_impl(const std::string & prompt) {")
        x = x.replace("static size_t utf16_len(const std::string & s) {",
                      "static size_t old_utf16_len(const std::string & s) {")
        x = x.replace("RenderedThinkShape::", "RenderedThinkShape::")   # 档位名保持
        parts[i] = x
    # 旧 enum 名 `RenderedThinkShape` 与真单元里的 `using` 别名冲突：给旧 enum 换名。
    for i, x in enumerate(parts):
        x = x.replace("enum class RenderedThinkShape {", "enum class OldRenderedThinkShape {")
        x = x.replace("RenderedThinkShape::", "OldRenderedThinkShape::")
        x = x.replace("static RenderedThinkShape old_classify_impl", "static OldRenderedThinkShape old_classify_impl")
        parts[i] = x
else:
    parts = [
        header,
        enum_utf8, push_fn, "", dec, "", utf16, "",
        "// ── F-2 判据本体：probe_util.h 的 classify_think_tail（真机编进去的就是这一份）──",
        tail_impl, "",
        "// llama_jni.cpp 的转发（真机上 new_rendered_prompt / 两条渲染路径走的就是它）。",
        "// 类型别名在这一并复刻（真源码里是 `using RenderedThinkShape = ThinkTailShape;`）。",
        "using RenderedThinkShape = ThinkTailShape;",
        "static RenderedThinkShape classify_rendered_think_tail(const std::string & prompt) {",
        "    return classify_think_tail(prompt);",
        "}",
        "",
        "// Kotlin 侧尾窗口（ThinkStream.TAIL_WINDOW_BYTES），从源码抽出后写在这里。",
        "// 两侧**同值同单位**（脚本已断言相等），不是各写一个 256。",
        "static size_t ktWindowBytes() { return %s; }" % win_kt.group(1),
        "",
    ]

open(out, 'w', encoding='utf-8').write("\n".join(parts))
print("抽取单元已生成：utf16_len / utf8_decode_each / classify_think_tail / 窗口 = %s"
      % win_cpp.group(1))
