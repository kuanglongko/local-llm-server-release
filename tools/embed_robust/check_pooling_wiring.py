#!/usr/bin/env python3
"""断言「池化语义交还模型」真的接上了：读 pooling_type → 算计划 → 按计划置 logits + 取行。

═══ 这一条守的是什么 ═══
句向量取哪一行**由模型在 GGUF 里声明**，不是我们能定的：
  · BGE 系（bge-small-zh-v1.5 / bge-large-zh-v1.5）—— CLS（取第 0 个 token）；
  · 生成式 decoder（Qwen3-Embedding）—— NONE / last（取最后一个）。

取错的表现**不报错**：BGE 上 100% 表现为"零范数"（非 CLS 位置没有过 LayerNorm，
范数量级不可控，float32 一平方就下溢成 0），而这与"模型坏了"长得一样。

⚠ 最容易漏的是**下标语义与 logits 置位的联动**：
  · `llama_batch.logits[j]` 决定 output 数组里有没有第 j 个 token；
  · `llama_get_embeddings_ith(ctx, i)` 的 `i` 是 **output 数组下标**，不是 batch 下标。
只改一处就出一个**不报错**的故障：要么"没要这一行却去读它"（返回 null → 检索全空，
接口 200），要么"要了 N 行只读第 0 行"（CLS/MEAN 静默退化成 last）。
所以判据必须同时钉住这两个字段**来自同一个计划**。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body  # noqa: E402



def _strip_cpp_comments(code: str) -> str:
    """去掉 C++ 的 // 行注释与 /* */ 块注释。

    ⚠ 判据里凡是要问"这段**代码**长什么样"的地方，都必须先过这一层：
    本仓库的注释密度极高，而注释里**天然会引用**被否定的旧写法
    （"这里**不得**再写成 `!library_gives_seq(t)`"）。直接对带注释的源码做
    子串匹配，会把"注释里提到了它"当成"代码里用了它" —— 判据方向正好反了。
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


def _extract_case_block(src: str, case_label: str):
    """抽 `case <label>: { ... }` 的**整块**（按花括号配平，不用非贪婪正则）。

    ⚠ 为什么必须配平：这一档的块里**自己**还有 `if (...) { ... }`，
    非贪婪 `(.*?)\n\s*\}` 会在第一个嵌套 `}` 处早退，
    于是判据只看得到块的前半段 —— 后半段的错（正是本次那些）一律看不见。
    这与本仓库反复记的"判据锚点看着像在判、其实判了个空"同源。
    """
    m = re.search(r'case\s+' + re.escape(case_label) + r'\s*:\s*\{', src)
    if not m:
        return None
    i = src.index('{', m.start())
    depth = 0
    j = i
    while j < len(src):
        if src[j] == '{':
            depth += 1
        elif src[j] == '}':
            depth -= 1
            if depth == 0:
                return src[m.start():j + 1]
        j += 1
    return None


def _extract_cpp_function(src: str, name: str):
    """把 `inline <ret> <name>(...) { ... }` 的整个函数体（含签名）切出来。"""
    m = re.search(r'\n[^\n]*\b' + re.escape(name) + r'\s*\([^;{]*\)\s*\{', src)
    if not m:
        return None
    start = m.start()
    i = src.index('{', m.start())
    depth = 0
    j = i
    while j < len(src):
        c = src[j]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[start:j + 1]
        j += 1
    return None


def _run_plan_pooling(plan_src: str, pool_type: int, n_tokens: int):
    """把 plan_pooling 的 C++ 体**逐分支翻译**成 Python 跑一遍，返回 want_logits。

    ⚠ 这里刻意"翻译"而不是"重新实现"：每一步都对应源码里的一行，
    所以要改这个函数就得同时改这里 —— 而本守卫的自测（桩⑮）会立刻发现漏改。
    返回 None = 翻译认不出来（判据锚点失效，必须红）。
    """
    if n_tokens <= 0:
        return None
    switch = re.search(r'switch\s*\(\s*p\.pool\s*\)\s*\{(.*?)\n    \}', plan_src, re.S)
    if not switch:
        return None
    body = switch.group(1)
    # 从分支里读出"哪些下标被置 1"：只认 `for (...) p.want_logits[i] = 1;` 与
    # `p.want_logits[<expr>] = 1;` 两种形态（源码里就这两种）。
    want = [0] * n_tokens
    for_start = body.find('case Pool::MEAN:')
    cls_start = body.find('case Pool::CLS:')
    last_start = body.find('case Pool::LAST:')
    if min(for_start, cls_start, last_start) < 0:
        return None
    seg = {'MEAN': body[for_start:cls_start], 'CLS': body[cls_start:last_start],
           'LAST': body[last_start:]}
    kind = {0: 'LAST', 3: 'LAST', -1: 'LAST', 1: 'MEAN', 2: 'CLS'}.get(pool_type)
    if kind is None:
        return None
    seg = seg[kind]
    # 整段置 1 的形态：`for (int i = 0; i < nTokens; i++) p.want_logits[...] = 1;`
    if re.search(r'for\s*\([^)]*nTokens[^)]*\)\s*p\.want_logits\[[^\]]*\]\s*=\s*1\s*;', seg):
        return [1] * n_tokens
    # 单点置 1 的形态：`p.want_logits[0] = 1;` / `p.want_logits[(size_t) (nTokens - 1)] = 1;`
    for idx_expr in re.findall(r'p\.want_logits\[([^\]]*)\]\s*=\s*1\s*;', seg):
        e = idx_expr.strip()
        if e == '0':
            want[0] = 1
        elif 'nTokens - 1' in e or 'nTokens-1' in e:
            want[n_tokens - 1] = 1
        else:
            return None
    return want


def _logits_ok_for_library(pool_type: int, want, n_tokens: int) -> bool:
    """与 embed_text.h 的 logits_ok_for_library **同一口径**（同样逐条翻译）。

    ⚠ 这里会与源码漂移，所以源码那一侧另有**宿主侧单测**钉住它（组 6b）——
    本函数只是让守卫能在没有编译器的环境里也判一次契约。
    """
    if n_tokens <= 0 or len(want) != n_tokens:
        return False
    ones = sum(1 for c in want if c)
    if pool_type in (1, 2):
        return ones == n_tokens
    if pool_type in (0, 3, -1):
        return ones >= 1
    return False


def _symptom(pool_type: int) -> str:
    if pool_type == 2:
        return "BGE 系（pooling=CLS）条条失败，且被记成 decode_failed（像内存问题）"
    if pool_type == 1:
        return "MEAN 的 decode 恒失败"
    return "output 一个都没有 -> 库的 n_outputs>=1 断言失败 = **进程崩溃**"

_HDR = None


def hdr_src() -> str:
    """embed_text.h 的正文（缓存；这条守卫与 self-test 桩都要读它）。"""
    global _HDR
    if _HDR is None:
        _HDR = Path('app/src/main/cpp/embed_text.h').read_text(encoding='utf-8')
    return _HDR


def main():
    src = Path(sys.argv[1]).read_text(encoding='utf-8')
    bad = 0

    # ① 加载期真的读并存下 pooling_type
    load, lline = fn_body(src, 'nativeLoadEmbedModel')
    if load is None:
        print("FAIL  找不到 nativeLoadEmbedModel（判据锚点失效）")
        return 1
    if 'llama_pooling_type' not in load:
        print("FAIL  nativeLoadEmbedModel 没有读 llama_pooling_type —— "
              "不读它就只能按 last-token 走，BGE 系必然零范数")
        bad += 1
    else:
        print("PASS  nativeLoadEmbedModel 读取了 llama_pooling_type")
    if not re.search(r'E\s*\.\s*pool_type\s*=', load):
        print("FAIL  pooling_type 没有被存进 Session（E.pool_type=）—— "
              "nativeEmbed 在另一跳里，存取不上就等于没读")
        bad += 1
    else:
        print("PASS  pooling_type 存进了 E.pool_type")

    # ② nativeEmbed 用计划（纯函数）决定池化
    body, line = fn_body(src, 'nativeEmbed')
    if body is None:
        print("FAIL  找不到 nativeEmbed 的函数体")
        return 1
    if 'embedtext::plan_pooling' not in body:
        print("FAIL  nativeEmbed 没有用 embedtext::plan_pooling（池化判据必须是纯函数，"
              "宿主侧才有单测钉四种类型的下标）")
        bad += 1
    else:
        print("PASS  nativeEmbed 用 embedtext::plan_pooling")

    # ③ logits 置位来自计划的 want_logits（不是硬编码"最后一个"）
    m = re.search(r'b\s*\.\s*logits\s*\[\s*i\s*\]\s*=\s*([^;]+);', body)
    if not m:
        print("FAIL  找不到 b.logits[i] 的赋值（判据锚点失效）")
        bad += 1
    elif 'want_logits' not in m.group(1):
        print(f"FAIL  b.logits[i] 的取值是「{m.group(1).strip()}」—— "
              "不是来自计划的 want_logits。硬编码\"最后一个\"会让 CLS/MEAN 模型取错位置")
        bad += 1
    else:
        print("PASS  b.logits[i] 取自计划的 want_logits")

    # ④ 读行也用同一个计划（output_slot / MEAN 分支）
    if 'llama_get_embeddings_ith' not in body:
        print("FAIL  找不到 llama_get_embeddings_ith（判据锚点失效）")
        bad += 1
    else:
        # ⚠ 必须锚**调用实参**，不能只判"函数体里出现过 plan.output_slot"——
        # 后者恒真：报错日志里也带着它（`output_idx=%d` 那一行传的就是 plan.output_slot）。
        # 把读取实参改成 `nTok - 1` 时，只要日志那行还在，恒真判据照样绿 ——
        # 而那正是本仓库反复记录的"判据写在别人身上"。本轮真踩到过一次。
        reads = re.findall(r'llama_get_embeddings_ith\s*\(\s*E\s*\.\s*ctx\s*,\s*([^)]+)\)', body)
        reads = [r.strip() for r in reads]
        if not reads:
            print("FAIL  找不到任何 llama_get_embeddings_ith(E.ctx, ...) 调用")
            bad += 1
        elif any(re.fullmatch(r'nTok\s*-\s*1|n_tokens\s*-\s*1|0', r) for r in reads):
            print(f"FAIL  读 embeddings 的实参里有**硬编码下标**（实为：{reads}）—— "
                  "`output_ids[0]` 只在「我们恰好只要了一行」时等于最后一个 token；"
                  "一旦库给的 output 更多（CLS/MEAN 整段、或库自己补 logits），"
                  "它就变成**第 0 个 token**（生成式 decoder 里是 BOS）→ "
                  "向量量级 1e-30 → 归一化失败，而接口 200")
            bad += 1
        elif not any(re.fullmatch(r'[A-Za-z_]\w*', r) for r in reads):
            print(f"FAIL  读 embeddings 的实参既不是硬编码下标、也不是一个具名局部量（实为：{reads}）—— "
                  "读哪一行必须由池化计划给出，否则与 want_logits 漂移")
            bad += 1
        else:
            # 具名局部量（`slot`）本身不够 —— 它可能是任何东西。真正的判据在
            # 下一条（④b）：那个名字必须由 `plan.row` 分岔出**最后一个 token**。
            print(f"PASS  读 embeddings 用一个具名下标（实为：{reads}；来源见下一条）")

        # ④b **语义与下标必须同源**：`row == LAST_TOKEN` 时读的必须是最后一个 token
        # ═══════════════════════════════════════════════════════════════════════
        # 这是本轮真 bug 的判据位。`plan.output_slot` 曾经对 LAST 恒为 0，
        # 而 0 在"只置了一个 logits"的前提下确实是最后一行 —— 前提一变就错。
        # 所以判据不能只看"实参叫什么名字"，要看**这个名字在语义上是哪一行**：
        #   · 抽取 plan_pooling 的 LAST 分支，核对 batch_last_slot 与 output_slot；
        #   · 抽取 native 的三元表达式，核对 LAST_TOKEN 走的是 batch_last_slot。
        # 只钉一头都可能是恒真：只钉纯函数，native 仍可能读 output_slot（等于 0，错）；
        # 只钉 native，纯函数仍可能把 batch_last_slot 算成 0。
        if not re.search(r'plan\s*\.\s*row\s*==\s*embedtext\s*::\s*SeqRow\s*::\s*LAST_TOKEN', body):
            print("FAIL  nativeEmbed 没有按 `plan.row == SeqRow::LAST_TOKEN` 分岔读哪一行 —— "
                  "语义（读最后一个 token）与下标（output_slot）会各算一套，"
                  "而两者只在一个前提下相等，前提一改就静默读错行")
            bad += 1
        else:
            m_last = re.search(
                r'plan\s*\.\s*row\s*==\s*embedtext\s*::\s*SeqRow\s*::\s*LAST_TOKEN\s*\?\s*'
                r'([A-Za-z_.]+)\s*:\s*([A-Za-z_.]+)', body)
            if not m_last:
                print("FAIL  找不出 LAST_TOKEN 三元表达式的两个分支（判据锚点失效）")
                bad += 1
            elif m_last.group(1) != 'plan.batch_last_slot':
                print(f"FAIL  LAST_TOKEN 分支读的是 `{m_last.group(1)}`，不是 plan.batch_last_slot —— "
                      "`output_slot` 对 LAST 档是「目标行在 output 里的排位」，"
                      "与「batch 里最后一个 token」不是一回事；混用会读到第 0 个 token（BOS）")
                bad += 1
            else:
                print(f"PASS  LAST_TOKEN 读 plan.batch_last_slot（另一分支：{m_last.group(2)}）")
        # ⚠ MEAN 的取值口在**本轮改过**：早先是"逐行累加再除以行数"（我们自己
        # 算一个近似库的池化），现在是 `llama_get_embeddings_seq(ctx, 0)`（读库
        # 给的那一个）。判据必须跟着现实走 —— 判"函数体里出现过 plan.output_count"
        # 在新实现下恒假，而判"出现过 get_embeddings_seq"在把实参改成别的 seq 时
        # 照样绿。所以锚**调用实参**。
        if 'llama_get_embeddings_seq' not in body:
            print("FAIL  MEAN 分支没有读 llama_get_embeddings_seq —— "
                  "自己按 output 行累加只是库的**近似**（分数像真的、语义错、不报错），"
                  "池化语义必须整个交还库")
            bad += 1
        else:
            seqs = re.findall(r'llama_get_embeddings_seq\s*\(\s*E\s*\.\s*ctx\s*,\s*([^)]+)\)', body)
            seqs = [x.strip() for x in seqs]
            if not any(x == '0' for x in seqs):
                print(f"FAIL  get_embeddings_seq 的 seq_id 实参不是 0（实为：{seqs}）—— "
                      "本实现只用 seq 0（单序列嵌入），写别的值会拿到 null 或别人的向量")
                bad += 1
            else:
                print(f"PASS  MEAN 走 llama_get_embeddings_seq(ctx, 0)（实参：{seqs}）")

    # ④b 读哪一路必须**由纯函数决定**，而不是"MEAN 走 seq、其余都走 ith"
    # ═══════════════════════════════════════════════════════════════════════
    # 为什么单列这一条：上面 ④ 只钉了"ith 的实参是 plan.output_slot"，
    # 而**没钉"什么时候才该走 ith"**。CLS 恰好就是不该走 ith 的那一档 ——
    # `ith` 的定义建在 `get_embeddings(ctx)` 有效之上，后者只在 pooling==NONE
    # 时成立；CLS 下它指向逐 token 的输出区，而库把池化结果写进独立的
    # `result_embd_pooled` 张量，读出来是**全零**（不是 null）。
    # 全零绕过 no_output 判据、直落 `范数=0` —— 这正是用户在 9b7335a 之后
    # 仍然看到的那一行日志（`pooling=2 … 范数=0`）。
    #
    # ④ 在旧实现下**全绿**：ith 的实参确实是 plan.output_slot，一件事都没错 ——
    # 错的是"CLS 根本不该走 ith 这条路"。所以这一条锚的是**分路判据本身**：
    # 必须调 `embedtext::read_from_seq`，且 ith 那一支必须在它的 else 里。
    if 'embedtext::read_from_seq' not in body:
        print("FAIL  nativeEmbed 没有用 embedtext::read_from_seq 决定读哪一路 —— "
              "CLS 会落进 get_embeddings_ith（它只在 pooling==NONE 时有效），"
              "读出全零而不是 null，直接表现为 `范数=0`（BGE 系条条失败）")
        bad += 1
    else:
        m3 = re.search(r'embedtext\s*::\s*read_from_seq\s*\(\s*([^)]*)\)', body)
        arg = m3.group(1).strip() if m3 else ''
        if arg != 'poolType':
            print(f"FAIL  read_from_seq 的实参不是 poolType（实为：{arg!r}）—— "
                  "分路依据必须是**模型声明的池化方式**，不是别的推导")
            bad += 1
        else:
            print("PASS  nativeEmbed 用 read_from_seq(poolType) 决定读哪一路")
        # ith 那一支必须在 read_from_seq 的**条件分支里**。
        #
        # ⚠ 只判"ith 出现在 read_from_seq 之后"是不够的 —— 下面这些都能骗过它：
        #   · `if (false && embedtext::read_from_seq(poolType))` —— 分路在，恒不成立；
        #   · `if (true || ...)` —— 分路在，恒成立；
        #   · 判据调用了，但结果没用在分支上（赋给一个没人读的变量）。
        # 所以锚的是**调用表达式本身是不是那个 if 的条件**：正则要求
        # `if ( <可含取反> read_from_seq(poolType) )` 这个形态，且取反不算
        # （`!read_from_seq` = "库自己池化时反倒走 ith"，是同一类错的反面）。
        guard = re.search(
            r'if\s*\(\s*(!?)\s*embedtext\s*::\s*read_from_seq\s*\(\s*poolType\s*\)\s*\)',
            body)
        if guard is None:
            print("FAIL  read_from_seq 不是 if 的**条件**（只是被调用过）—— "
                  "`if (false && …)` / 调了不判 / 判了不用 都能骗过上一版判据，"
                  "而它们都等于 CLS 又落回 get_embeddings_ith（本次失效）")
            bad += 1
        elif guard.group(1) == '!':
            print("FAIL  read_from_seq 被取反当条件（`!read_from_seq(...)`）—— "
                  "那等于'库自己池化时反倒走 ith'，是同一类错的反面")
            bad += 1
        else:
            print("PASS  read_from_seq(poolType) 是 if 的条件（分路真的生效）")
            # 光有分路还不够：ith 必须落在 else 那一支（即"不走 seq"才 ith）。
            i_guard = body.find(guard.group(0))
            i_ith = body.find('llama_get_embeddings_ith')
            if i_ith < 0:
                print("FAIL  找不到 llama_get_embeddings_ith（判据锚点失效）")
                bad += 1
            elif i_ith < i_guard:
                print("FAIL  get_embeddings_ith 出现在分路**之前** —— 说明它被无条件走了")
                bad += 1
            else:
                print("PASS  get_embeddings_ith 在分路之后（落在 else 支）")

    # ④c 分路判据自己必须与"库是否自己池化"一致（纯函数层）
    # 上面 ④b 钉的是"native 有没有调它"，这一条钉的是"它判得对不对"。
    # 两者缺一不可：把 read_from_seq 写成恒 false 时，④b 一个字符都没变、
    # 照样绿，而线上 CLS 又回到 ith（正是本次失效）。
    rfs_src = _extract_cpp_function(hdr_src(), 'read_from_seq')
    if rfs_src is None:
        print("FAIL  抽不出 read_from_seq 的函数体（判据锚点失效）")
        bad += 1
    else:
        # 逐条对照：库自己池化的那几档（MEAN=1 / CLS=2）必须为真，其余为假。
        lcp_src = _extract_cpp_function(hdr_src(), 'library_computes_pooling')
        for t in (0, 1, 2, 3, -1, 4):
            # 三档：库把池化结果写进 embd_seq 的是 MEAN/CLS/LAST。
            # NONE(0) 是唯一写 embd.data 的档；UNSPECIFIED(-1) 库侧会归成 NONE；
            # RANK(4) 我们根本不提交。
            want_seq = (t in (1, 2, 3))
            # 取"函数体里出现了哪个 case 常量"这一层可判定的形式：
            # read_from_seq 的写法是 `t == 1 || t == 2`（见 embed_text.h）。
            # 用正则抓它拿哪几个常量去比，比在 Python 里重跑实现更稳
            # （重跑=第二个真相来源）。这里只要求**集合相等**：拿 1 和 2。
            # ⚠ 0.9.160 起 read_from_seq 与 library_gives_seq **同一口径**
            #（前者是读取侧的入口名，后者是那条库前提的名字）。所以这里不再要求
            # 它自己写常量，而是要求它**复用** library_gives_seq —— 后者另有一条
            # 判据钉它的分路集合。抄成两处字面量正是本次这类漂移的回程票：
            # 改一处、另一处留着旧值，症状是"库算了池化、我们却按 token 读"。
            if 'library_gives_seq' not in rfs_src:
                nums = set(int(x) for x in re.findall(r'\bpoolTypeRaw\s*==\s*(-?\d+)', rfs_src))
                print(f"FAIL  read_from_seq 没有复用 library_gives_seq，而是自己写了一组常量 {sorted(nums)} —— "
                      f"同一条库前提被抄成两处，改一处就有一类模型读错 buffer（本次失效就是 CLS 那一档）")
                bad += 1
                break
            # 复用之后，集合本身由 library_gives_seq 的定义决定 —— 这里验它。
            lgs_src = _extract_cpp_function(hdr_src(), 'library_gives_seq')
            nums = set(int(x) for x in re.findall(r'poolTypeRaw\s*==\s*(-?\d+)', lgs_src or ''))
            # ⚠⚠ **三档**：`MEAN(1) / CLS(2) / LAST(3)`。
            # 判定依据不是"哪几档叫池化"，而是**库把结果写进哪个 buffer**：
            # vendor 里那个 switch 只把 `NONE(0)` 写进 `embd.data`（`ith` 读它），
            # `MEAN / CLS / LAST` 三档全部写进 `embd_seq`（`seq` 读它）。
            # 漏掉 `LAST` 的代价：`pooling=3` 的 Qwen3-Embedding 走 `ith` 去读一个
            # "分配了但从未写入"的 buffer → **全零**（不是 null，绕过 no_output 判据）
            # → `范数=0` 条条失败；而 `pooling=2` 的 BGE 走 seq 照常能用，
            # 于是看起来像"这个模型不行"。
            if nums != {1, 2, 3}:
                print(f"FAIL  library_gives_seq 拿来比较的常量是 {sorted(nums)}，"
                      f"应为 {{1, 2, 3}}（MEAN / CLS / LAST 是库把池化结果写进 "
                      f"embd_seq 的那三档）—— 少一档就有一类模型去读一个**从未写入**的 "
                      f"buffer，拿到全零（本次失效就是 LAST 那一档）")
                bad += 1
                break
            if want_seq and t not in nums:
                print(f"FAIL  read_from_seq 对 pooling={t} 不成立（该类模型的池化结果在库的独立 buffer 里）")
                bad += 1
                break
        else:
            print(f"PASS  read_from_seq 的分路集合 = {{1, 2}}（MEAN / CLS）")
        # 与 library_computes_pooling 的**包含关系**：库自己池化 => 必须走 seq。
        if lcp_src is not None:
            lcp_nums = set(int(x) for x in re.findall(r'poolTypeRaw\s*==\s*(-?\d+)', lcp_src))
            if not lcp_nums <= {1, 2, 3}:
                print(f"FAIL  library_computes_pooling 认的常量 {sorted(lcp_nums)} 超出 read_from_seq 的 {{1,2,3}} —— "
                      "会出现「库算了池化、我们却按 token 读」")
                bad += 1
            else:
                print(f"PASS  library_computes_pooling 的分支（{sorted(lcp_nums)}）被 read_from_seq 覆盖")

    # ⑤ 不受支持的池化类型必须拒绝（不猜）
    if not re.search(r'!\s*plan\s*\.\s*valid', body):
        print("FAIL  nativeEmbed 没有检查 plan.valid —— "
              "RANK（rerank 模型）会被当成句向量用，结果「有分数、语义全错」")
        bad += 1
    else:
        print("PASS  nativeEmbed 检查 plan.valid（不支持的池化类型拒绝）")

    # ⑤b **计划自洽**必须在填 batch/取向量**之前**自检
    # ═══════════════════════════════════════════════════════════════════════
    # ④/④b 守的是"计划本身对不对"，这一条守的是"native 有没有在**动手之前**
    # 核对计划自洽"。缺了它，一个不变量被破坏时没有归因：表现是
    # `get_embeddings_ith` 返回 null（检索全空、接口 200）或静默读到别的行，
    # 而这与"embeddings 没设上""库版本不同"长得一模一样。
    # 判据锚**调用实参**（plan + nTok），并锚它在读向量之前。
    if 'embedtext::plan_is_coherent' not in body:
        print("FAIL  nativeEmbed 没有自检「计划自洽」（plan_is_coherent）—— "
              "`row` 说读最后一个 token、`output_slot` 却指到别处时，"
              "报应不是「这一条失败」而是静默读到 BOS 行（范数 ~1e-30）")
        bad += 1
    else:
        m_c = re.search(r'embedtext\s*::\s*plan_is_coherent\s*\(\s*([^)]*)\)', body)
        args = [a.strip() for a in m_c.group(1).split(',')] if m_c else []
        if len(args) != 2 or args[0] != 'plan' or args[1] != 'nTok':
            print(f"FAIL  plan_is_coherent 的实参不对（实为：{args}）—— "
                  "必须拿**模型声明的池化方式算出的那份计划**去比它的 token 数")
            bad += 1
        elif body.find('embedtext::plan_is_coherent') > body.find('llama_get_embeddings_ith'):
            print("FAIL  计划自洽自检排在**读向量之后** —— 那时错的字节已经读出来了")
            bad += 1
        else:
            print(f"PASS  nativeEmbed 在取向量之前自检计划自洽（实参：{args}）")

    # ⑦ decode **之前**自检"我们填的 logits 满足库对该池化方式的前置要求"
    # ═══════════════════════════════════════════════════════════════════════
    # 为什么单列一条：CLS 的"只给第 0 个 token 置 1"在语义上是对的（取对了位置），
    # 却违反库的前置校验（`pooled embedding requires that all tokens are output`），
    # 于是 `llama_decode` 恒失败 —— **BGE 系条条失败**，而失败被记成 decode_failed，
    # 读起来像内存/模型问题。上面 ③④ 两条当时**全绿**：判据只钉了"读对位置"，
    # 没钉"库答不答应"。这一条补的就是那半张网。
    # 判据锚**调用实参**（pure 函数的 name 与两个入参），不锚"文件里出现过"。
    if 'embedtext::logits_ok_for_library' not in body:
        print("FAIL  nativeEmbed 没有在 decode 之前自检 logits 是否满足库的池化契约 —— "
              "CLS 只置第 0 个时库会直接拒绝 decode（BGE 系条条失败），"
              "而这条失败在日志里与'内存不够'长得一样")
        bad += 1
    else:
        m2 = re.search(r'embedtext\s*::\s*logits_ok_for_library\s*\(\s*([^)]*)\)', body)
        args = [a.strip() for a in m2.group(1).split(',')] if m2 else []
        if len(args) != 3 or args[0] != 'poolType' or 'plan.want_logits' not in args[1]:
            print(f"FAIL  logits_ok_for_library 的实参不对（实为：{args}）—— "
                  "必须拿**模型声明的池化方式**去比**我们实际填的那份 logits**")
            bad += 1
        else:
            print(f"PASS  nativeEmbed 自检 logits 契约（实参：{args}）")
        # 自检必须在 decode **之前**（否则失败了也回不去）
        i_check = body.find('embedtext::logits_ok_for_library')
        i_decode = body.find('llama_decode(E.ctx, b)')
        if i_decode < 0:
            print("FAIL  找不到 llama_decode(E.ctx, b)（判据锚点失效）")
            bad += 1
        elif i_check > i_decode:
            print("FAIL  logits 契约自检出现在 llama_decode **之后** —— "
                  "那时 decode 已经失败，自检救不回来，也说不清是为什么")
            bad += 1
        else:
            print("PASS  logits 契约自检在 llama_decode 之前")
        if 'pooling_contract' not in body and 'POOLING_CONTRACT' not in body:
            print("FAIL  自检失败时没有记成 pooling_contract 原因码 —— "
                  "UI/日志会把它读成 decode_failed（又一个'像内存问题'）")
            bad += 1
        else:
            print("PASS  自检失败记成 pooling_contract（可归因）")

    # ⑦b **纯函数自己**必须产出满足契约的 want_logits
    # ═══════════════════════════════════════════════════════════════════════
    # ⑦ 守的是"native 有没有去自检"，这一条守的是"自检**通得过**"——
    # 两者缺一不可：只做 ⑦ 的话，把 `plan_pooling` 的 CLS 分支改回"只置第 0 个"
    # （桩⑮）时，native 那边的自检调用**一个字符都没变**，⑦ 照样绿，
    # 而线上就是 BGE 系条条失败。这正是"判据写在别人身上"的又一次变体。
    #
    # 为什么用 execute 而不是文本匹配纯函数：这一个函数的价值**就是它的行为**
    #（"哪种池化该置哪几位"），文本匹配只能描到它的形状。而它是无依赖纯函数
    #（只用 vector），**可以原样取出来在 Python 里跑**（逐行翻译成等价的 Python，
    # 而不是重写一遍逻辑 —— 重写就是第二个真相来源，漂移了没人发现）。
    plan_src = _extract_cpp_function(hdr_src(), 'plan_pooling')
    coh_src = _extract_cpp_function(hdr_src(), 'plan_is_coherent')
    if plan_src is None:
        print("FAIL  抽不出 plan_pooling 的函数体（判据锚点失效）")
        bad += 1
    else:
        cases = [(0, 1), (0, 4), (3, 1), (3, 4), (-1, 4), (1, 1), (1, 4), (2, 1), (2, 4)]
        for t, n in cases:
            want = _run_plan_pooling(plan_src, t, n)
            if want is None:
                print(f"FAIL  plan_pooling({t},{n}) 跑不动（翻译/抽取失效）")
                bad += 1
                continue
            ok_contract = _logits_ok_for_library(t, want, n)
            if not ok_contract:
                print(f"FAIL  plan_pooling({t},{n}) 产出的 want_logits（{want}）"
                      f"**违反库的前置契约** —— pooling={t} 时库会拒绝 "
                      f"{'decode（要求整段）' if t in (1, 2) else '这个 batch（要求≥1 个 output）'}；"
                      f"线上表现是 {_symptom(t)}")
                bad += 1
            else:
                print(f"PASS  plan_pooling({t},{n}) 满足库契约（want_logits={want}）")

    # ⑦c `plan_pooling` 的 LAST 档必须把 `batch_last_slot` 指到**最后一个 token**，
    #     且 `output_slot` 与它一致；CLS 档必须 `output_slot == 0`。
    # 判据锚**字面量关系**（不是"文件里出现过 batch_last_slot"那种恒真式）。
    if plan_src is None:
        print("FAIL  抽不出 plan_pooling（⑦c 依赖它）")
        bad += 1
    else:
        m_last_seg = re.search(r'case\s+Pool::LAST\s*:(.*?)break\s*;', plan_src, re.S)
        if not m_last_seg:
            print("FAIL  找不到 plan_pooling 的 Pool::LAST 分支（判据锚点失效）")
            bad += 1
        else:
            # ⚠ 先去掉注释：本仓库的注释密度极高，而注释里**天然会引用**这个写法（"也就是 `library_gives_seq(poolTypeRaw)`"）。
            # 直接对带注释的源码做子串匹配，会把"注释里提到了它"当成"代码里用了它" ——
            # 判据方向正好反了（本桩的自测会立刻发现：真代码被改坏时判据不红）。
            seg = _strip_cpp_comments(m_last_seg.group(1))
            # ⚠ `Pool::LAST` 这一支覆盖三个**原始** pool 值：`NONE(0)` / `LAST(3)`
            # / `UNSPECIFIED(-1)`。三者的**语义**都是 last-token，但库把它们放在
            # **两个不同的 buffer** 里：
            #   · `NONE(0)`  → 逐 token 写 `embd.data`，读 `ith`；
            #   · `LAST(3)`  → 池化后写 `embd_seq`，读 `seq`（`embd.data` **不写**）。
            # 所以这一次分岔的判据**不能是 `p.pool`**（它把两者合并了），
            # 必须是原始值 —— `library_gives_seq(p.pool_type_raw)`。
            #
            # 漏掉这一次分岔的代价（真机）：`pooling=3` 走 `ith` 读从未写入的
            # buffer → 全零 → `范数=0` 条条失败。上面 ④c 钉的是
            # `library_gives_seq` **集合**对不对，这里钉的是**这一支有没有用它**。
            if not re.search(r'library_gives_seq\s*\(\s*(?:p\s*\.\s*pool_type_raw|poolTypeRaw)\s*\)', seg):
                print("FAIL  plan_pooling 的 LAST 支没有按 `library_gives_seq("
                      "p.pool_type_raw)` 再分岔一次 —— `Pool::LAST` 里 `NONE(0)` "
                      "与 `LAST(3)` 的池化结果在库的**两个不同 buffer** 里，"
                      "不分岔就会有一档去读**从未写入**的 `embd.data`（全零 → 范数=0）")
                bad += 1
            elif not re.search(r'batch_last_slot\s*=\s*0\s*;', seg):
                print("FAIL  LAST 档没有把 batch_last_slot 设为 0（= output 数组里"
                      "唯一那一项的排位）—— 它同时保证了"
                      "`output_ids[batch_last_slot] == nTokens-1`")
                bad += 1
            elif not re.search(r'want_logits\[\(size_t\)\s*\(\s*nTokens\s*-\s*1\s*\)\]\s*=\s*1', seg):
                print("FAIL  LAST 档没有把**最后一个**位置的 logits 置 1 —— "
                      "`batch_last_slot` 就指不到 `execution.last`（output 为空 → 库断言崩）")
                bad += 1
            # ⚠ 这一次分岔还必须**落到 `output_slot` 上**：写了分岔、却仍把
            # `output_slot` 写成一个字面量（0），读取点会拿着这个假下标去
            # `ith`（对 LAST(3) 不成立），或让 `plan_is_coherent` 判不自洽。
            # 判据不能只问"有没有 library_gives_seq"（那一条上面已经问了）——
            # 它必须在**同一行里**同时出现"池化档 → -1"与"逐 token 档 → batch_last_slot"。
            elif not re.search(r'p\s*\.\s*output_slot\s*=\s*p\s*\.\s*row\s*==\s*'
                               r'SeqRow\s*::\s*LIB_POOLED\s*\?\s*-1\s*:\s*p\s*\.\s*batch_last_slot',
                               seg):
                print("FAIL  LAST 档的 `output_slot` 不是按同一次分岔给的 —— "
                      "池化档必须是 **-1**（不给一个「看起来能用」的下标），"
                      "逐 token 档必须取自 `batch_last_slot`。写成一个字面量（0）时，"
                      "读取点会拿它去 `ith`，而那条路对 LAST(3) 没有数据")
                bad += 1
            else:
                print("PASS  LAST 档：want_logits 置最后一位 + 按 library_gives_seq(pool_type_raw) 分岔"
                      "（池化档 output_slot=-1 / 逐 token 档取 batch_last_slot）")
        m_cls_seg = re.search(r'case\s+Pool::CLS\s*:(.*?)break\s*;', plan_src, re.S)
        if m_cls_seg and not re.search(r'SeqRow::LIB_POOLED', m_cls_seg.group(1)):
            print("FAIL  CLS 档的 row 不是 LIB_POOLED —— CLS 的池化结果在库的 "
                  "`embd_seq` 里（`library_gives_seq(2)` 为真），我们只能读它给的那一份；"
                  "写成去 `output` 数组挑一行，那个字段说着另一套（没被使用的）做法，"
                  "正是本仓库反复记录的那类伏笔")
            bad += 1
        elif m_cls_seg:
            print("PASS  CLS 档 row = LIB_POOLED（读库给的 embd_seq 那一份）")

    # ⑦c-bis **自洽判据自己必须按「排位→位置」换算，不能拿排位与位置比大小**
    # ═══════════════════════════════════════════════════════════════════════
    # 这一条锚的是**本 PR 的真 bug**，而上面 ⑦c 在它发生时**全绿** ——
    # 7c 钉的是"`plan_pooling` 填了什么"（填的确实对：`batch_last_slot = 0`），
    # 而失效出在 `plan_is_coherent` 拿 **batch 位置** 去等于 **output 排位**：
    #
    #   `if (p.batch_last_slot != nTokens - 1) return false;`
    #
    # LAST 档只给最后一位置 logits → output 里只有 1 项，**排位是 0、位置是
    # nTokens-1**，判据要求 `0 == nTokens-1` 恒假 → **每一条嵌入都被判
    # "计划不自洽"**，用户在 0.9.160 之后看到的就是：
    #
    #   `[embed] 池化计划不自洽：pooling=3 row=0 output_slot=0`
    #   （row=0 是 LAST_TOKEN、output_slot=0 是**正确**的排位）
    #
    # 所以判据要正面钉住"换算"这一步：`plan_is_coherent` 的 LAST 分支必须
    # 调 `output_slot_to_batch`（唯一真相来源），且**不得**出现
    # `batch_last_slot != nTokens - 1` 这种"排位 == 位置"的写法。
    if coh_src is None:
        print("FAIL  抽不出 plan_is_coherent（⑦c-bis 依赖它）")
        bad += 1
    else:
        m_coh_last = _extract_case_block(coh_src, 'Pool::LAST')
        if not m_coh_last:
            print("FAIL  找不到 plan_is_coherent 的 LAST 分支（判据锚点失效）")
            bad += 1
        else:
            cseg = m_coh_last  # 整块（含 case 标签外的花括号）
            # ⚠ 这一支覆盖 `NONE(0)`（读 ith）与 `LAST(3)`（读 seq）两种情形，
            # 所以判据要**两头都看**：
            #   · `library_gives_seq(raw)` 为真（LAST(3)）→ row 必须是 LIB_POOLED、
            #     output_slot 必须是 -1（不给一个"看起来能用"的下标）；
            #   · 为假（NONE(0) / UNSPECIFIED(-1)）→ 必须用 `output_slot_to_batch`
            #     问"读的那一项是不是最后一行"（拿排位与位置比大小就在这一支错的）。
            #
            # ① 必须先按原始 pool 值分岔（写成"只看 row == LAST_TOKEN"会在
            #    `pooling=3` 上**绿着**通过，而那一档真的去读了从未写入的 buffer）
            if 'library_gives_seq' not in cseg:
                print("FAIL  plan_is_coherent 的 LAST 分支没有按 `library_gives_seq("
                      "p.pool_type_raw)` 分岔 —— 写成只看 `row == LAST_TOKEN` 会在 "
                      "`pooling=3` 上绿着通过，而那一档真的去读了从未写入的 buffer（全零）")
                bad += 1
            # ② 反面钉住：不许再出现"排位 == nTokens-1"这种把两个语义混起来的写法
            elif re.search(r'batch_last_slot\s*!=\s*nTokens\s*-\s*1', cseg):
                print("FAIL  plan_is_coherent 仍把 batch_last_slot 当**batch 位置**"
                      "（`!= nTokens-1`）比 —— 它是 **output 数组里的排位**，"
                      "NONE 档恒为 0，与 nTokens-1 永不相等")
                bad += 1
            # ③ NONE 那一支的换算结果必须真的落在最后一行（不是"调过就算"）
            elif not re.search(r'output_slot_to_batch\s*\(\s*p\s*,\s*p\s*\.\s*output_slot\s*\)'
                               r'\s*!=\s*nTokens\s*-\s*1', cseg):
                print("FAIL  LAST 分支（NONE 那一支）调了 output_slot_to_batch，"
                      "但没拿它的**结果**去对 `nTokens-1` —— 判据要问的是「读的那一项"
                      "换算回 batch 位置，是不是最后一行」，不是「这函数被调用过」")
                bad += 1
            else:
                print("PASS  plan_is_coherent 的 LAST 分支：按 library_gives_seq 两头分岔"
                      " + NONE 支按「排位→位置」换算（output_slot_to_batch(...) == nTokens-1）")
    # 换算函数本身必须存在，且按 `want_logits` 里 1 的升序排位算
    if coh_src is None or 'output_slot_to_batch' not in hdr_src():
        print("FAIL  embed_text.h 缺少 output_slot_to_batch —— 「output 排位」与"
              "「batch 位置」的换算没有唯一真相来源，两处各写一套迟早漂移")
        bad += 1
    else:
        m_conv = re.search(r'inline\s+int\s+output_slot_to_batch\s*\([^)]*\)\s*\{([^}]*)\}',
                           hdr_src(), re.S)
        if not m_conv:
            print("FAIL  抽不出 output_slot_to_batch 的实现（判据锚点失效）")
            bad += 1
        elif 'want_logits' not in m_conv.group(1):
            print("FAIL  output_slot_to_batch 没有按 want_logits 数 1 的序号 —— "
                  "`output_ids` 只收 logits!=0 的 token 且按出现顺序连续排列，"
                  "这个顺序就是唯一的排位定义")
            bad += 1
        else:
            print("PASS  output_slot_to_batch 按 want_logits 的 1 序号换算")

    # ⑦d 归一化口径必须与"向量从哪里来"同源（否则会把库给的合法向量判成失败）
    if 'normalize_pooling' not in hdr_src():
        print("FAIL  embed_text.h 缺少 normalize_pooling —— "
              "库里已归一化的档（CLS/MEAN）会被我们再判一次「范数是否为 0」，"
              "把合法向量报成 normalization_failed（用户去查文本/模型，方向错）")
        bad += 1
    else:
        m_norm = re.search(r'const\s+bool\s+we_normalize\s*=\s*([^;]+);', body)
        if not m_norm:
            print("FAIL  nativeEmbed 没有把「这次要不要我们归一化」变成显式判据")
            bad += 1
        elif 'normalize_pooling' not in m_norm.group(1):
            print(f"FAIL  归一化分岔用的是「{m_norm.group(1).strip()}」而不是纯函数 "
                  "embedtext::normalize_pooling（口径散在 JNI 里，宿主侧测不到）")
            bad += 1
        else:
            print(f"PASS  归一化分岔走 embedtext::normalize_pooling（实参：{m_norm.group(1).strip()}）")
        # 同一档的"读哪里"与"要不要归一化"必须读**同一个库条件**
        # —— 把 normalize_pooling 写成 `return true;`（永远归一化）时，
        # 库给的那一份会被我们重算一次；而判据若只判"出现过 normalize_pooling"
        # 照样绿。所以正面钉它与 library_gives_seq 的关系。
        m_np = re.search(r'inline\s+bool\s+normalize_pooling\s*\([^)]*\)\s*\{[^}]*?return\s+([^;]+);', hdr_src(), re.S)
        if not m_np:
            print("FAIL  抽不出 normalize_pooling 的实现（判据锚点失效）")
            bad += 1
        # ⚠⚠ 0.9.162 起这里**反过来**：`normalize_pooling` **不得**再是
        # `library_gives_seq` 的背面。那个等式被证伪了 —— 库对池化结果**不做 L2**
        #（`build_pooling` 只在 RANK 支过 `cls_norm`），做归一化的是
        # `cui-llama.rn` 的 RN 包装层（`common_embd_normalize`），而本 App 直接调
        # C API `llama_get_embeddings_seq`，**不经过那一层**。
        # 把它写成 `!library_gives_seq(t)` 的代价：MEAN/CLS/LAST 三档的向量
        # **永远不被归一化**，而检索侧的 `cosine` 是**纯点积** ——
        # 前提就是两边都归一化过。症状：排序按向量长度而不是语义（静默）。
        m_np_body = re.search(r'inline\s+bool\s+normalize_pooling\s*\([^)]*\)\s*\{(.*?)\n\}',
                              hdr_src(), re.S)
        if not m_np_body:
            print("FAIL  抽不出 normalize_pooling 的函数体（判据锚点失效）")
            bad += 1
        elif 'library_gives_seq' in _strip_cpp_comments(m_np_body.group(1)):
            print("FAIL  normalize_pooling 又变成 library_gives_seq 的背面了 —— "
                  "那个等式**已被证伪**：库对池化结果不做 L2（`build_pooling` 只在 "
                  "RANK 支过 cls_norm），做归一化的是 cui-llama.rn 的 RN 包装层，"
                  "而本 App 直接调 C API、不经过它。这一档若跳过归一化，"
                  "MEAN/CLS/LAST 的向量就永远不是单位长度，而检索侧 cosine 是纯点积")
            bad += 1
        elif not re.search(r'return\s+true\s*;', _strip_cpp_comments(m_np_body.group(1))):
            print(f"FAIL  normalize_pooling 的返回值不是恒真 —— 库对池化结果不做 L2，"
                  f"每一档都必须由我们归一化")
            bad += 1
        else:
            print("PASS  normalize_pooling 恒为真（库不做 L2，每一档都由我们归一化）")

    # ⑧ embed_text.h 里的纯函数契约判据（宿主侧有单测钉两头）
    for needle, desc in (('logits_ok_for_library', 'logits_ok_for_library 纯函数'),
                         ('library_computes_pooling', 'library_computes_pooling 纯函数')):
        if needle not in hdr_src():
            print(f"FAIL  embed_text.h 里缺少 {desc}")
            bad += 1
        else:
            print(f"PASS  embed_text.h 提供了 {desc}")
    if 'POOLING_CONTRACT' not in hdr_src():
        print("FAIL  embed_text.h 的 EmbedFailure 里没有 POOLING_CONTRACT 原因码")
        bad += 1
    else:
        print("PASS  EmbedFailure 有 POOLING_CONTRACT 原因码")

    # ⑥ 纯函数本身在 embed_text.h 里，且判据与实现同源
    hdr = hdr_src()
    for needle, desc in (('plan_pooling', 'plan_pooling 纯函数'),
                         ('PoolPlan', 'PoolPlan 结构'),
                         ('want_logits', 'want_logits 字段'),
                         ('output_slot', 'output_slot 字段')):
        if needle not in hdr:
            print(f"FAIL  embed_text.h 里缺少 {desc}")
            bad += 1
    if bad == 0:
        print("PASS  embed_text.h 提供了 plan_pooling / PoolPlan（与实现同源）")
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
