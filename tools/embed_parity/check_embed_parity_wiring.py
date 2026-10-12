#!/usr/bin/env python3
"""判「向量逐位比对」这件事**真的接上了**（ISSUE #269 第 5 步）。

═══════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠 EmbedParity 的单测）
═══════════════════════════════════════════════════════════════════════
`EmbedParity` 的纯逻辑单元能全绿，而它要修的**恰恰是"纯逻辑全绿、
真机上却拿不到结论"这一类**：

  · `EmbedParity.compare` 算得对，但**没有入口**跑它 -> 用户永远量不出
    那条待验断言（0.9.218 那句"尚未验证"会一直挂在那里）；
  · 有入口，但入口**只跑合批那一侧**（没跑逐条那侧）-> 比的是"合批自己和自己"，
    结论恒为"相同"，而那是个**假的**断言成立；
  · 入口跑了两侧，但结论**只打日志**不落 `/health` -> 结论要动手翻日志，
    而它是跨版本要对的一条（"0.9.218 那条断言到底成立没有"）；
  · `/health` 报了，但 `HealthCheck.parse` **没解析** -> 字段在响应里、
    探活报告里读不到（与没报等价，本仓库反复记录的一族）；
  · 结论报成**布尔** -> "没测过"与"测了但不是逐位"被混成一个 false，
    而两者给出的行动方向相反；
  · 换嵌入模型**不清**结论 -> 从"合批型"换到"回落型"，看到的仍是上一个模型的结论。

判据一律锚**结构关系**（谁在什么位置读了/调了谁），不锚"某某标识符存在"。
用法：check_embed_parity_wiring.py <LlmEngine.kt> <EngineActivity.kt> <HttpApi.kt> \\
                               <HealthCheck.kt> <EmbedParity.kt> <EmbedParityProbe.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import kotlin_fn_span, kotlin_fn_body  # noqa: E402


def kt_fn(src, name):
    """取函数**完整声明**（含表达式体里 `{` 之前的 `synchronized(...)` 等）。

    为什么不用 `kotlin_fn_body`：它只给 `{ ... }` 那一段，而本仓库的嵌入函数
    写成表达式体（`fun f() = synchronized(lock) { ... }`）—— 只取函数体会把
    签名与锁名漏掉，"这一整段里调了谁"就会假红。用 `kotlin_fn_span`。
    """
    span = kotlin_fn_span(src, name)
    if span and span[0]:
        return span[0]
    # 兜底：按括号配对取（不同版本的 _fnbody 可能命名不同）
    m = re.search(r'fun\s+' + re.escape(name) + r'\s*\(', src)
    if not m:
        return None
    i = src.index('(', m.end() - 1)
    depth, j = 0, i
    while j < len(src):
        if src[j] == '(':
            depth += 1
        elif src[j] == ')':
            depth -= 1
            if depth == 0:
                break
        j += 1
    k = src.find('{', j)
    if k < 0:
        return None
    depth = 0
    for x in range(k, len(src)):
        if src[x] == '{':
            depth += 1
        elif src[x] == '}':
            depth -= 1
            if depth == 0:
                return src[k:x + 1]
    return None


def kt_object(src, name):
    """取 `object Name { ... }` 的**大括号内文本**（按括号配对，找不到返回 None）。

    为什么不用 `kotlin_fn_span`：它按 `fun` 定位，而这里要的是一个 object 的体
    —— 且必须限定在这一个大括号里（文件里有别的同名枚举，不限定就会恒真）。
    """
    m = re.search(r'object\s+' + re.escape(name) + r'\s*\{', src)
    if not m:
        return None
    start = m.end() - 1
    depth = 0
    for i in range(start, len(src)):
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return None


def main():
    (engine_p, act_p, http_p, health_p, parity_p, probe_p) = sys.argv[1:7]
    engine = Path(engine_p).read_text(encoding='utf-8', errors='replace')
    act = Path(act_p).read_text(encoding='utf-8', errors='replace')
    http = Path(http_p).read_text(encoding='utf-8', errors='replace')
    health = Path(health_p).read_text(encoding='utf-8', errors='replace')
    parity = Path(parity_p).read_text(encoding='utf-8', errors='replace')
    probe = Path(probe_p).read_text(encoding='utf-8', errors='replace')
    # 纯逻辑层的常量出处（与 LlmEngine.kt 同目录）。缺失时按空串 -> 判据退回到
    # engine（常量还在引擎里的老版本照样判得动）。
    limits_p = Path(engine_p).parent / "EmbedLimits.kt"
    limits = (limits_p.read_text(encoding='utf-8', errors='replace')
              if limits_p.exists() else "")
    fails = []

    # ── 判据 1：入口**同时跑了两侧**（逐条 + 合批），且用同一批文本 ─────────
    pf = kt_fn(engine, "probeEmbedBatchParity")
    if pf is None:
        fails.append("LlmEngine.kt 里找不到 probeEmbedBatchParity —— 断言没有任何入口"
                     "跑，0.9.218 那句「尚未验证」会一直挂着（第 5 步没接上）")
        pf = ""
    else:
        # 逐条那一侧必须走 `embed(`（与建库同一条编码路径），不是直调 native。
        if not re.search(r'embed\s*\(\s*t\s*\)|embed\s*\(\s*text\s*\)', pf):
            fails.append("probeEmbedBatchParity 没有跑**逐条**那一侧（`embed(...)`）—— "
                         "只跑合批时比的是「合批自己和自己」，结论恒为「相同」（一个假的断言成立）")
        # 合批那一侧必须走 `embedBatch(`。
        if not re.search(r'embedBatch\s*\(', pf):
            fails.append("probeEmbedBatchParity 没有跑**合批**那一侧（`embedBatch(...)`）")
        # 两侧的结果必须交给 EmbedParity.compare 才算比过。
        if not re.search(r'EmbedParity\.compare\s*\(', pf):
            fails.append("probeEmbedBatchParity 没有调 EmbedParity.compare —— "
                         "跑了两侧却不比，等于没量（结论是编的）")
        # 结论必须落进对外读数（Last.record），不是只打日志。
        if not re.search(r'EmbedParity\.Last\.record\s*\(', pf):
            fails.append("probeEmbedBatchParity 没有把结论落进 EmbedParity.Last —— "
                         "只在日志里说是「要动手翻日志」，而结论是跨版本要对的一条")
        # 逐条明细必须落进日志：`describe()` 只说得出计数，而"是哪一条"直接决定
        # 下一步（只有某几条不同 -> 去查那几条；整批都不同 -> 这条路整体不可信）。
        # 首轮真机读数是「6 条里 1 条不同」，而 `Row.maxAbsDiff` / `Row.firstDiffDim`
        # 算了**没有任何地方读** —— 算出来没人读与没算在现象上一模一样。
        if not re.search(r'\.detailLines\s*\(', pf):
            fails.append("probeEmbedBatchParity 没有把逐条明细（detailLines()）打出来 —— "
                         "结论只说得出「有几条不同」，说不出「是哪一条」（而后者决定下一步）")
        # 合批整批不可用时必须「报没有结论」，不能报"不相同"。
        if not re.search(r'BatchEmbed\.Ok|Unavailable', pf):
            fails.append("probeEmbedBatchParity 没有分辨合批整批不可用（BatchEmbed.Unavailable）—— "
                         "那一侧根本没有向量，那是**缺失**，报成「不相同」是把未知当已知")
        # 「同路径重复」必须真的跑（同一条路编两次），不是只跑跨路一次。
        # 首轮真机那条 `firstDiffDim=0` 分不开「批布局改语义」与「编码本身不确定」
        # —— 只有"同一条路重复编两次"这组读数能分，而只跑一次时它不存在。
        if not re.search(r'aRepeat\s*=|repeats\s*\.add|repeatRows', pf):
            fails.append("probeEmbedBatchParity 没有做「同路径重复」对照（同一条路编两次）—— "
                         "只跑跨路一次时，「批布局改语义」与「编码本身不确定」混在一个读数里，"
                         "而两者下一步相反（前者收窄批大小结论，后者先别谈批大小）")
        # ⚠ 两条路**各自**都要重复：只重复逐条那条路时，读数里还剩一格没被排掉 ——
        # 「**合批自己**复现不了自己」。它和"批布局改语义"是两件事（前者先查合批，
        # 后者才谈 `g` 收不收窄），而只重复一条路时它不存在。
        if not re.search(r'aBatchedRepeat\s*=|repeatBatchedRows', pf):
            fails.append("probeEmbedBatchParity 没有做「**合批**那条路的同路径重复」对照 —— "
                         "只重复逐条那条路时，「合批自身不确定」与「批布局改语义」混在一个读数里，"
                         "而前者根本不该谈 `g`（先查合批那条路）")
        # 两组样本（长短悬殊 / 等长）必须分别跑 —— 并成一批时等长那几条会被
        # 悬殊那几条淹掉，而"不等长的 padding 是不是元凶"正是要问的东西。
        if not re.search(r'EmbedParityProbe\.(TEXTS|SAME_LEN)|SAME_LEN_LABEL', act):
            fails.append("EngineActivity 没有分别跑两组样本（长短悬殊 + 等长）—— "
                         "只有一组长短悬殊时，「批布局」与「不等长的 padding/切分」两个来源分不开")

    # ── 判据 2：`/health` 报出读数，且 HealthCheck 解析它 ───────────────────
    parity_fields = [
        ("embed_parity_rows", "参与条数"),
        ("embed_parity_identical", "逐位相同条数"),
        ("embed_parity_different", "真的不同条数"),
        ("embed_parity_verdict", "结论档"),
        ("embed_parity_repeat_measured", "逐条重复做没做"),
        ("embed_parity_repeat_not_identical", "逐条重复里不是逐位相同的条数"),
        ("embed_parity_repeat_batched_measured", "合批重复做没做"),
        ("embed_parity_repeat_batched_not_identical", "合批重复里不是逐位相同的条数"),
        ("embed_parity_group", "这份读数取自哪一组样本"),
    ]
    for f, what in parity_fields:
        # ⚠ 判据是「`f` 出现在 JSON 的**值位置**」（`"f":…`），不是 `f in http` ——
        # 文件上方的**注释里也写着**这些字段名（那是给别人看的说明），
        # 只搜名字的话：把 JSON 里的值换成常量 0、甚至整段删掉，判据仍然全绿
        #（本桩第一版就是这么假绿的 —— "报了"与"真的拼进去了"是两件事）。
        if not re.search('"' + re.escape(f) + r'"\s*:', http):
            fails.append(f"/health 没有报 {f}（{what}）—— 结论对外不可见，只能翻日志")
    for f in ("embed_parity_rows", "embed_parity_identical", "embed_parity_different",
              "embed_parity_verdict", "embed_parity_group"):
        if not re.search(r'int\("' + re.escape(f) + r'"\)|str\("' + re.escape(f) + r'"\)', health):
            fails.append(f"HealthCheck.parse 没解析 {f} —— 报在响应里、探活报告里读不到"
                         "（与没报等价）")
    # ── 判据 2b：落 `/health` 的必须是**长短悬殊**那组，且带得出组名 ────────
    # 等长组按构造必然稳（每条字符数全等），落它等于**用必然成立的那组**回答
    # "换批大小会不会改答案" —— 而这格存在的全部理由就是判「`g` 能不能放行」。
    # 真机现场（PR #301）：`/health` 报"逐位相同 4/4 —— 批大小是纯加速旋钮"，
    # 而同一份日志里长短悬殊是「真的不同 1 条、4.510e-03」。
    # ⚠ 判据锚那句 `record` 的**闸门**（`label == LONG_SHORT_LABEL`），不是
    #   "文件里出现过 LONG_SHORT_LABEL" —— 后者在改回落等长组时照样绿。
    if not re.search(r'label\s*==\s*LONG_SHORT_LABEL\)\s*EmbedParity\.Last\.record\s*\(', pf):
        fails.append("probeEmbedBatchParity 落 /health 时**没按长短悬殊那组分闸** —— "
                     "等长组按构造必然稳，落它时 /health 报的是「必然成立」那句，"
                     "答不了「换批大小会不会改答案」（真机 PR #301 的现场）")
    # 落盘时必须把**组名**带出去（`record(report, label)`）：只报数字不报来源时，
    # "逐位相同 4/4"在两组下同形，等长组的结论会被读成长短悬殊组也成立。
    if not re.search(r'EmbedParity\.Last\.record\s*\(\s*report\s*,\s*label\s*\)', pf):
        fails.append("probeEmbedBatchParity 落盘时没带组名（`record(report, label)`）—— "
                     "报告读不出这份读数取自哪一组，「逐位相同 4/4」会被当成长短悬殊也稳")
    # 快照里必须有 group；`/health` 的值必须来自快照；报告要按组换措辞。
    if not re.search(r'val\s+group\s*:\s*String', kt_object(parity, "Last") or ""):
        fails.append("EmbedParity.Last 的快照里没有「取自哪一组」（group）—— "
                     "两组样本回答的问题不同，只报数字时报不出来源")
    if not re.search(r'"embed_parity_group":\$parityGroupJson', http):
        fails.append("HttpApi 的 /health 没有报 embed_parity_group（值必须来自 "
                     "EmbedParity.Last 的快照）—— 「取自哪一组」在探活里读不到")
    if not re.search(r'embedParityGroup', health):
        fails.append("探活报告里没有用到「取自哪一组」—— 不按组换措辞时，"
                     "等长组那句「批大小是纯加速旋钮」会被读成长短悬殊组也成立")
    # 「同路径重复」的两个字段必须解析，且**分开**："测没测"（bool）与"几条不同"（int）
    # 不能压成一个数 —— 没测时"几条不同"也是 0，读成"全绿"就是把"没测过"说成
    # "验过了、没问题"（与 `different` 的 null/0 分开同一条理由）。
    for f, ty in (("embed_parity_repeat_measured", "bool"),
                  ("embed_parity_repeat_not_identical", "int"),
                  ("embed_parity_repeat_batched_measured", "bool"),
                  ("embed_parity_repeat_batched_not_identical", "int")):
        if not re.search(ty + r'\("' + re.escape(f) + r'"\)', health):
            fails.append(f"HealthCheck.parse 没解析 {f}（{ty}）—— 报在响应里、探活报告里读不到"
                         "（与没报等价）；'测没测'与'几条不同'必须分开报，"
                         "逐条与合批两条路也必须分开报")
    if not re.search(r'embedParityRepeatMeasured', health):
        fails.append("探活报告里没有用到「同路径重复做没做」—— 解析了却不报，等于没测过"
                     "（`notIdentical == 0` 在没测时也是 0，不说这句就会把没测读成全绿）")
    # 「合批」那一路的读数**必须真被报告读一次**：解析了却不读，等于没测过 ——
    # 而报告里"同路径重复全绿"这句就是靠它才能说出口的（没有它时，合批自身
    # 不确定会被当成批布局改语义，两个来源焊在一起）。
    if not re.search(r'embedParityRepeatBatchedMeasured', health):
        fails.append("探活报告里没有用到「合批那条路的重复做没做」—— 解析了却不报，"
                     "「合批自身不确定」与「批布局改语义」就分不开（而两者下一步相反）")
    # `repeat_measured` 的值必须真从快照来（不是常量）。
    if not re.search(r'"embed_parity_repeat_measured":\$\{pari\.repeatMeasured\}', http):
        fails.append("HttpApi 的 /health 没有报 embed_parity_repeat_measured（值必须来自 "
                     "EmbedParity.Last 的快照）—— 「测没测」这件事实在探活里读不到")
    # 合批那两格的值也必须来自快照：写成常量时"合批自己也复现不了自己"会被
    # 报成"合批是确定的"（一个**相反**的读数，而它正是"要不要谈 `g`"的前提）。
    if not re.search(r'"embed_parity_repeat_batched_measured":\$\{pari\.repeatBatchedMeasured\}', http):
        fails.append("HttpApi 的 /health 没有报 embed_parity_repeat_batched_measured（值必须来自 "
                     "EmbedParity.Last 的快照）—— 「合批那条路测没测」在探活里读不到")
    # `different` 的**条数**必须真进快照（不是只在 Report 里算）：真机首轮读数是
    # 「6 条里 1 条不同」，而"1/6"（去查那几条）与"6/6"（这条路整体不可信）
    # 下一步相反 —— 只报相同条数时这两个读数长得一样。
    if not re.search(r'different', kt_object(parity, "Last") or ""):
        fails.append("EmbedParity.Last 的快照里没有「真的不同」的条数 —— "
                     "结论档只说得出「有不同」，说不出「有 1 条不同还是每条都不同」"
                     "（而后者说明合批整体不可信，处置相反）")
    # 不同的**条数**必须真从快照来：写成常量 0 时 `/health` 里"有 1 条不同"会变成
    # "没有不同"（一个相反的读数）—— 而字段名照样在，只搜名字查不出来。
    if not re.search(r'"embed_parity_different":\$\{pari\.different\}', http):
        fails.append("HttpApi 的 /health 没有报 embed_parity_different（值必须来自 "
                     "EmbedParity.Last 的快照，不是常量）—— 不同的**条数**"
                     "（「1/6」与「6/6」指向相反的结论）在探活/监控里读不到")
    if not re.search(r'embedParityDifferent\s*=\s*int\("embed_parity_different"\)', health):
        fails.append("HealthCheck.parse 没解析 embed_parity_different —— "
                     "报在响应里、探活报告里读不到（与没报等价）")
    if not re.search(r'embedParityDifferent', health):
        fails.append("探活报告里没有用到「不同的条数」—— 解析了却不报，等于没测过"
                     "（首轮真机正是「说不出是哪一条/几条」）")
    # `verdict=DIFFERENT` 而 `different=0` 是**自相矛盾**（DIFFERENT 这一档的
    # 充分必要条件就是 `different > 0`）：它会被读成"验过了，没有不同的条目"
    # —— 一个与真相相反的**结论**，比不报更坏。Last.record 必须拦下来（落 UNKNOWN）。
    # 「同路径重复」的读数必须真进 Report 与快照（不是只算了就丢）。
    if not re.search(r'repeatRows', parity):
        fails.append("EmbedParity.Report 里没有「同路径重复」的逐条结论（repeatRows）—— "
                     "差异来自批布局还是编码本身不确定，这一格读不到")
    if not re.search(r'repeatBatchedRows', parity):
        fails.append("EmbedParity.Report 里没有「**合批**那条路的重复」逐条结论"
                     "（repeatBatchedRows）—— 「合批自身不确定」这一格读不到，"
                     "而它不读时会被当成「批布局改语义」")
    # 「合批重复全绿」与「合批重复有 N>0 条不同」不可能同时成立：对不上说明
    # `Report` 是手拼的，报出去会被读成"合批自身是确定的"（一个相反的结论）。
    if not re.search(
            r'repeatBatchedAllIdentical\s*&&\s*r\.repeatBatchedNotIdentical\s*>\s*0', parity):
        fails.append("EmbedParity.Last.record 没有拦「合批重复全绿、条数却是正数」—— "
                     "那个读数会被读成「合批自身是确定的」（而它正是要不要谈批布局的前提）")
    if not re.search(r'repeatMeasured', kt_object(parity, "Last") or ""):
        fails.append("EmbedParity.Last 的快照里没有「同路径重复测没测」—— "
                     "`notIdentical == 0` 在没测时也是 0，不说这句就会把「没测」读成「全绿」")
    if not re.search(r'Verdict\.DIFFERENT\s*&&\s*r\.different\s*<=\s*0', parity):
        fails.append("EmbedParity.Last.record 没有拦「结论说不相同、不同的条数却是 0」—— "
                     "那个读数会被读成「验过了，没有不同的条目」（相反的结论）")
    # `/health` 报的是 EmbedParity.Last.snapshot()，不是另算一遍。
    if not re.search(r'EmbedParity\.Last\.snapshot\s*\(', http):
        fails.append("HttpApi 的 /health 没有读 EmbedParity.Last.snapshot —— "
                     "自己另算一遍时，两处迟早分岔（数字都像正常数）")

    # ── 判据 3：`Last` 的结论档**不是布尔**（"没测过"与"不是逐位"必须分得开）──
    # ⚠ 锚点必须落在 `Last` 这个大括号里：文件里还有**另一个**同名枚举（逐条的
    #   `Verdict`），只搜 'enum class Verdict' 会被那一处**恒真**地骗过
    #   （本自测的桩④就是钉这一点）。
    last_obj = kt_object(parity, "Last")
    if last_obj is None:
        fails.append("EmbedParity 里找不到 Last 对象 —— 对外读数没有落点")
    elif not re.search(r'enum\s+class\s+\w+\s*\{', last_obj):
        fails.append("EmbedParity.Last 的结论不是分档枚举 —— 用一个布尔会把"
                     "「没测过」与「测了但不是逐位」混成一个值，而两者行动方向相反")
    for v in ("IDENTICAL", "CLOSE", "DIFFERENT", "MISSING"):
        if not re.search(r'\b' + v + r'\b', parity):
            fails.append(f"EmbedParity 缺少结论档 {v} —— 三档（逐位相同/仅接近/真的不同）"
                         f"是分「能不能据此沿用」的最小区分，少一档就把它压进了另一档")
    # `allIdentical` 的判据必须是「**非空**且全部逐位」（不是「没有不同」）。
    # 两个面各挡一种坏法，且**同一族**（都是把未知当已知）：
    #   · 用 `different == 0` 判 -> 「有条目缺失」也算成立；
    #   · 少了 `rows.isNotEmpty()` -> **空批**时 `0 == 0` 成立，即"一条都没编过
    #     就说整批逐位相同"（与本文件 `repeatMeasured` / `salvage = -1`
    #     同一条口径：没有读数 != 读数成立）。
    if not re.search(r'allIdentical[^\n]*rows\.isNotEmpty\s*\(\)', parity):
        fails.append("EmbedParity.allIdentical 少了 `rows.isNotEmpty()` —— 空批时"
                     "`identical == rows.size` 是 `0 == 0`，会报出「整批逐位相同」"
                     "（一条都没编过），正是本仓库反复挡的「没验过 == 验过了」")
    if not re.search(r'allIdentical[^\n]*identical\s*==\s*rows\.size', parity):
        fails.append("EmbedParity.allIdentical 的判据不是 `identical == rows.size` —— "
                     "用 `different == 0` 时「有条目缺失」也会算成立，那是把未知当已知")

    # ── 判据 4：换嵌入模型清结论（读数描述的是"这个模型"）──────────────────
    if not re.search(r'EmbedParity\.Last\.reset\s*\(', engine):
        fails.append("LlmEngine.kt 里没有 EmbedParity.Last.reset —— 换嵌入模型后仍留着"
                     "上一个模型的结论（从「合批型」换到「回落型」时看着正常，只是描述的不是眼前这个）")

    # ── 判据 5a：按钮的**定位与措辞**（0.9.231 改）────────────────────────
    #
    # 按钮当初存在的理由是回答一句待验证断言（"不同批大小算出的向量是否逐位相同"），
    # 那句**已经答完**（真机读数：长短悬殊那组 `different`、`4.510e-03`）。
    # 但它手里还攥着**没有别的入口**的一件事：它是"批大小会不会改语义 / 改答案"
    # 在真机上唯一的取样点 —— 样本是固定的两组**合成**语料，而用户自己的库里
    # 有没有比它们更坏的批（更长的、更不等长的、真踩到截断的）谁也没量过，
    # 而"`g` 能不能按库放行"恰恰取决于**最坏**的那组。
    #
    # 于是它从"临时诊断"改成"**常驻探针**"，名字也跟着改：旧名「向量逐位比对」
    # 只说了它量的一半（还有"检索对照"那半格），且"逐位比对"听起来像内部实现。
    # 判据锚**这个判断的两面**，而不是"按钮上写着某个字符串"：
    #   · 按钮还在（删了就没有取样点了）；
    #   · 名字已经改掉，且**界面可见文案里不再有旧名**（旧名残留 = 两处说法打架，
    #     用户按旧名去日志里搜不到任何东西 —— 日志前缀也跟着改了）。
    # ⚠ 旧名判据只看**字符串字面量**：解释这次改名的注释里必然带着旧名，
    #   不剥注释的话这条恒红（恒红的判据很快会被静音）——
    #   与 `rag_kb_name/check_wiring.py` 里那条同名判据同一个理由。
    par_btn = re.search(r'val logParityBtn\s*=\s*btn\("([^"]*)"\)', act)
    if not par_btn:
        fails.append("EngineActivity 里找不到 logParityBtn 的按钮文案 —— "
                     "批大小的取样入口要么没了（真机上再没有量它的地方），"
                     "要么改了写法（判据锚点失效）")
    else:
        label = par_btn.group(1)
        if "向量逐位比对" in label:
            fails.append(f"那个按钮还叫「{label}」—— 它量的**不只是**向量逐位比对"
                         "（还有「检索对照」那半格），而「逐位比对」听起来像内部实现；"
                         "0.9.231 已按「常驻探针」重新定位，名字该说用户要判断的那件事")
    # 可见文案里的旧名必须绝迹（**剥掉注释**再查 —— 解释改名的注释里必然有它）。
    code_only = re.sub(r'/\*.*?\*/|//[^\n]*', '', act, flags=re.S)
    if "向量逐位比对" in code_only:
        fails.append("EngineActivity 的**代码**里还有旧名「向量逐位比对」—— "
                     "改名要改干净（日志前缀跟着按钮走，旧名残留会让人按旧名去"
                     "日志里搜、什么也搜不到）")
    # 日志前缀与按钮名同源：按钮改了名而日志还打旧前缀时，用户按界面上的字去
    # 日志里搜不到刚才那趟的任何一行（而那趟的全部结论都在日志里）。
    if not re.search(r'uiLog\("\[批大小检查\]', act):
        fails.append("那趟检查的日志前缀没跟着新按钮名走（没有 `[批大小检查]`）—— "
                     "用户按界面上的字去日志里搜，会搜不到刚才那趟的任何结论")

    # ── 判据 5：诊断入口在界面上**够得着**，且样本是固定集 ─────────────────
    if not re.search(r'probeEmbedBatchParity\s*\(', act):
        fails.append("EngineActivity.kt 没有调 probeEmbedBatchParity —— 判据没有界面入口，"
                     "真机就没法量（0.9.218 那句「尚未验证」会一直挂着）")
    if not re.search(r'EmbedParityProbe\.TEXTS', act):
        fails.append("EngineActivity.kt 没有用固定样本集（EmbedParityProbe.TEXTS）—— "
                     "拿「当前库里的几条」时，两次结论来自不同文本，「上次相同、这次有些不同」分不清是环境还是文本变了")
    # 样本必须覆盖长短悬殊 + 超长（否则"验过了"什么都没验到）。
    if not re.search(r'TEXTS', probe):
        fails.append("EmbedParityProbe 里没有 TEXTS（固定样本集）")
    # 等长对照样本集必须是**另一个**常量（不是把两组并进 TEXTS）：并成一批时
    # 等长那几条的结论会被悬殊那几条淹掉，而"不等长是不是元凶"正是要问的东西。
    # ⚠ 判据锚的是**声明 + 取用**两处（都在 `EmbedParityProbe` 里）：
    # 只锚声明（`val SAME_LEN`）时，「把它改名、再从取用处换掉」这种删法照样绿 ——
    # 而那正是本仓库反复记的那类坑（判据锚在一个**可以留着不动**的符号上）。
    # ⚠ 判据锚的是**声明 + 取用**两处（都在 `EmbedParityProbe` 里）：
    # 只锚声明（`val SAME_LEN`）时，「把它改名、再从取用处换掉」这种删法照样绿 ——
    # 而那正是本仓库反复记的那类坑（判据锚在一个**可以留着不动**的符号上）。
    if not re.search(r'val\s+SAME_LEN', probe):
        fails.append("EmbedParityProbe 里没有 SAME_LEN（等长对照样本集）—— "
                     "只有长短悬殊那一组时，「批布局」与「不等长的 padding/切分」分不开")
    # 「取用」那处：`SAME_LEN` 必须真的被**用**过一次（不是只声明）——
    # 声明留着、取用处换掉时，上面那条判据照样绿（本桩实测过）。
    else:
        decl = re.search(r'val\s+SAME_LEN', probe)
        used = len(re.findall(r'\bSAME_LEN\b', probe))
        if used < 2:
            fails.append("SAME_LEN 只声明、没人用（`used < 2`）—— 取用处被换掉时，"
                         "声明留着就能把判据喂绿，而这正是本仓库反复记的那类坑")
    # 样本条数必须与**批大小**对齐（各占两批）：条数落在批边界之外时，"批边界
    # 前后的两条一不一样"这一格就没人问过，而它正是"批布局"这件事的落点。
    # `kEmbedBatchDefault` 与 `kEmbedBatchRows` 必须还是同一个数（否则这里的
    # 算术与真机跑的不是同一个批）。
    # ⚠ 自 0.9.229 起这个数的**出处**搬到 `EmbedLimits.kt`（纯逻辑层），
    #   `LlmEngine.kt` 里写的是 `= EmbedLimits.kEmbedBatchDefault`（引用，不是数字）。
    #   原因：住在 LlmEngine（依赖 Android）时宿主单测编不到，那两份套件**编不过、
    #   一次都没跑过**，输出却是"SKIP（缺工具链）"。见
    #   `tools/run_kt_test_runner_guard.py` 的文件头。
    kb = (re.search(r'kEmbedBatchDefault\s*=\s*(\d+)', limits)
          or re.search(r'kEmbedBatchDefault\s*=\s*(\d+)', engine))
    if not kb:
        fails.append("找不到 kEmbedBatchDefault 的数值（EmbedLimits 与 LlmEngine 里都没有）"
                     " —— 样本条数按它对齐，取不到时「各占两批」这句话没有依据")
    else:
        k = int(kb.group(1))
        counts = {}
        for name in ("TEXTS", "SAME_LEN"):
            m = re.search(r'val\s+' + name + r'\s*:\s*List<String>\s*=\s*listOf\((.*?)\n    \)',
                          probe, re.S)
            if not m:
                fails.append(f"EmbedParityProbe 里取不到 {name} 的条目 —— 判不了它占几批")
                continue
            # 数**条目行**：条目是"行尾带 `,` 的行"，且**注释行不算**（注释里
            # 也有逗号 —— 首版本把它数进去过）。多行 `+` 拼接的续行（行尾是
            # `+`）也不算，它属于上一条。
            rows = [ln for ln in m.group(1).split("\n")
                    if ln.strip() and not ln.strip().startswith("//")]
            counts[name] = len([ln for ln in rows if ln.rstrip().endswith(",")])
        # 界面上那行说明里的条数必须**与常量同源**（读 `EmbedParityProbe.*.size`），
        # 不是手抄一个数：0.9.222 把两组各 6 条改成各 4 条时，手抄的那份就地漂了
        #（说明里还是"等长 5 条"，真跑的是 4 条）—— 而那行字正是让人对读数的手段。
        # ⚠ 判据读的是**两句 `size`**，不是"文件里出现过 4 这个数字"：后者在改错方向
        #   （4 条写成 6 条）时照样绿，而前者只有真从常量取才绿。
        for name in ("TEXTS", "SAME_LEN"):
            if not re.search(r'EmbedParityProbe\.' + name + r'\.size', act):
                fails.append(f"EngineActivity 那行样本说明里的条数不是从常量取"
                             f"（没有 `EmbedParityProbe.{name}.size`）—— 手抄的条数"
                             f"会在常量改条数时就地漂，而它正是让人按**对的样本数**"
                             f"解释结论的那行字")
        for name, n in counts.items():
            # 样本条数与批大小**对齐**：n 要能整除 k，或 k 能整除 n
            # （n = k：恰好一批；n = k/2、k/4…：一批只装一半 —— 两种都能让
            # 「两批出来的东西是不是同一批布局」这件事有落点）。
            # 不对齐时（如 n=6、k=8）批里还剩空位，条数一变就去不了同样的图，
            # 跨版本对结论时多出一个"图不一样"的变量。
            if not (k % n == 0 or n % k == 0):
                fails.append(f"EmbedParityProbe.{name} 有 {n} 条，与批大小 {k} 不对齐"
                             f"（既不是 n 整除 k，也不是 k 整除 n）—— 条数与批大小不对齐时，"
                             f"两组样本跨版本比结论会多出「图不一样」这一个变量")

    if fails:
        for f in fails:
            print("FAIL  " + f)
        sys.exit(1)
    print("OK  逐位比对从真机入口到 /health 读数全程接通")


if __name__ == "__main__":
    main()
