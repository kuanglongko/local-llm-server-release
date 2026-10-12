#!/bin/sh
# 「原始读数只进日志、不进用户报告」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════
# `embed_parity_*` / `embed_retrieve_*` 那十几个字段（0.9.223 起为排查
# 「批大小 `g` 能不能按库放行」逐格补上）曾经整段铺在探活报告正文里。
# 用户的原话是"信息太乱了，对用户也没实际用处" —— 报告长了三倍，
# 而多出来的部分**看完不知道该做什么**（日常探活要的是"活没活 / 模型装没装 /
# 库认的批大小是多少"这种一句话能指导下一步的信息）。
#
# 要分开的**不是"报不报"**，是"报给谁看"：
#   · 报告正文（用户）—— 只留能直接决定下一步的结论；
#   · 日志（排查者）—— 完整读数照旧，一格不少；
#   · `/health` 响应体（监控 / 旧客户端）—— 一个字段都没动。
#
# 这一族有两个方向相反、而**都会被读成"改好了"**的坏法：
#   · **铺回报告**：把开关的默认值改成 true（或某个调用点自己传 true）——
#     用户眼前那份报告又长回去；
#   · **删干净却没人接住**：format 里那几段直接删掉。报告当然"干净"了，
#     可那十几个字段从此**没有任何落点**（`/health` 有、日志没有）——
#     "算出来没人读 == 没算"，本仓库反复记录的那族。
#
# **单测只能钉住一半**：`HealthCheckTest` 喂的是一份手写的 `Result`，
# "调用方有没有偷偷打开开关"、"日志那一行有没有真的接上"它看不见。
# 所以判据必须锚源码结构关系（见 `tools/health/check_health_probe_hide.py`）。
#
# 运行：sh tools/run_health_probe_hide_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

HEALTH=app/src/main/java/com/xiaowan/localinference/HealthCheck.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "两个源文件都在" "[ -f \$HEALTH ] && [ -f \$ACT ]"

# ① 全链判据收在一个 checker 里（它们是同一个契约的几个面），失败时逐条点名。
c "原始读数从报告正文移出、且完整读数有落点（默认关 + 日志那一行）全程接通" \
  "$PY tools/health/check_health_probe_hide.py \"\$HEALTH\" \"\$ACT\""

# ② 开关在**签名里**、默认 false —— 隐藏必须是默认值，不是每个调用点各写各的
#    （写在调用点上时，漏一个调用点就把十几格原始读数又铺回用户眼前）。
c "format 的开关是形参且默认 false（隐藏是默认值，不靠调用点自觉）" \
  "grep -q 'showRawEmbedProbe: Boolean = false' \$HEALTH"

# ③ 那两段**还在** format 里（只是被开关挡着）—— 直接删掉时，"打开开关能看见"
#    就是空的，而更坏的一半是：那些读数**没有任何地方算得出来**了。
c "那两段没有从 format 里被删掉（只是被开关挡着）" \
  "grep -q 'if (showRawEmbedProbe) {' \$HEALTH"

# ④ 完整读数从日志出口落下来（`/health` 里报了什么，日志里就有对应的一行）。
#    ⚠ 锚 `for (...) uiLog(...)` 这种**逐个落**的形状：只调一次不落、
#      或落到别处时，"完整信息不丢"这句就是空的。
c "探测那一趟把完整读数逐行打进日志" \
  "grep -q 'for (line in HealthCheck.rawEmbedProbeLines(r)) LlmEngine.uiLog(line)' \$ACT"

# ⑤ A 档新增：**向量模型**那一格（用户点名要加）必须真的进报告。
#    ⚠ 锚的是**读字段的那一处**（`r.embedLoaded`），不是"文件里出现过 embed_loaded"
#      —— 字段名在 Result 的 kdoc 与 parse 里也有，按名字锚会被注释喂绿。
c "报告里报了向量模型（嵌入那份）—— 缺它时检索必然全空而 model_loaded 看不出来" \
  "grep -q 'r.embedLoaded' \$HEALTH && grep -q '向量模型' \$HEALTH"

# ⑥ 报告不再复述整份响应原文，但**异常时照旧回显**（两个方向都要在）。
c "全绿时不再复述整份响应原文（真机那份 1249 字符就是\"信息太乱\"的来源）" \
  "grep -q '完整响应不在此复述' \$HEALTH"
c "异常时照旧回显原文（证据必须能被独立复核 —— 与上一条方向相反）" \
  "grep -q '响应原文：' \$HEALTH"

echo
echo "=== 探活报告瘦身守卫：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
