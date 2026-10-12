#!/bin/sh
# 自测 `tools/run_pure_logic_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# 这条自测防的是三个自欺模式（本仓库各栽过一次，所以三个都钉）：
#   1. **恒真**：pattern 写成到处都有的文本，把功能删掉照样 PASS；
#   2. **恒假被静音**：pattern 与源码差一个字符 -> 守卫永远红 -> 有人把
#      `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过 24000"，而不是"保险丝在只剩 2 条时仍生效"。
# 做法：就地改真源码（每个桩只改一处锚点）-> 断言守卫**变红并点名** -> 还原。
# 桩全部来自**本轮真正的四条旧写法**，不是编出来的反例。
#
# 纯 sh + python3 标准库，不依赖工具链。运行：sh tools/run_pure_logic_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_pure_logic_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
SS=$SRC/SessionStore.kt
TS=$SRC/ThinkStream.kt
SP=$SRC/SamplingParams.kt
TC=$SRC/ThinkingControl.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$SS" "$TMP/ss.bak"; cp "$TS" "$TMP/ts.bak"
cp "$SP" "$TMP/sp.bak"; cp "$TC" "$TMP/tc.bak"
restore() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/ts.bak" "$TS"; cp "$TMP/sp.bak" "$SP"; cp "$TMP/tc.bak" "$TC"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/ts.bak" "$TS"; cp "$TMP/sp.bak" "$SP"; cp "$TMP/tc.bak" "$TC"; }

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

# 守卫自身必须先在**干净源码**上全绿，否则下面的"变红"没有意义。
set +e
sh "$GUARD" > "$TMP/clean.out" 2>&1; rc=$?
set -e
ck "干净源码上守卫全绿（否则下面的变红无意义）" "$([ "$rc" = 0 ] && echo 1 || echo 0)"

# ── 桩①：C-1 保险丝退回「size > 2 当先决条件」的复合 while（旧写法本体）──────
"$PY" - $SS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        capChars(ctx, id, msgs)"""
new = """        while (msgs.sumOf { it.optString("content").length } > 24000 && msgs.size > 2) msgs.removeAt(0)"""
assert old in s
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "C-1：保险丝退回复合 while" "旧形状（size > 2 当先决条件的复合 while）已绝迹"
reset_src

# ── 桩①b：C-1 第二级只截一条（"两条各 12k+12k"仍会突破上限）────────────────
# ⚠ S1（`0.9.176`）起截断的落点是**历史**，所以这个桩改锚"把第二级换成对某一条的
#   无条件截断"：无论截谁，"逐条收窄直到落回上限"这条口径都没了。
# 桩文本放**独立 .py 文件**（见该文件顶部说明）：它的锚点里带 `for (i in ...) {`，
# 内联 heredoc 的引号层数会把它吃掉，实测切出空串、守卫照绿 —— 断言静默失效。
"$PY" tools/pure_logic/stub_cap_single.py $SS
must_red "C-1：第二级只截一条" "两级口径"
reset_src

# ── 桩①c（S1 新增）：截断退回"从最新一条往前截、保头"——啃掉本轮提问 ────────
# 这条是 S1 的**根因桩**：`msgs[i].put(...)` 的无条件下标写法回来了，于是末条
#（用户这一轮真正要说的话）第一个被截 —— 症状是"我贴的代码它只看到一半，
# 还答得头头是道"，与模型抽风完全同形。守卫必须变红，且指到"两级口径"那句。
"$PY" - $SS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            val cur = msgs[at].optString("content")  // 序号比 i 小的都还在 i 的左边，
            val cut = minOf(over, cur.length)        // 不会因裁剪而消失
            if (cut <= 0) continue
            msgs[at].put("content", cur.substring(0, cur.length - cut))"""
new = """            val cur = msgs[i].optString("content")
            val cut = minOf(over, cur.length)
            if (cut > 0) {
                msgs[i].put("content", cur.substring(0, cur.length - cut))
                over -= cut
            }"""
assert s.count(old) == 1, "桩①c：没找到新的截断循环"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "C-1（S1）：截断退回啃末条" "两级口径"
reset_src

# ── 桩②：C-1 裁剪不再留痕（症状变成"静默截断 = 答非所问"）──────────────────
# 删留痕同样走**独立 .py 文件**：正则要穿三层引号层，先前内联版本被吃掉后
# 桩"改了等于没改"，守卫照绿 —— 断言静默失效（见 stub_cap_nolog.py 顶部）。
"$PY" tools/pure_logic/stub_cap_nolog.py $SS
must_red "C-1：裁剪不留痕" "裁剪必须留痕"
reset_src

# ── 桩③：C-2 flush 退回「carry 原样无条件下发」（旧写法本体）──────────────
"$PY" - $TS <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            val keep = maxOf(partialTag(carry, OPEN), partialTag(carry, CLOSE))
            val body = carry.length - keep
            if (body > 0) emit(carry.substring(0, body), inThink)
            if (keep > 0) droppedPartialTag += keep"""
new = """            emit(carry.toString(), inThink)"""
assert old in s
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "C-2：flush 原样下发（旧写法本体）" "flush 不再把 carry 原样无条件下发"
reset_src

# ── 桩④：C-2 半标签丢弃量不再暴露（调用方无从留痕）────────────────────────
"$PY" - $TS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s2 = s.replace("    var droppedPartialTag: Int = 0\n        private set\n", "")
assert s2 != s
open(p, "w", encoding="utf-8").write(s2)
PYEOF
must_red "C-2：丢弃量不再暴露" "丢弃量作为可读状态暴露"
reset_src

# ── 桩⑤：C-3 seed 退回「字符串且非空」特判（旧写法本体）────────────────────
"$PY" - $SP <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
start = s.index("            val seed = when (val r = read(j, \"seed\")) {")
end = s.index("\n\n            return SamplingParams(", start)
old = """            val seed = readLongCompat(j) ?: run {
                if (j.has("seed") && !j.isNull("seed") &&
                    (j.opt("seed") as? String)?.trim()?.isNotEmpty() == true) return null to errSeed
                randomSeed()
            }"""
s2 = s[:start] + old + s[end:]
open(p, "w", encoding="utf-8").write(s2)
PYEOF
must_red "C-3：seed 退回特判判据（旧写法本体）" "seed 经由 read() 判 Absent/Bad/Ok"
reset_src

# ── 桩⑥：C-3 Bad 分支改成静默随机（"显式给了值却被当成没给"）──────────────
"$PY" - $SP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                is Read.Bad -> return null to errSeed"""
new = """                is Read.Bad -> randomSeed()"""
assert old in s
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "C-3：Bad 分支静默随机" "seed 的非法类型必须报 400"
reset_src

# ── 桩⑦：C-4 表态判据退回「只看 has」（has 对 null 返回 true）─────────────
"$PY" - $TC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            j.has("enable_thinking") && !j.isNull("enable_thinking") ->
                j.optBoolean("enable_thinking", true)"""
new = """            j.has("enable_thinking") -> j.optBoolean("enable_thinking", true)"""
assert old in s
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "C-4：null 判据退回只看 has（旧写法本体）" "enable_thinking 的表态判据排除 null"
reset_src

# ── 桩⑧：C-4 判据与 SamplingParams 分叉（把 isNull 判断删掉）──────────────
"$PY" - $SP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            if (!j.has(key) || j.isNull(key)) return Read.Absent"""
new = """            if (!j.has(key)) return Read.Absent"""
assert old in s
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "C-4：SamplingParams 的 null=Absent 判据被破坏" "与 SamplingParams 的 null=Absent 判据一致"
reset_src

# 还原后必须回到全绿 —— 否则"变红"可能只是源码被改坏了。
set +e
sh "$GUARD" > "$TMP/after.out" 2>&1; rc=$?
set -e
ck "还原后守卫回到全绿（证明变红来自桩本身）" "$([ "$rc" = 0 ] && echo 1 || echo 0)"

echo
echo "=== 纯逻辑守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
