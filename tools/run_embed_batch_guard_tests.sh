#!/bin/sh
# 自测 `tools/run_embed_batch_guard.sh`：拿几份**打桩源码树**跑它，
# 断言"该红的红、且失败时点得出是哪一条"（ISSUE #269 的另一半）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是**结构关系**（填 batch 时数组取自哪个计划 / KV 清没清全 /
# 两处批大小相不相等），而在**正确**的源码上它们全绿 —— 一个永远绿的判据
# 与没有判据在效果上一样。
#
# 桩全部取自"真正会写错的写法"（合批这一族真踩过的形态）：
#   · 清 KV 只清 seq 0（不遍历）—— 后几条复用上一批的 KV，向量混味，接口 200；
#   · `pos` 用整批全局下标（不是 seq 内位置）—— RoPE 一转，向量变成
#     "另一条文本在另一段的语义"，而长度 / 维度 / 归一化**全对**；
#   · 两处批大小不等 —— 每批都在 native 被拒、回落逐条，合批白做而界面看不出来；
#   · 合批的失败记账抄成两处 —— 批大小会改变"哪几条失败"（数字随批大小漂）；
#   · `batchable_pooling` 自己硬编码档位（不跟 `library_gives_seq` 同源）——
#     迟早只改一处，被合批的那一档读错 buffer。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_embed_batch_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_embed_batch_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/embed_text.h
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
# ⚠ 0.9.229 起"默认批大小 / 合批上限"这两个数的**出处**搬到了 EmbedLimits.kt
#（`LlmEngine` 里是同名常量转发）。桩③ 要改的就是那边，所以它必须一起备份/还原 ——
# 漏了它，桩会把**真源码**改坏且不还原。
LIM=app/src/main/java/com/xiaowan/localinference/EmbedLimits.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$HDR" "$TMP/hdr.bak"; cp "$KT" "$TMP/kt.bak"
cp "$LIM" "$TMP/lim.bak"
restore() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/hdr.bak" "$HDR";
            cp "$TMP/kt.bak" "$KT"; cp "$TMP/lim.bak" "$LIM"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/hdr.bak" "$HDR"
              cp "$TMP/kt.bak" "$KT"; cp "$TMP/lim.bak" "$LIM"; }

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

# ── 桩①：清 KV 只清 seq 0（不遍历所有 seq）────────────────────────────────
# 这正是"向量混味"的物质基础：第二排之后的条目复用到上一批同号 seq 的 KV，
# 语义里混进上一批的内容，而接口 200。
"$PY" - "$JNI" <<'PYEOF1'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 源码里这种"遍历所有 seq"的写法有**三处**（合批入口一处、decode 失败后一处、
# 切段循环里每段一处），三处都要改掉 —— 只改一处的话判据仍能被另两处满足，
# 桩就不红了（那正是"判据被另一处满足"的假绿，本仓库反复记录过）。
# 所以这里**全替** + 断个数。
# ⚠ 0.9.210 起切段循环里那一处是**段与段之间**的隔离（不清就会让下一段
# 复用上一段的 KV，向量混味而接口 200），所以个数必须跟着涨 ——
# 写死 2 时这一桩会直接 anchor miss（那比"静默绿"好，但也得跟着改）。
old = "    for (int j = 0; j < E.seq_max; j++)\n        llama_memory_seq_rm(llama_get_memory(E.ctx), (llama_seq_id) j, -1, -1);"
old_ind = "        for (int j = 0; j < E.seq_max; j++)\n            llama_memory_seq_rm(llama_get_memory(E.ctx), (llama_seq_id) j, -1, -1);"
old_in2 = "            for (int j = 0; j < E.seq_max; j++)\n                llama_memory_seq_rm(llama_get_memory(E.ctx), (llama_seq_id) j, -1, -1);"
n = s.count(old) + s.count(old_ind) + s.count(old_in2)
assert n == 3, f"stub1: anchor miss (found {n})"
s = s.replace(old, "    llama_memory_seq_rm(llama_get_memory(E.ctx), 0, -1, -1);")
s = s.replace(old_ind, "            llama_memory_seq_rm(llama_get_memory(E.ctx), 0, -1, -1);")
s = s.replace(old_in2, "                llama_memory_seq_rm(llama_get_memory(E.ctx), 0, -1, -1);")
assert "E.seq_max; j++" not in s, "stub1: 还有没替掉的遍历"
open(p, 'w', encoding='utf-8').write(s)
PYEOF1
must_red "桩①（清 KV 只清 seq 0）" "没有遍历所有 seq"
reset_src

# ── 桩②：pos 用整批全局下标（不是 seq 内位置）──────────────────────────────
# 词表里位置一错，RoPE 一转，向量就变成"另一条文本在另一段的语义"——
# 而长度、维度、归一化全对，只有检索结果不准。
"$PY" - "$JNI" <<'PYEOF2'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 0.9.210 起槽位是**段内**下标（`slot`），`pos` 仍取自计划的**整批**下标 `g`
# —— 桩要打在"pos 从哪来"这个量上（写成 `slot` 就是"位置也不是计划给的"）。
old = "                b.pos[slot]       = (llama_pos) plan.pos_of[(size_t) g];"
assert s.count(old) == 1, "stub2: anchor miss"
new = "                b.pos[slot]       = (llama_pos) g;"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF2
must_red "桩②（pos 用整批全局下标）" "填 pos 时没取自计划"
reset_src

# ── 桩③：两处批大小不等 ────────────────────────────────────────────────────
# 每批都在 native 被拒、回落逐条 —— 合批白做，而界面上完全看不出来。
# ⚠ 桩打的是 **EmbedLimits.kt**（0.9.229 起那个数的出处），不是 LlmEngine.kt：
#   后者现在写的是 `= EmbedLimits.kEmbedBatchDefault`（引用），改它只是改了个转发。
"$PY" - "$LIM" <<'PYEOF3'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "const val kEmbedBatchDefault = 8"
assert s.count(old) == 1, "stub3: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "const val kEmbedBatchDefault = 4", 1))
PYEOF3
must_red "桩③（两处默认批大小不等）" "两处默认批大小不相等"
reset_src

# ── 桩③b：上限与 native 的 n_seq_max 脱钩（钳成写死的 8）──
# ISSUE #269 第 2 步把批大小变成可调之后，"钳到上限"成了新的一处可写错的地方：
# 写死 8 而 native 改成别的数时，用户填了合法值也会———每批被拒、回落逐条。
"$PY" - "$KT" <<'PYEOF3B'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (want <= 0) 1 else minOf(want, kEmbedBatchRows)"
assert s.count(old) == 1, "stub3b: anchor miss"
new = "        if (want <= 0) 1 else minOf(want, 8)"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF3B
must_red "桩③b（kEmbedBatchLimit 钳成写死的 8）" "kEmbedBatchLimit 没有钳到 kEmbedBatchRows"
reset_src

# ── 桩③c：建库切片还用写死常量（设置页控不住建库）──
# 这正是本 issue 初始那个"线程数输入框对建库完全无效"的同一形态：
# 参数能填、能存，就是对建库没用。
"$PY" - "$KT" <<'PYEOF3C'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            val end = minOf(i + batch, texts.size)"
assert s.count(old) == 1, "stub3c: anchor miss"
new = "            val end = minOf(i + kEmbedBatchDefault, texts.size)"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF3C
must_red "桩③c（建库切片用写死常量）" "切片没用参数 batch"
reset_src

# ── 桩③d：建库不把传入值过钳判据（直接当批大小用）──
"$PY" - "$KT" <<'PYEOF3D'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val batch = kEmbedBatchLimit(embedBatchSize)"
assert s.count(old) == 1, "stub3d: anchor miss"
new = "        val batch = embedBatchSize"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF3D
must_red "桩③d（建库不过钳判据）" "没把传入的 embedBatchSize 过 kEmbedBatchLimit"
reset_src

# ── 桩④：合批的失败记账抄成两处（回落分支自己再记一遍）────────────────────
# 两处各写一遍 = 迟早只改一处，于是"哪几条失败"随批大小而变，无日志可辨。
"$PY" - "$KT" <<'PYEOF4'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                    List(end - i) { k -> embed(texts[i + k]) }"
assert s.count(old) == 1, "stub4: anchor miss"
new = ("                    List(end - i) { k ->\n"
       "                        val vv = embed(texts[i + k])\n"
       "                        if (vv == null) { failedIdx.add(i + k); skipped++ }\n"
       "                        vv\n"
       "                    }")
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF4
must_red "桩④（失败记账抄成两处）" "failedIdx.add 出现"
reset_src

# ── 桩⑤：batchable_pooling 自己硬编码档位（不由 plan_pooling 分岔）──
# 两份档位清单迟早分岔 —— 读取侧按一个清单取行、合批侧按另一份排布，
# 而**两者都不报错**。
#
# ⚠ 0.9.208 换过这一根桩的**内容**（判据的关系变了，桩必须跟着变）：
# 旧桩把 `return !library_gives_seq(...)` 换成手抄的 `== 0 || == -1`，
# 锚的是"这不等于 library_gives_seq 的取反"。现在那个式子已经不在源码里了
# （三档也能合批），旧锚自然锚不到 —— 而**锚不到必须是硬红**（见下面的兜底）。
# 新桩锚的是同一个病的另一种写法：绕过 `plan_pooling`、自己列放行清单。
"$PY" - "$HDR" <<'PYEOF5'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return plan_pooling(poolTypeRaw, 1).valid;"
assert s.count(old) == 1, "stub5: anchor miss"
new = "    return poolTypeRaw == 0 || poolTypeRaw == -1;   // 自己抄的一份档位清单"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF5
# 兜底：真源码里那句注释与上面写的可能不完全一致，锚不到就报错退出（设计如此，
# 比静默跳过好 —— 静默跳过会让"守卫失效"伪装成"全绿"）。
if ! grep -q "自己抄的一份档位清单" "$HDR"; then
    echo "FAIL  桩⑤：锚点未命中（embed_text.h 里 batchable_pooling 的写法变了）"; bad=$((bad+1))
else
    must_red "桩⑤（batchable_pooling 自己抄档位清单）" "没有走 plan_pooling"
fi
reset_src

# ── 桩⑤b：batchable_pooling **退回旧方向**（库自己池化的档不合批）──────────
# 这一根桩是本轮最要紧的：退回旧方向**不会红在任何"结构"判据上**
#（它仍然"与读取侧同源"、仍然是一份清单），代价却正是本轮要消掉的那件事 ——
# BGE（CLS）与 Qwen3-Embedding（LAST）一条都合批不上，而界面上只是"没变快"。
# 所以判据必须**正面**禁止那个式子，而不是只禁止"手抄清单"。
"$PY" - "$HDR" <<'PYEOF5B'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return plan_pooling(poolTypeRaw, 1).valid;"
assert s.count(old) == 1, "stub5b: anchor miss"
new = "    return !library_gives_seq(poolTypeRaw);   // 退回旧方向"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF5B
if ! grep -q "退回旧方向" "$HDR"; then
    echo "FAIL  桩⑤b：锚点未命中"; bad=$((bad+1))
else
    must_red "桩⑤b（退回『库池化的档不合批』）" "又退回了"
fi
reset_src

# ── 桩⑥：n_seq_max 提到 8 但 n_ctx 不跟着放大（每条的预算被除成 1/8）──────
# 这是本轮**真踩到**的那条：库里 `n_ctx_seq = n_ctx / n_seq_max`。
# 512 / 8 = 64 —— 单条路径里一条 300 token 的文本，pos 从 0 排到 299，
# 越过 64，库报 `sequence positions are not continuous` 或静默算错。
"$PY" - "$JNI" <<'PYEOF6'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把"总量 = 每条预算 × 条数"改回"总量 = 每条预算"（即不放大）
old = "    long long total = (long long) perSeq * (long long) kEmbedBatchRows;"
assert s.count(old) == 1, "stub6: anchor miss"
new = "    long long total = (long long) perSeq;"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF6
must_red "桩⑥（n_ctx 不随 n_seq_max 放大）" "没有按 kEmbedBatchRows 放大"
reset_src

# ── 桩⑦：不改 n_ctx，但把截断点从"每条预算"改回 ctx 总量 ──────────────────
# 与桩⑥ 是同一个毛病的另一半：即便总量对了，截断点看错数也会让文本越界上库。
"$PY" - "$JNI" <<'PYEOF7'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if ((int) toks.size() >= E.n_ctx_seq) {"
assert s.count(old) == 1, "stub7: anchor miss"
open(p, 'w', encoding='utf-8').write(s.replace(old, "if ((int) toks.size() >= E.n_ctx) {", 1))
PYEOF7
must_red "桩⑦（单条截断点看 ctx 总量）" "截断点必须看 E.n_ctx_seq"
reset_src

# ── 桩⑧：合批不再判「全零 = 失败」（只看 native 报的下标）────────────────
# 合批这条扁平数组里没有 null 这个位置，失败条目只能体现为全零。
# native 的失败下标一旦解析不出来（整段作废成空集），那些全零就会被当成
# **合法向量**收进索引 —— 而它与任何查询的余弦都是 0/0=NaN，排序未定义。
"$PY" - "$KT" <<'PYEOF8'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if (failed.contains(i) || isAllZero(v)) { out.add(null); continue }"
assert s.count(old) == 1, "stub8: anchor miss"
new = "if (failed.contains(i)) { out.add(null); continue }"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF8
must_red "桩⑧（合批不判全零）" "没有对『全零向量』判失败"
reset_src

# ── 桩⑨：合批这一层自己再判一次档位（档位清单被抄成两份）────────────────
# 这正是"走的路径"与"报的档位"分家的起点：`nativeEmbedBatch` 自己比
# `poolType == 0 || poolType == -1`，而 `batchable_pooling` 是另一份清单 ——
# 迟早只改一处，被合批的那一档读错 buffer（全零或串味，而长度、维度全对）。
"$PY" - "$JNI" <<'PYEOF9'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if (!embedtext::batchable_pooling(poolType)) {"
assert s.count(old) == 1, "stub9: anchor miss"
new = "if (!(poolType == 0 || poolType == -1)) {"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF9
must_red "桩⑨（合批自己再判一次档位）" "没有用 batchable_pooling"
reset_src

# ── 桩⑩：`library_gives_seq` 的**放行清单被漏写一档**（CLS）──────────────
# 与桩⑤ 不同：桩⑤ 把清单抄在**同一个函数体**里（那一条抓到的是"硬编码档位"），
# 这一桩改的是**放行集合本身** —— 它同时也是读取侧（`read_from_seq`）的判据，
# 所以"少一档"会让**两条路一起错**：读取侧把 CLS 读去 `ith`（全零），
# 合批侧按补集把 CLS 放行进合批（向量同样取自 `embd_seq`）。
# 而两者都不报错，只在换模型时出现（BGE 就是 CLS）。
"$PY" - "$HDR" <<'PYEOF10'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    return poolTypeRaw == 1 || poolTypeRaw == 2 || poolTypeRaw == 3;"
assert s.count(old) == 1, "stub10: anchor miss"
new = "    return poolTypeRaw == 1 || poolTypeRaw == 3;   // 手抄：CLS 也算进『库给 seq』"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF10
must_red "桩⑩（library_gives_seq 放行清单漏掉 CLS）" "放行清单里少了 CLS"
reset_src

echo
echo "=== 嵌入合批守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
