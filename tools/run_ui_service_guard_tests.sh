#!/bin/sh
# 自测 `tools/run_ui_service_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是三个自欺模式（本仓库各栽过一次，所以三个都钉）
# ═══════════════════════════════════════════════════════════════════════════
#   1. **恒真**：pattern 写成到处都有的文本，把功能删掉照样 PASS；
#   2. **恒假被静音**：pattern 与源码差一个字符 -> 守卫永远红 -> 有人把
#      `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过 HttpApi.stop()"，而不是
#      "stop() 的行号小于 unload() 的行号" —— 后者才是 I-1 的本体。
#      本轮我自己第一版就栽在这：锚点文本在 ⚠ 注释里也出现过，注释行号恰好
#      排在实现之前，于是一份「先卸载后停服」的源码被判成顺序正确（假 PASS）。
#      桩③就是为这条留的。
# 做法：就地改真源码（每个桩只改一处锚点）-> 断言守卫**变红并点名** -> 还原。
# 桩全部来自**本轮真正的旧写法**，不是编出来的反例。
#
# 纯 sh + python3 标准库，不依赖工具链。运行：sh tools/run_ui_service_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_ui_service_guard.sh
SVC=app/src/main/java/com/xiaowan/localinference/InferenceService.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$SVC" "$TMP/svc.bak"
restore() { cp "$TMP/svc.bak" "$SVC"; rm -rf "$TMP"; }
trap restore EXIT
reset_src() { cp "$TMP/svc.bak" "$SVC"; }

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

# ── 0) 基线：未打桩时必须全绿 ─────────────────────────────────────────────
set +e
sh "$GUARD" > "$TMP/base.out" 2>&1
base=$?
set -e
if [ "$base" = "0" ]; then echo "PASS  基线（未打桩）全绿"; ok=$((ok+1));
else echo "FAIL  基线（未打桩）就红了："; cat "$TMP/base.out"; bad=$((bad+1)); fi

# ── 桩①：I-1 本体 —— 把 HttpApi.stop() 挪回 unload() 之后 ───────────────
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                HttpApi.stop()
                if (LlmEngine.hasModel) {"""
new = """                if (LlmEngine.hasModel) {"""
assert s.count(old) == 1, "stub1: anchor A not found"
s = s.replace(old, new, 1)
old2 = """                HttpApi.currentModel = null
                // 保证下次「启动服务」必走完整加载流程：切换的 ctx/线程/GPU 参数生效、日志刷新。
"""
new2 = """                HttpApi.stop()
                HttpApi.currentModel = null
"""
assert s.count(old2) == 1, "stub1: anchor B not found"
s = s.replace(old2, new2, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩①（先卸载后停服，I-1 本体）" "HttpApi.stop() 先于 LlmEngine.unload()"
must_red "桩①（先卸载后停服，I-1 本体）" "「先卸载后停服」旧写法已绝迹"
reset_src

# ── 桩②：I-6 本体 —— ACTION_START 空路径分支把 HttpApi.stop() 去掉 ──────
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                    // 收尾与 ACTION_STOP 同一条纪律：先 HttpApi.stop() 再 stopSelf()。
                    // 只 stopSelf() 的话 watchdog 每 30s 探活、连续 2 次失败就重启 listener，
                    // 而这里的 desired 仍为 true —— 要靠 onDestroy() 兜（它是异步的，慢于 30s）。
                    HttpApi.stop()
"""
assert s.count(old) == 1, "stub2: anchor not found"
s = s.replace(old, "", 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩②（I-6：空路径分支不配对 stop()）" "每处 stopSelf() 在同一分支内都先配了 HttpApi.stop()"
reset_src

# ── 桩③：判据锚错位置的对照 —— 只把 stop() **写进注释**，实现挪回后面 ────
# 这个桩专门用来证明"排注释"的那两步是必要的：如果 firstline() 不排注释，
# 一份「注释里说 stop 先、实现是 unload 先」的源码会假绿。
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                // （`LlmEngine.unload()` 只持 `loadLock`，不持 `genLock`，也不看 `hasModel`）。
                HttpApi.stop()
                if (LlmEngine.hasModel) {"""
new = """                // HttpApi.stop() 先于 LlmEngine.unload() —— 注释里声明正确
                if (LlmEngine.hasModel) {"""
assert s.count(old) == 1, "stub3: anchor A not found"
s = s.replace(old, new, 1)
old2 = """                HttpApi.currentModel = null
                // 保证下次「启动服务」必走完整加载流程：切换的 ctx/线程/GPU 参数生效、日志刷新。
"""
new2 = """                HttpApi.stop()
                HttpApi.currentModel = null
"""
assert s.count(old2) == 1, "stub3: anchor B not found"
s = s.replace(old2, new2, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩③（注释里写 stop 先、实现是 unload 先）" "HttpApi.stop() 先于 LlmEngine.unload()"
reset_src

# ── 桩④：I-6 —— STICKY 找不到模型分支不配对 stop() ──────────────────────
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                    LlmEngine.uiLog("[服务] 系统重启服务但未找到已选模型，自动停止")
                    HttpApi.stop()
                    stopSelf()"""
new = """                    LlmEngine.uiLog("[服务] 系统重启服务但未找到已选模型，自动停止")
                    stopSelf()"""
assert s.count(old) == 1, "stub4: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩④（I-6：STICKY 分支不配对 stop()）" "每处 stopSelf() 在同一分支内都先配了 HttpApi.stop()"
reset_src

# ── 桩⑤：卸载闸门被删（hasModel 检查没了）─────────────────────────────────
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                if (LlmEngine.hasModel) {
                    try {
                        LlmEngine.unload()"""
new = """                if (true) {
                    try {
                        LlmEngine.unload()"""
assert s.count(old) == 1, "stub5: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑤（卸载前不再看 hasModel）" "卸载前仍以 hasModel 为闸门"
reset_src

# ── 桩⑥：onDestroy 不再收敛 HTTP ─────────────────────────────────────────
"$PY" - $SVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        HttpApi.stop()
        super.onDestroy()"""
new = """        super.onDestroy()"""
assert s.count(old) == 1, "stub6: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑥（onDestroy 不收敛 HTTP）" "onDestroy() 仍收敛 HTTP"
reset_src

# ── 桩⑦：UI 路径的"生成中不许卸载"被删（对照判据的一半）─────────────────
# 注意：这个桩改的是 EngineActivity，不是 InferenceService。自测要能覆盖两个文件。
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
cp "$ACT" "$TMP/act.bak"
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        if (generating || HttpApi.isGenerating) {
            statusTv.text = "生成进行中，请先停止生成再卸载模型\""""
new = """        if (false) {
            statusTv.text = "生成进行中，请先停止生成再卸载模型\""""
assert s.count(old) == 1, "stub7: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
set +e
sh "$GUARD" > "$TMP/g7.out" 2>&1; rc7=$?
set -e
if [ "$rc7" != "0" ] && grep -qF "UI 路径（EngineActivity.doUnload）仍拦" "$TMP/g7.out"; then
    echo "PASS  桩⑦（UI 路径不再拦生成中卸载）：守卫变红并点名"; ok=$((ok+1))
else
    echo "FAIL  桩⑦：守卫没抓到 UI 路径的退化"; bad=$((bad+1))
fi
cp "$TMP/act.bak" "$ACT"

# ── 收尾：还原后再跑一次基线，确认自测没污染源码 ─────────────────────────
set +e
sh "$GUARD" > "$TMP/final.out" 2>&1; fin=$?
set -e
if [ "$fin" = "0" ] && ! cmp -s "$TMP/svc.bak" "$SVC"; then
    echo "FAIL  自测污染了源码（InferenceService.kt 未还原干净）"; bad=$((bad+1))
elif [ "$fin" = "0" ] && ! cmp -s "$TMP/act.bak" "$ACT"; then
    echo "FAIL  自测污染了源码（EngineActivity.kt 未还原干净）"; bad=$((bad+1))
elif [ "$fin" = "0" ]; then
    echo "PASS  收尾（源码已还原，守卫仍全绿）"; ok=$((ok+1))
else
    echo "FAIL  收尾：源码还原后守卫仍红"; cat "$TMP/final.out"; bad=$((bad+1))
fi

echo ""
if [ "$bad" = "0" ]; then echo "=== UI/服务守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== UI/服务守卫自测：PASS $ok / FAIL $bad ==="; exit 1
