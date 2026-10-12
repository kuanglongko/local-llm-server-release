#!/bin/sh
# 自测 `tools/rag_kb_name/check_wiring.py`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是**存在性、结构性**断言，三个自欺模式（与 new_kb 那份同族）：
#
#   1. **恒真**：pattern 写成到处都有的文本。本族最容易恒真的有两条 ——
#      "用户可见文案里不再有旧按钮名『建库/重建』"（解释这次改名的注释里
#      必然带着那四个字）与"改名这条路上没有 buildIndex"（`doBuildIndex`
#      那一段就在同一个文件里）。前者靠**剥注释**、后者靠**锚函数体内**解掉，
#      而这两件事只有**真打桩验红**才证明它有效。
#   2. **锚错位置**：钉"文件里出现过 displayName"而不是"**list() 里**用的是它"。
#      所以判据一律锚函数体内 —— 而本族的锚点还有个额外坑：
#      `list()` 是**表达式体函数**且体内有多个 lambda 的 `{}`，
#      朴素的大括号计数会在第一个 lambda 的 `}` 处收工（实测只取到 90 个字符），
#      于是判据永远 FAIL 而源码是对的。桩⑨ 专门钉这一条。
#   3. **恒假后被静音**：pattern 与源码差一个字符 -> 永远 FAIL，
#      很快就会有人把 `exit 1` 改成 `|| true`。桩①②③⑥⑧ 都是这一族。
#
# 桩全部来自"真正会写错的写法"（这一族真正的失败方向）：
#   · 改名走成"新建"（清空正文框 + 建目录 = 用户报的那个 bug 原样复现）
#   · 改名顺手重建索引（几十秒的等待 + 一次没必要的向量重算）
#   · 改名不同步 currentKb / 归属（列表新名、检索旧名）
#   · 唯一性没挡（两个库同名，"我往哪个库里导入"没有答案）
#   · 删库退回 `drop`（留目录 = 一行 0 条的幽灵，没有任何按钮对它有效）
#   · 删库不清当前库（检索一直找一个不存在的库：查不到、接口 200、日志干净）
#   · 按钮名改回旧名（用户以为"建库"是建一个库，而它只算索引）
#   · 列表退回目录名（改名之后列表与库名框各说一个名字）
#   · 大括号计数在 lambda 处收工（判据假红 —— 这一条钉的是守卫自己）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_kb_name_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/rag_kb_name/check_wiring.py
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
STORE=$SRC/RagIndexStore.kt
NAME=$SRC/RagKbName.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$STORE" "$TMP/store.bak"; cp "$NAME" "$TMP/name.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/store.bak" "$STORE"; cp "$TMP/name.bak" "$NAME"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/store.bak" "$STORE"; cp "$TMP/name.bak" "$NAME"; }

run_guard() { set +e; "$PY" "$GUARD" "$ACT" "$STORE" "$NAME" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

stub_py() { "$PY" - "$@" || echo "⚠ 打桩脚本失败（锚点漂了？）—— 该桩将被跳过" >&2; }

# ⚠ 只认**带 FAIL 前缀**的那一行。本守卫里有若干对"一正一反"的判据
#（"没有 `mkdirs`" 与 "改名走成新建"），而它们的文案开头是同一串字 ——
# 直接 grep 整份输出会先命中那条 **PASS** 行，于是这条桩自测会把
# "判据确实点到了" 报成失败。这不是小事：桩自测假红的下场是没人再看它。
must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    line=$(grep -E "^FAIL " "$TMP/guard.out" | grep -F "$key" | head -1)
    if [ -n "$line" ]; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」（没有任何 FAIL 行命中它）" 0; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：改名走成"新建"（清空正文框 + 建目录）──────────────────────
# 症状：用户报的那条 bug 原样复现 —— 磁盘上多一个空库，旧库连着索引躺着，
# 而屏幕上两句提示都说"成了"。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (!forceNew && isRenameOfCurrent(name)) { doRenameCurrentKb(name); return }'
new = '        if (false && isRenameOfCurrent(name)) { doRenameCurrentKb(name); return }\n        RagIndexStore.dirOf(this, name)?.mkdirs()'
assert s.count(old) == 1, "桩①：没找到改名判据那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（改名走成新建）" "切库那一跳先判"
reset_src

# ── 2. 桩②：改名顺手重建索引（几十秒的等待 + 一次没必要的向量重算）────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        LlmEngine.uiLog("[知识库] 改名：$owner -> $display（目录仍为 $dir，索引未重建）")'
new = '        LlmEngine.buildIndex(this, dir, emptyList())\n        LlmEngine.uiLog("[知识库] 改名：$owner -> $display（目录仍为 $dir，索引未重建）")'
assert s.count(old) == 1, "桩②：没找到改名成功那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（改名顺手重建索引）" "改名这条路上没有 \`buildIndex\`"
reset_src

# ── 3. 桩③：改名只换显示名，不换 currentKb / 归属 ────────────────────────
# 症状：列表里是新名、检索读的还是旧名（两个信号打架，而状态行说的是旧名）。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        RagInject.setCurrentKb(this, display)\n        ragKbEt.setText(display)'
new = '        ragKbEt.setText(display)'
assert s.count(old) == 1, "桩③：没找到 currentKb 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（改名不同步 currentKb）" "当前库也跟着换"
reset_src

# ── 4. 桩④：唯一性没挡（两个库同名）───────────────────────────────────
# 症状：屏幕上两行名字一样，"我往哪个库里导入"没有答案 ——
# 而选中反查会随文件顺序漂（内容进了另一个库）。
stub_py "$NAME" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚点跟着源码搬家（ISSUE #259 本轮加了 liveDirs 那一支）：判据现在在
#   checkName 体内是**两段**（表那一列 + 磁盘目录那一列），桩把整段唯一性
#   比较换成 `return null`（= 完全不判重名）。
old = '''                if (s == safe && d != dir) return "已经有另一个库叫「$safe」了 —— 库名不能重复"'''
new = '''                if (false) return "已经有另一个库叫「$safe」了 —— 库名不能重复"'''
assert s.count(old) == 1, "桩④：没找到唯一性判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（唯一性没挡）" "唯一性判据真的在 checkName 体内比较"
reset_src

# ── 4b. 桩④b：唯一性只查表、**不看磁盘目录**（用户报的"两个库同时被选中"）──
# 症状：在 test 上改成 test1（磁盘已有目录 test1），只查表的判据放行 ——
# 两个库共用显示名，列表两行同时画 ●、dirOf 把真目录遮蔽掉。
stub_py "$NAME" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''            if (safe != dir && safe in liveDirs) {
                return "已经有另一个库叫「$safe」了 —— 库名不能重复"
            }'''
new = '''            // 打桩：不查磁盘目录'''
assert s.count(old) == 1, "桩④b：没找到 liveDirs 判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④b（唯一性不查磁盘目录）" "判据是 `safe != dir && safe in liveDirs`"
reset_src

# ── 4c. 桩④c：列库前不自愈（旧版本改坏的盘永远修不回来）──────────────────
# 症状：挡得住新的改名，但盘上那份坏表还在 —— 下次进来照样两个库同名。
# ⚠ 锚点跟着源码搬家（ISSUE #228 把 `refreshRagList` 拆成"骨架 + 后台补齐"两层）：
#   自愈那一跳现在在 `loadRagListCounts` 里（它才是**后台**那一层，也才是
#   紧接着 `RagIndexStore.list(this)` 的那一跳）。判据的语义没变 ——
#   桩要打的也还是同一跳，只是它现在住的地方换了名字。
#   只改锚点、**不动** must_red 那句：它判的是语义（"列库前在后台自愈那份表"），
#   而下面那一步会实测这一桩**真的让守卫变红**（否则就是"改了锚点于是恒绿"）。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''            runCatching {
                val live = RagIndexStore.liveDirs(this)
                RagIndexStore.repairNames(this, live)
            }'''
new = '''            // 打桩：列库前不自愈'''
assert s.count(old) == 1, "桩④c：没找到自愈那一跳"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④c（列库前不自愈）" "列库前在**后台**自愈那份表"
reset_src

# ── 5. 桩⑤：删库退回 `drop`（留目录 = 一行 0 条的幽灵）─────────────────
# 症状：列表里还有它，而**没有任何按钮**对一行 0 条的库起作用
#（删索引要索引在、建索引要正文在、删除是这一轮才有的）。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                val ok = runCatching { RagIndexStore.delete(this, name) }.getOrDefault(false)'
new = '                val ok = RagIndexStore.drop(this, name)'
assert s.count(old) == 1, "桩⑤：没找到 delete 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（删库退回 drop）" "不许**出现 drop"
reset_src

# ── 6. 桩⑥：删库不清「当前库」─────────────────────────────────────────
# 症状：检索一直去找一个已经不存在的库 —— 查不到命中、接口 200、日志干净。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''                if (RagInject.currentKb(this) == name) {
                    RagInject.setCurrentKb(this, "")
                    ragKbEt.setText("")
                }'''
new = ''
assert s.count(old) == 1, "桩⑥：没找到清当前库那一段"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（删库不清当前库）" "把「当前库」也清掉"
reset_src

# ── 7. 桩⑦：按钮名改回旧名 ────────────────────────────────────────────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'ragBuildBtn = btn("建索引/重建索引")'
new = 'ragBuildBtn = btn("建库/重建")'
assert s.count(old) == 1, "桩⑦：没找到建索引按钮"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（按钮名改回旧名）" "用户可见文案里不再有旧名"
reset_src

# ── 8. 桩⑧：列表退回目录名（改名之后列表与库名框各说一个名字）──────────
stub_py "$STORE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'Info(displayName(ctx, d.name), entries, dim,'
new = 'Info(d.name, entries, dim,'
assert s.count(old) == 1, "桩⑧：没找到 list 里给名字那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（列表退回目录名）" "列表给的是**显示名**"
reset_src

# ── 9. 桩⑨：这一条钉的是**守卫自己** ───────────────────────────────────
# `list()` 是表达式体函数，体内有多个 lambda 的 `{}`。朴素的大括号计数会在
# 第一个 lambda 的 `}` 处收工（实测只取到 90 个字符），于是"列表给的是不是
# 显示名"这条判据**永远 FAIL** —— 而源码是对的。
# 症状是"假红"，而假红的判据很快会被静音（那时它连假绿都不如）。
# 所以这里把守卫的 fnbody 换回朴素版，断言它**必须**报红。
stub_py "$GUARD" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    ln = fnbody(store, "fun list(")'
new = '    ln = fnbody_plain(store, "fun list(")'
assert s.count(old) == 1, "桩⑨：没找到 list 的锚点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（大括号计数在 lambda 处收工）" "列表给的是**显示名**"
# 守卫自身要还原（它不在 reset_src 的三份源码里）
set +e; "$PY" - "$GUARD" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    ln = fnbody_plain(store, "fun list(")'
new = '    ln = fnbody(store, "fun list(")'
assert s.count(old) == 1, "还原桩⑨：锚点漂了"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
set -e
reset_src

# ── 10. 桩⑩：把“先剥注释”去掉（旧按钮名那条判据**恒红**）──────────────
# 这一条钉的是 `visible_strings` 里那道 stripper。不剥注释的话，
# 解释“为什么改名”的注释里必然带着旧按钮名 —— 于是“用户可见文案里不再有旧名”
# 这条判据**永远红**，而源码是对的。恒红的判据很快会被静音（那时它连假绿都不如）。
#
# 桩的形态：让 `visible_strings` 直接在**未剥注释**的源码上取字面量。
# 那样会多出注释里那几个「建库/重建」，判据必须因此报红。
sed_py() { "$PY" - "$@" || { echo "⚠ 打桩脚本失败（锚点漂了？）"; exit 1; }; }
sed_py "$GUARD" <<'PYSTUB'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
OLD = '    return re.findall(r\'"((?:[^"\\\\]|\\\\.)*)"\', strip_comments(src))\n'
NEW = '    return re.findall(r\'"((?:[^"\\\\]|\\\\.)*)"\', src)\n'
assert s.count(OLD) == 1, "桩⑩：没找到 visible_strings 的返回那一行"
s = s.replace(OLD, NEW, 1)
open(p, "w", encoding="utf-8").write(s)
PYSTUB
must_red "桩⑩（不剥注释：旧按钮名判据恒红）" "用户可见文案里不再有旧名"
sed_py "$GUARD" <<'PYSTUB'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
OLD = '    return re.findall(r\'"((?:[^"\\\\]|\\\\.)*)"\', src)\n'
NEW = '    return re.findall(r\'"((?:[^"\\\\]|\\\\.)*)"\', strip_comments(src))\n'
assert s.count(OLD) == 1, "还原桩⑩：锚点漂了"
s = s.replace(OLD, NEW, 1)
open(p, "w", encoding="utf-8").write(s)
PYSTUB
reset_src

# ── 收尾：还原之后守卫必须又是绿的（防"某一桩的还原漏了"）────────────────
if run_guard; then ck "收尾：所有桩还原之后守卫重新全绿" 1
else ck "收尾：所有桩还原之后守卫重新全绿" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 改名/删库/按钮名守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 改名/删库/按钮名守卫自测：PASS $ok / FAIL $bad ==="; exit 1
