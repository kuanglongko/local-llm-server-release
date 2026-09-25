"""桩⑫：守卫取 `blocked` 右侧时退回**截断式**正则。

这是守卫**第一版真的写错过**的形态：`blocked\\s*=\\s*\\((.*?)\\)\\s*$` 会在
第一个 `)` 处截断，而右侧本身含括号（`(bool(dirty) and ...)`），截断后只剩
前半段 —— `blocked_selfcheck` 落在那半段之外，于是在**写对了的源码**上
报假故障。这一根桩钉的是"不许退回去"。

⚠ 切点必须用**唯一**锚：`emit('SELFCHECK_IN_BLOCKED'` 在真源码里出现两次
（`if not body_main:` 那支一次、`else` 那支一次），取 `index` 会切到**第一支**，
把 else 那段的括号平衡逻辑剥掉、露出一个裸 `else:` —— 守卫当场语法错误，
报出来的是 SyntaxError 而不是本桩要钉的那条判据（第一版实测如此）。
"""
I = "    # 括号平衡地取整段赋值右侧"
J = "    emit('SELFCHECK_IN_BLOCKED',"
i = s.index(I)
j = s.index(J, i)
s_out = (s[:i]
         + "    m = re.search(r'blocked\\s*=\\s*\\((.*?)\\)\\s*$', body_main, re.M | re.S)\n"
         + "    blk = m.group(1) if m else ''\n"
         + s[j:])
