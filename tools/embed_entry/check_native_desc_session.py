#!/usr/bin/env python3
"""断言 `nativeEmbedModelDesc` 读的是**嵌入** Session（E），不是对话那份（S）。

用 `nativeModelDesc` 去报嵌入模型，拼出来是一份**字段名正确、JSON 合法**的响应，
但它永远是对话模型的描述 —— 不崩、不报错，症状与"这个字段要终结的困惑"完全同形：
探活里 `embed_model` 与 `model` 永远一模一样，看不出嵌入到底装上没有。

判据锚「这个函数体里读的信道」：函数体里必须出现 `E.model`，且**不允许**出现 `S.`。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body  # noqa: E402


def main():
    src = Path(sys.argv[1]).read_text(encoding='utf-8')
    body, line = fn_body(src, 'Java_com_xiaowan_localinference_LlmEngine_nativeEmbedModelDesc')
    if body is None:
        print("FAIL  找不到 nativeEmbedModelDesc 的函数体（判据锚点失效）")
        return 1
    if 'E.model' not in body:
        print("FAIL  nativeEmbedModelDesc 没有读 E.model（嵌入那一份）")
        return 1
    if re.search(r'\bS\.', body):
        print("FAIL  nativeEmbedModelDesc 里出现了 S.（对话那一份）—— 会报错模型")
        return 1
    print(f"PASS  nativeEmbedModelDesc 只读 E.model（第 {line} 行起）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
