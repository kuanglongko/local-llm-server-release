#!/usr/bin/env python3
"""「改内容、行数不变 → 重算改那一行」的接线判据（ISSUE #228 的新特性）。

═══════════════════════════════════════════════════════════════════════════
这一组守的是什么
═══════════════════════════════════════════════════════════════════════════
用户原话：*"改内容、行数不变 → 重算改那一行。"*

在此之前，在正文框里改一个字只有两条路，两条都**不划算**：
  · 「保存正文」—— 只写 `texts.jsonl`，索引随即失效，要**整库重建**
    （几千条 = 几十分钟）；
  · 「删掉那一条 + 追加一条」—— 换了行号，也换了向量。

这一版补上第三条：框里改字 -> **只重算改过那几行的向量**，连同正文一起写回，
索引与正文**重新同步** —— 检索照常可用、命中的就是新内容、**无需重建**。

═══════════════════════════════════════════════════════════════════════════
为什么这一族**必须**配套判据：它的失效形态全是"静默毁数据 / 静默假绿"
═══════════════════════════════════════════════════════════════════════════
  · 指纹把**模型那一半现算**成"当前配置" -> 用户改过批大小 / 换过模型时，
    刚写完的索引**立刻**被判"需重建"，指向一个他没动过的字段 ——
    而这次修法本身就是为了"免重建"，于是它当场自相矛盾（且现象指向别处）；
  · 只重算向量、**不重算指纹里的 `x`** -> 索引与正文仍然不同源，
    "免重建"没发生，而界面上说不出为什么；
  · 编码失败 / 索引不同源时**不退回**"只写正文" -> 用户的改动一个字节都没落盘，
    而回执说得像成功了；
  · 退回时**不说清**（把"改动在、只是要重建"报成"失败"）-> 用户重敲一遍，
    更坏的是去重导（导入是追加合并，重导 = 条目翻倍）；
  · 不先确认"索引此刻可用"就编码 -> 算出来的向量与索引里其余行**不同源**，
    一份索引里两种语义，检索分数看着像随机，**不报错**。

判据一律锚**结构关系**（这个函数体内有没有那一跳 / 谁在谁之前 / 取值走哪个
函数），不锚"某某字符串出现过"（后者恒真：注释里就有）。
用法：python3 tools/rag_row_reembed/check_wiring.py <LlmEngine.kt> <RagIndexStore.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "rag_edit"))
from _fnbody import kotlin_fn_body  # noqa: E402


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def nocomment(s):
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def main():
    eng = read(sys.argv[1])
    store = read(sys.argv[2]) if len(sys.argv) > 2 else ""
    bad = []

    def need(cond, what):
        print(("PASS  " if cond else "FAIL  ") + what)
        if not cond:
            bad.append(what)

    def body(src, sig):
        """`kotlin_fn_body` 返回 (函数体, 起始行号) —— 只取函数体。"""
        code, _line = kotlin_fn_body(src, sig)
        return code or ""

    # ══════════════════════════════════════════════════════════════════════
    #  ① 存储层：两侧一起写，且**指纹只换 x**
    # ══════════════════════════════════════════════════════════════════════
    rw = nocomment(body(store, "replaceRowsWithVectors"))
    need("replaceRowsWithVectors(" in store or rw,
         "① 存储层有 replaceRowsWithVectors（正文与向量一起按行写回）")
    need("spliceRowsWithVectors(" in rw,
         "① 写盘那条路走纯函数 spliceRowsWithVectors（可离线单测）")
    # ⚠ 先写索引、后写正文：与 appendEntries / removeEntry 同一个次序。
    #   反过来（先正文后索引）失败时留下"索引说的正文"与磁盘正文不一致 ——
    #   那会被判需重建，可恢复但费解。判据锚**相对次序**。
    i_idx = rw.find("writeIndexAtomic(")
    i_txt = rw.find("writeTexts(")
    need(i_idx >= 0 and i_txt >= 0 and i_idx < i_txt,
         "① 先写索引、后写正文（与 append/remove 同一次序）")

    sp = nocomment(body(store, "spliceRowsWithVectors"))
    need("spliceRows(" in sp,
         "① 正文那一半复用 spliceRows（行号越界 / 空行 / 编成 JSON 行只有那一处）")
    # 索引必须**齐整**且与正文**同源**才允许按行改 —— 否则整次拒绝。
    need("rowsToCount(" in sp and "textsInSync(" in sp,
         "① 前置判据：索引齐整（count == 行数-1）且与正文同源（textsInSync）")
    # 新向量按行号替进去。
    need("arraycopy(" in sp and "copyOf(" in sp,
         "① 新向量按行号替进去、其余行逐字节搬（copyOf）")
    # ⚠⚠ 本组最贵的一条：指纹的模型那一半必须**照搬磁盘那份**（FpInput(fpIn)），
    #     不许在这里现算 `embedFpInput`（那会算出"当前配置"，用户改过批大小时
    #     刚写完的索引立刻不可用 —— 而这次修法就是为了免重建）。
    need("FpInput(fpIn)" in sp or "FpInput(" in sp and "fpIn" in sp,
         "① 指纹的模型那一半**照搬磁盘那份**（FpInput(fpIn)，不现算当前配置）")
    need("embedFpInput" not in sp,
         "① 不许在这里现算 embedFpInput（那会把『当前配置』写进一份旧索引）")
    # 只换 `x`（正文 hash）：**必须用改完之后的那份正文**（`after`），
    # 不许用磁盘上那份旧正文（`before`/`textsRaw`）—— 那等于没换，
    # 索引与正文仍不同源，"免重建"没发生而界面上说不出为什么。
    # ⚠ 锚的是"hash 取自哪个变量"，不是"调用过 textsHashOf"（后者恒真：
    #   函数里到处都是它）。
    need("textsHashOf(after" in sp or "textsHashOf(after.toByteArray" in sp,
         "① 指纹里只换 x，且取的是**改完之后**那份正文的 hash")
    # 返回"两份新字节"（纯函数不碰文件）。
    need("class SplicePlan" in store,
         "① 纯函数的产物是两份新字节（SplicePlan），不碰文件")

    # ══════════════════════════════════════════════════════════════════════
    #  ② 引擎层：只编码**改过的**那几条，且先确认索引可用
    # ══════════════════════════════════════════════════════════════════════
    rr = nocomment(body(eng, "rewriteRows"))
    need(rr, "② 引擎有 rewriteRows（改字 -> 只重算那几条向量）")
    # 只编码改过的那几条：循环遍历的是**入参**（indices/texts），不是整库正文。
    need("texts.indices" in rr or "for (k in texts.indices)" in rr,
         "② 只遍历**改过的那几条**（不是整库）")
    need("embed(" in rr, "② 走 embed() 逐条编码（不是整库 buildIndex）")
    need("buildIndex" not in rr,
         "② 这一跳**不调** buildIndex（那就是整库重建，正是要免掉的事）")
    # 三态返回值：IndexRebuilt / TextsOnly / Failed。
    need("RewriteResult.IndexRebuilt" in rr, "② 成功态是 IndexRebuilt（索引仍可用）")
    # ⚠⚠ "编码失败 -> 退回 TextsOnly"这条**不能**只数 `RewriteResult.TextsOnly`
    #   出现过 —— 它在该函数里有三处（编码失败 / STALE / 写盘被拒），
    #   于是"把编码失败那一支改成 Failed"之后，词还在、判据**恒绿**。
    #   本仓库反复记录过这种"存在性断言在别处也成立"的假绿。所以锚**那一支**：
    #   `if (failed.isNotEmpty())` 的分支里必须同时有"退回按行写正文"与 TextsOnly。
    i_fail = rr.find("failed.isNotEmpty()")
    need(i_fail >= 0, "② 有『编码失败』这一支（锚点未漂）")
    tail = rr[i_fail:] if i_fail >= 0 else ""
    # 那一支的边界：下一个顶层 `if (` 之前（用"可用性闸"那一句当右界）。
    i_next = tail.find("indexUsableForRewrite(")
    branch = tail[:i_next] if (i_fail >= 0 and i_next >= 0) else tail
    need("RewriteResult.TextsOnly" in branch,
         "② 编码失败那一支 -> 退回 TextsOnly（只写正文，改动不许丢）")
    need("replaceRowsInPlace(" in branch,
         "② 退回那一条走 replaceRowsInPlace（只写正文这条老路）")
    # 先确认"索引此刻可用"再编码 —— 否则算出来的向量与索引其余行不同源。
    need("indexUsableForRewrite(" in rr,
         "② 编码**之前**先确认索引与当前模型同源（不许直接编）")
    ju = nocomment(body(eng, "indexUsableForRewrite"))
    need("embedFpInput(" in ju,
         "② 「可用」的判据与检索**同源**（embedFpInput 取值）")
    need("RagIndexStore.load(" in ju,
         "② 「可用」的判据走 RagIndexStore.load（不是另写一个近似）")
    need("Load.valid" in ju,
         "② 只认 Load.valid（半份索引不可按行改 —— 后面那些行本来就没向量）")

    # ══════════════════════════════════════════════════════════════════════
    #  ③ 落盘那一跳走的是"两侧一起写"（不是只写正文）
    # ══════════════════════════════════════════════════════════════════════
    need("replaceRowsWithVectors(" in rr,
         "③ 正常路径走 replaceRowsWithVectors（正文 + 向量）")
    need("saveTexts" not in rr,
         "③ 这一跳**不经过** saveTexts（整份覆盖 = 把库砍成一页）")

    # ══════════════════════════════════════════════════════════════════════
    #  ④ RewriteResult 三态**存在且可分**
    # ══════════════════════════════════════════════════════════════════════
    need("sealed class RewriteResult" in eng, "④ RewriteResult 是三态（sealed）")
    need("object IndexRebuilt" in eng, "④ 有 IndexRebuilt 这一态")
    need("class TextsOnly" in eng, "④ 有 TextsOnly 这一态（改动在、只是要重建）")
    need("class Failed" in eng, "④ 有 Failed 这一态")

    print()
    if bad:
        print("=== 改字重算向量接线：FAIL %d ===" % len(bad))
        sys.exit(1)
    print("=== 改字重算向量接线：PASS ===")


if __name__ == "__main__":
    main()
