"""桩⑮：`DROP_EXACT` 定义了却不再被读（规则成装饰品）。

最难发现的一种"写了但接不上"：集合还在、名字都对、还长得像生效，
但 `is_dropped()` 改成不引用它 —— 于是整份清单**一行都没用上**，
发布器再也不会丢掉任何开发期文档。

与 D 轮 `kProbeFlagProps`（注释说会被读取、实际 0 处）、
模块 J 的 `--skip-hygiene-selfcheck`（写进 help 却没注册）同一形态。
"""
OLD = "return path in DROP_EXACT or any(path.startswith(p) for p in DROP_PREFIX)"
NEW = "return any(path.startswith(p) for p in DROP_PREFIX)  # 桩⑮：不再读 DROP_EXACT"
assert OLD in s, '桩⑮：没找到 is_dropped 的现行写法'
s_out = s.replace(OLD, NEW, 1)
