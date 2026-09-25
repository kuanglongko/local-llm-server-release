#!/bin/sh
# 自测 `tools/acceptance_sse.py`：对着六份**打桩 SSE 实现**跑，断言"该报有的报有、
# 该报无的报无"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条测试防的是"取证脚本自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# acceptance_sse.py 要装机跑（要真模型、真服务端），所以它在 CI 上无法靠"跑一遍
# 看结论"来保证。而它的三问全是**存在性**判据（"有没有裸标签/重复/乱码"），这类
# 判据有双向的自欺：
#
#   · **恒真**：永远报"有" —— 那什么都能被它"证明"成 bug；
#   · **恒假**：永远报"无" —— 真出问题时它全绿，等于没有这个工具。
#
# 只对着好实现跑一万次，两种都发现不了。所以对着**故意做坏的**桩跑：
#
#   good       —— 健康流                             => 三问必须全"无"（exit 0）
#   dup        —— 同一段逐字段发两次（相邻帧相等）    => 第 2 问必须"有"（形状 A）
#   cumulative —— 每帧都是累计文本                    => 第 2 问必须"有"（形状 B）
#   baretag    —— 字段值里漏裸 `<think>` / `</think>` => 第 1 问必须"有"
#   fffd       —— 尾部发送被切碎的中文字（非法 UTF-8）=> 第 3 问必须"有"
#   splitutf8  —— 同一次切碎，但**整包仍是合法 UTF-8**（边界落在字符内部）
#                 => 第 3 问必须报"可疑"，不能报"无"
#   framing    —— 内容干净，但每条事件分 4 次 write   => 三问必须全"无"（防假红）
#
# 本脚本的第一版判据就踩过假红：把单字符 delta（`"1"`）的天然重复当成了"发两次"，
# 是 `good` 桩抓出来的 —— 所以 framing / good 两份"必须报无"的桩不是凑数。
#
# `splitutf8` 是本轮补的，它钉的是一条**整包解码通过**的故障：只看"响应体是不是
# 合法 UTF-8"，`，`(EF BC 8C) 被切成 EF BC 之后残骸换成 U+FFFD 再拼回去，
# 整包照样合法、U+FFFD 计数还是 0（发出去的就是替换符本身）。这条桩保证
# 取证脚本会去比对"帧边界 vs 字符边界"，而不是只跑一次全量解码。
#
# 不需要 NDK / 设备 / 模型 / 工具链，只要 python3。CI 里可直接跑。
#
# 运行：sh tools/run_sse_forensics_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
SRV=tools/sse_probe/fake_sse_server.py
ACC=tools/acceptance_sse.py
PORT_BASE=${PORT_BASE:-19300}

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() {
    if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
    else echo "FAIL  $1"; bad=$((bad+1)); fi
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# 跑一个模式：起桩 -> 跑取证 -> 打印结论行与退出码
# run <mode> <port>  => 把结论行写到 $TMP/<mode>.line，退出码写到 $TMP/<mode>.code
run() {
    mode=$1; port=$2
    "$PY" "$SRV" "$mode" "$port" >/dev/null 2>&1 &
    pid=$!
    # 等端口真的 LISTEN（不用 sleep 猜）
    i=0
    while [ $i -lt 50 ]; do
        if "$PY" -c "import socket,sys; s=socket.socket(); s.settimeout(0.2)
try:
    s.connect(('127.0.0.1', $port)); s.close(); sys.exit(0)
except Exception: sys.exit(1)" 2>/dev/null; then break; fi
        i=$((i+1)); sleep 0.1
    done
    set +e
    "$PY" "$ACC" --port "$port" --prompt x --max-tokens 16 \
        --out-dir "$TMP" --label "$mode" > "$TMP/$mode.log" 2>&1
    echo $? > "$TMP/$mode.code"
    set -e
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    grep -A1 '结论一句话' "$TMP/$mode.log" | tail -1 | sed 's/^ *//' > "$TMP/$mode.line"
}

# line_has <mode> <裸标签:有|无> <重复:有|无> <乱码:有|无>
line_has() {
    mode=$1; b=$2; d=$3; f=$4
    line=$(cat "$TMP/$mode.line")
    case "$line" in
        *"裸标签: $b"*"重复: $d"*"乱码: $f"*) return 0 ;;
        *) return 1 ;;
    esac
}

# ── good：健康流，三问必须全"无" ──
run good $((PORT_BASE+1))
ck "good 退出码=0（健康流不报问题）" "$([ "$(cat "$TMP/good.code")" = "0" ] && echo 1 || echo 0)"
ck "good 三问全无（裸标签/重复/乱码）" "$(line_has good 无 无 无 && echo 1 || echo 0)"

# ── framing：分 4 次 write 的干净流，也必须全"无"（钉"分块"不被误当重复） ──
run framing $((PORT_BASE+2))
ck "framing 分块框架不被误判为重复" "$(line_has framing 无 无 无 && echo 1 || echo 0)"

# ── dup：相邻帧完全相等，第 2 问必须报"有"（形状 A） ──
run dup $((PORT_BASE+3))
ck "dup 识别出服务端重发（相邻帧相等）" "$(line_has dup 无 有 无 && echo 1 || echo 0)"
grep -q '形状A' "$TMP/dup.log" \
    && ck "dup 报告里点明是形状A（服务端重发）" 1 \
    || ck "dup 报告里点明是形状A（服务端重发）" 0

# ── cumulative：每帧都是累计文本，第 2 问必须报"有"（形状 B） ──
run cumulative $((PORT_BASE+4))
ck "cumulative 识别出累计式拼接" "$(line_has cumulative 无 有 无 && echo 1 || echo 0)"
grep -q '形状B' "$TMP/cumulative.log" \
    && ck "cumulative 报告里点明是形状B（累计式）" 1 \
    || ck "cumulative 报告里点明是形状B（累计式）" 0

# ── baretag：字段值里有裸标签，第 1 问必须报"有" ──
run baretag $((PORT_BASE+5))
ck "baretag 识别出裸标签" "$(line_has baretag 有 无 无 && echo 1 || echo 0)"
grep -q '命中' "$TMP/baretag.log" \
    && ck "baretag 报告里给出命中位置" 1 \
    || ck "baretag 报告里给出命中位置" 0

# ── fffd：尾部发送被切碎的中文字，第 3 问必须报"有" ──
run fffd $((PORT_BASE+6))
ck "fffd 识别出非法 UTF-8 尾部" "$(line_has fffd 无 无 有 && echo 1 || echo 0)"
grep -q '不是合法 UTF-8' "$TMP/fffd.log" \
    && ck "fffd 报告里指到具体偏移" 1 \
    || ck "fffd 报告里指到具体偏移" 0

# ── splitutf8：整包合法但帧边界切进字符内部，必须报"可疑" ──
run splitutf8 $((PORT_BASE+7))
ck "splitutf8 报出帧边界切进字符内部（乱码:可疑）" \
    "$(line_has splitutf8 无 无 可疑 && echo 1 || echo 0)"
grep -q '帧边界落在多字节字符内部' "$TMP/splitutf8.log" \
    && ck "splitutf8 报告里点明成因（帧边界）" 1 \
    || ck "splitutf8 报告里点明成因（帧边界）" 0
ck "splitutf8 退出码=0（可疑不判红，只提示）" \
    "$([ "$(cat "$TMP/splitutf8.code")" = "0" ] && echo 1 || echo 0)"

# ── 自污染：prompt 里带字面标签必须**拒跑**（exit 2），不能出假报告 ──
# 这一组防的是"脚本自己造出待查的形状"：模板对 user 消息原样拼接，字面 <think>
# 会被模型当**真标签**读，于是报告里的形状是脚本造的，不是服务端的。
set +e
"$PY" "$ACC" --port 1 --prompt '请重复 <think>你好</think>' --print-curl \
    > "$TMP/poll.log" 2>&1
echo $? > "$TMP/poll.code"
set -e
ck "自污染 prompt 被拒跑（退出码=2）" \
    "$([ "$(cat "$TMP/poll.code")" = "2" ] && echo 1 || echo 0)"
grep -q '字面标签' "$TMP/poll.log" \
    && ck "自污染 prompt 给出可读的拒绝理由" 1 \
    || ck "自污染 prompt 给出可读的拒绝理由" 0

# 默认 prompt 必须**干净**（否则默认用法就在自污染）——这里只做静态断言，
# 不需要起服务端：把默认值取出来，断言其中不含任何裸标签。
"$PY" - "$ACC" <<'PYEOF' > "$TMP/default.txt" 2>&1
import importlib.util, sys, io
path = sys.argv[1]
src = io.open(path, encoding="utf-8").read()
ns = {}
# 只取 DEFAULT_PROMPT 与 BARE_TAGS 两个字面量，不执行整个模块（模块顶层无副作用，
# 但 exec 整份更省事：它只定义函数/常量，不会连服务端）
exec(compile(src, path, "exec"), ns)
d = ns["DEFAULT_PROMPT"]
hits = [t for t in ns["BARE_TAGS"] if t in d]
print("HITS=" + ",".join(hits))
print("PROMPT=" + d)
PYEOF
ck "默认 prompt 不含任何裸标签（默认用法不自污染）" \
    "$(grep -q '^HITS=$' "$TMP/default.txt" && echo 1 || echo 0)"

echo
echo "== sse 取证脚本自测：$ok 通过，$bad 失败 =="
[ "$bad" = "0" ] || exit 1
