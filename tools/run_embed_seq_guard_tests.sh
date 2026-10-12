#!/bin/sh
# 自测 `tools/run_embed_seq_guard.sh`：拿几份**就地打桩的真源码**跑它，
# 断言"该红的红、且失败时点得出是哪一条"（ISSUE #269 第 3 步）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是**结构关系**（取向量按哪一支分岔 / 越界判在调用前还是后 /
# 实参是不是写死的 0），而在**正确**的源码上它们全绿 —— 一个永远绿的判据
# 与没有判据在效果上一样。
#
# 桩全部取自"真正会写错的写法"（多 seq 池化取值这一族）：
#   · 取向量只走 `ith`（不按 `read_from_seq` 分岔）—— MEAN/CLS/LAST 读到
#     `embd.data` 那块从未写入的内存，全零 -> 逐条 ZERO_NORM，
#     看起来像"合批把好条目弄坏了"；
#   · `get_embeddings_seq` 的实参写死 0 —— 8 条文本共用第 0 条的向量，
#     检索像"总是命中同几条"，而长度、维度、归一化全对；
#   · 越界判据挪到调用**之后**（等于装饰）—— 越界那一次已经发生，
#     而库对越界没有契约（可能返回别的行的"看起来能用"的指针）；
#   · 行号就地写 `j`（不经 `pooled_vector_slot`）—— 没有落点，下次改布局没人知道动哪；
#   · 单条路径丢掉 `read_from_seq` 分岔（两处判据分家）。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_embed_seq_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_embed_seq_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/embed_text.h
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
PREF=app/src/main/java/com/xiaowan/localinference/EmbedCtxPref.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
HEALTH=app/src/main/java/com/xiaowan/localinference/HealthCheck.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$HDR" "$TMP/hdr.bak"
cp "$KT" "$TMP/kt.bak"; cp "$ACT" "$TMP/act.bak"; cp "$PREF" "$TMP/pref.bak"
cp "$HTTP" "$TMP/http.bak"; cp "$HEALTH" "$TMP/health.bak"
restore() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/hdr.bak" "$HDR"
            cp "$TMP/kt.bak" "$KT"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/pref.bak" "$PREF"
            cp "$TMP/http.bak" "$HTTP"; cp "$TMP/health.bak" "$HEALTH"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/hdr.bak" "$HDR"
              cp "$TMP/kt.bak" "$KT"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/pref.bak" "$PREF"
              cp "$TMP/http.bak" "$HTTP"; cp "$TMP/health.bak" "$HEALTH"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 桩①：合批取向量**不按** `read_from_seq` 分岔（只走 ith）────────────────
# 这正是撤闸后最容易漏的那一处：`batchable_pooling` 恒真了，
# 而取向量那一支若还是只走 `ith`，MEAN/CLS/LAST 读的是 `embd.data`
# （池化结果在 `result_embd_pooled` 张量里，那块**从未写入**）→ 全零。
# 症状被下游读成"合批把好条目弄坏了"，而真因是我们问错了 API。
"$PY" - "$JNI" <<'PYEOF1'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            float * raw = from_seq ? llama_get_embeddings_seq(E.ctx, (llama_seq_id) slot)
                                   : llama_get_embeddings_ith(E.ctx, slot);"""
assert s.count(old) == 1, "stub1: anchor miss"
new = """            (void) from_seq;
            float * raw = llama_get_embeddings_ith(E.ctx, slot);   // 只走 ith"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF1
# 判据必须点出"库自己池化的那三档取不到"（`get_embeddings_seq` 不在合批里了）
must_red "桩①（取向量只走 ith，不分岔）" "没有 llama_get_embeddings_seq"
reset_src

# ── 桩①b：把分岔判断本身去掉（`from_seq` 恒为假）─────────────────────────
# 与桩① 是同一个病的两种写法：桩① 是调用点直接调 ith，
# 桩①b 是"分岔器被绕过"（读的那个量变了，而调用点看起来仍然分岔）。
# 两种都必须红 —— 只钉调用点的话，"分岔器读错量"这一种会绿着通过。
"$PY" - "$JNI" <<'PYEOF1B'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    const bool from_seq = embedtext::read_from_seq(poolType);"
assert s.count(old) == 1, "stub1b: anchor miss"
new = "    const bool from_seq = false;   // 分岔器被绕过"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF1B
must_red "桩①b（分岔器被绕过）" "from_seq 不是取自 read_from_seq(poolType)"
reset_src

# ── 桩②：`get_embeddings_seq` 的实参写死 0（等于单条路径的实参）──────────
# 8 条文本共用第 0 条的语义 —— 检索像"总是命中同几条"，
# 而长度、维度、归一化**全对**，接口 200，日志干净。
"$PY" - "$JNI" <<'PYEOF2'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            float * raw = from_seq ? llama_get_embeddings_seq(E.ctx, (llama_seq_id) slot)
                                   : llama_get_embeddings_ith(E.ctx, slot);"""
assert s.count(old) == 1, "stub2: anchor miss"
new = """            float * raw = from_seq ? llama_get_embeddings_seq(E.ctx, 0)
                                   : llama_get_embeddings_ith(E.ctx, slot);"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF2
must_red "桩②（seq 实参写死 0）" "实参写死成 0"
reset_src

# ── 桩③：越界判据挪到**调用之后**（先调库再判 — 等于装饰）───────────────
# 这是"判据存在但方向错了"的那一类：它**看起来**有判据（grep 得到），
# 而越界那一次已经发生了。库对越界没有契约，可能返回别的行的
# "看起来能用"的指针 —— 那是别人的语义，而下游一切正常。
#
# ⚠ 桩只在 `nativeEmbedBatch` 的**函数体范围内**动手（先切出函数体再改再拼回）——
# 上一版直接对全文 `index("llama_get_embeddings_seq")` 找位置，
# 结果把块搬进了**单条路径**（那里也有 `get_embeddings_seq`），
# 于是"还原"把单条路径改坏、而合批那一处保持完好：
# 桩自己污染了源码树，后面的每一根桩都在一个坏掉的世界里跑。
# ⚠ 桩只用**文本替换**（不切函数体、不按行位移）——上一版按行搬块时，
# 中途 assert 失败会把源码留在**半改**状态，而 `set -e` 让脚本直接退出，
# `reset_src` 根本没跑到：后续每一根桩都在一个坏掉的世界里跑，
# 于是"守卫红了"这件事本身失去意义。桩必须**先算好新文本、再一次性写**。
"$PY" - "$JNI" <<'PYEOF3'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把越界 if 块从 raw 赋值**之前**整段移到之后（先调库再判 = 装饰）。
# 用两处**完整字面量**做定位，任一处找不到就报错退出（不写文件）。
block = """            if (from_seq && !embedtext::pooled_slot_is_row(slot, segRows)) {
                jlog("[embedBatch] seq 下标越界（slot=%d, 本段 %d 条），整批拒绝——这是填充逻辑的 bug",
                     slot, segRows);
                embed_failure_set(embedtext::EmbedFailure::POOLING_CONTRACT, plan.n_tokens,
                                  nEmbd, nEmbdOut, poolType, 0.0);
                // ⚠ 这里**不许**再 `llama_batch_free(b)` —— `b` 已经在 decode 之后
                // 还回去了，双重释放是进程级崩溃，而它的现场指向的是这里，
                // 不是真正的错（越界下标）。
                return nullptr;
            }
"""
raw = """            float * raw = from_seq ? llama_get_embeddings_seq(E.ctx, (llama_seq_id) slot)
                                   : llama_get_embeddings_ith(E.ctx, slot);
"""
assert s.count(block) == 1, "stub3: 块锚点未命中"
assert s.count(raw) == 1, "stub3: raw 锚点未命中"
# 一次性构造新文本：去掉块 -> 在 raw 之后插回
s2 = s.replace(block, "", 1).replace(raw, raw + block, 1)
assert s2 != s, "stub3: 替换没生效"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF3

must_red "桩③（越界判据挪到调用之后）" "pooled_slot_is_row"
reset_src


# ── 桩④：行号就地写 j（不经 `pooled_vector_slot`）────────────────────────
# ⚠ 这一桩与桩①不同：桩①打断的是**分岔**（两条路只剩一条），
# 这一桩打断的是**落点**（行号就地写，没有可判据的那一处）——
# 症状是"下次改布局没人知道该动哪"：改动点散在调用点里。
"$PY" - "$JNI" <<'PYEOF4'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            const int slot = from_seq ? embedtext::pooled_vector_slot(gi) : j;"
assert s.count(old) == 1, "stub4: anchor miss"
# 保留分岔、但行号不再经那个函数（就地写段内下标）
new = "            const int slot = from_seq ? gi : j;   // 就地写，没有落点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF4
must_red "桩④（行号就地写 j，不经 pooled_vector_slot）" "没有经 pooled_vector_slot"
reset_src

# ── 桩⑤：单条路径丢掉 `read_from_seq` 分岔（两处判据分家）────────────────
# 合批与单条必须是**同一个**分岔器：抄成两份时迟早只改一处，
# 而症状是"同一条文本在两条路径上编码成不同的向量"——用户改了批大小，
# 索引里的语义就变了，而没有任何日志指向这里。
"$PY" - "$JNI" <<'PYEOF5'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    if (embedtext::read_from_seq(poolType)) {"
assert s.count(old) == 1, "stub5: anchor miss"
new = "    if (poolType == 1 || poolType == 2 || poolType == 3) {   // 手抄一份"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF5
must_red "桩⑤（单条路径手抄分岔）" "（单条路径）没有按 read_from_seq 分岔"
reset_src

# ── 桩⑥：退回『库自己池化的档不合批』（本轮要消掉的那件事）──────────────
# 与桩①~⑤不同：这一桩**不会**红在任何"结构"判据上
#（仍然一份清单、仍然同源），代价却正是本轮要消掉的收益 ——
# BGE（CLS）与 Qwen3-Embedding（LAST）一条都合批不上，而界面上只是"没变快"。
"$PY" - "$HDR" <<'PYEOF6'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return plan_pooling(poolTypeRaw, 1).valid;"
assert s.count(old) == 1, "stub6: anchor miss"
new = "    return !library_gives_seq(poolTypeRaw);   // 退回旧方向"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF6
must_red "桩⑥（退回『库池化的档不合批』）" "没有退回"
reset_src

# ── 桩⑦：`pooled_vector_slot` 里出现 native 符号（不再是纯函数）──────────
# 纯函数是**能在宿主侧逐项对照**的前提（`run_embed_text_tests.sh` 组 9）。
# 一旦它开始碰 llama / I/O，"行号算得对不对"就只能上真机看 —— 而那正是
# 这一轮试图摆脱的东西（本环境无 NDK / 无设备 / 无嵌入模型）。
"$PY" - "$HDR" <<'PYEOF7'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """inline int pooled_vector_slot(int rowIdx) {
    // 行号就是 seq 号 —— 由 `plan_batch` 保证（`BatchRow::seq == j`，
    // 且 `batch_is_valid` 逐条要求 `r.seq == j`）。
    return rowIdx;
}"""
assert s.count(old) == 1, "stub7: anchor miss"
new = """inline int pooled_vector_slot(int rowIdx) {
    llama_seq_id s = (llama_seq_id) rowIdx;
    return (int) s;
}"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF7
must_red "桩⑦（行号函数里出现 native 符号）" "必须能被宿主单测直接编译"
reset_src

# ── 桩⑧：`read_from_seq` 丢掉 `library_gives_seq`（读取侧分岔被一起撤掉）──
# 撤的是"合批那一层的闸"，不是"读取侧的分岔" —— 后者决定**读哪一路 buffer**，
# 撤掉它 MEAN/CLS/LAST 会去读 `embd.data`（全零），而 NONE 会去读 `embd_seq`。
"$PY" - "$HDR" <<'PYEOF8'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return library_gives_seq(poolTypeRaw);         // MEAN / CLS / LAST：库自己池化"
assert s.count(old) == 1, "stub8: anchor miss"
new = "    return poolTypeRaw == 1;   // 手抄：只认 MEAN"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF8
must_red "桩⑧（read_from_seq 丢掉 library_gives_seq）" "没被一起撤掉"
reset_src

# ── 桩⑨：合批**不读**库的 n_batch（自己推一个容量）──────────────────────
# 0.9.209 那一次真机闪退（`LM_GGML_ASSERT(n_tokens_all <= cparams.n_batch)`）
# 的根因就是"整批容量没人问过库"。自己按"条数 × 每条预算"推一个数时，
# 它与库的实际 `n_batch` 分岔 —— 而分岔的报应不是"这一批失败"，
# 是**整个进程 abort**（用户看到的"建索引闪退"）。
"$PY" - "$JNI" <<'PYEOF9'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    const int nBatch = (int) llama_n_batch(E.ctx);"
assert s.count(old) == 1, "stub9: anchor miss"
new = "    const int nBatch = kEmbedBatchRows * E.n_ctx_seq;   // 自己推一个"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF9
must_red "桩⑨（不读库的 n_batch，自己推容量）" "没有读库的 n_batch"
reset_src

# ── 桩⑩：容量判据挪到 `llama_decode` **之后**（等于装饰）─────────────────
# 这是"判据存在但方向错了"的那一类：grep 得到那个函数名，
# 而 abort 已经在 decode 那一行发生了 —— 后面的代码一行都执行不到。
# ⚠ 0.9.210 起判据有两处（切段循环里的 `batch_chunk_rows` 与每段提交前的
# `batch_fits_n_batch`），**两处都要搬** —— 判据问的是"**最早**那一处在哪边"，
# 只搬一处时另一处还在前面，它会照样全绿（桩没打在判据锚的量上）。
"$PY" - "$JNI" <<'PYEOF10'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
NL = chr(10)
cap = "    if (!embedtext::batch_fits_n_batch(segTokens, nBatch)) {"
assert s.count(cap) == 1, "stub10: fits anchor miss"
chunk = "        const int segRows = embedtext::batch_chunk_rows(rest, nBatch);"
assert s.count(chunk) == 1, "stub10: chunk anchor miss"
s2 = s.replace(chunk + NL, "", 1)
# ⚠ 锚带上下文：`if (llama_decode(E.ctx, b) != 0) {` 在**单条路径**里也有一处，
# 裸锚会改到那一边（桩自己污染源码树 —— 本仓库吃过这个亏）。
dec = """        if (llama_decode(E.ctx, b) != 0) {
            jlog("[embedBatch] decode 失败（本段 n_tokens=%d，第 %d/%d 段），返回 null 回落逐条","""
assert s2.count(dec) == 1, "stub10: decode anchor miss"
tail = NL + chunk + NL + cap + """
                jlog("矛盾");
                return nullptr;
            }"""
s2 = s2.replace(dec, dec + tail, 1)
assert s2 != s, "stub10: 替换没生效"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF10
must_red "桩⑩（容量判据挪到 decode 之后）" "判据必须是提交的**门禁**"
reset_src

reset_src

# ── 桩⑪：`llama_batch_init` 仍按**整批**容量申请 ─────────────────────────
# 申请整批、只填切出来的那一段时，后面那些 `token/pos/seq_id` 是
# **未初始化内存** —— 而计划的自检（`batch_is_valid`）看的是**计划**，
# 不是我们实际填进 `llama_batch` 的那几个字节，所以它照样全绿。
"$PY" - "$JNI" <<'PYEOF11'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        llama_batch b = llama_batch_init(segTokens, 0, 1);"
assert s.count(old) == 1, "stub11: anchor miss"
new = "        llama_batch b = llama_batch_init(plan.n_tokens, 0, 1);   // 整批容量"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF11
must_red "桩⑪（batch_init 按整批容量申请）" "llama_batch_init 按**整批**容量申请"
reset_src

# ── 桩⑫：取向量仍按 `plan.n_rows` 走（读**未提交**的那些行）──────────────
# 只提交了前几条时，后面那几条会去读 `embd_seq` 里**未写入**的行 ——
# 内容取决于上一批（或从未初始化），而长度、维度、归一化**全对**。
"$PY" - "$JNI" <<'PYEOF12'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        const bool from_seq = embedtext::read_from_seq(poolType);
        for (int j = 0; j < segRows; j++) {"""
assert s.count(old) == 1, "stub12: anchor miss"
new = """        const bool from_seq = embedtext::read_from_seq(poolType);
        for (int j = 0; j < plan.n_rows; j++) {"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF12
must_red "桩⑫（取向量按整批条数走）" "plan.n_rows"
reset_src

# ── 桩⑬：干脆不拦 —— 装不下时**硬着头皮提交**（0.9.208 的写法）──────────
# 这是真机 abort 的原始写法：合批把 8 条塞进同一个 batch，整批 token 数
# 没人看过库的 `n_batch`。它的报应不是"这一批失败"，是**整个进程消失**
# （建索引闪退），所以判据必须能把它钉红 —— 这是本守卫存在的最初理由。
# ⚠ 0.9.210 起"拦"这件事由**切段循环**承担（一段一验、一段一交），所以本桩
# 拆的是循环本身：循环一没，第一段之后的那几条再也没人提交过，
# 而整批的 token 数也没人验 —— 就是 0.9.208 的样子。
"$PY" - "$JNI" <<'PYEOF13'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
NL = chr(10)
loop = "    while (submitted < plan.n_rows) {"
assert s.count(loop) == 1, "stub13: loop anchor miss"
# 把循环头换成"只走一趟"的 if —— 身子照旧（第一段还是提交了），
# 但"余下几条还要再切一段"这件事没了，整批 token 数也没人验过。
s2 = s.replace(loop, "    if (submitted < plan.n_rows) {", 1)
# 推进语句搬到循环外（否则 `submitted += segRows` 会被 while 的语义带着走）
s2 = s2.replace("        decodeCount++;" + NL + "        submitted += segRows;" + NL,
                "    decodeCount++;" + NL + "    // （桩：只提交第一段，余下不再切）" + NL, 1)
assert s2 != s, "stub13: 替换没生效"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF13
must_red "桩⑬（装不下时硬着头皮提交）" "没有『切段提交』的循环"
reset_src

# ── 桩⑭：装不下时改写成『只提交前几条、余下记成失败』────────────────────
# 这一桩**不会**撞 abort（容量判据把整批拦住了），所以它比桩⑬ 更隐蔽：
# 它把"这一批排不下"渗透成"这几条坏了" —— 那几条进 `failedIdx` →
# `savePartial` → **整个库**被标成"索引待重建"，而用户看到的是"这几条坏了"。
# 判据必须能分辨"切段接着编"与"余下记失败"这两种出口。
"$PY" - "$JNI" <<'PYEOF14'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
NL = chr(10)
old = "    if (chunkRows < plan.n_rows) {"
assert s.count(old) == 1, "stub14: anchor miss"
# 把"切段接着编"改成"余下记失败"：拆掉 while 循环，改成从首段起点记账失败
loop = "    while (submitted < plan.n_rows) {"
assert s.count(loop) == 1, "stub14: loop anchor miss"
s2 = s.replace(loop, "    if (false) {", 1)
endmark = "    // 切段的读数：**一次调用替库省了几次 decode** 只有这里说得出来。"
assert s2.count(endmark) == 1, "stub14: end anchor miss"
s2 = s2.replace(endmark,
                "    for (int j = chunkRows; j < plan.n_rows; j++) g_embed_batch_failed.push_back(live[(size_t) j]);"
                + NL + endmark, 1)
assert s2 != s, "stub14: 替换没生效"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF14
must_red "桩⑭（余下记失败，而不是切段接着编）" "失败记账循环"
reset_src

# ── 桩⑮：装不下时**直接整批返回 null**（0.9.209 的出口）──────────────────
# 这一桩不崩、也不产生假失败 —— 它只是把 4~8 整段的合批收益一起扔掉
# （104 条 = 104 次 decode），而日志里"回落逐条"看起来永远是个安全出口。
# 判据必须能分辨"切段"与"整批回落"这两种出口 —— 否则这一版改回去
# 谁都不会发现（用户看到的是"没变快"，而"没变快"有个现成的解释：
# 这个模型的 pooling 不合批）。
"$PY" - "$JNI" <<'PYEOF15'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    if (chunkRows < plan.n_rows) {"""
assert s.count(old) == 1, "stub15: anchor miss"
loop = "    while (submitted < plan.n_rows) {"
assert s.count(loop) == 1, "stub15: loop anchor miss"
s2 = s.replace(loop, "    if (false) {", 1)
endmark = "    // 切段的读数：**一次调用替库省了几次 decode** 只有这里说得出来。"
assert s2.count(endmark) == 1, "stub15: end anchor miss"
# 0.9.209 的出口 —— 判据在循环之外（因为循环已经没了），整批直接返回
s2 = s2.replace(endmark,
                "    if (chunkRows < plan.n_rows) return nullptr;" + chr(10) + endmark, 1)
assert s2 != s, "stub15: 替换没生效"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF15
must_red "桩⑮（装不下时整批返回 null，0.9.209 的出口）" "直接整批返回 null 回落逐条"
reset_src

# ── 桩⑯：切段时**段长写死首段那个数**（后面几段悄悄越界）──────────────
# 这是"切段"这件事最容易写歪的一处，也最危险：首段装得下（所以看起来对），
# 后面几段按同一个条数取时总 token 数可能超过 `n_batch` —— 而越界的报应是
# **进程级 abort**（不是"这一段失败"）。判据必须锚"段长按余下的 counts 重算"。
"$PY" - "$JNI" <<'PYEOF16'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        const int segRows = embedtext::batch_chunk_rows(rest, nBatch);"
assert s.count(old) == 1, "stub16: anchor miss"
new = "        const int segRows = embedtext::batch_chunk_rows(liveCounts, nBatch);   // 写死整批"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF16
must_red "桩⑯（段长按整批 counts 算，不重算）" "段长用的还是**整批**的 counts"
reset_src

# ── 桩⑰：段内总量写成 `plan.rows[末条].begin + .n`（整批下标）─────────────
# 这是切段落地时最容易错、也最安静的一处（本版第一稿就踩了）：
# `begin` 是**整批**下标的起点，第二段起就带上前面几段的偏移
#（8 条满长时第二段算出 4088 > 2048），于是"这一段装得下"恒假 →
# 整批回落逐条。**不报错**，只是收益静默丢掉 —— 而"没变快"有个现成的解释
#（"这个模型的 pooling 不合批"），所以谁都不会发现。
"$PY" - "$JNI" <<'PYEOF17'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        int segTokens = 0;
        for (int j = 0; j < segRows; j++)
            segTokens += plan.rows[(size_t) (submitted + j)].n;"""
assert s.count(old) == 1, "stub17: anchor miss"
new = """        const embedtext::BatchRow & lastRow = plan.rows[(size_t) (submitted + segRows - 1)];
        const int segTokens = lastRow.begin + lastRow.n;   // 整批下标"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF17
must_red "桩⑰（段内总量用整批下标算）" "段内 token 总量不是"
reset_src

# ── 桩⑱：填 batch 用**整批**下标当槽位（不减 segBegin）────────────────────
# 第二段会从第一段末尾那一格（2044）开始写，而 `b` 只有这一段的容量
#（2044）—— 越界写堆，且 0..2043 格留空（噪声进了这一段每个 token 的位置/
# seq_id/logits）。它不是"算得慢"，是**算错**，而向量长度、维度、归一化全对。
"$PY" - "$JNI" <<'PYEOF18'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                const int slot = g - segBegin;                             // 段内槽位"""
assert s.count(old) == 1, "stub18: anchor miss"
new = """                const int slot = g;                                        // 整批下标"""
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF18
must_red "桩⑱（段内槽位用整批下标）" "段内槽位没有减掉这一段起点"
reset_src


# ── 桩⑲：加载期**不报** `n_ubatch`（只报 n_batch）─────────────────────────
# 0.9.211 补的两道门里，`n_ubatch` 是**非因果模型**（BERT 系 bge，走
# `llama_encode`）那一扇（`:1426`）。少了它时，用户报"合批 2 也崩"，
# 读的人只能看见 `n_batch` —— 而 bge **根本不经过** n_batch 那道门，
# 于是唯一的读数把人指向错的那一扇（本仓库已在 PR #285 讨论里照此推错两轮）。
"$PY" - "$JNI" <<'PYEOF19'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'E.pool_type, E.n_batch, E.n_ubatch,'
assert s.count(old) == 1, "stub19: anchor miss"
# 文案里去掉 n_ubatch、实参也只留一个 —— 两处都改，免得"只是文案不同"被蒙对
s2 = s.replace(old, 'E.pool_type, E.n_batch,', 1).replace(
    '"n_embd=%d, n_embd_out=%d, pooling=%d, n_batch=%d, n_ubatch=%d, full_rows_fit=%d",',
    '"n_embd=%d, n_embd_out=%d, pooling=%d, n_batch=%d, full_rows_fit=%d",   // 漏了 n_ubatch', 1)
assert s2 != s, "stub19: 替换没生效（静默 no-op）"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF19
must_red "桩⑲（加载期漏报 n_ubatch）" "没有报 n_ubatch"
reset_src

# ── 桩⑳：加载期打的是 `cp.n_ubatch`（我们传给库的值，而那条路**没传**）──
# `cp.n_ubatch` 在嵌入这条加载路径上从没被赋值 → 打出的是 struct 默认值
#（llama.cpp 里是 0 或哨兵），读的人会以为"门是 0、永远装不下" ——
# 比不打更坏：一个错的读数会把下一轮分析整个带偏。
"$PY" - "$JNI" <<'PYEOF20'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'E.pool_type, E.n_batch, E.n_ubatch,'
assert s.count(old) == 1, "stub20: anchor miss"
new = 'E.pool_type, E.n_batch, (int) cp.n_ubatch,   // 打我们传的值，而不是库的实数'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF20
must_red "桩⑳（加载期打 cp.n_ubatch 而非库的实数）" "cp.n_ubatch"
reset_src

# ── 桩㉑：提交前那一行**挪到 `llama_decode` 之后**（等于装饰）──────────────
# 与桩⑩ 同一条理由，但锚的是**读数**：abort 在 decode 那一行已经发生，
# 后面的代码一行都执行不到 —— 那一行永远不落盘，等于没有。
# ⚠ 搬到的落点必须是**整段提交之后**（`decodeCount++` 那一行）——
# 搬到 `if (llama_decode(...)) {` 之前是**原地不动**（那一行本来就贴在那儿），
# 桩就成了一次空操作：自测绿着，而判据从没被验过。本仓库反复记录这一类假绿。
"$PY" - "$JNI" <<'PYEOF21'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
NL = chr(10)
blk = """        jlog("[embedBatch] 提交：本段 %d 条 / %d tok（第 %d 段，本批共 %d 条），"
             "n_batch=%d n_ubatch=%d",
             segRows, segTokens, decodeCount + 1,
             plan.n_rows, nBatch, (int) llama_n_ubatch(E.ctx));
"""
assert s.count(blk) == 1, "stub21: anchor miss"
s2 = s.replace(blk, "", 1)
tail = "        decodeCount++;"
assert s2.count(tail) == 1, "stub21: decodeCount anchor miss"
s2 = s2.replace(tail, blk + tail, 1)
assert s2 != s, "stub21: 替换没生效"
open(p, 'w', encoding='utf-8').write(s2)
PYEOF21
must_red "桩㉑（提交前的读数挪到 decode 之后）" "排在 \`llama_decode\` **之后**"
reset_src

# ── 桩㉒：提交前那一行报的是**推出来的上限**（每条预算 × 条数）─────────────
# 这就是"511 天花板"那个错的复现：`E.n_ctx_seq * segRows` 是**取不到**的真值
#（单条路径每次最多 n_ctx_seq-1 个 token），拿它去解释崩溃正是本仓库
# 在 PR #285 讨论里被打回来的那一次。读数必须是 `segTokens`（真实提交量）。
"$PY" - "$JNI" <<'PYEOF22'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "             segRows, segTokens, decodeCount + 1,"
assert s.count(old) == 1, "stub22: anchor miss"
new = "             segRows, E.n_ctx_seq * segRows, decodeCount + 1,   // 推出来的上限"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF22
must_red "桩㉒（提交前报推出来的上限，不是 segTokens）" "不是 \`segTokens\`"
reset_src


# ── 桩㉓：两道门都不显式设（= 0.9.211 及以前的形态）───────────────────────
# 这正是真机 abort 的成因：两道门里少设一扇时，**非因果模型（bge）那扇仍是库默认**
#（`min(n_batch,512)`），而"哪一扇是默认值"没人一眼看得出来。删掉那两行赋值
# 就复现了 0.9.211 的处境 —— 守卫必须指名"没有把 cp.n_ubatch 显式设"。
"$PY" - "$JNI" <<'PYEOF23'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    cp.n_batch  = (uint32_t) gate;\n    cp.n_ubatch = (uint32_t) gate;\n"
assert s.count(old) == 1, "stub23: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
PYEOF23
must_red "桩㉓（两道门都不显式设 = 0.9.211 的形态）" "没有把 \`cp.n_ubatch\` 显式设"
reset_src

# ── 桩㉔：两扇门各写一个数（不收口到同一个 gate）───────────────────────────
# "两处各写一遍"是本仓库反复记录的一族：分岔时报应是 abort（`n_batch` 那条）
# 或"加载失败"（`n_batch % n_ubatch != 0` 那条）。这里把 ubatch 写死成 512 ——
# **看起来**是"回到库默认、更保守"，实际是 bge 那扇门继续一撞就 abort。
"$PY" - "$JNI" <<'PYEOF24'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    cp.n_ubatch = (uint32_t) gate;"
assert s.count(old) == 1, "stub24: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "    cp.n_ubatch = (uint32_t) 512;", 1))
PYEOF24
must_red "桩㉔（两道门不收口到同一个 gate）" "没有把 \`cp.n_ubatch\` 显式设成同一个 gate"
reset_src

# ── 桩㉕：加载期那一行改打 `cp.n_batch`（我们传的值，而不是库的实数）──────
# 这正是 0.9.211 的判据钉过的那一格：打 `cp` 等于担保"库收下了我们给的数"，
# 而那正是本仓库被打回来过两次的假设。0.9.212 之后它更容易写错 ——
# 因为 `cp` 里**确实**有我们设的值，看起来"打它更直接"。
"$PY" - "$JNI" <<'PYEOF25'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "E.pool_type, E.n_batch, E.n_ubatch,"
assert s.count(old) == 1, "stub25: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "E.pool_type, (int) cp.n_batch, (int) cp.n_ubatch,", 1))
PYEOF25
must_red "桩㉕（加载期打 cp 的值而非库的实数）" "打的不是 \`E.n_batch\`"
reset_src

# ── 桩㉖：`doLoadEmbed` 不把这一格传给 loadEmbedModel ──────────────────────
# 这是本 issue 的**原始形态**：设置页那个框能填、能存，但对加载没用
#（与"线程数输入框对建库完全无效"一模一样）。判据必须点出"没传 nCtx"。
"$PY" - "$ACT" <<'PYEOF26'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "LlmEngine.loadEmbedModel(f.absolutePath, nCtx = embedCtxForLoad,"
assert s.count(old) == 1, "stub26: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "LlmEngine.loadEmbedModel(f.absolutePath,", 1))
PYEOF26
must_red "桩㉖（设置页那一格对加载无效）" "没传 nCtx"
reset_src

# ── 桩㉗：`EmbedCtxPref.normalize` 自己再钳一次（上限与 native 脱钩）──────
"$PY" - "$PREF" <<'PYEOF27'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "fun normalize(value: Int): Int = LlmEngine.kEmbedCtxLimit(value)"
assert s.count(old) == 1, "stub27: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "fun normalize(value: Int): Int = value.coerceIn(1, 4096)", 1))
PYEOF27
must_red "桩㉗（normalize 自己再钳一次）" "没有转调 LlmEngine.kEmbedCtxLimit"
reset_src


# ── 桩㉘：内存归因那一行去掉（用户问的"内存为啥高"就没答案了）─────────────
# 这一桩打的是"读数缺失"—— 而它的失效形态是**静默**的：内存确实变了，
# 但没有任何一行把它与"这一格"联系起来，用户只能看到应用列表里那个数变高。
# 与本仓库反复记录的那一族同形（`[mmap释放]` 之前的 load_mode 假绿）。
"$PY" - "$JNI" <<'PYEOF28'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'jlog("[embed内存] n_ctx=%d n_batch=%d n_ubatch=%d'
assert s.count(old) == 1, "stub28: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, 'jlog("[x] n_ctx=%d n_batch=%d n_ubatch=%d', 1))
PYEOF28
must_red "桩㉘（内存归因那一行被去掉）" "没有 \`[embed内存]\` 那一行"
reset_src

# ── 桩㉙：`[embed内存]` 又把 RSS 差标成"本档 +N MB"（0.9.212 那个量错的口径）──
# 那一版的基线取在**模型加载之后**，892 MB 里已含模型 mmap 的页 —— 于是
# "建 ctx 时恰好被 touch 的几页"被当成了"这一档 ctx 自己吃的"（真机 +3 MB），
# 而一份 4096 的 ctx 光 output buffer 就不止 3 MB。**一个量不准却像量准了的数**，
# 比没有读数更坏：用户会按它去调这一格。这一桩把它钉回来，守卫必须红。
"$PY" - "$JNI" <<'PYEOF29'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '"内存高就调小「嵌入每批上下文」'
assert s.count(old) == 1, "stub29: anchor miss"
open(p, 'w', encoding='utf-8').write(
    s.replace(old, '（本档 +%ld MB）内存高就调小「嵌入每批上下文」', 1))
PYEOF29
must_red "桩㉙（又把 RSS 差标成『本档 +N MB』）" "标成『本档 +N MB』"
reset_src

# ── 桩㉚：RSS 绝对值没标"含模型 mmap" ──────────────────────────────────────
# 不标注时，读的人会把这个绝对值当成"这一档 ctx 花的" —— 而那正是 0.9.212
# 量错的那个数换了个说法。判据必须点出"没标注"。
"$PY" - "$JNI" <<'PYEOF30'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '（**含模型 mmap**，别当增量读）'
assert s.count(old) == 1, "stub30: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, '', 1))
PYEOF30
must_red "桩㉚（RSS 绝对值没标『含模型 mmap』）" "没标注『含模型 mmap』"
reset_src

# ── 桩㉛：每条的预算不是由门除出来的，而是"先取预算再钳总量" ──────────────
# 这一版**看着等价**（`ctx ≤ gate` 仍然成立），但它把 "8 条满长装不装得下"
# 变成了**事后结论** —— 成立只因为两个常量恰好相等，改任一个就 abort。
"$PY" - "$JNI" <<'PYEOF31'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    int perSeqMax = gatesAbs / kEmbedBatchRows;"
assert s.count(old) == 1, "stub31: anchor miss"
new = "    int perSeqMax = kEmbedMaxCtx / kEmbedBatchRows;   // 自己再推一个"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF31
must_red "桩㉛（每条预算不是由门除出来）" "没有 \`perSeqMax = gatesAbs / kEmbedBatchRows\`"
reset_src

# ── 桩㉜：调用方给的 perSeq **不钳**（这一格填大就顶出容量）────────────────
# 用户把「嵌入每批上下文」填 4096 时，它会被乘 8 再钳总量 —— **看着**没事，
# 但"每条的预算"与"两道门"的关系就断了，且这一格从此**静默失效**
#（填 4096 实际拿到 512，而界面上没有任何一行说明）。
"$PY" - "$JNI" <<'PYEOF32'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    if (perSeq > perSeqMax) perSeq = perSeqMax;"
assert s.count(old) == 1, "stub32: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, '', 1))
PYEOF32
must_red "桩㉜（perSeq 不钳进 perSeqMax）" "没有钳进"
reset_src

# ── 桩㉝：`embed model ready` 不打 `full_rows_fit` ──────────────────────────
# 这个数是本 issue 的核心读数（"这一档一次能装几条满长"）。只在余量预警那一行
# 出现不够：余量充足时（正常情况）读的人**看不到这个数**，而"它能装几条"
# 正是他判断"要不要调这一格"的依据。
"$PY" - "$JNI" <<'PYEOF33'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '"n_embd=%d, n_embd_out=%d, pooling=%d, n_batch=%d, n_ubatch=%d, full_rows_fit=%d",'
assert s.count(old) == 1, "stub33: anchor miss"
new = '"n_embd=%d, n_embd_out=%d, pooling=%d, n_batch=%d, n_ubatch=%d",   // 不打 full_rows_fit'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF33
must_red "桩㉝（ready 行不打 full_rows_fit）" "没有 \`full_rows_fit=%d\`"
reset_src


echo
echo "=== 嵌入多 seq 池化取值守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
