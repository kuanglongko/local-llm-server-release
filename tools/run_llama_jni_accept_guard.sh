#!/bin/sh
# 「nativeStep 只 accept 一次」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 真机现象（2026-09-19，issue #27 用户抓的原始 SSE）：
#   · 不带 stop 的请求：content 增量正常 —— `你好` / `！我` / `是` …
#   · 带 stop 的请求：content 增量**每个都翻倍** —— `你好你好` / `！我！我` / `是是` …
# 即正文整段重复，且只在带 `stop` 时出现。
#
# 根因：`llama_sampler_sample()` **内部已经调过 accept**（见 include/llama.h：
# "Shorthand for: ... llama_sampler_accept(smpl, token); return token;"），
# 而 nativeStep 又显式补了一次 `llama_sampler_accept(S.smpl, tok)`。于是 stop 采样器
# 每步被喂两遍同一个 token 文本，`st.generated` 把该 token 记两遍；而 nativeStep 按
# generated 的**增量**下发（stop_sampler_take_released），增量就成了「X + X」。
#
# 为什么单测抓不到：
#   · stop_sequences.h 的纯语义单测（run_stop_sampler_tests.sh）直接调 advance，
#     状态机本身没错 —— 错在"被喂了两遍"，那是外壳的事；
#   · 无状态采样器重复 accept 无害，所以它**只在带 stop 时**才暴露。
# ⇒ 这类缺陷只能靠源码级断言钉住。
#
# 运行：bash tools/run_llama_jni_accept_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在"                 "[ -f \$SRC ]"
# nativeStep 函数体：从 `Java_com_xiaowan_localinference_LlmEngine_nativeStep(` 起，
# 到下一行顶格 `}` 止。用 sed 取区间，避免跨函数的误匹配。
LINE=$(grep -n 'LlmEngine_nativeStep(JNIEnv' "$SRC" | cut -d: -f1)
END=$(awk -v s="$LINE" 'NR>s && /^}/{print NR; exit}' "$SRC")
BODY_N=$(sed -n "${LINE},${END}p" "$SRC")

# ── 1) nativeStep 里必须调 llama_sampler_sample
c "nativeStep 调用了 llama_sampler_sample" "echo \$BODY_N | grep -q 'llama_sampler_sample'"

# ── 2) nativeStep 里不得有实际调用 llama_sampler_accept（注释里提到不算）
#        过滤掉以 // 或 * 开头的注释行后再判
if echo "$BODY_N" | grep -vE '^\s*(//|\*)' | grep -qE 'llama_sampler_accept\s*\('; then
    echo "FAIL  nativeStep 不得再调 llama_sampler_accept（llama_sampler_sample 已内部 accept）"
    bad=$((bad+1))
else
    echo "PASS  nativeStep 未重复调用 llama_sampler_accept"
    ok=$((ok+1))
fi

# ── 3) llama_sampler_sample 调用点之后的第一条实语句不能是 accept
if grep -A3 'llama_sampler_sample(S.smpl, S.ctx, -1)' "$SRC" | grep -vE '^\s*(//|\*)' | grep -qE 'llama_sampler_accept\s*\('; then
    echo "FAIL  llama_sampler_sample 之后出现了 llama_sampler_accept（重复 accept）"
    bad=$((bad+1))
else
    echo "PASS  llama_sampler_sample 之后没有 llama_sampler_accept"
    ok=$((ok+1))
fi

# ── 4) 契约注释在位（防止后人"顺手加回去"）
c "代码里写明 sample 已内部 accept 的契约" "grep -q '内部已经调过 accept' \$SRC"

if [ "$bad" -eq 0 ]; then
    echo "=== llama_jni accept 守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== llama_jni accept 守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
