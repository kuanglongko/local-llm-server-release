#!/bin/sh
# 自测 `tools/run_finish_reason_guard.sh`：把它对着几份**就地改坏的真源码**跑，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据分两类，两类各有自己的自欺模式：
#
#   · **行号/顺序**类（`of()` 的分支顺序、`hitLimit` 在体内置位）—— 这类最容易
#     "锚错位置"：钉"文件里出现过 LENGTH"而不是"LENGTH 排在 cancelled 之后"，
#     于是顺序反了照样绿，而那一档永远不可达。
#   · **计数**类（"两个端点各一次"）—— 这类容易"少判一个端点"：只判文件里
#     出现过 `FinishReason.of` 的话，其中一个端点漏改照样绿，而那个端点上
#     被截断就报 stop。所以每一条都必须是 `-eq 2` 而不是 `-q`。
#
# 桩全部是**真正会写错的那几种写法**，每一条对应一个坏法：
#   · 把 `else "stop"` 三元写回去        -> 被截断时报 stop（就是本 PR 要修的那个）
#   · 只改 handleChat、漏掉 completions -> 两个端点对同一字段给不同答案
#   · 把上限判定挪回收尾（反推 n>=maxTok）-> "最后一步恰好吐一个 token" 与 EOS 混同
#   · of() 的分支顺序把 length 排到 cancelled 前 -> "被取消且正好跑满"报 length
#   · length 档整行删掉                  -> 四档塌成三档
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）——
# 与 run_protocol_fields_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_finish_reason_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_finish_reason_guard.sh
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
FR=app/src/main/java/com/xiaowan/localinference/FinishReason.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"; cp "$FR" "$TMP/fr.bak"
restore() { cp "$TMP/http.bak" "$HTTP"; cp "$TMP/fr.bak" "$FR"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/http.bak" "$HTTP"; cp "$TMP/fr.bak" "$FR"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if ! grep -qF "$key" "$TMP/guard.out"; then
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
        return
    fi
    line=$(grep -F -n "$key" "$TMP/guard.out" | head -1 | cut -d: -f1)
    if sed -n "1,${line}p" "$TMP/guard.out" | grep -q "^FAIL" && \
       awk -v n="$line" 'NR<=n' "$TMP/guard.out" | tail -1 | grep -qE '^(FAIL|        ↳)'; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」（该行既不是 FAIL 也不是 FAIL 的诊断行）" 0
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
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：把 `else "stop"` 三元写回 handleChat（就是本 PR 要修的那个）────
# 这条桩直接证明"守卫抓得住回归"：改回去之后被截断就又报 stop 了。
patch "$HTTP" '
old = """                        val fin = FinishReason.of(cancelled, hitLimit, toolCallsJson != null)"""
new = """                        val fin = if (cancelled) "cancelled" else if (toolCallsJson != null) "tool_calls" else "stop\""""
assert s.count(old) == 2, "桩①：没找到 handleChat 的两处 fin"
s = s.replace(old, new, 1)'
must_red "桩①（handleChat 退回旧三元 -> 被截断报 stop）" "没有残留任何 if (cancelled)"
reset_src

# ── 2. 桩②：只改 handleChat、漏掉 completions 端点 ─────────────────────────
patch "$HTTP" '
old = """                        val fin = FinishReason.of(cancelled, hitLimit, hasToolCalls = false)"""
new = """                        val fin = if (cancelled) "cancelled" else "stop\""""
assert s.count(old) == 1, "桩②：没找到 completions 的 fin"
s = s.replace(old, new, 1)'
must_red "桩②（只改 chat、漏掉 completions -> 两端点答案不同）" "没有残留任何 if (cancelled)"
reset_src

# ── 3. 桩③：completions 的流式收尾帧退回内联 stop ──────────────────────────
patch "$HTTP" '
old = """                        sseEvent(out, compChunkJson(id, modelName, created, "\\"\\"",
                            FinishReason.of(cancelled, hitLimit, hasToolCalls = false)))"""
new = """                        sseEvent(out, compChunkJson(id, modelName, created, "\\"\\"",
                            if (cancelled) "cancelled" else "stop"))"""
assert s.count(old) == 1, "桩③：没找到 completions 的流式收尾"
s = s.replace(old, new, 1)'
must_red "桩③（completions 流式收尾退回内联 stop -> 流式路径没有 length）" "handleCompletion 的流式收尾帧"
reset_src

# ── 4. 桩④：上限判定挪回收尾（反推 n >= maxTok）─────────────────────────────
# 坏法：把体内那支删掉，改成收尾处 `if (n >= maxTok) hitLimit = true`。
patch "$HTTP" '
old = """                        if (n >= maxTok) { hitLimit = true; break }
"""
assert s.count(old) == 2, "桩④：没找到体内上限判定"
s = s.replace(old, "", 1)
anchor = """                    think.flush { t, asReason ->"""
assert s.count(anchor) == 1, "桩④：没找到 chat 收尾"
s = s.replace(anchor, "                    if (n >= maxTok) hitLimit = true\n" + anchor, 1)'
must_red "桩④（上限判定挪回收尾反推 -> EOS 被当成截断）" "hitLimit 是在循环体内置的"
reset_src

# ── 5. 桩⑤：of() 的分支顺序把 length 排到 cancelled 之前 ───────────────────
patch "$FR" '
old = """        cancelled -> CANCELLED
        hitLimit -> LENGTH"""
new = """        hitLimit -> LENGTH
        cancelled -> CANCELLED"""
assert s.count(old) == 1, "桩⑤：没找到 of() 的两支"
s = s.replace(old, new, 1)'
must_red "桩⑤（length 排到 cancelled 前 -> 被取消且跑满时报 length）" "of() 的分支顺序"
reset_src

# ── 6. 桩⑥：把 length 档整行删掉（四档塌成三档）────────────────────────────
patch "$FR" '
old = """        hitLimit -> LENGTH
"""
assert s.count(old) == 1, "桩⑥：没找到 length 档"
s = s.replace(old, "", 1)'
must_red "桩⑥（length 档被删 -> 又回到「被截断报 stop」）" "of() 里找不到"
reset_src

# ── 7. 桩⑦：常量值改掉（`LENGTH = "len"`，SDK 认不出）──────────────────────
patch "$FR" '
old = """    const val LENGTH = "length\""""
new = """    const val LENGTH = "len\""""
assert s.count(old) == 1, "桩⑦：没找到 LENGTH 常量"
s = s.replace(old, new, 1)'
must_red "桩⑦（LENGTH 值改掉 -> OpenAI 枚举对不上）" "有 length 这个取值"
reset_src

# ── 8. 桩⑧：只在一个端点声明 hitLimit（另一个漏了 -> 那个端点翻成 cancelled）──
patch "$HTTP" '
old = """                    var hitLimit = false
"""
assert s.count(old) == 2, "桩⑧：没找到 hitLimit 声明"
s = s.replace(old, "", 1)'
must_red "桩⑧（只留一个端点声明 hitLimit -> 另一端点别名失效）" "两个端点都声明了 hitLimit"
reset_src

# ── 9. 还原后必须全绿 ─────────────────────────────────────────────────────
if run_guard; then ck "还原后：守卫全绿（确认桩没留下痕迹）" 1
else ck "还原后：守卫全绿（实际红了，restore 有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo
echo "=== finish_reason 守卫自测：$ok 通过 / $bad 失败 ==="
[ "$bad" = 0 ]
