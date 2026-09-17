#!/bin/sh
# native 工具解析的「解析器必须自己装回去」静态断言。
#
# 为什么单独一份：真机日志（probe-20260917-192446.txt）里
#     [parse] <<< templates_apply ok  format=peg-native(2)  parser_len=7946
#     [parse] <<< common_chat_parse ok  content_len=173 tool_calls=0
# 模型已经吐出完整、合法的 <tool_call>{...}</tool_call>，但 tool_calls 恒为 0、
# content 恒等于全文（= text 151B + generation_prompt 22B）。
#
# 根因：common_chat_parser_params(const common_chat_params &) **只搬 format 与
# generation_prompt**（见 chat.h），而 common_chat_parse 的实现是
#     return common_chat_peg_parse(params.parser, input, is_partial, params);
# 且 common_chat_peg_parse 里
#     parser = src_parser.empty() ? 纯内容解析器 : src_parser;
# 所以 pp.parser 为空时，整段输出被当成 content 吃掉，永远解析不出工具调用。
# cp.parser 是 PEG 的序列化串（save() 的产物），必须显式 arena.load(cp.parser)。
#
# 这类错「不崩溃、只是静默不干活」，比崩溃更难查，因此用断言钉死。
set -e
cd "$(dirname "$0")/.."
CPP=app/src/main/cpp/llama_jni.cpp
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
# 只抽出 parseToolCallsImpl 的函数体（到第一个顶格 } 为止），避免误命中渲染路径
awk '/static jstring parseToolCallsImpl/{f=1} f{print} f&&/^\}/{exit}' "$CPP" > "$TMP"

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "parse 路径显式 load PEG（pp.parser.load(cp.parser)）" \
  "grep -q 'pp.parser.load(cp.parser);' \$TMP"
c "load 在 common_chat_parse 之前" \
  "[ \$(grep -n 'pp.parser.load(cp.parser);' \$TMP | head -1 | cut -d: -f1) -lt \$(grep -n 'common_chat_parse(text' \$TMP | head -1 | cut -d: -f1) ]"
c "load 有 try/catch（不使异常穿过 JNI）" \
  "grep -A3 'pp.parser.load(cp.parser);' \$TMP | grep -q 'catch'"
c "load 后显式判空（防静默降级成纯内容）" \
  "grep -q 'PEG load 后仍为空' \$TMP && grep -q 'pp.parser.empty()' \$TMP"
c "load 失败时降级为 kNoToolCalls（不外抛）" \
  "grep -q 'PEG load 异常' \$TMP && grep -q 'PEG load 未知异常' \$TMP && [ \$(grep -c 'return env->NewStringUTF(kNoToolCalls);' \$TMP) -ge 3 ]"
c "load 成功后输出 root/size 便于远程复核" \
  "grep -q 'pp.parser.root()' \$TMP && grep -q 'pp.parser.size()' \$TMP"
# 回归：不许再出现「声称 common_chat_parse 自己推导解析器」的旧注释
c "不再声称由 common_chat_parse 自行推导" \
  "! grep -q '由 common_chat_parse 自己去推导' \$CPP"
# 渲染路径已确认会产出解析器（cp.parser 非空），否则 load 无从谈起
c "渲染路径判空 cp.parser（load 的前提）" \
  "grep -q 'cp.parser.empty()' \$CPP"

if [ "$bad" -eq 0 ]; then
    echo "=== 工具解析（PEG load）单测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 工具解析（PEG load）单测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
