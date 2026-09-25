#!/bin/sh
# run_mmap_release_guard.sh 的自测：**每条判据都要能红**，且红在真的坏形态上。
#
# 为什么必须有这一条：守卫失效的形态是"全绿"，而全绿与"真的通过了"同形。
# 本模块已经在这一条上栽过**五次**，最近一次（本轮的上一版）代价最大：
#
#   上一版守卫钉的是「归还函数里 mmap 与 MADV_DONTNEED 同现」，
#   而实现是**自己另建一份映射再 DONTNEED** —— 它**把所有判据都满足了**、
#   自测也全绿，但用户那边 RSS 一页都没降（madvise 返回 0，只清了自己那份 VMA）。
#   也就是说：**旧自测里没有一根桩是"错的做法"**，它能红的只是"少了某一环"。
#   所以本轮补的第一根桩就是**把旧错法原样放回去**，它必须判红。
#
# 桩都取自真实历史形态或真机上真的会发生的退化，不是凭空构造：
#   · 旧错法（另建映射 + DONTNEED）：本轮之前的真实实现，**假绿元凶**；
#   · 只报"已归还"不报量：第一版日志就是这么骗过人的（恒真）；
#   · 只打开 maps 却不真 DONTNEED：字符串在、动作没有；
#   · 拿掉 useMmap 门控：无条件 DONTNEED，把 mmap=0 的直读档拖慢；
#   · 拿掉 RSS 可观测：回归到"只能装机读 /proc"不可自证的状态；
#   · 拿掉 FUSE 提示：用户继续在共享存储上纠结同一个问题。
#
# 运行：sh tools/run_mmap_release_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
chk() {
    name="$1"; want="$2"
    if sh tools/run_mmap_release_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=/tmp/mmap_release_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$JNI" "$STASH/llama_jni.cpp"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; }
trap restore EXIT

stub() {
    name="$1"; want="$2"; prog="$3"
    cp "$STASH/llama_jni.cpp" "$JNI"
    printf '%s\n' "$prog" | python3 - "$JNI"
    chk "$name" "$want"
}

# ① **本轮最重要的桩**：把旧错法原样放回去（自己另建映射 + DONTNEED）。
#    它曾经是全绿 —— 现在必须红。这是"判据比实现更宽"的直接反证。
stub "旧错法：自己另建映射 + DONTNEED（碰不到库那份，RSS 不降）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """    FILE * f = fopen("/proc/self/maps", "r");"""
assert old in s, "源码形状变了，本自测要一起更新"
# 把"读 maps + 对库映射 DONTNEED"整段换成旧的"自己另建 + DONTNEED"
new = """    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    off_t sz = lseek(fd, 0, SEEK_END);
    if (sz <= 0) { close(fd); return -1; }
    void * addr = mmap(nullptr, (size_t) sz, PROT_READ, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) { close(fd); return -1; }
    int rc = madvise(addr, (size_t) sz, MADV_DONTNEED);
    munmap(addr, (size_t) sz);
    close(fd);
    if (n_ranges) *n_ranges = 1;
    if (bytes)    *bytes    = (long) sz;
    return rc == 0 ? 0 : -1;
    FILE * f = fopen("/proc/self/maps", "r");"""
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ② 只 mmap 不 DONTNEED（映射建了、页没还）
stub "只 mmap 不 DONTNEED（页没还回内核）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        int rc = madvise((void *) lo, (size_t)(hi - lo), MADV_DONTNEED);"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "        int rc = 0; (void) lo; (void) hi; (void) MADV_DONTNEED;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ③ 只打开 maps、不真 DONTNEED（字符串在、动作没有 → 必须由"真的拿地址去 DONTNEED"逮住）
stub "只读 maps 不 DONTNEED（关键词在、动作没有）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """        int rc = madvise((void *) lo, (size_t)(hi - lo), MADV_DONTNEED);"""
assert old in s
s = s.replace(old, """        int rc = 0; (void) lo; (void) hi;   /* madvise 删掉：只在日志里提 MADV_DONTNEED */
        (void) MADV_DONTNEED;""", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ④ 日志只报"已归还"、不报实际归还量（第一版正是这样骗过人的：恒真）
stub "日志不报实际归还量（『已归还』恒真）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """                 n_ranges, n_bytes / (1024 * 1024),"""
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, """                 0, 0,""", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑤ 无条件归还（丢掉 useMmap 门控，拖慢 mmap=0 的直读档）
stub "无条件归还（丢了 useMmap 门控）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """    if (useMmap == JNI_TRUE) {
        int  n_ranges  = 0;"""
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, """    if (true) {
        int  n_ranges  = 0;""", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑥ 调用点挪到加载**之前**（对还没建立的映射做归还，等于白做）
stub "归还放在 load_from_file 之前（顺序写反）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
call = """    if (useMmap == JNI_TRUE) {
        int  n_ranges  = 0;"""
assert call in s
i = s.index(call)
j = s.index("    }\n", s.index("file_map_before_kb / 1024)", i)) + len("    }\n")
block = s[i:j]
s = s[:i] + s[j:]
anchor = "    long rss_before_kb = proc_rss_kb();"
s = s.replace(anchor, block + "\n" + anchor, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦ 删掉 RSS 日志（回到"只能装机读 /proc"的不可自证状态）
stub "删掉 RSS 日志（不可自证）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
i = s.index("已对库映射归还预填充页")
i = s.rindex("jlog(", 0, i)
j = s.index("未能归还预填充页")
j = s.rindex("jlog(", 0, j)
k = s.index("        } else {", j)
s = s[:i] + s[k:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑧ 拿掉 FUSE 共享存储提示
stub "删掉 FUSE 共享存储提示" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
i = s.index("    if (modelPath.find(\"/storage/emulated/\")")
j = s.index("    }\n", s.index("RSS 真落下来即共享。\"", i)) + len("    }\n")
s = s[:i] + s[j:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑨ 路径判据只留"行尾正好是 path"、丢掉 "(deleted)" —— 本轮真机事故的错法本身。
#    它曾经是全绿（旧判据就是这么写的），现在必须红。
stub "判据丢掉内核 ' (deleted)' 后缀（本轮事故的错法）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """    if (col) {
        size_t rl = strlen(col);
        if (rl > plen && strncmp(col, path, plen) == 0 &&
            strcmp(col + plen, \" (deleted)\") == 0) {
            if (is_deleted) *is_deleted = true;
            return true;
        }
    }"""
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "    (void) col;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑩ 让释放那侧**绕过**共享判据、自己手写一份（口径分叉 = 本轮事故的机理）
stub "释放侧绕过共享判据、自己手写后缀匹配（口径分叉）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = """        bool is_deleted = false;
        if (!maps_line_is_path(line, n, path, &is_deleted)) continue;"""
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, """        bool is_deleted = false;
        if (n < strlen(path) || strcmp(line + n - strlen(path), path) != 0) continue;""", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑪ 读驻留那侧不调共享判据（两处判据只改了一处 → 读数与动作脱节）
stub "读驻留侧不调共享判据（读数与动作脱节）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "            in_target = maps_line_is_path(line, n, path, nullptr);"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "            in_target = false;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑫ 丢掉"命中方式自证"（下次再出这类事故又要从头推）
stub "命中方式不自证（删掉 deleted 计数）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "                 n_deleted > 0 ? \"（含内核 (deleted) 后缀 %d 个）\" : \"\","
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "                 \"\",", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑬ 把"回收量"的被减数改回**加载前** baseline —— 本轮真机事故的错法本身：
#     `A - C` 恒为负（A 是空进程），于是日志打出「（省 -2827 MB）」，
#     与同一行的「驻留归零」互相打脸。它曾经是全绿的（旧判据只判"有没有差值"）。
stub "回收量拿加载前 baseline 当被减数（本轮事故的错法，恒为负）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "                 (rss_loaded_kb >= 0 && rss_after_kb >= 0) ? (rss_loaded_kb - rss_after_kb) / 1024 : -1,"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "                 (rss_before_kb >= 0 && rss_after_kb >= 0) ? (rss_before_kb - rss_after_kb) / 1024 : -1,", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑭ 两个端点取自同一时刻（释放前 == 释放后），回收量恒 0 —— 也是"报了个数但没量到"。
stub "回收量两端取自同一时刻（恒 0，等于没量）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        const long rss_loaded_kb      = proc_rss_kb();          // 时刻 B：加载后、释放前"
assert old in s, "源码形状变了，本自测要一起更新"
# 让 B 在释放**之后**才量 —— 两端同刻
s = s.replace(old, "        long rss_loaded_kb = 0;", 1)
old2 = "            long rss_after_kb = proc_rss_kb();                   // 时刻 C：释放后"
assert old2 in s, "源码形状变了，本自测要一起更新"
s = s.replace(old2, old2 + "\n            rss_loaded_kb = rss_after_kb;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑮ 文案丢掉时刻标注（读数回到"两个数摆一行、不标时刻"的同形状态）
stub "日志不标时刻（『释放前/释放后』文案没了）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "\"RSS 释放前 %ld MB → 释放后 %ld MB（本次释放回收 %ld MB，正数=真回收）｜ \""
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "\"RSS %ld MB → %ld MB（%ld MB）｜ \"", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑯ "回收"的算式符号写反（释放后 − 释放前）—— 数值一样、方向相反，必须判红
stub "回收算式符号写反（释放后减释放前）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "                 (rss_loaded_kb >= 0 && rss_after_kb >= 0) ? (rss_loaded_kb - rss_after_kb) / 1024 : -1,"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "                 (rss_loaded_kb >= 0 && rss_after_kb >= 0) ? (rss_after_kb - rss_loaded_kb) / 1024 : -1,", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑨ 反向：真源码（本轮修复后的写法）必须全绿
stub "修复后的写法（反向对照）" green '
import sys
'

if [ "$bad" -eq 0 ]; then
    echo "=== mmap 真省内存守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 真省内存守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
