#!/usr/bin/env python3
"""`GET /v1/models` 与 `GET /v1/models/{id}` 的 profile 字段名必须**是同一批**。

═══════════════════════════════════════════════════════════════════════════
为什么单独抽成一个脚本
═══════════════════════════════════════════════════════════════════════════
判据是"两处的**集合**相等且就是那 8 个"，不是"两处都出现过 tensor_bytes"：

  · 后者在改名时只改一处照样绿 —— 而那一处的症状正是本判据要防的东西：
    客户端看到"列表里有 profile、单查没有"，两边都是合法 JSON、谁都不报错；
  · 也只判"出现过"的话，某处多塞一个只在一处出现的字段（例如单查多给一个
    `tensor_bytes2`）也会绿，而那等于两个接口的契约已经分叉。

用法：python3 tools/models_profile/check_field_names.py <HttpApi.kt>
"""
import re
import sys

WANT = {
    "name", "architecture", "tensor_bytes", "param_count",
    "ctx_train", "vocab_size", "file_type", "meta_count",
}


# 认两种写法，且**两种都收**：
#   · `profile.<字段>?.let { put("<JSON 名>")` —— 经过共用拼装口（形状本身钉住
#     "取不到就不出现"）；
#   · 直接在函数体里 `put("<JSON 名>"` / `o.put("<JSON 名>"` —— 这就是**绕过**共用口
#     的写法。它必须被算进集合，否则"单查多一个只在这里出现的字段"这根桩
#     （字段在自己的函数里被 `put`）根本不会让两个集合不等，判据就成了摆设。
_LET_PUT = re.compile(r'profile\.\w+\?\.let \{ put\("(\w+)"')
# 排除 `o.put("profile", ...)` 与 `o.put("meta", ...)` 这两个**包装**字段：
# 它们不是档案字段本身（档案字段在 profile 对象里），收进来只会让两边
# 永远差一个 —— 而恒差一位的判据会被改判据的人静音。
_NOT_PROFILE_FIELDS = {"profile", "meta"}
_RAW_PUT = re.compile(r'(?:^|[\s({])(?:o\.)?put\("(\w+)"')


def collect(box):
    """取出这段话里出现的全部 profile **JSON 字段名**。

    两种写法都收（见上方常量注释）：只认前一种的话，"绕开共用口另写一个字段"
    这种最需要抓的形态反而看不见 —— 那正是本判据存在的理由。
    """
    return {m.group(1) for m in _LET_PUT.finditer(box)} | \
           {m.group(1) for m in _RAW_PUT.finditer(box)
            if m.group(1) not in _NOT_PROFILE_FIELDS}


def body_of(src, header):
    i = src.index(header)
    j = src.index("\n    }", i)
    return src[i:j]


def main(path):
    src = open(path, encoding="utf-8").read()
    single = collect(body_of(src, "private fun modelEntryJson("))
    listed = collect(body_of(src, "private fun modelsJson()"))
    ok = single == WANT and listed == WANT
    if not ok:
        print(f"  单查字段：{sorted(single)}")
        print(f"  列表字段：{sorted(listed)}")
        print(f"  期望字段：{sorted(WANT)}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
