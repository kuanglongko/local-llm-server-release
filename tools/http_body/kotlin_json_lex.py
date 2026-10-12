#!/usr/bin/env python3
"""按 Kotlin 词法把源码里的原始字符串（`\"\"\"...\"\"\"`）拼接表达式还原成字面量。

为什么单独抽成一份：`/health` 的守卫（`run_health_json_guard.py`）已经把「行尾引号
多写一个」这件事证明成真事故（Issue #77，服务照 200、探活误判不可达）。这一份把
同一套词法抽出来，让**其余每个响应体**都能被同一把尺子量 —— 免得每个端点各写一份
扫描器，各错一次。

词法要点（与 Kotlin 规范一致，也是这整类 bug 的成因）：
    `\"\"\"` 开启原始字符串；扫描到**连续引号**为止；
    连续引号里**最后 3 个**是结束符，前面多出来的每一个都属于**内容**。
所以段尾写 0 个内容引号是正解；手滑多写一个引号，就凭空多出一个 `"` ——
JSON 里立刻多一对空串，整份响应非法，而 Kotlin 照样编过。
"""

import re


def scan_raw_strings(body):
    """从一段源码里按词法切出所有原始字符串段，返回定界后的原文段列表。

    段的形态：三段引号包内容（把多出的内容引号原样留在段里），
    便于调用方直接观察「这一段的行尾到底写了几个引号」。
    """
    segs = []
    i = 0
    while True:
        i = body.find('"""', i)
        if i < 0:
            break
        k = body.find('"""', i + 3)
        if k < 0:
            raise AssertionError("原始字符串未闭合：%r" % body[i:i + 60])
        n = k
        while n < len(body) and body[n] == '"':
            n += 1
        content_quotes = n - k - 3      # 最后 3 个才是结束符，其余属于内容
        segs.append(body[i:k] + '"' * content_quotes + '"""')
        i = n
    return segs


def restore_segment(seg):
    """把一个已定界的原始字符串段还原成它的**字面量内容**（去掉定界与内容引号规则）。"""
    seg = seg.rstrip()
    seg = re.sub(r"\s*\+\s*$", "", seg)
    m = re.match(r'^\s*"""', seg)
    assert m, "不是以 \"\"\" 开头：%r" % seg
    rest = seg[m.end():]
    run_m = re.search(r'("+)$', rest)
    assert run_m, "缺少结束引号：%r" % seg
    run = run_m.group(1)
    body = rest[: rest.rfind(run)]
    content_quotes = len(run) - 3
    assert content_quotes >= 0, "未闭合的原始字符串：%r" % seg
    return body + '"' * content_quotes


def extract_call_expr(src, anchor, stop_at):
    """切出 `anchor` 之后、`stop_at` 之前的**拼接表达式原文**。

    只取第一个语句/参数表达式，用于定位"这一处响应体是哪种形状"。
    段与段的切法交给 [scan_raw_strings] —— 那才是按词法的部分。
    """
    start = src.index(anchor)
    end = src.index(stop_at, start)
    return src[start:end]
