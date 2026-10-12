#!/bin/sh
# 自测 `tools/run_reset_default_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"。守卫判据全是 grep/awk 的结构性断言，
# 有三个自欺模式（本仓库各栽过一次，所以三个都钉）：
#   1. **恒真**：pattern 写成到处都有的文本（本文件里 `AlertDialog.Builder(this)`
#      有十几处，判据写成"有没有弹窗"就是恒真 —— 而问题正是"弹窗弹在事后"）；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过 `confirmResetDefaults`"，
#      而不是"**那个按钮的回调里**只有它" —— 前者对"另加入口 + 老回调原样保留"
#      的桩恒真（两处都在，用户点老按钮照样直接还原），而那正是用户报的那个现象。
#
# 桩全部取自**本按钮真正会写错的写法**（不是编出来的）：
#   · 内联还原体（本轮修掉的那个形状：点一下就还原，弹窗弹在事后）
#   · 只在别处加了确认方法、按钮回调原样保留（用户点老按钮照样直接还原）
#   · 取消也走一遍还原（界面上"取消"看着能退出，而参数已经没了）
#   · 弹窗只说"确定要还原吗"、不讲没有撤销与生效时机（框等于没有）
#   · 覆盖完不落盘（下次打开又回来，用户以为"还原没生效"）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本 —— 与 run_session_cleanup_guard_tests.sh
# 同一套路。不需要工具链，只要 sh + python3。
# 运行：sh tools/run_reset_default_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_reset_default_guard.sh
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"
restore() { cp "$TMP/act.bak" "$ACT"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    # ⚠ 要在**打红的那一行**上找，不能只看"关键词出现在输出里"：
    # 判据名本身会出现在守卫的 PASS 行上（同一句话既是被判的对象、也是判据名），
    # 只看"出现过"就会拿 PASS 行当命中 —— 那是判据网自己假绿，最难发现的那一类。
    line=$(grep -F "$key" "$TMP/guard.out" | grep '^FAIL' | head -1)
    if [ -n "$line" ]; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」（守卫里没这条判据，或它没在这条桩上红）" 0
    fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：内联还原体（本轮修掉的形状，回归桩）─────────────────────────────
# 这一桩**逐字还原**用户报的那个写法之前的样子：按钮点击回调里直接 `setText`。
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        resetBtn.setOnClickListener { confirmResetDefaults() }"
new = """        resetBtn.setOnClickListener {
            ctxEt.setText("4096"); gpuEt.setText("0"); thrEt.setText(LlmEngine.defaultThreads().toString())
            persistParams()
        }"""
assert s.count(old) == 1, "桩①：没找到还原按钮的回调"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩①（内联还原体：点一下就还原）" "点击回调里只调确认方法"
reset_src

# ── 2. 桩②：只加了确认方法，按钮回调原样保留（"两处都在"的假绿）────────────
# 这一桩专打**存在性判据**：`confirmResetDefaults()` 真的存在、也真的建了弹窗，
# 但它接不上那个按钮 —— "文件里有确认框"为真，用户点下去照样直接还原。
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        resetBtn.setOnClickListener { confirmResetDefaults() }"
new = """        resetBtn.setOnClickListener {
            ctxEt.setText("4096"); persistParams()
        }"""
assert s.count(old) == 1, "桩②：没找到还原按钮的回调"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩②（确认框在别处，按钮没接上）" "点击回调里只调确认方法"
reset_src

# ── 3. 桩③：取消也走一遍还原 ───────────────────────────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            .setPositiveButton("还原") { _, _ -> restoreDefaultParams() }
            .setNegativeButton("取消", null)"""
new = """            .setPositiveButton("还原") { _, _ -> restoreDefaultParams() }
            .setNegativeButton("取消") { _, _ -> restoreDefaultParams() }"""
assert s.count(old) == 1, "桩③：没找到确认框的按钮对"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩③（取消也走一遍还原）" "取消 = 什么都不做"
reset_src

# ── 4. 桩④：确认框只说"确定要还原吗"（不讲没有撤销）───────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''            .setMessage("把设置页里所有分组一次性恢复成默认值，" +
                "并重置端口（8080）与局域网开关（关）。\\n\\n" +
                "你自己调过的参数**不会保留**，也不会自动备份 —— 还原只能用默认值重来，" +
                "没有撤销。\\n\\n" +
                "（端口与局域网开关**立即生效**；ctx / gpu / 线程数这些加载类参数" +
                "要重新加载模型才生效，已加载的模型仍按旧参数跑。" +
                "外观那组里的暗色模式同样要重开应用才看得到变化。）")'''
new = '''            .setMessage("确定要还原默认设置吗？")'''
assert s.count(old) == 1, "桩④：没找到确认框的正文"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
# ⚠ 断言的**判据名**要与打红的那一行逐字一致：先写的是"没有撤销"，
# 而这一桩恰恰把"没有撤销"**删掉了** —— 它红在"说清了作用范围"那一条上
# （`grep -q` 与 `&&` 短路：第一条就假，第二条根本不求值）。
# 指向一条没红的判据 = 这条桩证明不了"是本桩引起的红"。
must_red "桩④（确认框不讲后果）" "确认框说清了作用范围"
reset_src

# ── 5. 桩⑤：覆盖完不落盘 ───────────────────────────────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 只删还原体内那一句 persistParams()（确认框里本来就没有它）。
i = s.index("private fun restoreDefaultParams() {")
head, tail = s[:i], s[i:]
old = "        if (repackDefaultId != View.NO_ID) repackGroup?.check(repackDefaultId)\n        persistParams()\n"
new = "        if (repackDefaultId != View.NO_ID) repackGroup?.check(repackDefaultId)\n"
assert tail.count(old) == 1, "桩⑤：没找到还原体里的 persistParams"
open(p, 'w', encoding='utf-8').write(head + tail.replace(old, new, 1))
PY_EOF
must_red "桩⑤（覆盖完不落盘）" "覆盖完要落盘"
reset_src

# ── 6. 桩⑥：确认按钮名写成"确定"（说不清它干什么）─────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '.setPositiveButton("还原") { _, _ -> restoreDefaultParams() }'
new = '.setPositiveButton("确定") { _, _ -> restoreDefaultParams() }'
assert s.count(old) == 1, "桩⑥：没找到确认按钮"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑥（确认按钮名为「确定」）" "确认按钮引用的是**写点**"
reset_src

# ── 7. 桩⑦：确认框里又抄了一份参数覆盖 ─────────────────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            .setPositiveButton("还原") { _, _ -> restoreDefaultParams() }'
new = '''            .setPositiveButton("还原") { _, _ ->
                ctxEt.setText("4096"); persistParams(); restoreDefaultParams()
            }'''
assert s.count(old) == 1, "桩⑦：没找到确认按钮"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑦（确认框里抄了一份覆盖）" "确认框本体里没有参数覆盖"
reset_src

echo
echo "=== 还原默认设置确认框守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
