#!/usr/bin/env python3
"""判「检索对照」这件事**真的接上了**（ISSUE #269 第 5 步）。

═══════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠 EmbedRetrieveParity 的单测）
═══════════════════════════════════════════════════════════════════════
`EmbedRetrieveParity` 的纯逻辑单元能全绿，而它要挡的**恰恰是"纯逻辑全绿、
真机上却量不到"这一类**：

  · 判定层算得对，但**没有入口**跑它 -> "向量不同会不会换答案"永远只能靠推断
    （而那条推断正是 `g` 能不能按库放行的那条决定）；
  · 有入口，但**只编了一套向量**（同一侧查自己）-> 结论恒为"一致"
    （一个**假的**"答案没变"，比没有结论更坏：它会让人据此放行 `g`）；
  · 有入口但两侧用的是**同一个批大小** -> 比的不是批大小，结论恒"一致"；
  · 查询**不在同一批文本里** -> 两侧都搜不到，"命中集合全同（都空）"
    会被读成"答案没变"（而那是 `measured == false` 那一格）；
  · 结论**只打日志**不落 `/health` -> 它是**跨版本**要对的那条（"换批大小
    到底换不换答案"），只能翻日志就等于拿不到；
  · `/health` 报了，但 `HealthCheck.parse` **没解析** -> 报告里读不到（与没报等价）；
  · 「测没测」不单独一格 -> 没测时 `answer_changed` 也是 0，被读成"答案没变"；
  · 查询向量**写死成 1 条一批** -> 只在 `batchB == 1` 时偶然与"查询来自新配置那侧"
    一致，配置改了就成了假的成立（真机那轮读数正是这样被喂绿的）；
  · 结论落 `/health` 时**不分组** -> 两次调用无条件覆盖，报的是后跑的**等长**组
    （按构造必然稳定）—— 等于用必然成立的结论回答"答案会不会变"；
  · 换嵌入模型**不清**结论 -> 看到的是上一个模型的结论。

判据一律锚**结构关系**（谁在什么位置读了/调了谁），不锚"某某标识符存在"。
用法：check_embed_retrieve_wiring.py <LlmEngine.kt> <EngineActivity.kt> <HttpApi.kt> \
                                    <HealthCheck.kt> <EmbedRetrieveParity.kt> \
                                    <EmbedParityProbe.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import kotlin_fn_span  # noqa: E402


def obj(src, name):
    """取 `object Name { ... }` 或 `class Name(...) { ... }` 的大括号内文本。

    为什么两种都认：`Report` / `Query` 是 `data class`（不是 object），
    而 `Last` 是 `object` —— 判据要能同时取到这两类（见文件头"锚结构关系"）。
    """
    m = re.search(r'(?:object|class)\s+' + re.escape(name) + r'\s*[(\{]', src)
    if not m:
        m = re.search(r'(?:object|class)\s+' + re.escape(name) + r'\s*\{', src)
    if not m:
        return None
    k = src.index('{', m.end() - 1)
    depth = 0
    for x in range(k, len(src)):
        if src[x] == '{':
            depth += 1
        elif src[x] == '}':
            depth -= 1
            if depth == 0:
                return src[k:x + 1]
    return None


def fn(src, name):
    span = kotlin_fn_span(src, name)
    return span[0] if span else None


def add(bad, msg):
    bad.append(msg)


def list_body(src, name):
    """取 `val <name>: List<String> = listOf( ... )` 里 `listOf(` 的**配对括号内**文本。

    ⚠ 不能用非贪婪正则 `listOf\((.*?)\)`：源码里后面的块（`RETRIEVE_TEXTS_QUERIES`
    等）也有 `)`，非贪婪会一路吞到下一个人为的收尾，把"语料有几项"数成十几项
    （**判据失效而看着正常**）。所以按**括号配对**取——
    "取到哪"这件事由结构决定，不由正则的运气决定。
    """
    m = re.search(r'val\s+' + re.escape(name) + r'\s*:\s*List<String>\s*=\s*listOf\(',
                  src)
    if not m:
        return None
    i = m.end()          # `listOf(` 之后
    depth = 1
    start = i
    while i < len(src) and depth > 0:
        c = src[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return src[start:i]
        i += 1
    return None


def main(engine_p, act_p, http_p, health_p, retr_p, probe_p):
    eng = Path(engine_p).read_text(encoding='utf-8')
    act = Path(act_p).read_text(encoding='utf-8')
    http = Path(http_p).read_text(encoding='utf-8')
    health = Path(health_p).read_text(encoding='utf-8')
    retr = Path(retr_p).read_text(encoding='utf-8')
    probe = Path(probe_p).read_text(encoding='utf-8')
    # 纯逻辑层的常量出处（与 LlmEngine.kt 同目录）。缺失时按空串 -> 判据退回到
    # eng（常量还在引擎里的老版本照样判得动）。
    limits_p = Path(engine_p).parent / "EmbedLimits.kt"
    limits = (limits_p.read_text(encoding='utf-8') if limits_p.exists() else "")
    bad = []

    # ① 判定层必须把「答案变没变」与「名次变没变」分成两格（压成一格时
    #    "打平处换序"与"答案变了"混一起，而两者的下一步相反）。
    body = obj(retr, 'Report')
    if not body:
        add(bad, "EmbedRetrieveParity.Report 找不到")
    else:
        if 'answerChanged' not in body:
            add(bad, "Report 里缺 answerChanged（答案变没变的唯一那一格）")
        if not re.search(r'val\s+orderChanged[^=]*=\s*rows\.count', body):
            add(bad, "Report 里 orderChanged 不是「从 rows 数出来的」（名次变了那一格"
                     "必须真的在数：写成一个常量或手填值时它永远报 0）")
        if not re.search(r'val\s+measured', body):
            add(bad, "Report 里缺 measured（没测时 answerChanged 也是 0）")
        # 判据必须落在**集合**上（只比名次时打平处换序会被读成答案变了）。
        if not re.search(r'sameSet', body):
            add(bad, "Report 里缺 sameSet（判据必须落在集合上，不是名次）")
        if 'vacuous' not in body:
            add(bad, "Report 里缺 vacuous（两侧都没搜到 = 没量到，不是一致）")
        if 'topK' not in body:
            add(bad, "Report 里缺 topK（口径要能报出来）")

    # ② 判定层不许做决策（沿用 / 重建这类符号）—— 与 EmbedParity 同一条规矩。
    if re.search(r'Load\.(none|ok)|savePartial|rebuild\s*\(', retr):
        add(bad, "EmbedRetrieveParity 里出现了决策符号（判定层不许做决策）")

    # ③ 查询集必须是**常量**（跨版本要对同一张图），且取自同一批文本。
    if not re.search(r'val\s+RETRIEVE_QUERIES', probe):
        add(bad, "EmbedParityProbe 里缺 RETRIEVE_QUERIES（查询集必须是常量）")
    else:
        m = re.search(r'val\s+RETRIEVE_QUERIES[^=]*=\s*listOf\((.*?)\)\s*\n', probe, re.S)
        qbody = m.group(1) if m else ''
        if 'TEXTS[' not in qbody:
            add(bad, "RETRIEVE_QUERIES 必须取自 TEXTS（查询要落在同一批文本里，"
                     "否则两侧都搜不到 -> 没量到会被读成一致）")

    # ③a 检索对照的**语料**必须比 topK 大得多 —— 否则"命中集合"恒等。
    #     这一条是真机那轮读数逼出来的：语料原来直接用 TEXTS（4 条），而 topK=3，
    #     于是任何查询的 Top-3 都是"4 条里去掉 1 条"，命中集合几乎恒等 ——
    #     那一格永远报"没换答案"，**不是因为答案稳，是样本被判据压住了**。
    #     语料必须在 topK 的**两倍以上**，"某条被挤出前 3"才看得见。
    #     判据锚**结构关系**（语料条数 vs topK 常量），不锚"文件里出现过 8"。
    if not re.search(r'val\s+RETRIEVE_TEXTS\b', probe):
        add(bad, "EmbedParityProbe 里缺 RETRIEVE_TEXTS（检索对照的语料该单独一份）")
    else:
        corpus = list_body(probe, 'RETRIEVE_TEXTS')
        if corpus is None:
            add(bad, "RETRIEVE_TEXTS 语料解析失败（判据锚点失效）")
            n_rows = 0
        else:
            # 逐项数：每一行是一个元素（`TEXTS[i]` 引用，或一个字符串字面量 / 拼接）。
            # 为什么按行数：这是**结构**（列表里有几项），不是"文件里出现过几个 8"。
            n_rows = len([ln for ln in corpus.splitlines()
                          if ln.strip() and not ln.strip().startswith('//')])
        # topK 常量 —— ⚠ 自 0.9.229 起它的**出处**搬到 `EmbedLimits.kt`（纯逻辑层），
        # `LlmEngine.kt` 里写的是 `= EmbedLimits.kRetrieveParityTopK`（引用，不是数字）。
        # 原因：住在 LlmEngine（依赖 Android）时宿主单测编不到，那两份套件
        # **编不过、一次都没跑过**，输出却是"SKIP（缺工具链）"。
        # 见 `tools/run_kt_test_runner_guard.py` 的文件头。
        mk = (re.search(r'kRetrieveParityTopK\s*=\s*(\d+)', limits)
              or re.search(r'kRetrieveParityTopK\s*=\s*(\d+)', eng))
        topk = int(mk.group(1)) if mk else 0
        if topk <= 0:
            add(bad, "找不到 kRetrieveParityTopK 的数值（EmbedLimits 与 LlmEngine 里都没有）")
        elif n_rows < 2 * topk:
            add(bad, f"检索对照语料 {n_rows} 条不足 topK({topk}) 的两倍 —— "
                     "Top-3 从 4 条里挑时命中集合几乎恒等，那一格会永远报"
                     "『没换答案』（而它不是答案稳，是样本太小）")
        # 查询必须取自这份语料
        if not re.search(r'val\s+RETRIEVE_TEXTS_QUERIES\b', probe):
            add(bad, "EmbedParityProbe 里缺 RETRIEVE_TEXTS_QUERIES（语料的查询集）")
        else:
            qb = list_body(probe, 'RETRIEVE_TEXTS_QUERIES') or ''
            if 'RETRIEVE_TEXTS[' not in qb:
                add(bad, "RETRIEVE_TEXTS_QUERIES 必须取自 RETRIEVE_TEXTS（查询要落在"
                         "同一份语料里，否则两侧都搜不到 -> 没量到被读成一致）")

    # ④ 入口：两侧各编一套（两个批大小），且查询来自**配置**而不是手写常量。
    f = fn(eng, 'probeRetrieveParity')
    if not f:
        add(bad, "LlmEngine.probeRetrieveParity 找不到（那条对照没有入口）")
    else:
        calls = len(re.findall(r'embedAllInMemory\s*\(', f))
        if calls < 2:
            add(bad, "probeRetrieveParity 里只编了一套向量 —— 比的是"
                     "『同一侧查自己』，结论恒为一致（假的断言成立）")
        if 'batchA' not in f or 'batchB' not in f:
            add(bad, "probeRetrieveParity 没有两个批大小实参（比不了批大小）")
        if 'EmbedRetrieveParity.compare' not in f:
            add(bad, "probeRetrieveParity 没调 EmbedRetrieveParity.compare（判定层没接上）")
        if 'EmbedRetrieveParity.Last.record' not in f:
            add(bad, "probeRetrieveParity 没把结论落 Last（/health 拿不到）")

        # ④a 查询向量必须按 **batchB** 那一侧编（而不是写死 1 条一批）。
        #     原来那一句是 `embedBatch(listOf(q), dim)` —— 只在 `batchB == 1` 时
        #     才偶然与"查询来自新配置那一侧"一致；用户配置不是 1 时，这一格比的
        #     成了"batchA 建的库 vs 1 条一批的查询"，与要量的那件事无关，
        #     而它**看起来仍然成立**（真机那轮读数正是在这里被喂绿的）。
        #     ⚠ 判据锚**结构**：查询那段的取数必须写出 batchB，且**不能**是
        #       写死的 `listOf(q)` 单条调用。
        qloop = re.search(r'val\s+qv\w*\s*=.*?\n(?=\s*(?:if\s*\(|val\s+indicesA))', f, re.S)
        if not qloop:
            add(bad, "取不到 probeRetrieveParity 里算查询向量那一段（判据锚点失效）")
        else:
            qtext = qloop.group(0)
            if 'batchB' not in qtext:
                add(bad, "查询向量没按 batchB 那一侧编 —— 查询与索引不是同一批布局，"
                         "这格量的是『1 条一批查 batchA 建的库』，与要量的事无关")
            if re.search(r'embedBatch\s*\(\s*listOf\s*\(', qtext):
                add(bad, "查询向量写死成 1 条一批（`embedBatch(listOf(q), ...)`）—— "
                         "只在 batchB==1 时偶然对，配置改了就成了假的成立")

        # ④b 落 Last 必须**只落长短悬殊那组**（与向量那格落等长相反）。
        #     两次调用无条件覆盖时，`/health` 报的是后跑的等长那组 ——
        #     而它按构造就是稳的，等于用必然成立的那组回答"答案会不会变"。
        recline = [ln for ln in f.splitlines() if 'Last.record' in ln and '//' not in ln.split('Last.record')[0]]
        if not recline:
            add(bad, "probeRetrieveParity 里找不到落 Last 的那一行（判据锚点失效）")
        elif 'label' not in recline[0]:
            add(bad, "落 Last 没按 label 分闸 —— 两次调用会无条件覆盖，"
                     "`/health` 报的是后跑的等长组（按构造必然稳定）")
        # 签名里也得真有 label 形参（只锚"函数体里出现过 label"时，
        # 删掉形参、改成读常量照样绿）。
        if not re.search(r'fun\s+probeRetrieveParity\s*\([^)]*label\s*:', eng, re.S):
            add(bad, "probeRetrieveParity 没有 label 形参 —— 落读数分不了组")
    # 「只在内存里」是这一格的性质：不落盘、不碰知识库。
    g = fn(eng, 'embedAllInMemory')
    if not g:
        add(bad, "LlmEngine.embedAllInMemory 找不到")
    else:
        if re.search(r'RagIndexStore\.save|savePartial|writeTexts', g):
            add(bad, "embedAllInMemory 落盘了 —— 这条对照只在内存里比，不该写出"
                     "一个用户没点过的知识库")
        # 复用同一条编码入口：可以直接调 `embedBatch`，也可以走摊行那层
        # （它自己那条判据另钉 `embedBatch`）—— 但不能两个都没有（那就是另写了一遍）。
        if 'embedBatch' not in g and 'embedRowsInMemory' not in g:
            add(bad, "embedAllInMemory 没走 embedBatch（两套向量要复用同一条编码入口）")
    # 摊行那层也要走同一条编码入口（`embedAllInMemory` 与查询向量都复用它）。
    h = fn(eng, 'embedRowsInMemory')
    if not h:
        add(bad, "LlmEngine.embedRowsInMemory 找不到（语料与查询的摊行入口）")
    else:
        if 'embedBatch' not in h:
            add(bad, "embedRowsInMemory 没走 embedBatch（另写一遍就是比另一段代码）")
        if re.search(r'RagIndexStore\\.save|savePartial|writeTexts', h):
            add(bad, "embedRowsInMemory 落盘了 —— 这条对照只在内存里比")

    # ⑤ UI：按钮里必须真的调它（否则"有入口"只是名义上的）。
    if 'LlmEngine.probeRetrieveParity(' not in act:
        add(bad, "EngineActivity 里没有调 probeRetrieveParity（按钮没接上）")
    else:
        if 'EmbedBatchPref.get' not in act:
            add(bad, "EngineActivity 里没读 EmbedBatchPref —— 批大小手写常量时"
                     "『改了配置这格还按老数字跑』")
        if ('EmbedParityProbe.RETRIEVE_QUERIES' not in act
                and 'EmbedParityProbe.RETRIEVE_TEXTS_QUERIES' not in act):
            add(bad, "EngineActivity 没传查询集（RETRIEVE_QUERIES / "
                     "RETRIEVE_TEXTS_QUERIES 都没接上）")
        # ⚠ 锚 `RETRIEVE_TEXTS,`（**后面跟逗号**）而不是 `RETRIEVE_TEXTS`：
        #   后者是 `RETRIEVE_TEXTS_QUERIES` 的前缀，判据会被那个名字喂绿。
        if 'EmbedParityProbe.RETRIEVE_TEXTS,' not in act:
            add(bad, "EngineActivity 没拿 RETRIEVE_TEXTS 当检索对照的语料 —— "
                     "用 TEXTS（4 条）时 topK=3 会让命中集合恒等（样本太小，"
                     "不是答案稳）")
        # ⚠ 两组都必须带 label：不带时入口把"缺 label"当成"第一组"落进 `/health`，
        #   于是等长那组（按构造必然稳定）会被当成长短悬殊那组报出去 ——
        #   一个**相反**的结论（"答案没变"），而它正是这一格存在的理由的反面。
        #   ⚠ 判据锚**那两次调用的实参**（不是"文件里出现过这个常量"）：
        #     `LlmEngine.SAME_LEN_LABEL` 在向量那格（`probeEmbedBatchParity`）也用，
        #     只锚"出现过"时把检索那格的 label 删掉照样绿。
        for call, sym in (('EmbedParityProbe.RETRIEVE_TEXTS,', 'LlmEngine.LONG_SHORT_LABEL'),
                          ('EmbedParityProbe.SAME_LEN, EmbedParityProbe.SAME_LEN_QUERIES,',
                           'LlmEngine.SAME_LEN_LABEL')):
            k = act.find(call)
            if k < 0:
                continue  # 调用点本身缺了由上面那两条判据抓
            args = act[k:k + 400]
            args = args[:args.index(')')] if ')' in args else args
            if sym not in args:
                add(bad, f"EngineActivity 调检索对照时没在实参里带 {sym} —— "
                         "落 /health 的那组会变成『后跑的』而不是『要报的那组』")

        # ⑤c 入口自己也要**拒绝两侧相同的批大小**。UI 侧改对了，但入口这层
        #     若不加兜底，将来谁再写一个调用点忘了推导，就会再造一个"假的绿"。
        #     ⚠ 判据要**同时**看两件事，缺一不可：
        #       · 签名里真有 `batchA` / `batchB` **两个形参**（不是函数体里
        #         提过这两个名字 —— 兜底那句 `batchA == batchB` 自己就会把名字
        #         带出来，只锚"出现过"时把形参删掉照样绿）；
        #       · 函数体里有"两者相同"的**拒绝**（`batchA == batchB`）。
        sig_pf = re.search(r'fun\s+probeRetrieveParity\s*\(([^)]*)\)', eng, re.S)
        pf = fn(eng, 'probeRetrieveParity')
        if not sig_pf or not pf:
            add(bad, "取不到 probeRetrieveParity 的签名/函数体（判据锚点失效）")
        else:
            params = sig_pf.group(1)
            if 'batchA' not in params or 'batchB' not in params:
                add(bad, "probeRetrieveParity 没有两个批大小形参（batchA / batchB）—— "
                         "两个实参收成一个时比的是『同一侧查自己』，结论恒为一致")
            if not re.search(r'batchA\s*==\s*batchB|batchB\s*==\s*batchA', pf):
                add(bad, "probeRetrieveParity 没拒绝『两侧批大小相同』 —— 两侧相同时"
                         "比的是『自己对自己』，结论恒为一致（假的断言成立）")

        # ⑤a **两侧批大小必须真的不同**。这一条是真机那轮闪退现场逼出来的：
        #     原来写的是 `if (curBatch == baseBatch) 1 else baseBatch` —— 用户把设置
        #     改成 1（正是复现配置）时 curBatch=1、baseBatch=8 不等，于是 otherBatch
        #     取到 8，**两侧都是 8**：比的是"8 对 8"，结论恒为"答案没变"。那是**假的**
        #     断言成立（比没有结论更坏：会让人据此放行 `g`）。
        #     判据锚**结构**：两个批大小变量之间必须出现一个"取反/不同"的关系项
        #     —— 只锚"有个 otherBatch 变量"时，`otherBatch = baseBatch` 这种写法照样绿。
        btn = act[act.index('logParityBtn.setOnClickListener'):]
        btn = btn[:btn.index('logParityRow.addView')] if 'logParityRow.addView' in btn else btn
        if 'otherBatch' not in btn:
            add(bad, "按钮里没有 otherBatch（第二套批大小）")
        else:
            # 必须是"与 baseBatch 不同"的那种推导，而不是把 baseBatch 抄过去。
            if not re.search(r'otherBatch\s*=\s*if\s*\([^)]*!=', btn):
                add(bad, "otherBatch 不是从『与 baseBatch 不同』推出来的 —— "
                         "两侧取同一个批大小时比的是『自己对自己』，结论恒为一致"
                         "（假的断言成立：会让人据此放行 `g`）")

        # ⑤b **这一趟不许在主线程上跑**。四组对照里两组各要编两遍「长短悬殊」
        #     （含一条 ~1180 token 的超长文本），真机上要跑几十秒；本页所有重活
        #     都走 ioExecutor，只有它漏在外面就是 ANR / 被系统杀掉 —— 现场看起来
        #     像 native 崩（探针里没有任何信号记录）。
        #     判据：`setOnClickListener` 的块里必须出现 `ioExecutor.execute`。
        if 'ioExecutor.execute' not in btn:
            add(bad, "按钮里的比对照跑在主线程上（没有 ioExecutor.execute）—— "
                     "几十秒的 native 重活会 ANR / 被系统杀掉，现场看起来像 native 崩")

    # ⑥ `/health` 报 + 探活解析：两处都要有，缺一处等于没报。
    for key in ['embed_retrieve_measured":', 'embed_retrieve_queries":',
                'embed_retrieve_answer_changed":', 'embed_retrieve_order_changed":',
                'embed_retrieve_batch_base":', 'embed_retrieve_batch_other":',
                'embed_retrieve_topk":']:
        field = key.rstrip('":')
        if key not in http:
            add(bad, f"/health 没报 {field}")
        # 探活侧读的是字段名（`bool("embed_retrieve_measured")`），判据锚那个**字面量**：
        # 只锚裸名时，`HealthCheck` 里那句注释会把判据喂绿（注释不是解析）。
        if ('"' + field + '"') not in health:
            add(bad, f"HealthCheck 没解析 {field}")
    # 「测没测」必须单独一格（没测时 answer_changed 也是 0）。
    if 'embed_retrieve_measured":' not in http or '"embed_retrieve_measured"' not in health:
        add(bad, "『测没测』没单独报（没测时 answer_changed 也是 0，同形）")

    # ⑦ 换嵌入模型必须清结论（它描述的是"这个模型换批大小后答案稳不稳"）。
    if not re.search(r'EmbedRetrieveParity\.Last\.reset\(\)', eng):
        add(bad, "换嵌入模型时没清 EmbedRetrieveParity.Last（留着上一个模型的结论）")

    if bad:
        for b in bad:
            print("FAIL  " + b)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:]))
