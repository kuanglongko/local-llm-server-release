#!/bin/sh
# 自测 `tools/run_chat_regen_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 grep/awk 形式的**存在性与结构性**断言，它有三个自欺模式
#（本仓库各栽过一次，所以三个都钉）：
#
#   1. **恒真**：pattern 写成到处都有的文本（例如只 grep `resetKvCache`，
#      而它在设置页、删除路径、清空路径**本来就都在**），于是把重跑路径的作废
#      删掉照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快就有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过某函数"，而不是"**在这条路径里**
#      按这个顺序做了这件事" —— 前者在函数定义处就恒真。
#
# 桩全部来自 ③ 真正会写错的写法（不是编出来的），每一条都对应一个具体的坏法：
#   · 顺序反了 → "参数非法时旧答案凭空消失"
#   · 边界取末条 → 长按 user 时把它自己也丢了，问话没了
#   · 作废搬进 startGeneration → 每条起点都无条件多丢一次缓存（零症状）
#   · 停止不落盘 → 退回"半截只活在页面上"的半成品，历史里出现两种同形语义
#   · 又冒出 partial / streamingAppended → 自创的"接着往下写"那套语义回来了
#   · 菜单给了非末条 → 点了没反应/答非所问
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_chat_bubble_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_chat_regen_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_chat_regen_guard.sh
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
SS=app/src/main/java/com/xiaowan/localinference/SessionStore.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$SS" "$TMP/ss.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/ss.bak" "$SS"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/ss.bak" "$SS"; }

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

# ── 1. 桩①：先建占位气泡、再置 generating（顺序反了 = "位置还没出现就开始跑"）──
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        renderChat("")
        val maxTok = sp.maxTokens
        generating = true; genBtn.isEnabled = false"""
new = """        generating = true; genBtn.isEnabled = false
        val maxTok = sp.maxTokens
        renderChat("")"""
assert s.count(old) == 1, "桩①：没找到 startGeneration 的次序那两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（先置 generating 再建占位气泡）" "先建占位气泡、再置 generating"
reset_src

# ── 1b. 桩①b：让 startGeneration 自己也去截断历史（顺序约束再也说不清）──────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val sp = currentSamplingParams() ?: return"""
new = """        val sp = currentSamplingParams() ?: return
        SessionStore.truncateTo(this, currentSessionId, 0)"""
assert s.count(old) == 1, "桩①b：没找到 startGeneration 的开头"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①b（startGeneration 内部也截断历史）" "startGeneration 内部不碰历史"
reset_src

# ── 2. 桩②：重跑边界退化成"末条"（长按 user 时把问话自己也丢了）──────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val target = SessionStore.load(this, currentSessionId).getOrNull(index) ?: return"""
new = """        val all = SessionStore.load(this, currentSessionId)
        val index = all.size - 1
        val target = all.getOrNull(index) ?: return"""
assert s.count(old) == 1, "桩②：没找到重跑边界那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（边界退化成末条）" "重跑边界取自**用户长按的那一条**"
reset_src

# ── 3. 桩③：把作废搬进 startGeneration（"接着往下写"白白多丢一次缓存）────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val sp = currentSamplingParams() ?: return
        // 这一句多了一条"""
new = """        val sp = currentSamplingParams() ?: return
        LlmEngine.resetKvCache()
        // 这一句多了一条"""
assert s.count(old) == 1, "桩③：没找到 startGeneration 的开头"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（作废搬进 startGeneration）" "作废点也不在 startGeneration 里"
reset_src

# ── 4. 桩④：停止路径不落盘（退回"半截只活在页面上"的旧行为）──────────────
"$PY" - "$ACT" <<'STUBEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
q = chr(34)
old = """                        if (sb.isNotBlank()) {
                            SessionStore.appendAssistant(this@EngineActivity, currentSessionId, sb.toString())
                            renderChat(null)
                            statusTv.text = """ + q + """已停止（已保存，可长按这条回答 → 重新生成）""" + q + """
                        } else statusTv.text = """ + q + """已中止""" + q
new = """                        if (sb.isNotBlank()) {
                            statusTv.text = """ + q + """已停止（可长按这条回答 → 重新生成）""" + q + """
                        } else statusTv.text = """ + q + """已中止""" + q
assert s.count(old) == 1, "桩④：没找到停止落盘那一段"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
STUBEOF
must_red "桩④（停止路径不落盘）" "「停止」后的半截回答照样落盘"
reset_src

# ── 5. 桩⑤：又冒出 streamingAppended 标志位（把"半成品"钉回存储之外）──────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    private var streamingBody: TextView? = null"""
new = """    private var streamingBody: TextView? = null
    private var streamingAppended = true"""
assert s.count(old) == 1, "桩⑤：没找到 streamingBody 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（又冒出 streamingAppended 标志位）" "不再有 streamingAppended"
reset_src

# ── 5b. 桩⑤b：重跑路径又冒回 partial 半截分支（自创"接着往下写"语义回来）──
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val truncated = SessionStore.truncateTo(this, currentSessionId, index)"""
new = """        val partial = target.role == "assistant" && false
        val truncated = SessionStore.truncateTo(this, currentSessionId, index)"""
assert s.count(old) == 1, "桩⑤b：没找到 truncateTo 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤b（重跑路径又冒出 partial 半截分支）" "不再有 partial 半截分支"
reset_src

# ── 6. 桩⑥：长按 user 时不补回那条问话（重跑 = 让模型自己续写 system）────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (target.role == "user" && SessionStore.load(this, currentSessionId).size == index) {
            SessionStore.appendUser(this, currentSessionId, target.content)
        }"""
new = ""
assert s.count(old) == 1, "桩⑥：没找到补回 user 那一段"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（长按 user 时不补回问话）" "从存储补回这条提问"
reset_src

# ── 7. 桩⑦：菜单里对所有条都给"重新生成"（点了答非所问）──────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val regen = if (!isLast) null else "重新生成这一条\""""
new = """        val regen = "重新生成这一条\""""
assert s.count(old) == 1, "桩⑦：没找到 regen 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（菜单不给末条限制）" "只在长按**末条**时挂上"
reset_src

# ── 7b. 桩⑦b：菜单文案又长出 接着往下写（自创语义回来了）──────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val regen = if (!isLast) null else "重新生成这一条\""""
new = """        val regen = if (!isLast) null else "接着往下写\""""
assert s.count(old) == 1, "桩⑦b：没找到 regen 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦b（菜单文案又长出 接着往下写）" "不再有 接着往下写 那支"
reset_src

# ── 7c. 桩⑦c：中位按钮改回不存在的 Builder.setButton（编 APK 才暴露）──────
"$PY" - "$ACT" <<'STUBEOF2'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (regen != null) builder.setNeutralButton(regen) { _, _ -> regenerateLast(index) }"
new = "        if (regen != null) builder.setButton(6, regen) { _, _ -> regenerateLast(index) }"
assert s.count(old) == 1, "桩⑦c：没找到 setNeutralButton 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
STUBEOF2
must_red "桩⑦c（中位按钮改回不存在的 Builder.setButton）" "Builder.setNeutralButton"
reset_src

# ── 8. 桩⑧：截断路径夹带 capChars 裁剪（顺手又裁一刀）────────────────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        return writeMessages(ctx, id, msgs.subList(0, keep).toMutableList())"
new = """        val kept = msgs.subList(0, keep).toMutableList()
        capChars(ctx, id, kept)
        return writeMessages(ctx, id, kept)"""
assert s.count(old) == 1, "桩⑧：没找到 truncateTo 的写回那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（截断路径夹带裁剪）" "截断不夹带裁剪"
reset_src

# ── 9. 桩⑨：重跑路径**又**去全清 KV（= 上一版那个 bug：复用直接归零）──────
# 这一桩是本轮修的那个真 bug 的复现件：在重跑点调 `resetKvCache()`（全清），
# 下一轮 plan_reuse 因 kv_valid==false 从 token 0 全量重算。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (!truncated && target.role == "assistant") return   // 下标已失效：原地不动
        // 这里**不碰 KV**"""
new = """        if (!truncated && target.role == "assistant") return   // 下标已失效：原地不动
        LlmEngine.resetKvCache()
        // 这里**不碰 KV**"""
assert s.count(old) == 1, "桩⑨：没找到重跑路径收尾那一段"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（重跑路径又去全清 KV：复用归零）" "重跑路径**不**作废账本"
reset_src

# ── 10. 收尾：还原后必须恢复全绿 ────────────────────────────────────────────
if run_guard; then ck "收尾：还原源码后守卫恢复全绿" 1
else ck "收尾：还原源码后守卫恢复全绿（实际红了 -> 有桩没还原干净）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 聊天页重新生成守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 聊天页重新生成守卫自测：PASS $ok / FAIL $bad ==="; exit 1
