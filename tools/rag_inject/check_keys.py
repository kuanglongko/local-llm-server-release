#!/usr/bin/env python3
"""断言「检索开关」与「当前知识库」用的是**两个不同的** prefs 键。

合并成一个键的后果是静默的：勾选开关会顺手把「当前库」写成一个布尔值、
或选库会顺手改变开关状态 —— 两者都不会崩，只是"下次打开发现设置变了"，
而且用户多半会归因成"App 自己重置了"。

用法：check_keys.py <RagInject.kt>
"""
import re
import sys


def main():
    src = open(sys.argv[1], encoding='utf-8').read()
    m1 = re.search(r'const\s+val\s+KEY_ENABLED\s*=\s*"([^"]+)"', src)
    m2 = re.search(r'const\s+val\s+KEY_CURRENT\s*=\s*"([^"]+)"', src)
    if not m1 or not m2:
        print("FAIL  取不到两个键的字面量（判据锚点失效：常量被改名或删掉了）")
        return 1
    if m1.group(1) == m2.group(1):
        print("FAIL  开关与当前库共用同一个键「%s」—— 两者会互相覆盖" % m1.group(1))
        return 1
    if not m1.group(1) or not m2.group(1):
        print("FAIL  键为空串（写进 prefs 的是空键名，读的那一侧取不到）")
        return 1
    print("PASS  开关键「%s」与当前库键「%s」分开" % (m1.group(1), m2.group(1)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
