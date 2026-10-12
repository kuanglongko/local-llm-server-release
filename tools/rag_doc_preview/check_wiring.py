#!/usr/bin/env python3
"""「正文框装一片、按行偏移回写」的接线判据（P1-a + P1-b，ISSUE #228）。

═══════════════════════════════════════════════════════════════════════════
这一组守的是什么
═══════════════════════════════════════════════════════════════════════════
用户报的是*"输入框的预览和逐条预览这个，还能优化么，现在还会卡很久"*。

上一轮（0.9.233 的 P0+P3）动的是"**要等多久 / 做多少次**"；这一轮（P1-a）
动的是那个框**本身**：它此前要一次装下**整个库**，而 `EditText` 装多少就排
多少版（全量 `StaticLayout`），于是库一大，"点开库"这个动作就要等几秒到
十几秒。截断（`RagUiCap.MAX_DOC_CHARS`）只是把最坏那一档挡掉，门槛仍随
库的大小走。

P1-a 把它反过来：框只装**当前这一页**（与条目列表同一页），且**只读**。
编辑改走两处已经存在的路 —— 每条一个「删」、「追加」一条。

P1-b 再把"只读"这一点收回来：框**可以改**了，但保存换了另一条路 ——
按**行偏移**只回写改过的行（[RagIndexStore.replaceRowsInPlace]），
其余行与其余页的真值仍来自磁盘。于是"改字"重新可用，而"拿一页覆盖全库"
这条路径**从代码上不存在**。

═══════════════════════════════════════════════════════════════════════════
为什么这一族**必须**配套判据：它的失效形态全是"静默毁数据"
═══════════════════════════════════════════════════════════════════════════
框从"整库"变成"一页"之后，**所有**"把框里的内容当成要索引的全部"的出口
都变了语义。而它们一个都不抛异常：

  · `doBuildIndex` 拿**一页**去建 -> 静默切出一个只有 60 条的库，回执说
    "索引已重建"，屏幕上每一个字都在说成了，实际九成内容没进去；
  · `doSaveTexts`  / `confirmDiscardRagDoc` 的「保存」 拿**一页**去整份覆盖 ->
    `texts.jsonl` 被**整份覆盖成那一页**，磁盘上原来那份完整正文就此没了，
    且没有副本 —— 这是本仓库最贵的一类丢失；
  · 框里那一页与列表那一页**不是同一页** -> 用户按框里看到的内容去点「删」
    /「追加」/「保存本页改动」，改动落在一段他以为不是那里的行上；
  · P1-b 之后还多一条：回写靠的是"**画出去时**那一片是什么"（`ragDocSliceRows`
    / `ragDocSliceFirst`）。它若在保存那一刻现算，回写就落在**另一段行**上。

三件的共同点：**屏幕上一切正常**。只有静态判据能钉住它们不许回来。

判据一律锚**结构关系**（哪个状态字段决定走哪条路 / 谁在谁之前 / 在哪个函数
体内 / 取数走哪个函数），不锚"某某字符串出现过"（后者恒真：注释里就有）。
用法：python3 tools/rag_doc_preview/check_wiring.py <EngineActivity.kt> <RagUiCap.kt>
"""
import re
import sys


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def fnbody(src, sig):
    """取函数体（真括号配对，认字符串字面量）。

    ⚠ 找 `{` 必须**跳过参数表外面的默认值花括号**：签名写成
      `private fun f(x: () -> Unit = {}) { ... }` 时，参数表里那个 `{}` 是
      默认值、**不是函数体**。直接 `find("{", i)` 会在它上面停住，于是取到的是
      `private fun f(... = {}` 这一截 —— 而判据会因为这个"锚点漂了"变成假红
      （或者更坏：找的东西恰好在那半截里，判据变成恒真）。
      所以先配对参数表的圆括号，再从它后面找第一个 `{`。
    """
    i = src.find(sig)
    if i < 0:
        return None
    par = src.find("(", i)
    if par < 0:
        return None
    depth_p = 0
    k = par
    while k < len(src):
        if src[k] == "(":
            depth_p += 1
        elif src[k] == ")":
            depth_p -= 1
            if depth_p == 0:
                break
        k += 1
    j = src.find("{", k + 1)
    if j < 0:
        return None
    depth = 0
    k = j
    in_str = None
    while k < len(src):
        c = src[k]
        if in_str:
            if c == "\\":
                k += 2
                continue
            if c == in_str:
                in_str = None
        elif c == '"':
            in_str = '"'
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
        k += 1
    return None


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def main():
    ea = sys.argv[1]
    cap = sys.argv[2] if len(sys.argv) > 2 else ""
    s = read(ea)
    c = read(cap) if cap else ""
    bad = []

    def need(cond, what):
        print(("PASS  " if cond else "FAIL  ") + what)
        if not cond:
            bad.append(what)

    # ══════════════════════════════════════════════════════════════════════
    # ① 框有两态，判据是**一个状态字段**，写点收在一处
    # ══════════════════════════════════════════════════════════════════════
    need(re.search(r"private var ragDocSliced = ", s) is not None,
         "① 框的两态是个**字段**（ragDocSliced），不是就地按库大小算")
    setter = fnbody(s, "private fun setRagDocSliced(")
    need(setter is not None, "① setRagDocSliced() 存在（两态的唯一写点）")
    set_c = strip_comments(setter or "")
    # P1-b：两态**都**可编辑 —— 出错的从来不是"能不能敲"，是"敲完之后拿哪一份当
    # 真值"。所以这一处要守的是"两个态都没把框锁死"（P1-a 立的那套只读属性
    # 必须**整个去掉**：留着就是"属性说可编辑、实际敲不进去"的半态）。
    for prop in ("setFocusable(true)", "setCursorVisible(true)", "inputType",
                 "setSingleLine(false)", "keyListener"):
        need(prop in set_c, f"① 两态都放回可输入能力：{prop}（少一个就是半可输入）")
    need("setFocusable(false)" not in set_c and "keyListener = null" not in set_c,
         "① **没有**把框锁死的动作（P1-a 的只读属性已整个去掉）")
    need("setEnabled(false)" not in set_c,
         "① 不用 setEnabled(false)（那会吞掉点击，复制都做不了）")

    # ══════════════════════════════════════════════════════════════════════
    # ② 这一片取的是**当前这一页**，与条目列表同源
    # ══════════════════════════════════════════════════════════════════════
    need("fun docPreviewRange(" in c, "② RagUiCap.docPreviewRange 存在（区间唯一实体）")
    # ⚠ 锚**表达式那一行**，不用 fnbody：`docPreviewRange` 是表达式体函数（没有
    #   `{`），fnbody 会一路吃到下一个函数的体里 —— 那样 "rowPageRange" 会因为
    #   别的函数用了它而恒真（判据变成恒绿，等于没写）。
    line = ""
    for ln in c.splitlines():
        if "fun docPreviewRange(" in ln:
            line = ln
            break
    need("rowPageRange" in line,
         "② 这一片的区间**复用** rowPageRange（与条目列表同源，不另算一份页）")
    need("fun docPreviewText(" in c, "② RagUiCap.docPreviewText 存在（这一片的拼法唯一实体）")
    need("fun docEditLines(" in c,
         "② RagUiCap.docEditLines 存在（拆行唯一实体 —— 与 docPreviewText 互逆）")
    # 这一片必须跟着列表一起画（同一跳）—— 否则"列表在第 3 页、框里还在第 1 页"。
    rows = fnbody(s, "private fun renderRagRows(")
    rows_c = strip_comments(rows or "")
    need("renderRagDocSlice()" in rows_c,
         "② 这一片在 renderRagRows 里画（与列表同一页、同一跳）")
    prv = fnbody(s, "private fun renderRagDocSlice(")
    prv_c = strip_comments(prv or "")
    need("RagUiCap.docPreviewRange" in prv_c, "② 区间走 RagUiCap.docPreviewRange")
    need("RagUiCap.docPreviewText" in prv_c, "② 拼法走 RagUiCap.docPreviewText")
    need("RagIndexStore" not in prv_c,
         "② 画这一片**不做任何 IO**（数据来自内存里的 ragRowsShown）")

    # ══════════════════════════════════════════════════════════════════════
    # ②b P1-b：把"画出去的那一片是什么"**留下来**，且**只**在这一跳写
    # ══════════════════════════════════════════════════════════════════════
    need("private var ragDocSliceRows" in s and "private var ragDocSliceFirst" in s,
         "②b 画出去的那一片被记下来（行原值 + 首行行号）—— 回写的依据")
    need("ragDocSliceRows = rows" in prv_c and "ragDocSliceFirst =" in prv_c,
         "②b 记录**在这一跳**写（与 setText 同一次）")
    # 记录与 setText 的**先后**：先记再画（判据钉得住的小事，写反了将来会踩）。
    need(prv_c.find("ragDocSliceRows = rows") < prv_c.find("setRagDocText("),
         "②b 先记这一片、再 setText（顺序反了第一帧的记录与框里内容不同源）")
    # 切库时把记录清掉：留着它 = 拿着**上一个库**的行号去回写。
    load = fnbody(s, "private fun loadRagDoc(")
    load_c = strip_comments(load or "")
    need("ragDocSliceRows = emptyList()" in load_c,
         "②b 切库时把记录清掉（否则按上一个库的行号回写）")

    # ══════════════════════════════════════════════════════════════════════
    # ③ 「要索引/要落盘的全部」不再默认取框 —— 这是**毁数据**的那一半
    # ══════════════════════════════════════════════════════════════════════
    dbi = fnbody(s, "private fun doBuildIndex(")
    need(dbi is not None, "③ doBuildIndex() 存在（锚点未漂）")
    dbi_c = strip_comments(dbi or "")
    need("ragDocSliced" in dbi_c, "③ 建索引先看框的态（取正文口径按态分家）")
    need("startBuildDisk(name)" in dbi_c,
         "③ 切片态走 startBuildDisk（框里只有一页，取正文必须走磁盘）")
    head = dbi_c[:dbi_c.find("ragDocEntries(")] if "ragDocEntries(" in dbi_c else dbi_c
    slice_branch = head[head.find("if (ragDocSliced)"):] if "if (ragDocSliced)" in head else ""
    need(slice_branch != "" and "ragDocEntries(" not in slice_branch,
         "③ 切片态那一支**不取框**（拿一页去建 = 静默切出一个缺九成内容的库）")
    need("ragDocOwner" in dbi_c and "ragDocOwner != name" in dbi_c,
         "③ 切片态要求归属与库名一致（否则建出的库名字与内容对不上）")

    # ══════════════════════════════════════════════════════════════════════
    # ③b 保存：切片态走**按行偏移回写**，且**不许**出现整份覆盖
    # ══════════════════════════════════════════════════════════════════════
    sv = fnbody(s, "private fun doSaveTexts(")
    need(sv is not None, "③b doSaveTexts() 存在（锚点未漂）")
    sv_c = strip_comments(sv or "")
    need("ragDocSliced" in sv_c, "③b 保存先看框的态（切片态下框只有一页）")
    i_slice = sv_c.find("ragDocSliced")
    i_docs = sv_c.find("ragDocEntries(")
    need(i_slice >= 0 and (i_docs < 0 or i_slice < i_docs),
         "③b 切片态的分岔在**取正文之前**（先读进来再分岔 = 白干一次）")
    need("saveRagDocSlice()" in sv_c, "③b 切片态走 saveRagDocSlice（按行偏移）")

    # P1-b 的核心判据：回写那一跳走**按行偏移**那一族，不许出现 saveTexts / runRagSave。
    #
    # ISSUE #228 的新特性之后，"按行偏移"有两个入口，而**两个都对**：
    #   · `LlmEngine.rewriteRows` —— 重算改过那几行的**向量**并一起写回
    #     （索引仍然可用，无需重建）。这是**首选**的那条；
    #   · `RagIndexStore.replaceRowsInPlace` —— 只写正文（索引待重建）。
    #     它是 rewriteRows 的**退路**（编码失败 / 索引不同源 / 索引只是半份时），
    #     所以判据认"至少走了 rewriteRows"，而不是死认某一个函数名。
    #
    # ⚠ 判据为什么收在"走了 rewriteRows"而不是"必须出现 replaceRowsInPlace"：
    #   后者会让"改字即重算"这条新路**判红**（它走的是 rewriteRows，而
    #   replaceRowsInPlace 只作为退路出现在 LlmEngine 里、不在这一跳的体内）。
    #   而死认 `rewriteRows` 又会让退路那条判据消失 —— 所以下面两条一起看：
    #   ①必须走 rewriteRows（新路是首选）；②那一跳体内不许出现整份覆盖。
    slice_save = fnbody(s, "private fun runRagSliceSave(")
    need(slice_save is not None, "③b runRagSliceSave() 存在（按行回写的唯一落盘点）")
    ss_c = strip_comments(slice_save or "")
    need("LlmEngine.rewriteRows" in ss_c,
         "③b 回写走 LlmEngine.rewriteRows（只重算改过那几行的向量）")
    # 三态回执必须**分家**：把"改动在、只是要重建"说成失败会让用户重敲一遍
    #（更坏：去重导 —— 导入是追加合并，重导 = 条目翻倍）。
    # ⚠ 判据必须同时看到**三支**（`IndexRebuilt` / `TextsOnly` / `Failed`）——
    #   只数 `RewriteResult` 这个词会出现假绿：三个 `is ... ->` 里挂两个到
    #   同一个 `when` 分支上，词还在、分家已经没了。而"把 TextsOnly 并进 Failed"
    #   正是这一族最省事、也最贵的写法错误。
    need(all(k in ss_c for k in ("RewriteResult.IndexRebuilt",
                                 "RewriteResult.TextsOnly",
                                 "RewriteResult.Failed")),
         "③b 回写按 RewriteResult **三支**分开（IndexRebuilt / TextsOnly / Failed）")
    # 三支必须挂在**同一个** `when (res)` 上：三支分成两个 if 时，"先命中哪个"
    # 决定了回执是哪一句，而 `Failed` 排在前面就会把 TextsOnly 吃掉。
    need("when (res)" in ss_c, "③b 三支挂在同一个 when(res) 上（不许拆成先后判断）")
    need("saveTexts" not in ss_c and "runRagSave" not in ss_c,
         "③b 回写**不经过** saveTexts / runRagSave（整份覆盖 = 把库砍成一页）")

    # 计划必须来自 RagUiCap.docEditPlan（纯逻辑、宿主可测），而不是就地判。
    plan = fnbody(s, "private fun saveRagDocSlice(")
    need(plan is not None, "③b saveRagDocSlice() 存在")
    plan_c = strip_comments(plan or "")
    need("RagUiCap.docEditPlan" in plan_c,
         "③b 计划走 RagUiCap.docEditPlan（纯逻辑，宿主侧可单测）")
    need("ragDocSliceRows" in plan_c and "ragDocSliceFirst" in plan_c,
         "③b 计划用的是**记下来的**那一片（不是保存那一刻现算）")

    # `confirmDiscardRagDoc` 的「保存」也必须按态分家：切片态走按行回写，
    # 不许落进 runRagSave（那一条同样会把库砍成一页，而用户以为自己在"丢弃"）。
    dis = fnbody(s, "private fun confirmDiscardRagDoc(")
    need(dis is not None, "③b confirmDiscardRagDoc() 存在")
    dis_c = strip_comments(dis or "")
    need("ragDocSliced" in dis_c, "③b 切走时的「保存」也按态分家")
    # 那一支里必须走 runRagSliceSave（而不是 runRagSave）。
    need("runRagSliceSave(" in dis_c, "③b 切走时的「保存」在切片态走按行回写")

    # ══════════════════════════════════════════════════════════════════════
    # ③c 「编辑器内容 = 整个库」这个取数口在切片态**必须返回空**
    # ══════════════════════════════════════════════════════════════════════
    # 这是把"拿一页当全库"那条路**从入口堵死**：三个调用方（runRagSave /
    # autosavePendingKb / importEntries 那两条）都以 `isEmpty()` 收场，
    # 而不是拿着**一页**去整份覆盖。比"每个调用点判一次"可靠 —— 调用点会变多。
    ent = fnbody(s, "private fun ragDocEntries(")
    need(ent is not None, "③c ragDocEntries() 存在")
    ent_c = strip_comments(ent or "")
    need("ragDocSliced" in ent_c,
         "③c ragDocEntries 在切片态**拒绝**（返回空 = 堵死『拿一页当全库』的入口）")
    need("return emptyList()" in ent_c, "③c 拒绝的方式是返回空表（三个调用方都有 isEmpty 分支）")
    # ⚠ 拒绝必须在**取数之前**：先 `ragDocEt.text.split(...)` 再拒绝，读进来的是
    #   一整页，而判据只查"文件里出现过 return emptyList()" -> 那个写法**恒绿**。
    #   这一条钉的是相对次序（与 doSaveTexts / doBuildIndex 那两处同一形态）。
    i_guard = ent_c.find("ragDocSliced")
    i_read = ent_c.find("ragDocEt.text")
    need(i_guard >= 0 and (i_read < 0 or i_guard < i_read),
         "③c 拒绝在**取数之前**（先读进一页再拒绝 = 白干一次，且下一个人会接上去用）")

    # ══════════════════════════════════════════════════════════════════════
    # ④ 两态各自的入口都接上了（否则框停在错的那一态上）
    # ══════════════════════════════════════════════════════════════════════
    need("setRagDocSliced(true)" in load_c,
         "④ 打开一个**磁盘上的库** -> 切成一页态")
    newkb = fnbody(s, "private fun openNewRagKb(")
    newkb_c = strip_comments(newkb or "")
    need("setRagDocSliced(false)" in newkb_c,
         "④ 新建、还没落盘的库 -> 框就是全部（磁盘上没有副本）")

    # ══════════════════════════════════════════════════════════════════════
    # ⑤ 说明行：同源、且说清"这一片能改、改完怎么存"
    # ══════════════════════════════════════════════════════════════════════
    need("fun docEditableNote(" in c, "⑤ RagUiCap.docEditableNote 存在（说明的唯一实体）")
    note = fnbody(c, "fun docEditableNote(")
    nc = strip_comments(note or "")
    need("rowPageCount" in nc and "rowPageRange" in nc,
         "⑤ 说明里的页数与区间与列表同源（不另算一份）")
    need("if (pages <= 1)" in nc,
         "⑤ 只有一页时**不报页号**（噪音会让真多页那行被无视）")
    need("docEditableNote" in prv_c,
         "⑤ 说明行在这一片里画（按钮/说明/框三处同源）")

    # ══════════════════════════════════════════════════════════════════════
    # ⑥ 旧的那条"整库 clipDoc 回填"链**必须真的没了**
    # ══════════════════════════════════════════════════════════════════════
    # P1-a 之后没有任何一条路会造出"框里是某份文件的头部"这个状态。
    # 留着 `clipped = isClipped` 那一路 = 那条链的入口还在（下一个人会接上去）。
    need("clipped = isClipped" not in s and "clipDoc" not in strip_comments(load or ""),
         "⑥ 没有任何一条回填把框设成『被截的头部』（框装一页，不是头部）")
    # `loadRagDoc` 里不许再拼整库（那是"点开库要等"的根）。
    need("readEntries(" not in load_c.replace("readEntriesAligned(", ""),
         "⑥ loadRagDoc 不读整库（`readEntries(` 那一次 6 MB 拼接的起点）")

    print()
    if bad:
        print(f"=== 正文框装一片 + 按行回写（P1-a/P1-b）：FAIL {len(bad)} ===")
        return 1
    print("=== 正文框装一片 + 按行回写（P1-a/P1-b）：PASS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
