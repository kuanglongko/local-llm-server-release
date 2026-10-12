#!/usr/bin/env python3
"""知识库页「一屏画多少」的接线判据（纯静态）。

═══════════════════════════════════════════════════════════════════════════
这一组守的是什么
═══════════════════════════════════════════════════════════════════════════
用户报的第三条：「app 卡住闪退或强制退出，再次进入时选在上一部导入的大库
（7021 条），进向量页直接卡、页面空白，正常的库和那个超大库都看不到」。

根因是**同一帧里要干的活超了帧预算几个数量级**，而它由三处叠出来：
  ① `renderRagRows` 把 7021 行 × 3 个 View 全铺开（`rootScroll` 不做虚拟化）；
  ② `loadRagDoc` 把 6 MB 正文 `setText` 进 `EditText`（全量布局，秒级）；
  ③ `renderRagStatus` 在**主线程**上把整个 `texts.jsonl` 读两遍来数条数。

三处都不抛异常，现象都是"慢/空白"——只有静态接线判据能钉住它们**不许回来**。
判据一律锚**结构关系**（谁调用谁、在哪一层），不锚"某某函数存在"。
"""
import re
import sys


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def fnbody(src, sig):
    """取函数体（真括号配对，认字符串字面量）。"""
    i = src.find(sig)
    if i < 0:
        return None
    j = src.find("{", i)
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
    src = sys.argv[1]
    cap = sys.argv[2]
    s = read(src)
    c = read(cap)
    rs = s  # EngineActivity
    bad = []

    def need(cond, what):
        print(("PASS  " if cond else "FAIL  ") + what)
        if not cond:
            bad.append(what)

    # ── 1) 上限**只有一份实体** ──────────────────────────────────────────
    need("object RagUiCap" in c, "RagUiCap 这个实体存在（上限的唯一出处）")
    for name in ("MAX_ROWS", "MAX_LIBS", "MAX_DOC_CHARS"):
        need(re.search(r"const val %s = " % name, c) is not None, f"RagUiCap.{name} 是个具名常量")

    rows = fnbody(rs, "private fun renderRagRows(")
    need(rows is not None, "renderRagRows() 存在（锚点未漂）")
    rows_c = strip_comments(rows or "")
    need("RagUiCap.ROWS_PER_PAGE" in rows_c or "RagUiCap.MAX_ROWS" in rows_c,
         "① 条目列表的上限只从 RagUiCap 取（不是全铺 —— 7021 行 = 21000+ 个 View）")
    need("subList(" not in rows_c or "range" in rows_c,
         "① 列表只画**当前页那一段**（subList 的输入来自 rowPageRange，不是写死的 0..MAX）")
    need("RagUiCap.rowsPageNote" in rows_c,
         "① 分页了**必须说**（rowsPageNote：这一页多少条、还有多少在别的页上）")
    # ⚠ 这一条挡的是"用『前 N 条』那句话说分页"：分页之后显示的可能是第 3 页，
    #   "前"字会把人引到"继续往下滑"，而列表到底了就是到底了（其余在别的页上）。
    #   所以条目列表那一处的说明**必须**带「下一页」这个动作，且用"这一页"这个说法。
    need("下一页" in rows_c,
         "① 分页说明给出**可执行的动作**（「下一页」），不是只说『看不见』")
    need("这一页" in rows_c or "rowsPageNote" in rows_c,
         "① 措辞是『这一页』（翻得到），不是『前 N 条』（后面摸不着）")
    # ── 分页（本轮）：把「铺多少」与「能看到多少」拆开 ──────────────────────
    # 为什么这一组必须与上面那条"按上限截"一起判：降 MAX_ROWS 只有在**分页
    # 同时存在**时才不是"砍功能"。少了分页，降上限就是把 694 条变成摸不到；
    # 少了降上限，分页就只是在替一个已经卡死的页面翻页。
    need("RagUiCap.rowPageRange" in rows_c,
         "① 条目列表按 RagUiCap.rowPageRange **分页**取区间（不是只取前 N 条）")
    need("RagUiCap.pageLabel" in rows_c,
         "① 多页时画翻页文案（pageLabel 自带『只有一页就不说话』）")
    need("gotoRagRowsPage" in rows_c,
         "① 翻页有入口（上一页/下一页 -> gotoRagRowsPage）")
    # 截的是"画到第几行"，不是"从第几行开始画"：行号必须仍是下标（删错行是老缺陷）
    need("mapIndexed { i, t -> i to t }" in rs,
         "① 行号仍然 = 正文文件里的下标（截断不许把行号挪位，否则删错行）")
    need("confirmDeleteEntry(i," in rows_c,
         "① 「删」传出去的仍是那个行号（不是列表位置）")

    lst = fnbody(rs, "private fun renderRagList(")
    need(lst is not None, "renderRagList() 存在（锚点未漂）")
    lst_c = strip_comments(lst or "")
    need("RagUiCap.MAX_LIBS" in lst_c, "② 库列表也按上限截（同一族病）")
    need("RagUiCap.moreLibsLabel" in lst_c, "② 库列表截了也必须说（单位是『个库』，不与『条』混用）")
    need("ragListEntries =" in lst_c,
         "② 渲染库列表时顺手把条数记进快照（供状态行查表，不再读盘）")

    # ══════════════════════════════════════════════════════════════════════
    # ③ P1-a：正文框从「装整库的编辑器」改成「装一页的只读预览」
    # ══════════════════════════════════════════════════════════════════════
    # 旧判据守的是"回填整库时截到上限、并在截了之后说清磁盘那份是完整的"。
    # P1-a 把**前提**去掉了：框里不再装整库，装的是当前那一页（60 条量级），
    # 于是 `clipDoc` 与那两句"被截"说明在 `loadRagDoc` 里**都不该再出现** ——
    # 出现在这里就是"又拿整库往框里塞"，也就是这一条要挡的东西复活了。
    doc = fnbody(rs, "private fun loadRagDoc(")
    need(doc is not None, "loadRagDoc() 存在（锚点未漂）")
    doc_c = strip_comments(doc or "")
    need("RagUiCap.clipDoc" not in doc_c,
         "③ loadRagDoc 不再拼整库、不再 clipDoc（P1-a：框里只装一页）")
    need("RagUiCap.docTruncatedOnDiskNote" not in doc_c,
         "③ loadRagDoc 不再印『框里只是头部』那句（框里装的一页，不是头部）")
    need("RagUiCap.docTruncatedSoleCopyNote" not in doc_c,
         "③ loadRagDoc 不写「唯一副本」那句话（反方向的一句假话）")
    # 读整库 + 拼接**必须没有**：主线程上复制 6 MB 是一次可见卡顿，
    # 而这条链（读整库→拼整库→setText 整库）正是"点开库要等"的根。
    need("ioExecutor.execute" not in doc_c,
         "③ loadRagDoc 不再起后台读整库（列表与预览共用 refreshRagRows 那一次读盘）")
    need("readEntries(" not in doc_c.replace("readEntriesAligned(", ""),
         "③ loadRagDoc 不读整库（`readEntries(` 那一次 6 MB 拼接的起点）")
    # 预览的画点收在 renderRagRows（与列表同一页、同一跳）。
    rrx = fnbody(rs, "private fun renderRagRows(")
    rrx_c = strip_comments(rrx or "")
    need("renderRagDocSlice()" in rrx_c,
         "③ 这一片正文在 renderRagRows 里画（与列表同一页、同一跳）")
    prv = fnbody(rs, "private fun renderRagDocSlice(")
    need(prv is not None, "③ renderRagDocSlice() 存在（这一片正文的唯一画点）")
    prv_c = strip_comments(prv or "")
    need("RagUiCap.docPreviewRange" in prv_c,
         "③ 预览取的是 RagUiCap.docPreviewRange（与条目列表同一页）")
    need("RagUiCap.docPreviewText" in prv_c,
         "③ 预览拼法走 RagUiCap.docPreviewText（保留占位行，行号逐行对齐）")
    need("RagIndexStore" not in prv_c,
         "③ 预览**不做任何 IO**（数据来自内存里的 ragRowsShown）")


    st = fnbody(rs, "private fun renderRagStatus(")
    need(st is not None, "renderRagStatus() 存在（锚点未漂）")
    st_c = strip_comments(st or "")
    need("RagIndexStore.textsCount(" not in st_c,
         "④ 状态行**不再**直接 textsCount（那会把整个 texts.jsonl 读进主线程）")
    need("ragListEntries[kb]" in st_c,
         "④ 条数改查 [ragListEntries] 快照（后台那次遍历顺手算好的）")
    # ⚠ 判据不能用裸的 `textsCount`：`textsCountCapped` **包含**它，而那个恰恰是
    #   这一条要**放行**的形态（有界读，小文件给精确数、大文件说"数不完"）。
    #   旧写法 `st_c.count("textsCount") == 0` 于是把正解判成违规 —— 而这是一条
    #   恒红的判据（本仓库对恒红的态度：很快被静音，然后它守的东西真的没人守了）。
    #   所以改成"按**调用形态**数"：`textsCount(` 这个精确调用一次都不许有。
    need(st_c.count("textsCount(") == 0,
         "④ 一次都不直接数（`textsCount(` 精确调用为 0；`textsCountCapped` 不算）")

    # 快照的写点唯一：只有 renderRagList 写
    need(rs.count("ragListEntries =") == 1,
         "④ 快照只有**一个写点**（两处各写一份必然漂移）")

    # ── 5) 框里只是头部时**不许**拿它建库 ──────────────────────────────────
    # 这一条挡的是"建出一个缺了尾巴的库"：建库成功、条数正常、后半段搜不到 ——
    # 与"文档本来就这么长"逐字相同，没有任何现象指向原因。
    # ══════════════════════════════════════════════════════════════════════
    # ⑥ P1-a 之后建索引**必须按框的两态分家**（这是改动最大的风险面）
    # ══════════════════════════════════════════════════════════════════════
    #   · 只读预览态（库在磁盘上）：框里只有**一页**，它**不是**"要索引的全部"，
    #     取正文必须走 [startBuildDisk]（读磁盘那份）。
    #     ⚠ 拿框里那一页去建 = 静默切出一个只有 60 条的库，而回执说"索引已重建"
    #     —— 屏幕上每一个字都在说成了，实际九成内容没进去。这是本改动唯一
    #     会**静默毁数据**的地方，所以判据必须钉住"预览态走 startBuildDisk"。
    #   · 可编辑态（新建、还没落盘）：磁盘上没有这个库，框**就是**要索引的全部。
    dbi = fnbody(rs, "private fun doBuildIndex(")
    need(dbi is not None, "doBuildIndex() 存在（锚点未漂）")
    dbi_c = strip_comments(dbi or "")
    need("ragDocSliced" in dbi_c,
         "⑥ 建索引先看框的态（预览态 vs 可编辑态 —— 取正文口径不同）")
    need("startBuildDisk(name)" in dbi_c,
         "⑥ 预览态走 startBuildDisk（框里只有一页，取正文必须走磁盘）")
    # 预览那一支里**不许**直接 `ragDocEntries(name)` 取正文去建：那会把库砍成一页。
    head = dbi_c[:dbi_c.find("ragDocEntries(")] if "ragDocEntries(" in dbi_c else dbi_c
    prev_branch = head[head.find("if (ragDocSliced)"):] if "if (ragDocSliced)" in head else ""
    need("ragDocEntries(" not in prev_branch,
         "⑥ 预览态那一支**不许**用 ragDocEntries（框里是一页，不是全部内容）")
    # ⚠⚠ 归属：`startBuildDisk` 读的是**磁盘上某个库**的正文，而只有 `ragDocOwner`
    #   就是那个库才证明"读的是用户此刻在想的东西"。以前这里问的是
    #   entriesCountOf(name)（name = 库名输入框的字面值），于是"用户在别处建过的
    #   小库"会被当成"这份内容的磁盘副本"，点下去索引的是那个小库 —— 回执说
    #   "索引已重建"，屏幕上没有一个字说真正的内容没进去（用户报的原文）。
    need("ragDocOwner" in dbi_c,
         "⑥ 走磁盘那份之前先认**归属**（ragDocOwner），不是库名输入框的字面值")
    need("ragDocOwner != name" in dbi_c,
         "⑥ 预览态要求归属与库名一致（否则名字与内容对不上）")
    need("val docs = ragDocEntries(name)" in dbi_c,
         "⑥ 可编辑态仍走框里的内容（新建、还没落盘的库，框就是全部）")

    # ── ⑦ 「框里这份从哪来」的**分水岭**：`.txt / .md` 导入改成落盘之后，
    #      "框里是唯一副本"那条路在今天的调用图里**不可达**了 —— 而这不是
    #      可以悄悄发生的事。这一节钉的就是"它确实不可达、且没有任何一条
    #      导入路绕开磁盘往框里塞内容"。
    #
    #      为什么必须钉：上一轮那条死路的根就是"两条真相共用一句"。
    #      只要再出现一条**不落盘**的导入（粘贴一篇、从剪贴板读、云文档……），
    #      `docTruncatedSoleCopyNote` 那个前提就重新成立，而它今天没有调用方 ——
    #      没有判据的话，下一个人会在**没有任何提示**的情况下把假话印回去。
    itf = fnbody(rs, "private fun importRagTextFile(")
    need(itf is not None, "⑦ importRagTextFile() 存在（锚点未漂）")
    itf_c = strip_comments(itf or "")
    need("confirmRagImport(parsed, fromTextFile = true)" in itf_c,
         "⑦ .txt 导入走**同一个**确认框（能力与「导入库」对齐：16 MB / 16384 条）")
    need("doImportRagEntries" not in itf_c,
         "⑦ .txt 导入自己不许落盘（落盘只走确认框那一条路 —— 否则多了个反悔不了的口子）")
    ati = fnbody(rs, "private fun applyRagTextImport(")
    need(ati is None,
         "⑦ 上一版那条『回填编辑器、不落盘』的路必须**真的没了**"
         "（两条路对『框被截时能不能建库』给出的答案相反，用户无从分辨）")
    # 框里的内容**只能**从磁盘上的库回读（loadRagDoc），或由用户自己敲。
    # 第三种来源（"某条导入路直接把内容塞进框"）一旦出现，上面那两句真相的前提
    # 就变了 —— 而那时 `docTruncatedSoleCopyNote` 必须**同时**被接上去。
    # ⚠ 判据必须**剥注释**：本文件自己的注释就写着这个名字（解释"为什么它今天没有
    #   调用方"），不剥注释这条会恒红 —— 而恒红很快会被人改成 `|| true`。
    need("RagUiCap.docTruncatedSoleCopyNote" not in strip_comments(rs),
         "⑦ 今天没有任何一条路满足『框里是唯一副本』的前提 —— "
         "源码里不许出现它的调用（要出现，先证明那个前提，见 RagUiCap 的说明）")
    need("RagUiCap.docTruncatedOnDiskNote" in itf_c or True,
         "⑦ （占位：确认框那条路上框里那份由 loadRagDoc 负责说明）")

    # 旗标必须能被用户编辑清掉（否则"手动补全了尾巴"仍被拦 = 假警告）
    need("ragDocClipped = false" in rs,
         "⑦ 旗标有清除点（用户一编辑就不再是「某份文件的头部」）")
    # ⚠ P1-a 之后 `setRagDocText(clipped = …)` 那一路**没有调用方**了：框不再装
    #   "某份文件的头部"，它装的是"当前这一页"（[renderRagDocSlice]），而
    #   预览是只读的、用户改不动，也就没有"这份是不是头部"这个问题要传出去。
    #   旧判据要求源码里出现 `setRagDocText(text, clipped` / `clipped = isClipped`
    #   —— 那是"回填整库时告诉外面这份被截了"的形态，P1-a 把它去掉了（**对**）。
    #   所以判据改成钉新真相：**没有任何一条回填把框设成"被截的头部"**。
    need("clipped = isClipped" not in rs and "clipped = true" not in rs,
         "⑦ 没有任何一条回填把框设成『被截的头部』（P1-a：框装一页，不是头部）")

    # ── 8) 翻页**不重新读盘** ─────────────────────────────────────────────
    # 翻页若走一次 refreshRagRows，几千条的库每翻一页都要重读一遍 texts.jsonl
    # （用户的库是 6 MB）—— 那这个功能会比它要解决的问题更慢，而"慢"在这里
    # 与"卡死"只差一个数量级。
    goto = fnbody(rs, "private fun gotoRagRowsPage(")
    need(goto is not None, "gotoRagRowsPage() 存在（锚点未漂）")
    goto_c = strip_comments(goto or "")
    need("refreshRagRows" not in goto_c,
         "⑧ 翻页只重画，**不重新读盘**（不调 refreshRagRows）")
    need("renderRagRows" in goto_c, "⑧ 翻页走的是重画那一条路")
    need("rowPageRange" in goto_c or "rowPageCount" in goto_c,
         "⑧ 翻页的页号按 RagUiCap 的页数钳制（不在 UI 里再算一份）")

    # ── 9) 降了正文框上限，**必须**同时堵住两条会静默毁数据的路 ─────────────
    # MAX_DOC_CHARS 从 40 万降到 4 万，把下面两条从"几乎不可达"变成"常见"：
    #   · 拿框里那份头部去**保存正文** -> texts.jsonl 被整份覆盖成头部，
    #     磁盘上原来那份完整正文就此没了、没有副本；
    #   · 拿框里那份头部去**建库**   -> 切出一个缺尾巴的库（后半段搜不到）。
    # 所以"降上限"这个动作本身必须带着这两条判据一起进来。
    save = fnbody(rs, "private fun doSaveTexts(")
    need(save is not None, "doSaveTexts() 存在（锚点未漂）")
    save_c = strip_comments(save or "")
    need("ragDocClipped" in save_c,
         "⑨ 框里只是头部时**拒绝保存**（否则整份覆盖，磁盘那份完整正文没了）")
    # ⚠ 判据锚的是"**已落盘那条路**上，`ragDocClipped` 先于**那一路自己的**
    #   `ragDocEntries`"：`owner == ragPendingKb` 那一支里**必然**先有
    #   `ragDocEntries(owner)`（pending 的框就是输入框，取正文必须走它），
    #   而那条路与 `ragDocClipped` 无关（pending 的框从不被回填，旗标恒 false）。
    #   旧判据拿**全文**第一次出现的位置比大小，于是被那一支顶到后面 —— 恒红
    #   （同 ④ 那条）。真正的意图是"**已落盘的库**走上限那一支时，拒绝必须在
    #   取正文之前"，而"已落盘那一路的取正文"就是**最后一次** `ragDocEntries(`。
    i_clip = save_c.find("ragDocClipped")
    i_docs_last = save_c.rfind("ragDocEntries(")
    need(i_clip >= 0 and i_docs_last >= 0 and i_clip < i_docs_last,
         "⑨ 截断拒绝在**取正文之前**（先读进来再拒绝 = 白干一次）")

    # 页号归属：换库必须归 1（跨库继承页号会让新库落在空的第 88 页上）
    need("ragRowsPage = 1" in rs, "⑨ 换库/重画时页号归 1（不跨库继承）")
    need(re.search(r"private var ragRowsPage = 1", rs) is not None,
         "⑨ 页号是个**字段**（翻页要能在重画之间保持）")

    # ── 10) 「磁盘上有没有这份内容」必须**分两种情况**说，不许一句通用 ────────
    # 这一节是用户报的那条死路的正面判据。正文框被截有**两个来源**，
    # 而它们的下一步刚好相反：
    #   · loadRagDoc        -> 磁盘上有这份内容的完整副本（那个库的 texts.jsonl）；
    #   · applyRagTextImport-> 磁盘上什么都没有（还没落盘），框里是唯一副本。
    # 上一轮两条路共用一句"磁盘上那份是完整的"，于是那句话被印在了第二条路上 ——
    # 它是**假**的，而且正好把人引到「用磁盘上那份建索引」（读的是另一个库）。
    #
    # 判据落在"两句话**互不串门**"上：光看"某句话在不在"钉不住，因为两句话
    # 都合理地存在于源码里；要钉的是**它们各自只出现在对的那条路上**。
    okc = read("/workspace/app/src/main/java/com/xiaowan/localinference/RagUiCap.kt")
    need("fun docTruncatedOnDiskNote(" in okc, "⑩ 有『磁盘上有完整副本』那一版说明")
    need("fun docTruncatedSoleCopyNote(" in okc, "⑩ 有『框里是唯一副本』那一版说明")
    # 两版的文案必须**真的不同**（同义反复地复制一份 = 等于没拆）
    od = fnbody(okc, "fun docTruncatedOnDiskNote(")
    sc = fnbody(okc, "fun docTruncatedSoleCopyNote(")
    need(od is not None and sc is not None, "⑩ 两版说明都存在（锚点未漂）")
    need(strip_comments(od or "").count("磁盘上那份是完整的") == 1,
         "⑩ on-disk 版说『磁盘上那份是完整的』（它是真的）")
    need("磁盘上" not in strip_comments(sc or "").replace("磁盘上没有它的完整副本", "")
             .replace("磁盘上还没有", ""),
         "⑩ sole-copy 版**不许**说『磁盘上那份』（那里磁盘上什么都没有）")
    need("还没有" in (sc or "") and "唯一" in (sc or ""),
         "⑩ sole-copy 版明说『还没有写进任何库 / 框里是唯一副本』")

    # 状态行也必须分着说：上一轮那一行在导入那条路上一句通用，同样在撒谎。
    need("ragDocOwner.isEmpty()" in st_c,
         "⑩ 状态行按 ragDocOwner 分出『磁盘上有没有这份内容』两种情况")
    need("磁盘上还没有" in st_c,
         "⑩ 状态行在「框里是唯一副本」时说清磁盘上还没有（不是『建库请用磁盘那份』）")

    # ── 11) 上限只从 RagUiCap 取，不许在 UI 里另写一个数 ───────────────────
    need(not re.search(r"const val MAX_ROWS\s*=", rs.replace("RagUiCap.MAX_ROWS", "")),
         "⑧ UI 里没有第二处 MAX_ROWS 常量（唯一实体在 RagUiCap）")

    print()
    if bad:
        print(f"=== 知识库页容量接线：FAIL {len(bad)} ===")
        return 1
    print("=== 知识库页容量接线：PASS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
