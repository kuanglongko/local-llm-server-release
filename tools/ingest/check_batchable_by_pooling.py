#!/usr/bin/env python3
"""判「建库能不能合批」这个问题的答案**只有一处**（= 判据吃模型声明的池化）。

═══════════════════════════════════════════════════════════════════════
为什么单列这一条
═══════════════════════════════════════════════════════════════════════
`embed_text.h::batchable_pooling(poolType)` 是这一族里**唯一**能回答
"这个模型建库能不能合批"的地方。

⚠⚠ 0.9.207 及以前它放行的只有 `NONE(0)` / `UNSPECIFIED(-1)` ——
**库自己池化的三档（MEAN 1 / CLS 2 / LAST 3）全部回落逐条**，
于是 `bge-small-zh-v1.5-gguf`（GGUF 写 `pooling=2` CLS）与
`Qwen3-Embedding-0.6B`（`pooling=3` LAST，本仓库实测那一份）**一条都合批不上**。

0.9.208 把这件事的**前因**（"`embd_seq` 在多 seq 下的布局没验过"）补成了
"验过"：那份 buffer 就是**每个 seq 一行**，第 j 条读
`llama_get_embeddings_seq(ctx, j)`（单条路径实参写死 0 是因为它只有一个 seq，
不是因为只有第 0 行有数据）。于是**所有 `plan_pooling` 认的档都能合批**。

这条判据的口径**没变**（"能不能合批"这个问题只许有一处答案），
变的只是那个答案的**取值** —— 所以下面第 ④ 条也随之改了：

于是这条链上最危险的写法不是"没合批"，而是**把档位清单抄成两份**：
`loadEmbedModel` 那边（或 `buildIndex`、或写 `meta` 的那一处）自己再判一次
"这个模型的 pooling 是多少"。判据一分成两份，迟早只改一份 —— 而分岔的表现是：

  · 走的路径 A、报的档位说 B（向量按逐条算的，`meta.pooling` 却写着"已合批"）；
  · 或者反过来：把不支持合批的档塞进合批（向量全零 / 串味，而**长度、维度全对**）。

两者都**不报错**，且只在换模型时出现（同一个 App，换成 BGE 就变了），
看起来像"这个模型不行"。所以这里正面钉住：

  ① `nativeLoadEmbedModel` 里 `E.pool_type` 取自**库自己**（`llama_pooling_type`）；
  ② `nativeEmbedBatch` 里那个"能不能合批"的问句**来自** `batchable_pooling(E.pool_type)`
     —— 不是自己比字面量、也不是比别的什么东西；
  ③ `nativeEmbedBatch` 里不许出现第二份档位清单（`poolType == 1/2/3` 之类）；
  ④ `batchable_pooling` 的实现**由 `plan_pooling` 分岔**（0.9.208 起它不再
     反问 `library_gives_seq` —— 三档现在也能合批）。它自己列一份
     `t == 0 || t == -1` 就是第二份清单，`plan_pooling` 加档时不会跟着动。

用法：check_batchable_by_pooling.py <llama_jni.cpp> <embed_text.h>
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

    # ── ① 池化档位来自库，不是我们猜的 ────────────────────────────────────
    load, _l1 = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel")
    if load is None:
        fails.append("找不到 nativeLoadEmbedModel（判据锚点失效）")
    elif not re.search(r'E\.pool_type\s*=\s*\(int\)\s*llama_pooling_type\s*\(', load):
        # 自己猜档位（例如"看模型名里有没有 bge"）会让 BGE / Qwen 之外的模型
        # 全部走错档，而错档的症状是"向量算出来了、语义全错"。
        fails.append("E.pool_type 不是取自 llama_pooling_type()（档位必须问库，不能猜）")

    # ── ②③ 合批这一层问的是**同一个**判据，且没有第二份档位清单 ─────────────
    batch, _l2 = fn_body(jni, "Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch")
    if batch is None:
        fails.append("找不到 nativeEmbedBatch（判据锚点失效）")
    else:
        cbs = re.findall(r'batchable_pooling\s*\(\s*([^)]*)\)', batch)
        if not cbs:
            fails.append("nativeEmbedBatch 没有用 batchable_pooling 判『这一档能不能合批』"
                         "—— 自己另判一次时，两份判据迟早分岔（分岔的表现是"
                         "『走的路径』与『报的档位』不是同一件事，而两者都不报错）")
        elif not all(re.fullmatch(r"(?:E\.)?poolType", c.strip()) for c in cbs):
            fails.append(f"nativeEmbedBatch 调 batchable_pooling 时传的不是 E.pool_type"
                         f"（实参：{cbs[0].strip()}）—— 传别的值等于让判据看另一个模型的档位")
        # 第二份档位清单：`poolType == 3` / `pooling == 2` 之类。允许 `== 0` / `== -1`
        # 吗？也不允许 —— 那正是"手抄一份放行清单"的形态，而它一旦与
        # `library_gives_seq` 分家，被合批的档就读错 buffer。
        stray = re.findall(r'(?:poolType|pooling|pool_type)\w*\s*==\s*-?\d+', batch)
        if stray:
            fails.append(f"nativeEmbedBatch 里直接比了档位字面量（{stray[0]}）——"
                         "档位清单只该有一份（batchable_pooling），这里再写一份就是分岔的起点")

    # ── ④ batchable_pooling 与 library_gives_seq 同源 ─────────────────────
    bp = fn_body(hdr, "batchable_pooling")
    if bp is None:
        fails.append("embed_text.h 里找不到 batchable_pooling（判据锚点失效）")
    else:
        # 0.9.208：不再是 `!library_gives_seq`（三档现在也能合批），但**必须**
        # 由 `plan_pooling` 分岔 —— "哪些档我们根本算不出向量"的清单只有那一份。
        if 'plan_pooling' not in bp[0]:
            fails.append("batchable_pooling 没有走 plan_pooling ——"
                         "它自己手抄一份放行清单时，与读取侧的分岔不会再被发现"
                         "（读取侧按 seq 取行、合批侧按另一份清单排布，两者都不报错）")
        if re.search(r'poolTypeRaw\s*==\s*-?\d', bp[0]):
            fails.append("batchable_pooling 里硬编码了档位（该由 plan_pooling 分岔）")
        # ⚠ 反面判据：**不许**再退回"只放行 NONE/UNSPECIFIED"。那一版把
        # BGE（CLS）与 Qwen3-Embedding（LAST）挡在合批外，而它们正是用户
        # 手上的两个模型 —— 退回那一版等于把本轮的收益整个丢掉，
        # 且界面/日志上完全看不出来（只是"没变快"）。
        if re.search(r'!\s*library_gives_seq', bp[0]):
            fails.append("batchable_pooling 又退回了『库自己池化的档不合批』——"
                         "那会让 BGE（CLS）/ Qwen3-Embedding（LAST）一条都合批不上，"
                         "而它们正是用户手上的两个模型（收益凭空消失、界面上看不出来）")

    # ── ⑤ 回落逐条在 Kotlin 侧有处理（不是静默不生效）─────────────────────
    kt = Path(jni_p).resolve().parents[3] / "src" / "main" / "java" / "com" / "xiaowan" / "localinference" / "LlmEngine.kt"
    if kt.exists():
        k = kt.read_text(encoding='utf-8', errors='replace')
        if 'BatchEmbed.Unavailable' not in k:
            fails.append("LlmEngine.kt 没有处理 BatchEmbed.Unavailable ——"
                         "『这一档不支持合批』会被当成『这批全失败』，条目凭空消失")
    else:
        fails.append(f"找不到 LlmEngine.kt（判据锚点失效：{kt}）")

    if fails:
        for f in fails:
            print(f"FAIL  {f}")
        return 1
    print("PASS  E.pool_type 取自 llama_pooling_type（档位问库，不猜）")
    print("PASS  nativeEmbedBatch 用 batchable_pooling(E.pool_type) 判合批，且无第二份档位清单")
    print("PASS  batchable_pooling 由 plan_pooling 分岔，且没有退回『库池化的档不合批』")
    print("PASS  回落到逐条在 Kotlin 侧有显式处理（BatchEmbed.Unavailable）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
