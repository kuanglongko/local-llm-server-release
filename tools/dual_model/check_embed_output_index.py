#!/usr/bin/env python3
"""断言 `nativeEmbed` 读的是**第一个 output**（下标 0），而不是 `n_tokens-1`。

这是本特性里**最易写错、又最哑**的一处：
`llama_get_embeddings_ith(ctx, i)` 的 `i` 是 output 数组下标（`output_ids[i]*n_embd`），
而 output 数组只收那些 `llama_batch.logits[j] != 0` 的 token。实现只给最后一个 token
置了 `logits=1` → output 恰好 1 个元素 → 下标是 **0**。
写成 `n_tokens-1` 时，只要文本超过 1 个 token 就取到无效下标，
`llama_get_embeddings_ith` 返回 null → 检索全空，**而接口 200**。

判据分三段（缺一段就可能是恒真）：
  ① 读 output 的实参是**具名量**（不是 `n_tokens-1` / 裸 0 这类硬编码表达式）；
  ② 那个量`按语义分岔`：`SeqRow::LAST_TOKEN` 走 `plan.batch_last_slot`；
  ③ 计划里确实存在 `plan.batch_last_slot`（它保证
     `output_ids[batch_last_slot] == nTokens-1` —— 这是"读到最后一个 token"
     的**唯一**依据，也是 0.9.160 修掉的那个 bug 的判据位）。

⚠ ① 单独存在时**恒真**：`plan.output_slot` 曾经就是那个具名量，而它对 LAST
恒为 0 —— 名字对、语义错，于是整条链静默读第 0 个 token（BOS，范数 ~1e-30）。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import fn_body  # noqa: E402


def main():
    """判据已随 `plan_pooling`（池化语义交还模型）更新。

    早先的实现只给最后一个 token 置 logits，于是 output 恒为 1 个元素、下标恒为 0，
    判据就是"用 first_output_index()（= 0）而不是 n_tokens-1"。现在 CLS/MEAN 也支持了，
    "第几个 output"由 `plan_pooling` 决定，而**同一条不变式仍在**：
    `llama_get_embeddings_ith(ctx, i)` 的 `i` 是 **output 数组下标**，不是 batch 下标。
    写成 `n_tokens-1` 时多 token 文本取到无效下标 → 返回 null → 检索全空、接口 200。

    所以判据改成锚**调用实参来自计划**（而不是"某个变量等于 0"）。
    这与 `tools/embed_robust/check_pooling_wiring.py` 同源，但保留在这里是因为
    本文件属于"双常驻"那一份，历史上专门守过这个坑，删掉会让那条教训无处安放。

    ⚠ 0.9.160 又在这条路上栽了一次，形态更隐蔽：实参**确实**叫
    `plan.output_slot`，而 `plan.output_slot` 对 `LAST` 档是"output 数组里的
    排位 0" —— 它只在"我们恰好只要了一行 output"时等于最后一行。库给的 output
    一变多，`output_ids[0]` 就成了第 0 个 token（BOS），向量量级 1e-30。
    于是判据从"看名字"升到"看语义来源"：`plan.batch_last_slot` + `plan.row` 分岔。
    """
    src = Path(sys.argv[1]).read_text(encoding='utf-8')
    body, line = fn_body(src, 'nativeEmbed')
    if body is None:
        print("FAIL  找不到 nativeEmbed 的函数体（判据锚点失效）")
        return 1
    if re.search(r'last_token_index', body):
        print("FAIL  nativeEmbed 里出现了 last_token_index —— 那是 batch 下标，"
              "不能当 output 下标用（两者在多 token 时不同）")
        return 1
    reads = re.findall(r'llama_get_embeddings_ith\s*\(\s*[A-Za-z_][A-Za-z0-9_.]*\s*,\s*([^)]+)\)', body)
    reads = [r.strip() for r in reads]
    if not reads:
        print("FAIL  nativeEmbed 里没有 llama_get_embeddings_ith(ctx, ...) 调用")
        return 1
    # 非 MEAN 分支必须用计划给的**排位**；MEAN 分支走库的 `get_embeddings_seq`。
    # ⚠ 锚**调用实参**而不是"函数体里出现过 plan.output_slot"—— 后者恒真
    # （报错日志那一行也传它）。本仓库踩过这个坑（见 check_pooling_wiring.py 注释）。
    #
    # ⚠⚠ 但"实参叫什么名字"**仍然不够** —— 这是本轮的真教训：
    # 早先的实参就是 `plan.output_slot`，而 `plan.output_slot` 对 LAST 档恒为 0。
    # 它在"我们只给最后一位置了 logits"的前提下**恰好**等于最后一行，所以看起来对；
    # 一旦库给的 output 不止一项（CLS/MEAN 整段），`output_ids[0]` 就变成
    # **第 0 个 token** —— 生成式 decoder 里是 BOS（无前文，量级 ~1e-30），
    # 归一化必然拒绝。所以判据必须同时锚**这个名字的语义来源**：
    # 它得由 `plan.row` 分岔出"读最后一个 token 的排位"。
    #
    # 本文件是历史上第一次踩这个坑的地方，教训留在这里最合适。
    bad = [r for r in reads if not (re.fullmatch(r'[A-Za-z_]\w*', r) or r == 'i')]
    if bad:
        print(f"FAIL  llama_get_embeddings_ith 的实参里有硬编码表达式 {bad} —— "
              "output 排位必须是具名量，写成 n_tokens-1 / 0 会在一部分 pooling 上"
              "读到别的行（接口照常 200）")
        return 1
    if not re.search(r'plan\s*\.\s*batch_last_slot', body):
        print("FAIL  nativeEmbed 没有用 plan.batch_last_slot —— "
              "LAST 语义（读最后一个 token）必须由这个字段表达，"
              "它保证 `output_ids[batch_last_slot] == nTokens-1`；"
              "只写 plan.output_slot 时，两个语义共用一个数值，"
              "把 0 当最后一位（生成式 decoder 里那是 BOS，范数 ~1e-30）")
        return 1
    if not re.search(r'plan\s*\.\s*row\s*==\s*embedtext\s*::\s*SeqRow\s*::\s*LAST_TOKEN', body):
        print("FAIL  nativeEmbed 没有按 `plan.row` 分岔读哪一行 —— "
              "「读最后一个 token」与「去 output 数组的第几项」是两个语义，"
              "不分岔就只能靠「它们恰好数值相同」这个别处决定的前提")
        return 1
    print(f"PASS  读 output 的排位来自计划且按语义分岔（实参 {reads}，第 {line} 行起）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
