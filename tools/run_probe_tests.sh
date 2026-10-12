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
c "打开探针时 log sink 换成落盘（不再丢弃）"  "grep -q 'g_probe_on ? probe_log_sink : jni_log_cb' \$CPP"
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

# ── ⑤ 探针不再吞日志（A 层：探针存在的前提就是"崩前那几行必须在"）──────────
# 这一族故障的共同形态是"开了探针反而不如不开"：探针开着、文件有了，但里面
# 只剩下 `>>`/`<<` 骨架，最值钱的 llama 日志与 stderr 遗言整段消失。
# 而且**没有任何症状** —— 用户看到的是一个"内容很少的探针文件"，只会以为
# "加载这段确实没打日志"。所以四条通路各钉一条判据在**函数体/结构**上。
LFS=app/src/main/java/com/xiaowan/localinference/LogFileStore.kt

c "探针 sink 不再丢弃，改成经 jp() 落盘（且不依赖 JVM）" \
  "python3 - \$CPP <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('static void probe_log_sink(')
j = s.index('\n}\n', i)
body = '\n'.join(l for l in s[i:j].splitlines() if not l.strip().startswith('//'))
sys.exit(0 if 'jp(' in body and 'CallVoidMethod' not in body else 1)
PY"
c "探针模式下 pushLog **同时**落 Kotlin 会话文件（不再是整段缺失）" \
  "python3 - \$KT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun pushLog(t: String)')
j = s.index('fun onNativeLog(', i)
body = s[i:j]
probe = body[body.index('if (probeEnabled) {'):]
probe = probe[:probe.index('return')]
sys.exit(0 if 'LogFileStore.appendRaw(t)' in probe else 1)
PY"
c "探针模式落盘**不折叠**（appendRaw 而非 append）——否则逐 token 流变成 ×N" \
  "python3 - \$KT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun pushLog(t: String)')
j = s.index('fun onNativeLog(', i)
body = s[i:j]
probe = body[body.index('if (probeEnabled) {'):]
probe = probe[:probe.index('return')]
sys.exit(0 if 'appendRaw' in probe and 'LogFileStore.append(' not in probe else 1)
PY"
c "会话文件里有 appendRaw 的测试锚（不折叠写出）" \
  "grep -q 'appendRaw' \$LFS"
c "探针 Mark 也走 appendRaw（会话文件里一套折叠规则）" \
  "python3 - \$KT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('fun probeMark(msg: String)')
j = s.index('\n    }', i)
body = '\n'.join(l for l in s[i:j].splitlines() if not l.strip().startswith('//'))
sys.exit(0 if 'LogFileStore.appendRaw(' in body and 'LogFileStore.append(' not in body else 1)
PY"
c "stderr 那份经 uiLog→pushLog 落盘（ggml_abort 遗言不再丢）" \
  "grep -q 'StderrPump.start { uiLog(\"\[stderr\] \$it\") }' \$KT && grep -q 'fun uiLog(t: String)' \$KT"

# ── ⑥ B 层：让人看懂（"开完取证模式回日志页，第一眼就知道难怪少了"）────────
# 这几个改动的共同目的：把"探针是证据保全模式、会改变日志行为"这件事从
# 藏在设置页的一句说明里，搬到用户真正困惑的那一屏上。它们全都能被 grep 掉，所以钉住。
c "设置项改名，名字里直说"会改变日志行为"" \
  "grep -q 'text = \"崩溃取证模式（会改变日志落点，仅在排查闪退时开）\"' \$ACT"
c "旧的"崩溃探针（诊断用…）"这个名字必须绝迹" \
  "! grep -q '崩溃探针（诊断用' \$ACT"
c "日志页顶部有常驻状态带" \
  "grep -q 'private lateinit var logModeTv' \$ACT && \
   grep -q 'pageLog.addView(logModeTv)' \$ACT && \
   grep -q 'applyProbeStatusBand()' \$ACT"
c "状态带两档都说清"日志落在哪里"（判据锚在函数体）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun applyProbeStatusBand()')
j = s.index('\n    }', i)
b = s[i:j]
sys.exit(0 if 'probe-native.log' in b and 'session-*.log' in b and '常规日志模式' in b else 1)
PY"
c "状态带不说「只进 probe-native.log」（两份文件分工，写成一份就是第二种谎）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun applyProbeStatusBand()')
j = s.index('\n    }', i)
b = '\n'.join(l for l in s[i:j].splitlines() if not l.strip().startswith('//'))
sys.exit(1 if '只进 ' in b or '只此一份' in b else 0)
PY"
c "两个导出按钮下方有一行分工小字（常规叙述 vs 信号现场）" \
  "grep -q '常规日志叙述' \$ACT && grep -q '信号现场' \$ACT"
c "「导出崩溃探针」空态按成因分三种说法（不再一句话盖住）" \
  "grep -q 'probeEmptyReason' \$ACT && \
   grep -q '崩溃取证模式未开启' \$ACT && \
   grep -q '探针已挂载，但本次运行还没有任何 native 输出' \$ACT"
c "旧的"一句话盖三种成因"的空态文案必须绝迹" \
  "! grep -q '空：进程未挂上探针或本次是首次启动' \$ACT"
c "会话保留数由 LogFileStore.KEEP 单一来源（状态带不另抄字面量）" \
  "grep -q 'internal const val KEEP = 8' \$LFS && \
   grep -q 'LogFileStore.KEEP' \$ACT"

# ── ⑦ C 层：开关搬到日志（调试）页，与状态带 / 导出按钮同屏 ────────────────
# 这一层的坏法同样是**零症状**的：开关留在设置页时一切照旧能编译、能开、能取证，
# 只是用户产生困惑的那一屏（日志页）上永远写着"到设置页去开"。
# 所以判据锚**归属**（`pageSet.addView(probeSwitch)` 必须绝迹、`pageLog` 必须有一处），
# 不锚"文件里出现过 probeSwitch"—— 那种断言在搬与不搬之间恒真。
c "开关已挂到日志页（pageLog 有且仅有一处挂载）" \
  "[ \$(grep -c 'pageLog.addView(probeSwitch' \$ACT) -eq 1 ]"
c "开关不再挂在设置页（搬走后残留 = 两处开关，一改一个不生效）" \
  "! grep -q 'addFull(probeSwitch)' \$ACT && ! grep -q 'pageSet.addView(probeSwitch' \$ACT"
c "旧的设置页分组头「── 崩溃取证（闪退排查）──」绝迹（空组头会留下一条无处可折的标题）" \
  "! grep -q '── 崩溃取证' \$ACT"
c "开关与状态带在**同一页**（判据锚函数体之外：两者都进 pageLog）" \
  "grep -q 'pageLog.addView(logModeTv)' \$ACT && grep -q 'pageLog.addView(probeSwitch' \$ACT"
c "「必须完全退出重启」是**常驻行**，不许退回 Toast（那正是"以为开了没生效"的坑）" \
  "grep -q 'private fun applyProbeHint' \$ACT && \
   python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('probeSwitch = android.widget.Switch(this)')
j = s.index('pageLog.addView(probeHintTv)', i)
blk = s[i:j]
sys.exit(0 if 'probeRestartTv' in blk and '完全退出并重启 App 才生效' in blk else 1)
PY"
# ⚠ 判据必须**排注释**：本次改动的注释里正引用着那句旧指路文案（解释"为什么改"）。
#   不排的话，判据被自己的说明文字命中，在**完全正确**的源码上报假故障 ——
#   与本仓库 run_keepalive_guard 的"判据锚到注释上"那一族同形。
c "日志页里不再指路"到设置页「崩溃取证」"（开关就在本页；只看代码行）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
code = [l for l in s.splitlines() if not l.lstrip().startswith('//')]
bad = [l for l in code if '到设置页「崩溃取证」' in l or '设置页「崩溃取证」打开' in l]
sys.exit(1 if bad else 0)
PY"
c "空态指路改成"本页顶部那个开关"" \
  "grep -q '打开本页顶部那个开关' \$ACT"
c "日志页页签名带"调试"（这一页装的不只是日志）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
sys.exit(0 if 'tabBtn(\"调试\")' in s and 'tabBtn(\"日志\")' not in s else 1)
PY"
# 判据锚"按钮在哪一页"，不锚"出现过这个字面量"：只 grep 名字的话，
# 把它留在设置页照样绿。
c "「纯 CPU 加载」进了日志页工具栏（排障入口不再混在加载/卸载那一排）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('val logCpuLoadBtn = btn(\"纯 CPU 加载\")')
j = s.index('pageLog.addView(logProbeRow)', i)
blk = s[i:j]
sys.exit(0 if 'logProbeRow.addView(logCpuLoadBtn' in blk else 1)
PY"
c "设置页那一排不再有「纯CPU诊断」（两个入口名不同 = 迟早漂移）" \
  "! grep -q 'btn(\"纯CPU诊断\")' \$ACT && ! grep -q 'cpuBtn' \$ACT"

if [ "$bad" -eq 0 ]; then
    echo "=== 崩溃探针单测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 崩溃探针单测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
