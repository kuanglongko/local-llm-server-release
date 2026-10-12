#!/bin/sh
# 「嵌入失败可归因 + 池化语义交还模型 + 维度对照」的接线守卫（方案 B + C + A）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 用户报的三个现象（Qwen3-Embedding 生僻字偶发失败 / BGE 系条条失败 /
# 失败只给一句"检查嵌入模型与内存"）是**同一个根因的三个面**，而三个面都
# 具备本仓库反复记录的那类性质：**不崩、不报错、接口/界面照常**。
#
#   ① 输出维度不匹配（n_embd != n_embd_out）
#      —— 库按 n_embd_out 分配 output buffer，按 n_embd 步长读会跨进别的 buffer
#      （含未初始化 NaN）。症状：**某些条失败且时好时坏**，与文本内容无关 ——
#      失败取决于那块内存之前被谁写过。用户看到的是"改改词序某次成功之后就好了"。
#   ② 池化取错位置
#      —— BGE 系声明 CLS，我们取 last；非 CLS 位置没有过 LayerNorm，范数下溢成 0。
#      症状：**100% 复现的零范数**，与①的"时好时坏"形成对照。
#   ③ 失败静默
#      —— 只 skipped++，连"哪一条"都不记。症状：100 条坏 3 条时只能二分重试。
#
# 三条都**不会被 native 双常驻守卫发现**（那一份守的是"两份 Session 彼此隔离"），
# 也都**不会被宿主单测发现**（单测只测纯函数，测不到接线）。
# 所以判据锚**结构关系**：对照点与读取点的先后、logits 与 output_slot 是否同源、
# 失败清单是否真的带上了"哪一条"与实值。
#
# 锚"文件里出现过某关键字"是恒真的（写错的那一支里那个关键字还在）——
# 本仓库已吃过几次亏（run_kv_cache_guard 的标识符断言、run_schema_sampler_guard
# 的 awk 段）。每一份检查都是独立的 python 脚本，且有对应的自测（打桩源码树）。
#
# 运行：sh tools/run_embed_failure_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HDR=app/src/main/cpp/embed_text.h
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在"     "[ -f \$JNI ]"
c "LlmEngine.kt 存在"      "[ -f \$KT ]"
c "embed_text.h 存在"      "[ -f \$HDR ]"
c "RagIndexStore.kt 存在"  "[ -f \$STORE ]"

# ── 方案 B：输出维度对照（必须在读向量之前、且走纯函数）───────────────────
c "nativeEmbed 做输出维度对照（n_embd vs n_embd_out，读取之前，走纯函数）" \
  "$PY tools/embed_robust/check_dim_guard.py \$JNI"
c "输出维度对照的纯函数在 embed_text.h（宿主侧有单测）" \
  "grep -q 'output_dim_ok' \$HDR"
c "单测覆盖了 n_embd_out 更小 / 更大 / 取不到三档" \
  "grep -q 'n_embd_out 更小（越界读）必须拒绝' tools/embed_text_test.cpp"

# ── 方案 B：warm-up 把未初始化的 output buffer 写干净 ──────────────────────
# ⚠ 上一版这两条判据锚的是「文件里出现过 `warm-up` 这个字」与「那句日志在不在」
#   —— 两条都是**恒真**的：写错的那一版里那个字还在、那句日志也在（它讲的是
#   "decode 失败不影响加载"，而真实的故障是 warm-up **根本没写满 buffer**，
#   decode 甚至没走到就 abort 了）。这正是本仓库反复记录的那类假绿。
#   现在判据锚**结构关系**：warm-up 的 batch 是否由**同一个计划函数**产生、
#   logits 是否逐位取自计划、"跳过"分支是否留了日志、写满了没有是否**如实**回答。
c "warm-up 与真实编码路径**同形**（计划同源 / logits 同源 / 跳过不留假绿 / 如实报写满）" \
  "$PY tools/embed_robust/check_warmup_wiring.py \$JNI"
c "warm-up 的一组纯判据在 embed_text.h（宿主侧有单测）" \
  "grep -q 'warmup_plan_usable' \$HDR && grep -q 'warmup_wrote_all_rows' \$HDR"
c "单测钉住「1 个 token 的 warm-up 等于没做」与「CLS 只置一行必须判不可用」" \
  "grep -q '热身等于没做' tools/embed_text_test.cpp && grep -q '早先那版正是这一形态' tools/embed_text_test.cpp"
c "单测钉住「NONE/LAST 写不满时判据必须如实说没有」（不许假绿）" \
  "grep -q '判据必须如实说「没有」' tools/embed_text_test.cpp"

# ── 方案 A：池化语义交还模型 ──────────────────────────────────────────────
c "池化接线：读 pooling_type -> plan_pooling -> logits 与 output_slot 同源" \
  "$PY tools/embed_robust/check_pooling_wiring.py \$JNI"
c "池化纯函数 plan_pooling 在 embed_text.h" \
  "grep -q 'plan_pooling' \$HDR"
c "卸载时 pool_type 与 embedding 标记同组复位（漏一个特定顺序下静默错）" \
  "$PY -c \"import pathlib,sys; s=pathlib.Path('\$JNI').read_text(encoding='utf-8'); i=s.find('s.embedding = false;', s.find('static void unloadModelInternal')); sys.exit(0 if i>=0 and 's.pool_type = -1;' in s[i:i+600] else 1)\""
c "单测钉住四种 pooling 类型的下标（NONE/LAST/CLS/MEAN）与 RANK 的拒绝" \
  "grep -q 'pooling=4(RANK) 必须拒绝' tools/embed_text_test.cpp"
c "nativeEmbed 不再用裸 first_output_index（改由计划给定下标）" \
  "$PY -c \"import sys,pathlib; s=pathlib.Path('\$JNI').read_text(encoding='utf-8'); a=s.find('nativeEmbed('); b=s.find('nativeModelDesc', a); seg=s[a:b]; sys.exit(1 if 'first_output_index' in seg else 0)\""

# ── 方案 A（本轮）：语义与下标同源 + 归一化口径同源 ──────────────────────
c "单测钉住「计划自洽」：row/slot/logits 互相支持，错一个就不自洽" \
  "grep -q '整段都进 output 却仍读第 0 项 -> 不自洽' tools/embed_text_test.cpp"
c "单测钉住「排位→位置」换算：output_slot_to_batch 把两者分成两个数" \
  "grep -q '排位 0 -> batch 位置 4' tools/embed_text_test.cpp"
c "单测钉住「每一档都由我们归一化」（库对池化结果不做 L2）" \
  "grep -q '我们自己归一化（pooling=' tools/embed_text_test.cpp"
c "单测钉住「LAST(3) 是库给 pooled buffer 的第三档」（本次失效就是它）" \
  "grep -q 'LAST -> seq（⚠ 第三档' tools/embed_text_test.cpp"
c "单测钉住「LAST 语义读最后一个 token，而不是 output 数组第 0 项」" \
  "grep -q '第 0 项换算回 batch 位置 == 8（== nTokens-1）' tools/embed_text_test.cpp"
c "native 在**取向量之前**自检计划自洽（plan_is_coherent）" \
  "grep -q 'embedtext::plan_is_coherent(plan, nTok)' \$JNI"
c "归一化分岔走纯函数 normalize_pooling（口径不在 JNI 里各写一遍）" \
  "grep -q 'embedtext::normalize_pooling(poolType)' \$JNI"

# ── 返回值契约：分配长度与填充长度必须是同一个数（0.9.186 修）──────────────
# ⚠ 这一条守的是 nativeEmbed 末段那次「创建 + 填充」配对调用：曾经
#   `NewFloatArray(nEmbdOut)` 配 `SetFloatArrayRegion(out, 0, nEmbd, ...)` ——
#   两个数不相等时返回的数组带一截**静默零尾**，而调用方看到的长度是前者。
#   它当时没爆只是因为上游 output_dim_ok 要求严格相等（**另一处**判据在挡），
#   而那道闸只判"值相不相等"，从来没保证过"分配数 == 填充数"。
c "nativeEmbed 返回值契约：分配长度 == 填充长度（同一个标识符，不再各写一个数）" \
  "$PY tools/embed_robust/check_return_contract.py \$JNI"

# ── 方案 C：失败可归因 ────────────────────────────────────────────────────
c "失败不再静默：结构化详情 + 失败清单 + meta 记录 + 正文保留" \
  "$PY tools/embed_robust/check_failure_report.py \$KT \$JNI \$STORE"
# ⚠ 锚 `[embed]` 这个前缀（**单条**路径那条日志）：合批那条是 `[embedBatch]`，
# 它的措辞不同 —— 只 grep "归一化失败（范数=" 会被合批那条满足，
# 于是"单条路径不再报范数"这种退化会**假绿**（本仓库反复记录过"判据被
# 另一处满足"的假绿）。两处日志各锚各的前缀。
c "归一化失败报出实际范数" \
  "grep -q '\[embed\] 归一化失败（范数=' \$JNI"
c "失败原因名稳定（进 meta.json 的 failReason）" \
  "grep -q 'failure_name' \$HDR"
c "native 导出了 nativeEmbedLastFailure" \
  "grep -q 'Java_com_xiaowan_localinference_LlmEngine_nativeEmbedLastFailure' \$JNI"
c "Kotlin 声明了 nativeEmbedLastFailure" \
  "grep -q 'external fun nativeEmbedLastFailure' \$KT"
c "Kotlin 声明了 nativeEmbedOutDim / nativeEmbedPooling（与 native 导出对齐）" \
  "grep -q 'external fun nativeEmbedOutDim' \$KT && grep -q 'external fun nativeEmbedPooling' \$KT"

# ── 方案 C：UI 侧不能把「部分失败」当成「真失败」─────────────────────────
c "UI 区分「部分失败」（正文已落盘）与「真失败」" \
  "grep -q 'val partial = err != null && err.startsWith(\"成功 \")' \$EA"
c "部分失败时状态行/归属仍按已保存更新（否则用户反复重试同样内容）" \
  "grep -q 'if (err == null || partial)' \$EA"

echo
if [ "$bad" = 0 ]; then
    echo "=== 嵌入失败可归因守卫 全部通过（$ok 条）==="
else
    echo "=== 嵌入失败可归因守卫 $bad 条失败（共 $((ok+bad)) 条）==="
    exit 1
fi
