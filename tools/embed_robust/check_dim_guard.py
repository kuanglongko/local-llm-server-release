#!/usr/bin/env python3
"""断言 nativeEmbed 在**读向量之前**做了「输出维度对照」（方案 B 的核心）。

═══ 这一条守的是什么 ═══
`llama_get_embeddings_ith` 按 **n_embd** 步长走（头文件 `ctx->output_ids[i]*n_embd`），
而库按 **n_embd_out** 分配 output buffer —— 两者在部分模型上不相等。
不等时"读一行"是未定义行为：n_embd_out 更小就跨进别的 buffer（含未初始化的 NaN）。

症状正是用户报的：**某些条归一化失败、且时好时坏**、改改词序某次成功之后就再
不失败。失败由**那块内存之前被谁写过**决定，与文本内容无关 —— 所以它
"看起来像生僻字的问题"，而改一个字都可能是假象。

判据分三段（缺任一段都可能是恒真）：
  ① 真的调用了 `llama_model_n_embd_out`（不是只用了 n_embd）；
  ② 对照走的是纯函数 `embedtext::output_dim_ok`（不是就地写一个 `!=`，
     因为就地写的那一版**没人测**——它只在 JNI 里，宿主侧跑不到）；
  ③ 对照发生在**取 embeddings 之前**（放到之后等于白挡：已经读过脏内存了）。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body  # noqa: E402


def main():
    src = Path(sys.argv[1]).read_text(encoding='utf-8')
    body, line = fn_body(src, 'nativeEmbed')
    if body is None:
        print("FAIL  找不到 nativeEmbed 的函数体（判据锚点失效）")
        return 1

    bad = 0
    # ① 真的取了输出侧维度
    if 'llama_model_n_embd_out' not in body:
        print("FAIL  nativeEmbed 没有取 llama_model_n_embd_out —— "
              "只用 n_embd 时，两者不等的模型会跨 buffer 读（NaN，时好时坏）")
        bad += 1
    else:
        print("PASS  nativeEmbed 取了 llama_model_n_embd_out")

    # ② 对照走纯函数
    if 'embedtext::output_dim_ok' not in body:
        print("FAIL  nativeEmbed 没有用 embedtext::output_dim_ok 做对照 "
              "（就地写 != 的版本宿主侧测不到）")
        bad += 1
    else:
        print("PASS  nativeEmbed 用 embedtext::output_dim_ok 做对照")

    # ③ 对照必须在**读 embeddings 之前**
    m_ok = body.find('embedtext::output_dim_ok')
    m_read = body.find('llama_get_embeddings_ith')
    if m_ok < 0 or m_read < 0:
        print("FAIL  取不到对照点或读取点的位置（判据锚点失效）")
        bad += 1
    elif m_ok > m_read:
        print("FAIL  维度对照排在 llama_get_embeddings_ith **之后** —— "
              "那时脏内存已经读过了，等于没挡")
        bad += 1
    else:
        print(f"PASS  维度对照排在读取之前（对照 @{m_ok} < 读取 @{m_read}）")

    # ④ 不匹配时必须**拒绝继续**（不是只在日志里说一句就往下走）
    if not re.search(r'if\s*\(\s*!\s*embedtext::output_dim_ok', body):
        print("FAIL  维度不匹配时没有 `if (!output_dim_ok(...))` 的提前返回分支 —— "
              "只记一句日志就继续读，症状与不检查完全一样")
        bad += 1
    else:
        print("PASS  维度不匹配时有提前返回（拒绝继续读）")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
