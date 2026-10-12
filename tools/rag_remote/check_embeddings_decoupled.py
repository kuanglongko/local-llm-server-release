#!/usr/bin/env python3
"""断言 `/v1/embeddings` 与知识库（RAG）开关**完全解耦**。

═══ 这一条守的是什么 ═══
用户口径第 4 条："`/v1/embeddings` 与开关完全解耦"。这条最容易被"顺手"改坏 ——
加一句 `if (!RagInject.remoteEnabled(this)) 503` 看起来是"统一口径"，
实际是**让两件不相干的事互相绑架**：

  · 用户为了 App 内聊天不被注入而关掉知识库开关 -> 远端客户端的 embeddings
    **跟着不可用**，而它跟知识库一点关系都没有（它只是"算向量"）；
  · 报出来的错还是"知识库未启用"，与真实原因（一个无关的开关）差着十万八千里。

所以判据只有一条，但是**硬**的：`handleEmbeddings` 的函数体里
**一个 `RagInject.` 引用都不许有**（也不许有 `RagProtocol.`）。
判据锚**函数体内**而不是整个文件：HttpApi.kt 里别处当然会用 RagInject
（`handleChat` 就用），整文件判会恒红。

反向也要判：它**必须**独立判"嵌入模型在不在"（缺了就 503 + 明文原因）——
只判"没引用 RagInject"会放过一个"什么都不判、直接算"的实现，而那是
"缺嵌入模型时静默返回空向量"的来源。

用法：check_embeddings_decoupled.py <HttpApi.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def main():
    if len(sys.argv) < 2:
        print("用法：check_embeddings_decoupled.py <HttpApi.kt>")
        return 2
    api = Path(sys.argv[1]).read_text(encoding="utf-8")
    bad = 0

    def ck(name, ok, detail=""):
        nonlocal bad
        if ok:
            print("PASS  " + name)
        else:
            print("FAIL  " + name + (" —— " + detail if detail else ""))
            bad += 1

    body, _ = kotlin_fn_body(api, 'handleEmbeddings')
    ck("找得到 handleEmbeddings", body is not None)
    if body is None:
        return 1

    # ① 与 RAG 开关**零耦合**：函数体里不许出现任何 Rag* 引用
    refs = sorted(set(re.findall(r'\b(RagInject|RagProtocol|RagIndexStore)\b', body)))
    ck("handleEmbeddings 不引用任何 RAG 组件（完全解耦）",
       not refs,
       f"引用了 {refs} —— 把无关的开关绑了进来")

    # ② 独立判"嵌入模型在不在"，缺了就 503（不是静默返回空 data）
    ck("handleEmbeddings 独立判 hasEmbedModel",
       'LlmEngine.hasEmbedModel' in body,
       "没有独立判断 —— 缺嵌入模型时会静默返回空向量")
    ck("缺嵌入模型回 503（不是 200 + 空 data）",
       re.search(r'if\s*\(!LlmEngine\.hasEmbedModel\)\s*\{[^}]*writeJson\(out,\s*503',
                 body) is not None,
       "缺模型时不是 503 —— 静默空结果会被调用方当成『这些文本没有向量』")

    # ③ **不持 busy**：嵌入与生成是两条路（双常驻的另一半价值）
    #    判据锚 `busy` 这个标识符在函数体里不出现 —— 出现即意味着"聊天时不能算向量"。
    ck("handleEmbeddings 不持 busy（嵌入不等生成）",
       not re.search(r'\bbusy\b', body),
       "持了 busy —— 双常驻『检索不用等生成』这条承诺在 HTTP 路径上失效")

    # ④ 路由存在，且落在 /v1/ 命名空间（自动要鉴权）
    ck("路由存在且是 POST /v1/embeddings",
       re.search(r'POST"?\s*&&\s*pathUnder\(req\.path,\s*"/v1/embeddings"\)\s*->\s*\n\s*handleEmbeddings',
                 api) is not None,
       "路由没接上（端点不可达）")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
