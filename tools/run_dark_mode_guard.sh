#!/bin/sh
# 「暗色模式」的接线守卫（需求：默认关闭，可选跟随系统）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这一族必须靠结构判据
# ═══════════════════════════════════════════════════════════════════════════
# 本仓库的界面是**全代码装配**的（没有一个 XML 布局），颜色是逐处
# `setTextColor(0xFF...)` 硬编码进去的。于是暗色有三个都必须成立、
# 且**失效时都不报错**的落点：
#
#   A. `setTheme()` 必须早于 `super.onCreate()` —— 放到之后调用只会改到
#      "还没建的下一批视图"，表现是"页面还是亮的，只有对话框暗了"。
#      两行换个位置，编译过、点得动、不报错。
#   B. 取色必须**只有一个来源**（`pal`）。漏一处 = 某一小块在暗色下看不见，
#      而那处**只在用户切到暗色时才出现** —— 冷启动、截图、单测全都看不到。
#      这一条尤其要防"顺手又写回去一个字面量"：它不改变任何行为，只在
#      暗色下变成一行读不清的字。
#   C. 档位必须真的落到设定页（有控件、有三档、点的结果落盘）——
#      "档位存在、但没有入口"与"没做这个功能"在用户侧完全同形
#      （本仓库 mmap / 嵌入模型那两轮都栽在"调用方为 0"）。
#
# 判据一律锚**结构关系**（顺序 / 取值来源 / 调用关系），不锚存在性：
# "文件里出现过 ThemePref" 对上面三条全部恒真。
#
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_dark_mode_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
TP=$SRC/ThemePref.kt
PAL=$SRC/ThemePalette.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取一个 Kotlin 函数的函数体（从签名行到顶格 `}`），与 run_log_rotation_guard.sh
# 的 ktfn 同一判据（两处各写一份取函数体的近似就是"判据漂移"的起点）。
ktfn() { awk -v f="$2" 'index($0, f) > 0 {inb=1} inb {print} inb && /^    }$/ {exit}' "$1"; }

# 剥注释行与行内 `//...`（本文件里判据文本也在注释中出现，不剥会得到恒真/假红）。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*(\*|/\*|//)' ; }

c "三个文件都在（判据不会因改名而恒真）" \
  "[ -f \$ACT ] && [ -f \$TP ] && [ -f \$PAL ]"

# ── 0) 纯逻辑层不得 import android（否则宿主单测编不过 = 那份测试形同虚设）──
# 判据是"没有 `import android` 这一行"，不是"文件里没出现过 android" ——
# `systemDark` 那一处必须读 `Configuration`，它走**全限定名**引用，
# 不引入 import。所以这两件事不冲突。
# ⚠ 判据是"没有 `import android...` 这一行"，不是"文件里不出现 android"
#   —— `systemDark` 必须读 `Configuration`，它走**全限定名**引用，
#   所以判据还得再钉一条"整个文件里只有那一处碰 android"，
#   否则后来人随手加一个 `import android.graphics.Color` 也照样绿。
c "ThemePref.kt 的 import 里只剩 Context 一处（别的一律全限定名引用）" \
  "[ \$(nocomment < \$TP | grep -c '^import android\\.') -eq 1 ] && \
   nocomment < \$TP | grep -q '^import android\\.content\\.Context\$'"
c "ThemePref.kt 里碰 android 的**只有** systemDark 那两行（多一处就要重新评估纯逻辑前提）" \
  "[ \$(nocomment < \$TP | grep -c 'android\\.content\\.res') -eq 2 ]"
c "ThemePalette.kt 同样零 import（它是 data class，连 android 的类型都不用）" \
  "! grep -q '^import ' \$PAL"
c "判据不是恒真：ThemePalette 确实定义了配色字段（剥注释后仍在）" \
  "nocomment < \$PAL | grep -q 'val background: Int' && \
   nocomment < \$PAL | grep -q 'val aiBubbleText: Int'"

# ── A) setTheme 必须在 super.onCreate() 之前 ───────────────────────────────
# 判据锚**顺序**（谁在谁之前），不锚"文件里出现过 setTheme"。
c "setTheme 排在 super.onCreate() 之前（放到之后 = 页面还是亮的，只有对话框暗了）" \
  "\$PY - \$ACT <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
i = src.index('override fun onCreate(savedInstanceState: Bundle?)')
j = src.index('\n    }\n', i)
body = '\\n'.join(l.split('//')[0] for l in src[i:j].splitlines())
t = body.find('setTheme(')
s = body.find('super.onCreate(')
sys.exit(0 if t >= 0 and s > t else 1)
PY"
c "setTheme 两种主题都给了（只给暗的那一支 = 回到亮色时反而是另一个外观）" \
  "grep -q 'Theme_Material_NoActionBar' \$ACT && \
   grep -q 'Theme_Material_Light_NoActionBar' \$ACT"
c "该用哪一支由 ThemePref 的判定决定，不是**再写一遍** if（两处各判一次迟早分叉）" \
  "grep -q 'ThemePref.themeDark(' \$ACT"

# ── B) 取色只有一个来源：pal（防"顺手又写回去一个字面量"）──────────────────
c "pal 由三档与系统深浅**一次性**定下（只在一处赋值）" \
  "[ \$(grep -c 'pal = if (dark) DARK_PALETTE else LIGHT_PALETTE' \$ACT) -eq 1 ]"
c "浅色那一列是**本仓库原本的色值**（逐字搬迁，不改一个字节）" \
  "grep -q 'surface = 0xFFFFFFFF.toInt()' \$ACT && \
   grep -q 'aiBubble = 0xFFF1F3F4.toInt()' \$ACT"
c "浅色那一列**逐字段**给全了（与 ThemePalette 的字段声明逐项对账）" \
  "\$PY tools/dark_mode/check_palette_fields.py \$ACT \$PAL | grep -q 'PASS  LIGHT_PALETTE 逐字段给全了'"
c "暗色那一列**逐字段**给全了（漏一个字段 = 那一处在暗底上读不清）" \
  "\$PY tools/dark_mode/check_palette_fields.py \$ACT \$PAL | grep -q 'PASS  DARK_PALETTE 逐字段给全了'"
c "取色点没有绕开 `pal` 直接引用某一列（那在另一种主题下必然错）" \
  "\$PY tools/dark_mode/check_palette_fields.py \$ACT \$PAL | grep -q 'PASS  取色点没有绕开'"

# 反向判据：`setTextColor` / `setBackgroundColor` / `setColor` 的实参里
# **不得**再出现 `0xFF......` 字面量。
# 只放行一种：全透明的那个色值（它不随主题变，本来就是"没有底色"）；
#            ② 配色表本身那两段定义（在那里出现才是对的）。
# 剥注释后再判：注释里正引用着"旧写法长什么样"（本文件通篇如此），不剥会假红。
c "取色点里不再有硬编码的 8 位色值字面量（漏一处 = 暗色下那处看不见）" \
  "\$PY - \$ACT <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
# 只看两段配色定义之外的部分
head = src.index('private val LIGHT_PALETTE = ThemePalette(')
tail = src.index('private lateinit var pal: ThemePalette')
body = src[:head] + src[tail:]
body = re.sub(r'//[^\\n]*', '', body)
pat = re.compile(r'set(?:Text|Background)?Color\\(([^)]*)\\)')
# ⚠ 全透明的那个色值要放行：它不随主题变（"没有底色"这件事在暗色下
#   也是"没有底色"）。把它算成硬编码会逼着后来人给一个**假的**暗色值去凑绿。
bad = [m.group(0) for m in pat.finditer(body)
       if re.search(r'0x(?!00000000)[0-9A-Fa-f]{8}', m.group(1))]
if bad:
    print('   违规：' + '; '.join(bad[:5]))
sys.exit(1 if bad else 0)
PY"

# pal 必须**每个语义字段都被用到**（定义了一列却没人取 = 那处还是硬编码）。
c "配色表的每个语义字段都真的被取用（定义了没人用 = 那处还是写死的）" \
  "\$PY tools/dark_mode/check_palette_fields.py --used \$ACT \$PAL"

# 外壳三件（根滚动区 / tab 行 / 输入条与分隔线）必须真的压色，
# 且**压两次**：一次在 setContentView 之前（第一帧不闪白）、一次在三个控件
# 建好之后（那时才真的落色）。
c "外壳压色抽成了唯一出口 applyThemeChrome" \
  "[ \$(grep -c 'private fun applyThemeChrome()' \$ACT) -eq 1 ]"
c "根滚动区 / tab 行 / 输入条都在它里面被压色（缺一个就有一块还是亮的）" \
  "ktfn \$ACT 'private fun applyThemeChrome()' | grep -q 'rootScroll.setBackgroundColor' && \
   ktfn \$ACT 'private fun applyThemeChrome()' | grep -q 'tabRow.setBackgroundColor' && \
   ktfn \$ACT 'private fun applyThemeChrome()' | grep -q 'chatBar.setBackgroundColor'"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM HUP
ktfn "$ACT" "private fun applyThemeChrome()" > "$TMP/ch.txt"
c "applyThemeChrome 存在（锚点未漂）" "[ -s \"\$TMP/ch.txt\" ]"
c "它压的是 pal 的值，不是字面量" \
  "cat \"\$TMP/ch.txt\" | grep -q 'pal.background' && \
   cat \"\$TMP/ch.txt\" | nocomment | grep -q 'pal.divider'"
# 计到 3 次是"定义 1 次 + 调用 2 次"（`grep -c` 数的是行数，定义那一行也含这个名字）。
# 判据钉的是**行为**（两次调用一次都不能少），不是"有人调过它"。
c "外壳压色**调用了两次**（少一次 = 第一帧闪白，或外壳根本没落色）" \
  "[ \$(nocomment < \$ACT | grep -c 'applyThemeChrome()') -eq 3 ]"
# 这两处的**行序**也要钉：排在 setContentView 之前的那一次是"第一帧不闪白"
# 的唯一来源；只在之后调 = 冷启动先闪一下白底（暗色用户每次都看到）。
c "其中一处在 setContentView 之前（只在之后调 = 第一帧闪白）" \
  "\$PY - \$ACT <<'PY'
import sys
src = open(sys.argv[1], encoding='utf-8').read()
i = src.index('private fun applyThemeChrome()')
call = src.index('applyThemeChrome()', i + 10)
cv = src.index('setContentView(outer)')
sys.exit(0 if call < cv else 1)
PY"

# ── C) 档位必须真的接到设定页 ──────────────────────────────────────────────
c "有建三档单选组的函数（不是一个留空即默认的输入框）" \
  "[ \$(grep -c 'private fun darkModeRadioGroup()' \$ACT) -eq 1 ]"
ktfn "$ACT" "private fun darkModeRadioGroup()" > "$TMP/dmg.txt"
c "darkModeRadioGroup 存在（锚点未漂）" "[ -s \"\$TMP/dmg.txt\" ]"
c "三档由 ThemePref.MODES 铺开（不是这里再写三个字面量）" \
  "cat \"\$TMP/dmg.txt\" | grep -q 'ThemePref.MODES.forEach'"
c "档位值走 tag（**不**靠下标反推：改了 MODES 顺序就会静默错位）" \
  "cat \"\$TMP/dmg.txt\" | grep -q 'tag = mode' && \
   cat \"\$TMP/dmg.txt\" | grep -q 'tag as? Int'"
c "点击后真的落盘（只改内存 = 下次打开又回默认，用户以为没生效）" \
  "cat \"\$TMP/dmg.txt\" | grep -q 'ThemePref.set(this, mode)'"
c "点击后说清了**代价**（本屏不会立刻变，要重开）" \
  "cat \"\$TMP/dmg.txt\" | grep -q '重启应用后生效\|下次打开应用时生效'"
c "单选组必须**加进设置页**（建了不挂 = 用户根本找不到，与没做同形）" \
  "grep -q 'darkModeGroup = darkModeRadioGroup()' \$ACT && \
   grep -q 'addFull(darkModeGroup!!)' \$ACT"
# 纵向 RadioGroup 里用 `lp(0, 1)` 会把按钮拉成整屏高（模块 M5 的真机现场）。
c "三档按钮用的是 MATCH_PARENT（不是 lp(0,1)：纵向组里 weight 分的是**高度**）" \
  "cat \"\$TMP/dmg.txt\" | grep -q 'ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT'"

# ── D) 「跟随系统」必须真的会跟着系统变 ────────────────────────────────────
c "有 onConfigurationChanged 回调（否则跟随系统只在冷启动那一次对）" \
  "grep -q 'override fun onConfigurationChanged(' \$ACT"
awk '/override fun onConfigurationChanged/{inb=1} inb{print} inb && /^    }$/{exit}' "$ACT" > "$TMP/cr.txt"
c "onConfigurationChanged 存在（锚点未漂）" "[ -s \"\$TMP/cr.txt\" ]"
# ⚠ 用**剥注释后**的两行相邻判据，而不是"整段里含某个字符串"：
#   后者对"只在某一档重建"这件事**恒真**（把 if 改成无条件 recreate 也照样绿）。
c "它只在「跟随系统」这一档重建（另两档重建 = 白闪 + 丢正文）" \
  "cat \"\$TMP/cr.txt\" | nocomment | grep -A 1 'ThemePref.followsSystem(ThemePref.get(this))' | grep -q 'recreate()'"
c "重建用的是 recreate()（走同一份 onCreate -> 三处落值天然一致）" \
  "cat \"\$TMP/cr.txt\" | grep -q 'recreate()'"

# ── E) 「还原默认设置」必须**真的**回到默认（而不是把默认值写进键里）──────
ktfn "$ACT" "private fun restoreDefaultParams()" > "$TMP/reset.txt"
c "restoreDefaultParams 存在（锚点未漂）" "[ -s \"\$TMP/reset.txt\" ]"
c "还原时**删键**（写 OFF 进键里 = 键还在 = 读数仍说"用户设过"）" \
  "cat \"\$TMP/reset.txt\" | grep -q 'ThemePref.clear(this)'"
c "还原时把单选组回勾到默认档（不回勾 = 显示与真相不符）" \
  "cat \"\$TMP/reset.txt\" | grep -q 'darkModeGroup?.check(darkModeDefaultId)'"

# ── F) 判据网自身：不得只锚「关键词出现过」────────────────────────────────
c "本守卫剥了注释（否则『注释里提到某个字面量』会误判）" \
  "grep -q 'nocomment()' tools/run_dark_mode_guard.sh"
c "本守卫对 setTheme 的判据是**行序**而非存在性（防桩：晚一步调用）" \
  "grep -q 'body.find(.setTheme(.)' tools/run_dark_mode_guard.sh"
c "本守卫有一条**逐字段**对账（两列配色漏字段）" \
  "grep -q 'check_palette_fields.py' tools/run_dark_mode_guard.sh"

echo ""
if [ "$bad" -eq 0 ]; then
    echo "=== 暗色模式接线守卫 全部通过（$ok 条）==="
    exit 0
fi
echo "=== 暗色模式接线守卫 $bad 条失败（共 $ok 条）==="
exit 1
