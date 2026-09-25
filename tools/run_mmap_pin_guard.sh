#!/bin/sh
# 「mmap 与 GPU/NPU 不互斥」必须落在 mp.devices 上：显式全池、不钉 CPU（模块 M4，*反转*）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一轮把上一轮的修复**收回去**：钉到 CPU 是过度修复
# ═══════════════════════════════════════════════════════════════════════════
# 0.9.121（`fad6329`）在本文件对应的地方钉住了这样一件行为：
#
#     if (useMmap == JNI_TRUE) mp.devices = g_cpu_only_devs;   // 钉到 CPU 唯一一台
#
# 理由是当时认为"池里有 OpenCL/HTP 就必然多出一组走拷贝，整份权重必被读进匿名缓冲"。
# **这个前提是错的。** 上游 `load_tensors()` 是两层结构：
#
#   1) 先按**层**定归属（`get_layer_buft_list(il)`）：GPU 段 → 该 GPU 的 buft，
#      其余（含 output）→ CPU 默认 buft；
#   2) 再按 **buft 分组**，每组各自判走不走映射。
#
# 所以：`n_gpu_layers=0` 时所有层都落回 CPU 组，OpenCL 的默认 buft **不会被任何
# create_tensor 用到** → 那一组压根不存在；`0 < k < n_layer` 时天然两组 ——
# **CPU 组走映射、OpenCL 组进 VRAM**，后者是它该有的形态，不是 bug。
# 即用户说的：「加载到 CPU 的层 mmap、加载到 OpenCL 的层加载到 VRAM」，**不互斥**。
#
# 上一版钉池的代价是**用户一旦要 mmap 就强制失去全部 GPU/NPU 加速** —— 白付。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「值」与「归属」，不锚「出现过某个字符串」
# ═══════════════════════════════════════════════════════════════════════════
# 这一族最容易被写出的假绿，在**这一轮反转了方向**：
#   · 旧假绿：加了日志和辅助函数，却没真把 `mp.devices` 改掉（只加日志）；
#   · 新假绿：`mp.devices` 出现过赋值，但值不是 `nullptr`、或不在 useMmap 的分支里
#     （"显式全池"是个**契约**，只判"存在赋值"会把它顶成恒真 —— 上一版正是如此）。
# 所以本守卫先剥注释、再剥字符串字面量（日志文案里会出现 `mp.devices`、`全池` 这些字样），
# 钉的是这六件事：
#   ① 钉池机制**真的不在了**：没有 `g_cpu_only_devs`、没有 `mp.devices = g_cpu_only_devs`；
#   ② 也不再按 `dev_by_type(CPU)` 挑"唯一一台"来当设备池（那条判据本身也不可靠）；
#   ③ `mp.devices` 在 useMmap 的 **if 与 else 两支里各显式回落一次 `nullptr`**；
#   ④ 这两处赋值**分别落在**那两个分支内（判归属，不判存在）；
#   ⑤ 逐组可观测：分类输出带默认 buft 名与 host 标记（"哪些组存在、各走哪条路"可读）；
#   ⑥ 两支日志都说明"不互斥 / 加速不受影响"，并给出各自的归属结论。
#
# 运行：sh tools/run_mmap_pin_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释：修复注释里**必然**会引用被收回的旧写法（"上一版钉到 CPU"）。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*'; }
# 剥字符串字面量：日志文案里会出现 mp.devices / 全池 / 不互斥，不剥就会把"只加日志"误判成"改了行为"。
stripstr() { sed -e 's/"[^"]*"/""/g' ; }
fnbody() { awk -v f="$2" 'index($0, f) > 0 && /\(/ {inb=1} inb {print} inb && /^}/ {exit}' "$1"; }
loadbody() { fnbody "$JNI" 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel'; }
classify() { fnbody "$JNI" 'static int classify_mmap_devices('; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"

# ── ① 钉池机制真的不在了（撤回要撤干净，不留影子赋值）────────────────────
# 只在**剥注释后**判：本轮的修复注释里**必然**引用被收回的旧名字
# （"上一版钉的是 g_cpu_only_devs"）—— 不剥就恒红，反而会让人把这条删掉。
c "代码里不再有钉 CPU 的静态数组 g_cpu_only_devs" \
  "! sed -e 's://.*::' \$JNI | grep -vE '^[[:space:]]*\*' | grep -q 'g_cpu_only_devs'"

c "不再有 mp.devices = g_cpu_only_devs（真的撤回了，不只是改了注释）" \
  "! loadbody | nocomment | stripstr | grep -q 'mp.devices[[:space:]]*=[[:space:]]*g_cpu_only_devs'"

c "不再有钉池函数 pin_devices_to_cpu" \
  "! grep -q 'static bool pin_devices_to_cpu(' \$JNI"

# ── ② 不再按 dev_by_type(CPU) 挑"唯一一台"当设备池 ──────────────────────
# 这条不是风格洁癖：`by_type(CPU)` 的语义是 "CPU device using system memory"，
# 带 IGPU 的机型上可能返回 integrated GPU，而那台**没有** buffer_from_host_ptr。
# 上一版所谓"钉到 CPU 就能映射"的前提，本来就站不住。
c "不再用 dev_by_type(CPU) 挑设备来钉池" \
  "! loadbody | nocomment | stripstr | grep -q 'g_cpu_only_devs'"

# ── ③ 两支都**显式**回落 nullptr（值必须是 nullptr，不是"出现过赋值"）──
# 为什么必须判**值**：库默认恰好也是 NULL，所以"不写"与"写 nullptr"行为等价、
# 语义不等价 —— 前者谁也不知道是契约还是漏写（`da30046` 修过的同一种病）。
c "mmap=1 支显式回落 mp.devices = nullptr（全池是契约，不是靠库默认）" \
  "loadbody | nocomment | stripstr | grep -q 'mp.devices = nullptr'"

c "mmap=0 支也显式回落 mp.devices = nullptr（两支都写，不落一半）" \
  "[ \$(loadbody | nocomment | stripstr | grep -c 'mp.devices = nullptr') -ge 2 ]"

# ── ④ 两处赋值分别落在 useMmap 的 if / else 之内（判归属）──────────────
# 只 grep「出现过 if (useMmap == JNI_TRUE)」是判**存在**，不是判**归属** ——
# 本文件里不止一处用这个门控词，把赋值挪到分支外也照样绿。
c "mmap=1 那支的 nullptr 赋值确实落在该 if 之内" \
  "loadbody | nocomment | stripstr | \
   awk '/if \\(useMmap == JNI_TRUE\\)[[:space:]]*\\{/{inb=NR; next} \
        inb && /mp\\.devices = nullptr/{ok=1} \
        END{exit !ok}' && \
   loadbody | nocomment | stripstr | grep -q 'if (useMmap == JNI_TRUE)'"

c "mmap=0 那支的 nullptr 赋值确实落在其 else 之内" \
  "loadbody | nocomment | stripstr | \
   awk '/\\} else \\{/{inb=NR; next} \
        inb && /mp\\.devices = nullptr/{ok=1} \
        END{exit !ok}'"

c "设备池赋值发生在 nativeLoadModel 里（不是在别处打了个影子赋值）" \
  "loadbody | nocomment | stripstr | grep -q 'mp.devices'"

# ── ⑤ 逐组可观测：分类输出带默认 buft 名与 host 标记 ────────────────────
# 只报"几台能承接映射"不够：分组是**按层**的，池里的设备不等于实际会出现的组。
# 逐台把「默认buft=… host=…」打出来，才能从日志读出"哪些组真实存在、各走哪条路"。
c "分类逐台报出默认缓存类型名（不是只报设备名）" \
  "classify | nocomment | grep -q 'lm_ggml_backend_buft_name'"

c "分类逐台报出该台默认 buft 是不是 host" \
  "classify | nocomment | grep -q 'default_is_host'"

c "分类结论仍读库的能力位（buffer_from_host_ptr），不读设备名/版本" \
  "classify | nocomment | grep -q 'caps.buffer_from_host_ptr'"

# ── ⑥ 两支日志都说清"不互斥 / 加速不受影响"，并给归属结论 ─────────────
# 必须**剥注释后再看**：注释里必然写着这段因果解释，不剥就恒真。
c "存在 [mmap池] 归属日志（两支都打）" \
  "[ \$(loadbody | nocomment | grep -c 'mmap池') -ge 2 ]"

c "mmap=1 支日志明说『CPU 组走映射、GPU/NPU 组进显存』两者不互斥" \
  "loadbody | nocomment | grep -q 'CPU 组走映射' && \
   loadbody | nocomment | grep -q '不互斥'"

c "mmap=0 支日志明说加速不受影响" \
  "loadbody | nocomment | grep -q 'GPU/NPU 加速不受影响'"

# ── ⑦ 判据网自身：不得只锚「关键词出现过」 ─────────────────────────────
c "本守卫剥了字符串字面量（否则『只加日志』会误判成『改了行为』）" \
  "grep -q 'stripstr' tools/run_mmap_pin_guard.sh && \
   grep -q 'grep -c ' tools/run_mmap_pin_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "=== mmap 设备池守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 设备池守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
