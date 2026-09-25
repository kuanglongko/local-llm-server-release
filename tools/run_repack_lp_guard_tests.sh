#!/bin/sh
# run_repack_lp_guard.sh 的自测：**每条判据都要能红**，且红在真的坏形态上。
#
# 为什么必须有这一条：这条缺陷的失效形态是"装到手机上才发现" —— 静态守卫
# 全绿与"真的修好了"同形。更糟的是这一族的假绿特别多：
#   · 「repack 单选组存在」恒真（它一直在，正是它显示成空白）；
#   · 「repackRadioGroup() 里有 addView」恒真（问题就在那句的参数上）；
#   · 「文件里出现过 MATCH_PARENT」恒真（全文件到处都是）。
# 所以桩全部取自**本轮真出现过的写法**与它最像的近亲：
#   · 退回 `g.addView(rb, lp(0, 1))`（0.9.127 原样，就是真机上那三片空白）；
#   · 换成 `lp(MATCH_PARENT, 0)` —— 看起来"用上 MATCH_PARENT 了"，其实
#     `if (w == 0)` 之外那一支给的是 WRAP_CONTENT，宽度仍然不是满宽（假绿）；
#   · 只在别的组（NPU 配额）上写对、repack 组没动（判归属必须逮住）；
#   · 把全文件横向行一起改掉（反向锚要逮住：不该动的别动）。
#
# 运行：sh tools/run_repack_lp_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
KT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
chk() {
    name="$1"; want="$2"
    if sh tools/run_repack_lp_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=/tmp/repack_lp_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$KT" "$STASH/EngineActivity.kt"
restore() { cp "$STASH/EngineActivity.kt" "$KT"; }
trap restore EXIT

stub() {
    name="$1"; want="$2"; prog="$3"
    cp "$STASH/EngineActivity.kt" "$KT"
    printf '%s\n' "$prog" | python3 - "$KT" || {
        # 桩的 assert 失败 = 这个桩根本没打上。**必须判红**，
        # 否则脚本退出非 0、文件还是原样，守卫自然绿 —— 桩静默变成"什么都没改"。
        # （本脚本第一版正是这样：stub ① 的锚带尾换行、而 GOOD 以单引号结尾不带，
        #   assert 抛错被 here-doc 吞掉，连"退回坏写法"这条都判成绿。）
        echo "FAIL  $name：桩没打上（python 退出非 0，源码未被改动）"
        bad=$((bad+1))
        return 0
    }
    chk "$name" "$want"
}

GOOD='            g.addView(rb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))'

# ① 0.9.127 原样回来：纵向组里用横向行的 lp(0, 1) → 三片空白
stub "退回 g.addView(rb, lp(0, 1))（真机上那三片空白）" red "
import sys, io
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
old = '''$GOOD'''
assert old in s, '源码形状变了，本自测要一起更新'
s = s.replace(old, '            g.addView(rb, lp(0, 1))', 1)
io.open(p, 'w', encoding='utf-8').write(s)
"

# ② 假绿形态：用上 MATCH_PARENT 了，但塞的是 lp() 的**宽度**位 ——
#    而 lp() 的宽度位只认 0，非 0 一律给 WRAP_CONTENT，宽度根本没满。
stub "假绿：g.addView(rb, lp(MATCH_PARENT, 0))（宽度位传 MATCH_PARENT 其实被丢掉）" red "
import sys, io
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
old = '''$GOOD'''
assert old in s
s = s.replace(old, '            g.addView(rb, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0))', 1)
io.open(p, 'w', encoding='utf-8').write(s)
"

# ③ 高仍交给 weight（宽度对了、高度还在吃满剩余空间）→ 还是大片空白
stub "只修宽、高仍用 weight（lp 形态）" red "
import sys, io
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
old = '''$GOOD'''
assert old in s
s = s.replace(old, '''            g.addView(rb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))''', 1)
io.open(p, 'w', encoding='utf-8').write(s)
"

# ④ 判归属：把**别的**纵向组（NPU 配额）改成正确写法，repack 组仍退回坏写法
stub "只在 NPU 配额组上写对、repack 组退回坏写法（判归属必须逮住）" red "
import sys, io
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
old = '''$GOOD'''
assert old in s
s = s.replace(old, '            g.addView(rb, lp(0, 1))', 1)
s = s.replace('            quotaGroup.addView(rb, LinearLayout.LayoutParams(-1, -2))',
              '            quotaGroup.addView(rb, LinearLayout.LayoutParams(-1, -2)); // 已修，样板在此', 1)
io.open(p, 'w', encoding='utf-8').write(s)
"

# ⑤ 反向锚：为了把这条修绿，把全文件横向行的 lp(0, 1) 一起改掉
stub "把横向行的 lp(0, 1) 全删掉（不该动的别动）" red "
import sys, io, re
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
s2 = re.sub(r'lp\\(0, 1\\)', 'lp(0, 0)', s)
assert s2 != s, '源码形状变了，本自测要一起更新'
io.open(p, 'w', encoding='utf-8').write(s2)
"

# ⑥ 另开一个纵向容器又用 lp()（同形态的网必须逮住新写的）
stub "新写一个 VERTICAL 容器又拿 lp() 加子项" red "
import sys, io
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
anchor = '        pageSet.addView(label(\"── NPU / Hexagon（HTP，改后需重启 App）──\"))'
assert anchor in s, '源码形状变了，本自测要一起更新'
s = s.replace(anchor,
    '        val newCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }\n'
    '        newCol.addView(android.widget.TextView(this), lp(0, 1))\n' + anchor, 1)
io.open(p, 'w', encoding='utf-8').write(s)
"

# ⑦ 反向：真源码（本轮修复后的写法）必须全绿
stub "修复后的写法（反向对照）" green "
import sys
"

# ⑧ 文案体积：把长文放回面板 → 说明堆成排查手册，必须判红。
stub "把长文提示放回面板（说明又堆成排查手册 —— 长度判据必须逮住）" red "
import sys, io
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
anchor = '        addFull(flashCb); addFull(flashHint)'
assert anchor in s, '源码形状变了，本自测要一起更新'
# ⚠ 桩**不能**用本轮删掉的那段原文：那段 141 字，比 HINT_MAX(160) 短，
#   打上去根本判不了红 —— \"桩没打在洞上 = 判绿\"，正是本文件开头列的那一族。
#   这里用同族的**另一段**长文（也真的存在过）来打：证明判据判的是**体积**
#   （任何一段长文都挡得住），而不是\"只认某几句措辞\"。
long_hint = (
    '        addFull(label(\"提示：上面三档应逐行紧挨排布。若三段之间有大片空白、点上去也没反应，\" +\n'
    '            \"那是控件被撑开了（0.9.127 的 LayoutParams 缺陷，0.9.128 修），换新版即可；\" +\n'
    '            \"若只是选了「关」而日志仍写 use_extra_bufts=1，那才是档位没落到库上。\" +\n'
    '            \"这两件事排查方向不同：前者换版本、后者要查 JNI 传值与落值，别混着查。\" +\n'
    '            \"补一句说明文案的长尾，让本段明确越过字数上限，用于反向打桩。\").apply {\n'
    '            setTextColor(0xFFB26A00.toInt())\n'
    '        })\n')
s = s.replace(anchor, long_hint + anchor, 1)
io.open(p, 'w', encoding='utf-8').write(s)
"

# ⑨ 位置：把 mmap 的说明再挪回 repack 之后（用户点1 报的原始形态）→ 必须判红。
stub "mmap 的说明又挪到 repack 三档之后（用户点1 报的原始形态）" red "
import sys, io
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
row = '        addFull(label(\"mmap=0 为无映射直读（内存占用约翻倍，仅供诊断推理卡顿）\").apply {\n'
assert row in s, '源码形状变了，本自测要一起更新'
i = s.index(row); j = s.index('        })\n', i) + len('        })\n')
block = s[i:j]
s = s[:i] + s[j:]
anchor = '        addFull(flashCb); addFull(flashHint)'
assert anchor in s
s = s.replace(anchor, block + anchor, 1)
io.open(p, 'w', encoding='utf-8').write(s)
"


# ⚠ 汇总行的措辞不能随手改：`tools/count_suite_totals.py` 靠 `（共 N 条` 这个形状
# 取本套件的总数（清单见 tools/suite-counts.json，接线守卫拿它对账）。
# 第一版成功支写成「全部通过（$ok 条）」—— 与它的**内层**驱动的那份守卫逐字同形，
# 计数脚本从后往前找时取到的还是内层那句，于是本份自测的总数恒为 None
# （实测漂移：清单=7 实测=None）。成功支也补上「共 N 条」那一半。
if [ "$bad" -eq 0 ]; then
    echo "=== repack 布局守卫自测 全部通过（共 $ok 条）==="
    exit 0
else
    echo "=== repack 布局守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
