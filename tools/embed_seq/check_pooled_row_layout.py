#!/usr/bin/env python3
"""判「合批时第 j 条的**池化向量**取自哪一行」这件事**只有一处判据**，
且那一处与单条路径**同一个 API、同一个分岔器**。

═══════════════════════════════════════════════════════════════════════
为什么单列这一条（ISSUE #269 第 3 步的**全部新增知识**都在这里）
═══════════════════════════════════════════════════════════════════════
0.9.207 及以前，`batchable_pooling` 把 MEAN/CLS/LAST 挡在合批外，理由是
"`embd_seq` 在多 seq 下的布局本仓库没验过"。这一轮把它验了，于是**取值行号**
成了唯一的新东西 —— 而它猜错的症状**全是静默的**：

  · 取错行（8 条文本共用第 0 条的向量）-> 检索像"总是命中同几条"，
    而长度、维度、归一化**全对**，接口 200，日志干净；
  · 越界取（取第 n 行，批里只有 n 行）-> `llama_get_embeddings_seq` 对越界
    **没有契约**（可能 null、可能返回别的行的"看起来能用"的指针）——
    后者比 null 更坏：它是**别人的语义**，而下游一切正常；
  · 走错 API（该 `get_embeddings_seq` 却走 `get_embeddings_ith`）-> 读
    `embd.data` 那块**从未写入**的内存（池化结果在 `result_embd_pooled` 那个
    独立张量里），拿到全零 -> 逐条 `ZERO_NORM`，看起来像"合批把好条目弄坏了"。

所以这一条钉三个结构关系（都是"谁调谁"，不是"某个标识符在不在"）：

  ① 取向量那一支**按 `read_from_seq` 分岔**（与单条路径**同一个**判据）——
     MEAN/CLS/LAST 走 `llama_get_embeddings_seq`，NONE/UNSPECIFIED 走 `ith`；
     合批里**两处**（计划里的 seq 号 / 单条路径写死的 0）都来自它。
  ② 走 seq 时的实参必须是**那条的 seq 号**（经 `pooled_vector_slot` 取），
     且必须**在调用前**过 `pooled_slot_is_row` 越界判据 ——
     顺序写反（先调库再判）等于判据只是装饰。
  ③ `get_embeddings_seq` 在合批里**不许**出现写死的 0 ——
     那是单条路径的实参（它只有一个 seq），抄过来就是"8 条共用第 0 条"。

用法：check_pooled_row_layout.py <llama_jni.cpp> <embed_text.h>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body  # noqa: E402


def main():
    jni_p, hdr_p = sys.argv[1], sys.argv[2]
    jni = Path(jni_p).read_text(encoding='utf-8', errors='replace')
    hdr = Path(hdr_p).read_text(encoding='utf-8', errors='replace')
    fails = []

    # ── ① 纯逻辑层的三个函数都在（行号 / 越界 / 分岔）──────────────────────
    for fn, why in (
        ("pooled_vector_slot", "『第 j 条读第几行』必须有判据（取错行 = 8 条共用同一段语义）"),
        ("pooled_slot_is_row", "越界下标必须先判再调库（get_embeddings_seq 对越界没有契约）"),
        ("batchable_pooling", "『这一档能不能合批』只许有一处判据"),
    ):
        if not re.search(r'inline\s+\w+\s+' + fn + r'\s*\(', hdr):
            fails.append(f"embed_text.h 里没有 {fn} —— {why}")

    # ── ② native 侧：合批取向量按 read_from_seq 分岔 ───────────────────────
    batch, line = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch")
    if batch is None:
        fails.append("找不到 nativeEmbedBatch（判据锚点失效）")
        batch = ""
    else:
        # 判据必须锚"喂给取向量循环的那个量"，而不是"文件里出现过 read_from_seq" ——
        # 合批里还有**另一处** read_from_seq（判 output 布局该不该卡）。只数出现
        # 次数时，把 `from_seq` 写死成 `false`（分岔器被绕过）照样绿：
        # 文件里那个调用还在，只是**没人用它**。
        if not re.search(r'const\s+bool\s+from_seq\s*=\s*[^;]*read_from_seq\s*\(\s*(?:E\.)?poolType\s*\)', batch):
            fails.append(
                "nativeEmbedBatch 的 from_seq 不是取自 read_from_seq(poolType) —— "
                "取向量那一支没有按档分岔（或分岔器被绕过）：MEAN/CLS/LAST 会去读 "
                "embd.data 那块从未写入的内存，全零 -> 逐条 ZERO_NORM，"
                "看起来像『合批把好条目弄坏了』")
        if not re.search(r'llama_get_embeddings_seq\s*\(', batch):
            fails.append("nativeEmbedBatch 里没有 llama_get_embeddings_seq —— 库自己池化的"
                         "那三档的向量只在 embd_seq 里，取不到它们等于这三档合批无效")
        if not re.search(r'llama_get_embeddings_ith\s*\(', batch):
            fails.append("nativeEmbedBatch 里没有 llama_get_embeddings_ith —— "
                         "NONE/UNSPECIFIED 那两档的向量只在 embd.data 里")
        # 越界判据必须在**调用之前**（同一个 for 体里，判据在前）
        seq_call = batch.find("llama_get_embeddings_seq")
        row_guard = batch.find("pooled_slot_is_row")
        if row_guard < 0:
            fails.append("nativeEmbedBatch 没有用 pooled_slot_is_row 判越界 ——"
                         "越界下标交给库时行为无契约（可能返回别的行的指针，"
                         "而下游一切正常：长度、维度、归一化全对，语义是别人的）")
        elif seq_call >= 0 and row_guard > seq_call:
            fails.append(
                "pooled_slot_is_row 出现在 llama_get_embeddings_seq 之后 —— 先调库再判"
                "等于判据只是装饰（越界那一次已经发生）")
        # ⚠ 反面：合批里不许出现写死的 seq 0（那是单条路径的实参）
        m = re.search(r'llama_get_embeddings_seq\s*\([^,]*,\s*([^)]*)\)', batch)
        if m and m.group(1).strip() in ("0", "0LL", "(llama_seq_id) 0"):
            fails.append(f"nativeEmbedBatch 里 llama_get_embeddings_seq 的实参写死成 "
                         f"{m.group(1).strip()} —— 那是单条路径的实参（它只有一个 seq）。"
                         "抄过来就是 8 条文本共用第 0 条的向量，而长度、维度全对")

    # ── ③ 合批里的 seq 号必须经 pooled_vector_slot（不是就地写 j）─────────
    if batch and "pooled_vector_slot" not in batch:
        fails.append("nativeEmbedBatch 没有经 pooled_vector_slot 取行号 ——"
                     "『第 j 条读第几行』这件事就没有落点（就地写 j 时，"
                     "将来 output 布局或 seq 分配一变，没人知道该动哪一处）")

    # ── ④ 单条路径：读 seq 时也必须按 read_from_seq 分岔（两处同一个判据）──
    single, _l = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeEmbed")
    if single is None:
        fails.append("找不到 nativeEmbed（判据锚点失效）")
    elif not re.search(r'read_from_seq\s*\(\s*poolType\s*\)', single):
        fails.append("nativeEmbed（单条路径）没有按 read_from_seq 分岔 ——"
                     "合批与单条的『读哪一路』必须是同一个判据，"
                     "抄成两份时迟早只改一处（而那是同一条文本两条路径算出不同向量）")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print("PASS  纯逻辑层有 pooled_vector_slot / pooled_slot_is_row / batchable_pooling 三个落点")
    print("PASS  nativeEmbedBatch 按 read_from_seq 分岔（seq 与 ith 两条路都在）")
    print("PASS  越界判据在调用之前，且行号经 pooled_vector_slot（没有就地写 j）")
    print("PASS  llama_get_embeddings_seq 的实参不是写死的 0（合批里那是错的）")
    print("PASS  单条路径与合批用同一个 read_from_seq 分岔器")
    return 0


if __name__ == '__main__':
    sys.exit(main())
