#!/bin/sh
# 自测 `tools/run_resource_lifecycle_guard.sh`：拿**就地改坏的真源码**跑它，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# 桩全是真会写出来的坏法（"只涨不跌"且与正确顺序同形）：
#   ① 摘除从 finally 挪到 try 末尾 -> 异常路径漏摘 -> 僵尸轮次
#   ② HTTP 连接线程漏标 daemon -> Service 停后进程不退
#   ③ ioExecutor 不 shutdown -> 每次 Activity 重建各留一个线程 + 捕获 this
#   ④ shutdown 排到 super.onDestroy() 之后 -> 与已拆视图抢资源
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明
PY=${PYTHON:-python3}
GUARD=tools/run_resource_lifecycle_guard.sh
HA=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HA" "$TMP/ha.bak"; cp "$EA" "$TMP/ea.bak"
restore() { cp "$TMP/ha.bak" "$HA"; cp "$TMP/ea.bak" "$EA"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ha.bak" "$HA"; cp "$TMP/ea.bak" "$EA"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/out" 2>&1; rc=$?; set -e; return $rc; }
must_red() {
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/out"; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」" 0; fi
}
patch() {
    "$PY" - "$1" <<PYEOF
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
$2
open(p, 'w', encoding='utf-8').write(s)
PYEOF
}

# ── 0. 基线 ────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了）" 0; sed -n '1,30p' "$TMP/out"; fi

# ── 1. 桩①：摘除从 finally 挪到 try 末尾（异常路径漏摘）──────────────────
# 直接把 `endCancelable(cancel)` 从 finally 里删掉一处（模拟"漏摘"）。
# 真实漏摘的写法是**把摘除从 finally 挪到 try 的正常路径末尾**（不是注释掉）——
# 注释掉会连 `endCancelable` 这个名字一起消失，存在性判据反而"看不见"。
# 这里把 finally 里那两行**整行删掉**，模拟"只在正常路径摘、异常路径漏"。
patch "$HA" '
old = """                } finally {
                    // 摘除取消归属。**必须在 emitLog 之前**：之后任何 /v1/abort 都应看到
                    // 「没有轮次在跑」，而不是打在一个已收尾的对象上。
                    LlmEngine.endCancelable(cancel)
                }"""
new = """                } finally {
                    // 桩：摘除被挪走（异常路径漏摘）
                }"""
assert s.count(old) == 1, "桩①：没找到 finally 里的摘除块"
s = s.replace(old, new, 1)'
must_red "桩①（摘除离开 finally -> 异常路径漏摘）" "都在 finally 块内"
reset_src

# ── 2. 桩②：HTTP 连接线程漏标 daemon ───────────────────────────────────────
patch "$HA" '
old = "}, \"http-conn\").apply { isDaemon = true }.start()"
new = "}, \"http-conn\").start()"
assert s.count(old) == 1, "桩②：没找到连接线程"
s = s.replace(old, new, 1)'
must_red "桩②（连接线程漏标 daemon -> 进程不退）" "HttpApi 每个 Thread 创建点都标了 daemon"
reset_src

# ── 3. 桩③：ioExecutor 不 shutdown ────────────────────────────────────────
# 真实的不关闭 = 那一行**不存在**（不是注释掉 —— 注释里仍有 `shutdownNow` 字样，
# 存在性判据会假绿）。这里把整行删掉。
patch "$EA" '
old = """        ioExecutor.shutdownNow()
"""
assert s.count(old) == 1, "桩③：没找到 shutdown"
s = s.replace(old, "", 1)'
must_red "桩③（ioExecutor 不 shutdown -> 每次重建留一个线程）" "onDestroy 里 shutdownNow"
reset_src

# ── 4. 桩④：shutdown 排到 super.onDestroy() 之后 ──────────────────────────
patch "$EA" '
old = """        ioExecutor.shutdownNow()"""
new = """        // 桩：shutdown 挪到 super 之后
        ioExecutor.shutdownNow2()"""
assert s.count(old) == 1, "桩④：没找到 shutdown"
s = s.replace(old, new, 1)
# 把真正的 shutdownNow 放到 super.onDestroy() 之后
old2 = "        ioExecutor.shutdownNow2()"
# 直接在 super.onDestroy() 后插一行
anchor = "        super.onDestroy()"
assert s.count(anchor) == 1, "桩④：没找到 super.onDestroy"
s = s.replace(anchor, "        ioExecutor.shutdownNow()\n" + anchor, 1)
# 去掉占位那行
s = s.replace(old2 + "\n", "", 1)'
must_red "桩④（shutdown 排在 super 之后）" "shutdown 排在 super.onDestroy() 之前"
reset_src

# ── 5. 全部还原后回绿 ──────────────────────────────────────────────────────
if run_guard; then ck "全部还原后：守卫回绿（桩没有残留污染）" 1
else ck "全部还原后：守卫回绿" 0; fi

echo
echo "=== 线程与资源生命周期守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
