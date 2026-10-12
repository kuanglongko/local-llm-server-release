#!/usr/bin/env python3
"""对 `EngineActivity.kt` 打桩：把"编辑闭环"的某一条跳去掉（守卫必须因此变红）。

为什么单独一个脚本而不是守卫自测里的 heredoc：`sh` 的 heredoc 里嵌 python
的引号/中文在本仓库已多次踩坑（静默返回空串）。一个**真文件**没有这个问题，
也能被单独跑、单独看输出。

⚠ 桩必须**在指定函数体内**动手：本仓库的守卫判据一律锚"函数体内有没有那一跳"，
全局替换会连别的函数一起改掉 —— 那样守卫即使"指对了"也不是因为真的锚在那里。
另外找不到锚点时必须**明确报错**（而非静默不改：那会让"该红的红"这条自测假绿）。

用法：stub_edit.py <file> <桩名>
"""
import re
import sys


def fn_range(lines, name):
    """返回成员函数 `name` 的 (起行, 止行)（半开区间）。"""
    start = None
    for i, l in enumerate(lines):
        if re.match(r'    (?:@\w+\s*)?(?:private |public |internal |protected |override |open |final |suspend )*fun\s+'
                    + re.escape(name) + r'\s*[(<]', l):
            start = i
            break
    if start is None:
        return None
    for i in range(start + 1, len(lines)):
        if re.match(r'    (?:@\w+\s*)?(?:private |public |internal |protected |override |open |final |suspend )*fun\s',
                    lines[i]) or lines[i].startswith('    // ──'):
            return (start, i)
    return (start, len(lines))


def patch(lines, fn, old, new, label):
    a, b = fn_range(lines, fn)
    if a is None:
        sys.stderr.write('打桩失败：找不到函数 %s\n' % fn)
        return None
    hits = [i for i in range(a, b) if old in lines[i]]
    if len(hits) != 1:
        sys.stderr.write('打桩失败：%s 里含 %r 的行有 %d 处（应为 1 处）\n'
                         % (fn, old, len(hits)))
        return None
    lines[hits[0]] = lines[hits[0]].replace(old, new, 1)
    print('已打桩：%s（%s 第 %d 行）' % (label, fn, hits[0] + 1))
    return 0


def main() -> int:
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    path, which = sys.argv[1], sys.argv[2]
    lines = open(path, encoding='utf-8').read().split('\n')
    rc = None
    if which == 'no-reload':
        # 点库不再回填（只改库名）—— 回到阶段 2 之前那个"点库 1 显示库 2 内容"的形态
        # ⚠ 锚点本轮跟着源码搬家（ISSUE #259 的 ③）：切库现在只有**一个**入口
        #   [switchRagDocTo]，`selectRagKb` 只剩一行转发 —— 桩打在转发那一行上
        #   会"打不上"（而打不上会被自测报成 FAIL，正是为了不静默漏桩）。
        rc = patch(lines, 'switchRagDocTo', 'loadRagDoc(name)', 'refreshRagList()', '点库不回填')
    elif which == 'no-restore':
        # 启动不回填 —— "退出重进后输入框空白"仍然存在
        rc = patch(lines, 'onCreate', 'loadRagDoc(RagInject.currentKb(this))',
                   '/* 打桩：启动不回填 */', '启动不回填')
    elif which == 'save-rebuilds':
        # 保存走重建 —— "想保存一下"要等几十秒
        rc = patch(lines, 'runRagSave', 'LlmEngine.saveTexts(this, name, docs)',
                   'LlmEngine.buildIndex(this, name, docs)', '保存改成重建')
    elif which == 'no-dirty-guard':
        # 切库不问 —— 改动静默消失。锚点同上（切库的唯一入口已搬到 switchRagDocTo）。
        rc = patch(lines, 'switchRagDocTo', 'confirmDiscardRagDoc(',
                   'run {', '切库不确认')
    elif which == 'dirty-on-load':
        # 程序化回填也算"用户改过" —— 点库立刻弹一次"要不要保存"（用户什么都没改）
        rc = patch(lines, 'setRagDocText', 'ragDocLoading = true', 'ragDocLoading = false',
                   '回填也置脏')
    elif which == 'save-by-input':
        # 保存按**库名输入框**的字面值定目标 —— A 的正文被写进 B
        rc = patch(lines, 'doSaveTexts', 'val owner = ragDocOwner', 'val owner = typed',
                   '保存按输入框定目标')
    elif which == 'no-gen':
        # 回填不记代际 —— 快速点两个库会串内容。
        # ⚠ P1-a 之后锚点从 loadRagDoc 挪到 refreshRagRows：那个函数是**现在**
        #   唯一"读回内容并落地"的地方（条目列表 + 正文预览都由它画），
        #   代际与归属复核都收在那里（见 rag_edit/check_edit_wiring.py 的 doc_gen）。
        rc = patch(lines, 'refreshRagRows', 'val myGen = ++ragRowsGen', 'val myGen = ragRowsGen',
                   '回填不记代际')
    elif which == 'skip-no-owner':
        # 归属为空时直接放行（"没建过库就敲的字"切库时静默消失）
        rc = patch(lines, 'confirmDiscardRagDoc',
                   'if (!ragDocDirty) { proceed(); return }',
                   'if (!ragDocDirty || ragDocOwner.isEmpty()) { proceed(); return }',
                   '归属为空时放行')
    elif which == 'status-other-criterion':
        # 状态行**另写一个近似判据**（拿"索引文件在不在"当"正文改了没有"）——
        # 这一支里 `textsInSync` 整个消失，而旧判据（"体内出现过 textsInSync"）
        # 恰好能挡住它；真正挡不住的是下面两个桩。
        rc = patch(lines, 'renderRagStatus',
                   'val inSync = exists && RagIndexStore.textsInSync(this, kb)',
                   'val inSync = exists', '状态行另写近似判据')
    elif which == 'status-const-insync':
        # 取值换成**常量 true**：标识符没了，但更坏的是——它换一个近似判据
        # 也照样能编译、照样显示"索引就绪"。
        rc = patch(lines, 'renderRagStatus',
                   'val inSync = exists && RagIndexStore.textsInSync(this, kb)',
                   'val inSync = true', '状态行的 inSync 换成常量')
    elif which == 'status-branch-ignores':
        # ⚠ 这才是本轮的关键桩：**取值取了、分支不看它**。
        # `inSync` 变量还在（旧判据"体内出现过 textsInSync"照样绿），
        # 而"正文已改"那一支永远不会亮 —— 状态行恒绿。
        # ⚠⚠ 桩锚点跟着源码改了（`exists` -> `noIndex`）：上一轮（ISSUE #259 把
        #    "索引在不在"与"库在不在"拆成两把尺子）把这一支的条件从
        #    `exists && !inSync` 换成了 `noIndex && !inSync`，而桩还锚着旧写法 ——
        #    于是它**打不上**（"应为 1 处，实为 0 处"），自测就此恒红。
        #    桩漂最坏的结果是它悄悄不再挡住任何东西（这里已经是红着没人管），
        #    所以锚点跟着换。意图不变：把这一支变成恒假（状态行永远不说"正文已改"）。
        rc = patch(lines, 'renderRagStatus',
                   'noIndex && !inSync -> parts.add',
                   'noIndex && false -> parts.add', '状态行的分支不看 inSync')
    elif which == 'no-list-mark':
        # 列表不标"需重建" —— 用户只看到"库在、N 条、检索永远为空"
        # 去掉**标记本身**（不是只去掉它的颜色）：条件变恒假之后那句提示永远不会出现。
        #
        # ⚠ 本轮这条桩跟着源码改写过：列表从"三元表达式"换成了
        #   `when { !hasIndex -> …; !inSync -> … }`（多分了一态，见 ISSUE #259），
        #   而桩锚的是**具体写法**，于是打桩失败（"应为 1 处，实为 0 处"）。
        #   桩本来的意图是"把 inSync 那一支变成恒假"，所以这里也锚那一支的
        #   条件（`!r.inSync ->`），而不是整句三元表达式的字面形态。
        # ⚠⚠ 本轮**又**换了一次锚点：五档状态收进了 `RagResumeUi.state(...)`
        #   （纯逻辑层，宿主单测钉得住），列表这边只剩"把字段喂进去"。
        #   桩的意图不变（"把'正文已改'那一支变成恒假"），落点随之变成
        #   **喂进去的那个字段**（`inSync = r.inSync` -> `inSync = true`）：
        #   喂常量之后，状态层永远看不到"对不上"，于是那句提示永远不出现。
        rc = patch(lines, 'renderRagList',
                   'inSync = r.inSync',
                   'inSync = true',
                   '列表不标需重建（喂常量，状态层看不到不一致）')
    elif which == 'rows-compacted':
        # ⚠ 本轮新增：条目列表退回**压紧**的 readEntries —— 下标不再是正文行号。
        # 症状是"点删除删错一行"（有坏行时更坏：真的删掉隔壁那条），
        # 而界面上完全正常（条目数少了一个、Toast 说"已删除"）。
        rc = patch(lines, 'refreshRagRows',
                   'RagIndexStore.readEntriesAligned(this, name).map { it?.text }',
                   'RagIndexStore.readEntries(this, name).map { it.text }',
                   '条目列表退回压紧的 readEntries')
    elif which == 'rows-refilter':
        # ⚠ 本轮新增：先 filter 再遍历 —— 下标变成"过滤后的位置"，不再是行号。
        rc = patch(lines, 'renderRagRows',
                   'ragRowsShown = rows.mapIndexed { i, t -> i to t }',
                   'ragRowsShown = rows.filter { it != null }.mapIndexed { i, t -> i to t }',
                   '列表先 filter 再遍历（下标不再是行号）')
    elif which == 'rows-no-continue':
        # ⚠ 本轮新增：跳过空段/坏行时不用 continue。
        # 桩必须**把那句 `continue` 删掉**（不能只把外面条件改成 false ——
        # 那样 `continue` 字面量还在，判据"体内有没有 continue"照样绿，
        # 而这正是判据要防的"跳过时行号被重排"形态）。
        # 删掉 continue 之后，循环体会继续往下走（`val t = t0` 撞上 null），
        # 但那不与本判据相关 —— 判据只看"跳过这条路有没有 continue"。
        # 删掉那句 `continue`（单行替换：`patch` 是按行匹配的）。
        # 顺带把"跳过"整段抹平成一个空分支，让桩只表达"没有 continue"这一件事。
        rc = patch(lines, 'renderRagRows',
                   '                continue',
                   '                // (桩：跳过时不 continue)',
                   '列表跳过行时删掉 continue（行号会被重排）')
    else:
        sys.stderr.write('未知桩名：%s\n%s' % (which, __doc__))
        return 2
    if rc is None:
        return 2
    open(path, 'w', encoding='utf-8').write('\n'.join(lines))
    return 0


if __name__ == '__main__':
    sys.exit(main())
