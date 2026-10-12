#!/usr/bin/env python3
"""「知识库续编」这条链子**两端接上了**的判据（源码级，先剥注释）。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠 RagResumeUiTest / RagIndexStoreTest）
═══════════════════════════════════════════════════════════════════════════
本轮要的是三件事，而它们的失效形态**全是静默**的（不崩、不报错、HTTP 200）：

  · **准入**：半份索引（[savePartial] 留下的）必须被判成**可续**而不是
    "要重建"。判错的后果是两个方向都坏 ——
      判成 none -> 用户点一次建索引就白等几十分钟（本轮要消灭的正是它）；
      判成 valid -> 检索拿半份索引去命中，缺的那些行永远搜不到，
                    而这与"没命中"长得一模一样。
  · **续编**：`buildIndex` 要先读回前缀、从缺口接着编。读回的那一步若排在
    编码循环**之后**（或压根没有），行为与"从头跑"逐字相同 —— 而它不报错。
  · **作废闸**：指纹里那一串（模型/维度/池化/切块/**批大小**）任何一项变了，
    必须整档作废**且说出来**。漏了它，续编会拿"上次那套配置算的向量"
    配"这次这套"，排序像随机、日志干净 —— 本仓库反复消灭的正是这一族。

单测各自绿证明不了"接上了"：RagResumeUiTest 只证措辞对、RagIndexStore 的单测
只在喂进去的字节上跑。所以这里锚**结构关系**（谁在谁之前、谁只认哪种状态、
落盘只在什么条件下发生），不锚"文件里出现过某个字符串"。

用法：check_resume_wiring.py <RagIndexStore.kt> <LlmEngine.kt> <EngineActivity.kt>
"""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'rag_index'))
from strip_comments import strip_kt  # noqa: E402


def fail(msg):
    print("FAIL  " + msg)
    return 1


def ok(msg):
    print("PASS  " + msg)
    return 0


def fn_body(src, name):
    """按真括号配对取函数体（剥注释之后）。找不到返回 None。"""
    code = strip_kt(src)
    for m in re.finditer(re.escape(name) + r'\s*\(', code):
        k = m.end() - 1
        depth = 0
        while k < len(code):
            if code[k] == '(':
                depth += 1
            elif code[k] == ')':
                depth -= 1
                if depth == 0:
                    break
            k += 1
        # 找函数体的第一个 `{`（跳过 `=` 与返回类型）
        j = code.find('{', k)
        eq = code.find('=', k, j if j >= 0 else k + 200)
        if j < 0:
            continue
        if eq >= 0 and eq < j:
            j = code.find('{', eq)
        if j < 0:
            continue
        depth = 0
        p = j
        while p < len(code):
            if code[p] == '{':
                depth += 1
            elif code[p] == '}':
                depth -= 1
                if depth == 0:
                    return code[j + 1:p]
            p += 1
    return None


bad = 0


def check(cond, msg):
    global bad
    if cond:
        print("PASS  " + msg)
    else:
        print("FAIL  " + msg)
        bad += 1


def main():
    if len(sys.argv) != 4:
        sys.stderr.write("用法：check_resume_wiring.py <RagIndexStore.kt> <LlmEngine.kt> <EngineActivity.kt>\n")
        return 2
    store_p, engine_p, act_p = sys.argv[1:]
    store = open(store_p, encoding='utf-8').read()
    engine = open(engine_p, encoding='utf-8').read()
    act = open(act_p, encoding='utf-8').read()
    store_c = strip_kt(store)
    engine_c = strip_kt(engine)

    # ── ① 准入：load() 对"前缀连续"的半份返回 partial，对"越界"返回 none ────
    load = fn_body(store, 'load')
    check(load is not None, "RagIndexStore.load 函数体取得到（改名/拆掉即红）")
    if load:
        check('Load.partial(' in load,
              "load() 对**前缀连续**的半份返回 partial（而不是 none + stale）")
        check(re.search(r'count\s*>\s*full', load) is not None,
              "load() 对**越界**的（count > 正文行数）返回 none（损坏，不是半份）")
        # 反面：不许再把"count != rows-1"一律判 none（那会把可续掐死）
        check(not re.search(r'rowsToCount\(rowsInFp\)\s*!=\s*count', load),
              "反面：load() 里**不再**用『count != rows-1 就 none』把可续掐死")

    # ── ② loadPrefix 只认 partial（不许把齐整/损坏当可续）─────────────────
    lp = fn_body(store, 'loadPrefix')
    check(lp is not None, "RagIndexStore.loadPrefix 函数体取得到")
    if lp:
        check('is Load.partial' in lp or 'Load.partial' in lp,
              "loadPrefix() 只认 Load.partial（齐整索引没有缺口可补）")
        check(lp.count('load(') >= 1,
              "loadPrefix() 走的是 load()（判据同源，不另写一套近似）")

    # ── ③ resumableCount 的界：0 < count < rows-1，且正文 hash 一致 ──────────
    rc = fn_body(store, 'resumableCount')
    check(rc is not None, "RagIndexStore.resumableCount 函数体取得到")
    if rc:
        check(re.search(r'count\s*<=\s*0', rc) is not None, "resumableCount 下界：count <= 0 不是可续")
        check(re.search(r'count\s*>=\s*full', rc) is not None, "resumableCount 上界：count >= rows-1 不是可续（没有缺口）")
        check('textsInSync' in rc, "resumableCount 要求正文 hash 一致（正文改过就不是可续，是要重建）")

    # ── ④ 续编：buildIndex 先读前缀、再用它算起点、再跳过 ─────────────────
    bi = fn_body(engine, 'buildIndex')
    check(bi is not None, "LlmEngine.buildIndex 函数体取得到")
    if bi:
        check('loadPrefix' in bi,
              "buildIndex 调 loadPrefix 读回前缀（不读 = 与从头跑逐字相同）")
        check(re.search(r'resumeFrom\s*\(', bi) is not None,
              "buildIndex 用 resumeFrom 算起点（起点不是手写的第二份算术）")
        check(re.search(r'var\s+i\s*=\s*startFrom', bi) is not None,
              "编码循环从 startFrom 起步（从 0 起 = 续编白做）")
        # 顺序：loadPrefix 必须**早于**编码循环里的 embedBatch
        ip = bi.find('loadPrefix')
        bt = bi.find('embedBatch(')
        check(ip >= 0 and bt >= 0 and ip < bt,
              "loadPrefix 排在 embedBatch **之前**（排在之后 = 装饰）")
        # 反面：不许"装不下就整批 return null"那种把可续掐死的记账
        check(re.search(r'prefix\s*!=\s*null\s*&&\s*startFrom\s*>\s*0', bi) is not None,
              "前缀向量搬运的判据是 prefix != null && startFrom > 0")
        # 作废闸要出声
        check('lastDiff' in bi,
              "buildIndex 在续不上时读 lastDiff —— 作废闸**出声**（不许静默从头）")

    # ── ⑤ 定期落盘：只在没有失败时落，且复用 savePartial ──────────────────
    if bi:
        check('kEmbedResumePersistChunks' in bi or 'kEmbedResumePersistChunks' in engine_c,
              "定期落盘的块数闸存在（常量没被删）")
        check('kEmbedResumePersistMs' in bi or 'kEmbedResumePersistMs' in engine_c,
              "定期落盘的时间闸存在（常量没被删）")
        check(re.search(r'failedIdx\.isEmpty\(\)', bi) is not None,
              "定期落盘**只在没有失败时**发生（有失败时向量不能往前挤）")
        check('saveProgress' in bi,
              "定期落盘复用 store 的进度落盘（不新造文件格式）")
        # 落盘点必须在循环体内（在循环外 = 只落一次 = 等于不落）
        chk = re.search(r'while\s*\(i\s*<\s*texts\.size\)\s*\{', bi)
        if chk:
            seg = bi[chk.end():]
            check(re.search(r'saveProgress[\s\S]{0,400}?\n\s*i\s*=\s*end', seg) is not None
                  or (seg.find('saveProgress') >= 0 and seg.find('i = end') >= 0 and
                      seg.find('saveProgress') < seg.find('i = end') + 2000),
                  "落盘点在编码循环**体内**（在体外 = 只落一次）")

    # ── ⑤b 反面：定期落盘**不许改写正文** ────────────────────────────────────
    # 判据锚"落盘那一刻交出去的是不是**全量正文**"。
    #
    # 为什么这条必须有（0.9.216 那个 bug）：`savePartial` 的契约是"传进来的
    # rows 就是这份库的**全部正文**，向量只留前缀"。而建索引的循环里
    # `rows` 只装到"已编过的那些块" —— 把它直接传进去，`savePartial` 会
    # 照契约把**前缀**当整份正文写下去：
    #   · 后 `texts.size - i` 块的字从 `texts.jsonl` 里**消失**（用户导进来的
    #     正文被删掉），而索引与剩下的正文**自洽**（count == 行数-1、hash 对得上）
    #     -> 下次进来判成 `Load.valid`，界面报"库建成功了"
    #     -> 现象正是"导 1000 条、建到 216 条被杀、重启只剩 216 条"；
    #   · 指纹里的正文 hash/行数按**前缀**算 -> `loadPrefix(fullFp)` 永远对不上
    #     -> 续编一次都不生效（不报错，只是"又从 0 开始"）。
    # 前者是**数据丢失**，后者是**本功能整个不发生**。两者都静默。
    if bi:
        # 正面：进度落盘走收口函数（拼接在 RagIndexStore 一处，调用方只报块数）
        check('saveProgress' in bi,
              "定期落盘走 RagIndexStore.saveProgress（前缀向量+全量正文的拼接收口一处）")
        # ⚠ 判据只圈**定期落盘那一块**（`if (failedIdx.isEmpty() && ...)` 的
        #   真括号配对范围），不是整个 buildIndex：收尾那次传 `rows` 是**对的**
        #   （那时 `rows` 已经是全量），圈进来会把正确写法判红 —— 而一条会把
        #   正确写法判红的判据只能被改成恒真（本仓库栽过的"锚错了量"）。
        per = re.search(r'if\s*\(failedIdx\.isEmpty\(\)\s*&&', bi)
        per_seg = ''
        if per:
            k = bi.find('{', per.start())
            depth = 0
            q = k
            while q < len(bi):
                if bi[q] == '{':
                    depth += 1
                elif bi[q] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                q += 1
            per_seg = bi[k:q]
        # 取每个调用点的**实参**（括号配对，不用 `[^)]*` —— 实参里嵌了
        # `partialMeta(...)`，非贪婪正则会在第一个 `)` 处截断）。
        args_list = []
        for m in re.finditer(r'(?:saveProgress|savePartial)\s*\(', per_seg):
            d2 = 0
            t = m.end() - 1
            while t < len(per_seg):
                if per_seg[t] == '(':
                    d2 += 1
                elif per_seg[t] == ')':
                    d2 -= 1
                    if d2 == 0:
                        break
                t += 1
            args_list.append(per_seg[m.end():t])
        # 反面①：进度落盘的实参里**不许**出现"只装到第 i 块"的那两个列表
        #   （`rows` = 已编过的那些块 / `slice` = 这一批）。
        bad_args = [a for a in args_list if re.search(r'\brows\b|\bslice\b', a)]
        check(not bad_args,
              "反面：进度落盘不许把 rows/slice 这类『只装到第 i 块』的列表当整份正文传"
              + ("（实际传了：%s）" % "; ".join(a.strip()[:60] for a in bad_args) if bad_args else ""))
        # 反面②：进度落盘必须**同时**给出全量正文（`texts`）—— 只给已编的那半份
        #   就是拿前缀当整份写，等于把没编到的那些字从 `texts.jsonl` 里删掉。
        # ⚠ 判据用"**裸** texts"（后面不跟 `.`）：参数表里还有 `texts.size`
        #   （喂给 partialMeta 的），那是**取长度**、不是交正文 —— 把两者当一件事
        #   会让这条判据恒真（本仓库栽过的"判据锚错了量"）。要的是那个
        #   **作为实参本身**传进去的全量正文列表。
        check(any(re.search(r'(?<![\w.])texts(?![\w.])', a) for a in args_list),
              "反面：进度落盘的实参里有 texts 全量正文 —— 只给已编的那半份就是删正文")
        check(bool(args_list), "进度落盘的调用点找得到（找不到 = 上面两条判据空转）")

    # ── ⑥ UI：状态五态收在一处，且可续排在正文已改之前 ─────────────────────
    check('RagResumeUi.state(' in act,
          "列表状态走 RagResumeUi.state（纯逻辑一处；两处各写一句必然漂移）")
    resume_ui = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                  '..', '..',
                                  'app/src/main/java/com/xiaowan/localinference/RagResumeUi.kt'),
                     encoding='utf-8').read()
    rui = strip_kt(resume_ui)
    rp = rui.find('resumable ->')
    bp = rui.find('!inSync ->')
    check(rp >= 0 and bp >= 0 and rp < bp,
          "RagResumeUi：可续**排在**正文已改之前（写反 = 可续永远显示成『正文已改』）")

    return 1 if bad else 0


if __name__ == '__main__':
    raise SystemExit(main())
