#!/bin/sh
# 自测 `tools/run_rag_remote_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫全是**存在性/关系性断言**，它们最容易的失效是：
#   · pattern 写成到处都有的文本 -> 恒真（写错的那一支里关键字往往还在）；
#   · 函数体提取不到就静默通过 -> 恒真（名字改了，两边都取不到）。
# 唯一有效的做法是对着**已知该红**的桩跑。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
#
# 运行：sh tools/run_rag_remote_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_remote_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
INJ=$SRC/RagInject.kt
API=$SRC/HttpApi.kt
PROTO=$SRC/RagProtocol.kt
EMB=$SRC/EmbeddingsApi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 基线守卫必须全绿，否则桩会被当成基线存下来。
if ! sh "$GUARD" > /dev/null 2>&1; then
    echo "FAIL  跑自测前工作树不干净：基线守卫已经是红的。"
    echo "      先手动还原（git checkout）再跑本自测。"
    exit 2
fi

TMP=$(mktemp -d)
for f in "$INJ" "$API" "$PROTO" "$EMB"; do
    cp "$f" "$TMP/$(basename "$f").bak"
done
restore() {
    for f in "$INJ" "$API" "$PROTO" "$EMB"; do
        cp "$TMP/$(basename "$f").bak" "$f"
    done
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
    for f in "$INJ" "$API" "$PROTO" "$EMB"; do
        [ -f "$TMP/$(basename "$f").bak" ] && cp "$TMP/$(basename "$f").bak" "$f"
    done
}
must_red() {
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
    else ck "$name：守卫全绿（实际红了）" 0; sed -n '1,80p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 桩①：远端开关默认值被改成 true（升级后所有人被静默注入）───────────
# 这是本阶段唯一会**主动伤害用户**的错误，必须能抓到。
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('getBoolean(KEY_REMOTE_ENABLED, false)') == 1
s = s.replace('getBoolean(KEY_REMOTE_ENABLED, false)',
              'getBoolean(KEY_REMOTE_ENABLED, true)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩①（远端开关默认改成 true）" "远端开关读出默认 false"
reset_src

# ── 桩②：App 内开关默认值被"顺手统一"成 false ─────────────────────────
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('getBoolean(KEY_ENABLED, true)') == 1
s = s.replace('getBoolean(KEY_ENABLED, true)', 'getBoolean(KEY_ENABLED, false)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩②（App 内开关默认被带偏）" "App 内开关默认仍是 true"
reset_src

# ── 桩③：远端库名与 App 内当前库共用键（切库会静默改远端）─────────────
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('const val KEY_REMOTE_KB = "rag_remote_kb"') == 1
s = s.replace('const val KEY_REMOTE_KB = "rag_remote_kb"',
              'const val KEY_REMOTE_KB = "rag_current_kb"', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩③（远端库名与当前库共用键）" "四个 prefs 键两两不同"
reset_src

# ── 桩④：remoteKb 改成跟随 currentKb ──────────────────────────────────
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('prefs(ctx).getString(KEY_REMOTE_KB, "").orEmpty()') == 1
s = s.replace('prefs(ctx).getString(KEY_REMOTE_KB, "").orEmpty()',
              'prefs(ctx).getString(KEY_CURRENT, "").orEmpty()', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
# 键落在**守卫那一行的标签**上（python 子脚本自己的输出不是 FAIL 行，
# must_red 找的是守卫输出里的 FAIL 行）。
must_red "桩④（远端库名跟随当前库）" "remoteKb 不跟随 currentKb"
reset_src

# ── 桩⑤：HTTP 路径不再读远端开关（开了也不会注入）─────────────────────
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('RagInject.remoteEnabled(ragPrefs())') >= 2
# 把 handleChat 里那次取值改成字面量 false（等价于"远端路径没读开关"）
old = 'val rag = LlmEngine.ragContext(\n                ragPrefs(), remoteKb, lastUserText, RagInject.remoteEnabled(ragPrefs()))'
assert s.count(old) == 1
s = s.replace(old, 'val rag = LlmEngine.ragContext(\n                ragPrefs(), remoteKb, lastUserText, false)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑤（HTTP 路径不读远端开关）" "远端开关作为 ragContext 的实参传入"
reset_src

# ── 桩⑥：流式收尾帧漏掉 local_rag（那条路径永远看不到被注入）──────────
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'chatChunkJson(id, modelName, created, "{}", fin, lpField, ragField)'
assert s.count(old) == 1
s = s.replace(old, 'chatChunkJson(id, modelName, created, "{}", fin, lpField)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑥（流式收尾帧漏 local_rag）" "流式收尾帧带 local_rag"
reset_src

# ── 桩⑦：/v1/embeddings 被"顺手"绑上 RAG 开关（两件不相干的事互相绑架）──
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (!LlmEngine.hasEmbedModel) {'
assert s.count(old) >= 1
# 只在 handleEmbeddings 内插一句（用它的错误文案当锚）
anchor = 'if (!LlmEngine.hasEmbedModel) {\n            emitLog("拒绝 /v1/embeddings'
assert s.count(anchor) == 1
s = s.replace(anchor,
              'if (!RagInject.remoteEnabled(this)) { writeJson(out, 503, errJson("知识库未启用"), corsOrigin); return }\n        ' + anchor, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑦（embeddings 被绑上 RAG 开关）" "不引用任何 RAG 组件"
reset_src

# ── 桩⑧：handleEmbeddings 不判嵌入模型（缺模型时静默返回空向量）───────
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (!LlmEngine.hasEmbedModel) {\n            emitLog("拒绝 /v1/embeddings'
assert s.count(old) == 1
s = s.replace(old, 'if (false) {\n            emitLog("拒绝 /v1/embeddings', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑧（embeddings 不判嵌入模型）" "独立判 hasEmbedModel"
reset_src

# ── 桩⑨：responseField 没注入时返回非空（恒出现字段）──────────────────
"$PY" - "$PROTO" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (inj == null) "" else'
assert s.count(old) == 1
s = s.replace(old, 'if (inj == null) ",\\"local_rag\\":null" else', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑨（没注入也留字段 = 两种情形不可分）" "responseField 返回空串"
reset_src

# ── 桩⑩：ragField 各拼一份（三条路径漂移）────────────────────────────
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val ragField = ragFieldOf(lastRagKb, lastRagInjected.get())'
assert s.count(old) == 1
s = s.replace(old, old + '\n                    val ragField2 = ragFieldOf(lastRagKb, lastRagInjected.get())', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑩（ragField 拼了第二份）" "ragField 只算一次"
reset_src

# ── 桩⑪：把 HttpApi 这个 object 当 Context 传（= 0.9.168 的编译阻断）──
# 这一桩不是"静默错"，而是**直接编译不过**：`RagInject.remoteKb` 的形参是
# `android.content.Context`，而 `this` 在 `object HttpApi` 里是 HttpApi 自己。
# 守卫要能在这类改动一进来就点出来 —— 靠 CI 才知道的成本是"一轮编译"。
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val remoteKb = RagInject.remoteKb(ragPrefs())'
assert s.count(old) == 1
s = s.replace(old, 'val remoteKb = RagInject.remoteKb(this)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑪（object 当 Context 传 = 类型不匹配）" "没有裸 this 当 Context"
reset_src

# ── 桩⑫：「开关开着但没注入」那一支又自己拼了一遍条件 ──────────────
# 漂移方向是"两处判据不一致" —— 一处改了另一处没改，日志与行为说的不是同一件事。
"$PY" - "$API" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '} else if (ragActive()) {'
assert s.count(old) == 1
s = s.replace(old,
              '} else if (RagInject.remoteEnabled(ragPrefs()) && remoteKb.isNotEmpty()) {', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑫（取值口判据写了两处）" "只判一次"
reset_src

# ── 桩⑬：库名到响应时现读（与条数不同源 = 一对从未同时成立过的事实）──
# ⚠ 本轮修的缺陷形态。用户在长生成期间把远端库名从 A 改成 B，响应里就成了
# `kb:"B", injected:3` —— 字段合法、JSON 合法、只是内容不真。
"$PY" - "$API" <<'PYEOF13'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'RagProtocol.Injection(kb, count)'
assert s.count(old) == 1
s = s.replace(old, 'RagProtocol.Injection(RagInject.remoteKb(ragPrefs()), count)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF13
must_red "桩⑬（库名到响应时现读，与条数不同源）" "ragFieldOf 体内不再现读 remoteKb"
reset_src

# ── 桩⑭：取值口退回"只传条数"（库名又丢了）────────────────────────────
"$PY" - "$API" <<'PYEOF14'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val ragField = ragFieldOf(lastRagKb, lastRagInjected.get())'
assert s.count(old) == 1
s = s.replace(old, 'var ragField = ragFieldOf("", lastRagInjected.get())', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF14
must_red "桩⑭（取值口不再传注入当时的库名）" "响应里的 local_rag 用注入当时存下的库名"
reset_src

# ── 桩⑮：注入成功时不存库名（只存条数）────────────────────────────────
"$PY" - "$API" <<'PYEOF15'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'lastRagKb = rag.kb'
assert s.count(old) == 1
s = s.replace(old, 'lastRagKb = ""', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF15
must_red "桩⑮（注入成功时不记库名）" "注入成功那一刻把库名与条数一起写下"
reset_src

# ── 收尾 ───────────────────────────────────────────────────────────────
reset_src
must_green "收尾：全部桩已还原"

echo
echo "=== 远端知识库守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
