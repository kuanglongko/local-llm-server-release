#!/bin/sh
# 「检索对照真的接上了」的源码级守卫（ISSUE #269 第 5 步）。
#
# ═══════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠 EmbedRetrieveParity 的单测）
# ═══════════════════════════════════════════════════════════════════════
# 用户那句话问得对：「索引逐字节对比都不一样，检索出来能一样？」——
# 而本仓库之前**只有一个向量级的读数**，于是这句问话只能靠**推断**回答。
# 这一格补上之后，还有一族"接上了但量不到"的静默失效：
#
#   · 判定层算得对，但**没有入口**跑它；
#   · 有入口，但只编了**一套**向量（同一侧查自己）-> 结论恒"一致"（假的）；
#   · 两侧用了**同一个批大小** -> 比的不是批大小，结论恒"一致"；
#   · 查询**不在同一批文本里** -> 两侧都搜不到，"都空"被读成"答案没变"；
#   · 结论只打日志不落 `/health` -> 跨版本要对的那条只能翻日志；
#   · `/health` 报了但 `HealthCheck.parse` 没解析 -> 报告里读不到（与没报等价）；
#   · 「测没测」不单独一格 -> 没测时 `answer_changed` 也是 0（与"全同"同形）；
#   · 换嵌入模型不清结论 -> 看到的是上一个模型的结论；
#   · 查询向量**写死成 1 条一批** -> 只在批大小为 1 时偶然对（配置改了就成了假的成立）；
#   · 落 `/health` 时**不分组** -> 两次调用无条件覆盖，报的是后跑的**等长**组
#     （按构造必然稳定）—— 等于用必然成立的结论回答"答案会不会变"。
#
# **单测钉不住它们**：`EmbedRetrieveParity` 的单测喂什么下标就比什么下标，
# "界面上有没有那个入口"、"结论有没有落进 /health"它看不见。
# 所以判据必须锚源码结构关系。
#
# 运行：sh tools/run_embed_retrieve_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
HEALTH=app/src/main/java/com/xiaowan/localinference/HealthCheck.kt
RETR=app/src/main/java/com/xiaowan/localinference/EmbedRetrieveParity.kt
PROBE=app/src/main/java/com/xiaowan/localinference/EmbedParityProbe.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "六个源文件都在" \
  "[ -f \$ENGINE ] && [ -f \$ACT ] && [ -f \$HTTP ] && [ -f \$HEALTH ] && [ -f \$RETR ] && [ -f \$PROBE ]"

# ① 全链判据收在一个 checker 里（它们是同一个契约的几个面），失败时逐条点名。
c "检索对照（入口->两侧各一套向量->结论->/health 读数->探活解析）全程接通" \
  "$PY tools/embed_retrieve/check_embed_retrieve_wiring.py \"\$ENGINE\" \"\$ACT\" \"\$HTTP\" \"\$HEALTH\" \"\$RETR\" \"\$PROBE\""

# ② 判定层与决策层**必须分开**：`EmbedRetrieveParity` 只给结论，不许出现
#    "沿用 / 重建"这类决策符号（与 EmbedParity 同一条规矩）。
c "判定层不做决策（EmbedRetrieveParity 里不出现沿用/重建这类决策符号）" \
  "$PY -c \"
import re, sys
s = open('$RETR', encoding='utf-8').read()
sys.exit(0 if not re.search(r'Load\\.(none|ok)|savePartial|rebuild\\s*\\(', s) else 1)
\""

# ③ 判据必须落在**集合**上，不是名次：只比名次时打平处换序会被读成"答案变了"，
#    而那是另一件事（处置不同）。
c "答案变了与名次变了分成两格（answerChanged / orderChanged / sameSet）" \
  "grep -q 'answerChanged' \$RETR && grep -q 'orderChanged' \$RETR && grep -q 'sameSet' \$RETR"

# ④ 「测没测」必须单独一格（没测时 answerChanged 也是 0 —— 与"全同"同形）。
c "「测没测」单独一格（measured），没测不等于一致" \
  "grep -q 'val measured' \$RETR && grep -q 'embed_retrieve_measured' \$HTTP && grep -q 'embed_retrieve_measured' \$HEALTH"

# ⑤ 两侧都没搜到 = 没量到（不是一致）：`vacuous` 这一格必须在。
c "两侧都搜到才算量到（vacuous 那一格），都空不许报成一致" \
  "grep -q 'vacuous' \$RETR"

# ⑥ 查询向量必须按 **batchB** 那一侧编（不是写死 1 条一批）—— 只在批大小为 1 时
#    才偶然与"查询来自新配置那侧"一致，配置改了就成了一个假的成立。
c "查询向量按 batchB 那一侧编（不是写死 1 条一批）" \
  "grep -q 'embedRowsInMemory(queries, dim, batchB)' \$ENGINE"

# ⑦ 落 `/health` 必须**只落长短悬殊那组**（等长组按构造必然稳定，
#    报它等于用必然成立的结论回答"答案会不会变"）。
c "落 /health 只落长短悬殊那组（等长组不许覆盖它）" \
  "grep -q 'label == LONG_SHORT_LABEL) EmbedRetrieveParity.Last.record(report)' \$ENGINE && grep -q 'const val LONG_SHORT_LABEL' \$ENGINE"

echo
echo "=== 嵌入检索对照守卫：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
