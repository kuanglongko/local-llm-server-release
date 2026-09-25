#!/usr/bin/env python3
"""tools/repack_lp/scan_vertical_lp.py 的单测：纯 python3，不依赖工具链。

为什么要有这一层：守卫 ④ 的结论完全由这个扫描器给出，而扫描器**自己坏掉**时
的表现与"真的没有误用"完全同形（都打印 `none` / 都 exit 0）。本判据第一版就
这样坏过一次 —— 剥注释的顺序把字符串里的 `//` 与 `/* … */` 串到一起，
整段代码被吃掉，"有误用"被判成"没有"。

所以下面每条都同时钉两面：**该报的必须报**（含"看起来像其实不是"的近亲），
**不该报的必须不报**（横向行、字符串里的同形文本、注释里的同形文本）。
"""
import io
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scan_vertical_lp import offenders, strip_literals_and_comments  # noqa: E402

ok = 0
bad = 0


def case(name, src, want_offenders):
    """want_offenders: 期望报出的**容器名**列表（顺序无关）。"""
    global ok, bad
    got = sorted(c for c, _ in offenders(strip_literals_and_comments(src)))
    want = sorted(want_offenders)
    if got == want:
        print('PASS  %s' % name)
        ok += 1
    else:
        print('FAIL  %s：期望 %s，实得 %s' % (name, want, got))
        bad += 1


# ① 真形态：纵向组 + lp(0, 1) —— 0.9.127 的 repack 三档单选（真机上那三片空白）
case('纵向组 + lp(0, 1)（本轮真形态）', '''
class A {
    private fun f() {
        val g = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val rb = android.widget.RadioButton(this)
        g.addView(rb, lp(0, 1))
    }
}
''', ['g'])

# ② 事后赋 orientation 的写法（另一种常见写法，识别必须覆盖）
case('事后 .orientation = …VERTICAL（第二种写法）', '''
val body = LinearLayout(this)
body.orientation = LinearLayout.VERTICAL
body.addView(tv, lp(0, 1))
''', ['body'])

# ③ 横向行同形文本 —— **不该报**（本文件里 20 多处都这样，误报等于把守卫变成噪声）
case('横向行 + lp(0, 1)：不该报', '''
val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
row.addView(btn, lp(0, 1))
''', [])

# ④ 字符串字面量里的同形文本 —— 不该报（剥字面量的意义）
case('字符串里的同形文本：不该报', '''
val msg = "写成 g.addView(rb, lp(0, 1)) 就会撑高"
g.addView(rb, LinearLayout.LayoutParams(-1, -2))
''', [])

# ⑤ 注释里的同形文本 —— 不该报（本轮修复注释**必然**引用坏写法）
case('注释里的同形文本：不该报', '''
// bad: g.addView(rb, lp(0, 1))  ← 会撑高
g.addView(rb, LinearLayout.LayoutParams(-1, -2))
''', [])

# ⑥ 含 `//` 的字符串不得把后面的代码吃掉 ——
#    这正是本判据第一版坏掉的地方：Kotlin 字符串里的 `//` 与 `/* … */` 互相吞。
case('字符串含 // 时不得吃掉后续代码', '''
val url = "https://example.com/a"
val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
g.addView(tv, lp(0, 1))
''', ['g'])

# ⑦ 块注释里的同形代码不得吃掉后续代码
case('块注释里的同形代码不得吃掉后续代码', '''
/* 说明：val x = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } */
val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
g.addView(tv, lp(0, 1))
''', ['g'])

# ⑧ 纵向容器但参数是显式 LayoutParams —— 不该报（修复后的形态）
case('纵向组 + 显式 LayoutParams：不该报（修复后）', '''
val g = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
g.addView(rb, LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
''', [])

# ⑨ 子项表达式带逗号（构造器调用）也要能认出容器 —— 回归：第一版正则漏过
case('子项是带逗号的构造器调用（回归）', '''
val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
col.addView(android.widget.TextView(this), lp(0, 1))
''', ['col'])

# ⑩ 真源码（仓库当下这份）必须是干净的 —— 反向对照，防"判据只知道桩"
REPO_KT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                       'app/src/main/java/com/xiaowan/localinference/EngineActivity.kt')
if os.path.exists(REPO_KT):
    src = io.open(REPO_KT, encoding='utf-8').read()
    off = offenders(strip_literals_and_comments(src))
    if not off:
        print('PASS  真源码 EngineActivity.kt 无「纵向 + lp()」误用（反向对照）')
        ok += 1
    else:
        print('FAIL  真源码里仍有误用：%s' % off)
        bad += 1
else:
    print('FAIL  找不到真源码 %s' % REPO_KT)
    bad += 1

if bad == 0:
    print('=== repack 布局扫描器单测 全部通过（%d 条）===' % ok)
    sys.exit(0)
print('=== repack 布局扫描器单测 %d 条失败（共 %d 条）===' % (bad, ok))
sys.exit(1)
