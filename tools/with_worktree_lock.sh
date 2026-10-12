#!/bin/sh
# 独占锁：让「就地打桩真源码 + trap 还原」类自测**串行**跑。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么要有这把锁
# ═══════════════════════════════════════════════════════════════════════════
# 仓库里有几十份 `*_tests.sh`，做法都是同一条：
#
#     备份真源码 → 就地改一处锚点（打桩）→ 断言守卫该红 → 还原
#
# 它们动的是**同一份工作树**。一旦两条自测并行跑：
#   · A 打断了 HttpApi.kt，B 也在动 HttpApi.kt → B 可能把 A 的**桩当基线**读走；
#   · 一条的 `trap restore` 还原窗口，会**盖掉**另一条刚打的桩。
#
# 这不是猜测：`run_assertion_anchor_guard.py` 里已实测记录过「并发跑一轮就在
# llama_jni.cpp 里留下一处桩」，所以它把 ledger 那一步写死成串行。
#
# 后果是「假红 / 假绿」：
#   · 假红 —— 某份自测误报，你以为自己改坏了（吵人，可接受）；
#   · 假绿 —— 静默漏桩，一条断言白写而 CI 照绿（真隐患，正是本仓库一贯在防的
#     「静默失效」，见 #174 的 assertion_anchor_guard）。
#
# ⚠ **CI 里天然不会并发**：`.cnb.yml` 是单个 script、`set -e` 一行行串行跑。
# 这把锁护的是「开发机上人工并发跑」这种非标准用法 —— 拿不到锁就**排队等**，
# 于是无论怎么并发调用，打桩/还原窗口都**天然互斥**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 用法
# ═══════════════════════════════════════════════════════════════════════════
# 在**动源码之前**把整段抢锁 + 执行包起来。最省事的接法是把脚本既有内容
# 整体挪进一个函数 `main`，再用本文件驱动：
#
#     . "$(dirname "$0")/with_worktree_lock.sh"
#     main() {
#         ...原有逻辑，含打桩与 trap restore...
#     }
#     with_worktree_lock "$0" main
#
# —— 这样 `trap restore EXIT INT TERM HUP` 与还原都发生在**锁内**，
#    还原完成才释放锁。
#
# ═══════════════════════════════════════════════════════════════════════════
# 实现要点
# ═══════════════════════════════════════════════════════════════════════════
# · **在同一个 shell 进程里执行**：命令可能是**函数**（上面那种接法），
#   子进程 `sh -c` 里根本没有它 —— 所以必须用 **fd 上的 flock**（`fcntl`
#   锁随进程/fd 存续），在**当前进程内**跑命令，而不是 fork 一个 sh 去跑。
# · **可重入（必须）**：`run_assertion_anchor_guard.py` 会真跑别的套件，那些
#   套件又各自抢锁 —— 若不可重入就是自锁死。这里用「持锁进程的祖先链」判定：
#   若当前进程的某个祖先是锁持有者，则视为已持锁，直接放行，不重复抢。
# · **队列等待**：后到者阻塞等待，天然先到先得，不报错、不放弃。
# · **超时兜底**：默认 1800s，可用 `WORKTREE_LOCK_TIMEOUT=<秒>` 覆盖，0 = 无限等。
#   超时**明确报错**（而不是无限期挂住 —— "挂住"与"还在跑"在 CI 里同形）。

# 锁文件名固定：所有就地打桩自测共用同一把锁，跨脚本互斥。
WORKTREE_LOCK_FILE=${WORKTREE_LOCK_FILE:-/tmp/local-llm-server.worktree.lock}
WORKTREE_LOCK_TIMEOUT=${WORKTREE_LOCK_TIMEOUT:-1800}

# 本进程为了持锁而打开的 fd 号（flock 用）。避开 0/1/2 与常见重定向。
_WWL_FD=9

# 当前进程链里有没有 pid 为 $1 的祖先。用于可重入判定。
_with_worktree_lock__ancestor_is() {
    _want=$1
    _p=$$
    while [ -n "$_p" ] && [ "$_p" != "0" ] && [ "$_p" != "1" ]; do
        [ "$_p" = "$_want" ] && return 0
        _p=$(awk '{print $4}' "/proc/$_p/stat" 2>/dev/null) || return 1
        [ -n "$_p" ] || return 1
    done
    return 1
}

# 内联抢锁：只抢、**不显式放** —— 锁随本进程退出（fd 关闭）自动释放。
# 适合不想把整段逻辑搬进函数的脚本：在 `trap restore ...` 之前调一次，
# 之后所有打桩/还原都在锁内，且退出时 `trap` 先跑、fd 后关，还原必在锁内。
# 拿不到锁（超时）时**明确报错并 exit 1**。
worktree_lock_acquire() {
    _wwl_caller=${1:-$0}
    _wwl_holder=$(cat "$WORKTREE_LOCK_FILE.holder" 2>/dev/null || true)
    if [ -n "$_wwl_holder" ] && _with_worktree_lock__ancestor_is "$_wwl_holder"; then
        return 0   # 可重入：已在锁内
    fi
    if command -v flock >/dev/null 2>&1; then
        eval "exec $_WWL_FD>\"\$WORKTREE_LOCK_FILE\""
        if [ "${WORKTREE_LOCK_TIMEOUT}" = "0" ]; then
            flock -x "$_WWL_FD" || { echo "FAIL  [$WORKTREE_LOCK_FILE 上的工作区锁] 抢锁失败" >&2; exit 1; }
        else
            flock -x -w "$WORKTREE_LOCK_TIMEOUT" "$_WWL_FD" || {
                echo "FAIL  [$WORKTREE_LOCK_FILE 上的工作区锁] 等待超过 ${WORKTREE_LOCK_TIMEOUT}s 仍未拿到" >&2
                echo "      调用方：$_wwl_caller" >&2
                echo "      有别的就地打桩自测卡住了；查占用者，或调大 WORKTREE_LOCK_TIMEOUT" >&2
                exit 1; }
        fi
        echo $$ > "$WORKTREE_LOCK_FILE.holder"
        # 退出时抹掉 holder 文件（fd 关闭即释放锁，与这里无关，仅清理痕迹）。
        trap 'rm -f "$WORKTREE_LOCK_FILE.holder"' EXIT
        return 0
    fi
    # 退化路径：mkdir 自旋锁，靠退出时 trap 清理。
    _wwl_dir="$WORKTREE_LOCK_FILE.d"
    _wwl_deadline=$(( $(date +%s) + ${WORKTREE_LOCK_TIMEOUT} ))
    while ! mkdir "$_wwl_dir" 2>/dev/null; do
        if [ "${WORKTREE_LOCK_TIMEOUT}" != "0" ] && [ "$(date +%s)" -ge "$_wwl_deadline" ]; then
            echo "FAIL  [$WORKTREE_LOCK_FILE 上的工作区锁] 等待超过 ${WORKTREE_LOCK_TIMEOUT}s 仍未拿到" >&2
            echo "      调用方：$_wwl_caller" >&2
            exit 1
        fi
        sleep 1
    done
    echo $$ > "$_wwl_dir/holder"
    trap 'rm -rf "$_wwl_dir"' EXIT
    return 0
}

# 抢锁并执行命令。第一个参数是**调用方标识**（用于报错），其余是命令（可为函数）。
with_worktree_lock() {
    _wwl_caller=$1; shift

    # 可重入：持锁进程是不是我们的祖先？是则已在锁内，直接跑。
    _wwl_holder=$(cat "$WORKTREE_LOCK_FILE.holder" 2>/dev/null || true)
    if [ -n "$_wwl_holder" ] && _with_worktree_lock__ancestor_is "$_wwl_holder"; then
        "$@"
        return $?
    fi

    if command -v flock >/dev/null 2>&1; then
        # fd 上的 flock(fcntl 锁)：随本进程存续，命令在本进程内跑 —— 函数可用。
        eval "exec $_WWL_FD>\"\$WORKTREE_LOCK_FILE\""
        if [ "${WORKTREE_LOCK_TIMEOUT}" = "0" ]; then
            flock -x "$_WWL_FD" || { eval "exec $_WWL_FD>&-"
                echo "FAIL  [$WORKTREE_LOCK_FILE 上的工作区锁] 抢锁失败（无超时模式）" >&2
                echo "      调用方：$_wwl_caller" >&2
                return 1; }
        else
            flock -x -w "$WORKTREE_LOCK_TIMEOUT" "$_WWL_FD" || { eval "exec $_WWL_FD>&-"
                echo "FAIL  [$WORKTREE_LOCK_FILE 上的工作区锁] 等待超过 ${WORKTREE_LOCK_TIMEOUT}s 仍未拿到" >&2
                echo "      调用方：$_wwl_caller" >&2
                echo "      有别的就地打桩自测卡住了；查占用者，或调大 WORKTREE_LOCK_TIMEOUT" >&2
                return 1; }
        fi
        # 记下持有者进程号，供重入判定。
        echo $$ > "$WORKTREE_LOCK_FILE.holder"
        "$@"
        _rc=$?
        rm -f "$WORKTREE_LOCK_FILE.holder"
        eval "exec $_WWL_FD>&-"
        return $_rc
    fi

    # 退化路径：mkdir 自旋锁（flock(1) 缺席的极瘦容器）。
    _wwl_dir="$WORKTREE_LOCK_FILE.d"
    _wwl_deadline=$(( $(date +%s) + ${WORKTREE_LOCK_TIMEOUT} ))
    while ! mkdir "$_wwl_dir" 2>/dev/null; do
        if [ "${WORKTREE_LOCK_TIMEOUT}" != "0" ] && [ "$(date +%s)" -ge "$_wwl_deadline" ]; then
            echo "FAIL  [$WORKTREE_LOCK_FILE 上的工作区锁] 等待超过 ${WORKTREE_LOCK_TIMEOUT}s 仍未拿到" >&2
            echo "      调用方：$_wwl_caller" >&2
            return 1
        fi
        sleep 1
    done
    echo $$ > "$_wwl_dir/holder"
    "$@"
    _rc=$?
    rm -rf "$_wwl_dir"
    return $_rc
}
