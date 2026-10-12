#!/usr/bin/env python3
"""按**真括号配对**取出 Kotlin 函数体并打印（给 sh 守卫用）。

为什么这个脚本存在：`run_rag_archive_guard.sh` 里那个 `body()` 是 awk 数大括号
配平的写法，它**不认字符串字面量里的花括号**。本轮新增的几个函数恰好在文案里
带 `{`（`"${RagArchive.EMBED_MAX_CHARS} 字"`）—— 于是配平提前闭合，
取出来的"函数体"只有前半段，判据在这半段上恒红，而看起来像"源码写错了"。

这正是本仓库最怕的那类假红/假绿（守卫指向错误的地方）。所以凡是判据要锚
**长函数体**的地方，用这份按真配对取体的实现（复用 `tools/rag_edit/_fnbody.py`，
不另写一套）。

用法：fnbody.py <file> <funcName>   —— 找到打印函数体（含签名），找不到退出码 1。
"""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "rag_edit"))
from _fnbody import _strip  # noqa: E402


def _expr_body(code: str, name: str):
    """**表达式体**函数（`fun f(...): T = ...`）—— 取到下一个同缩进的 `fun` 为止。

    `_fnbody.fn_body` 只认 `{` 开头的函数体，而本仓库里 `RagIndexStore.list` 正是
    表达式体（`fun list(ctx): List<Info> = rootDir(...)`）。判据要锚在它身上的话，
    用那个实现会拿到 None —— 于是守卫恒红，而消息写着"函数体里找不到锚点"，
    看起来像源码写错了（本轮实测踩到）。
    """
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    # 找签名之后第一个 `=`（不是 `==`/`<=`/`>=`/`!=`）作为表达式体的起点。
    # ⚠ `m.end()` 停在 `(` **之后**，depth 得从 1 起算 —— 从 0 起算的话，
    #    参数表之后的第一个 `)` 就把 depth 收成 0 并 break，取到的是空串。
    i = m.end()
    depth = 1
    while i < len(code):
        c = code[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                i += 1
                break
        i += 1
    while i < len(code) and code[i] in " \t\r\n":
        i += 1
    # 跳过**返回类型**（`): List<Info> =`）—— 签名与 `=` 之间必然隔着它，
    # 不跳的话下面的判据恒不成立（`:` != `=`），函数返回 None，守卫恒红。
    while i < len(code) and code[i] != "=":
        # 遇到 `{`（块体）或 `;`/换行到下一个声明，说明不是表达式体。
        if code[i] in "{\n":
            return None
        i += 1
    if i >= len(code) or code[i] != "=" or (i + 1 < len(code) and code[i + 1] == "="):
        return None
    indent = len(code[:m.start()].split("\n")[-1])
    # 到下一个行首缩进 <= 当前缩进的 `fun` 为止（或文件尾）
    rest = code[i:]
    end = len(rest)
    for mm in re.finditer(r"\n([ \t]*)fun\s+", rest):
        if len(mm.group(1)) <= indent:
            end = mm.start()
            break
    return rest[:end]


def _block_body(code: str, name: str):
    """块体函数（`fun f(...): T { ... }`），按真括号配对取到自己的收尾 `}`。

    `_fnbody.fn_body` 只认 `)` 紧跟 `{` 的形态（C 风格），而 Kotlin 的返回类型
    就卡在中间 —— 于是它对带返回类型的函数一律返回 None。这里补上那一步：
    `)` 之后一路跳到第一个 `{`（途中遇到 `=` 说明是表达式体、交给 `_expr_body`）
    或是行尾/`;`（说明是抽象声明，不取）。
    """
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    i = m.end()
    depth = 1
    while i < len(code):
        c = code[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                i += 1
                break
        i += 1
    # 签名之后：跳到 `{`（块体）或 `=`（表达式体）
    while i < len(code):
        c = code[i]
        if c == "{":
            break
        if c == "=":
            return None          # 表达式体，交给 _expr_body
        i += 1
    if i >= len(code):
        return None
    depth = 0
    start = i
    while i < len(code):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
        i += 1
    return None


def main() -> int:
    if len(sys.argv) != 3:
        print("用法: fnbody.py <file> <funcName>", file=sys.stderr)
        return 2
    src = open(sys.argv[1], encoding="utf-8").read()
    code = _strip(src)
    # ① 块体（`fun f(...): T {`）—— 先按**真括号配对**取，取不到再退。
    #    ⚠ 不能直接用 `_fnbody.fn_body`：它要求 `)` 之后**紧跟** `{`，而 Kotlin
    #      的签名与 `{` 之间必然隔着返回类型（`): String? {`）——于是它对本仓库
    #      绝大多数函数返回 None（"锚点漂了"），而那看起来像源码写错了。
    body = _block_body(code, sys.argv[2])
    if body is None:
        body = _expr_body(code, sys.argv[2])
    if body is None:
        print(f"找不到函数 {sys.argv[2]}（锚点漂了）", file=sys.stderr)
        return 1
    sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
