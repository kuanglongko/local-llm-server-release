#!/bin/sh
# `scripts/prune-issue-assets.sh` 的**行为**自测：拿一个假平台跑真 HTTP。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么不能只做静态接线守卫
# ═══════════════════════════════════════════════════════════════════════════
# 这个脚本每天自动跑一次，而它干的是**不可逆**的事（删附件、没有撤销）。
# 静态守卫只能钉住"结构没改回去"，钉不住下面这几条 —— 它们的对错全在
# 一次具体调用的**参数与次序**里，而且错了都不抛异常：
#
#   · **边界那一天的归属**。`created_at == now - N天` 算不算到期？开闭
#     二选一都"看着对"，但它决定同一份附件今天该不该没。这里钉死"算"，
#     并用两侧各一条（早一秒 / 晚一秒）把它夹住 —— 只测"很久以前的那条
#     被删了"是钉不住开闭的（几十年 vs 1 秒，两种写法都过）。
#   · **不该删的一条都没碰**。类型不可删（release/commit）、时间戳缺失、
#     ID 缺失、还没到期 —— 这四类的"落选"各有各的理由，而**只看输出分不开**
#     "跳过了"与"试过了但它 422"。所以要读假平台**实际收到的 DELETE**。
#   · **dry-run 真的没删**。默认必须是只看；`--apply` 才是真删。
#     "默认真删"这个错在输出文本上**完全看不出来**（清单一样、退出码一样），
#     只有数请求才能发现。
#   · **失败不许被读成"没东西可清"**。403 / 连不上 / 422 三种，处置各不同：
#     前两种必须整体失败（不能写成"共 0 条"），第三种只让那一条失败、
#     其余照删、退出码非 0。
#   · **分页边界**。记录正好是 `page_size` 的整数倍时，最后一次要能停住
#     （靠"空页"停）而不是靠"不够一页" —— 前者漏一个 `if not got` 就无限翻。
#
# 全离线：假平台是本机 127.0.0.1 上的一个 python3 标准库 HTTP 服务，
# 不碰真平台、要不了任何 scope、也不会真删任何东西。依赖 python3（本仓库
# 处处依赖），不依赖编译器。
echo "=== 附件事务清理行为测试 ==="
set -e
cd "$(dirname "$0")/.."

SCRIPT=scripts/prune-issue-assets.sh
FAKE=tools/prune_assets/fake_api.py
[ -f "$SCRIPT" ] || { echo "缺 $SCRIPT"; exit 1; }
[ -f "$FAKE" ] || { echo "缺 $FAKE"; exit 1; }
command -v python3 >/dev/null || { echo "缺 python3"; exit 1; }

ok=0; bad=0
c() {
  if [ "$1" = "$2" ]; then
    echo "PASS  $3"; ok=$((ok + 1))
  else
    echo "FAIL  $3（期望 [$2]，实际 [$1]）"; bad=$((bad + 1))
  fi
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"; [ -n "$SRV" ] && kill "$SRV" 2>/dev/null' EXIT

# 起一个假平台，跑到测完。端口按场景递增，免得 TIME_WAIT 上撞。
PORT=18731
start_api() {
  SCEN="$1"; RECS="$2"
  PORT=$((PORT + 1))
  python3 "$FAKE" "$PORT" "$SCEN" "$RECS" >/dev/null 2>&1 &
  SRV=$!
  sleep 0.6
}
stop_api() { [ -n "$SRV" ] && kill "$SRV" 2>/dev/null; SRV=; sleep 0.2; }

run() {  # run <场景> <记录文件> [额外参数...]
  scen=$1; recs=$2; shift 2
  CNB_TOKEN=test-token CNB_REPO_SLUG=org/repo \
    CNB_API_HOST="http://127.0.0.1:$PORT" \
    sh "$SCRIPT" --now 2026-10-11T00:00:00Z "$@" 2>&1
}

deletes() { grep '^DELETE ' "$TMP/requests.log" 2>/dev/null | sed 's/^DELETE //' | sort | tr '\n' ' '; }
gets_n() { grep -c '^GET ' "$TMP/requests.log" 2>/dev/null || true; }

# ─────────────────────────────────────────────────────────────────────────
# ① 边界：闭区间 —— `now - 3天` 那一刻算到期，早一秒算、晚一秒不算
# ─────────────────────────────────────────────────────────────────────────
cat > "$TMP/b.json" <<'JSON'
[
 {"id":"early","record_type":"slug_file","created_at":"2026-10-07T23:59:59Z","size_in_byte":1,"path":"early"},
 {"id":"edge","record_type":"slug_file","created_at":"2026-10-08T00:00:00Z","size_in_byte":1,"path":"edge"},
 {"id":"late","record_type":"slug_file","created_at":"2026-10-08T00:00:01Z","size_in_byte":1,"path":"late"}
]
JSON
start_api ok "$TMP/b.json"
out=$(run ok "$TMP/b.json" --apply)
stop_api
c "$(deletes)" "early edge " "① 寿命线是闭区间：早一秒 + 恰好相等到期，晚一秒留下"

# ─────────────────────────────────────────────────────────────────────────
# ② 四类"不该删"的一条都不许碰
# ─────────────────────────────────────────────────────────────────────────
cat > "$TMP/skip.json" <<'JSON'
[
 {"id":"d1","record_type":"slug_file","created_at":"2026-01-01T00:00:00Z","size_in_byte":1,"path":"old"},
 {"id":"k1","record_type":"repo_release","created_at":"2026-01-01T00:00:00Z","size_in_byte":1,"path":"rel"},
 {"id":"k2","record_type":"repo_commit","created_at":"2026-01-01T00:00:00Z","size_in_byte":1,"path":"com"},
 {"id":"k3","record_type":"issue_file","created_at":"","size_in_byte":1,"path":"notime"},
 {"id":"k4","record_type":"issue_file","created_at":"不是时间","size_in_byte":1,"path":"badtime"},
 {"id":"","record_type":"issue_file","created_at":"2026-01-01T00:00:00Z","size_in_byte":1,"path":"noid"},
 {"id":"k5","record_type":"slug_img","created_at":"2026-10-10T23:00:00Z","size_in_byte":1,"path":"fresh"}
]
JSON
start_api ok "$TMP/skip.json"
out=$(run ok "$TMP/skip.json" --apply)
stop_api
c "$(deletes)" "d1 " "② 只删可删且到期的：release/commit/无时间戳/无 ID/未到期一律不碰"
c "$(echo "$out" | grep -c 'repo_release')" "0" "② 不可删的类型不进到期清单（连列都不列）"

# ─────────────────────────────────────────────────────────────────────────
# ③ dry-run 默认不删：清单照出、请求一条不发
# ─────────────────────────────────────────────────────────────────────────
start_api ok "$TMP/skip.json"
out=$(run ok "$TMP/skip.json")
stop_api
c "$(deletes)" "" "③ 默认只看：一条 DELETE 都不发"
c "$([ "$(echo "$out" | grep -c 'dry-run')" -ge 1 ] && echo yes)" "yes" "③ 输出里说清这是 dry-run"

# ─────────────────────────────────────────────────────────────────────────
# ④ 403（缺 scope）必须**整体失败**，不许写成"共 0 条 / 已清 0 条"
# ─────────────────────────────────────────────────────────────────────────
start_api forbid "$TMP/b.json"
set +e
out=$(run forbid "$TMP/b.json" --apply); rc=$?
set -e
stop_api
c "$rc" "1" "④ 403 退出码非 0（不然定时任务每天绿）"
c "$([ "$(echo "$out" | grep -c 'repo-manage:r')" -ge 1 ] && echo yes)" "yes" "④ 403 时说得出缺哪个 scope"

# ⑤ 连不上（网络层）同样整体失败 —— 这条与 403 分开测：
#    处置相同但**成因不同**，只测 403 会漏掉"HTTPError 之外那条路没接"
start_api refuse "$TMP/b.json"
set +e
out=$(run refuse "$TMP/b.json" --apply); rc=$?
set -e
stop_api
c "$rc" "1" "⑤ 连不上时退出码非 0，不许静默当成「没有附件」"
c "$(echo "$out" | grep -c 'Traceback')" "0" "⑤ 连不上时不留 Python traceback（错误得是人话）"

# ─────────────────────────────────────────────────────────────────────────
# ⑥ 单条删失败不中断整批，但退出码非 0
# ─────────────────────────────────────────────────────────────────────────
start_api del500 "$TMP/b.json"
set +e
out=$(run del500 "$TMP/b.json" --apply); rc=$?
set -e
stop_api
c "$rc" "1" "⑥ 有单条失败时退出码非 0"
c "$(echo "$out" | grep -c '失败')" "$(echo "$out" | grep -c '已删\|失败')" "⑥ 失败条数被逐个记账（不是一次 abort 就停）"
# b.json 里只有 early/edge 两条到期（late 在界内），这两条都要被试过 ——
# 若实现是"第一条失败就 abort"，这里只会剩 early 一条，判据立刻红。
c "$(deletes)" "early edge " "⑥ 单条失败不阻断其余：两条到期的都试过"

# ⑦ 422（不可删的类型）也是单条失败，不许升级成"整批都没有"
start_api del422 "$TMP/b.json"
set +e
run del422 "$TMP/b.json" --apply >/dev/null 2>&1; rc=$?
set -e
stop_api
c "$rc" "1" "⑦ 422 记成失败并反映在退出码上"

# ─────────────────────────────────────────────────────────────────────────
# ⑧ 分页：记录数正好是 page_size 整数倍时，要靠"空页"停住
#    （漏了 `if not got: break` 就会无限翻；`if len(got) < size: break`
#      这一种在这里恰好也能停，所以**条数要造得正好整除**才夹得住）
# ─────────────────────────────────────────────────────────────────────────
python3 - "$TMP/pg.json" <<'PY'
import json, sys
recs = [{"id": f"p{i}", "record_type": "slug_file",
         "created_at": "2026-01-01T00:00:00Z", "size_in_byte": 1,
         "path": f"p{i}"} for i in range(1, 7)]
json.dump(recs, open(sys.argv[1], 'w'))
PY
start_api ok "$TMP/pg.json"
out=$(PAGE_SIZE=3 run ok "$TMP/pg.json" --apply)
stop_api
c "$(deletes)" "p1 p2 p3 p4 p5 p6 " "⑧ 6 条 / 每页 3 条：三页全覆盖（分页真的在走）"

# ─────────────────────────────────────────────────────────────────────────
# ⑨ `--apply` 与"恰好没有到期的" ：不许因为清单空就改退出码
# ─────────────────────────────────────────────────────────────────────────
cat > "$TMP/none.json" <<'JSON'
[{"id":"n1","record_type":"slug_file","created_at":"2026-10-10T23:00:00Z","size_in_byte":1,"path":"new"}]
JSON
start_api ok "$TMP/none.json"
set +e
out=$(run ok "$TMP/none.json" --apply); rc=$?
set -e
stop_api
c "$rc" "0" "⑨ 没有到期的 = 正常收工（退出 0，不是失败）"
c "$(deletes)" "" "⑨ 没有到期的：一条不发"

echo
if [ "$bad" -eq 0 ]; then
  echo "=== 附件事务清理行为测试：PASS $ok / FAIL 0 ==="
else
  echo "=== 附件事务清理行为测试：$ok 通过 / $bad 失败 ==="
  exit 1
fi
