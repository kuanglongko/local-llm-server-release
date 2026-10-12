#!/usr/bin/env python3
"""断言嵌入 n_ctx **被钳到上限**，而不是直接放行。

不钳的后果：`embeddings=true` 的 output buffer 随 n_ctx 线性分配，
用户按对话模型经验给 4096/8192 → 手机上 `ctx create failed`（加载失败），
而用户完全不知道是 ctx 给大了（他会以为是 GGUF 坏了）。
判据分两段：① 超过上限时**赋值回上限**；② 上限常量被真的用在比较里
（只声明不用 = 恒真，本仓库在 KV 那轮抓过同型弱断言）。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import fn_body  # noqa: E402


def main():
    src_path = sys.argv[1]
    src = Path(src_path).read_text(encoding='utf-8')
    body, line = fn_body(src, 'nativeLoadEmbedModel')
    if body is None:
        print("FAIL  找不到 nativeLoadEmbedModel 的函数体")
        return 1

    # ② 上限常量必须参与比较（`> kEmbedMaxCtx`）——
    # ⚠ 别锚左边的变量名：合批引入之后，被钳的是**总量**（`total`，
    # 由"每条预算 × 条数"推出来），而不是字面意义上的 `ctx`。
    # 锚 `= kEmbedMaxCtx` 会在正确的重构上误红，然后判据被"顺手放宽"成恒真。
    if not re.search(r'>\s*\(?\s*long\s+long\s*\)?\s*kEmbedMaxCtx\b', body) \
       and not re.search(r'>\s*kEmbedMaxCtx\b', body):
        print("FAIL  n_ctx 没有与 kEmbedMaxCtx 比较（上限只声明不用 = 没钳制）")
        return 1
    # ① 超限时必须把值改回上限（变量名不限，锚的是"被赋成上限"这件事）
    if not re.search(r'^[^\n]*=\s*kEmbedMaxCtx\s*;', body, re.M):
        print("FAIL  超限时没有把值赋回 kEmbedMaxCtx —— 比较了却没钳制")
        return 1
    # ③ 合批之后还有一条：被钳的总量必须是条数的整数倍（库对除不尽会
    #    "rounding down" 而不报错 —— 每条的预算悄悄少一格）。
    if 'kEmbedBatchRows' in body and not re.search(r'/\s*kEmbedBatchRows\s*\)\s*\*\s*kEmbedBatchRows', body):
        print("FAIL  被钳后的 ctx 没有对齐到 kEmbedBatchRows 的整数倍 ——"
              "库对 `n_ctx / n_seq_max` 除不尽时会 round down 而不报错，"
              "每条的预算悄悄少一格")
        return 1
    # 默认值也必须来自常量（防两处各写一个字面量然后漂移）
    if not re.search(r'kEmbedDefaultCtx\b', body):
        print("FAIL  nCtx<=0 时没有回落到 kEmbedDefaultCtx（默认值来源不统一）")
        return 1
    print(f"PASS  嵌入 n_ctx 有默认与上限，且超限被钳回上限（第 {line} 行起）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
