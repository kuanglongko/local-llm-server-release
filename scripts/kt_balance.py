#!/usr/bin/env python3
"""Kotlin 括号平衡检查：正确处理字符串、行注释、可嵌套块注释。"""
import sys

def check(path):
    s = open(path, encoding='utf-8').read()
    i, depth, instr, tstr = 0, 0, False, False
    line = 1
    while i < len(s):
        c = s[i]
        if c == '\n':
            line += 1; tstr = False; i += 1; continue
        if instr:
            if c == '\\': i += 2; continue
            if c == '"': instr = False
            i += 1; continue
        if tstr:
            if s.startswith('"""', i): tstr = False; i += 3; continue
            if c == '\\': i += 2; continue
            i += 1; continue
        if s.startswith('"""', i): tstr = True; i += 3; continue
        if c == '"': instr = True; i += 1; continue
        if s.startswith('//', i):
            j = s.find('\n', i); i = j if j >= 0 else len(s); continue
        if s.startswith('/*', i):
            d, i = 1, i + 2
            while i < len(s) and d > 0:
                if s.startswith('/*', i): d += 1; i += 2
                elif s.startswith('*/', i): d -= 1; i += 2
                else:
                    if s[i] == '\n': line += 1
                    i += 1
            continue
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth < 0: return f"{path}: extra }} at line {line}"
        i += 1
    return f"{path}: {depth}" if depth else f"{path}: OK"

for p in sys.argv[1:]:
    print(check(p))
