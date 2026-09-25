#!/usr/bin/env python3
"""钉「设置页的说明文案不许再堆成排查手册」；超限则打印并 exit 1。

背景见 tools/run_repack_lp_guard.sh 的 ④b。设置页的 mmap / repack 两处说明
曾经各堆成一大段（日志两行怎么对账、0.9.127 的 LayoutParams 缺陷、两种同形
现象怎么分），用户反馈"太啰嗦" —— 面板要的是"按钮干什么 + 要注意什么"，
排查过程属于 README / HTP-STATUS 那一层。

为什么不把判据写成"不许出现某段话"：那是把它钉在**本次删掉的那几句措辞**上，
下次换个说法堆同样长的文就照样绿。所以这里锚**体积**与**位置**两件语义：

  ① 每段说明的字数上限（HINT_MAX）：挡住"又堆长文"；
  ② 说明**必须挨着它自己的控件**：mmap 的说明要落在含 mmapEt 的那一行之后、
     且其间不得再插进另一个控件的标签 —— 上一版正是把 mmap 的说明放到了
     repack 三档之后，设置框与说明被整块隔开（用户点1 报的就是这个）。

剥离串写在同一份源码上按"label(...) 的实参"取，而不是按行号：
行号一改就漂，而"说明是哪个 label"是语义。

用法：python3 tools/repack_lp/check_hint_length.py <Kotlin 源码路径>
"""
import io
import re
import sys

# 说明文段的字数上限。定为 160 是**贴着本轮收敛后**的两段走的
# （mmap 一句 28 字、repack 一句 99 字，各留一点余量），
# 不是为了容忍长文 —— 上一版那两段分别是 210 字与 190 字，都在这条线之上。
HINT_MAX = 160


def hints(src: str):
    """[(起始行号, 文案)] —— 面板里所有 `label("…")` 的文案（拼接后的整段）。"""
    out = []
    for m in re.finditer(r'label\(', src):
        # 从 label( 开始按括号配对取实参：文案是若干个字符串字面量的 `+` 拼接。
        i = m.end()
        depth = 1
        j = i
        while j < len(src) and depth:
            if src[j] == '(':
                depth += 1
            elif src[j] == ')':
                depth -= 1
            j += 1
        arg = src[i:j - 1]
        text = ''.join(re.findall(r'"([^"]*)"', arg))
        if text:
            out.append((src.count('\n', 0, m.start()) + 1, text))
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print('用法: check_hint_length.py <Kotlin 源码路径>', file=sys.stderr)
        return 2
    path = sys.argv[1]
    src = io.open(path, encoding='utf-8').read()
    bad = []

    # ① 体积：每段说明都在上限内。
    for line, text in hints(src):
        if len(text) > HINT_MAX:
            bad.append('%s:%d 说明 %d 字，超过 %d 字上限：%s…'
                       % (path, line, len(text), HINT_MAX, text[:30]))

    # ② 位置：mmap 的说明必须紧挨含 mmapEt 的那一行（其间不再有别的控件标签）。
    # ⚠ 起点取 **addFull 那一行**（`listOf(labRowA, …, labRowB, gridRowB).forEach`）：
    # mmap 输入框就在这一行被挂进面板，说明必须紧跟其后。
    # 第一版把起点取在 gridRowB.addView 那一行、还把 mmapEt 当"插进来的控件"查，
    # 改对了也判红 —— 判据网自己坏了最难发现，因为"红"看起来像代码的问题；
    # 第二版取 labRowB（构造行），而构造与挂载之间还隔着 labRowG / 说明文本，
    # 于是又判红在同一件事上。锚「挂载」而不是「构造」才是这段布局的语义。
    rows = src.split('\n')
    mmap_row = next((i for i, l in enumerate(rows) if 'labRowB, gridRowB).forEach' in l), None)
    hint_row = next((i for i, l in enumerate(rows) if 'mmap=0 为无映射直读' in l), None)
    if mmap_row is None or hint_row is None:
        bad.append('%s 找不到 mmap 说明或含 mmapEt 的那一行（锚点已失效，判据要一起更新）' % path)
    elif hint_row < mmap_row:
        bad.append('%s mmap 的说明（第 %d 行）排在含 mmapEt 的那一行（第 %d 行）**之前**'
                   % (path, hint_row + 1, mmap_row + 1))
    elif hint_row == mmap_row:
        bad.append('%s mmap 的说明与 mmap 输入框写在同一行，读的人对不上是哪一格' % path)
    else:
        # ⚠ 只查"有没有**别的**设置控件被插进来"，**不**把 mmapEt 自己算进去 ——
        # 它就在起始行（含 mmapEt 的那一行）上，本来就在这段区间里。
        # 第一版把它一并查了，于是**改对了也判红**：判据网自己坏了，
        # 而"改对了却红"与"代码真的坏了"混在同一个读数里，最容易被当成噪声忽略。
        between = '\n'.join(rows[mmap_row + 1:hint_row])
        # ⚠ 只查**说明文段**（`addFull(label(` / `pageSet.addView(label(`）与挂载语句，
        # 不查控件标识符：起点那一行自己就写着 gridRowB（那正是挂载语句），
        # 把 `gridRow` 一并当"插进来的东西"会把它自己算进去 —— 又一次"改对了也红"。
        for other in ('addFull(label(', 'addFull(labRow', 'repackGroup', 'flashCb', 'thinkCb'):
            if other in between:
                bad.append('%s mmap 的说明与 mmap 输入框之间又插进了 %s（说明被隔开了）'
                           % (path, other))
                break

    if not bad:
        print('ok')
        return 0
    for b in bad:
        print(b)
    return 1


if __name__ == '__main__':
    sys.exit(main())
