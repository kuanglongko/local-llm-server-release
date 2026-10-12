#!/usr/bin/env python3
"""断言知识库状态行读的是**引擎/存储真值**，不是本页自己的变量。

为什么守这一条：这一屏最容易出现的困惑是"我点了建库，到底建上了没有 / 为什么检索没内容"。
如果状态行读本页变量（"我以为加载了"），那它与真实状态不符时**看不出任何差别** ——
用户会照着一个骗人的绿字去排查。

判据锚 `renderRagStatus` 体内出现的那几处取值，不锚"文件里有 hasEmbedModel"
（后者恒真：别的函数里也有）。

⚠ 例外是"正文改了没有"那一条：**只判"体内出现过 `textsInSync`"是不够的**。
这一族写法有两种假绿，且都是真实存在的源码形状：

  ① 换个近似判据 —— 取值改成 `indexFile(...) != null`（"索引文件在就是就绪"）。
     能编译、照样显示"索引就绪"，而在**正文已改**与**半份索引**（`savePartial`）
     两种情形下与 `textsInSync` 结论相反。状态行说"就绪"而检索实际不生效，
     比没有状态行更坏：用户会照着一句骗人的绿字去查模型、查内存（两种不同修法）。
  ② 取值在、分支不看它 —— 保留 `val inSync = ...textsInSync(...)`，
     把下面那一支改成 `exists && false ->`。标识符还在体内，判据恒绿，
     而"正文已改"的提示永远不会亮。

所以那一条锚**三处表达式**：取值来自同一个存储判据 / 它被存进一个变量 /
报错的那一支挂在**同一个变量**上。

用法：check_status.py <EngineActivity.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body, kotlin_fn_span  # noqa: E402


def raw_fn(src, name):
    code, sline = kotlin_fn_span(src, name)
    if code is None:
        return None
    d = 0
    started = False
    end_line = sline
    for idx, ch in enumerate(code):
        if ch == '{':
            d += 1
            started = True
        elif ch == '}':
            d -= 1
            if started and d == 0:
                end_line = sline + code.count(chr(10), 0, idx)
                break
    lines = src.split(chr(10))
    return chr(10).join(lines[sline - 1:min(end_line, len(lines))])


def main():
    ea = Path(sys.argv[1]).read_text(encoding='utf-8')
    bad = 0
    # 字符串字面量在 _fnbody 里被抹掉，取值表达式多在字符串模板里 -> 读原文区间
    body = raw_fn(ea, 'renderRagStatus')
    if body is None:
        print("FAIL  找不到 renderRagStatus（用户看不到知识库状态）")
        return 1
    for needle, desc in (
        ('LlmEngine.hasEmbedModel', '嵌入是否驻留取引擎真值'),
        ('RagInject.enabled', '开关取持久化真值'),
        ('RagInject.currentKb', '当前库取持久化真值'),
        ('RagIndexStore.indexFile', '索引是否存在取存储真值'),
    ):
        if needle not in body:
            print("FAIL  renderRagStatus 缺少 %s（%s）" % (needle, desc))
            bad += 1
        else:
            print("PASS  renderRagStatus 的%s（%s）" % (desc, needle))

    # ── 第二条轴：正文改了没有 —— 必须与检索**同判据**，且判在那一支上 ──────
    #
    # ① 取值来自同一个存储判据（唯一真值来源），且被存进一个**变量**
    #    （下面那一支用的是同一份读数，不是再取一次）。
    m = re.search(r'val\s+(\w+)\s*=\s*[^\n]*RagIndexStore\.textsInSync\(', body)
    if not m:
        print("FAIL  状态行没有把 RagIndexStore.textsInSync(...) 存进变量 —— "
              "「正文改了没有」要么没判，要么另写了一个近似判据（两者都会说反话）")
        bad += 1
        return 1
    var = m.group(1)
    print("PASS  renderRagStatus 的正文与索引是否一致取存储真值"
          "（RagIndexStore.textsInSync -> %s）" % var)

    # ② 报错的那一支挂在**同一个变量**上。
    #    ⚠ 锚点是 `\binSync\b\s*->`：变量在箭头**前**（`exists && !inSync -> …`），
    #    箭头后面是 `parts.add(...)`，那里永远不会有第二个读数 ——
    #    写成 `->[^\n]*inSync` 会**恒假**（这一处我看错过一次）。
    #    也不能只判"体内出现过该名字"：`if (false)` / 换个同名前缀的变量都会让它恒真。
    if not re.search(r'\b' + re.escape(var) + r'\b\s*->', body):
        print("FAIL  「正文已改」的那一支没有挂在 %s 上 —— 取值取了、分支不看它，"
              "状态行会一直报「索引就绪」（比没有状态行更坏）" % var)
        bad += 1
    else:
        print("PASS  「正文已改」的提示挂在 %s 那一支上（取值与分支同源）" % var)

    # ③ 分支条件不许是常量（恒真/恒假 = 那一条提示永远不会按真实状态亮/灭）。
    if re.search(r'\b(?:true|false)\b\s*->', body):
        print("FAIL  「正文已改」的分支条件里出现常量（恒真/恒假）—— "
              "那一条提示不会随真实状态变化")
        bad += 1
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
