#!/bin/sh
# 「检索对照守卫」自己的自测（ISSUE #269 第 5 步）：
# 把源码**按各种改错方向**打桩，确认守卫会**变红**（绿不证明什么，
# 只有"改错了它就叫"才证明它在守）。
#
# 每份桩都是"一处真实会犯的错"，且**只改那一处** —— 改多了就说不清是哪一条判据
# 抓到的（本仓库反复记录的那族：判据要落在具体一格上）。
#
# 运行：sh tools/run_embed_retrieve_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

SRC=app/src/main/java/com/xiaowan/localinference
CHECK="tools/embed_retrieve/check_embed_retrieve_wiring.py"

ok=0; bad=0
# 跑一套桩树（5 个文件），返回 checker 的退出码。
run_case() {
    name="$1"; dir="$2"
    if "$PY" "$CHECK" "$dir/LlmEngine.kt" "$dir/EngineActivity.kt" "$dir/HttpApi.kt" \
        "$dir/HealthCheck.kt" "$dir/EmbedRetrieveParity.kt" "$dir/EmbedParityProbe.kt" \
        >/dev/null 2>&1; then
        echo "FAIL  $name（该红却绿了）"; bad=$((bad+1))
    else
        echo "PASS  $name"; ok=$((ok+1))
    fi
}

# 干净树必须先绿（否则后面所有"变红"都可能只是恒红）。
CLEAN=$(mktemp -d)
# ⚠ 桩树里的文件清单**必须把 checker 会去读的都带上**：它除了显式那几个形参，
#   还会从 `LlmEngine.kt` 的**同目录**取 `EmbedLimits.kt`（0.9.229 起那两个数
#   的出处搬到了那里）。少带一个 -> 干净树也不绿、所有桩都成了"恒红"。
cp "$SRC/LlmEngine.kt" "$SRC/EngineActivity.kt" "$SRC/HttpApi.kt" "$SRC/HealthCheck.kt" \
   "$SRC/EmbedRetrieveParity.kt" "$SRC/EmbedParityProbe.kt" "$SRC/EmbedLimits.kt" "$CLEAN/"
if "$PY" "$CHECK" "$CLEAN/LlmEngine.kt" "$CLEAN/EngineActivity.kt" "$CLEAN/HttpApi.kt" \
    "$CLEAN/HealthCheck.kt" "$CLEAN/EmbedRetrieveParity.kt" "$CLEAN/EmbedParityProbe.kt" \
    >/dev/null 2>&1; then
    echo "PASS  基线：干净树全绿"; ok=$((ok+1))
else
    echo "FAIL  基线：干净树就不绿（后面所有判据都不可信）"; bad=$((bad+1))
fi

# 桩①：入口只编一套向量（"同一侧查自己" -> 结论恒一致，一个假的"答案没变"）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("        val vecB = embedAllInMemory(texts, dim, batchB) ?: return null\n", "")
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩①：入口只编一套向量" "$T"

# 桩②：两侧用同一个批大小（比的不是批大小）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把两个实参都写成同一个（两侧比的是同一个批大小 -> 结论恒"一致"）。
s = s.replace("        batchA: Int,\n        batchB: Int,", "        batchB: Int,\n        batchB2: Int,")
s = s.replace("embedAllInMemory(texts, dim, batchA)", "embedAllInMemory(texts, dim, batchB)")
s = s.replace("EmbedRetrieveParity.compare(\n            queries, indicesA, indicesB, batchA, batchB, topK, scoreA, scoreB)",
              "EmbedRetrieveParity.compare(\n            queries, indicesA, indicesB, batchB, batchB, topK, scoreA, scoreB)")
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩②：两侧都用同一个批大小" "$T"

# 桩③：查询集改成自己手写（不在同一批文本里）-> 两侧都搜不到，
#       "都空"会被读成"答案没变"。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EmbedParityProbe.kt" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = re.sub(r'val RETRIEVE_QUERIES[^=]*=\s*listOf\(.*?\)\n',
           'val RETRIEVE_QUERIES: List<String> = listOf("随便一句库外的话")\n',
           s, count=1, flags=re.S)
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩③：查询集不在同一批文本里（改手写常量）" "$T"

# 桩④：结论不落 Last（/health 永远拿不到）—— 这条对照就没用了。
#       ⚠ 锚**结构**（那一行现在带着 label 分闸，按原文锚会静默失配 ->
#       该红的桩不变红）。这就是本仓库记录过的那族："打桩失败不是绿"。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
out = [ln for ln in s.splitlines(True)
       if 'EmbedRetrieveParity.Last.record(report)' not in ln]
s2 = ''.join(out)
assert 'EmbedRetrieveParity.Last.record(report)' not in s2, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s2)
EOF
run_case "桩④：结论没落 Last（/health 拿不到）" "$T"

# 桩⑤：/health 不报「测没测」这一格（没测时 answer_changed 也是 0，与"全同"同形）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HttpApi.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace('"embed_retrieve_measured":${retr.measured},', '')
assert '":${retr.measured}' not in s, 'mutation did not apply' 
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑤：/health 漏报 embed_retrieve_measured" "$T"

# 桩⑥：探活不解析（字段在响应里、报告里读不到 —— 与没报等价）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace('            embedRetrieveMeasured = bool("embed_retrieve_measured"),\n', '')
assert 'embedRetrieveMeasured = bool(' not in s, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑥：HealthCheck 没解析 embed_retrieve_measured" "$T"

# 桩⑦：界面不调那条对照（"有入口"只是名义上的）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EngineActivity.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("LlmEngine.probeRetrieveParity(", "LlmEngine.probeRetrieveParityDisabled(")
assert 'LlmEngine.probeRetrieveParityDisabled(' in s, 'mutation did not apply' 
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑦：按钮里没调 probeRetrieveParity" "$T"

# 桩⑧：把「答案变了」与「名次变了」压成一格（打平处换序会被读成答案变了）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EmbedRetrieveParity.kt" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("        val orderChanged: Int get() = rows.count { it.onlyOrderChanged }",
              "        val orderChanged: Int get() = 0")
assert 'it.onlyOrderChanged }' not in s, 'mutation did not apply' 
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑧：名次变了那一格被删掉（与答案变了混一起）" "$T"

# 桩⑨：换嵌入模型不清结论（看到的是上一个模型的结论）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("        EmbedRetrieveParity.Last.reset()\n", "")
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑨：换模型没清结论" "$T"

# 桩⑩：那套向量**落盘**了（写出一个用户没点过的知识库）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("    private fun embedAllInMemory(texts: List<String>, dim: Int, batch: Int): FloatArray? {\n",
              "    private fun embedAllInMemory(texts: List<String>, dim: Int, batch: Int): FloatArray? {\n"
              "        RagIndexStore.savePartial(this, \"probe\", emptyList())\n")
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑩：那套向量落盘了（只在内存里的性质被破）" "$T"

# 桩⑪：按钮里两侧取**同一个**批大小（真机那轮闪退现场的那一处）——
#        `if (curBatch == baseBatch) 1 else baseBatch` 在用户把设置改成 1 时
#        会让 otherBatch 也取到 8，比的成了"8 对 8"，结论恒为"答案没变"（假的成立）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EngineActivity.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("""                val otherBatch = if (curBatch != baseBatch) curBatch
                                 else if (baseBatch != 1) 1 else 2""",
              "                val otherBatch = baseBatch")
assert 'val otherBatch = baseBatch' in s, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑪：按钮里两侧取同一个批大小（结论恒一致）" "$T"

# 桩⑫：那一趟跑在**主线程**上（几十秒 native 重活 -> ANR / 被系统杀掉，
#        现场看起来像 native 崩，而探针里没有任何信号记录）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EngineActivity.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("            ioExecutor.execute {\n              try {", "            run {\n              try {")
assert 'ioExecutor.execute {\n              try {' not in s, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑫：比对照跑在主线程上（ANR）" "$T"

# 桩⑬：检索对照的**语料又用回 TEXTS**（4 条），topK=3 时命中集合恒等 ——
#        那一格会永远报"没换答案"，而它不是答案稳，是样本被判据压住了。
#        （真机那轮读数就是被这一处压住的，见 RETRIEVE_TEXTS 对象头。）
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EngineActivity.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("                    EmbedParityProbe.RETRIEVE_TEXTS, EmbedParityProbe.RETRIEVE_TEXTS_QUERIES,",
              "                    EmbedParityProbe.TEXTS, EmbedParityProbe.RETRIEVE_QUERIES,")
assert 'EmbedParityProbe.RETRIEVE_TEXTS,' not in s, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑬：检索语料用回 TEXTS（4 条，Top-3 恒等）" "$T"

# 桩⑭：入口把语料缩到 topK 以下（判据锚的是"语料 >= 2*topK"这一结构关系）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EmbedParityProbe.kt" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = re.sub(r'val RETRIEVE_TEXTS\s*:\s*List<String>\s*=\s*listOf\(.*?\n\s*\)\n',
           'val RETRIEVE_TEXTS: List<String> = listOf(TEXTS[0], TEXTS[1])\n',
           s, count=1, flags=re.S)
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑭：检索语料条数不足 topK 的两倍" "$T"

# 桩⑮：入口不拒绝"两侧同一个批大小"（UI 改对了但入口没兜底 ->
#        将来谁再写一个调用点忘了推导，又会造一个假的绿）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = re.sub(r'if \(batchA == batchB \|\| batchA <= 0 \|\| batchB <= 0\) \{.*?\n        \}\n',
           '', s, count=1, flags=re.S)
assert 'batchA == batchB' not in s, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑮：入口没拒绝两侧相同的批大小" "$T"

# 桩⑯：语料的查询集改成手写（不在同一份语料里 -> 两侧都搜不到，
#        "都空"会被读成"答案没变"）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EmbedParityProbe.kt" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = re.sub(r'val RETRIEVE_TEXTS_QUERIES[^=]*=\s*listOf\(.*?\)\n',
           'val RETRIEVE_TEXTS_QUERIES: List<String> = listOf("库外的一句话")\n',
           s, count=1, flags=re.S)
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑯：语料的查询集不在同一份语料里（改手写）" "$T"

# 桩⑰：查询向量写死成 1 条一批（真机那轮读数被喂绿的那一处）——
#        只在 batchB==1 时偶然与"查询来自新配置那一侧"一致；配置不是 1 时，
#        这格比的是"batchA 建的库 vs 1 条一批的查询"，与要量的事无关。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val qvRows = embedRowsInMemory(queries, dim, batchB)"
assert old in s, 'mutation did not apply'
s = s.replace(old, "        val qvRows = queries.map { q ->\n"
                   "            val r = embedBatch(listOf(q), dim)\n"
                   "            if (r is BatchEmbed.Ok) r.vectors.firstOrNull() else null\n"
                   "        }")
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑰：查询向量写死成 1 条一批（不看 batchB）" "$T"

# 桩⑱：落 Last 不带 label 分闸 -> 两次调用无条件覆盖，
#        `/health` 报的是后跑的等长组（按构造必然稳定）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/LlmEngine.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (label.isEmpty() || label == LONG_SHORT_LABEL) EmbedRetrieveParity.Last.record(report)"
assert old in s, 'mutation did not apply'
s = s.replace(old, "        EmbedRetrieveParity.Last.record(report)")
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑱：落 Last 不分组（等长组覆盖长短悬殊组）" "$T"

# 桩⑲：界面两组不各带 label（等长那组会被当成"缺 label 的第一组"落进 /health）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EngineActivity.kt" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert 'LlmEngine.SAME_LEN_LABEL' in s, 'mutation did not apply'
s = s.replace("                    baseBatch, otherBatch, label = LlmEngine.SAME_LEN_LABEL)",
              "                    baseBatch, otherBatch)")
open(p, 'w', encoding='utf-8').write(s)
EOF
run_case "桩⑲：等长那组没带自己的 label（会被当成长短悬殊落 /health）" "$T"

echo
echo "=== 嵌入检索对照守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
