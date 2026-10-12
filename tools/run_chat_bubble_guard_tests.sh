#!/bin/sh
# 自测 `tools/run_chat_bubble_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 `grep`/`awk` 形式的**存在性与结构性**断言，它有三个自欺模式
#（本仓库各栽过一次，所以三个都钉）：
#
#   1. **恒真**：pattern 写成到处都有的文本（例如只 grep `resetKvCache`，
#      而它在设置页那处**本来就存在**），于是把删除路径的作废删掉照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快就有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过 setStreamingText"，而不是
#      "**生成循环里**走的是它" —— 前者在函数定义处就恒真。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：把源码临时改成故障版本，守卫必须变红、且必须指出是哪一条；
# 再改回来，必须恢复全绿。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_kv_cache_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_chat_bubble_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_chat_bubble_guard.sh
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
SS=app/src/main/java/com/xiaowan/localinference/SessionStore.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"; cp "$SS" "$TMP/ss.bak"
restore() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/ss.bak" "$SS"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/act.bak" "$ACT"; cp "$TMP/ss.bak" "$SS"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：流式退回"每 token 重建整屏"（外观不变，只在长会话慢生成时卡）──
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                            ui.post { setStreamingText(ps) }"
new = "                            ui.post { renderChat(ps) }"
assert s.count(old) == 1, "桩①：没找到流式渲染那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（流式退回重建整屏）" "生成循环里走的是 setStreamingText"
reset_src

# ── 2. 桩②：长按闭包捕获下标（列表重绘后删错一条，不可恢复）──────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        wrap.setOnLongClickListener { v ->
            val idx = chatBubbles.indexOf(v)
            if (idx >= 0) confirmDeleteMessage(idx)
            true
        }"""
new = """        wrap.setOnLongClickListener { _ ->
            confirmDeleteMessage(chatBubbles.size - 1)
            true
        }"""
assert s.count(old) == 1, "桩②：没找到长按监听体"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（长按闭包捕获下标）" "下标**当场反查**"
reset_src

# ── 3. 桩③：删一条**又**去全清 KV（= 上一版那个 bug：删末条后复用归零）────
# 这一桩是本轮修的那个真 bug 的复现件：删除路径调 `resetKvCache()`（全清）会把
# 分叉点**之前**还能复用的前缀一起扔了，下一轮从 token 0 全量重算。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                SessionStore.deleteAt(this, currentSessionId, index)
                // 删掉一条**不改 KV**"""
new = """                SessionStore.deleteAt(this, currentSessionId, index)
                LlmEngine.resetKvCache()
                // 删掉一条**不改 KV**"""
assert s.count(old) == 1, "桩③：没找到删除路径那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（删一条又去全清 KV：复用归零）" "删一条**不**全清 KV"
reset_src

# ── 4. 桩④：删除路径夹带 capChars 裁剪（"只删了中间一条，最早的也少了"）──
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        msgs.removeAt(index)
        return writeMessages(ctx, id, msgs)"""
new = """        msgs.removeAt(index)
        capChars(ctx, id, msgs)
        return writeMessages(ctx, id, msgs)"""
assert s.count(old) == 1, "桩④：没找到 deleteAt 的删除那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
# 注意：这一条钉的是**两个**面，桩④ 会同时踩到其中两个判据（"没有夹带裁剪" +
# "append 仍是唯一做裁剪的地方"）。所以期望命中"append 仍是**唯一**"那条 ——
# 它直接报出"裁剪不止一处"，比"writeMessages 里没有 capChars"更能点明根因。
must_red "桩④（删除路径夹带裁剪）" "append() 仍是**唯一**做裁剪的地方"
reset_src

# ── 5. 桩⑤：清空对话不再作废 KV（与桩③同族，但走的是另一个入口）─────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                    // 所以它们**不**作废（否则就是"删末条后 KV 完全没复用"那个 bug）。
                    LlmEngine.resetKvCache()
                    renderChat(null)"""
new = """                    // 所以它们**不**作废（否则就是"删末条后 KV 完全没复用"那个 bug）。
                    renderChat(null)"""
assert s.count(old) == 1, "桩⑤：没找到清空后的作废那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（清空对话后不作废 KV）" "清空对话**才**全清 KV 账本"
reset_src

# ── 6. 桩⑥：可变气泡混进可删除索引（收尾后长按删到一个空位）──────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (!mutable) chatBubbles.add(wrap)"
new = "        chatBubbles.add(wrap)"
assert s.count(old) == 1, "桩⑥：没找到入索引那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（可变气泡混进索引）" "可变气泡不进 chatBubbles"
reset_src

# ── 7. 桩⑦：正文可选中（真机症状：「长按 AI 的输出或者我的输入都没反应」）──
# 这条是从用户实测反馈补进来的 —— 上一版没有它，所以"长按被正文吃掉"这整类
# 失效在源码级守卫里是**无判据的**，只能等真机报出来。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            setPadding(px(8), px(2), px(8), px(2))"
new = "            setTextIsSelectable(true)\n" + old
assert s.count(old) == 1, "桩⑦：没找到正文 padding 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（正文可选中，吃掉长按）" "正文**不**可选中"
reset_src

# ── 8. 桩⑧：气泡没有可见形状（真机症状：「看不到气泡，只是布局有变化」）────
# 只留缩进时它照样编译、照样能删 —— 缺的是"这是一条独立消息"的可见证据。
# 表现正好是用户说的那句：看得到布局变了，看不到气泡。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            background = bubbleBackground(isUser)\n"
new = ""
assert s.count(old) == 1, "桩⑧：没找到气泡底色那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（气泡没有可见形状）" "气泡本体有可见形状"
reset_src

# ── 7. 收尾：还原后必须恢复全绿 ─────────────────────────────────────────────
if run_guard; then ck "收尾：还原源码后守卫恢复全绿" 1
else ck "收尾：还原源码后守卫恢复全绿（实际红了 -> 有桩没还原干净）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 聊天页气泡守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 聊天页气泡守卫自测：PASS $ok / FAIL $bad ==="; exit 1
