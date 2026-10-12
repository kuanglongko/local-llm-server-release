#!/bin/sh
# 自测 `tools/run_session_cap_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 grep/awk 形式的存在性与结构性断言，它有三个自欺模式
#（本仓库各栽过一次，所以三个都钉）：
#   1. **恒真**：pattern 写成到处都有的文本，于是把坏写法改回去照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快就有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过某函数"，而不是"**在这条路径里**
#      按这个顺序做了这件事" —— 前者在函数定义处就恒真。
#
# 桩全部取自 **S1 真正会写错的写法**（不是编出来的）：
#   · 退回 `msgs.size > 40` 裸魔数      · 截断方向改回"保头啃末条"
#   · 空条靠 minOf 顺手越过（静默少截）· 挪位记账写在 cut 判断之前
#   · 常量不具名（MAX_MSGS 消失）       · 留痕退回一句"已裁剪"
#   · 复刻测试与实现脱钩（复刻改回旧循环）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_chat_regen_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_session_cap_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_session_cap_guard.sh
SS=app/src/main/java/com/xiaowan/localinference/SessionStore.kt
TST=tools/session_cap/SessionCapTest.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$SS" "$TMP/ss.bak"; cp "$TST" "$TMP/tst.bak"
restore() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/tst.bak" "$TST"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/tst.bak" "$TST"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：条数保险丝退回 `msgs.size > 40` 裸魔数（改 40 时没人知道它代表轮数）──
#   本轮形态变了：保险丝从 append 内联收进了共享的 trimToCap，所以桩改在这里。
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "while (msgs.size > MAX_MSGS) msgs.removeAt(0)"
new = "while (msgs.size > 40) msgs.removeAt(0)"
assert s.count(old) == 1, "桩①：没找到 trimToCap 的条数保险丝"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（条数退回裸魔数 40）" "条数保险丝落在 trimToCap 里（具名常量 MAX_MSGS，不是裸 40）"
reset_src

# ── 1b. 桩①b：导入这条路**绕过**保险丝（B1 的坏法：writeImported 不过 trimToCap）──
#   这一根专钉本轮修的 B1 —— 它是"保险丝在、但有一条路不过它"的形态，
#   而"trimToCap 存在"对它恒真，只有"两个入口都过它"判得出。
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val msgs = s.messages.map { JSONObject().put("role", it.role).put("content", it.content) }.toMutableList()
        trimToCap(ctx, id, msgs)"""
new = """        val msgs = s.messages.map { JSONObject().put("role", it.role).put("content", it.content) }.toMutableList()"""
assert s.count(old) == 1, "桩①b：没找到 writeImported 的 trimToCap 调用"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①b（导入绕过保险丝，不裁剪直接落盘）" "导入这条入口也过 trimToCap（落盘前裁剪，不绕过保险丝）"
reset_src

# ── 2. 桩②：常量不具名（MAX_MSGS 消失，直接内联）────────────────────────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    const val MAX_MSGS = 40\n"
assert s.count(old) == 1, "桩②：没找到 MAX_MSGS 常量"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩②（常量不具名）" "条数上限是具名常量 MAX_MSGS = 40"
reset_src

# ── 3. 桩③：截断方向改回"从最新一条起、保头" —— 正是啃掉本轮提问的写法 ──────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val last = msgs.lastIndex
        var over = total() - MAX_CHARS
        var cutMsgs = 0
        var cutChars = 0
        val removals = IntArray(msgs.size)
        for (i in last - 1 downTo 0) {
            if (over <= 0) break
            val at = i - removals[i]                 // i 条被往左挪过几格
            if (at !in 0 until last) continue        // 已被丢掉的 i 条：跳过
            val cur = msgs[at].optString("content")  // 序号比 i 小的都还在 i 的左边，
            val cut = minOf(over, cur.length)        // 不会因裁剪而消失
            if (cut <= 0) continue
            msgs[at].put("content", cur.substring(0, cur.length - cut))
            removals[at]++
            cutMsgs++
            cutChars += cut
            over -= cut
        }"""
new = """        var over = total() - MAX_CHARS
        var cutMsgs = 0
        var cutChars = 0
        for (i in msgs.indices.reversed()) {
            if (over <= 0) break
            val cur = msgs[i].optString("content")
            val cut = minOf(over, cur.length)
            if (cut > 0) {
                msgs[i].put("content", cur.substring(0, cur.length - cut))
                cutMsgs++
                cutChars += cut
                over -= cut
            }
        }"""
assert s.count(old) == 1, "桩③：没找到截断循环"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（截断方向改回保头啃末条）" "截断循环**不含末条**"
reset_src

# ── 4. 桩④：空条靠 minOf 顺手越过（静默少截，超限留给下一轮）──────────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            if (cut <= 0) continue\n"
assert s.count(old) == 1, "桩④：没找到空条 continue"
open(p, 'w', encoding='utf-8').write(s.replace(old, "            if (cut > 0) {\n", 1))
EOF
must_red "桩④（空条不 continue）" "空历史条（cut <= 0）显式 continue"
reset_src

# ── 5. 桩⑤：整段收窄成"先记账、再决定要不要截"（空条也记一笔，下标全偏）────
# 判据是**行序**：`removals[at]++` 必须在 `if (cut <= 0) continue` **之后**。
# 只 grep 两个片段都存在是恒真的（挪到 continue 之前它照样都在）。
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            val cut = minOf(over, cur.length)        // 不会因裁剪而消失
            if (cut <= 0) continue
            msgs[at].put("content", cur.substring(0, cur.length - cut))"""
new = """            val cut = minOf(over, cur.length)        // 不会因裁剪而消失
            removals[at]++
            if (cut <= 0) continue
            msgs[at].put("content", cur.substring(0, cur.length - cut))"""
assert s.count(old) == 1, "桩⑤：没找到 cut 判断那几行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（挪位记账放在 cut 判断之前）" "条目挪位的记账只在 cut 真的发生后累加"
reset_src

# ── 6. 桩⑥：留痕退回一句"已裁剪"（用户分不清是谁被截了）───────────────────
"$PY" - "$SS" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        Log.w(TAG, "会话 $id 超过 $MAX_CHARS 字符（$before -> ${total()}）：丢弃最早 $droppedMsgs 条历史，" +
            "截断 $cutMsgs 条历史共 $cutChars 字符；末条一字未动" +
            if (over > 0) "，末条自身仍超限（超出 $over 字符，已整体保留）" else "")"""
new = """        Log.w(TAG, "会话 $id 超过 $MAX_CHARS 字符（$before -> ${total()}），已裁剪")"""
assert s.count(old) == 1, "桩⑥：没找到留痕那几行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（留痕退回\"已裁剪\"）" "留痕里明说末条一字未动"
reset_src

# ── 7. 桩⑦：复刻测试与实现脱钩（复刻改回旧的"从最新往前截"）──────────────
"$PY" - "$TST" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    for (i in last - 1 downTo 0) {"
assert s.count(old) == 1, "桩⑦：没找到复刻里的截断循环"
open(p, 'w', encoding='utf-8').write(s.replace(old, "    for (i in msgs.lastIndex downTo 0) {", 1))
EOF
must_red "桩⑦（复刻测试改回旧循环，与实现脱钩）" "行为测试复刻的截断循环与实现同形"
reset_src

# ── 8. 桩⑧：把"末条逐字节不变"换成一条**看着像在钉、其实恒真**的断言 ───────
# 这正是本仓库最爱的失败形态：断言还在、名字还在，只是判据退化成"末条非空"。
"$PY" - "$TST" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'now.last().second == long'
assert s.count(old) == 1, "桩⑧：没找到末条不变量那条断言"
open(p, 'w', encoding='utf-8').write(s.replace(old, "now.last().second.isNotEmpty()", 1))
EOF
must_red "桩⑧（末条不变量退化成"非空"）" "行为测试钉住「末条逐字节不变」这条核心不变量"
reset_src

# ── 8b. 桩⑨：maybeAutoTitle 退回裸 `take(20)`（标题切在代理对中间）─────────
# 这是本轮修的 A4：裸 take(20) 会切出孤立高代理，落盘/渲染时被替换成 `?`。
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val clipped = if (short.length > 20) TextBoundary.clipAtCharBoundary(short, 20) + "…" else short'
new = 'val clipped = if (short.length > 20) short.take(20) + "…" else short'
assert s.count(old) == 1, "桩⑨：没找到 maybeAutoTitle 的标题截断"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（标题截断退回裸 take(20)）" "maybeAutoTitle 的标题截断走 clipAtCharBoundary"
reset_src

# ── 9. 还原确认：桩跑完必须回到全绿（否则 restore 漏了，桩会留在工作区）───
if run_guard; then ck "收尾：全部还原后守卫仍全绿" 1
else ck "收尾：全部还原后守卫仍全绿（有桩没还原）" 0; fi
# 判据是"与备份逐字节相同"，不用 `git diff --quiet`：开发机上改这两个文件本来就是
# 本任务的正常状态（这个脚本是在 S1 的改动之上跑的），git 一比对就恒红 ——
# 恒红的收尾判据等于没有收尾判据。备份才是"跑之前的样子"。
if cmp -s "$TMP/ss.bak" "$SS" && cmp -s "$TMP/tst.bak" "$TST"; then
    ck "收尾：源码与测试文件逐字节回到跑之前的样子" 1
else
    ck "收尾：源码与测试文件逐字节回到跑之前的样子" 0
fi

echo
echo "=== 会话裁剪守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
