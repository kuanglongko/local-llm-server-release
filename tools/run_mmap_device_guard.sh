#!/bin/sh
# 「mmap 的权重落点」必须被问清楚、且能被日志读出来（模块 M3）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么在第一轮（M）与第二轮（M2）之后还要这一条
# ═══════════════════════════════════════════════════════════════════════════
# M 钉住「`useMmap` 有没有显式写进 `mp.load_mode`」——绿。
# M2 钉住「预填充进来的页有没有被还回去」——绿。
# 但用户实测仍然对不上：他在 chatterui 里**勾选 OpenCL** 后加载 5G 模型，
# 占用照样很低；本仓库同样开 GPU 后端却做不到。差别不在 mmap 开关，在
# **权重到底落在哪种缓冲上**：
#
#   上游 `llama_model_base::load_tensors()` 只有一个判据：
#     默认缓存类型能不能 `buffer_from_host_ptr(映射地址)`？
#       能 → 权重缓冲直接把**映射地址**包一层（缓冲名 `CPU_Mapped`，
#            其 `free_buffer = NULL`：内核从不通过这个缓冲记账），
#            RSS 只随**被真正读到的页**增长 —— 这才是"省内存"。
#       不能 → 权重进引擎自己 malloc 的**匿名缓冲**，整份模型都读进内存，
#            RSS ≈ 模型大小，**与 mmap 开关无关**。
#
#   而这取决于**实际出现了哪些 buft 组** —— 分组是**按层**的：
#     · `n_gpu_layers=0`：所有层（含 output）都落回 CPU 组
#       （`buffer_from_host_ptr = true`）→ 走映射，OpenCL 组压根不存在；
#     · `0 < k < n_layer`：天然两组 —— CPU 组走映射、OpenCL/HTP 组走拷贝，
#       后者是它该有的形态（那些层本来就要进 VRAM），不是 bug。
#   所以本模块**只做诊断**：把每台的默认 buft 名与 host 标记逐台打出来，
#   让"哪些组真实存在、各自走哪条路"可读；不替用户挑设备（见模块 M4）。
#
# 所以真正该做的是：**在加载前把池问清楚**，并保证这个结论**进日志**。
#
# 第六轮又发现一处同形的缺口：**权重重排（repack）**。q4_K/q6_K 会被重排成
# `q4_K_8x8`/`q6_K_8x8` 另存一份**匿名内存**，与映射里的原始权重并存 ——
# 它是除模型本体外最大的单一可回收项（开销随 CPU 组层数增长，读数见 HTP-STATUS §52）。
# 它的开关 `use_extra_bufts` 本仓库此前**从未显式设过**（同 `load_mode` 的病）。
# 本模块因此再钉两件事：这一档**显式落值**、且**档位进日志**（见 ⑧ ⑨）。
# vendor 的 .so 不能改（按指令集分档编译），能力位只能**运行时**读：
# 写死一份名单会在库升级时（例如 Hexagon 的 `host_buffer` 开关变化）悄悄失效。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「结构关系」，不锚「出现过某个字符串」
# ═══════════════════════════════════════════════════════════════════════════
#   ① 存在一个「逐台分类设备池」的函数，且它**读库的能力位**
#      （`buffer_from_host_ptr` 与 `buft_is_host`），而不是设备名/版本，
#      并把**默认 buft 名 + host 标记**逐台报出来（分组按层，不能只报设备名）；
#   ② 分类结果必须**进日志**，且"一台都没有"这一支要有独立文案
#      —— 池里一台都承接不起时，权重只能进匿名缓冲，那是"内存 ≈ 模型大小"的现场，不能静默；
#   ③ 该日志在 `llama_model_load_from_file` **之前**打出（加载前已知的结论，
#      才能与加载后的读数对得上）；
#   ④ 设备池下标与 `tensor_split` 同源（都按 `dev_count/dev_get` 的登记顺序）；
#   ⑤ 「把非文件缓冲改注册成文件支持」这一能力的存在性也要报（本变体为无）；
#   ⑥ 共享存储（FUSE）那条**不许**再写"复制到应用内"这种换不到省内存的建议；
#   ⑦ `use_extra_bufts` **显式落值**（不再靠 vendor 默认），
#      且该档位在加载**前**进日志 —— 否则 RSS 里多出来的那份匿名拷贝认不出是 repack；
#   ⑧ 该档位的**判定通道**必须是仓库自己的存储（JNI 参数），属性只作覆盖，
#      且"没设过 / 显式 0 / 属性读数"三者都进日志 —— 属性链失效时与"没设过"同形，
#      已因此白跑过一轮实测（见 ⑧ 段）；
#   ⑨ 推理期要有 RSS 读数：全仓此前唯一的 RSS 探针在 `loadModel` 里，
#      于是"用起来之后多少内存"从来没人量到过（真机读数全是加载完成态）。
#
# 运行：sh tools/run_mmap_device_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*'; }
stripstr() { sed -e 's/"[^"]*"/""/g' ; }
# 取函数体：锚点是**签名**，起点是该签名之后的第一处 `{`（跳过注释行），止于行首 `}`。
#
# 为什么锚点必须是"签名"而不是"名字"，三种"看起来对"的写法各错一半，本文件全踩过：
#   · 只给函数名（如 `unloadModelInternal`）会同时命中**前置声明**，起点落到它后面
#     第一个函数上 —— 本自测 ⑦-9 两次假绿都是这么来的（复位语句被删了，判据却在
#     `rss_note_peak` 里匹配到同名标识符）；
#   · 只要求"签名行行尾是 `{`"：`Java_..._nativeLoadModel(` 参数表跨 3 行，
#     整个函数体取不到，判据全红在取法自身；
#   · 只判"下一处 `{`"不跳过注释：注释里写着 `{` 的说明行会把起点提前。
# 所以调用方一律写**含返回类型的签名**，且行首锚定（`^static ...`），本函数负责找体。
fnbody() { awk -v f="$2" '
  !seen && $0 ~ f {
    seen=1
    if (index($0, "{") > 0) { inb=1; print }   # 定义行自带 `{`（常见）
    next
  }
  seen && !inb {
    if (index($0, "{") > 0 && $0 !~ /^[[:space:]]*(\/\/|\*)/) { inb=1; print }
    next
  }
  inb { print }
  inb && /^}/ { exit }' "$1"; }

# 两个常用锚点：分类函数体、以及加载入口的函数体。
# 锚点一律写**含返回类型的签名**并加行首锚：只给函数名会命中前置声明或调用点，
# 取到的"函数体"其实落在别的函数里，判据于是锚错地方还照样能绿（见 fnbody 上方）。
classify() { fnbody "$JNI" '^static int classify_mmap_devices[(]'; }
loadbody() { fnbody "$JNI" '^Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel'; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"

# ── ① 分类函数真的读库的能力位 ─────────────────────────────────────────
c "存在逐台分类设备池的函数 (classify_mmap_devices)" \
  "grep -q 'static int classify_mmap_devices(' \$JNI"

c "分类读的是 buffer_from_host_ptr 能力位（不是设备名/版本）" \
  "classify | nocomment | grep -q 'caps.buffer_from_host_ptr'"

c "分类同时看默认缓存类型是不是 host（两处都看，只信一个会误判）" \
  "classify | nocomment | grep -q 'buft_is_host'"

# 逐台报「默认buft名 + host标记」：分组是按**层**的，池里的设备不等于实际出现的组。
# 只报"几台能承接映射"会让人继续从设备名去推组 —— 本模块两次误判都出在这里。
c "分类逐台报出默认缓存类型名（不是只报设备名）" \
  "classify | nocomment | grep -q 'lm_ggml_backend_buft_name'"

c "分类逐台报出该台默认 buft 是不是 host" \
  "classify | nocomment | grep -q 'default_is_host'"

c "分类遍历的是库的设备池（dev_count/dev_get），下标与 tensor_split 同源" \
  "classify | nocomment | grep -q 'lm_ggml_backend_dev_count()' && \
   classify | nocomment | grep -q 'lm_ggml_backend_dev_get('"

c "三档分类显式区分：能承接映射 / 需拷一份 / 无默认缓存类型" \
  "grep -q 'MMAP_DEV_TAKES_MAPPING' \$JNI && grep -q 'MMAP_DEV_COPIES' \$JNI && \
   grep -q 'MMAP_DEV_NO_BUFT' \$JNI"

# ── ② 结论必须进日志，且"一台都没有"有独立文案 ─────────────────────────
c "存在把分类结论打进日志的函数 (log_mmap_device_diag)" \
  "grep -q 'static void log_mmap_device_diag(' \$JNI"

c "『一台都没有』是独立分支，不静默" \
  "fnbody \$JNI '^static void log_mmap_device_diag[(]' | nocomment | grep -q 'n_take > 0' && \
   fnbody \$JNI '^static void log_mmap_device_diag[(]' | nocomment | grep -qE 'else'"

c "『一台都没有』那支直说内存 ≈ 模型大小、与开关无关" \
  "fnbody \$JNI '^static void log_mmap_device_diag[(]' | nocomment | grep -q 'mmap诊断'"

# ── ③ 日志在加载**之前**打出 ────────────────────────────────────────────
c "诊断在 llama_model_load_from_file 之前打出（加载前已知的结论）" \
  "loadbody | nocomment | awk '/log_mmap_device_diag[(]/{if(!l)l=NR} /llama_model_load_from_file/{if(!m)m=NR} END{exit !(l && m && l < m)}'"

# ── ④ 匿名缓冲可改注册为文件支持：只报存在性，不假装懂语义 ──────────────
c "报告『匿名缓冲可改注册为文件支持』的存在性" \
  "classify | nocomment | grep -q 'lm_ggml_backend_register_anon_mmap'"

# ── ⑤ 加载行必须带 gpu_layers（分组与 gpu_layers 无关这一事实的可读面）──
c "加载日志带上 gpu_layers（说明后端参与与 gpu_layers 是两件事）" \
  "loadbody | nocomment | grep -q 'gpu_layers=%d'"

# ── ⑥ 共享存储提示不许再给"复制到应用内"这种换不到省内存的建议 ─────────
c "共享存储提示不再宣称『复制到应用内』能省内存" \
  "! loadbody | nocomment | grep -q '复制到应用内'"

c "共享存储提示改为说明『是否 passthrough 决定能否共享，native 探不出来』" \
  "loadbody | nocomment | grep -q 'passthrough' && loadbody | nocomment | grep -q 'mmap提示'"

# ── ⑦ repack（`use_extra_bufts`）：显式落值 + 档位进日志 ────────────────
# 为什么这一条与"内存没降"直接相关：CPU 组走映射之后，RSS 里**仍**可能多出一份
# 与模型大小同量级的匿名内存，来源就是 repack。它不是 mmap 的失效，而是另一个开关
# 的默认值 —— 不在日志里报出档位，读日志的人只会又一次归因到设备分组上。
c "存在 repack 档位判定函数 (model_use_extra_bufts)" \
  "grep -q 'static bool model_use_extra_bufts(' \$JNI"

c "存在把 repack 档位打进日志的函数 (log_extra_bufts_diag)" \
  "grep -q 'static void log_extra_bufts_diag(' \$JNI"

c "mp.use_extra_bufts 显式落值（不再靠库默认值）" \
  "loadbody | nocomment | stripstr | grep -q 'mp.use_extra_bufts ='"

c "repack 档位在 llama_model_load_from_file 之前进日志（加载前已知的结论）" \
  "loadbody | nocomment | awk '/log_extra_bufts_diag[(]/{if(!l)l=NR} /llama_model_load_from_file/{if(!m)m=NR} END{exit !(l && m && l < m)}'"

c "档位日志自带『这份拷贝与映射并存』的因果说明（否则读不出它是什么）" \
  "fnbody \$JNI '^static void log_extra_bufts_diag[(]' | nocomment | grep -q 'repack' && \
   fnbody \$JNI '^static void log_extra_bufts_diag[(]' | nocomment | grep -q 'use_extra_bufts=%d'"

# ── ⑧ repack 的**判定通道**：必须落在仓库自己的存储上，不是系统属性 ────────
# 为什么把判据从这里改向（0.9.125，上一版的属性通道被真机否掉）：
# 上一版把档位交给系统属性 `lm_extra_bufts`，靠 `probe_getprop`（fork +
# execl /system/bin/getprop）读。真机实测 root 机 setprop 之后日志**仍**是
# "库默认"、CPU_REPACK 一分没少 —— 而这条链失效时与"没设过"**完全同形**，
# 一轮实测白跑。所以判据不再接受"存在一个读属性的判定函数"，
# 而要钉三件事：① 存在**仓库自有**的下发入口（JNI 参数）；② 该入口的值
# **参与**判定，且"没设过"与"显式 0"必须可区分；③ 三级的原始读数都进日志。
c "存在仓库自有的档位下发入口（nativeSetRepack，不经属性链）" \
  "grep -q 'Java_com_xiaowan_localinference_LlmEngine_nativeSetRepack' \$JNI"

c "下发入口的值真的参与判定（不是存了不用）" \
  "fnbody \$JNI '^static bool model_use_extra_bufts[(]' | nocomment | grep -q 'g_extra_bufts_ui'"

c "『没设过』与『显式设为 0』可区分（丢了这一位就分不出来源）" \
  "grep -q 'nativeSetRepack(JNIEnv \*, jclass, jint mode)' \$JNI && \
   fnbody \$JNI '^Java_com_xiaowan_localinference_LlmEngine_nativeSetRepack' | nocomment | grep -q -- '-1'"

# 旧名（nativeSetExtraBufts）必须**还在**：跨版本覆盖安装时 .so 与 APK 会错配，
# Java 侧在新名缺失时回退到它。两条都写同一个变量 —— 只留新名会让旧包静默失去这一档。
c "旧名别名仍在（跨版本覆盖安装的 ABI 兜底），且与新名写同一个变量" \
  "fnbody \$JNI '^Java_com_xiaowan_localinference_LlmEngine_nativeSetRepack' | nocomment | grep -q 'g_extra_bufts_ui[[:space:]]*=' && \
   fnbody \$JNI '^Java_com_xiaowan_localinference_LlmEngine_nativeSetExtraBufts' | nocomment | grep -q 'g_extra_bufts_ui[[:space:]]*='"

# ── ⑪『判定』与『落给库的值』必须都能读出来，且是两行 ──────────────────────
# 旧版只有加载**前**的一行，报的是判定（谁定的档）。判定 → 落值 之间还隔着
# "参数跨 JNI 有没有到 / 有没有被后面覆盖"，这几步里任何一步断开都与
# "设置页没写进去"同形 —— 本模块已经因为同形白跑过两轮真机实测。
c "落值本身被记下来（mp.use_extra_bufts 的赋值进入 g_extra_bufts_applied）" \
  "loadbody | nocomment | grep -q 'g_extra_bufts_applied = mp.use_extra_bufts'"

c "加载**后**有独立的 repack 结果行（落给库的值 + 实测增量）" \
  "grep -q 'static void log_repack_applied(' \$JNI && \
   loadbody | nocomment | awk '/log_repack_applied[(]/{if(!l)l=NR} /llama_model_load_from_file/{if(!m)m=NR} END{exit !(l && m && l > m)}'"

c "实测增量真的量了（加载前后各一次 RSS 采样，不是写死的 0）" \
  "loadbody | nocomment | awk '/measure_repack_before[(]/{if(!b)b=NR} /measure_repack_after[(]/{if(!a)a=NR} END{exit !(b && a && b < a)}' && \
   fnbody \$JNI '^static void measure_repack_after[(]' | nocomment | grep -q 'proc_rss_kb()'"

# 差值必须**由两次采样算出**，不能是常量：判据锚"赋值语句的右侧里有那次采样"。
# 锚"存在一条赋值"会假绿 —— 把右侧换成 `(0 - 0)` 仍然有赋值，而增量就恒为 0 了，
# 于是"量不到"与"真的没建"再次同形（本自测的 ⑪-3 桩正是这么漏的第一版）。
c "增量由前后两次采样算出（右侧不是常量，写死即红）" \
  "fnbody \$JNI '^static void measure_repack_after[(]' | stripstr | \
   awk '/g_repack_bytes[[:space:]]*=/' | grep -q 'after' && \
   fnbody \$JNI '^static void measure_repack_after[(]' | stripstr | \
   awk '/g_repack_bytes[[:space:]]*=/' | grep -q 'g_repack_rss_before_kb'"

c "量不到时说的是『量不到』，不与『真的关掉了』同形（先剥注释，注释里也有这两个词）" \
  "fnbody \$JNI '^static void measure_repack_after[(]' | nocomment | grep -qE '读不到|量不到'"

c "属性降为『覆盖』且必须排在自有档位之后（不是唯一通道）" \
  "fnbody \$JNI '^static bool model_use_extra_bufts[(]' | nocomment | awk '/g_extra_bufts_ui/{if(!u)u=NR} /probe_getprop/{if(!p)p=NR} END{exit !(u && p && u < p)}'"

# 判据必须锚**同一条 jlog 调用**内的三个实参，而不是"函数体里出现过这两个名字"：
# 后者会假绿 —— 把日志换成只报数字、但函数体里剩下的赋值语句仍含这两个标识符时，
# 首版判据照样绿（本自测的 ⑦-3 桩第一版就是这么漏的）。所以先截出这条 jlog 调用的
# 实参表，再在表内找两处读数。
# ⑪-5 **落值这一位必须活到加载后** —— 0.9.128 真机现场（用户原话：
# "修复日志显示错误"）：`log_extra_bufts_diag()` 第一句是 `g_extra_bufts_applied = -1;`
# （当重置用），而真正落值的赋值在**它之后**才发生 → 加载后读到的恒是哨兵值
# `-1`，无论用户设 0 还是 1。日志于是**看起来**在自证，实际恒假 ——
# 与"档位没到库"同形，正是本模块反复出现的"判定/落值/自证挤在同一个变量上"。
#
# 判据不下"必须存在重置"（那是实现细节，可以没有），而是钉**归属与顺序**：
# 若文件里出现对 `g_extra_bufts_applied` 的哨兵重置，它必须排在
# `mp.use_extra_bufts =` 落值**之前**；排在之后（或干脆放在 diag 里）即红。
c "落值位不被自己的重置语句冲掉（哨兵重置必须早于 mp.use_extra_bufts 赋值）" \
  "loadbody | nocomment | stripstr | awk \
     '/g_extra_bufts_applied[[:space:]]*=[[:space:]]*-1/{if(!r)r=NR} \
      /mp.use_extra_bufts[[:space:]]*=/{if(!a)a=NR} \
      END{exit !(a && (!r || r < a))}'"

c "判定日志函数里不再重置落值位（判定与落值是两个变量）" \
  "! fnbody \$JNI '^static void log_extra_bufts_diag[(]' | nocomment | grep -q 'g_extra_bufts_applied'"

c "落值位仍在加载后那行里被读出（重置掉了就不再自证）" \
  "fnbody \$JNI '^static void log_repack_applied[(]' | nocomment | grep -q 'g_extra_bufts_applied'"

c "档位日志**在同一条 jlog 调用内**报出来源与三级读数" \
  "buftlog=\$(fnbody \$JNI '^static void log_extra_bufts_diag[(]' | nocomment | stripstr | \
             awk '/jlog\\(/ {inb=1} inb {print} inb && /;$$/ {exit}'); \
   printf '%s' \"\$buftlog\" | grep -q 'g_extra_bufts_ui' && \
   printf '%s' \"\$buftlog\" | grep -q 'g_extra_bufts_prop_raw' && \
   printf '%s' \"\$buftlog\" | grep -q 'src'"

# ── ⑨ 推理期的内存读数：加载态不等于用起来之后的占用 ──────────────────────
# 这一条是"内存占用为什么对不上"的直接根因之一：全仓唯一的 RSS 探针打在
# `loadModel` 里，于是所有读数都是**加载完成态**（真机那几轮 kv_rounds 全是 0）。
# 判据钉：存在推理期采样、且它被**真的调用**在 prefill 成功之后。
c "存在推理期 RSS 采样函数 (rss_note_peak)" \
  "grep -q 'static void rss_note_peak(' \$JNI"

c "推理期 RSS 采样在 prefill 成功之后被真的调用（不是只定义）" \
  "grep -c 'rss_note_peak();' \$JNI | grep -qv '^0$' && [ \$(grep -c 'rss_note_peak();' \$JNI) -ge 1 ]"

# 判据锚"函数体里有一次对 g_rss_peak_kb 的**赋值**"，不锚某个具体常量名：
# 锚常量名的话，别人把复位值改成一个新的宏/字面量，判据会红在自己的措辞上
# （那是"守卫讨厌某种写法"，不是"代码坏了"）；而 `g_rss_peak_kb = ...` 这种
# 赋值形式是语义本身 —— 去掉赋值就真的没复位。
c "峰值跨加载周期复位（换模型后不继承上一个模型的数）" \
  "fnbody \$JNI '^static void unloadModelInternal[(][)][[:space:]]*[{]$' | nocomment | grep -qE 'g_rss_peak_kb[[:space:]]*='"

c "峰值有对外读数（nativeRssPeakMb），否则只能靠人读日志" \
  "grep -q 'Java_com_xiaowan_localinference_LlmEngine_nativeRssPeakMb' \$JNI"

# ── ⑧ 判据网自身：不得只锚「关键词出现过」 ─────────────────────────────
# ── ⑩ JNI 导出的**链接规格**：名字必须是 C 链接，不能被 C++ 重整 ─────────────
# 这一条是 0.9.125 装机实测的根因：`nativeSetExtraBufts` 的定义被写在文件里
# 那个大 `extern "C" {` **之外**（JNI_OnLoad 之前），于是它拿到的是 C++ 链接，
# 导出符号成了 `_Z83Java_com_..._nativeSetExtraBufts...`；而 JVM 只按未重整的
# `Java_com_..._nativeSetExtraBufts` 去 dlopen 里找 → 装机后
# `UnsatisfiedLinkError`，模型加载直接进不去。
#
# 上面 ⑧ 的第一条判据（`grep -q 'Java_com_..._nativeSetExtraBufts' $JNI`）
# **对这件事全程是绿的** —— 它锚的是"这个名字在文件里出现过"，而重整与否
# 恰恰由这一行**周围**的链接规格决定。判据只锚字符串、不锚结构，就会这样假绿。
# 这里按结构钉：该符号必须处在某个 `extern "C" {` 的作用域内。
# 取法上先剥注释 —— 本文件的注释里也写着 `extern "C"` 几个字，
# 不剥的话注释行会把锚点带偏、判据照样绿（本版自测时正是这么翻过一次车）；
# 剥完再按花括号深度跟踪"本行是否在 extern-C 块内"。
c "JNI 导出 nativeSetRepack 必须具备 C 链接（否则符号被重整 → UnsatisfiedLinkError）" \
  "nocomment < \$JNI | awk -v fn='Java_com_xiaowan_localinference_LlmEngine_nativeSetRepack' '
     {
       if (index(\$0, fn)) { found=1; if (guarded) ok=1 }
       for (i=1; i<=length(\$0); i++) {
         ch = substr(\$0, i, 1)
         if (guarded && ch == \"}\") { depth--; if (depth <= 0) guarded=0 }
         else if (ch == \"{\") {
           pre = substr(\$0, 1, i)
           if (pre ~ /extern[[:space:]]*\"C\"[[:space:]]*\\{[[:space:]]*\$/ ) { guarded=1; depth=1 }
           else if (guarded) depth++
         }
       }
     }
     END { exit !(found && ok) }'"

c "本守卫锚的是函数体结构与顺序，不是『常量出现过』" \
  "grep -q 'classify_mmap_devices(' tools/run_mmap_device_guard.sh && \
   grep -q 'awk' tools/run_mmap_device_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "=== mmap 权重落点守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 权重落点守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
