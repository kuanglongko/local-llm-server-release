#!/bin/sh
# 自测 `tools/run_rag_edit_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是"某函数体内有没有那一跳"。这一族判据有两种根深蒂固的自欺：
#
#   A. **恒真**：判据只写"出现了某标识符"，而写错的那一支里那个标识符还在。
#      本轮**真抓到 2 处**这种：`doSaveTexts` 里"目标取自 ragDocOwner"（改成
#      `val owner = typed` 后标识符在比对那一行还在）与 `loadRagDoc` 里
#      "有代际计数"（改成不递增后标识符还在）。桩⑥⑦ 就是它们的回归。
#      另一处是"列表标出需重建"：只判"字面量出现过"，把条件改成恒假它照样在（桩⑧）。
#
#   B. **恒假**：判据的 pattern 与源码差一个字符（很快就有人把 exit 1 改成 || true）。
#      桩①~⑤ 各钉一条关键判据的"必红性"。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）。打桩本身走 tools/rag_edit/stub_edit.py
# 的真文件（不是 heredoc：本仓库在 sh 里嵌 python 引号已多次踩坑，
# 而"静默没打成"会让本自测假绿）。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_rag_edit_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_edit_guard.sh
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
STUB=tools/rag_edit/stub_edit.py

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ⚠ 存基线**之前**先断言磁盘上是干净的真源码（否则一次异常退出会把桩当基线存下来，
#    之后每一轮都在错的基础上跑 —— 表现为"明明还原了却一直红"）。
if ! sh "$GUARD" > /dev/null 2>&1; then
    echo "FAIL  跑自测前工作树不干净：基线守卫已经是红的。"
    echo "      先手动还原（git checkout $EA）再跑本自测。"
    exit 2
fi

TMP=$(mktemp -d)
cp "$EA" "$TMP/ea.bak"
restore() { cp "$TMP/ea.bak" "$EA"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}

must_red() { # 桩名 描述 期望命中的 FAIL 关键词
    stub=$1; name=$2; key=$3
    if ! $PY "$STUB" "$EA" "$stub" > "$TMP/stub.out" 2>&1; then
        ck "$name：桩打上了" 0
        sed -n '1,3p' "$TMP/stub.out"
        cp "$TMP/ea.bak" "$EA"
        return
    fi
    ck "$name：桩打上了" 1
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
    cp "$TMP/ea.bak" "$EA"
}

must_green() {
    if run_guard; then ck "$1：守卫全绿" 1
    else ck "$1：守卫全绿（实际红了）" 0; sed -n '1,40p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 桩①：点库不回填（回到阶段 2 之前那个"点库 1 显示库 2 内容"的形态）──
# ⚠ 期望关键词本轮跟着**判据的锚点**改了（ISSUE #259 的 ③）：切库只有一个入口
#   [switchRagDocTo]，`selectRagKb` 只剩转发 —— 判据搬进了那个入口，
#   而"点库不回填"这件事在源码里的位置也一起搬了。
must_red no-reload "桩①（点库不回填）" "switchRagDocTo 没有调用 loadRagDoc"

# ── 桩②：启动不回填（"退出重进后输入框空白"仍然存在）────────────────────
must_red no-restore "桩②（启动不回填）" "没有回填编辑器"

# ── 桩③：保存改成重建（"想保存一下"要等几十秒，用户就不保存了）──────────
must_red save-rebuilds "桩③（保存走重建）" "没有调 LlmEngine.saveTexts"

# ── 桩④：切库不确认（改了没保存就切走，改动无声无息地没了）──────────────
must_red no-dirty-guard "桩④（切库不确认未保存改动）" "没有先确认未保存的改动"

# ── 桩④b：归属为空时直接放行（"没建过库就敲的字"切库时静默消失）─────────
# ⚠ 这一条钉"提前返回的条件里**只有**脏标记"：把 `ragDocOwner.isEmpty()` 也写进去
#    之后，用户还没建库就敲的那些字在切库时无声无息地没了 —— 而对话框一次都不会弹。
must_red skip-no-owner "桩④b（归属为空时放行）" "提前返回把"

# ── 桩⑤：程序化回填也算"用户改过"（确认框立刻变成噪声）──────────────────
must_red dirty-on-load "桩⑤（回填被当成用户编辑）" "没有包住 ragDocLoading"

# ── 桩⑥：保存按库名输入框定目标（A 的正文被整份写进 B）──────────────────
# ⚠ 这一条钉的正是 A 类自欺：改完之后 `ragDocOwner` 仍在比对那一行里出现。
must_red save-by-input "桩⑥（保存按输入框定目标）" "不是取自 ragDocOwner"

# ── 桩⑦：回填不递增代际（快速点两个库会串内容）──────────────────────────
# ⚠ 同 A 类自欺：改成不递增之后标识符还在。
must_red no-gen "桩⑦（回填不记代际）" "没有**递增**代际计数"

# ── 桩⑧：列表不标"需重建"（用户只看到"库在、N 条、检索永远为空"）────────
# ⚠ 同 A 类自欺：条件变恒假，字面量还在文件里。
# ⚠ 本轮这条桩的**期望关键词跟着判据一起改**：列表从三元表达式换成了
#   `when { !hasIndex -> …; !inSync -> … }`（多分了一态，ISSUE #259），
#   而判据现在锚的是"两态各自绑到各自的字段上"——"有索引"那一支仍在，
#   所以桩打掉的正是 inSync 那一支，命中关键词也随之更新。
# ⚠ 本轮锚点又搬了一次（五档状态收进 RagResumeUi.state），所以这条桩的
#   **落点与期望关键词都跟着换**：现在打的是"喂进状态层的字段被换成常量"，
#   判据随之命中的是"没有把 hasIndex/inSync 喂给"那一条 —— 意图不变
#   （让"正文已改"那一支永远不出现）。
must_red no-list-mark "桩⑧（列表不标需重建）" "喂给 RagResumeUi.state"

# ── 桩⑨⑩⑪：状态行必须与检索用**同一判据**，且判在那一支上 ─────────────
# 背景：状态行说"索引就绪"而检索实际不生效（正文已改 / 半份索引），
# 比没有状态行更坏 —— 用户会照着一句骗人的绿字去查模型、查内存。
# 三条判据缺一不可：① 取值来自同一个存储判据；② 存进变量；③ 分支真的看它。
must_red status-other-criterion "桩⑨（状态行另写近似判据）" "状态行没有把 RagIndexStore.textsInSync"

# ⚠ 桩⑩ 钉的是**同一族判据的第二种假绿**：判据只写"体内出现过 textsInSync"时，
#    把取值换成常量 `true` 它照样绿；换成另一条近似判据也照样绿。
must_red status-const-insync "桩⑩（状态行的取值换成常量）" "状态行没有把 RagIndexStore.textsInSync"

# ⚠ 桩⑪ 钉的是本轮**真踩到**的那一处："取值在、分支不看它"。
#    改成 `exists && false ->` 之后 `inSync` 这个名字还在体内 —— 只判
#    "体内出现过该标识符"的写法会**恒绿**（并且箭头后面永远不会有第二个读数，
#    所以判据必须锚在箭头**前**的那个条件上）。
must_red status-branch-ignores "桩⑪（分支不看 inSync）" "没有挂在 inSync 上"

# ── 收尾：还原后必须恢复全绿 ────────────────────────────────────────────
# ── 桩⑫：条目列表退回压紧的 readEntries（下标不再 == 正文行号）────────────
# ⚠ 这是本轮修的"删错一行"的根因形态：压紧后只要正文里出现一个空行或坏行，
#    下标 i 就不再是行号 —— 界面上第 i 行显示的是**另一条**，点它的「删」
#    删掉的是隔壁那一行（空行会被 removeEntry 的 isEmpty 挡住 = "点了没反应"，
#    坏行挡不住 = 真删掉，且此后索引与正文整体错位一格）。
must_red rows-compacted "桩⑫（条目列表退回压紧的 readEntries）" "条目列表没走 readEntriesAligned"
must_red rows-compacted "桩⑫（条目列表退回压紧的 readEntries）" "仍调用压紧的 readEntries"

# ── 桩⑬：列表先 filter 再遍历（下标变成"过滤后的位置"）───────────────────
must_red rows-refilter "桩⑬（列表先 filter 再遍历）" "下标变成"

# ── 桩⑭：跳过空段/坏行时不再 continue（行号会被重排）────────────────────
# "跳过"这件事本身是对的（空段不是条目、坏行不能从列表删），
# 但**跳过的写法**决定了行号保不保得住：`continue` 保留 i，filter/重排则把 i 挪走。
must_red rows-no-continue "桩⑭（跳过的行不用 continue）" "没有 continue"

must_green "收尾：全部桩已还原"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
