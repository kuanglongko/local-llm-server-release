#!/bin/sh
# 「卸载后归还空闲堆段」不得绕过分配器去猜匿名段（ISSUE #274 的闪退根因）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么需要这条守卫：一次真实闪退，且是"看起来更彻底"的那个写法造成的
# ═══════════════════════════════════════════════════════════════════════════
# `nativeReclaimReleasedHeap` 的第一版除了 `malloc_trim(0)`，还顺手补了一段
# "从 /proc/self/maps 挑最大的 `rw-p` 私有匿名区间、对整段 DONTNEED"，注释写着
# 「私有匿名段里只会有本进程的堆/静态区，DONTNEED 不影响任何被引用的数据」。
#
# **这句是错的**：bionic 的 scudo 把主堆做成一整块 `rw-p` 匿名映射，里面
# 活对象与空闲块**混在一起**。DONTNEED 按页清零、不看谁在用 —— 于是活对象
# （含 scudo 的 chunk 头）被抹成 0，下一次 `free()` 读到全 0 的头就 abort：
#
#   [stderr] Invalid 0x732a679000 passed to free: value not allocated,
#            chunk: 0x732a600000 mapbits: 0 msg:
#
# 症状：用户在"加载对话模型"时闪退（实际崩点在卸载→重载交替处的下一次 free）。
#
# ⚠ 这与 `release_populated_pages`（文件映射按路径 DONTNEED）**同形但不同质**：
#   文件映射的页是干净的，丢了自己会缺页读回；匿名堆里的页装着活数据。
#   所以判据必须区分「按路径认出来的文件映射」与「按大小猜出来的匿名段」——
#   前者允许，后者禁止。这条守卫钉的就是这个区分，而不是"有没有 DONTNEED"。
#
# 运行：sh tools/run_reclaim_heap_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥注释：修复注释里**故意**写着这些关键词（讲清那条错路），不剥掉会因为
# "注释提过"而误判。剥字符串字面量：日志文案里也会出现这些词。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*'; }
stripstr() { sed -e 's/"[^"]*"/""/g' ; }
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

R="$JNI"
reclaim() { fnbody "$R" 'Java_com_xiaowan_localinference_LlmEngine_nativeReclaimReleasedHeap'; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"

# ── ① 归还函数本体不得出现任何匿名段 DONTNEED ────────────────────────────
# 判据是"在真正的函数体里、剥掉注释与字符串之后，还有没有 madvise/DONTNEED"。
# 只 grep 全文件是不行的：文件别处（release_populated_pages）**合法地**用了
# DONTNEED —— 那一条要留，所以必须限定在归还函数的函数体内。
c "归还函数体内不再有 madvise 调用（剥注释/字符串后）" \
  "! reclaim | nocomment | stripstr | grep -qE 'madvise[[:space:]]*\\('"

c "归还函数体内不再有 MADV_DONTNEED 字面量" \
  "! reclaim | nocomment | stripstr | grep -q 'MADV_DONTNEED'"

c "归还函数体内不再扫 /proc/self/maps 挑区间（不许'按大小猜哪段空闲'）" \
  "! reclaim | nocomment | stripstr | grep -q '/proc/self/maps'"

# 这条防"改个名字绕过去"：归还函数体里不得再出现 'rw-p' 这类权限判据 ——
# 出现即说明又在按权限挑匿名段，而不是交给分配器判断。
c "归还函数体内不按 rw-p 权限挑段" \
  "! reclaim | nocomment | stripstr | grep -q 'rw-p'"

# ── ② 合法的那一条路必须还在：malloc_trim，且只在 glibc 上 ──────────────
c "归还函数仍走 malloc_trim(0)" \
  "reclaim | nocomment | grep -qE 'malloc_trim[[:space:]]*\\([[:space:]]*0[[:space:]]*\\)'"

c "malloc_trim 由 __GLIBC__ 门控（bionic 上不编进来，也就不会误报'已归还'）" \
  "reclaim | grep -q '__GLIBC__'"

c "malloc_trim 的 include 也由 __GLIBC__ 门控" \
  "grep -q '#if defined(__GLIBC__)' \$JNI && grep -q '#include <malloc.h>' \$JNI"

# ── ③ 自证仍在：RSS 前后值必须进日志，且不得把 no-op 写成"已归还" ──────
# ⚠ 判据锚「真的从 proc_rss_kb 读了这个量」，不是"变量名出现过"：
#   只 grep `before_kb` 的话，把 `= proc_rss_kb()` 换成 `= -1` 照样绿
#   （自测④就是这个形态，它一开始真的骗过了这一条）。
c "归还结果仍自证（RSS 前后各真读一次 /proc）" \
  "reclaim | nocomment | grep -qE 'before_kb[[:space:]]*=[[:space:]]*proc_rss_kb\\(\\)' && \
   reclaim | nocomment | grep -qE 'after_kb[[:space:]]*=[[:space:]]*proc_rss_kb\\(\\)'"

# 这一条**要看字符串**：文案本身就是判据（"不还"这句话必须真出现在日志里），
# 所以这里不 stripstr —— 与其余几条刚好相反，写清以免后来人"顺手统一"掉。
#
# ⚠ 只判『出现了"本 libc 无此接口"』是不够的（自测⑤证明它会被改动绕过：
#   把它挪进"已归还（本 libc 无此接口）"里照样命中）。真正要钉的是
#   **这句话不能被"已归还"包着** —— 因为 `freed_kb <= 0` 之后本函数**什么都不做**，
#   写"已归还"就是一条恒真的假读数。
c "bionic 无接口时如实说'不还'，不写成恒真的'已归还'" \
  "reclaim | nocomment | grep -q '本 libc 无此接口' && \
   ! reclaim | nocomment | grep -q '已归还（本 libc'"

# ── ④ 文件映射那条 DONTNEED **必须留着**（别把这条守卫读成"禁 DONTNEED"）──
c "release_populated_pages 仍对按路径认出的映射 DONTNEED（那条是安全的）" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | \
     stripstr | grep -qE 'madvise[[:space:]]*\\([^;]*MADV_DONTNEED'"

c "release_populated_pages 的判据是**按路径**认映射，不是按大小" \
  "fnbody \$JNI 'static int release_populated_pages(' | nocomment | grep -q 'maps_line_is_path'"

# ── ⑤ Kotlin 侧的说明不得再描述已被移除的行为 ───────────────────────────
c "Kotlin 卸载收尾仍调用归还这一脚" \
  "grep -q 'nativeReclaimReleasedHeap()' \$KT"

c "Kotlin 注释不再把 DONTNEED 描述成'对已释放块没有副作用'" \
  "! grep -q '对\*\*已释放\*\*的块没有副作用' \$KT"

# ── ⑥ 判据网自身不得只锚「存在性」───────────────────────────────────────
c "本守卫锚的是函数体结构，不是『关键词出现过』" \
  "grep -q 'fnbody' tools/run_reclaim_heap_guard.sh && \
   grep -q 'reclaim()' tools/run_reclaim_heap_guard.sh && \
   grep -q 'stripstr' tools/run_reclaim_heap_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "=== 归还空闲堆段守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 归还空闲堆段守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
