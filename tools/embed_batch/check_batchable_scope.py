#!/usr/bin/env python3
"""判「合批的适用档」与「读哪一路 buffer」是**同一个**判据（不许抄成两份）。

═══════════════════════════════════════════════════════════════════════
为什么单列一条（以及 0.9.208 为什么**换了方向**）
═══════════════════════════════════════════════════════════════════════
`library_gives_seq(t)` 回答的是"库自己池化吗"（MEAN/CLS/LAST(3) 为真）。
`read_from_seq(t)` 是它在**读取侧**的调用入口（拿 pooled buffer 还是逐 token）。

0.9.207 及以前，`batchable_pooling` 是它的**反面**（`!library_gives_seq`）——
理由是"库自己池化的档读 embd_seq，那份 buffer 在多 seq 下的布局没验过，
所以那三档不合批"。那是当时的诚实，代价是 **BGE（CLS）与 Qwen3-Embedding（LAST）
一条都没合批上**：用户手上两个模型都只有"线程数"那一半收益。

0.9.208 把"没验过"补成了"验过"：`embd_seq` 在多 seq 下就是**每个 seq 一行**，
第 j 条读 `llama_get_embeddings_seq(ctx, j)` —— 与单条路径（实参写死 0）
是同一个 API。于是 `batchable_pooling` **不再排除任何一档**。

⚠ 换了方向之后，这条判据钉的东西**没变**（还是"只有一份档位清单"），
但钉的**关系**变了 —— 而这一条恰恰是换方向时最容易漏的地方：

  ① `batchable_pooling` 与 `read_from_seq` 必须**同源**（都走 `library_gives_seq`）。
     现在它不再反问（`!library_gives_seq`），但它**仍然**由那一个函数分岔
     （唯一被拒的是 `plan_pooling` 不认的档）。若它自己列一份清单
     （`t == 0 || t == -1`），下次 `library_gives_seq` 改口径时它不会跟着动 ——
     而症状是"读取侧说走 seq、合批侧按另一份清单排布"，两者都不报错。

  ② `read_from_seq` 仍然必须走 `library_gives_seq` —— 合批引入多 seq 之后，
     读取侧的那次分岔是**取哪一行**的根据（取错行 = 8 条文本共用第 0 条的语义，
     而长度、维度、归一化全对）。

  ③ `library_gives_seq` 的放行清单必须仍是**三档**（MEAN/CLS/LAST），
     并与 `library_computes_pooling` 对同一个档说同样的话。
     少一档时读取侧与合批侧**一起错**：LAST 漏掉过一次（0.9.163 才补），
     那次的症状是 Qwen3-Embedding 条条 `范数=0`，而 BGE 照常能用 ——
     看起来像"这个模型不行".

用法：check_batchable_scope.py <embed_text.h>
"""


import re
import sys
from pathlib import Path


def body_of(src: str, name: str):
    m = re.search(r'(?:inline\s+)?bool\s+' + re.escape(name) + r'\s*\(int poolTypeRaw\)\s*\{', src)
    if not m:
        return None
    i = src.find('{', m.end() - 1)
    depth = 0
    for k in range(i, len(src)):
        if src[k] == '{':
            depth += 1
        elif src[k] == '}':
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
    return None


def main():
    hdr = Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace')
    fails = []
    b = body_of(hdr, 'batchable_pooling')
    if b is None:
        print("FAIL  embed_text.h 里找不到 batchable_pooling 的函数体（判据锚点失效）")
        return 1
    # ⚠⚠ 0.9.208：本函数**不再**反问 `library_gives_seq`（三档现在也能合批），
    # 但它必须仍然**由 `plan_pooling` 分岔** —— 那是"哪些档我们根本算不出向量"
    # 的唯一清单。自己列一份 `t == 0 || t == -1` 就是第二份清单，
    # 而 `plan_pooling` 加档/改档时它不会跟着动（分岔后"读取侧说走 seq、
    # 合批侧按另一份清单排布"，两者都不报错）。
    if 'plan_pooling' not in b:
        fails.append("batchable_pooling 没有走 plan_pooling ——"
                     "它自己列一份档位清单时，与读取侧的分岔不会再被发现")
    # 不许出现自己硬编码的档位比较（`== 1` / `== 2` / `== 3` 之类）——
    # 无论方向怎么换，档位清单永远只该有一份。
    if re.search(r'poolTypeRaw\s*==\s*-?\d', b):
        fails.append("batchable_pooling 里直接比了档位字面量 ——"
                     "档位清单只该有一份（plan_pooling / library_gives_seq）")
    r = body_of(hdr, 'read_from_seq')
    if r is None:
        fails.append("找不到 read_from_seq（判据锚点失效）")
    elif 'library_gives_seq' not in r:
        fails.append("read_from_seq 没有走 library_gives_seq ——"
                     "batchable_pooling 与 read_from_seq 两份档位清单不同源（迟早只改一处，被合批的那一档读错 buffer）")
    # `library_gives_seq` 的**放行清单**必须与"库自己池化"那三档一致 ——
    # 它是 MEAN(1) / CLS(2) / LAST(3) **三档**。少一档时读取侧与合批侧**一起错**：
    # 合批那一侧是按补集算的（`batchable_pooling = !library_gives_seq`），
    # 把 CLS 从清单里划掉 = **从合批里放行 CLS**，而 CLS 的向量取自 `embd_seq`
    # （多 seq 下的布局本仓库没验过）。症状是向量全零 / 串味，
    # 而长度、维度、归一化**全对**；且只在换模型时出现，看起来像"这个模型不行"。
    #
    # ⚠ 锚"**两条定义各自成立**"（`library_computes_pooling` 说 MEAN ∈ 库池化，
    # `library_gives_seq` 就必须把 MEAN 也放进去；CLS 是 CLS 的定义），
    # 而不是"文件里出现过 1/2/3 三个数字" —— 后者在放行清单被改歪时照样绿。
    lg = body_of(hdr, 'library_gives_seq')
    lc = body_of(hdr, 'library_computes_pooling')
    if lg is None:
        fails.append("找不到 library_gives_seq（判据锚点失效）")
    else:
        # ⚠ LAST(3) 也在清单里，而且**最容易被漏**：它是 0.9.163 才补进来的第三档
        # （前两档修完后 Qwen3-Embedding 照样条条 `范数=0`）。漏掉它的后果与
        # "少一档"同型：`batchable_pooling` 按补集把它们放行去合批 ——
        # 而它们的向量取自 `embd_seq`。
        # CLS(2) 单列一条的理由与它一致（BGE 系就是 CLS）。
        for need, why in (("== 1", "MEAN(1)"), ("== 2", "CLS(2)"), ("== 3", "LAST(3)")):
            if not re.search(re.escape(need) + r'\b', lg):
                fails.append(f"library_gives_seq 的放行清单里少了 {why} ——"
                             "它是**三档**（MEAN/CLS/LAST）：少一档时读取侧与合批侧一起错"
                             "（合批按补集算，少掉的档会被当成『库不池化』而合批，"
                             "而它的向量取自 embd_seq）")
        # ⚠ 与 `library_computes_pooling` 的**同源**：那个函数是"MEAN 归库"的
        # 唯一定义，而 `library_gives_seq` 必须把它也收进放行清单 ——
        # 两个函数对同一个档说不同的话时，读取侧（走 seq）与合批侧（按补集）
        # 会各自按自己那份清单走，而**两条路都不报错**。
        if lc is not None:
            for lit, why in (("== 1", "MEAN(1)"), ("== 2", "CLS(2)"), ("== 3", "LAST(3)")):
                if re.search(re.escape(lit) + r'\b', lc) and not re.search(re.escape(lit) + r'\b', lg):
                    fails.append(f"library_computes_pooling 把 {why} 当作『库自己池化』，"
                                 "而 library_gives_seq 没把它算进『库给 seq』——"
                                 "同一件事的两处判据分家了（读取侧与合批侧会各按一份走）")
    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print("PASS  batchable_pooling 由 plan_pooling 分岔（只拒我们不认的档），没有第二份档位清单")
    print("PASS  read_from_seq 仍走 library_gives_seq（合批里取哪一行由它决定）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
