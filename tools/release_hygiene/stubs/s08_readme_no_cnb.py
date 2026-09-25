"""桩⑧：README 不再点真门禁的名字 —— **J-4 的原始状态**。

实测 `grep -c 'cnb.yml' README.md` 在 `main` 上是 **0**：README 全文只提
`.github/workflows/build.yml`，而那份**一次都不跑** `tools/run_*`。
照 README 读的人会以为"CI 只编 APK"，不知道那些守卫在哪跑。
这里把 `.cnb.yml` 改成一个不存在的名字，模拟"读者找不到真门禁"。
"""
s_out = s.replace('.cnb.yml', '.cnb-auto.yml')
