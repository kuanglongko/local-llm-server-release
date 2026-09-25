"""桩②：自检不调共享扫描器，自己另写一份近似。

这是判据漂移的经典起点：两处各写一份"看起来一样"的扫描，改了一处忘了另一处，
于是自检与门禁对同一份产物给出不同结论 —— 而"谁对"没有任何东西能判。
"""
OLD = "        hits, _ = hygiene_hits([(path, _m, cleaned.encode('utf-8'))])"
NEW = "        hits = []  # 旧写法：自己再扫一次近似"
assert OLD in s
s_out = s.replace(OLD, NEW, 1)
