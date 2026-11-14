// utf8_safe.h — 把 C 层字节串安全地交给 Java
//
// 背景：llama.cpp 打印 tokenizer 词表等日志时按**字节**截断（substr + "..."），
// 会把一个多字节序列切成一半，触发如下 JNI 崩溃栈（含 Ċ 这类 token 的词表尤易命中）：
// JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
// illegal continuation byte 0x2e
// string: 'llama_model_loader: - kv 24: tokenizer.ggml.tokens arr[str,128000] = ["\xc4\x8a...\xc4 .'
// in call to NewStringUTF
// 末尾 0xc4 紧跟 "..."(0x2e) 正是 Ċ 的首字节被截断。CheckJNI（debuggable 应用默认
// 开启）判为非法 Modified UTF-8 直接 SIGABRT。同理 char desc[256] 按字节截断会让
// 中文模型名在 nativeModelDesc 处崩溃；模型输出里的孤立代理对会击穿 nativeStep。
//
// 做法：手工解码 UTF-8 -> UTF-16，严格拒绝 overlong / 孤立代理对 / 越界 / 截断
// 序列，非法字节替换为 U+FFFD，再经 NewString(jchar*) 回传。不走 Modified UTF-8
// 校验路径，任何脏输入都不会 abort。
//
// 返回值按字节数分两档，**为空必须按空档走**：
//   · 空（len == 0，含 str == nullptr）-> `env->NewStringUTF("")`；
//   · 非空                              -> `env->NewString(...)`。
// "是不是空"的判据在 C++ 侧，**调用方不得在 Kotlin 侧再判一次**：那边拿到的是
// jstring，要么再解成 String 比一次（白解一遍），要么用 `?: ""` 把它当成 null ——
// 后者是编译错（`nativeModelDesc()` 声明为非空 String），2026-09-20 CI 就是这么挂的。
//
// 单测见 tools/utf8_safe_test.cpp（与本头文件共用同一实现）。
#pragma once

#include <jni.h>
#include <cstring>
#include <string>
#include <vector>

// ══════════════════════════════════════════════════════════════════════════
// 解码逻辑（本文件与 llama_jni.cpp 的**唯一**一处）
// ══════════════════════════════════════════════════════════════════════════
// `llama_jni.cpp` 的渲染出口要把"生成后缀占多少 UTF-16 code unit"写进回传格式的
// 长度段，而宿主据此切串 —— 那个数必须等于**本函数实际发出的这一串**的长度。
//
// 上一版是两份各自实现的计数器，于是对非法序列算出不同的 code unit 数：
// overlong `C0 80` 计数侧记 1、本文件发出侧记 2（这里每次只前进 1 字节就重新解码），
// CESU-8 `ED A0 80` 记 1 vs 3。合法 UTF-8 下两边恒等，所以真机常见模型（后缀纯
// ASCII）全无症状；只有第三方 GGUF 模型自带的模板里含非法字节时才错位 ——
// 典型的"只在某些模型上才错"的静默故障。
//
// 收口方式不是"再补一条对齐用例"，而是**解码只此一处**：
//   · 本文件 → `utf8_decode_each` 建 `jchar` 串（`out` 的 push 是唯一的 push）；
//   · `utf16_len`（llama_jni.cpp）→ 同一个 `utf8_decode_each`，push 只累加计数。
// 两处若再长出第二个实现，`tools/run_render_tail_tests.sh` 的 50 万例随机字节
// 逐字节对照会直接红。
static inline void utf8_push_code_unit(std::vector<jchar> & out, uint32_t cp) {
    if (cp < 0x10000u) {
        out.push_back((jchar) cp);
    } else {
        cp -= 0x10000u;
        out.push_back((jchar) (0xD800u + (cp >> 10)));
        out.push_back((jchar) (0xDC00u + (cp & 0x3FFu)));
    }
}

// 解码结果：是否出现过非法字节 / overlong / 孤立代理对 / 截断（都按 U+FFFD 计）。
// 调用方目前只用它做诊断，解码行为不因它而变 —— 保持"任何脏输入都不 abort"这条底线。
enum class Utf8Decode {
    kClean    = 0,
    kRepaired = 1,
};

template <typename PushFn>
static inline Utf8Decode utf8_decode_each(const char * str, size_t len, PushFn push) {
    const unsigned char * s = (const unsigned char *) str;
    bool repaired = false;
    size_t i = 0;
    while (i < len) {
        unsigned char c = s[i];
        if (c < 0x80) { push((uint32_t) c); i++; continue; } // ASCII 快速路径

        uint32_t need, cp;
        if      ((c & 0xE0) == 0xC0) { need = 1; cp = c & 0x1Fu; }
        else if ((c & 0xF0) == 0xE0) { need = 2; cp = c & 0x0Fu; }
        else if ((c & 0xF8) == 0xF0) { need = 3; cp = c & 0x07u; }
        else { push(0xFFFDu); repaired = true; i++; continue; } // 非法首字节 / 多余的后续字节

        if (i + need >= len) { push(0xFFFDu); repaired = true; i++; continue; } // 序列被截断

        bool ok = true;
        for (uint32_t k = 1; k <= need; k++) {
            unsigned char cc = s[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        const bool overlong =
            (need == 1 && cp < 0x80u)     ||
            (need == 2 && cp < 0x800u)    ||
            (need == 3 && cp < 0x10000u);
        const bool in_range = ok && cp <= 0x10FFFFu;
        const bool surrogate = (cp >= 0xD800u && cp <= 0xDFFFu);
        if (!in_range || surrogate || overlong) {
            push(0xFFFDu);
            repaired = true;
            i++; // 只前进 1 字节，尽量恢复其后内容
            continue;
        }
        push(cp);
        i += need + 1;
    }
    return repaired ? Utf8Decode::kRepaired : Utf8Decode::kClean;
}

static inline jstring new_string_utf8_safe(JNIEnv * env, const char * str) {
    if (!str) return env->NewStringUTF("");
    const size_t len = strlen(str);
    if (len == 0) return env->NewStringUTF("");

    std::vector<jchar> out;
    out.reserve(len); // 最坏情况 1 字节 -> 1 个 code unit

    utf8_decode_each(str, len, [&out](uint32_t cp) { utf8_push_code_unit(out, cp); });

    if (out.empty()) return env->NewStringUTF("");
    return env->NewString(out.data(), (jsize) out.size());
}
