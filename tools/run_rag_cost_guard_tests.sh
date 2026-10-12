#!/bin/sh
# 自测 `tools/run_rag_cost_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据有一半是**位置关系**（"提示在开工之前"），而这类断言最容易的两个
# 自欺模式是：
#   1. 锚"文件里出现过 buildPrompt"而不是"**在 doBuildIndex 里、且在开工之前**"——
#      前者在函数定义处就恒真，于是"根本没人调"也能绿（而那正是要防的事）。
#   2. 锚"出现过某个数"而不是"两个数**相等**"—— 两边一起改歪也能绿。
#
# 桩全部来自"真正会写错的写法"：
#   · 提示排到开工之后（用户先看到"建库中…"才知道贵，与不提示等价）
#   · 小批量也弹框（用户闭眼点确定，真有一条贵的提示时也被无视）
#   · 开工入口多开一个（两条路径各设一次状态标志，必漏一个 -> 按钮灰了不恢复）
#   · RagCost 里那份抄写的上限与真实值分家（撞上限时算错，用户按错方向拆文档）
#   · RagCost 引了 Android / RagIndexStore（纯逻辑套件编不过 = 唯一的证据没了）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_cost_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_cost_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
COST=$SRC/RagCost.kt
STORE=$SRC/RagIndexStore.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$COST" "$TMP/cost.bak"; cp "$STORE" "$TMP/store.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/cost.bak" "$COST";
            cp "$TMP/store.bak" "$STORE"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/cost.bak" "$COST";
              cp "$TMP/store.bak" "$STORE"; }

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

# ── 桩①：提示排到开工**之后**（用户先看到"建库中…"才知道贵）──────────────
# 这一支的界面观感与"不提示"等价，甚至更坏：弹框会挡在已经开跑的建库前面。
# ⚠ 锚点要**限定在 doBuildIndex 体内**：ISSUE #259 起 `startBuildDisk`
#   也调了一次 `RagCost.buildPrompt(`（"用磁盘那份建库"那条路同样要先说多久），
#   所以全局 `s.count(old) == 1` 已不成立 —— 桩会因为"锚点不唯一"直接跳过，
#   而那一跳是**静默**的（守卫不会变红，看起来像"这条桩本来就不该红"）。
#   这里改成"在 doBuildIndex 的体里找"，与守卫本身的判据口径一致。
"$PY" - "$ACT" <<'PYEOF1'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()


def fn_span(src, name):
    m = re.search(r'private\s+fun\s+' + re.escape(name) + r'\s*\(', src)
    if not m:
        return None
    k = src.index('{', m.end())
    depth = 0
    for x in range(k, len(src)):
        if src[x] == '{':
            depth += 1
        elif src[x] == '}':
            depth -= 1
            if depth == 0:
                return (k, x + 1)
    return None


span = fn_span(s, 'doBuildIndex')
assert span, "stub1: doBuildIndex 找不到"
i0, i1 = span
body = s[i0:i1]
old = "        val prompt = RagCost.buildPrompt("
assert body.count(old) == 1, "stub1: anchor miss（doBuildIndex 体内）"
# 把提示整段挪到 startBuildIndex 之后：最简做法是把它**改成不可达的顺序** ——
# 在 startBuildIndex(name, docs) 之后再取 prompt。这里直接把那一行搬到后面。
i = body.index(old)
j = body.index("\n", body.index("maxChunks)", i))
line = body[i:j]
body = body[:i] + body[j:]
k = body.index("        startBuildIndex(name, docs)")
body = body[:k] + line + "\n" + body[k:]
s = s[:i0] + body + s[i1:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF1
must_red "桩①（提示排在开工之后）" "提示排在开工**之后**"
reset_src

# ── 桩②：小批量也弹框（没过 `!= null` 那道闸）────────────────────────────
# ⚠ 同桩①③：这一句在两个函数里都有（doBuildIndex 与 startBuildDisk），
#   限定在 doBuildIndex 体内（守卫判的也正是那一处）。
"$PY" - "$ACT" <<'PYEOF2'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
m = re.search(r'private\s+fun\s+doBuildIndex\s*\(', s)
assert m, "stub2: doBuildIndex 找不到"
k = s.index('{', m.end())
depth = 0
end = k
for x in range(k, len(s)):
    if s[x] == '{':
        depth += 1
    elif s[x] == '}':
        depth -= 1
        if depth == 0:
            end = x + 1
            break
body = s[k:end]
old = "        if (prompt != null) {"
assert body.count(old) == 1, "stub2: anchor miss（doBuildIndex 体内）"
body = body.replace(old, "        if (true) {", 1)
s = s[:k] + body + s[end:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF2
must_red "桩②（条数小时也弹框）" "提示没过"

reset_src

# ── 桩③：开工入口多开一处（两条路径各设一次状态标志）─────────────────────
# 症状：按钮灰了却永远不恢复（用户以为卡死），而日志干净。
"$PY" - "$ACT" <<'PYEOF3'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 同桩①：`startBuildIndex(name, docs)` 这一句在 `startBuildDisk` 里也有一处
#   （"用磁盘那份建库"同样要走确认/直通车），所以要限定在 doBuildIndex 体内。
m = re.search(r'private\s+fun\s+doBuildIndex\s*\(', s)
assert m, "stub3: doBuildIndex 找不到"
k = s.index('{', m.end())
depth = 0
end = k
for x in range(k, len(s)):
    if s[x] == '{':
        depth += 1
    elif s[x] == '}':
        depth -= 1
        if depth == 0:
            end = x + 1
            break
body = s[k:end]
old = "        startBuildIndex(name, docs)\n"
assert body.count(old) == 1, "stub3: anchor miss（doBuildIndex 体内）"
body = body.replace(old, old + old, 1)
s = s[:k] + body + s[end:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF3
must_red "桩③（开工入口多开一处）" "处 startBuildIndex 调用"
reset_src

# ── 桩④：RagCost 里那份抄写的上限与真实值**分家** ────────────────────────
# 判据锚"两个数相等"：只锚"等于 16384"的话，两边一起改歪也能绿。
"$PY" - "$COST" <<'PYEOF4'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "const val DEFAULT_MAX_CHUNKS = 16384"
assert s.count(old) == 1, "stub4: anchor miss"
s = s.replace(old, "const val DEFAULT_MAX_CHUNKS = 4096", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF4
must_red "桩④（抄写的上限与真实值分家）" "不相等"
reset_src

# ── 桩⑤：RagCost 引了 Android（纯逻辑套件编不过 = 唯一的证据没了）────────
"$PY" - "$COST" <<'PYEOF5'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "package com.xiaowan.localinference\n"
assert s.count(old) == 1, "stub5: anchor miss"
s = s.replace(old, old + "import android.content.Context\n", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF5
must_red "桩⑤（RagCost 引了 android.*）" "RagCost 不 import android"
reset_src

# ── 桩⑥：导入那条路不再复用 RagCost（各自算一套，必然漂移）──────────────
"$PY" - "$ACT" <<'PYEOF6'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                val (lo, hi) = RagCost.estimateSeconds(after)"
assert s.count(old) == 1, "stub6: anchor miss"
s = s.replace(old, "                val (lo, hi) = 1.0 to 2.0", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF6
must_red "桩⑥（导入那条路不再复用 RagCost）" "导入确认框也复用了 RagCost"
reset_src

if run_guard; then ck "收尾：全部桩已还原 -> 守卫全绿" 1
else ck "收尾：全部桩已还原 -> 守卫全绿（实际红了）" 0; sed -n '1,60p' "$TMP/guard.out"; fi

echo
echo "=== 建库成本提示守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
