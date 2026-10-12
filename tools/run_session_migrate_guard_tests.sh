#!/bin/sh
# 自测 `tools/run_session_migrate_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 grep/awk 形式的**存在性与结构性**断言，它有三个自欺模式
#（本仓库各栽过一次，所以三个都钉）：
#   1. **恒真**：pattern 写成到处都有的文本，于是把坏写法改回去照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快就有人把 `exit 1` 改成 `|| true`，那时它彻底没用了；
#   3. **判据锚错位置**：钉的是"文件里出现过某函数"，而不是"**在这条路径里**
#      按这个顺序做了这件事" —— 前者在定义处就恒真。
#
# 桩全部取自 **S2 真正会写错的写法**（不是编出来的）：
#   · 退回 `createNoMigrate(ctx) ?: return` 静默返回  · 结局塌成一个 Boolean
#   · 把 BLOCKED_BY_LIMIT 与 FAILED 合成一格         · 失败也删旧文件
#   · 调用方吞掉结局（不分支）                       · 对 NO_LEGACY 也出声
#   · 提示改成 Toast 弹一次                           · 分支从 currentId 里偷
#   · 提示与标题分开刷（删完会话提示还挂着）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_session_cap_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_session_migrate_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_session_migrate_guard.sh
SS=app/src/main/java/com/xiaowan/localinference/SessionStore.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
TST=tools/session_migrate/SessionMigrateTest.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$SS" "$TMP/ss.bak"; cp "$ACT" "$TMP/act.bak"; cp "$TST" "$TMP/tst.bak"
restore() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tst.bak" "$TST"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tst.bak" "$TST"; }

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

# ── 1. 桩①：退回旧写法 `createNoMigrate(ctx) ?: return`（静默返回）──────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val id = createNoMigrate(ctx) ?: return MigrateResult.BLOCKED_BY_LIMIT"
new = "        val id = createNoMigrate(ctx) ?: return MigrateResult.FAILED"
assert s.count(old) == 1, "桩①：没找到撞上限那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（撞上限被当成写入失败）" "拿不到空会话（撞上限）时返回 BLOCKED_BY_LIMIT"
reset_src

# ── 2. 桩②：结局塌成 Boolean（说不出为什么，用户无从归因）──────────────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    enum class MigrateResult { OK, NO_LEGACY, BLOCKED_BY_LIMIT, FAILED }"
assert s.count(old) == 1, "桩②：没找到 MigrateResult 四格"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩②（迁移结局塌成 Boolean）" "迁移结局是具名类型"
reset_src

# ── 3. 桩③：把 BLOCKED_BY_LIMIT 与 FAILED 合成一格（处置相反却报同一句）────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    enum class MigrateResult { OK, NO_LEGACY, BLOCKED_BY_LIMIT, FAILED }"
new = "    enum class MigrateResult { OK, NO_LEGACY, BLOCKED_BY_LIMIT }"
assert s.count(old) == 1, "桩③：没找到 MigrateResult 四格"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（上限与写入失败合成一格）" "四种结局都在"
reset_src

# ── 4. 桩④：失败也删旧文件（把"迁移失败"变成"删除成功"）────────────────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            if (!ok) return MigrateResult.FAILED\n"
assert s.count(old) == 1, "桩④：没找到落盘失败判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩④（落盘失败不判、照样删旧文件）" "正文落盘失败时返回 FAILED"
reset_src

# ── 5. 桩④b：删旧文件挪到失败判据**之前**（行序判据的唯一有效对象）─────────
# 只 grep"两个片段都在"是恒真的 —— 挪到前面就变成了"失败也把旧记录删掉"。
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val id = createNoMigrate(ctx) ?: return MigrateResult.BLOCKED_BY_LIMIT
        if (msgs.isNotEmpty()) {"""
new = """        val id = createNoMigrate(ctx) ?: return MigrateResult.BLOCKED_BY_LIMIT
        legacy.delete()
        if (msgs.isNotEmpty()) {"""
assert s.count(old) == 1, "桩④b：没找到 migrateIfNeeded 的开头"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④b（删旧文件挪到失败判据之前）" "删旧文件排在失败判据**之后**"
reset_src

# ── 6. 桩⑤：调用方吞掉结局（不分支，用户还是什么都不知道）──────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            when (SessionStore.migrateIfNeeded(this)) {"
new = "            run { SessionStore.migrateIfNeeded(this) }\n            when (0) {"
assert s.count(old) == 1, "桩⑤：没找到结局认领那处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（调用方吞掉结局不分支）" "调用方按 BLOCKED_BY_LIMIT 分支"
reset_src

# ── 7. 桩⑥：对 NO_LEGACY 也出声（每次冷启动都"迁移失败"，用户会去删数据）──
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                SessionStore.MigrateResult.OK, SessionStore.MigrateResult.NO_LEGACY -> Unit"
new = "                SessionStore.MigrateResult.OK, SessionStore.MigrateResult.NO_LEGACY ->\n                    migrateHint = \"⚠ 旧聊天记录迁移失败\""
assert s.count(old) == 1, "桩⑥：没找到不出声那一支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（没有旧文件也报"迁移失败"）" "NO_LEGACY 与 OK 落在不出声的那一支"
reset_src

# ── 8. 桩⑦：提示改成 Toast 弹一次（冷启动那一瞬漏掉就再也不出现）───────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    private var migrateHintTv: TextView? = null"
new = "    private var migrateHintToast = false"
assert s.count(old) == 1, "桩⑦：没找到常驻提示字段"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（提示退回 Toast 弹一次）" "提示是页面上的常驻行"
reset_src

# ── 9. 桩⑧：结局从 currentId 里偷（读取路径单报 → 提示自己消失又冒出来）────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        migrateIfNeeded(ctx)\n"
new = "        SessionStore.lastMigrate = migrateIfNeeded(ctx)\n"
assert s.count(old) == 1, "桩⑧：没找到 currentId 里那次调用"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（读取路径也认领结局）" "currentId() 里那次迁移调用**独自取值**"
reset_src

# ── 10. 桩⑨：提示与标题分开刷（删完会话回聊天页，提示还挂着）───────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        // 提示与标题**同一个渲染点**：分开刷会出现"删完会话回到聊天页，
        // 会话已经建好了、提示还挂着"这种自相矛盾的画面。
        migrateHintTv?.let { tv ->
            val hint = migrateHint
            if (hint.isNullOrEmpty()) tv.visibility = View.GONE
            else { tv.text = hint; tv.visibility = View.VISIBLE }
        }
"""
assert s.count(old) == 1, "桩⑨：没找到提示渲染那几行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩⑨（提示与标题分开刷）" "提示与标题**同一个渲染点**"
reset_src

# ── 11. 桩⑩：把"失败不删旧文件"退化成一条**看着像在钉、其实恒真**的断言 ────
# 这正是本仓库最爱的失败形态：断言还在、名字还在，只是判据退化成"文件非空"。
"$PY" - "$TST" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "File(d2, \"chat_session.json\").readText() == original2"
assert s.count(old) == 1, "桩⑩：没找到失败时旧文件仍在那条断言"
open(p, 'w', encoding='utf-8').write(s.replace(old, "File(d2, \"chat_session.json\").readText().isNotEmpty()", 1))
EOF
must_red "桩⑩（失败不变量退化成\"文件非空\"）" "行为测试钉住「失败时旧文件逐字节不变」"
reset_src

# ── 12. 桩⑪：行为测试退回 `File.createTempFile` 当目录用（往工作区落垃圾）─────
"$PY" - "$TST" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    val root = File(System.getProperty("java.io.tmpdir"), "mig-test-" + System.nanoTime())'
new = '    val root = File.createTempFile("mig", "").let { it.delete(); it }'
assert s.count(old) == 1, "桩⑪：没找到临时目录那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑪（行为测试用 createTempFile 当目录）" "不静默落垃圾到工作区"
reset_src

# ── 13. 还原确认：桩跑完必须回到全绿 ────────────────────────────────────────
if run_guard; then ck "收尾：全部还原后守卫仍全绿" 1
else ck "收尾：全部还原后守卫仍全绿（有桩没还原）" 0; fi
# 判据是"与备份逐字节相同"，不用 `git diff --quiet`：开发机上改这三个文件本来就是
# 本任务的正常状态，git 一比对就恒红 —— **恒红的收尾判据等于没有收尾判据**。
# 备份才是"跑之前的样子"。
if cmp -s "$TMP/ss.bak" "$SS" && cmp -s "$TMP/act.bak" "$ACT" && cmp -s "$TMP/tst.bak" "$TST"; then
    ck "收尾：源码与测试文件逐字节回到跑之前的样子" 1
else
    ck "收尾：源码与测试文件逐字节回到跑之前的样子" 0
fi

echo
if [ "$bad" = "0" ]; then echo "=== 会话迁移守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 会话迁移守卫自测：PASS $ok / FAIL $bad ==="; exit 1
