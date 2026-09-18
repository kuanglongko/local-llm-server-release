#!/bin/sh
# 拉取上游 cui-llama.rn 的 common/chat.cpp（我们 vendor 的那个 .so 的源码），
# 用于**复核解析语义**：PEG 根节点字面量、generation_prompt 算法、
# special token 是否文本化，这三处都不在本仓库源码内（在 vendor 预编译库里）。
#
# 为什么不把源码提交进仓库：chat.cpp 本身 160KB+，且依赖整棵 llama.cpp 树，
# 推进来会让仓库体积与维护面显著膨胀。而它每次都能从公开源拉到（本脚本就是证明），
# 缺的不是"有没有源码"，而是"有没有把复核步骤固定下来"——所以固定的是这个脚本。
#
# 版本必须与 vendor 里的一致（v1.12.2）：头文件 chat.h 的字段布局是按该版本逐字段
# static_assert 钉住的（见 app/src/main/cpp/chat_abi.h），源码版本不一致就没有复核价值。
set -e
OUT="${1:-/tmp/upstream-chat.cpp}"
REF="${UPSTREAM_REF:-main}"
URL="https://raw.githubusercontent.com/Vali-98/cui-llama.rn/${REF}/cpp/common/chat.cpp"

echo "拉取 $URL"
rm -f "$OUT"
curl -sSL -o "$OUT" "$URL" || { echo "curl 失败"; exit 1; }
[ -s "$OUT" ] || { echo "拉取失败或为空：$OUT"; exit 1; }

SIZE=$(wc -c < "$OUT")
echo "已保存 $OUT（$SIZE 字节）"
[ "$SIZE" -gt 100000 ] || { echo "内容过小，可能拉到了错误页（期望 >100KB）"; exit 1; }

# 三条本轮结论所依赖的关键实现，逐条打印出来供人工复核
echo
echo "=== 1) common_chat_parse：generation_prompt 会被前拼到输入上 ==="
grep -n "effective_input = params.generation_prompt" "$OUT" || echo "  （未命中，上游可能已改，请人工确认）"

echo
echo "=== 2) common_chat_peg_parse：空 arena 静默降级成纯内容解析器 ==="
grep -n "src_parser.empty() ?" "$OUT" || echo "  （未命中）"

echo
echo "=== 3) MiniCPM5：PEG 根节点字面量 ==="
grep -n 'p.literal("<|im_start|>assistant' "$OUT" || echo "  （未命中）"

echo
echo "=== 4) MiniCPM5：preserved_tokens（这些在词表里是 special token） ==="
sed -n '/static common_chat_params common_chat_params_init_minicpm5/,/thinking_start_tag/p' "$OUT" \
  | sed -n '/preserved_tokens/,/};/p'

echo
echo "=== 5) generation_prompt 算法：两次渲染取公共前缀之后的剩余 ==="
grep -n "common_chat_template_generation_prompt_impl" "$OUT" | head -3
