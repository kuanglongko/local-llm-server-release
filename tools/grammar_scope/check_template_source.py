#!/usr/bin/env python3
"""检查两个生成端点传给 `chatTemplateOverride` 的取值**来源**是否为运行时模板。

为什么是真词法检查而不是 grep：两个端点**都有** `chatTemplateOverride = ...` 这一行，
差别只在等号右边。`grep` 只能回答"某段文本在不在文件里"，而这条判据要回答的是
「**每一个**调用点右边都是运行时模板」—— 前者是存在性（恒真），后者才是结构关系。
第一版守卫写成"文件里出现过正确表达式"，自测的桩②当场抓出来：一个端点对了、
另一个还是 `chatTemplateOf(null)` 时，它照样全绿 —— 那正是 F-2 本身的形态。

判据（每个调用点都要满足）：
  · 实参不是字面量空串 / `null` / `""`（= "让库按模型自选"，那条分叉的来源）；
  · 实参必须**追溯**到 `LlmEngine.chatTemplate()`：
      - 直接写 `RequestContext.chatTemplateOf(LlmEngine.chatTemplate())`，或
      - 写 `RequestContext.chatTemplateOf(<局部变量>)`，而该局部变量在同一函数体里
        被赋值为 `LlmEngine.chatTemplate()`。
    两条都是"运行时模板"，区别只是 handleChat 先把它存进 `val chatTemplate`
 （那个变量后面还要给渲染侧用，同一个值给两处是**有意**的）。

用法：python3 tools/grammar_scope/check_template_source.py <HttpApi.kt>
退出码：0 = 每个调用点都来自运行时模板；1 = 有分叉（打印是哪一处）。
"""
import re
import sys

CALL = "chatTemplateOverride = RequestContext.chatTemplateOf("
RUNTIME = "LlmEngine.chatTemplate()"

# 局部变量赋值形态：`val chatTemplate = LlmEngine.chatTemplate()`
ASSIGN = re.compile(r'\bval\s+([A-Za-z_]\w*)\s*=\s*' + re.escape(RUNTIME))


def strip_comments(src):
    """去掉行注释与整行块注释，但**保持行号不变**（用空行占位）。

    为什么必须去注释：注释里出现 `chatTemplateOf(null)`（解释旧写法叫什么、或写明
    "不许这样写"）不该让守卫变红。模块 I 的 PR-1 正是踩了这个坑 —— 把注释行也算进
    "实现行号"之后，一份"注释里说 stop 先、实现是 unload 先"的源码被判成顺序正确。

    为什么必须保持行号：失败时要指出是哪一行。用空串替换注释会让后续行号整体前移，
    报出来的行号对不上真源码 —— 那比不报更坏（排障者会去看一个无关的位置）。
    """
    out = []
    for line in src.splitlines():
        s = line.lstrip()
        if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
            out.append("")
            continue
        i = line.find('//')
        out.append(line[:i] if i >= 0 else line)
    return "\n".join(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    flags = {a for a in sys.argv[1:] if a.startswith('--')}
    if not args:
        print("用法: check_template_source.py [--no-null-literal] <HttpApi.kt>")
        return 2
    src = strip_comments(open(args[0], encoding='utf-8').read())

    # `--no-null-literal`：只回答"还有没有字面量式空模板"（去注释后），
    # 不做调用点计数。守卫用它表达"旧写法绝迹"这条 —— 与计数判据分开，
    # 因为一条断言只该回答一件事（混在一起时失败信息指不出是哪一侧坏的）。
    if '--no-null-literal' in flags:
        hits = [i + 1 for i, line in enumerate(src.splitlines())
                if 'chatTemplateOf(null)' in line or 'chatTemplateOf("")' in line]
        if hits:
            print("失败：去注释后仍有字面量式空模板（= 让库按模型自选），行 %s"
                  % ", ".join(map(str, hits)))
            return 1
        return 0

    # 收集每个局部变量 -> 是否来自运行时模板。
    runtime_vars = {m.group(1) for m in ASSIGN.finditer(src)}

    sites = []
    i = 0
    while True:
        i = src.find(CALL, i)
        if i < 0:
            break
        # 实参区要按**括号配对**取到最外层右括号 —— 实参本身可能含括号
        # （`LlmEngine.chatTemplate()`），用 `index(')')` 会在这里截断，
        # 于是把正确的取值判成 `LlmEngine.chatTemplate(` 而误红。
        depth = 1
        j = i + len(CALL)
        while depth > 0:
            ch = src[j]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            j += 1
        j -= 1  # 回到配对的右括号位置
        arg = src[i + len(CALL):j].strip()
        # 行号（便于失败时点名）
        line = src[:i].count("\n") + 1
        sites.append((line, arg))
        i = j

    if len(sites) != 2:
        print("失败：`chatTemplateOverride = ...` 调用点应为 2 处（两个生成端点），"
              "实际 %d 处" % len(sites))
        return 1

    bad = []
    for line, arg in sites:
        if arg in ('null', '""', "''", 'String()'):
            bad.append((line, arg, "字面量「让库按模型自选」—— 与渲染侧分叉的来源"))
            continue
        if arg == RUNTIME or (arg in runtime_vars and re.match(r'^[A-Za-z_]\w*$', arg)):
            continue
        bad.append((line, arg, "无法追溯到 %s" % RUNTIME))

    for line, arg, why in bad:
        print("失败：HttpApi.kt:%d 的 chatTemplateOverride 取值 = `%s` —— %s" % (line, arg, why))
    if bad:
        return 1
    print("     两个调用点都来自运行时模板：" + "，".join("L%d=`%s`" % (l, a) for l, a in sites))
    return 0


if __name__ == '__main__':
    sys.exit(main())
