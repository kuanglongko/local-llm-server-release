#!/bin/sh
# 自测 `tools/run_monotonic_clock_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是三个自欺模式（同 `run_http_lifecycle_guard_tests.sh` 的口径）
# ═══════════════════════════════════════════════════════════════════════════
#   1. **恒真**：断言写成"文件里出现过 nanoTime"（SamplingParams 早就用了），
#      把某处退回墙钟照样 PASS；
#   2. **恒假被静音**：断言与源码差一个字符 -> 守卫永远红 -> 有人把它 `|| true`；
#   3. **锚错位置**：钉的是"出现过 nanoTime"，而不是"**这一对差**的两个端点
#      都在单调钟上" —— 后者才是 G1/G2/G3 的本体（混钟相减是另一种失效）。
# 做法：就地改真源码（每个桩只改一处锚点）-> 断言守卫**变红并点名** -> 还原。
# 桩全部来自本轮**真正的旧写法**（就是修之前那几行），不是编出来的反例。
#
# 纯 sh + python3 标准库，不依赖工具链。运行：sh tools/run_monotonic_clock_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_monotonic_clock_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
HTTP=$SRC/HttpApi.kt
ENG=$SRC/LlmEngine.kt
HC=$SRC/HealthCheck.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
SESS=$SRC/SessionStore.kt
cp "$ACT" "$TMP/act.bak"; cp "$HTTP" "$TMP/http.bak"
cp "$ENG" "$TMP/eng.bak"; cp "$HC" "$TMP/hc.bak"; cp "$SESS" "$TMP/sess.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/http.bak" "$HTTP"
            cp "$TMP/eng.bak" "$ENG"; cp "$TMP/hc.bak" "$HC"
            cp "$TMP/sess.bak" "$SESS"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_all() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/http.bak" "$HTTP"
              cp "$TMP/eng.bak" "$ENG"; cp "$TMP/hc.bak" "$HC"
              cp "$TMP/sess.bak" "$SESS"; }

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

# ── 桩①：G1 生产者退回墙钟（lastProgressAt 的写入）────────────────────────
# 本体：让一对差值的**写方**回到墙钟 —— 读方还是 nanoTime，这就是混钟。
"$PY" - $ENG <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "        lastProgressAt = System.nanoTime()\n        LogFileStore.flushNoise()"
new = "        lastProgressAt = System.currentTimeMillis()\n        LogFileStore.flushNoise()"
assert s.count(old) == 1, "stub1: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩①（lastProgressAt 写方退回墙钟）" "G1 lastProgressAt 的两处写入都在单调钟"
reset_all

# ── 桩②：G1 消费者退回墙钟（hangWatch 的 idle）────────────────────────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "            val idle = (System.nanoTime() - LlmEngine.lastProgressAt) / 1_000_000_000L"
new = "            val idle = (System.currentTimeMillis() - LlmEngine.lastProgressAt) / 1000L"
assert s.count(old) == 1, "stub2: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩②（hangWatch idle 退回墙钟）" "G1 卡死看门狗的 idle 用单调钟算"
must_red "桩②（hangWatch idle 退回墙钟）" "G1 idle 的墙钟旧写法已绝迹"
reset_all

# ── 桩③：G1 生成开始的基准退回墙钟（另一处写方）──────────────────────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "        LlmEngine.lastProgressAt = System.nanoTime()"
new = "        LlmEngine.lastProgressAt = System.currentTimeMillis()"
assert s.count(old) == 1, "stub3: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩③（生成开始基准退回墙钟）" "G1 生成开始时置基准也在单调钟"
reset_all

# ── 桩④：G2 心跳判据退回墙钟（阈值 800）──────────────────────────────────
# 只改**判据**、不改基准 —— 正是"混钟"的最小复现。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "                        if (stream && think.inThink && emitted > 0 && System.nanoTime() - lastBeat > 800_000_000L) {"
new = "                        if (stream && think.inThink && emitted > 0 && System.currentTimeMillis() - lastBeat > 800) {"
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩④（handleChat 心跳判据退回墙钟）" "G2 心跳节流用单调钟"
must_red "桩④（handleChat 心跳判据退回墙钟）" "G2 心跳的墙钟旧写法已绝迹"
reset_all

# ── 桩⑤：G2 心跳基准退回墙钟（handleCompletions 的声明）──────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "                    var lastBeat = System.nanoTime()  // G2：与 handleChat 同一口径"
new = "                    var lastBeat = System.currentTimeMillis()"
assert s.count(old) == 1, "stub5: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑤（handleCompletions 心跳基准退回墙钟）" "G2 心跳基准 lastBeat 的六处刷新"
reset_all

# ── 桩⑥：G3 tok/s 的 sec 退回墙钟 ─────────────────────────────────────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "                    val sec = (System.nanoTime() - t0) / 1_000_000_000f"
new = "                    val sec = (System.currentTimeMillis() - t0) / 1000f"
assert s.count(old) == 1, "stub6: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑥（tok/s 的 sec 退回墙钟）" "G3 tok/s 的 sec 用单调钟"
must_red "桩⑥（tok/s 的 sec 退回墙钟）" "G3 sec 的墙钟旧写法已绝迹"
reset_all

# ── 桩⑦：watchdog 睡眠实测退回墙钟 ───────────────────────────────────────
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "                    recordWatchdogSleep(plannedMs, (System.nanoTime() - t0) / 1_000_000L)"
new = "                    recordWatchdogSleep(plannedMs, System.currentTimeMillis() - t0)"
assert s.count(old) == 1, "stub7: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑦（watchdog 睡眠实测退回墙钟）" "watchdog 睡眠实测用单调钟"
must_red "桩⑦（watchdog 睡眠实测退回墙钟）" "watchdog 睡眠的墙钟旧写法已绝迹"
reset_all

# ── 桩⑧：HealthCheck 的 elapsedMs 退回墙钟 ───────────────────────────────
"$PY" - $HC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "        return parse(raw, (System.nanoTime() - t0) / 1_000_000L)"
new = "        return parse(raw, System.currentTimeMillis() - t0)"
assert s.count(old) == 1, "stub8: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑧（probe 的 elapsedMs 退回墙钟）" "probe 的四处 elapsedMs 都在单调钟"
must_red "桩⑧（probe 的 elapsedMs 退回墙钟）" "probe 的墙钟耗时旧写法已绝迹"
reset_all

# ── 桩⑨（反向）：对外时间戳被误改成 nanoTime ─────────────────────────────
# 这是"一刀切全改"的反向错误：nanoTime 起点任意，当时间戳毫无意义且随重启归零。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "            val created = System.currentTimeMillis() / 1000"
new = "            val created = System.nanoTime() / 1000"
n = s.count(old)
assert n == 2, f"stub9: 期望两处 created，实际 {n} 处"
open(p, "w", encoding="utf-8").write(s.replace(old, new))
PYEOF
must_red "桩⑨（对外 created 被误改成 nanoTime）" "HttpApi 的 id / created 仍用墙钟"
must_red "桩⑨（对外 created 被误改成 nanoTime）" "对外时间戳没被误改成 nanoTime"
reset_all

# ── 桩⑩（反向）：SessionStore 的持久化时间戳被误改成 nanoTime ─────────────
"$PY" - "$SRC/SessionStore.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '.put("updatedAt", System.currentTimeMillis())'
new = '.put("updatedAt", System.nanoTime())'
n = s.count(old)
assert n == 3, f"stub10: 期望三处 updatedAt，实际 {n} 处"
open(p, "w", encoding="utf-8").write(s.replace(old, new))
PYEOF
must_red "桩⑩（持久化 updatedAt 被误改成 nanoTime）" "SessionStore 的 updatedAt 仍用墙钟"
reset_all

# ── 收尾：还原后守卫必须恢复全绿 ──────────────────────────────────────────
set +e
sh "$GUARD" > "$TMP/guard.out" 2>&1
rc=$?
set -e
if [ "$rc" = "0" ]; then echo "PASS  收尾：还原源码后守卫恢复全绿"; ok=$((ok+1));
else echo "FAIL  收尾：还原后守卫仍红"; grep '^FAIL' "$TMP/guard.out" || true; bad=$((bad+1)); fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 单调钟守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 单调钟守卫自测：PASS $ok / FAIL $bad ==="; exit 1
