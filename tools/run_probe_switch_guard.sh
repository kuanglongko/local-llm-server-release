#!/bin/sh
# 「探针开关必须由用户意图决定、自举只记不写」的源码级守卫（模块 D：D-1 / D-2 / D-4）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是只靠单测 / 读代码）
# ═══════════════════════════════════════════════════════════════════════════
# 这三条**没有一条会让别的测试变红**：
#
#   D-1 用户在设置页关掉探针，native 侧照样全量落盘并把 llama 日志 sink
#       换成一个空函数 —— 于是原生日志（模型加载失败、采样告警）整段消失在
#       UI 的日志面板之外，而用户既不知道探针在写盘、也没有入口去看它。
#       成因是自举（JNI_OnLoad）无条件打开文件并置 g_probe_on，
#       而 Kotlin 侧「关」的那一次调用根本不会发生（!probeEnabled 直接 return）。
#       稳态下"看起来正常"，只有对比 probe-native.log 与设置页开关才会发现。
#
#   D-2 `kProbeFlagProps`（受控自举开关）是**死代码**：注释写着"JNI_OnLoad 时读取"，
#       全仓库 0 处读取。它本身只是死代码，但它正是 D-1 的证据 ——
#       当初的设计意图确实是"有一个开关"，实现漏了。
#
#   D-4 `probe_bootstrap_flush()` 恒写 0 字节：自举当场把事件落盘并清零缓冲，
#       随后那句"回灌到正式文件"自然什么也写不出来。而 Kotlin 侧给排障者的
#       操作指令恰恰是"probe-native.log 里应该有一条 [boot]；一条都没有才说明
#       native 没跑起来" —— 判据与行为分叉，给出的是**错误方向的确信**。
#
# 这三条的共同形态是"存在但无效"：函数在、常量在、调用点也在，就是不起作用。
# 所以判据必须钉在**结构关系**上（"谁置位 g_probe_on"、"自举里有没有落盘"、
# "调用点会不会被执行到"），而不是"grep 某个标识符出现过"。
# 上一版探针单测的弱点正在这里：它断言 `probe_bootstrap_flush` 这个名字出现过、
# 断言 `if (off) { ... }` 这一行存在 —— 两条都恒真于失效状态。
#
# 运行：sh tools/run_probe_switch_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}
JNI=app/src/main/cpp/llama_jni.cpp
FLAG=app/src/main/cpp/probe_flag.h
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
README=README.md
HTP=HTP-STATUS.md

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥掉注释行与行内 `//...`：判据是"源码里有没有这种写法"，而注释里恰好会
# 引用被禁掉的旧写法（本文件的修复注释里就写着"此前无条件 probe_raw"）。
# 不剥注释 -> 恒红 -> 被人静音。与 run_llama_jni_abort_guard.sh 同一套路。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*' ; }
# 只取某个函数的函数体（从 `static ... 名字(` 那一行到行首 `}`）。
# 为什么按函数体取：`grep` 全文件会让"别处也有这个标识符"把断言弄成恒真。
fnbody() { awk -v f="$2" 'index($0, f) > 0 && /\(/ {inb=1} inb {print} inb && /^}/ {exit}' "$1"; }

c "三份源文件都在" "[ -f \$JNI ] && [ -f \$FLAG ] && [ -f \$KT ]"

# ── ① D-2：kProbeFlagProps 必须真的参与判定 ──────────────────────────────
# 判据是"开关值被**读**并且被**判**"，不是"常量定义还在"。
c "kProbeFlagProps 在自举里被逐个 getprop 读取" \
  "fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'kProbeFlagProps\\[i\\]'"
c "读取条数由 kProbeFlagPropCount 统一（不是硬编码循环上界）" \
  "fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'kProbeFlagPropCount'"
c "读到的值真的被判定（probe_flag_off）" \
  "fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'probe_flag_off('"
c "显式关闭有独立的落点状态（g_boot_off_by_prop）" \
  "grep -q 'static bool g_boot_off_by_prop = false;' \$JNI && \
   fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'g_boot_off_by_prop = true'"
c "判据本体在宿主可编的头里（不是留在编不过的 .cpp 里）" \
  "[ -f \$FLAG ] && grep -q 'static bool probe_flag_off(' \$FLAG"
c "判据**只接受明确的否**，不接受 on 方向（fail-safe 反向）" \
  "grep -q 'kProbeOffValues\\[\\] = {\"0\", \"false\", \"no\", \"off\"' \$JNI"
# 值判据不得退化成"属性在不在"：probe_prop_has 只问存在性，用它当开关 =
# `setprop ...probe.on 0` 会被读成"开着"。
c "开关判据不得退化成 probe_prop_has（只看在不在）" \
  "! fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'probe_prop_has'"

# ── ② D-1：g_probe_on 的唯一权威是用户意图 ──────────────────────────────
c "g_probe_on 的置真只出现在 nativeProbeInit 里（自举不再预先打开）" \
  "[ \$(grep -c 'g_probe_on = true;' \$JNI) -eq 1 ] && \
   fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit' | nocomment | grep -q 'g_probe_on = true;'"
c "自举函数体里不得出现 g_probe_on 赋值（只记不写、也不改开关）" \
  "! fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'g_probe_on\\s*='"
c "自举函数体里不得 open 文件（文件只由 nativeProbeInit 建立）" \
  "! fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -qE 'open\\('"
c "自举函数体里不得 probe_raw / probe_fmt 落盘" \
  "! fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -qE 'probe_(raw|fmt)\\('"
c "自举函数体里不得改 g_probe_fd" \
  "! fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'g_probe_fd\\s*='"
# nativeProbeInit 是唯一权威：off 分支必须先清 fd 与 g_probe_on，且不因自举而漏掉。
c "nativeProbeInit 先无条件收口 fd 与 g_probe_on（off 分支也不漏）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit' | nocomment | \
   grep -q 'if (g_probe_fd >= 0) { close(g_probe_fd); g_probe_fd = -1; }' && \
   fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit' | nocomment | \
   grep -q 'g_probe_on = false;'"
c "off 分支不落盘、直接返回（不被自举带偏）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit' | nocomment | \
   grep -q 'if (off) {' && \
   fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit' | nocomment | \
   grep -q 'return JNI_FALSE;'"
c "显式关闭优先于 Kotlin 传入的 true（属性强关）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit' | nocomment | \
   grep -q 'if (g_boot_off_by_prop) {'"
c "打不开文件时不静默（errno 进自举缓冲并交回）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit' | nocomment | \
   grep -q 'probe_bootstrap_recall()'"
# Kotlin 侧：「关」也必须显式告知 native —— 钉**调用点**，不钉定义。
# Kotlin 的缩进函数体：`fnbody` 以行首 `}` 收尾，Kotlin 里是 `    }`，
# 所以这里用 `ktbody`（遇到顶格 `}` 或空行+同级缩进即停）。
ktbody() { awk -v f="$2" 'index($0, f) > 0 {inb=1} inb {print} inb && /^    }$/ {exit}' "$1"; }
# 判据写成"传参那一行之前**不得**有 probeEnabled 的提前返回"：
# 只断言"文件里没有 `if (!probeEnabled) return null`"是错的 —— 修复后这一行
# 本来就得留着（它是"关着就别报挂载失败"的正确处理），只是必须排在调用**之后**。
# 用 python3 数行号（sh 里跨管道传行号太容易写错，本判据值这个依赖；仓库里
# 其它守卫也已经在用 python3 做同类结构抽取）。
c "Kotlin 在调 nativeProbeInit 之前不得因 !probeEnabled 提前返回" \
  "\$PY tools/probe/check_kt_probe_order.py \$KT" \
# 判据不是"传了 probeEnabled 这个标识符"，而是"on 参数**恰好**由 probeEnabled 决定"。
# 写成 `nativeProbeInit(dir.absolutePath, true)` 是最容易发生的退化
# （copy 自旧代码），它会让用户在设置页关掉探针也照开 —— 必须抓到。
c "Kotlin 把 probeEnabled 作为 on 参数传给 nativeProbeInit（不是写死 true）" \
  "ktbody \$KT 'fun startProbe(' | nocomment | grep -q 'nativeProbeInit(dir.absolutePath, probeEnabled)' && \
   ! ktbody \$KT 'fun startProbe(' | nocomment | grep -qE 'nativeProbeInit\(dir.absolutePath, true\)'"
c "backendInit 选 sink 用的是 g_probe_on（结果），不是自举是否成功" \
  "grep -q 'llama_log_set(g_probe_on ? probe_log_sink : jni_log_cb, nullptr);' \$JNI"

# ── ③ D-4：自举事件只记不写、且只能交付一次 ─────────────────────────────
c "自举缓冲交付走 probe_bootstrap_write（唯一口径）" \
  "fnbody \$JNI 'static void probe_bootstrap_flush()' | nocomment | grep -q 'probe_bootstrap_write('"
c "交付次数有独立计数（同一段现场不重复落盘）" \
  "grep -q 'static int g_boot_flush_tries = 0;' \$JNI && \
   fnbody \$JNI 'static void probe_bootstrap_flush()' | nocomment | grep -q '&g_boot_flush_tries'"
# 旧写法的两个特征必须绝迹：自举里当场落盘 + 立刻清零缓冲。
c "自举里不再清零 g_boot_log_len（旧写法正是这一行让回灌恒为 0）" \
  "! fnbody \$JNI 'static bool probe_bootstrap()' | nocomment | grep -q 'g_boot_log_len = 0'"
c "flush 只在拿到的 n>0 时写（不再无条件下发）" \
  "fnbody \$JNI 'static void probe_bootstrap_flush()' | nocomment | grep -q 'if (n > 0) probe_raw('"
c "判据本体同样收口到宿主可编的头里" \
  "grep -q 'static size_t probe_bootstrap_write(' \$FLAG"
c "探针始终没启用时，自举事件交回 logcat（不建文件）" \
  "fnbody \$JNI 'static void probe_bootstrap_recall()' | nocomment | grep -q 'LOGE(' && \
   ! fnbody \$JNI 'static void probe_bootstrap_recall()' | nocomment | grep -qE 'open\\('"

# ── ④ 判据网自身不得只锚「存在性」 ───────────────────────────────────────
# 探针单测此前有两条恒真的弱断言（只 grep 标识符/某一行的存在）。
# 现在要求它们钉到结构上，否则删掉调用照样绿。
# 弱断言是"只 grep 标识符/某一行在不在"。要钉的是"断言里出现了函数体提取
# （awk 取函数体，如 awk '/static bool probe_bootstrap\(\)/,/^}/'）"这种**结构级**判据。
c "探针单测对自举与 nativeProbeInit 都改用函数体级判据" \
  "grep -qF \"awk '/static bool probe_bootstrap\" tools/run_probe_tests.sh && \
   grep -qF \"awk '/nativeProbeInit\" tools/run_probe_tests.sh"
c "探针单测把自举事件交付钉到『写了非零字节』而非『函数存在』" \
  "grep -q 'probe_bootstrap_write' tools/run_probe_tests.sh"
c "探针单测把开关钉到『属性值真的被判定』而非『常量存在』" \
  "grep -q 'kProbeFlagProps' tools/run_probe_tests.sh && grep -q 'probe_flag_off' tools/run_probe_tests.sh"
c "探针单测断言 off=false 那次调用真的发得出去（不做无效的提前 return）" \
  "grep -q \"nativeProbeInit(dir.absolutePath, probeEnabled)\" tools/run_probe_tests.sh"
c "开关单测接进 probe_flag.h 的真实现（不是复刻）" \
  "grep -q '#include \"probe_flag.h\"' tools/probe_util_test.cpp"

# ── ⑤ 文档承诺与实现一致 ────────────────────────────────────────────────
# 判据要能抓住"文档退回旧承诺"（自举会落盘 / 关不掉）。所以钉三件事：
# ① 明确写了"只记不写"；② 写了"没启用就只进 logcat、不建文件"；③ 写了"关也告知 native"。
# 只钉 ① 太弱：桩⑦ 只改一处措辞就能绕过（这是自测逼出来的）。
c "README 说明自举只记不写" \
  "grep -q '自举阶段\\*\\*只记不写\\*\\*' \$README"
c "README 说明探针没启用时不建文件、只进 logcat" \
  "grep -q '不建文件、不落盘' \$README"
c "README 说明『关』也显式告知 native（开关唯一事实来源）" \
  "grep -q '「关」也会显式告知 native' \$README"
c "HTP-STATUS 说明受控自举开关真的生效" \
  "grep -q 'probe.on' \$HTP"

if [ "$bad" -eq 0 ]; then
    echo "=== 探针开关/自举守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 探针开关/自举守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
