# MiniCPM5 chat 模板（测试夹具）

`chat_template.jinja` 是 **MiniCPM5-2B 的真实 chat 模板**，作为离线单测的固定输入。

- 来源：模型仓库的 `chat_template.jinja`
- 尺寸：**9060 字节**（与真机日志里的 `chat_template_len=9060` 一致）
- md5：`87da9b132dd68fd3158b392429290a6d`
- 用途：`tools/run_generation_prompt_tests.py` 用它复核
  `content_len - text_len = 8` 的数值来源与「解码丢标记」的残骸形状

**为什么是"真模板"而不是手写简化版**：本轮四条成因里有两条（③④）的结论完全建立在
模板的**具体字形**上——尾部的 `add_generation_prompt` / `enable_thinking` 分支、
以及 `<function name="...">` / `<param name="...">` 这两组标记。
手写简化版会把这些细节"顺手写对"，于是测不出真实故障，反而给出虚假的安全感。
md5 固定是为了防止它被无意替换（换掉就等于换掉了断言的前提）。
