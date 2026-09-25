#!/bin/sh
# 「mmap 真省内存」必须有两件事同时成立的源码级守卫（模块 M2）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么在第一轮（模块 M）之后还要这一条
# ═══════════════════════════════════════════════════════════════════════════
# 模块 M 钉住的是「`useMmap` 有没有显式写进 `mp.load_mode`」。它现在是绿的，
# 日志也能读到 `load_mode=MMAP` —— 但用户实测**内存纹丝不动**。
# 原因是：`load_mode` 只决定「映射建不建」，挡不住上游顺手把整份权重读进内存：
#
#   librnllama 的 `load_tensors()` → `ml.init_mappings(true, …)`
#   prefetch **硬编码 true** → `llama_mmap(file, /*prefetch=*/-1, …)`
#   （形参 size_t，-1 即 SIZE_MAX）：
#     · `if (prefetch && …) flags |= MAP_POPULATE;` → mmap 时内核把整个 GGUF 灌进物理内存；
#     · `if (prefetch > 0)` 恒真 → 再对整个文件 POSIX_MADV_WILLNEED。
#
# 于是「映射上了、RSS 照样 ≈ 模型大小」，与用户报的「占用都是模型大小加 kv 大小」逐字吻合。
# `load_mode` 那一档是对的，**但它不是全部**：省内存要两件事都给上。
#
# 本仓库不改写 vendor 的 .so（见 vendor/*/README.md：按指令集分档编译，改了探测失效），
# 所以修复放在**加载之后**：把预填充进来的干净页还给内核。
#
# ⚠ 第一版在这里写错了做法，本守卫**当时是绿的**，用户那边 RSS 一页没降：
#     「自己再 mmap 同一个文件 + `MADV_DONTNEED`（号称同一份 page cache，
#       库那侧的页一并放掉）」
#   前半句对、后半句错：`MADV_DONTNEED` 只清**调用它的那个 VMA** 的页表项，
#   库那份映射是**另一个 VMA**，页表项原封不动 —— `madvise` 返回 0、日志照打
#   「已归还」，RSS 没降。旧判据钉的正是「mmap 与 DONTNEED 同现」，于是它
#   **把错的做法判成绿**。正确做法：从 `/proc/self/maps` 找出**库那份映射**的区间，
#   对那些地址 DONTNEED。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「结构关系」，不锚「出现过某个常量 / 某句话」
# ═══════════════════════════════════════════════════════════════════════════
#   ① 存在一个「归还预填充页」的函数，且它**从 /proc/self/maps 找出库那份映射、
#      对找到的地址调 madvise(MADV_DONTNEED)** —— 而不是"自己另建一份映射"
#      （后者是错的：DONTNEED 的作用域只有单个 VMA，碰不到库那份）；
#      且**必须报出实际归还的区间条数/字节数**（只报"已归还"是恒真的）；
#   ② 该函数只在 `useMmap` 为真时被调用（mmap=0 是直读档，对它 DONTNEED 反而更慢）；
#   ③ 调用点在**加载成功之后**（`llama_model_load_from_file` 之后、且在其非空检查之后）；
#   ④ 归还结果必须进日志（RSS 前后 + 文件映射驻留）—— 这是本轮唯一的自证点；
#   ⑤ 共享存储（FUSE）路径必须给明确提示 —— 那条路径下 mmap 原理上就省不了内存。
#
# 判据刻意不锚「MADV_DONTNEED 这个字符串出现在文件里」：那在注释里也恒真。
# 锚的是「在真正的函数体里、走 maps 找库映射那条路、且被 useMmap 门控、且在加载之后被调」。
# 尤其**不锚"存在 mmap 调用"** —— 那正是第一版错法满足的条件，而它是假绿。
#
# 运行：sh tools/run_mmap_release_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp


ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释：判据是"源码里有没有这种写法"，而修复注释里就写着这些关键词。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*'; }
# 抹掉字符串字面量：判据要锚「真的调了这个函数」，而日志文案里**同样**会出现
# `madvise(MADV_DONTNEED)` 这样的字面量 —— 不剥掉的话，「删掉真调用、只留一条
# 错误日志」也能让判据变绿（自测①就是这个形态）。
stripstr() { sed -e 's/"[^"]*"/""/g' ; }
# 抓以给定签名开头的**定义**函数体。
# ⚠ 这一版**按花括号深度**收尾（不是"遇到行首 } 就停"）：
#   旧版会在函数体内部第一个缩进为 0 的 `}` 上截断 —— 而 C/C++ 的
#   `#if ... { ... } #else ... #endif` 预处理器分支恰好会让一个 `}` 落在行首
#   （本轮的 release_populated_pages 就是），于是"函数体"只取到一半，
#   判据锚到半截代码上、该红的红不了。旧版还要求定义行自带 `(`，跨行签名同样会取错。
# ⚠ 实现上刻意**不在 awk 程序里写花括号正则**（`/[{]/` 这种）：判据用
#   `eval "$2"` 执行，awk 程序再被 shell 展开一次，花括号会被 shell 咬掉，
#   于是 awk 报 "regular expression compile failed"。用字符计数（`index` 累加）
#   代替正则计大括号，既不踩 shell 也不踩 awk。
fnbody() { awk -v f="$2" -v OPEN="{" -v CLOSE="}" '
  function braces(s,   i,ch,n){ n=0; for(i=1;i<=length(s);i++){ ch=substr(s,i,1); if(ch==OPEN)n++; else if(ch==CLOSE)n-- } return n }
  !seen && index($0, f) > 0 {
    seen=1
    if (index($0, OPEN) > 0) { inb=1; print; depth=0; depth+=braces($0); if (depth<=0) exit }
    next
  }
  seen && !inb {
    if (index($0, OPEN) > 0 && $0 !~ /^[[:space:]]*([/][/]|[*])/) {
      inb=1; print; depth=0; depth+=braces($0); if (depth<=0) exit
    }
    next
  }
  inb { print; depth+=braces($0); if (depth<=0) exit }' "$1"; }
# 取 nativeLoadModel 的函数体。
loadbody() { fnbody "$JNI" 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel'; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"

# ── ① 归还函数本体：从 /proc/self/maps 找出库那份映射，对它 DONTNEED ──
c "存在归还预填充页的函数 (release_populated_pages)" \
  "grep -q 'static int release_populated_pages(' \$JNI"

# 判据要求 madvise 与 MADV_DONTNEED 在**同一行调用**上 —— 分两处 grep 会漏掉
# 「MADV_DONTNEED 只是被引用/转发、真正调用的却是别的档」这种形态（自测①就是它）。
c "归还实现真的调了 madvise(..., MADV_DONTNEED)（同一次调用）" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | \
     stripstr | grep -qE 'madvise[[:space:]]*\\([^;]*MADV_DONTNEED'"

# 本轮**方向反转**的核心：必须走"读 /proc/self/maps + 对找到的区间操作"。
# ⚠ 这一条**不能**先 stripstr：`"/proc/self/maps"` 正是字符串字面量，
#   洗掉之后判据恒假（本守卫第一版就踩过这个）。只剥注释。
#   反过来说，"只留一条提到 maps 的错误日志"能不能骗过它？不能 —— 因为
#   同族另两条（fopen 真的打开它、以及对解析出的区间调 madvise）分别钉住，
#   只有字符串、没有真调用时那两条不会一起绿。
c "归还走 /proc/self/maps 找库那份映射（不是自己另建一份）" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | grep -q '/proc/self/maps'"

c "真的拿 maps 的地址去 DONTNEED（对解析出的区间调用，不是只打开文件）" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | stripstr | \
     grep -qE 'madvise[[:space:]]*\\([^;]*\\(void \\*\\) *lo'"

# 只钉"存在 mmap 调用"会**把第一版错法判成绿**（它就是自己另建一份映射）——
# 所以现在钉反面：函数体里**不得**出现自己另开的 mmap。
c "不再自己另建 mmap（DONTNEED 作用域只有单个 VMA，另建那份碰不到库映射）" \
  "! fnbody \$JNI 'static int release_populated_pages(' | nocomment | stripstr | grep -qE '(^|[^A-Za-z0-9_])mmap[(]'"

c "只对可读映射下手（判 perms 首字符为 r，不碰不可读的块）" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | stripstr | grep -q \"perms\[0\] != 'r'\""

c "解析 maps 块头取地址区间（strtoul 十六进制 → hi/lo，且 hi>lo）" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | stripstr | grep -q 'strtoul' && \
   fnbody \$JNI 'static int release_populated_pages(' | nocomment | stripstr | grep -qE 'hi[[:space:]]*<=[[:space:]]*lo'"

# 只报"已归还"是恒真的（第一版就是这么骗过判据的）：必须把**实际归还量**带出来。
c "归还量必须自证（区间条数 + 覆盖字节数出参）" \
  "grep -q 'int \\* n_ranges' \$JNI && grep -q 'long \\* bytes' \$JNI"

c "归还失败不影响加载（失败路径只 return -1，不抛/不 abort）" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | grep -q 'return -1'"

# ── ①b 路径判据：唯一真源 + 认内核 " (deleted)" 后缀 ──────────────────────
# 本轮真机事故：用户那条 `[mmap释放] 区间 2 个 / 5072 MB｜驻留 2601 MB → 0 MB`
# **自相矛盾** —— 若 5072 MB 真是模型映射且真被还掉，驻留必须归零。
# 根因是**两处各写一份路径判据、口径还不一样**：内核在 VMA 名字被清时会在
# maps/smaps 里追加 " (deleted)"，而旧判据要求"行尾正好是 path"，于是驻留那侧
# 认不到（报 0），释放那侧若认到了，读数就与动作脱节。
#
# 所以这里钉三件事：判据**只有一个函数**、两个读 /proc 的地方**都调它**、
# 且它认 "(deleted)"。⚠ 只钉"文件里出现过 (deleted)"是**恒真**的
# （注释里就有），必须钉它是真的被写进那个函数体、并被两处调用。
c "路径判据是唯一真源（maps_line_is_path 存在且真写在函数体里）" \
  "grep -q 'static bool maps_line_is_path(' \$JNI && \
   fnbody \$JNI 'static bool maps_line_is_path(' | nocomment | stripstr | grep -q 'plen'"

c "路径判据认内核追加的 ' (deleted)' 后缀（且只认这一个后缀）" \
  "fnbody \$JNI 'static bool maps_line_is_path(' | nocomment | grep -qF '\" (deleted)\"'"

# ⚠ 不能钉"出现过 maps_line_is_path"（定义处也算）——要钉**调用点**在两处。
# 每处至少一次调用，而**定义**那一行不含调用形态 `maps_line_is_path(` + 非 `static bool`
c "读驻留（smaps）与释放（maps）**都走同一个判据**（不许各写一份）" \
  "[ \$(grep -c 'maps_line_is_path(' \$JNI) -ge 3 ] && \
   fnbody \$JNI 'static long proc_file_mapped_kb(' | nocomment | grep -q 'maps_line_is_path(' && \
   fnbody \$JNI 'static int release_populated_pages(' | nocomment | grep -q 'maps_line_is_path('"

# 反面：两处**不得**再各自手写"行尾正好是 path"那套判据（就是它漏掉 deleted 的）。
# 判据只钉在那一个 helper 里可以有 strcmp(line + n - plen, path)。
c "两处读 /proc 的调用点不再各自手写后缀判据（防口径再次分叉）" \
  "! fnbody \$JNI 'static int release_populated_pages(' | nocomment | stripstr | grep -q 'strcmp(line + n - plen'"

# ⚠ 只钉"出现过 n_deleted"是**判存在**（声明、赋值、`(void)` 都能满足）——
#   本轮自测的桩⑫ 正是这么打进去的：把日志实参换成常量，判据照样绿。
#   所以钉**结构**：那半个格式串与 `n_deleted` 必须在同一条 jlog 里、且
#   `n_deleted` 出现在**实参**位置（`n_deleted > 0 ?` 这种三目里）。
c "命中方式自证（deleted 计数被真读进 jlog 实参，不是只声明）" \
  "loadbody | nocomment | grep -qF '(deleted) 后缀' && \
   loadbody | nocomment | grep -qE 'n_deleted[[:space:]]*>[[:space:]]*0[[:space:]]*\?' && \
   fnbody \$JNI 'static int release_populated_pages(' | nocomment | stripstr | grep -q 'n_deleted++'"

# ── ② 只在 useMmap 为真时调用 ───────────────────────────────────────────
# 只 grep「出现了 if (useMmap == JNI_TRUE)」是**判存在**，不是**判归属** ——
# 本文件里不止一处用这个门控词（设备池归属那一段用的是同一个），
# 于是把归还那一段的门控删掉、只留设备池那段的，这条判据照样绿（自测④ 正是这一形态）。
# 所以改成判**归属**：`release_populated_pages(...)` 这个调用点必须在
# useMmap 的那个 if 之内（取两者行号，要求调用点落在门控之后、下一处 else 之前）。
# 而且不能只判"调用点在**某个** useMmap 门控之后" —— 本文件里前面还有一处
# 用同一个门控词（模块 M4 的钉池），那会把它顶成恒真。所以要求**紧邻**：
# 归还调用点必须就在那个 `if (useMmap == JNI_TRUE) {` 的**下一行**。
# 这一条判的是**归属**，不是"紧邻"：调用点必须落在 useMmap 那个 if 的
# 块内。为什么不再要求"紧邻下一行"：本轮调用点前多了几个**自证用的**声明
# （区间条数/字节数/文件映射驻留基线）—— 它们是同一分支内的正常语句，
# 紧邻判据会把正确实现判红（判据一旦比实现更窄，就会开始制造假红）。
# 实现放在独立脚本里：判据要按花括号深度走（函数体里有 `#if/#else` 的行首 `}`），
# 而且用 awk 写会被 `eval` 再展开一次、转义反复出错（本守卫实测踩过）。
c "归还被 useMmap 门控（调用点落在该 if 的块内，不是无条件也不是别处）" \
  "python3 tools/mmap_release_gate_check.py >/dev/null"
# ── ③ 调用点在加载成功之后 ─────────────────────────────────────────────
# 两条必须同时成立：调用点在 load_from_file 之后，且中间先判了 !S.model。
c "归还在模型加载成功之后（load_from_file 之后）" \
  "loadbody | nocomment | awk '/llama_model_load_from_file/{m=1} /release_populated_pages[(]/{if(m)e=1} END{exit !(m&&e)}'"

c "归还前先判了 !S.model（加载失败不进入归还）" \
  "loadbody | nocomment | awk '/!S.model/{f=1} /release_populated_pages[(]/{if(f)e=1} END{exit !(f&&e)}'"

# ── ④ 可观测：RSS 前后 + 文件映射驻留必须进日志 ────────────────────────
c "归还结果进日志（含『mmap释放』与 RSS 前后）" \
  "loadbody | nocomment | grep -q 'mmap释放' && \
   loadbody | nocomment | grep -q 'RSS'"

# 日志必须报**实际归还量**（区间条数/字节数）——只写"已归还"与"什么都没做"同形。
# ⚠ 只 grep "n_ranges / n_bytes 出现过"是**判存在**：把实参换成常量 0 照样绿
#   （本自测第 ④ 根桩就是这么抓出来的）。所以钉**结构关系**：
#   这两个标识符必须在 jlog 的实参位置上被**读**（`n_ranges,` / `n_bytes /`），
#   而不是"文件里出现过这个名字"。
c "日志报出实际归还量（区间条数 / 字节数被真读进 jlog，不是常量）" \
  "loadbody | nocomment | grep -qE 'jlog\\(\"?\\[mmap释放\\]\"?|jlog\\([^;]*n_ranges' && \
   loadbody | nocomment | grep -qE '^[[:space:]]*n_ranges,|n_ranges, *$' && \
   loadbody | nocomment | grep -qE 'n_bytes */'"

# 文件映射驻留必须报**前后对比**（单值无法说明"这次到底动了多少"）。
c "文件映射驻留报前后对比（before → after）" \
  "loadbody | nocomment | grep -q 'file_map_before_kb'"

# ── ④b 读数**三个时刻**必须各标出来，且「回收」的口径不得是「加载前 − 释放后」 ────
# 用户 0.9.132 那一行 `RSS 258 MB → 3086 MB（省 -2827 MB）｜ 驻留 2856 MB → 0 MB`
# **自相矛盾**：若驻留真归零（文件页没了），RSS 不可能还剩 2856 MB。
# 矛盾不在两个探针，在这行**把三个时刻压成了两个**：
#   A = 加载前（空进程，恒小）／B = 加载后释放前（峰值）／C = 释放后。
# `A - C` 被写成「省」，而 A 是空进程 ⇒ **这个数恒为负**，与释放有没有生效无关；
# 「驻留」取的是 B 之前的量，与 C 的 RSS 摆在同一行却不标时刻 ⇒ 读成自相矛盾。
#
# 所以这组判据**不判"有没有报一个差值"，判差值的两个端点是不是同一动作的两端**：
c "回收量的被减数是『释放前』（加载后量），不是『加载前』" \
  "loadbody | nocomment | grep -qE 'rss_loaded_kb' && \
   loadbody | nocomment | awk '/rss_loaded_kb *= *proc_rss_kb/{seen=1} END{exit !seen}'"

c "加载前的 baseline 不再被当作回收量的被减数（A 不得出现在回收式里）" \
  "loadbody | nocomment | grep -qE '\(rss_loaded_kb *>= *0 && *rss_after_kb *>= *0\)' && \
   ! loadbody | nocomment | grep -qE '\(rss_before_kb *>= *0 && *rss_after_kb *>= *0\)'"

c "三处读数各标时刻（『释放前』『释放后』进日志文案）" \
  "loadbody | nocomment | grep -q '释放前' && loadbody | nocomment | grep -q '释放后'"

c "回收量自证：『回收』这个词与它的算式在同一条 jlog 里" \
  "loadbody | nocomment | awk '/jlog\\(\"\\[mmap释放\\]/{inj=1} inj&&/本次释放回收/{f=1} inj&&/rss_loaded_kb/{v=1} inj&&/\\\"/{if(f&&v)ok=1} END{exit !ok}'"

c "『回收』的算式按释放前减释放后（符号不得反）" \
  "fnbody \$JNI 'Java_com_xiaowan_localinference_LlmEngine_nativeLoadModel' | nocomment | \
   grep -qE '\(rss_loaded_kb *- *rss_after_kb\)'"

c "RSS 由 /proc/self/status 真读（不是写死的数）" \
  "fnbody \$JNI 'static long proc_rss_kb()' | nocomment | grep -q '/proc/self/status'"

c "文件映射驻留由 /proc/self/smaps 真读" \
  "fnbody \$JNI 'static long proc_file_mapped_kb(' | nocomment | grep -q '/proc/self/smaps'"

# ── ⑤ 共享存储（FUSE）必须给明确提示 ──────────────────────────────────
c "共享存储路径（/storage/emulated）给明确提示" \
  "loadbody | nocomment | grep -q '/storage/emulated/'"

# 这一条的措辞在模块 M3 里被**收紧**过：上一轮写的"请复制到应用内"换不到省内存
# （私有目录也在同一挂载下），已删。现在要求的是"把因果说对"：能不能共享取决于
# 该挂载是否走 FUSE passthrough，native 探不出来 —— 由 M3 的第 ⑥ 组钉住。
c "提示里说明该路径能否共享取决于挂载语义（不再给错的『复制到应用内』）" \
  "loadbody | nocomment | grep -q 'mmap提示' && loadbody | nocomment | grep -q 'passthrough'"

# ── ⑥ 判据网自身不得只锚「存在性」─────────────────────────────────────
c "本守卫锚的是函数体结构与调用门控，不是『关键词出现过』" \
  "grep -q 'release_populated_pages(' tools/run_mmap_release_guard.sh && \
   grep -q 'MADV_DONTNEED' tools/run_mmap_release_guard.sh && \
   grep -q 'awk' tools/run_mmap_release_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "=== mmap 真省内存守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 真省内存守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
