// 宿主侧 JNI stub，仅用于宿主单测与静态语法检查（不参与 Android 构建）。
//
// 为什么要有它：`llama_jni.cpp` 在宿主上编不过的**唯一**原因是缺 NDK 的 jni.h /
// android/log.h（真头文件与 libc++ 一起装在 NDK 里，Runner 没有）。这一层不是
// 「复刻一份 native 逻辑」—— 它就是 JNI 的**接口形状**：类型别名与方法签名，
// 没有任何行为（方法体全是空/常量返回，只用于让编译器完成重载解析与语义检查）。
//
// ⚠ 存根的面积必须**刚好**是本文件真正用到的那些成员：多写一个不会发现任何问题，
//   少写一个则真源码编不过（这时把成员补上，而不是把检查关掉 —— 宿主侧真编一遍
//   是本仓库唯一能拦住「定义在调用点之后」「签名不一致」这类错的通路）。
//
// 两个使用方：
//   · `tools/run_utf8_tests.sh`（tools/utf8_safe_test.cpp）：**带行为**的成员
//     （NewString/NewStringUTF）把结果捕获给断言用；
//   · `tools/run_static_order_guard.sh`：对真源码做 `-fsyntax-only`。
#pragma once
#include <cstdint>
#include <string>
#include <vector>

typedef int32_t  jsize;
typedef uint16_t jchar;
typedef int32_t  jint;
typedef uint8_t  jboolean;
typedef int64_t  jlong;
typedef float    jfloat;
typedef double   jdouble;
typedef int8_t   jbyte;

// 真 jni.h 里 `jobject` / `jstring` / `jclass` / `jarray` 全是指向同一个不透明
// `_jobject` 的 typedef（C 没有继承，Java 对象在 JNI 边界就是这个统一类型）。
// 这里必须**照同一形状**写：各写一个独立 struct 的话，真源码里合法的
// `env->DeleteLocalRef(jstring)` 会在宿主侧假红 —— 那不是源码的问题，是桩写歪了，
// 而假红会让人把整套检查 `|| true` 掉（比没有更坏）。
struct _jobject;
typedef _jobject * jobject;
typedef _jobject * jstring;
typedef _jobject * jclass;
typedef _jobject * jarray;
typedef _jobject * jobjectArray;
typedef _jobject * jintArray;
typedef _jobject * jthrowable;
struct _jmethodID;
typedef _jmethodID * jmethodID;
struct _jfieldID;
typedef _jfieldID * jfieldID;

#define JNI_FALSE 0
#define JNI_TRUE 1
#define JNI_VERSION_1_6 0x00010006
#define JNI_OK 0
#define JNIEXPORT
#define JNICALL

// JNI 方法签名在 C++ 里是**虚成员**（C 里是函数指针表）。形状一致，故重载解析、
// 参数个数/类型、返回类型都能被编译器查出来；调用点写错形参就在这里红。
struct JNIEnv {
    std::vector<jchar> u16;   // NewString 捕获（单测断言用）
    std::string        lit;   // NewStringUTF 捕获（单测断言用）
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

    // ---- 以下为 llama_jni.cpp 语法检查所需（面积 = 真源码用到的全部成员）----
    jint  GetVersion() { return JNI_VERSION_1_6; }
    jclass FindClass(const char *) { return nullptr; }
    jclass GetObjectClass(jobject) { return nullptr; }
    jmethodID GetMethodID(jclass, const char *, const char *) { return nullptr; }
    jobject NewGlobalRef(jobject o) { return o; }
    void  DeleteGlobalRef(jobject) {}
    void  DeleteLocalRef(jobject) {}
    void  CallVoidMethod(jobject, jmethodID, ...) {}
    void  ExceptionClear() {}
    jobjectArray NewObjectArray(jsize, jclass, jobject) { return nullptr; }
    void  SetObjectArrayElement(jobjectArray, jsize, jobject) {}
    jobject GetObjectArrayElement(jobjectArray, jsize) { return nullptr; }
    jintArray NewIntArray(jsize) { return nullptr; }
    void  SetIntArrayRegion(jintArray, jsize, jsize, const jint *) {}
    jsize GetArrayLength(jarray) { return 0; }
    const char * GetStringUTFChars(jstring, jboolean *) { return ""; }
    void  ReleaseStringUTFChars(jstring, const char *) {}
};

// 真 jni.h 的 JavaVM 里 AttachCurrentThread 有一份 C++ 重载
// （`jint AttachCurrentThread(JNIEnv **, void *)`）。这里保留同一形状：
// 写成 `void *` 会让真源码的调用点在宿主侧假红（"is not a structure"）。
struct JavaVM {
    jint AttachCurrentThread(JNIEnv ** p_env, void *) { if (p_env) *p_env = nullptr; return JNI_OK; }
};


