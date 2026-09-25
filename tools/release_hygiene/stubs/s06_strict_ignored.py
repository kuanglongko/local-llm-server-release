"""桩⑥：`--hygiene-selfcheck-strict` 收下了却**不参与阻断判定**（死参数）。

真旧写法里根本没有这个参数；这一根桩取的是"注册了、也传了，但决定
`blocked_selfcheck` 的那一行不读它" —— 红线形同虚设：配 `--hygiene-selfcheck-strict 5`
与不配完全一样。

⚠ 这一根桩**只能**动「决定 blocked_selfcheck 的那一行」，不能只是往别处塞一个
同名的无效引用：守卫的判据是"赋值行的**任一**行带红线参数"，塞一个同名的
`print(args.hygiene_selfcheck_strict)` 会**让判据仍然 PASS**（第一版就是这么写的，
实测全绿）—— 那说明那样写根本不是在测"红线有没有用"。
"""
OLD = "            blocked_selfcheck = len(residual) > args.hygiene_selfcheck_strict"
NEW = "            blocked_selfcheck = bool(residual)   # 旧写法：红线不参与判定"
assert OLD in s
s_out = s.replace(OLD, NEW, 1)
