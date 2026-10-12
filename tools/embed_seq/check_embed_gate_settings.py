#!/usr/bin/env python3
"""判「嵌入 ctx 的两道门被**显式设**、且那一格**用户改得动、读数看得见**」（0.9.212）。

═══════════════════════════════════════════════════════════════════════
守的是哪三件，以及它们为什么必须**静态**守
═══════════════════════════════════════════════════════════════════════
用户在真机上撞到的是：**bge-large-zh-v1.5 合批 8 → 4 → 2 全崩、只有 1 不崩**。
0.9.211 的读数把答案写死了 —— 那一行同时打出 `n_batch=2048 n_ubatch=512`，
而 bge 是 BERT（非因果），库让它走 `llama_encode`，只看 **512** 那扇门：

  · 批大小拧的是 `n_seq_max`（= 一次收几条），**不是**这两扇门里的任何一个；
  · 一条块实测 430 token（中位数），2 条一合就 > 512 → `:1426` 断言 → release
    里 `abort()`（用户看到的"建索引闪退"）；
  · 只有 1 条时 `n_seqs == 1`，那道门退化成恒真 —— 所以"只有 1 不崩"。

根因是**配置缺了两个参数**：嵌入那条加载路径 `cp.n_batch` / `cp.n_ubatch`
**一个都没传**（只有内联的对话模型 `nativeLoadModel` 有那两个入参），
于是两个都是库默认值。而它们的失效形态是**进程级 abort**，只有真机能看到 ——
所以判据只能锚源码结构：

  ① `nativeLoadEmbedModel` 必须**显式**设 `cp.n_batch` **与** `cp.n_ubatch`，
     且同源（同一个 `gate`，不留第二个字面量）—— 只设一个时另一扇门仍然是
     库默认，而『哪一扇是默认值』没有人能一眼看出来；
  ② 两者必须被**钳到 ctx**（`min`）：请求一个比上下文还大的批，库当场拒绝建
     ctx，症状是『嵌入模型加载失败』（用户会去怀疑 GGUF 坏了，方向全错）；
  ③ 读数必须报**库的实数**（落进 `E.n_batch` / `E.n_ubatch`，且 `embed model
     ready` 打的是 `E.*`）—— 打 `cp.n_batch` 等于担保『库收下了我们给的数』，
     而那正是本仓库被打回来过两次的那种假设；
  ④ 这一格必须**用户改得动**，且**两端都接上**：
     · `EmbedCtxPref`（读/写/归一/isSet）存在，且 `normalize` **转调**
       `LlmEngine.kEmbedCtxLimit`（不自己再钳一遍）；
     · 设置页**真的建了**输入框、且 `doLoadEmbed` 把值**传给** `loadEmbedModel`
       （不传的话这一格就是第二个『线程数输入框对建库完全无效』：能填、能存、
       对加载没用）；
     · 读 View 的那一下必须排在 `Thread {` **之前**（Android 的 UI 工具包
       不是线程安全的，在后台线程读 `.text` 是未定义行为）；
  ⑤ 内存归因必须**说得出口**：native 侧要有一行量"建 ctx 造成的 RSS 差"，
     Kotlin 侧设置页要有一行把"内存高看这一格、批大小那格不影响内存"讲明白 ——
     只写"越大越慢"会把人送去拧**对内存毫无影响**的那个旋钮。

用法：check_embed_gate_settings.py <llama_jni.cpp> <LlmEngine.kt> <EngineActivity.kt> \\
                                   <EmbedCtxPref.kt> <HttpApi.kt> <HealthCheck.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body, kotlin_fn_body  # noqa: E402


def fn_span(src: str, name: str):
    """取 `name` 函数的**原文区间**（含字符串字面量），找不到返回 None。

    为什么不能复用 `_fnbody.fn_body`：它把字符串一起抹成 `""`，于是
    `LOGI("embed model ready…")` 这类判据全变成 `LOGI("")` —— 本文件的
    判据恰恰要读**日志文案里的字段名**（`E.n_batch` / `E.n_ubatch`），
    抹掉字符串后只能假红（而"假红"与"判据没跑"是一回事）。
    """
    code = src
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
    (jni_p, kt_p, act_p, pref_p, http_p, health_p) = sys.argv[1:7]
    jni = Path(jni_p).read_text(encoding='utf-8', errors='replace')
    kt = Path(kt_p).read_text(encoding='utf-8', errors='replace')
    act = Path(act_p).read_text(encoding='utf-8', errors='replace')
    pref = Path(pref_p).read_text(encoding='utf-8', errors='replace')
    http = Path(http_p).read_text(encoding='utf-8', errors='replace')
    health = Path(health_p).read_text(encoding='utf-8', errors='replace')
    fails = []

    # ── ① native：两道门都显式设，且同源 ────────────────────────────────────
    load, _l = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel")
    if load is None:
        fails.append("找不到 nativeLoadEmbedModel 的函数体（判据锚点失效）")
        load = ""
    else:
        # ⚠ 锚的是**赋值**（`cp.n_ubatch = ...`），不是"文件里出现过 n_ubatch" ——
        # 后者在注释与日志文案里到处都有，恒真。
        if not re.search(r'cp\.n_batch\s*=\s*\(uint32_t\)\s*gate\b', load):
            fails.append("nativeLoadEmbedModel 没有把 `cp.n_batch` 显式设成同一个 gate —— "
                         "不设时它是库默认 2048，而『哪一扇是默认值』没人一眼看得出来；"
                         "因果模型（Qwen3）撞的就是这一扇（`:1711`）")
        if not re.search(r'cp\.n_ubatch\s*=\s*\(uint32_t\)\s*gate\b', load):
            fails.append("nativeLoadEmbedModel 没有把 `cp.n_ubatch` 显式设成同一个 gate —— "
                         "**非因果模型（BERT 系 bge）只认这一扇**（`:1426` 的 "
                         "`encoder requires n_ubatch >= n_tokens`），真机上正是它 abort 的")
        # 两者同源：不许出现第二个字面量（两处各写一遍时迟早分岔，而分岔的报应是 abort）
        if not re.search(r'const\s+int\s+gate\s*=', load):
            fails.append("两道门没有收口到一个 `gate` —— 两处各写一个数时迟早分岔，"
                         "而 `n_batch % n_ubatch != 0` 是库自己会断言的条件")
        # 必须钳到 ctx：比上下文还大的批，库当场拒绝建 ctx
        if not re.search(r'kEmbedGate\s*<\s*ctx', load):
            fails.append("两道门没有钳到 `ctx` —— 请求一个比自己上下文还大的批时库拒绝建 ctx，"
                         "症状是『嵌入模型加载失败』，而用户完全看不出是这一格给大了")
        # 读数必须来自库
        for fn, field in (("llama_n_batch", "E.n_batch"), ("llama_n_ubatch", "E.n_ubatch")):
            if not re.search(fn + r'\s*\(\s*E\.ctx\s*\)', load):
                fails.append(f"没有读库的 `{fn}(E.ctx)` —— 读数必须是库的**实数**，"
                             "不是我们传给 cp 的值（那是『库收下了我们给的数』这个未验证的假设）")
            if not re.search(re.escape(field) + r'\s*=', load):
                fails.append(f"没有把库的实数落进 `{field}`（合批与日志都只该信这个）")
        # 加载期那一行打的是 E.*，不是 cp.*
        # ⚠ 锚的是**调用**（`LOGI("embed model ready…`），且必须限定在同一行的实参上 ——
        # 用 `fn_body` 取函数体时字符串字面量已被抹成 `""`（它只关心代码结构），
        # 所以这里改用**原文区间**：先定位 `embed model ready`，再取到那一句分号为止。
        ready = None
        span = fn_span(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel")
        if span is not None:
            ready = re.search(r'LOGI\(\s*"embed model ready[^;]*;', span, re.S)
        if not ready:
            fails.append("找不到 `embed model ready` 那一行（判据锚点失效）")
        else:
            line = ready.group(0)
            for key in ("n_batch", "n_ubatch"):
                if f"E.{key}" not in line:
                    fails.append(f"`embed model ready` 打的不是 `E.{key}`（库的实数）—— "
                                 "打 `cp.*` 等于担保库收下了我们给的数，而那正是被打回来过两次的假设")
                if re.search(r'\bcp\.' + key + r'\b', line):
                    fails.append(f"`embed model ready` 打的是 `cp.{key}` —— 必须读库的实数")

        # ── ⑤ 内存归因：native 侧要报**算得准的**量（0.9.213 改口径）────────
        # ⚠ 0.9.212 报的是"建 ctx 前后 RSS 的差"，而它量错了：基线在模型加载
        # **之后**，那 892 MB 里已含模型 mmap 的页 —— 真机读数是 `+3 MB`，
        # 对一份 4096 的 ctx 小得反常（光 output buffer 就不止 3 MB）。
        # 判据因此改成钉"报的是**理论量 + 绝对值**、且**不许**把它标成增量"。
        span_load = fn_span(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel") or ""
        # ⚠ 锚的是**那一行 jlog 调用**，不是"函数体里出现过 `[embed内存]`"——
        # 后者会被上面解释这条读数的注释命中（恒真），于是"读数被删掉"这件事
        # 永远不报红 —— 正是本仓库反复记录的那一类假绿。
        mem = re.search(r'jlog\(\s*"\[embed内存\][^;]*;', span_load, re.S)
        if not mem:
            fails.append("没有 `[embed内存]` 那一行 —— 用户看不到『这一档吃了多少』，"
                         "只能看到应用列表里那个数变高，归因不到任何一格")
        else:
            memline = mem.group(0)
            # 理论量：必须报得出 KV / output 的估算字节数（n_ctx × … ），
            # 否则这一行又变成"只有一个看不懂的 RSS 绝对值"。
            if 'kv_mb' not in span_load or 'out_mb' not in span_load:
                fails.append("`[embed内存]` 没有报 KV / output 的**理论量**（算得准的那两项）—— "
                             "只报一个 RSS 绝对值的话，用户调小这一格时看不出『该降多少』")
            if 'llama_model_n_layer' not in span_load:
                fails.append("理论量没读 `llama_model_n_layer` —— KV 的字节数按层数算，"
                             "少它就只能猜")
            # ⚠ 反面判据：不许再把它标成"本档 +N MB"。那个数在模型已 mmap 的基线下
            # **量不出**"这一档自己吃的"，而它看起来像量准了 —— 本仓库吃过这一族的亏。
            if re.search(r'本档\s*\+?%?l?d?\s*MB|本档 \+%ld MB', memline):
                fails.append("`[embed内存]` 又把 RSS 差标成『本档 +N MB』了 —— 那个数在模型"
                             "已 mmap 的基线下量不出『这一档自己吃的』（真机读出 +3 MB），"
                             "标成增量会让用户按一个假数去调")
            if '含模型 mmap' not in memline and '含.*mmap' not in memline:
                fails.append("`[embed内存]` 报的 RSS 绝对值没标注『含模型 mmap』—— "
                             "不标注时读的人会把它当成『这一档花的』（那正是 0.9.212 的错）")

    # ── ② Kotlin：这一格用户改得动，且两端都接上 ────────────────────────────
    for need, why in (
        ("fun get(", "读"),
        ("fun set(", "写"),
        ("fun normalize(", "归一"),
        ("fun isSet(", "区分『没设过』与『设了值』"),
    ):
        if need not in pref:
            fails.append(f"EmbedCtxPref 里没有 {need} —— "
                         f"这一格{why}的一侧缺失时，设置页那个框要么存不下来、要么读不回来")
    nm = re.search(r'fun\s+normalize\s*\([^)]*\)\s*:\s*Int\s*=\s*(.+)', pref)
    if nm is None:
        fails.append("EmbedCtxPref.normalize 的函数体找不到（判据锚点失效）")
    elif 'kEmbedCtxLimit' not in nm.group(1):
        fails.append("EmbedCtxPref.normalize 没有转调 LlmEngine.kEmbedCtxLimit —— "
                     "自己再钳一次时，上限与 native 的 kEmbedMaxCtx 会脱钩")
    if not re.search(r'fun\s+kEmbedCtxLimit\s*\(', kt):
        fails.append("LlmEngine 里没有 kEmbedCtxLimit（这一格没有唯一的钳处：设置页与加载"
                     "两处各钳一遍时迟早分岔）")
    for c in ("kEmbedCtxDefault", "kEmbedCtxMax"):
        if not re.search(c + r'\s*=\s*\d+', kt):
            fails.append(f"LlmEngine 里没有 {c} —— 默认值与上限必须有单一出处")
    # ⚠ 上限必须与 native 的 kEmbedMaxCtx **相等**：设置页要在加载之前就给出合法区间，
    #   而『真能被库接受多少』只有加载后才知道 —— 两个数分岔时，用户填一个『界面允许、
    #   库拒绝』的值，症状就是『嵌入模型加载失败』而没有任何线索指向这一格。
    m_kt = re.search(r'kEmbedCtxMax\s*=\s*(\d+)', kt)
    m_jni = re.search(r'kEmbedMaxCtx\s*=\s*(\d+)', jni)
    if m_kt and m_jni and m_kt.group(1) != m_jni.group(1):
        fails.append(f"Kotlin 的 kEmbedCtxMax({m_kt.group(1)}) 与 native 的 "
                     f"kEmbedMaxCtx({m_jni.group(1)}) 不等 —— 设置页会允许一个库拒绝的值")

    # ── ④ 设置页真的建了框、且 doLoadEmbed 真的把它传下去 ──────────────────
    if 'ragEmbedCtxEt' not in act:
        fails.append("EngineActivity 里没有 ragEmbedCtxEt —— 这一格在界面上不存在")
    if 'EmbedCtxPref.set(' not in act:
        fails.append("EngineActivity 没有 EmbedCtxPref.set —— 设置页那个框存不下来")
    if not re.search(r'saveRagEmbedCtx\s*\(', act):
        fails.append("EngineActivity 里没有 saveRagEmbedCtx —— 这一格的读写没有收口到一处")
    load_body, _lb = kotlin_fn_body(act, "doLoadEmbed")
    if load_body is None:
        fails.append("EngineActivity 里找不到 doLoadEmbed（判据锚点失效）")
    else:
        # ⚠ 锚『真的传了 nCtx 实参』，不是"文件里出现过 EmbedCtxPref" ——
        # 后者证明不了『加载真的用了它』，而那正是本 issue 的原始形态
        #（线程数输入框能填、能存、对建库没用）。
        if not re.search(r'loadEmbedModel\s*\([^)]*nCtx\s*=', load_body, re.S):
            fails.append("doLoadEmbed 调 loadEmbedModel 时没传 nCtx —— 设置页那个框能填、"
                         "能存，但对加载没用（与『线程数输入框对建库完全无效』同一个病）")
        i_thread = load_body.find("Thread {")
        m_read = re.search(r'val\s+\w+\s*=\s*saveRagEmbedCtx\s*\(', load_body)
        if m_read is None:
            fails.append("doLoadEmbed 没读这一格（**没有** `val x = saveRagEmbedCtx()`）——"
                         "加载会用 native 的默认值，设置页那个框等于没用")
        elif i_thread >= 0 and m_read.start() > i_thread:
            fails.append("这一格在**后台线程里**读（赋值排在 `Thread {` 之后）—— 它读的是 "
                         "View 的 `.text`，Android 的 UI 工具包不是线程安全的")
    # ── ⑤ Kotlin 侧的内存归因：设置页必须点名"内存看哪一格" ────────────────
    if not re.search(r'内存', act):
        fails.append("EngineActivity 里没有一行讲内存 —— 用户问的是『内存占用为啥高』，"
                     "而这一格正是答案所在（他此前只能看到占用升高，看不到原因）")
    if not re.search(r'不.*影响内存', act):
        fails.append("设置页没有点明『批大小那格不影响内存』 —— 只说『越大越占内存』会把人"
                     "送去拧**对内存毫无影响**的那个旋钮（批大小只决定一次塞几条）")

    # ── ⑥ 对外可见：/health 报这一格，且 HealthCheck 解析它 ────────────────
    if 'embed_ctx_per_seq' not in http:
        fails.append("/health 没有报 `embed_ctx_per_seq` —— 这一格只在 App 内可见，"
                     "远端客户端与监控读不到（与合批那六个字段同一个病）")
    if not re.search(r'embed_ctx_per_seq"\s*\)|embed_ctx_per_seq"\)', health) and \
       'embed_ctx_per_seq' not in health:
        fails.append("HealthCheck.parse 没解析 `embed_ctx_per_seq` —— 报在响应里、"
                     "探活报告里读不到（与没报等价）")

    # ── ⑦ 容量收口：`kEmbedBatchRows × 每条预算 ≤ 两道门`（0.9.213）──────────
    # ⚠ 这一节守的是**一条此前没有任何代码在管**的不变式。0.9.212 把两道门设成
    # 4096 之后，"8 条满长一次装得下"看起来成立（8 × 511 = 4088 ≤ 4096）——
    # 但它成立**只是因为** `kEmbedMaxCtx`(4096) 恰好等于 `kEmbedGate`(4096)。
    # 改任一个常量都会破，而破的形态不是报错、是 `abort()`（"建索引闪退"）。
    # 所以判据钉三件：
    #   ① 每条的预算必须由两道门**除**出来（`gatesAbs / kEmbedBatchRows`）——
    #      而不是"先取预算再乘条数再钳总量"（后者把恒等式变成事后结论）；
    #   ② 调用方给的 perSeq 必须**钳进**那个上限（这一格被填大时不会顶出容量）；
    #   ③ 这个关系必须有**读数**（`batch_full_rows_fit` + 日志里那一行）——
    #      "最多几条满长"此前只能靠读日志的人自己拿两个数去推。
    if not re.search(r'gatesAbs\s*=', load):
        fails.append("每条的预算不是由两道门**除**出来的（没有 `gatesAbs`）—— "
                     "`kEmbedBatchRows × 每条预算 ≤ 门` 此前只活在两个常量相等的巧合里"
                     "（改任一个都会让 8 条满长顶出容量，报应是 abort）")
    if not re.search(r'perSeqMax\s*=\s*gatesAbs\s*/\s*kEmbedBatchRows', load):
        fails.append("没有 `perSeqMax = gatesAbs / kEmbedBatchRows` —— 每条的预算上限必须"
                     "由门除出来，否则『8 条满长装不装得下』又变成事后才知的事")
    if not re.search(r'if\s*\(perSeq\s*>\s*perSeqMax\)\s*perSeq\s*=\s*perSeqMax', load):
        fails.append("调用方给的 perSeq **没有钳进** `perSeqMax` —— 这一格被填大时会把"
                     "整批顶出两道门，而那正是真机 abort 的形态（用户在设置页填 4096，"
                     "实际该拿到 512）")
    if not re.search(r'embedtext::batch_full_rows_fit\s*\(', load):
        fails.append("没有算『这一档最多几条满长』（`batch_full_rows_fit`）—— "
                     "这个数此前只能由读日志的人自己拿两三个数去推，"
                     "而本仓库正是靠推在 PR #285 里连续错了两轮")
    if not re.search(r'fullRowsFit\s*<\s*kEmbedBatchRows', load):
        fails.append("`fullRowsFit` 算出来了但**没有预警**（余量不足时那一行）—— "
                     "差一步就切段的处境要看得到，而不是等某天 abort")
    # ⚠ 锚的是**格式串里的那个字段**（`full_rows_fit=%d`），不是"LOGI 那一片里出现过
    # 这个词" —— 后者会被旁边解释它的注释命中（恒真）：桩把字段从格式串里删掉、
    # 只在注释里留个说明，判据照样绿。本仓库反复记录的那一类假绿。
    ready_line = re.search(r'LOGI\(\s*"embed model ready[^;]*;', span_load, re.S)
    if ready_line is None:
        fails.append("找不到 `embed model ready` 那一行（判据锚点失效）")
    elif 'full_rows_fit=%d' not in ready_line.group(0):
        fails.append("`embed model ready` 的格式串里没有 `full_rows_fit=%d` —— 这一档"
                     "『一次能装几条满长』是本 issue 的核心读数，只在某一行预警里出现不够")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print("PASS  两道门（n_batch / n_ubatch）显式设、同源、钳到 ctx，且读库的实数")
    print("PASS  这一格用户改得动：EmbedCtxPref 转调 kEmbedCtxLimit，上限与 native 相等")
    print("PASS  doLoadEmbed 真的把这一格传给 loadEmbedModel（且在主线程读 View）")
    print("PASS  内存归因说得出口：native 报 KV/output 理论量 + 标了『含 mmap』的 RSS 绝对值，"
          "设置页点名『内存看这一格』")
    print("PASS  /health 报这一格，且 HealthCheck 解析它（对外可见，不只 App 内）")
    print("PASS  容量收口：每条预算由门除出来、perSeq 钳进去，且报『最多几条满长』+ 余量预警")
    return 0


if __name__ == '__main__':
    sys.exit(main())
