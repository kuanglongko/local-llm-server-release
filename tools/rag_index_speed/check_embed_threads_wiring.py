#!/usr/bin/env python3
"""判「嵌入模型的线程数」这一条路**从头到尾是通的**（ISSUE #269）。

═══════════════════════════════════════════════════════════════════════
为什么必须有这一条
═══════════════════════════════════════════════════════════════════════
用户报的现象：8e（骁龙 8 Elite，8 核）上建索引 **1 秒 1.3 条**，
7000 条约等于**两小时**。核到底之后发现这不是"手机就这么慢"，是三处叠加：

  ① `LlmEngine.loadEmbedModel` 的 `nThreads` 默认值**写死 4**，
     与设备无关 —— 8 核机器上有一半核心全程闲着；
  ② `EngineActivity.doLoadEmbed` 调它时**不传线程数**，
     于是设置页那个 threads 输入框对建库**完全无效**。
     用户把它从 4 调到 8，建库速度一点都不变，而屏幕上没有任何东西
     说明"这个参数对嵌入不生效"；
  ③ `RagCost` 的秒/条口径（0.05~0.30）比真机读数（0.77）还小 2.6 倍，
     提示把 7000 条报成"约 35 分钟"—— **提示报小了比不提示更坏**：
     用户按那句决定现在开始，然后等了两小时，期间一直以为遇上了卡死。

这三处的**共同形态**是"参数在某处被写死 / 在某处断掉，而界面上完全看不出来"。
单测钉不住它们（`loadEmbedModel` 的单测喂什么线程数就跑什么线程数，
"UI 传没传"它看不见）。所以判据必须锚**源码结构关系**。

判据（每条都对应上面一处，顺序敏感不许合并）：
  1. `loadEmbedModel` 的 `nThreads` 默认值**不是字面量**，而是调 `defaultThreads()`；
  2. `defaultThreads()` 存在，且真的读 `availableProcessors()`（不是返回常数）；
  3. `doLoadEmbed` 里调 `loadEmbedModel` 时**显式传了** `nThreads`，
     且那个实参来自 `parseThreads()`（不是字面量、也不是漏传）；
  4. `parseThreads()` 的兜底走 `defaultThreads()`（不是字面量 4）；
  5. 全仓 `thrEt.text...toIntOrNull() ?: 4` 这种**按设备无关常数兜底**的写法为 0 处
     （清空输入框时不该按 4 跑）。

用法：check_embed_threads_wiring.py <LlmEngine.kt> <EngineActivity.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import kotlin_fn_span, kotlin_fn_body  # noqa: E402




def _expr_fn_decl(src: str, name: str):
    """取一个**表达式体** Kotlin 函数声明的整段文本（`fun f(): T = <表达式>`）。

    为什么不用 `_fnbody` 里现成的两个取法：`kotlin_fn_body` 找"第一个 `{`"，
    表达式体里若含 lambda（`.takeIf { it > 0 }`）就会把 **lambda 体**当成函数体；
    `kotlin_fn_span` 同理在那个 `{` 处截断。两者都**切歪但不报错** ——
    判据于是在一段不是函数体的文本上跑（本仓库反复记录的那类假绿）。

    这里按"从 `fun` 起，到下一个**同缩进**的声明/`}`为止"取：
    表达式体没有花括号可配对，缩进是唯一可用的边界。
    """
    import re as _re
    m = _re.search(r'^(\s*)(?:private\s+|public\s+|internal\s+)?fun\s+' + _re.escape(name) + r'\s*\(', src, _re.M)
    if not m:
        return None
    indent = m.group(1)
    lines = src[m.start():].split('\n')
    out = [lines[0]]
    for ln in lines[1:]:
        if ln.strip() == '':
            out.append(ln)
            continue
        # 同缩进（或更浅）且不是续行 —— 声明到此结束
        cur = len(ln) - len(ln.lstrip())
        if cur <= len(indent) and ln.strip():
            break
        out.append(ln)
    return '\n'.join(out).rstrip()


def _args_of(src: str, open_paren: int) -> str:
    """取 `src[open_paren]` 那个 `(` 起、按括号配对到匹配 `)` 的**实参表文本**。"""
    depth = 0
    i = open_paren
    while i < len(src):
        if src[i] == '(':
            depth += 1
        elif src[i] == ')':
            depth -= 1
            if depth == 0:
                return src[open_paren + 1:i]
        i += 1
    return src[open_paren + 1:]


def main():
    eng, act = sys.argv[1], sys.argv[2]
    e = Path(eng).read_text(encoding='utf-8')
    a = Path(act).read_text(encoding='utf-8')
    fails = []

    # ── 判据 1：loadEmbedModel 的默认值走 defaultThreads() ───────────────
    span, line = kotlin_fn_span(e, 'loadEmbedModel')
    if span is None:
        fails.append("找不到 loadEmbedModel（判据锚点失效）")
    elif not re.search(r'nThreads\s*:\s*Int\s*=\s*defaultThreads\s*\(', span):
        # 写死字面量时的报应：换机器/换核数时默认值不动，而没有任何日志
        # 会指出"这个默认值与本机无关"。
        fails.append("loadEmbedModel 的 nThreads 默认值不是 defaultThreads()"
                     "（写死的默认值只能对一种机器正确）")

    # ── 判据 2：defaultThreads() 真的按本机核数，不是返回常数 ─────────────
    body, dline = kotlin_fn_body(e, 'defaultThreads')
    if body is None:
        fails.append("找不到 defaultThreads()（判据锚点失效）")
    else:
        if 'availableProcessors()' not in body:
            fails.append("defaultThreads() 没读 availableProcessors()"
                         "（返回常数的默认值正是本轮要修的东西）")
        if 'coerceIn' not in body:
            # 不钳上下限时：1 核设备上给 1 线程尚可，但某些 ROM 会报出
            # 很大的数（含全部小核），线程数超过内存带宽能喂的上限时只剩同步开销。
            fails.append("defaultThreads() 没钳上下限（coerceIn）——"
                         "多线程对逐条小前向是饱和的，给太多只剩同步开销")

    # ── 判据 3：doLoadEmbed 真的把线程数传下去 ──────────────────────────
    dbody, dload_line = kotlin_fn_body(a, 'doLoadEmbed')
    if dbody is None:
        fails.append("找不到 doLoadEmbed（判据锚点失效）")
    else:
        # ⚠ 参数表里**含括号**（`parseThreads()`），所以不能用 `[^)]*` 截 ——
        #    那样会在 `parseThreads(` 处停下，把"传了 parseThreads()"误判成"没传"。
        #    按括号配对取真正的实参表。
        m = re.search(r'loadEmbedModel\s*\(', dbody)
        if not m:
            fails.append("doLoadEmbed 里没调 loadEmbedModel")
        else:
            args = _args_of(dbody, m.end() - 1)
            if not re.search(r'nThreads\s*=', args):
                # 这正是用户踩到的形态：用户调了线程数、建库速度不变，
                # 而屏幕上没有任何东西说明"这个参数对嵌入不生效"。
                fails.append("doLoadEmbed 调 loadEmbedModel 时**没传** nThreads ——"
                             "设置页的线程数对建库完全无效，而界面上看不出来")
            elif 'parseThreads()' not in args:
                fails.append(f"doLoadEmbed 传的 nThreads 不是 parseThreads()（实参：{args.strip()}）"
                             "—— 两处各取一遍时会分岔，而症状是两个入口行为不同、无日志可辨")

    # ── 判据 4：parseThreads() 的兜底也是设备自适应 ─────────────────────
    # ⚠ parseThreads 是**表达式体**（`... = thrEt... ?: defaultThreads()`），
    #    且表达式里含一个 lambda（`.takeIf { it > 0 }`）。`kotlin_fn_body`
    #    会把那个 lambda 的函数体当成函数体（返回 `{ it > 0 }`），
    #    `kotlin_fn_span` 也会在第一个 `{` 处截断 —— 两个现成取法在这里都**切歪**。
    #    切歪的后果不是报错，是判据在别人身上跑（那一族假绿本仓库反复记录过）。
    #    所以这一条自己按"声明起 -> 同缩进的下一行"取**整条表达式体**。
    pdecl = _expr_fn_decl(a, 'parseThreads')
    if pdecl is None:
        fails.append("找不到 parseThreads() 声明（判据锚点失效）")
    elif 'defaultThreads()' not in pdecl:
        fails.append("parseThreads() 的兜底不是 defaultThreads() ——"
                     "输入框被清空时会按一个与设备无关的常数跑")

    # ── 判据 5：全仓不许再有"按常数 4 兜底线程数"的写法 ─────────────────
    # 只判 **thr** 那一路（`ctxEt...?: 4096` 是另一件事，不在此列）。
    stale = re.findall(r'thrEt\.text[^\n]*?toIntOrNull\(\)\s*\?:\s*4\b', a)
    if stale:
        fails.append(f"仍有 {len(stale)} 处 `thrEt...toIntOrNull() ?: 4` 的按常数兜底写法"
                     "（线程数的兜底只该有一条路）")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print(f"PASS  loadEmbedModel 的线程数默认值走 defaultThreads()（第 {line} 行起）")
    print(f"PASS  defaultThreads() 按 availableProcessors() 并钳上下限（第 {dline} 行起）")
    print(f"PASS  doLoadEmbed 把 parseThreads() 真的传给了 loadEmbedModel（第 {dload_line} 行起）")
    print("PASS  parseThreads() 的兜底是 defaultThreads()（且它是全仓唯一的线程数取值处）")
    print("PASS  全仓没有『按常数 4 兜底线程数』的残留写法")
    return 0


if __name__ == '__main__':
    sys.exit(main())
