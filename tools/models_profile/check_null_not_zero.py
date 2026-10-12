#!/usr/bin/env python3
"""`ModelProfile` 的每个字段都必须"取不到就是 null"，不许折成 0 / 空串。

═══════════════════════════════════════════════════════════════════════════
为什么 0 与"读不到"必须分开
═══════════════════════════════════════════════════════════════════════════
客户端拿 `/v1/models/{id}` 是去做**判断**的（"这个包多大""够不够上下文"）。
把"读不到"折成 0 之后，两个完全不同的处境在 JSON 里长得一模一样：
`"ctx_train":0` 既可以读成"这个模型没有上下文声明"，也可以读成"它声明了 0"——
而后者是个不存在的东西。所有"把缺省值填进协议字段"的写法都是这个病。

判据的口径（不是"出现过 getOrNull"这种恒真式）：
  · 解析口只有 `s` / `l` / `n` 三个局部函数；
  · 这三个函数体内都必须在**同一条链**上出现 `getOrNull`（数组越界 -> null）
    与 `to...OrNull`（解析失败 -> null）；
  · `s` 还必须在同一条链上判空串（native 用空串表示"取不到"）；
  · 8 个字段的赋值**每一个**都必须调用其中一个口 —— 这才是桩④ 那种
    `a?.getOrNull(3)?.toIntOrNull() ?: 0`（绕开 l/n 自己折成 0）会红的原因。

用法：python3 tools/models_profile/check_null_not_zero.py <ModelProfile.kt>
"""
import re
import sys

FIELDS = ["tensorBytes", "paramCount", "name", "ctxTrain",
          "architecture", "vocabSize", "fileType", "metaCount"]


def main(path):
    src = open(path, encoding="utf-8").read()
    i = src.index("fun fromNative(")
    j = src.index("\n        }", src.index("return ModelProfile(", i))
    body = src[i:j]

    code = "\n".join(l for l in body.splitlines()
                     if l.strip() and not l.strip().startswith("//"))

    ok = True
    # 三个口各自的 null 化方式：`s` 直接问数组，`l`/`n` 经由 `s(i)` 再解析 ——
    # 所以 `l`/`n` 体内出现的是 `s(i)`，而 `getOrNull` 在 `s` 里（同一条链，
    # 只是中间隔了一次调用）。要求「l/n 体内也有 getOrNull」会恒假。
    for fn, needles in (("fun s(", ("getOrNull",)),
                        ("fun l(", ("s(i)", "toLongOrNull")),
                        ("fun n(", ("s(i)", "toIntOrNull"))):
        k = code.index(fn)
        seg = code[k:code.index("\n", k)]
        if not all(t in seg for t in needles):
            ok = False
            print(f"  解析口 {fn} 缺少 null 化：{seg.strip()}")

    # s() 还必须判空串：native 用空串而不是 null 表示"取不到"
    k = code.index("fun s(")
    if "isNotEmpty" not in code[k:code.index("\n", k)]:
        ok = False
        print("  s() 没有把空串当成「取不到」")

    # 8 个字段的赋值必须**全部**经由那三个口
    for f in FIELDS:
        m = re.search(r"%s = ([^,\n]+)," % f, code)
        if not m:
            ok = False
            print(f"  字段 {f} 没有赋值")
            continue
        rhs = m.group(1)
        if not re.match(r"^[sln]\(\d+\)$", rhs.strip()):
            ok = False
            print(f"  字段 {f} 绕开了共用解析口：{rhs.strip()}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
