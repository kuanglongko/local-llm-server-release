"""桩⑤：把「自检」整块挪到 `blocked` 赋值**之后**。

这正是"存在但无效"的另一种形态：自检被调了、也打印了，但等到它跑的时候，
本次发布的放行决定**已经做完了** —— 它只能报告，不能拦。
（旧写法里根本没有这块，所以这一根桩取的是"挪位"而不是"删掉"。）
"""
HEAD = '\n    blocked = (bool(drift)'
i = s.index('    if not args.skip_hygiene_selfcheck:')
j = s.index(HEAD)
seg = s[i:j]
rest = s[j:]
k = rest.index('    # 4) 落到 release 工作树')
s_out = s[:i] + rest[:k] + seg + rest[k:]
