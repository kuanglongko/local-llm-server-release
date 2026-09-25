"""桩⑪：参数写了 help 却没注册（死参数）。

`--skip-hygiene-selfcheck` 在正文里被读到（`args.skip_hygiene_selfcheck`），
但 argparse 里没注册 —— 于是它**永远是 False**，"跳过自检"这个能力写了却不可用。
这类"写了但接不上"与 D 轮的 `kProbeFlagProps`（注释说会被读取、实际 0 处读取）
是同一形态。
"""
OLD = ("    ap.add_argument('--skip-hygiene-selfcheck', action='store_true',\n"
       "                    help='跳过\"清洗规则 vs 卫生门禁\"自检（仅在复现历史发布树时使用）')\n")
assert OLD in s
s_out = s.replace(OLD, '', 1)
