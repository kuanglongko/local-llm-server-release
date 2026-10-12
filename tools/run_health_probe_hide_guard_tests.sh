#!/bin/sh
# 「探活报告瘦身守卫」自己的自测：把源码**按各种改错方向**打桩，
# 确认守卫会**变红**（绿不证明什么，只有"改错了它就叫"才证明它在守）。
#
# 每份桩都是"一处真实会犯的错"，且**只改那一处** —— 改多了就说不清是哪条
# 判据抓到的（本仓库反复记录的那族：判据要落在具体一格上）。
#
# 运行：sh tools/run_health_probe_hide_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

SRC=app/src/main/java/com/xiaowan/localinference
CHECK="tools/health/check_health_probe_hide.py"

ok=0; bad=0
run_case() {
    name="$1"; dir="$2"
    if "$PY" "$CHECK" "$dir/HealthCheck.kt" "$dir/EngineActivity.kt" >/dev/null 2>&1; then
        echo "FAIL  $name（该红却绿了）"; bad=$((bad+1))
    else
        echo "PASS  $name"; ok=$((ok+1))
    fi
}

# 干净树必须先绿（否则后面所有"变红"都可能只是恒红）。
CLEAN=$(mktemp -d)
cp "$SRC/HealthCheck.kt" "$SRC/EngineActivity.kt" "$SRC/EmbedLimits.kt" "$CLEAN/"
if "$PY" "$CHECK" "$CLEAN/HealthCheck.kt" "$CLEAN/EngineActivity.kt" >/dev/null 2>&1; then
    echo "PASS  基线：干净树全绿"; ok=$((ok+1))
else
    echo "FAIL  基线：干净树就不绿（后面所有判据都不可信）"; bad=$((bad+1))
fi

# 桩①：开关的**默认值**被改成 true —— 用户眼前那份报告又长回十几格原始读数。
#       这是这次要修的东西的**原样复活**，而它看起来"开关还在、很克制"。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'showRawEmbedProbe: Boolean = false'
assert old in s, 'mutation did not apply'
s = s.replace(old, 'showRawEmbedProbe: Boolean = true', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩①：开关默认改成 true（报告又铺回原始读数）" "$T"

# 桩②：调用点自己传 true —— 同上，只是改在调用方。判据必须两个面都拦
#       （形参默认值对、调用方传 true 时用户看到的还是全量）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EngineActivity.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'HealthCheck.format(r, host, probePort)'
assert old in s, 'mutation did not apply'
s = s.replace(old, 'HealthCheck.format(r, host, probePort, showRawEmbedProbe = true)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩②：探测那一趟自己打开了开关（报告又铺回去）" "$T"

# 桩③：那两段从 format 里**直接删掉** —— 报告当然干净了，可那些读数从此
#       **没有任何地方算得出来**（`/health` 有、日志没有）。这一族比铺出来更坏：
#       "算出来没人读 == 没算"，而那十几个字段就成了写在服务端的摆设。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把那道门连同门里内容一起删掉（按括号配平找门的收尾）。
i = s.index('if (showRawEmbedProbe) {')
k = s.index('{', i); depth = 0
for x in range(k, len(s)):
    if s[x] == '{': depth += 1
    elif s[x] == '}':
        depth -= 1
        if depth == 0:
            s = s[:i] + s[x+1:]
            break
assert 'if (showRawEmbedProbe) {' not in s, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩③：那两段被整个删掉（读数没有任何落点）" "$T"

# 桩④：日志那一行没接上（读数从报告里消失后，**确实没有落点**了）。
#       ⚠ 只删"逐个落"那层（换个不落日志的写法）—— 这正是"看起来还在调它"的坏法。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/EngineActivity.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'for (line in HealthCheck.rawEmbedProbeLines(r)) LlmEngine.uiLog(line)'
assert old in s, 'mutation did not apply'
s = s.replace(old, 'HealthCheck.rawEmbedProbeLines(r)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩④：日志那一行没接上（完整读数没有落点）" "$T"

# 桩⑤：完整读数出口里**漏掉一格**（这里漏 `embedParityGroup`）——
#       只报数字不报来源时，"逐位相同 4/4"在两组样本下**字面一样、含义相反**，
#       而那正是这一族被真机现场暴露出来的那一点。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'r.embedParityGroup.orEmpty().ifEmpty { "(未报)" }'
assert old in s, 'mutation did not apply'
s = s.replace(old, '"-"', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩⑤：完整读数漏掉「取自哪一组」" "$T"

# 桩⑥：完整读数出口整个删掉（那十几个字段从此没有任何落点）。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
i = s.index('fun rawEmbedProbeLines(')
k = s.index('{', i); depth = 0
for x in range(k, len(s)):
    if s[x] == '{': depth += 1
    elif s[x] == '}':
        depth -= 1
        if depth == 0:
            s = s[:i] + s[x+1:]
            break
assert 'fun rawEmbedProbeLines(' not in s, 'mutation did not apply'
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩⑥：完整读数出口被删掉" "$T"

# 桩⑦：那条分岔被去掉 —— 全绿时又整份复述响应原文（用户眼前那份报告长回去）。
#       ⚠ 这正是**这次要修的东西的原样复活**，而它看起来"只是少了一个 if"。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (verdict(r) == Verdict.ALIVE && r.jsonParsed) {'
assert old in s, 'mutation did not apply'
s = s.replace(old, 'if (r.httpCode == -1) {', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩⑦：全绿时又整份复述响应原文（这次要修的那件事复活）" "$T"

# 桩⑧：一刀切把回显删掉 —— 报告"干净"了，可异常时用户只拿到"不可达"三个字，
#       原文（"到底哪里不对"的唯一证据）没了。方向与桩⑦相反，同样会被读成"改好了"。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'sb.append("\\n响应原文：${flat.take(BODY_PREVIEW_CHARS)}")'
assert old in s, 'mutation did not apply'
s = s.replace(old, '', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩⑧：一刀切删掉回显（异常时用户没了复核的证据）" "$T"

# 桩⑨：向量模型那格没进报告（用户点名要加的）—— 读字段那行被摘掉。
T=$(mktemp -d); cp "$CLEAN"/*.kt "$T"/
"$PY" - "$T/HealthCheck.kt" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'r.embedLoaded?.let { loaded ->'
assert old in s, 'mutation did not apply'
s = s.replace(old, 'null?.let { loaded ->', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
run_case "桩⑨：向量模型那格没进报告（用户点名要加的那行）" "$T"

echo
echo "=== 探活报告瘦身守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
