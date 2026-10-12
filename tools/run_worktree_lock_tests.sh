#!/bin/sh
# 自测 `tools/with_worktree_lock.sh`：**这把锁真的互斥吗、真的可重入吗**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一份
# ═══════════════════════════════════════════════════════════════════════════
# `run_worktree_lock_guard.sh` 只判「**接线**在不在」（脚本有没有引入锁、有没有
# 在打桩前调用它）。它判不了「锁**本身**对不对」—— 一把"永远直接放行"的空壳锁，
# 接线守卫照样全绿。这一份就补那一件：对着**真并发**跑一遍，断言它真的串行化了。
#
# 两条必须挡住的反向：
#   ① **不互斥**：两段"进临界区 → 睡一会 → 出临界区"并发跑，临界区若有重叠 ->
#      这把锁是假的（打桩/还原窗口会互相盖，正是要防的那个 bug）；
#   ② **自锁死**：`run_assertion_anchor_guard.py` 会真跑别的套件，被驱动的套件
#      又各自抢锁 —— 若不可重入，一抢就死等自己。这类"挂住"与"还在跑"同形，
#      必须专门测。
#
# 判据用**真时钟序**（进/出的先后关系），不锚"文件里出现了 flock 字样"。
#
# 运行：sh tools/run_worktree_lock_tests.sh
set -e
cd "$(dirname "$0")/.."

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
# 测试用**独立锁文件**，避免与真跑着的其他套件抢同一把锁、污染判定。
export WORKTREE_LOCK_FILE="$TMP/lock"
export WORKTREE_LOCK_TIMEOUT=30
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT INT TERM HUP

# ── 判据①：并发进入临界区必须互斥 ────────────────────────────────────────
# 三段 worker 并发跑，每段：抢锁 → 往日志写 "IN <id>" → 睡 0.4s → 写 "OUT <id>"。
# 若互斥，日志里 IN/OUT 必然**成对相邻**；若有重叠，会出现 IN,IN,...（无锁）。
cat > "$TMP/worker.sh" <<'WEOF'
#!/bin/sh
. tools/with_worktree_lock.sh
id=$1
crit() { echo "IN $id" >> "$LOG"; sleep 0.4; echo "OUT $id" >> "$LOG"; }
with_worktree_lock "worker($id)" crit
WEOF
LOG="$TMP/log1"; export LOG
rm -f "$LOG"
sh "$TMP/worker.sh" 1 & sh "$TMP/worker.sh" 2 & sh "$TMP/worker.sh" 3 & wait

# 判定：读日志，若任何 "IN" 出现时前一条不是已配对的 OUT（即 IN 后面紧跟另一个 IN）
overlap=0
prev=""
while read -r kind id; do
    if [ "$kind" = "IN" ] && [ "$prev" = "IN" ]; then overlap=1; fi
    prev="$kind"
done < "$LOG"
ck "① 三段并发：临界区不重叠（真互斥）" "$([ "$overlap" = 0 ] && echo 1 || echo 0)"
[ "$overlap" = 0 ] || { echo "      —— 实测日志（暴露重叠）："; sed 's/^/      /' "$LOG"; }

# 且三段都跑到了（不是"只有一段跑、另两段被饿死"）
ck "① 三段并发：三段都执行了（没有饿死）" "$([ "$(grep -c '^OUT' "$LOG")" = 3 ] && echo 1 || echo 0)"

# ── 判据②：同一进程内嵌套抢锁**不自锁死**（可重入） ──────────────────────
cat > "$TMP/reentry.sh" <<'REOF'
#!/bin/sh
. tools/with_worktree_lock.sh
inner() { echo "INNER-OK" >> "$LOG2"; }
outer() { with_worktree_lock "reentry(inner)" inner; echo "OUTER-OK" >> "$LOG2"; }
with_worktree_lock "reentry(outer)" outer
REOF
LOG2="$TMP/log2"; export LOG2
rm -f "$LOG2"
# 用 timeout 兜底：真锁死时这里会挂住，必须"响亮地"失败而不是拖死整轮。
if timeout 20 sh "$TMP/reentry.sh"; then
    ck "② 同进程嵌套抢锁：不自锁死" "$([ "$(grep -c 'INNER-OK' "$LOG2")" = 1 ] && [ "$(grep -c 'OUTER-OK' "$LOG2")" = 1 ] && echo 1 || echo 0)"
else
    ck "② 同进程嵌套抢锁：不自锁死" 0
    echo "      —— 超时（20s）：疑似自锁死"
fi

# ── 判据③：**跨进程**嵌套（父持锁时子进程也抢锁）不自锁死 ─────────────────
# 这正是 run_assertion_anchor_guard.py 驱动别的套件时的形态：父守卫持锁，
# 子套件（另起进程）也调 acquire —— 必须判定为"已在锁内"而放行。
cat > "$TMP/child.sh" <<'CEOF'
#!/bin/sh
. tools/with_worktree_lock.sh
cw() { echo "CHILD-OK" >> "$LOG3"; }
with_worktree_lock "child" cw
CEOF
cat > "$TMP/parent.sh" <<'PEOF'
#!/bin/sh
. tools/with_worktree_lock.sh
pw() { sh "$TMP/child.sh"; }
with_worktree_lock "parent" pw
PEOF
LOG3="$TMP/log3"; export LOG3 TMP
rm -f "$LOG3"
if timeout 20 sh "$TMP/parent.sh"; then
    ck "③ 跨进程嵌套（父持锁、子再抢）：不自锁死" "$([ "$(grep -c 'CHILD-OK' "$LOG3")" = 1 ] && echo 1 || echo 0)"
else
    ck "③ 跨进程嵌套（父持锁、子再抢）：不自锁死" 0
    echo "      —— 超时（20s）：疑似自锁死"
fi

# ── 判据④：拿不到锁时**明确报错**，而不是静默继续 ────────────────────────
# 造一个"锁被占住 + 抢锁超时极短"的场景：占锁者睡 5s，抢锁者只等 1s。
cat > "$TMP/holder.sh" <<'HEOF'
#!/bin/sh
. tools/with_worktree_lock.sh
h() { sleep 5; }
with_worktree_lock "holder" h
HEOF
cat > "$TMP/waiter.sh" <<'WEOF2'
#!/bin/sh
. tools/with_worktree_lock.sh
w() { echo "SHOULD-NOT-RUN" >> "$LOG4"; }
with_worktree_lock "waiter" w
WEOF2
LOG4="$TMP/log4"; export LOG4
rm -f "$LOG4"
sh "$TMP/holder.sh" & holder=$!
sleep 0.5
WORKTREE_LOCK_TIMEOUT=1 sh "$TMP/waiter.sh" > "$TMP/waiter.out" 2>&1 && rc=0 || rc=$?
wait $holder 2>/dev/null || true
ck "④ 超时拿不到锁：非零退出" "$([ "$rc" != 0 ] && echo 1 || echo 0)"
ck "④ 超时拿不到锁：临界区没被错误进入" "$([ ! -f "$LOG4" ] && echo 1 || echo 0)"
ck "④ 超时拿不到锁：打印了明确原因（含 FAIL）" "$(grep -q 'FAIL' "$TMP/waiter.out" && echo 1 || echo 0)"

echo ""
if [ "$bad" = "0" ]; then echo "=== 工作区锁自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 工作区锁自测：PASS $ok / FAIL $bad ==="; exit 1
