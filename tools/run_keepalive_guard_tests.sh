#!/bin/sh
# 自测 `tools/run_keepalive_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是三个自欺模式（本仓库各栽过一次，所以三个都钉）
# ═══════════════════════════════════════════════════════════════════════════
#   1. **恒真**：pattern 写成到处都有的文本（"有 AccessibilityService"），
#      把功能删掉照样 PASS。本守卫的本体不是"服务存在"，而是
#      "系统侧声明齐备 + 形态只看有没有活着 + 三态没被折成两态" ——
#      桩①/②③/④ 分别钉这三件事。
#   2. **恒假被静音**：pattern 与源码差一个字符 -> 守卫永远红 -> 有人把
#      `exit 1` 改成 `|| true`，那时它彻底没用了。每条桩都验"变红且点名"。
#   3. **判据锚到注释上**：本守卫第一版就踩了这条 —— xml 的注释里写着
#      flagRetrieveInteractiveWindows 等字样（注释本身就在解释"禁止加它"），
#      于是"不许出现"那三条在**完全正确的源码**上报了 3 条假故障。
#      桩⑤ 专钉这个差别：把禁用项**写进注释**不得变红，**写进代码**必须变红。
# 做法：就地改真源码（每个桩只改一处锚点）-> 断言守卫**变红并点名** -> 还原。
# 桩全部来自真实会发生的旧写法/坏写法，不是编出来的反例。
#
# 纯 sh + python3 标准库，不依赖工具链。运行：sh tools/run_keepalive_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_keepalive_guard.sh
KA=app/src/main/java/com/xiaowan/localinference/KeepAlive.kt
SVC=app/src/main/java/com/xiaowan/localinference/KeepAliveAccessibilityService.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
MAN=app/src/main/AndroidManifest.xml
XML=app/src/main/res/xml/keepalive_accessibility.xml

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
for f in "$KA" "$SVC" "$ACT" "$MAN" "$XML"; do
    cp "$f" "$TMP/$(basename "$f").bak"
done
restore() {
    for f in "$KA" "$SVC" "$ACT" "$MAN" "$XML"; do cp "$TMP/$(basename "$f").bak" "$f"; done
    rm -rf "$TMP"
}
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { for f in "$KA" "$SVC" "$ACT" "$MAN" "$XML"; do cp "$TMP/$(basename "$f").bak" "$f"; done; }

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

# ── 桩①：K-1 本体 —— 系统侧声明丢了 BIND_ACCESSIBILITY_SERVICE ────────────
# 这是真会发生的写法：manual 里只写 <service> + intent-filter，漏了 permission。
# 后果是系统把这一项从无障碍列表里丢掉，而界面上只会显示"未开启"。
"$PY" - $MAN <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">"""
assert s.count(old) == 1, "stub1: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, ">", 1))
PYEOF
must_red "桩①（丢了 BIND_ACCESSIBILITY_SERVICE）" "声明了 BIND_ACCESSIBILITY_SERVICE"
reset_src

# ── 桩②：K-2 本体 —— 形态退化成"什么都收" ────────────────────────────────
"$PY" - $XML <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'android:accessibilityEventTypes="typeWindowStateChanged"'
new = 'android:accessibilityEventTypes="typeAllMask"'
assert s.count(old) == 1, "stub2: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩②（事件类型退回 typeAllMask）" "绝不许出现 typeAllMask"
reset_src

# ── 桩③：K-2 第二半 —— 开始请求窗口内容 ──────────────────────────────────
"$PY" - $XML <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'android:accessibilityFlags="flagDefault"'
new = 'android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"'
assert s.count(old) == 1, "stub3: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩③（flags 里加上交互窗口）" "不许请求交互窗口"
# 同一根桩顺带验"flags 只留 flagDefault"这条也看得见
must_red "桩③（flags 不再只留 flagDefault）" "的 flags 只留 flagDefault"
reset_src

# ── 桩④：K-4 本体 —— 把"查不到"折成"没开" ───────────────────────────────
"$PY" - $KA <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    } catch (_: Throwable) {
        OnOff.UNKNOWN
    }

    /**
     * 是否已「忽略电池优化」"""
new = """    } catch (_: Throwable) {
        OnOff.OFF
    }

    /**
     * 是否已「忽略电池优化」"""
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩④（无障碍状态把查不到折成未开）" "无障碍状态的 catch 分支回 UNKNOWN"
reset_src

# ── 桩⑤：判据锚到注释上的对照（第一版守卫真踩的那条）─────────────────────
# 只把禁用项**写进注释** —— 这是正确写法（注释本来就在解释它），守卫**不得**变红。
"$PY" - $XML <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "  android:notificationTimeout=\"0\" />"
new = ("  android:notificationTimeout=\"0\" />\n"
       "  <!-- 排障备注：这里不允许出现 flagRetrieveInteractiveWindows 与 typeAllMask -->")
assert s.count(old) == 1, "stub5: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
set +e
sh "$GUARD" > "$TMP/g5.out" 2>&1; rc5=$?
set -e
if [ "$rc5" = "0" ]; then
    echo "PASS  桩⑤（把禁用项写进注释）：守卫全绿（判据没锚到注释上）"; ok=$((ok+1))
else
    echo "FAIL  桩⑤：注释里的字样让守卫假红了 ——"; grep '^FAIL' "$TMP/g5.out" || true; bad=$((bad+1))
fi
reset_src

# ── 桩⑥：代用户开启 —— 源码里去写 ENABLED_ACCESSIBILITY_SERVICES ──────────
"$PY" - $KA <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    /** 打开系统的「无障碍」设置页。 */"""
new = """    /** 反例：代用户开启（写系统设置）—— 等于绕过用户同意，且需要系统级权限。 */
    fun enableSelf(ctx: Context) {
        Settings.Secure.putString(ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, accessibilityFlattened(ctx))
    }

    /** 打开系统的「无障碍」设置页。 */"""
assert s.count(old) == 1, "stub6: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑥（源码里代用户开启无障碍）" "没有任何一处**写**系统设置"
reset_src

# ── 桩⑦：引导降级被砍成一级（只剩申请页，ROM 不认就彻底没有出路）─────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            "优化列表" to KeepAlive.batteryOptimizationListIntent(),
            "应用详情" to KeepAlive.appDetailsIntent(this),
"""
assert s.count(old) == 1, "stub7: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑦（电池优化跳转只剩一级）" "电池优化跳转是逐级尝试"
reset_src

# ── 桩⑧：状态行不再回读（用户去系统设置改完回来看到的是旧结论）────────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    override fun onResume() {
        super.onResume()
        refreshKeepAlive()
    }
"""
new = """    override fun onResume() {
        super.onResume()
    }
"""
assert s.count(old) == 1, "stub8: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑧（onResume 不再重读状态）" "onResume 会重读状态"
reset_src

# ── 桩⑨：冻结痕迹那一行另发一次请求（进设置页就打自己的服务）──────────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            append("\\n").append(KeepAlive.freezeLine(lastProbeResult))"""
new = """            append("\\n").append(KeepAlive.freezeLine(HealthCheck.probe("127.0.0.1", 8080)))"""
assert s.count(old) == 1, "stub9: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑨（冻结痕迹不再取自缓存的探测结论）" "冻结痕迹取自缓存的那份探测结论"
reset_src

# ── 桩⑩：A-1 本体 —— 把"探测失败"与"缺字段"用 || 并成同一句 ───────────────
# 这正是本轮修掉的那条旧写法：两个分支输出同一句错方向的话，用户会去查
# "服务端字段"，而真因是"服务根本没在跑"。它**能通过**上面那条存在性断言
# （"未报 frozen_ 字段"这句还在），只有锚分支结构的四条抓得住 —— 所以这桩是
# 那四条判据的"非恒真"证据。
"$PY" - $KA <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        r.transportError?.let { return "冻结痕迹：本次探测失败（$it），冻结痕迹读不到" }
        if (r.frozenCount == null) {"""
new = """        if (r.transportError != null || r.frozenCount == null) {"""
assert s.count(old) == 1, "stub10: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑩（A-1：两件事又用 || 并回一句）" "不再把 transportError 与 frozenCount==null 用 || 并成同一句"
reset_src

# ── 桩⑪：A-2 本体 —— 用了 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 却没声明权限 ──
# 这是真会发生的写法：源码发起该 action，manifest 只写 <application>，
# 漏了配套 uses-permission。后果是静默降级（一级入口恒不可用），不崩不报错。
"$PY" - $MAN <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />\n"""
assert s.count(old) == 1, "stub11: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "", 1))
PYEOF
must_red "桩⑪（A-2：用了电池优化 action 却没声明配套权限）" "声明了 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
must_red "桩⑪（A-2：权限与 action 的成对判据也看得见）" "成对"
reset_src

# ── 桩⑫：A-3a 本体 —— 注释又去描述另一套实现（getServiceInfo）────────────
"$PY" - $KA <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """     * 且返回的是"系统里装了什么"而不是"本应用声明了什么"）：改用带 `setPackage` 的"""
new = """     * 且返回的是"系统里装了什么"而不是"本应用声明了什么"）：改用 getServiceInfo(cn, 0)，"""
assert s.count(old) == 1, "stub12: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑫（A-3a：注释描述的是另一套实现）" "注释描述的是"
reset_src

# ── 桩⑬：这一组被"顺手"搬到日志/调试页（同族的崩溃取证确实搬过去了）─────
# 这正是本轮改动的**副作用风险**：搬控件的人看到"排障工具都归到那一页"很容易
# 连保活一起搬。后果不是崩溃，而是用户"去系统设置改完权限回来"要在一个滚动
# 位置的日志页里找那一行读数 —— 而这一组的全部价值就是那行读数。
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        pageSet.addView(label("── 后台保活（改的是系统权限，不是本应用参数）──"))
        keepAliveTv = label(keepAliveText()).apply { setTextColor(0xFF666666.toInt()) }
        addFull(keepAliveTv)"""
new = """        pageLog.addView(label("── 后台保活（改的是系统权限，不是本应用参数）──"))
        keepAliveTv = label(keepAliveText()).apply { setTextColor(0xFF666666.toInt()) }
        pageLog.addView(keepAliveTv)"""
assert s.count(old) == 1, "stub13: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩⑬（后台保活整组被搬到日志页）" "在同一页"
reset_src

# ── 收尾：还原后再跑一次基线，确认自测没污染源码 ─────────────────────────
set +e
sh "$GUARD" > "$TMP/final.out" 2>&1; fin=$?
set -e
DIRTY=0
for f in "$KA" "$SVC" "$ACT" "$MAN" "$XML"; do
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
if [ "$bad" = "0" ]; then echo "=== 后台保活守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 后台保活守卫自测：PASS $ok / FAIL $bad ==="; exit 1
