#!/usr/bin/env python3
"""判「建库成本提示」是不是真的**接在用户按下去之前**，且数一致。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠 RagCostTest）
═══════════════════════════════════════════════════════════════════════════
`RagCost` 自己的单测只证明"算得对、文案短"。它证明不了**接上了** ——
而这一族缺陷的形状恰恰是"纯逻辑全绿、界面上什么都没有"：

  · `buildPrompt` 写好了但没人调 -> 用户按下去之前**仍然是零信息**，
    而这正是本轮要修的那件事（"按了才知道贵"）。单测完全看不出来。
  · 调了但提示**在 startBuildIndex 之后** -> 用户是先看到"建库中…"才知道的，
    与不提示等价（甚至更坏：弹框会挡在已开始的建库前面）。
  · 条数小的时候也调 -> 每次建库都弹框，用户闭眼点确定，
    真有一条贵的提示时也被无视（"简短"这条要求就落空了）。
  · `RagCost.DEFAULT_MAX_CHUNKS` 与 `ChunkParams.maxChunks` 不相等 ->
    撞上限时算错，用户按错的方向去拆文档。

判据一律锚**结构关系**（谁在什么位置调了谁），不锚"某某函数存在"。
用法：check_rag_cost_wiring.py <EngineActivity.kt> <RagCost.kt> <RagIndexStore.kt>
"""
import re
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0] + '/../rag_archive')
sys.path.insert(0, __file__.rsplit('/', 1)[0] + '/../rag_index')
from strip_comments import strip_kt  # noqa: E402


def fn_body(src, name):
    """按括号配对取一个 Kotlin 函数的函数体。"""
    m = re.search(r'private\s+fun\s+' + re.escape(name) + r'\s*\(', src)
    if not m:
        m = re.search(r'fun\s+' + re.escape(name) + r'\s*\(', src)
    if not m:
        return None
    # 先跳到参数表的右括号，再找函数体左括号
    i = src.index('(', m.end() - 1)
    depth = 0
    j = i
    while j < len(src):
        if src[j] == '(':
            depth += 1
        elif src[j] == ')':
            depth -= 1
            if depth == 0:
                break
        j += 1
    k = src.find('{', j)
    if k < 0:
        return None
    depth = 0
    for x in range(k, len(src)):
        if src[x] == '{':
            depth += 1
        elif src[x] == '}':
            depth -= 1
            if depth == 0:
                return src[k:x + 1]
    return None


def cap_in_store(store_src):
    src = strip_kt(store_src)
    m = re.search(r'data\s+class\s+ChunkParams\s*\(', src)
    if not m:
        return None
    i = src.find(')', m.end())
    body = src[m.end():i if i > 0 else m.end() + 1200]
    m2 = re.search(r'val\s+maxChunks\s*:\s*Int\s*=\s*(\d+)', body)
    return int(m2.group(1)) if m2 else None


def cap_in_cost(cost_src):
    m = re.search(r'const\s+val\s+DEFAULT_MAX_CHUNKS\s*(?::\s*Int)?\s*=\s*(\d+)',
                  strip_kt(cost_src))
    return int(m.group(1)) if m else None


def main():
    if len(sys.argv) != 4:
        sys.stderr.write(__doc__)
        return 2
    act = strip_kt(open(sys.argv[1], encoding='utf-8').read())
    cost = open(sys.argv[2], encoding='utf-8').read()
    store = open(sys.argv[3], encoding='utf-8').read()

    body_build = fn_body(act, 'doBuildIndex')
    body_start = fn_body(act, 'startBuildIndex')
    if body_build is None:
        print('FAIL  取不到 doBuildIndex（改名/拆走了？）')
        return 1
    if body_start is None:
        print('FAIL  取不到 startBuildIndex（提示与开工没拆开 —— 顺序判不了）')
        return 1

    fails = []

    # ① 提示必须在 doBuildIndex 里、且 buildPrompt 在 startBuildIndex() 调用**之前**
    if 'RagCost.buildPrompt' not in body_build:
        fails.append('doBuildIndex 里没有 RagCost.buildPrompt —— 用户按下去之前仍是零信息')
    else:
        i_prompt = body_build.find('RagCost.buildPrompt')
        i_start = body_build.find('startBuildIndex(')
        if i_start < 0:
            fails.append('doBuildIndex 里没有 startBuildIndex() 调用（开工入口断了？）')
        elif i_prompt > i_start:
            fails.append('提示排在开工**之后**：用户先看到"建库中…"才知道贵，与不提示等价')

    # ② 小批量必须**不打扰**：提示必须包在 `!= null` 的分支里
    if not re.search(r'val\s+prompt\s*=\s*RagCost\.buildPrompt', body_build):
        fails.append('提示结果没有先存进一个局部变量 —— 无法证明"null 时直通车"（小批量不打扰）')
    elif 'prompt != null' not in body_build:
        fails.append('提示没过 `!= null` 这道闸 —— 条数小时也会弹框（用户会闭眼点确定）')
    # 直通车：null 那一支直接调 startBuildIndex
    if 'startBuildIndex(name, docs)' not in body_build:
        fails.append('doBuildIndex 里没有"不提示时直接开工"那一句（小批量走不通？）')

    # ③ 真实上限必须显式传进去（不靠 RagCost 那份同值抄写）
    if not re.search(r'RagCost\.buildPrompt\([^)]*RagIndexStore\.ChunkParams\(\)\.maxChunks', body_build):
        fails.append('buildPrompt 没显式传真实 maxChunks —— 撞上限时可能按错的值算')

    # ④ RagCost 里那份抄写的默认值必须与真实值相等
    a = cap_in_cost(cost)
    b = cap_in_store(store)
    if a is None:
        fails.append('RagCost 里取不到 DEFAULT_MAX_CHUNKS')
    if b is None:
        fails.append('ChunkParams 里取不到 maxChunks 的默认值')
    if a is not None and b is not None and a != b:
        fails.append('DEFAULT_MAX_CHUNKS=%d 与 ChunkParams.maxChunks=%d 不相等' % (a, b))

    # ⑤ 进行中那行也要带量级（不能只有条数 —— 等的时候没有参照物才是焦虑来源）
    if 'RagCost.buildingLine' not in body_start:
        fails.append('startBuildIndex 里没有 RagCost.buildingLine —— 进行中没有"还要多久"')

    # ⑥ doBuildIndex 里**恰好两处**开工调用，且各有明确归属：
    #      · 一处包在确认按钮的回调里（`setPositiveButton(...) { ... startBuildIndex(...) }`）；
    #      · 一处是**直通车**（小批量不打扰那条路）。
    #    多出来第三处/少一处都意味着"问"与"做"的代码又被摊开写了 ——
    #    那正是"状态标志漏一个"的来源（症状：按钮灰了却永远不恢复，用户以为卡死，
    #    而日志干净）。锚**函数体内**的次数（不锚全文件计数：别处又多调一次也能骗过）。
    n_in_build = len(re.findall(r'startBuildIndex\(', body_build))
    if n_in_build != 2:
        fails.append('doBuildIndex 里有 %d 处 startBuildIndex 调用（应恰好 2 处：'
                     '确认回调 1 处 + 小批量直通车 1 处 —— 多了少了都会漏状态标志）'
                     % n_in_build)
    # 直通车那一处**不**在 lambda 里：它必须是顶格的 `startBuildIndex(name, docs)`。
    # 两处都在 lambda 里的形态 = "小批量也要点一下才建"，与"不打扰"直接冲突。
    if not re.search(r'^\s{8}startBuildIndex\(name, docs\)$', body_build, re.M):
        fails.append('没有"小批量直通车"那一处顶格调用 —— 小批量也要确认，与"不打扰"冲突')

    if fails:
        for f in fails:
            print('FAIL  %s' % f)
        return 1
    print('PASS  建库成本提示确实接在"用户按下去之前"，且上限与真实值一致')
    return 0


if __name__ == '__main__':
    sys.exit(main())
