"""桩⑨：README 先点名 build.yml、`.cnb.yml` 排到后面（口径颠倒）。

判据要的是**顺序关系**：讲质量门禁时，真跑 `tools/run_*` 的那份必须**先**被点名。
只断言"文件里出现过 .cnb.yml"挡不住这一根桩 —— 它出现过，但排在后面，
读者第一眼读到的仍是"CI = build.yml"。
"""
A = '**质量门禁：`.cnb.yml`（CNB 流水线）**'
assert A in s
s_out = (s.replace(A, '**质量门禁：CNB 流水线**', 1).rstrip()
         + '\n\n<!-- 见 .cnb.yml -->\n')
