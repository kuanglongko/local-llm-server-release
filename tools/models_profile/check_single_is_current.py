#!/usr/bin/env python3
"""`GET /v1/models/{id}` 也必须"只给当前加载的那个模型"挂档案。

═══════════════════════════════════════════════════════════════════════════
为什么单查这条不能省（它和列表那条是两回事）
═══════════════════════════════════════════════════════════════════════════
native 侧只有一个 `Session S` —— 权重同一时刻只加载**一份**。档案里的权重字节、
参数个数、训练上下文全是**那一个**模型的读数。单查若不加这个条件，用户查
`/v1/models/别的模型` 会拿到当前加载模型的权重字节，而那个模型确实存在、
JSON 也合法 —— 读的人会拿它去对账，得出"这个 4B 模型只有 300 MB 权重"这种结论。

判据不是"出现过 currentModel 这个名字"（那样删掉条件也照样绿），而是：
  · 函数体内先取了一个局部值 = `currentModel`；
  · profile 分支里比的那个名字**就是那个局部值**（不是写死的字符串、不是别的变量）。

用法：python3 tools/models_profile/check_single_is_current.py <HttpApi.kt>
"""
import re
import sys


def main(path):
    src = open(path, encoding="utf-8").read()
    i = src.index("private fun modelEntryJson(")
    j = src.index("\n    }", i)
    body = src[i:j]
    code = "\n".join(l for l in body.splitlines()
                     if l.strip() and not l.strip().startswith("//"))

    m = re.search(r"val (\w+) = currentModel", code)
    if not m:
        print("  没找到 `val <名> = currentModel`")
        return 1
    name = m.group(1)

    # 必须存在 "profile.available && <名> == <该局部值>" 形状的比较
    pat = re.compile(r"if \(profile\.available && \w+ == %s\)" % re.escape(name))
    if not pat.search(code):
        print(f"  没有 `if (profile.available && <入参> == {name})` 形状的比较")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
