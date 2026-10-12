#!/bin/sh
# 自测 `tools/run_fgs_type_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是三个自欺模式（本仓库各栽过一次，所以三个都钉）
# ═══════════════════════════════════════════════════════════════════════════
#   1. **恒真**：pattern 写成到处都有的文本（"manifest 里有 specialUse"），
#      把声明删掉照样 PASS。本守卫的本体不是"出现过 specialUse"，而是
#      "两类型同现 + 用途说明有内容 + 对应权限齐备 + 不把它说成保活开关" ——
#      桩①/②③ 分别钉前两件事，桩④/⑤ 钉后两件事。
#   2. **恒假被静音**：pattern 与源码差一个字符 -> 守卫永远红 -> 有人把
#      `exit 1` 改成 `|| true`，那时它彻底没用了。每条桩都验"变红且点名"。
#   3. **判据锚到注释上**：本族最容易踩的一条。manifest 的 `<service>` 注释里
#      **必须**写着 oom_score_adj / MemFree（F-4 就靠它们），若不剥注释，
#      "不许出现"那几条会在完全正确的源码上假红。桩⑥ 专钉这个差别：
#      把禁用项**写进注释**不得变红，**写进代码**必须变红。
# 做法：就地改真源码（每个桩只改一处锚点）-> 断言守卫**变红并点名** -> 还原。
# 桩全部来自真实会发生的旧写法/坏写法，不是编出来的反例。
#
# 纯 sh + python3 标准库，不依赖工具链。运行：sh tools/run_fgs_type_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_fgs_type_guard.sh
MAN=app/src/main/AndroidManifest.xml
README=README.md
ISVC=app/src/main/java/com/xiaowan/localinference/InferenceService.kt
GRADLE=app/build.gradle.kts

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
for f in "$MAN" "$README" "$ISVC" "$GRADLE"; do cp "$f" "$TMP/$(basename "$f").bak"; done
restore() { for f in "$MAN" "$README" "$ISVC" "$GRADLE"; do cp "$TMP/$(basename "$f").bak" "$f"; done; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { for f in "$MAN" "$README" "$ISVC" "$GRADLE"; do cp "$TMP/$(basename "$f").bak" "$f"; done; }

# must_red "<桩名>" "<期望被点名的那条断言>"
must_red() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    if [ "$rc" = "0" ]; then
        echo "FAIL  $1：守卫没变红（该断言失效了）"; bad=$((bad+1)); return
    fi
    if grep -qF "$2" "$TMP/guard.out"; then
        echo "PASS  $1：守卫变红，且指到了「$2」"; ok=$((ok+1))
    else
        echo "FAIL  $1：守卫红了，但没指到「$2」"; bad=$((bad+1))
    fi
}

# ── 0) 基线：未打桩时必须全绿 ─────────────────────────────────────────────
set +e
sh "$GUARD" > "$TMP/base.out" 2>&1
base=$?
set -e
if [ "$base" = "0" ]; then echo "PASS  基线（未打桩）全绿"; ok=$((ok+1));
else echo "FAIL  基线（未打桩）就红了："; cat "$TMP/base.out"; bad=$((bad+1)); fi

# ── 桩①：F-3 本体 —— 为了"只看 specialUse"把 dataSync 抹掉 ────────────────
"$PY" - $MAN <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'android:foregroundServiceType="dataSync|specialUse"'
new = 'android:foregroundServiceType="specialUse"'
assert s.count(old) == 1, "stub1: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩①（抹掉 dataSync，只留 specialUse）" "不能只剩 specialUse"
reset_src

# ── 桩②：F-1 本体 —— specialUse 声明了却不写用途说明 ──────────────────────
"$PY" - $MAN <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
i = s.index("PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
j = s.index("/>", i) + 2
open(p, "w", encoding="utf-8").write(s[:i - len('android:name="') - 40] + s[j:])
PYEOF
must_red "桩②（specialUse 没有用途说明 property）" "PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
reset_src

# ── 桩③：F-2 本体 —— 少了 specialUse 对应的权限 ───────────────────────────
"$PY" - $MAN <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />\n'
assert s.count(old) == 1, "stub3: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩③（少了 FOREGROUND_SERVICE_SPECIAL_USE）" "缺了它 API 34+ 不认该类型"
reset_src

# ── 桩④：F-1 的反面 —— 用途说明写成一句空话 ──────────────────────────────
"$PY" - $MAN <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r'(android:value=")[^"]*(" />)', s)
assert m, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s[:m.start(1)] + 'android:value="keep alive"' + s[m.end(2) - len('" />') + 0:])
PYEOF
must_red "桩④（用途说明写成空话 \"keep alive\"）" "不是 \"keep alive\" 之类的空话"
reset_src

# ── 桩⑤：F-4 本体 —— 注释把它说成"保活开关" ──────────────────────────────
"$PY" - $MAN <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r'不是.不再被冻.', s)
assert m, "stub5: anchor not found"
open(p, "w", encoding="utf-8").write(s[:m.start()] + '加上它系统就不再被冻、保证存活' + s[m.end():])
PYEOF
must_red "桩⑤（说明把它说成保活/保证存活）" "每一处承诺词都被"
reset_src

# ── 桩⑥：判据锚到注释上的对照（本族最容易踩的那条）───────────────────────
# 只把禁用项**写进注释** —— 这是正确写法（注释本来就在解释它），守卫**不得**变红。
"$PY" - $MAN <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "    <uses-permission android:name=\"android.permission.INTERNET\" />"
assert s.count(old) == 1, "stub6: anchor not found"
new = ("    <!-- 排障备注：这里不允许出现 PARTIAL_WAKE_LOCK 与 oom_score_adj 之类的字样 -->\n"
       + old)
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
set +e
sh "$GUARD" > "$TMP/g6.out" 2>&1; rc6=$?
set -e
if [ "$rc6" = "0" ]; then
    echo "PASS  桩⑥（把禁用项写进注释）：守卫全绿（判据没锚到注释上）"; ok=$((ok+1))
else
    echo "FAIL  桩⑥：注释里的字样让守卫假红了 ——"; grep '^FAIL' "$TMP/g6.out" || true; bad=$((bad+1))
fi
reset_src

# ── 桩⑦：偷偷把通知提到 IMPORTANCE_HIGH（换不到豁免还弹横幅）─────────────
"$PY" - $ISVC <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "NotificationManager.IMPORTANCE_DEFAULT)"
assert s.count(old) == 1, "stub7: anchor not found"
new = "NotificationManager.IMPORTANCE_HIGH)"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑦（通知渠道偷偷提到 IMPORTANCE_HIGH）" "没把通知提到 IMPORTANCE_HIGH"
reset_src

# ── 桩⑧：为了这个类型去抬 targetSdk（会牵动 FGS 类型与 POST_NOTIFICATIONS）─
"$PY" - "$GRADLE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "        targetSdk = 28"
assert s.count(old) == 1, "stub8: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "        targetSdk = 34", 1))
PYEOF
must_red "桩⑧（顺手把 targetSdk 抬到 34）" "targetSdk 仍是 28"
reset_src

# ── 桩⑨：README 只写"加了 specialUse"却不点明它不改 oom_score_adj ─────────
"$PY" - $README <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r"- `oom_score_adj` 由.*?无关", s)
assert m, "stub9: anchor not found"
open(p, "w", encoding="utf-8").write(s[:m.start()] + "- 类型位与进程优先级无关" + s[m.end():])
PYEOF
must_red "桩⑨（README 不点明 oom_score_adj 由什么决定）" "点明 oom_score_adj 由"
reset_src

# ── 收尾：还原后再跑一次基线，确认自测没污染源码 ─────────────────────────
set +e
sh "$GUARD" > "$TMP/final.out" 2>&1; fin=$?
set -e
DIRTY=0
for f in "$MAN" "$README" "$ISVC" "$GRADLE"; do
    cmp -s "$TMP/$(basename "$f").bak" "$f" || DIRTY=1
done
if [ "$fin" != "0" ]; then
    echo "FAIL  收尾：源码还原后守卫仍红"; cat "$TMP/final.out"; bad=$((bad+1))
elif [ "$DIRTY" != "0" ]; then
    echo "FAIL  自测污染了源码（未还原干净）"; bad=$((bad+1))
else
    echo "PASS  收尾（源码已还原，守卫仍全绿）"; ok=$((ok+1))
fi

echo ""
if [ "$bad" = "0" ]; then echo "=== FGS 类型守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== FGS 类型守卫自测：PASS $ok / FAIL $bad ==="; exit 1
