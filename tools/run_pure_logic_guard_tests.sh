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
trap restore EXIT
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

# ── 桩①b：C-1 截断只做末条（"两条各 12k+12k"仍会突破上限）────────────────
"$PY" - $SS <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
start = s.index("        if (total() <= MAX_CHARS) return")
end = s.index("        Log.w(TAG,")
old = """        if (total() <= MAX_CHARS) return
        val last = msgs.last()
        val head = last.optString("content")
        if (head.length > MAX_CHARS) {
            last.put("content", head.substring(0, MAX_CHARS))
        }
"""
s2 = s[:start] + old + s[end:]
open(p, "w", encoding="utf-8").write(s2)
PYEOF
must_red "C-1：截断只做末条" "先按上限裁历史、再把剩余超限量逐条截断"
reset_src

# ── 桩②：C-1 裁剪不再留痕（症状变成"静默截断 = 答非所问"）──────────────────
"$PY" - $SS <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s2 = re.sub(r'\n *Log\.w\(TAG, "会话 \$id 超过 \$MAX_CHARS 字符[^\n]*\n', '\n', s, count=1)
assert s2 != s
open(p, "w", encoding="utf-8").write(s2)
PYEOF
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
