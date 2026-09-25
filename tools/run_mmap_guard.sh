#!/bin/sh
# 「mmap 开关必须真的落到 llama_model_params.load_mode」的源码级守卫（模块 M）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是只靠单测 / 读代码）
# ═══════════════════════════════════════════════════════════════════════════
# 设置页的「mmap（1=默认省内存）」从 0.7（`a6832e9`，进程内推理那一轮）起就
# 一路透传到 JNI 的形参 `jboolean useMmap`；而 native 侧**从来没有读过它**。
# 0.9.6（`3b8e2a5`）补上了"关"这一支：
#
#     if (!useMmap) mp.load_mode = LLAMA_LOAD_MODE_NONE;
#
# 看着对称，实际只落了一支 —— 开的时候"什么都不做，靠 llama_model_default_params()"，
# 关的时候才写一次。用户报的正是这个形态："mmap 设 1 或 0，占用内存没有明显变化，
# 都是模型大小 + KV 大小，设 1 根本没省内存"。
#
# 更坏的是它**没有任何一条测试或守卫能拦住**：
#   · 形参在、常量定义在、赋值语句也在 —— 存在性断言全绿；
#   · 关（=0）那一支**真的会**走进 LLAMA_LOAD_MODE_NONE，所以"随手调一下 0，
#     看到常量被写进去"就以为验过了；
#   · 稳态下（默认就是 AUTO，AUTO 落到 mmap 是库内行为）两支**表现一样**，
#     只有真机读 /proc/<pid>/maps 才分得出来。
# 这正是本项目反复踩的"存在但无效"，且比一般的更隐蔽：**一半有效**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「两支都显式落值」，不锚「出现过 LLAMA_LOAD_MODE_NONE」
# ═══════════════════════════════════════════════════════════════════════════
# 钉两件事（缺一即回到旧形态）：
#   ① `mp.load_mode` 由 `useMmap` 的**三目**决定 —— "开"也显式落值，
#      不再把一半契约交给库的默认值；
#   ② load_mode 的赋值**只有一处**（`if (!useMmap)` 那种单支写法一旦回来，
#      计数就 ≠ 1，因为三目会被拆成两条）。
# 两条都不依赖注释文本 —— 注释里恰好会引用被禁掉的旧写法。
#
# 运行：sh tools/run_mmap_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释：判据是"源码里有没有这种写法"，而修复注释里就写着旧写法。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*' ; }
# 只取 nativeLoadModel 的函数体：全文件 grep 会让"别处也有 load_mode"把断言弄成恒真。
fnbody() { awk -v f="$2" 'index($0, f) > 0 && /\(/ {inb=1} inb {print} inb && /^}/ {exit}' "$1"; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"

# ── ① 两支都显式落值 ─────────────────────────────────────────────────────
c "load_mode 由 useMmap 三目决定（开也显式写，不靠库默认值）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
   grep -q 'mp.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;'"

# ── ② 赋值只有一处（单支 `if (!useMmap)` 写法回来后计数即 ≠ 1）───────────
# 数"给 mp.load_mode 赋值"的语句条数。写成 `mp.load_mode =` 的形态都算。
# 为什么必须计数：单支写法与三目写法**都能**让 ①"看起来"不是空话
# （单支里那一个常量是真的），区别只在那条赋值进不进得去。
c "load_mode 的赋值恰好一处（没有并存的单支写法）" \
  "[ \$(fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
       grep -c 'mp\\.load_mode[[:space:]]*=[^=]') -eq 1 ]"

# ── ③ 旧写法必须绝迹 ────────────────────────────────────────────────────
c "不再有仅凭 !useMmap 的单支赋值" \
  "! fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
     grep -qE 'if[[:space:]]*\\([[:space:]]*!useMmap[[:space:]]*\\)[[:space:]]*mp\\.load_mode'"

# ── ④ 不得改用库的字符串解析（那是第三方行为，本仓库只信编译期常量）────
c "不用 llama_load_mode_from_str 解析（只用编译期常量）" \
  "! fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
     grep -q 'llama_load_mode_from_str'"

# ── ⑤ 可观测：交给库的那一档必须进日志 ──────────────────────────────────
# 没有这一行，"开关生效没有"只能靠事后读 /proc/<pid>/maps；
# 而本轮故障的判据恰恰是"日志里能不能读到实际交给库的档位"。
c "把实际交给库的 load_mode 打进日志（含 mmap= 与档位名）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
   grep -q '传给库的 load_mode'"

c "两档名字都在源码里（日志可自证，不是只报数字）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
   grep -q '\\\"MMAP\\\"' && \
   fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
   grep -q '\\\"NONE\\\"'"

# ── ⑥ 判据网自身不得只锚「存在性」────────────────────────────────────────
c "本守卫锚的是三目赋值与赋值计数，不是『常量出现过』" \
  "grep -q 'grep -c ' tools/run_mmap_guard.sh && \
   grep -q 'useMmap ? LLAMA_LOAD_MODE_MMAP' tools/run_mmap_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "=== mmap 开关守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 开关守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
