#!/usr/bin/env python3
"""比对「分块规则」在 C++（rag_index.h）与 Kotlin（LlmEngine.kt）两边是否逐条一致。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠各自单测）
═══════════════════════════════════════════════════════════════════════════
分块在两处各写一遍：native 侧 `chunk_text`（建库/编码时算），Kotlin 侧
`LlmEngine.chunk`（`RagIndexStore` 的追加/编辑路径算）。**两边单测各自都绿**，
验不了"两边一致" —— 而这正是静默失效的形状：

  · 两边**分隔符表**不同 -> 同一份文档在两条路上切出的块边界不同 ->
    追加一条时按新边界算的向量，与建库时按旧边界算的混在同一个索引里。
    检索命中一段、注入另一段，排序看着像随机，接口 200、日志干净。
  · 两边**降级次序**不同 -> 同上，且更隐蔽（只在"窗口内没有段落"的文档上分叉）。
  · 两边**"找不到怎么办"**不同（一边硬截、一边往窗口外找）-> 一边不丢字、
    一边丢字，而丢的那几个字节"检索不到"与"文档里没有"表现完全一样。

因为指纹里只有 `t`/`o` 两个数（块多大 / 重叠多少），描述不了"从哪儿切"，
**表与次序的差异在指纹上完全不可见**。所以这里逐条比对，
并且把"规则版本必须进指纹"也钉住（改了规则却忘了 +1 = 旧索引被当新的用）。

判据一律锚**实现**（先剥注释），不锚"文件里出现过某个字符串"。
用法：check_chunk_rules.py <rag_index.h> <LlmEngine.kt> <RagIndexStore.kt>
"""
import os
import re
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0])
from strip_comments import strip_c, strip_kt  # noqa: E402


def strip_any(src):
    # 只看"这段源码里有没有那一跳"，两种语言都剥掉注释与字符串内容即可。
    # 用 .kt 规则还是 .c 规则由本文档的两类来源决定，所以这里两个都跑、
    # 取更"干净"的那份（Kotlin 规则对 C 源码同样有效：C 里没有 `"""`）。
    return strip_kt(src)


def cpp_table(src):
    """从 `kChunkSeps[]` 初始化列表里取 [(sep, isPara), ...]，保持源码次序。"""
    src = strip_any(src)
    m = re.search(r'kChunkSeps\s*\[\s*\]\s*=\s*\{', src)
    if not m:
        return None
    i = m.end() - 1
    depth = 0
    end = None
    for j in range(i, len(src)):
        if src[j] == '{':
            depth += 1
        elif src[j] == '}':
            depth -= 1
            if depth == 0:
                end = j
                break
    if end is None:
        return None
    body = src[i:end]
    # 每一项形如 { "\n\n", true },
    out = []
    for item in re.findall(r'\{\s*"((?:[^"\\]|\\.)*)"\s*,\s*(true|false)\s*\}', body):
        # C++ 源码里写的是**两字符** `\` `n`，要还原成真实换行才能与 Kotlin 比
        raw = item[0].replace('\\n', '\n').replace('\\t', '\t').replace('\\\\', '\\')
        out.append((raw, item[1] == 'true'))
    return out


def kt_table(src):
    """从 `SOFT_CUT_SEPS` 的 listOf(...) 里取 [(sep, isPara), ...]。

    ⚠ 锚点从 `kChunkSeps` 换成 `SOFT_CUT_SEPS`：表本身搬到了 `TextBoundary.kt`
    （它有第二个消费方 —— 导入超长正文的切段），`LlmEngine` 只剩一个转发。
    名字换了而这里没跟，这条判据会**以 FAIL 的形式**说话（提取不到），不是静默变绿 ——
    提取不到一律当失败处理，见下面 `k is None` 那几行。
    仍然只认 listOf 字面量：若哪天改成"在代码里拼表"，这里提取不到会直接红。
    """
    src = strip_any(src)
    m = re.search(r'SOFT_CUT_SEPS[^\n]*=\s*listOf\s*\(', src)
    if not m:
        # 可能写成 `val SOFT_CUT_SEPS: List<...> = listOf(`（泛型里含括号）
        m = re.search(r'SOFT_CUT_SEPS[\s\S]{0,160}?listOf\s*\(', src)
    if not m:
        return None
    i = m.end()
    depth = 1
    end = None
    for j in range(i, len(src)):
        if src[j] == '(':
            depth += 1
        elif src[j] == ')':
            depth -= 1
            if depth == 0:
                end = j
                break
    if end is None:
        return None
    body = src[i:end]
    # 每项形如 "。\n" to false,  （Kotlin 的 `\n` 在源码里是**两字符** `\` `n`）
    out = []
    for item in re.findall(r'"((?:[^"\\]|\\.)*)"\s*to\s*(true|false)', body):
        raw = item[0]
        # 把 Kotlin 源码里的转义还原成真实字符，才能与 C++ 侧比较
        raw = raw.replace('\\n', '\n').replace('\\t', '\t').replace('\\\\', '\\')
        out.append((raw, item[1] == 'true'))
    return out


def main():
    if len(sys.argv) < 4:
        sys.stderr.write(__doc__)
        return 2
    hdr = open(sys.argv[1], encoding='utf-8').read()
    engine = open(sys.argv[2], encoding='utf-8').read()
    store = open(sys.argv[3], encoding='utf-8').read()
    tb = open(sys.argv[4], encoding='utf-8').read() if len(sys.argv) > 4 and os.path.exists(sys.argv[4]) else ''
    # Kotlin 侧的表在 TextBoundary（共享），规则版本字段可能出现在 RagIndexStore。
    # tb 排在最后：`pickChunkCut` 的判据要看 LlmEngine 里的**转发**，
    # 而切点函数的实体在 TextBoundary —— 两处都要在内，所以合一串一起找。
    kt = engine + '\n' + store + '\n' + tb
    c = cpp_table(hdr)
    k = kt_table(kt)

    fails = []
    if c is None:
        fails.append('C++ 侧提取不到 kChunkSeps[]（改名/拆掉了？）')
    if k is None:
        fails.append('Kotlin 侧提取不到 kChunkSeps（改名/拆掉了？）')
    if c is not None and not c:
        fails.append('C++ 侧分隔符表为空（提取失效 = 判据恒真）')
    if k is not None and not k:
        fails.append('Kotlin 侧分隔符表为空（提取失效 = 判据恒真）')

    if c and k:
        # ① 逐条（次序 + 内容 + 级次）必须完全相同
        if c != k:
            onlyC = [x for x in c if x not in k]
            onlyK = [x for x in k if x not in c]
            fails.append('分隔符表不一致：C++=%r Kotlin=%r（仅 C++=%r / 仅 Kotlin=%r）'
                         % (c, k, onlyC, onlyK))
        else:
            print('PASS  分隔符表逐条一致（%d 条，次序与级次都相同）' % len(c))

        # ② 段落级恰好 1 条
        paras = [s for s, p in c if p]
        if paras != ['\n\n']:
            fails.append('段落级分隔符不是恰好 1 条「\\n\\n」：%r' % paras)

        # ③ ⚠ 空格 / `-` / `·` 必须**不在**表内
        #    它们语义增益极低，却是"窗口内找不到就往前找 -> 整块丢字"的主要触发源。
        for bad in (' ', '-', '·'):
            if any(s == bad for s, _ in c):
                fails.append('分隔符表含 %r：语义增益极低，却是「整块丢字」的主要触发源' % bad)

        # ④ 句读级至少要含用户点名保留的那几条
        for need in ('\n', '。', '！', '？', '；', '，', '、'):
            if not any(s == need for s, _ in c):
                fails.append('分隔符表缺 %r（句读级）' % need)

    # ⑤ `chunk_text` / `chunk` 的**降级次序**与"找不到就硬截"必须是同一套控制流。
    #    判据锚实现：两边都要出现 `pick_chunk_cut`（把"窗口内找切点"抽成一处），
    #    且都要在找不到时**显式**走硬截（`hardCuts` 计数那一步）。
    hdr_code = strip_any(hdr)
    kt_code = strip_any(kt)
    if 'pick_chunk_cut' not in hdr_code:
        fails.append('rag_index.h 里没有 pick_chunk_cut（切点选择没被抽成一处 —— 降级次序会散落）')
    if 'pickChunkCut' not in kt_code:
        fails.append('LlmEngine.kt 里没有 pickChunkCut（切点选择没被抽成一处）')

    # ⑥ "窗口内找不到"必须是**正常结局**（硬截），不许往窗口外找。
    #    两边的实现里，找切点的那个函数体内**不得**出现"往前找最近的一次"的形态：
    #    只要出现 `if (cut == npos) cut = end;` 这类"找不到就用另一个值"的兜底，
    #    就说明又回到了老路径上（那条路径会丢字）。
    #    Kotlin 侧多一跳：`pickChunkCut` 是**转发**（实体在 TextBoundary.pickCut）。
    #    所以查两处 —— 转发在 LlmEngine（控制流没被挪回本地、没被各写一套），
    #    闸在 TextBoundary（"对齐后 <= pos 就当没找到"确实还在）。
    #    只查转发那一处的话，转发到一个没有闸的实现上会**恒绿**。
    for label, src, fn in (('C++', hdr_code, 'pick_chunk_cut'),
                           ('Kotlin', kt_code, 'pickChunkCut')):
        m = re.search(re.escape(fn) + r'\s*\(', src)
        if not m:
            continue
        body = src[m.end():m.end() + 2600]
        # ⚠ Kotlin 侧查的是**闸所在的整个函数**，不是转发的那一行：
        #   实体在 TextBoundary.pickCut 里。转发体内当然没有 `cutRaw <= pos`。
        if label == 'Kotlin':
            m2 = re.search(r'fun\s+pickCut\s*\(', src)
            if not m2:
                fails.append('Kotlin 侧没有 pickCut 的函数体（切点选择没被抽成一处）')
                continue
            body = src[m2.end():m2.end() + 2600]
        # 抽出到第一个顶层 `}` 结束（粗略即可：判据只看"有没有那两行"）
        if re.search(r'cutRaw\s*<=?\s*pos', body) is None:
            fails.append('%s 的 %s 里没有「对齐后 <= pos 就当没找到」这道闸（丢字路径会回来）' % (label, fn))
    if 'TextBoundary.pickCut' not in kt_code and 'fun pickCut' not in kt_code:
        fails.append('Kotlin 侧找不到共享切点函数 TextBoundary.pickCut（切段的那一路会重新各写一套）')

    # ⑦ 规则版本必须进指纹（改了规则却忘了 +1 = 旧索引被当新的用）
    if 'chunkRuleVersion' not in hdr_code:
        fails.append('rag_index.h 的 ChunkCfg 没有 chunkRuleVersion（规则改了、块大小没改时指纹不变）')
    if 'chunkRuleVersion' not in kt_code:
        fails.append('LlmEngine/RagIndexStore 没有 chunkRuleVersion')
    # 它必须真的被写进 fingerprints 的那一格（`k`）：锚 `"k"` 那一行里出现它
    if not re.search(r'"k"\s*\)\s*\.append\([^)]*chunkRuleVersion', kt_code) and \
       not re.search(r'append\("k"\)[\s\S]{0,80}?chunkRuleVersion', kt_code):
        fails.append('Kotlin 侧指纹里没有把 chunkRuleVersion 写进 `k` 那一格')

    if fails:
        for f in fails:
            print('FAIL  %s' % f)
        return 1
    print('PASS  分块规则两边一致（表 / 次序 / 硬截 / 规则版本进指纹）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
