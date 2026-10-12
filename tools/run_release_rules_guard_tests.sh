#!/bin/sh
# 自测 `tools/run_release_rules_guard.py`：拿**就地改坏的真数据**跑它，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# 桩全是真会写出来的坏法（丙-2 的三种静默形态）：
#   ① 规则的 old 与 new **都不在**源码里（源码又改过一遍）-> 规则再也命不中 = 漏清洗
#   ② 规则的 path 指向不存在的文件 -> 规则永远空转
#   ③ 两条规则 old 重复 -> 一条永远轮不到
#   ④ 基线登记被删 -> 漂移提示永远是"未登记"，没人再看
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明
PY=${PYTHON:-python3}
GUARD=tools/run_release_rules_guard.py
RULES=scripts/release-rules.json
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$RULES" "$TMP/rules.bak"
restore() { cp "$TMP/rules.bak" "$RULES"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/rules.bak" "$RULES"; }

run_guard() { set +e; "$PY" "$GUARD" > "$TMP/out" 2>&1; rc=$?; set -e; return $rc; }
must_red() {
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/out"; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」" 0; fi
}

# ── 0. 基线 ────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动规则 -> 守卫全绿" 1
else ck "基线：未改动规则 -> 守卫全绿（实际红了）" 0; sed -n '1,20p' "$TMP/out"; fi

# ── 1. 桩①：把某条规则的 old/new 都改成源码里没有的文本 ──────────────────
"$PY" - "$RULES" <<'PYEOF'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding="utf-8"))
# 找一条活跃规则（old 在源码里），把它的 old/new 都改成不存在的文本
import os
for r in d["rules"]:
    if os.path.exists(r["path"]) and r["old"] in open(r["path"], encoding="utf-8").read():
        r["old"] = "桩：这段文本源码里没有 AAA"
        r["new"] = "桩：这段文本源码里也没有 BBB"
        break
else:
    raise SystemExit("桩①：没找到活跃规则")
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
PYEOF
must_red "桩①（old/new 都不在源码里 -> 规则失效 = 漏清洗）" "没有失效的规则"
reset_src

# ── 2. 桩②：路径指向不存在的文件 ──────────────────────────────────────────
"$PY" - "$RULES" <<'PYEOF'
import json, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = '"path": "README.md"'
assert s.count(old) >= 1, "桩②：没找到 README 条目"
s = s.replace(old, '"path": "README-does-not-exist.md"', 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩②（规则 path 悬空 -> 永远空转）" "每条规则的 path 都存在"
reset_src

# ── 3. 桩③：基线登记被删（漂移提示永远"未登记"）────────────────────────────
"$PY" - "$RULES" <<'PYEOF'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding="utf-8"))
d["generated_from"]["dev"] = ""
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
PYEOF
must_red "桩③（基线登记被删 -> 漂移永远'未登记'）" "generated_from.dev 已登记"
reset_src

# ── 4. 全部还原后回绿 ──────────────────────────────────────────────────────
if run_guard; then ck "全部还原后：守卫回绿（桩没有残留污染）" 1
else ck "全部还原后：守卫回绿" 0; fi

echo
echo "=== 发布规则守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
