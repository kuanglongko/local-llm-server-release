#!/usr/bin/env python3
"""交叉校验注入片段格式：C++（rag_index.h）与 Kotlin（RagInject.kt）必须一致。

两份实现各自的单测都能自洽 —— 只要各自内部一致。真正会静默出问题的是
"一边改了格式、另一边没跟"：

  · 行首标记不一致 -> App 内注入的片段与（阶段 3 的）远端注入长得不一样，
    "到底注入了没有"的 grep 判据会有一边恒假；
  · 来源修饰还在（或只有一边去了）-> 行尾那段普通上下文文本会被模型抄进回答，
    正是用户要消掉的「（来源: …）」；两边只要有一边还在，就等于没修干净；
  · 表头策略不一致（一边"无条目返回空串"、另一边"仍返回表头"）->
    后者让"非空即已注入"这条判据恒真，任何"注入失效"的验证都白做。

这几条都不是"算错了"，是两边不是同一个东西。单测验不出来，只能交叉比。

⚠ 本轮契约变更：注入片段里**不再有来源修饰** —— 每条只有正文一行。所以这里的
"来源修饰"判据从"两边**相等**"翻成"两边**都没有**"（方向反了，见 [main]）。

用法：cross_inject.py <rag_index.h> <RagInject.kt>
"""
import re
import sys

NL = chr(92) + 'n'          # 源码文本里的转义序列：反斜杠 + n（两个字符）
OPEN_Q = '\uff08'           # 全角左括号（来源修饰的左括号，用于"不该出现"的探测）
SRC_TAG = '\u6765\u6e90'    # 来源


def _read(p):
    return open(p, encoding='utf-8').read()


def cpp_format(hdr):
    m = re.search(r'render_injection\s*\([^)]*\)\s*\{', hdr)
    if not m:
        return None
    body = hdr[m.end():m.end() + 2000]
    mark = re.search(r'if\s*\(s\.empty\(\)\)\s*s\s*\+=\s*"([^"]*)"', body)
    if not mark:
        return None
    # 行结尾：**最后一条** `s += "...";`（mark 那条写在 if 里，排除掉）。
    assigns = re.findall(r's\s*\+=\s*"([^"]*)"\s*;', body)
    tail = [a for a in assigns if a != mark.group(1)]
    if not tail:
        return None
    close = tail[-1]
    # 来源修饰：契约要求**不出现**。这里照样去抓，抓到就是违约（不是"提取失败"）。
    src = re.search(r'(' + OPEN_Q + SRC_TAG + r':[^"]*)"', body)
    return {'mark': mark.group(1), 'close': close,
            'src': src.group(1) if src else None, 'body': body}


def kt_format(kt):
    mark = re.search(r'const\s+val\s+MARK\s*=\s*"([^"]*)"', kt)
    m = re.search(r'fun\s+render\s*\([^)]*\)\s*:\s*String\s*\{', kt)
    if not mark or not m:
        return None
    body = kt[m.end():m.end() + 2000]
    # 行结尾：**最后一条** `sb.append(...)` 的字面量（mark 那条是 append(MARK).append('\n')，
    # 抓的是单引号字面量，两者不会混）。
    tails = re.findall(r"append\('([^']*)'\)", body)
    if not tails:
        return None
    close = tails[-1]
    src = re.search(r'append\("(' + OPEN_Q + SRC_TAG + r':[^"]*)"\)', body)
    return {'mark': mark.group(1), 'close': close,
            'src': src.group(1) if src else None, 'body': body}


def main():
    hdr, kt = _read(sys.argv[1]), _read(sys.argv[2])
    bad = 0
    c, k = cpp_format(hdr), kt_format(kt)
    if c is None:
        print("FAIL  C++ 侧提取不到 render_injection 的格式常量（判据锚点失效）")
        return 1
    if k is None:
        print("FAIL  Kotlin 侧提取不到 RagInject.render 的格式常量（判据锚点失效）")
        return 1

    # 行首标记：两边都必须是同一个文本。C++ 的捕获里带转义 `\n`，剥掉后比。
    c_mark = c['mark']
    c_has_nl = c_mark.endswith(NL)
    if c_has_nl:
        c_mark = c_mark[:-len(NL)]
    if c_mark != k['mark']:
        print("FAIL  行首标记不一致: C++ [%s] vs Kotlin [%s]" % (c_mark, k['mark']))
        bad += 1
    else:
        print("PASS  行首标记一致（[%s]）" % k['mark'])

    if not c_has_nl:
        print("FAIL  C++ 的行首标记后没有换行（表头会与第一条粘成一行）")
        bad += 1
    else:
        print("PASS  C++ 的行首标记后带换行")

    # 来源修饰：新契约要求两边**都不再有**（它会被模型抄进回答）。
    # 判据是"不出现"，不是"相等" —— 只要有一边还在，就等于没修干净。
    if c['src'] is not None or k['src'] is not None:
        print("FAIL  注入片段里还有来源修饰: C++ [%s] vs Kotlin [%s]"
              % (c['src'] or '-', k['src'] or '-'))
        bad += 1
    else:
        print("PASS  两边都不含来源修饰（每条只有正文）")

    # C++ 侧字面量里写的是转义 `\n`（两个字符），Kotlin 侧单引号里也是转义 `\n`。
    # 两者语义都是"每条正文之后换一行"，翻成同一个判据：都等于 NL（反斜杠 + n）。
    c_nl = c['close'] == NL
    k_nl = k['close'] == NL
    if not (c_nl and k_nl):
        print("FAIL  行结尾换行不一致: C++ %r vs Kotlin %r" % (c['close'], k['close']))
        bad += 1
    else:
        print("PASS  行结尾换行一致（每条正文一行）")

    idx_skip = c['body'].find('continue')
    idx_head = c['body'].find('s.empty()')
    if idx_skip < 0 or idx_head < 0 or idx_head < idx_skip:
        print("FAIL  C++ 的表头写入早于空正文跳过（无条目时会留一个光表头）")
        bad += 1
    else:
        print("PASS  C++ 的表头只在真有条目时写（无条目 -> 空串）")

    idx_skip = k['body'].find('continue')
    idx_head = k['body'].find('isEmpty()')
    if idx_skip < 0 or idx_head < 0 or idx_head < idx_skip:
        print("FAIL  Kotlin 的表头写入早于空正文跳过（无条目时会留一个光表头）")
        bad += 1
    else:
        print("PASS  Kotlin 的表头只在真有条目时写（无条目 -> 空串）")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
