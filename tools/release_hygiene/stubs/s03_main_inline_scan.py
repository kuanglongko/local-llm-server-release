"""桩③：主流程的卫生门禁回到内联循环（J-3 之前的真写法）。

旧写法（`main` 第 3 步）是一段内联的 for 循环 + `HYGIENE_SKIP`/`HYGIENE_EXT`
判断。抽成共享函数的**唯一**意义就是让自检能用同一份判据 —— 主流程绕回内联，
自检与门禁就又分叉了。
"""
OLD = "    dirty, _scanned = hygiene_hits(built)"
NEW = "    dirty = []\n    _scanned = 0  # 旧写法：内联再扫一遍"
assert OLD in s
s_out = s.replace(OLD, NEW, 1)
