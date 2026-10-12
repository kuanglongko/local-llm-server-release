#!/usr/bin/env python3
"""判「嵌入合批」这一条路**从头到尾是通的**，且口径与逐条**逐项相同**（ISSUE #269 的另一半）。

═══════════════════════════════════════════════════════════════════════
为什么必须有这一条
═══════════════════════════════════════════════════════════════════════
PR #273 把"线程数按本机核数"拿回来了（多核那一份算力），而它在自己的
"放弃方案"里**明确没走**另一半：

    改 native 做多条目 batch：嵌入的每条是独立前向（每条前都要清 KV），
    合批要先证明"条目间语义不串"这件事在库那一层成立 —— 那是另一轮的量。

那一轮的答案现在落地了：隔离靠 `llama_batch` 的 `seq_id`（库按 seq 分块建
注意力掩码），每条的 `pos` 从 0 起。**这一份守卫守的就是那个答案有没有被
写歪** —— 而这一族错全是静默的：

  · 忘了清**所有** seq 的 KV（只清 seq 0）：第二排之后的条目复用到上一批的
    KV，向量里混进上一批的语义，接口 200；
  · `pos` 写成整批的全局下标（不是 seq 内位置）：RoPE 一转，向量变成"另一条
    文本在另一段的语义"，而长度 / 维度 / 归一化**全对**；
  · 合批这一层偷偷放宽了失败口径（"整批不可用"被当成"这批全失败"）：
    用户看到进度一批一批地倒退，而真相只是批大小或模型档位；
  · `kEmbedBatch`（Kotlin 侧批大小）与 `kEmbedBatchRows`（native 侧
    `n_seq_max`）不相等：每批都在 native 那儿被拒、回落逐条 —— 合批白做，
    而日志里只有一行 `Unavailable`，界面上完全看不出来。

判据锚**结构关系**（谁读谁、哪个数组取自哪个计划），不锚"出现过某个词"。

用法：check_embed_batch_wiring.py <llama_jni.cpp> <LlmEngine.kt> <embed_text.h>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body, kotlin_fn_body  # noqa: E402


def main():
    jni_p, kt_p, h_p = sys.argv[1], sys.argv[2], sys.argv[3]
    jni = Path(jni_p).read_text(encoding='utf-8', errors='replace')
    kt = Path(kt_p).read_text(encoding='utf-8', errors='replace')
    hdr = Path(h_p).read_text(encoding='utf-8', errors='replace')
    # 纯逻辑层的常量出处（与 LlmEngine.kt 同一个目录）。缺失时按空串处理 ——
    # 那样 `_num` 会退回到 LlmEngine.kt，老版本（常量还在引擎里）照样判得动。
    limits_p = Path(kt_p).parent / "EmbedLimits.kt"
    limits = limits_p.read_text(encoding='utf-8', errors='replace') \
        if limits_p.exists() else ""
    fails = []

    # ── 判据 1：native 真的导出了合批前向，且它在用纯函数计划 ──────────────
    body, line = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch")
    if body is None:
        fails.append("llama_jni.cpp 里找不到 nativeEmbedBatch（合批没有入口 = 这一半没做）")
        body = ""
    else:
        # 计划必须来自纯函数 `plan_batch`（不是 native 自己临时拼 seq/pos）——
        # "判据在别处各写一遍"正是这一族错的成因。
        if not re.search(r'embedtext::plan_batch\s*\(', body):
            fails.append("nativeEmbedBatch 没用 embedtext::plan_batch（合批计划不在纯函数里，"
                         "判据就落不到宿主单测上）")
        # 填 batch 的 seq/pos/logits 必须**取自计划**，不许在 native 里另算 ——
        # 另算一处就会出现"计划说 A、实际填 B"，而计划的自检照样全绿。
        for arr, what in (("plan.seq_of", "seq_id"), ("plan.pos_of", "pos"), ("plan.want_logits", "logits")):
            if not re.search(re.escape(arr) + r'\s*\[', body):
                fails.append(f"nativeEmbedBatch 填 {what} 时没取自计划（{arr}）——"
                             "计划与实填两处各写一遍，自检会绿而值是错的")
        # 自检必须在 decode **之前**（违反库的 logits 契约时后果是整批失败/崩溃）
        i_chk = body.find("batch_is_valid")
        i_dec = body.find("llama_decode")
        if i_chk < 0:
            fails.append("nativeEmbedBatch 没调 embedtext::batch_is_valid（填 batch 之前不自检）")
        elif i_dec >= 0 and i_chk > i_dec:
            fails.append("nativeEmbedBatch 的 batch_is_valid 排在 llama_decode **之后**"
                         "（自检要在提交给库之前）")
        # KV 必须**清所有 seq**，不能只清 seq 0。
        # 判据锚"清的时候用的 seq 实参是**循环变量**" —— 只清 seq 0 时实参是字面量 0，
        # 而它同样能通过"出现过 llama_memory_seq_rm"这种存在性断言（本仓库吃过那亏）。
        # 取所有 `llama_memory_seq_rm(...)` 调用的第一个实参，要求其中**至少一个**
        # 是 `j`（循环变量）而不是字面量。
        # 取 `llama_memory_seq_rm(<mem>, <seq>, ...)` 的**第二个**实参（第一个是
        # memory 句柄）—— 按括号配对取，免得被 `llama_get_memory(E.ctx)` 里的
        # 逗号/括号切歪（切歪的判据会在别人身上跑）。
        rm_seqs = []
        for mm in re.finditer(r'llama_memory_seq_rm\s*\(', body):
            k = mm.end() - 1
            depth = 0
            args, cur = [], ''
            while k < len(body):
                ch = body[k]
                if ch == '(':
                    depth += 1
                    if depth > 1: cur += ch
                elif ch == ')':
                    depth -= 1
                    if depth == 0:
                        args.append(cur); break
                    cur += ch
                elif ch == ',' and depth == 1:
                    args.append(cur); cur = ''
                else:
                    cur += ch
                k += 1
            if len(args) >= 2:
                rm_seqs.append(args[1].strip())
        if not rm_seqs:
            fails.append("nativeEmbedBatch 没有清 KV（上一批的 KV 会被这一批看见）")
        elif not any(re.fullmatch(r'\(\s*llama_seq_id\s*\)\s*j', a) or a == 'j' for a in rm_seqs):
            fails.append("nativeEmbedBatch 清 KV 时没有遍历所有 seq（seq 实参不是循环变量）——"
                         "只清 seq 0 会让后面几条复用上一批的 KV（向量混味，接口 200）")
        if not re.search(r'E\.seq_max', body):
            fails.append("nativeEmbedBatch 没读 E.seq_max（批上限必须来自 ctx 的**实际**容量，"
                         "不是写死的常量）")
        # 批内截断也必须按**每条预算**（n_ctx_seq），不是 ctx 总量
        if re.search(r'E\.n_ctx\b', body):
            fails.append("nativeEmbedBatch 里用了 E.n_ctx（总量）——截断与"
                         "「每条能不能装下」只该看 E.n_ctx_seq（每条的预算）")
        # 返回长度必须是 `条数 * nEmbd`（**包括**失败条目）：按 live 条数返回会让
        # 调用方与原下标整体错位 —— 而错位不报错。
        if not re.search(r'NewFloatArray\s*\(\s*nRows\s*\*\s*nEmbd\s*\)', body):
            fails.append("nativeEmbedBatch 的返回数组长度不是 `nRows * nEmbd`"
                         "（按参与批的条数返回会让调用方按原下标对齐时整体错位）")

    # ── 判据 1b：单条路径的截断点也必须是"每条的预算" ──────────────────────
    # ⚠ 名字**不带**尾括号（`fn_body` 自己会补 `\s*\(`）：带上就成了 `(\(`
    # 的双重括号，锚点恒不命中 —— 而那时判据静默不跑、守卫照样全绿。
    single, _l1b = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeEmbed")
    if single:
        if re.search(r'E\.n_ctx\b', single):
            fails.append("单条 nativeEmbed 里用了 E.n_ctx（总量）——合批之后"
                         "截断点必须看 E.n_ctx_seq，否则 65~512 token 的文本会"
                         "带着越界的 pos 上库")

    # ── 判据 2：`n_seq_max` 与批上限同源 ────────────────────────────────────
    load, _l2 = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel")
    if load is None:
        fails.append("找不到 nativeLoadEmbedModel（判据锚点失效）")
    else:
        if not re.search(r'cp\.n_seq_max\s*=\s*\(uint32_t\)\s*kEmbedBatchRows', load):
            fails.append("建嵌入 ctx 时 n_seq_max 没写 kEmbedBatchRows ——"
                         "seq_id >= n_seq_max 时库的行为是未定义（不是崩，是向量不对）")
        if not re.search(r'E\.seq_max\s*=\s*kEmbedBatchRows', load):
            fails.append("加载时没把批上限记进 E.seq_max（合批只能信 ctx 的**实际**容量）")
        # ⚠⚠ 本轮真踩到的那条：库里 `n_ctx_seq = n_ctx / n_seq_max`，是**每条**
        # 文本能用的位置数。把 n_seq_max 提到 8 而不放大 n_ctx 时，每条的预算
        # 掉到 64 —— 而截断判据还按 n_ctx（512）截，于是 65~512 token 的文本
        # 带着越界的 pos 上库（`sequence positions are not continuous` 或静默算错）。
        # 所以：总量必须由"每条预算 × 条数"推出，且截断只看每条的预算。
        # 锚"每条预算 **乘以** 条数"这个结构（不是"出现过 kEmbedBatchRows"——
        # 那个标识符在 n_seq_max 那一行也有，裸锚它会让本判据恒真）。
        if not re.search(r'perSeq\s*\*\s*\(\s*long\s+long\s*\)\s*kEmbedBatchRows', load) \
           and not re.search(r'perSeq\s*\*\s*kEmbedBatchRows', load):
            fails.append("建 ctx 时 n_ctx 没有按 kEmbedBatchRows 放大（每条预算 × 条数）——"
                         "库里 n_ctx_seq = n_ctx/n_seq_max，不放大时每条的预算被"
                         "除以条数（单条路径的 pos 会越界）")
        if 'n_ctx_seq' not in load:
            fails.append("加载时没有取/存每条的预算（n_ctx_seq）——"
                         "截断点必须跟库的每条预算走，不能拿 n_ctx 总量当它")
        if not re.search(r'llama_n_ctx_seq\s*\(', load):
            fails.append("没有用 llama_n_ctx_seq 取库算出的每条预算（自己除的那个"
                         "可能与库的钳制分岔，而赌输的表现是静默算错）")
        if not re.search(r'E\.n_ctx_seq\s*=', load):
            fails.append("没把每条预算记进 E.n_ctx_seq（截断判据读它）")

    # ── 判据 3：Kotlin 侧批大小与 native 相等（同一个数）────────────────────
    # ⚠ ISSUE #269 第 2 步起，批大小从**常量**提成了参数：
    #   · `kEmbedBatchDefault`（Kotlin）与 `kEmbedBatchRows`（native）必须相等
    #     —— 它们是"默认跑多少"与"建 ctx 时的 n_seq_max"，不等时默认场景就在回落；
    #   · 真正跑的那个数必须经 `kEmbedBatchLimit` 钳到 `kEmbedBatchRows`，
    #     否则用户填 16 会———每批都被 native 拒（而界面上只有一行日志）。
    # ⚠ 这两个数的**出处**自 0.9.229 起搬到 `EmbedLimits.kt`（纯逻辑层）——
    #   原因是它们住在 `LlmEngine.kt`（依赖 Android）时，宿主单测编不到，
    #   于是 `run_embed_parity_tests.sh` / `run_health_tests.sh` **编不过、
    #   一次都没跑过**，而输出的是"SKIP（缺工具链）"。见
    #   `tools/run_kt_test_runner_guard.py` 的文件头。
    #   所以判据改成：**先在 EmbedLimits.kt 里找那个数，再回退到 LlmEngine.kt**
    #   （`LlmEngine` 现在写的是 `= EmbedLimits.kEmbedBatchDefault`，这是**引用**
    #   而不是数字 —— 只认数字的老判据会在搬家的当天恒红）。
    def _num(name):
        """取这个常量的**数值**：EmbedLimits.kt 优先，其次 LlmEngine.kt。"""
        for src in (limits, kt):
            m = re.search(re.escape(name) + r'\s*=\s*(\d+)', src)
            if m:
                return m
        return None

    m_kt = _num('kEmbedBatchDefault')
    m_kt_rows = _num('kEmbedBatchRows')
    m_ni = re.search(r'kEmbedBatchRows\s*=\s*(\d+)', jni)
    if not m_kt:
        fails.append("找不到 kEmbedBatchDefault（批大小没有命名，散在循环里会漂）——"
                     "EmbedLimits.kt 与 LlmEngine.kt 里都没有它的数值")
    elif not m_ni:
        fails.append("llama_jni.cpp 里找不到 kEmbedBatchRows（判据锚点失效）")
    elif m_kt.group(1) != m_ni.group(1):
        fails.append(f"两处默认批大小不相等（Kotlin {m_kt.group(1)} vs native {m_ni.group(1)}）——"
                     "不等时每批都在 native 被拒、回落逐条，合批白做而界面看不出来")
    # Kotlin 自己也得知道上限（设置页要用它限制输入）—— 两个常量在 Kotlin
    # 里也必须相等（否则设置页允许的上限与实际能跑的不一致）。
    if not m_kt_rows:
        fails.append("找不到 kEmbedBatchRows 的数值（设置页限制输入时没有上限可用）")
    elif m_ni and m_kt_rows.group(1) != m_ni.group(1):
        fails.append(f"Kotlin 自己的 kEmbedBatchRows({m_kt_rows.group(1)}) 与 native 的({m_ni.group(1)}) 不等 ——"
                     "设置页允许的上限与实际能跑的不是同一个数")
    # 钳判据必须存在且转调那一个函数（不各自 coerceIn）
    if not re.search(r'fun\s+kEmbedBatchLimit\s*\(', kt):
        fails.append("LlmEngine.kt 里没有 kEmbedBatchLimit（批大小没有唯一的钳处："
                     "设置页与建库两处各钳一遍时迟早分岔）")
    else:
        m_lim = re.search(r'fun\s+kEmbedBatchLimit\s*\([^)]*\)\s*[:\w\s]*=\s*(.*?)(?:\n\s*\n|\n\s*/\*)', kt, re.S)
        lim_body = m_lim.group(1) if m_lim else ''
        if 'kEmbedBatchRows' not in lim_body:
            fails.append("kEmbedBatchLimit 没有钳到 kEmbedBatchRows（上限与 native 的 n_seq_max 脱钩）")

    # ── 判据 4：Kotlin 真的走合批，且失败口径与逐条**同一段落盘** ────────────
    bi, _l4 = kotlin_fn_body(kt, "buildIndex")
    if bi is None:
        fails.append("LlmEngine.kt 里找不到 buildIndex（判据锚点失效）")
    else:
        if not re.search(r'embedBatch\s*\(', bi):
            fails.append("buildIndex 没调 embedBatch —— 合批没接进建库主路"
                         "（入口在、没人走，与没有等价）")
        # 切片必须用**参数**（`batch`）而不是写死的常量 ——
        # 否则 "合批可调"这件事只到了设置页、没到建库。
        if not re.search(r'val\s+end\s*=\s*minOf\s*\(\s*i\s*\+\s*batch\b', bi):
            fails.append("buildIndex 切片没用参数 batch（批大小还是写死的）——"
                         "设置页改了也不会影响建库")
        if not re.search(r'kEmbedBatchLimit\s*\(\s*embedBatchSize\s*\)', bi):
            fails.append("buildIndex 没把传入的 embedBatchSize 过 kEmbedBatchLimit"
                         "（超上限时会静默地每批被拒）")
        # 回落分支必须存在（Unavailable 时要走逐条），否则整批不可用时条目会凭空消失
        if "BatchEmbed.Unavailable" not in bi:
            fails.append("buildIndex 没有处理 BatchEmbed.Unavailable（整批不可用时"
                         "必须回落逐条，否则那几条的正文会凭空消失）")
        # 失败记账必须只有一处（合批与回落共用）：`failedIdx.add` 只该出现在
        # 那个共用的 for 里。两处各写一遍 = 迟早只改一处，两个入口数字不同。
        n_add = bi.count("failedIdx.add")
        if n_add != 1:
            fails.append(f"buildIndex 里 failedIdx.add 出现 {n_add} 处（应为 1）——"
                         "合批与回落必须共用同一段失败记账，否则批大小会改变『哪几条失败』")

    # ── 判据 4b：合批这条扁平数组也要有"全零 = 失败"那道闸 ──────────────────
    # 单条路径的闸是 native 返回 null；合批返回的是扁平数组，**没有 null 这个
    # 位置**，失败条目只能体现为全零。只看"native 报的下标"不够 ——
    # `parseBatchFailed` 解析失败时它整段作废成空集，那些全零就会被当成
    # 合法向量收进索引（与任何查询的余弦都是 0/0=NaN，排序未定义）。
    eb, _l4b = kotlin_fn_body(kt, "embedBatch")
    if eb is None:
        fails.append("LlmEngine.kt 里找不到 embedBatch（判据锚点失效）")
    else:
        if not re.search(r'isAllZero\s*\(', eb):
            fails.append("embedBatch 没有对『全零向量』判失败 —— 合批这条扁平数组"
                         "里失败条目就是全零，只看 native 报的下标时，解析失败"
                         "会让全零被当成合法向量收进索引（余弦 0/0=NaN）")
        if not re.search(r'failed\.contains\s*\(\s*i\s*\)', eb):
            fails.append("embedBatch 没有按 native 报的失败下标过滤（失败详情白取了）")

    # ── 判据 5：纯逻辑层里的两条关键判据真的存在且被用到 ────────────────────
    for need in ("batchable_pooling", "plan_batch", "batch_is_valid", "batch_output_layout_ok"):
        if not re.search(r'inline\s+bool\s+' + need + r'\s*\(|inline\s+BatchPlan\s+' + need + r'\s*\(', hdr):
            fails.append(f"embed_text.h 里没有 {need}（合批的判据必须在纯逻辑层，宿主可单测）")
    # `batchable_pooling` 必须**由 `plan_pooling` 分岔**（0.9.208 起它不再反问
    # `library_gives_seq`：三档现在也能合批，理由见 `embed_text.h` 那段）。
    # 判据仍然是"档位清单只许有一份"——换方向不该换这一条。
    bp = re.search(r'inline bool batchable_pooling\(int poolTypeRaw\)\s*\{(.*?)\}', hdr, re.S)
    if not bp:
        fails.append("embed_text.h 里找不到 batchable_pooling 的函数体")
    elif "plan_pooling" not in bp.group(1):
        fails.append("batchable_pooling 没有走 plan_pooling ——"
                     "它自己手抄一份档位清单时，与读取侧的分岔不会再被发现")
    # 池化向量的**取值行号**判据也必须在纯逻辑层（合批里第 j 条读 embd_seq 的第几行）
    if not re.search(r'inline\s+int\s+pooled_vector_slot\s*\(', hdr):
        fails.append("embed_text.h 里没有 pooled_vector_slot —— 合批读 embd_seq 时"
                     "『第 j 条读第几行』必须有判据（取错行 = 8 条共用同一段语义，"
                     "而长度、维度、归一化全对）")
    if not re.search(r'inline\s+bool\s+pooled_slot_is_row\s*\(', hdr):
        fails.append("embed_text.h 里没有 pooled_slot_is_row —— 越界下标必须先判再调库"
                     "（get_embeddings_seq 对越界没有契约）")
    # 反面判据：不许再退回"只放行库不池化的档"
    if bp and re.search(r'!\s*library_gives_seq', bp.group(1)):
        fails.append("batchable_pooling 又退回了『库自己池化的档不合批』——"
                     "BGE（CLS）/ Qwen3-Embedding（LAST）会一条都合批不上")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print(f"PASS  nativeEmbedBatch 用纯函数计划 + 填 batch 取自计划 + decode 前自检（第 {line} 行起）")
    print("PASS  n_seq_max / E.seq_max / kEmbedBatchRows 三处同源")
    print(f"PASS  Kotlin 默认批大小与 native 相等（{m_kt.group(1) if m_kt else '?'}），"
          f"上限由 kEmbedBatchLimit 统一钳（≤{m_ni.group(1) if m_ni else '?'}）")
    print("PASS  buildIndex 走合批 + 回落逐条 + 失败记账只有一处")
    print("PASS  embed_text.h 的合批判据齐备，batchable_pooling 由 plan_pooling 分岔，"
          "且池化取值行号有判据（pooled_vector_slot / pooled_slot_is_row）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
