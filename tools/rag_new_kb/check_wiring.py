#!/usr/bin/env python3
"""「新建库 + 目标库取当前选中」的接线判据（纯静态，ISSUE #259）。

═══════════════════════════════════════════════════════════════════════
这一组守的是什么
═══════════════════════════════════════════════════════════════════════
用户三条诉求里的第 ② ③ 条：

  ② *"现在的导入 txt 文本逻辑有问题，他现在默认将 txt 文本名字，当做导入的
     库名，这与导入 jsonl 的逻辑不一样，导致追加到现有库只能通过事先改到与
     库名一样来实现，我觉得正确逻辑应该是与导入 jsonl 一样，选中库，然后执行导入"*；

  ③ *"新建库时，我认为，文本框应该默认为空，而不是保留上一个库的内容误导用户，
     而且新建库后应该在库列表里有体现，马上生成一个选项，如果本次未添加内容，
     那么默认丢弃，如果执行了手动输入（粘贴），或者文本倒入，或者库导入，
     那么库应该立即保存"*。

两者的失效形态都是**静默**的：
  · 目标库取错 -> 用户以为在往 A 追加，磁盘上却多了一个"文件名"库 / 或者
    静默建出一个新库（A 里一条没多，而屏幕上两句提示都说"成了"）；
  · 新建库不清正文框 -> 下一步「建库/重建」会把**上一个库的正文建成这个新库**
    （建库成功、条数正常、搜到的是别的内容）；
  · 新建库不立即落盘 -> 库名只活在内存里，用户一离开就没了（而界面上什么都没说）；
  · 未添加内容不丢弃 -> 留下一个点不动、删不掉的幽灵行。

判据一律锚**结构关系**（谁调用谁 / 谁设置哪个状态 / 在哪个函数体内），
不锚"某某字符串出现过"（后者恒真：注释里就有）。
用法：python3 tools/rag_new_kb/check_wiring.py <EngineActivity.kt> <RagArchive.kt>
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
    act_p, ra_p = sys.argv[1], sys.argv[2]
    act = read(act_p)
    ra = read(ra_p)
    bad = []

    def need(cond, what):
        print(("PASS  " if cond else "FAIL  ") + what)
        if not cond:
            bad.append(what)

    # ═══ ① 目标库：两条导入路**同一条规则** ═══════════════════════════════
    # 规则：信封带的 library 优先 -> 否则当前选中的库。判据锚在**取值链**上，
    # 不锚"函数里出现过 fromCurrent"（那在别处也成立）。
    cfi = fnbody(act, "private fun confirmRagImport(")
    cfi_c = strip_comments(cfi or "")
    need(cfi is not None, "confirmRagImport() 存在（锚点未漂）")
    need("fromEnvelope" in cfi_c and "fromCurrent" in cfi_c,
         "① 目标库的两级来源都在（信封库名 / 当前选中的库）")
    need(re.search(r"fromEnvelope \?: fromCurrent", cfi_c) is not None,
         "① 取值顺序 = 信封库名 ?: 当前选中的库（两条路共用这一条）")
    # ⚠ 反向：不许再凭空兜底一个用户没要的库名 —— 它会让"我明明选了库"与
    #   "App 给我编了一个库"在屏幕上长得一样，而后者会静默建出新库。
    need("导入的库" not in cfi_c,
         "① 不再凭空兜底『导入的库』（那会静默建出用户没要的库）")
    # 目标库的**来路**必须在点「导入」之前说清（.txt 那条路的先验正好是反的）。
    need("你当前选中的库" in cfi_c and "文件里带的库名" in cfi_c,
         "① 确认框里说清目标库的来路（信封 / 当前选中）")
    need("还没有选中任何库" in cfi_c,
         "① 没选库时明说『要自己填一个』（不静默落默认库）")

    dpt = fnbody(ra, "fun decodePlainText(")
    need(dpt is not None, "decodePlainText() 存在（锚点未漂）")
    # ⚠ `.txt/.md` 的目标库**不在解码里定**：文件名只进来源标签。
    need(re.search(r"return Parsed\(out, \"\", 0, null", strip_comments(dpt or "")) is not None,
         "① 纯文本解码回填空库名（目标库由 UI 侧按同一规则取）")
    # ⚠ 必须**剥注释**：解释"为什么不再那么干"的注释里必然带着那个旧函数名，
    #   不剥就是恒红（而恒红的判据很快会被静音，见 plain_import 自测顶部那段）。
    need("fun plainLibName" not in strip_comments(ra),
         "① 旧的『文件名当库名』函数已删（不许两套语义并存）")
    need("plainSourceLabel(name)" in (dpt or ""),
         "① 文件名只进**来源标签**（它是来源，不是目标库）")

    # ═══ ② 新建库 = 先清正文框（不许留着上一个库的内容）═══════════════════
    onk = fnbody(act, "private fun openNewRagKb(")
    onk_c = strip_comments(onk or "")
    need(onk is not None, "openNewRagKb() 存在（锚点未漂）")
    need("ragPendingKb = name" in onk_c,
         "② 新建库进 pending（磁盘上还没有它 —— 见 ragPendingKb 的说明）")
    need("setRagDocText(\"\")" in onk_c,
         "② 新建库**清空正文框**（用户报的『保留上一个库的内容误导用户』）")
    need("renderRagRows(emptyList())" in onk_c,
         "② 新建库清空条目列表（留着上一个库的行会让『删』删向不存在的行号）")
    # ⚠ 表里那些数是『磁盘上有几条』的真值快照；把没落盘的库塞进去 = 状态行报
    #   一个磁盘上不存在的库的条数。渲染 pending 那一行必须在**写快照之后**，
    #   而且不碰它。这一条靠"渲染体里没有 ragListEntries =" 来钉。
    rl = fnbody(act, "private fun renderRagList(")
    rl_c = strip_comments(rl or "")
    need(rl is not None, "renderRagList() 存在（锚点未漂）")
    need("ragPendingKb" in rl_c,
         "② 库列表里画出那个**未落盘**的新库（用户要的『马上生成一个选项』）")
    need("未落盘" in rl_c,
         "② 那一行必须明说『未落盘』（与一个真的空库分不开就该说清）")
    need("ragListEntries =" not in rl_c.split("ragPendingKb")[-1],
         "② pending 不进『库名 -> 条数』快照（那是磁盘真值，它还没落盘）")

    # ═══ ③ 提交库名 = 名字与正文框**成对切换** ═════════════════════════════
    ck = fnbody(act, "private fun commitRagKbName(")
    ck_c = strip_comments(ck or "")
    need(ck is not None, "commitRagKbName() 存在（锚点未漂）")
    need("switchRagDocTo(safe)" in ck_c,
         "③ 提交库名之后**切换正文框**（只改名字会让『框里是 A、库名是 B』常见化）")
    need("safe == ragDocOwner" in ck_c,
         "③ 名字没变就不重读（失焦是高频事件，重读会把光标位置清掉）")
    srt = fnbody(act, "private fun switchRagDocTo(")
    srt_c = strip_comments(srt or "")
    need(srt is not None, "switchRagDocTo() 存在（锚点未漂）")
    need("isPresent(this, name)" in srt_c,
         "③ 磁盘上有就读它的正文、没有就当新建（分水岭 = isPresent）")
    need("openNewRagKb(name)" in srt_c and "loadRagDoc(name)" in srt_c,
         "③ 两条分支各自走到位（已有库 -> loadRagDoc；新库 -> openNewRagKb）")
    need("confirmDiscardRagDoc" in srt_c,
         "③ 切换要过『未保存改动』那道闸（切换会把正文框整段换掉）")
    # 同一个库再切一次 = 什么都不做（否则 openNewRagKb 会清空用户刚粘的内容）。
    need(re.search(r"if \(name == ragDocOwner\)", srt_c) is not None,
         "③ 已经在那个库上时什么都不做（重复点会清空还没落盘的内容）")

    # ═══ ④ 有内容立即落盘；没内容默认丢弃 ═════════════════════════════════
    ms = fnbody(act, "private fun maybeSchedulePendingAutosave(")
    ms_c = strip_comments(ms or "")
    need(ms is not None, "maybeSchedulePendingAutosave() 存在（锚点未漂）")
    need("ragDocOwner != pending" in ms_c,
         "④ 只给『当前那个 pending』排落盘（不许把上一个库的正文写进新库）")
    need("ragPendingSaveGen" in ms_c,
         "④ 用代际作废**已经跑在后台**的那一跳（removeCallbacks 挡不住它）")
    ap = fnbody(act, "private fun autosavePendingKb(")
    ap_c = strip_comments(ap or "")
    need(ap is not None, "autosavePendingKb() 存在（锚点未漂）")
    need("docs.isEmpty()" in ap_c and "return" in ap_c,
         "④ 空内容**不落盘**（『本次未添加内容默认丢弃』）")
    need("RagIndexStore.importEntries" in ap_c,
         "④ 落盘走 importEntries（与『导入库』同一个函数：建目录 + 写正文，不动索引）")
    need("myGen != ragPendingSaveGen" in ap_c,
         "④ 落地前复核代际（旧内容不许把新内容盖掉）")
    need("pendingKbLanded()" in ap_c,
         "④ 落盘成功后 pending 才结束（清早了就是『看着在、其实没有』）")
    # ⚠ 光有调用还不够：`pendingKbLanded` 自己**必须真的清掉那个字符串** ——
    #   一个只刷列表不清状态的"落地"函数会让库名永远挂在界面上（列表里
    #   同时出现"未落盘"那一行与磁盘上那个真库，两行同名）。
    pl = strip_comments(fnbody(act, "private fun pendingKbLanded(") or "")
    need(pl is not None and "ragPendingKb = \"\"" in pl,
         "④ pendingKbLanded() 真的清掉了那个名字（不是只刷一下列表）")
    need("written <= 0" in ap_c,
         "④ 落盘失败必须出声（失败与『还没落盘』在界面上一模一样）")
    # 空内容那条路：不建目录。断言那一段里没有 mkdir/dirOf。
    empty_guard = ap_c.split("if (docs.isEmpty())")[1].split("ioExecutor")[0] \
        if "if (docs.isEmpty())" in ap_c else ""
    need("mkdir" not in empty_guard and "dirOf" not in empty_guard,
         "④ 空内容那一条不建目录（否则『丢弃』会留下点不动的幽灵行）")

    cl = fnbody(act, "private fun clearPendingKb(")
    cl_c = strip_comments(cl or "")
    need(cl is not None and "ragPendingKb = \"\"" in cl_c,
         "④ 丢弃只清那个字符串（pending 从没在磁盘上留东西，丢弃不需要删文件）")
    need("delete" not in cl_c and "drop" not in cl_c,
         "④ 『丢弃』**不许**碰磁盘（它连目录都没有 —— 碰了就是删别人的东西）")

    # 六条落盘出口都要让 pending 结束（漏一条 = 那个库名还在界面上，而其实已经落盘）。
    for sig, label in (
        ("private fun startBuildIndex(", "建库/重建"),
        ("private fun doImportRagEntries(", "导入（库/文档）"),
        ("private fun runRagSave(", "保存正文（已有库）"),
    ):
        b = strip_comments(fnbody(act, sig) or "")
        need("pendingKbLanded()" in b, f"④ 「{label}」落盘成功后也结束 pending")

    # 新建库那条路**不许**为了"让它进列表"去建一个空目录。
    need("mkdirs" not in onk_c and "dirOf" not in onk_c,
         "② 新建库**不建目录**（空目录会留下一个点不动、删不掉的幽灵行）")

    print()
    if bad:
        print(f"=== 新建库接线：FAIL {len(bad)} ===")
        return 1
    print("=== 新建库接线：PASS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
