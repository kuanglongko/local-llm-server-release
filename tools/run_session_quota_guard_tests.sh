#!/bin/sh
# 自测 `tools/run_session_quota_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"。守卫判据全是 grep/awk 的结构性断言，
# 有三个自欺模式（本仓库各栽过一次，所以三个都钉）：
#   1. **恒真**：pattern 写成到处都有的文本，把坏写法改回去照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过某常量"，而不是"**在这条路径里**
#      按这个顺序做了这件事" —— 前者在定义处就恒真。
#
# 桩全部取自 **S3 真正会写错的写法**（不是编出来的）：
#   · 软提示线掺进 decide（退回个数硬卡）   · 闸门挂到 list/load 上（老库打不开）
#   · 导入整批一起判（尾部超限连累前面的）   · create 的判据退回比个数
#   · UI 里写死 2000000（两处漂移）          · SessionQuota 偷偷 import android
#   · acceptCount 跳过收不下的继续试后面的
#
# 桩是"就地改真源码 + 改完还原"，不维护副本 —— 与 run_session_migrate_guard_tests.sh
# 同一套路。不需要工具链，只要 sh + python3。
# 运行：sh tools/run_session_quota_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_session_quota_guard.sh
SS=app/src/main/java/com/xiaowan/localinference/SessionStore.kt
QUOTA=app/src/main/java/com/xiaowan/localinference/SessionQuota.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
TST=tools/session_quota/SessionQuotaTest.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$SS" "$TMP/ss.bak"; cp "$QUOTA" "$TMP/quota.bak"; cp "$ACT" "$TMP/act.bak"; cp "$TST" "$TMP/tst.bak"
restore() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/quota.bak" "$QUOTA"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tst.bak" "$TST"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/quota.bak" "$QUOTA"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tst.bak" "$TST"; }

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

# ── 1. 桩①：软提示线掺进 decide（退回"用个数硬卡"）────────────────────────
"$PY" - "$QUOTA" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚的是**判决式本身**，不是它的上下文 —— 本轮给判决式加了"零增长放行"
#   那一支（`incomingChars <= 0L || ...`），所以这条桩跟着改。
#   桩要证的仍是同一件事：把 MAX_SESSIONS_WARN 掺进判决就变红。
old = "            allowed = incomingChars <= 0L || usedChars + incomingChars <= MAX_TOTAL_CHARS,"
new = "            allowed = (incomingChars <= 0L || usedChars + incomingChars <= MAX_TOTAL_CHARS) && MAX_SESSIONS_WARN > 0,"
assert s.count(old) == 1, "桩①：没找到判决式"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩①（软提示线掺进 decide）" "软提示线不参与"
reset_src

# ── 2. 桩②：闸门挂到读路径上（已超限的老库打不开、也删不掉）────────────────
"$PY" - "$SS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    /** 按更新时间倒序列出全部会话。损坏文件跳过。 */
    @Synchronized
    fun list(ctx: Context): List<Info> {"""
new = """    /** 按更新时间倒序列出全部会话。损坏文件跳过。 */
    @Synchronized
    fun list(ctx: Context): List<Info> {
        if (!SessionQuota.decide(usedChars(ctx), 0L).allowed) return emptyList()"""
assert s.count(old) == 1, "桩②：没找到 list()"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩②（闸门挂到读路径上）" "list()/load()/currentId() 里**没有**配额判据"
reset_src

# ── 3. 桩③：导入整批一起判（尾部超限连累前面能收的也全丢）──────────────────
"$PY" - "$SS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val quota = SessionQuota.acceptCount(usedChars(ctx), sessions.map { charsOf(it) })"""
new = """        val quota = if (SessionQuota.decide(usedChars(ctx), sessions.sumOf { charsOf(it) }).allowed) sessions.size else 0"""
assert s.count(old) == 1, "桩③：没找到导入预算那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩③（导入整批一起判）" "导入按预算算能收几条"
reset_src

# ── 4. 桩④：create 的判据退回比个数（不经过 decide）──────────────────────
"$PY" - "$SS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (!SessionQuota.decide(usedChars(ctx), 0L).allowed) return null"
new = "        if (list(ctx).size >= 200) return null"
assert s.count(old) == 2, "桩④：create/createNoMigrate 的判据不是各一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩④（create 退回比个数）" "create() 的拒绝判据走 SessionQuota.decide"
reset_src

# ── 5. 桩⑤：UI 里写死上限数字（两处漂移的起点）───────────────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'Toast.makeText(this, "会话空间已满（已用 $used/${SessionQuota.MAX_TOTAL_CHARS} 字符），请先删除一些旧会话",'
new = 'Toast.makeText(this, "会话空间已满（已用 $used/2000000 字符），请先删除一些旧会话",'
assert s.count(old) == 1, "桩⑤：没找到新建被拒的提示"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑤（UI 写死上限数字）" "UI 里不出现**写死的上限数字**"
reset_src

# ── 6. 桩⑥：SessionQuota 偷偷 import android（宿主行为测试编不出来）──────
"$PY" - "$QUOTA" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "package com.xiaowan.localinference\n"
new = "package com.xiaowan.localinference\n\nimport android.util.Log\n"
assert s.count(old) == 1, "桩⑥：没找到包声明"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑥（纯逻辑文件偷偷 import android）" "不 import android"
reset_src

# ── 7. 桩⑦：acceptCount 收不下不 break（跳过它继续试后面）────────────────
"$PY" - "$QUOTA" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (used + size > MAX_TOTAL_CHARS) break
            used += size
            n++"""
new = """            if (used + size > MAX_TOTAL_CHARS) continue
            used += size
            n++"""
assert s.count(old) == 1, "桩⑦：没找到 acceptCount 的收下判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑦（导入跳过收不下的、继续试后面的）" "收不下就**停**"
reset_src

# ── 8. 桩⑧：抽掉"零增长放行"那一支（顶到上限后建不出空会话）──────────────
# 这正是本轮修掉的真缺陷：`used == 上限 && incoming == 0` 是 create /
# createNoMigrate 唯一会问的那一格，判成拒绝 = 用户再也建不出落脚点来整理旧会话。
# 桩要证的是"这条放行**真的被判据钉着**"，而不是"守卫里恰好有这么一行字"。
"$PY" - "$QUOTA" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            allowed = incomingChars <= 0L || usedChars + incomingChars <= MAX_TOTAL_CHARS,"
new = "            allowed = usedChars + incomingChars <= MAX_TOTAL_CHARS,   // stub8: 零增长不再放行"
assert s.count(old) == 1, "桩⑧：没找到 decide 的判决式"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑧（零增长不再放行 -> 顶到上限后建不出空会话）" "零增长必须放行"
reset_src

# ── 9. 收尾：桩跑完必须回到全绿 ─────────────────────────────────────────
if run_guard; then ck "收尾：全部还原后守卫仍全绿" 1
else ck "收尾：全部还原后守卫仍全绿（有桩没还原）" 0; fi

echo
echo "=== 会话配额守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
