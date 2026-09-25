"""桩⑦：README 的版本号清洗规则被删掉 —— **J-3 的原始状态**。

真旧写法就是如此：`release-rules.json` 里 README 的规则覆盖不到正文里的日常线
版本号（实测三处），套用清洗后门禁照样报脏，每次发布都被挡下 —— 而报出来的
是"发布失败"，不是"这里有一条规则该补"。

判据是**按效果**锚的（清洗后不得再有残留），不是锚某个具体版本号字面量：
这里删掉规则集里的**全部** `v0.9` 版本号规则，残留必然回来。
"""
import json
d = json.loads(s)
d['rules'] = [r for r in d['rules']
              if not (r['path'] == 'README.md' and 'v0.9' in r['old'])]
s_out = json.dumps(d, ensure_ascii=False, indent=1)
