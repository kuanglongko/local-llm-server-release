#!/bin/sh
# run_reclaim_heap_guard.sh 的自测：**每条判据都要能红**，且红在真的坏形态上。
#
# 为什么必须有这一条：守卫失效的形态是"全绿"，而全绿与"真的通过了"同形。
# 本条的坏形态**真实发生过**（ISSUE #274：用户加载对话模型闪退），
# 所以第一根桩就是把当时的实现原样放回去 —— 它必须判红。
#
# 桩都取自真实历史形态，不是凭空构造：
#   · ①「按大小猜匿名段 + DONTNEED」：ISSUE #274 闪退的直接实现；
#   · ②「借道 release_populated_pages 的路径判据挑匿名段」：换汤不换药；
#   · ③ 拿掉 __GLIBC__ 门控：bionic 上编不进 malloc_trim，却会打"已归还"假读数；
#   · ④ 删掉 RSS 自证：回到"只能装机读 /proc"；
#   · ⑤ 把 no-op 写成"已归还"：恒真的假读数（本仓库为此付过学费）；
#   · ⑥ 顺手把 release_populated_pages 的 DONTNEED 也删了：**过度修复** ——
#        文件映射那条是安全的，删了会丢"真省内存"的能力。
#
# 运行：sh tools/run_reclaim_heap_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

ok=0; bad=0
chk() {
    name="$1"; want="$2"
    if sh tools/run_reclaim_heap_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=$(mktemp -d "${TMPDIR:-/tmp}/reclaim_heap_XXXXXX")
cp "$JNI" "$STASH/llama_jni.cpp"
cp "$KT"  "$STASH/LlmEngine.kt"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; cp "$STASH/LlmEngine.kt" "$KT"; }
_exit_cleaned=0
_on_exit() { [ "$_exit_cleaned" = 1 ] && return 0; _exit_cleaned=1; restore; rm -rf "$STASH"; }
worktree_lock_acquire "$0"
trap _on_exit EXIT INT TERM HUP

stub() {
    name="$1"; want="$2"; prog="$3"
    cp "$STASH/llama_jni.cpp" "$JNI"
    cp "$STASH/LlmEngine.kt"  "$KT"
    printf '%s\n' "$prog" | python3 - "$JNI" "$KT"
    chk "$name" "$want"
}

# ① **本轮真机现场**：把 ISSUE #274 的实现原样放回 —— 按大小挑最大的 rw-p 匿名段、
#    整段 DONTNEED。它在真机上 abort 了进程（[stderr] Invalid ... passed to free）。
#    修复前这条判据不存在，于是它一路绿到用户手上。
stub "按大小猜匿名段整段 DONTNEED（ISSUE #274 的闪退实现）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """    const long after_kb = proc_rss_kb();
    const long freed_kb = (before_kb >= 0 && after_kb >= 0) ? (before_kb - after_kb) : -1;

    if (freed_kb <= 0) return nullptr;"""
assert old in s, "源码形状变了，本自测要一起更新"
bad = """    long madv_ok = 0, madv_fail = 0;
    void * best = nullptr; size_t best_sz = 0;
    if (FILE * mf = fopen("/proc/self/maps", "r")) {
        char line[512];
        while (fgets(line, sizeof(line), mf)) {
            unsigned long long a = 0, b = 0;
            char perms[8] = {0};
            if (sscanf(line, "%llx-%llx %7s", &a, &b, perms) != 3) continue;
            if (strcmp(perms, "rw-p") != 0) continue;
            if (strchr(line, \x27/\x27) != nullptr) continue;
            const size_t sz = (size_t) (b - a);
            if (sz > best_sz) { best_sz = sz; best = (void *) (uintptr_t) a; }
        }
        fclose(mf);
    }
    if (best && best_sz) {
        const long rc = (long) syscall(__NR_madvise, best, best_sz, MADV_DONTNEED);
        if (rc == 0) madv_ok++; else madv_fail++;
    }

""" + old
s = s.replace(old, bad, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ② 换汤不换药：借 release_populated_pages 的路径判据（maps_line_is_path）去挑匿名段
#    —— 路径判据对匿名段（没有路径）永远为假，但"借用"这个动作说明有人在往回走。
stub "借路径判据挑匿名段（换汤不换药）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """    const long after_kb = proc_rss_kb();
    const long freed_kb = (before_kb >= 0 && after_kb >= 0) ? (before_kb - after_kb) : -1;

    if (freed_kb <= 0) return nullptr;"""
assert old in s, "源码形状变了，本自测要一起更新"
bad = """    long madv_ok = 0;
    if (FILE * mf = fopen("/proc/self/maps", "r")) {
        char line[512];
        while (fgets(line, sizeof(line), mf)) {
            size_t n = strlen(line);
            while (n > 0 && (line[n-1] == \x27\\n\x27 || line[n-1] == \x27\\r\x27)) line[--n] = 0;
            if (!maps_line_is_path(line, n, "/data/app/libfoo.so", nullptr)) continue;
            char * dash = strchr(line, \x27-\x27);
            unsigned long lo = strtoul(line, nullptr, 16);
            unsigned long hi = strtoul(dash + 1, nullptr, 16);
            if (syscall(__NR_madvise, (void *) lo, (size_t)(hi - lo), MADV_DONTNEED) == 0) madv_ok++;
        }
        fclose(mf);
    }
    (void) madv_ok;

""" + old
s = s.replace(old, bad, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ③ 拿掉 __GLIBC__ 门控：bionic 上照样编进 malloc_trim 调用（编不过）或退回假读数
stub "拿掉 __GLIBC__ 门控（假读数温床）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """    bool trimmed = false;
#if defined(__GLIBC__)
    trimmed = (malloc_trim(0) != 0);
#endif"""
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, """    bool trimmed = (malloc_trim(0) != 0);""", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ④ 删掉 RSS 自证（回到"只能装机读 /proc"的不可自证状态）
stub "删掉 RSS 自证（不可自证）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """    const long before_kb = proc_rss_kb();"""
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, """    const long before_kb = -1;""", 1)
s = s.replace("""    const long after_kb = proc_rss_kb();""", """    const long after_kb = -1;""", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑤ 把 no-op 写成"已归还"（恒真的假读数）
stub "把 no-op 写成『已归还』（恒真假读数）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "本 libc 无此接口（不还：匿名段 DONTNEED 会抹活对象，已移除）"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "已归还（本 libc 无此接口）", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑥ **过度修复**：顺手把 release_populated_pages 那条**安全**的 DONTNEED 也删了。
#    守卫必须能红 —— 否则"删掉真省内存的能力"会被当成绿灯。
stub "过度修复：连 release_populated_pages 的 DONTNEED 也删了" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        int rc = madvise((void *) lo, (size_t)(hi - lo), MADV_DONTNEED);"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "        int rc = 0; (void) lo; (void) hi; (void) MADV_DONTNEED;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦ 判据按路径认映射那一步被抽掉（改成按大小）—— 防"两条路串味"
stub "release_populated_pages 改成按大小挑映射（口径污染）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        bool is_deleted = false;\n        if (!maps_line_is_path(line, n, path, &is_deleted)) continue;"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "        bool is_deleted = false;\n        if (strstr(line, \"rw-p\") != line) continue;\n        (void) path;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑧ Kotlin 侧注释回到"对已释放的块没有副作用"那句错话
stub "Kotlin 注释回到『DONTNEED 对已释放块无副作用』" red '
import sys, io
p = sys.argv[2]; s = io.open(p, encoding="utf-8").read()
old = "     * 下一次分配又要重新缺页（代价是加载/首轮推理变慢）。空闲时调用是净收益。"
assert old in s, "Kotlin 形状变了，本自测要一起更新"
s = s.replace(old, "     * 而 `madvise(MADV_DONTNEED)` 对**已释放**的块没有副作用。", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑨ 反向：真源码（本轮修复后的写法）必须全绿
stub "修复后的写法（反向对照）" green '
import sys
'

if [ "$bad" -eq 0 ]; then
    echo "=== 归还空闲堆段守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 归还空闲堆段守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
