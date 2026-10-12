#!/bin/sh
# 自测 `tools/run_rag_progress_guard.sh`：拿几份**打桩源码树**跑它，
# 断言"该红的红、且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是**结构关系**（前缀出现在几处 / 哪口钟做差 / 哪一处写最后
# 读数 / 两份格式化对边界值给出什么），而在**正确**的源码上它们全绿 ——
# 一个永远绿的判据与没有判据在效果上一样。
#
# 桩全部取自"真正会写错的写法"（本轮那个真故障 + 四个同族）：
#   · 收方另写一份前缀副本（**本轮真故障**：多"逐条"两个字 -> startsWith 恒假）；
#   · 开工时刻用墙钟做差（NTP 跳变把"已耗时"拉歪甚至算负）；
#   · 切页那一跳退回一句静态话（用户报的"建索引提示丢失"原样复现）；
#   · 收工不清最后读数（下一次切页带出过期读数：已完成的库像"还在跑"）；
#   · native 那份格式化不补零（"日志 1:23、界面 1:5"这种无迹象的漂移）。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_progress_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_progress_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ACT=$SRC/EngineActivity.kt
ENG=$SRC/LlmEngine.kt
PROG=$SRC/RagProgress.kt
ETH=app/src/main/cpp/embed_text.h

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$ENG" "$TMP/eng.bak"
cp "$PROG" "$TMP/prog.bak"; cp "$ETH" "$TMP/eth.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/eng.bak" "$ENG";
            cp "$TMP/prog.bak" "$PROG"; cp "$TMP/eth.bak" "$ETH"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/eng.bak" "$ENG";
              cp "$TMP/prog.bak" "$PROG"; cp "$TMP/eth.bak" "$ETH"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

stub_py() { "$PY" - "$@" || { echo "⚠ 打桩脚本失败（锚点漂了？）—— 该桩将被跳过" >&2; return 1; }; }

# ⚠ 只认**带 FAIL 前缀**的那一行：本守卫里有若干对"一正一反"的判据，
# 文案开头是同一串字 —— 直接 grep 整份输出会先命中那条 PASS 行，
# 于是这条桩自测会把"判据确实点到了"报成失败。桩自测假红的下场是没人再看它。
must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    line=$(grep -E "^FAIL " "$TMP/guard.out" | grep -F "$key" | head -1)
    if [ -n "$line" ]; then ck "$name：指到了「$key」" 1
    else ck "$name：指到了「$key」（没有任何 FAIL 行命中它）" 0; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi
# ── 1. 桩①：收方另写一份前缀副本（**本次真故障**）───────────────────────────
# 这正是本轮要修的东西本身。收方那份多写两个字时，`startsWith` 恒假：
# 进度从来没走到进度行，每一行都落进"2000 行 ring 全量重排"那条闸。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        private val PROGRESS_PREFIX = RagProgress.PREFIX\n'
assert s.count(old) == 1, "桩①：没找到收方取前缀那一行"
new = '        private const val PROGRESS_PREFIX = "[知识库] 正在逐条编码："\n'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩①（收方另写一份前缀副本）" "不是取 RagProgress.PREFIX"
reset_src

# ── 2. 桩②：开工时刻用墙钟做差 ─────────────────────────────────────────────
# 症状：NTP 跳变/用户改时间把"已耗时"拉歪，甚至算成负数 ——
# 而"已耗时"是用户手上唯一的参照物（一条挂几十分钟的读数）。
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        val buildT0 = System.nanoTime()\n'
assert s.count(old) == 1, "桩②：没找到开工时刻那一行"
new = '        val buildT0 = System.currentTimeMillis()\n'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩②（开工时刻用墙钟）" "System.nanoTime()"
reset_src

# ── 3. 桩③：切页那一跳退回一句静态话 ───────────────────────────────────────
# 症状 = 用户报的第 ④ 条原样复现："切换页面后，**建索引提示丢失**"——
# 那条提示的价值全在"它还在走"这个判断上，静止的一句话把这个判断交回用户。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                        append(lastProgressBody.ifEmpty {'
assert s.count(old) == 1, "桩③：没找到切页带读数那一行"
new = '                        append("".ifEmpty {'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩③（切页退回静态话）" "切页会退回一句静态话"
reset_src

# ── 4. 桩④：收工不清最后读数 ───────────────────────────────────────────────
# 症状：下一次切页把一句早已过期的读数带出来，一个**已完成的**库看起来像
# "还在跑"—— 与"骗人的绿字"同族的"骗人的进度字"。
stub_py "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                lastProgressBody = ""\n'
assert s.count(old) == 1, "桩④：没找到收工清读数那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, '', 1))
PYSTUB
must_red "桩④（收工不清最后读数）" "收工后没有把 lastProgressBody 清空"
reset_src

# ── 5. 桩⑤：native 那份格式化不补零 ───────────────────────────────────────
# 症状："日志里 1:23、界面上 1:5" —— 两者都"没错"，只是说的不是同一件事，
# 而这种漂移**没有任何迹象**（要等到有人同时在真机上看日志和屏幕才发现）。
stub_py "$ETH" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        snprintf(out, cap, "%lld:%02lld", m, s);\n'
assert s.count(old) == 1, "桩⑤：没找到 native 的分档格式串"
new = '        snprintf(out, cap, "%lld:%lld", m, s);\n'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤（native 格式化不补零）" "秒位必须补零"
reset_src

# ── 6. 桩⑥：发方传 `i + 1`（进度提前报满）────────────────────────────────
# 症状：最后一批走到 `end == texts.size` 之前就报 `210/210 条（100.0%）`，
# 用户据此以为可以走了，而那一批还没编完 —— 差 1 看起来无害，落在**最后一条**上
# 就是"提前整整一批报告完成"。
stub_py "$ENG" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                i, totalChunks, (System.nanoTime() - buildT0) / 1_000_000L) + "（$name）")'
assert s.count(old) == 1, "桩⑥：没找到发方拼进度那一处"
new = '                i + 1, totalChunks, (System.nanoTime() - buildT0) / 1_000_000L) + "（$name）")'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑥（发方传 i+1，进度提前报满）" "提前报满"
reset_src

# ── 收尾：还原之后守卫必须又是绿的（防"某一桩的还原漏了"）────────────────
if run_guard; then ck "收尾：所有桩还原之后守卫重新全绿" 1
else ck "收尾：所有桩还原之后守卫重新全绿" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 建索引进度读数守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 建索引进度读数守卫自测：PASS $ok / FAIL $bad ==="; exit 1
