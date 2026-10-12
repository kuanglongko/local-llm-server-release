#!/usr/bin/env python3
"""「列表先出 + 删一条不重读盘」的接线判据（纯静态）。

═══════════════════════════════════════════════════════════════════════════
这一组守的是什么
═══════════════════════════════════════════════════════════════════════════
用户报的两件事：
  · *"输入框的预览和逐条预览这个，还能优化么，现在还会卡很久，体验不好"*；
  · 同一屏里"点库之后一片空白很久"。

拆出来是两处**同族**的浪费，两处都不抛异常、都只表现为"慢/空白"：

  P0 —— 条目列表的刷新挂在了正文框回填**之后**的 `ui.post{}` 里。
        列表只依赖后台一次 `readEntriesAligned`（几十毫秒），而它等的
        `setRagDocText` 是**全量布局**（几十万字符 = 秒级）—— 于是列表
        要等这一屏最贵的一步做完才出现，用户看到的就是那段空白。

  P3 —— 删除成功之后走 `refreshRagRows`，把 6 MB 的 `texts.jsonl` 又读
        一遍、重新解析、重铺 180 个 View。而"删完之后磁盘上是哪张表"
        是**已知的**（`removeEntry` = 摘掉第 index 行、其后前移一位）。

判据一律锚**结构关系**（谁在谁之前 / 在哪个函数体内 / 调用了谁），
不锚"某某字符串出现过"（后者恒真：解释这件事的注释里就有）。
用法：python3 tools/rag_list_first/check_wiring.py <EngineActivity.kt>
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
    s = read(src)
    bad = []

    def need(cond, what):
        print(("PASS  " if cond else "FAIL  ") + what)
        if not cond:
            bad.append(what)

    # ══════════════════════════════════════════════════════════════════════
    # P0 —— 条目列表与正文框**并行发起**
    # ══════════════════════════════════════════════════════════════════════
    doc = fnbody(s, "private fun loadRagDoc(")
    need(doc is not None, "loadRagDoc() 存在（锚点未漂）")
    doc_c = strip_comments(doc or "")

    # ① 列表在这个函数里被发起（而不是交给调用方去补一次）。
    need("refreshRagRows(name)" in doc_c,
         "① loadRagDoc 里发起条目列表的刷新（不是等调用方补）")

    # ② P1-a 之后 loadRagDoc **自己**不许再起后台读 —— 列表与正文预览
    #    共用 refreshRagRows 那一次读盘，这就是"并行/不重复"的新形态：
    #    旧写法是"列表与整库回填并行发起"，新写法干脆只剩**一条**读盘。
    #    ⚠ 判据锚的是**这个函数体里有没有 `ioExecutor`**，不是"文件里有没有"
    #      （后者到处都是：追加/删除/刷新列表都在后台）。
    need("ioExecutor.execute" not in doc_c,
         "① loadRagDoc 不再自己起后台读（列表与预览共用 refreshRagRows 那一次读盘）")
    need("readEntries(" not in doc_c.replace("readEntriesAligned(", ""),
         "① loadRagDoc 不再读整库正文（P1-a：框只装一页，不再拼全文）")

    # ③ 归属先于发起：`refreshRagRows` 落地前要复核 ragDocOwner，归属没写上
    #    就被它看到 -> 这一次刷新会被自己丢掉（列表要等下一次才出来）。
    i_owner = doc_c.find("ragDocOwner = name")
    i_rows = doc_c.find("refreshRagRows(name)")
    need(i_owner > 0, "① loadRagDoc 里写了 ragDocOwner（归属的唯一写点之一）")
    if i_owner > 0 and i_rows > 0:
        need(i_owner < i_rows,
             "① 归属（ragDocOwner）写在发起列表刷新**之前**"
             "（否则那一次刷新会被自己的归属判据丢掉）")

    # ④ 正文预览**只**在 renderRagRows 的末尾画（与列表同一跳）——
    #    这样"列表在第 3 页、框里还在第 1 页"这种分家不可能发生。
    rrx = fnbody(s, "private fun renderRagRows(")
    rrx_c = strip_comments(rrx or "")
    need("renderRagDocSlice()" in rrx_c,
         "① 这一片正文在 renderRagRows 里画（与列表同一页、同一跳）")

    # ⑤ 行号口径没变：列表仍走 readEntriesAligned（下标即行号）。
    #    并行之后行号仍须与磁盘对齐 —— 删错行那个老缺陷不许借这次改动回来。
    rrr = fnbody(s, "private fun refreshRagRows(")
    need(rrr is not None, "refreshRagRows() 存在（锚点未漂）")
    rrr_c = strip_comments(rrr or "")
    need("readEntriesAligned" in rrr_c,
         "① 列表仍用 readEntriesAligned（下标即行号，并行不许把行号挪位）")
    need("readEntries(" not in rrr_c.replace("readEntriesAligned(", ""),
         "① 列表**不许**用压紧的 readEntries（那会让删除删错隔壁那一行）")

    # ══════════════════════════════════════════════════════════════════════
    # P3 —— 删一条之后**不重读盘**
    # ══════════════════════════════════════════════════════════════════════
    dele = fnbody(s, "private fun doDeleteEntry(")
    need(dele is not None, "doDeleteEntry() 存在（锚点未漂）")
    dele_c = strip_comments(dele or "")
    need("dropRowLocally" in dele_c,
         "② 删除成功后走本地推算 dropRowLocally（不再整份重读）")
    need("refreshRagRows(name)" not in dele_c,
         "② 删除那条路上**没有** refreshRagRows（那就是一次 6 MB 的重读）")

    drop = fnbody(s, "private fun dropRowLocally(")
    need(drop is not None, "dropRowLocally() 存在（锚点未漂）")
    drop_c = strip_comments(drop or "")

    # ⑥ 推算走**纯函数**那一份（`RagUiCap.dropRowAt`）—— 不在这里手写一份。
    #    两份实体必然漂移，而漂移的形态是"删错隔壁那一行"，屏幕上一切正常。
    need("RagUiCap.dropRowAt" in drop_c,
         "② 推算走纯函数 RagUiCap.dropRowAt（不在 UI 里手写第二份）")
    need("refreshRagRows(name)" in drop_c,
         "② 推算不可信时**退回重读**（宁可慢一次，不按不明表推行号）")

    # ⑦ 重画只从内存那份表出发（不碰磁盘）。
    need("renderRagRows" in drop_c,
         "② 重画走 renderRagRows（用内存里那份 ragRowsShown，不重读盘）")
    need("RagIndexStore" not in drop_c,
         "② dropRowLocally 里**一个磁盘调用都没有**（它就是为省掉那次读盘存在的）")

    # ══════════════════════════════════════════════════════════════════════
    # P3 —— 推算本身（纯逻辑那一层）
    # ══════════════════════════════════════════════════════════════════════
    cap = read(sys.argv[2]) if len(sys.argv) > 2 else ""
    need("fun dropRowAt(" in cap,
         "② RagUiCap 里有 dropRowAt（推算的唯一实体，宿主侧可单测）")
    dbody = fnbody(cap, "fun dropRowAt(")
    need(dbody is not None, "② dropRowAt 的锚点没漂")
    dc = strip_comments(dbody or "")
    # 不变量必须**在**这条纯函数里（合进来就是为了不许有人只摘格子忘了验对齐）。
    need("rows[i].first != i" in dc,
         "② 先验「下标即行号」的不变量（不验 -> 按一份来路不明的表推行号）")
    need("return null" in dc,
         "② 对不上时返回 null（调用方据此退回重读，不是硬算）")
    # 行号按新位置重建，不是留洞。
    need("out.add(i to kept[i].second)" in dc or "mapIndexed" in dc,
         "② 行号按新位置重建（保留旧行号会让后面每一行都指错一条）")
    need("removeAt" in dc or "if (i != index)" in dc,
         "② 摘掉的恰好是那个下标那一格（下标就是行号）")

    # ══════════════════════════════════════════════════════════════════════
    # P3 —— 摘要行的断行策略
    # ══════════════════════════════════════════════════════════════════════
    rows = fnbody(s, "private fun renderRagRows(")
    need(rows is not None, "renderRagRows() 存在（锚点未漂）")
    rows_c = strip_comments(rows or "")
    need("BREAK_STRATEGY_SIMPLE" in rows_c,
         "③ 摘要行用 BREAK_STRATEGY_SIMPLE（默认 BALANCED 要多跑一遍断行）")
    need("HYPHENATION_FREQUENCY_NONE" in rows_c,
         "③ 摘要行关掉连字符（默认要查连字符字典，摘要不需要）")
    # 锚在**摘要那个 TextView** 上：整页每行一个，改错对象等于没改。
    i_sum = rows_c.find("one.substring(0, 60)")
    need(i_sum > 0, "③ 摘要行那个 TextView 的锚点在（one.substring(0, 60)）")
    if i_sum > 0:
        seg = rows_c[i_sum:i_sum + 900]
        need("BREAK_STRATEGY_SIMPLE" in seg and "HYPHENATION_FREQUENCY_NONE" in seg,
             "③ 两个策略都落在**摘要行**这个 TextView 上（不是别处）")

    # ⑨ 行号仍是下标（与 P0 同一条老缺陷的正面判据）。
    need("mapIndexed { i, t -> i to t }" in rows_c,
         "③ 行号仍然 = 正文文件里的下标（并行/本地推算都不许把它挪位）")

    print()
    if bad:
        print(f"=== 列表先出 + 删除不重读：FAIL {len(bad)} ===")
        return 1
    print("=== 列表先出 + 删除不重读：PASS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
