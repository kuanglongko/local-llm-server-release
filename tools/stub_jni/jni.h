// 宿主侧 JNI stub，仅用于 tools/utf8_safe_test.cpp 单测（不参与 Android 构建）
// 提供 utf8_safe.h 需要的最小类型与两个被调用的方法，并把结果捕获给测试断言。
#pragma once
#include <cstdint>
#include <string>
#include <vector>

typedef int32_t  jsize;
typedef uint16_t jchar;
struct _jstring;
typedef _jstring * jstring;

struct JNIEnv {
    std::vector<jchar> u16;   // NewString 捕获
    std::string        lit;   // NewStringUTF 捕获
    int                new_string_calls = 0;
    int                new_string_utf_calls = 0;

    jstring NewString(const jchar * chars, jsize len) {
        ++new_string_calls;
        u16.assign(chars, chars + (len > 0 ? (size_t) len : 0));
        return (jstring) this;
    }
    jstring NewStringUTF(const char * s) {
        ++new_string_utf_calls;
        lit = (s ? s : "");
        u16.clear();
        return (jstring) this;
    }
};
