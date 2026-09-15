/*
 * CPU 能力探测（供 Kotlin 选择 rnllama 预编译变体）。
 *
 * 为什么单独成库：变体探测必须发生在加载任何 llmjni_* 之前，
 * 若把探测函数放进 llmjni_* 就成了"先有鸡还是先有蛋"。
 * 本库只依赖 libc，体积几 KB，加载零风险。
 *
 * 为什么不用 /proc/cpuinfo：ARM64 的 Features 行只导出 AT_HWCAP 的一部分，
 * i8mm 位于 AT_HWCAP2，很多内核根本不打出来 → 会误判成"不支持"而选到慢变体；
 * 反过来若解析字符串（"asimddp"/"i8mm"）则受内核版本命名差异影响。
 * 因此直接读 getauxval(AT_HWCAP/AT_HWCAP2)，常量取自 Linux arm64 uapi。
 */
#include <jni.h>
#include <sys/auxv.h>

#if defined(__aarch64__)
#  include <asm/hwcap.h>
#endif

#ifndef AT_HWCAP
#define AT_HWCAP 16
#endif
#ifndef AT_HWCAP2
#define AT_HWCAP2 26
#endif
/* Linux arch/arm64/include/uapi/asm/hwcap.h（ABI 冻结，旧内核未定义时同值兜底） */
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)   /* ARMv8.1 DotProd（SDOT/UDOT） */
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1UL << 13)     /* ARMv8.4 I8MM（SMMLA/UMMLA） */
#endif

/* 返回 [dotprod, i8mm, hwcap_raw, hwcap2_raw]；内核不报告时对应位为 0（保守回退）。 */
JNIEXPORT jlongArray JNICALL
Java_com_xiaowan_localinference_LlmEngine_cpuCaps(JNIEnv *env, jclass clazz) {
    (void)clazz;
    unsigned long h1 = getauxval(AT_HWCAP);
    unsigned long h2 = getauxval(AT_HWCAP2);
    jlong out[4];
    out[0] = (h1 & HWCAP_ASIMDDP) ? 1L : 0L;
    out[1] = (h2 & HWCAP2_I8MM) ? 1L : 0L;
    out[2] = (jlong)h1;
    out[3] = (jlong)h2;
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (arr != NULL) (*env)->SetLongArrayRegion(env, arr, 0, 4, out);
    return arr;
}
