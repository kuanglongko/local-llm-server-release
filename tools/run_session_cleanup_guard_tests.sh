#!/bin/sh
# 自测 `tools/run_session_cleanup_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"。守卫判据全是 grep/awk 的结构性断言，
# 有三个自欺模式（本仓库各栽过一次，所以三个都钉）：
#   1. **恒真**：pattern 写成到处都有的文本，把坏写法改回去照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过某函数"，而不是"**在这条路径里**
#      按这个关系做了这件事" —— 前者在定义处就恒真。
#
# 桩全部取自 **S4 真正会写错的写法**（不是编出来的）：
#   · 搜索渗进删除取数（用户在搜索状态下删掉的是"看起来只有三条"里的）
#   · 批量删**预勾选**候选（用户按下删除时以为在清垃圾）
#   · 删完**不作废 KV 账本**（模型会从别人的上下文接着往下写）
#   · olderThan 改读系统时钟（边界再也穷举不了，"跑到第二天结果变了"）
#   · 点击退回**下标换算**（筛过之后点第 3 行打开第 7 个会话）
#   · 批量删候选从**当前显示的行**取（不重取全库）
#   · 空集合也放行（一个都没勾就顺手清掉列出来的）
#   · 天数换算在各处重算（改档位时两处漂移）
#   · **靠 `dialogRef.getButton(...)` 回手同步删除按钮的可用性**（本轮的真 bug）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本 —— 与 run_session_quota_guard_tests.sh
# 同一套路。不需要工具链，只要 sh + python3。
# 运行：sh tools/run_session_cleanup_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_session_cleanup_guard.sh
SC=app/src/main/java/com/xiaowan/localinference/SessionCleanup.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
TST=tools/session_cleanup/SessionCleanupTest.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$SC" "$TMP/sc.bak"; cp "$ACT" "$TMP/act.bak"; cp "$TST" "$TMP/tst.bak"
restore() { cp "$TMP/sc.bak" "$SC"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tst.bak" "$TST"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/sc.bak" "$SC"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tst.bak" "$TST"; }

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

# ── 1. 桩①：搜索渗进删除取数（候选改从"当前显示的行"取）────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            candidates = SessionCleanup.olderThan(fresh, now, SessionCleanup.DAY_CHOICES[daysGroup.checkedRadioButtonId])"
new = "            candidates = SessionCleanup.olderThan(visibleRows, now, SessionCleanup.DAY_CHOICES[daysGroup.checkedRadioButtonId])"
assert s.count(old) == 1, "桩①：没找到候选取数那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩①（批量删候选不重取全库）" "批量删的候选是从**全库**取的"
reset_src

# ── 2. 桩②：批量删预勾选候选（候选一列出来就全勾上）────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val selected = linkedSetOf<String>()"
new = """        val selected = linkedSetOf<String>()
        // 桩：预勾选（用户按下删除时以为在清垃圾）
        fun preselect(list: List<SessionStore.Info>) { list.forEach { selected.add(it.id) } }"""
assert s.count(old) == 1, "桩②：没找到勾选集合"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩②（批量删预勾选候选）" "勾选态存在**集合**里"
reset_src

# ── 3. 桩③：删完不作废 KV 账本 ─────────────────────────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        LlmEngine.resetKvCache()
        if (removedCurrent) {"""
new = """        if (removedCurrent) {"""
assert s.count(old) == 1, "桩③：没找到 deleteSessions 里的 resetKvCache"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩③（删完不作废 KV 账本）" "删会话**要**作废 KV 账本"
reset_src

# ── 4. 桩④：olderThan 改读系统时钟（边界再也穷举不了）─────────────────────
"$PY" - "$SC" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val cutoff = now - days.toLong() * DAY_MS"
new = "        val cutoff = System.currentTimeMillis() - days.toLong() * DAY_MS"
assert s.count(old) == 1, "桩④：没找到 cutoff 那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩④（olderThan 改读系统时钟）" "不在内部读系统时钟"
reset_src

# ── 5. 桩⑤：点击退回下标换算（筛过之后点第 3 行开第 7 个）──────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            val picked = adapter.rows.getOrNull(pos) ?: return@setOnItemClickListener"
new = "            val picked = SessionStore.list(this).getOrNull(pos) ?: return@setOnItemClickListener"
assert s.count(old) == 1, "桩⑤：没找到点击监听"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑤（点击退回下标换算）" "不重新按全库下标取"
reset_src

# ── 6. 桩⑥：空集合也放行（一个都没勾就顺手清掉列出来的）────────────────────
"$PY" - "$SC" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        selected.isNotEmpty() && selected.any { existing.contains(it) }"
new = "        selected.any { existing.contains(it) } || existing.isNotEmpty()"
assert s.count(old) == 1, "桩⑥：没找到 canDelete 的判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑥（空集合也放行）" "空集合一律拒绝"
reset_src

# ── 7. 桩⑦：天数换算在各处重算（改档位时两处漂移）─────────────────────────
"$PY" - "$SC" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val cutoff = now - days.toLong() * DAY_MS"
new = "        val cutoff = now - days.toLong() * 24 * 60 * 60 * 1000"
assert s.count(old) == 1, "桩⑦：没找到 cutoff 那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑦（天数换算各处重算）" "用 DAY_MS"
reset_src

# ── 8. 桩⑨：删除按钮的可用性退回「靠对话框回手同步」────────────────────────
# 这是**本轮的真 bug**：框架 `AlertDialog.BUTTON_NEUTRAL` 是后来插入的常量，
# 而本仓库零第三方依赖、用的是 `android.app.AlertDialog` —— 运行时取不到那个按钮。
# 症状分岔：`btn?.isEnabled` 时静默失效（删除按钮永远亮着，"不误删"的闸门被漏过），
# `btn.isEnabled` 时直接 NPE **闪退**（用户点「批量清理」就崩）。两种都要红。
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            delBtn.isEnabled = SessionCleanup.canDelete(selected, candidates.map { it.id }.toSet())"
new = "            dialogRef.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.isEnabled = SessionCleanup.canDelete(selected, candidates.map { it.id }.toSet())"
assert s.count(old) == 1, "桩⑨：没找到同步删除按钮那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑨（删除按钮靠对话框回手同步）" "不再问对话框"
reset_src

# ── 9. 桩⑩：批量清理的删除按钮退回 `setPositiveButton`（同上，另一种写法）────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            .setView(box)\n            .setNegativeButton(\"取消\", null)"
new = "            .setView(box)\n            .setPositiveButton(\"删除\") { _, _ -> }\n            .setNegativeButton(\"取消\", null)"
assert s.count(old) == 1, "桩⑩：没找到批量清理对话框的壳"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑩（删除退回对话框按钮位）" "「删除」是视图里的自有按钮"
reset_src

# ── 10. 桩⑪：SessionCleanup 偷偷 import android（宿主行为测试编不出来）──────
"$PY" - "$SC" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "package com.xiaowan.localinference\n"
new = "package com.xiaowan.localinference\n\nimport android.util.Log\n"
assert s.count(old) == 1, "桩⑧：没找到包声明"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑧（纯逻辑文件偷偷 import android）" "不 import android"
reset_src

# ── 11. 桩⑫：切档位不回写「全选本档」（复选框挂着、新档位一条没勾）──────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            check.isChecked = candidates.isNotEmpty() && candidates.all { selected.contains(it.id) }\n"
assert s.count(old) == 1, "桩⑫：没找到回写复选框那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PY_EOF
must_red "桩⑫（切档不回写全选框）" "同步回写「全选本档」的勾选态"
reset_src

# ── 12. 桩⑬：搜索空态退回「每次按键弹 Toast」─────────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                emptyTv.text = "没有标题匹配「${kw}」的会话"
                emptyTv.visibility = if (adapter.rows.isEmpty() && kw.isNotBlank()) View.VISIBLE else View.GONE"""
new = """                if (adapter.rows.isEmpty()) {
                    Toast.makeText(this@EngineActivity, "没有标题匹配「${kw}」的会话", Toast.LENGTH_SHORT).show()
                }"""
assert s.count(old) == 1, "桩⑬：没找到空态那两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑬（无匹配退回 Toast）" "不得有 Toast"
reset_src

# ── 13. 桩⑭：删除会话后重画两次（回到本轮修掉的闪烁）────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                sessionDialog?.dismiss(); sessionDialog = null // 删除后关闭旧管理对话框，避免叠加
"""
new = """                sessionDialog?.dismiss(); sessionDialog = null // 删除后关闭旧管理对话框，避免叠加
                showSessionDialog() // 旧写法：if 之前先刷一次
"""
assert s.count(old) == 1, "桩⑭：没找到删除后的收场行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑭（删除后重画两次）" "showSessionDialog() 只有一处"
reset_src

# ── 14. 桩⑮：分隔线不跟着切页（切到日志页留一条划痕）──────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            chatBarDivider?.visibility = if (i == 0) View.VISIBLE else View.GONE\n"
assert s.count(old) == 1, "桩⑮：没找到分隔线同步那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PY_EOF
must_red "桩⑮（分隔线不随切页）" "与 chatBar 同步显隐"
reset_src

# ── 15. 桩⑯：打勾判据退回直接读 currentSessionId（用户报的那个 bug 本体）──
#
# 这是**回归桩**：它逐字还原用户报的那个现象之前的写法。
# ⚠ 桩必须动 `label` 的**判据**（换成 Activity 字段），只把 `selected` 删掉
#    会让守卫的另一条（自持 selected）变红 —— 那条也能红，但红的不是"对号
#    不跟随"这件事。桩要能指到**对号**那一条，才证明它是这条缺陷的判据。
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            if (i.id == selectedId) append(\"✓ \")"
new = "            if (i.id == currentSessionId) append(\"✓ \")"
assert s.count(old) == 1, "桩⑯：没找到 label 里的打勾判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑯（打勾判据退回读 currentSessionId）" "打勾的判据是入参 selectedId"
reset_src

# ── 16. 桩⑰：点了不重画（改了状态不刷新，要等滚动才看见）──────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            adapter.selected = picked.id
            adapter.notifyDataSetChanged()
            showSessionDialogDismiss()"""
new = """            adapter.selected = picked.id
            showSessionDialogDismiss()"""
assert s.count(old) == 1, "桩⑰：没找到点击后的重画"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑰（点了不重画）" "点击之后**重画这一屏**"
reset_src

# ── 17. 桩⑱：切完留在对话框里（库里可能已经被改过，标题还是旧的）──────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            adapter.selected = picked.id
            adapter.notifyDataSetChanged()
            showSessionDialogDismiss()"""
new = """            adapter.selected = picked.id
            adapter.notifyDataSetChanged()"""
assert s.count(old) == 1, "桩⑱：没找到切完的收场"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑱（切完留在原层）" "切成功之后关掉这一层"
reset_src

# ── 18. 桩⑲：切换失败也先把对号挪走（画出"已经切过去了"而其实没有）────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (!switchSession(picked.id)) return@setOnItemClickListener
            adapter.selected = picked.id"""
new = """            adapter.selected = picked.id
            switchSession(picked.id)"""
assert s.count(old) == 1, "桩⑲：没找到 switchSession 的返回值判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑲（切失败也先挪对号）" "切失败就直接返回"
reset_src

echo
echo "=== 会话列表清理守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
