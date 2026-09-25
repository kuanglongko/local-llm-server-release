#!/bin/sh
# 「KV 前缀复用不得读错历史」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是只靠 kv_prefix.h 的单测）
# ═══════════════════════════════════════════════════════════════════════════
# `tools/run_kv_cache_tests.sh` 钉住了"切分算得对不对"，但钉不住**接线**：
# 复用能不能成立，取决于 llama_jni.cpp 里三件事有没有同时做到 ——
#
#   ① 任何一个"KV 被动过 / 换了模型"的路径都必须把账本置为无效
#      （kv_invalidate）。漏一处的后果：下一轮拿一份**以为还在**的账本去复用
#      一段已经被删掉/被换掉的 KV -> 模型读到错位历史 -> 答非所问。
#      这一类**不崩、不报错、HTTP 200**，只能靠源码断言把"每条路径都作了废"钉住。
#
#   ② 生成出来的 token 必须记账（S.last_tokens.push_back）。
#      漏了不是错，只是"少了一半收益"—— 多轮对话里回复段越来越长，
#      漏记就等于每轮都把上一轮的回复整段重算。它不会让任何测试变红，
#      所以必须有一条断言专门盯着它。
#
#   ③ 账本**只能**在 prefill 成功之后才算有效（kv_valid = true 的位置）。
#      在 prefill 之前就置 true（或失败路径漏置 false），下一轮会复用一段
#      根本没进 KV 的 token 序列 —— 与 ① 是同一类故障，但成因不同、修法不同。
#
# 运行：bash tools/run_kv_cache_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/kv_prefix.h
SRC=app/src/main/java/com/xiaowan/localinference

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在"    "[ -f \$JNI ]"
c "kv_prefix.h 是独立判据文件（可被宿主单测）" "[ -f \$HDR ]"

# ── 1) 判据本体：逐 token、有门槛、必须留一个 token 给采样 ──────────────
c "判据按**逐 token**比前缀（不依赖可逆 tokenize）" "grep -q 'common_prefix_len' \$HDR"
# 光 grep 标识符是不够的（桩⑦把 16 换成 1 照样 PASS）：必须断言门槛**真的参与判定** ——
# 既要有常量值，也要有拿它去挡的那一行。这是本自测抓出来的第一处弱断言。
c "有复用门槛（常量 kMinReuseTokens = 16）"  "grep -q 'const int kMinReuseTokens = 16;' \$HDR"
c "门槛真的参与判定（lcp 与 reuse 两处都要挡）" \
  "grep -q 'if (lcp < kMinReuseTokens) return p;' \$HDR && grep -q 'if (reuse < kMinReuseTokens) return p;' \$HDR"
c "复用长度必须 < 整段 prompt（否则采样步没有 logits）" \
  "grep -q 'std::min(lcp, p.total - 1)' \$HDR"
c "账本无效一律不复用（宁可慢，不可读错历史）" \
  "grep -q 'if (!prev_valid || prev.empty() || cur.empty()) return p;' \$HDR"

# ── 2) 接线①：每条改动 KV / 换模型的路径都必须作废账本 ─────────────────
c "有统一的作废入口 kv_invalidate" "grep -q 'static void kv_invalidate' \$JNI"
c "模型卸载/换模型时作废账本（唯一一个静默答非所问的入口）" \
  "grep -q 'kv_invalidate(\"模型卸载/换模型\")' \$JNI"
c "prefill 被 abort 打断时作废账本（半截 prompt 不接受复用）" \
  "grep -q 'kv_invalidate(\"prefill 被中断\")' \$JNI"
c "prefill decode 失败时作废账本" \
  "grep -q 'kv_invalidate(\"prefill decode 失败\")' \$JNI"
c "step 的 decode 失败时作废账本（否则账本与 KV 长度错位）" \
  "grep -q 'kv_invalidate(\"step 的 decode 失败\")' \$JNI"
c "prompt 超长时先作废账本再失败（不许把还算能用的缓存留成"看起来有效"）" \
  "grep -q 'kv_invalidate(\"prompt 超长，本轮注定失败\")' \$JNI"

# ── 3) 接线②：生成出来的 token 必须记账 ────────────────────────────────
c "nativeStep 把采样出的 token 追加进账本（否则回复段每轮重算）" \
  "grep -q 'S.last_tokens.push_back' \$JNI"

# ── 4) 接线③：账本只在 prefill 成功之后才算有效 ────────────────────────
# 判据写成"整个文件里只有一处 `S.kv_valid = true`"：多出来的那一处
# 一定会落在某条**还没跑完 prefill** 的路径上，而那正是要防的。
c "S.kv_valid = true 全文件只有一处（只能在 prefill 成功之后）" \
  "[ \"\$(grep -c 'S.kv_valid = true' \$JNI)\" = \"1\" ]"
c "那一处就在 last_tokens 赋值之后（记账与置有效必须成对）" \
  "grep -A3 'S.last_tokens.assign' \$JNI | grep -q 'S.kv_valid = true'"

# ── 5) 复用不改变"失败语义"：缓存分支不得让请求失败 ────────────────────
# 判据范围**只取"算出计划 → 应用到 KV"这一小段**，不延伸到后面的 prefill
# （那里有 abort 与 decode 失败两条**业务**失败路径，它们本来就该 return false）。
#
# 为什么非钉不可：复用是"只影响速度"的功能，一旦哪条缓存分支写了
# `return JNI_FALSE`，表现就是"某些请求莫名 400/prefill 失败" —— 而且只在
# 特定前缀关系下出现（本地单测根本复现不出），是最难归因的一类新引入故障。
# 这里用行号锚定，避免"范围划歪了变成恒真断言"（第一版就划到了 abort 那行，
# 靠断言红掉才发现 —— 见下方第 6 条对同一现象的说明）。
# 范围锚定在「复用计划 → 清掉不匹配的尾巴」这一小段：从 plan_reuse 那行起，
# 到 abort（取消）判定那一行为止 —— abort 是**业务**失败路径，不属于缓存。
c "复用计划的应用段不产生失败返回（缓存问题绝不让请求失败）" \
  "! awk '/kvprefix::Plan plan = kvprefix::plan_reuse/{f=1} f&&/abort（取消）判定/{exit} f' \$JNI | grep -q 'return JNI_FALSE'"


# ── 6) 可观测面：/health 必须报得出"缓存有没有生效" ─────────────────────
# 复用是纯加速，协议上完全看不出来。没有这几个字段，它会在回归里
# 静默失效而无人发现（见 kv_prefix.h 文件头）。
# 断言必须带上**字段名 + 取值表达式**两段：只 grep 字段名的话，把取值换成常量 0
# （桩⑤）照样 PASS —— 那正好是"报了个字段、但永远报 0、于是看起来缓存从未生效"。
c "HttpApi /health 报 kv_cache_valid（且取的是引擎实况）" \
  "grep -q '\"kv_cache_valid\":\$kvValid' \$SRC/HttpApi.kt && grep -q 'kvValid = if (loaded) LlmEngine.kvCacheValid' \$SRC/HttpApi.kt"
c "HttpApi /health 报 kv_reuse_tokens（且取的是引擎实况）" \
  "grep -q '\"kv_reuse_tokens\":\$kvReuse' \$SRC/HttpApi.kt && grep -q 'kvReuse = if (loaded) LlmEngine.lastReuseTokens' \$SRC/HttpApi.kt"
c "HttpApi /health 报 kv_prefill_tokens（且取的是引擎实况）" \
  "grep -q '\"kv_prefill_tokens\":\$kvPrefill' \$SRC/HttpApi.kt && grep -q 'kvPrefill = if (loaded) LlmEngine.lastPrefillTokens' \$SRC/HttpApi.kt"
c "LlmEngine 暴露 lastReuseTokens / lastPrefillTokens / kvCacheValid" \
  "grep -q 'val lastReuseTokens' \$SRC/LlmEngine.kt && grep -q 'val lastPrefillTokens' \$SRC/LlmEngine.kt && grep -q 'val kvCacheValid' \$SRC/LlmEngine.kt"
c "LlmEngine 有 discard 出口 resetKvCache" "grep -q 'fun resetKvCache' \$SRC/LlmEngine.kt"
c "native 侧三个出口与 Kotlin 声明一一对应" \
  "grep -q 'nativeKvCacheStats' \$JNI && grep -q 'nativeKvCacheStats' \$SRC/LlmEngine.kt && grep -q 'nativeKvCacheValid' \$JNI && grep -q 'nativeKvCacheValid' \$SRC/LlmEngine.kt && grep -q 'nativeKvCacheReset' \$JNI && grep -q 'nativeKvCacheReset' \$SRC/LlmEngine.kt"

echo ""
if [ "$bad" = "0" ]; then echo "=== KV 前缀复用守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== KV 前缀复用守卫：PASS $ok / FAIL $bad ==="; exit 1
