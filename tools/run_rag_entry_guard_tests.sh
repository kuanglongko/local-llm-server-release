#!/bin/sh
# 自测 `tools/run_rag_entry_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据全是"某函数体内有没有那一跳 / 是不是那个取值"。这一族判据有两种
# 根深蒂固的自欺：
#
#   A. **恒真**：判据只写"出现了某标识符"，而写错的那一支里那个标识符还在。
#      本轮**真抓到 1 处**：`check_entry_wiring.py status-count` 首版用
#      `kotlin_fn_body` 判 `textsCount` —— 而这个抽取器会把字符串字面量抹成 `""`，
#      于是"算了条目数"与"算出来放进 parts"看起来一模一样（判据恒假）。
#      桩⑫ 就是它的回归。
#
#   B. **恒假**：判据的 pattern 与源码差一个字符（很快就有人把 exit 1 改成 || true）。
#      桩①~⑪ 各钉一条关键判据的"必红性"。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）。打桩本身走 tools/rag_edit/stub_entry.py
# 的真文件（不是 heredoc：本仓库在 sh 里嵌 python 引号已多次踩坑，
# 而"静默没打成"会让本自测假绿）。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_rag_entry_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_entry_guard.sh
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
STUB=tools/rag_edit/stub_entry.py

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ⚠ 存基线**之前**先断言磁盘上是干净的真源码（否则一次异常退出会把桩当基线存下来，
#    之后每一轮都在错的基础上跑 —— 表现为"明明还原了却一直红"）。
if ! sh "$GUARD" > /dev/null 2>&1; then
    echo "FAIL  跑自测前工作树不干净：基线守卫已经是红的。"
    echo "      先手动还原（git checkout $EA $ENGINE $STORE）再跑本自测。"
    exit 2
fi

TMP=$(mktemp -d)
cp "$EA" "$TMP/ea.bak"; cp "$ENGINE" "$TMP/engine.bak"; cp "$STORE" "$TMP/store.bak"
restore() { cp "$TMP/ea.bak" "$EA"; cp "$TMP/engine.bak" "$ENGINE"; cp "$TMP/store.bak" "$STORE"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}

# must_red 桩名 目标文件 描述 期望命中的 FAIL 关键词
must_red() {
    stub=$1; file=$2; name=$3; key=$4
    if ! $PY "$STUB" "$file" "$stub" > "$TMP/stub.out" 2>&1; then
        ck "$name：桩打上了" 0
        sed -n '1,3p' "$TMP/stub.out"
        cp "$TMP/ea.bak" "$EA"; cp "$TMP/engine.bak" "$ENGINE"; cp "$TMP/store.bak" "$STORE"
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
    cp "$TMP/ea.bak" "$EA"; cp "$TMP/engine.bak" "$ENGINE"; cp "$TMP/store.bak" "$STORE"
}

must_green() {
    if run_guard; then ck "$1：守卫全绿" 1
    else ck "$1：守卫全绿（实际红了）" 0; sed -n '1,40p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 桩①：追加不编码（退回"标脏等重建"，增量编辑名存实亡）────────────────
must_red no-encode "$ENGINE" "桩①（追加不编码）" "addEntry 没有编码这一条"

# ── 桩②：算了向量却不交给 Entry（索引里不会有这一条，界面却说成功）──────
# ⚠ 这一条钉 A 类自欺：判据若只写"出现过 embed("，`val v = null` 之后照样绿。
must_red drop-vector "$ENGINE" "桩②（向量没交给存储层）" "算了向量却没把它交给 Entry"

# ── 桩③：rows 改成"旧行数 + 追加条数"（有条目没算出向量时多算）──────────
must_red rows-add-count "$STORE" "桩③（rows 用累加而不是现算）" "没有按新正文现算行数"

# ── 桩④：指纹不带行数（"索引落后于正文"照样可用）───────────────────────
must_red rows-not-fp "$STORE" "桩④（指纹不带行数）" "指纹没用"

# ── 桩⑤：没算出的向量也进向量区（后面每一行都错位）──────────────────────
must_red vec-all "$STORE" "桩⑤（没算出的向量也进向量区）" "维度对得上才进向量区"

# ── 桩⑥：先写正文后写索引（失败留下"前 N-1 行都对"的错位）──────────────
must_red texts-first "$STORE" "桩⑥（先写正文后写索引）" "先写正文后写索引"

# ── 桩⑦：不校验"指纹行数 == 正文行数 == count"────────────────────────────
must_red no-rows-check "$STORE" "桩⑦（不校验行数一致）" "三者一致性判据不完整"

# ── 桩⑧：搬向量时不跳过被删的那一行（正文少一行、向量多一行）────────────
must_red no-skip-line "$STORE" "桩⑧（搬向量时不跳过被删行）" "没有跳过被删的那一行"

# ── 桩⑨：去掉"搬运数 != 预期即拒"的兜底 ────────────────────────────────
must_red no-dst-guard "$STORE" "桩⑨（去掉搬运数兜底）" "搬运数 != 预期就不落盘"

# ── 桩⑩：删除按"第几个视图"而不是记下来的行号 ──────────────────────────
# ⚠ 判据锚 `confirmDeleteEntry(i`（捕获到的行号）而不是"有没有 confirmDeleteEntry"。
must_red del-by-view "$EA" "桩⑩（删除按视图下标）" "传的不是**捕获到的行号**"

# ── 桩⑪：追加顺手覆盖编辑器里的草稿 ────────────────────────────────────
must_red append-clobbers-draft "$EA" "桩⑪（追加覆盖草稿）" "里出现了 setRagDocText"

# ── 桩⑦b：去掉 rows -> count 的口径换算（"以为两者相等"）────────────────
# ⚠ 这一条钉的是**口径本身**：`rows`（段数）与 `count`（条目数）差 1 是刻意的，
#    写在一起当相等会让每一份正常索引都被判成"索引落后于正文"（每次都重建），
#    而那只是慢 —— 慢的东西没人查。
must_red no-rows-to-count "$STORE" "桩⑦b（去掉 rows->count 口径换算）" "三者一致性判据不完整"

# ── 桩⑫：状态行不报条目数 ──────────────────────────────────────────────
# ⚠ 这一条钉 A 类自欺：`kotlin_fn_body` 会抹掉字符串字面量，判据若用它取
#    条数取值就只能判"出现过"，而"算出来不放进 parts"照样绿。
#    判据已改成读**原文区间** + 锚 `parts.add(...)` 那一行。
# ⚠ 期望命中的关键词跟着判据改了字面：判据现在锚**那一行的完整形态**
#   `if (exists && cnt != null) parts.add("$cnt 条")` —— 因为"正文在、索引待建"
#   那一支里也报了条数，只判"出现过条数取值"在把这一行整个删掉之后照样绿。
#   `0.9.195` 把取值来源（`RagIndexStore.textsCount` -> 快照 `cnt`）换了，
#   判据与桩一起跟着改；"报没报这个数"这条语义没松。
must_red no-status-count "$EA" "桩⑫（状态行不报条目数）" "状态行算了条目数却没放进 parts"

# ── 桩⑬：追加超长不判"是否被截断"（于是无从提示）────────────────────────
must_red no-truncated-flag "$ENGINE" "桩⑬（不判截断）" "没有把「是否被截断」单独判出来"

# ── 桩⑭：判了截断却不放进成功回执（用户以为整条进去了）─────────────────
must_red no-truncated-note "$ENGINE" "桩⑭（截断不提示）" "成功路径没有按 truncated 分流"

# ── 收尾：还原后必须恢复全绿 ────────────────────────────────────────────
must_green "收尾：全部桩已还原"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
