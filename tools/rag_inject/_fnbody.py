"""从 C++ 源码里按**真括号配对**抽出一个函数体；只看实现，不看注释。

为什么不用正则切 `{`...`}`：函数体里含字符串字面量（日志里就有 `{`/`}`）、
含块注释、含嵌套的 lambda / 初始化列表。按括号配对 + 跳过字符串与注释才是
唯一不会切歪的做法 —— 而切歪的后果是"判据在别人身上跑"，
那一族假绿本仓库已经踩过（run_schema_sampler_guard 的 awk 段）。
"""
import re


def _strip(src: str) -> str:
    """剥掉块注释 / 行注释 / 字符串字面量，保留换行以便行号定位。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c in '"\'':
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                j += 1
            out.append('""')
            i = j
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def fn_body(src: str, name: str):
    """按真括号配对取出 `name` 的函数体（不含签名）。

    返回 (body, start_line)；找不到返回 (None, -1)。
    先剥注释/字符串再找签名，避免注释里提到函数名就命中。
    """
    code = _strip(src)
    # 找到 `name(` 后面跟参数表、再跟 `{` 的那处定义
    for m in re.finditer(re.escape(name) + r'\s*\(', code):
        k = m.end() - 1
        depth = 0
        # 跳过参数表
        while k < len(code):
            if code[k] == '(':
                depth += 1
            elif code[k] == ')':
                depth -= 1
                if depth == 0:
                    k += 1
                    break
            k += 1
        # 跳过空白，看是不是 `{`（定义），还是 `;`（声明）
        while k < len(code) and code[k] in ' \t\r\n':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        # 括号配对取函数体
        depth = 0
        start = k
        while k < len(code):
            if code[k] == '{':
                depth += 1
            elif code[k] == '}':
                depth -= 1
                if depth == 0:
                    body = code[start:k + 1]
                    return body, code.count('\n', 0, start) + 1
            k += 1
    return None, -1


def kotlin_fn_body(src: str, name: str):
    """Kotlin 侧：取 `fun name(...) ... { ... }` 或表达式体的函数体。

    表达式体（`fun f() = synchronized(lock) { ... }`）同样按括号配对，
    所以要在签名之后找到**第一个 `{`** 再配对 —— 对本仓库的
    `loadEmbedModel(...) = synchronized(embedLock) { ... }` 形态有效。
    """
    code = _strip(src)
    for m in re.finditer(r'\bfun\s+' + re.escape(name) + r'\s*[\(<]', code):
        k = m.end() - 1
        depth = 0
        opened = False
        while k < len(code):
            if code[k] == '(':
                depth += 1
                opened = True
            elif code[k] == ')':
                depth -= 1
                if opened and depth == 0:
                    k += 1
                    break
            k += 1
        # 从参数表之后找第一个 `{`（跳过返回类型、`=`、`synchronized(x)` 等）
        while k < len(code) and code[k] != '{' and code[k] != ';':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        depth = 0
        start = k
        while k < len(code):
            if code[k] == '{':
                depth += 1
            elif code[k] == '}':
                depth -= 1
                if depth == 0:
                    return code[start:k + 1], code.count('\n', 0, start) + 1
            k += 1
    return None, -1


def kotlin_fn_span(src: str, name: str):
    """返回 `fun name ... { ... }` 的**完整文本**（含签名与 `synchronized(lock)` 这类
    表达式体的前缀），不含前导注释。

    为什么需要它（而不是只取函数体）：本仓库的嵌入函数写成表达式体
    `fun embed(...) = synchronized(embedLock) { ... }` —— 锁的**名字在 `{` 之前**。
    只取函数体会把 `synchronized(embedLock)` 漏掉，于是"它持哪把锁"根本判不到
    （守卫会假红，正是本轮踩到的）。判据要的是"这一整段声明里出现了哪把锁"。
    """
    code = _strip(src)
    for m in re.finditer(r'\bfun\s+' + re.escape(name) + r'\s*[\(<]', code):
        k = m.end() - 1
        depth = 0
        opened = False
        while k < len(code):
            if code[k] == '(':
                depth += 1
                opened = True
            elif code[k] == ')':
                depth -= 1
                if opened and depth == 0:
                    k += 1
                    break
            k += 1
        while k < len(code) and code[k] != '{' and code[k] != ';':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        depth = 0
        start = k
        while k < len(code):
            if code[k] == '{':
                depth += 1
            elif code[k] == '}':
                depth -= 1
                if depth == 0:
                    head_start = m.start()
                    return code[head_start:k + 1], code.count('\n', 0, head_start) + 1
            k += 1
    return None, -1


def _main() -> int:
    """CLI：从 stdin 读源码，把 `argv[1]` 那个函数的**函数体**打到 stdout。

    用途：守卫里做 `printf '%s' "$CODE" | python3 _fnbody.py buildIndex | grep -q X`。
    为什么走 CLI 而不是让每个 check_*.py 都 import：本守卫的判据是**一行一条**
    的 shell 断言（点得出是哪条红），把抽取逻辑塞进 CLI 才让每条断言都能单独跑、
    单独打桩。找不到函数时打空串并返回 2 —— 空串会让下面的 grep 全部假红，
    这正是我们要的（函数改名/拆掉时守卫必须红，而不是静默变绿）。

    Kotlin 与 C++ 都能处理：先试 C++ 的 `fn_body`，取不到再试 Kotlin 的。
    """
    import sys
    if len(sys.argv) != 2:
        sys.stderr.write("用法：<source> | _fnbody.py <fnName>\n")
        return 2
    src = sys.stdin.read()
    name = sys.argv[1]
    body, _line = fn_body(src, name)
    if body is None:
        # Kotlin：表达式体函数（`fun f() = synchronized(x) { ... }`）在
        # `kotlin_fn_body` 里也拿得到（它跳过 `=` 与 `synchronized(...)` 找第一个 `{`）。
        body, _line = kotlin_fn_body(src, name)
    if body is None:
        sys.stderr.write("找不到函数体：%s\n" % name)
        return 2
    sys.stdout.write(body)
    return 0


if __name__ == '__main__':
    raise SystemExit(_main())
