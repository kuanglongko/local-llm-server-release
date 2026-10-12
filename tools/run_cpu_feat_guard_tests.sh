#!/bin/sh
# 自测 `tools/run_cpu_feat_guard.sh`：拿**就地改坏的真源码**跑它，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# 桩全是真会写出来的坏法（对应"零症状"的两种历史形态）：
#   ① i8mm 的位常量写错（1<<13 -> 1<<12）-> 探测恒 0，静默选慢变体
#   ② dotprod 的来源写反（读 AT_HWCAP2）-> 同理，一位恒 0
#   ③ Kotlin 侧槽位对调（c[0] 当 i8mm）-> 两边都"看起来对"，判据整个反过来
#   ④ StderrPump 的 sink 裸调（不包 runCatching）-> sink 抛异常即停排空 -> 卡死
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明
PY=${PYTHON:-python3}
GUARD=tools/run_cpu_feat_guard.sh
CF=app/src/main/cpp/cpu_feat.c
SP=app/src/main/java/com/xiaowan/localinference/StderrPump.kt
LE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$CF" "$TMP/cf.bak"; cp "$SP" "$TMP/sp.bak"; cp "$LE" "$TMP/le.bak"
restore() { cp "$TMP/cf.bak" "$CF"; cp "$TMP/sp.bak" "$SP"; cp "$TMP/le.bak" "$LE"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/cf.bak" "$CF"; cp "$TMP/sp.bak" "$SP"; cp "$TMP/le.bak" "$LE"; }

run_guard() { set +e; REQUIRED=0 sh "$GUARD" > "$TMP/out" 2>&1; rc=$?; set -e; return $rc; }
must_red() {
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/out"; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」" 0; fi
}
patch() {
    "$PY" - "$1" <<PYEOF
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
$2
open(p, 'w', encoding='utf-8').write(s)
PYEOF
}

# ── 0. 基线 ────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了）" 0; sed -n '1,30p' "$TMP/out"; fi

# ── 1. 桩①：i8mm 位常量写错 ────────────────────────────────────────────────
patch "$CF" '
old = "#define HWCAP2_I8MM (1UL << 13)"
new = "#define HWCAP2_I8MM (1UL << 12)"
assert s.count(old) == 1, "桩①：没找到常量"
s = s.replace(old, new, 1)'
must_red "桩①（i8mm 位常量写错 -> 静默选慢变体）" "真源 HWCAP2_I8MM 是 bit 13"
reset_src

# ── 2. 桩②：dotprod 来源写反（读 AT_HWCAP2）───────────────────────────────
patch "$CF" '
old = "out[0] = (h1 & HWCAP_ASIMDDP) ? 1L : 0L;"
new = "out[0] = (h2 & HWCAP_ASIMDDP) ? 1L : 0L;"
assert s.count(old) == 1, "桩②：没找到取值行"
s = s.replace(old, new, 1)'
must_red "桩②（dotprod 来源写反）" "dotprod 取自 AT_HWCAP（h1）"
reset_src

# ── 3. 桩③：Kotlin 侧槽位对调 ────────────────────────────────────────────
patch "$LE" '
old = "val dotprod = c != null && c[0] == 1L"
new = "val dotprod = c != null && c[1] == 1L"
assert s.count(old) == 1, "桩③：没找到 dotprod 槽位"
s = s.replace(old, new, 1)'
must_red "桩③（Kotlin 槽位对调 -> 判据整个反过来）" "c[0] 当 dotprod"
reset_src

# ── 4. 桩④：StderrPump 的 sink 裸调（不包 runCatching）────────────────────
patch "$SP" '
old = "if (line.isNotEmpty()) runCatching { sink(line) }"
new = "if (line.isNotEmpty()) sink(line)"
assert s.count(old) == 1, "桩④：没找到 sink 调用"
s = s.replace(old, new, 1)'
must_red "桩④（sink 裸调 -> 抛异常即停排空 -> native 卡死）" "sink 调用被 runCatching 包住"
reset_src

# ── 5. 全部还原后回绿 ──────────────────────────────────────────────────────
if run_guard; then ck "全部还原后：守卫回绿（桩没有残留污染）" 1
else ck "全部还原后：守卫回绿" 0; fi

echo
echo "=== cpu_feat / StderrPump 守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
