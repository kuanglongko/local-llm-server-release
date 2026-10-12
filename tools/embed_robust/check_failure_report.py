#!/usr/bin/env python3
"""断言「失败不再静默」（方案 C）：失败清单、原因实值、meta.json 记录、正文保留。

═══ 这一条守的是什么 ═══
早先的实现里，一条文本编码失败的全部痕迹是：
  · native 日志一句「归一化失败（零范数/NaN），丢弃这一条」；
  · `skipped++`；
  · UI 上一句「检查嵌入模型与内存」。
三处都**不知道"是哪一条"**，而那句提示在两个不同根因下都成立（池化取错 / 维度不匹配）
—— 方向还是错的。100 条里坏 3 条时只能二分重试。

判据锚的是「哪一条」真的被记录下来、原因真的带实值、正文真的留下了 ——
不锚"日志里出现过某关键词"（后者恒真：写错的那一支也会有关键词）。
"""
import re
import sys
from pathlib import Path


def main():
    kt = Path(sys.argv[1]).read_text(encoding='utf-8')
    native = Path(sys.argv[2]).read_text(encoding='utf-8')
    store = Path(sys.argv[3]).read_text(encoding='utf-8')
    bad = 0

    # ① native：失败详情结构化（原因码 + 实值），且暴露给 Kotlin
    if 'embed_failure_set' not in native:
        print("FAIL  native 没有 embed_failure_set —— 失败详情只进日志，Kotlin 拿不到")
        bad += 1
    else:
        print("PASS  native 记录结构化失败详情（embed_failure_set）")
    if 'nativeEmbedLastFailure' not in native:
        print("FAIL  native 没有导出 nativeEmbedLastFailure（Kotlin 无从取失败详情）")
        bad += 1
    else:
        print("PASS  native 导出 nativeEmbedLastFailure")
    # 原因码必须是稳定短名（进 meta.json 的 failReason，改名会让历史索引对不上）
    if 'failure_name' not in native:
        print("FAIL  native 没有用 embedtext::failure_name 产生稳定原因码")
        bad += 1
    else:
        print("PASS  native 用 failure_name() 产生稳定原因码")

    # ② 归一化失败必须报出**实际范数**（0 与 1e-38 是两种病）
    m = re.search(r'归一化失败（范数=', native)
    if not m:
        print("FAIL  归一化失败的日志里没有实际范数 —— "
              "`norm=0`（真零向量）与 `1e-38`（下溢）指向完全不同的毛病，"
              "只报\"失败\"时两者长得一样")
        bad += 1
    else:
        print("PASS  归一化失败报出实际范数")

    # ③ Kotlin：把失败详情解析出来（不猜：格式不符返回 null）
    if 'parseEmbedFailure' not in kt:
        print("FAIL  LlmEngine 没有 parseEmbedFailure（失败详情没有被解析出来）")
        bad += 1
    else:
        if not re.search(r'if\s*\(\s*f\s*\.\s*size\s*!=\s*6\s*\)\s*return\s+null', kt):
            print("FAIL  parseEmbedFailure 没有校验字段个数 —— "
                  "格式不符时硬解会得到一个字段全错的对象（比没有更糟）")
            bad += 1
        else:
            print("PASS  parseEmbedFailure 校验字段个数（不猜）")

    # ④ buildIndex：失败清单被收集（含下标），且带上限
    if 'kMaxReportedFails' not in kt:
        print("FAIL  没有 kMaxReportedFails —— 失败条数一多就刷爆界面")
        bad += 1
    else:
        print("PASS  失败明细有上限（kMaxReportedFails）")
    if 'failedIdx' not in kt:
        print("FAIL  buildIndex 没有记录失败下标（failedIdx）—— \"哪一条\"还是不知道")
        bad += 1
    else:
        print("PASS  buildIndex 记录失败下标 failedIdx")

    # ⑤ 失败条目的**正文必须留下**（不是整条丢弃）
    if not re.search(r'rows\s*\.\s*add\(RagIndexStore\s*\.\s*Entry\s*\(\s*texts\s*\[', kt):
        print("FAIL  失败分支没有把正文 add 进 rows —— "
              "整条丢弃会让用户看不见自己敲的那条（100 条坏 3 条时只能二分重试）")
        bad += 1
    else:
        # 确认它出现在 continue 之前的失败分支里。
        # ⚠ 锚"无向量的 Entry 形态"（两个实参），**不锚循环变量名**：合批之后
        # 这里的下标是 `k`（批内偏移）而不是 `i`（批起点），写死名字会让判据
        # 在**正确的**代码上变红，然后被"顺手放宽"成恒真的写法 —— 本仓库吃过
        # 这个亏（判据锚到了具体标识符，改个名就报警，最后没人再看它）。
        # 真正要钉的是"失败分支 add 的 Entry **不带向量**"（带上错向量才是坏的那支）。
        # ⚠ 起点必须锚到**编码循环里的那一处** `if (v == null ...)`：`LlmEngine.kt`
        # 里同一个条件出现了两次（另一处是 `probeRetrieveParity` 的批大小对照，
        # 它没有 `rows.add` 也没有 `failedIdx`）。第一版写的是"文件里第一处
        # `if (v == null`"，在另一处被提到前面之后，这段窗口整个滑到了**无关的函数**
        # 上，于是"失败分支的 Entry 形态"永远判红 —— 而源码是对的。
        # 锚**带上下文的那一行**（后面紧跟"失败的正文照样留下"这条注释的首句），
        # 那是这一支语义上的指纹，不是行号、也不是"文件里第几处"。
        ANCHOR = 'if (v == null || v.size != dim) {'
        at = kt.find('失败的**正文照样留下**')
        j = kt.rfind(ANCHOR, 0, at) if at >= 0 else -1
        if j < 0:
            print("FAIL  找不到编码循环里的失败分支（锚点已失效，判据要一起更新）")
            bad += 1
            seg = ''
        else:
            seg = kt[j:j + 700]
        if not re.search(r'rows\s*\.\s*add\(\s*RagIndexStore\s*\.\s*Entry\s*\(\s*texts\s*\[[^\]]+\]\s*,\s*src\s*\)\s*\)', seg):
            print("FAIL  失败分支里 add 的不是「无向量」的那个 Entry 形态（可能带上了错向量）")
            bad += 1
        else:
            print("PASS  失败条目的正文照样留下（无向量形态）")

    # ⑥ 有失败 → 走 savePartial（正文全量 + 索引待重建），不是 save
    # ⚠ 必须锚**分支条件**，不能只判"文件里出现过 savePartial"——后者恒真：
    # 把 `if (stale)` 改成 `if (false)`（永远走 save）时，savePartial 的调用**文本还在**，
    # 只是永远执行不到，而那份索引会被 save 的"向量行数 == 正文行数"校验直接拒绝 ——
    # 症状是"建库总是失败"，且判据照样绿。本轮真踩到过。
    m6 = re.search(r'val\s+\w+\s*=\s*if\s*\(\s*stale\s*\)\s*\{', kt)
    if not m6:
        print("FAIL  buildIndex 没有「按 stale 分支」决定 save/savePartial —— "
              "有失败时要么整体失败、要么假装成功")
        bad += 1
    elif 'savePartial' not in kt:
        print("FAIL  RagIndexStore 的 savePartial 没有被调用")
        bad += 1
    else:
        print("PASS  buildIndex 有失败时按 stale 分支走 savePartial")
    if 'savePartial' not in store:
        print("FAIL  RagIndexStore 没有 savePartial 实现")
        bad += 1
    else:
        print("PASS  RagIndexStore 提供 savePartial")

    # ⑦ savePartial 只保留"行号对齐的向量前缀"（否则会整体错位）
    if not re.search(r'for\s*\(\s*r\s+in\s+rows\s*\)\s*\{[^}]*vector\s*\.\s*size\s*==\s*dim[^}]*break', store, re.S):
        print("FAIL  savePartial 没有「遇到第一个缺向量的行就停」—— "
              "把后面的成功向量往前挤会造成整体错位：分数对、注入内容错，且不报错")
        bad += 1
    else:
        print("PASS  savePartial 只保留对齐的向量前缀（遇到缺口即停）")

    # ⑧ meta.json 必须记下失败下标与原因（刷新后仍看得见）
    for needle, desc in (('"failedIndices"', '失败下标'), ('"failReason"', '失败原因码')):
        if needle not in kt:
            print(f"FAIL  meta.json 没写 {desc}（{needle}）—— 日志会滚、UI 会重画，"
                  "用户刷新后就看不到哪几条是坏的")
            bad += 1
        else:
            print(f"PASS  meta.json 写入了{desc}")

    # ⑨ 追加一条失败时，报错要**带上那一条的内容**
    m2 = re.search(r'这一条追加失败：「\$head」', kt)
    if not m2:
        print("FAIL  追加单条失败时没有带出这一条的内容 —— "
              "用户点「追加」时知道自己敲了什么，但不知道坏在哪（要点是他能动手）")
        bad += 1
    else:
        print("PASS  追加单条失败时带出这一条的内容")

    # ⑩ 失败要给出**结论**（不只是数据）—— 本轮补的那一步
    # ═══════════════════════════════════════════════════════════════════════
    # 上一轮把失败做成了**数据**（下标 + 原因码 + 实值），这一轮补**判断**。
    # 差别用用户的经历量一下：他看到"#1 向量范数为 0.0 …… 池化方式**可能**与该模型
    # 不符"，而"可能"不是结论 —— 于是他换了模型、反复重建，方向全错。
    #
    # 判据钉两头：
    #   ① 有 verdictOf（判决函数存在，且**按失败形态**分岔 —— 全败 vs 部分失败）；
    #   ② 它的结论**进了汇报**（只写一个没人调的函数 = 没有结论）。
    # 不钉文案（文案会改），钉"分支条件"（全败 == okCount == 0）与"调用实参"。
    m3 = re.search(r'\n\s*private\s+fun\s+verdictOf\s*\(([^)]*)\)', kt)
    if not m3:
        print("FAIL  LlmEngine 没有 verdictOf —— 失败只有数据、没有结论："
              "用户看到一句「**可能**与该模型不符」之后不知道该改什么")
        bad += 1
    else:
        args = [a.strip().split(':')[0].strip() for a in m3.group(1).split(',')]
        if 'okCount' not in args or 'failCount' not in args:
            print(f"FAIL  verdictOf 的入参里没有 okCount/failCount（实为：{args}）—— "
                  "分辨「全败（看配置）」与「部分失败（看内容）」**只能**靠成功条数")
            bad += 1
        else:
            print(f"PASS  verdictOf 按 okCount/failCount 分岔（实参：{args}）")
        # 全败那一支的判据必须真的在函数体里（不是"文件里出现过 okCount"）
        fn = kt[kt.index('private fun verdictOf'):]
        fn = fn[:fn.index('\n    }\n')]
        if not re.search(r'if\s*\(\s*okCount\s*==\s*0\s*\)', fn):
            print("FAIL  verdictOf 里没有 `okCount == 0` 这一支 —— "
                  "全败与部分失败的处置**完全相反**（看配置 vs 看内容），"
                  "不做这个分岔就等于把两种病说成一种")
            bad += 1
        else:
            print("PASS  verdictOf 有「全败」那一支（okCount == 0）")
        # 结论必须**进汇报**（函数体外用一次不算：那条 return 才是用户看到的）
        bi = kt.index('fun buildIndex(')
        seg = kt[bi:bi + kt[bi:].index('\n    /** 汇报给用户的失败明细上限')]
        if not re.search(r'verdictOf\s*\(', seg):
            print("FAIL  buildIndex 的汇报里没有调 verdictOf —— "
                  "判决函数写了但不接进汇报，用户拿到的仍是一堆数据")
            bad += 1
        else:
            print("PASS  buildIndex 的汇报调用了 verdictOf（结论进了用户可见的那句话）")
        if '结论：' not in seg:
            print("FAIL  汇报里没有出现「结论：」这一行 —— 日志与 Toast 都该带它")
            bad += 1
        else:
            print("PASS  汇报里带「结论：」")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
