#!/bin/sh
# 自测 `tools/run_protocol_fields_guard.sh`：把它对着几份**就地改坏的真源码**跑，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据几乎全是 `grep` / `python3 找函数体` 形式的**结构性**断言，
# 它有三个自欺模式（本仓库各栽过一次，所以三个都钉）：
#
#   1. **恒真**：pattern 写成到处都有的文本。本套件最容易恒真的一条是
#      「偏置挂在第一个选择器之前」—— 写成 `grep -q llama_sampler_init_logit_bias`
#      会在**注释**里那几句说明处就恒真。所以它锚的是两处的**行号关系**。
#   2. **恒假后被静音**：pattern 与源码差一个字符 -> 守卫永远 FAIL ->
#      有人把 `exit 1` 改成 `|| true`，那时它彻底没用了。
#   3. **锚错位置**：钉"文件里出现过 maxTokensOk"而不是"别名**也**过这一条"。
#      于是"别名自己另写一份范围判据"照样绿，而分叉已经发生。
#
# 桩全部是**真正会写错的那几种写法**（不是编出来的），每一条对应一个坏法：
#   · 别名自己另写一份范围判据（不引 maxTokensOk）-> 上限分叉，两种字段名行为不同
#   · 400 文案把上限写死成 8192            -> 改 MAX_TOKENS_CAP 时文案对不上真实判据
#   · 两个平行数组不同源                   -> 长度不等 -> 偏置整条不挂而请求照 200
#   · 偏置采样器挪到选择器**之后**         -> 正偏置再也拉不回被砍掉的 token
#   · `logprobs` 字段无条件出现            -> 所有既有客户端响应形状被改
#   · `top_logprobs` 的绑定判据删掉        -> 单独给会被静默接受
#   · `describe()` 写成 `$p.maxTokens`     -> 日志里那行读数变成 Parsed(...) 的 toString
#   · key 解析去掉非负判据                 -> `-12` 被收下（往返一致拦不住它）
#   · 超限处 return 400                    -> 把能用的客户端**每个请求**都拒掉
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_log_filter_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_protocol_fields_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_protocol_fields_guard.sh
PF=app/src/main/java/com/xiaowan/localinference/ProtocolFields.kt
SP=app/src/main/java/com/xiaowan/localinference/SamplingParams.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
JNI=app/src/main/cpp/llama_jni.cpp

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$PF" "$TMP/pf.bak"; cp "$SP" "$TMP/sp.bak"
cp "$HTTP" "$TMP/http.bak"; cp "$JNI" "$TMP/jni.bak"
restore() { cp "$TMP/pf.bak" "$PF"; cp "$TMP/sp.bak" "$SP"
            cp "$TMP/http.bak" "$HTTP"; cp "$TMP/jni.bak" "$JNI"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/pf.bak" "$PF"; cp "$TMP/sp.bak" "$SP"
              cp "$TMP/http.bak" "$HTTP"; cp "$TMP/jni.bak" "$JNI"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if ! grep -qF "$key" "$TMP/guard.out"; then
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
        return
    fi
    # 命中的那一行要么本身就是 FAIL，要么是它紧随的诊断行（`↳` 开头，
    # 由守卫里 c() 的失败回显产生 —— python helper 的诊断比判据名字有用）。
    # 判据是"它**属于**某条 FAIL"，不是"它自己以 FAIL 开头"：后者会把
    # helper 的诊断误判成"指到了别的 PASS 行"。
    line=$(grep -F -n "$key" "$TMP/guard.out" | head -1 | cut -d: -f1)
    if sed -n "1,${line}p" "$TMP/guard.out" | grep -q "^FAIL" &&        awk -v n="$line" 'NR<=n' "$TMP/guard.out" | tail -1 | grep -qE '^(FAIL|        ↳)'; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」（该行既不是 FAIL 也不是 FAIL 的诊断行）" 0
    fi
}

patch() { # 文件 python-替换脚本
    "$PY" - "$1" <<PYEOF
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
$2
open(p, 'w', encoding='utf-8').write(s)
PYEOF
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：别名自己另写一份范围判据（不引 maxTokensOk）────────────────────
patch "$PF" '
old = """        if (alias != null && !SamplingParams.maxTokensOk(alias)) return null to errMaxCompletionTokens"""
new = """        if (alias != null && (alias < 1 || alias > 8192)) return null to errMaxCompletionTokens"""
assert s.count(old) == 1, "桩①：没找到别名的范围校验"
s = s.replace(old, new, 1)'
must_red "桩①（别名另写一份上限 -> 两种字段名行为不同）" "别名（alias）同样过 maxTokensOk"
reset_src

# ── 2. 桩②：400 文案把上限**写死**成 8192（不再引用常量）────────────────────
patch "$PF" '
old = """        \"max_completion_tokens 必须是 1~${SamplingParams.MAX_TOKENS_CAP} 的整数\""""
new = """        \"max_completion_tokens 必须是 1~8192 的整数\""""
assert s.count(old) == 1, "桩②：没找到 400 文案"
s = s.replace(old, new, 1)'
must_red "桩②（上限写死 8192，改常量时文案与判据分叉）" "400 文案里的上限"
reset_src

# ── 3. 桩③：两个平行数组**不同源**（一个从 list、一个另拼）──────────────────
patch "$PF" '
old = """    fun biasValues(p: Parsed): FloatArray? = p.logitBias?.map { it.bias }?.toFloatArray()"""
new = """    fun biasValues(p: Parsed): FloatArray? = FloatArray(p.logitBias?.size ?: 0) { i -> p.logitBias!![i].bias }"""
assert s.count(old) == 1, "桩③：没找到 biasValues"
s = s.replace(old, new, 1)'
must_red "桩③（平行数组不同源）" "biasValues 从**同一个** p.logitBias"
reset_src

# ── 4. 桩④：偏置采样器挪到**选择器之后**（正偏置拉不回被砍掉的 token）──────
patch "$JNI" '
old = """    {
        const int n_bias = jbiasIds ? env->GetArrayLength(jbiasIds) : 0;"""
new = """    if (false) {
        const int n_bias = jbiasIds ? env->GetArrayLength(jbiasIds) : 0;"""
assert s.count(old) == 1, "桩④：没找到偏置挂载点"
s = s.replace(old, new, 1)
# 真的挪到 dist 之后：删掉原块，追加到链尾
import re
blk_start = s.index("    if (false) {")
blk_end = s.index("    if (temp <= 0.0f) {", blk_start)
block = s[blk_start:blk_end]
s = s[:blk_start] + s[blk_end:]
anchor = "        llama_sampler_chain_add(ch, llama_sampler_init_dist((uint32_t) seed));"
assert s.count(anchor) == 1, "桩④：没找到 dist 收尾"
s = s.replace(anchor, anchor + "\n" + block, 1)'
must_red "桩④（偏置挂在选择器之后）" "偏置挂在第一个选择器之前"
reset_src

# ── 5. 桩⑤：`logprobs` 字段**无条件出现**（else 支改成非空）────────────────
# 替换脚本单独成文件：要匹配的正是源码里那串 `" ,\"logprobs\":null"`，
# 内联进本文件的 heredoc 时会被三层引号层吃掉（那是**桩自己**写坏，
# 不是被守卫抓住的 —— 本套件第一版就这么假绿过一次）。
"$PY" tools/protocol_fields/stub_logprobs_always.py "$HTTP"
must_red "桩⑤（logprobs 字段无条件出现）" "logprobsFieldOf 的 else 支是空串"
reset_src

# ── 6. 桩⑥：`top_logprobs` 的**绑定判据**删掉（单独给会被静默接受）─────────
patch "$PF" '
old = """        if (tlRaw != null && !logprobs) return null to errTopLogprobsNeedsLogprobs"""
new = """        // 桩：绑定判据删掉"""
assert s.count(old) == 1, "桩⑥：没找到绑定判据"
s = s.replace(old, new, 1)'
must_red "桩⑥（top_logprobs 单独给被静默接受）" "top_logprobs 单独给必须报 400"
reset_src

# ── 7. 桩⑦：`describe()` 写成 `$p.maxTokens`（退化成 toString）─────────────
patch "$PF" '
old = """        if (p.maxTokens != null) sb.append(\"max_completion=${p.maxTokens} \")"""
new = """        if (p.maxTokens != null) sb.append(\"max_completion=$p.maxTokens \")"""
assert s.count(old) == 1, "桩⑦：没找到 describe 的取值行"
s = s.replace(old, new, 1)'
must_red "桩⑦（describe 写成 \$p.x，日志读数变成 toString）" "describe() 用 \${p.x} 形态"
reset_src

# ── 8. 桩⑧：key 解析**去掉非负判据**（`-12` 被收下）─────────────────────────
patch "$PF" '
old = """                val id = k.toIntOrNull()?.takeIf { it >= 0 && it.toString() == k }"""
new = """                val id = k.toIntOrNull()?.takeIf { it.toString() == k }"""
assert s.count(old) == 1, "桩⑧：没找到 key 解析"
s = s.replace(old, new, 1)'
must_red "桩⑧（负数 token id 被收下）" "key 解析显式判 it >= 0"
reset_src

# ── 9. 桩⑨：超限处 **return 400**（把能用的客户端每个请求都拒掉）───────────
patch "$PF" '
old = """            notices += \"logit_bias ${ordered.size} 条，超过内部上限 $MAX_LOGIT_BIAS 条，\" +
                \"已按 token id 升序保留前 $MAX_LOGIT_BIAS 条\""""
new = """            return null to \"logit_bias 条数超过上限\""""
assert s.count(old) == 1, "桩⑨：没找到超限留痕"
s = s.replace(old, new, 1)'
must_red "桩⑨（超限报 400，把客户端每个请求都拒掉）" "超限分支里没有 early return 400"
reset_src

# ── 9b. 桩⑨b/⑨c：超限截断**不排序**（取 HashMap 哈希序的任意子集）───────────
# 这是本轮新修的那条：`bias` 按 `o.keys()` 攒，而 `logit_bias` 是 JSON **对象**、
# `org.json` 内部是 `HashMap` —— `keys()` 是 String 哈希序。不排序时
# `subList(0, N)` 的成员不可预测，而日志说"前 N 条"。
# 两个桩分别打两个方向：完全没排序、以及排了但截断取的是原始 `bias`。
# 后者尤其重要 —— 只判"文件里有 sortedBy"会被它骗过（存在性断言的老毛病）。
patch "$PF" '
old = """        val ordered = bias.sortedBy { it.tokenId }"""
new = """        val ordered = bias"""
assert s.count(old) == 1, "桩⑨b：没找到排序动作"
s = s.replace(old, new, 1)
# 让超限判据也回到未排序的那一份，形成一个"完整回退到旧写法"的桩
s = s.replace("ordered.size > MAX_LOGIT_BIAS", "bias.size > MAX_LOGIT_BIAS", 1)'
must_red "桩⑨b（截断前完全不排序 -> 取哈希序子集）" "超限截断前按 tokenId 排序"
reset_src

patch "$PF" '
old = """            ordered.subList(0, MAX_LOGIT_BIAS).toList()"""
new = """            bias.subList(0, MAX_LOGIT_BIAS).toList()"""
assert s.count(old) == 1, "桩⑨c：没找到截断"
s = s.replace(old, new, 1)'
must_red "桩⑨c（排了序但截断仍取原始 bias -> 排序动作白做）" "截断取的不是排序后的那一份"
reset_src

# ── 10. 桩⑩：**其中一个**端点顺序写反（别名在该端点上失效）────────────────
# 单独成文件的替换脚本，理由写在它自己的文件头（三层引号层会把桩写坏）。
"$PY" tools/protocol_fields/stub_parse_order.py "$HTTP"
must_red "桩⑩（只把 chat 端点顺序写反 -> 该端点别名失效）" "顺序反了"
reset_src

# ── 11. 桩⑪：native 侧**不判**两个数组长度（按短的截断使用）────────────────
patch "$JNI" '
old = """        if (n_bias > 0 && n_bias == n_vals) {"""
new = """        if (n_bias > 0) {"""
assert s.count(old) == 1, "桩⑪：没找到长度判据"
s = s.replace(old, new, 1)'
must_red "桩⑪（长度不等时按短的用，接线 bug 被静默）" "两个数组长度不等时整条不挂"
reset_src

# ── 12. 还原后必须全绿 ─────────────────────────────────────────────────────
if run_guard; then ck "还原后：守卫全绿（确认桩没留下痕迹）" 1
else ck "还原后：守卫全绿（实际红了，restore 有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo
echo "=== 协议字段守卫自测：$ok 通过 / $bad 失败 ==="
[ "$bad" = 0 ]
