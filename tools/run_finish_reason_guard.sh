#!/bin/sh
# `finish_reason` 的源码级守卫 —— 四档优先级、以及"截断判据锚在哪"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 单测（`tools/run_protocol_fields_tests.sh` 里的 FinishReasonTest）证明
# `FinishReason.of` 这个**纯函数**的四个档位算得对。它证明不了：
#
#   ① 两个端点**都改到了** —— 只改 `handleChat` 的话，`/v1/completions`
#      照旧在被截断时报 stop，而单测全绿（它只测纯函数）。本仓库已经用
#      「逐端点各判一次」钉过同一类分叉（见 `run_protocol_fields_guard.sh`
#      的解析顺序那条），这里同样。
#   ② 旧那个三元**没有残留在别处** —— `else "stop"` 写回去就退化成"没有 length"，
#      而它编得过、单测（不测 HttpApi）也照样绿。
#   ③ "截断"判据锚在**循环怎么退出的**（`hitLimit` 在体内置位），
#      不是收尾处反推 `n >= maxTok` —— 后者会把"最后一步恰好吐出一个 token"
#      与"最后一步是 EOS"混成一谈（`n` 停在 maxTok-1 时两个判据都不成立，
#      但如果写成 `n >= maxTok` 而恰好 EOS 发生在 n == maxTok 那一刻，
#      就会把一次正常收尾报成截断）。
#   ④ `cancelled` 的优先级没有被 `length` 吃掉 —— 顺序反了的话
#      "被取消且正好跑满"会报 length，而调用方会以为"拉长上限就能拿到完整输出"。
#
# 运行：sh tools/run_finish_reason_guard.sh
set -e
cd "$(dirname "$0")/.."
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
FR=app/src/main/java/com/xiaowan/localinference/FinishReason.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() {
    if eval "$2" >/dev/null 2>&1; then echo "PASS  $1"; ok=$((ok+1)); return; fi
    echo "FAIL  $1"; bad=$((bad+1))
    eval "$2" 2>&1 | head -3 | sed 's/^/        ↳ /'
}

c "FinishReason.kt 存在" "[ -f \$FR ]"
c "HttpApi.kt 存在"      "[ -f \$HTTP ]"

# ── 1) 四档取值都在，且 length 是本 PR 要补的那一格 ────────────────────────
c "有 length 这个取值"     "grep -q 'const val LENGTH = \"length\"' \$FR"
c "有 tool_calls 这个取值" "grep -q 'const val TOOL_CALLS = \"tool_calls\"' \$FR"
c "有 stop 这个取值"       "grep -q 'const val STOP = \"stop\"' \$FR"
c "有 cancelled 这个自定取值" "grep -q 'const val CANCELLED = \"cancelled\"' \$FR"

# ── 2) 优先级：cancelled > length > tool_calls > stop ──────────────────────
# 判据是**分支的顺序**（与 ll_ama_jni 那两条行号判据同一做法，不靠肉眼 review）。
# 顺序反了不是报错，是某一档永远不可达 —— 正是最该钉的那类静默。
c "of() 的 when 分支顺序是 cancelled > length > tool_calls > stop" \
  "\$PY tools/finish_reason/check_priority_order.py \$FR"
c "hasToolCalls 是形参（不是从别处读全局）" \
  "grep -q 'fun of(cancelled: Boolean, hitLimit: Boolean, hasToolCalls: Boolean)' \$FR"

# ── 3) 旧的三元**没有残留** ────────────────────────────────────────────────
# 这一行写回去就退化成"被截断时报 stop"，而它处处编得过。
# 判据锚在**前缀** `if (cancelled) "cancelled" else` 上：旧那两处三元的左半截
# 都一样（右半截一个是 tool_calls，一个是 stop），锚左半截能把两种残留一起收住，
# 也不必为 sseEvent 那行另写一条（它同样以这半截开头）。
c "HttpApi 里没有残留任何 if (cancelled) cancelled else 形式的三元" \
  "[ \$(grep -c 'if (cancelled) .cancelled. else' \$HTTP) -eq 0 ]"

# ── 4) 两个端点**都**走 of()：逐端点各判一次 ───────────────────────────────
# 只判"文件里出现过 FinishReason.of" 是不够的：其中**一个**端点漏改照样绿，
# 而那个端点上被截断就报 stop（两个端点对同一字段给不同答案是本仓库最忌讳的）。
c "handleChat 非流式的 fin 走 of()" \
  "[ \$(grep -c 'val fin = FinishReason.of(cancelled, hitLimit, toolCallsJson != null)' \$HTTP) -eq 2 ]"
c "handleCompletion 非流式的 fin 走 of()（hasToolCalls=false）" \
  "grep -q 'FinishReason.of(cancelled, hitLimit, hasToolCalls = false)' \$HTTP"
c "handleCompletion 的流式收尾帧也走 of()（不是内联 stop）" \
  "[ \$(grep -c 'FinishReason.of(cancelled, hitLimit, hasToolCalls = false)' \$HTTP) -eq 2 ]"

# ── 5) 「截断」判据锚在循环怎么退出的 ──────────────────────────────────────
# hitLimit 必须在**循环体内**（顶部那支）置位，不是在收尾处反推 `n >= maxTok`。
c "两个端点都声明了 hitLimit（逐端点各一次）" \
  "[ \$(grep -c 'var hitLimit = false' \$HTTP) -eq 2 ]"
c "hitLimit 是在循环体内置的（两个端点各一次）" \
  "[ \$(grep -c 'if (n >= maxTok) { hitLimit = true; break }' \$HTTP) -eq 2 ]"
c "没有在收尾处反推 n >= maxTok 当截断（那会把 EOS 也覆盖）" \
  "[ \$(grep -c 'n >= maxTok) hitLimit' \$HTTP) -eq 0 ]"
# ⚠ 判据要锚**真实代码行**、不能锚"文件里出现过"这个串：注释里引用旧写法
# （"写成 `while (n < maxTok)` 的话…"）是**正常的**，锚串会把注释也判红 ——
# 那是判据过窄，不是真问题。这里用 python 判"非注释行里以 while 开头的那句"。
c "两个循环都没退回 while (n < maxTok) 的旧形态（不含注释引用）" \
  "\$PY tools/finish_reason/check_no_legacy_while.py \$HTTP"

# ── 6) 两档关键含义的注释还在（免得下一个人把 length 当"可省"删掉）──────────
c "文件里写清了「旧实现报 stop」这条代价" \
  "grep -q 'SDK 侧按' \$FR"
c "'被取消且正好跑满' 的取值有明确契约（取 cancelled）" \
  "grep -q '取 \`cancelled\`' \$FR"

echo
echo "=== finish_reason 守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
