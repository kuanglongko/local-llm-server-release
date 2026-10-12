#!/bin/sh
# 「GGUF 预读必须先过文件头检查」守卫的**双向自测**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一份（而单测替代不了它）
# ═══════════════════════════════════════════════════════════════════════════
# `run_gguf_precheck_tests.sh` 钉的是**判据本身**（gguf_header_check 该挡的挡、
# 该放的放）—— 它编的是 `gguf_precheck.h`，**看不见调用点**。
# `run_gguf_precheck_guard.sh` 钉的是**可达性**（nativeProbeGguf 里真的调了它、
# 调在解析之前、失败提前 return）。而这份守卫里有一条**负向断言**：
#
#   if (false && !gguf_header_precheck(...))   —— 绝不该算通过
#
# 负向断言有个共性风险：它在**当前树里** pattern 零命中，是**正确**的，
# 而那也意味着"没有任何东西能触发它" —— 判据写得再宽也照样绿。
# `tools/run_assertion_anchor_guard.py` 的判据②专门逮这件事：带负向断言的守卫
# 必须有一份自测往里**打桩**、让它变红。这一份就是那份自测（此前缺失，
# 于是判据②一直报"缺 1 个"）。
#
# 桩全部打在**真源码**上（改完 trap 还原），且用工作区独占锁 —— 与其他同套路
# 自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
#
# 运行：sh tools/run_gguf_precheck_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"

PY=${PYTHON:-python3}
GUARD=tools/run_gguf_precheck_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$KT" "$TMP/kt.bak"
restore() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP
reset_src() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"; }

run_guard() { sh "$GUARD" > "$TMP/guard.out" 2>&1; }

# must_red "<桩名>" "<期望被点名的那条断言>"
must_red() {
    set +e; run_guard; rc=$?; set -e
    if [ "$rc" = "0" ]; then
        echo "FAIL  $1：守卫没变红（该断言失效了）"; bad=$((bad+1)); return
    fi
    if grep -qF "$2" "$TMP/guard.out"; then
        echo "PASS  $1：守卫变红，且指到了「$2」"; ok=$((ok+1))
    else
        echo "FAIL  $1：守卫红了，但没指到「$2」"; ok=$((ok+0)); bad=$((bad+1))
        sed 's/^/      /' "$TMP/guard.out" | head -8
    fi
}

# must_green "<桩名>"
must_green() {
    set +e; run_guard; rc=$?; set -e
    if [ "$rc" = "0" ]; then echo "PASS  $1"; ok=$((ok+1));
    else echo "FAIL  $1（实得 red）"; bad=$((bad+1)); sed 's/^/      /' "$TMP/guard.out" | head -8; fi
}

# ── 0) 基线：未打桩时必须全绿 ─────────────────────────────────────────────
must_green "基线（未打桩）全绿"

# ── 桩①：调用点整块删掉（判据①"真的调了"必须逮住）────────────────────────
# 这是本守卫存在的全部理由：函数在、单测绿，而运行期根本没走这一支。
"$PY" - "$JNI" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        std::string why;
        if (!gguf_header_precheck(cpath, &why)) {"""
new = """        std::string why;
        if (false /* 桩①：判据根本没被调 */ ) {"""
assert s.count(old) == 1, "stub1: anchor not found"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩①（调用点被短路常量废掉）" "守卫调用没有被短路常量废掉"
reset_src

# ── 桩②：短路常量写回 `false && !gguf_header_precheck(...)` ─────────────────
# 那个名字**照样在文件里**，只查"字符串出现过"的判据会恒绿 —— 这正是负向
# 断言（`! grep -q 'false && !gguf_header_precheck'`）唯一能被打脸的方式。
"$PY" - "$JNI" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (!gguf_header_precheck(cpath, &why)) {"""
new = """        if (false && !gguf_header_precheck(cpath, &why)) {"""
assert s.count(old) == 1, "stub2: anchor not found"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩②（false && !precheck，名字还在但永不执行）" "守卫调用所在条件里没有 if (false"
reset_src

# ── 桩③：失败分支不 return，改成"打个日志继续解析" ───────────────────────
# 判据③钉的是"提前 return"。这一支坏了不会崩、不会报错 ——
# 它只是把"明显不能读的文件"照旧交给了那场 malloc 风暴。
"$PY" - "$JNI" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            LOGI("gguf probe: 前置检查未通过 -> %s", why.c_str());
            return mk("", "-1", "", msg.c_str());"""
new = """            LOGI("gguf probe: 前置检查未通过 -> %s", why.c_str());
            /* 桩③：不 return，继续往下解析 */"""
assert s.count(old) == 1, "stub3: anchor not found"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩③（失败分支不 return，继续解析）" "守卫失败时提前 return"
reset_src

# ── 桩④：先解析后挡（顺序反了）────────────────────────────────────────────
# 钉的是**行号**先后。反了之后守卫形同虚设：风暴已经打完了。
"$PY" - "$JNI" <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把 `return mk("", "-1", "", msg.c_str());` 之后插一句假的解析调用，
# 让它排在守卫**之前**（判据取的是第一处 `lm_gguf_init_from_file` 的行号）。
old = """    struct lm_gguf_init_params params;"""
new = """    struct lm_gguf_init_params params;
    /* 桩④：先解析、后挡 —— 顺序反了 */"""
assert s.count(old) == 1, "stub4: anchor not found"
s = s.replace(old, new, 1)
# 再在守卫的上方插一处同名调用，使"第一处 lm_gguf_init_from_file"落在守卫之前。
old2 = """    {
        std::string why;
        if (!gguf_header_precheck(cpath, &why)) {"""
new2 = """    (void) lm_gguf_init_from_file;   // 桩④：假解析点，排在守卫之前
    {
        std::string why;
        if (!gguf_header_precheck(cpath, &why)) {"""
assert s.count(old2) == 1, "stub4b: anchor not found"
s = s.replace(old2, new2, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩④（先解析、后挡，顺序反了）" "守卫出现在解析之前"
reset_src

# ── 桩⑤：Kotlin 侧读不出时静默回退（不出声）──────────────────────────────
# 判据④：预读失败必须留痕。静默回退与"压根没调预读"在客户端看来完全一样。
"$PY" - "$KT" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
key = '预读不可用'
# ⚠ 两处都要改：`probeGguf` 里它出现两次（catch 分支与"返回串读不出原因"那一支），
#   守卫取的是整个函数体，只改一处仍是绿的。
#   这也是本仓库反复记录的那族：桩**没打在洞上** -> 判绿 -> 假绿。
n = s.count(key)
assert n == 2, f"stub5: 期望 2 处，实得 {n}"
open(p, 'w', encoding='utf-8').write(s.replace(key, '预读暂不可用桩⑤'))
PYEOF
must_red "桩⑤（Kotlin 侧静默回退，不出声）" "probeGguf 在读不出时 uiLog"
reset_src

# ── 6) 收尾：全部还原后必须重新全绿 ───────────────────────────────────────
must_green "收尾：所有桩还原之后守卫重新全绿"

echo ""
if [ "$bad" = "0" ]; then echo "=== GGUF 预读守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== GGUF 预读守卫自测：PASS $ok / FAIL $bad ==="; exit 1
