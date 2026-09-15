import io, sys

CODE, LINE, BLOCK, STR, CHR = 0, 1, 2, 3, 4


def check(path):
    s = io.open(path, encoding="utf-8").read()
    st = CODE
    stack = []          # 括号栈，元素 (char, line, kind-of-scope)
    interp = []         # 字符串插值 ${ 深度栈
    line = 1
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c == "\n":
            line += 1
            if st == LINE:
                st = CODE
            i += 1
            continue
        if st == LINE:
            i += 1
            continue
        if st == BLOCK:
            if c == "*" and i + 1 < n and s[i + 1] == "/":
                st = CODE
                i += 2
            else:
                i += 1
            continue
        if st == CHR:
            if c == "\\":
                i += 2
            elif c == "'":
                st = CODE
                i += 1
            else:
                i += 1
            continue
        # CODE / STR
        if st == CODE:
            if c == "/" and i + 1 < n and s[i + 1] == "/":
                st = LINE
                i += 2
                continue
            if c == "/" and i + 1 < n and s[i + 1] == "*":
                st = BLOCK
                i += 2
                continue
            if c == '"' and s[max(0, i - 2):i + 2] == '"""':
                # 三引号：跳到下一个三引号（粗略，项目里未用）
                j = s.find('"""', i + 3)
                if j < 0:
                    return ["%s:%d 三引号未闭合" % (path, line)]
                line += s.count("\n", i, j)
                i = j + 3
                continue
            if c == '"':
                st = STR
                i += 1
                continue
            if c == "'":
                st = CHR
                i += 1
                continue
            if c in "([{":
                stack.append((c, line))
                i += 1
                continue
            if c in ")]}":
                if not stack:
                    return ["%s:%d 多余闭括号 %c" % (path, line, c)]
                o, ol = stack.pop()
                if "([{".index(o) != ")]}".index(c):
                    return ["%s:%d %c 与 %d 行的 %c 不匹配" % (path, line, c, ol, o)]
                i += 1
                continue
            i += 1
            continue
        # inside string literal
        if c == "\\":
            i += 2
            continue
        if c == "$" and i + 1 < n and s[i + 1] == "{":
            interp.append(line)
            stack.append(("{", line))
            i += 2
            continue
        if c == "}":
            if interp:
                if not stack:
                    return ["%s:%d 插值 } 无配对" % (path, line)]
                o, ol = stack.pop()
                if o != "{":
                    return ["%s:%d 插值 } 命中 %d 行的 %c" % (path, line, ol, o)]
                interp.pop()
                i += 1
                continue
            # Kotlin 字符串内裸 } 合法（只有 $ 和 " 有特殊含义），按字面量跳过
            i += 1
            continue
        if c == '"':
            st = CODE
            i += 1
            continue
        # 字符串内出现 ( 或 ) 不计入
        i += 1
    errs = []
    if stack:
        for o, ol in stack:
            errs.append("%s: 未闭合 %c (起于第 %d 行)" % (path, o, ol))
    if st != CODE:
        errs.append("%s: 词法状态未归位 st=%d（可能未闭合字符串/注释）" % (path, st))
    return errs


targets = sys.argv[1:]
bad = 0
for t in targets:
    e = check(t)
    if e:
        bad = 1
        for x in e:
            print("FAIL " + x)
    else:
        print("ok   %-22s %d 行" % (t.split("/")[-1], len(io.open(t, encoding='utf-8').read().splitlines())))
sys.exit(bad)
