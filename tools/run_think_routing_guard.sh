#!/bin/sh
# 「思考模式下正文不得被吞」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 真机现象（2026-09-19，本仓 PR #66 用户日志）：
#   · 开思考模式发消息，客户端只收到**很短**一段，随后是
#     `[ERROR] Request Failed: Error: Expected leading [0-9a-fA-F] character but was 0x0`；
#   · 服务端日志里这一轮**没有**任何报错，只是 `handleChat 正常结束 n=<小>`。
#
# 根因是"思考段是否已由 prompt 开好"这条判据判反了：
#   MiniCPM5 模板在 enable_thinking=true 下吐 `…<|im_start|>assistant\n<think>\n`
#   —— **只有开标签**。旧判据（宿主扫渲染结果："见过 <think> 且其后没有 </think>"
#   就算已开）命中它，于是 ThinkStream 以 inThink=true 起步；模型自己吐的 `</think>`
#   走了「思考段内的裸闭合标签」那一支被静默剥离，整段思考与正文一起留在
#   reasoning 里，content 只剩极短一截。
#
# 判据本身无从"看输出"发现（不崩、不报错、HTTP 200），所以钉两件事：
#   ① 判据只允许有一个来源：渲染侧（C++）给出的语义标注，宿主不得再扫字符串近似；
#   ② 判据的语义必须是「后缀里有一个**未闭合**的 <think>」，而不是「出现过 <think>」。
#
# 运行：bash tools/run_think_routing_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
PU=app/src/main/cpp/probe_util.h
SRC=app/src/main/java/com/xiaowan/localinference

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在"   "[ -f \$JNI ]"
c "probe_util.h 存在"   "[ -f \$PU ]"

# ── 1) C++ 判据存在，且**必须**去看闭合标签（去掉这一步就是本次故障） ──
# ⚠ 判据本体在模块 F 那一轮从 `llama_jni.cpp` 搬到了 `probe_util.h`
# （理由见那里：判据原先有三份各自实现、窗口单位还不一致，收口到**一处**；
# 放 `probe_util.h` 是因为它宿主可编，宿主测因此能测真实现）。
# 本守卫的**判据意图逐条不变**，只是锚点跟着实现走。
c "C++ 侧有渲染尾部形状判据（本体在 probe_util.h）" \
  "grep -q 'static inline ThinkTailShape classify_think_tail' \$PU"
c "llama_jni.cpp 仍然经同一个出口取该判据" \
  "grep -q 'classify_think_tail(prompt)' \$JNI"
c "判据要求「最后一个开标签之后没有闭合标签」才算已开（关键分支）" \
  "grep -q 'tail.find(CLOSE, at) != std::string::npos) return ThinkTailShape::kClosed' \$PU"
c "判据的尾窗口只覆盖生成后缀（不扫全文/历史轮）" \
  "grep -q 'kThinkTailWindowBytes = 256' \$PU"
c "关思考那一支（模板自己吐完整闭合块）单独成档，不得并进「已开」" \
  "grep -q 'kClosed,    //' \$PU"

# ── 2) 两条渲染路径都必须经同一个语义标注出口 ──
# 数量必须恰为 2：只改一条（无 tools 改了、带 tools 没改，或反之）就是"改一处漏一处"，
# 而那种情况下另一条路径仍会返回不带标注的裸 prompt —— 宿主按"无前缀"退化成 false，
# 于是同一个功能换一个入口行为就不同，且**只在带 tools 时**复发。必须按数量钉。
# 判据只钉"两条路径都经这个出口"，不钉形参表 —— 形参在本轮以后还会长
# （0.9.88 加了生成后缀），钉死形参会让这条判据在每次扩参时假红。
N=$(grep -cE 'return new_rendered_prompt\(env, cp\.prompt[,)]' "$JNI" || true)
c "无 tools / 带 tools 两条渲染路径都走同一个标注出口（应为 2 处，实际 $N）" "[ \"\$N\" = \"2\" ]"

# ── 3) 宿主不得再自行判定 openAtStart（近似判据必须绝迹） ──
c "宿主不再自行扫串判定（近似函数已删除）" \
  "! grep -rq 'fun promptEndsWithOpenThink' \$SRC"
c "HTTP 路径只消费渲染侧结论" \
  "grep -q 'rendered.openAtStart && !soft' \$SRC/HttpApi.kt"
# App 内聊天路径必须**从 rendered.text 取**。只 grep `rendered.text` 是**恒真**的 ——
# HttpApi 里也有这串，于是"漏改一条路径"照样 PASS。自测的桩③ 钉的就是这个洞：
# 断言必须精确到那一行调用，另加一条"旧形状（对 rendered 整体做字符串操作）已废"。
c "App 内聊天路径同步消费 rendered.text（不得只改一条路径）" \
  "grep -q 'softSwitchApplies(thinkingOn, chatTemplate, rendered.text)' \$SRC/EngineActivity.kt"
c "App 内聊天路径不得再对 rendered 整体做字符串操作（旧形状已废）" \
  "! grep -qE 'applyToPrompt\(rendered[,)]' \$SRC/EngineActivity.kt"

# ── 4) 软开关注入之后标注必须落到"已闭合"（不是"已开"） ──
# 软开关补的是**闭合段**，注入完思考段已闭合、模型接下来写的是正文。
# 置 true 会让整段正文被当 reasoning 折叠进思考块（2026-09-21 ISSUE #106）。
c "软开关注入后 openAtStart 落到 false（不得翻成 true）" \
  "grep -q 'val openAtStart = rendered.openAtStart && !soft' \$SRC/HttpApi.kt"
c "旧的错误叠加（|| soft）已绝迹" \
  "! grep -q 'rendered.openAtStart || soft' \$SRC/HttpApi.kt"

# ── 5) 契约注释在位（防止后人"顺手改回去"） ──
c "代码里写明「未闭合」才是判据的契约" \
  "grep -q '未闭合' \$SRC/RenderedPrompt.kt"
c "ThinkStream 里写明 openAtStart 的唯一合法来源" \
  "grep -q '取值只有一个合法来源' \$SRC/ThinkStream.kt"

if [ "$bad" -eq 0 ]; then
    echo "=== 思考路由守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 思考路由守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
