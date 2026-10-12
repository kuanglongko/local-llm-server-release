#!/usr/bin/env python3
"""`/health` 的嵌入三个字段必须取自**嵌入那一份**，不能是对话那份的复制品。

判据锚「取值表达式」而不是「字段名出现过」—— 后者恒真：一份
`"embed_model":$desc`（$desc 是对话模型的描述）里字段名写得完全正确，
拼出来也是合法 JSON，而它报的永远是对话模型。这种错**不崩、不报错**，
症状是「装没装嵌入模型，探活里一模一样」—— 恰好是这个字段要终结的困惑本身。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import kotlin_fn_span  # noqa: E402


def _raw_fn_span(src: str, name: str, start_line: int):
    """按 `{` 配对切出 `name` 函数在**原文**里的区间（保留字符串字面量）。

    `_fnbody.kotlin_fn_span` 返回的是剥过注释与字符串的代码，不能用来找字段名。
    这里只用它定位**起止行**，再回到原文按行切 —— 判据因此读的是真实现。
    """
    code, sline = kotlin_fn_span(src, name)
    if code is None:
        return None, -1
    # 剥注释版与原文行数一一对应（_strip 只替换字符、保留换行）
    depth = 0
    started = False
    end_line = sline
    for i in range(sline - 1, len(src.split('\n'))):
        pass
    # 直接对剥注释版做括号配对得到结束行号
    d = 0
    for idx, ch in enumerate(code):
        if ch == '{':
            d += 1
            started = True
        elif ch == '}':
            d -= 1
            if started and d == 0:
                # code 内部行偏移 -> 原文件行号
                end_line = sline + code.count('\n', 0, idx)
                break
    lines = src.split('\n')
    if end_line > len(lines):
        return None, -1
    return '\n'.join(lines[sline - 1:end_line]), end_line


def main():
    src_path = sys.argv[1]
    src = Path(src_path).read_text(encoding='utf-8')
    # 必须用 **span**（剥注释但保留原始区间再切回原文），不能用函数体：
    # `_fnbody` 的剥注释实现会把**字符串字面量**也抹成 `""`，而本判据要找的
    # 恰恰是 JSON 字段名（`"embed_loaded":`）它们全在字符串里 —— 用函数体会
    # 让每个字段名都"找不到"，那是**假红**（守卫读的不是实现）。
    _, start_line = kotlin_fn_span(src, 'healthJson')
    if start_line < 0:
        print("FAIL  找不到 healthJson 的函数体（判据锚点失效，不可静默放过）")
        return 1
    lines = src.split('\n')
    # 从函数头往下取到函数结束：用剥注释版重新定位结束行，再回到原文切片
    raw_body, end_line = _raw_fn_span(src, 'healthJson', start_line)
    if raw_body is None:
        print("FAIL  无法按括号配对切出 healthJson 的原文区间")
        return 1
    body = raw_body
    line = start_line

    # 逐条：(字段名, 该字段取值必须出现的表达式, 说明)
    checks = [
        ('embed_loaded', 'LlmEngine.hasEmbedModel',
         'embed_loaded 必须取 LlmEngine.hasEmbedModel'),
        ('embed_model', 'LlmEngine.embedModelDesc()',
         'embed_model 必须取 LlmEngine.embedModelDesc()（不是 modelDesc()）'),
        ('embed_dim', 'LlmEngine.embedDim',
         'embed_dim 必须取 LlmEngine.embedDim'),
    ]
    bad = 0
    for field, needle, desc in checks:
        # 该字段必须以 "字段":$var 的形式出现在拼接里
        if f'"{field}":' not in body:
            print(f"FAIL  {desc} —— 字段 {field} 没出现在 healthJson 的拼接里")
            bad += 1
            continue
        # 找到那个字段后面的取值变量名，再断言它的赋值来自期望表达式
        m = re.search(rf'"{field}":\$([A-Za-z_][A-Za-z0-9_]*)', body)
        if not m:
            print(f"FAIL  {desc} —— 字段 {field} 的取值不是变量插值（形如 $x）")
            bad += 1
            continue
        var = m.group(1)
        # 变量的赋值必须来自期望的表达式
        if not re.search(rf'\bval\s+{var}\s*=\s*[^\n]*{re.escape(needle)}', body):
            print(f"FAIL  {desc} —— 字段 {field} 取的变量 {var} 不是来自 {needle}")
            bad += 1
            continue
        print(f"PASS  {desc}（字段 {field} <- {var} <- {needle}）")

    # 反向：三个字段不许都取自对话那一份的同一个变量（复制品的典型形态）
    vars_used = re.findall(r'"(?:embed_loaded|embed_model|embed_dim)":\$([A-Za-z_][A-Za-z0-9_]*)', body)
    if len(set(vars_used)) < 2:
        print(f"FAIL  三个嵌入字段取自同一个变量 {vars_used} —— 那是对话模型读数的复制品")
        bad += 1
    else:
        print("PASS  三个嵌入字段取值各自独立（不是同一个变量的复用）")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
