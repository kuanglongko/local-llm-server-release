"""桩①：共享扫描器被掏空（回到"两处各写一份近似"的旧写法）。

旧写法里 `HYGIENE` 的遍历散在 main 内联循环里、自检另写一份。这一根桩取的是
"共享函数还在、判据本体已经不在它里面"——`HYGIENE_EXT` / `HYGIENE_SKIP` 的
对照全部移出该函数体。这是本次要钉的形态：**名字在、判据不在**。
"""
import re
s_out = re.sub(r'def hygiene_hits\(built\):.*?(?=\ndef selfcheck_hygiene)',
               'def hygiene_hits(built):\n    return [], 0\n\n', s, flags=re.S)
