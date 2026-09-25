#!/bin/sh
# 自测 `tools/run_http_lifecycle_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是三个自欺模式（本仓库各栽过一次，所以三个都钉）
# ═══════════════════════════════════════════════════════════════════════════
#   1. **恒真**：pattern 写成到处都有的文本，把功能删掉照样 PASS；
#   2. **恒假被静音**：pattern 与源码差一个字符 -> 守卫永远红 -> 有人把
#      `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过 listenerGen"，而不是
#      "清状态那两行被代际号包住" —— 后者才是 A-1 的本体。
# 做法：就地改真源码（每个桩只改一处锚点）-> 断言守卫**变红并点名** -> 还原。
# 桩全部来自**本轮真正的四处旧写法**，不是编出来的反例。
#
# 纯 sh + python3 标准库，不依赖工具链。运行：sh tools/run_http_lifecycle_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_http_lifecycle_guard.sh
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"
restore() { cp "$TMP/http.bak" "$HTTP"; rm -rf "$TMP"; }
trap restore EXIT
reset_src() { cp "$TMP/http.bak" "$HTTP"; }

# must_red "<桩名>" "<期望被点名的那条断言>"
must_red() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    if [ "$rc" = "0" ]; then
        echo "FAIL  $1：守卫没变红（该断言失效了）"; bad=$((bad+1)); return
    fi
    if grep -qF "$2" "$TMP/guard.out"; then
        echo "PASS  $1：守卫变红，且指到了「$2」"; ok=$((ok+1))
    else
        echo "FAIL  $1：守卫红了，但没指到「$2」"; bad=$((bad+1))
    fi
}

# ── 桩①：listener 的 finally 退回「无条件清」（A-1 的本体）────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                if (listenerGen.get() == myGen) {
                    running.set(false)
                    server = null
                } else {"""
new = """                if (true) {
                    running.set(false)
                    server = null
                } else {"""
assert s.count(old) == 1, "stub1: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩①（finally 又变成无条件清）" "listener 的 finally 先比代际再清共享状态"
reset_src

# ── 桩②：accept 循环不再查代际 ───────────────────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "                    if (listenerGen.get() != myGen) break\n"
assert s.count(old) == 1, "stub2: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩②（accept 循环不再查代际）" "accept 循环每轮都查代际"
reset_src

# ── 桩③：watchdog 闸门退回只看 running（A-2 的本体）──────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "                    if (!desired.get()) { failStreak = 0; continue }"
new = "                    if (!running.get()) { failStreak = 0; continue }"
assert s.count(old) == 1, "stub3: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩③（watchdog 只看 running）" "watchdog 闸门是 desired"
must_red "桩③（watchdog 只看 running）" "只看 running 的死闸门已绝迹"
reset_src

# ── 桩④：重启入口不再检查 desired（用户停服也会被拉起）────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "        if (!desired.get()) return false\n"
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩④（重启入口不查 desired）" "重启入口先查 desired"
reset_src

# ── 桩⑤：503 判据退回复合写法（A-3 的本体）──────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        if (!busy.compareAndSet(false, true)) {
            writeJson(out, 503, errJson("server busy（已有生成任务进行中）"), corsOrigin); return
        }
        if (LlmEngine.isGenerating) {
            busy.set(false)  // 这份占位是**我自己**刚抢到的，回滚安全
            writeJson(out, 503, errJson("server busy（已有生成任务进行中）"), corsOrigin); return
        }"""
new = """        if (!busy.compareAndSet(false, true) || LlmEngine.isGenerating) {
            busy.set(false)
            writeJson(out, 503, errJson("server busy（已有生成任务进行中）"), corsOrigin); return
        }"""
assert s.count(old) == 1, "stub5: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑤（503 判据退回复合写法）" "复合写法已绝迹"
must_red "桩⑤（503 判据退回复合写法）" "CAS 与「引擎被占用」被拆成两条 if"
reset_src

# ── 桩⑥：stop() 不再收敛在途连接（A-4 的本体）────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        for (c in activeConns.toList()) {
            try { c.close() } catch (_: Exception) {}
            if (activeConns.remove(c)) connCount.decrementAndGet()
        }\n"""
assert s.count(old) == 1, "stub6: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑥（stop 不再收敛在途连接）" "stop() 收敛在途连接"
reset_src

# ── 桩⑦：accept 后不再登记在途连接 ───────────────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "                    if (!admitConn(s)) {"
assert s.count(old) == 1, "stub7: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "                    if (false) {", 1))
PYEOF
must_red "桩⑦（不再判定连接上限）" "受理路径经 admitConn"
reset_src

# ── 桩⑧：stop() 不递增代际（在途 listener 的 finally 不会作废）────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "        listenerGen.incrementAndGet()\n"
assert s.count(old) == 1, "stub8: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑧（stop 不递增代际）" "stop() 递增代际"
reset_src

# ── 桩⑨：desired 与 running 合并成一个（语义坍缩）────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "    private val desired = AtomicBoolean(false)\n"
assert s.count(old) == 1, "stub9: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑨（desired 被删掉）" "desired（用户意图）独立于 running（实际状态）"
reset_src

# ── 桩⑩：迟到退出不留痕（症状指向错方向的那类）──────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '                    emitLog("listener gen=$myGen 迟到退出：已被 gen=${listenerGen.get()} 取代，不清共享状态")\n'
assert s.count(old) == 1, "stub10: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑩（迟到退出不留痕）" "迟到退出必须留痕"
reset_src


# ── 桩⑪（连接上限轮）：受理判据回到"受理后补救"（起线程之后才判上限）────────
# 这是本轮新增判据的本体：上限必须在**起线程之前**判定。
# 反例的形态很具体：把 admitConn 的判定挪到线程体内部 —— 那时连接
# 已经占了一个 http-conn 线程槽，上限就不再约束线程数（判据的全部意义）。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                    if (!admitConn(s)) {"""
new = """                    val admitted = admitConn(s)
                    if (!admitted) {"""
assert s.count(old) == 1, "stub11: anchor not found"
# 把"判据"搬到线程体里：先起线程，再在线程里看 admit 结果
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑪（受理判据挪到 val 之后/线程体里）" "受理判据在"
reset_src

# ── 桩⑫（连接上限轮）：admitConn 里 add 放到 CAS 之前（占坑与登记分家）──────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        s?.let { activeConns.add(it) }
        return true"""
new = """        return true
        @Suppress("UNREACHABLE_CODE") s?.let { activeConns.add(it) }"""
assert s.count(old) == 1, "stub12: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑫（add 挪到 CAS 之后之外）" "占坑成功后才登记"
reset_src

# ── 桩⑬（连接上限轮）：releaseConn 回到无条件减计数（stop 清表后会重复减）──
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        if (s != null && activeConns.remove(s)) connCount.decrementAndGet()"""
new = """        s?.let { activeConns.remove(it) }
        connCount.decrementAndGet()"""
assert s.count(old) == 1, "stub13: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑬（releaseConn 无条件减计数）" "以 remove 的返回值减计数"
reset_src

# ── 桩⑭（连接上限轮）：超限改成静默 close（客户端只看到 Empty reply）────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            writeRaw(out, 503, body, corsOrigin = null)"""
new = """            // 静默 close：客户端只看到连接被重置
            Unit"""
assert s.count(old) == 1, "stub14: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑭（超限静默 close）" "超限拒绝回可读 503"
reset_src

# ── 桩⑮（连接上限轮）：stop() 收敛时只关 socket 不减计数（计数泄漏）────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            if (activeConns.remove(c)) connCount.decrementAndGet()"""
new = """            activeConns.remove(c)"""
assert s.count(old) == 1, "stub15: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑮（stop 只关不减）" "stop() 收敛时同步减计数"
reset_src

# ── 桩⑯（连接上限轮）：上限常量被删（判据退化成散落魔数）──────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "    private const val MAX_CONNS = 64\n"
assert s.count(old) == 1, "stub16: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "    private const val MAX_CONNS_UNUSED = 64\n", 1))
PYEOF
must_red "桩⑯（上限常量被改名）" "上限是具名常量"
reset_src

# ── 桩⑰（连接上限轮）：超限拒绝里起了线程（拒绝路径自己成了新入口）────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        } finally {
            // 必须关：不关的话 fd 会随拒绝次数线性泄漏（拒绝率越高漏得越快），
            // 而"拒绝"恰恰是高压时的常态。
            try { s.close() } catch (_: Exception) {}"""
new = """        } finally {
            Thread({ try { s.close() } catch (_: Exception) {} }, "http-reject").start()"""
assert s.count(old) == 1, "stub17: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑰（拒绝路径起线程）" "不占 http-conn 线程槽"
reset_src


# ── 桩⑱（连接上限轮）：读超时被改成 0（满员后容量不会自愈）──────────────
# 本轮新增的两条"前提"判据必须证明非恒真：把 handleConn 的 soTimeout 改成 0，
# 守卫要变红。否则那两条只是"文件里有个数字"式的存在性断言。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "        s.soTimeout = 15_000  // 15s 内未收到完整请求即断开；生成期只写不读，不受影响"
new = "        s.soTimeout = 0  // 永不超时（反例：满员后容量不自愈）"
assert s.count(old) == 1, "stub18: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑱（读超时被改成 0）" "读超时不许被改成 0"
must_red "桩⑱（读超时被改成 0）" "有非零读超时"
reset_src

# ── 收尾：还原后守卫必须恢复全绿 ──────────────────────────────────────────
set +e
sh "$GUARD" > "$TMP/guard.out" 2>&1
rc=$?
set -e
if [ "$rc" = "0" ]; then echo "PASS  收尾：还原源码后守卫恢复全绿"; ok=$((ok+1));
else echo "FAIL  收尾：还原后守卫仍红"; grep '^FAIL' "$TMP/guard.out" || true; bad=$((bad+1)); fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 生命周期守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 生命周期守卫自测：PASS $ok / FAIL $bad ==="; exit 1
