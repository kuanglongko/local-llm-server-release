#!/bin/sh
# 「日志重置与保留窗口」的源码级守卫（Issue #238 的日志一轮）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 这一族故障的共同形态是**没有任何症状**：文件越写越大、上一段现场被自己挤掉，
# 都不会崩、不会报错，也不会让别的测试变红。用户看到的是一个"很大的日志文件"，
# 只会以为"探针就是这么占地方"。
#
#   R-1 探针文件**没有重置**：native 的 fd 是 O_APPEND，从 App 装好到卸载只有
#       一个文件、只增不减。它还是三样东西一起灌（native 全量 + stderr 遗言 +
#       逐 token 原始流），一次长回答就是上万行。
#   R-2 "保留最近 N 个会话"**把上一次会话删掉**：`cur` 在筛 prev 之前就已创建、
#       `lastModified()` 立刻变成 now，于是它永远是第 1 个，`drop(KEEP - 1)`
#       恰好删掉上次那座现场 —— 而"导出上次会话"正是为它准备的。
#       文件名里就带着时间戳，却拿 mtime 去排序，是这个 bug 的入口。
#
# 判据一律锚**结构关系**（谁在开文件前重置、按什么排序、排除谁），
# 不锚存在性（"文件里出现过 rename"）—— 那种断言在 R-1/R-2 上全绿。
#
# 本守卫**本轮刻意不管上限**：上限值要先有实测数据（见 Issue #238 的讨论）。
# 所以这里没有"上限"判据，只有"重置"与"保留窗口"两条。
#
# 运行：sh tools/run_log_rotation_guard.sh
set -e
cd "$(dirname "$0")/.."

JNI=app/src/main/cpp/llama_jni.cpp
LFS=app/src/main/java/com/xiaowan/localinference/LogFileStore.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释行与行内 `//...`：注释里正引用着"旧写法长什么样"。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*' ; }
# 取一个 C/C++ 静态函数的函数体（从签名行到行首 `}`）。
fnbody() { awk -v f="$2" 'index($0, f) > 0 && /\(/ {inb=1} inb {print} inb && /^}/ {exit}' "$1"; }
# 取一个 Kotlin 函数的函数体（从签名行到顶格 `}`）。
ktfn() { awk -v f="$2" 'index($0, f) > 0 {inb=1} inb {print} inb && /^    }$/ {exit}' "$1"; }

for f in "$JNI" "$LFS"; do
    [ -f "$f" ] || { echo "FAIL  锚点文件不存在：$f（守卫需更新）"; exit 1; }
done

# ── ① R-1：探针文件在**每次开文件之前**被重置成上一代 ───────────────────────
# 判据锚"重置真的发生在开文件的前一行"，不锚"仓库里有个 rename" ——
# 把重置放到 open 之后就等于没重置（旧 fd 已被截走）。
c "native 里存在唯一的重置函数，且真的调了 rename" \
  "fnbody \$JNI 'static void probe_rotate_prev' | nocomment | grep -q 'rename('" 
c "重置目标带 .prev 后缀（上一代留下，不是 unlink 扔掉）" \
  "fnbody \$JNI 'static void probe_rotate_prev' | nocomment | grep -q '\"\\.prev\"'"
c "重置不得用 unlink/remove（那等于把上一段现场扔掉）" \
  "! fnbody \$JNI 'static void probe_rotate_prev' | nocomment | grep -qE 'unlink\\(|remove\\('"
c "重置失败不静默（ENOENT 之外要留痕），且不阻断取证" \
  "fnbody \$JNI 'static void probe_rotate_prev' | nocomment | grep -q 'ENOENT' && \
   fnbody \$JNI 'static void probe_rotate_prev' | nocomment | grep -qE '\\bLOG[WIDE]\\('"
# 归属判据：nativeProbeInit 函数体里，重置那行必须排在 open 那行**之前**。
c "nativeProbeInit：重置排在 open 之前（不然重置的是别人的 fd）" \
  "python3 - \$JNI <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
i = src.index('Java_com_xiaowan_localinference_LlmEngine_nativeProbeInit')
j = src.index('\n}\n', i)
body = '\n'.join(l.split('//')[0] for l in src[i:j].splitlines())
rot, ope = body.find('probe_rotate_prev('), body.find('open(path.c_str()')
sys.exit(0 if rot >= 0 and ope >= 0 and rot < ope else 1)
PY"
c "探针文件仍是 O_APPEND（重置不改变写入语义，崩溃现场不丢已写内容）" \
  "grep -q 'open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600)' \$JNI"

# ── ② R-2：会话保留窗口按**文件名里的时间戳**排，且按名字排除本次 ────────────
# 旧写法两处都用 `lastModified()`，而本次文件是刚建出来的 —— 它永远最新，
# `drop(KEEP - 1)` 于是恰好删掉上一次会话。这两个判据钉的正是那两处。
c "保留窗口按文件名排序（不是 lastModified —— 那正是删掉上次会话的入口）" \
  "ktfn \$LFS 'fun init(' | nocomment | grep -q 'sortedByDescending { it.name }'"
c "筛上一次会话也按文件名取最大（同一口径，两处不许各写一套）" \
  "ktfn \$LFS 'fun init(' | nocomment | grep -q 'maxByOrNull { it.name }'"
c "本次会话按**名字**排除（f != cur 依赖对象同一性，自己那句 stale 假设已失效）" \
  "ktfn \$LFS 'fun init(' | nocomment | grep -q 'f.name != cur.name'"
c "保留窗口仍由 KEEP 单一来源（不许把 8 抄进判据）" \
  "ktfn \$LFS 'fun init(' | nocomment | grep -q 'drop(KEEP - 1)' && \
   grep -q 'internal const val KEEP = 8' \$LFS"
c "旧写法 lastModified 在 init 里绝迹（它一到场，R-2 就回来了）" \
  "! ktfn \$LFS 'fun init(' | nocomment | grep -q 'lastModified'"

if [ "$bad" -eq 0 ]; then
    echo "=== 日志重置/保留窗口守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 日志重置/保留窗口守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
