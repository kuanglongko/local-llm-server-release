#!/usr/bin/env python3
"""从 vendor 头里抽出某个 struct 的**字段清单**（按声明顺序）。

用途：ABI 守卫要判"每个字段都有 offsetof 断言"，而不是"offsetof 有 >= N 条"。
后者是个**数量**判据：结构体加了字段它不红（数量只增），删了字段也未必红
（删 2 个、加 2 个，数量不变）。所以数量判据挡不住"头与 .so 不同源"这种事。
"""

import re


def struct_fields(header_path, struct_name):
    """返回 [(字段名, 字段类型原文)]，按声明顺序。

    只取 struct 顶层字段：按花括号深度切，避开嵌套 struct / 初始化器里的分号。
    """
    src = re.sub(r"/\*.*?\*/", "", open(header_path, encoding="utf-8").read(), flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    m = re.search(r"struct\s+" + re.escape(struct_name) + r"\s*(?::[^{]*)?\{", src)
    if not m:
        return []
    i = m.end()
    depth = 1
    body_start = i
    while i < len(src) and depth:
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
        i += 1
    body = src[body_start:i - 1]
    fields = []
    depth = 0
    cur = []
    for ch in body:
        if ch in "{(":
            depth += 1
        elif ch in "})":
            depth -= 1
        if ch == ";" and depth == 0:
            stmt = "".join(cur).strip()
            cur = []
            if not stmt:
                continue
            # 函数声明/静态断言：以 `)` 收尾**且没有初始化器**（带 `=` 的是字段默认值）
            if stmt.endswith(")") and "=" not in stmt:
                continue
            # `类型 名字` 或 `类型 名字 = 默认值`
            stmt = stmt.split("=")[0].strip()
            fm = re.match(r"^(.+?)[\s*&]+(\w+)$", stmt, re.S)
            if fm:
                typ = re.sub(r"\s+", " ", fm.group(1)).strip()
                fields.append((fm.group(2), typ))
        else:
            cur.append(ch)
    return fields
