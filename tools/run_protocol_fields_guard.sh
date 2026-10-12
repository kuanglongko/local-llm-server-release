#!/bin/sh
# 协议字段（`max_completion_tokens` / `logit_bias` / `logprobs`）的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 这三个字段的坏法**全是静默的**：HTTP 200、响应体合法、没有异常、没有日志，
# 只是调用方设的值没生效（或只生效一半）。单测证明"这些输入得到这些输出"，
# 但证明不了"仓库里那一处**结构**没改回去"：
#
#   ① `max_completion_tokens` 与 `max_tokens` 各自**算一份 maxTokens** ——
#      分叉时的症状是"换个字段名就越界/就不生效"，而两边都不报错。
#   ② `logit_bias` 的两个平行数组**不同源** —— 长度不等时 native 侧会整条不挂
#      （对），但那意味着**偏置全丢**而请求照 200。
#   ③ 偏置采样器**挂错了位置**（在选择器之后）—— 正偏置本该把低分 token 拉回来，
#      放在截断之后就再也拉不回来，而"拉不回来"与"模型本来就不想输出它"同形。
#   ④ `logprobs` 字段**无条件出现** —— 违反"新增可选字段必须默认逐字节同旧行为"。
#   ⑤ `top_logprobs` 与 `logprobs` 的**绑定判据漏掉** —— 单独给 top_logprobs
#      会被静默接受，调用方以为自己拿到了候选置信度。
#   ⑥ `describe()` 里写 `$p.maxTokens`（而不是 `${p.maxTokens}`）—— Kotlin 模板
#      只取 `p`，`.maxTokens` 被当文本拼进去；编得过、跑得过，只是日志里那行读数
#      变成 `Parsed(...)` 的 toString（本轮自测当场抓到过一次）。
#   ⑦ 负数 token id 被收下（`toIntOrNull` 收 `-12`，且 `(-12).toString()` 恰好
#      还原成 key 本身，所以"往返一致"这条判据**拦不住它**）。
#
# 所以钉的是**结构与同源**，不是"文件里出现过 logit_bias"。
# 运行：sh tools/run_protocol_fields_guard.sh
set -e
cd "$(dirname "$0")/.."
PF=app/src/main/java/com/xiaowan/localinference/ProtocolFields.kt
SP=app/src/main/java/com/xiaowan/localinference/SamplingParams.kt
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/include/llama.h
PY=${PYTHON:-python3}

ok=0; bad=0
# 失败的判据把**命令的 stderr/stdout** 一起回显：
# python helper 的诊断（文件:行:原因）比"判据名字"有用得多，而 `eval` 默认
# 会把它们直接漏到终端上、与 PASS/FAIL 行交错，读起来像别的问题。
# 这里显式吞掉并按 FAIL 行格式带出一行首行诊断（长输出截前 3 行）。
c() {
    if eval "$2" >/dev/null 2>&1; then echo "PASS  $1"; ok=$((ok+1)); return; fi
    echo "FAIL  $1"; bad=$((bad+1))
    eval "$2" 2>&1 | head -3 | sed 's/^/        ↳ /'
}

c "ProtocolFields.kt 存在" "[ -f \$PF ]"
c "HttpApi.kt 存在"        "[ -f \$HTTP ]"
c "llama_jni.cpp 存在"     "[ -f \$JNI ]"
c "llama.h 存在"           "[ -f \$HDR ]"

# ── 1) 别名与本体**共用同一份**上限与 400 文案 ────────────────────────────
# 各写一份数字时的症状是 `max_tokens=99999` 报 400、而
# `max_completion_tokens=99999` 通吃（或反过来）—— 调用方看到的是
# "换个字段名就能用"，而不是"我越界了"。所以两条判据都锚在**引用**上。
c "本体（legacy）过 SamplingParams.maxTokensOk" \
  "grep -q '!SamplingParams.maxTokensOk(legacy)' \$PF"
c "别名（alias）同样过 maxTokensOk（不另写一份范围判据）" \
  "grep -q '!SamplingParams.maxTokensOk(alias)' \$PF"
c "400 文案里的上限**引用** SamplingParams.MAX_TOKENS_CAP（不写死 8192）" \
  "\$PY tools/protocol_fields/check_const_ref.py \$PF SamplingParams.MAX_TOKENS_CAP errMaxCompletionTokens"
c "SamplingParams.fromRequest 接受别名结果（不再自己另读一遍 j）" \
  "grep -q 'fun fromRequest(j: JSONObject, aliasMaxTokens: Int? = null)' \$SP"
c "别名存在时优先取别名（缺省才走默认值）" \
  "grep -q 'val maxTokRaw = if (aliasMaxTokens != null) aliasMaxTokens' \$SP"
c "两个字段名同时给出且取值不同必须 400（不做静默二选一）" \
  "grep -q 'return null to errMaxTokensConflict' \$PF"
# 顺序是判据，且**必须逐端点各判一次**：顺序反了别名永远生效不了 ——
# SamplingParams 会先按 max_tokens 的缺省填成 512，而它那一步看不到别名
# （别名要在外面先解）。只判"某处 pf 在 sp 之前"是不够的：其中**一个**端点
# 写反照样绿，而那个端点上别名就是失效的（两个端点对同一字段给不同答案是
# 本仓库最忌讳的分叉形态）。
# 判据走外部脚本（要逐端点各判一次，grep 的行号关系做不到"每一个都判"）。
# helper 的输出**会**被 c() 吞掉并折成 FAIL —— python 侧非零退出时把它的诊断
# 也带出来，否则只看到一行 FAIL 而不知道为什么。
c "两个端点各自：协议字段解析都排在采样参数之前" \
  "\$PY tools/protocol_fields/check_parse_order.py \$HTTP"
c "两个端点都把别名传进 SamplingParams（不是只有 chat 支持）" \
  "[ \$(grep -c 'SamplingParams.fromRequest(j, aliasMaxTokens = pf.maxTokens)' \$HTTP) -eq 2 ]"

# ── 2) 平行数组必须**同源** ───────────────────────────────────────────────
# 判据：两者都是对**同一个** `p.logitBias` 的一次 map。不同源时长度会不等，
# 而长度不等在 native 侧的处理是"整条不挂" —— 偏置全丢、请求照 200。
c "biasIds 从 p.logitBias 一次 map 出来" \
  "grep -q 'p.logitBias?.map { it.tokenId }?.toIntArray()' \$PF"
c "biasValues 从**同一个** p.logitBias 一次 map 出来" \
  "grep -q 'p.logitBias?.map { it.bias }?.toFloatArray()' \$PF"
c "两个端点都从同一个 pf 取偏置传进 newSampler" \
  "[ \$(grep -c 'biasIds = ProtocolFields.biasIds(pf), biasVals = ProtocolFields.biasValues(pf)' \$HTTP) -eq 2 ]"
c "Kotlin 侧 nativeNewSampler 的签名含两个平行数组" \
  "grep -q 'thinkingOn: Boolean, biasIds: IntArray?, biasVals: FloatArray?): Boolean' \$ENG"
c "native 侧签名与 Kotlin 逐字对应（同样两个参数、同一顺序）" \
  "grep -q 'jboolean jthinkingOn, jintArray jbiasIds, jfloatArray jbiasVals) {' \$JNI"
# 长度不等时必须**整条不挂**，不能按较短的那个截断使用 —— 后者把一个接线
# bug 静默成"偏置少了几条"。
c "两个数组长度不等时整条不挂（不按短的截断）" \
  "grep -q 'n_bias > 0 && n_bias == n_vals' \$JNI"
c "长度不等有一行可查的日志（不静默）" \
  "grep -q 'logit_bias 的 id/bias 数组长度不一致' \$JNI"

# ── 3) 偏置采样器必须挂在**第一个选择器之前** ──────────────────────────────
# 判据是**行号关系**（与 grammar 那条同一个做法，不靠 review 肉眼保证）。
BIAS=$(grep -n 'llama_sampler_init_logit_bias(llama_vocab_n_tokens(S.vocab), n_bias' "$JNI" | head -1 | cut -d: -f1)
FIRSTSEL=$(grep -n 'llama_sampler_init_greedy()\|llama_sampler_init_penalties(\|llama_sampler_init_top_k\|llama_sampler_init_top_p\|llama_sampler_init_min_p\|llama_sampler_init_temp\|llama_sampler_init_dist' "$JNI" | head -1 | cut -d: -f1)
c "找得到偏置挂载点与第一个选择器" "[ -n \"\$BIAS\" ] && [ -n \"\$FIRSTSEL\" ]"
c "偏置挂在第一个选择器之前（放后面正偏置就拉不回被砍掉的 token）" \
  "[ \"\$BIAS\" -lt \"\$FIRSTSEL\" ]"
c "头文件里确实有 llama_sampler_init_logit_bias（判据的前提）" \
  "grep -q 'llama_sampler_init_logit_bias(' \$HDR"
c "头文件里的 llama_logit_bias 结构是 {token, bias}（下标顺序的前提）" \
  "grep -q 'llama_token token;' \$HDR"

# ── 4) `logprobs` 字段**只在客户端要了时**出现 ────────────────────────────
c "logprobsFieldOf 的 else 支是空串（不要就一个字节都不多）" \
  "\$PY tools/protocol_fields/check_logprobs_optional.py \$HTTP"
c "两个 chunk 构造函数都长出了这个片段参数（各自都有，不是只有一个）" \
  "[ \$(grep -c 'logprobsField: String = ' \$HTTP) -ge 2 ]"
c "非流式 chat 响应用的是变量而不是写死的 logprobs 字段" \
  "grep -q '\$toolField}\$lpField' \$HTTP"
c "非流式 completions 响应同理" \
  "grep -q 'toJsonStr()}\$lpField' \$HTTP"
c "lpField 每个端点各算一次（两处，不重复不遗漏）" \
  "[ \$(grep -c 'val lpField = logprobsFieldOf(pf.logprobs)' \$HTTP) -eq 2 ]"

# ── 5) 五条 400 文案各自存在，绑定判据必须在 ──────────────────────────────
c "logit_bias 必须是对象的 400"        "grep -q 'const val errLogitBiasType' \$PF"
c "logit_bias 取值区间的 400"          "grep -q 'const val errLogitBiasValue' \$PF"
c "logprobs 必须布尔的 400"            "grep -q 'const val errLogprobsType' \$PF"
c "top_logprobs 必须 0~20 整数的 400"   "grep -q 'const val errTopLogprobsType' \$PF"
c "top_logprobs 单独给必须报 400（两者是绑定关系）" \
  "grep -q 'if (tlRaw != null && !logprobs) return null to errTopLogprobsNeedsLogprobs' \$PF"
c "top_logprobs 的上界是常量（文案引同一个，不写死 20）" \
  "\$PY tools/protocol_fields/check_const_ref.py \$PF TOP_LOGPROBS_MAX errTopLogprobsType"

# ── 6) `describe()` 里不许出现会退化成 toString 的模板 ─────────────────────
# 走 python：要匹配的字面量里有 `${`，内联进 shell 的双引号层会被 shell 自己
# 展开，判据就会退化成"文件里出现过 p"（本仓库在 release_hygiene 与筛选的桩里
# 已经栽过两次同款）。
c "describe() 用 \${p.x} 形态、没有 \$p.<字段> 退化模板" \
  "\$PY tools/protocol_fields/check_describe_braces.py \$PF"

# ── 7) key 解析必须显式判非负 ─────────────────────────────────────────────
c "key 解析显式判 it >= 0（往返一致那条拦不住 -12）" \
  "grep -q 'k.toIntOrNull()?.takeIf { it >= 0 && it.toString() == k }' \$PF"

# ── 8) 条数上限是**截断 + 留痕**，不是 400 ─────────────────────────────────
# 与 StopSequences.MAX_SEQUENCES 同族：条数是性能边界，不是协议合法性边界。
c "超上限只截断并留痕（notices 里出现「超过内部上限」）" \
  "grep -q '超过内部上限 \$MAX_LOGIT_BIAS 条' \$PF"
c "超限分支里没有 early return 400（只截断 + 留痕）" \
  "\$PY tools/protocol_fields/check_no_400_on_trim.py \$PF"
# 截断前**必须排序**：`bias` 按 `o.keys()` 攒，而 `logit_bias` 是 JSON **对象**，
# `org.json` 内部是 `HashMap` —— `keys()` 是 String 哈希序。不排序时
# `subList(0, N)` 取的是"哈希序前 N 个"，而日志说"前 N 条"：实测插 302 条时
# 丢的是 id 109/229、留下 300/301，调用方会按一个**错误的假设**排查输出。
# 判据锚"排序动作"与"截断取自排序后的那份"，而不是"文件里出现过 sortedBy"。
c "超限截断前按 tokenId 排序（不取哈希序的任意子集）" \
  "\$PY tools/protocol_fields/check_trim_sorted.py \$PF"
c "logprobs=true 必须留痕（字段收下了但不出数据）" \
  "grep -q 'notices += logprobsAcceptedNotice(topLogprobs)' \$PF"

# ── 9) `n`：只认 1，其余明确 400（不静默给一条）──────────────────────────
# `n: 2` 的失效形态与 max_completion_tokens 同族，但**更隐蔽**：HTTP 200、
# 响应体结构合法，只是 choices 少一条 —— 而很多 SDK 根本不看 choices.length。
c "n 的支持取值是常量（不把 1 写死在分支里）" \
  "grep -q 'const val N_SUPPORTED' \$PF"
c "n > 1 明确 400（不静默截成 1 条）" \
  "grep -q 'return null to errNUnsupported' \$PF"
c "n 为 0/负数报的是**取值**错误、与「不支持」分开" \
  "grep -q 'nRaw == MALFORMED || nRaw < 1 -> return null to errNType' \$PF"
# ⚠ 判序是判据：MALFORMED 哨兵恰好 > N_SUPPORTED，且 === N_SUPPORTED 那支在前面时
# `n: 1.5`（哨兵值 Int.MAX_VALUE -> 是不是 MALFORMED 要看 readIntField，1.5 落 MALFORMED）
# 会落进「不支持」而不是「写错了」。这一行钉的是"哨兵先判"。
c "哨兵（MALFORMED）判在取值分支里、先于「不支持」" \
  "\$PY tools/protocol_fields/check_n_order.py \$PF"
c "n 进 Parsed 结构（否则校验了也没人用）" \
  "grep -q 'val n: Int,' \$PF"

echo
echo "=== 协议字段守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
