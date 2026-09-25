"""桩⑩：README 不再点明 build.yml **不跑** `tools/run_*`。

只说"有两条流水线"不够：build.yml 与 .cnb.yml 在读者眼里都叫"CI"，
不点破"这条不跑任何 tools/run_*"，两条流水线的**职责差**仍然是隐形的 ——
而"改坏了什么会在哪一环被拦"恰恰由这个差决定。
"""
OLD = '只做构建，**不跑任何**\n`tools/run_*`。push 到 `main`'
NEW = '只做构建。push 到 `main`'
assert OLD in s
s_out = s.replace(OLD, NEW, 1)
