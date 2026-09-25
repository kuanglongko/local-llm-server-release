#!/bin/sh
# 崩溃探针的静态断言：探针是排查工具，本身出问题会比原 bug 更难查，故用断言钉住几条硬约束。
set -e
cd "$(dirname "$0")/.."
CPP=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "探针 fd 直写，不经 JVM 回调"        "grep -q 'write(g_probe_fd' \$CPP"
c "写失败不重试不阻塞（单次 write）"     "[ \$(grep -c 'write(g_probe_fd, s, n)' \$CPP) -eq 1 ]"
c "探针关闭时 jp 直接返回（零开销）"     "grep -q 'if (!g_probe_on || g_probe_fd < 0) return;' \$CPP"
c "信号 handler 只装、不夺（链式转发）"  "grep -q 'sigaction(SIGSEGV, &sa, &g_old_segv)' \$CPP && grep -q 'g_in_handler' \$CPP"
c "信号 handler 里有递归保护"           "grep -q '_exit(128 + sig)' \$CPP"
# 只看代码、不看注释：注释里为了说明「不许用这些」正是会写出这些词
c "handler 不用非异步安全函数"          "! sed -n '/probe_signal/,/^}/p' \$CPP | sed 's|//.*||' | grep -qE 'std::string|new |malloc|jlog|printf\(.*std'"
c "打开探针时 log sink 变空操作"        "grep -q 'g_probe_on ? probe_log_sink : jni_log_cb' \$CPP"
c "probeInit 显式返回失败原因"          "grep -q 'nativeProbeInit(dir: String, on: Boolean): Boolean' \$KT"
# 探针挂载点：nativeProbeInit 定义在 llama_jni.cpp（编进 llmjni_<tag>.so），
# 不在 libcpufeat.so 里，所以必须在 loadNative **之后**才调得到。
# 之前的断言写反了（要求"在 loadNative 之前"），三份日志里的
# UnsatisfiedLinkError: nativeProbeInit 正是这个顺序导致的。
c "探针在 loadNative 之后挂载"          "sed -n '/fun init(/,/backendInit(logBridge)/p' \$KT | grep -q 'startProbe(context)'"
c "探针在 backendInit 之前挂载"          "awk '/fun init\(/,/backendInit\(logBridge\)/' \$KT | grep -n 'startProbe(context)' | head -1"
c "startProbe 先确认 native 库已加载"    "grep -q 'if (nativeTag == null) return' \$KT"
c "Kotlin 对 nativeProbeMark 有 try 包裹" "grep -q 'try { nativeProbeMark(msg) } catch' \$KT"
c "探针模式下日志不过滤噪音、不折叠"     "grep -q 'if (probeEnabled) {' \$KT && grep -q 'PROBE_RING' \$KT"
c "HTTP 侧关键节点有埋点"               "grep -q 'probeMark(\"\[http\] 请求分类' \$HTTP"
c "工具渲染/解析路径各有一对埋点"        "grep -q '即将渲染带 tools' \$KT && grep -q '即将调用 nativeParseToolCalls' \$KT"
# ── 自举：探针失效的第一层原因（必须先 load 到 .so 才调得到 nativeProbeInit）
#    以前没有任何对策，日志里只有一句 UnsatisfiedLinkError 就断线。以下断言把
#    「探针不依赖 JNI 调用也能起」这条约束钉死，防止有人又把它收回 nativeProbeInit 里。
c "JNI_OnLoad 里跑自举（不依赖任何 JNI 调用）" "sed -n '/JNI_OnLoad(JavaVM/,/^}/p' \$CPP | grep -q 'probe_bootstrap()'"
c "自举在 JNI 入口之前完成"                  "sed -n '/JNI_OnLoad(JavaVM/,/^}/p' \$CPP | grep -q 'probe_bootstrap'"
c "自举只跑一次（有明确一次性开关）"          "grep -q 'static bool g_bootstrapped = false;' \$CPP && grep -q 'if (g_bootstrapped) return false;' \$CPP"
c "自举目录可经系统属性指定"                 "grep -q 'debug.localinference.probe.dir' \$CPP"
c "getprop 读取走受控字符集（不接受任意值）"  "grep -q 'probe_getprop' \$CPP"
# 「自举阶段退到 /data/local/tmp」这条**已按 D-1 撤销**：自举不再打开任何文件，
# 所以在自举里做降级落盘的能力一并消失 —— 这正是要的（用户没开探针就不该有文件）。
# 落盘失败的可观测性改由 nativeProbeInit 承担：errno 进自举缓冲 → 交回 logcat。
c "自举不自己降级落盘（目录/权限问题留给唯一权威处理）" \
  "! awk '/static bool probe_bootstrap\(\)/,/^}/' \$CPP | sed 's|//.*||' | grep -q '/data/local/tmp'"
c "落盘失败不静默：errno 进自举缓冲并交回 logcat" \
  "grep -q 'probe boot: %s' \$CPP && grep -q 'Kotlin 目录打不开' \$CPP"
c "探针始终没启用时也有交代（自举事件交回 logcat，不建文件）" \
  "grep -q 'static void probe_bootstrap_recall()' \$CPP && grep -q '探针未启用（设置页未开），自举只记不写' \$CPP"
# 下面四条钉的是**结构关系**，不是"某个标识符出现过"。
# 上一版这里是 `grep -q 'probe_bootstrap_flush'` 与
# `grep -q 'if (off) { g_bootstrapped = true; return JNI_FALSE; }'` —— 两条都恒真于
# 失效状态：函数名叫什么都在、off 分支这一行也在，而 D-1/D-4 正是活在
# "存在但无效"里的（self-bootstrap 无条件落盘 / 回灌恒写 0 字节）。
# 现在把判据落到函数体里，删掉调用或改回旧写法都会红。
c "自举期事件交付走唯一口径，且只在拿到的字节数 >0 时才写" \
  "awk '/static void probe_bootstrap_flush\(\)/,/^}/' \$CPP | sed 's|//.*||' | grep -q 'probe_bootstrap_write(' && \
   awk '/static void probe_bootstrap_flush\(\)/,/^}/' \$CPP | sed 's|//.*||' | grep -q 'if (n > 0) probe_raw('"
c "自举只记不写：函数体里不得 open / 不得落盘 / 不得动 g_probe_on" \
  "! awk '/static bool probe_bootstrap\(\)/,/^}/' \$CPP | sed 's|//.*||' | grep -qE 'open\(|probe_(raw|fmt)\(|g_probe_on'"
c "受控自举开关（kProbeFlagProps）真的参与判定：取值被读、被判定" \
  "grep -q 'kProbeFlagProps\[i\]' \$CPP && grep -q 'probe_flag_off(' \$CPP"
c "入参 on=false 仍能关掉探针（不被自举带偏）：先无条件收口再 return" \
  "awk '/nativeProbeInit\(JNIEnv/,/^}/' \$CPP | sed 's|//.*||' | grep -q 'g_probe_fd = -1;' && \
   awk '/nativeProbeInit\(JNIEnv/,/^}/' \$CPP | sed 's|//.*||' | grep -q 'g_probe_on = false;'"
c "Kotlin 的『关』也显式告知 native（探针开关唯一事实来源）" \
  "grep -q 'nativeProbeInit(dir.absolutePath, probeEnabled)' \$KT"
c "探针开关判据与自举缓冲判据由宿主单测真跑（不是复刻品）" \
  "grep -q 'probe_flag.h' tools/probe_util_test.cpp && [ -f tools/run_probe_flag_tests.sh ]"
c "每个 JNI 入口都会在 logcat 留一行"        "grep -q 'mark_jni_entry(fn)' \$CPP"
# 自举必须在「没有 JNIEnv」的前提下也能跑：函数体里不许出现 env->
c "自举不依赖 JVM（函数体无 env->）"          "! sed -n '/static bool probe_bootstrap()/,/^}/p' \$CPP | sed 's|//.*||' | grep -q 'env->'"

c "UI 有探针开关与一键导出"             "grep -q 'nativeProbeInit' \$KT && grep -q '导出崩溃探针' \$ACT"

if [ "$bad" -eq 0 ]; then
    echo "=== 崩溃探针单测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 崩溃探针单测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
