#!/bin/sh
# 自测 `tools/run_dark_mode_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 `grep` / `awk` / `python` 形式的**结构性**断言，它有三个
# 自欺模式（本仓库各栽过一次，所以三个都钉）：
#
#   1. **恒真**：pattern 写成到处都有的文本（例如只 grep `setTheme`，
#      而它只要出现就算过），于是把"排在 super.onCreate 之后"这个故障放过去；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快就有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过 pal"，而不是"**取色点**走的是它"
#      —— 前者在派生属性声明处就恒真。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：把源码临时改成故障版本，守卫必须变红、且必须指出是哪一条；
# 再改回来，必须恢复全绿。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_chat_bubble_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_dark_mode_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_dark_mode_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
TP=$SRC/ThemePref.kt
PAL=$SRC/ThemePalette.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$TP" "$TMP/tp.bak"; cp "$PAL" "$TMP/pal.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/tp.bak" "$TP"; cp "$TMP/pal.bak" "$PAL"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/tp.bak" "$TP"; cp "$TMP/pal.bak" "$PAL"; }

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

# ── 1. 桩①：setTheme 挪到 super.onCreate() **之后**（页面还是亮的，只有对话框暗了）
# 两行换个位置，编译过、点得动、不报错 —— 正是"看着做了、其实晚了一步"。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
theme = """        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar
                 else android.R.style.Theme_Material_Light_NoActionBar)
"""
super_ = "        super.onCreate(savedInstanceState)\n"
assert s.count(theme) == 1 and s.count(super_) == 1, "桩①：没找到 setTheme / super 那两行"
s = s.replace(theme, "", 1).replace(super_, super_ + theme, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩①（setTheme 排在 super 之后）" "setTheme 排在 super.onCreate() 之前"
reset_src

# ── 2. 桩②：取色点又写回去一个字面量（暗色下那一处看不见）────────────────
# 这是本轮修的那一整族（"漏一处 = 某一小块在暗色下读不清"）的复现件。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            textSize = 13f; setTextColor(pal.accent)"
new = "            textSize = 13f; setTextColor(0xFF3D5AFE.toInt())"
assert s.count(old) == 1, "桩②：没找到会话标题那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（又写回去一个字面量）" "取色点里不再有硬编码的 8 位色值字面量"
reset_src

# ── 3. 桩③：外壳压色**只调一次**（第一帧闪白：暗色用户每次冷启动先看到白底）──
# 这一桩的失效是"第一帧的颜色"——只在**冷启动的那一帧**出现，
# 截图、单测、跑起来之后再看全都看不见（那正是它要防的）。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        applyThemeChrome()
        setContentView(outer)"""
new = """        setContentView(outer)"""
assert s.count(old) == 1, "桩③：没找到 setContentView 之前那一次压色"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（第一帧不压色：闪白）" "外壳压色**调用了两次"
reset_src

# ── 4. 桩④：暗色那一列**少给一个字段**（漏一处的失效是"那一小块在暗色下"）──
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        aiBubbleText = 0xFFE8EAED.toInt())\n"
assert s.count(old) == 1, "桩④：没找到暗色那一列的末尾"
s = s.replace(old, "        chipOffText = 0xFFBDC1C6.toInt())\n", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩④（暗色那一列漏/重一个字段）" "暗色那一列**逐字段**给全了"
reset_src

# ── 5. 桩⑤：单选组建好了但**没挂到设置页**（用户根本找不到 = 与没做同形）──
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        addFull(darkModeGroup!!)\n"
assert s.count(old) == 1, "桩⑤：没找到挂载那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
# ⚠ 期望命中串要**逐字**取自守卫里的判据描述（多一个字就变成"守卫里没这条判据"），
#   而"守卫里没这条判据"这条自测本身会因为拼错而红 —— 那正是它该做的。
must_red "桩⑤（建了单选组但没挂进设置页）" "单选组必须**加进设置页**"
reset_src

# ── 6. 桩⑥：改档位只改内存、不落盘（下次打开又回默认）──────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            ThemePref.set(this, mode)\n"
assert s.count(old) == 1, "桩⑥：没找到落盘那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩⑥（改档位不落盘）" "点击后真的落盘"
reset_src

# ── 7. 桩⑦：onConfigurationChanged 无条件重建（另两档也白闪 + 丢正文）────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (ThemePref.followsSystem(ThemePref.get(this))) recreate()"
new = "        recreate()"
assert s.count(old) == 1, "桩⑦：没找到重建的闸门"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（无条件重建）" "只在「跟随系统」这一档重建"
reset_src

# ── 8. 桩⑧：「还原默认设置」把默认值**写进键里**（键还在 = 读数说用户设过）──
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        ThemePref.clear(this)\n"
new = "        ThemePref.set(this, ThemePref.DEFAULT)\n"
assert s.count(old) == 1, "桩⑧：没找到还原时那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（还原写默认值而不是删键）" "还原时**删键**"
reset_src

# ── 9. 桩⑨：ThemePref 引入 `import android`（宿主单测就编不过了）──────────
# 这一桩的失效是**整份行为测试形同虚设**：脚本会 SKIP，而 SKIP 在 CI 里
# 与"通过"长得很像（本仓库对"没跑的测试"判据一向是硬的）。
"$PY" - "$TP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "import android.content.Context\n"
new = "import android.content.Context\nimport android.graphics.Color\n"
assert s.count(old) == 1, "桩⑨：没找到 import 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（纯逻辑层引入 import android）" "的 import 里只剩 Context 一处"
reset_src

# ── 10. 桩⑩：`clear` 被改成"把默认值写进键里"（isSet 仍为 true）──────────
# 这一条**守卫抓不到**（它只钉"调用点存在"），必须由行为测试抓 ——
# 所以这里不断言守卫变红，而是**断言行为测试会红**：
# `ThemePref.clear` 的语义是"键消失"，改成 putInt 之后 `isSet` 恒为 true，
# 而那正是"我明明还原了、读数却说我没还原"。
# ⚠ 这一条是**故意留下的缺口**：判据网必须知道"哪条坏法不归我管"，
#   否则下一轮有人会以为守卫绿了就等于这条也守住了。
# 它不在这里执行（要工具链），只登记在注释里，由
# `tools/dark_mode/ThemePrefTest.kt` 的 `clear` 用例覆盖。

# ── 11. 收尾：还原后必须恢复全绿 ──────────────────────────────────────────
if run_guard; then ck "收尾：还原源码后守卫恢复全绿" 1
else ck "收尾：还原源码后守卫恢复全绿（实际红了 -> 有桩没还原干净）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 暗色模式守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 暗色模式守卫自测：PASS $ok / FAIL $bad ==="; exit 1
