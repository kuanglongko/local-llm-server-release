#!/usr/bin/env python3
"""判「合批前把**整批 token 数**拦在库的 `n_batch` 之内」这件事真的做了，
且拦在 `llama_decode` **之前**（0.9.209 修的真机闪退）。

═══════════════════════════════════════════════════════════════════════
为什么单列这一条
═══════════════════════════════════════════════════════════════════════
`llama_batch_allocr::init`（在 `llama_context::decode` 的**正常路径**上）断言：

    LM_GGML_ASSERT(n_tokens_all <= cparams.n_batch) failed

release（NDK 默认 `-DNDEBUG`）里断言失败 = `abort()` —— 用户看到的是
**建索引闪退**，日志最后一行只有知识库进度，没有任何一行说得出"批太大了"。

这一族错**只有真机能看到**（本环境无 NDK / 无设备 / 无嵌入模型），而它
又**不是**"概率崩"：8 条各 511 token = 4088 > 2048 = llama.cpp 的 n_batch
默认值 —— 只要一批里凑够长条目就必崩。所以判据只能锚源码结构：

  ① 提交前必须**读**库的 `n_batch`（`llama_n_batch(E.ctx)`）——
     拿 `kEmbedBatchRows × n_ctx_seq` 自己推一个"容量"是**另一份清单**，
     而它与库的实际值分岔时，症状还是 abort（库不认我们推的那个数）；
  ② 必须调纯逻辑判据（`batch_fits_n_batch` / `batch_chunk_rows`）——
     判据落不到宿主单测上时，"切成几段"这件事只能靠真机上看崩不崩；
  ③ 判据必须排在 `llama_decode` **之前** —— 排在之后等于它只是装饰
     （abort 已经在那一行发生了）；
  ④ `llama_batch_init` 的容量必须是**切过之后**的那个数（提交几条就申请几条）——
     申请整批容量、只填一段，后面那些 `token/pos/seq_id` 是未初始化内存；
  ⑤ 取向量的循环必须只走**提交过的**条数（读未提交的 seq = 读上一批（或从未
     写入）的 `embd_seq` 行 —— 长度、维度、归一化全对，语义是别人的）；
  ⑥ 装不下时必须**按条目边界切成几段、在同一次调用里接着编**（0.9.210），
     而**不是**"只提交前几条、余下记失败"—— 后者的代价是那几条进 `failedIdx`
     → `savePartial` → 整个库被标成"索引待重建"，而真相只是"这一批排不下"
     （那属于合批这一层，不该渗透成"这个库要重建"）。切段的判据是：
       · 切出来的每一段提交前都过一遍容量判据（判据是提交的**门禁**，不是装饰）；
       · 段长**每段重算**（`batch_chunk_rows(余下的 counts, nBatch)`）——
         写死首段那个数会让后面几段悄悄越界，而越界的报应是 abort；
       · 只提交前几条就 `return nullptr`（整批回落逐条）**也是一种错**：
         它不会崩，但会把 4~8 整段的合批收益一起扔掉（104 条 = 104 次 decode）。

用法：check_batch_capacity.py <llama_jni.cpp> <embed_text.h>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body  # noqa: E402


def main():
    jni_p, hdr_p = sys.argv[1], sys.argv[2]
    jni = Path(jni_p).read_text(encoding='utf-8', errors='replace')
    hdr = Path(hdr_p).read_text(encoding='utf-8', errors='replace')
    fails = []

    # ── ① 纯逻辑层：容量判据必须在 embed_text.h（宿主可单测）───────────────
    for fn in ("batch_fits_n_batch", "batch_chunk_rows"):
        if not re.search(r'inline\s+\w+\s+' + fn + r'\s*\(', hdr):
            fails.append(f"embed_text.h 里没有 {fn} —— 『整批装不装得进 n_batch』"
                         "必须有能在宿主单测里逐项对照的判据（这条错只有真机能看到）")

    body, line = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch")
    if body is None:
        print("FAIL  llama_jni.cpp 里找不到 nativeEmbedBatch（判据锚点失效）")
        return 1

    # ── ② 提交前读库的 n_batch（不是自己推一个容量）────────────────────────
    if not re.search(r'llama_n_batch\s*\(\s*E\.ctx\s*\)', body):
        fails.append("nativeEmbedBatch 没有读库的 n_batch（`llama_n_batch(E.ctx)`）——"
                     "自己按『条数 × 每条预算』推一个容量就是第二份清单："
                     "与库的实际 n_batch 分岔时，报应仍然是**进程级 abort**")

    # ── ③ 必须调纯逻辑判据 ─────────────────────────────────────────────────
    if not re.search(r'embedtext::batch_(?:fits_n_batch|chunk_rows)\s*\(', body):
        fails.append("nativeEmbedBatch 没有调 embedtext::batch_fits_n_batch / batch_chunk_rows ——"
                     "『切成几段』这件事必须落在纯函数上（否则只能靠真机上看崩不崩）")

    # ── ④ 判据排在 decode 之前 ────────────────────────────────────────────
    # ⚠ 锚的是**调用**（`llama_decode(E.ctx, b)`），不是"文件里出现过 llama_decode" ——
    # 后者会被注释/文案命中的（本文件在别处解释这条断言时就会写到它），
    # 而那样一来判据会**恒假**（注释永远排在前面），红得毫无意义。
    #
    # ⚠ 这里问的是"**要提交给库的那一段**的容量判据在哪边"，所以必须与解码点
    # **同段**去比 —— 0.9.210 起 `batch_chunk_rows` 在文件里出现三次（整批一次、
    # 切段循环里每段一次），拿"全局最早一次出现"（整批那一次）去比时，
    # 把段内的判据搬到 decode **之后**（等于装饰）**照样全绿**。
    # 同段 = `decode` 之前那一整段（`_seg`）。
    _blocks = re.split(r'if \(llama_decode\s*\(', body)
    if len(_blocks) < 2:
        fails.append("找不到 `llama_decode` 那一处（判据锚点失效）")
    else:
        _seg = _blocks[0]           # 真正**提交给库**的那一段（单条路径不是本函数）
        # 这一段的容量判据：最后一次 `batch_chunk_rows` 调用之后紧接着的那两处
        _cap_pos = [m.start() for m in
                    re.finditer(r'embedtext::batch_(?:fits_n_batch|chunk_rows)\s*\(', _seg)]
        # ⚠ 锚"**要提交的那一段**的容量判据" = **最后**一处 `batch_chunk_rows`
        # （切段循环里每段重算的那一处）—— 它决定这一段提交几条。
        # 拿**最早**一处（整批那一次）去比时，把段内判据搬到 decode 之后
        # （等于装饰）照样全绿：那一段里 abort 已经在望。
        _last = _cap_pos[-1]
        # 判据之后若出现 `llama_batch_init(`（申请这一段），说明"算段长"这件事
        # 排到了**申请之后** —— 那正是装饰的位置：申请与填 batch 用的是没人验过的段长。
        # 判据与申请之间的那点距离（几句 jlog / return）是正常的，只锚**在不在申请之前**。
        _init = _seg.find("llama_batch_init(")
        if _init >= 0 and _init < _last:
            fails.append("切段的容量判据排在 `llama_batch_init` **之后** ——"
                         "那等于装饰：申请与填 batch 用的是没人验过的段长，"
                         "而断言失败（abort）在 decode 那一行就已经发生")
        elif not re.search(r'segRows\s*=\s*[^;]*batch_chunk_rows[^;]*;'
                           r'.{0,600}?batch_fits_n_batch\s*\(\s*segTokens',
                           _seg, re.S):
            fails.append("段长（`batch_chunk_rows`）与提交前的容量判据（`batch_fits_n_batch(segTokens)`）"
                         "没有排在一起 —— 判据必须是提交的**门禁**："
                         "算完段长不过判据就填 batch 等于装不下也交")
        # 真正要钉死的量：**切段起点那次**判据（决定这一段提交几条）必须在 `decode` 之前 ——
        # 它在 `_seg` 里（`_seg` 就是 decode 之前的那一整段）。若它不在，
        # 说明有人把"算段长"这件事挪到了 decode 之后。
        if not re.search(r'batch_fits_n_batch\s*\(\s*segTokens', body):
            fails.append("每段提交前没有过容量判据（`batch_fits_n_batch(segTokens, ...)`）——"
                         "判据必须是提交的**门禁**，而不是算完段长就算了")

    # ── ⑤ batch_init 的容量必须是切过之后的那个数 ─────────────────────────
    # ⚠ 容量实参可能是 `submitTokens`，也可能是 `plan.n_tokens` 这种**带点**的
    # 表达式 —— 只认标识符的字符类会让"按整批容量申请"那一桩**锚不到**，
    # 于是判据静默不跑（本仓库反复记录的那类"守卫失效伪装成全绿"）。
    m_init = re.search(r'llama_batch_init\s*\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*,', body)
    if not m_init:
        fails.append("nativeEmbedBatch 里找不到 llama_batch_init（判据锚点失效）")
    else:
        cap = m_init.group(1)
        if cap in ("plan.n_tokens", "nTokens"):
            fails.append(f"llama_batch_init 按**整批**容量申请（{cap}）—— "
                         "申请整批、只填切出来的那一段时，后面那些 token/pos/seq_id "
                         "是未初始化内存（填没填对，计划的自检看不出来）")
        # 容量实参必须在本函数里算出来（`const int` 或 `int` —— 段内总量是
        # 循环累加，写成 `int segTokens = 0;` 更贴近它"由几项相加"的来历）。
        if not re.search(r'(?:const\s+)?int\s+' + re.escape(cap) + r'\s*=', body):
            fails.append(f"llama_batch_init 的容量实参 `{cap}` 不是本函数里算出来的"
                         "（看不清它是不是『切过之后』的那个数）")

    # ── ⑥ 取向量只走提交过的条数 ──────────────────────────────────────────
    # 锚"喂给取向量循环的上界"这个量：`plan.n_rows` 直接上循环时，
    # 没提交的那几条会去读**未写入的** embd_seq 行 / 越界的 output 排位。
    m_loop = re.search(r'for\s*\(\s*int\s+\w+\s*=\s*0\s*;\s*\w+\s*<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*;', body[body.find("pooled_vector_slot"):] if "pooled_vector_slot" in body else "")
    if "pooled_vector_slot" not in body:
        fails.append("nativeEmbedBatch 里找不到取向量循环（判据锚点失效）")
    else:
        after = body[body.find("const bool from_seq"):] if "const bool from_seq" in body else body
        m2 = re.search(r'for\s*\(\s*int\s+\w+\s*=\s*0\s*;\s*\w+\s*<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*;', after)
        if not m2:
            fails.append("取向量循环的上界取不到（判据锚点失效）")
        elif m2.group(1) == "plan.n_rows":
            fails.append("取向量循环按 **plan.n_rows**（整批条数）走 —— 只提交了前几条时，"
                         "后面那几条会去读 `embd_seq` 里**未写入**的行（内容取决于上一批"
                         "或从未初始化），而长度、维度、归一化全对")
        elif m2.group(1) in ("nRows", "nEmbd"):
            fails.append(f"取向量循环的上界是 {m2.group(1)}（原始条数）—— 必须按**提交过的**条数走")

    # ── ⑦ 装不下时**按条目边界切段、在同一次调用里接着编**（0.9.210）────────
    # 0.9.209 的出口是"整批返回 null 回落逐条"——它把 4~8 整段的合批收益一起扔掉
    # （104 条 = 104 次 decode）。这一版改成"切成几段、一段一次 decode"，
    # 而三个必要条件都必须锚住，缺一个就是另一类错：
    #   · 切段循环（`while (submitted < plan.n_rows)` 这个形状）—— 没有它时
    #     "只提交前几条"会**静默**变成一次性收益损失，不报错、不崩；
    #   · 段长**每段重算**（实参含 `submitted`）—— 写死首段那个数会让后面几段
    #     悄悄越界，而越界的报应是进程级 abort；
    #   · 每段提交前仍过容量判据（`batch_fits_n_batch`）—— 判据必须是提交的**门禁**，
    #     排在 `llama_decode` 之前，不是装饰。
    # ⚠ 反面判据（0.9.209 留着的那一条）：`chunkRows < plan.n_rows` 时**直接**
    # `return nullptr`（整批回落逐条）也是一种错 —— 它不崩，但它把这一批的收益
    # 全扔了，而日志里两个方向的话都说得通（"回落逐条"看起来永远是个安全出口）。
    if not re.search(r'while\s*\(\s*\w+\s*<\s*plan\.n_rows\s*\)', body):
        fails.append("nativeEmbedBatch 里没有『切段提交』的循环（`while (submitted < plan.n_rows)`）——"
                     "装不下时只能整批回落逐条，4~8 整段的合批收益会被一起扔掉"
                     "（104 条 = 104 次 decode），而这件事**不报错**")
    m_chunk2 = re.findall(r'batch_chunk_rows\s*\(\s*([^,]*),', body)
    if not m_chunk2:
        fails.append("切段循环里没有调 `batch_chunk_rows` 算段长（判据锚点失效）")
    elif not any(a.strip() not in ("liveCounts", "counts") for a in m_chunk2):
        fails.append("段长用的还是**整批**的 counts（`batch_chunk_rows` 的实参只有 "
                     "`liveCounts` / `counts`）—— 段长必须按『余下的那几条』重算，"
                     "写死首段那个数会让后面几段悄悄越过 `n_batch`，"
                     "而越界的报应是**进程级 abort**")
    # 反面判据：装不下时**直接**整批返回 null（0.9.209 的写法）—— 见上面那段。
    # ⚠ 锚的是"那个 if 块里**第一条**语句就是 `return nullptr`"这个形状，
    # 而不是"文件里出现过 return nullptr"（后者到处都是，恒真）。
    # ⚠ 用**惰性**通配（`.*?`）而不是 `[^}]*`：注释被 `fn_body` 剥掉之后，
    # 那个 `if` 块体里是一串**空行**再跟 `jlog(...)` —— 而 `jlog` 的实参里
    # 可能出现花括号，`[^}]*` 会因此中途断开、判据恒假（红得毫无意义）。
    # ⚠ 两种写法都要认：带大括号的 `{ jlog(...); return nullptr; }`，
    # 以及**不带大括号**的 `if (...) return nullptr;`（后者更省事、也更容易
    # 在这一版里被顺手写回来 —— 只认大括号会让这一桩静默漏过）。
    early = re.search(r'if\s*\(\s*chunkRows\s*<\s*plan\.n_rows\s*\)\s*'
                      r'(?:\{\s*(?:jlog\([^;]*?\)\s*;\s*)*return\s+nullptr\s*;|return\s+nullptr\s*;)',
                      body, re.S)
    if early:
        fails.append("装不下（`chunkRows < plan.n_rows`）时**直接整批返回 null 回落逐条** ——"
                     "那是 0.9.209 的出口：它不崩，但会把 4~8 整段的合批收益一起扔掉"
                     "（104 条 = 104 次 decode）。要的是**按条目边界切段、在同一次调用里接着编**")
    # ── ⑦b 段内总量与段内槽位必须**相对这一段**算 ──────────────────────────
    # 这是切段落地时最容易错、也最安静的两处（本版第一稿两处都错了）：
    #   · 段内总量写成 `plan.rows[末条].begin + .n` —— 那个 `begin` 是**整批**
    #     下标起点，第二段起就带上了前面几段的偏移（8 条满长时第二段算出 4088），
    #     于是"这一段装得下"恒假 → 整批回落逐条。**不报错**，只是收益没了；
    #   · 填 batch 时用**整批**下标 `g` 当槽位 —— 第二段会从 2044 那格开始写，
    #     而 `b` 只有 2044 格：越界写堆，且 0..2043 格留空（噪声进了向量）。
    # 两处都锚"那个量是从哪来的"：段内总量必须由**这一段的 rows 逐条加**得到，
    # 槽位必须减掉这一段的起点。
    if not re.search(r'for\s*\(\s*int\s+\w+\s*=\s*0\s*;\s*\w+\s*<\s*segRows[^)]*\)'
                     r'[^;]*;?[^;]*segTokens\s*\+=', body, re.S):
        fails.append("段内 token 总量不是『这一段的 rows 逐条加』出来的 ——"
                     "写成 `plan.rows[末条].begin + .n` 时，那个 `begin` 是**整批**下标，"
                     "第二段起就带上前面几段的偏移，于是容量判据恒假、整批回落逐条"
                     "（不报错，只是收益静默丢掉）")
    if not re.search(r'segBegin\s*=\s*plan\.rows\[\s*\(size_t\)\s*submitted\s*\]\.begin', body):
        fails.append("填 batch 时没有算『这一段的起点』（`segBegin = plan.rows[submitted].begin`）——"
                     "槽位直接用**整批**下标 `g` 时，第二段会从第一段末尾那一格开始写，"
                     "而 `b` 只有这一段的容量：越界写堆，且前几格留空（噪声进向量）")
    elif not re.search(r'(?:slot|\w+)\s*=\s*g\s*-\s*segBegin', body):
        fails.append("段内槽位没有减掉这一段起点（`g - segBegin`）—— 见上一条")

    # ⚠ 另一条反面判据：不许出现以**切出来的条数**为起点的**失败记账**循环 ——
    # 那是"只提交前几条、余下记成失败"那种写法的指纹（余下那几条会进
    # `failedIdx` → `savePartial` → 整个库被标成"索引待重建"）。
    # 锚"以切出来的条数为起点 **且 循环体里在记账失败**"这个**结合**，
    # 而不是"存在一个从 chunkRows 起的循环" —— 切段的推进循环本身正是那个形状，
    # 只锚形状的话它会把**正确**的实现也判红。
    # ⚠ 尾巴那一句可能**不带大括号**（`for (...) g_embed_batch_failed.push_back(...);`
    # —— 只认 `\{...\}` 会让这一种静默漏过：本仓库反复记录的那类"判据看着在、
    # 实际锚不到"）。所以锚的是"循环头之后到最近一个分号之间出现记账"。
    tail = re.search(r'for\s*\(\s*int\s+\w+\s*=\s*(?:chunkRows|submitRows|submitted)\s*;'
                     r'[^)]*\)\s*(?:\{[^}]*|[^;{]*?)failed\s*\.\s*push_back', body, re.S)
    if tail:
        fails.append("nativeEmbedBatch 里有以**切出来的条数为起点**的失败记账循环 ——"
                     "那是『只提交前几条、余下记成失败』那种写法：余下那几条会进"
                     "`failedIdx` → `savePartial`，整个库被标成『索引待重建』，"
                     "而真相只是这一批排不下（该切段接着编，一条都不记失败）")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print(f"PASS  整批容量判据在纯逻辑层（batch_fits_n_batch / batch_chunk_rows，第 {line} 行起）")
    print("PASS  提交前读库的 n_batch，且在 llama_decode 之前拦")
    print("PASS  batch_init 容量 = 切过之后的 token 数")
    print("PASS  取向量的循环只按**提交过的**条数走")
    print("PASS  装不下时按条目边界切段、在同一次调用里接着编（段长每段重算）")
    print("PASS  段内总量按这一段逐条加、段内槽位减掉这一段起点（不是整批下标）")
    print("PASS  不是整批回落逐条（0.9.209 的出口），也不是『余下记成失败』")
    return 0


if __name__ == '__main__':
    sys.exit(main())
