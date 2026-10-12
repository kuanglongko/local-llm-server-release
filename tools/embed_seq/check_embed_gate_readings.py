#!/usr/bin/env python3
"""判「两道门的**实数**在日志里能被读到」这件事真的做了（0.9.211 补的**纯读数**）。

═══════════════════════════════════════════════════════════════════════
为什么单列这一条：这不是"加一行日志"，是补一个**当时唯一缺的证据**
═══════════════════════════════════════════════════════════════════════
真机上用户报的是"合批从 8 改到 4 改到 2 **都闪退**，只有 1 不崩"。要判
"2 条为什么也会崩"，只需要两个数：**这一批真实提交了多少 token**，以及
**库的两扇门分别是多少** —— 而 0.9.210 及以前**两处一行都没打**：

  · `embed model ready` 打了 `n_ctx / n_ctx_seq / seq_max / pooling`，
    **独独没有 `n_batch` 与 `n_ubatch`** —— 而它们正是两道门的阈值本身；
  · `nativeEmbedBatch` 提交前**没有一行**报"本段几条 / 多少 token"。

于是"撞的是哪扇门"只能靠倒推，而两个方向的倒推都说得通（块太大 / 门太小）——
本仓库在 PR #285 的讨论里**照着倒推连错两轮**，两次都是拿一个没量过的数去解释崩溃。

⚠ 这一族读数的失效形态是**静默的**，所以必须静态钉：
  ① 打出来的必须是**库的实数**（`llama_n_batch` / `llama_n_ubatch`），
     **不能**打我们传给 `cp` 的那个值 —— 嵌入这条加载路径 `cp.n_batch` /
     `cp.n_ubatch` **一个都没显式设**（库默认），打 `cp.n_batch` 会打出 0
     （struct 默认值），比不打更坏：读的人会以为"门是 0"；
  ② 提交前那一行必须报**本段真实 token 数**（`segTokens`，即 `b.n_tokens`），
     不能报一个按每条预算 ×条数**推出来**的上限 —— 那正是"511 天花板"那个错；
  ③ 提交前那一行必须排在 `llama_decode` **之前** —— 排在之后时 abort 已经发生，
     这一行永远不落盘（与 `batch_fits_n_batch` 同一条理由）；
  ④ 它必须报的是**本段**（切段后）的数：撞门发生在段这一级。

用法：check_embed_gate_readings.py <llama_jni.cpp>
"""
import re
import sys
from pathlib import Path


def strip_comments_keep_strings(src: str) -> str:
    """剥注释、**保留字符串字面量**（判据要读日志文案本身）。

    `_fnbody.fn_body` 连字符串一起抹成 `""`（它只关心代码结构），
    而本文件的判据恰恰要读**日志文案里的字段名**（`n_batch=` / `提交`），
    所以不能复用它 —— 只剥注释，字符串原样留下。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c in '"\'':
            q, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                j += 1
            out.append(src[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def fn_span(src: str, name: str):
    """按真括号配对取出 `name` 的函数体（原始文本，含字符串），找不到返回 None。"""
    code = strip_comments_keep_strings(src)
    for m in re.finditer(r'\b' + re.escape(name) + r'\s*\(', code):
        k = m.end() - 1
        depth = 0
        while k < len(code):
            if code[k] == '(':
                depth += 1
            elif code[k] == ')':
                depth -= 1
                if depth == 0:
                    k += 1
                    break
            k += 1
        while k < len(code) and code[k] in ' \t\r\n':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        depth, start = 0, k
        while k < len(code):
            if code[k] == '{':
                depth += 1
            elif code[k] == '}':
                depth -= 1
                if depth == 0:
                    return code[start:k + 1]
            k += 1
    return None


def main():
    jni_p = sys.argv[1]
    jni = Path(jni_p).read_text(encoding='utf-8', errors='replace')
    fails = []

    # ── ① 加载期：`embed model ready` 必须报库的 `n_batch` / `n_ubatch` ──────
    load_body = fn_span(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel")
    if load_body is None:
        fails.append("找不到 nativeLoadEmbedModel 的函数体（判据锚点失效）")
    else:
        ready = re.search(r'LOGI\(\s*"embed model ready[^;]*;', load_body, re.S)
        if not ready:
            fails.append("nativeLoadEmbedModel 里没有 `embed model ready` 那一行（判据锚点失效）")
        else:
            line = ready.group(0)
            for key in ("n_batch", "n_ubatch"):
                if key not in line:
                    fails.append(f"`embed model ready` 没有报 {key} —— 两道门里少了这一扇，"
                                 "『撞的是 2048 还是 512』就只能靠倒推（本仓库照此推错过两轮）")
            # 必须是**读库的实数**，不是 `cp.<字段>`
            for fn, key in (("llama_n_batch", "n_batch"), ("llama_n_ubatch", "n_ubatch")):
                if not re.search(fn + r'\s*\(\s*E\.ctx\s*\)', load_body):
                    fails.append(f"加载期没有读库的 `{fn}(E.ctx)` —— 两道门的阈值"
                                 f"必须来自库（`E.ctx`），拿 `cp.{key}` 会打出 struct 默认值 0")
                if re.search(r'\bcp\.' + key + r'\b', line):
                    fails.append(f"`embed model ready` 打的是 `cp.{key}` —— 嵌入这条加载路径"
                                 f"**没有**显式设 `cp.{key}`（库默认），打出来是 0，比不打更坏")

    # ── ② 提交期：本段真实 token 数 + 条数 + 两道门，且排在 decode 之前 ──────
    batch_body = fn_span(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch")
    if batch_body is None:
        fails.append("找不到 nativeEmbedBatch 的函数体（判据锚点失效）")
    else:
        # 这一行必须同时报"本段条数/本段 token 数"，且把两道门并排打出来。
        submit = re.search(r'jlog\(\s*"\[embedBatch\]\s*提交[^;]*;', batch_body, re.S)
        if not submit:
            fails.append("nativeEmbedBatch 提交前没有一行报『本段 N 条 / M tok』——"
                         "用户报『合批 <N 就闪退』时，最要紧的那个数（真实提交量）"
                         "就是它，而它此前一行都没打")
        else:
            s = submit.group(0)
            if "segTokens" not in s:
                fails.append("提交前那一行报的不是 `segTokens`（本段真实 token 数）——"
                             "拿『每条预算 × 条数』推一个上限正是『511 天花板』那个错")
            if "segRows" not in s:
                fails.append("提交前那一行没报本段**条数**（`segRows`）——"
                             "没有条数就无法把『2 条也崩』与容量对上")
            if "n_ubatch" not in s:
                fails.append("提交前那一行没把 `n_ubatch` 并排打出来 —— 非因果模型"
                             "（BERT 系 bge）撞的是这一扇（`:1426`），少了它就分不清两扇门")
            if not re.search(r'llama_n_ubatch\s*\(\s*E\.ctx\s*\)', batch_body):
                fails.append("提交前那一行没有读库的 `llama_n_ubatch(E.ctx)` ——"
                             "必须报库的实数，不是推出来的")
            # 顺序：这一行必须排在**同段内**的 `llama_decode(E.ctx, b)` 之前。
            # ⚠ 锚**那一行自己**的文案前缀（`[embedBatch] 提交：`），不是裸的"提交" ——
            # 后者在本函数里出现多次（`按容量切段提交` 等），拿"最早的『提交』"
            # 去比时，把这一行搬到 decode **之后**照样全绿（桩没打在判据锚的量上）。
            mark = batch_body.index("[embedBatch] 提交：")
            dec = re.search(r'llama_decode\(\s*E\.ctx\s*,\s*b\s*\)', batch_body)
            if not dec:
                fails.append("找不到 `llama_decode(E.ctx, b)`（判据锚点失效）")
            elif mark >= dec.end():
                fails.append("『本段 N 条 / M tok』那一行排在 `llama_decode` **之后** ——"
                             "之后等于装饰：abort 已经发生，这一行永远不落盘")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print("PASS  加载期报库的 n_batch / n_ubatch（`llama_n_*(E.ctx)`，不是 cp 的值）")
    print("PASS  提交前报本段真实 token 数（segTokens）与本段条数（segRows）")
    print("PASS  提交前那一行把两道门并排打出，且排在 llama_decode 之前")
    return 0


if __name__ == '__main__':
    sys.exit(main())
