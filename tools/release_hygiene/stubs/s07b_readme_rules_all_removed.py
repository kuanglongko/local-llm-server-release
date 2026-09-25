"""桩⑦b：README 的规则**全删**。

桩⑦ 只动三条，可能被"另一侧也漂了"掩盖。这一根桩把 README 的规则全删，
钉住"规则集里确实有 README 的条目"那条前置判据 —— 没有它，桩⑦ 会在
"README 根本没有规则"时给出一个空洞的绿。
"""
import json
d = json.loads(s)
d['rules'] = [r for r in d['rules'] if r['path'] != 'README.md']
s_out = json.dumps(d, ensure_ascii=False, indent=1)
