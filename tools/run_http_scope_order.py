#!/usr/bin/env python3
"""局部变量「先用后声明」检查（read-before-declare）。

═══════════════════════════════════════════════════════════════════════════
为什么需要这个（而不是再加一条 grep）
═══════════════════════════════════════════════════════════════════════════
2026-09-22 CI 红在 `HttpApi.kt`：

    HttpApi.kt:434:53: error: unresolved reference 'corsOrigin'.
    HttpApi.kt:440:55: error: unresolved reference 'corsOrigin'.

成因：B-2/B-3 把 400/413 两条拒绝**提到路由之前**，但 `val corsOrigin = ...`
的声明留在原地（路由判定的注释块下面）。于是拒绝路径成了它的读者，
而读者**排在声明之前**。Kotlin 的局部变量没有提升 → 编译不过。

为什么那一轮的 22 条判据一条都没拦住 —— 它们**全是 grep 存在性/邻接断言**，
只回答"这段文字在不在、挨着谁"，从不回答"这个名字在被读的那一刻可见吗"。
对这个形态它们**原理上**无效：`corsOrigin` 在文件里确实出现过 20 次，
`writeJson` 与 `corsOrigin` 也确实常常挨着 —— 每条断言都能为真，
而文件根本编不过。

所以本检查做的是**真词法顺序**：把每个函数体切成语句序列，收集
`val <名> =` 的声明行号，再找出对同名标识符的**读**，要求
`首次读的行号 > 声明行号`。这不是编译器（不做类型/作用域/重载解析），
但恰好覆盖"局部变量顺序写反"这一类，而这类正是静态 grep 的盲区。

判据有意做成**窄**的：只查函数体内**顶层**（同一缩进层级）的 `val`，
只把"在声明之前出现同名标识符"当失败。宁可漏（嵌套块、委托属性、
同名遮蔽），不要误报 —— 一个会误报的守卫很快会被 `|| true` 静音，
那时它保护的与它自己都一起失效。

用法：python3 tools/run_http_scope_order.py <kt 文件> [更多文件...]
退出码：0 = 无 read-before-declare；1 = 有（打印文件:行:名字）
"""
import re
import sys

# 成员声明的开头：缩进 4 空格 + 可选修饰符 + fun/object/class/val/var/companion。
# 只在**成员层级**切函数体，不切函数体内的嵌套块 —— 否则函数内部任何
# `val x = ...` 都会被当成"新函数开始"，前缀被切短，漏报。
# 注意必须带上修改器 `@Volatile private val` 这类（否则文件属性会被当成函数体）。
MEMBERS = re.compile(
    r"^(?:    |\t)(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:private|internal|public|protected|override|final|open|abstract|"
    r"const|lateinit|inline|suspend|operator|infix|external|companion)\s+)*"
    r"(?:fun|object|class|val|var|interface|enum\s+class|data\s+class)\b"
)

# 同一行就写完了整个函数的（`private fun emitLog(s: String) { ... }`）不算边界 ——
# 它没有"函数体"，碰撞风险为零，把它当边界反而会把后面的真函数切掉一段。
def _single_line(ln):
    return ln.count("(") > 0 and ln.count("(") == ln.count(")") and "{" in ln and ln.rstrip().endswith("}")

VAL_DECL = re.compile(r"^\s*val\s+([A-Za-z_]\w*)\s*(?::[^=]+)?=")


def funcs(lines):
    """产出 (起始行号, 函数体行号列表)。

    只在**成员层级**切（见 [MEMBERS]）：函数体内的语句不参与切分，
    它们本来就在同一个"函数体"里，正是要检查的那种"同一作用域内的顺序"。
    """
    starts = [i for i, ln in enumerate(lines)
              if MEMBERS.match(ln) and not _single_line(ln)]
    for k, s in enumerate(starts):
        e = starts[k + 1] if k + 1 < len(starts) else len(lines)
        yield s, list(range(s, e))


def check(path):
    src = open(path, encoding="utf-8").read()
    # 先把注释块与块注释剥掉（别把注释里提到的名字当成读者/声明）。
    # 逐行处理：`//` 之后截断；`/* ... */` 用状态机跨行剥。
    lines = []
    in_block = 0
    for ln in src.split("\n"):
        out, i = [], 0
        while i < len(ln):
            if in_block:
                j = ln.find("*/", i)
                if j < 0:
                    i = len(ln)
                else:
                    in_block = 0
                    i = j + 2
            else:
                j = ln.find("/*", i)
                k = ln.find("//", i)
                if k >= 0 and (j < 0 or k < j):
                    out.append(ln[i:k])
                    i = len(ln)
                elif j >= 0:
                    out.append(ln[i:j])
                    in_block = 1
                    i = j + 2
                else:
                    out.append(ln[i:])
                    i = len(ln)
        lines.append("".join(out))

    bad = []
    for _s, body in funcs(lines):
        decl = {}
        for n in body:
            m = VAL_DECL.match(lines[n])
            if m:
                decl.setdefault(m.group(1), n)
        if not decl:
            continue
        for name, dn in decl.items():
            # 声明之前出现过这个名字 = read-before-declare（只看整词）。
            # 只截「本函数声明之前」的那一小段 —— 不要越到上一个函数里去，
            # 同名参数/局部变量在上一个函数里出现是完全正常的（第一版就是这么
            # 误报 19 条的：把整个 body 列表都当前缀，扫到了前一个函数的 `r`）。
            pat = re.compile(r"\b%s\b" % re.escape(name))
            for n in body:
                if n >= dn:
                    break
                ln = lines[n]
                # 排除"名字出现在**声明位**"的几种形态 —— 它们不是读：
                #   · 命名实参 / 具名调用：`method = ""`、`alias = ...`
                #   · 字符串键：`put("id", ...)`（名字只是键名，不是标识符读）
                #   · lambda 形参：`{ alias -> ... }`（它就是本块的声明）
                #   · 函数签名行（形参表）：`fun f(method: String)`
                # 这类形态在同一个函数体里大量存在，不排除会淹掉真信号。
                if re.search(r"\b%s\s*=" % re.escape(name), ln):
                    continue
                if re.search(r'"[^"]*\b%s\b[^"]*"' % re.escape(name), ln):
                    continue
                if re.search(r"\{[^}]*\b%s\s*->" % re.escape(name), ln):
                    continue
                # 跨行 lambda 的形参表（`{ t, asReason ->` 写在下一行）：
                # 往前看一行，若能拼成 `{ <名>[, ...] ->` 就算声明位。
                # 这是上一版唯一剩下的误报（think.feed(piece) { t, asReason ->），
                # 而它恰好说明"贴着一行判"不够 —— lambda 头可以断行。
                joined = ln + " " + (lines[n + 1] if n + 1 < len(lines) else "")
                if re.search(r"\{[^{}]*\b%s\s*[,)]?[^{}]*->" % re.escape(name), joined):
                    continue
                # lambda 形参也可能是**前面若干行**开的头：`{` 与形参表可以断开，
                # 形参表本身也能换行。往上最多看 3 行（再多会开始吃掉相邻语句，
                # 变成"恒真"—— 那就等于把这条判据关掉了）。
                for back in range(1, 4):
                    if n - back < 0:
                        break
                    joined2 = " ".join(lines[n - back:n + 1])
                    if re.search(r"\{[^{}]*\b%s\s*[,)]?[^{}]*->" % re.escape(name), joined2):
                        break
                else:
                    pass
                if n > 0 and any(re.search(r"\{[^{}]*\b%s\s*[,)]?[^{}]*->" % re.escape(name),
                                 " ".join(lines[max(0, n - back):n + 1]))
                           for back in range(1, 4)):
                    continue
                if re.search(r"\bfun\b[^)]*\b%s\b\s*:" % re.escape(name), ln):
                    continue
                # 形参表里的默认值也算"声明位"：`fun f(x: Int = y)` 里的 y 是读，
                # 但 `fun f(name: T)` 里的 name 不是 —— 上面两条已覆盖。
                if pat.search(ln):
                    bad.append((path, n + 1, name, dn + 1))
                    break
    return bad


def main(argv):
    if len(argv) < 2:
        print("用法: python3 tools/run_http_scope_order.py <kt 文件> [...]")
        return 2
    allbad = []
    for p in argv[1:]:
        allbad += check(p)
    if allbad:
        for p, rl, name, dl in allbad:
            print("FAIL  %s:%d 局部变量 '%s' 在声明（第 %d 行）之前被读到"
                  % (p, rl, name, dl))
        print("== 局部变量顺序检查：FAIL %d ==" % len(allbad))
        return 1
    print("== 局部变量顺序检查：PASS（无 read-before-declare）==")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
