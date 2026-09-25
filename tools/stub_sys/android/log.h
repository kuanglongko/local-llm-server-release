// 宿主侧 `android/log.h` 存根，只用于静态语法检查（不参与 Android 构建）。
//
// `llama_jni.cpp` include 它取三个东西：`ANDROID_LOG_INFO` / `ANDROID_LOG_ERROR`
// 两个优先级常量，与 `__android_log_print` 的声明。宿主上没有 NDK，但有它之后
// **真源码本体**（不是抽取片段）就能在宿主侧过一遍编译器 —— 这是本仓库唯一能
// 拦住「定义在调用点之后」「签名与前置声明不一致」这类错的通路（见
// `tools/run_static_order_guard.sh`）。
//
// ⚠ 只声明不定义：本文件只参与 `-fsyntax-only`，不链接。定义会掩盖"某个日志宏
//   在真机上是否真被调用"这件事没法查 —— 但那不是这层的职责。
#pragma once

enum {
    ANDROID_LOG_INFO  = 4,
    ANDROID_LOG_DEBUG = 3,
    ANDROID_LOG_WARN  = 5,
    ANDROID_LOG_ERROR = 6,
};

int __android_log_print(int prio, const char * tag, const char * fmt, ...);
