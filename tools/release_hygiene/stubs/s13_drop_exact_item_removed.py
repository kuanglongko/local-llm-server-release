"""桩⑬：`DROP_EXACT` 少了一项（帐目② 的原始形态）。

`fix(release)` 那次把 `docs/REVIEW-110-SUMMARY.md` 加进 `DROP_EXACT`
—— 因为这份小结被误发进了发布包。若有人把这行删掉（或以后再加的开发期
文档只改别处、忘了这里），**没有任何东西会红**：发布器照旧"清洗过了"，
只是把这份小结原样发出去。

取的是**真正的旧写法**：早期 `DROP_EXACT` 只有三项，SUMMARY 不在里面。
"""
OLD = ("DROP_EXACT = {'CHANGELOG.md', 'docs/RELEASE-WORKFLOW.md', "
       "'docs/REVIEW-110-SUMMARY.md', 'HTP-STATUS.md'}")
NEW = "DROP_EXACT = {'CHANGELOG.md', 'docs/RELEASE-WORKFLOW.md', 'HTP-STATUS.md'}"
assert OLD in s, '桩⑬：没找到 DROP_EXACT 的现行写法'
s_out = s.replace(OLD, NEW, 1)
