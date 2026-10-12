#!/usr/bin/env python3
"""断言 warm-up 前向**与真实编码路径同形** —— 它的目的是"写满 output buffer"。

═══ 这一条守的是什么 ═══
`embeddings=true` 时 ctx 里那块 output buffer 是**新分配的未初始化内存**，
而"读一行"按 `n_embd` 走、与"写了哪几行"无关。所以第一条真实文本读到的
可能有一部分是没被写过的内存（含 NaN），现象是"刚加载时某几条失败、之后就好了"。
warm-up 的存在就是为了把这个不确定性去掉 —— 而"去掉"的判据**不是**
"跑没跑过前向"，是"**每一行**是不是都被写过"。

这两件事被当成过一件。早先那版 warm-up 是"BOS 单 token + 只要一行输出"，
推理链是"库对 NONE/LAST 只要求至少一个 output"。这条推理**每一步都错**：

  ① **CLS 上直接进程级崩溃**。库的门禁在 `llama_batch_allocr::init`
     （vendor `librnllama.so` 里符号 `_ZN18llama_batch_allocr4initE…`），
     认的是 `llama_batch.logits[]` 覆盖了所有 input token 没有：
       `pooled embedding requires that all tokens are output
        (n_outputs_all = %d, n_tokens_all = %d)`
     而 `init` 在 `llama_context::decode` 的**正常路径**上；release
     （NDK 默认 `-DNDEBUG`）里 `ABORT` 就是 `abort()` →
     BGE 系（`pooling=2`）**每次加载嵌入模型都崩**。

  ② **"库会替我们补"补的是真实 input**。`llama_context::decode` 里那条
     `embeddings required but some input tokens were not marked as outputs
      -> overriding` 补的是 **input token** —— 而这里只有 BOS 一个，
     "补"完还是只有第 0 行：warm-up 想干的事**根本没干成**，只剩一条假绿日志。

所以判据锚的是"warm-up 的 batch 与真实路径**由同一个计划产生**"：
  · 形状下限（≥2 token）—— 1 个 token 时"整段"= 第 0 行，与早先那版等价；
  · 给库的门禁由 `logits_ok_for_library` 判（CLS/MEAN 整段）；
  · "写满了没有"由 `warmup_wrote_all_rows` **如实**回答（不许假绿）。

⚠ 本守卫**不锚"文件里出现过 warm-up 这个字"** —— 那是恒真的（写错的那一版里
那个字还在），而它正是上一版守卫的判据形态。判据全部锚**结构关系**：
计划从哪来、logits 从哪来、失败分支说了什么、有没有"跳过"的兜底路径。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body  # noqa: E402


def _strip_cpp_comments(code: str) -> str:
    """去掉 C++ 的 // 行注释与 /* */ 块注释。

    ⚠ 判据里凡是要问"这段**代码**长什么样"的地方，都必须先过这一层：
    本仓库注释密度极高，而注释里**天然会引用**被否定的旧写法
    （"这里**不得**再用 BOS 单 token")。直接对带注释的源码做子串匹配，
    会把"注释里提到了它"当成"代码里用了它" —— 判据方向正好反了。
    """
    out = []
    i = 0
    n = len(code)
    in_line = in_block = False
    while i < n:
        c = code[i]
        nxt = code[i + 1] if i + 1 < n else ''
        if in_line:
            if c == '\n':
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == '*' and nxt == '/':
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if c == '/' and nxt == '/':
            in_line = True
            i += 2
            continue
        if c == '/' and nxt == '*':
            in_block = True
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def _hdr_src() -> str:
    return Path('app/src/main/cpp/embed_text.h').read_text(encoding='utf-8')


def main():
    src = Path(sys.argv[1]).read_text(encoding='utf-8')
    bad = 0

    load, _ = fn_body(src, 'nativeLoadEmbedModel')
    if load is None:
        print("FAIL  找不到 nativeLoadEmbedModel（判据锚点失效）")
        return 1
    load_code = _strip_cpp_comments(load)

    # ① warm-up 的 batch 必须由**同一个计划函数**产生
    # ═══════════════════════════════════════════════════════════════════════
    # 这是本 PR 的核心：早先那版自己手写 `wb.logits[0] = 1`（"只要一行"），
    # 于是与真实路径的契约分家，而 CLS 的门禁要求"整段"。
    # 判据锚"计划从哪来"而不是"有没有 warm-up"。
    if 'embedtext::plan_pooling' not in load_code:
        print("FAIL  warm-up 没有用 embedtext::plan_pooling —— "
              "它自己手写 logits 置位就与真实路径的库契约分家了（CLS 上直接 abort）")
        bad += 1
    else:
        print("PASS  warm-up 的 batch 由 plan_pooling 产生（与真实路径同一套置位规则）")

    # ⚠ warm-up 块的定位用**原本的源码**：`fn_body` 会把注释剥掉，而那行
    # 标注注释（`── warm-up：…`）正好是唯一稳定的锚点。定位之后**先按括号配平
    # 切出整块（此时还没去注释，块内的 `//` 与 `/*` 里不会出现裸的 `*/`），
    # 再对切出来的这段去注释** —— 顺序反了就会在"注释里带了 `{`"的地方切歪。
    i_cmt = src.find('warm-up：')
    if i_cmt < 0:
        print("FAIL  找不到 warm-up 那行标注注释（判据锚点失效）")
        bad += 1
        warm = load_code
    else:
        i_brace = src.find('{', i_cmt)
        warm = None
        if i_brace >= 0:
            d = 0
            k = i_brace
            while k < len(src):
                if src[k] == '{':
                    d += 1
                elif src[k] == '}':
                    d -= 1
                    if d == 0:
                        warm = _strip_cpp_comments(src[i_brace:k + 1])
                        break
                k += 1
        if warm is None:
            print("FAIL  切不出 warm-up 那段（判据锚点失效）")
            bad += 1
            warm = load_code

    # ② logits 必须逐位照计划的 want_logits 置（不是硬编码"最后一个"/"第 0 个"）
    m = re.search(r'wb\s*\.\s*logits\s*\[\s*i\s*\]\s*=\s*([^;]+);', warm)
    if not m:
        print("FAIL  找不到 wb.logits[i] 的赋值（判据锚点失效）")
        bad += 1
    elif 'want_logits' not in m.group(1):
        print(f"FAIL  warm-up 的 logits 取值是「{m.group(1).strip()}」—— 不是池化计划的 want_logits。"
              "硬编码「只要一行」在 CLS 上会踩库的前置门禁（进程级 abort），"
              "在 NONE/LAST 上则**写不满 buffer**（warm-up 等于没做，只剩假绿日志）")
        bad += 1
    else:
        print("PASS  warm-up 的 wb.logits[i] 取自池化计划（与真实路径同一套置位）")

    # ③ 「计划不可用」必须有一条**显式**的兜底分支，且必须留日志
    # ═══════════════════════════════════════════════════════════════════════
    # warm-up 是"去掉不确定性"，不是功能前提 —— 所以它**允许跳过**。
    # 但"允许跳过"与"静默跳过"是两件事：静默跳过就是又一条假绿
    #（日志里什么都没有，用户以为 buffer 干净了）。
    if 'embedtext::warmup_plan_usable' not in warm:
        print("FAIL  warm-up 没有用 embedtext::warmup_plan_usable 判「这个计划能不能用」—— "
              "没有这道闸，1 个 token 的退化形态会被当成正常热身跑一遍（等于没做）")
        bad += 1
    else:
        print("PASS  warm-up 用 warmup_plan_usable 判计划可用性")
        # 取反用在 if 上 → 那是"不可用就跳过"
        if not re.search(r'if\s*\(\s*!\s*embedtext\s*::\s*warmup_plan_usable', warm):
            print("FAIL  warmup_plan_usable 不是作为 skip 条件用的 —— "
                  "用了但没判（或判反）等于这道闸不存在")
            bad += 1
        else:
            print("PASS  warmup_plan_usable 被用作跳过分支的判断依据")
        # 跳过必须留日志。
        #
        # ⚠ 判据锚**那个 if 分支自己的花括号体**，不是"warmup_plan_usable 之后
        # 出现过 jlog" —— 后者是恒真的：后面还有 decode 失败那条 jlog，
        # 于是"跳过分支里一句日志都没有"照样绿（本桩真抓到过一次）。
        m_if = re.search(r'if\s*\(\s*!\s*embedtext\s*::\s*warmup_plan_usable[^{]*\)\s*\{', warm)
        if not m_if:
            print("FAIL  找不出 warmup_plan_usable 的跳过分支（判据锚点失效）")
            bad += 1
        else:
            d = 0
            k = warm.index('{', m_if.start())
            e = -1
            while k < len(warm):
                if warm[k] == '{':
                    d += 1
                elif warm[k] == '}':
                    d -= 1
                    if d == 0:
                        e = k
                        break
                k += 1
            branch = warm[m_if.start():e + 1] if e > 0 else ''
            if 'jlog' not in branch:
                print("FAIL  「warm-up 计划不可用」这条跳过分支**没有日志** —— 静默跳过 = 假绿"
                      "（用户以为未初始化内存这个不确定性已消除）")
                bad += 1
            else:
                print("PASS  warm-up 跳过时有日志（不静默）")

    # ④ 失败**不影响加载**（这条口径必须保留：不让一次热身把功能判死）
    if 'warm-up decode 失败（不影响加载' not in warm:
        print("FAIL  找不到「warm-up decode 失败（不影响加载…）」这条分支 —— "
              "把热身失败升级成加载失败等于用一次前向把整个功能判死")
        bad += 1
    else:
        print("PASS  warm-up 失败不影响加载（口径保留）")

    # ⑤ 「写满了没有」必须**如实**回答，不许假称写干净
    # ═══════════════════════════════════════════════════════════════════════
    # NONE/LAST 档的库契约就是"至少一个 output"，warm-up 写不满是库决定的。
    # 但不许说它"写干净了"——那会让下一个人以为未初始化内存已消除。
    if 'embedtext::warmup_wrote_all_rows' not in warm:
        print("FAIL  warm-up 没有用 warmup_wrote_all_rows 问「是不是每一行都写过了」—— "
              "NONE/LAST 档只写 1 行，而日志/代码会让人以为 buffer 已写干净"
              "（上一版正是这样假绿的）")
        bad += 1
    else:
        print("PASS  warm-up 用 warmup_wrote_all_rows 区分「写满」与「只写了部分行」")

    # ⑥ 不再用 BOS 单 token 那条路径（结构判据：batch 不再固定长度 1）
    # ═══════════════════════════════════════════════════════════════════════
    # ⚠ 这条锚"llama_batch_init 的第一个实参"：早先那版是字面量 1
    #（BOS 只有一个 token）。写回 1 就说明"只要一行"那套回来了。
    m_init = re.search(r'llama_batch_init\s*\(\s*([^,]+)\s*,', warm)
    if not m_init:
        print("FAIL  找不到 llama_batch_init（判据锚点失效）")
        bad += 1
    else:
        arg = m_init.group(1).strip()
        if re.fullmatch(r'1', arg):
            print("FAIL  warm-up 的 llama_batch_init 第一个实参是字面量 1 —— "
                  "又回到「BOS 单 token」那一版：CLS 上库直接 abort，"
                  "NONE/LAST 上库「替我们补」也补不出第二行（warm-up 等于没做）")
            bad += 1
        else:
            print(f"PASS  warm-up 的 batch 长度不是字面量 1（实为：{arg}）")

    # ⑦ 纯函数层：`warmup_plan_usable` 的下限必须是 ≥2 token
    # 上面几条钉的是 native 的接线，这一条钉的是判据本身对不对 ——
    # 把下限写成 `< 1`（等于不设限）时 ①~⑥ 一个字符都没变、照样绿，
    # 而 1 个 token 的退化形态会被放行（warm-up 白做）。
    hdr = _hdr_src()
    m_min = re.search(r'kWarmupMinTokens\s*=\s*(\d+)', hdr)
    if not m_min:
        print("FAIL  找不到 kWarmupMinTokens（判据锚点失效）")
        bad += 1
    elif int(m_min.group(1)) < 2:
        print(f"FAIL  kWarmupMinTokens = {m_min.group(1)} —— 1 个 token 时 CLS/MEAN 的"
              "「整段」只有第 0 行，与早先那版**等价**，warm-up 是假绿")
        bad += 1
    else:
        print(f"PASS  kWarmupMinTokens = {m_min.group(1)}（跨过 1-token 退化点）")
    m_usable = re.search(r'warmup_plan_usable\s*\([^)]*\)\s*\{(.*?)\n\}', hdr, re.S)
    if not m_usable:
        print("FAIL  抽不出 warmup_plan_usable 的函数体（判据锚点失效）")
        bad += 1
    else:
        body = m_usable.group(1)
        if 'logits_ok_for_library' not in body:
            print("FAIL  warmup_plan_usable 没有复用 logits_ok_for_library —— "
                  "「给库的门禁」抄成两处，改一处就会让 warm-up 与真实路径再次分家")
            bad += 1
        else:
            print("PASS  warmup_plan_usable 复用 logits_ok_for_library（同一道闸）")

    if bad:
        print(f"FAIL  warm-up 接线守卫：{bad} 条不通过")
        return 1
    print("PASS  warm-up 接线守卫：全部通过")
    return 0


if __name__ == '__main__':
    sys.exit(main())
