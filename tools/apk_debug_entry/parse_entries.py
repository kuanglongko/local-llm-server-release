# `.cnb.yml` 里 `api_trigger_*` 事件两段共用的取法。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单独落一份
# ═══════════════════════════════════════════════════════════════════════════
# 守卫（`tools/run_apk_debug_entry_guard.sh`）要在**两个地方**按事件名切段：
#   ③ 比「两个事件的 script 正文是否逐字相同」；
#   ④ 取「新入口那段里调了哪个脚本」。
# 两处各写一份近似的切法必然分家 —— 一份把段切到下一个事件、另一份切到下一个
# 顶层键，于是**同一个文件**在两条判据里被读成两段不同的正文，
# 一条绿一条红，而红的那条指不到真正的原因。本仓库对这类"两把尺子"的
# 一贯口径是：抽出唯一一份实现（同 `count_suite_totals.py` 里 `totals` 与
# `suite_total` 同源那条）。
#
# ⚠ 切法必须**先剥注释**：说明这件事的注释里必然出现事件名、脚本名与 `script:`，
#   不剥的话"把入口从配置里删掉、注释还留着"这种桩会让判据恒真（本仓库
#   在鉴权守卫桩② 与到期清扫守卫判据④ 上各栽过一次同款）。所以这里只有一个
#   入口 `strip_comments()`，调用方不许自己再 `re.sub` 一遍。
import re


def strip_comments(text):
    """按 YAML 的规矩剥注释：`#` 只有在**行首或前面是空白**时才起注释。

    不能直接 `re.sub(r'#.*', '')` —— 那样会把字符串里的 `#` 一起吃掉
    （本仓库 repack 布局扫描器第一版正是这样假绿的：`//` 吃了后面的代码）。
    """
    # ⚠ 结果**逐行 rstrip**：`(^|\s)#.*$` 会留下"行首空白 + 空尾巴"，
    #   于是整行注释变成"看着是空行、`ln.strip()` 却非空"的东西 ——
    #   `block()` 的空行分支判不到它，剥注释这件事就等于没做。
    #   本文件第一版正是这样：`block()` 把注释行当正文收进段里，
    #   切段结果一路吃到文件末尾（自测桩①/⑩ 当场抓到）。
    stripped = re.sub(r"(?m)(^|\s)#.*$", r"\1", text)
    return "\n".join(l.rstrip() for l in stripped.split("\n"))


def _indent(line):
    return len(line) - len(line.lstrip(" \t"))


def block(text, event):
    """取 `event:` 自己那一段（不含这一行的键），到下一个**同级或更浅**的键为止。

    只在 `$:` 这一层用（事件名都是 `$` 下的同级键），所以"同级"就是
    "缩进等于事件行本身"。下一个事件键当然也停 —— 它属于下一个入口。
    """
    for i, ln in enumerate(text.split("\n")):
        if ln.startswith("#") or not ln.strip():
            continue                   # 注释行 / 空行不可能是事件键
        m = re.match(r"^([ \t]+)" + re.escape(event) + r":[ \t]*$", ln)
        if not m:
            continue
        base = len(m.group(1))
        rest = text.split("\n")[i + 1:]
        # 尾部的空行不属于这个键（它是键之间的分隔），先整段 rstrip 掉 ——
        # 否则"段"的边界会随文件末尾有几个空行而变，判据③ 就在比一个
        # 与内容无关的东西。
        while rest and not rest[-1].strip():
            rest.pop()
        out = []
        for nxt in rest:
            # 空行照收：它在段内不影响任何判定（`script_of` 会把它滤掉），
            # 而**不**在这里 break 是刻意的 —— 空白行在 YAML 里不结束一个键。
            if not nxt.strip():
                out.append(nxt)
                continue
            if _indent(nxt) < base:
                break                  # 回到顶层（`$:` / `main:`）—— 本事件结束
            if re.match(r"^[ \t]+api_trigger_\S+:", nxt):
                break                  # 下一个入口
            out.append(nxt)
        return "\n".join(out)
    return ""


def script_of(text, event):
    """取该事件第一个 `script: |` 的正文，按"去空行、去行首缩进"归一化。

    归一化是有意的：这一行比的是「两条入口跑的是不是同一件事」，
    不是"缩进抄得一样"。缩进差一格不影响它编出什么。
    """
    b = block(text, event)
    m = re.search(r"(?m)^[ \t]*script:[ \t]*\|[ \t]*$(.*)", b, re.S)
    if not m:
        return None
    return "\n".join(l.strip() for l in m.group(1).split("\n") if l.strip())
