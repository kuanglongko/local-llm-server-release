#!/bin/sh
# 崩溃探针「信号 handler 链 + 预读文件身份」的源码级守卫（模块 K：K-1 / K-2 / K-3 / K-4）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是只靠单测 / 读代码）
# ═══════════════════════════════════════════════════════════════════════════
# 这四条**没有一条会让别的测试变红**，且失效形态全部是「看起来正常」：
#
#   K-1 `probe_install_signals()` 被调第二次 → 每次都无条件覆盖 `g_old_*`。
#       `nativeProbeInit(on=true)` 在本进程里不是一次性的：`LlmEngine.init()` 的
#       `if (loaded) return true` 只在 init **成功**后成立，而 startProbe 排在
#       backendInit 之前 —— backendInit 抛异常时 loaded 仍为 false，探针却已装过。
#       第二次装完 `g_old_segv` 里存的是**探针自己**，转发链变成一个环。
#       症状：`!!! ===== SIGNAL` 一行都留不下 —— 与「根本没崩 signal」**同形**。
#
#   K-2 `SA_ONSTACK` 设了，但全仓库 0 处 `sigaltstack`。SA_ONSTACK 的语义是
#       "**有**备用栈就用它"，没装时是 no-op —— handler 仍跑在那个**已经爆掉的栈**上。
#       而本项目最需要现场的正是渲染 / PEG 解析的深递归（爆栈型 SEGV）。
#
#   K-3 原 disposition 是 `SIG_IGN` 时，旧写法先 `old->sa_handler(sig)`
#       （对 SIG_IGN 而言 `(void(*)(int))1` **不是**可调用函数）再 `_exit(128+sig)`：
#       把"本进程忽略这个信号"**升级成致命退出**，与文件头"不改变原有行为"直接冲突。
#
#   K-4 `probeGguf` 的内存缓存只按 path 命中，不看 size/mtime。同一路径被**就地替换**
#       （外部路径重新下载完成、续传落盘、adb push 覆盖）后，旧判定继续被当成
#       "这个文件的真实量化"写进 `[HTP判定]` 日志与加载决策。
#       而 `peekGguf` 的持久缓存**本来**就是 size+mtime 双校验 —— 两处口径分叉。
#
# 判据一律钉在**结构关系**上（"清 `g_old_*` 之前有没有比过闸门"、"SA_ONSTACK 是否
# 有 sigaltstack 兜底"、"能否命中的是文件身份而不是路径"），不钉"某个标识符出现过"。
# 上一版 `run_probe_tests.sh` 那条 `grep -q 'g_in_handler'` 就是反面教材：它恒真于
# 自链状态，而自链正是 K-1。
#
# 运行：sh tools/run_probe_signal_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释：判据是"源码里有没有这种写法"，而注释里为说明「不许这样」恰好会写出旧写法。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*' ; }
fnbody() { awk -v f="$2" 'index($0, f) > 0 && /\(/ {inb=1} inb {print} inb && /^}/ {exit}' "$1"; }
ktbody() { awk -v f="$2" 'index($0, f) > 0 {inb=1} inb {print} inb && /^    }$/ {exit}' "$1"; }

c "两份源文件都在" "[ -f \$JNI ] && [ -f \$KT ]"

# ── ① K-1：安装必须幂等，且清 g_old_* 只能在闸门之后 ──────────────────────
c "有安装闸门（只装一次的一次性状态）" \
  "grep -q 'static bool g_signals_installed = false;' \$JNI"
c "probe_install_signals 在装之前先查闸门并直接返回" \
  "fnbody \$JNI 'static void probe_install_signals()' | nocomment | \
   grep -q 'if (g_signals_installed) {' && \
   fnbody \$JNI 'static void probe_install_signals()' | nocomment | \
   grep -q 'return;'"
# 判据是**顺序**：`sigaction(..., &g_old_x)` 必须排在闸门之后，否则第二次安装照样覆盖。
c "每次 sigaction 都在闸门之后（第二次不再覆盖 g_old_*）" \
  "\$PY tools/probe/check_signal_order.py \$JNI"
c "装完置闸门" \
  "fnbody \$JNI 'static void probe_install_signals()' | nocomment | grep -q 'g_signals_installed = true;'"

# ── ② K-2：SA_ONSTACK 必须真有备用栈 ─────────────────────────────────────
c "确实调用了 sigaltstack（不只是设了 SA_ONSTACK）" \
  "grep -q 'sigaltstack(&ss' \$JNI"
# 判据是**顺序**：备用栈必须在写 sa_flags 之前装好，否则 SA_ONSTACK 首次安装就是一个 no-op。
c "备用栈的安装排在设置 sa_flags 之前" \
  "\$PY tools/probe/check_altstack_first.py \$JNI"
c "SA_ONSTACK 只在备用栈确实装好后设置（不是无条件）" \
  "fnbody \$JNI 'static void probe_install_signals()' | nocomment | \
   grep -q 'SA_SIGINFO | (g_alt_stack_ready ? SA_ONSTACK : 0)'"
c "备用栈有独立状态位，装失败时不谎报" \
  "grep -qE 'static bool +g_alt_stack_ready = false;' \$JNI && \
   fnbody \$JNI 'static void probe_install_alt_stack()' | nocomment | grep -q 'g_alt_stack_ready = true;'"
c "备用栈用静态存储（不 malloc：栈溢出型 SEGV 可能在堆已崩之后）" \
  "grep -q 'static char   g_alt_stack\\[kAltStackSize\\];' \$JNI && \
   ! fnbody \$JNI 'static void probe_install_alt_stack()' | nocomment | grep -qE 'malloc|new '"
c "备用栈不得小于 MINSIGSTKSZ" \
  "fnbody \$JNI 'static void probe_install_alt_stack()' | nocomment | grep -q 'MINSIGSTKSZ'"

# ── ③ K-3：SIG_IGN 必须保持"忽略"，不得升级成致命退出 ─────────────────────
# ⚠ 判据是"**这个分支**里不得 _exit"，不是"文件里没有 _exit" ——
#   g_in_handler 那个递归保护**必须**留着 _exit（handler 自己又崩了就别死循环）。
c "SIG_IGN 分支保持忽略（直接返回，不调 sa_handler）" \
  "fnbody \$JNI 'static void probe_signal' | nocomment | \
   grep -q 'old->sa_handler == SIG_IGN) {' && \
   fnbody \$JNI 'static void probe_signal' | nocomment | grep -q 'return;'"
c "SIG_IGN 分支不得 _exit（旧写法在这里把\"忽略\"升级成\"去死\"）" \
  "\$PY tools/probe/check_sigign_exit.py \$JNI"
c "递归保护的 _exit 仍然保留（handler 自己也崩 -> 直接退出）" \
  "fnbody \$JNI 'static void probe_signal' | nocomment | grep -q '_exit(128 + sig);'"
c "递归保护在写日志之前（否则 handler 自己崩时会反复写）" \
  "\$PY tools/probe/check_reentrancy_first.py \$JNI"

# ── ④ K-4：预读缓存命中判据 = 文件身份（path + size + mtime），不是路径 ───
c "有文件身份这个判据本体（path + length + mtime 三者同值）" \
  "grep -q 'private class ProbeIdentity(val path: String, val length: Long, val mtime: Long)' \$KT && \
   grep -q 'other.path == path && other.length == length && other.mtime == mtime' \$KT"
c "内存缓存改存身份，不再只存路径" \
  "grep -q 'probeId' \$KT && ! grep -q 'probePath' \$KT"
c "probeGguf 的命中判据是身份比对" \
  "ktbody \$KT 'fun probeGguf(' | nocomment | grep -q 'if (probeId == id) return probeVal'"
c "peekGguf 与 probeGguf 共用同一身份判据（不再各写一份近似）" \
  "ktbody \$KT 'fun peekGguf(' | nocomment | grep -q 'if (probeId == id && probeVal != null) return probeVal'"
c "身份取自真实文件属性（length + lastModified），不是猜" \
  "grep -q 'ProbeIdentity(path, f.length(), f.lastModified())' \$KT"
c "forgetProbe 与 unload 也清身份（按路径清）" \
  "ktbody \$KT 'fun forgetProbe(' | nocomment | grep -q 'probeId = null' && \
   ktbody \$KT 'fun unload(' | nocomment | grep -q 'probeId = null'"
# htpProbeForLoaded 的语义是"只对当前已加载路径成立"，路径这一半仍要判。
c "htpProbeForLoaded 仍按路径比对（file identity != 已加载模型）" \
  "grep -q 'probeId.*path == currentPath' \$KT"

# ── ⑤ 判据网自身不得只锚「存在性」────────────────────────────────────────
# "存在但无效"是这一族的共同形态：函数在、常量在、调用点也在，就是不起作用。
c "判据占位符不得只断言标识符存在" \
  "! grep -qE '^c \"[^\"]*\" \"grep -q .(g_signals_installed|g_alt_stack_ready).\"\$' \$0"
c "抽取脚本与源码形状绑定（改名即红，不掉成静默跳过）" \
  "grep -q '本脚本与源码形状绑定' tools/probe_syntax/extract.py"

echo "=== 探针信号链守卫：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
