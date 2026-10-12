#!/usr/bin/env python3
"""从 app/src/main/cpp/llama_jni.cpp **逐字抽取**探针开关/自举这一段，供宿主语法检查。

为什么按函数体逐字抽、而不是"另写一份近似"：本项目反复栽在"宿主侧镜像 native 逻辑"上
（镜像与真实现分叉后测试全绿、真机照错，F-2 就是活例子）。抽出来编的是**同一份字节**，
改坏了在宿主侧就红，不必等装机。

抽取顺序必须与源码里的定义顺序一致（C++ 先声明后使用）。
"""
import sys

def main(src_path: str, out_path: str) -> None:
    src = open(src_path, encoding="utf-8").read()
    def grab(sig: str) -> str:
        try:
            i = src.index(sig)
        except ValueError:
            sys.exit("抽取失败：源码里找不到定义 `" + sig + "`（函数改名/签名改了？"
                     "本脚本与源码形状绑定，请一起更新）")
        j = src.index("\n}\n", i) + 3
        return src[i:j]

    # 日志宏**从真源码逐字带过来**，不在桩头里另写一份。
    # 桩头此前手抄了 `#define LOGI/LOGE`，于是「源码里用了没定义的宏」这件事
    # 在本单元里永远编得过 —— ccf0557 的 `LOGW` 正是这样：真机 GCC 报
    # `'LOGW' was not declared`，而全部守卫与语法检查一路全绿（LOGW 只出现在
    # 源码第 313 行，桩头没有它，两边各自"自洽"）。判据漂移的经典形态。
    log_macros = "\n".join(
        l for l in src.splitlines() if l.startswith("#define LOG")
    )
    if not log_macros:
        sys.exit("抽取失败：源码里找不到任何 `#define LOG*` 日志宏"
                 "（宏改名/挪走了？本脚本与源码形状绑定，请一起更新）")

    parts = [
        grab("static bool probe_getprop("),
        grab("static bool probe_prop_has("),
        grab("static void probe_boot_note("),
        grab("static void probe_bootstrap_flush()"),
        grab("static void probe_bootstrap_recall()"),
        # R-1 的探针文件重置。此前**不在**抽取单元里，于是它用了一个不存在的
        # 日志宏、真机编不过，而本单元照样绿 —— "抽取范围之外"等于"没人管"。
        grab("static void probe_rotate_prev("),
        grab("static bool probe_bootstrap()"),
        grab("JNIEXPORT jboolean JNICALL\n"
             "Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit("),
        # 信号 handler 链（K-1 幂等 / K-2 备用栈 / K-3 SIG_IGN 不升级）。
        # 这一段是「探针本该给出信号现场」的那条路，改坏了在宿主侧就要红 ——
        # 它在真机上失效的形态是「现场一行都没有」，与「根本没崩 signal」同形。
        grab("static void probe_install_alt_stack("),
        grab("static void probe_install_signals()"),
    ]
    open(out_path, "w", encoding="utf-8").write("\n\n".join(parts))
    # 供 head.h include：桩头只给"宏的名字从哪来"，不再自己抄一份。
    if len(sys.argv) > 3:
        open(sys.argv[3], "w", encoding="utf-8").write(log_macros + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
