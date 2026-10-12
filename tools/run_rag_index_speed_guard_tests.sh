#!/bin/sh
# 自测 `tools/run_rag_index_speed_guard.sh`：拿几份**打桩源码树**跑它，
# 断言"该红的红、且失败时点得出是哪一条"（ISSUE #269）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是**结构关系**（某函数的默认值是不是走了某个 helper /
# 某个调用的实参是不是来自某个函数），而在**正确**的源码上它们全绿 ——
# 一个永远绿的判据与没有判据在效果上一样。
#
# 桩全部取自"真正会写错的写法"（本轮真踩到的三个）：
#   · `loadEmbedModel` 的默认值写回字面量 4（与设备无关）；
#   · `doLoadEmbed` 不传线程数 —— **这就是用户报的形态**：
#     用户在设置页把 threads 从 4 调到 8，建库速度一点都不变；
#   · `parseThreads()` 的兜底写回常数（输入框清空后按 4 跑）；
#   · `RagCost` 的上界回到 0.30（把 7000 条报成"约 35 分钟"，真机两小时）；
#   · `humanSeconds` 去掉"小时"档（两小时被说成"十几分钟"）。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_index_speed_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_index_speed_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ENG=$SRC/LlmEngine.kt
ACT=$SRC/EngineActivity.kt
COST=$SRC/RagCost.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ENG" "$TMP/eng.bak"; cp "$ACT" "$TMP/act.bak"; cp "$COST" "$TMP/cost.bak"
restore() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/act.bak" "$ACT";
            cp "$TMP/cost.bak" "$COST"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/eng.bak" "$ENG"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/cost.bak" "$COST"; }

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

# ── 桩①：loadEmbedModel 的默认值写回字面量 4 ───────────────────────────────
# 这正是本轮要修的东西本身。写死字面量时守卫必须红。
"$PY" - "$ENG" <<'PYEOF1'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "nThreads: Int = defaultThreads(),"
assert s.count(old) == 1, "stub1: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "nThreads: Int = 4,", 1))
PYEOF1
must_red "桩①（默认值写回字面量 4）" "默认值不是 defaultThreads"
reset_src

# ── 桩②：doLoadEmbed 不传线程数（**用户报的那个形态**）────────────────────
# 用户在设置页调了 threads，建库速度一点不变，而界面上没有任何东西
# 说明"这个参数对嵌入不生效"。
"$PY" - "$ACT" <<'PYEOF2'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚点跟 0.9.212 改过：那一版给这一处多传了一个 `nCtx` 具名实参，
# 而本桩原来锚的是"只有 nThreads 一个具名实参"的旧写法 —— 不跟着改时
# `assert` 直接失败、整条桩被跳过。桩自己会喊"锚点失效"（比静默空转强），
# 但**被跳过的桩等于没有覆盖**，所以这里按新的实参表锚。
old = "loadEmbedModel(f.absolutePath, nCtx = embedCtxForLoad,\n                    nThreads = parseThreads())"
assert s.count(old) == 1, "stub2: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "loadEmbedModel(f.absolutePath)", 1))
PYEOF2
must_red "桩②（doLoadEmbed 不传线程数）" "没传"
reset_src

# ── 桩③：parseThreads() 的兜底写回常数 4 ───────────────────────────────────
# 输入框被清空 = "我没给值"，按 4 跑而不是按本机核数 —— 与输入框的默认值分家。
"$PY" - "$ACT" <<'PYEOF3'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "?: LlmEngine.defaultThreads()"
assert s.count(old) == 1, "stub3: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "?: 4", 1))
PYEOF3
must_red "桩③（parseThreads 兜底写回常数）" "兜底不是 defaultThreads"
reset_src

# ── 桩④：RagCost 的上界回到 0.30（比真机读数还小）────────────────────────
# 这时 7000 条算出来是 35 分钟，而真机两小时 —— 提示报小了比不提示更坏。
"$PY" - "$COST" <<'PYEOF4'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s2 = re.sub(r'(SEC_PER_ENTRY_HI\s*=\s*)1\.00', r'\g<1>0.30', s)
assert s2 != s, "stub4: anchor miss"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF4
must_red "桩④（RagCost 上界回到 0.30）" "上界覆盖真机读数"
reset_src

# ── 桩⑤：humanSeconds 去掉「小时」档 ──────────────────────────────────────
# 上界 1.0 秒/条时 7000 条接近两小时；最高档若是"十几分钟"，提示仍在骗人。
"$PY" - "$COST" <<'PYEOF5'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            hi < 3600.0 -> "约 ${ceil(lo / 60.0).coerceAtLeast(1)}~${ceil(hi / 60.0)} 分钟（可能超过半小时）"\n            else -> "**数小时级**'
assert old in s, "stub5: anchor miss"
# 把小时档整段换成旧那句模糊话（`3600` 与"小时"一起消失）
i = s.index(old)
j = s.index("\n        }", i)
s = s[:i] + '            else -> "可能十几分钟甚至更久"' + s[j:]
open(p, 'w', encoding='utf-8').write(s)
PYEOF5
must_red "桩⑤（humanSeconds 去掉小时档）" "有『小时』这一档"
reset_src

echo
if [ "$bad" = 0 ]; then
    echo "=== 嵌入建库速度守卫自测：PASS $ok / FAIL $bad ==="
else
    echo "=== 嵌入建库速度守卫自测：PASS $ok / FAIL $bad ==="
    exit 1
fi
