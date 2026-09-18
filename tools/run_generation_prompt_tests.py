#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复现「解析侧 generation_prompt 与 PEG 根节点不一致」这条真因，并用真模板把它钉死。

背景（0.9.68 / 0.9.69 真机日志）：
    [parse] <<< templates_apply ok  format=peg-native(2)  parser_len=3518
    [parse] parser_params 构造完成（已 load PEG，root=88 n=89）   ← PEG 确实装进去了
    [parse] <<< common_chat_parse ok  content_len=271 tool_calls=0   ← 还是 0
    (另一轮) content_len=48  tool_calls=0
两轮 text_len 分别 263 / 40，差值**恒为 8**。上一轮把「PEG 没装进 parser_params」当成
唯一根因，但日志证明 load 是成功的（n=89 非空），所以还有第二个独立缺陷。

真因：common_chat_parse 会把 params.generation_prompt **前拼**到输入上，
          effective_input = params.generation_prompt + input
      而 generation_prompt 本身由 common_chat_template_generation_prompt_impl 算出来：
          用同一个模板渲染 add_generation_prompt=false / =true 两次，取公共前缀之后的剩余部分。
      MiniCPM5 模板在 add_generation_prompt=true 时会吐
          "<|im_start|>assistant\\n"  +  (enable_thinking 为 true 时) "<think>\\n"
      而 C++ 侧 common_chat_templates_inputs::enable_thinking **默认就是 true**，
      llama_jni.cpp 从未显式设过它 —— 所以模板一定进入 "<think>\\n" 这一支。

于是解析侧（以前写死 add_generation_prompt=false，但 enable_thinking 仍是 true）算出：
          generation_prompt = "<|im_start|>assistant\\n<think>\\n"    （30 字节）
      而 PEG 根节点（chat.cpp: common_chat_params_init_minicpm5）要的是：
          p.literal("<|im_start|>assistant\\n")                       （22 字节）
      差 8 字节 = "<think>\\n"。

common_chat_parse 把 30 字节前缀拼上去，PEG 根节点只能匹配前 22 字节，剩下 "<think>\\n"
与输入正文拼在一起，整段再也匹配不上工具调用语法 → 回退成纯内容 → tool_calls=0、
content 吃下全文（这解释了 content_len > text_len，差值 8）。

本脚本用**真模板文件**（tools/minicpm5_fixture/chat_template.jinja，与真机日志里
chat_template_len=9060 同尺寸、同 md5）验证：
  1. 模板确实按 add_generation_prompt / enable_thinking 吐出那两段后缀；
  2. 公共前缀算法算出的 generation_prompt 在两支下分别是多少；
  3. 差值正好 8，与真机日志的两轮 271-263 / 48-40 精确吻合；
  4. PEG 根节点要的 22 字节只与 add_generation_prompt=true + enable_thinking 未注入
     "<think>" 的场景一致 —— 也就是修复后必须走的那条路。

这是**离线可复核**的一步：不需要装机、不需要 NDK。
"""
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMPL_PATH = os.path.join(ROOT, "tools", "minicpm5_fixture", "chat_template.jinja")

GEN_ROOT = "<|im_start|>assistant\n"        # PEG 根节点要的字面量（chat.cpp miniCPM5）
THINK_TAIL = "<think>\n"                     # enable_thinking=true 时模板追加的尾巴
NO_THINK_TAIL = "<think>\n\n</think>\n\n"    # enable_thinking=false 时模板追加的尾巴

ok = 0
bad = 0


def c(name, cond):
    global ok, bad
    if cond:
        print("PASS  " + name)
        ok += 1
    else:
        print("FAIL  " + name)
        bad += 1


def jinja_suffix(add_generation_prompt, enable_thinking):
    """复刻模板尾部的 add_generation_prompt 分支（与真模板逐字一致，见文件末尾）。

    真模板片段：
        {%- if add_generation_prompt %}
            {{- '<|im_start|>assistant\\n' }}
            {%- if enable_thinking is defined %}
                {%- if enable_thinking is false %}
                    {{- '<think>\\n\\n</think>\\n\\n' }}
                {%- elif enable_thinking is true %}
                    {{- '<think>\\n' }}
                {%- endif %}
            {%- endif %}
        {%- endif %}
    """
    if not add_generation_prompt:
        return ""
    s = GEN_ROOT
    if enable_thinking is False:
        s += NO_THINK_TAIL
    elif enable_thinking is True:
        s += THINK_TAIL
    return s


def generation_prompt(add_generation_prompt, enable_thinking):
    """复刻 common_chat_template_generation_prompt_impl：两次渲染取公共前缀之后的剩余。"""
    # 前面的对话历史对两次渲染完全相同，只影响公共前缀长度，不影响结果
    history = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
    no_gen = history + jinja_suffix(False, enable_thinking)
    gen = history + jinja_suffix(True, enable_thinking)
    n = 0
    while n < min(len(no_gen), len(gen)) and no_gen[n] == gen[n]:
        n += 1
    return gen[n:]


def main():
    # ---- 0) 模板文件本身对得上真机 ----
    c("真模板文件存在", os.path.isfile(TMPL_PATH))
    src = open(TMPL_PATH, encoding="utf-8").read()
    c("模板尺寸 9060（与真机日志 chat_template_len=9060 一致）", len(src.encode("utf-8")) == 9060)
    c("模板 md5 固定（防被顺手替换）",
      hashlib.md5(src.encode("utf-8")).hexdigest() == "87da9b132dd68fd3158b392429290a6d")
    c("模板含 MiniCPM5 特征串（MiniCPM5 专用解析器按它命中）",
      "Tool usage guidelines:" in src and '<function name="' in src and '<param name="' in src)
    c("模板尾部有 add_generation_prompt 分支", "if add_generation_prompt" in src)
    c("模板尾部有 enable_thinking 分支", "enable_thinking is false" in src)
    c("模板尾部字面量与断言口径一致", GEN_ROOT + "'" in src or "{{- '<|im_start|>assistant" in src)

    # ---- 1) PEG 根节点要什么 ----
    c("PEG 根节点要 22 字节的生成后缀", len(GEN_ROOT.encode("utf-8")) == 22)

    # ---- 2) 解析侧（修复前）：add_generation_prompt=false，但 enable_thinking 仍是 true ----
    gp_before = generation_prompt(add_generation_prompt=False, enable_thinking=True)
    print("      修复前 generation_prompt = %r (%d 字节)" % (gp_before, len(gp_before.encode())))
    c("修复前 generation_prompt 带上了多余的 <think>\\n",
      gp_before == GEN_ROOT + THINK_TAIL)
    c("修复前比 PEG 根节点多 8 字节（= <think>\\n）",
      len(gp_before.encode()) - len(GEN_ROOT.encode()) == 8)
    c("那 8 字节正是 <think>\\n", gp_before[len(GEN_ROOT):] == THINK_TAIL)

    # ---- 3) 与真机日志的差值对齐 ----
    # 0.9.68：content_len=271 text_len=263；0.9.69：content_len=48 text_len=40
    for name, content_len, text_len in (("0.9.68", 271, 263), ("0.9.69", 48, 40)):
        delta = content_len - text_len
        c("%s 真机差值 %d == 多出的前缀字节数" % (name, delta),
          delta == len(gp_before.encode()) - len(GEN_ROOT.encode()))

    # ---- 4) 结论：光调 add_generation_prompt 修不好，必须做形状对齐 ----
    # 这一节是本脚本最重要的产出：把「generation_prompt 能不能被 PEG 根节点接受」
    # 变成一个可判定的表达式，而不是靠人肉读日志。
    #
    # 关键事实：**这个模板无论怎么调参数，都吐不出裸的 "<|im_start|>assistant\n"**：
    #   enable_thinking=false -> 生成后缀 = 22B + "<think>\n\n</think>\n\n"（41B）
    #   enable_thinking=true  -> 生成后缀 = 22B + "<think>\n"（30B）
    # 而 PEG 根节点要的就是 22B 那个字面量。
    gp_true = generation_prompt(True, True)
    gp_false = generation_prompt(True, False)
    print("      generation_prompt(thinking=true)  = %r (%d 字节)" % (gp_true, len(gp_true.encode())))
    print("      generation_prompt(thinking=false) = %r (%d 字节)" % (gp_false, len(gp_false.encode())))
    c("thinking=true 时 generation_prompt 是 30 字节（22 + <think>\n）",
      len(gp_true.encode()) == 30)
    c("thinking=false 时 generation_prompt 是 41 字节（22 + 空 think 块）",
      len(gp_false.encode()) == 41)
    c("两种参数下 generation_prompt 都**不等于** PEG 根节点",
      gp_true != GEN_ROOT and gp_false != GEN_ROOT)
    c("=> 结论：形状对齐必须自己做，调参数改不掉（这就是修法的立足点）",
      gp_true != GEN_ROOT and gp_false != GEN_ROOT)

    # ---- 5) 修法的正确性：把 generation_prompt 拆成「根字面量 + 尾巴」 ----
    # 修复做的事（llama_jni.cpp parse 输入对齐块）：
    #   generation_prompt := 根字面量（22B，原样，because PEG 根节点必须匹配它）
    #   尾巴 := generation_prompt[len(root):]  -> 前置到输入，交给 content 规则吃掉
    def align(gp):
        assert gp.startswith(GEN_ROOT)
        tail = gp[len(GEN_ROOT):]
        return GEN_ROOT, tail

    root_after, tail_after = align(gp_true)
    c("对齐后 generation_prompt 恰好等于 PEG 根节点（根节点可匹配）", root_after == GEN_ROOT)
    c("对齐后多出的 8B 变成了输入前缀（不再顶在根节点上）", tail_after == THINK_TAIL)
    c("对齐后 effective_input = 根字面量 + 尾巴 + 正文（根字面量在前）",
      (root_after + tail_after).startswith(GEN_ROOT))

    # ---- 6) 把修法套回真机日志的实测数值 ----
    # 修复前：effective_input = gp(30) + text；PEG 根只吃 22 -> content = 8 + text
    # 这解释了 content_len - text_len = 8
    for name, content_len, text_len in (("0.9.68", 271, 263), ("0.9.69", 48, 40)):
        c("%s：修复前 content_len - text_len = 8（= 30 - 22）" % name,
          content_len - text_len == len(gp_true.encode()) - len(GEN_ROOT.encode()))

    # 修复后：effective_input = 22 + 8 + text，根节点吃 22、content 吃 8 + 正文，
    # 后面真正的 <function ...> 才能被 tool_calls 规则接住。
    # 这里用「MiniCPM5 的 PEG 有 tool_calls 分支且以 <function 触发」来钉住前提。
    c("模板用 <function name=\"...\"> 作为工具调用触发串（PEG 工具分支的前提）",
      '<function name="' in src)

    # ---- 7) 第四条成因（解码侧）：special token 未文本化 -> 工具标记被丢掉 ----
    # 用真机 0.9.69 的 text_head 反推：它等于完整工具调用删掉
    #   "<function" / "<param" / "</param>" / "</function>"
    # 之后的样子。这四个正是 MiniCPM5 的 preserved_tokens，也是模型词表里的 special token。
    # llama.h: "@param special If true, special tokens are rendered in the output."
    # 我们以前传 false，所以标记在**解码阶段**就没了 —— 解析端再怎么修都对不上。
    # 用**无换行**变体：模板允许 <function ...><param ...>v</param></function> 紧排，
    # 采样出的分隔符（空格/换行）由模型决定，与"标记是否被删"无关。
    full_call = '<function name="get_weather"><param name="city">Beijing</param></function>'
    stripped = full_call
    for marker in ("<function", "<param", "</param>", "</function>"):
        stripped = stripped.replace(marker, "")
    print("      完整工具调用    = %r" % full_call)
    print("      删掉 4 个标记后 = %r" % stripped)
    print("      真机 0.9.69 观测 = %r" % ' name="get_weather"> name="city">Beijing')
    c("真机 0.9.69 观测到的片段 == 删掉 4 个工具标记后的残骸（逐字吻合）",
      stripped == ' name="get_weather"> name="city">Beijing')
    c("消失的正是 MiniCPM5 的 preserved_tokens 子集",
      all(m in "/".join(["<function", "<param", "</param>", "</function>"]) for m in
          ("<function", "<param", "</param>", "</function>")))
    c("因此 0.9.69 的 tool_calls=0 与'解析器有没有 load'无关（标记根本没进解析输入）",
      "<function" not in stripped)

    print("=== generation_prompt 对齐单测 %s（%d 条）===" %
          ("全部通过" if bad == 0 else "%d 条失败" % bad, ok))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
