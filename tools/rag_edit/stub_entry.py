#!/usr/bin/env python3
"""对源码打桩：把「追加一条 / 删除单条」的某一条跳去掉（守卫必须因此变红）。

为什么单独一个脚本而不是守卫自测里的 heredoc：`sh` 的 heredoc 里嵌 python
的引号/中文在本仓库已多次踩坑（静默返回空串）。一个**真文件**没有这个问题，
也能被单独跑、单独看输出。

⚠ 桩必须**在指定函数体内**动手：本仓库的守卫判据一律锚"函数体内有没有那一跳"，
全局替换会连别的函数一起改掉 —— 那样守卫即使"指对了"也不是因为真的锚在那里。
另外找不到锚点时必须**明确报错**（而非静默不改：那会让"该红的红"这条自测假绿）。

用法：stub_entry.py <file> <桩名>
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


def patch(lines, fn, old, new, label, nth=1):
    a, b = fn_range(lines, fn)
    if a is None:
        sys.stderr.write('打桩失败：找不到函数 %s\n' % fn)
        return None
    hits = [i for i in range(a, b) if old in lines[i]]
    if len(hits) < nth:
        sys.stderr.write('打桩失败：%s 里含 %r 的行只有 %d 处（要第 %d 处）\n'
                         % (fn, old, len(hits), nth))
        return None
    i = hits[nth - 1]
    lines[i] = lines[i].replace(old, new, 1)
    print('已打桩：%s（%s 第 %d 行）' % (label, fn, i + 1))
    return 0


def main() -> int:
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    path, which = sys.argv[1], sys.argv[2]
    lines = open(path, encoding='utf-8').read().split('\n')
    rc = None
    if which == 'no-encode':
        # 追加不编码 —— 退回"标脏等重建"（增量编辑名存实亡）
        rc = patch(lines, 'addEntry', 'val v = embed(clipped)',
                   'val v: FloatArray? = null', '追加不编码')
    elif which == 'drop-vector':
        # 算了向量但不交给 Entry —— 索引里不会有这一条（而界面说"追加成功"）
        rc = patch(lines, 'addEntry', 'RagIndexStore.Entry(clipped, src, v)',
                   'RagIndexStore.Entry(clipped, src)', '向量没交给存储层')
    elif which == 'rows-add-count':
        # rows 改成"旧行数 + 追加条数"（有条目没算出向量时多算 -> 索引判为无效）
        rc = patch(lines, 'appendEntries', 'val rowsAfter = rowsOf(after)',
                   'val rowsAfter = rowsBefore + newOnes.size', 'rows 用累加而不是现算')
    elif which == 'rows-not-fp':
        # 指纹不算行数 —— 退回"索引落后于正文也照样可用"
        rc = patch(lines, 'appendEntries', 'textsHashOf(after), rowsAfter)',
                   'textsHashOf(after))', '指纹不带行数')
    elif which == 'vec-all':
        # 不管维度对不对，全部塞进向量区 —— 后面每一行都错位
        rc = patch(lines, 'appendEntries', 'if (r.vector.size == dim) addVec.add(r.vector) else dropped++',
                   'addVec.add(r.vector)', '没算出的向量也进向量区')
    elif which == 'texts-first':
        # 先写正文后写索引（失败会留下"前 N-1 行都对"的错位，没有现象指向它）
        a, b = fn_range(lines, 'appendEntries')
        if a is None:
            sys.stderr.write('打桩失败：找不到 appendEntries\n'); return 2
        i_idx = i_txt = None
        for i in range(a, b):
            if 'writeIndexAtomic' in lines[i] and i_idx is None:
                i_idx = i
            if 'writeTexts' in lines[i] and i_txt is None:
                i_txt = i
        if i_idx is None or i_txt is None or i_idx >= i_txt:
            sys.stderr.write('打桩失败：appendEntries 里没找到"先索引后正文"的两跳\n'); return 2
        lines[i_idx], lines[i_txt] = lines[i_txt], lines[i_idx]
        print('已打桩：交换"写索引"与"写正文"两跳（%d <-> %d 行）' % (i_idx + 1, i_txt + 1))
        rc = 0
    elif which == 'no-rows-check':
        # 不校验"指纹行数 == 正文行数 == count"
        rc = patch(lines, 'readState', 'if (rows != rowsInFp || count != rowsToCount(rowsInFp)) return null',
                   '/* 打桩：不校验 */', '不校验行数一致')
    elif which == 'no-skip-line':
        # 搬向量时不跳过被删的那一行 —— 正文少一行、向量多一行
        rc = patch(lines, 'removeEntry', 'if (r == index) continue',
                   '/* 打桩：不跳过 */', '搬向量时不跳过被删行')
    elif which == 'no-dst-guard':
        # 去掉"搬运数 != 预期即拒"的兜底
        rc = patch(lines, 'removeEntry', 'if (dst != countAfter) return EditResult.FAILED',
                   '/* 打桩：去掉兜底 */', '去掉搬运数兜底')
    elif which == 'del-by-view':
        # UI：删除传的是"第几个视图"而不是记下来的行号
        rc = patch(lines, 'renderRagRows', 'confirmDeleteEntry(i,',
                   'confirmDeleteEntry(ragRowsContainer.indexOfChild(row),',
                   '删除按视图下标')
    elif which == 'append-clobbers-draft':
        # 追加顺手把内容写进编辑器（覆盖用户未保存的草稿）
        rc = patch(lines, 'doAppendEntry', "ragNewEt.setText(\"\")",
                   'setRagDocText(text)', '追加覆盖草稿')
    elif which == 'no-rows-to-count':
        # 把 rows -> count 的口径换算去掉（以为两者相等）
        # —— 后果：每一份**正常**索引都被判成"索引落后于正文"（每次都重建）
        rc = patch(lines, 'readState', 'count != rowsToCount(rowsInFp)',
                   'count != rowsInFp', '去掉 rows->count 口径换算')
    elif which == 'no-truncated-flag':
        # 不把"是否被截断"单独判出来 —— 于是成功路径无从提示
        rc = patch(lines, 'addEntry', 'val truncated = trimmed.length > cp.targetChars',
                   'val truncated = false', '不判截断（无从提示）')
    elif which == 'no-truncated-note':
        # 判了截断却不放进成功回执 —— 用户以为整条进去了
        rc = patch(lines, 'addEntry', 'if (truncated) "已追加',
                   'if (false) "已追加', '截断不提示')
    elif which == 'no-status-count':
        # 状态行不报条目数
        # ⚠ 锚的是**那一行**（`parts.add("$cnt 条")`），行内容而不是行号。
        # ⚠ 本轮换了锚：PR #265 把状态行的取值从 `RagIndexStore.textsCount(...)`
        #   换成了**后台快照表** `ragListEntries[kb]`（`textsCount` 会把整个
        #   texts.jsonl 读进主线程，而这一屏每重画一次都读一遍）。出口那一行
        #   随之从 `parts.add("${…textsCount…} 条")` 变成 `parts.add("$cnt 条")`。
        #   桩若还锚老形态，patch() 找不到目标 -> `rc is None` -> 退出 2 ->
        #   自测把"桩没打上"报成 FAIL。**那不是判据坏了，是桩漂了** ——
        #   而桩漂了最坏的结果是它悄悄不再挡住任何东西（假绿），所以必须跟着改。
        # ⚠ 更早一轮还换过一次锚：`if (exists) parts.add(...)` 上面加了一行注释
        #   （"待重建那一支上面已经报过条数了，不重复"）—— 行号挪了一格，
        #   但锚的是**行内容**不是行号，所以那次不需要改；这次是行内容真的变了。
        rc = patch(lines, 'renderRagStatus',
                   'if (exists && cnt != null) parts.add("$cnt 条")',
                   '/* 打桩：不报条目数 */', '状态行不报条目数')
    else:
        sys.stderr.write('未知桩名：%s\n%s' % (which, __doc__))
        return 2
    if rc is None:
        return 2
    open(path, 'w', encoding='utf-8').write('\n'.join(lines))
    return 0


if __name__ == '__main__':
    sys.exit(main())
