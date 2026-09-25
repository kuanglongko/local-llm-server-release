#!/bin/sh
# 「纯逻辑层」四条静默缺陷的接线守卫（模块 C：可离线单测的纯逻辑）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠单测不够
# ═══════════════════════════════════════════════════════════════════════════
# `tools/run_thinking_tests.sh` / `run_sampling_tests.sh` 证明的是"**这些输入**
# 得到**这些输出**"，它们证明不了"仓库里那一处**结构**没有改回去" ——
# 本轮四条的共同点是**零症状**，且都藏在"显式给了值却被静默当成没给"或
# "只在收尾这一拍才出现"的位置：
#
#   C-1 `SessionStore` 的字符数保险丝写成
#       `while (sum > 24000 && msgs.size > 2)`：`size > 2` 是**先决条件**不是保底，
#       消息数正好为 2 时整条循环短路 —— 最需要兜底的会话（一轮问答各上万字）
#       恰好完全失效。症状是 native 侧静默截断 prompt 前半段 =「答非所问」。
#   C-2 `ThinkStream.flush()` 把 carry 里剩的字节**一律原样发出**：
#       截断/取消停在 `<thi` 时 `content` 尾部多出裸 `<think` / `</think`
#       （与文件头的故障③同一条判据，只是发生在收尾这一拍）。
#   C-3 `SamplingParams` 的 `seed` **自己另写**一套类型判据（只拦「字符串且非空」），
#       bool/array/object 一路落到 randomSeed() —— 显式给了值却被静默当成没给，
#       与文件头「非法的显式取值报 400」相反。
#   C-4 `ThinkingControl` 用 `j.has("enable_thinking")` 判"表态"，而 `has` 对
#       JSONObject.NULL 返回 true、`optBoolean` 又回落到默认值 true ——
#       `{"enable_thinking":null}` 被判成**开思考**；同一仓库的 `SamplingParams`
#       恰恰把 null 当"没给"，两处判据分叉。
#
# 四条都无法靠"读输出"发现（不崩、不报错、HTTP 200），所以判据锚定**结构关系**：
# 保险丝要在只剩 2 条时仍生效 / flush 必须剥标签前缀 / seed 必须与其它字段
# 共用同一条读取路径 / null 判据必须与 SamplingParams 对齐。
#
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_pure_logic_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
SS=$SRC/SessionStore.kt
TS=$SRC/ThinkStream.kt
SP=$SRC/SamplingParams.kt
TC=$SRC/ThinkingControl.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 0) 四个文件都在，且判据不会因文件改名而恒真 ────────────────────────────
c "SessionStore.kt / ThinkStream.kt / SamplingParams.kt / ThinkingControl.kt 都存在" \
  "[ -f \$SS ] && [ -f \$TS ] && [ -f \$SP ] && [ -f \$TC ]"

# ── 1) C-1：字符数保险丝必须能在「只剩 2 条」时仍生效 ───────────────────────
# 判据不是"文件里出现过 24000"，而是"上限被抽成具名常量、且落实处**不是**把
# `size > 2` 当先决条件的那条复合 while"。
c "字符数上限是具名常量（不再散落魔数）" \
  "grep -q 'const val MAX_CHARS = 24000' \$SS"
c "旧形状（size > 2 当先决条件的复合 while）已绝迹" \
  "! grep -q 'sumOf .* > 24000 && msgs.size > 2' \$SS"
c "裁剪逻辑抽成独立函数（可与条数保底分开处理）" \
  "grep -q 'private fun capChars(' \$SS"
c "先按上限裁历史、再把剩余超限量逐条截断（两级口径）" \
  "grep -q 'while (msgs.size > MIN_MSGS && total() > MAX_CHARS) msgs.removeAt(0)' \$SS && \
   grep -q 'msgs\[i\].put(\"content\", cur.substring(0, cur.length - cut))' \$SS"
# 判据只看**代码行**（注释里保留了旧写法的引用，那是给后来人看的）：
# 以 `//` 或 `*` 开头的行不算。
c "保底条数是具名常量，且代码里不再有 `msgs.size > 2` 这种先决条件" \
  "grep -q 'const val MIN_MSGS = 2' \$SS && \
   ! grep -vE '^\s*(//|\*)' \$SS | grep -q 'msgs.size > 2'"
c "裁剪必须留痕（否则症状是 native 静默截断 = 答非所问）" \
  "grep -q 'Log.w(TAG, \"会话 \$id 超过 \$MAX_CHARS 字符' \$SS"

# ── 2) C-2：flush 必须剥掉「整个 carry 就是标签前缀」的情况 ─────────────────
# 判据是"flush 里对 carry 跑了 partialTag 并按 body/keep 分开"，不是"出现过 partialTag"
# ——partialTag 本来就是 drain 的常客，只 grep 它等于没钉。
c "flush 里对 carry 计算标签前缀长度" \
  "grep -q 'val keep = maxOf(partialTag(carry, OPEN), partialTag(carry, CLOSE))' \$TS"
c "flush 不再把 carry 原样无条件下发" \
  "! grep -q 'if (carry.isNotEmpty()) emit(carry.toString(), inThink)' \$TS"
c "flush 只发非前缀那一段（body > 0 才发）" \
  "grep -q 'if (body > 0) emit(carry.substring(0, body), inThink)' \$TS"
c "丢弃量作为可读状态暴露" \
  "grep -q 'var droppedPartialTag: Int = 0' \$TS"
# 纯逻辑文件不得 import android.*（否则宿主单测直接 ExceptionInInitializerError
# / "Stub!"）：本文件被 tools/run_thinking_tests.sh 无 android 运行时跑。
c "ThinkStream 仍是纯逻辑（零 android import，否则宿主编不了）" \
  "! grep -q '^import android' \$TS"
c "SamplingParams / ThinkingControl 同样是纯逻辑" \
  "! grep -q '^import android' \$SP && ! grep -q '^import android' \$TC"
c "调用方对丢弃量留痕" \
  "grep -q 'think.droppedPartialTag' \$SRC/HttpApi.kt"

# ── 3) C-3：seed 必须与其它 9 个字段**共用同一条**读取路径 ─────────────────
c "seed 经由 read() 判 Absent/Bad/Ok（不再另写一套类型判据）" \
  "grep -q 'val seed = when (val r = read(j, \"seed\"))' \$SP"
c "seed 的非法类型必须报 400（Bad -> errSeed）" \
  "grep -q 'is Read.Bad -> return null to errSeed' \$SP"
c "seed 的缺席分支走随机" \
  "grep -q 'is Read.Absent -> randomSeed()' \$SP"
c "旧的「字符串且非空」特判判据已绝迹（正是 C-3 的根因）" \
  "! grep -q 'as? String)?.trim()?.isNotEmpty() == true' \$SP"
c "不再有仅服务于 seed 的 readLong 私有函数（避免再分叉）" \
  "! grep -q 'private fun readLong(' \$SP"

# ── 4) C-4：null 判据必须与 SamplingParams 对齐 ────────────────────────────
c "enable_thinking 的表态判据排除 null" \
  "grep -q 'j.has(\"enable_thinking\") && !j.isNull(\"enable_thinking\")' \$TC"
c "旧的「只看 has」形状已绝迹（has 对 null 返回 true）" \
  "! grep -q 'j.has(\"enable_thinking\") -> j.optBoolean' \$TC"
c "null 不得吞掉后续判据（kwargs / reasoning_effort 仍要被读到）" \
  "grep -q 'val kwEt = j.optJSONObject(\"chat_template_kwargs\")?.opt(\"enable_thinking\")' \$TC"
c "与 SamplingParams 的 null=Absent 判据一致（两处都排除 isNull）" \
  "grep -q 'if (!j.has(key) || j.isNull(key)) return Read.Absent' \$SP"

echo
echo "=== 纯逻辑接线守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
