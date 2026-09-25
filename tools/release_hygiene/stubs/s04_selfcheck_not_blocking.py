"""桩④：自检只打印、不进 `blocked`（"存在但无效"）。

`selfcheck_hygiene()` 被调了、也打印了，但放行判据里没有它 —— 那么它一点都不拦。
这与模块 D 的 `probe_bootstrap_flush()`（函数在、恒写 0 字节）、
模块 H 的 `grep -q 'fun escapeForScript'`（函数在、用错位置）同一族：
**存在性断言的绿区里藏着"存在但无效"**。
"""
OLD = ("    blocked = (bool(drift) or (bool(dirty) and not args.waive_hygiene)\n"
       "               or blocked_selfcheck)")
NEW = "    blocked = bool(drift) or (bool(dirty) and not args.waive_hygiene)"
assert OLD in s
s_out = s.replace(OLD, NEW, 1)
