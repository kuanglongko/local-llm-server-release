#!/bin/sh
# 自测 `tools/run_log_filter_guard.sh`：把它对着几份**就地改坏的真源码**跑，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据几乎全是 `grep` / `python3 找函数体` 形式的**存在性与结构性**断言，
# 它有三个自欺模式（本仓库各栽过一次，所以三个都钉）：
#
#   1. **恒真**：pattern 写成到处都有的文本。本套件里最容易恒真的一条正是
#      「过滤不在渲染结果上做」—— 写成 `! grep -q 'filter' renderLog` 会连带
#      把正常的 `logFilterKeys` 判成失败（恒假），而写成 `grep -q 'filter'`
#      又永远为真。所以它锚的是 `tail.split/lines/.filter` 这三样渲染后字符串才有的形态。
#   2. **恒假后被静音**：pattern 与源码差一个字符 -> 守卫永远 FAIL -> 有人把
#      `exit 1` 改成 `|| true`，那时它彻底没用了。
#   3. **锚错位置**：钉"文件里出现过某函数"而不是"**在这条路径里**这么做了" ——
#      前者在函数定义处就恒真（`recentLogsUnfiltered` 的定义本身就会满足
#      "导出用了它"，所以那两条判据锚的是**导出按钮那两段代码之间**）。
#
# 桩全部是**真正会写错的那几种写法**（不是编出来的），每一条对应一个具体坏法：
#   · 在渲染出的 120 行上过滤  -> 空白页，用户读成"没打日志"
#   · 判据换成 contains        -> 正文里提到 [内存] 也被捞进来
#   · recentLogs 不过滤        -> chip 点了没反应（最容易被误当"功能没做"）
#   · 导出用过滤后的取数       -> 导出的日志少几段，且没有任何提示
#   · 崩溃现场用过滤后的取数   -> crash_last.txt 可能变空，现场只此一次
#   · 清空不复位口径           -> 下次进来"筛选项还在、一行都没有"
#   · 筛完不复位贴底           -> 最新命中的行不在视野里
#   · 读回的档位不做合法性校验 -> prefs 里的脏值 = 整页空白
#   · 空集合写成"放行 0 行"    -> 一个 chip 都没选 = 一张空白页
#   · chip 点击写成换档（单选）-> 点第二个档顶掉第一个，而"多选"在界面上看不出来
#   · 关键字没 trim            -> 搜两个空格筛掉所有正常行，且说明行说"筛 含「  」"
#   · 「仅错误」单独选时仍按标签收窄 -> 它悄悄变成 HTP 的子集
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_chat_regen_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_log_filter_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_log_filter_guard.sh
LFS=app/src/main/java/com/xiaowan/localinference/LogFileStore.kt
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
APP=app/src/main/java/com/xiaowan/localinference/App.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$LFS" "$TMP/lfs.bak"; cp "$ENG" "$TMP/eng.bak"
cp "$ACT" "$TMP/act.bak"; cp "$APP" "$TMP/app.bak"
restore() { cp "$TMP/lfs.bak" "$LFS"; cp "$TMP/eng.bak" "$ENG"
            cp "$TMP/act.bak" "$ACT"; cp "$TMP/app.bak" "$APP"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/lfs.bak" "$LFS"; cp "$TMP/eng.bak" "$ENG"
              cp "$TMP/act.bak" "$ACT"; cp "$TMP/app.bak" "$APP"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    # 只取 **FAIL 那一行**：同一个判据名可能同时出现在别的行上（比如自证那条），
    # 取 head -1 会取到 PASS 那一行，于是"桩打上了却报成功"。
    if grep -F "$key" "$TMP/guard.out" | grep -q '^FAIL'; then
        line=$(grep -F "$key" "$TMP/guard.out" | grep '^FAIL' | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}

# 就地替换一段源码；断言"只命中一次"，防桩匹配错地方（那样桩根本没打上，
# 测试却会因为"守卫还是绿的"而误判成 PASS）。
patch() { # 文件 python-替换脚本
    "$PY" - "$1" <<EOF
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
$2
open(p, 'w', encoding='utf-8').write(s)
EOF
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：过滤搬到**渲染结果**上（先取尾 120 行再筛 = 空白页）────────────
patch "$ACT" '
old = """        val tail = lines.takeLast(shown).joinToString("\\n")"""
new = """        val tail = lines.takeLast(shown)
            .filter { LogFileStore.LogFilter.matches(LlmEngine.logFilterKeysNow(), it) }
            .joinToString("\\n")"""
assert s.count(old) == 1, "桩①：没找到 renderLog 里取尾那一行"
s = s.replace(old, new, 1)'
must_red "桩①（在渲染出的 120 行上过滤）" "不在渲染结果"
reset_src

# ── 2. 桩②：判据在引用常量的同时**旁路**一个字面量（正文里提到标签也被捞进来）─
# 这是本轮**差点放过**的写法：前三条判据（「某某档用 TAG_* 常量判」）照旧全绿，
# 因为常量引用还在；而多出来的那一支就是静默失真本身。
patch "$LFS" '
old = """                (MEM in keys && l.startsWith(TAG_MEM)) ||"""
new = """                (MEM in keys && (l.startsWith(TAG_MEM) || l.startsWith("[内存]"))) ||"""
assert s.count(old) == 1, "桩②：没找到「内存」档判据"
s = s.replace(old, new, 1)'
must_red "桩②（判据旁路标签字面量）" "不出现任何标签字面量"
reset_src

# ── 3. 桩③：recentLogs 的筛选整段变成**死代码**（chip 点了没反应）──────────
# 这根桩专门打"判据写成三个字符串在不在体内"的写法：那样三个都在、判据全绿，
# 而筛选一次都不会跑。现在的判据锚入口条件与 else 支的形状，所以它会红。
patch "$ENG" '
old = """        if (keys.isEmpty() && kw.isEmpty()) logRing.toList()"""
new = """        if (true) logRing.toList()"""
assert s.count(old) == 1, "桩③：没找到 recentLogs 的取数分支"
s = s.replace(old, new, 1)'
must_red "桩③（ring 侧不再筛选，筛选成了死代码）" "recentLogs 只在两维都空时走全量"
reset_src

# ── 4. 桩④：「导出本次」改用过滤后的取数（导出少几段，无提示）──────────────
patch "$ACT" '
old = """                    LlmEngine.recentLogsUnfiltered().joinToString("\\n")
                }"""
new = """                    LlmEngine.recentLogs().joinToString("\\n")
                }"""
assert s.count(old) == 1, "桩④：没找到导出本次的兜底取数"
s = s.replace(old, new, 1)'
must_red "桩④（导出本次用过滤后的取数）" "导出本次"
reset_src

# ── 5. 桩⑤：崩溃现场改用过滤后的取数（crash_last.txt 可能变空）─────────────
patch "$APP" '
old = """                    val ring = LlmEngine.recentLogsUnfiltered()"""
new = """                    val ring = LlmEngine.recentLogs()"""
assert s.count(old) == 1, "桩⑤：没找到崩溃现场的取数"
s = s.replace(old, new, 1)'
must_red "桩⑤（崩溃现场用过滤后的取数）" "崩溃现场"
reset_src

# ── 6. 桩⑥：清空只清行、不清口径（下次进来"筛选项还在、一行都没有"）────────
patch "$ENG" '
old = """        logFilterKeys = emptySet(); logFilterKeyword = \"\""""
new = """        logFilterKeys = logFilterKeys"""
assert s.count(old) == 1, "桩⑥：没找到 clearLogs 的清理那一行"
s = s.replace(old, new, 1)'
must_red "桩⑥（清空不复位口径）" "clearLogs 体内同时复位两维口径"
reset_src

# ── 7. 桩⑦：筛完不复位贴底标志（最新命中的行不在视野里）───────────────────
patch "$ACT" '
old = """        paintLogFilterChips()
        logFollowBottom = true
        // 筛选是\"换了要显示的内容\""""
new = """        paintLogFilterChips()
        // 筛选是\"换了要显示的内容\""""
assert s.count(old) == 1, "桩⑦：没找到 applyLogFilter 里复位贴底那一行"
s = s.replace(old, new, 1)'
must_red "桩⑦（筛完不复位贴底）" "筛完复位贴底标志"
reset_src

# ── 8. 桩⑧：读回的档位不做合法性校验（prefs 脏值 = 整页空白）───────────────
patch "$ACT" '
old = """            .orEmpty().filter { LogFileStore.LogFilter.labels.contains(it) }"""
new = """            .orEmpty()"""
assert s.count(old) == 1, "桩⑧：没找到读回档位的那一段校验"
s = s.replace(old, new, 1)'
must_red "桩⑧（读回档位不做校验）" "读回的值必须在 labels 里"
reset_src

# ── 9. 桩⑨：空集合写成"放行 0 行"（一个 chip 都没选 = 一张空白页）──────────
patch "$LFS" '
old = """            val byLabel = if (!anyLabel(keys)) true else labelMatch(keys, l)"""
new = """            val byLabel = labelMatch(keys, l)"""
assert s.count(old) == 1, "桩⑨：没找到空集合的那条判据"
s = s.replace(old, new, 1)'
must_red "桩⑨（空集合被当成"全部不放行"）" "空集合不参与标签这一维"
reset_src

# ── 10. 桩⑩：chip 点击写成**换档**（单选残留，多选在界面上看不出来）────────
patch "$ACT" '
old = """        if (!logFilterKeys.remove(label)) logFilterKeys.add(label)"""
new = """        logFilterKeys.clear(); logFilterKeys.add(label)"""
assert s.count(old) == 1, "桩⑩：没找到 toggle 的取反那一行"
s = s.replace(old, new, 1)'
must_red "桩⑩（chip 点击写成换档）" "toggle 是**取反**集合"
reset_src

# ── 11. 桩⑪：关键字没 trim（搜两个空格筛掉所有正常行）──────────────────────
patch "$ACT" '
old = """            keywordEt.text?.toString().orEmpty().trim().take(KEYWORD_MAX) else \"\""""
new = """            keywordEt.text?.toString().orEmpty().take(KEYWORD_MAX) else \"\""""
assert s.count(old) == 1, "桩⑪：没找到 applyLogFilter 里 trim 那一行"
s = s.replace(old, new, 1)'
must_red "桩⑪（关键字没 trim）" "关键字统一 trim"
reset_src

# ── 12. 桩⑫：关键字渗进标签判据（搜标签文字会把别的档带出来）───────────────
patch "$LFS" '
old = """            val byLabel = if (!anyLabel(keys)) true else labelMatch(keys, l)"""
new = """            val byLabel = if (!anyLabel(keys)) true else labelMatch(keys, l) && keywordMatches(\"x\", l)"""
assert s.count(old) == 1, "桩⑫：没找到 matches 里那两行"
s = s.replace(old, new, 1)'
must_red "桩⑫（关键字参与标签判据）" "关键字**不参与**标签判据"
reset_src

# ── 13. 桩⑬：「仅错误」单独选时仍按标签收窄（它悄悄变成 HTP 的子集）────────
patch "$LFS" '
old = """            val byLabel = if (!anyLabel(keys)) true else labelMatch(keys, l)"""
new = """            val byLabel = labelMatch(keys, l) || !anyLabel(keys)"""
assert s.count(old) == 1, "桩⑬：没找到空集合判据"
s = s.replace(old, new, 1)'
must_red "桩⑬（空集合/仅错误那条判据被改宽）" "空集合不参与标签这一维"
reset_src

# ── 14. 桩⑭：界面里出现第二个写口径的地方（半截口径）───────────────────────
patch "$ACT" '
old = """        LlmEngine.setLogFilter(logFilterKeys, kw)"""
new = """        LlmEngine.setLogFilter(logFilterKeys, kw)
        if (kw.length > 200) LlmEngine.setLogFilter(logFilterKeys, kw.take(200))"""
assert s.count(old) == 1, "桩⑭：没找到唯一写点"
s = s.replace(old, new, 1)'
must_red "桩⑭（口径出现第二个写点）" "没有第二个写口径的地方"
reset_src

# ── 15. 桩⑮：`⚠` 被改成只认行首（→「仅错误」在本仓库几乎永远筛不出东西）──────
# 这根桩打的正是本轮**先写错、后来才发现**的那条：真实失败行是
# `[repack] ⚠ …`（标签在前、⚠ 在后），要求 ⚠ 在行首就等于把这一档做成摆设。
# 而"做成摆设"的症状是"筛不出来" —— 与"确实没出错"同形，最难发现。
patch "$LFS" '
old = """            l.line.contains(WARN_MARK) ||"""
new = """            l.startsWith(WARN_MARK) ||"""
assert s.count(old) == 1, "桩⑮：没找到 ⚠ 的行内判据"
s = s.replace(old, new, 1)'
must_red "桩⑮（⚠ 被改成只认行首，「仅错误」成了摆设）" "认行内的 ⚠"
reset_src

# ── 16. 桩⑯：方括号错误标记改成行内查找（正文里提到就被算成错误行）──────────
patch "$LFS" '
old = """                FALLBACK_ERR.any { l.startsWith(it) }"""
new = """                FALLBACK_ERR.any { l.line.contains(it) }"""
assert s.count(old) == 1, "桩⑯：没找到方括号标记的判据"
s = s.replace(old, new, 1)'
must_red "桩⑯（方括号错误标记改成行内查找）" "方括号错误标记只认行首"
reset_src

# ── 17. 桩⑰：把"过滤态只在切页时写正文"的旧写法**真的加回去** ──────────────
# 症状：正文停在旧快照、说明行的命中数一路涨 —— 页面说自相矛盾的话，
# 而"命中 3 行、下面一行都没有"与"日志坏了"不可区分。
# 它**不报错、不异常**，静态看"写过 logTv.text"永远是绿的，所以必须专门钉。
patch "$ACT" '
old = """        if (filtered) {"""
new = """        if (false) { if (justSwitchedTab) logTv.text = tail }   // 桩⑰：旧写法回来了
        if (filtered) {"""
assert s.count(old) == 1, "桩⑰：没找到过滤态分流那一处"
s = s.replace(old, new, 1)'
must_red "桩⑰（旧写法回归：过滤态只在切页时写正文）" "旧写法（过滤态只看 justSwitchedTab 才写正文）必须绝迹"
reset_src

# ── 18. 桩⑱：去掉快照比对，退回"每帧都写"────────────────────────────────────
# 这是"修 D-3 时把它原本的目的改掉"的那条路：正文自洽了，但用户长按复制
# 的那一行会被下一帧无声地清掉。所以"内容没变就不写"这条必须同时在。
patch "$ACT" '
old = """            if (justSwitchedTab || logLastBody != tail) {"""
new = """            if (true) {"""
assert s.count(old) == 1, "桩⑱：没找到内容比对那一处"
s = s.replace(old, new, 1)'
must_red "桩⑱（去掉内容比对，退回每帧都写）" "过滤态写正文的判据是"
reset_src

# ── 19. 收尾：还原后必须恢复全绿 ────────────────────────────────────────────
if run_guard; then ck "收尾：还原源码后守卫恢复全绿" 1
else ck "收尾：还原源码后守卫恢复全绿（实际红了 -> 有桩没还原干净）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 日志筛选守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 日志筛选守卫自测：PASS $ok / FAIL $bad ==="; exit 1
