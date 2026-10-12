#!/bin/sh
# 「生成轮次之间不得串味」的源码级守卫 —— 采样器状态**每一轮从哪来**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（模块 E 修复轮）
# ═══════════════════════════════════════════════════════════════════════════
# `nativeStartCompletion` 的注释原文写着「本轮开始前的**采样器复位**（stop 状态机等）」，
# 而本文件**从未**调用过 `llama_sampler_reset`（或链上任何采样器的 reset）。也就是说：
#
#   · 注释声称做了一件事，实现没做 —— 典型「注释与实现分叉」，读代码的人会据此
#     得出"暂扣状态每轮都被清过"的结论，而那只在**另一条**路径上才成立；
#   · `stop_sampler_reset`（iface 的 .reset）因此**没有任何调用点**：
#     它里面那笔 `c->emitted = 0` 是 `stop_match_ctx` 唯一的复位入口，
#     而它在真机路径上从来没被执行过。
#
# 当前**不是** bug，因为不变量由另一条路撑着：
#   `nativeNewSampler` 每轮先 `llama_sampler_free(S.smpl)` 再重新
#   `llama_sampler_chain_init`，且 `newSampler` 恒在 `startCompletion` **之前**被调用。
#   于是 `stop_match_ctx` 每轮都是全新对象 —— 泄漏路径不成立。
#
# 但"注释在撒谎 + reset 是死代码"这两件事必须被**同时**钉住：它们合起来才是安全的。
# 只改其中一个（例如"把 reset 删掉"或"顺手补一次 llama_sampler_reset"而不看链是否重建）
# 都可能把一个"靠结构成立"的性质改坏。所以本守卫判的是**这一对关系**：
#
#   ① 链确实由 newSampler 整条重建（free + chain_init），且它在任一新对象上建 stop ctx；
#   ② startCompletion 里**不得**出现"复位采样器"这类声称（注释也不许）；
#   ③ reset 要么有调用点、要么实现里明确标注"当前无调用点，靠链重建" ——
#      二者取其一，**不许**两边都不成立（那才是真的漏复位）。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_gen_state_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }
n() { if eval "$2"; then echo "FAIL  $1"; bad=$((bad+1)); else echo "PASS  $1"; ok=$((ok+1)); fi; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"

# ── 取函数体（顶格 `}` 收尾），避免跨函数误匹配 ──────────────────────────
# ⚠ 函数名与其后的 `(JNIEnv ...)` 常常**不在同一行**（JNIEXPORT 那行只有返回类型），
#   所以只按"名字 + (JNIEnv"同现来判起始会漏 —— 本 helper 的第一版就栽在这里，
#   四条断言全假红。按"名字出现在该行"判起始，再收到顶格 `}`。
body_of() {
    awk -v sig="$1" '
        index($0, "LlmEngine_" sig "(") { infn=1 }
        infn { print; if ($0 == "}") exit }
    ' "$JNI"
}
NEWSMPL=$(body_of nativeNewSampler)
START=$(body_of nativeStartCompletion)

# ── 1) 链是每轮**整条重建**的（这是"不需要 reset"的唯一依据）────────────
c "newSampler 先释放旧链（llama_sampler_free(S.smpl)）" \
  "echo \"\$NEWSMPL\" | grep -q 'llama_sampler_free(S.smpl)'"
c "newSampler 再建新链（llama_sampler_chain_init）" \
  "echo \"\$NEWSMPL\" | grep -q 'llama_sampler_chain_init'"
c "stop 采样器挂在新链上（每轮一个新 ctx）" \
  "echo \"\$NEWSMPL\" | grep -q 'llama_sampler_init(&stop_sampler_iface'"

# ── 2) startCompletion 不得**声称**"复位采样器" ─────────────────────────
#   这是分叉的那一句，所以连注释一起判。但不能简单地"禁掉这四个字"：
#   修复后注释里**会**引用它（"原文写的是…那句话与实现分叉"）—— 那是历史说明。
#   所以判据钉的是**声明的形状**：
#     · 首行概括里不许把"复位采样器"说成这一轮做的事；
#     · 同一段里必须显式写出「不 / 不需要」复位（否则引用就成了新的声称）。
FIRST=$(echo "$START" | grep -m1 '本轮开始前的' || true)
n "startCompletion 首行不把「采样器复位」当成这一轮做的事" \
  "echo \"\$FIRST\" | grep -q '采样器复位'"
c "同一段里显式写明「不需要复位采样器」（与实现一致）" \
  "echo \"\$START\" | grep -q '不需要\*\*复位采样器\|不需要复位采样器'"
c "startCompletion 写明真实不变量（链由 newSampler 重建）" \
  "echo \"\$START\" | grep -q '整条重建'"

# ── 3) reset 的归属：有调用点，或明确标注"无调用点 + 靠链重建" ────────────
#  ⚠ 计数必须**剥掉注释**再数。`grep -c 'llama_sampler_reset'` 会把注释里那处
#    引用（"本文件从未调用过 llama_sampler_reset"）当成调用点 —— 于是判据以为
#    "有调用点"、实际一处都没有。这正是本仓库 assertion_anchor 甲-1 盯的那类
#    "判据问了一个它答不出的问题"：它在 CI 里照样 PASS，信息量为 0。
#    本守卫的第一版就是这么写的，本守卫自测的桩③ 当场把它抓了出来。
HAS_CALL=$(sed -E 's://.*$::' "$JNI" | grep -c 'llama_sampler_reset[[:space:]]*(' || true)
HAS_NOTE=$(grep -c '当前没有调用点' "$JNI" || true)
if [ "$HAS_CALL" -gt 0 ]; then
    echo "PASS  stop 采样器的 reset 有调用点（$HAS_CALL 处）—— 与「每轮重建」并存也安全"
    ok=$((ok+1))
elif [ "$HAS_NOTE" -gt 0 ]; then
    echo "PASS  reset 无调用点（已剥注释核对），实现里也有标注（靠链重建成立）"
    ok=$((ok+1))
else
    echo "FAIL  reset 既没有调用点、也没有标注说明 —— 暂扣/emitted 的复位依据不明"
    bad=$((bad+1))
fi

# ── 4) 复位语义本体仍在（将来改成链复用时必须能接上）────────────────────
c "stop_sampler_reset 仍清 emitted 账（不是只清 st）" \
  "grep -q 'c->emitted = 0' \$JNI"
c "reset 挂进 iface（.reset 接线正确）" \
  "grep -q '/\\* .reset               = \\*/ stop_sampler_reset' \$JNI"

if [ "$bad" -eq 0 ]; then
    echo "=== 生成轮次状态守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 生成轮次状态守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
