#!/bin/sh
# 自测 `tools/rag_list_first/check_wiring.py`：把它对着几份**打桩源码**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是**存在性、结构性**断言（谁在谁之前 / 在哪个函数体内），
# 两个自欺模式这一族最容易踩：
#
#   1. **锚错位置**：钉"文件里出现过 refreshRagRows"而不是"它发在后台读**之前**"
#      —— 前者在别处（追加/导入/保存）也成立。所以判据一律锚函数体内 + 相对次序。
#   2. **恒假后被静音**：pattern 与源码差一个字符 -> 永远 FAIL，
#      很快就会有人把 `exit 1` 改成 `|| true`。
#
# 桩全部来自"真正会写错的写法"（这一族真正的失败方向）：
#   · 列表刷新又挪回正文回填**之后**（用户报的那段空白原样回来）
#   · 列表刷新发在归属**之前**（它会被自己的归属判据丢掉，列表要等下一次）
#   · 删除成功后照旧整份重读（6 MB 又读一遍）
#   · 本地推算**不验**"下标即行号"（按一份来路不明的表推行号 -> 删错隔壁）
#   · 本地推算**保留旧行号**（不按新位置重建 -> 后面每一行都指错一条）
#   · 本地推算去碰磁盘（那它就没有存在的理由）
#   · 摘要行退回默认断行策略（BALANCED + 连字符）
#   · 列表改用压紧的 readEntries（删除会删错隔壁那一行）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_list_first_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"

PY=${PYTHON:-python3}
GUARD=tools/rag_list_first/check_wiring.py
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
CAP=$SRC/RagUiCap.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$CAP" "$TMP/cap.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/cap.bak" "$CAP"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/cap.bak" "$CAP"; }

run_guard() { set +e; "$PY" "$GUARD" "$ACT" "$CAP" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

stub_py() { "$PY" - "$@" || echo "⚠ 打桩脚本失败（锚点漂了？）—— 该桩将被跳过" >&2; }

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
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：loadRagDoc 又自己起后台读整库（P1-a 拆掉的那条链复活）─────────
# 症状：点开库又要等"读整库 + 拼整库 + setText 整库"（几十万字符 = 秒级），
#       而 P1-a 之后框只装一页、列表与预览共用 refreshRagRows 那一次读盘。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        refreshRagRows(name)\n    }"
new = ("        refreshRagRows(name)\n"
       "        ioExecutor.execute {\n"
       "            RagIndexStore.readEntries(this, name)\n"
       "        }\n    }")
assert s.count(old) >= 1, "桩①：没找到 loadRagDoc 末尾"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩①（loadRagDoc 又起后台读整库）" "loadRagDoc 不再自己起后台读"
reset_src

# ── 2. 桩②：列表刷新发在归属**之前**（会被自己的归属判据丢掉）───────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚点必须是**语义稳定**的那两行（赋值本身），不是后面那句注释的措辞 ——
#   注释会随口径改（P1-a -> P1-b 就改过一次），而"归属写在刷新之前"这条关系不变。
#   锚在注释上会让这条桩随一次无关的措辞改动**静默跳过**（跳过 = 这一层没人守）。
# ⚠ 锚点取 `loadRagDoc` 里那三行的**唯一组合**（`isInitialized` + 两个赋值）：
#   单独两行赋值在文件里有两处（`openNewRagKb` 也这么写），`count == 1` 会失败。
old = ("        if (!::ragDocEt.isInitialized) return\n"
       "        ragDocOwner = name\n        ragDocDirty = false\n")
new = ("        if (!::ragDocEt.isInitialized) return\n"
       "        refreshRagRows(name)\n"
       "        ragDocOwner = name\n        ragDocDirty = false\n")
assert s.count(old) == 1, "桩②：没找到归属那几行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩②（列表发在归属之前）" "归属（ragDocOwner）写在发起列表刷新**之前**"
reset_src

# ── 3. 桩③：删除成功后照旧整份重读（6 MB 又读一遍）─────────────────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                if (err == null) dropRowLocally(name, index)"
assert s.count(old) == 1, "桩③：没找到本地推算那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, "                refreshRagRows(name)", 1))
PYSTUB
must_red "桩③（删除后整份重读）" "删除那条路上**没有** refreshRagRows"
reset_src

# ── 4. 桩④：本地推算**不验**"下标即行号"（按来路不明的表推行号）──────────
# 症状：删掉的是隔壁那一行，而屏幕上一切正常（本仓库最忌讳的那一类）。
stub_py "$CAP" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        for (i in rows.indices) if (rows[i].first != i) return null\n"
assert s.count(old) == 1, "桩④：没找到不变量那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYSTUB
must_red "桩④（本地推算不验对齐不变量）" "先验「下标即行号」的不变量"
reset_src

# ── 5. 桩⑤：本地推算**保留旧行号**（不按新位置重建）─────────────────────
# 症状：删掉第 3 条之后，第 4 条顶着"4."的标号、点它删的是第 3 条。
stub_py "$CAP" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val out = ArrayList<Pair<Int, String?>>(kept.size)\n        for (i in kept.indices) out.add(i to kept[i].second)\n        return out"
new = "        return kept"
assert s.count(old) == 1, "桩⑤：没找到重建行号那一段"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤（本地推算保留旧行号）" "行号按新位置重建"
reset_src

# ── 6. 桩⑥：本地推算去碰磁盘（那它就没有存在的理由）─────────────────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val next = RagUiCap.dropRowAt(ragRowsShown, index)\n"
new = ("        val reread = RagIndexStore.readEntriesAligned(this, name).map { it?.text }\n"
       "        val next = RagUiCap.dropRowAt(ragRowsShown, index)\n"
       "        if (reread.isEmpty()) return\n")
assert s.count(old) == 1, "桩⑥：没找到纯函数调用那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑥（本地推算里出现磁盘调用）" "dropRowLocally 里**一个磁盘调用都没有**"
reset_src

# ── 9. 桩⑨：UI 里手写第二份推算（不走纯函数）───────────────────────────
# 症状：两份实体漂移，而漂移的形态是"删错隔壁那一行"，屏幕上一切正常。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val next = RagUiCap.dropRowAt(ragRowsShown, index)\n        if (next == null) { refreshRagRows(name); return }"
new = "        val kept = ragRowsShown.toMutableList().apply { removeAt(index) }\n        val next = kept.mapIndexed { i, p -> i to p.second }"
assert s.count(old) == 1, "桩⑨：没找到纯函数那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑨（UI 里手写第二份推算）" "推算走纯函数 RagUiCap.dropRowAt"
reset_src

# ── 10. 桩⑩：dropRowAt 不返回 null（对不上也硬算）─────────────────────
stub_py "$CAP" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 两处 `return null` 都拿掉（对不上也硬算 = 这份推算不再"可判不可信"）
old = ("        if (rows.isEmpty() || index < 0 || index >= rows.size) return null\n"
       "        // 不变量：下标即行号。对不上 = 这份表来路不明，不许按下标推算。\n"
       "        for (i in rows.indices) if (rows[i].first != i) return null\n")
assert s.count(old) == 1, "桩⑩：没找到那两处判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYSTUB
must_red "桩⑩（dropRowAt 不返回 null）" "对不上时返回 null"
reset_src

# ── 7. 桩⑦：摘要行退回默认断行策略（BALANCED + 连字符）─────────────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)\n                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE\n"
assert s.count(old) == 1, "桩⑦：没找到断行策略那两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYSTUB
must_red "桩⑦（摘要行退回默认断行策略）" "摘要行用 BREAK_STRATEGY_SIMPLE"
reset_src

# ── 8. 桩⑧：列表改用压紧的 readEntries（删除会删错隔壁那一行）──────────
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                RagIndexStore.readEntriesAligned(this, name).map { it?.text }"
new = "                RagIndexStore.readEntries(this, name).map { it.text }"
assert s.count(old) == 1, "桩⑧：没找到列表取数那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑧（列表用压紧的 readEntries）" "列表仍用 readEntriesAligned"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 列表先出 + 删除不重读守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 列表先出 + 删除不重读守卫自测：PASS $ok / FAIL $bad ==="; exit 1
