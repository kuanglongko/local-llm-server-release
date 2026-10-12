#!/bin/sh
# 自测 `tools/run_assertion_anchor_guard.py`：拿**就地改坏的真守卫**跑它，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# 桩全是真会写出来的坏法：
#   ① 正向断言 pattern 写错一个字符（打错字/改了源码没改判据）-> 恒红后被人 `|| true` 掉
#   ② 带负向断言的守卫删掉自测 -> 那条"不出现"再没有东西能触发它
#   ③ 计数断言阈值被注释撑到 -> 判据在替一段说明文字站岗
#
# ═══════════════════════════════════════════════════════════════════════
# 为什么真源码**一个字节都不改**（v2，2026-10-02）
# ═══════════════════════════════════════════════════════════════════════
# v1 的机制是"就地 sed 改真源码 + trap 还原"。它踩过两次同样的坑（#126 那轮）：
#   · 被 `kill -9` / 容器回收 -> trap 不触发（SIGKILL 根本不可捕获）；
#   · `$TMP` 被清理 -> trap 里的 `cp "$TMP/x.bak"` 失败，**还原本身失败**，
#     桩原地留下（`set -e` 下 restore 半途死掉，后几个文件根本没机会还原）。
# 两条路径的后果相同：桩留在工作区，下一轮别人看到"守卫莫名变红"。
#
# 靠 trap 更严密是修不掉根因的 —— **SIGKILL 无法捕获**，而"还原"依赖一件
# 随时可能消失的东西（$TMP）。所以 v2 换判据：**真源码只读**。
# 全部打桩都发生在 `$WORK`（一份完整工作副本）里，guard 也从副本里跑
# （`run_assertion_anchor_guard.py` 的 ROOT 按脚本自身位置推导，副本自洽）。
# 于是"忘了还原"这件事**在物理上不可能发生**：没有东西需要还原。
# 强杀、`/tmp` 回收、`set -e` 中断 —— 全都只影响副本，真源码恒为基线。
set -e
cd "$(dirname "$0")/.."
ROOT=$(pwd)
PY=${PYTHON:-python3}
GUARD=tools/run_assertion_anchor_guard.py
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 建一份只能读、不能改真源码的工作副本 ────────────────────────────────
# 只拷 guard 真正会读的那几样（app/docs/scripts/tools + 根下四份账目），
# 不拷 .git（副本不需要版本库，也省几百 MB）。
# 注意：这里**不装 trap 清理**。副本留在 /tmp 无害（真源码没被动过），
# 而清理一旦失败（`/tmp` 被回收、rm 被 kill）又会变成一条"看起来该跑却没跑"的路径——
# 正是 v1 的死法。宁可泄漏几 MB 到 /tmp，也不要一条会静默失效的清理。
WORK=$(mktemp -d "${TMPDIR:-/tmp}/anchor_selftest.XXXXXX") || exit 2
for d in app docs scripts tools; do
    [ -d "$d" ] && cp -a "$d" "$WORK/$d"
done
for f in .cnb.yml README.md HTP-STATUS.md CHANGELOG.md; do
    [ -f "$f" ] && cp -a "$f" "$WORK/$f"
done
echo "工作副本：$WORK（真源码只读，桩全打在副本里）"

# 副本内的"基线"——桩之间靠它复位。它活在**副本自己**里，
# 不依赖 $WORK 之外任何东西：$WORK 若被回收，复位失败也无所谓（污染不了真源码）。
BASE="$WORK/.baseline"
mkdir -p "$BASE"
for f in tools/run_kv_cache_guard.sh tools/run_pure_logic_guard_tests.sh \
         tools/run_mmap_device_guard.sh app/src/main/cpp/llama_jni.cpp; do
    mkdir -p "$BASE/$(dirname "$f")"
    cp -a "$WORK/$f" "$BASE/$f"
done
reset_all() {
    for f in tools/run_kv_cache_guard.sh tools/run_pure_logic_guard_tests.sh \
             tools/run_mmap_device_guard.sh app/src/main/cpp/llama_jni.cpp; do
        cp -a "$BASE/$f" "$WORK/$f"
    done
}

# ── 真源码指纹：跑完自测要比对一次，证明"没被本测试动过"──────────────────
# v1 里没有这条 —— 于是"桩有没有残留"只能靠人 `git status` 看，而强杀之后
# 根本没人看。这里把基线 sha256 记下来，收工直接比对。
WATCH="tools/run_kv_cache_guard.sh tools/run_pure_logic_guard_tests.sh tools/run_mmap_device_guard.sh app/src/main/cpp/llama_jni.cpp"
repo_fingerprint() {
    for f in $WATCH; do
        [ -f "$f" ] && sha256sum "$f" | cut -d' ' -f1
    done | sha256sum | cut -d' ' -f1
}
BEFORE=$(repo_fingerprint)

run_guard() { set +e; ( cd "$WORK" && "$PY" "$GUARD" ) > "$WORK/out" 2>&1; rc=$?; set -e; return $rc; }
must_red() {
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$WORK/out"; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」" 0; fi
}

# ── 0. 基线 ────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了）" 0; sed -n '1,40p' "$WORK/out"; fi

# ── 1. 桩①：正向断言 pattern 打错一个字符（恒红后会被 `|| true` 抹掉）──────
"$PY" - "$WORK" <<'PY'
import sys, os
w = sys.argv[1]
p = os.path.join(w, "tools/run_kv_cache_guard.sh")
s = open(p, encoding="utf-8").read()
old = "grep -q 'fun resetKvCache' \\$SRC/LlmEngine.kt"
new = "grep -q 'fun resetKvCachX' \\$SRC/LlmEngine.kt"   # 打错一个字符
assert s.count(old) == 1, "桩①：没找到该正向断言"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
must_red "桩①（正向断言 pattern 打错 -> 恒红/恒静默）" "每条正向 grep 断言都能命中"
reset_all

# ── 2. 桩②：带负向断言的守卫**删掉自测引用**（那条"不出现"再没东西能触发）──
"$PY" - "$WORK" <<'PY'
import sys, os
w = sys.argv[1]
p = os.path.join(w, "tools/run_pure_logic_guard_tests.sh")
s = open(p, encoding="utf-8").read()
# 把对守卫本体的引用注释掉 -> 判据② 认作"没有双向自测"
old_ref = "run_pure_logic_guard.sh"
assert old_ref in s, "桩②：没找到对该守卫的引用"
# 全部替换成一个不指向它的名字
s = s.replace(old_ref, "run_pure_logic_guardXX.sh")
open(p, "w", encoding="utf-8").write(s)
PY
must_red "桩②（负向断言的守卫没有双向自测）" "带负向断言的守卫都有双向自测"
reset_all

# ── 3. 桩③：计数断言阈值被**注释**撑到（判据替说明文字站岗）───────────────
# 往 llama_jni.cpp 里加两行注释提到 rss_note_peak();，
# 同时把 assert 改成 -ge 3 —— 真实非注释命中只有 1，阈值虚高。
"$PY" - "$WORK" <<'PY'
import sys, os
w = sys.argv[1]
jni = os.path.join(w, "app/src/main/cpp/llama_jni.cpp")
s = open(jni, encoding="utf-8").read()
# 在文件顶部插注释行（含目标串）
s = "// rss_note_peak();\n// rss_note_peak();\n" + s
open(jni, "w", encoding="utf-8").write(s)
g = os.path.join(w, "tools/run_mmap_device_guard.sh")
t = open(g, encoding="utf-8").read()
old = "grep -c 'rss_note_peak();' \\$JNI) -ge 1 ]"
new = "grep -c 'rss_note_peak();' \\$JNI) -ge 3 ]"
assert t.count(old) == 1, "桩③：没找到计数断言"
open(g, "w", encoding="utf-8").write(t.replace(old, new, 1))
PY
must_red "桩③（计数阈值被注释撑到 -> 替说明文字站岗）" "计数类断言的阈值由非注释行满足"
reset_all

# ── 4. 全部还原后回绿 ──────────────────────────────────────────────────────
if run_guard; then ck "全部还原后：守卫回绿（桩没有残留污染）" 1
else ck "全部还原后：守卫回绿" 0; sed -n '1,40p' "$WORK/out"; fi

# ── 5. 收工自证：真源码指纹与开工时一致 ────────────────────────────────────
# 这条是 v2 的**根因判据**：v1 的"漏桩"在此处会直接变红 ——
# 因为 v1 的桩就打在真源码上，指纹必然变了。
AFTER=$(repo_fingerprint)
ck "真源码未被本测试改动（指纹 $BEFORE -> $AFTER）" "$([ "$BEFORE" = "$AFTER" ] && echo 1 || echo 0)"

echo
echo "=== 判据锚定守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
