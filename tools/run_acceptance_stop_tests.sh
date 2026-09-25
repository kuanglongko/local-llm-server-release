#!/bin/sh
# 自测 `tools/acceptance_stop.py`：对着四份**打桩实现**跑，断言"该绿的绿、该红的红"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条测试防的是"验收脚本自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# acceptance_stop.py 必须装机才能跑（要真模型、真采样器），所以它的正确性在 CI 上
# 无法靠"跑一遍看绿"来保证。而它的判据全是**否定式**的（"客户端没看到 stop 串"），
# 这带来一个致命的自欺：判据恒真也全绿。最典型的两种：
#
#   1. 服务端完全不认 stop —— 只要模型的回答恰好把记号写在文末，"记号没出现"
#      这条判据就成立（真机上这非常常见：提示词就是让人在末尾写记号）；
#   2. 判据漏查"尾部被切掉了" —— 同上，等于没测。
#
# 与之对偶的第三类自欺是**假红**：判据把正确实现判成坏的，于是"红"也失去信息量。
# 真机上最容易触发它的是**模型把提示词抄进输出**，使 stop 串在输出里出现两次
# （用户日志里 `@@END@@`（7 字）-> `@@@@@ENDEND`（10 字）的形状）。此时服务端
# 的正确行为是"停在**最靠前**那个命中点"，而脚本若用 `ref.find(mark)` / `rfind`
# 定位，就会把正确输出判成"多吐了字"。所以第 4 组用 `echoraw` 桩钉住这条：
# 它语义**正确**，只是输出形状是"回显式"的，必须全绿。
#
# 本 PR 的第一版判据就踩了第 1 条（对流式命中只查"记号没出现"），是**对着
# nosampler 桩跑出来的**，装机跑多少次都发现不了。所以这个自测不是走过场：
# 它同时钉住"脚本能识别坏实现"与"脚本不误伤好实现"。
#
# 不需要 NDK / 设备 / 模型 / 工具链，只要 python3。CI 里可直接跑。
#
# 运行：sh tools/run_acceptance_stop_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
SRV=tools/stop_acceptance/fake_stop_server.py
ACC=tools/acceptance_stop.py
PORT_BASE=${PORT_BASE:-18500}

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() {
    if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
    else echo "FAIL  $1"; bad=$((bad+1)); fi
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"; kill $SRV_PID 2>/dev/null || true' EXIT

# 起一个桩服务端，回显它的模式与端口，等待端口真的 LISTEN（不用 sleep 猜）
start_server() {
    mode=$1; port=$2
    "$PY" "$SRV" "$mode" "$port" > "$TMP/$mode.log" 2>&1 &
    SRV_PID=$!
    i=0
    while [ $i -lt 60 ]; do
        if "$PY" - "$port" <<'EOF' 2>/dev/null
import socket, sys
s = socket.socket(); s.settimeout(0.5)
try:
    s.connect(("127.0.0.1", int(sys.argv[1]))); sys.exit(0)
except Exception:
    sys.exit(1)
EOF
        then return 0; fi
        i=$((i+1)); sleep 0.2
    done
    echo "桩服务端 $mode 没起来（端口 $port）"; cat "$TMP/$mode.log"; return 1
}
stop_server() { kill $SRV_PID 2>/dev/null || true; wait $SRV_PID 2>/dev/null || true; }

run_acc() { # mode port -> 打印脚本输出，返回脚本退出码
    "$PY" "$ACC" --host 127.0.0.1 --port "$2" --tries 2 --log "$TMP/$1.log"
}

# ── 1. good：语义正确 => 必须 exit 0 且 0 FAIL ──────────────────────────────
start_server good $((PORT_BASE+1))
set +e; run_acc good $((PORT_BASE+1)) > "$TMP/good.out" 2>&1; rc=$?; set -e
stop_server
# 注意验收脚本会带 ANSI 颜色输出，统计前先剥掉，否则 grep '^PASS' 一条都数不到。
"$PY" - "$TMP/good.out" "$TMP/good.clean" <<'EOF'
import re, sys
t = open(sys.argv[1], encoding='utf-8', errors='replace').read()
open(sys.argv[2], 'w', encoding='utf-8').write(re.sub(r'\x1b\[[0-9;]*m', '', t))
EOF
npass=$(grep -c '^PASS' "$TMP/good.clean" || true)
nfail=$(grep -c '^FAIL' "$TMP/good.clean" || true)
dets=$(grep -oE 'PASS [0-9]+ / FAIL [0-9]+ / WARN [0-9]+' "$TMP/good.clean" | tail -1)
ck "good 实现：脚本 exit 0（实际 $rc，$dets）" "$([ "$rc" = "0" ] && echo 1 || echo 0)"
ck "good 实现：0 条 FAIL（实际 $nfail）" "$([ "$nfail" = "0" ] && echo 1 || echo 0)"
# 判据数量：脚本被判据"缩水"（改动时误删了一组）也要报出来
ck "good 实现：至少 30 条判据都过了（实际 $npass）" "$([ "$npass" -ge 30 ] && echo 1 || echo 0)"
if [ "$bad" != "0" ] || [ "$nfail" != "0" ]; then sed -n '1,200p' "$TMP/good.clean"; fi

# ── 2. nosampler：完全不认 stop（本 PR 修的 bug）=> 必须有 FAIL 且 exit 1 ──
start_server nosampler $((PORT_BASE+2))
set +e; run_acc nosampler $((PORT_BASE+2)) > "$TMP/nos.out" 2>&1; rc=$?; set -e
stop_server
ck "nosampler 实现（静默忽略 stop）：脚本 exit 1（实际 $rc）" "$([ "$rc" = "1" ] && echo 1 || echo 0)"
# 重点：流式与尾部命中两组必须能抓住它 —— 第一版判据恰恰放过了这两条
for key in "流式命中：输出被切在记号起点" "长文本尾部命中：输出是基准在记号起点处的真前缀" \
           "跨 token 命中：输出被切在记号起点" "已挂载 stop 采样器" "stop 串不落在结果里"; do
    if grep -qF "$key" "$TMP/nos.out"; then
        # 该判据那一行必须以 FAIL 开头
        line=$(grep -F "$key" "$TMP/nos.out" | head -1)
        case "$line" in *FAIL*) ck "nosampler 实现：抓住「$key」" 1 ;;
                         *)     ck "nosampler 实现：抓住「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "nosampler 实现：抓住「$key」（脚本里没这条判据）" 0
    fi
done

# ── 3. swallow：暂扣后永不放行（吞正文）=> 必须有 FAIL ─────────────────────
start_server swallow $((PORT_BASE+3))
set +e; run_acc swallow $((PORT_BASE+3)) > "$TMP/sw.out" 2>&1; rc=$?; set -e
stop_server
ck "swallow 实现（暂扣不放行、吞正文）：脚本 exit 1（实际 $rc）" "$([ "$rc" = "1" ] && echo 1 || echo 0)"
line=$(grep -F "不吞正文" "$TMP/sw.out" | head -1)
case "$line" in *FAIL*) ck "swallow 实现：抓住「不吞正文」被吞字" 1 ;;
                 *)     ck "swallow 实现：抓住「不吞正文」被吞字（实际：$line）" 0 ;; esac

# ── 4. echoraw：语义正确，但输出会把提示词抄一遍（stop 串出现两次）───────
# 这一组防的是**假红**：服务端在每个命中点都切对了，只是"命中点"不是
# `ref.find(mark)` 算出来的那个。判据必须全绿，否则脚本会把好实现判成坏的。
start_server echoraw $((PORT_BASE+4))
set +e; run_acc echoraw $((PORT_BASE+4)) > "$TMP/echo.out" 2>&1; rc=$?; set -e
stop_server
"$PY" - "$TMP/echo.out" "$TMP/echo.clean" <<'EOF'
import re, sys
t = open(sys.argv[1], encoding='utf-8', errors='replace').read()
open(sys.argv[2], 'w', encoding='utf-8').write(re.sub(r'\x1b\[[0-9;]*m', '', t))
EOF
necho_fail=$(grep -c '^FAIL' "$TMP/echo.clean" || true)
ck "echoraw 实现（提示词回显、stop 串出现两次）：脚本 exit 0（实际 $rc）" "$([ "$rc" = "0" ] && echo 1 || echo 0)"
ck "echoraw 实现：0 条 FAIL（实际 $necho_fail）" "$([ "$necho_fail" = "0" ] && echo 1 || echo 0)"
# 这三条曾经就是被 find() 误判成 FAIL 的判据，必须点名确认它们是 PASS
for key in "流式命中：输出被切在记号起点" "跨 token 命中：输出被切在记号起点" \
           "长文本尾部命中：输出是基准在记号起点处的真前缀"; do
    if grep -qF "$key" "$TMP/echo.clean"; then
        line=$(grep -F "$key" "$TMP/echo.clean" | head -1)
        case "$line" in *PASS*) ck "echoraw 实现：命中点判据不误伤「$key」" 1 ;;
                         *)     ck "echoraw 实现：命中点判据不误伤「$key」（实际：$line）" 0 ;; esac
    else
        ck "echoraw 实现：命中点判据不误伤「$key」（脚本里没这条判据）" 0
    fi
done
if [ "$bad" != "0" ] || [ "$necho_fail" != "0" ]; then sed -n '1,200p' "$TMP/echo.clean"; fi

# ── 5. --need-never：要求模型在文末写记号，覆盖「命中 + 放行」两条路 ───────
start_server good $((PORT_BASE+5))
set +e; "$PY" "$ACC" --host 127.0.0.1 --port $((PORT_BASE+5)) --tries 2 \
      --need-never --log "$TMP/good.log" > "$TMP/need.out" 2>&1; rc=$?; set -e
stop_server
"$PY" - "$TMP/need.out" "$TMP/need.clean" <<'EOF'
import re, sys
t = open(sys.argv[1], encoding='utf-8', errors='replace').read()
open(sys.argv[2], 'w', encoding='utf-8').write(re.sub(r'\x1b\[[0-9;]*m', '', t))
EOF
nneed_fail=$(grep -c '^FAIL' "$TMP/need.clean" || true)
ck "--need-never：脚本 exit 0（实际 $rc）" "$([ "$rc" = "0" ] && echo 1 || echo 0)"
ck "--need-never：0 条 FAIL（实际 $nneed_fail）" "$([ "$nneed_fail" = "0" ] && echo 1 || echo 0)"
if [ "$nneed_fail" != "0" ]; then sed -n '1,200p' "$TMP/need.clean"; fi

# ── 6. 连不上时必须退化为环境问题，而不是"代码坏了" ───────────────────────
set +e
"$PY" "$ACC" --host 127.0.0.1 --port $((PORT_BASE+9)) > "$TMP/down.out" 2>&1; rc=$?
set -e
ck "目标连不上：exit 2（环境问题，不是断言失败）" "$([ "$rc" = "2" ] && echo 1 || echo 0)"
set +e
"$PY" "$ACC" --host 127.0.0.1 --port $((PORT_BASE+9)) --allow-skip > "$TMP/down2.out" 2>&1; rc=$?
set -e
ck "目标连不上 + --allow-skip：exit 0（CI 可安全跳过）" "$([ "$rc" = "0" ] && echo 1 || echo 0)"

echo
echo "== run_acceptance_stop_tests: PASS $ok / FAIL $bad =="
[ "$bad" = "0" ] || exit 1
