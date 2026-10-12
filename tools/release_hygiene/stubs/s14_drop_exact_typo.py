"""桩⑭：`DROP_EXACT` 里有一个名字拼错（差一个字符）。

这是三类里最隐蔽的一种：清单"看着齐全"（几项都在、数量也对），
但拼错的那一项**恒不命中** —— 发布器照旧报"已清洗"，而那份文档
就是发不出去的。存在性断言（"文件里出现过 HTP-STATUS"）抓不到它，
只有"每一项真在磁盘上"这条结构判据能抓。

锚的是真实文件名的**单字符改动**（`HTP-STATUS.md` -> `HTP-STATUSX.md`）。
"""
OLD = "'HTP-STATUS.md'"
NEW = "'HTP-STATUSX.md'"
assert s.count(OLD) >= 1, '桩⑭：没找到 HTP-STATUS.md 字面量'
s_out = s.replace(OLD, NEW, 1)
