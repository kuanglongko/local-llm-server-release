#!/bin/sh
# 自测 `tools/rag_new_kb/check_wiring.py`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是**存在性、结构性**断言，三个自欺模式（与 plain_import 那份同族）：
#
#   1. **恒真**：pattern 写成到处都有的文本。本族最容易恒真的有两条 ——
#      "不再凭空兜底『导入的库』"（解释这件事的注释里必然带着那四个字）与
#      "旧的 plainLibName 已删"（同上）。所以那两条都**剥注释**，
#      而剥注释这件事只有**真打桩验红**才证明它有效。
#   2. **锚错位置**：钉"文件里出现过 setRagDocText"而不是"**新建库这条路径里**
#      用的是它" —— 前者在别处（切库回填）也成立。所以判据一律锚函数体内。
#   3. **恒假后被静音**：pattern 与源码差一个字符 -> 永远 FAIL，
#      很快就会有人把 `exit 1` 改成 `|| true`。
#
# 桩全部来自"真正会写错的写法"（这一族真正的失败方向）：
#   · 目标库又退回"取文件名 / 凭空兜底一个名字"（静默建出用户没要的库）
#   · 新建库**不清正文框**（下一步把上一个库的正文建成新库）
#   · 新建库为了进列表去**建空目录**（留下点不动、删不掉的幽灵行）
#   · 空内容也落盘（"本次未添加内容默认丢弃"被违背）
#   · pending 落盘后不清（库名还挂在界面上，而它其实已经落盘）
#   · 重复点同一个库时又走"新建"那条路（清空还没落盘的内容）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_new_kb_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/rag_new_kb/check_wiring.py
SRC=app/src/main/java/com/xiaowan/localinference
RA=$SRC/RagArchive.kt
ACT=$SRC/EngineActivity.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$RA" "$TMP/ra.bak"; cp "$ACT" "$TMP/act.bak"
restore() { cp "$TMP/ra.bak" "$RA"; cp "$TMP/act.bak" "$ACT"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ra.bak" "$RA"; cp "$TMP/act.bak" "$ACT"; }

run_guard() { set +e; "$PY" "$GUARD" "$ACT" "$RA" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

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

# ── 1. 桩①：目标库退回"取文件名"（.txt 那条路又和「导入库」分家）──────────
# 症状：想往现有库里追加一篇文档只能先把文件改名；更坏的是静默建出第二个库
#（他以为在往 A 里追加，磁盘上多了个"文件名"库，A 里一条没多）。
stub_py "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'return Parsed(out, "", 0, null, splitNoPunct = nosplit)'
new = 'return Parsed(out, plainLibName(name), 0, null, splitNoPunct = nosplit)'
assert s.count(old) == 1, "桩①：没找到纯文本解码的返回"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（目标库退回取文件名）" "纯文本解码回填空库名"
reset_src

# ── 2. 桩②：目标库又凭空兜底一个用户没要的名字 ──────────────────────────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val suggested = fromEnvelope ?: fromCurrent ?: ""'
new = 'val suggested = fromEnvelope ?: fromCurrent ?: "导入的库"'
assert s.count(old) == 1, "桩②：没找到目标库预填那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（目标库又凭空兜底一个名字）" "不再凭空兜底"
reset_src

# ── 3. 桩③：新建库**不清正文框**（用户报的那个误导）────────────────────
# 症状：输入框里是新库名 B，框里还是 A 的正文 —— 下一步「建库/重建」把 A 建成 B。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        setRagDocText("")\n        ragRowsPage = 1'
new = '        ragRowsPage = 1'
assert s.count(old) == 1, "桩③：没找到清空正文框那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（新建库不清正文框）" "新建库**清空正文框**"
reset_src

# ── 4. 桩④：新建库为了"进列表"去建空目录 ────────────────────────────────
# 症状：留下一个点不动、删不掉的幽灵行（没有「删索引」的对象），
# 而"丢弃"这个动作也变成了"留下一个空目录"。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        ragPendingKb = name\n        RagInject.setCurrentKb(this, name)'
new = '        ragPendingKb = name\n        RagIndexStore.dirOf(this, name)?.mkdirs()\n        RagInject.setCurrentKb(this, name)'
assert s.count(old) == 1, "桩④：没找到 pending 赋值那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（新建库去建空目录）" "新建库**不建目录**"
reset_src

# ── 5. 桩⑤：空内容也落盘（违背"本次未添加内容默认丢弃"）──────────────────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (docs.isEmpty()) return          // 没有内容 = 还是新建态，不落盘、不建目录'
new = '        if (false) return'
assert s.count(old) == 1, "桩⑤：没找到空内容判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（空内容也落盘）" "空内容**不落盘**"
reset_src

# ── 6. 桩⑥：pending 落盘后不清（库名还挂在界面上，而它其实已经落盘）──────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚点只取**函数体里那两行**，不取整个函数签名：pendingKbLanded 本体现在
#   多了一段"把显示名写进表"（ISSUE #259 的改名），而桩只关心"清不清那个字符串"。
#   整段锚的话，以后每加一句都要跟着改桩 —— 而桩漂的症状是它**悄悄不再挡住任何东西**。
old = '        ragPendingKb = ""\n        refreshRagList()\n    }'
new = '        refreshRagList()\n    }'
assert s.count(old) == 1, "桩⑥：没找到 pendingKbLanded 的收尾那两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（落盘后 pending 不清）" "真的清掉了那个名字"
reset_src

# ── 7. 桩⑦：重复点同一个库时又走"新建"那条路（清空还没落盘的内容）───────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (name == ragDocOwner) {
            RagInject.setCurrentKb(this, name)
            renderRagStatus()
            return
        }
"""
assert s.count(old) == 1, "桩⑦：没找到重复点判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩⑦（重复点同一个库又走新建）" "已经在那个库上时什么都不做"
reset_src

# ── 8. 桩⑧：丢弃 pending 时**碰磁盘**（删别人的东西）────────────────────
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    private fun clearPendingKb() {
        ragPendingKb = ""
    }"""
new = """    private fun clearPendingKb() {
        RagIndexStore.dirOf(this, ragPendingKb)?.deleteRecursively()
        ragPendingKb = ""
    }"""
assert s.count(old) == 1, "桩⑧：没找到 clearPendingKb"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（丢弃 pending 时碰磁盘）" "『丢弃』**不许**碰磁盘"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 新建库守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 新建库守卫自测：PASS $ok / FAIL $bad ==="; exit 1
