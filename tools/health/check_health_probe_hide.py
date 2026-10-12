#!/usr/bin/env python3
"""判「原始读数只进日志、不进用户报告」这件事**真的接上了**。

═══════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠 HealthCheckTest）
═══════════════════════════════════════════════════════════════════════
`embed_parity_*` / `embed_retrieve_*` 那十几个字段（0.9.223 起为排查
「批大小 `g` 能不能按库放行」逐格补上）有两件事会**同时**发生，而它们方向相反：

  · **铺回报告正文** —— 用户的原话是"信息太乱了，对用户也没实际用处"：
    十几格原始读数看完不知道该做什么，而日常探活要的是"活没活 / 模型装没装 /
    库认的批大小是多少"这种**一句话能指导下一步**的信息。
  · **删干净却没人接住** —— `/health` 里报着、报告里没了、日志里也没有，
    那十几个字段就成了"写在服务端、谁也没读过"的摆设（本仓库反复记录的那族：
    算出来没人读 == 没算）。

所以判据要**同时**钉住两个方向：
  ① 默认的报告正文里看不见那两行（而且"看不见"必须来自**默认值**，
     不是调用方各写各的 —— 换个调用点就会漏出来）；
  ② 完整读数真的从**另一个出口**（日志那一行）落下来了，且那些格一个不少。

⚠ 还有一个更隐蔽的坏法：把"隐藏"做成**删掉代码**（format 里那几段直接删）
  —— 那时 ① 照样绿（看不见了），而 ② 拿不到任何东西（读数根本没人算出来）。
  判据因此必须锚**两处各自存在**：format 里那几段还在（只是被开关挡着），
  且 rawEmbedProbeLines 真的读那十几个字段。

判据一律锚**结构关系**（谁在什么条件下读了/打了什么），不锚"某某标识符存在"。
纯 python3 标准库。用法：check_health_probe_hide.py <HealthCheck.kt> <EngineActivity.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import kotlin_fn_span  # noqa: E402


def fn(src, name, raw=None):
    """取函数**完整声明**的原文。

    `_fnbody.kotlin_fn_span` 会**先剥掉字符串字面量**（把 `"..."` 换成 `""`）——
    这对它原本的用途（C++ 里按括号配对找函数体）是对的，但本判据要盯的恰好是
    **字符串里写了什么措辞**，被剥过就全成了 `""`（症状：判据恒红，看着像代码没写）。
    所以：只用它定位**起行**（它跳过注释里同名函数这层更稳），文本一律从原文取，
    再用**真括号配对**收到函数末尾（原文里有字符串/注释，行数不能当长度用）。
    """
    span = kotlin_fn_span(src, name)
    if not span or span[0] is None:
        return None
    text = raw if raw is not None else src
    head = text.find('fun ' + name)
    if head < 0:
        return None
    k = text.find('{', head)
    if k < 0:
        return None
    depth = 0
    for x in range(k, len(text)):
        if text[x] == '{':
            depth += 1
        elif text[x] == '}':
            depth -= 1
            if depth == 0:
                return text[head:x + 1]
    return None


def main(health_p, act_p):
    health = Path(health_p).read_text(encoding='utf-8')
    act = Path(act_p).read_text(encoding='utf-8')
    bad = []

    # ── ① format 有一个**默认关**的开关 ────────────────────────────────────
    # ⚠ 必须是**形参默认值 false**，不能是"报告里写死删掉"：
    #   写死删掉时，判据②（完整读数有落点）就没人能拿它校验了 ——
    #   而那正是这一族里更坏的一半（读数没人算出来，报告当然也就看不见）。
    sig = re.search(r'fun\s+format\s*\(([^)]*)\)', health, re.S)
    if not sig:
        bad.append("HealthCheck.format 找不到（判据锚点失效）")
    else:
        params = sig.group(1)
        m = re.search(r'showRawEmbedProbe\s*:\s*Boolean\s*=\s*false', params)
        if not m:
            bad.append("format 没有 `showRawEmbedProbe: Boolean = false` 形参 —— "
                       "隐藏必须是**默认值**（写在调用点上时，换一个调用点就会"
                       "把十几格原始读数又漏回用户眼前）")

    # ── ② 那两段**还在** format 里，只是被开关挡着 ─────────────────────────
    # ⚠ 锚 `if (showRawEmbedProbe) {` 这道门 + 门里那段真正的措辞，
    #   而不是"文件里出现过 向量逐位比对"（删掉代码时那句话也还在 kdoc 里，
    #   判据会被注释喂绿 —— 本仓库记过的那族）。
    if not re.search(r'if\s*\(\s*showRawEmbedProbe\s*\)\s*\{', health):
        bad.append("format 里没有 `if (showRawEmbedProbe) {` 这道门 —— 要么那段"
                   "没被挡着（原始读数照旧铺在用户眼前），要么代码被整个删了"
                   "（那时读数根本没人算，比铺出来更坏）")
    else:
        # 门里必须真有那两段（逐位比对 + 检索对照）：只留空壳时，
        # "打开开关能看见"这件事就没有内容可看。
        body = fn(health, 'format', raw=health) or ''
        gate = body.find('if (showRawEmbedProbe)')
        # ⚠ 这两个 marker 都取自**原文的字符串字面量**里真正会出现的那几段
        #   （判据锚"门之后真有那两句措辞"，不是"文件里出现过"——
        #   后者在把代码整段删掉、只留同名标识符时照样绿）。
        for marker, what in (('\\n向量逐位比对：逐位相同', '逐位比对那一段'),
                             ('\\n检索对照（跳批大小', '检索对照那一段')):
            if gate < 0 or marker not in body[gate:]:
                bad.append(f"showRawEmbedProbe 门里没有{what} —— 开关打开时"
                           f"没东西可看（判据锚的是门**之后**那段，不是文件里出现过）")

    # ── ③ 完整读数有一个**独立出口**，且它真的读那些字段 ───────────────────
    raw = fn(health, 'rawEmbedProbeLines')
    if not raw:
        bad.append("HealthCheck.rawEmbedProbeLines 不存在 —— 原始读数被从报告里"
                   "搬走后没有落点，它们就成了『写在服务端、谁也没读过』的摆设")
    else:
        # 这些格一个都不能少。判据锚**取数字那几处**（`r.embed*`），不是
        # "文件里出现过字段名"：字段名在 Result 的 kdoc 里也有，会被注释喂绿。
        for field, what in (
            ("r.embedParityRows", "参与条数"),
            ("r.embedParityDifferent", "真的不同条数"),
            ("r.embedParityGroup", "取自哪一组（只报数字时两组同形、含义相反）"),
            ("r.embedParityRepeatMeasured", "「测没测」与「几条不同」必须分开"),
            ("r.embedParityRepeatBatchedMeasured", "合批那条路的重复做没做"),
            ("r.embedRetrieveMeasured", "检索对照测没测"),
            ("r.embedRetrieveAnswerChanged", "命中集合变没变"),
            ("r.embedRetrieveBatchBase", "两侧批大小"),
        ):
            if field not in raw:
                bad.append(f"rawEmbedProbeLines 没落 {field}（{what}）—— "
                           f"这一格从报告里搬走后就没有任何落点了")

    # ── ④ 探测那一趟真的把它打进日志 ──────────────────────────────────────
    # ⚠ 锚 `for (line in HealthCheck.rawEmbedProbeLines(r)) LlmEngine.uiLog(line)`
    #   这种**逐个落**的形状：只调一次不落（或落到别处）时，
    #   "完整信息不丢"这句就是空的。
    probe = fn(act, 'doProbe')
    if not probe:
        bad.append("EngineActivity.doProbe 找不到（探测那一趟的判据没有锚点）")
    elif not re.search(
            r'for\s*\(\s*line\s+in\s+HealthCheck\.rawEmbedProbeLines\s*\(\s*r\s*\)\s*\)'
            r'[^\n]*uiLog\s*\(', probe):
        bad.append("doProbe 没有把 rawEmbedProbeLines 的每一行打进日志 —— "
                   "原始读数从报告里消失后没有落点（完整信息不丢这句是空的）")

    # ── ⑤ 调用方不许自己写 `showRawEmbedProbe = true` ──────────────────────
    # 只有**测试**可以显式打开（它问的是措辞本身）。生产代码里出现 `= true`
    # 时，用户眼前那份报告又会长回去 —— 而那正是这次要修的东西。
    prod = "\n".join(ln for ln in act.splitlines())
    if re.search(r'HealthCheck\.format\([^)]*showRawEmbedProbe\s*=\s*true', prod):
        bad.append("EngineActivity 调 format 时打开了 showRawEmbedProbe —— "
                   "用户眼前的报告又会长回十几格原始读数（这次要修的正是它）")

    # ── ⑥ 方案 1：报告**不再复述整份响应原文**（但异常时照旧回显） ────────
    # 用户看到的"信息太乱"其实不是字段多：真机那份 /health 正文 1249 字符 /
    # 51 个字段，**没到 BODY_PREVIEW_CHARS(4096)** 的截断线，于是末尾那行
    # `响应原文：{...}` 把整份原件一字不落带进了报告。要分开的不是"报不报"，
    # 是"**报给谁看**"：全绿时那一坨字段对"接下来该做什么"没有任何指向，
    # 所以只留一句"去哪儿看"；**异常时照旧回显**（原文就是"哪里不对"的唯一证据）。
    #
    # ⚠ 两个方向都要钉，且它们**都会被读成"改好了"**：
    #   · 又整份回显 -> 用户眼前那份报告长回去（这次要修的正是它）；
    #   · 一刀切删掉回显 -> 异常时用户只拿到"不可达"三个字，没法问、没法贴。
    rb = fn(health, 'renderBody')
    if not rb:
        bad.append("HealthCheck.renderBody 找不到（判据锚点失效）")
    else:
        # ① 必须以**判定结果**分岔（不是"有没有 body"这类形状）：
        #    锚 `verdict(r)` 与 ALIVE 的比较 —— 只锚"出现了 ALIVE"会被注释喂绿。
        if not re.search(r'verdict\(\s*r\s*\)\s*==\s*Verdict\.ALIVE', rb):
            bad.append("renderBody 没有按 `verdict(r) == Verdict.ALIVE` 分岔 —— "
                       "全绿时仍会复述整份响应原文（那正是用户看到的\"信息太乱\"）")
        # ② 全绿那一支要**真的不复述**：那句"不在此复述"必须在。
        if '完整响应不在此复述' not in rb:
            bad.append("renderBody 里没有「完整响应不在此复述」那句 —— "
                       "全绿时用户拿不到\"完整响应去哪儿看\"的指引")
        # ③ 异常时**照旧回显**：`响应原文：` 那行不能被一刀切删掉。
        if '响应原文：' not in rb:
            bad.append("renderBody 里没有「响应原文：」那行 —— 一刀切删掉回显时，"
                       "不可达/降级现场只剩\"不可达\"三个字，用户没法独立复核")
        # ④ 截断必须自证（老约束，跟着这一处一起钉住）。
        if 'BODY_PREVIEW_CHARS' not in rb:
            bad.append("renderBody 不再用 BODY_PREVIEW_CHARS 收口 —— "
                       "截断会变成默默 take(N)，残片天然非法 JSON（证据变伪证）")

    # ── ⑦ 向量模型那一格必须进报告（用户点名要加） ────────────────────────
    # 对话模型加载与否由「模型已加载」那行回答，可**嵌入模型是另一份**：
    # 双常驻的四种组合在 `model_loaded` 上**长得一模一样**，而缺嵌入模型时
    # 检索必然全空（用户看到"一条都搜不到"会被引去查知识库）。
    fb = fn(health, 'format')
    if not fb:
        bad.append("HealthCheck.format 找不到（向量模型那格的判据没有锚点）")
    else:
        # 锚**取的那三个字段**（`r.embedLoaded` 等），不锚"文件里出现过 embed_loaded"：
        # 字段名在 Result 的 kdoc / parse 里也有，会被注释喂绿。
        if 'r.embedLoaded' not in fb:
            bad.append("format 没有读 `r.embedLoaded` —— 向量模型那格没进报告"
                       "（缺嵌入模型时检索必然全空，而它在 model_loaded 上看不出来）")
        if '向量模型' not in fb:
            bad.append("format 里没有「向量模型」这一行 —— 用户点名要加的那格不见了")

    if bad:
        for b in bad:
            print("FAIL  " + b)
        return 1
    print("OK  原始读数只进日志、不进用户报告，且完整读数有落点")
    return 0


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:]))
