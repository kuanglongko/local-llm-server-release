#!/usr/bin/env python3
"""全仓扫描：还有没有「字面量式空模板」（= 让库按模型自选）的残留。

只管一件事，且**去注释后**再判 —— 注释里解释"旧写法叫什么"不该让守卫变红。
（模块 I 的 PR-1 踩过"把注释算成实现"的坑，这里一并防住。）

用法：python3 tools/grammar_scope/scan_template_literals.py <源码目录>
退出码：0 = 无残留；1 = 有残留（打印文件:行）。
"""
import os
import re
import sys

# 只认**真代码**里的字面量空模板。去掉行注释与整行块注释后再匹配。
PAT = re.compile(r'chatTemplateOf\(\s*(?:null|""|\'\')\s*\)')


def main():
    if len(sys.argv) < 2:
        print("用法: scan_template_literals.py <源码目录>")
        return 2
    root = sys.argv[1]
    bad = []
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith('.kt'):
                continue
            path = os.path.join(dirpath, f)
            for n, line in enumerate(open(path, encoding='utf-8').read().splitlines(), 1):
                st = line.lstrip()
                if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
                    continue
                i = line.find('//')
                code = line[:i] if i >= 0 else line
                if PAT.search(code):
                    bad.append("%s:%d" % (path, n))
    if bad:
        print("失败：仍有字面量式空模板（让库按模型自选）：" + ", ".join(bad))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
