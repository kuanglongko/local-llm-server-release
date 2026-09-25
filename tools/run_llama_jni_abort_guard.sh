#!/bin/sh
# 「取消（abort）必须带归属、且不得留下可复用的脏账本」的源码级守卫（模块 E：E-1 / E-2 / E-3 / E-5）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是只靠单测 / 读代码）
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫钉的四条，**没有一条会让别的测试变红**：
#
#   E-1 `S.abort` 是裸 `bool`（非 atomic / 非 volatile），而置位方是
#       HTTP 连接线程 / 主线程，读取方是生成线程 —— 这是 C++ 上的数据竞争（UB），
#       编译器可以把读取提升进寄存器只读一次，"取消"于是有时永远看不见。
#       稳态下 100% 正常，只有内存序真的被优化动到才会现形 ⇒ 读代码/跑测试都发现不了。
#
#   E-1b `nativeAbort()` 此前无条件置位、**不带轮次编号**，于是"上一轮迟到的取消"
#       会停掉**此刻才开始的下一轮**。Kotlin 侧 `RequestCancel` 专门为此做了归属，
#       而最后一跳把它丢了 —— 归属只防到了 JNI 门口。这类"错停对象"与
#       「服务随机抽风」同形，没有任何异常或日志。
#
#   E-2 `nativeAbort` 只置标志，既不作废账本、也不清 KV，于是被打断的那一轮在
#       KV 里的残留（prompt + 已算进去的若干生成 token）**照样被当成有效历史**，
#       下一轮直接复用 —— 正是 `kv_prefix.h` 文件头点名要防的「多留一段 ->
#       模型读到错位的历史 -> 答非所问，但接口 200、不报错」。没有异常、没有日志。
#
#   E-3 prefill 被打断 / decode 失败时把 `S.n_used` 写死 0，而 KV 里明明有内容 ——
#       /health 的 `ctx_used` 在**整段服务期里唯一一次**报错值，且它偏偏与
#       `kv_cache_valid=false` 同时出现，读日志的人会以为"KV 已清、可以放心复用"，
#       方向正好读反。
#
#   E-5 `kv_cache_valid=false` 同时覆盖「从未跑过」与「跑过但账本已失效」，
#       两者一个是冷启动、一个是取消/故障，只报前者会让它们同形。
#
# 所以判据只能钉在**结构关系**上（"是不是原子"、"清状态前有没有比代际"、
# "路径上有没有作废账本"），而不是"跑一遍看它绿"。
#
# 运行：sh tools/run_llama_jni_abort_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
RC=app/src/main/java/com/xiaowan/localinference/RequestCancel.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 剥掉 C++ 注释行（`//` 与块注释续行 `*`）。
# 为什么必须剥：本守卫的判据是"源码里有没有这种写法"，而**注释里**恰好会引用
# 那些被禁掉的旧写法（例如 nativeAbort 的说明里写着"此前是无条件置位"）。
# 不剥注释，守卫就会把"解释为什么不能这么写"当成"又这么写了" -> 恒红 -> 被人静音。
# 与 run_llama_jni_accept_guard.sh 的过滤方式一致。
# 只删注释**行**不够：本文件里有大量缩进注释（`    // 无条件 \`S.abort = true\``），
# 它们不以 `//` 开头。所以这里把行内 `//...` 之后的部分也截掉，并丢弃块注释行。
nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*\*' ; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"
c "LlmEngine.kt 存在"  "[ -f \$ENG ]"

# ── 0) 起锚点：确实装了 <atomic> ─────────────────────────────────────────
c "llama_jni.cpp 引入 <atomic>（跨线程置位/读取需要）" "grep -q '#include <atomic>' \$JNI"

# ── 1) E-1：S.abort 必须是原子量，且只经 .store()/.load() 访问 ───────────
c "Session::abort 声明为 std::atomic<bool>（不是裸 bool）" \
  "grep -q 'std::atomic<bool> abort{false};' \$JNI"
c "裸 bool abort 声明必须绝迹（它是 UB）" \
  "! grep -qE '^[[:space:]]*bool[[:space:]]+abort[[:space:]]*=' \$JNI"
c "abort 的写入统一走 store()" \
  "grep -q 'S.abort.store(true);' \$JNI && grep -q 'S.abort.store(false);' \$JNI"
c "abort 的读取统一走 load()（不得裸读 S.abort）" \
  "grep -q 'S.abort.load()' \$JNI"
# 裸赋值（S.abort = ...）必须绝迹：它是"绕过原子"的那条路，
# 少写一次 .store() 编译器就有权把它优化成非原子写。
c "裸赋值 S.abort = ... 已绝迹（原子量必须走 .store()）" \
  "! nocomment < \$JNI | grep -qE 'S\\.abort[[:space:]]*=[^=]'"
c "step 入口的取消判定读原子量" \
  "grep -q 'if (!S.ctx || !S.smpl || S.abort.load()) return nullptr;' \$JNI"

# ── 2) E-1b：取消必须带**归属**（轮次编号），归不上就不生效 ───────────────
c "Session 有轮次编号 round_epoch（原子）" \
  "grep -q 'std::atomic<long long> round_epoch{0};' \$JNI"
c "归属只留一个真值来源（不留没人读的 abort_epoch 死状态）" \
  "! grep -q 'abort_epoch' \$JNI"
c "startCompletion 领取新编号（自增在最前）" \
  "grep -q 'S.round_epoch.fetch_add(1);' \$JNI"
c "nativeAbort 的签名带轮次编号参数" \
  "grep -q 'nativeAbort(JNIEnv \\*, jclass, jlong roundEpoch)' \$JNI"
c "nativeAbort 先比归属再置位（编号不一致不生效）" \
  "grep -q 'if (roundEpoch == 0 || (long long) roundEpoch != cur) {' \$JNI"
c "nativeAbort 归属不符时留痕（不静默）" \
  "grep -q 'abort 被忽略：归属' \$JNI"
c "暴露 nativeCurrentEpoch 供 Kotlin 绑定归属" \
  "grep -q 'nativeCurrentEpoch(JNIEnv \\*, jclass)' \$JNI"
c "Kotlin 侧 beginCancelable 把编号绑进 token（否则归属止步于 JNI 门口）" \
  "grep -q 't.bindNativeEpoch(runCatching { nativeCurrentEpoch() }' \$ENG"
c "RequestCancel.Token 携带 nativeEpoch" \
  "grep -q 'var nativeEpoch: Long = 0L' \$RC"
c "Kotlin 侧有唯一的取消入口 requestAbort（标记 token + 送 native）" \
  "grep -q 'fun requestAbort(): RequestCancel.Token?' \$ENG && grep -q 'abortRound(t)' \$ENG"
c "Kotlin 侧 nativeAbort 声明带编号（签名与 native 一致）" \
  "grep -q 'nativeAbort(roundEpoch: Long)' \$ENG"

# ── 3) E-2：nativeAbort 必须作废账本（残留不得成为复用候选）─────────────
# 判据不是"文件里出现过 kv_invalidate"（那样删掉本处照样 PASS），
# 而是"nativeAbort 的函数体里有它" —— 取函数体再断言，见下面 BODY_ABORT。
LINE_A=$(grep -n 'LlmEngine_nativeAbort(JNIEnv' "$JNI" | cut -d: -f1)
END_A=$(awk -v s="$LINE_A" 'NR>s && /^}/{print NR; exit}' "$JNI")
BODY_A=$(sed -n "${LINE_A},${END_A}p" "$JNI")
c "nativeAbort 在函数体内作废账本（残留不作复用候选）" \
  "echo \"\$BODY_A\" | grep -q 'kv_invalidate(\"本轮被 abort（残留不作复用候选）\")'"
c "作废排在置位之后（顺序可核：先认领这一轮，再作废它的账本）" \
  "echo \"\$BODY_A\" | grep -q 'S.abort.store(true);'"
# 清 KV **不能**放在这里：本函数跑在 HTTP 连接线程 / 主线程上，而 KV 不是线程安全的。
c "nativeAbort 不得直接清 KV（非生成线程，KV 非线程安全）" \
  "! echo \"\$BODY_A\" | nocomment | grep -q 'seq_rm'"
c "next-round 全清是残留的真正出口（账本作废 -> 计划不命中 -> 全清）" \
  "grep -q 'llama_memory_seq_rm(mem, 0, -1, -1);' \$JNI"

# ── 4) E-3：prefill 中断 / decode 失败不得把 n_used 写死 0 ───────────────
# 取 prefill 循环体（从 'for (size_t off = start' 到函数结束）再断言。
LINE_S=$(grep -n 'JNIEXPORT jboolean JNICALL' "$JNI" | awk -F: '$0 ~ /StartCompletion/ {print $1}' | head -1)
if [ -z "$LINE_S" ]; then LINE_S=$(grep -n 'nativeStartCompletion' "$JNI" | head -1 | cut -d: -f1); fi
END_S=$(awk -v s="$LINE_S" 'NR>s && /^\}/{print NR; exit}' "$JNI")
BODY_S=$(sed -n "${LINE_S},${END_S}p" "$JNI")
# 取"以某条 kv_invalidate() 为起点、到其下第一条 return 为止"的**分支块**再断言，
# 而不是在整段函数里 grep —— 否则会把合法的 `S.n_used = 0`（计划不命中分支 /
# prompt 超长分支，那两处**确实**清过 KV）当成违规。判据必须钉在出问题的分支上。
branch_block() { # $1=invalidate 的文案片段
  awk -v k="$1" 'index($0,k){f=1} f{print} f && $0 ~ /return JNI_FALSE/ {exit}' "$JNI" | nocomment
}
c "prefill 中断分支不再写死 S.n_used = 0（读数与 KV 实况一致）" \
  "! branch_block 'kv_invalidate(\"prefill 被中断\")' | grep -q 'S.n_used = 0'"
c "prefill decode 失败分支同样不写死读数（同一条纪律，两处都要）" \
  "! branch_block 'kv_invalidate(\"prefill decode 失败\")' | grep -q 'S.n_used = 0'"
c "「本轮开始即被 abort」分支不再清零读数" \
  "! branch_block 'kv_invalidate(\"本轮开始即被 abort\")' | grep -q 'S.n_used = 0'"
c "prefill 中断仍作废账本（半截 prompt 不接受复用）" \
  "grep -q 'kv_invalidate(\"prefill 被中断\")' \$JNI"
c "prefill decode 失败仍作废账本" \
  "grep -q 'kv_invalidate(\"prefill decode 失败\")' \$JNI"
c "本轮开始即被 abort 的分支不再清零读数" \
  "grep -q 'if (S.abort.load()) { kv_invalidate(\"本轮开始即被 abort\"); return JNI_FALSE; }' \$JNI"
c "注释写明「不作废读数」的理由（防后人顺手加回去）" \
  "grep -q '不作废\"KV 里有几个 token\"这个读数' \$JNI"

# ── 5) E-5：区分「没跑过」与「跑过但账本无效」 ─────────────────────────
c "Session 有 kv_rounds 计数" "grep -q 'int   kv_rounds = 0;' \$JNI"
c "prefill 成功后记一轮（否则计数恒 0，字段失去意义）" \
  "grep -q 'S.kv_rounds++;' \$JNI"
c "kv_invalidate 不得清零 kv_rounds（作废账本 ≠ 没跑过）" \
  "! sed -n '/^static void kv_invalidate/,/^}/p' \$JNI | nocomment | grep -q 'S\.kv_rounds'"
c "stats 出口带上第三个读数（数组长度 3）" \
  "grep -q 'NewIntArray(3)' \$JNI && grep -q 'S.kv_rounds };' \$JNI"
c "LlmEngine 暴露 kvRounds（取引擎实况）" \
  "grep -q 'val kvRounds: Int get() = runCatching { nativeKvCacheStats()\\[2\\] }' \$ENG"
c "HttpApi /health 报 kv_rounds（且取的是引擎实况）" \
  "grep -q '\"kv_rounds\":\$kvRounds' \$HTTP && grep -q 'val kvRounds = if (loaded) LlmEngine.kvRounds else 0' \$HTTP"

# ── 6) E-4 的接线：取消时要能对账「已进账本 / 已下发」 ────────────────────
c "Session 有 gen_in_ledger（本轮已进账本的生成 token 数）" \
  "grep -q 'int   gen_in_ledger = 0;' \$JNI"
c "账本 push 处同步计数" "grep -q 'S.last_tokens.push_back((long long) tok); S.gen_in_ledger++;' \$JNI"
c "暴露 nativeRoundLedgerTokens" "grep -q 'nativeRoundLedgerTokens' \$JNI && grep -q 'nativeRoundLedgerTokens' \$ENG"
c "两个生成循环的取消收尾都对账（同一处判据）" \
  "[ \$(grep -c '引擎账本 \$ledger tok' \$HTTP) -ge 2 ]"

# ── 7) 归属必须在**取消**这一跳兑现（而不是只标记 Kotlin token）───────────
c "断连探测/心跳把取消送到 native（否则 prefill 阶段停不下来）" \
  "[ \$(grep -c 'LlmEngine.abortRound(cancel)' \$HTTP) -ge 2 ]"
c "App 停止按钮走同一个入口（不再只标记 token）" \
  "grep -q 'LlmEngine.requestAbort()' app/src/main/java/com/xiaowan/localinference/EngineActivity.kt"
c "handleAbort 走同一个入口" \
  "grep -q 'val t = LlmEngine.requestAbort()' \$HTTP"

if [ "$bad" -eq 0 ]; then
    echo "=== llama_jni abort 守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== llama_jni abort 守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
