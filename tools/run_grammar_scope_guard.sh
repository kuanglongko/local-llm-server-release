#!/bin/sh
# 「GBNF 推导所用的 chat 模板必须与渲染侧同源」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# `response_format` 生效需要两次模板求值：
#   ① 渲染 prompt（handleChat）—— 用**运行时**模板 `LlmEngine.chatTemplate()`；
#   ② 推导 GBNF（native `gbnf_from_json_schema`）—— 必须回答"用哪个模板"。
#
# ② 原先在 `/v1/completions` 传空串（"让库按模型自选"），而 `handleChat` 传运行时模板
# —— **两个端点对同一个问题给了两个答案**。库内自选那一份由 `minja::resolve_template`
# 决定，还会被 `json_schema` 分支改写，与运行时那份**可能不是同一份**。
#
# 模板换了，`cp.grammar` 的根首字面量与 `cp.generation_prompt` 就都按另一份求值：
#   · 约束落错位置 —— 模型先自己吐一遍生成后缀再满足 grammar；
#   · 或压根产不出 GBNF —— 日志说"已降级为无约束采样"。
# 两者都是**不崩、不报错、HTTP 200**。这正是本项目反复踩的那类"哑得不响"，
# 也正是 0.9.9x 那一串真机故障（content 前面多一段 / 数组只剩 `[ ]`）的同一内核。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「取值来源」，不锚「存在性」
# ═══════════════════════════════════════════════════════════════════════════
# 两个端点**都**有 `chatTemplateOverride = ` 这一行（存在性断言的绿区）——
# 差别只在交给它的那个表达式。所以：
#   · 判据必须**计数**（两处都必须是运行时模板），"某个端点出现过正确表达式"是恒真的；
#   · 且不得回退成 `chatTemplateOf(null)` / `chatTemplateOf("")`（空串 = 让库另选）。
# 第一版判据就是"有过正确表达式"式，自测的桩②当场抓出来（一个对一个错时它照样全绿）。
#
# 运行：sh tools/run_grammar_scope_guard.sh
set -e
cd "$(dirname "$0")/.."
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
JNI=app/src/main/cpp/llama_jni.cpp
CTX=app/src/main/java/com/xiaowan/localinference/RequestContext.kt
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "HttpApi.kt 存在"  "[ -f \$HTTP ]"
c "llama_jni.cpp 存在" "[ -f \$JNI ]"
c "RequestContext.kt 存在" "[ -f \$CTX ]"

# ── 1) 两个生成端点都必须把**运行时模板**传下去 ────────────────────────────
# 1a. 主判据：**逐个调用点**检查取值来源（真词法，不是 grep）。
#     为什么不用"某段文本出现过"：两个端点**都**有这一行，差别只在等号右边 ——
#     "出现过一次正确表达式"在"一个对一个错"时恒真，而那正是 F-2 本身的形态。
c "两个生成端点的 chatTemplateOverride 都追溯到运行时模板" \
  "$PY tools/grammar_scope/check_template_source.py \$HTTP"

# 1b. 空串写法必须绝迹：`chatTemplateOf(null)`（= 让库按模型自选）在任何端点都不得再出现。
#     必须**去注释**之后再看 —— 否则注释里写一句"旧写法是 chatTemplateOf(null)"就会误红，
#     而把注释也算进实现是模块 I 的 PR-1 已经踩过的坑。自测的桩④就是为这条留的。
c "「让库按模型自选」的写法已绝迹（去注释后检查，注释里提到不算）" \
  "$PY tools/grammar_scope/check_template_source.py --no-null-literal \$HTTP"
c "「让库按模型自选」的写法在全仓绝迹（含空串形态，去注释后）" \
  "$PY tools/grammar_scope/scan_template_literals.py app/src/main/java/com/xiaowan/localinference"

# 1c. 形参名必须仍叫 chatTemplateOverride，且真的被传进 newSampler（防形参漂移）。
c "newSampler 仍接受 chatTemplateOverride 形参" \
  "grep -q 'chatTemplateOverride: String? = null' \$KT"
c "handleCompletion 的 newSampler 调用里确实传了 chatTemplateOverride" \
  "$PY - \$HTTP <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun handleCompletion(')
j = s.index('\n    private fun ', i + 10) if '\n    private fun ' in s[i+10:] else len(s)
body = s[i:j]
ok = ('chatTemplateOverride = RequestContext.chatTemplateOf(LlmEngine.chatTemplate())' in body)
sys.exit(0 if ok else 1)
PY"

# ── 2) native 侧：模板是**透传**的，不得再退回"库自选" ─────────────────────
c "native 侧把 tmplOverride 交给 common_chat_templates_init（不是写死空串）" \
  "grep -q 'common_chat_templates_init(S.model, tmpl);' \$JNI"
c "native 侧不再有「模板写死空串」的注释声明（那是旧口径）" \
  "! grep -q '让库按模型自选' \$JNI"

# ── 3) 契约注释在位（防止后人"顺手改回去"） ────────────────────────────────
c "RequestContext 写明「这一跳必须回答用哪个模板」这条故障" \
  "grep -q '必须回答一个' \$CTX"
c "RequestContext 写明「库内那份与运行时模板可能不是同一份」" \
  "grep -q '与运行时模板可能不是同一份' \$CTX"
c "RequestContext 写明后果是\"哑得不响\"（不报错）" \
  "grep -q '哑得不响' \$CTX"
c "HttpApi 的 completions 侧写明「必须与渲染侧同源」" \
  "grep -q '必须与渲染侧同源' \$HTTP"
c "HttpApi 的 completions 侧写明「空串=让库按模型自选」是错的那一侧" \
  "grep -q '让库按模型自选' \$HTTP"

if [ "$bad" -eq 0 ]; then
    echo "=== grammar 模板同源守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== grammar 模板同源守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
