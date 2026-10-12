#!/bin/sh
# 自测 `tools/run_http_body_guard.py`：把它对着**就地改坏的真源码**跑，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是"按 Kotlin 词法还原响应体 -> 真解析 JSON"，它有三个自欺模式：
#
#   1. **恒真**：还原出来的样例恰好合法（比如把插值一律填成 0，反而把字符串位的
#      引号结构错掩盖掉）。所以样例的代入必须**类型正确**，桩里专门放一个
#      "引号写多一个"的坏法，它必须被抓住 —— 抓不住就说明代入把问题填平了。
#   2. **恒假后被静音**：pattern 与源码差一个字符 -> 守卫永远 FAIL ->
#      有人 `|| true` 掉。所以每条桩都断言"守卫变红"且"指到具体那条"。
#   3. **锚错位置**：钉"文件里出现过 JSON"而不是"这一处响应体是合法 JSON"。
#
# 桩全部是**真正会写错的那几种**（对应真事故与真写法），每条映射一个坏法：
#   · 行尾引号写多一个      -> JSON 里凭空多一对空串（Issue #77 同款，真事故）
#   · 漏一个逗号            -> 客户端 `JSON.parse` 抛
#   · choices 里 index 漏了 -> SDK 按 index 定位时错位
#   · Content-Length 用字符数 -> 含中文的响应体被客户端截断
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_http_body_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_http_body_guard.py
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"
restore() { cp "$TMP/http.bak" "$HTTP"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/http.bak" "$HTTP"; }

run_guard() { set +e; "$PY" "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if ! grep -qF "$key" "$TMP/guard.out"; then
        ck "$name：指到了「$key」" 0
        return
    fi
    line=$(grep -F -n "$key" "$TMP/guard.out" | head -1 | cut -d: -f1)
    if sed -n "1,${line}p" "$TMP/guard.out" | grep -q "^FAIL"; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」（该行不是 FAIL）" 0
    fi
}

patch() { # 文件 python-替换脚本
    "$PY" - "$1" <<PYEOF
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
$2
open(p, 'w', encoding='utf-8').write(s)
PYEOF
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：非流式 chat 响应体行尾引号**写多一个**（Issue #77 同款）────────
# 把 `...,"usage":$usage}"""` 改成多一个引号：JSON 里凭空多一对空串。
patch "$HTTP" '
old = "\"\"\"\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":$contentField$reasonField$toolField}$lpField,\"finish_reason\":\"$fin\"}],\"usage\":$usage$ragField}\"\"\""
new = "\"\"\"\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":$contentField$reasonField$toolField}$lpField,\"finish_reason\":\"$fin\"}],\"usage\":$usage$ragField}\"\"\"\""
assert s.count(old) == 1, "桩①：没找到 chat 响应体收口"
s = s.replace(old, new, 1)'
must_red "桩①（chat 响应体行尾引号多一个 -> 响应体非法）" "/v1/chat/completions 非流式响应体：是合法 JSON"
reset_src

# ── 2. 桩②：completions 响应体**漏一个逗号** ────────────────────────────────
patch "$HTTP" '
old = "\"text\":${sb.toString().toJsonStr()}$lpField,\"finish_reason\""
new = "\"text\":${sb.toString().toJsonStr()}$lpField\"finish_reason\""
assert s.count(old) == 1, "桩②：没找到 completions 响应体的逗号"
s = s.replace(old, new, 1)'
must_red "桩②（completions 响应体漏逗号）" "/v1/completions 非流式响应体：是合法 JSON"
reset_src

# ── 3. 桩③：chat chunk 的 choices[0] 里 **index 删掉** ──────────────────────
patch "$HTTP" '
old = "\"\"\"\"choices\":[{\"index\":0,\"delta\":$deltaJson$logprobsField,\"finish_reason\":$fin}]$ragField}\"\"\""
new = "\"\"\"\"choices\":[{\"delta\":$deltaJson$logprobsField,\"finish_reason\":$fin}]$ragField}\"\"\""
assert s.count(old) == 1, "桩③：没找到 chat chunk 的 index"
s = s.replace(old, new, 1)'
must_red "桩③（chat chunk 少了 index:0）" "chat chunk 的 choices[0].index：choices 里显式带 index:0"
reset_src

# ── 4. 桩④：Content-Length 改用**字符数**（含中文的响应体会被截断）─────────
patch "$HTTP" '
old = """\"Content-Length: ${b.size}\\r\\n\""""
new = """\"Content-Length: ${body.length}\\r\\n\""""
assert s.count(old) == 1, "桩④：没找到 Content-Length"
s = s.replace(old, new, 1)'
must_red "桩④（Content-Length 用字符数）" "Content-Length 取自 UTF-8 字节数"
reset_src

# ── 5. 桩⑤：errJson **漏掉右花括号**（错误响应变非法 JSON）─────────────────
patch "$HTTP" '
old = "\"\"\"{\"error\":{\"message\":${msg.toJsonStr()},\"type\":\"local_inference_error\"}}\"\"\""
new = "\"\"\"{\"error\":{\"message\":${msg.toJsonStr()},\"type\":\"local_inference_error\"}\"\"\""
assert s.count(old) == 1, "桩⑤：没找到 errJson 收口"
s = s.replace(old, new, 1)'
must_red "桩⑤（errJson 漏右花括号）" "errJson 响应体：是合法 JSON"
reset_src

# ── 6. 还原后必须全绿（确认桩只影响该处、没有残留）─────────────────────────
if run_guard; then ck "全部还原后：守卫回绿（桩没有残留污染）" 1
else ck "全部还原后：守卫回绿" 0; fi

echo
echo "=== http body 守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
