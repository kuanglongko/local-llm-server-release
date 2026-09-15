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
// 单测见 tools/utf8_safe_test.cpp（与本头文件共用同一实现）。
#pragma once

#include <jni.h>
#include <cstring>
#include <string>
#include <vector>

static inline jstring new_string_utf8_safe(JNIEnv * env, const char * str) {
    if (!str) return env->NewStringUTF("");
    const size_t len = strlen(str);
    if (len == 0) return env->NewStringUTF("");

    const unsigned char * s = (const unsigned char *) str;
    std::vector<jchar> out;
    out.reserve(len); // 最坏情况 1 字节 -> 1 个 code unit

    auto push = [&out](uint32_t cp) {
        if (cp < 0x10000u) {
            out.push_back((jchar) cp);
        } else {
            cp -= 0x10000u;
            out.push_back((jchar) (0xD800u + (cp >> 10)));
            out.push_back((jchar) (0xDC00u + (cp & 0x3FFu)));
        }
    };

    size_t i = 0;
    while (i < len) {
        unsigned char c = s[i];
        if (c < 0x80) { push(c); i++; continue; } // ASCII 快速路径

        uint32_t need, cp;
        if      ((c & 0xE0) == 0xC0) { need = 1; cp = c & 0x1Fu; }
        else if ((c & 0xF0) == 0xE0) { need = 2; cp = c & 0x0Fu; }
        else if ((c & 0xF8) == 0xF0) { need = 3; cp = c & 0x07u; }
        else { push(0xFFFDu); i++; continue; } // 非法首字节 / 多余的后续字节

        if (i + need >= len) { push(0xFFFDu); i++; continue; } // 序列被截断

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
            i++; // 只前进 1 字节，尽量恢复其后内容
            continue;
        }
        push(cp);
        i += need + 1;
    }
    if (out.empty()) return env->NewStringUTF("");
    return env->NewString(out.data(), (jsize) out.size());
}
