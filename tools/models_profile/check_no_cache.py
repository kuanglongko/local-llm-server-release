#!/usr/bin/env python3
"""`LlmEngine.modelProfile()` 必须**每次直读 native**，不许自己存一份。

═══════════════════════════════════════════════════════════════════════════
为什么"不缓存"是一条判据（而不是随手的选择）
═══════════════════════════════════════════════════════════════════════════
缓存就要回答"换模型时怎么失效"，而失效判据正是这个仓库里静默 bug 的温床：
漏了失效 = 换模型之后 `/v1/models` 上挂的还是**上一个模型**的权重字节与参数个数，
而两个模型都存在、JSON 也合法、没有任何症状 —— 读的人会拿它去对账。

档案是低频读（`/v1/models` 轮询），重读一次 native 的成本（几微秒的只读查询）
远低于错读的代价。所以这条判据是**设计约束**，不是性能取舍。

判据只取**代码行**：注释里为了讲清"为什么不缓存"必然会出现"缓存"二字，
拿整段（含注释）去判会把这条判据变成恒假 —— 而恒假的判据终会被 `|| true` 静音。

用法：python3 tools/models_profile/check_no_cache.py <LlmEngine.kt>
"""
import sys


def main(path):
    src = open(path, encoding="utf-8").read()
    i = src.index("fun modelProfile(): ModelProfile")
    j = src.index("\n    }", i) + 6
    body = src[i:j]
    code = [l.strip() for l in body.splitlines()
            if l.strip() and not l.strip().startswith("//")
            and not l.strip().startswith("*") and not l.strip().startswith("/*")]
    flat = " ".join(code)
    ok = ("nativeModelMeta()" in flat
          and "by lazy" not in flat
          and "private var" not in flat
          and "private val" not in flat)
    if not ok:
        print(f"  modelProfile() 体内代码：{flat}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
