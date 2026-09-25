#!/bin/sh
# 取消验收脚本（tools/acceptance_cancel.py）的**自测**：拿四份打桩实现跑它，
# 断言「该绿的绿、该红的红」。
#
# 为什么必须在 CI 里跑：验收脚本本身要装机才能跑，而它的判据全是**否定式**的
# （"客户端断开后输出没跑满"、"`/v1/abort` 之后提前收尾"）—— 恒真也全绿。
# 一个完全不实现取消的服务端，只要模型短答，脚本一样全绿。只对着好实现跑
# 一万次都发现不了这一点，只能对着坏实现跑。
#
# 五份桩与各自主钉的失效模式：
#   good     —— 语义正确（对照 RequestCancel + HttpApi）        => 必须全绿
#   noabort  —— /v1/abort 回 200 但什么都不停（取消的"假成功"） => 必须有 FAIL
#   nogone   —— 探测整个没接上、永远当活着（漏掉"写失败"这条路）  => disconnect-* 必红（用 --discover-wait 缩短轮询）
#   naked    —— 探测**裸写 0x00**（不按 chunked 分帧）           => stream-integrity 必红
#   shortans —— 对照组不成立（模型短答）                         => 不许报绿，WARN>0
#
# 纯 python3 标准库，不依赖任何工具链。
set -e
cd "$(dirname "$0")/.."

PY=python3
command -v $PY >/dev/null 2>&1 || { echo "缺少 python3"; exit 2; }

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
trap 'kill $(cat "$TMP"/pid* 2>/dev/null) 2>/dev/null; rm -rf "$TMP"' EXIT

# 起一个桩，返回端口（在 $1 里）到 $TMP/port_<mode>
start_mock() {
    mode="$1"; port="$2"; logfile="$3"
    : > "$logfile"
    $PY tools/cancel_acceptance/fake_cancel_server.py "$mode" "$port" "$logfile" \
        > "$TMP/out_$mode" 2>&1 &
    echo $! > "$TMP/pid_$mode"
    # 等端口就绪（最多 5s），别用固定 sleep：CI 机器快慢不定，会变成 flaky
    i=0
    while [ $i -lt 50 ]; do
        if $PY - "$port" <<'PYEOF' >/dev/null 2>&1
import socket, sys
s = socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=0.2); s.close()
PYEOF
        then return 0; fi
        i=$((i + 1)); sleep 0.1
    done
    echo "mock($mode) 起不来"; return 1
}

run_accept() {  # $1=port $2=log 之后是额外参数（原样透传，不加引号以免词分割）
    port="$1"; logf="$2"; shift 2
    $PY tools/acceptance_cancel.py --host 127.0.0.1 --port "$port" --log "$logf" \
        --timeout 30 --min-tok 32 "$@" 2>&1
}

P1=19181; P2=19182; P3=19183; P4=19184; P5=19185

# ── 1) good：必须全绿 ─────────────────────────────────────────────────────
start_mock good $P1 "$TMP/good.log"
set +e
OUT=$(run_accept $P1 "$TMP/good.log"); RC=$?
set -e
echo "$OUT" | tail -40
# 判据不能拿 "FAIL" 这个字去 grep：脚本自己的汇总行
# 「== 取消验收：PASS 25 / FAIL 0 / WARN 0 ==」永远含 FAIL 字样，
# 那样写出来的断言恒为假（第一版就踩了）。改为解析汇总行的三个数字。
summary() { echo "$1" | sed -n 's/.*PASS \([0-9]*\) \/ FAIL \([0-9]*\) \/ WARN \([0-9]*\).*/\1 \2 \3/p' | tail -1; }
c "good：全部用例通过（exit 0）" "[ $RC -eq 0 ]"
c "good：汇总行 FAIL=0" "[ \"\$(summary \"\$OUT\" | awk '{print \$2}')\" = 0 ]"
c "good：汇总行 PASS>0（不是空跑）" "[ \"\$(summary \"\$OUT\" | awk '{print \$1}')\" -gt 0 ]"
# 两种断开方式都必须被发现。**不**断言"FIN 一定走读 EOF 那条、RST 一定走写
# 异常那条"—— 实测 TCP 上这一点并不稳定（服务端收缓冲里还有未读完的请求体时，
# 对端 FIN 之后的写同样会 RST）。判据只要求"两种离开方式都被发现"。
c "good：正常关闭（FIN）被发现" "echo \"\$OUT\" | grep -q '\[FIN\] 服务端日志发现客户端断开'"
c "good：连接被重置（RST）被发现" "echo \"\$OUT\" | grep -q '\[RST\] 服务端日志发现客户端断开'"
c "good：/v1/abort 停掉了进行中的请求" "echo \"\$OUT\" | grep -q 'aborted=true'"
c "good：/health 报了 generating 字段" "echo \"\$OUT\" | grep -q 'generating 字段'"
c "good：流式分帧未被污染（客户端完整解析 chunked）" "echo \"\$OUT\" | grep -q '客户端能完整解析 chunked 分帧'"
c "good：stream-integrity 用例跑了且不是 FAIL" "echo \"\$OUT\" | grep -q '客户端能完整解析 chunked 分帧' && ! echo \"\$OUT\" | grep -q 'FAIL.*chunked'"

# ── 2) noabort：/v1/abort 假成功，必须有 FAIL ────────────────────────────
start_mock noabort $P2 "$TMP/noabort.log"
set +e
OUT=$(run_accept $P2 "$TMP/noabort.log" --only abort-idle,abort-stream); RC=$?
set -e
echo "$OUT" | tail -25
c "noabort：abort 假成功被抓住（exit 1）" "[ $RC -ne 0 ]"
c "noabort：汇总行 FAIL>0" "[ \"\$(summary \"\$OUT\" | awk '{print \$2}')\" -gt 0 ]"
c "noabort：抓住的是 abort 那两条判据（不是别的用例误伤）" \
  "echo \"\$OUT\" | grep -q 'aborted=true.*FAIL\|FAIL.*aborted=true\|报 aborted=true'"

# ── 3) nogone：探测只写不判、永远当活着（漏掉"写失败"这条路）─────────────
start_mock nogone $P3 "$TMP/nogone.log"
set +e
OUT=$(run_accept $P3 "$TMP/nogone.log" --only disconnect-fin,disconnect-rst --discover-wait 6); RC=$?
set -e
echo "$OUT" | tail -25
c "nogone：至少一组断开判据变红（探测整个没接上、永远当活着）" "echo \"\$OUT\" | grep -q 'FAIL'"
# 诚实说明：nogone 桩钉的是"漏掉写失败这条路"。实测 TCP 上 FIN 与 RST 并**不能**
# 从服务端可靠区分（收缓冲有残留数据时，FIN 之后的写同样会 RST），所以不断言
# "只有 FIN 变红"——那是个 OS 层面不成立的判据，会把正确的实现判成假的。
c "nogone：整体 exit 1（漏掉写失败这条路必须被抓）" "[ $RC -ne 0 ]"

# ── 3b) naked：探测裸写 0x00、污染 chunked 分帧（2026-09-20 真机事故）─────
# 桩把非分帧字节写进 Transfer-Encoding: chunked 的流里，客户端解析"下一个 chunk
# 的长度行"会读到 0x0 -> `Expected leading [0-9a-fA-F] character but was 0x0`。
# 这条用例（stream-integrity）就是为它设的：必须变红。
start_mock naked $P5 "$TMP/naked.log"
set +e
OUT=$(run_accept $P5 "$TMP/naked.log" --only stream-integrity); RC=$?
set -e
echo "$OUT" | tail -25
c "naked：裸写探测字节污染分帧被抓住（stream-integrity 变红）" \
  "echo \"\$OUT\" | grep -q 'FAIL.*chunked\|FAIL.*分帧'"
c "naked：整体 exit 1" "[ $RC -ne 0 ]"

# ── 4) shortans：对照组不成立 => 降级 WARN，不许报绿 ─────────────────────
start_mock shortans $P4 "$TMP/shortans.log"
set +e
OUT=$(run_accept $P4 "$TMP/shortans.log" --only abort-stream); RC=$?
set -e
echo "$OUT" | tail -25
# shortans 的桩让对照组不成立（模型只吐 3 个 token）。此时
# 「取消后没跑满」这条判据**恒真** —— 脚本必须把它降级成 WARN，
# 且**不许有任何一条取消判据报绿**（报绿就等于用一个无意义的前提证明了结论）。
c "shortans：对照组不成立时给出 WARN" "echo \"\$OUT\" | grep -q 'WARN'"
# （彩色输出的 WARN 后面跟的是全角冒号，所以只匹配 WARN 之后的"对照"二字）
c "shortans：WARN 落在对照组那条上（说明前提确实被识别为不成立）" \
  "echo \"\$OUT\" | grep -q 'WARN.*对照'"
c "shortans：不允许把「取消生效」判成 PASS（这是恒真判据）" \
  "! echo \"\$OUT\" | grep -qE 'PASS.*(取消时实际生成|aborted=true)'"
c "shortans：整体 exit != 0（WARN 不得当成功）" "[ $RC -ne 0 ]"

# ── 5) 脚本自身的静态约束 ────────────────────────────────────────────────
c "验收脚本对每种断开方式都有独立用例（FIN 与 RST 对偶）" \
  "grep -q 'def case_disconnect_fin' tools/acceptance_cancel.py && grep -q 'def case_disconnect_rst' tools/acceptance_cancel.py"
c "验收脚本要求 --log（取消在客户端一侧不可见）" \
  "grep -q '取消在客户端一侧完全不可见' tools/acceptance_cancel.py"
c "打桩实现与验收脚本不共用判据代码（否则一起错、测试全绿）" \
  "! grep -q 'from tools.acceptance_cancel' tools/cancel_acceptance/fake_cancel_server.py"

echo
echo "=== 取消验收脚本自测：$ok 通过 / $bad 失败 ==="
[ "$bad" -eq 0 ] || exit 1
