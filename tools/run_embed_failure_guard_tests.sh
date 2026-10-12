#!/bin/sh
# 自测 `tools/run_embed_failure_guard.sh`：对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据几乎全是"某段结构关系成立"（对照点在读取点之前、logits 取自
# want_logits、失败清单带上了下标…）。这类断言最常见的两种自欺：
#
#   A. **恒真**：pattern 写成到处都有的文本（或只判"出现过某关键字"，
#      而**写错的那一支里那个关键字还在**）。
#   B. **切歪**：抽函数体时切到了别的函数上，于是测的是一段无辜的代码。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真源码成故障版本，守卫必须变红并指出是哪一条；
# 再改回来，必须恢复全绿。
#
# 桩全部"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 改动窗口由 with_worktree_lock 串行化（见该文件顶部）。
#
# 运行：sh tools/run_embed_failure_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_embed_failure_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HDR=app/src/main/cpp/embed_text.h
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$KT" "$TMP/kt.bak"; cp "$HDR" "$TMP/hdr.bak"
cp "$STORE" "$TMP/store.bak"; cp "$EA" "$TMP/ea.bak"
restore() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"; cp "$TMP/hdr.bak" "$HDR"
    cp "$TMP/store.bak" "$STORE"; cp "$TMP/ea.bak" "$EA"
    rm -rf "$TMP"
}
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
reset_src() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"; cp "$TMP/hdr.bak" "$HDR"
    cp "$TMP/store.bak" "$STORE"; cp "$TMP/ea.bak" "$EA"
}
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
must_green() {
    name=$1
    if run_guard; then ck "$name：守卫全绿" 1
    else ck "$name：守卫全绿（实际红了）" 0; sed -n '1,60p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 1. 桩①：去掉输出维度对照（回到"只用 n_embd"）─────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 这条对照现在有**两处**（单条 `nativeEmbed` 与合批 `nativeEmbedBatch`），
# 桩只该改**单条那一处** —— 两处都改掉的话，"合批那份还挡着"会让判据照样绿，
# 桩就成了假红（本仓库反复记录过"判据被另一处满足"的假绿）。
src = open(p, encoding='utf-8').read()
i_fn = src.index('Java_com_xiaowan_localinference_LlmEngine_nativeEmbed(')
j_fn = src.index('Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch(')
seg = src[i_fn:j_fn]
old = 'if (!embedtext::output_dim_ok(nEmbd, nEmbdOut)) {'
assert seg.count(old) == 1, "桩①：单条 nativeEmbed 里没找到维度对照分支"
s = src[:i_fn] + seg.replace(old, 'if (false) {', 1) + src[j_fn:]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩①（维度对照被短路）" "output_dim_ok"
reset_src

# ── 2. 桩②：对照排到读向量**之后**（已经读过脏内存了）───────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
start = s.index('    // ── 维度这一对照必须在**读向量之前**成立')
e = s.index('    std::vector<float> vec((size_t) nEmbd', start)
seg = s[start:e]
s = s[:start] + s[e:]
# 把对照段挪到**最后一次读 embeddings** 之后（脏内存已经读过了）
anchor = '        std::memcpy(vec.data(), raw, sizeof(float) * (size_t) nEmbd);'
i = s.index(anchor) + len(anchor)
s = s[:i] + '\n' + seg + s[i:]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩②（维度对照排在读取之后）" "维度对照排在 llama_get_embeddings_ith **之后**"
reset_src

# ── 3. 桩③：logits 硬编码"最后一个"（CLS/MEAN 静默退化成 last）───────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'b.logits[i]    = plan.want_logits[i] ? 1 : 0;'
assert s.count(old) == 1, "桩③：没找到 logits 赋值"
s = s.replace(old, 'b.logits[i]    = (i == nTok - 1) ? 1 : 0;', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩③（logits 硬编码最后一个）" "want_logits"
reset_src

# ── 4. 桩④：读行用 batch 下标（n_tokens-1）而不是计划给的下标 ─────────────
# ⚠ **本桩的含义在 0.9.160 变过**（源码变了，桩要跟着走，不然它就成了
# "测一段没人信的代码"）：早先的坑是"用 batch 下标当 output 下标"；
# 现在读的位置由 `plan.row` 分岔，坑变成"整个分岔被拆掉、直接读一个硬编码
# 下标"。两桩都要有：本条打"硬编码"，桩⑳/㉑ 打"语义与下标不同源"。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'float * raw = llama_get_embeddings_ith(E.ctx, slot);'
assert s.count(old) == 1, "桩④：没找到读 embeddings 的调用"
s = s.replace(old, 'float * raw = llama_get_embeddings_ith(E.ctx, nTok - 1);', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩④（读行用硬编码 n_tokens-1）" "硬编码下标"
reset_src

# ── 5. 桩⑤：不检查 plan.valid（RANK 被当成句向量用）─────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 与桩① 同理：`plan.valid` 现在有两处（单条与合批），只改单条那一处。
src = open(p, encoding='utf-8').read()
i_fn = src.index('Java_com_xiaowan_localinference_LlmEngine_nativeEmbed(')
j_fn = src.index('Java_com_xiaowan_localinference_LlmEngine_nativeEmbedBatch(')
seg = src[i_fn:j_fn]
old = 'if (!plan.valid) {'
assert seg.count(old) == 1, "桩⑤：单条 nativeEmbed 里没找到 plan.valid 检查"
s = src[:i_fn] + seg.replace(old, 'if (false) {', 1) + src[j_fn:]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑤（不检查 plan.valid）" "plan.valid"
reset_src

# ── 6. 桩⑥：归一化失败不报实际范数（回到无差别那句）─────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '归一化失败（范数=%.3g，n_tokens=%d，pooling=%d，取自=%s，n_embd=%d，n_embd_out=%d），丢弃这一条'
assert s.count(old) == 1, "桩⑥：没找到范数日志"
s = s.replace(old, '归一化失败（零范数/NaN），丢弃这一条', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
# ⚠ 期望命中的是**守卫那一行**的名字（`归一化失败报出实际范数`）：合批之后
# 那个 grep 换成了锚 `[embed]` 前缀的版本，裸的"没有实际范数"只在**修改前**
# 的守卫里出现过 —— 桩要比对的是"现在的守卫指出的是哪一条"。
# ⚠ 关键词要**同时**含 `FAIL` 前缀：这条判据的名字也出现在一处 PASS 行里，
# 只给名字时 `head -1` 会先抓到 PASS 那一行（桩于是"指错了"）。
must_red "桩⑥（归一化失败不报范数）" "FAIL  归一化失败报出实际范数"
reset_src

# ── 7. 桩⑦：失败条目整条丢弃（正文不留下）───────────────────────────────
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 下标变量在合批之后是 `k`（批内偏移），不再是 `i`（批起点）——
# 锚"无向量形态的 Entry 调用"而不锚变量名（同 check_failure_report.py 的 ⑤）。
import re as _re
m = _re.search(r'rows\.add\(RagIndexStore\.Entry\(texts\[[^\]]+\], src\)\)', s)
assert m, "桩⑦：没找到失败分支的正文 add"
s = s[:m.start()] + '// stub7: 失败条目丢弃' + s[m.end():]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑦（失败条目整条丢弃）" "失败分支里 add 的不是"
reset_src

# ── 8. 桩⑧：有失败时仍走 save（假装成功）───────────────────────────────
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val okSave = if (stale) {'
assert s.count(old) == 1, "桩⑧：没找到 save/savePartial 分界"
s = s.replace(old, 'val okSave = if (false) {', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑧（有失败时仍走 save）" "没有「按 stale 分支」决定 save/savePartial"
reset_src

# ── 9. 桩⑨：savePartial 把成功向量往前挤（整体错位）─────────────────────
"$PY" - "$STORE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (r.vector.size == dim) keep.add(r.vector) else break'
assert s.count(old) == 1, "桩⑨：没找到 savePartial 的前缀保留逻辑"
s = s.replace(old, 'if (r.vector.size == dim) keep.add(r.vector)', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑨（成功向量往前挤造成错位）" "没有「遇到第一个缺向量的行就停」"
reset_src

# ── 10. 桩⑩：meta.json 不写失败下标（刷新后看不见哪几条坏）───────────────
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '.put("failedIndices", org.json.JSONArray(failedIdx))'
assert s.count(old) == 1, "桩⑩：没找到 failedIndices 写入"
s = s.replace(old, '.put("failedIndicesStub", 0)', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑩（meta 不记失败下标）" "没写 失败下标"
reset_src

# ── 11. 桩⑪：追加单条失败不带内容（用户不知道坏在哪一条）────────────────
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'return "这一条追加失败：「$head」—— $why"'
assert s.count(old) == 1, "桩⑪：没找到追加失败文案"
s = s.replace(old, 'return "这条编码失败（嵌入模型返回空，检查内存）"', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑪（追加失败不带这一条内容）" "没有带出这一条的内容"
reset_src

# ── 12. 桩⑫：UI 把「部分失败」当「真失败」（用户反复重试）────────────────
"$PY" - "$EA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (err == null || partial) {'
assert s.count(old) == 1, "桩⑫：没找到 partial 分支"
s = s.replace(old, 'if (err == null) {', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑫（UI 把部分失败当真失败）" "部分失败时状态行/归属仍按已保存更新"
reset_src

# ── 13. 桩⑬：pooling_type 读了但没存进 Session ───────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'E.pool_type = (int) llama_pooling_type(E.ctx);'
assert s.count(old) == 1, "桩⑬：没找到 pool_type 赋值"
s = s.replace(old, '/* stub13: pooling 没存 */', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑬（pooling_type 没存进 Session）" "pool_type"
reset_src

# ── 14. 桩⑭：行注释里塞 `/*`，真代码不变 —— 守卫必须**仍全绿** ──────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
anchor = 'static Session E;   // 嵌入（embed），embedding=true'
assert s.count(anchor) == 1, "桩⑭：没找到 Session E 声明"
s = s.replace(anchor, '// 一段注释里的斜杠星号：/* 这不是块注释起点 */\n' + anchor, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_green "桩⑭（行注释里的 /* 不得让守卫假红）"
reset_src

# ── 15. 桩⑮：CLS 的 logits 只置第 0 个（0.9.156 的真 bug）────────────────
# 这一桩打的正是 0.9.156 里的写法：CLS "取第 0 个 token" 被理解成"只把第 0 个
# token 标成 output"。语义上"取对位置了"，但库对 CLS 要求整段都在 output 里 ——
# `llama_decode` 直接失败，BGE 系（pooling=CLS）**条条失败**，而失败被记成
# decode_failed，读起来像内存/模型问题。
# 关键：桩⑮ 打上去时，当时那条守卫（只钉"读对位置"）**全绿** ——
# 这就是"判据只覆盖了半张网"的实证，也是本条自测存在的意义。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            for (int i = 0; i < nTokens; i++) p.want_logits[(size_t) i] = 1;
            p.output_count = nTokens;          // 整段都进 output（满足库的 CLS 门禁）
            p.row = SeqRow::LIB_POOLED;"""
assert s.count(old) == 1, "桩⑮：没找到 CLS 的 want_logits 填充"
s = s.replace(old, """            p.want_logits[0] = 1;
            p.output_count = nTokens;
            p.row = SeqRow::LIB_POOLED;""", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑮（CLS 只置第 0 个 logits -> 库拒 decode）" "整段"

reset_src

# ── 16. 桩⑯：decode 之前不做契约自检（违反库前置条件时只能看到 decode 失败）──
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (!embedtext::logits_ok_for_library(poolType, plan.want_logits, nTok)) {'
assert s.count(old) == 1, "桩⑯：没找到契约自检"
s = s.replace(old, 'if (false) {', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑯（decode 前不自检 logits 契约）" "logits 是否满足库的池化契约"
reset_src

# ── 17. 桩⑰：MEAN 又退回"自己逐行累加平均"（近似库的池化）───────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'float * pooled = llama_get_embeddings_seq(E.ctx, 0);'
assert s.count(old) == 1, "桩⑰：没找到 embeddings_seq 调用"
s = s.replace(old, 'float * pooled = llama_get_embeddings_ith(E.ctx, 0);', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑰（MEAN 不读库给的那一个）" "get_embeddings_seq"
reset_src

# ── 18. 桩⑱：verdictOf 不分岔（把"全败"与"部分失败"说成同一种病）────────
# 两者的**处置完全相反**：全败要看配置/契约（换模型没用），部分失败要看内容。
# 不做这个分岔时，用户会拿"内容问题"的处置去治"配置问题" —— 也就是他这次
# 走的那一整晚弯路。
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (okCount == 0) {'
assert s.count(old) == 1, "桩⑱：没找到全败分支"
s = s.replace(old, 'if (false) {   // stub18: 不分岔', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑱（verdictOf 不分岔全败/部分失败）" "全败"
reset_src

# ── 19. 桩⑲：verdictOf 写了但没接进汇报（用户仍只拿到数据）──────────────
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val verdict = verdictOf(fails.map { it.second }, okCount, failedIdx.size)'
assert s.count(old) == 1, "桩⑲：没找到 verdict 调用"
s = s.replace(old, 'val verdict = "见上方明细"   // stub19: 不判决', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑲（verdictOf 没接进汇报）" "buildIndex 的汇报里没有调 verdictOf"
reset_src

# ── 20. 桩⑳：CLS 又落回 get_embeddings_ith（**9b7335a 之后仍复现的真因**）──
# 这一桩打的是"库自己池化的那几档该读 seq、却读了 ith"。
# `llama_get_embeddings_ith` 的定义建在 `get_embeddings(ctx)` 有效之上，
# 而后者**只在 pooling==NONE 时**成立；CLS 下它指向逐 token 的输出区，
# 池化结果在库的独立张量里，读出来是**全零**（不是 null）——
# 全零绕过 no_output 判据、直落 `范数=0`，正是用户那行日志的形态：
#   [embed] 归一化失败（范数=0，n_tokens=13，pooling=2，n_embd=1024, n_embd_out=1024）
# 关键：桩⑳ 打上去时，旧守卫的 ③④（读对位置 / ith 实参是 output_slot）**全绿** ——
# 一件事都没错，错的是"CLS 根本不该走 ith 这条路"。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return library_gives_seq(poolTypeRaw);         // MEAN / CLS / LAST：库自己池化"
assert s.count(old) == 1, "桩⑳：没找到 read_from_seq 的分路集合"
s = s.replace(old, "    return poolTypeRaw == 1;   // stub20: CLS 退回 ith", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑳（CLS 退回 get_embeddings_ith -> 读全零、范数=0）" "没有复用 library_gives_seq"
reset_src

# ── 21. 桩㉑：read_from_seq 恒 false（分路判据退化成摆设）────────────────
# ④b 判"函数体里出现过 read_from_seq"在恒 false 时**照样绿** ——
# 所以这一桩专门钉"判据自己的行为"，与 ④c 那条一起才拦得住。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return library_gives_seq(poolTypeRaw);         // MEAN / CLS / LAST：库自己池化"
assert s.count(old) == 1, "桩㉑：没找到 read_from_seq 的分路集合"
s = s.replace(old, "    return false;   // stub21: 恒不走 seq", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉑（read_from_seq 恒 false -> CLS/MEAN 全走 ith）" "没有复用 library_gives_seq"
reset_src

# ── 22. 桩㉒：分路被绕过（`if (false && read_from_seq(...))`）──────────────
# 这一桩防的是"判据只判'调用过'、不判'调用结果管用'"。
# 分路表达式在、constant 也在、ith 实参也没错 —— 旧口径下**全绿**，
# 而线上 CLS 就是又走回了 ith。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (embedtext::read_from_seq(poolType)) {'
assert s.count(old) == 1, "桩㉒：没找到分路条件"
s = s.replace(old, 'if (false && embedtext::read_from_seq(poolType)) {', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉒（分路被短路绕过 -> CLS 又走 ith）" "条件"
reset_src

# ── 23. 桩㉓：分路连"调用"都不做了（回到 MEAN-only 的硬编码分支）──────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (embedtext::read_from_seq(poolType)) {'
assert s.count(old) == 1, "桩㉓：没找到分路条件"
s = s.replace(old, 'if (plan.pool == embedtext::Pool::MEAN) {', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉓（分路退回硬编码 MEAN-only）" "read_from_seq"
reset_src

# ── 24. 桩㉔：LAST 档又退回「读 output 数组第 0 项」（**本轮的真 bug**）──────
# 这一桩打的正是 311011c 里的写法：`plan.output_slot` 对 LAST 恒为 0。
# 它在「我们只给最后一个位置置了 logits」的前提下**恰好**等于最后一行，
# 所以看起来对；一旦库给的 output 不止一项，`output_ids[0]` 就变成
# **第 0 个 token** —— 生成式 decoder 里是 BOS，没有前文，隐藏态量级 ~1e-30，
# 归一化必然拒绝。关键：桩㉘ 打上去时，**旧版守卫全绿**（它只钉
# “实参叫 plan.output_slot”，而这一桩改的正是 plan.output_slot 的语义/排位，
# 名字没变）—— 这就是“判据只钉了名字、没钉语义”的实证。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            p.output_slot = p.row == SeqRow::LIB_POOLED ? -1 : p.batch_last_slot;"
assert s.count(old) == 1, "stub20: no output_slot ternary"
# 桩㉔ 打的是"LAST 档又退回写死一个下标"：把 -1 / batch_last_slot 的**来源**断掉，
# 换成一个字面量 0。那会让"库给 pooled buffer 的档"也带上一个假的下标。
s = s.replace(old, "            p.output_slot = 0;   // stub20: hard-coded", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉔（LAST 档又写死下标，池化档也带上了假下标）" "LAST 档的 \`output_slot\` 不是按同一次分岔给的"
reset_src

# ── 25. 桩㉕：native 不按 plan.row 分岔，直接读 plan.output_slot ──────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        const int slot = plan.row == embedtext::SeqRow::LAST_TOKEN
                         ? plan.batch_last_slot : plan.output_slot;"""
assert s.count(old) == 1, "stub21: no slot ternary"
s = s.replace(old, "        const int slot = plan.output_slot;   // stub21", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉕（native 不按语义分岔读哪一行）" "找不出 LAST_TOKEN 三元表达式"
reset_src

# ── 26. 桩㉖：**又不做归一化分岔**（库给的向量被我们按自家口径判失败）──
# 库里已经在归一化了；我们再归一化一次不会把方向改坏，于是“顺手归一化”
# 看起来无害 —— 它有害的地方是**判据**：`l2_normalize` 的返回值被当成
# “这一档能不能用”的结论，于是一个合法的库向量会被报成归一化失败。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'const bool we_normalize = embedtext::normalize_pooling(poolType);'
assert s.count(old) == 1, "stub22: no normalize split"
s = s.replace(old, 'const bool we_normalize = true;   // stub22', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉖（不做归一化分岔）" "归一化分岔用的是"
reset_src

# ── 27. 桩㉗：normalize_pooling 自己写一套（不再与 library_gives_seq 同源）──
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    (void) poolTypeRaw;
    return true;"""
assert s.count(old) == 1, "stub23: no normalize_pooling impl"
# 打回 0.9.160 那个**被证伪的**写法：`return !library_gives_seq(t)`。
# 它会让 MEAN/CLS/LAST 三档**永远不归一化**，而检索侧 cosine 是纯点积 ——
# 前提就是两边都归一化过。症状：排序按向量长度（静默）。
s = s.replace(old, "    return !library_gives_seq(poolTypeRaw);   // stub23", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉗（归一化又变成 library_gives_seq 的背面 —— 已被证伪）" "已被证伪"
reset_src

# ── 28. 桩㉘：decode 前不做**计划自洽**自检 ───────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (!embedtext::plan_is_coherent(plan, nTok)) {'
assert s.count(old) == 1, "stub24: no coherence check"
s = s.replace(old, 'if (false) {', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉘（decode 前不做计划自洽自检）" "plan_is_coherent"
reset_src

# ── 29. 桩㉙：**自洽判据把「output 排位」当「batch 位置」比**（本 PR 的真 bug）──
# 这一桩打的正是 6efd38e 里的写法：`if (p.batch_last_slot != nTokens - 1)`。
# `batch_last_slot` 是 output 数组里的**排位**（LAST 档只置最后一项 → 排位 0），
# 而 `nTokens-1` 是 **batch 位置**；两者只在"output 恰好整段"时相等，
# LAST 档下**恒不相等** → **每一条嵌入都被判「计划不自洽」**。
#
# 真机日志逐字对应（0.9.160 之后）：
#   `[embed] 池化计划不自洽：pooling=3 row=0 output_slot=0`
# —— row=0(LAST_TOKEN) / output_slot=0 都是**正确**的，被判不自在的是判据自己。
#
# ⚠ 关键：桩㉙ 打上去时，**旧版守卫（含 ⑦c）全绿** —— ⑦c 钉的是
# `plan_pooling` 填了什么（填的确实对），而没钉 `plan_is_coherent` 怎么判。
# 这是"判据覆盖了半边"的实证 —— 与本仓库反复记录的那类自欺同源。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (output_slot_to_batch(p, p.output_slot) != nTokens - 1) return false;"""
assert s.count(old) == 1, "stub29: no coherence convert"
s = s.replace(old, "            if (p.batch_last_slot != nTokens - 1) return false;   // stub29", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉙（自洽判据拿排位比位置 -> 每条都判不自洽）" "仍把 batch_last_slot 当**batch 位置**"
reset_src

# ── 29b. 桩㉛：**`library_gives_seq` 丢掉 `LAST(3)`（本 PR 的真 bug）**──────
# 这一桩打的正是 0.9.161 里的写法：`return t == 2 || t == 1;`（只有 MEAN / CLS）。
# 判定依据不是"哪几档叫池化"，而是**库把结果写进哪个 buffer**：
# vendor 里那个 switch 只把 `NONE(0)` 写进 `embd.data`（`ith` 读它），
# `MEAN / CLS / LAST` 三档**全部**写进 `embd_seq`（`seq` 读它）。
#
# 漏掉 LAST 的报应（真机）：`pooling=3` 的 Qwen3-Embedding 走 `ith` 去读一个
# **分配了但从未写入**的 buffer → 拿到**全零**（不是 null，绕过 no_output 判据）
# → `范数=0` 条条失败。而 `pooling=2` 的 BGE 走 seq 照常能用 ——
# 于是看起来像"这个模型不行"，用户连换三个模型都没救。
#
# ⚠ 关键：桩㉛ 打上去时，**旧版守卫（含 ④c）全红但方向相反** —— ④c 当时把
# `{1,2}` 硬编码成"正确集合"，于是真正的 bug（少一档）它判绿、
# 而正确代码（三档）它判红。这就是"判据把某个具体值当成语义真值"的实证。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return poolTypeRaw == 1 || poolTypeRaw == 2 || poolTypeRaw == 3;"
assert s.count(old) == 1, "stub31: no library_gives_seq set"
s = s.replace(old, "    return poolTypeRaw == 2 || poolTypeRaw == 1;   // stub31: LAST 掉了", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉛（library_gives_seq 丢掉 LAST(3) -> pooling=3 读全零）" "应为 {1, 2, 3}"
reset_src

# ── 29c. 桩㉜：**`plan_pooling` 的 LAST 支不再按原始 pool 值分岔**──────────
# `Pool::LAST` 同时覆盖 `NONE(0)`（读 ith）与 `LAST(3)`（读 seq）。
# 把这次分岔去掉（恒 LAST_TOKEN），`pooling=3` 又回去读从未写入的 buffer。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            p.row = library_gives_seq(poolTypeRaw) ? SeqRow::LIB_POOLED : SeqRow::LAST_TOKEN;"
assert s.count(old) == 1, "stub32: no LAST split"
s = s.replace(old, "            p.row = SeqRow::LAST_TOKEN;   // stub32: 不分岔", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉜（LAST 支不再按原始 pool 值分岔）" "plan_pooling 的 LAST 支没有按"
reset_src

# ── 30. 桩㉚：**换算函数消失**（"排位→位置"没有唯一真相来源）──────────
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "inline int output_slot_to_batch(const PoolPlan & p, int slot) {"
assert s.count(old) == 1, "stub30: no output_slot_to_batch"
s = s.replace(old, "inline int output_slot_to_batch_removed(const PoolPlan & p, int slot) {", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉚（换算函数被改名/删掉）" "抽不出 output_slot_to_batch 的实现"
reset_src

# ── 31. 桩㉝：**warm-up 退回「BOS 单 token + 只要一行」**（本 PR 的真 bug）──
# 这一桩打的正是修复前的写法。它的推理是"库对 NONE/LAST 只要求至少一个
# output"，而这条推理每一步都错：
#   · CLS（BGE 系）上，库的门禁在 `llama_batch_allocr::init` 里认
#     `llama_batch.logits[]` 覆盖了所有 input token 没有 —— 只要一行直接
#     `abort()`（release 里就是进程级崩溃），连"失败但不影响加载"都打不出来；
#   · NONE/LAST 上，`llama_context::decode` 那条"库替我们补"补的是**真实
#     input**（只有 BOS 一个），"补"完还是只有第 0 行 —— warm-up 想干的事
#     **根本没干成**，只剩一条假绿日志。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                        wb.logits[i]    = wplan.want_logits[(size_t) i] ? 1 : 0;'
assert s.count(old) == 1, "stub33: no warm-up logits assignment"
s = s.replace(old, '                        wb.logits[i]    = (i == 0) ? 1 : 0;   // stub33: 只要第 0 行', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉝（warm-up 的 logits 硬编码「只要第 0 行」）" "warm-up 的 logits 取值"
reset_src

# ── 31b. 桩㉞：**warm-up 不再用计划函数**（自己另写一套置位）─────────────
# 与桩㉝ 成对：㉝ 打"置位内容错了"，㉞ 打"连计划都不用了"。
# 后者更容易漏 —— 它看起来只是"少了一次调用"，而真实的差别是
# warm-up 与真实路径的库契约从此分家（改一处没人会想到另一处）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            const embedtext::PoolPlan wplan = embedtext::plan_pooling(E.pool_type, wtok);'
assert s.count(old) == 1, "stub34: no warm-up plan call"
s = s.replace(old, '            const embedtext::PoolPlan wplan;   // stub34: 不用计划', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉞（warm-up 不用 plan_pooling，自己另写置位）" "warm-up 没有用 embedtext::plan_pooling"
reset_src

# ── 31c. 桩㉟：**warm-up 退回「字面量 1 个 token」的退化形态**─────────────
# `llama_batch_init(1, ...)` 是"BOS 单 token"那一版的指纹。
# ⚠ 这一条与桩㉝ 不同：㉝ 打"置位内容"，这条打"batch 长度"。
# 只改置位、不改 batch 长度的话，CLS 又能过门禁（整段 = 那 1 个 token），
# 但 warm-up 仍然是**假绿**（"整段"只有第 0 行）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'llama_batch wb = llama_batch_init(wtok, 0, 1);'
assert s.count(old) == 1, "stub35: no warm-up batch_init"
s = s.replace(old, 'llama_batch wb = llama_batch_init(1, 0, 1);   // stub35: 退化', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉟（warm-up 的 batch 退回字面量 1 = BOS 单 token）" "字面量 1"
reset_src

# ── 31d. 桩㊱：**「计划不可用」的跳过分支不留日志**（静默跳过 = 假绿）────
# warm-up 允许跳过（它是"去掉不确定性"，不是功能前提），但**不许静默**：
# 静默跳过之后日志里什么都没有，用户会以为 buffer 已经干净了。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                jlog("[embed] warm-up 跳过：计划不可用（pooling=%d, n_tokens=%d, valid=%d）"'
assert s.count(old) == 1, "stub36: no warm-up skip log"
i = s.index(old)
# 把这条 jlog 整句删掉（连它的续行），保留 else 分支结构
j = s.index(');', i) + 3
s = s[:i] + '                ;' + s[j:]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊱（warm-up 跳过不留日志）" "没有日志"
reset_src

# ── 31e. 桩㊲：**`warmup_plan_usable` 的下限被放到 1**（退化形态被放行）──
# 判据本身改坏，而 native 的接线一个字符都没变 —— 桩㉝~㊱ 全绿。
# 这就是"两层判据缺一层都拦不住"的实证：接线上有闸，闸本身的门槛是坏的。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'static const int kWarmupMinTokens = 2;'
assert s.count(old) == 1, "stub37: no kWarmupMinTokens"
s = s.replace(old, 'static const int kWarmupMinTokens = 1;   // stub37: 门槛被放低', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊲（kWarmupMinTokens 被放到 1 -> 退化形态放行）" "kWarmupMinTokens = 1"
reset_src

# ── 31f. 桩㊳：**`warmup_plan_usable` 不再复用 logits_ok_for_library**─────
# "给库的门禁"被抄成两处 —— 改一处就会让 warm-up 与真实路径再次分家
# （而分家的报应是 CLS 上的进程级 abort，正是本 PR 的根因）。
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    if (!logits_ok_for_library(poolTypeRaw, plan.want_logits, nTokens)) return false;'
assert s.count(old) == 1, "stub38: no library-gate reuse"
s = s.replace(old, '    (void) poolTypeRaw;   // stub38: 门禁不判了', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊳（warmup_plan_usable 不再复用库门禁判据）" "没有复用 logits_ok_for_library"
reset_src

# ── 31g. 桩㊴：**「写满了没有」不再追问**（NONE/LAST 假装写干净）─────────
# 这一条是"假绿"的另一种形态：warm-up 确实跑了、也确实没写满，
# 但代码/日志不再提这件事 —— 下一个人据此以为未初始化内存已消除。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                const bool all_rows = embedtext::warmup_wrote_all_rows(wplan, wtok);'
assert s.count(old) == 1, "stub39: no all_rows probe"
s = s.replace(old, '                const bool all_rows = true;   // stub39: 假装写满了', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊴（不再追问「写满了没有」-> NONE/LAST 假装写干净）" "warmup_wrote_all_rows"
reset_src

# ── 31h. 桩㊵：**返回数组的分配长度退回 `nEmbdOut`**（本轮修掉的那一条）──
# 这正是 0.9.186 修的那个形态：`NewFloatArray(nEmbdOut)` 配
# `SetFloatArrayRegion(out, 0, nEmbd, ...)`。它当时**不爆**，因为上游
# `output_dim_ok` 要求严格相等 —— 也就是说唯一在挡它的东西是**另一处**判据，
# 而那处只判"值相不相等"，从来没保证过"分配数 == 填充数"。
# 打这个桩 = 把"闸"和"闸后面的地板"分开：闸还在（守卫前几条全绿），
# 地板被抽掉，这一条必须自己能红。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'jfloatArray out = env->NewFloatArray(nEmbd);'
assert s.count(old) == 1, "stub40: no NewFloatArray(nEmbd)"
s = s.replace(old, 'jfloatArray out = env->NewFloatArray(nEmbdOut);   // stub40: 退回另一个数', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊵（分配长度退回 nEmbdOut，填充仍是 nEmbd）" "不是同一个数"
reset_src

# ── 31i. 桩㊶：**填充起点不为 0**（开头留下未填的位置）────────────────────
# 与桩㊵ 同一族，但错在**起点**而不是长度：`SetFloatArrayRegion(out, i, nEmbd, ...)`
# 会让数组前 i 个位置保持创建时的 0 —— 看起来"长度契约对了"，
# 实际向量头部被削掉、尾部越界写入（JNI 会直接抛异常）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'env->SetFloatArrayRegion(out, 0, nEmbd, vec.data());'
assert s.count(old) == 1, "stub41: no SetFloatArrayRegion"
s = s.replace(old, 'env->SetFloatArrayRegion(out, 1, nEmbd, vec.data());   // stub41: 起点不是 0', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊶（填充起点不是 0）" "填充起点不是 0"
reset_src

must_green "收尾：还原源码后"

echo ""
if [ "$bad" = "0" ]; then echo "=== 嵌入失败可归因守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 嵌入失败可归因守卫自测：PASS $ok / FAIL $bad ==="; exit 1
