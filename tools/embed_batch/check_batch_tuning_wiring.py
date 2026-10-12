#!/usr/bin/env python3
"""判「嵌入合批的读数与批大小设置」这两件事**真的接上了**（ISSUE #269 第 1+2 步）。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠两个纯逻辑单测）
═══════════════════════════════════════════════════════════════════════════
第 1 步（读数）与第 2 步（可调）各自的纯逻辑单元都能全绿，而它们要修的
**恰恰是"纯逻辑全绿、界面上什么都没有"这一类**：

  · [第 1 步] `EmbedBatchStats` 算得对，但建库循环里**没人调 record** ->
    读数永远是空态（"还没跑过"），而那正是本轮要消除的"合批怎么样全靠猜"；
  · [第 1 步] 调了 record，但 `/health` **没报出来** -> 用户与客户端在探测里
    读不到，只有 App 内日志有（而日志会滚、要人先动手翻）；
  · [第 1 步] `/health` 报了字段，但 `HealthCheck.parse` **没解析** ->
    字段在响应里、探活报告里没有 —— 与没报等价（这是本仓库反复记录的一族）；
  · [第 2 步] 设置页有输入框、能存盘，但 `buildIndex` **没读它** ->
    与当初那个"线程数输入框对建库完全无效"一模一样：能填、能存、就是没用；
  · [第 2 步] 批大小**没进指纹** -> 用户改了批大小，旧索引仍被当成"可用"，
    而新旧向量一旦不逐位相同，检索就是"用一种语义命中、注入另一种语义的正文"；
  · [第 2 步] 建库与检索**各取一次批大小** -> 两边算法不同时指纹永远不匹配
    （= 每次打开都在重建，只是慢，所以没人会去查）。

判据一律锚**结构关系**（谁在什么位置读了/调了谁），不锚"某某标识符存在"。
用法：check_batch_tuning_wiring.py <LlmEngine.kt> <EngineActivity.kt> <HttpApi.kt> \\
                               <HealthCheck.kt> <EmbedBatchStats.kt> <EmbedBatchPref.kt> \\
                               <RagIndexStore.kt> <rag_index.h>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body, kotlin_fn_body  # noqa: E402


def kt_fn(src, name):
    """按括号配对取 Kotlin 函数体；找不到返回 None。"""
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


def main():
    (engine_p, act_p, http_p, health_p, stats_p, pref_p, store_p, hdr_p) = sys.argv[1:9]
    engine = Path(engine_p).read_text(encoding='utf-8', errors='replace')
    act = Path(act_p).read_text(encoding='utf-8', errors='replace')
    http = Path(http_p).read_text(encoding='utf-8', errors='replace')
    health = Path(health_p).read_text(encoding='utf-8', errors='replace')
    stats = Path(stats_p).read_text(encoding='utf-8', errors='replace')
    pref = Path(pref_p).read_text(encoding='utf-8', errors='replace')
    store = Path(store_p).read_text(encoding='utf-8', errors='replace')
    hdr = Path(hdr_p).read_text(encoding='utf-8', errors='replace')
    fails = []

    # ── 判据 1：建库循环真的记了两条路 ──────────────────────────────────────
    bi = kt_fn(engine, "buildIndex")
    if bi is None:
        fails.append("LlmEngine.kt 里找不到 buildIndex（判据锚点失效）")
        bi = ""
    else:
        if not re.search(r'EmbedBatchStats\.record\s*\(', bi):
            fails.append("buildIndex 里没有 EmbedBatchStats.record —— 读数永远是空态"
                         "（'还没跑过'），合批到底有没有生效仍然只能靠猜（第 1 步没接上）")
        # 两条路各记一次，且**路由取自各自的分支**：只记一条 = 另一条白跑而
        # 不知道（"整轮一条都没合批"这个关键读数会消失）。
        if not re.search(r'Route\.BATCHED', bi):
            fails.append("buildIndex 没记 Route.BATCHED（合批成功这批没被计入）")
        if not re.search(r'Route\.FELL_BACK', bi):
            fails.append("buildIndex 没记 Route.FELL_BACK（回落这批没被计入 —— "
                         "'整轮一条都没合批'这个关键读数会消失）")
        # 建库结束时必须把这一轮的路由说出来（用户唯一会看的那一行）。
        if not re.search(r'logBatchRoute\s*\(', bi):
            fails.append("buildIndex 没有把批级路由说一句话（logBatchRoute）—— "
                         "用户看不到'这轮到底合批了没有'")
        # ⚠ 那一行报的必须是**本段增量**，不是进程级累计 —— 而增量要靠
        # "开工前留一份基线"才拿得到。判据锚"buildIndex 的**函数体里**出现过
        # 一份开工前的快照变量"，而不是"文件里出现过 snapshot()"：
        # logBatchRoute 自己也会调 snapshot()（那是收尾端），锚"出现过"时
        # 就算把开工基线整个删掉，判据照样绿。
        # 为什么这条值得单列：本仓库真出过这处措辞错 —— 累计值顶着"本轮"两个字，
        # 用户拿它去比"批4 vs 批1"时读到两个几乎相等的数，会读成"批大小没生效"。
        if not re.search(r'EmbedBatchStats\.snapshot\s*\(\s*\)', bi):
            fails.append("buildIndex 里没有**开工前**取一份 EmbedBatchStats.snapshot() —— "
                         "没有基线，收尾那一行只能报进程级累计，而它顶着'本段'两个字，"
                         "会被读成'这一次建库'（用户用这行判批大小时方向就反了）")
        # 两处调用（成功那条 + 待重建那条）都必须把基线传下去：漏传一处时
        # 那一路仍报累计，而它恰恰是"部分失败、更想核对批行为"的那一路。
        n_calls = len(re.findall(r'logBatchRoute\s*\(\s*name\s*,\s*batch\s*,\s*\w+\s*\)', bi))
        if n_calls < 2:
            fails.append(f"logBatchRoute 只被传了基线的调用点 {n_calls} 处（应 2："
                         "索引已重建 / 待重建两条收尾路都要报本段 —— 漏传的那一路"
                         "会把累计读成本次建库）")
        # ⚠ 那一行报的批大小必须**与写进指纹 `g` 的那个是同一个源**。
        # 为什么这条要单独钉：真机上真出现过"日志写 批大小=8，而磁盘上那份索引的
        # `g` 是 4"这种两处各取一遍造成的分岔 —— 用户拿日志判断"8 批到底建过没有"
        # 时，读到的数是**另一处**取来的。这一行的唯一用途就是让人去核对
        # "这次真正跑的是几"，所以它与指纹的 `g` 必须同源（同一个局部变量）。
        # 判据锚**结构**：`embedFpInput(` 与 `logBatchRoute(name, ` 后面跟的是
        # 同一个标识符（不锚"文件里出现过 batch"）。
        m_fp = re.search(r'embedFpInput\s*\(\s*\w+\s*,\s*([A-Za-z_]\w*)\s*\)', bi)
        m_lb = re.search(r'logBatchRoute\s*\(\s*\w+\s*,\s*([A-Za-z_]\w*)\s*,', bi)
        if not m_fp or not m_lb:
            fails.append("取不到『指纹 g 的批大小』与『日志那行的批大小』（判据锚点失效）")
        elif m_fp.group(1) != m_lb.group(1):
            fails.append(
                f"日志那行的批大小（{m_lb.group(1)}）与写进指纹 g 的那个"
                f"（{m_fp.group(1)}）**不是同一个源** —— 两处各取一遍会分岔，"
                "用户拿日志去核对『这次真正跑的是几』时读到的是另一个数")

        # 收尾那一行必须用**段口径标签**：`describe(s, kScopeThisBuild)`。
        # 锚"第一个实参后面还跟着一个 scope 实参"，而不是锚"出现过 kScopeThisBuild" ——
        # 后者在"把标签写在注释里"时照样绿。
        lbr = kt_fn(engine, "logBatchRoute")
        if lbr is None:
            fails.append("LlmEngine.kt 里找不到 logBatchRoute（判据锚点失效）")
        else:
            # ⚠ 形参要在**签名**里看，不在函数体里（`lbr` 只有体，注释还被剥掉了）。
            sig = re.search(r'fun\s+logBatchRoute\s*\(([^)]*)\)', engine)
            if sig is None or 'before' not in sig.group(1):
                fails.append("logBatchRoute 没有收开工基线（形参里没有 `before`）—— "
                             "它拿不到基线，就只能报累计")
            if not re.search(r'EmbedBatchStats\.delta\s*\(', lbr):
                fails.append("logBatchRoute 里没有 EmbedBatchStats.delta —— 收尾那一行"
                             "没把'收尾快照 - 开工快照'减出来，报的仍是累计量")
            if not re.search(r'kScopeThisBuild', lbr):
                fails.append("logBatchRoute 没把口径标签设成 kScopeThisBuild —— 段读数配"
                             "'累计'的口径（空态时还会说'本次运行还没跑过'，"
                             "而真相是这一段建库一条都没编过）")
            if re.search(r'本轮', lbr):
                fails.append("logBatchRoute 里还有「本轮」—— 它既像'本次'又像'累计'，"
                             "正是这处出过的错（数字正常、标签把方向指反）；"
                             "累计说'累计'、段说'本段'")

    # ── 判据 2：/health 报出读数，且 HealthCheck 解析它 ─────────────────────
    health_fields = [
        ("embed_batch_batches", "合批批次"),
        ("embed_batch_entries", "合批条数"),
        ("embed_batch_fallback_batches", "回落批次"),
        ("embed_batch_fallback_entries", "回落条数"),
        ("embed_batch_ms", "合批毫秒"),
        ("embed_batch_size", "当前批大小"),
    ]
    for f, what in health_fields:
        if f not in http:
            fails.append(f"/health 没有报 {f}（{what}）—— 这个读数对外不可见，"
                         "只有 App 内日志有（而日志会滚、要人先动手翻）")
    # HealthCheck 侧必须解析 —— 报了不解析 = 响应里有、报告里没有。
    for f in ("embed_batch_batches", "embed_batch_entries", "embed_batch_fallback_batches",
              "embed_batch_fallback_entries", "embed_batch_ms", "embed_batch_size"):
        if not re.search(r'long\("' + re.escape(f) + r'"\)|int\("' + re.escape(f) + r'"\)', health):
            fails.append(f"HealthCheck.parse 没解析 {f} —— 报在响应里、探活报告里读不到"
                         "（与没报等价）")
    # 报的是 EmbedBatchStats 的快照，不是另算一遍。
    if not re.search(r'EmbedBatchStats\.snapshot\s*\(', http):
        fails.append("HttpApi 的 /health 没有读 EmbedBatchStats.snapshot —— "
                     "自己另算一遍时，两处迟早分岔（数字都像正常数）")

    # ── 判据 3：批大小从设置读进建库，且建库与检索**同一个取值处** ────────
    if not re.search(r'EmbedBatchPref\.get\s*\(', engine):
        fails.append("LlmEngine.kt 里没有 EmbedBatchPref.get —— 批大小从设置读不进来，"
                     "建库仍会用默认参数（与'线程数输入框对建库完全无效'同一个病）")
    # 建库与检索都必须把批大小喂给 embedFpInput（同一个函数的两个参数位）。
    # ⚠ 只数**调用点**（排除函数声明与注释里的 `[embedFpInput]`）——
    # 把声明也算进去时，"两处都传了批大小"这条判据会**恒假**（它本身就是一个
    # 不带批大小实参的函数声明）。
    call_sites = re.findall(r'=\s*embedFpInput\s*\(([^)]*)\)', engine)
    n_fp_calls = len(call_sites)
    n_fp_two_arg = len([a for a in call_sites if a.count(',') >= 1])
    if n_fp_calls < 2:
        fails.append(f"embedFpInput 只有 {n_fp_calls} 处调用（建库与检索两处都要，否则"
                     "一边算指纹时不知道批大小 —— 指纹永远不匹配，每次都在重建）")
    if n_fp_two_arg != n_fp_calls:
        fails.append(f"embedFpInput 的调用点里只有 {n_fp_two_arg}/{n_fp_calls} 处传了批大小 ——"
                     "漏传的那处会把指纹算成'批大小=0'，于是它永远不匹配真实索引")
    # 设置页必须真的写盘、且有确定按钮（"我到底改好了没"要有落点）。
    if not re.search(r'EmbedBatchPref\.set\s*\(', act):
        fails.append("EngineActivity 没有 EmbedBatchPref.set —— 设置页那个框存不下来")
    if not re.search(r'saveRagBatchSize\s*\(', act):
        fails.append("EngineActivity 里没有 saveRagBatchSize —— 批大小的读写没有收口到一处")
    # ⚠ 最关键的一跳：**建库那次调用**必须把批大小传进去。上面那两条只证明
    # "读写的函数存在"，证明不了"建库真的用了它" —— 而那正是本 issue 要修的
    # 形态（能填、能存、就是对建库没用）。判据锚"buildIndex(...) 的实参里有
    # embedBatchSize"，而不是"文件里出现过 EmbedBatchPref"。
    if not re.search(r'buildIndex\s*\([^)]*embedBatchSize\s*=', act):
        fails.append("EngineActivity 调 buildIndex 时没传 embedBatchSize —— "
                     "设置页那个框能填、能存，但对建库没用（本 issue 的原始形态）")
    # ⚠ 批大小必须在**主线程上先读完**。`saveRagBatchSize` 读的是 `ragBatchEt.text`，
    # 而那是一个 `View` 的属性 —— Android 的 UI 工具包**不是线程安全的**，
    # 在 `Thread { ... }` 里读它是未定义行为（最软是读到旧值，最硬是闪退）。
    # 判据锚：`Thread {` **之前**必须已经把它取到一个 local 里。
    # 锚法：`startBuildIndex` 的函数体里，"读批大小"必须排在 `Thread {` **之前**。
    sb_body = kt_fn(act, "startBuildIndex")
    if sb_body is None:
        fails.append("EngineActivity 里找不到 startBuildIndex（判据锚点失效）")
    else:
        i_thread = sb_body.find("Thread {")
        # ⚠ 只认**赋值形式的调用**（`= saveRagBatchSize()`），不认注释里的提及 ——
        # 注释里也有这个名字，而按"出现过"取位置时，判据会被那行**注释**满足
        #（= 恒绿：代码里真把它挪进了线程，判据还是绿的）。
        m_read = re.search(r'val\s+\w+\s*=\s*saveRagBatchSize\s*\(', sb_body)
        if i_thread < 0:
            fails.append("startBuildIndex 里没有 Thread {（建库不在后台跑了？）")
        elif m_read is None:
            fails.append("startBuildIndex 没读批大小（**没有** `val x = saveRagBatchSize()`）"
                         "—— 建库会用默认参数，设置页那个框等于没用")
        elif m_read.start() > i_thread:
            fails.append("批大小在**后台线程里**读（赋值排在 Thread { 之后）"
                         "—— 它读的是 View 的 .text，Android 的 UI 工具包不是线程安全的")
    # 输入框的默认显示必须是**当前生效值**（不是 0）：0 的真义是"没设过"，
    # 显示 0 会让人以为"合批关了"，而实际跑 8 —— 一个与真相相反的读数。
    if not re.search(r'EmbedBatchPref\.isSet\s*\(', act):
        fails.append("设置页没有用 EmbedBatchPref.isSet 区分'没设过'与'设了值' —— "
                     "输入框里显示 0 会被读成'关了合批'（而 0 的真义是'没设过'）")

    # ── 判据 4：批大小**进了指纹**（两边）─────────────────────────────────
    if not re.search(r'embedBatch\s*:\s*Int\s*=\s*0', store):
        fails.append("RagIndexStore.FpInput 里没有 embedBatch 字段 —— 批大小不进指纹时，"
                     "用户改了它、旧索引仍被当成'可用'（新旧向量语义不同就是静默错位）")
    if not re.search(r'"g"\s+to\s+"合批每批条数"', store) and \
       not re.search(r'"g" to "[^"]*批[^"]*"', store):
        fails.append("RagIndexStore 的 kRequiredFpFields 里没有 `g`（合批每批条数）——"
                     "它不是必比字段时，旧格式指纹会被静默放行")
    if not re.search(r'sb\.append\("g"\)', store):
        fails.append("RagIndexStore.fingerprintOf 没有写 `g` 字段（批大小没进指纹）")
    if not re.search(r'embedBatch\s*=\s*f\("g"\)', store):
        fails.append("RagIndexStore.FpInput(fp: String) 没有搬 `g` 字段 —— "
                     "追加/删除一条时按默认批大小重算指纹，用户点一次「追加」就把索引弄失效")
    if not re.search(r'int\s+embedBatch\s*=', hdr):
        fails.append("rag_index.h 的 IndexFingerprint 里没有 embedBatch 字段")
    if not re.search(r's\s*\+=\s*"g";', hdr):
        fails.append("rag_index.h 的 fingerprint_of 没有写 `g` 字段（两边指纹字段集合会分岔）")

    # ── 判据 5：归一判据只有一处（设置页与建库共用）──────────────────────
    if not re.search(r'fun\s+normalize\s*\(', pref):
        fails.append("EmbedBatchPref 里没有 normalize —— 设置页与建库各钳一遍时迟早分岔，"
                     "而分岔的症状是'输入框里 16、日志里跑 8，界面上一个字都没有'")
    # 它必须**转调** LlmEngine 的那个函数，不许自己再钳一次。
    # 两种写法都收：表达式体（`fun normalize(v: Int): Int = ...`）与块体。
    nm = re.search(r'fun\s+normalize\s*\([^)]*\)\s*:\s*Int\s*=\s*(.+)', pref)
    if not nm:
        nm = re.search(r'fun\s+normalize\s*\([^)]*\)[^{]*\{(.*?)\n    \}', pref, re.S)
    if not nm:
        fails.append("EmbedBatchPref.normalize 的函数体找不到（判据锚点失效）")
    elif 'kEmbedBatchLimit' not in nm.group(1):
        fails.append("EmbedBatchPref.normalize 没有转调 LlmEngine.kEmbedBatchLimit —— "
                     "自己再钳一次时，上限与 native 的 n_seq_max 会脱钩")
    # 上限定成**一处**：LlmEngine 里那两个常量必须都在。
    # ⚠ 自 0.9.229 起这个数的**出处**搬到 `EmbedLimits.kt`（纯逻辑层），
    #   `LlmEngine.kt` 里写的是 `= EmbedLimits.kEmbedBatchDefault`（引用）。
    #   这一条判据只问"这个数有没有被命名"（符号存在），所以**两处都认**。
    #   见 `tools/run_kt_test_runner_guard.py` 的文件头。
    limits_p2 = Path(engine_p).parent / "EmbedLimits.kt"
    limits2 = limits_p2.read_text(encoding='utf-8') if limits_p2.exists() else ""
    if not (re.search(r'kEmbedBatchDefault\s*=', limits2)
            or re.search(r'kEmbedBatchDefault\s*=', engine)):
        fails.append("没有 kEmbedBatchDefault（默认批大小没有命名）—— "
                     "EmbedLimits.kt 与 LlmEngine.kt 里都找不到")
    if not (re.search(r'kEmbedBatchRows\s*=', limits2)
            or re.search(r'kEmbedBatchRows\s*=', engine)):
        fails.append("没有 kEmbedBatchRows（上限没有单一出处）—— "
                     "EmbedLimits.kt 与 LlmEngine.kt 里都找不到")

    # ── 判据 6：建索引时用的批大小是**读数**（ISSUE #269 第 5 步）──────────
    # ═══════════════════════════════════════════════════════════════════════
    # 这一条补的是"只有一半读数"留下的洞：此前 `/health` 里**只有当前配置**那个
    # 批大小，而"建这份索引时用的是几"哪里都读不到。于是用户把批大小 8 改成 1
    # 之后整库**静默不可检索**（`g` 是必比字段），而他能拿到的只有一句
    # "索引不可用：合批每批条数 变了" —— **机制一个字都不说**。
    # 两个数摆在一起，"我改的"与"库认的"才对得上账。
    # ═══════════════════════════════════════════════════════════════════════
    if not re.search(r'embed_batch_size_index"', http):
        fails.append("/health 没有报 embed_batch_size_index —— '建索引时用的批大小' 在读数上"
                     "没有落点，用户改了批大小之后库为什么废了**没有任何地方能回答**"
                     "（两个数里只有一个在外面）")
    if not re.search(r'embed_batch_size_index_kb"', http):
        fails.append("/health 没有报 embed_batch_size_index_kb —— `g` 是**每份索引自己**开头"
                     "写的，没有'全局的那个'；不报库名时，用户看到一个 -1 分不清是"
                     "'这个库没索引'还是'服务端读的是别的库'")
    if not re.search(r'int\("embed_batch_size_index"\)', health):
        fails.append("HealthCheck.parse 没解析 embed_batch_size_index —— 报在响应里、"
                     "探活报告里读不到（与没报等价）")
    if not re.search(r'str\("embed_batch_size_index_kb"\)', health):
        fails.append("HealthCheck.parse 没解析 embed_batch_size_index_kb")
    # 它必须**从索引指纹里读**（真值），而不是"当前配置"的复制品 ——
    # 复制品 = 两个数恒相等 = 对账恒绿 = 这一格白加。
    if not re.search(r'fun\s+batchInFingerprint\s*\(', store):
        fails.append("RagIndexStore 里没有 batchInFingerprint —— 从指纹里取 `g` 的取值处"
                     "不存在，'建索引时用的批大小'只能靠推算（而推算的那份会在"
                     "哪天默认值变了之后静默失效）")
    if not re.search(r'fun\s+batchOfIndex\s*\(', store):
        fails.append("RagIndexStore 里没有 batchOfIndex（从磁盘索引读那一格）—— "
                     "/health 拿不到真值")
    if not re.search(r'batchOfIndex\s*\(', http):
        fails.append("HttpApi 的 /health 没有调 batchOfIndex —— 那一格没有真的从索引里读，"
                     "读数与真相无关")

    # ── 判据 7：`g` 只差一格时，说得出"能救多少"（ISSUE #269 第 5 步）──────
    # `g`（旋钮）与 v/p/b/m/d/t/o/k/c/x/r（语义）是**两类**东西。把它们压进
    # 同一个"从头编"里，用户就看不到"那 N 块是语义同源的、只是旋钮动了"——
    # 他只能认为几十几分钟全白费，而真相是"改回来就能续上"。
    if not re.search(r'onlyBatch\s*:\s*Boolean\s*=', store):
        fails.append("RagIndexStore.FpDiff 里没有 onlyBatch（`g` 单独定级）—— "
                     "改一下批大小，一份语义完好的索引被说得像换了模型，"
                     "用户看不到'能救多少'")
    if not re.search(r'salvage\s*:\s*Int\s*=', store):
        fails.append("RagIndexStore.FpDiff 里没有 salvage（能救的前缀条数）—— "
                     "'能救多少'没有落点")
    if not re.search(r'onlyBatch\s*=\s*true', store):
        fails.append("compareFp 里没有 onlyBatch=true 的出口 —— `g` 差异仍被报成普通'变了'")
    cfp = kt_fn(store, "compareFp")
    if cfp is None:
        fails.append("找不到 compareFp（判据锚点失效）")
    else:
        # 语义那一轮必须**先去 g**：`kSemanticsFpFields` 要用在值比较上。
        if 'kSemanticsFpFields' not in cfp:
            fails.append("compareFp 的值比较没有用 kSemanticsFpFields（除 g 外的语义字段）"
                         "—— `g` 差异仍会与语义差异共用一个出口")
        # 但**缺失**检查仍必须覆盖全部必比字段（含 g）：旧格式缺 g 不许放行。
        if 'kRequiredFpFields' not in cfp:
            fails.append("compareFp 里没有 kRequiredFpFields —— 缺 g（旧格式）时会被静默放行")
    # buildIndex 必须把"能救多少"说出来（不许只有一句"从头编"）。
    if not re.search(r'onlyBatch', engine):
        fails.append("LlmEngine.buildIndex 里没有读 onlyBatch —— '只差批大小'这件事"
                     "在日志里说不出来，用户只看到一行'从头编'")
    if not re.search(r'salvage', engine):
        fails.append("LlmEngine.buildIndex 里没有用 salvage —— 可救的块数没有被报出来")

    # ── 判据 8：「还原默认设置」必须**真的**清掉这两格（否则读数说谎）──────
    # `isSet()` 的判据是"键在不在"。还原时写一个默认值进去 = 键仍在 = `isSet`
    # 仍为 true = 用户以为自己还原了而读数说他没还原。更坏的是批大小进指纹（`g`）：
    # 他设过 1（逐条）之后还原 -> 界面显示回默认 8、而**跑的还是 1**。
    if not re.search(r'fun\s+clear\s*\(', pref):
        fails.append("EmbedBatchPref 里没有 clear() —— 「还原默认设置」只能'写默认值'，"
                     "而键还在 = isSet 仍报'用户设过'（用户以为还原了）")
    reset = kt_fn(act, "restoreDefaultParams")
    if reset is None:
        fails.append("EngineActivity 里找不到 restoreDefaultParams（判据锚点失效）")
    else:
        if 'EmbedBatchPref.clear' not in reset:
            fails.append("restoreDefaultParams 没有调 EmbedBatchPref.clear —— "
                         "用户设过批大小后点「还原默认」，键还在，跑的还是他设的值"
                         "（而设置页显示回默认了）")
        if 'EmbedCtxPref.clear' not in reset:
            fails.append("restoreDefaultParams 没有调 EmbedCtxPref.clear —— "
                         "这一格是 ctx 的乘数，没清掉时'内存为什么还这么高'藏在"
                         "一个用户以为自己没设过的值里")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print("PASS  建库循环记了两条路 + 结束时说一句（第 1 步接上了）")
    print("PASS  /health 报 6 个读数，且 HealthCheck 解析它们（对外可见，不只 App 内）")
    print("PASS  批大小从设置读进建库，且建库与检索共用同一个取值处（第 2 步接上了）")
    print("PASS  批大小进了指纹（Kotlin 与 C++ 两边），改它 = 旧索引作废重建")
    print("PASS  归一判据只有一处（EmbedBatchPref.normalize 转调 kEmbedBatchLimit）")
    print("PASS  建索引时用的批大小是读数（/health + 从索引指纹读，两个数能对账）")
    print("PASS  `g` 只差一格时说得清'能救多少'（onlyBatch + salvage，日志不再只有'从头编'）")
    print("PASS  「还原默认设置」真的清掉批大小与 ctx 两格（不是写个默认值糊过去）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
