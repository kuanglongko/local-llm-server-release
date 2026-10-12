#!/usr/bin/env python3
"""断言 `doLoadEmbed` 调的是 `loadEmbedModel`，**不是** `loadModel`。

这正是用户踩到的那个坑的判据：按对话模型的步骤去加载向量模型，
实际走 `loadModel`（装进对话槽）→ "两个模型互相顶掉"。
写成 `LlmEngine.loadModel(...)` 时按钮文字还写着"加载嵌入模型"，
UI 上完全看不出走错了哪条路。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import kotlin_fn_body  # noqa: E402


def main():
    src = Path(sys.argv[1]).read_text(encoding='utf-8')
    body, line = kotlin_fn_body(src, 'doLoadEmbed')
    if body is None:
        print("FAIL  找不到 doLoadEmbed 函数体（判据锚点失效）")
        return 1
    if 'loadEmbedModel' not in body:
        print("FAIL  doLoadEmbed 没调 loadEmbedModel（嵌入模型装不进嵌入槽）")
        return 1
    # 反向：不许出现「调用 loadModel」——`loadEmbedModel` 里也含 `loadModel` 子串，
    # 所以必须按**调用形式**判（`LlmEngine.loadModel(` 且后面不是 `Embed`）。
    if re.search(r'LlmEngine\.loadModel\s*\(', body):
        print("FAIL  doLoadEmbed 里出现了 LlmEngine.loadModel( —— 会装进对话槽、顶掉对话模型")
        return 1
    print(f"PASS  doLoadEmbed 只调 loadEmbedModel（第 {line} 行起）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
