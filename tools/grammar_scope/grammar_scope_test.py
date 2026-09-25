#!/usr/bin/env python3
"""GBNF 模板取值链的**可运行复刻**：两个端点对「用哪个模板」是否给同一个答案。

═══════════════════════════════════════════════════════════════════════════
这份复刻测的是什么（以及不测什么）
═══════════════════════════════════════════════════════════════════════════
不测库（vendor 只有预编译 .so，`common_chat_templates_apply` 跑不了）。测的是
**调用侧那条取值链**：`handleChat` 与 `handleCompletion` 各自把什么交给
`gbnf_from_json_schema` 的 `tmplOverride`，以及那两个取值是否指向同一份模板。

为什么这条链值得单独测一遍：
  · 两个端点**都**有 `chatTemplateOverride = ...` 这一行（存在性断言的绿区），
    差别只在等号右边 —— 于是"两个端点取值一致"是一条**关系**判据，不是存在性判据；
  · 取值一旦分叉，症状是 `content` 前面多一段生成标记、或数组 schema 只剩 `[ ]`，
    **HTTP 200、不报错**，与"没修"逐字相同；
  · 真机上"两个端点各自对同一份模型给不同约束"这件事只能靠**打两个端点的请求**
    才能看出来，而审查/CI 里没有设备。

复刻对象是**真源码**：从 `HttpApi.kt` 抽取两个端点里 `chatTemplateOverride` 的实参
表达式，再按 Kotlin 语义折成"它最终是什么"。旧写法（`chatTemplateOf(null)`）折成
空串（= 让库按模型自选），新写法折成运行时模板原文。

用法：python3 tools/grammar_scope/grammar_scope_test.py [--old <HttpApi.kt>]
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

ok = 0
bad = 0


def check(name, cond, detail=""):
    global ok, bad
    if cond:
        print("PASS  " + name)
        ok += 1
    else:
        print("FAIL  " + name + (" —— " + detail if detail else ""))
        bad += 1


CALL = "chatTemplateOverride = RequestContext.chatTemplateOf("
RUNTIME = "LlmEngine.chatTemplate()"

# 运行时模板原文。复刻里用一个**明显不等于库内自选**的值：真机上它来自
# `llama_model_chat_template`，与 `minja::resolve_template` 挑的那份可能不同，
# 所以复刻必须把"这两份不同"这件事显式建出来，否则分叉测不出来。
RUNTIME_TEMPLATE = "{%- if enable_thinking is defined %}<|im_start>assistant\n<think>\n{%- endif %}"
# 库内自选那份（空串 = 让库自己 resolve）。刻意与上面不同。
LIB_SELECTED = "<lib-auto-selected-template>"


def strip_comments_lines(src):
    """去注释、**保持行号**（失败时行号要对得上真源码）。"""
    out = []
    for line in src.splitlines():
        st = line.lstrip()
        if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
            out.append("")
            continue
        i = line.find('//')
        out.append(line[:i] if i >= 0 else line)
    return "\n".join(out)


def parse_sites(src):
    """抽出每个 `chatTemplateOverride` 调用点的实参表达式（括号配对取到最外层右括号）。"""
    src = strip_comments_lines(src)
    runtime_vars = {m.group(1) for m in
                    re.finditer(r'\bval\s+([A-Za-z_]\w*)\s*=\s*' + re.escape(RUNTIME), src)}
    sites = []
    i = 0
    while True:
        i = src.find(CALL, i)
        if i < 0:
            break
        depth = 1
        j = i + len(CALL)
        while depth > 0:
            ch = src[j]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            j += 1
        j -= 1
        arg = src[i + len(CALL):j].strip()
        line = src[:i].count("\n") + 1
        sites.append((line, arg, runtime_vars))
        i = j
    return sites, runtime_vars


def resolve(arg, runtime_vars):
    """按 Kotlin 语义把实参折成**交给 native 的字符串**。

    `chatTemplateOf(x)` 的定义是 `x ?: ""`（见 RequestContext），所以：
      · 字面量 null -> ""（让库按模型自选）—— 这就是分叉的来源；
      · 字面量 ""   -> ""（同上，方向相反的同一种错）；
      · 变量/表达式  -> 运行时模板原文（复刻里用 RUNTIME_TEMPLATE 代表）；
      · 无法追溯     -> 抛错（宁可炸，不要静默当成"对的"）。
    """
    if arg in ('null', '""', "''", 'String()'):
        return ""
    if arg == RUNTIME:
        return RUNTIME_TEMPLATE
    if re.match(r'^[A-Za-z_]\w*$', arg) and arg in runtime_vars:
        return RUNTIME_TEMPLATE
    raise ValueError("无法追溯的取值：%s" % arg)


def final_template(param):
    """native `gbnf_from_json_schema` 的折法：空串 -> 库按模型自选；非空 -> 用这份。"""
    return LIB_SELECTED if param == "" else param


def main():
    args = sys.argv[1:]
    mode_old = '--old' in args
    path = args[-1] if args else str(
        ROOT / "app/src/main/java/com/xiaowan/localinference/HttpApi.kt")
    src = Path(path).read_text(encoding='utf-8')

    sites, _vars = parse_sites(src)
    if len(sites) != 2:
        print("失败：`chatTemplateOverride = ...` 调用点应为 2 处（两个生成端点），实际 %d 处"
              % len(sites))
        return 1

    got = []
    for line, arg, rv in sites:
        try:
            got.append((line, arg, resolve(arg, rv)))
        except ValueError as e:
            got.append((line, arg, None))
            check("HttpApi.kt:%d 的取值可追溯" % line, False, str(e))

    # ① 两个端点传给 native 的**最终模板**必须一致。
    finals = [final_template(p) if p is not None else None for (_l, _a, p) in got]
    check("两个生成端点传给 gbnf_from_json_schema 的模板**一致**",
          finals[0] is not None and finals[0] == finals[1],
          "L%d=%r vs L%d=%r" % (got[0][0], finals[0], got[1][0], finals[1]))

    # ② 两侧都必须落在**运行时模板**上（不是"让库自选"）。
    check("两侧取值都不是「让库按模型自选」（空串）",
          all(f == RUNTIME_TEMPLATE for f in finals),
          "实际 = %r" % (finals,))

    # ③ 反例对照：旧写法（传 null）在这条判据下必须**为假** —— 判据非恒真。
    old_final = final_template("")
    check("反例对照：旧写法（空串/让库自选）不满足「两侧同为运行时模板」",
          old_final != RUNTIME_TEMPLATE)

    if mode_old:
        print("     旧版取值链：两个端点最终模板 = %r（分叉：库自选 vs 运行时）" % (finals,))

    print("=== grammar 模板取值链复刻测试 %s（共 %d 条）===" %
          ("全部通过" if bad == 0 else "%d 条失败" % bad, ok + bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
