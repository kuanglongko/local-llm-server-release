#!/usr/bin/env python3
"""断言嵌入加载路径真的调了 `llama_set_embeddings(ctx, true)`。

不调它的后果：`llama_get_embeddings_ith` 取到 null/未初始化 buffer，
nativeEmbed 返回 null，检索**全空**而接口 200 —— 典型"哑得不响"。
锚在 `nativeLoadEmbedModel` 的函数体内，且断言取值是 `true`（写 false 等于没开）。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import fn_body  # noqa: E402


def main():
    src = Path(sys.argv[1]).read_text(encoding='utf-8')
    body, line = fn_body(src, 'nativeLoadEmbedModel')
    if body is None:
        print("FAIL  找不到 nativeLoadEmbedModel 的函数体")
        return 1
    m = re.search(r'llama_set_embeddings\s*\(\s*[A-Za-z_][A-Za-z0-9_.]*\s*,\s*(true|false)\s*\)', body)
    if not m:
        print("FAIL  nativeLoadEmbedModel 体内没有 llama_set_embeddings(ctx, ...) 调用")
        return 1
    if m.group(1) != 'true':
        print("FAIL  llama_set_embeddings 的第二个实参不是 true（等于没开嵌入）")
        return 1
    print(f"PASS  nativeLoadEmbedModel 调用了 llama_set_embeddings(ctx, true)（第 {line} 行起）")
    return 0


if __name__ == '__main__':
    sys.exit(main())
