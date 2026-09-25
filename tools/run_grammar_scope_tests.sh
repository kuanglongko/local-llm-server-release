#!/bin/sh
# grammar 模板取值链的行为复刻测试（见 tools/grammar_scope/grammar_scope_test.py）。
#
# 与 `run_grammar_scope_guard.sh`（静态结构守卫）互补：守卫钉"源码里的取值来源"，
# 这一份把取证链**跑一遍**，回答"两个端点最后交给 native 的模板是不是同一份"。
# 并对**旧写法**做反例对照 —— 旧写法必须在同一组判据下红，否则说明用例没打在洞上。
#
# 纯 python3 标准库，不需要工具链。运行：sh tools/run_grammar_scope_tests.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

echo "=== 1) 当前源码：两个端点取值必须一致且都在运行时模板上 ==="
"$PY" tools/grammar_scope/grammar_scope_test.py

echo "=== 2) 反例对照：把 /v1/completions 改回旧写法，必须变红 ==="
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
cp "$HTTP" "$TMP/http.bak"
restore() { cp "$TMP/http.bak" "$HTTP"; }
trap 'restore; rm -rf "$TMP"' EXIT

"$PY" - "$HTTP" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = "chatTemplateOverride = RequestContext.chatTemplateOf(LlmEngine.chatTemplate()),"
if s.count(old) != 1:
    sys.exit("抽取失败：源码里找不到本轮新增的取值表达式（改回去了？）")
s = s.replace(old, "chatTemplateOverride = RequestContext.chatTemplateOf(null),")
open(p, 'w', encoding='utf-8').write(s)
PY

set +e
"$PY" tools/grammar_scope/grammar_scope_test.py > "$TMP/old.out" 2>&1
rc=$?
set -e
restore

if [ "$rc" -eq 0 ]; then
    echo "FAIL  旧写法（让库按模型自选）在判据下**没有**变红 —— 判据是恒真的"
    exit 1
fi
if ! grep -q 'FAIL' "$TMP/old.out"; then
    echo "FAIL  旧写法变红了，但没指出是哪一条判据"
    exit 1
fi
echo "PASS  旧写法变红，且点出了分叉（$(grep -c 'FAIL' "$TMP/old.out") 条）"

echo "=== grammar 模板取值链复刻测试 全部通过 ==="
