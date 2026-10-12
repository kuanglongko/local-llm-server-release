#!/usr/bin/env python3
"""断言 nativeEmbed 返回的 `jfloatArray` **分配长度与填充长度是同一个数**。

═══ 这一条守的是什么 ═══
`nativeEmbed` 末段有一次「创建数组 + 填充数组」的配对调用：

    jfloatArray out = env->NewFloatArray(<A>);
    env->SetFloatArrayRegion(out, 0, <B>, vec.data());
    return out;

`jfloatArray` 创建即**全零**，所以 `A != B` 时多出来的那 `A-B` 个位置
就是一段静默的零尾 —— 而调用方（`LlmEngine.buildIndex` 用 `v.size != dim` 判、
`EmbeddingsApi` 用 `vec.isEmpty()` 判）拿到的长度是 `A`。

这条判据在 0.9.186 里曾经**真的被踩到**：`A = nEmbdOut`、`B = nEmbd`。
它没爆，只是因为上游 `output_dim_ok` 要求两者严格相等、挡住了这条路径 ——
也就是说，唯一在挡它的东西是**另一处**的判据，而那处判据只判"值"、
从来没保证过"分配数 == 填充数"。闸一旦放宽（`embed_text.h` 那段注释
正在讨论"输出维度更长时语义被截掉一截"），这里就会静默返回带零尾的向量：
归一化后方向偏离，检索命中率凭空下降，接口 200。

═══ 为什么锚"标识符相同"而不是"出现过 nEmbd" ═══
`nEmbd` 在这个函数里出现几十次，锚"出现过"是恒真的。这里从**函数原文**里
抽出那两次调用的实参字符串，逐字比 —— 只有"同一个名字"才算过。
写成 `nEmbdOut` / `vec.size()` / `(int) vec.size()` 都会红，
而"往两边各抄一个常量"（`480`/`480`）这种绕过方式不在这里堵：
那属于"两个数各自写死"，评审时看得见。

用法：check_return_contract.py <llama_jni.cpp>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import fn_body  # noqa: E402


def main():
    if len(sys.argv) < 2:
        print("用法：check_return_contract.py <llama_jni.cpp>")
        return 2
    src = Path(sys.argv[1])
    if not src.is_file():
        print("FAIL  找不到 " + str(src))
        return 1
    text = src.read_text(encoding="utf-8", errors="replace")
    body, _line = fn_body(text, "nativeEmbed")

    fails = []
    if body is None:
        print("FAIL  抽不到 nativeEmbed 的函数体（改名/拆掉了？）")
        return 1

    def args(pattern, what):
        m = re.search(pattern, body)
        if not m:
            fails.append(f"{what}：锚点没找到（{pattern}）")
            return None
        return m.group(1).strip()

    # 分配长度
    alloc = args(r'NewFloatArray\s*\(\s*([^)]*?)\s*\)', "分配长度 NewFloatArray(...)")
    # 填充长度：SetFloatArrayRegion(out, start, len, data) 里的第三个实参
    fill = args(r'SetFloatArrayRegion\s*\(\s*out\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,',
                "填充起点/长度 SetFloatArrayRegion(...)")
    fill_pair = re.search(r'SetFloatArrayRegion\s*\(\s*out\s*,\s*([^,]+?)\s*,\s*([^,]+?)\s*,', body)
    fill_start = fill_pair.group(1).strip() if fill_pair else None
    fill_len = fill_pair.group(2).strip() if fill_pair else None

    if alloc is not None and fill_len is not None:
        if alloc != fill_len:
            fails.append(
                f"分配长度与填充长度**不是同一个数**：NewFloatArray({alloc}) 但只填 {fill_len} 个 —— "
                f"多出来的那 {alloc} - {fill_len} 个位置是静默零尾，"
                f"而调用方看到的长度是前者")
        else:
            print(f"PASS  分配长度 == 填充长度 == {alloc}（同一个标识符，不再各写一个数）")
    if fill_start is not None and fill_start != "0":
        fails.append(f"填充起点不是 0（是 {fill_start}）—— 开头会留下未填的位置")

    for f in fails:
        print("FAIL  " + f)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
